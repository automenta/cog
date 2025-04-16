package dumb.coglog1; // Use a package name

// Coglog 2.6.1 Engine - Single File Implementation
// Description: A probabilistic, reflective, self-executing logic engine for agentic AI.
//              Processes Thoughts based on Belief scores, matching against META_THOUGHTs
//              to execute actions, enabling planning, self-modification, and robust operation.
// Features:    Immutable data structures, probabilistic execution, meta-circularity,
//              reflection, error handling, retries, persistence, garbage collection.
// Dependencies: Minimal standard Java libraries (UUID, Collections, IO).
// Version: 2.6.1

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static dumb.coglog1.CoglogEngine0.BasicThoughtGenerator.parseBeliefString;

/**
 * Main container class for the Coglog 2.6.1 Engine and its components.
 * This single file includes all necessary data structures, interfaces,
 * and implementation logic as specified.
 */
public class CoglogEngine0 implements AutoCloseable {

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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2); // One for GC, one for ExecuteLoop+Timeouts

    /** Constructor using specified configuration */
    public CoglogEngine0(Configuration config) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.storeNotifier = new ConsoleNotifier(); // Simple console notifier
        this.thoughtStore = new InMemoryThoughtStore(storeNotifier);
        this.persistenceService = new FilePersistenceService();
        this.textParserService = new BasicTextParser();
        this.llmService = new MockLlmService(config.llmApiEndpoint(), config.llmApiKey()); // Use Mock LLM
        this.thoughtGenerator = new BasicThoughtGenerator(llmService);
        this.unifier = new Unifier();
        this.actionExecutor = new ActionExecutor(thoughtStore, thoughtGenerator, llmService);
        this.executeLoop = new ExecuteLoop(thoughtStore, unifier, actionExecutor, config, scheduler);
        this.garbageCollector = new GarbageCollector(thoughtStore, config.gcThresholdMillis());
        this.bootstrap = new Bootstrap(thoughtStore);

        loadState(); // Load existing state or bootstrap if necessary
    }

    /** Constructor using default configuration */
    public CoglogEngine0() {
        this(Configuration.DEFAULT);
    }

    // --- Engine Setup and Control ---

    /** Starts the engine's execution loop and garbage collection scheduling. */
    public void start() {
        executeLoop.start();
        long gcInterval = Math.max(60_000L, config.gcThresholdMillis()); // Ensure GC runs at least every minute
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
            // Wait for scheduler tasks to finish gracefully
            if (!scheduler.awaitTermination(config.pollIntervalMillis() * 2L + 1000L, TimeUnit.MILLISECONDS)) {
                System.err.println("Scheduler did not terminate gracefully, forcing shutdown.");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for scheduler shutdown.");
            scheduler.shutdownNow();
        }
        saveState(); // Save state after stopping activity
        System.out.println("CoglogEngine shut down complete.");
    }

    /** Adds new Thoughts based on input text, parsed by the TextParserService. */
    public void addInputText(String text) {
        List<Thought> thoughts = textParserService.parse(text);
        thoughts.forEach(thoughtStore::addThought); // addThought handles notification
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
        try {
            Path saveFile = Paths.get(config.persistencePath());
            if (Files.exists(saveFile) && Files.size(saveFile) > 0) { // Check size > 0
                loadedThoughts = persistenceService.load(config.persistencePath());
                loadSuccess = true;
            } else if (Files.exists(saveFile)) {
                System.out.println("Persistence file exists but is empty: " + config.persistencePath() + ". Starting fresh.");
                Files.deleteIfExists(saveFile); // Clean up empty file
            } else {
                System.out.println("Persistence file not found: " + config.persistencePath() + ". Starting fresh.");
            }
        } catch (PersistenceException e) {
            System.err.println("ERROR: Failed to load persisted state from " + config.persistencePath() + ": " + e.getMessage());
            System.err.println("       Cause: " + (e.getCause() != null ? e.getCause().getMessage() : "N/A"));
            System.err.println("Attempting to bootstrap with default META_THOUGHTs instead.");
            // Optionally backup corrupted file here: backupCorruptedFile(config.persistencePath());
        } catch (IOException e) {
            System.err.println("ERROR: IO exception while checking persistence file: " + config.persistencePath() + ": " + e.getMessage());
        }

        thoughtStore.clear(); // Clear any existing state before loading/bootstrapping
        if (loadSuccess && !loadedThoughts.isEmpty()) {
            int activeResetCount = 0;
            for (Thought t : loadedThoughts) {
                if (t.status() == Status.ACTIVE) {
                    // Reset ACTIVE thoughts from previous run to PENDING
                    thoughtStore.addThought(t.toBuilder().status(Status.PENDING).build());
                    activeResetCount++;
                } else {
                    thoughtStore.addThought(t);
                }
            }
            if (activeResetCount > 0) {
                System.out.println("WARN: Reset " + activeResetCount + " thoughts from ACTIVE to PENDING on load.");
            }
        } else {
            System.out.println("No previous state loaded or state was empty. Bootstrapping...");
            bootstrap.loadBootstrapMetaThoughts();
        }
    }

    /** Saves the current state of the ThoughtStore to the persistence path. */
    private void saveState() {
        try {
            persistenceService.save(thoughtStore.getAllThoughts(), config.persistencePath());
        } catch (PersistenceException e) {
            System.err.println("ERROR: Failed to save state to " + config.persistencePath() + ": " + e.getMessage());
            System.err.println("       Cause: " + (e.getCause() != null ? e.getCause().getMessage() : "N/A"));
        }
    }

    // --- Main Method for Demo ---

    /** Demonstrates the CoglogEngine initialization, execution, and shutdown. */
    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println("Starting Coglog 2.6.1 Engine Demo...");
        Configuration config = Configuration.DEFAULT;

        try (CoglogEngine0 engine = new CoglogEngine0(config)) {

            // Add initial goals if the store has few non-META thoughts
            if (engine.getAllThoughts().stream().filter(t -> t.role() != Role.META_THOUGHT).count() < 3) {
                System.out.println("Adding demo thoughts...");
                engine.addInputText("decompose(plan_weekend_trip)"); // Should match MT-GOAL-DECOMPOSE
                engine.addInputText("execute_via_llm(summarize_quantum_physics)"); // Should match MT-GOAL-EXECUTE-LLM
                engine.addInputText("convert_note_to_goal"); // Should match MT-NOTE-TO-GOAL (if parser creates NOTE) - Parser currently makes GOALs, so this WON'T match MT-NOTE-TO-GOAL
                // Let's add a NOTE manually for testing MT-NOTE-TO-GOAL
                // engine.thoughtStore.addThought(Thought.create(Role.NOTE, Helpers.parseTerm("convert_note_to_goal"), Belief.DEFAULT_POSITIVE));
                // Let's add a goal that won't match any META to test failure/retry
                engine.addInputText("goal_with_no_meta(do_nothing)");
                // engine.addInputText("generate_new_meta_thought"); // Uncomment to test reflection
            } else {
                System.out.println("Loaded existing thoughts, skipping demo input.");
            }

            engine.start(); // Start the engine loops

            System.out.println("\nEngine running... Press Enter to stop.");
            // Non-blocking wait for Enter
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                while (!reader.ready()) { // Check if Enter key is pressed without blocking
                    System.out.print("."); // Show activity
                    // Print status periodically
                    if (System.currentTimeMillis() % 5000 < 100) { // Approx every 5 seconds
                        System.out.printf("%nCurrent Status: %s%n", engine.getStatusCounts());
                    }
                    //noinspection BusyWait
                    Thread.sleep(100); // Wait briefly
                }
                reader.readLine(); // Consume the Enter key press
            }
            System.out.println("\nStopping engine...");

        } // Engine.close() called automatically (stops loops, saves state)

        System.out.println("Coglog 2.6.1 Engine Demo finished.");
    }

    // --- Configuration Record ---
    /** Configuration parameters for the Coglog Engine. */
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
                "http://localhost:11434/api/generate", // llmApiEndpoint (Example for Ollama)
                ""                    // llmApiKey (Optional)
        );
    }

    // --- Data Structures ---

    /** Defines the purpose or type of a Thought. Serializable for persistence. */
    enum Role implements Serializable { NOTE, GOAL, STRATEGY, OUTCOME, META_THOUGHT }

    /** Indicates the processing state of a Thought. Serializable for persistence. */
    enum Status implements Serializable { PENDING, ACTIVE, WAITING_CHILDREN, DONE, FAILED }

    /**
     * Represents logical content. Must be immutable and serializable.
     * Uses Java's sealed interfaces for controlled subtypes.
     */
    sealed interface Term extends Serializable permits Atom, Variable, NumberTerm, Structure, ListTerm {}

    /** Represents an atomic symbol (e.g., 'go', 'true'). Immutable and serializable. */
    record Atom(String name) implements Term {
        Atom { Objects.requireNonNull(name); }
        @Override public String toString() { return name; }
    }

    /** Represents a logical variable (e.g., '_X', 'Content'). Immutable and serializable. */
    record Variable(String name) implements Term { // Convention: Uppercase or starting with _
        Variable { Objects.requireNonNull(name); }
        @Override public String toString() { return name; }
    }

    /** Represents a numeric value. Immutable and serializable. */
    record NumberTerm(double value) implements Term {
        @Override public String toString() { return String.valueOf(value); }
    }

    /** Represents a structured term (functor and arguments). Immutable and serializable. */
    record Structure(String name, List<Term> args) implements Term {
        public Structure {
            Objects.requireNonNull(name);
            // Ensure args list is immutable upon creation
            args = List.copyOf(Objects.requireNonNull(args));
        }
        @Override public String toString() {
            return name + "(" + args.stream().map(Term::toString).collect(Collectors.joining(", ")) + ")";
        }
    }

    /** Represents a list of terms. Immutable and serializable. */
    record ListTerm(List<Term> elements) implements Term {
        public ListTerm {
            // Ensure elements list is immutable upon creation
            elements = List.copyOf(Objects.requireNonNull(elements));
        }
        @Override public String toString() {
            return "[" + elements.stream().map(Term::toString).collect(Collectors.joining(", ")) + "]";
        }
    }

    /**
     * Quantifies confidence using positive/negative evidence counts with Laplace smoothing.
     * Immutable and serializable.
     */
    record Belief(double positive, double negative) implements Serializable {
        static final Belief DEFAULT_POSITIVE = new Belief(1.0, 0.0);    // score ≈ 0.67
        static final Belief DEFAULT_UNCERTAIN = new Belief(1.0, 1.0);  // score = 0.5
        static final Belief DEFAULT_LOW_CONFIDENCE = new Belief(0.1, 0.9); // score ≈ 0.18

        // Validate counts during construction
        Belief {
            if (!Double.isFinite(positive) || !Double.isFinite(negative) || positive < 0 || negative < 0) {
                throw new IllegalArgumentException("Belief counts must be finite and non-negative: " + positive + ", " + negative);
            }
        }

        /** Calculates the belief score using Laplace smoothing (add-1 smoothing). */
        double score() {
            double total = positive + negative;
            // Handle potential overflow if counts are extremely large
            return total > Double.MAX_VALUE / 2.0 ?
                    positive / total : // Approximate if near overflow
                    (positive + 1.0) / (total + 2.0); // Laplace smoothing
        }

        /** Returns a new Belief instance updated based on a signal. */
        Belief update(boolean positiveSignal) {
            // Use Math.min to prevent overflow beyond Double.MAX_VALUE
            double newPositive = positiveSignal ? Math.min(positive + 1.0, Double.MAX_VALUE) : positive;
            double newNegative = positiveSignal ? negative : Math.min(negative + 1.0, Double.MAX_VALUE);
            // Avoid creating new object if counts haven't changed (e.g., already at max)
            return (newPositive == positive && newNegative == negative) ? this : new Belief(newPositive, newNegative);
        }

        @Override public String toString() { return String.format("B(%.1f, %.1f)", positive, negative); }
    }

    /**
     * The primary unit of processing. Represents a piece of information or task.
     * Immutable and serializable.
     */
    record Thought(
            String id,                // Unique identifier (UUID v4 string)
            Role role,                // Purpose of the thought
            Term content,             // Logical content
            Belief belief,            // Confidence score
            Status status,            // Current processing state
            Map<String, Object> metadata // Auxiliary data (must be immutable map)
    ) implements Serializable {

        // Ensure immutability and validate metadata during construction
        Thought {
            Objects.requireNonNull(id);
            Objects.requireNonNull(role);
            Objects.requireNonNull(content);
            Objects.requireNonNull(belief);
            Objects.requireNonNull(status);
            metadata = Map.copyOf(Objects.requireNonNull(metadata)); // Ensure immutable map
            validateMetadata(metadata); // Validate value types
        }

        /** Provides a builder for creating modified copies of this Thought. */
        public ThoughtBuilder toBuilder() { return new ThoughtBuilder(this); }

        /** Returns a concise string representation for logging. */
        @Override public String toString() {
            return String.format("Thought[%s, %s, %s, %s, %s, %s]",
                    id.substring(0, Math.min(id.length(), 8)), // Short ID
                    role, status, belief, content, metadata);
        }

        /** Safely retrieves a metadata value by key, casting to the expected type. */
        public <T> Optional<T> getMetadata(String key, Class<T> type) {
            return Optional.ofNullable(metadata.get(key)).filter(type::isInstance).map(type::cast);
        }

        // Static factory for creating new Thoughts with default metadata
        public static Thought create(Role role, Term content, Belief belief) {
            return new ThoughtBuilder().role(role).content(content).belief(belief).build();
        }
        public static Thought create(Role role, Term content) { // Overload with default belief
            return create(role, content, Belief.DEFAULT_UNCERTAIN);
        }

        // Validate allowed metadata types (primitive wrappers, String, List<String>)
        private static void validateMetadata(Map<String, Object> metadata) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                Object value = entry.getValue();
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean ||
                        (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)))) {
                    throw new IllegalArgumentException("Invalid metadata value type for key '" + entry.getKey() + "': " + (value == null ? "null" : value.getClass().getName()));
                }
            }
        }
    }

    /**
     * Builder pattern for convenient creation and modification of immutable Thought objects.
     * Ensures required fields and standard metadata (timestamps, UUID) are handled.
     */
    static class ThoughtBuilder {
        private String id;
        private Role role;
        private Term content;
        private Belief belief;
        private Status status;
        private final Map<String, Object> metadata; // Use mutable map during build process

        // Constructor for creating a new Thought from scratch
        ThoughtBuilder() {
            this.id = Helpers.generateUUID();
            this.metadata = new HashMap<>();
            this.metadata.put("creation_timestamp", System.currentTimeMillis());
            this.status = Status.PENDING;       // Default status
            this.belief = Belief.DEFAULT_UNCERTAIN; // Default belief
        }

        // Constructor for creating a builder based on an existing Thought (for modification)
        ThoughtBuilder(Thought original) {
            this.id = original.id();
            this.role = original.role();
            this.content = original.content();
            this.belief = original.belief();
            this.status = original.status();
            this.metadata = new HashMap<>(original.metadata()); // Start with copy of original metadata
        }

        // --- Builder methods ---
        public ThoughtBuilder id(String id) { this.id = Objects.requireNonNull(id); return this; }
        public ThoughtBuilder role(Role role) { this.role = Objects.requireNonNull(role); return this; }
        public ThoughtBuilder content(Term content) { this.content = Objects.requireNonNull(content); return this; }
        public ThoughtBuilder belief(Belief belief) { this.belief = Objects.requireNonNull(belief); return this; }
        public ThoughtBuilder status(Status status) { this.status = Objects.requireNonNull(status); return this; }
        public ThoughtBuilder metadata(Map<String, Object> metadata) {
            this.metadata.clear();
            this.metadata.putAll(Objects.requireNonNull(metadata));
            return this; // Replace metadata
        }
        public ThoughtBuilder mergeMetadata(Map<String, Object> additionalMetadata) {
            this.metadata.putAll(Objects.requireNonNull(additionalMetadata));
            return this; // Add/overwrite metadata
        }
        public ThoughtBuilder putMetadata(String key, Object value) {
            Objects.requireNonNull(key); Objects.requireNonNull(value);
            // Validate type before adding
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean ||
                    (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)))) {
                throw new IllegalArgumentException("Invalid metadata value type for key '" + key + "': " + value.getClass().getName());
            }
            // Ensure lists are immutable copies
            this.metadata.put(key, (value instanceof List<?> listValue) ? List.copyOf(listValue) : value);
            return this;
        }

        /** Builds the immutable Thought object, applying final checks and defaults. */
        public Thought build() {
            // Ensure required fields are set
            Objects.requireNonNull(id, "ID cannot be null");
            Objects.requireNonNull(role, "Role cannot be null");
            Objects.requireNonNull(content, "Content cannot be null");
            Objects.requireNonNull(belief, "Belief cannot be null");
            Objects.requireNonNull(status, "Status cannot be null");

            // Update timestamp and ensure core metadata types are correct
            metadata.put("last_updated_timestamp", System.currentTimeMillis());
            metadata.computeIfPresent("retry_count", (k, v) -> (v instanceof Number n) ? n.intValue() : 0);
            metadata.computeIfPresent("creation_timestamp", (k, v) -> (v instanceof Number n) ? n.longValue() : System.currentTimeMillis());

            // Return the immutable Thought with an immutable copy of metadata
            return new Thought(id, role, content, belief, status, Map.copyOf(this.metadata));
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
        Collection<Thought> load(String path); // Throws PersistenceException on failure
    }

    interface StoreNotifier {
        enum ChangeType { ADD, UPDATE, REMOVE }
        void notifyChange(Thought thought, ChangeType type);
    }

    interface TextParserService { List<Thought> parse(String text); }
    interface LlmService { String generateText(String prompt); } // Simplified LLM interaction
    interface ThoughtGenerator { List<Thought> generate(Term prompt, Map<Variable, Term> bindings, String parentId); }

    // --- Helper Class and Functions ---
    static class Helpers {
        private static final Random RANDOM = new Random(); // Reusable random instance

        /** Generates a standard UUID v4 string. */
        public static String generateUUID() { return UUID.randomUUID().toString(); }

        /** Performs standard unification with occurs check. Returns substitution map or throws UnificationException. */
        public static Map<Variable, Term> unify(Term term1, Term term2) throws UnificationException {
            return unifyRecursive(term1, term2, Collections.emptyMap());
        }

        private static Map<Variable, Term> unifyRecursive(Term term1, Term term2, Map<Variable, Term> substitution) throws UnificationException {
            Term t1 = applySubstitution(term1, substitution); // Resolve current bindings
            Term t2 = applySubstitution(term2, substitution);

            if (t1.equals(t2)) return substitution; // Already unified
            if (t1 instanceof Variable v1) return bindVariable(v1, t2, substitution); // Bind var t1
            if (t2 instanceof Variable v2) return bindVariable(v2, t1, substitution); // Bind var t2

            // Unify Structures
            if (t1 instanceof Structure s1 && t2 instanceof Structure s2) {
                if (!s1.name().equals(s2.name()) || s1.args().size() != s2.args().size()) {
                    throw new UnificationException("Structure mismatch: " + s1 + " vs " + s2);
                }
                Map<Variable, Term> currentSub = substitution;
                for (int i = 0; i < s1.args().size(); i++) {
                    currentSub = unifyRecursive(s1.args().get(i), s2.args().get(i), currentSub); // Unify args recursively
                }
                return currentSub;
            }

            // Unify Lists
            if (t1 instanceof ListTerm l1 && t2 instanceof ListTerm l2) {
                if (l1.elements().size() != l2.elements().size()) {
                    throw new UnificationException("List length mismatch: " + l1 + " vs " + l2);
                }
                Map<Variable, Term> currentSub = substitution;
                for (int i = 0; i < l1.elements().size(); i++) {
                    currentSub = unifyRecursive(l1.elements().get(i), l2.elements().get(i), currentSub); // Unify elements
                }
                return currentSub;
            }

            // Terms are different and not variables/compatible structures/lists
            throw new UnificationException("Cannot unify incompatible terms: " + t1 + " and " + t2);
        }

        // Bind variable to term, performing occurs check
        private static Map<Variable, Term> bindVariable(Variable var, Term term, Map<Variable, Term> substitution) throws UnificationException {
            if (substitution.containsKey(var)) { // If var already bound
                return unifyRecursive(substitution.get(var), term, substitution); // Unify its value with the term
            }
            if (term instanceof Variable vTerm && substitution.containsKey(vTerm)) { // If term is a bound var
                return unifyRecursive(var, substitution.get(vTerm), substitution); // Unify var with term's value
            }
            if (occursCheck(var, term, substitution)) { // Occurs Check
                throw new UnificationException("Occurs check failed: " + var + " in " + term);
            }
            // Create new substitution map with the binding added
            Map<Variable, Term> newSubstitution = new HashMap<>(substitution);
            newSubstitution.put(var, term);
            return Map.copyOf(newSubstitution); // Return immutable map
        }

        // Checks if variable 'var' occurs within 'term' under the given 'substitution'
        private static boolean occursCheck(Variable var, Term term, Map<Variable, Term> substitution) {
            Term resolvedTerm = applySubstitution(term, substitution); // Check against resolved term
            if (var.equals(resolvedTerm)) return true; // Direct match
            // Recursively check within structures and lists
            return switch (resolvedTerm) {
                case Structure s -> s.args().stream().anyMatch(arg -> occursCheck(var, arg, substitution));
                case ListTerm l -> l.elements().stream().anyMatch(el -> occursCheck(var, el, substitution));
                default -> false; // Atom, Number, or unbound Variable cannot contain var
            };
        }

        /** Recursively applies a substitution map to a term, returning a new term. */
        public static Term applySubstitution(Term term, Map<Variable, Term> substitution) {
            if (substitution.isEmpty()) return term; // No substitution needed

            return switch (term) {
                case Variable v -> substitution.getOrDefault(v, v); // Return binding or variable itself if not bound
                case Structure s -> {
                    List<Term> substitutedArgs = s.args().stream()
                            .map(arg -> applySubstitution(arg, substitution))
                            .toList(); // Use toList for concise immutable list
                    // Avoid creating new object if args didn't change
                    yield s.args().equals(substitutedArgs) ? s : new Structure(s.name(), substitutedArgs);
                }
                case ListTerm l -> {
                    List<Term> substitutedElements = l.elements().stream()
                            .map(el -> applySubstitution(el, substitution))
                            .toList();
                    yield l.elements().equals(substitutedElements) ? l : new ListTerm(substitutedElements);
                }
                case Atom a -> a; // Atoms are constants
                case NumberTerm n -> n; // Numbers are constants
            };
        }

        /** Applies substitution and throws UnboundVariableException if any variables remain. */
        public static Term applySubstitutionFully(Term term, Map<Variable, Term> substitution) throws UnboundVariableException {
            Term substituted = applySubstitution(term, substitution);
            Variable unbound = findFirstVariable(substituted); // Check for remaining variables
            if (unbound != null) throw new UnboundVariableException(unbound);
            return substituted;
        }

        // Helper to find the first unbound variable in a term (post-substitution)
        private static Variable findFirstVariable(Term term) {
            return switch (term) {
                case Variable v -> v; // Found an unbound variable
                case Structure s -> s.args().stream().map(Helpers::findFirstVariable).filter(Objects::nonNull).findFirst().orElse(null);
                case ListTerm l -> l.elements().stream().map(Helpers::findFirstVariable).filter(Objects::nonNull).findFirst().orElse(null);
                default -> null; // Atom, Number, fully substituted composite
            };
        }

        /** Samples an item from a list of weighted items. Returns Optional.empty if list is empty or weights sum to zero/invalid. */
        public static <T> Optional<T> sampleWeighted(List<Map.Entry<Double, T>> itemsWithScores) {
            // Filter out items with non-positive or non-finite scores
            List<Map.Entry<Double, T>> validItems = itemsWithScores.stream()
                    .filter(entry -> entry.getKey() > 0 && Double.isFinite(entry.getKey()))
                    .toList();
            if (validItems.isEmpty()) return Optional.empty();

            double totalWeight = validItems.stream().mapToDouble(Map.Entry::getKey).sum();
            if (totalWeight <= 0) return Optional.empty(); // Should not happen after filter, but safe check

            double randomValue = RANDOM.nextDouble() * totalWeight; // Use instance random
            double cumulativeWeight = 0.0;
            for (Map.Entry<Double, T> entry : validItems) {
                cumulativeWeight += entry.getKey();
                if (randomValue < cumulativeWeight) {
                    return Optional.of(entry.getValue()); // Found item
                }
            }
            // Fallback due to potential floating point inaccuracies, return last item
            return Optional.of(validItems.getLast().getValue());
        }

        /** Parses a string into a Term. Basic implementation, assumes clean input. */
        public static Term parseTerm(String input) throws IllegalArgumentException {
            input = input.trim();
            if (input.isEmpty()) throw new IllegalArgumentException("Cannot parse empty string to Term");

            // Try parsing as Number
            try { return new NumberTerm(Double.parseDouble(input)); } catch (NumberFormatException ignored) {}

            // Try parsing as List [el1, el2]
            if (input.startsWith("[") && input.endsWith("]")) {
                String content = input.substring(1, input.length() - 1).trim();
                if (content.isEmpty()) return new ListTerm(Collections.emptyList());
                // Basic split by comma - caution: fails with nested structures/lists containing commas
                return new ListTerm(Arrays.stream(content.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Helpers::parseTerm) // Recursive call
                        .toList());
            }

            // Try parsing as Structure name(arg1, arg2)
            if (input.matches("^[a-z_][\\w]*\\(.*\\)$")) { // Check for name(...) pattern
                int openParen = input.indexOf('(');
                String name = input.substring(0, openParen).trim();
                String argsContent = input.substring(openParen + 1, input.length() - 1).trim();
                if (argsContent.isEmpty()) return new Structure(name, Collections.emptyList());
                // Basic split by comma - same caution as above
                return new Structure(name, Arrays.stream(argsContent.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Helpers::parseTerm) // Recursive call
                        .toList());
            }

            // Try parsing as Variable (Uppercase or starts with _)
            if (input.matches("^[_A-Z][_a-zA-Z0-9]*$")) {
                return new Variable(input);
            }

            // Assume Atom (lowercase start, alphanumeric/underscore)
            if (input.matches("^[a-z_][\\w]*$")) {
                return new Atom(input);
            }

            throw new IllegalArgumentException("Cannot parse string into a valid Term: " + input);
        }
    }

    // --- Component Implementations ---

    /** In-memory ThoughtStore using ConcurrentHashMap for thread-safe access. */
    static class InMemoryThoughtStore implements ThoughtStore {
        private final ConcurrentMap<String, Thought> thoughts = new ConcurrentHashMap<>();
        private final StoreNotifier notifier;

        InMemoryThoughtStore(StoreNotifier notifier) { this.notifier = Objects.requireNonNull(notifier); }

        @Override public Optional<Thought> getThought(String id) { return Optional.ofNullable(thoughts.get(id)); }
        @Override public Collection<Thought> getAllThoughts() { return List.copyOf(thoughts.values()); } // Return immutable copy
        @Override public void clear() { thoughts.clear(); } // Note: No notification for clear

        @Override public void addThought(Thought thought) {
            Objects.requireNonNull(thought);
            // Use putIfAbsent for safety, only notify if actually added
            if (thoughts.putIfAbsent(thought.id(), thought) == null) {
                notifier.notifyChange(thought, StoreNotifier.ChangeType.ADD);
            } else {
                System.err.println("WARN: Attempted to add thought with existing ID: " + thought.id().substring(0, 8));
            }
        }

        /** Atomically updates a thought if the oldThought matches the current value. */
        @Override public boolean updateThought(Thought oldThought, Thought newThought) {
            Objects.requireNonNull(oldThought); Objects.requireNonNull(newThought);
            if (!oldThought.id().equals(newThought.id())) return false; // ID mismatch

            // Use ConcurrentMap's atomic replace method for optimistic locking
            boolean updated = thoughts.replace(newThought.id(), oldThought, newThought);
            if (updated) notifier.notifyChange(newThought, StoreNotifier.ChangeType.UPDATE);
            // else { System.err.printf("DEBUG: Atomic update failed for %s (likely changed since read)%n", newThought.id().substring(0,8)); }
            return updated;
        }

        @Override public boolean removeThought(String id) {
            Thought removed = thoughts.remove(id);
            if (removed != null) {
                notifier.notifyChange(removed, StoreNotifier.ChangeType.REMOVE);
                return true;
            }
            return false;
        }

        /** Samples a PENDING thought (excluding META_THOUGHTs) based on belief score. */
        @Override public Optional<Thought> samplePendingThought() {
            List<Map.Entry<Double, Thought>> pendingEligible = thoughts.values().stream()
                    .filter(t -> t.status() == Status.PENDING && t.role() != Role.META_THOUGHT)
                    .map(t -> Map.entry(t.belief().score(), t)) // Pair score with thought
                    .toList();
            return Helpers.sampleWeighted(pendingEligible);
        }

        /** Returns a list of non-FAILED META_THOUGHTs. */
        @Override public List<Thought> getMetaThoughts() {
            return thoughts.values().stream()
                    .filter(t -> t.role() == Role.META_THOUGHT && t.status() != Status.FAILED)
                    .toList();
        }

        @Override public List<Thought> findThoughtsByParentId(String parentId) {
            Objects.requireNonNull(parentId);
            return thoughts.values().stream()
                    .filter(t -> parentId.equals(t.metadata().get("parent_id")))
                    .toList();
        }
    }

    /** Simple StoreNotifier implementation that prints changes to the console. */
    static class ConsoleNotifier implements StoreNotifier {
        @Override public void notifyChange(Thought thought, ChangeType type) {
            // Print concise info to reduce log noise
            System.out.printf("Store Notify: %s - %s (%s)%n", type, thought.id().substring(0, 8), thought.role());
        }
    }

    /** Persistence implementation using Java Serialization. */
    static class FilePersistenceService implements PersistenceService {
        @Override
        public void save(Collection<Thought> thoughts, String path) {
            Path filePath = Paths.get(path);
            try {
                // Ensure parent directory exists
                Files.createDirectories(filePath.getParent());
                // Use try-with-resources for automatic stream closing
                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath.toFile()))) {
                    oos.writeObject(new ArrayList<>(thoughts)); // Serialize as ArrayList (Serializable)
                    System.out.println("Saved " + thoughts.size() + " thoughts to " + path);
                }
            } catch (IOException e) {
                throw new PersistenceException("Failed to save state to " + path, e);
            }
        }

        @Override
        public Collection<Thought> load(String path) {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) {
                throw new PersistenceException("Load failed: File not found at " + path, null); // Throw if file missing
            }
            // Use try-with-resources
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath.toFile()))) {
                Object loadedObject = ois.readObject();
                // Check if the loaded object is a List of Thoughts
                if (loadedObject instanceof List<?> list && list.stream().allMatch(Thought.class::isInstance)) {
                    @SuppressWarnings("unchecked") Collection<Thought> thoughts = (Collection<Thought>) list;
                    System.out.println("Loaded " + thoughts.size() + " thoughts from " + path);
                    return thoughts;
                } else {
                    throw new PersistenceException("Loaded file format is incorrect (expected List<Thought>). Found: " + (loadedObject == null ? "null" : loadedObject.getClass().getName()), null);
                }
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                throw new PersistenceException("Failed to load or deserialize state from " + path, e);
            }
        }
    }

    /** Basic Text Parser: Parses each line as a Term, creates GOAL Thought. */
    static class BasicTextParser implements TextParserService {
        @Override
        public List<Thought> parse(String text) {
            return Arrays.stream(text.split("\\r?\\n")) // Split by newline
                    .map(String::trim).filter(line -> !line.isEmpty())
                    .map(line -> {
                        try {
                            // Assume each line is a Term, create a GOAL thought
                            return Thought.create(Role.GOAL, Helpers.parseTerm(line), Belief.DEFAULT_POSITIVE);
                        } catch (IllegalArgumentException e) {
                            System.err.println("WARN: Input line failed Term parsing, creating GOAL with Atom content: '" + line + "' (" + e.getMessage() + ")");
                            // Fallback: Create a GOAL thought with the raw line as an Atom
                            return Thought.create(Role.GOAL, new Atom(line.replaceAll("[^a-zA-Z0-9_]", "_")), Belief.DEFAULT_POSITIVE); // Sanitize atom name
                        }
                    })
                    .toList();
        }
    }

    /** Placeholder LLM Service: Returns mock responses based on prompt content. */
    static class MockLlmService implements LlmService {
        private final String apiEndpoint; private final String apiKey; // Currently unused
        MockLlmService(String apiEndpoint, String apiKey) { this.apiEndpoint = apiEndpoint; this.apiKey = apiKey; }

        @Override public String generateText(String prompt) {
            System.out.println("LLM Call (Mock): Endpoint=" + apiEndpoint + ", Prompt='" + prompt + "'");
            String goal = extractGoalContent(prompt); // Helper to get context

            if (prompt.contains("Decompose goal:")) {
                // Simulate decomposition into STRATEGY thoughts
                return """
                       add_thought(STRATEGY, execute_strategy(step1_for_%s), default_positive)
                       add_thought(STRATEGY, execute_strategy(step2_for_%s), default_positive)
                       """.formatted(goal, goal).trim();
            } else if (prompt.contains("Execute goal:")) {
                // Simulate direct execution result
                return "LLM completed task: " + goal;
            } else if (prompt.contains("generate_meta_thought")) {
                // Simulate generating a new META_THOUGHT definition
                return "add_thought(META_THOUGHT, meta_def(trigger(new_condition), action(new_effect)), default_positive)";
            }
            // Default response
            return "Mock response for: " + goal;
        }

        // Simple helper to extract content after the last colon or return a placeholder
        private String extractGoalContent(String prompt) {
            int lastColon = prompt.lastIndexOf(':');
            return (lastColon >= 0 ? prompt.substring(lastColon + 1).trim() : prompt)
                    .replaceAll("\\W+", "_"); // Sanitize for use in mock responses
        }
    }

    /** Generates new Thoughts, often using an LLM service. */
    static class BasicThoughtGenerator implements ThoughtGenerator {
        private final LlmService llmService;
        BasicThoughtGenerator(LlmService llmService) { this.llmService = Objects.requireNonNull(llmService); }

        @Override public List<Thought> generate(Term promptTerm, Map<Variable, Term> bindings, String parentId) {
            String promptString;
            try {
                // Apply bindings fully to the prompt before generating text
                promptString = "Generate thoughts task: " + Helpers.applySubstitutionFully(promptTerm, bindings).toString();
            } catch (UnboundVariableException e) {
                System.err.println("WARN: Cannot generate thoughts, prompt term has unbound variable: " + e.getMessage());
                return Collections.emptyList(); // Cannot generate if prompt is incomplete
            }

            String llmResponse = llmService.generateText(promptString);
            List<Thought> generatedThoughts = new ArrayList<>();

            // Parse each line of the LLM response, expecting 'add_thought(...)' format
            for (String line : llmResponse.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("add_thought(") && line.endsWith(")")) {
                    try {
                        String argsString = line.substring("add_thought(".length(), line.length() - 1);
                        // Basic comma splitting - fragile! Needs improvement for robust parsing.
                        String[] parts = argsString.split(",", 3); // Split into Role, ContentStr, BeliefStr
                        if (parts.length == 3) {
                            Role role = Role.valueOf(parts[0].trim().toUpperCase());
                            Term content = Helpers.parseTerm(parts[1].trim()); // Parse content string
                            Belief belief = parseBeliefString(parts[2].trim()); // Parse belief string
                            generatedThoughts.add(new ThoughtBuilder()
                                    .role(role).content(content).belief(belief)
                                    .putMetadata("parent_id", parentId)
                                    .putMetadata("provenance", List.of("LLM_GENERATED")) // Add provenance
                                    .build());
                        } else logParseWarning(line, "Incorrect argument count (expected 3)");
                    } catch (Exception e) { logParseWarning(line, e.getMessage()); } // Catch parsing errors
                } else if (!line.isEmpty()) logParseWarning(line, "Does not match add_thought(R, T, B) format");
            }
            return generatedThoughts;
        }

        private void logParseWarning(String line, String reason) { System.err.println("WARN: Could not parse generated thought line (" + reason + "): " + line); }

        // Parses belief string representations (e.g., 'default_positive', 'B(1.0, 0.5)')
        static Belief parseBeliefString(String beliefStr) {
            return switch (beliefStr.toLowerCase()) {
                case "default_positive" -> Belief.DEFAULT_POSITIVE;
                case "default_uncertain" -> Belief.DEFAULT_UNCERTAIN;
                case "default_low_confidence" -> Belief.DEFAULT_LOW_CONFIDENCE;
                // Attempt to parse B(p, n) format
                case String s when s.matches("(?i)b\\(\\s*\\d+(\\.\\d+)?\\s*,\\s*\\d+(\\.\\d+)?\\s*\\)") -> {
                    try {
                        String[] parts = s.substring(2, s.length() - 1).split(",");
                        yield new Belief(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
                    } catch (Exception e) {
                        System.err.println("WARN: Failed to parse Belief format B(p,n): '" + beliefStr + "', using default.");
                        yield Belief.DEFAULT_UNCERTAIN; // Fallback
                    }
                }
                default -> { System.err.println("WARN: Unknown belief string: '" + beliefStr + "', using default."); yield Belief.DEFAULT_UNCERTAIN; }
            };
        }
    }

    /** Handles unification of an active Thought against available META_THOUGHTs and samples a match. */
    static class Unifier {

        /** Result of unification attempt, holding matched META_THOUGHT and substitutions. */
        record UnificationResult(Thought matchedMetaThought, Map<Variable, Term> substitutionMap) {
            static UnificationResult noMatch() { return new UnificationResult(null, Collections.emptyMap()); }
            boolean hasMatch() { return matchedMetaThought != null; }
        }

        /** Finds META_THOUGHTs matching the active thought's content, then samples one based on belief score. */
        public UnificationResult findAndSampleMatchingMeta(Thought activeThought, List<Thought> metaThoughts) {
            List<Map.Entry<Double, UnificationResult>> potentialMatches = new ArrayList<>();

            for (Thought meta : metaThoughts) {
                // Basic check: META_THOUGHT structure should be meta_def(Target, Action)
                if (!(meta.content() instanceof Structure metaDef &&
                        "meta_def".equals(metaDef.name) && metaDef.args.size() == 2)) {
                    System.err.printf("WARN: Skipping invalid META_THOUGHT format: %s (%s)%n", meta.id().substring(0, 8), meta.content());
                    continue;
                }
                Term targetTerm = metaDef.args().get(0); // The pattern to match against active thought's content

                // Optional Role Check (if specified in META's metadata)
                Optional<String> targetRoleName = meta.getMetadata("target_role", String.class);
                if (targetRoleName.isPresent() && !targetRoleName.get().equalsIgnoreCase(activeThought.role().name())) {
                    // System.out.printf("DEBUG: Skipping META %s, role mismatch (target: %s, active: %s)%n", meta.id().substring(0, 8), targetRoleName.get(), activeThought.role());
                    continue; // Role doesn't match
                }

                // --- Attempt Unification ---
                try {
                    Map<Variable, Term> substitution = Helpers.unify(activeThought.content(), targetTerm);
                    // Successful unification! Add to potential matches with META's belief score.
                    potentialMatches.add(Map.entry(meta.belief().score(), new UnificationResult(meta, Map.copyOf(substitution))));
                    // System.out.printf("DEBUG: Potential match: META %s (Score: %.2f) unified with Active %s%n", meta.id().substring(0, 8), meta.belief().score(), activeThought.id().substring(0, 8));
                } catch (UnificationException e) {
                    // No match, ignore and continue to next META thought
                    // System.out.printf("DEBUG: Unification failed: META %s vs Active %s (%s)%n", meta.id().substring(0, 8), activeThought.id().substring(0, 8), e.getMessage());
                } catch (Exception e) { // Catch unexpected errors during unification
                    System.err.printf("ERROR: Unexpected error during unification for META %s: %s%n", meta.id().substring(0, 8), e.getMessage());
                    e.printStackTrace(); // Log stack trace for debugging
                }
            }

            // --- Sample from potential matches ---
            // System.out.printf("DEBUG: Found %d potential META matches for Active %s%n", potentialMatches.size(), activeThought.id().substring(0, 8));
            return Helpers.sampleWeighted(potentialMatches).orElse(UnificationResult.noMatch());
        }
    }

    /** Executes the action part of a matched META_THOUGHT. */
    static class ActionExecutor {
        private final ThoughtStore thoughtStore;
        private final ThoughtGenerator thoughtGenerator;
        private final LlmService llmService;

        ActionExecutor(ThoughtStore store, ThoughtGenerator generator, LlmService llm) {
            this.thoughtStore = store; this.thoughtGenerator = generator; this.llmService = llm;
        }

        /** Extracts, resolves, and executes the action term from the matched META_THOUGHT. */
        public void execute(Thought activeThought, Thought metaThought, Map<Variable, Term> substitutionMap) {
            // 1. Extract the Action Term from meta_def(Target, Action)
            Term actionTerm = extractActionTerm(metaThought);

            // 2. Apply substitution to the Action Term (variables bound during unification)
            Term resolvedActionTerm = Helpers.applySubstitution(actionTerm, substitutionMap);
            // Note: We don't use applySubstitutionFully here, as some actions might need to
            // handle variables internally or expect them to be bound by sub-actions (like sequence).
            // Individual actions must validate their arguments are fully resolved if required.

            // 3. Execute the resolved action
            System.out.printf("Action: Executing %s for %s%n", resolvedActionTerm, activeThought.id().substring(0, 8));
            executeResolvedAction(resolvedActionTerm, activeThought, substitutionMap, metaThought.id());
        }

        // Extracts the action term (second argument) from a meta_def/2 structure.
        private Term extractActionTerm(Thought metaThought) {
            if (metaThought.content() instanceof Structure(String name, List<Term> args) &&
                    "meta_def".equals(name) && args.size() == 2) {
                return args.get(1); // The action part
            }
            throw new ActionExecutionException("Invalid META_THOUGHT structure: Expected meta_def(Target, Action), found: " + metaThought.content());
        }

        // Dispatches to the appropriate primitive action handler based on the resolved action term.
        private void executeResolvedAction(Term action, Thought contextThought, Map<Variable, Term> substitutionMap, String metaThoughtId) {
            if (!(action instanceof Structure(String name, List<Term> args))) {
                // Allow 'noop' atom as a valid action
                if (action instanceof Atom(String atomName) && "noop".equals(atomName)) return;
                throw new ActionExecutionException("Action term must be a Structure or 'noop' Atom, got: " + action);
            }

            try {
                // --- Primitive Action Dispatch ---
                switch (name) {
                    case "add_thought"             -> executeAddThought(args, contextThought, substitutionMap, metaThoughtId);
                    case "set_status"              -> executeSetStatus(args, contextThought, substitutionMap, metaThoughtId);
                    case "set_belief"              -> executeSetBelief(args, contextThought, substitutionMap, metaThoughtId);
                    case "check_parent_completion" -> executeCheckParentCompletion(args, contextThought, substitutionMap, metaThoughtId);
                    case "generate_thoughts"       -> executeGenerateThoughts(args, contextThought, substitutionMap, metaThoughtId);
                    case "sequence"                -> executeSequence(args, contextThought, substitutionMap, metaThoughtId);
                    case "call_llm"                -> executeCallLlm(args, contextThought, substitutionMap, metaThoughtId);
                    case "noop"                    -> { /* Explicit no-operation */ }
                    default -> throw new ActionExecutionException("Unknown primitive action name: " + name);
                }
            } catch (UnboundVariableException uve) {
                // If a primitive required a fully resolved arg but got a variable
                throw new ActionExecutionException("Action '" + name + "' argument resolution failed: " + uve.getMessage(), uve);
            } catch (ActionExecutionException aee) {
                throw aee; // Re-throw specific action errors
            } catch (Exception e) { // Catch unexpected errors during action logic
                throw new ActionExecutionException("Unexpected error executing action '" + name + "': " + e.getMessage(), e);
            }
        }

        // --- Primitive Action Implementations ---

        // add_thought(RoleAtom, ContentTerm, BeliefTerm)
        private void executeAddThought(List<Term> args, Thought context, Map<Variable, Term> sub, String metaId) {
            if (args.size() != 3) throw new ActionExecutionException("add_thought requires 3 arguments: Role, Content, Belief");
            // Ensure args are fully resolved before using them
            Role role = expectEnumValue(Helpers.applySubstitutionFully(args.get(0), sub), Role.class, "Role");
            Term content = Helpers.applySubstitutionFully(args.get(1), sub); // Content can be any term
            Belief belief = expectBelief(Helpers.applySubstitutionFully(args.get(2), sub), "Belief");

            Thought newThought = new ThoughtBuilder()
                    .role(role).content(content).belief(belief)
                    .putMetadata("parent_id", context.id())
                    .putMetadata("provenance", List.of(metaId))
                    .build();
            thoughtStore.addThought(newThought); // Notifier handles logging add
        }

        // set_status(StatusAtom) - applies to context thought
        private void executeSetStatus(List<Term> args, Thought context, Map<Variable, Term> sub, String metaId) {
            if (args.size() != 1) throw new ActionExecutionException("set_status requires 1 argument: Status");
            Status newStatus = expectEnumValue(Helpers.applySubstitutionFully(args.getFirst(), sub), Status.class, "Status");
            if (newStatus == Status.ACTIVE) throw new ActionExecutionException("Cannot set status directly to ACTIVE");

            // Retrieve the LATEST version for atomic update
            Thought current = thoughtStore.getThought(context.id()).orElse(null);
            if (current == null || current.status() != Status.ACTIVE) { // Must be ACTIVE to change status via action
                System.err.printf("WARN: set_status skipped for %s. Thought not found or no longer ACTIVE (status: %s).%n", context.id().substring(0,8), current!=null? current.status() : "null");
                return;
            }
            if (current.status() == newStatus) return; // Already in target status

            Thought updated = current.toBuilder()
                    .status(newStatus)
                    .putMetadata("provenance", addToProvenance(current.metadata(), metaId))
                    .build();

            if (!thoughtStore.updateThought(current, updated)) { // Atomic update
                System.err.println("WARN: Failed atomic update for set_status on " + context.id().substring(0, 8) + " to " + newStatus + ". Thought likely changed concurrently.");
            } else {
                System.out.printf("Action: Set status of %s to %s%n", context.id().substring(0, 8), newStatus);
            }
        }

        // set_belief(TypeAtom) - applies to context thought (Type: POSITIVE/NEGATIVE)
        private void executeSetBelief(List<Term> args, Thought context, Map<Variable, Term> sub, String metaId) {
            if (args.size() != 1) throw new ActionExecutionException("set_belief requires 1 argument: Belief Type (Atom: POSITIVE/NEGATIVE)");
            Atom typeAtom = expectAtom(Helpers.applySubstitutionFully(args.getFirst(), sub), "Belief Type");
            boolean positiveSignal = switch (typeAtom.name().toUpperCase()) {
                case "POSITIVE" -> true;
                case "NEGATIVE" -> false;
                default -> throw new ActionExecutionException("Invalid belief type: '" + typeAtom.name() + "'. Use POSITIVE or NEGATIVE.");
            };

            Thought current = thoughtStore.getThought(context.id()).orElse(null);
            if (current == null || current.status() != Status.ACTIVE) { // Must be ACTIVE
                System.err.printf("WARN: set_belief skipped for %s. Thought not found or no longer ACTIVE (status: %s).%n", context.id().substring(0,8), current!=null? current.status() : "null");
                return;
            }

            Belief newBelief = current.belief().update(positiveSignal);
            if (newBelief.equals(current.belief())) return; // No change

            Thought updated = current.toBuilder()
                    .belief(newBelief)
                    .putMetadata("provenance", addToProvenance(current.metadata(), metaId))
                    .build();

            if (!thoughtStore.updateThought(current, updated)) { // Atomic update
                System.err.println("WARN: Failed atomic update for set_belief on " + context.id().substring(0, 8));
            } else {
                System.out.printf("Action: Updated belief of %s based on %s (New: %s)%n", context.id().substring(0, 8), typeAtom.name(), newBelief);
            }
        }

        // check_parent_completion(CheckTypeAtom, StatusIfCompleteAtom, RecursiveAtom) - applies to context thought's parent
        private void executeCheckParentCompletion(List<Term> args, Thought context, Map<Variable, Term> sub, String metaId) {
            if (args.size() != 3) throw new ActionExecutionException("check_parent_completion requires 3 arguments: CheckType, StatusIfComplete, Recursive (Atoms)");
            Atom checkTypeAtom = expectAtom(Helpers.applySubstitutionFully(args.get(0), sub), "CheckType");
            Status statusIfComplete = expectEnumValue(Helpers.applySubstitutionFully(args.get(1), sub), Status.class, "StatusIfComplete");
            Atom recursiveAtom = expectAtom(Helpers.applySubstitutionFully(args.get(2), sub), "Recursive");
            boolean recursive = "TRUE".equalsIgnoreCase(recursiveAtom.name()); // Currently unused

            if (recursive) System.err.println("WARN: Recursive check_parent_completion not implemented.");

            Optional<String> parentIdOpt = context.getMetadata("parent_id", String.class);
            if (parentIdOpt.isEmpty()) return; // No parent to check
            String parentId = parentIdOpt.get();

            // Get parent atomically
            Thought parent = thoughtStore.getThought(parentId).orElse(null);
            if (parent == null || parent.status() != Status.WAITING_CHILDREN) return; // Parent not found or not waiting

            // Check children statuses atomically relative to the parent read
            List<Thought> children = thoughtStore.findThoughtsByParentId(parentId);
            // Need the *current* status of the context thought for the check
            Thought currentContext = thoughtStore.getThought(context.id()).orElse(context); // Get latest version of self
            Status contextStatus = currentContext.status();

            // Check completion condition
            boolean allComplete = children.stream().allMatch(child -> {
                Status status = child.id().equals(context.id()) ? contextStatus : child.status(); // Use latest status for self
                return switch (checkTypeAtom.name().toUpperCase()) {
                    case "ALL_DONE" -> status == Status.DONE;
                    case "ALL_TERMINAL" -> status == Status.DONE || status == Status.FAILED;
                    default -> throw new ActionExecutionException("Unknown checkType: " + checkTypeAtom.name());
                };
            });

            if (allComplete) {
                // System.out.printf("Action: All children of %s (%s) complete. Attempting to set parent status to %s.%n", parentId.substring(0, 8), checkTypeAtom.name(), statusIfComplete);
                // Re-fetch parent for atomic update check
                Thought currentParent = thoughtStore.getThought(parentId).orElse(null);
                // Only update if parent is *still* WAITING_CHILDREN
                if (currentParent != null && currentParent.status() == Status.WAITING_CHILDREN) {
                    Thought updatedParent = currentParent.toBuilder()
                            .status(statusIfComplete)
                            .putMetadata("provenance", addToProvenance(currentParent.metadata(), metaId))
                            .build();
                    if (!thoughtStore.updateThought(currentParent, updatedParent)) { // Atomic update
                        System.err.println("WARN: Failed atomic update for parent completion on " + parentId.substring(0,8) + ". Parent likely changed concurrently.");
                    } else {
                        System.out.printf("Action: Parent %s status set to %s by %s.%n", parentId.substring(0, 8), statusIfComplete, context.id().substring(0, 8));
                    }
                } else {
                    // System.out.printf("DEBUG: Parent %s no longer WAITING_CHILDREN when completion check passed (%s).%n", parentId.substring(0,8), currentParent != null ? currentParent.status() : "null");
                }
            }
        }

        // generate_thoughts(PromptTerm)
        private void executeGenerateThoughts(List<Term> args, Thought context, Map<Variable, Term> sub, String metaId) {
            if (args.size() != 1) throw new ActionExecutionException("generate_thoughts requires 1 argument: Prompt Term");
            // Prompt term might contain variables bound during unification, pass substitution map to generator
            Term promptTerm = args.getFirst(); // Don't fully resolve here, let generator handle it

            List<Thought> generated = thoughtGenerator.generate(promptTerm, sub, context.id());
            // System.out.printf("Action: generate_thoughts (using prompt: %s) by %s generated %d thoughts.%n", promptTerm, context.id().substring(0, 8), generated.size());
            for (Thought t : generated) {
                // Add provenance linking to this META thought
                Thought finalThought = t.toBuilder().putMetadata("provenance", addToProvenance(t.metadata(), metaId)).build();
                thoughtStore.addThought(finalThought);
            }
        }

        // sequence(ListTerm actions)
        private void executeSequence(List<Term> args, Thought context, Map<Variable, Term> sub, String metaId) {
            if (args.size() != 1 || !(args.getFirst() instanceof ListTerm actionList)) { // Use pattern variable binding
                throw new ActionExecutionException("sequence requires 1 ListTerm argument");
            }

            // System.out.printf("Action: Executing sequence for %s (%d steps)%n", context.id().substring(0, 8), actionList.elements().size());
            int step = 1;
            for (Term actionStep : actionList.elements()) {
                // IMPORTANT: Re-fetch context thought before *each* step to check if it's still ACTIVE
                Thought currentContext = thoughtStore.getThought(context.id()).orElse(null);
                if (currentContext == null || currentContext.status() != Status.ACTIVE) {
                    System.out.printf("Action: Sequence for %s interrupted at step %d: Context thought no longer ACTIVE (Status: %s)%n",
                            context.id().substring(0, 8), step, (currentContext != null ? currentContext.status() : "REMOVED"));
                    return; // Stop sequence execution
                }

                // System.out.printf("  Sequence step %d/%d for %s: %s%n", step, actionList.elements().size(), context.id().substring(0, 8), actionStep);
                try {
                    // Execute sub-action. Pass original substitutionMap, sub-actions resolve as needed.
                    // Use the *current* context state for execution.
                    executeResolvedAction(actionStep, currentContext, sub, metaId);
                } catch (Exception e) {
                    // If a step fails, the whole sequence fails immediately. Re-throw to trigger failure handling.
                    System.err.printf("  Sequence failed at step %d for %s: %s%n", step, context.id().substring(0, 8), e.getMessage());
                    throw new ActionExecutionException("Sequence failed at step " + step + ": " + actionStep + ". Cause: " + e.getMessage(), e);
                }
                step++;
            }
            // System.out.printf("Action: Sequence completed for %s%n", context.id().substring(0, 8));
        }

        // call_llm(PromptTerm, ResultRoleAtom)
        private void executeCallLlm(List<Term> args, Thought context, Map<Variable, Term> sub, String metaId) {
            if (args.size() != 2) throw new ActionExecutionException("call_llm requires 2 arguments: Prompt (Term), Result Role (Atom)");
            // Resolve prompt fully before calling LLM
            Term promptTerm = Helpers.applySubstitutionFully(args.get(0), sub);
            Role resultRole = expectEnumValue(Helpers.applySubstitutionFully(args.get(1), sub), Role.class, "Result Role");
            String promptString = promptTerm.toString();

            // System.out.printf("Action: Calling LLM for %s (Prompt: %s)%n", context.id().substring(0, 8), promptString);
            String llmResultText;
            try { llmResultText = llmService.generateText(promptString); }
            catch (Exception e) { throw new ActionExecutionException("LLM call failed: " + e.getMessage(), e); }

            // Create result thought with LLM output (attempt parsing, fallback to Atom)
            Term resultContent;
            try { resultContent = Helpers.parseTerm(llmResultText); }
            catch (IllegalArgumentException e) { resultContent = new Atom(llmResultText); }

            Thought resultThought = new ThoughtBuilder()
                    .role(resultRole).content(resultContent).belief(Belief.DEFAULT_POSITIVE)
                    .putMetadata("parent_id", context.id())
                    .putMetadata("provenance", List.of(metaId, "LLM_CALL"))
                    .build();
            thoughtStore.addThought(resultThought);
            // System.out.printf("Action: call_llm created result thought %s (%s) for %s%n", resultThought.id().substring(0, 8), resultRole, context.id().substring(0, 8));
        }

        // --- Action Helper Methods ---
        private <T extends Enum<T>> T expectEnumValue(Term term, Class<T> enumClass, String desc) {
            if (term instanceof Atom atom) {
                try { return Enum.valueOf(enumClass, atom.name().toUpperCase()); }
                catch (IllegalArgumentException e) { throw new ActionExecutionException("Invalid " + desc + " value: '" + atom.name() + "'. Expected one of " + Arrays.toString(enumClass.getEnumConstants())); }
            }
            throw new ActionExecutionException("Expected Atom for " + desc + ", got: " + term);
        }
        private Atom expectAtom(Term term, String desc) {
            if (term instanceof Atom atom) return atom;
            throw new ActionExecutionException("Expected Atom for " + desc + ", got: " + term);
        }
        private NumberTerm expectNumber(Term term, String desc) {
            if (term instanceof NumberTerm number) return number;
            throw new ActionExecutionException("Expected NumberTerm for " + desc + ", got: " + term);
        }
        private Belief expectBelief(Term term, String desc) {
            return switch (term) {
                case Atom a -> parseBeliefString(a.name()); // Use helper for atoms like "default_positive"
                case Structure s when "belief".equals(s.name()) && s.args().size() == 2 ->
                        new Belief(expectNumber(s.args().get(0), "positive belief count").value(),
                                expectNumber(s.args().get(1), "negative belief count").value());
                default -> throw new ActionExecutionException("Expected Atom (belief type) or belief(Pos, Neg) Structure for " + desc + ", got: " + term);
            };
        }
        // Helper to add entry to provenance list (immutable)
        private List<String> addToProvenance(Map<String, Object> metadata, String newEntry) {
            List<String> current = Optional.ofNullable(metadata.get("provenance"))
                    .filter(List.class::isInstance).map(l -> (List<?>) l)
                    .flatMap(l -> l.stream().allMatch(String.class::isInstance) ? Optional.of(l.stream().map(String.class::cast).toList()) : Optional.<List<String>>empty())
                    .orElse(Collections.emptyList());
            List<String> updated = new ArrayList<>(current);
            updated.add(newEntry);
            // Optional: Limit provenance size if needed: e.g., return List.copyOf(updated.subList(Math.max(0, updated.size() - MAX_PROVENANCE), updated.size()));
            return List.copyOf(updated);
        }
    }

    /** Main execution loop controller. Runs the select-match-execute cycle. */
    static class ExecuteLoop implements Runnable {
        private final ThoughtStore thoughtStore;
        private final Unifier unifier;
        private final ActionExecutor actionExecutor;
        private final Configuration config;
        private final ScheduledExecutorService scheduler; // Shared scheduler
        private final ConcurrentMap<String, ScheduledFuture<?>> activeThoughtTimeouts = new ConcurrentHashMap<>();
        private volatile boolean running = false;
        private ScheduledFuture<?> cycleFuture;

        ExecuteLoop(ThoughtStore store, Unifier uni, ActionExecutor exec, Configuration cfg, ScheduledExecutorService sched) {
            this.thoughtStore = store; this.unifier = uni; this.actionExecutor = exec;
            this.config = cfg; this.scheduler = sched;
        }

        public void start() {
            if (running) return;
            running = true;
            // Schedule the main cycle execution periodically using the shared scheduler
            cycleFuture = scheduler.scheduleWithFixedDelay(this::runCycleInternal, 0, config.pollIntervalMillis(), TimeUnit.MILLISECONDS);
            System.out.println("ExecuteLoop started (polling every " + config.pollIntervalMillis() + "ms).");
        }

        public void stop() {
            if (!running) return;
            running = false;
            if (cycleFuture != null) cycleFuture.cancel(false); // Stop scheduling new cycles
            // Cancel any pending timeouts actively
            activeThoughtTimeouts.values().forEach(future -> future.cancel(true));
            activeThoughtTimeouts.clear();
            System.out.println("ExecuteLoop stopped.");
            // Note: Don't shut down the shared scheduler here, owner (CoglogEngine) does that.
        }

        @Override public void run() { runCycleInternal(); } // Allow direct invocation if needed

        // The core logic executed in each cycle
        private void runCycleInternal() {
            if (!running) return; // Check flag before proceeding

            Thought active = null; // Track the thought being processed in this cycle
            String activeId = null;

            try {
                // --- 1. Sample a PENDING Thought ---
                Optional<Thought> pendingOpt = thoughtStore.samplePendingThought();
                if (pendingOpt.isEmpty()) return; // No work available, wait for next poll

                Thought pending = pendingOpt.get();

                // --- 2. Attempt to set to ACTIVE (Atomic Update) ---
                // Re-fetch latest state before trying to activate
                Thought currentPending = thoughtStore.getThought(pending.id()).orElse(null);
                if (currentPending == null || currentPending.status() != Status.PENDING) {
                    return; // Thought changed status or was removed since sampling
                }
                active = updateStatusAtomically(currentPending, Status.ACTIVE);
                if (active == null) return; // Failed to activate (race condition), another thread/cycle got it

                activeId = active.id(); // Assign ID for timeout handling

                // --- 3. Schedule Timeout ---
                final String finalActiveId = activeId; // Final variable for lambda
                ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> handleTimeout(finalActiveId),
                        config.maxActiveDurationMillis(), TimeUnit.MILLISECONDS);
                // Store the timeout future, potentially replacing an old one (though unlikely)
                ScheduledFuture<?> previousTimeout = activeThoughtTimeouts.put(activeId, timeoutFuture);
                if (previousTimeout != null) previousTimeout.cancel(false); // Cancel any lingering old timeout

                // --- 4. Process the ACTIVE Thought ---
                // System.out.printf("%n--- Cycle Start: Processing Thought %s (%s) ---%n", activeId.substring(0, 8), active.role());
                // System.out.println("Current Status: " + ((InMemoryThoughtStore)thoughtStore).thoughts.values().stream().collect(Collectors.groupingBy(Thought::status, Collectors.counting()))); // DEBUG Status
                // System.out.println(".Active Thought: " + active);


                // --- 5. Find Matching META_THOUGHT & Unify ---
                List<Thought> metaThoughts = thoughtStore.getMetaThoughts(); // Get current meta rules
                Unifier.UnificationResult unification = unifier.findAndSampleMatchingMeta(active, metaThoughts);

                if (unification.hasMatch()) {
                    // System.out.printf("Matched META: %s (Score: %.2f)%n", unification.matchedMetaThought().id().substring(0, 8), unification.matchedMetaThought().belief().score());
                    // System.out.println("Substitution: " + unification.substitutionMap());

                    // --- 6. Execute Action ---
                    // Pass the 'active' thought state as it was when activated
                    actionExecutor.execute(active, unification.matchedMetaThought(), unification.substitutionMap());

                    // --- 7. Verify final status (action should set terminal/waiting status) ---
                    Thought finalState = thoughtStore.getThought(activeId).orElse(null);
                    if (finalState != null && finalState.status() == Status.ACTIVE) {
                        // This is usually undesirable - the action likely didn't complete its job.
                        // Rely on the timeout to eventually fail it.
                        System.err.printf("WARN: Action for thought %s completed but left status ACTIVE. Timeout will handle.%n", activeId.substring(0, 8));
                    } else if (finalState != null) {
                        // System.out.printf("Thought %s finished cycle with status: %s%n", activeId.substring(0, 8), finalState.status());
                    } // If null, it was removed (e.g., by a sequence step), which is valid.

                } else {
                    // --- No Match Found: Handle Failure ---
                    System.out.printf("No matching META_THOUGHT found for %s.%n", activeId.substring(0, 8));
                    // Handle failure directly on the 'active' state we successfully set
                    handleFailure(active, "No matching META_THOUGHT", calculateRemainingRetries(active));
                }

            } catch (Throwable e) { // Catch ALL exceptions/errors within a cycle
                System.err.println("FATAL: Uncaught exception during cycle execution: " + e.getMessage());
                e.printStackTrace();
                // Attempt to fail the thought if one was active
                if (active != null) {
                    // Fetch latest known state before failing
                    Thought currentState = thoughtStore.getThought(active.id()).orElse(active);
                    handleFailure(currentState, "Uncaught exception: " + e.getClass().getSimpleName(), calculateRemainingRetries(currentState));
                }
            } finally {
                // --- Cleanup: Cancel Timeout if thought processing finished normally ---
                if (activeId != null) {
                    ScheduledFuture<?> timeoutFuture = activeThoughtTimeouts.remove(activeId);
                    if (timeoutFuture != null) timeoutFuture.cancel(false); // Cancel if not already run/cancelled
                    // System.out.printf("--- Cycle End: Thought %s ---%n", activeId.substring(0, 8));
                }
            }
        }

        // Handles timeout event for a thought
        private void handleTimeout(String timedOutThoughtId) {
            // This might run concurrently if scheduler has multiple threads, but our scheduler is single-threaded for the loop
            activeThoughtTimeouts.remove(timedOutThoughtId); // Remove from map regardless

            Thought current = thoughtStore.getThought(timedOutThoughtId).orElse(null);
            // Only fail if it's *still* ACTIVE (it might have finished just before timeout executed)
            if (current != null && current.status() == Status.ACTIVE) {
                System.err.printf("TIMEOUT detected for thought %s after %dms.%n", timedOutThoughtId.substring(0, 8), config.maxActiveDurationMillis());
                // Handle failure directly
                handleFailure(current, "Timeout", calculateRemainingRetries(current));
            }
        }

        // Handles failure of a thought (e.g., no match, execution error, timeout)
        private void handleFailure(Thought thought, String error, int retriesLeft) {
            // Re-fetch the latest state before attempting update
            Thought currentThought = thoughtStore.getThought(thought.id()).orElse(null);
            if (currentThought == null) return; // Thought gone

            // Only handle failure if thought is currently ACTIVE or PENDING (e.g., failed before execution)
            if (currentThought.status() != Status.ACTIVE && currentThought.status() != Status.PENDING) {
                // System.out.printf("DEBUG: Failure handling skipped for %s, not ACTIVE/PENDING (Status: %s)%n", thought.id().substring(0, 8), currentThought.status());
                return;
            }

            // Prepare metadata for the failed state
            Map<String, Object> meta = new HashMap<>(currentThought.metadata());
            meta.put("error_info", error);
            int currentRetries = currentThought.getMetadata("retry_count", Integer.class).orElse(0);
            meta.put("retry_count", currentRetries + 1);

            // Determine next status (PENDING for retry, FAILED if no retries left)
            Status nextStatus = retriesLeft > 0 ? Status.PENDING : Status.FAILED;
            Belief nextBelief = currentThought.belief().update(false); // Update belief negatively

            Thought updated = currentThought.toBuilder()
                    .status(nextStatus)
                    .belief(nextBelief)
                    .metadata(Map.copyOf(meta)) // Ensure metadata map is immutable
                    .build();

            System.out.printf("Handling Failure for %s: Error='%s', Retries Left=%d, New Status=%s%n",
                    thought.id().substring(0, 8), error, retriesLeft, nextStatus);

            // Attempt atomic update from the 'currentThought' state we fetched
            if (!thoughtStore.updateThought(currentThought, updated)) {
                System.err.printf("CRITICAL: Failed atomic update during failure handling for %s. State may be inconsistent.%n", thought.id().substring(0, 8));
            }
        }

        // Calculates remaining retries based on current thought state
        private int calculateRemainingRetries(Thought thought) {
            int currentRetries = thought.getMetadata("retry_count", Integer.class).orElse(0);
            return Math.max(0, config.maxRetries() - currentRetries);
        }

        // Atomically updates thought status. Returns the updated thought or null if failed.
        private Thought updateStatusAtomically(Thought currentThought, Status newStatus) {
            if (currentThought.status() == newStatus) return currentThought; // Already in desired state

            Thought updated = currentThought.toBuilder().status(newStatus).build();
            // Use atomic updateThought
            return thoughtStore.updateThought(currentThought, updated) ? updated : null;
        }
    }

    /** Performs garbage collection on old, terminal (DONE/FAILED) thoughts. */
    static class GarbageCollector implements Runnable {
        private final ThoughtStore thoughtStore;
        private final long gcThresholdMillis;

        GarbageCollector(ThoughtStore store, long threshold) { this.thoughtStore = store; this.gcThresholdMillis = threshold; }

        @Override public void run() {
            // System.out.println("Garbage Collector: Running...");
            long now = System.currentTimeMillis();
            int initialCount = thoughtStore.getAllThoughts().size(); // May not be perfectly accurate if adds happen
            int removedCount = 0;

            // Iterate over a snapshot to avoid ConcurrentModificationException
            Collection<Thought> currentThoughts = thoughtStore.getAllThoughts();

            for (Thought thought : currentThoughts) {
                // Check if thought is terminal and old enough
                if ((thought.status() == Status.DONE || thought.status() == Status.FAILED)) {
                    long lastUpdated = thought.getMetadata("last_updated_timestamp", Long.class).orElse(0L);
                    if (lastUpdated > 0 && (now - lastUpdated > gcThresholdMillis)) {
                        // Optional: Check for active children? Not specified, keeping simple.
                        if (thoughtStore.removeThought(thought.id())) { // Attempt removal
                            removedCount++;
                            // System.out.printf("DEBUG: GC Removed %s%n", thought.id().substring(0,8));
                        }
                        // else { System.err.printf("WARN: GC failed to remove thought %s, likely already removed.%n", thought.id().substring(0,8)); }
                    }
                }
            }

            if (removedCount > 0) {
                System.out.printf("Garbage Collector: Finished. Removed %d old terminal thoughts (Approx initial: %d).%n", removedCount, initialCount);
            } // else { System.out.println("Garbage Collector: Finished. No thoughts eligible for removal."); }
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

        public void loadBootstrapMetaThoughts() {
            System.out.println("Loading Bootstrap META_THOUGHTs...");
            int initialCount = thoughtStore.getMetaThoughts().size();

            // MT-NOTE-TO-GOAL: Converts a NOTE with 'convert_note_to_goal' content
            addMeta("NOTE-TO-GOAL", Role.NOTE, // Specific target role
                    a("convert_note_to_goal"), // Target content
                    s("sequence", l(
                            s("add_thought", a("GOAL"), a("process_converted_item"), a("DEFAULT_POSITIVE")),
                            s("set_status", a("DONE"))
                    )), Belief.DEFAULT_POSITIVE, "Converts specific NOTE to a GOAL");

            // MT-GOAL-DECOMPOSE: Decomposes a GOAL using LLM
            addMeta("GOAL-DECOMPOSE", Role.GOAL,
                    s("decompose", v("GoalContent")), // Target: decompose(...)
                    s("sequence", l(
                            s("set_status", a("WAITING_CHILDREN")), // Set parent waiting
                            s("generate_thoughts", s("prompt", a("Decompose goal:"), v("GoalContent"))) // Ask LLM
                    )), Belief.DEFAULT_POSITIVE, "Decomposes GOAL via LLM");

            // MT-GOAL-EXECUTE-LLM: Executes a GOAL directly using LLM
            addMeta("GOAL-EXECUTE-LLM", Role.GOAL,
                    s("execute_via_llm", v("GoalContent")), // Target: execute_via_llm(...)
                    s("sequence", l(
                            s("call_llm", s("prompt", a("Execute goal:"), v("GoalContent")), a("OUTCOME")), // Call LLM, create OUTCOME
                            s("set_status", a("DONE")) // Mark GOAL done
                    )), Belief.DEFAULT_POSITIVE, "Executes GOAL via LLM, creates OUTCOME");

            // MT-STRATEGY-EXECUTE: Simple execution of a STRATEGY (e.g., create OUTCOME)
            addMeta("STRATEGY-EXECUTE", Role.STRATEGY,
                    s("execute_strategy", v("StrategyContent")), // Target: execute_strategy(...)
                    s("sequence", l(
                            // Example: Directly create an outcome thought
                            s("add_thought", a("OUTCOME"), s("result", v("StrategyContent"), a("completed")), a("DEFAULT_POSITIVE")),
                            s("set_status", a("DONE")), // Mark strategy done
                            s("check_parent_completion", a("ALL_TERMINAL"), a("DONE"), a("FALSE")) // Check parent GOAL
                    )), Belief.DEFAULT_POSITIVE, "Executes STRATEGY, creates OUTCOME, checks parent");

            // MT-OUTCOME-PROCESS: Processes an OUTCOME
            addMeta("OUTCOME-PROCESS", Role.OUTCOME, // Target role OUTCOME
                    v("AnyOutcomeContent"), // Target: Matches any outcome content
                    s("sequence", l(
                            s("set_status", a("DONE")), // Mark outcome done
                            s("check_parent_completion", a("ALL_TERMINAL"), a("DONE"), a("FALSE")) // Check if parent (GOAL/STRATEGY) is complete
                    )), Belief.DEFAULT_POSITIVE, "Processes OUTCOME, marks DONE, checks parent");

            // MT-REFLECT-GEN-META: Generates a new META_THOUGHT via LLM
            addMeta("REFLECT-GEN-META", Role.GOAL,
                    a("generate_new_meta_thought"), // Specific goal trigger
                    s("sequence", l(
                            s("generate_thoughts", s("prompt", a("generate_meta_thought"))), // Ask LLM
                            s("set_status", a("DONE"))
                    )), Belief.DEFAULT_POSITIVE, "Generates a new META_THOUGHT via LLM");

            int finalCount = thoughtStore.getMetaThoughts().size();
            System.out.println("Bootstrap META_THOUGHTs loaded: " + (finalCount - initialCount));
        }

        // Helper to add a META_THOUGHT
        private void addMeta(String idHint, Role targetRole, Term target, Term action, Belief belief, String description) {
            Thought meta = new ThoughtBuilder()
                    .id("META-" + idHint.replaceAll("\\W", "_")) // Sanitize hint for ID
                    .role(Role.META_THOUGHT)
                    .content(s("meta_def", target, action)) // Standard meta_def(Target, Action)
                    .belief(belief)
                    .status(Status.PENDING) // Meta thoughts are PENDING but not processed by main loop
                    .putMetadata("description", "Bootstrap: " + description)
                    // Store target role in metadata for efficient filtering by Unifier
                    .putMetadata("target_role", targetRole.name())
                    .build();
            thoughtStore.addThought(meta);
        }
    }
}