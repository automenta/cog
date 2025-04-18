package dumb.cognote3;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A probabilistic, online, iterative beam-search based SUMO KIF reasoner
 * driven by dynamic knowledge base changes via WebSockets, with callback support,
 * integrated with a Swing UI for Note->KIF distillation via LLM.
 *
 * Delivers a single, consolidated, refactored, and corrected Java file.
 */
public class ProbabilisticKifReasoner extends WebSocketServer {

    private static final int UI_FONT_SIZE = 16;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);

    private final int beamWidth;
    private final int maxKbSize;
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl;
    private final String llmModel;

    private final ConcurrentMap<String, ConcurrentMap<KifList, Assertion>> factIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
    private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private final Set<KifList> kbContentSnapshot = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<Assertion> kbPriorityQueue = new PriorityBlockingQueue<>();
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>();

    private final PriorityBlockingQueue<InputMessage> inputQueue = new PriorityBlockingQueue<>();

    private final PriorityQueue<PotentialAssertion> beam = new PriorityQueue<>();
    private final Set<KifList> beamContentSnapshot = ConcurrentHashMap.newKeySet();
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();

    private final ExecutorService reasonerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ReasonerThread"));
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "LLMThread"));
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private volatile boolean running = true;
    private final SwingUI swingUI;

    public ProbabilisticKifReasoner(int port, int beamWidth, int maxKbSize, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.beamWidth = Math.max(1, beamWidth);
        this.maxKbSize = Math.max(10, maxKbSize);
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = Objects.requireNonNullElse(llmUrl, "http://localhost:11434/api/chat");
        this.llmModel = Objects.requireNonNullElse(llmModel, "hf.co/mradermacher/phi-4-GGUF:Q4_K_S");
        this.swingUI = Objects.requireNonNull(ui, "SwingUI cannot be null");
        System.out.printf("Reasoner config: Port=%d, Beam=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s%n",
                          port, this.beamWidth, this.maxKbSize, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel);
        // Internal broadcast callback removed - broadcasting handled directly in invokeCallbacks
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            int port = 8887, beamWidth = 10, maxKbSize = 64*1024;
            String rulesFile = null, llmUrl = null, llmModel = null;
            var broadcastInput = false;

            for (var i = 0; i < args.length; i++) {
                try {
                    switch (args[i]) {
                        case "-p", "--port" -> port = Integer.parseInt(args[++i]);
                        case "-b", "--beam" -> beamWidth = Integer.parseInt(args[++i]);
                        case "-k", "--kb-size" -> maxKbSize = Integer.parseInt(args[++i]);
                        case "-r", "--rules" -> rulesFile = args[++i];
                        case "--llm-url" -> llmUrl = args[++i];
                        case "--llm-model" -> llmModel = args[++i];
                        case "--broadcast-input" -> broadcastInput = true;
                        default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                    }
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    System.err.println("Error parsing argument for " + (i > 0 ? args[i-1] : args[i]) + ": " + e.getMessage());
                    printUsageAndExit();
                } catch (Exception e) {
                    System.err.println("Error parsing args: " + e.getMessage());
                    printUsageAndExit();
                }
            }

            ProbabilisticKifReasoner server = null;
            SwingUI ui = null;
            try {
                ui = new SwingUI(null);
                server = new ProbabilisticKifReasoner(port, beamWidth, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.reasoner = server;
                final var finalServer = server;

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    if (finalServer != null) finalServer.stopReasoner();
                }));

                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified.");

                server.startReasoner();
                ui.setVisible(true);

            } catch (IllegalArgumentException e) {
                System.err.println("Configuration Error: " + e.getMessage());
                if (ui != null) ui.dispose();
                System.exit(1);
            } catch (IOException | ParseException e) {
                System.err.println("Error loading initial file: " + e.getMessage());
                if (ui != null) ui.dispose();
                System.exit(1);
            } catch (Exception e) {
                System.err.println("Failed to initialize/start: " + e.getMessage());
                e.printStackTrace();
                if (ui != null) ui.dispose();
                System.exit(1);
            }
        });
    }

    private static void printUsageAndExit() {
        System.err.println("""
        Usage: java ProbabilisticKifReasoner [options]
        Options:
          -p, --port <port>           WebSocket server port (default: 8887)
          -b, --beam <beamWidth>      Beam search width (default: 10)
          -k, --kb-size <maxKbSize>   Max KB assertion count (default: 1000)
          -r, --rules <rulesFile>     Path to file with initial KIF rules/facts
          --llm-url <url>             URL for the LLM API (default: http://localhost:11434/api/chat)
          --llm-model <model>         LLM model name (default: llama3)
          --broadcast-input           Broadcast input assertions via WebSocket""");
        System.exit(1);
    }

    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        var message = switch (type) {
            case "assert-added" -> String.format("assert-derived %.4f %s", assertion.probability(), kifString);
            case "assert-input" -> String.format("assert-input %.4f %s", assertion.probability(), kifString);
            case "assert-retracted" -> String.format("retract %s", assertion.id());
            case "evict" -> String.format("evict %s", assertion.id());
            default -> type + " " + kifString;
        };
        try {
            //if (this.server != null && !this.server.isClosed())
                broadcast(message);
            //else System.out.println("WS broadcast skipped (server not ready/closed): " + message);
        } catch (Exception e) {
            System.err.println("Error during WebSocket broadcast: " + e.getMessage());
        }
    }

    public void loadExpressionsFromFile(String filename) throws IOException, ParseException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        long processedCount = 0, queuedCount = 0;
        var kifBuffer = new StringBuilder();
        var parenDepth = 0;
        var lineNumber = 0;

        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                var commentStart = line.indexOf(';');
                if (commentStart != -1) line = line.substring(0, commentStart);
                line = line.trim();
                if (line.isEmpty()) continue;

                parenDepth += line.chars().filter(c -> c == '(').count() - line.chars().filter(c -> c == ')').count();
                kifBuffer.append(line).append(" ");

                if (parenDepth == 0 && kifBuffer.length() > 0) {
                    var kifText = kifBuffer.toString().trim();
                    kifBuffer.setLength(0);
                    if (!kifText.isEmpty()) {
                        processedCount++;
                        try {
                            var terms = KifParser.parseKif(kifText);
                            for (var term : terms) {
                                queueExpressionFromSource(term, "file:" + filename);
                                queuedCount++;
                            }
                        } catch (ParseException e) {
                            System.err.printf("File Parse Error (line ~%d): %s near '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), 50)));
                        } catch (Exception e) {
                             System.err.printf("File Queue Error (line ~%d): %s for '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), 50)));
                        }
                    }
                } else if (parenDepth < 0) {
                    System.err.printf("Mismatched parentheses near line %d: '%s'%n", lineNumber, line);
                    parenDepth = 0;
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d expressions from %s, queued %d items.%n", processedCount, filename, queuedCount);
    }

    private void queueExpressionFromSource(KifTerm term, String sourceId) {
         if (term instanceof KifList list) {
            var opOpt = list.getOperator();
            if (opOpt.isPresent()) {
                String op = opOpt.get();
                switch (op) {
                    case "=>":
                    case "<=>":
                        inputQueue.put(new RuleMessage(list.toKifString(), sourceId));
                        break;
                    case "exists":
                        handleExists(list, sourceId);
                        break;
                    case "forall":
                        handleForall(list, sourceId);
                        break;
                    default:
                        // Handle ground facts (no variables)
                        if (!list.terms().isEmpty() && !list.containsVariable()) {
                            inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                        } else {
                             System.err.println("Warning: Ignoring non-rule/quantified list containing variables from " + sourceId + ": " + list.toKifString());
                        }
                        break;
                }
            } else {
                 System.err.println("Warning: Ignoring list without operator constant from " + sourceId + ": " + list.toKifString());
            }
        } else {
             System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
        }
    }

    private void handleExists(KifList existsExpr, String sourceId) {
        if (existsExpr.size() != 3) {
             System.err.println("Invalid 'exists' format (requires var(s) and body): " + existsExpr.toKifString());
             return;
        }
        KifTerm varsTerm = existsExpr.get(1);
        KifTerm body = existsExpr.get(2);
        List<KifVariable> variables;

        if (varsTerm instanceof KifList varList) {
            variables = varList.terms().stream()
                .filter(KifVariable.class::isInstance).map(KifVariable.class::cast)
                .toList();
             if (variables.size() != varList.size()) { // Ensure all elements were variables
                 System.err.println("Invalid variable list in 'exists' (contains non-variables): " + varList.toKifString());
                 return;
             }
        } else if (varsTerm instanceof KifVariable singleVar) {
            variables = List.of(singleVar);
        } else {
             System.err.println("Invalid variable specification in 'exists' (expected variable or list): " + varsTerm.toKifString());
             return;
        }

        if (variables.isEmpty()) {
            System.err.println("Warning: 'exists' with no variables: " + existsExpr.toKifString() + ". Processing body directly.");
            queueExpressionFromSource(body, sourceId + "-existsBody");
            return;
        }

        Map<KifVariable, KifTerm> skolemBindings = new HashMap<>();
        for (KifVariable var : variables) {
            String skolemName = "skolem_" + var.name().substring(1) + "_" + idCounter.incrementAndGet();
            skolemBindings.put(var, new KifConstant(skolemName));
        }

        KifTerm skolemizedBody = Unifier.substitute(body, skolemBindings);
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);
        queueExpressionFromSource(skolemizedBody, sourceId + "-skolemized");
    }

    private void handleForall(KifList forallExpr, String sourceId) {
         if (forallExpr.size() != 3) {
              System.err.println("Invalid 'forall' format (requires var(s) and body): " + forallExpr.toKifString());
              return;
         }
         KifTerm body = forallExpr.get(2);

         // Interpret (forall (vars...) (=> ant con)) as rule (=> ant con)
         if (body instanceof KifList bodyList && bodyList.getOperator().filter("=>"::equals).isPresent() && bodyList.size() == 3) {
             System.out.println("Interpreting 'forall ... (=> ant con)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
             inputQueue.put(new RuleMessage(bodyList.toKifString(), sourceId + "-forallRule"));
         } else {
             System.err.println("Warning: Ignoring 'forall' expression with non-'=>' body structure: " + forallExpr.toKifString());
         }
     }


    public void submitMessage(InputMessage message) {
        inputQueue.put(message);
    }

    public void startReasoner() {
        running = true;
        reasonerExecutor.submit(this::reasonerLoop);
        try {
            start();
            System.out.println("WebSocket server thread started on port " + getPort());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage() + ". Reasoner running without WebSocket.");
        }
    }

    public void stopReasoner() {
        if (!running) return;
        System.out.println("Stopping reasoner and services...");
        running = false;

        shutdownExecutor(reasonerExecutor, "Reasoner");
        shutdownExecutor(llmExecutor, "LLM");

        try {
            stop(1000);
            System.out.println("WebSocket server stopped.");
        } catch (InterruptedException e) {
            System.err.println("Interrupted while stopping WebSocket server.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Error stopping WebSocket server: " + e.getMessage());
        }
        System.out.println("Reasoner stopped.");
    }

     private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println(name + " executor did not terminate gracefully, forcing shutdown.");
                executor.shutdownNow();
                 if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                      System.err.println(name + " executor did not terminate after forced shutdown.");
            } else {
                 System.out.println(name + " executor stopped gracefully.");
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for " + name + " executor shutdown.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("WS Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + (reason.isEmpty() ? "N/A" : reason));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String remoteAddress = (conn != null) ? conn.getRemoteSocketAddress().toString() : "server";
        // Log common network errors concisely, others with more detail
        if (ex instanceof IOException || ex.getMessage().contains("Socket closed") || ex.getMessage().contains("Connection reset")) {
             System.err.println("WS Network Error from " + remoteAddress + ": " + ex.getMessage());
        } else {
            System.err.println("WS Logic Error from " + remoteAddress + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void onStart() {
        System.out.println("Reasoner WebSocket listener active on port " + getPort() + ".");
        setConnectionLostTimeout(100); // 100 seconds timeout
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        var trimmed = message.trim();
        var sourceId = conn.getRemoteSocketAddress().toString();
        try {
            if (trimmed.startsWith("(")) {
                var terms = KifParser.parseKif(trimmed);
                if (terms.isEmpty()) throw new ParseException("Empty KIF message received.");
                if (terms.size() > 1) System.err.println("Warning: Multiple top-level KIF expressions in one WS message, processing all.");

                for(var term : terms) {
                    if (term instanceof KifList list) {
                        var op = list.getOperator();
                        if (op.isPresent()) {
                            switch (op.get()) {
                                case "retract-id":
                                    if (list.size() == 2 && list.get(1) instanceof KifConstant id)
                                        submitMessage(new RetractByIdMessage(id.value(), sourceId));
                                    else throw new ParseException("Invalid retract-id format: Expected (retract-id \"id\")");
                                    break;
                                case "retract-rule":
                                    if (list.size() == 2 && list.get(1) instanceof KifList ruleForm)
                                        submitMessage(new RetractRuleMessage(ruleForm.toKifString(), sourceId));
                                    else throw new ParseException("Invalid retract-rule format: Expected (retract-rule (rule-form))");
                                    break;
                                case "=>":
                                case "<=>":
                                    submitMessage(new RuleMessage(list.toKifString(), sourceId));
                                    break;
                                case "exists": // Allow exists/forall from WebSocket? Yes.
                                case "forall":
                                     queueExpressionFromSource(list, sourceId); // Use existing handler
                                     break;
                                default: // Assume assertion (ground or not)
                                    if (!list.terms().isEmpty()) {
                                        submitMessage(new AssertMessage(1.0, list.toKifString(), sourceId, null)); // Submit even if non-ground
                                    } else throw new ParseException("Empty KIF list received as assertion");
                                    break;
                            }
                        } else { // List starting with variable or nested list - treat as assertion?
                             if (!list.terms().isEmpty()) {
                                 submitMessage(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                             } else throw new ParseException("Empty KIF list received");
                        }
                    } else throw new ParseException("Top-level message must be a KIF list for commands/assertions: " + term.toKifString());
                }
            } else { // Handle "probability (kif)" format
                var m = Pattern.compile("^([0-9.]+)\\s*(\\(.*\\))$", Pattern.DOTALL).matcher(trimmed);
                if (m.matches()) {
                    var probability = Double.parseDouble(m.group(1));
                    var kifStr = m.group(2);
                    if (probability < 0.0 || probability > 1.0)
                        throw new NumberFormatException("Probability must be between 0.0 and 1.0");

                    var terms = KifParser.parseKif(kifStr);
                     if (terms.isEmpty()) throw new ParseException("Empty KIF message received with probability.");
                     if (terms.size() > 1) System.err.println("Warning: Multiple top-level KIF expressions with single probability, applying probability to all.");

                     for(var term : terms) {
                          if (term instanceof KifList list) {
                              if (!list.terms().isEmpty()) {
                                  submitMessage(new AssertMessage(probability, list.toKifString(), sourceId, null));
                              } else throw new ParseException("Empty KIF list received with probability");
                          } else throw new ParseException("Expected KIF list after probability: " + term.toKifString());
                     }
                } else throw new ParseException("Invalid format. Expected KIF list '(..)' or 'probability (..)'");
            }
        } catch (ParseException | NumberFormatException | ClassCastException e) {
            System.err.printf("WS Message Error from %s: %s | Original: %s%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), 100)));
        } catch (Exception e) {
            System.err.println("Unexpected WS message processing error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void reasonerLoop() {
        System.out.println("Reasoner loop started.");
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                var msg = inputQueue.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) processInputMessage(msg);
                else if (!beam.isEmpty()) processBeamStep();
                 else Thread.onSpinWait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
                System.out.println("Reasoner loop interrupted.");
            } catch (Exception e) {
                System.err.println("Unhandled Error in reasoner loop: " + e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("Reasoner loop finished.");
    }

    private void processInputMessage(InputMessage msg) {
        try {
            switch (msg) {
                case AssertMessage am -> processAssertionInput(am);
                case RetractByIdMessage rm -> processRetractionByIdInput(rm);
                case RetractByNoteIdMessage rnm -> processRetractionByNoteIdInput(rnm);
                case RetractRuleMessage rrm -> processRetractionRuleInput(rrm);
                case RuleMessage ruleMsg -> processRuleInput(ruleMsg);
                case RegisterCallbackMessage rcm -> registerCallback(rcm);
            }
        } catch (ParseException e) {
            System.err.printf("Error processing queued message (%s from %s): %s%n", msg.getClass().getSimpleName(), msg.getSourceId(), e.getMessage());
        } catch (Exception e) {
            System.err.printf("Unexpected error processing queued message (%s from %s): %s%n", msg.getClass().getSimpleName(), msg.getSourceId(), e.getMessage());
            e.printStackTrace();
        }
    }

     private void processAssertionInput(AssertMessage am) throws ParseException {
        var terms = KifParser.parseKif(am.kifString());
        for (var term : terms) {
            if (term instanceof KifList fact) {
                if (fact.terms().isEmpty()) {
                    System.err.println("Assertion ignored (empty list): " + fact.toKifString());
                    continue;
                }
                // Ground facts are added directly to beam
                // Non-ground facts currently cannot be added to KB directly (engine is forward-chaining on ground facts)
                // We could potentially store non-ground assertions for other purposes, but they won't participate in current reasoning.
                if (!fact.containsVariable()) {
                    var pa = new PotentialAssertion(fact, am.probability(), Collections.emptySet(), am.sourceNoteId());
                    addToBeam(pa);

                    // Trigger broadcast/callback for the *input* event, regardless of broadcastInputAssertions flag,
                    // as it might be needed for UI updates or external listeners tracking specific sources.
                    var tempId = "input-" + idCounter.incrementAndGet();
                    var inputAssertion = new Assertion(tempId, fact, am.probability(), System.currentTimeMillis(), am.sourceNoteId(), Collections.emptySet());
                    invokeCallbacks("assert-input", inputAssertion);

                } else {
                     System.err.println("Warning: Non-ground assertion received: " + fact.toKifString() + ". Cannot be added to KB directly by current reasoner.");
                     // Future work: Store or handle non-ground assertions if needed.
                }
            } else System.err.println("Assertion input ignored (not a KIF list): " + term.toKifString());
        }
    }

    private void processRetractionByIdInput(RetractByIdMessage rm) {
        retractAssertion(rm.assertionId(), "retract-id");
    }

    private void processRetractionByNoteIdInput(RetractByNoteIdMessage rnm) {
        var idsToRetract = noteIdToAssertionIds.remove(rnm.noteId());
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.println("Retracting " + idsToRetract.size() + " assertions for note: " + rnm.noteId());
            idsToRetract.forEach(id -> retractAssertion(id, "retract-note"));
        }
    }

    private void processRetractionRuleInput(RetractRuleMessage rrm) throws ParseException {
        var term = KifParser.parseKif(rrm.ruleKif()).getFirst();
        if (term instanceof KifList ruleForm) {
            boolean removed = rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));

             if (ruleForm.getOperator().filter("<=>"::equals).isPresent() && ruleForm.size() == 3) {
                var ant = ruleForm.get(1);
                var con = ruleForm.get(2);
                 var fwd = new KifList(new KifConstant("=>"), ant, con);
                 var bwd = new KifList(new KifConstant("=>"), con, ant);
                 removed |= rules.removeIf(r -> r.ruleForm().equals(fwd));
                 removed |= rules.removeIf(r -> r.ruleForm().equals(bwd));
             }

            if (removed) System.out.println("Retracted rule matching: " + ruleForm.toKifString());
            else System.out.println("Retract rule: No rule found matching: " + ruleForm.toKifString());
        } else System.err.println("Retract rule: Input is not a valid rule KIF list: " + rrm.ruleKif());
    }

     private void processRuleInput(RuleMessage ruleMsg) throws ParseException {
        var term = KifParser.parseKif(ruleMsg.kifString()).getFirst();
        if (term instanceof KifList list) {
            var op = list.getOperator().orElse("");
            if (list.size() == 3 && (op.equals("=>") || op.equals("<=>"))) {
                // Allow valid KIF terms (constants, variables, lists) as ant/con
                if (list.get(1) instanceof KifTerm antTerm && list.get(2) instanceof KifTerm conTerm) {
                    addRuleInternal(list, antTerm, conTerm);
                    if (op.equals("<=>")) {
                        addRuleInternal(new KifList(new KifConstant("=>"), conTerm, antTerm), conTerm, antTerm);
                    }
                 } else { // Should not happen if parser works
                    System.err.println("Invalid rule structure (ant/con not valid KIF terms): " + ruleMsg.kifString());
                 }
            } else {
                // Fix for error message: check if it's one of the specific invalid patterns mentioned
                 String specificError = "";
                 if (list.getOperator().filter(s -> s.equals("domain") || s.equals("instance")).isPresent() && list.size() == 3) {
                     specificError = " (Note: 'domain'/'instance' are typically facts, not rule operators like '=>')";
                 }
                 System.err.println("Invalid rule format (expected '(=> ant con)' or '(<=> ant con)'): " + ruleMsg.kifString() + specificError);
             }
        } else System.err.println("Rule input ignored (not a KIF list): " + ruleMsg.kifString());
    }

    private void addRuleInternal(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
        var ruleId = "rule-" + idCounter.incrementAndGet();
        try {
            var newRule = new Rule(ruleId, ruleForm, antecedent, consequent);
            if (rules.add(newRule)) System.out.println("Added rule: " + ruleId + " " + newRule.ruleForm().toKifString());
            else System.out.println("Rule ignored (duplicate): " + newRule.ruleForm().toKifString());
        } catch (IllegalArgumentException e) {
             System.err.println("Failed to create rule: " + e.getMessage() + " for " + ruleForm.toKifString());
        }
    }

     private void registerCallback(RegisterCallbackMessage rcm) {
         if (rcm.pattern() instanceof KifTerm patternTerm) { // Accept any KifTerm as pattern
             callbackRegistrations.add(new CallbackRegistration(patternTerm, rcm.callback()));
             System.out.println("Registered external callback for pattern: " + patternTerm.toKifString());
         } else { // Should not happen if message created correctly
             System.err.println("Callback registration failed: Invalid pattern object.");
         }
     }

    private void processBeamStep() {
        List<PotentialAssertion> candidates = new ArrayList<>(beamWidth);
        synchronized (beam) {
            var count = 0;
            while (count < beamWidth && !beam.isEmpty()) {
                var pa = beam.poll();
                if (pa == null) continue;
                beamContentSnapshot.remove(pa.fact());
                if (!kbContentSnapshot.contains(pa.fact())) {
                    candidates.add(pa);
                    count++;
                }
            }
        }

        candidates.forEach(pa -> {
            var newAssertion = addAssertionToKb(pa);
            if (newAssertion != null) deriveFrom(newAssertion);
        });
    }

    private void deriveFrom(Assertion newAssertion) {
        rules.parallelStream().forEach(rule -> {
            var antClauses = rule.getAntecedentClauses();
            if (antClauses.isEmpty()) return;

            boolean potentiallyMatches = antClauses.stream().anyMatch(clause ->
                // Relaxed check: operator match OR clause is variable (can match anything)
                clause.getOperator().equals(newAssertion.fact().getOperator()) || clause.getOperator().isEmpty() // Allow matching variable-headed clauses? Maybe too broad.
                // Stick to operator matching for efficiency. If clause is just a var, unification handles it.
                //clause.getOperator().equals(newAssertion.fact().getOperator()) && clause.size() == newAssertion.fact().size() // Arity check might be too strict if using vars
            );
            // Skip if operators clearly don't match - simple optimization
            if (!newAssertion.fact().getOperator().isPresent()) return; // Cannot trigger rule if new fact has no operator
             boolean opMatchPossible = antClauses.stream().anyMatch(clause -> clause.getOperator().equals(newAssertion.fact().getOperator()));
             if (!opMatchPossible) return;


            IntStream.range(0, antClauses.size()).forEach(i -> {
                var clauseToMatch = antClauses.get(i);
                var bindings = Unifier.unify(clauseToMatch, newAssertion.fact(), Map.of());
                if (bindings != null) {
                    var remaining = IntStream.range(0, antClauses.size())
                                             .filter(idx -> idx != i)
                                             .mapToObj(antClauses::get)
                                             .toList();
                    findMatches(remaining, bindings, Set.of(newAssertion.id()))
                            .forEach(match -> {
                                var consequentTerm = Unifier.substitute(rule.consequent(), match.bindings());
                                if (consequentTerm instanceof KifList derived && !derived.containsVariable()) {
                                    var supportIds = new HashSet<>(match.supportingAssertionIds());
                                    supportIds.add(newAssertion.id());
                                    var prob = calculateDerivedProbability(supportIds);
                                    var inheritedNoteId = findCommonSourceNodeId(supportIds);
                                    var pa = new PotentialAssertion(derived, prob, supportIds, inheritedNoteId);
                                    addToBeam(pa);
                                } else if (!(consequentTerm instanceof KifList)) {
                                     // System.err.println("Rule resulted in non-list consequent: " + consequentTerm.toKifString());
                                } else if (consequentTerm.containsVariable()) {
                                     // System.err.println("Rule resulted in non-ground consequent: " + consequentTerm.toKifString());
                                }
                            });
                }
            });
        });
    }

    private String findCommonSourceNodeId(Set<String> supportIds) {
        if (supportIds == null || supportIds.isEmpty()) return null;
        String commonId = null;
        boolean first = true;
        for (var id : supportIds) {
            var assertion = assertionsById.get(id);
            if (assertion == null) continue;
            var noteId = assertion.sourceNoteId();
            if (first) {
                 if (noteId == null) return null;
                 commonId = noteId;
                 first = false;
            } else if (!Objects.equals(commonId, noteId)) return null;
        }
        return commonId;
    }

    private Stream<MatchResult> findMatches(List<KifList> remainingClauses, Map<KifVariable, KifTerm> bindings, Set<String> supportIds) {
        if (remainingClauses.isEmpty()) return Stream.of(new MatchResult(bindings, supportIds));

        var clause = remainingClauses.getFirst();
        var nextRemaining = remainingClauses.subList(1, remainingClauses.size());
        var substTerm = Unifier.substitute(clause, bindings);

        if (!(substTerm instanceof KifList substClause)) {
             // This happens if a variable is bound to a constant, and the clause was just that variable.
             // We need to find assertions matching that constant.
             if (substTerm instanceof KifConstant constant) {
                  // This scenario doesn't fit the KifList assumption. Need to rethink?
                  // The antecedent clauses are expected to be lists. If substitution results in a constant,
                  // it means the original clause was likely just a variable, e.g., `(=> ?X (Consequent ?X))`.
                  // This style of rule isn't well-supported by the list-based matching.
                  // For now, return empty stream if substitution doesn't yield a list.
                  System.err.println("Warning: Rule antecedent clause became non-list after substitution: " + substTerm.toKifString());
                  return Stream.empty();
             }
             return Stream.empty(); // Cannot match if not a list after substitution
        }


        Stream<Assertion> candidates = findCandidateAssertions(substClause);

        if (substClause.containsVariable()) {
            return candidates.flatMap(candidate -> {
                var newBindings = Unifier.unify(substClause, candidate.fact(), bindings);
                if (newBindings != null) {
                    Set<String> nextSupport = new HashSet<>(supportIds);
                    nextSupport.add(candidate.id());
                    return findMatches(nextRemaining, newBindings, nextSupport);
                }
                return Stream.empty();
            });
        } else {
            return candidates
                .filter(a -> a.fact().equals(substClause))
                .flatMap(match -> {
                    Set<String> nextSupport = new HashSet<>(supportIds);
                    nextSupport.add(match.id());
                    return findMatches(nextRemaining, bindings, nextSupport);
                 });
        }
    }

    private Stream<Assertion> findCandidateAssertions(KifList clause) {
        return clause.getOperator()
            .map(factIndex::get)
            .map(Map::values) // Get collection of Assertions for that operator
            .map(Collection::stream)
            .orElseGet(() -> {
                 // If clause has no operator (e.g., starts with variable), we must scan all facts. Less efficient.
                 // System.err.println("Warning: Finding candidates for clause without operator: " + clause.toKifString()); // Potentially noisy
                 return assertionsById.values().stream().filter(Objects::nonNull);
            });
    }

    private double calculateDerivedProbability(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return 0.0;
        return ids.stream()
                  .map(assertionsById::get)
                  .filter(Objects::nonNull)
                  .mapToDouble(Assertion::probability)
                  .min()
                  .orElse(0.0);
    }

    private Assertion addAssertionToKb(PotentialAssertion pa) {
        if (kbContentSnapshot.contains(pa.fact())) return null;
        enforceKbCapacity();

        var id = "fact-" + idCounter.incrementAndGet();
        var timestamp = System.currentTimeMillis();
        var newAssertion = new Assertion(id, pa.fact(), pa.probability(), timestamp, pa.sourceNoteId(), pa.supportingAssertionIds()); // Already immutable

        if (assertionsById.putIfAbsent(id, newAssertion) == null) {
            pa.fact().getOperator().ifPresent(p -> factIndex.computeIfAbsent(p, k -> new ConcurrentHashMap<>()).put(pa.fact(), newAssertion));
            kbContentSnapshot.add(pa.fact());
            kbPriorityQueue.put(newAssertion);

            if (pa.sourceNoteId() != null)
                noteIdToAssertionIds.computeIfAbsent(pa.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(id);

            invokeCallbacks("assert-added", newAssertion);
            return newAssertion;
        } else {
            System.err.println("Collision detected for assertion ID: " + id + ". Skipping addition.");
            return null;
        }
    }

    private void retractAssertion(String id, String reason) {
        var removed = assertionsById.remove(id);
        if (removed != null) {
            kbContentSnapshot.remove(removed.fact());
            kbPriorityQueue.remove(removed);
            removed.fact().getOperator().ifPresent(p -> factIndex.computeIfPresent(p, (k, v) -> {
                v.remove(removed.fact());
                return v.isEmpty() ? null : v;
            }));
            if (removed.sourceNoteId() != null)
                noteIdToAssertionIds.computeIfPresent(removed.sourceNoteId(), (k, v) -> {
                    v.remove(id);
                    return v.isEmpty() ? null : v;
                });
            invokeCallbacks("assert-retracted", removed);
        }
    }

    private void enforceKbCapacity() {
        while (assertionsById.size() >= maxKbSize) {
            var toEvict = kbPriorityQueue.poll();
            if (toEvict == null) break;
            if (assertionsById.containsKey(toEvict.id())) {
                 System.out.printf("KB Evict (Low Prio) [%s]: P=%.4f %s%n", toEvict.id(), toEvict.probability(), toEvict.toKifString());
                 // Retrieve the full assertion object before retracting for the callback
                 Assertion evictedAssertion = assertionsById.get(toEvict.id());
                 if (evictedAssertion != null) {
                      retractAssertion(evictedAssertion.id(), "evict-capacity");
                      invokeCallbacks("evict", evictedAssertion); // Pass the actual evicted assertion
                 }
            }
        }
    }

     private void addToBeam(PotentialAssertion pa) {
         if (kbContentSnapshot.contains(pa.fact()) || beamContentSnapshot.contains(pa.fact())) return;

         synchronized (beam) {
             if (!kbContentSnapshot.contains(pa.fact()) && !beamContentSnapshot.contains(pa.fact())) {
                 if (beam.offer(pa)) {
                     beamContentSnapshot.add(pa.fact());
                 }
             }
         }
     }

    private void invokeCallbacks(String type, Assertion assertion) {
        // 1. Direct Broadcasting (if applicable)
        switch (type) {
            case "assert-added":
            case "assert-retracted":
            case "evict":
                broadcastMessage(type, assertion); // Always broadcast derivations, retractions, evictions
                break;
            case "assert-input":
                if (broadcastInputAssertions) { // Broadcast input only if flag is set
                    broadcastMessage(type, assertion);
                }
                break;
        }

        // 2. UI Update
        if (swingUI != null && swingUI.isDisplayable()) {
            swingUI.handleReasonerCallback(type, assertion);
        }

        // 3. Process Registered KIF Pattern Callbacks
        callbackRegistrations.forEach(reg -> {
//            // Check if the pattern can possibly unify with the assertion's fact
//            // This is a basic check; Unifier.unify does the real work.
//            boolean structureMatch = (reg.pattern() instanceof KifList pList && assertion.fact() instanceof KifList fList && pList.size() == fList.size())
//                                   || (reg.pattern() instanceof KifVariable)
//                                   || (reg.pattern() instanceof KifConstant && assertion.fact() instanceof KifConstant); // Allow matching constants?
             // A simple operator check might be more effective if both are lists
             boolean operatorMatch = !(reg.pattern() instanceof KifList pList) || !(assertion.fact() instanceof KifList fList) ||
                 pList.getOperator().isEmpty() || fList.getOperator().isEmpty() ||
                 pList.getOperator().equals(fList.getOperator());


             // Only attempt unification if structure/operators seem compatible
            if (operatorMatch) { // Simplified check
                var bindings = Unifier.unify(reg.pattern(), assertion.fact(), Map.of());
                if (bindings != null) {
                    try {
                        reg.callback().onMatch(type, assertion, bindings);
                    } catch (Exception e) {
                        System.err.println("Error in KIF callback execution: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        });
    }


    public CompletableFuture<String> getKifFromLlmAsync(String noteText, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var prompt = """
            Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax).
            Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
            Do not include explanations, markdown formatting (like ```kif), or comments.
            Focus on factual statements and relationships. Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', attribute relations, etc. where appropriate.
            If mentioning an entity whose specific name isn't given, use a KIF variable (e.g., `?cpu`, `?company`).

            Note:
            "%s"

            KIF Assertions:""".formatted(noteText);

            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var jsonResponse = new JSONObject(new JSONTokener(responseBody));
                    String kifResult;

                    if (jsonResponse.has("message") && jsonResponse.getJSONObject("message").has("content")) {
                        kifResult = jsonResponse.getJSONObject("message").getString("content");
                    } else if (jsonResponse.has("choices") && !jsonResponse.getJSONArray("choices").isEmpty() &&
                               jsonResponse.getJSONArray("choices").getJSONObject(0).has("message") &&
                               jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").has("content")) {
                        kifResult = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                    } else if (jsonResponse.has("response")) {
                        kifResult = jsonResponse.getString("response");
                    } else {
                        throw new IOException("Unexpected LLM response format. Body starts with: " + responseBody.substring(0, Math.min(responseBody.length(), 200)));
                    }

                    var cleanedKif = kifResult.lines()
                                              .map(String::trim)
                                              .filter(line -> line.startsWith("(") && line.endsWith(")"))
                                              .filter(line -> !line.matches("^\\(\\s*\\)$"))
                                              .collect(Collectors.joining("\n")); // Join with newline

                    if (cleanedKif.isEmpty() && !kifResult.isBlank()) {
                         System.err.println("LLM Warning ("+noteId+"): Result contained text but no valid KIF lines:\n---\n"+kifResult+"\n---");
                    } else if (!cleanedKif.isEmpty()) {
                         System.out.println("LLM (" + noteId + ") Raw KIF Output:\n" + cleanedKif);
                    }
                    return cleanedKif; // Return raw KIF, grounding happens in UI thread

                } else {
                    throw new IOException("LLM API request failed: " + response.statusCode() + " " + responseBody);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("LLM API interaction failed for note " + noteId + ": " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (Exception e) {
                System.err.println("LLM response processing failed for note " + noteId + ": " + e.getMessage());
                e.printStackTrace();
                throw new CompletionException(e);
            }
        }, llmExecutor);
    }

    // --- KIF Data Structures ---
    sealed interface KifTerm permits KifConstant, KifVariable, KifList {
        default String toKifString() {
            var sb = new StringBuilder();
            writeKifString(sb);
            return sb.toString();
        }
        void writeKifString(StringBuilder sb);
        default boolean containsVariable() { return false; }
        // Helper to collect all variables within a term
        default Set<KifVariable> getVariables() {
            Set<KifVariable> vars = new HashSet<>();
            collectVariablesRecursive(this, vars);
            return vars;
        }
        private static void collectVariablesRecursive(KifTerm term, Set<KifVariable> vars) {
              switch (term) {
                  case KifVariable v -> vars.add(v);
                  case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars));
                  case KifConstant c -> {}
              }
          }
    }

    record KifConstant(String value) implements KifTerm {
        @Override
        public void writeKifString(StringBuilder sb) {
            boolean needsQuotes = value.isEmpty() || value.chars().anyMatch(c ->
                Character.isWhitespace(c) || "()\";?".indexOf(c) != -1
            );
            // Ensure generated skolem/grounded names are quoted if they contain underscores or hyphens etc.
            needsQuotes |= (value.contains("_") || value.contains("-"));

            if (needsQuotes) sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            else sb.append(value);
        }
    }

    record KifVariable(String name) implements KifTerm {
        KifVariable { Objects.requireNonNull(name); if (!name.startsWith("?")) throw new IllegalArgumentException("Var name must start with ?"); }
        @Override public void writeKifString(StringBuilder sb) { sb.append(name); }
        @Override public boolean containsVariable() { return true; }
    }

    record KifList(List<KifTerm> terms) implements KifTerm {
        KifList(KifTerm... terms) { this(List.of(terms)); }
        KifList { Objects.requireNonNull(terms); terms = List.copyOf(terms); }

        @Override public boolean equals(Object o) { return o instanceof KifList other && terms.equals(other.terms); }
        @Override public int hashCode() { return terms.hashCode(); }

        @Override
        public void writeKifString(StringBuilder sb) {
            sb.append('(');
            IntStream.range(0, terms.size()).forEach(i -> {
                terms.get(i).writeKifString(sb);
                if (i < terms.size() - 1) sb.append(' ');
            });
            sb.append(')');
        }

        KifTerm get(int index) { return terms.get(index); }
        int size() { return terms.size(); }
        Stream<KifTerm> stream() { return terms.stream(); }
        Optional<String> getOperator() { return terms.isEmpty() || !(terms.getFirst() instanceof KifConstant c) ? Optional.empty() : Optional.of(c.value()); }
        @Override public boolean containsVariable() { return terms.stream().anyMatch(KifTerm::containsVariable); }
    }

    // --- Knowledge Representation ---
    record Assertion(String id, KifList fact, double probability, long timestamp, String sourceNoteId, Set<String> directSupportingIds)
        implements Comparable<Assertion> {
        Assertion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(fact);
            Objects.requireNonNull(directSupportingIds);
            if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]");
            directSupportingIds = Set.copyOf(directSupportingIds);
        }
        @Override public int compareTo(Assertion other) { return Double.compare(this.probability, other.probability); }
        String toKifString() { return fact.toKifString(); }
    }

    record Rule(String id, KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
         private static final KifConstant AND_OP = new KifConstant("and");

         Rule {
             Objects.requireNonNull(id);
             Objects.requireNonNull(ruleForm);
             Objects.requireNonNull(antecedent);
             Objects.requireNonNull(consequent);
             if (!(ruleForm.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent() && ruleForm.size() == 3))
                 throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con)");

             // Validate antecedent structure for common cases
             if (antecedent instanceof KifList antList) {
                 if (antList.getOperator().filter("and"::equals).isPresent()) {
                     if (antList.terms().stream().skip(1).anyMatch(t -> !(t instanceof KifList)))
                         throw new IllegalArgumentException("Antecedent 'and' must contain only lists: " + ruleForm.toKifString());
                 } else if (antList.getOperator().isEmpty() && !antList.terms().isEmpty()) {
                     // Allow single KifVariable as antecedent? No, must be a list.
                     // throw new IllegalArgumentException("Antecedent clause must have an operator constant: " + ruleForm.toKifString());
                 }
             } else if (!(antecedent instanceof KifVariable)) { // Allow single variable? Let's require list.
                 // throw new IllegalArgumentException("Antecedent must be a KIF list or variable: " + ruleForm.toKifString());
             }

             // Check for unbound variables in consequent (simple check)
             Set<KifVariable> antVars = antecedent.getVariables();
             Set<KifVariable> conVars = consequent.getVariables();
             conVars.removeAll(antVars);
             if (!conVars.isEmpty() && !ruleForm.getOperator().filter("<=>"::equals).isPresent()) { // Allow unbound in <=>
                  System.err.println("Warning: Rule consequent contains unbound variables: " + conVars.stream().map(KifVariable::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKifString());
             }
         }

         List<KifList> getAntecedentClauses() {
            // If antecedent is a single list (P ?X), return list containing just that list.
            // If antecedent is (and (P ?X) (Q ?Y)), return list [(P ?X), (Q ?Y)].
             return switch (antecedent) {
                 case KifList list -> {
                     if (list.size() > 1 && list.get(0).equals(AND_OP)) {
                         yield list.terms().subList(1, list.size()).stream()
                             .filter(KifList.class::isInstance).map(KifList.class::cast)
                             .toList();
                     } else if (list.getOperator().isPresent() || (list.size() > 0 && list.get(0) instanceof KifVariable)) {
                         // Allow single clause list like (instance ?X Dog) or (?Var A B)
                         yield List.of(list);
                     } else {
                         System.err.println("Warning: Antecedent list has invalid structure: " + list.toKifString());
                         yield List.of();
                     }
                 }
                 // Removed support for single variable antecedent for simplicity with matching logic
                 // case KifVariable v -> { System.err.println("Warning: Single variable antecedent not directly supported: " + v.name()); yield List.of(); }
                 default -> { System.err.println("Warning: Antecedent is not a KIF list: " + antecedent.toKifString()); yield List.of(); }
             };
         }

        @Override public boolean equals(Object o) { return o instanceof Rule other && ruleForm.equals(other.ruleForm); }
        @Override public int hashCode() { return ruleForm.hashCode(); }
    }

    // --- Parser ---
    static class KifParser {
        private final StringReader reader;
        private int currentChar = -2;
        private int line = 1;
        private int col = 0;

        KifParser(String input) { this.reader = new StringReader(input.trim()); }

        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try { return new KifParser(input).parseTopLevel(); }
            catch (IOException e) { throw new ParseException("Read error: " + e.getMessage()); }
        }

        List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>();
            consumeWhitespaceAndComments();
            while (peek() != -1) {
                terms.add(parseTerm());
                consumeWhitespaceAndComments();
            }
            return Collections.unmodifiableList(terms);
        }

        KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments();
            int c = peek();
            if (c == -1) throw createParseException("Unexpected end of input while expecting term");
            return switch (c) {
                case '(' -> parseList();
                case '"' -> parseQuotedString();
                case '?' -> parseVariable();
                default -> parseAtom();
            };
        }

        KifList parseList() throws IOException, ParseException {
            consumeChar('(');
            List<KifTerm> terms = new ArrayList<>();
            while (true) {
                consumeWhitespaceAndComments();
                int c = peek();
                if (c == ')') { consumeChar(')'); return new KifList(terms); }
                if (c == -1) throw createParseException("Unmatched parenthesis in list");
                terms.add(parseTerm());
            }
        }

        KifVariable parseVariable() throws IOException, ParseException {
            consumeChar('?');
            var name = new StringBuilder("?");
            if (!isValidAtomChar(peek(), true)) throw createParseException("Variable name expected after '?'");
            while (isValidAtomChar(peek(), false)) name.append((char) consumeChar());
            if (name.length() == 1) throw createParseException("Empty variable name after '?'");
            return new KifVariable(name.toString());
        }

        KifConstant parseAtom() throws IOException, ParseException {
            var atom = new StringBuilder();
            if (!isValidAtomChar(peek(), true)) throw createParseException("Invalid start character for an atom");
            while (isValidAtomChar(peek(), false)) atom.append((char) consumeChar());
            if (atom.isEmpty()) throw createParseException("Empty atom encountered");
             // Check if it looks like a number, still treat as constant string
             String atomStr = atom.toString();
             boolean isNumber = Pattern.matches("^[+-]?([0-9]*[.])?[0-9]+$", atomStr); // Basic number check
             // Could add KifNumber type if needed, but constant works
            return new KifConstant(atomStr);
        }

         boolean isValidAtomChar(int c, boolean isFirstChar) {
            if (c == -1 || Character.isWhitespace(c) || "()\";?".indexOf(c) != -1) return false;
            // Standard KIF allows many chars in atoms if not starting with '?' or '"'
            return true;
         }

        KifConstant parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                int c = consumeChar();
                if (c == '"') return new KifConstant(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') {
                    int next = consumeChar();
                    if (next == -1) throw createParseException("Unmatched quote after escape character");
                    // Handle basic escapes like \\ and \"
                    sb.append((char) (next == 'n' ? '\n' : next == 't' ? '\t' : next));
                } else sb.append((char) c);
            }
        }

        int peek() throws IOException {
            if (currentChar == -2) currentChar = reader.read();
            return currentChar;
        }

        int consumeChar() throws IOException {
            int c = peek();
            if (c != -1) {
                 currentChar = -2;
                 if (c == '\n') { line++; col = 0; }
                 else col++;
            }
            return c;
        }

        void consumeChar(char expected) throws IOException, ParseException {
            int c = consumeChar();
            if (c != expected) throw createParseException("Expected '" + expected + "' but found " + (c == -1 ? "EOF" : "'" + (char) c + "'"));
        }

        void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                int c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) consumeChar();
                else if (c == ';') {
                     consumeChar();
                     while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                } else break;
            }
        }

         ParseException createParseException(String message) {
            return new ParseException(message + " at line " + line + " col " + col);
         }
    }

    static class ParseException extends Exception { ParseException(String message) { super(message); } }

    // --- Input Message Types ---
    sealed interface InputMessage extends Comparable<InputMessage> {
        double getPriority();
        String getSourceId();
    }

    record AssertMessage(double probability, String kifString, String sourceId, String sourceNoteId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.probability, other.getPriority()); }
        @Override public double getPriority() { return probability; }
        @Override public String getSourceId() {return sourceId;}
    }

    record RetractByIdMessage(String assertionId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 10.0; }
        @Override public String getSourceId() {return sourceId;}
    }

    record RetractByNoteIdMessage(String noteId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 10.0; }
        @Override public String getSourceId() {return sourceId;}
    }

    record RetractRuleMessage(String ruleKif, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 9.0; }
        @Override public String getSourceId() {return sourceId;}
    }

    record RuleMessage(String kifString, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 8.0; }
        @Override public String getSourceId() {return sourceId;}
    }

    record RegisterCallbackMessage(KifTerm pattern, KifCallback callback, String sourceId) implements InputMessage {
         @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
         @Override public double getPriority() { return 7.0; }
         @Override public String getSourceId() {return sourceId;}
     }

    // --- Reasoner Internals ---
    record PotentialAssertion(KifList fact, double probability, Set<String> supportingAssertionIds, String sourceNoteId)
        implements Comparable<PotentialAssertion> {
         PotentialAssertion {
             Objects.requireNonNull(fact);
             Objects.requireNonNull(supportingAssertionIds);
             if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]");
             supportingAssertionIds = Set.copyOf(supportingAssertionIds);
         }
        @Override public int compareTo(PotentialAssertion other) { return Double.compare(other.probability, this.probability); }
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && fact.equals(pa.fact); }
        @Override public int hashCode() { return fact.hashCode(); }
    }

    // Allow KifTerm as pattern for more flexibility, e.g. matching a specific constant
    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
         CallbackRegistration { Objects.requireNonNull(pattern); Objects.requireNonNull(callback); }
     }

    record MatchResult(Map<KifVariable, KifTerm> bindings, Set<String> supportingAssertionIds) {
         MatchResult { Objects.requireNonNull(bindings); Objects.requireNonNull(supportingAssertionIds); }
     }

    @FunctionalInterface interface KifCallback { void onMatch(String type, Assertion assertion, Map<KifVariable, KifTerm> bindings); }

    // --- Unification Logic ---
    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 100;

        static Map<KifVariable, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVariable, KifTerm> bindings) {
            if (bindings == null) return null;
            KifTerm xSubst = fullySubstitute(x, bindings);
            KifTerm ySubst = fullySubstitute(y, bindings);

            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVariable v) return bindVariable(v, ySubst, bindings);
            if (ySubst instanceof KifVariable v) return bindVariable(v, xSubst, bindings);

            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lx.size(); i++) {
                    currentBindings = unify(lx.get(i), ly.get(i), currentBindings);
                    if (currentBindings == null) return null;
                }
                return currentBindings;
            }
            return null;
        }

        static KifTerm substitute(KifTerm term, Map<KifVariable, KifTerm> bindings) { return fullySubstitute(term, bindings); }

        private static KifTerm fullySubstitute(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term;
            var current = term;
            for (var depth = 0; depth < MAX_SUBST_DEPTH; depth++) {
                var next = substituteOnce(current, bindings);
                if (next == current) return current; // Use reference equality check for performance
                if (next.equals(current)) return current;
                current = next;
            }
            System.err.println("Warning: Substitution depth limit reached for: " + term.toKifString());
            return current;
        }

        private static KifTerm substituteOnce(KifTerm term, Map<KifVariable, KifTerm> bindings) {
             return switch (term) {
                 case KifVariable v -> bindings.getOrDefault(v, v);
                 case KifList l -> {
                      List<KifTerm> originalTerms = l.terms();
                      List<KifTerm> substitutedTerms = null;
                      boolean changed = false;
                      for (int i = 0; i < originalTerms.size(); i++) {
                          KifTerm originalTerm = originalTerms.get(i);
                          KifTerm substitutedTerm = substituteOnce(originalTerm, bindings);
                          if (substitutedTerm != originalTerm) changed = true;
                          if (changed && substitutedTerms == null) {
                              substitutedTerms = new ArrayList<>(originalTerms.subList(0, i));
                           }
                           if (substitutedTerms != null) substitutedTerms.add(substitutedTerm);
                      }
                       yield changed ? new KifList(substitutedTerms != null ? substitutedTerms : originalTerms) : l;
                  }
                 case KifConstant c -> c;
             };
        }

        private static Map<KifVariable, KifTerm> bindVariable(KifVariable var, KifTerm value, Map<KifVariable, KifTerm> bindings) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var)) return unify(bindings.get(var), value, bindings);
            if (value instanceof KifVariable vVal && bindings.containsKey(vVal)) return bindVariable(var, bindings.get(vVal), bindings);
            if (occursCheck(var, value, bindings)) return null;

            Map<KifVariable, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, value);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheck(KifVariable var, KifTerm term, Map<KifVariable, KifTerm> bindings) {
             KifTerm substTerm = fullySubstitute(term, bindings);
             return switch (substTerm) {
                 case KifVariable v -> var.equals(v);
                 case KifList l -> l.stream().anyMatch(t -> occursCheck(var, t, bindings));
                 case KifConstant c -> false;
             };
        }
    }

    // --- Swing UI Class ---
    static class SwingUI extends JFrame {
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JTextArea derivationView = new JTextArea();
        private final JButton addButton = new JButton("Add Note");
        private final JButton removeButton = new JButton("Remove Note");
        private final JButton analyzeButton = new JButton("Analyze Note");
        private final JLabel statusLabel = new JLabel("Status: Idle");
        ProbabilisticKifReasoner reasoner;
        private Note currentNote = null;

        public SwingUI(ProbabilisticKifReasoner reasoner) {
            super("Cognote - Probabilistic KIF Reasoner");
            this.reasoner = reasoner;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(1200, 800);
            setLocationRelativeTo(null);

            setupFonts();
            setupComponents();
            setupLayout();
            setupListeners();

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    System.out.println("UI closing event received.");
                    if (reasoner != null) {
                        reasoner.stopReasoner();
                    }
                    dispose();
                    System.exit(0);
                }
            });
            updateUIForSelection();
        }

         private void setupFonts() {
             UIManager.put("TextArea.font", UI_DEFAULT_FONT);
             UIManager.put("List.font", UI_DEFAULT_FONT);
             UIManager.put("Button.font", UI_DEFAULT_FONT);
             UIManager.put("Label.font", UI_DEFAULT_FONT);
             noteList.setFont(UI_DEFAULT_FONT);
             noteEditor.setFont(UI_DEFAULT_FONT);
             derivationView.setFont(MONOSPACED_FONT);
             addButton.setFont(UI_DEFAULT_FONT);
             removeButton.setFont(UI_DEFAULT_FONT);
             analyzeButton.setFont(UI_DEFAULT_FONT);
             statusLabel.setFont(UI_DEFAULT_FONT);
         }

        private void setupComponents() {
            noteEditor.setLineWrap(true);
            noteEditor.setWrapStyleWord(true);
            derivationView.setEditable(false);
            derivationView.setLineWrap(true);
            derivationView.setWrapStyleWord(true);
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteList.setCellRenderer(new NoteListCellRenderer());
            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        }

        private void setupLayout() {
             var derivationScrollPane = new JScrollPane(derivationView);
             var editorScrollPane = new JScrollPane(noteEditor);

             var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScrollPane, derivationScrollPane);
             rightSplit.setResizeWeight(0.65);

             var leftScrollPane = new JScrollPane(noteList);
             leftScrollPane.setMinimumSize(new Dimension(200, 100));

             var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightSplit);
             mainSplit.setResizeWeight(0.25);

             var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
             buttonPanel.add(addButton);
             buttonPanel.add(removeButton);
             buttonPanel.add(analyzeButton);

             var bottomPanel = new JPanel(new BorderLayout());
             bottomPanel.add(buttonPanel, BorderLayout.WEST);
             bottomPanel.add(statusLabel, BorderLayout.CENTER);
             bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

             getContentPane().add(mainSplit, BorderLayout.CENTER);
             getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupListeners() {
            addButton.addActionListener(this::addNote);
            removeButton.addActionListener(this::removeNote);
            analyzeButton.addActionListener(this::analyzeNote);

            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    Note selected = noteList.getSelectedValue();
                    if (currentNote != null && selected != currentNote && noteEditor.isEnabled()) {
                        currentNote.text = noteEditor.getText();
                    }
                    currentNote = selected;
                    updateUIForSelection();
                }
            });

             noteEditor.addFocusListener(new java.awt.event.FocusAdapter() {
                 public void focusLost(java.awt.event.FocusEvent evt) {
                     if (currentNote != null && noteEditor.isEnabled()) {
                         currentNote.text = noteEditor.getText();
                     }
                 }
             });
        }

        private void updateUIForSelection() {
            boolean noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected);
            removeButton.setEnabled(noteSelected);
            analyzeButton.setEnabled(noteSelected);

            if (noteSelected) {
                noteEditor.setText(currentNote.text);
                noteEditor.setCaretPosition(0);
                displayExistingDerivations(currentNote);
                setTitle("Cognote - " + currentNote.title);
            } else {
                noteEditor.setText("");
                derivationView.setText("");
                 setTitle("Cognote - Probabilistic KIF Reasoner");
            }
        }

        private void addNote(ActionEvent e) {
            var title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) {
                var noteId = "note-" + UUID.randomUUID();
                var newNote = new Note(noteId, title.trim(), "");
                noteListModel.addElement(newNote);
                noteList.setSelectedValue(newNote, true);
            }
        }

        private void removeNote(ActionEvent e) {
            if (currentNote == null) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                "Remove note '" + currentNote.title + "'?\nThis will retract all associated assertions from the knowledge base.",
                "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                statusLabel.setText("Status: Removing '" + currentNote.title + "' and retracting assertions...");
                reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Remove"));

                int selectedIndex = noteList.getSelectedIndex();
                noteListModel.removeElement(currentNote);
                currentNote = null;

                if (noteListModel.getSize() > 0) {
                    noteList.setSelectedIndex(Math.min(selectedIndex, noteListModel.getSize() - 1));
                } else {
                    updateUIForSelection();
                }
                 statusLabel.setText("Status: Note removed.");
            }
        }

        // --- Grounding Helper ---
        private KifTerm groundTerm(KifTerm term, Map<KifVariable, KifTerm> groundingMap) {
             return switch(term) {
                 case KifVariable v -> groundingMap.getOrDefault(v, v); // Return grounded constant or original var if not in map
                 case KifList l -> {
                     List<KifTerm> groundedTerms = l.terms().stream()
                                                    .map(t -> groundTerm(t, groundingMap))
                                                    .toList();
                     // Only create new list if any term actually changed
                     yield IntStream.range(0, l.size()).anyMatch(i -> l.get(i) != groundedTerms.get(i))
                           ? new KifList(groundedTerms)
                           : l;
                 }
                 case KifConstant c -> c;
             };
        }

        private void analyzeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            statusLabel.setText("Status: Analyzing '" + currentNote.title + "'...");
            analyzeButton.setEnabled(false);
            removeButton.setEnabled(false);
            var noteTextToAnalyze = noteEditor.getText();
            currentNote.text = noteTextToAnalyze;

            derivationView.setText("Analyzing Note...\n--------------------\n");
            reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Analyze"));

            reasoner.getKifFromLlmAsync(noteTextToAnalyze, currentNote.id)
                .thenAcceptAsync(kifString -> {
                    try {
                        var terms = KifParser.parseKif(kifString);
                        int submittedCount = 0;
                        int groundedCount = 0;
                        int skippedCount = 0;

                        for (var term : terms) {
                            if (term instanceof KifList fact) {
                                if (!fact.containsVariable()) {
                                    reasoner.submitMessage(new AssertMessage(1.0, fact.toKifString(), "UI-LLM", currentNote.id));
                                    submittedCount++;
                                } else {
                                    // Ground variables using note context
                                    Set<KifVariable> variables = fact.getVariables();
                                    Map<KifVariable, KifTerm> groundingMap = new HashMap<>();
                                    String notePrefix = currentNote.id.replace("-", "_") + "_"; // Sanitize note id for KIF
                                    for (KifVariable var : variables) {
                                         // Create a likely unique constant based on note ID and variable name
                                         String groundedName = notePrefix + var.name().substring(1); // Remove '?'
                                         groundingMap.put(var, new KifConstant(groundedName));
                                    }

                                    KifTerm groundedTerm = groundTerm(fact, groundingMap);

                                    if (groundedTerm instanceof KifList groundedFact && !groundedFact.containsVariable()) {
                                        System.out.println("Grounded '" + fact.toKifString() + "' to '" + groundedFact.toKifString() + "' for note " + currentNote.id);
                                        reasoner.submitMessage(new AssertMessage(1.0, groundedFact.toKifString(), "UI-LLM-Grounded", currentNote.id));
                                        groundedCount++;
                                    } else {
                                        System.err.println("Failed to fully ground LLM KIF or result not a list: " + (groundedTerm != null ? groundedTerm.toKifString() : "null from grounding"));
                                        skippedCount++;
                                    }
                                }
                            } else {
                                 System.err.println("LLM generated non-list KIF, skipped: " + term.toKifString());
                                 skippedCount++;
                            }
                        }

                        final int totalSubmitted = submittedCount + groundedCount;
                        String statusMsg = String.format("Status: Analyzed '%s'. Submitted %d assertions (%d grounded).", currentNote.title, totalSubmitted, groundedCount);
                        if (skippedCount > 0) statusMsg += String.format(" Skipped %d invalid/ungroundable terms.", skippedCount);
                        statusLabel.setText(statusMsg);
                        derivationView.setText("Derivations for: " + currentNote.title + "\n--------------------\n"); // Clear view, wait for callbacks

                    } catch (ParseException pe) {
                        statusLabel.setText("Status: KIF Parse Error from LLM for '" + currentNote.title + "'.");
                        System.err.println("KIF Parse Error from LLM output: " + pe.getMessage() + "\nInput was:\n" + kifString);
                        JOptionPane.showMessageDialog(SwingUI.this, "Could not parse KIF from LLM response:\n" + pe.getMessage() + "\n\nCheck console for details and LLM output.", "KIF Parse Error", JOptionPane.ERROR_MESSAGE);
                        derivationView.setText("Error parsing KIF from LLM.\n--------------------\n" + kifString);
                    } catch (Exception ex) {
                        statusLabel.setText("Status: Error processing LLM response for '" + currentNote.title + "'.");
                        System.err.println("Error processing LLM KIF: " + ex);
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(SwingUI.this, "An unexpected error occurred processing the LLM response:\n" + ex.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE);
                    } finally {
                         analyzeButton.setEnabled(true);
                         removeButton.setEnabled(true);
                    }
                }, SwingUtilities::invokeLater)
                .exceptionallyAsync(ex -> {
                    var cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                    statusLabel.setText("Status: LLM Analysis Failed for '" + currentNote.title + "'.");
                    System.err.println("LLM call failed: " + cause.getMessage());
                    cause.printStackTrace();
                     JOptionPane.showMessageDialog(SwingUI.this, "LLM communication or processing failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
                     analyzeButton.setEnabled(true);
                     removeButton.setEnabled(true);
                     derivationView.setText("LLM Analysis Failed.\n--------------------\n" + cause.getMessage());
                    return null;
                }, SwingUtilities::invokeLater);
        }

        public void handleReasonerCallback(String type, Assertion assertion) {
            SwingUtilities.invokeLater(() -> {
                if (currentNote != null && isRelatedToNote(assertion, currentNote.id)) {
                    updateDerivationViewForAssertion(type, assertion);
                }
                switch (type) {
                    case "assert-added":
                         if (!assertion.id().startsWith("input-"))
                             statusLabel.setText(String.format("Status: Derived %s (P=%.3f)", assertion.id(), assertion.probability()));
                        break;
                     case "assert-retracted":
                         statusLabel.setText(String.format("Status: Retracted %s", assertion.id()));
                         break;
                     case "evict":
                         statusLabel.setText(String.format("Status: Evicted %s (Low P)", assertion.id()));
                         break;
                     case "assert-input":
                         if (currentNote != null && Objects.equals(assertion.sourceNoteId(), currentNote.id)) {
                              statusLabel.setText(String.format("Status: Processed input %s for current note", assertion.id()));
                         }
                         break;
                 }
            });
        }

        private void updateDerivationViewForAssertion(String type, Assertion assertion) {
            String linePrefix = String.format("P=%.3f %s", assertion.probability(), assertion.toKifString());
            String lineSuffix = String.format(" [%s]", assertion.id());
            String fullLine = linePrefix + lineSuffix;

            switch (type) {
                case "assert-added", "assert-input":
                    String currentText = derivationView.getText();
                    // Avoid adding duplicates visually if already present (e.g. from initial display)
                    if (!currentText.contains(lineSuffix)) { // Check based on unique ID suffix
                        derivationView.append(fullLine + "\n");
                        derivationView.setCaretPosition(derivationView.getDocument().getLength());
                    }
                    break;
                case "assert-retracted", "evict":
                    String text = derivationView.getText();
                    String updatedText = text.lines()
                                             .map(l -> l.trim().endsWith(lineSuffix) ? "# " + type.toUpperCase() + ": " + l : l)
                                             .collect(Collectors.joining("\n"));
                     if (!text.equals(updatedText)) {
                        int caretPos = derivationView.getCaretPosition(); // Try to preserve position
                        derivationView.setText(updatedText);
                        try { derivationView.setCaretPosition(caretPos); } catch (IllegalArgumentException ignored) {} // Ignore if pos invalid
                    }
                    break;
            }
        }

        private boolean isRelatedToNote(Assertion assertion, String targetNoteId) {
            if (targetNoteId == null) return false;
            if (targetNoteId.equals(assertion.sourceNoteId())) return true;

            Queue<String> toCheck = new LinkedList<>(assertion.directSupportingIds());
            Set<String> visited = new HashSet<>(assertion.directSupportingIds());
            visited.add(assertion.id());

            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                var parent = reasoner.assertionsById.get(currentId);
                if (parent != null) {
                    if (targetNoteId.equals(parent.sourceNoteId())) return true;
                    parent.directSupportingIds().stream()
                          .filter(visited::add)
                          .forEach(toCheck::offer);
                }
            }
            return false;
        }

        private void displayExistingDerivations(Note note) {
            if (note == null || reasoner == null) {
                 derivationView.setText("");
                 return;
            }
            derivationView.setText("Derivations for: " + note.title + "\n--------------------\n");
            reasoner.assertionsById.values().stream()
                    .filter(a -> isRelatedToNote(a, note.id))
                    .sorted(Comparator.comparingLong(Assertion::timestamp).reversed())
                    .forEach(a -> derivationView.append(String.format("P=%.3f %s [%s]\n", a.probability(), a.toKifString(), a.id())));
            derivationView.setCaretPosition(0);
        }

        static class Note {
            final String id;
            String title;
            String text;

            Note(String id, String title, String text) {
                this.id = Objects.requireNonNull(id);
                this.title = Objects.requireNonNull(title);
                this.text = Objects.requireNonNull(text);
            }
            @Override public String toString() { return title; }
            @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); }
            @Override public int hashCode() { return id.hashCode(); }
        }

        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (c instanceof JLabel label) {
                    label.setBorder(new EmptyBorder(5, 10, 5, 10));
                    label.setFont(UI_DEFAULT_FONT);
                }
                return c;
            }
        }
    }
}