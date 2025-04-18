package dumb.cognote5;

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
 * <p>
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
    private final ConcurrentMap<String, ConcurrentMap<KifList, Assertion>> factIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>();
    private final Set<Rule> rules = ConcurrentHashMap.newKeySet();
    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());
    private final Set<KifList> kbContentSnapshot = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<Assertion> kbPriorityQueue = new PriorityBlockingQueue<>();
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>();

    // --- Input Queue ---
    private final PriorityBlockingQueue<InputMessage> inputQueue = new PriorityBlockingQueue<>();

    // --- Reasoning Engine State ---
    private final PriorityQueue<PotentialAssertion> beam = new PriorityQueue<>();
    private final Set<KifList> beamContentSnapshot = ConcurrentHashMap.newKeySet();
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();

    // --- Execution & Control ---
    private final ExecutorService reasonerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ReasonerThread"));
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private volatile boolean running = true;
    private final SwingUI swingUI;

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
                } catch (Exception e) {
                    System.err.println("Error parsing args: " + e.getMessage());
                    printUsageAndExit();
                }
            }

            SwingUI ui = null;
            ProbabilisticKifReasoner server = null;
            try {
                ui = new SwingUI(null); // Initialize UI first
                server = new ProbabilisticKifReasoner(port, beamWidth, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.reasoner = server; // Link UI and Reasoner
                final var finalServer = server;

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown hook activated.");
                    if (finalServer != null) finalServer.stopReasoner();
                }));

                if (rulesFile != null) server.loadExpressionsFromFile(rulesFile);
                else System.out.println("No initial rules/facts file specified.");

                server.startReasoner();
                ui.setVisible(true);

            } catch (IllegalArgumentException e) {
                System.err.println("Configuration Error: " + e.getMessage());
                if (ui != null) ui.dispose(); System.exit(1);
            } catch (IOException | ParseException e) {
                System.err.println("Error loading initial file: " + e.getMessage());
                if (ui != null) ui.dispose(); System.exit(1);
            } catch (Exception e) {
                System.err.println("Failed to initialize/start: " + e.getMessage()); e.printStackTrace();
                if (ui != null) ui.dispose(); System.exit(1);
            }
        });
    }

    private static void printUsageAndExit() {
        System.err.println("""
        Usage: java ProbabilisticKifReasoner [options]
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
            default -> type + " " + kifString;
        };
        try {
            if (/*!isClosed() && */!getConnections().isEmpty())
                broadcast(message);
        } catch (Exception e) { System.err.println("Error during WebSocket broadcast: " + e.getMessage()); }
    }

    // --- File Loading ---
    public void loadExpressionsFromFile(String filename) throws IOException, ParseException {
        System.out.println("Loading expressions from: " + filename);
        var path = Paths.get(filename);
        long processedCount = 0, queuedCount = 0;
        var kifBuffer = new StringBuilder();
        var parenDepth = 0; var lineNumber = 0;

        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                var commentStart = line.indexOf(';'); if (commentStart != -1) line = line.substring(0, commentStart);
                line = line.trim(); if (line.isEmpty()) continue;

                parenDepth += line.chars().filter(c -> c == '(').count() - line.chars().filter(c -> c == ')').count();
                kifBuffer.append(line).append(" ");

                if (parenDepth == 0 && !kifBuffer.isEmpty()) {
                    var kifText = kifBuffer.toString().trim(); kifBuffer.setLength(0);
                    if (!kifText.isEmpty()) {
                        processedCount++;
                        try {
                            for (var term : KifParser.parseKif(kifText)) { queueExpressionFromSource(term, "file:" + filename); queuedCount++; }
                        } catch (ParseException e) { System.err.printf("File Parse Error (line ~%d): %s near '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), 50))); }
                          catch (Exception e) { System.err.printf("File Queue Error (line ~%d): %s for '%s...'%n", lineNumber, e.getMessage(), kifText.substring(0, Math.min(kifText.length(), 50))); }
                    }
                } else if (parenDepth < 0) {
                    System.err.printf("Mismatched parentheses near line %d: '%s'%n", lineNumber, line);
                    parenDepth = 0; kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.printf("Processed %d expressions from %s, queued %d items.%n", processedCount, filename, queuedCount);
    }

    // --- Input Processing ---
    private void queueExpressionFromSource(KifTerm term, String sourceId) {
        if (term instanceof KifList list) {
            list.getOperator().ifPresentOrElse(op -> {
                switch (op) {
                    case "=>", "<=>" -> inputQueue.put(new RuleMessage(list.toKifString(), sourceId));
                    case "exists" -> handleExists(list, sourceId);
                    case "forall" -> handleForall(list, sourceId);
                    default -> { if (!list.terms().isEmpty()) inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null)); }
                }
            }, () -> { // List doesn't start with operator (constant)
                 if (!list.terms().isEmpty()) inputQueue.put(new AssertMessage(1.0, list.toKifString(), sourceId, null));
            });
        } else System.err.println("Warning: Ignoring non-list top-level term from " + sourceId + ": " + term.toKifString());
    }

    private void handleExists(KifList existsExpr, String sourceId) {
        if (existsExpr.size() != 3) { System.err.println("Invalid 'exists' format: " + existsExpr.toKifString()); return; }
        KifTerm varsTerm = existsExpr.get(1), body = existsExpr.get(2);
        Set<KifVariable> variables = KifTerm.collectVariablesFromSpec(varsTerm);
        if (variables.isEmpty()) { queueExpressionFromSource(body, sourceId + "-existsBody"); return; }

        Map<KifVariable, KifTerm> skolemBindings = variables.stream().collect(Collectors.toMap(v -> v,
                v -> new KifConstant("skolem_" + v.name().substring(1) + "_" + idCounter.incrementAndGet())));
        KifTerm skolemizedBody = Unifier.substitute(body, skolemBindings);
        System.out.println("Skolemized '" + existsExpr.toKifString() + "' to '" + skolemizedBody.toKifString() + "' from source " + sourceId);
        queueExpressionFromSource(skolemizedBody, sourceId + "-skolemized");
    }

    private void handleForall(KifList forallExpr, String sourceId) {
        if (forallExpr.size() == 3 && forallExpr.get(2) instanceof KifList bodyList && bodyList.getOperator().filter("=>"::equals).isPresent()) {
            System.out.println("Interpreting 'forall ... (=> ant con)' as rule: " + bodyList.toKifString() + " from source " + sourceId);
            inputQueue.put(new RuleMessage(bodyList.toKifString(), sourceId + "-forallRule"));
        } else System.err.println("Warning: Ignoring complex 'forall': " + forallExpr.toKifString());
    }

    // --- Public Control Methods ---
    public void submitMessage(InputMessage message) { inputQueue.put(message); }
    public void startReasoner() {
        running = true;
        reasonerExecutor.submit(this::reasonerLoop);
        try { start(); } catch (Exception e) { System.err.println("WebSocket server failed to start: " + e.getMessage()); }
    }
    public void stopReasoner() {
        if (!running) return; System.out.println("Stopping reasoner and services..."); running = false;
        shutdownExecutor(reasonerExecutor, "Reasoner"); shutdownExecutor(llmExecutor, "LLM");
        try { stop(1000); System.out.println("WebSocket server stopped."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("Interrupted stopping WebSocket server."); }
        catch (Exception e) { System.err.println("Error stopping WebSocket server: " + e.getMessage()); }
        System.out.println("Reasoner stopped.");
    }
    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try { if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    // --- WebSocket Server Implementation ---
    @Override public void onOpen(WebSocket c, ClientHandshake h) { System.out.println("WS Client connected: " + c.getRemoteSocketAddress()); }
    @Override public void onClose(WebSocket c, int code, String reason, boolean remote) { System.out.println("WS Client disconnected: " + c.getRemoteSocketAddress() + " Code: " + code + " Reason: " + (reason.isEmpty() ? "N/A" : reason)); }
    @Override public void onError(WebSocket c, Exception ex) {
        String addr = (c != null && c.getRemoteSocketAddress() != null) ? c.getRemoteSocketAddress().toString() : "server";
        if (ex instanceof IOException || (ex.getMessage() != null && (ex.getMessage().contains("Socket closed") || ex.getMessage().contains("Connection reset"))))
             System.err.println("WS Network Error from " + addr + ": " + ex.getMessage());
        else { System.err.println("WS Logic Error from " + addr + ": " + ex.getMessage()); ex.printStackTrace(); }
    }
    @Override public void onStart() { System.out.println("Reasoner WebSocket listener active on port " + getPort() + "."); setConnectionLostTimeout(100); }
    @Override public void onMessage(WebSocket conn, String message) {
        var trimmed = message.trim(); var sourceId = conn.getRemoteSocketAddress().toString();
        try {
            var terms = KifParser.parseKif(trimmed); // Try parsing one or more KIF terms first
            if (!terms.isEmpty()) {
                terms.forEach(term -> queueExpressionFromSource(term, sourceId));
            } else { // Try probability format: probability (kif...)
                var m = Pattern.compile("^([0-9.]+)\\s*(\\(.*\\))$", Pattern.DOTALL).matcher(trimmed);
                if (m.matches()) {
                    var probability = Double.parseDouble(m.group(1)); if (probability < 0.0 || probability > 1.0) throw new ParseException("Probability out of range [0,1]");
                    var kifStr = m.group(2); var probTerms = KifParser.parseKif(kifStr);
                    if (probTerms.isEmpty()) throw new ParseException("Empty KIF message received with probability.");
                    for(var term : probTerms) {
                        if (term instanceof KifList list && !list.terms().isEmpty()) submitMessage(new AssertMessage(probability, list.toKifString(), sourceId, null));
                        else throw new ParseException("Expected non-empty KIF list after probability: " + term.toKifString());
                    }
                } else throw new ParseException("Invalid format. Expected KIF list(s) '(..)' or 'probability (..)'");
            }
        } catch (ParseException | NumberFormatException | ClassCastException e) { System.err.printf("WS Message Error from %s: %s | Original: %s%n", sourceId, e.getMessage(), trimmed.substring(0, Math.min(trimmed.length(), 100)));
        } catch (Exception e) { System.err.println("Unexpected WS message processing error: " + e.getMessage()); e.printStackTrace(); }
    }

    // --- Reasoner Core Loop ---
    private void reasonerLoop() {
        System.out.println("Reasoner loop started.");
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                InputMessage msg = inputQueue.poll(); // Check input queue first
                if (msg != null) processInputMessage(msg);
                else if (!beam.isEmpty()) processBeamStep(); // Process beam if input empty
                else { // Wait briefly if both queues are empty
                    msg = inputQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (msg != null) processInputMessage(msg);
                    else Thread.onSpinWait();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); running = false; System.out.println("Reasoner loop interrupted."); }
              catch (Exception e) { System.err.println("Unhandled Error in reasoner loop: " + e.getMessage()); e.printStackTrace(); }
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
        } catch (ParseException e) { System.err.printf("Error processing queued message (%s from %s): %s%n", msg.getClass().getSimpleName(), msg.getSourceId(), e.getMessage()); }
          catch (Exception e) { System.err.printf("Unexpected error processing queued message (%s from %s): %s%n", msg.getClass().getSimpleName(), msg.getSourceId(), e.getMessage()); e.printStackTrace(); }
    }

    private void processAssertionInput(AssertMessage am) throws ParseException {
        var term = KifParser.parseKif(am.kifString()).getFirst(); // Expect single term
        if (term instanceof KifList fact && !fact.terms().isEmpty()) {
            if (!fact.containsVariable()) { // Ground fact
                addToBeam(new PotentialAssertion(fact, am.probability(), Set.of(), am.sourceNoteId()));
                var tempId = "input-" + idCounter.incrementAndGet();
                var inputAssertion = new Assertion(tempId, fact, am.probability(), System.currentTimeMillis(), am.sourceNoteId(), Set.of());
                invokeCallbacks("assert-input", inputAssertion); // Trigger UI/callback immediately for input
            } else System.err.println("Warning: Non-ground assertion input ignored: " + fact.toKifString());
        } else System.err.println("Assertion input ignored (not a non-empty KIF list): " + am.kifString());
    }

    private void processRetractionByIdInput(RetractByIdMessage rm) { retractAssertion(rm.assertionId(), "retract-id"); }
    private void processRetractionByNoteIdInput(RetractByNoteIdMessage rnm) {
        var idsToRetract = noteIdToAssertionIds.remove(rnm.noteId());
        if (idsToRetract != null && !idsToRetract.isEmpty()) {
            System.out.println("Retracting " + idsToRetract.size() + " assertions for note: " + rnm.noteId());
            new HashSet<>(idsToRetract).forEach(id -> retractAssertion(id, "retract-note"));
        }
    }
    private void processRetractionRuleInput(RetractRuleMessage rrm) throws ParseException {
        var term = KifParser.parseKif(rrm.ruleKif()).getFirst();
        if (term instanceof KifList ruleForm) {
            boolean removed = rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));
            if (!removed && ruleForm.getOperator().filter("<=>"::equals).isPresent() && ruleForm.size() == 3) {
                var ant = ruleForm.get(1); var con = ruleForm.get(2);
                var fwd = new KifList(new KifConstant("=>"), ant, con); var bwd = new KifList(new KifConstant("=>"), con, ant);
                removed |= rules.removeIf(r -> r.ruleForm().equals(fwd)); removed |= rules.removeIf(r -> r.ruleForm().equals(bwd));
            }
            System.out.println("Retract rule: " + (removed ? "Success" : "No match found") + " for: " + ruleForm.toKifString());
        } else System.err.println("Retract rule: Input is not a valid rule KIF list: " + rrm.ruleKif());
    }

    private void processRuleInput(RuleMessage ruleMsg) throws ParseException {
        var term = KifParser.parseKif(ruleMsg.kifString()).getFirst();
        if (term instanceof KifList list && list.size() == 3 && list.get(1) instanceof KifTerm antTerm && list.get(2) instanceof KifTerm conTerm) {
            var op = list.getOperator().orElse("");
            if (op.equals("=>") || op.equals("<=>")) {
                addRuleInternal(list, antTerm, conTerm);
                if (op.equals("<=>")) addRuleInternal(new KifList(new KifConstant("=>"), conTerm, antTerm), conTerm, antTerm);
            } else System.err.println("Invalid rule format (expected '=>' or '<=>' op): " + ruleMsg.kifString());
        } else System.err.println("Rule input ignored (not a valid list structure 'op ant con'): " + ruleMsg.kifString());
    }

    private void addRuleInternal(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
        var ruleId = "rule-" + idCounter.incrementAndGet();
        try {
            var newRule = new Rule(ruleId, ruleForm, antecedent, consequent);
            if (rules.add(newRule)) System.out.println("Added rule: " + ruleId + " " + newRule.ruleForm().toKifString());
        } catch (IllegalArgumentException e) { System.err.println("Failed to create rule: " + e.getMessage() + " for " + ruleForm.toKifString()); }
    }

    private void registerCallback(RegisterCallbackMessage rcm) {
        if (rcm.pattern() instanceof KifTerm patternTerm) {
            callbackRegistrations.add(new CallbackRegistration(patternTerm, rcm.callback()));
            System.out.println("Registered external callback for pattern: " + patternTerm.toKifString());
        } else System.err.println("Callback registration failed: Invalid pattern object.");
    }

    // --- Beam Search Step ---
    private void processBeamStep() {
        List<PotentialAssertion> candidates = new ArrayList<>(beamWidth);
        synchronized (beam) { // Lock beam and snapshot together
            int count = 0;
            while (count < beamWidth && !beam.isEmpty()) {
                PotentialAssertion pa = beam.poll(); if (pa == null) continue;
                beamContentSnapshot.remove(pa.fact()); // Always remove polled item from snapshot
                if (!kbContentSnapshot.contains(pa.fact())) { candidates.add(pa); count++; } // Add to candidates if not in KB
            }
        }
        candidates.forEach(pa -> { Assertion added = addAssertionToKb(pa); if (added != null) deriveFrom(added); });
    }

    // --- Forward Chaining Derivation ---
    private void deriveFrom(Assertion newAssertion) {
        rules.stream().forEach(rule -> {
            rule.getAntecedentClauses().stream()
                .filter(clause -> clause.getOperator().equals(newAssertion.fact().getOperator())) // Optimization: Predicate match check
                .forEach(clauseToMatch -> {
                    var initialBindings = Unifier.unify(clauseToMatch, newAssertion.fact(), Map.of());
                    if (initialBindings != null) {
                        List<KifList> remainingClauses = rule.getAntecedentClauses().stream()
                                .filter(c -> c != clauseToMatch).toList(); // Efficiently create list of remaining
                        findMatches(remainingClauses, initialBindings, Set.of(newAssertion.id()))
                            .forEach(match -> {
                                var consequentTerm = Unifier.substitute(rule.consequent(), match.bindings());
                                if (consequentTerm instanceof KifList derived && !derived.containsVariable()) {
                                    var prob = calculateDerivedProbability(match.supportingAssertionIds());
                                    var inheritedNoteId = findCommonSourceNodeId(match.supportingAssertionIds());
                                    addToBeam(new PotentialAssertion(derived, prob, match.supportingAssertionIds(), inheritedNoteId));
                                }
                            });
                    }
                });
        });
    }

    // --- Helper for Finding Matches for Remaining Clauses ---
    private Stream<MatchResult> findMatches(List<KifList> remainingClauses, Map<KifVariable, KifTerm> bindings, Set<String> supportIds) {
        if (remainingClauses.isEmpty()) return Stream.of(new MatchResult(bindings, supportIds));

        var clause = remainingClauses.getFirst(); var nextRemaining = remainingClauses.subList(1, remainingClauses.size());
        var substTerm = Unifier.fullySubstitute(clause, bindings); // Apply current bindings fully

        if (!(substTerm instanceof KifList substClause)) { System.err.println("Warning: Antecedent clause reduced to non-list: " + substTerm.toKifString()); return Stream.empty(); }

        Stream<Assertion> candidates = findCandidateAssertions(substClause);

        if (substClause.containsVariable()) { // Need unification
            return candidates.flatMap(candidate -> {
                var newBindings = Unifier.unify(substClause, candidate.fact(), bindings);
                if (newBindings != null) {
                    Set<String> nextSupport = new HashSet<>(supportIds); nextSupport.add(candidate.id());
                    return findMatches(nextRemaining, newBindings, nextSupport);
                } return Stream.empty();
            });
        } else { // Ground clause, find exact matches
            return candidates.filter(a -> a.fact().equals(substClause)).flatMap(match -> {
                Set<String> nextSupport = new HashSet<>(supportIds); nextSupport.add(match.id());
                return findMatches(nextRemaining, bindings, nextSupport);
            });
        }
    }

    // --- KB Querying & Management ---
    private Stream<Assertion> findCandidateAssertions(KifList clause) {
        return clause.getOperator()
            .map(factIndex::get)
            .map(ConcurrentMap::values)
            .map(Collection::stream)
            .orElseGet(() -> assertionsById.values().stream().filter(Objects::nonNull)); // Fallback: full scan
    }

    private String findCommonSourceNodeId(Set<String> supportIds) {
        if (supportIds == null || supportIds.isEmpty()) return null;
        String commonId = null; boolean first = true;
        for (var id : supportIds) {
            var assertion = assertionsById.get(id); if (assertion == null) continue;
            var noteId = assertion.sourceNoteId();
            if (first) { if (noteId == null) return null; commonId = noteId; first = false; }
            else if (!Objects.equals(commonId, noteId)) return null;
        } return commonId;
    }

    private double calculateDerivedProbability(Set<String> ids) {
        return ids.stream().map(assertionsById::get).filter(Objects::nonNull)
                .mapToDouble(Assertion::probability).min().orElse(0.0);
    }

    private Assertion addAssertionToKb(PotentialAssertion pa) {
        if (kbContentSnapshot.contains(pa.fact())) return null; // Duplicate check
        enforceKbCapacity(); // Evict BEFORE adding if needed
        if (assertionsById.size() >= maxKbSize) { System.err.printf("Warning: KB full (%d/%d), cannot add: %s%n", assertionsById.size(), maxKbSize, pa.fact().toKifString()); return null; }

        var id = "fact-" + idCounter.incrementAndGet(); var timestamp = System.currentTimeMillis();
        var newAssertion = new Assertion(id, pa.fact(), pa.probability(), timestamp, pa.sourceNoteId(), pa.supportingAssertionIds());

        if (assertionsById.putIfAbsent(id, newAssertion) == null) { // Atomic add
            pa.fact().getOperator().ifPresent(p -> factIndex.computeIfAbsent(p, k -> new ConcurrentHashMap<>()).put(pa.fact(), newAssertion));
            kbContentSnapshot.add(pa.fact()); kbPriorityQueue.put(newAssertion);
            if (pa.sourceNoteId() != null) noteIdToAssertionIds.computeIfAbsent(pa.sourceNoteId(), k -> ConcurrentHashMap.newKeySet()).add(id);
            invokeCallbacks("assert-added", newAssertion);
            return newAssertion;
        } else { System.err.println("KB Add Error: ID collision for " + id); return null; } // Highly unlikely
    }

    private void retractAssertion(String id, String reason) {
        var removed = assertionsById.remove(id); // Atomic removal from primary map
        if (removed != null) {
            kbContentSnapshot.remove(removed.fact()); kbPriorityQueue.remove(removed);
            removed.fact().getOperator().ifPresent(p -> factIndex.computeIfPresent(p, (k, v) -> { v.remove(removed.fact()); return v.isEmpty() ? null : v; }));
            if (removed.sourceNoteId() != null) noteIdToAssertionIds.computeIfPresent(removed.sourceNoteId(), (k, v) -> { v.remove(id); return v.isEmpty() ? null : v; });
            invokeCallbacks("assert-retracted", removed);
             // System.out.printf("KB Retract [%s] (%s): %s%n", id, reason, removed.toKifString());
        }
    }

    private void enforceKbCapacity() {
        while (assertionsById.size() >= maxKbSize) {
            Assertion toEvict = kbPriorityQueue.poll(); if (toEvict == null) break;
            Assertion evictedAssertion = assertionsById.get(toEvict.id()); // Check if still present
            if (evictedAssertion != null) {
                System.out.printf("KB Evict (Low Prio) [%s]: P=%.4f %s%n", evictedAssertion.id(), evictedAssertion.probability(), evictedAssertion.toKifString());
                retractAssertion(evictedAssertion.id(), "evict-capacity"); // Use main retraction logic
                invokeCallbacks("evict", evictedAssertion); // Specific eviction callback
            }
        }
    }

    private void addToBeam(PotentialAssertion pa) {
        if (pa == null || pa.fact() == null || kbContentSnapshot.contains(pa.fact()) || beamContentSnapshot.contains(pa.fact())) return;
        synchronized (beam) { // Synchronize access to beam and snapshot
            if (!kbContentSnapshot.contains(pa.fact()) && beamContentSnapshot.add(pa.fact())) beam.offer(pa);
        }
    }

    // --- Callback Handling ---
    private void invokeCallbacks(String type, Assertion assertion) {
        // 1. WebSocket Broadcast
        switch (type) { case "assert-added", "assert-retracted", "evict" -> broadcastMessage(type, assertion);
                        case "assert-input" -> { if (broadcastInputAssertions) broadcastMessage(type, assertion); } }
        // 2. UI Update (if UI exists and is visible)
        if (swingUI != null && swingUI.isDisplayable()) swingUI.handleReasonerCallback(type, assertion);
        // 3. Registered KIF Pattern Callbacks
        callbackRegistrations.forEach(reg -> {
            var bindings = Unifier.unify(reg.pattern(), assertion.fact(), Map.of());
            if (bindings != null) try { reg.callback().onMatch(type, assertion, bindings); }
                                  catch (Exception e) { System.err.println("Error in KIF callback execution: " + e.getMessage()); e.printStackTrace(); }
        });
    }

    // --- LLM Interaction ---
    public CompletableFuture<String> getKifFromLlmAsync(String contextPrompt, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var finalPrompt = (!contextPrompt.toLowerCase().contains("kif assertions:") && !contextPrompt.toLowerCase().contains("proposed fact:")) ? """
                    Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax).
                    Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                    Do not include explanations, markdown formatting (like ```kif), or comments.
                    Focus on factual statements and relationships. Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', attribute relations, etc. where appropriate.
                    If mentioning an entity whose specific name isn't given, use a KIF variable (e.g., `?cpu`, `?company`).

                    Note:
                    "%s"

                    KIF Assertions:""".formatted(contextPrompt) : contextPrompt;

            var payload = new JSONObject().put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", finalPrompt)))
                    .put("stream", false).put("options", new JSONObject().put("temperature", 0.2));
            var request = HttpRequest.newBuilder().uri(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();

            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()); var responseBody = response.body();
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var jsonResponse = new JSONObject(new JSONTokener(responseBody)); String kifResult = "";
                    if (jsonResponse.has("message") && jsonResponse.getJSONObject("message").has("content")) kifResult = jsonResponse.getJSONObject("message").getString("content");
                    else if (jsonResponse.has("choices") && !jsonResponse.getJSONArray("choices").isEmpty() && jsonResponse.getJSONArray("choices").getJSONObject(0).has("message") && jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").has("content")) kifResult = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                    else if (jsonResponse.has("response")) kifResult = jsonResponse.getString("response");
                    else kifResult = findNestedContent(jsonResponse).orElseThrow(() -> new IOException("Unexpected LLM response format: " + responseBody.substring(0, Math.min(responseBody.length(), 200))));

                    var cleanedKif = kifResult.lines()
                            .map(s -> s.replaceAll("```kif", "").replaceAll("```", "").trim())
                            .filter(line -> line.startsWith("(") && line.endsWith(")"))
                            .filter(line -> !line.matches("^\\(\\s*\\)$")).collect(Collectors.joining("\n"));
                    if (cleanedKif.isEmpty() && !kifResult.isBlank()) System.err.println("LLM Warning ("+noteId+"): Result contained text but no valid KIF lines:\n---\n"+kifResult+"\n---");
                    return cleanedKif;
                } else throw new IOException("LLM API request failed: " + response.statusCode() + " " + responseBody);
            } catch (IOException | InterruptedException e) { System.err.println("LLM API interaction failed for note " + noteId + ": " + e.getMessage()); if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new CompletionException(e); }
              catch (Exception e) { System.err.println("LLM response processing failed for note " + noteId + ": " + e.getMessage()); e.printStackTrace(); throw new CompletionException(e); }
        }, llmExecutor);
    }

    private Optional<String> findNestedContent(JSONObject obj) {
        if (obj.has("content") && obj.get("content") instanceof String s) return Optional.of(s);
        for (String key : obj.keySet()) {
            Object value = obj.get(key);
            if (value instanceof JSONObject nestedObj) { Optional<String> found = findNestedContent(nestedObj); if (found.isPresent()) return found; }
            else if (value instanceof JSONArray arr) {
                for (int i = 0; i < arr.length(); i++)
                    if (arr.get(i) instanceof JSONObject nestedArrObj) { Optional<String> found = findNestedContent(nestedArrObj); if (found.isPresent()) return found; }
            }
        } return Optional.empty();
    }

    // --- KIF Data Structures ---
    sealed interface KifTerm permits KifConstant, KifVariable, KifList {
        default String toKifString() { var sb = new StringBuilder(); writeKifString(sb); return sb.toString(); }
        void writeKifString(StringBuilder sb);
        default boolean containsVariable() { return false; }
        default Set<KifVariable> getVariables() { Set<KifVariable> vars = new HashSet<>(); collectVariablesRecursive(this, vars); return Collections.unmodifiableSet(vars); }
        private static void collectVariablesRecursive(KifTerm term, Set<KifVariable> vars) {
            switch (term) { case KifVariable v -> vars.add(v); case KifList l -> l.terms().forEach(t -> collectVariablesRecursive(t, vars)); case KifConstant c -> {} }
        }
        static Set<KifVariable> collectVariablesFromSpec(KifTerm varsTerm) {
            Set<KifVariable> vars = new HashSet<>();
            if (varsTerm instanceof KifVariable v) vars.add(v);
            else if (varsTerm instanceof KifList l) l.terms().stream().filter(KifVariable.class::isInstance).map(KifVariable.class::cast).forEach(vars::add);
            else System.err.println("Warning: Invalid variable spec in quantifier: " + varsTerm.toKifString());
            return Collections.unmodifiableSet(vars);
        }
    }
    record KifConstant(String value) implements KifTerm {
        KifConstant { Objects.requireNonNull(value); }
        @Override public void writeKifString(StringBuilder sb) {
            boolean needsQuotes = value.isEmpty() || value.chars().anyMatch(c -> Character.isWhitespace(c) || "()\";?_".indexOf(c) != -1);
            if (needsQuotes) sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"'); else sb.append(value);
        }
    }
    record KifVariable(String name) implements KifTerm {
        KifVariable { Objects.requireNonNull(name); if (!name.startsWith("?")) throw new IllegalArgumentException("Variable name must start with '?'"); }
        @Override public void writeKifString(StringBuilder sb) { sb.append(name); }
        @Override public boolean containsVariable() { return true; }
    }
    record KifList(List<KifTerm> terms) implements KifTerm {
        KifList(KifTerm... terms) { this(List.of(terms)); }
        KifList { terms = List.copyOf(Objects.requireNonNull(terms)); } // Ensure immutable copy
        @Override public boolean equals(Object o) { return o instanceof KifList l && terms.equals(l.terms); }
        @Override public int hashCode() { return terms.hashCode(); }
        @Override public void writeKifString(StringBuilder sb) {
            sb.append('('); IntStream.range(0, terms.size()).forEach(i -> { terms.get(i).writeKifString(sb); if (i < terms.size() - 1) sb.append(' '); }); sb.append(')');
        }
        KifTerm get(int index) { return terms.get(index); }
        int size() { return terms.size(); }
        Optional<String> getOperator() { return terms.isEmpty() || !(terms.getFirst() instanceof KifConstant c) ? Optional.empty() : Optional.of(c.value()); }
        @Override public boolean containsVariable() { return terms.stream().anyMatch(KifTerm::containsVariable); }
    }

    // --- Knowledge Representation ---
    record Assertion(String id, KifList fact, double probability, long timestamp, String sourceNoteId, Set<String> directSupportingIds) implements Comparable<Assertion> {
        Assertion { Objects.requireNonNull(id); Objects.requireNonNull(fact); Objects.requireNonNull(directSupportingIds); if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]"); directSupportingIds = Set.copyOf(directSupportingIds); }
        @Override public int compareTo(Assertion other) { return Double.compare(this.probability, other.probability); } // Min-heap for eviction
        String toKifString() { return fact.toKifString(); }
    }
    record Rule(String id, KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
        private static final KifConstant AND_OP = new KifConstant("and");
        Rule { Objects.requireNonNull(id); Objects.requireNonNull(ruleForm); Objects.requireNonNull(antecedent); Objects.requireNonNull(consequent);
            if (!(ruleForm.getOperator().filter(op -> op.equals("=>") || op.equals("<=>")).isPresent() && ruleForm.size() == 3)) throw new IllegalArgumentException("Rule form must be (=> ant con) or (<=> ant con): " + ruleForm.toKifString());
            if (!(antecedent instanceof KifList antList && (antList.getOperator().filter("and"::equals).map(op -> antList.terms().stream().skip(1).allMatch(KifList.class::isInstance)).orElse(true)))) throw new IllegalArgumentException("Antecedent must be a KIF list or valid 'and' list: " + ruleForm.toKifString());
            validateUnboundVariables(ruleForm, antecedent, consequent); // Separate validation logic
        }
        private static void validateUnboundVariables(KifList ruleForm, KifTerm antecedent, KifTerm consequent) {
            Set<KifVariable> antVars = antecedent.getVariables(); Set<KifVariable> conVarsAll = consequent.getVariables();
            Set<KifVariable> conVarsBoundLocally = getQuantifierBoundVariables(consequent);
            Set<KifVariable> conVarsNeedingBinding = new HashSet<>(conVarsAll); conVarsNeedingBinding.removeAll(conVarsBoundLocally); conVarsNeedingBinding.removeAll(antVars);
            if (!conVarsNeedingBinding.isEmpty() && !ruleForm.getOperator().filter("<=>"::equals).isPresent())
                System.err.println("Warning: Rule consequent has unbound variables: " + conVarsNeedingBinding.stream().map(KifVariable::name).collect(Collectors.joining(", ")) + " in " + ruleForm.toKifString());
        }
        List<KifList> getAntecedentClauses() {
            return (antecedent instanceof KifList list && list.size() > 1 && list.get(0).equals(AND_OP))
                   ? list.terms().subList(1, list.size()).stream().filter(KifList.class::isInstance).map(KifList.class::cast).toList()
                   : (antecedent instanceof KifList list ? List.of(list) : List.of());
        }
        private static Set<KifVariable> getQuantifierBoundVariables(KifTerm term) { Set<KifVariable> bound = new HashSet<>(); collectQuantifierBoundVariablesRecursive(term, bound); return Collections.unmodifiableSet(bound); }
        private static void collectQuantifierBoundVariablesRecursive(KifTerm term, Set<KifVariable> boundVars) {
            if (term instanceof KifList list && list.size() == 3 && list.getOperator().filter(op -> op.equals("exists") || op.equals("forall")).isPresent()) {
                boundVars.addAll(KifTerm.collectVariablesFromSpec(list.get(1))); collectQuantifierBoundVariablesRecursive(list.get(2), boundVars);
            } else if (term instanceof KifList list) list.terms().forEach(sub -> collectQuantifierBoundVariablesRecursive(sub, boundVars));
        }
        @Override public boolean equals(Object o) { return o instanceof Rule r && ruleForm.equals(r.ruleForm); }
        @Override public int hashCode() { return ruleForm.hashCode(); }
    }

    // --- Parser ---
    static class KifParser {
        private final StringReader reader; private int currentChar = -2; private int line = 1; private int col = 0;
        KifParser(String input) { this.reader = new StringReader(input.trim()); }
        static List<KifTerm> parseKif(String input) throws ParseException {
            if (input == null || input.isBlank()) return List.of();
            try { return new KifParser(input).parseTopLevel(); } catch (IOException e) { throw new ParseException("Read error: " + e.getMessage()); }
        }
        private List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>(); consumeWhitespaceAndComments();
            while (peek() != -1) { terms.add(parseTerm()); consumeWhitespaceAndComments(); } return Collections.unmodifiableList(terms);
        }
        private KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments(); int c = peek(); if (c == -1) throw createParseException("Unexpected EOF");
            return switch (c) { case '(' -> parseList(); case '"' -> parseQuotedString(); case '?' -> parseVariable(); default -> parseAtom(); };
        }
        private KifList parseList() throws IOException, ParseException {
            consumeChar('('); List<KifTerm> terms = new ArrayList<>();
            while (true) { consumeWhitespaceAndComments(); int c = peek(); if (c == ')') { consumeChar(')'); return new KifList(terms); } if (c == -1) throw createParseException("Unmatched parenthesis"); terms.add(parseTerm()); }
        }
        private KifVariable parseVariable() throws IOException, ParseException {
            consumeChar('?'); var name = new StringBuilder("?"); if (!isValidAtomChar(peek(), true)) throw createParseException("Variable name expected after '?'");
            while (isValidAtomChar(peek(), false)) name.append((char) consumeChar()); if (name.length() == 1) throw createParseException("Empty variable name"); return new KifVariable(name.toString());
        }
        private KifConstant parseAtom() throws IOException, ParseException {
            var atom = new StringBuilder(); if (!isValidAtomChar(peek(), true)) throw createParseException("Invalid start for atom");
            while (isValidAtomChar(peek(), false)) atom.append((char) consumeChar()); if (atom.isEmpty()) throw createParseException("Empty atom"); return new KifConstant(atom.toString());
        }
        private boolean isValidAtomChar(int c, boolean isFirstChar) { return !(c == -1 || Character.isWhitespace(c) || "()\";?".indexOf(c) != -1); }
        private KifConstant parseQuotedString() throws IOException, ParseException {
            consumeChar('"'); var sb = new StringBuilder();
            while (true) { int c = consumeChar(); if (c == '"') return new KifConstant(sb.toString()); if (c == -1) throw createParseException("Unmatched quote");
                           if (c == '\\') { int next = consumeChar(); if (next == -1) throw createParseException("EOF after escape"); sb.append((char) switch(next) { case 'n'->'\n'; case 't'->'\t'; default->next; }); } else sb.append((char) c); }
        }
        private int peek() throws IOException { if (currentChar == -2) currentChar = reader.read(); return currentChar; }
        private int consumeChar() throws IOException { int c = peek(); if (c != -1) { currentChar = -2; if (c == '\n') { line++; col = 0; } else col++; } return c; }
        private void consumeChar(char expected) throws IOException, ParseException { if (consumeChar() != expected) throw createParseException("Expected '" + expected + "'"); }
        private void consumeWhitespaceAndComments() throws IOException {
            while (true) { int c = peek(); if (c == -1) break; if (Character.isWhitespace(c)) consumeChar(); else if (c == ';') { while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar(); } else break; }
        }
        private ParseException createParseException(String message) { return new ParseException(message + " at line " + line + " col " + col); }
    }
    static class ParseException extends Exception { ParseException(String message) { super(message); } }

    // --- Input Message Types (PriorityQueue uses compareTo: higher value = higher priority) ---
    sealed interface InputMessage extends Comparable<InputMessage> { double getPriority(); String getSourceId(); }
    record AssertMessage(double probability, String kifString, String sourceId, String sourceNoteId) implements InputMessage {
        @Override public int compareTo(InputMessage o) { return Double.compare(getPriority(), o.getPriority()); } // Higher prob first
        @Override public double getPriority() { return probability * 10; } // Scale prob [0,1] -> [0,10] for range
        @Override public String getSourceId() {return sourceId;}
    }
    record RegisterCallbackMessage(KifTerm pattern, KifCallback callback, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage o) { return Double.compare(getPriority(), o.getPriority()); }
        @Override public double getPriority() { return 70.0; } // Priority 70
        @Override public String getSourceId() {return sourceId;}
    }
    record RuleMessage(String kifString, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage o) { return Double.compare(getPriority(), o.getPriority()); }
        @Override public double getPriority() { return 80.0; } // Priority 80
        @Override public String getSourceId() {return sourceId;}
    }
    record RetractRuleMessage(String ruleKif, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage o) { return Double.compare(getPriority(), o.getPriority()); }
        @Override public double getPriority() { return 90.0; } // Priority 90
        @Override public String getSourceId() {return sourceId;}
    }
    record RetractByNoteIdMessage(String noteId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage o) { return Double.compare(getPriority(), o.getPriority()); }
        @Override public double getPriority() { return 100.0; } // Priority 100 (Highest)
        @Override public String getSourceId() {return sourceId;}
    }
    record RetractByIdMessage(String assertionId, String sourceId) implements InputMessage {
        @Override public int compareTo(InputMessage o) { return Double.compare(getPriority(), o.getPriority()); }
        @Override public double getPriority() { return 100.0; } // Priority 100 (Highest)
        @Override public String getSourceId() {return sourceId;}
    }

    // --- Reasoner Internals ---
    record PotentialAssertion(KifList fact, double probability, Set<String> supportingAssertionIds, String sourceNoteId) implements Comparable<PotentialAssertion> {
        PotentialAssertion { Objects.requireNonNull(fact); Objects.requireNonNull(supportingAssertionIds); if (probability < 0.0 || probability > 1.0) throw new IllegalArgumentException("Probability out of range [0,1]"); supportingAssertionIds = Set.copyOf(supportingAssertionIds); }
        @Override public int compareTo(PotentialAssertion other) { return Double.compare(other.probability, this.probability); } // Max-heap for beam
        @Override public boolean equals(Object o) { return o instanceof PotentialAssertion pa && fact.equals(pa.fact); }
        @Override public int hashCode() { return fact.hashCode(); }
    }
    record CallbackRegistration(KifTerm pattern, KifCallback callback) { CallbackRegistration { Objects.requireNonNull(pattern); Objects.requireNonNull(callback); } }
    record MatchResult(Map<KifVariable, KifTerm> bindings, Set<String> supportingAssertionIds) { MatchResult { Objects.requireNonNull(bindings); Objects.requireNonNull(supportingAssertionIds); } }
    @FunctionalInterface interface KifCallback { void onMatch(String type, Assertion assertion, Map<KifVariable, KifTerm> bindings); }

    // --- Unification Logic ---
    static class Unifier {
        private static final int MAX_SUBST_DEPTH = 100;
        static Map<KifVariable, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVariable, KifTerm> bindings) {
            if (bindings == null) return null; KifTerm xSubst = fullySubstitute(x, bindings); KifTerm ySubst = fullySubstitute(y, bindings);
            if (xSubst.equals(ySubst)) return bindings; if (xSubst instanceof KifVariable v) return bindVariable(v, ySubst, bindings); if (ySubst instanceof KifVariable v) return bindVariable(v, xSubst, bindings);
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var currentBindings = bindings;
                for (var i = 0; i < lx.size(); i++) { currentBindings = unify(lx.get(i), ly.get(i), currentBindings); if (currentBindings == null) return null; } return currentBindings;
            } return null;
        }
        static KifTerm substitute(KifTerm term, Map<KifVariable, KifTerm> bindings) { return fullySubstitute(term, bindings); }
        private static KifTerm fullySubstitute(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term; var current = term;
            for (var depth = 0; depth < MAX_SUBST_DEPTH; depth++) { var next = substituteOnce(current, bindings); if (next == current || next.equals(current)) return current; current = next; }
            System.err.println("Warning: Substitution depth limit reached for: " + term.toKifString()); return current;
        }
        private static KifTerm substituteOnce(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            return switch (term) {
                case KifVariable v -> bindings.getOrDefault(v, v);
                case KifList l -> {
                    List<KifTerm> originalTerms = l.terms(); List<KifTerm> substitutedTerms = null; boolean changed = false;
                    for (int i = 0; i < originalTerms.size(); i++) { KifTerm sub = substituteOnce(originalTerms.get(i), bindings); if (sub != originalTerms.get(i)) changed = true; if (changed && substitutedTerms == null) substitutedTerms = new ArrayList<>(originalTerms.subList(0, i)); if (substitutedTerms != null) substitutedTerms.add(sub); }
                    yield changed ? new KifList(substitutedTerms != null ? substitutedTerms : originalTerms) : l;
                } case KifConstant c -> c;
            };
        }
        private static Map<KifVariable, KifTerm> bindVariable(KifVariable var, KifTerm value, Map<KifVariable, KifTerm> bindings) {
            if (var.equals(value)) return bindings; if (bindings.containsKey(var)) return unify(bindings.get(var), value, bindings);
            if (value instanceof KifVariable vVal && bindings.containsKey(vVal)) return bindVariable(var, bindings.get(vVal), bindings);
            if (occursCheck(var, value, bindings)) return null; Map<KifVariable, KifTerm> newBindings = new HashMap<>(bindings); newBindings.put(var, value); return Collections.unmodifiableMap(newBindings);
        }
        private static boolean occursCheck(KifVariable var, KifTerm term, Map<KifVariable, KifTerm> bindings) {
            KifTerm substTerm = fullySubstitute(term, bindings);
            return switch (substTerm) { case KifVariable v -> var.equals(v); case KifList l -> l.terms.stream().anyMatch(t -> occursCheck(var, t, bindings)); case KifConstant c -> false; };
        }
    }

    // --- Swing UI Class (Static Nested) ---
    static class SwingUI extends JFrame {
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JTextArea derivationView = new JTextArea();
        private final JButton addButton = new JButton("Add Note");
        private final JButton removeButton = new JButton("Remove Note");
        private final JButton analyzeButton = new JButton("Analyze Note");
        private final JButton resolveButton = new JButton("Resolve...");
        private final JLabel statusLabel = new JLabel("Status: Idle");
        ProbabilisticKifReasoner reasoner; // Link set after construction
        private Note currentNote = null;

        public SwingUI(ProbabilisticKifReasoner reasoner) {
            super("Cognote - Probabilistic KIF Reasoner"); this.reasoner = reasoner;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); setSize(1200, 800); setLocationRelativeTo(null);
            setupFonts(); setupComponents(); setupLayout(); setupListeners();
            addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { System.out.println("UI closing event."); if (reasoner != null) reasoner.stopReasoner(); dispose(); System.exit(0); } });
            updateUIForSelection();
        }
        private void setupFonts() {
            UIManager.put("TextArea.font", UI_DEFAULT_FONT); UIManager.put("List.font", UI_DEFAULT_FONT); UIManager.put("Button.font", UI_DEFAULT_FONT); UIManager.put("Label.font", UI_DEFAULT_FONT);
            noteList.setFont(UI_DEFAULT_FONT); noteEditor.setFont(UI_DEFAULT_FONT); derivationView.setFont(MONOSPACED_FONT);
            addButton.setFont(UI_DEFAULT_FONT); removeButton.setFont(UI_DEFAULT_FONT); analyzeButton.setFont(UI_DEFAULT_FONT); resolveButton.setFont(UI_DEFAULT_FONT); statusLabel.setFont(UI_DEFAULT_FONT);
        }
        private void setupComponents() {
            noteEditor.setLineWrap(true); noteEditor.setWrapStyleWord(true); derivationView.setEditable(false); derivationView.setLineWrap(true); derivationView.setWrapStyleWord(true);
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); noteList.setCellRenderer(new NoteListCellRenderer()); statusLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        }
        private void setupLayout() {
            var derivationScrollPane = new JScrollPane(derivationView); var editorScrollPane = new JScrollPane(noteEditor); var leftScrollPane = new JScrollPane(noteList); leftScrollPane.setMinimumSize(new Dimension(200, 100));
            var rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScrollPane, derivationScrollPane); rightSplit.setResizeWeight(0.65);
            var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightSplit); mainSplit.setResizeWeight(0.25);
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5)); buttonPanel.add(addButton); buttonPanel.add(removeButton); buttonPanel.add(analyzeButton); buttonPanel.add(resolveButton);
            var bottomPanel = new JPanel(new BorderLayout()); bottomPanel.add(buttonPanel, BorderLayout.WEST); bottomPanel.add(statusLabel, BorderLayout.CENTER); bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
            getContentPane().add(mainSplit, BorderLayout.CENTER); getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        }
        private void setupListeners() {
            addButton.addActionListener(this::addNote); removeButton.addActionListener(this::removeNote); analyzeButton.addActionListener(this::analyzeNote); resolveButton.addActionListener(this::resolveMismatches);
            noteList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) { Note selected = noteList.getSelectedValue(); if (currentNote != null && selected != currentNote && noteEditor.isEnabled()) currentNote.text = noteEditor.getText(); currentNote = selected; updateUIForSelection(); } });
            noteEditor.addFocusListener(new java.awt.event.FocusAdapter() { public void focusLost(java.awt.event.FocusEvent evt) { if (currentNote != null && noteEditor.isEnabled()) currentNote.text = noteEditor.getText(); } });
        }
        private void updateUIForSelection() {
            boolean noteSelected = (currentNote != null); noteEditor.setEnabled(noteSelected); removeButton.setEnabled(noteSelected); analyzeButton.setEnabled(noteSelected); resolveButton.setEnabled(noteSelected);
            if (noteSelected) { noteEditor.setText(currentNote.text); noteEditor.setCaretPosition(0); displayExistingDerivations(currentNote); setTitle("Cognote - " + currentNote.title); }
            else { noteEditor.setText(""); derivationView.setText(""); setTitle("Cognote - Probabilistic KIF Reasoner"); }
        }
        private void addNote(ActionEvent e) {
            var title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) { var noteId = "note-" + UUID.randomUUID(); var newNote = new Note(noteId, title.trim(), ""); noteListModel.addElement(newNote); noteList.setSelectedValue(newNote, true); }
        }
        private void removeNote(ActionEvent e) {
            if (currentNote == null) return;
            int confirm = JOptionPane.showConfirmDialog(this, "Remove note '" + currentNote.title + "' and retract associated assertions?", "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                statusLabel.setText("Status: Removing '" + currentNote.title + "'...");
                reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Remove"));
                int selectedIndex = noteList.getSelectedIndex(); noteListModel.removeElement(currentNote); currentNote = null;
                if (noteListModel.getSize() > 0) noteList.setSelectedIndex(Math.min(selectedIndex, noteListModel.getSize() - 1)); else updateUIForSelection();
                statusLabel.setText("Status: Note removed.");
            }
        }
        private void analyzeNote(ActionEvent e) {
            if (currentNote == null || reasoner == null) return; statusLabel.setText("Status: Analyzing '" + currentNote.title + "'..."); setButtonsEnabled(false);
            var noteTextToAnalyze = noteEditor.getText(); currentNote.text = noteTextToAnalyze; derivationView.setText("Analyzing Note...\n--------------------\n");
            reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Analyze")); // Retract old first

            reasoner.getKifFromLlmAsync(noteTextToAnalyze, currentNote.id)
                .thenAcceptAsync(kifString -> {
                    try {
                        var terms = KifParser.parseKif(kifString); int submittedCount = 0, groundedCount = 0, skippedCount = 0;
                        for (var term : terms) {
                            if (term instanceof KifList fact) {
                                if (!fact.containsVariable()) { reasoner.submitMessage(new AssertMessage(1.0, fact.toKifString(), "UI-LLM", currentNote.id)); submittedCount++; }
                                else { KifTerm groundedTerm = groundLlmTerm(fact, currentNote.id);
                                    if (groundedTerm instanceof KifList groundedFact && !groundedFact.containsVariable()) { System.out.println("Grounded '" + fact.toKifString() + "' to '" + groundedFact.toKifString() + "'"); reasoner.submitMessage(new AssertMessage(1.0, groundedFact.toKifString(), "UI-LLM-Grounded", currentNote.id)); groundedCount++; }
                                    else { System.err.println("Failed to ground LLM KIF: " + (groundedTerm != null ? groundedTerm.toKifString() : "null")); skippedCount++; }
                                }
                            } else { System.err.println("LLM generated non-list KIF, skipped: " + term.toKifString()); skippedCount++; }
                        }
                        final int totalSubmitted = submittedCount + groundedCount; String statusMsg = String.format("Status: Analyzed '%s'. Submitted %d assertions (%d grounded).", currentNote.title, totalSubmitted, groundedCount);
                        if (skippedCount > 0) statusMsg += String.format(" Skipped %d invalid/ungroundable.", skippedCount); statusLabel.setText(statusMsg);
                        derivationView.setText("Derivations for: " + currentNote.title + "\n--------------------\n"); // Clear view, callbacks will repopulate
                    } catch (ParseException pe) { statusLabel.setText("Status: KIF Parse Error from LLM."); System.err.println("KIF Parse Error: " + pe.getMessage() + "\nInput:\n" + kifString); JOptionPane.showMessageDialog(SwingUI.this, "Could not parse KIF from LLM:\n" + pe.getMessage(), "KIF Parse Error", JOptionPane.ERROR_MESSAGE); derivationView.setText("Error parsing KIF from LLM.\n--------------------\n" + kifString); }
                      catch (Exception ex) { statusLabel.setText("Status: Error processing LLM response."); System.err.println("Error processing LLM KIF: " + ex); ex.printStackTrace(); JOptionPane.showMessageDialog(SwingUI.this, "Error processing LLM response:\n" + ex.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE); }
                      finally { setButtonsEnabled(true); }
                }, SwingUtilities::invokeLater)
                .exceptionallyAsync(ex -> { var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex; statusLabel.setText("Status: LLM Analysis Failed."); System.err.println("LLM call failed: " + cause.getMessage()); cause.printStackTrace(); JOptionPane.showMessageDialog(SwingUI.this, "LLM communication failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE); setButtonsEnabled(true); derivationView.setText("LLM Analysis Failed.\n--------------------\n" + cause.getMessage()); return null; }, SwingUtilities::invokeLater);
        }
        private KifTerm groundLlmTerm(KifTerm term, String noteId) {
            Set<KifVariable> variables = term.getVariables(); if (variables.isEmpty()) return term;
            Map<KifVariable, KifTerm> groundingMap = new HashMap<>(); String notePrefix = "note_" + noteId.replaceAll("[^a-zA-Z0-9_]", "_") + "_";
            variables.forEach(var -> groundingMap.put(var, new KifConstant(notePrefix + var.name().substring(1))));
            return Unifier.substitute(term, groundingMap);
        }
        private void resolveMismatches(ActionEvent e) {
            if (currentNote == null || reasoner == null) return; statusLabel.setText("Status: Finding potential mismatches for '" + currentNote.title + "'..."); setButtonsEnabled(false);
            CompletableFuture.supplyAsync(() -> findPotentialMismatches(currentNote.id), Executors.newSingleThreadExecutor())
                .thenAcceptAsync(mismatches -> { if (mismatches.isEmpty()) { statusLabel.setText("Status: No potential mismatches found."); JOptionPane.showMessageDialog(SwingUI.this, "No potential rule/fact mismatches found for this note.", "Resolution", JOptionPane.INFORMATION_MESSAGE); } else { statusLabel.setText("Status: Found " + mismatches.size() + " potential mismatches."); showMismatchResolutionDialog(mismatches); } }, SwingUtilities::invokeLater)
                .whenCompleteAsync((res, err) -> setButtonsEnabled(true), SwingUtilities::invokeLater);
        }
        private List<PotentialBridge> findPotentialMismatches(String noteId) {
            List<PotentialBridge> potentialBridges = new ArrayList<>(); Set<String> assertionIds = reasoner.noteIdToAssertionIds.getOrDefault(noteId, Set.of()); if (assertionIds.isEmpty()) return potentialBridges;
            List<Assertion> noteAssertions = assertionIds.stream().map(reasoner.assertionsById::get).filter(Objects::nonNull).toList();

            for (Rule rule : reasoner.rules) {
                for (KifList antClause : rule.getAntecedentClauses()) {
                    boolean clauseMatchedInKb = reasoner.findCandidateAssertions(antClause).anyMatch(kbAssertion -> Unifier.unify(antClause, kbAssertion.fact(), Map.of()) != null);
                    if (!clauseMatchedInKb) { // If clause is unmatched in the whole KB...
                        for (Assertion noteAssertion : noteAssertions) { // ...check note's assertions
                            Optional<String> clauseOp = antClause.getOperator(), factOp = noteAssertion.fact().getOperator();
                            // Check if predicates and arity match, but direct unification failed (implied by !clauseMatchedInKb check earlier)
                            if (clauseOp.isPresent() && factOp.isPresent() && clauseOp.equals(factOp) && antClause.size() == noteAssertion.fact().size()) {
                                // Attempt to create a bridging fact by substituting constants from note into variable slots of the clause
                                Map<KifVariable, KifTerm> proposalBindings = new HashMap<>(); boolean canPropose = antClause.containsVariable();
                                if (canPropose) {
                                    for (int i = 0; i < antClause.size(); i++) {
                                        if (antClause.get(i) instanceof KifVariable var && noteAssertion.fact().get(i) instanceof KifConstant con) proposalBindings.put(var, con);
                                        // We allow proposing even if other constant terms differ, LLM/user can verify relevance
                                    }
                                }
                                if (canPropose && !proposalBindings.isEmpty()) { // Must have actually bound something
                                    KifTerm proposedTerm = Unifier.substitute(antClause, proposalBindings);
                                    if (proposedTerm instanceof KifList proposedFact && !proposedFact.containsVariable() && !reasoner.kbContentSnapshot.contains(proposedFact) && !reasoner.beamContentSnapshot.contains(proposedFact)) {
                                        potentialBridges.add(new PotentialBridge(noteAssertion.fact(), antClause, rule.ruleForm(), proposedFact));
                                    }
                                }
                            }
                        }
                    }
                }
            } return potentialBridges.stream().distinct().toList(); // Remove duplicates based on proposed bridge
        }
        private void showMismatchResolutionDialog(List<PotentialBridge> mismatches) {
            new MismatchResolutionDialog(this, mismatches, reasoner, currentNote.id).setVisible(true);
        }
        public void handleReasonerCallback(String type, Assertion assertion) {
            SwingUtilities.invokeLater(() -> { if (currentNote != null && isRelatedToNote(assertion, currentNote.id)) updateDerivationViewForAssertion(type, assertion); updateStatusBar(type, assertion); });
        }
        private void updateDerivationViewForAssertion(String type, Assertion assertion) {
            String linePrefix = String.format("P=%.3f %s", assertion.probability(), assertion.toKifString()); String lineSuffix = String.format(" [%s]", assertion.id()); String fullLine = linePrefix + lineSuffix; String currentText = derivationView.getText();
            switch (type) {
                case "assert-added", "assert-input" -> { if (!currentText.contains(lineSuffix)) { derivationView.append(fullLine + "\n"); derivationView.setCaretPosition(derivationView.getDocument().getLength()); } }
                case "assert-retracted", "evict" -> {
                    String updatedText = currentText.lines().map(l -> l.trim().endsWith(lineSuffix) ? "# " + type.toUpperCase() + ": " + l : l).collect(Collectors.joining("\n"));
                    if (!currentText.equals(updatedText)) { int caretPos = derivationView.getCaretPosition(); derivationView.setText(updatedText); try { derivationView.setCaretPosition(Math.min(caretPos, updatedText.length())); } catch (IllegalArgumentException ignored) { derivationView.setCaretPosition(0); } }
                }
            }
        }
        private void updateStatusBar(String type, Assertion assertion) {
            String status = switch (type) {
                case "assert-added" -> String.format("Status: Derived %s (P=%.3f)", assertion.id(), assertion.probability());
                case "assert-retracted" -> String.format("Status: Retracted %s", assertion.id());
                case "evict" -> String.format("Status: Evicted %s (Low P)", assertion.id());
                case "assert-input" -> (currentNote != null && Objects.equals(assertion.sourceNoteId(), currentNote.id)) ? String.format("Status: Processed input %s for current note", assertion.id()) : null; default -> null;
            }; if (status != null && !statusLabel.getText().equals(status)) statusLabel.setText(status);
        }
        private boolean isRelatedToNote(Assertion assertion, String targetNoteId) {
            if (targetNoteId == null) return false; if (targetNoteId.equals(assertion.sourceNoteId())) return true;
            Queue<String> toCheck = new LinkedList<>(assertion.directSupportingIds()); Set<String> visited = new HashSet<>(assertion.directSupportingIds()); visited.add(assertion.id());
            while (!toCheck.isEmpty()) {
                var parent = reasoner.assertionsById.get(toCheck.poll());
                if (parent != null) { if (targetNoteId.equals(parent.sourceNoteId())) return true; parent.directSupportingIds().stream().filter(visited::add).forEach(toCheck::offer); }
            } return false;
        }
        private void displayExistingDerivations(Note note) {
            if (note == null || reasoner == null) { derivationView.setText(""); return; }
            derivationView.setText("Derivations for: " + note.title + "\n--------------------\n");
            reasoner.assertionsById.values().stream().filter(a -> isRelatedToNote(a, note.id))
                .sorted(Comparator.comparingLong(Assertion::timestamp))
                .forEach(a -> derivationView.append(String.format("P=%.3f %s [%s]\n", a.probability(), a.toKifString(), a.id())));
            derivationView.setCaretPosition(0);
        }
        private void setButtonsEnabled(boolean enabled) {
            addButton.setEnabled(enabled); boolean noteSelected = (currentNote != null);
            removeButton.setEnabled(enabled && noteSelected); analyzeButton.setEnabled(enabled && noteSelected); resolveButton.setEnabled(enabled && noteSelected);
        }

        // --- Static Inner Data Classes ---
        static class Note { final String id; String title; String text; Note(String id, String title, String text) { this.id = Objects.requireNonNull(id); this.title = Objects.requireNonNull(title); this.text = Objects.requireNonNull(text); } @Override public String toString() { return title; } @Override public boolean equals(Object o) { return o instanceof Note n && id.equals(n.id); } @Override public int hashCode() { return id.hashCode(); } }
        static class NoteListCellRenderer extends DefaultListCellRenderer { @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) { Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus); if (c instanceof JLabel l) { l.setBorder(new EmptyBorder(5, 10, 5, 10)); l.setFont(UI_DEFAULT_FONT); } return c; } }
        record PotentialBridge(KifList sourceFact, KifList ruleAntecedent, KifList ruleForm, KifList proposedBridge) { @Override public boolean equals(Object o) { return o instanceof PotentialBridge pb && proposedBridge.equals(pb.proposedBridge); } @Override public int hashCode() { return proposedBridge.hashCode(); } } // Equality/hash on proposed bridge for distinct()

        // --- Dialog for Mismatch Resolution ---
        static class MismatchResolutionDialog extends JDialog {
            private final ProbabilisticKifReasoner reasoner; private final String sourceNoteId; private final List<PotentialBridge> mismatches; private final List<JCheckBox> checkBoxes = new ArrayList<>(); private final Map<JButton, Integer> llmButtonIndex = new HashMap<>(); // Map button to its index

            MismatchResolutionDialog(Frame owner, List<PotentialBridge> mismatches, ProbabilisticKifReasoner reasoner, String sourceNoteId) {
                super(owner, "Resolve Potential Mismatches", true); this.mismatches = mismatches; this.reasoner = reasoner; this.sourceNoteId = sourceNoteId;
                setSize(800, 600); setLocationRelativeTo(owner); setLayout(new BorderLayout(10, 10));
                JPanel mainPanel = new JPanel(); mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS)); mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
                IntStream.range(0, mismatches.size()).forEach(i -> { mainPanel.add(createMismatchPanel(mismatches.get(i), i)); mainPanel.add(Box.createRigidArea(new Dimension(0, 10))); });
                JScrollPane scrollPane = new JScrollPane(mainPanel); scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED); scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); JButton assertButton = new JButton("Assert Selected"); JButton cancelButton = new JButton("Cancel"); assertButton.setFont(UI_DEFAULT_FONT); cancelButton.setFont(UI_DEFAULT_FONT);
                assertButton.addActionListener(this::assertSelectedBridges); cancelButton.addActionListener(e -> dispose()); buttonPanel.add(cancelButton); buttonPanel.add(assertButton);
                add(scrollPane, BorderLayout.CENTER); add(buttonPanel, BorderLayout.SOUTH);
            }
            private JPanel createMismatchPanel(PotentialBridge bridge, int index) {
                JPanel panel = new JPanel(new BorderLayout(5, 5)); panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.GRAY), new EmptyBorder(5, 5, 5, 5)));
                JPanel textPanel = new JPanel(); textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
                JLabel sourceLabel = new JLabel("Source Fact: " + bridge.sourceFact().toKifString()); JLabel ruleLabel = new JLabel("Rule Antecedent: " + bridge.ruleAntecedent().toKifString()); JLabel proposedLabel = new JLabel("Proposed Bridge: " + bridge.proposedBridge().toKifString());
                sourceLabel.setFont(UI_SMALL_FONT); ruleLabel.setFont(UI_SMALL_FONT); proposedLabel.setFont(UI_SMALL_FONT); proposedLabel.setForeground(Color.BLUE);
                textPanel.add(sourceLabel); textPanel.add(ruleLabel); textPanel.add(Box.createRigidArea(new Dimension(0, 5))); textPanel.add(proposedLabel);
                JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT)); JCheckBox checkBox = new JCheckBox("Assert this bridge?"); checkBox.setFont(UI_DEFAULT_FONT); checkBoxes.add(checkBox);
                JButton llmButton = new JButton("Ask LLM"); llmButton.setFont(UI_SMALL_FONT); llmButtonIndex.put(llmButton, index); // Store index with button
                llmButton.addActionListener(this::handleLlmButtonClick); // Use central handler
                actionPanel.add(checkBox); actionPanel.add(llmButton); panel.add(textPanel, BorderLayout.CENTER); panel.add(actionPanel, BorderLayout.SOUTH); return panel;
            }
            private void handleLlmButtonClick(ActionEvent e) { // Get button and index
                 JButton sourceButton = (JButton) e.getSource(); int index = llmButtonIndex.get(sourceButton); askLlmForBridge(mismatches.get(index), checkBoxes.get(index), sourceButton);
            }
            private void askLlmForBridge(PotentialBridge bridge, JCheckBox associatedCheckbox, JButton sourceButton) {
                String prompt = String.format("""
                Given the following KIF assertions:
                1. %s (derived from user note)
                2. A rule requires a fact matching the pattern: %s
                Could assertion 1 imply the following specific fact, needed by the rule?
                Proposed Fact: %s
                Answer ONLY with the proposed KIF fact if it is a valid and likely inference based on common sense or SUMO ontology, otherwise output NOTHING.
                Do not include explanations or markdown. KIF Assertion (or nothing):""",
                bridge.sourceFact().toKifString(), bridge.ruleAntecedent().toKifString(), bridge.proposedBridge().toKifString());

                sourceButton.setEnabled(false); associatedCheckbox.setEnabled(false); associatedCheckbox.setText("Asking LLM...");
                reasoner.getKifFromLlmAsync(prompt, sourceNoteId + "-bridge-" + System.currentTimeMillis())
                    .thenAcceptAsync(llmResult -> {
                        boolean confirmed = false;
                        if (llmResult != null && !llmResult.isBlank()) try { KifTerm parsedResult = KifParser.parseKif(llmResult).getFirst(); if (parsedResult instanceof KifList resultFact && resultFact.equals(bridge.proposedBridge())) { associatedCheckbox.setSelected(true); associatedCheckbox.setText("Assert (LLM Confirmed)"); confirmed = true; } }
                                                         catch (ParseException | NoSuchElementException ex) { System.err.println("LLM bridge response parse error: " + ex.getMessage()); }
                        if (!confirmed) associatedCheckbox.setText("Assert this bridge? (LLM Denied/Error)");
                    }, SwingUtilities::invokeLater)
                    .whenCompleteAsync((res, err) -> { associatedCheckbox.setEnabled(true); sourceButton.setEnabled(true); // Re-enable both
                        if (err != null) { associatedCheckbox.setText("Assert this bridge? (LLM Request Failed)"); System.err.println("LLM bridge request failed: " + err.getMessage()); }
                    }, SwingUtilities::invokeLater);
            }
            private void assertSelectedBridges(ActionEvent e) {
                int count = 0;
                for (int i = 0; i < checkBoxes.size(); i++) {
                    if (checkBoxes.get(i).isSelected()) { PotentialBridge bridge = mismatches.get(i); reasoner.submitMessage(new AssertMessage(1.0, bridge.proposedBridge().toKifString(), "UI-Bridge", sourceNoteId)); count++; }
                } JOptionPane.showMessageDialog(this, "Asserted " + count + " bridging facts.", "Resolution Complete", JOptionPane.INFORMATION_MESSAGE); dispose();
            }
        }
    }
} // End of ProbabilisticKifReasoner class