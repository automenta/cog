package dumb.cognote1;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.*;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A probabilistic, online, iterative beam-search based SUMO KIF reasoner
 * driven by dynamic knowledge base changes via WebSockets, with callback support,
 * integrated with a Swing UI for Note->KIF distillation via LLM.
 */
public class ProbabilisticKifReasoner extends WebSocketServer {

    // --- Configuration ---
    private final int beamWidth;
    private final int maxKbSize;
    private final boolean broadcastInputAssertions;
    private final String llmApiUrl; // e.g., "http://localhost:11434/api/chat"
    private final String llmModel; // e.g., "llama3" or specific fine-tune
    // --- Knowledge Base State ---
    private final ConcurrentMap<String, ConcurrentMap<KifList, Assertion>> factIndex = new ConcurrentHashMap<>(); // Predicate -> Fact -> Assertion
    private final ConcurrentMap<String, Assertion> assertionsById = new ConcurrentHashMap<>(); // ID -> Assertion
    private final Set<Rule> rules = ConcurrentHashMap.newKeySet(); // Rule definitions
    private final AtomicLong idCounter = new AtomicLong(0); // For unique IDs
    private final Set<KifList> kbContentSnapshot = ConcurrentHashMap.newKeySet(); // Fast existence check for facts
    private final PriorityBlockingQueue<Assertion> kbPriorityQueue = new PriorityBlockingQueue<>(); // For capacity management
    private final ConcurrentMap<String, Set<String>> noteIdToAssertionIds = new ConcurrentHashMap<>(); // Track assertions per note
    // --- Input Queue ---
    private final PriorityBlockingQueue<InputMessage> inputQueue = new PriorityBlockingQueue<>();
    // --- Reasoning Engine State ---
    private final PriorityQueue<PotentialAssertion> beam = new PriorityQueue<>(); // Candidates for KB addition
    private final Set<KifList> beamContentSnapshot = ConcurrentHashMap.newKeySet(); // Fast check for beam duplicates
    private final List<CallbackRegistration> callbackRegistrations = new CopyOnWriteArrayList<>();
    // --- Main Reasoner Thread & Control ---
    private final ExecutorService reasonerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ReasonerThread"));
    private final SwingUI swingUI; // Reference to the UI
    // --- LLM Interaction ---
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "LLMThread")); // Dedicated thread for blocking LLM calls
    private volatile boolean running = true;

    // --- Constructor ---
    public ProbabilisticKifReasoner(int port, int beamWidth, int maxKbSize, boolean broadcastInput, String llmUrl, String llmModel, SwingUI ui) {
        super(new InetSocketAddress(port));
        this.beamWidth = beamWidth > 0 ? beamWidth : 10;
        this.maxKbSize = maxKbSize > 0 ? maxKbSize : 1000;
        this.broadcastInputAssertions = broadcastInput;
        this.llmApiUrl = Objects.requireNonNullElse(llmUrl, "http://localhost:11434/api/chat");
        this.llmModel = Objects.requireNonNullElse(llmModel, "llamablit"); // Provide a default model
        this.swingUI = ui; // Link to UI
        System.out.println("Reasoner config: Port=" + port + ", Beam=" + this.beamWidth + ", KBSize=" + this.maxKbSize + ", LLM_URL=" + this.llmApiUrl + ", LLM_Model=" + this.llmModel);
        registerInternalCallback("assert-internal-broadcast", (type, assertion, bindings) -> broadcastMessage(type, assertion)); // Internal broadcaster
    }

    // --- Main Method ---
    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {

            int port = 8887, beamWidth = 10, maxKbSize = 1000;
            String rulesFile = null, llmUrl = null, llmModel = null;
            var broadcastInput = false;

            // Basic command-line parsing
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
                } catch (Exception e) {
                    System.err.println("Error parsing args: " + e.getMessage());
                    printUsageAndExit();
                }
            }


            ProbabilisticKifReasoner server = null;
            SwingUI ui = null;
            try {
                // Create UI instance first, pass it to reasoner constructor
                ui = new SwingUI(null); // Temp null, set later
                server = new ProbabilisticKifReasoner(port, beamWidth, maxKbSize, broadcastInput, llmUrl, llmModel, ui);
                ui.reasoner = server; // Link UI back to reasoner

                final var finalServer = server; // For shutdown hook

                // Add shutdown hook for graceful termination
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (finalServer != null) finalServer.stopReasoner();
                }));

                // Load initial rules if specified (after server instance created)
                if (rulesFile != null) server.loadRulesFromFile(rulesFile);
                else System.out.println("No initial rules file specified.");

                // Start the server and reasoner loop (in background threads)
                server.startReasoner();

                // Make the UI visible
                ui.setVisible(true);

            } catch (IllegalArgumentException e) {
                System.err.println("Configuration Error: " + e.getMessage());
                if (ui != null) ui.dispose();
                System.exit(1);
            } catch (IOException | ParseException e) {
                System.err.println("Error loading rules file: " + e.getMessage());
                if (ui != null) ui.dispose();
                System.exit(1);
            } catch (Exception e) {
                System.err.println("Failed to initialize/start: " + e);
                e.printStackTrace();
                if (ui != null) ui.dispose();
                System.exit(1);
            }
        });
    }

    private static void printUsageAndExit() {
        System.err.println("Usage: java ProbabilisticKifReasoner [-p <port>] [-b <beamWidth>] [-k <maxKbSize>] [-r <rulesFile>] [--llm-url <url>] [--llm-model <model>] [--broadcast-input]");
        System.exit(1);
    }

    // Helper for callback registration
    private void registerInternalCallback(String patternKif, KifCallback callback) {
        try {
            var pattern = KifParser.parseKif(patternKif).getFirst(); // Assume single pattern list
            callbackRegistrations.add(new CallbackRegistration(pattern, callback));
            System.out.println("Registered internal callback for: " + pattern.toKifString());
        } catch (ParseException | ClassCastException | IndexOutOfBoundsException e) {
            System.err.println("Failed to parse/register internal callback pattern: " + patternKif + " - " + e);
        }
    }

    private void broadcastMessage(String type, Assertion assertion) {
        var kifString = assertion.toKifString();
        var prefix = switch (type) {
            case "assert-added" -> String.format("assert-derived %.4f ", assertion.probability());
            case "assert-input" -> String.format("assert-input %.4f ", assertion.probability());
            case "assert-retracted" -> String.format("retract %s ", assertion.id());
            case "evict" -> String.format("evict %s ", assertion.id());
            default -> type + " ";
        };
        var message = prefix + (type.equals("assert-retracted") || type.equals("evict") ? "" : kifString);
        // Check WebSocket state before broadcasting
        if (this.isReady())
            broadcast(message);
        else System.out.println("WS broadcast skipped (server not ready): " + message);
    }

    /**
     * ?
     */
    private boolean isReady() {
        return true;
    }

    // --- Public Control Methods ---
    public void loadRulesFromFile(String filename) throws IOException, ParseException {
        System.out.println("Loading rules from: " + filename);
        var path = Paths.get(filename);
        long ruleCount = 0;
        var kifBuffer = new StringBuilder();
        var parenDepth = 0;
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var commentStart = line.indexOf(';');
                if (commentStart != -1) line = line.substring(0, commentStart);
                line = line.trim();
                if (line.isEmpty()) continue;
                parenDepth += line.chars().filter(c -> c == '(').count() - line.chars().filter(c -> c == ')').count();
                kifBuffer.append(line).append(" ");
                if (parenDepth == 0 && !kifBuffer.isEmpty()) {
                    var kifText = kifBuffer.toString().trim();
                    kifBuffer.setLength(0);
                    if (!kifText.isEmpty()) {
                        try {
                            inputQueue.put(new RuleMessage(kifText, "file:" + filename));
                            ruleCount++;
                        } catch (Exception e) {
                            System.err.println("Error queuing rule from file: " + kifText + " - " + e);
                        }
                    }
                } else if (parenDepth < 0) {
                    System.err.println("Mismatched parentheses near: " + line);
                    parenDepth = 0;
                    kifBuffer.setLength(0);
                }
            }
            if (parenDepth != 0) System.err.println("Warning: Unbalanced parentheses at end of file: " + filename);
        }
        System.out.println("Queued " + ruleCount + " potential rules/expressions.");
    }

    public void submitMessage(InputMessage message) {
        inputQueue.put(message);
    }

    public void startReasoner() {
        running = true;
        reasonerExecutor.submit(this::reasonerLoop);
        try {
            start();
        } catch (Exception e) {
            System.err.println("WebSocket server failed to start: " + e.getMessage()); /* Continue without WS? */
        }
        System.out.println("WebSocket server thread started on port " + getPort());
    }

    public void stopReasoner() {
        System.out.println("Stopping reasoner...");
        running = false;
        reasonerExecutor.shutdown();
        llmExecutor.shutdown(); // Shutdown LLM executor too
        try {
            stop(1000); // Stop WebSocket server
            if (!reasonerExecutor.awaitTermination(5, TimeUnit.SECONDS)) reasonerExecutor.shutdownNow();
            if (!llmExecutor.awaitTermination(5, TimeUnit.SECONDS)) llmExecutor.shutdownNow();
        } catch (InterruptedException e) {
            reasonerExecutor.shutdownNow();
            llmExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Reasoner stopped.");
    }

    // --- WebSocket Methods ---
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("WS Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("WS Client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("WS Error from " + (conn != null ? conn.getRemoteSocketAddress() : "server") + ": " + ex);
    }

    @Override
    public void onStart() {
        System.out.println("Reasoner WebSocket listener active.");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        var trimmed = message.trim();
        var sourceId = conn.getRemoteSocketAddress().toString();
        try {
            if (trimmed.startsWith("(")) { // KIF list processing
                var terms = KifParser.parseKif(trimmed);
                if (terms.isEmpty()) throw new ParseException("Empty KIF message");
                if (terms.size() > 1)
                    System.err.println("Warning: Multiple top-level KIF expressions received in one message, processing only the first.");

                var term = terms.getFirst();
                if (term instanceof KifList list) {
                    var op = list.getOperator();
                    if (op.isPresent()) {
                        switch (op.get()) {
                            case "retract-id":
                                if (list.size() == 2 && list.get(1) instanceof KifConstant id)
                                    submitMessage(new RetractByIdMessage(id.value(), sourceId));
                                else throw new ParseException("Invalid retract-id format");
                                break;
                            case "retract-rule":
                                if (list.size() == 2 && list.get(1) instanceof KifList ruleForm)
                                    submitMessage(new RetractRuleMessage(ruleForm.toKifString(), sourceId));
                                else throw new ParseException("Invalid retract-rule format");
                                break;
                            case "=>":
                            case "<=>":
                                submitMessage(new RuleMessage(trimmed, sourceId));
                                break;
                            default:
                                submitMessage(new AssertMessage(1.0, trimmed, sourceId, null));
                                break; // Default P=1.0, no note source
                        }
                    } else
                        submitMessage(new AssertMessage(1.0, trimmed, sourceId, null)); // List doesn't start with constant op
                } else throw new ParseException("Top-level message must be a KIF list for commands/assertions");
            } else { // Assume "priority (kif-list)"
                var m = Pattern.compile("^([0-9.]+)\\s*(\\(.*\\))$", Pattern.DOTALL).matcher(trimmed);
                if (m.matches()) {
                    var priority = Double.parseDouble(m.group(1));
                    var kifStr = m.group(2);
                    if (priority < 0.0 || priority > 1.0)
                        throw new NumberFormatException("Probability out of range [0,1]");
                    submitMessage(new AssertMessage(priority, kifStr, sourceId, null));
                } else throw new ParseException("Invalid format. Expected '(kif)' or 'priority (kif)'");
            }
        } catch (ParseException | NumberFormatException | ClassCastException e) {
            System.err.println("WS Message Error from " + sourceId + ": " + e.getMessage() + " | Original: " + message);
        } catch (Exception e) {
            System.err.println("Unexpected WS message processing error: " + e);
            e.printStackTrace();
        }
    }

    // --- Reasoner Loop ---
    private void reasonerLoop() {
        System.out.println("Reasoner loop started.");
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                var msg = inputQueue.poll(100, TimeUnit.MILLISECONDS); // Poll with timeout
                if (msg != null) processInputMessage(msg);
                if (!beam.isEmpty()) processBeamStep();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
                System.out.println("Reasoner loop interrupted.");
            } catch (Exception e) {
                System.err.println("Error in reasoner loop: " + e);
                e.printStackTrace();
            }
        }
        System.out.println("Reasoner loop finished.");
    }

    // --- Input Message Processing ---
    private void processInputMessage(InputMessage msg) {
        try {
            switch (msg) {
                case AssertMessage am -> processAssertionInput(am);
                case RetractByIdMessage rm -> processRetractionByIdInput(rm);
                case RetractByNoteIdMessage rnm -> processRetractionByNoteIdInput(rnm);
                case RetractRuleMessage rrm -> processRetractionRuleInput(rrm);
                case RuleMessage ruleMsg -> processRuleInput(ruleMsg);
                case RegisterCallbackMessage rcm ->
                        callbackRegistrations.add(new CallbackRegistration(rcm.pattern(), rcm.callback())); // Direct add ok here
            }
        } catch (ParseException e) {
            System.err.println("Error processing queued message (" + msg.getClass().getSimpleName() + "): " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error processing queued message (" + msg.getClass().getSimpleName() + "): " + e);
            e.printStackTrace();
        }
    }

    private void processAssertionInput(AssertMessage am) throws ParseException {
        // Parse potentially multiple KIF expressions from the input string
        var terms = KifParser.parseKif(am.kifString());
        for (var term : terms) {
            if (term instanceof KifList fact) {
                if (fact.terms().isEmpty() || fact.containsVariable()) {
                    System.err.println("Assertion ignored (empty, or contains variables): " + fact.toKifString());
                    continue;
                }
                var pa = new PotentialAssertion(fact, am.probability(), Collections.emptySet(), am.sourceNoteId());
                addToBeam(pa);
                if (broadcastInputAssertions) {
                    // Trigger broadcast via callback mechanism, create temporary assertion
                    var tempId = "input-" + idCounter.incrementAndGet();
                    var inputAssertion = new Assertion(tempId, fact, am.probability(), System.currentTimeMillis(), am.sourceNoteId(), Collections.emptySet());
                    invokeCallbacks("assert-input", inputAssertion); // Uses registered broadcaster
                }
            } else System.err.println("Assertion input ignored (not a KIF list): " + term.toKifString());
        }
    }

    private void processRetractionByIdInput(RetractByIdMessage rm) {
        retractAssertion(rm.assertionId());
    }

    private void processRetractionByNoteIdInput(RetractByNoteIdMessage rnm) {
        var idsToRetract = noteIdToAssertionIds.remove(rnm.noteId()); // Remove mapping
        if (idsToRetract != null) {
            System.out.println("Retracting " + idsToRetract.size() + " assertions for note: " + rnm.noteId());
            idsToRetract.forEach(this::retractAssertion); // Retract each one
        }
    }

    private void processRetractionRuleInput(RetractRuleMessage rrm) throws ParseException {
        var term = KifParser.parseKif(rrm.ruleKif()).getFirst(); // Assume single rule KIF
        if (term instanceof KifList ruleForm) {
            var removed = rules.removeIf(rule -> rule.ruleForm().equals(ruleForm));
            // Handle <=> removal
            if (!removed && ruleForm.getOperator().filter("<=>"::equals).isPresent() && ruleForm.size() == 3) {
                var fwd = new KifList(new KifConstant("=>"), ruleForm.get(1), ruleForm.get(2));
                var bwd = new KifList(new KifConstant("=>"), ruleForm.get(2), ruleForm.get(1));
                removed = rules.removeIf(r -> r.ruleForm().equals(fwd)) | rules.removeIf(r -> r.ruleForm().equals(bwd));
            }
            if (removed) System.out.println("Retracted rule matching: " + ruleForm.toKifString());
            else System.out.println("Retract rule: No rule found matching: " + ruleForm.toKifString());
        } else System.err.println("Retract rule: Input is not a valid rule KIF list: " + rrm.ruleKif());
    }

    private void processRuleInput(RuleMessage ruleMsg) throws ParseException {
        var term = KifParser.parseKif(ruleMsg.kifString()).getFirst(); // Assume single rule KIF
        if (term instanceof KifList list) {
            var op = list.getOperator().orElse("");
            if (list.size() == 3 && (op.equals("=>") || op.equals("<=>"))) {
                if (list.get(1) instanceof KifList ant && list.get(2) instanceof KifList con) {
                    addRuleInternal(list, ant, con);
                    if (op.equals("<=>")) addRuleInternal(new KifList(new KifConstant("=>"), con, ant), con, ant);
                } else System.err.println("Invalid rule structure (ant/con not lists): " + ruleMsg.kifString());
            } else
                System.err.println("Invalid rule format (expected '(=> ant con)' or '(<=> ant con)'): " + ruleMsg.kifString());
        } else System.err.println("Rule input ignored (not a KIF list): " + ruleMsg.kifString());
    }

    private void addRuleInternal(KifList ruleForm, KifList antecedent, KifList consequent) {
        var ruleId = "rule-" + idCounter.incrementAndGet();
        var newRule = new Rule(ruleId, ruleForm, antecedent, consequent);
        if (rules.add(newRule)) System.out.println("Added rule: " + ruleId + " " + newRule.ruleForm().toKifString());
        else System.out.println("Rule ignored (duplicate): " + newRule.ruleForm().toKifString());
    }

    // --- Core Logic: Beam Search Step ---
    private void processBeamStep() {
        List<PotentialAssertion> candidates = new ArrayList<>();
        synchronized (beam) { // Lock beam for poll/snapshot consistency
            var count = 0;
            while (count < beamWidth && !beam.isEmpty()) {
                var pa = beam.poll();
                if (pa == null) continue;
                beamContentSnapshot.remove(pa.fact());
                if (!kbContentSnapshot.contains(pa.fact())) {
                    candidates.add(pa);
                    count++;
                }
            }
        } // Unlock beam
        candidates.forEach(pa -> { // Process outside lock
            var newAssertion = addAssertionToKb(pa.fact(), pa.probability(), pa.sourceNoteId(), pa.supportingAssertionIds());
            if (newAssertion != null) deriveFrom(newAssertion);
        });
    }

    // --- Core Logic: Forward Chaining / Derivation ---
    private void deriveFrom(Assertion newAssertion) {
        rules.forEach(rule -> {
            var antClauses = rule.getAntecedentClauses();
            if (antClauses.isEmpty()) return;
            IntStream.range(0, antClauses.size()).forEach(i -> {
                var bindings = Unifier.unify(antClauses.get(i), newAssertion.fact(), Map.of());
                if (bindings != null) {
                    var remaining = IntStream.range(0, antClauses.size()).filter(idx -> idx != i).mapToObj(antClauses::get).toList();
                    findMatches(remaining, bindings, Set.of(newAssertion.id()), newAssertion.sourceNoteId()) // Pass noteId
                            .forEach(match -> {
                                var consequentTerm = Unifier.substitute(rule.consequent(), match.bindings());
                                if (consequentTerm instanceof KifList derived && !derived.containsVariable()) {
                                    var prob = calculateDerivedProbability(match.supportingAssertionIds());
                                    // Inherit sourceNoteId if all supporters share the same *non-null* sourceNoteId
                                    var inheritedNoteId = findCommonSourceNodeId(match.supportingAssertionIds());
                                    var pa = new PotentialAssertion(derived, prob, match.supportingAssertionIds(), inheritedNoteId);
                                    addToBeam(pa);
                                }
                            });
                }
            });
        });
    }

    // Helper to find common sourceNoteId among supporters
    private String findCommonSourceNodeId(Set<String> supportIds) {
        if (supportIds.isEmpty()) return null;
        String commonId = null;
        var first = true;
        for (var id : supportIds) {
            var assertion = assertionsById.get(id);
            if (assertion == null || assertion.sourceNoteId() == null)
                return null; // If any is null or missing, no common source
            if (first) {
                commonId = assertion.sourceNoteId();
                first = false;
            } else if (!commonId.equals(assertion.sourceNoteId())) return null; // Mismatch found
        }
        return commonId; // All non-null and matched
    }

    private Stream<MatchResult> findMatches(List<KifList> remainingClauses, Map<KifVariable, KifTerm> bindings, Set<String> supportIds, String currentNoteId) {
        if (remainingClauses.isEmpty()) return Stream.of(new MatchResult(bindings, supportIds)); // Base case
        var clause = remainingClauses.getFirst();
        var nextRemaining = remainingClauses.subList(1, remainingClauses.size());
        var substTerm = Unifier.substitute(clause, bindings);
        if (!(substTerm instanceof KifList substClause)) return Stream.empty();

        if (substClause.containsVariable()) { // Clause needs KB lookup
            return findCandidateAssertions(substClause).flatMap(candidate -> {
                var newBindings = Unifier.unify(substClause, candidate.fact(), bindings);
                if (newBindings != null) {
                    Set<String> nextSupport = new HashSet<>(supportIds);
                    nextSupport.add(candidate.id());
                    return findMatches(nextRemaining, newBindings, nextSupport, candidate.sourceNoteId()); // Pass candidate's noteId? (or keep original?)
                }
                return Stream.empty();
            });
        } else { // Ground clause, check existence
            return findCandidateAssertions(substClause) // Find matching facts in KB
                    .filter(a -> a.fact().equals(substClause)) // Double check equality
                    .flatMap(match -> {
                        Set<String> nextSupport = new HashSet<>(supportIds);
                        nextSupport.add(match.id());
                        return findMatches(nextRemaining, bindings, nextSupport, match.sourceNoteId()); // Pass noteId from matched fact
                    });
        }
    }

    private Stream<Assertion> findCandidateAssertions(KifList clause) {
        return clause.getOperator().map(factIndex::get).map(Map::values).map(Collection::stream)
                .orElseGet(() -> assertionsById.values().stream()).filter(Objects::nonNull);
    }

    private double calculateDerivedProbability(Set<String> ids) {
        if (ids.isEmpty()) return 0.0;
        return ids.stream().map(assertionsById::get).filter(Objects::nonNull)
                .mapToDouble(Assertion::probability).min().orElse(0.0);
    }

    // --- Knowledge Base Management ---
    private Assertion addAssertionToKb(KifList fact, double probability, String sourceNoteId, Set<String> supportingIds) {
        if (kbContentSnapshot.contains(fact)) return null; // Already present
        enforceKbCapacity(); // Evict first if full

        var id = "fact-" + idCounter.incrementAndGet();
        var timestamp = System.currentTimeMillis();
        var newAssertion = new Assertion(id, fact, probability, timestamp, sourceNoteId, Collections.unmodifiableSet(supportingIds));

        assertionsById.put(id, newAssertion); // Main storage
        fact.getOperator().ifPresent(p -> factIndex.computeIfAbsent(p, k -> new ConcurrentHashMap<>()).put(fact, newAssertion)); // Index
        kbContentSnapshot.add(fact); // Existence check
        kbPriorityQueue.put(newAssertion); // Eviction queue

        // Track assertion IDs per note source
        if (sourceNoteId != null)
            noteIdToAssertionIds.computeIfAbsent(sourceNoteId, k -> ConcurrentHashMap.newKeySet()).add(id);

        // System.out.printf("KB Add [%s]: P=%.4f %s (Src:%s Size:%d/%d)\n", id, probability, fact.toKifString(), sourceNoteId, assertionsById.size(), maxKbSize);
        invokeCallbacks("assert-added", newAssertion); // Trigger callbacks (includes broadcasting)
        return newAssertion;
    }

    private void retractAssertion(String id) {
        var removed = assertionsById.remove(id);
        if (removed != null) {
            kbContentSnapshot.remove(removed.fact());
            kbPriorityQueue.remove(removed);
            removed.fact().getOperator().ifPresent(p -> factIndex.computeIfPresent(p, (k, v) -> {
                v.remove(removed.fact());
                return v.isEmpty() ? null : v;
            }));
            if (removed.sourceNoteId() != null)
                noteIdToAssertionIds.computeIfPresent(removed.sourceNoteId(), (k, v) -> {
                    v.remove(id);
                    return v.isEmpty() ? null : v;
                });
            // System.out.println("KB Retract [" + id + "]: " + removed.toKifString());
            invokeCallbacks("assert-retracted", removed);
        }
    }

    private void enforceKbCapacity() {
        while (assertionsById.size() >= maxKbSize && !kbPriorityQueue.isEmpty()) {
            var toEvict = kbPriorityQueue.poll(); // Lowest priority
            if (toEvict != null) {
                var actuallyRemoved = assertionsById.get(toEvict.id()); // Check if still present
                if (actuallyRemoved != null) {
                    retractAssertion(actuallyRemoved.id()); // Use main retraction logic
                    System.out.println("KB Evict (Low Prio) [" + actuallyRemoved.id() + "]");
                    invokeCallbacks("evict", actuallyRemoved); // Specific callback type for eviction
                }
            }
        }
    }

    private void addToBeam(PotentialAssertion pa) {
        if (kbContentSnapshot.contains(pa.fact()) || beamContentSnapshot.contains(pa.fact())) return;
        synchronized (beam) { // Lock for final check and add
            if (!kbContentSnapshot.contains(pa.fact()) && !beamContentSnapshot.contains(pa.fact())) {
                if (beam.offer(pa)) beamContentSnapshot.add(pa.fact());
            }
        }
    }

    // --- Callback Invocation ---
    private void invokeCallbacks(String type, Assertion assertion) {
        var currentRegs = List.copyOf(callbackRegistrations); // Thread-safe iteration
        currentRegs.forEach(reg -> {
            var bindings = Unifier.unify(reg.pattern(), assertion.fact(), Map.of());
            if (bindings != null) { // Pattern matches
                try {
                    reg.callback().onMatch(type, assertion, bindings);
                } catch (Exception e) {
                    System.err.println("Error in KIF callback: " + e);
                    e.printStackTrace();
                }
            }
        });
        // Also update UI if relevant assertion
        if (swingUI != null && swingUI.isDisplayable()) { // Check if UI exists and is visible
            swingUI.handleReasonerCallback(type, assertion);
        }
    }

    // --- LLM Interaction Method ---
    public CompletableFuture<String> getKifFromLlmAsync(String noteText, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            var prompt = "Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax). Output ONLY the KIF assertions, each on a new line, enclosed in parentheses. Do not include explanations or markdown formatting. Example: (instance Fluffy Cat)\n\nNote:\n\"" + noteText + "\"\n\nKIF Assertions:";
            var payload = new JSONObject()
                    .put("model", this.llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false); // Ensure single response

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(this.llmApiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var jsonResponse = new JSONObject(response.body());
                    // Check common OpenAI/Ollama structures
                    var kifResult = "";
                    if (jsonResponse.has("choices")) { // OpenAI standard
                        var choices = jsonResponse.getJSONArray("choices");
                        if (!choices.isEmpty())
                            kifResult = choices.getJSONObject(0).getJSONObject("message").getString("content");
                    } else if (jsonResponse.has("message")) { // Ollama direct chat response
                        kifResult = jsonResponse.getJSONObject("message").getString("content");
                    } else if (jsonResponse.has("response")) { // Ollama generate response
                        kifResult = jsonResponse.getString("response");
                    } else {
                        throw new IOException("Unexpected LLM response format: " + response.body().substring(0, Math.min(response.body().length(), 100)));
                    }

                    // Basic cleanup - remove potential markdown code blocks
                    kifResult = kifResult.replaceAll("```kif\n", "").replaceAll("```lisp\n", "").replaceAll("```\n", "").trim();
                    System.out.println("LLM (" + noteId + ") KIF Output:\n" + kifResult); // Log LLM output
                    return kifResult;
                } else {
                    throw new IOException("LLM API request failed: " + response.statusCode() + " " + response.body());
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("LLM API interaction failed for note " + noteId + ": " + e.getMessage());
                Thread.currentThread().interrupt(); // Restore interrupt status if needed
                throw new CompletionException(e); // Wrap exception for CompletableFuture
            } catch (Exception e) { // Catch JSON parsing errors etc.
                System.err.println("LLM response processing failed for note " + noteId + ": " + e.getMessage());
                throw new CompletionException(e);
            }
        }, llmExecutor); // Run in dedicated LLM thread pool
    }

    // --- KIF Data Structures ---
    sealed interface KifTerm permits KifConstant, KifVariable, KifList {
        default String toKifString() {
            var sb = new StringBuilder();
            writeKifString(sb);
            return sb.toString();
        }

        void writeKifString(StringBuilder sb);

        default boolean containsVariable() {
            return false;
        }
    }

    sealed interface InputMessage extends Comparable<InputMessage> {
        double getPriority();

        String getSourceId();
    }

    // --- Callback API ---
    @FunctionalInterface
    interface KifCallback {
        void onMatch(String type, Assertion assertion, Map<KifVariable, KifTerm> bindings);
    }

    record KifConstant(String value) implements KifTerm {
        @Override
        public void writeKifString(StringBuilder sb) {
            var needsQuotes = value.chars().anyMatch(c -> Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == ';') || value.isEmpty();
            if (needsQuotes) sb.append('"').append(value.replace("\"", "\\\"")).append('"');
            else sb.append(value);
        }
    }

    record KifVariable(String name) implements KifTerm {
        @Override
        public void writeKifString(StringBuilder sb) {
            sb.append(name);
        }

        @Override
        public boolean containsVariable() {
            return true;
        }
    }

    record KifList(List<KifTerm> terms) implements KifTerm {
        KifList(KifTerm... terms) {
            this(List.of(terms));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof KifList other && terms.equals(other.terms);
        }

        @Override
        public int hashCode() {
            return terms.hashCode();
        }

        @Override
        public void writeKifString(StringBuilder sb) {
            sb.append('(');
            IntStream.range(0, terms.size()).forEach(i -> {
                terms.get(i).writeKifString(sb);
                if (i < terms.size() - 1) sb.append(' ');
            });
            sb.append(')');
        }

        KifTerm get(int index) {
            return terms.get(index);
        }

        int size() {
            return terms.size();
        }

        Stream<KifTerm> stream() {
            return terms.stream();
        }

        Optional<String> getOperator() {
            return terms.isEmpty() || !(terms.getFirst() instanceof KifConstant c) ? Optional.empty() : Optional.of(c.value());
        }

        @Override
        public boolean containsVariable() {
            return terms.stream().anyMatch(KifTerm::containsVariable);
        }
    }

    // --- Knowledge Representation ---
    // Assertion: Represents a fact in the KB. Includes provenance.
    record Assertion(String id, KifList fact, double probability, long timestamp,
                     String sourceNoteId, // ID of the Note object that originated this (if any)
                     Set<String> directSupportingIds // IDs of assertions directly used by the rule to derive this
    ) implements Comparable<Assertion> {
        @Override
        public int compareTo(Assertion other) {
            return Double.compare(this.probability, other.probability);
        } // Min-heap for eviction

        String toKifString() {
            return fact.toKifString();
        }
    }

    // Rule: Represents an implication.
    record Rule(String id, KifList ruleForm, KifList antecedent, KifList consequent) {
        List<KifList> getAntecedentClauses() {
            if (antecedent.size() > 1 && antecedent.getOperator().filter("and"::equals).isPresent()) {
                return antecedent.terms().subList(1, antecedent.size()).stream()
                        .filter(KifList.class::isInstance).map(KifList.class::cast).toList();
            } else if (antecedent.size() > 0 && antecedent.get(0) instanceof KifConstant) {
                return List.of(antecedent); // Single clause
            }
            return List.of();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Rule other && ruleForm.equals(other.ruleForm);
        }

        @Override
        public int hashCode() {
            return ruleForm.hashCode();
        }
    }

    // --- Parser ---
    static class KifParser {
        // Simplified KIF Parser (Handles basic lists, atoms, variables, strings, comments)
        private final StringReader reader;
        private int lastChar = -2;

        KifParser(String input) {
            this.reader = new StringReader(input.trim());
        }

        static List<KifTerm> parseKif(String input) throws ParseException {
            try {
                return new KifParser(input).parseTopLevel();
            } catch (IOException e) {
                throw new ParseException("Read error: " + e.getMessage());
            }
        }

        List<KifTerm> parseTopLevel() throws IOException, ParseException {
            List<KifTerm> terms = new ArrayList<>();
            while (peek() != -1) {
                consumeWhitespaceAndComments();
                if (peek() == -1) break;
                terms.add(parseTerm());
                consumeWhitespaceAndComments();
            }
            return Collections.unmodifiableList(terms);
        }

        KifTerm parseTerm() throws IOException, ParseException {
            consumeWhitespaceAndComments();
            var c = peek();
            if (c == -1) throw new ParseException("Unexpected end of input while expecting term");
            return switch (c) {
                case '(' -> parseList();
                case '"' -> parseQuotedString();
                default -> parseAtom();
            };
        }

        KifList parseList() throws IOException, ParseException {
            consumeChar('(');
            List<KifTerm> terms = new ArrayList<>();
            while (true) {
                consumeWhitespaceAndComments();
                if (peek() == ')') {
                    consumeChar(')');
                    return new KifList(Collections.unmodifiableList(terms));
                }
                if (peek() == -1) throw new ParseException("Unmatched parenthesis in list");
                terms.add(parseTerm());
            }
        }

        KifTerm parseAtom() throws IOException, ParseException {
            var atom = new StringBuilder();
            var firstChar = peek();
            var isVariable = firstChar == '?';
            if (isVariable) consumeChar(); // Consume '?'

            while (true) {
                var c = peek();
                if (c == -1 || Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == ';') {
                    if (atom.isEmpty() && !isVariable) throw new ParseException("Empty atom encountered");
                    if (isVariable && atom.isEmpty()) throw new ParseException("Empty variable name after '?'");
                    var value = atom.toString();
                    return isVariable ? new KifVariable("?" + value) : new KifConstant(value);
                }
                atom.append((char) consumeChar());
            }
        }

        KifConstant parseQuotedString() throws IOException, ParseException {
            consumeChar('"');
            var sb = new StringBuilder();
            while (true) {
                var c = consumeChar();
                if (c == '"') return new KifConstant(sb.toString());
                if (c == -1) throw new ParseException("Unmatched quote in string literal");
                if (c == '\\') {
                    var next = consumeChar();
                    if (next == -1) throw new ParseException("Unmatched quote after escape character");
                    sb.append((char) next);
                } else {
                    sb.append((char) c);
                }
            }
        }

        int peek() throws IOException {
            if (lastChar == -2) lastChar = reader.read();
            return lastChar;
        }

        int consumeChar() throws IOException {
            var c = peek();
            lastChar = -2;
            return c;
        }

        void consumeChar(char expected) throws IOException, ParseException {
            var c = consumeChar();
            if (c != expected)
                throw new ParseException("Expected '" + expected + "' but found '" + (char) c + "' (code " + c + ")");
        }

        void consumeWhitespaceAndComments() throws IOException {
            while (true) {
                var c = peek();
                if (c == -1) break;
                if (Character.isWhitespace(c)) consumeChar();
                else if (c == ';') { // Line comment
                    while (peek() != '\n' && peek() != '\r' && peek() != -1) consumeChar();
                } else break;
            }
        }
    }

    static class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    record AssertMessage(double probability, String kifString, String sourceId,
                         String sourceNoteId) implements InputMessage { // Added sourceNoteId
        @Override
        public int compareTo(InputMessage other) {
            return Double.compare(other.getPriority(), this.probability);
        } // Descending probability

        @Override
        public double getPriority() {
            return probability;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }
    }

    record RetractByIdMessage(String assertionId, String sourceId) implements InputMessage {
        @Override
        public int compareTo(InputMessage other) {
            return other instanceof RetractByIdMessage || other instanceof RetractRuleMessage || other instanceof RetractByNoteIdMessage ? 0 : -1;
        }

        @Override
        public double getPriority() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }
    }

    record RetractByNoteIdMessage(String noteId, String sourceId) implements InputMessage { // New message type
        @Override
        public int compareTo(InputMessage other) {
            return other instanceof RetractByIdMessage || other instanceof RetractRuleMessage || other instanceof RetractByNoteIdMessage ? 0 : -1;
        }

        @Override
        public double getPriority() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }
    }

    record RetractRuleMessage(String ruleKif, String sourceId) implements InputMessage {
        @Override
        public int compareTo(InputMessage other) {
            return other instanceof RetractByIdMessage || other instanceof RetractRuleMessage || other instanceof RetractByNoteIdMessage ? 0 : -1;
        }

        @Override
        public double getPriority() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }
    }

    record RuleMessage(String kifString, String sourceId) implements InputMessage {
        @Override
        public int compareTo(InputMessage other) {
            return other instanceof RetractByIdMessage || other instanceof RetractRuleMessage || other instanceof RetractByNoteIdMessage ? 1 : (other instanceof RuleMessage ? 0 : -1);
        }

        @Override
        public double getPriority() {
            return Double.MAX_VALUE;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }
    }

    record RegisterCallbackMessage(KifList pattern, KifCallback callback, String sourceId) implements InputMessage {
        @Override
        public int compareTo(InputMessage other) {
            return other instanceof RetractByIdMessage || other instanceof RetractRuleMessage || other instanceof RetractByNoteIdMessage || other instanceof RuleMessage ? 1 : (other instanceof RegisterCallbackMessage ? 0 : -1);
        }

        @Override
        public double getPriority() {
            return Double.MAX_VALUE - 1;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }
    }

    record PotentialAssertion(KifList fact, double probability, Set<String> supportingAssertionIds,
                              String sourceNoteId) // Added sourceNoteId & support info
            implements Comparable<PotentialAssertion> {
        @Override
        public int compareTo(PotentialAssertion other) {
            return Double.compare(other.probability, this.probability);
        } // Descending probability

        @Override
        public boolean equals(Object o) {
            return o instanceof PotentialAssertion pa && fact.equals(pa.fact);
        }

        @Override
        public int hashCode() {
            return fact.hashCode();
        }
    }

    record CallbackRegistration(KifTerm pattern, KifCallback callback) {
    }

    record MatchResult(Map<KifVariable, KifTerm> bindings, Set<String> supportingAssertionIds) {
    }

    // --- Unification Logic ---
    static class Unifier {
        static Map<KifVariable, KifTerm> unify(KifTerm x, KifTerm y, Map<KifVariable, KifTerm> bindings) {
            if (bindings == null) return null;
            KifTerm xSubst = fullySubstitute(x, bindings), ySubst = fullySubstitute(y, bindings);
            if (xSubst.equals(ySubst)) return bindings;
            if (xSubst instanceof KifVariable v) return bindVariable(v, ySubst, bindings);
            if (ySubst instanceof KifVariable v) return bindVariable(v, xSubst, bindings);
            if (xSubst instanceof KifList lx && ySubst instanceof KifList ly && lx.size() == ly.size()) {
                var current = bindings;
                for (var i = 0; i < lx.size(); i++) {
                    current = unify(lx.get(i), ly.get(i), current);
                    if (current == null) return null;
                }
                return current;
            }
            return null;
        }

        static KifTerm fullySubstitute(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            if (bindings.isEmpty() || !term.containsVariable()) return term;
            var current = term;
            for (var depth = 0; depth < 100; depth++) { // Limit recursion
                var next = substituteOnce(current, bindings);
                if (next.equals(current)) return current;
                current = next;
            }
            System.err.println("Warn: Substitution depth limit: " + term.toKifString());
            return current;
        }

        static KifTerm substituteOnce(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            return switch (term) {
                case KifVariable v -> bindings.getOrDefault(v, v);
                case KifList l -> {
                    var sTerms = l.stream().map(t -> substituteOnce(t, bindings)).toList();
                    yield IntStream.range(0, l.size()).anyMatch(i -> l.get(i) != sTerms.get(i)) ? new KifList(sTerms) : l;
                }
                case KifConstant c -> c;
            };
        }

        static KifTerm substitute(KifTerm term, Map<KifVariable, KifTerm> bindings) {
            return fullySubstitute(term, bindings);
        }

        static Map<KifVariable, KifTerm> bindVariable(KifVariable var, KifTerm value, Map<KifVariable, KifTerm> bindings) {
            if (bindings.containsKey(var)) return unify(bindings.get(var), value, bindings);
            if (value instanceof KifVariable vVal && bindings.containsKey(vVal))
                return bindVariable(var, bindings.get(vVal), bindings);
            if (occursCheck(var, value, bindings)) return null;
            Map<KifVariable, KifTerm> newB = new HashMap<>(bindings);
            newB.put(var, value);
            return newB;
        }

        static boolean occursCheck(KifVariable var, KifTerm term, Map<KifVariable, KifTerm> bindings) {
            var substTerm = fullySubstitute(term, bindings);
            return switch (substTerm) {
                case KifVariable v -> var.equals(v);
                case KifList l -> l.stream().anyMatch(t -> occursCheck(var, t, bindings));
                case KifConstant c -> false;
            };
        }
    }

    // --- Swing UI Class (Nested for Single File) ---
    static class SwingUI extends JFrame {
        private final DefaultListModel<Note> noteListModel = new DefaultListModel<>();
        private final JList<Note> noteList = new JList<>(noteListModel);
        private final JTextArea noteEditor = new JTextArea();
        private final JTextArea derivationView = new JTextArea();
        private final JButton addButton = new JButton("Add");
        private final JButton removeButton = new JButton("Remove");
        private final JButton analyzeButton = new JButton("Analyze");
        private final JLabel statusLabel = new JLabel("Status: Idle");
        private ProbabilisticKifReasoner reasoner;
        private Note currentNote = null; // Track the currently selected note

        public SwingUI(ProbabilisticKifReasoner reasoner) {
            super("Probabilistic KIF Reasoner + Notes");
            this.reasoner = reasoner;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Handle close via listener
            setSize(1000, 700);
            setLocationRelativeTo(null);

            // --- Components ---
            noteEditor.setLineWrap(true);
            noteEditor.setWrapStyleWord(true);
            derivationView.setEditable(false);
            derivationView.setLineWrap(true);
            derivationView.setWrapStyleWord(true);
            derivationView.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

            // --- Layout ---
            var mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(noteEditor),
                    new JScrollPane(derivationView));
            mainSplit.setResizeWeight(0.7); // Editor gets more space initially

            var horizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    new JScrollPane(noteList),
                    mainSplit);
            horizontalSplit.setResizeWeight(0.2); // Note list gets less space

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            buttonPanel.add(addButton);
            buttonPanel.add(removeButton);
            buttonPanel.add(analyzeButton);

            var bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(buttonPanel, BorderLayout.WEST);
            bottomPanel.add(statusLabel, BorderLayout.CENTER);

            add(horizontalSplit, BorderLayout.CENTER);
            add(bottomPanel, BorderLayout.SOUTH);

            // --- Listeners ---
            setupListeners();

            // Graceful shutdown
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    System.out.println("UI closing, stopping reasoner...");
                    reasoner.stopReasoner(); // Stop the backend
                    dispose(); // Close the UI window
                    System.exit(0); // Ensure application exit
                }
            });
        }

        private void setupListeners() {
            addButton.addActionListener(this::addNote);
            removeButton.addActionListener(this::removeNote);
            analyzeButton.addActionListener(this::analyzeNote);

            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    currentNote = noteList.getSelectedValue();
                    updateUIForSelection();
                }
            });
        }

        private void updateUIForSelection() {
            if (currentNote != null) {
                noteEditor.setText(currentNote.text);
                noteEditor.setEnabled(true);
                removeButton.setEnabled(true);
                analyzeButton.setEnabled(true);
                derivationView.setText("Derivations for: " + currentNote.title + "\n--------------------\n"); // Clear previous derivations
                // Request reasoner to show current derivations for this note? (Or rely on live updates)
                displayExistingDerivations(currentNote);
            } else {
                noteEditor.setText("");
                noteEditor.setEnabled(false);
                removeButton.setEnabled(false);
                analyzeButton.setEnabled(false);
                derivationView.setText("");
            }
        }

        private void addNote(ActionEvent e) {
            var title = JOptionPane.showInputDialog(this, "Enter note title:", "New Note", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.trim().isEmpty()) {
                var noteId = "note-" + UUID.randomUUID();
                var newNote = new Note(noteId, title.trim(), "");
                noteListModel.addElement(newNote);
                noteList.setSelectedValue(newNote, true); // Select the new note
            }
        }

        private void removeNote(ActionEvent e) {
            if (currentNote != null) {
                var confirm = JOptionPane.showConfirmDialog(this, "Remove note '" + currentNote.title + "' and retract its assertions?", "Confirm Removal", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    // Send retraction message for all assertions associated with this note
                    reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI"));
                    // Remove from UI list
                    var selectedIndex = noteList.getSelectedIndex();
                    noteListModel.removeElement(currentNote);
                    currentNote = null; // Clear selection tracking
                    // Select previous item if possible
                    if (noteListModel.getSize() > 0) noteList.setSelectedIndex(Math.max(0, selectedIndex - 1));
                    else updateUIForSelection(); // Update if list is now empty
                }
            }
        }

        private void analyzeNote(ActionEvent e) {
            if (currentNote == null) return;
            statusLabel.setText("Status: Analyzing '" + currentNote.title + "'...");
            analyzeButton.setEnabled(false); // Disable while processing
            var currentText = noteEditor.getText();
            currentNote.text = currentText; // Update note object text immediately

            // 1. Retract previous assertions for this note
            reasoner.submitMessage(new RetractByNoteIdMessage(currentNote.id, "UI-Analyze"));
            // Clear the tracked set immediately (new IDs will be added) - careful with concurrency if needed, but reasoner processes sequentially.
            currentNote.associatedAssertionIds.clear();

            // 2. Call LLM to get new KIF
            reasoner.getKifFromLlmAsync(currentText, currentNote.id).thenAcceptAsync(kifString -> {
                        // 3. Process LLM response and assert new facts
                        try {
                            var terms = KifParser.parseKif(kifString);
                            var count = 0;
                            for (var term : terms) {
                                if (term instanceof KifList fact) {
                                    if (!fact.containsVariable()) { // Only assert ground facts
                                        // Send assertion message to reasoner, linked to the note
                                        reasoner.submitMessage(new AssertMessage(1.0, fact.toKifString(), "UI-LLM", currentNote.id));
                                        count++;
                                    } else System.err.println("LLM generated non-ground KIF, skipped: " + fact.toKifString());
                                } else System.err.println("LLM generated non-list KIF, skipped: " + term.toKifString());
                            }
                            final var assertionCount = count;
                            SwingUtilities.invokeLater(() -> statusLabel.setText("Status: Analyzed '" + currentNote.title + "', submitted " + assertionCount + " assertions."));

                        } catch (ParseException pe) {
                            SwingUtilities.invokeLater(() -> statusLabel.setText("Status: KIF Parse Error from LLM for '" + currentNote.title + "'."));
                            System.err.println("KIF Parse Error from LLM output: " + pe.getMessage() + "\nInput:\n" + kifString);
                            JOptionPane.showMessageDialog(this, "Could not parse KIF from LLM:\n" + pe.getMessage(), "KIF Parse Error", JOptionPane.ERROR_MESSAGE);
                        } catch (Exception ex) { // Catch other potential errors during processing
                            SwingUtilities.invokeLater(() -> statusLabel.setText("Status: Error processing LLM response for '" + currentNote.title + "'."));
                            System.err.println("Error processing LLM KIF: " + ex);
                            ex.printStackTrace();
                        } finally {
                            SwingUtilities.invokeLater(() -> analyzeButton.setEnabled(true)); // Re-enable button
                        }
                    }, SwingUtilities::invokeLater) // Ensure processing happens on EDT after LLM call
                    .exceptionallyAsync(ex -> { // Handle failures in LLM call itself
                        var cause = ex instanceof CompletionException ? ex.getCause() : ex;
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("Status: LLM Analysis Failed for '" + currentNote.title + "'.");
                            analyzeButton.setEnabled(true); // Re-enable button
                            JOptionPane.showMessageDialog(this, "LLM communication failed:\n" + cause.getMessage(), "LLM Error", JOptionPane.ERROR_MESSAGE);
                        });
                        return null;
                    }, SwingUtilities::invokeLater); // Ensure exception handling happens on EDT
        }

        // Called by the reasoner's callback mechanism (must be thread-safe for UI updates)
        public void handleReasonerCallback(String type, Assertion assertion) {
            if (!type.equals("assert-added")) return; // Only show new derivations for now

            SwingUtilities.invokeLater(() -> {
                if (currentNote != null && isDerivedFrom(assertion, currentNote.id)) {
                    derivationView.append(String.format("P=%.3f %s\n", assertion.probability(), assertion.toKifString()));
                    derivationView.setCaretPosition(derivationView.getDocument().getLength()); // Auto-scroll
                }
            });
        }

        // Check if an assertion originates from or is derived from a specific note
        private boolean isDerivedFrom(Assertion assertion, String targetNoteId) {
            if (targetNoteId == null) return false;
            if (targetNoteId.equals(assertion.sourceNoteId())) return true; // Direct origin

            // Check provenance recursively (BFS to avoid deep stacks)
            Queue<String> toCheck = new LinkedList<>(assertion.directSupportingIds());
            Set<String> visited = new HashSet<>(assertion.directSupportingIds());
            visited.add(assertion.id()); // Avoid checking self

            while (!toCheck.isEmpty()) {
                var currentId = toCheck.poll();
                var parent = reasoner.assertionsById.get(currentId);
                if (parent != null) {
                    if (targetNoteId.equals(parent.sourceNoteId())) return true; // Found origin
                    if (parent.directSupportingIds() != null) {
                        parent.directSupportingIds().stream()
                                .filter(visited::add) // Add returns true if not already present
                                .forEach(toCheck::offer);
                    }
                }
            }
            return false; // Not derived from the target note
        }

        // Initial display of existing derivations when a note is selected
        private void displayExistingDerivations(Note note) {
            if (note == null) return;
            derivationView.setText("Derivations for: " + note.title + "\n--------------------\n"); // Clear
            // Iterate through all assertions in KB and check provenance
            reasoner.assertionsById.values().stream()
                    .filter(a -> isDerivedFrom(a, note.id))
                    .sorted(Comparator.comparingLong(Assertion::timestamp)) // Show in rough order of derivation
                    .forEach(a -> derivationView.append(String.format("P=%.3f %s\n", a.probability(), a.toKifString())));
            derivationView.setCaretPosition(0); // Scroll to top
        }

        // Simple Note data class
        static class Note {
            final String id;
            final Set<String> associatedAssertionIds = ConcurrentHashMap.newKeySet(); // Store IDs generated from this note
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
            } // Display title in JList

            @Override
            public boolean equals(Object o) {
                return o instanceof Note n && id.equals(n.id);
            }

            @Override
            public int hashCode() {
                return id.hashCode();
            }
        }
    }
}