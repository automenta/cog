package dumb.cognote15;


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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

public class CogNote {

    public static final String KIF_OP_IMPLIES = "=>", KIF_OP_EQUIV = "<=>", KIF_OP_AND = "and", KIF_OP_OR = "or",
            KIF_OP_EXISTS = "exists", KIF_OP_FORALL = "forall", KIF_OP_EQUAL = "=", KIF_OP_NOT = "not";
    private static final Set<String> REFLEXIVE_PREDICATES = Set.of("instance", "subclass", "subrelation", "equivalent", "same", "equal", "domain", "range");

    private static final int UI_FONT_SIZE = 16;
    private static final Font MONOSPACED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, UI_FONT_SIZE - 2);
    private static final Font UI_DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE);
    private static final Font UI_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, UI_FONT_SIZE - 4);

    private static final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private static final String ID_PREFIX_RULE = "rule_", ID_PREFIX_FACT = "fact_", ID_PREFIX_SKOLEM_FUNC = "skf_",
            ID_PREFIX_SKOLEM_CONST = "skc_", ID_PREFIX_NOTE = "note-", ID_PREFIX_LLM_ITEM = "llm_",
            ID_PREFIX_INPUT_ITEM = "input_", ID_PREFIX_TEMP_ITEM = "temp_", ID_PREFIX_PLUGIN = "plugin_";

    private static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_LLM_MODEL = "hf.co/mradermacher/phi-4-GGUF:Q4_K_S";
    private static final int HTTP_TIMEOUT_SECONDS = 90;
    private static final double DEFAULT_RULE_PRIORITY = 1.0;
    private static final double INPUT_ASSERTION_BASE_PRIORITY = 10.0;
    private static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    private static final double DERIVED_PRIORITY_DECAY = 0.95;
    private static final int MAX_DERIVATION_DEPTH = 4;
    private static final int MAX_DERIVED_TERM_WEIGHT = 150;
    private static final int KB_SIZE_THRESHOLD_WARN_PERCENT = 90;
    private static final int KB_SIZE_THRESHOLD_HALT_PERCENT = 98;
    private static final int WS_STOP_TIMEOUT_MS = 1000;
    private static final int WS_CONNECTION_LOST_TIMEOUT_MS = 100;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final int MAX_KIF_PARSE_PREVIEW = 50;
    private static final int MAX_WS_PARSE_PREVIEW = 100;

    final EventBus eventBus;
    final PluginManager pluginManager;
    final CogNoteContext context;
    final HttpClient http;
    final SwingUI swingUI;
    final MyWebSocketServer websocket;
    final ExecutorService mainExecutor = Executors.newVirtualThreadPerTaskExecutor();

    final boolean broadcastInputAssertions;
    final String llmApiUrl;
    final String llmModel;
    final int globalKbCapacity;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    volatile String systemStatus = "Initializing";

    public CogNote(int port, int kbCapacity, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        this.globalKbCapacity = kbCapacity;
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = requireNonNullElse(llmUrl, DEFAULT_LLM_URL);
        this.llmModel = requireNonNullElse(llmModel, DEFAULT_LLM_MODEL);
        this.swingUI = requireNonNull(ui, "SwingUI cannot be null");

        this.eventBus = new EventBus(mainExecutor);
        this.context = new CogNoteContext(kbCapacity, eventBus);
        this.pluginManager = new PluginManager(eventBus, context);

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .executor(mainExecutor)
                .build();
        this.websocket = new MyWebSocketServer(new InetSocketAddress(port));

        System.out.printf("System config: Port=%d, KBSize=%d, BroadcastInput=%b, LLM_URL=%s, LLM_Model=%s, MaxDepth=%d%n",
                port, kbCapacity, broadcastInputAssertions, llmApiUrl, llmModel, MAX_DERIVATION_DEPTH);
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
                ui.setSystemReference(server);

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    server.stopSystem();
                }));

                server.startSystem();

                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified.");

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
        System.err.printf("""
                Usage: java %s [options]
                Options:
                  -p, --port <port>           WebSocket server port (default: 8887)
                  -k, --kb-size <maxKbSize>   Max global KB assertion count (default: 65536)
                  -r, --rules <rulesFile>     Path to file with initial KIF rules/facts
                  --llm-url <url>             URL for the LLM API (default: %s)
                  --llm-model <model>         LLM model name (default: %s)
                  --broadcast-input           Broadcast input assertions via WebSocket (default: false)
                """, CogNote.class.getName(), DEFAULT_LLM_URL, DEFAULT_LLM_MODEL);
        System.exit(1);
    }

    public static String generateId(String prefix) {
        return prefix + idCounter.incrementAndGet();
    }

    static boolean isTrivial(KifList list) {
        var s = list.size();
        var opOpt = list.getOperator();
        if (s >= 3 && list.get(1).equals(list.get(2))) {
            return opOpt.filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
        } else if (opOpt.filter(KIF_OP_NOT::equals).isPresent() && s == 2 && list.get(1) instanceof KifList inner) {
            return inner.size() >= 3 && inner.get(1).equals(inner.get(2)) &&
                    inner.getOperator().filter(op -> REFLEXIVE_PREDICATES.contains(op) || op.equals(KIF_OP_EQUAL)).isPresent();
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

    private void setupDefaultPlugins() {
        pluginManager.loadPlugin(new InputProcessingPlugin());
        pluginManager.loadPlugin(new CommitPlugin());
        pluginManager.loadPlugin(new RetractionPlugin());
        pluginManager.loadPlugin(new ForwardChainingPlugin());
        pluginManager.loadPlugin(new RewriteRulePlugin());
        pluginManager.loadPlugin(new UniversalInstantiationPlugin());
        pluginManager.loadPlugin(new StatusUpdaterPlugin((x) -> updateStatusLabel(x.statusMessage)));
        pluginManager.loadPlugin(new WebSocketBroadcasterPlugin(this));
        pluginManager.loadPlugin(new UiUpdatePlugin(swingUI));
    }

    public void startSystem() {
        if (!running.get()) {
            System.err.println("Cannot restart a stopped system.");
            return;
        }
        paused.set(false);
        systemStatus = "Starting";
        updateStatusLabel();

        setupDefaultPlugins();
        pluginManager.initializeAll();

        try {
            websocket.start();
            System.out.println("WebSocket server started on port " + websocket.getPort());
        } catch (IllegalStateException e) {
            System.err.println("WebSocket server already started or failed to start: " + e.getMessage());
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

        pluginManager.shutdownAll();

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
        eventBus.publish(new SystemStatusEvent(systemStatus, context.getKbCount(), context.getTotalKbCapacity(), 0, 0, context.getRuleCount()));
    }

    public void clearAllKnowledge() {
        System.out.println("Clearing all knowledge...");
        setPaused(true);

        context.getAllNoteIds().forEach(noteId ->
                eventBus.publish(new RetractionRequestEvent(noteId, RetractionType.BY_NOTE, "UI-ClearAll", noteId))
        );

        context.clearAll();

        SwingUtilities.invokeLater(swingUI::clearAllUILists);

        systemStatus = "Cleared";
        updateStatusLabel();
        setPaused(false);
        System.out.println("Knowledge cleared.");
        eventBus.publish(new SystemStatusEvent(systemStatus, 0, globalKbCapacity, 0, 0, 0));
    }

    public void loadExpressionsFromFile(String filename) throws IOException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        if (!Files.exists(path) || !Files.isReadable(path))
            throw new IOException("File not found or not readable: " + filename);

        var kifBuffer = new StringBuilder();
        long[] counts = {0, 0, 0};

        try (Reader reader = Files.newBufferedReader(path)) {
            String line;
            var parenDepth = 0;
            while ((line = readLine(reader)) != null) {
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
                                eventBus.publish(new ExternalInputEvent(term, "file:" + filename, null));
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
                    parenDepth = 0;
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d KIF blocks from %s, published %d input events.%n", counts[1], filename, counts[2]);
    }

    private String readLine(Reader reader) throws IOException {
        var line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            line.append((char) c);
            if (c == '\n') break;
        }
        return (line.isEmpty() && c == -1) ? null : line.toString();
    }


    private CompletableFuture<String> llmAsync(String prompt, String interactionType, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            waitIfPaused();
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

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("LLM API request failed (" + interactionType + "): " + response.statusCode() + " Body: " + responseBody);
                }
                return extractLlmContent(new JSONObject(new JSONTokener(responseBody)))
                        .orElseThrow(() -> new IOException("LLM response missing expected content field. Body: " + responseBody));

            } catch (IOException | InterruptedException e) {
                System.err.printf("LLM API interaction failed (%s for note %s): %s%n", interactionType, noteId, e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException("LLM API communication error (" + interactionType + ")", e);
            } catch (Exception e) {
                System.err.printf("LLM response processing failed (%s for note %s): %s%n", interactionType, noteId, e.getMessage());
                e.printStackTrace();
                throw new CompletionException("LLM response processing error (" + interactionType + ")", e);
            }
        }, mainExecutor);
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
                    var statusType = (ex != null) ? SwingUI.AssertionDisplayType.LLM_ERROR :
                            (cleanedKif == null || cleanedKif.trim().isEmpty() ? SwingUI.AssertionDisplayType.LLM_INFO : SwingUI.AssertionDisplayType.LLM_INFO);
                    var statusContent = (ex != null) ? "KIF Generation Error: " + ex.getMessage() :
                            (cleanedKif == null || cleanedKif.trim().isEmpty() ? "KIF Generation Warning: No valid KIF found." : "KIF Generation Complete.");
                    var vm = new SwingUI.AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + "kif_result"), noteId, statusContent, statusType, SwingUI.AssertionStatus.ACTIVE, 0, 0, System.currentTimeMillis(), noteId);
                    eventBus.publish(new LlmResponseEvent(vm));

                    if (ex == null && cleanedKif != null && !cleanedKif.trim().isEmpty()) {
                        System.out.printf("LLM Success (KIF %s): Extracted KIF assertions.%n", noteId);
                        try {
                            KifParser.parseKif(cleanedKif).forEach(term ->
                                    eventBus.publish(new ExternalInputEvent(term, "llm-kif:" + noteId, noteId))
                            );
                        } catch (ParseException parseEx) {
                            System.err.printf("LLM Error (KIF %s): Failed to parse generated KIF: %s%n", noteId, parseEx.getMessage());
                            var errVm = new SwingUI.AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + "kif_parse_error"), noteId, "KIF Parse Error: " + parseEx.getMessage(), SwingUI.AssertionDisplayType.LLM_ERROR, SwingUI.AssertionStatus.ACTIVE, 0, 0, System.currentTimeMillis(), noteId);
                            eventBus.publish(new LlmResponseEvent(errVm));
                        }
                    } else if (ex == null) {
                        System.err.printf("LLM Warning (KIF %s): Result contained text but no valid KIF lines found after cleaning.%n", noteId);
                    } else {
                        System.err.printf("LLM Error (KIF %s): %s%n", noteId, ex.getMessage());
                    }
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
        return llmAsync(finalPrompt, "Note Enhancement", noteId)
                .whenComplete((response, ex) -> {
                    var statusType = (ex != null) ? SwingUI.AssertionDisplayType.LLM_ERROR :
                            (response == null || response.isBlank() ? SwingUI.AssertionDisplayType.LLM_INFO : SwingUI.AssertionDisplayType.LLM_INFO);
                    var statusContent = (ex != null) ? "Enhancement Error: " + ex.getMessage() :
                            (response == null || response.isBlank() ? "Enhancement Warning: Empty response." : "Enhancement Complete.");
                    var vm = new SwingUI.AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + "enhance_result"), noteId, statusContent, statusType, SwingUI.AssertionStatus.ACTIVE, 0, 0, System.currentTimeMillis(), noteId);
                    eventBus.publish(new LlmResponseEvent(vm));
                });
    }

    public CompletableFuture<String> summarizeNoteWithLlmAsync(String noteText, String noteId) {
        var finalPrompt = """
                Summarize the following note in one or two concise sentences. Output ONLY the summary.
                
                Note:
                "%s"
                
                Summary:""".formatted(noteText);
        return llmAsync(finalPrompt, "Note Summarization", noteId)
                .whenComplete((response, ex) -> {
                    var statusType = (ex != null) ? SwingUI.AssertionDisplayType.LLM_ERROR : SwingUI.AssertionDisplayType.LLM_SUMMARY;
                    var statusContent = (ex != null) ? "Summary Error: " + ex.getMessage() :
                            (response == null || response.isBlank() ? "Summary Warning: Empty response." : response);
                    var vm = new SwingUI.AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + "summary_result"), noteId, statusContent, statusType, SwingUI.AssertionStatus.ACTIVE, 0, 0, System.currentTimeMillis(), noteId);
                    eventBus.publish(new LlmResponseEvent(vm));
                });
    }

    public CompletableFuture<String> keyConceptsWithLlmAsync(String noteText, String noteId) {
        var finalPrompt = """
                Identify the key concepts or entities mentioned in the following note. List them separated by commas. Output ONLY the comma-separated list.
                
                Note:
                "%s"
                
                Key Concepts:""".formatted(noteText);
        return llmAsync(finalPrompt, "Key Concept Identification", noteId)
                .whenComplete((response, ex) -> {
                    var statusType = (ex != null) ? SwingUI.AssertionDisplayType.LLM_ERROR : SwingUI.AssertionDisplayType.LLM_CONCEPTS;
                    var statusContent = (ex != null) ? "Concepts Error: " + ex.getMessage() :
                            (response == null || response.isBlank() ? "Concepts Warning: Empty response." : response);
                    var vm = new SwingUI.AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + "concepts_result"), noteId, statusContent, statusType, SwingUI.AssertionStatus.ACTIVE, 0, 0, System.currentTimeMillis(), noteId);
                    eventBus.publish(new LlmResponseEvent(vm));
                });
    }

    public CompletableFuture<String> generateQuestionsWithLlmAsync(String noteText, String noteId) {
        var finalPrompt = """
                Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.
                
                Note:
                "%s"
                
                Questions:""".formatted(noteText);
        return llmAsync(finalPrompt, "Question Generation", noteId)
                .whenComplete((response, ex) -> {
                    if (ex == null && response != null && !response.isBlank()) {
                        response.lines()
                                .map(q -> q.replaceFirst("^\\s*-\\s*", "").trim())
                                .filter(Predicate.not(String::isBlank))
                                .forEach(q -> {
                                    var vm = new SwingUI.AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + "question"), noteId, q, SwingUI.AssertionDisplayType.LLM_QUESTION, SwingUI.AssertionStatus.ACTIVE, 0, 0, System.currentTimeMillis(), noteId);
                                    eventBus.publish(new LlmResponseEvent(vm));
                                });
                    }
                    var statusType = (ex != null) ? SwingUI.AssertionDisplayType.LLM_ERROR : SwingUI.AssertionDisplayType.LLM_INFO;
                    var statusContent = (ex != null) ? "Question Gen Error: " + ex.getMessage() :
                            (response == null || response.isBlank() ? "Question Gen Warning: Empty response." : "Question Generation Complete.");
                    var statusVm = new SwingUI.AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + "question_result"), noteId, statusContent, statusType, SwingUI.AssertionStatus.ACTIVE, 0, 0, System.currentTimeMillis(), noteId);
                    eventBus.publish(new LlmResponseEvent(statusVm));
                });
    }

    private Optional<String> extractLlmContent(JSONObject r) {
        return ofNullable(r.optJSONObject("message")).map(m -> m.optString("content", null))
                .or(() -> ofNullable(r.optString("response", null)))
                .or(() -> ofNullable(r.optJSONArray("choices")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(c -> c.optJSONObject("message")).map(m -> m.optString("content", null)))
                .or(() -> ofNullable(r.optJSONArray("results")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(res -> res.optJSONObject("candidates")).map(cand -> cand.optJSONObject("content")).map(cont -> cont.optJSONArray("parts")).filter(Predicate.not(JSONArray::isEmpty)).map(p -> p.optJSONObject(0)).map(p -> p.optString("text", null)))
                .or(() -> findNestedContent(r));
    }

    private Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> obj.keySet().stream()
                    .filter(key -> key.toLowerCase().contains("content") || key.toLowerCase().contains("text") || key.toLowerCase().contains("response"))
                    .map(obj::opt)
                    .flatMap(val -> (val instanceof String s && !s.isBlank()) ? Stream.of(s) : Stream.empty())
                    .findFirst()
                    .or(() -> obj.keySet().stream().map(obj::opt).map(this::findNestedContent).flatMap(Optional::stream).findFirst());
            case JSONArray arr ->
                    IntStream.range(0, arr.length()).mapToObj(arr::opt).map(this::findNestedContent).flatMap(Optional::stream).findFirst();
            case String s -> Optional.of(s).filter(Predicate.not(String::isBlank));
            default -> Optional.empty();
        };
    }

    void updateStatusLabel() {
        if (swingUI != null && swingUI.isDisplayable()) {
            var kbCount = context.getKbCount();
            var kbCapacityTotal = context.getTotalKbCapacity();
            var statusText = String.format("KB: %d/%d | Rules: %d | Notes: %d | Status: %s",
                    kbCount, kbCapacityTotal, context.getRuleCount(), context.getAllNoteIds().size(), systemStatus);
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

    enum RetractionType {BY_ID, BY_NOTE, BY_RULE_FORM}

    enum AssertionType {GROUND, UNIVERSAL, SKOLEMIZED}

    interface CogNoteEvent {
        default String getAssociatedNoteId() {
            return null;
        }
    }

    interface Plugin {
        String getId();

        void initialize(EventBus eventBus, CogNoteContext context);

        void shutdown();
    }

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
                case KifList l ->
                        l.getOperator().filter(op -> op.startsWith(ID_PREFIX_SKOLEM_FUNC)).isPresent() || l.terms.stream().anyMatch(KifTerm::containsSkolemTerm);
                case KifVar ignored -> false;
            };
        }
    }

    record AssertionEvent(Assertion assertion, String noteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return noteId;
        }
    }

    record AssertionAddedEvent(Assertion assertion, String noteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return noteId;
        }
    }

    record AssertionRetractedEvent(Assertion assertion, String noteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return noteId;
        }
    }

    record AssertionEvictedEvent(Assertion assertion, String noteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return noteId;
        }
    }

    record PotentialAssertionEvent(PotentialAssertion potentialAssertion, String targetNoteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return targetNoteId;
        }
    }

    record TemporaryAssertionEvent(KifList temporaryAssertion, Map<KifVar, KifTerm> bindings,
                                   String sourceNoteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return sourceNoteId;
        }
    }

    record RuleEvent(Rule rule) implements CogNoteEvent {
    }

    record RuleAddedEvent(Rule rule) implements CogNoteEvent {
    }

    record RuleRemovedEvent(Rule rule) implements CogNoteEvent {
    }

    record LlmResponseEvent(SwingUI.AssertionViewModel llmItem) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return llmItem.noteId();
        }
    }

    record SystemStatusEvent(String statusMessage, int kbCount, int kbCapacity, int taskQueueSize, int commitQueueSize,
                             int ruleCount) implements CogNoteEvent {
    }

    record NoteEvent(SwingUI.Note note) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return note.id;
        }
    }

    record NoteAddedEvent(SwingUI.Note note) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return note.id;
        }
    }

    record NoteRemovedEvent(SwingUI.Note note) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return note.id;
        }
    }

    record ExternalInputEvent(KifTerm term, String sourceId, @Nullable String targetNoteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return targetNoteId;
        }
    }

    record RetractionRequestEvent(String target, RetractionType type, String sourceId,
                                  @Nullable String targetNoteId) implements CogNoteEvent {
        @Override
        public String getAssociatedNoteId() {
            return targetNoteId;
        }
    }

    record WebSocketBroadcastEvent(String message) implements CogNoteEvent {
    }

    static class EventBus {
        private final ConcurrentMap<Class<? extends CogNoteEvent>, CopyOnWriteArrayList<Consumer<CogNoteEvent>>> listeners = new ConcurrentHashMap<>();
        private final ConcurrentMap<KifTerm, CopyOnWriteArrayList<BiConsumer<CogNoteEvent, Map<KifVar, KifTerm>>>> patternListeners = new ConcurrentHashMap<>();
        private final ExecutorService executor;

        EventBus(ExecutorService executor) {
            this.executor = requireNonNull(executor);
        }

        public <T extends CogNoteEvent> void subscribe(Class<T> eventType, Consumer<T> listener) {
            listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(event -> listener.accept(eventType.cast(event)));
        }

        public void subscribePattern(KifTerm pattern, BiConsumer<CogNoteEvent, Map<KifVar, KifTerm>> listener) {
            patternListeners.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        public void publish(CogNoteEvent event) {
            executor.submit(() -> {
                listeners.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>()).forEach(listener -> safeExecute(listener, event, "Direct Listener"));
                if (event instanceof AssertionAddedEvent aaEvent)
                    handlePatternMatching(aaEvent.assertion().kif(), event);
                else if (event instanceof TemporaryAssertionEvent taEvent)
                    handlePatternMatching(taEvent.temporaryAssertion(), event);
            });
        }

        private void handlePatternMatching(KifTerm eventTerm, CogNoteEvent event) {
            patternListeners.forEach((pattern, listeners) ->
                    ofNullable(Unifier.match(pattern, eventTerm, Map.of())).ifPresent(bindings ->
                            listeners.forEach(listener -> safeExecutePattern(listener, event, bindings, "Pattern Listener"))
                    )
            );
        }

        private void safeExecute(Consumer<CogNoteEvent> listener, CogNoteEvent event, String type) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                System.err.printf("Error in %s for %s: %s%n", type, event.getClass().getSimpleName(), e.getMessage());
                e.printStackTrace();
            }
        }

        private void safeExecutePattern(BiConsumer<CogNoteEvent, Map<KifVar, KifTerm>> listener, CogNoteEvent event, Map<KifVar, KifTerm> bindings, String type) {
            try {
                listener.accept(event, bindings);
            } catch (Exception e) {
                System.err.printf("Error in %s for %s (Pattern Match): %s%n", type, event.getClass().getSimpleName(), e.getMessage());
                e.printStackTrace();
            }
        }

        public void shutdown() {
            listeners.clear();
            patternListeners.clear();
        }
    }

    static class PluginManager {
        private final EventBus eventBus;
        private final CogNoteContext context;
        private final List<Plugin> plugins = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        PluginManager(EventBus eventBus, CogNoteContext context) {
            this.eventBus = eventBus;
            this.context = context;
        }

        public void loadPlugin(Plugin plugin) {
            if (initialized.get()) {
                System.err.println("Cannot load plugin " + plugin.getId() + " after initialization.");
                return;
            }
            plugins.add(plugin);
            System.out.println("Plugin loaded: " + plugin.getId());
        }

        public void initializeAll() {
            if (!initialized.compareAndSet(false, true)) return;
            System.out.println("Initializing " + plugins.size() + " plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.initialize(eventBus, context);
                    System.out.println("Initialized plugin: " + plugin.getId());
                } catch (Exception e) {
                    System.err.println("Failed to initialize plugin " + plugin.getId() + ": " + e.getMessage());
                    e.printStackTrace();
                    plugins.remove(plugin);
                }
            });
            System.out.println("Plugin initialization complete.");
        }

        public void shutdownAll() {
            System.out.println("Shutting down " + plugins.size() + " plugins...");
            plugins.forEach(plugin -> {
                try {
                    plugin.shutdown();
                    System.out.println("Shutdown plugin: " + plugin.getId());
                } catch (Exception e) {
                    System.err.println("Error shutting down plugin " + plugin.getId() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
            plugins.clear();
            eventBus.shutdown();
            System.out.println("Plugin shutdown complete.");
        }
    }

    static class CogNoteContext {
        private final ConcurrentMap<String, KnowledgeBase> noteKbs = new ConcurrentHashMap<>();
        private final KnowledgeBase globalKb;
        private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
        private final EventBus eventBus;

        CogNoteContext(int globalKbCapacity, EventBus eventBus) {
            this.globalKb = new KnowledgeBase("global", globalKbCapacity, eventBus);
            this.eventBus = eventBus;
        }

        public KnowledgeBase getKb(@Nullable String noteId) {
            return (noteId == null) ? globalKb : noteKbs.computeIfAbsent(noteId, id -> new KnowledgeBase(id, globalKb.maxKbSize, eventBus));
        }

        public KnowledgeBase getGlobalKb() {
            return globalKb;
        }

        public Map<String, KnowledgeBase> getAllNoteKbs() {
            return Collections.unmodifiableMap(noteKbs);
        }

        public Set<String> getAllNoteIds() {
            return Collections.unmodifiableSet(noteKbs.keySet());
        }

        public Set<Rule> getRules() {
            return Collections.unmodifiableSet(rules);
        }

        public int getRuleCount() {
            return rules.size();
        }

        public int getKbCount() {
            return globalKb.getAssertionCount() + noteKbs.values().stream().mapToInt(KnowledgeBase::getAssertionCount).sum();
        }

        public int getTotalKbCapacity() {
            return globalKb.maxKbSize + noteKbs.size() * globalKb.maxKbSize;
        }

        public boolean addRule(Rule rule) {
            var added = rules.add(rule);
            if (added) eventBus.publish(new RuleAddedEvent(rule));
            return added;
        }

        public boolean removeRule(Rule rule) {
            var removed = rules.remove(rule);
            if (removed) eventBus.publish(new RuleRemovedEvent(rule));
            return removed;
        }

        public boolean removeRule(KifList ruleForm) {
            Rule removedRule = null;
            for (var r : rules) {
                if (r.form().equals(ruleForm)) {
                    removedRule = r;
                    break;
                }
            }
            if (removedRule != null && rules.remove(removedRule)) {
                eventBus.publish(new RuleRemovedEvent(removedRule));
                return true;
            }
            return false;
        }

        public void removeNoteKb(String noteId) {
            var kb = noteKbs.remove(noteId);
            if (kb != null) kb.clear();
        }

        public void clearAll() {
            globalKb.clear();
            noteKbs.values().forEach(KnowledgeBase::clear);
            noteKbs.clear();
            rules.clear();
        }

        public Optional<Assertion> findAssertionByIdAcrossKbs(String assertionId) {
            return globalKb.getAssertion(assertionId).or(() -> noteKbs.values().stream().map(kb -> kb.getAssertion(assertionId)).flatMap(Optional::stream).findFirst());
        }

        @Nullable
        public String findCommonSourceNodeId(Set<String> supportIds) {
            if (supportIds == null || supportIds.isEmpty()) return null;
            String commonId = null;
            var firstFound = false;
            Set<String> visited = new HashSet<>();
            Queue<String> toCheck = new LinkedList<>(supportIds);
            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                if (currentId == null || !visited.add(currentId)) continue;
                var assertionOpt = findAssertionByIdAcrossKbs(currentId);
                if (assertionOpt.isPresent()) {
                    var assertion = assertionOpt.get();
                    if (assertion.sourceNoteId() != null) {
                        if (!firstFound) {
                            commonId = assertion.sourceNoteId();
                            firstFound = true;
                        } else if (!commonId.equals(assertion.sourceNoteId())) return null;
                    } else if (assertion.derivationDepth() > 0 && !assertion.support().isEmpty()) {
                        assertion.support().forEach(toCheck::offer);
                    }
                }
            }
            return commonId;
        }

        public double calculateDerivedPri(Set<String> supportIds, double basePri) {
            return supportIds.isEmpty() ? basePri : supportIds.stream().map(this::findAssertionByIdAcrossKbs).flatMap(Optional::stream)
                    .mapToDouble(Assertion::pri).min().orElse(basePri) * DERIVED_PRIORITY_DECAY;
        }

        public int calculateDerivedDepth(Set<String> supportIds) {
            return supportIds.stream().map(this::findAssertionByIdAcrossKbs).flatMap(Optional::stream)
                    .mapToInt(Assertion::derivationDepth).max().orElse(-1);
        }

        public KifList performSkolemization(KifList body, Collection<KifVar> existentialVars, Map<KifVar, KifTerm> contextBindings) {
            Set<KifVar> freeVarsInBody = new HashSet<>(body.getVariables());
            freeVarsInBody.removeAll(existentialVars);
            var skolemArgs = freeVarsInBody.stream().map(fv -> Unifier.substFully(contextBindings.getOrDefault(fv, fv), contextBindings)).sorted(Comparator.comparing(KifTerm::toKifString)).toList();
            Map<KifVar, KifTerm> skolemMap = new HashMap<>();
            for (var exVar : existentialVars) {
                var skolemTerm = skolemArgs.isEmpty() ? KifAtom.of(ID_PREFIX_SKOLEM_CONST + exVar.name().substring(1) + "_" + idCounter.incrementAndGet())
                        : new KifList(Stream.concat(Stream.of(KifAtom.of(ID_PREFIX_SKOLEM_FUNC + exVar.name().substring(1) + "_" + idCounter.incrementAndGet())), skolemArgs.stream()).toList());
                skolemMap.put(exVar, skolemTerm);
            }
            var substituted = Unifier.subst(body, skolemMap);
            return (substituted instanceof KifList sl) ? sl : new KifList(substituted);
        }

        public KifList simplifyLogicalTerm(KifList term) {
            final var MAX_SIMPLIFY_DEPTH = 5;
            var current = term;
            for (var depth = 0; depth < MAX_SIMPLIFY_DEPTH; depth++) {
                var next = simplifyLogicalTermOnce(current);
                if (next.equals(current)) return current;
                current = next;
            }
            if (!term.equals(current))
                System.err.println("Warning: Simplification depth limit reached for: " + term.toKifString());
            return current;
        }

        private KifList simplifyLogicalTermOnce(KifList term) {
            if (term.getOperator().filter(KIF_OP_NOT::equals).isPresent() && term.size() == 2 && term.get(1) instanceof KifList nl && nl.getOperator().filter(KIF_OP_NOT::equals).isPresent() && nl.size() == 2 && nl.get(1) instanceof KifList inner)
                return simplifyLogicalTermOnce(inner);
            var changed = new boolean[]{false};
            var newTerms = term.terms().stream().map(subTerm -> {
                var simplifiedSub = (subTerm instanceof KifList sl) ? simplifyLogicalTermOnce(sl) : subTerm;
                if (!simplifiedSub.equals(subTerm)) changed[0] = true;
                return simplifiedSub;
            }).toList();
            return changed[0] ? new KifList(newTerms) : term;
        }
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
            var needsQuotes = value.isEmpty() || !SAFE_ATOM_PATTERN.matcher(value).matches() || value.chars().anyMatch(c -> Character.isWhitespace(c) || "()\";?".indexOf(c) != -1);
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
            if (!name.startsWith("?") || name.length() < 2)
                throw new IllegalArgumentException("Variable name must start with '?' and have length > 1: " + name);
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
        private volatile int hashCodeCache;
        private volatile boolean hashCodeCalculated = false;
        private volatile String kifStringCache;
        private volatile int weightCache = -1;
        private volatile Set<KifVar> variablesCache;
        private volatile Boolean containsVariableCache;
        private volatile Boolean containsSkolemCache;

        KifList(List<KifTerm> terms) {
            this.terms = List.copyOf(requireNonNull(terms));
        }

        KifList(KifTerm... terms) {
            this(List.of(terms));
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
            return terms.isEmpty() || !(terms.getFirst() instanceof KifAtom(var v)) ? Optional.empty() : Optional.of(v);
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
            if (containsSkolemCache == null) containsSkolemCache = KifTerm.super.containsSkolemTerm();
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
            return this == o || (o instanceof KifList that && this.hashCode() == that.hashCode() && terms.equals(that.terms));
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
            requireNonNull(assertionType);
            support = Set.copyOf(requireNonNull(support));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (isNegated != kif.getOperator().filter(KIF_OP_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for KIF: " + kif.toKifString());
            if (assertionType == AssertionType.UNIVERSAL && (kif.getOperator().filter(KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
                throw new IllegalArgumentException("Universal assertion must be (forall ...) with quantified vars: " + kif.toKifString());
            if (assertionType != AssertionType.UNIVERSAL && !quantifiedVars.isEmpty())
                throw new IllegalArgumentException("Only Universal assertions should have quantified vars: " + kif.toKifString());
        }

        @Override
        public int compareTo(Assertion other) {
            var priComp = Double.compare(other.pri, this.pri);
            if (priComp != 0) return priComp;
            var depthComp = Integer.compare(this.derivationDepth, other.derivationDepth);
            if (depthComp != 0) return depthComp;
            return Long.compare(other.timestamp, this.timestamp);
        }

        String toKifString() {
            return kif.toKifString();
        }

        KifTerm getEffectiveTerm() {
            return switch (assertionType) {
                case GROUND, SKOLEMIZED -> isNegated ? kif.get(1) : kif;
                case UNIVERSAL -> kif.get(2);
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
                list.terms().stream().skip(1).forEach(sub -> collectPredicatesRecursive(sub, predicates));
            } else if (term instanceof KifList list)
                list.terms().forEach(sub -> collectPredicatesRecursive(sub, predicates));
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
            if (!(ruleForm.getOperator().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent() && ruleForm.size() == 3))
                throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());
            var antTerm = ruleForm.get(1);
            var conTerm = ruleForm.get(2);
            var parsedAntecedents = switch (antTerm) {
                case KifList list when list.getOperator().filter(KIF_OP_AND::equals).isPresent() ->
                        list.terms().stream().skip(1).map(Rule::validateAntecedentClause).toList();
                case KifList list -> List.of(validateAntecedentClause(list));
                case KifTerm t when t.equals(KifAtom.of("true")) -> List.<KifTerm>of();
                default ->
                        throw new IllegalArgumentException("Antecedent must be a KIF list, (not list), (and ...), or true: " + antTerm.toKifString());
            };
            validateUnboundVariables(ruleForm, antTerm, conTerm);
            return new Rule(id, ruleForm, antTerm, conTerm, pri, parsedAntecedents);
        }

        private static KifTerm validateAntecedentClause(KifTerm term) {
            return switch (term) {
                case KifList list -> {
                    if (list.getOperator().filter(KIF_OP_NOT::equals).isPresent() && (list.size() != 2 || !(list.get(1) instanceof KifList)))
                        throw new IllegalArgumentException("Argument of 'not' in rule antecedent must be a list: " + list.toKifString());
                    yield list;
                }
                default ->
                        throw new IllegalArgumentException("Elements of rule antecedent must be lists or (not list): " + term.toKifString());
            };
        }

        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
            Set<KifVar> unboundConsequentVars = new HashSet<>(consequent.getVariables());
            unboundConsequentVars.removeAll(antecedent.getVariables());
            unboundConsequentVars.removeAll(getQuantifierBoundVariables(consequent));
            if (!unboundConsequentVars.isEmpty() && ruleForm.getOperator().filter(KIF_OP_IMPLIES::equals).isPresent())
                System.err.println("Warning: Rule consequent has variables not bound by antecedent or local quantifier: " + unboundConsequentVars.stream().map(KifVar::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKifString());
        }

        private static Set<KifVar> getQuantifierBoundVariables(KifTerm term) {
            Set<KifVar> bound = new HashSet<>();
            collectQuantifierBoundVariablesRecursive(term, bound);
            return Collections.unmodifiableSet(bound);
        }

        private static void collectQuantifierBoundVariablesRecursive(KifTerm term, Set<KifVar> boundVars) {
            switch (term) {
                case KifList list when list.size() == 3 && list.getOperator().filter(op -> op.equals(KIF_OP_EXISTS) || op.equals(KIF_OP_FORALL)).isPresent() -> {
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
            requireNonNull(sourceId);
            requireNonNull(derivedType);
            support = Set.copyOf(requireNonNull(support));
            quantifiedVars = List.copyOf(requireNonNull(quantifiedVars));
            if (isNegated != kif.getOperator().filter(KIF_OP_NOT::equals).isPresent())
                throw new IllegalArgumentException("Negation flag mismatch for potential KIF: " + kif.toKifString());
            if (derivedType == AssertionType.UNIVERSAL && (kif.getOperator().filter(KIF_OP_FORALL::equals).isEmpty() || quantifiedVars.isEmpty()))
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

    static class PathNode {
        static final Class<KifVar> VAR_MARKER = KifVar.class;
        static final Object LIST_MARKER = new Object();
        final ConcurrentMap<Object, PathNode> children = new ConcurrentHashMap<>();
        final Set<String> assertionIdsHere = ConcurrentHashMap.newKeySet();
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
                case KifList l -> l.getOperator().map(op -> (Object) op).orElse(PathNode.LIST_MARKER);
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
            if (queryTerm instanceof KifList)
                ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> collectAllAssertionIds(listNode, candidates));
            var specificNode = indexNode.children.get(getIndexKey(queryTerm));
            if (specificNode != null) {
                candidates.addAll(specificNode.assertionIdsHere);
                if (queryTerm instanceof KifList) collectAllAssertionIds(specificNode, candidates);
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
                if (queryPattern instanceof KifList) collectAllAssertionIds(specificNode, candidates);
            }
        }

        private void findGeneralizationsRecursive(KifTerm queryTerm, PathNode indexNode, Set<String> candidates) {
            if (indexNode == null) return;
            ofNullable(indexNode.children.get(PathNode.VAR_MARKER)).ifPresent(varNode -> collectAllAssertionIds(varNode, candidates));
            if (queryTerm instanceof KifList)
                ofNullable(indexNode.children.get(PathNode.LIST_MARKER)).ifPresent(listNode -> candidates.addAll(listNode.assertionIdsHere));
            ofNullable(indexNode.children.get(getIndexKey(queryTerm))).ifPresent(nextNode -> candidates.addAll(nextNode.assertionIdsHere));
        }

        @FunctionalInterface
        private interface TriConsumer<T, U, V> {
            void accept(T t, U u, V v);
        }
    }

    static class KnowledgeBase {
        final String kbId;
        final int maxKbSize;
        final EventBus eventBus;
        final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
        final PathIndex pathIndex = new PathIndex();
        final ConcurrentMap<KifAtom, Set<String>> universalIndex = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Set<String>> assertionDependencies = new ConcurrentHashMap<>();
        final PriorityBlockingQueue<String> groundEvictionQueue = new PriorityBlockingQueue<>(1024, Comparator.<String, Double>comparing(id -> ofNullable(assertionsById.get(id)).map(Assertion::pri).orElse(Double.MAX_VALUE)).thenComparing(id -> ofNullable(assertionsById.get(id)).map(Assertion::timestamp).orElse(Long.MAX_VALUE)));
        final ReadWriteLock kbLock = new ReentrantReadWriteLock();

        KnowledgeBase(String kbId, int maxKbSize, EventBus eventBus) {
            this.kbId = requireNonNull(kbId);
            this.maxKbSize = maxKbSize;
            this.eventBus = requireNonNull(eventBus);
        }

        int getAssertionCount() {
            return assertionsById.size();
        }

        List<String> getAllAssertionIds() {
            return List.copyOf(assertionsById.keySet());
        }

        Optional<Assertion> getAssertion(String id) {
            return ofNullable(assertionsById.get(id));
        }

        List<Assertion> getAllAssertions() {
            return List.copyOf(assertionsById.values());
        }

        Optional<Assertion> commitAssertion(PotentialAssertion pa) {
            kbLock.writeLock().lock();
            try {
                if (pa.kif() instanceof KifList kl && CogNote.isTrivial(kl)) return Optional.empty();
                var finalType = (pa.derivedType() == AssertionType.GROUND && pa.kif().containsSkolemTerm()) ? AssertionType.SKOLEMIZED : pa.derivedType();
                var alreadyExistsOrSubsumed = switch (finalType) {
                    case GROUND, SKOLEMIZED ->
                            isSubsumedInternal(pa.kif(), pa.isNegated()) || findExactMatchInternal(pa.kif()).isPresent();
                    case UNIVERSAL -> findExactMatchInternal(pa.kif()).isPresent();
                };
                if (alreadyExistsOrSubsumed) return Optional.empty();
                enforceKbCapacityInternal();
                if (assertionsById.size() >= maxKbSize) {
                    System.err.printf("Warning: KB '%s' full (%d/%d) after eviction attempt. Cannot add: %s%n", kbId, assertionsById.size(), maxKbSize, pa.kif().toKifString());
                    return Optional.empty();
                }
                var newId = generateId(ID_PREFIX_FACT + finalType.name().toLowerCase() + "_");
                var newAssertion = new Assertion(newId, pa.kif(), pa.pri(), System.currentTimeMillis(), pa.sourceNoteId(), pa.support(), finalType, pa.isEquality(), pa.isOrientedEquality(), pa.isNegated(), pa.quantifiedVars(), pa.derivationDepth());
                if (assertionsById.putIfAbsent(newId, newAssertion) != null) {
                    System.err.println("KB Commit Error (KB: " + kbId + "): ID collision for " + newId);
                    return Optional.empty();
                }
                switch (finalType) {
                    case GROUND, SKOLEMIZED -> {
                        pathIndex.add(newAssertion);
                        groundEvictionQueue.offer(newId);
                    }
                    case UNIVERSAL ->
                            newAssertion.getReferencedPredicates().forEach(pred -> universalIndex.computeIfAbsent(pred, _ -> ConcurrentHashMap.newKeySet()).add(newId));
                }
                newAssertion.support().forEach(supporterId -> assertionDependencies.computeIfAbsent(supporterId, _ -> ConcurrentHashMap.newKeySet()).add(newId));
                eventBus.publish(new AssertionAddedEvent(newAssertion, kbId));
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
                new HashSet<>(assertionsById.keySet()).forEach(id -> retractAssertionWithCascadeInternal(id, new HashSet<>()));
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
            return pathIndex.findUnifiable(queryTerm).stream().map(assertionsById::get).filter(Objects::nonNull).filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED);
        }

        Stream<Assertion> findInstancesOf(KifTerm queryPattern) {
            var patternIsNegated = (queryPattern instanceof KifList ql && ql.getOperator().filter(KIF_OP_NOT::equals).isPresent());
            return pathIndex.findInstances(queryPattern).stream().map(assertionsById::get).filter(Objects::nonNull).filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED).filter(a -> a.isNegated() == patternIsNegated).filter(a -> Unifier.match(queryPattern, a.kif(), Map.of()) != null);
        }

        List<Assertion> findRelevantUniversalAssertions(KifAtom predicate) {
            return universalIndex.getOrDefault(predicate, Set.of()).stream().map(assertionsById::get).filter(Objects::nonNull).toList();
        }

        private Assertion retractAssertionWithCascadeInternal(String id, Set<String> retractedInThisCascade) {
            if (!retractedInThisCascade.add(id)) return null;
            var removed = assertionsById.remove(id);
            if (removed != null) {
                switch (removed.assertionType()) {
                    case GROUND, SKOLEMIZED -> {
                        pathIndex.remove(removed);
                        groundEvictionQueue.remove(id);
                    }
                    case UNIVERSAL ->
                            removed.getReferencedPredicates().forEach(pred -> universalIndex.computeIfPresent(pred, (_, ids) -> {
                                ids.remove(id);
                                return ids.isEmpty() ? null : ids;
                            }));
                }
                removed.support().forEach(supporterId -> assertionDependencies.computeIfPresent(supporterId, (_, dependents) -> {
                    dependents.remove(id);
                    return dependents.isEmpty() ? null : dependents;
                }));
                ofNullable(assertionDependencies.remove(id)).ifPresent(dependents -> new HashSet<>(dependents).forEach(depId -> retractAssertionWithCascadeInternal(depId, retractedInThisCascade)));
                eventBus.publish(new AssertionRetractedEvent(removed, kbId));
            }
            return removed;
        }

        private boolean isSubsumedInternal(KifTerm term, boolean isNegated) {
            return pathIndex.findGeneralizations(term).stream().map(assertionsById::get).filter(Objects::nonNull).filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED).anyMatch(candidate -> candidate.isNegated() == isNegated && Unifier.match(candidate.getEffectiveTerm(), term, Map.of()) != null);
        }

        private Optional<Assertion> findExactMatchInternal(KifList kif) {
            return pathIndex.findInstances(kif).stream().map(assertionsById::get).filter(Objects::nonNull).filter(a -> a.kif().equals(kif)).findFirst().or(() -> assertionsById.values().stream().filter(a -> a.kif().equals(kif)).findFirst());
        }

        private void enforceKbCapacityInternal() {
            while (assertionsById.size() >= maxKbSize && !groundEvictionQueue.isEmpty()) {
                ofNullable(groundEvictionQueue.poll()).flatMap(this::getAssertion).filter(a -> a.assertionType() == AssertionType.GROUND || a.assertionType() == AssertionType.SKOLEMIZED)
                        .ifPresent(toEvict -> {
                            var evicted = retractAssertionWithCascadeInternal(toEvict.id(), new HashSet<>());
                            if (evicted != null) eventBus.publish(new AssertionEvictedEvent(evicted, kbId));
                        });
            }
        }

        private void checkResourceThresholds() {
            var currentSize = assertionsById.size();
            var warnThreshold = maxKbSize * KB_SIZE_THRESHOLD_WARN_PERCENT / 100;
            var haltThreshold = maxKbSize * KB_SIZE_THRESHOLD_HALT_PERCENT / 100;
            if (currentSize >= haltThreshold)
                System.err.printf("KB CRITICAL (KB: %s): Size %d/%d (%.1f%%)%n", kbId, currentSize, maxKbSize, 100.0 * currentSize / maxKbSize);
            else if (currentSize >= warnThreshold)
                System.out.printf("KB WARNING (KB: %s): Size %d/%d (%.1f%%)%n", kbId, currentSize, maxKbSize, 100.0 * currentSize / maxKbSize);
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

        private boolean isValidAtomChar(int c) {
            return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';';
        }

        private KifAtom parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                var c = consumeChar();
                if (c == '"') return KifAtom.of(sb.toString());
                if (c == -1) throw createParseException("Unmatched quote in string literal");
                if (c == '\\') {
                    var next = consumeChar();
                    if (next == -1) throw createParseException("EOF after escape character");
                    sb.append((char) switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\', '"' -> next;
                        default -> next;
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
                } else col++;
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
                    do consumeChar(); while (peek() != '\n' && peek() != '\r' && peek() != -1);
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
            return unifyRecursive(x, y, bindings, 0);
        }

        @Nullable
        static Map<KifVar, KifTerm> match(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings) {
            return matchRecursive(pattern, term, bindings, 0);
        }

        static KifTerm subst(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return substRecursive(term, bindings, 0);
        }

        static KifTerm substFully(KifTerm term, Map<KifVar, KifTerm> bindings) {
            return substRecursive(term, bindings, 0);
        }

        static Optional<KifTerm> rewrite(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate) {
            return rewriteRecursive(target, lhsPattern, rhsTemplate, 0);
        }

        @Nullable
        private static Map<KifVar, KifTerm> unifyRecursive(KifTerm x, KifTerm y, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var xSubst = substRecursive(x, bindings, depth + 1);
            var ySubst = substRecursive(y, bindings, depth + 1);
            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVar varX) return bindVariable(varX, ySubst, bindings, true, depth);
            if (ySubst instanceof KifVar varY) return bindVariable(varY, xSubst, bindings, true, depth);
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lx.size(); i++) {
                    currentBindings = unifyRecursive(lx.get(i), ly.get(i), currentBindings, depth + 1);
                    if (currentBindings == null) return null;
                }
                return currentBindings;
            }
            return null;
        }

        @Nullable
        private static Map<KifVar, KifTerm> matchRecursive(KifTerm pattern, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings == null || depth > MAX_SUBST_DEPTH) return null;
            var patternSubst = substRecursive(pattern, bindings, depth + 1);
            if (patternSubst instanceof KifVar varP) return bindVariable(varP, term, bindings, false, depth);
            if (patternSubst.equals(term)) return bindings;
            if (patternSubst instanceof KifList lp && term instanceof KifList lt && lp.size() == lt.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lp.size(); i++) {
                    currentBindings = matchRecursive(lp.get(i), lt.get(i), currentBindings, depth + 1);
                    if (currentBindings == null) return null;
                }
                return currentBindings;
            }
            return null;
        }

        private static KifTerm substRecursive(KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (bindings.isEmpty() || depth > MAX_SUBST_DEPTH || !term.containsVariable()) return term;
            return switch (term) {
                case KifAtom atom -> atom;
                case KifVar var ->
                        ofNullable(bindings.get(var)).map(boundTerm -> substRecursive(boundTerm, bindings, depth + 1)).orElse(var);
                case KifList list -> {
                    var newTerms = list.terms().stream().map(sub -> substRecursive(sub, bindings, depth + 1)).toList();
                    yield newTerms.equals(list.terms()) ? list : new KifList(newTerms);
                }
            };
        }

        @Nullable
        private static Map<KifVar, KifTerm> bindVariable(KifVar var, KifTerm value, Map<KifVar, KifTerm> bindings, boolean doOccursCheck, int depth) {
            if (var.equals(value)) return bindings;
            if (bindings.containsKey(var))
                return doOccursCheck ? unifyRecursive(bindings.get(var), value, bindings, depth + 1) : matchRecursive(bindings.get(var), value, bindings, depth + 1);
            var finalValue = substRecursive(value, bindings, depth + 1);
            if (doOccursCheck && occursCheckRecursive(var, finalValue, bindings, depth + 1)) return null;
            Map<KifVar, KifTerm> newBindings = new HashMap<>(bindings);
            newBindings.put(var, finalValue);
            return Collections.unmodifiableMap(newBindings);
        }

        private static boolean occursCheckRecursive(KifVar var, KifTerm term, Map<KifVar, KifTerm> bindings, int depth) {
            if (depth > MAX_SUBST_DEPTH) return true;
            var substTerm = substRecursive(term, bindings, depth + 1);
            return switch (substTerm) {
                case KifVar v -> var.equals(v);
                case KifList l -> l.terms().stream().anyMatch(t -> occursCheckRecursive(var, t, bindings, depth + 1));
                case KifAtom ignored -> false;
            };
        }

        private static Optional<KifTerm> rewriteRecursive(KifTerm target, KifTerm lhsPattern, KifTerm rhsTemplate, int depth) {
            if (depth > MAX_SUBST_DEPTH) return Optional.empty();
            return ofNullable(matchRecursive(lhsPattern, target, Map.of(), depth + 1)).map(bindings -> substRecursive(rhsTemplate, bindings, depth + 1)).or(() -> (target instanceof KifList tl) ? rewriteSubterms(tl, lhsPattern, rhsTemplate, depth + 1) : Optional.empty());
        }

        private static Optional<KifTerm> rewriteSubterms(KifList targetList, KifTerm lhsPattern, KifTerm rhsTemplate, int depth) {
            var changed = false;
            List<KifTerm> newSubTerms = new ArrayList<>(targetList.size());
            for (var subTerm : targetList.terms()) {
                var rewrittenSub = rewriteRecursive(subTerm, lhsPattern, rhsTemplate, depth);
                if (rewrittenSub.isPresent()) {
                    changed = true;
                    newSubTerms.add(rewrittenSub.get());
                } else newSubTerms.add(subTerm);
            }
            return changed ? Optional.of(new KifList(newSubTerms)) : Optional.empty();
        }
    }

    abstract static class BasePlugin implements Plugin {
        protected final String id = generateId(ID_PREFIX_PLUGIN + getClass().getSimpleName().replace("Plugin", "").toLowerCase() + "_");
        protected EventBus eventBus;
        protected CogNoteContext context;

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            this.eventBus = bus;
            this.context = ctx;
        }

        @Override
        public void shutdown() {
        }

        protected void publish(CogNoteEvent event) {
            if (eventBus != null) eventBus.publish(event);
        }

        protected KnowledgeBase getKb(@Nullable String noteId) {
            return context.getKb(noteId);
        }
    }

    static class InputProcessingPlugin extends BasePlugin {
        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(ExternalInputEvent.class, this::handleExternalInput);
        }

        private void handleExternalInput(ExternalInputEvent event) {
            if (!(event.term() instanceof KifList list) || list.terms().isEmpty()) {
                if (event.term() != null && !(event.term() instanceof KifList))
                    System.err.println("Warning: Ignoring non-list top-level term from " + event.sourceId() + ": " + event.term().toKifString());
                return;
            }
            list.getOperator().ifPresentOrElse(op -> {
                switch (op) {
                    case KIF_OP_IMPLIES, KIF_OP_EQUIV -> handleRuleInput(list, event.sourceId());
                    case KIF_OP_EXISTS -> handleExistsInput(list, event.sourceId(), event.targetNoteId());
                    case KIF_OP_FORALL -> handleForallInput(list, event.sourceId(), event.targetNoteId());
                    default -> handleStandardAssertionInput(list, event.sourceId(), event.targetNoteId());
                }
            }, () -> handleStandardAssertionInput(list, event.sourceId(), event.targetNoteId()));
        }

        private void handleRuleInput(KifList list, String sourceId) {
            try {
                var rule = Rule.parseRule(generateId(ID_PREFIX_RULE), list, DEFAULT_RULE_PRIORITY);
                if (context.addRule(rule)) System.out.println("Rule added [" + rule.id() + "] from " + sourceId);
                if (KIF_OP_EQUIV.equals(list.getOperator().orElse(""))) {
                    var reverseList = new KifList(List.of(KifAtom.of(KIF_OP_IMPLIES), list.get(2), list.get(1)));
                    var reverseRule = Rule.parseRule(generateId(ID_PREFIX_RULE), reverseList, DEFAULT_RULE_PRIORITY);
                    if (context.addRule(reverseRule))
                        System.out.println("Equivalence rule added [" + reverseRule.id() + "] from " + sourceId);
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid rule format ignored (" + sourceId + "): " + list.toKifString() + " | Error: " + e.getMessage());
            }
        }

        private void handleStandardAssertionInput(KifList list, String sourceId, @Nullable String targetNoteId) {
            if (list.containsVariable()) {
                System.err.println("Warning: Non-ground assertion input ignored (" + sourceId + "): " + list.toKifString());
                return;
            }
            var isNegated = list.getOperator().filter(KIF_OP_NOT::equals).isPresent();
            if (isNegated && list.size() != 2) {
                System.err.println("Invalid 'not' format ignored (" + sourceId + "): " + list.toKifString());
                return;
            }
            var isEq = !isNegated && list.getOperator().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && list.size() == 3 && list.get(1).calculateWeight() > list.get(2).calculateWeight();
            var derivedType = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            var pri = (targetNoteId != null ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.calculateWeight());
            publish(new PotentialAssertionEvent(new PotentialAssertion(list, pri, Set.of(), sourceId, isEq, isNegated, isOriented, targetNoteId, derivedType, List.of(), 0), targetNoteId));
        }

        private void handleExistsInput(KifList existsExpr, String sourceId, @Nullable String targetNoteId) {
            if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) {
                System.err.println("Invalid 'exists' format ignored (" + sourceId + "): " + existsExpr.toKifString());
                return;
            }
            var variables = KifTerm.collectVariablesFromSpec(existsExpr.get(1));
            if (variables.isEmpty()) {
                publish(new ExternalInputEvent(existsExpr.get(2), sourceId + "-existsBody", targetNoteId));
                return;
            }
            var skolemizedBody = context.performSkolemization(body, variables, Map.of());
            System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);
            var isNegated = skolemizedBody.getOperator().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNegated && skolemizedBody.getOperator().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemizedBody.size() == 3 && skolemizedBody.get(1).calculateWeight() > skolemizedBody.get(2).calculateWeight();
            var pri = (targetNoteId != null ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + skolemizedBody.calculateWeight());
            publish(new PotentialAssertionEvent(new PotentialAssertion(skolemizedBody, pri, Set.of(), sourceId + "-skolemized", isEq, isNegated, isOriented, targetNoteId, AssertionType.SKOLEMIZED, List.of(), 0), targetNoteId));
        }

        private void handleForallInput(KifList forallExpr, String sourceId, @Nullable String targetNoteId) {
            if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) {
                System.err.println("Invalid 'forall' format ignored (" + sourceId + "): " + forallExpr.toKifString());
                return;
            }
            var variables = KifTerm.collectVariablesFromSpec(forallExpr.get(1));
            if (variables.isEmpty()) {
                publish(new ExternalInputEvent(forallExpr.get(2), sourceId + "-forallBody", targetNoteId));
                return;
            }
            if (body.getOperator().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent())
                handleRuleInput(body, sourceId);
            else {
                System.out.println("Storing 'forall' as universal fact from " + sourceId + ": " + forallExpr.toKifString());
                var pri = (targetNoteId != null ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + forallExpr.calculateWeight());
                publish(new PotentialAssertionEvent(new PotentialAssertion(forallExpr, pri, Set.of(), sourceId, false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(variables), 0), targetNoteId));
            }
        }
    }

    static class CommitPlugin extends BasePlugin {
        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(PotentialAssertionEvent.class, this::handlePotentialAssertion);
        }

        private void handlePotentialAssertion(PotentialAssertionEvent event) {
            getKb(event.targetNoteId()).commitAssertion(event.potentialAssertion());
        }
    }

    static class RetractionPlugin extends BasePlugin {
        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(RetractionRequestEvent.class, this::handleRetractionRequest);
        }

        private void handleRetractionRequest(RetractionRequestEvent event) {
            switch (event.type()) {
                case BY_ID -> {
                    var removed = getKb(event.targetNoteId()).retractAssertion(event.target());
                    var kbName = event.targetNoteId() != null ? event.targetNoteId() : "global";
                    if (removed != null)
                        System.out.printf("Retraction initiated for [%s] by %s in KB '%s': %s%n", event.target(), event.sourceId(), kbName, removed.toKifString());
                    else
                        System.out.printf("Retraction by ID %s from %s in KB '%s' failed: ID not found or already retracted.%n", event.target(), event.sourceId(), kbName);
                }
                case BY_NOTE -> {
                    var noteId = event.target();
                    var kb = context.getAllNoteKbs().get(noteId);
                    if (kb != null) {
                        var idsToRetract = kb.getAllAssertionIds();
                        if (!idsToRetract.isEmpty()) {
                            System.out.printf("Initiating retraction of %d assertions for note %s from %s.%n", idsToRetract.size(), noteId, event.sourceId());
                            new HashSet<>(idsToRetract).forEach(kb::retractAssertion);
                        } else
                            System.out.printf("Retraction by Note ID %s from %s failed: No associated assertions found in its KB.%n", noteId, event.sourceId());
                        context.removeNoteKb(noteId);
                        publish(new NoteRemovedEvent(new SwingUI.Note(noteId, "Removed", "")));
                    } else
                        System.out.printf("Retraction by Note ID %s from %s failed: Note KB not found.%n", noteId, event.sourceId());
                }
                case BY_RULE_FORM -> {
                    try {
                        var terms = KifParser.parseKif(event.target());
                        if (terms.size() == 1 && terms.getFirst() instanceof KifList ruleForm) {
                            var removed = context.removeRule(ruleForm);
                            System.out.println("Retract rule from " + event.sourceId() + ": " + (removed ? "Success" : "No match found") + " for: " + ruleForm.toKifString());
                        } else
                            System.err.println("Retract rule from " + event.sourceId() + ": Input is not a single valid rule KIF list: " + event.target());
                    } catch (ParseException e) {
                        System.err.println("Retract rule from " + event.sourceId() + ": Parse error: " + e.getMessage());
                    }
                }
            }
        }
    }

    static class ForwardChainingPlugin extends BasePlugin {
        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newAssertion = event.assertion();
            var sourceKbId = event.noteId();
            if (newAssertion.assertionType() != AssertionType.GROUND && newAssertion.assertionType() != AssertionType.SKOLEMIZED)
                return;
            context.getRules().forEach(rule -> rule.antecedents().forEach(clause -> {
                var clauseIsNegated = (clause instanceof KifList l && l.getOperator().filter(KIF_OP_NOT::equals).isPresent());
                if (clauseIsNegated == newAssertion.isNegated()) {
                    var pattern = clauseIsNegated ? ((KifList) clause).get(1) : clause;
                    ofNullable(Unifier.unify(pattern, newAssertion.getEffectiveTerm(), Map.of())).ifPresent(initialBindings -> findMatchesRecursive(rule, rule.antecedents(), initialBindings, Set.of(newAssertion.id()), sourceKbId).forEach(matchResult -> processDerivedAssertion(rule, matchResult)));
                }
            }));
        }

        private Stream<MatchResult> findMatchesRecursive(Rule rule, List<KifTerm> remainingClauses, Map<KifVar, KifTerm> currentBindings, Set<String> currentSupportIds, String currentKbId) {
            if (remainingClauses.isEmpty()) return Stream.of(new MatchResult(currentBindings, currentSupportIds));
            var clauseToMatch = Unifier.substFully(remainingClauses.getFirst(), currentBindings);
            var nextRemainingClauses = remainingClauses.subList(1, remainingClauses.size());
            var clauseIsNegated = (clauseToMatch instanceof KifList l && l.getOperator().filter(KIF_OP_NOT::equals).isPresent());
            var pattern = clauseIsNegated ? ((KifList) clauseToMatch).get(1) : clauseToMatch;
            if (!(pattern instanceof KifList)) return Stream.empty();
            var currentKb = getKb(currentKbId);
            var globalKb = context.getGlobalKb();
            return Stream.concat(currentKb.findUnifiableAssertions(pattern), (currentKbId != null && !currentKbId.equals("global")) ? globalKb.findUnifiableAssertions(pattern) : Stream.empty())
                    .distinct().filter(candidate -> candidate.isNegated() == clauseIsNegated)
                    .flatMap(candidate -> ofNullable(Unifier.unify(pattern, candidate.getEffectiveTerm(), currentBindings)).map(newBindings -> {
                        var nextSupport = Stream.concat(currentSupportIds.stream(), Stream.of(candidate.id())).collect(Collectors.toSet());
                        var candidateKbId = context.getAllNoteKbs().entrySet().stream().filter(entry -> entry.getValue().getAssertion(candidate.id()).isPresent()).map(Map.Entry::getKey).findFirst().orElse("global");
                        return findMatchesRecursive(rule, nextRemainingClauses, newBindings, nextSupport, candidateKbId);
                    }).orElse(Stream.empty()));
        }

        private void processDerivedAssertion(Rule sourceRule, MatchResult matchResult) {
            var consequentTerm = Unifier.subst(sourceRule.consequent(), matchResult.bindings);
            if (consequentTerm == null) return;
            var simplifiedTerm = (consequentTerm instanceof KifList kl) ? context.simplifyLogicalTerm(kl) : consequentTerm;
            if (!(simplifiedTerm instanceof KifList derivedList)) {
                if (!(simplifiedTerm instanceof KifVar))
                    System.err.println("Warning: Rule " + sourceRule.id() + " derived non-list/non-var consequent: " + simplifiedTerm.toKifString());
                return;
            }
            var targetNoteId = context.findCommonSourceNodeId(matchResult.supportIds());
            derivedList.getOperator().ifPresentOrElse(op -> {
                switch (op) {
                    case KIF_OP_AND -> processDerivedConjunction(sourceRule, derivedList, matchResult, targetNoteId);
                    case KIF_OP_FORALL -> processDerivedForall(sourceRule, derivedList, matchResult, targetNoteId);
                    case KIF_OP_EXISTS -> processDerivedExists(sourceRule, derivedList, matchResult, targetNoteId);
                    default -> processDerivedStandard(sourceRule, derivedList, matchResult, targetNoteId);
                }
            }, () -> processDerivedStandard(sourceRule, derivedList, matchResult, targetNoteId));
        }

        private void processDerivedConjunction(Rule sourceRule, KifList conjunction, MatchResult matchResult, @Nullable String targetNoteId) {
            conjunction.terms().stream().skip(1).forEach(term -> {
                var simplifiedSubTerm = (term instanceof KifList kl) ? context.simplifyLogicalTerm(kl) : term;
                if (simplifiedSubTerm instanceof KifList conjunct) {
                    var dummyRule = new Rule(sourceRule.id(), sourceRule.form(), sourceRule.antecedent(), conjunct, sourceRule.pri(), sourceRule.antecedents());
                    processDerivedAssertion(dummyRule, matchResult);
                } else if (!(simplifiedSubTerm instanceof KifVar))
                    System.err.println("Warning: Rule " + sourceRule.id() + " derived (and ...) with non-list/non-var conjunct: " + term.toKifString());
            });
        }

        private void processDerivedForall(Rule sourceRule, KifList forallExpr, MatchResult matchResult, @Nullable String targetNoteId) {
            if (forallExpr.size() != 3 || !(forallExpr.get(1) instanceof KifList || forallExpr.get(1) instanceof KifVar) || !(forallExpr.get(2) instanceof KifList body)) {
                System.err.println("Rule " + sourceRule.id() + " derived invalid 'forall' structure: " + forallExpr.toKifString());
                return;
            }
            var variables = KifTerm.collectVariablesFromSpec(forallExpr.get(1));
            if (variables.isEmpty()) {
                processDerivedStandard(sourceRule, body, matchResult, targetNoteId);
                return;
            }
            var newDepth = context.calculateDerivedDepth(matchResult.supportIds()) + 1;
            if (newDepth > MAX_DERIVATION_DEPTH) return;
            if (body.getOperator().filter(op -> op.equals(KIF_OP_IMPLIES) || op.equals(KIF_OP_EQUIV)).isPresent()) {
                try {
                    var derivedRulePri = context.calculateDerivedPri(matchResult.supportIds(), sourceRule.pri());
                    var derivedRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "derived_"), body, derivedRulePri);
                    if (context.addRule(derivedRule)) System.out.println("Derived rule added: " + derivedRule.id());
                    if (KIF_OP_EQUIV.equals(body.getOperator().orElse(""))) {
                        var reverseList = new KifList(List.of(KifAtom.of(KIF_OP_IMPLIES), body.get(2), body.get(1)));
                        var reverseRule = Rule.parseRule(generateId(ID_PREFIX_RULE + "derived_"), reverseList, derivedRulePri);
                        if (context.addRule(reverseRule))
                            System.out.println("Derived equivalence rule added: " + reverseRule.id());
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Invalid derived rule format ignored: " + body.toKifString() + " from rule " + sourceRule.id() + " | Error: " + e.getMessage());
                }
            } else {
                var pa = new PotentialAssertion(forallExpr, context.calculateDerivedPri(matchResult.supportIds(), sourceRule.pri()), matchResult.supportIds(), sourceRule.id(), false, false, false, targetNoteId, AssertionType.UNIVERSAL, List.copyOf(variables), newDepth);
                publish(new PotentialAssertionEvent(pa, targetNoteId));
            }
        }

        private void processDerivedExists(Rule sourceRule, KifList existsExpr, MatchResult matchResult, @Nullable String targetNoteId) {
            if (existsExpr.size() != 3 || !(existsExpr.get(1) instanceof KifList || existsExpr.get(1) instanceof KifVar) || !(existsExpr.get(2) instanceof KifList body)) {
                System.err.println("Rule " + sourceRule.id() + " derived invalid 'exists' structure: " + existsExpr.toKifString());
                return;
            }
            var variables = KifTerm.collectVariablesFromSpec(existsExpr.get(1));
            if (variables.isEmpty()) {
                processDerivedStandard(sourceRule, body, matchResult, targetNoteId);
                return;
            }
            var newDepth = context.calculateDerivedDepth(matchResult.supportIds()) + 1;
            if (newDepth > MAX_DERIVATION_DEPTH) return;
            var skolemizedBody = context.performSkolemization(body, variables, matchResult.bindings());
            var isNegated = skolemizedBody.getOperator().filter(KIF_OP_NOT::equals).isPresent();
            var isEq = !isNegated && skolemizedBody.getOperator().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && skolemizedBody.size() == 3 && skolemizedBody.get(1).calculateWeight() > skolemizedBody.get(2).calculateWeight();
            publish(new PotentialAssertionEvent(new PotentialAssertion(skolemizedBody, context.calculateDerivedPri(matchResult.supportIds(), sourceRule.pri()), matchResult.supportIds(), sourceRule.id(), isEq, isNegated, isOriented, targetNoteId, AssertionType.SKOLEMIZED, List.of(), newDepth), targetNoteId));
        }

        private void processDerivedStandard(Rule sourceRule, KifList derivedTerm, MatchResult matchResult, @Nullable String targetNoteId) {
            if (derivedTerm.containsVariable() || CogNote.isTrivial(derivedTerm)) return;
            var newDepth = context.calculateDerivedDepth(matchResult.supportIds()) + 1;
            if (newDepth > MAX_DERIVATION_DEPTH || derivedTerm.calculateWeight() > MAX_DERIVED_TERM_WEIGHT) return;
            var isNegated = derivedTerm.getOperator().filter(KIF_OP_NOT::equals).isPresent();
            if (isNegated && derivedTerm.size() != 2) {
                System.err.println("Rule " + sourceRule.id() + " derived invalid 'not': " + derivedTerm.toKifString());
                return;
            }
            var isEq = !isNegated && derivedTerm.getOperator().filter(KIF_OP_EQUAL::equals).isPresent();
            var isOriented = isEq && derivedTerm.size() == 3 && derivedTerm.get(1).calculateWeight() > derivedTerm.get(2).calculateWeight();
            var derivedType = derivedTerm.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
            publish(new PotentialAssertionEvent(new PotentialAssertion(derivedTerm, context.calculateDerivedPri(matchResult.supportIds(), sourceRule.pri()), matchResult.supportIds(), sourceRule.id(), isEq, isNegated, isOriented, targetNoteId, derivedType, List.of(), newDepth), targetNoteId));
        }

        record MatchResult(Map<KifVar, KifTerm> bindings, Set<String> supportIds) {
        }
    }

    static class RewriteRulePlugin extends BasePlugin {
        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newAssertion = event.assertion();
            var sourceKbId = event.noteId();
            if (newAssertion.assertionType() != AssertionType.GROUND && newAssertion.assertionType() != AssertionType.SKOLEMIZED)
                return;
            if (newAssertion.isEquality() && newAssertion.isOrientedEquality() && !newAssertion.isNegated() && newAssertion.kif().size() == 3) {
                var lhs = newAssertion.kif().get(1);
                Stream.concat(getKb(sourceKbId).findUnifiableAssertions(lhs), (sourceKbId != null && !sourceKbId.equals("global")) ? context.getGlobalKb().findUnifiableAssertions(lhs) : Stream.empty()).distinct().filter(target -> !target.id().equals(newAssertion.id())).filter(target -> Unifier.match(lhs, target.getEffectiveTerm(), Map.of()) != null).forEach(target -> applyRewrite(newAssertion, target));
            }
            Stream.concat(getKb(sourceKbId).getAllAssertions().stream(), (sourceKbId != null && !sourceKbId.equals("global")) ? context.getGlobalKb().getAllAssertions().stream() : Stream.empty()).distinct().filter(ruleAssertion -> ruleAssertion.isEquality() && ruleAssertion.isOrientedEquality() && !ruleAssertion.isNegated() && ruleAssertion.kif().size() == 3).filter(ruleAssertion -> !ruleAssertion.id().equals(newAssertion.id())).filter(ruleAssertion -> Unifier.match(ruleAssertion.kif().get(1), newAssertion.getEffectiveTerm(), Map.of()) != null).forEach(ruleAssertion -> applyRewrite(ruleAssertion, newAssertion));
        }

        private void applyRewrite(Assertion ruleAssertion, Assertion targetAssertion) {
            var lhs = ruleAssertion.kif().get(1);
            var rhs = ruleAssertion.kif().get(2);
            Unifier.rewrite(targetAssertion.kif(), lhs, rhs).filter(rewrittenTerm -> rewrittenTerm instanceof KifList && !rewrittenTerm.equals(targetAssertion.kif())).map(KifList.class::cast).filter(Predicate.not(CogNote::isTrivial))
                    .ifPresent(rewrittenList -> {
                        var support = Stream.concat(targetAssertion.support().stream(), Stream.of(targetAssertion.id(), ruleAssertion.id())).collect(Collectors.toSet());
                        var newDepth = Math.max(targetAssertion.derivationDepth(), ruleAssertion.derivationDepth()) + 1;
                        if (newDepth > MAX_DERIVATION_DEPTH || rewrittenList.calculateWeight() > MAX_DERIVED_TERM_WEIGHT)
                            return;
                        var isNeg = rewrittenList.getOperator().filter(KIF_OP_NOT::equals).isPresent();
                        var isEq = !isNeg && rewrittenList.getOperator().filter(KIF_OP_EQUAL::equals).isPresent();
                        var isOriented = isEq && rewrittenList.size() == 3 && rewrittenList.get(1).calculateWeight() > rewrittenList.get(2).calculateWeight();
                        var derivedType = rewrittenList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                        var targetNoteId = context.findCommonSourceNodeId(support);
                        publish(new PotentialAssertionEvent(new PotentialAssertion(rewrittenList, context.calculateDerivedPri(support, (ruleAssertion.pri() + targetAssertion.pri()) / 2.0), support, ruleAssertion.id(), isEq, isNeg, isOriented, targetNoteId, derivedType, List.of(), newDepth), targetNoteId));
                    });
        }
    }

    static class UniversalInstantiationPlugin extends BasePlugin {
        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(AssertionAddedEvent.class, this::handleAssertionAdded);
        }

        private void handleAssertionAdded(AssertionAddedEvent event) {
            var newAssertion = event.assertion();
            var sourceKbId = event.noteId();
            var sourceKb = getKb(sourceKbId);
            var globalKb = context.getGlobalKb();
            if ((newAssertion.assertionType() == AssertionType.GROUND || newAssertion.assertionType() == AssertionType.SKOLEMIZED) && newAssertion.kif().get(0) instanceof KifAtom predicate) {
                Stream.concat(sourceKb.findRelevantUniversalAssertions(predicate).stream(), (sourceKbId != null && !sourceKbId.equals("global")) ?
                                globalKb.findRelevantUniversalAssertions(predicate).stream() : Stream.empty()).distinct()
                        .filter(uniAssertion -> uniAssertion.derivationDepth() < MAX_DERIVATION_DEPTH)
                        .forEach(uniAssertion -> tryInstantiate(uniAssertion, newAssertion));
            } else if (newAssertion.assertionType() == AssertionType.UNIVERSAL && newAssertion.derivationDepth() < MAX_DERIVATION_DEPTH) {
                var predicate = ofNullable(newAssertion.getEffectiveTerm()).filter(KifList.class::isInstance).map(KifList.class::cast).flatMap(KifList::getOperator).map(KifAtom::of).orElse(null);
                if (predicate != null) {
                    Stream.concat(sourceKb.getAllAssertions().stream(), (sourceKbId != null && !sourceKbId.equals("global")) ? globalKb.getAllAssertions().stream() : Stream.empty()).distinct().filter(ground -> ground.assertionType() == AssertionType.GROUND || ground.assertionType() == AssertionType.SKOLEMIZED).filter(ground -> ground.getReferencedPredicates().contains(predicate)).forEach(groundAssertion -> tryInstantiate(newAssertion, groundAssertion));
                }
            }
        }

        private void tryInstantiate(Assertion uniAssertion, Assertion groundAssertion) {
            var formula = uniAssertion.getEffectiveTerm();
            var quantifiedVars = uniAssertion.quantifiedVars();
            if (quantifiedVars.isEmpty()) return;
            findSubExpressionMatches(formula, groundAssertion.kif()).forEach(bindings -> {
                if (bindings.keySet().containsAll(quantifiedVars)) {
                    var instantiatedFormula = Unifier.subst(formula, bindings);
                    if (instantiatedFormula instanceof KifList instList && !instantiatedFormula.containsVariable() && !CogNote.isTrivial(instList)) {
                        var newSupport = Stream.concat(groundAssertion.support().stream(), uniAssertion.support().stream()).collect(Collectors.toSet());
                        newSupport.add(groundAssertion.id());
                        newSupport.add(uniAssertion.id());
                        var newPri = context.calculateDerivedPri(newSupport, (groundAssertion.pri() + uniAssertion.pri()) / 2.0);
                        var newDepth = Math.max(groundAssertion.derivationDepth(), uniAssertion.derivationDepth()) + 1;
                        if (newDepth <= MAX_DERIVATION_DEPTH) {
                            var isNeg = instList.getOperator().filter(KIF_OP_NOT::equals).isPresent();
                            var isEq = !isNeg && instList.getOperator().filter(KIF_OP_EQUAL::equals).isPresent();
                            var isOriented = isEq && instList.size() == 3 && instList.get(1).calculateWeight() > instList.get(2).calculateWeight();
                            var derivedType = instList.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                            var targetNoteId = context.findCommonSourceNodeId(newSupport);
                            publish(new PotentialAssertionEvent(new PotentialAssertion(instList, newPri, newSupport, uniAssertion.id(), isEq, isNeg, isOriented, targetNoteId, derivedType, List.of(), newDepth), targetNoteId));
                        }
                    }
                }
            });
        }

        private Stream<Map<KifVar, KifTerm>> findSubExpressionMatches(KifTerm expression, KifTerm target) {
            return Stream.concat(ofNullable(Unifier.match(expression, target, Map.of())).stream(), (expression instanceof KifList list) ? list.terms().stream().flatMap(sub -> findSubExpressionMatches(sub, target)) : Stream.empty());
        }
    }

    static class StatusUpdaterPlugin extends BasePlugin {
        private final Consumer<SystemStatusEvent> uiUpdater;

        StatusUpdaterPlugin(Consumer<SystemStatusEvent> uiUpdater) {
            this.uiUpdater = uiUpdater;
        }

        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(AssertionAddedEvent.class, e -> updateStatus());
            bus.subscribe(AssertionRetractedEvent.class, e -> updateStatus());
            bus.subscribe(AssertionEvictedEvent.class, e -> updateStatus());
            bus.subscribe(RuleAddedEvent.class, e -> updateStatus());
            bus.subscribe(RuleRemovedEvent.class, e -> updateStatus());
            bus.subscribe(NoteAddedEvent.class, e -> updateStatus());
            bus.subscribe(NoteRemovedEvent.class, e -> updateStatus());
            bus.subscribe(SystemStatusEvent.class, uiUpdater);
            updateStatus();
        }

        private void updateStatus() {
            var kbCount = context.getKbCount();
            var kbCapacity = context.getTotalKbCapacity();
            var ruleCount = context.getRuleCount();
            publish(new SystemStatusEvent("Status Update", kbCount, kbCapacity, 0, 0, ruleCount));
        }
    }

    static class WebSocketBroadcasterPlugin extends BasePlugin {
        private final CogNote server;

        WebSocketBroadcasterPlugin(CogNote server) {
            this.server = server;
        }

        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            bus.subscribe(AssertionAddedEvent.class, this::onAssertionAdded);
            bus.subscribe(AssertionRetractedEvent.class, this::onAssertionRetracted);
            bus.subscribe(AssertionEvictedEvent.class, this::onAssertionEvicted);
            bus.subscribe(LlmResponseEvent.class, this::onLlmResponse);
            bus.subscribe(WebSocketBroadcastEvent.class, this::onBroadcastRequest);
            if (server.broadcastInputAssertions) bus.subscribe(ExternalInputEvent.class, this::onExternalInput);
        }

        private void onAssertionAdded(AssertionAddedEvent event) {
            broadcastMessage("assert-added", event.assertion(), event.noteId());
        }

        private void onAssertionRetracted(AssertionRetractedEvent event) {
            broadcastMessage("retract", event.assertion(), event.noteId());
        }

        private void onAssertionEvicted(AssertionEvictedEvent event) {
            broadcastMessage("evict", event.assertion(), event.noteId());
        }

        private void onLlmResponse(LlmResponseEvent event) {
            broadcastMessage("llm-response", event.llmItem());
        }

        private void onExternalInput(ExternalInputEvent event) {
            if (event.term() instanceof KifList list) {
                var tempId = generateId(ID_PREFIX_INPUT_ITEM);
                var pri = (event.targetNoteId() != null ? LLM_ASSERTION_BASE_PRIORITY : INPUT_ASSERTION_BASE_PRIORITY) / (1.0 + list.calculateWeight());
                var type = list.containsSkolemTerm() ? AssertionType.SKOLEMIZED : AssertionType.GROUND;
                var tempAssertion = new Assertion(tempId, list, pri, System.currentTimeMillis(), event.targetNoteId(), Set.of(), type, false, false, false, List.of(), 0);
                broadcastMessage("assert-input", tempAssertion, event.targetNoteId());
            }
        }

        private void onBroadcastRequest(WebSocketBroadcastEvent event) {
            safeBroadcast(event.message());
        }

        private void broadcastMessage(String type, Assertion assertion, String kbId) {
            var kifString = assertion.toKifString();
            var message = switch (type) {
                case "assert-added" ->
                        String.format("assert-added %.4f %s [%s] {type:%s, depth:%d, kb:%s}", assertion.pri(), kifString, assertion.id(), assertion.assertionType(), assertion.derivationDepth(), kbId);
                case "assert-input" ->
                        String.format("assert-input %.4f %s [%s] {type:%s, depth:%d, kb:%s}", assertion.pri(), kifString, assertion.id(), assertion.assertionType(), assertion.derivationDepth(), kbId);
                case "retract" -> String.format("retract %s", assertion.id());
                case "evict" -> String.format("evict %s", assertion.id());
                default -> String.format("%s %.4f %s [%s]", type, assertion.pri(), kifString, assertion.id());
            };
            safeBroadcast(message);
        }

        private void broadcastMessage(String type, SwingUI.AssertionViewModel llmItem) {
            if (!"llm-response".equals(type) || llmItem.noteId() == null) return;
            var message = String.format("llm-response %s [%s] {type:%s, content:\"%s\"}", llmItem.noteId(), llmItem.id(), llmItem.displayType(), llmItem.content().replace("\"", "\\\""));
            safeBroadcast(message);
        }

        private void safeBroadcast(String message) {
            try {
                if (!server.websocket.getConnections().isEmpty()) server.websocket.broadcast(message);
            } catch (Exception e) {
                if (!(e instanceof ConcurrentModificationException || ofNullable(e.getMessage()).map(m -> m.contains("closed")).orElse(false)))
                    System.err.println("Error during WebSocket broadcast: " + e.getMessage());
            }
        }
    }

    static class UiUpdatePlugin extends BasePlugin {
        private final SwingUI swingUI;

        UiUpdatePlugin(SwingUI ui) {
            this.swingUI = ui;
        }

        @Override
        public void initialize(EventBus bus, CogNoteContext ctx) {
            super.initialize(bus, ctx);
            if (swingUI == null || !swingUI.isDisplayable()) return;
            bus.subscribe(AssertionAddedEvent.class, e -> handleUiUpdate("assert-added", e.assertion(), e.noteId()));
            bus.subscribe(AssertionRetractedEvent.class, e -> handleUiUpdate("retract", e.assertion(), e.noteId()));
            bus.subscribe(AssertionEvictedEvent.class, e -> handleUiUpdate("evict", e.assertion(), e.noteId()));
            bus.subscribe(LlmResponseEvent.class, e -> handleUiUpdate("llm-response", e.llmItem(), e.getAssociatedNoteId()));
            bus.subscribe(NoteAddedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.addNoteToList(e.note())));
            bus.subscribe(NoteRemovedEvent.class, e -> SwingUtilities.invokeLater(() -> swingUI.removeNoteFromList(e.note().id)));
        }

        private void handleUiUpdate(String type, Object payload, @Nullable String eventKbId) {
            if (swingUI == null || !swingUI.isDisplayable()) return;
            String displayNoteId = null;
            if (payload instanceof Assertion assertion) {
                displayNoteId = assertion.sourceNoteId();
                if (displayNoteId == null && assertion.derivationDepth() > 0)
                    displayNoteId = context.findCommonSourceNodeId(assertion.support());
                if (displayNoteId == null && !"global".equals(eventKbId)) displayNoteId = eventKbId;
            } else if (payload instanceof SwingUI.AssertionViewModel vm) displayNoteId = vm.noteId();
            final var finalDisplayNoteId = displayNoteId;
            SwingUtilities.invokeLater(() -> swingUI.handleSystemUpdate(type, payload, finalDisplayNoteId));
        }
    }

    static class SwingUI extends JFrame {
        final JLabel statusLabel = new JLabel("Status: Initializing...");
        final Map<String, DefaultListModel<AssertionViewModel>> noteAssertionModels = new ConcurrentHashMap<>();
        final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        final JList<Note> noteList = new JList<>(noteListModel);
        final JTextArea noteEditor = new JTextArea();
        final JList<AssertionViewModel> noteAssertionDisplayList = new JList<>();
        private final JButton addButton = new JButton("Add Note"), pauseResumeButton = new JButton("Pause"), clearAllButton = new JButton("Clear All");
        private final JPopupMenu noteContextMenu = new JPopupMenu();
        private final JMenuItem analyzeItem = new JMenuItem("Analyze Note (LLM -> KIF)"), enhanceItem = new JMenuItem("Enhance Note (LLM Replace)"), summarizeItem = new JMenuItem("Summarize Note (LLM)"), keyConceptsItem = new JMenuItem("Identify Key Concepts (LLM)"), generateQuestionsItem = new JMenuItem("Generate Questions (LLM)"), renameItem = new JMenuItem("Rename Note"), removeItem = new JMenuItem("Remove Note");
        private final JPopupMenu assertionContextMenu = new JPopupMenu();
        private final JMenuItem retractAssertionItem = new JMenuItem("Retract Assertion"), showSupportItem = new JMenuItem("Show Support Chain"), viewRulesItem = new JMenuItem("View Rules");
        private CogNote systemRef;
        private Note currentNote = null;

        public SwingUI(@Nullable CogNote systemRef) {
            super("Cognote - Event Driven");
            this.systemRef = systemRef;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(1200, 800);
            setLocationRelativeTo(null);
            setupFonts();
            setupComponents();
            setupLayout();
            setupActionListeners();
            setupWindowListener();
            setupMenuBar();
            updateUIForSelection();
        }

        void setSystemReference(CogNote system) {
            this.systemRef = system;
            updateUIForSelection();
        }

        private void setupFonts() {
            Stream.of(noteList, noteEditor, addButton, pauseResumeButton, clearAllButton, statusLabel, analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, renameItem, removeItem, retractAssertionItem, showSupportItem, viewRulesItem).forEach(c -> c.setFont(UI_DEFAULT_FONT));
            noteAssertionDisplayList.setFont(MONOSPACED_FONT);
        }

        private void setupComponents() {
            noteEditor.setLineWrap(true);
            noteEditor.setWrapStyleWord(true);
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteList.setCellRenderer(new NoteListCellRenderer());
            noteAssertionDisplayList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteAssertionDisplayList.setCellRenderer(new AssertionListCellRenderer());
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
        }

        private void setupLayout() {
            var leftPane = new JScrollPane(noteList);
            leftPane.setPreferredSize(new Dimension(250, 0));
            var editorPane = new JScrollPane(noteEditor);
            var noteAssertionListPane = new JScrollPane(noteAssertionDisplayList);
            var noteDetailSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPane, noteAssertionListPane);
            noteDetailSplit.setResizeWeight(0.6);
            var mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, noteDetailSplit);
            mainSplitPane.setResizeWeight(0.2);
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
            Stream.of(addButton, pauseResumeButton, clearAllButton).forEach(buttonPanel::add);
            var bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(buttonPanel, BorderLayout.WEST);
            bottomPanel.add(statusLabel, BorderLayout.CENTER);
            bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            var p = getContentPane();
            p.setLayout(new BorderLayout());
            p.add(mainSplitPane, BorderLayout.CENTER);
            p.add(bottomPanel, BorderLayout.SOUTH);
        }

        private void setupMenuBar() {
            var mb = new JMenuBar();
            var fm = new JMenu("File");
            var vm = new JMenu("View");
            var hm = new JMenu("Help");
            viewRulesItem.addActionListener(this::viewRulesAction);
            vm.add(viewRulesItem);
            mb.add(fm);
            mb.add(vm);
            mb.add(hm);
            setJMenuBar(mb);
        }

        private void setupActionListeners() {
            addButton.addActionListener(this::addNoteAction);
            pauseResumeButton.addActionListener(this::togglePauseAction);
            clearAllButton.addActionListener(this::clearAllAction);
            analyzeItem.addActionListener(this::analyzeNoteAction);
            enhanceItem.addActionListener(this::enhanceNoteAction);
            summarizeItem.addActionListener(this::summarizeNoteAction);
            keyConceptsItem.addActionListener(this::keyConceptsAction);
            generateQuestionsItem.addActionListener(this::generateQuestionsAction);
            renameItem.addActionListener(this::renameNoteAction);
            removeItem.addActionListener(this::removeNoteAction);
            retractAssertionItem.addActionListener(this::retractSelectedAssertionAction);
            showSupportItem.addActionListener(this::showSupportAction);
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
            noteList.addMouseListener(createContextMenuMouseListener(noteList, noteContextMenu));
            noteAssertionDisplayList.addMouseListener(createContextMenuMouseListener(noteAssertionDisplayList, assertionContextMenu, this::updateAssertionContextMenuState));
            noteAssertionDisplayList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) showSupportAction(null);
                }
            });
        }

        private MouseAdapter createContextMenuMouseListener(JList<?> list, JPopupMenu popup, Runnable... preShowActions) {
            return new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowPopup(e);
                }

                private void maybeShowPopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    var index = list.locationToIndex(e.getPoint());
                    if (index != -1) {
                        if (list.getSelectedIndex() != index) list.setSelectedIndex(index);
                        for (var action : preShowActions) action.run();
                        popup.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            };
        }

        private void updateAssertionContextMenuState() {
            var selectedVM = noteAssertionDisplayList.getSelectedValue();
            var isAssertion = selectedVM != null && selectedVM.isActualAssertion();
            var isActive = isAssertion && selectedVM.status() == AssertionStatus.ACTIVE;
            retractAssertionItem.setEnabled(isActive);
            showSupportItem.setEnabled(isAssertion);
        }

        private void setupWindowListener() {
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    saveCurrentNoteText();
                    ofNullable(systemRef).ifPresent(CogNote::stopSystem);
                    dispose();
                    System.exit(0);
                }
            });
        }

        private void saveCurrentNoteText() {
            ofNullable(currentNote).filter(_ -> noteEditor.isEnabled()).ifPresent(n -> n.text = noteEditor.getText());
        }

        private void updateUIForSelection() {
            var noteSelected = (currentNote != null);
            noteEditor.setEnabled(noteSelected);
            noteEditor.getHighlighter().removeAllHighlights();
            ofNullable(systemRef).ifPresent(r -> pauseResumeButton.setText(r.isPaused() ? "Resume" : "Pause"));
            if (noteSelected) {
                noteEditor.setText(currentNote.text);
                noteEditor.setCaretPosition(0);
                noteAssertionDisplayList.setModel(noteAssertionModels.computeIfAbsent(currentNote.id, id -> new DefaultListModel<>()));
                setTitle("Cognote - " + currentNote.title);
                SwingUtilities.invokeLater(noteEditor::requestFocusInWindow);
            } else {
                noteEditor.setText("");
                noteAssertionDisplayList.setModel(new DefaultListModel<>());
                setTitle("Cognote - Event Driven");
            }
            setControlsEnabled(true);
        }

        private void addNoteAction(ActionEvent e) {
            ofNullable(JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE)).map(String::trim).filter(Predicate.not(String::isEmpty)).ifPresent(title -> {
                var newNote = new Note(generateId(ID_PREFIX_NOTE), title, "");
                addNoteToList(newNote);
                noteList.setSelectedValue(newNote, true);
                if (systemRef != null) systemRef.eventBus.publish(new NoteAddedEvent(newNote));
            });
        }

        private void removeNoteAction(ActionEvent e) {
            performNoteAction("Removing", "Confirm Removal", "Remove note '%s' and retract all associated assertions?", JOptionPane.WARNING_MESSAGE, noteToRemove -> {
                if (systemRef != null)
                    systemRef.eventBus.publish(new RetractionRequestEvent(noteToRemove.id, RetractionType.BY_NOTE, "UI-Remove", noteToRemove.id));
            });
        }

        private void renameNoteAction(ActionEvent e) {
            ofNullable(currentNote).flatMap(note -> ofNullable(JOptionPane.showInputDialog(this, "Enter new title for '" + note.title + "':", "Rename Note", JOptionPane.PLAIN_MESSAGE, null, null, note.title)).map(Object::toString).map(String::trim).filter(Predicate.not(String::isEmpty)).filter(newTitle -> !newTitle.equals(note.title)).map(newTitle -> {
                note.title = newTitle;
                return note;
            })).ifPresent(updatedNote -> {
                noteListModel.setElementAt(updatedNote, noteList.getSelectedIndex());
                setTitle("Cognote - " + updatedNote.title);
                statusLabel.setText("Status: Renamed note to '" + updatedNote.title + "'.");
            });
        }

        private void enhanceNoteAction(ActionEvent e) {
            performNoteActionAsync("Enhancing", note -> systemRef.enhanceNoteWithLlmAsync(note.text, note.id), this::processLlmEnhancementResponse, this::handleLlmFailure);
        }

        private void analyzeNoteAction(ActionEvent e) {
            performNoteActionAsync("Analyzing", note -> {
                addLlmUiItem(note.id, AssertionDisplayType.LLM_PLACEHOLDER, "Analyzing Note via LLM -> KIF...");
                if (systemRef != null)
                    systemRef.eventBus.publish(new RetractionRequestEvent(note.id, RetractionType.BY_NOTE, "UI-Analyze-Retract", note.id));
                clearNoteAssertionList(note.id);
                return systemRef.text2kifAsync(note.text, note.id);
            }, (kif, note) -> {
            }, this::handleLlmFailure);
        }

        private void summarizeNoteAction(ActionEvent e) {
            performNoteActionAsync("Summarizing", note -> systemRef.summarizeNoteWithLlmAsync(note.text, note.id), (resp, n) -> {
            }, this::handleLlmFailure);
        }

        private void keyConceptsAction(ActionEvent e) {
            performNoteActionAsync("Identifying Concepts", note -> systemRef.keyConceptsWithLlmAsync(note.text, note.id), (resp, n) -> {
            }, this::handleLlmFailure);
        }

        private void generateQuestionsAction(ActionEvent e) {
            performNoteActionAsync("Generating Questions", note -> systemRef.generateQuestionsWithLlmAsync(note.text, note.id), (resp, n) -> {
            }, this::handleLlmFailure);
        }

        private void retractSelectedAssertionAction(ActionEvent e) {
            ofNullable(noteAssertionDisplayList.getSelectedValue()).filter(AssertionViewModel::isActualAssertion).filter(vm -> vm.status() == AssertionStatus.ACTIVE).map(AssertionViewModel::id).ifPresent(id -> {
                System.out.println("UI Requesting retraction for: " + id);
                if (systemRef != null)
                    systemRef.eventBus.publish(new RetractionRequestEvent(id, RetractionType.BY_ID, "UI-Retract", currentNote != null ? currentNote.id : null));
            });
        }

        private void showSupportAction(ActionEvent e) {
            ofNullable(noteAssertionDisplayList.getSelectedValue()).filter(AssertionViewModel::isActualAssertion).map(AssertionViewModel::id).flatMap(id -> systemRef.context.findAssertionByIdAcrossKbs(id)).ifPresent(this::displaySupportChain);
        }

        private void viewRulesAction(ActionEvent e) {
            if (systemRef == null) return;
            var rulesText = systemRef.context.getRules().stream().sorted(Comparator.comparing(Rule::id)).map(r -> String.format("[%s] %.2f %s", r.id(), r.pri(), r.form().toKifString())).collect(Collectors.joining("\n"));
            var textArea = new JTextArea(rulesText.isEmpty() ? "<No rules defined>" : rulesText);
            textArea.setEditable(false);
            textArea.setFont(MONOSPACED_FONT);
            var scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(600, 400));
            JOptionPane.showMessageDialog(this, scrollPane, "Current Rules", JOptionPane.INFORMATION_MESSAGE);
        }

        private void togglePauseAction(ActionEvent e) {
            ofNullable(systemRef).ifPresent(r -> r.setPaused(!r.isPaused()));
        }

        private void clearAllAction(ActionEvent e) {
            if (systemRef != null && JOptionPane.showConfirmDialog(this, "Clear all notes, assertions, and rules? This cannot be undone.", "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
                systemRef.clearAllKnowledge();
        }

        private void performNoteAction(String actionName, String confirmTitle, String confirmMsgFormat, int confirmMsgType, Consumer<Note> action) {
            ofNullable(currentNote).filter(_ -> systemRef != null).ifPresent(note -> {
                if (JOptionPane.showConfirmDialog(this, String.format(confirmMsgFormat, note.title), confirmTitle, JOptionPane.YES_NO_OPTION, confirmMsgType) == JOptionPane.YES_OPTION) {
                    statusLabel.setText(String.format("Status: %s '%s'...", actionName, note.title));
                    setControlsEnabled(false);
                    try {
                        action.accept(note);
                        statusLabel.setText(String.format("Status: Finished %s '%s'.", actionName, note.title));
                    } catch (Exception ex) {
                        statusLabel.setText(String.format("Status: Error %s '%s'.", actionName, note.title));
                        handleActionError(actionName, ex);
                    } finally {
                        setControlsEnabled(true);
                    }
                }
            });
        }

        private <T> void performNoteActionAsync(String actionName, NoteAsyncAction<T> asyncAction, BiConsumer<T, Note> successCallback, BiConsumer<Throwable, Note> failureCallback) {
            ofNullable(currentNote).filter(_ -> systemRef != null).ifPresent(noteForAction -> {
                systemRef.systemStatus = actionName + " Note: " + noteForAction.title;
                systemRef.updateStatusLabel();
                setControlsEnabled(false);
                saveCurrentNoteText();
                addLlmUiItem(noteForAction.id, AssertionDisplayType.LLM_PLACEHOLDER, actionName + " Note via LLM...");
                try {
                    asyncAction.execute(noteForAction).thenAcceptAsync(result -> {
                        if (noteForAction.equals(currentNote)) successCallback.accept(result, noteForAction);
                    }, SwingUtilities::invokeLater).exceptionallyAsync(ex -> {
                        if (noteForAction.equals(currentNote)) failureCallback.accept(ex, noteForAction);
                        return null;
                    }, SwingUtilities::invokeLater).thenRunAsync(() -> {
                        if (noteForAction.equals(currentNote)) setControlsEnabled(true);
                    }, SwingUtilities::invokeLater);
                } catch (Exception e) {
                    failureCallback.accept(e, noteForAction);
                    setControlsEnabled(true);
                }
            });
        }

        private void handleActionError(String actionName, Throwable ex) {
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            System.err.println("Error during " + actionName + ": " + cause.getMessage());
            cause.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error during " + actionName + ":\n" + cause.getMessage(), "Action Error", JOptionPane.ERROR_MESSAGE);
        }

        private void processLlmEnhancementResponse(String enhancedText, Note enhancedNote) {
            removeLlmPlaceholders(enhancedNote.id);
            if (enhancedText != null && !enhancedText.isBlank()) {
                enhancedNote.text = enhancedText.trim();
                noteEditor.setText(enhancedNote.text);
                noteEditor.setCaretPosition(0);
            } else {
                addLlmUiItem(enhancedNote.id, AssertionDisplayType.LLM_ERROR, "Enhancement failed (Empty Response)");
                JOptionPane.showMessageDialog(this, "LLM returned empty enhancement.", "Enhancement Failed", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void handleLlmFailure(Throwable ex, Note contextNote) {
            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
            var action = (cause instanceof ParseException) ? "KIF Parse Error" : "LLM Interaction Failed";
            removeLlmPlaceholders(contextNote.id);
            addLlmUiItem(contextNote.id, AssertionDisplayType.LLM_ERROR, action + ": " + cause.getMessage());
            handleActionError(action, cause);
        }

        public void handleSystemUpdate(String type, Object payload, @Nullable String associatedNoteId) {
            switch (payload) {
                case Assertion assertion -> handleAssertionCallback(type, assertion, associatedNoteId);
                case AssertionViewModel llmItem when type.equals("llm-response") -> handleLlmCallback(llmItem);
                default -> {
                }
            }
        }

        private void handleAssertionCallback(String type, Assertion assertion, @Nullable String displayNoteId) {
            var vm = AssertionViewModel.fromAssertion(assertion, type, displayNoteId);
            var status = vm.status();
            if (displayNoteId != null)
                ofNullable(noteAssertionModels.get(displayNoteId)).ifPresent(model -> updateOrAddModelItem(model, vm));
            if (status == AssertionStatus.RETRACTED || status == AssertionStatus.EVICTED) {
                updateAssertionStatusInOtherNoteModels(assertion.id(), status, displayNoteId);
                highlightAffectedNoteText(assertion, status);
            } else if (status == AssertionStatus.ACTIVE) highlightAffectedNoteText(assertion, status);
        }

        private void handleLlmCallback(AssertionViewModel llmItem) {
            ofNullable(llmItem.noteId()).map(noteAssertionModels::get).ifPresent(model -> updateOrAddModelItem(model, llmItem));
        }

        private void updateOrAddModelItem(DefaultListModel<AssertionViewModel> model, AssertionViewModel newItem) {
            var existingIndex = findViewModelIndexById(model, newItem.id());
            if (existingIndex != -1) {
                var existingItem = model.getElementAt(existingIndex);
                if (newItem.status() != existingItem.status() || !newItem.content().equals(existingItem.content()) || !Objects.equals(newItem.associatedNoteId(), existingItem.associatedNoteId()) || newItem.priority() != existingItem.priority())
                    model.setElementAt(newItem, existingIndex);
            } else if (newItem.status() == AssertionStatus.ACTIVE || !newItem.isActualAssertion()) {
                var insertIndex = Collections.binarySearch((List<AssertionViewModel>) (List<?>) Arrays.asList(model.toArray()), newItem);
                if (insertIndex < 0) insertIndex = -(insertIndex + 1);
                model.add(insertIndex, newItem);
                if (model == noteAssertionDisplayList.getModel())
                    noteAssertionDisplayList.ensureIndexIsVisible(insertIndex);
            }
        }

        private void updateAssertionStatusInOtherNoteModels(String assertionId, AssertionStatus newStatus, @Nullable String primaryNoteId) {
            noteAssertionModels.forEach((noteId, model) -> {
                if (!noteId.equals(primaryNoteId)) {
                    var index = findViewModelIndexById(model, assertionId);
                    if (index != -1 && model.getElementAt(index).status() != newStatus)
                        model.setElementAt(model.getElementAt(index).withStatus(newStatus), index);
                }
            });
        }

        private void addLlmUiItem(String noteId, AssertionDisplayType type, String content) {
            var vm = new AssertionViewModel(generateId(ID_PREFIX_LLM_ITEM + type.name().toLowerCase()), noteId, content, type, AssertionStatus.ACTIVE, 0.0, -1, System.currentTimeMillis(), noteId);
            if (systemRef != null) systemRef.eventBus.publish(new LlmResponseEvent(vm));
        }

        private void removeLlmPlaceholders(String noteId) {
            ofNullable(noteAssertionModels.get(noteId)).ifPresent(model -> {
                for (var i = model.getSize() - 1; i >= 0; i--)
                    if (model.getElementAt(i).displayType() == AssertionDisplayType.LLM_PLACEHOLDER)
                        model.removeElementAt(i);
            });
        }

        private int findViewModelIndexById(DefaultListModel<AssertionViewModel> model, String id) {
            for (var i = 0; i < model.getSize(); i++) if (model.getElementAt(i).id().equals(id)) return i;
            return -1;
        }

        public void clearAllUILists() {
            noteListModel.clear();
            noteAssertionModels.values().forEach(DefaultListModel::clear);
            noteEditor.setText("");
            currentNote = null;
            setTitle("Cognote - Event Driven");
        }

        private void clearNoteAssertionList(String noteId) {
            ofNullable(noteAssertionModels.get(noteId)).ifPresent(DefaultListModel::clear);
        }

        public void addNoteToList(Note note) {
            if (!noteListModel.contains(note)) {
                noteListModel.addElement(note);
                noteAssertionModels.put(note.id, new DefaultListModel<>());
            }
        }

        public void removeNoteFromList(String noteId) {
            noteAssertionModels.remove(noteId);
            for (var i = 0; i < noteListModel.size(); i++) {
                if (noteListModel.getElementAt(i).id.equals(noteId)) {
                    var idx = noteList.getSelectedIndex();
                    noteListModel.removeElementAt(i);
                    if (currentNote != null && currentNote.id.equals(noteId)) currentNote = null;
                    if (!noteListModel.isEmpty())
                        noteList.setSelectedIndex(Math.max(0, Math.min(idx, noteListModel.getSize() - 1)));
                    else updateUIForSelection();
                    break;
                }
            }
        }

        private void displaySupportChain(Assertion startingAssertion) {
            if (systemRef == null) return;
            var supportDialog = new JDialog(this, "Support Chain for: " + startingAssertion.id(), false);
            supportDialog.setSize(600, 400);
            supportDialog.setLocationRelativeTo(this);
            var model = new DefaultListModel<AssertionViewModel>();
            var list = new JList<AssertionViewModel>(model);
            list.setCellRenderer(new AssertionListCellRenderer());
            list.setFont(MONOSPACED_FONT);
            Set<String> visited = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            queue.offer(startingAssertion.id());
            while (!queue.isEmpty()) {
                var currentId = queue.poll();
                if (currentId == null || !visited.add(currentId)) continue;
                systemRef.context.findAssertionByIdAcrossKbs(currentId).ifPresent(assertion -> {
                    var displayNoteId = assertion.sourceNoteId() != null ? assertion.sourceNoteId() : systemRef.context.findCommonSourceNodeId(assertion.support());
                    model.addElement(AssertionViewModel.fromAssertion(assertion, "support", displayNoteId));
                    assertion.support().forEach(queue::offer);
                });
            }
            List<AssertionViewModel> sortedList = Collections.list(model.elements());
            sortedList.sort(Comparator.naturalOrder());
            model.clear();
            sortedList.forEach(model::addElement);
            supportDialog.add(new JScrollPane(list));
            supportDialog.setVisible(true);
        }

        private void highlightAffectedNoteText(Assertion assertion, AssertionStatus status) {
            if (systemRef == null || currentNote == null) return;
            var displayNoteId = assertion.sourceNoteId() != null ? assertion.sourceNoteId() : systemRef.context.findCommonSourceNodeId(assertion.support());
            if (currentNote.id.equals(displayNoteId)) {
                var searchTerm = extractHighlightTerm(assertion.kif());
                if (searchTerm == null || searchTerm.isBlank()) return;
                var highlighter = noteEditor.getHighlighter();
                Highlighter.HighlightPainter painter = switch (status) {
                    case ACTIVE -> new DefaultHighlighter.DefaultHighlightPainter(new Color(200, 255, 200));
                    case RETRACTED -> new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 200, 200));
                    case EVICTED -> new DefaultHighlighter.DefaultHighlightPainter(Color.LIGHT_GRAY);
                };
                try {
                    var text = noteEditor.getText();
                    var pos = text.toLowerCase().indexOf(searchTerm.toLowerCase());
                    while (pos >= 0) {
                        highlighter.addHighlight(pos, pos + searchTerm.length(), painter);
                        pos = text.toLowerCase().indexOf(searchTerm.toLowerCase(), pos + 1);
                    }
                } catch (BadLocationException e) {
                }
            }
        }

        private String extractHighlightTerm(KifList kif) {
            return kif.getOperator().filter(op -> !op.matches("and|or|not|=>|<=>|=|forall|exists")).or(() -> (kif.size() > 1 && kif.get(1) instanceof KifAtom(
                    var v
            )) ? Optional.of(v) : Optional.empty()).or(() -> (kif.size() > 2 && kif.get(2) instanceof KifAtom(
                    var v
            )) ? Optional.of(v) : Optional.empty()).filter(s -> s.length() > 2).orElse(null);
        }

        private void setControlsEnabled(boolean enabled) {
            var noteSelected = (currentNote != null);
            var systemReady = (systemRef != null && systemRef.running.get() && !systemRef.paused.get());
            Stream.of(addButton, clearAllButton).forEach(c -> c.setEnabled(enabled && systemRef != null));
            pauseResumeButton.setEnabled(enabled && systemRef != null && systemRef.running.get());
            Stream.of(analyzeItem, enhanceItem, summarizeItem, keyConceptsItem, generateQuestionsItem, removeItem).forEach(c -> c.setEnabled(enabled && noteSelected && systemReady));
            renameItem.setEnabled(enabled && noteSelected);
            viewRulesItem.setEnabled(enabled && systemRef != null);
            if (systemRef != null && systemRef.running.get())
                pauseResumeButton.setText(systemRef.isPaused() ? "Resume" : "Pause");
        }

        enum AssertionStatus {ACTIVE, RETRACTED, EVICTED}

        enum AssertionDisplayType {INPUT, ADDED, DERIVED, UNIVERSAL, SKOLEMIZED, LLM_INFO, LLM_QUEUED_KIF, LLM_SKIPPED, LLM_SUMMARY, LLM_CONCEPTS, LLM_QUESTION, LLM_ERROR, LLM_PLACEHOLDER}

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
                this.id = requireNonNull(id);
                this.title = requireNonNull(title);
                this.text = requireNonNull(text);
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

        record AssertionViewModel(String id, @Nullable String noteId, String content, AssertionDisplayType displayType,
                                  AssertionStatus status, double priority, int depth, long timestamp,
                                  @Nullable String associatedNoteId) implements Comparable<AssertionViewModel> {
            static AssertionViewModel fromAssertion(Assertion assertion, String callbackType, @Nullable String associatedNoteId) {
                var type = switch (assertion.assertionType()) {
                    case GROUND ->
                            (assertion.derivationDepth() == 0) ? (callbackType.equals("assert-input") ? AssertionDisplayType.INPUT : AssertionDisplayType.ADDED) : AssertionDisplayType.DERIVED;
                    case SKOLEMIZED -> AssertionDisplayType.SKOLEMIZED;
                    case UNIVERSAL -> AssertionDisplayType.UNIVERSAL;
                };
                var stat = switch (callbackType) {
                    case "retract" -> AssertionStatus.RETRACTED;
                    case "evict" -> AssertionStatus.EVICTED;
                    default -> AssertionStatus.ACTIVE;
                };
                return new AssertionViewModel(assertion.id(), assertion.sourceNoteId(), assertion.toKifString(), type, stat, assertion.pri(), assertion.derivationDepth(), assertion.timestamp(), associatedNoteId);
            }

            public AssertionViewModel withStatus(AssertionStatus newStatus) {
                return new AssertionViewModel(id, noteId, content, displayType, newStatus, priority, depth, timestamp, associatedNoteId);
            }

            public boolean isActualAssertion() {
                return switch (displayType) {
                    case INPUT, ADDED, DERIVED, UNIVERSAL, SKOLEMIZED -> true;
                    default -> false;
                };
            }

            @Override
            public int compareTo(AssertionViewModel other) {
                var statusComp = Integer.compare(status.ordinal(), other.status.ordinal());
                if (statusComp != 0) return statusComp;
                var typeGroupComp = Boolean.compare(other.isActualAssertion(), this.isActualAssertion());
                if (typeGroupComp != 0) return typeGroupComp;
                if (isActualAssertion()) {
                    var priComp = Double.compare(other.priority, this.priority);
                    if (priComp != 0) return priComp;
                    var depthComp = Integer.compare(this.depth, other.depth);
                    if (depthComp != 0) return depthComp;
                }
                return Long.compare(other.timestamp, this.timestamp);
            }
        }

        static class AssertionListCellRenderer extends JPanel implements ListCellRenderer<AssertionViewModel> {
            private final JLabel iconLabel = new JLabel();
            private final JLabel contentLabel = new JLabel();
            private final JLabel detailLabel = new JLabel();
            private final Border activeBorder = new CompoundBorder(new LineBorder(Color.LIGHT_GRAY, 1), new EmptyBorder(3, 5, 3, 5));
            private final Border inactiveBorder = new CompoundBorder(new LineBorder(new Color(240, 240, 240), 1), new EmptyBorder(3, 5, 3, 5));
            private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

            AssertionListCellRenderer() {
                setLayout(new BorderLayout(5, 0));
                var textPanel = new JPanel(new BorderLayout());
                textPanel.setOpaque(false);
                textPanel.add(contentLabel, BorderLayout.CENTER);
                textPanel.add(detailLabel, BorderLayout.SOUTH);
                add(iconLabel, BorderLayout.WEST);
                add(textPanel, BorderLayout.CENTER);
                setOpaque(true);
                contentLabel.setFont(MONOSPACED_FONT);
                detailLabel.setFont(UI_SMALL_FONT);
                iconLabel.setFont(UI_DEFAULT_FONT.deriveFont(Font.BOLD));
                iconLabel.setBorder(new EmptyBorder(0, 4, 0, 4));
                iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            }

            @Override
            public Component getListCellRendererComponent(JList<? extends AssertionViewModel> list, AssertionViewModel value, int index, boolean isSelected, boolean cellHasFocus) {
                contentLabel.setText(value.content());
                contentLabel.setFont(MONOSPACED_FONT);
                String details, iconText = "?";
                Color iconColor = Color.BLACK, bgColor = Color.WHITE, fgColor = Color.BLACK;
                switch (value.displayType()) {
                    case INPUT -> {
                        iconText = "I";
                        iconColor = new Color(0, 128, 0);
                        bgColor = new Color(235, 255, 235);
                    }
                    case ADDED -> {
                        iconText = "A";
                        iconColor = Color.BLACK;
                    }
                    case DERIVED -> {
                        iconText = "D";
                        iconColor = Color.BLUE;
                        bgColor = new Color(230, 240, 255);
                        contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC));
                    }
                    case UNIVERSAL -> {
                        iconText = "∀";
                        iconColor = new Color(0, 0, 128);
                        bgColor = new Color(235, 235, 255);
                    }
                    case SKOLEMIZED -> {
                        iconText = "∃";
                        iconColor = new Color(139, 69, 19);
                        bgColor = new Color(255, 255, 230);
                        contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC));
                    }
                    case LLM_QUEUED_KIF -> {
                        iconText = "Q";
                        iconColor = Color.BLUE;
                        contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC));
                    }
                    case LLM_SKIPPED -> {
                        iconText = "S";
                        iconColor = Color.ORANGE;
                    }
                    case LLM_SUMMARY -> {
                        iconText = "Σ";
                        iconColor = Color.DARK_GRAY;
                    }
                    case LLM_CONCEPTS -> {
                        iconText = "C";
                        iconColor = Color.DARK_GRAY;
                    }
                    case LLM_QUESTION -> {
                        iconText = "?";
                        iconColor = Color.MAGENTA;
                    }
                    case LLM_ERROR -> {
                        iconText = "!";
                        iconColor = Color.RED;
                        bgColor = new Color(255, 230, 230);
                    }
                    case LLM_INFO -> {
                        iconText = "i";
                        iconColor = Color.GRAY;
                    }
                    case LLM_PLACEHOLDER -> {
                        iconText = "…";
                        iconColor = Color.LIGHT_GRAY;
                        contentLabel.setFont(MONOSPACED_FONT.deriveFont(Font.ITALIC));
                    }
                }
                if (value.isActualAssertion())
                    details = String.format("P:%.3f | D:%d | %s%s", value.priority(), value.depth(), timeFormatter.format(Instant.ofEpochMilli(value.timestamp())), value.associatedNoteId() != null ? " (N:" + value.associatedNoteId().replace(ID_PREFIX_NOTE, "") + ")" : "");
                else
                    details = String.format("%s | %s", value.displayType(), timeFormatter.format(Instant.ofEpochMilli(value.timestamp())));
                detailLabel.setText(details);
                iconLabel.setText(iconText);
                if (value.status() != AssertionStatus.ACTIVE) {
                    fgColor = Color.LIGHT_GRAY;
                    contentLabel.setText("<html><strike>" + value.content().replace("<", "&lt;").replace(">", "&gt;") + "</strike></html>");
                    detailLabel.setText(value.status() + " | " + details);
                    bgColor = new Color(248, 248, 248);
                    setBorder(inactiveBorder);
                    iconColor = Color.LIGHT_GRAY;
                } else setBorder(activeBorder);
                if (isSelected) {
                    setBackground(list.getSelectionBackground());
                    contentLabel.setForeground(list.getSelectionForeground());
                    detailLabel.setForeground(list.getSelectionForeground());
                    iconLabel.setForeground(list.getSelectionForeground());
                } else {
                    setBackground(bgColor);
                    contentLabel.setForeground(fgColor);
                    detailLabel.setForeground(value.status() == AssertionStatus.ACTIVE ? Color.GRAY : Color.LIGHT_GRAY);
                    iconLabel.setForeground(iconColor);
                }
                return this;
            }
        }
    }

    private class MyWebSocketServer extends WebSocketServer {
        public MyWebSocketServer(InetSocketAddress address) {
            super(address);
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
            System.out.println("System WebSocket listener active on port " + getPort() + ".");
            setConnectionLostTimeout(WS_CONNECTION_LOST_TIMEOUT_MS);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            var addr = ofNullable(conn).map(WebSocket::getRemoteSocketAddress).map(Object::toString).orElse("server");
            var msg = ofNullable(ex.getMessage()).orElse("");
            if (ex instanceof IOException && (msg.contains("Socket closed") || msg.contains("Connection reset") || msg.contains("Broken pipe")))
                System.err.println("WS Network Info from " + addr + ": " + msg);
            else {
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
                if (!idToRetract.isEmpty())
                    eventBus.publish(new RetractionRequestEvent(idToRetract, RetractionType.BY_ID, sourceId, null));
                else System.err.println("WS Retract Error from " + sourceId + ": Missing assertion ID.");
            } else if (lowerTrimmed.startsWith("register_callback "))
                System.err.println("WS Callback registration via message is not implemented.");
            else {
                try {
                    KifParser.parseKif(trimmed).forEach(term -> eventBus.publish(new ExternalInputEvent(term, sourceId, null)));
                } catch (ParseException | ClassCastException e) {
                    System.err.printf("WS Message Parse Error from %s: %s | Original: %s...%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), MAX_WS_PARSE_PREVIEW)));
                } catch (Exception e) {
                    System.err.println("Unexpected WS message processing error from " + sourceId + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }
}
