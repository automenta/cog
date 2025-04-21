package dumb.cognote18;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class Cog {

    public static final String KIF_OP_IMPLIES = "=>", KIF_OP_EQUIV = "<=>", KIF_OP_AND = "and", KIF_OP_OR = "or",
            KIF_OP_EXISTS = "exists", KIF_OP_FORALL = "forall", KIF_OP_EQUAL = "=", KIF_OP_NOT = "not";
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");
    private static final String PRED_NOTE_SUMMARY = "noteSummary", PRED_NOTE_CONCEPT = "noteConcept", PRED_NOTE_QUESTION = "noteQuestion";
    private static final int UI_FONT_SIZE = 16;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);
    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private static final String ID_PREFIX_RULE = "rule_", ID_PREFIX_FACT = "fact_", ID_PREFIX_SKOLEM_FUNC = "skf_",
            ID_PREFIX_SKOLEM_CONST = "skc_", ID_PREFIX_NOTE = "note-", ID_PREFIX_LLM_ITEM = "llm_",
            ID_PREFIX_INPUT_ITEM = "input_", ID_PREFIX_TEMP_ITEM = "temp_", ID_PREFIX_PLUGIN = "plugin_",
            ID_PREFIX_QUERY = "query_", ID_PREFIX_TICKET = "tms_", ID_PREFIX_OPERATOR = "op_",
            ID_PREFIX_LLM_RESULT = "llmres_";
    private static final String GLOBAL_KB_NOTE_ID = "kb://global";
    private static final String GLOBAL_KB_NOTE_TITLE = "Global Knowledge";
    private static final String CONFIG_NOTE_ID = "note-config";
    private static final String CONFIG_NOTE_TITLE = "System Configuration";
    private static final String NOTES_FILE = "cognote_notes.json";
    private static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_LLM_MODEL = "llama3";
    private static final int DEFAULT_KB_CAPACITY = 64 * 1024;
    private static final int DEFAULT_REASONING_DEPTH = 4;
    private static final int HTTP_TIMEOUT_SECONDS = 90;
    private static final double DEFAULT_RULE_PRIORITY = 1.0;
    private static final double INPUT_ASSERTION_BASE_PRIORITY = 10.0;
    private static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    private static final double DERIVED_PRIORITY_DECAY = 0.95;
    private static final int MAX_BACKWARD_CHAIN_DEPTH = 8;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;
    private static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90;
    private static final int KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    private static final int WS_STOP_TIMEOUT_MS = 1000;
    private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int MAX_WS_PARSE_PREVIEW = 100;

    final Events events;
    final Plugins plugins;
    final ReasonerManager reasonerManager;
    final Cognition context;
    final HttpClient http;
    final SwingUI swingUI;
    final MyWebSocketServer websocket;
    final ExecutorService mainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    boolean broadcastInputAssertions;
    String llmApiUrl;
    String llmModel;
    int globalKbCapacity;
    int reasoningDepthLimit;
    volatile String systemStatus = "Initializing";

    public Cog(int port, SwingUI ui) {
        this.swingUI = requireNonNull(ui, "SwingUI cannot be null");
        this.events = new Events(mainExecutor);
        var skolemizer = new Skolemizer();
        var tms = new BasicTMS(events);
        var operatorRegistry = new Operators();

        loadNotesAndConfig();

        this.context = new Cognition(globalKbCapacity, events, tms, skolemizer, operatorRegistry, this);
        this.reasonerManager = new ReasonerManager(events, context);
        this.plugins = new Plugins(events, context);

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .executor(mainExecutor)
                .build();
        this.websocket = new MyWebSocketServer(new InetSocketAddress(port));

        System.out.printf("System config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
                port, globalKbCapacity, broadcastInputAssertions, llmApiUrl, llmModel, reasoningDepthLimit);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            var port = 8887;
            String rulesFile = null;

            for (var i = 0; i < args.length; i++) {
                try {
                    switch (args[i]) {
                        case "-p", "--port" -> port = Integer.parseInt(args[++i]);
                        case "-r", "--rules" -> rulesFile = args[++i];
                        default -> System.err.println("Warning: Unknown or deprecated command-line option: " + args[i] + ". Configuration is now managed via UI/JSON.");
                    }
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    System.err.printf("Error parsing argument for %s: %s%n", (i > 0 ? args[i - 1] : args[i]), e.getMessage());
                    printUsageAndExit();
                }
            }

            SwingUI ui = null;
            try {
                ui = new SwingUI(null);
                var server = new Cog(port, ui);
                ui.setSystemReference(server);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    server.stopSystem();
                }));
                server.startSystem();
                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified via command line.");
                ui.setVisible(true);
            } catch (Exception e) {
                System.err.println("Initialization/Startup failed: " + e.getMessage());
                e.printStackTrace();
                ofNullable(ui).ifPresent(JFrame::dispose);
                System.exit(1);
            }
        });
    }

    private static void printUsageAndExit() {
        System.err.printf("Usage: java %s [-p port] [-r rules_file.kif]%n", Cog.class.getName());
        System.err.println("Note: Most configuration is now managed via the UI and persisted in " + NOTES_FILE);
        System.exit(1);
    }

    public static String generateId(String prefix) {
        return prefix + idCounter.incrementAndGet();
    }

    static boolean isTrivial(KifList list) {
        var s = list.size();
        var opOpt = list.op();
        if (s >= 3 && list.get(1).equals(list.get(2)))
            return opOpt.filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
        else if (opOpt.filter(KIF_OP_NOT::equals).isPresent() && s == 2 && list.get(1) instanceof KifList inner)
            return inner.size() >= 3 && inner.get(1).equals(inner.get(2)) && inner.op().filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
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

    private void setupDefaultPlugins() {
        plugins.loadPlugin(new InputProcessingPlugin());
        plugins.loadPlugin(new RetractionPlugin());
        plugins.loadPlugin(new StatusUpdaterPlugin(statusEvent -> updateStatusLabel(statusEvent.statusMessage())));
        plugins.loadPlugin(new WebSocketBroadcasterPlugin(this));
        plugins.loadPlugin(new UiUpdatePlugin(swingUI, this));

        reasonerManager.loadPlugin(new ForwardChainingReasonerPlugin());
        reasonerManager.loadPlugin(new RewriteRuleReasonerPlugin());
        reasonerManager.loadPlugin(new UniversalInstantiationReasonerPlugin());
        reasonerManager.loadPlugin(new BackwardChainingReasonerPlugin());

        var or = context.operators();
        BiFunction<KifList, DoubleBinaryOperator, Optional<KifTerm>> numeric = (args, op) -> {
            if (args.size() != 3 || !(args.get(1) instanceof KifAtom(var a1)) || !(args.get(2) instanceof KifAtom(
                    var a2
            ))) return Optional.empty();
            try {
                return Optional.of(KifAtom.of(String.valueOf(op.applyAsDouble(Double.parseDouble(a1), Double.parseDouble(a2)))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        };
        BiFunction<KifList, DoubleDoublePredicate, Optional<KifTerm>> comparison = (args, op) -> {
            if (args.size() != 3 || !(args.get(1) instanceof KifAtom(var a1)) || !(args.get(2) instanceof KifAtom(
                    var a2
            ))) return Optional.empty();
            try {
                return Optional.of(KifAtom.of(op.test(Double.parseDouble(a1), Double.parseDouble(a2)) ? "true" : "false"));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        };
        or.add(new BasicOperator(KifAtom.of("+"), args -> numeric.apply(args, Double::sum)));
        or.add(new BasicOperator(KifAtom.of("-"), args -> numeric.apply(args, (a, b) -> a - b)));
        or.add(new BasicOperator(KifAtom.of("*"), args -> numeric.apply(args, (a, b) -> a * b)));
        or.add(new BasicOperator(KifAtom.of("/"), args -> numeric.apply(args, (a, b) -> b == 0 ? Double.NaN : a / b)));
        or.add(new BasicOperator(KifAtom.of("<"), args -> comparison.apply(args, (a, b) -> a < b)));
        or.add(new BasicOperator(KifAtom.of(">"), args -> comparison.apply(args, (a, b) -> a > b)));
        or.add(new BasicOperator(KifAtom.of("<="), args -> comparison.apply(args, (a, b) -> a <= b)));
        or.add(new BasicOperator(KifAtom.of(">="), args -> comparison.apply(args, (a, b) -> a >= b)));
    }

    public void startSystem() {
        if (!running.get()) {
            System.err.println("Cannot restart a stopped system.");
            return;
        }
        paused.set(false);
        systemStatus = "Starting";
        updateStatusLabel();

        SwingUtilities.invokeLater(() -> {
            var globalNote = new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Assertions in the global knowledge base.");
            swingUI.addNoteToList(globalNote);
            swingUI.loadNotes(loadNotesFromFile());
        });

        setupDefaultPlugins();
        plugins.initializeAll();
        reasonerManager.initializeAll();

        try {
            websocket.start();
            System.out.println("WebSocket server started on port " + websocket.getPort());
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage());
            stopSystem();
            return;
        }

        systemStatus = "Running";
        updateStatusLabel();
        System.out.println("System started.");
    }

    public void stopSystem() {
        if (!running.compareAndSet(true, false)) return;
        System.out.println("Stopping system and services...");
        systemStatus = "Stopping";
        updateStatusLabel();
        paused.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        activeLlmTasks.values().forEach(f -> f.cancel(true));
        activeLlmTasks.clear();
        saveNotesToFile();

        plugins.shutdownAll();
        reasonerManager.shutdownAll();

        try {
            websocket.stop(WS_STOP_TIMEOUT_MS);
            System.out.println("WebSocket server stopped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while stopping WebSocket server.");
        } catch (Exception e) {
            System.err.println("Error stopping WebSocket server: " + e.getMessage());
        }

        shutdownExecutor(mainExecutor, "Main Executor");
        systemStatus = "Stopped";
        updateStatusLabel();
        System.out.println("System stopped.");
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean pause) {
        if (paused.get() == pause || !running.get()) return;
        paused.set(pause);
        systemStatus = pause ? "Paused" : "Running";
        updateStatusLabel();
        if (!pause) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        events.emit(new SystemStatusEvent(systemStatus, context.kbCount(), context.kbTotalCapacity(), activeLlmTasks.size(), 0, context.ruleCount()));
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);
        context.getAllNoteIds().forEach(noteId -> {
            if (!noteId.equals(GLOBAL_KB_NOTE_ID) && !noteId.equals(CONFIG_NOTE_ID))
                events.emit(new RetractionRequestEvent(noteId, RetractionType.BY_NOTE, "UI-ClearAll", noteId));
        });
        context.kbGlobal().getAllAssertionIds().forEach(assertionId -> context.truth().retractAssertion(assertionId, "UI-ClearAll"));
        context.clearAll();

        SwingUtilities.invokeLater(() -> {
            swingUI.clearAllUILists();
            var globalNote = new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Assertions in the global knowledge base.");
            swingUI.addNoteToList(globalNote);
            if (swingUI.findNoteById(CONFIG_NOTE_ID).isEmpty()) {
                var configNote = createDefaultConfigNote();
                swingUI.addNoteToList(configNote);
            }
            swingUI.noteList.setSelectedIndex(0);
        });

        systemStatus = "Cleared";
        updateStatusLabel();
        setPaused(false);
        System.out.println("Knowledge cleared.");
        events.emit(new SystemStatusEvent(systemStatus, 0, globalKbCapacity, 0, 0, 0));
    }

    public void loadExpressionsFromFile(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

        var kifBuffer = new StringBuilder();
        long[] counts = {0, 0, 0}; // lines, blocks, events
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            var parenDepth = 0;
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
                            KifParser.parseKif(kifText).forEach(term -> events.emit(new ExternalInputEvent(term, "file:" + filename, null)));
                            counts[2]++;
                        } catch (ParseException e) {
                            System.err.printf("File Parse Error (line ~%d): %s near '%s...'%n", counts[0], e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
                        } catch (Exception e) {
                            System.err.printf("File Processing Error (line ~%d): %s for '%s...'%n", counts[0], e.getMessage(), kifText.substring(0, Math.min(kifText.length(), MAX_KIF_PARSE_PREVIEW)));
                            e.printStackTrace();
                        }
                    }
                } else if (parenDepth < 0) {
                    System.err.printf("Mismatched parentheses near line %d: '%s'%n", counts[0], line);
                    parenDepth = 0;
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d KIF blocks from %s, published %d input events.%n", counts[1], filename, counts[2]);
    }

    private CompletableFuture<String> llmAsync(String taskId, String prompt, String interactionType, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            waitIfPaused();
            updateLlmItemStatus(taskId, SwingUI.LlmStatus.PROCESSING, interactionType + ": Waiting for LLM...");
            var payload = new JSONObject()
                    .put("model", llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2));
            var request = HttpRequest.newBuilder(URI.create(llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new IOException("LLM API request failed (" + interactionType + "): " + response.statusCode() + " Body: " + responseBody);
                return extractLlmContent(new JSONObject(new JSONTokener(responseBody)))
                        .orElseThrow(() -> new IOException("LLM response missing expected content field. Body: " + responseBody));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException("LLM API communication error (" + interactionType + ")", e);
            } catch (Exception e) {
                throw new CompletionException("LLM response processing error (" + interactionType + ")", e);
            }
        }, mainExecutor);
    }

    private void handleLlmKifResponse(String taskId, String noteId, String kifResult, Throwable ex) {
        activeLlmTasks.remove(taskId);
        if (ex instanceof CancellationException) {
            updateLlmItemStatus(taskId, SwingUI.LlmStatus.CANCELLED, "KIF Generation Cancelled.");
            return;
        }

        if (ex == null && kifResult != null) {
            var cleanedKif = kifResult.lines()
                    .map(s -> s.replaceAll("(?i)```kif", "").replaceAll("```", "").trim())
                    .filter(line -> line.startsWith("(") && line.endsWith(")") && !line.matches("^\\(\\s*\\)$"))
                    .collect(Collectors.joining("\n"));

            if (!cleanedKif.trim().isEmpty()) {
                System.out.printf("LLM Success (KIF %s): Extracted KIF assertions.%n", noteId);
                try {
                    KifParser.parseKif(cleanedKif).forEach(term -> events.emit(new ExternalInputEvent(term, "llm-kif:" + noteId, noteId)));
                    updateLlmItemStatus(taskId, SwingUI.LlmStatus.DONE, "KIF Generation Complete. Assertions added to KB.");
                } catch (ParseException parseEx) {
                    System.err.printf("LLM Error (KIF %s): Failed to parse generated KIF: %s%n", noteId, parseEx.getMessage());
                    updateLlmItemStatus(taskId, SwingUI.LlmStatus.ERROR, "KIF Parse Error: " + parseEx.getMessage());
                }
            } else {
                System.err.printf("LLM Warning (KIF %s): Result contained text but no valid KIF lines found after cleaning.%n", noteId);
                updateLlmItemStatus(taskId, SwingUI.LlmStatus.DONE, "KIF Generation Warning: No valid KIF found in response.");
            }
        } else {
            var errorMsg = (ex != null) ? "KIF Generation Error: " + ex.getMessage() : "KIF Generation Failed: Empty or null response.";
            System.err.printf("LLM Error (KIF %s): %s%n", noteId, errorMsg);
            updateLlmItemStatus(taskId, SwingUI.LlmStatus.ERROR, errorMsg);
        }
    }

    private void handleLlmGenericResponse(String taskId, String noteId, String interactionType, String response, Throwable ex, String kifPredicate) {
        activeLlmTasks.remove(taskId);
        if (ex instanceof CancellationException) {
            updateLlmItemStatus(taskId, SwingUI.LlmStatus.CANCELLED, interactionType + " Cancelled.");
            return;
        }

        if (ex == null && response != null && !response.isBlank()) {
            response.lines()
                    .map(String::trim)
                    .filter(Predicate.not(String::isBlank))
                    .forEach(lineContent -> {
                        var resultId = generateId(ID_PREFIX_LLM_RESULT);
                        var kifTerm = new KifList(KifAtom.of(kifPredicate), KifAtom.of(noteId), KifAtom.of(resultId), KifAtom.of(lineContent));
                        events.emit(new ExternalInputEvent(kifTerm, "llm-" + kifPredicate + ":" + noteId, noteId));
                    });
            updateLlmItemStatus(taskId, SwingUI.LlmStatus.DONE, interactionType + " Complete. Result added to KB.");
        } else {
            var errorMsg = (ex != null) ? interactionType + " Error: " + ex.getMessage() : interactionType + " Warning: Empty response.";
            System.err.printf("LLM %s (%s): %s%n", (ex != null ? "Error" : "Warning"), interactionType, errorMsg);
            updateLlmItemStatus(taskId, (ex != null ? SwingUI.LlmStatus.ERROR : SwingUI.LlmStatus.DONE), errorMsg);
        }
    }

    private void handleLlmEnhancementResponse(String taskId, String noteId, String response, Throwable ex) {
        activeLlmTasks.remove(taskId);
        if (ex instanceof CancellationException) {
            updateLlmItemStatus(taskId, SwingUI.LlmStatus.CANCELLED, "Enhancement Cancelled.");
            return;
        }

        if (ex == null && response != null && !response.isBlank()) {
            swingUI.findNoteById(noteId).ifPresent(note -> {
                note.text = response.trim();
                SwingUtilities.invokeLater(() -> {
                    if (note.equals(swingUI.currentNote)) {
                        swingUI.noteEditor.setText(note.text);
                        swingUI.noteEditor.setCaretPosition(0);
                    }
                });
                saveNotesToFile();
                updateLlmItemStatus(taskId, SwingUI.LlmStatus.DONE, "Note Enhanced and Updated.");
            });
        } else {
            var errorMsg = (ex != null) ? "Enhancement Error: " + ex.getMessage() : "Enhancement Warning: Empty response.";
            System.err.printf("LLM Enhancement (%s): %s%n", noteId, errorMsg);
            updateLlmItemStatus(taskId, (ex != null ? SwingUI.LlmStatus.ERROR : SwingUI.LlmStatus.DONE), errorMsg);
        }
    }

    public CompletableFuture<String> text2kifAsync(String taskId, String noteText, String noteId) {
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
        var future = llmAsync(taskId, finalPrompt, "KIF Generation", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((kifResult, ex) -> handleLlmKifResponse(taskId, noteId, kifResult, ex), mainExecutor);
        return future;
    }

    public CompletableFuture<String> enhanceNoteWithLlmAsync(String taskId, Note n) {
        String noteId = n.id;
        var finalPrompt = """
                You are a helpful assistant. Please revise and enhance the following note for clarity, conciseness, and improved structure. Keep the core meaning intact.
                Focus on improving readability and flow. Correct any grammatical errors or awkward phrasing.
                Output ONLY the revised note text, without any introductory or concluding remarks.

                Original Note:
                "%s"

                Enhanced Note:""".formatted(n.text);
        var future = llmAsync(taskId, finalPrompt, "Note Enhancement", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmEnhancementResponse(taskId, noteId, response, ex), mainExecutor);
        return future;
    }

    public CompletableFuture<String> summarizeNoteWithLlmAsync(String taskId, Note n) {
        String noteText = n.text, noteId = n.id;
        var finalPrompt = """
                Summarize the following note in one or two concise sentences. Output ONLY the summary.

                Note:
                "%s"

                Summary:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "Note Summarization", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, noteId, "Summary", response, ex, PRED_NOTE_SUMMARY), mainExecutor);
        return future;
    }

    public CompletableFuture<String> keyConceptsWithLlmAsync(String taskId, Note n) {
        String noteText = n.text, noteId = n.id;
        var finalPrompt = """
                Identify the key concepts or entities mentioned in the following note. List them separated by newlines. Output ONLY the newline-separated list.

                Note:
                "%s"

                Key Concepts:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "Key Concept Identification", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, noteId, "Concepts", response, ex, PRED_NOTE_CONCEPT), mainExecutor);
        return future;
    }

    public CompletableFuture<String> generateQuestionsWithLlmAsync(String taskId, Note n) {
        String noteText = n.text, noteId = n.id;
        var finalPrompt = """
                Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.

                Note:
                "%s"

                Questions:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "Question Generation", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, noteId, "Question Gen", response, ex, PRED_NOTE_QUESTION), mainExecutor);
        return future;
    }

    private Optional<String> extractLlmContent(JSONObject r) {
        return Stream.<Supplier<Optional<String>>>of(
                        () -> ofNullable(r.optJSONObject("message")).map(m -> m.optString("content", null)),
                        () -> ofNullable(r.optString("response", null)),
                        () -> ofNullable(r.optJSONArray("choices")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(c -> c.optJSONObject("message")).map(m -> m.optString("content", null)),
                        () -> ofNullable(r.optJSONArray("results")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(res -> res.optJSONObject("candidates")).map(cand -> cand.optJSONObject("content")).map(cont -> cont.optJSONArray("parts")).filter(Predicate.not(JSONArray::isEmpty)).map(p -> p.optJSONObject(0)).map(p -> p.optString("text", null)),
                        () -> findNestedContent(r)
                ).map(Supplier::get)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> obj.keySet().stream()
                    .filter(key -> key.toLowerCase().contains("content") || key.toLowerCase().contains("text") || key.toLowerCase().contains("response"))
                    .map(obj::opt)
                    .flatMap(val -> (val instanceof String s && !s.isBlank()) ? Stream.of(s) : Stream.empty())
                    .findFirst()
                    .or(() -> obj.keySet().stream().map(obj::opt).map(this::findNestedContent).flatMap(Optional::stream).findFirst());
            case JSONArray arr -> IntStream.range(0, arr.length()).mapToObj(arr::opt)
                    .map(this::findNestedContent)
                    .flatMap(Optional::stream)
                    .findFirst();
            case String s -> Optional.of(s).filter(Predicate.not(String::isBlank));
            default -> Optional.empty();
        };
    }

    void updateStatusLabel() {
        if (swingUI != null && swingUI.isDisplayable()) {
            var kbCount = context.kbCount();
            var kbCapacityTotal = context.kbTotalCapacity();
            int notesCount = swingUI.noteListModel.size();
            int tasksCount = activeLlmTasks.size();
            var statusText = String.format("KB: %d/%d | Rules: %d | Notes: %d | Tasks: %d | Status: %s",
                    kbCount, kbCapacityTotal, context.ruleCount(), notesCount, tasksCount, systemStatus);
            updateStatusLabel(statusText);
        }
    }

    private void updateStatusLabel(String statusText) {
        SwingUtilities.invokeLater(() -> swingUI.statusLabel.setText(statusText));
    }

    void waitIfPaused() {
        synchronized (pauseLock) {
            while (paused.get() && running.get()) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                }
            }
        }
        if (!running.get()) throw new RuntimeException("System stopped");
    }

    private void loadNotesAndConfig() {
        List<Note> notes = loadNotesFromFile();
        Optional<Note> configNoteOpt = notes.stream().filter(n -> n.id.equals(CONFIG_NOTE_ID)).findFirst();

        if (configNoteOpt.isPresent()) {
            parseConfig(configNoteOpt.get().text);
        } else {
            System.out.println("Configuration note not found, using defaults and creating one.");
            var configNote = createDefaultConfigNote();
            notes.add(configNote);
            parseConfig(configNote.text);
            saveNotesToFile(notes);
        }
    }

    private void parseConfig(String jsonText) {
        try {
            var configJson = new JSONObject(new JSONTokener(jsonText));
            this.llmApiUrl = configJson.optString("llmApiUrl", DEFAULT_LLM_URL);
            this.llmModel = configJson.optString("llmModel", DEFAULT_LLM_MODEL);
            this.globalKbCapacity = configJson.optInt("globalKbCapacity", DEFAULT_KB_CAPACITY);
            this.reasoningDepthLimit = configJson.optInt("reasoningDepthLimit", DEFAULT_REASONING_DEPTH);
            this.broadcastInputAssertions = configJson.optBoolean("broadcastInputAssertions", false);
        } catch (Exception e) {
            System.err.println("Error parsing configuration JSON, using defaults: " + e.getMessage());
            this.llmApiUrl = DEFAULT_LLM_URL;
            this.llmModel = DEFAULT_LLM_MODEL;
            this.globalKbCapacity = DEFAULT_KB_CAPACITY;
            this.reasoningDepthLimit = DEFAULT_REASONING_DEPTH;
            this.broadcastInputAssertions = false;
        }
    }

    private Note createDefaultConfigNote() {
        var configJson = new JSONObject()
                .put("llmApiUrl", DEFAULT_LLM_URL)
                .put("llmModel", DEFAULT_LLM_MODEL)
                .put("globalKbCapacity", DEFAULT_KB_CAPACITY)
                .put("reasoningDepthLimit", DEFAULT_REASONING_DEPTH)
                .put("broadcastInputAssertions", false);
        return new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, configJson.toString(2));
    }

    public boolean updateConfig(String newConfigJsonText) {
        try {
            var newConfigJson = new JSONObject(new JSONTokener(newConfigJsonText));
            parseConfig(newConfigJsonText);
            swingUI.findNoteById(CONFIG_NOTE_ID).ifPresent(note -> {
                note.text = newConfigJson.toString(2);
                saveNotesToFile();
            });
            System.out.println("Configuration updated and saved.");
            System.out.printf("New Config: KBSize=%d, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
                    globalKbCapacity, llmApiUrl, llmModel, reasoningDepthLimit);
            return true;
        } catch (org.json.JSONException e) {
            System.err.println("Failed to parse new configuration JSON: " + e.getMessage());
            return false;
        }
    }

    private List<Note> loadNotesFromFile() {
        Path filePath = Paths.get(NOTES_FILE);
        if (!Files.exists(filePath)) return new ArrayList<>(List.of(createDefaultConfigNote()));
        try {
            var jsonText = Files.readString(filePath);
            var jsonArray = new JSONArray(new JSONTokener(jsonText));
            List<Note> notes = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                var obj = jsonArray.getJSONObject(i);
                notes.add(new Note(obj.getString("id"), obj.getString("title"), obj.getString("text")));
            }
            if (notes.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
                notes.add(createDefaultConfigNote());
            }
            System.out.println("Loaded " + notes.size() + " notes from " + NOTES_FILE);
            return notes;
        } catch (IOException | org.json.JSONException e) {
            System.err.println("Error loading notes from " + NOTES_FILE + ": " + e.getMessage());
            return new ArrayList<>(List.of(createDefaultConfigNote()));
        }
    }

    private void saveNotesToFile() {
        saveNotesToFile(swingUI.getAllNotes());
    }

    private void saveNotesToFile(List<Note> notes) {
        Path filePath = Paths.get(NOTES_FILE);
        var jsonArray = new JSONArray();
        List<Note> notesToSave = new ArrayList<>(notes);
        if (notesToSave.stream().noneMatch(n -> n.id.equals(CONFIG_NOTE_ID))) {
            notesToSave.add(createDefaultConfigNote());
        }

        notesToSave.forEach(note -> jsonArray.put(new JSONObject()
                .put("id", note.id)
                .put("title", note.title)
                .put("text", note.text)));
        try {
            Files.writeString(filePath, jsonArray.toString(2));
            System.out.println("Saved " + notesToSave.size() + " notes to " + NOTES_FILE);
        } catch (IOException e) {
            System.err.println("Error saving notes to " + NOTES_FILE + ": " + e.getMessage());
        }
    }

    String addLlmUiPlaceholder(String noteId, String actionName) {
        var vm = SwingUI.AttachmentViewModel.forLlm(
                generateId(ID_PREFIX_LLM_ITEM + actionName.toLowerCase().replaceAll("\\s+", "")),
                noteId,
                actionName + ": Starting...",
                SwingUI.AttachmentType.LLM_INFO,
                System.currentTimeMillis(),
                noteId,
                SwingUI.LlmStatus.SENDING
        );
        events.emit(new LlmInfoEvent(vm));
        return vm.id;
    }

    private void updateLlmItemStatus(String taskId, SwingUI.LlmStatus status, String content) {
        events.emit(new LlmUpdateEvent(taskId, status, content));
    }

    enum RetractionType {BY_ID, BY_NOTE, BY_RULE_FORM}

    enum AssertionType {GROUND, UNIVERSAL, SKOLEMIZED}

    enum QueryType {ASK_BINDINGS, ASK_TRUE_FALSE, ACHIEVE_GOAL}

    enum Feature {FORWARD_CHAINING, BACKWARD_CHAINING, TRUTH_MAINTENANCE, CONTRADICTION_DETECTION, UNCERTAINTY_HANDLING, OPERATOR_SUPPORT, REWRITE_RULES, UNIVERSAL_INSTANTIATION}

    enum QueryStatus {SUCCESS, FAILURE, TIMEOUT, ERROR}

    enum ResolutionStrategy {RETRACT_WEAKEST, LOG_ONLY}

    @FunctionalInterface
    interface DoubleDoublePredicate {
        boolean test(double a, double b);
    }

    interface CogEvent {
        default String assocNote() { return null; }
    }

    interface Plugin {
        String id();
        void start(Events events, Cognition context);
        void stop();
    }

    interface ReasonerPlugin extends Plugin {
        void initialize(ReasonerContext context);
        default void processAssertionEvent(AssertionEvent event) {}
        default void processRuleEvent(RuleEvent event) {}
        CompletableFuture<Answer> executeQuery(Query query);
        Set<QueryType> getSupportedQueryTypes();
        Set<Feature> getSupportedFeatures();
        @Override default void start(Events events, Cognition ctx) {}
    }

    interface Truths {
        SupportTicket addAssertion(Assertion assertion, Set<String> justificationIds, String source);
        void retractAssertion(String assertionId, String source);
        Set<String> getActiveSupport(String assertionId);
        boolean isActive(String assertionId);
        Optional<Assertion> getAssertion(String assertionId);
        Collection<Assertion> getAllActiveAssertions();
        void resolveContradiction(Contradiction contradiction, ResolutionStrategy strategy);
        Set<Contradiction> findContradictions();
    }

    interface Operator {
        String id();
        KifAtom pred();
        CompletableFuture<KifTerm> exe(KifList arguments, ReasonerContext context);
    }

    sealed interface KifTerm permits KifAtom, KifVar, KifList {
        static Set<KifVar> collectSpecVars(KifTerm varsTerm) {
            return switch (varsTerm) {
                case KifVar v -> Set.of(v);
                case KifList l -> l.terms().stream().filter(KifVar.class::isInstance).map(KifVar.class::cast).collect(Collectors.toUnmodifiableSet());
                default -> {
                    System.err.println("Warning: Invalid variable specification in quantifier: " + varsTerm.toKif());
                    yield Set.of();
                }
            };
        }
        String toKif();
        boolean containsVar();
        Set<KifVar> vars();
        int weight();
        default boolean containsSkolemTerm() {
            return switch (this) {
                case KifAtom a -> a.value.startsWith(ID_PREFIX_SKOLEM_CONST);
                case KifList l -> l.op().filter(op -> op.startsWith(ID_PREFIX_SKOLEM_FUNC)).isPresent() || l.terms.stream().anyMatch(KifTerm::containsSkolemTerm);
                case KifVar ignored -> false;
            };
        }
    }

    record AssertionEvent(Assertion assertion, String noteId) implements CogEvent {
        @Override public String assocNote() { return noteId; }
    }

    record AssertionAddedEvent(Assertion assertion, String kbId) implements CogEvent {
        @Override public String assocNote() { return assertion.sourceNoteId(); }
        public String getKbId() { return kbId; }
    }

    record AssertionRetractedEvent(Assertion assertion, String kbId, String reason) implements CogEvent {
        @Override public String assocNote() { return assertion.sourceNoteId(); }
        public String getKbId() { return kbId; }
    }

    record AssertionEvictedEvent(Assertion assertion, String kbId) implements CogEvent {
        @Override public String assocNote() { return assertion.sourceNoteId(); }
        public String getKbId() { return kbId; }
    }

    record AssertionStatusChangedEvent(String assertionId, boolean isActive, String kbId) implements CogEvent {}

    record TemporaryAssertionEvent(KifList temporaryAssertion, Map<KifVar, KifTerm> bindings, String sourceNoteId) implements CogEvent {
        @Override public String assocNote() { return sourceNoteId; }
    }

    record RuleEvent(Rule rule) implements CogEvent {}
    record RuleAddedEvent(Rule rule) implements CogEvent {}
    record RuleRemovedEvent(Rule rule) implements CogEvent {}

    record LlmInfoEvent(SwingUI.AttachmentViewModel llmItem) implements CogEvent {
        @Override public String assocNote() { return llmItem.noteId(); }
    }

    record LlmUpdateEvent(String taskId, SwingUI.LlmStatus status, String content) implements CogEvent {}

    record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize, int commitQueueSize, int ruleCount) implements CogEvent {}

    record AddedEvent(Note note) implements CogEvent {
        @Override public String assocNote() { return note.id; }
    }

    record RemovedEvent(Note note) implements CogEvent {
        @Override public String assocNote() { return note.id; }
    }

    record ExternalInputEvent(KifTerm term, String sourceId, @Nullable String targetNoteId) implements CogEvent {
        @Override public String assocNote() { return targetNoteId; }
    }

    record RetractionRequestEvent(String target, RetractionType type, String sourceId, @Nullable String targetNoteId) implements CogEvent {
        @Override public String assocNote() { return targetNoteId; }
    }

    record WebSocketBroadcastEvent(String message) implements CogEvent {}
    record ContradictionDetectedEvent(Set<String> contradictoryAssertionIds, String kbId) implements CogEvent {}
    record QueryRequestEvent(Query query) implements CogEvent {}
    record QueryResultEvent(Answer result) implements CogEvent {}

    static class Events {
        private final ConcurrentMap<Class<? extends CogEvent>, CopyOnWriteArrayList<Consumer<CogEvent>>> listeners = new ConcurrentHashMap<>();
        private final ConcurrentMap<KifTerm, CopyOnWriteArrayList<BiConsumer<CogEvent, Map<KifVar, KifTerm>>>> patternListeners = new ConcurrentHashMap<>();
        private final ExecutorService exe;

        Events(ExecutorService exe) { this.exe = requireNonNull(exe); }

        public <T extends CogEvent> void on(Class<T> eventType, Consumer<T> listener) {
            listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(event -> listener.accept(eventType.cast(event)));
        }

        public void on(KifTerm pattern, BiConsumer<CogEvent, Map<KifVar, KifTerm>> listener) {
            patternListeners.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        public void emit(CogEvent event) {
            if (exe.isShutdown()) {
                System.err.println("Warning: Events executor shutdown. Cannot publish event: " + event.getClass().getSimpleName());
                return;
            }
            exe.submit(() -> {
                listeners.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>()).forEach(listener -> exeSafe(listener, event, "Direct Listener"));
                switch (event) {
                    case AssertionAddedEvent aaEvent -> handlePatternMatching(aaEvent.assertion().kif, event);
                    case TemporaryAssertionEvent taEvent -> handlePatternMatching(taEvent.temporaryAssertion(), event);
                    default -> {}
                }
            });
        }

        private void handlePatternMatching(KifTerm eventTerm, CogEvent event) {
            patternListeners.forEach((pattern, listeners) ->
                    ofNullable(Unifier.match(pattern, eventTerm, Map.of()))
                            .ifPresent(bindings -> listeners.forEach(listener -> exeSafe(listener, event, bindings, "Pattern Listener")))
            );
        }

        private void exeSafe(Consumer<CogEvent> listener, CogEvent event, String type) {
            try { listener.accept(event); }
            catch (Exception e) { logExeError(e, type, event.getClass().getSimpleName()); }
        }

        private void exeSafe(BiConsumer<CogEvent, Map<KifVar, KifTerm>> listener, CogEvent event, Map<KifVar, KifTerm> bindings, String type) {
            try { listener.accept(event, bindings); }
            catch (Exception e) { logExeError(e, type, event.getClass().getSimpleName() + " (Pattern Match)"); }
        }

        private void logExeError(Exception e, String type, String eventName) {
            System.err.printf("Error in %s for %s: %s%n", type, eventName, e.getMessage());
            e.printStackTrace();
        }

        public void shutdown() { listeners.clear(); patternListeners.clear(); }
    }

    static class Plugins {
        private final Events events;
        private final Cognition context;
        private final List<Plugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        Plugins(Events events, Cognition context) { this.events = events; this.context = context; }

        public void loadPlugin(Plugin plugin) {
            if (initialized.get()) { System.err.println("Cannot load plugin " + plugin.id() + " after initialization."); return; }
            plugins.add(plugin);
            System.out.println("Plugin loaded: " + plugin.id());
        }

        public void initializeAll() {
            if (!initialized.compareAndSet(false, true)) return;
            System.out.println("Initializing " + plugins.size() + " general plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.start(events, context);
                    System.out.println("Initialized plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Failed to initialize plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                    plugins.remove(plugin);
                }
            });
            System.out.println("General plugin initialization complete.");
        }

        public void shutdownAll() {
            System.out.println("Shutting down " + plugins.size() + " general plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.stop();
                    System.out.println("Shutdown plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Error shutting down plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            System.out.println("General plugin shutdown complete.");
        }
    }

    record ReasonerContext(Cognition cognition, Events events) {
        Knowledge getKb(@Nullable String noteId) { return cognition.kb(noteId); }
        Set<Rule> rules() { return cognition.rules(); }
        Configuration getConfig() { return new Configuration(cognition.cog); }
        Skolemizer getSkolemizer() { return cognition.skolemizer(); }
        Truths getTMS() { return cognition.truth(); }
        Operators operators() { return cognition.operators(); }
    }

    record Query(String id, QueryType type, KifTerm pattern, @Nullable String targetKbId, Map<String, Object> parameters) {}

    record Answer(String query, QueryStatus status, List<Map<KifVar, KifTerm>> bindings, @Nullable Explanation explanation) {
        static Answer success(String queryId, List<Map<KifVar, KifTerm>> bindings) { return new Answer(queryId, QueryStatus.SUCCESS, bindings, null); }
        static Answer failure(String queryId) { return new Answer(queryId, QueryStatus.FAILURE, List.of(), null); }
        static Answer error(String queryId, String message) { return new Answer(queryId, QueryStatus.ERROR, List.of(), new Explanation(message)); }
    }

    record Explanation(String details) {}
    record SupportTicket(String ticketId, String assertionId) {}
    record Contradiction(Set<String> conflictingAssertionIds) {}

    static class Operators {
        private final ConcurrentMap<KifAtom, Operator> ops = new ConcurrentHashMap<>();
        void add(Operator operator) { ops.put(operator.pred(), operator); System.out.println("Registered operator: " + operator.pred().toKif()); }
        Optional<Operator> get(KifAtom predicate) { return ofNullable(ops.get(predicate)); }
    }

    static class Skolemizer {
        KifList skolemize(KifList existentialFormula, Map<KifVar, KifTerm> contextBindings) {
            if (!KIF_OP_EXISTS.equals(existentialFormula.op().orElse(""))) throw new IllegalArgumentException("Input must be an 'exists' formula");
            if (existentialFormula.size() != 3 || !(existentialFormula.get(1) instanceof KifList || existentialFormula.get(1) instanceof KifVar) || !(existentialFormula.get(2) instanceof KifList body)) throw new IllegalArgumentException("Invalid 'exists' format: " + existentialFormula.toKif());

            var vars = KifTerm.collectSpecVars(existentialFormula.get(1));
            if (vars.isEmpty()) return body;

            Set<KifVar> freeVars = new HashSet<>(body.vars());
            freeVars.removeAll(vars);
            var skolemArgs = freeVars.stream().map(fv -> Unifier.substFully(contextBindings.getOrDefault(fv, fv), contextBindings)).sorted(Comparator.comparing(KifTerm::toKif)).toList();

            Map<KifVar, KifTerm> skolemMap = new HashMap<>();
            for (var exVar : vars) {
                var skolemNameBase = ID_PREFIX_SKOLEM_CONST + exVar.name().substring(1) + "_" + idCounter.incrementAndGet();
                var skolemTerm = skolemArgs.isEmpty()
                        ? KifAtom.of(skolemNameBase)
                        : new KifList(Stream.concat(Stream.of(KifAtom.of(ID_PREFIX_SKOLEM_FUNC + exVar.name().substring(1) + "_" + idCounter.incrementAndGet())), skolemArgs.stream()).toList());
                skolemMap.put(exVar, skolemTerm);
            }
            var substituted = Unifier.subst(body, skolemMap);
            return (substituted instanceof KifList sl) ? sl : new KifList(substituted);
        }
    }

    record Configuration(Cog cog) {
        String llmApiUrl() { return cog.llmApiUrl; }
        String llmModel() { return cog.llmModel; }
        int globalKbCapacity() { return cog.globalKbCapacity; }
        int reasoningDepthLimit() { return cog.reasoningDepthLimit; }
        boolean broadcastInputAssertions() { return cog.broadcastInputAssertions; }
        JSONObject toJson() {
            return new JSONObject()
                    .put("llmApiUrl", llmApiUrl())
                    .put("llmModel", llmModel())
                    .put("globalKbCapacity", globalKbCapacity())
                    .put("reasoningDepthLimit", reasoningDepthLimit())
                    .put("broadcastInputAssertions", broadcastInputAssertions());
        }
    }

    static class ReasonerManager {
        private final Events events;
        private final ReasonerContext reasonerContext;
        private final List<ReasonerPlugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        ReasonerManager(Events events, Cognition ctx) { this.events = events; this.reasonerContext = new ReasonerContext(ctx, events); }

        public void loadPlugin(ReasonerPlugin plugin) {
            if (initialized.get()) { System.err.println("Cannot load reasoner plugin " + plugin.id() + " after initialization."); return; }
            plugins.add(plugin);
            System.out.println("Reasoner plugin loaded: " + plugin.id());
        }

        public void initializeAll() {
            if (!initialized.compareAndSet(false, true)) return;
            System.out.println("Initializing " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.initialize(reasonerContext);
                    System.out.println("Initialized reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Failed to initialize reasoner plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                    plugins.remove(plugin);
                }
            });

            events.on(AssertionAddedEvent.class, this::dispatchAssertionEvent);
            events.on(AssertionRetractedEvent.class, this::dispatchAssertionEvent);
            events.on(AssertionStatusChangedEvent.class, this::dispatchAssertionEvent);
            events.on(RuleAddedEvent.class, this::dispatchRuleEvent);
            events.on(RuleRemovedEvent.class, this::dispatchRuleEvent);
            events.on(QueryRequestEvent.class, this::handleQueryRequest);
            System.out.println("Reasoner plugin initialization complete.");
        }

        private void dispatchAssertionEvent(CogEvent event) {
            switch (event) {
                case AssertionAddedEvent aae -> plugins.forEach(p -> p.processAssertionEvent(new AssertionEvent(aae.assertion(), aae.getKbId())));
                case AssertionRetractedEvent are -> plugins.forEach(p -> p.processAssertionEvent(new AssertionEvent(are.assertion(), are.getKbId())));
                case AssertionStatusChangedEvent asce -> getTMS().getAssertion(asce.assertionId()).ifPresent(a -> plugins.forEach(p -> p.processAssertionEvent(new AssertionEvent(a, asce.kbId()))));
                default -> {}
            }
        }

        private void dispatchRuleEvent(CogEvent event) {
            switch (event) {
                case RuleAddedEvent(var rule) -> plugins.forEach(p -> p.processRuleEvent(new RuleEvent(rule)));
                case RuleRemovedEvent(var rule) -> plugins.forEach(p -> p.processRuleEvent(new RuleEvent(rule)));
                default -> {}
            }
        }

        private void handleQueryRequest(QueryRequestEvent event) {
            var query = event.query();
            var futures = plugins.stream().filter(p -> p.getSupportedQueryTypes().contains(query.type)).map(p -> p.executeQuery(query)).toList();

            if (futures.isEmpty()) { events.emit(new QueryResultEvent(Answer.failure(query.id))); return; }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApplyAsync(v -> {
                        List<Map<KifVar, KifTerm>> allBindings = new ArrayList<>();
                        var overallStatus = QueryStatus.FAILURE;
                        Explanation combinedExplanation = null;
                        for (var future : futures) {
                            try {
                                var result = future.join();
                                if (result.status == QueryStatus.SUCCESS) {
                                    overallStatus = QueryStatus.SUCCESS;
                                    allBindings.addAll(result.bindings());
                                    if (result.explanation() != null) combinedExplanation = result.explanation();
                                } else if (result.status != QueryStatus.FAILURE && overallStatus == QueryStatus.FAILURE) {
                                    overallStatus = result.status;
                                    if (result.explanation() != null) combinedExplanation = result.explanation();
                                }
                            } catch (CompletionException | CancellationException e) {
                                System.err.println("Query execution error for " + query.id + ": " + e.getMessage());
                                if (overallStatus != QueryStatus.ERROR) {
                                    overallStatus = QueryStatus.ERROR;
                                    combinedExplanation = new Explanation(e.getMessage());
                                }
                            }
                        }
                        return new Answer(query.id, overallStatus, allBindings, combinedExplanation);
                    }, reasonerContext.events.exe)
                    .thenAccept(result -> events.emit(new QueryResultEvent(result)));
        }

        public void shutdownAll() {
            System.out.println("Shutting down " + plugins.size() + " reasoner plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.stop();
                    System.out.println("Shutdown reasoner plugin: " + plugin.id());
                } catch (Exception e) {
                    System.err.println("Error shutting down reasoner plugin " + plugin.id() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            System.out.println("Reasoner plugin shutdown complete.");
        }

        private Truths getTMS() { return reasonerContext.getTMS(); }
    }

    record KifAtom(String value) implements KifTerm {
        private static final Pattern SAFE_ATOM_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-+*/.<>=:]+$");
        private static final Map<String, KifAtom> internCache = new ConcurrentHashMap<>(1024);
        KifAtom { requireNonNull(value); }
        public static KifAtom of(String value) { return internCache.computeIfAbsent(value, KifAtom::new); }
        @Override public String toKif() {
            var needsQuotes = value.isEmpty() || !SAFE_ATOM_PATTERN.matcher(value).matches() || value.chars().anyMatch(c -> Character.isWhitespace(c) || "()\";?".indexOf(c) != -1);
            return needsQuotes ? '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : value;
        }
        @Override public boolean containsVar() { return false; }
        @Override public Set<KifVar> vars() { return Set.of(); }
        @Override public int weight() { return 1; }
        @Override public String toString() { return "KifAtom[" + value + ']'; }
    }

    record KifVar(String name) implements KifTerm {
        private static final Map<String, KifVar> internCache = new ConcurrentHashMap<>(256);
        KifVar {
            requireNonNull(name);
            if (!name.startsWith("?") || name.length() < 2) throw new IllegalArgumentException("Variable name must start with '?' and have length > 1: " + name);
        }
        public static KifVar of(String name) { return internCache.computeIfAbsent(name, KifVar::new); }
        @Override public String toKif() { return name; }
        @Override public boolean containsVar() { return true; }
        @Override public Set<KifVar> vars() { return Set.of(this); }
        @Override public int weight() { return 1; }
        @Override public String toString() { return "KifVar[" + name + ']'; }
    }

    static final class KifList implements KifTerm {
        final List<KifTerm> terms;
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;
        private volatile Set<KifVar> variablesCache;
        private volatile Boolean containsVariableCache;
        private volatile Boolean containsSkolemCache;

        KifList(List<KifTerm> terms) { this.terms = List.copyOf(requireNonNull(terms)); }
        KifList(KifTerm... terms) { this(List.of(terms)); }
        public List<KifTerm> terms() { return terms; }
        KifTerm get(int index) { return terms.get(index); }
        int size() { return terms.size(); }
        Optional<String> op() { return terms.isEmpty() || !(terms.getFirst() instanceof KifAtom(var v)) ? Optional.empty() : Optional.of(v); }
        @Override public String toKif() {
            if (kifStringCache == null) kifStringCache = terms.stream().map(KifTerm::toKif).collect(Collectors.joining(" ", "(", ")"));
            return kifStringCache;
        }
        @Override public boolean containsVar() {
            if (containsVariableCache == null) containsVariableCache = terms.stream().anyMatch(KifTerm::containsVar);
            return containsVariableCache;
        }
        @Override public boolean containsSkolemTerm() {
            if (containsSkolemCache == null) containsSkolemCache = KifTerm.super.containsSkolemTerm();
            return containsSkolemCache;
        }
        @Override public Set<KifVar> vars() {
            if (variablesCache == null) variablesCache = terms.stream().flatMap(t -> t.vars().stream()).collect(Collectors.toUnmodifiableSet());
            return variablesCache;
        }
        @Override public int weight() {
            if (weightCache == -1) weightCache = 1 + terms.stream().mapToInt(KifTerm::weight).sum();
            return weightCache;
        }
        @Override public boolean equals(Object o) { return this == o || (o instanceof KifList that && this.hashCode() == that.hashCode() && terms.equals(that.terms)); }
        @Override public int hashCode() {
            if (!hashCodeCalculated) { hashCodeCache = terms.hashCode(); hashCodeCalculated = true; }
            return hashCodeCache;
        }
        @Override public String toString() { return "KifList" + terms; }
    }

    record Assertion(String id, KifList kif, double pri, long timestamp, @Nullable String sourceNoteId,
                     Set<String> justificationIds, AssertionType type,
                     boolean isEquality, boolean isOrientedEquality, boolean negated,
                     List<KifVar> quantifiedVars, int derivationDepth, boolean isActive, String kb) implements Comparable<Assertion> {
        Assertion {
            requireNonNull(id); requireNonNull(kif); requireNonNull(type); requireNonNull(kb);
            justificationIds = Set.copyOf(requireNonNull(justificationIds));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (negated != kif.op().filter(KIF_OP_NOT::equals).isPresent()) throw new IllegalArgumentException("Negation flag mismatch for KIF: " + kif.toKif());
            if (type == AssertionType.UNIVERSAL && (kif.op().filter(KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty())) throw new IllegalArgumentException("Universal assertion must be (forall ...) with quantified vars: " + kif.toKif());
            if (type != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty()) throw new IllegalArgumentException("Only Universal assertions should have quantified vars: " + kif.toKif());
        }
        @Override public int compareTo(Assertion other) {
            var cmp = Boolean.compare(other.isActive, this.isActive);
            if (cmp != 0) return cmp;
            cmp = Double.compare(other.pri, this.pri);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.derivationDepth, other.derivationDepth);
            if (cmp != 0) return cmp;
            return Long.compare(other.timestamp, this.timestamp);
        }
        String toKifString() { return kif.toKif(); }
        KifTerm getEffectiveTerm() {
            return switch (type) {
                case GROUND, SKOLEMIZED -> negated ? kif.get(1) : kif;
                case UNIVERSAL -> kif.get(2);
            };
        }
        Set<KifAtom> getReferencedPredicates() {
            Set<KifAtom> p = new HashSet<>();
            collectPredicatesRecursive(getEffectiveTerm(), p);
            return Collections.unmodifiableSet(p);
        }
        private void collectPredicatesRecursive(KifTerm term, Set<KifAtom> predicates) {
            switch (term) {
                case KifList list when !list.terms().isEmpty() && list.get(0) instanceof KifAtom pred -> {
                    predicates.add(pred);
                    list.terms().stream().skip(1).forEach(sub -> collectPredicatesRecursive(sub, predicates));
                }
                case KifList list -> list.terms().forEach(sub -> collectPredicatesRecursive(sub, predicates));
                default -> {}
            }
        }
        Assertion withStatus(boolean newActiveStatus) {
            return new Assertion(id, kif, pri, timestamp, sourceNoteId, justificationIds, type, isEquality, isOrientedEquality, negated, quantifiedVars, derivationDepth, newActiveStatus, kb);
        }
    }

    record Rule(String id, KifList form, KifTerm antecedent, KifTerm consequent, double pri, List<KifTerm> antecedents) {
        Rule {
            requireNonNull(id); requireNonNull(form); requireNonNull(antecedent); requireNonNull(consequent);
            antecedents = List.copyOf(requireNonNull(antecedents));
        }
        static Rule parseRule(String id, KifList ruleForm, double pri) throws IllegalArgumentException {
            if (!(ruleForm.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent() && ruleForm.size() == 3)) throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKif());
            var antTerm = ruleForm.get(1);
            var conTerm = ruleForm.get(2);
            var parsedAntecedents = switch (antTerm) {
                case KifList list when list.op().filter(KIF_OP_AND::equals).isPresent() -> list.terms().stream().skip(1).map(Rule::validateAntecedentClause).toList();
                case KifList list -> List.of(validateAntecedentClause(list));
                case KifTerm t when t.equals(KifAtom.of("true")) -> List.<KifTerm>of();
                default -> throw new IllegalArgumentException("Antecedent must be a KIF list, (not list), (and ...), or true: " + antTerm.toKif());
            };
            validateUnboundVariables(ruleForm, antTerm, conTerm);
            return new Rule(id, ruleForm, antTerm, conTerm, pri, parsedAntecedents);
        }
        private static KifTerm validateAntecedentClause(KifTerm term) {
            return switch (term) {
                case KifList list -> {
                    if (list.op().filter(KIF_OP_NOT::equals).isPresent() && (list.size() != 2 || !(list.get(1) instanceof KifList))) throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + list.toKif());
                    yield list;
                }
                default -> throw new IllegalArgumentException("Elements of rule antecedent must be lists or (not list): " + term.toKif());
            };
        }
        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
            var unbound = new HashSet<KifVar>(consequent.vars());
            unbound.removeAll(antecedent.vars());
            unbound.removeAll(getQuantifierBoundVariables(consequent));
            if (!unbound.isEmpty() && ruleForm.op().filter(KIF_OP_IMPLIES::equals).isPresent())
                System.err.println("Warning: Rule consequent has variables not bound by antecedent or local quantifier: " + unbound.stream().map(KifVar::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKif());
        }
        private static Set<KifVar> getQuantifierBoundVariables(KifTerm term) {
            Set<KifVar> bound = new HashSet<>();
            collectQuantifierBoundVariablesRecursive(term, bound);
            return Collections.unmodifiableSet(bound);
        }
        private static void collectQuantifierBoundVariablesRecursive(KifTerm term, Set<KifVar> boundVars) {
            switch (term) {
                case KifList list when list.size() == 3 && list.op().filter(op -> op.equals(KIF_OP_EXISTS) || op.equals(KIF_OP_FORALL)).isPresent() -> {
                    boundVars.addAll(KifTerm.collectSpecVars(list.get(1)));
                    collectQuantifierBoundVariablesRecursive(list.get(2), boundVars);
                }
                case KifList list -> list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
                default -> {}
            }
        }
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
            if (isNegated != kif.op().filter(KIF_OP_NOT::equals).isPresent()) throw new IllegalArgumentException("Negation flag mismatch for potential KIF: " + kif.toKif());
            if (derivedType == AssertionType.UNIVERSAL && (kif.op().filter(KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty())) throw new IllegalArgumentException("Potential Universal assertion must be (forall ...) with quantified vars: " + kif.toKif());
            if (derivedType != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty()) throw new IllegalArgumentException("Only potential Universal assertions should have quantified vars: " + kif.toKif());
        }
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && kif.equals(pa.kif); }
        @Override public int hashCode() { return kif.hashCode(); }
        KifTerm getEffectiveTerm() {
            return switch (derivedType) {
                case GROUND, SKOLEMIZED -> isNegated ? kif.get(1) : kif;
                case UNIVERSAL -> kif.get(2);
            };
        }
    }

    static class PathNode {
        static final Class<KifVar> VAR_MARKER = KifVar.class;
        static final Object LIST_MARKER = new Object();
        final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
        final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet();
    }

    static class PathIndex {
        private final PathNode root = new PathNode();
        private final Truths tms;
        PathIndex(Truths tms) { this.tms = tms; }
        void add(Assertion assertion) { if (tms.isActive(assertion.id)) addPathsRecursive(assertion.kif, assertion.id, root); }
        void remove(Assertion assertion) { removePathsRecursive(assertion.kif, assertion.id, root); }
        void clear() { root.children.clear(); root.assertionIdsHere.clear(); }
        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) { return findCandidates(queryTerm, this::findUnifiableRecursive).stream().map(tms::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive); }
        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            var neg = (queryPattern instanceof KifList ql && ql.op().filter(KIF_OP_NOT::equals).isPresent());
            return findCandidates(queryPattern, this::findInstancesRecursive).stream().map(tms::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.negated == neg).filter(a -> Unifier.match(queryPattern, a.kif, Map.of()) != null);
        }
        Stream<Assertion> findGeneralizationsOf(KifTerm queryTerm) { return findCandidates(queryTerm, this::findGeneralizationsRecursive).stream().map(tms::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive); }
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
            if (term instanceof KifList list) list.terms().forEach(subTerm -> addPathsRecursive(subTerm, assertionId, termNode));
        }
        private boolean removePathsRecursive(KifTerm term, String assertionId, PathNode currentNode) {
            if (currentNode == null) return false;
            currentNode.assertionIdsHere.remove(assertionId);
            var key = getIndexKey(term);
            var termNode = currentNode.children.get(key);
            if (termNode != null) {
                termNode.assertionIdsHere.remove(assertionId);
                var canPruneChild = true;
                if (term instanceof KifList list) canPruneChild = list.terms().stream().allMatch(subTerm -> removePathsRecursive(subTerm, assertionId, termNode));
                if (canPruneChild && termNode.assertionIdsHere.isEmpty() && termNode.children.isEmpty()) currentNode.children.remove(key, termNode);
            }
            return currentNode.assertionIdsHere.isEmpty() && currentNode.children.isEmpty();
        }
        private Object getIndexKey(KifTerm term) {
            return switch (term) {
                case KifAtom a -> a.value();
                case KifVar _ -> PathNode.VAR_MARKER;
                case KifList l -> l.op().map(op -> (Object) op).orElse(PathNode.LIST_MARKER);
            };
        }
        private void collectAllAssertionIds(PathNode node, Set<String> ids) {
            if (node == null) return;
            ids.addAll(node.assertionIdsHere);
            node.children.values().forEach(child -> collectAllAssertionIds(child, ids));
        }
        private void findUnifiableRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
            if (queryTerm instanceof KifList) ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> collectAllAssertionIds(listNode, candidates));
            var specificNode = indexNode.children.get(getIndexKey(queryTerm));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                if (queryTerm instanceof KifList) collectAllAssertionIds(specificNode, candidates);
            }
            if (queryTerm instanceof KifVar) indexNode.children.values().forEach(childNode -> collectAllAssertionIds(childNode, candidates));
        }
        private void findInstancesRecursive(KifTerm queryPattern, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            if (queryPattern instanceof KifVar) { collectAllAssertionIds(indexNode, candidates); return; }
            var specificNode = indexNode.children.get(getIndexKey(queryPattern));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                if (queryPattern instanceof KifList listPattern && !listPattern.terms().isEmpty()) { collectAllAssertionIds(specificNode, candidates); }
            }
        }
        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
            if (queryTerm instanceof KifList) ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> candidates.addAll(listNode.assertionIdsHere));
            ofNullable(indexNode.children.get(getIndexKey(queryTerm))).ifPresent(nextNode -> {
                candidates.addAll(nextNode.assertionIdsHere);
                if (queryTerm instanceof KifList queryList && !queryList.terms().isEmpty()) { queryList.terms().forEach(subTerm -> findGeneralizationsRecursive(subTerm, nextNode, candidates)); }
            });
        }
        @FunctionalInterface private interface TriConsumer<T, U, V> { void accept(T t, U u, V v); }
    }

    static class Knowledge {
        final String id;
        final int capacity;
        final Events events;
        final Truths truth;
        final PathIndex paths;
        final ConcurrentMap<KifAtom, Set<String>> universalIndex = new ConcurrentHashMap<>();
        final PriorityBlockingQueue<String> groundEvictionQueue;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        Knowledge(String kbId, int capacity, Events events, Truths truth) {
            this.id = requireNonNull(kbId); this.capacity = capacity; this.events = requireNonNull(events);
            this.truth = requireNonNull(truth); this.paths = new PathIndex(truth);
            this.groundEvictionQueue = new PriorityBlockingQueue<>(1024,
                    Comparator.<String, Double>comparing(id -> truth.getAssertion(id).map(Assertion::pri).orElse(Double.MAX_VALUE))
                            .thenComparing(id -> truth.getAssertion(id).map(Assertion::timestamp).orElse(Long.MAX_VALUE)));
        }
        int getAssertionCount() { return (int) truth.getAllActiveAssertions().stream().filter(a -> a.kb.equals(id)).count(); }
        List<String> getAllAssertionIds() { return truth.getAllActiveAssertions().stream().filter(a -> a.kb.equals(id)).map(Assertion::id).toList(); }
        Optional<Assertion> getAssertion(String id) { return truth.getAssertion(id).filter(a -> a.kb.equals(this.id)); }
        List<Assertion> getAllAssertions() { return truth.getAllActiveAssertions().stream().filter(a -> a.kb.equals(id)).toList(); }

        @Nullable Assertion commit(PotentialAssertion pa, String source) {
            if (pa.kif instanceof KifList kl && Cog.isTrivial(kl)) return null;
            lock.writeLock().lock();
            try {
                var finalType = (pa.derivedType == AssertionType.GROUND && pa.kif.containsSkolemTerm()) ? AssertionType.SKOLEMIZED : pa.derivedType;

                var existingMatch = findExactMatchInternal(pa.kif);
                if (existingMatch.isPresent() && truth.isActive(existingMatch.get().id)) return null;
                if (isSubsumedInternal(pa.kif, pa.isNegated())) return null;

                enforceKbCapacityInternal(source);
                if (getAssertionCount() >= capacity) {
                    System.err.printf("Warning: KB '%s' full (%d/%d) after eviction attempt. Cannot add: %s%n", id, getAssertionCount(), capacity, pa.kif.toKif());
                    return null;
                }

                var newId = generateId(ID_PREFIX_FACT + finalType.name().toLowerCase() + "_");
                var newAssertion = new Assertion(newId, pa.kif, pa.pri, System.currentTimeMillis(), pa.sourceNoteId(), pa.support(), finalType, pa.isEquality(), pa.isOrientedEquality(), pa.isNegated(), pa.quantifiedVars(), pa.derivationDepth(), true, id);

                var ticket = truth.addAssertion(newAssertion, pa.support(), source);
                if (ticket == null) return null;

                var addedAssertion = truth.getAssertion(newId).orElse(null);
                if (addedAssertion == null || !addedAssertion.isActive()) return null;

                switch (finalType) {
                    case GROUND, SKOLEMIZED -> { paths.add(addedAssertion); groundEvictionQueue.offer(newId); }
                    case UNIVERSAL -> addedAssertion.getReferencedPredicates().forEach(pred -> universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(newId));
                }
                checkResourceThresholds();
                events.emit(new AssertionAddedEvent(addedAssertion, id));
                return addedAssertion;
            } finally { lock.writeLock().unlock(); }
        }

        void retractAssertion(String id, String source) { lock.writeLock().lock(); try { truth.retractAssertion(id, source); } finally { lock.writeLock().unlock(); } }

        void clear(String source) {
            lock.writeLock().lock();
            try {
                new HashSet<>(getAllAssertionIds()).forEach(id -> truth.retractAssertion(id, source));
                paths.clear(); universalIndex.clear(); groundEvictionQueue.clear();
            } finally { lock.writeLock().unlock(); }
        }

        Stream<Assertion> findUnifiableAssertions(KifTerm queryTerm) { return paths.findUnifiableAssertions(queryTerm); }
        Stream<Assertion> findInstancesOf(KifTerm queryPattern) { return paths.findInstancesOf(queryPattern); }
        List<Assertion> findRelevantUniversalAssertions(KifAtom predicate) { return universalIndex.getOrDefault(predicate, Set.of()).stream().map(truth::getAssertion).flatMap(Optional::stream).filter(Assertion::isActive).filter(a -> a.kb.equals(id)).toList(); }
        private boolean isSubsumedInternal(KifTerm term, boolean isNegated) {
            return paths.findGeneralizationsOf(term)
                    .filter(a -> a.type == AssertionType.GROUND || a.type == AssertionType.SKOLEMIZED)
                    .anyMatch(candidate -> candidate.negated == isNegated && Unifier.match(candidate.getEffectiveTerm(), term, Map.of()) != null);
        }
        private Optional<Assertion> findExactMatchInternal(KifList kif) { return paths.findInstancesOf(kif).filter(a -> kif.equals(a.kif)).findFirst(); }
        private void enforceKbCapacityInternal(String source) {
            while (getAssertionCount() >= capacity && !groundEvictionQueue.isEmpty()) {
                ofNullable(groundEvictionQueue.poll())
                        .flatMap(truth::getAssertion)
                        .filter(a -> a.kb.equals(id) && (a.type == AssertionType.GROUND || a.type == AssertionType.SKOLEMIZED))
                        .ifPresent(toEvict -> {
                            truth.retractAssertion(toEvict.id, source + "-evict");
                            events.emit(new AssertionEvictedEvent(toEvict, id));
                        });
            }
        }
        private void checkResourceThresholds() {
            var currentSize = getAssertionCount(); var warnT = capacity * KB_SIZE_THRESHOLD_WARN_PERCENT / 100; var haltT = capacity * KB_SIZE_THRESHOLD_HALT_PERCENT / 100;
            if (currentSize >= haltT) System.err.printf("KB CRITICAL (KB: %s): Size %d/%d (%.1f%%)%n", id, currentSize, capacity, 100.0 * currentSize / capacity);
            else if (currentSize >= warnT) System.out.printf("KB WARNING (KB: %s): Size %d/%d (%.1f%%)%n", id, currentSize, capacity, 100.0 * currentSize / capacity);
        }
        void handleExternalRetraction(Assertion a) {
            lock.writeLock().lock();
            try {
                switch (a.type) {
                    case GROUND, SKOLEMIZED -> { paths.remove(a); groundEvictionQueue.remove(a.id); }
                    case UNIVERSAL -> a.getReferencedPredicates().forEach(pred -> universalIndex.computeIfPresent(pred, (_, ids) -> { ids.remove(a.id); return ids.isEmpty() ? null : ids; }));
                }
            } finally { lock.writeLock().unlock(); }
        }
        void handleExternalStatusChange(Assertion a) {
            lock.writeLock().lock();
            try {
                if (a.isActive()) {
                    switch (a.type) {
                        case GROUND, SKOLEMIZED -> paths.add(a);
                        case UNIVERSAL -> a.getReferencedPredicates().forEach(pred -> universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(a.id));
                    }
                } else handleExternalRetraction(a);
            } finally { lock.writeLock().unlock(); }
        }
    }

    static class Cognition {
        final Cog cog;
        private final ConcurrentMap<String, Knowledge> noteKbs = new ConcurrentHashMap<>();
        private final Knowledge globalKb;
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final Events events;
        private final Truths tms;
        private final Skolemizer skolemizer;
        private final Operators operators;

        Cognition(int globalKbCapacity, Events events, Truths tms, Skolemizer skolemizer, Operators operators, Cog cog) {
            this.cog = cog; this.events = events; this.tms = tms; this.skolemizer = skolemizer; this.operators = operators;
            this.globalKb = new Knowledge(GLOBAL_KB_NOTE_ID, globalKbCapacity, events, tms);
        }
        public Knowledge kb(@Nullable String noteId) { return (noteId == null || GLOBAL_KB_NOTE_ID.equals(noteId)) ? globalKb : noteKbs.computeIfAbsent(noteId, id -> new Knowledge(id, globalKb.capacity, events, tms)); }
        public Knowledge kbGlobal() { return globalKb; }
        public Map<String, Knowledge> getAllNoteKbs() { return Collections.unmodifiableMap(noteKbs); }
        public Set<String> getAllNoteIds() { return Collections.unmodifiableSet(noteKbs.keySet()); }
        public Set<Rule> rules() { return Collections.unmodifiableSet(rules); }
        public int ruleCount() { return rules.size(); }
        public int kbCount() { return (int) tms.getAllActiveAssertions().stream().filter(a -> a.kb.equals(GLOBAL_KB_NOTE_ID) || noteKbs.containsKey(a.kb)).count(); }
        public int kbTotalCapacity() { return globalKb.capacity + noteKbs.size() * globalKb.capacity; }
        public Truths truth() { return tms; }
        public Skolemizer skolemizer() { return skolemizer; }
        public Operators operators() { return operators; }
        public boolean addRule(Rule rule) { var added = rules.add(rule); if (added) events.emit(new RuleAddedEvent(rule)); return added; }
        public boolean removeRule(Rule rule) { var removed = rules.remove(rule); if (removed) events.emit(new RuleRemovedEvent(rule)); return removed; }
        public boolean removeRule(KifList ruleForm) { return rules.stream().filter(r -> r.form.equals(ruleForm)).findFirst().map(this::removeRule).orElse(false); }
        public void removeNoteKb(String noteId, String source) { ofNullable(noteKbs.remove(noteId)).ifPresent(kb -> kb.clear(source)); }
        public void clearAll() { globalKb.clear("clearAll"); noteKbs.values().forEach(kb -> kb.clear("clearAll")); noteKbs.clear(); rules.clear(); }
        public Optional<Assertion> findAssertionByIdAcrossKbs(String assertionId) { return tms.getAssertion(assertionId); }

        @Nullable public String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null; var firstFound = false; Set<String> visited = new HashSet<>(); Queue<String> toCheck = new LinkedList<>(supportIds);
            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll(); if (currentId == null || !visited.add(currentId)) continue;
                var assertionOpt = findAssertionByIdAcrossKbs(currentId);
                if (assertionOpt.isPresent()) {
                    var assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (!firstFound) { commonId = assertion.sourceNoteId(); firstFound = true; }
                        else if (!commonId.equals(assertion.sourceNoteId())) return null;
                    } else if (assertion.derivationDepth() > 0 && !assertion.justificationIds().isEmpty()) {
                        assertion.justificationIds().forEach(toCheck::offer);
                    }
                }
            }
            return commonId;
        }
        public double calculateDerivedPri(Set<String> supportIds, double basePri) { return supportIds.isEmpty() ? basePri : supportIds.stream().map(this::findAssertionByIdAcrossKbs).flatMap(Optional::stream).mapToDouble(Assertion::pri).min().orElse(basePri) * DERIVED_PRIORITY_DECAY; }
        public int calculateDerivedDepth(Set<String> supportIds) { return supportIds.stream().map(this::findAssertionByIdAcrossKbs).flatMap(Optional::stream).mapToInt(Assertion::derivationDepth).max().orElse(-1); }
        public KifList performSkolemization(KifList body, Collection<KifVar> existentialVars, Map<KifVar, KifTerm> contextBindings) { return skolemizer.skolemize(new KifList(KifAtom.of(KIF_OP_EXISTS), new KifList(new ArrayList<>(existentialVars)), body), contextBindings); }
        public KifList simplifyLogicalTerm(KifList term) {
            final var MAX_DEPTH = 5; var current = term;
            for (var depth = 0; depth < MAX_DEPTH; depth++) {
                var next = simplifyLogicalTermOnce(current);
                if (next.equals(current)) return current;
                current = next;
            }
            if (!term.equals(current)) System.err.println("Warning: Simplification depth limit reached for: " + term.toKif());
            return current;
        }
        private KifList simplifyLogicalTermOnce(KifList term) {
            if (term.op().filter(KIF_OP_NOT::equals).isPresent() && term.size() == 2 && term.get(1) instanceof KifList nl && nl.op().filter(KIF_OP_NOT::equals).isPresent() && nl.size() == 2 && nl.get(1) instanceof KifList inner)
                return simplifyLogicalTermOnce(inner);
            var changed = new boolean[]{false};
            var newTerms = term.terms().stream().map(subTerm -> {
                var simplifiedSub = (subTerm instanceof KifList sl) ? simplifyLogicalTermOnce(sl) : subTerm;
                if (!simplifiedSub.equals(subTerm)) changed[0] = true;
                return simplifiedSub;
            }).toList();
            return changed[0] ? new KifList(newTerms) : term;
        }
        @Nullable public Assertion tryCommitAssertion(PotentialAssertion pa, String source) { return kb(pa.sourceNoteId()).commit(pa, source); }
    }

    static class Note {
        final String id; String title; String text;
        Note(String id, String title, String text) { this.id = requireNonNull(id); this.title = requireNonNull(title); this.text = requireNonNull(text); }
        @Override public String toString() { return title; }
        @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    static class BasicTMS implements Truths {
        private final Events events;
        private final ConcurrentMap<String, Assertion> assertions = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<String>> justifications = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<String>> dependents = new ConcurrentHashMap<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        BasicTMS(Events e) { this.events = e; }

        @Override public SupportTicket addAssertion(Assertion assertion, Set<String> justificationIds, String source) {
            lock.writeLock().lock();
            try {
                if (assertions.containsKey(assertion.id)) return null;
                var assertionToAdd = assertion.withStatus(true);
                var supportingAssertions = justificationIds.stream().map(assertions::get).filter(Objects::nonNull).toList();
                if (!justificationIds.isEmpty() && supportingAssertions.size() != justificationIds.size()) {
                    System.err.printf("TMS Warning: Justification missing for %s. Supporters: %s, Found: %s%n", assertion.id, justificationIds, supportingAssertions.stream().map(Assertion::id).toList());
                    return null;
                }
                if (!justificationIds.isEmpty() && supportingAssertions.stream().noneMatch(Assertion::isActive)) assertionToAdd = assertionToAdd.withStatus(false);

                assertions.put(assertionToAdd.id, assertionToAdd);
                justifications.put(assertionToAdd.id, Set.copyOf(justificationIds));
                var finalAssertionToAdd = assertionToAdd;
                justificationIds.forEach(supporterId -> dependents.computeIfAbsent(supporterId, k -> ConcurrentHashMap.newKeySet()).add(finalAssertionToAdd.id));

                if (!assertionToAdd.isActive()) events.emit(new AssertionStatusChangedEvent(assertionToAdd.id, false, assertionToAdd.kb));
                else checkForContradictions(assertionToAdd);
                return new SupportTicket(generateId(ID_PREFIX_TICKET), assertionToAdd.id);
            } finally { lock.writeLock().unlock(); }
        }

        @Override public void retractAssertion(String assertionId, String source) { lock.writeLock().lock(); try { retractInternal(assertionId, source, new HashSet<>()); } finally { lock.writeLock().unlock(); } }
        private void retractInternal(String assertionId, String source, Set<String> visited) {
            if (!visited.add(assertionId)) return;
            var assertion = assertions.remove(assertionId); if (assertion == null) return;
            justifications.remove(assertionId);
            assertion.justificationIds().forEach(supporterId -> ofNullable(dependents.get(supporterId)).ifPresent(deps -> deps.remove(assertionId)));
            var depsToProcess = new HashSet<>(dependents.remove(assertionId));
            if (assertion.isActive()) events.emit(new AssertionRetractedEvent(assertion, assertion.kb, source));
            else events.emit(new AssertionStatusChangedEvent(assertion.id, false, assertion.kb));
            depsToProcess.forEach(depId -> updateStatus(depId, visited));
        }
        private void updateStatus(String assertionId, Set<String> visited) {
            if (!visited.add(assertionId)) return;
            var assertion = assertions.get(assertionId); if (assertion == null) return;
            var just = justifications.getOrDefault(assertionId, Set.of());
            var supportActive = just.stream().map(assertions::get).filter(Objects::nonNull).allMatch(Assertion::isActive);
            var newActiveStatus = !just.isEmpty() && supportActive;
            if (newActiveStatus != assertion.isActive()) {
                var updatedAssertion = assertion.withStatus(newActiveStatus);
                assertions.put(assertionId, updatedAssertion);
                events.emit(new AssertionStatusChangedEvent(assertionId, newActiveStatus, assertion.kb));
                if (newActiveStatus) checkForContradictions(updatedAssertion);
                dependents.getOrDefault(assertionId, Set.of()).forEach(depId -> updateStatus(depId, visited));
            }
        }
        @Override public Set<String> getActiveSupport(String assertionId) { lock.readLock().lock(); try { return justifications.getOrDefault(assertionId, Set.of()).stream().filter(this::isActive).collect(Collectors.toSet()); } finally { lock.readLock().unlock(); } }
        @Override public boolean isActive(String assertionId) { lock.readLock().lock(); try { return ofNullable(assertions.get(assertionId)).map(Assertion::isActive).orElse(false); } finally { lock.readLock().unlock(); } }
        @Override public Optional<Assertion> getAssertion(String assertionId) { lock.readLock().lock(); try { return ofNullable(assertions.get(assertionId)); } finally { lock.readLock().unlock(); } }
        @Override public Collection<Assertion> getAllActiveAssertions() { lock.readLock().lock(); try { return assertions.values().stream().filter(Assertion::isActive).toList(); } finally { lock.readLock().unlock(); } }

        private void checkForContradictions(Assertion newlyActive) {
            if (!newlyActive.isActive()) return;
            var oppositeForm = newlyActive.negated ? newlyActive.getEffectiveTerm() : new KifList(KifAtom.of(KIF_OP_NOT), newlyActive.kif);
            if (!(oppositeForm instanceof KifList)) return;
            findMatchingAssertion((KifList) oppositeForm, newlyActive.kb, !newlyActive.negated)
                    .ifPresent(match -> {
                        System.err.printf("TMS Contradiction Detected in KB %s: %s and %s%n", newlyActive.kb, newlyActive.id, match.id);
                        events.emit(new ContradictionDetectedEvent(Set.of(newlyActive.id, match.id), newlyActive.kb));
                    });
        }
        private Optional<Assertion> findMatchingAssertion(KifList formToMatch, String kbId, boolean matchIsNegated) {
            lock.readLock().lock();
            try {
                return assertions.values().stream()
                        .filter(Assertion::isActive)
                        .filter(a -> a.negated == matchIsNegated && a.kb.equals(kbId) && a.kif.equals(formToMatch))
                        .findFirst();
            } finally { lock.readLock().unlock(); }
        }
        @Override public void resolveContradiction(Contradiction contradiction, ResolutionStrategy strategy) { System.err.println("Contradiction resolution not implemented. Strategy: " + strategy + ", Conflicting: " + contradiction.conflictingAssertionIds()); }
        @Override public Set<Contradiction> findContradictions() { return Set.of(); }
    }

    abstract static class BasePlugin implements Plugin {
        protected final String id = generateId(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("Plugin", "").toLowerCase() + "_");
        protected Events events;
        protected Cognition context;
        @Override public String id() { return id; }
        @Override public void start(Events e, Cognition ctx) { this.events = e; this.context = ctx; }
        @Override public void stop() {}
        protected void publish(CogEvent event) { if (events != null) events.emit(event); }
        protected Knowledge getKb(@Nullable String noteId) { return context.kb(noteId); }
    }

    static class InputProcessingPlugin extends BasePlugin {
        @Override public void start(Events e, Cognition ctx) { super.start(e, ctx); e.on(ExternalInputEvent.class, this::handleExternalInput); }
        private void handleExternalInput(ExternalInputEvent event) {
            switch (event.term()) {
                case KifList list when !list.terms().isEmpty() -> list.op().ifPresentOrElse(
                        op -> { switch (op) {
                            case KIF_OP_IMPLIES, KIF_OP_EQUIV -> handleRuleInput(list, event.sourceId());
                            case KIF_OP_EXISTS -> handleExistsInput(list, event.sourceId(), event.targetNoteId());
                            case KIF_OP_FORALL -> handleForallInput(list, event.sourceId(), event.targetNoteId());
                            default -> handleStandardAssertionInput(list, event.sourceId(), event.targetNoteId()); }
                        }, () -> handleStandardAssertionInput(list, event.sourceId(), event.targetNoteId())
                );
                case KifTerm term when !(term instanceof KifList) -> System.err.println("Warning: Ignoring non-list top-level term from " + event.sourceId() + ": " + term.toKif());
                default -> {}
            }
        }
        private void handleRuleInput(KifList list, String sourceId) {
            try {
                var rule = Rule.parseRule(generateId(ID_PREFIX_RULE), list, DEFAULT_RULE_PRIORITY);
                context.addRule(rule);
                if (KIF_OP_EQUIV.equals(list.op().orElse(""))) {
                    var revList = new KifList(KifAtom.of(KIF_OP_IMPLIES), list.get(2), list.get(1));
                    var revRule = Rule.parseRule(generateId(ID_PREFIX_RULE), revList, DEFAULT_RULE_PRIORITY);
                    context.addRule(revRule);
                }
            } catch (IllegalArgumentException e) { System.err.println("Invalid rule format ignored (" + sourceId + "): " + list.toKif() + " | Error: " + e.getMessage()); }
        }
        private void handleStandardAssertionInput(KifList list, String sourceId, @Nullable String targetNoteId) {
            if (list.containsVar()) { System.err.println("Warning: Non-ground assertion input ignored (" + sourceId + "): " + list.toKif()); return; }
            var isNeg = list.op().filter(KIF_OP_NOT::equals).isPresent();
            if (isNeg && list.size() != 2) { System.err.println("Invalid 'not' format ignored (" + sourceId + "): " + list.toKif()); return; }
            var isEq = !isNeg && list.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && list.size() == 3 && list.get(1).weight() > list.get(2).weight();
            var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pri = (sourceId.startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
            var pa = new PotentialAssertion(list, pri, Set.of(), sourceId, isEq, isNeg, isOriented, targetNoteId, type, List.of(), 0);
            context.tryCommitAssertion(pa, sourceId);
        }
        private void handleExistsInput(KifList existsExpr, String sourceId, @Nullable String targetNoteId) {
            if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) { System.err.println("Invalid 'exists' format ignored (" + sourceId + "): " + existsExpr.toKif()); return; }
            var vars = KifTerm.collectSpecVars(existsExpr.get(1));
            if (vars.isEmpty()) { publish(new ExternalInputEvent(existsExpr.get(2), sourceId + "-existsBody", targetNoteId)); return; }
            var skolemBody = context.performSkolemization(body, vars, Map.of());
            var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
            var pri = (sourceId.startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + skolemBody.weight());
            var pa = new PotentialAssertion(skolemBody, pri, Set.of(), sourceId + "-skolemized", isEq, isNeg, isOriented, targetNoteId, AssertionType.SKOLEMIZED, List.of(), 0);
            context.tryCommitAssertion(pa, sourceId + "-skolemized");
        }
        private void handleForallInput(KifList forallExpr, String sourceId, @Nullable String targetNoteId) {
            if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) { System.err.println("Invalid 'forall' format ignored (" + sourceId + "): " + forallExpr.toKif()); return; }
            var vars = KifTerm.collectSpecVars(forallExpr.get(1));
            if (vars.isEmpty()) { publish(new ExternalInputEvent(forallExpr.get(2), sourceId + "-forallBody", targetNoteId)); return; }
            if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) { handleRuleInput(body, sourceId); }
            else {
                System.out.println("Storing 'forall' as universal fact from " + sourceId + ": " + forallExpr.toKif());
                var pri = (sourceId.startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + forallExpr.weight());
                var pa = new PotentialAssertion(forallExpr, pri, Set.of(), sourceId, false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), 0);
                context.tryCommitAssertion(pa, sourceId);
            }
        }
    }

    static class RetractionPlugin extends BasePlugin {
        @Override public void start(Events e, Cognition ctx) {
            super.start(e, ctx);
            e.on(RetractionRequestEvent.class, this::handleRetractionRequest);
            e.on(AssertionRetractedEvent.class, this::handleExternalRetraction);
            e.on(AssertionStatusChangedEvent.class, this::handleExternalStatusChange);
        }
        private void handleRetractionRequest(RetractionRequestEvent event) {
            final var s = event.sourceId();
            switch (event.type) {
                case BY_ID -> {
                    context.truth().retractAssertion(event.target(), s);
                    System.out.printf("Retraction requested for [%s] by %s in KB '%s'.%n", event.target(), s, getKb(event.targetNoteId()).id);
                }
                case BY_NOTE -> {
                    var noteId = event.target();
                    if (CONFIG_NOTE_ID.equals(noteId)) { System.err.println("Attempted to retract config note " + noteId + " from " + s + ". Operation ignored."); return; }
                    var kb = context.getAllNoteKbs().get(noteId);
                    if (kb != null) {
                        var ids = kb.getAllAssertionIds();
                        if (!ids.isEmpty()) {
                            System.out.printf("Initiating retraction of %d assertions for note %s from %s.%n", ids.size(), noteId, s);
                            new HashSet<>(ids).forEach(id -> context.truth().retractAssertion(id, s));
                        } else System.out.printf("Retraction by Note ID %s from %s: No associated assertions found in its KB.%n", noteId, s);
                        context.removeNoteKb(noteId, s);
                        publish(new RemovedEvent(new Note(noteId, "Removed", "")));
                    } else System.out.printf("Retraction by Note ID %s from %s failed: Note KB not found.%n", noteId, s);
                }
                case BY_RULE_FORM -> {
                    try {
                        var terms = KifParser.parseKif(event.target());
                        if (terms.size() == 1 && terms.getFirst() instanceof KifList rf) {
                            var removed = context.removeRule(rf);
                            System.out.println("Retract rule from " + s + ": " + (removed ? "Success" : "No match found") + " for: " + rf.toKif());
                        } else System.err.println("Retract rule from " + s + ": Input is not a single valid rule KIF list: " + event.target());
                    } catch (ParseException e) { System.err.println("Retract rule from " + s + ": Parse error: " + e.getMessage()); }
                }
            }
        }
        private void handleExternalRetraction(AssertionRetractedEvent event) { ofNullable(getKb(event.getKbId())).ifPresent(kb -> kb.handleExternalRetraction(event.assertion())); }
        private void handleExternalStatusChange(AssertionStatusChangedEvent event) { context.truth().getAssertion(event.assertionId()).flatMap(a -> ofNullable(getKb(event.kbId())).map(kb -> Map.entry(kb, a))).ifPresent(e -> e.getKey().handleExternalStatusChange(e.getValue())); }
    }

    abstract static class BaseReasonerPlugin implements ReasonerPlugin {
        protected final String id = generateId(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("ReasonerPlugin", "").toLowerCase() + "_");
        protected ReasonerContext context;
        @Override public String id() { return id; }
        @Override public void initialize(ReasonerContext ctx) { this.context = ctx; }
        @Override public void stop() {}
        protected void publish(CogEvent event) { if (context != null && context.events() != null) context.events().emit(event); }
        protected Knowledge getKb(@Nullable String noteId) { return context.getKb(noteId); }
        protected Truths getTMS() { return context.getTMS(); }
        protected Cognition getCogNoteContext() { return context.cognition(); }
        protected int getMaxDerivationDepth() { return context.getConfig().reasoningDepthLimit(); }
        @Nullable protected Assertion tryCommit(PotentialAssertion pa, String source) { return getCogNoteContext().tryCommitAssertion(pa, source); }
        @Override public CompletableFuture<Answer> executeQuery(Query query) { return CompletableFuture.completedFuture(Answer.failure(query.id)); }
        @Override public Set<QueryType> getSupportedQueryTypes() { return Set.of(); }
    }

    static class ForwardChainingReasonerPlugin extends BaseReasonerPlugin {
        @Override public void initialize(ReasonerContext ctx) { super.initialize(ctx); ctx.events().on(AssertionAddedEvent.class, this::handleAssertionAdded); }
        @Override public Set<Feature> getSupportedFeatures() { return Set.of(Feature.FORWARD_CHAINING); }
        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newAssertion = event.assertion(); var sourceKbId = event.getKbId();
            if (!newAssertion.isActive() || (newAssertion.type != AssertionType.GROUND && newAssertion.type != AssertionType.SKOLEMIZED)) return;
            context.rules().forEach(rule -> rule.antecedents().forEach(clause -> {
                var neg = (clause instanceof KifList l && l.op().filter(KIF_OP_NOT::equals).isPresent());
                if (neg == newAssertion.negated) {
                    var pattern = neg ? ((KifList) clause).get(1) : clause;
                    ofNullable(Unifier.unify(pattern, newAssertion.getEffectiveTerm(), Map.of()))
                            .ifPresent(bindings -> findMatchesRecursive(rule, rule.antecedents(), bindings, Set.of(newAssertion.id), sourceKbId).forEach(match -> processDerivedAssertion(rule, match)));
                }
            }));
        }
        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifTerm> remaining, Map<KifVar, KifTerm> bindings, Set<String> support, String currentKbId) {
            if (remaining.isEmpty()) return Stream.of(new MatchResult(bindings, support));
            var clause = Unifier.substFully(remaining.getFirst(), bindings); var nextRemaining = remaining.subList(1, remaining.size());
            var neg = (clause instanceof KifList l && l.op().filter(KIF_OP_NOT::equals).isPresent());
            var pattern = neg ? ((KifList) clause).get(1) : clause;
            if (!(pattern instanceof KifList)) return Stream.empty();
            var currentKb = getKb(currentKbId); var globalKb = context.getKb(GLOBAL_KB_NOTE_ID);
            return Stream.concat(currentKb.findUnifiableAssertions(pattern), (!currentKb.id.equals(GLOBAL_KB_NOTE_ID)) ? globalKb.findUnifiableAssertions(pattern) : Stream.empty())
                    .distinct().filter(c -> c.negated == neg)
                    .flatMap(c -> ofNullable(Unifier.unify(pattern, c.getEffectiveTerm(), bindings))
                            .map(newB -> findMatchesRecursive(rule, nextRemaining, newB, Stream.concat(support.stream(), Stream.of(c.id)).collect(Collectors.toSet()), c.kb))
                            .orElse(Stream.empty()));
        }
        private void processDerivedAssertion(Rule rule, MatchResult result) {
            var consequent = Unifier.subst(rule.consequent(), result.bindings()); if (consequent == null) return;
            var simplified = (consequent instanceof KifList kl) ? getCogNoteContext().simplifyLogicalTerm(kl) : consequent;
            var targetNoteId = getCogNoteContext().findCommonSourceNodeId(result.supportIds());
            switch (simplified) {
                case KifList derived when derived.op().filter(KIF_OP_AND::equals).isPresent() -> processDerivedConjunction(rule, derived, result, targetNoteId);
                case KifList derived when derived.op().filter(KIF_OP_FORALL::equals).isPresent() -> processDerivedForall(rule, derived, result, targetNoteId);
                case KifList derived when derived.op().filter(KIF_OP_EXISTS::equals).isPresent() -> processDerivedExists(rule, derived, result, targetNoteId);
                case KifList derived -> processDerivedStandard(rule, derived, result, targetNoteId);
                case KifTerm term when !(term instanceof KifVar) -> System.err.println("Warning: Rule " + rule.id + " derived non-list/non-var consequent: " + term.toKif());
                default -> {}
            }
        }
        private void processDerivedConjunction(Rule rule, KifList conj, MatchResult result, @Nullable String targetNoteId) {
            conj.terms().stream().skip(1).forEach(term -> {
                var simp = (term instanceof KifList kl) ? getCogNoteContext().simplifyLogicalTerm(kl) : term;
                if (simp instanceof KifList c) processDerivedAssertion(new Rule(rule.id, rule.form, rule.antecedent(), c, rule.pri, rule.antecedents()), result);
                else if (!(simp instanceof KifVar)) System.err.println("Warning: Rule " + rule.id + " derived (and ...) with non-list/non-var conjunct: " + term.toKif());
            });
        }
        private void processDerivedForall(Rule rule, KifList forall, MatchResult result, @Nullable String targetNoteId) {
            if (forall.size() != 3 || !(forall.get(1) instanceof KifList || forall.get(1) instanceof KifVar) || !(forall.get(2) instanceof KifList body)) return;
            var vars = KifTerm.collectSpecVars(forall.get(1)); if (vars.isEmpty()) { processDerivedStandard(rule, body, result, targetNoteId); return; }
            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1; if (depth > getMaxDerivationDepth()) return;
            if (body.op().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) {
                try {
                    var pri = getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri);
                    var derivedRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "derived_"), body, pri);
                    getCogNoteContext().addRule(derivedRule);
                    if (KIF_OP_EQUIV.equals(body.op().orElse(""))) {
                        var revList = new KifList(KifAtom.of(KIF_OP_IMPLIES), body.get(2), body.get(1));
                        var revRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "derived_"), revList, pri);
                        getCogNoteContext().addRule(revRule);
                    }
                } catch (IllegalArgumentException e) { System.err.println("Invalid derived rule format ignored: " + body.toKif() + " from rule " + rule.id + " | Error: " + e.getMessage()); }
            } else {
                var pa = new PotentialAssertion(forall, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri), result.supportIds(), rule.id, false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(vars), depth);
                tryCommit(pa, rule.id);
            }
        }
        private void processDerivedExists(Rule rule, KifList exists, MatchResult result, @Nullable String targetNoteId) {
            if (exists.size() != 3 || !(exists.get(1) instanceof KifList || exists.get(1) instanceof KifVar) || !(exists.get(2) instanceof KifList body)) { System.err.println("Rule " + rule.id + " derived invalid 'exists' structure: " + exists.toKif()); return; }
            var vars = KifTerm.collectSpecVars(exists.get(1)); if (vars.isEmpty()) { processDerivedStandard(rule, body, result, targetNoteId); return; }
            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1; if (depth > getMaxDerivationDepth()) return;
            var skolemBody = getCogNoteContext().performSkolemization(body, vars, result.bindings());
            var isNeg = skolemBody.op().filter(KIF_OP_NOT::equals).isPresent(); var isEq = !isNeg && skolemBody.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemBody.size() == 3 && skolemBody.get(1).weight() > skolemBody.get(2).weight();
            var pa = new PotentialAssertion(skolemBody, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri), result.supportIds(), rule.id, isEq, isNeg, isOriented, targetNoteId, AssertionType.SKOLEMIZED, List.of(), depth);
            tryCommit(pa, rule.id);
        }
        private void processDerivedStandard(Rule rule, KifList derived, MatchResult result, @Nullable String targetNoteId) {
            if (derived.containsVar() || Cog.isTrivial(derived)) return;
            var depth = getCogNoteContext().calculateDerivedDepth(result.supportIds()) + 1;
            if (depth > getMaxDerivationDepth() || derived.weight() > MAX_DERIVED_TERM_WEIGHT) return;
            var isNeg = derived.op().filter(KIF_OP_NOT::equals).isPresent();
            if (isNeg && derived.size() != 2) { System.err.println("Rule " + rule.id + " derived invalid 'not': " + derived.toKif()); return; }
            var isEq = !isNeg && derived.op().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && derived.size() == 3 && derived.get(1).weight() > derived.get(2).weight();
            var type = derived.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pa = new PotentialAssertion(derived, getCogNoteContext().calculateDerivedPri(result.supportIds(), rule.pri), result.supportIds(), rule.id, isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
            tryCommit(pa, rule.id);
        }
        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {}
    }

    static class RewriteRuleReasonerPlugin extends BaseReasonerPlugin {
        @Override public void initialize(ReasonerContext ctx) { super.initialize(ctx); ctx.events().on(AssertionAddedEvent.class, this::handleAssertionAdded); }
        @Override public Set<Feature> getSupportedFeatures() { return Set.of(Feature.REWRITE_RULES); }
        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newA = event.assertion(); var kbId = event.getKbId();
            if (!newA.isActive() || (newA.type != AssertionType.GROUND && newA.type != AssertionType.SKOLEMIZED)) return;
            var kb = getKb(kbId); var globalKb = context.getKb(GLOBAL_KB_NOTE_ID);
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            if (newA.isEquality() && newA.isOrientedEquality() && !newA.negated && newA.kif.size() == 3) {
                var lhs = newA.kif.get(1);
                relevantKbs.flatMap(k -> k.findUnifiableAssertions(lhs)).distinct()
                        .filter(t -> !t.id.equals(newA.id) && Unifier.match(lhs, t.getEffectiveTerm(), Map.of()) != null)
                        .forEach(t -> applyRewrite(newA, t));
            }
            relevantKbs.flatMap(k -> k.getAllAssertions().stream()).distinct()
                    .filter(Assertion::isActive)
                    .filter(r -> r.isEquality() && r.isOrientedEquality() && !r.negated && r.kif.size() == 3 && !r.id.equals(newA.id))
                    .filter(r -> Unifier.match(r.kif.get(1), newA.getEffectiveTerm(), Map.of()) != null)
                    .forEach(r -> applyRewrite(r, newA));
        }
        private void applyRewrite(Assertion ruleA, Assertion targetA) {
            var lhs = ruleA.kif.get(1); var rhs = ruleA.kif.get(2);
            Unifier.rewrite(targetA.kif, lhs, rhs)
                    .filter(rw -> rw instanceof KifList && !rw.equals(targetA.kif)).map(KifList.class::cast).filter(Predicate.not(Cog::isTrivial))
                    .ifPresent(rwList -> {
                        var support = Stream.concat(targetA.justificationIds().stream(), Stream.of(targetA.id, ruleA.id)).collect(Collectors.toSet());
                        var depth = Math.max(targetA.derivationDepth(), ruleA.derivationDepth()) + 1;
                        if (depth > getMaxDerivationDepth() || rwList.weight() > MAX_DERIVED_TERM_WEIGHT) return;
                        var isNeg = rwList.op().filter(KIF_OP_NOT::equals).isPresent(); var isEq = !isNeg && rwList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                        var isOriented = isEq && rwList.size() == 3 && rwList.get(1).weight() > rwList.get(2).weight();
                        var type = rwList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                        var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
                        var pa = new PotentialAssertion(rwList, getCogNoteContext().calculateDerivedPri(support, (ruleA.pri + targetA.pri) / 2.0), support, ruleA.id, isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                        tryCommit(pa, ruleA.id);
                    });
        }
    }

    static class UniversalInstantiationReasonerPlugin extends BaseReasonerPlugin {
        @Override public void initialize(ReasonerContext ctx) { super.initialize(ctx); ctx.events().on(AssertionAddedEvent.class, this::handleAssertionAdded); }
        @Override public Set<Feature> getSupportedFeatures() { return Set.of(Feature.UNIVERSAL_INSTANTIATION); }
        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newA = event.assertion(); var kbId = event.getKbId(); var kb = getKb(kbId); var globalKb = context.getKb(GLOBAL_KB_NOTE_ID);
            if (!newA.isActive()) return;
            var relevantKbs = Stream.of(kb, globalKb).filter(Objects::nonNull).distinct();
            if ((newA.type == AssertionType.GROUND || newA.type == AssertionType.SKOLEMIZED) && newA.kif.get(0) instanceof KifAtom pred) {
                relevantKbs.flatMap(k -> k.findRelevantUniversalAssertions(pred).stream()).distinct()
                        .filter(u -> u.derivationDepth() < getMaxDerivationDepth())
                        .forEach(u -> tryInstantiate(u, newA));
            } else if (newA.type == AssertionType.UNIVERSAL && newA.derivationDepth() < getMaxDerivationDepth()) {
                ofNullable(newA.getEffectiveTerm()).filter(KifList.class::isInstance).map(KifList.class::cast)
                        .flatMap(KifList::op).map(KifAtom::of)
                        .ifPresent(pred -> relevantKbs.flatMap(k -> k.getAllAssertions().stream()).distinct()
                                .filter(Assertion::isActive).filter(g -> g.type == AssertionType.GROUND || g.type == AssertionType.SKOLEMIZED)
                                .filter(g -> g.getReferencedPredicates().contains(pred))
                                .forEach(g -> tryInstantiate(newA, g)));
            }
        }
        private void tryInstantiate(Assertion uniA, Assertion groundA) {
            var formula = uniA.getEffectiveTerm(); var vars = uniA.quantifiedVars(); if (vars.isEmpty()) return;
            findSubExpressionMatches(formula, groundA.kif)
                    .filter(bindings -> bindings.keySet().containsAll(vars))
                    .forEach(bindings -> {
                        var instFormula = Unifier.subst(formula, bindings);
                        if (instFormula instanceof KifList instList && !instFormula.containsVar() && !Cog.isTrivial(instList)) {
                            var support = Stream.concat(Stream.of(groundA.id, uniA.id), Stream.concat(groundA.justificationIds.stream(), uniA.justificationIds.stream())).collect(Collectors.toSet());
                            var pri = getCogNoteContext().calculateDerivedPri(support, (groundA.pri + uniA.pri) / 2.0);
                            var depth = Math.max(groundA.derivationDepth(), uniA.derivationDepth()) + 1;
                            if (depth <= getMaxDerivationDepth()) {
                                var isNeg = instList.op().filter(KIF_OP_NOT::equals).isPresent(); var isEq = !isNeg && instList.op().filter(KIF_OP_EQUAL::equals).isPresent();
                                var isOriented = isEq && instList.size() == 3 && instList.get(1).weight() > instList.get(2).weight();
                                var type = instList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                                var targetNoteId = getCogNoteContext().findCommonSourceNodeId(support);
                                var pa = new PotentialAssertion(instList, pri, support, uniA.id, isEq, isNeg, isOriented, targetNoteId, type, List.of(), depth);
                                tryCommit(pa, uniA.id);
                            }
                        }
                    });
        }
        private Stream<Map<KifVar, KifTerm>> findSubExpressionMatches(KifTerm expr, KifTerm target) {
            return Stream.concat(
                    ofNullable(Unifier.match(expr, target, Map.of())).stream(),
                    (expr instanceof KifList l) ? l.terms().stream().flatMap(sub -> findSubExpressionMatches(sub, target)) : Stream.empty()
            );
        }
    }

    static class BackwardChainingReasonerPlugin extends BaseReasonerPlugin {
        @Override public Set<Feature> getSupportedFeatures() { return Set.of(Feature.BACKWARD_CHAINING, Feature.OPERATOR_SUPPORT); }
        @Override public Set<QueryType> getSupportedQueryTypes() { return Set.of(QueryType.ASK_BINDINGS, QueryType.ASK_TRUE_FALSE); }
        @Override public CompletableFuture<Answer> executeQuery(Query query) {
            return CompletableFuture.supplyAsync(() -> {
                var results = new ArrayList<Map<KifVar, KifTerm>>();
                var maxDepth = (Integer) query.parameters().getOrDefault("maxDepth", MAX_BACKWARD_CHAIN_DEPTH);
                try {
                    prove(query.pattern(), query.targetKbId(), Map.of(), maxDepth, new HashSet<>()).forEach(results::add);
                    return Answer.success(query.id, results);
                } catch (Exception e) {
                    System.err.println("Backward chaining query failed: " + e.getMessage()); e.printStackTrace();
                    return Answer.error(query.id, e.getMessage());
                }
            }, context.events().exe);
        }
        private Stream<Map<KifVar, KifTerm>> prove(KifTerm goal, @Nullable String kbId, Map<KifVar, KifTerm> bindings, int depth, Set<KifTerm> proofStack) {
            if (depth <= 0) return Stream.empty();
            var currentGoal = Unifier.substFully(goal, bindings); if (!proofStack.add(currentGoal)) return Stream.empty();
            Stream<Map<KifVar, KifTerm>> resultStream = Stream.empty();
            if (currentGoal instanceof KifList goalList && !goalList.terms().isEmpty() && goalList.get(0) instanceof KifAtom opAtom) {
                resultStream = context.operators().get(opAtom).flatMap(op -> executeOperator(op, goalList, bindings, currentGoal)).stream();
            }
            var kbStream = Stream.concat(getKb(kbId).findUnifiableAssertions(currentGoal), (kbId != null && !kbId.equals(GLOBAL_KB_NOTE_ID)) ? context.getKb(GLOBAL_KB_NOTE_ID).findUnifiableAssertions(currentGoal) : Stream.empty())
                    .distinct().flatMap(fact -> ofNullable(Unifier.unify(currentGoal, fact.kif, bindings)).stream());
            resultStream = Stream.concat(resultStream, kbStream);
            var ruleStream = context.rules().stream().flatMap(rule -> {
                var renamedRule = renameRuleVariables(rule, depth);
                return ofNullable(Unifier.unify(renamedRule.consequent(), currentGoal, bindings))
                        .map(consequentBindings -> proveAntecedents(renamedRule.antecedents(), kbId, consequentBindings, depth - 1, new HashSet<>(proofStack)))
                        .orElse(Stream.empty());
            });
            resultStream = Stream.concat(resultStream, ruleStream);
            proofStack.remove(currentGoal); return resultStream.distinct();
        }
        private Optional<Map<KifVar, KifTerm>> executeOperator(Operator op, KifList goalList, Map<KifVar, KifTerm> bindings, KifTerm currentGoal) {
            try {
                return op.exe(goalList, context).handle((opResult, ex) -> {
                    if (ex != null) { System.err.println("Operator execution failed for " + op.pred().toKif() + ": " + ex.getMessage()); return Optional.<Map<KifVar, KifTerm>>empty(); }
                    if (opResult == null) return Optional.<Map<KifVar, KifTerm>>empty();
                    if (opResult.equals(KifAtom.of("true"))) return Optional.of(bindings);
                    return ofNullable(Unifier.unify(currentGoal, opResult, bindings));
                }).join();
            } catch (Exception e) { System.err.println("Operator execution exception for " + op.pred().toKif() + ": " + e.getMessage()); return Optional.empty(); }
        }
        private Stream<Map<KifVar, KifTerm>> proveAntecedents(List<KifTerm> antecedents, @Nullable String kbId, Map<KifVar, KifTerm> bindings, int depth, Set<KifTerm> proofStack) {
            if (antecedents.isEmpty()) return Stream.of(bindings);
            var first = antecedents.getFirst(); var rest = antecedents.subList(1, antecedents.size());
            return prove(first, kbId, bindings, depth, proofStack).flatMap(newBindings -> proveAntecedents(rest, kbId, newBindings, depth, proofStack));
        }
        private Rule renameRuleVariables(Rule rule, int depth) {
            var suffix = "_d" + depth + "_" + idCounter.incrementAndGet();
            Map<KifVar, KifTerm> renameMap = rule.form.vars().stream().collect(Collectors.toMap(Function.identity(), v -> KifVar.of(v.name() + suffix)));
            var renamedForm = (KifList) Unifier.subst(rule.form, renameMap);
            try { return Rule.parseRule(rule.id + suffix, renamedForm, rule.pri); }
            catch (IllegalArgumentException e) { System.err.println("Error renaming rule variables: " + e.getMessage()); return rule; }
        }
    }

    static class BasicOperator implements Operator {
        private final String id = generateId(ID_PREFIX_OPERATOR);
        private final KifAtom pred;
        private final Function<KifList, Optional<KifTerm>> function;
        BasicOperator(KifAtom pred, Function<KifList, Optional<KifTerm>> function) { this.pred = pred; this.function = function; }
        @Override public String id() { return id; }
        @Override public KifAtom pred() { return pred; }
        @Override public CompletableFuture<KifTerm> exe(KifList arguments, ReasonerContext context) { return CompletableFuture.completedFuture(function.apply(arguments).orElse(null)); }
    }

    static class StatusUpdaterPlugin extends BasePlugin {
        private final Consumer<SystemStatusEvent> uiUpdater;
        StatusUpdaterPlugin(Consumer<SystemStatusEvent> uiUpdater) { this.uiUpdater = uiUpdater; }
        @Override public void start(Events ev, Cognition ctx) {
            super.start(ev, ctx);
            ev.on(AssertionAddedEvent.class, e -> updateStatus());
            ev.on(AssertionRetractedEvent.class, e -> updateStatus());
            ev.on(AssertionEvictedEvent.class, e -> updateStatus());
            ev.on(AssertionStatusChangedEvent.class, e -> updateStatus());
            ev.on(RuleAddedEvent.class, e -> updateStatus());
            ev.on(RuleRemovedEvent.class, e -> updateStatus());
            ev.on(AddedEvent.class, e -> updateStatus());
            ev.on(RemovedEvent.class, e -> updateStatus());
            ev.on(LlmInfoEvent.class, e -> updateStatus());
            ev.on(LlmUpdateEvent.class, e -> updateStatus());
            ev.on(SystemStatusEvent.class, uiUpdater);
            updateStatus();
        }
        private void updateStatus() { publish(new SystemStatusEvent(context.cog.systemStatus, context.kbCount(), context.kbTotalCapacity(), context.cog.activeLlmTasks.size(), 0, context.ruleCount())); }
    }

    static class WebSocketBroadcasterPlugin extends BasePlugin {
        private final Cog server;
        WebSocketBroadcasterPlugin(Cog server) { this.server = server; }
        @Override public void start(Events ev, Cognition ctx) {
            super.start(ev, ctx);
            ev.on(AssertionAddedEvent.class, e -> broadcastMessage("assert-added", e.assertion(), e.getKbId()));
            ev.on(AssertionRetractedEvent.class, e -> broadcastMessage("retract", e.assertion(), e.getKbId()));
            ev.on(AssertionEvictedEvent.class, e -> broadcastMessage("evict", e.assertion(), e.getKbId()));
            ev.on(LlmInfoEvent.class, e -> broadcastMessage("llm-info", e.llmItem()));
            ev.on(LlmUpdateEvent.class, e -> broadcastMessage("llm-update", e));
            ev.on(WebSocketBroadcastEvent.class, e -> safeBroadcast(e.message()));
            if (server.broadcastInputAssertions) ev.on(ExternalInputEvent.class, this::onExternalInput);
        }
        private void onExternalInput(ExternalInputEvent event) {
            if (event.term() instanceof KifList list) {
                var tempId = generateId(ID_PREFIX_INPUT_ITEM);
                var pri = (event.sourceId().startsWith("llm-") ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.weight());
                var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                var kbId = requireNonNullElse(event.targetNoteId(), GLOBAL_KB_NOTE_ID);
                var tempAssertion = new Assertion(tempId, list, pri, System.currentTimeMillis(), event.targetNoteId(), Set.of(), type, false, false, false, List.of(), 0, true, kbId);
                broadcastMessage("assert-input", tempAssertion, kbId);
            }
        }
        private void broadcastMessage(String type, Assertion assertion, String kbId) {
            var kif = assertion.toKifString();
            var msg = switch (type) {
                case "assert-added", "assert-input" -> String.format("%s %.4f %s [%s] {type:%s, depth:%d, kb:%s}", type, assertion.pri, kif, assertion.id, assertion.type, assertion.derivationDepth(), kbId);
                case "retract", "evict" -> String.format("%s %s", type, assertion.id);
                default -> String.format("%s %.4f %s [%s]", type, assertion.pri, kif, assertion.id);
            };
            safeBroadcast(msg);
        }
        private void broadcastMessage(String type, SwingUI.AttachmentViewModel llmItem) {
            if (!type.equals("llm-info") || llmItem.noteId() == null) return;
            var msg = String.format("llm-info %s [%s] {type:%s, status:%s, content:\"%s\"}",
                    llmItem.noteId(), llmItem.id, llmItem.attachmentType, llmItem.llmStatus(), llmItem.content().replace("\"", "\\\""));
            safeBroadcast(msg);
        }
        private void broadcastMessage(String type, LlmUpdateEvent event) {
            if (!type.equals("llm-update")) return;
            var msg = String.format("llm-update %s {status:%s, content:\"%s\"}",
                    event.taskId(), event.status(), event.content().replace("\"", "\\\""));
            safeBroadcast(msg);
        }
        private void safeBroadcast(String message) {
            try { if (!server.websocket.getConnections().isEmpty()) server.websocket.broadcast(message); }
            catch (Exception e) {
                if (!(e instanceof ConcurrentModificationException || ofNullable(e.getMessage()).map(m -> m.contains("closed")).orElse(false)))
                    System.err.println("Error during WebSocket broadcast: " + e.getMessage());
            }
        }
    }

    static class UiUpdatePlugin extends BasePlugin {
        private final SwingUI swingUI;
        private final Cog cog;
        UiUpdatePlugin(SwingUI ui, Cog cog) { this.swingUI = ui; this.cog = cog; }
        @Override public void start(Events events, Cognition ctx) {
            super.start(events, ctx);
            events.on(AssertionAddedEvent.class, e -> handleUiUpdate("assert-added", e.assertion()));
            events.on(AssertionRetractedEvent.class, e -> handleUiUpdate("retract", e.assertion()));
            events.on(AssertionEvictedEvent.class, e -> handleUiUpdate("evict", e.assertion()));
            events.on(AssertionStatusChangedEvent.class, this::handleStatusChange);
            events.on(LlmInfoEvent.class, e -> handleUiUpdate("llm-info", e.llmItem()));
            events.on(LlmUpdateEvent.class, this::handleLlmUpdate);
            events.on(AddedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.addNoteToList(e.note())));
            events.on(RemovedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.removeNoteFromList(e.note().id)));
            events.on(QueryResultEvent.class, e -> handleUiUpdate("query-result", e.result()));
            events.on(QueryRequestEvent.class, e -> handleUiUpdate("query-sent", e.query()));
        }
        private void handleUiUpdate(String type, Object payload) {
            if (swingUI == null || !swingUI.isDisplayable()) return;
            SwingUI.AttachmentViewModel vm = null;
            String displayNoteId = null;

            switch (payload) {
                case Assertion assertion -> {
                    var sourceNoteId = assertion.sourceNoteId();
                    var derivedNoteId = (sourceNoteId == null && assertion.derivationDepth() > 0) ? context.findCommonSourceNodeId(assertion.justificationIds()) : null;

                    var i = sourceNoteId !=null ? sourceNoteId : derivedNoteId;
                    displayNoteId = i!=null ? i : assertion.kb.equals(GLOBAL_KB_NOTE_ID) ? GLOBAL_KB_NOTE_ID : assertion.kb;

                    if (displayNoteId==null)
                        return;

                    vm = SwingUI.AttachmentViewModel.fromAssertion(assertion, type, displayNoteId);
                }
                case SwingUI.AttachmentViewModel llmVm -> { vm = llmVm; displayNoteId = vm.noteId(); }
                case Answer result -> {
                    displayNoteId = GLOBAL_KB_NOTE_ID;
                    var content = String.format("Query Result (%s): %s -> %d bindings", result.status, result.query, result.bindings().size());
                    vm = SwingUI.AttachmentViewModel.forQuery(result.query + "_res", displayNoteId, content, SwingUI.AttachmentType.QUERY_RESULT, System.currentTimeMillis(), GLOBAL_KB_NOTE_ID);
                }
                case Query query -> {
                    displayNoteId = requireNonNullElse(query.targetKbId(), GLOBAL_KB_NOTE_ID);
                    var content = "Query Sent: " + query.pattern().toKif();
                    vm = SwingUI.AttachmentViewModel.forQuery(query.id + "_sent", displayNoteId, content, SwingUI.AttachmentType.QUERY_SENT, System.currentTimeMillis(), displayNoteId);
                }
                default -> { return; }
            }
            final var finalVm = vm;
            final var finalDisplayNoteId = displayNoteId;
            SwingUtilities.invokeLater(() -> swingUI.handleSystemUpdate(finalVm, finalDisplayNoteId));
        }
        private void handleStatusChange(AssertionStatusChangedEvent event) { context.findAssertionByIdAcrossKbs(event.assertionId()).ifPresent(a -> handleUiUpdate(event.isActive() ? "status-active" : "status-inactive", a)); }
        private void handleLlmUpdate(LlmUpdateEvent event) { SwingUtilities.invokeLater(() -> swingUI.updateLlmItem(event.taskId(), event.status(), event.content())); }
    }

    static class KifParser {
        private final Reader reader;
        private int currentChar = -2; private int line = 1; private int col = 0;
        private KifParser(Reader reader) { this.reader = reader; }
        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try (var sr = new StringReader(input.trim())) { return new KifParser(sr).parseTopLevel(); }
            catch (IOException e) { throw new ParseException("Internal Read error: " + e.getMessage()); }
        }
        private List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>(); consumeWhitespaceAndComments();
            while (peek() != -1) { terms.add(parseTerm()); consumeWhitespaceAndComments(); }
            return Collections.unmodifiableList(terms);
        }
        private KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments();
            return switch (peek()) {
                case -1 -> throw createParseException("Unexpected EOF");
                case '(' -> parseList(); case '"' -> parseQuotedString();
                case '?' -> parseVariable(); default -> parseAtom();
            };
        }
        private KifList parseList() throws IOException, ParseException {
            consumeChar('('); List<KifTerm> terms = new ArrayList<>();
            while (true) {
                consumeWhitespaceAndComments(); var next = peek();
                if (next == ')') { consumeChar(')'); return new KifList(terms); }
                if (next == -1) throw createParseException("Unmatched parenthesis");
                terms.add(parseTerm());
            }
        }
        private KifVar parseVariable() throws IOException, ParseException {
            consumeChar('?'); var sb = new StringBuilder("?");
            if (!isValidAtomChar(peek())) throw createParseException("Variable name character expected after '?'");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            if (sb.length() < 2) throw createParseException("Empty variable name after '?'");
            return KifVar.of(sb.toString());
        }
        private KifAtom parseAtom() throws IOException, ParseException {
            var sb = new StringBuilder();
            if (!isValidAtomChar(peek())) throw createParseException("Invalid character at start of atom");
            while (isValidAtomChar(peek())) sb.append((char) consumeChar());
            return KifAtom.of(sb.toString());
        }
        private boolean isValidAtomChar(int c) { return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';'; }
        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"'); var sb = new StringBuilder();
            while (true) {
                var c = consumeChar();
                if (c == '"') return KifAtom.of(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') {
                    var next = consumeChar(); if (next == -1) throw createParseException("EOF after escape character");
                    sb.append((char) switch (next) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case '\\', '"' -> next; default -> next; });
                } else sb.append((char) c);
            }
        }
        private int peek() throws IOException { if (currentChar == -2) currentChar = reader.read(); return currentChar; }
        private int consumeChar() throws IOException {
            var c = peek();
            if (c != -1) { currentChar = -2; if (c == '\n') { line++; col = 0; } else col++; }
            return c;
        }
        private void consumeChar(char expected) throws IOException, ParseException {
            var actual = consumeChar();
            if (actual != expected) throw createParseException("Expected '" + expected + "' but found " + ((actual == -1) ? "EOF" : "'" + (char) actual + "'"));
        }
        private void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                var c = peek(); if (c == -1) break;
                if (Character.isWhitespace(c)) consumeChar();
                else if (c == ';') { do consumeChar(); while (peek() != '\n' && peek() != '\r' && peek() != -1); }
                else break;
            }
        }
        private ParseException createParseException(String message) { return new ParseException(message + " at line " + line + " col " + col); }
    }

    static class ParseException extends Exception { ParseException(String message) { super(message); } }

    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 50;
        @Nullable static Map<KifVar, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings) { return unifyRecursive(x, y, bindings, 0); }
        @Nullable static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) { return matchRecursive(pattern, term, bindings, 0); }
        static KifTerm subst(KifTerm term, Map<KifVar, KifTerm> bindings) { return substRecursive(term, bindings, 0, false); }
        static KifTerm substFully(KifTerm term, Map<KifVar, KifTerm> bindings) { return substRecursive(term, bindings, 0, true); }
        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) { return rewriteRecursive(target, lhsPattern, rhsTemplate, 0); }

        @Nullable private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var xSubst = substRecursive(x, bindings, depth + 1, true); var ySubst = substRecursive(y, bindings, depth + 1, true);
            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true, depth);
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true, depth);
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var current = bindings;
                for (var i = 0; i < lx.size(); i++) { current = unifyRecursive(lx.get(i), ly.get(i), current, depth + 1); if (current == null) return null; }
                return current;
            }
            return null;
        }
        @Nullable private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var patternSubst = substRecursive(pattern, bindings, depth + 1, true);
            if (patternSubst instanceof KifVar varP) return bindVariable(varP, term, bindings, false, depth);
            if (patternSubst.equals(term)) return bindings;
            if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                var current = bindings;
                for (var i = 0; i < lp.size(); i++) { current = matchRecursive(lp.get(i), lt.get(i), current, depth + 1); if (current == null) return null; }
                return current;
            }
            return null;
        }
        private static KifTerm substRecursive(KifTerm term, Map<KifVar, KifTerm> bindings, int depth, boolean fully) {
            if (bindings.isEmpty() || depth > MAX_SUBST_DEPTH || !term.containsVar()) return term;
            return switch (term) {
                case KifAtom atom -> atom;
                case KifVar var -> { var binding = bindings.get(var); yield (binding != null && fully) ? substRecursive(binding, bindings, depth + 1, true) : requireNonNullElse(binding, var); }
                case KifList list -> {
                    var changed = new boolean[]{false};
                    var newTerms = list.terms().stream().map(sub -> { var subSubst = substRecursive(sub, bindings, depth + 1, fully); if (subSubst != sub) changed[0] = true; return subSubst; }).toList();
                    yield changed[0] ? new KifList(newTerms) : list;
                }
            };
        }
        @Nullable private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean doOccursCheck, int depth) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var)) return doOccursCheck ? unifyRecursive(bindings.get(var), value, bindings, depth + 1) : matchRecursive(bindings.get(var), value, bindings, depth + 1);
            var finalValue = substRecursive(value, bindings, depth + 1, true);
            if (doOccursCheck && occursCheckRecursive(var, finalValue, bindings, depth + 1)) return null;
            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings); newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }
        private static boolean occursCheckRecursive(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (depth > MAX_SUBST_DEPTH) return true; var substTerm = substRecursive(term, bindings, depth + 1, true);
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheckRecursive(var, t, bindings, depth + 1));
                case KifAtom ignored -> false;
            };
        }
        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhs, KifTerm rhs, int depth) {
            if (depth > MAX_SUBST_DEPTH) return Optional.empty();
            return ofNullable(matchRecursive(lhs, target, Map.of(), depth + 1))
                    .map(b -> substRecursive(rhs, b, depth + 1, true))
                    .or(() -> (target instanceof KifList tl) ? rewriteSubterms(tl, lhs, rhs, depth + 1) : Optional.empty());
        }
        private static Optional<KifTerm> rewriteSubterms(KifList targetList, KifTerm lhs, KifTerm rhs, int depth) {
            var changed = false; List<KifTerm> newSubs = new ArrayList<>(targetList.size());
            for (var sub : targetList.terms()) {
                var rewritten = rewriteRecursive(sub, lhs, rhs, depth);
                if (rewritten.isPresent()) { changed = true; newSubs.add(rewritten.get()); } else { newSubs.add(sub); }
            }
            return changed ? Optional.of(new KifList(newSubs)) : Optional.empty();
        }
    }

    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing...");
        final Map<String, DefaultListModel<AttachmentViewModel>> noteAttachmentModels = new ConcurrentHashMap<>();
        final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        final JList<Note> noteList = new JList<>(noteListModel);
        final JTextArea noteEditor = new JTextArea();
        final JTextField noteTitleField = new JTextField();
        final JList<AttachmentViewModel> attachmentList = new JList<>();
        private final JButton addButton = new JButton("Add Note"), pauseResumeButton = new JButton("Pause"), clearAllButton = new JButton("Clear All");
        private final JPopupMenu noteContextMenu = new JPopupMenu(), itemContextMenu = new JPopupMenu();
        private final JMenuItem analyzeItem = new JMenuItem("Analyze Note (LLM -> KIF)"), enhanceItem = new JMenuItem("Enhance Note (LLM Replace)"),
                summarizeItem = new JMenuItem("Summarize Note (LLM)"), keyConceptsItem = new JMenuItem("Identify Key Concepts (LLM)"),
                generateQuestionsItem = new JMenuItem("Generate Questions (LLM)"), removeItem = new JMenuItem("Remove Note");
        private final JMenuItem retractItem = new JMenuItem("Delete Attachment"), showSupportItem = new JMenuItem("Show Support Chain"),
                queryItem = new JMenuItem("Query This Pattern"), cancelLlmItem = new JMenuItem("Cancel LLM Task"),
                insertSummaryItem = new JMenuItem("Insert Summary into Note"), answerQuestionItem = new JMenuItem("Answer Question in Note"),
                findRelatedConceptsItem = new JMenuItem("Find Related Notes (Concept)");

        private JTextField filterField, queryInputField;
        private JButton queryButton;
        private Cog systemRef;
        private Note currentNote = null;
        private boolean isUpdatingTitleField = false;
        private JPanel attachmentPanel;


        public SwingUI(@Nullable Cog systemRef) {
            super("Cognote - Event Driven");
            this.systemRef = systemRef;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(1200, 800);
            setLocationRelativeTo(null);
            setupComponents();
            setupLayout();
            setupActionListeners();
            setupWindowListener();
            setupMenuBar();
            updateUIForSelection();
            setupFonts();
        }

        void setSystemReference(Cog system) { this.systemRef = system; updateUIForSelection(); }
        private void setupFonts() { Stream.of(noteList, noteEditor, noteTitleField, filterField, queryInputField, addButton, pauseResumeButton, clearAllButton, queryButton, statusLabel, analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem, retractItem, showSupportItem, queryItem, cancelLlmItem, insertSummaryItem, answerQuestionItem, findRelatedConceptsItem).forEach(c -> c.setFont(UI_DEFAULT_FONT)); attachmentList.setFont(MONOSPACED_FONT); }

        private void setupComponents() {
            noteEditor.setLineWrap(true); noteEditor.setWrapStyleWord(true);
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); noteList.setCellRenderer(new NoteListCellRenderer());
            statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10)); statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
            attachmentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); attachmentList.setCellRenderer(new AttachmentListCellRenderer());
            filterField = new JTextField(); filterField.setToolTipText("Filter attachments (case-insensitive contains)");
            filterField.getDocument().addDocumentListener((SimpleDocumentListener) e -> refreshAttachmentDisplay());
            queryInputField = new JTextField(); queryInputField.setToolTipText("Enter KIF query pattern and press Enter or click Query");
            queryButton = new JButton("Query KB");
            Stream.of(analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem).forEach(item -> { if (item != analyzeItem) noteContextMenu.addSeparator(); noteContextMenu.add(item); });
            Stream.of(retractItem, showSupportItem, queryItem, cancelLlmItem, insertSummaryItem, answerQuestionItem, findRelatedConceptsItem).forEach(item -> { if (item != retractItem) itemContextMenu.addSeparator(); itemContextMenu.add(item); });
        }

        private void setupLayout() {
            var leftPane = new JScrollPane(noteList); leftPane.setPreferredSize(new Dimension(250, 0));
            var titlePanel = new JPanel(new BorderLayout()); titlePanel.add(new JLabel("Title: "), BorderLayout.WEST); titlePanel.add(noteTitleField, BorderLayout.CENTER); titlePanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            var editorPane = new JScrollPane(noteEditor); var editorPanel = new JPanel(new BorderLayout()); editorPanel.add(titlePanel, BorderLayout.NORTH); editorPanel.add(editorPane, BorderLayout.CENTER);
            var filterQueryPanel = new JPanel(new BorderLayout(5, 0)); filterQueryPanel.add(filterField, BorderLayout.CENTER); var queryActionPanel = new JPanel(new BorderLayout(5, 0)); queryActionPanel.add(queryInputField, BorderLayout.CENTER); queryActionPanel.add(queryButton, BorderLayout.EAST); filterQueryPanel.add(queryActionPanel, BorderLayout.SOUTH); filterQueryPanel.setBorder(new EmptyBorder(5, 0, 5, 0));
            attachmentPanel = new JPanel(new BorderLayout(0, 5)); attachmentPanel.setBorder(BorderFactory.createTitledBorder("Attachments")); attachmentPanel.add(filterQueryPanel, BorderLayout.NORTH); attachmentPanel.add(new JScrollPane(attachmentList), BorderLayout.CENTER); attachmentPanel.setPreferredSize(new Dimension(0, 250));
            var rightPanel = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPanel, attachmentPanel); rightPanel.setResizeWeight(0.7);
            var mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPanel); mainSplitPane.setResizeWeight(0.2);
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5)); Stream.of(addButton, pauseResumeButton, clearAllButton).forEach(buttonPanel::add);
            var bottomPanel = new JPanel(new BorderLayout()); bottomPanel.add(buttonPanel, BorderLayout.WEST); bottomPanel.add(statusLabel, BorderLayout.CENTER); bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            var p = getContentPane(); p.setLayout(new BorderLayout()); p.add(mainSplitPane, BorderLayout.CENTER); p.add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupMenuBar() {
            var mb = new JMenuBar(); var fm = new JMenu("File"); var em = new JMenu("Edit"); var vm = new JMenu("View"); var qm = new JMenu("Query"); var hm = new JMenu("Help");
            var settingsItem = new JMenuItem("Settings..."); settingsItem.addActionListener(e -> showSettingsDialog()); fm.add(settingsItem);
            var viewRulesItem = new JMenuItem("View Rules"); viewRulesItem.addActionListener(e -> viewRulesAction()); vm.add(viewRulesItem);
            var askQueryItem = new JMenuItem("Ask Query..."); askQueryItem.addActionListener(e -> queryInputField.requestFocusInWindow()); qm.add(askQueryItem);
            Stream.of(fm, em, vm, qm, hm).forEach(mb::add); setJMenuBar(mb);
        }

        private void setupActionListeners() {
            addButton.addActionListener(e -> addNoteAction()); pauseResumeButton.addActionListener(e -> togglePauseAction()); clearAllButton.addActionListener(e -> clearAllAction());
            analyzeItem.addActionListener(e -> analyzeNoteAction()); enhanceItem.addActionListener(e -> enhanceNoteAction()); summarizeItem.addActionListener(e -> summarizeNoteAction());
            keyConceptsItem.addActionListener(e -> keyConceptsAction()); generateQuestionsItem.addActionListener(e -> generateQuestionsAction()); removeItem.addActionListener(e -> removeNoteAction());
            retractItem.addActionListener(e -> retractSelectedAttachmentAction()); showSupportItem.addActionListener(e -> showSupportAction()); queryItem.addActionListener(e -> querySelectedAttachmentAction());
            cancelLlmItem.addActionListener(e -> cancelSelectedLlmTaskAction()); insertSummaryItem.addActionListener(e -> insertSummaryAction()); answerQuestionItem.addActionListener(e -> answerQuestionAction());
            findRelatedConceptsItem.addActionListener(e -> findRelatedConceptsAction());
            queryButton.addActionListener(e -> askQueryAction()); queryInputField.addActionListener(e -> askQueryAction());

            noteList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) { saveCurrentNote(); currentNote = noteList.getSelectedValue(); updateUIForSelection(); } });
            noteEditor.getDocument().addDocumentListener((SimpleDocumentListener) e -> { if (currentNote != null && !GLOBAL_KB_NOTE_ID.equals(currentNote.id)) { currentNote.text = noteEditor.getText(); } });
            noteEditor.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { saveCurrentNote(); } });
            noteTitleField.getDocument().addDocumentListener((SimpleDocumentListener) e -> {
                if (!isUpdatingTitleField && currentNote != null && !GLOBAL_KB_NOTE_ID.equals(currentNote.id) && !CONFIG_NOTE_ID.equals(currentNote.id)) {
                    currentNote.title = noteTitleField.getText(); noteListModel.setElementAt(currentNote, noteList.getSelectedIndex()); setTitle("Cognote - " + currentNote.title + " [" + currentNote.id + "]");
                }
            });
            noteList.addMouseListener(createContextMenuMouseListener(noteList, noteContextMenu, this::updateNoteContextMenuState));
            MouseListener itemMouseListener = createContextMenuMouseListener(attachmentList, itemContextMenu, this::updateItemContextMenuState);
            MouseListener itemDoubleClickListener = new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) showSupportAction(); } };
            attachmentList.addMouseListener(itemMouseListener); attachmentList.addMouseListener(itemDoubleClickListener);
        }

        private MouseAdapter createContextMenuMouseListener(@Nullable JList<?> list, JPopupMenu popup, Runnable... preShowActions) {
            return new MouseAdapter() {
                private void maybeShowPopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) return; var targetList = (list != null) ? list : (JList<?>) e.getSource(); var idx = targetList.locationToIndex(e.getPoint());
                    if (idx != -1) { if (targetList.getSelectedIndex() != idx) targetList.setSelectedIndex(idx); Stream.of(preShowActions).forEach(Runnable::run); popup.show(e.getComponent(), e.getX(), e.getY()); }
                }
                @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
                @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            };
        }

        private void updateNoteContextMenuState() {
            var noteSelected = currentNote != null; var isEditableNote = noteSelected && !GLOBAL_KB_NOTE_ID.equals(currentNote.id) && !CONFIG_NOTE_ID.equals(currentNote.id);
            var systemReady = systemRef != null && systemRef.running.get() && !systemRef.paused.get();
            Stream.of(analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem).forEach(i -> i.setEnabled(isEditableNote && systemReady));
        }

        private void updateItemContextMenuState() {
            var vm = attachmentList.getSelectedValue(); var isSelected = vm != null; var isKif = isSelected && vm.isKifBased(); var isActiveKif = isKif && vm.status == AttachmentStatus.ACTIVE;
            var isLlmTask = isSelected && vm.attachmentType.name().startsWith("LLM_"); var isCancelableLlm = isLlmTask && (vm.llmStatus == LlmStatus.SENDING || vm.llmStatus == LlmStatus.PROCESSING);
            var isSummary = isActiveKif && vm.attachmentType == AttachmentType.SUMMARY; var isQuestion = isActiveKif && vm.attachmentType == AttachmentType.QUESTION; var isConcept = isActiveKif && vm.attachmentType == AttachmentType.CONCEPT;
            retractItem.setEnabled(isActiveKif); showSupportItem.setEnabled(isKif); queryItem.setEnabled(isKif); cancelLlmItem.setEnabled(isCancelableLlm);
            insertSummaryItem.setEnabled(isSummary); answerQuestionItem.setEnabled(isQuestion); findRelatedConceptsItem.setEnabled(isConcept);
        }

        private void setupWindowListener() { addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { saveCurrentNote(); ofNullable(systemRef).ifPresent(Cog::stopSystem); dispose(); System.exit(0); } }); }

        private void saveCurrentNote() {
            ofNullable(currentNote).filter(n -> !GLOBAL_KB_NOTE_ID.equals(n.id)).ifPresent(n -> {
                n.text = noteEditor.getText();
                if (!CONFIG_NOTE_ID.equals(n.id)) n.title = noteTitleField.getText();
                else if (systemRef != null && !systemRef.updateConfig(n.text)) JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration note. Changes not applied.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
            });
        }

        private void updateUIForSelection() {
            var noteSelected = (currentNote != null); var isGlobalSelected = noteSelected && GLOBAL_KB_NOTE_ID.equals(currentNote.id); var isConfigSelected = noteSelected && CONFIG_NOTE_ID.equals(currentNote.id);
            var isEditableNoteContent = noteSelected && !isGlobalSelected; var isEditableNoteTitle = noteSelected && !isGlobalSelected && !isConfigSelected;
            isUpdatingTitleField = true; noteTitleField.setText(noteSelected ? currentNote.title : ""); noteTitleField.setEditable(isEditableNoteTitle); noteTitleField.setEnabled(noteSelected && !isGlobalSelected); isUpdatingTitleField = false;
            noteEditor.setText(noteSelected ? currentNote.text : ""); noteEditor.setEditable(isEditableNoteContent); noteEditor.setEnabled(isEditableNoteContent); noteEditor.getHighlighter().removeAllHighlights(); noteEditor.setCaretPosition(0);
            ofNullable(systemRef).ifPresent(r -> pauseResumeButton.setText(r.isPaused() ? "Resume" : "Pause"));
            filterField.setText(""); queryInputField.setText("");
            if (noteSelected) {
                setTitle("Cognote - " + currentNote.title + (isGlobalSelected || isConfigSelected ? "" : " [" + currentNote.id + "]"));
                SwingUtilities.invokeLater(isEditableNoteContent ? noteEditor::requestFocusInWindow : filterField::requestFocusInWindow);
            } else {
                setTitle("Cognote - Event Driven");
            }
            refreshAttachmentDisplay();
            updateAttachmentPanelTitle();
            setControlsEnabled(true);
        }

        private void refreshAttachmentDisplay() {
            if (currentNote == null) {
                attachmentList.setModel(new DefaultListModel<>());
                return;
            }
            var filterText = filterField.getText().trim().toLowerCase();
            var sourceModel = noteAttachmentModels.computeIfAbsent(currentNote.id, id -> new DefaultListModel<>());

            List<AttachmentViewModel> sortedSource = Collections.list(sourceModel.elements());
            sortedSource.sort(Comparator.naturalOrder());

            var displayModel = new DefaultListModel<AttachmentViewModel>();
            var stream = sortedSource.stream();
            if (!filterText.isEmpty()) {
                stream = stream.filter(vm -> vm.content().toLowerCase().contains(filterText));
            }
            stream.forEach(displayModel::addElement);

            attachmentList.setModel(displayModel);
        }

        private void filterAttachmentList() {
            refreshAttachmentDisplay();
        }


        private void updateAttachmentPanelTitle() {
            var count = (currentNote != null) ? noteAttachmentModels.getOrDefault(currentNote.id, new DefaultListModel<>()).getSize() : 0;
            ((TitledBorder) attachmentPanel.getBorder()).setTitle("Attachments" + (count > 0 ? " (" + count + ")" : ""));
            attachmentPanel.repaint();
        }

        private void addNoteAction() {
            ofNullable(JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE))
                    .map(String::trim).filter(Predicate.not(String::isEmpty))
                    .ifPresent(title -> {
                        var newNote = new Note(generateId(ID_PREFIX_NOTE), title, "");
                        addNoteToList(newNote); noteList.setSelectedValue(newNote, true);
                        if (systemRef != null) systemRef.events.emit(new AddedEvent(newNote));
                    });
        }
        private void removeNoteAction() { performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions?", JOptionPane.WARNING_MESSAGE, note -> ofNullable(systemRef).ifPresent(s -> s.events.emit(new RetractionRequestEvent(note.id, RetractionType.BY_NOTE, "UI-Remove", note.id)))); }
        private void enhanceNoteAction() { performNoteActionAsync("Enhancing", systemRef::enhanceNoteWithLlmAsync, (resp, n) -> {}, this::handleLlmFailure); }
        private void analyzeNoteAction() {
            performNoteActionAsync("Analyzing", (taskId, note) -> {
                // Retracting BY_NOTE caused issues, including removing the KB.
                // Just clear the UI list. Duplicates should be handled by the KB commit logic.
                clearNoteAttachmentList(note.id); // Clears UI list immediately
                return systemRef.text2kifAsync(taskId, note.text, note.id); // LLM call starts
            }, (kif, note) -> {}, this::handleLlmFailure);
        }
        private void summarizeNoteAction() { performNoteActionAsync("Summarizing", systemRef::summarizeNoteWithLlmAsync, (resp, n) -> {}, this::handleLlmFailure); }
        private void keyConceptsAction() { performNoteActionAsync("Identifying Concepts", systemRef::keyConceptsWithLlmAsync, (resp, n) -> {}, this::handleLlmFailure); }
        private void generateQuestionsAction() { performNoteActionAsync("Generating Questions", systemRef::generateQuestionsWithLlmAsync, (resp, n) -> {}, this::handleLlmFailure); }
        private void retractSelectedAttachmentAction() { getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).filter(vm -> vm.status == AttachmentStatus.ACTIVE).map(AttachmentViewModel::id).ifPresent(id -> { System.out.println("UI Requesting retraction for: " + id); ofNullable(systemRef).ifPresent(s -> s.events.emit(new RetractionRequestEvent(id, RetractionType.BY_ID, "UI-Retract", currentNote != null ? currentNote.id : null))); }); }
        private void showSupportAction() { getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).map(AttachmentViewModel::id).flatMap(id -> systemRef.context.findAssertionByIdAcrossKbs(id)).ifPresent(this::displaySupportChain); }
        private void querySelectedAttachmentAction() { getSelectedAttachmentViewModel().filter(AttachmentViewModel::isKifBased).ifPresent(vm -> queryInputField.setText(vm.content())); }
        private void cancelSelectedLlmTaskAction() { getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType.name().startsWith("LLM_")).ifPresent(vm -> { ofNullable(systemRef.activeLlmTasks.remove(vm.id)).ifPresent(future -> future.cancel(true)); updateLlmItem(vm.id, LlmStatus.CANCELLED, "Task cancelled by user."); }); }
        private void insertSummaryAction() { getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.SUMMARY && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> { try { var doc = noteEditor.getDocument(); var caretPos = noteEditor.getCaretPosition(); var summaryText = extractContentFromKif(vm.content()); doc.insertString(caretPos, summaryText + "\n", null); saveCurrentNote(); } catch (BadLocationException e) { System.err.println("Error inserting summary: " + e.getMessage()); } }); }
        private void answerQuestionAction() {
            getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.QUESTION && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> {
                var questionText = extractContentFromKif(vm.content()); var answer = JOptionPane.showInputDialog(this, "Q: " + questionText + "\n\nEnter your answer:", "Answer Question", JOptionPane.PLAIN_MESSAGE);
                if (answer != null && !answer.isBlank()) { try { var doc = noteEditor.getDocument(); var qaText = String.format("\n\nQ: %s\nA: %s\n", questionText, answer.trim()); doc.insertString(doc.getLength(), qaText, null); saveCurrentNote(); } catch (BadLocationException e) { System.err.println("Error appending Q/A: " + e.getMessage()); } }
            });
        }
        private void findRelatedConceptsAction() { getSelectedAttachmentViewModel().filter(vm -> vm.attachmentType == AttachmentType.CONCEPT && vm.status == AttachmentStatus.ACTIVE).ifPresent(vm -> { var conceptText = extractContentFromKif(vm.content()); JOptionPane.showMessageDialog(this, "Functionality to find related notes for concept '" + conceptText + "' is not yet implemented.", "Find Related Notes", JOptionPane.INFORMATION_MESSAGE); }); }

        private String extractContentFromKif(String kifString) {
            try {
                var terms = KifParser.parseKif(kifString);
                if (terms.size() == 1 && terms.getFirst() instanceof KifList list && list.size() >= 4 && list.get(3) instanceof KifAtom contentAtom) return contentAtom.value();
            } catch (ParseException e) { System.err.println("Failed to parse KIF for content extraction: " + kifString); }
            return kifString;
        }
        private Optional<AttachmentViewModel> getSelectedAttachmentViewModel() { return Optional.ofNullable(attachmentList.getSelectedValue()); }
        private void askQueryAction() {
            if (systemRef == null) return; var queryText = queryInputField.getText().trim(); if (queryText.isBlank()) return;
            try {
                var terms = KifParser.parseKif(queryText); if (terms.size() != 1 || !(terms.getFirst() instanceof KifList queryPattern)) { JOptionPane.showMessageDialog(this, "Invalid query: Must be a single KIF list.", "Query Error", JOptionPane.ERROR_MESSAGE); return; }
                var queryId = generateId(ID_PREFIX_QUERY); var targetKbId = (currentNote != null && !GLOBAL_KB_NOTE_ID.equals(currentNote.id)) ? currentNote.id : null;
                var query = new Query(queryId, QueryType.ASK_BINDINGS, queryPattern, targetKbId, Map.of()); systemRef.events.emit(new QueryRequestEvent(query)); queryInputField.setText("");
            } catch (ParseException ex) { JOptionPane.showMessageDialog(this, "Query Parse Error: " + ex.getMessage(), "Query Error", JOptionPane.ERROR_MESSAGE); }
        }
        private void viewRulesAction() {
            if (systemRef == null) return; var rules = systemRef.context.rules().stream().sorted(Comparator.comparing(Rule::id)).toList();
            if (rules.isEmpty()) { JOptionPane.showMessageDialog(this, "<No rules defined>", "Current Rules", JOptionPane.INFORMATION_MESSAGE); return; }
            var ruleListModel = new DefaultListModel<Rule>(); rules.forEach(ruleListModel::addElement); var ruleJList = new JList<>(ruleListModel);
            ruleJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); ruleJList.setFont(MONOSPACED_FONT);
            ruleJList.setCellRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) { var lbl = (JLabel) super.getListCellRendererComponent(l, v, i, s, f); if (v instanceof Rule r) lbl.setText(String.format("[%s] %.2f %s", r.id, r.pri, r.form.toKif())); return lbl; } });
            var scrollPane = new JScrollPane(ruleJList); scrollPane.setPreferredSize(new Dimension(700, 400)); var removeButton = new JButton("Remove Selected Rule"); removeButton.setFont(UI_DEFAULT_FONT); removeButton.setEnabled(false);
            ruleJList.addListSelectionListener(ev -> removeButton.setEnabled(ruleJList.getSelectedIndex() != -1));
            removeButton.addActionListener(ae -> { var selectedRule = ruleJList.getSelectedValue(); if (selectedRule != null && JOptionPane.showConfirmDialog(this, "Remove rule: " + selectedRule.id + "?", "Confirm Rule Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) { systemRef.events.emit(new RetractionRequestEvent(selectedRule.form.toKif(), RetractionType.BY_RULE_FORM, "UI-RuleView", null)); ruleListModel.removeElement(selectedRule); } });
            var panel = new JPanel(new BorderLayout(0, 10)); panel.add(scrollPane, BorderLayout.CENTER); panel.add(removeButton, BorderLayout.SOUTH);
            JOptionPane.showMessageDialog(this, panel, "Current Rules", JOptionPane.PLAIN_MESSAGE);
        }
        private void togglePauseAction() { ofNullable(systemRef).ifPresent(r -> r.setPaused(!r.isPaused())); }
        private void clearAllAction() { if (systemRef != null && JOptionPane.showConfirmDialog(this, "Clear all notes (except config), assertions, and rules? This cannot be undone.", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) systemRef.clearAllKnowledge(); }

        private void performNoteAction(String actionName, String confirmTitle, String confirmMsgFormat, int confirmMsgType, Consumer<Note> action) {
            ofNullable(currentNote).filter(_ -> systemRef != null).filter(n -> !GLOBAL_KB_NOTE_ID.equals(n.id) && !CONFIG_NOTE_ID.equals(n.id))
                    .filter(note -> JOptionPane.showConfirmDialog(this, String.format(confirmMsgFormat, note.title), confirmTitle, JOptionPane.YES_NO_OPTION, confirmMsgType) == JOptionPane.YES_OPTION)
                    .ifPresent(note -> {
                        systemRef.systemStatus = String.format("%s '%s'...", actionName, note.title); systemRef.updateStatusLabel(); setControlsEnabled(false);
                        try { action.accept(note); systemRef.systemStatus = String.format("Finished %s '%s'.", actionName, note.title); }
                        catch (Exception ex) { systemRef.systemStatus = String.format("Error %s '%s'.", actionName, note.title); handleActionError(actionName, ex); }
                        finally { systemRef.updateStatusLabel(); setControlsEnabled(true); }
                    });
        }

        private <T> void performNoteActionAsync(String actionName, NoteAsyncAction<T> asyncAction, BiConsumer<T, Note> successCallback, BiConsumer<Throwable, Note> failureCallback) {
            ofNullable(currentNote).filter(_ -> systemRef != null).filter(n -> !GLOBAL_KB_NOTE_ID.equals(n.id) && !CONFIG_NOTE_ID.equals(n.id))
                    .ifPresent(noteForAction -> {
                        systemRef.systemStatus = actionName + " Note: " + noteForAction.title; systemRef.updateStatusLabel(); setControlsEnabled(false); saveCurrentNote();
                        var taskId = systemRef.addLlmUiPlaceholder(noteForAction.id, actionName);
                        try {
                            var future = asyncAction.execute(taskId, noteForAction);
                            systemRef.activeLlmTasks.put(taskId, future);
                            future.whenCompleteAsync((result, ex) -> {
                                systemRef.activeLlmTasks.remove(taskId);
                                // Status updates (Done/Error/Cancelled) are handled by LlmUpdateEvent handler
                                if (ex != null) failureCallback.accept(ex, noteForAction);
                                else successCallback.accept(result, noteForAction);
                                setControlsEnabled(true); systemRef.systemStatus = "Running"; systemRef.updateStatusLabel();
                            }, SwingUtilities::invokeLater);
                        } catch (Exception e) {
                            systemRef.activeLlmTasks.remove(taskId); systemRef.updateLlmItemStatus(taskId, LlmStatus.ERROR, "Failed to start: " + e.getMessage());
                            failureCallback.accept(e, noteForAction); setControlsEnabled(true); systemRef.systemStatus = "Running"; systemRef.updateStatusLabel();
                        }
                    });
        }

        private void handleActionError(String actionName, Throwable ex) {
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            System.err.println("Error during " + actionName + ": " + cause.getMessage()); cause.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error during " + actionName + ":\n" + cause.getMessage(), "Action Error", JOptionPane.ERROR_MESSAGE);
        }
        private void handleLlmFailure(Throwable ex, Note contextNote) {
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            var action = (cause instanceof ParseException) ? "KIF Parse Error" : "LLM Interaction Failed";
            if (!(cause instanceof CancellationException)) {
                System.err.println("LLM Failure for note " + contextNote.id + ": " + action + ": " + cause.getMessage());
                // Error status is set via LlmUpdateEvent in the calling completion handler
            } else { System.out.println("LLM task cancelled for note " + contextNote.id); }
        }

        public void handleSystemUpdate(AttachmentViewModel vm, @Nullable String displayNoteId) {
            if (!isDisplayable()) return;
            var targetNoteIdForList = requireNonNullElse(displayNoteId, GLOBAL_KB_NOTE_ID);
            var sourceModel = noteAttachmentModels.computeIfAbsent(targetNoteIdForList, id -> new DefaultListModel<>());
            updateOrAddModelItem(sourceModel, vm);

            if (vm.isKifBased()) {
                var status = vm.status;
                if (status == AttachmentStatus.RETRACTED || status == AttachmentStatus.EVICTED || status == AttachmentStatus.INACTIVE) updateAttachmentStatusInOtherNoteModels(vm.id, status, targetNoteIdForList);
                ofNullable(systemRef).flatMap(s -> s.context.findAssertionByIdAcrossKbs(vm.id)).ifPresent(assertion -> highlightAffectedNoteText(assertion, status));
            }

            if (currentNote != null && currentNote.id.equals(targetNoteIdForList)) refreshAttachmentDisplay();
            updateAttachmentPanelTitle();
        }


        private void updateOrAddModelItem(DefaultListModel<AttachmentViewModel> sourceModel, AttachmentViewModel newItem) {
            var existingIndex = findViewModelIndexById(sourceModel, newItem.id);
            if (existingIndex != -1) {
                var existingItem = sourceModel.getElementAt(existingIndex);
                if (newItem.status != existingItem.status || !newItem.content().equals(existingItem.content()) || newItem.priority() != existingItem.priority() || !Objects.equals(newItem.associatedNoteId(), existingItem.associatedNoteId()) || !Objects.equals(newItem.kbId(), existingItem.kbId()) || newItem.llmStatus != existingItem.llmStatus || newItem.attachmentType != existingItem.attachmentType) {
                    sourceModel.setElementAt(newItem, existingIndex);
                }
            } else if (newItem.status == AttachmentStatus.ACTIVE || !newItem.isKifBased()) { // Only add active KIF or any non-KIF
                sourceModel.addElement(newItem);
            }
        }

        private void updateAttachmentStatusInOtherNoteModels(String attachmentId, AttachmentStatus newStatus, @Nullable String primaryNoteId) {
            noteAttachmentModels.forEach((noteId, model) -> {
                if (!noteId.equals(primaryNoteId)) {
                    var idx = findViewModelIndexById(model, attachmentId);
                    if (idx != -1 && model.getElementAt(idx).status != newStatus) {
                        model.setElementAt(model.getElementAt(idx).withStatus(newStatus), idx);
                        if (currentNote != null && currentNote.id.equals(noteId)) refreshAttachmentDisplay();
                    }
                }
            });
        }

        public void updateLlmItem(String taskId, LlmStatus status, String content) {
            findViewModelInAnyModel(taskId).ifPresent(entry -> {
                var model = entry.getKey(); var index = entry.getValue(); var oldVm = model.getElementAt(index);
                var newVm = oldVm.withLlmUpdate(status, content);
                model.setElementAt(newVm, index);
                if (currentNote != null && currentNote.id.equals(oldVm.noteId())) refreshAttachmentDisplay();
            });
        }

        private Optional<Map.Entry<DefaultListModel<AttachmentViewModel>, Integer>> findViewModelInAnyModel(String id) {
            return noteAttachmentModels.entrySet().stream()
                    .flatMap(entry -> { var model = entry.getValue(); var index = findViewModelIndexById(model, id); return (index != -1) ? Stream.of(Map.entry(model, index)) : Stream.empty(); })
                    .findFirst();
        }
        private int findViewModelIndexById(DefaultListModel<AttachmentViewModel> model, String id) { for (var i = 0; i < model.getSize(); i++) if (model.getElementAt(i).id.equals(id)) return i; return -1; }
        public void clearAllUILists() { noteListModel.clear(); noteAttachmentModels.clear(); attachmentList.setModel(new DefaultListModel<>()); noteEditor.setText(""); noteTitleField.setText(""); currentNote = null; setTitle("Cognote - Event Driven"); updateAttachmentPanelTitle(); }
        private void clearNoteAttachmentList(String noteId) {
            DefaultListModel<AttachmentViewModel> modelToClear = noteAttachmentModels.get(noteId);
            if (modelToClear != null) {
                modelToClear.clear(); // Clear the model stored in the map
            }
            // If this note is currently displayed, refresh its display
            if (currentNote != null && currentNote.id.equals(noteId)) {
                refreshAttachmentDisplay(); // Will show the now-empty list
                updateAttachmentPanelTitle(); // Update count shown in title
            }
        }
        public void addNoteToList(Note note) { if (findNoteById(note.id).isEmpty()) { noteListModel.addElement(note); noteAttachmentModels.computeIfAbsent(note.id, id -> new DefaultListModel<>()); } }
        public void removeNoteFromList(String noteId) {
            noteAttachmentModels.remove(noteId);
            findNoteIndexById(noteId).ifPresent(i -> {
                var idx = noteList.getSelectedIndex(); noteListModel.removeElementAt(i);
                if (currentNote != null && currentNote.id.equals(noteId)) currentNote = null;
                if (!noteListModel.isEmpty()) noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
                else updateUIForSelection();
            });
        }
        public Optional<Note> findNoteById(String noteId) { return Collections.list(noteListModel.elements()).stream().filter(n -> n.id.equals(noteId)).findFirst(); }
        private OptionalInt findNoteIndexById(String noteId) { return IntStream.range(0, noteListModel.size()).filter(i -> noteListModel.getElementAt(i).id.equals(noteId)).findFirst(); }
        public List<Note> getAllNotes() { return Collections.list(noteListModel.elements()); }
        public void loadNotes(List<Note> notes) {
            noteListModel.clear(); notes.forEach(this::addNoteToList);
            if (findNoteById(GLOBAL_KB_NOTE_ID).isEmpty()) addNoteToList(new Note(GLOBAL_KB_NOTE_ID, GLOBAL_KB_NOTE_TITLE, "Assertions in the global knowledge base."));
            if (findNoteById(CONFIG_NOTE_ID).isEmpty()) addNoteToList(systemRef != null ? systemRef.createDefaultConfigNote() : new Note(CONFIG_NOTE_ID, CONFIG_NOTE_TITLE, "{}"));
            if (!noteListModel.isEmpty()) {
                var firstSelectable = IntStream.range(0, noteListModel.getSize()).filter(i -> !noteListModel.getElementAt(i).id.equals(GLOBAL_KB_NOTE_ID) && !noteListModel.getElementAt(i).id.equals(CONFIG_NOTE_ID)).findFirst().orElse(findNoteIndexById(GLOBAL_KB_NOTE_ID).orElse(0));
                noteList.setSelectedIndex(firstSelectable);
            }
            updateUIForSelection();
        }

        private void displaySupportChain(Assertion startingAssertion) {
            if (systemRef == null) return;
            var dialog = new JDialog(this, "Support Chain for: " + startingAssertion.id, false); dialog.setSize(600, 400); dialog.setLocationRelativeTo(this);
            var model = new DefaultListModel<AttachmentViewModel>(); var list = new JList<>(model); list.setCellRenderer(new AttachmentListCellRenderer()); list.setFont(MONOSPACED_FONT);
            Set<String> visited = new HashSet<>(); Queue<String> queue = new LinkedList<>(); queue.offer(startingAssertion.id);
            while (!queue.isEmpty()) {
                var currentId = queue.poll(); if (currentId == null || !visited.add(currentId)) continue;
                systemRef.context.findAssertionByIdAcrossKbs(currentId).ifPresent(a -> {
                    var displayNoteId = a.sourceNoteId() != null ? a.sourceNoteId() : systemRef.context.findCommonSourceNodeId(a.justificationIds());
                    model.addElement(AttachmentViewModel.fromAssertion(a, "support", displayNoteId)); a.justificationIds().forEach(queue::offer);
                });
            }
            List<AttachmentViewModel> sortedList = Collections.list(model.elements()); sortedList.sort(Comparator.naturalOrder()); model.clear(); sortedList.forEach(model::addElement);
            dialog.add(new JScrollPane(list)); dialog.setVisible(true);
        }

        private void highlightAffectedNoteText(Assertion assertion, AttachmentStatus status) {
            if (systemRef == null || currentNote == null || GLOBAL_KB_NOTE_ID.equals(currentNote.id) || CONFIG_NOTE_ID.equals(currentNote.id)) return;
            var displayNoteId = assertion.sourceNoteId();
            if (displayNoteId == null && assertion.derivationDepth() > 0) displayNoteId = systemRef.context.findCommonSourceNodeId(assertion.justificationIds());
            if (displayNoteId == null && !GLOBAL_KB_NOTE_ID.equals(assertion.kb)) displayNoteId = assertion.kb;
            if (currentNote.id.equals(displayNoteId)) {
                var searchTerm = extractHighlightTerm(assertion.kif); if (searchTerm == null || searchTerm.isBlank()) return;
                var highlighter = noteEditor.getHighlighter();
                Highlighter.HighlightPainter painter = switch (status) {
                    case ACTIVE -> new DefaultHighlighter.DefaultHighlightPainter(new Color(200, 255, 200));
                    case RETRACTED, INACTIVE -> new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 200, 200));
                    case EVICTED -> new DefaultHighlighter.DefaultHighlightPainter(Color.LIGHT_GRAY);
                };
                try {
                    var text = noteEditor.getText(); var pos = text.toLowerCase().indexOf(searchTerm.toLowerCase());
                    while (pos >= 0) { highlighter.addHighlight(pos, pos + searchTerm.length(), painter); pos = text.toLowerCase().indexOf(searchTerm.toLowerCase(), pos + 1); }
                } catch (BadLocationException e) { /* Ignore */ }
            }
        }
        private String extractHighlightTerm(KifList kif) {
            return kif.terms().stream().filter(KifAtom.class::isInstance).map(KifAtom.class::cast).map(KifAtom::value)
                    .filter(s -> s.length() > 2 && !Set.of(KIF_OP_AND, KIF_OP_OR, KIF_OP_NOT, KIF_OP_IMPLIES, KIF_OP_EQUIV, KIF_OP_EQUAL, KIF_OP_EXISTS, KIF_OP_FORALL, PRED_NOTE_SUMMARY, PRED_NOTE_CONCEPT, PRED_NOTE_QUESTION).contains(s))
                    .filter(s -> !s.startsWith(ID_PREFIX_NOTE) && !s.startsWith(ID_PREFIX_LLM_RESULT))
                    .findFirst().orElse(null);
        }
        private void setControlsEnabled(boolean enabled) {
            var noteSelected = (currentNote != null); var isGlobalSelected = noteSelected && GLOBAL_KB_NOTE_ID.equals(currentNote.id); var isConfigSelected = noteSelected && CONFIG_NOTE_ID.equals(currentNote.id);
            var isEditableNote = noteSelected && !isGlobalSelected && !isConfigSelected; var systemReady = (systemRef != null && systemRef.running.get() && !systemRef.paused.get());
            Stream.of(addButton, clearAllButton).forEach(c -> c.setEnabled(enabled && systemRef != null)); pauseResumeButton.setEnabled(enabled && systemRef != null && systemRef.running.get());
            noteTitleField.setEnabled(enabled && noteSelected && !isGlobalSelected); noteTitleField.setEditable(enabled && isEditableNote);
            noteEditor.setEnabled(enabled && noteSelected && !isGlobalSelected); noteEditor.setEditable(enabled && noteSelected && !isGlobalSelected);
            filterField.setEnabled(enabled && noteSelected); queryInputField.setEnabled(enabled && noteSelected && systemReady); queryButton.setEnabled(enabled && noteSelected && systemReady); attachmentList.setEnabled(enabled && noteSelected);
            if (systemRef != null && systemRef.running.get()) pauseResumeButton.setText(systemRef.isPaused() ? "Resume" : "Pause");
        }
        private void showSettingsDialog() { if (systemRef == null) return; var dialog = new SettingsDialog(this, systemRef); dialog.setVisible(true); }

        enum AttachmentStatus {ACTIVE, RETRACTED, EVICTED, INACTIVE}
        enum LlmStatus {IDLE, SENDING, PROCESSING, DONE, ERROR, CANCELLED}
        enum AttachmentType {
            FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION, LLM_INFO, LLM_ERROR, QUERY_SENT, QUERY_RESULT, OTHER
        }

        @FunctionalInterface interface NoteAsyncAction<T> { CompletableFuture<T> execute(String taskId, Note note); }
        @FunctionalInterface interface SimpleDocumentListener extends DocumentListener {
            void update(DocumentEvent e);
            @Override default void insertUpdate(DocumentEvent e) { update(e); }
            @Override default void removeUpdate(DocumentEvent e) { update(e); }
            @Override default void changedUpdate(DocumentEvent e) { update(e); }
        }

        static class NoteListCellRenderer extends DefaultListCellRenderer {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                var label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus); label.setBorder(new EmptyBorder(5, 10, 5, 10)); label.setFont(UI_DEFAULT_FONT);
                if (value instanceof Note note) {
                    if (CONFIG_NOTE_ID.equals(note.id)) { label.setFont(label.getFont().deriveFont(Font.ITALIC)); label.setForeground(Color.GRAY); }
                    else if (GLOBAL_KB_NOTE_ID.equals(note.id)) { label.setFont(label.getFont().deriveFont(Font.BOLD)); }
                } return label;
            }
        }

        record AttachmentViewModel(String id, @Nullable String noteId, String content, AttachmentType attachmentType,
                                   AttachmentStatus status, double priority, int depth, long timestamp,
                                   @Nullable String associatedNoteId, @Nullable String kbId,
                                   @Nullable Set<String> justifications, LlmStatus llmStatus
        ) implements Comparable<AttachmentViewModel> {
            static AttachmentViewModel fromAssertion(Assertion assertion, String callbackType, @Nullable String associatedNoteId) {
                var type = determineTypeFromAssertion(assertion); var stat = determineStatusFromCallback(callbackType, assertion.isActive());
                var finalKbId = assertion.kb; var finalAssocNoteId = requireNonNullElse(associatedNoteId, assertion.sourceNoteId());
                return new AttachmentViewModel(assertion.id, assertion.sourceNoteId(), assertion.toKifString(), type, stat, assertion.pri, assertion.derivationDepth(), assertion.timestamp(), finalAssocNoteId, finalKbId, assertion.justificationIds(), LlmStatus.IDLE);
            }
            static AttachmentViewModel forLlm(String id, @Nullable String noteId, String content, AttachmentType attachmentType, long timestamp, @Nullable String kbId, LlmStatus llmStatus) {
                return new AttachmentViewModel(id, noteId, content, attachmentType, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, llmStatus);
            }
            static AttachmentViewModel forQuery(String id, @Nullable String noteId, String content, AttachmentType attachmentType, long timestamp, @Nullable String kbId) {
                return new AttachmentViewModel(id, noteId, content, attachmentType, AttachmentStatus.ACTIVE, 0.0, -1, timestamp, noteId, kbId, null, LlmStatus.IDLE);
            }
            private static AttachmentType determineTypeFromAssertion(Assertion assertion) {
                return assertion.kif.op().map(op -> switch (op) {
                    case PRED_NOTE_SUMMARY -> AttachmentType.SUMMARY; case PRED_NOTE_CONCEPT -> AttachmentType.CONCEPT; case PRED_NOTE_QUESTION -> AttachmentType.QUESTION;
                    default -> switch (assertion.type) {
                        case GROUND -> (assertion.derivationDepth() == 0) ? AttachmentType.FACT : AttachmentType.DERIVED;
                        case SKOLEMIZED -> AttachmentType.SKOLEMIZED; case UNIVERSAL -> AttachmentType.UNIVERSAL;
                    };
                }).orElse(switch (assertion.type) {
                    case GROUND -> (assertion.derivationDepth() == 0) ? AttachmentType.FACT : AttachmentType.DERIVED;
                    case SKOLEMIZED -> AttachmentType.SKOLEMIZED; case UNIVERSAL -> AttachmentType.UNIVERSAL;
                });
            }
            private static AttachmentStatus determineStatusFromCallback(String callbackType, boolean isActive) {
                return switch (callbackType) {
                    case "retract" -> AttachmentStatus.RETRACTED; case "evict" -> AttachmentStatus.EVICTED; case "status-inactive" -> AttachmentStatus.INACTIVE;
                    default -> isActive ? AttachmentStatus.ACTIVE : AttachmentStatus.INACTIVE;
                };
            }
            public AttachmentViewModel withStatus(AttachmentStatus newStatus) { return new AttachmentViewModel(id, noteId, content, attachmentType, newStatus, priority, depth, timestamp, associatedNoteId, kbId, justifications, llmStatus); }
            public AttachmentViewModel withLlmUpdate(LlmStatus newLlmStatus, String newContent) { return new AttachmentViewModel(id, noteId, newContent, attachmentType, status, priority, depth, timestamp, associatedNoteId, kbId, justifications, newLlmStatus); }
            public boolean isKifBased() { return switch (attachmentType) { case FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION -> true; default -> false; }; }
            @Override public int compareTo(AttachmentViewModel other) {
                var cmp = Integer.compare(status.ordinal(), other.status.ordinal()); if (cmp != 0) return cmp;
                cmp = Integer.compare(attachmentType.ordinal(), other.attachmentType.ordinal()); if (cmp != 0) return cmp;
                if (isKifBased()) { cmp = Double.compare(other.priority, this.priority); if (cmp != 0) return cmp; cmp = Integer.compare(this.depth, other.depth); if (cmp != 0) return cmp; }
                return Long.compare(other.timestamp, this.timestamp);
            }
        }

        static class AttachmentListCellRenderer extends JPanel implements ListCellRenderer<AttachmentViewModel> {
            private final JLabel iconLabel = new JLabel(); private final JLabel contentLabel = new JLabel(); private final JLabel detailLabel = new JLabel();
            private final Border activeBorder = new CompoundBorder(new LineBorder(Color.LIGHT_GRAY, 1), new EmptyBorder(3, 5, 3, 5));
            private final Border inactiveBorder = new CompoundBorder(new LineBorder(new Color(240, 240, 240), 1), new EmptyBorder(3, 5, 3, 5));
            private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
            AttachmentListCellRenderer() {
                setLayout(new BorderLayout(5, 0)); setOpaque(true); var textPanel = new JPanel(new BorderLayout()); textPanel.setOpaque(false);
                textPanel.add(contentLabel, BorderLayout.CENTER); textPanel.add(detailLabel, BorderLayout.SOUTH); add(iconLabel, BorderLayout.WEST); add(textPanel, BorderLayout.CENTER);
                contentLabel.setFont(MONOSPACED_FONT); detailLabel.setFont(UI_SMALL_FONT); iconLabel.setFont(UI_DEFAULT_FONT.deriveFont(Font.BOLD)); iconLabel.setBorder(new EmptyBorder(0, 4, 0, 4)); iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            }
            @Override public Component getListCellRendererComponent(JList<? extends AttachmentViewModel> list, AttachmentViewModel value, int index, boolean isSelected, boolean cellHasFocus) {
                contentLabel.setText(value.content()); contentLabel.setFont(MONOSPACED_FONT); String iconText; Color iconColor, bgColor = Color.WHITE, fgColor = Color.BLACK;
                switch (value.attachmentType) {
                    case FACT -> { iconText = "F"; iconColor = new Color(0, 128, 0); bgColor = new Color(235, 255, 235); }
                    case DERIVED -> { iconText = "D"; iconColor = Color.BLUE; bgColor = new Color(230, 240, 255); contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC)); }
                    case UNIVERSAL -> { iconText = "∀"; iconColor = new Color(0, 0, 128); bgColor = new Color(235, 235, 255); }
                    case SKOLEMIZED -> { iconText = "∃"; iconColor = new Color(139, 69, 19); bgColor = new Color(255, 255, 230); contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC)); }
                    case SUMMARY -> { iconText = "Σ"; iconColor = Color.DARK_GRAY; bgColor = new Color(240, 240, 240); }
                    case CONCEPT -> { iconText = "C"; iconColor = Color.DARK_GRAY; bgColor = new Color(240, 240, 240); }
                    case QUESTION -> { iconText = "?"; iconColor = Color.MAGENTA; bgColor = new Color(255, 240, 255); }
                    case LLM_ERROR -> { iconText = "!"; iconColor = Color.RED; bgColor = new Color(255, 230, 230); }
                    case LLM_INFO -> { iconText = "i"; iconColor = Color.GRAY; }
                    case QUERY_SENT -> { iconText = "->"; iconColor = new Color(0, 150, 150); }
                    case QUERY_RESULT -> { iconText = "<-"; iconColor = new Color(0, 150, 150); }
                    default -> { iconText = "*"; iconColor = Color.BLACK; }
                }
                var kbDisplay = ofNullable(value.kbId()).map(id -> switch (id) { case GLOBAL_KB_NOTE_ID -> " [KB:G]"; case "unknown" -> ""; default -> " [KB:" + id.replace(ID_PREFIX_NOTE, "") + "]"; }).orElse("");
                var assocNoteDisplay = ofNullable(value.associatedNoteId()).filter(id -> !id.equals(value.kbId())).map(id -> " (N:" + id.replace(ID_PREFIX_NOTE, "") + ")").orElse("");
                var timeStr = timeFormatter.format(Instant.ofEpochMilli(value.timestamp()));
                var details = switch (value.attachmentType) {
                    case FACT, DERIVED, UNIVERSAL, SKOLEMIZED, SUMMARY, CONCEPT, QUESTION -> String.format("P:%.3f | D:%d | %s%s%s", value.priority(), value.depth(), timeStr, assocNoteDisplay, kbDisplay);
                    case LLM_INFO, LLM_ERROR -> String.format("%s | %s%s", value.llmStatus(), timeStr, kbDisplay);
                    case QUERY_SENT, QUERY_RESULT -> String.format("%s | %s%s", value.attachmentType, timeStr, kbDisplay);
                    default -> String.format("%s%s", timeStr, kbDisplay);
                };
                detailLabel.setText(details); iconLabel.setText(iconText);
                if (value.status != AttachmentStatus.ACTIVE && value.isKifBased()) {
                    fgColor = Color.LIGHT_GRAY; contentLabel.setText("<html><strike>" + value.content().replace("<", "&lt;").replace(">", "&gt;") + "</strike></html>"); detailLabel.setText(value.status + " | " + details); bgColor = new Color(248, 248, 248); setBorder(inactiveBorder); iconColor = Color.LIGHT_GRAY;
                } else { setBorder(activeBorder); }
                if (value.attachmentType == AttachmentType.LLM_INFO || value.attachmentType == AttachmentType.LLM_ERROR) {
                    switch (value.llmStatus) {
                        case SENDING, PROCESSING -> { bgColor = new Color(255, 255, 200); iconColor = Color.ORANGE; }
                        case ERROR -> { bgColor = new Color(255, 220, 220); iconColor = Color.RED; }
                        case CANCELLED -> { fgColor = Color.GRAY; contentLabel.setText("<html><strike>" + value.content().replace("<", "&lt;").replace(">", "&gt;") + "</strike></html>"); bgColor = new Color(230, 230, 230); iconColor = Color.GRAY; }
                        default -> {}
                    }
                }
                if (isSelected) { setBackground(list.getSelectionBackground()); contentLabel.setForeground(list.getSelectionForeground()); detailLabel.setForeground(list.getSelectionForeground()); iconLabel.setForeground(list.getSelectionForeground()); }
                else { setBackground(bgColor); contentLabel.setForeground(fgColor); detailLabel.setForeground((value.status == AttachmentStatus.ACTIVE || !value.isKifBased()) ? Color.GRAY : Color.LIGHT_GRAY); iconLabel.setForeground(iconColor); }
                var justList = (value.justifications() == null || value.justifications().isEmpty()) ? "None" : String.join(", ", value.justifications());
                setToolTipText(String.format("<html>ID: %s<br>KB: %s<br>Associated Note: %s<br>Status: %s<br>LLM Status: %s<br>Type: %s<br>Priority: %.4f<br>Depth: %d<br>Timestamp: %s<br>Justifications: %s</html>", value.id, value.kbId() != null ? value.kbId() : "N/A", value.associatedNoteId() != null ? value.associatedNoteId() : "N/A", value.status, value.llmStatus, value.attachmentType, value.priority(), value.depth(), Instant.ofEpochMilli(value.timestamp()).toString(), justList));
                return this;
            }
        }

        static class SettingsDialog extends JDialog {
            private final JTextField llmUrlField, llmModelField; private final JSpinner kbCapacitySpinner, depthLimitSpinner; private final JCheckBox broadcastInputCheckbox; private final Cog systemRef;
            SettingsDialog(Frame owner, Cog cog) {
                super(owner, "Settings", true); this.systemRef = cog; setSize(500, 300); setLocationRelativeTo(owner); setLayout(new BorderLayout(10, 10));
                var config = new Configuration(cog); llmUrlField = new JTextField(config.llmApiUrl()); llmModelField = new JTextField(config.llmModel());
                kbCapacitySpinner = new JSpinner(new SpinnerNumberModel(config.globalKbCapacity(), 1024, 1024 * 1024, 1024)); depthLimitSpinner = new JSpinner(new SpinnerNumberModel(config.reasoningDepthLimit(), 1, 32, 1));
                broadcastInputCheckbox = new JCheckBox("Broadcast Input Assertions via WebSocket", config.broadcastInputAssertions());
                var formPanel = new JPanel(new GridBagLayout()); var gbc = new GridBagConstraints(); gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.insets = new Insets(5, 5, 5, 5);
                formPanel.add(new JLabel("LLM API URL:"), gbc); gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; formPanel.add(llmUrlField, gbc);
                gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0; formPanel.add(new JLabel("LLM Model:"), gbc); gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; formPanel.add(llmModelField, gbc);
                gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0; formPanel.add(new JLabel("Global KB Capacity:"), gbc); gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; formPanel.add(kbCapacitySpinner, gbc);
                gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0; formPanel.add(new JLabel("Reasoning Depth Limit:"), gbc); gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; formPanel.add(depthLimitSpinner, gbc);
                gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; formPanel.add(broadcastInputCheckbox, gbc);
                var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); var saveButton = new JButton("Save"); var cancelButton = new JButton("Cancel");
                saveButton.addActionListener(e -> saveSettings()); cancelButton.addActionListener(e -> dispose()); buttonPanel.add(saveButton); buttonPanel.add(cancelButton);
                add(formPanel, BorderLayout.CENTER); add(buttonPanel, BorderLayout.SOUTH); ((JPanel) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));
            }
            private void saveSettings() {
                var newConfigJson = new JSONObject().put("llmApiUrl", llmUrlField.getText()).put("llmModel", llmModelField.getText()).put("globalKbCapacity", (Integer) kbCapacitySpinner.getValue()).put("reasoningDepthLimit", (Integer) depthLimitSpinner.getValue()).put("broadcastInputAssertions", broadcastInputCheckbox.isSelected());
                if (systemRef.updateConfig(newConfigJson.toString())) dispose();
                else JOptionPane.showMessageDialog(this, "Invalid JSON format in Configuration. Please correct.", "Configuration Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private class MyWebSocketServer extends WebSocketServer {
        public MyWebSocketServer(InetSocketAddress address) { super(address); }
        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) { System.out.println("WS Client connected: " + conn.getRemoteSocketAddress()); }
        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) { System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress() + " Code: " + code + " Reason: " + requireNonNullElse(reason, "N/A")); }
        @Override public void onStart() { System.out.println("System WebSocket listener active on port " + getPort() + "."); setConnectionLostTimeout(WS_CONNECTION_LOST_TIMEOUT_MS); }
        @Override public void onError(WebSocket conn, Exception ex) {
            var addr = ofNullable(conn).map(WebSocket::getRemoteSocketAddress).map(Object::toString).orElse("server"); var msg = ofNullable(ex.getMessage()).orElse("");
            if (ex instanceof IOException && (msg.contains("Socket closed") || msg.contains("Connection reset") || msg.contains("Broken pipe"))) System.err.println("WS Network Info from " + addr + ": " + msg);
            else { System.err.println("WS Error from " + addr + ": " + msg); ex.printStackTrace(); }
        }
        @Override public void onMessage(WebSocket conn, String message) {
            var trimmed = message.trim(); if (trimmed.isEmpty()) return; var sourceId = "ws:" + conn.getRemoteSocketAddress().toString();
            var parts = trimmed.split("\\s+", 2); var command = parts[0].toLowerCase(); var argument = (parts.length > 1) ? parts[1] : "";
            switch (command) {
                case "retract" -> { if (!argument.isEmpty()) events.emit(new RetractionRequestEvent(argument, RetractionType.BY_ID, sourceId, null)); else System.err.println("WS Retract Error from " + sourceId + ": Missing assertion ID."); }
                case "query" -> {
                    try {
                        var terms = KifParser.parseKif(argument); if (terms.size() != 1 || !(terms.getFirst() instanceof KifList queryPattern)) { conn.send("error Query must be a single KIF list."); return; }
                        var queryId = generateId(ID_PREFIX_QUERY); var query = new Query(queryId, QueryType.ASK_BINDINGS, queryPattern, null, Map.of()); events.emit(new QueryRequestEvent(query));
                    } catch (ParseException e) { conn.send("error Parse error: " + e.getMessage()); }
                }
                default -> {
                    try { KifParser.parseKif(trimmed).forEach(term -> events.emit(new ExternalInputEvent(term, sourceId, null))); }
                    catch (ParseException | ClassCastException e) { System.err.printf("WS Message Parse Error from %s: %s | Original: %s...%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW))); }
                    catch (Exception e) { System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage()); e.printStackTrace(); }
                }
            }
        }
    }
}
