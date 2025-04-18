package dumb.cognote6;

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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNullElse;

/**
 * A probabilistic, online, iterative beam-search based SUMO KIF reasoner
 * driven by dynamic knowledge base changes via WebSockets, with callback support,
 * integrated with a Swing UI for Note->KIF distillation via LLM and mismatch resolution.
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
    private final int beamWidth;
    private final int maxKbSize;
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl;
    private final String llmModel;

    // --- Knowledge Base State ---
    private final ConcurrentMap<String, Map<KifList, Assertion>> factIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
    private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private final Set<KifList> kbContentSnapshot = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<Assertion> kbPriorityQueue = new PriorityBlockingQueue<>(); // Min-heap for eviction
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>();

    // --- Input Queue ---
    private final PriorityBlockingQueue<InputMessage> inputQueue = new PriorityBlockingQueue<>(); // Max-heap based on priority

    // --- Reasoning Engine State ---
    private final PriorityQueue<PotentialAssertion> beam = new PriorityQueue<>(); // Max-heap based on probability
    private final Set<KifList> beamContentSnapshot = ConcurrentHashMap.newKeySet();
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();

    // --- Execution & Control ---
    private final ExecutorService reasonerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ReasonerThread"));
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final SwingUI swingUI;
    private volatile String reasonerStatus = "Idle";
    private volatile boolean running = true;
    private volatile boolean paused = false;

    // --- Constructor ---
    public CogNote(int port, int beamWidth, int maxKbSize, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.beamWidth = Math.max(1, beamWidth);
        this.maxKbSize = Math.max(10, maxKbSize);
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = requireNonNullElse(llmUrl, "http://localhost:11434/api/chat");
        this.llmModel = requireNonNullElse(llmModel, "llamablit"); // Default to llama3
        this.swingUI = Objects.requireNonNull(ui, "SwingUI cannot be null");
        System.out.printf("Reasoner config: Port=%d, Beam=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s%n",
                port, this.beamWidth, this.maxKbSize, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel);
    }

    // --- Main Method ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            int port = 8887, beamWidth = 64, maxKbSize = 64 * 1024;
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
                var server = new CogNote(port, beamWidth, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
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
                  -b, --beam <beamWidth>      Beam search width (default: 64)
                  -k, --kb-size <maxKbSize>   Max KB assertion count (default: 65536)
                  -r, --rules <rulesFile>     Path to file with initial KIF rules/facts
                  --llm-url <url>             URL for the LLM API (default: http://localhost:11434/api/chat)
                  --llm-model <model>         LLM model name (default: llama3)
                  --broadcast-input           Broadcast input assertions via WebSocket"""
        );
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
            default -> type + " " + kifString; // Fallback for other types if needed
        };
        try {
            if (!getConnections().isEmpty()) broadcast(message);
        } catch (Exception e) { // Catch broader exceptions during broadcast
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
                            e.printStackTrace(); // Print stack trace for unexpected errors
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
                        case "=>", "<=>" -> inputQueue.put(new RuleMessage(list.toKifString(), sourceId));
                        case "exists" -> handleExists(list, sourceId);
                        case "forall" -> handleForall(list, sourceId);
                        default -> inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                    }
                } else { // List doesn't start with operator (e.g., a list of constants)
                    inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null));
                }
            }
            case KifList ignored -> { /* Ignore empty list */ }
            default -> System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
        }
    }

    private void handleExists(KifList existsExpr, String sourceId) {
        if (existsExpr.size() != 3) {
            System.err.println("Invalid 'exists' format (expected 3 parts): " + existsExpr.toKifString());
            return;
        }
        KifTerm varsTerm = existsExpr.get(1), body = existsExpr.get(2);
        var variables = KifTerm.collectVariablesFromSpec(varsTerm);
        if (variables.isEmpty()) { // If no variables, just process the body
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
        // Interpret 'forall vars (=> ant con)' as a rule '( => ant con )'
        if (forallExpr.size() == 3 && forallExpr.get(2) instanceof KifList bodyList && bodyList.getOperator().filter("=>"::equals).isPresent()) {
            System.out.println("Interpreting 'forall ... (=> ant con)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
            inputQueue.put(new RuleMessage(bodyList.toKifString(), sourceId + "-forallRule"));
        } else {
            System.err.println("Warning: Ignoring complex 'forall' (only handles 'forall vars (=> ant con)'): " + forallExpr.toKifString());
        }
    }

    // --- Public Control Methods ---
    public void submitMessage(InputMessage message) {
        inputQueue.put(message);
    }

    public void startReasoner() {
        if (!running) { // Prevent restarting if already stopped permanently
            System.err.println("Cannot restart a stopped reasoner.");
            return;
        }
        paused = false;
        reasonerExecutor.submit(this::reasonerLoop);
        try {
            start(); // Start WebSocket server
        } catch (IllegalStateException e) { // Catch if already started
            System.err.println("WebSocket server already started or failed to start: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage());
            // Consider stopping the reasoner thread if WS fails critically
            running = false;
            reasonerExecutor.shutdownNow();
        }
    }

    public void stopReasoner() {
        if (!running) return; // Already stopped or stopping
        System.out.println("Stopping reasoner and services...");
        running = false;
        paused = false; // Ensure pause state doesn't block shutdown
        synchronized (this) { notifyAll(); } // Wake up reasoner thread if paused

        shutdownExecutor(reasonerExecutor, "ReasonerThread");
        shutdownExecutor(llmExecutor, "LLM Executor");

        try {
            stop(1000); // Stop WebSocket server with timeout
            System.out.println("WebSocket server stopped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while stopping WebSocket server.");
        } catch (Exception e) {
            System.err.println("Error stopping WebSocket server: " + e.getMessage());
        }
        reasonerStatus = "Stopped";
        updateUIStatus(); // Final status update
        System.out.println("Reasoner stopped.");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor.isShutdown()) return;
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
        this.paused = pause;
        if (!pause) {
            synchronized (this) { this.notifyAll(); } // Notify loop if resuming
        } else {
            reasonerStatus = "Paused";
            updateUIStatus(); // Update status immediately on pause
        }
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        final List<Assertion> toRetract; // Use final for effective finality

        // Atomically clear core structures
        synchronized (this) { // Synchronize major clear operations
            toRetract = List.copyOf(assertionsById.values()); // Snapshot before clearing
            factIndex.clear();
            assertionsById.clear();
            rules.clear();
            kbContentSnapshot.clear();
            kbPriorityQueue.clear();
            noteIdToAssertionIds.clear();
            synchronized (beam) {
                beam.clear();
                beamContentSnapshot.clear();
            }
            inputQueue.clear(); // Clear pending inputs as well
        }

        // Notify UI/WS about retractions AFTER clearing internal state
        toRetract.forEach(a -> invokeCallbacks("assert-retracted", a));

        reasonerStatus = "Cleared";
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
        // Check for common, less severe network errors
        if (ex instanceof IOException && (ex.getMessage() != null && (ex.getMessage().contains("Socket closed") || ex.getMessage().contains("Connection reset") || ex.getMessage().contains("Broken pipe")))) {
            System.err.println("WS Network Error from " + addr + ": " + ex.getMessage());
        } else {
            System.err.println("WS Error from " + addr + ": " + ex.getMessage());
            ex.printStackTrace(); // Print stack trace for unexpected errors
        }
    }

    @Override
    public void onStart() {
        System.out.println("Reasoner WebSocket listener active on port " + getPort() + ".");
        setConnectionLostTimeout(100); // Set a ping/pong timeout
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        var trimmed = message.trim();
        if (trimmed.isEmpty()) return; // Ignore empty messages
        var sourceId = conn.getRemoteSocketAddress().toString();

        try {
            // Try probability format first: probability (kif...)
            var m = Pattern.compile("^([0-9.]+)\\s*(\\(.*\\))$", Pattern.DOTALL).matcher(trimmed);
            if (m.matches()) {
                var probability = Double.parseDouble(m.group(1));
                if (probability < 0.0 || probability > 1.0) throw new ParseException("Probability out of range [0,1]");
                var kifStr = m.group(2);
                var probTerms = KifParser.parseKif(kifStr);
                if (probTerms.isEmpty()) throw new ParseException("Empty KIF message received with probability.");

                for (var term : probTerms) {
                    if (term instanceof KifList list && !list.terms.isEmpty()) {
                        submitMessage(new AssertMessage(probability, list.toKifString(), sourceId, null));
                    } else {
                        throw new ParseException("Expected non-empty KIF list after probability: " + term.toKifString());
                    }
                }
            } else { // Assume one or more KIF terms (default probability 1.0)
                var terms = KifParser.parseKif(trimmed);
                if (terms.isEmpty()) throw new ParseException("Received non-KIF message: " + trimmed.substring(0, Math.min(trimmed.length(), 100)));

                terms.forEach(term -> queueExpressionFromSource(term, sourceId));
            }
        } catch (ParseException | NumberFormatException | ClassCastException e) {
            System.err.printf("WS Message Error from %s: %s | Original: %s%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), 100)));
        } catch (Exception e) { // Catch unexpected errors during parsing or queuing
            System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Reasoner Core Loop ---
    private void reasonerLoop() {
        System.out.println("Reasoner loop started.");
        long lastStatusUpdate = System.currentTimeMillis(); // Initialize

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Handle pause state
                synchronized (this) {
                    while (paused && running) {
                        wait(500); // Wait efficiently while paused
                    }
                }
                if (!running) break; // Re-check running flag after potential pause

                InputMessage msg = inputQueue.poll(); // Check input queue first (non-blocking)

                if (msg != null) {
                    reasonerStatus = "Processing Input";
                    processInputMessage(msg);
                } else if (!beam.isEmpty()) {
                    reasonerStatus = "Deriving";
                    processBeamStep();
                } else {
                    // Both queues empty, wait for input with timeout
                    reasonerStatus = "Waiting";
                    updateUIStatus(); // Update status while waiting
                    msg = inputQueue.poll(100, TimeUnit.MILLISECONDS); // Wait briefly
                    if (msg != null) {
                        reasonerStatus = "Processing Input";
                        processInputMessage(msg);
                    } // else loop continues, potentially waits again
                }

                // Update status periodically if busy
                var now = System.currentTimeMillis();
                if (now - lastStatusUpdate > 500) {
                    updateUIStatus();
                    lastStatusUpdate = now;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Re-interrupt thread
                running = false; // Ensure loop terminates
                System.out.println("Reasoner loop interrupted.");
            } catch (Exception e) {
                System.err.println("Unhandled Error in reasoner loop: " + e.getMessage());
                e.printStackTrace();
                reasonerStatus = "Error";
                updateUIStatus(); // Show error status
                // Consider adding a short sleep here to prevent rapid error looping
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); running = false;}
            }
        }
        reasonerStatus = "Stopped";
        updateUIStatus();
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
        } catch (Exception e) { // Catch unexpected runtime errors during processing
            System.err.printf("Unexpected error processing queued message (%s from %s): %s%n", msg.getClass().getSimpleName(), msg.getSourceId(), e.getMessage());
            e.printStackTrace();
        }
    }

    private void processAssertionInput(AssertMessage am) throws ParseException {
        var terms = KifParser.parseKif(am.kifString());
        if (terms.size() != 1) throw new ParseException("Assertion message must contain exactly one KIF term: " + am.kifString());

        var term = terms.getFirst();
        if (term instanceof KifList fact && !fact.terms.isEmpty()) {
            if (!fact.containsVariable()) { // Ground fact
                var potential = new PotentialAssertion(fact, am.probability(), Set.of(), am.sourceNoteId());
                addToBeam(potential);

                // Create a temporary assertion for immediate callback notification
                var tempId = "input-" + idCounter.incrementAndGet();
                var inputAssertion = new Assertion(tempId, fact, am.probability(), System.currentTimeMillis(), am.sourceNoteId(), Set.of());
                invokeCallbacks("assert-input", inputAssertion); // Trigger UI/callback immediately for input
            } else {
                System.err.println("Warning: Non-ground assertion input ignored: " + fact.toKifString());
            }
        } else {
            System.err.println("Assertion input ignored (not a non-empty KIF list): " + am.kifString());
        }
    }

    private void processRetractionByIdInput(RetractByIdMessage rm) {
        retractAssertion(rm.assertionId(), "retract-id");
    }

    private void processRetractionByNoteIdInput(RetractByNoteIdMessage rnm) {
        var idsToRetract = noteIdToAssertionIds.remove(rnm.noteId()); // Atomically get and remove the set
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.println("Retracting " + idsToRetract.size() + " assertions for note: " + rnm.noteId());
            // Iterate over a copy to avoid ConcurrentModificationException if retractAssertion modifies the original set elsewhere (though unlikely here)
            new HashSet<>(idsToRetract).forEach(id -> retractAssertion(id, "retract-note"));
        }
    }

    private void processRetractionRuleInput(RetractRuleMessage rrm) throws ParseException {
        var terms = KifParser.parseKif(rrm.ruleKif());
        if (terms.size() != 1) throw new ParseException("Rule retraction message must contain exactly one KIF term: " + rrm.ruleKif());

        var term = terms.getFirst();
        if (term instanceof KifList ruleForm) {
            boolean removed = rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));
            // If it was a bidirectional rule (<=>), try removing the implied forward/backward rules too
            if (!removed && ruleForm.getOperator().filter("<=>"::equals).isPresent() && ruleForm.size() == 3) {
                var ant = ruleForm.get(1);
                var con = ruleForm.get(2);
                var fwd = new KifList(new KifAtom("=>"), ant, con);
                var bwd = new KifList(new KifAtom("=>"), con, ant);
                removed |= rules.removeIf(r -> r.ruleForm().equals(fwd));
                removed |= rules.removeIf(r -> r.ruleForm().equals(bwd));
            }
            System.out.println("Retract rule: " + (removed ? "Success" : "No match found") + " for: " + ruleForm.toKifString());
        } else {
            System.err.println("Retract rule: Input is not a valid rule KIF list: " + rrm.ruleKif());
        }
    }

    private void processRuleInput(RuleMessage ruleMsg) throws ParseException {
        var terms = KifParser.parseKif(ruleMsg.kifString());
        if (terms.size() != 1) throw new ParseException("Rule message must contain exactly one KIF term: " + ruleMsg.kifString());

        var term = terms.getFirst();
        if (term instanceof KifList list && list.size() == 3 && list.get(1) instanceof KifTerm antTerm && list.get(2) instanceof KifTerm conTerm) {
            list.getOperator().ifPresentOrElse(op -> {
                if ("=>".equals(op) || "<=>".equals(op)) {
                    addRuleInternal(list, antTerm, conTerm);
                    // If bidirectional, add the reverse rule explicitly
                    if ("<=>".equals(op)) {
                        addRuleInternal(new KifList(new KifAtom("=>"), conTerm, antTerm), conTerm, antTerm);
                    }
                } else {
                    System.err.println("Invalid rule format (expected '=>' or '<=>' operator): " + ruleMsg.kifString());
                }
            }, () -> System.err.println("Rule input ignored (list must start with '=>' or '<=>'): " + ruleMsg.kifString()));
        } else {
            System.err.println("Rule input ignored (not a valid list structure 'op ant con'): " + ruleMsg.kifString());
        }
    }

    private void addRuleInternal(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
        var ruleId = "rule-" + idCounter.incrementAndGet();
        try {
            var newRule = new Rule(ruleId, ruleForm, antecedent, consequent);
            if (rules.add(newRule)) { // Add returns true if the set did not already contain the element
                System.out.println("Added rule: " + ruleId + " " + newRule.ruleForm().toKifString());
            } else {
                System.out.println("Rule already exists (not added): " + newRule.ruleForm().toKifString());
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Failed to create rule: " + e.getMessage() + " for " + ruleForm.toKifString());
        }
    }

    private void registerCallback(RegisterCallbackMessage rcm) {
        if (rcm.pattern() instanceof KifTerm patternTerm) {
            callbackRegistrations.add(new CallbackRegistration(patternTerm, rcm.callback()));
            System.out.println("Registered external callback for pattern: " + patternTerm.toKifString());
        } else {
            System.err.println("Callback registration failed: Invalid pattern object type.");
        }
    }

    // --- Beam Search Step ---
    private void processBeamStep() {
        List<PotentialAssertion> candidates = new ArrayList<>(beamWidth);
        synchronized (beam) { // Lock beam and snapshot together
            int count = 0;
            while (count < beamWidth && !beam.isEmpty()) {
                PotentialAssertion pa = beam.poll(); // Retrieve and remove highest probability item
                if (pa == null) continue; // Should not happen with !beam.isEmpty() check, but safety first

                beamContentSnapshot.remove(pa.fact()); // Remove polled item from snapshot regardless

                // Check against KB *after* removing from beam, *before* adding to candidates
                if (!kbContentSnapshot.contains(pa.fact())) {
                    candidates.add(pa);
                    count++;
                } else {
                    // System.out.println("Beam item already in KB, skipping: " + pa.fact().toKifString()); // Optional: Log skipped items
                }
            }
        } // Release lock on beam

        // Process candidates outside the beam lock
        candidates.forEach(pa -> {
            Assertion addedAssertion = addAssertionToKb(pa);
            if (addedAssertion != null) {
                deriveFrom(addedAssertion); // Trigger forward chaining
            }
        });
    }

    // --- Forward Chaining Derivation ---
    private void deriveFrom(Assertion newAssertion) {
        rules.parallelStream().forEach(rule -> { // Parallelize rule application
            rule.getAntecedentClauses().stream()
                    // Optimization: Check if the new assertion's predicate matches the clause's predicate
                    .filter(clause -> clause.getOperator().equals(newAssertion.fact().getOperator()))
                    .forEach(clauseToMatch -> {
                        // Attempt initial unification with the new assertion
                        Map<KifVar, KifTerm> initialBindings = Unifier.unify(clauseToMatch, newAssertion.fact(), Map.of());

                        if (initialBindings != null) {
                            // Find remaining clauses (excluding the one just matched)
                            var remainingClauses = rule.getAntecedentClauses().stream()
                                    .filter(c -> c != clauseToMatch)
                                    .toList(); // Create immutable list

                            // Recursively find matches for the rest of the clauses
                            findMatches(remainingClauses, initialBindings, Set.of(newAssertion.id()))
                                    .forEach(match -> {
                                        // Substitute bindings into the consequent
                                        var consequentTerm = Unifier.substitute(rule.consequent(), match.bindings());

                                        // Check if the result is a ground fact (no variables)
                                        if (consequentTerm instanceof KifList derived && !derived.containsVariable()) {
                                            // Calculate probability and find common source note ID
                                            var prob = calculateDerivedProbability(match.supportingAssertionIds());
                                            var inheritedNoteId = findCommonSourceNodeId(match.supportingAssertionIds());

                                            // Add the derived fact to the beam
                                            addToBeam(new PotentialAssertion(derived, prob, match.supportingAssertionIds(), inheritedNoteId));
                                        } else {
                                            // System.err.println("Warning: Rule consequent did not ground or not a list: " + consequentTerm.toKifString()); // Optional logging
                                        }
                                    });
                        }
                    });
        });
    }

    // --- Helper for Finding Matches for Remaining Clauses ---
    private Stream<MatchResult> findMatches(List<KifList> remainingClauses, Map<KifVar, KifTerm> currentBindings, Set<String> currentSupportIds) {
        // Base case: No more clauses to match, return the successful binding set and support IDs
        if (remainingClauses.isEmpty()) {
            return Stream.of(new MatchResult(currentBindings, currentSupportIds));
        }

        var clauseToMatch = remainingClauses.getFirst();
        var nextRemainingClauses = remainingClauses.subList(1, remainingClauses.size());

        // Apply current bindings to the clause we need to match
        var substitutedClauseTerm = Unifier.fullySubstitute(clauseToMatch, currentBindings);

        // Ensure the substituted term is still a list (it should be, unless the rule is malformed)
        if (!(substitutedClauseTerm instanceof KifList substitutedClause)) {
            System.err.println("Warning: Antecedent clause reduced to non-list after substitution: " + substitutedClauseTerm.toKifString() + " from " + clauseToMatch.toKifString());
            return Stream.empty(); // Cannot match a non-list
        }

        // Find candidate assertions in the KB that might match the substituted clause
        var candidateAssertions = findCandidateAssertions(substitutedClause);

        // Process candidates based on whether the substituted clause still has variables
        if (substitutedClause.containsVariable()) {
            // Clause still has variables: Need unification
            return candidateAssertions.flatMap(candidate -> {
                // Try to unify the (partially bound) clause with the candidate assertion
                var newBindings = Unifier.unify(substitutedClause, candidate.fact(), currentBindings);
                if (newBindings != null) {
                    // If unification succeeds, create new support set and recurse
                    Set<String> nextSupport = new HashSet<>(currentSupportIds);
                    nextSupport.add(candidate.id());
                    return findMatches(nextRemainingClauses, newBindings, nextSupport);
                } else {
                    return Stream.empty(); // Unification failed for this candidate
                }
            });
        } else {
            // Clause is fully ground: Need exact match
            return candidateAssertions
                    .filter(candidate -> candidate.fact().equals(substitutedClause)) // Find exact matches in KB
                    .flatMap(matchedAssertion -> {
                        // If match found, create new support set and recurse
                        Set<String> nextSupport = new HashSet<>(currentSupportIds);
                        nextSupport.add(matchedAssertion.id());
                        return findMatches(nextRemainingClauses, currentBindings, nextSupport); // Bindings don't change for ground matches
                    });
        }
    }


    // --- KB Querying & Management ---
    private Stream<Assertion> findCandidateAssertions(KifList clause) {
        // Use index if operator is present, otherwise stream all assertions (less efficient)
        return clause.getOperator()
                .map(op -> factIndex.getOrDefault(op, Map.of()).values().stream()) // Use getOrDefault for safety
                .orElseGet(() -> assertionsById.values().stream().filter(Objects::nonNull)); // Fallback: full scan
    }

    private String findCommonSourceNodeId(Set<String> supportIds) {
        if (supportIds == null || supportIds.isEmpty()) return null;

        String commonId = null;
        boolean first = true;

        for (var id : supportIds) {
            var assertion = assertionsById.get(id);
            // If any supporting assertion is missing (e.g., evicted), we can't determine the source
            if (assertion == null) return null;

            var noteId = assertion.sourceNoteId();
            // If any supporter doesn't have a note ID, the derived fact doesn't either
            if (noteId == null) return null;

            if (first) {
                commonId = noteId;
                first = false;
            } else if (!commonId.equals(noteId)) {
                // If source IDs diverge among supporters, the derived fact has no single source note
                return null;
            }
        }
        // If loop completes, all supporters had the same non-null noteId (or the set had only one element)
        return commonId;
    }

    private double calculateDerivedProbability(Set<String> supportingIds) {
        // Simple probability combination: use the minimum probability of the supporters
        return supportingIds.stream()
                .map(assertionsById::get) // Get assertion object by ID
                .filter(Objects::nonNull) // Filter out any assertions that might have been retracted/evicted
                .mapToDouble(Assertion::probability)
                .min() // Find the minimum probability
                .orElse(0.0); // Default to 0.0 if no valid supporters found (shouldn't happen in normal flow)
    }

    private Assertion addAssertionToKb(PotentialAssertion pa) {
        // Pre-checks: Check if already in KB snapshot
        if (kbContentSnapshot.contains(pa.fact())) {
            // System.out.println("KB Add skipped (already exists): " + pa.fact().toKifString()); // Optional logging
            return null;
        }

        // Enforce KB capacity *before* adding
        enforceKbCapacity();

        // Check capacity again after potential eviction
        if (assertionsById.size() >= maxKbSize) {
            System.err.printf("Warning: KB full (%d/%d), cannot add: %s%n", assertionsById.size(), maxKbSize, pa.fact().toKifString());
            return null;
        }

        // Create the new assertion
        var id = "fact-" + idCounter.incrementAndGet();
        var timestamp = System.currentTimeMillis();
        var newAssertion = new Assertion(id, pa.fact(), pa.probability(), timestamp, pa.sourceNoteId(), pa.supportingAssertionIds());

        // Attempt atomic addition to the main map
        if (assertionsById.putIfAbsent(id, newAssertion) == null) {
            // Successfully added, update secondary structures
            pa.fact().getOperator().ifPresent(op ->
                    factIndex.computeIfAbsent(op, k -> new ConcurrentHashMap<>()).put(pa.fact(), newAssertion)
            );
            kbContentSnapshot.add(pa.fact()); // Add to snapshot *after* successful add
            kbPriorityQueue.put(newAssertion); // Add to eviction queue

            // Link to source note if applicable
            if (pa.sourceNoteId() != null) {
                noteIdToAssertionIds.computeIfAbsent(pa.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(id);
            }

            // Invoke callbacks *after* KB state is fully updated
            invokeCallbacks("assert-added", newAssertion);
            // System.out.println("KB Add: " + id + " " + newAssertion.toKifString()); // Optional logging
            return newAssertion;
        } else {
            // This should be extremely rare due to unique ID generation
            System.err.println("KB Add Error: ID collision for " + id + ". Assertion not added.");
            return null;
        }
    }

    private void retractAssertion(String id, String reason) {
        // Atomically remove from the primary map
        Assertion removedAssertion = assertionsById.remove(id);

        if (removedAssertion != null) {
            // Remove from secondary structures
            kbContentSnapshot.remove(removedAssertion.fact());
            kbPriorityQueue.remove(removedAssertion); // Remove from eviction queue

            removedAssertion.fact().getOperator().ifPresent(op ->
                    factIndex.computeIfPresent(op, (k, map) -> {
                        map.remove(removedAssertion.fact());
                        return map.isEmpty() ? null : map; // Remove map from index if empty
                    })
            );

            // Unlink from source note if applicable
            if (removedAssertion.sourceNoteId() != null) {
                noteIdToAssertionIds.computeIfPresent(removedAssertion.sourceNoteId(), (k, set) -> {
                    set.remove(id);
                    return set.isEmpty() ? null : set; // Remove set if empty
                });
            }

            // Invoke callbacks *after* state is updated
            invokeCallbacks("assert-retracted", removedAssertion);
            // System.out.printf("KB Retract [%s] (%s): P=%.4f %s%n", id, reason, removedAssertion.probability(), removedAssertion.toKifString()); // Optional logging
        } else {
            // System.out.println("Retraction skipped: Assertion ID not found: " + id); // Optional logging
        }
    }


    private void enforceKbCapacity() {
        while (assertionsById.size() >= maxKbSize && !kbPriorityQueue.isEmpty()) {
            Assertion lowestPriorityAssertion = kbPriorityQueue.poll(); // Get lowest priority (lowest probability)
            if (lowestPriorityAssertion == null) break; // Safety check

            // Verify the assertion still exists in the main map before retracting
            // (It might have been retracted by another operation concurrently)
            if (assertionsById.containsKey(lowestPriorityAssertion.id())) {
                reasonerStatus = "Evicting"; // Update status briefly
                // System.out.printf("KB Evict (Low Prio) [%s]: P=%.4f %s%n", lowestPriorityAssertion.id(), lowestPriorityAssertion.probability(), lowestPriorityAssertion.toKifString()); // Optional logging
                retractAssertion(lowestPriorityAssertion.id(), "evict-capacity"); // Use main retraction logic
                invokeCallbacks("evict", lowestPriorityAssertion); // Specific eviction callback
            }
        }
    }

    private void addToBeam(PotentialAssertion pa) {
        if (pa == null || pa.fact() == null) return; // Basic validation

        // Quick check against KB snapshot (outside lock for performance)
        if (kbContentSnapshot.contains(pa.fact())) {
            // System.out.println("Beam add skipped (already in KB): " + pa.fact().toKifString()); // Optional
            return;
        }

        synchronized (beam) { // Synchronize access to beam and its snapshot
            // Re-check KB snapshot *inside* the lock to prevent race conditions
            // Also check beam snapshot to avoid duplicates within the beam itself
            if (!kbContentSnapshot.contains(pa.fact()) && beamContentSnapshot.add(pa.fact())) {
                beam.offer(pa); // Add to the priority queue (max-heap)

                // Optional: Trim beam if it significantly exceeds beamWidth (e.g., > 2*beamWidth)
                // This prevents the beam from growing indefinitely if derivation rate is very high.
                // A simple trim strategy:
                while (beam.size() > beamWidth * 2) {
                    PotentialAssertion removed = beam.poll(); // Remove lowest probability item from beam
                    if (removed != null) {
                        beamContentSnapshot.remove(removed.fact()); // Keep snapshot consistent
                        // System.out.println("Trimmed beam, removing: " + removed.fact().toKifString()); // Optional logging
                    }
                }
            } else {
                // System.out.println("Beam add skipped (already in KB or Beam snapshot): " + pa.fact().toKifString()); // Optional
            }
        }
    }

    // --- Callback Handling ---
    private void invokeCallbacks(String type, Assertion assertion) {
        // 1. WebSocket Broadcast (Conditional)
        boolean shouldBroadcast = switch (type) {
            case "assert-added", "assert-retracted", "evict" -> true;
            case "assert-input" -> broadcastInputAssertions;
            default -> false; // Don't broadcast unknown types
        };
        if (shouldBroadcast) {
            broadcastMessage(type, assertion);
        }

        // 2. UI Update (if UI exists and is visible)
        if (swingUI != null && swingUI.isDisplayable()) {
            swingUI.handleReasonerCallback(type, assertion); // Let UI handle EDT invocation
        }

        // 3. Registered KIF Pattern Callbacks
        callbackRegistrations.forEach(reg -> {
            try {
                Map<KifVar, KifTerm> bindings = Unifier.unify(reg.pattern(), assertion.fact(), Map.of());
                if (bindings != null) {
                    // Execute callback, potentially offloading long tasks if necessary
                    // Consider using an executor if callbacks are complex/blocking
                    reg.callback().onMatch(type, assertion, bindings);
                }
            } catch (Exception e) {
                System.err.println("Error executing KIF pattern callback for " + reg.pattern().toKifString() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // --- UI Status Update ---
    private void updateUIStatus() {
        if (swingUI != null && swingUI.isDisplayable()) {
            // Capture sizes atomically where possible, though perfect sync isn't critical for status
            int kbSize = assertionsById.size();
            int beamSize = beam.size(); // size() is weakly consistent for PriorityBlockingQueue, good enough for status
            int inputSize = inputQueue.size();
            int ruleCount = rules.size();
            // Use final for effectively final capture by lambda
            final String statusText = String.format("KB: %d/%d | Beam: %d | Input: %d | Rules: %d | Status: %s",
                    kbSize, maxKbSize, beamSize, inputSize, ruleCount, reasonerStatus);
            SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
        }
    }

    // --- LLM Interaction ---
    public CompletableFuture<String> getKifFromLlmAsync(String contextPrompt, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            // Standardized prompt for KIF generation
            var finalPrompt = (!contextPrompt.toLowerCase().contains("kif assertions:") && !contextPrompt.toLowerCase().contains("proposed fact:")) ? """
                    Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax).
                    Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                    Do not include explanations, markdown formatting (like ```kif), comments, or any surrounding text.
                    Focus on factual statements and relationships. Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', attribute relations, etc. where appropriate.
                    If mentioning an entity whose specific name isn't given, use a KIF variable (e.g., `?cpu`, `?company`).

                    Note:
                    "%s"

                    KIF Assertions:""".formatted(contextPrompt) : contextPrompt;

            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", finalPrompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2)); // Low temp for factual generation

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60)) // Increased timeout for potentially slow LLMs
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var jsonResponse = new JSONObject(new JSONTokener(responseBody));

                    // Extract content using a robust approach, checking common structures and falling back
                    var kifResult = extractLlmContent(jsonResponse)
                            .orElse(""); // Default to empty string if no content found

                    // Clean the extracted KIF string
                    var cleanedKif = kifResult.lines()
                            .map(s -> s.replaceAll("(?i)```kif", "").replaceAll("```", "").trim()) // Remove markdown code fences
                            .filter(line -> line.startsWith("(") && line.endsWith(")")) // Basic KIF list structure check
                            .filter(line -> !line.matches("^\\(\\s*\\)$")) // Filter empty parentheses `()`
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
                // Wrap checked exceptions for CompletableFuture
                throw new CompletionException("LLM API communication error", e);
            } catch (Exception e) { // Catch broader JSON exceptions, etc.
                System.err.println("LLM response processing failed for note " + noteId + ": " + e.getMessage());
                e.printStackTrace();
                throw new CompletionException("LLM response processing error", e);
            }
        }, llmExecutor); // Execute on the virtual thread executor
    }

    // Robustly extracts content string from common LLM response structures
    private Optional<String> extractLlmContent(JSONObject jsonResponse) {
        // 1. Standard OpenAI/Ollama structure: response.message.content
        var content = Optional.ofNullable(jsonResponse.optJSONObject("message"))
                .map(m -> m.optString("content", null));
        if (content.isPresent() && content.get() != null) return content;

        // 2. Ollama non-streaming: response.response
        content = Optional.ofNullable(jsonResponse.optString("response", null));
        if (content.isPresent() && content.get() != null) return content;

        // 3. OpenAI choices structure: response.choices[0].message.content
        content = Optional.ofNullable(jsonResponse.optJSONArray("choices"))
                .filter(a -> !a.isEmpty())
                .map(a -> a.optJSONObject(0))
                .map(c -> c.optJSONObject("message"))
                .map(m -> m.optString("content", null));
        if (content.isPresent() && content.get() != null) return content;

        // 4. Fallback: Recursive search for any "content" field
        return findNestedContent(jsonResponse);
    }

    // Recursive helper to find a "content" string anywhere in a nested JSON structure
    private Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> {
                if (obj.has("content") && obj.get("content") instanceof String s && !s.isBlank()) {
                    yield Optional.of(s);
                }
                Optional<String> found = Optional.empty();
                for (var key : obj.keySet()) {
                    found = findNestedContent(obj.get(key)); // Recurse on value
                    if (found.isPresent()) break; // Stop searching if found
                }
                yield found;
            }
            case JSONArray arr -> {
                Optional<String> found = Optional.empty();
                for (var i = 0; i < arr.length(); i++) {
                    found = findNestedContent(arr.get(i)); // Recurse on element
                    if (found.isPresent()) break; // Stop searching if found
                }
                yield found;
            }
            default -> Optional.empty(); // Not a JSONObject or JSONArray, cannot contain nested content
        };
    }

    // --- KIF Data Structures ---
    sealed interface KifTerm permits KifAtom, KifVar, KifList {
        String toKifString();
        boolean containsVariable();
        Set<KifVar> getVariables(); // Lazily computed and cached

        private static void collectVariablesRecursive(KifTerm term, Set<KifVar> vars) {
            switch (term) {
                case KifVar v -> vars.add(v);
                case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars)); // Use terms() accessor
                case KifAtom ignored -> {} // No variables in atoms
            }
        }

        static Set<KifVar> collectVariablesFromSpec(KifTerm varsTerm) {
            return switch (varsTerm) {
                case KifVar v -> Set.of(v);
                case KifList l -> l.terms().stream() // Use terms() accessor
                        .filter(KifVar.class::isInstance)
                        .map(KifVar.class::cast)
                        .collect(Collectors.toUnmodifiableSet());
                default -> {
                    System.err.println("Warning: Invalid variable specification in quantifier (expected variable or list of variables): " + varsTerm.toKifString());
                    yield Set.of();
                }
            };
        }
    }

    static final class KifAtom implements KifTerm {
            private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$"); // Example safe charsprivate final String value;
        private final String value;

        private volatile Set<KifVar> variables; // Cache

            KifAtom(String value) {
                Objects.requireNonNull(value);
                this.value = value;
            }

            @Override
            public String toKifString() {
                // Quote if empty, contains whitespace, or special KIF characters, or doesn't match safe pattern
                boolean needsQuotes = value.isEmpty()
                        || value.chars().anyMatch(Character::isWhitespace)
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
                if (variables == null) variables = Set.of();
                return variables;
            }

        public String value() {
            return value;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (KifAtom) obj;
            return Objects.equals(this.value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return "KifAtom[" +
                    "value=" + value + ']';
        }

        }

    static final class KifVar implements KifTerm {
        private final String name;

            private volatile Set<KifVar> variables; // Cache

            KifVar(String name) {
                Objects.requireNonNull(name);
                if (!name.startsWith("?")) throw new IllegalArgumentException("Variable name must start with '?': " + name);
                if (name.length() == 1) throw new IllegalArgumentException("Variable name cannot be empty ('?')");
                this.name = name;
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
                if (variables == null) variables = Set.of(this);
                return variables;
            }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (KifVar) obj;
            return Objects.equals(this.name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        @Override
        public String toString() {
            return "KifVar[" +
                    "name=" + name + ']';
        }

        }

    static final class KifList implements KifTerm {
        private final List<KifTerm> terms;

            // Lazily computed and cached fields
            private volatile Boolean containsVariable;
            private volatile Set<KifVar> variables;
            private volatile int hashCode;
            private volatile String kifString;

            KifList(KifTerm... terms) {
                this(List.of(terms));
            }

        KifList(List<KifTerm> terms) {
            terms = List.copyOf(Objects.requireNonNull(terms));
            this.terms = terms;
        }

            @Override
            public boolean equals(Object o) {
                return this==o || (o instanceof KifList l && terms.equals(l.terms));
            }

        @Override
        public int hashCode() {
                if (hashCode == 0) hashCode = terms.hashCode();
                return hashCode;
            }

        @Override
        public String toKifString() {
                if (kifString == null) {
                    kifString = terms.stream().map(KifTerm::toKifString).collect(Collectors.joining(" ", "(", ")"));
                }
                return kifString;
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
            public boolean containsVariable() {
                if (containsVariable == null) {
                    containsVariable = terms.stream().anyMatch(KifTerm::containsVariable);
                }
                return containsVariable;
            }

        @Override
        public Set<KifVar> getVariables() {
                if (variables == null) {
                    Set<KifVar> vars = new HashSet<>();
                    terms.forEach(t -> KifTerm.collectVariablesRecursive(t, vars));
                    variables = Collections.unmodifiableSet(vars);
                }
                return variables;
            }

        public List<KifTerm> terms() {
            return terms;
        }

        @Override
        public String toString() {
            return "KifList[" +
                    "terms=" + terms + ']';
        }

        }

    // --- Reasoner Internals & Data Records ---
    record Assertion(String id, KifList fact, double probability, long timestamp, String sourceNoteId,
                     Set<String> directSupportingIds) implements Comparable<Assertion> {
        Assertion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(fact);
            if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]: " + probability);
            directSupportingIds = Set.copyOf(Objects.requireNonNull(directSupportingIds));
        }
        @Override public int compareTo(Assertion other) { return Double.compare(this.probability, other.probability); } // Min-heap (lower probability first for eviction)
        String toKifString() { return fact.toKifString(); }
    }

    static final class Rule {
            private static final KifAtom AND_OP = new KifAtom("and");
        private final String id;
        private final KifList ruleForm;
        private final KifTerm antecedent;
        private final KifTerm consequent;

            private final List<KifList> antecedentClauses; // Cached clauses

            Rule(String id, KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
                Objects.requireNonNull(id);
                Objects.requireNonNull(ruleForm);
                Objects.requireNonNull(antecedent);
                Objects.requireNonNull(consequent);

                // Validate rule structure
                if (!(ruleForm.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent() && ruleForm.size() == 3)) {
                    throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());
                }
                // Validate antecedent structure (must be list, 'true', or 'and' list)
                boolean validAntecedent = switch (antecedent) {
                    case KifList list when list.getOperator().filter(AND_OP.value::equals).isPresent() ->
                            list.terms().stream().skip(1).allMatch(KifList.class::isInstance); // All elements after 'and' must be lists
                    case KifList ignored -> true; // Single list is valid
                    case KifAtom atom when atom.value().equalsIgnoreCase("true") -> true; // 'true' atom is valid
                    default -> false;
                };
                if (!validAntecedent) {
                    throw new IllegalArgumentException("Antecedent must be a KIF list, 'true', or (and list1 list2 ...): " + antecedent.toKifString());
                }

                validateUnboundVariables(ruleForm, antecedent, consequent);

                // Cache antecedent clauses
                antecedentClauses = switch (antecedent) {
                    case KifList list when list.getOperator().filter(AND_OP.value::equals).isPresent() ->
                            list.terms().stream().skip(1).map(KifList.class::cast).toList(); // Assumes validation passed
                    case KifList list -> List.of(list);
                    default -> List.of(); // e.g., 'true' antecedent
                };
                this.id = id;
                this.ruleForm = ruleForm;
                this.antecedent = antecedent;
                this.consequent = consequent;
            }

            // Check for variables in consequent not bound by antecedent or local quantifiers
            private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
                var antVars = antecedent.getVariables();
                var conVarsAll = consequent.getVariables();
                var conVarsBoundLocally = getQuantifierBoundVariables(consequent); // Variables bound by exists/forall within consequent

                Set<KifVar> conVarsNeedingBinding = new HashSet<>(conVarsAll);
                conVarsNeedingBinding.removeAll(antVars); // Remove vars bound by antecedent
                conVarsNeedingBinding.removeAll(conVarsBoundLocally); // Remove vars bound locally in consequent

                // Allow unbound in <=> rules (implicitly universally quantified), warn for =>
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
                        boundVars.addAll(KifTerm.collectVariablesFromSpec(list.get(1))); // Add variables from spec
                        collectQuantifierBoundVariablesRecursive(list.get(2), boundVars); // Recurse into body
                    }
                    case KifList list -> list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars)); // Recurse into sub-terms
                    default -> {
                    } // Atoms and Vars don't bind
                }
            }

            // Accessor for cached clauses
            List<KifList> getAntecedentClauses() {
                return antecedentClauses;
            }

            @Override
            public boolean equals(Object o) {
                return this==o || (o instanceof Rule r && ruleForm.equals(r.ruleForm));
            }

        @Override
        public int hashCode() {
            return ruleForm.hashCode();
        }

        public String id() {
            return id;
        }

        public KifList ruleForm() {
            return ruleForm;
        }

        public KifTerm antecedent() {
            return antecedent;
        }

        public KifTerm consequent() {
            return consequent;
        }

        @Override
        public String toString() {
            return "Rule[" +
                    "id=" + id + ", " +
                    "ruleForm=" + ruleForm + ", " +
                    "antecedent=" + antecedent + ", " +
                    "consequent=" + consequent + ']';
        }

        }

    record PotentialAssertion(KifList fact, double probability, Set<String> supportingAssertionIds,
                              String sourceNoteId) implements Comparable<PotentialAssertion> {
        PotentialAssertion {
            Objects.requireNonNull(fact);
            if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]");
            supportingAssertionIds = Set.copyOf(Objects.requireNonNull(supportingAssertionIds));
        }
        @Override public int compareTo(PotentialAssertion other) { return Double.compare(other.probability, this.probability); } // Max-heap (higher probability first)
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && fact.equals(pa.fact); } // Equality based on fact only for snapshot checks
        @Override public int hashCode() { return fact.hashCode(); }
    }

    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
        CallbackRegistration { Objects.requireNonNull(pattern); Objects.requireNonNull(callback); }
    }

    record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportingAssertionIds) {
        MatchResult { Objects.requireNonNull(bindings); Objects.requireNonNull(supportingAssertionIds); }
    }

    // --- Input Message Types (PriorityQueue uses compareTo: higher value = higher priority) ---
    sealed interface InputMessage extends Comparable<InputMessage> {
        double getPriority();
        String getSourceId();
        @Override default int compareTo(InputMessage other) { return Double.compare(getPriority(), other.getPriority()); }
    }

    record AssertMessage(double probability, String kifString, String sourceId, String sourceNoteId) implements InputMessage {
        @Override public double getPriority() { return probability * 10.0; } // Priority 0-10 based on probability
        @Override public String getSourceId() { return sourceId; }
    }
    record RuleMessage(String kifString, String sourceId) implements InputMessage {
        @Override public double getPriority() { return 80.0; }
        @Override public String getSourceId() { return sourceId; }
    }
    record RegisterCallbackMessage(KifTerm pattern, KifCallback callback, String sourceId) implements InputMessage {
        @Override public double getPriority() { return 70.0; }
        @Override public String getSourceId() { return sourceId; }
    }
    record RetractRuleMessage(String ruleKif, String sourceId) implements InputMessage {
        @Override public double getPriority() { return 90.0; }
        @Override public String getSourceId() { return sourceId; }
    }
    record RetractByIdMessage(String assertionId, String sourceId) implements InputMessage {
        @Override public double getPriority() { return 100.0; } // High priority for direct retractions
        @Override public String getSourceId() { return sourceId; }
    }
    record RetractByNoteIdMessage(String noteId, String sourceId) implements InputMessage {
        @Override public double getPriority() { return 100.0; } // High priority for note-based retractions
        @Override public String getSourceId() { return sourceId; }
    }

    @FunctionalInterface interface KifCallback { void onMatch(String type, Assertion assertion, Map<KifVar, KifTerm> bindings); }

    // --- KIF Parser ---
    static class KifParser {
        private final Reader reader;
        private int currentChar = -2; // -2: initial state, -1: EOF, >=0: char code
        private int line = 1;
        private int col = 0;

        private KifParser(Reader reader) { this.reader = reader; }

        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var reader = new StringReader(input.trim())) {
                return new KifParser(reader).parseTopLevel();
            } catch (IOException e) { throw new ParseException("Read error: " + e.getMessage()); } // Should not happen with StringReader
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
            if (atom.isEmpty()) throw createParseException("Empty atom encountered"); // Should not happen if isValidAtomChar(first) is true
            return new KifAtom(atom.toString());
        }

        // Checks if a character is valid for an unquoted atom
        private boolean isValidAtomChar(int c, boolean isFirstChar) {
            if (c == -1 || Character.isWhitespace(c) || "()\";?".indexOf(c) != -1) return false;
            // Optionally restrict first char further (e.g., not a digit) if needed by KIF standard
            // if (isFirstChar && Character.isDigit(c)) return false;
            return true;
        }

        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                int c = consumeChar();
                if (c == '"') return new KifAtom(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') { // Handle escape sequences
                    int next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape character");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\', '"' -> next; // Escaped backslash or quote
                        default -> {
                            // KIF standard might not define other escapes, treat as literal chars
                            System.err.println("Warning: Unknown escape sequence '\\" + (char) next + "' at line " + line + ", col " + (col -1));
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
                currentChar = -2; // Mark cache as invalid
                if (c == '\n') { line++; col = 0; }
                else { col++; }
            }
            return c;
        }

        private void consumeChar(char expected) throws IOException, ParseException {
            int actual = consumeChar();
            if (actual != expected) {
                throw createParseException("Expected '" + expected + "' but found '" + (actual == -1 ? "EOF" : (char) actual) + "'");
            }
        }

        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                int c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) {
                    consumeChar();
                } else if (c == ';') { // Comment runs to end of line
                    consumeChar(); // Consume ';'
                    while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                    // Consume the newline itself if present
                    if (peek() == '\n' || peek() == '\r') consumeChar();
                } else {
                    break; // Not whitespace or comment start
                }
            }
        }

        private ParseException createParseException(String message) {
            return new ParseException(message + " at line " + line + " col " + col);
        }
    }

    static class ParseException extends Exception { ParseException(String message) { super(message); } }

    // --- Unification Logic ---
    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 100; // Prevents infinite recursion in substitution

        static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null; // Propagate failure

            // Substitute terms fully based on current bindings before comparing/binding
            var xSubst = fullySubstitute(x, bindings);
            var ySubst = fullySubstitute(y, bindings);

            // 1. Identical after substitution? Success.
            if (xSubst.equals(ySubst)) return bindings;

            // 2. One is a variable? Try binding it.
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings);
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings);

            // 3. Both are lists? Unify elements recursively.
            if (xSubst instanceof KifList listX && ySubst instanceof KifList listY) {
                if (listX.size() != listY.size()) return null; // Mismatched list size

                var currentBindings = bindings;
                for (int i = 0; i < listX.size(); i++) {
                    currentBindings = unify(listX.get(i), listY.get(i), currentBindings);
                    if (currentBindings == null) return null; // Unification failed for an element
                }
                return currentBindings; // Success
            }

            // 4. Mismatch (e.g., different atoms, atom vs list)
            return null;
        }

        static KifTerm substitute(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return fullySubstitute(term, bindings); // Public API uses full substitution
        }

        static KifTerm fullySubstitute(KifTerm term, Map<KifVar, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term; // Optimization: no need to substitute

            var current = term;
            for (int depth = 0; depth < MAX_SUBST_DEPTH; depth++) {
                var next = substituteOnce(current, bindings);
                // Stop if no change occurred or if the term becomes ground
                if (next.equals(current) || !next.containsVariable()) {
                    return next;
                }
                current = next;
            }

            System.err.println("Warning: Substitution depth limit (" + MAX_SUBST_DEPTH + ") reached for: " + term.toKifString());
            return current; // Return the term at max depth
        }

        // Performs one level of substitution
        private static KifTerm substituteOnce(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return switch (term) {
                case KifAtom atom -> atom; // Atoms are constants
                case KifVar var -> bindings.getOrDefault(var, var); // Substitute if binding exists
                case KifList list -> {
                    boolean changed = false;
                    List<KifTerm> newTerms = new ArrayList<>(list.size());
                    for (KifTerm subTerm : list.terms()) {
                        KifTerm substitutedSubTerm = substituteOnce(subTerm, bindings);
                        if (substitutedSubTerm != subTerm) changed = true;
                        newTerms.add(substitutedSubTerm);
                    }
                    // Return new list only if something changed, otherwise reuse original
                    yield changed ? new KifList(newTerms) : list;
                }
            };
        }

        // Attempts to bind variable 'var' to 'value' within the 'bindings' map
        private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings) {
            // 1. Trivial binding: var already bound to the same value (or value is var itself)
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var)) {
                // 2. Var already bound: Unify the existing binding with the new value
                return unify(bindings.get(var), value, bindings);
            }

            // 3. Value is a variable that is already bound: Bind 'var' to the ultimate value
            // This helps keep binding chains short
            var finalValue = value;
            if (value instanceof KifVar valueVar && bindings.containsKey(valueVar)) {
                finalValue = fullySubstitute(valueVar, bindings); // Find the end of the chain
            }

            // 4. Occurs Check: Prevent binding ?X to f(?X)
            if (occursCheck(var, finalValue, bindings)) {
                // System.err.println("Occurs check failed: cannot bind " + var.toKifString() + " to " + finalValue.toKifString());
                return null;
            }

            // 5. Create new binding set
            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        // Checks if 'var' occurs within 'term' after substituting using 'bindings'
        private static boolean occursCheck(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings) {
            var substTerm = fullySubstitute(term, bindings); // Fully substitute term first for accurate check
            return switch (substTerm) {
                case KifVar v -> var.equals(v); // Base case: term is the variable itself
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheck(var, t, bindings)); // Recurse into list elements
                case KifAtom ignored -> false; // Variable cannot occur in a constant atom
            };
        }
    }

    // --- Swing UI Inner Class ---
    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing..."); // Accessible by outer class
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JTextArea derivationView = new JTextArea();
        private final JButton addButton = new JButton("Add Note");
        private final JButton removeButton = new JButton("Remove Note");
        private final JButton analyzeButton = new JButton("Analyze Note");
        private final JButton resolveButton = new JButton("Resolve...");
        private final JButton pauseResumeButton = new JButton("Pause");
        private final JButton clearAllButton = new JButton("Clear All");
        private CogNote reasoner; // Link set after construction
        private Note currentNote = null;
        private final Map<String, String> derivationTextCache = new ConcurrentHashMap<>(); // Cache derivation text per note ID

        public SwingUI(CogNote reasoner) {
            super("Cognote - Probabilistic KIF Reasoner");
            this.reasoner = reasoner;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Use WindowListener for shutdown
            setSize(1200, 800);
            setLocationRelativeTo(null); // Center on screen
            setupFonts();
            setupComponents();
            setupLayout();
            setupListeners();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    System.out.println("UI closing event detected.");
                    saveCurrentNoteText(); // Save any pending edits
                    if (SwingUI.this.reasoner != null) {
                        SwingUI.this.reasoner.stopReasoner(); // Gracefully stop the reasoner
                    }
                    dispose(); // Close the UI window
                    System.exit(0); // Terminate the application
                }
            });
            updateUIForSelection(); // Initial UI state
        }

        void setReasoner(CogNote reasoner) { this.reasoner = reasoner; }

        private void setupFonts() {
            // Apply fonts consistently
            Font defaultFont = UI_DEFAULT_FONT;
            Font monoFont = MONOSPACED_FONT;
            noteList.setFont(defaultFont);
            noteEditor.setFont(defaultFont);
            derivationView.setFont(monoFont);
            addButton.setFont(defaultFont);
            removeButton.setFont(defaultFont);
            analyzeButton.setFont(defaultFont);
            resolveButton.setFont(defaultFont);
            pauseResumeButton.setFont(defaultFont);
            clearAllButton.setFont(defaultFont);
            statusLabel.setFont(defaultFont);
            // Apply to default UI elements if needed (less critical now)
            // UIManager.put("TextArea.font", defaultFont);
            // UIManager.put("List.font", defaultFont);
            // ... etc.
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
            var leftScrollPane = new JScrollPane(noteList);
            leftScrollPane.setPreferredSize(new Dimension(250, 0)); // Give list preferred width

            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScrollPane, derivationScrollPane);
            rightSplit.setResizeWeight(0.65); // Give editor more space initially

            var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightSplit);
            mainSplit.setResizeWeight(0.20); // Give list less space initially

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
            buttonPanel.add(addButton);
            buttonPanel.add(removeButton);
            buttonPanel.add(analyzeButton);
            buttonPanel.add(resolveButton);
            buttonPanel.add(pauseResumeButton);
            buttonPanel.add(clearAllButton);

            var bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(buttonPanel, BorderLayout.WEST);
            bottomPanel.add(statusLabel, BorderLayout.CENTER);
            bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

            getContentPane().setLayout(new BorderLayout()); // Ensure frame has BorderLayout
            getContentPane().add(mainSplit, BorderLayout.CENTER);
            getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupListeners() {
            addButton.addActionListener(this::addNote);
            removeButton.addActionListener(this::removeNote);
            analyzeButton.addActionListener(this::analyzeNote);
            resolveButton.addActionListener(this::resolveMismatches);
            pauseResumeButton.addActionListener(this::togglePause);
            clearAllButton.addActionListener(this::clearAll);

            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) { // Process selection change only when stable
                    var selected = noteList.getSelectedValue();
                    saveCurrentNoteText(); // Save text of the previously selected note
                    currentNote = selected;
                    updateUIForSelection(); // Update UI for the newly selected note
                }
            });

            // Save text when focus leaves the editor
            noteEditor.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent evt) { saveCurrentNoteText(); }
            });
        }

        // Saves the current editor content to the currently selected Note object
        private void saveCurrentNoteText() {
            if (currentNote != null && noteEditor.isEnabled()) {
                var newText = noteEditor.getText();
                if (!newText.equals(currentNote.text)) {
                    currentNote.text = newText;
                    // System.out.println("Saved text for note: " + currentNote.title); // Optional logging
                }
            }
        }

        // Updates the UI state based on whether a note is selected
        private void updateUIForSelection() {
            boolean noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected);
            removeButton.setEnabled(noteSelected);
            analyzeButton.setEnabled(noteSelected);
            resolveButton.setEnabled(noteSelected);
            // Pause/Clear depend only on reasoner existence
            pauseResumeButton.setEnabled(reasoner != null);
            clearAllButton.setEnabled(reasoner != null);

            if (noteSelected) {
                noteEditor.setText(currentNote.text);
                noteEditor.setCaretPosition(0); // Scroll editor to top
                displayCachedDerivations(currentNote); // Load cached or generate derivations
                setTitle("Cognote - " + currentNote.title);
            } else {
                noteEditor.setText("");
                derivationView.setText("");
                setTitle("Cognote - Probabilistic KIF Reasoner");
            }
            noteEditor.requestFocusInWindow(); // Focus editor when selection changes
        }

        private void addNote(ActionEvent e) {
            String title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) {
                var noteId = "note-" + UUID.randomUUID();
                var newNote = new Note(noteId, title.trim(), ""); // Start with empty text
                noteListModel.addElement(newNote);
                noteList.setSelectedValue(newNote, true); // Select the new note
                noteEditor.requestFocusInWindow(); // Focus editor for immediate typing
            }
        }

        private void removeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;

            int confirm = JOptionPane.showConfirmDialog(this,
                    "Remove note '" + currentNote.title + "' and retract all associated assertions?",
                    "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                statusLabel.setText("Status: Removing '" + currentNote.title + "'...");
                setButtonsEnabled(false); // Disable buttons during removal
                var noteToRemove = currentNote; // Capture current note

                // Submit retraction message to the reasoner
                reasoner.submitMessage(new RetractByNoteIdMessage(noteToRemove.id, "UI-Remove"));
                derivationTextCache.remove(noteToRemove.id); // Clear cache for this note

                // Update UI after submitting retraction
                SwingUtilities.invokeLater(() -> {
                    int selectedIndex = noteList.getSelectedIndex();
                    noteListModel.removeElement(noteToRemove);

                    // Select another note if possible
                    if (!noteListModel.isEmpty()) {
                        int newIndex = Math.max(0, Math.min(selectedIndex, noteListModel.getSize() - 1));
                        noteList.setSelectedIndex(newIndex);
                    } else {
                        currentNote = null; // No notes left
                        updateUIForSelection(); // Update UI to reflect no selection
                    }
                    setButtonsEnabled(true); // Re-enable buttons
                    statusLabel.setText("Status: Removed '" + noteToRemove.title + "'."); // Update status
                });
            }
        }

        private void analyzeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;

            reasoner.reasonerStatus = "Analyzing Note: " + currentNote.title;
            reasoner.updateUIStatus();
            setButtonsEnabled(false);
            saveCurrentNoteText(); // Ensure latest text is saved before analysis

            final var noteToAnalyze = currentNote; // Capture current note
            final var noteTextToAnalyze = noteToAnalyze.text; // Capture text
            derivationView.setText("Analyzing Note '" + noteToAnalyze.title + "' via LLM...\n--------------------\n");

            // 1. Retract existing assertions for this note
            reasoner.submitMessage(new RetractByNoteIdMessage(noteToAnalyze.id, "UI-Analyze-Retract"));
            derivationTextCache.remove(noteToAnalyze.id); // Clear derivation cache

            // 2. Call LLM for KIF extraction
            reasoner.getKifFromLlmAsync(noteTextToAnalyze, noteToAnalyze.id)
                    .thenAcceptAsync(kifString -> {
                        // 3. Process LLM response on EDT
                        processLlmResponse(kifString, noteToAnalyze);
                    }, SwingUtilities::invokeLater) // Ensure processing happens on EDT
                    .exceptionallyAsync(ex -> {
                        // 4. Handle LLM call failures on EDT
                        handleLlmFailure(ex, noteToAnalyze);
                        return null;
                    }, SwingUtilities::invokeLater); // Ensure error handling happens on EDT
        }

        // Processes the KIF string received from the LLM
        private void processLlmResponse(String kifString, Note analyzedNote) {
            if (currentNote != analyzedNote) { // Check if selection changed during analysis
                setButtonsEnabled(true);
                return; // Don't update UI for a note that's no longer selected
            }
            try {
                var terms = KifParser.parseKif(kifString);
                int submittedCount = 0, groundedCount = 0, skippedCount = 0;
                var analysisLog = new StringBuilder("LLM KIF Analysis for: " + analyzedNote.title + "\n--------------------\n");

                for (var term : terms) {
                    if (term instanceof KifList fact && !fact.terms.isEmpty()) {
                        if (!fact.containsVariable()) { // Ground fact
                            reasoner.submitMessage(new AssertMessage(1.0, fact.toKifString(), "UI-LLM", analyzedNote.id));
                            analysisLog.append("Asserted: ").append(fact.toKifString()).append("\n");
                            submittedCount++;
                        } else { // Fact contains variables, attempt grounding
                            var groundedTerm = groundLlmTerm(fact, analyzedNote); // Use note object for better prefix
                            if (groundedTerm instanceof KifList groundedFact && !groundedFact.containsVariable()) {
                                reasoner.submitMessage(new AssertMessage(1.0, groundedFact.toKifString(), "UI-LLM-Grounded", analyzedNote.id));
                                analysisLog.append("Grounded: ").append(fact.toKifString()).append(" -> ").append(groundedFact.toKifString()).append("\n");
                                groundedCount++;
                            } else {
                                analysisLog.append("Skipped (ungroundable variable): ").append(fact.toKifString()).append("\n");
                                skippedCount++;
                            }
                        }
                    } else {
                        analysisLog.append("Skipped (non-list/empty term): ").append(term.toKifString()).append("\n");
                        skippedCount++;
                    }
                }

                final int totalSubmitted = submittedCount + groundedCount;
                reasoner.reasonerStatus = String.format("Analyzed '%s': %d submitted (%d grounded), %d skipped", analyzedNote.title, totalSubmitted, groundedCount, skippedCount);
                // Update derivation view immediately with analysis log
                // Callbacks will append actual derivations later
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
                System.err.println("Error processing LLM KIF: " + ex);
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error processing LLM response:\n" + ex.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE);
            } finally {
                setButtonsEnabled(true); // Re-enable buttons
                reasoner.updateUIStatus(); // Update status bar
            }
        }

        // Handles failures during the LLM call
        private void handleLlmFailure(Throwable ex, Note analyzedNote) {
            if (currentNote != analyzedNote) { // Check if selection changed
                setButtonsEnabled(true);
                return;
            }
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            reasoner.reasonerStatus = "LLM Analysis Failed";
            System.err.println("LLM call failed for note '" + analyzedNote.title + "': " + cause.getMessage());
            cause.printStackTrace();
            JOptionPane.showMessageDialog(this, "LLM communication failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
            derivationView.setText("LLM Analysis Failed for '" + analyzedNote.title + "'.\n--------------------\n" + cause.getMessage());
            setButtonsEnabled(true); // Re-enable buttons
            reasoner.updateUIStatus(); // Update status bar
        }


        // Grounds variables in a KIF term using note-specific entity names
        private KifTerm groundLlmTerm(KifTerm term, Note note) {
            var variables = term.getVariables();
            if (variables.isEmpty()) return term; // Already ground

            Map<KifVar, KifTerm> groundingMap = new HashMap<>();
            // Create a readable prefix from the note title (sanitized)
            var prefixBase = note.title.trim().toLowerCase()
                    .replaceAll("\\s+", "_") // Replace whitespace with underscore
                    .replaceAll("[^a-z0-9_]", "") // Remove non-alphanumeric/underscore chars
                    .replaceAll("_+", "_"); // Collapse multiple underscores
            if (prefixBase.isEmpty() || prefixBase.equals("_")) prefixBase = note.id; // Fallback to ID if title yields nothing useful

            var notePrefix = "entity_" + prefixBase + "_";

            variables.forEach(var -> {
                // Sanitize variable name part as well
                var varNamePart = var.name().substring(1).replaceAll("[^a-zA-Z0-9_]", "");
                groundingMap.put(var, new KifAtom(notePrefix + varNamePart));
            });
            return Unifier.substitute(term, groundingMap);
        }

        private void resolveMismatches(ActionEvent e) {
            if (currentNote == null || reasoner == null) return;

            reasoner.reasonerStatus = "Finding Mismatches for: " + currentNote.title;
            reasoner.updateUIStatus();
            setButtonsEnabled(false);
            final var noteForMismatch = currentNote; // Capture locally

            // Run mismatch finding logic off the EDT
            CompletableFuture.supplyAsync(() -> findPotentialMismatches(noteForMismatch.id), reasoner.llmExecutor)
                    .thenAcceptAsync(mismatches -> {
                        // Handle results on the EDT
                        if (currentNote != noteForMismatch) return; // Selection changed

                        if (mismatches.isEmpty()) {
                            reasoner.reasonerStatus = "No Mismatches Found";
                            JOptionPane.showMessageDialog(this, "No potential rule/fact mismatches found for this note.", "Resolution", JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            reasoner.reasonerStatus = "Mismatch Resolution Dialog";
                            showMismatchResolutionDialog(mismatches, noteForMismatch); // Pass note object
                        }
                    }, SwingUtilities::invokeLater) // Switch to EDT for UI updates/dialog
                    .whenCompleteAsync((res, err) -> {
                        // Final cleanup/error handling on EDT
                        if (currentNote != noteForMismatch && err == null) return; // Selection changed, ignore non-error completion

                        if (err != null) {
                            reasoner.reasonerStatus = "Mismatch Find Error";
                            System.err.println("Error finding mismatches: " + err);
                            err.printStackTrace();
                            JOptionPane.showMessageDialog(this, "Error finding mismatches:\n" + err.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                        setButtonsEnabled(true); // Re-enable buttons
                        reasoner.updateUIStatus(); // Update status bar
                    }, SwingUtilities::invokeLater); // Switch to EDT for final actions
        }

        // Finds potential bridge facts between note assertions and rule antecedents
        private List<PotentialBridge> findPotentialMismatches(String noteId) {
            List<PotentialBridge> potentialBridges = new ArrayList<>();
            var assertionIds = reasoner.noteIdToAssertionIds.getOrDefault(noteId, Set.of());
            if (assertionIds.isEmpty()) return potentialBridges; // No assertions for this note

            // Get the actual Assertion objects for this note
            var noteAssertions = assertionIds.stream()
                    .map(reasoner.assertionsById::get)
                    .filter(Objects::nonNull) // Filter out any potentially retracted assertions
                    .toList();
            if (noteAssertions.isEmpty()) return potentialBridges;

            // Snapshot KB and Beam content for efficient checking
            Set<KifList> existingKbFacts = new HashSet<>(reasoner.kbContentSnapshot);
            final Set<KifList> existingBeamFacts;
            synchronized (reasoner.beam) { // Lock beam briefly to snapshot content
                existingBeamFacts = new HashSet<>(reasoner.beamContentSnapshot);
            }

            // Iterate through rules and their antecedent clauses
            for (var rule : reasoner.rules) {
                for (var antClause : rule.getAntecedentClauses()) {
                    // Check if *any* assertion in the *entire KB* unifies with this clause
                    // This is potentially expensive if the KB is large and unindexed well
                    boolean clauseMatchedInKb = reasoner.findCandidateAssertions(antClause)
                            .anyMatch(kbAssertion -> Unifier.unify(antClause, kbAssertion.fact(), Map.of()) != null);

                    // If the clause is NOT matched in the KB AND it contains variables...
                    if (!clauseMatchedInKb && antClause.containsVariable()) {
                        // ...try matching it against assertions from the *current note*
                        for (var noteAssertion : noteAssertions) {
                            Optional<String> clauseOp = antClause.getOperator();
                            Optional<String> factOp = noteAssertion.fact().getOperator();

                            // Basic check: matching operator and arity (size)
                            if (clauseOp.isPresent() && factOp.isPresent() && clauseOp.equals(factOp) && antClause.size() == noteAssertion.fact().size()) {

                                Map<KifVar, KifTerm> proposalBindings = new HashMap<>();
                                boolean possibleMatch = true;

                                // Try to build bindings: Clause Var -> Note Atom
                                for (int i = 0; i < antClause.size(); i++) {
                                    KifTerm clauseTerm = antClause.get(i);
                                    KifTerm factTerm = noteAssertion.fact().get(i);

                                    if (clauseTerm instanceof KifVar var) {
                                        // If clause has a variable, the corresponding fact term MUST be an atom to propose a binding
                                        if (factTerm instanceof KifAtom atom) {
                                            proposalBindings.put(var, atom);
                                        } else {
                                            possibleMatch = false; // Cannot bind variable to a non-atom (list or variable) from the fact
                                            break;
                                        }
                                    } else if (!clauseTerm.equals(factTerm)) {
                                        // If clause has a constant, it must match the fact's term exactly
                                        possibleMatch = false;
                                        break;
                                    }
                                    // Case: Both clauseTerm and factTerm are identical constants - continue checking
                                }

                                // If a consistent set of bindings (Var -> Atom) was found...
                                if (possibleMatch && !proposalBindings.isEmpty()) {
                                    // ...create the proposed bridge fact by substituting bindings into the clause
                                    var proposedTerm = Unifier.substitute(antClause, proposalBindings);

                                    // Check if the proposed fact is fully ground (no variables) and truly new
                                    if (proposedTerm instanceof KifList proposedFact && !proposedFact.containsVariable()
                                            && !existingKbFacts.contains(proposedFact) && !existingBeamFacts.contains(proposedFact))
                                    {
                                        potentialBridges.add(new PotentialBridge(noteAssertion.fact(), antClause, rule.ruleForm(), proposedFact));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Return distinct bridges (based on the proposed bridge fact)
            return potentialBridges.stream().distinct().toList();
        }


        // Shows the dialog for resolving mismatches
        private void showMismatchResolutionDialog(List<PotentialBridge> mismatches, Note sourceNote) {
            new MismatchResolutionDialog(this, mismatches, reasoner, sourceNote).setVisible(true);
        }

        private void togglePause(ActionEvent e) {
            if (reasoner == null) return;
            boolean pausing = !reasoner.isPaused();
            reasoner.setPaused(pausing); // Toggle pause state in reasoner
            pauseResumeButton.setText(pausing ? "Resume" : "Pause"); // Update button text
            // Status label is updated by reasoner's updateUIStatus called within setPaused or loop
        }

        private void clearAll(ActionEvent e) {
            if (reasoner == null) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Clear all notes, assertions, and rules from the reasoner?\nThis cannot be undone.",
                    "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                reasoner.clearAllKnowledge(); // Tell reasoner to clear its state

                // Clear UI state on EDT
                SwingUtilities.invokeLater(() -> {
                    noteListModel.clear();
                    derivationTextCache.clear(); // Clear derivation cache
                    currentNote = null;
                    updateUIForSelection(); // Update UI to reflect cleared state
                    statusLabel.setText("Status: Knowledge cleared."); // Explicit UI update
                });
            }
        }

        // Entry point for reasoner callbacks to update the UI
        public void handleReasonerCallback(String type, Assertion assertion) {
            // Perform UI updates on the Event Dispatch Thread
            SwingUtilities.invokeLater(() -> {
                // Update derivation cache for the related note (if any)
                String sourceNoteId = assertion.sourceNoteId();
                String relatedNoteId = findRelatedNoteId(assertion); // Find direct or indirect source note

                if (relatedNoteId != null) {
                    updateDerivationCache(relatedNoteId, type, assertion);
                    // If the affected note is the currently selected one, update the view
                    if (currentNote != null && relatedNoteId.equals(currentNote.id)) {
                        derivationView.setText(derivationTextCache.getOrDefault(relatedNoteId, ""));
                        // Try to maintain scroll position (best effort)
                        // derivationView.setCaretPosition(derivationView.getDocument().getLength()); // Scroll to bottom might be annoying
                    }
                } else if (type.equals("assert-retracted") || type.equals("evict")) {
                    // If an assertion with no known note relation is retracted, clear relevant lines from *all* caches
                    clearAssertionLineFromAllCaches(assertion);
                    // Update current view if it was affected
                    if (currentNote != null && derivationTextCache.containsKey(currentNote.id)) {
                        derivationView.setText(derivationTextCache.get(currentNote.id));
                    }
                }
                // Status bar is updated by the reasoner loop's periodic updateUIStatus()
            });
        }

        // Updates the cached derivation text for a specific note ID
        private void updateDerivationCache(String noteId, String type, Assertion assertion) {
            String lineSuffix = String.format("[%s]", assertion.id());
            String fullLine = String.format("P=%.3f %s %s", assertion.probability(), assertion.toKifString(), lineSuffix);

            String currentCachedText = derivationTextCache.computeIfAbsent(noteId, id ->
                    "Derivations for note ID: " + id + "\n--------------------\n" // Initial header if cache miss
            );

            String newCachedText = currentCachedText; // Start with current text

            switch (type) {
                case "assert-added", "assert-input":
                    // Append only if the assertion ID isn't already present (basic duplicate check)
                    if (!currentCachedText.contains(lineSuffix)) {
                        newCachedText = currentCachedText + fullLine + "\n";
                    }
                    break;
                case "assert-retracted", "evict":
                    // Find lines containing the suffix and prepend a marker, avoid double-marking
                    newCachedText = currentCachedText.lines()
                            .map(line -> {
                                if (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#")) {
                                    return "# " + type.toUpperCase() + ": " + line;
                                }
                                return line;
                            })
                            .collect(Collectors.joining("\n")) + "\n"; // Ensure trailing newline
                    break;
            }

            // Update cache only if text changed
            if (!newCachedText.equals(currentCachedText)) {
                derivationTextCache.put(noteId, newCachedText);
            }
        }

        // Removes/Marks lines corresponding to an assertion from all cached derivation texts
        private void clearAssertionLineFromAllCaches(Assertion assertion) {
            String lineSuffix = String.format("[%s]", assertion.id());
            String typeMarker = "# " + (assertion.probability() <= 0 ? "RETRACTED" : "EVICTED") + ": "; // Simplified marker

            derivationTextCache.replaceAll((noteId, text) ->
                    text.lines()
                            .map(line -> {
                                if (line.trim().endsWith(lineSuffix) && !line.trim().startsWith("#")) {
                                    return typeMarker + line;
                                }
                                return line;
                            })
                            .collect(Collectors.joining("\n")) + "\n"
            );
        }


        // Finds the ultimate source note ID by traversing the support chain
        private String findRelatedNoteId(Assertion assertion) {
            if (reasoner == null) return null;
            if (assertion.sourceNoteId() != null) return assertion.sourceNoteId(); // Direct source

            // Breadth-first search up the support chain to find the first ancestor with a note ID
            Queue<String> toCheck = new LinkedList<>(assertion.directSupportingIds());
            Set<String> visited = new HashSet<>(assertion.directSupportingIds());
            visited.add(assertion.id()); // Avoid cycles

            while (!toCheck.isEmpty()) {
                String parentId = toCheck.poll();
                Assertion parent = reasoner.assertionsById.get(parentId); // Look up parent assertion
                if (parent != null) {
                    if (parent.sourceNoteId() != null) {
                        return parent.sourceNoteId(); // Found an ancestor with a note ID
                    }
                    // Add unvisited parents of this parent to the queue
                    parent.directSupportingIds().stream()
                            .filter(visited::add) // Add returns true if the element was not already present
                            .forEach(toCheck::offer);
                }
            }
            return null; // No related note found in the support chain
        }

        // Displays derivations from cache or generates them if needed
        private void displayCachedDerivations(Note note) {
            if (note == null) {
                derivationView.setText("");
                return;
            }
            String cachedText = derivationTextCache.get(note.id);
            if (cachedText == null) {
                // If not cached, generate it now (might happen on initial load or after clear)
                generateAndCacheDerivations(note);
                cachedText = derivationTextCache.getOrDefault(note.id, "No derivations found for: " + note.title);
            }
            derivationView.setText(cachedText);
            derivationView.setCaretPosition(0); // Scroll to top
        }

        // Generates and caches the derivation text for a note
        private void generateAndCacheDerivations(Note note) {
            if (note == null || reasoner == null) return;

            StringBuilder derivationsText = new StringBuilder();
            derivationsText.append("Derivations for: ").append(note.title).append("\n--------------------\n");

            // Retrieve all assertions related to this note (direct or indirect)
            // This requires traversing the support graph, which can be complex.
            // A simpler approach for now: filter all KB assertions.
            // TODO: Optimize derivation display if performance becomes an issue
            reasoner.assertionsById.values().stream()
                    .filter(a -> note.id.equals(findRelatedNoteId(a))) // Check relation
                    .sorted(Comparator.comparingLong(Assertion::timestamp)) // Sort chronologically
                    .forEach(a -> derivationsText.append(String.format("P=%.3f %s [%s]\n", a.probability(), a.toKifString(), a.id())));

            derivationTextCache.put(note.id, derivationsText.toString());
        }


        // Utility to enable/disable buttons based on state
        private void setButtonsEnabled(boolean enabled) {
            addButton.setEnabled(enabled);
            boolean noteSelected = (currentNote != null);
            removeButton.setEnabled(enabled && noteSelected);
            analyzeButton.setEnabled(enabled && noteSelected);
            resolveButton.setEnabled(enabled && noteSelected);
            // Pause/Clear depend on reasoner existence, not selection or general enabled state
            pauseResumeButton.setEnabled(reasoner != null);
            clearAllButton.setEnabled(reasoner != null);
            // Ensure pause button text reflects current state
            if (reasoner != null) {
                pauseResumeButton.setText(reasoner.isPaused() ? "Resume" : "Pause");
            }
        }

        // --- Static Inner Data Classes for UI ---
        static class Note {
            final String id;
            String title;
            String text;

            Note(String id, String title, String text) {
                this.id = Objects.requireNonNull(id);
                this.title = Objects.requireNonNull(title);
                this.text = Objects.requireNonNull(text);
            }
            @Override public String toString() { return title; } // Used by JList rendering
            @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); }
            @Override public int hashCode() { return id.hashCode(); }
        }

        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                // Use JLabel for rendering, allows setting border and font easily
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setBorder(new EmptyBorder(5, 10, 5, 10)); // Add padding
                label.setFont(UI_DEFAULT_FONT); // Ensure correct font
                return label;
            }
        }

        record PotentialBridge(KifList sourceFact, KifList ruleAntecedent, KifList ruleForm, KifList proposedBridge) {
            // Equality and hashCode based only on the proposedBridge for distinct filtering
            @Override public boolean equals(Object o) { return o instanceof PotentialBridge pb && proposedBridge.equals(pb.proposedBridge); }
            @Override public int hashCode() { return proposedBridge.hashCode(); }
        }

        // --- Dialog for Mismatch Resolution ---
        static class MismatchResolutionDialog extends JDialog {
            private final CogNote reasoner;
            private final Note sourceNote; // Store the note object
            private final List<PotentialBridge> mismatches;
            private final List<JCheckBox> checkBoxes = new ArrayList<>();
            private final Map<JButton, Integer> llmButtonIndexMap = new HashMap<>(); // Map button to mismatch index

            MismatchResolutionDialog(Frame owner, List<PotentialBridge> mismatches, CogNote reasoner, Note sourceNote) {
                super(owner, "Resolve Potential Mismatches for: " + sourceNote.title, true); // Modal dialog
                this.mismatches = mismatches;
                this.reasoner = reasoner;
                this.sourceNote = sourceNote;
                setSize(800, 600);
                setLocationRelativeTo(owner);
                setLayout(new BorderLayout(10, 10));

                // Panel to hold all mismatch panels in a vertical layout
                var mainPanel = new JPanel();
                mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
                mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

                // Create a panel for each mismatch
                IntStream.range(0, mismatches.size()).forEach(i -> {
                    mainPanel.add(createMismatchPanel(mismatches.get(i), i));
                    mainPanel.add(Box.createRigidArea(new Dimension(0, 10))); // Spacing
                });

                // Make the main panel scrollable
                var scrollPane = new JScrollPane(mainPanel);
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                scrollPane.getVerticalScrollBar().setUnitIncrement(16); // Improve scroll speed

                // Bottom button panel
                var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                var assertButton = new JButton("Assert Selected");
                var cancelButton = new JButton("Cancel");
                assertButton.setFont(UI_DEFAULT_FONT);
                cancelButton.setFont(UI_DEFAULT_FONT);

                assertButton.addActionListener(this::assertSelectedBridges);
                cancelButton.addActionListener(e -> dispose()); // Close dialog on cancel

                buttonPanel.add(cancelButton);
                buttonPanel.add(assertButton);

                add(scrollPane, BorderLayout.CENTER);
                add(buttonPanel, BorderLayout.SOUTH);
            }

            // Creates a single panel displaying one potential bridge
            private JPanel createMismatchPanel(PotentialBridge bridge, int index) {
                var panel = new JPanel(new BorderLayout(5, 5));
                panel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.LIGHT_GRAY), new EmptyBorder(8, 8, 8, 8))
                );

                // Panel for text labels
                var textPanel = new JPanel();
                textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
                textPanel.add(createWrappingLabel("Source Fact: " + bridge.sourceFact().toKifString(), UI_SMALL_FONT, Color.DARK_GRAY));
                textPanel.add(createWrappingLabel("Rule Antecedent: " + bridge.ruleAntecedent().toKifString(), UI_SMALL_FONT, Color.DARK_GRAY));
                textPanel.add(Box.createRigidArea(new Dimension(0, 5)));
                textPanel.add(createWrappingLabel("Proposed Bridge: " + bridge.proposedBridge().toKifString(), UI_DEFAULT_FONT, Color.BLUE.darker())); // Emphasize proposal

                // Panel for checkbox and LLM button
                var actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                var checkBox = new JCheckBox("Assert this bridge?");
                checkBox.setFont(UI_DEFAULT_FONT);
                checkBoxes.add(checkBox); // Add to list for later retrieval

                var llmButton = new JButton("Ask LLM");
                llmButton.setFont(UI_SMALL_FONT);
                llmButtonIndexMap.put(llmButton, index); // Map button to the index of the mismatch
                llmButton.addActionListener(this::handleLlmButtonClick); // Add listener

                actionPanel.add(checkBox);
                actionPanel.add(llmButton);

                panel.add(textPanel, BorderLayout.CENTER);
                panel.add(actionPanel, BorderLayout.SOUTH);
                return panel;
            }

            // Helper to create labels that wrap within the dialog width
            private JLabel createWrappingLabel(String text, Font font, Color color) {
                // Use HTML for wrapping. Adjust width as needed.
                var label = new JLabel("<html><body style='width: 550px; font-family: " + font.getFamily() + "; font-size: " + font.getSize() + "pt; color: rgb(" + color.getRed() + "," + color.getGreen() + "," + color.getBlue() + ");'>"
                        + text.replace("<", "&lt;").replace(">", "&gt;") // Basic HTML escaping
                        + "</body></html>");
                // Set font and color directly as well (HTML might override, but good practice)
                label.setFont(font);
                label.setForeground(color);
                return label;
            }

            // Handles clicks on the "Ask LLM" button for a specific bridge
            private void handleLlmButtonClick(ActionEvent e) {
                var sourceButton = (JButton) e.getSource();
                int index = llmButtonIndexMap.get(sourceButton); // Get the index associated with the button
                askLlmForBridge(mismatches.get(index), checkBoxes.get(index), sourceButton);
            }

            // Asks the LLM whether the proposed bridge is a reasonable inference
            private void askLlmForBridge(PotentialBridge bridge, JCheckBox associatedCheckbox, JButton sourceButton) {
                var prompt = String.format("""
                                You are an assistant validating logical inferences in KIF (Knowledge Interchange Format).
                                Given the following information:
                                1. A fact derived from a user's note: `%s`
                                2. A pattern required by a rule's antecedent: `%s`
                                3. A proposed specific fact that matches the pattern and could bridge the gap: `%s`

                                Question: Based on common sense or the general SUMO ontology, is it highly likely that the Source Fact implies the Proposed Fact?

                                Answer format: Respond ONLY with the exact Proposed Fact KIF `(...)` if you confirm it's a likely inference. Otherwise, output NOTHING.
                                Do not include explanations, justifications, markdown, or any surrounding text. Just the KIF or nothing.

                                Proposed Fact (output only this if confirmed): `%s`""",
                        bridge.sourceFact().toKifString(),
                        bridge.ruleAntecedent().toKifString(),
                        bridge.proposedBridge().toKifString(),
                        bridge.proposedBridge().toKifString()); // Repeat proposed fact for clarity in prompt

                sourceButton.setEnabled(false); // Disable button during LLM call
                associatedCheckbox.setEnabled(false); // Disable checkbox too
                associatedCheckbox.setText("Asking LLM...");

                var llmNoteId = sourceNote.id + "-bridge-" + System.currentTimeMillis(); // Unique ID for LLM call context

                reasoner.getKifFromLlmAsync(prompt, llmNoteId)
                        .thenAcceptAsync(llmResult -> { // Process result on EDT
                            boolean confirmed = false;
                            if (llmResult != null && !llmResult.isBlank()) {
                                try {
                                    var parsedResults = KifParser.parseKif(llmResult.trim());
                                    // Check if the *single* term returned exactly matches the proposed bridge
                                    if (parsedResults.size() == 1 && parsedResults.getFirst().equals(bridge.proposedBridge())) {
                                        associatedCheckbox.setSelected(true); // Check the box if LLM confirms
                                        associatedCheckbox.setText("Assert (LLM Confirmed)");
                                        confirmed = true;
                                    } else {
                                        System.err.println("LLM bridge response mismatch or multiple terms: " + llmResult);
                                    }
                                } catch (ParseException | NoSuchElementException ex) {
                                    System.err.println("LLM bridge response parse error: " + ex.getMessage() + " | Response: " + llmResult);
                                }
                            }
                            // Update checkbox text if not confirmed
                            if (!confirmed) {
                                associatedCheckbox.setText("Assert this bridge? (LLM Denied/Error)");
                            }
                        }, SwingUtilities::invokeLater) // Ensure UI update is on EDT
                        .whenCompleteAsync((res, err) -> { // Handle completion/errors on EDT
                            associatedCheckbox.setEnabled(true); // Re-enable checkbox
                            sourceButton.setEnabled(true); // Re-enable button

                            if (err != null) {
                                associatedCheckbox.setText("Assert this bridge? (LLM Request Failed)");
                                System.err.println("LLM bridge request failed: " + err.getMessage());
                                err.printStackTrace();
                                // Optionally show an error message to the user
                                JOptionPane.showMessageDialog(this, "LLM request failed for bridge check:\n" + err.getMessage(), "LLM Error", JOptionPane.WARNING_MESSAGE);
                            }
                        }, SwingUtilities::invokeLater); // Ensure final actions are on EDT
            }

            // Asserts the bridges selected by the user via checkboxes
            private void assertSelectedBridges(ActionEvent e) {
                int count = 0;
                for (int i = 0; i < checkBoxes.size(); i++) {
                    if (checkBoxes.get(i).isSelected()) {
                        PotentialBridge bridge = mismatches.get(i);
                        // Submit assertion message with high probability (1.0) and UI source
                        reasoner.submitMessage(new AssertMessage(
                                1.0, // Assert bridge facts with full confidence
                                bridge.proposedBridge().toKifString(),
                                "UI-Bridge",
                                sourceNote.id // Associate bridge fact with the source note
                        ));
                        count++;
                    }
                }
                JOptionPane.showMessageDialog(this, "Submitted " + count + " bridging fact assertions.", "Resolution Complete", JOptionPane.INFORMATION_MESSAGE);
                dispose(); // Close the dialog after assertion
            }
        } // End MismatchResolutionDialog
    } // End SwingUI
} // End CogNote class