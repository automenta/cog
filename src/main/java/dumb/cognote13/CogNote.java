package dumb.cognote13;

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

    // KIF Constants
    public static final String KIF_OPERATOR_IMPLIES = "=>";
    public static final String KIF_OPERATOR_EQUIV = "<=>";
    public static final String KIF_OPERATOR_AND = "and";
    public static final String KIF_OPERATOR_OR = "or";
    public static final String KIF_OPERATOR_EXISTS = "exists";
    public static final String KIF_OPERATOR_FORALL = "forall";
    public static final String KIF_OPERATOR_EQUAL = "=";
    public static final String KIF_OPERATOR_NOT = "not";
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");

    // UI Constants
    private static final int UI_FONT_SIZE = 16;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);

    // ID Generation
    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private static final String ID_PREFIX_RULE = "rule";
    private static final String ID_PREFIX_FACT = "fact";
    private static final String ID_PREFIX_SKOLEM_FUNC = "skf_";
    private static final String ID_PREFIX_SKOLEM_CONST = "skc_";
    private static final String ID_PREFIX_ENTITY = "entity_"; // Unused currently? Retain for potential future use.
    private static final String ID_PREFIX_NOTE = "note-";
    private static final String ID_PREFIX_LLM_ITEM = "llm_";

    // LLM Configuration
    private static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_LLM_MODEL = "llamablit";
    private static final int HTTP_TIMEOUT_SECONDS = 90;

    // Callback Types
    private static final String CALLBACK_ASSERT_ADDED = "assert-added";
    private static final String CALLBACK_ASSERT_INPUT = "assert-input";
    private static final String CALLBACK_ASSERT_RETRACTED = "assert-retracted";
    private static final String CALLBACK_EVICT = "evict";
    private static final String CALLBACK_LLM_RESPONSE = "llm-response";

    // Reasoner Configuration & Tuning
    private static final double DEFAULT_RULE_PRIORITY = 1.0;
    private static final double INPUT_ASSERTION_BASE_PRIORITY = 10.0;
    private static final double LLM_ASSERTION_BASE_PRIORITY = 15.0; // Slightly higher for explicit LLM KIF
    private static final double DERIVED_PRIORITY_DECAY = 0.95;
    private static final int MAX_DERIVATION_DEPTH = 10;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;
    private static final boolean ENABLE_FORWARD_INSTANTIATION = true;
    private static final boolean ENABLE_RULE_DERIVATION = true;
    private static final boolean ENABLE_SKOLEMIZATION = true;

    // Resource Limits & Thresholds
    private static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90;
    private static final int KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    private static final int COMMIT_QUEUE_CAPACITY = 1024 * 1024;
    private static final int TASK_QUEUE_CAPACITY = 1024 * 1024;
    private static final int QUEUE_SIZE_THRESHOLD_WARN = TASK_QUEUE_CAPACITY / 2;
    private static final int QUEUE_SIZE_THRESHOLD_HALT = TASK_QUEUE_CAPACITY * 9 / 10;
    private static final int MIN_INFERENCE_WORKERS = 2;

    // Networking & Timeouts
    private static final int WS_STOP_TIMEOUT_MS = 1000;
    private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;

    // Parsing & Misc
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int MAX_WS_PARSE_PREVIEW = 100;
    private static final String MARKER_RETRACTED = "[RETRACTED]"; // Used in UI Renderer
    private static final String MARKER_EVICTED = "[EVICTED]"; // Used in UI Renderer
    private static final String MARKER_INPUT = "[Input]"; // Unused currently?
    private static final String MARKER_ADDED = "[Added]"; // Unused currently?
    private static final String MARKER_LLM_QUEUED = "[LLM Queued]"; // Unused currently?

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
                ui = new SwingUI(null); // Initialize UI first
                var server = new CogNote(port, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.setReasoner(server); // Set the reasoner reference in the UI

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    server.stopReasoner();
                }));

                server.startReasoner(); // Start WS server and ReasonerEngine threads

                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified.");

                ui.setVisible(true); // Make UI visible

            } catch (Exception e) {
                System.err.println("Initialization/Startup failed: " + e.getMessage());
                e.printStackTrace();
                Optional.ofNullable(ui).ifPresent(JFrame::dispose); // Clean up UI window if created
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
        if (s >= 3 && list.get(1).equals(list.get(2))) {
             return opOpt.filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OPERATOR_EQUAL)).isPresent();
        } else if (opOpt.filter(KIF_OPERATOR_NOT::equals).isPresent() && s == 2 && list.get(1) instanceof KifList inner) {
            return inner.size() >= 3 && inner.get(1).equals(inner.get(2)) &&
                   inner.getOperator().filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OPERATOR_EQUAL)).isPresent();
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

    // --- WebSocket Handling ---

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
        var msg = Optional.ofNullable(ex.getMessage()).orElse("");
        if (ex instanceof IOException && (msg.contains("Socket closed") || msg.contains("Connection reset") || msg.contains("Broken pipe"))) {
            System.err.println("WS Network Info from " + addr + ": " + msg);
        } else {
            System.err.println("WS Error from " + addr + ": " + msg);
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
                KifParser.parseKif(trimmed)
                         .forEach(term -> queueExpressionFromSource(term, sourceId, null));
            } catch (ParseException | ClassCastException e) {
                System.err.printf("WS Message Parse Error from %s: %s | Original: %s...%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
            } catch (Exception e) {
                System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void broadcastMessage(String type, Assertion assertion) {
         var kifString = assertion.toKifString();
         var message = switch (type) {
             case CALLBACK_ASSERT_ADDED -> String.format("assert-added %.4f %s [%s] {type:%s, depth:%d}", assertion.pri(), kifString, assertion.id(), assertion.assertionType(), assertion.derivationDepth());
             case CALLBACK_ASSERT_INPUT -> String.format("assert-input %.4f %s [%s] {type:%s, depth:%d}", assertion.pri(), kifString, assertion.id(), assertion.assertionType(), assertion.derivationDepth());
             case CALLBACK_ASSERT_RETRACTED -> String.format("retract %s", assertion.id());
             case CALLBACK_EVICT -> String.format("evict %s", assertion.id());
             default -> String.format("%s %.4f %s [%s]", type, assertion.pri(), kifString, assertion.id());
         };
         safeBroadcast(message);
     }

     private void broadcastMessage(String type, String noteId, SwingUI.AssertionViewModel llmItem) {
          if (!CALLBACK_LLM_RESPONSE.equals(type)) return;
          var message = String.format("llm-response %s [%s] {type:%s, content:\"%s\"}",
              noteId, llmItem.id(), llmItem.displayType(), llmItem.content().replace("\"", "\\\""));
          safeBroadcast(message);
      }

      private void safeBroadcast(String message) {
          try {
              if (!getConnections().isEmpty()) broadcast(message);
          } catch (Exception e) {
              // Ignore common concurrent modification or closed connection errors during broadcast
              if (!(e instanceof ConcurrentModificationException || Optional.ofNullable(e.getMessage()).map(m -> m.contains("closed")).orElse(false))) {
                  System.err.println("Error during WebSocket broadcast: " + e.getMessage());
              }
          }
      }

    // --- Input Processing & Loading ---

    public void loadExpressionsFromFile(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

        var kifBuffer = new StringBuilder();
        var counts = new long[]{0, 0, 0}; // [lines, processedBlocks, queuedItems]

        try (var reader = Files.newBufferedReader(path)) {
            var line = "";
            int parenDepth = 0;
            while ((line = reader.readLine()) != null) {
                counts[0]++;
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
                        counts[1]++;
                        try {
                            for (var term : KifParser.parseKif(kifText)) {
                                queueExpressionFromSource(term, "file:" + filename, null);
                                counts[2]++;
                            }
                        } catch (ParseException e) {
                            System.err.printf("File Parse Error (line ~%d): %s near '%s...'%n", counts[0], e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
                        } catch (Exception e) {
                            System.err.printf("File Processing Error (line ~%d): %s for '%s...'%n", counts[0], e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
                            e.printStackTrace();
                        }
                    }
                } else if (parenDepth < 0) {
                    System.err.printf("Mismatched parentheses near line %d: '%s'%n", counts[0], line);
                    parenDepth = 0; // Reset depth on error
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d KIF blocks from %s, attempted to queue %d items.%n", counts[1], filename, counts[2]);
    }

    private void queueExpressionFromSource(KifTerm term, String sourceId, @Nullable String sourceNoteId) {
        if (!(term instanceof KifList list) || list.terms().isEmpty()) {
            if (term != null && !(term instanceof KifList))
                System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
            return;
        }

        list.getOperator().ifPresentOrElse(
            op -> {
                switch (op) {
                    case KIF_OPERATOR_IMPLIES, KIF_OPERATOR_EQUIV -> handleRuleInput(list, sourceId);
                    case KIF_OPERATOR_EXISTS -> handleExistsInput(list, sourceId, sourceNoteId);
                    case KIF_OPERATOR_FORALL -> handleForallInput(list, sourceId, sourceNoteId);
                    default -> handleStandardAssertionInput(list, sourceId, sourceNoteId);
                }
            },
            () -> handleStandardAssertionInput(list, sourceId, sourceNoteId) // List with no operator atom? Treat as fact.
        );
    }

    private void handleRuleInput(KifList list, String sourceId) {
        try {
            var rule = Rule.parseRule(generateId(ID_PREFIX_RULE), list, DEFAULT_RULE_PRIORITY);
            reasonerEngine.addRule(rule);
            if (KIF_OPERATOR_EQUIV.equals(list.getOperator().orElse(""))) {
                // Create and add the reverse implication for <=>
                var reverseList = new KifList(List.of(KifAtom.of(KIF_OPERATOR_IMPLIES), list.get(2), list.get(1)));
                reasonerEngine.addRule(Rule.parseRule(generateId(ID_PREFIX_RULE), reverseList, DEFAULT_RULE_PRIORITY));
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid rule format ignored (" + sourceId + "): " + list.toKifString() + " | Error: " + e.getMessage());
        }
    }

    private void handleStandardAssertionInput(KifList list, String sourceId, @Nullable String sourceNoteId) {
        if (list.containsVariable()) {
             System.err.println("Warning: Non-ground assertion input ignored (" + sourceId + ") (use exists/forall or ensure rules bind variables): " + list.toKifString());
             return;
        }
        var isNegated = list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
        if (isNegated && list.size() != 2) {
             System.err.println("Invalid 'not' format ignored (" + sourceId + ") (must have 1 argument): " + list.toKifString());
             return;
        }
        var isEq = !isNegated && list.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
        var isOriented = isEq && list.size() == 3 && list.get(1).calculateWeight() > list.get(2).calculateWeight();
        var derivedType = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
        var pri = (sourceNoteId != null ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.calculateWeight());

        submitPotentialAssertion(new PotentialAssertion(list, pri, Set.of(), sourceId, isEq, isNegated, isOriented, sourceNoteId, derivedType, List.of(), 0));
    }

    private void handleExistsInput(KifList existsExpr, String sourceId, @Nullable String sourceNoteId) {
        if (!ENABLE_SKOLEMIZATION) {
            System.err.println("Warning: Skolemization disabled, ignoring 'exists' (" + sourceId + "): " + existsExpr.toKifString());
            return;
        }
        if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) {
            System.err.println("Invalid 'exists' format ignored (" + sourceId + ") (expected '(exists (?Var | (vars...)) (body...))'): " + existsExpr.toKifString());
            return;
        }
        var variables = KifTerm.collectVariablesFromSpec(existsExpr.get(1));
        if (variables.isEmpty()) {
            System.out.println("Note: 'exists' with no variables (" + sourceId + "), processing body: " + existsExpr.get(2).toKifString());
            queueExpressionFromSource(existsExpr.get(2), sourceId + "-existsBody", sourceNoteId);
            return;
        }
        var skolemizedBody = reasonerEngine.performSkolemization(body, variables, Map.of());
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);

        var isNegated = skolemizedBody.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent();
        var isEq = !isNegated && skolemizedBody.getOperator().filter(KIF_OPERATOR_EQUAL::equals).isPresent();
        var isOriented = isEq && skolemizedBody.size() == 3 && skolemizedBody.get(1).calculateWeight() > skolemizedBody.get(2).calculateWeight();
        var pri = (sourceNoteId != null ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + skolemizedBody.calculateWeight());

        submitPotentialAssertion(new PotentialAssertion(skolemizedBody, pri, Set.of(), sourceId + "-skolemized", isEq, isNegated, isOriented, sourceNoteId, AssertionType.SKOLEMIZED, List.of(), 0));
    }

    private void handleForallInput(KifList forallExpr, String sourceId, @Nullable String sourceNoteId) {
        if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) {
            System.err.println("Invalid 'forall' format ignored (" + sourceId + ") (expected '(forall (?Var | (vars...)) (body...))'): " + forallExpr.toKifString());
            return;
        }
        var variables = KifTerm.collectVariablesFromSpec(forallExpr.get(1));
        if (variables.isEmpty()) {
            System.out.println("Note: 'forall' with no variables (" + sourceId + "), processing body: " + forallExpr.get(2).toKifString());
            queueExpressionFromSource(forallExpr.get(2), sourceId + "-forallBody", sourceNoteId);
            return;
        }

        if (body.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent()) {
            System.out.println("Interpreting 'forall ... (" + body.getOperator().get() + " ...)' as rule from " + sourceId + ": " + body.toKifString());
            handleRuleInput(body, sourceId);
        } else if (ENABLE_FORWARD_INSTANTIATION) {
            System.out.println("Storing 'forall' as universal fact from " + sourceId + ": " + forallExpr.toKifString());
            var pri = (sourceNoteId != null ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + forallExpr.calculateWeight());
            var pa = new PotentialAssertion(forallExpr, pri, Set.of(), sourceId, false, false, false, sourceNoteId, AssertionType.UNIVERSAL, List.copyOf(variables), 0);
            submitPotentialAssertion(pa);
        } else {
            System.err.println("Warning: Forward instantiation disabled, ignoring 'forall' fact (" + sourceId + "): " + forallExpr.toKifString());
        }
    }

    // --- Reasoner Control ---

    public void startReasoner() {
        if (!running) { System.err.println("Cannot restart a stopped reasoner."); return; }
        paused = false;
        reasonerEngine.start();
        try {
            start(); // Start WebSocketServer
            System.out.println("WebSocket server started on port " + getPort());
        } catch (IllegalStateException e) {
            System.err.println("WebSocket server already started or failed to start: " + e.getMessage());
        } catch (Exception e) { // Catch broader exceptions during startup
            System.err.println("WebSocket server failed to start: " + e.getMessage());
            stopReasoner(); // Attempt cleanup if WS fails
        }
        System.out.println("Reasoner started.");
        updateUIStatus();
    }

    public void stopReasoner() {
        if (!running) return;
        System.out.println("Stopping reasoner and services...");
        running = false;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); } // Wake up paused threads
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
        // Shutdown HttpClient's executor if it's managed
        httpClient.executor().filter(ExecutorService.class::isInstance).map(ExecutorService.class::cast)
                  .ifPresent(exec -> shutdownExecutor(exec, "HTTP Client Executor"));
        reasonerStatus = "Stopped";
        updateUIStatus();
        System.out.println("Reasoner stopped.");
    }

    public boolean isPaused() { return this.paused; }

    public void setPaused(boolean pause) {
        if (this.paused == pause || !running) return;
        this.paused = pause;
        reasonerEngine.setPaused(pause);
        if (!pause) {
            synchronized (pauseLock) { pauseLock.notifyAll(); }
        }
        reasonerStatus = pause ? "Paused" : "Running";
        updateUIStatus();
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        var idsToNotify = knowledgeBase.getAllAssertionIds(); // Get IDs before clearing
        knowledgeBase.clear();
        reasonerEngine.clear();
        noteIdToAssertionIds.clear();
        SwingUtilities.invokeLater(swingUI::clearAllNoteAssertionLists); // Clear UI note/assertion data

        // Notify listeners about the retractions after clearing internal state
        idsToNotify.forEach(id -> {
            var dummyKif = new KifList(List.of(KifAtom.of("retracted"), KifAtom.of(id)));
            // Create a minimal dummy assertion for the callback payload
            callback(CALLBACK_ASSERT_RETRACTED, new Assertion(id, dummyKif, 0, 0, null, Set.of(), AssertionType.GROUND, false, false, false, List.of(), 0));
        });

        reasonerStatus = "Cleared";
        setPaused(false); // Resume after clear
        updateUIStatus();
        System.out.println("Knowledge cleared.");
    }

    // --- Public API for Adding/Retracting ---

    public void submitPotentialAssertion(PotentialAssertion pa) {
        if (pa == null || (pa.kif() instanceof KifList kl && isTrivial(kl))) return;

        // Determine if this input should be immediately broadcast/shown in UI as "input"
        var isDirectInputSource = !(pa.sourceId().startsWith(ID_PREFIX_RULE) || pa.sourceId().startsWith(ID_PREFIX_FACT));
        if (isDirectInputSource && (pa.sourceNoteId() != null || broadcastInputAssertions)) {
             // Create a temporary assertion representation for the input callback
             var inputAssertion = new Assertion(generateId("input"), pa.kif(), pa.pri(), System.currentTimeMillis(), pa.sourceNoteId(), pa.support(), pa.derivedType(), pa.isEquality(), pa.isOrientedEquality(), pa.isNegated(), pa.quantifiedVars(), pa.derivationDepth());
             callback(CALLBACK_ASSERT_INPUT, inputAssertion);
        }
        reasonerEngine.submitPotentialAssertion(pa);
    }

    public void submitRule(Rule rule) {
        reasonerEngine.addRule(rule);
        updateUIStatus();
    }

    public void processRetractionByIdInput(String assertionId, String sourceId) {
        var removed = knowledgeBase.retractAssertion(assertionId);
        if (removed != null) System.out.printf("Retraction initiated for [%s] by %s: %s%n", assertionId, sourceId, removed.toKifString());
        else System.out.printf("Retraction by ID %s from %s failed: ID not found or already retracted.%n", assertionId, sourceId);
        updateUIStatus(); // Update status after attempting retraction
    }

    public void processRetractionByNoteIdInput(String noteId, String sourceId) {
        var idsToRetract = noteIdToAssertionIds.remove(noteId); // Atomically get and remove the set
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.printf("Initiating retraction of %d assertions for note %s from %s.%n", idsToRetract.size(), noteId, sourceId);
            // Retract a copy to avoid issues if retraction modifies the set concurrently (though it shouldn't here)
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
                var removed = reasonerEngine.removeRule(ruleForm);
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

    // --- Central Callback Mechanism ---

    void callback(String type, Object payload) {
         switch (payload) {
             case Assertion assertion -> invokeAssertionCallbacks(type, assertion);
             case SwingUI.AssertionViewModel llmItem when CALLBACK_LLM_RESPONSE.equals(type) -> invokeLlmCallbacks(type, llmItem);
             default -> System.err.println("Warning: Unknown callback payload type: " + payload.getClass());
         }
         if (!"Cleared".equals(reasonerStatus) && !"Stopping".equals(reasonerStatus) && !"Stopped".equals(reasonerStatus)) {
              updateUIStatus(); // Update UI unless reasoner is stopped/cleared
         }
     }

     private void invokeAssertionCallbacks(String type, Assertion a) {
         var noteId = a.sourceNoteId();
         if (noteId != null) {
             if (CALLBACK_ASSERT_ADDED.equals(type)) {
                 noteIdToAssertionIds.computeIfAbsent(noteId, _ -> ConcurrentHashMap.newKeySet()).add(a.id());
             } else if (CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) {
                 noteIdToAssertionIds.computeIfPresent(noteId, (_, set) -> {
                     set.remove(a.id());
                     return set.isEmpty() ? null : set; // Remove entry if set becomes empty
                 });
             }
         }

         var shouldBroadcast = switch (type) {
             case CALLBACK_ASSERT_ADDED, CALLBACK_ASSERT_RETRACTED, CALLBACK_EVICT -> true;
             case CALLBACK_ASSERT_INPUT -> broadcastInputAssertions;
             default -> false;
         };
         if (shouldBroadcast) broadcastMessage(type, a);

         // UI update is now triggered via the central callback
         if (swingUI != null && swingUI.isDisplayable()) swingUI.handleReasonerCallback(type, a);

         // External pattern-based callbacks
         callbackRegistrations.forEach(reg -> {
             if (CALLBACK_ASSERT_ADDED.equals(type)) {
                 try {
                     Optional.ofNullable(Unifier.match(reg.pattern, a.kif(), Map.of()))
                             .ifPresent(bindings -> reg.callback.onMatch(type, a, bindings));
                 } catch (Exception e) {
                     System.err.println("Error executing KIF pattern callback for " + reg.pattern.toKifString() + ": " + e.getMessage());
                     e.printStackTrace();
                 }
             }
         });
     }

     private void invokeLlmCallbacks(String type, SwingUI.AssertionViewModel llmItem) {
         // UI update is handled via the central callback
         if (swingUI != null && swingUI.isDisplayable()) swingUI.handleReasonerCallback(type, llmItem);

         // Broadcast LLM responses if configured
         if (broadcastInputAssertions) { // Reuse flag for LLM items for now
             broadcastMessage(type, llmItem.noteId(), llmItem);
         }
     }

    private void updateUIStatus() {
        if (swingUI != null && swingUI.isDisplayable()) {
            var statusText = String.format("KB: %d/%d | Tasks: %d | Commits: %d | Rules: %d | Status: %s",
                    knowledgeBase.getAssertionCount(), capacity, reasonerEngine.getTaskQueueSize(), reasonerEngine.getCommitQueueSize(), reasonerEngine.getRuleCount(), reasonerEngine.getCurrentStatus());
            SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
        }
    }

    // --- LLM Interaction ---

    private CompletableFuture<String> llmAsync(String prompt, String interactionType, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2)); // Low temp for factual tasks

            var request = HttpRequest.newBuilder(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                     throw new IOException("LLM API request failed (" + interactionType + "): " + response.statusCode() + " Body: " + responseBody);
                }
                return extractLlmContent(new JSONObject(new JSONTokener(responseBody)))
                       .orElseThrow(() -> new IOException("LLM response missing expected content field. Body: " + responseBody));

            } catch (IOException | InterruptedException e) {
                System.err.printf("LLM API interaction failed (%s for note %s): %s%n", interactionType, noteId, e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException("LLM API communication error (" + interactionType + ")", e);
            } catch (Exception e) { // Catch JSON parsing errors etc.
                System.err.printf("LLM response processing failed (%s for note %s): %s%n", interactionType, noteId, e.getMessage());
                e.printStackTrace();
                throw new CompletionException("LLM response processing error (" + interactionType + ")", e);
            }
        }, httpClient.executor().orElse(Executors.newVirtualThreadPerTaskExecutor())); // Use HttpClient's executor if available
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
                    else if (ex != null)
                        System.err.printf("LLM Error (KIF %s): %s%n", noteId, ex.getMessage());
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
        // Try common response structures first
        return Optional.ofNullable(r.optJSONObject("message"))
                       .map(m -> m.optString("content", null))
                       .or(() -> Optional.ofNullable(r.optString("response", null))) // Some Ollama models use this
                       .or(() -> Optional.ofNullable(r.optJSONArray("choices"))
                                      .filter(Predicate.not(JSONArray::isEmpty))
                                      .map(a -> a.optJSONObject(0))
                                      .map(c -> c.optJSONObject("message"))
                                      .map(m -> m.optString("content", null)))
                       .or(() -> findNestedContent(r)); // Fallback to recursive search
    }

    private Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> obj.keySet().stream()
                                      .map(obj::get)
                                      .map(this::findNestedContent)
                                      .flatMap(Optional::stream)
                                      .findFirst()
                                      .or(() -> Optional.ofNullable(obj.optString("content", null)).filter(Predicate.not(String::isBlank))); // Check current level last
            case JSONArray arr -> IntStream.range(0, arr.length())
                                          .mapToObj(arr::get)
                                          .map(this::findNestedContent)
                                          .flatMap(Optional::stream)
                                          .findFirst();
            case String s -> Optional.of(s).filter(Predicate.not(String::isBlank)); // If we hit a string directly
            default -> Optional.empty();
        };
    }

    // =========================================================================
    // === Inner Classes (Data Structures, Logic Components, UI) =============
    // =========================================================================

    enum TaskType { MATCH_ANTECEDENT, APPLY_ORDERED_REWRITE }

    enum AssertionType { GROUND, UNIVERSAL, SKOLEMIZED }

    sealed interface KifTerm permits KifAtom, KifVar, KifList {
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

        String toKifString();
        boolean containsVariable();
        Set<KifVar> getVariables();
        int calculateWeight();

        default boolean containsSkolemTerm() {
            return switch (this) {
                case KifAtom a -> a.value.startsWith(ID_PREFIX_SKOLEM_CONST);
                case KifList l -> l.terms.stream().anyMatch(KifTerm::containsSkolemTerm) ||
                                  l.getOperator().filter(op -> op.startsWith(ID_PREFIX_SKOLEM_FUNC)).isPresent();
                case KifVar ignored -> false;
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

        KifAtom { requireNonNull(value); }

        public static KifAtom of(String value) { return internCache.computeIfAbsent(value, KifAtom::new); }

        @Override
        public String toKifString() {
             var needsQuotes = value.isEmpty() || !SAFE_ATOM_PATTERN.matcher(value).matches() ||
                               value.chars().anyMatch(c -> Character.isWhitespace(c) || "()\";?".indexOf(c) != -1);
             return needsQuotes ? '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : value;
        }

        @Override public boolean containsVariable() { return false; }
        @Override public Set<KifVar> getVariables() { return Set.of(); }
        @Override public int calculateWeight() { return 1; }
        @Override public String toString() { return "KifAtom[" + value + ']'; }
    }

    record KifVar(String name) implements KifTerm {
        private static final Map<String, KifVar> internCache = new ConcurrentHashMap<>(256);

        KifVar {
            requireNonNull(name);
            if (!name.startsWith("?") || name.length() < 2)
                throw new IllegalArgumentException("Variable name must start with '?' and have length > 1: " + name);
        }

        public static KifVar of(String name) { return internCache.computeIfAbsent(name, KifVar::new); }

        @Override public String toKifString() { return name; }
        @Override public boolean containsVariable() { return true; }
        @Override public Set<KifVar> getVariables() { return Set.of(this); }
        @Override public int calculateWeight() { return 1; }
        @Override public String toString() { return "KifVar[" + name + ']'; }
    }

    // Made final for potential performance benefits and clarity
    static final class KifList implements KifTerm {
        final List<KifTerm> terms;
        // Caching fields - volatile ensures visibility across threads
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;
        private volatile Set<KifVar> variablesCache;
        private volatile Boolean containsVariableCache;
        private volatile Boolean containsSkolemCache;


        KifList(List<KifTerm> terms) { this.terms = List.copyOf(requireNonNull(terms)); }
        KifList(KifTerm... terms) { this(List.of(terms)); } // Convenience constructor

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
        public boolean containsSkolemTerm() {
            if (containsSkolemCache == null) {
                // Explicitly call super implementation to avoid infinite recursion if overridden incorrectly
                containsSkolemCache = KifTerm.super.containsSkolemTerm();
            }
            return containsSkolemCache;
        }


        @Override
        public Set<KifVar> getVariables() {
            if (variablesCache == null) {
                variablesCache = terms.stream().flatMap(t -> t.getVariables().stream()).collect(Collectors.toUnmodifiableSet());
            }
            return variablesCache;
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
            if (this == o) return true;
            if (!(o instanceof KifList that)) return false;
            // Compare hash first for quick check, then list content
            return this.hashCode() == that.hashCode() && terms.equals(that.terms);
        }

        @Override
        public int hashCode() {
            if (!hashCodeCalculated) {
                hashCodeCache = terms.hashCode();
                hashCodeCalculated = true;
            }
            return hashCodeCache;
        }

        @Override public String toString() { return "KifList" + terms; }
    }

    record Assertion(String id, KifList kif, double pri, long timestamp, @Nullable String sourceNoteId,
                     Set<String> support, AssertionType assertionType,
                     boolean isEquality, boolean isOrientedEquality, boolean isNegated,
                     List<KifVar> quantifiedVars, int derivationDepth) implements Comparable<Assertion> {
        Assertion {
            requireNonNull(id); requireNonNull(kif); requireNonNull(assertionType);
            support = Set.copyOf(requireNonNull(support));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            // Validation checks
            if (isNegated != kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for KIF: " + kif.toKifString());
            if (assertionType == AssertionType.UNIVERSAL && (kif.getOperator().filter(KIF_OPERATOR_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Universal assertion must be (forall ...) with quantified vars: " + kif.toKifString());
            if (assertionType != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty())
                throw new IllegalArgumentException("Only Universal assertions should have quantified vars: " + kif.toKifString());
        }

        @Override
        public int compareTo(Assertion other) {
            // Primary: Higher priority first
            int priComp = Double.compare(other.pri, this.pri);
            if (priComp != 0) return priComp;
            // Secondary: Lower depth first
            int depthComp = Integer.compare(this.derivationDepth, other.derivationDepth);
            if (depthComp != 0) return depthComp;
            // Tertiary: Newer timestamp first (more recently added/derived)
            return Long.compare(other.timestamp, this.timestamp);
        }

        String toKifString() { return kif.toKifString(); }

        KifTerm getEffectiveTerm() {
             return switch (assertionType) {
                 case GROUND, SKOLEMIZED -> isNegated ? kif.get(1) : kif; // If negated, the term inside 'not'
                 case UNIVERSAL -> kif.get(2); // The body of the forall
             };
        }

        Set<KifAtom> getReferencedPredicates() {
            Set<KifAtom> predicates = new HashSet<>();
            collectPredicatesRecursive(getEffectiveTerm(), predicates);
            return Collections.unmodifiableSet(predicates);
        }

        private void collectPredicatesRecursive(KifTerm term, Set<KifAtom> predicates) {
            if (term instanceof KifList list && !list.terms().isEmpty() && list.get(0) instanceof KifAtom pred) {
                predicates.add(pred);
                // Recurse only on arguments, not the predicate itself
                list.terms().stream().skip(1).forEach(sub -> collectPredicatesRecursive(sub, predicates));
            } else if (term instanceof KifList list) {
                // Handle lists where the first element isn't an Atom (less common, but possible)
                list.terms().forEach(sub -> collectPredicatesRecursive(sub, predicates));
            }
        }
    }

    record Rule(String id, KifList form, KifTerm antecedent, KifTerm consequent, double pri, List<KifTerm> antecedents) {
        Rule {
            requireNonNull(id); requireNonNull(form); requireNonNull(antecedent); requireNonNull(consequent);
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
                 case KifList list -> List.of(validateAntecedentClause(list)); // Single antecedent list
                 case KifTerm t when t.equals(KifAtom.of("true")) -> List.<KifTerm>of(); // Handle tautology antecedent
                 default -> throw new IllegalArgumentException("Antecedent must be a KIF list, (not list), or (and ...): " + antTerm.toKifString());
             };
             validateUnboundVariables(ruleForm, antTerm, conTerm);
             return new Rule(id, ruleForm, antTerm, conTerm, pri, parsedAntecedents);
        }

        private static KifTerm validateAntecedentClause(KifTerm term) {
            return switch(term) {
                case KifList list -> {
                    if (list.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent()) {
                         if (list.size() != 2 || !(list.get(1) instanceof KifList))
                             throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + list.toKifString());
                    }
                    yield list; // Valid list or (not list)
                }
                default -> throw new IllegalArgumentException("Elements of rule antecedent must be lists or (not list): " + term.toKifString());
            };
        }

        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
             var unboundConsequentVars = new HashSet<>(consequent.getVariables());
             unboundConsequentVars.removeAll(antecedent.getVariables());
             unboundConsequentVars.removeAll(getQuantifierBoundVariables(consequent)); // Remove locally bound vars
             // Only issue warning for '=>' rules, as '<=>' might intentionally have unbound vars handled by the reverse rule
             if (!unboundConsequentVars.isEmpty() && ruleForm.getOperator().filter(KIF_OPERATOR_IMPLIES::equals).isPresent()) {
                 System.err.println("Warning: Rule consequent has variables not bound by antecedent or local quantifier: "
                                    + unboundConsequentVars.stream().map(KifVar::name).collect(Collectors.joining(", "))
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
                    collectQuantifierBoundVariablesRecursive(list.get(2), boundVars); // Recurse into body
                }
                case KifList list -> list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
                default -> {} // Atoms, Vars don't bind
            }
        }

        // Use form for equals/hashCode as rules are uniquely identified by their structure
        @Override public boolean equals(Object o) { return this == o || (o instanceof Rule r && form.equals(r.form)); }
        @Override public int hashCode() { return form.hashCode(); }
    }

    record PotentialAssertion(KifList kif, double pri, Set<String> support, String sourceId, boolean isEquality,
                              boolean isNegated, boolean isOrientedEquality, @Nullable String sourceNoteId,
                              AssertionType derivedType, List<KifVar> quantifiedVars, int derivationDepth) {
        PotentialAssertion {
            requireNonNull(kif); requireNonNull(sourceId); requireNonNull(derivedType);
            support = Set.copyOf(requireNonNull(support));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            // Validation checks similar to Assertion
            if (isNegated != kif.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for potential KIF: " + kif.toKifString());
            if (derivedType == AssertionType.UNIVERSAL && (kif.getOperator().filter(KIF_OPERATOR_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Potential Universal assertion must be (forall ...) with quantified vars: " + kif.toKifString());
            if (derivedType != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty())
                throw new IllegalArgumentException("Only potential Universal assertions should have quantified vars: " + kif.toKifString());
        }

        // Use KIF for equals/hashCode to detect duplicates in queues/sets
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && kif.equals(pa.kif); }
        @Override public int hashCode() { return kif.hashCode(); }

        KifTerm getEffectiveTerm() {
            return switch (derivedType) {
                 case GROUND, SKOLEMIZED -> isNegated ? kif.get(1) : kif;
                 case UNIVERSAL -> kif.get(2);
            };
        }
    }

    record InferenceTask(TaskType type, double pri, Object data) implements Comparable<InferenceTask> {
        InferenceTask { requireNonNull(type); requireNonNull(data); }

        static InferenceTask matchAntecedent(Rule rule, Assertion trigger, Map<KifVar, KifTerm> bindings, double pri) {
            return new InferenceTask(TaskType.MATCH_ANTECEDENT, pri, new MatchContext(rule, trigger.id(), bindings));
        }
        static InferenceTask applyRewrite(Assertion rule, Assertion target, double pri) {
            return new InferenceTask(TaskType.APPLY_ORDERED_REWRITE, pri, new RewriteContext(rule, target));
        }
        // Higher priority tasks execute first
        @Override public int compareTo(InferenceTask other) { return Double.compare(other.pri, this.pri); }
    }

    record MatchContext(Rule rule, String triggerAssertionId, Map<KifVar, KifTerm> initialBindings) {}
    record RewriteContext(Assertion rewriteRule, Assertion targetAssertion) {}
    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
        CallbackRegistration { requireNonNull(pattern); requireNonNull(callback); }
    }

    static class PathNode {
        static final Class<KifVar> VAR_MARKER = KifVar.class; // Marker for variable path
        static final Object LIST_MARKER = new Object(); // Marker for generic list path
        final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
        final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet(); // Assertions ending at this node
    }

    static class PathIndex {
        private final PathNode root = new PathNode();

        void add(Assertion assertion) { addPathsRecursive(assertion.kif(), assertion.id(), root); }
        void remove(Assertion assertion) { removePathsRecursive(assertion.kif(), assertion.id(), root); }
        void clear() { root.children.clear(); root.assertionIdsHere.clear(); }

        Set<String> findUnifiable(KifTerm queryTerm) { return findCandidates(queryTerm, this::findUnifiableRecursive); }
        Set<String> findInstances(KifTerm queryPattern) { return findCandidates(queryPattern, this::findInstancesRecursive); }
        Set<String> findGeneralizations(KifTerm queryTerm) { return findCandidates(queryTerm, this::findGeneralizationsRecursive); }

        private Set<String> findCandidates(KifTerm query, TriConsumer<KifTerm, PathNode, Set<String>> searchFunc) {
            Set<String> candidates = ConcurrentHashMap.newKeySet();
            searchFunc.accept(query, root, candidates);
            return Set.copyOf(candidates); // Return immutable copy
        }

        private void addPathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return;
            currentNode.assertionIdsHere.add(assertionId); // Add ID at current level

            var key = getIndexKey(term);
            var termNode = currentNode.children.computeIfAbsent(key, _ -> new PathNode());
            termNode.assertionIdsHere.add(assertionId); // Also add ID at the specific term node

            // Recurse into sub-terms if it's a list
            if (term instanceof KifList list) {
                list.terms().forEach(subTerm -> addPathsRecursive(subTerm, assertionId, termNode));
            }
        }

        private boolean removePathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return false;
            currentNode.assertionIdsHere.remove(assertionId);

            var key = getIndexKey(term);
            var termNode = currentNode.children.get(key);

            if (termNode != null) {
                termNode.assertionIdsHere.remove(assertionId);
                var canPruneChild = true; // Assume child can be pruned initially
                if (term instanceof KifList list) {
                    // Check if all recursive removals indicate the child is now empty
                    canPruneChild = list.terms().stream()
                                       .allMatch(subTerm -> removePathsRecursive(subTerm, assertionId, termNode));
                }
                // Prune child node if it's empty and its sub-paths were also pruned/empty
                if (canPruneChild && termNode.assertionIdsHere.isEmpty() && termNode.children.isEmpty()) {
                    currentNode.children.remove(key, termNode); // Atomic remove
                }
            }
            // Return true if this node itself is now empty and can be pruned by its parent
            return currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
        }

        private Object getIndexKey(KifTerm term) {
            return switch (term) {
                case KifAtom a -> a.value(); // Use atom value as key
                case KifVar _ -> PathNode.VAR_MARKER; // Use class marker for variables
                case KifList l -> l.getOperator().map(op -> (Object) op).orElse(PathNode.LIST_MARKER); // Use operator or list marker
            };
        }

        private void collectAllAssertionIds(PathNode node, Set<String> ids) {
            if (node == null) return;
            ids.addAll(node.assertionIdsHere);
            node.children.values().forEach(child -> collectAllAssertionIds(child, ids));
        }

        // Finds assertions that *could* unify with the queryTerm (queryTerm may contain vars)
        private void findUnifiableRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
             if (indexNode == null) return;

             // 1. Assertions indexed under VAR_MARKER can potentially unify with anything
             Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER))
                     .ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));

             // 2. If query is a list, assertions indexed under LIST_MARKER might unify
             if (queryTerm instanceof KifList) {
                 Optional.ofNullable(indexNode.children.get(PathNode.LIST_MARKER))
                         .ifPresent(listNode -> collectAllAssertionIds(listNode, candidates));
             }

             // 3. Check the specific key for the query term
             var specificNode = indexNode.children.get(getIndexKey(queryTerm));
             if (specificNode != null) {
                 candidates.addAll(specificNode.assertionIdsHere);
                 // If the query is a list, we need to recurse into its structure
                 if (queryTerm instanceof KifList ql && !ql.terms().isEmpty()) {
                     // Recurse on the first element (often the predicate) for further filtering
                     // A more sophisticated approach might check all sub-terms, but this is a common heuristic
                     findUnifiableRecursive(ql.terms().getFirst(), specificNode, candidates);
                 } else if (queryTerm instanceof KifList) {
                     // If it's a list but we don't recurse further (e.g., empty list), collect all under the node
                     collectAllAssertionIds(specificNode, candidates);
                 }
             }

             // 4. If the query term is a variable, any assertion at this level could unify
             if (queryTerm instanceof KifVar) {
                 indexNode.children.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));
             }
        }

        // Finds assertions that are instances of the queryPattern (pattern may contain vars)
        private void findInstancesRecursive(KifTerm queryPattern, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;

            // If the pattern is a variable, everything under this node is an instance
            if (queryPattern instanceof KifVar) {
                collectAllAssertionIds(indexNode, candidates);
                return;
            }

            // Check the specific path corresponding to the pattern's key
            var specificNode = indexNode.children.get(getIndexKey(queryPattern));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                // If pattern is a list, recurse into its structure
                if (queryPattern instanceof KifList ql && !ql.terms().isEmpty()) {
                    // Match the rest of the pattern against the sub-index
                    // Simplified: Recurse on first element. Full matching requires unification check later.
                    findInstancesRecursive(ql.terms().getFirst(), specificNode, candidates);
                }
                // If it's not a list (e.g., atom) or an empty list, adding assertionIdsHere is sufficient
            }

            // We do NOT consider VAR_MARKER or LIST_MARKER here, as we are looking for specific instances matching the pattern structure.
            // The final filtering happens outside the index using Unifier.match.
        }


        // Finds assertions that are generalizations of the queryTerm (queryTerm is ground)
        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;

            // 1. Patterns indexed under VAR_MARKER are generalizations of any term
             Optional.ofNullable(indexNode.children.get(PathNode.VAR_MARKER))
                     .ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));

            // 2. If queryTerm is a list, patterns indexed under LIST_MARKER might generalize
            if (queryTerm instanceof KifList ql) {
                 Optional.ofNullable(indexNode.children.get(PathNode.LIST_MARKER))
                         .ifPresent(listNode -> {
                             candidates.addAll(listNode.assertionIdsHere);
                             // Recurse further if the list isn't empty
                             if (!ql.terms().isEmpty()) {
                                 findGeneralizationsRecursive(ql.terms().getFirst(), listNode, candidates);
                             }
                         });
             }

            // 3. Check the specific key matching the query term
            Optional.ofNullable(indexNode.children.get(getIndexKey(queryTerm)))
                    .ifPresent(nextNode -> {
                        candidates.addAll(nextNode.assertionIdsHere);
                        // Recurse if the query term is a list
                        if (queryTerm instanceof KifList ql && !ql.terms().isEmpty()) {
                            findGeneralizationsRecursive(ql.terms().getFirst(), nextNode, candidates);
                        }
                    });

             // Note: We don't need a case for queryTerm being a KifVar because the input `queryTerm`
             // for generalization check is assumed to be ground (or specific).
        }

        @FunctionalInterface private interface TriConsumer<T, U, V> { void accept(T t, U u, V v); }
    }

    static class KnowledgeBase {
        private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
        private final PathIndex pathIndex = new PathIndex(); // Indexes GROUND and SKOLEMIZED assertions
        private final ConcurrentMap<KifAtom, Set<String>> universalIndex = new ConcurrentHashMap<>(); // Predicate -> UNIVERSAL assertion IDs
        private final ConcurrentMap<String, Set<String>> assertionDependencies = new ConcurrentHashMap<>(); // Supporter ID -> Set<Dependent ID>
        private final PriorityBlockingQueue<String> groundEvictionQueue = new PriorityBlockingQueue<>(1024,
                Comparator.<String, Double>comparing(id -> Optional.ofNullable(assertionsById.get(id)).map(Assertion::pri).orElse(Double.MAX_VALUE)) // Lower pri first for eviction
                          .thenComparing(id -> Optional.ofNullable(assertionsById.get(id)).map(Assertion::timestamp).orElse(Long.MAX_VALUE))); // Older first if pri same
        private final ReadWriteLock kbLock = new ReentrantReadWriteLock();
        private final int maxKbSize;
        private final BiConsumer<String, Object> eventCallback;

        KnowledgeBase(int maxKbSize, BiConsumer<String, Object> eventCallback) {
            this.maxKbSize = maxKbSize;
            this.eventCallback = requireNonNull(eventCallback);
        }

        int getAssertionCount() { return assertionsById.size(); }
        List<String> getAllAssertionIds() { return List.copyOf(assertionsById.keySet()); }
        Optional<Assertion> getAssertion(String id) { return Optional.ofNullable(assertionsById.get(id)); } // Read lock not strictly needed for ConcurrentHashMap get

        List<Assertion> getAllAssertions() {
            // No lock needed as ConcurrentHashMap's values() provides a weakly consistent view
            // and Assertion objects are immutable. Copy to list for stable snapshot if needed by caller.
            return List.copyOf(assertionsById.values());
        }

        Optional<Assertion> commitAssertion(PotentialAssertion pa) {
            kbLock.writeLock().lock(); // Lock needed for composite operations (check, add, index, evict)
            try {
                if (pa.kif() instanceof KifList kl && CogNote.isTrivial(kl)) return Optional.empty();

                // Determine final type (e.g., GROUND becomes SKOLEMIZED if it contains Skolem terms)
                var finalType = (pa.derivedType() == AssertionType.GROUND && pa.kif().containsSkolemTerm())
                                ? AssertionType.SKOLEMIZED : pa.derivedType();

                // --- Pre-commit Checks ---
                if (finalType == AssertionType.GROUND || finalType == AssertionType.SKOLEMIZED) {
                    if (isSubsumedInternal(pa.kif(), pa.isNegated())) return Optional.empty(); // Already covered by a more general fact
                    if (findExactMatchInternal(pa.kif()) != null) return Optional.empty(); // Exact duplicate
                } else if (finalType == AssertionType.UNIVERSAL) {
                    // Optional: Add more sophisticated semantic equivalence checks for universals if needed
                    if (findExactMatchInternal(pa.kif()) != null) return Optional.empty(); // Simple exact match check
                }

                // --- Enforce Capacity ---
                enforceKbCapacityInternal();
                if (assertionsById.size() >= maxKbSize) {
                     System.err.printf("Warning: KB full (%d/%d) after eviction attempt. Cannot add: %s%n", assertionsById.size(), maxKbSize, pa.kif().toKifString());
                     return Optional.empty();
                }

                // --- Create and Add Assertion ---
                var newId = generateId(ID_PREFIX_FACT + "-" + finalType.name().toLowerCase());
                var newAssertion = new Assertion(newId, pa.kif(), pa.pri(), System.currentTimeMillis(), pa.sourceNoteId(),
                                                 pa.support(), finalType, pa.isEquality(), pa.isOrientedEquality(),
                                                 pa.isNegated(), pa.quantifiedVars(), pa.derivationDepth());

                if (assertionsById.putIfAbsent(newId, newAssertion) != null) {
                    System.err.println("KB Commit Error: ID collision for " + newId); // Should be extremely rare
                    return Optional.empty();
                }

                // --- Indexing ---
                if (finalType == AssertionType.GROUND || finalType == AssertionType.SKOLEMIZED) {
                    pathIndex.add(newAssertion);
                    groundEvictionQueue.offer(newId); // Add to eviction queue (PriorityQueue handles sorting)
                } else if (finalType == AssertionType.UNIVERSAL) {
                    newAssertion.getReferencedPredicates().forEach(pred ->
                            universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(newId));
                }

                // --- Update Dependencies ---
                newAssertion.support().forEach(supporterId ->
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
                // Use a set to track retractions within this specific call stack to prevent cycles
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
            // Lockless read using PathIndex and assertionsById map
            // Filter results further based on exact type if needed by caller
            return pathIndex.findUnifiable(queryTerm).stream()
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    .filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED);
        }

        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            // Lockless read using PathIndex and assertionsById map
            var patternIsNegated = (queryPattern instanceof KifList ql && ql.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent());
            return pathIndex.findInstances(queryPattern).stream()
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    .filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED)
                    .filter(a -> a.isNegated() == patternIsNegated) // Check negation match early
                    .filter(a -> Unifier.match(queryPattern, a.kif(), Map.of()) != null); // Final unification check
        }

        List<Assertion> findRelevantUniversalAssertions(KifAtom predicate) {
            // Lockless read using universalIndex and assertionsById map
            return universalIndex.getOrDefault(predicate, Set.of()).stream()
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    // .filter(a -> a.assertionType() == AssertionType.UNIVERSAL) // Should always be true due to index
                    .toList(); // Collect to list for stable snapshot
        }

        // --- Internal Helpers (Assume write lock is held) ---

        private Assertion retractAssertionWithCascadeInternal(String id, Set<String> retractedInThisCascade) {
            if (!retractedInThisCascade.add(id)) return null; // Already processed in this cascade

            var removed = assertionsById.remove(id);
            if (removed != null) {
                // 1. Remove from type-specific indices
                if (removed.assertionType() == AssertionType.GROUND || removed.assertionType() == AssertionType.SKOLEMIZED) {
                    pathIndex.remove(removed);
                    groundEvictionQueue.remove(id); // Remove from eviction candidate queue
                } else if (removed.assertionType() == AssertionType.UNIVERSAL) {
                    removed.getReferencedPredicates().forEach(pred ->
                            universalIndex.computeIfPresent(pred, (_, ids) -> {
                                ids.remove(id);
                                return ids.isEmpty() ? null : ids; // Clean up empty sets
                            }));
                }

                // 2. Remove dependency links FROM supporters TO this removed assertion
                removed.support().forEach(supporterId ->
                        assertionDependencies.computeIfPresent(supporterId, (_, dependents) -> {
                            dependents.remove(id);
                            return dependents.isEmpty() ? null : dependents;
                        }));

                // 3. Recursively retract dependents OF this removed assertion
                Optional.ofNullable(assertionDependencies.remove(id)) // Remove the entry for the retracted ID itself
                        .ifPresent(dependents ->
                            new HashSet<>(dependents).forEach(depId -> retractAssertionWithCascadeInternal(depId, retractedInThisCascade))
                        );

                eventCallback.accept(CALLBACK_ASSERT_RETRACTED, removed);
            }
            return removed;
        }

        private boolean isSubsumedInternal(KifTerm term, boolean isNegated) {
            // Only check subsumption against GROUND/SKOLEMIZED assertions
            return pathIndex.findGeneralizations(term).stream()
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    .filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED)
                    .anyMatch(candidate -> candidate.isNegated() == isNegated // Check negation match
                                            && Unifier.match(candidate.getEffectiveTerm(), term, Map.of()) != null); // Check if candidate matches term
        }

        @Nullable
        private Assertion findExactMatchInternal(KifList kif) {
            // Check all types for exact match. Use PathIndex for initial filtering if possible.
            return pathIndex.findInstances(kif).stream()
                    .map(assertionsById::get)
                    .filter(Objects::nonNull)
                    .filter(a -> a.kif().equals(kif))
                    .findFirst()
                    .orElseGet(() -> // Fallback necessary for UNIVERSAL not fully matched by index prefix
                            assertionsById.values().stream()
                                    .filter(a -> a.kif().equals(kif))
                                    .findFirst().orElse(null)
                    );
        }

        private void enforceKbCapacityInternal() {
             // Evict lowest priority GROUND/SKOLEMIZED assertions until below capacity
             while (assertionsById.size() >= maxKbSize && !groundEvictionQueue.isEmpty()) {
                 Optional.ofNullable(groundEvictionQueue.poll()) // Get lowest pri/oldest ID
                         .flatMap(this::getAssertion) // Re-fetch assertion in case it was retracted meanwhile
                         .filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED) // Ensure it's evictable type
                         .ifPresent(toEvict -> {
                             var evicted = retractAssertionWithCascadeInternal(toEvict.id(), new HashSet<>()); // Perform cascade retraction
                             if (evicted != null) {
                                 eventCallback.accept(CALLBACK_EVICT, evicted); // Notify about the eviction
                             }
                         });
             }
        }

        private void checkResourceThresholds() {
            var currentSize = assertionsById.size();
            var warnThreshold = maxKbSize * KB_SIZE_THRESHOLD_WARN_PERCENT / 100;
            var haltThreshold = maxKbSize * KB_SIZE_THRESHOLD_HALT_PERCENT / 100;
            if (currentSize >= haltThreshold) {
                System.err.printf("KB CRITICAL: Size %d/%d (%.1f%%) exceeds halt threshold (%d%%)%n", currentSize, maxKbSize, 100.0 * currentSize / maxKbSize, KB_SIZE_THRESHOLD_HALT_PERCENT);
                // Consider potentially pausing or rejecting input here if needed
            } else if (currentSize >= warnThreshold) {
                System.out.printf("KB WARNING: Size %d/%d (%.1f%%) exceeds warning threshold (%d%%)%n", currentSize, maxKbSize, 100.0 * currentSize / maxKbSize, KB_SIZE_THRESHOLD_WARN_PERCENT);
            }
        }
    }

    static class ReasonerEngine {
        private final KnowledgeBase kb;
        private final Supplier<String> idGenerator;
        private final BiConsumer<String, Object> callbackNotifier;
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<PotentialAssertion> commitQueue = new LinkedBlockingQueue<>(COMMIT_QUEUE_CAPACITY);
        private final PriorityBlockingQueue<InferenceTask> taskQueue = new PriorityBlockingQueue<>(TASK_QUEUE_CAPACITY);
        private final ExecutorService commitExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "CommitThread"));
        private final ExecutorService inferenceExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Use virtual threads for I/O bound or numerous tasks
        private final Object pauseLock;
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private volatile String currentStatus = "Idle";

        ReasonerEngine(KnowledgeBase kb, Supplier<String> idGenerator, BiConsumer<String, Object> notifier, Object pauseLock) {
            this.kb = kb; this.idGenerator = idGenerator; this.callbackNotifier = notifier; this.pauseLock = pauseLock;
        }

        long getTaskQueueSize() { return taskQueue.size(); }
        long getCommitQueueSize() { return commitQueue.size(); }
        int getRuleCount() { return rules.size(); }
        String getCurrentStatus() { return currentStatus; }

        void start() {
            if (running) return;
            running = true; paused = false; currentStatus = "Starting";
            commitExecutor.submit(this::commitLoop);
            var workerCount = Math.max(MIN_INFERENCE_WORKERS, Runtime.getRuntime().availableProcessors()); // More workers with virtual threads
            IntStream.range(0, workerCount).forEach(i -> inferenceExecutor.submit(this::inferenceLoop));
            currentStatus = "Running";
            System.out.println("Reasoner Engine started with " + workerCount + " inference workers.");
        }

        void stop() {
            if (!running) return;
            running = false; paused = false;
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
            if (!pause) synchronized (pauseLock) { pauseLock.notifyAll(); }
        }

        void clear() {
            setPaused(true); // Pause while clearing
            rules.clear();
            commitQueue.clear();
            taskQueue.clear();
            // KB clear is handled by CogNote.clearAllKnowledge
            System.out.println("Reasoner Engine queues and rules cleared.");
            // Don't resume here, CogNote.clearAllKnowledge will resume
        }

        void submitPotentialAssertion(PotentialAssertion pa) {
            if (!running || pa == null) return;
            try {
                if (!commitQueue.offer(pa, 100, TimeUnit.MILLISECONDS)) // Timeout to prevent blocking indefinitely
                    System.err.println("Warning: Commit queue full. Discarding potential assertion: " + pa.kif().toKifString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while submitting potential assertion to commit queue.");
            }
            checkQueueThresholds();
        }

        void addRule(Rule rule) {
            if (rules.add(rule)) {
                System.out.println("Added rule: " + rule.id() + " " + rule.form().toKifString());
                triggerMatchingForNewRule(rule); // Check new rule against existing facts
            }
        }

        boolean removeRule(KifList ruleForm) {
            return rules.removeIf(rule -> rule.form().equals(ruleForm));
        }

        private void commitLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    waitIfPaused();
                    if (!running) break;
                    currentStatus = "Committing";
                    PotentialAssertion pa = commitQueue.take(); // Blocks until item available
                    kb.commitAssertion(pa).ifPresent(committed -> {
                        generateNewTasks(committed); // Trigger inference based on the new assertion
                        // Trigger forward instantiation only for newly committed GROUND assertions
                        if (ENABLE_FORWARD_INSTANTIATION && committed.assertionType() == AssertionType.GROUND) {
                            triggerInstantiation(committed);
                        }
                    });
                    currentStatus = "Idle (Commit)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Re-interrupt thread
                    running = false; // Stop the loop
                } catch (Exception e) { // Catch unexpected errors
                    System.err.println("Error in commit loop: " + e.getMessage());
                    e.printStackTrace();
                    currentStatus = "Error (Commit)";
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); running = false; } // Prevent busy-loop on error
                }
            }
            System.out.println("Commit thread finished.");
        }

        private void inferenceLoop() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    waitIfPaused();
                    if (!running) break;
                    InferenceTask task = taskQueue.take(); // Blocks until task available
                    currentStatus = "Inferring (" + task.type() + ")";
                    switch (task.type()) {
                        case MATCH_ANTECEDENT -> handleMatchAntecedentTask(task);
                        case APPLY_ORDERED_REWRITE -> handleApplyRewriteTask(task);
                        // default -> System.err.println("Unknown task type: " + task.type()); // Should not happen
                    }
                    currentStatus = "Idle (Inference)";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                } catch (Exception e) {
                    System.err.println("Error in inference loop ("+currentStatus+"): " + e.getMessage());
                    e.printStackTrace();
                    currentStatus = "Error (Inference)";
                     try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); running = false; }
                }
            }
             System.out.println("Inference worker finished.");
        }

        private void waitIfPaused() throws InterruptedException {
            synchronized (pauseLock) {
                while (paused && running) { // Check running flag inside loop
                    currentStatus = "Paused";
                    pauseLock.wait();
                }
            }
        }

        private void handleMatchAntecedentTask(InferenceTask task) {
            if (task.data() instanceof MatchContext(var rule, var triggerId, var initialBindings)) {
                findMatchesRecursive(rule, rule.antecedents(), initialBindings, Set.of(triggerId))
                        .forEach(matchResult -> processDerivedAssertion(rule, matchResult));
            } else {
                 System.err.println("Invalid data for MATCH_ANTECEDENT task: " + task.data());
            }
        }

        private void handleApplyRewriteTask(InferenceTask task) {
            if (!(task.data() instanceof RewriteContext(var ruleAssertion, var targetAssertion))) {
                 System.err.println("Invalid data for APPLY_ORDERED_REWRITE task: " + task.data());
                 return;
            }
            // Validation: Rule must be an oriented equality assertion
            if (!(ruleAssertion.assertionType() == AssertionType.GROUND || ruleAssertion.assertionType() == AssertionType.SKOLEMIZED)
                || ruleAssertion.isNegated() || !ruleAssertion.isEquality() || !ruleAssertion.isOrientedEquality() || ruleAssertion.kif().size() != 3) {
                return; // Not a valid rewrite rule
            }

            Unifier.rewrite(targetAssertion.kif(), ruleAssertion.kif().get(1), ruleAssertion.kif().get(2))
                    .filter(rewrittenTerm -> rewrittenTerm instanceof KifList && !rewrittenTerm.equals(targetAssertion.kif())) // Ensure it changed and is a list
                    .map(KifList.class::cast)
                    .filter(Predicate.not(CogNote::isTrivial)) // Avoid trivial results
                    .ifPresent(rewrittenList -> {
                        var support = Stream.concat(targetAssertion.support().stream(), Stream.of(targetAssertion.id(), ruleAssertion.id())).collect(Collectors.toSet());
                        var newDepth = Math.max(targetAssertion.derivationDepth(), ruleAssertion.derivationDepth()) + 1;

                        if (newDepth > MAX_DERIVATION_DEPTH) {
                            // System.out.printf("Rewrite depth limit (%d) reached. Discarding: %s from rule %s%n", MAX_DERIVATION_DEPTH, rewrittenList.toKifString(), ruleAssertion.id());
                            return;
                        }

                        var termWeight = rewrittenList.calculateWeight();
                        if (termWeight > MAX_DERIVED_TERM_WEIGHT) {
                            // System.err.printf("Rewrite term weight (%d) exceeds limit (%d). Discarding: %s from rule %s%n", termWeight, MAX_DERIVED_TERM_WEIGHT, rewrittenList.toKifString(), ruleAssertion.id());
                             return;
                        }

                        var isNeg = rewrittenList.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
                        var isEq = !isNeg && rewrittenList.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
                        var isOriented = isEq && rewrittenList.size() == 3 && rewrittenList.get(1).calculateWeight() > rewrittenList.get(2).calculateWeight();
                        var derivedType = rewrittenList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;

                        submitPotentialAssertion(new PotentialAssertion(rewrittenList, calculateDerivedPri(support, task.pri()), support, ruleAssertion.id(), isEq, isNeg, isOriented, findCommonSourceNodeId(support), derivedType, List.of(), newDepth));
                    });
        }

        private void processDerivedAssertion(Rule sourceRule, MatchResult matchResult) {
             var consequentTerm = Unifier.subst(sourceRule.consequent(), matchResult.bindings);
             if (consequentTerm == null) return; // Substitution failed? Should not happen if unbound vars checked

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
                     () -> processDerivedStandard(sourceRule, derivedList, matchResult) // Handle list with non-atom operator
                 );
             } else if (!(simplifiedTerm instanceof KifVar)) { // Ignore unbound variables, warn on other non-lists
                  System.err.println("Warning: Rule " + sourceRule.id() + " derived non-list/non-var consequent: " + simplifiedTerm.toKifString());
             }
        }

        private void processDerivedConjunction(Rule sourceRule, KifList conjunction, MatchResult matchResult) {
             conjunction.terms().stream().skip(1).forEach(term -> {
                 var simplifiedSubTerm = (term instanceof KifList kl) ? simplifyLogicalTerm(kl) : term;
                 // Re-package as a single derived assertion problem for each conjunct
                 // This avoids deeply nested calls and reuses the main processing logic
                 if (simplifiedSubTerm instanceof KifList conjunct) {
                     processDerivedAssertion(sourceRule, new MatchResult(matchResult.bindings, matchResult.supportIds)); // Pass original bindings/support
                 } else if (!(simplifiedSubTerm instanceof KifVar)) {
                      System.err.println("Warning: Rule " + sourceRule.id() + " derived (and ...) with non-list/non-var conjunct: " + term.toKifString());
                 }
             });
        }

        private void processDerivedForall(Rule sourceRule, KifList forallExpr, MatchResult matchResult) {
             if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) {
                  System.err.println("Rule " + sourceRule.id() + " derived invalid 'forall' structure: " + forallExpr.toKifString()); return;
             }
             var variables = KifTerm.collectVariablesFromSpec(forallExpr.get(1));
             if (variables.isEmpty()) { processDerivedAssertion(sourceRule, matchResult); return; } // Process body if no vars

             var newDepth = calculateDerivedDepth(matchResult.supportIds()) + 1;
             if (newDepth > MAX_DERIVATION_DEPTH) { /* System.out.printf("Depth limit (%d) for derived forall: %s%n", MAX_DERIVATION_DEPTH, forallExpr.toKifString()); */ return; }

             // Check if the body is an implication (derived rule)
             if (ENABLE_RULE_DERIVATION && body.getOperator().filter(op -> op.equals(KIF_OPERATOR_IMPLIES) || op.equals(KIF_OPERATOR_EQUIV)).isPresent()) {
                 try {
                     var derivedRulePri = calculateDerivedPri(matchResult.supportIds(), sourceRule.pri());
                     var derivedRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "-derived"), body, derivedRulePri);
                     addRule(derivedRule);
                     // Handle equivalence by adding the reverse rule
                     if (KIF_OPERATOR_EQUIV.equals(body.getOperator().orElse(""))) {
                         var reverseList = new KifList(List.of(KifAtom.of(KIF_OPERATOR_IMPLIES), body.get(2), body.get(1)));
                         addRule(Rule.parseRule(generateId(ID_PREFIX_RULE + "-derived"), reverseList, derivedRulePri));
                     }
                 } catch (IllegalArgumentException e) {
                     System.err.println("Invalid derived rule format ignored: " + body.toKifString() + " from rule " + sourceRule.id() + " | Error: " + e.getMessage());
                 }
             } else if (ENABLE_FORWARD_INSTANTIATION) { // Treat as a universal fact
                  var pa = new PotentialAssertion(forallExpr, calculateDerivedPri(matchResult.supportIds(), sourceRule.pri()), matchResult.supportIds(), sourceRule.id(), false, false, false, findCommonSourceNodeId(matchResult.supportIds()), AssertionType.UNIVERSAL, List.copyOf(variables), newDepth);
                  submitPotentialAssertion(pa);
             } else {
                  System.err.println("Warning: Forward instantiation disabled, ignoring derived 'forall' fact: " + forallExpr.toKifString());
             }
        }

        private void processDerivedExists(Rule sourceRule, KifList existsExpr, MatchResult matchResult) {
             if (!ENABLE_SKOLEMIZATION) { System.err.println("Warning: Skolemization disabled, ignoring derived 'exists': " + existsExpr.toKifString()); return; }
             if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) {
                  System.err.println("Rule " + sourceRule.id() + " derived invalid 'exists' structure: " + existsExpr.toKifString()); return;
             }
             var variables = KifTerm.collectVariablesFromSpec(existsExpr.get(1));
             if (variables.isEmpty()) { processDerivedAssertion(sourceRule, matchResult); return; } // Process body if no vars

             var newDepth = calculateDerivedDepth(matchResult.supportIds()) + 1;
             if (newDepth > MAX_DERIVATION_DEPTH) { /* System.out.printf("Depth limit (%d) for derived exists: %s%n", MAX_DERIVATION_DEPTH, existsExpr.toKifString()); */ return; }

             var skolemizedBody = performSkolemization(body, variables, matchResult.bindings());
             var isNegated = skolemizedBody.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
             var isEq = !isNegated && skolemizedBody.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
             var isOriented = isEq && skolemizedBody.size() == 3 && skolemizedBody.get(1).calculateWeight() > skolemizedBody.get(2).calculateWeight();

             submitPotentialAssertion(new PotentialAssertion(skolemizedBody, calculateDerivedPri(matchResult.supportIds(), sourceRule.pri()), matchResult.supportIds(), sourceRule.id(), isEq, isNegated, isOriented, findCommonSourceNodeId(matchResult.supportIds()), AssertionType.SKOLEMIZED, List.of(), newDepth));
        }

        private void processDerivedStandard(Rule sourceRule, KifList derivedTerm, MatchResult matchResult) {
            if (derivedTerm.containsVariable() || CogNote.isTrivial(derivedTerm)) {
                 // if (derivedTerm.containsVariable()) System.out.println("Note: Derived non-ground assertion ignored: " + derivedTerm.toKifString() + " from rule " + sourceRule.id());
                 return;
            }
            var newDepth = calculateDerivedDepth(matchResult.supportIds()) + 1;
            if (newDepth > MAX_DERIVATION_DEPTH) { /* System.out.printf("Depth limit (%d) reached: %s%n", MAX_DERIVATION_DEPTH, derivedTerm.toKifString()); */ return; }

            var termWeight = derivedTerm.calculateWeight();
            if (termWeight > MAX_DERIVED_TERM_WEIGHT) { /* System.err.printf("Term weight (%d) > limit (%d): %s%n", termWeight, MAX_DERIVED_TERM_WEIGHT, derivedTerm.toKifString()); */ return; }

            var isNegated = derivedTerm.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
            if (isNegated && derivedTerm.size() != 2) { System.err.println("Rule " + sourceRule.id() + " derived invalid 'not': " + derivedTerm.toKifString()); return; }

            var isEq = !isNegated && derivedTerm.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
            var isOriented = isEq && derivedTerm.size() == 3 && derivedTerm.get(1).calculateWeight() > derivedTerm.get(2).calculateWeight();
            var derivedType = derivedTerm.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;

            submitPotentialAssertion(new PotentialAssertion(derivedTerm, calculateDerivedPri(matchResult.supportIds(), sourceRule.pri()), matchResult.supportIds(), sourceRule.id(), isEq, isNegated, isOriented, findCommonSourceNodeId(matchResult.supportIds()), derivedType, List.of(), newDepth));
        }

        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifTerm> remainingClauses, Map<KifVar, KifTerm> currentBindings, Set<String> currentSupportIds) {
             if (remainingClauses.isEmpty()) {
                 return Stream.of(new MatchResult(currentBindings, currentSupportIds)); // Base case: all clauses matched
             }

             var clauseToMatch = Unifier.substFully(remainingClauses.getFirst(), currentBindings); // Apply current bindings
             var nextRemainingClauses = remainingClauses.subList(1, remainingClauses.size());

             var clauseIsNegated = (clauseToMatch instanceof KifList l && l.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent());
             var pattern = clauseIsNegated ? ((KifList) clauseToMatch).get(1) : clauseToMatch;

             if (!(pattern instanceof KifList)) { // We can only match against list-based assertions in the KB
                 // System.err.println("Rule " + rule.id() + " has non-list antecedent clause after substitution: " + clauseToMatch.toKifString());
                 return Stream.empty();
             }

             // Find candidate assertions in the KB that could unify with the pattern
             return kb.findUnifiableAssertions(pattern)
                     .filter(candidate -> candidate.isNegated() == clauseIsNegated) // Match negation status
                     // Attempt unification for each candidate
                     .flatMap(candidate -> Optional.ofNullable(Unifier.unify(pattern, candidate.getEffectiveTerm(), currentBindings))
                                             .map(newBindings -> {
                                                 // If unify succeeds, recurse with updated bindings and support
                                                 var nextSupport = Stream.concat(currentSupportIds.stream(), Stream.of(candidate.id())).collect(Collectors.toSet());
                                                 return findMatchesRecursive(rule, nextRemainingClauses, newBindings, nextSupport);
                                             })
                                             .orElse(Stream.empty())); // If unify fails, continue with next candidate
        }


        private void triggerInstantiation(Assertion groundAssertion) {
            if (groundAssertion.derivationDepth() >= MAX_DERIVATION_DEPTH) return;
            // Only trigger if the ground assertion has a predicate (first element is an atom)
            if (!(groundAssertion.kif().get(0) instanceof KifAtom predicate)) return;

            kb.findRelevantUniversalAssertions(predicate).stream()
                .filter(uniAssertion -> uniAssertion.derivationDepth() < MAX_DERIVATION_DEPTH) // Check universal's depth
                .forEach(uniAssertion -> {
                    var formula = uniAssertion.getEffectiveTerm(); // The body of the forall
                    var quantifiedVars = uniAssertion.quantifiedVars();

                    // Attempt to match the ground assertion against any sub-expression within the universal formula
                    findSubExpressionMatches(formula, groundAssertion.kif())
                        .forEach(bindings -> {
                            // Check if *all* quantified variables got bound by this specific match
                            if (bindings.keySet().containsAll(quantifiedVars)) {
                                var instantiatedFormula = Unifier.subst(formula, bindings);
                                // Ensure the result is a ground list and not trivial
                                if (instantiatedFormula instanceof KifList instantiatedList
                                    && !instantiatedFormula.containsVariable()
                                    && !CogNote.isTrivial(instantiatedList)) {

                                    var newSupport = Stream.concat(groundAssertion.support().stream(), uniAssertion.support().stream())
                                                           .collect(Collectors.toSet());
                                    newSupport.add(groundAssertion.id());
                                    newSupport.add(uniAssertion.id());

                                    var newPri = calculateDerivedPri(newSupport, (groundAssertion.pri() + uniAssertion.pri()) / 2.0);
                                    var newDepth = Math.max(groundAssertion.derivationDepth(), uniAssertion.derivationDepth()) + 1;

                                    if (newDepth <= MAX_DERIVATION_DEPTH) {
                                        var isNeg = instantiatedList.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent();
                                        var isEq = !isNeg && instantiatedList.getOperator().filter(CogNote.KIF_OPERATOR_EQUAL::equals).isPresent();
                                        var isOriented = isEq && instantiatedList.size() == 3 && instantiatedList.get(1).calculateWeight() > instantiatedList.get(2).calculateWeight();
                                        var derivedType = instantiatedList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;

                                        submitPotentialAssertion(new PotentialAssertion(instantiatedList, newPri, newSupport, uniAssertion.id(), isEq, isNeg, isOriented, findCommonSourceNodeId(newSupport), derivedType, List.of(), newDepth));
                                    }
                                }
                            }
                        });
                });
        }

        // Recursively find matches between a target and sub-expressions of an expression
        private Stream<Map<KifVar, KifTerm>> findSubExpressionMatches(KifTerm expression, KifTerm target) {
             return Stream.concat(
                 // Try matching at the current level
                 Optional.ofNullable(Unifier.match(expression, target, Map.of())).stream(),
                 // If expression is a list, recurse into sub-terms
                 (expression instanceof KifList list) ?
                     list.terms().stream().flatMap(sub -> findSubExpressionMatches(sub, target)) :
                     Stream.empty() // Not a list, no sub-terms to check
             );
        }

        KifList performSkolemization(KifList body, Collection<KifVar> existentialVars, Map<KifVar, KifTerm> contextBindings) {
             Set<KifVar> freeVarsInBody = new HashSet<>(body.getVariables());
             freeVarsInBody.removeAll(existentialVars); // Vars that are free *within the scope* of the 'exists'

             // Determine the terms corresponding to the free variables using the *outer* context bindings
             List<KifTerm> skolemArgs = freeVarsInBody.stream()
                     .map(fv -> Unifier.substFully(contextBindings.getOrDefault(fv, fv), contextBindings)) // Substitute context fully
                     .sorted(Comparator.comparing(KifTerm::toKifString)) // Consistent argument order
                     .toList();

             Map<KifVar, KifTerm> skolemMap = new HashMap<>();
             for (KifVar exVar : existentialVars) {
                 KifTerm skolemTerm;
                 if (skolemArgs.isEmpty()) {
                     // Skolem constant: skc_VarName_UniqueID
                     skolemTerm = KifAtom.of(ID_PREFIX_SKOLEM_CONST + exVar.name().substring(1) + "_" + idCounter.incrementAndGet());
                 } else {
                     // Skolem function: (skf_VarName_UniqueID arg1 arg2 ...)
                     var funcName = KifAtom.of(ID_PREFIX_SKOLEM_FUNC + exVar.name().substring(1) + "_" + idCounter.incrementAndGet());
                     List<KifTerm> funcArgs = new ArrayList<>(skolemArgs.size() + 1);
                     funcArgs.add(funcName);
                     funcArgs.addAll(skolemArgs); // Add the substituted free variables as arguments
                     skolemTerm = new KifList(funcArgs);
                 }
                 skolemMap.put(exVar, skolemTerm);
             }
             // Substitute the existential variables in the body with their Skolem terms
             return (KifList) Unifier.subst(body, skolemMap); // Assume body remains a KifList
        }

        private void generateNewTasks(Assertion newAssertion) {
            // Trigger tasks based on the type of the newly committed assertion
            triggerRuleMatchingTasks(newAssertion);
            triggerRewriteTasks(newAssertion);
            // Forward instantiation for GROUND assertions is triggered directly from commitLoop
        }

        private void triggerRuleMatchingTasks(Assertion newAssertion) {
            // Only GROUND or SKOLEMIZED assertions can trigger rules directly
            if (newAssertion.assertionType() != AssertionType.GROUND && newAssertion.assertionType() != AssertionType.SKOLEMIZED) return;

            rules.forEach(rule -> rule.antecedents().forEach(clause -> {
                var clauseIsNegated = (clause instanceof KifList l && l.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent());
                // Check if negation status matches
                if (clauseIsNegated == newAssertion.isNegated()) {
                    var pattern = clauseIsNegated ? ((KifList) clause).get(1) : clause;
                    // Attempt to unify the clause pattern with the new assertion's effective term
                    Optional.ofNullable(Unifier.unify(pattern, newAssertion.getEffectiveTerm(), Map.of()))
                            .ifPresent(bindings -> taskQueue.put(InferenceTask.matchAntecedent(rule, newAssertion, bindings, calculateTaskPri(rule.pri(), newAssertion.pri()))));
                }
            }));
        }

        private void triggerRewriteTasks(Assertion newAssertion) {
            // Rewrites only apply to/by GROUND or SKOLEMIZED assertions
            if (newAssertion.assertionType() != AssertionType.GROUND && newAssertion.assertionType() != AssertionType.SKOLEMIZED) return;

            // Case 1: New assertion IS an oriented equality rule
            if (newAssertion.isEquality() && newAssertion.isOrientedEquality() && !newAssertion.isNegated() && newAssertion.kif().size() == 3) {
                // Find existing assertions (targets) that might be rewritten by this new rule
                kb.findUnifiableAssertions(newAssertion.kif().get(1)) // Find potential targets matching LHS
                        .filter(target -> !target.id().equals(newAssertion.id())) // Don't rewrite self
                        .filter(target -> Unifier.match(newAssertion.kif().get(1), target.getEffectiveTerm(), Map.of()) != null) // Confirm match
                        .forEach(target -> taskQueue.put(InferenceTask.applyRewrite(newAssertion, target, calculateTaskPri(newAssertion.pri(), target.pri()))));
            }

            // Case 2: New assertion IS a potential target for existing rewrite rules
            // Check against all existing oriented equality rules
            kb.getAllAssertions().stream()
                .filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED)
                .filter(a -> a.isEquality() && a.isOrientedEquality() && !a.isNegated() && a.kif().size() == 3)
                .filter(ruleAssertion -> !ruleAssertion.id().equals(newAssertion.id())) // Don't rewrite self
                .filter(ruleAssertion -> Unifier.match(ruleAssertion.kif().get(1), newAssertion.getEffectiveTerm(), Map.of()) != null) // Check if rule LHS matches new assertion
                .forEach(ruleAssertion -> taskQueue.put(InferenceTask.applyRewrite(ruleAssertion, newAssertion, calculateTaskPri(ruleAssertion.pri(), newAssertion.pri()))));
        }

        private void triggerMatchingForNewRule(Rule newRule) {
            // Match new rule's antecedents against existing GROUND/SKOLEMIZED assertions
            kb.getAllAssertions().stream()
                .filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED)
                .forEach(existing -> newRule.antecedents().forEach(clause -> {
                    var clauseIsNegated = (clause instanceof KifList l && l.getOperator().filter(CogNote.KIF_OPERATOR_NOT::equals).isPresent());
                    if (clauseIsNegated == existing.isNegated()) {
                        var pattern = clauseIsNegated ? ((KifList) clause).get(1) : clause;
                        Optional.ofNullable(Unifier.unify(pattern, existing.getEffectiveTerm(), Map.of()))
                                .ifPresent(bindings -> taskQueue.put(InferenceTask.matchAntecedent(newRule, existing, bindings, calculateTaskPri(newRule.pri(), existing.pri()))));
                    }
                }));
        }

        private double calculateDerivedPri(Set<String> supportIds, double basePri) {
            // Priority is the minimum priority of supporters decayed, or basePri if no supporters
            return supportIds.isEmpty() ? basePri :
                   supportIds.stream()
                           .map(kb::getAssertion)
                           .flatMap(Optional::stream)
                           .mapToDouble(Assertion::pri)
                           .min().orElse(basePri) // Use basePri if supporters not found (shouldn't happen)
                           * DERIVED_PRIORITY_DECAY;
        }

        private int calculateDerivedDepth(Set<String> supportIds) {
             // Depth is max depth of supporters + 1
             return supportIds.stream()
                     .map(kb::getAssertion)
                     .flatMap(Optional::stream)
                     .mapToInt(Assertion::derivationDepth)
                     .max().orElse(-1); // If no supporters, depth is effectively -1 (base facts are 0)
        }

        private double calculateTaskPri(double p1, double p2) {
            // Task priority is average of the participants (e.g., rule and fact)
            return (p1 + p2) / 2.0;
        }

        @Nullable
        private String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;

            String commonId = null;
            boolean firstFound = false;
            Set<String> visited = new HashSet<>(); // Prevent cycles in dependency graph
            Queue<String> toCheck = new LinkedList<>(supportIds);

            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                if (currentId == null || !visited.add(currentId)) continue; // Skip nulls or visited

                var assertionOpt = kb.getAssertion(currentId);
                if (assertionOpt.isPresent()) {
                    var assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (!firstFound) {
                            commonId = assertion.sourceNoteId(); // Found the first note ID
                            firstFound = true;
                        } else if (!commonId.equals(assertion.sourceNoteId())) {
                            return null; // Found a conflicting note ID, no common source
                        }
                        // Don't recurse further if we found a note ID at this level
                    } else if (assertion.derivationDepth() > 0 && !assertion.support().isEmpty()) {
                        // Only recurse up the chain for derived facts without a direct note ID
                        assertion.support().forEach(toCheck::offer);
                    }
                }
            }
            return commonId; // Return the common ID found, or null if none or conflict
        }

        // Basic logical simplification (can be extended)
        private KifList simplifyLogicalTerm(KifList term) {
             final var MAX_SIMPLIFY_DEPTH = 5; // Prevent infinite loops in complex cases
             var current = term;
             for (int depth = 0; depth < MAX_SIMPLIFY_DEPTH; depth++) {
                 var next = simplifyLogicalTermOnce(current);
                 if (next.equals(current)) return current; // No change, simplification complete
                 current = next;
             }
             if (MAX_SIMPLIFY_DEPTH <= 5) System.err.println("Warning: Simplification depth limit reached for: " + term.toKifString());
             return current;
        }

        private KifList simplifyLogicalTermOnce(KifList term) {
             var opOpt = term.getOperator();
             if (opOpt.isPresent()) {
                 var op = opOpt.get();
                 // Rule: (not (not X)) -> X
                 if (op.equals(KIF_OPERATOR_NOT) && term.size() == 2 && term.get(1) instanceof KifList negList) {
                      if (negList.getOperator().filter(KIF_OPERATOR_NOT::equals).isPresent() && negList.size() == 2) {
                           if (negList.get(1) instanceof KifList innerList) return simplifyLogicalTermOnce(innerList); // Recurse on inner list
                      }
                 }
                 // TODO: Add more rules like De Morgan's, identity, absorption if needed
             }

             // Recursively simplify sub-terms
             var changed = new boolean[]{false}; // Use array to modify in lambda
             List<KifTerm> newTerms = term.terms().stream().map(subTerm -> {
                  var simplifiedSub = (subTerm instanceof KifList sl) ? simplifyLogicalTermOnce(sl) : subTerm;
                  if (simplifiedSub != subTerm) changed[0] = true;
                  return simplifiedSub;
             }).toList(); // Use toList() for immutability

             return changed[0] ? new KifList(newTerms) : term; // Return new list only if changed
        }

        private void checkQueueThresholds() {
            var taskQSize = taskQueue.size();
            var commitQSize = commitQueue.size(); // Check commit queue too
            if (taskQSize >= QUEUE_SIZE_THRESHOLD_HALT || commitQSize >= COMMIT_QUEUE_CAPACITY * 9 / 10) { // Use relative threshold for commit queue
                System.err.printf("Queue CRITICAL: Tasks %d/%d, Commits %d/%d%n", taskQSize, TASK_QUEUE_CAPACITY, commitQSize, COMMIT_QUEUE_CAPACITY);
            } else if (taskQSize >= QUEUE_SIZE_THRESHOLD_WARN || commitQSize >= COMMIT_QUEUE_CAPACITY / 2) {
                System.out.printf("Queue WARNING: Tasks %d/%d, Commits %d/%d%n", taskQSize, TASK_QUEUE_CAPACITY, commitQSize, COMMIT_QUEUE_CAPACITY);
            }
        }

        // Helper record for rule matching results
        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {}
    }

    static class KifParser {
        private final Reader reader;
        private int currentChar = -2; // Signal initial read needed
        private int line = 1;
        private int col = 0;

        private KifParser(Reader reader) { this.reader = reader; }

        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var stringReader = new StringReader(input.trim())) {
                return new KifParser(stringReader).parseTopLevel();
            } catch (IOException e) { // Should not happen with StringReader
                throw new ParseException("Internal Read error: " + e.getMessage());
            }
        }

        private List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>();
            consumeWhitespaceAndComments();
            while (peek() != -1) { // While not EOF
                terms.add(parseTerm());
                consumeWhitespaceAndComments();
            }
            return Collections.unmodifiableList(terms);
        }

        private KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments(); // Ensure leading whitespace/comments are skipped
            return switch (peek()) {
                case -1 -> throw createParseException("Unexpected EOF");
                case '(' -> parseList();
                case '"' -> parseQuotedString();
                case '?' -> parseVariable();
                default -> parseAtom(); // Assume atom if not other starting char
            };
        }

        private KifList parseList() throws IOException, ParseException {
            consumeChar('(');
            List<KifTerm> terms = new ArrayList<>();
            while (true) {
                consumeWhitespaceAndComments();
                var next = peek();
                if (next == ')') { consumeChar(')'); return new KifList(terms); }
                if (next == -1) throw createParseException("Unmatched parenthesis");
                terms.add(parseTerm()); // Recursively parse terms within the list
            }
        }

        private KifVar parseVariable() throws IOException, ParseException {
            consumeChar('?');
            var sb = new StringBuilder("?");
            if (!isValidAtomChar(peek())) throw createParseException("Variable name character expected after '?'");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            if (sb.length() < 2) throw createParseException("Empty variable name after '?'");
            return KifVar.of(sb.toString());
        }

        private KifAtom parseAtom() throws IOException, ParseException {
            var sb = new StringBuilder();
            if (!isValidAtomChar(peek())) throw createParseException("Invalid character at start of atom");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            // No need to check for empty atom here, as initial check ensures at least one valid char
            return KifAtom.of(sb.toString());
        }

        // Defines characters allowed in unquoted atoms and variable names (after '?')
        private boolean isValidAtomChar(int c) {
            return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';';
        }

        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                var c = consumeChar();
                if (c == '"') return KifAtom.of(sb.toString()); // End of string
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') { // Handle escape sequences
                    var next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape character");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\', '"' -> next; // Escaped backslash or quote
                        default -> {
                            // System.err.printf("Warning: Unknown escape '\\%c' at line %d, col %d%n", (char) next, line, col - 1);
                            yield next; // Treat unknown escapes literally
                        }
                    });
                } else {
                    sb.append((char) c); // Regular character
                }
            }
        }

        private int peek() throws IOException {
            if (currentChar == -2) currentChar = reader.read(); // Read ahead if needed
            return currentChar;
        }

        private int consumeChar() throws IOException {
            var c = peek(); // Get potentially pre-read char
            if (c != -1) {
                currentChar = -2; // Signal next peek needs to read
                if (c == '\n') { line++; col = 0; }
                else { col++; }
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
                 if (c == -1) break; // EOF
                 if (Character.isWhitespace(c)) {
                     consumeChar(); // Consume whitespace
                 } else if (c == ';') { // Comment start
                     consumeChar(); // Consume ';'
                     while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar(); // Consume until newline or EOF
                 } else {
                     break; // Non-whitespace, non-comment character found
                 }
             }
        }

        private ParseException createParseException(String message) {
            return new ParseException(message + " at line " + line + " col " + col);
        }
    }

    static class ParseException extends Exception { ParseException(String message) { super(message); } }

    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 50; // Prevent infinite substitution loops

        @Nullable static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) { return unifyRecursive(x, y, bindings, 0); }
        @Nullable static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) { return matchRecursive(pattern, term, bindings, 0); }
        static KifTerm subst(KifTerm term, Map<KifVar, KifTerm> bindings) { return substRecursive(term, bindings, 0); }
        static KifTerm substFully(KifTerm term, Map<KifVar, KifTerm> bindings) { return substRecursive(term, bindings, 0); } // Alias for clarity, underlying logic handles full substitution
        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) { return rewriteRecursive(target, lhsPattern, rhsTemplate, 0); }


        @Nullable
        private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null; // Check depth limit
            var xSubst = substRecursive(x, bindings, depth + 1); // Substitute using current bindings
            var ySubst = substRecursive(y, bindings, depth + 1);

            if (xSubst.equals(ySubst)) return bindings; // Already unified
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true, depth); // Bind xVar = y
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true, depth); // Bind yVar = x

            // If both are lists of the same size, unify elements
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lx.size(); i++) {
                    currentBindings = unifyRecursive(lx.get(i), ly.get(i), currentBindings, depth + 1);
                    if (currentBindings == null) return null; // Unification failed for an element
                }
                return currentBindings; // Successful list unification
            }
            return null; // Mismatch (e.g., atom vs list, lists of different sizes)
        }

        @Nullable
        private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
             if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
             var patternSubst = substRecursive(pattern, bindings, depth + 1); // Substitute pattern variables first

             if (patternSubst instanceof KifVar varP) {
                  return bindVariable(varP, term, bindings, false, depth); // Bind pattern var to the term (no occurs check needed for match)
             }
             if (patternSubst.equals(term)) {
                  return bindings; // Terms match exactly after substitution
             }
             // If pattern is a list and term is a list of the same size, match elements
             if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                  var currentBindings = bindings;
                  for (var i = 0; i < lp.size(); i++) {
                      currentBindings = matchRecursive(lp.get(i), lt.get(i), currentBindings, depth + 1);
                      if (currentBindings == null) return null; // Element match failed
                  }
                  return currentBindings; // Successful list match
             }
             return null; // Mismatch
        }

        private static KifTerm substRecursive(KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings.isEmpty() || depth > MAX_SUBST_DEPTH || !term.containsVariable()) {
                return term; // Base case: no bindings, depth limit, or no variables
            }

            return switch (term) {
                case KifAtom atom -> atom; // Atoms are constants
                case KifVar var -> {
                     KifTerm boundValue = bindings.get(var);
                     yield (boundValue != null) ? substRecursive(boundValue, bindings, depth + 1) : var; // Substitute if bound, else keep var
                 }
                case KifList list -> {
                    // Recursively substitute in sub-terms
                    List<KifTerm> newTerms = list.terms().stream()
                        .map(sub -> substRecursive(sub, bindings, depth + 1))
                        .toList();
                    // Return new list only if any sub-term actually changed
                    yield newTerms.equals(list.terms()) ? list : new KifList(newTerms);
                }
            };
        }

        @Nullable
        private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean doOccursCheck, int depth) {
             if (var.equals(value)) return bindings; // Trivial binding x = x

             // Check if var is already bound
             if (bindings.containsKey(var)) {
                 // If already bound, unify the existing binding with the new value
                 return doOccursCheck ? unifyRecursive(bindings.get(var), value, bindings, depth + 1)
                                      : matchRecursive(bindings.get(var), value, bindings, depth + 1); // Use match for match context
             }

             // Substitute value completely before occurs check/binding
             var finalValue = substRecursive(value, bindings, depth + 1);

             // Occurs check (only for unification)
             if (doOccursCheck && occursCheckRecursive(var, finalValue, bindings, depth + 1)) {
                 return null; // Occurs check failed
             }

             // Create new binding map
             Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
             newBindings.put(var, finalValue); // Add the new binding
             return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheckRecursive(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (depth > MAX_SUBST_DEPTH) return true; // Assume occurs check fails if depth exceeded
            var substTerm = substRecursive(term, bindings, depth + 1); // Use substRecursive to handle transitive bindings

            return switch (substTerm) {
                case KifVar v -> var.equals(v); // Check if variable itself is found
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheckRecursive(var, t, bindings, depth + 1)); // Check sub-terms
                case KifAtom ignored -> false; // Atom cannot contain the variable
            };
        }

        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate, int depth) {
             if (depth > MAX_SUBST_DEPTH) return Optional.empty(); // Depth limit

             // 1. Try to match the pattern at the current level
             return Optional.ofNullable(matchRecursive(lhsPattern, target, Map.of(), depth + 1))
                 .map(bindings -> substRecursive(rhsTemplate, bindings, depth + 1)) // If match, substitute RHS
                 .or(() -> // 2. If no match here, try rewriting subterms recursively
                     (target instanceof KifList targetList) ?
                         rewriteSubterms(targetList, lhsPattern, rhsTemplate, depth + 1) :
                         Optional.empty() // Not a list, cannot rewrite subterms
                 );
        }

        private static Optional<KifTerm> rewriteSubterms(KifList targetList, KifTerm lhsPattern, KifTerm rhsTemplate, int depth) {
            boolean changed = false;
            List<KifTerm> newSubTerms = new ArrayList<>(targetList.size());
            for (KifTerm subTerm : targetList.terms()) {
                Optional<KifTerm> rewrittenSub = rewriteRecursive(subTerm, lhsPattern, rhsTemplate, depth);
                if (rewrittenSub.isPresent()) {
                    changed = true;
                    newSubTerms.add(rewrittenSub.get());
                } else {
                    newSubTerms.add(subTerm); // Keep original if not rewritten
                }
            }
            // Return new list only if at least one subterm changed
            return changed ? Optional.of(new KifList(newSubTerms)) : Optional.empty();
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
        private final JMenuItem showSupportItem = new JMenuItem("Show Support"); // TODO: Future feature
        private final boolean showInputCallbacksInUI; // Config based on reasoner setting
        private CogNote reasoner;
        private Note currentNote = null; // The currently selected note

        public SwingUI(@Nullable CogNote reasoner) {
            super("Cognote - Enhanced Reasoner");
            this.reasoner = reasoner;
            this.showInputCallbacksInUI = Optional.ofNullable(reasoner).map(r -> r.broadcastInputAssertions).orElse(false);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Use window listener for shutdown
            setSize(1200, 800);
            setLocationRelativeTo(null); // Center on screen
            setupFonts();
            setupComponents();
            setupLayout();
            setupActionListeners();
            setupWindowListener();
            updateUIForSelection(); // Initial UI state
        }

        void setReasoner(CogNote reasoner) {
            this.reasoner = reasoner;
            updateUIForSelection(); // Update UI elements that depend on the reasoner
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
            assertionDisplayList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            assertionDisplayList.setCellRenderer(new AssertionListCellRenderer()); // Custom renderer for assertions/LLM items

            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
            statusLabel.setHorizontalAlignment(SwingConstants.LEFT);

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
            showSupportItem.setEnabled(false); // Disabled until implemented
        }

        private void setupLayout() {
            var leftPane = new JScrollPane(noteList);
            leftPane.setPreferredSize(new Dimension(250, 0)); // Give note list reasonable width
            var editorPane = new JScrollPane(noteEditor);
            var assertionListPane = new JScrollPane(assertionDisplayList);
            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPane, assertionListPane);
            rightSplit.setResizeWeight(0.6); // Give editor more initial space
            var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightSplit);
            mainSplit.setResizeWeight(0.25); // Adjust horizontal split

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
            summarizeItem.addActionListener(this::summarizeNoteAction);
            keyConceptsItem.addActionListener(this::keyConceptsAction);
            generateQuestionsItem.addActionListener(this::generateQuestionsAction);
            renameItem.addActionListener(this::renameNoteAction);
            removeItem.addActionListener(this::removeNoteAction);

            retractAssertionItem.addActionListener(this::retractSelectedAssertionAction);

            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    saveCurrentNoteText(); // Save text of previously selected note
                    currentNote = noteList.getSelectedValue(); // Get newly selected note
                    updateUIForSelection(); // Update editor, assertion list, title, etc.
                }
            });
            noteEditor.addFocusListener(new FocusAdapter() {
                 @Override public void focusLost(FocusEvent evt) { saveCurrentNoteText(); } // Save on losing focus
            });

            // Mouse listener for note list context menu
            noteList.addMouseListener(createContextMenuMouseListener(noteList, noteContextMenu));
            // Mouse listener for assertion list context menu
            assertionDisplayList.addMouseListener(createContextMenuMouseListener(assertionDisplayList, assertionContextMenu, this::updateAssertionContextMenuState));
        }

        private MouseAdapter createContextMenuMouseListener(JComponent component, JPopupMenu popup, Runnable... preShowActions) {
             return new MouseAdapter() {
                 @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
                 @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
                 private void maybeShowPopup(MouseEvent e) {
                     if (!e.isPopupTrigger()) return;
                     int index = -1;
                     if (component instanceof JList<?> list) {
                          index = list.locationToIndex(e.getPoint());
                          if (index != -1) list.setSelectedIndex(index); // Select item under cursor
                     }
                     if (index != -1 || !(component instanceof JList<?>)) { // Show if index valid or not a JList
                         for (Runnable action : preShowActions) action.run(); // Run pre-show hooks
                         popup.show(e.getComponent(), e.getX(), e.getY());
                     }
                 }
             };
         }

         // Update assertion context menu based on selected item
         private void updateAssertionContextMenuState() {
              var selectedVM = assertionDisplayList.getSelectedValue();
              boolean enabled = selectedVM != null && selectedVM.isActualAssertion() && selectedVM.status() == AssertionStatus.ACTIVE;
              retractAssertionItem.setEnabled(enabled);
              showSupportItem.setEnabled(selectedVM != null && selectedVM.isActualAssertion()); // Enable based on selection, functionality still TODO
         }

        private void setupWindowListener() {
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    saveCurrentNoteText(); // Save any pending edits
                    Optional.ofNullable(SwingUI.this.reasoner).ifPresent(CogNote::stopReasoner); // Gracefully stop reasoner
                    dispose(); // Close the UI window
                    System.exit(0); // Terminate the application
                }
            });
        }

        // --- UI Update & State Management ---

        private void saveCurrentNoteText() {
             // Save text only if a note was selected and the editor was enabled
             Optional.ofNullable(currentNote)
                     .filter(_ -> noteEditor.isEnabled())
                     .ifPresent(n -> n.text = noteEditor.getText());
        }

        private void updateUIForSelection() {
            var noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected);
            Optional.ofNullable(reasoner).ifPresent(r -> pauseResumeButton.setText(r.isPaused() ? "Resume" : "Pause"));

            if (noteSelected) {
                noteEditor.setText(currentNote.text);
                noteEditor.setCaretPosition(0); // Scroll to top
                // Ensure the model exists and set it for the assertion list
                assertionDisplayList.setModel(noteAssertionModels.computeIfAbsent(currentNote.id, id -> new DefaultListModel<>()));
                setTitle("Cognote - " + currentNote.title);
                SwingUtilities.invokeLater(noteEditor::requestFocusInWindow); // Request focus after update
            } else {
                noteEditor.setText(""); // Clear editor
                assertionDisplayList.setModel(new DefaultListModel<>()); // Use an empty model
                setTitle("Cognote - Enhanced Reasoner");
            }
            setControlsEnabled(true); // Re-enable controls based on new state
        }

        // --- Note Actions ---

        private void addNote(ActionEvent e) {
            Optional.ofNullable(JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE))
                    .map(String::trim).filter(Predicate.not(String::isEmpty))
                    .ifPresent(title -> {
                        var newNote = new Note(generateId(ID_PREFIX_NOTE), title, "");
                        noteListModel.addElement(newNote);
                        noteAssertionModels.put(newNote.id, new DefaultListModel<>()); // Ensure model exists
                        noteList.setSelectedValue(newNote, true); // Select the new note
                    });
        }

        private void removeNoteAction(ActionEvent e) {
            performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions (including derived)?", JOptionPane.WARNING_MESSAGE, noteToRemove -> {
                 reasoner.processRetractionByNoteIdInput(noteToRemove.id, "UI-Remove");
                 noteAssertionModels.remove(noteToRemove.id); // Remove UI model too
                 var idx = noteList.getSelectedIndex();
                 noteListModel.removeElement(noteToRemove);
                 if (!noteListModel.isEmpty()) // Select adjacent item if possible
                     noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
                 else {
                     currentNote = null; // No notes left
                     updateUIForSelection(); // Update UI to reflect empty state
                 }
            });
        }

        private void renameNoteAction(ActionEvent e) {
             Optional.ofNullable(currentNote)
                     .flatMap(note -> Optional.ofNullable(JOptionPane.showInputDialog(this, "Enter new title for '" + note.title + "':", "Rename Note", JOptionPane.PLAIN_MESSAGE, null, null, note.title))
                                          .map(Object::toString).map(String::trim).filter(Predicate.not(String::isEmpty))
                                          .filter(newTitle -> !newTitle.equals(note.title)) // Only if title changed
                                          .map(newTitle -> { note.title = newTitle; return note; })
                     )
                     .ifPresent(updatedNote -> {
                         noteListModel.setElementAt(updatedNote, noteList.getSelectedIndex()); // Update model
                         setTitle("Cognote - " + updatedNote.title); // Update window title
                         statusLabel.setText("Status: Renamed note to '" + updatedNote.title + "'.");
                     });
        }

        // --- LLM Action Handlers ---

        private void enhanceNoteAction(ActionEvent e) {
            performNoteActionAsync("Enhancing", note -> {
                addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Enhancing Note via LLM...");
                return reasoner.enhanceNoteWithLlmAsync(note.text, note.id);
            }, this::processLlmEnhancementResponse, this::handleLlmFailure);
        }

        private void analyzeNoteAction(ActionEvent e) {
            performNoteActionAsync("Analyzing", note -> {
                clearNoteAssertionList(note.id); // Clear previous analysis results visually
                addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Analyzing Note via LLM -> KIF...");
                reasoner.processRetractionByNoteIdInput(note.id, "UI-Analyze-Retract"); // Retract old assertions from KB
                return reasoner.text2kifAsync(note.text, note.id);
            }, this::processLlmKifResponse, this::handleLlmFailure);
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
                 questions.lines()
                     .map(q -> q.replaceFirst("^\\s*-\\s*", "").trim()) // Clean up list markers
                     .filter(Predicate.not(String::isBlank))
                     .forEach(q -> addLlmUiItem(note.id, AssertionDisplayType.LLM_QUESTION, q));
             }, this::handleLlmFailure);
         }

        // --- Assertion Action Handlers ---

        private void retractSelectedAssertionAction(ActionEvent e) {
            Optional.ofNullable(assertionDisplayList.getSelectedValue())
                .filter(AssertionViewModel::isActualAssertion) // Ensure it's a real assertion
                .filter(vm -> vm.status() == AssertionStatus.ACTIVE) // Ensure it's active
                .map(AssertionViewModel::id)
                .ifPresent(id -> {
                    System.out.println("UI Requesting retraction for: " + id);
                    reasoner.processRetractionByIdInput(id, "UI-Retract");
                    // UI update will happen via callback
                });
        }

        // --- Action Helpers ---

        private void performNoteAction(String actionName, String confirmTitle, String confirmMsgFormat, int confirmMsgType, NoteAction action) {
            Optional.ofNullable(currentNote).filter(_ -> reasoner != null).ifPresent(note -> {
                 if (JOptionPane.showConfirmDialog(this, String.format(confirmMsgFormat, note.title), confirmTitle, JOptionPane.YES_NO_OPTION, confirmMsgType) == JOptionPane.YES_OPTION) {
                     statusLabel.setText(String.format("Status: %s '%s'...", actionName, note.title));
                     setControlsEnabled(false);
                     try {
                         action.execute(note); // Execute synchronous action
                         statusLabel.setText(String.format("Status: Finished %s '%s'.", actionName, note.title));
                     } catch (Exception ex) {
                         statusLabel.setText(String.format("Status: Error %s '%s'.", actionName, note.title));
                         System.err.println("Error during " + actionName + ": " + ex.getMessage());
                         ex.printStackTrace();
                         JOptionPane.showMessageDialog(this, "Error during " + actionName + ":\n" + ex.getMessage(), "Action Error", JOptionPane.ERROR_MESSAGE);
                     } finally {
                         setControlsEnabled(true); // Re-enable controls
                         Optional.ofNullable(reasoner).ifPresent(CogNote::updateUIStatus); // Update general status bar
                     }
                 }
            });
        }

        // Helper for asynchronous LLM actions
        private <T> void performNoteActionAsync(String actionName, NoteAsyncAction<T> asyncAction, BiConsumer<T, Note> successCallback, BiConsumer<Throwable, Note> failureCallback) {
            Optional.ofNullable(currentNote).filter(_ -> reasoner != null).ifPresent(noteForAction -> {
                 reasoner.reasonerStatus = actionName + " Note: " + noteForAction.title; // Update reasoner status
                 reasoner.updateUIStatus();
                 setControlsEnabled(false); // Disable controls during async operation
                 saveCurrentNoteText(); // Save current text before potential modification

                 try {
                     asyncAction.execute(noteForAction)
                             .thenAcceptAsync(result -> { // Success path (on EDT)
                                 // Check if the note is still the selected one before updating UI
                                 if (noteForAction.equals(currentNote)) {
                                     successCallback.accept(result, noteForAction);
                                 }
                             }, SwingUtilities::invokeLater)
                             .exceptionallyAsync(ex -> { // Failure path (on EDT)
                                 if (noteForAction.equals(currentNote)) {
                                     failureCallback.accept(ex, noteForAction);
                                 }
                                 return null; // Needed for exceptionally
                             }, SwingUtilities::invokeLater)
                             .thenRunAsync(() -> { // Always run path (on EDT)
                                 // Re-enable controls only if the action was for the currently selected note
                                 if (noteForAction.equals(currentNote)) {
                                     setControlsEnabled(true);
                                 }
                                 // Update status regardless
                                 Optional.ofNullable(reasoner).ifPresent(CogNote::updateUIStatus);
                             }, SwingUtilities::invokeLater);
                 } catch (Exception e) { // Catch synchronous errors during setup
                     failureCallback.accept(e, noteForAction);
                     setControlsEnabled(true);
                     Optional.ofNullable(reasoner).ifPresent(CogNote::updateUIStatus);
                 }
            });
        }

        private void processLlmEnhancementResponse(String enhancedText, Note enhancedNote) {
             removeLlmPlaceholders(enhancedNote.id); // Remove placeholder first
             if (enhancedText != null && !enhancedText.isBlank()) {
                 enhancedNote.text = enhancedText.trim();
                 noteEditor.setText(enhancedNote.text); // Update editor
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
            removeLlmPlaceholders(analyzedNote.id); // Remove placeholder first
            try {
                var terms = KifParser.parseKif(kifString);
                var counts = new int[]{0, 0}; // [queued, skipped]
                addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_INFO, "--- Start KIF Analysis ---");
                for (var term : terms) {
                    if (term instanceof KifList list && !list.terms().isEmpty()) {
                        addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_QUEUED_KIF, term.toKifString());
                        Optional.ofNullable(reasoner).ifPresent(r -> r.queueExpressionFromSource(term, "UI-LLM", analyzedNote.id));
                        counts[0]++;
                    } else if (term != null) {
                        addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_SKIPPED, "Skipped (non-list/empty): " + term.toKifString());
                        counts[1]++;
                    }
                }
                Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = String.format("Analyzed '%s': %d queued, %d skipped", analyzedNote.title, counts[0], counts[1]));
                addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_INFO, String.format("--- End KIF Analysis (%d queued, %d skipped) ---", counts[0], counts[1]));
                if (terms.isEmpty() && !kifString.isBlank()) {
                     addLlmUiItem(analyzedNote.id, AssertionDisplayType.LLM_ERROR, "Analysis failed: No valid KIF found in LLM response.");
                     JOptionPane.showMessageDialog(this, "LLM response did not contain valid KIF assertions.", "Analysis Failed", JOptionPane.WARNING_MESSAGE);
                 }
            } catch (Exception ex) {
                handleLlmFailure(ex, analyzedNote); // Handle parsing errors etc.
            }
        }

        private void handleLlmFailure(Throwable ex, Note contextNote) {
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            var action = (ex instanceof ParseException) ? "KIF Parse Error" : "LLM Interaction Failed";
            Optional.ofNullable(reasoner).ifPresent(r -> r.reasonerStatus = action + " for " + contextNote.title);
            System.err.println(action + " for note '" + contextNote.title + "': " + cause.getMessage());
            cause.printStackTrace();
            removeLlmPlaceholders(contextNote.id); // Remove placeholders on failure
            addLlmUiItem(contextNote.id, AssertionDisplayType.LLM_ERROR, action + ": " + cause.getMessage());
            JOptionPane.showMessageDialog(this, action + ":\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
        }

        private void togglePause(ActionEvent e) {
             Optional.ofNullable(reasoner).ifPresent(r -> {
                 var pausing = !r.isPaused();
                 r.setPaused(pausing); // This updates button text via callback/updateUIStatus
             });
        }

        private void clearAll(ActionEvent e) {
            Optional.ofNullable(reasoner).ifPresent(r -> {
                if (JOptionPane.showConfirmDialog(this, "Clear all notes, assertions, and rules? This cannot be undone.", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                    noteListModel.clear(); // Clear UI note list
                    currentNote = null; // Deselect note
                    r.clearAllKnowledge(); // Clears KB, Reasoner, UI assertion lists via callback
                    updateUIForSelection(); // Refresh UI to empty state
                }
            });
        }

        // --- Reasoner Callback Handling (UI Updates) ---

        // Called by CogNote.callback -> invokeAssertion/LlmCallbacks, always on EDT
        public void handleReasonerCallback(String type, Object payload) {
             switch (payload) {
                 case Assertion assertion -> handleAssertionCallback(type, assertion);
                 case AssertionViewModel llmItem when type.equals(CALLBACK_LLM_RESPONSE) -> handleLlmCallback(llmItem);
                 default -> {} // Ignore other types
             }
         }

         private void handleAssertionCallback(String type, Assertion assertion) {
             var noteId = assertion.sourceNoteId();
             var assertionId = assertion.id();

             if (CALLBACK_ASSERT_INPUT.equals(type) && (!showInputCallbacksInUI || noteId == null)) {
                  return; // Don't show input unless configured AND associated with a note
             }

             // Update the model for the specific note if it's the source
             if (noteId != null) {
                 Optional.ofNullable(noteAssertionModels.get(noteId))
                         .ifPresent(model -> updateOrAddModelItem(model, AssertionViewModel.fromAssertion(assertion, type)));
             }

             // Handle retractions/evictions globally across all potentially displayed models
             // because dependencies might cross notes (though sourceNoteId tracking helps)
             if (CALLBACK_ASSERT_RETRACTED.equals(type) || CALLBACK_EVICT.equals(type)) {
                 var newStatus = CALLBACK_ASSERT_RETRACTED.equals(type) ? AssertionStatus.RETRACTED : AssertionStatus.EVICTED;
                 updateAssertionStatusInAllModels(assertionId, newStatus);
             }
         }

        private void handleLlmCallback(AssertionViewModel llmItem) {
            // LLM items are typically added directly by the UI action handlers.
            // This callback ensures they are present in the correct note's model if added asynchronously,
            // or could be used to update placeholders if that pattern was used.
            Optional.ofNullable(noteAssertionModels.get(llmItem.noteId()))
                 .ifPresent(model -> updateOrAddModelItem(model, llmItem)); // Add if not already present
        }

         // --- UI List Model Management ---

         private void updateOrAddModelItem(DefaultListModel<AssertionViewModel> model, AssertionViewModel newItem) {
             var existingIndex = findViewModelIndexById(model, newItem.id());

             if (existingIndex != -1) { // Item exists, update it
                 var existingItem = model.getElementAt(existingIndex);
                 // Update only if status changed or it's replacing a placeholder KIF
                 if (newItem.status() != existingItem.status() || existingItem.displayType() == AssertionDisplayType.LLM_QUEUED_KIF) {
                     model.setElementAt(newItem, existingIndex);
                 }
             } else { // Item doesn't exist
                 // Only add if it's ACTIVE or an LLM item (retracted/evicted items shouldn't be added if not already present)
                 if (newItem.status() == AssertionStatus.ACTIVE || !newItem.isActualAssertion()) {
                     // If adding a real assertion, remove any corresponding QUEUED_KIF placeholder first
                     if (newItem.isActualAssertion()) {
                         removeLlmQueuedPlaceholder(model, newItem.content());
                     }
                     model.addElement(newItem); // Add the new item
                     // Scroll to the newly added item if it's in the currently viewed list
                     if (currentNote != null && newItem.noteId() != null && newItem.noteId().equals(currentNote.id)) {
                         assertionDisplayList.ensureIndexIsVisible(model.getSize() - 1);
                     }
                 }
             }
         }

         private void updateAssertionStatusInAllModels(String assertionId, AssertionStatus newStatus) {
             // Check every note's model, as dependencies might cause an assertion to appear
             // visually related to multiple notes if support chains cross note boundaries.
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
             // This method adds directly to the UI model, intended to be called from EDT
             var model = noteAssertionModels.computeIfAbsent(noteId, id -> new DefaultListModel<>());
             var vm = new AssertionViewModel(
                 generateId(ID_PREFIX_LLM_ITEM + type.name().toLowerCase()),
                 noteId, content, type, AssertionStatus.ACTIVE, // LLM items are always 'active' visually
                 0.0, 0, System.currentTimeMillis()
             );
             // Add the item and notify CogNote (for potential WS broadcast or logging)
             updateOrAddModelItem(model, vm);
             Optional.ofNullable(reasoner).ifPresent(r -> r.callback(CALLBACK_LLM_RESPONSE, vm));
         }

         private void removeLlmPlaceholders(String noteId) {
             Optional.ofNullable(noteAssertionModels.get(noteId)).ifPresent(model -> {
                 for (int i = model.size() - 1; i >= 0; i--) { // Iterate backwards when removing
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
                 if (model.getElementAt(i).id().equals(id)) return i;
             }
             return -1; // Not found
         }

         // Called by clearAllKnowledge via callback on EDT
         public void clearAllNoteAssertionLists() {
             noteAssertionModels.values().forEach(DefaultListModel::clear); // Clear all models
             assertionDisplayList.setModel(new DefaultListModel<>()); // Reset active list view
             // noteAssertionModels map itself is cleared in CogNote.clearAllKnowledge
         }

         // Method to clear list for a specific note (e.g., before re-analyzing)
         private void clearNoteAssertionList(String noteId) {
              Optional.ofNullable(noteAssertionModels.get(noteId)).ifPresent(DefaultListModel::clear);
          }

        private void setControlsEnabled(boolean enabled) {
            var noteSelected = (currentNote != null);
            var reasonerReady = (reasoner != null && reasoner.running);
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
            // Update pause button text based on actual reasoner state if enabled
            if (enabled && reasonerReady) {
                 pauseResumeButton.setText(reasoner.isPaused() ? "Resume" : "Pause");
            }
        }

        @FunctionalInterface interface NoteAction { void execute(Note note); }
        @FunctionalInterface interface NoteAsyncAction<T> { CompletableFuture<T> execute(Note note); }

        // --- Inner Classes for UI Data and Rendering ---

        static class Note {
            final String id; String title; String text;
            Note(String id, String title, String text) { this.id = requireNonNull(id); this.title = requireNonNull(title); this.text = requireNonNull(text); }
            @Override public String toString() { return title; } // Used by JList default renderer
            @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); }
            @Override public int hashCode() { return id.hashCode(); }
        }

        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                var label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setBorder(new EmptyBorder(5, 10, 5, 10)); // Add padding
                label.setFont(UI_DEFAULT_FONT);
                return label;
            }
        }

        enum AssertionStatus { ACTIVE, RETRACTED, EVICTED }
        enum AssertionDisplayType {
            INPUT, ADDED, UNIVERSAL, SKOLEMIZED, // From Assertions
            LLM_QUEUED_KIF, LLM_SKIPPED, LLM_SUMMARY, LLM_CONCEPTS, LLM_QUESTION, LLM_ERROR, LLM_INFO, LLM_PLACEHOLDER // From LLM/UI
        }

        record AssertionViewModel(String id, @Nullable String noteId, String content, AssertionDisplayType displayType, AssertionStatus status,
                                  double priority, int depth, long timestamp) {

             static AssertionViewModel fromAssertion(Assertion assertion, String callbackType) {
                 AssertionDisplayType type = switch (assertion.assertionType()) {
                     case GROUND -> (callbackType.equals(CALLBACK_ASSERT_INPUT)) ? AssertionDisplayType.INPUT : AssertionDisplayType.ADDED;
                     case UNIVERSAL -> AssertionDisplayType.UNIVERSAL;
                     case SKOLEMIZED -> AssertionDisplayType.SKOLEMIZED;
                 };
                 AssertionStatus stat = switch (callbackType) {
                     case CALLBACK_ASSERT_RETRACTED -> AssertionStatus.RETRACTED;
                     case CALLBACK_EVICT -> AssertionStatus.EVICTED;
                     default -> AssertionStatus.ACTIVE; // Includes INPUT, ADDED
                 };
                 return new AssertionViewModel(assertion.id(), assertion.sourceNoteId(), assertion.toKifString(), type, stat,
                                               assertion.pri(), assertion.derivationDepth(), assertion.timestamp());
             }

             public AssertionViewModel withStatus(AssertionStatus newStatus) {
                  return new AssertionViewModel(id, noteId, content, displayType, newStatus, priority, depth, timestamp);
             }

             public boolean isActualAssertion() {
                  return switch(displayType) {
                      case INPUT, ADDED, UNIVERSAL, SKOLEMIZED -> true;
                      default -> false; // LLM types are not KB assertions
                  };
             }
         }

        static class AssertionListCellRenderer extends JPanel implements ListCellRenderer<AssertionViewModel> {
            private final JLabel iconLabel = new JLabel();
            private final JLabel contentLabel = new JLabel();
            private final JLabel detailLabel = new JLabel();
            private final Border activeBorder = new CompoundBorder(new LineBorder(Color.LIGHT_GRAY, 1), new EmptyBorder(3, 5, 3, 5));
            private final Border inactiveBorder = new CompoundBorder(new LineBorder(new Color(240,240,240), 1), new EmptyBorder(3, 5, 3, 5));
            private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
            private final JPanel textPanel;

            AssertionListCellRenderer() {
                setLayout(new BorderLayout(5, 0));
                textPanel = new JPanel(new BorderLayout());
                textPanel.setOpaque(false); // Inherit background color
                textPanel.add(contentLabel, BorderLayout.CENTER);
                textPanel.add(detailLabel, BorderLayout.SOUTH);
                add(iconLabel, BorderLayout.WEST);
                add(textPanel, BorderLayout.CENTER);
                setOpaque(true); // Required for background colors to work
                contentLabel.setFont(MONOSPACED_FONT);
                detailLabel.setFont(UI_SMALL_FONT);
                iconLabel.setFont(UI_DEFAULT_FONT.deriveFont(Font.BOLD));
                iconLabel.setBorder(new EmptyBorder(0, 4, 0, 4)); // Icon padding
                iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            }

            @Override
            public Component getListCellRendererComponent(JList<? extends AssertionViewModel> list, AssertionViewModel value, int index, boolean isSelected, boolean cellHasFocus) {
                contentLabel.setText(value.content());
                contentLabel.setFont(MONOSPACED_FONT); // Ensure font is reset

                String details;
                String iconText = "?";
                Color iconColor = Color.BLACK;
                Color bgColor = Color.WHITE;
                Color fgColor = Color.BLACK;

                // Configure based on type
                switch (value.displayType()) {
                     case INPUT -> { iconText = "I"; iconColor = new Color(0, 128, 0); bgColor = new Color(235, 255, 235); }
                     case ADDED -> { iconText = "A"; iconColor = Color.BLACK; }
                     case UNIVERSAL -> { iconText = "∀"; iconColor = new Color(0, 0, 128); bgColor = new Color(235, 235, 255); }
                     case SKOLEMIZED -> { iconText = "∃"; iconColor = new Color(139, 69, 19); bgColor = new Color(255, 255, 230); } // Brown icon, light yellow bg
                     case LLM_QUEUED_KIF -> { iconText = "Q"; iconColor = Color.BLUE; contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC)); }
                     case LLM_SKIPPED -> { iconText = "S"; iconColor = Color.ORANGE; }
                     case LLM_SUMMARY -> { iconText = "Σ"; iconColor = Color.DARK_GRAY; }
                     case LLM_CONCEPTS -> { iconText = "C"; iconColor = Color.DARK_GRAY; }
                     case LLM_QUESTION -> { iconText = "?"; iconColor = Color.MAGENTA; }
                     case LLM_ERROR -> { iconText = "!"; iconColor = Color.RED; bgColor = new Color(255, 230, 230); }
                     case LLM_INFO -> { iconText = "i"; iconColor = Color.GRAY; }
                     case LLM_PLACEHOLDER -> { iconText = "…"; iconColor = Color.LIGHT_GRAY; contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC)); }
                 }

                // Format details string
                if (value.isActualAssertion()) {
                    details = String.format("P:%.3f | D:%d | %s", value.priority(), value.depth(), timeFormatter.format(Instant.ofEpochMilli(value.timestamp())));
                } else {
                    details = String.format("%s | %s", value.displayType(), timeFormatter.format(Instant.ofEpochMilli(value.timestamp())));
                }
                detailLabel.setText(details);
                iconLabel.setText(iconText);

                // Apply status modifications (Retracted/Evicted)
                if (value.status() != AssertionStatus.ACTIVE) {
                    fgColor = Color.LIGHT_GRAY; // Dim text for inactive items
                    contentLabel.setText("<html><strike>" + value.content().replace("<", "&lt;").replace(">", "&gt;") + "</strike></html>"); // Strikethrough + basic HTML escape
                    detailLabel.setText(value.status() + " | " + details); // Prepend status
                    bgColor = new Color(248, 248, 248); // Very light gray background
                    setBorder(inactiveBorder);
                    iconColor = Color.LIGHT_GRAY; // Dim icon too
                } else {
                    // contentLabel.setText(value.content()); // Already set above, remove strikethrough if needed
                    setBorder(activeBorder);
                }

                // Apply selection highlighting
                if (isSelected) {
                    setBackground(list.getSelectionBackground());
                    contentLabel.setForeground(list.getSelectionForeground());
                    detailLabel.setForeground(list.getSelectionForeground());
                    iconLabel.setForeground(list.getSelectionForeground());
                } else {
                    setBackground(bgColor);
                    contentLabel.setForeground(fgColor);
                    detailLabel.setForeground(value.status() == AssertionStatus.ACTIVE ? Color.GRAY : Color.LIGHT_GRAY); // Detail text dimmer than main text when active
                    iconLabel.setForeground(iconColor);
                }

                return this;
            }
        }
    }
}
