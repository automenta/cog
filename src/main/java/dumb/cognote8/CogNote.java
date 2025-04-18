package dumb.cognote8;

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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNullElse;

/**
 * A priority-driven, concurrent forward-chaining SUMO KIF reasoner
 * using Path Indexing, Ordered Rewriting, and Forward Subsumption.
 * Driven by dynamic knowledge base changes via WebSockets, with callback support,
 * integrated with a Swing UI for Note->KIF distillation via LLM.
 * <p>
 * Delivers a single, consolidated, refactored, and corrected Java file.
 */
public class CogNote extends WebSocketServer {

    // --- Configuration & UI Styling ---
    private static final int UI_FONT_SIZE = 16;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);
    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    // --- Constants ---
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range"); // Predicates where (pred x x) is trivial
    // --- Reasoner Parameters ---
    private final int maxKbSize;
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl;
    private final String llmModel;
    // --- Core Components ---
    private final KnowledgeBase knowledgeBase;
    private final ReasonerEngine reasonerEngine;
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>();
    // --- Execution & Control ---
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final SwingUI swingUI;
    private final Object pauseLock = new Object();
    private volatile String reasonerStatus = "Idle";
    private volatile boolean running = true;
    private volatile boolean paused = false;

    // --- Constructor ---
    public CogNote(int port, int maxKbSize, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.maxKbSize = Math.max(10, maxKbSize);
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = requireNonNullElse(llmUrl, "http://localhost:11434/api/chat");
        this.llmModel = requireNonNullElse(llmModel, "llamablit"); // Default to llama3
        this.swingUI = Objects.requireNonNull(ui, "SwingUI cannot be null");
        this.knowledgeBase = new KnowledgeBase(this.maxKbSize, this::invokeEvictionCallbacks);
        this.reasonerEngine = new ReasonerEngine(knowledgeBase, () -> CogNote.generateId(""), this::invokeCallbacks);
        System.out.printf("Reasoner config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s%n",
                port, this.maxKbSize, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel);
    }

    // --- Main Method ---
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

                server.startReasoner(); // Start engine and WS server *before* loading files

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
        System.err.println("""
                Usage: java dumb.cognote7.CogNote [options]
                Options:
                  -p, --port <port>           WebSocket server port (default: 8887)
                  -k, --kb-size <maxKbSize>   Max KB assertion count (default: 65536)
                  -r, --rules <rulesFile>     Path to file with initial KIF rules/facts
                  --llm-url <url>             URL for the LLM API (default: http://localhost:11434/api/chat)
                  --llm-model <model>         LLM model name (default: llama3)
                  --broadcast-input           Broadcast input assertions via WebSocket"""
        );
        System.exit(1);
    }

    static String generateId(String prefix) {
        return prefix + "-" + idCounter.incrementAndGet();
    }

    private static boolean isTrivial(KifList list) {
        if (list.size() < 3) return false; // Need at least (op arg1 arg2)
        var arg1 = list.get(1);
        var arg2 = list.get(2);
        if (!arg1.equals(arg2)) return false; // Arguments must be identical

        return list.getOperator()
                .map(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals("="))
                .orElse(false); // Only check if operator is present and in the set or is '='
    }

    private static void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
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

    static BooleanExtensions wrap(boolean b) {
        return () -> b;
    }

    static Optional<Boolean> wrapOpt(boolean b) {
        return Optional.of(b);
    } // Example usage

    // --- WebSocket Communication ---
    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        var message = switch (type) {
            case "assert-added" -> String.format("assert-derived %.4f %s", assertion.priority(), kifString);
            case "assert-input" -> String.format("assert-input %.4f %s", assertion.priority(), kifString);
            case "assert-retracted" -> String.format("retract %s", assertion.id());
            case "evict" -> String.format("evict %s", assertion.id());
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
                            System.err.printf("File Parse Error (line ~%d): %s near '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), 50)));
                        } catch (Exception e) {
                            System.err.printf("File Processing Error (line ~%d): %s for '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), 50)));
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
                        case "=>", "<=>" -> {
                            try {
                                var rule = Rule.parseRule(generateId("rule"), list, 1.0);
                                reasonerEngine.addRule(rule);
                                if ("<=>".equals(opOpt.get())) {
                                    var reverseList = new KifList(new KifAtom("=>"), list.get(2), list.get(1));
                                    var reverseRule = Rule.parseRule(generateId("rule"), reverseList, 1.0);
                                    reasonerEngine.addRule(reverseRule);
                                }
                            } catch (IllegalArgumentException e) {
                                System.err.println("Invalid rule format ignored: " + list.toKifString() + " | Error: " + e.getMessage());
                            }
                        }
                        case "exists" -> handleExists(list, sourceId);
                        case "forall" -> handleForall(list, sourceId);
                        case "=" -> handleEqualityInput(list, sourceId);
                        default -> handleStandardAssertionInput(list, sourceId); // Handle standard assertion
                    }
                } else { // List doesn't start with operator -> Treat as ground fact
                    handleStandardAssertionInput(list, sourceId);
                }
            }
            case KifList ignored -> { /* Ignore empty list */ }
            default ->
                    System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
        }
    }

    private void handleEqualityInput(KifList list, String sourceId) {
        if (list.size() != 3) {
            System.err.println("Invalid equality format ignored (must have 2 arguments): " + list.toKifString());
            return;
        }
        if (isTrivial(list)) {
            System.err.println("Warning: Ignoring trivial equality: " + list.toKifString());
            return;
        }
        var lhs = list.get(1);
        var rhs = list.get(2);
        var weight = list.calculateWeight();
        var priority = 10.0 / (1.0 + weight);
        boolean isOriented = lhs.calculateWeight() > rhs.calculateWeight();
        var pa = new PotentialAssertion(list, priority, Set.of(), sourceId, true, isOriented, null);
        reasonerEngine.submitPotentialAssertion(pa);
    }

    private void handleStandardAssertionInput(KifList list, String sourceId) {
        if (list.containsVariable()) {
            System.err.println("Warning: Non-ground assertion input ignored: " + list.toKifString());
        } else if (isTrivial(list)) {
            System.err.println("Warning: Ignoring trivial assertion: " + list.toKifString());
        } else {
            var weight = list.calculateWeight();
            var priority = 10.0 / (1.0 + weight);
            var pa = new PotentialAssertion(list, priority, Set.of(), sourceId, false, false, null);
            reasonerEngine.submitPotentialAssertion(pa);
        }
    }

    // --- Quantifier Handling (Simplified) ---
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

        Map<KifVar, KifTerm> skolemBindings = variables.stream().collect(Collectors.toUnmodifiableMap(
                v -> v,
                v -> new KifAtom("skolem_" + v.name().substring(1) + "_" + idCounter.incrementAndGet())
        ));
        var skolemizedBody = Unifier.substitute(body, skolemBindings);
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);
        queueExpressionFromSource(skolemizedBody, sourceId + "-skolemized");
    }

    private void handleForall(KifList forallExpr, String sourceId) {
        if (forallExpr.size() == 3 && forallExpr.get(2) instanceof KifList bodyList
                && bodyList.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent()) {
            System.out.println("Interpreting 'forall ... (" + bodyList.getOperator().get() + " ...)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
            try {
                var rule = Rule.parseRule(generateId("rule"), bodyList, 1.0);
                reasonerEngine.addRule(rule);
                if ("<=>".equals(bodyList.getOperator().get())) {
                    var reverseList = new KifList(new KifAtom("=>"), bodyList.get(2), bodyList.get(1));
                    var reverseRule = Rule.parseRule(generateId("rule"), reverseList, 1.0);
                    reasonerEngine.addRule(reverseRule);
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid rule format ignored: " + bodyList.toKifString() + " | Error: " + e.getMessage());
            }
        } else {
            System.err.println("Warning: Ignoring complex 'forall' (only handles 'forall vars (=> ant con)' or '<=>'): " + forallExpr.toKifString());
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
            start(); // Start WebSocket server
        } catch (IllegalStateException e) {
            System.err.println("WebSocket server already started or failed to start: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage());
            stopReasoner();
        }
        System.out.println("Reasoner started.");
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
            stop(1000);
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

    public boolean isPaused() {
        return this.paused;
    }

    public void setPaused(boolean pause) {
        if (this.paused == pause) return;
        this.paused = pause;
        reasonerEngine.setPaused(pause);
        if (!pause) synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        reasonerStatus = pause ? "Paused" : "Running";
        updateUIStatus();
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        List<Assertion> toRetract = knowledgeBase.getAllAssertions();
        knowledgeBase.clear();
        reasonerEngine.clear();
        noteIdToAssertionIds.clear();
        SwingUtilities.invokeLater(swingUI.derivationTextCache::clear);

        toRetract.forEach(a -> invokeCallbacks("assert-retracted", a));

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
        System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + requireNonNullElse(reason, "N/A"));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        var addr = (conn != null && conn.getRemoteSocketAddress() != null) ? conn.getRemoteSocketAddress().toString() : "server";
        if (ex instanceof IOException && ex.getMessage() != null && (ex.getMessage().contains("Socket closed") || ex.getMessage().contains("Connection reset") || ex.getMessage().contains("Broken pipe"))) {
            System.err.println("WS Network Error from " + addr + ": " + ex.getMessage());
        } else {
            System.err.println("WS Error from " + addr + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void onStart() {
        System.out.println("Reasoner WebSocket listener active on port " + getPort() + ".");
        setConnectionLostTimeout(100);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        var trimmed = message.trim();
        if (trimmed.isEmpty()) return;
        var sourceId = conn.getRemoteSocketAddress().toString();
        var lowerTrimmed = trimmed.toLowerCase();

        if (lowerTrimmed.startsWith("retract ")) {
            var idToRetract = trimmed.substring(8).trim();
            if (!idToRetract.isEmpty()) processRetractionByIdInput(idToRetract, sourceId);
            else System.err.println("WS Retract Error from " + sourceId + ": Missing assertion ID.");
        } else if (lowerTrimmed.startsWith("register_callback ")) {
            System.err.println("WS Callback registration not implemented yet.");
        } else {
            try {
                var terms = KifParser.parseKif(trimmed);
                if (terms.isEmpty())
                    throw new ParseException("Received non-KIF message or empty KIF: " + trimmed.substring(0, Math.min(trimmed.length(), 100)));
                terms.forEach(term -> queueExpressionFromSource(term, sourceId));
            } catch (ParseException | ClassCastException e) {
                System.err.printf("WS Message Parse Error from %s: %s | Original: %s%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), 100)));
            } catch (Exception e) {
                System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // --- Direct Input Processing Methods ---
    public void submitPotentialAssertion(PotentialAssertion pa) {
        if (pa == null) return;
        if (isTrivial(pa.kif())) { // Check triviality before submitting
            System.err.println("Warning: Ignoring submission of trivial assertion: " + pa.kif().toKifString());
            return;
        }
        reasonerEngine.submitPotentialAssertion(pa);
        if (pa.sourceNoteId() != null && !pa.isEquality() && !pa.kif().containsVariable()) {
            var tempId = generateId("input");
            var inputAssertion = new Assertion(tempId, pa.kif(), pa.priority(), System.currentTimeMillis(), pa.sourceNoteId(), Set.of(), pa.isEquality(), pa.isOrientedEquality());
            invokeCallbacks("assert-input", inputAssertion);
        }
    }

    public void submitRule(Rule rule) {
        reasonerEngine.addRule(rule);
    }

    public void processRetractionByIdInput(String assertionId, String sourceId) {
        Assertion removed = knowledgeBase.retractAssertion(assertionId);
        if (removed != null) {
            System.out.printf("Retracted [%s] by ID from %s: %s%n", assertionId, sourceId, removed.toKifString());
            invokeCallbacks("assert-retracted", removed);
            if (removed.sourceNoteId() != null) {
                noteIdToAssertionIds.computeIfPresent(removed.sourceNoteId(), (k, set) -> {
                    set.remove(assertionId);
                    return set.isEmpty() ? null : set;
                });
            }
            // TODO: Implement cascading retraction if needed.
        } else {
            System.out.printf("Retraction by ID %s from %s failed: ID not found.%n", assertionId, sourceId);
        }
    }

    public void processRetractionByNoteIdInput(String noteId, String sourceId) {
        var idsToRetract = noteIdToAssertionIds.remove(noteId);
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.printf("Retracting %d assertions for note %s from %s.%n", idsToRetract.size(), noteId, sourceId);
            new HashSet<>(idsToRetract).forEach(id -> processRetractionByIdInput(id, sourceId + "-noteRetract"));
        } else {
            System.out.printf("Retraction by Note ID %s from %s failed: No associated assertions.%n", noteId, sourceId);
        }
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
    }

    public void registerCallback(KifTerm pattern, KifCallback callback) {
        callbackRegistrations.add(new CallbackRegistration(pattern, callback));
        System.out.println("Registered external callback for pattern: " + pattern.toKifString());
    }

    // --- Callback Handling ---
    void invokeCallbacks(String type, Assertion assertion) {
        // Link assertion to note ID *after* commit only if sourceNoteId is present
        if (assertion.sourceNoteId() != null) {
            noteIdToAssertionIds.computeIfAbsent(assertion.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(assertion.id());
        }

        boolean shouldBroadcast = switch (type) {
            case "assert-added", "assert-retracted", "evict" -> true;
            case "assert-input" -> broadcastInputAssertions;
            default -> false;
        };
        if (shouldBroadcast) broadcastMessage(type, assertion);

        if (swingUI != null && swingUI.isDisplayable()) swingUI.handleReasonerCallback(type, assertion);

        callbackRegistrations.forEach(reg -> {
            try {
                Map<KifVar, KifTerm> bindings = Unifier.match(reg.pattern(), assertion.kif, Map.of());
                if (bindings != null) reg.callback().onMatch(type, assertion, bindings);
            } catch (Exception e) {
                System.err.println("Error executing KIF pattern callback for " + reg.pattern().toKifString() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
        updateUIStatus();
    }

    void invokeEvictionCallbacks(Assertion evictedAssertion) {
        invokeCallbacks("evict", evictedAssertion);
        // Remove from note mapping if evicted
        if (evictedAssertion.sourceNoteId() != null) {
            noteIdToAssertionIds.computeIfPresent(evictedAssertion.sourceNoteId(), (k, set) -> {
                set.remove(evictedAssertion.id());
                return set.isEmpty() ? null : set;
            });
        }
    }

    // --- UI Status Update ---
    private void updateUIStatus() {
        if (swingUI != null && swingUI.isDisplayable()) {
            int kbSize = knowledgeBase.getAssertionCount();
            long taskQueueSize = reasonerEngine.getTaskQueueSize();
            long commitQueueSize = reasonerEngine.getCommitQueueSize();
            int ruleCount = reasonerEngine.getRuleCount();
            final String statusText = String.format("KB: %d/%d | Tasks: %d | Commits: %d | Rules: %d | Status: %s",
                    kbSize, maxKbSize, taskQueueSize, commitQueueSize, ruleCount, reasonerEngine.getCurrentStatus());
            SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
        }
    }

    // --- LLM Interaction ---
    public CompletableFuture<String> getKifFromLlmAsync(String contextPrompt, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var finalPrompt = (!contextPrompt.toLowerCase().contains("kif assertions:") && !contextPrompt.toLowerCase().contains("proposed fact:")) ? """
                    Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax).
                    Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                    Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', attribute relations, etc.
                    Use '=' for equality. Handle variables appropriately (e.g., `?x`) if needed, but prefer ground facts.
                    Avoid trivial assertions like (instance X X) or (= X X).
                    Example: (instance MyComputer Computer), (= (deviceName MyComputer) "HAL")
                    
                    Note:
                    "%s"
                    
                    KIF Assertions:""".formatted(contextPrompt) : contextPrompt;

            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", finalPrompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2));

            var request = HttpRequest.newBuilder(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
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
                            .filter(line -> !line.matches("^\\(\\s*\\)$")) // Filter empty parens
                            .collect(Collectors.joining("\n"));
                    if (cleanedKif.isEmpty() && !kifResult.isBlank())
                        System.err.println("LLM Warning (" + noteId + "): Result contained text but no valid KIF lines found:\n---\n" + kifResult + "\n---");
                    return cleanedKif;
                } else throw new IOException("LLM API request failed: " + response.statusCode() + " " + responseBody);
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
                .or(() -> Optional.ofNullable(jsonResponse.optJSONArray("choices")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(c -> c.optJSONObject("message")).map(m -> m.optString("content", null)))
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

    enum TaskType {MATCH_ANTECEDENT, APPLY_ORDERED_REWRITE}

    // --- KIF Data Structures ---
    sealed interface KifTerm permits KifAtom, KifVar, KifList {
        private static void collectVariablesRecursive(KifTerm term, Set<KifVar> vars) {
            switch (term) {
                case KifVar v -> vars.add(v);
                case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars));
                case KifAtom ignored -> {
                }
            }
        }

        static Set<KifVar> collectVariablesFromSpec(KifTerm varsTerm) {
            return switch (varsTerm) {
                case KifVar v -> Set.of(v);
                case KifList l ->
                        l.terms().stream().filter(KifVar.class::isInstance).map(KifVar.class::cast).collect(Collectors.toUnmodifiableSet());
                default -> {
                    System.err.println("Warning: Invalid variable specification in quantifier: " + varsTerm.toKifString());
                    yield Set.of();
                }
            };
        }

        String toKifString();

        boolean containsVariable();

        Set<KifVar> getVariables();

        int calculateWeight();
    }

    @FunctionalInterface
    interface KifCallback {
        void onMatch(String type, Assertion assertion, Map<KifVar, KifTerm> bindings);
    }

    // --- Utility Extensions ---
    // Helper for boolean conditions in streams/optionals
    interface BooleanExtensions {
        default void ifTrue(Runnable action) {
            if (this.value()) action.run();
        }

        boolean value();
    }

    static final class KifAtom implements KifTerm {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$");
        private final String value;
        private volatile int weight = -1;

        KifAtom(String value) {
            this.value = Objects.requireNonNull(value);
        }

        public String value() {
            return value;
        }

        @Override
        public String toKifString() {
            boolean needsQuotes = value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)
                    || value.chars().anyMatch(c -> "()\";?".indexOf(c) != -1)
                    || !SAFE_ATOM_PATTERN.matcher(value).matches();
            return needsQuotes ? '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : value;
        }

        @Override
        public boolean containsVariable() {
            return false;
        }

        @Override
        public Set<KifVar> getVariables() {
            return Set.of();
        }

        @Override
        public int calculateWeight() {
            if (weight == -1) weight = 1;
            return weight;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof KifAtom a && value.equals(a.value));
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return "KifAtom[" + value + ']';
        }
    }

    static final class KifVar implements KifTerm {
        private final String name;
        private volatile int weight = -1;

        KifVar(String name) {
            Objects.requireNonNull(name);
            if (!name.startsWith("?") || name.length() == 1)
                throw new IllegalArgumentException("Variable name must start with '?' and be non-empty: " + name);
            this.name = name;
        }

        public String name() {
            return name;
        }

        @Override
        public String toKifString() {
            return name;
        }

        @Override
        public boolean containsVariable() {
            return true;
        }

        @Override
        public Set<KifVar> getVariables() {
            return Set.of(this);
        }

        @Override
        public int calculateWeight() {
            if (weight == -1) weight = 1;
            return weight;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof KifVar v && name.equals(v.name));
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return "KifVar[" + name + ']';
        }
    }

    static final class KifList implements KifTerm {
        private final List<KifTerm> terms;
        private volatile Boolean containsVariable;
        private volatile Set<KifVar> variables;
        private volatile int hashCode;
        private volatile String kifString;
        private volatile int weight = -1;

        KifList(KifTerm... terms) {
            this(List.of(terms));
        }

        KifList(List<KifTerm> terms) {
            this.terms = List.copyOf(Objects.requireNonNull(terms));
        }

        public List<KifTerm> terms() {
            return terms;
        }

        KifTerm get(int index) {
            return terms.get(index);
        }

        int size() {
            return terms.size();
        }

        Optional<String> getOperator() {
            return terms.isEmpty() || !(terms.getFirst() instanceof KifAtom atom) ? Optional.empty() : Optional.of(atom.value());
        }

        @Override
        public String toKifString() {
            if (kifString == null)
                kifString = terms.stream().map(KifTerm::toKifString).collect(Collectors.joining(" ", "(", ")"));
            return kifString;
        }

        @Override
        public boolean containsVariable() {
            if (containsVariable == null) containsVariable = terms.stream().anyMatch(KifTerm::containsVariable);
            return containsVariable;
        }

        @Override
        public Set<KifVar> getVariables() {
            if (variables == null) {
                Set<KifVar> vars = new HashSet<>();
                KifTerm.collectVariablesRecursive(this, vars);
                variables = Collections.unmodifiableSet(vars);
            }
            return variables;
        }

        @Override
        public int calculateWeight() {
            if (weight == -1) weight = 1 + terms.stream().mapToInt(KifTerm::calculateWeight).sum();
            return weight;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof KifList l && terms.equals(l.terms));
        }

        @Override
        public int hashCode() {
            if (hashCode == 0) hashCode = terms.hashCode();
            return hashCode;
        }

        @Override
        public String toString() {
            return "KifList[" + terms + ']';
        }
    }

    // --- Reasoner Data Records ---
    record Assertion(String id, KifList kif, double priority, long timestamp, String sourceNoteId, Set<String> support,
                     boolean isEquality, boolean isOrientedEquality) implements Comparable<Assertion> {
        Assertion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
        }

        @Override
        public int compareTo(Assertion other) {
            return Double.compare(this.priority, other.priority);
        } // Min-heap for eviction

        String toKifString() {
            return kif.toKifString();
        }
    }

    record Rule(String id, KifList ruleForm, KifTerm antecedent, KifTerm consequent, double priority,
                List<KifList> antecedents) {
        Rule {
            Objects.requireNonNull(id);
            Objects.requireNonNull(ruleForm);
            Objects.requireNonNull(antecedent);
            Objects.requireNonNull(consequent);
            antecedents = List.copyOf(Objects.requireNonNull(antecedents));
        }

        static Rule parseRule(String id, KifList ruleForm, double priority) throws IllegalArgumentException {
            if (!(ruleForm.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent() && ruleForm.size() == 3))
                throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());
            var antTerm = ruleForm.get(1);
            var conTerm = ruleForm.get(2);

            List<KifList> parsedAntecedents = switch (antTerm) {
                case KifList list when list.getOperator().filter("and"::equals).isPresent() ->
                        list.terms().stream().skip(1).map(t -> {
                            if (t instanceof KifList l) return l;
                            throw new IllegalArgumentException("Elements of 'and' antecedent must be lists: " + t.toKifString());
                        }).toList();
                case KifList list -> List.of(list);
                default ->
                        throw new IllegalArgumentException("Antecedent must be a KIF list or (and list1...): " + antTerm.toKifString());
            };
            validateUnboundVariables(ruleForm, antTerm, conTerm);
            return new Rule(id, ruleForm, antTerm, conTerm, priority, parsedAntecedents);
        }

        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
            var antVars = antecedent.getVariables();
            var conVarsAll = consequent.getVariables();
            var conVarsBoundLocally = getQuantifierBoundVariables(consequent);
            Set<KifVar> conVarsNeedingBinding = new HashSet<>(conVarsAll);
            conVarsNeedingBinding.removeAll(antVars);
            conVarsNeedingBinding.removeAll(conVarsBoundLocally);
            if (!conVarsNeedingBinding.isEmpty() && ruleForm.getOperator().filter("=>"::equals).isPresent())
                System.err.println("Warning: Rule consequent has unbound variables: " + conVarsNeedingBinding.stream().map(KifVar::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKifString());
        }

        private static Set<KifVar> getQuantifierBoundVariables(KifTerm term) {
            Set<KifVar> bound = new HashSet<>();
            collectQuantifierBoundVariablesRecursive(term, bound);
            return Collections.unmodifiableSet(bound);
        }

        private static void collectQuantifierBoundVariablesRecursive(KifTerm term, Set<KifVar> boundVars) {
            switch (term) {
                case KifList list when list.size() == 3 && list.getOperator().filter(op -> op.equals("exists") || op.equals("forall")).isPresent() -> {
                    boundVars.addAll(KifTerm.collectVariablesFromSpec(list.get(1)));
                    collectQuantifierBoundVariablesRecursive(list.get(2), boundVars);
                }
                case KifList list ->
                        list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
                default -> {
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Rule r && ruleForm.equals(r.ruleForm));
        }

        @Override
        public int hashCode() {
            return ruleForm.hashCode();
        }
    }

    record PotentialAssertion(KifList kif, double priority, Set<String> support, String sourceId, boolean isEquality,
                              boolean isOrientedEquality, String sourceNoteId) {
        PotentialAssertion {
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
            Objects.requireNonNull(sourceId);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PotentialAssertion pa && kif.equals(pa.kif);
        }

        @Override
        public int hashCode() {
            return kif.hashCode();
        }
    }

    record InferenceTask(TaskType type, double priority, Object data) implements Comparable<InferenceTask> {
        InferenceTask {
            Objects.requireNonNull(type);
            Objects.requireNonNull(data);
        }

        static InferenceTask matchAntecedent(Rule rule, Assertion trigger, Map<KifVar, KifTerm> bindings, double priority) {
            return new InferenceTask(TaskType.MATCH_ANTECEDENT, priority, new MatchContext(rule, trigger.id(), bindings));
        }

        static InferenceTask applyRewrite(Assertion rule, Assertion target, double priority) {
            return new InferenceTask(TaskType.APPLY_ORDERED_REWRITE, priority, new RewriteContext(rule, target));
        }

        @Override
        public int compareTo(InferenceTask other) {
            return Double.compare(other.priority, this.priority);
        } // Max-heap by priority
    }

    record MatchContext(Rule rule, String triggerAssertionId, Map<KifVar, KifTerm> initialBindings) {
    }

    record RewriteContext(Assertion rewriteRule, Assertion targetAssertion) {
    }

    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
        CallbackRegistration {
            Objects.requireNonNull(pattern);
            Objects.requireNonNull(callback);
        }
    }

    // --- Path Indexing Structures ---
    static class PathNode {
        static final Class<KifVar> VAR_MARKER = KifVar.class;
        final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
        final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet();
        // static final Object LIST_START_MARKER = new Object(); // Potential future enhancement
    }

    static class PathIndex {
        private final PathNode root = new PathNode();

        void add(Assertion assertion) {
            addPathsRecursive(assertion.kif(), assertion.id(), root);
        }

        void remove(Assertion assertion) {
            removePathsRecursive(assertion.kif(), assertion.id(), root);
        }

        void clear() {
            root.children.clear();
            root.assertionIdsHere.clear();
        }

        Set<String> findUnifiable(KifTerm queryTerm) {
            Set<String> c = ConcurrentHashMap.newKeySet();
            findUnifiableRecursive(queryTerm, root, c);
            return c;
        }

        Set<String> findInstances(KifTerm queryPattern) {
            Set<String> c = ConcurrentHashMap.newKeySet();
            findInstancesRecursive(queryPattern, root, c);
            return c;
        }

        Set<String> findGeneralizations(KifTerm queryTerm) {
            Set<String> c = ConcurrentHashMap.newKeySet();
            findGeneralizationsRecursive(queryTerm, root, c);
            return c;
        }

        private void addPathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return;
            currentNode.assertionIdsHere.add(assertionId);
            Object key = switch (term) {
                case KifAtom a -> a.value();
                case KifVar v -> PathNode.VAR_MARKER;
                case KifList l -> lIndex(l);
            };
            var nextNode = currentNode.children.computeIfAbsent(key, k -> new PathNode());
            switch (term) {
                case KifAtom a -> nextNode.assertionIdsHere.add(assertionId); // Add ID at leaf for atoms
                case KifVar v -> nextNode.assertionIdsHere.add(assertionId);   // Add ID at var marker node
                case KifList list ->
                        list.terms().forEach(subTerm -> addPathsRecursive(subTerm, assertionId, nextNode)); // Recurse for list elements into the node keyed by operator
            }
        }

        private boolean removePathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return false;
            currentNode.assertionIdsHere.remove(assertionId);
            Object key = switch (term) {
                case KifAtom a -> a.value();
                case KifVar v -> PathNode.VAR_MARKER;
                case KifList l -> lIndex(l);
            };
            PathNode childNode = currentNode.children.get(key);
            if (childNode != null) {
                boolean childBecameEmpty = false;
                switch (term) {
                    case KifAtom a -> childNode.assertionIdsHere.remove(assertionId); // No deeper recursion for atom
                    case KifVar v -> childNode.assertionIdsHere.remove(assertionId);   // No deeper recursion for var
                    case KifList list -> { // Recurse for list elements
                        boolean allChildrenEmpty = true;
                        for (var subTerm : list.terms()) {
                            if (!removePathsRecursive(subTerm, assertionId, childNode)) allChildrenEmpty = false;
                        }
                        childBecameEmpty = allChildrenEmpty; // Approximation: if all recursive calls returned true
                    }
                }
                // Pruning logic: Remove child if it became empty and has no IDs itself
                if (childBecameEmpty && childNode.assertionIdsHere.isEmpty() && childNode.children.isEmpty()) {
                    currentNode.children.remove(key, childNode);
                }
            }
            return currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
        }

        private Object lIndex(KifList l) {
            Object ll = l.getOperator().orElseGet(null);
            return ll != null ? ll : PathNode.VAR_MARKER;
        }

        private void findUnifiableRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            candidates.addAll(indexNode.assertionIdsHere); // Add all IDs at this level

            switch (queryTerm) {
                case KifAtom queryAtom -> {
                    // Match Atom or Var in index
                    Optional.ofNullable(indexNode.children.get(queryAtom.value())).ifPresent(n -> findUnifiableRecursive(queryAtom, n, candidates));
                    Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(n -> collectAllAssertionIds(n, candidates));
                }
                case KifVar queryVar ->
                        indexNode.children.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates)); // Var matches anything
                case KifList queryList -> {
                    // Match Var in index
                    Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(n -> collectAllAssertionIds(n, candidates));
                    // Match Operator (simplified)
                    queryList.getOperator().flatMap(op -> Optional.ofNullable(indexNode.children.get(op)))
                            .ifPresent(opNode -> {
                                candidates.addAll(opNode.assertionIdsHere); // Add IDs at operator node
                                // TODO: Refine list matching beyond operator
                                // Simplified: Recurse on first argument against opNode children?
                                // if (queryList.size() > 1) findUnifiableRecursive(queryList.get(1), opNode, candidates);
                                // For now, rely on candidates collected at opNode and parent indexNode
                            });
                    // Also consider matching list structure against VAR_MARKER if no operator match? Maybe covered by initial addAll.
                }
            }
        }

        private void findInstancesRecursive(KifTerm queryPattern, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            switch (queryPattern) {
                case KifAtom queryAtom ->
                        Optional.ofNullable(indexNode.children.get(queryAtom.value())).ifPresent(n -> candidates.addAll(n.assertionIdsHere)); // Exact match
                case KifVar queryVar -> collectAllAssertionIds(indexNode, candidates); // Var matches anything below
                case KifList queryList -> {
                    // Match Var marker in index
                    Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(n -> { /* TODO: Need recursive match against structures under varNode */
                        candidates.addAll(n.assertionIdsHere);
                    });
                    // Match Operator (simplified)
                    queryList.getOperator().flatMap(op -> Optional.ofNullable(indexNode.children.get(op)))
                            .ifPresent(opNode -> {
                                candidates.addAll(opNode.assertionIdsHere); // Add IDs at operator node as potential start
                                // TODO: Implement recursive list instance matching
                            });
                }
            }
        }

        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            // Generalizations found at VAR_MARKER nodes
            Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> {
                candidates.addAll(varNode.assertionIdsHere);
                findGeneralizationsRecursive(queryTerm, varNode, candidates);
            });

            switch (queryTerm) {
                case KifAtom queryAtom ->
                        Optional.ofNullable(indexNode.children.get(queryAtom.value())).ifPresent(n -> {
                            candidates.addAll(n.assertionIdsHere); /* No deeper recursion for atom */
                        });
                case KifVar ignored -> {
                } // Should not happen for ground query
                case KifList queryList -> {
                    // Match Operator (simplified)
                    queryList.getOperator().flatMap(op -> Optional.ofNullable(indexNode.children.get(op)))
                            .ifPresent(opNode -> {
                                candidates.addAll(opNode.assertionIdsHere);
                                // TODO: Implement recursive list generalization matching
                            });
                    candidates.addAll(indexNode.assertionIdsHere); // Add current node's IDs (e.g., var matching whole list)
                }
            }
        }

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
        private final PriorityBlockingQueue<Assertion> evictionQueue = new PriorityBlockingQueue<>();
        private final ReadWriteLock kbCommitLock = new ReentrantReadWriteLock();
        private final int maxKbSize;
        private final Consumer<Assertion> evictionCallback;

        KnowledgeBase(int maxKbSize, Consumer<Assertion> evictionCallback) {
            this.maxKbSize = maxKbSize;
            this.evictionCallback = Objects.requireNonNull(evictionCallback);
        }

        Optional<Assertion> commitAssertion(PotentialAssertion pa, String newId, long timestamp) {
            kbCommitLock.writeLock().lock();
            try {
                if (isTrivial(pa.kif())) return Optional.empty(); // Double check triviality inside lock

                var newAssertion = new Assertion(newId, pa.kif(), pa.priority(), timestamp, pa.sourceNoteId(), pa.support(), pa.isEquality(), pa.isOrientedEquality());

                // Check for exact duplicate (using index for efficiency)
                if (!newAssertion.kif().containsVariable() && findExactMatch(newAssertion.kif()) != null)
                    return Optional.empty();

                enforceKbCapacity();
                if (assertionsById.size() >= maxKbSize) {
                    System.err.printf("Warning: KB full (%d/%d), cannot add: %s%n", assertionsById.size(), maxKbSize, pa.kif().toKifString());
                    return Optional.empty();
                }

                if (assertionsById.putIfAbsent(newId, newAssertion) != null) {
                    System.err.println("KB Commit Error: ID collision for " + newId);
                    return Optional.empty();
                }

                pathIndex.add(newAssertion);
                evictionQueue.put(newAssertion);
                return Optional.of(newAssertion);
            } finally {
                kbCommitLock.writeLock().unlock();
            }
        }

        Assertion retractAssertion(String id) {
            kbCommitLock.writeLock().lock();
            try {
                Assertion removed = assertionsById.remove(id);
                if (removed != null) {
                    pathIndex.remove(removed);
                    evictionQueue.remove(removed);
                }
                return removed;
            } finally {
                kbCommitLock.writeLock().unlock();
            }
        }

        Optional<Assertion> getAssertion(String id) {
            kbCommitLock.readLock().lock();
            try {
                return Optional.ofNullable(assertionsById.get(id));
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        boolean isSubsumed(PotentialAssertion pa) {
            kbCommitLock.readLock().lock();
            try {
                Set<String> candidateIds = pathIndex.findGeneralizations(pa.kif());
                if (candidateIds.isEmpty()) return false;
                return candidateIds.stream().map(assertionsById::get).filter(Objects::nonNull).anyMatch(candidate -> Unifier.match(candidate.kif(), pa.kif(), Map.of()) != null);
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) {
            kbCommitLock.readLock().lock();
            try {
                Set<String> ids = new HashSet<>(new ArrayList<>(pathIndex.findUnifiable(queryTerm)));
                return ids.stream().map(assertionsById::get).filter(Objects::nonNull);
            } // Copy IDs before releasing lock
            finally {
                kbCommitLock.readLock().unlock();
            }
        }

        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            kbCommitLock.readLock().lock();
            try {
                Set<String> ids = new HashSet<>(new ArrayList<>(pathIndex.findInstances(queryPattern)));
                return ids.stream().map(assertionsById::get).filter(Objects::nonNull).filter(a -> Unifier.match(queryPattern, a.kif(), Map.of()) != null);
            } // Copy IDs + final check
            finally {
                kbCommitLock.readLock().unlock();
            }
        }

        Assertion findExactMatch(KifList groundKif) {
            kbCommitLock.readLock().lock();
            try {
                Set<String> ids = pathIndex.findInstances(groundKif);
                return ids.stream().map(assertionsById::get).filter(Objects::nonNull).filter(a -> a.kif().equals(groundKif)).findFirst().orElse(null);
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        List<Assertion> getAllAssertions() {
            kbCommitLock.readLock().lock();
            try {
                return List.copyOf(assertionsById.values());
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        int getAssertionCount() {
            return assertionsById.size();
        }

        void clear() {
            kbCommitLock.writeLock().lock();
            try {
                assertionsById.clear();
                pathIndex.clear();
                evictionQueue.clear();
            } finally {
                kbCommitLock.writeLock().unlock();
            }
        }

        private void enforceKbCapacity() {
            while (assertionsById.size() >= maxKbSize && !evictionQueue.isEmpty()) {
                Assertion lowest = evictionQueue.poll();
                if (lowest != null && assertionsById.remove(lowest.id()) != null) { // Remove only if still present
                    pathIndex.remove(lowest);
                    evictionCallback.accept(lowest);
                }
            }
        }
    }

    // --- Reasoner Engine ---
    static class ReasonerEngine {
        private final KnowledgeBase kb;
        private final Supplier<String> idGenerator;
        private final BiConsumer<String, Assertion> callbackNotifier;
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<PotentialAssertion> commitQueue = new LinkedBlockingQueue<>(10000);
        private final PriorityBlockingQueue<InferenceTask> taskQueue = new PriorityBlockingQueue<>(1000); // Uses InferenceTask.compareTo
        private final ExecutorService commitExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "CommitThread"));
        private final ExecutorService inferenceExecutor = Executors.newVirtualThreadPerTaskExecutor();
        private final Object pauseLock = new Object();
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private volatile String currentStatus = "Idle";

        ReasonerEngine(KnowledgeBase kb, Supplier<String> idGenerator, BiConsumer<String, Assertion> notifier) {
            this.kb = kb;
            this.idGenerator = idGenerator;
            this.callbackNotifier = notifier;
        }

        void start() {
            if (running) return;
            running = true;
            paused = false;
            currentStatus = "Starting";
            commitExecutor.submit(this::commitLoop);
            int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            for (int i = 0; i < workerCount; i++) inferenceExecutor.submit(this::inferenceLoop);
            currentStatus = "Running";
            System.out.println("Reasoner Engine started with " + workerCount + " inference workers.");
        }

        void stop() {
            if (!running) return;
            running = false;
            paused = false;
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            currentStatus = "Stopping";
            shutdownExecutor(commitExecutor, "Commit Executor");
            shutdownExecutor(inferenceExecutor, "Inference Executor");
            currentStatus = "Stopped";
            System.out.println("Reasoner Engine stopped.");
        }

        void setPaused(boolean pause) {
            if (this.paused == pause) return;
            this.paused = pause;
            if (!pause) synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            currentStatus = pause ? "Paused" : "Running";
        }

        void clear() {
            rules.clear();
            commitQueue.clear();
            taskQueue.clear();
            System.out.println("Reasoner Engine queues and rules cleared.");
        }

        long getTaskQueueSize() {
            return taskQueue.size();
        }

        long getCommitQueueSize() {
            return commitQueue.size();
        }

        int getRuleCount() {
            return rules.size();
        }

        String getCurrentStatus() {
            return currentStatus;
        }

        void submitPotentialAssertion(PotentialAssertion pa) {
            if (!running) { /* Warning already printed by CogNote if reasoner not started */
                return;
            }
            if (pa == null) return; // Avoid null submissions
            try {
                commitQueue.put(pa);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while submitting potential assertion.");
            }
        }

        void addRule(Rule rule) {
            if (rules.add(rule)) {
                System.out.println("Added rule: " + rule.id() + " " + rule.ruleForm().toKifString());
                triggerMatchingForNewRule(rule);
            }
        }

        boolean removeRule(KifList ruleForm) {
            return rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));
        }

        private void commitLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (pauseLock) {
                        while (paused && running) pauseLock.wait(500);
                    }
                    if (!running) break;
                    PotentialAssertion pa = commitQueue.take();
                    currentStatus = "Committing";
                    if (!isTrivial(pa.kif()) && !kb.isSubsumed(pa)) { // Check triviality and subsumption
                        kb.commitAssertion(pa, generateFactId(pa), System.currentTimeMillis()).ifPresent(committed -> {
                            callbackNotifier.accept("assert-added", committed);
                            generateNewTasks(committed);
                        });
                    }
                    currentStatus = "Idle (Commit)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Commit thread interrupted.");
                } catch (Exception e) {
                    System.err.println("Error in commit loop: " + e.getMessage());
                    e.printStackTrace();
                    currentStatus = "Error (Commit)";
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            System.out.println("Commit thread finished.");
        }

        private void inferenceLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (pauseLock) {
                        while (paused && running) pauseLock.wait(500);
                    }
                    if (!running) break;
                    InferenceTask task = taskQueue.take();
                    currentStatus = "Inferring (" + task.type() + ")";
                    switch (task.type()) {
                        case MATCH_ANTECEDENT -> handleMatchAntecedentTask(task);
                        case APPLY_ORDERED_REWRITE -> handleApplyRewriteTask(task);
                    }
                    currentStatus = "Idle (Inference)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Inference worker interrupted.");
                } catch (Exception e) {
                    System.err.println("Error in inference loop: " + e.getMessage());
                    e.printStackTrace();
                    currentStatus = "Error (Inference)";
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            System.out.println("Inference worker finished.");
        }

        private void handleMatchAntecedentTask(InferenceTask task) {
            if (!(task.data() instanceof MatchContext ctx)) return;
            findMatchesRecursive(ctx.rule(), ctx.rule().antecedents(), ctx.initialBindings(), Set.of(ctx.triggerAssertionId()))
                    .forEach(matchResult -> {
                        var consequentTerm = Unifier.substitute(ctx.rule().consequent(), matchResult.bindings());
                        if (consequentTerm instanceof KifList derived && !derived.containsVariable() && !isTrivial(derived)) {
                            double derivedPriority = calculateDerivedPriority(matchResult.supportIds(), ctx.rule().priority());
                            String commonNoteId = findCommonSourceNodeId(matchResult.supportIds());
                            boolean isEq = derived.getOperator().filter("="::equals).isPresent(); // Check if derived result is equality
                            boolean isOriented = isEq && derived.size() == 3 && derived.get(1).calculateWeight() > derived.get(2).calculateWeight();
                            var pa = new PotentialAssertion(derived, derivedPriority, matchResult.supportIds(), ctx.rule().id(), isEq, isOriented, commonNoteId);
                            submitPotentialAssertion(pa);
                        }
                    });
        }

        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifList> remainingClauses, Map<KifVar, KifTerm> currentBindings, Set<String> currentSupportIds) {
            if (remainingClauses.isEmpty()) return Stream.of(new MatchResult(currentBindings, currentSupportIds));
            var clauseToMatch = remainingClauses.getFirst();
            var nextRemainingClauses = remainingClauses.subList(1, remainingClauses.size());
            var substitutedClauseTerm = Unifier.fullySubstitute(clauseToMatch, currentBindings);
            if (!(substitutedClauseTerm instanceof KifList substitutedClause))
                return Stream.empty(); // Should not happen if rule is valid

            return kb.findUnifiableAssertions(substitutedClause).flatMap(candidate -> {
                Map<KifVar, KifTerm> newBindings = Unifier.unify(substitutedClause, candidate.kif(), currentBindings);
                if (newBindings != null) {
                    Set<String> nextSupport = new HashSet<>(currentSupportIds);
                    nextSupport.add(candidate.id());
                    return findMatchesRecursive(rule, nextRemainingClauses, newBindings, nextSupport);
                } else return Stream.empty();
            });
        }

        private void handleApplyRewriteTask(InferenceTask task) {
            if (!(task.data() instanceof RewriteContext ctx)) return;
            Assertion rewriteRule = ctx.rewriteRule(), targetAssertion = ctx.targetAssertion();
            if (!rewriteRule.isEquality() || !rewriteRule.isOrientedEquality() || rewriteRule.kif().size() != 3)
                return; // Invalid rule

            KifTerm lhsPattern = rewriteRule.kif().get(1), rhsTemplate = rewriteRule.kif().get(2);
            Optional<KifTerm> rewrittenTermOpt = Unifier.rewrite(targetAssertion.kif(), lhsPattern, rhsTemplate);

            rewrittenTermOpt.ifPresent(rewrittenTerm -> {
                if (rewrittenTerm instanceof KifList rewrittenList && !rewrittenList.equals(targetAssertion.kif()) && !isTrivial(rewrittenList)) {
                    Set<String> support = new HashSet<>(targetAssertion.support());
                    support.add(targetAssertion.id());
                    support.add(rewriteRule.id());
                    double derivedPriority = calculateDerivedPriority(support, task.priority());
                    String commonNoteId = findCommonSourceNodeId(support);
                    boolean resultIsEq = rewrittenList.getOperator().filter("="::equals).isPresent();
                    boolean resultIsOriented = resultIsEq && rewrittenList.size() == 3 && rewrittenList.get(1).calculateWeight() > rewrittenList.get(2).calculateWeight();
                    var pa = new PotentialAssertion(rewrittenList, derivedPriority, support, rewriteRule.id(), resultIsEq, resultIsOriented, commonNoteId);
                    submitPotentialAssertion(pa);
                }
            });
        }

        private void generateNewTasks(Assertion newAssertion) {
            // 1. Trigger rule matching
            rules.forEach(rule -> rule.antecedents().forEach(antecedentClause -> {
                var c = antecedentClause.getOperator().map(op -> op.equals(newAssertion.kif().getOperator().orElse(null))).orElse(true);
                // Operator pre-check
                if (c) {
                    Map<KifVar, KifTerm> bindings = Unifier.unify(antecedentClause, newAssertion.kif(), Map.of());
                    if (bindings != null)
                        taskQueue.put(InferenceTask.matchAntecedent(rule, newAssertion, bindings, calculateTaskPriority(rule.priority(), newAssertion.priority())));

                }
            }));

            // 2. Trigger rewrites
            if (newAssertion.isEquality() && newAssertion.isOrientedEquality()) { // New assertion IS a rewrite rule
                KifTerm lhsPattern = newAssertion.kif().get(1);
                kb.findInstancesOf(lhsPattern)
                        .filter(target -> !target.id().equals(newAssertion.id()))
                        .forEach(target -> taskQueue.put(InferenceTask.applyRewrite(newAssertion, target, calculateTaskPriority(newAssertion.priority(), target.priority()))));
            } else { // New assertion is a standard fact, find rules that apply TO it
                kb.getAllAssertions().stream() // TODO: Optimize KB lookup for rewrite rules
                        .filter(Assertion::isOrientedEquality)
                        .filter(rule -> rule.kif().size() == 3) // Ensure valid equality structure
                        .forEach(rule -> {
                            KifTerm lhsPattern = rule.kif().get(1);
                            if (Unifier.match(lhsPattern, newAssertion.kif(), Map.of()) != null) {
                                taskQueue.put(InferenceTask.applyRewrite(rule, newAssertion, calculateTaskPriority(rule.priority(), newAssertion.priority())));
                            }
                        });
            }
        }

        private void triggerMatchingForNewRule(Rule newRule) {
            System.out.println("Triggering matching for new rule: " + newRule.id());
            kb.getAllAssertions().forEach(existing -> newRule.antecedents().forEach(clause -> {
                Map<KifVar, KifTerm> bindings = Unifier.unify(clause, existing.kif(), Map.of());
                if (bindings != null)
                    taskQueue.put(InferenceTask.matchAntecedent(newRule, existing, bindings, calculateTaskPriority(newRule.priority(), existing.priority())));
            }));
        }

        private String generateFactId(PotentialAssertion pa) {
            return "fact-" + idGenerator.get();
        }

        private double calculateDerivedPriority(Set<String> supportIds, double basePriority) {
            if (supportIds.isEmpty()) return basePriority;
            double minSupportPriority = supportIds.stream().map(kb::getAssertion).flatMap(Optional::stream).mapToDouble(Assertion::priority).min().orElse(basePriority);
            return minSupportPriority * 0.95; // Simple decay heuristic
        }

        private double calculateTaskPriority(double p1, double p2) {
            return (p1 + p2) / 2.0;
        } // Average priority

        private String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null;
            boolean first = true;
            Queue<String> toCheck = new LinkedList<>(supportIds);
            Set<String> visited = new HashSet<>(supportIds);
            while (!toCheck.isEmpty()) {
                var assertionOpt = kb.getAssertion(toCheck.poll());
                if (assertionOpt.isPresent()) {
                    Assertion assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (first) {
                            commonId = assertion.sourceNoteId();
                            first = false;
                        } else if (!commonId.equals(assertion.sourceNoteId())) return null; // Diverged
                    } else if (!assertion.support().isEmpty() && Collections.disjoint(assertion.support(), visited)) {
                        assertion.support().forEach(supId -> {
                            if (visited.add(supId)) toCheck.offer(supId);
                        });
                    } else if (assertion.support().isEmpty() && !first)
                        return null; // Reached root without note ID after finding one
                } else return null; // Support assertion missing
            }
            return commonId;
        }

        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {
        }
    }

    // --- KIF Parser ---
    static class KifParser {
        private Reader reader;
        private int currentChar = -2;
        private int line = 1;
        private int col = 0;

        private KifParser(Reader reader) {
            this.reader = reader;
        }

        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var r = new StringReader(input.trim())) {
                return new KifParser(r).parseTopLevel();
            } catch (IOException e) {
                throw new ParseException("Read error: " + e.getMessage());
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
                case -1 -> throw createParseException("Unexpected EOF");
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
                int c = peek();
                if (c == ')') {
                    consumeChar(')');
                    return new KifList(terms);
                }
                if (c == -1) throw createParseException("Unmatched parenthesis");
                terms.add(parseTerm());
            }
        }

        private KifVar parseVariable() throws IOException, ParseException {
            consumeChar('?');
            var name = new StringBuilder("?");
            if (!isValidAtomChar(peek(), true)) throw createParseException("Variable name expected after '?'");
            while (isValidAtomChar(peek(), false)) name.append((char) consumeChar());
            if (name.length() == 1) throw createParseException("Empty variable name");
            return new KifVar(name.toString());
        }

        private KifAtom parseAtom() throws IOException, ParseException {
            var atom = new StringBuilder();
            if (!isValidAtomChar(peek(), true)) throw createParseException("Invalid start for atom");
            while (isValidAtomChar(peek(), false)) atom.append((char) consumeChar());
            if (atom.isEmpty()) throw createParseException("Empty atom encountered");
            return new KifAtom(atom.toString());
        }

        private boolean isValidAtomChar(int c, boolean isFirstChar) {
            return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1;
        }

        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                int c = consumeChar();
                if (c == '"') return new KifAtom(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') {
                    int next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape character");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\', '"' -> next;
                        default -> {
                            System.err.println("Warning: Unknown escape sequence '\\" + (char) next + "' at line " + line + ", col " + (col - 1));
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
                if (c == '\n') {
                    line++;
                    col = 0;
                } else {
                    col++;
                }
            }
            return c;
        }

        private void consumeChar(char expected) throws IOException, ParseException {
            int actual = consumeChar();
            if (actual != expected)
                throw createParseException("Expected '" + expected + "' but found '" + (actual == -1 ? "EOF" : (char) actual) + "'");
        }

        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                int c = peek();
                if (c == -1 || (!Character.isWhitespace(c) && c != ';')) break;
                if (Character.isWhitespace(c)) consumeChar();
                else {
                    while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                    if (peek() == '\n' || peek() == '\r') consumeChar();
                }
            }
        }

        private ParseException createParseException(String message) {
            return new ParseException(message + " at line " + line + " col " + col);
        }
    }

    static class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    // --- Unification & Rewriting Logic ---
    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 50;

        static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            return unifyRecursive(x, y, bindings);
        }

        static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            return matchRecursive(pattern, term, bindings);
        }

        static KifTerm substitute(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return fullySubstitute(term, bindings);
        }

        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            return rewriteRecursive(target, lhsPattern, rhsTemplate);
        }

        private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null;
            var xSubst = fullySubstitute(x, bindings);
            var ySubst = fullySubstitute(y, bindings);
            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true);
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true);
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var current = bindings;
                for (int i = 0; i < lx.size(); i++) {
                    current = unifyRecursive(lx.get(i), ly.get(i), current);
                    if (current == null) return null;
                }
                return current;
            }
            return null;
        }

        private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null;
            var patternSubst = fullySubstitute(pattern, bindings);
            if (patternSubst instanceof KifVar varP) return bindVariable(varP, term, bindings, false);
            if (patternSubst.equals(term)) return bindings;
            if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                var current = bindings;
                for (int i = 0; i < lp.size(); i++) {
                    current = matchRecursive(lp.get(i), lt.get(i), current);
                    if (current == null) return null;
                }
                return current;
            }
            return null;
        }

        static KifTerm fullySubstitute(KifTerm term, Map<KifVar, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term;
            var current = term;
            for (int depth = 0; depth < MAX_SUBST_DEPTH; depth++) {
                var next = substituteOnce(current, bindings);
                if (next.equals(current) || !next.containsVariable()) return next;
                current = next;
            }
            System.err.println("Warning: Substitution depth limit (" + MAX_SUBST_DEPTH + ") reached for: " + term.toKifString());
            return current;
        }

        private static KifTerm substituteOnce(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return switch (term) {
                case KifAtom atom -> atom;
                case KifVar var -> bindings.getOrDefault(var, var);
                case KifList list -> {
                    boolean changed = false;
                    List<KifTerm> newTerms = new ArrayList<>(list.size());
                    for (KifTerm sub : list.terms()) {
                        KifTerm substSub = substituteOnce(sub, bindings);
                        if (!Objects.equals(substSub, sub)) changed = true;
                        newTerms.add(substSub);
                    }
                    yield changed ? new KifList(newTerms) : list;
                }
            };
        }

        private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean checkOccurs) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var))
                return checkOccurs ? unifyRecursive(bindings.get(var), value, bindings) : matchRecursive(bindings.get(var), value, bindings);
            var finalValue = (value instanceof KifVar v && bindings.containsKey(v)) ? fullySubstitute(v, bindings) : value;
            if (checkOccurs && occursCheck(var, finalValue, bindings)) return null;
            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheck(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings) {
            var substTerm = fullySubstitute(term, bindings);
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheck(var, t, bindings));
                case KifAtom ignored -> false;
            };
        }

        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            Map<KifVar, KifTerm> matchBindings = match(lhsPattern, target, Map.of());
            if (matchBindings != null)
                return Optional.of(fullySubstitute(rhsTemplate, matchBindings)); // Matched at this level
            if (target instanceof KifList targetList) { // Try rewriting subterms
                boolean changed = false;
                List<KifTerm> newSubTerms = new ArrayList<>(targetList.size());
                for (KifTerm subTerm : targetList.terms()) {
                    Optional<KifTerm> rewrittenSub = rewriteRecursive(subTerm, lhsPattern, rhsTemplate);
                    if (rewrittenSub.isPresent()) changed = true;
                    newSubTerms.add(rewrittenSub.orElse(subTerm));
                }
                return changed ? Optional.of(new KifList(newSubTerms)) : Optional.empty();
            }
            return Optional.empty(); // Atom/Var, no match
        }
    }

    // --- Swing UI Inner Class ---
    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing...");
        final JTextArea derivationView = new JTextArea();
        final Map<String, String> derivationTextCache = new ConcurrentHashMap<>();
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JButton addButton = new JButton("Add Note");
        private final JButton removeButton = new JButton("Remove Note");
        private final JButton analyzeButton = new JButton("Analyze Note");
        private final JButton pauseResumeButton = new JButton("Pause");
        private final JButton clearAllButton = new JButton("Clear All");
        private CogNote reasoner;
        private Note currentNote = null;

        public SwingUI(CogNote reasoner) {
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
                    if (SwingUI.this.reasoner != null) SwingUI.this.reasoner.stopReasoner();
                    dispose();
                    System.exit(0);
                }
            });
            updateUIForSelection();
        }

        void setReasoner(CogNote reasoner) {
            this.reasoner = reasoner;
        }

        private void setupFonts() {
            Stream.of(noteList, noteEditor, addButton, removeButton, analyzeButton, pauseResumeButton, clearAllButton, statusLabel).forEach(c -> c.setFont(UI_DEFAULT_FONT));
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
        }

        private void setupLayout() {
            var leftPane = new JScrollPane(noteList);
            leftPane.setPreferredSize(new Dimension(250, 0));
            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(noteEditor), new JScrollPane(derivationView));
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
                @Override
                public void focusLost(java.awt.event.FocusEvent evt) {
                    saveCurrentNoteText();
                }
            });
        }

        private void saveCurrentNoteText() {
            if (currentNote != null && noteEditor.isEnabled()) currentNote.text = noteEditor.getText();
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
            noteEditor.requestFocusInWindow();
        }

        private void addNote(ActionEvent e) {
            String title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) {
                var newNote = new Note("note-" + UUID.randomUUID(), title.trim(), "");
                noteListModel.addElement(newNote);
                noteList.setSelectedValue(newNote, true);
                noteEditor.requestFocusInWindow();
            }
        }

        private void removeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            if (JOptionPane.showConfirmDialog(this, "Remove note '" + currentNote.title + "' and retract associated assertions?", "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                statusLabel.setText("Status: Removing '" + currentNote.title + "'...");
                setButtonsEnabled(false);
                var noteToRemove = currentNote;
                reasoner.processRetractionByNoteIdInput(noteToRemove.id, "UI-Remove");
                derivationTextCache.remove(noteToRemove.id);
                SwingUtilities.invokeLater(() -> {
                    int idx = noteList.getSelectedIndex();
                    noteListModel.removeElement(noteToRemove);
                    if (!noteListModel.isEmpty())
                        noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
                    else {
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
            derivationView.setText("Analyzing Note '" + noteToAnalyze.title + "' via LLM...\n--------------------\n");
            reasoner.processRetractionByNoteIdInput(noteToAnalyze.id, "UI-Analyze-Retract");
            derivationTextCache.remove(noteToAnalyze.id);
            reasoner.getKifFromLlmAsync(noteTextToAnalyze, noteToAnalyze.id)
                    .thenAcceptAsync(kifString -> processLlmResponse(kifString, noteToAnalyze), SwingUtilities::invokeLater)
                    .exceptionallyAsync(ex -> {
                        handleLlmFailure(ex, noteToAnalyze);
                        return null;
                    }, SwingUtilities::invokeLater);
        }

        private void processLlmResponse(String kifString, Note analyzedNote) {
            if (currentNote != analyzedNote) {
                setButtonsEnabled(true);
                return;
            } // User switched notes
            try {
                var terms = KifParser.parseKif(kifString);
                int submitted = 0, skipped = 0;
                var log = new StringBuilder("LLM KIF Analysis for: " + analyzedNote.title + "\n--------------------\n");
                for (var term : terms) {
                    if (term instanceof KifList list && !list.terms.isEmpty()) {
                        var processedTerm = groundLlmTerm(list, analyzedNote); // Ground variables
                        if (processedTerm instanceof KifList pList && !pList.terms.isEmpty() && !isTrivial(pList)) {
                            boolean isEq = pList.getOperator().filter("="::equals).isPresent();
                            int weight = pList.calculateWeight();
                            double priority = 15.0 / (1.0 + weight);
                            boolean isOriented = isEq && pList.size() == 3 && pList.get(1).calculateWeight() > pList.get(2).calculateWeight();
                            var pa = new PotentialAssertion(pList, priority, Set.of(), "UI-LLM", isEq, isOriented, analyzedNote.id);
                            reasoner.submitPotentialAssertion(pa);
                            log.append("Submitted: ").append(pList.toKifString()).append("\n");
                            submitted++;
                        } else {
                            log.append("Skipped (trivial/processing failed): ").append(list.toKifString()).append("\n");
                            skipped++;
                        }
                    } else {
                        log.append("Skipped (non-list/empty term): ").append(term.toKifString()).append("\n");
                        skipped++;
                    }
                }
                reasoner.reasonerStatus = String.format("Analyzed '%s': %d submitted, %d skipped", analyzedNote.title, submitted, skipped);
                derivationTextCache.put(analyzedNote.id, log.toString() + "\n-- Derivations Follow --\n");
                derivationView.setText(derivationTextCache.get(analyzedNote.id));
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
            if (currentNote != analyzedNote) {
                setButtonsEnabled(true);
                return;
            }
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
            var prefixBase = note.title.trim().toLowerCase().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "").replaceAll("_+", "_");
            if (prefixBase.isEmpty() || prefixBase.equals("_")) prefixBase = note.id;
            var notePrefix = "entity_" + prefixBase + "_";
            variables.forEach(var -> groundingMap.put(var, new KifAtom(notePrefix + var.name().substring(1).replaceAll("[^a-zA-Z0-9_]", ""))));
            return Unifier.substitute(term, groundingMap);
        }

        private void togglePause(ActionEvent e) {
            if (reasoner == null) return;
            boolean pausing = !reasoner.isPaused();
            reasoner.setPaused(pausing);
            pauseResumeButton.setText(pausing ? "Resume" : "Pause");
        }

        private void clearAll(ActionEvent e) {
            if (reasoner == null) return;
            if (JOptionPane.showConfirmDialog(this, "Clear all notes, assertions, and rules?", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
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
                String noteId = assertion.sourceNoteId(); // Use the sourceNoteId directly populated by CogNote.invokeCallbacks
                if (noteId != null) {
                    updateDerivationCache(noteId, type, assertion);
                    if (currentNote != null && noteId.equals(currentNote.id)) {
                        derivationView.setText(derivationTextCache.getOrDefault(noteId, ""));
                        // Ensure view updates, maybe scroll to keep current position? Or top?
                        // derivationView.setCaretPosition(derivationView.getDocument().getLength()); // Scroll bottom
                    }
                } else if (type.equals("assert-retracted") || type.equals("evict")) {
                    // If no sourceNoteId, still need to mark retraction/eviction in *all* caches where it might appear
                    clearAssertionLineFromAllCaches(assertion, type);
                    if (currentNote != null && derivationTextCache.containsKey(currentNote.id)) {
                        derivationView.setText(derivationTextCache.get(currentNote.id)); // Refresh current view if affected
                    }
                }
            });
        }

        private void updateDerivationCache(String noteId, String type, Assertion assertion) {
            String lineSuffix = String.format("[%s]", assertion.id());
            String fullLine = String.format("Prio=%.3f %s %s", assertion.priority(), assertion.toKifString(), lineSuffix);
            String currentText = derivationTextCache.computeIfAbsent(noteId, id -> "Derivations for note: " + getNoteTitleById(id) + "\n--------------------\n");
            String newText = currentText;
            switch (type) {
                case "assert-added", "assert-input" -> {
                    if (!currentText.contains(lineSuffix)) newText = currentText + fullLine + "\n";
                }
                case "assert-retracted", "evict" ->
                        newText = currentText.lines().map(line -> (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#")) ? "# " + type.toUpperCase() + ": " + line : line).collect(Collectors.joining("\n")) + "\n";
            }
            if (!newText.equals(currentText)) derivationTextCache.put(noteId, newText);
        }

        private void clearAssertionLineFromAllCaches(Assertion assertion, String type) {
            String lineSuffix = String.format("[%s]", assertion.id());
            String typeMarker = "# " + type.toUpperCase() + ": ";
            derivationTextCache.replaceAll((noteId, text) -> text.lines().map(line -> (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#")) ? typeMarker + line : line).collect(Collectors.joining("\n")) + "\n");
        }

        private void displayCachedDerivations(Note note) {
            if (note == null) {
                derivationView.setText("");
                return;
            }
            // Simple display, no regeneration on cache miss here to avoid slow operations
            derivationView.setText(derivationTextCache.getOrDefault(note.id, "Derivations for: " + note.title + "\n--------------------\n(No derivations cached or generated yet)\n"));
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
            pauseResumeButton.setEnabled(reasoner != null);
            clearAllButton.setEnabled(reasoner != null);
            if (reasoner != null) pauseResumeButton.setText(reasoner.isPaused() ? "Resume" : "Pause");
        }

        static class Note {
            final String id;
            String title;
            String text;

            Note(String id, String title, String text) {
                this.id = id;
                this.title = title;
                this.text = text;
            }

            @Override
            public String toString() {
                return title;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Note n && id.equals(n.id);
            }

            @Override
            public int hashCode() {
                return id.hashCode();
            }
        }

        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                lbl.setBorder(new EmptyBorder(5, 10, 5, 10));
                lbl.setFont(UI_DEFAULT_FONT);
                return lbl;
            }
        }
    }

} // End CogNote class
