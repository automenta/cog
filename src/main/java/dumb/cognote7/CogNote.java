package dumb.cognote7;

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

    // --- Reasoner Parameters ---
    private final int maxKbSize; // Renamed from beamWidth (no longer beam search)
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl;
    private final String llmModel;

    // --- Core Components ---
    private final KnowledgeBase knowledgeBase;
    private final ReasonerEngine reasonerEngine;
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();
    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>();

    // --- Execution & Control ---
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final SwingUI swingUI;
    private volatile String reasonerStatus = "Idle";
    private volatile boolean running = true;
    private volatile boolean paused = false;
    private final Object pauseLock = new Object(); // Separate lock for pause synchronization


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
            int port = 8887, maxKbSize = 64 * 1024; // Default KB size
            String rulesFile = null, llmUrl = null, llmModel = null;
            var broadcastInput = false;

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
                ui = new SwingUI(null); // Initialize UI first
                // Removed beamWidth from CogNote constructor call
                var server = new CogNote(port, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.setReasoner(server); // Link UI and Reasoner

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    server.stopReasoner(); // Ensure server is stopped on exit
                }));

                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified.");

                server.startReasoner();
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
                Usage: java dumb.cognote6.CogNote [options]
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

    // --- WebSocket Communication ---
    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        // Use priority instead of probability in message
        var message = switch (type) {
            case "assert-added" -> String.format("assert-derived %.4f %s", assertion.priority(), kifString);
            case "assert-input" -> String.format("assert-input %.4f %s", assertion.priority(), kifString); // Assuming priority for input too
            case "assert-retracted" -> String.format("retract %s", assertion.id());
            case "evict" -> String.format("evict %s", assertion.id());
            default -> type + " " + kifString; // Fallback
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
        if (!Files.exists(path) || !Files.isReadable(path)) throw new IOException("File not found or not readable: " + filename);

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
                    parenDepth = 0; // Reset depth
                    kifBuffer.setLength(0); // Clear buffer
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
                        case "=>", "<=>" -> { // Handle rules
                            try {
                                // Default rule priority, could be enhanced later
                                var rule = Rule.parseRule(generateId("rule"), list, 1.0);
                                reasonerEngine.addRule(rule);
                                // If bidirectional, add the reverse rule explicitly
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
                        case "=" -> { // Handle equality
                            if (list.size() == 3) {
                                var lhs = list.get(1);
                                var rhs = list.get(2);
                                // Calculate weight immediately for orientation/priority
                                var weight = list.calculateWeight();
                                // Simple priority: inverse weight (lower weight = higher priority)
                                var priority = 10.0 / (1.0 + weight);
                                // Mark as equality, let commit service orient it later if needed? No, handle here.
                                boolean isOriented = lhs.calculateWeight() > rhs.calculateWeight();
                                // Create PotentialAssertion for the equality
                                var pa = new PotentialAssertion(list, priority, Set.of(), sourceId, true, isOriented, null);
                                reasonerEngine.submitPotentialAssertion(pa);
                                // Also submit potential rewrite tasks? Or let commit service do it? Commit service.
                            } else {
                                System.err.println("Invalid equality format ignored (must have 2 arguments): " + list.toKifString());
                            }
                        }
                        default -> { // Standard assertion
                            // Check for groundness
                            if (list.containsVariable()) {
                                System.err.println("Warning: Non-ground assertion input ignored: " + list.toKifString());
                            } else {
                                var weight = list.calculateWeight();
                                var priority = 10.0 / (1.0 + weight); // Example priority
                                var pa = new PotentialAssertion(list, priority, Set.of(), sourceId, false, false, null);
                                reasonerEngine.submitPotentialAssertion(pa);
                            }
                        }
                    }
                } else { // List doesn't start with operator (e.g., a list of constants) - Treat as ground fact
                    if (list.containsVariable()) {
                        System.err.println("Warning: Non-ground assertion input ignored: " + list.toKifString());
                    } else {
                        var weight = list.calculateWeight();
                        var priority = 10.0 / (1.0 + weight);
                        var pa = new PotentialAssertion(list, priority, Set.of(), sourceId, false, false, null);
                        reasonerEngine.submitPotentialAssertion(pa);
                    }
                }
            }
            case KifList ignored -> { /* Ignore empty list */ }
            default -> System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
        }
    }

    // --- Quantifier Handling (Simplified) ---
    // Keep existing Skolemization for 'exists'
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

    // Keep existing 'forall' handling (only for rule definitions)
    private void handleForall(KifList forallExpr, String sourceId) {
        if (forallExpr.size() == 3 && forallExpr.get(2) instanceof KifList bodyList && bodyList.getOperator().filter("=>"::equals).isPresent()) {
            System.out.println("Interpreting 'forall ... (=> ant con)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
            try {
                var rule = Rule.parseRule(generateId("rule"), bodyList, 1.0); // Default priority 1.0
                reasonerEngine.addRule(rule);
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid rule format ignored: " + bodyList.toKifString() + " | Error: " + e.getMessage());
            }
        } else {
            System.err.println("Warning: Ignoring complex 'forall' (only handles 'forall vars (=> ant con)'): " + forallExpr.toKifString());
        }
    }

    // --- Public Control Methods ---
    public void startReasoner() {
        if (!running) {
            System.err.println("Cannot restart a stopped reasoner.");
            return;
        }
        paused = false;
        reasonerEngine.start(); // Start the engine's threads
        try {
            start(); // Start WebSocket server
        } catch (IllegalStateException e) {
            System.err.println("WebSocket server already started or failed to start: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage());
            stopReasoner(); // Stop everything if WS fails
        }
        System.out.println("Reasoner started.");
    }

    public void stopReasoner() {
        if (!running) return;
        System.out.println("Stopping reasoner and services...");
        running = false;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); } // Wake up engine if paused

        reasonerEngine.stop(); // Stop the engine first

        try {
            stop(1000); // Stop WebSocket server
            System.out.println("WebSocket server stopped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while stopping WebSocket server.");
        } catch (Exception e) {
            System.err.println("Error stopping WebSocket server: " + e.getMessage());
        }

        // Shutdown HTTP client executor
        var clientExecutor = httpClient.executor();
        if (clientExecutor.isPresent() && clientExecutor.get() instanceof ExecutorService exec) {
            shutdownExecutor(exec, "HTTP Client Executor");
        }

        reasonerStatus = "Stopped";
        updateUIStatus();
        System.out.println("Reasoner stopped.");
    }

    private static void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println(name + " did not terminate gracefully, forcing shutdown.");
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println(name + " did not terminate after forced shutdown.");
                }
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for " + name + " shutdown.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isPaused() {
        return this.paused;
    }

    public void setPaused(boolean pause) {
        if (this.paused == pause) return; // No change
        this.paused = pause;
        reasonerEngine.setPaused(pause); // Propagate pause state to engine
        if (!pause) {
            synchronized (pauseLock) { pauseLock.notifyAll(); }
        }
        reasonerStatus = pause ? "Paused" : "Running"; // Update status
        updateUIStatus();
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        setPaused(true); // Pause engine during clear
        List<Assertion> toRetract = knowledgeBase.getAllAssertions(); // Get snapshot before clearing
        knowledgeBase.clear();
        reasonerEngine.clear();
        noteIdToAssertionIds.clear();
        SwingUtilities.invokeLater(swingUI.derivationTextCache::clear); // Clear UI cache

        // Notify UI/WS about retractions AFTER clearing internal state
        toRetract.forEach(a -> invokeCallbacks("assert-retracted", a));

        reasonerStatus = "Cleared";
        setPaused(false); // Resume engine
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
        System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + (reason == null || reason.isEmpty() ? "N/A" : reason));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        var addr = (conn != null && conn.getRemoteSocketAddress() != null) ? conn.getRemoteSocketAddress().toString() : "server";
        if (ex instanceof IOException && (ex.getMessage() != null && (ex.getMessage().contains("Socket closed") || ex.getMessage().contains("Connection reset") || ex.getMessage().contains("Broken pipe")))) {
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

        // Basic command parsing (simple prefix check)
        if (trimmed.toLowerCase().startsWith("retract ")) {
            var idToRetract = trimmed.substring(8).trim();
            if (!idToRetract.isEmpty()) {
                processRetractionByIdInput(idToRetract, sourceId);
            } else {
                System.err.println("WS Retract Error from " + sourceId + ": Missing assertion ID.");
            }
        } else if (trimmed.toLowerCase().startsWith("register_callback ")) {
            // Example: register_callback (pattern ?X Y)
            // TODO: Implement callback registration parsing if needed via WS
            System.err.println("WS Callback registration not fully implemented yet.");
        } else {
            // Default: Assume KIF expression(s)
            try {
                // Try parsing KIF directly
                var terms = KifParser.parseKif(trimmed);
                if (terms.isEmpty()) throw new ParseException("Received non-KIF message or empty KIF: " + trimmed.substring(0, Math.min(trimmed.length(), 100)));

                terms.forEach(term -> queueExpressionFromSource(term, sourceId));

            } catch (ParseException | ClassCastException e) {
                System.err.printf("WS Message Parse Error from %s: %s | Original: %s%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), 100)));
            } catch (Exception e) {
                System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }


    // --- Direct Input Processing Methods (Called by UI or other internal logic) ---
    // Simplified - submit PotentialAssertion directly
    public void submitPotentialAssertion(PotentialAssertion pa) {
        reasonerEngine.submitPotentialAssertion(pa);
        if (pa.sourceNoteId() != null && !pa.isEquality() && !pa.kif().containsVariable()) {
            // For immediate UI feedback on direct inputs from notes
            var tempId = generateId("input");
            var inputAssertion = new Assertion(tempId, pa.kif(), pa.priority(), System.currentTimeMillis(),
                    pa.sourceNoteId(), Set.of(), pa.isEquality(), pa.isOrientedEquality());
            invokeCallbacks("assert-input", inputAssertion);
        }
    }

    // Add or update a rule
    public void submitRule(Rule rule) {
        reasonerEngine.addRule(rule);
    }

    // Process retraction by ID
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
            // TODO: Implement proper cascading retraction if needed. Currently just removes the assertion.
        } else {
            System.out.printf("Retraction by ID %s from %s failed: ID not found.%n", assertionId, sourceId);
        }
    }

    // Process retraction by Note ID
    public void processRetractionByNoteIdInput(String noteId, String sourceId) {
        var idsToRetract = noteIdToAssertionIds.remove(noteId); // Atomically get and remove
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.printf("Retracting %d assertions for note %s from %s.%n", idsToRetract.size(), noteId, sourceId);
            new HashSet<>(idsToRetract).forEach(id -> processRetractionByIdInput(id, sourceId + "-noteRetract"));
        } else {
            System.out.printf("Retraction by Note ID %s from %s failed: No associated assertions.%n", noteId, sourceId);
        }
    }

    // Process rule retraction (by exact KIF form)
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

    // Register a callback
    public void registerCallback(KifTerm pattern, KifCallback callback) {
        callbackRegistrations.add(new CallbackRegistration(pattern, callback));
        System.out.println("Registered external callback for pattern: " + pattern.toKifString());
    }

    // --- Callback Handling ---
    // Called by ReasonerEngine after commit
    void invokeCallbacks(String type, Assertion assertion) {
        // Link assertion to note ID *after* commit
        if (assertion.sourceNoteId() != null) {
            noteIdToAssertionIds.computeIfAbsent(assertion.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(assertion.id());
        }

        // 1. WebSocket Broadcast (Conditional)
        boolean shouldBroadcast = switch (type) {
            case "assert-added", "assert-retracted", "evict" -> true;
            case "assert-input" -> broadcastInputAssertions;
            default -> false;
        };
        if (shouldBroadcast) {
            broadcastMessage(type, assertion);
        }

        // 2. UI Update
        if (swingUI != null && swingUI.isDisplayable()) {
            swingUI.handleReasonerCallback(type, assertion);
        }

        // 3. Registered KIF Pattern Callbacks
        callbackRegistrations.forEach(reg -> {
            try {
                // Use match instead of unify if the pattern might have variables
                Map<KifVar, KifTerm> bindings = Unifier.match(reg.pattern(), assertion.kif, Map.of());
                if (bindings != null) {
                    reg.callback().onMatch(type, assertion, bindings);
                }
            } catch (Exception e) {
                System.err.println("Error executing KIF pattern callback for " + reg.pattern().toKifString() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
        updateUIStatus(); // Update status after any callback activity
    }

    // Separate callback for evictions needed by KB
    void invokeEvictionCallbacks(Assertion evictedAssertion) {
        invokeCallbacks("evict", evictedAssertion);
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

    // --- LLM Interaction (Mostly unchanged, submits PotentialAssertion now) ---
    public CompletableFuture<String> getKifFromLlmAsync(String contextPrompt, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var finalPrompt = (!contextPrompt.toLowerCase().contains("kif assertions:") && !contextPrompt.toLowerCase().contains("proposed fact:")) ? """
                    Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax).
                    Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                    Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', attribute relations, etc.
                    Use '=' for equality. Handle variables appropriately (e.g., `?x`) if needed, but prefer ground facts.
                    Example: (instance MyComputer Computer), (= (deviceName MyComputer) "HAL")

                    Note:
                    "%s"

                    KIF Assertions:""".formatted(contextPrompt) : contextPrompt;

            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", finalPrompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(this.llmApiUrl))
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
                            .filter(line -> !line.matches("^\\(\\s*\\)$"))
                            .collect(Collectors.joining("\n"));
                    if (cleanedKif.isEmpty() && !kifResult.isBlank()) {
                        System.err.println("LLM Warning (" + noteId + "): Result contained text but no valid KIF lines found:\n---\n" + kifResult + "\n---");
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
        return Optional.ofNullable(jsonResponse.optJSONObject("message"))
                .map(m -> m.optString("content", null))
                .or(() -> Optional.ofNullable(jsonResponse.optString("response", null)))
                .or(() -> Optional.ofNullable(jsonResponse.optJSONArray("choices"))
                        .filter(a -> !a.isEmpty()).map(a -> a.optJSONObject(0))
                        .map(c -> c.optJSONObject("message")).map(m -> m.optString("content", null)))
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
                } yield found;
            }
            case JSONArray arr -> {
                Optional<String> found = Optional.empty();
                for (var i = 0; i < arr.length(); i++) {
                    found = findNestedContent(arr.get(i));
                    if (found.isPresent()) break;
                } yield found;
            } default -> Optional.empty();
        };
    }

    // --- KIF Data Structures ---
    sealed interface KifTerm permits KifAtom, KifVar, KifList {
        String toKifString();
        boolean containsVariable();
        Set<KifVar> getVariables();
        int calculateWeight(); // Added method

        private static void collectVariablesRecursive(KifTerm term, Set<KifVar> vars) {
            switch (term) {
                case KifVar v -> vars.add(v);
                case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars));
                case KifAtom ignored -> {}
            }
        }
        static Set<KifVar> collectVariablesFromSpec(KifTerm varsTerm) {
            return switch (varsTerm) {
                case KifVar v -> Set.of(v);
                case KifList l -> l.terms().stream()
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

    static final class KifAtom implements KifTerm {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$");
        private final String value;
        private volatile Set<KifVar> variables; // Cache for getVariables
        private volatile int weight = -1; // Cache for weight

        KifAtom(String value) { this.value = Objects.requireNonNull(value); }
        public String value() { return value; }

        @Override public String toKifString() {
            boolean needsQuotes = value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)
                    || value.chars().anyMatch(c -> "()\";?".indexOf(c) != -1)
                    || !SAFE_ATOM_PATTERN.matcher(value).matches();
            return needsQuotes ? '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : value;
        }
        @Override public boolean containsVariable() { return false; }
        @Override public Set<KifVar> getVariables() {
            if (variables == null) variables = Set.of();
            return variables;
        }
        @Override public int calculateWeight() {
            if (weight == -1) weight = 1; // Simple weight for atoms
            return weight;
        }
        @Override public boolean equals(Object o) { return this == o || (o instanceof KifAtom a && value.equals(a.value)); }
        @Override public int hashCode() { return value.hashCode(); }
        @Override public String toString() { return "KifAtom[" + value + ']'; }
    }

    static final class KifVar implements KifTerm {
        private final String name;
        private volatile Set<KifVar> variables; // Cache
        private volatile int weight = -1; // Cache

        KifVar(String name) {
            Objects.requireNonNull(name);
            if (!name.startsWith("?")) throw new IllegalArgumentException("Variable name must start with '?': " + name);
            if (name.length() == 1) throw new IllegalArgumentException("Variable name cannot be empty ('?')");
            this.name = name;
        }
        public String name() { return name; }

        @Override public String toKifString() { return name; }
        @Override public boolean containsVariable() { return true; }
        @Override public Set<KifVar> getVariables() {
            if (variables == null) variables = Set.of(this);
            return variables;
        }
        @Override public int calculateWeight() {
            if (weight == -1) weight = 1; // Simple weight for variables
            return weight;
        }
        @Override public boolean equals(Object o) { return this == o || (o instanceof KifVar v && name.equals(v.name)); }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public String toString() { return "KifVar[" + name + ']'; }
    }

    static final class KifList implements KifTerm {
        private final List<KifTerm> terms;
        private volatile Boolean containsVariable;
        private volatile Set<KifVar> variables;
        private volatile int hashCode;
        private volatile String kifString;
        private volatile int weight = -1; // Cache for weight

        KifList(KifTerm... terms) { this(List.of(terms)); }
        KifList(List<KifTerm> terms) { this.terms = List.copyOf(Objects.requireNonNull(terms)); }

        public List<KifTerm> terms() { return terms; }
        KifTerm get(int index) { return terms.get(index); }
        int size() { return terms.size(); }
        Optional<String> getOperator() {
            return terms.isEmpty() || !(terms.getFirst() instanceof KifAtom atom) ? Optional.empty() : Optional.of(atom.value());
        }

        @Override public String toKifString() {
            if (kifString == null) kifString = terms.stream().map(KifTerm::toKifString).collect(Collectors.joining(" ", "(", ")"));
            return kifString;
        }
        @Override public boolean containsVariable() {
            if (containsVariable == null) containsVariable = terms.stream().anyMatch(KifTerm::containsVariable);
            return containsVariable;
        }
        @Override public Set<KifVar> getVariables() {
            if (variables == null) {
                Set<KifVar> vars = new HashSet<>();
                terms.forEach(t -> KifTerm.collectVariablesRecursive(t, vars));
                variables = Collections.unmodifiableSet(vars);
            }
            return variables;
        }
        @Override public int calculateWeight() {
            if (weight == -1) {
                // Weight = 1 (for list structure) + sum of children weights
                weight = 1 + terms.stream().mapToInt(KifTerm::calculateWeight).sum();
            }
            return weight;
        }
        @Override public boolean equals(Object o) { return this == o || (o instanceof KifList l && terms.equals(l.terms)); }
        @Override public int hashCode() {
            if (hashCode == 0) hashCode = terms.hashCode();
            return hashCode;
        }
        @Override public String toString() { return "KifList[" + terms + ']'; }
    }


    // --- Reasoner Data Records ---

    // Represents a fact or equality in the KB
    record Assertion(
            String id,
            KifList kif,
            double priority, // Higher value = higher priority for keeping/processing
            long timestamp,
            String sourceNoteId,
            Set<String> support, // Renamed from directSupportingIds
            boolean isEquality,
            boolean isOrientedEquality // True if LHS weight > RHS weight (used for rewriting)
    ) implements Comparable<Assertion> {
        Assertion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
            // Priority validation if needed (e.g., positive)
        }
        // Comparison for EvictionQueue (Min-heap based on priority - lower priority evicted first)
        @Override public int compareTo(Assertion other) { return Double.compare(this.priority, other.priority); }
        String toKifString() { return kif.toKifString(); }
        // Note: weight is not stored directly, priority is derived from it (and other factors)
    }

    // Represents a rule in the KB
    record Rule(
            String id,
            KifList ruleForm, // Original KIF like (=> ant con) or (<=> ant con)
            KifTerm antecedent,
            KifTerm consequent,
            double priority, // Priority for applying the rule
            List<KifList> antecedents // Parsed antecedent clauses
    ) {
        Rule {
            Objects.requireNonNull(id);
            Objects.requireNonNull(ruleForm);
            Objects.requireNonNull(antecedent);
            Objects.requireNonNull(consequent);
            antecedents = List.copyOf(Objects.requireNonNull(antecedents));
        }

        // Factory method to parse rule structure
        static Rule parseRule(String id, KifList ruleForm, double priority) throws IllegalArgumentException {
            if (!(ruleForm.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent() && ruleForm.size() == 3)) {
                throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());
            }
            var antTerm = ruleForm.get(1);
            var conTerm = ruleForm.get(2);

            // Parse antecedent clauses
            List<KifList> parsedAntecedents = switch (antTerm) {
                case KifList list when list.getOperator().filter("and"::equals).isPresent() ->
                        list.terms().stream().skip(1).map(t -> {
                            if (t instanceof KifList l) return l;
                            throw new IllegalArgumentException("Elements of 'and' antecedent must be lists: " + t.toKifString());
                        }).toList();
                case KifList list -> List.of(list);
                // case KifAtom atom when atom.value().equalsIgnoreCase("true") -> List.of(); // Rule with no antecedent
                default -> throw new IllegalArgumentException("Antecedent must be a KIF list or (and list1...): " + antTerm.toKifString());
            };

            validateUnboundVariables(ruleForm, antTerm, conTerm);

            return new Rule(id, ruleForm, antTerm, conTerm, priority, parsedAntecedents);
        }

        // Check for variables in consequent not bound by antecedent (Warn for =>, allow for <=>)
        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
            var antVars = antecedent.getVariables();
            var conVarsAll = consequent.getVariables();
            var conVarsBoundLocally = getQuantifierBoundVariables(consequent);

            Set<KifVar> conVarsNeedingBinding = new HashSet<>(conVarsAll);
            conVarsNeedingBinding.removeAll(antVars);
            conVarsNeedingBinding.removeAll(conVarsBoundLocally);

            if (!conVarsNeedingBinding.isEmpty() && ruleForm.getOperator().filter("=>"::equals).isPresent()) {
                System.err.println("Warning: Rule consequent has unbound variables: "
                        + conVarsNeedingBinding.stream().map(KifVar::name).collect(Collectors.joining(", "))
                        + " in " + ruleForm.toKifString());
            }
        }

        // Helper to find variables bound by quantifiers within a term
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
                case KifList list -> list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
                default -> {}
            }
        }

        @Override public boolean equals(Object o) { return this == o || (o instanceof Rule r && ruleForm.equals(r.ruleForm)); }
        @Override public int hashCode() { return ruleForm.hashCode(); }
    }


    // Represents a potential assertion before commit (used in CommitQueue)
    record PotentialAssertion(
            KifList kif,
            double priority, // Priority for commit/processing
            Set<String> support,
            String sourceId, // Where it came from (e.g., rule ID, input source)
            boolean isEquality,
            boolean isOrientedEquality, // Pre-calculated orientation
            String sourceNoteId // Optional note ID association
    ) {
        PotentialAssertion {
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
            Objects.requireNonNull(sourceId);
        }
        // Equality based on KIF structure for subsumption checks etc.
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && kif.equals(pa.kif); }
        @Override public int hashCode() { return kif.hashCode(); }
    }

    // Represents tasks for the inference workers
    enum TaskType { MATCH_ANTECEDENT, APPLY_ORDERED_REWRITE }

    record InferenceTask(
            TaskType type,
            double priority, // Higher value = higher priority
            Object data // Context-specific data (e.g., Rule + Bindings, Assertion Pair)
    ) implements Comparable<InferenceTask> {
        InferenceTask { Objects.requireNonNull(type); Objects.requireNonNull(data); }
        // Comparison for PriorityBlockingQueue (Max-heap based on priority)
        @Override public int compareTo(InferenceTask other) { return Double.compare(other.priority, this.priority); }

        // Convenience factory methods
        static InferenceTask matchAntecedent(Rule rule, Assertion triggerAssertion, Map<KifVar, KifTerm> initialBindings, double priority) {
            return new InferenceTask(TaskType.MATCH_ANTECEDENT, priority, new MatchContext(rule, triggerAssertion.id(), initialBindings));
        }
        static InferenceTask applyRewrite(Assertion rewriteRule, Assertion targetAssertion, double priority) {
            return new InferenceTask(TaskType.APPLY_ORDERED_REWRITE, priority, new RewriteContext(rewriteRule, targetAssertion));
        }
    }
    // Context record for MATCH_ANTECEDENT tasks
    record MatchContext(Rule rule, String triggerAssertionId, Map<KifVar, KifTerm> initialBindings) {}
    // Context record for APPLY_ORDERED_REWRITE tasks
    record RewriteContext(Assertion rewriteRule, Assertion targetAssertion) {}


    @FunctionalInterface interface KifCallback { void onMatch(String type, Assertion assertion, Map<KifVar, KifTerm> bindings); }
    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
        CallbackRegistration { Objects.requireNonNull(pattern); Objects.requireNonNull(callback); }
    }


    // --- Path Indexing Structures ---
    static class PathNode {
        // Children map: Key is atom value (String), KifVar.class, or a special marker (e.g., LIST_START)
        final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
        // Set of assertion IDs whose path ends at this node
        final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet();

        // Special marker for variables in the index path
        static final Class<KifVar> VAR_MARKER = KifVar.class;
        // Potential future markers for list boundaries if needed
        // static final Object LIST_START_MARKER = new Object();
        // static final Object LIST_END_MARKER = new Object();
    }

    static class PathIndex {
        private final PathNode root = new PathNode();

        // Adds all paths corresponding to the assertion and its subterms
        void add(Assertion assertion) {
            addPathsRecursive(assertion.kif(), assertion.id(), root);
        }

        private void addPathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return; // Safety

            // Add the assertion ID at the node representing the *end* of the current term's path
            currentNode.assertionIdsHere.add(assertionId);

            switch (term) {
                case KifAtom atom -> {
                    var nextNode = currentNode.children.computeIfAbsent(atom.value(), k -> new PathNode());
                    nextNode.assertionIdsHere.add(assertionId); // Add ID at the leaf too
                }
                case KifVar var -> {
                    // Use a class marker for variables
                    var nextNode = currentNode.children.computeIfAbsent(PathNode.VAR_MARKER, k -> new PathNode());
                    nextNode.assertionIdsHere.add(assertionId);
                }
                case KifList list -> {
                    PathNode listNode = currentNode; // Start from current node for list elements
                    // Optionally add LIST_START marker node here if needed for structure disambiguation
                    // listNode = currentNode.children.computeIfAbsent(PathNode.LIST_START_MARKER, k -> new PathNode());
                    // listNode.assertionIdsHere.add(assertionId); // Add ID to list start node

                    for (KifTerm subTerm : list.terms()) {
                        // Determine the key for the next step based on the subTerm type
                        Object key = switch (subTerm) {
                            case KifAtom a -> a.value();
                            case KifVar v -> PathNode.VAR_MARKER;
                            case KifList l -> PathNode.VAR_MARKER; // Treat nested lists like variables for structural matching? Or recurse? Let's recurse fully.
                            // If recurring fully, the logic needs refinement.
                            // For simplicity now, let's just index the top-level structure elements.
                            // A better index might flatten paths like (a (b c) d) -> a -> LIST_START -> b -> c -> LIST_END -> d
                        };
                        // Simplified: Create/get node for the element type/value and recurse
                        PathNode elementNode = listNode.children.computeIfAbsent(key, k -> new PathNode());
                        addPathsRecursive(subTerm, assertionId, elementNode); // Recurse for sub-term
                        // If not recursing fully, the listNode doesn't advance here.
                    }
                    // Optionally add LIST_END marker node here
                }
            }
        }

        // Removes assertion ID from all paths
        void remove(Assertion assertion) {
            removePathsRecursive(assertion.kif(), assertion.id(), root);
        }

        // Recursive removal helper - returns true if a node became empty and can be potentially pruned
        private boolean removePathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return false;

            boolean nodeBecameEmpty = false;
            currentNode.assertionIdsHere.remove(assertionId);

            switch (term) {
                case KifAtom atom -> {
                    PathNode childNode = currentNode.children.get(atom.value());
                    if (childNode != null) {
                        childNode.assertionIdsHere.remove(assertionId); // Remove from leaf too
                        if (removePathsRecursive(term, assertionId, childNode)) { // Recurse (though atom has no children)
                            // If child became empty after deeper removals (not applicable for atoms)
                            // AND it has no IDs itself, remove it.
                            if(childNode.assertionIdsHere.isEmpty() && childNode.children.isEmpty()) {
                                currentNode.children.remove(atom.value(), childNode); // Conditional remove
                            }
                        }
                    }
                }
                case KifVar _ -> {
                    PathNode childNode = currentNode.children.get(PathNode.VAR_MARKER);
                    if (childNode != null) {
                        childNode.assertionIdsHere.remove(assertionId);
                        if (removePathsRecursive(term, assertionId, childNode)) {
                            if(childNode.assertionIdsHere.isEmpty() && childNode.children.isEmpty()) {
                                currentNode.children.remove(PathNode.VAR_MARKER, childNode);
                            }
                        }
                    }
                }
                case KifList list -> {
                    // Similar recursive logic as add, but with removal and pruning
                    // PathNode listNode = currentNode; // Adjust if using LIST markers
                    for (KifTerm subTerm : list.terms()) {
                        Object key = switch (subTerm) {
                            case KifAtom a -> a.value();
                            case KifVar v -> PathNode.VAR_MARKER;
                            case KifList l -> PathNode.VAR_MARKER; // Simplified key
                        };
                        PathNode elementNode = currentNode.children.get(key); // Use current node, not listNode if simplified
                        if (elementNode != null) {
                            if (removePathsRecursive(subTerm, assertionId, elementNode)) {
                                // If the elementNode became empty, remove it from the parent
                                if (elementNode.assertionIdsHere.isEmpty() && elementNode.children.isEmpty()) {
                                    currentNode.children.remove(key, elementNode);
                                }
                            }
                        }
                    }
                }
            }
            // Node considered empty if it has no assertion IDs AND no children
            nodeBecameEmpty = currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
            return nodeBecameEmpty;
        }


        // Finds candidate assertion IDs potentially unifiable with the query term.
        // Traverses index, allowing index VAR_MARKER to match any query subtree,
        // and query variables to match any index subtree.
        Set<String> findUnifiable(KifTerm queryTerm) {
            Set<String> candidates = ConcurrentHashMap.newKeySet();
            findUnifiableRecursive(queryTerm, root, candidates);
            return candidates;
        }

        private void findUnifiableRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;

            // Add all assertions reachable from this index node, as they might unify
            // due to variable matching deeper down or query variables.
            candidates.addAll(indexNode.assertionIdsHere); // Potential match

            // Explore matching children
            switch (queryTerm) {
                case KifAtom queryAtom -> {
                    // Query Atom matches Index Atom
                    PathNode nextNode = indexNode.children.get(queryAtom.value());
                    if (nextNode != null) findUnifiableRecursive(queryAtom, nextNode, candidates); // Should be leaf, but recurse for safety

                    // Query Atom matches Index Variable
                    PathNode varNode = indexNode.children.get(PathNode.VAR_MARKER);
                    if (varNode != null) {
                        // Var marker matches anything, collect all assertions beneath it
                        collectAllAssertionIds(varNode, candidates);
                    }
                }
                case KifVar queryVar -> {
                    // Query Variable matches *any* child branch in the index
                    indexNode.children.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));
                }
                case KifList queryList -> {
                    // Match list structure (simplified: match element by element)
                    PathNode currentQueryNode = indexNode; // Follow path based on query structure

                    // Query List matches Index Variable
                    PathNode varNode = indexNode.children.get(PathNode.VAR_MARKER);
                    if (varNode != null) {
                        collectAllAssertionIds(varNode, candidates);
                    }

                    // Try matching query structure against index structure
                    // This part is complex for full list matching. Simplified:
                    // Match operator first if possible.
                    queryList.getOperator().ifPresent(op -> {
                        PathNode opNode = indexNode.children.get(op);
                        if (opNode != null) {
                            // If operator matches, potentially add all IDs here
                            candidates.addAll(opNode.assertionIdsHere);
                            // TODO: Need more sophisticated list matching logic here
                            // to handle term-by-term unification possibilities.
                            // For now, this is a very coarse filter.
                            // A full implementation would traverse queryList and indexNode children in parallel.
                        }
                    });
                    // As a fallback/broad phase, add all assertions at the current index node
                    candidates.addAll(indexNode.assertionIdsHere);
                }
            }
        }


        // Finds assertion IDs whose terms are instances of the query pattern.
        // Query pattern can have variables. Index terms are ground (or contain skolem constants treated as ground).
        // Traverse index matching query constants, allowing query variables to match any index subtree.
        Set<String> findInstances(KifTerm queryPattern) {
            Set<String> candidates = ConcurrentHashMap.newKeySet();
            findInstancesRecursive(queryPattern, root, candidates);
            return candidates;
        }
        private void findInstancesRecursive(KifTerm queryPattern, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;

            switch (queryPattern) {
                case KifAtom queryAtom -> {
                    // Must match the exact atom in the index
                    PathNode nextNode = indexNode.children.get(queryAtom.value());
                    if (nextNode != null) {
                        candidates.addAll(nextNode.assertionIdsHere); // Found instances ending here
                        // No further recursion needed for atoms
                    }
                }
                case KifVar queryVar -> {
                    // A variable in the query matches anything in the index at this point.
                    // Collect all assertions reachable from the current index node.
                    collectAllAssertionIds(indexNode, candidates);
                }
                case KifList queryList -> {
                    // Match structure: query constants must match index constants. query vars match anything.
                    // Start recursion with the first element of the list.
                    // This needs a list traversal matching algorithm.
                    // Simplified: Check operator match if present, then broadly add candidates.
                    queryList.getOperator().ifPresent(op -> {
                        PathNode opNode = indexNode.children.get(op);
                        if (opNode != null) {
                            // Operator matches, these are potential instances.
                            // Need to recursively check remaining list elements.
                            // Simplified: Add all IDs at the opNode as initial candidates. Refine later.
                            candidates.addAll(opNode.assertionIdsHere);
                            // TODO: Implement proper recursive list matching for findInstances
                        }
                    });
                    // Also consider lists indexed under a variable marker if the query starts with a var or needs structural match
                    PathNode varNode = indexNode.children.get(PathNode.VAR_MARKER);
                    if (varNode != null) {
                        // TODO: Recurse/match queryList against structures under varNode
                        candidates.addAll(varNode.assertionIdsHere); // Add as potential candidates
                    }
                }
            }
        }


        // Finds assertion IDs whose terms are generalizations of the query term.
        // Query term is typically ground. Index terms can have variables.
        // Traverse index allowing index VAR_MARKER to match query constants/subtrees.
        Set<String> findGeneralizations(KifTerm queryTerm) {
            Set<String> candidates = ConcurrentHashMap.newKeySet();
            findGeneralizationsRecursive(queryTerm, root, candidates);
            return candidates;
        }

        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;

            // Generalizations can be found at variable markers in the index path
            PathNode varNode = indexNode.children.get(PathNode.VAR_MARKER);
            if (varNode != null) {
                // Assertions ending at VAR_MARKER are generalizations of anything at this level
                candidates.addAll(varNode.assertionIdsHere);
                // We might need to continue recursion down the varNode path depending on the queryTerm structure,
                // but for subsumption, ending at a variable often suffices.
                findGeneralizationsRecursive(queryTerm, varNode, candidates); // Continue search down the var branch too
            }

            // Also check for matching structure
            switch (queryTerm) {
                case KifAtom queryAtom -> {
                    // Match the specific atom value
                    PathNode nextNode = indexNode.children.get(queryAtom.value());
                    if (nextNode != null) {
                        candidates.addAll(nextNode.assertionIdsHere); // Add assertions ending exactly here
                        findGeneralizationsRecursive(queryAtom, nextNode, candidates); // Recurse (though atom is leaf)
                    }
                }
                case KifVar ignored -> {
                    // Should not happen for ground queryTerm in subsumption check typically.
                    // If query has variables, generalization finding is complex.
                }
                case KifList queryList -> {
                    // Match list structure element by element
                    // TODO: Implement recursive list matching for findGeneralizations
                    // Simplified: Check operator match
                    queryList.getOperator().ifPresent(op -> {
                        PathNode opNode = indexNode.children.get(op);
                        if (opNode != null) {
                            candidates.addAll(opNode.assertionIdsHere); // Add exact matches
                            // Recurse into sub-structure matching
                            if (queryList.size() > 1 && opNode.children.size() > 0) {
                                // Example: Match first argument
                                // findGeneralizationsRecursive(queryList.get(1), opNode, candidates); // Needs proper state/traversal
                            }
                        }
                    });
                    // Add assertions ending at the current node as potential matches (e.g., variable matching whole list)
                    candidates.addAll(indexNode.assertionIdsHere);
                }
            }
        }


        // Helper to collect all assertion IDs from a node and its descendants
        private void collectAllAssertionIds(PathNode node, Set<String> ids) {
            if (node == null) return;
            ids.addAll(node.assertionIdsHere);
            node.children.values().forEach(child -> collectAllAssertionIds(child, ids));
        }

        // Clears the entire index
        void clear() {
            root.children.clear();
            root.assertionIdsHere.clear();
        }
    }


    // --- Knowledge Base ---
    static class KnowledgeBase {
        private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
        private final PathIndex pathIndex = new PathIndex();
        private final PriorityBlockingQueue<Assertion> evictionQueue = new PriorityBlockingQueue<>(); // Min-heap by priority
        private final ReadWriteLock kbCommitLock = new ReentrantReadWriteLock();
        private final int maxKbSize;
        private final Consumer<Assertion> evictionCallback; // Callback when an assertion is evicted

        KnowledgeBase(int maxKbSize, Consumer<Assertion> evictionCallback) {
            this.maxKbSize = maxKbSize;
            this.evictionCallback = Objects.requireNonNull(evictionCallback);
        }

        // Commits a new assertion derived from a PotentialAssertion
        Optional<Assertion> commitAssertion(PotentialAssertion pa, String newId, long timestamp) {
            kbCommitLock.writeLock().lock();
            try {
                // 1. Create the final Assertion object
                var newAssertion = new Assertion(newId, pa.kif(), pa.priority(), timestamp,
                        pa.sourceNoteId(), pa.support(), pa.isEquality(), pa.isOrientedEquality());

                // 2. Check if an identical assertion already exists (via PathIndex - heuristic)
                // A more robust check might involve full unification/matching, but index check is faster.
                // If findInstances returns non-empty for the ground fact, it likely exists.
                if (!newAssertion.kif().containsVariable() && !pathIndex.findInstances(newAssertion.kif()).isEmpty()) {
                    Assertion existing = findExactMatch(newAssertion.kif());
                    if (existing != null) {
                        // Found exact match. Optionally update priority if new one is higher?
                        // System.out.println("KB Commit skipped (exact match exists): " + newAssertion.toKifString());
                        return Optional.empty();
                    }
                }

                // 3. Enforce KB capacity *before* adding
                enforceKbCapacity();
                if (assertionsById.size() >= maxKbSize) {
                    System.err.printf("Warning: KB full (%d/%d), cannot add: %s%n", assertionsById.size(), maxKbSize, pa.kif().toKifString());
                    return Optional.empty();
                }

                // 4. Add to primary map
                if (assertionsById.putIfAbsent(newId, newAssertion) != null) {
                    System.err.println("KB Commit Error: ID collision for " + newId + ". Assertion not added.");
                    return Optional.empty(); // ID collision, should be rare
                }

                // 5. Add to secondary structures (index, eviction queue)
                pathIndex.add(newAssertion);
                evictionQueue.put(newAssertion);

                // System.out.println("KB Commit: " + newId + " Prio=" + newAssertion.priority() + " " + newAssertion.toKifString());
                return Optional.of(newAssertion);

            } finally {
                kbCommitLock.writeLock().unlock();
            }
        }

        // Retracts an assertion by ID
        Assertion retractAssertion(String id) {
            kbCommitLock.writeLock().lock();
            try {
                Assertion removedAssertion = assertionsById.remove(id);
                if (removedAssertion != null) {
                    pathIndex.remove(removedAssertion);
                    evictionQueue.remove(removedAssertion);
                    // System.out.println("KB Retract: " + id + " " + removedAssertion.toKifString());
                }
                return removedAssertion;
            } finally {
                kbCommitLock.writeLock().unlock();
            }
        }

        // --- Read Operations (Use Read Lock) ---

        Optional<Assertion> getAssertion(String id) {
            kbCommitLock.readLock().lock();
            try {
                return Optional.ofNullable(assertionsById.get(id));
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        // Check if a PotentialAssertion is subsumed by any existing assertion
        boolean isSubsumed(PotentialAssertion pa) {
            kbCommitLock.readLock().lock();
            try {
                // Find potential generalizers in the index
                Set<String> candidateIds = pathIndex.findGeneralizations(pa.kif());
                if (candidateIds.isEmpty()) return false; // No potential generalizers

                // Perform unification check (match pattern=generalizer, term=potentialAssertion)
                return candidateIds.stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull)
                        // Check if candidate.kif() can be matched to pa.kif()
                        .anyMatch(candidate -> Unifier.match(candidate.kif(), pa.kif(), Map.of()) != null);
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        // Find assertions potentially unifiable with a query term
        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) {
            kbCommitLock.readLock().lock();
            try {
                // Use PathIndex first to get candidate IDs
                Set<String> candidateIds = pathIndex.findUnifiable(queryTerm);
                // Retrieve Assertion objects and return as stream
                // Need to copy IDs to avoid holding lock during stream processing if lazy
                List<String> ids = new ArrayList<>(candidateIds);
                return ids.stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull);
            } finally {
                kbCommitLock.readLock().unlock();
            }
            // Note: Consider returning List<Assertion> if stream laziness + locking is problematic
        }

        // Find assertions that are instances of a query pattern (e.g., for rewrite LHS)
        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            kbCommitLock.readLock().lock();
            try {
                Set<String> candidateIds = pathIndex.findInstances(queryPattern);
                List<String> ids = new ArrayList<>(candidateIds); // Copy IDs
                return ids.stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull)
                        // Add a final check: the retrieved assertion must actually be an instance
                        .filter(a -> Unifier.match(queryPattern, a.kif(), Map.of()) != null);
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        // Find an exact match for a ground KIF list
        Assertion findExactMatch(KifList groundKif) {
            kbCommitLock.readLock().lock();
            try {
                // Use findInstances with the ground KIF - should return only exact matches if index is correct
                Set<String> candidateIds = pathIndex.findInstances(groundKif);
                return candidateIds.stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull)
                        .filter(a -> a.kif().equals(groundKif)) // Final precise check
                        .findFirst()
                        .orElse(null);
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }


        // Get all assertions (for clearing, etc.) - requires write lock if modifying outside
        List<Assertion> getAllAssertions() {
            kbCommitLock.readLock().lock();
            try {
                return List.copyOf(assertionsById.values());
            } finally {
                kbCommitLock.readLock().unlock();
            }
        }

        int getAssertionCount() {
            return assertionsById.size(); // ConcurrentHashMap size is approximate but okay for status
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

        // --- Private Helper ---
        private void enforceKbCapacity() {
            // Assumes write lock is already held
            while (assertionsById.size() >= maxKbSize && !evictionQueue.isEmpty()) {
                Assertion lowestPriorityAssertion = evictionQueue.poll();
                if (lowestPriorityAssertion == null) break;

                // Remove *only if* it's still present (might have been retracted concurrently before lock)
                Assertion actuallyRemoved = assertionsById.remove(lowestPriorityAssertion.id());
                if (actuallyRemoved != null) {
                    pathIndex.remove(actuallyRemoved);
                    // System.out.println("KB Evict (Low Prio): " + actuallyRemoved.id() + " Prio=" + actuallyRemoved.priority());
                    evictionCallback.accept(actuallyRemoved); // Notify about eviction
                }
            }
        }
    }

    // --- Reasoner Engine ---
    static class ReasonerEngine {
        private final KnowledgeBase kb;
        private final Supplier<String> idGenerator; // Function to generate IDs (e.g., "fact-123")
        private final BiConsumer<String, Assertion> callbackNotifier; // Function to notify CogNote about committed assertions

        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<PotentialAssertion> commitQueue = new LinkedBlockingQueue<>(10000); // Bounded queue
        private final PriorityBlockingQueue<InferenceTask> taskQueue = new PriorityBlockingQueue<>(1000, Comparator.comparingDouble(InferenceTask::priority).reversed()); // Max-heap

        private final ExecutorService commitExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "CommitThread"));
        private final ExecutorService inferenceExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Use virtual threads for workers

        private volatile boolean running = false;
        private volatile boolean paused = false;
        private final Object pauseLock = new Object();
        private volatile String currentStatus = "Idle";


        ReasonerEngine(KnowledgeBase kb, Supplier<String> idGenerator, BiConsumer<String, Assertion> callbackNotifier) {
            this.kb = Objects.requireNonNull(kb);
            this.idGenerator = Objects.requireNonNull(idGenerator);
            this.callbackNotifier = Objects.requireNonNull(callbackNotifier);
        }

        void start() {
            if (running) return;
            running = true;
            paused = false;
            currentStatus = "Starting";
            commitExecutor.submit(this::commitLoop);
            // Start a pool of inference workers
            int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2); // Adjust as needed
            for (int i = 0; i < workerCount; i++) {
                inferenceExecutor.submit(this::inferenceLoop);
            }
            currentStatus = "Running";
            System.out.println("Reasoner Engine started with " + workerCount + " inference workers.");
        }

        void stop() {
            if (!running) return;
            running = false;
            paused = false; // Ensure pause doesn't block shutdown
            synchronized (pauseLock) { pauseLock.notifyAll(); } // Wake up threads

            currentStatus = "Stopping";
            // Shutdown executors gracefully
            shutdownExecutor(commitExecutor, "Commit Executor");
            shutdownExecutor(inferenceExecutor, "Inference Executor");
            currentStatus = "Stopped";
            System.out.println("Reasoner Engine stopped.");
        }

        void setPaused(boolean pause) {
            if (this.paused == pause) return;
            this.paused = pause;
            if (!pause) {
                synchronized (pauseLock) { pauseLock.notifyAll(); }
            }
            currentStatus = pause ? "Paused" : "Running";
        }

        void clear() {
            // Assumes KB is cleared separately by CogNote.clearAllKnowledge
            rules.clear();
            commitQueue.clear();
            taskQueue.clear();
            System.out.println("Reasoner Engine queues and rules cleared.");
        }

        // Add assertion to the commit queue
        void submitPotentialAssertion(PotentialAssertion pa) {
            if (!running) {
                System.err.println("Warning: Reasoner not running, ignoring potential assertion: " + pa.kif().toKifString());
                return;
            }
            try {
                commitQueue.put(pa); // Use put for blocking if queue is full
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while submitting potential assertion.");
            }
        }

        // Add a rule (thread-safe)
        void addRule(Rule rule) {
            if (rules.add(rule)) {
                System.out.println("Added rule: " + rule.id() + " " + rule.ruleForm().toKifString());
                // Trigger matching against existing facts for the new rule
                triggerMatchingForNewRule(rule);
            } else {
                System.out.println("Rule already exists (not added): " + rule.ruleForm().toKifString());
            }
        }

        // Remove a rule by its KIF form (thread-safe)
        boolean removeRule(KifList ruleForm) {
            return rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));
        }

        long getTaskQueueSize() { return taskQueue.size(); }
        long getCommitQueueSize() { return commitQueue.size(); }
        int getRuleCount() { return rules.size(); }
        String getCurrentStatus() { return currentStatus; }


        // --- Commit Service Loop ---
        private void commitLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (pauseLock) { while (paused && running) pauseLock.wait(500); }
                    if (!running) break;

                    PotentialAssertion pa = commitQueue.take(); // Blocks until item available
                    currentStatus = "Committing";

                    // 1. Subsumption Check
                    if (!kb.isSubsumed(pa)) {
                        // 2. Commit to KB
                        Optional<Assertion> committedAssertionOpt = kb.commitAssertion(pa, generateFactId(pa), System.currentTimeMillis());

                        if (committedAssertionOpt.isPresent()) {
                            Assertion committedAssertion = committedAssertionOpt.get();

                            // 3. Trigger Callbacks
                            callbackNotifier.accept("assert-added", committedAssertion);

                            // 4. Generate New Tasks based on the committed assertion
                            generateNewTasks(committedAssertion);
                        }
                        // else: Commit failed (duplicate, KB full, etc.) - logged by KB
                    } else {
                        // System.out.println("Commit skipped (subsumed): " + pa.kif().toKifString());
                    }
                    currentStatus = "Idle (Commit)"; // Reset status after processing

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Commit thread interrupted.");
                } catch (Exception e) {
                    System.err.println("Error in commit loop: " + e.getMessage());
                    e.printStackTrace();
                    currentStatus = "Error (Commit)";
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } // Avoid rapid error loops
                }
            }
            System.out.println("Commit thread finished.");
        }

        // --- Inference Worker Loop ---
        private void inferenceLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (pauseLock) { while (paused && running) pauseLock.wait(500); }
                    if (!running) break;

                    InferenceTask task = taskQueue.take(); // Blocks until task available
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
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            System.out.println("Inference worker finished.");
        }

        // --- Task Handling Logic ---

        private void handleMatchAntecedentTask(InferenceTask task) {
            if (!(task.data() instanceof MatchContext ctx)) return;

            // Find matches for remaining antecedents recursively/iteratively
            findMatchesRecursive(
                    ctx.rule(),
                    ctx.rule().antecedents(), // Start with all antecedents
                    ctx.initialBindings(),
                    Set.of(ctx.triggerAssertionId()) // Initial support
            ).forEach(matchResult -> {
                // Successful match found for all antecedents
                var consequentTerm = Unifier.substitute(ctx.rule().consequent(), matchResult.bindings());

                if (consequentTerm instanceof KifList derived && !derived.containsVariable()) {
                    // Calculate priority for the potential assertion
                    double derivedPriority = calculateDerivedPriority(matchResult.supportIds(), ctx.rule().priority());
                    String commonNoteId = findCommonSourceNodeId(matchResult.supportIds());

                    // Create PotentialAssertion and submit to commit queue
                    var pa = new PotentialAssertion(derived, derivedPriority, matchResult.supportIds(), ctx.rule().id(), false, false, commonNoteId);
                    submitPotentialAssertion(pa);
                } else {
                    // System.err.println("Warning: Rule consequent did not ground or not a list: " + consequentTerm.toKifString());
                }
            });
        }

        // Recursive helper for matching rule antecedents
        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifList> remainingClauses, Map<KifVar, KifTerm> currentBindings, Set<String> currentSupportIds) {
            // Base case: No more clauses to match
            if (remainingClauses.isEmpty()) {
                return Stream.of(new MatchResult(currentBindings, currentSupportIds));
            }

            var clauseToMatch = remainingClauses.getFirst();
            var nextRemainingClauses = remainingClauses.subList(1, remainingClauses.size());

            var substitutedClauseTerm = Unifier.fullySubstitute(clauseToMatch, currentBindings);

            if (!(substitutedClauseTerm instanceof KifList substitutedClause)) {
                System.err.println("Warning: Antecedent clause reduced to non-list: " + substitutedClauseTerm.toKifString());
                return Stream.empty();
            }

            // Find candidate assertions from KB using the index (read lock acquired by KB method)
            Stream<Assertion> candidateAssertions = kb.findUnifiableAssertions(substitutedClause);

            // Process candidates
            return candidateAssertions.flatMap(candidate -> {
                // Try to unify the (potentially bound) clause with the candidate assertion
                Map<KifVar, KifTerm> newBindings = Unifier.unify(substitutedClause, candidate.kif(), currentBindings);

                if (newBindings != null) {
                    // If unification succeeds, create new support set and recurse
                    Set<String> nextSupport = new HashSet<>(currentSupportIds);
                    nextSupport.add(candidate.id());
                    // Check depth/cycle? Optional.

                    // Recurse for the rest of the clauses
                    return findMatchesRecursive(rule, nextRemainingClauses, newBindings, nextSupport);
                } else {
                    return Stream.empty(); // Unification failed
                }
            });
        }


        private void handleApplyRewriteTask(InferenceTask task) {
            if (!(task.data() instanceof RewriteContext ctx)) return;

            Assertion rewriteRule = ctx.rewriteRule(); // This is the (= A B) assertion, oriented
            Assertion targetAssertion = ctx.targetAssertion();

            // Ensure the rule is an oriented equality
            if (!rewriteRule.isEquality() || !rewriteRule.isOrientedEquality() || rewriteRule.kif().size() != 3) {
                System.err.println("Warning: Invalid rewrite rule passed to task: " + rewriteRule.id());
                return;
            }

            KifTerm lhsPattern = rewriteRule.kif().get(1); // Heavier term (the pattern to find)
            KifTerm rhsTemplate = rewriteRule.kif().get(2); // Lighter term (the replacement)

            // Use Unifier.rewrite to find matches and apply rewrite
            Optional<KifTerm> rewrittenTermOpt = Unifier.rewrite(targetAssertion.kif(), lhsPattern, rhsTemplate);

            rewrittenTermOpt.ifPresent(rewrittenTerm -> {
                if (rewrittenTerm instanceof KifList rewrittenList && !rewrittenList.equals(targetAssertion.kif())) {
                    // Successfully rewritten to a new KIF list
                    Set<String> support = new HashSet<>(targetAssertion.support());
                    support.add(targetAssertion.id());
                    support.add(rewriteRule.id());

                    double derivedPriority = calculateDerivedPriority(support, task.priority()); // Use task priority as base
                    String commonNoteId = findCommonSourceNodeId(support);

                    // Decide if the result is an equality itself (usually not from rewrite)
                    boolean resultIsEq = rewrittenList.getOperator().filter("="::equals).isPresent();

                    var pa = new PotentialAssertion(rewrittenList, derivedPriority, support, rewriteRule.id(), resultIsEq, false, commonNoteId);
                    submitPotentialAssertion(pa);
                    // System.out.println("Rewrite Applied: " + targetAssertion.kif().toKifString() + " -> " + rewrittenList.toKifString());
                }
            });
        }

        // --- Task Generation ---
        private void generateNewTasks(Assertion newAssertion) {
            // 1. Trigger rule matching (MATCH_ANTECEDENT tasks)
            rules.forEach(rule -> {
                rule.antecedents().forEach(antecedentClause -> {
                    // Optimization: Check if operator matches if both have one
                    boolean operatorMatch = antecedentClause.getOperator()
                            .map(op -> op.equals(newAssertion.kif().getOperator().orElse(null)))
                            .orElse(true); // If one has no operator, proceed

                    if (operatorMatch) {
                        // Attempt initial unification
                        Map<KifVar, KifTerm> initialBindings = Unifier.unify(antecedentClause, newAssertion.kif(), Map.of());
                        if (initialBindings != null) {
                            // If initial match, create task to find matches for remaining clauses
                            double taskPriority = calculateTaskPriority(rule.priority(), newAssertion.priority());
                            taskQueue.put(InferenceTask.matchAntecedent(rule, newAssertion, initialBindings, taskPriority));
                        }
                    }
                });
            });

            // 2. Trigger rewrite rule application (APPLY_ORDERED_REWRITE tasks)
            if (newAssertion.isEquality() && newAssertion.isOrientedEquality()) {
                // The new assertion IS a rewrite rule. Find existing assertions it might apply to.
                KifTerm lhsPattern = newAssertion.kif().get(1);
                // Find assertions in KB that are instances of the LHS pattern
                kb.findInstancesOf(lhsPattern).forEach(targetAssertion -> {
                    if (!targetAssertion.id().equals(newAssertion.id())) { // Don't rewrite itself
                        double taskPriority = calculateTaskPriority(newAssertion.priority(), targetAssertion.priority());
                        taskQueue.put(InferenceTask.applyRewrite(newAssertion, targetAssertion, taskPriority));
                    }
                });
            } else {
                // The new assertion is a standard fact. Find rewrite rules that might apply TO it.
                // Find all oriented equality assertions in KB
                kb.getAllAssertions().stream() // TODO: Optimize this - needs index for equalities
                        .filter(Assertion::isEquality)
                        .filter(Assertion::isOrientedEquality)
                        .forEach(rewriteRule -> {
                            KifTerm lhsPattern = rewriteRule.kif().get(1);
                            // Check if the new assertion is an instance of the rule's LHS
                            if (Unifier.match(lhsPattern, newAssertion.kif(), Map.of()) != null) {
                                double taskPriority = calculateTaskPriority(rewriteRule.priority(), newAssertion.priority());
                                taskQueue.put(InferenceTask.applyRewrite(rewriteRule, newAssertion, taskPriority));
                            }
                        });
            }
        }

        // Trigger matching for a newly added rule against existing KB facts
        private void triggerMatchingForNewRule(Rule newRule) {
            System.out.println("Triggering matching for new rule: " + newRule.id());
            // Iterate through all assertions in the KB
            kb.getAllAssertions().forEach(existingAssertion -> {
                // For each antecedent clause in the new rule...
                newRule.antecedents().forEach(antecedentClause -> {
                    // Check if the existing assertion unifies with this clause
                    Map<KifVar, KifTerm> initialBindings = Unifier.unify(antecedentClause, existingAssertion.kif(), Map.of());
                    if (initialBindings != null) {
                        // Create a task to find matches for the *remaining* clauses
                        double taskPriority = calculateTaskPriority(newRule.priority(), existingAssertion.priority());
                        taskQueue.put(InferenceTask.matchAntecedent(newRule, existingAssertion, initialBindings, taskPriority));
                    }
                });
            });
        }


        // --- Helpers ---
        private String generateFactId(PotentialAssertion pa) {
            // Simple ID generation, could be enhanced
            return "fact-" + idGenerator.get();
        }

        private double calculateDerivedPriority(Set<String> supportIds, double basePriority) {
            // Example: Geometric mean of support priorities times base priority (e.g., rule priority)
            // Or Min priority * decay^depth?
            if (supportIds.isEmpty()) return basePriority; // Input assertion?

            double minSupportPriority = supportIds.stream()
                    .map(kb::getAssertion) // Fetch assertion from KB
                    .filter(Optional::isPresent)
                    .mapToDouble(opt -> opt.get().priority())
                    .min()
                    .orElse(basePriority); // Default to base if support not found (shouldn't happen ideally)

            // Simple heuristic: slightly lower priority than minimum supporter
            return minSupportPriority * 0.95;
        }

        private double calculateTaskPriority(double p1, double p2) {
            // Heuristic for task priority, e.g., average or max of related assertion/rule priorities
            return (p1 + p2) / 2.0;
        }

        private String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null;
            boolean first = true;
            Queue<String> toCheck = new LinkedList<>(supportIds);
            Set<String> visited = new HashSet<>(supportIds);

            while(!toCheck.isEmpty()) {
                String currentId = toCheck.poll();
                var assertionOpt = kb.getAssertion(currentId);
                if (assertionOpt.isPresent()) {
                    Assertion assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (first) {
                            commonId = assertion.sourceNoteId();
                            first = false;
                        } else if (!commonId.equals(assertion.sourceNoteId())) {
                            return null; // Diverged
                        }
                    } else if (!assertion.support().isEmpty() && Collections.disjoint(assertion.support(), visited)) {
                        // If no note ID here, explore its support if not already visited
                        assertion.support().forEach(supId -> {
                            if (visited.add(supId)) {
                                toCheck.offer(supId);
                            }
                        });
                    } else if (assertion.support().isEmpty() && !first) {
                        // Reached a root assertion without a note ID, and we already had a common ID
                        return null; // Diverged
                    }
                } else {
                    // Support assertion not found (e.g., retracted) - cannot determine common source
                    return null;
                }
            }
            return commonId; // Return the common ID found, or null if none or diverged
        }


        // Helper record for findMatches results
        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {}
    }


    // --- KIF Parser (Mostly Unchanged) ---
    static class KifParser {
        private final Reader reader;
        private int currentChar = -2;
        private int line = 1;
        private int col = 0;

        private KifParser(Reader reader) { this.reader = reader; }

        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var reader = new StringReader(input.trim())) {
                return new KifParser(reader).parseTopLevel();
            } catch (IOException e) { throw new ParseException("Read error: " + e.getMessage()); }
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
                if (c == ')') { consumeChar(')'); return new KifList(terms); }
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
                        case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                        case '\\', '"' -> next;
                        default -> {
                            System.err.println("Warning: Unknown escape sequence '\\" + (char) next + "' at line " + line + ", col " + (col -1));
                            yield next;
                        }
                    });
                } else { sb.append((char) c); }
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
            } return c;
        }
        private void consumeChar(char expected) throws IOException, ParseException {
            int actual = consumeChar();
            if (actual != expected) throw createParseException("Expected '" + expected + "' but found '" + (actual == -1 ? "EOF" : (char) actual) + "'");
        }
        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                int c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) consumeChar();
                else if (c == ';') {
                    while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                    if (peek() == '\n' || peek() == '\r') consumeChar();
                } else break;
            }
        }
        private ParseException createParseException(String message) { return new ParseException(message + " at line " + line + " col " + col); }
    }
    static class ParseException extends Exception { ParseException(String message) { super(message); } }


    // --- Unification & Rewriting Logic ---
    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 50;

        // Standard unification: Find Most General Unifier (MGU)
        static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            return unifyRecursive(x, y, bindings);
        }

        // Matching: Check if term is an instance of pattern (only bind variables in pattern)
        static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            return matchRecursive(pattern, term, bindings);
        }

        // Substitution: Apply bindings to a term
        static KifTerm substitute(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return fullySubstitute(term, bindings);
        }

        // Rewriting: Find instance of lhsPattern in target, replace with rhsTemplate applying bindings
        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            return rewriteRecursive(target, lhsPattern, rhsTemplate);
        }


        // --- Recursive Implementations ---

        private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null;
            var xSubst = fullySubstitute(x, bindings);
            var ySubst = fullySubstitute(y, bindings);

            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true); // Check occurs in unify
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true); // Check occurs in unify
            if (xSubst instanceof KifList listX && ySubst instanceof KifList listY) {
                if (listX.size() != listY.size()) return null;
                var currentBindings = bindings;
                for (int i = 0; i < listX.size(); i++) {
                    currentBindings = unifyRecursive(listX.get(i), listY.get(i), currentBindings);
                    if (currentBindings == null) return null;
                } return currentBindings;
            }
            return null; // Mismatch
        }

        private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null;
            // Note: We substitute the pattern based on bindings found so far, but NOT the term.
            var patternSubst = fullySubstitute(pattern, bindings);

            if (patternSubst instanceof KifVar varP) {
                // Bind variable in pattern to the term (no occurs check needed for matching)
                return bindVariable(varP, term, bindings, false);
            }
            if (patternSubst.equals(term)) return bindings; // Exact match after substitution

            if (patternSubst instanceof KifList listP && term instanceof KifList listT) {
                if (listP.size() != listT.size()) return null;
                var currentBindings = bindings;
                for (int i = 0; i < listP.size(); i++) {
                    currentBindings = matchRecursive(listP.get(i), listT.get(i), currentBindings);
                    if (currentBindings == null) return null;
                } return currentBindings;
            }
            return null; // Mismatch (atom vs list, different atoms, etc.)
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
                    for (KifTerm subTerm : list.terms()) {
                        KifTerm substitutedSubTerm = substituteOnce(subTerm, bindings);
                        if (!Objects.equals(substitutedSubTerm, subTerm)) changed = true; // Use Objects.equals for safety
                        newTerms.add(substitutedSubTerm);
                    }
                    yield changed ? new KifList(newTerms) : list;
                }
            };
        }

        private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean checkOccurs) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var)) {
                // Var already bound: unify/match existing binding with new value
                return checkOccurs ? unifyRecursive(bindings.get(var), value, bindings) : matchRecursive(bindings.get(var), value, bindings);
            }

            // Follow binding chain for value if it's a bound variable
            var finalValue = value;
            if (value instanceof KifVar valueVar && bindings.containsKey(valueVar)) {
                finalValue = fullySubstitute(valueVar, bindings);
            }

            if (checkOccurs && occursCheck(var, finalValue, bindings)) return null;

            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheck(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings) {
            var substTerm = fullySubstitute(term, bindings); // Check against fully substituted term
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheck(var, t, bindings));
                case KifAtom ignored -> false;
            };
        }

        // --- Rewriting ---
        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            // 1. Try matching the pattern at the current target level
            Map<KifVar, KifTerm> matchBindings = match(lhsPattern, target, Map.of());
            if (matchBindings != null) {
                // Match found! Apply bindings to the RHS template
                KifTerm rewritten = fullySubstitute(rhsTemplate, matchBindings);
                return Optional.of(rewritten);
            }

            // 2. If no match at this level and target is a list, try rewriting subterms
            if (target instanceof KifList targetList) {
                boolean changed = false;
                List<KifTerm> newSubTerms = new ArrayList<>(targetList.size());
                for (KifTerm subTerm : targetList.terms()) {
                    Optional<KifTerm> rewrittenSubTermOpt = rewriteRecursive(subTerm, lhsPattern, rhsTemplate);
                    if (rewrittenSubTermOpt.isPresent()) {
                        changed = true;
                        newSubTerms.add(rewrittenSubTermOpt.get());
                    } else {
                        newSubTerms.add(subTerm); // Keep original subterm
                    }
                }
                // Return new list only if a subterm was changed
                return changed ? Optional.of(new KifList(newSubTerms)) : Optional.empty();
            }

            // 3. Target is an atom or variable, and didn't match pattern -> cannot rewrite
            return Optional.empty();
        }
    }


    // --- Swing UI Inner Class (Adapted) ---
    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing...");
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        final JTextArea derivationView = new JTextArea(); // Made final
        private final JButton addButton = new JButton("Add Note");
        private final JButton removeButton = new JButton("Remove Note");
        private final JButton analyzeButton = new JButton("Analyze Note");
        private final JButton resolveButton = new JButton("Resolve..."); // Kept button, but functionality removed/changed
        private final JButton pauseResumeButton = new JButton("Pause");
        private final JButton clearAllButton = new JButton("Clear All");
        private CogNote reasoner;
        private Note currentNote = null;
        final Map<String, String> derivationTextCache = new ConcurrentHashMap<>(); // Made final

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
                @Override public void windowClosing(WindowEvent e) {
                    System.out.println("UI closing event detected.");
                    saveCurrentNoteText();
                    if (SwingUI.this.reasoner != null) SwingUI.this.reasoner.stopReasoner();
                    dispose();
                    System.exit(0);
                }
            });
            updateUIForSelection();
            resolveButton.setEnabled(false); // Disable resolve button initially (no functionality)
            resolveButton.setToolTipText("Mismatch resolution feature removed/rethinking required.");
        }

        void setReasoner(CogNote reasoner) { this.reasoner = reasoner; }

        private void setupFonts() {
            noteList.setFont(UI_DEFAULT_FONT); noteEditor.setFont(UI_DEFAULT_FONT);
            derivationView.setFont(MONOSPACED_FONT); addButton.setFont(UI_DEFAULT_FONT);
            removeButton.setFont(UI_DEFAULT_FONT); analyzeButton.setFont(UI_DEFAULT_FONT);
            resolveButton.setFont(UI_DEFAULT_FONT); pauseResumeButton.setFont(UI_DEFAULT_FONT);
            clearAllButton.setFont(UI_DEFAULT_FONT); statusLabel.setFont(UI_DEFAULT_FONT);
        }

        private void setupComponents() {
            noteEditor.setLineWrap(true); noteEditor.setWrapStyleWord(true);
            derivationView.setEditable(false); derivationView.setLineWrap(true); derivationView.setWrapStyleWord(true);
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteList.setCellRenderer(new NoteListCellRenderer());
            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        }

        private void setupLayout() {
            var derivationScrollPane = new JScrollPane(derivationView);
            var editorScrollPane = new JScrollPane(noteEditor);
            var leftScrollPane = new JScrollPane(noteList); leftScrollPane.setPreferredSize(new Dimension(250, 0));
            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScrollPane, derivationScrollPane); rightSplit.setResizeWeight(0.65);
            var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightSplit); mainSplit.setResizeWeight(0.20);
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
            buttonPanel.add(addButton); buttonPanel.add(removeButton); buttonPanel.add(analyzeButton);
            // buttonPanel.add(resolveButton); // Keep resolve button hidden/disabled for now
            buttonPanel.add(pauseResumeButton); buttonPanel.add(clearAllButton);
            var bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(buttonPanel, BorderLayout.WEST); bottomPanel.add(statusLabel, BorderLayout.CENTER); bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            getContentPane().setLayout(new BorderLayout());
            getContentPane().add(mainSplit, BorderLayout.CENTER); getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupListeners() {
            addButton.addActionListener(this::addNote);
            removeButton.addActionListener(this::removeNote);
            analyzeButton.addActionListener(this::analyzeNote);
            // resolveButton listener removed
            pauseResumeButton.addActionListener(this::togglePause);
            clearAllButton.addActionListener(this::clearAll);
            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    saveCurrentNoteText(); currentNote = noteList.getSelectedValue(); updateUIForSelection();
                }
            });
            noteEditor.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent evt) { saveCurrentNoteText(); }
            });
        }

        private void saveCurrentNoteText() {
            if (currentNote != null && noteEditor.isEnabled()) {
                var newText = noteEditor.getText();
                if (!newText.equals(currentNote.text)) { currentNote.text = newText; }
            }
        }

        private void updateUIForSelection() {
            boolean noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected); removeButton.setEnabled(noteSelected); analyzeButton.setEnabled(noteSelected);
            // resolveButton.setEnabled(noteSelected); // Keep disabled
            pauseResumeButton.setEnabled(reasoner != null); clearAllButton.setEnabled(reasoner != null);
            if (noteSelected) {
                noteEditor.setText(currentNote.text); noteEditor.setCaretPosition(0);
                displayCachedDerivations(currentNote);
                setTitle("Cognote - " + currentNote.title);
            } else {
                noteEditor.setText(""); derivationView.setText("");
                setTitle("Cognote - Concurrent KIF Reasoner");
            }
            noteEditor.requestFocusInWindow();
        }

        private void addNote(ActionEvent e) {
            String title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) {
                var noteId = "note-" + UUID.randomUUID();
                var newNote = new Note(noteId, title.trim(), "");
                noteListModel.addElement(newNote);
                noteList.setSelectedValue(newNote, true);
                noteEditor.requestFocusInWindow();
            }
        }

        private void removeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            int confirm = JOptionPane.showConfirmDialog(this, "Remove note '" + currentNote.title + "' and retract associated assertions?", "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                statusLabel.setText("Status: Removing '" + currentNote.title + "'...");
                setButtonsEnabled(false);
                var noteToRemove = currentNote;
                reasoner.processRetractionByNoteIdInput(noteToRemove.id, "UI-Remove"); // Use new method
                derivationTextCache.remove(noteToRemove.id);
                SwingUtilities.invokeLater(() -> {
                    int idx = noteList.getSelectedIndex();
                    noteListModel.removeElement(noteToRemove);
                    if (!noteListModel.isEmpty()) noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
                    else { currentNote = null; updateUIForSelection(); }
                    setButtonsEnabled(true);
                    statusLabel.setText("Status: Removed '" + noteToRemove.title + "'.");
                });
            }
        }

        private void analyzeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;
            reasoner.reasonerStatus = "Analyzing Note: " + currentNote.title; // Use internal status field
            reasoner.updateUIStatus();
            setButtonsEnabled(false);
            saveCurrentNoteText();
            final var noteToAnalyze = currentNote;
            final var noteTextToAnalyze = noteToAnalyze.text;
            derivationView.setText("Analyzing Note '" + noteToAnalyze.title + "' via LLM...\n--------------------\n");

            // 1. Retract existing assertions for this note
            reasoner.processRetractionByNoteIdInput(noteToAnalyze.id, "UI-Analyze-Retract");
            derivationTextCache.remove(noteToAnalyze.id);

            // 2. Call LLM
            reasoner.getKifFromLlmAsync(noteTextToAnalyze, noteToAnalyze.id)
                    .thenAcceptAsync(kifString -> processLlmResponse(kifString, noteToAnalyze), SwingUtilities::invokeLater)
                    .exceptionallyAsync(ex -> { handleLlmFailure(ex, noteToAnalyze); return null; }, SwingUtilities::invokeLater);
        }

        private void processLlmResponse(String kifString, Note analyzedNote) {
            if (currentNote != analyzedNote) { setButtonsEnabled(true); return; }
            try {
                var terms = KifParser.parseKif(kifString);
                int submittedCount = 0, skippedCount = 0;
                var analysisLog = new StringBuilder("LLM KIF Analysis for: " + analyzedNote.title + "\n--------------------\n");

                for (var term : terms) {
                    if (term instanceof KifList list && !list.terms.isEmpty()) {
                        // Grounding is no longer strictly necessary, but can be helpful
                        var processedTerm = groundLlmTerm(list, analyzedNote);
                        if (processedTerm instanceof KifList processedList && !processedList.terms.isEmpty()) {
                            // Submit the processed term by creating a PotentialAssertion
                            boolean isEq = processedList.getOperator().filter("="::equals).isPresent();
                            int weight = processedList.calculateWeight();
                            double priority = 15.0 / (1.0 + weight); // Higher base priority for LLM input
                            boolean isOriented = false;
                            if (isEq && processedList.size() == 3) {
                                isOriented = processedList.get(1).calculateWeight() > processedList.get(2).calculateWeight();
                            }

                            var pa = new PotentialAssertion(processedList, priority, Set.of(), "UI-LLM", isEq, isOriented, analyzedNote.id);
                            reasoner.submitPotentialAssertion(pa); // Use the new submission method

                            analysisLog.append("Submitted: ").append(processedList.toKifString()).append("\n");
                            submittedCount++;
                        } else {
                            analysisLog.append("Skipped (processing failed): ").append(list.toKifString()).append("\n");
                            skippedCount++;
                        }
                    } else {
                        analysisLog.append("Skipped (non-list/empty term): ").append(term.toKifString()).append("\n");
                        skippedCount++;
                    }
                }
                reasoner.reasonerStatus = String.format("Analyzed '%s': %d submitted, %d skipped", analyzedNote.title, submittedCount, skippedCount);
                derivationTextCache.put(analyzedNote.id, analysisLog.toString() + "\n-- Derivations Follow --\n");
                derivationView.setText(derivationTextCache.get(analyzedNote.id));
                derivationView.setCaretPosition(0);

            } catch (ParseException pe) {
                reasoner.reasonerStatus = "KIF Parse Error from LLM";
                System.err.println("KIF Parse Error: " + pe.getMessage() + "\nInput:\n" + kifString);
                JOptionPane.showMessageDialog(this, "Could not parse KIF from LLM:\n" + pe.getMessage(), "KIF Parse Error", JOptionPane.ERROR_MESSAGE);
                derivationView.setText("Error parsing KIF from LLM.\n--------------------\n" + kifString);
            } catch (Exception ex) {
                reasoner.reasonerStatus = "Error processing LLM response";
                System.err.println("Error processing LLM KIF: " + ex); ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error processing LLM response:\n" + ex.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE);
            } finally {
                setButtonsEnabled(true); reasoner.updateUIStatus();
            }
        }

        private void handleLlmFailure(Throwable ex, Note analyzedNote) {
            if (currentNote != analyzedNote) { setButtonsEnabled(true); return; }
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            reasoner.reasonerStatus = "LLM Analysis Failed";
            System.err.println("LLM call failed for note '" + analyzedNote.title + "': " + cause.getMessage()); cause.printStackTrace();
            JOptionPane.showMessageDialog(this, "LLM communication failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
            derivationView.setText("LLM Analysis Failed for '" + analyzedNote.title + "'.\n--------------------\n" + cause.getMessage());
            setButtonsEnabled(true); reasoner.updateUIStatus();
        }

        // Grounding remains useful for LLM output
        private KifTerm groundLlmTerm(KifTerm term, Note note) {
            var variables = term.getVariables();
            if (variables.isEmpty()) return term;
            Map<KifVar, KifTerm> groundingMap = new HashMap<>();
            var prefixBase = note.title.trim().toLowerCase().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "").replaceAll("_+", "_");
            if (prefixBase.isEmpty() || prefixBase.equals("_")) prefixBase = note.id;
            var notePrefix = "entity_" + prefixBase + "_";
            variables.forEach(var -> {
                var varNamePart = var.name().substring(1).replaceAll("[^a-zA-Z0-9_]", "");
                groundingMap.put(var, new KifAtom(notePrefix + varNamePart));
            });
            return Unifier.substitute(term, groundingMap);
        }

        // Placeholder for resolve button action if needed later
        private void resolveMismatches(ActionEvent e) {
            JOptionPane.showMessageDialog(this, "Mismatch resolution feature is currently disabled/under development.", "Info", JOptionPane.INFORMATION_MESSAGE);
        }

        private void togglePause(ActionEvent e) {
            if (reasoner == null) return;
            boolean pausing = !reasoner.isPaused();
            reasoner.setPaused(pausing);
            pauseResumeButton.setText(pausing ? "Resume" : "Pause");
        }

        private void clearAll(ActionEvent e) {
            if (reasoner == null) return;
            int confirm = JOptionPane.showConfirmDialog(this, "Clear all notes, assertions, and rules?", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                reasoner.clearAllKnowledge(); // Handles engine/KB clearing
                SwingUtilities.invokeLater(() -> { // Clear UI state on EDT
                    noteListModel.clear(); derivationTextCache.clear(); currentNote = null;
                    updateUIForSelection(); statusLabel.setText("Status: Knowledge cleared.");
                });
            }
        }

        public void handleReasonerCallback(String type, Assertion assertion) {
            SwingUtilities.invokeLater(() -> {
                String relatedNoteId = findRelatedNoteId(assertion); // Use helper to trace support
                if (relatedNoteId != null) {
                    updateDerivationCache(relatedNoteId, type, assertion);
                    if (currentNote != null && relatedNoteId.equals(currentNote.id)) {
                        derivationView.setText(derivationTextCache.getOrDefault(relatedNoteId, ""));
                        // Maybe scroll to top instead of bottom? derivationView.setCaretPosition(0);
                    }
                } else if (type.equals("assert-retracted") || type.equals("evict")) {
                    clearAssertionLineFromAllCaches(assertion);
                    if (currentNote != null && derivationTextCache.containsKey(currentNote.id)) {
                        derivationView.setText(derivationTextCache.get(currentNote.id));
                    }
                }
            });
        }

        private void updateDerivationCache(String noteId, String type, Assertion assertion) {
            String lineSuffix = String.format("[%s]", assertion.id());
            // Use priority in display now
            String fullLine = String.format("Prio=%.3f %s %s", assertion.priority(), assertion.toKifString(), lineSuffix);
            String currentCachedText = derivationTextCache.computeIfAbsent(noteId, id -> "Derivations for note: " + getNoteTitleById(id) + "\n--------------------\n");
            String newCachedText = currentCachedText;

            switch (type) {
                case "assert-added", "assert-input":
                    if (!currentCachedText.contains(lineSuffix)) newCachedText = currentCachedText + fullLine + "\n";
                    break;
                case "assert-retracted", "evict":
                    newCachedText = currentCachedText.lines()
                            .map(line -> (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#")) ? "# " + type.toUpperCase() + ": " + line : line)
                            .collect(Collectors.joining("\n")) + "\n";
                    break;
            }
            if (!newCachedText.equals(currentCachedText)) derivationTextCache.put(noteId, newCachedText);
        }

        private void clearAssertionLineFromAllCaches(Assertion assertion) {
            String lineSuffix = String.format("[%s]", assertion.id());
            String typeMarker = "# " + (assertion.priority() <= 0 ? "RETRACTED" : "EVICTED") + ": "; // Marker
            derivationTextCache.replaceAll((noteId, text) -> text.lines()
                    .map(line -> (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#")) ? typeMarker + line : line)
                    .collect(Collectors.joining("\n")) + "\n");
        }

        // Find related note by traversing support graph (BFS) - needs access to reasoner's KB
        private String findRelatedNoteId(Assertion assertion) {
            if (reasoner == null) return null;
            if (assertion.sourceNoteId() != null) return assertion.sourceNoteId();

            Queue<String> toCheck = new LinkedList<>(assertion.support());
            Set<String> visited = new HashSet<>(assertion.support());
            visited.add(assertion.id());

            while (!toCheck.isEmpty()) {
                String parentId = toCheck.poll();
                // Access KB via reasoner instance - requires KB methods to be accessible or pass KB reference
                Optional<Assertion> parentOpt = reasoner.knowledgeBase.getAssertion(parentId);
                if (parentOpt.isPresent()) {
                    Assertion parent = parentOpt.get();
                    if (parent.sourceNoteId() != null) return parent.sourceNoteId();
                    parent.support().stream().filter(visited::add).forEach(toCheck::offer);
                } else {
                    // Parent not found (retracted/evicted), cannot trace further down this path
                }
            }
            return null;
        }

        private void displayCachedDerivations(Note note) {
            if (note == null) { derivationView.setText(""); return; }
            String cachedText = derivationTextCache.get(note.id);
            if (cachedText == null) {
                generateAndCacheDerivations(note); // Regenerate if cache miss
                cachedText = derivationTextCache.getOrDefault(note.id, "No derivations found for: " + note.title);
            }
            derivationView.setText(cachedText);
            derivationView.setCaretPosition(0);
        }

        // Regenerate derivation cache (might be slow for large KB)
        private void generateAndCacheDerivations(Note note) {
            if (note == null || reasoner == null) return;
            StringBuilder derivationsText = new StringBuilder("Derivations for: ").append(note.title).append("\n--------------------\n");
            // Filter all assertions - potentially slow, relies on findRelatedNoteId
            reasoner.knowledgeBase.getAllAssertions().stream()
                    .filter(a -> note.id.equals(findRelatedNoteId(a)))
                    .sorted(Comparator.comparingLong(Assertion::timestamp))
                    .forEach(a -> derivationsText.append(String.format("Prio=%.3f %s [%s]\n", a.priority(), a.toKifString(), a.id())));
            derivationTextCache.put(note.id, derivationsText.toString());
        }

        private String getNoteTitleById(String noteId) {
            for (int i = 0; i < noteListModel.size(); i++) {
                Note note = noteListModel.getElementAt(i);
                if (note.id.equals(noteId)) return note.title;
            }
            return noteId; // Fallback to ID if not found
        }

        private void setButtonsEnabled(boolean enabled) {
            addButton.setEnabled(enabled);
            boolean noteSelected = (currentNote != null);
            removeButton.setEnabled(enabled && noteSelected);
            analyzeButton.setEnabled(enabled && noteSelected);
            // resolveButton.setEnabled(enabled && noteSelected); // Keep disabled
            pauseResumeButton.setEnabled(reasoner != null);
            clearAllButton.setEnabled(reasoner != null);
            if (reasoner != null) pauseResumeButton.setText(reasoner.isPaused() ? "Resume" : "Pause");
        }

        // --- Static Inner Data Classes for UI ---
        static class Note {
            final String id; String title; String text;
            Note(String id, String title, String text) { this.id=id; this.title=title; this.text=text; }
            @Override public String toString() { return title; }
            @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); }
            @Override public int hashCode() { return id.hashCode(); }
        }
        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                lbl.setBorder(new EmptyBorder(5, 10, 5, 10)); lbl.setFont(UI_DEFAULT_FONT); return lbl;
            }
        }
        // Mismatch resolution dialog and related classes removed as per plan.

    } // End SwingUI
} // End CogNote class
