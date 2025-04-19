package dumb.cognote11;

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
import java.awt.event.*;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class CogNote extends WebSocketServer {

    public static final String KIF_OPERATOR_IMPLIES = "=>";
    public static final String KIF_OPERATOR_EQUIV = "<=>";
    public static final String KIF_OPERATOR_AND = "and";
    public static final String KIF_OPERATOR_OR = "or";
    public static final String KIF_OPERATOR_EXISTS = "exists";
    public static final String KIF_OPERATOR_FORALL = "forall";
    public static final String KIF_OPERATOR_EQUAL = "=";
    public static final String KIF_OPERATOR_NOT = "not";
    private static final int UI_FONT_SIZE = 18;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");
    private static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_LLM_MODEL = "llamablit";
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
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;
    private static final String MARKER_RETRACTED = "[RETRACTED]";
    private static final String MARKER_EVICTED = "[EVICTED]";
    private static final String MARKER_INPUT = "[Input]";
    private static final String MARKER_ADDED = "[Added]";
    final boolean broadcastInputAssertions;
    private final int capacity;
    private final String llmApiUrl;
    private final String llmModel;
    private final KnowledgeBase knowledgeBase;
    private final ReasonerEngine reasonerEngine;
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private final SwingUI swingUI;
    private final Object pauseLock = new Object();
    private volatile String reasonerStatus = "Idle";
    private volatile boolean running = true;
    private volatile boolean paused = false;

    public CogNote(int port, int capacity, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.capacity = capacity;
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = Objects.requireNonNullElse(llmUrl, DEFAULT_LLM_URL);
        this.llmModel = Objects.requireNonNullElse(llmModel, DEFAULT_LLM_MODEL);
        this.swingUI = Objects.requireNonNull(ui, "SwingUI cannot be null");
        this.knowledgeBase = new KnowledgeBase(this.capacity, this::invokeCallbacks);
        this.reasonerEngine = new ReasonerEngine(knowledgeBase, () -> generateId(""), this::invokeCallbacks, pauseLock);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        System.out.printf("Reasoner config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s%n",
                port, this.capacity, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            var port = 8887;
            var maxKbSize = 64 * 1024;
            String rulesFile = null;
            String llmUrl = null;
            String llmModel = null;
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
                    System.err.printf("Error parsing argument for %s: %s%n", (i > 0 ? args[i - 1] : args[i]), e.getMessage());
                    printUsageAndExit();
                } catch (IllegalArgumentException e) {
                    System.err.println("Argument Error: " + e.getMessage());
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

                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified.");

                ui.setVisible(true);

            } catch (Exception e) {
                System.err.println("Initialization/Startup failed: " + e.getMessage());
                e.printStackTrace();
                Optional.ofNullable(ui).ifPresent(JFrame::dispose);
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
                  --broadcast-input           Broadcast input assertions via WebSocket (default: false)
                """, CogNote.class.getName(), DEFAULT_LLM_URL, DEFAULT_LLM_MODEL);
        System.exit(1);
    }

    static String generateId(String prefix) {
        return prefix + "-" + idCounter.incrementAndGet();
    }

    static boolean isTrivial(KifList list) {
        var s = list.size();
        var opOpt = list.getOperator();
        if (s >= 3) {
            return list.get(1).equals(list.get(2)) &&
                    opOpt.filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OPERATOR_EQUAL)).isPresent();
        } else if (opOpt.filter(KIF_OPERATOR_NOT::equals).isPresent() && s == 2 && list.get(1) instanceof KifList inner) {
            if (inner.size() >= 3) {
                return inner.get(1).equals(inner.get(2)) &&
                        inner.getOperator().filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OPERATOR_EQUAL)).isPresent();
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

    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        var message = switch (type) {
            case CALLBACK_ASSERT_ADDED ->
                    String.format("assert-added %.4f %s [%s]", assertion.pri, kifString, assertion.id);
            case CALLBACK_ASSERT_INPUT ->
                    String.format("assert-input %.4f %s [%s]", assertion.pri, kifString, assertion.id);
            case CALLBACK_ASSERT_RETRACTED -> String.format("retract %s", assertion.id);
            case CALLBACK_EVICT -> String.format("evict %s", assertion.id);
            default -> String.format("%s %.4f %s [%s]", type, assertion.pri, kifString, assertion.id);
        };
        try {
            if (!getConnections().isEmpty()) broadcast(message);
        } catch (Exception e) {
            if (!(e instanceof ConcurrentModificationException || Optional.ofNullable(e.getMessage()).map(m -> m.contains("closed")).orElse(false))) {
                System.err.println("Error during WebSocket broadcast: " + e.getMessage());
            }
        }
    }

    public void loadExpressionsFromFile(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

        var kifBuffer = new StringBuilder();
        int parenDepth = 0, lineNumber = 0;
        long processedCount = 0, queuedCount = 0;

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
                                queueExpressionFromSource(term, "file:" + filename, null);
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
        System.out.printf("Processed %d KIF blocks from %s, attempted to queue %d items.%n", processedCount, filename, queuedCount);
    }

    private void queueExpressionFromSource(KifTerm term, String sourceId, @Nullable String sourceNoteId) {
        switch (term) {
            case KifList list when !list.terms.isEmpty() -> list.getOperator().ifPresentOrElse(
                    op -> {
                        switch (op) {
                            case KIF_OPERATOR_IMPLIES, KIF_OPERATOR_EQUIV -> handleRuleInput(list, sourceId);
                            case KIF_OPERATOR_EXISTS -> handleExists(list, sourceId, sourceNoteId);
                            case KIF_OPERATOR_FORALL -> handleForall(list, sourceId, sourceNoteId);
                            default -> handleStandardAssertionInput(list, sourceId, sourceNoteId);
                        }
                    },
                    () -> handleStandardAssertionInput(list, sourceId, sourceNoteId)
            );
            case KifList ignored -> {
            } // Ignore empty lists
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
                reasonerEngine.addRule(Rule.parseRule(generateId(ID_PREFIX_RULE), reverseList, DEFAULT_RULE_PRIORITY));
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid rule format ignored: " + list.toKifString() + " | Error: " + e.getMessage());
        }
    }

    private void handleStandardAssertionInput(KifList list, String sourceId, @Nullable String sourceNoteId) {
        var isNegated = list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
        if (isNegated && list.size() != 2) {
            System.err.println("Invalid 'not' format ignored (must have 1 argument): " + list.toKifString());
            return;
        }
        if (list.getOperator().filter(op -> !op.equals(KIF_OPERATOR_IMPLIES) && !op.equals(KIF_OPERATOR_EQUIV)).isPresent() && list.containsVariable()) {
            System.err.println("Warning: Non-ground assertion input ignored (use exists/forall or ensure rules bind variables): " + list.toKifString());
            return;
        }

        var isEq = !isNegated && list.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
        var isOriented = isEq && list.size() == 3 && list.get(1).calculateWeight() > list.get(2).calculateWeight();
        var pa = new PotentialAssertion(list, INPUT_ASSERTION_BASE_PRIORITY / (1.0 + list.calculateWeight()), Set.of(), sourceId, isEq, isNegated, isOriented, sourceNoteId);
        submitPotentialAssertion(pa);
    }

    private void handleExists(KifList existsExpr, String sourceId, @Nullable String sourceNoteId) {
        if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar)) {
            System.err.println("Invalid 'exists' format (expected '(exists (?Var | (vars...)) body)'): " + existsExpr.toKifString());
            return;
        }
        var variables = KifTerm.collectVariablesFromSpec(existsExpr.get(1));
        if (variables.isEmpty()) {
            System.out.println("Note: 'exists' with no variables, processing body: " + existsExpr.get(2).toKifString());
            queueExpressionFromSource(existsExpr.get(2), sourceId + "-existsBody", sourceNoteId);
            return;
        }
        Map<KifVar, KifTerm> skolemBindings = variables.stream().collect(Collectors.toUnmodifiableMap(
                v -> v, v -> new KifAtom(ID_PREFIX_SKOLEM + v.name().substring(1) + "_" + idCounter.incrementAndGet())));
        var skolemizedBody = Unifier.subst(existsExpr.get(2), skolemBindings);
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);
        queueExpressionFromSource(skolemizedBody, sourceId + "-skolemized", sourceNoteId);
    }

    private void handleForall(KifList forallExpr, String sourceId, @Nullable String ignoredSourceNoteId) {
        if (forallExpr.size() == 3 && forallExpr.get(2) instanceof KifList bodyList &&
                bodyList.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent()) {
            System.out.println("Interpreting 'forall ... (" + bodyList.getOperator().get() + " ...)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
            handleRuleInput(bodyList, sourceId);
        } else {
            System.err.println("Warning: Ignoring 'forall' expression not directly scoping '=>' or '<=>': " + forallExpr.toKifString());
        }
    }

    public void startReasoner() {
        if (!running) {
            System.err.println("Cannot restart a stopped reasoner.");
            return;
        }
        paused = false;
        reasonerEngine.start();
        try {
            start();
            System.out.println("WebSocket server started on port " + getPort());
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
        var idsToNotify = knowledgeBase.getAllAssertionIds();
        knowledgeBase.clear();
        reasonerEngine.clear();
        noteIdToAssertionIds.clear();
        SwingUtilities.invokeLater(swingUI.derivationTextCache::clear);
        idsToNotify.forEach(id -> {
            var dummyKif = new KifList(new KifAtom("retracted"), new KifAtom(id));
            invokeCallbacks(CALLBACK_ASSERT_RETRACTED, new Assertion(id, dummyKif, 0, 0, null, Set.of(), false, false, false));
        });
        reasonerStatus = "Cleared";
        setPaused(false);
        updateUIStatus();
        System.out.println("Knowledge cleared.");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("WS Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + Objects.requireNonNullElse(reason, "N/A"));
    }

    @Override
    public void onStart() {
        System.out.println("Reasoner WebSocket listener active on port " + getPort() + ".");
        setConnectionLostTimeout(WS_CONNECTION_LOST_TIMEOUT_MS);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        var addr = Optional.ofNullable(conn).map(WebSocket::getRemoteSocketAddress).map(Object::toString).orElse("server");
        if (ex instanceof IOException && Optional.ofNullable(ex.getMessage()).map(m -> m.contains("Socket closed") || m.contains("Connection reset") || m.contains("Broken pipe")).orElse(false)) {
            System.err.println("WS Network Info from " + addr + ": " + ex.getMessage());
        } else {
            System.err.println("WS Error from " + addr + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        var trimmed = message.trim();
        if (trimmed.isEmpty()) return;
        var sourceId = "ws:" + conn.getRemoteSocketAddress().toString();
        var lowerTrimmed = trimmed.toLowerCase();

        if (lowerTrimmed.startsWith("retract ")) {
            var idToRetract = trimmed.substring(8).trim();
            if (!idToRetract.isEmpty()) processRetractionByIdInput(idToRetract, sourceId);
            else System.err.println("WS Retract Error from " + sourceId + ": Missing assertion ID.");
        } else if (lowerTrimmed.startsWith("register_callback ")) {
            System.err.println("WS Callback registration via message is not implemented.");
        } else {
            try {
                var terms = KifParser.parseKif(trimmed);
                if (terms.isEmpty())
                    System.err.printf("WS Received non-KIF or empty KIF message from %s: %s...%n", sourceId, trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
                else terms.forEach(term -> queueExpressionFromSource(term, sourceId, null));
            } catch (ParseException | ClassCastException e) {
                System.err.printf("WS Message Parse Error from %s: %s | Original: %s...%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
            } catch (Exception e) {
                System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void submitPotentialAssertion(PotentialAssertion pa) {
        if (pa == null || isTrivial(pa.kif)) return;
        var isDirectInputSource = !(pa.sourceId().startsWith(ID_PREFIX_RULE) || pa.sourceId().startsWith(ID_PREFIX_FACT));
        if (isDirectInputSource && (pa.sourceNoteId() != null || broadcastInputAssertions)) {
            var inputAssertion = new Assertion(generateId("input"), pa.kif, pa.pri, System.currentTimeMillis(), pa.sourceNoteId(), Set.of(), pa.isEquality(), pa.isOrientedEquality(), pa.isNegated);
            invokeCallbacks(CALLBACK_ASSERT_INPUT, inputAssertion);
        }
        reasonerEngine.submitPotentialAssertion(pa);
    }

    public void submitRule(Rule rule) {
        reasonerEngine.addRule(rule);
        updateUIStatus();
    }

    public void processRetractionByIdInput(String assertionId, String sourceId) {
        var removed = knowledgeBase.retractAssertion(assertionId);
        if (removed != null)
            System.out.printf("Retraction initiated for [%s] by %s: %s%n", assertionId, sourceId, removed.toKifString());
        else
            System.out.printf("Retraction by ID %s from %s failed: ID not found or already retracted.%n", assertionId, sourceId);
        updateUIStatus();
    }

    public void processRetractionByNoteIdInput(String noteId, String sourceId) {
        var idsToRetract = noteIdToAssertionIds.remove(noteId);
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.printf("Initiating retraction of %d assertions for note %s from %s.%n", idsToRetract.size(), noteId, sourceId);
            new HashSet<>(idsToRetract).forEach(id -> processRetractionByIdInput(id, sourceId + "-noteRetract"));
        } else
            System.out.printf("Retraction by Note ID %s from %s failed: No associated assertions found.%n", noteId, sourceId);
        updateUIStatus();
    }

    public void processRetractionRuleInput(String ruleKif, String sourceId) {
        try {
            var terms = KifParser.parseKif(ruleKif);
            if (terms.size() == 1 && terms.getFirst() instanceof KifList ruleForm) {
                var removed = reasonerEngine.removeRule(ruleForm);
                System.out.println("Retract rule from " + sourceId + ": " + (removed ? "Success" : "No match found") + " for: " + ruleForm.toKifString());
            } else
                System.err.println("Retract rule from " + sourceId + ": Input is not a single valid rule KIF list: " + ruleKif);
        } catch (ParseException e) {
            System.err.println("Retract rule from " + sourceId + ": Parse error: " + e.getMessage());
        }
        updateUIStatus();
    }

    public void registerCallback(KifTerm pattern, KifCallback callback) {
        callbackRegistrations.add(new CallbackRegistration(pattern, callback));
        System.out.println("Registered external callback for pattern: " + pattern.toKifString());
    }

    void invokeCallbacks(String type, Assertion assertion) {
        if (CALLBACK_ASSERT_ADDED.equals(type) && assertion.sourceNoteId() != null) {
            noteIdToAssertionIds.computeIfAbsent(assertion.sourceNoteId(), _ -> ConcurrentHashMap.newKeySet()).add(assertion.id);
        } else if ((CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) && assertion.sourceNoteId() != null) {
            noteIdToAssertionIds.computeIfPresent(assertion.sourceNoteId(), (_, set) -> {
                set.remove(assertion.id);
                return set.isEmpty() ? null : set;
            });
        }

        var shouldBroadcast = switch (type) {
            case CALLBACK_ASSERT_ADDED, CALLBACK_ASSERT_RETRACTED, CALLBACK_EVICT -> true;
            case CALLBACK_ASSERT_INPUT -> broadcastInputAssertions;
            default -> false;
        };
        if (shouldBroadcast) broadcastMessage(type, assertion);

        if (swingUI != null && swingUI.isDisplayable()) swingUI.handleReasonerCallback(type, assertion);

        callbackRegistrations.forEach(reg -> {
            try {
                if (CALLBACK_ASSERT_ADDED.equals(type) && Unifier.match(reg.pattern(), assertion.kif, Map.of()) != null) {
                    reg.callback().onMatch(type, assertion, Unifier.match(reg.pattern(), assertion.kif, Map.of()));
                }
            } catch (Exception e) {
                System.err.println("Error executing KIF pattern callback for " + reg.pattern().toKifString() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });

        if (!"Cleared".equals(reasonerStatus)) updateUIStatus();
    }

    void invokeEvictionCallbacks(Assertion evictedAssertion) {
        invokeCallbacks(CALLBACK_EVICT, evictedAssertion);
    }

    private void updateUIStatus() {
        if (swingUI != null && swingUI.isDisplayable()) {
            var statusText = String.format("KB: %d/%d | Tasks: %d | Commits: %d | Rules: %d | Status: %s",
                    knowledgeBase.getAssertionCount(), capacity, reasonerEngine.getTaskQueueSize(), reasonerEngine.getCommitQueueSize(), reasonerEngine.getRuleCount(), reasonerEngine.getCurrentStatus());
            SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
        }
    }

    private CompletableFuture<String> callLlmApiAsync(String prompt, String interactionType, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var payload = new JSONObject().put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false).put("options", new JSONObject().put("temperature", 0.2));
            var request = HttpRequest.newBuilder(URI.create(this.llmApiUrl)).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return extractLlmContent(new JSONObject(new JSONTokener(responseBody))).orElse("");
                } else
                    throw new IOException("LLM API request failed (" + interactionType + "): " + response.statusCode() + " " + responseBody);
            } catch (IOException | InterruptedException e) {
                System.err.printf("LLM API interaction failed (%s for note %s): %s%n", interactionType, noteId, e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException("LLM API communication error (" + interactionType + ")", e);
            } catch (Exception e) {
                System.err.printf("LLM response processing failed (%s for note %s): %s%n", interactionType, noteId, e.getMessage());
                e.printStackTrace();
                throw new CompletionException("LLM response processing error (" + interactionType + ")", e);
            }
        }, httpClient.executor().orElse(Executors.newVirtualThreadPerTaskExecutor()));
    }

    public CompletableFuture<String> getKifFromLlmAsync(String noteText, String noteId) {
        var finalPrompt = """
                Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                Use standard SUMO predicates like 'instance', 'subclass' (preferred over 'subclass_of'), 'domain', 'range', 'attribute', 'partOf', etc.
                Use '=' for equality between terms. Use unique names for new entities derived from the note (start with uppercase, use CamelCase).
                Use '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                Example: (instance Fluffy Cat) (attribute Fluffy OrangeColor) (= (age Fluffy) 3) (not (attribute Fluffy BlackColor))
                
                Note:
                "%s"
                
                KIF Assertions:""".formatted(noteText);

        return callLlmApiAsync(finalPrompt, "KIF Generation", noteId)
                .thenApply(kifResult -> kifResult.lines()
                        .map(s -> s.replaceAll("(?i)```kif", "").replaceAll("```", "").trim())
                        .filter(line -> line.startsWith("(") && line.endsWith(")") && !line.matches("^\\(\\s*\\)$"))
                        .collect(Collectors.joining("\n")))
                .whenComplete((cleanedKif, ex) -> {
                    if (ex == null && cleanedKif.isEmpty())
                        System.err.printf("LLM Warning (KIF %s): Result contained text but no valid KIF lines found after cleaning.%n", noteId);
                });
    }

    public CompletableFuture<String> enhanceNoteWithLlmAsync(String noteText, String noteId) {
        var finalPrompt = """
                You are a helpful assistant. Please revise and enhance the following note for clarity, conciseness, and improved structure. Keep the core meaning intact.
                Focus on improving readability and flow. Correct any grammatical errors or awkward phrasing.
                Output ONLY the revised note text, without any introductory or concluding remarks.
                
                Original Note:
                "%s"
                
                Enhanced Note:""".formatted(noteText);

        return callLlmApiAsync(finalPrompt, "Note Enhancement", noteId);
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
                yield obj.keySet().stream().map(obj::get).map(this::findNestedContent).flatMap(Optional::stream).findFirst();
            }
            case JSONArray arr -> {
                yield IntStream.range(0, arr.length()).mapToObj(arr::get).map(this::findNestedContent).flatMap(Optional::stream).findFirst();
            }
            default -> Optional.empty();
        };
    }

    enum TaskType {MATCH_ANTECEDENT, APPLY_ORDERED_REWRITE}

    sealed interface KifTerm permits KifAtom, KifVar, KifList {
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

    record KifAtom(String value) implements KifTerm {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$");
        private static final Map<String, KifAtom> internCache = new ConcurrentHashMap<>(1024);

        KifAtom {
            Objects.requireNonNull(value);
        }

        public static KifAtom of(String value) {
            return internCache.computeIfAbsent(value, KifAtom::new);
        }

        @Override
        public String toKifString() {
            var needsQuotes = value.isEmpty() || value.chars().anyMatch(Character::isWhitespace) || value.chars().anyMatch(c -> "()\";?".indexOf(c) != -1) || !SAFE_ATOM_PATTERN.matcher(value).matches();
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
            return 1;
        }

        @Override
        public String toString() {
            return "KifAtom[" + value + ']';
        }
    }

    record KifVar(String name) implements KifTerm {
        private static final Map<String, KifVar> internCache = new ConcurrentHashMap<>(256);

        KifVar {
            Objects.requireNonNull(name);
            if (!name.startsWith("?") || name.length() == 1)
                throw new IllegalArgumentException("Variable name must start with '?' and be non-empty: " + name);
        }

        public static KifVar of(String name) {
            return internCache.computeIfAbsent(name, KifVar::new);
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
            return 1;
        }

        @Override
        public String toString() {
            return "KifVar[" + name + ']';
        }
    }

    static final class KifList implements KifTerm {
        final List<KifTerm> terms;
        private volatile Boolean containsVariableCache;
        private volatile Set<KifVar> variablesCache;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;

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
            if (kifStringCache == null)
                kifStringCache = terms.stream().map(KifTerm::toKifString).collect(Collectors.joining(" ", "(", ")"));
            return kifStringCache;
        }

        @Override
        public boolean containsVariable() {
            if (containsVariableCache == null)
                containsVariableCache = terms.stream().anyMatch(KifTerm::containsVariable);
            return containsVariableCache;
        }

        @Override
        public Set<KifVar> getVariables() {
            if (variablesCache == null)
                variablesCache = Collections.unmodifiableSet(terms.stream().flatMap(t -> t.getVariables().stream()).collect(Collectors.toSet()));
            return variablesCache;
        }

        @Override
        public int calculateWeight() {
            if (weightCache == -1) weightCache = 1 + terms.stream().mapToInt(KifTerm::calculateWeight).sum();
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

        @Override
        public String toString() {
            return "KifList" + terms;
        }
    }

    record Assertion(String id, KifList kif, double pri, long timestamp, @Nullable String sourceNoteId,
                     Set<String> support,
                     boolean isEquality, boolean isOrientedEquality,
                     boolean isNegated) implements Comparable<Assertion> {
        Assertion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
            if (isNegated != kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for KIF: " + kif.toKifString());
        }

        @Override
        public int compareTo(Assertion other) {
            return Double.compare(this.pri, other.pri);
        }

        String toKifString() {
            return kif.toKifString();
        }

        KifTerm getEffectiveTerm() {
            return isNegated ? kif.get(1) : kif;
        }
    }

    record Rule(String id, KifList form, KifTerm antecedent, KifTerm consequent, double pri,
                List<KifTerm> antecedents) {
        Rule {
            Objects.requireNonNull(id);
            Objects.requireNonNull(form);
            Objects.requireNonNull(antecedent);
            Objects.requireNonNull(consequent);
            antecedents = List.copyOf(Objects.requireNonNull(antecedents));
        }

        static Rule parseRule(String id, KifList ruleForm, double pri) throws IllegalArgumentException {
            if (!(ruleForm.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent() && ruleForm.size() == 3))
                throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());
            var antTerm = ruleForm.get(1);
            var conTerm = ruleForm.get(2);
            var parsedAntecedents = switch (antTerm) {
                case KifList list when list.getOperator().filter(KIF_OPERATOR_AND::equals).isPresent() ->
                        list.terms().stream().skip(1).map(Rule::validateAntecedentClause).toList();
                case KifList list -> List.of(validateAntecedentClause(list));
                default ->
                        throw new IllegalArgumentException("Antecedent must be a KIF list, (not list), or (and ...): " + antTerm.toKifString());
            };
            validateUnboundVariables(ruleForm, antTerm, conTerm);
            return new Rule(id, ruleForm, antTerm, conTerm, pri, parsedAntecedents);
        }

        private static KifTerm validateAntecedentClause(KifTerm term) {
            if (term instanceof KifList list) {
                if (list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                    if (list.size() == 2 && list.get(1) instanceof KifList) return list;
                    else
                        throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + list.toKifString());
                } else return list;
            }
            throw new IllegalArgumentException("Elements of rule antecedent must be lists or (not list): " + term.toKifString());
        }

        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
            var unboundConsequentVars = new HashSet<>(consequent.getVariables());
            unboundConsequentVars.removeAll(antecedent.getVariables());
            unboundConsequentVars.removeAll(getQuantifierBoundVariables(consequent));
            if (!unboundConsequentVars.isEmpty() && ruleForm.getOperator().filter(KIF_OPERATOR_IMPLIES::equals).isPresent())
                System.err.println("Warning: Rule consequent has variables not bound by antecedent or local quantifier: " + unboundConsequentVars.stream().map(KifVar::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKifString());
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
                case KifList list ->
                        list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
                default -> {
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Rule r && form.equals(r.form));
        }

        @Override
        public int hashCode() {
            return form.hashCode();
        }
    }

    record PotentialAssertion(KifList kif, double pri, Set<String> support, String sourceId, boolean isEquality,
                              boolean isNegated, boolean isOrientedEquality, @Nullable String sourceNoteId) {
        PotentialAssertion {
            Objects.requireNonNull(kif);
            support = Set.copyOf(Objects.requireNonNull(support));
            Objects.requireNonNull(sourceId);
            if (isNegated != kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for potential KIF: " + kif.toKifString());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PotentialAssertion pa && kif.equals(pa.kif);
        }

        @Override
        public int hashCode() {
            return kif.hashCode();
        }

        KifTerm getEffectiveTerm() {
            return isNegated ? kif.get(1) : kif;
        }
    }

    record InferenceTask(TaskType type, double pri, Object data) implements Comparable<InferenceTask> {
        InferenceTask {
            Objects.requireNonNull(type);
            Objects.requireNonNull(data);
        }

        static InferenceTask matchAntecedent(Rule rule, Assertion trigger, Map<KifVar, KifTerm> bindings, double pri) {
            return new InferenceTask(TaskType.MATCH_ANTECEDENT, pri, new MatchContext(rule, trigger.id, bindings));
        }

        static InferenceTask applyRewrite(Assertion rule, Assertion target, double pri) {
            return new InferenceTask(TaskType.APPLY_ORDERED_REWRITE, pri, new RewriteContext(rule, target));
        }

        @Override
        public int compareTo(InferenceTask other) {
            return Double.compare(other.pri, this.pri);
        }
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

    static class PathNode {
        static final Class<KifVar> VAR_MARKER = KifVar.class;
        final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
        final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet();
    }

    static class PathIndex {
        private static final Object LIST_MARKER = new Object();
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
            return findCandidates(queryTerm, this::findUnifiableRecursive);
        }

        Set<String> findInstances(KifTerm queryPattern) {
            return findCandidates(queryPattern, this::findInstancesRecursive);
        }

        Set<String> findGeneralizations(KifTerm queryTerm) {
            return findCandidates(queryTerm, this::findGeneralizationsRecursive);
        }

        private Set<String> findCandidates(KifTerm query, TriConsumer<KifTerm, PathNode, Set<String>> searchFunc) {
            Set<String> candidates = ConcurrentHashMap.newKeySet();
            searchFunc.accept(query, root, candidates);
            return Set.copyOf(candidates);
        }

        private void addPathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return;
            currentNode.assertionIdsHere.add(assertionId);
            var key = getIndexKey(term);
            var termNode = currentNode.children.computeIfAbsent(key, _ -> new PathNode());
            termNode.assertionIdsHere.add(assertionId);
            if (term instanceof KifList list)
                list.terms().forEach(subTerm -> addPathsRecursive(subTerm, assertionId, termNode));
        }

        private boolean removePathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return false;
            currentNode.assertionIdsHere.remove(assertionId);
            var key = getIndexKey(term);
            var termNode = currentNode.children.get(key);
            if (termNode != null) {
                termNode.assertionIdsHere.remove(assertionId);
                var canPruneChild = true;
                if (term instanceof KifList list)
                    canPruneChild = list.terms().stream().allMatch(subTerm -> removePathsRecursive(subTerm, assertionId, termNode));
                if (canPruneChild && termNode.assertionIdsHere.isEmpty() && termNode.children.isEmpty())
                    currentNode.children.remove(key, termNode);
            }
            return currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
        }

        private Object getIndexKey(KifTerm term) {
            return switch (term) {
                case KifAtom a -> a.value();
                case KifVar _ -> PathNode.VAR_MARKER;
                case KifList l -> l.getOperator().map(op -> (Object) op).orElse(LIST_MARKER);
            };
        }

        private void collectAllAssertionIds(PathNode node, Set<String> ids) {
            if (node == null) return;
            ids.addAll(node.assertionIdsHere);
            node.children.values().forEach(child -> collectAllAssertionIds(child, ids));
        }

        private void findUnifiableRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
            if (queryTerm instanceof KifList)
                Optional.ofNullable(indexNode.children.get(LIST_MARKER)).ifPresent(listNode -> collectAllAssertionIds(listNode, candidates));
            var specificNode = indexNode.children.get(getIndexKey(queryTerm));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                if (queryTerm instanceof KifList ql && !ql.terms().isEmpty())
                    findUnifiableRecursive(ql.terms().getFirst(), specificNode, candidates);
                else if (queryTerm instanceof KifList) collectAllAssertionIds(specificNode, candidates);
            }
            if (queryTerm instanceof KifVar)
                indexNode.children.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));
        }

        private void findInstancesRecursive(KifTerm queryPattern, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            if (queryPattern instanceof KifVar) {
                collectAllAssertionIds(indexNode, candidates);
                return;
            }
            var specificNode = indexNode.children.get(getIndexKey(queryPattern));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                if (queryPattern instanceof KifList ql && !ql.terms().isEmpty())
                    findInstancesRecursive(ql.terms().getFirst(), specificNode, candidates);
            }
            Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> {
                candidates.addAll(varNode.assertionIdsHere);
                if (queryPattern instanceof KifList ql && !ql.terms().isEmpty())
                    findInstancesRecursive(ql.terms().getFirst(), varNode, candidates);
            });
            if (queryPattern instanceof KifList ql)
                Optional.ofNullable(indexNode.children.get(LIST_MARKER)).ifPresent(listNode -> {
                    candidates.addAll(listNode.assertionIdsHere);
                    if (!ql.terms().isEmpty()) findInstancesRecursive(ql.terms().getFirst(), listNode, candidates);
                });
        }

        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
            if (queryTerm instanceof KifList ql)
                Optional.ofNullable(indexNode.children.get(LIST_MARKER)).ifPresent(listNode -> {
                    candidates.addAll(listNode.assertionIdsHere);
                    if (!ql.terms().isEmpty())
                        findGeneralizationsRecursive(ql.terms().getFirst(), listNode, candidates);
                });
            Optional.ofNullable(indexNode.children.get(getIndexKey(queryTerm))).ifPresent(nextNode -> {
                candidates.addAll(nextNode.assertionIdsHere);
                if (queryTerm instanceof KifList ql && !ql.terms().isEmpty())
                    findGeneralizationsRecursive(ql.terms().getFirst(), nextNode, candidates);
            });
        }

        @FunctionalInterface
        private interface TriConsumer<T, U, V> {
            void accept(T t, U u, V v);
        }
    }

    static class KnowledgeBase {
        private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
        private final PathIndex pathIndex = new PathIndex();
        private final ConcurrentMap<String, Set<String>> assertionDependencies = new ConcurrentHashMap<>();
        private final PriorityBlockingQueue<Assertion> evictionQueue = new PriorityBlockingQueue<>();
        private final ReadWriteLock kbLock = new ReentrantReadWriteLock();
        private final int maxKbSize;
        private final BiConsumer<String, Assertion> eventCallback;

        KnowledgeBase(int maxKbSize, BiConsumer<String, Assertion> eventCallback) {
            this.maxKbSize = maxKbSize;
            this.eventCallback = Objects.requireNonNull(eventCallback);
        }

        int getAssertionCount() {
            return assertionsById.size();
        }

        List<Assertion> getAllAssertions() {
            kbLock.readLock().lock();
            try {
                return List.copyOf(assertionsById.values());
            } finally {
                kbLock.readLock().unlock();
            }
        }

        List<String> getAllAssertionIds() {
            kbLock.readLock().lock();
            try {
                return List.copyOf(assertionsById.keySet());
            } finally {
                kbLock.readLock().unlock();
            }
        }

        Optional<Assertion> getAssertion(String id) {
            kbLock.readLock().lock();
            try {
                return Optional.ofNullable(assertionsById.get(id));
            } finally {
                kbLock.readLock().unlock();
            }
        }

        boolean isSubsumed(PotentialAssertion pa) {
            kbLock.readLock().lock();
            try {
                return isSubsumedInternal(pa);
            } finally {
                kbLock.readLock().unlock();
            }
        }

        @Nullable Assertion findExactMatch(KifList groundKif) {
            kbLock.readLock().lock();
            try {
                return findExactMatchInternal(groundKif);
            } finally {
                kbLock.readLock().unlock();
            }
        }

        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) {
            kbLock.readLock().lock();
            try {
                return pathIndex.findUnifiable(queryTerm).stream().map(assertionsById::get).filter(Objects::nonNull).toList().stream();
            } finally {
                kbLock.readLock().unlock();
            }
        } // Collect to list to release lock sooner

        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            kbLock.readLock().lock();
            try {
                var patternIsNegated = (queryPattern instanceof KifList ql && ql.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());
                return pathIndex.findInstances(queryPattern).stream().map(assertionsById::get).filter(Objects::nonNull).filter(a -> a.isNegated == patternIsNegated).filter(a -> Unifier.match(queryPattern, a.kif, Map.of()) != null).toList().stream();
            } finally {
                kbLock.readLock().unlock();
            }
        } // Collect to list

        Optional<Assertion> commitAssertion(PotentialAssertion pa, String newId, long timestamp) {
            kbLock.writeLock().lock();
            try {
                if (CogNote.isTrivial(pa.kif) || isSubsumedInternal(pa)) return Optional.empty();
                var newAssertion = new Assertion(newId, pa.kif, pa.pri, timestamp, pa.sourceNoteId(), pa.support(), pa.isEquality(), pa.isOrientedEquality(), pa.isNegated);
                if (!newAssertion.kif.containsVariable() && findExactMatchInternal(newAssertion.kif) != null)
                    return Optional.empty();
                enforceKbCapacityInternal();
                if (assertionsById.size() >= maxKbSize) {
                    System.err.printf("Warning: KB full (%d/%d) after eviction attempt. Cannot add: %s%n", assertionsById.size(), maxKbSize, pa.kif.toKifString());
                    return Optional.empty();
                }
                if (assertionsById.putIfAbsent(newId, newAssertion) != null) {
                    System.err.println("KB Commit Error: ID collision for " + newId);
                    return Optional.empty();
                }
                pathIndex.add(newAssertion);
                evictionQueue.put(newAssertion);
                newAssertion.support().forEach(supporterId -> assertionDependencies.computeIfAbsent(supporterId, _ -> ConcurrentHashMap.newKeySet()).add(newId));
                eventCallback.accept(CALLBACK_ASSERT_ADDED, newAssertion);
                return Optional.of(newAssertion);
            } finally {
                kbLock.writeLock().unlock();
            }
        }

        Assertion retractAssertion(String id) {
            kbLock.writeLock().lock();
            try {
                return retractAssertionWithCascadeInternal(id, new HashSet<>());
            } finally {
                kbLock.writeLock().unlock();
            }
        }

        void clear() {
            kbLock.writeLock().lock();
            try {
                assertionsById.clear();
                pathIndex.clear();
                evictionQueue.clear();
                assertionDependencies.clear();
            } finally {
                kbLock.writeLock().unlock();
            }
        }

        private Assertion retractAssertionWithCascadeInternal(String id, Set<String> retractedInThisCascade) {
            if (!retractedInThisCascade.add(id)) return null;
            var removed = assertionsById.remove(id);
            if (removed != null) {
                pathIndex.remove(removed);
                evictionQueue.remove(removed);
                removed.support().forEach(supporterId -> assertionDependencies.computeIfPresent(supporterId, (_, dependents) -> {
                    dependents.remove(id);
                    return dependents.isEmpty() ? null : dependents;
                }));
                Optional.ofNullable(assertionDependencies.remove(id)).ifPresent(dependents -> new HashSet<>(dependents).forEach(depId -> retractAssertionWithCascadeInternal(depId, retractedInThisCascade)));
                eventCallback.accept(CALLBACK_ASSERT_RETRACTED, removed);
            }
            return removed;
        }

        private boolean isSubsumedInternal(PotentialAssertion pa) {
            return pathIndex.findGeneralizations(pa.kif).stream().map(assertionsById::get).filter(Objects::nonNull)
                    .anyMatch(candidate -> candidate.isNegated == pa.isNegated && Unifier.match(candidate.getEffectiveTerm(), pa.getEffectiveTerm(), Map.of()) != null);
        }

        @Nullable
        private Assertion findExactMatchInternal(KifList groundKif) {
            var queryIsNegated = groundKif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
            return pathIndex.findInstances(groundKif).stream().map(assertionsById::get).filter(Objects::nonNull)
                    .filter(a -> a.isNegated == queryIsNegated && a.kif.equals(groundKif)).findFirst().orElse(null);
        }

        private void enforceKbCapacityInternal() {
            while (assertionsById.size() >= maxKbSize && !evictionQueue.isEmpty()) {
                Optional.ofNullable(evictionQueue.poll()).ifPresent(lowestPri -> Optional.ofNullable(retractAssertionWithCascadeInternal(lowestPri.id, new HashSet<>())).ifPresent(r -> eventCallback.accept(CALLBACK_EVICT, r)));
            }
        }
    }

    static class ReasonerEngine {
        private final KnowledgeBase kb;
        private final Supplier<String> idGenerator;
        private final BiConsumer<String, Assertion> callbackNotifier;
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
            this.callbackNotifier = notifier;
            this.pauseLock = pauseLock;
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

        void start() {
            if (running) return;
            running = true;
            paused = false;
            currentStatus = "Starting";
            commitExecutor.submit(this::commitLoop);
            var workerCount = Math.max(MIN_INFERENCE_WORKERS, Runtime.getRuntime().availableProcessors() / 2);
            IntStream.range(0, workerCount).forEach(i -> inferenceExecutor.submit(this::inferenceLoop));
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
            currentStatus = pause ? "Paused" : "Running";
            if (!pause) synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }

        void clear() {
            setPaused(true);
            rules.clear();
            commitQueue.clear();
            taskQueue.clear();
            System.out.println("Reasoner Engine queues and rules cleared.");
            setPaused(false);
        }

        void submitPotentialAssertion(PotentialAssertion pa) {
            if (!running || pa == null) return;
            try {
                if (!commitQueue.offer(pa, 100, TimeUnit.MILLISECONDS))
                    System.err.println("Warning: Commit queue full. Discarding potential assertion: " + pa.kif.toKifString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while submitting potential assertion to commit queue.");
            }
        }

        void addRule(Rule rule) {
            if (rules.add(rule)) {
                System.out.println("Added rule: " + rule.id + " " + rule.form.toKifString());
                triggerMatchingForNewRule(rule);
            }
        }

        boolean removeRule(KifList ruleForm) {
            return rules.removeIf(rule -> rule.form.equals(ruleForm));
        }

        private void commitLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    waitIfPaused();
                    if (!running) break;
                    currentStatus = "Committing";
                    var pa = commitQueue.take();
                    kb.commitAssertion(pa, generateFactId(pa), System.currentTimeMillis()).ifPresent(this::generateNewTasks);
                    currentStatus = "Idle (Commit)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
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
                    waitIfPaused();
                    if (!running) break;
                    var task = taskQueue.take();
                    currentStatus = "Inferring (" + task.type + ")";
                    switch (task.type) {
                        case MATCH_ANTECEDENT -> handleMatchAntecedentTask(task);
                        case APPLY_ORDERED_REWRITE -> handleApplyRewriteTask(task);
                    }
                    currentStatus = "Idle (Inference)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
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

        private void waitIfPaused() throws InterruptedException {
            synchronized (pauseLock) {
                while (paused && running) pauseLock.wait();
            }
        }

        private void handleMatchAntecedentTask(InferenceTask task) {
            if (task.data instanceof MatchContext(var rule, var triggerId, var initialBindings))
                findMatchesRecursive(rule, rule.antecedents(), initialBindings, Set.of(triggerId)).forEach(matchResult -> processRuleMatchResult(rule, matchResult));
        }

        private void processRuleMatchResult(Rule rule, MatchResult matchResult) {
            var consequentTerm = Unifier.subst(rule.consequent, matchResult.bindings);
            if (consequentTerm instanceof KifList derived) {
                var simplifiedDerived = simplifyLogicalTerm(derived);
                if (simplifiedDerived.getOperator().filter(KIF_OPERATOR_AND::equals).isPresent())
                    processDerivedConjunction(rule, simplifiedDerived, matchResult);
                else processDerivedTerm(rule, simplifiedDerived, matchResult);
            } else if (consequentTerm != null && !(consequentTerm instanceof KifVar))
                System.err.println("Warning: Rule " + rule.id + " derived a non-list consequent after substitution: " + consequentTerm.toKifString());
        }

        private void processDerivedConjunction(Rule rule, KifList conjunction, MatchResult matchResult) {
            conjunction.terms().stream().skip(1).forEach(term -> {
                if (term instanceof KifList conjunct) processDerivedTerm(rule, conjunct, matchResult);
                else
                    System.err.println("Warning: Rule " + rule.id + " derived (and ...) with non-list conjunct: " + term.toKifString());
            });
        }

        private void processDerivedTerm(Rule rule, KifList derivedTerm, MatchResult matchResult) {
            if (derivedTerm.containsVariable() || CogNote.isTrivial(derivedTerm)) {
                if (derivedTerm.containsVariable())
                    System.out.println("Note: Derived non-ground assertion currently ignored: " + derivedTerm.toKifString() + " from rule " + rule.id);
                return;
            }
            var isNegated = derivedTerm.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
            if (isNegated && derivedTerm.size() != 2) {
                System.err.println("Rule " + rule.id + " derived invalid 'not' structure: " + derivedTerm.toKifString());
                return;
            }
            var termWeight = derivedTerm.calculateWeight();
            if (termWeight > MAX_DERIVED_TERM_WEIGHT) {
                System.err.printf("Warning: Derived term weight (%d) exceeds limit (%d). Discarding: %s from rule %s%n", termWeight, MAX_DERIVED_TERM_WEIGHT, derivedTerm.toKifString(), rule.id);
                return;
            }
            var isEq = !isNegated && derivedTerm.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
            var isOriented = isEq && derivedTerm.size() == 3 && derivedTerm.get(1).calculateWeight() > derivedTerm.get(2).calculateWeight();
            submitPotentialAssertion(new PotentialAssertion(derivedTerm, calculateDerivedPri(matchResult.supportIds(), rule.pri), matchResult.supportIds(), rule.id, isEq, isNegated, isOriented, findCommonSourceNodeId(matchResult.supportIds())));
        }

        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifTerm> remainingClauses, Map<KifVar, KifTerm> currentBindings, Set<String> currentSupportIds) {
            if (remainingClauses.isEmpty()) return Stream.of(new MatchResult(currentBindings, currentSupportIds));
            var clauseToMatch = Unifier.substFully(remainingClauses.getFirst(), currentBindings);
            var nextRemainingClauses = remainingClauses.subList(1, remainingClauses.size());
            var clauseIsNegated = (clauseToMatch instanceof KifList l && l.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent());
            var pattern = clauseIsNegated ? ((KifList) clauseToMatch).get(1) : clauseToMatch;
            if (!(pattern instanceof KifList)) {
                System.err.println("Rule " + rule.id + " has invalid antecedent clause structure after substitution: " + clauseToMatch.toKifString());
                return Stream.empty();
            }
            return kb.findUnifiableAssertions(pattern).filter(candidate -> candidate.isNegated == clauseIsNegated)
                    .flatMap(candidate -> Optional.ofNullable(Unifier.unify(pattern, candidate.getEffectiveTerm(), currentBindings))
                            .map(newBindings -> findMatchesRecursive(rule, nextRemainingClauses, newBindings, Stream.concat(currentSupportIds.stream(), Stream.of(candidate.id)).collect(Collectors.toSet())))
                            .orElse(Stream.empty()));
        }

        private void handleApplyRewriteTask(InferenceTask task) {
            if (!(task.data instanceof RewriteContext(var ruleAssertion, var targetAssertion))) return;
            if (ruleAssertion.isNegated || !ruleAssertion.isEquality() || !ruleAssertion.isOrientedEquality() || ruleAssertion.kif.size() != 3)
                return;
            Unifier.rewrite(targetAssertion.kif, ruleAssertion.kif.get(1), ruleAssertion.kif.get(2))
                    .filter(rewrittenTerm -> rewrittenTerm instanceof KifList && !rewrittenTerm.equals(targetAssertion.kif)).map(KifList.class::cast)
                    .filter(Predicate.not(CogNote::isTrivial))
                    .ifPresent(rewrittenList -> {
                        var support = Stream.concat(targetAssertion.support.stream(), Stream.of(targetAssertion.id, ruleAssertion.id)).collect(Collectors.toSet());
                        var isNeg = rewrittenList.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
                        var isEq = !isNeg && rewrittenList.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
                        var isOriented = isEq && rewrittenList.size() == 3 && rewrittenList.get(1).calculateWeight() > rewrittenList.get(2).calculateWeight();
                        submitPotentialAssertion(new PotentialAssertion(rewrittenList, calculateDerivedPri(support, task.pri), support, ruleAssertion.id, isEq, isNeg, isOriented, findCommonSourceNodeId(support)));
                    });
        }

        private void generateNewTasks(Assertion newAssertion) {
            triggerRuleMatchingTasks(newAssertion);
            triggerRewriteTasks(newAssertion);
        }

        private void triggerRuleMatchingTasks(Assertion newAssertion) {
            rules.forEach(rule -> rule.antecedents.forEach(clause -> {
                var clauseIsNegated = (clause instanceof KifList l && l.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent());
                if (clauseIsNegated == newAssertion.isNegated) {
                    var pattern = clauseIsNegated ? ((KifList) clause).get(1) : clause;
                    Optional.ofNullable(Unifier.unify(pattern, newAssertion.getEffectiveTerm(), Map.of()))
                            .ifPresent(bindings -> taskQueue.put(InferenceTask.matchAntecedent(rule, newAssertion, bindings, calculateTaskPri(rule.pri, newAssertion.pri))));
                }
            }));
        }

        private void triggerRewriteTasks(Assertion newAssertion) {
            if (newAssertion.isEquality() && newAssertion.isOrientedEquality() && !newAssertion.isNegated && newAssertion.kif.size() == 3) {
                kb.findUnifiableAssertions(newAssertion.kif.get(1)).filter(target -> !target.id.equals(newAssertion.id)).filter(target -> Unifier.match(newAssertion.kif.get(1), target.getEffectiveTerm(), Map.of()) != null)
                        .forEach(target -> taskQueue.put(InferenceTask.applyRewrite(newAssertion, target, calculateTaskPri(newAssertion.pri, target.pri))));
            } else {
                kb.getAllAssertions().stream().filter(a -> a.isEquality() && a.isOrientedEquality() && !a.isNegated && a.kif.size() == 3)
                        .filter(ruleAssertion -> Unifier.match(ruleAssertion.kif.get(1), newAssertion.getEffectiveTerm(), Map.of()) != null)
                        .forEach(ruleAssertion -> taskQueue.put(InferenceTask.applyRewrite(ruleAssertion, newAssertion, calculateTaskPri(ruleAssertion.pri, newAssertion.pri))));
            }
        }

        private void triggerMatchingForNewRule(Rule newRule) {
            kb.getAllAssertions().forEach(existing -> newRule.antecedents.forEach(clause -> {
                var clauseIsNegated = (clause instanceof KifList l && l.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent());
                if (clauseIsNegated == existing.isNegated) {
                    var pattern = clauseIsNegated ? ((KifList) clause).get(1) : clause;
                    Optional.ofNullable(Unifier.unify(pattern, existing.getEffectiveTerm(), Map.of()))
                            .ifPresent(bindings -> taskQueue.put(InferenceTask.matchAntecedent(newRule, existing, bindings, calculateTaskPri(newRule.pri, existing.pri))));
                }
            }));
        }

        private String generateFactId(PotentialAssertion pa) {
            return ID_PREFIX_FACT + (pa.isEquality ? "-eq" : "") + (pa.isNegated ? "-not" : "") + "-" + idGenerator.get();
        }

        private double calculateDerivedPri(Set<String> supportIds, double basePri) {
            return supportIds.isEmpty() ? basePri : supportIds.stream().map(kb::getAssertion).flatMap(Optional::stream).mapToDouble(Assertion::pri).min().orElse(basePri) * DERIVED_PRIORITY_DECAY;
        }

        private double calculateTaskPri(double p1, double p2) {
            return (p1 + p2) / 2.0;
        }

        @Nullable
        private String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null;
            var firstFound = false;
            Set<String> visited = new HashSet<>();
            Queue<String> toCheck = new LinkedList<>(supportIds);
            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                if (currentId == null || !visited.add(currentId)) continue;
                var assertionOpt = kb.getAssertion(currentId);
                if (assertionOpt.isPresent()) {
                    var assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (!firstFound) {
                            commonId = assertion.sourceNoteId();
                            firstFound = true;
                        } else if (!commonId.equals(assertion.sourceNoteId())) return null;
                    } else assertion.support().forEach(toCheck::offer);
                }
            }
            return commonId;
        }

        private KifList simplifyLogicalTerm(KifList term) {
            var current = term;
            var depth = 0;
            final var maxDepth = 5;
            while (depth < maxDepth) {
                var next = simplifyLogicalTermOnce(current);
                if (next.equals(current)) return current;
                current = next;
                depth++;
            }
            if (depth >= maxDepth)
                System.err.println("Warning: Simplification depth limit reached for: " + term.toKifString());
            return current;
        }

        private KifList simplifyLogicalTermOnce(KifList term) {
            var opOpt = term.getOperator();
            if (opOpt.isPresent()) {
                var op = opOpt.get();
                if ((op.equals(KIF_OPERATOR_OR) || op.equals(KIF_OPERATOR_AND)) && term.size() >= 3) {
                    KifTerm arg1 = term.get(1), arg2 = term.get(2);
                    var simplifiedArg1 = (arg1 instanceof KifList l1) ? simplifyLogicalTermOnce(l1) : arg1;
                    var simplifiedArg2 = (arg2 instanceof KifList l2) ? simplifyLogicalTermOnce(l2) : arg2;
                    if (simplifiedArg1.equals(simplifiedArg2) && simplifiedArg1 instanceof KifList l)
                        return l; // (and X X) -> X
                    if (term.size() == 3 && (simplifiedArg1 != arg1 || simplifiedArg2 != arg2))
                        return new KifList(term.get(0), simplifiedArg1, simplifiedArg2);
                } else if (op.equals(KIF_OPERATOR_NOT) && term.size() == 2 && term.get(1) instanceof KifList negList && negList.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent() && negList.size() == 2) {
                    var inner = negList.get(1);
                    if (inner instanceof KifList l) return simplifyLogicalTermOnce(l); // (not (not X)) -> X
                }
            }
            var changed = false;
            List<KifTerm> newTerms = new ArrayList<>(term.size());
            for (var subTerm : term.terms()) {
                var simplifiedSub = (subTerm instanceof KifList sl) ? simplifyLogicalTermOnce(sl) : subTerm;
                if (simplifiedSub != subTerm) changed = true;
                newTerms.add(simplifiedSub);
            }
            return changed ? new KifList(newTerms) : term;
        }

        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {
        }
    }

    static class KifParser {
        private final Reader reader;
        private int currentChar = -2;
        private int line = 1;
        private int col = 0;

        private KifParser(Reader reader) {
            this.reader = reader;
        }

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
                var next = peek();
                if (next == ')') {
                    consumeChar(')');
                    return new KifList(terms);
                }
                if (next == -1) throw createParseException("Unmatched parenthesis");
                terms.add(parseTerm());
            }
        }

        private KifVar parseVariable() throws IOException, ParseException {
            consumeChar('?');
            var sb = new StringBuilder("?");
            if (!isValidAtomChar(peek())) throw createParseException("Variable name expected after '?'");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            if (sb.length() == 1) throw createParseException("Empty variable name");
            return KifVar.of(sb.toString());
        }

        private KifAtom parseAtom() throws IOException, ParseException {
            var sb = new StringBuilder();
            if (!isValidAtomChar(peek())) throw createParseException("Invalid start of atom");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            if (sb.isEmpty()) throw createParseException("Empty atom");
            return KifAtom.of(sb.toString());
        }

        private boolean isValidAtomChar(int c) {
            return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';';
        }

        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                var c = consumeChar();
                if (c == '"') return KifAtom.of(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote");
                if (c == '\\') {
                    var next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\', '"' -> next;
                        default -> {
                            System.err.printf("Warning: Unknown escape '\\%c' at line %d, col %d%n", (char) next, line, col - 1);
                            yield next;
                        }
                    });
                } else sb.append((char) c);
            }
        }

        private int peek() throws IOException {
            if (currentChar == -2) currentChar = reader.read();
            return currentChar;
        }

        private int consumeChar() throws IOException {
            var c = peek();
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
            var actual = consumeChar();
            if (actual != expected)
                throw createParseException("Expected '" + expected + "' but found " + ((actual == -1) ? "EOF" : "'" + (char) actual + "'"));
        }

        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                var c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) consumeChar();
                else if (c == ';') {
                    while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                } else break;
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

    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 50;

        @Nullable
        static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            return unifyRecursive(x, y, bindings);
        }

        @Nullable
        static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            return matchRecursive(pattern, term, bindings);
        }

        static KifTerm subst(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return substFully(term, bindings);
        }

        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            return rewriteRecursive(target, lhsPattern, rhsTemplate);
        }

        @Nullable
        private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null;
            var xSubst = substFully(x, bindings);
            var ySubst = substFully(y, bindings);
            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true);
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true);
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lx.size(); i++) {
                    if ((currentBindings = unifyRecursive(lx.get(i), ly.get(i), currentBindings)) == null) return null;
                }
                return currentBindings;
            }
            return null;
        }

        @Nullable
        private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            if (bindings == null) return null;
            var patternSubst = substFully(pattern, bindings);
            if (patternSubst instanceof KifVar varP) return bindVariable(varP, term, bindings, false);
            if (patternSubst.equals(term)) return bindings;
            if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lp.size(); i++) {
                    if ((currentBindings = matchRecursive(lp.get(i), lt.get(i), currentBindings)) == null) return null;
                }
                return currentBindings;
            }
            return null;
        }

        static KifTerm substFully(KifTerm term, Map<KifVar, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term;
            var current = term;
            for (var depth = 0; depth < MAX_SUBST_DEPTH; depth++) {
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
                    var changed = false;
                    List<KifTerm> newTerms = new ArrayList<>(list.size());
                    for (var sub : list.terms()) {
                        var substSub = substituteOnce(sub, bindings);
                        if (substSub != sub) changed = true;
                        newTerms.add(substSub);
                    }
                    yield changed ? new KifList(newTerms) : list;
                }
            };
        }

        @Nullable
        private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean doOccursCheck) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var))
                return doOccursCheck ? unifyRecursive(bindings.get(var), value, bindings) : matchRecursive(bindings.get(var), value, bindings);
            var finalValue = substFully(value, bindings);
            if (doOccursCheck && occursCheckRecursive(var, finalValue, bindings)) return null;
            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheckRecursive(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings) {
            var substTerm = substFully(term, bindings);
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheckRecursive(var, t, bindings));
                case KifAtom ignored -> false;
            };
        }

        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            return Optional.ofNullable(match(lhsPattern, target, Map.of())).map(bindings -> substFully(rhsTemplate, bindings)).or(() ->
                    (target instanceof KifList targetList) ? targetList.terms().stream().map(subTerm -> rewriteRecursive(subTerm, lhsPattern, rhsTemplate)).collect(Collectors.collectingAndThen(Collectors.toList(), rewrittenSubs -> {
                        var changed = false;
                        List<KifTerm> newSubTerms = new ArrayList<>(targetList.size());
                        for (var i = 0; i < targetList.size(); i++) {
                            var opt = rewrittenSubs.get(i);
                            if (opt.isPresent()) {
                                changed = true;
                                newSubTerms.add(opt.get());
                            } else newSubTerms.add(targetList.get(i));
                        }
                        return changed ? Optional.of(new KifList(newSubTerms)) : Optional.<KifTerm>empty();
                    }))
                            : Optional.empty()
            );
        }
    }

    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing...");
        final JTextArea derivationView = new JTextArea();
        final Map<String, String> derivationTextCache = new ConcurrentHashMap<>();
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JButton addButton = new JButton("Add Note"), pauseResumeButton = new JButton("Pause"), clearAllButton = new JButton("Clear All");
        private final JPopupMenu noteContextMenu = new JPopupMenu();
        private final JMenuItem analyzeItem = new JMenuItem("Analyze Note (LLM -> KIF)"), enhanceItem = new JMenuItem("Enhance Note (LLM Replace)"), renameItem = new JMenuItem("Rename Note"), removeItem = new JMenuItem("Remove Note");
        private final boolean showInputCallbacksInUI;
        private CogNote reasoner;
        private Note currentNote = null;

        public SwingUI(@Nullable CogNote reasoner) {
            super("Cognote - Concurrent KIF Reasoner");
            this.reasoner = reasoner;
            this.showInputCallbacksInUI = Optional.ofNullable(reasoner).map(r -> r.broadcastInputAssertions).orElse(false);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(1200, 800);
            setLocationRelativeTo(null);
            setupFonts();
            setupComponents();
            setupLayout();
            setupActionListeners();
            setupWindowListener();
            updateUIForSelection();
        }

        void setReasoner(CogNote reasoner) {
            this.reasoner = reasoner;
            updateUIForSelection();
        }

        private void setupFonts() {
            Stream.of(noteList, noteEditor, addButton, pauseResumeButton, clearAllButton, statusLabel, analyzeItem, enhanceItem, renameItem, removeItem).forEach(c -> c.setFont(UI_DEFAULT_FONT));
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
            Stream.of(analyzeItem, enhanceItem, renameItem).forEach(noteContextMenu::add);
            noteContextMenu.addSeparator();
            noteContextMenu.add(removeItem);
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
            Stream.of(addButton, pauseResumeButton, clearAllButton).forEach(buttonPanel::add);
            var bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(buttonPanel, BorderLayout.WEST);
            bottomPanel.add(statusLabel, BorderLayout.CENTER);
            bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            getContentPane().setLayout(new BorderLayout());
            getContentPane().add(mainSplit, BorderLayout.CENTER);
            getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupActionListeners() {
            addButton.addActionListener(this::addNote);
            pauseResumeButton.addActionListener(this::togglePause);
            clearAllButton.addActionListener(this::clearAll);
            analyzeItem.addActionListener(this::analyzeNoteAction);
            enhanceItem.addActionListener(this::enhanceNoteAction);
            renameItem.addActionListener(this::renameNoteAction);
            removeItem.addActionListener(this::removeNoteAction);
            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    saveCurrentNoteText();
                    currentNote = noteList.getSelectedValue();
                    updateUIForSelection();
                }
            });
            noteEditor.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent evt) {
                    saveCurrentNoteText();
                }
            });
            noteList.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowPopup(e);
                }

                private void maybeShowPopup(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        var index = noteList.locationToIndex(e.getPoint());
                        if (index != -1) {
                            noteList.setSelectedIndex(index);
                            noteContextMenu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
            });
        }

        private void setupWindowListener() {
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    saveCurrentNoteText();
                    Optional.ofNullable(SwingUI.this.reasoner).ifPresent(CogNote::stopReasoner);
                    dispose();
                    System.exit(0);
                }
            });
        }

        private void saveCurrentNoteText() {
            Optional.ofNullable(currentNote).filter(_ -> noteEditor.isEnabled()).ifPresent(n -> n.text = noteEditor.getText());
        }

        private void updateUIForSelection() {
            var noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected);
            Optional.ofNullable(reasoner).ifPresent(r -> pauseResumeButton.setText(r.isPaused() ? "Resume" : "Pause"));
            if (noteSelected) {
                noteEditor.setText(currentNote.text);
                noteEditor.setCaretPosition(0);
                displayCachedDerivations(currentNote);
                setTitle("Cognote - " + currentNote.title);
                SwingUtilities.invokeLater(noteEditor::requestFocusInWindow);
            } else {
                noteEditor.setText("");
                derivationView.setText("");
                setTitle("Cognote - Concurrent KIF Reasoner");
            }
            setControlsEnabled(true);
        }

        private void addNote(ActionEvent e) {
            Optional.ofNullable(JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE)).map(String::trim).filter(Predicate.not(String::isEmpty)).ifPresent(t -> {
                var newNote = new Note(generateId(ID_PREFIX_NOTE), t, "");
                noteListModel.addElement(newNote);
                noteList.setSelectedValue(newNote, true);
            });
        }

        private void removeNoteAction(ActionEvent e) {
            performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions (including derived)?", JOptionPane.WARNING_MESSAGE, noteToRemove -> {
                reasoner.processRetractionByNoteIdInput(noteToRemove.id, "UI-Remove");
                derivationTextCache.remove(noteToRemove.id);
                var idx = noteList.getSelectedIndex();
                noteListModel.removeElement(noteToRemove);
                if (!noteListModel.isEmpty())
                    noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
                else {
                    currentNote = null;
                    updateUIForSelection();
                }
            });
        }

        private void renameNoteAction(ActionEvent e) {
            Optional.ofNullable(currentNote).flatMap(note -> Optional.ofNullable(JOptionPane.showInputDialog(this, "Enter new title for '" + note.title + "':", "Rename Note", JOptionPane.PLAIN_MESSAGE, null, null, note.title)).map(Object::toString).map(String::trim).filter(Predicate.not(String::isEmpty)).filter(nt -> !nt.equals(note.title)).map(nt -> {
                note.title = nt;
                return note;
            })).ifPresent(note -> {
                noteListModel.setElementAt(note, noteList.getSelectedIndex());
                setTitle("Cognote - " + note.title);
                statusLabel.setText("Status: Renamed note to '" + note.title + "'.");
            });
        }

        private void enhanceNoteAction(ActionEvent e) {
            performNoteActionAsync("Enhancing", note -> {
                derivationView.setText(String.format("Enhancing Note '%s' via LLM...\n--------------------\n%s", note.title, note.text));
                derivationView.setCaretPosition(0);
                return reasoner.enhanceNoteWithLlmAsync(note.text, note.id);
            }, this::processLlmEnhancementResponse, this::handleLlmFailure);
        }

        private void analyzeNoteAction(ActionEvent e) {
            performNoteActionAsync("Analyzing", note -> {
                derivationTextCache.remove(note.id);
                derivationView.setText(String.format("Analyzing Note '%s' via LLM...\nRetracting previous assertions...\n--------------------\n", note.title));
                derivationView.setCaretPosition(0);
                reasoner.processRetractionByNoteIdInput(note.id, "UI-Analyze-Retract");
                return reasoner.getKifFromLlmAsync(note.text, note.id);
            }, this::processLlmKifResponse, this::handleLlmFailure);
        }

        private void performNoteAction(String actionName, String confirmTitle, String confirmMsgFormat, int confirmMsgType, NoteAction action) {
            Optional.ofNullable(currentNote).filter(_ -> reasoner != null).ifPresent(note -> {
                if (JOptionPane.showConfirmDialog(this, String.format(confirmMsgFormat, note.title), confirmTitle, JOptionPane.YES_NO_OPTION, confirmMsgType) == JOptionPane.YES_OPTION) {
                    statusLabel.setText(String.format("Status: %s '%s'...", actionName, note.title));
                    setControlsEnabled(false);
                    try {
                        action.execute(note);
                        statusLabel.setText(String.format("Status: Finished %s '%s'.", actionName, note.title));
                    } catch (Exception ex) {
                        statusLabel.setText(String.format("Status: Error %s '%s'.", actionName, note.title));
                        System.err.println("Error during " + actionName + ": " + ex.getMessage());
                        ex.printStackTrace();
                    } finally {
                        setControlsEnabled(true);
                        Optional.ofNullable(reasoner).ifPresent(CogNote::updateUIStatus);
                    }
                }
            });
        }

        private <T> void performNoteActionAsync(String actionName, NoteAsyncAction<T> asyncAction, BiConsumer<T, Note> successCallback, BiConsumer<Throwable, Note> failureCallback) {
            Optional.ofNullable(currentNote).filter(_ -> reasoner != null).ifPresent(noteForAction -> {
                reasoner.reasonerStatus = actionName + " Note: " + noteForAction.title;
                reasoner.updateUIStatus();
                setControlsEnabled(false);
                saveCurrentNoteText();
                try {
                    asyncAction.execute(noteForAction)
                            .thenAcceptAsync(result -> Optional.ofNullable(currentNote).filter(n -> n == noteForAction).ifPresent(n -> successCallback.accept(result, n)), SwingUtilities::invokeLater)
                            .exceptionallyAsync(ex -> {
                                Optional.ofNullable(currentNote).filter(n -> n == noteForAction).ifPresent(n -> failureCallback.accept(ex, n));
                                return null;
                            }, SwingUtilities::invokeLater)
                            .thenRunAsync(() -> Optional.ofNullable(currentNote).filter(n -> n == noteForAction).ifPresent(n -> {
                                setControlsEnabled(true);
                                Optional.ofNullable(reasoner).ifPresent(CogNote::updateUIStatus);
                            }), SwingUtilities::invokeLater);
                } catch (Exception e) {
                    failureCallback.accept(e, noteForAction);
                    setControlsEnabled(true);
                    Optional.ofNullable(reasoner).ifPresent(CogNote::updateUIStatus);
                }
            });
        }

        private void processLlmEnhancementResponse(String enhancedText, Note enhancedNote) {
            if (enhancedText != null && !enhancedText.isBlank()) {
                enhancedNote.text = enhancedText.trim();
                noteEditor.setText(enhancedNote.text);
                noteEditor.setCaretPosition(0);
                Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = String.format("Enhanced note '%s'", enhancedNote.title));
                derivationView.setText(derivationView.getText() + "\n\n--- ENHANCED NOTE --- \n" + enhancedNote.text);
            } else {
                Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = String.format("Enhancement failed for '%s': Empty response", enhancedNote.title));
                derivationView.setText(derivationView.getText() + "\n\n--- ENHANCEMENT FAILED (Empty Response) ---");
                JOptionPane.showMessageDialog(this, "LLM returned empty enhancement.", "Enhancement Failed", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void processLlmKifResponse(String kifString, Note analyzedNote) {
            try {
                var terms = KifParser.parseKif(kifString);
                var log = new StringBuilder("LLM KIF Analysis for: " + analyzedNote.title + "\n--------------------\n");
                var counts = new int[]{0, 0}; // [submitted, skipped]
                for (var term : terms) {
                    if (term instanceof KifList list && !list.terms.isEmpty()) {
                        var processedTerm = groundLlmTerm(list, analyzedNote);
                        if (processedTerm instanceof KifList pList && !pList.terms.isEmpty() && !CogNote.isTrivial(pList)) {
                            var isNegated = pList.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
                            if (isNegated && pList.size() != 2) {
                                log.append("Skipped (invalid 'not'): ").append(pList.toKifString()).append("\n");
                                counts[1]++;
                                continue;
                            }
                            var isEq = !isNegated && pList.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
                            var isOriented = isEq && pList.size() == 3 && pList.get(1).calculateWeight() > pList.get(2).calculateWeight();
                            var pa = new PotentialAssertion(pList, LLM_ASSERTION_BASE_PRIORITY / (1.0 + pList.calculateWeight()), Set.of(), "UI-LLM", isEq, isNegated, isOriented, analyzedNote.id);
                            Optional.ofNullable(reasoner).ifPresent(r -> r.submitPotentialAssertion(pa));
                            log.append("Submitted: ").append(pList.toKifString()).append("\n");
                            counts[0]++;
                        } else {
                            log.append("Skipped (trivial/non-list/empty): ").append(list.toKifString()).append("\n");
                            counts[1]++;
                        }
                    } else if (term != null) {
                        log.append("Skipped (non-list/empty): ").append(term.toKifString()).append("\n");
                        counts[1]++;
                    }
                }
                Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = String.format("Analyzed '%s': %d submitted, %d skipped", analyzedNote.title, counts[0], counts[1]));
                var initialLog = log.toString() + "\n-- Derivations Follow --\n";
                derivationTextCache.put(analyzedNote.id, initialLog);
                derivationView.setText(initialLog);
                derivationView.setCaretPosition(0);
            } catch (ParseException pe) {
                handleLlmFailure(pe, analyzedNote);
                derivationView.setText("Error parsing KIF from LLM.\n--------------------\n" + kifString);
            } catch (Exception ex) {
                handleLlmFailure(ex, analyzedNote);
            }
        }

        private void handleLlmFailure(Throwable ex, Note contextNote) {
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            var action = (ex instanceof ParseException) ? "KIF Parse Error" : "LLM Communication Failed";
            Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = action + " for " + contextNote.title);
            System.err.println(action + " for note '" + contextNote.title + "': " + cause.getMessage());
            cause.printStackTrace();
            JOptionPane.showMessageDialog(this, action + ":\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
            var errorMsg = "\n\n--- " + action.toUpperCase() + " --- \n" + cause.getMessage();
            derivationView.setText(derivationView.getText().matches("(?s).*(Analyzing|Enhancing).*") ? derivationView.getText() + errorMsg : action + " for '" + contextNote.title + "'.\n--------------------\n" + cause.getMessage());
        }

        private KifTerm groundLlmTerm(KifTerm term, Note note) {
            var variables = term.getVariables();
            if (variables.isEmpty()) return term;
            Map<KifVar, KifTerm> groundingMap = new HashMap<>();
            var prefix = ID_PREFIX_ENTITY + note.id + "_";
            variables.forEach(var -> groundingMap.put(var, KifAtom.of(prefix + var.name().substring(1).replaceAll("[^a-zA-Z0-9_]", ""))));
            return Unifier.subst(term, groundingMap);
        }

        private void togglePause(ActionEvent e) {
            Optional.ofNullable(reasoner).ifPresent(r -> {
                var pausing = !r.isPaused();
                r.setPaused(pausing);
                pauseResumeButton.setText(pausing ? "Resume" : "Pause");
            });
        }

        private void clearAll(ActionEvent e) {
            Optional.ofNullable(reasoner).ifPresent(r -> {
                if (JOptionPane.showConfirmDialog(this, "Clear all notes, assertions, and rules? This cannot be undone.", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                    r.clearAllKnowledge();
                    SwingUtilities.invokeLater(() -> {
                        noteListModel.clear();
                        derivationTextCache.clear();
                        currentNote = null;
                        updateUIForSelection();
                        statusLabel.setText("Status: Knowledge cleared.");
                    });
                }
            });
        }

        public void handleReasonerCallback(String type, Assertion assertion) {
            SwingUtilities.invokeLater(() -> {
                var noteId = assertion.sourceNoteId();
                if (CALLBACK_ASSERT_INPUT.equals(type) && !showInputCallbacksInUI) return;
                if (noteId != null) {
                    updateDerivationCacheForNote(noteId, type, assertion);
                    Optional.ofNullable(currentNote).filter(cn -> noteId.equals(cn.id)).ifPresent(cn -> derivationView.setText(derivationTextCache.getOrDefault(noteId, "")));
                } else if (CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) {
                    markAssertionRemovedInAllCaches(assertion, type);
                    Optional.ofNullable(currentNote).filter(cn -> derivationTextCache.containsKey(cn.id)).ifPresent(cn -> derivationView.setText(derivationTextCache.get(cn.id)));
                }
            });
        }

        private void updateDerivationCacheForNote(String noteId, String type, Assertion assertion) {
            var suffix = " [" + assertion.id + "]";
            var prefix = String.format("Prio=%.3f ", assertion.pri);
            var marker = switch (type) {
                case CALLBACK_ASSERT_INPUT -> MARKER_INPUT + " ";
                case CALLBACK_ASSERT_ADDED -> MARKER_ADDED + " ";
                default -> "";
            };
            var fullLine = marker + prefix + assertion.toKifString() + suffix;
            var header = "Derivations/Inputs for note: " + getNoteTitleById(noteId) + "\n--------------------\n";
            var removalMarker = switch (type) {
                case CALLBACK_ASSERT_RETRACTED -> MARKER_RETRACTED;
                case CALLBACK_EVICT -> MARKER_EVICTED;
                default -> null;
            };
            derivationTextCache.compute(noteId, (_, currentText) -> {
                var text = (currentText == null || currentText.isBlank()) ? header : currentText;
                return switch (type) {
                    case CALLBACK_ASSERT_ADDED, CALLBACK_ASSERT_INPUT ->
                            text.contains(suffix) ? text : text + fullLine + "\n";
                    case CALLBACK_ASSERT_RETRACTED, CALLBACK_EVICT -> markLine(text, suffix, removalMarker);
                    default -> text;
                };
            });
        }

        private void markAssertionRemovedInAllCaches(Assertion assertion, String type) {
            var suffix = " [" + assertion.id + "]";
            var marker = switch (type) {
                case CALLBACK_ASSERT_RETRACTED -> MARKER_RETRACTED;
                case CALLBACK_EVICT -> MARKER_EVICTED;
                default -> null;
            };
            if (marker != null) derivationTextCache.replaceAll((noteId, text) -> markLine(text, suffix, marker));
        }

        private String markLine(String text, String suffix, String marker) {
            return text.lines().map(line -> (line.trim().endsWith(suffix) && !line.trim().startsWith("[")) ? marker + " " + line : line).collect(Collectors.joining("\n", "", "\n"));
        }

        private void displayCachedDerivations(Note note) {
            if (note == null) {
                derivationView.setText("");
                return;
            }
            var header = "Derivations/Inputs for: " + note.title + "\n--------------------\n";
            derivationView.setText(derivationTextCache.getOrDefault(note.id, header + "(No assertions cached or generated yet)\n"));
            derivationView.setCaretPosition(0);
        }

        private String getNoteTitleById(String noteId) {
            return IntStream.range(0, noteListModel.size()).mapToObj(noteListModel::getElementAt).filter(n -> n.id.equals(noteId)).map(n -> n.title).findFirst().orElse(noteId);
        }

        private void setControlsEnabled(boolean enabled) {
            var noteSelected = (noteList.getSelectedIndex() != -1);
            var reasonerReady = (reasoner != null);
            addButton.setEnabled(enabled);
            pauseResumeButton.setEnabled(enabled && reasonerReady);
            clearAllButton.setEnabled(enabled && reasonerReady);
            analyzeItem.setEnabled(enabled && noteSelected && reasonerReady);
            enhanceItem.setEnabled(enabled && noteSelected && reasonerReady);
            renameItem.setEnabled(enabled && noteSelected);
            removeItem.setEnabled(enabled && noteSelected && reasonerReady);
            Optional.ofNullable(reasoner).filter(_ -> enabled).ifPresent(r -> pauseResumeButton.setText(r.isPaused() ? "Resume" : "Pause"));
        }

        @FunctionalInterface
        interface NoteAction {
            void execute(Note note);
        }

        @FunctionalInterface
        interface NoteAsyncAction<T> {
            CompletableFuture<T> execute(Note note);
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
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                var label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setBorder(new EmptyBorder(5, 10, 5, 10));
                label.setFont(UI_DEFAULT_FONT);
                return label;
            }
        }
    }
}
