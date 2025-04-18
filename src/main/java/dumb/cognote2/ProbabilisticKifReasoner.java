package dumb.cognote2;

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
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2); // Slightly smaller for derivations
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);

    private final int beamWidth;
    private final int maxKbSize;
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl;
    private final String llmModel;

    private final ConcurrentMap<String, ConcurrentMap<KifList, Assertion>> factIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
    private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis()); // Start with timestamp for better uniqueness
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
            .connectTimeout(Duration.ofSeconds(30)) // Increased timeout
            .executor(Executors.newVirtualThreadPerTaskExecutor()) // Use virtual threads for HTTP client tasks
            .build();
    private volatile boolean running = true;
    private final SwingUI swingUI;

    public ProbabilisticKifReasoner(int port, int beamWidth, int maxKbSize, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.beamWidth = Math.max(1, beamWidth);
        this.maxKbSize = Math.max(10, maxKbSize);
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = Objects.requireNonNullElse(llmUrl, "http://localhost:11434/api/chat");
        this.llmModel = Objects.requireNonNullElse(llmModel, "hf.co/mradermacher/phi-4-GGUF:Q4_K_S"); // Updated default
        this.swingUI = Objects.requireNonNull(ui, "SwingUI cannot be null");
        System.out.printf("Reasoner config: Port=%d, Beam=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s%n",
                          port, this.beamWidth, this.maxKbSize, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel);
        registerInternalCallback("assert-internal-broadcast", (type, assertion, bindings) -> broadcastMessage(type, assertion));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            int port = 8887, beamWidth = 10, maxKbSize = 1000;
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
                ui = new SwingUI(null); // Initialize UI first
                server = new ProbabilisticKifReasoner(port, beamWidth, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.reasoner = server; // Link UI back to reasoner
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

    private void registerInternalCallback(String patternKif, KifCallback callback) {
        try {
            var pattern = KifParser.parseKif(patternKif).getFirst();
            if (pattern instanceof KifList listPattern) {
                callbackRegistrations.add(new CallbackRegistration(listPattern, callback));
                System.out.println("Registered internal callback for: " + listPattern.toKifString());
            } else System.err.println("Internal callback pattern must be a KIF list: " + patternKif);
        } catch (ParseException | NoSuchElementException | ClassCastException e) {
            System.err.println("Failed to parse/register internal callback pattern: " + patternKif + " - " + e);
        }
    }

    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        var message = switch (type) {
            case "assert-added" -> String.format("assert-derived %.4f %s", assertion.probability(), kifString);
            case "assert-input" -> String.format("assert-input %.4f %s", assertion.probability(), kifString);
            case "assert-retracted" -> String.format("retract %s", assertion.id());
            case "evict" -> String.format("evict %s", assertion.id());
            default -> type + " " + kifString; // Fallback, less likely needed now
        };
        try {
            broadcast(message);
//            if (this.server != null && !this.server.isClosed()) broadcast(message);
//            else System.out.println("WS broadcast skipped (server not ready/closed): " + message);
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
                    parenDepth = 0; // Reset for recovery
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d expressions from %s, queued %d rules/facts.%n", processedCount, filename, queuedCount);
    }

    private void queueExpressionFromSource(KifTerm term, String sourceId) {
         if (term instanceof KifList list) {
            var op = list.getOperator().orElse("");
            if (list.size() == 3 && (op.equals("=>") || op.equals("<=>"))) {
                 inputQueue.put(new RuleMessage(list.toKifString(), sourceId));
            } else if (!list.terms().isEmpty() && !list.containsVariable()) {
                 inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null));
            } else {
                 System.err.println("Warning: Ignoring non-rule/non-ground-fact list from " + sourceId + ": " + list.toKifString());
            }
        } else {
             System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
        }
    }

    public void submitMessage(InputMessage message) {
        inputQueue.put(message);
    }

    public void startReasoner() {
        running = true;
        reasonerExecutor.submit(this::reasonerLoop);
        try {
            start(); // Start WebSocket server thread
            System.out.println("WebSocket server thread started on port " + getPort());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage() + ". Reasoner running without WebSocket.");
        }
    }

    public void stopReasoner() {
        if (!running) return; // Prevent double stopping
        System.out.println("Stopping reasoner and services...");
        running = false;

        // Gracefully shutdown executors
        shutdownExecutor(reasonerExecutor, "Reasoner");
        shutdownExecutor(llmExecutor, "LLM");
        // Shutdown HttpClient's internal executor if possible/needed (virtual threads handle themselves mostly)

        // Stop WebSocket server
        try {
            stop(1000); // Timeout in milliseconds
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
        System.err.println("WS Error from " + (conn != null ? conn.getRemoteSocketAddress() : "server") + ": " + ex.getMessage());
        if (!(ex instanceof IOException)) { // Reduce noise for common IO errors
            ex.printStackTrace();
        }
    }

    @Override
    public void onStart() {
        System.out.println("Reasoner WebSocket listener active on port " + getPort() + ".");
        setConnectionLostTimeout(0); // Disable automatic timeout disconnection if desired
        setConnectionLostTimeout(100); // Example: 100 seconds timeout
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
                                default:
                                    submitMessage(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                                    break;
                            }
                        } else submitMessage(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                    } else throw new ParseException("Top-level message must be a KIF list for commands/assertions: " + term.toKifString());
                }
            } else {
                var m = Pattern.compile("^([0-9.]+)\\s*(\\(.*\\))$", Pattern.DOTALL).matcher(trimmed);
                if (m.matches()) {
                    var probability = Double.parseDouble(m.group(1));
                    var kifStr = m.group(2);
                    if (probability < 0.0 || probability > 1.0)
                        throw new NumberFormatException("Probability must be between 0.0 and 1.0");
                    // Parse the KIF string to ensure it's valid before queueing potentially multiple assertions
                    var terms = KifParser.parseKif(kifStr);
                     if (terms.isEmpty()) throw new ParseException("Empty KIF message received with probability.");
                     if (terms.size() > 1) System.err.println("Warning: Multiple top-level KIF expressions with single probability, applying probability to all.");
                     for(var term : terms) {
                          if (term instanceof KifList list) {
                              submitMessage(new AssertMessage(probability, list.toKifString(), sourceId, null));
                          } else throw new ParseException("Expected KIF list after probability: " + term.toKifString());
                     }
                } else throw new ParseException("Invalid format. Expected KIF list '(..)' or 'probability (..)'");
            }
        } catch (ParseException | NumberFormatException | ClassCastException e) {
            System.err.printf("WS Message Error from %s: %s | Original: %s%n", sourceId, e.getMessage(), message.substring(0, Math.min(message.length(), 100)));
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
                else if (!beam.isEmpty()) processBeamStep(); // Only process beam if input queue is empty
                 else Thread.onSpinWait(); // Small pause if nothing to do
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
                System.out.println("Reasoner loop interrupted.");
            } catch (Exception e) {
                System.err.println("Unhandled Error in reasoner loop: " + e.getMessage());
                e.printStackTrace(); // Log stack trace for unexpected errors
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
                if (fact.terms().isEmpty() || fact.containsVariable()) {
                    System.err.println("Assertion ignored (empty or contains variables): " + fact.toKifString());
                    continue;
                }
                var pa = new PotentialAssertion(fact, am.probability(), Collections.emptySet(), am.sourceNoteId());
                addToBeam(pa);

                if (broadcastInputAssertions || am.sourceNoteId() != null) { // Broadcast if flag set OR if it came from UI note
                    var tempId = "input-" + idCounter.incrementAndGet();
                    var inputAssertion = new Assertion(tempId, fact, am.probability(), System.currentTimeMillis(), am.sourceNoteId(), Collections.emptySet());
                    // Directly broadcast here if needed, or use internal callback for consistency
                     invokeCallbacks("assert-input", inputAssertion); // Use callback mechanism
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
        var term = KifParser.parseKif(rrm.ruleKif()).getFirst(); // Assume single rule KIF
        if (term instanceof KifList ruleForm) {
            boolean removed = rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));

            // Handle <=> by attempting to remove both directions
             if (ruleForm.getOperator().filter("<=>"::equals).isPresent() && ruleForm.size() == 3) {
                var ant = ruleForm.get(1);
                var con = ruleForm.get(2);
                 var fwd = new KifList(new KifConstant("=>"), ant, con);
                 var bwd = new KifList(new KifConstant("=>"), con, ant);
                 // Use removeIf again, result |= ensures we capture if either was removed
                 removed |= rules.removeIf(r -> r.ruleForm().equals(fwd));
                 removed |= rules.removeIf(r -> r.ruleForm().equals(bwd));
             }

            if (removed) System.out.println("Retracted rule matching: " + ruleForm.toKifString());
            else System.out.println("Retract rule: No rule found matching: " + ruleForm.toKifString());
        } else System.err.println("Retract rule: Input is not a valid rule KIF list: " + rrm.ruleKif());
    }

     private void processRuleInput(RuleMessage ruleMsg) throws ParseException {
        var term = KifParser.parseKif(ruleMsg.kifString()).getFirst(); // Assume single rule KIF
        if (term instanceof KifList list) {
            var op = list.getOperator().orElse("");
            if (list.size() == 3 && (op.equals("=>") || op.equals("<=>"))) {
                if (list.get(1) instanceof KifTerm antTerm && list.get(2) instanceof KifTerm conTerm) {
                     // Allow atoms or lists as ant/con, but handle structure internally in Rule record
                    addRuleInternal(list, antTerm, conTerm);
                    if (op.equals("<=>")) {
                        // Create the reverse rule explicitly
                        addRuleInternal(new KifList(new KifConstant("=>"), conTerm, antTerm), conTerm, antTerm);
                    }
                } else {
                    // This case should technically not happen if parsing is correct
                    System.err.println("Invalid rule structure (ant/con not valid KIF terms): " + ruleMsg.kifString());
                 }
            } else System.err.println("Invalid rule format (expected '(=> ant con)' or '(<=> ant con)'): " + ruleMsg.kifString());
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
         if (rcm.pattern() instanceof KifList listPattern) {
             callbackRegistrations.add(new CallbackRegistration(listPattern, rcm.callback()));
             System.out.println("Registered external callback for: " + listPattern.toKifString());
         } else {
             System.err.println("Callback registration failed: Pattern must be a KIF list.");
         }
     }

    private void processBeamStep() {
        List<PotentialAssertion> candidates = new ArrayList<>(beamWidth);
        synchronized (beam) {
            var count = 0;
            while (count < beamWidth && !beam.isEmpty()) {
                var pa = beam.poll();
                if (pa == null) continue; // Should not happen with PriorityQueue, but safety first
                beamContentSnapshot.remove(pa.fact());
                if (!kbContentSnapshot.contains(pa.fact())) {
                    candidates.add(pa);
                    count++;
                } else {
                    // System.out.println("Beam skip (already in KB): " + pa.fact().toKifString());
                }
            }
        }

        candidates.forEach(pa -> {
            var newAssertion = addAssertionToKb(pa);
            if (newAssertion != null) deriveFrom(newAssertion);
        });
    }

    private void deriveFrom(Assertion newAssertion) {
        rules.parallelStream().forEach(rule -> { // Parallelize rule application
            var antClauses = rule.getAntecedentClauses();
            if (antClauses.isEmpty()) return;

            // Check if the new assertion *potentially* matches any antecedent clause *syntactically* first
            // This avoids unnecessary unification attempts if operators/arity don't match
            boolean potentiallyMatches = antClauses.stream().anyMatch(clause ->
                clause.getOperator().equals(newAssertion.fact().getOperator()) && clause.size() == newAssertion.fact().size()
            );
            if (!potentiallyMatches) return;


            IntStream.range(0, antClauses.size()).forEach(i -> {
                var bindings = Unifier.unify(antClauses.get(i), newAssertion.fact(), Map.of());
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
                                    supportIds.add(newAssertion.id()); // Add the triggering assertion id
                                    var prob = calculateDerivedProbability(supportIds);
                                    var inheritedNoteId = findCommonSourceNodeId(supportIds);
                                    var pa = new PotentialAssertion(derived, prob, supportIds, inheritedNoteId);
                                    addToBeam(pa);
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
            if (assertion == null) continue; // Should not happen, but be safe
            var noteId = assertion.sourceNoteId();
            if (first) {
                 if (noteId == null) return null; // If first has no source, no common source
                 commonId = noteId;
                 first = false;
            } else if (!Objects.equals(commonId, noteId)) return null; // Mismatch found (handles nulls)
        }
        return commonId; // Returns the common ID or null if none found or mismatch
    }

    private Stream<MatchResult> findMatches(List<KifList> remainingClauses, Map<KifVariable, KifTerm> bindings, Set<String> supportIds) {
        if (remainingClauses.isEmpty()) return Stream.of(new MatchResult(bindings, supportIds));

        var clause = remainingClauses.getFirst();
        var nextRemaining = remainingClauses.subList(1, remainingClauses.size());
        var substTerm = Unifier.substitute(clause, bindings);

        if (!(substTerm instanceof KifList substClause)) return Stream.empty(); // Cannot match if not a list after substitution

        Stream<Assertion> candidates = findCandidateAssertions(substClause);

        if (substClause.containsVariable()) { // Need KB lookup and further unification
            return candidates.flatMap(candidate -> {
                var newBindings = Unifier.unify(substClause, candidate.fact(), bindings);
                if (newBindings != null) {
                    Set<String> nextSupport = new HashSet<>(supportIds);
                    nextSupport.add(candidate.id());
                    return findMatches(nextRemaining, newBindings, nextSupport);
                }
                return Stream.empty();
            });
        } else { // Ground clause, check existence and add support
            return candidates
                .filter(a -> a.fact().equals(substClause)) // Efficient check for ground clauses
                .flatMap(match -> {
                    Set<String> nextSupport = new HashSet<>(supportIds);
                    nextSupport.add(match.id());
                    return findMatches(nextRemaining, bindings, nextSupport);
                 });
        }
    }

    private Stream<Assertion> findCandidateAssertions(KifList clause) {
        // Use index if operator is present, otherwise full scan (less efficient but necessary)
        return clause.getOperator()
            .map(factIndex::get)
            .map(Map::values)
            .map(Collection::stream)
            .orElseGet(() -> assertionsById.values().stream().filter(Objects::nonNull)); // Fallback: stream all assertions
    }

    private double calculateDerivedProbability(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return 0.0;
        // Use minimum probability of supporting facts (weakest link)
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
        var newAssertion = new Assertion(id, pa.fact(), pa.probability(), timestamp, pa.sourceNoteId(), Collections.unmodifiableSet(pa.supportingAssertionIds()));

        if (assertionsById.putIfAbsent(id, newAssertion) == null) { // Ensure ID uniqueness
            pa.fact().getOperator().ifPresent(p -> factIndex.computeIfAbsent(p, k -> new ConcurrentHashMap<>()).put(pa.fact(), newAssertion));
            kbContentSnapshot.add(pa.fact());
            kbPriorityQueue.put(newAssertion);

            if (pa.sourceNoteId() != null)
                noteIdToAssertionIds.computeIfAbsent(pa.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(id);

            // System.out.printf("KB Add [%s]: P=%.4f %s (Src:%s Size:%d/%d)%n", id, pa.probability(), pa.fact().toKifString(), pa.sourceNoteId(), assertionsById.size(), maxKbSize);
            invokeCallbacks("assert-added", newAssertion);
            return newAssertion;
        } else {
            System.err.println("Collision detected for assertion ID: " + id + ". Skipping addition."); // Should be rare
            return null;
        }
    }

    private void retractAssertion(String id, String reason) {
        var removed = assertionsById.remove(id);
        if (removed != null) {
            kbContentSnapshot.remove(removed.fact());
            kbPriorityQueue.remove(removed); // Removal from PriorityBlockingQueue can be O(n)
            removed.fact().getOperator().ifPresent(p -> factIndex.computeIfPresent(p, (k, v) -> {
                v.remove(removed.fact());
                return v.isEmpty() ? null : v; // Clean up map entry if empty
            }));
            if (removed.sourceNoteId() != null)
                noteIdToAssertionIds.computeIfPresent(removed.sourceNoteId(), (k, v) -> {
                    v.remove(id);
                    return v.isEmpty() ? null : v; // Clean up map entry if empty
                });
            // System.out.printf("KB Retract [%s] (%s): %s%n", id, reason, removed.toKifString());
            invokeCallbacks("assert-retracted", removed);
        }
    }

    private void enforceKbCapacity() {
        while (assertionsById.size() >= maxKbSize) {
            var toEvict = kbPriorityQueue.poll(); // Removes lowest priority
            if (toEvict == null) break; // Queue empty, nothing to evict
            // Double-check if it wasn't retracted by another operation concurrently
            if (assertionsById.containsKey(toEvict.id())) {
                 System.out.printf("KB Evict (Low Prio) [%s]: P=%.4f %s%n", toEvict.id(), toEvict.probability(), toEvict.toKifString());
                 retractAssertion(toEvict.id(), "evict-capacity"); // Use main retraction logic
                 invokeCallbacks("evict", toEvict); // Specific callback type for eviction
            }
        }
    }

     private void addToBeam(PotentialAssertion pa) {
         // Avoid adding if already in KB or already in the beam (more robust check)
         if (kbContentSnapshot.contains(pa.fact()) || beamContentSnapshot.contains(pa.fact())) return;

         synchronized (beam) { // Synchronize access to beam and beamContentSnapshot
             // Final check within synchronized block
             if (!kbContentSnapshot.contains(pa.fact()) && !beamContentSnapshot.contains(pa.fact())) {
                 if (beam.offer(pa)) { // Offer returns true if added (queue not full, etc.)
                     beamContentSnapshot.add(pa.fact());
                 }
             }
         }
     }

    private void invokeCallbacks(String type, Assertion assertion) {
        // UI update is handled separately now for clarity
        if (swingUI != null && swingUI.isDisplayable()) {
            swingUI.handleReasonerCallback(type, assertion);
        }

        // Process registered KIF pattern callbacks
        callbackRegistrations.forEach(reg -> {
             // Optimize: Check if assertion operator matches pattern operator first if possible
            var p = reg.pattern();
            boolean operatorMatch = p instanceof KifList pl && pl.getOperator()
                                      .map(op -> op.equals(assertion.fact().getOperator().orElse(null)))
                                      .orElse(true); // If pattern has no operator, always try unify

            if (operatorMatch) {
                var bindings = Unifier.unify(p, assertion.fact(), Map.of());
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

            Note:
            "%s"

            KIF Assertions:""".formatted(noteText);

            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2)); // Lower temp for factual KIF

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60)) // Longer timeout for LLM
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var jsonResponse = new JSONObject(new JSONTokener(responseBody));
                    String kifResult;

                    // Handle different potential response structures gracefully
                    if (jsonResponse.has("message") && jsonResponse.getJSONObject("message").has("content")) {
                        kifResult = jsonResponse.getJSONObject("message").getString("content"); // Ollama chat standard
                    } else if (jsonResponse.has("choices") && !jsonResponse.getJSONArray("choices").isEmpty() &&
                               jsonResponse.getJSONArray("choices").getJSONObject(0).has("message") &&
                               jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").has("content")) {
                        kifResult = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"); // OpenAI standard
                    } else if (jsonResponse.has("response")) {
                        kifResult = jsonResponse.getString("response"); // Ollama generate standard
                    } else {
                        throw new IOException("Unexpected LLM response format. Body starts with: " + responseBody.substring(0, Math.min(responseBody.length(), 200)));
                    }

                    var cleanedKif = kifResult.lines()
                                              .map(String::trim)
                                              .filter(line -> line.startsWith("(") && line.endsWith(")"))
                                              .filter(line -> !line.matches("^\\(\\s*\\)$")) // Remove empty parens
                                              .reduce((s1, s2) -> s1 + "\n" + s2) // Re-join valid lines
                                              .orElse(""); // Handle case where no valid lines found

                    if (cleanedKif.isEmpty() && !kifResult.isBlank()) {
                         System.err.println("LLM Warning ("+noteId+"): Result contained text but no valid KIF lines:\n---\n"+kifResult+"\n---");
                    } else {
                         System.out.println("LLM (" + noteId + ") KIF Output:\n" + cleanedKif);
                    }
                    return cleanedKif;

                } else {
                    throw new IOException("LLM API request failed: " + response.statusCode() + " " + responseBody);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("LLM API interaction failed for note " + noteId + ": " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (Exception e) { // Catch JSON parsing errors etc.
                System.err.println("LLM response processing failed for note " + noteId + ": " + e.getMessage());
                e.printStackTrace(); // Print stack trace for unexpected parsing issues
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
    }

    record KifConstant(String value) implements KifTerm {
        @Override
        public void writeKifString(StringBuilder sb) {
            boolean needsQuotes = value.isEmpty() || value.chars().anyMatch(c ->
                Character.isWhitespace(c) || "()\";?".indexOf(c) != -1
            );
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
        KifList { Objects.requireNonNull(terms); terms = List.copyOf(terms); } // Ensure immutability

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
        Assertion { // Canonical constructor for validation/immutability
            Objects.requireNonNull(id);
            Objects.requireNonNull(fact);
            Objects.requireNonNull(directSupportingIds);
            if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]");
            directSupportingIds = Set.copyOf(directSupportingIds); // Ensure immutable set
        }
        @Override public int compareTo(Assertion other) { return Double.compare(this.probability, other.probability); } // Lower probability = higher priority for eviction queue
        String toKifString() { return fact.toKifString(); }
    }

    record Rule(String id, KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
         private static final KifConstant AND_OP = new KifConstant("and");

         Rule { // Canonical constructor
             Objects.requireNonNull(id);
             Objects.requireNonNull(ruleForm);
             Objects.requireNonNull(antecedent);
             Objects.requireNonNull(consequent);
             // Basic validation
             if (!(ruleForm.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent() && ruleForm.size() == 3))
                 throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con)");
             if (consequent instanceof KifList cl && cl.containsVariable() && !ruleForm.getOperator().filter("<=>"::equals).isPresent()) {
                 // Check if variables in consequent are bound by antecedent (simplistic check)
                 Set<KifVariable> antVars = collectVariables(antecedent);
                 Set<KifVariable> conVars = collectVariables(consequent);
                 conVars.removeAll(antVars);
                 if (!conVars.isEmpty())
                      System.err.println("Warning: Rule consequent contains unbound variables: " + conVars + " in " + ruleForm.toKifString());
             }
         }

         List<KifList> getAntecedentClauses() {
            return switch (antecedent) {
                 case KifList list -> {
                     if (list.size() > 1 && list.get(0).equals(AND_OP)) {
                         yield list.terms().subList(1, list.size()).stream()
                             .filter(KifList.class::isInstance)
                             .map(KifList.class::cast)
                             .toList();
                     } else if (list.getOperator().isPresent()) { // Single clause list like (instance ?X Dog)
                         yield List.of(list);
                     } else {
                         yield List.of(); // Not a valid antecedent structure? e.g., list starting with var or no operator
                     }
                 }
                 default -> List.of(); // Antecedent must be a list (or list of lists via 'and')
             };
         }

         private static Set<KifVariable> collectVariables(KifTerm term) {
             Set<KifVariable> vars = new HashSet<>();
             collectVariablesRecursive(term, vars);
             return vars;
         }
          private static void collectVariablesRecursive(KifTerm term, Set<KifVariable> vars) {
              switch (term) {
                  case KifVariable v -> vars.add(v);
                  case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars));
                  case KifConstant c -> {}
              }
          }

        @Override public boolean equals(Object o) { return o instanceof Rule other && ruleForm.equals(other.ruleForm); }
        @Override public int hashCode() { return ruleForm.hashCode(); }
    }

    // --- Parser ---
    static class KifParser {
        private final StringReader reader;
        private int currentChar = -2; // Use -2 to signify not read yet
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
             // Check for numbers - should maybe be KifNumber constant? Keeping as KifConstant for simplicity.
             try { Double.parseDouble(atom.toString()); } catch (NumberFormatException ignored) {} // It's a number, still store as constant string
            return new KifConstant(atom.toString());
        }

         boolean isValidAtomChar(int c, boolean isFirstChar) {
            if (c == -1 || Character.isWhitespace(c) || "()\";?".indexOf(c) != -1) return false;
            // Add more checks if needed, e.g., based on SUMO syntax rules
            // if (isFirstChar && Character.isDigit(c)) return false; // Example: atoms cannot start with digits
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
                    sb.append((char) next); // Simple escape handling
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
                 currentChar = -2; // Mark as consumed
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
                else if (c == ';') { // Line comment
                     consumeChar(); // Consume ';'
                     while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                     consumeWhitespaceAndComments(); // Consume EOL and any following whitespace/comments
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
        double getPriority(); // Higher value means higher priority
        String getSourceId();
    }

    record AssertMessage(double probability, String kifString, String sourceId, String sourceNoteId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.probability, other.getPriority()); } // Higher probability first
        @Override public double getPriority() { return probability; }
        @Override public String getSourceId() { return sourceId; }
    }

    record RetractByIdMessage(String assertionId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 10.0; } // High priority
        @Override public String getSourceId() { return sourceId; }
    }

    record RetractByNoteIdMessage(String noteId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 10.0; } // High priority
        @Override public String getSourceId() { return sourceId; }
    }

    record RetractRuleMessage(String ruleKif, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 9.0; } // Medium-high priority
        @Override public String getSourceId() { return sourceId; }
    }

    record RuleMessage(String kifString, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 8.0; } // Medium priority
        @Override public String getSourceId() { return sourceId; }
    }

    record RegisterCallbackMessage(KifTerm pattern, KifCallback callback, String sourceId) implements InputMessage {
         @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
         @Override public double getPriority() { return 7.0; } // Medium-low priority
        @Override public String getSourceId() { return sourceId; }
     }

    // --- Reasoner Internals ---
    record PotentialAssertion(KifList fact, double probability, Set<String> supportingAssertionIds, String sourceNoteId)
        implements Comparable<PotentialAssertion> {
         PotentialAssertion { // Canonical constructor
             Objects.requireNonNull(fact);
             Objects.requireNonNull(supportingAssertionIds);
             if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]");
             supportingAssertionIds = Set.copyOf(supportingAssertionIds); // Ensure immutable set
         }
        @Override public int compareTo(PotentialAssertion other) { return Double.compare(other.probability, this.probability); } // Descending prob for beam selection
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && fact.equals(pa.fact); }
        @Override public int hashCode() { return fact.hashCode(); }
    }

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
            return null; // Mismatch (constants, lists of different sizes, etc.)
        }

        static KifTerm substitute(KifTerm term, Map<KifVariable, KifTerm> bindings) { return fullySubstitute(term, bindings); }

        private static KifTerm fullySubstitute(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term;
            var current = term;
            for (var depth = 0; depth < MAX_SUBST_DEPTH; depth++) {
                var next = substituteOnce(current, bindings);
                if (next.equals(current)) return current; // Fixed point reached
                current = next;
            }
            // Avoid infinite loops for cyclic bindings, though bindVariable should prevent them
            System.err.println("Warning: Substitution depth limit reached for: " + term.toKifString());
            return current;
        }

        private static KifTerm substituteOnce(KifTerm term, Map<KifVariable, KifTerm> bindings) {
             return switch (term) {
                 case KifVariable v -> bindings.getOrDefault(v, v);
                 case KifList l -> {
                      // Use lazy substitution: only create new list if a term actually changed
                      List<KifTerm> originalTerms = l.terms();
                      List<KifTerm> substitutedTerms = null; // Initialized lazily
                      for (int i = 0; i < originalTerms.size(); i++) {
                          KifTerm originalTerm = originalTerms.get(i);
                          KifTerm substitutedTerm = substituteOnce(originalTerm, bindings);
                          if (substitutedTerm != originalTerm) { // Check for reference equality first
                              if (substitutedTerms == null) { // First change? Copy previous terms
                                  substitutedTerms = new ArrayList<>(originalTerms.subList(0, i));
                              }
                          }
                           if (substitutedTerms != null) { // If changes have started, add the (potentially substituted) term
                               substitutedTerms.add(substitutedTerm);
                           }
                      }
                       yield substitutedTerms != null ? new KifList(substitutedTerms) : l; // Return new list only if changed
                  }
                 case KifConstant c -> c;
             };
        }

        private static Map<KifVariable, KifTerm> bindVariable(KifVariable var, KifTerm value, Map<KifVariable, KifTerm> bindings) {
            if (var.equals(value)) return bindings; // Binding ?X to ?X is redundant
            if (bindings.containsKey(var)) return unify(bindings.get(var), value, bindings); // Existing binding
            if (value instanceof KifVariable vVal && bindings.containsKey(vVal)) return bindVariable(var, bindings.get(vVal), bindings); // Follow binding chain for value
            if (occursCheck(var, value, bindings)) return null; // Prevent cyclic bindings

            Map<KifVariable, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, value);
            return Collections.unmodifiableMap(newBindings); // Return immutable map
        }

        private static boolean occursCheck(KifVariable var, KifTerm term, Map<KifVariable, KifTerm> bindings) {
             KifTerm substTerm = fullySubstitute(term, bindings); // Check against the fully substituted term
             return switch (substTerm) {
                 case KifVariable v -> var.equals(v);
                 // Recursively check inside lists
                 case KifList l -> l.stream().anyMatch(t -> occursCheck(var, t, bindings));
                 case KifConstant c -> false;
             };
        }
    }

    // --- Swing UI Class (Nested for Single File) ---
    static class SwingUI extends JFrame {
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JTextArea derivationView = new JTextArea();
        private final JButton addButton = new JButton("Add Note");
        private final JButton removeButton = new JButton("Remove Note");
        private final JButton analyzeButton = new JButton("Analyze Note");
        private final JLabel statusLabel = new JLabel("Status: Idle");
        ProbabilisticKifReasoner reasoner; // Set after construction
        private Note currentNote = null;

        public SwingUI(ProbabilisticKifReasoner reasoner) {
            super("Cognote - Probabilistic KIF Reasoner");
            this.reasoner = reasoner;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(1200, 800); // Larger default size
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
                    System.exit(0); // Ensure JVM exit if this is the last window
                }
            });
            updateUIForSelection(); // Initial state
        }

         private void setupFonts() {
             UIManager.put("TextArea.font", UI_DEFAULT_FONT);
             UIManager.put("List.font", UI_DEFAULT_FONT);
             UIManager.put("Button.font", UI_DEFAULT_FONT);
             UIManager.put("Label.font", UI_DEFAULT_FONT);
             // You might need to apply fonts directly if UIManager defaults don't cover everything
             noteList.setFont(UI_DEFAULT_FONT);
             noteEditor.setFont(UI_DEFAULT_FONT);
             derivationView.setFont(MONOSPACED_FONT); // Use monospaced for derivations
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
            noteList.setCellRenderer(new NoteListCellRenderer()); // Custom renderer for padding
            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10)); // Padding for status bar
        }

        private void setupLayout() {
             var derivationScrollPane = new JScrollPane(derivationView);
             var editorScrollPane = new JScrollPane(noteEditor);

             var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScrollPane, derivationScrollPane);
             rightSplit.setResizeWeight(0.65);

             var leftScrollPane = new JScrollPane(noteList);
             leftScrollPane.setMinimumSize(new Dimension(200, 100)); // Minimum width for note list

             var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightSplit);
             mainSplit.setResizeWeight(0.25);

             var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5)); // Add gaps
             buttonPanel.add(addButton);
             buttonPanel.add(removeButton);
             buttonPanel.add(analyzeButton);

             var bottomPanel = new JPanel(new BorderLayout());
             bottomPanel.add(buttonPanel, BorderLayout.WEST);
             bottomPanel.add(statusLabel, BorderLayout.CENTER);
             bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5)); // Padding for bottom panel

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
                    // Save changes to previous note before switching
                    if (currentNote != null && selected != currentNote && noteEditor.isEnabled()) {
                        currentNote.text = noteEditor.getText(); // Silently save text on switch
                    }
                    currentNote = selected;
                    updateUIForSelection();
                }
            });

             // Basic auto-save mechanism (could be more sophisticated)
             noteEditor.addFocusListener(new java.awt.event.FocusAdapter() {
                 public void focusLost(java.awt.event.FocusEvent evt) {
                     if (currentNote != null && noteEditor.isEnabled()) {
                         currentNote.text = noteEditor.getText();
                         // Could add visual indication of saving/saved state
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
                noteEditor.setCaretPosition(0); // Scroll to top on selection
                displayExistingDerivations(currentNote);
                setTitle("Cognote - " + currentNote.title); // Update window title
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
                // Submit retraction message *before* removing from UI list
                reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Remove"));

                int selectedIndex = noteList.getSelectedIndex();
                noteListModel.removeElement(currentNote);
                currentNote = null;

                if (noteListModel.getSize() > 0) { // Select next or previous item
                    noteList.setSelectedIndex(Math.min(selectedIndex, noteListModel.getSize() - 1));
                } else {
                    updateUIForSelection(); // Handles empty list case
                }
                 statusLabel.setText("Status: Note removed.");
            }
        }

        private void analyzeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            statusLabel.setText("Status: Analyzing '" + currentNote.title + "'...");
            analyzeButton.setEnabled(false);
            removeButton.setEnabled(false); // Prevent removal during analysis
            var noteTextToAnalyze = noteEditor.getText();
            currentNote.text = noteTextToAnalyze; // Update note object immediately

            // Clear previous derivations view for this note
            derivationView.setText("Analyzing Note...\n--------------------\n");

            // 1. Retract previous assertions (async operation in reasoner)
            reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Analyze"));

            // 2. Call LLM asynchronously
            reasoner.getKifFromLlmAsync(noteTextToAnalyze, currentNote.id)
                .thenAcceptAsync(kifString -> { // 3. Process result on EDT
                    try {
                        var terms = KifParser.parseKif(kifString);
                        var count = 0;
                        var skippedNonGround = 0;
                         var skippedNonList = 0;
                        for (var term : terms) {
                            if (term instanceof KifList fact) {
                                if (!fact.containsVariable()) {
                                    // Use probability 1.0 for now, could potentially get from LLM?
                                    reasoner.submitMessage(new AssertMessage(1.0, fact.toKifString(), "UI-LLM", currentNote.id));
                                    count++;
                                } else {
                                     skippedNonGround++;
                                     System.err.println("LLM generated non-ground KIF, skipped: " + fact.toKifString());
                                }
                            } else {
                                 skippedNonList++;
                                 System.err.println("LLM generated non-list KIF, skipped: " + term.toKifString());
                            }
                        }
                        final var totalSubmitted = count;
                        final var totalSkipped = skippedNonGround + skippedNonList;
                         String statusMsg = String.format("Status: Analyzed '%s'. Submitted %d assertions.", currentNote.title, totalSubmitted);
                         if (totalSkipped > 0) statusMsg += String.format(" Skipped %d non-ground/non-list terms.", totalSkipped);
                         statusLabel.setText(statusMsg);
                         // Refresh derivations view *after* submitting assertions
                         // The assertions will trigger callbacks to update the view naturally.
                         // But we can clear and re-display existing ones immediately if desired.
                         // For now, rely on callbacks triggered by the AssertMessages.
                         derivationView.setText("Derivations for: " + currentNote.title + "\n--------------------\n"); // Clear view, wait for callbacks

                    } catch (ParseException pe) {
                        statusLabel.setText("Status: KIF Parse Error from LLM for '" + currentNote.title + "'.");
                        System.err.println("KIF Parse Error from LLM output: " + pe.getMessage() + "\nInput was:\n" + kifString);
                        JOptionPane.showMessageDialog(SwingUI.this, "Could not parse KIF from LLM response:\n" + pe.getMessage() + "\n\nCheck console for details and LLM output.", "KIF Parse Error", JOptionPane.ERROR_MESSAGE);
                        derivationView.setText("Error parsing KIF from LLM.\n--------------------\n" + kifString); // Show raw output on error
                    } catch (Exception ex) {
                        statusLabel.setText("Status: Error processing LLM response for '" + currentNote.title + "'.");
                        System.err.println("Error processing LLM KIF: " + ex);
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(SwingUI.this, "An unexpected error occurred processing the LLM response:\n" + ex.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE);
                    } finally {
                         analyzeButton.setEnabled(true); // Re-enable button
                         removeButton.setEnabled(true);
                    }
                }, SwingUtilities::invokeLater) // Ensure this block runs on EDT
                .exceptionallyAsync(ex -> { // Handle LLM call failures on EDT
                    var cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                    statusLabel.setText("Status: LLM Analysis Failed for '" + currentNote.title + "'.");
                    System.err.println("LLM call failed: " + cause.getMessage());
                    cause.printStackTrace(); // Log the underlying cause
                     JOptionPane.showMessageDialog(SwingUI.this, "LLM communication or processing failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
                     analyzeButton.setEnabled(true);
                     removeButton.setEnabled(true);
                     derivationView.setText("LLM Analysis Failed.\n--------------------\n" + cause.getMessage());
                    return null;
                }, SwingUtilities::invokeLater); // Ensure exception handling runs on EDT
        }

        public void handleReasonerCallback(String type, Assertion assertion) {
            SwingUtilities.invokeLater(() -> {
                // Update derivation view only if the current note is related
                if (currentNote != null && isRelatedToNote(assertion, currentNote.id)) {
                    updateDerivationViewForAssertion(type, assertion);
                }
                 // Update status bar for key events
                switch (type) {
                    case "assert-added":
                         if (!assertion.id().startsWith("input-")) // Avoid status spam for input broadcasts
                             statusLabel.setText(String.format("Status: Derived %s (P=%.3f)", assertion.id(), assertion.probability()));
                        break;
                     case "assert-retracted":
                         statusLabel.setText(String.format("Status: Retracted %s", assertion.id()));
                         break;
                     case "evict":
                         statusLabel.setText(String.format("Status: Evicted %s (Low P)", assertion.id()));
                         break;
                     case "assert-input": // Indicate when input tied to the current note is processed
                         if (currentNote != null && Objects.equals(assertion.sourceNoteId(), currentNote.id)) {
                              statusLabel.setText(String.format("Status: Processed input %s for current note", assertion.id()));
                         }
                         break;
                 }
            });
        }

        private void updateDerivationViewForAssertion(String type, Assertion assertion) {
            String line = String.format("P=%.3f %s [%s]", assertion.probability(), assertion.toKifString(), assertion.id());
            switch (type) {
                case "assert-added", "assert-input":
                     // Avoid adding duplicates if already shown (e.g., from displayExistingDerivations)
                    if (!derivationView.getText().contains(line)) {
                        derivationView.append(line + "\n");
                        // Optionally scroll to bottom, or keep position? Scroll for now.
                        derivationView.setCaretPosition(derivationView.getDocument().getLength());
                    }
                    break;
                case "assert-retracted", "evict":
                    // Find and remove or comment out the line
                    String currentText = derivationView.getText();
                    String updatedText = currentText.lines()
                                                    .map(l -> l.contains("[" + assertion.id() + "]") ? "# " + type.toUpperCase() + ": " + l : l)
                                                    .reduce((s1, s2) -> s1 + "\n" + s2)
                                                    .orElse("");
                     if (!currentText.equals(updatedText)) {
                        derivationView.setText(updatedText);
                         // Restore caret position potentially? For now, just update.
                    }
                    break;
            }
        }

        private boolean isRelatedToNote(Assertion assertion, String targetNoteId) {
            if (targetNoteId == null) return false;
            if (targetNoteId.equals(assertion.sourceNoteId())) return true;

            // Check provenance using BFS
            Queue<String> toCheck = new LinkedList<>(assertion.directSupportingIds());
            Set<String> visited = new HashSet<>(assertion.directSupportingIds());
            visited.add(assertion.id());

            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                var parent = reasoner.assertionsById.get(currentId); // Safe concurrent read
                if (parent != null) {
                    if (targetNoteId.equals(parent.sourceNoteId())) return true;
                    parent.directSupportingIds().stream()
                          .filter(visited::add) // Process each support ID only once
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
            reasoner.assertionsById.values().stream() // Safe iteration on concurrent map values
                    .filter(a -> isRelatedToNote(a, note.id))
                    .sorted(Comparator.comparingLong(Assertion::timestamp).reversed()) // Show newest first? Or oldest? Let's try newest.
                    .forEach(a -> derivationView.append(String.format("P=%.3f %s [%s]\n", a.probability(), a.toKifString(), a.id())));
            derivationView.setCaretPosition(0); // Scroll to top
        }

        // Simple Note data class (static inner class)
        static class Note {
            final String id;
            String title;
            String text; // Mutable text field

            Note(String id, String title, String text) {
                this.id = Objects.requireNonNull(id);
                this.title = Objects.requireNonNull(title);
                this.text = Objects.requireNonNull(text);
            }

            @Override public String toString() { return title; }
            @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); }
            @Override public int hashCode() { return id.hashCode(); }
        }

        // Custom renderer for padding in JList
        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (c instanceof JLabel label) {
                    label.setBorder(new EmptyBorder(5, 10, 5, 10)); // Add padding
                    label.setFont(UI_DEFAULT_FONT); // Ensure font is applied
                }
                return c;
            }
        }
    }
}