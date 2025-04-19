package dumb.cognote12;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

public class CogNote extends WebSocketServer {

    public static final String KIF_OPERATOR_IMPLIES = "=>";
    public static final String KIF_OPERATOR_EQUIV = "<=>";
    public static final String KIF_OPERATOR_AND = "and";
    public static final String KIF_OPERATOR_OR = "or";
    public static final String KIF_OPERATOR_EXISTS = "exists";
    public static final String KIF_OPERATOR_FORALL = "forall";
    public static final String KIF_OPERATOR_EQUAL = "=";
    public static final String KIF_OPERATOR_NOT = "not";
    private static final int UI_FONT_SIZE = 16; // Adjusted for potentially denser list view
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);
    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");
    private static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_LLM_MODEL = "llamablit";
    private static final String CALLBACK_ASSERT_ADDED = "assert-added";
    private static final String CALLBACK_ASSERT_INPUT = "assert-input";
    private static final String CALLBACK_ASSERT_RETRACTED = "assert-retracted";
    private static final String CALLBACK_EVICT = "evict";
    private static final String CALLBACK_LLM_RESPONSE = "llm-response"; // New callback type
    private static final String ID_PREFIX_RULE = "rule";
    private static final String ID_PREFIX_FACT = "fact";
    private static final String ID_PREFIX_SKOLEM_FUNC = "skf_";
    private static final String ID_PREFIX_SKOLEM_CONST = "skc_";
    private static final String ID_PREFIX_ENTITY = "entity_";
    private static final String ID_PREFIX_NOTE = "note-";
    private static final String ID_PREFIX_LLM_ITEM = "llm_"; // Prefix for LLM-generated items in UI list
    private static final double DEFAULT_RULE_PRIORITY = 1.0;
    private static final double INPUT_ASSERTION_BASE_PRIORITY = 10.0;
    private static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    private static final double DERIVED_PRIORITY_DECAY = 0.95;
    private static final int HTTP_TIMEOUT_SECONDS = 90; // Increased timeout for potentially longer LLM calls
    private static final int WS_STOP_TIMEOUT_MS = 1000;
    private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int MAX_WS_PARSE_PREVIEW = 100;
    private static final int COMMIT_QUEUE_CAPACITY = 1024 * 1024;
    private static final int TASK_QUEUE_CAPACITY = 1024 * 1024;
    private static final int MIN_INFERENCE_WORKERS = 2;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;
    private static final String MARKER_RETRACTED = "[RETRACTED]";
    private static final String MARKER_EVICTED = "[EVICTED]";
    private static final String MARKER_INPUT = "[Input]";
    private static final String MARKER_ADDED = "[Added]";
    private static final String MARKER_LLM_QUEUED = "[LLM Queued]";

    // Configuration Constants
    private static final int MAX_DERIVATION_DEPTH = 10;
    private static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90;
    private static final int KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    private static final int QUEUE_SIZE_THRESHOLD_WARN = TASK_QUEUE_CAPACITY / 2;
    private static final int QUEUE_SIZE_THRESHOLD_HALT = TASK_QUEUE_CAPACITY * 9 / 10;
    private static final boolean ENABLE_FORWARD_INSTANTIATION = true;
    private static final boolean ENABLE_RULE_DERIVATION = true;
    private static final boolean ENABLE_SKOLEMIZATION = true;

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
        this.llmApiUrl = requireNonNullElse(llmUrl, DEFAULT_LLM_URL);
        this.llmModel = requireNonNullElse(llmModel, DEFAULT_LLM_MODEL);
        this.swingUI = requireNonNull(ui, "SwingUI cannot be null");
        this.knowledgeBase = new KnowledgeBase(this.capacity, this::callback);
        this.reasonerEngine = new ReasonerEngine(knowledgeBase, () -> generateId(""), this::callback, pauseLock);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        System.out.printf("Reasoner config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
                port, this.capacity, this.broadcastInputAssertions, this.llmApiUrl, this.llmModel, MAX_DERIVATION_DEPTH);
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
                    String.format("assert-added %.4f %s [%s] {type:%s, depth:%d}", assertion.pri, kifString, assertion.id, assertion.assertionType, assertion.derivationDepth);
            case CALLBACK_ASSERT_INPUT ->
                    String.format("assert-input %.4f %s [%s] {type:%s, depth:%d}", assertion.pri, kifString, assertion.id, assertion.assertionType, assertion.derivationDepth);
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

    // Overload for LLM responses (which aren't Assertions)
    private void broadcastMessage(String type, String noteId, SwingUI.AssertionViewModel llmItem) {
         if (!CALLBACK_LLM_RESPONSE.equals(type)) return;
         var message = String.format("llm-response %s [%s] {type:%s, content:\"%s\"}",
             noteId, llmItem.id(), llmItem.displayType(), llmItem.content().replace("\"", "\\\""));
         try {
             if (!getConnections().isEmpty()) broadcast(message);
         } catch (Exception e) {
             if (!(e instanceof ConcurrentModificationException || Optional.ofNullable(e.getMessage()).map(m -> m.contains("closed")).orElse(false))) {
                 System.err.println("Error during WebSocket broadcast (LLM): " + e.getMessage());
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
                            case KIF_OPERATOR_EXISTS -> handleExistsInput(list, sourceId, sourceNoteId);
                            case KIF_OPERATOR_FORALL -> handleForallInput(list, sourceId, sourceNoteId);
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
        if (list.containsVariable()) {
            System.err.println("Warning: Non-ground assertion input ignored (use exists/forall or ensure rules bind variables): " + list.toKifString());
            return;
        }

        var isEq = !isNegated && list.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
        var isOriented = isEq && list.size() == 3 && list.get(1).calculateWeight() > list.get(2).calculateWeight();
        var derivedType = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND; // Check for skolem terms even in input
        submitPotentialAssertion(new PotentialAssertion(list, INPUT_ASSERTION_BASE_PRIORITY / (1.0 + list.calculateWeight()), Set.of(), sourceId, isEq, isNegated, isOriented, sourceNoteId, derivedType, List.of(), 0));
    }

    private void handleExistsInput(KifList existsExpr, String sourceId, @Nullable String sourceNoteId) {
        if (!ENABLE_SKOLEMIZATION) {
            System.err.println("Warning: Skolemization disabled, ignoring 'exists': " + existsExpr.toKifString());
            return;
        }
        if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) {
            System.err.println("Invalid 'exists' format (expected '(exists (?Var | (vars...)) (body...))'): " + existsExpr.toKifString());
            return;
        }
        var variables = KifTerm.collectVariablesFromSpec(existsExpr.get(1));
        if (variables.isEmpty()) {
            System.out.println("Note: 'exists' with no variables, processing body: " + existsExpr.get(2).toKifString());
            queueExpressionFromSource(existsExpr.get(2), sourceId + "-existsBody", sourceNoteId);
            return;
        }
        var skolemizedBody = reasonerEngine.performSkolemization(body, variables, Map.of()); // No context bindings for top-level input
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);

        var isNegated = skolemizedBody.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
        var isEq = !isNegated && skolemizedBody.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
        var isOriented = isEq && skolemizedBody.size() == 3 && skolemizedBody.get(1).calculateWeight() > skolemizedBody.get(2).calculateWeight();
        var pa = new PotentialAssertion(skolemizedBody, INPUT_ASSERTION_BASE_PRIORITY / (1.0 + skolemizedBody.calculateWeight()), Set.of(), sourceId + "-skolemized", isEq, isNegated, isOriented, sourceNoteId, AssertionType.SKOLEMIZED, List.of(), 0);
        submitPotentialAssertion(pa);
    }

    private void handleForallInput(KifList forallExpr, String sourceId, @Nullable String sourceNoteId) {
        if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) {
            System.err.println("Invalid 'forall' format (expected '(forall (?Var | (vars...)) (body...))'): " + forallExpr.toKifString());
            return;
        }
        var variables = KifTerm.collectVariablesFromSpec(forallExpr.get(1));
        if (variables.isEmpty()) {
            System.out.println("Note: 'forall' with no variables, processing body: " + forallExpr.get(2).toKifString());
            queueExpressionFromSource(forallExpr.get(2), sourceId + "-forallBody", sourceNoteId);
            return;
        }

        if (body.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent()) {
            System.out.println("Interpreting 'forall ... (" + body.getOperator().get() + " ...)' as rule: " + body.toKifString() + " from source " + sourceId);
            handleRuleInput(body, sourceId);
        } else if (ENABLE_FORWARD_INSTANTIATION) {
            System.out.println("Storing 'forall' as universal fact: " + forallExpr.toKifString() + " from source " + sourceId);
            var pa = new PotentialAssertion(forallExpr, INPUT_ASSERTION_BASE_PRIORITY / (1.0 + forallExpr.calculateWeight()), Set.of(), sourceId, false, false, false, sourceNoteId, AssertionType.UNIVERSAL, List.copyOf(variables), 0);
            submitPotentialAssertion(pa);
        } else {
            System.err.println("Warning: Forward instantiation disabled, ignoring 'forall' fact: " + forallExpr.toKifString());
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
        SwingUtilities.invokeLater(swingUI::clearAllNoteAssertionLists); // Clear UI lists
        idsToNotify.forEach(id -> {
            var dummyKif = new KifList(new KifAtom("retracted"), new KifAtom(id));
            callback(CALLBACK_ASSERT_RETRACTED, new Assertion(id, dummyKif, 0, 0, null, Set.of(), AssertionType.GROUND, false, false, false, List.of(), 0));
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
        System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + requireNonNullElse(reason, "N/A"));
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
        if (pa == null || (pa.kif instanceof KifList kl && isTrivial(kl))) return;
        var isDirectInputSource = !(pa.sourceId.startsWith(ID_PREFIX_RULE) || pa.sourceId.startsWith(ID_PREFIX_FACT));
        if (isDirectInputSource && (pa.sourceNoteId != null || broadcastInputAssertions)) {
            callback(CALLBACK_ASSERT_INPUT, new Assertion(generateId("input"), pa.kif, pa.pri, System.currentTimeMillis(), pa.sourceNoteId, Set.of(), pa.derivedType, pa.isEquality, pa.isOrientedEquality, pa.isNegated, pa.quantifiedVars, pa.derivationDepth));
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

    // Central callback invocation
    void callback(String type, Object payload) {
         switch (payload) {
             case Assertion assertion -> invokeAssertionCallbacks(type, assertion);
             case SwingUI.AssertionViewModel llmItem when CALLBACK_LLM_RESPONSE.equals(type) -> invokeLlmCallbacks(type, llmItem);
             default -> System.err.println("Warning: Unknown callback payload type: " + payload.getClass());
         }
         if (!"Cleared".equals(reasonerStatus)) updateUIStatus();
     }

     // Specific callback for Assertions
     private void invokeAssertionCallbacks(String type, Assertion a) {
         var id = a.sourceNoteId;
         if (id != null && CALLBACK_ASSERT_ADDED.equals(type)) {
             noteIdToAssertionIds.computeIfAbsent(id, _ -> ConcurrentHashMap.newKeySet()).add(a.id);
         } else if ((CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) && id != null) {
             noteIdToAssertionIds.computeIfPresent(id, (_, set) -> {
                 set.remove(a.id);
                 return set.isEmpty() ? null : set;
             });
         }

         var shouldBroadcast = switch (type) {
             case CALLBACK_ASSERT_ADDED, CALLBACK_ASSERT_RETRACTED, CALLBACK_EVICT -> true;
             case CALLBACK_ASSERT_INPUT -> broadcastInputAssertions;
             default -> false;
         };
         if (shouldBroadcast) broadcastMessage(type, a);

         if (swingUI != null && swingUI.isDisplayable()) swingUI.handleReasonerCallback(type, a);

         callbackRegistrations.forEach(reg -> {
             var p = reg.pattern;
             try {
                 if (CALLBACK_ASSERT_ADDED.equals(type) && Unifier.match(p, a.kif, Map.of()) != null) {
                     reg.callback().onMatch(type, a, Unifier.match(p, a.kif, Map.of()));
                 }
             } catch (Exception e) {
                 System.err.println("Error executing KIF pattern callback for " + p.toKifString() + ": " + e.getMessage());
                 e.printStackTrace();
             }
         });
     }

     // Specific callback for LLM UI items
     private void invokeLlmCallbacks(String type, SwingUI.AssertionViewModel llmItem) {
         if (swingUI != null && swingUI.isDisplayable()) swingUI.handleReasonerCallback(type, llmItem);
         // Broadcast LLM responses if needed
         if (broadcastInputAssertions) { // Reuse flag for broadcasting LLM items? Or add a new one?
             broadcastMessage(type, llmItem.noteId(), llmItem);
         }
     }

    void invokeEvictionCallbacks(Assertion evictedAssertion) {
        callback(CALLBACK_EVICT, evictedAssertion);
    }

    private void updateUIStatus() {
        if (swingUI != null && swingUI.isDisplayable()) {
            var statusText = String.format("KB: %d/%d | Tasks: %d | Commits: %d | Rules: %d | Status: %s",
                    knowledgeBase.getAssertionCount(), capacity, reasonerEngine.getTaskQueueSize(), reasonerEngine.getCommitQueueSize(), reasonerEngine.getRuleCount(), reasonerEngine.getCurrentStatus());
            SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
        }
    }

    /** call LLM API */
    private CompletableFuture<String> llmAsync(String prompt, String interactionType, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var payload = new JSONObject().put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false).put("options", new JSONObject().put("temperature", 0.2));
            var request = HttpRequest.newBuilder(URI.create(this.llmApiUrl)).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            try {

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new IOException("LLM API request failed (" + interactionType + "): " + response.statusCode() + " " + responseBody);

                return extractLlmContent(new JSONObject(new JSONTokener(responseBody))).orElse("");

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

    public CompletableFuture<String> text2kifAsync(String noteText, String noteId) {
        var finalPrompt = """
                Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', 'attribute', 'partOf', etc.
                Use '=' for equality between terms. Use unique names for new entities derived from the note (start with uppercase, use CamelCase).
                Use '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                Use '(forall (?X) (=> (instance ?X Dog) (attribute ?X Canine)))' for universal statements.
                Use '(exists (?Y) (and (instance ?Y Cat) (attribute ?Y BlackColor)))' for existential statements.
                Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                Example: (instance Fluffy Cat) (attribute Fluffy OrangeColor) (= (age Fluffy) 3) (not (attribute Fluffy BlackColor)) (exists (?K) (instance ?K Kitten))
                
                Note:
                "%s"
                
                KIF Assertions:""".formatted(noteText);

        return llmAsync(finalPrompt, "KIF Generation", noteId)
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

        return llmAsync(finalPrompt, "Note Enhancement", noteId);
    }

     public CompletableFuture<String> summarizeNoteWithLlmAsync(String noteText, String noteId) {
         var finalPrompt = """
                 Summarize the following note in one or two concise sentences. Output ONLY the summary.
                 
                 Note:
                 "%s"
                 
                 Summary:""".formatted(noteText);
         return llmAsync(finalPrompt, "Note Summarization", noteId);
     }

     public CompletableFuture<String> keyConceptsWithLlmAsync(String noteText, String noteId) {
         var finalPrompt = """
                 Identify the key concepts or entities mentioned in the following note. List them separated by commas. Output ONLY the comma-separated list.
                 
                 Note:
                 "%s"
                 
                 Key Concepts:""".formatted(noteText);
         return llmAsync(finalPrompt, "Key Concept Identification", noteId);
     }

     public CompletableFuture<String> generateQuestionsWithLlmAsync(String noteText, String noteId) {
         var finalPrompt = """
                 Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.
                 
                 Note:
                 "%s"
                 
                 Questions:""".formatted(noteText);
         return llmAsync(finalPrompt, "Question Generation", noteId);
     }


    private Optional<String> extractLlmContent(JSONObject r) {
        return Optional.ofNullable(r.optJSONObject("message"))
                .map(m -> m.optString("content", null))
                .or(() -> Optional.ofNullable(r.optString("response", null)))
                .or(() -> Optional.ofNullable(r.optJSONArray("choices")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(c -> c.optJSONObject("message")).map(m -> m.optString("content", null)))
                .or(() -> findNestedContent(r));
    }

    private Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> (obj.has("content") && (obj.get("content") instanceof String s) && !s.isBlank()) ?
                Optional.of(s) :
                obj.keySet().stream().map(obj::get).map(this::findNestedContent).flatMap(Optional::stream).findFirst();
            case JSONArray arr -> IntStream.range(0, arr.length())
                .mapToObj(arr::get)
                .map(this::findNestedContent)
                .flatMap(Optional::stream)
                .findFirst();
            default -> Optional.empty();
        };
    }

    enum TaskType {MATCH_ANTECEDENT, APPLY_ORDERED_REWRITE}

    enum AssertionType {GROUND, UNIVERSAL, SKOLEMIZED}

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

        default boolean containsSkolemTerm() {
            return switch (this) {
                case KifAtom a -> a.value.startsWith(ID_PREFIX_SKOLEM_CONST);
                case KifList l -> l.terms.stream().anyMatch(KifTerm::containsSkolemTerm) ||
                        l.getOperator().filter(op -> op.startsWith(ID_PREFIX_SKOLEM_FUNC)).isPresent();
                case KifVar v -> false;
            };
        }
    }

    @FunctionalInterface
    interface KifCallback {
        void onMatch(String type, Assertion assertion, Map<KifVar, KifTerm> bindings);
    }

    record KifAtom(String value) implements KifTerm {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$");
        private static final Map<String, KifAtom> internCache = new ConcurrentHashMap<>(1024);

        KifAtom {
            requireNonNull(value);
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
            requireNonNull(name);
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
        private volatile Boolean containsSkolemCache;
        private volatile Set<KifVar> variablesCache;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;

        KifList(KifTerm... terms) {
            this(List.of(terms));
        }

        KifList(List<KifTerm> terms) {
            this.terms = List.copyOf(requireNonNull(terms));
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
            return terms.isEmpty() || !(terms.getFirst() instanceof KifAtom(String value)) ? Optional.empty() : Optional.of(value);
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
        public boolean containsSkolemTerm() {
            if (containsSkolemCache == null)
                containsSkolemCache = KifTerm.super.containsSkolemTerm();
            return containsSkolemCache;
        }

        @Override
        public Set<KifVar> getVariables() {
            if (variablesCache == null)
                variablesCache = terms.stream().flatMap(t -> t.getVariables().stream()).collect(Collectors.toUnmodifiableSet());
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
                     Set<String> support, AssertionType assertionType,
                     boolean isEquality, boolean isOrientedEquality, boolean isNegated,
                     List<KifVar> quantifiedVars, int derivationDepth) implements Comparable<Assertion> {
        Assertion {
            requireNonNull(id);
            requireNonNull(kif);
            support = Set.copyOf(requireNonNull(support));
            requireNonNull(assertionType);
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (isNegated != kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for KIF: " + kif.toKifString());
            if (assertionType == AssertionType.UNIVERSAL && (kif.getOperator().filter(KIF_OPERATOR_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Universal assertion must be (forall ...) with quantified vars: " + kif.toKifString());
            if (assertionType != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty())
                throw new IllegalArgumentException("Only Universal assertions should have quantified vars: " + kif.toKifString());
        }

        @Override
        public int compareTo(Assertion other) {
            // Higher priority first, then lower depth, then newer timestamp
            int priComp = Double.compare(other.pri, this.pri);
            if (priComp != 0) return priComp;
            int depthComp = Integer.compare(this.derivationDepth, other.derivationDepth);
            if (depthComp != 0) return depthComp;
            return Long.compare(other.timestamp, this.timestamp);
        }

        String toKifString() {
            return kif.toKifString();
        }

        KifTerm getEffectiveTerm() {
            return switch (assertionType) {
                case GROUND, SKOLEMIZED -> isNegated ? kif.get(1) : kif;
                case UNIVERSAL -> kif.get(2); // The body of the forall
            };
        }

        Set<KifAtom> getReferencedPredicates() {
            Set<KifAtom> predicates = new HashSet<>(); //ConcurrentHashMap.newKeySet();
            collectPredicatesRecursive(getEffectiveTerm(), predicates);
            return Collections.unmodifiableSet(predicates);
        }

        private void collectPredicatesRecursive(KifTerm term, Set<KifAtom> predicates) {
            if (term instanceof KifList list && !list.terms.isEmpty() && list.get(0) instanceof KifAtom pred) {
                predicates.add(pred);
                list.terms().stream().skip(1).forEach(sub -> collectPredicatesRecursive(sub, predicates));
            }
        }
    }

    record Rule(String id, KifList form, KifTerm antecedent, KifTerm consequent, double pri,
                List<KifTerm> antecedents) {
        Rule {
            requireNonNull(id);
            requireNonNull(form);
            requireNonNull(antecedent);
            requireNonNull(consequent);
            antecedents = List.copyOf(requireNonNull(antecedents));
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
                              boolean isNegated, boolean isOrientedEquality, @Nullable String sourceNoteId,
                              AssertionType derivedType, List<KifVar> quantifiedVars, int derivationDepth) {
        PotentialAssertion {
            requireNonNull(kif);
            support = Set.copyOf(requireNonNull(support));
            requireNonNull(sourceId);
            requireNonNull(derivedType);
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (isNegated != kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for potential KIF: " + kif.toKifString());
            if (derivedType == AssertionType.UNIVERSAL && (kif.getOperator().filter(KIF_OPERATOR_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Potential Universal assertion must be (forall ...) with quantified vars: " + kif.toKifString());
            if (derivedType != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty())
                throw new IllegalArgumentException("Only potential Universal assertions should have quantified vars: " + kif.toKifString());
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
            return switch (derivedType) {
                case GROUND, SKOLEMIZED -> isNegated ? kif.get(1) : kif;
                case UNIVERSAL -> kif.get(2);
            };
        }
    }

    record InferenceTask(TaskType type, double pri, Object data) implements Comparable<InferenceTask> {
        InferenceTask {
            requireNonNull(type);
            requireNonNull(data);
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
            requireNonNull(pattern);
            requireNonNull(callback);
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
        private final PathIndex pathIndex = new PathIndex(); // Indexes GROUND and SKOLEMIZED assertions
        private final ConcurrentMap<KifAtom, Set<String>> universalIndex = new ConcurrentHashMap<>(); // Predicate -> UNIVERSAL assertion IDs
        private final ConcurrentMap<String, Set<String>> assertionDependencies = new ConcurrentHashMap<>();
        private final PriorityBlockingQueue<String> groundEvictionQueue = new PriorityBlockingQueue<>(1024, Comparator.comparingDouble(id -> assertionsById.getOrDefault(id, null) != null ? assertionsById.get(id).pri : Double.MAX_VALUE));
        private final ReadWriteLock kbLock = new ReentrantReadWriteLock();
        private final int maxKbSize;
        private final BiConsumer<String, Object> eventCallback; // Changed to Object payload

        KnowledgeBase(int maxKbSize, BiConsumer<String, Object> eventCallback) {
            this.maxKbSize = maxKbSize;
            this.eventCallback = requireNonNull(eventCallback);
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

        Optional<Assertion> commitAssertion(PotentialAssertion pa) {
            kbLock.writeLock().lock();
            try {
                if (pa.kif instanceof KifList kl && CogNote.isTrivial(kl)) return Optional.empty();

                var newId = generateId(ID_PREFIX_FACT + "-" + pa.derivedType.name().toLowerCase());
                var timestamp = System.currentTimeMillis();

                // Determine final type (e.g., if derived type is GROUND but contains Skolem terms)
                var finalType = (pa.derivedType == AssertionType.GROUND && pa.kif.containsSkolemTerm()) ? AssertionType.SKOLEMIZED : pa.derivedType;

                // Type-specific checks
                if (finalType == AssertionType.GROUND || finalType == AssertionType.SKOLEMIZED) {
                    if (isSubsumedInternal(pa.kif, pa.isNegated)) return Optional.empty();
                    if (findExactMatchInternal(pa.kif) != null) return Optional.empty();
                } else if (finalType == AssertionType.UNIVERSAL) {
                    // Optional: Check for semantic equivalence with existing universals (skipped for now)
                    if (findExactMatchInternal(pa.kif) != null) return Optional.empty(); // Simple exact match check
                }

                enforceKbCapacityInternal();
                if (assertionsById.size() >= maxKbSize) {
                    System.err.printf("Warning: KB full (%d/%d) after eviction attempt. Cannot add: %s%n", assertionsById.size(), maxKbSize, pa.kif.toKifString());
                    return Optional.empty();
                }

                var newAssertion = new Assertion(newId, pa.kif, pa.pri, timestamp, pa.sourceNoteId, pa.support, finalType, pa.isEquality, pa.isOrientedEquality, pa.isNegated, pa.quantifiedVars, pa.derivationDepth);

                if (assertionsById.putIfAbsent(newId, newAssertion) != null) {
                    System.err.println("KB Commit Error: ID collision for " + newId);
                    return Optional.empty();
                }

                // Type-specific indexing
                if (finalType == AssertionType.GROUND || finalType == AssertionType.SKOLEMIZED) {
                    pathIndex.add(newAssertion);
                    groundEvictionQueue.put(newId);
                } else if (finalType == AssertionType.UNIVERSAL) {
                    newAssertion.getReferencedPredicates().forEach(pred ->
                            universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(newId));
                }

                // Update dependencies
                newAssertion.support.forEach(supporterId ->
                        assertionDependencies.computeIfAbsent(supporterId, _ -> ConcurrentHashMap.newKeySet()).add(newId));

                eventCallback.accept(CALLBACK_ASSERT_ADDED, newAssertion);
                checkResourceThresholds();
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
                universalIndex.clear();
                groundEvictionQueue.clear();
                assertionDependencies.clear();
            } finally {
                kbLock.writeLock().unlock();
            }
        }

        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) {
            kbLock.readLock().lock();
            try {
                // Only query GROUND/SKOLEMIZED assertions via PathIndex
                return pathIndex.findUnifiable(queryTerm).stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull)
                        .filter(a -> a.assertionType == AssertionType.GROUND || a.assertionType == AssertionType.SKOLEMIZED)
                        .toList().stream(); // Collect to list to release lock sooner
            } finally {
                kbLock.readLock().unlock();
            }
        }

        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            kbLock.readLock().lock();
            try {
                // Only query GROUND/SKOLEMIZED assertions via PathIndex
                var patternIsNegated = (queryPattern instanceof KifList ql && ql.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());
                return pathIndex.findInstances(queryPattern).stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull)
                        .filter(a -> a.assertionType == AssertionType.GROUND || a.assertionType == AssertionType.SKOLEMIZED)
                        .filter(a -> a.isNegated == patternIsNegated)
                        .filter(a -> Unifier.match(queryPattern, a.kif, Map.of()) != null)
                        .toList().stream(); // Collect to list
            } finally {
                kbLock.readLock().unlock();
            }
        }

        List<Assertion> findRelevantUniversalAssertions(KifAtom predicate) {
            kbLock.readLock().lock();
            try {
                return universalIndex.getOrDefault(predicate, Set.of()).stream()
                        .map(assertionsById::get)
                        .filter(Objects::nonNull)
                        .filter(a -> a.assertionType == AssertionType.UNIVERSAL)
                        .toList(); // Collect to list
            } finally {
                kbLock.readLock().unlock();
            }
        }

        private Assertion retractAssertionWithCascadeInternal(String id, Set<String> retractedInThisCascade) {
            if (!retractedInThisCascade.add(id)) return null;
            var removed = assertionsById.remove(id);
            if (removed != null) {
                // Remove from type-specific indices
                if (removed.assertionType == AssertionType.GROUND || removed.assertionType == AssertionType.SKOLEMIZED) {
                    pathIndex.remove(removed);
                    groundEvictionQueue.remove(id);
                } else if (removed.assertionType == AssertionType.UNIVERSAL) {
                    removed.getReferencedPredicates().forEach(pred ->
                            universalIndex.computeIfPresent(pred, (_, ids) -> {
                                ids.remove(id);
                                return ids.isEmpty() ? null : ids;
                            }));
                }

                // Remove dependencies
                removed.support.forEach(supporterId -> assertionDependencies.computeIfPresent(supporterId, (_, dependents) -> {
                    dependents.remove(id);
                    return dependents.isEmpty() ? null : dependents;
                }));
                Optional.ofNullable(assertionDependencies.remove(id)).ifPresent(dependents ->
                        new HashSet<>(dependents).forEach(depId -> retractAssertionWithCascadeInternal(depId, retractedInThisCascade)));

                eventCallback.accept(CALLBACK_ASSERT_RETRACTED, removed);
            }
            return removed;
        }

        private boolean isSubsumedInternal(KifTerm term, boolean isNegated) {
            // Only check subsumption against GROUND/SKOLEMIZED assertions
            return pathIndex.findGeneralizations(term).stream()
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    .filter(a -> a.assertionType == AssertionType.GROUND || a.assertionType == AssertionType.SKOLEMIZED)
                    .anyMatch(candidate -> candidate.isNegated == isNegated && Unifier.match(candidate.getEffectiveTerm(), term, Map.of()) != null);
        }

        @Nullable
        private Assertion findExactMatchInternal(KifList kif) {
            // Check against all types for exact match
            return pathIndex.findInstances(kif).stream() // Use pathIndex for initial filtering if possible
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    .filter(a -> a.kif.equals(kif))
                    .findFirst()
                    .orElseGet(() -> // Fallback for universals not fully in pathIndex
                            assertionsById.values().stream()
                                    .filter(a -> a.kif.equals(kif))
                                    .findFirst().orElse(null)
                    );
        }

        private void enforceKbCapacityInternal() {
            // Only evict GROUND/SKOLEMIZED assertions based on priority
            while (assertionsById.size() >= maxKbSize && !groundEvictionQueue.isEmpty()) {
                Optional.ofNullable(groundEvictionQueue.poll())
                        .flatMap(this::getAssertion) // Ensure assertion still exists
                        .filter(a -> a.assertionType == AssertionType.GROUND || a.assertionType == AssertionType.SKOLEMIZED).flatMap(lowestPri -> Optional.ofNullable(retractAssertionWithCascadeInternal(lowestPri.id, new HashSet<>()))).ifPresent(r -> eventCallback.accept(CALLBACK_EVICT, r));
            }
        }

        private void checkResourceThresholds() {
            var currentSize = assertionsById.size();
            var warnThreshold = maxKbSize * KB_SIZE_THRESHOLD_WARN_PERCENT / 100;
            var haltThreshold = maxKbSize * KB_SIZE_THRESHOLD_HALT_PERCENT / 100;
            if (currentSize >= haltThreshold)
                System.err.printf("KB CRITICAL: Size %d/%d (%.1f%%) exceeds halt threshold (%d%%)%n", currentSize, maxKbSize, 100.0 * currentSize / maxKbSize, KB_SIZE_THRESHOLD_HALT_PERCENT);
            else if (currentSize >= warnThreshold)
                System.out.printf("KB WARNING: Size %d/%d (%.1f%%) exceeds warning threshold (%d%%)%n", currentSize, maxKbSize, 100.0 * currentSize / maxKbSize, KB_SIZE_THRESHOLD_WARN_PERCENT);
        }
    }

    static class ReasonerEngine {
        private final KnowledgeBase kb;
        private final Supplier<String> idGenerator;
        private final BiConsumer<String, Object> callbackNotifier; // Changed to Object payload
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<PotentialAssertion> commitQueue = new LinkedBlockingQueue<>(COMMIT_QUEUE_CAPACITY);
        private final PriorityBlockingQueue<InferenceTask> taskQueue = new PriorityBlockingQueue<>(TASK_QUEUE_CAPACITY);
        private final ExecutorService commitExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "CommitThread"));
        private final ExecutorService inferenceExecutor = Executors.newVirtualThreadPerTaskExecutor();
        private final Object pauseLock;
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private volatile String currentStatus = "Idle";

        ReasonerEngine(KnowledgeBase kb, Supplier<String> idGenerator, BiConsumer<String, Object> notifier, Object pauseLock) {
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
            checkQueueThresholds();
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
                    kb.commitAssertion(pa).ifPresent(committed -> {
                        generateNewTasks(committed);
                        // Trigger instantiation only for newly committed GROUND assertions
                        if (ENABLE_FORWARD_INSTANTIATION && committed.assertionType == AssertionType.GROUND) {
                            triggerInstantiation(committed);
                        }
                    });
                    currentStatus = "Idle (Commit)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
                findMatchesRecursive(rule, rule.antecedents(), initialBindings, Set.of(triggerId))
                        .forEach(matchResult -> processDerivedAssertion(rule, matchResult));
        }

        private void handleApplyRewriteTask(InferenceTask task) {
            if (!(task.data instanceof RewriteContext(var ruleAssertion, var targetAssertion))) return;
            if (ruleAssertion.assertionType != AssertionType.GROUND || ruleAssertion.isNegated || !ruleAssertion.isEquality() || !ruleAssertion.isOrientedEquality() || ruleAssertion.kif.size() != 3)
                return;

            Unifier.rewrite(targetAssertion.kif, ruleAssertion.kif.get(1), ruleAssertion.kif.get(2))
                    .filter(rewrittenTerm -> rewrittenTerm instanceof KifList && !rewrittenTerm.equals(targetAssertion.kif))
                    .map(KifList.class::cast)
                    .filter(Predicate.not(CogNote::isTrivial))
                    .ifPresent(rewrittenList -> {
                        var support = Stream.concat(targetAssertion.support.stream(), Stream.of(targetAssertion.id, ruleAssertion.id)).collect(Collectors.toSet());
                        var newDepth = Math.max(targetAssertion.derivationDepth, ruleAssertion.derivationDepth) + 1;
                        if (newDepth > MAX_DERIVATION_DEPTH) {
                            System.out.printf("Rewrite depth limit (%d) reached. Discarding: %s from rule %s%n", MAX_DERIVATION_DEPTH, rewrittenList.toKifString(), ruleAssertion.id);
                            return;
                        }

                        var isNeg = rewrittenList.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
                        var isEq = !isNeg && rewrittenList.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
                        var isOriented = isEq && rewrittenList.size() == 3 && rewrittenList.get(1).calculateWeight() > rewrittenList.get(2).calculateWeight();
                        var derivedType = rewrittenList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;

                        submitPotentialAssertion(new PotentialAssertion(rewrittenList, calculateDerivedPri(support, task.pri), support, ruleAssertion.id, isEq, isNeg, isOriented, findCommonSourceNodeId(support), derivedType, List.of(), newDepth));
                    });
        }

        private void processDerivedAssertion(Rule sourceRule, MatchResult matchResult) {
            var consequentTerm = Unifier.subst(sourceRule.consequent, matchResult.bindings);
            if (consequentTerm == null) return;

            var simplifiedTerm = (consequentTerm instanceof KifList kl) ? simplifyLogicalTerm(kl) : consequentTerm;

            if (simplifiedTerm instanceof KifList derivedList) {
                derivedList.getOperator().ifPresentOrElse(
                    op -> {
                        switch (op) {
                            case KIF_OPERATOR_AND -> processDerivedConjunction(sourceRule, derivedList, matchResult);
                            case KIF_OPERATOR_FORALL -> processDerivedForall(sourceRule, derivedList, matchResult);
                            case KIF_OPERATOR_EXISTS -> processDerivedExists(sourceRule, derivedList, matchResult);
                            default -> processDerivedStandard(sourceRule, derivedList, matchResult);
                        }
                    },
                    () -> processDerivedStandard(sourceRule, derivedList, matchResult) // Handle lists without operator atoms if needed
                );
            } else if (!(simplifiedTerm instanceof KifVar)) {
                 System.err.println("Warning: Rule " + sourceRule.id + " derived a non-list, non-variable consequent after substitution: " + simplifiedTerm.toKifString());
            }
            // Ignore KifVar consequents (should be bound or handled by quantifiers)
        }

        private void processDerivedConjunction(Rule sourceRule, KifList conjunction, MatchResult matchResult) {
             conjunction.terms().stream().skip(1).forEach(term -> {
                 var simplifiedSubTerm = (term instanceof KifList kl) ? simplifyLogicalTerm(kl) : term;
                 if (simplifiedSubTerm instanceof KifList conjunct) {
                     conjunct.getOperator().ifPresentOrElse(
                         op -> {
                             switch (op) {
                                 case KIF_OPERATOR_FORALL -> processDerivedForall(sourceRule, conjunct, matchResult);
                                 case KIF_OPERATOR_EXISTS -> processDerivedExists(sourceRule, conjunct, matchResult);
                                 default -> processDerivedStandard(sourceRule, conjunct, matchResult);
                             }
                         },
                         () -> processDerivedStandard(sourceRule, conjunct, matchResult)
                     );
                 } else if (!(simplifiedSubTerm instanceof KifVar)) {
                     System.err.println("Warning: Rule " + sourceRule.id + " derived (and ...) with non-list/non-var conjunct: " + term.toKifString());
                 }
             });
        }

        private void processDerivedForall(Rule sourceRule, KifList forallExpr, MatchResult matchResult) {
            if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) {
                 System.err.println("Rule " + sourceRule.id + " derived invalid 'forall' structure: " + forallExpr.toKifString()); return;
            }
            var variables = KifTerm.collectVariablesFromSpec(forallExpr.get(1));
            if (variables.isEmpty()) { processDerivedAssertion(sourceRule, new MatchResult(matchResult.bindings, matchResult.supportIds)); return; } // Process body if no vars

            var newDepth = calculateDerivedDepth(matchResult.supportIds()) + 1;
            if (newDepth > MAX_DERIVATION_DEPTH) { System.out.printf("Derivation depth limit (%d) reached for derived forall. Discarding: %s from rule %s%n", MAX_DERIVATION_DEPTH, forallExpr.toKifString(), sourceRule.id); return; }

            // Check if it's a derived rule
            if (ENABLE_RULE_DERIVATION && body.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent()) {
                try {
                    var derivedRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "-derived"), body, calculateDerivedPri(matchResult.supportIds(), sourceRule.pri));
                    addRule(derivedRule);
                    if (KIF_OPERATOR_EQUIV.equals(body.getOperator().orElse(""))) {
                        var reverseList = new KifList(new KifAtom(KIF_OPERATOR_IMPLIES), body.get(2), body.get(1));
                        addRule(Rule.parseRule(generateId(ID_PREFIX_RULE + "-derived"), reverseList, derivedRule.pri));
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Invalid derived rule format ignored: " + body.toKifString() + " from rule " + sourceRule.id + " | Error: " + e.getMessage());
                }
            } else if (ENABLE_FORWARD_INSTANTIATION) { // Treat as a universal fact
                 var pa = new PotentialAssertion(forallExpr, calculateDerivedPri(matchResult.supportIds(), sourceRule.pri), matchResult.supportIds(), sourceRule.id, false, false, false, findCommonSourceNodeId(matchResult.supportIds()), AssertionType.UNIVERSAL, List.copyOf(variables), newDepth);
                 submitPotentialAssertion(pa);
            } else {
                 System.err.println("Warning: Forward instantiation disabled, ignoring derived 'forall' fact: " + forallExpr.toKifString());
            }
        }

        private void processDerivedExists(Rule sourceRule, KifList existsExpr, MatchResult matchResult) {
            if (!ENABLE_SKOLEMIZATION) { System.err.println("Warning: Skolemization disabled, ignoring derived 'exists': " + existsExpr.toKifString()); return; }
            if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) {
                 System.err.println("Rule " + sourceRule.id + " derived invalid 'exists' structure: " + existsExpr.toKifString()); return;
            }
            var variables = KifTerm.collectVariablesFromSpec(existsExpr.get(1));
            if (variables.isEmpty()) { processDerivedAssertion(sourceRule, new MatchResult(matchResult.bindings, matchResult.supportIds)); return; } // Process body if no vars

            var newDepth = calculateDerivedDepth(matchResult.supportIds()) + 1;
            if (newDepth > MAX_DERIVATION_DEPTH) { System.out.printf("Derivation depth limit (%d) reached for derived exists. Discarding: %s from rule %s%n", MAX_DERIVATION_DEPTH, existsExpr.toKifString(), sourceRule.id); return; }

            var skolemizedBody = performSkolemization(body, variables, matchResult.bindings);
            var isNegated = skolemizedBody.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
            var isEq = !isNegated && skolemizedBody.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemizedBody.size() == 3 && skolemizedBody.get(1).calculateWeight() > skolemizedBody.get(2).calculateWeight();

            var pa = new PotentialAssertion(skolemizedBody, calculateDerivedPri(matchResult.supportIds(), sourceRule.pri), matchResult.supportIds(), sourceRule.id, isEq, isNegated, isOriented, findCommonSourceNodeId(matchResult.supportIds()), AssertionType.SKOLEMIZED, List.of(), newDepth);
            submitPotentialAssertion(pa);
        }

        private void processDerivedStandard(Rule sourceRule, KifList derivedTerm, MatchResult matchResult) {
            if (derivedTerm.containsVariable() || CogNote.isTrivial(derivedTerm)) {
                 if (derivedTerm.containsVariable()) System.out.println("Note: Derived non-ground assertion currently ignored: " + derivedTerm.toKifString() + " from rule " + sourceRule.id);
                 return;
            }
            var newDepth = calculateDerivedDepth(matchResult.supportIds()) + 1;
            if (newDepth > MAX_DERIVATION_DEPTH) { System.out.printf("Derivation depth limit (%d) reached. Discarding: %s from rule %s%n", MAX_DERIVATION_DEPTH, derivedTerm.toKifString(), sourceRule.id); return; }

            var termWeight = derivedTerm.calculateWeight();
            if (termWeight > MAX_DERIVED_TERM_WEIGHT) { System.err.printf("Warning: Derived term weight (%d) exceeds limit (%d). Discarding: %s from rule %s%n", termWeight, MAX_DERIVED_TERM_WEIGHT, derivedTerm.toKifString(), sourceRule.id); return; }

            var isNegated = derivedTerm.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
            if (isNegated && derivedTerm.size() != 2) { System.err.println("Rule " + sourceRule.id + " derived invalid 'not' structure: " + derivedTerm.toKifString()); return; }

            var isEq = !isNegated && derivedTerm.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
            var isOriented = isEq && derivedTerm.size() == 3 && derivedTerm.get(1).calculateWeight() > derivedTerm.get(2).calculateWeight();
            var derivedType = derivedTerm.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;

            submitPotentialAssertion(new PotentialAssertion(derivedTerm, calculateDerivedPri(matchResult.supportIds(), sourceRule.pri), matchResult.supportIds(), sourceRule.id, isEq, isNegated, isOriented, findCommonSourceNodeId(matchResult.supportIds()), derivedType, List.of(), newDepth));
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
            // Use KB method that queries GROUND/SKOLEMIZED assertions
            return kb.findUnifiableAssertions(pattern)
                    .filter(candidate -> candidate.isNegated == clauseIsNegated)
                    .flatMap(candidate -> Optional.ofNullable(Unifier.unify(pattern, candidate.getEffectiveTerm(), currentBindings))
                            .map(newBindings -> findMatchesRecursive(rule, nextRemainingClauses, newBindings, Stream.concat(currentSupportIds.stream(), Stream.of(candidate.id)).collect(Collectors.toSet())))
                            .orElse(Stream.empty()));
        }

        private void triggerInstantiation(Assertion groundAssertion) {
            if (groundAssertion.derivationDepth >= MAX_DERIVATION_DEPTH) return;
            if (!(groundAssertion.kif.get(0) instanceof KifAtom predicate)) return; // Need a predicate to index universals

            kb.findRelevantUniversalAssertions(predicate).stream()
                .filter(uniAssertion -> uniAssertion.derivationDepth < MAX_DERIVATION_DEPTH) // Check universal's depth too
                .forEach(uniAssertion -> {
                    var formula = uniAssertion.getEffectiveTerm();
                    var quantifiedVars = uniAssertion.quantifiedVars;

                    // Simple matching strategy: Match ground assertion against direct sub-expressions of the universal formula
                    findSubExpressionMatches(formula, groundAssertion.kif).forEach(bindings -> {
                        // Check if ALL quantified variables are bound by this match
                        if (bindings.keySet().containsAll(quantifiedVars)) {
                            var instantiatedFormula = Unifier.subst(formula, bindings);
                            if (instantiatedFormula instanceof KifList instantiatedList && !instantiatedFormula.containsVariable() && !CogNote.isTrivial(instantiatedList)) {
                                var newSupport = Stream.concat(Stream.concat(groundAssertion.support.stream(), uniAssertion.support.stream()), Stream.of(groundAssertion.id, uniAssertion.id)).collect(Collectors.toSet());
                                var newPri = calculateDerivedPri(newSupport, (groundAssertion.pri + uniAssertion.pri) / 2.0); // Base on average pri
                                var newDepth = Math.max(groundAssertion.derivationDepth, uniAssertion.derivationDepth) + 1;

                                if (newDepth <= MAX_DERIVATION_DEPTH) {
                                    var isNeg = instantiatedList.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
                                    var isEq = !isNeg && instantiatedList.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
                                    var isOriented = isEq && instantiatedList.size() == 3 && instantiatedList.get(1).calculateWeight() > instantiatedList.get(2).calculateWeight();
                                    var derivedType = instantiatedList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;

                                    var pa = new PotentialAssertion(instantiatedList, newPri, newSupport, uniAssertion.id, isEq, isNeg, isOriented, findCommonSourceNodeId(newSupport), derivedType, List.of(), newDepth);
                                    submitPotentialAssertion(pa);
                                }
                            }
                        }
                    });
                });
        }

        private Stream<Map<KifVar, KifTerm>> findSubExpressionMatches(KifTerm expression, KifTerm target) {
            return Stream.concat(
                Optional.ofNullable(Unifier.match(expression, target, Map.of())).stream(),
                (expression instanceof KifList list) ?
                    list.terms().stream().flatMap(sub -> findSubExpressionMatches(sub, target)) :
                    Stream.empty());
        }

        KifList performSkolemization(KifList body, Collection<KifVar> existentialVars, Map<KifVar, KifTerm> contextBindings) {
            Set<KifVar> freeVarsInBody = new HashSet<>(body.getVariables());
            freeVarsInBody.removeAll(existentialVars);

            // Determine the terms corresponding to the free variables from the context
            List<KifTerm> skolemArgs = freeVarsInBody.stream()
                    .map(fv -> Unifier.substFully(contextBindings.getOrDefault(fv, fv), contextBindings)) // Substitute context bindings fully
                    .sorted(Comparator.comparing(KifTerm::toKifString)) // Ensure consistent order
                    .toList();

            Map<KifVar, KifTerm> skolemMap = new HashMap<>();
            for (KifVar exVar : existentialVars) {
                if (skolemArgs.isEmpty()) {
                    // Skolem constant
                    skolemMap.put(exVar, KifAtom.of(ID_PREFIX_SKOLEM_CONST + exVar.name().substring(1) + "_" + idCounter.incrementAndGet()));
                } else {
                    // Skolem function
                    var funcName = KifAtom.of(ID_PREFIX_SKOLEM_FUNC + exVar.name().substring(1) + "_" + idCounter.incrementAndGet());
                    List<KifTerm> funcArgs = new ArrayList<>(skolemArgs.size() + 1);
                    funcArgs.add(funcName);
                    funcArgs.addAll(skolemArgs);
                    skolemMap.put(exVar, new KifList(funcArgs));
                }
            }
            return (KifList) Unifier.subst(body, skolemMap); // Body is assumed to be KifList
        }

        private void generateNewTasks(Assertion newAssertion) {
            triggerRuleMatchingTasks(newAssertion);
            triggerRewriteTasks(newAssertion);
            // Forward instantiation is triggered from commitLoop for GROUND assertions
        }

        private void triggerRuleMatchingTasks(Assertion newAssertion) {
            // Only trigger rules with GROUND or SKOLEMIZED assertions
            if (newAssertion.assertionType != AssertionType.GROUND && newAssertion.assertionType != AssertionType.SKOLEMIZED) return;

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
            // Rewrites only apply to/by GROUND or SKOLEMIZED assertions
            if (newAssertion.assertionType != AssertionType.GROUND && newAssertion.assertionType != AssertionType.SKOLEMIZED) return;

            if (newAssertion.isEquality() && newAssertion.isOrientedEquality() && !newAssertion.isNegated && newAssertion.kif.size() == 3) {
                // New assertion is an oriented equality rule, find things it can rewrite
                kb.findUnifiableAssertions(newAssertion.kif.get(1)) // Finds GROUND/SKOLEMIZED
                        .filter(target -> !target.id.equals(newAssertion.id))
                        .filter(target -> Unifier.match(newAssertion.kif.get(1), target.getEffectiveTerm(), Map.of()) != null)
                        .forEach(target -> taskQueue.put(InferenceTask.applyRewrite(newAssertion, target, calculateTaskPri(newAssertion.pri, target.pri))));
            } else {
                // New assertion is a potential target, find rules that can rewrite it
                kb.getAllAssertions().stream() // Check all assertions...
                        .filter(a -> a.assertionType == AssertionType.GROUND || a.assertionType == AssertionType.SKOLEMIZED) // ...that are ground/skolemized equality rules
                        .filter(a -> a.isEquality() && a.isOrientedEquality() && !a.isNegated && a.kif.size() == 3)
                        .filter(ruleAssertion -> Unifier.match(ruleAssertion.kif.get(1), newAssertion.getEffectiveTerm(), Map.of()) != null)
                        .forEach(ruleAssertion -> taskQueue.put(InferenceTask.applyRewrite(ruleAssertion, newAssertion, calculateTaskPri(ruleAssertion.pri, newAssertion.pri))));
            }
        }

        private void triggerMatchingForNewRule(Rule newRule) {
            // Match new rule against existing GROUND/SKOLEMIZED assertions
            kb.getAllAssertions().stream()
                .filter(a -> a.assertionType == AssertionType.GROUND || a.assertionType == AssertionType.SKOLEMIZED)
                .forEach(existing -> newRule.antecedents.forEach(clause -> {
                    var clauseIsNegated = (clause instanceof KifList l && l.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent());
                    if (clauseIsNegated == existing.isNegated) {
                        var pattern = clauseIsNegated ? ((KifList) clause).get(1) : clause;
                        Optional.ofNullable(Unifier.unify(pattern, existing.getEffectiveTerm(), Map.of()))
                                .ifPresent(bindings -> taskQueue.put(InferenceTask.matchAntecedent(newRule, existing, bindings, calculateTaskPri(newRule.pri, existing.pri))));
                    }
                }));
        }

        private double calculateDerivedPri(Set<String> supportIds, double basePri) {
            return supportIds.isEmpty() ? basePri : supportIds.stream().map(kb::getAssertion).flatMap(Optional::stream).mapToDouble(Assertion::pri).min().orElse(basePri) * DERIVED_PRIORITY_DECAY;
        }

        private int calculateDerivedDepth(Set<String> supportIds) {
             return supportIds.stream().map(kb::getAssertion).flatMap(Optional::stream).mapToInt(Assertion::derivationDepth).max().orElse(-1);
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
                    if (assertion.sourceNoteId != null) {
                        if (!firstFound) {
                            commonId = assertion.sourceNoteId;
                            firstFound = true;
                        } else if (!commonId.equals(assertion.sourceNoteId)) return null; // Mismatch found
                    } else if (assertion.derivationDepth > 0) { // Only recurse on derived facts
                        assertion.support.forEach(toCheck::offer);
                    }
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
                // Basic simplification: (not (not X)) -> X
                if (op.equals(KIF_OPERATOR_NOT) && term.size() == 2 && term.get(1) instanceof KifList negList && negList.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent() && negList.size() == 2) {
                    var inner = negList.get(1);
                    if (inner instanceof KifList l) return simplifyLogicalTermOnce(l);
                }
                // TODO: Add more simplification rules if needed (e.g., (and X X) -> X)
            }
            // Recursively simplify sub-terms
            var changed = false;
            List<KifTerm> newTerms = new ArrayList<>(term.size());
            for (var subTerm : term.terms()) {
                var simplifiedSub = (subTerm instanceof KifList sl) ? simplifyLogicalTermOnce(sl) : subTerm;
                if (simplifiedSub != subTerm) changed = true;
                newTerms.add(simplifiedSub);
            }
            return changed ? new KifList(newTerms) : term;
        }

        private void checkQueueThresholds() {
            var taskQSize = taskQueue.size();
            if (taskQSize >= QUEUE_SIZE_THRESHOLD_HALT)
                System.err.printf("Task Queue CRITICAL: Size %d/%d exceeds halt threshold (%d)%n", taskQSize, TASK_QUEUE_CAPACITY, QUEUE_SIZE_THRESHOLD_HALT);
            else if (taskQSize >= QUEUE_SIZE_THRESHOLD_WARN)
                System.out.printf("Task Queue WARNING: Size %d/%d exceeds warning threshold (%d)%n", taskQSize, TASK_QUEUE_CAPACITY, QUEUE_SIZE_THRESHOLD_WARN);
            // Similar checks for commitQueue if needed
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
            var patternSubst = substFully(pattern, bindings); // Substitute pattern first
            if (patternSubst instanceof KifVar varP) return bindVariable(varP, term, bindings, false); // Bind pattern var to term
            if (patternSubst.equals(term)) return bindings; // Exact match after substitution
            if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lp.size(); i++) {
                    if ((currentBindings = matchRecursive(lp.get(i), lt.get(i), currentBindings)) == null) return null; // Recurse
                }
                return currentBindings;
            }
            return null; // Mismatch
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
            if (var.equals(value)) return bindings; // Trivial binding
            if (bindings.containsKey(var)) { // Variable already bound
                // Check if existing binding is compatible
                return doOccursCheck ? unifyRecursive(bindings.get(var), value, bindings) : matchRecursive(bindings.get(var), value, bindings);
            }
            // Occurs check needed for unification, not for matching
            if (doOccursCheck) {
                 var finalValue = substFully(value, bindings); // Substitute value before occurs check
                 if (occursCheckRecursive(var, finalValue, bindings)) return null; // Occurs check fail
                 Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
                 newBindings.put(var, finalValue);
                 return Collections.unmodifiableMap(newBindings);
            } else {
                 // No occurs check for matching, just add the binding
                 Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
                 newBindings.put(var, value); // Bind to the original value, substitution happens later
                 return Collections.unmodifiableMap(newBindings);
            }
        }

        private static boolean occursCheckRecursive(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings) {
            var substTerm = substFully(term, bindings); // Fully substitute term before checking
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheckRecursive(var, t, bindings));
                case KifAtom ignored -> false;
            };
        }

        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            // Attempt to match the pattern at the current level
            return Optional.ofNullable(match(lhsPattern, target, Map.of()))
                .map(bindings -> substFully(rhsTemplate, bindings)) // If match, substitute RHS
                .or(() -> // If no match at this level, try rewriting subterms
                    (target instanceof KifList targetList) ?
                        targetList.terms().stream()
                            .map(subTerm -> rewriteRecursive(subTerm, lhsPattern, rhsTemplate)) // Recursively try to rewrite each subterm
                            .collect(Collectors.collectingAndThen(Collectors.toList(), rewrittenSubs -> {
                                var changed = false;
                                List<KifTerm> newSubTerms = new ArrayList<>(targetList.size());
                                for (var i = 0; i < targetList.size(); i++) {
                                    var opt = rewrittenSubs.get(i);
                                    if (opt.isPresent()) { // If a subterm was rewritten
                                        changed = true;
                                        newSubTerms.add(opt.get());
                                    } else { // Otherwise, keep the original subterm
                                        newSubTerms.add(targetList.get(i));
                                    }
                                }
                                // If any subterm changed, return the new list, otherwise empty
                                return changed ? Optional.of(new KifList(newSubTerms)) : Optional.<KifTerm>empty();
                            }))
                        : Optional.empty() // Not a list, cannot rewrite subterms
                );
        }
    }

    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing...");
        final Map<String, DefaultListModel<AssertionViewModel>> noteAssertionModels = new ConcurrentHashMap<>();
        final JList<AssertionViewModel> assertionDisplayList = new JList<>();
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JButton addButton = new JButton("Add Note"), pauseResumeButton = new JButton("Pause"), clearAllButton = new JButton("Clear All");
        private final JPopupMenu noteContextMenu = new JPopupMenu();
        private final JMenuItem analyzeItem = new JMenuItem("Analyze Note (LLM -> KIF)");
        private final JMenuItem enhanceItem = new JMenuItem("Enhance Note (LLM Replace)");
        private final JMenuItem summarizeItem = new JMenuItem("Summarize Note (LLM)");
        private final JMenuItem keyConceptsItem = new JMenuItem("Identify Key Concepts (LLM)");
        private final JMenuItem generateQuestionsItem = new JMenuItem("Generate Questions (LLM)");
        private final JMenuItem renameItem = new JMenuItem("Rename Note");
        private final JMenuItem removeItem = new JMenuItem("Remove Note");
        private final JPopupMenu assertionContextMenu = new JPopupMenu();
        private final JMenuItem retractAssertionItem = new JMenuItem("Retract Assertion");
        private final JMenuItem showSupportItem = new JMenuItem("Show Support"); // TODO: Implement support display
        private final boolean showInputCallbacksInUI;
        private CogNote reasoner;
        private Note currentNote = null;

        public SwingUI(@Nullable CogNote reasoner) {
            super("Cognote - Enhanced Reasoner");
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
            Stream.of(noteList, noteEditor, addButton, pauseResumeButton, clearAllButton, statusLabel,
                      analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, renameItem, removeItem,
                      retractAssertionItem, showSupportItem)
                  .forEach(c -> c.setFont(UI_DEFAULT_FONT));
            assertionDisplayList.setFont(MONOSPACED_FONT); // Keep monospaced for KIF alignment
        }

        private void setupComponents() {
            noteEditor.setLineWrap(true);
            noteEditor.setWrapStyleWord(true);
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteList.setCellRenderer(new NoteListCellRenderer());
            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
            statusLabel.setHorizontalAlignment(SwingConstants.LEFT);

            // Setup Assertion List
            assertionDisplayList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            assertionDisplayList.setCellRenderer(new AssertionListCellRenderer());

            // Setup Context Menus
            noteContextMenu.add(analyzeItem);
            noteContextMenu.addSeparator();
            noteContextMenu.add(enhanceItem);
            noteContextMenu.add(summarizeItem);
            noteContextMenu.add(keyConceptsItem);
            noteContextMenu.add(generateQuestionsItem);
            noteContextMenu.addSeparator();
            noteContextMenu.add(renameItem);
            noteContextMenu.add(removeItem);

            assertionContextMenu.add(retractAssertionItem);
            assertionContextMenu.add(showSupportItem);
            showSupportItem.setEnabled(false); // TODO: Enable when implemented
        }

        private void setupLayout() {
            var leftPane = new JScrollPane(noteList);
            leftPane.setPreferredSize(new Dimension(250, 0));
            var editorPane = new JScrollPane(noteEditor);
            var assertionListPane = new JScrollPane(assertionDisplayList); // Use the JList here
            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPane, assertionListPane); // Replace derivationView
            rightSplit.setResizeWeight(0.5); // Adjust split ratio if needed
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

            // Note Context Menu Actions
            analyzeItem.addActionListener(this::analyzeNoteAction);
            enhanceItem.addActionListener(this::enhanceNoteAction);
            summarizeItem.addActionListener(this::summarizeNoteAction);
            keyConceptsItem.addActionListener(this::keyConceptsAction);
            generateQuestionsItem.addActionListener(this::generateQuestionsAction);
            renameItem.addActionListener(this::renameNoteAction);
            removeItem.addActionListener(this::removeNoteAction);

            // Assertion Context Menu Actions
            retractAssertionItem.addActionListener(this::retractSelectedAssertionAction);
            // showSupportItem listener needed when implemented

            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    saveCurrentNoteText();
                    currentNote = noteList.getSelectedValue();
                    updateUIForSelection();
                }
            });
            noteEditor.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent evt) { saveCurrentNoteText(); }
            });
            noteList.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
                @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
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
            // Add listener for assertion list context menu
            assertionDisplayList.addMouseListener(new MouseAdapter() {
                 @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
                 @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
                 private void maybeShowPopup(MouseEvent e) {
                     if (e.isPopupTrigger()) {
                         var index = assertionDisplayList.locationToIndex(e.getPoint());
                         if (index != -1) {
                             assertionDisplayList.setSelectedIndex(index);
                             var selectedVM = assertionDisplayList.getSelectedValue();
                             // Only show menu for actual assertions, not LLM items, and only if active
                             retractAssertionItem.setEnabled(selectedVM != null && selectedVM.isActualAssertion() && selectedVM.status() == AssertionStatus.ACTIVE);
                             showSupportItem.setEnabled(selectedVM != null && selectedVM.isActualAssertion()); // TODO: Refine when implemented
                             assertionContextMenu.show(e.getComponent(), e.getX(), e.getY());
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
                // Set the model for the assertion list
                assertionDisplayList.setModel(noteAssertionModels.computeIfAbsent(currentNote.id, id -> new DefaultListModel<>()));
                setTitle("Cognote - " + currentNote.title);
                SwingUtilities.invokeLater(noteEditor::requestFocusInWindow);
            } else {
                noteEditor.setText("");
                assertionDisplayList.setModel(new DefaultListModel<>()); // Clear list model
                setTitle("Cognote - Enhanced Reasoner");
            }
            setControlsEnabled(true);
        }

        private void addNote(ActionEvent e) {
            Optional.ofNullable(JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE)).map(String::trim).filter(Predicate.not(String::isEmpty)).ifPresent(t -> {
                var newNote = new Note(generateId(ID_PREFIX_NOTE), t, "");
                noteListModel.addElement(newNote);
                noteAssertionModels.put(newNote.id, new DefaultListModel<>()); // Create model for new note
                noteList.setSelectedValue(newNote, true);
            });
        }

        private void removeNoteAction(ActionEvent e) {
            performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions (including derived)?", JOptionPane.WARNING_MESSAGE, noteToRemove -> {
                reasoner.processRetractionByNoteIdInput(noteToRemove.id, "UI-Remove");
                noteAssertionModels.remove(noteToRemove.id); // Remove assertion list model
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

        // --- LLM Action Handlers ---

        private void enhanceNoteAction(ActionEvent e) {
            performNoteActionAsync("Enhancing", note -> {
                addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Enhancing Note via LLM...");
                return reasoner.enhanceNoteWithLlmAsync(note.text, note.id);
            }, (enhancedText, note) -> {
                removeLlmPlaceholders(note.id);
                processLlmEnhancementResponse(enhancedText, note);
            }, this::handleLlmFailure);
        }

        private void analyzeNoteAction(ActionEvent e) {
            performNoteActionAsync("Analyzing", note -> {
                clearNoteAssertionList(note.id); // Clear previous analysis results
                addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Analyzing Note via LLM -> KIF...");
                reasoner.processRetractionByNoteIdInput(note.id, "UI-Analyze-Retract"); // Retract old assertions
                return reasoner.text2kifAsync(note.text, note.id);
            }, (kifString, note) -> {
                 removeLlmPlaceholders(note.id);
                 processLlmKifResponse(kifString, note);
            }, this::handleLlmFailure);
        }

        private void summarizeNoteAction(ActionEvent e) {
             performNoteActionAsync("Summarizing", note -> {
                 addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Summarizing Note via LLM...");
                 return reasoner.summarizeNoteWithLlmAsync(note.text, note.id);
             }, (summary, note) -> {
                 removeLlmPlaceholders(note.id);
                 addLlmUiItem(note.id, AssertionDisplayType.LLM_SUMMARY, summary);
             }, this::handleLlmFailure);
         }

         private void keyConceptsAction(ActionEvent e) {
             performNoteActionAsync("Identifying Concepts", note -> {
                 addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Identifying Key Concepts via LLM...");
                 return reasoner.keyConceptsWithLlmAsync(note.text, note.id);
             }, (concepts, note) -> {
                 removeLlmPlaceholders(note.id);
                 addLlmUiItem(note.id, AssertionDisplayType.LLM_CONCEPTS, concepts);
             }, this::handleLlmFailure);
         }

         private void generateQuestionsAction(ActionEvent e) {
             performNoteActionAsync("Generating Questions", note -> {
                 addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Generating Questions via LLM...");
                 return reasoner.generateQuestionsWithLlmAsync(note.text, note.id);
             }, (questions, note) -> {
                 removeLlmPlaceholders(note.id);
                 // Split questions and add individually
                 questions.lines()
                     .map(q -> q.replaceFirst("^\\s*-\\s*", "").trim()) // Remove leading "- "
                     .filter(Predicate.not(String::isBlank))
                     .forEach(q -> addLlmUiItem(note.id, AssertionDisplayType.LLM_QUESTION, q));
             }, this::handleLlmFailure);
         }

        // --- Assertion Action Handlers ---

        private void retractSelectedAssertionAction(ActionEvent e) {
            Optional.ofNullable(assertionDisplayList.getSelectedValue())
                .filter(AssertionViewModel::isActualAssertion)
                .map(AssertionViewModel::id)
                .ifPresent(id -> {
                    System.out.println("UI Requesting retraction for: " + id);
                    reasoner.processRetractionByIdInput(id, "UI-Retract");
                });
        }

        // --- Helper Methods ---

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
                addLlmUiItem(enhancedNote.id, AssertionDisplayType.LLM_INFO, "Note text enhanced by LLM.");
            } else {
                Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = String.format("Enhancement failed for '%s': Empty response", enhancedNote.title));
                addLlmUiItem(enhancedNote.id, AssertionDisplayType.LLM_ERROR, "Enhancement failed (Empty Response)");
                JOptionPane.showMessageDialog(this, "LLM returned empty enhancement.", "Enhancement Failed", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void processLlmKifResponse(String kifString, Note analyzedNote) {
            try {
                var terms = KifParser.parseKif(kifString);
                var counts = new int[]{0, 0}; // [queued, skipped]
                addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_INFO, "--- Start KIF Analysis ---");
                for (var term : terms) {
                    if (term instanceof KifList list && !list.terms.isEmpty()) {
                        // Use the main queueing logic which handles forall/exists/rules/facts
                        Optional.ofNullable(reasoner).ifPresent(r -> r.queueExpressionFromSource(term, "UI-LLM", analyzedNote.id));
                        // Add a placeholder to the UI list immediately
                        addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_QUEUED_KIF, term.toKifString());
                        counts[0]++;
                    } else if (term != null) {
                        addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_SKIPPED, "Skipped (non-list/empty): " + term.toKifString());
                        counts[1]++;
                    }
                }
                Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = String.format("Analyzed '%s': %d queued, %d skipped", analyzedNote.title, counts[0], counts[1]));
                addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_INFO, String.format("--- End KIF Analysis (%d queued, %d skipped) ---", counts[0], counts[1]));
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
            removeLlmPlaceholders(contextNote.id); // Remove placeholders on failure
            addLlmUiItem(contextNote.id, AssertionDisplayType.LLM_ERROR, action + ": " + cause.getMessage());
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
                    r.clearAllKnowledge(); // This now calls clearAllNoteAssertionLists via callback
                }
            });
        }

        // Called by reasoner callback thread
        public void handleReasonerCallback(String type, Object payload) {
             SwingUtilities.invokeLater(() -> {
                 switch (payload) {
                     case Assertion assertion -> {
                         var noteId = assertion.sourceNoteId;
                         if (CALLBACK_ASSERT_INPUT.equals(type) && !showInputCallbacksInUI) return;

                         if (noteId != null) {
                             updateAssertionListForNote(noteId, type, assertion);
                         } else if (CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) {
                             // Update status in all lists where the assertion might appear (due to support chains)
                             updateAssertionStatusInAllModels(assertion.id,
                                 CALLBACK_ASSERT_RETRACTED.equals(type) ? AssertionStatus.RETRACTED : AssertionStatus.EVICTED);
                         }
                     }
                     case AssertionViewModel llmItem when CALLBACK_LLM_RESPONSE.equals(type) -> {
                         // LLM items are already added directly in the UI thread handlers
                         // This callback is mainly for potential external listeners or logging
                         // We could update a placeholder here if needed, but current approach adds final item directly.
                     }
                     default -> {} // Ignore other payload types
                 }
             });
         }

         // --- UI List Model Management ---

         private void updateAssertionListForNote(String noteId, String type, Assertion assertion) {
             var model = noteAssertionModels.get(noteId);
             if (model == null) return; // Should not happen if note exists

             var vm = AssertionViewModel.fromAssertion(assertion, type);

             // Try to find and update first
             var existingIndex = findViewModelIndexById(model, assertion.id);
             if (existingIndex != -1) {
                 var existingVm = model.getElementAt(existingIndex);
                 // Only update if status changes (e.g., from active to retracted)
                 // Or if it was an LLM Queued placeholder being replaced by actual assertion
                 if (vm.status() != existingVm.status() || existingVm.displayType() == AssertionDisplayType.LLM_QUEUED_KIF) {
                     model.setElementAt(vm, existingIndex);
                 }
             } else {
                 // If not found, add it (common case for ADDED/INPUT)
                 if (vm.status() == AssertionStatus.ACTIVE) {
                     // Remove any "LLM Queued" placeholder for this KIF string before adding the real one
                     removeLlmQueuedPlaceholder(model, assertion.toKifString());
                     model.addElement(vm);
                 }
                 // Don't add if it's already retracted/evicted and wasn't in the list
             }
         }

         private void updateAssertionStatusInAllModels(String assertionId, AssertionStatus newStatus) {
              noteAssertionModels.values().forEach(model -> {
                  var index = findViewModelIndexById(model, assertionId);
                  if (index != -1) {
                      var oldVm = model.getElementAt(index);
                      if (oldVm.status() != newStatus) { // Avoid redundant updates
                          model.setElementAt(oldVm.withStatus(newStatus), index);
                      }
                  }
              });
          }

          private void addLlmUiItem(String noteId, AssertionDisplayType type, String content) {
              var model = noteAssertionModels.computeIfAbsent(noteId, id -> new DefaultListModel<>());
              var vm = new AssertionViewModel(
                  generateId(ID_PREFIX_LLM_ITEM + type.name().toLowerCase()),
                  noteId,
                  content, // Content is the text for LLM items
                  type,
                  AssertionStatus.ACTIVE, // LLM items are always 'active' in the list context
                  0.0, 0, // No priority/depth for LLM items
                  System.currentTimeMillis()
              );
              model.addElement(vm);
              // Optionally scroll to the bottom
              if (currentNote != null && noteId.equals(currentNote.id)) {
                  int lastIndex = model.getSize() - 1;
                  if (lastIndex >= 0) {
                      assertionDisplayList.ensureIndexIsVisible(lastIndex);
                  }
              }
              // Notify listeners (including potential external ones via CogNote)
              Optional.ofNullable(reasoner).ifPresent(r -> r.callback(CALLBACK_LLM_RESPONSE, vm));
          }

          private void removeLlmPlaceholders(String noteId) {
               Optional.ofNullable(noteAssertionModels.get(noteId)).ifPresent(model -> {
                   for (int i = model.size() - 1; i >= 0; i--) {
                       if (model.getElementAt(i).displayType() == AssertionDisplayType.LLM_PLACEHOLDER) {
                           model.removeElementAt(i);
                       }
                   }
               });
           }

           private void removeLlmQueuedPlaceholder(DefaultListModel<AssertionViewModel> model, String kifString) {
                for (int i = model.size() - 1; i >= 0; i--) {
                    var vm = model.getElementAt(i);
                    if (vm.displayType() == AssertionDisplayType.LLM_QUEUED_KIF && vm.content().equals(kifString)) {
                        model.removeElementAt(i);
                        return; // Assume only one placeholder per KIF string
                    }
                }
            }


         private int findViewModelIndexById(DefaultListModel<AssertionViewModel> model, String id) {
             for (int i = 0; i < model.getSize(); i++) {
                 if (model.getElementAt(i).id().equals(id)) {
                     return i;
                 }
             }
             return -1;
         }

         // Method called by clearAllKnowledge
         public void clearAllNoteAssertionLists() {
             noteAssertionModels.clear();
             // If a note is selected, clear its list model too
             Optional.ofNullable(currentNote)
                 .map(note -> noteAssertionModels.computeIfAbsent(note.id, id -> new DefaultListModel<>()))
                 .ifPresent(DefaultListModel::clear);
             // Set the current list to an empty model
             assertionDisplayList.setModel(new DefaultListModel<>());
         }

         // Method to clear list for a specific note (e.g., before re-analyzing)
         private void clearNoteAssertionList(String noteId) {
              Optional.ofNullable(noteAssertionModels.get(noteId)).ifPresent(DefaultListModel::clear);
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
            summarizeItem.setEnabled(enabled && noteSelected && reasonerReady);
            keyConceptsItem.setEnabled(enabled && noteSelected && reasonerReady);
            generateQuestionsItem.setEnabled(enabled && noteSelected && reasonerReady);
            renameItem.setEnabled(enabled && noteSelected);
            removeItem.setEnabled(enabled && noteSelected && reasonerReady);
            Optional.ofNullable(reasoner).filter(_ -> enabled).ifPresent(r -> pauseResumeButton.setText(r.isPaused() ? "Resume" : "Pause"));
        }

        @FunctionalInterface interface NoteAction { void execute(Note note); }
        @FunctionalInterface interface NoteAsyncAction<T> { CompletableFuture<T> execute(Note note); }

        // --- Inner Classes for UI ---

        static class Note {
            final String id; String title; String text;
            Note(String id, String title, String text) { this.id = requireNonNull(id); this.title = requireNonNull(title); this.text = requireNonNull(text); }
            @Override public String toString() { return title; }
            @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); }
            @Override public int hashCode() { return id.hashCode(); }
        }

        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                var label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setBorder(new EmptyBorder(5, 10, 5, 10));
                label.setFont(UI_DEFAULT_FONT);
                return label;
            }
        }

        // --- View Model and Renderer for Assertion List ---

        enum AssertionStatus { ACTIVE, RETRACTED, EVICTED }
        enum AssertionDisplayType {
            INPUT, ADDED, UNIVERSAL, SKOLEMIZED, // From Assertions
            LLM_QUEUED_KIF, LLM_SKIPPED, LLM_SUMMARY, LLM_CONCEPTS, LLM_QUESTION, LLM_ERROR, LLM_INFO, LLM_PLACEHOLDER // From LLM/UI
        }

        record AssertionViewModel(String id, String noteId, String content, AssertionDisplayType displayType, AssertionStatus status,
                                  double priority, int depth, long timestamp) {

             static AssertionViewModel fromAssertion(Assertion assertion, String callbackType) {
                 AssertionDisplayType displayType = switch (assertion.assertionType) {
                     case GROUND -> (callbackType.equals(CALLBACK_ASSERT_INPUT)) ? AssertionDisplayType.INPUT : AssertionDisplayType.ADDED;
                     case UNIVERSAL -> AssertionDisplayType.UNIVERSAL;
                     case SKOLEMIZED -> AssertionDisplayType.SKOLEMIZED;
                 };
                 AssertionStatus status = switch (callbackType) {
                     case CALLBACK_ASSERT_RETRACTED -> AssertionStatus.RETRACTED;
                     case CALLBACK_EVICT -> AssertionStatus.EVICTED;
                     default -> AssertionStatus.ACTIVE;
                 };
                 return new AssertionViewModel(assertion.id, assertion.sourceNoteId, assertion.toKifString(), displayType, status,
                                               assertion.pri, assertion.derivationDepth, assertion.timestamp);
             }

             // Helper to create a new VM with updated status
             public AssertionViewModel withStatus(AssertionStatus newStatus) {
                 return new AssertionViewModel(id, noteId, content, displayType, newStatus, priority, depth, timestamp);
             }

             public boolean isActualAssertion() {
                  return switch(displayType) {
                      case INPUT, ADDED, UNIVERSAL, SKOLEMIZED -> true;
                      default -> false;
                  };
             }
         }

        static class AssertionListCellRenderer extends JPanel implements ListCellRenderer<AssertionViewModel> {
            private final JLabel iconLabel = new JLabel();
            private final JLabel contentLabel = new JLabel();
            private final JLabel detailLabel = new JLabel();
            private final Border activeBorder = new CompoundBorder(new LineBorder(Color.LIGHT_GRAY, 1), new EmptyBorder(3, 5, 3, 5));
            private final Border inactiveBorder = new CompoundBorder(new LineBorder(Color.WHITE, 1), new EmptyBorder(3, 5, 3, 5)); // Less prominent border
            private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
            private final JPanel textPanel;


            AssertionListCellRenderer() {
                setLayout(new BorderLayout(5, 0));
                textPanel = new JPanel(new BorderLayout());
                textPanel.add(contentLabel, BorderLayout.CENTER);
                textPanel.add(detailLabel, BorderLayout.SOUTH);
                add(iconLabel, BorderLayout.WEST);
                add(textPanel, BorderLayout.CENTER);
                setOpaque(true);
                contentLabel.setFont(MONOSPACED_FONT);
                detailLabel.setFont(UI_SMALL_FONT);
                detailLabel.setForeground(Color.GRAY);
                iconLabel.setBorder(new EmptyBorder(0, 2, 0, 5));
            }

            @Override
            public Component getListCellRendererComponent(JList<? extends AssertionViewModel> list, AssertionViewModel value, int index, boolean isSelected, boolean cellHasFocus) {
                contentLabel.setText(value.content());
                String details;
                if (value.isActualAssertion()) {
                     details = String.format("ID: %s | P:%.3f | D:%d | T:%s | %s",
                         value.id(), value.priority(), value.depth(), value.displayType(), timeFormatter.format(Instant.ofEpochMilli(value.timestamp())));
                } else {
                     details = String.format("ID: %s | %s | %s",
                         value.id(), value.displayType(), timeFormatter.format(Instant.ofEpochMilli(value.timestamp())));
                }
                detailLabel.setText(details);

                Color bgColor = Color.WHITE;
                Color fgColor = Color.BLACK;
                String iconText = "?"; // Default icon

                switch (value.displayType()) {
                    case INPUT -> { iconText = "I"; bgColor = new Color(230, 255, 230); } // Light green
                    case ADDED -> { iconText = "A"; bgColor = Color.WHITE; }
                    case UNIVERSAL -> { iconText = "∀"; bgColor = new Color(230, 230, 255); } // Light blue
                    case SKOLEMIZED -> { iconText = "∃"; bgColor = new Color(255, 255, 220); } // Light yellow
                    case LLM_QUEUED_KIF -> { iconText = "Q"; fgColor = Color.BLUE; }
                    case LLM_SKIPPED -> { iconText = "S"; fgColor = Color.ORANGE; }
                    case LLM_SUMMARY -> { iconText = "Σ"; fgColor = Color.DARK_GRAY; }
                    case LLM_CONCEPTS -> { iconText = "C"; fgColor = Color.DARK_GRAY; }
                    case LLM_QUESTION -> { iconText = "?"; fgColor = Color.MAGENTA; }
                    case LLM_ERROR -> { iconText = "!"; fgColor = Color.RED; }
                    case LLM_INFO -> { iconText = "i"; fgColor = Color.GRAY; }
                    case LLM_PLACEHOLDER -> { iconText = "..."; fgColor = Color.LIGHT_GRAY; }
                }

                iconLabel.setText(iconText);
                iconLabel.setForeground(fgColor);

                if (value.status() != AssertionStatus.ACTIVE) {
                    fgColor = Color.LIGHT_GRAY; // Dim text for inactive
                    contentLabel.setText("<html><strike>" + value.content() + "</strike></html>");
                    detailLabel.setText(value.status() + " | " + details);
                    bgColor = new Color(245, 245, 245); // Very light gray background
                    setBorder(inactiveBorder);
                } else {
                    contentLabel.setText(value.content()); // No strikethrough
                    detailLabel.setText(details);
                    setBorder(activeBorder);
                }

                contentLabel.setForeground(fgColor);
                detailLabel.setForeground(value.status() == AssertionStatus.ACTIVE ? Color.GRAY : Color.LIGHT_GRAY);

                if (isSelected) {
                    setBackground(list.getSelectionBackground());
                    setForeground(list.getSelectionForeground());
                    textPanel.setBackground(list.getSelectionBackground());
                    contentLabel.setForeground(list.getSelectionForeground());
                    detailLabel.setForeground(list.getSelectionForeground());
                    iconLabel.setForeground(list.getSelectionForeground());
                } else {
                    setBackground(bgColor);
                    setForeground(fgColor); // Use calculated fgColor
                    textPanel.setBackground(bgColor);
                    // contentLabel and detailLabel foreground already set based on status
                    iconLabel.setForeground(value.status() == AssertionStatus.ACTIVE ? fgColor : Color.LIGHT_GRAY); // Dim icon if inactive
                }

                return this;
            }
        }
    }
}
