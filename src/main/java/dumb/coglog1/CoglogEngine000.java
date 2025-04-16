package dumb.coglog1; // Use a package name

// Coglog 2.6.1 Engine - Single File Implementation
// Description: A probabilistic, reflective, self-executing logic engine for agentic AI.
//              Processes Thoughts based on Belief scores, matching against META_THOUGHTs
//              to execute actions, enabling planning, self-modification, and robust operation.
// Features:    Immutable data structures, probabilistic execution, meta-circularity,
//              reflection, error handling, retries, persistence, garbage collection.
// Dependencies: Minimal standard Java libraries (UUID, Collections, IO).
// Version: 2.6.1.2 (Parser refinement, log verbosity reduction, bug fix for LLM thought generation)

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main container class for the Coglog 2.6.1 Engine and its components.
 * This single file includes all necessary data structures, interfaces,
 * and implementation logic as specified.
 */
public class CoglogEngine000 implements AutoCloseable {

    // --- Configuration ---
    private final Configuration config;

    // --- Core Components ---
    private final ThoughtStore thoughtStore;
    private final PersistenceService persistenceService;
    private final StoreNotifier storeNotifier;
    private final TextParserService textParserService;
    private final LlmService llmService;
    private final ThoughtGenerator thoughtGenerator;
    private final Unifier unifier;
    private final ActionExecutor actionExecutor;
    private final ExecuteLoop executeLoop;
    private final GarbageCollector garbageCollector;
    private final Bootstrap bootstrap;

    // --- Concurrency Control ---
    // Use a ScheduledThreadPoolExecutor for task management
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setName("CoglogScheduler-" + t.threadId());
        t.setDaemon(true); // Allow JVM exit
        return t;
    });

    /** Constructor using specified configuration */
    public CoglogEngine000(Configuration config) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.storeNotifier = new ConsoleNotifier();
        this.thoughtStore = new InMemoryThoughtStore(storeNotifier);
        this.persistenceService = new FilePersistenceService();
        this.textParserService = new BasicTextParser();
        this.llmService = new MockLlmService(config.llmApiEndpoint(), config.llmApiKey());
        // Pass store to ActionExecutor Helpers for potential future use, though not strictly needed now
        this.actionExecutor = new ActionExecutor(thoughtStore, llmService);
        // Pass ActionExecutor helpers to ThoughtGenerator
        this.thoughtGenerator = new BasicThoughtGenerator(llmService, this.actionExecutor);
        // Set thoughtGenerator back into actionExecutor after construction
        this.actionExecutor.setThoughtGenerator(this.thoughtGenerator);
        this.unifier = new Unifier();
        this.executeLoop = new ExecuteLoop(thoughtStore, unifier, actionExecutor, config, scheduler);
        this.garbageCollector = new GarbageCollector(thoughtStore, config.gcThresholdMillis());
        this.bootstrap = new Bootstrap(thoughtStore);

        loadState(); // Load or bootstrap
    }

    /** Constructor using default configuration */
    public CoglogEngine000() {
        this(Configuration.DEFAULT);
    }

    // --- Engine Setup and Control ---

    /** Starts the engine's execution loop and garbage collection scheduling. */
    public void start() {
        executeLoop.start();
        long gcInterval = Math.max(60_000L, config.gcThresholdMillis()); // GC min every minute
        scheduler.scheduleAtFixedRate(garbageCollector, gcInterval, gcInterval, TimeUnit.MILLISECONDS);
        System.out.println("CoglogEngine started (GC scheduled every " + gcInterval + "ms).");
    }

    /** Stops the execution loop, shuts down scheduler, and saves state. */
    @Override
    public void close() {
        System.out.println("Shutting down CoglogEngine...");
        executeLoop.stop(); // Stop processing loop first
        scheduler.shutdown();
        try {
            // Wait for scheduler tasks
            if (!scheduler.awaitTermination(config.pollIntervalMillis() * 2L + 1000L, TimeUnit.MILLISECONDS)) {
                System.err.println("WARN: Scheduler did not terminate gracefully, forcing shutdown.");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: Interrupted while waiting for scheduler shutdown.");
            scheduler.shutdownNow();
        }
        saveState(); // Save state after stopping
        System.out.println("CoglogEngine shut down complete.");
    }

    /** Adds new Thoughts based on input text, parsed by the TextParserService. */
    public void addInputText(String text) {
        List<Thought> thoughts = textParserService.parse(text);
        thoughts.forEach(thoughtStore::addThought);
        System.out.println("Added " + thoughts.size() + " thought(s) from input text.");
    }

    /** Returns an immutable collection of all current Thoughts in the store. */
    public Collection<Thought> getAllThoughts() {
        return thoughtStore.getAllThoughts();
    }

    /** Provides a map of Thought counts grouped by their status. */
    public Map<Status, Long> getStatusCounts() {
        return thoughtStore.getAllThoughts().stream()
                .collect(Collectors.groupingBy(Thought::status, Collectors.counting()));
    }

    // --- Persistence Handling ---

    /** Loads state from the persistence path or bootstraps if no valid state found. */
    private void loadState() {
        Collection<Thought> loadedThoughts = Collections.emptyList();
        boolean loadSuccess = false;
        Path saveFile = Paths.get(config.persistencePath());
        try {
            if (Files.exists(saveFile) && Files.size(saveFile) > 0) {
                loadedThoughts = persistenceService.load(config.persistencePath());
                loadSuccess = true;
            } else if (Files.exists(saveFile)) {
                System.out.println("Persistence file exists but is empty: " + config.persistencePath() + ". Starting fresh.");
                try { Files.delete(saveFile); } catch (IOException delEx) { System.err.println("WARN: Could not delete empty state file: " + delEx.getMessage());}
            } else {
                System.out.println("Persistence file not found: " + config.persistencePath() + ". Starting fresh.");
            }
        } catch (PersistenceException e) {
            System.err.println("ERROR: Failed to load persisted state from " + config.persistencePath() + ": " + e.getMessage());
            System.err.println("       Cause: " + (e.getCause() != null ? e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage() : "N/A"));
            System.err.println("Attempting to bootstrap with default META_THOUGHTs instead.");
        } catch (IOException e) {
            System.err.println("ERROR: IO exception accessing persistence file " + config.persistencePath() + ": " + e.getMessage());
        }

        thoughtStore.clear(); // Clear before loading/bootstrapping
        if (loadSuccess && !loadedThoughts.isEmpty()) {
            int activeResetCount = 0;
            for (Thought t : loadedThoughts) {
                Thought toAdd = t;
                if (t.status() == Status.ACTIVE) { // Reset ACTIVE thoughts from previous run
                    toAdd = t.toBuilder().status(Status.PENDING).build();
                    activeResetCount++;
                }
                thoughtStore.addThought(toAdd); // Add thought (handles notification)
            }
            if (activeResetCount > 0) System.out.println("WARN: Reset " + activeResetCount + " thoughts from ACTIVE to PENDING on load.");
            System.out.println("Loaded " + thoughtStore.getAllThoughts().size() + " thoughts from state file.");
        } else {
            System.out.println("No previous state loaded or state was empty/invalid. Bootstrapping...");
            bootstrap.loadBootstrapMetaThoughts();
        }
    }

    /** Saves the current state of the ThoughtStore to the persistence path. */
    private void saveState() {
        try {
            persistenceService.save(thoughtStore.getAllThoughts(), config.persistencePath());
        } catch (PersistenceException e) {
            System.err.println("ERROR: Failed to save state to " + config.persistencePath() + ": " + e.getMessage());
            System.err.println("       Cause: " + (e.getCause() != null ? e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage() : "N/A"));
        }
    }

    // --- Main Method for Demo ---

    /** Demonstrates the CoglogEngine initialization, execution, and shutdown. */
    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println("Starting Coglog 2.6.1 Engine Demo...");
        Configuration config = Configuration.DEFAULT;

        try (CoglogEngine000 engine = new CoglogEngine000(config)) {

            // Add initial goals only if the store is nearly empty (ignoring META thoughts)
            if (engine.getAllThoughts().stream().filter(t -> t.role() != Role.META_THOUGHT).count() < 2) {
                System.out.println("Adding demo thoughts...");
                engine.addInputText("decompose(plan_weekend_trip)"); // GOAL -> MT-GOAL-DECOMPOSE
                engine.addInputText("execute_via_llm(summarize_quantum_physics)"); // GOAL -> MT-GOAL-EXECUTE-LLM
                engine.addInputText("convert_note_to_goal"); // NOTE -> MT-NOTE-TO-GOAL (via parser)
                engine.addInputText("goal_with_no_meta(do_nothing)"); // GOAL -> No Match -> FAILED
                engine.addInputText("process_converted_item"); // GOAL -> Should match bootstrap META (e.g. an outcome processing one)
            } else {
                System.out.println("Loaded existing thoughts ("
                        + engine.getAllThoughts().stream().filter(t -> t.role() != Role.META_THOUGHT).count()
                        + " non-META), skipping demo input.");
            }

            engine.start(); // Start the engine loops

            System.out.println("\nEngine running... Press Enter to stop.");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                long lastStatusPrint = System.currentTimeMillis();
                while (!reader.ready()) { // Non-blocking check for Enter
                    if (System.currentTimeMillis() - lastStatusPrint > 5000) { // Print status approx every 5 seconds
                        System.out.printf("%n.Current Status: %s%n", engine.getStatusCounts());
                        lastStatusPrint = System.currentTimeMillis();
                    } else {
                        System.out.print("."); // Show activity more frequently
                    }
                    //noinspection BusyWait
                    Thread.sleep(200); // Wait briefly
                }
                reader.readLine(); // Consume Enter
            }
            System.out.println("\nStopping engine...");

        } // Engine.close() called automatically

        System.out.println("Coglog 2.6.1 Engine Demo finished.");
    }

    // --- Configuration Record ---
    /** Configuration parameters for the Coglog Engine. Immutable record. */
    record Configuration(
            int maxRetries,
            long pollIntervalMillis,
            long maxActiveDurationMillis,
            long gcThresholdMillis,
            String persistencePath,
            String llmApiEndpoint,
            String llmApiKey
    ) {
        static final Configuration DEFAULT = new Configuration(
                3,                    // maxRetries
                100,                  // pollIntervalMillis
                30_000,               // maxActiveDurationMillis (30 seconds)
                TimeUnit.HOURS.toMillis(1), // gcThresholdMillis (1 hour)
                "coglog_state.dat",   // persistencePath
                "http://localhost:11434/api/generate", // llmApiEndpoint (Example Ollama)
                ""                    // llmApiKey (Optional)
        );
    }

    // --- Data Structures ---

    /** Defines the purpose or type of a Thought. Serializable for persistence. */
    enum Role implements Serializable { NOTE, GOAL, STRATEGY, OUTCOME, META_THOUGHT }

    /** Indicates the processing state of a Thought. Serializable for persistence. */
    enum Status implements Serializable { PENDING, ACTIVE, WAITING_CHILDREN, DONE, FAILED }

    /** Represents logical content. Must be immutable and serializable. Uses Java's sealed interfaces. */
    sealed interface Term extends Serializable permits Atom, Variable, NumberTerm, Structure, ListTerm {}

    /** Represents an atomic symbol. Immutable and serializable. */
    record Atom(String name) implements Term {
        Atom { Objects.requireNonNull(name, "Atom name cannot be null"); }
        @Override public String toString() { return name; }
    }

    /** Represents a logical variable. Immutable and serializable. Convention: Uppercase or starting with _. */
    record Variable(String name) implements Term {
        Variable { Objects.requireNonNull(name, "Variable name cannot be null"); }
        @Override public String toString() { return name; }
    }

    /** Represents a numeric value. Immutable and serializable. */
    record NumberTerm(double value) implements Term {
        @Override public String toString() { return String.valueOf(value); }
    }

    /** Represents a structured term (functor and arguments). Immutable and serializable. */
    record Structure(String name, List<Term> args) implements Term {
        public Structure {
            Objects.requireNonNull(name, "Structure name cannot be null");
            Objects.requireNonNull(args, "Structure arguments cannot be null");
            args = List.copyOf(args); // Ensure immutability and non-null elements
            if (args.stream().anyMatch(Objects::isNull)) throw new NullPointerException("Structure arguments cannot contain null");
        }
        @Override public String toString() {
            return name + "(" + args.stream().map(Term::toString).collect(Collectors.joining(", ")) + ")";
        }
    }

    /** Represents a list of terms. Immutable and serializable. */
    record ListTerm(List<Term> elements) implements Term {
        public ListTerm {
            Objects.requireNonNull(elements, "List elements cannot be null");
            elements = List.copyOf(elements); // Ensure immutability and non-null elements
            if (elements.stream().anyMatch(Objects::isNull)) throw new NullPointerException("List elements cannot contain null");
        }
        @Override public String toString() {
            return "[" + elements.stream().map(Term::toString).collect(Collectors.joining(", ")) + "]";
        }
    }

    /** Quantifies confidence using positive/negative evidence counts with Laplace smoothing. Immutable and serializable. */
    record Belief(double positive, double negative) implements Serializable {
        static final Belief DEFAULT_POSITIVE = new Belief(1.0, 0.0);
        static final Belief DEFAULT_UNCERTAIN = new Belief(1.0, 1.0);
        static final Belief DEFAULT_LOW_CONFIDENCE = new Belief(0.1, 0.9);
        private static final double LAPLACE_K = 1.0; // Laplace smoothing constant

        Belief { // Validate counts during construction
            if (!Double.isFinite(positive) || !Double.isFinite(negative) || positive < 0 || negative < 0) {
                throw new IllegalArgumentException("Belief counts must be finite and non-negative: pos=" + positive + ", neg=" + negative);
            }
        }

        /** Calculates belief score using Laplace smoothing. */
        double score() {
            double totalEvidence = positive + negative;
            if (totalEvidence > Double.MAX_VALUE / 2.0) return positive / totalEvidence; // Avoid overflow
            if (totalEvidence == 0.0) return LAPLACE_K / (2.0 * LAPLACE_K); // Handle zero evidence (0.5 for k=1)
            return (positive + LAPLACE_K) / (totalEvidence + 2.0 * LAPLACE_K);
        }

        /** Returns a new Belief instance updated based on a signal. */
        Belief update(boolean positiveSignal) {
            double newPositive = positiveSignal ? Math.min(positive + 1.0, Double.MAX_VALUE) : positive;
            double newNegative = !positiveSignal ? Math.min(negative + 1.0, Double.MAX_VALUE) : negative;
            return (newPositive == positive && newNegative == negative) ? this : new Belief(newPositive, newNegative);
        }

        @Override public String toString() { return String.format("B(%.1f, %.1f)", positive, negative); }
    }

    /** The primary unit of processing. Represents information or a task. Immutable and serializable. */
    record Thought(
            String id,                // Unique identifier (UUID v4 string)
            Role role,                // Purpose
            Term content,             // Logical content
            Belief belief,            // Confidence
            Status status,            // Processing state
            Map<String, Object> metadata // Auxiliary data (immutable map)
    ) implements Serializable {

        private static final Map<String, Object> EMPTY_METADATA = Map.of();

        Thought { // Ensure immutability and validate metadata
            Objects.requireNonNull(id); Objects.requireNonNull(role); Objects.requireNonNull(content);
            Objects.requireNonNull(belief); Objects.requireNonNull(status);
            metadata = (metadata == null || metadata.isEmpty()) ? EMPTY_METADATA : Map.copyOf(metadata);
            validateMetadataValueTypes(metadata); // Validate value types
        }

        /** Provides a builder for creating modified copies. */
        public ThoughtBuilder toBuilder() { return new ThoughtBuilder(this); }

        /** Concise string representation for logging. */
        @Override public String toString() {
            return String.format("Thought[%s, %s, %s, %s, %s, %s]",
                    id.substring(0, Math.min(id.length(), 8)), role, status, belief, content,
                    metadata.isEmpty() ? "{}" : metadata);
        }

        /** Safely retrieves metadata, casting to the expected type. */
        public <T> Optional<T> getMetadata(String key, Class<T> type) {
            return Optional.ofNullable(metadata.get(key)).filter(type::isInstance).map(type::cast);
        }

        /** Creates new Thought with default metadata. */
        public static Thought create(Role role, Term content, Belief belief) {
            return new ThoughtBuilder().role(role).content(content).belief(belief).build();
        }
        public static Thought create(Role role, Term content) { // Overload with default belief
            return create(role, content, Belief.DEFAULT_UNCERTAIN);
        }

        // Validate allowed metadata types
        private static void validateMetadataValueTypes(Map<String, Object> metadata) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                Object value = entry.getValue();
                if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean ||
                        (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String || item == null)))) {
                    throw new IllegalArgumentException("Invalid metadata value type for key '" + entry.getKey() + "': " + value.getClass().getName());
                }
            }
        }
    }

    /** Builder for convenient creation/modification of immutable Thought objects. */
    static class ThoughtBuilder {
        private String id; private Role role; private Term content;
        private Belief belief; private Status status;
        private final Map<String, Object> metadata; // Mutable during build

        ThoughtBuilder() { // For new thoughts
            this.id = Helpers.generateUUID(); this.metadata = new HashMap<>();
            this.metadata.put("creation_timestamp", System.currentTimeMillis());
            this.status = Status.PENDING; this.belief = Belief.DEFAULT_UNCERTAIN;
        }

        ThoughtBuilder(Thought original) { // For modification
            this.id = original.id(); this.role = original.role(); this.content = original.content();
            this.belief = original.belief(); this.status = original.status();
            this.metadata = new HashMap<>(original.metadata()); // Start with copy
        }

        // Chainable builder methods
        public ThoughtBuilder id(String id) { this.id = Objects.requireNonNull(id); return this; }
        public ThoughtBuilder role(Role role) { this.role = Objects.requireNonNull(role); return this; }
        public ThoughtBuilder content(Term content) { this.content = Objects.requireNonNull(content); return this; }
        public ThoughtBuilder belief(Belief belief) { this.belief = Objects.requireNonNull(belief); return this; }
        public ThoughtBuilder status(Status status) { this.status = Objects.requireNonNull(status); return this; }
        public ThoughtBuilder metadata(Map<String, Object> metadata) { this.metadata.clear(); this.metadata.putAll(Objects.requireNonNull(metadata)); return this; }
        public ThoughtBuilder putMetadata(String key, Object value) {
            Objects.requireNonNull(key);
            // Validate type before adding
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean ||
                    (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String || item == null)))) {
                throw new IllegalArgumentException("Invalid metadata value type for key '" + key + "': " + value.getClass().getName());
            }
            // Ensure lists are immutable copies
            this.metadata.put(key, (value instanceof List<?> listValue) ? List.copyOf(listValue) : value);
            return this;
        }

        /** Builds the immutable Thought, applying final checks and defaults. */
        public Thought build() {
            metadata.put("last_updated_timestamp", System.currentTimeMillis());
            metadata.computeIfPresent("retry_count", (k, v) -> (v instanceof Number n) ? n.intValue() : 0);
            metadata.computeIfPresent("creation_timestamp", (k, v) -> (v instanceof Number n) ? n.longValue() : System.currentTimeMillis());
            metadata.computeIfPresent("last_updated_timestamp", (k, v) -> (v instanceof Number n) ? n.longValue() : System.currentTimeMillis());
            // Ensure provenance is List<String>
            metadata.computeIfPresent("provenance", (k,v) -> {
                if (v instanceof List<?> list && list.stream().allMatch(o -> o instanceof String)) {
                    return List.copyOf((List<String>)list); // Ensure immutable copy
                }
                return List.of("INVALID_PROVENANCE_TYPE"); // Default if type is wrong
            });

            // Constructor handles final null checks and Map.copyOf
            return new Thought(id, role, content, belief, status, metadata);
        }
    }

    // --- Custom Exceptions ---
    static class CoglogException extends RuntimeException { CoglogException(String m) { super(m); } CoglogException(String m, Throwable c) { super(m, c); } }
    static class UnificationException extends CoglogException { UnificationException(String m) { super("Unification failed: " + m); } }
    static class UnboundVariableException extends CoglogException { UnboundVariableException(Variable v) { super("Unbound variable during substitution: " + v.name()); } }
    static class ActionExecutionException extends CoglogException { ActionExecutionException(String m) { super("Action execution failed: " + m); } ActionExecutionException(String m, Throwable c) { super("Action execution failed: " + m, c); } }
    static class PersistenceException extends CoglogException { PersistenceException(String m, Throwable c) { super("Persistence error: " + m, c); } }

    // --- Interfaces for Components and Services ---
    interface ThoughtStore {
        Optional<Thought> getThought(String id);
        void addThought(Thought thought);
        boolean updateThought(Thought oldThought, Thought newThought); // Atomic update
        boolean removeThought(String id);
        Optional<Thought> samplePendingThought(); // Samples non-META PENDING thoughts
        List<Thought> getMetaThoughts(); // Returns non-FAILED META_THOUGHTs
        List<Thought> findThoughtsByParentId(String parentId);
        Collection<Thought> getAllThoughts(); // Returns immutable view or copy
        void clear(); // Clears all thoughts
    }

    interface PersistenceService {
        void save(Collection<Thought> thoughts, String path);
        Collection<Thought> load(String path); // Throws PersistenceException
    }

    interface StoreNotifier {
        enum ChangeType { ADD, UPDATE, REMOVE }
        void notifyChange(Thought thought, ChangeType type);
    }

    interface TextParserService { List<Thought> parse(String text); }
    interface LlmService { String generateText(String prompt); }
    interface ThoughtGenerator { List<Thought> generate(Term prompt, Map<Variable, Term> bindings, String parentId); }

    // --- Helper Class and Functions ---
    static class Helpers {
        private static final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();
        private static final Pattern STRUCTURE_PATTERN = Pattern.compile("^([a-z_][\\w]*)\\((.*)\\)$", Pattern.DOTALL); // Allow newlines in args
        private static final Pattern VARIABLE_PATTERN = Pattern.compile("^[_A-Z][_a-zA-Z0-9]*$");
        private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

        /** Generates a standard UUID v4 string. */
        public static String generateUUID() { return UUID.randomUUID().toString(); }

        /** Performs standard unification with occurs check. Returns substitution map or throws UnificationException. */
        public static Map<Variable, Term> unify(Term term1, Term term2) throws UnificationException {
            return unifyRecursive(term1, term2, Collections.emptyMap());
        }

        private static Map<Variable, Term> unifyRecursive(Term term1, Term term2, Map<Variable, Term> substitution) throws UnificationException {
            Term t1 = applySubstitution(term1, substitution); Term t2 = applySubstitution(term2, substitution);

            if (t1.equals(t2)) return substitution;
            if (t1 instanceof Variable v1) return bindVariable(v1, t2, substitution);
            if (t2 instanceof Variable v2) return bindVariable(v2, t1, substitution);

            if (t1 instanceof Structure s1 && t2 instanceof Structure s2 && s1.name().equals(s2.name()) && s1.args().size() == s2.args().size()) {
                Map<Variable, Term> currentSub = substitution;
                for (int i = 0; i < s1.args().size(); i++) currentSub = unifyRecursive(s1.args().get(i), s2.args().get(i), currentSub);
                return currentSub;
            }
            if (t1 instanceof ListTerm l1 && t2 instanceof ListTerm l2 && l1.elements().size() == l2.elements().size()) {
                Map<Variable, Term> currentSub = substitution;
                for (int i = 0; i < l1.elements().size(); i++) currentSub = unifyRecursive(l1.elements().get(i), l2.elements().get(i), currentSub);
                return currentSub;
            }
            throw new UnificationException("Cannot unify incompatible terms: " + t1 + " and " + t2);
        }

        private static Map<Variable, Term> bindVariable(Variable var, Term term, Map<Variable, Term> substitution) throws UnificationException {
            if (substitution.containsKey(var)) return unifyRecursive(substitution.get(var), term, substitution);
            if (term instanceof Variable vTerm && substitution.containsKey(vTerm)) return unifyRecursive(var, substitution.get(vTerm), substitution);
            if (occursCheck(var, term, substitution)) throw new UnificationException("Occurs check failed: " + var + " in " + term);

            Map<Variable, Term> newSubstitution = new HashMap<>(substitution);
            newSubstitution.put(var, term);
            return Map.copyOf(newSubstitution); // Return immutable map
        }

        private static boolean occursCheck(Variable var, Term term, Map<Variable, Term> substitution) {
            Deque<Term> stack = new ArrayDeque<>(); stack.push(applySubstitution(term, substitution));
            Set<Term> visited = new HashSet<>();
            while (!stack.isEmpty()) {
                Term current = stack.pop();
                if (var.equals(current)) return true;
                if (!visited.add(current)) continue; // Avoid cycles
                switch (current) {
                    case Structure s -> s.args().forEach(stack::push);
                    case ListTerm l -> l.elements().forEach(stack::push);
                    default -> {}
                }
            } return false;
        }

        /** Recursively applies substitution map, returning a new term. Avoids object creation if no changes. */
        public static Term applySubstitution(Term term, Map<Variable, Term> substitution) {
            if (substitution.isEmpty()) return term;
            return switch (term) {
                case Variable v -> substitution.getOrDefault(v, v);
                case Structure s -> {
                    boolean changed = false; List<Term> originalArgs = s.args();
                    List<Term> substitutedArgs = new ArrayList<>(originalArgs.size());
                    for (Term arg : originalArgs) {
                        Term subArg = applySubstitution(arg, substitution); substitutedArgs.add(subArg);
                        if (subArg != arg) changed = true;
                    } yield changed ? new Structure(s.name(), List.copyOf(substitutedArgs)) : s;
                }
                case ListTerm l -> {
                    boolean changed = false; List<Term> originalElements = l.elements();
                    List<Term> subElements = new ArrayList<>(originalElements.size());
                    for (Term el : originalElements) {
                        Term subEl = applySubstitution(el, substitution); subElements.add(subEl);
                        if (subEl != el) changed = true;
                    } yield changed ? new ListTerm(List.copyOf(subElements)) : l;
                }
                default -> term; // Atom, NumberTerm
            };
        }

        /** Applies substitution and throws UnboundVariableException if any variables remain. */
        public static Term applySubstitutionFully(Term term, Map<Variable, Term> substitution) throws UnboundVariableException {
            Term substituted = applySubstitution(term, substitution);
            Variable unbound = findFirstVariable(substituted);
            if (unbound != null) throw new UnboundVariableException(unbound);
            return substituted;
        }

        private static Variable findFirstVariable(Term term) { // Iterative check for remaining variables
            Deque<Term> stack = new ArrayDeque<>(); stack.push(term); Set<Term> visited = new HashSet<>();
            while(!stack.isEmpty()) {
                Term current = stack.pop(); if (!visited.add(current)) continue;
                switch (current) {
                    case Variable v -> { return v; }
                    case Structure s -> s.args().forEach(stack::push);
                    case ListTerm l -> l.elements().forEach(stack::push);
                    default -> {}
                }
            } return null;
        }

        /** Samples an item from a list of weighted items. Returns Optional.empty if list empty/weights invalid. */
        public static <T> Optional<T> sampleWeighted(List<Map.Entry<Double, T>> itemsWithScores) {
            List<Map.Entry<Double, T>> validItems = itemsWithScores.stream()
                    .filter(entry -> entry.getKey() > 0 && Double.isFinite(entry.getKey()))
                    .toList();
            if (validItems.isEmpty()) return Optional.empty();
            double totalWeight = validItems.stream().mapToDouble(Map.Entry::getKey).sum();
            if (totalWeight <= 0 || !Double.isFinite(totalWeight)) return Optional.empty();

            double randomValue = RANDOM.nextDouble() * totalWeight; double cumulativeWeight = 0.0;
            for (Map.Entry<Double, T> entry : validItems) {
                cumulativeWeight += entry.getKey();
                if (randomValue < cumulativeWeight) return Optional.of(entry.getValue());
            }
            // Fallback for floating point issues
            return Optional.of(validItems.getLast().getValue());
        }

        /** Parses a string into a Term. Order: Number, List, Structure, Variable, Atom. */
        public static Term parseTerm(String input) throws IllegalArgumentException {
            input = input.trim();
            if (input.isEmpty()) throw new IllegalArgumentException("Cannot parse empty string to Term");

            // 1. Try Number
            if (NUMBER_PATTERN.matcher(input).matches()) {
                try { return new NumberTerm(Double.parseDouble(input)); } catch (NumberFormatException ignored) {} // Should not happen if regex matches
            }
            // 2. Try List: [...]
            if (input.startsWith("[") && input.endsWith("]")) {
                String content = input.substring(1, input.length() - 1).trim();
                return new ListTerm(parseTermListContent(content));
            }
            // 3. Try Structure: name(...)
            Matcher structureMatcher = STRUCTURE_PATTERN.matcher(input);
            if (structureMatcher.matches()) {
                String name = structureMatcher.group(1);
                String argsContent = structureMatcher.group(2).trim();
                return new Structure(name, parseTermListContent(argsContent));
            }
            // 4. Try Variable: Starts with uppercase or underscore
            if (VARIABLE_PATTERN.matcher(input).matches()) {
                return new Variable(input);
            }
            // 5. Default to Atom: Requires basic validation to avoid ambiguity
            if (input.matches("^[a-z_][\\w]*$") || !input.matches("^\\d.*|^\\[.*|.*\\(.*\\).*")) { // Basic check: common atom or not like others
                 // Atoms can contain symbols, spaces etc. but cannot *look* like other types parsed above.
                 // This check is simplified and might allow ambiguous cases (e.g., "A(b)"). Parsing is hard.
                 // A more robust parser would use tokenization and grammar rules.
                return new Atom(input);
            }

            throw new IllegalArgumentException("Cannot parse string into a valid Term: '" + input + "'");
        }

        // Helper to parse comma-separated term content within lists or structures, handling nesting.
        private static List<Term> parseTermListContent(String content) throws IllegalArgumentException {
            if (content.isEmpty()) return Collections.emptyList();
            List<Term> terms = new ArrayList<>();
            int balance = 0; int start = 0; boolean inQuotes = false;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '"' && (i == 0 || content.charAt(i-1) != '\\')) inQuotes = !inQuotes; // Basic quote handling
                if (!inQuotes) {
                    if (c == '(' || c == '[') balance++;
                    else if (c == ')' || c == ']') balance--;
                    else if (c == ',' && balance == 0) {
                        terms.add(parseTerm(content.substring(start, i))); // Parse segment
                        start = i + 1;
                    }
                }
            }
            if (balance != 0) throw new IllegalArgumentException("Mismatched parentheses/brackets in term list: " + content);
            if (inQuotes) throw new IllegalArgumentException("Unclosed quotes in term list: " + content);
            terms.add(parseTerm(content.substring(start))); // Add last term
            return List.copyOf(terms); // Return immutable list
        }
    }

    // --- Component Implementations ---

    /** In-memory ThoughtStore using ConcurrentHashMap for thread-safe access. */
    static class InMemoryThoughtStore implements ThoughtStore {
        private final ConcurrentMap<String, Thought> thoughts = new ConcurrentHashMap<>();
        private final StoreNotifier notifier;

        InMemoryThoughtStore(StoreNotifier notifier) { this.notifier = Objects.requireNonNull(notifier); }

        @Override public Optional<Thought> getThought(String id) { return Optional.ofNullable(thoughts.get(id)); }
        @Override public Collection<Thought> getAllThoughts() { return List.copyOf(thoughts.values()); }
        @Override public void clear() { thoughts.clear(); notifier.notifyChange(null, StoreNotifier.ChangeType.REMOVE); }

        @Override public void addThought(Thought thought) {
            Objects.requireNonNull(thought);
            if (thoughts.putIfAbsent(thought.id(), thought) == null) {
                notifier.notifyChange(thought, StoreNotifier.ChangeType.ADD);
            }
        }

        @Override public boolean updateThought(Thought oldThought, Thought newThought) {
            Objects.requireNonNull(oldThought); Objects.requireNonNull(newThought);
            if (!oldThought.id().equals(newThought.id())) return false;
            boolean updated = thoughts.replace(newThought.id(), oldThought, newThought); // Atomic replace
            if (updated) notifier.notifyChange(newThought, StoreNotifier.ChangeType.UPDATE);
            return updated;
        }

        @Override public boolean removeThought(String id) {
            Thought removed = thoughts.remove(id);
            if (removed != null) { notifier.notifyChange(removed, StoreNotifier.ChangeType.REMOVE); return true; }
            return false;
        }

        @Override public Optional<Thought> samplePendingThought() {
            if (thoughts.isEmpty()) return Optional.empty();
            List<Map.Entry<Double, Thought>> pendingEligible = thoughts.values().stream()
                    .filter(t -> t.status() == Status.PENDING && t.role() != Role.META_THOUGHT)
                    .map(t -> Map.entry(t.belief().score(), t))
                    .toList();
            return Helpers.sampleWeighted(pendingEligible);
        }

        @Override public List<Thought> getMetaThoughts() {
            if (thoughts.isEmpty()) return Collections.emptyList();
            return thoughts.values().stream()
                    .filter(t -> t.role() == Role.META_THOUGHT && t.status() != Status.FAILED)
                    .toList();
        }

        @Override public List<Thought> findThoughtsByParentId(String parentId) {
            Objects.requireNonNull(parentId);
            if (thoughts.isEmpty()) return Collections.emptyList();
            return thoughts.values().stream()
                    .filter(t -> parentId.equals(t.metadata().get("parent_id")))
                    .toList();
        }
    }

    /** Simple StoreNotifier printing changes to console (minimal output). */
    static class ConsoleNotifier implements StoreNotifier {
        @Override public void notifyChange(Thought thought, ChangeType type) { /* Reduced verbosity */ }
    }

    /** Persistence implementation using Java Serialization. */
    static class FilePersistenceService implements PersistenceService {
        @Override
        public void save(Collection<Thought> thoughts, String path) {
            Path filePath = Paths.get(path);
            try {
                Files.createDirectories(filePath.getParent());
                try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(filePath.toFile())))) {
                    oos.writeObject(new ArrayList<>(thoughts)); // Serialize as ArrayList
                }
            } catch (IOException e) { throw new PersistenceException("Failed to save state to " + path, e); }
        }

        @Override
        public Collection<Thought> load(String path) {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                throw new PersistenceException("Load failed: File not found or not readable at " + path, null);
            }
            try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(filePath.toFile())))) {
                Object loadedObject = ois.readObject();
                if (loadedObject instanceof List<?> list && list.stream().allMatch(Thought.class::isInstance)) {
                    @SuppressWarnings("unchecked") Collection<Thought> thoughts = (Collection<Thought>) list;
                    return thoughts;
                } else {
                    throw new PersistenceException("Loaded file format is incorrect (expected List<Thought>). Found: " + (loadedObject == null ? "null" : loadedObject.getClass().getName()), null);
                }
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                throw new PersistenceException("Failed to load or deserialize state from " + path, e);
            }
        }
    }

    /** Basic Text Parser: Creates GOALs for Structures/Lists, NOTE for others. */
    static class BasicTextParser implements TextParserService {
        @Override
        public List<Thought> parse(String text) {
            return Arrays.stream(text.split("\\r?\\n")).map(String::trim).filter(line -> !line.isEmpty())
                    .map(line -> {
                        try {
                            Term parsedTerm = Helpers.parseTerm(line);
                            Role role = (parsedTerm instanceof Structure || parsedTerm instanceof ListTerm) ? Role.GOAL : Role.NOTE;
                            return Thought.create(role, parsedTerm, Belief.DEFAULT_POSITIVE);
                        } catch (IllegalArgumentException e) {
                            System.err.println("WARN: Input line failed Term parsing, creating NOTE with Atom content: '" + line + "' (" + e.getMessage() + ")");
                            return Thought.create(Role.NOTE, new Atom(line), Belief.DEFAULT_POSITIVE);
                        }
                    }).toList();
        }
    }

    /** Placeholder LLM Service: Returns mock responses. */
    static class MockLlmService implements LlmService {
        private final String apiEndpoint; private final String apiKey;
        MockLlmService(String apiEndpoint, String apiKey) { this.apiEndpoint = apiEndpoint; this.apiKey = apiKey; }

        @Override public String generateText(String prompt) {
            System.out.println("LLM Call (Mock): Prompt='" + prompt + "'");
            String goal = extractGoalContent(prompt);

            if (prompt.contains("Decompose goal:")) {
                // Ensure generated roles are Atoms for parser compatibility
                return """
                       add_thought(STRATEGY, execute_strategy(step1_for_%s), default_positive)
                       add_thought(STRATEGY, execute_strategy(step2_for_%s), default_positive)
                       """.formatted(goal, goal).trim();
            } else if (prompt.contains("Execute goal:")) {
                return "llm_completed(task(" + goal + "))"; // Parsable as Structure
            } else if (prompt.contains("generate_meta_thought")) {
                // Role needs to be an Atom
                return "add_thought(META_THOUGHT, meta_def(trigger(new_condition), action(new_effect)), default_positive)";
            }
            return "mock_response(for(" + goal + "))"; // Parsable as Structure
        }

        private String extractGoalContent(String prompt) {
            int marker = prompt.lastIndexOf(":");
            return (marker >= 0 ? prompt.substring(marker + 1).trim() : prompt).replaceAll("[^a-zA-Z0-9_(),]", "_");
        }
    }

    /** Generates new Thoughts, often using an LLM service. */
    static class BasicThoughtGenerator implements ThoughtGenerator {
        private final LlmService llmService;
        private final ActionExecutor actionExecutor; // To access shared helpers

        BasicThoughtGenerator(LlmService llmService, ActionExecutor actionExecutor) {
            this.llmService = Objects.requireNonNull(llmService);
            this.actionExecutor = Objects.requireNonNull(actionExecutor);
        }

        @Override public List<Thought> generate(Term promptTerm, Map<Variable, Term> bindings, String parentId) {
            String promptString;
            try {
                promptString = "Generate thoughts task: " + Helpers.applySubstitutionFully(promptTerm, bindings).toString();
            } catch (UnboundVariableException e) {
                System.err.println("WARN: Cannot generate thoughts, prompt term has unbound variable: " + e.getMessage());
                return Collections.emptyList();
            }

            String llmResponse = llmService.generateText(promptString);
            List<Thought> generatedThoughts = new ArrayList<>();

            for (String line : llmResponse.split("\\r?\\n")) {
                line = line.trim();
                // Expect add_thought(RoleName, ContentTerm, BeliefTermOrName)
                if (line.startsWith("add_thought(") && line.endsWith(")")) {
                    try {
                        String argsString = line.substring("add_thought(".length(), line.length() - 1);
                        List<Term> args = Helpers.parseTermListContent(argsString); // Use robust parser

                        if (args.size() == 3) {
                            // Use ActionExecutor's static helpers for consistency
                            Role role = ActionExecutor.expectEnumValue(args.get(0), Role.class, "Generated Role");
                            Term content = args.get(1); // Content is already a Term
                            Belief belief = ActionExecutor.expectBelief(args.get(2), "Generated Belief");

                            generatedThoughts.add(new ThoughtBuilder()
                                    .role(role).content(content).belief(belief)
                                    .putMetadata("parent_id", parentId)
                                    .putMetadata("provenance", List.of("LLM_GENERATED")) // Add LLM marker
                                    .build());
                        } else logParseWarning(line, "Incorrect argument count (expected 3, got " + args.size() + ")");
                    } catch (Exception e) { logParseWarning(line, e.getClass().getSimpleName() + ": " + e.getMessage()); }
                } else if (!line.isEmpty()) logParseWarning(line, "Does not match add_thought(...) format");
            }
            if (!generatedThoughts.isEmpty()) {
                System.out.printf("Action: generate_thoughts by %s generated %d thoughts.%n", parentId.substring(0,8), generatedThoughts.size());
            }
            return generatedThoughts;
        }

        private void logParseWarning(String line, String reason) { System.err.println("WARN: Could not parse generated thought line (" + reason + "): " + line); }
    }

    /** Handles unification of an active Thought against available META_THOUGHTs and samples a match. */
    static class Unifier {
        record UnificationResult(Thought matchedMetaThought, Map<Variable, Term> substitutionMap) {
            static UnificationResult noMatch() { return new UnificationResult(null, Collections.emptyMap()); }
            boolean hasMatch() { return matchedMetaThought != null; }
        }

        /** Finds META_THOUGHTs matching the active thought's content, then samples one based on belief score. */
        public UnificationResult findAndSampleMatchingMeta(Thought activeThought, List<Thought> metaThoughts) {
            List<Map.Entry<Double, UnificationResult>> potentialMatches = new ArrayList<>();
            for (Thought meta : metaThoughts) {
                if (!(meta.content() instanceof Structure metaDef && "meta_def".equals(metaDef.name()) && metaDef.args().size() == 2)) continue;
                Term targetTerm = metaDef.args().get(0);

                Optional<String> targetRoleName = meta.getMetadata("target_role", String.class);
                if (targetRoleName.isPresent() && !targetRoleName.get().equalsIgnoreCase(activeThought.role().name())) continue;

                try {
                    Map<Variable, Term> substitution = Helpers.unify(activeThought.content(), targetTerm);
                    potentialMatches.add(Map.entry(meta.belief().score(), new UnificationResult(meta, substitution)));
                } catch (UnificationException ignore) { /* No match */
                } catch (Exception e) { System.err.printf("ERROR: Unexpected unification error for META %s: %s%n", meta.id().substring(0, 8), e.getMessage()); }
            }
            return Helpers.sampleWeighted(potentialMatches).orElse(UnificationResult.noMatch());
        }
    }

    /** Executes the action part of a matched META_THOUGHT. Contains static helpers for parsing action args. */
    static class ActionExecutor {
        private final ThoughtStore thoughtStore;
        private final LlmService llmService;
        // Needs ThoughtGenerator for 'generate_thoughts' action. Use setter injection to resolve cyclic dependency.
        private ThoughtGenerator thoughtGenerator;

        ActionExecutor(ThoughtStore store, LlmService llm) {
            this.thoughtStore = store; this.llmService = llm;
        }
        // Setter for ThoughtGenerator
        void setThoughtGenerator(ThoughtGenerator generator) { this.thoughtGenerator = generator; }

        /** Extracts, resolves, and executes the action term from the matched META_THOUGHT. */
        public void execute(Thought activeThought, Thought metaThought, Map<Variable, Term> substitutionMap) {
            Term actionTerm = extractActionTerm(metaThought);
            Term resolvedActionTerm = Helpers.applySubstitution(actionTerm, substitutionMap); // Apply substitution before dispatch
            executeResolvedAction(resolvedActionTerm, activeThought, substitutionMap, metaThought.id());
        }

        private Term extractActionTerm(Thought metaThought) {
            if (metaThought.content() instanceof Structure metaDef && "meta_def".equals(metaDef.name()) && metaDef.args().size() == 2) {
                return metaDef.args().get(1);
            } throw new ActionExecutionException("Invalid META_THOUGHT structure: Expected meta_def(Target, Action), found: " + metaThought.content());
        }

        // Dispatches to the appropriate primitive action handler.
        private void executeResolvedAction(Term action, Thought contextThought, Map<Variable, Term> substitutionMap, String metaThoughtId) {
            if (!(action instanceof Structure structAction)) {
                if (action instanceof Atom atom && "noop".equals(atom.name())) return; // Allow noop atom
                throw new ActionExecutionException("Action term must be a Structure or 'noop' Atom, got: " + action);
            }
            try {
                List<Term> resolvedArgs = structAction.args(); // Args are already substituted by Helpers.applySubstitution
                switch (structAction.name()) {
                    case "add_thought"             -> executeAddThought(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "set_status"              -> executeSetStatus(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "set_belief"              -> executeSetBelief(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "check_parent_completion" -> executeCheckParentCompletion(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "generate_thoughts"       -> executeGenerateThoughts(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "sequence"                -> executeSequence(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "call_llm"                -> executeCallLlm(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "noop"                    -> { /* Explicit noop structure */ }
                    default -> throw new ActionExecutionException("Unknown primitive action name: " + structAction.name());
                }
            } catch (UnboundVariableException uve) { throw new ActionExecutionException("Action '" + structAction.name() + "' argument resolution failed: " + uve.getMessage(), uve);
            } catch (ActionExecutionException aee) { throw aee;
            } catch (Exception e) { throw new ActionExecutionException("Unexpected error executing action '" + structAction.name() + "': " + e.getMessage(), e); }
        }

        // --- Primitive Action Implementations ---
        // Arguments (resolvedArgs) are partially substituted. Ensure groundness using applySubstitutionFully where needed.

        private void executeAddThought(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 3) throw new ActionExecutionException("add_thought requires 3 arguments");
            // Arguments need to be fully ground before use
            Role role = expectEnumValue(Helpers.applySubstitutionFully(resolvedArgs.get(0), sub), Role.class, "Role");
            Term content = Helpers.applySubstitutionFully(resolvedArgs.get(1), sub);
            Belief belief = expectBelief(Helpers.applySubstitutionFully(resolvedArgs.get(2), sub), "Belief");

            Thought newThought = new ThoughtBuilder().role(role).content(content).belief(belief)
                    .putMetadata("parent_id", context.id()).putMetadata("provenance", List.of(metaId)).build();
            thoughtStore.addThought(newThought);
        }

        private void executeSetStatus(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 1) throw new ActionExecutionException("set_status requires 1 argument");
            Status newStatus = expectEnumValue(Helpers.applySubstitutionFully(resolvedArgs.getFirst(), sub), Status.class, "Status");
            if (newStatus == Status.ACTIVE) throw new ActionExecutionException("Cannot set status directly to ACTIVE");

            Thought current = thoughtStore.getThought(context.id()).orElse(null);
            if (current == null || current.status() == newStatus) return; // Missing or already set

            Thought updated = current.toBuilder().status(newStatus).putMetadata("provenance", addToProvenance(current.metadata(), metaId)).build();
            if (!thoughtStore.updateThought(current, updated)) System.err.println("WARN: Failed atomic update for set_status on " + context.id().substring(0, 8));
        }

        private void executeSetBelief(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 1) throw new ActionExecutionException("set_belief requires 1 argument: POSITIVE/NEGATIVE Atom");
            Atom typeAtom = expectAtom(Helpers.applySubstitutionFully(resolvedArgs.getFirst(), sub), "Belief Type");
            boolean positiveSignal = "POSITIVE".equalsIgnoreCase(typeAtom.name());
            if (!positiveSignal && !"NEGATIVE".equalsIgnoreCase(typeAtom.name())) throw new ActionExecutionException("Invalid belief type: '" + typeAtom.name() + "'");

            Thought current = thoughtStore.getThought(context.id()).orElse(null);
            if (current == null) return;
            Belief newBelief = current.belief().update(positiveSignal);
            if (newBelief.equals(current.belief())) return; // No change

            Thought updated = current.toBuilder().belief(newBelief).putMetadata("provenance", addToProvenance(current.metadata(), metaId)).build();
            if (!thoughtStore.updateThought(current, updated)) System.err.println("WARN: Failed atomic update for set_belief on " + context.id().substring(0, 8));
        }

        private void executeCheckParentCompletion(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 3) throw new ActionExecutionException("check_parent_completion requires 3 arguments");
            Atom checkTypeAtom = expectAtom(Helpers.applySubstitutionFully(resolvedArgs.get(0), sub), "CheckType");
            Status statusIfComplete = expectEnumValue(Helpers.applySubstitutionFully(resolvedArgs.get(1), sub), Status.class, "StatusIfComplete");
            // boolean recursive = "TRUE".equalsIgnoreCase(expectAtom(Helpers.applySubstitutionFully(resolvedArgs.get(2), sub), "Recursive").name()); // Ignore recursive for now

            String parentId = context.getMetadata("parent_id", String.class).orElse(null);
            if (parentId == null) return;
            Thought parent = thoughtStore.getThought(parentId).orElse(null);
            if (parent == null || parent.status() != Status.WAITING_CHILDREN) return; // Parent not waiting or missing

            List<Thought> children = thoughtStore.findThoughtsByParentId(parentId);
            Thought currentContext = thoughtStore.getThought(context.id()).orElse(context); // Get latest status of self
            Status contextStatus = currentContext.status();

            boolean allComplete = children.stream().allMatch(child -> {
                Status status = child.id().equals(context.id()) ? contextStatus : child.status(); // Use latest status for self
                return switch (checkTypeAtom.name().toUpperCase()) {
                    case "ALL_DONE" -> status == Status.DONE;
                    case "ALL_TERMINAL" -> status == Status.DONE || status == Status.FAILED;
                    default -> throw new ActionExecutionException("Unknown checkType: " + checkTypeAtom.name());
                };
            });

            if (allComplete) {
                Thought currentParent = thoughtStore.getThought(parentId).orElse(null); // Re-fetch for atomic update
                if (currentParent != null && currentParent.status() == Status.WAITING_CHILDREN) { // Check status again before update
                    Thought updatedParent = currentParent.toBuilder().status(statusIfComplete).putMetadata("provenance", addToProvenance(currentParent.metadata(), metaId)).build();
                    if (thoughtStore.updateThought(currentParent, updatedParent)) {
                         System.out.printf("Action: Parent %s status set to %s by check from %s.%n", parentId.substring(0, 8), statusIfComplete, context.id().substring(0, 8));
                    } else System.err.println("WARN: Failed atomic update for parent completion on " + parentId.substring(0,8));
                }
            }
        }

        private void executeGenerateThoughts(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (thoughtGenerator == null) throw new ActionExecutionException("ThoughtGenerator not set");
            if (resolvedArgs.size() != 1) throw new ActionExecutionException("generate_thoughts requires 1 argument: Prompt Term");
            // Pass original prompt term (may contain variables) and substitution map to generator
            Term promptTerm = resolvedArgs.getFirst();
            thoughtGenerator.generate(promptTerm, sub, context.id())
                    .forEach(t -> { // Add this metaId to provenance of generated thoughts
                        Thought finalThought = t.toBuilder().putMetadata("provenance", addToProvenance(t.metadata(), metaId)).build();
                        thoughtStore.addThought(finalThought);
                    });
        }

        private void executeSequence(List<Term> resolvedArgs, Thought contextThought, Map<Variable, Term> substitutionMap, String metaThoughtId) {
            if (resolvedArgs.size() != 1 || !(resolvedArgs.getFirst() instanceof ListTerm actionList)) {
                throw new ActionExecutionException("sequence requires 1 ListTerm argument, got: " + resolvedArgs);
            }
            int step = 1;
            for (Term actionStep : actionList.elements()) {
                try {
                    // Execute sub-action. Needs careful substitution handling if sub-action uses variables
                    // bound *within* the sequence, which is not supported here. Assumes variables are from outer scope.
                    Term resolvedActionStep = Helpers.applySubstitution(actionStep, substitutionMap); // Resolve outer bindings
                    executeResolvedAction(resolvedActionStep, contextThought, substitutionMap, metaThoughtId);
                } catch (Exception e) { throw new ActionExecutionException("Sequence failed at step " + step + " (" + actionStep + "). Cause: " + e.getMessage(), e); }
                step++;
            }
        }

        private void executeCallLlm(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 2) throw new ActionExecutionException("call_llm requires 2 arguments: Prompt Term, Result Role Atom/Name");
            Term promptTerm = Helpers.applySubstitutionFully(resolvedArgs.get(0), sub); // Ensure prompt is ground
            Role resultRole = expectEnumValue(Helpers.applySubstitutionFully(resolvedArgs.get(1), sub), Role.class, "Result Role");

            String llmResultText;
            try { llmResultText = llmService.generateText(promptTerm.toString()); }
            catch (Exception e) { throw new ActionExecutionException("LLM call failed: " + e.getMessage(), e); }

            Term resultContent; // Attempt to parse LLM response as Term, fallback to Atom
            try { resultContent = Helpers.parseTerm(llmResultText); }
            catch (IllegalArgumentException e) { resultContent = new Atom(llmResultText); }

            Thought resultThought = new ThoughtBuilder().role(resultRole).content(resultContent).belief(Belief.DEFAULT_POSITIVE)
                    .putMetadata("parent_id", context.id()).putMetadata("provenance", List.of(metaId, "LLM_CALL")).build();
            thoughtStore.addThought(resultThought);
        }

        // --- Static Action Helper Methods (Shared with ThoughtGenerator) ---
        /** Expects term to be an Atom whose name matches an Enum constant (case-insensitive). */
        static <T extends Enum<T>> T expectEnumValue(Term term, Class<T> enumClass, String desc) throws ActionExecutionException {
            if (term instanceof Atom atom) {
                try { return Enum.valueOf(enumClass, atom.name().toUpperCase()); }
                catch (IllegalArgumentException e) { throw new ActionExecutionException("Invalid " + desc + ": '" + atom.name() + "' is not a valid " + enumClass.getSimpleName()); }
            } throw new ActionExecutionException("Expected Atom for " + desc + ", got: " + term.getClass().getSimpleName() + " (" + term + ")");
        }
        /** Expects term to be an Atom. */
        static Atom expectAtom(Term term, String desc) throws ActionExecutionException {
            if (term instanceof Atom a) return a; throw new ActionExecutionException("Expected Atom for " + desc + ", got: " + term);
        }
        /** Expects term to be a NumberTerm. */
        static NumberTerm expectNumber(Term term, String desc) throws ActionExecutionException {
            if (term instanceof NumberTerm n) return n; throw new ActionExecutionException("Expected NumberTerm for " + desc + ", got: " + term);
        }
        /** Expects term to represent a Belief (Atom name or Structure). */
        static Belief expectBelief(Term term, String desc) throws ActionExecutionException {
            return switch (term) {
                case Atom a -> parseBeliefString(a.name());
                case Structure s when "belief".equals(s.name()) && s.args().size() == 2 ->
                    new Belief(expectNumber(s.args().get(0), "pos belief").value(), expectNumber(s.args().get(1), "neg belief").value());
                default -> throw new ActionExecutionException("Expected Atom (belief name) or belief(Pos, Neg) for " + desc + ", got: " + term);
            };
        }
        /** Parses common belief names or B(p,n) format into Belief object. */
        private static Belief parseBeliefString(String str) {
            return switch (str.toLowerCase()) {
                case "default_positive" -> Belief.DEFAULT_POSITIVE;
                case "default_uncertain" -> Belief.DEFAULT_UNCERTAIN;
                case "default_low_confidence" -> Belief.DEFAULT_LOW_CONFIDENCE;
                case String s when s.matches("(?i)b\\(\\s*\\d+(\\.\\d+)?\\s*,\\s*\\d+(\\.\\d+)?\\s*\\)") -> {
                    try { String[] p = s.substring(2, s.length() - 1).split(","); yield new Belief(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())); }
                    catch (Exception e) { yield Belief.DEFAULT_UNCERTAIN; } // Fallback on parse error
                } default -> Belief.DEFAULT_UNCERTAIN; // Default fallback
            };
        }
        /** Safely adds an entry to the provenance list in metadata. */
        static List<String> addToProvenance(Map<String, Object> meta, String entry) {
            List<String> current = Optional.ofNullable(meta.get("provenance")).filter(List.class::isInstance)
                .map(l -> (List<?>) l).flatMap(l -> l.stream().allMatch(String.class::isInstance) ?
                    Optional.of(l.stream().map(String.class::cast).toList()) : Optional.<List<String>>empty())
                .orElse(List.of());
            List<String> updated = new ArrayList<>(current); updated.add(entry);
            return List.copyOf(updated); // Return immutable list
        }
    }

    /** Main execution loop controller. Runs the select-match-execute cycle. */
    static class ExecuteLoop implements Runnable {
        private final ThoughtStore thoughtStore; private final Unifier unifier;
        private final ActionExecutor actionExecutor; private final Configuration config;
        private final ScheduledExecutorService scheduler;
        private final ConcurrentMap<String, ScheduledFuture<?>> activeThoughtTimeouts = new ConcurrentHashMap<>();
        private volatile boolean running = false; private ScheduledFuture<?> cycleFuture;

        ExecuteLoop(ThoughtStore s, Unifier u, ActionExecutor a, Configuration c, ScheduledExecutorService sch) {
            this.thoughtStore = s; this.unifier = u; this.actionExecutor = a; this.config = c; this.scheduler = sch;
        }

        public void start() {
            if (running) return; running = true;
            cycleFuture = scheduler.scheduleWithFixedDelay(this::runCycleInternal, 0, config.pollIntervalMillis(), TimeUnit.MILLISECONDS);
            System.out.println("ExecuteLoop started (polling every " + config.pollIntervalMillis() + "ms).");
        }

        public void stop() {
            if (!running) return; running = false;
            if (cycleFuture != null) cycleFuture.cancel(false); // Don't interrupt running cycle
            activeThoughtTimeouts.values().forEach(f -> f.cancel(true)); activeThoughtTimeouts.clear();
            System.out.println("ExecuteLoop stopped.");
        }

        @Override public void run() { runCycleInternal(); } // Allow direct run if needed

        private void runCycleInternal() {
            if (!running) return; // Exit if stopped during cycle start

            Thought active = null; String activeId = null;
            try {
                // 1. Sample PENDING thought
                Optional<Thought> pendingOpt = thoughtStore.samplePendingThought();
                if (pendingOpt.isEmpty()) return; // Nothing to do
                Thought pending = pendingOpt.get();

                // 2. Activate (Atomic: re-fetch and update)
                Thought currentPending = thoughtStore.getThought(pending.id()).orElse(null);
                if (currentPending == null || currentPending.status() != Status.PENDING) return; // Gone or changed status
                active = updateStatusAtomically(currentPending, Status.ACTIVE);
                if (active == null) return; // Failed activation (race condition)
                activeId = active.id();

                // 3. Schedule Timeout Task
                final String finalActiveId = activeId; // Need final variable for lambda
                ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> handleTimeout(finalActiveId), config.maxActiveDurationMillis(), TimeUnit.MILLISECONDS);
                ScheduledFuture<?> oldTask = activeThoughtTimeouts.put(activeId, timeoutTask); // Store task
                if (oldTask != null) oldTask.cancel(false); // Cancel previous if any lingered (unlikely)

                // 4. Match & Execute
                List<Thought> metaThoughts = thoughtStore.getMetaThoughts(); // Get current METAs
                Unifier.UnificationResult unification = unifier.findAndSampleMatchingMeta(active, metaThoughts);

                if (unification.hasMatch()) {
                    actionExecutor.execute(active, unification.matchedMetaThought(), unification.substitutionMap());
                    // Action should set terminal status (DONE, FAILED, WAITING_CHILDREN). Timeout handles stuck ACTIVE.
                } else {
                    // 5. No Match -> Fail
                    System.out.printf("No matching META_THOUGHT found for %s (%s: %s).%n", activeId.substring(0, 8), active.role(), active.content());
                    handleFailure(active, "No matching META_THOUGHT", calculateRemainingRetries(active));
                }

            } catch (Throwable e) { // Catch unexpected errors during cycle
                System.err.println("FATAL: Uncaught exception during cycle: " + e.getMessage());
                e.printStackTrace();
                if (active != null) { // Attempt to fail the thought that caused the error
                    Thought current = thoughtStore.getThought(active.id()).orElse(active); // Get latest version
                    handleFailure(current, "Uncaught cycle exception: " + e.getClass().getSimpleName(), calculateRemainingRetries(current));
                }
            } finally {
                // 6. Cleanup Timeout (if cycle finished before timeout)
                if (activeId != null) {
                    ScheduledFuture<?> task = activeThoughtTimeouts.remove(activeId);
                    if (task != null) task.cancel(false); // Cancel if still pending
                }
            }
        }

        // Handles timeout event scheduled in runCycleInternal
        private void handleTimeout(String timedOutThoughtId) {
            activeThoughtTimeouts.remove(timedOutThoughtId); // Remove handled task
            Thought current = thoughtStore.getThought(timedOutThoughtId).orElse(null);
            // Only fail if it's *still* ACTIVE (i.e., action didn't complete or fail it)
            if (current != null && current.status() == Status.ACTIVE) {
                System.err.printf("TIMEOUT detected for thought %s after %dms.%n", timedOutThoughtId.substring(0, 8), config.maxActiveDurationMillis());
                handleFailure(current, "Timeout", calculateRemainingRetries(current));
            }
        }

        // Handles failure (no match, error, timeout), updating thought status and belief.
        private void handleFailure(Thought thought, String error, int retriesLeft) {
            Thought current = thoughtStore.getThought(thought.id()).orElse(null);
            // Only handle if still ACTIVE or PENDING (could be pending if failure happened before activation)
            if (current == null || (current.status() != Status.ACTIVE && current.status() != Status.PENDING)) return;

            int currentRetries = current.getMetadata("retry_count", Integer.class).orElse(0);
            Status nextStatus = retriesLeft > 0 ? Status.PENDING : Status.FAILED;
            Belief nextBelief = current.belief().update(false); // Negative reinforcement

            Thought updated = current.toBuilder().status(nextStatus).belief(nextBelief)
                    .putMetadata("error_info", error).putMetadata("retry_count", currentRetries + 1).build();

            System.out.printf("Handling Failure for %s: Error='%s', Retries Left=%d, New Status=%s%n",
                    thought.id().substring(0, 8), error, retriesLeft, nextStatus);

            if (!thoughtStore.updateThought(current, updated)) { // Atomic update
                System.err.printf("CRITICAL: Failed atomic update during failure handling for %s.%n", thought.id().substring(0, 8));
                // Could potentially retry the failure handling, but risks loops. Log critical error.
            }
        }

        // Calculates remaining retries based on current thought metadata and config.
        private int calculateRemainingRetries(Thought thought) {
            int currentRetries = thought.getMetadata("retry_count", Integer.class).orElse(0);
            return Math.max(0, config.maxRetries() - currentRetries);
        }

        // Atomically updates thought status. Returns updated thought or null on failure.
        private Thought updateStatusAtomically(Thought current, Status newStatus) {
            if (current.status() == newStatus) return current; // Already in desired state
            Thought updated = current.toBuilder().status(newStatus).build();
            return thoughtStore.updateThought(current, updated) ? updated : null;
        }
    }

    /** Performs garbage collection on old, terminal (DONE/FAILED) thoughts. */
    static class GarbageCollector implements Runnable {
        private final ThoughtStore thoughtStore; private final long gcThresholdMillis;
        private static final Set<Status> TERMINAL_STATUSES = Set.of(Status.DONE, Status.FAILED);

        GarbageCollector(ThoughtStore store, long threshold) {
            this.thoughtStore = store; this.gcThresholdMillis = Math.max(1000L, threshold); // Min 1 sec
        }

        @Override public void run() {
            long cutoffTime = System.currentTimeMillis() - gcThresholdMillis;
            int removedCount = 0;

            // Collect IDs of thoughts eligible for GC based on a snapshot
            List<String> removableIds = thoughtStore.getAllThoughts().stream()
                .filter(t -> TERMINAL_STATUSES.contains(t.status()))
                .filter(t -> t.getMetadata("last_updated_timestamp", Long.class)
                             .orElse(t.getMetadata("creation_timestamp", Long.class).orElse(0L)) < cutoffTime)
                .map(Thought::id)
                .toList(); // Collect IDs first to avoid ConcurrentModificationException if iterating directly

            // Remove the collected IDs
            for (String id : removableIds) {
                if (thoughtStore.removeThought(id)) removedCount++;
            }

            if (removedCount > 0) {
                System.out.printf("Garbage Collector: Finished. Removed %d old terminal thoughts.%n", removedCount);
            }
        }
    }

    /** Loads the initial set of META_THOUGHTs required for basic engine operation. */
    static class Bootstrap {
        private final ThoughtStore thoughtStore;
        Bootstrap(ThoughtStore store) { this.thoughtStore = store; }

        // --- Term creation shorthand helpers ---
        private static Atom a(String n) { return new Atom(n); }
        private static Variable v(String n) { return new Variable(n); }
        private static Structure s(String n, Term... args) { return new Structure(n, List.of(args)); }
        private static ListTerm l(Term... elems) { return new ListTerm(List.of(elems)); }

        /** Loads default META_THOUGHTs if the store doesn't have them. */
        public void loadBootstrapMetaThoughts() {
            System.out.println("Loading Bootstrap META_THOUGHTs...");
            int count = 0;
            // Add common META_THOUGHTs using helper method
            count += addMeta("NOTE-TO-GOAL", Role.NOTE, a("convert_note_to_goal"),
                    s("sequence", l( s("add_thought", a("GOAL"), a("process_converted_item"), a("DEFAULT_POSITIVE")), s("set_status", a("DONE")) )),
                    Belief.DEFAULT_POSITIVE, "Converts 'convert_note_to_goal' NOTE to a GOAL");

            count += addMeta("GOAL-DECOMPOSE", Role.GOAL, s("decompose", v("GoalContent")),
                    s("sequence", l( s("set_status", a("WAITING_CHILDREN")), s("generate_thoughts", s("prompt", a("Decompose goal:"), v("GoalContent"))) )),
                    Belief.DEFAULT_POSITIVE, "Decomposes GOAL via LLM");

            count += addMeta("GOAL-EXECUTE-LLM", Role.GOAL, s("execute_via_llm", v("GoalContent")),
                    s("sequence", l( s("call_llm", s("prompt", a("Execute goal:"), v("GoalContent")), a("OUTCOME")), s("set_status", a("DONE")) )),
                    Belief.DEFAULT_POSITIVE, "Executes GOAL via LLM, creates OUTCOME");

            count += addMeta("STRATEGY-EXECUTE", Role.STRATEGY, s("execute_strategy", v("StrategyContent")),
                    s("sequence", l( s("add_thought", a("OUTCOME"), s("result", v("StrategyContent"), a("completed")), a("DEFAULT_POSITIVE")), s("set_status", a("DONE")), s("check_parent_completion", a("ALL_TERMINAL"), a("DONE"), a("FALSE")) )), // Check parent after DONE
                    Belief.DEFAULT_POSITIVE, "Executes STRATEGY, creates OUTCOME, checks parent");

            count += addMeta("OUTCOME-PROCESS", Role.OUTCOME, v("AnyOutcomeContent"), // Matches any outcome
                    s("sequence", l( s("set_status", a("DONE")), s("check_parent_completion", a("ALL_TERMINAL"), a("DONE"), a("FALSE")) )), // Check parent after DONE
                    Belief.DEFAULT_POSITIVE, "Processes OUTCOME, marks DONE, checks parent");

            // Added META to handle the 'process_converted_item' GOAL created by NOTE-TO-GOAL
             count += addMeta("PROCESS-CONVERTED-ITEM", Role.GOAL, a("process_converted_item"),
                    s("sequence", l( s("add_thought", a("NOTE"), a("Item processing complete"), a("DEFAULT_POSITIVE")), s("set_status", a("DONE")) )), // Simple action: add note, set done
                    Belief.DEFAULT_POSITIVE, "Handles the generic GOAL created after NOTE conversion");

            count += addMeta("REFLECT-GEN-META", Role.GOAL, a("generate_new_meta_thought"),
                    s("sequence", l( s("generate_thoughts", s("prompt", a("generate_meta_thought"))), s("set_status", a("DONE")) )),
                    Belief.DEFAULT_POSITIVE, "Generates a new META_THOUGHT via LLM");

            System.out.println("Bootstrap META_THOUGHTs loaded: " + count);
        }

        // Helper to add a META_THOUGHT if not already present. Returns 1 if added, 0 otherwise.
        private int addMeta(String idHint, Role targetRole, Term target, Term action, Belief belief, String description) {
            String id = "META-" + idHint.replaceAll("\\W", "_"); // Create stable ID
            if (thoughtStore.getThought(id).isPresent()) return 0; // Don't overwrite if loaded

            Thought meta = new ThoughtBuilder().id(id).role(Role.META_THOUGHT)
                    .content(s("meta_def", target, action))
                    .belief(belief).status(Status.PENDING) // Status PENDING: Not executed directly, can be modified? Use DONE? Let's use PENDING.
                    .putMetadata("description", "Bootstrap: " + description)
                    .putMetadata("target_role", targetRole.name()) // Store target role hint
                    .build();
            thoughtStore.addThought(meta);
            return 1;
        }
    }
}