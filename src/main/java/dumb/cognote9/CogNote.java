package dumb.cognote9;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
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
import java.io.Reader;
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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A priority-driven, concurrent forward-chaining SUMO KIF reasoner
 * using Path Indexing, Ordered Rewriting, Forward Subsumption, Cascading Retraction,
 * and basic support for Negation.
 * Driven by dynamic knowledge base changes via WebSockets, with callback support,
 * integrated with a Swing UI for Note->KIF distillation via LLM.
 * Delivers a single, consolidated, refactored, and corrected Java file addressing previous limitations.
 */
public class CogNote extends WebSocketServer {

    // --- Constants ---
    private static final int UI_FONT_SIZE = 18;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");
    private static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_LLM_MODEL = "llama3";
    private static final String KIF_OPERATOR_IMPLIES = "=>";
    private static final String KIF_OPERATOR_EQUIV = "<=>";
    private static final String KIF_OPERATOR_AND = "and";
    private static final String KIF_OPERATOR_EXISTS = "exists";
    private static final String KIF_OPERATOR_FORALL = "forall";
    private static final String KIF_OPERATOR_EQUAL = "=";
    private static final String KIF_OPERATOR_NOT = "not"; // Added constant for negation
    private static final String CALLBACK_ASSERT_ADDED = "assert-added";
    private static final String CALLBACK_ASSERT_INPUT = "assert-input";
    private static final String CALLBACK_ASSERT_RETRACTED = "assert-retracted";
    private static final String CALLBACK_EVICT = "evict";
    private static final String ID_PREFIX_RULE = "rule";
    private static final String ID_PREFIX_FACT = "fact";
    private static final String ID_PREFIX_SKOLEM = "skolem_";
    private static final String ID_PREFIX_ENTITY = "entity_";
    private static final String ID_PREFIX_NOTE = "note-";
    private static final double DEFAULT_RULE_PRIORITY = 1.0;
    private static final double INPUT_ASSERTION_BASE_PRIORITY = 10.0;
    private static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    private static final double DERIVED_PRIORITY_DECAY = 0.95;
    private static final int HTTP_TIMEOUT_SECONDS = 60;
    private static final int WS_STOP_TIMEOUT_MS = 1000;
    private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int MAX_WS_PARSE_PREVIEW = 100;
    private static final int COMMIT_QUEUE_CAPACITY = 10000;
    private static final int TASK_QUEUE_CAPACITY = 10000;
    private static final int MIN_INFERENCE_WORKERS = 2;

    // --- Reasoner Parameters ---
    private final int capacity;
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl, llmModel;

    // --- Core Components ---
    private final KnowledgeBase knowledgeBase;
    private final ReasonerEngine reasonerEngine;
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>();

    // --- Execution & Control ---
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final SwingUI swingUI;
    private final Object pauseLock = new Object();

    private volatile String reasonerStatus = "Idle";
    private volatile boolean running = true, paused = false;

    public CogNote(int port, int capacity, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.capacity = capacity;
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = Objects.requireNonNullElse(llmUrl, DEFAULT_LLM_URL);
        this.llmModel = Objects.requireNonNullElse(llmModel, DEFAULT_LLM_MODEL);
        this.swingUI = Objects.requireNonNull(ui, "SwingUI cannot be null");
        // Pass the callback handler to KB for cascaded retractions
        this.knowledgeBase = new KnowledgeBase(this.capacity, this::invokeCallbacks);
        this.reasonerEngine = new ReasonerEngine(knowledgeBase, () -> generateId(""), this::invokeCallbacks, pauseLock);
        System.out.printf("Reasoner config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s%n",
                port, this.capacity, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            int port = 8887;
            int maxKbSize = 64 * 1024;
            String rulesFile = null;
            String llmUrl = null;
            String llmModel = null;
            boolean broadcastInput = false;

            for (var i = 0; i < args.length; i++) {
                try {
                    switch (args[i]) {
                        case "-p", "--port" -> port = Integer.parseInt(args[++i]);
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
                } catch (IllegalArgumentException e) {
                    System.err.println("Argument Error: " + e.getMessage());
                    printUsageAndExit();
                } catch (Exception e) {
                    System.err.println("Error parsing args: " + e.getMessage());
                    printUsageAndExit();
                }
            }

            SwingUI ui = null;
            try {
                ui = new SwingUI(null);
                var server = new CogNote(port, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.setReasoner(server);

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    server.stopReasoner();
                }));

                server.startReasoner();

                if (rulesFile != null) {
                    server.loadExpressionsFromFile(rulesFile);
                } else {
                    System.out.println("No initial rules/facts file specified.");
                }

                ui.setVisible(true);

            } catch (IllegalArgumentException e) {
                System.err.println("Configuration Error: " + e.getMessage());
                if (ui != null) ui.dispose();
                System.exit(1);
            } catch (IOException e) {
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
        System.err.printf("""
                Usage: java %s [options]
                Options:
                  -p, --port <port>           WebSocket server port (default: 8887)
                  -k, --kb-size <maxKbSize>   Max KB assertion count (default: 65536)
                  -r, --rules <rulesFile>     Path to file with initial KIF rules/facts
                  --llm-url <url>             URL for the LLM API (default: %s)
                  --llm-model <model>         LLM model name (default: %s)
                  --broadcast-input           Broadcast input assertions via WebSocket
                """, CogNote.class.getName(), DEFAULT_LLM_URL, DEFAULT_LLM_MODEL);
        System.exit(1);
    }

    static String generateId(String prefix) {
        return prefix + "-" + idCounter.incrementAndGet();
    }

    // Check for trivial assertions like (pred x x) or (= x x) or (not (not ...))
    private static boolean isTrivial(KifList list) {
        // Basic reflexivity check
        if (list.size() >= 3) {
            var arg1 = list.get(1);
            var arg2 = list.get(2);
            if (arg1.equals(arg2) && list.getOperator().map(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OPERATOR_EQUAL)).orElse(false)) {
                return true;
            }
        }
        // Check for (not (not X)) - requires simplification, maybe handle elsewhere?
        // For now, let's consider (not (not X)) non-trivial input, could be simplified by rewrite rule.
        // Check for (not (= X X)) or (not (instance X X)) etc.
        if (list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent() && list.size() == 2 && list.get(1) instanceof KifList inner) {
             if (inner.size() >= 3) {
                 var arg1 = inner.get(1);
                 var arg2 = inner.get(2);
                 if (arg1.equals(arg2) && inner.getOperator().map(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OPERATOR_EQUAL)).orElse(false)) {
                    System.out.println("Warning: Ignoring trivial negated assertion: " + list.toKifString());
                    return true; // e.g. (not (= A A)) is trivially false, ignore input? Or handle? Let's ignore for now.
                 }
             }
        }

        return false;
    }

    private static void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println(name + " did not terminate gracefully, forcing shutdown.");
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.SECONDS))
                    System.err.println(name + " did not terminate after forced shutdown.");
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for " + name + " shutdown.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // --- WebSocket Communication ---
    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        var message = switch (type) {
            case CALLBACK_ASSERT_ADDED -> String.format("assert-derived %.4f %s", assertion.pri, kifString);
            case CALLBACK_ASSERT_INPUT -> String.format("assert-input %.4f %s", assertion.pri, kifString);
            case CALLBACK_ASSERT_RETRACTED -> String.format("retract %s", assertion.id); // Broadcast uses ID
            case CALLBACK_EVICT -> String.format("evict %s", assertion.id); // Broadcast uses ID
            default -> type + " " + kifString;
        };
        try {
            if (!getConnections().isEmpty()) broadcast(message);
        } catch (Exception e) {
            System.err.println("Error during WebSocket broadcast: " + e.getMessage());
        }
    }

    // --- File Loading ---
    public void loadExpressionsFromFile(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

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
                kifBuffer.append(line).append(' ');

                if (parenDepth == 0 && !kifBuffer.isEmpty()) {
                    var kifText = kifBuffer.toString().trim();
                    kifBuffer.setLength(0);
                    if (!kifText.isEmpty()) {
                        processedCount++;
                        try {
                            for (var term : KifParser.parseKif(kifText)) {
                                queueExpressionFromSource(term, "file:" + filename);
                                queuedCount++;
                            }
                        } catch (ParseException e) {
                            System.err.printf("File Parse Error (line ~%d): %s near '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
                        } catch (Exception e) {
                            System.err.printf("File Processing Error (line ~%d): %s for '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
                            e.printStackTrace();
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

    // --- Input Processing ---
    private void queueExpressionFromSource(KifTerm term, String sourceId) {
        switch (term) {
            case KifList list when !list.terms.isEmpty() -> {
                var opOpt = list.getOperator();
                if (opOpt.isPresent()) {
                    switch (opOpt.get()) {
                        case KIF_OPERATOR_IMPLIES, KIF_OPERATOR_EQUIV -> handleRuleInput(list, sourceId);
                        case KIF_OPERATOR_EXISTS -> handleExists(list, sourceId);
                        case KIF_OPERATOR_FORALL -> handleForall(list, sourceId);
                        case KIF_OPERATOR_EQUAL -> handleEqualityInput(list, sourceId);
                        // Handle NOT at the top level as a standard assertion (e.g., (not (p a)))
                        case KIF_OPERATOR_NOT -> handleStandardAssertionInput(list, sourceId);
                        default -> handleStandardAssertionInput(list, sourceId);
                    }
                } else { // List doesn't start with an operator atom
                    handleStandardAssertionInput(list, sourceId);
                }
            }
            case KifList ignored -> { /* Ignore empty list */ }
            default ->
                    System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
        }
    }

    private void handleRuleInput(KifList list, String sourceId) {
        try {
            var rule = Rule.parseRule(generateId(ID_PREFIX_RULE), list, DEFAULT_RULE_PRIORITY);
            reasonerEngine.addRule(rule);
            if (KIF_OPERATOR_EQUIV.equals(list.getOperator().orElse(""))) {
                var reverseList = new KifList(new KifAtom(KIF_OPERATOR_IMPLIES), list.get(2), list.get(1));
                var reverseRule = Rule.parseRule(generateId(ID_PREFIX_RULE), reverseList, DEFAULT_RULE_PRIORITY);
                reasonerEngine.addRule(reverseRule);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid rule format ignored: " + list.toKifString() + " | Error: " + e.getMessage());
        }
    }

    private void handleEqualityInput(KifList list, String sourceId) {
        if (list.size() != 3) {
            System.err.println("Invalid equality format ignored (must have 2 arguments): " + list.toKifString());
            return;
        }
        if (isTrivial(list)) {
            // Warning already printed by isTrivial
            return;
        }
        var lhs = list.get(1);
        var rhs = list.get(2);
        var weight = list.calculateWeight();
        var pri = INPUT_ASSERTION_BASE_PRIORITY / (1.0 + weight);
        boolean isOriented = lhs.calculateWeight() > rhs.calculateWeight();
        var pa = new PotentialAssertion(list, pri, Set.of(), sourceId, true, false, list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent(), null);
        submitPotentialAssertion(pa);
    }

    private void handleStandardAssertionInput(KifList list, String sourceId) {
        // Check for (not X) structure
        boolean isNegated = list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
        if (isNegated && list.size() != 2) {
             System.err.println("Invalid 'not' format ignored (must have 1 argument): " + list.toKifString());
             return;
        }
        // Check inner term if negated
        KifTerm effectiveTerm = isNegated ? list.get(1) : list;

        if (effectiveTerm.containsVariable()) {
            System.err.println("Warning: Non-ground assertion input ignored: " + list.toKifString());
        } else if (isTrivial(list)) {
            // Warning potentially printed by isTrivial
        } else {
            var weight = list.calculateWeight();
            var pri = INPUT_ASSERTION_BASE_PRIORITY / (1.0 + weight);
            var pa = new PotentialAssertion(list, pri, Set.of(), sourceId, false, isNegated, false, null);
            submitPotentialAssertion(pa);
        }
    }

    // --- Quantifier Handling ---
    // Skolemize top-level 'exists' or 'exists' not bound by an outer 'forall'
    private void handleExists(KifList existsExpr, String sourceId) {
        if (existsExpr.size() != 3) {
            System.err.println("Invalid 'exists' format (expected 3 parts): " + existsExpr.toKifString());
            return;
        }
        KifTerm varsTerm = existsExpr.get(1), body = existsExpr.get(2);
        var variables = KifTerm.collectVariablesFromSpec(varsTerm);
        if (variables.isEmpty()) {
            queueExpressionFromSource(body, sourceId + "-existsBody");
            return;
        }

        // Simple Skolemization using constants. Assumes no outer 'forall' context here.
        // A more advanced implementation would generate Skolem functions based on free variables in scope.
        Map<KifVar, KifTerm> skolemBindings = variables.stream().collect(Collectors.toUnmodifiableMap(
                v -> v,
                v -> new KifAtom(ID_PREFIX_SKOLEM + v.name().substring(1) + "_" + idCounter.incrementAndGet())
        ));
        var skolemizedBody = Unifier.subst(body, skolemBindings);
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);
        queueExpressionFromSource(skolemizedBody, sourceId + "-skolemized");
    }

    // Interpret 'forall' only as scoping rules (=> or <=>)
    private void handleForall(KifList forallExpr, String sourceId) {
        if (forallExpr.size() == 3 && forallExpr.get(2) instanceof KifList bodyList
                && bodyList.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent()) {
            System.out.println("Interpreting 'forall ... (" + bodyList.getOperator().get() + " ...)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
            handleRuleInput(bodyList, sourceId);
        } else {
            // Ignore other forms of 'forall' as they are difficult to handle in forward chaining
            System.err.println("Warning: Ignoring 'forall' expression not scoping '=>' or '<=>': " + forallExpr.toKifString());
        }
    }

    // --- Public Control Methods ---
    public void startReasoner() {
        if (!running) {
            System.err.println("Cannot restart a stopped reasoner.");
            return;
        }
        paused = false;
        reasonerEngine.start();
        try {
            start();
        } catch (IllegalStateException e) {
            System.err.println("WebSocket server already started or failed to start: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage());
            stopReasoner();
        }
        System.out.println("Reasoner started.");
        updateUIStatus();
    }

    public void stopReasoner() {
        if (!running) return;
        System.out.println("Stopping reasoner and services...");
        running = false;
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        reasonerEngine.stop();

        try {
            stop(WS_STOP_TIMEOUT_MS);
            System.out.println("WebSocket server stopped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while stopping WebSocket server.");
        } catch (Exception e) {
            System.err.println("Error stopping WebSocket server: " + e.getMessage());
        }

        httpClient.executor().filter(ExecutorService.class::isInstance).map(ExecutorService.class::cast)
                .ifPresent(exec -> shutdownExecutor(exec, "HTTP Client Executor"));

        reasonerStatus = "Stopped";
        updateUIStatus();
        System.out.println("Reasoner stopped.");
    }

    public boolean isPaused() { return this.paused; }

    public void setPaused(boolean pause) {
        if (this.paused == pause) return;
        this.paused = pause;
        reasonerEngine.setPaused(pause);
        if (!pause) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        reasonerStatus = pause ? "Paused" : "Running";
        updateUIStatus();
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        // Get assertion IDs *before* clearing KB, as KB clear triggers retraction callbacks
        List<String> idsToNotify = knowledgeBase.getAllAssertionIds();
        knowledgeBase.clear(); // This handles internal clearing and dependency removal
        reasonerEngine.clear();
        noteIdToAssertionIds.clear();
        SwingUtilities.invokeLater(swingUI.derivationTextCache::clear);

        // Manually invoke callbacks for assertions that were present before the clear
        // Note: KB.clear() doesn't invoke callbacks itself.
        idsToNotify.forEach(id -> {
             // Create a dummy assertion for the callback as the real one is gone
             // This isn't ideal, maybe callbacks should just take IDs for retraction?
             // For now, send a minimal placeholder.
             var dummyKif = new KifList(new KifAtom("retracted"), new KifAtom(id));
             var placeholder = new Assertion(id, dummyKif, 0, 0, null, Set.of(), false, false, false);
             invokeCallbacks(CALLBACK_ASSERT_RETRACTED, placeholder);
        });


        reasonerStatus = "Cleared";
        setPaused(false);
        updateUIStatus();
        System.out.println("Knowledge cleared.");
    }

    // --- WebSocket Server Implementation ---
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("WS Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + Objects.requireNonNullElse(reason, "N/A"));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        var addr = (conn != null && conn.getRemoteSocketAddress() != null) ? conn.getRemoteSocketAddress().toString() : "server";
        if (ex instanceof IOException && ex.getMessage() != null && (ex.getMessage().contains("Socket closed") || ex.getMessage().contains("Connection reset") || ex.getMessage().contains("Broken pipe"))) {
            System.err.println("WS Network Info from " + addr + ": " + ex.getMessage());
        } else {
            System.err.println("WS Error from " + addr + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void onStart() {
        System.out.println("Reasoner WebSocket listener active on port " + getPort() + ".");
        setConnectionLostTimeout(WS_CONNECTION_LOST_TIMEOUT_MS);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        var trimmed = message.trim();
        if (trimmed.isEmpty()) return;
        var sourceId = conn.getRemoteSocketAddress().toString();
        var lowerTrimmed = trimmed.toLowerCase();

        if (lowerTrimmed.startsWith("retract ")) {
            var idToRetract = trimmed.substring(8).trim();
            if (!idToRetract.isEmpty()) {
                processRetractionByIdInput(idToRetract, sourceId);
            } else {
                System.err.println("WS Retract Error from " + sourceId + ": Missing assertion ID.");
            }
        } else if (lowerTrimmed.startsWith("register_callback ")) {
            System.err.println("WS Callback registration via message is not implemented.");
        } else {
            try {
                var terms = KifParser.parseKif(trimmed);
                if (terms.isEmpty()) {
                    System.err.printf("WS Received non-KIF or empty KIF message from %s: %s...%n", sourceId, trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
                } else {
                    terms.forEach(term -> queueExpressionFromSource(term, sourceId));
                }
            } catch (ParseException | ClassCastException e) {
                System.err.printf("WS Message Parse Error from %s: %s | Original: %s...%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
            } catch (Exception e) {
                System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // --- Direct Input Processing Methods ---
    public void submitPotentialAssertion(PotentialAssertion pa) {
        if (pa == null) return;
        if (isTrivial(pa.kif)) {
            // Warning potentially printed by isTrivial
            return;
        }
        reasonerEngine.submitPotentialAssertion(pa);

        // Invoke input callback immediately for external sources
        boolean isExternal = pa.sourceId().startsWith("UI-") || pa.sourceId().startsWith("file:") || pa.sourceId().contains(":");
        if (isExternal && pa.sourceNoteId() != null && !pa.isEquality() && !pa.kif.containsVariable()) {
            var tempId = generateId("input");
            var inputAssertion = new Assertion(tempId, pa.kif, pa.pri, System.currentTimeMillis(), pa.sourceNoteId(), Set.of(), pa.isEquality(), pa.isOrientedEquality(), pa.isNegated());
            invokeCallbacks(CALLBACK_ASSERT_INPUT, inputAssertion);
        }
    }

    public void submitRule(Rule rule) {
        reasonerEngine.addRule(rule);
    }

    public void processRetractionByIdInput(String assertionId, String sourceId) {
        // Delegate to KB which handles cascade and invokes callbacks
        Assertion removed = knowledgeBase.retractAssertion(assertionId);
        if (removed != null) {
            System.out.printf("Retraction initiated for [%s] by %s: %s%n", assertionId, sourceId, removed.toKifString());
        } else {
            System.out.printf("Retraction by ID %s from %s failed: ID not found or already retracted.%n", assertionId, sourceId);
        }
        updateUIStatus();
    }

    public void processRetractionByNoteIdInput(String noteId, String sourceId) {
        var idsToRetract = noteIdToAssertionIds.remove(noteId);
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.printf("Initiating retraction of %d assertions for note %s from %s.%n", idsToRetract.size(), noteId, sourceId);
            new HashSet<>(idsToRetract).forEach(id -> processRetractionByIdInput(id, sourceId + "-noteRetract"));
        } else {
            System.out.printf("Retraction by Note ID %s from %s failed: No associated assertions found.%n", noteId, sourceId);
        }
        updateUIStatus();
    }

    public void processRetractionRuleInput(String ruleKif, String sourceId) {
        try {
            var terms = KifParser.parseKif(ruleKif);
            if (terms.size() == 1 && terms.getFirst() instanceof KifList ruleForm) {
                boolean removed = reasonerEngine.removeRule(ruleForm);
                System.out.println("Retract rule from " + sourceId + ": " + (removed ? "Success" : "No match found") + " for: " + ruleForm.toKifString());
            } else {
                System.err.println("Retract rule from " + sourceId + ": Input is not a single valid rule KIF list: " + ruleKif);
            }
        } catch (ParseException e) {
            System.err.println("Retract rule from " + sourceId + ": Parse error: " + e.getMessage());
        }
        updateUIStatus();
    }

    public void registerCallback(KifTerm pattern, KifCallback callback) {
        callbackRegistrations.add(new CallbackRegistration(pattern, callback));
        System.out.println("Registered external callback for pattern: " + pattern.toKifString());
    }

    // --- Callback Handling ---
    // This method is now also called by KnowledgeBase during cascaded retraction
    void invokeCallbacks(String type, Assertion assertion) {
        // Link/Unlink assertion to note ID
        if (CALLBACK_ASSERT_ADDED.equals(type) && assertion.sourceNoteId() != null) {
            noteIdToAssertionIds.computeIfAbsent(assertion.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(assertion.id);
        } else if ((CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) && assertion.sourceNoteId() != null) {
             noteIdToAssertionIds.computeIfPresent(assertion.sourceNoteId(), (k, set) -> {
                set.remove(assertion.id);
                return set.isEmpty() ? null : set;
            });
        }

        // Determine if broadcast is needed
        boolean shouldBroadcast = switch (type) {
            case CALLBACK_ASSERT_ADDED, CALLBACK_ASSERT_RETRACTED, CALLBACK_EVICT -> true;
            case CALLBACK_ASSERT_INPUT -> broadcastInputAssertions;
            default -> false;
        };
        if (shouldBroadcast) {
            broadcastMessage(type, assertion);
        }

        // Notify UI
        if (swingUI != null && swingUI.isDisplayable()) {
            swingUI.handleReasonerCallback(type, assertion);
        }

        // Execute registered pattern callbacks
        callbackRegistrations.forEach(reg -> {
            try {
                // Match only applies meaningfully to added assertions
                if (CALLBACK_ASSERT_ADDED.equals(type)) {
                    Map<KifVar, KifTerm> bindings = Unifier.match(reg.pattern(), assertion.kif, Map.of());
                    if (bindings != null) {
                        reg.callback().onMatch(type, assertion, bindings);
                    }
                }
                // Extend later if callbacks need to react differently to retract/evict
            } catch (Exception e) {
                System.err.println("Error executing KIF pattern callback for " + reg.pattern().toKifString() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });

        // Avoid redundant UI updates if called during clearAllKnowledge
        if (!reasonerStatus.equals("Cleared")) {
             updateUIStatus();
        }
    }

    // Separate method for eviction callback (though currently just calls invokeCallbacks)
    void invokeEvictionCallbacks(Assertion evictedAssertion) {
        invokeCallbacks(CALLBACK_EVICT, evictedAssertion);
    }

    // --- UI Status Update ---
    private void updateUIStatus() {
        if (swingUI != null && swingUI.isDisplayable()) {
            int kbSize = knowledgeBase.getAssertionCount();
            long taskQueueSize = reasonerEngine.getTaskQueueSize();
            long commitQueueSize = reasonerEngine.getCommitQueueSize();
            int ruleCount = reasonerEngine.getRuleCount();
            final String statusText = String.format("KB: %d/%d | Tasks: %d | Commits: %d | Rules: %d | Status: %s",
                    kbSize, capacity, taskQueueSize, commitQueueSize, ruleCount, reasonerEngine.getCurrentStatus());
            SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
        }
    }

    // --- LLM Interaction ---
    public CompletableFuture<String> getKifFromLlmAsync(String contextPrompt, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var finalPrompt = (!contextPrompt.toLowerCase().contains("kif assertions:") && !contextPrompt.toLowerCase().contains("proposed fact:")) ? """
                    Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                    Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                    Use standard SUMO predicates (instance, subclass, domain, range, attribute relations, partOf, etc.).
                    Use '=' for equality between terms. Use unique names for new entities derived from the note.
                    Use '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                    Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                    Example: (instance Fluffy Cat) (attribute Fluffy OrangeColor) (= (age Fluffy) 3) (not (attribute Fluffy BlackColor))

                    Note:
                    "%s"

                    KIF Assertions:""".formatted(contextPrompt) : contextPrompt; // Added (not...) example

            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", finalPrompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2));

            var request = HttpRequest.newBuilder(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var jsonResponse = new JSONObject(new JSONTokener(responseBody));
                    var kifResult = extractLlmContent(jsonResponse).orElse("");

                    var cleanedKif = kifResult.lines()
                            .map(s -> s.replaceAll("(?i)```kif", "").replaceAll("```", "").trim())
                            .filter(line -> line.startsWith("(") && line.endsWith(")"))
                            .filter(line -> !line.matches("^\\(\\s*\\)$"))
                            .collect(Collectors.joining("\n"));

                    if (cleanedKif.isEmpty() && !kifResult.isBlank()) {
                        System.err.println("LLM Warning (" + noteId + "): Result contained text but no valid KIF lines found after cleaning:\n---\n" + kifResult + "\n---");
                    }
                    return cleanedKif;
                } else {
                    throw new IOException("LLM API request failed: " + response.statusCode() + " " + responseBody);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("LLM API interaction failed for note " + noteId + ": " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException("LLM API communication error", e);
            } catch (Exception e) {
                System.err.println("LLM response processing failed for note " + noteId + ": " + e.getMessage());
                e.printStackTrace();
                throw new CompletionException("LLM response processing error", e);
            }
        }, httpClient.executor().orElse(Executors.newVirtualThreadPerTaskExecutor()));
    }

    private Optional<String> extractLlmContent(JSONObject jsonResponse) {
        return Optional.ofNullable(jsonResponse.optJSONObject("message")).map(m -> m.optString("content", null))
                .or(() -> Optional.ofNullable(jsonResponse.optString("response", null)))
                .or(() -> Optional.ofNullable(jsonResponse.optJSONArray("choices"))
                        .filter(Predicate.not(JSONArray::isEmpty))
                        .map(a -> a.optJSONObject(0))
                        .map(c -> c.optJSONObject("message"))
                        .map(m -> m.optString("content", null)))
                .or(() -> findNestedContent(jsonResponse));
    }

    private Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> {
                if (obj.has("content") && obj.get("content") instanceof String s && !s.isBlank()) yield Optional.of(s);
                Optional<String> found = Optional.empty();
                for (var key : obj.keySet()) {
                    found = findNestedContent(obj.get(key));
                    if (found.isPresent()) break;
                }
                yield found;
            }
            case JSONArray arr -> {
                Optional<String> found = Optional.empty();
                for (var i = 0; i < arr.length(); i++) {
                    found = findNestedContent(arr.get(i));
                    if (found.isPresent()) break;
                }
                yield found;
            }
            default -> Optional.empty();
        };
    }

    // --- KIF Data Structures ---
    sealed interface KifTerm permits KifAtom, KifVar, KifList {
        String toKifString();
        boolean containsVariable();
        Set<KifVar> getVariables();
        int calculateWeight();

        static Set<KifVar> collectVariablesFromSpec(KifTerm varsTerm) {
            return switch (varsTerm) {
                case KifVar v -> Set.of(v);
                case KifList l ->
                        l.terms().stream()
                                .filter(KifVar.class::isInstance)
                                .map(KifVar.class::cast)
                                .collect(Collectors.toUnmodifiableSet());
                default -> {
                    System.err.println("Warning: Invalid variable specification in quantifier: " + varsTerm.toKifString());
                    yield Set.of();
                }
            };
        }
    }

    @FunctionalInterface
    interface KifCallback {
        void onMatch(String type, Assertion assertion, Map<KifVar, KifTerm> bindings);
    }

    static final class KifAtom implements KifTerm {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$");
        private final String value;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;

        KifAtom(String value) { this.value = Objects.requireNonNull(value); }
        public String value() { return value; }

        @Override
        public String toKifString() {
            boolean needsQuotes = value.isEmpty()
                    || value.chars().anyMatch(Character::isWhitespace)
                    || value.chars().anyMatch(c -> "()\";?".indexOf(c) != -1)
                    || !SAFE_ATOM_PATTERN.matcher(value).matches();
            return needsQuotes ? '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : value;
        }

        @Override public boolean containsVariable() { return false; }
        @Override public Set<KifVar> getVariables() { return Set.of(); }
        @Override public int calculateWeight() { return 1; }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof KifAtom a && value.equals(a.value));
        }

        @Override
        public int hashCode() {
            if (!hashCodeCalculated) {
                hashCodeCache = value.hashCode();
                hashCodeCalculated = true;
            }
            return hashCodeCache;
        }

        @Override public String toString() { return "KifAtom[" + value + ']'; }
    }

    static final class KifVar implements KifTerm {
        private final String name;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;

        KifVar(String name) {
            Objects.requireNonNull(name);
            if (!name.startsWith("?") || name.length() == 1)
                throw new IllegalArgumentException("Variable name must start with '?' and be non-empty: " + name);
            this.name = name;
        }
        public String name() { return name; }

        @Override public String toKifString() { return name; }
        @Override public boolean containsVariable() { return true; }
        @Override public Set<KifVar> getVariables() { return Set.of(this); }
        @Override public int calculateWeight() { return 1; }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof KifVar v && name.equals(v.name));
        }

        @Override
        public int hashCode() {
            if (!hashCodeCalculated) {
                hashCodeCache = name.hashCode();
                hashCodeCalculated = true;
            }
            return hashCodeCache;
        }
        @Override public String toString() { return "KifVar[" + name + ']'; }
    }

    static final class KifList implements KifTerm {
        final List<KifTerm> terms;
        private volatile Boolean containsVariableCache;
        private volatile Set<KifVar> variablesCache;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;

        KifList(KifTerm... terms) { this(List.of(terms)); }
        KifList(List<KifTerm> terms) { this.terms = List.copyOf(Objects.requireNonNull(terms)); }

        public List<KifTerm> terms() { return terms; }
        KifTerm get(int index) { return terms.get(index); }
        int size() { return terms.size(); }

        Optional<String> getOperator() {
            return terms.isEmpty() || !(terms.getFirst() instanceof KifAtom atom) ? Optional.empty() : Optional.of(atom.value());
        }

        @Override
        public String toKifString() {
            if (kifStringCache == null) {
                kifStringCache = terms.stream().map(KifTerm::toKifString).collect(Collectors.joining(" ", "(", ")"));
            }
            return kifStringCache;
        }

        @Override
        public boolean containsVariable() {
            if (containsVariableCache == null) {
                containsVariableCache = terms.stream().anyMatch(KifTerm::containsVariable);
            }
            return containsVariableCache;
        }

        @Override
        public Set<KifVar> getVariables() {
            if (variablesCache == null) {
                Set<KifVar> vars = new HashSet<>();
                collectVariablesRecursive(this, vars);
                variablesCache = Collections.unmodifiableSet(vars);
            }
            return variablesCache;
        }

        private static void collectVariablesRecursive(KifTerm term, Set<KifVar> vars) {
            switch (term) {
                case KifVar v -> vars.add(v);
                case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars));
                case KifAtom ignored -> {}
            }
        }

        @Override
        public int calculateWeight() {
            if (weightCache == -1) {
                weightCache = 1 + terms.stream().mapToInt(KifTerm::calculateWeight).sum();
            }
            return weightCache;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof KifList l && this.hashCode() == l.hashCode() && terms.equals(l.terms));
        }

        @Override
        public int hashCode() {
            if (!hashCodeCalculated) {
                hashCodeCache = terms.hashCode();
                hashCodeCalculated = true;
            }
            return hashCodeCache;
        }
        @Override public String toString() { return "KifList[" + terms + ']'; }
    }

    // --- Reasoner Data Records ---
    record Assertion(String id, KifList kif, double pri, long timestamp, @Nullable String sourceNoteId, Set<String> support,
                     boolean isEquality, boolean isOrientedEquality, boolean isNegated) implements Comparable<Assertion> {
        Assertion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
            // Validate negation consistency
            if (isNegated && !kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                 throw new IllegalArgumentException("Assertion marked negated but KIF does not start with 'not': " + kif.toKifString());
            }
            if (!isNegated && kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                 // This could be allowed if (not ...) is just another predicate, but let's enforce consistency
                 throw new IllegalArgumentException("Assertion not marked negated but KIF starts with 'not': " + kif.toKifString());
            }
        }

        @Override public int compareTo(Assertion other) { return Double.compare(this.pri, other.pri); }
        String toKifString() { return kif.toKifString(); }

        // Helper to get the inner term if negated, otherwise the term itself
        KifTerm getEffectiveTerm() {
            return isNegated ? kif.get(1) : kif;
        }
    }

    record Rule(String id, KifList ruleForm, KifTerm antecedent, KifTerm consequent, double pri,
                List<KifTerm> antecedents) { // Antecedents can now be KifList or (not KifList)
        Rule {
            Objects.requireNonNull(id);
            Objects.requireNonNull(ruleForm);
            Objects.requireNonNull(antecedent);
            Objects.requireNonNull(consequent);
            antecedents = List.copyOf(Objects.requireNonNull(antecedents)); // Ensure immutable list
        }

        static Rule parseRule(String id, KifList ruleForm, double pri) throws IllegalArgumentException {
            if (!(ruleForm.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent() && ruleForm.size() == 3))
                throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());

            var antTerm = ruleForm.get(1);
            var conTerm = ruleForm.get(2);

            // Parse antecedent(s) - handles single clause, (and ...), allows (not ...) within
            List<KifTerm> parsedAntecedents = switch (antTerm) {
                case KifList list when list.getOperator().filter(KIF_OPERATOR_AND::equals).isPresent() -> {
                    // Allow KifList or (not KifList) inside 'and'
                    List y = list.terms().stream().skip(1).map(t -> {
                        if (t instanceof KifList l) {
                            if (l.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                                if (l.size() == 2 && l.get(1) instanceof KifList) return l; // Valid (not (p...))
                                else
                                    throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + l.toKifString());
                            } else {
                                return l; // Valid positive literal (p...)
                            }
                        }
                        throw new IllegalArgumentException("Elements of 'and' antecedent must be lists or (not list): " + t.toKifString());
                    }).toList();
                    yield y;
                }
                case KifList list -> { // Single antecedent clause
                     if (list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                         if (list.size() == 2 && list.get(1) instanceof KifList) yield List.of(list); // Valid (not (p...))
                         else throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + list.toKifString());
                     } else {
                         yield List.of(list); // Valid positive literal (p...)
                     }
                }
                default ->
                        throw new IllegalArgumentException("Antecedent must be a KIF list, (not list), or (and ...): " + antTerm.toKifString());
            };

            validateUnboundVariables(ruleForm, antTerm, conTerm);
            return new Rule(id, ruleForm, antTerm, conTerm, pri, parsedAntecedents);
        }

        // Unchanged from previous version
        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
             var antVars = antecedent.getVariables();
             var conVarsAll = consequent.getVariables();
             var conVarsBoundLocally = getQuantifierBoundVariables(consequent);

             Set<KifVar> conVarsNeedingBinding = new HashSet<>(conVarsAll);
             conVarsNeedingBinding.removeAll(antVars);
             conVarsNeedingBinding.removeAll(conVarsBoundLocally);

             if (!conVarsNeedingBinding.isEmpty() && ruleForm.getOperator().filter(KIF_OPERATOR_IMPLIES::equals).isPresent()) {
                 System.err.println("Warning: Rule consequent has variables not bound by antecedent or local quantifier: "
                         + conVarsNeedingBinding.stream().map(KifVar::name).collect(Collectors.joining(", "))
                         + " in " + ruleForm.toKifString());
             }
        }
        private static Set<KifVar> getQuantifierBoundVariables(KifTerm term) {
             Set<KifVar> bound = new HashSet<>();
             collectQuantifierBoundVariablesRecursive(term, bound);
             return Collections.unmodifiableSet(bound);
        }
        private static void collectQuantifierBoundVariablesRecursive(KifTerm term, Set<KifVar> boundVars) {
             switch (term) {
                 case KifList list when list.size() == 3 && list.getOperator().filter(op -> op.equals(KIF_OPERATOR_EXISTS) || op.equals(KIF_OPERATOR_FORALL)).isPresent() -> {
                     boundVars.addAll(KifTerm.collectVariablesFromSpec(list.get(1)));
                     collectQuantifierBoundVariablesRecursive(list.get(2), boundVars);
                 }
                 case KifList list -> list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
                 default -> {}
             }
        }

        @Override
        public boolean equals(Object o) { return this == o || (o instanceof Rule r && ruleForm.equals(r.ruleForm)); }
        @Override public int hashCode() { return ruleForm.hashCode(); }
    }

    record PotentialAssertion(KifList kif, double pri, Set<String> support, String sourceId, boolean isEquality,
                              boolean isNegated, boolean isOrientedEquality, @Nullable String sourceNoteId) {
        PotentialAssertion {
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
            Objects.requireNonNull(sourceId);
             // Validate negation consistency
            if (isNegated && !kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                 throw new IllegalArgumentException("PotentialAssertion marked negated but KIF does not start with 'not': " + kif.toKifString());
            }
            if (!isNegated && kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                 throw new IllegalArgumentException("PotentialAssertion not marked negated but KIF starts with 'not': " + kif.toKifString());
            }
        }
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && kif.equals(pa.kif); }
        @Override public int hashCode() { return kif.hashCode(); }
    }

    enum TaskType { MATCH_ANTECEDENT, APPLY_ORDERED_REWRITE }

    record InferenceTask(TaskType type, double pri, Object data) implements Comparable<InferenceTask> {
        InferenceTask { Objects.requireNonNull(type); Objects.requireNonNull(data); }
        static InferenceTask matchAntecedent(Rule rule, Assertion trigger, Map<KifVar, KifTerm> bindings, double pri) {
            return new InferenceTask(TaskType.MATCH_ANTECEDENT, pri, new MatchContext(rule, trigger.id, bindings));
        }
        static InferenceTask applyRewrite(Assertion rule, Assertion target, double pri) {
            return new InferenceTask(TaskType.APPLY_ORDERED_REWRITE, pri, new RewriteContext(rule, target));
        }
        @Override public int compareTo(InferenceTask other) { return Double.compare(other.pri, this.pri); }
    }

    record MatchContext(Rule rule, String triggerAssertionId, Map<KifVar, KifTerm> initialBindings) {}
    record RewriteContext(Assertion rewriteRule, Assertion targetAssertion) {}
    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
         CallbackRegistration { Objects.requireNonNull(pattern); Objects.requireNonNull(callback); }
    }

    // --- Path Indexing Structures ---
    static class PathNode {
        final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
        final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet();
        static final Class<KifVar> VAR_MARKER = KifVar.class;
    }

    static class PathIndex {
        private final PathNode root = new PathNode();

        void add(Assertion assertion) {
            addPathsRecursive(assertion.kif, assertion.id, root);
        }

        void remove(Assertion assertion) {
            removePathsRecursive(assertion.kif, assertion.id, root);
        }

        void clear() {
            root.children.clear();
            root.assertionIdsHere.clear();
        }

        Set<String> findUnifiable(KifTerm queryTerm) {
            Set<String> candidates = new HashSet<>();
            findUnifiableRecursive(queryTerm, root, candidates);
            return Set.copyOf(candidates);
        }

        Set<String> findInstances(KifTerm queryPattern) {
            Set<String> candidates = new HashSet<>();
            findInstancesRecursive(queryPattern, root, candidates);
            return Set.copyOf(candidates);
        }

        Set<String> findGeneralizations(KifTerm queryTerm) {
            Set<String> candidates = new HashSet<>();
            findGeneralizationsRecursive(queryTerm, root, candidates);
            return Set.copyOf(candidates);
        }

        // --- Index Modification (Add/Remove) ---

        // Refined recursive add: Recurses into list elements under the operator node.
        private void addPathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return;
            currentNode.assertionIdsHere.add(assertionId); // ID associated with the path prefix ending here

            Object key = getIndexKey(term);
            PathNode termNode = currentNode.children.computeIfAbsent(key, k -> new PathNode());
            termNode.assertionIdsHere.add(assertionId); // ID associated directly with this term/key

            // If it's a list, recurse on its elements *within* the node keyed by the operator/marker
            if (term instanceof KifList list) {
                list.terms().forEach(subTerm -> addPathsRecursive(subTerm, assertionId, termNode));
            }
            // No deeper recursion needed for atoms or vars at this level
        }

        // Refined recursive remove: Mirrors the add structure.
        private boolean removePathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return false;
            currentNode.assertionIdsHere.remove(assertionId);

            Object key = getIndexKey(term);
            PathNode termNode = currentNode.children.get(key);

            if (termNode != null) {
                termNode.assertionIdsHere.remove(assertionId); // Remove ID from the term's node

                boolean canPruneChild = true; // Assume prunable unless sub-recursion says otherwise
                if (term instanceof KifList list) {
                    // Recurse for elements within the termNode
                    for (var subTerm : list.terms()) {
                        if (!removePathsRecursive(subTerm, assertionId, termNode)) {
                            canPruneChild = false; // If any sub-path isn't empty, don't prune yet
                        }
                    }
                }
                // For atoms/vars, canPruneChild remains true (no deeper recursion)

                // Pruning logic: Remove child if recursive calls allow AND node is now empty
                if (canPruneChild && termNode.assertionIdsHere.isEmpty() && termNode.children.isEmpty()) {
                    currentNode.children.remove(key, termNode);
                }
            }
            // Return true if this node itself is now empty and potentially prunable
            return currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
        }

        // Key generation remains the same (atom value, VAR_MARKER, list operator/VAR_MARKER)
        private Object getIndexKey(KifTerm term) {
            return switch (term) {
                case KifAtom a -> a.value();
                case KifVar v -> PathNode.VAR_MARKER;
                case KifList l -> ((Optional) l.getOperator()).orElse(PathNode.VAR_MARKER);
            };
        }

        // --- Index Search ---

        // Find potentially unifiable terms. Refined list handling.
        private void findUnifiableRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            candidates.addAll(indexNode.assertionIdsHere); // Add IDs at current path prefix

            final var childrenMap = indexNode.children;

            // Always check VAR_MARKER branch in index
            Optional.ofNullable(childrenMap.get(PathNode.VAR_MARKER))
                    .ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));

            switch (queryTerm) {
                case KifAtom queryAtom -> // Query Atom: Match Atom key
                        Optional.ofNullable(childrenMap.get(queryAtom.value()))
                                .ifPresent(atomNode -> collectAllAssertionIds(atomNode, candidates)); // Collect all under atom

                case KifVar queryVar -> // Query Var: Matches everything below (already handled by VAR_MARKER + initial addAll)
                        childrenMap.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));

                case KifList queryList -> {
                    // Query List: Match List key (operator or VAR_MARKER)
                    Object listKey = getIndexKey(queryList);
                    Optional.ofNullable(childrenMap.get(listKey))
                            .ifPresent(listNode -> {
                                candidates.addAll(listNode.assertionIdsHere); // Add IDs at the list node itself
                                // **Enhanced:** Recurse into listNode using first query arg as heuristic probe
                                if (!queryList.terms.isEmpty()) {
                                    findUnifiableRecursive(queryList.get(0), listNode, candidates);
                                } else {
                                    // If query list is empty, collect everything under the matched node
                                    collectAllAssertionIds(listNode, candidates);
                                }
                            });
                }
            }
        }

        // Find potential instances. Refined list handling.
        private void findInstancesRecursive(KifTerm queryPattern, PathNode indexNode, Set<String> candidates) {
             if (indexNode == null) return;
             if (queryPattern instanceof KifVar) { // If pattern is var, collect everything here
                 candidates.addAll(indexNode.assertionIdsHere);
             }

             final var childrenMap = indexNode.children;

             switch (queryPattern) {
                 case KifAtom queryAtom -> // Pattern Atom: Exact match key
                         Optional.ofNullable(childrenMap.get(queryAtom.value()))
                                 .ifPresent(atomNode -> candidates.addAll(atomNode.assertionIdsHere)); // Collect IDs at leaf

                 case KifVar queryVar -> // Pattern Var: Collect everything below
                         childrenMap.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));

                 case KifList queryList -> {
                     // Pattern List: Match List key
                     Object listKey = getIndexKey(queryList);
                     Optional.ofNullable(childrenMap.get(listKey))
                             .ifPresent(listNode -> {
                                 candidates.addAll(listNode.assertionIdsHere); // Add IDs at list node
                                 // **Enhanced:** Recurse into listNode using first pattern arg
                                 if (!queryList.terms.isEmpty()) {
                                     findInstancesRecursive(queryList.get(0), listNode, candidates);
                                 } else {
                                     collectAllAssertionIds(listNode, candidates);
                                 }
                             });
                     // Also consider index VAR_MARKER matching this list pattern
                     Optional.ofNullable(childrenMap.get(PathNode.VAR_MARKER))
                             .ifPresent(varNode -> candidates.addAll(varNode.assertionIdsHere));
                 }
             }
        }

        // Find potential generalizations. Refined list handling.
        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;

            // Generalizations primarily found at VAR_MARKER nodes
            Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER))
                    .ifPresent(varNode -> {
                        candidates.addAll(varNode.assertionIdsHere);
                        // Don't just stop, explore deeper under VAR_MARKER too
                        findGeneralizationsRecursive(queryTerm, varNode, candidates);
                    });

            // Follow the specific path of the query term
            Object key = getIndexKey(queryTerm);
            Optional.ofNullable(indexNode.children.get(key))
                    .ifPresent(nextNode -> {
                         candidates.addAll(nextNode.assertionIdsHere); // Add IDs at the exact match node
                         // **Enhanced:** Recurse deeper if query is a list
                         if (queryTerm instanceof KifList queryList && !queryList.terms.isEmpty()) {
                             findGeneralizationsRecursive(queryList.get(0), nextNode, candidates);
                         }
                    });
        }

        // Collect all IDs from a node and its descendants.
        private void collectAllAssertionIds(PathNode node, Set<String> ids) {
            if (node == null) return;
            ids.addAll(node.assertionIdsHere);
            node.children.values().forEach(child -> collectAllAssertionIds(child, ids));
        }
    }


    // --- Knowledge Base ---
    static class KnowledgeBase {
        private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
        private final PathIndex pathIndex = new PathIndex();
        private final ConcurrentMap<String, Set<String>> assertionDependencies = new ConcurrentHashMap<>();
        private final PriorityBlockingQueue<Assertion> evictionQueue = new PriorityBlockingQueue<>();
        private final ReadWriteLock kbLock = new ReentrantReadWriteLock();
        private final int maxKbSize;
        // Use BiConsumer for callbacks to pass type information (assert-added, retract, evict)
        private final BiConsumer<String, Assertion> eventCallback;

        KnowledgeBase(int maxKbSize, BiConsumer<String, Assertion> eventCallback) {
            this.maxKbSize = maxKbSize;
            this.eventCallback = Objects.requireNonNull(eventCallback);
        }

        Optional<Assertion> commitAssertion(PotentialAssertion pa, String newId, long timestamp) {
            kbLock.writeLock().lock();
            try {
                if (isTrivial(pa.kif)) return Optional.empty();
                if (isSubsumedInternal(pa)) return Optional.empty();

                // Create the final Assertion object, including the isNegated flag
                var newAssertion = new Assertion(newId, pa.kif, pa.pri, timestamp, pa.sourceNoteId(), pa.support(), pa.isEquality(), pa.isOrientedEquality(), pa.isNegated());

                if (!newAssertion.kif.containsVariable() && findExactMatchInternal(newAssertion.kif) != null) {
                    return Optional.empty();
                }

                enforceKbCapacityInternal();
                if (assertionsById.size() >= maxKbSize) {
                    System.err.printf("Warning: KB full (%d/%d), cannot add: %s%n", assertionsById.size(), maxKbSize, pa.kif.toKifString());
                    return Optional.empty();
                }

                if (assertionsById.putIfAbsent(newId, newAssertion) != null) {
                    System.err.println("KB Commit Error: ID collision for " + newId);
                    return Optional.empty();
                }

                pathIndex.add(newAssertion);
                evictionQueue.put(newAssertion);

                // Update dependencies
                for (String supporterId : newAssertion.support()) {
                    assertionDependencies.computeIfAbsent(supporterId, k -> ConcurrentHashMap.newKeySet()).add(newId);
                }

                // Invoke ADDED callback *after* successful commit
                eventCallback.accept(CALLBACK_ASSERT_ADDED, newAssertion);
                return Optional.of(newAssertion);
            } finally {
                kbLock.writeLock().unlock();
            }
        }

        Assertion retractAssertion(String id) {
            kbLock.writeLock().lock();
            try {
                // Initiate cascade, pass empty set for initial call
                return retractAssertionWithCascadeInternal(id, new HashSet<>());
            } finally {
                kbLock.writeLock().unlock();
            }
        }

        // Internal cascade logic
        private Assertion retractAssertionWithCascadeInternal(String id, Set<String> retractedInThisCascade) {
            if (retractedInThisCascade.contains(id)) return null; // Cycle detected or already processed

            Assertion removed = assertionsById.remove(id);
            if (removed != null) {
                retractedInThisCascade.add(id); // Mark as processed for this cascade
                pathIndex.remove(removed);
                evictionQueue.remove(removed);

                // Clean up dependencies: remove this from supporters' dependent lists
                removed.support().forEach(supporterId ->
                    assertionDependencies.computeIfPresent(supporterId, (k, v) -> {
                        v.remove(id);
                        return v.isEmpty() ? null : v;
                    })
                );

                // Find and remove dependents
                Set<String> dependents = assertionDependencies.remove(id);
                if (dependents != null) {
                    // System.out.printf("Cascading retraction from [%s] to %d dependents: %s%n", id, dependents.size(), dependents);
                    // Create copy before iterating as recursive call modifies the map
                    new HashSet<>(dependents).forEach(dependentId -> retractAssertionWithCascadeInternal(dependentId, retractedInThisCascade));
                }

                // Invoke RETRACTED callback *after* handling cascade for this assertion
                eventCallback.accept(CALLBACK_ASSERT_RETRACTED, removed);
                return removed;
            } else {
                return null; // Not found
            }
        }

        Optional<Assertion> getAssertion(String id) {
            kbLock.readLock().lock();
            try { return Optional.ofNullable(assertionsById.get(id)); }
            finally { kbLock.readLock().unlock(); }
        }

        boolean isSubsumed(PotentialAssertion pa) {
            kbLock.readLock().lock();
            try { return isSubsumedInternal(pa); }
            finally { kbLock.readLock().unlock(); }
        }

        // Internal subsumption check, handles negation
        private boolean isSubsumedInternal(PotentialAssertion pa) {
            // Find potential generalizations (more general terms in the KB)
            Set<String> candidateIds = pathIndex.findGeneralizations(pa.kif);
            if (candidateIds.isEmpty()) return false;

            return candidateIds.stream()
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    .anyMatch(candidate -> {
                        // Check if candidate subsumes pa
                        // Both positive or both negative, and inner term matches
                        if (candidate.isNegated() == pa.isNegated()) {
                            // If negated, match inner terms. If positive, match directly.
                            KifTerm pattern = candidate.isNegated ? candidate.kif.get(1) : candidate.kif;
                            KifTerm term = pa.isNegated ? pa.kif.get(1) : pa.kif;
                            return Unifier.match(pattern, term, Map.of()) != null;
                        }
                        return false; // Cannot subsume if one is negated and the other isn't
                    });
        }

        // Find assertions that *might* unify (read lock)
        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) {
            kbLock.readLock().lock();
            try {
                Set<String> ids = pathIndex.findUnifiable(queryTerm);
                return ids.stream().map(assertionsById::get).filter(Objects::nonNull);
            } finally {
                kbLock.readLock().unlock();
            }
        }

        // Find assertions that *are* instances of a pattern (read lock + unification)
        // Handles negation in the pattern.
        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            kbLock.readLock().lock();
            try {
                Set<String> candidateIds = pathIndex.findInstances(queryPattern);
                boolean patternIsNegated = (queryPattern instanceof KifList ql && ql.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());

                return candidateIds.stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull)
                        // Ensure assertion's negation status matches pattern's
                        .filter(a -> a.isNegated() == patternIsNegated)
                        // Perform final match check
                        .filter(a -> Unifier.match(queryPattern, a.kif, Map.of()) != null);
            } finally {
                kbLock.readLock().unlock();
            }
        }

        @Nullable Assertion findExactMatch(KifList groundKif) {
            kbLock.readLock().lock();
            try { return findExactMatchInternal(groundKif); }
            finally { kbLock.readLock().unlock(); }
        }

        @Nullable private Assertion findExactMatchInternal(KifList groundKif) {
             Set<String> candidateIds = pathIndex.findInstances(groundKif);
             boolean queryIsNegated = groundKif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
             return candidateIds.stream()
                     .map(assertionsById::get)
                     .filter(Objects::nonNull)
                     .filter(a -> a.isNegated() == queryIsNegated) // Match negation status
                     .filter(a -> a.kif.equals(groundKif)) // Exact equality check
                     .findFirst()
                     .orElse(null);
        }

        List<Assertion> getAllAssertions() {
            kbLock.readLock().lock();
            try { return List.copyOf(assertionsById.values()); }
            finally { kbLock.readLock().unlock(); }
        }

        // Helper to get all current assertion IDs (used by clearAllKnowledge)
        List<String> getAllAssertionIds() {
             kbLock.readLock().lock();
            try { return List.copyOf(assertionsById.keySet()); }
            finally { kbLock.readLock().unlock(); }
        }


        int getAssertionCount() { return assertionsById.size(); }

        void clear() {
            kbLock.writeLock().lock();
            try {
                // Clear data structures
                assertionsById.clear();
                pathIndex.clear();
                evictionQueue.clear();
                assertionDependencies.clear();
                // Note: Callbacks for removed items are handled by the caller (clearAllKnowledge)
            } finally {
                kbLock.writeLock().unlock();
            }
        }

        // Internal capacity enforcement, triggers cascade retraction
        private void enforceKbCapacityInternal() {
            while (assertionsById.size() >= maxKbSize && !evictionQueue.isEmpty()) {
                Assertion lowestPri = evictionQueue.poll();
                if (lowestPri != null) {
                    // Retract using cascade, which will invoke EVICT callback via eventCallback
                    Assertion actuallyRemoved = retractAssertionWithCascadeInternal(lowestPri.id, new HashSet<>());
                    if (actuallyRemoved != null) {
                         // Explicitly signal eviction *after* cascade finishes for the evicted item
                         eventCallback.accept(CALLBACK_EVICT, actuallyRemoved);
                    }
                }
            }
        }
    }


    // --- Reasoner Engine ---
    static class ReasonerEngine {
        private final KnowledgeBase kb;
        private final Supplier<String> idGenerator;
        private final BiConsumer<String, Assertion> callbackNotifier; // For assert-added etc.
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<PotentialAssertion> commitQueue = new LinkedBlockingQueue<>(COMMIT_QUEUE_CAPACITY);
        private final PriorityBlockingQueue<InferenceTask> taskQueue = new PriorityBlockingQueue<>(TASK_QUEUE_CAPACITY);
        private final ExecutorService commitExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "CommitThread"));
        private final ExecutorService inferenceExecutor = Executors.newVirtualThreadPerTaskExecutor();
        private final Object pauseLock;
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private volatile String currentStatus = "Idle";

        ReasonerEngine(KnowledgeBase kb, Supplier<String> idGenerator, BiConsumer<String, Assertion> notifier, Object pauseLock) {
            this.kb = kb;
            this.idGenerator = idGenerator;
            this.callbackNotifier = notifier; // Used for ADDED callback from commit loop
            this.pauseLock = pauseLock;
        }

        void start() {
            if (running) return;
            running = true;
            paused = false;
            currentStatus = "Starting";
            commitExecutor.submit(this::commitLoop);
            int workerCount = Math.max(MIN_INFERENCE_WORKERS, Runtime.getRuntime().availableProcessors() / 2);
            for (int i = 0; i < workerCount; i++) {
                inferenceExecutor.submit(this::inferenceLoop);
            }
            currentStatus = "Running";
            System.out.println("Reasoner Engine started with " + workerCount + " inference workers.");
        }

        void stop() {
            if (!running) return;
            running = false;
            paused = false;
            synchronized (pauseLock) { pauseLock.notifyAll(); }
            currentStatus = "Stopping";
            shutdownExecutor(commitExecutor, "Commit Executor");
            shutdownExecutor(inferenceExecutor, "Inference Executor");
            currentStatus = "Stopped";
            System.out.println("Reasoner Engine stopped.");
        }

        void setPaused(boolean pause) {
            if (this.paused == pause) return;
            this.paused = pause;
            currentStatus = pause ? "Paused" : "Running";
            // Notification handled by CogNote on shared lock
        }

        void clear() {
            setPaused(true);
            rules.clear();
            commitQueue.clear();
            taskQueue.clear();
            System.out.println("Reasoner Engine queues and rules cleared.");
            setPaused(false);
        }

        long getTaskQueueSize() { return taskQueue.size(); }
        long getCommitQueueSize() { return commitQueue.size(); }
        int getRuleCount() { return rules.size(); }
        String getCurrentStatus() { return currentStatus; }

        void submitPotentialAssertion(PotentialAssertion pa) {
            if (!running || pa == null) return;
            try {
                if (!commitQueue.offer(pa, 100, TimeUnit.MILLISECONDS)) {
                     System.err.println("Warning: Commit queue full. Discarding potential assertion: " + pa.kif().toKifString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while submitting potential assertion.");
            }
        }

        void addRule(Rule rule) {
            if (rules.add(rule)) {
                System.out.println("Added rule: " + rule.id + " " + rule.ruleForm().toKifString());
                triggerMatchingForNewRule(rule);
            }
        }

        boolean removeRule(KifList ruleForm) {
            return rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));
        }

        // --- Core Loops ---
        private void commitLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    waitIfPaused();
                    if (!running) break;
                    PotentialAssertion pa = commitQueue.take();
                    currentStatus = "Committing";
                    // Commit to KB. KB now handles ADDED callback internally.
                    kb.commitAssertion(pa, generateFactId(pa), System.currentTimeMillis())
                      .ifPresent(this::generateNewTasks); // Generate tasks if commit was successful

                    currentStatus = "Idle (Commit)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Commit thread interrupted.");
                    running = false;
                } catch (Exception e) {
                    System.err.println("Error in commit loop: " + e.getMessage());
                    e.printStackTrace();
                    currentStatus = "Error (Commit)";
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            System.out.println("Commit thread finished.");
        }

        private void inferenceLoop() {
             while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    waitIfPaused();
                    if (!running) break;
                    InferenceTask task = taskQueue.take();
                    currentStatus = "Inferring (" + task.type + ")";
                    switch (task.type) {
                        case MATCH_ANTECEDENT -> handleMatchAntecedentTask(task);
                        case APPLY_ORDERED_REWRITE -> handleApplyRewriteTask(task);
                    }
                    currentStatus = "Idle (Inference)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Inference worker interrupted.");
                    running = false;
                } catch (Exception e) {
                    System.err.println("Error in inference loop: " + e.getMessage());
                    e.printStackTrace();
                    currentStatus = "Error (Inference)";
                     try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            System.out.println("Inference worker finished.");
        }

        private void waitIfPaused() throws InterruptedException {
            synchronized (pauseLock) {
                while (paused && running) {
                    pauseLock.wait();
                }
            }
        }

        // --- Task Handling ---
        private void handleMatchAntecedentTask(InferenceTask task) {
            if (!(task.data instanceof MatchContext(Rule rule, String triggerId, Map<KifVar, KifTerm> initialBindings))) return;

            // Find matches recursively, now handles negated antecedents
            findMatchesRecursive(rule, rule.antecedents(), initialBindings, Set.of(triggerId))
                .forEach(matchResult -> {
                    var consequentTerm = Unifier.subst(rule.consequent, matchResult.bindings);

                    // Handle derived positive or negative literals
                    if (consequentTerm instanceof KifList derived && !derived.containsVariable()) {
                         boolean derivedIsNegated = derived.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
                         if (derivedIsNegated && derived.size() != 2) {
                             System.err.println("Rule " + rule.id + " derived invalid 'not' structure: " + derived.toKifString());
                             return; // Skip invalid derived term
                         }
                         if (!isTrivial(derived)) {
                            double derivedPri = calculateDerivedPri(matchResult.supportIds(), rule.pri);
                            String commonNoteId = findCommonSourceNodeId(matchResult.supportIds());
                            boolean isEq = !derivedIsNegated && derived.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
                            boolean isOriented = isEq && derived.size() == 3 && derived.get(1).calculateWeight() > derived.get(2).calculateWeight();

                            var pa = new PotentialAssertion(derived, derivedPri, matchResult.supportIds(), rule.id, isEq, derivedIsNegated, isOriented, commonNoteId);
                            submitPotentialAssertion(pa);
                        }
                    }
                    // Handle derived quantifiers (e.g., exists) - currently ignored
                    else if (consequentTerm instanceof KifList derived && derived.getOperator().filter(KIF_OPERATOR_EXISTS::equals).isPresent()) {
                         System.out.println("Note: Derived existential assertion currently not processed further: " + derived.toKifString());
                    }
                });
        }

        // Recursive matching, adapted for negated literals
        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifTerm> remainingClauses, Map<KifVar, KifTerm> currentBindings, Set<String> currentSupportIds) {
            if (remainingClauses.isEmpty()) {
                return Stream.of(new MatchResult(currentBindings, currentSupportIds));
            }

            var clauseToMatch = remainingClauses.getFirst();
            var nextRemainingClauses = remainingClauses.subList(1, remainingClauses.size());

            // Substitute bindings into the *pattern* part of the clause
            var substitutedClause = Unifier.substFully(clauseToMatch, currentBindings);

            // Determine if the clause is positive or negative, and get the pattern inside
            boolean clauseIsNegated = (substitutedClause instanceof KifList l && l.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());
            KifTerm pattern = clauseIsNegated ? ((KifList) substitutedClause).get(1) : substitutedClause;

            if (!(pattern instanceof KifList)) {
                 // Antecedent clause (or its 'not' argument) must be a list pattern after substitution
                 System.err.println("Rule " + rule.id + " has invalid antecedent clause structure after substitution: " + substitutedClause.toKifString());
                 return Stream.empty();
            }

            // Find candidate assertions in the KB
            // Query must match the negation status of the clause
            return kb.findUnifiableAssertions(substitutedClause) // Query with the full clause (incl. not if present)
                     .filter(candidate -> candidate.isNegated() == clauseIsNegated) // Ensure negation matches
                     .flatMap(candidateAssertion -> {
                         // Unify the *inner* pattern with the *inner* term of the candidate
                         KifTerm candidateInnerTerm = clauseIsNegated ? candidateAssertion.kif.get(1) : candidateAssertion.kif;
                         Map<KifVar, KifTerm> newBindings = Unifier.unify(pattern, candidateInnerTerm, currentBindings);

                         if (newBindings != null) {
                             Set<String> nextSupport = new HashSet<>(currentSupportIds);
                             nextSupport.add(candidateAssertion.id);
                             return findMatchesRecursive(rule, nextRemainingClauses, newBindings, nextSupport);
                         } else {
                             return Stream.empty();
                         }
                     });
        }


        private void handleApplyRewriteTask(InferenceTask task) {
            if (!(task.data instanceof RewriteContext(Assertion ruleAssertion, Assertion targetAssertion))) return;

            // Rewrite rule must be a positive, oriented equality
            if (ruleAssertion.isNegated() || !ruleAssertion.isEquality() || !ruleAssertion.isOrientedEquality() || ruleAssertion.kif.size() != 3) {
                return;
            }

            KifTerm lhsPattern = ruleAssertion.kif.get(1);
            KifTerm rhsTemplate = ruleAssertion.kif.get(2);

            // Rewrite the target (handles rewriting inside 'not' via Unifier.rewrite)
            Optional<KifTerm> rewrittenTermOpt = Unifier.rewrite(targetAssertion.kif, lhsPattern, rhsTemplate);

            rewrittenTermOpt.ifPresent(rewrittenTerm -> {
                if (rewrittenTerm instanceof KifList rewrittenList
                        && !rewrittenList.equals(targetAssertion.kif) // Ensure change
                        && !isTrivial(rewrittenList)) {

                    Set<String> support = new HashSet<>(targetAssertion.support());
                    support.add(targetAssertion.id);
                    support.add(ruleAssertion.id);

                    double derivedPri = calculateDerivedPri(support, task.pri);
                    String commonNoteId = findCommonSourceNodeId(support);

                    boolean resultIsNegated = rewrittenList.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
                    boolean resultIsEq = !resultIsNegated && rewrittenList.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
                    boolean resultIsOriented = resultIsEq && rewrittenList.size() == 3 && rewrittenList.get(1).calculateWeight() > rewrittenList.get(2).calculateWeight();

                    var pa = new PotentialAssertion(rewrittenList, derivedPri, support, ruleAssertion.id, resultIsEq, resultIsNegated, resultIsOriented, commonNoteId);
                    submitPotentialAssertion(pa);
                }
            });
        }

        // --- Task Generation ---
        private void generateNewTasks(Assertion newAssertion) {
            // 1. Trigger Rule Matching
            rules.forEach(rule -> {
                for (KifTerm antecedentClause : rule.antecedents) {
                    boolean clauseIsNegated = (antecedentClause instanceof KifList l && l.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());
                    KifTerm pattern = clauseIsNegated ? ((KifList) antecedentClause).get(1) : antecedentClause;
                    KifTerm target = newAssertion.isNegated() ? newAssertion.kif.get(1) : newAssertion.kif;

                    // Match if negation status is compatible and inner terms unify
                    if (clauseIsNegated == newAssertion.isNegated()) {
                        Map<KifVar, KifTerm> bindings = Unifier.unify(pattern, target, Map.of());
                        if (bindings != null) {
                            taskQueue.put(InferenceTask.matchAntecedent(rule, newAssertion, bindings, calculateTaskPri(rule.pri, newAssertion.pri)));
                        }
                    }
                }
            });

            // 2. Trigger Ordered Rewriting
            if (newAssertion.isEquality() && newAssertion.isOrientedEquality() && !newAssertion.isNegated()) {
                // Case A: New assertion IS a positive oriented rewrite rule
                KifTerm lhsPattern = newAssertion.kif.get(1);
                // Find existing assertions (positive or negative) matching the LHS pattern
                kb.findUnifiableAssertions(lhsPattern) // Find things that *might* match LHS
                  .filter(target -> !target.id.equals(newAssertion.id)) // Don't rewrite self
                  // Check if the LHS pattern *actually* matches the target (or target's inner term if negated)
                  .filter(target -> Unifier.match(lhsPattern, target.isNegated() ? target.kif.get(1) : target.kif, Map.of()) != null)
                  .forEach(target -> taskQueue.put(InferenceTask.applyRewrite(newAssertion, target, calculateTaskPri(newAssertion.pri, target.pri))));
            } else {
                // Case B: New assertion is standard fact (pos or neg) or non-oriented equality
                // Find existing positive oriented rewrite rules that might apply TO this new assertion.
                kb.getAllAssertions().stream()
                  .filter(a -> a.isEquality() && a.isOrientedEquality() && !a.isNegated()) // Positive oriented rules
                  .filter(ruleAssertion -> ruleAssertion.kif.size() == 3)
                  .forEach(ruleAssertion -> {
                      KifTerm lhsPattern = ruleAssertion.kif.get(1); // LHS of the rule
                      // Check if the rule's LHS pattern matches the new assertion (or its inner term)
                      KifTerm targetTerm = newAssertion.isNegated() ? newAssertion.kif.get(1) : newAssertion.kif;
                      if (Unifier.match(lhsPattern, targetTerm, Map.of()) != null) {
                          taskQueue.put(InferenceTask.applyRewrite(ruleAssertion, newAssertion, calculateTaskPri(ruleAssertion.pri, newAssertion.pri)));
                      }
                  });
            }
        }

        private void triggerMatchingForNewRule(Rule newRule) {
            System.out.println("Triggering matching for new rule: " + newRule.id);
            kb.getAllAssertions().forEach(existingAssertion -> {
                for (KifTerm antecedentClause : newRule.antecedents) {
                     boolean clauseIsNegated = (antecedentClause instanceof KifList l && l.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());
                     KifTerm pattern = clauseIsNegated ? ((KifList) antecedentClause).get(1) : antecedentClause;
                     KifTerm target = existingAssertion.isNegated() ? existingAssertion.kif.get(1) : existingAssertion.kif;

                     if (clauseIsNegated == existingAssertion.isNegated()) {
                         Map<KifVar, KifTerm> bindings = Unifier.unify(pattern, target, Map.of());
                         if (bindings != null) {
                             taskQueue.put(InferenceTask.matchAntecedent(newRule, existingAssertion, bindings, calculateTaskPri(newRule.pri, existingAssertion.pri)));
                         }
                     }
                }
            });
        }

        // --- Helpers ---
        private String generateFactId(PotentialAssertion pa) {
            String prefix = pa.isEquality ? ID_PREFIX_FACT + "-eq" : ID_PREFIX_FACT;
            if (pa.isNegated) prefix += "-not";
            return prefix + "-" + idGenerator.get();
        }

        private double calculateDerivedPri(Set<String> supportIds, double basePri) {
            if (supportIds.isEmpty()) return basePri;
            double minSupportPri = supportIds.stream()
                                             .map(kb::getAssertion)
                                             .flatMap(Optional::stream)
                                             .mapToDouble(Assertion::pri)
                                             .min()
                                             .orElse(basePri);
            return minSupportPri * DERIVED_PRIORITY_DECAY;
        }

        private double calculateTaskPri(double p1, double p2) { return (p1 + p2) / 2.0; }

        @Nullable
        private String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null;
            boolean firstNoteIdFound = false;
            Set<String> visited = new HashSet<>();
            Queue<String> toCheck = new LinkedList<>(supportIds);

            while (!toCheck.isEmpty()) {
                String currentId = toCheck.poll();
                if (currentId == null || !visited.add(currentId)) continue;

                Optional<Assertion> assertionOpt = kb.getAssertion(currentId);
                if (assertionOpt.isPresent()) {
                    Assertion assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (!firstNoteIdFound) {
                            commonId = assertion.sourceNoteId();
                            firstNoteIdFound = true;
                        } else if (!commonId.equals(assertion.sourceNoteId())) {
                            return null; // Diverged
                        }
                    } else { // No note ID, traverse support
                        assertion.support().forEach(toCheck::offer);
                    }
                }
                // Ignore missing support assertions during trace? Or fail? Currently ignoring.
            }
            return commonId;
        }

        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {}
    }


    // --- KIF Parser (Unchanged from previous version, already handles 'not' as operator) ---
    static class KifParser {
        private final Reader reader;
        private int currentChar = -2;
        private int line = 1;
        private int col = 0;

        private KifParser(Reader reader) { this.reader = reader; }

        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var stringReader = new StringReader(input.trim())) {
                return new KifParser(stringReader).parseTopLevel();
            } catch (IOException e) {
                throw new ParseException("Internal Read error: " + e.getMessage());
            }
        }

        private List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>();
            consumeWhitespaceAndComments();
            while (peek() != -1) {
                terms.add(parseTerm());
                consumeWhitespaceAndComments();
            }
            return Collections.unmodifiableList(terms);
        }

        private KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments();
            return switch (peek()) {
                case -1 -> throw createParseException("Unexpected EOF while expecting a term");
                case '(' -> parseList();
                case '"' -> parseQuotedString();
                case '?' -> parseVariable();
                default -> parseAtom();
            };
        }

        private KifList parseList() throws IOException, ParseException {
            consumeChar('(');
            List<KifTerm> terms = new ArrayList<>();
            while (true) {
                consumeWhitespaceAndComments();
                int nextChar = peek();
                if (nextChar == ')') {
                    consumeChar(')');
                    return new KifList(terms);
                }
                if (nextChar == -1) throw createParseException("Unmatched parenthesis - reached EOF");
                terms.add(parseTerm());
            }
        }

        private KifVar parseVariable() throws IOException, ParseException {
            consumeChar('?');
            var nameBuilder = new StringBuilder("?");
            if (!isValidAtomChar(peek(), true)) throw createParseException("Variable name expected after '?'");
            while (isValidAtomChar(peek(), false)) {
                nameBuilder.append((char) consumeChar());
            }
            if (nameBuilder.length() == 1) throw createParseException("Empty variable name (only '?')");
            return new KifVar(nameBuilder.toString());
        }

        private KifAtom parseAtom() throws IOException, ParseException {
            var atomBuilder = new StringBuilder();
            if (!isValidAtomChar(peek(), true)) throw createParseException("Invalid character for start of atom");
            while (isValidAtomChar(peek(), false)) {
                atomBuilder.append((char) consumeChar());
            }
            if (atomBuilder.isEmpty()) throw createParseException("Empty atom encountered unexpectedly");
            return new KifAtom(atomBuilder.toString());
        }

        private boolean isValidAtomChar(int c, boolean isFirstChar) {
            return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';';
        }

        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                int c = consumeChar();
                if (c == '"') return new KifAtom(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal - reached EOF");
                if (c == '\\') {
                    int next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape character '\\'");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                        case '\\', '"' -> next;
                        default -> {
                            System.err.printf("Warning: Unknown escape sequence '\\%c' at line %d, col %d%n", (char) next, line, col -1);
                            yield next;
                        }
                    });
                } else {
                    sb.append((char) c);
                }
            }
        }

        private int peek() throws IOException {
            if (currentChar == -2) currentChar = reader.read();
            return currentChar;
        }

        private int consumeChar() throws IOException {
            int c = peek();
            if (c != -1) {
                currentChar = -2;
                if (c == '\n') { line++; col = 0; } else { col++; }
            }
            return c;
        }

        private void consumeChar(char expected) throws IOException, ParseException {
            int actual = consumeChar();
            if (actual != expected) {
                String found = (actual == -1) ? "EOF" : "'" + (char) actual + "'";
                throw createParseException("Expected '" + expected + "' but found " + found);
            }
        }

        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                int c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) { consumeChar(); }
                else if (c == ';') {
                    consumeChar();
                    while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                    if (peek() == '\r') consumeChar();
                    if (peek() == '\n') consumeChar();
                } else { break; }
            }
        }

        private ParseException createParseException(String message) {
            return new ParseException(message + " at line " + line + " col " + col);
        }
    }

    static class ParseException extends Exception {
        ParseException(String message) { super(message); }
    }


    // --- Unification and Substitution Logic (Handles Negation) ---
    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 50;

        @Nullable static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            return unifyRecursive(x, y, bindings);
        }

        @Nullable static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            return matchRecursive(pattern, term, bindings);
        }

        static KifTerm subst(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return substFully(term, bindings);
        }

        // Rewriting now handles potential negation
        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            return rewriteRecursive(target, lhsPattern, rhsTemplate);
        }

        // --- Recursive Implementations ---

        @Nullable private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null;
            var xSubst = substFully(x, bindings);
            var ySubst = substFully(y, bindings);

            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true);
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true);

            // Handle negation: (not A) unifies with (not B) if A unifies with B
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly) {
                boolean xIsNeg = lx.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
                boolean yIsNeg = ly.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();

                if (xIsNeg && yIsNeg) { // Both are (not ...)
                    if (lx.size() == 2 && ly.size() == 2) {
                        return unifyRecursive(lx.get(1), ly.get(1), bindings); // Unify inner terms
                    } else return null; // Invalid (not...) structure
                } else if (!xIsNeg && !yIsNeg) { // Both are positive lists
                    return unifyListRecursive(lx, ly, bindings);
                } else {
                    return null; // Mismatched negation
                }
            }
            return null; // Mismatch (e.g., Atom vs List, different Atoms)
        }

        @Nullable private static Map<KifVar, KifTerm> unifyListRecursive(KifList lx, KifList ly, Map<KifVar, KifTerm> bindings) {
            if (lx.size() != ly.size()) return null;
            var currentBindings = bindings;
            for (int i = 0; i < lx.size(); i++) {
                currentBindings = unifyRecursive(lx.get(i), ly.get(i), currentBindings);
                if (currentBindings == null) return null;
            }
            return currentBindings;
        }

        @Nullable private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
             if (bindings == null) return null;
             var patternSubst = substFully(pattern, bindings);

             if (patternSubst instanceof KifVar varP) return bindVariable(varP, term, bindings, false);
             // Don't substitute term here for matching

             // Handle negation
             boolean patternIsNeg = (patternSubst instanceof KifList lp && lp.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());
             boolean termIsNeg = (term instanceof KifList lt && lt.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());

             if (patternIsNeg && termIsNeg) { // Match (not P) with (not T)
                 if (((KifList)patternSubst).size() == 2 && ((KifList)term).size() == 2) {
                     return matchRecursive(((KifList)patternSubst).get(1), ((KifList)term).get(1), bindings); // Match inner terms
                 } else return null; // Invalid structure
             } else if (!patternIsNeg && !termIsNeg) { // Match positive P with positive T
                 if (patternSubst.equals(term)) return bindings;
                 if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                     var currentBindings = bindings;
                     for (int i = 0; i < lp.size(); i++) {
                         currentBindings = matchRecursive(lp.get(i), lt.get(i), currentBindings);
                         if (currentBindings == null) return null;
                     }
                     return currentBindings;
                 }
             }
             // Mismatch in negation or structure
             return null;
        }

        // Substitution needs to handle (not ...) structure
        static KifTerm substFully(KifTerm term, Map<KifVar, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term;
            var current = term;
            for (int depth = 0; depth < MAX_SUBST_DEPTH; depth++) {
                var next = substituteOnce(current, bindings);
                if (next.equals(current)) return next;
                current = next;
                if (!current.containsVariable()) return current;
            }
            System.err.println("Warning: Substitution depth limit (" + MAX_SUBST_DEPTH + ") reached for: " + term.toKifString());
            return current;
        }

        private static KifTerm substituteOnce(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return switch (term) {
                case KifAtom atom -> atom;
                case KifVar var -> bindings.getOrDefault(var, var);
                case KifList list -> {
                    // Handle (not Inner) case specifically
                    if (list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent() && list.size() == 2) {
                         KifTerm inner = list.get(1);
                         KifTerm substInner = substituteOnce(inner, bindings);
                         yield substInner == inner ? list : new KifList(list.get(0), substInner); // Rebuild (not ...) if inner changed
                    } else { // Standard list substitution
                        boolean changed = false;
                        List<KifTerm> newTerms = new ArrayList<>(list.size());
                        for (KifTerm sub : list.terms()) {
                            KifTerm substSub = substituteOnce(sub, bindings);
                            if (substSub != sub) changed = true;
                            newTerms.add(substSub);
                        }
                        yield changed ? new KifList(newTerms) : list;
                    }
                }
            };
        }

        @Nullable private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean doOccursCheck) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var)) {
                KifTerm existingBinding = bindings.get(var);
                return doOccursCheck ? unifyRecursive(existingBinding, value, bindings)
                                     : matchRecursive(existingBinding, value, bindings);
            }
            var finalValue = substFully(value, bindings);
            if (doOccursCheck && occursCheckRecursive(var, finalValue, bindings)) {
                return null;
            }
            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheckRecursive(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings) {
            var substTerm = substFully(term, bindings);
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                // Check inside (not ...) as well
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheckRecursive(var, t, bindings));
                case KifAtom ignored -> false;
            };
        }

        // Rewriting handles target being (not ...)
        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            // 1. Try matching pattern at the current level (handles positive targets)
            Map<KifVar, KifTerm> matchBindings = match(lhsPattern, target, Map.of());
            if (matchBindings != null) {
                return Optional.of(substFully(rhsTemplate, matchBindings));
            }

            // 2. If target is (not Inner), try rewriting Inner
            if (target instanceof KifList targetList && targetList.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent() && targetList.size() == 2) {
                 KifTerm innerTarget = targetList.get(1);
                 Optional<KifTerm> rewrittenInnerOpt = rewriteRecursive(innerTarget, lhsPattern, rhsTemplate);
                 // If inner term was rewritten, return (not <rewrittenInner>)
                 return rewrittenInnerOpt.map(rewrittenInner -> new KifList(targetList.get(0), rewrittenInner));
            }

            // 3. If target is a positive list, try rewriting subterms
            if (target instanceof KifList targetList && !targetList.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                boolean changed = false;
                List<KifTerm> newSubTerms = new ArrayList<>(targetList.size());
                for (KifTerm subTerm : targetList.terms()) {
                    Optional<KifTerm> rewrittenSubOpt = rewriteRecursive(subTerm, lhsPattern, rhsTemplate);
                    if (rewrittenSubOpt.isPresent()) {
                        changed = true;
                        newSubTerms.add(rewrittenSubOpt.get());
                    } else {
                        newSubTerms.add(subTerm);
                    }
                }
                return changed ? Optional.of(new KifList(newSubTerms)) : Optional.empty();
            }

            // 4. Atom/Var or non-matching structure
            return Optional.empty();
        }
    }


    // --- Swing UI Inner Class (Largely unchanged, minor updates for Negation display if needed) ---
    // Note: UI doesn't explicitly handle negation differently, it just displays the KIF string.
    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing...");
        final JTextArea derivationView = new JTextArea();
        final Map<String, String> derivationTextCache = new ConcurrentHashMap<>();
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JButton addButton = new JButton("Add");
        private final JButton removeButton = new JButton("Remove");
        private final JButton analyzeButton = new JButton("Analyze");
        private final JButton pauseResumeButton = new JButton("Pause");
        private final JButton clearAllButton = new JButton("Clear All");
        private CogNote reasoner;
        private Note currentNote = null;

        public SwingUI(@Nullable CogNote reasoner) {
            super("Cognote - Concurrent KIF Reasoner");
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
                    saveCurrentNoteText();
                    if (SwingUI.this.reasoner != null) {
                        SwingUI.this.reasoner.stopReasoner();
                    }
                    dispose();
                    System.exit(0);
                }
            });
            updateUIForSelection();
        }

        void setReasoner(CogNote reasoner) {
            this.reasoner = reasoner;
            updateUIForSelection();
        }

        private void setupFonts() {
            Stream.of(noteList, noteEditor, addButton, removeButton, analyzeButton, pauseResumeButton, clearAllButton, statusLabel)
                  .forEach(c -> c.setFont(UI_DEFAULT_FONT));
            derivationView.setFont(MONOSPACED_FONT);
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
            statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        }

        private void setupLayout() {
            var leftPane = new JScrollPane(noteList);
            leftPane.setPreferredSize(new Dimension(250, 0));
            var editorPane = new JScrollPane(noteEditor);
            var derivationPane = new JScrollPane(derivationView);
            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPane, derivationPane);
            rightSplit.setResizeWeight(0.65);
            var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightSplit);
            mainSplit.setResizeWeight(0.20);
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
            Stream.of(addButton, removeButton, analyzeButton, pauseResumeButton, clearAllButton).forEach(buttonPanel::add);
            var bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(buttonPanel, BorderLayout.WEST);
            bottomPanel.add(statusLabel, BorderLayout.CENTER);
            bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            getContentPane().setLayout(new BorderLayout());
            getContentPane().add(mainSplit, BorderLayout.CENTER);
            getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupListeners() {
            addButton.addActionListener(this::addNote);
            removeButton.addActionListener(this::removeNote);
            analyzeButton.addActionListener(this::analyzeNote);
            pauseResumeButton.addActionListener(this::togglePause);
            clearAllButton.addActionListener(this::clearAll);
            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    saveCurrentNoteText();
                    currentNote = noteList.getSelectedValue();
                    updateUIForSelection();
                }
            });
            noteEditor.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent evt) { saveCurrentNoteText(); }
            });
        }

        private void saveCurrentNoteText() {
            if (currentNote != null && noteEditor.isEnabled()) {
                currentNote.text = noteEditor.getText();
            }
        }

        private void updateUIForSelection() {
            boolean noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected);
            removeButton.setEnabled(noteSelected);
            analyzeButton.setEnabled(noteSelected);
            pauseResumeButton.setEnabled(reasoner != null);
            clearAllButton.setEnabled(reasoner != null);

            if (noteSelected) {
                noteEditor.setText(currentNote.text);
                noteEditor.setCaretPosition(0);
                displayCachedDerivations(currentNote);
                setTitle("Cognote - " + currentNote.title);
            } else {
                noteEditor.setText("");
                derivationView.setText("");
                setTitle("Cognote - Concurrent KIF Reasoner");
            }
            if (reasoner != null) {
                pauseResumeButton.setText(reasoner.isPaused() ? "Resume" : "Pause");
            }
            if (noteSelected) noteEditor.requestFocusInWindow();
        }

        private void addNote(ActionEvent e) {
            String title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) {
                var newNote = new Note(generateId(ID_PREFIX_NOTE), title.trim(), "");
                noteListModel.addElement(newNote);
                noteList.setSelectedValue(newNote, true);
                noteEditor.requestFocusInWindow();
            }
        }

        private void removeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Remove note '" + currentNote.title + "' and retract all associated assertions (including derived)?",
                    "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                statusLabel.setText("Status: Removing '" + currentNote.title + "'...");
                setButtonsEnabled(false);
                var noteToRemove = currentNote;
                reasoner.processRetractionByNoteIdInput(noteToRemove.id, "UI-Remove");
                derivationTextCache.remove(noteToRemove.id);
                SwingUtilities.invokeLater(() -> {
                    int idx = noteList.getSelectedIndex();
                    noteListModel.removeElement(noteToRemove);
                    if (!noteListModel.isEmpty()) {
                        noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
                    } else {
                        currentNote = null;
                        updateUIForSelection();
                    }
                    setButtonsEnabled(true);
                    statusLabel.setText("Status: Removed '" + noteToRemove.title + "'.");
                });
            }
        }

        private void analyzeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            reasoner.reasonerStatus = "Analyzing Note: " + currentNote.title;
            reasoner.updateUIStatus();
            setButtonsEnabled(false);
            saveCurrentNoteText();
            final var noteToAnalyze = currentNote;
            final var noteTextToAnalyze = noteToAnalyze.text;
            derivationTextCache.remove(noteToAnalyze.id);
            derivationView.setText("Analyzing Note '" + noteToAnalyze.title + "' via LLM...\nRetracting previous assertions...\n--------------------\n");
            derivationView.setCaretPosition(0);
            reasoner.processRetractionByNoteIdInput(noteToAnalyze.id, "UI-Analyze-Retract");
            reasoner.getKifFromLlmAsync(noteTextToAnalyze, noteToAnalyze.id)
                .thenAcceptAsync(kifString -> processLlmResponse(kifString, noteToAnalyze), SwingUtilities::invokeLater)
                .exceptionallyAsync(ex -> { handleLlmFailure(ex, noteToAnalyze); return null; }, SwingUtilities::invokeLater);
        }

        private void processLlmResponse(String kifString, Note analyzedNote) {
            if (currentNote != analyzedNote) { setButtonsEnabled(true); return; }
            try {
                var terms = KifParser.parseKif(kifString);
                int submitted = 0, skipped = 0;
                var log = new StringBuilder("LLM KIF Analysis for: " + analyzedNote.title + "\n--------------------\n");
                for (var term : terms) {
                    if (term instanceof KifList list && !list.terms.isEmpty()) {
                        var processedTerm = groundLlmTerm(list, analyzedNote);
                        if (processedTerm instanceof KifList pList && !pList.terms.isEmpty() && !isTrivial(pList)) {
                            boolean isNegated = pList.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
                            if (isNegated && pList.size() != 2) {
                                log.append("Skipped (invalid 'not' structure): ").append(pList.toKifString()).append("\n");
                                skipped++;
                                continue;
                            }
                            boolean isEq = !isNegated && pList.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
                            int weight = pList.calculateWeight();
                            double pri = LLM_ASSERTION_BASE_PRIORITY / (1.0 + weight);
                            boolean isOriented = isEq && pList.size() == 3 && pList.get(1).calculateWeight() > pList.get(2).calculateWeight();
                            var pa = new PotentialAssertion(pList, pri, Set.of(), "UI-LLM", isEq, isNegated, isOriented, analyzedNote.id);
                            reasoner.submitPotentialAssertion(pa);
                            log.append("Submitted: ").append(pList.toKifString()).append("\n");
                            submitted++;
                        } else {
                            log.append("Skipped (trivial/non-list/empty after grounding): ").append(list.toKifString()).append("\n");
                            skipped++;
                        }
                    } else {
                        log.append("Skipped (non-list/empty term): ").append(term.toKifString()).append("\n");
                        skipped++;
                    }
                }
                reasoner.reasonerStatus = String.format("Analyzed '%s': %d submitted, %d skipped", analyzedNote.title, submitted, skipped);
                String initialLog = log.toString() + "\n-- Derivations Follow --\n";
                derivationTextCache.put(analyzedNote.id, initialLog);
                derivationView.setText(initialLog);
                derivationView.setCaretPosition(0);
            } catch (ParseException pe) {
                reasoner.reasonerStatus = "KIF Parse Error from LLM";
                System.err.println("KIF Parse Error: " + pe.getMessage() + "\nInput:\n" + kifString);
                JOptionPane.showMessageDialog(this, "Could not parse KIF from LLM:\n" + pe.getMessage(), "KIF Parse Error", JOptionPane.ERROR_MESSAGE);
                derivationView.setText("Error parsing KIF from LLM.\n--------------------\n" + kifString);
            } catch (Exception ex) {
                reasoner.reasonerStatus = "Error processing LLM response";
                System.err.println("Error processing LLM KIF: " + ex);
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error processing LLM response:\n" + ex.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE);
            } finally {
                setButtonsEnabled(true);
                reasoner.updateUIStatus();
            }
        }

        private void handleLlmFailure(Throwable ex, Note analyzedNote) {
            if (currentNote != analyzedNote) { setButtonsEnabled(true); return; }
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            reasoner.reasonerStatus = "LLM Analysis Failed";
            System.err.println("LLM call failed for note '" + analyzedNote.title + "': " + cause.getMessage());
            cause.printStackTrace();
            JOptionPane.showMessageDialog(this, "LLM communication failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
            derivationView.setText("LLM Analysis Failed for '" + analyzedNote.title + "'.\n--------------------\n" + cause.getMessage());
            setButtonsEnabled(true);
            reasoner.updateUIStatus();
        }

        private KifTerm groundLlmTerm(KifTerm term, Note note) {
            var variables = term.getVariables();
            if (variables.isEmpty()) return term;
            Map<KifVar, KifTerm> groundingMap = new HashMap<>();
            var notePrefix = ID_PREFIX_ENTITY + note.id + "_";
            variables.forEach(var -> {
                String safeVarName = var.name().substring(1).replaceAll("[^a-zA-Z0-9_]", "");
                groundingMap.put(var, new KifAtom(notePrefix + safeVarName));
            });
            return Unifier.subst(term, groundingMap);
        }

        private void togglePause(ActionEvent e) {
            if (reasoner == null) return;
            boolean pausing = !reasoner.isPaused();
            reasoner.setPaused(pausing);
            pauseResumeButton.setText(pausing ? "Resume" : "Pause");
        }

        private void clearAll(ActionEvent e) {
            if (reasoner == null) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Clear all notes, assertions, and rules? This cannot be undone.",
                    "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                reasoner.clearAllKnowledge();
                SwingUtilities.invokeLater(() -> {
                    noteListModel.clear();
                    derivationTextCache.clear();
                    currentNote = null;
                    updateUIForSelection();
                    statusLabel.setText("Status: Knowledge cleared.");
                });
            }
        }

        public void handleReasonerCallback(String type, Assertion assertion) {
            SwingUtilities.invokeLater(() -> {
                String noteId = assertion.sourceNoteId();
                if (noteId != null) {
                    updateDerivationCacheForNote(noteId, type, assertion);
                    if (currentNote != null && noteId.equals(currentNote.id)) {
                        derivationView.setText(derivationTextCache.getOrDefault(noteId, ""));
                    }
                } else if (CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) {
                     markAssertionRemovedInAllCaches(assertion, type);
                     if (currentNote != null && derivationTextCache.containsKey(currentNote.id)) {
                         derivationView.setText(derivationTextCache.get(currentNote.id));
                     }
                }
            });
        }

        private void updateDerivationCacheForNote(String noteId, String type, Assertion assertion) {
            String lineSuffix = String.format(" [%s]", assertion.id);
            // Use assertion.toKifString() which correctly formats (not ...)
            String fullLine = String.format("Prio=%.3f %s%s", assertion.pri, assertion.toKifString(), lineSuffix);
            String header = "Derivations for note: " + getNoteTitleById(noteId) + "\n--------------------\n";

            derivationTextCache.compute(noteId, (id, currentText) -> {
                String text = (currentText == null) ? header : currentText;
                String newText = text;
                switch (type) {
                    case CALLBACK_ASSERT_ADDED, CALLBACK_ASSERT_INPUT -> {
                        if (!text.contains(lineSuffix)) newText = text + fullLine + "\n";
                    }
                    case CALLBACK_ASSERT_RETRACTED, CALLBACK_EVICT -> {
                        newText = text.lines()
                            .map(line -> (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#"))
                                        ? "# " + type.toUpperCase() + ": " + line : line)
                            .collect(Collectors.joining("\n")) + "\n";
                    }
                }
                return newText;
            });
        }

        private void markAssertionRemovedInAllCaches(Assertion assertion, String type) {
             String lineSuffix = String.format(" [%s]", assertion.id);
             String typeMarker = "# " + type.toUpperCase() + ": ";
             derivationTextCache.replaceAll((noteId, text) ->
                 text.lines()
                     .map(line -> (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#"))
                                 ? typeMarker + line : line)
                     .collect(Collectors.joining("\n")) + "\n"
             );
        }

        private void displayCachedDerivations(Note note) {
            if (note == null) { derivationView.setText(""); return; }
            String header = "Derivations for: " + note.title + "\n--------------------\n";
            derivationView.setText(derivationTextCache.getOrDefault(note.id, header + "(No derivations cached or generated yet)\n"));
            derivationView.setCaretPosition(0);
        }

        private String getNoteTitleById(String noteId) {
            for (int i = 0; i < noteListModel.size(); i++) {
                Note note = noteListModel.getElementAt(i);
                if (note.id.equals(noteId)) return note.title;
            }
            return noteId;
        }

        private void setButtonsEnabled(boolean enabled) {
            addButton.setEnabled(enabled);
            boolean noteSelected = (currentNote != null);
            removeButton.setEnabled(enabled && noteSelected);
            analyzeButton.setEnabled(enabled && noteSelected);
            pauseResumeButton.setEnabled(enabled && reasoner != null);
            clearAllButton.setEnabled(enabled && reasoner != null);
            if (enabled && reasoner != null) {
                pauseResumeButton.setText(reasoner.isPaused() ? "Resume" : "Pause");
            }
        }

        static class Note {
            final String id; String title; String text;
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
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setBorder(new EmptyBorder(5, 10, 5, 10));
                label.setFont(UI_DEFAULT_FONT);
                return label;
            }
        }
    }
}
