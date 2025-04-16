package dumb.coglog1; // Use a package name

// Coglog 2.6.1 Engine - Single File Implementation
// Description: A probabilistic, reflective, self-executing logic engine for agentic AI.
//              Processes Thoughts based on Belief scores, matching against META_THOUGHTs
//              to execute actions, enabling planning, self-modification, and robust operation.
// Features:    Immutable data structures, probabilistic execution, meta-circularity,
//              reflection, error handling, retries, persistence, garbage collection.
// Dependencies: Minimal standard Java libraries (UUID, Collections, IO).
// Version: 2.6.1.2 (Parser refinement, log verbosity reduction)

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
public class CoglogEngine00 implements AutoCloseable {

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
    // Use a ScheduledThreadPoolExecutor for better task management and potential future expansion
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setName("CoglogScheduler-" + t.threadId());
        t.setDaemon(true); // Allow JVM exit even if scheduler threads are running
        return t;
    });

    /** Constructor using specified configuration */
    public CoglogEngine00(Configuration config) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.storeNotifier = new ConsoleNotifier(); // Simple console notifier
        this.thoughtStore = new InMemoryThoughtStore(storeNotifier);
        this.persistenceService = new FilePersistenceService();
        this.textParserService = new BasicTextParser(); // Use Basic parser
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
    public CoglogEngine00() {
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
        // Ensure thread safety by operating on a snapshot
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
            if (Files.exists(saveFile) && Files.size(saveFile) > 0) { // Check existence and size
                loadedThoughts = persistenceService.load(config.persistencePath());
                loadSuccess = true;
            } else if (Files.exists(saveFile)) { // Exists but empty
                System.out.println("Persistence file exists but is empty: " + config.persistencePath() + ". Starting fresh.");
                try { Files.delete(saveFile); } catch (IOException delEx) { System.err.println("WARN: Could not delete empty state file: " + delEx.getMessage());}
            } else { // Does not exist
                System.out.println("Persistence file not found: " + config.persistencePath() + ". Starting fresh.");
            }
        } catch (PersistenceException e) {
            System.err.println("ERROR: Failed to load persisted state from " + config.persistencePath() + ": " + e.getMessage());
            System.err.println("       Cause: " + (e.getCause() != null ? e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage() : "N/A"));
            System.err.println("Attempting to bootstrap with default META_THOUGHTs instead.");
            // backupCorruptedFile(saveFile); // Consider adding backup logic
        } catch (IOException e) { // Catch IO errors during file check/delete
            System.err.println("ERROR: IO exception accessing persistence file " + config.persistencePath() + ": " + e.getMessage());
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
                    thoughtStore.addThought(t); // Add thought directly (handles notification)
                }
            }
            if (activeResetCount > 0) {
                System.out.println("WARN: Reset " + activeResetCount + " thoughts from ACTIVE to PENDING on load.");
            }
            System.out.println("Loaded " + thoughtStore.getAllThoughts().size() + " thoughts from state file.");
        } else {
            System.out.println("No previous state loaded or state was empty/invalid. Bootstrapping...");
            bootstrap.loadBootstrapMetaThoughts();
        }
    }

    /** Saves the current state of the ThoughtStore to the persistence path. */
    private void saveState() {
        try {
            // Save a copy to prevent issues if store is modified during save
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
        // Consider adding command-line argument parsing for configuration overrides
        Configuration config = Configuration.DEFAULT;

        try (CoglogEngine00 engine = new CoglogEngine00(config)) {

            // Add initial goals only if the store is nearly empty (ignoring META thoughts)
            if (engine.getAllThoughts().stream().filter(t -> t.role() != Role.META_THOUGHT).count() < 2) {
                System.out.println("Adding demo thoughts...");
                engine.addInputText("decompose(plan_weekend_trip)"); // GOAL -> MT-GOAL-DECOMPOSE
                engine.addInputText("execute_via_llm(summarize_quantum_physics)"); // GOAL -> MT-GOAL-EXECUTE-LLM
                engine.addInputText("convert_note_to_goal"); // NOTE -> MT-NOTE-TO-GOAL (via parser)
                // Add a goal that won't match any META to test failure/retry
                engine.addInputText("goal_with_no_meta(do_nothing)"); // GOAL -> No Match -> FAILED
                // engine.addInputText("generate_new_meta_thought"); // GOAL -> MT-REFLECT-GEN-META
            } else {
                System.out.println("Loaded existing thoughts ("
                        + engine.getAllThoughts().stream().filter(t -> t.role() != Role.META_THOUGHT).count()
                        + " non-META), skipping demo input.");
            }

            engine.start(); // Start the engine loops

            System.out.println("\nEngine running... Press Enter to stop.");
            // Non-blocking wait for Enter
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                long lastStatusPrint = 0;
                while (!reader.ready()) { // Check if Enter key is pressed without blocking
                    if (System.currentTimeMillis() - lastStatusPrint > 5000) { // Print status approx every 5 seconds
                        System.out.printf("%n.Current Status: %s%n", engine.getStatusCounts());
                        lastStatusPrint = System.currentTimeMillis();
                    } else {
                        System.out.print("."); // Show activity more frequently
                    }
                    //noinspection BusyWait
                    Thread.sleep(200); // Wait briefly
                }
                reader.readLine(); // Consume the Enter key press
            }
            System.out.println("\nStopping engine...");

        } // Engine.close() called automatically (stops loops, saves state)

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

    /** Represents an atomic symbol (e.g., 'go', 'true', 'convert_note_to_goal'). Immutable and serializable. */
    record Atom(String name) implements Term {
        Atom { Objects.requireNonNull(name, "Atom name cannot be null"); } // Added null check
        @Override public String toString() { return name; }
    }

    /** Represents a logical variable (e.g., '_X', 'Content'). Immutable and serializable. */
    record Variable(String name) implements Term { // Convention: Uppercase or starting with _
        Variable { Objects.requireNonNull(name, "Variable name cannot be null"); } // Added null check
        @Override public String toString() { return name; }
    }

    /** Represents a numeric value. Immutable and serializable. */
    record NumberTerm(double value) implements Term {
        // No explicit constructor needed, default is fine. Validation done by Double.
        @Override public String toString() { return String.valueOf(value); }
    }

    /** Represents a structured term (functor and arguments). Immutable and serializable. */
    record Structure(String name, List<Term> args) implements Term {
        public Structure {
            Objects.requireNonNull(name, "Structure name cannot be null");
            Objects.requireNonNull(args, "Structure arguments cannot be null");
            // Ensure args list is immutable upon creation and doesn't contain nulls
            args = List.copyOf(args);
            if (args.stream().anyMatch(Objects::isNull)) throw new NullPointerException("Structure arguments cannot contain null");
        }
        @Override public String toString() {
            // More compact representation for empty args: name() instead of name([])
            return args.isEmpty() ? name + "()" :
                    name + "(" + args.stream().map(Term::toString).collect(Collectors.joining(", ")) + ")";
        }
    }

    /** Represents a list of terms. Immutable and serializable. */
    record ListTerm(List<Term> elements) implements Term {
        public ListTerm {
            Objects.requireNonNull(elements, "List elements cannot be null");
            // Ensure elements list is immutable upon creation and doesn't contain nulls
            elements = List.copyOf(elements);
            if (elements.stream().anyMatch(Objects::isNull)) throw new NullPointerException("List elements cannot contain null");
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
        private static final double LAPLACE_K = 1.0; // Laplace smoothing constant (k=1)

        // Validate counts during construction
        Belief {
            if (!Double.isFinite(positive) || !Double.isFinite(negative) || positive < 0 || negative < 0) {
                throw new IllegalArgumentException("Belief counts must be finite and non-negative: pos=" + positive + ", neg=" + negative);
            }
        }

        /** Calculates the belief score using Laplace smoothing (add-k smoothing). */
        double score() {
            double totalEvidence = positive + negative;
            // Handle potential overflow or zero evidence cases
            if (totalEvidence > Double.MAX_VALUE / 2.0) return positive / totalEvidence; // Approximate near overflow
            if (totalEvidence == 0.0) return LAPLACE_K / (2.0 * LAPLACE_K); // Handle zero evidence (yields 0.5 for k=1)
            return (positive + LAPLACE_K) / (totalEvidence + 2.0 * LAPLACE_K); // Laplace smoothing
        }

        /** Returns a new Belief instance updated based on a signal. */
        Belief update(boolean positiveSignal) {
            double newPositive = positiveSignal ? Math.min(positive + 1.0, Double.MAX_VALUE) : positive;
            double newNegative = positiveSignal ? negative : Math.min(negative + 1.0, Double.MAX_VALUE);
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

        // Canonical empty metadata map
        private static final Map<String, Object> EMPTY_METADATA = Map.of();

        // Ensure immutability and validate metadata during construction
        Thought {
            Objects.requireNonNull(id, "Thought ID cannot be null");
            Objects.requireNonNull(role, "Thought Role cannot be null");
            Objects.requireNonNull(content, "Thought Content cannot be null");
            Objects.requireNonNull(belief, "Thought Belief cannot be null");
            Objects.requireNonNull(status, "Thought Status cannot be null");
            // Use canonical empty map if metadata is null or empty
            metadata = (metadata == null || metadata.isEmpty()) ? EMPTY_METADATA : Map.copyOf(metadata);
            validateMetadata(metadata); // Validate value types
        }

        /** Provides a builder for creating modified copies of this Thought. */
        public ThoughtBuilder toBuilder() { return new ThoughtBuilder(this); }

        /** Returns a concise string representation for logging. */
        @Override public String toString() {
            String metaString = metadata.isEmpty() ? "{}" : metadata.toString(); // More compact empty metadata
            return String.format("Thought[%s, %s, %s, %s, %s, %s]",
                    id.substring(0, Math.min(id.length(), 8)), // Short ID
                    role, status, belief, content, metaString);
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
                // Allow null values in metadata? Specification doesn't explicitly forbid. Assume allowed for now.
                if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean ||
                        (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String || item == null)))) { // Allow nulls in list
                    throw new IllegalArgumentException("Invalid metadata value type for key '" + entry.getKey() + "': " + value.getClass().getName());
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

        // --- Builder methods (chainable) ---
        public ThoughtBuilder id(String id) { this.id = Objects.requireNonNull(id); return this; }
        public ThoughtBuilder role(Role role) { this.role = Objects.requireNonNull(role); return this; }
        public ThoughtBuilder content(Term content) { this.content = Objects.requireNonNull(content); return this; }
        public ThoughtBuilder belief(Belief belief) { this.belief = Objects.requireNonNull(belief); return this; }
        public ThoughtBuilder status(Status status) { this.status = Objects.requireNonNull(status); return this; }
        public ThoughtBuilder metadata(Map<String, Object> metadata) { this.metadata.clear(); this.metadata.putAll(Objects.requireNonNull(metadata)); return this; }
        public ThoughtBuilder mergeMetadata(Map<String, Object> additionalMetadata) { this.metadata.putAll(Objects.requireNonNull(additionalMetadata)); return this; }
        public ThoughtBuilder putMetadata(String key, Object value) {
            Objects.requireNonNull(key);
            // Allow null value removal? `put` handles it, validation below allows nulls.
            // Validate type before adding
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean ||
                    (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String || item == null)))) {
                throw new IllegalArgumentException("Invalid metadata value type for key '" + key + "': " + value.getClass().getName());
            }
            // Ensure lists are immutable copies if not null
            this.metadata.put(key, (value instanceof List<?> listValue) ? List.copyOf(listValue) : value);
            return this;
        }

        /** Builds the immutable Thought object, applying final checks and defaults. */
        public Thought build() {
            // Update timestamp and ensure core metadata types are correct before final validation
            metadata.put("last_updated_timestamp", System.currentTimeMillis());
            // Convert retry_count to Integer if present as another Number type
            metadata.computeIfPresent("retry_count", (k, v) -> (v instanceof Number n) ? n.intValue() : 0);
            // Ensure timestamps are Long
            metadata.computeIfPresent("creation_timestamp", (k, v) -> (v instanceof Number n) ? n.longValue() : System.currentTimeMillis());
            metadata.computeIfPresent("last_updated_timestamp", (k, v) -> (v instanceof Number n) ? n.longValue() : System.currentTimeMillis());
            // Remove null values before creating immutable map? Let's keep them as spec doesn't forbid.

            // Return the immutable Thought (constructor handles null checks and Map.copyOf)
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
        // Use ThreadLocalRandom for potentially better performance in concurrent scenarios, though not strictly needed here.
        private static final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();

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
                for (int i = 0; i < s1.args().size(); i++) { // Use indexed loop for potentially better performance
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
                for (int i = 0; i < l1.elements().size(); i++) { // Use indexed loop
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
            Deque<Term> stack = new ArrayDeque<>(); // Use stack for iterative check to avoid deep recursion
            stack.push(applySubstitution(term, substitution)); // Start with the resolved term
            Set<Term> visited = new HashSet<>(); // Avoid cycles in complex terms

            while (!stack.isEmpty()) {
                Term current = stack.pop();
                if (var.equals(current)) return true; // Found the variable

                if (!visited.add(current)) continue; // Skip already visited terms

                switch (current) {
                    case Structure s -> s.args().forEach(stack::push); // Add args to stack
                    case ListTerm l -> l.elements().forEach(stack::push); // Add elements to stack
                    default -> {} // Atom, Number, unbound Variable cannot contain var further
                }
            }
            return false; // Variable not found
        }


        /** Recursively applies a substitution map to a term, returning a new term. Optimized to avoid object creation if no changes. */
        public static Term applySubstitution(Term term, Map<Variable, Term> substitution) {
            if (substitution.isEmpty()) return term; // Quick exit if no substitutions

            return switch (term) {
                case Variable v -> substitution.getOrDefault(v, v); // Return binding or variable itself
                case Structure s -> {
                    boolean changed = false;
                    List<Term> originalArgs = s.args();
                    List<Term> substitutedArgs = new ArrayList<>(originalArgs.size());
                    for (Term arg : originalArgs) {
                        Term substitutedArg = applySubstitution(arg, substitution); // Recursive call
                        substitutedArgs.add(substitutedArg);
                        if (substitutedArg != arg) changed = true; // Track if any argument changed
                    }
                    yield changed ? new Structure(s.name(), List.copyOf(substitutedArgs)) : s; // Reuse original if no change
                }
                case ListTerm l -> {
                    boolean changed = false;
                    List<Term> originalElements = l.elements();
                    List<Term> substitutedElements = new ArrayList<>(originalElements.size());
                    for (Term el : originalElements) {
                        Term substitutedEl = applySubstitution(el, substitution);
                        substitutedElements.add(substitutedEl);
                        if (substitutedEl != el) changed = true;
                    }
                    yield changed ? new ListTerm(List.copyOf(substitutedElements)) : l; // Reuse original if no change
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

        // Iterative helper to find the first unbound variable in a term (post-substitution)
        private static Variable findFirstVariable(Term term) {
            Deque<Term> stack = new ArrayDeque<>();
            stack.push(term);
            Set<Term> visited = new HashSet<>(); // Avoid infinite loops with cyclic terms (though unlikely here)

            while(!stack.isEmpty()) {
                Term current = stack.pop();
                if (!visited.add(current)) continue;

                switch (current) {
                    case Variable v -> { return v; } // Found one!
                    case Structure s -> s.args().forEach(stack::push);
                    case ListTerm l -> l.elements().forEach(stack::push);
                    default -> {} // Atom, NumberTerm - no variables within
                }
            }
            return null; // No unbound variables found
        }

        /** Samples an item from a list of weighted items. Returns Optional.empty if list is empty or weights sum to zero/invalid. */
        public static <T> Optional<T> sampleWeighted(List<Map.Entry<Double, T>> itemsWithScores) {
            List<Map.Entry<Double, T>> validItems = itemsWithScores.stream()
                    .filter(entry -> entry.getKey() > 0 && Double.isFinite(entry.getKey())) // Filter invalid scores
                    .toList();
            if (validItems.isEmpty()) return Optional.empty();

            double totalWeight = validItems.stream().mapToDouble(Map.Entry::getKey).sum();
            if (totalWeight <= 0 || !Double.isFinite(totalWeight)) return Optional.empty(); // Check for valid total weight

            double randomValue = RANDOM.nextDouble() * totalWeight; // Use thread-local random
            double cumulativeWeight = 0.0;
            for (Map.Entry<Double, T> entry : validItems) {
                cumulativeWeight += entry.getKey();
                if (randomValue < cumulativeWeight) {
                    return Optional.of(entry.getValue()); // Found item
                }
            }
            // Fallback for floating point issues: return the last valid item
            return Optional.of(validItems.getLast().getValue());
        }

        /**
         * Parses a string into a Term. Attempts Number, List, Structure, Variable, then Atom.
         * Handles basic nesting but assumes commas primarily separate args/elements.
         */
        public static Term parseTerm(String input) throws IllegalArgumentException {
            input = input.trim();
            if (input.isEmpty()) throw new IllegalArgumentException("Cannot parse empty string to Term");

            // 1. Try Number
            try { return new NumberTerm(Double.parseDouble(input)); } catch (NumberFormatException ignored) {}

            // 2. Try List: [...]
            if (input.startsWith("[") && input.endsWith("]")) {
                String content = input.substring(1, input.length() - 1).trim();
                return new ListTerm(parseTermListContent(content));
            }

            // 3. Try Structure: name(...)
            Matcher structureMatcher = Pattern.compile("^([a-z_][\\w]*)\\((.*)\\)$").matcher(input);
            if (structureMatcher.matches()) {
                String name = structureMatcher.group(1);
                String argsContent = structureMatcher.group(2).trim();
                return new Structure(name, parseTermListContent(argsContent));
            }

            // 4. Try Variable: Starts with uppercase or underscore
            if (input.matches("^[_A-Z][_a-zA-Z0-9]*$")) {
                return new Variable(input);
            }

            // 5. Default to Atom: Allows lowercase start, symbols, spaces etc.
            if (!input.isEmpty()) { // Check if not empty after trimming
                // Basic validation: Atoms cannot be just numbers, or look like variables/structures/lists.
                // These checks prevent ambiguity with the types parsed above.
                if (input.matches("\\d+(\\.\\d+)?") || // Looks like a number
                        input.matches("^[_A-Z][_a-zA-Z0-9]*$") || // Looks like a variable
                        input.matches("^[a-z_][\\w]*\\(.*\\)$") || // Looks like a structure
                        (input.startsWith("[") && input.endsWith("]"))) { // Looks like a list
                    throw new IllegalArgumentException("Input '" + input + "' resembles another term type but failed to parse as such.");
                }
                return new Atom(input);
            }

            throw new IllegalArgumentException("Cannot parse string into a valid Term: '" + input + "'");
        }

        // Helper to parse comma-separated term content within lists or structures
        // Handles basic nesting by tracking parentheses/brackets.
        private static List<Term> parseTermListContent(String content) throws IllegalArgumentException {
            if (content.isEmpty()) return Collections.emptyList();
            List<Term> terms = new ArrayList<>();
            int balance = 0; // Track nesting level (parentheses/brackets)
            int start = 0;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '(' || c == '[') balance++;
                else if (c == ')' || c == ']') balance--;
                else if (c == ',' && balance == 0) { // Comma at top level separates terms
                    terms.add(parseTerm(content.substring(start, i))); // Parse the segment
                    start = i + 1; // Start next term after comma
                }
            }
            if (balance != 0) throw new IllegalArgumentException("Mismatched parentheses or brackets in term list: " + content);
            terms.add(parseTerm(content.substring(start))); // Add the last term
            return List.copyOf(terms); // Return immutable list
        }

        // Pattern for structure parsing
        private static final Pattern STRUCTURE_PATTERN = Pattern.compile("^([a-z_][\\w]*)\\((.*)\\)$");
        private static final java.util.regex.Matcher structureMatcher = STRUCTURE_PATTERN.matcher(""); // Reusable matcher
    }


    // --- Component Implementations ---

    /** In-memory ThoughtStore using ConcurrentHashMap for thread-safe access. */
    static class InMemoryThoughtStore implements ThoughtStore {
        private final ConcurrentMap<String, Thought> thoughts = new ConcurrentHashMap<>();
        private final StoreNotifier notifier;

        InMemoryThoughtStore(StoreNotifier notifier) { this.notifier = Objects.requireNonNull(notifier); }

        @Override public Optional<Thought> getThought(String id) { return Optional.ofNullable(thoughts.get(id)); }
        @Override public Collection<Thought> getAllThoughts() { return List.copyOf(thoughts.values()); } // Return immutable copy
        @Override public void clear() { thoughts.clear(); notifier.notifyChange(null, StoreNotifier.ChangeType.REMOVE); /* Notify generic clear */ }

        @Override public void addThought(Thought thought) {
            Objects.requireNonNull(thought);
            if (thoughts.putIfAbsent(thought.id(), thought) == null) { // Atomic add
                notifier.notifyChange(thought, StoreNotifier.ChangeType.ADD);
            } // else { System.err.println("WARN: Attempted to add thought with existing ID: " + thought.id().substring(0, 8)); } // Reduce noise
        }

        /** Atomically updates a thought if the oldThought matches the current value. */
        @Override public boolean updateThought(Thought oldThought, Thought newThought) {
            Objects.requireNonNull(oldThought); Objects.requireNonNull(newThought);
            if (!oldThought.id().equals(newThought.id())) return false; // ID mismatch is logic error

            // Use ConcurrentMap's atomic replace for optimistic locking
            boolean updated = thoughts.replace(newThought.id(), oldThought, newThought);
            if (updated) notifier.notifyChange(newThought, StoreNotifier.ChangeType.UPDATE);
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
            // Avoid stream creation if map is empty
            if (thoughts.isEmpty()) return Optional.empty();

            List<Map.Entry<Double, Thought>> pendingEligible = thoughts.values().stream()
                    .filter(t -> t.status() == Status.PENDING && t.role() != Role.META_THOUGHT)
                    .map(t -> Map.entry(t.belief().score(), t))
                    .toList(); // Collect to list for sampling
            return Helpers.sampleWeighted(pendingEligible);
        }

        /** Returns a list of non-FAILED META_THOUGHTs. */
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

    /** Simple StoreNotifier implementation that prints changes to the console. */
    static class ConsoleNotifier implements StoreNotifier {
        @Override public void notifyChange(Thought thought, ChangeType type) {
            // Reduce log verbosity for demo
            // if (thought != null) System.out.printf("Store Notify: %s - %s (%s)%n", type, thought.id().substring(0, 8), thought.role());
            // else System.out.printf("Store Notify: %s - ALL%n", type); // For clear
        }
    }

    /** Persistence implementation using Java Serialization. */
    static class FilePersistenceService implements PersistenceService {
        @Override
        public void save(Collection<Thought> thoughts, String path) {
            Path filePath = Paths.get(path);
            try {
                Files.createDirectories(filePath.getParent()); // Ensure directory exists
                // Use try-with-resources for automatic stream closing
                try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(filePath.toFile())))) { // Add buffering
                    oos.writeObject(new ArrayList<>(thoughts)); // Serialize as ArrayList
                    // System.out.println("Saved " + thoughts.size() + " thoughts to " + path); // Less verbose
                }
            } catch (IOException e) {
                throw new PersistenceException("Failed to save state to " + path, e);
            }
        }

        @Override
        public Collection<Thought> load(String path) {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) {
                throw new PersistenceException("Load failed: File not found at " + path, null);
            }
            if (!Files.isReadable(filePath)) {
                throw new PersistenceException("Load failed: File not readable at " + path, null);
            }
            // Use try-with-resources
            try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(filePath.toFile())))) { // Add buffering
                Object loadedObject = ois.readObject();
                if (loadedObject instanceof List<?> list && list.stream().allMatch(Thought.class::isInstance)) {
                    @SuppressWarnings("unchecked") Collection<Thought> thoughts = (Collection<Thought>) list;
                    // System.out.println("Loaded " + thoughts.size() + " thoughts from " + path); // Less verbose
                    return thoughts;
                } else {
                    throw new PersistenceException("Loaded file format is incorrect (expected List<Thought>). Found: " + (loadedObject == null ? "null" : loadedObject.getClass().getName()), null);
                }
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                throw new PersistenceException("Failed to load or deserialize state from " + path, e);
            }
        }
    }

    /** Basic Text Parser: Parses each line as a Term. Creates GOALs for structured terms, NOTE for others. */
    static class BasicTextParser implements TextParserService {
        @Override
        public List<Thought> parse(String text) {
            return Arrays.stream(text.split("\\r?\\n")) // Split by newline
                    .map(String::trim).filter(line -> !line.isEmpty())
                    .map(line -> {
                        try {
                            Term parsedTerm = Helpers.parseTerm(line);
                            // Determine role based on parsed term type
                            Role role = (parsedTerm instanceof Structure || parsedTerm instanceof ListTerm) ? Role.GOAL : Role.NOTE;
                            return Thought.create(role, parsedTerm, Belief.DEFAULT_POSITIVE);
                        } catch (IllegalArgumentException e) {
                            // Fallback: Create a NOTE with the original line as an Atom if parsing fails
                            System.err.println("WARN: Input line failed Term parsing, creating NOTE with Atom content: '" + line + "' (" + e.getMessage() + ")");
                            return Thought.create(Role.NOTE, new Atom(line), Belief.DEFAULT_POSITIVE);
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
            System.out.println("LLM Call (Mock): Prompt='" + prompt + "'"); // Simplified log
            String goal = extractGoalContent(prompt); // Helper to get context

            if (prompt.contains("Decompose goal:")) {
                // Simulate decomposition into STRATEGY thoughts
                return """
                       add_thought(STRATEGY, execute_strategy(step1_for_%s), default_positive)
                       add_thought(STRATEGY, execute_strategy(step2_for_%s), default_positive)
                       """.formatted(goal, goal).trim();
            } else if (prompt.contains("Execute goal:")) {
                // Simulate direct execution result
                // Make result parsable as a Term (e.g., an Atom)
                return "llm_completed(task(" + goal + "))";
            } else if (prompt.contains("generate_meta_thought")) {
                // Simulate generating a new META_THOUGHT definition
                return "add_thought(META_THOUGHT, meta_def(trigger(new_condition), action(new_effect)), default_positive)";
            }
            // Default response, make it parsable
            return "mock_response(for(" + goal + "))";
        }

        // Simple helper to extract content after the last colon or return a placeholder
        private String extractGoalContent(String prompt) {
            int marker = prompt.indexOf(":"); // Find first colon as marker
            return (marker >= 0 ? prompt.substring(marker + 1).trim() : prompt)
                    .replaceAll("[^a-zA-Z0-9_(),]", "_"); // Sanitize slightly more carefully
        }
    }

    /** Generates new Thoughts, often using an LLM service. */
    static class BasicThoughtGenerator implements ThoughtGenerator {
        private final LlmService llmService;
        BasicThoughtGenerator(LlmService llmService) { this.llmService = Objects.requireNonNull(llmService); }

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

            // Parse each line of the LLM response, expecting 'add_thought(...)' format
            for (String line : llmResponse.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("add_thought(") && line.endsWith(")")) {
                    try {
                        // Improved parsing: Use the main Term parser logic for robustness?
                        // For now, stick to basic split, assuming clean LLM output format.
                        String argsString = line.substring("add_thought(".length(), line.length() - 1);
                        List<Term> args = Helpers.parseTermListContent(argsString); // Use helper parser
                        if (args.size() == 3) {
                            Role role = expectEnumValue(args.get(0), Role.class, "Generated Role");
                            Term content = args.get(1); // Content is already a Term
                            Belief belief = expectBelief(args.get(2), "Generated Belief");
                            generatedThoughts.add(new ThoughtBuilder()
                                    .role(role).content(content).belief(belief)
                                    .putMetadata("parent_id", parentId)
                                    .putMetadata("provenance", List.of("LLM_GENERATED"))
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

        // --- Action Helper Methods Duplicated Here (Refactor?) ---
        // Duplicating these helpers avoids making ActionExecutor fields public or passing executor instance.
        // Consider a shared 'ActionUtils' class if complexity grows.
        private <T extends Enum<T>> T expectEnumValue(Term term, Class<T> enumClass, String desc) {
            if (term instanceof Atom atom) {
                try { return Enum.valueOf(enumClass, atom.name().toUpperCase()); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid " + desc + " value: '" + atom.name() + "'"); }
            } throw new IllegalArgumentException("Expected Atom for " + desc + ", got: " + term);
        }
        private Belief expectBelief(Term term, String desc) {
            return switch (term) {
                case Atom a -> parseBeliefString(a.name());
                case Structure s when "belief".equals(s.name()) && s.args().size() == 2 ->
                        new Belief(expectNumber(s.args().get(0), "pos belief").value(),
                                expectNumber(s.args().get(1), "neg belief").value());
                default -> throw new IllegalArgumentException("Expected Atom (belief type) or belief(Pos, Neg) for " + desc + ", got: " + term);
            };
        }
        private NumberTerm expectNumber(Term term, String desc) {
            if (term instanceof NumberTerm number) return number;
            throw new IllegalArgumentException("Expected NumberTerm for " + desc + ", got: " + term);
        }
        private Belief parseBeliefString(String beliefStr) { // Copied from ActionExecutor - Needs refactor
            return switch (beliefStr.toLowerCase()) {
                case "default_positive" -> Belief.DEFAULT_POSITIVE;
                case "default_uncertain" -> Belief.DEFAULT_UNCERTAIN;
                case "default_low_confidence" -> Belief.DEFAULT_LOW_CONFIDENCE;
                case String s when s.matches("(?i)b\\(\\s*\\d+(\\.\\d+)?\\s*,\\s*\\d+(\\.\\d+)?\\s*\\)") -> {
                    try {
                        String[] parts = s.substring(2, s.length() - 1).split(",");
                        yield new Belief(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
                    } catch (Exception e) {
                        yield Belief.DEFAULT_UNCERTAIN;
                    }
                } default -> Belief.DEFAULT_UNCERTAIN;
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
                if (!(meta.content() instanceof Structure metaDef && "meta_def".equals(metaDef.name()) && metaDef.args().size() == 2)) continue; // Basic structure check
                Term targetTerm = metaDef.args().get(0);

                // Optional Role Check metadata
                Optional<String> targetRoleName = meta.getMetadata("target_role", String.class);
                if (targetRoleName.isPresent() && !targetRoleName.get().equalsIgnoreCase(activeThought.role().name())) continue; // Role mismatch

                // --- Attempt Unification ---
                try {
                    Map<Variable, Term> substitution = Helpers.unify(activeThought.content(), targetTerm);
                    potentialMatches.add(Map.entry(meta.belief().score(), new UnificationResult(meta, substitution))); // Use original substitution map
                } catch (UnificationException e) { /* No match, ignore */
                } catch (Exception e) { System.err.printf("ERROR: Unexpected unification error for META %s: %s%n", meta.id().substring(0, 8), e.getMessage()); }
            }
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
            Term actionTerm = extractActionTerm(metaThought);
            // Apply substitution *before* execution dispatch
            Term resolvedActionTerm = Helpers.applySubstitution(actionTerm, substitutionMap);
            executeResolvedAction(resolvedActionTerm, activeThought, substitutionMap, metaThought.id());
        }

        // Extracts the action term (second argument) from a meta_def/2 structure.
        private Term extractActionTerm(Thought metaThought) {
            if (metaThought.content() instanceof Structure metaDef && "meta_def".equals(metaDef.name()) && metaDef.args().size() == 2) {
                return metaDef.args().get(1);
            }
            throw new ActionExecutionException("Invalid META_THOUGHT structure: Expected meta_def(Target, Action), found: " + metaThought.content());
        }

        // Dispatches to the appropriate primitive action handler based on the resolved action term.
        private void executeResolvedAction(Term action, Thought contextThought, Map<Variable, Term> substitutionMap, String metaThoughtId) {
            if (!(action instanceof Structure structAction)) { // Use pattern variable
                if (action instanceof Atom atom && "noop".equals(atom.name())) return; // Allow noop Atom
                throw new ActionExecutionException("Action term must be a Structure or 'noop' Atom, got: " + action);
            }

            try {
                // Pass the already resolved args from structAction
                List<Term> resolvedArgs = structAction.args();
                // --- Primitive Action Dispatch ---
                switch (structAction.name()) {
                    case "add_thought"             -> executeAddThought(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "set_status"              -> executeSetStatus(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "set_belief"              -> executeSetBelief(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "check_parent_completion" -> executeCheckParentCompletion(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "generate_thoughts"       -> executeGenerateThoughts(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "sequence"                -> executeSequence(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "call_llm"                -> executeCallLlm(resolvedArgs, contextThought, substitutionMap, metaThoughtId);
                    case "noop"                    -> { /* Handled above, or explicit noop structure */ }
                    default -> throw new ActionExecutionException("Unknown primitive action name: " + structAction.name());
                }
            } catch (UnboundVariableException uve) {
                throw new ActionExecutionException("Action '" + structAction.name() + "' argument resolution failed: " + uve.getMessage(), uve);
            } catch (ActionExecutionException aee) { throw aee;
            } catch (Exception e) { throw new ActionExecutionException("Unexpected error executing action '" + structAction.name() + "': " + e.getMessage(), e); }
        }

        // --- Primitive Action Implementations ---
        // Note: Arguments passed (resolvedArgs) are already substituted based on the initial unification.
        // Actions must call applySubstitutionFully if they need to ensure *their* arguments are ground.

        private void executeAddThought(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 3) throw new ActionExecutionException("add_thought requires 3 arguments");
            // Ensure args passed to builder are fully ground
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
            if (current == null) { System.err.printf("WARN: set_status skipped for missing thought %s%n", context.id().substring(0,8)); return; }
            if (current.status() == newStatus) return; // Already in target status

            Thought updated = current.toBuilder().status(newStatus).putMetadata("provenance", addToProvenance(current.metadata(), metaId)).build();
            if (!thoughtStore.updateThought(current, updated)) { System.err.println("WARN: Failed atomic update for set_status on " + context.id().substring(0, 8)); }
            // else { System.out.printf("Action: Set status of %s to %s%n", context.id().substring(0, 8), newStatus); } // Reduce noise
        }

        private void executeSetBelief(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 1) throw new ActionExecutionException("set_belief requires 1 argument: POSITIVE/NEGATIVE Atom");
            Atom typeAtom = expectAtom(Helpers.applySubstitutionFully(resolvedArgs.getFirst(), sub), "Belief Type");
            boolean positiveSignal = switch (typeAtom.name().toUpperCase()) { case "POSITIVE" -> true; case "NEGATIVE" -> false; default -> throw new ActionExecutionException("Invalid belief type: '" + typeAtom.name() + "'"); };

            Thought current = thoughtStore.getThought(context.id()).orElse(null);
            if (current == null) { System.err.printf("WARN: set_belief skipped for missing thought %s%n", context.id().substring(0,8)); return; }

            Belief newBelief = current.belief().update(positiveSignal);
            if (newBelief.equals(current.belief())) return; // No change

            Thought updated = current.toBuilder().belief(newBelief).putMetadata("provenance", addToProvenance(current.metadata(), metaId)).build();
            if (!thoughtStore.updateThought(current, updated)) { System.err.println("WARN: Failed atomic update for set_belief on " + context.id().substring(0, 8)); }
            // else { System.out.printf("Action: Updated belief of %s based on %s (New: %s)%n", context.id().substring(0, 8), typeAtom.name(), newBelief); } // Reduce noise
        }

        private void executeCheckParentCompletion(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 3) throw new ActionExecutionException("check_parent_completion requires 3 arguments");
            Atom checkTypeAtom = expectAtom(Helpers.applySubstitutionFully(resolvedArgs.get(0), sub), "CheckType");
            Status statusIfComplete = expectEnumValue(Helpers.applySubstitutionFully(resolvedArgs.get(1), sub), Status.class, "StatusIfComplete");
            // boolean recursive = "TRUE".equalsIgnoreCase(expectAtom(Helpers.applySubstitutionFully(resolvedArgs.get(2), sub), "Recursive").name()); // Recursive unused

            Optional<String> parentIdOpt = context.getMetadata("parent_id", String.class);
            if (parentIdOpt.isEmpty()) return; // No parent
            String parentId = parentIdOpt.get();

            Thought parent = thoughtStore.getThought(parentId).orElse(null);
            if (parent == null || parent.status() != Status.WAITING_CHILDREN) return; // Parent not waiting

            List<Thought> children = thoughtStore.findThoughtsByParentId(parentId);
            Thought currentContext = thoughtStore.getThought(context.id()).orElse(context); // Latest status of self
            Status contextStatus = currentContext.status();

            boolean allComplete = children.stream().allMatch(child -> {
                Status status = child.id().equals(context.id()) ? contextStatus : child.status();
                return switch (checkTypeAtom.name().toUpperCase()) {
                    case "ALL_DONE" -> status == Status.DONE;
                    case "ALL_TERMINAL" -> status == Status.DONE || status == Status.FAILED;
                    default -> throw new ActionExecutionException("Unknown checkType: " + checkTypeAtom.name());
                };
            });

            if (allComplete) {
                Thought currentParent = thoughtStore.getThought(parentId).orElse(null); // Re-fetch for atomic update
                if (currentParent != null && currentParent.status() == Status.WAITING_CHILDREN) {
                    Thought updatedParent = currentParent.toBuilder().status(statusIfComplete).putMetadata("provenance", addToProvenance(currentParent.metadata(), metaId)).build();
                    if (!thoughtStore.updateThought(currentParent, updatedParent)) { System.err.println("WARN: Failed atomic update for parent completion on " + parentId.substring(0,8)); }
                    else { System.out.printf("Action: Parent %s status set to %s by check from %s.%n", parentId.substring(0, 8), statusIfComplete, context.id().substring(0, 8)); }
                }
            }
        }

        private void executeGenerateThoughts(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 1) throw new ActionExecutionException("generate_thoughts requires 1 argument: Prompt Term");
            // Pass the prompt term (potentially with variables) and the *original* substitution map
            // The generator is responsible for applying the substitution before calling LLM.
            Term promptTerm = resolvedArgs.getFirst();
            thoughtGenerator.generate(promptTerm, sub, context.id()) // Generate thoughts
                    .forEach(t -> { // Add provenance to each generated thought
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
                    // Execute sub-action. Pass original context and substitution map.
                    // The sub-action itself might be a structure containing variables bound by the outer unification.
                    executeResolvedAction(actionStep, contextThought, substitutionMap, metaThoughtId);
                } catch (Exception e) {
                    // Include step details in the exception message
                    throw new ActionExecutionException("Sequence failed at step " + step + " (" + actionStep + "). Cause: " + e.getMessage(), e);
                }
                step++;
            }
        }

        private void executeCallLlm(List<Term> resolvedArgs, Thought context, Map<Variable, Term> sub, String metaId) {
            if (resolvedArgs.size() != 2) throw new ActionExecutionException("call_llm requires 2 arguments: Prompt Term, Result Role Atom");
            Term promptTerm = Helpers.applySubstitutionFully(resolvedArgs.get(0), sub); // Ensure prompt is ground
            Role resultRole = expectEnumValue(Helpers.applySubstitutionFully(resolvedArgs.get(1), sub), Role.class, "Result Role");

            String llmResultText;
            try { llmResultText = llmService.generateText(promptTerm.toString()); }
            catch (Exception e) { throw new ActionExecutionException("LLM call failed: " + e.getMessage(), e); }

            Term resultContent; // Attempt to parse LLM response as a Term
            try { resultContent = Helpers.parseTerm(llmResultText); }
            catch (IllegalArgumentException e) { resultContent = new Atom(llmResultText); } // Fallback to Atom

            Thought resultThought = new ThoughtBuilder().role(resultRole).content(resultContent).belief(Belief.DEFAULT_POSITIVE)
                    .putMetadata("parent_id", context.id()).putMetadata("provenance", List.of(metaId, "LLM_CALL")).build();
            thoughtStore.addThought(resultThought);
            // System.out.printf("Action: call_llm created result thought %s (%s) for %s%n", resultThought.id().substring(0, 8), resultRole, context.id().substring(0, 8)); // Reduce noise
        }

        // --- Action Helper Methods ---
        // (expectEnumValue, expectAtom, expectNumber, expectBelief, addToProvenance, parseBeliefString - copied from ThoughtGenerator, needs refactor)
        // Duplicated from BasicThoughtGenerator for brevity. Refactor target.
        private <T extends Enum<T>> T expectEnumValue(Term term, Class<T> enumClass, String desc) { if (term instanceof Atom a) { try { return Enum.valueOf(enumClass, a.name().toUpperCase()); } catch (IllegalArgumentException e) { throw new ActionExecutionException("Invalid " + desc + ": '" + a.name() + "'"); } } throw new ActionExecutionException("Expected Atom for " + desc + ", got: " + term); }
        private Atom expectAtom(Term term, String desc) { if (term instanceof Atom a) return a; throw new ActionExecutionException("Expected Atom for " + desc + ", got: " + term); }
        private NumberTerm expectNumber(Term term, String desc) { if (term instanceof NumberTerm n) return n; throw new ActionExecutionException("Expected NumberTerm for " + desc + ", got: " + term); }
        private Belief expectBelief(Term term, String desc) { return switch (term) { case Atom a -> parseBeliefString(a.name()); case Structure s when "belief".equals(s.name()) && s.args().size() == 2 -> new Belief(expectNumber(s.args().get(0), "pos").value(), expectNumber(s.args().get(1), "neg").value()); default -> throw new ActionExecutionException("Expected Atom (belief type) or belief(Pos, Neg) for " + desc + ", got: " + term); }; }
        private Belief parseBeliefString(String str) { return switch (str.toLowerCase()) { case "default_positive" -> Belief.DEFAULT_POSITIVE; case "default_uncertain" -> Belief.DEFAULT_UNCERTAIN; case "default_low_confidence" -> Belief.DEFAULT_LOW_CONFIDENCE; case String s when s.matches("(?i)b\\(\\s*\\d+(\\.\\d+)?\\s*,\\s*\\d+(\\.\\d+)?\\s*\\)") -> { try { String[] p = s.substring(2, s.length() - 1).split(","); yield new Belief(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())); } catch (Exception e) { yield Belief.DEFAULT_UNCERTAIN; } } default -> Belief.DEFAULT_UNCERTAIN; }; }
        private List<String> addToProvenance(Map<String, Object> meta, String entry) { List<String> cur = Optional.ofNullable(meta.get("provenance")).filter(List.class::isInstance).map(l -> (List<?>) l).flatMap(l -> l.stream().allMatch(String.class::isInstance) ? Optional.of(l.stream().map(String.class::cast).toList()) : Optional.<List<String>>empty()).orElse(List.of()); List<String> upd = new ArrayList<>(cur); upd.add(entry); return List.copyOf(upd); }
    }


    /** Main execution loop controller. Runs the select-match-execute cycle. */
    static class ExecuteLoop implements Runnable {
        private final ThoughtStore thoughtStore;
        private final Unifier unifier;
        private final ActionExecutor actionExecutor;
        private final Configuration config;
        private final ScheduledExecutorService scheduler;
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
            cycleFuture = scheduler.scheduleWithFixedDelay(this::runCycleInternal, 0, config.pollIntervalMillis(), TimeUnit.MILLISECONDS);
            System.out.println("ExecuteLoop started (polling every " + config.pollIntervalMillis() + "ms).");
        }

        public void stop() {
            if (!running) return;
            running = false;
            if (cycleFuture != null) cycleFuture.cancel(false);
            activeThoughtTimeouts.values().forEach(f -> f.cancel(true));
            activeThoughtTimeouts.clear();
            System.out.println("ExecuteLoop stopped.");
        }

        @Override public void run() { runCycleInternal(); }

        private void runCycleInternal() {
            if (!running) return; // Exit if stopped

            Thought active = null; String activeId = null;
            try {
                // 1. Sample
                Optional<Thought> pendingOpt = thoughtStore.samplePendingThought();
                if (pendingOpt.isEmpty()) return;
                Thought pending = pendingOpt.get();

                // 2. Activate (Atomic)
                Thought currentPending = thoughtStore.getThought(pending.id()).orElse(null);
                if (currentPending == null || currentPending.status() != Status.PENDING) return; // Gone or changed
                active = updateStatusAtomically(currentPending, Status.ACTIVE);
                if (active == null) return; // Failed activation (race)
                activeId = active.id();

                // 3. Schedule Timeout
                final String finalActiveId = activeId;
                ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> handleTimeout(finalActiveId), config.maxActiveDurationMillis(), TimeUnit.MILLISECONDS);
                ScheduledFuture<?> oldTask = activeThoughtTimeouts.put(activeId, timeoutTask);
                if (oldTask != null) oldTask.cancel(false); // Cancel previous if any lingered

                // 4. Match & Execute
                List<Thought> metaThoughts = thoughtStore.getMetaThoughts();
                Unifier.UnificationResult unification = unifier.findAndSampleMatchingMeta(active, metaThoughts);

                if (unification.hasMatch()) {
                    actionExecutor.execute(active, unification.matchedMetaThought(), unification.substitutionMap());
                    // Check final status (optional check, timeout handles stuck ACTIVE)
                    // Thought finalState = thoughtStore.getThought(activeId).orElse(null);
                    // if (finalState != null && finalState.status() == Status.ACTIVE) { System.err.printf("WARN: Thought %s left ACTIVE.%n", activeId.substring(0, 8)); }
                } else {
                    // 5. No Match -> Fail
                    System.out.printf("No matching META_THOUGHT found for %s (%s: %s).%n", activeId.substring(0, 8), active.role(), active.content());
                    handleFailure(active, "No matching META_THOUGHT", calculateRemainingRetries(active));
                }

            } catch (Throwable e) { // Catch all cycle errors
                System.err.println("FATAL: Uncaught exception during cycle: " + e.getMessage());
                e.printStackTrace();
                if (active != null) { // Attempt to fail the active thought
                    Thought current = thoughtStore.getThought(active.id()).orElse(active);
                    handleFailure(current, "Uncaught cycle exception: " + e.getClass().getSimpleName(), calculateRemainingRetries(current));
                }
            } finally {
                // 6. Cleanup Timeout (if processing finished before timeout)
                if (activeId != null) {
                    ScheduledFuture<?> timeoutTask = activeThoughtTimeouts.remove(activeId);
                    if (timeoutTask != null) timeoutTask.cancel(false); // Cancel if still pending
                }
            }
        }

        // Handles timeout event
        private void handleTimeout(String timedOutThoughtId) {
            activeThoughtTimeouts.remove(timedOutThoughtId); // Remove handled timeout
            Thought current = thoughtStore.getThought(timedOutThoughtId).orElse(null);
            if (current != null && current.status() == Status.ACTIVE) { // Check if *still* ACTIVE
                System.err.printf("TIMEOUT detected for thought %s after %dms.%n", timedOutThoughtId.substring(0, 8), config.maxActiveDurationMillis());
                handleFailure(current, "Timeout", calculateRemainingRetries(current));
            }
        }

        // Handles failure of a thought (no match, error, timeout)
        private void handleFailure(Thought thought, String error, int retriesLeft) {
            Thought current = thoughtStore.getThought(thought.id()).orElse(null);
            if (current == null || (current.status() != Status.ACTIVE && current.status() != Status.PENDING)) return; // Gone or already handled

            int currentRetries = current.getMetadata("retry_count", Integer.class).orElse(0);
            Status nextStatus = retriesLeft > 0 ? Status.PENDING : Status.FAILED;
            Belief nextBelief = current.belief().update(false); // Negative reinforcement

            Thought updated = current.toBuilder()
                    .status(nextStatus).belief(nextBelief)
                    .putMetadata("error_info", error)
                    .putMetadata("retry_count", currentRetries + 1)
                    .build();

            System.out.printf("Handling Failure for %s: Error='%s', Retries Left=%d, New Status=%s%n",
                    thought.id().substring(0, 8), error, retriesLeft, nextStatus);

            if (!thoughtStore.updateThought(current, updated)) { // Atomic update
                System.err.printf("CRITICAL: Failed atomic update during failure handling for %s.%n", thought.id().substring(0, 8));
            }
        }

        // Calculates remaining retries based on current thought state
        private int calculateRemainingRetries(Thought thought) {
            int currentRetries = thought.getMetadata("retry_count", Integer.class).orElse(0);
            return Math.max(0, config.maxRetries() - currentRetries);
        }

        // Atomically updates thought status. Returns updated thought or null.
        private Thought updateStatusAtomically(Thought current, Status newStatus) {
            if (current.status() == newStatus) return current;
            Thought updated = current.toBuilder().status(newStatus).build();
            return thoughtStore.updateThought(current, updated) ? updated : null;
        }
    }

    /** Performs garbage collection on old, terminal (DONE/FAILED) thoughts. */
    static class GarbageCollector implements Runnable {
        private final ThoughtStore thoughtStore;
        private final long gcThresholdMillis;
        private static final Set<Status> TERMINAL_STATUSES = Set.of(Status.DONE, Status.FAILED);

        GarbageCollector(ThoughtStore store, long threshold) {
            this.thoughtStore = store;
            // Ensure threshold is reasonably positive
            this.gcThresholdMillis = Math.max(1000L, threshold); // Min 1 second threshold
        }

        @Override public void run() {
            long now = System.currentTimeMillis();
            long cutoffTime = now - gcThresholdMillis;
            int removedCount = 0;

            // Iterate over a snapshot of thought IDs to avoid issues with concurrent modification
            // and reduce memory overhead compared to copying all thoughts.
            Set<String> currentIds = new HashSet<>();
            thoughtStore.getAllThoughts().forEach(t -> currentIds.add(t.id()));

            for (String id : currentIds) {
                thoughtStore.getThought(id).ifPresent(thought -> { // Re-fetch thought to get latest status
                    if (TERMINAL_STATUSES.contains(thought.status())) {
                        long lastUpdated = thought.getMetadata("last_updated_timestamp", Long.class).orElse(0L);
                        // Use creation time if lastUpdated is missing (shouldn't happen ideally)
                        if (lastUpdated == 0) lastUpdated = thought.getMetadata("creation_timestamp", Long.class).orElse(0L);

                        if (lastUpdated > 0 && lastUpdated < cutoffTime) {
                            // Attempt removal. Concurrency safe due to ThoughtStore implementation.
                            if (thoughtStore.removeThought(id)) {
                                // Use a local counter, cannot modify removedCount directly from lambda easily
                                // This part is slightly complex due to lambda scope, alternative below.
                            }
                        }
                    }
                });
            }

            // Simpler alternative: Stream and collect removable IDs, then remove
            List<String> removableIds = thoughtStore.getAllThoughts().stream()
                    .filter(t -> TERMINAL_STATUSES.contains(t.status()))
                    .filter(t -> t.getMetadata("last_updated_timestamp", Long.class).orElse(
                            t.getMetadata("creation_timestamp", Long.class).orElse(0L)) < cutoffTime)
                    .map(Thought::id)
                    .toList();

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

        public void loadBootstrapMetaThoughts() {
            System.out.println("Loading Bootstrap META_THOUGHTs...");
            int count = 0;
            // MT-NOTE-TO-GOAL: Converts a specific NOTE atom to a GOAL atom
            count += addMeta("NOTE-TO-GOAL", Role.NOTE, a("convert_note_to_goal"),
                    s("sequence", l(s("add_thought", a("GOAL"), a("process_converted_item"), a("DEFAULT_POSITIVE")), s("set_status", a("DONE")))),
                    Belief.DEFAULT_POSITIVE, "Converts 'convert_note_to_goal' NOTE to a GOAL");

            // MT-GOAL-DECOMPOSE: Decomposes goal via LLM generate_thoughts
            count += addMeta("GOAL-DECOMPOSE", Role.GOAL, s("decompose", v("GoalContent")),
                    s("sequence", l(s("set_status", a("WAITING_CHILDREN")), s("generate_thoughts", s("prompt", a("Decompose goal:"), v("GoalContent"))))),
                    Belief.DEFAULT_POSITIVE, "Decomposes GOAL via LLM");

            // MT-GOAL-EXECUTE-LLM: Executes goal via LLM call_llm
            count += addMeta("GOAL-EXECUTE-LLM", Role.GOAL, s("execute_via_llm", v("GoalContent")),
                    s("sequence", l(s("call_llm", s("prompt", a("Execute goal:"), v("GoalContent")), a("OUTCOME")), s("set_status", a("DONE")))),
                    Belief.DEFAULT_POSITIVE, "Executes GOAL via LLM, creates OUTCOME");

            // MT-STRATEGY-EXECUTE: Executes a strategy, creates outcome, checks parent
            count += addMeta("STRATEGY-EXECUTE", Role.STRATEGY, s("execute_strategy", v("StrategyContent")),
                    s("sequence", l(s("add_thought", a("OUTCOME"), s("result", v("StrategyContent"), a("completed")), a("DEFAULT_POSITIVE")), s("set_status", a("DONE")), s("check_parent_completion", a("ALL_TERMINAL"), a("DONE"), a("FALSE")))),
                    Belief.DEFAULT_POSITIVE, "Executes STRATEGY, creates OUTCOME, checks parent");

            // MT-OUTCOME-PROCESS: Marks outcome done, checks parent
            count += addMeta("OUTCOME-PROCESS", Role.OUTCOME, v("AnyOutcomeContent"), // Matches any outcome
                    s("sequence", l(s("set_status", a("DONE")), s("check_parent_completion", a("ALL_TERMINAL"), a("DONE"), a("FALSE")))),
                    Belief.DEFAULT_POSITIVE, "Processes OUTCOME, marks DONE, checks parent");

            // MT-REFLECT-GEN-META: Generates new meta via LLM
            count += addMeta("REFLECT-GEN-META", Role.GOAL, a("generate_new_meta_thought"),
                    s("sequence", l(s("generate_thoughts", s("prompt", a("generate_meta_thought"))), s("set_status", a("DONE")))),
                    Belief.DEFAULT_POSITIVE, "Generates a new META_THOUGHT via LLM");

            System.out.println("Bootstrap META_THOUGHTs loaded: " + count);
        }

        // Helper to add a META_THOUGHT, returns 1 if added, 0 otherwise
        private int addMeta(String idHint, Role targetRole, Term target, Term action, Belief belief, String description) {
            String id = "META-" + idHint.replaceAll("\\W", "_");
            // Avoid adding if already present (e.g., from loaded state)
            if (thoughtStore.getThought(id).isPresent()) return 0;

            Thought meta = new ThoughtBuilder().id(id).role(Role.META_THOUGHT)
                    .content(s("meta_def", target, action))
                    .belief(belief).status(Status.PENDING) // Status PENDING allows it to be potentially modified, but won't be run by loop
                    .putMetadata("description", "Bootstrap: " + description)
                    .putMetadata("target_role", targetRole.name())
                    .build();
            thoughtStore.addThought(meta);
            return 1;
        }
    }
}