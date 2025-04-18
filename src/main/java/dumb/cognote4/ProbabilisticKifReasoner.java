package dumb.cognote4; // Renamed package slightly to avoid IDE conflicts if old file exists

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
 * integrated with a Swing UI for Note->KIF distillation via LLM and mismatch resolution.
 *
 * Delivers a single, consolidated, refactored, and corrected Java file.
 */
public class ProbabilisticKifReasoner extends WebSocketServer {

    // --- Configuration & UI Styling ---
    private static final int UI_FONT_SIZE = 16;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);

    // --- Reasoner Parameters ---
    private final int beamWidth;
    private final int maxKbSize;
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl;
    private final String llmModel;

    // --- Knowledge Base State ---
    private final ConcurrentMap<String, ConcurrentMap<KifList, Assertion>> factIndex = new ConcurrentHashMap<>(); // Predicate -> Fact -> Assertion
    private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>(); // ID -> Assertion
    private final Set<Rule> rules = ConcurrentHashMap.newKeySet(); // Rule definitions
    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis()); // Unique ID generation
    private final Set<KifList> kbContentSnapshot = ConcurrentHashMap.newKeySet(); // Fast existence check for facts
    private final PriorityBlockingQueue<Assertion> kbPriorityQueue = new PriorityBlockingQueue<>(); // For capacity management (lowest prob first)
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>(); // Track assertions per note

    // --- Input Queue ---
    private final PriorityBlockingQueue<InputMessage> inputQueue = new PriorityBlockingQueue<>(); // Prioritized input processing

    // --- Reasoning Engine State ---
    private final PriorityQueue<PotentialAssertion> beam = new PriorityQueue<>(); // Candidates for KB addition (highest prob first)
    private final Set<KifList> beamContentSnapshot = ConcurrentHashMap.newKeySet(); // Fast check for beam duplicates
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>(); // Registered callbacks

    // --- Execution & Control ---
    private final ExecutorService reasonerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ReasonerThread")); // Main reasoning loop
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Use virtual threads for blocking LLM calls
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor()) // Also use virtual threads here
            .build();
    private volatile boolean running = true; // Control flag for graceful shutdown
    private final SwingUI swingUI; // Reference to the UI

    // --- Constructor ---
    public ProbabilisticKifReasoner(int port, int beamWidth, int maxKbSize, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.beamWidth = Math.max(1, beamWidth);
        this.maxKbSize = Math.max(10, maxKbSize);
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = Objects.requireNonNullElse(llmUrl, "http://localhost:11434/api/chat");
        this.llmModel = Objects.requireNonNullElse(llmModel, "llamablit"); // Default model
        this.swingUI = Objects.requireNonNull(ui, "SwingUI cannot be null");
        System.out.printf("Reasoner config: Port=%d, Beam=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s%n",
                port, this.beamWidth, this.maxKbSize, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel);
    }

    // --- Main Method ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            int port = 8887, beamWidth = 64, maxKbSize = 64 * 1024; // Increased default KB size
            String rulesFile = null, llmUrl = null, llmModel = null;
            var broadcastInput = false;

            // Command-line argument parsing
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
                    System.err.println("Error parsing argument for " + (i > 0 ? args[i - 1] : args[i]) + ": " + e.getMessage());
                    printUsageAndExit();
                } catch (Exception e) {
                    System.err.println("Error parsing args: " + e.getMessage());
                    printUsageAndExit();
                }
            }

            ProbabilisticKifReasoner server = null;
            SwingUI ui = null;
            try {
                // Initialize UI first, then reasoner, then link them
                ui = new SwingUI(null);
                server = new ProbabilisticKifReasoner(port, beamWidth, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.reasoner = server;
                final var finalServer = server; // For shutdown hook

                // Graceful shutdown hook
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    if (finalServer != null) finalServer.stopReasoner();
                }));

                // Load initial rules/facts from file if specified
                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified.");

                // Start reasoner and WebSocket server threads
                server.startReasoner();
                // Make UI visible
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
          -k, --kb-size <maxKbSize>   Max KB assertion count (default: 65536)
          -r, --rules <rulesFile>     Path to file with initial KIF rules/facts
          --llm-url <url>             URL for the LLM API (default: http://localhost:11434/api/chat)
          --llm-model <model>         LLM model name (default: llama3)
          --broadcast-input           Broadcast input assertions via WebSocket""");
        System.exit(1);
    }

    // --- WebSocket Communication ---
    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        var message = switch (type) {
            case "assert-added" -> String.format("assert-derived %.4f %s", assertion.probability(), kifString);
            case "assert-input" -> String.format("assert-input %.4f %s", assertion.probability(), kifString);
            case "assert-retracted" -> String.format("retract %s", assertion.id());
            case "evict" -> String.format("evict %s", assertion.id());
            default -> type + " " + kifString; // Fallback
        };
        try {
            // Check if server is running and connection list is not empty before broadcasting
//            if (this.server != null && !this.server.isClosed() && !getConnections().isEmpty()) {
                broadcast(message);
//            } else {
                // System.out.println("WS broadcast skipped (server not ready/closed or no connections): " + message);
//            }
        } catch (Exception e) {
            System.err.println("Error during WebSocket broadcast: " + e.getMessage());
        }
    }

    // --- File Loading ---
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

                // Basic parenthesis balancing check across lines
                parenDepth += line.chars().filter(c -> c == '(').count() - line.chars().filter(c -> c == ')').count();
                kifBuffer.append(line).append(" "); // Append with space separator

                if (parenDepth == 0 && kifBuffer.length() > 0) { // Potential complete expression(s)
                    var kifText = kifBuffer.toString().trim();
                    kifBuffer.setLength(0); // Reset buffer
                    if (!kifText.isEmpty()) {
                        processedCount++;
                        try {
                            var terms = KifParser.parseKif(kifText); // Can parse multiple top-level expressions
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
        System.out.printf("Processed %d expressions from %s, queued %d items.%n", processedCount, filename, queuedCount);
    }

    // --- Input Processing ---
    private void queueExpressionFromSource(KifTerm term, String sourceId) {
        if (term instanceof KifList list) {
            var opOpt = list.getOperator();
            if (opOpt.isPresent()) {
                String op = opOpt.get();
                switch (op) {
                    case "=>", "<=>" -> inputQueue.put(new RuleMessage(list.toKifString(), sourceId));
                    case "exists" -> handleExists(list, sourceId); // Skolemize and queue resulting assertions/rules
                    case "forall" -> handleForall(list, sourceId); // Convert to rule if possible
                    default -> { // Assume fact
                        if (!list.terms().isEmpty()) {
                            // Allow non-ground facts to be queued; processAssertionInput will decide what to do
                            inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                        } else {
                            System.err.println("Warning: Ignoring empty list from " + sourceId);
                        }
                    }
                }
            } else { // List starting with variable or another list
                if (!list.terms().isEmpty()) {
                    // Treat as potential assertion (likely non-ground)
                    inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                } else {
                    System.err.println("Warning: Ignoring empty list from " + sourceId);
                }
            }
        } else { // Non-list term (Constant or Variable) at top level - ignore
            System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
        }
    }

    private void handleExists(KifList existsExpr, String sourceId) {
        // Simplified Skolemization: (exists (?X) (P ?X)) becomes (P skolem_X_timestamp)
        if (existsExpr.size() != 3) {
            System.err.println("Invalid 'exists' format (requires var(s) and body): " + existsExpr.toKifString());
            return;
        }
        KifTerm varsTerm = existsExpr.get(1);
        KifTerm body = existsExpr.get(2);
        Set<KifVariable> variables = KifTerm.collectVariablesFromSpec(varsTerm);

        if (variables.isEmpty()) {
            System.err.println("Warning: 'exists' with no variables: " + existsExpr.toKifString() + ". Processing body directly.");
            queueExpressionFromSource(body, sourceId + "-existsBody");
            return;
        }

        Map<KifVariable, KifTerm> skolemBindings = variables.stream()
                .collect(Collectors.toMap(
                        var -> var,
                        var -> new KifConstant("skolem_" + var.name().substring(1) + "_" + idCounter.incrementAndGet()),
                        (v1, v2) -> v1 // Should not happen with unique IDs
                ));

        KifTerm skolemizedBody = Unifier.substitute(body, skolemBindings);
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);
        queueExpressionFromSource(skolemizedBody, sourceId + "-skolemized"); // Queue the resulting term(s)
    }

    private void handleForall(KifList forallExpr, String sourceId) {
        // Simplified: Only handle (forall (vars...) (=> ant con)) -> (=> ant con)
        if (forallExpr.size() != 3) {
            System.err.println("Invalid 'forall' format (requires var(s) and body): " + forallExpr.toKifString());
            return;
        }
        KifTerm body = forallExpr.get(2);
        if (body instanceof KifList bodyList && bodyList.getOperator().filter("=>"::equals).isPresent()) {
            System.out.println("Interpreting 'forall ... (=> ant con)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
            inputQueue.put(new RuleMessage(bodyList.toKifString(), sourceId + "-forallRule"));
        } else {
            System.err.println("Warning: Ignoring 'forall' expression not directly convertible to simple rule: " + forallExpr.toKifString());
        }
    }

    // --- Public Control Methods ---
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
                // System.out.println(name + " executor stopped gracefully."); // Reduce shutdown verbosity
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for " + name + " executor shutdown.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // --- WebSocket Server Implementation ---
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
        String remoteAddress = (conn != null && conn.getRemoteSocketAddress() != null) ? conn.getRemoteSocketAddress().toString() : "server";
        if (ex instanceof IOException || (ex.getMessage() != null && (ex.getMessage().contains("Socket closed") || ex.getMessage().contains("Connection reset")))) {
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
            // Try parsing multiple expressions first
            var terms = KifParser.parseKif(trimmed);
            if (!terms.isEmpty()) {
                if (terms.size() > 1) System.err.println("Warning: Multiple top-level KIF expressions in one WS message, processing all.");
                for (var term : terms) {
                    queueExpressionFromSource(term, sourceId);
                }
            } else { // If parsing multiple failed, try the "probability (kif)" format
                var m = Pattern.compile("^([0-9.]+)\\s*(\\(.*\\))$", Pattern.DOTALL).matcher(trimmed);
                if (m.matches()) {
                    var probability = Double.parseDouble(m.group(1));
                    var kifStr = m.group(2);
                    if (probability < 0.0 || probability > 1.0) throw new NumberFormatException("Probability must be between 0.0 and 1.0");

                    var probTerms = KifParser.parseKif(kifStr); // Parse the KIF part
                    if (probTerms.isEmpty()) throw new ParseException("Empty KIF message received with probability.");
                    if (probTerms.size() > 1) System.err.println("Warning: Multiple top-level KIF expressions with single probability, applying probability to all.");

                    for(var term : probTerms) {
                        if (term instanceof KifList list && !list.terms().isEmpty()) {
                            submitMessage(new AssertMessage(probability, list.toKifString(), sourceId, null));
                        } else {
                            throw new ParseException("Expected non-empty KIF list after probability: " + term.toKifString());
                        }
                    }
                } else throw new ParseException("Invalid format. Expected KIF list(s) '(..)' or 'probability (..)'");
            }
        } catch (ParseException | NumberFormatException | ClassCastException e) {
            System.err.printf("WS Message Error from %s: %s | Original: %s%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), 100)));
        } catch (Exception e) {
            System.err.println("Unexpected WS message processing error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Reasoner Core Loop ---
    private void reasonerLoop() {
        System.out.println("Reasoner loop started.");
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Prioritize processing input messages over beam steps
                InputMessage msg = inputQueue.poll(); // Check input queue without waiting initially
                if (msg != null) {
                    processInputMessage(msg);
                } else if (!beam.isEmpty()) { // If no input, process beam
                    processBeamStep();
                } else { // If input queue and beam are empty, wait briefly for new input
                    msg = inputQueue.poll(100, TimeUnit.MILLISECONDS); // Wait for input
                    if (msg != null) processInputMessage(msg);
                    else Thread.onSpinWait(); // Still nothing, brief pause
                }
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
        // Only process first term if multiple were somehow in the message string (should be handled by WS receiver now)
        var term = KifParser.parseKif(am.kifString()).getFirst();
        if (term instanceof KifList fact) {
            if (fact.terms().isEmpty()) {
                System.err.println("Assertion ignored (empty list): " + fact.toKifString());
                return;
            }
            // Only ground facts can be added to the beam/KB currently
            if (!fact.containsVariable()) {
                var pa = new PotentialAssertion(fact, am.probability(), Collections.emptySet(), am.sourceNoteId());
                addToBeam(pa);

                // Trigger callback for input event (UI might need it)
                var tempId = "input-" + idCounter.incrementAndGet();
                var inputAssertion = new Assertion(tempId, fact, am.probability(), System.currentTimeMillis(), am.sourceNoteId(), Collections.emptySet());
                invokeCallbacks("assert-input", inputAssertion);

            } else {
                System.err.println("Warning: Non-ground assertion received: " + fact.toKifString() + ". Cannot be added to KB directly by current reasoner.");
                // Consider adding to a separate store or triggering specific callbacks if needed
            }
        } else {
            System.err.println("Assertion input ignored (not a KIF list): " + term.toKifString());
        }
    }

    private void processRetractionByIdInput(RetractByIdMessage rm) {
        retractAssertion(rm.assertionId(), "retract-id");
    }

    private void processRetractionByNoteIdInput(RetractByNoteIdMessage rnm) {
        var idsToRetract = noteIdToAssertionIds.remove(rnm.noteId()); // Remove mapping first
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.println("Retracting " + idsToRetract.size() + " assertions for note: " + rnm.noteId());
            // Create a snapshot for iteration to avoid ConcurrentModificationException if retract causes callbacks
            new HashSet<>(idsToRetract).forEach(id -> retractAssertion(id, "retract-note"));
        }
    }

    private void processRetractionRuleInput(RetractRuleMessage rrm) throws ParseException {
        var term = KifParser.parseKif(rrm.ruleKif()).getFirst();
        if (term instanceof KifList ruleForm) {
            boolean removed = rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));
            // Handle <=> removal by checking both directions if direct match failed
            if (!removed && ruleForm.getOperator().filter("<=>"::equals).isPresent() && ruleForm.size() == 3) {
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
                if (list.get(1) instanceof KifTerm antTerm && list.get(2) instanceof KifTerm conTerm) {
                    addRuleInternal(list, antTerm, conTerm);
                    if (op.equals("<=>")) {
                        addRuleInternal(new KifList(new KifConstant("=>"), conTerm, antTerm), conTerm, antTerm);
                    }
                } else {
                    System.err.println("Invalid rule structure (ant/con not valid KIF terms): " + ruleMsg.kifString());
                }
            } else {
                System.err.println("Invalid rule format (expected '(=> ant con)' or '(<=> ant con)'): " + ruleMsg.kifString());
            }
        } else System.err.println("Rule input ignored (not a KIF list): " + ruleMsg.kifString());
    }

    private void addRuleInternal(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
        var ruleId = "rule-" + idCounter.incrementAndGet();
        try {
            var newRule = new Rule(ruleId, ruleForm, antecedent, consequent); // Validation happens in constructor
            if (rules.add(newRule)) System.out.println("Added rule: " + ruleId + " " + newRule.ruleForm().toKifString());
            else System.out.println("Rule ignored (duplicate): " + newRule.ruleForm().toKifString());
        } catch (IllegalArgumentException e) {
            // Constructor validation failed
            System.err.println("Failed to create rule: " + e.getMessage() + " for " + ruleForm.toKifString());
        }
    }

    private void registerCallback(RegisterCallbackMessage rcm) {
        if (rcm.pattern() instanceof KifTerm patternTerm) {
            callbackRegistrations.add(new CallbackRegistration(patternTerm, rcm.callback()));
            System.out.println("Registered external callback for pattern: " + patternTerm.toKifString());
        } else {
            System.err.println("Callback registration failed: Invalid pattern object.");
        }
    }

    // --- Beam Search Step ---
    private void processBeamStep() {
        List<PotentialAssertion> candidates = new ArrayList<>(beamWidth);
        synchronized (beam) {
            int count = 0;
            while (count < beamWidth && !beam.isEmpty()) {
                PotentialAssertion pa = beam.poll(); // Highest probability assertion
                if (pa == null) continue;
                beamContentSnapshot.remove(pa.fact()); // Remove from snapshot regardless
                if (!kbContentSnapshot.contains(pa.fact())) { // Add to candidates only if not already in KB
                    candidates.add(pa);
                    count++;
                }
            }
        }
        // Add candidates to KB and trigger derivations outside the beam lock
        candidates.forEach(pa -> {
            Assertion newAssertion = addAssertionToKb(pa);
            if (newAssertion != null) {
                deriveFrom(newAssertion);
            }
        });
    }

    // --- Forward Chaining Derivation ---
    private void deriveFrom(Assertion newAssertion) {
        rules.stream()./*parallelStream().*/forEach(rule -> { // Parallelize rule matching
            List<KifList> antClauses = rule.getAntecedentClauses();
            if (antClauses.isEmpty()) return;

            // Optimization: check if new assertion's predicate can match *any* antecedent predicate
            newAssertion.fact().getOperator().ifPresent(newFactOp -> {
                boolean opMatchPossible = antClauses.stream()
                        .anyMatch(clause -> clause.getOperator().map(op -> op.equals(newFactOp)).orElse(false));

                if (opMatchPossible) {
                    var antClauseCount = antClauses.size();
                    IntStream.range(0, antClauseCount).forEach(i -> {
                        var clauseToMatch = antClauses.get(i);
                        // Attempt unification only if predicates match (or clause starts with var)
                        if (clauseToMatch.getOperator().map(op -> op.equals(newFactOp)).orElse(true)) {
                            var bindings = Unifier.unify(clauseToMatch, newAssertion.fact(), Map.of());
                            if (bindings != null) {
                                // Found initial binding, now try to satisfy remaining clauses
                                var remaining = IntStream.range(0, antClauseCount).filter(idx -> idx != i).mapToObj(antClauses::get).toList();
                                Set<String> initialSupport = Set.of(newAssertion.id());

                                findMatches(remaining, bindings, initialSupport).forEach(match -> {
                                    // Successfully matched all antecedents
                                    var consequentTerm = Unifier.substitute(rule.consequent(), match.bindings());
                                    if (consequentTerm instanceof KifList derived && !derived.containsVariable()) {
                                        // Derived a new ground fact
                                        var supportIds = match.supportingAssertionIds(); // Already includes initial support
                                        var prob = calculateDerivedProbability(supportIds);
                                        var inheritedNoteId = findCommonSourceNodeId(supportIds);
                                        addToBeam(new PotentialAssertion(derived, prob, supportIds, inheritedNoteId));
                                    }
                                    // Ignore non-list or non-ground consequents for now
                                });
                            }
                        }
                    });
                }
            });
        });
    }

    // --- Helper for Finding Matches for Remaining Clauses ---
    private Stream<MatchResult> findMatches(List<KifList> remainingClauses, Map<KifVariable, KifTerm> bindings, Set<String> supportIds) {
        if (remainingClauses.isEmpty()) {
            return Stream.of(new MatchResult(bindings, supportIds)); // Base case: all matched
        }

        var clause = remainingClauses.getFirst();
        var nextRemaining = remainingClauses.subList(1, remainingClauses.size());
        var substTerm = Unifier.substitute(clause, bindings); // Apply current bindings

        if (!(substTerm instanceof KifList substClause)) {
            // Antecedent clause reduced to non-list (e.g., just a variable bound to a constant) - unsupported rule structure for matching?
            System.err.println("Warning: Rule antecedent clause became non-list after substitution: " + substTerm.toKifString() + " in rule (matching phase).");
            return Stream.empty();
        }

        // Find candidate assertions in KB that *could* match the substituted clause
        Stream<Assertion> candidates = findCandidateAssertions(substClause);

        if (substClause.containsVariable()) {
            // Clause still has variables, need to unify with candidates
            return candidates.flatMap(candidate -> {
                var newBindings = Unifier.unify(substClause, candidate.fact(), bindings); // Try unifying candidate with clause
                if (newBindings != null) {
                    // Successful unification, add support and recurse
                    Set<String> nextSupport = new HashSet<>(supportIds);
                    nextSupport.add(candidate.id());
                    return findMatches(nextRemaining, newBindings, nextSupport);
                }
                return Stream.empty(); // Candidate didn't unify
            });
        } else {
            // Ground clause, find exact matches in KB
            return candidates
                    .filter(a -> a.fact().equals(substClause)) // Filter for exact match
                    .flatMap(match -> {
                        // Found match, add support and recurse
                        Set<String> nextSupport = new HashSet<>(supportIds);
                        nextSupport.add(match.id());
                        return findMatches(nextRemaining, bindings, nextSupport); // Bindings don't change for ground match
                    });
        }
    }


    // --- KB Querying & Management ---
    private Stream<Assertion> findCandidateAssertions(KifList clause) {
        // Use index if operator is present and constant
        return clause.getOperator()
                .map(factIndex::get) // Get the map for this predicate
                .map(ConcurrentMap::values) // Get the assertions
                .map(Collection::stream)
                .orElseGet(() -> { // Fallback: clause starts with var or no operator
                    // System.err.println("Warning: Full KB scan needed for clause: " + clause.toKifString()); // Potentially noisy
                    return assertionsById.values().stream().filter(Objects::nonNull); // Stream all assertions
                });
    }

    private String findCommonSourceNodeId(Set<String> supportIds) {
        if (supportIds == null || supportIds.isEmpty()) return null;
        String commonId = null;
        boolean first = true;
        for (var id : supportIds) {
            var assertion = assertionsById.get(id); // Read from concurrent map is safe
            if (assertion == null) continue; // Should not happen if IDs are valid
            var noteId = assertion.sourceNoteId();
            if (first) {
                if (noteId == null) return null; // If first has no source, impossible to have common source
                commonId = noteId;
                first = false;
            } else if (!Objects.equals(commonId, noteId)) { // Handles null mismatch correctly
                return null; // Mismatch found
            }
        }
        return commonId; // All matched or only one item
    }

    private double calculateDerivedProbability(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return 0.0;
        // Use minimum probability of supporting facts (weakest link semantics)
        return ids.stream()
                .map(assertionsById::get) // Read is safe
                .filter(Objects::nonNull)
                .mapToDouble(Assertion::probability)
                .min()
                .orElse(0.0); // Default if no supporting assertions found (should not happen)
    }

    private Assertion addAssertionToKb(PotentialAssertion pa) {
        // Prevent adding exact duplicates
        if (kbContentSnapshot.contains(pa.fact())) return null;

        // Evict lowest probability assertion(s) if capacity reached BEFORE adding new one
        enforceKbCapacity();
        if (assertionsById.size() >= maxKbSize) {
            System.err.printf("Warning: KB still full (%d/%d) after trying eviction. Cannot add: %s%n", assertionsById.size(), maxKbSize, pa.fact().toKifString());
            return null; // Skip adding if eviction failed to make space
        }


        var id = "fact-" + idCounter.incrementAndGet();
        var timestamp = System.currentTimeMillis();
        // Create the final Assertion record
        var newAssertion = new Assertion(id, pa.fact(), pa.probability(), timestamp, pa.sourceNoteId(), pa.supportingAssertionIds());

        // Add to primary storage and indices
        if (assertionsById.putIfAbsent(id, newAssertion) == null) { // Ensure ID uniqueness atomically
            pa.fact().getOperator().ifPresent(p -> factIndex.computeIfAbsent(p, k -> new ConcurrentHashMap<>()).put(pa.fact(), newAssertion));
            kbContentSnapshot.add(pa.fact());
            kbPriorityQueue.put(newAssertion); // Add to eviction queue

            // Link to source note if applicable
            if (pa.sourceNoteId() != null) {
                noteIdToAssertionIds.computeIfAbsent(pa.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(id);
            }

            // Trigger callbacks and broadcasts
            invokeCallbacks("assert-added", newAssertion);
            return newAssertion;
        } else {
            System.err.println("KB Add Error: Collision detected for assertion ID: " + id + ". Skipping addition."); // Should be extremely rare
            return null;
        }
    }

    private void retractAssertion(String id, String reason) {
        // Remove from primary map first to make it immediately unavailable
        var removed = assertionsById.remove(id);
        if (removed != null) {
            // Remove from secondary structures
            kbContentSnapshot.remove(removed.fact());
            kbPriorityQueue.remove(removed); // Note: O(n) removal, could be slow if KB is huge and retractions frequent
            removed.fact().getOperator().ifPresent(p -> factIndex.computeIfPresent(p, (k, v) -> {
                v.remove(removed.fact()); // Remove specific fact from index
                return v.isEmpty() ? null : v; // Clean up predicate entry if map becomes empty
            }));
            // Remove from note tracking map
            if (removed.sourceNoteId() != null) {
                noteIdToAssertionIds.computeIfPresent(removed.sourceNoteId(), (k, v) -> {
                    v.remove(id);
                    return v.isEmpty() ? null : v; // Clean up note entry if set becomes empty
                });
            }
            // Trigger callbacks and broadcasts AFTER successful removal
            invokeCallbacks("assert-retracted", removed);
            // System.out.printf("KB Retract [%s] (%s): %s%n", id, reason, removed.toKifString()); // Optional logging
        }
        // Else: Assertion with this ID didn't exist (might have been evicted/retracted already)
    }

    private void enforceKbCapacity() {
        while (assertionsById.size() >= maxKbSize) {
            Assertion toEvict = kbPriorityQueue.poll(); // Get lowest probability assertion
            if (toEvict == null) break; // Queue is empty, nothing to evict

            // Double-check if it still exists before trying to retract (might have been retracted by another operation)
            Assertion evictedAssertion = assertionsById.get(toEvict.id());
            if (evictedAssertion != null) {
                // Use the main retraction logic, which handles all indices and triggers callbacks
                System.out.printf("KB Evict (Low Prio) [%s]: P=%.4f %s%n", evictedAssertion.id(), evictedAssertion.probability(), evictedAssertion.toKifString());
                retractAssertion(evictedAssertion.id(), "evict-capacity");
                // Retraction logic now calls invokeCallbacks("assert-retracted", ...).
                // We might want a distinct "evict" callback type *as well*. Let's trigger both.
                invokeCallbacks("evict", evictedAssertion); // Specific callback type for eviction
            }
        }
    }

    private void addToBeam(PotentialAssertion pa) {
        // Basic validity checks
        if (pa == null || pa.fact() == null) return;
        // Avoid adding if already in KB or already in the beam
        if (kbContentSnapshot.contains(pa.fact()) || beamContentSnapshot.contains(pa.fact())) return;

        synchronized (beam) { // Synchronize access to beam and its snapshot
            // Final check within synchronized block
            if (!kbContentSnapshot.contains(pa.fact()) && beamContentSnapshot.add(pa.fact())) { // Add to snapshot returns true if new
                beam.offer(pa); // Add to priority queue
            } else {
                // If add to snapshot failed, it means another thread added it between outer check and lock acquisition
                // No need to add to beam queue.
            }
        }
    }

    // --- Callback Handling ---
    private void invokeCallbacks(String type, Assertion assertion) {
        // 1. Direct Broadcasting
        switch (type) {
            case "assert-added", "assert-retracted", "evict":
                broadcastMessage(type, assertion); // Always broadcast these key events
                break;
            case "assert-input":
                if (broadcastInputAssertions) { // Only broadcast input if flag is set
                    broadcastMessage(type, assertion);
                }
                break;
        }

        // 2. UI Update (always try, UI checks if relevant)
        if (swingUI != null && swingUI.isDisplayable()) {
            swingUI.handleReasonerCallback(type, assertion);
        }

        // 3. Process Registered KIF Pattern Callbacks
        callbackRegistrations.forEach(reg -> {
            // Attempt unification between the pattern and the assertion's fact
            var bindings = Unifier.unify(reg.pattern(), assertion.fact(), Map.of());
            if (bindings != null) { // If unification successful, invoke the callback
                try {
                    reg.callback().onMatch(type, assertion, bindings);
                } catch (Exception e) {
                    System.err.println("Error in KIF callback execution: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    // --- LLM Interaction ---
    public CompletableFuture<String> getKifFromLlmAsync(String contextPrompt, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            // Default prompt if only note text is given
            var finalPrompt = contextPrompt;
            if (!contextPrompt.toLowerCase().contains("kif assertions:")) { // Check if it's a specific bridging prompt or the default note analysis
                finalPrompt = """
                    Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax).
                    Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                    Do not include explanations, markdown formatting (like ```kif), or comments.
                    Focus on factual statements and relationships. Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', attribute relations, etc. where appropriate.
                    If mentioning an entity whose specific name isn't given, use a KIF variable (e.g., `?cpu`, `?company`).

                    Note:
                    "%s"

                    KIF Assertions:""".formatted(contextPrompt); // Assume contextPrompt is the note text here
            }


            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", finalPrompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2)); // Lower temp for factual KIF

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60)) // Reasonable timeout for LLM
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var jsonResponse = new JSONObject(new JSONTokener(responseBody));
                    String kifResult = "";

                    // Handle common response structures
                    if (jsonResponse.has("message") && jsonResponse.getJSONObject("message").has("content")) {
                        kifResult = jsonResponse.getJSONObject("message").getString("content"); // Ollama chat standard
                    } else if (jsonResponse.has("choices") && !jsonResponse.getJSONArray("choices").isEmpty() &&
                            jsonResponse.getJSONArray("choices").getJSONObject(0).has("message") &&
                            jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").has("content")) {
                        kifResult = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"); // OpenAI standard
                    } else if (jsonResponse.has("response")) {
                        kifResult = jsonResponse.getString("response"); // Ollama generate standard
                    } else {
                        // Fallback: try to extract content if structure is unknown but contains "content" key
                        Optional<String> contentFallback = findNestedContent(jsonResponse);
                        if (contentFallback.isPresent()) {
                            System.err.println("Warning: Using fallback content extraction for LLM response.");
                            kifResult = contentFallback.get();
                        } else {
                            throw new IOException("Unexpected LLM response format. Body starts with: " + responseBody.substring(0, Math.min(responseBody.length(), 200)));
                        }
                    }


                    // Clean the raw KIF output: remove markdown, trim lines, filter for valid lists
                    var cleanedKif = kifResult.lines()
                            .map(s -> s.replaceAll("```kif", "").replaceAll("```", "").trim()) // Remove markdown code fences
                            .filter(line -> line.startsWith("(") && line.endsWith(")")) // Basic structural check
                            .filter(line -> !line.matches("^\\(\\s*\\)$")) // Remove empty parens ()
                            .collect(Collectors.joining("\n")); // Join valid lines with newline

                    if (cleanedKif.isEmpty() && !kifResult.isBlank()) {
                        System.err.println("LLM Warning ("+noteId+"): Result contained text but no valid KIF lines:\n---\n"+kifResult+"\n---");
                    } else if (!cleanedKif.isEmpty()) {
                        // System.out.println("LLM (" + noteId + ") Raw KIF Output:\n" + cleanedKif); // Log cleaned KIF
                    }
                    return cleanedKif; // Return potentially multi-line cleaned KIF string

                } else {
                    throw new IOException("LLM API request failed: " + response.statusCode() + " " + responseBody);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("LLM API interaction failed for note " + noteId + ": " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (Exception e) { // Catch JSON parsing errors etc.
                System.err.println("LLM response processing failed for note " + noteId + ": " + e.getMessage());
                e.printStackTrace();
                throw new CompletionException(e);
            }
        }, llmExecutor);
    }

    // Helper to search for "content" key recursively in JSON response
    private Optional<String> findNestedContent(JSONObject obj) {
        if (obj.has("content") && obj.get("content") instanceof String) {
            return Optional.of(obj.getString("content"));
        }
        for (String key : obj.keySet()) {
            Object value = obj.get(key);
            if (value instanceof JSONObject nestedObj) {
                Optional<String> found = findNestedContent(nestedObj);
                if (found.isPresent()) return found;
            } else if (value instanceof JSONArray arr) {
                for (int i = 0; i < arr.length(); i++) {
                    if (arr.get(i) instanceof JSONObject nestedObj) {
                        Optional<String> found = findNestedContent(nestedObj);
                        if (found.isPresent()) return found;
                    }
                }
            }
        }
        return Optional.empty();
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
            return Collections.unmodifiableSet(vars); // Return immutable set
        }

        private static void collectVariablesRecursive(KifTerm term, Set<KifVariable> vars) {
            switch (term) {
                case KifVariable v -> vars.add(v);
                case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars));
                case KifConstant c -> {} // No variables in constants
            }
        }

        // Helper to extract variables from a quantifier spec (e.g., ?X or (?X ?Y))
        static Set<KifVariable> collectVariablesFromSpec(KifTerm varsTerm) {
            Set<KifVariable> vars = new HashSet<>();
            if (varsTerm instanceof KifVariable singleVar) {
                vars.add(singleVar);
            } else if (varsTerm instanceof KifList varList) {
                varList.terms().stream()
                        .filter(KifVariable.class::isInstance)
                        .map(KifVariable.class::cast)
                        .forEach(vars::add);
                if (vars.size() != varList.size()) { // Check if list contained non-variables
                    System.err.println("Warning: Non-variable element found in quantifier variable list: " + varsTerm.toKifString());
                }
            } else {
                System.err.println("Warning: Invalid variable specification in quantifier (expected variable or list): " + varsTerm.toKifString());
            }
            return Collections.unmodifiableSet(vars);
        }
    }

    record KifConstant(String value) implements KifTerm {
        KifConstant { Objects.requireNonNull(value, "Constant value cannot be null"); }
        @Override
        public void writeKifString(StringBuilder sb) {
            // Quote if empty, contains whitespace, or special KIF chars '()";?' or common delimiters like '_'
            boolean needsQuotes = value.isEmpty() || value.chars().anyMatch(c ->
                    Character.isWhitespace(c) || "()\";?_".indexOf(c) != -1
            );
            if (needsQuotes) sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            else sb.append(value);
        }
    }

    record KifVariable(String name) implements KifTerm {
        KifVariable { Objects.requireNonNull(name); if (!name.startsWith("?")) throw new IllegalArgumentException("Variable name must start with '?'"); }
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
            var n = terms.size();
            IntStream.range(0, n).forEach(i -> {
                terms.get(i).writeKifString(sb);
                if (i < n - 1) sb.append(' ');
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
        // Compare by probability for PriorityBlockingQueue (lower probability = higher priority for eviction)
        @Override public int compareTo(Assertion other) { return Double.compare(this.probability, other.probability); }
        String toKifString() { return fact.toKifString(); }
    }

    record Rule(String id, KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
        private static final KifConstant AND_OP = new KifConstant("and");

        Rule { // Canonical constructor with refined validation
            Objects.requireNonNull(id);
            Objects.requireNonNull(ruleForm);
            Objects.requireNonNull(antecedent);
            Objects.requireNonNull(consequent);

            // Basic rule form validation (=> or <=>)
            if (!(ruleForm.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent() && ruleForm.size() == 3))
                throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());

            // Validate antecedent structure (must be list or list of lists via 'and')
            if (antecedent instanceof KifList antList) {
                if (antList.getOperator().filter("and"::equals).isPresent()) {
                    if (antList.terms().stream().skip(1).anyMatch(t -> !(t instanceof KifList)))
                        throw new IllegalArgumentException("Antecedent 'and' must contain only lists: " + ruleForm.toKifString());
                }
            } else {
                // Allow KifVariable? No, requires list for clause extraction.
                throw new IllegalArgumentException("Antecedent must be a KIF list: " + ruleForm.toKifString());
            }

            // --- Refined Unbound Variable Check (Handles quantifiers in consequent) ---
            Set<KifVariable> antVars = antecedent.getVariables();
            Set<KifVariable> conVarsAll = consequent.getVariables();

            // Find variables bound by quantifiers *within* the consequent
            Set<KifVariable> conVarsBoundLocally = getQuantifierBoundVariables(consequent);

            // Determine which consequent variables NEED binding from the antecedent
            Set<KifVariable> conVarsNeedingBinding = new HashSet<>(conVarsAll);
            conVarsNeedingBinding.removeAll(conVarsBoundLocally);

            // Check if any variables needing binding are missing from the antecedent
            conVarsNeedingBinding.removeAll(antVars); // Remove variables that ARE bound by antecedent

            // Issue warning only for truly unbound variables (unless it's <=>, where unbound consequent vars are okay)
            if (!conVarsNeedingBinding.isEmpty() && !ruleForm.getOperator().filter("<=>"::equals).isPresent()) {
                System.err.println("Warning: Rule consequent contains variables not bound by antecedent or local quantifier: " +
                        conVarsNeedingBinding.stream().map(KifVariable::name).collect(Collectors.joining(", ")) +
                        " in " + ruleForm.toKifString());
            }
        }

        // Extracts antecedent clauses: handles single clause or (and clause1 clause2 ...)
        List<KifList> getAntecedentClauses() {
            return switch (antecedent) {
                case KifList list -> {
                    if (list.size() > 1 && list.get(0).equals(AND_OP)) {
                        // (and c1 c2 ...) -> [c1, c2, ...]
                        yield list.terms().subList(1, list.size()).stream()
                                .filter(KifList.class::isInstance).map(KifList.class::cast)
                                .toList();
                    } else { // Assume single clause list like (instance ?X Dog)
                        yield List.of(list);
                    }
                }
                default -> List.of(); // Should not happen due to constructor validation
            };
        }

        // --- Helper Method to find variables bound by quantifiers within a term ---
        private static Set<KifVariable> getQuantifierBoundVariables(KifTerm term) {
            Set<KifVariable> boundVars = new HashSet<>();
            collectQuantifierBoundVariablesRecursive(term, boundVars);
            return Collections.unmodifiableSet(boundVars);
        }

        private static void collectQuantifierBoundVariablesRecursive(KifTerm term, Set<KifVariable> boundVars) {
            if (term instanceof KifList list) {
                Optional<String> opOpt = list.getOperator();
                // Check if it's a quantifier expression
                if (opOpt.isPresent() && (opOpt.get().equals("exists") || opOpt.get().equals("forall")) && list.size() == 3) {
                    KifTerm varsTerm = list.get(1);
                    KifTerm body = list.get(2);
                    // Add variables declared in this quantifier scope
                    boundVars.addAll(KifTerm.collectVariablesFromSpec(varsTerm));
                    // Recurse into the body of the quantifier
                    collectQuantifierBoundVariablesRecursive(body, boundVars);
                } else {
                    // Not a quantifier, recurse into all subterms
                    list.terms().forEach(subTerm -> collectQuantifierBoundVariablesRecursive(subTerm, boundVars));
                }
            }
            // No action needed for KifConstant or KifVariable base cases here
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

        // Main parsing entry point
        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try { return new KifParser(input).parseTopLevel(); }
            catch (IOException e) { throw new ParseException("Read error: " + e.getMessage()); } // Should be rare with StringReader
        }

        // Parses multiple top-level KIF terms until EOF
        private List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>();
            consumeWhitespaceAndComments();
            while (peek() != -1) {
                terms.add(parseTerm());
                consumeWhitespaceAndComments();
            }
            return Collections.unmodifiableList(terms);
        }

        // Parses a single KIF term (List, Constant, Variable)
        private KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments();
            int c = peek();
            if (c == -1) throw createParseException("Unexpected end of input while expecting term");
            return switch (c) {
                case '(' -> parseList();
                case '"' -> parseQuotedString();
                case '?' -> parseVariable();
                default -> parseAtom(); // Parses constants (including numbers as strings)
            };
        }

        // Parses a KIF list: (term1 term2 ...)
        private KifList parseList() throws IOException, ParseException {
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

        // Parses a KIF variable: ?VarName
        private KifVariable parseVariable() throws IOException, ParseException {
            consumeChar('?'); // Consume '?'
            var name = new StringBuilder("?");
            if (!isValidAtomChar(peek(), true)) throw createParseException("Variable name expected after '?'");
            while (isValidAtomChar(peek(), false)) name.append((char) consumeChar()); // Consume valid atom chars
            if (name.length() == 1) throw createParseException("Empty variable name after '?'");
            return new KifVariable(name.toString());
        }

        // Parses a KIF atom (becomes a KifConstant)
        private KifConstant parseAtom() throws IOException, ParseException {
            var atom = new StringBuilder();
            if (!isValidAtomChar(peek(), true)) throw createParseException("Invalid start character for an atom");
            while (isValidAtomChar(peek(), false)) atom.append((char) consumeChar());
            if (atom.isEmpty()) throw createParseException("Empty atom encountered");
            return new KifConstant(atom.toString());
        }

        // Checks if a character is valid for an unquoted atom name
        private boolean isValidAtomChar(int c, boolean isFirstChar) {
            if (c == -1 || Character.isWhitespace(c) || "()\";?".indexOf(c) != -1) return false;
            // SUMO KIF atoms are quite permissive, can include -, _, etc.
            return true;
        }

        // Parses a quoted string: "..."
        private KifConstant parseQuotedString() throws IOException, ParseException {
            consumeChar('"'); // Consume opening quote
            var sb = new StringBuilder();
            while (true) {
                int c = consumeChar();
                if (c == '"') return new KifConstant(sb.toString()); // Closing quote
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') { // Handle escape sequences
                    int next = consumeChar();
                    if (next == -1) throw createParseException("Unmatched quote after escape character");
                    // Basic escapes: \\ -> \, \" -> ", \n -> newline, \t -> tab
                    sb.append((char) switch(next) { case 'n' -> '\n'; case 't' -> '\t'; default -> next; });
                } else {
                    sb.append((char) c);
                }
            }
        }

        // --- Reader Helpers ---
        private int peek() throws IOException {
            if (currentChar == -2) currentChar = reader.read();
            return currentChar;
        }

        private int consumeChar() throws IOException {
            int c = peek(); // Get current char (reads if needed)
            if (c != -1) {
                currentChar = -2; // Mark as consumed for next peek
                // Update line/col tracking
                if (c == '\n') { line++; col = 0; }
                else col++;
            }
            return c;
        }

        private void consumeChar(char expected) throws IOException, ParseException {
            int c = consumeChar();
            if (c != expected) throw createParseException("Expected '" + expected + "' but found " + (c == -1 ? "EOF" : "'" + (char) c + "'"));
        }

        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                int c = peek();
                if (c == -1) break; // EOF
                if (Character.isWhitespace(c)) { consumeChar(); continue; } // Consume whitespace
                if (c == ';') { // Line comment
                    consumeChar(); // Consume ';'
                    while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar(); // Consume until EOL/EOF
                    continue; // Loop again to consume EOL and potential following whitespace/comments
                }
                break; // Not whitespace or comment start
            }
        }

        private ParseException createParseException(String message) {
            return new ParseException(message + " at line " + line + " col " + col);
        }
    }

    static class ParseException extends Exception { ParseException(String message) { super(message); } }

    // --- Input Message Types (using priority for ordering) ---
    sealed interface InputMessage extends Comparable<InputMessage> {
        double getPriority(); // Higher value = higher priority
        String getSourceId();
    }

    // Priority: Assertions (lowest, depends on prob) < Callbacks < Rules < Retractions (highest)
    record AssertMessage(double probability, String kifString, String sourceId, String sourceNoteId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.probability, other.getPriority()); } // Higher probability first among asserts
        @Override public double getPriority() { return probability; } // Priority is the probability itself
        @Override public String getSourceId() {return sourceId;}
    }
    record RegisterCallbackMessage(KifTerm pattern, KifCallback callback, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 7.0; } // Medium-low priority
        @Override public String getSourceId() {return sourceId;}
    }
    record RuleMessage(String kifString, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 8.0; } // Medium priority
        @Override public String getSourceId() {return sourceId;}
    }
    record RetractRuleMessage(String ruleKif, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 9.0; } // Medium-high priority
        @Override public String getSourceId() {return sourceId;}
    }
    record RetractByNoteIdMessage(String noteId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 10.0; } // High priority
        @Override public String getSourceId() {return sourceId;}
    }
    record RetractByIdMessage(String assertionId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage other) { return Double.compare(this.getPriority(), other.getPriority()); }
        @Override public double getPriority() { return 10.0; } // High priority (same as by Note ID)
        @Override public String getSourceId() {return sourceId;}
    }


    // --- Reasoner Internals ---
    // Represents a candidate assertion for the KB, prioritized by probability for beam search
    record PotentialAssertion(KifList fact, double probability, Set<String> supportingAssertionIds, String sourceNoteId)
            implements Comparable<PotentialAssertion> {
        PotentialAssertion { // Canonical constructor for validation/immutability
            Objects.requireNonNull(fact);
            Objects.requireNonNull(supportingAssertionIds);
            if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]");
            supportingAssertionIds = Set.copyOf(supportingAssertionIds); // Ensure immutable set
        }
        // Compare by probability DESCENDING for PriorityQueue used as MAX-heap (beam selection)
        @Override public int compareTo(PotentialAssertion other) { return Double.compare(other.probability, this.probability); }
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && fact.equals(pa.fact); } // Equality based on fact content
        @Override public int hashCode() { return fact.hashCode(); }
    }

    // Represents a registered callback with a KIF pattern
    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
        CallbackRegistration { Objects.requireNonNull(pattern); Objects.requireNonNull(callback); }
    }

    // Represents the result of matching rule antecedents
    record MatchResult(Map<KifVariable, KifTerm> bindings, Set<String> supportingAssertionIds) {
        MatchResult { Objects.requireNonNull(bindings); Objects.requireNonNull(supportingAssertionIds); }
    }

    // Functional interface for callbacks
    @FunctionalInterface interface KifCallback { void onMatch(String type, Assertion assertion, Map<KifVariable, KifTerm> bindings); }

    // --- Unification Logic ---
    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 100; // Prevents infinite loops from cyclic bindings (though occurs check should prevent)

        // Main unification entry point
        static Map<KifVariable, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVariable, KifTerm> bindings) {
            if (bindings == null) return null; // Unification failed in recursive call
            // Apply existing bindings fully before comparing/binding
            KifTerm xSubst = fullySubstitute(x, bindings);
            KifTerm ySubst = fullySubstitute(y, bindings);

            if (xSubst.equals(ySubst)) return bindings; // Already equal
            if (xSubst instanceof KifVariable v) return bindVariable(v, ySubst, bindings); // Bind variable x
            if (ySubst instanceof KifVariable v) return bindVariable(v, xSubst, bindings); // Bind variable y

            // If both are lists, unify element-wise
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lx.size(); i++) {
                    currentBindings = unify(lx.get(i), ly.get(i), currentBindings); // Recurse
                    if (currentBindings == null) return null; // Unification failed deeper down
                }
                return currentBindings; // Successful list unification
            }
            return null; // Mismatch (constants, different list sizes, etc.)
        }

        // Public method to apply bindings to a term
        static KifTerm substitute(KifTerm term, Map<KifVariable, KifTerm> bindings) { return fullySubstitute(term, bindings); }

        // Recursively applies substitutions until no more changes occur or depth limit reached
        private static KifTerm fullySubstitute(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term; // No bindings or no variables, nothing to substitute
            var current = term;
            for (var depth = 0; depth < MAX_SUBST_DEPTH; depth++) {
                var next = substituteOnce(current, bindings);
                if (next == current) return current; // Reference equality check (fastest)
                if (next.equals(current)) return current; // Equality check if reference changed but content didn't
                current = next; // Continue substituting
            }
            System.err.println("Warning: Substitution depth limit reached for: " + term.toKifString()); // Should be rare
            return current;
        }

        // Applies substitutions one level deep
        private static KifTerm substituteOnce(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            return switch (term) {
                case KifVariable v -> bindings.getOrDefault(v, v); // Substitute variable if binding exists
                case KifList l -> {
                    // Efficiently create new list only if a subterm changes
                    List<KifTerm> originalTerms = l.terms();
                    List<KifTerm> substitutedTerms = null; // Lazily initialized
                    boolean changed = false;
                    for (int i = 0; i < originalTerms.size(); i++) {
                        KifTerm originalTerm = originalTerms.get(i);
                        KifTerm substitutedTerm = substituteOnce(originalTerm, bindings); // Recurse
                        if (substitutedTerm != originalTerm) changed = true; // Check reference equality first

                        if (changed && substitutedTerms == null) { // First change? Copy preceding terms
                            substitutedTerms = new ArrayList<>(originalTerms.subList(0, i));
                        }
                        if (substitutedTerms != null) { // If changes have started, add the current (potentially substituted) term
                            substitutedTerms.add(substitutedTerm);
                        }
                    }
                    // Return new list only if changed, otherwise return original list
                    yield changed ? new KifList(substitutedTerms != null ? substitutedTerms : originalTerms) : l;
                }
                case KifConstant c -> c; // Constants are unchanged
            };
        }

        // Binds a variable to a value, respecting existing bindings and occurs check
        private static Map<KifVariable, KifTerm> bindVariable(KifVariable var, KifTerm value, Map<KifVariable, KifTerm> bindings) {
            if (var.equals(value)) return bindings; // Binding ?X to ?X is redundant
            if (bindings.containsKey(var)) return unify(bindings.get(var), value, bindings); // Variable already bound, unify its value with new value
            // If value is a variable that's already bound, use its binding instead
            if (value instanceof KifVariable vVal && bindings.containsKey(vVal)) return bindVariable(var, bindings.get(vVal), bindings);

            // Occurs Check: prevent binding ?X to (f ?X)
            if (occursCheck(var, value, bindings)) return null;

            // Create new immutable binding map
            Map<KifVariable, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, value);
            return Collections.unmodifiableMap(newBindings);
        }

        // Checks if a variable occurs within a term (after substitution), preventing cyclic bindings
        private static boolean occursCheck(KifVariable var, KifTerm term, Map<KifVariable, KifTerm> bindings) {
            KifTerm substTerm = fullySubstitute(term, bindings); // Check against the fully substituted term
            return switch (substTerm) {
                case KifVariable v -> var.equals(v); // Variable matches itself
                case KifList l -> l.stream().anyMatch(t -> occursCheck(var, t, bindings)); // Check recursively in list elements
                case KifConstant c -> false; // Variable cannot occur in a constant
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
        private final JButton resolveButton = new JButton("Resolve..."); // New button
        private final JLabel statusLabel = new JLabel("Status: Idle");
        ProbabilisticKifReasoner reasoner; // Set after construction
        private Note currentNote = null; // Track the currently selected note

        public SwingUI(ProbabilisticKifReasoner reasoner) {
            super("Cognote - Probabilistic KIF Reasoner");
            this.reasoner = reasoner;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Handle close via listener
            setSize(1200, 800); // Default size
            setLocationRelativeTo(null); // Center on screen

            // Setup UI elements
            setupFonts();
            setupComponents();
            setupLayout();
            setupListeners();

            // Window closing listener for graceful shutdown
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    System.out.println("UI closing event received.");
                    if (reasoner != null) {
                        reasoner.stopReasoner(); // Stop the backend
                    }
                    dispose(); // Close the UI window
                    System.exit(0); // Ensure JVM exit
                }
            });
            updateUIForSelection(); // Set initial UI state
        }

        private void setupFonts() {
            // Set default fonts for UI elements for consistency and size
            UIManager.put("TextArea.font", UI_DEFAULT_FONT);
            UIManager.put("List.font", UI_DEFAULT_FONT);
            UIManager.put("Button.font", UI_DEFAULT_FONT);
            UIManager.put("Label.font", UI_DEFAULT_FONT);
            UIManager.put("TextField.font", UI_DEFAULT_FONT);
            UIManager.put("Panel.font", UI_DEFAULT_FONT);
            UIManager.put("TitledBorder.font", UI_DEFAULT_FONT);
            UIManager.put("CheckBox.font", UI_DEFAULT_FONT);
            // Apply fonts directly where UIManager might miss them
            noteList.setFont(UI_DEFAULT_FONT);
            noteEditor.setFont(UI_DEFAULT_FONT);
            derivationView.setFont(MONOSPACED_FONT); // Monospaced for KIF alignment
            addButton.setFont(UI_DEFAULT_FONT);
            removeButton.setFont(UI_DEFAULT_FONT);
            analyzeButton.setFont(UI_DEFAULT_FONT);
            resolveButton.setFont(UI_DEFAULT_FONT); // Apply font to new button
            statusLabel.setFont(UI_DEFAULT_FONT);
        }

        private void setupComponents() {
            // Configure text areas
            noteEditor.setLineWrap(true);
            noteEditor.setWrapStyleWord(true);
            derivationView.setEditable(false); // Read-only view for derivations
            derivationView.setLineWrap(true);
            derivationView.setWrapStyleWord(true);
            // Configure list
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // Only one note selected at a time
            noteList.setCellRenderer(new NoteListCellRenderer()); // Custom renderer for padding/styling
            // Configure status label
            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10)); // Add padding
        }

        private void setupLayout() {
            // Create scroll panes for text areas and list
            var derivationScrollPane = new JScrollPane(derivationView);
            var editorScrollPane = new JScrollPane(noteEditor);
            var leftScrollPane = new JScrollPane(noteList);
            leftScrollPane.setMinimumSize(new Dimension(200, 100)); // Ensure note list isn't too small

            // Split pane for editor and derivations (right side)
            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScrollPane, derivationScrollPane);
            rightSplit.setResizeWeight(0.65); // Give editor more initial space

            // Main split pane for note list (left) and editor/derivations (right)
            var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightSplit);
            mainSplit.setResizeWeight(0.25); // Give note list less initial space

            // Panel for control buttons
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5)); // Flow layout with gaps
            buttonPanel.add(addButton);
            buttonPanel.add(removeButton);
            buttonPanel.add(analyzeButton);
            buttonPanel.add(resolveButton); // Add the new resolve button

            // Panel for the bottom status bar
            var bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(buttonPanel, BorderLayout.WEST);
            bottomPanel.add(statusLabel, BorderLayout.CENTER);
            bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5)); // Padding around bottom panel

            // Add main components to the frame's content pane
            getContentPane().add(mainSplit, BorderLayout.CENTER);
            getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupListeners() {
            // Button actions
            addButton.addActionListener(this::addNote);
            removeButton.addActionListener(this::removeNote);
            analyzeButton.addActionListener(this::analyzeNote);
            resolveButton.addActionListener(this::resolveMismatches); // Listener for new button

            // List selection listener
            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) { // Process selection change only when finalized
                    Note selected = noteList.getSelectedValue();
                    // Auto-save text of previously selected note before switching
                    if (currentNote != null && selected != currentNote && noteEditor.isEnabled()) {
                        currentNote.text = noteEditor.getText();
                    }
                    currentNote = selected; // Update currently selected note
                    updateUIForSelection(); // Refresh UI based on new selection
                }
            });

            // Auto-save note editor text when focus is lost
            noteEditor.addFocusListener(new java.awt.event.FocusAdapter() {
                public void focusLost(java.awt.event.FocusEvent evt) {
                    if (currentNote != null && noteEditor.isEnabled()) {
                        currentNote.text = noteEditor.getText();
                    }
                }
            });
        }

        // Updates UI elements based on whether a note is selected
        private void updateUIForSelection() {
            boolean noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected);
            removeButton.setEnabled(noteSelected);
            analyzeButton.setEnabled(noteSelected);
            resolveButton.setEnabled(noteSelected); // Enable/disable resolve button with selection

            if (noteSelected) {
                noteEditor.setText(currentNote.text); // Load selected note's text
                noteEditor.setCaretPosition(0); // Scroll editor to top
                displayExistingDerivations(currentNote); // Show derivations for this note
                setTitle("Cognote - " + currentNote.title); // Update window title
            } else { // No note selected
                noteEditor.setText("");
                derivationView.setText("");
                setTitle("Cognote - Probabilistic KIF Reasoner");
            }
        }

        // --- Action Handlers ---
        private void addNote(ActionEvent e) {
            var title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) {
                var noteId = "note-" + UUID.randomUUID(); // Generate unique ID
                var newNote = new Note(noteId, title.trim(), ""); // Create note object
                noteListModel.addElement(newNote); // Add to UI list model
                noteList.setSelectedValue(newNote, true); // Select the newly added note
            }
        }

        private void removeNote(ActionEvent e) {
            if (currentNote == null) return; // No note selected
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Remove note '" + currentNote.title + "'?\nThis will retract all associated assertions from the knowledge base.",
                    "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                statusLabel.setText("Status: Removing '" + currentNote.title + "' and retracting assertions...");
                // Submit retraction message to reasoner *before* removing from UI
                reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Remove"));

                int selectedIndex = noteList.getSelectedIndex();
                noteListModel.removeElement(currentNote); // Remove from UI list
                currentNote = null; // Clear current selection tracker

                // Select adjacent item in list if possible
                if (noteListModel.getSize() > 0) {
                    noteList.setSelectedIndex(Math.min(selectedIndex, noteListModel.getSize() - 1));
                } else {
                    updateUIForSelection(); // Handles case where list becomes empty
                }
                statusLabel.setText("Status: Note removed.");
            }
        }

        private void analyzeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            statusLabel.setText("Status: Analyzing '" + currentNote.title + "'...");
            setButtonsEnabled(false); // Disable buttons during analysis
            var noteTextToAnalyze = noteEditor.getText();
            currentNote.text = noteTextToAnalyze; // Update note object immediately

            derivationView.setText("Analyzing Note...\n--------------------\n"); // Clear derivation view
            // 1. Retract any previous assertions from this note
            reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Analyze"));

            // 2. Call LLM to get KIF from note text
            reasoner.getKifFromLlmAsync(noteTextToAnalyze, currentNote.id)
                    .thenAcceptAsync(kifString -> { // 3. Process LLM response on EDT
                        try {
                            var terms = KifParser.parseKif(kifString); // Parse potentially multiple lines/terms
                            int submittedCount = 0;
                            int groundedCount = 0;
                            int skippedCount = 0;

                            // Process each term returned by the LLM
                            for (var term : terms) {
                                if (term instanceof KifList fact) {
                                    if (!fact.containsVariable()) { // Ground fact - submit directly
                                        reasoner.submitMessage(new AssertMessage(1.0, fact.toKifString(), "UI-LLM", currentNote.id));
                                        submittedCount++;
                                    } else { // Non-ground fact - attempt to ground it
                                        KifTerm groundedTerm = groundLlmTerm(fact, currentNote.id); // Use helper
                                        if (groundedTerm instanceof KifList groundedFact && !groundedFact.containsVariable()) {
                                            System.out.println("Grounded '" + fact.toKifString() + "' to '" + groundedFact.toKifString() + "' for note " + currentNote.id);
                                            reasoner.submitMessage(new AssertMessage(1.0, groundedFact.toKifString(), "UI-LLM-Grounded", currentNote.id));
                                            groundedCount++;
                                        } else {
                                            System.err.println("Failed to fully ground LLM KIF or result not a list: " + (groundedTerm != null ? groundedTerm.toKifString() : "null"));
                                            skippedCount++;
                                        }
                                    }
                                } else { // Term was not a KIF list
                                    System.err.println("LLM generated non-list KIF, skipped: " + term.toKifString());
                                    skippedCount++;
                                }
                            }

                            // Update status bar with results
                            final int totalSubmitted = submittedCount + groundedCount;
                            String statusMsg = String.format("Status: Analyzed '%s'. Submitted %d assertions (%d grounded).", currentNote.title, totalSubmitted, groundedCount);
                            if (skippedCount > 0) statusMsg += String.format(" Skipped %d invalid/ungroundable terms.", skippedCount);
                            statusLabel.setText(statusMsg);
                            // Clear derivation view; new assertions will trigger callbacks to populate it
                            derivationView.setText("Derivations for: " + currentNote.title + "\n--------------------\n");

                        } catch (ParseException pe) {
                            statusLabel.setText("Status: KIF Parse Error from LLM for '" + currentNote.title + "'.");
                            System.err.println("KIF Parse Error from LLM output: " + pe.getMessage() + "\nInput was:\n" + kifString);
                            JOptionPane.showMessageDialog(SwingUI.this, "Could not parse KIF from LLM response:\n" + pe.getMessage() + "\n\nCheck console for details and LLM output.", "KIF Parse Error", JOptionPane.ERROR_MESSAGE);
                            derivationView.setText("Error parsing KIF from LLM.\n--------------------\n" + kifString); // Show raw output on error
                        } catch (Exception ex) { // Catch other unexpected errors during processing
                            statusLabel.setText("Status: Error processing LLM response for '" + currentNote.title + "'.");
                            System.err.println("Error processing LLM KIF: " + ex);
                            ex.printStackTrace();
                            JOptionPane.showMessageDialog(SwingUI.this, "An unexpected error occurred processing the LLM response:\n" + ex.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE);
                        } finally {
                            setButtonsEnabled(true); // Re-enable buttons
                        }
                    }, SwingUtilities::invokeLater) // Ensure this block runs on EDT
                    .exceptionallyAsync(ex -> { // Handle failures in the LLM call itself (runs on EDT)
                        var cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                        statusLabel.setText("Status: LLM Analysis Failed for '" + currentNote.title + "'.");
                        System.err.println("LLM call failed: " + cause.getMessage());
                        cause.printStackTrace(); // Log underlying cause
                        JOptionPane.showMessageDialog(SwingUI.this, "LLM communication or processing failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
                        setButtonsEnabled(true); // Re-enable buttons
                        derivationView.setText("LLM Analysis Failed.\n--------------------\n" + cause.getMessage());
                        return null; // Required for exceptionally block
                    }, SwingUtilities::invokeLater); // Ensure exception handling runs on EDT
        }

        // Helper to ground variables in a KIF term using note ID context
        private KifTerm groundLlmTerm(KifTerm term, String noteId) {
            Set<KifVariable> variables = term.getVariables();
            if (variables.isEmpty()) return term; // Already ground

            Map<KifVariable, KifTerm> groundingMap = new HashMap<>();
            // Create a prefix from the note ID (replace invalid KIF chars like '-')
            String notePrefix = "note_" + noteId.replaceAll("[^a-zA-Z0-9_]", "_") + "_";
            for (KifVariable var : variables) {
                String groundedName = notePrefix + var.name().substring(1); // Remove '?'
                groundingMap.put(var, new KifConstant(groundedName));
            }
            // Apply the grounding substitutions
            return Unifier.substitute(term, groundingMap);
        }

        // New action handler for the "Resolve Mismatches" button
        private void resolveMismatches(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            statusLabel.setText("Status: Finding potential mismatches for '" + currentNote.title + "'...");
            setButtonsEnabled(false);

            // Run mismatch finding in background to avoid blocking UI
            CompletableFuture.supplyAsync(() -> findPotentialMismatches(currentNote.id), Executors.newSingleThreadExecutor())
                    .thenAcceptAsync(mismatches -> { // Process results on EDT
                        if (mismatches.isEmpty()) {
                            statusLabel.setText("Status: No obvious potential mismatches found for '" + currentNote.title + "'.");
                            JOptionPane.showMessageDialog(SwingUI.this, "No potential rule/fact mismatches requiring bridging were automatically identified for this note.", "Resolution", JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            statusLabel.setText("Status: Found " + mismatches.size() + " potential mismatches for '" + currentNote.title + "'.");
                            // Show dialog to user
                            showMismatchResolutionDialog(mismatches);
                        }
                    }, SwingUtilities::invokeLater)
                    .whenCompleteAsync((res, err) -> setButtonsEnabled(true), SwingUtilities::invokeLater); // Re-enable buttons on EDT
        }

        // Finds potential mismatches between note assertions and rule antecedents
        private List<PotentialBridge> findPotentialMismatches(String noteId) {
            List<PotentialBridge> potentialBridges = new ArrayList<>();
            Set<String> assertionIds = reasoner.noteIdToAssertionIds.getOrDefault(noteId, Set.of());
            if (assertionIds.isEmpty()) return potentialBridges; // No assertions from this note

            // Get assertions originating from this note
            List<Assertion> noteAssertions = assertionIds.stream()
                    .map(reasoner.assertionsById::get)
                    .filter(Objects::nonNull)
                    .toList();

            // Check each rule
            for (Rule rule : reasoner.rules) {
                for (KifList antClause : rule.getAntecedentClauses()) {
                    // Check if this clause can be matched by *any* assertion currently in KB
                    boolean clauseMatchedInKb = reasoner.findCandidateAssertions(antClause)
                            .anyMatch(kbAssertion -> Unifier.unify(antClause, kbAssertion.fact(), Map.of()) != null);

                    // If the clause *cannot* be matched in the *entire KB*...
                    if (!clauseMatchedInKb) {
                        // ...check if any *note-specific assertion* might be semantically related
                        for (Assertion noteAssertion : noteAssertions) {
                            // Simple semantic relation check: same predicate?
                            Optional<String> clauseOp = antClause.getOperator();
                            Optional<String> factOp = noteAssertion.fact().getOperator();

                            if (clauseOp.isPresent() && factOp.isPresent() && clauseOp.get().equals(factOp.get()) && antClause.size() == noteAssertion.fact().size()) {
                                // Predicates match, but clause is unmatched in KB. Could noteAssertion bridge it?
                                // Try to unify the *specific* noteAssertion with the clause
                                Map<KifVariable, KifTerm> bindings = Unifier.unify(antClause, noteAssertion.fact(), Map.of());
                                if (bindings != null) {
                                    // This shouldn't happen if clauseMatchedInKb was false. Log error if it does.
                                    System.err.println("Logic Error in mismatch finding: Clause previously unmatched unified successfully?");
                                } else {
                                    // They didn't unify directly. Propose a bridge IF structure is compatible.
                                    // Example: (instance ?X Socket) vs (instance note_cpu AMDCPU)
                                    // Propose: (instance note_cpu Socket) ?

                                    // Create the proposed bridging fact by substituting constants from noteAssertion into antClause pattern
                                    Map<KifVariable, KifTerm> proposalBindings = new HashMap<>();
                                    boolean canPropose = true;
                                    if (antClause.containsVariable()) {
                                        // Simple proposal: substitute corresponding terms if clause has vars
                                        for(int i = 0; i < antClause.size(); i++) {
                                            if (antClause.get(i) instanceof KifVariable var && noteAssertion.fact().get(i) instanceof KifConstant con) {
                                                proposalBindings.put(var, con);
                                            } else if (!antClause.get(i).equals(noteAssertion.fact().get(i))) {
                                                // If non-variable terms mismatch, cannot propose simple bridge
                                                // canPropose = false; break; // Allow proposing even if other constants differ? Let's try.
                                            }
                                        }
                                    } else { canPropose = false; } // Cannot propose if clause has no variables to substitute

                                    if (canPropose && !proposalBindings.isEmpty()) {
                                        KifTerm proposedTerm = Unifier.substitute(antClause, proposalBindings);
                                        if (proposedTerm instanceof KifList proposedFact && !proposedFact.containsVariable()) {
                                            // Avoid adding proposals already in KB or beam
                                            if (!reasoner.kbContentSnapshot.contains(proposedFact) && !reasoner.beamContentSnapshot.contains(proposedFact)) {
                                                potentialBridges.add(new PotentialBridge(
                                                        noteAssertion.fact(), // The fact from the note
                                                        antClause, // The unmatched rule clause pattern
                                                        rule.ruleForm(), // The rule it belongs to
                                                        proposedFact // The proposed bridging fact
                                                ));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Remove duplicates (same proposed fact might arise from different paths)
            return potentialBridges.stream().distinct().toList();
        }


        // Displays the dialog for resolving mismatches
        private void showMismatchResolutionDialog(List<PotentialBridge> mismatches) {
            MismatchResolutionDialog dialog = new MismatchResolutionDialog(this, mismatches, reasoner, currentNote.id);
            dialog.setVisible(true); // Show the modal dialog
        }


        // --- UI Update Callbacks & Helpers ---
        // Called by the reasoner's callback mechanism (must run on EDT)
        public void handleReasonerCallback(String type, Assertion assertion) {
            SwingUtilities.invokeLater(() -> {
                // Update derivation view only if the current note is related
                if (currentNote != null && isRelatedToNote(assertion, currentNote.id)) {
                    updateDerivationViewForAssertion(type, assertion);
                }
                // Update status bar for key events
                updateStatusBar(type, assertion);
            });
        }

        // Updates the text shown in the derivation view based on event type
        private void updateDerivationViewForAssertion(String type, Assertion assertion) {
            String linePrefix = String.format("P=%.3f %s", assertion.probability(), assertion.toKifString());
            String lineSuffix = String.format(" [%s]", assertion.id()); // Unique ID for finding lines
            String fullLine = linePrefix + lineSuffix;
            String currentText = derivationView.getText();

            switch (type) {
                case "assert-added", "assert-input":
                    // Add line only if it's not already present (check by ID suffix)
                    if (!currentText.contains(lineSuffix)) {
                        derivationView.append(fullLine + "\n");
                        derivationView.setCaretPosition(derivationView.getDocument().getLength()); // Scroll to bottom
                    }
                    break;
                case "assert-retracted", "evict":
                    // Find line by ID suffix and comment it out
                    String updatedText = currentText.lines()
                            .map(l -> l.trim().endsWith(lineSuffix) ? "# " + type.toUpperCase() + ": " + l : l)
                            .collect(Collectors.joining("\n"));
                    if (!currentText.equals(updatedText)) {
                        int caretPos = derivationView.getCaretPosition(); // Try to preserve position
                        derivationView.setText(updatedText);
                        try { derivationView.setCaretPosition(Math.min(caretPos, updatedText.length())); } // Restore position carefully
                        catch (IllegalArgumentException ignored) { derivationView.setCaretPosition(0); } // Scroll to top on error
                    }
                    break;
            }
        }

        // Updates the status bar label
        private void updateStatusBar(String type, Assertion assertion) {
            String status = switch (type) {
                case "assert-added" -> String.format("Status: Derived %s (P=%.3f)", assertion.id(), assertion.probability());
                case "assert-retracted" -> String.format("Status: Retracted %s", assertion.id());
                case "evict" -> String.format("Status: Evicted %s (Low P)", assertion.id());
                case "assert-input" -> (currentNote != null && Objects.equals(assertion.sourceNoteId(), currentNote.id))
                        ? String.format("Status: Processed input %s for current note", assertion.id())
                        : null; // Don't update status for inputs unrelated to current note (unless broadcast flag is on?)
                default -> null;
            };
            // Only update if status changed and is not null
            if (status != null && !statusLabel.getText().equals(status)) {
                statusLabel.setText(status);
            }
        }

        // Checks if an assertion originates from or is derived from a specific note ID
        private boolean isRelatedToNote(Assertion assertion, String targetNoteId) {
            if (targetNoteId == null) return false; // Cannot be related to null
            if (targetNoteId.equals(assertion.sourceNoteId())) return true; // Direct origin

            // Check provenance recursively using BFS to avoid deep stacks
            Queue<String> toCheck = new LinkedList<>(assertion.directSupportingIds());
            Set<String> visited = new HashSet<>(assertion.directSupportingIds());
            visited.add(assertion.id()); // Don't re-check self or already visited support nodes

            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                var parent = reasoner.assertionsById.get(currentId); // Safe concurrent read
                if (parent != null) {
                    if (targetNoteId.equals(parent.sourceNoteId())) return true; // Found origin in support chain
                    // Add parent's support nodes to queue if not already visited
                    parent.directSupportingIds().stream()
                            .filter(visited::add) // Add returns true if not already present
                            .forEach(toCheck::offer);
                }
            }
            return false; // Did not find target note ID in provenance chain
        }

        // Displays existing derivations related to the selected note
        private void displayExistingDerivations(Note note) {
            if (note == null || reasoner == null) {
                derivationView.setText("");
                return;
            }
            // Clear view and repopulate
            derivationView.setText("Derivations for: " + note.title + "\n--------------------\n");
            // Stream through all assertions, filter by relation to note, sort (e.g., by timestamp), and display
            reasoner.assertionsById.values().stream() // Safe iteration on concurrent map values
                    .filter(a -> isRelatedToNote(a, note.id))
                    .sorted(Comparator.comparingLong(Assertion::timestamp)) // Show in order of assertion time
                    .forEach(a -> derivationView.append(String.format("P=%.3f %s [%s]\n", a.probability(), a.toKifString(), a.id())));
            derivationView.setCaretPosition(0); // Scroll view to top
        }

        // Helper to enable/disable main action buttons
        private void setButtonsEnabled(boolean enabled) {
            addButton.setEnabled(enabled); // Adding note should always be possible? Maybe not during analysis.
            // Only enable remove/analyze/resolve if a note is also selected
            boolean noteSelected = (currentNote != null);
            removeButton.setEnabled(enabled && noteSelected);
            analyzeButton.setEnabled(enabled && noteSelected);
            resolveButton.setEnabled(enabled && noteSelected);
        }


        // --- Static Inner Data Classes ---
        // Represents a simple Note object
        static class Note {
            final String id; // Unique identifier
            String title; // Display title
            String text; // Content of the note (mutable)

            Note(String id, String title, String text) {
                this.id = Objects.requireNonNull(id);
                this.title = Objects.requireNonNull(title);
                this.text = Objects.requireNonNull(text);
            }
            @Override public String toString() { return title; } // Used by JList display
            @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); } // Equality based on ID
            @Override public int hashCode() { return id.hashCode(); }
        }

        // Custom renderer for JList cells to add padding
        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (c instanceof JLabel label) {
                    label.setBorder(new EmptyBorder(5, 10, 5, 10)); // Add padding L/R
                    label.setFont(UI_DEFAULT_FONT); // Ensure font is applied
                }
                return c;
            }
        }

        // Record to hold information about a potential bridge
        record PotentialBridge(KifList sourceFact, KifList ruleAntecedent, KifList ruleForm, KifList proposedBridge) {
            @Override public boolean equals(Object o) { // Equality based on proposed bridge only for distinct check
                return o instanceof PotentialBridge pb && proposedBridge.equals(pb.proposedBridge);
            }
            @Override public int hashCode() { return proposedBridge.hashCode(); }
        }

        // --- Dialog for Mismatch Resolution ---
        static class MismatchResolutionDialog extends JDialog {
            private final ProbabilisticKifReasoner reasoner;
            private final String sourceNoteId;
            private final List<PotentialBridge> mismatches;
            private final List<JCheckBox> checkBoxes = new ArrayList<>();
            private final List<JButton> llmButtons = new ArrayList<>();

            MismatchResolutionDialog(Frame owner, List<PotentialBridge> mismatches, ProbabilisticKifReasoner reasoner, String sourceNoteId) {
                super(owner, "Resolve Potential Mismatches", true); // Modal dialog
                this.mismatches = mismatches;
                this.reasoner = reasoner;
                this.sourceNoteId = sourceNoteId;

                setSize(800, 600);
                setLocationRelativeTo(owner);
                setLayout(new BorderLayout(10, 10));

                JPanel mainPanel = new JPanel();
                mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS)); // Vertical layout
                mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

                for (int i = 0; i < mismatches.size(); i++) {
                    mainPanel.add(createMismatchPanel(mismatches.get(i), i));
                    mainPanel.add(Box.createRigidArea(new Dimension(0, 10))); // Spacer
                }

                JScrollPane scrollPane = new JScrollPane(mainPanel);
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER); // Avoid horizontal scroll

                // Bottom panel for main action buttons
                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                JButton assertButton = new JButton("Assert Selected");
                JButton cancelButton = new JButton("Cancel");

                assertButton.setFont(UI_DEFAULT_FONT);
                cancelButton.setFont(UI_DEFAULT_FONT);

                assertButton.addActionListener(this::assertSelectedBridges);
                cancelButton.addActionListener(e -> dispose()); // Just close dialog

                buttonPanel.add(cancelButton);
                buttonPanel.add(assertButton);

                add(scrollPane, BorderLayout.CENTER);
                add(buttonPanel, BorderLayout.SOUTH);
            }

            // Creates a panel for a single potential mismatch
            private JPanel createMismatchPanel(PotentialBridge bridge, int index) {
                JPanel panel = new JPanel(new BorderLayout(5, 5));
                panel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.GRAY),
                        new EmptyBorder(5, 5, 5, 5)
                ));

                JPanel textPanel = new JPanel();
                textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

                JLabel sourceLabel = new JLabel("Source Fact: " + bridge.sourceFact().toKifString());
                JLabel ruleLabel = new JLabel("Rule Antecedent: " + bridge.ruleAntecedent().toKifString());
                // JLabel fullRuleLabel = new JLabel("Full Rule: " + bridge.ruleForm().toKifString()); // Optionally show full rule
                JLabel proposedLabel = new JLabel("Proposed Bridge: " + bridge.proposedBridge().toKifString());

                sourceLabel.setFont(UI_SMALL_FONT);
                ruleLabel.setFont(UI_SMALL_FONT);
                // fullRuleLabel.setFont(UI_SMALL_FONT);
                proposedLabel.setFont(UI_SMALL_FONT);
                proposedLabel.setForeground(Color.BLUE); // Highlight proposal

                textPanel.add(sourceLabel);
                textPanel.add(ruleLabel);
                // textPanel.add(fullRuleLabel);
                textPanel.add(Box.createRigidArea(new Dimension(0, 5)));
                textPanel.add(proposedLabel);

                JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                JCheckBox checkBox = new JCheckBox("Assert this bridge?");
                checkBox.setFont(UI_DEFAULT_FONT);
                checkBoxes.add(checkBox); // Add checkbox to list

                JButton llmButton = new JButton("Ask LLM");
                llmButton.setFont(UI_SMALL_FONT);
                llmButtons.add(llmButton); // Add button to list
                llmButton.addActionListener(e -> askLlmForBridge(bridge, checkBox));

                actionPanel.add(checkBox);
                actionPanel.add(llmButton);

                panel.add(textPanel, BorderLayout.CENTER);
                panel.add(actionPanel, BorderLayout.SOUTH);

                return panel;
            }

            // Action handler for "Ask LLM" button
            private void askLlmForBridge(PotentialBridge bridge, JCheckBox associatedCheckbox) {
                String fact1 = bridge.sourceFact().toKifString();
                String fact2Pattern = bridge.ruleAntecedent().toKifString();
                String proposed = bridge.proposedBridge().toKifString();

                String prompt = String.format("""
                Given the following KIF assertions:
                1. %s (derived from user note)
                2. A rule requires a fact matching the pattern: %s

                Could assertion 1 imply the following specific fact, needed by the rule?
                Proposed Fact: %s

                Answer ONLY with the proposed KIF fact if it is a valid and likely inference based on common sense or SUMO ontology, otherwise output NOTHING.
                Do not include explanations or markdown.
                KIF Assertion (or nothing):""", fact1, fact2Pattern, proposed);

                // Disable button while LLM runs
                JButton sourceButton = (JButton) Arrays.stream(llmButtons.toArray(new JButton[0]))
                        .filter(b -> ((ActionEvent) b.getActionListeners()[0].getClass().getEnclosingMethod().getParameters()[0].getAnnotations()[0]).getSource() == b) // Hacky way to find button, improve if needed
                        .findFirst().orElse(null); // This association is brittle, find a better way if possible
                // if (sourceButton != null) sourceButton.setEnabled(false);

                associatedCheckbox.setEnabled(false); // Disable checkbox during LLM call
                associatedCheckbox.setText("Asking LLM...");

                reasoner.getKifFromLlmAsync(prompt, sourceNoteId + "-bridge")
                        .thenAcceptAsync(llmResult -> {
                            boolean confirmed = false;
                            if (llmResult != null && !llmResult.isBlank()) {
                                try {
                                    // Check if LLM returned something *similar* to the proposed fact
                                    KifTerm parsedResult = KifParser.parseKif(llmResult).getFirst();
                                    if (parsedResult instanceof KifList resultFact && resultFact.equals(bridge.proposedBridge())) {
                                        associatedCheckbox.setSelected(true); // LLM confirmed, check the box
                                        associatedCheckbox.setText("Assert (LLM Confirmed)");
                                        confirmed = true;
                                    }
                                } catch (ParseException | NoSuchElementException ex) {
                                    System.err.println("LLM bridge response parse error: " + ex.getMessage());
                                }
                            }
                            if (!confirmed) {
                                associatedCheckbox.setText("Assert this bridge? (LLM Denied/Error)");
                            }
                        }, SwingUtilities::invokeLater) // Process result on EDT
                        .whenCompleteAsync((res, err) -> {
                            // Re-enable checkbox, maybe not button to prevent spamming LLM?
                            associatedCheckbox.setEnabled(true);
                            // if (sourceButton != null) sourceButton.setEnabled(true);
                            if (err != null) {
                                associatedCheckbox.setText("Assert this bridge? (LLM Request Failed)");
                                System.err.println("LLM bridge request failed: " + err.getMessage());
                            }
                        }, SwingUtilities::invokeLater); // Re-enable on EDT
            }


            // Action handler for the main "Assert Selected" button
            private void assertSelectedBridges(ActionEvent e) {
                int count = 0;
                for (int i = 0; i < checkBoxes.size(); i++) {
                    if (checkBoxes.get(i).isSelected()) {
                        PotentialBridge bridge = mismatches.get(i);
                        // Assert the proposed bridging fact with high probability? Or 1.0? Let's use 1.0.
                        reasoner.submitMessage(new AssertMessage(
                                1.0,
                                bridge.proposedBridge().toKifString(),
                                "UI-Bridge", // Indicate source
                                sourceNoteId // Associate with the original note
                        ));
                        count++;
                    }
                }
                JOptionPane.showMessageDialog(this, "Asserted " + count + " bridging facts.", "Resolution Complete", JOptionPane.INFORMATION_MESSAGE);
                dispose(); // Close the dialog
            }
        }

    }

} // End of ProbabilisticKifReasoner class
