package dumb;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;

/**
 * # Cognitive Logic Engine (CLE) - Version 2.0 Synthesis
 * <p>
 * Represents a significantly enhanced and refactored Probabilistic Logic Network implementation,
 * designed for general intelligence research, emphasizing adaptability, autonomy, and cognitive power.
 * It recursively integrates its components and knowledge using its own representational framework ("dogfooding").
 * <p>
 * ## Core Design Principles:
 * - **Unified Representation:** All concepts, relations, events, goals, plans, and even system states
 * are represented uniformly as `Atom` instances (Nodes or Links).
 * - **Probabilistic & Temporal:** Core reasoning uses probabilistic truth values (`Certainty`) and
 * explicit temporal scopes (`TimeSpec`).
 * - **Importance-Driven Attention:** `Atom` importance (STI/LTI) guides attention allocation, inference focus,
 * learning priorities, and memory management (forgetting).
 * - **Recursive Integration:** Inference rules operate on Atoms, potentially producing new Atoms representing
 * derived knowledge, plans, or beliefs. The Agent uses inference and planning (which use the memory)
 * to interact with the Environment, learning new Atoms that refine its memory and future behavior.
 * - **Metaprogrammatic Potential:** The structure allows representing agent goals, beliefs, inference strategies,
 * or system states as Atoms, enabling meta-reasoning (though advanced meta-reasoning requires further rule development).
 * - **Dynamic & Hierarchical:** Knowledge is added dynamically. Links create hierarchical structures (e.g., Evaluation links
 * predicating on other links or nodes).
 * - **Consolidated & Modular:** Delivered as a single, self-contained file using nested static classes for logical modularity.
 * - **Modern Java:** Leverages Records, Streams, VarHandles (for efficient volatile access), Concurrent Collections, etc.
 * <p>
 * ## Key Enhancements over Previous Versions:
 * - **Refined Naming:** Identifiers are concise, accurate, and follow lower-level descriptors (e.g., `Certainty`, `Atom`, `Node`, `Link`).
 * - **Enhanced `Certainty`:** More robust merging logic.
 * - **Improved Importance:** Uses `VarHandle` for atomic updates, refined decay/boost logic, and integration with confidence.
 * - **Advanced Memory:** More efficient indexing, robust atom revision, integrated time provider.
 * - **Sophisticated Inference:**
 * - More robust rule implementations (Deduction, Inversion, Modus Ponens).
 * - Basic Unification implemented and integrated into Backward Chaining.
 * - More explicit handling of Temporal Rules (basic Persistence, Effect application).
 * - Forward Chaining prioritizes based on combined importance and confidence.
 * - **Enhanced Planning:** More robust recursive planner, better integration of preconditions, handles action schemas.
 * - **Autonomous Agent:** More sophisticated state representation (`perceiveState`), improved action selection (planning + reactive utility),
 * enhanced learning (state transitions, reward/utility association, basic reinforcement).
 * - **Dogfooding Examples:** Goals, state representations, and learned associations are directly represented as Atoms/Links.
 * - **Code Quality:** Improved structure, clarity, efficiency (e.g., VarHandles), and adherence to modern Java practices. Reduced redundancy.
 *
 * @version 2.0 Synthesis
 */
public final class Cog {

    // --- Configuration Constants ---
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0; // k in confidence = count / (count + k)
    private static final double TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE = 0.05;
    private static final double TRUTH_MIN_STRENGTH_FOR_RELEVANCE = 0.01;

    // Inference
    private static final double INFERENCE_CONFIDENCE_DISCOUNT = 0.9; // General uncertainty factor
    private static final double INFERENCE_TEMPORAL_DISCOUNT = 0.8; // Higher uncertainty for temporal projection
    private static final int INFERENCE_DEFAULT_MAX_DEPTH = 5;
    private static final int FORWARD_CHAINING_BATCH_SIZE = 50;

    // Importance / Attention / Forgetting
    private static final double IMPORTANCE_MIN_FORGET_THRESHOLD = 0.02; // Combined STI/LTI threshold for forgetting
    private static final double IMPORTANCE_INITIAL_STI = 0.1;
    private static final double IMPORTANCE_INITIAL_LTI_FACTOR = 0.1; // LTI starts as a fraction of initial STI
    private static final double IMPORTANCE_STI_DECAY_RATE = 0.05; // Multiplicative decay per cycle/access
    private static final double IMPORTANCE_LTI_DECAY_RATE = 0.005; // Slower LTI decay
    private static final double IMPORTANCE_STI_TO_LTI_RATE = 0.02; // Rate LTI learns from STI
    private static final double IMPORTANCE_BOOST_ON_ACCESS = 0.05; // STI boost when Atom is used/retrieved
    private static final double IMPORTANCE_BOOST_ON_REVISION_MAX = 0.4; // Max STI boost from significant revision
    private static final double IMPORTANCE_BOOST_ON_GOAL_FOCUS = 0.9; // STI boost for active goals
    private static final double IMPORTANCE_BOOST_ON_PERCEPTION = 0.7; // STI boost for directly perceived atoms
    private static final double IMPORTANCE_BOOST_INFERRED_FACTOR = 0.3; // How much importance is inherited during inference
    private static final long FORGETTING_CHECK_INTERVAL_MS = 10000; // Check every 10 seconds
    private static final int FORGETTING_MAX_MEM_SIZE_TRIGGER = 15000; // Check if mem exceeds this size
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // Target size as % of max after forgetting
    private static final Set<String> PROTECTED_NODE_NAMES = Set.of("Reward", "GoodAction", "Self", "Goal"); // Core concepts

    // Planning & Agent
    private static final int PLANNING_DEFAULT_MAX_PLAN_DEPTH = 8; // Max actions in a plan
    private static final double AGENT_DEFAULT_PERCEPTION_COUNT = 5.0; // Default evidence count for perceived facts
    private static final double AGENT_DEFAULT_LEARNING_COUNT = 1.0; // Default evidence count for learned associations
    private static final double AGENT_UTILITY_THRESHOLD_FOR_SELECTION = 0.1;
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05; // Epsilon for exploration

    // Naming Conventions
    private static final String VARIABLE_PREFIX = "?"; // SPARQL-like prefix for variables

    // --- Core Components ---
    private final Memory mem;
    private final Infer inference;
    private final Agent agent;
    private final ScheduledExecutorService scheduler;

    /**
     * logical, monotonic time for internal ordering/recency
     */
    private final AtomicLong iteration = new AtomicLong(0);

    // --- Constructor ---
    public Cog() {
        this.mem = new Memory(this::iteration);
        this.inference = new Infer(this.mem);
        this.agent = new Agent();

        // Schedule periodic maintenance (forgetting, importance decay)
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "CLE-Maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::performMaintenance,
                FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println("Cognitive Logic Engine Initialized.");
    }

    // --- Public Facade API ---


    // === Main Method (Comprehensive Demonstration)                       ===

    public static void main(String[] args) {
        var cle = new Cog();
        try {
            System.out.println("\n--- Cognitive Logic Engine Demonstration ---");

            // --- 1. Basic Knowledge & Forward Chaining ---
            System.out.println("\n[1] Basic Knowledge & Forward Chaining:");
            var cat = cle.getOrCreateNode("Cat");
            var mammal = cle.getOrCreateNode("Mammal");
            var animal = cle.getOrCreateNode("Animal");
            var dog = cle.getOrCreateNode("Dog");
            var predator = cle.getOrCreateNode("Predator");
            var hasClaws = cle.getOrCreateNode("HasClaws");

            cle.learn(Link.Type.INHERITANCE, Truth.of(0.95, 20), 0.8, cat, mammal);
            cle.learn(Link.Type.INHERITANCE, Truth.of(0.98, 50), 0.9, mammal, animal);
            cle.learn(Link.Type.INHERITANCE, Truth.of(0.90, 30), 0.8, dog, mammal);
            cle.learn(Link.Type.INHERITANCE, Truth.of(0.7, 10), 0.6, cat, predator);
            cle.learn(Link.Type.INHERITANCE, Truth.of(0.8, 15), 0.7, predator, hasClaws);

            cle.reasonForward(2); // Run inference

            System.out.println("\nChecking derived knowledge:");
            var dogAnimalLinkId = Link.id(Link.Type.INHERITANCE, List.of(dog.id, animal.id));
            cle.retrieveAtom(dogAnimalLinkId)
                    .ifPresentOrElse(
                            atom -> System.out.println(" -> Derived Dog->Animal: " + atom),
                            () -> System.out.println(" -> Derived Dog->Animal: Not found (Expected).")
                    );

            var catClawsLinkId = Link.id(Link.Type.INHERITANCE, List.of(cat.id, hasClaws.id));
            cle.retrieveAtom(catClawsLinkId)
                    .ifPresentOrElse(
                            atom -> System.out.println(" -> Derived Cat->HasClaws: " + atom),
                            () -> System.out.println(" -> Derived Cat->HasClaws: Not found (Expected).")
                    );

            // --- 2. Backward Chaining Query & Unification ---
            System.out.println("\n[2] Backward Chaining & Unification:");
            var varX = cle.getOrCreateVariable("X");
            var chasesPred = cle.getOrCreateNode("Predicate:Chases"); // Use prefix convention
            var ball = cle.getOrCreateNode("Ball");
            // Define 'Dog chases Ball' using an Evaluation link: Evaluation(Chases, Dog, Ball)
            cle.learn(Link.Type.EVALUATION, Truth.of(0.85, 10), 0.7, chasesPred, dog, ball);

            // Query: Evaluation(Chases, ?X, Ball) - What chases the ball?
            Atom queryPattern = new Link(Link.Type.EVALUATION, List.of(chasesPred.id, varX.id, ball.id), Truth.UNKNOWN, null);
            System.out.println("Query: " + queryPattern.id);
            var results = cle.query(queryPattern, 3);
            if (results.isEmpty()) {
                System.out.println(" -> No results found.");
            } else {
                results.forEach(res -> System.out.println(" -> Result: " + res));
            }

            // --- 3. Temporal Logic & Planning ---
            System.out.println("\n[3] Temporal Logic & Planning:");
            var agentNode = cle.getOrCreateNode("Self"); // Standard node for the agent
            var key = cle.getOrCreateNode("Key");
            var door = cle.getOrCreateNode("Door");
            var locationA = cle.getOrCreateNode("LocationA");
            var locationB = cle.getOrCreateNode("LocationB");
            var hasKeyFluent = cle.getOrCreateNode("Fluent:HasKey");
            var doorOpenFluent = cle.getOrCreateNode("Fluent:DoorOpen");
            var atLocationPred = cle.getOrCreateNode("Predicate:AtLocation");
            var pickupAction = cle.getOrCreateNode("Action:PickupKey");
            var openAction = cle.getOrCreateNode("Action:OpenDoor");
            var moveToBAction = cle.getOrCreateNode("Action:MoveToB");

            // Initial State (Time = 0 assumed for simplicity in setup)
            var t0 = cle.iteration(); // Or cle.incrementLogicalTime();
            // HoldsAt(Predicate:AtLocation(Self, LocationA), T0)
            var agentAtA = cle.learn(Link.Type.HOLDS_AT, Truth.TRUE, 1.0, cle.timePoint(t0),
                    cle.learn(Link.Type.EVALUATION, Truth.TRUE, 1.0, atLocationPred, agentNode, locationA));
            // NOT HoldsAt(Fluent:HasKey(Self), T0) - Represented by low strength/confidence
            var notHasKey = cle.learn(Link.Type.HOLDS_AT, Truth.FALSE, 1.0, cle.timePoint(t0),
                    cle.learn(Link.Type.EVALUATION, Truth.TRUE, 1.0, hasKeyFluent, agentNode)); // Evaluation represents the state itself
            // NOT HoldsAt(Fluent:DoorOpen(Door), T0)
            var notDoorOpen = cle.learn(Link.Type.HOLDS_AT, Truth.FALSE, 1.0, cle.timePoint(t0),
                    cle.learn(Link.Type.EVALUATION, Truth.TRUE, 1.0, doorOpenFluent, door));

            // Rules (Simplified - Using Predictive Implication for planning effects)
            // Precondition: HoldsAt(AtLocation(Self, LocationA))
            Atom precondPickup = agentAtA; // Use the state link directly
            // Effect: HoldsAt(HasKey(Self))
            Atom effectPickup = cle.learn(Link.Type.HOLDS_AT, Truth.TRUE, 0.9, // Effect will be true
                    cle.learn(Link.Type.EVALUATION, Truth.TRUE, 1.0, hasKeyFluent, agentNode));
            // Rule: Sequence(Precond, Action) => Effect [Time: duration]
            var pickupSeq = cle.learn(Link.Type.SEQUENCE, Truth.TRUE, 0.9, precondPickup, pickupAction);
            cle.learn(Link.Type.PREDICTIVE_IMPLICATION, Truth.of(0.95, 10), 0.9, cle.duration(100), pickupSeq, effectPickup);

            // Precondition: HoldsAt(HasKey(Self))
            var precondOpen = effectPickup; // Use the effect of the previous action's potential outcome
            // Effect: HoldsAt(DoorOpen(Door))
            Atom effectOpen = cle.learn(Link.Type.HOLDS_AT, Truth.TRUE, 0.9,
                    cle.learn(Link.Type.EVALUATION, Truth.TRUE, 1.0, doorOpenFluent, door));
            // Rule: Sequence(Precond, Action) => Effect
            var openSeq = cle.learn(Link.Type.SEQUENCE, Truth.TRUE, 0.9, precondOpen, openAction);
            cle.learn(Link.Type.PREDICTIVE_IMPLICATION, Truth.of(0.9, 10), 0.9, cle.duration(200), openSeq, effectOpen);

            // Precondition: HoldsAt(DoorOpen(Door))
            var precondMove = effectOpen;
            // Effect: HoldsAt(AtLocation(Self, LocationB))
            Atom effectMove = cle.learn(Link.Type.HOLDS_AT, Truth.TRUE, 0.9,
                    cle.learn(Link.Type.EVALUATION, Truth.TRUE, 1.0, atLocationPred, agentNode, locationB));
            // Rule: Sequence(Precond, Action) => Effect
            var moveSeq = cle.learn(Link.Type.SEQUENCE, Truth.TRUE, 0.9, precondMove, moveToBAction);
            cle.learn(Link.Type.PREDICTIVE_IMPLICATION, Truth.of(0.98, 10), 0.9, cle.duration(300), moveSeq, effectMove);


            // Goal: Agent At Location B -> HoldsAt(AtLocation(Self, LocationB))
            var goal = effectMove;
            // Boost goal importance
            cle.retrieveAtom(goal.id).ifPresent(g -> g.updateImportance(IMPORTANCE_BOOST_ON_GOAL_FOCUS, cle.iteration()));

            System.out.println("\nPlanning to achieve goal: " + goal.id);
            var planOpt = cle.plan(goal, 5, 3); // Max 5 actions, max 3 search depth for preconditions

            planOpt.ifPresentOrElse(
                    plan -> {
                        System.out.println("Plan Found:");
                        plan.forEach(action -> System.out.println(" -> " + action.id));
                    },
                    () -> System.out.println("No plan found.")
            );

            // --- 4. Agent Simulation (Simple Grid World) ---
            System.out.println("\n[4] Agent Simulation (Basic Grid World):");
            var gridWorld = new BasicGridWorld(cle, 4, 2, 2); // 4x4 grid, goal at (2,2)
            var goalLocationNode = cle.getOrCreateNode("Pos_2_2");
            var agentAtPred = precondPickup; //???
            Atom goalState = cle.learn(Link.Type.HOLDS_AT, Truth.TRUE, 1.0,
                    cle.learn(Link.Type.EVALUATION, Truth.TRUE, 1.0, agentAtPred, agentNode, goalLocationNode));
            cle.runAgent(gridWorld, goalState, 20); // Run agent for max 20 steps


            // --- 5. Forgetting Check ---
            System.out.println("\n[5] Forgetting Check:");
            var sizeBefore = cle.mem.getAtomCount();
            System.out.println("Mem size before wait: " + sizeBefore);
            System.out.println("Waiting for maintenance cycle...");
            try {
                Thread.sleep(FORGETTING_CHECK_INTERVAL_MS + 1000);
            } catch (InterruptedException ignored) {
            }
            var sizeAfter = cle.mem.getAtomCount();
            System.out.println("Mem size after wait: " + sizeAfter + " (Removed: " + (sizeBefore - sizeAfter) + ")");


        } catch (Exception e) {
            System.err.println("\n--- ERROR during demonstration ---");
            e.printStackTrace();
        } finally {
            cle.shutdown(); // Ensure scheduler is stopped
        }
        System.out.println("\n--- Cognitive Logic Engine Demonstration Finished ---");
    }

    private static double unitize(double initialSTI) {
        return Math.max(0.0, Math.min(1.0, initialSTI));
    }

    /**
     * Provides the current logical time step.
     */
    public long iteration() {
        return iteration.get();
    }

    /**
     * Adds or revises an Atom in the Knowledge Base.
     * Handles certainty merging, importance updates, and indexing.
     *
     * @param atom The Atom to add or revise.
     * @return The resulting Atom in the memory (potentially the existing one after revision).
     */
    public Atom learn(Atom atom) {
        return mem.learn(atom);
    }

    /**
     * Retrieves an Atom by its unique ID, boosting its importance.
     *
     * @param id The unique ID of the Atom.
     * @return An Optional containing the Atom if found, empty otherwise.
     */
    public Optional<Atom> retrieveAtom(String id) {
        return mem.atom(id);
    }

    /**
     * Gets or creates a Node Atom by its name.
     * If created, uses default certainty and initial importance.
     *
     * @param name The conceptual name of the node.
     * @return The existing or newly created Node.
     */
    public Node getOrCreateNode(String name) {
        return mem.node(name, Truth.UNKNOWN, IMPORTANCE_INITIAL_STI);
    }

    /**
     * Gets or creates a Node Atom with specified initial certainty and importance.
     *
     * @param name       The conceptual name.
     * @param certainty  Initial certainty.
     * @param initialSTI Initial Short-Term Importance.
     * @return The existing or newly created Node.
     */
    public Node getOrCreateNode(String name, Truth certainty, double initialSTI) {
        return mem.node(name, certainty, initialSTI);
    }

    /**
     * Gets or creates a Variable Node Atom. Variables are typically protected from forgetting.
     *
     * @param name The variable name (without prefix).
     * @return The existing or newly created VariableNode.
     */
    public Var getOrCreateVariable(String name) {
        return mem.var(name);
    }

    /**
     * Creates a TimeSpec representing a specific time point.
     */
    public Time timePoint(long time) {
        return Time.at(time);
    }

    /**
     * Creates a TimeSpec representing a time interval.
     */
    public Time timeInterval(long start, long end) {
        return Time.between(start, end);
    }

    /**
     * Creates a TimeSpec representing a relative duration.
     */
    public Time duration(long duration) {
        return Time.range(duration);
    }

    /**
     * Creates and learns a Link Atom connecting target Atoms.
     *
     * @param type       The type of relationship.
     * @param certainty  The initial certainty of the link.
     * @param initialSTI Initial Short-Term Importance.
     * @param targets    The target Atoms being linked.
     * @return The learned Link Atom.
     */
    public Link learn(Link.Type type, Truth certainty, double initialSTI, Atom... targets) {
        return mem.link(type, certainty, initialSTI, null, targets);
    }

    /**
     * Creates and learns a Link Atom with an associated TimeSpec.
     *
     * @param type       The type of relationship.
     * @param certainty  The initial certainty of the link.
     * @param initialSTI Initial Short-Term Importance.
     * @param time       The temporal specification.
     * @param targets    The target Atoms being linked.
     * @return The learned Link Atom.
     */
    public Link learn(Link.Type type, Truth certainty, double initialSTI, Time time, Atom... targets) {
        return mem.link(type, certainty, initialSTI, time, targets);
    }

    /**
     * Creates and learns a Link Atom using target Atom IDs.
     * Less safe if IDs might not exist, but convenient.
     *
     * @param type       The type of relationship.
     * @param certainty  The initial certainty of the link.
     * @param initialSTI Initial Short-Term Importance.
     * @param targetIds  The IDs of the target Atoms.
     * @return The learned Link Atom, or null if target IDs are invalid.
     */
    public Link learn(Link.Type type, Truth certainty, double initialSTI, String... targetIds) {
        return mem.link(type, certainty, initialSTI, null, targetIds);
    }

    /**
     * Creates and learns a Link Atom with a TimeSpec, using target Atom IDs.
     *
     * @param type       The type of relationship.
     * @param certainty  The initial certainty of the link.
     * @param initialSTI Initial Short-Term Importance.
     * @param time       The temporal specification.
     * @param targetIds  The IDs of the target Atoms.
     * @return The learned Link Atom, or null if target IDs are invalid.
     */
    public Link learn(Link.Type type, Truth certainty, double initialSTI, Time time, String... targetIds) {
        return mem.link(type, certainty, initialSTI, time, targetIds);
    }

    /**
     * Performs forward chaining inference, deriving new knowledge based on importance heuristics.
     *
     * @param maxSteps The maximum number of inference batches to perform.
     */
    public void reasonForward(int maxSteps) {
        inference.forwardChain(maxSteps);
    }

    /**
     * Queries the knowledge base using backward chaining to determine the certainty of a pattern Atom.
     * Supports variables and unification.
     *
     * @param queryPattern The Atom pattern to query (can contain VariableNodes).
     * @param maxDepth     Maximum recursion depth for the search.
     * @return A list of QueryResult records, each containing bind and the inferred Atom matching the query.
     */
    public List<Answer> query(Atom queryPattern, int maxDepth) {
        return inference.backwardChain(queryPattern, maxDepth);
    }

    /**
     * Attempts to find a sequence of action Atoms (e.g., Action Nodes or Execution Links)
     * predicted to achieve the desired goal state Atom.
     *
     * @param goalPattern    The Atom pattern representing the desired goal state.
     * @param maxPlanDepth   Maximum length of the action sequence.
     * @param maxSearchDepth Maximum recursion depth for finding preconditions.
     * @return An Optional containing a list of action Atoms representing the plan, or empty if no plan found.
     */
    public Optional<List<Atom>> plan(Atom goalPattern, int maxPlanDepth, int maxSearchDepth) {
        return inference.planToActionSequence(goalPattern, maxPlanDepth, maxSearchDepth);
    }

    /**
     * Runs the agent's perceive-reason-act-learn cycle within a given environment.
     *
     * @param environment The environment the agent interacts with.
     * @param goalAtom    The primary goal the agent tries to achieve.
     * @param maxCycles   The maximum number of cycles to run the agent.
     */
    public void runAgent(Game environment, Atom goalAtom, int maxCycles) {
        agent.runLoop(environment, goalAtom, maxCycles);
    }

    /**
     * Performs periodic maintenance tasks like forgetting low-importance atoms and decaying importance.
     */
    private void performMaintenance() {
        mem.decayAndForget();
    }

    /**
     * Shuts down the background maintenance scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Cognitive Logic Engine scheduler shut down.");
    }


    public interface Game {
        /**
         * Perceive the current state of the environment.
         */
        Percept perceive();

        /**
         * Get the list of actions currently possible for the agent.
         */
        List<Atom> actions(); // Should return Action schema Atoms (e.g., Nodes)

        /**
         * Execute the chosen action schema in the environment.
         */
        Act exe(Atom actionSchema);

        /**
         * Check if the environment simulation has not reached a terminal state.
         */
        boolean running();
    }


    public static class Memory {
        // Primary Atom store: ID -> Atom
        private final ConcurrentMap<String, Atom> atoms = new ConcurrentHashMap<>(256);
        // Indices for efficient link retrieval
        private final ConcurrentMap<Link.Type, ConcurrentSkipListSet<String>> linksByType = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, ConcurrentSkipListSet<String>> linksByTarget = new ConcurrentHashMap<>();
        private final Supplier<Long> time;

        public Memory(Supplier<Long> time) {
            this.time = time;
            // Pre-populate link type index map for efficiency
            for (var type : Link.Type.values()) {
                linksByType.put(type, new ConcurrentSkipListSet<>());
            }
        }

        /**
         * Adds or updates an Atom, handling revision, importance, and indexing.
         */
        public <A extends Atom> A learn(A atom) {
            final long currentTime = time.get();
            final var atomId = atom.id;

            var result = atoms.compute(atomId, (id, existingAtom) -> {
                if (existingAtom == null) {
                    atom.updateAccessTime(currentTime);
                    atom.updateImportance(IMPORTANCE_INITIAL_STI * 0.5, currentTime); // Small boost on initial learn
                    return atom;
                } else {
                    // Perform revision: merge certainty, update importance, merge TimeSpec
                    var oldCertainty = existingAtom.truth();
                    var revisedCertainty = oldCertainty.merge(atom.truth());
                    existingAtom.truth(revisedCertainty); // Update certainty in place
                    existingAtom.updateAccessTime(currentTime);

                    var boost = revisionBoost(oldCertainty, atom.truth(), revisedCertainty);
                    existingAtom.updateImportance(boost + IMPORTANCE_BOOST_ON_ACCESS, currentTime); // Boost on revision + access

                    if (existingAtom instanceof Link existingLink && atom instanceof Link newLink) {
                        existingLink.setTime(Time.merge(existingLink, newLink));
                    }
                    // Log revision? System.out.printf("Revised: %s -> %s%n", existingAtom.id(), revisedCertainty);
                    return existingAtom;
                }
            });

            // Update indices if it's a Link and it was newly added or potentially modified
            if (result instanceof Link link)
                updateIndices(link);

            // Trigger forgetting check if Memory grows too large (asynchronously)
            if (atoms.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER)
                CompletableFuture.runAsync(this::decayAndForget);

            //noinspection unchecked
            return (A) result;
        }

        /**
         * Retrieves an Atom by ID, boosting its importance.
         */
        public Optional<Atom> atom(String id) {
            var atom = atoms.get(id);
            if (atom != null) {
                atom.updateImportance(IMPORTANCE_BOOST_ON_ACCESS, time.get());
            }
            return Optional.ofNullable(atom);
        }

        /**
         * Gets or creates a Node by name with specific initial state.
         */
        public Node node(String name, Truth certainty, double initialSTI) {
            return (Node) atoms.computeIfAbsent(Node.id(name), id -> {
                var newNode = new Node(name, certainty);
                newNode.importance(initialSTI, time.get());
                return newNode;
            });
        }

        public Node node(String name) {
            return (Node) atom(Node.id(name)).orElse(null);
        }

        /**
         * Gets or creates a variable
         */
        public Var var(String name) {
            var varId = Var.varID(name);
            return (Var) atoms.computeIfAbsent(varId, id -> {
                var vn = new Var(name);
                vn.importance(0.0, time.get()); // vars have 0 STI, high LTI implied by protection
                vn.lti(1); // Ensure LTI is high
                return vn;
            });
        }

        /**
         * Creates and learns a Link from Atom instances.
         */
        public Link link(Link.Type type, Truth certainty, double initialSTI, Time time, Atom... targets) {
            // Use List.of() or toList()
            return link(type, certainty, initialSTI, time, Arrays.stream(targets)
                    .filter(Objects::nonNull)
                    .map(atom -> atom.id)
                    .toList()
            );
        }

        /**
         * Creates and learns a Link from target IDs.
         */
        public Link link(Link.Type type, Truth certainty, double initialSTI, Time time, String... targetIds) {
            if (targetIds == null || targetIds.length == 0 || Arrays.stream(targetIds).anyMatch(Objects::isNull)) {
                System.err.printf("Warning: Attempted to create link type %s with null or empty targets.%n", type);
                return null; // Or throw exception
            }
            // Basic check if target IDs exist (optional, adds overhead)
            // for (String targetId : targetIds) { if (!atoms.containsKey(targetId)) { System.err.println("Warning: Link target missing: " + targetId); } }
            return link(type, certainty, initialSTI, time, Arrays.asList(targetIds));
        }

        private Link link(Link.Type type, Truth certainty, double initialSTI, Time time, List<String> targetIds) {
            var link = new Link(type, targetIds, certainty, time);
            link.importance(initialSTI, this.time.get());
            return learn(link); // Use learnAtom for revision/indexing logic
        }

        /**
         * Removes an Atom and updates indices. Needs external synchronization if called outside learnAtom's compute.
         */
        private void removeAtomInternal(String id) {
            var removed = atoms.remove(id);
            if (removed instanceof Link link)
                removeIndices(link);
        }

        /**
         * Updates indices for a given link.
         */
        private void updateIndices(Link link) {
            linksByType.get(link.type).add(link.id); // Assumes type exists in map
            for (var targetId : link.targets) {
                linksByTarget.computeIfAbsent(targetId, k -> new ConcurrentSkipListSet<>()).add(link.id);
            }
        }

        /**
         * Removes indices for a given link.
         */
        private void removeIndices(Link link) {
            Optional.ofNullable(linksByType.get(link.type)).ifPresent(set -> set.remove(link.id));
            for (var targetId : link.targets) {
                Optional.ofNullable(linksByTarget.get(targetId)).ifPresent(set -> set.remove(link.id));
                // Optional: Clean up empty target sets periodically?
                // if (linksByTarget.containsKey(targetId) && linksByTarget.get(targetId).isEmpty()) { linksByTarget.remove(targetId); }
            }
        }

        /**
         * Retrieves links of a specific type, boosting importance.
         */
        public Stream<Link> links(Link.Type type) {
            var links = linksByType.get(type);
            return links.isEmpty() ? Stream.empty() : links
                    .stream()
                    .map(this::atom) // Use retrieveAtom to boost importance
                    .filter(Optional::isPresent).map(Optional::get)
                    .filter(Link.class::isInstance)
                    .map(Link.class::cast);
        }

        /**
         * Retrieves links that include a specific Atom ID as a target, boosting importance.
         */
        public Stream<Link> linksWithTarget(String targetId) {
            var links = linksByTarget.get(targetId);
            return links.isEmpty() ? Stream.empty() : links
                    .stream()
                    .map(this::atom) // Boost importance
                    .filter(Optional::isPresent).map(Optional::get)
                    .filter(Link.class::isInstance)
                    .map(Link.class::cast);
        }

        /**
         * Retrieves all atoms. Does NOT update importance. Use for bulk operations like forgetting.
         */
        public Collection<Atom> getAllAtomsUnsafe() {
            return atoms.values();
        }

        /**
         * Gets the current number of atoms in the Memory.
         */
        public int getAtomCount() {
            return atoms.size();
        }

        /**
         * Implements importance decay and forgetting. Called periodically.
         */
        public synchronized void decayAndForget() {
            final long currentTime = time.get();
            var initialSize = atoms.size();
            if (initialSize == 0) return;

            List<Atom> candidates = new ArrayList<>(atoms.values()); // Copy for safe iteration/removal
            var decayCount = 0;
            var removedCount = 0;

            // 1. Decay importance for all atoms
            for (var atom : candidates) {
                atom.decayImportance(currentTime);
                decayCount++;
            }

            // 2. Forget low-importance atoms if Memory is large
            var targetSize = (FORGETTING_MAX_MEM_SIZE_TRIGGER * FORGETTING_TARGET_MEM_SIZE_FACTOR) / 100;
            if (initialSize > FORGETTING_MAX_MEM_SIZE_TRIGGER || initialSize > targetSize * 1.1) { // Check size triggers
                // Sort by current combined importance (ascending)
                candidates.sort(Comparator.comparingDouble(a -> a.getCurrentImportance(currentTime)));

                var removalTargetCount = Math.max(0, initialSize - targetSize);

                for (var atom : candidates) {
                    if (removedCount >= removalTargetCount) break; // Removed enough

                    var currentImportance = atom.getCurrentImportance(currentTime);
                    if (currentImportance < IMPORTANCE_MIN_FORGET_THRESHOLD) {
                        var isProtected = (atom instanceof Node node && PROTECTED_NODE_NAMES.contains(node.name))
                                || (atom instanceof Var);
                        // Add other protection logic? (e.g., part of active goal/plan - needs AgentController input)

                        if (!isProtected) {
                            removeAtomInternal(atom.id); // Use internal remove synchronized method
                            removedCount++;
                        }
                    } else {
                        // Since sorted, all remaining atoms have higher importance
                        break;
                    }
                }
            }

            if (removedCount > 0 || decayCount > 0) {
                System.out.printf("DEBUG: Maintenance Cycle: Decayed %d atoms. Removed %d atoms (below importance < %.3f). Memory size %d -> %d.%n",
                        decayCount, removedCount, IMPORTANCE_MIN_FORGET_THRESHOLD, initialSize, atoms.size());
            }
        }

        private double revisionBoost(Truth oldC, Truth newC, Truth revisedC) {
            var strengthChange = Math.abs(revisedC.strength - oldC.strength);
            var confidenceChange = Math.abs(revisedC.conf() - oldC.conf());
            // Boost more for significant changes or high-confidence confirmations
            var boost = (strengthChange * 0.6 + confidenceChange * 0.4) * (1.0 + newC.conf());
            return Math.min(IMPORTANCE_BOOST_ON_REVISION_MAX, boost); // Cap the boost
        }
    }


    // === Core Data Structures                                           ===


    // === Nested Static Class: InferenceEngine                           ===

    public static class Infer {
        private final Memory mem;
        private final Unify unify;

        public Infer(Memory mem) {
            this.mem = mem;
            this.unify = new Unify(mem);
        }

        // --- Core Inference Rules ---

        /**
         * PLN Deduction: (A->B, B->C) => A->C. Handles INHERITANCE, PREDICTIVE_IMPLICATION.
         */
        public Optional<Link> deduce(Link linkAB, Link linkBC) {
            if (linkAB.type != linkBC.type || !isValidBinaryLink(linkAB) || !isValidBinaryLink(linkBC))
                return empty();

            var bId = linkAB.targets.get(1);
            if (!bId.equals(linkBC.targets.get(0))) return empty(); // Must chain A->B, B->C

            var aId = linkAB.targets.get(0);
            var cId = linkBC.targets.get(1);
            // Retrieving atoms boosts their importance
            var nodeAOpt = atom(aId);
            var nodeBOpt = atom(bId);
            var nodeCOpt = atom(cId);
            if (nodeAOpt.isEmpty() || nodeBOpt.isEmpty() || nodeCOpt.isEmpty()) return empty();

            var cAB = linkAB.truth();
            var cBC = linkBC.truth();
            var cB = nodeBOpt.get().truth();
            var cC = nodeCOpt.get().truth(); // Base rates //TODO utilize 'cC' somehow

            // Simplified Deduction strength formula (avoids potential division by zero issues in original complex formula)
            // sAC approx sAB * sBC (More complex versions exist, but are sensitive to priors)
            // Let's use a weighted average based on confidence, tending towards product for low confidence
            var cABstrength = cAB.strength;
            var cBCstrength = cBC.strength;
            var cBConf = cB.conf();
            var sAC = unitize(
                    (cABstrength * cBCstrength * (1 - cBConf)) // Product term
                            + (cABstrength * cBCstrength + (1 - cABstrength) * cBCstrength * cAB.conf()) * cBConf // Towards implication
            );

            var discount = (linkAB.type == Link.Type.PREDICTIVE_IMPLICATION) ? INFERENCE_TEMPORAL_DISCOUNT : INFERENCE_CONFIDENCE_DISCOUNT;
            var nAC = discount * Math.min(cAB.evi, cBC.evi) * cBConf; // Confidence depends on middle term's confidence
            var cAC = Truth.of(sAC, nAC);

            if (cAC.conf() < TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE) return empty();

            var timeAC = (linkAB.type == Link.Type.PREDICTIVE_IMPLICATION) ?
                    Time.compose(linkAB.time(), linkBC.time()) : null;

            var inferredLink = new Link(linkAB.type, List.of(aId, cId), cAC, timeAC);

            var inferredSTI = calculateInferredImportance(linkAB, linkBC);
            inferredLink.importance(inferredSTI, mem.time.get());

            return Optional.of(mem.learn(inferredLink));
        }

        private Optional<Atom> atom(String aId) {
            return mem.atom(aId);
        }

        /**
         * PLN Inversion (Abduction/Bayes): (A->B) => (B->A). Handles INHERITANCE, PREDICTIVE_IMPLICATION.
         */
        public Optional<Link> invert(Link linkAB) {
            if (!isValidBinaryLink(linkAB)) return empty();

            var aId = linkAB.targets.getFirst();
            var nodeAOpt = atom(aId);
            if (nodeAOpt.isEmpty()) return empty();

            var bId = linkAB.targets.get(1);
            var nodeBOpt = atom(bId);
            if (nodeBOpt.isEmpty()) return empty();


            var cAB = linkAB.truth();
            var cA = nodeAOpt.get().truth();
            var cB = nodeBOpt.get().truth();

            // Bayes' Rule: s(B->A) = s(A->B) * s(A) / s(B)
            double sBA;
            double nBA;
            if (cB.strength < 1e-9) { // Avoid division by zero; P(B) approx 0
                sBA = 0.5; // Maximum uncertainty
                nBA = 0.0; // No evidence
            } else {
                sBA = Math.max(0.0, Math.min(1.0, cAB.strength * cA.strength / cB.strength)); // Clamp result
                var discount = (linkAB.type == Link.Type.PREDICTIVE_IMPLICATION) ? INFERENCE_TEMPORAL_DISCOUNT : INFERENCE_CONFIDENCE_DISCOUNT;
                // Confidence depends on confidence of all terms in Bayes theorem
                nBA = discount * cAB.evi * cA.conf() * cB.conf();
            }
            var cBA = Truth.of(sBA, nBA);

            if (cBA.conf() < TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE) return empty();

            var timeBA = (linkAB.type == Link.Type.PREDICTIVE_IMPLICATION) ? linkAB.time().inverse() : null;
            var inferredSTI = calculateInferredImportance(linkAB);

            var inferredLink = new Link(linkAB.type, List.of(bId, aId), cBA, timeBA); // Reversed targets
            inferredLink.importance(inferredSTI, mem.time.get());
            return Optional.of(mem.learn(inferredLink));
        }

        /**
         * Probabilistic Modus Ponens: (A, A->B) => Update B.
         */
        public Optional<Atom> modusPonens(Atom premiseA, Link implicationAB) {
            if (premiseA == null || !isValidBinaryLink(implicationAB) || !implicationAB.targets.get(0).equals(premiseA.id))
                return empty();

            var conclusionBOpt = atom(implicationAB.targets.get(1));
            if (conclusionBOpt.isEmpty()) return empty(); // Conclusion atom must exist to be updated

            var cA = premiseA.truth();
            var cAB = implicationAB.truth();

            // Calculate the evidence for B provided *by this specific inference*
            // s(B|A) = s(A->B) [Assuming independence, simple projection] - More sophisticated needed for complex PLN
            // Derived Strength: P(B) derived = P(A) * P(B|A) = s(A) * s(A->B)
            var derived_sB = unitize(cA.strength * cAB.strength);

            var discount = (implicationAB.type == Link.Type.PREDICTIVE_IMPLICATION) ? INFERENCE_TEMPORAL_DISCOUNT : INFERENCE_CONFIDENCE_DISCOUNT;
            // Derived Count: Confidence depends on both premise and rule confidence
            var derived_nB = discount * Math.min(cA.evi, cAB.evi);

            if (derived_nB < 1e-6) return empty(); // Negligible evidence

            var evidenceCertainty = Truth.of(derived_sB, derived_nB);
            var conclusionB = conclusionBOpt.get();

            // Create a temporary atom representing just this new piece of evidence for B
            var evidenceForB = conclusionB.withCertainty(evidenceCertainty);
            evidenceForB.importance(calculateInferredImportance(premiseA, implicationAB), mem.time.get());

            // Learn this new evidence, merging it with the existing certainty of B
            var revisedB = mem.learn(evidenceForB);

            // Log inference? System.out.printf("  MP: %s, %s => %s revised to %s%n", premiseA.id(), implicationAB.id(), revisedB.id(), revisedB.certainty());
            return Optional.of(revisedB);
        }

        // --- Control Structures ---

        /**
         * Performs forward chaining using importance heuristics.
         */
        public void forwardChain(int steps) {
            // System.out.println("--- Inference: Starting Forward Chaining ---");
            Set<String> executedInferenceSignatures = new HashSet<>(); // Avoid redundant immediate re-computation
            var totalInferences = 0;

            for (var step = 0; step < steps; step++) {
                var queue = gatherPotentialInferences();
                if (queue.isEmpty()) {
                    // System.out.println("FC Step " + (step + 1) + ": No potential inferences found.");
                    break;
                }

                var inferencesThisStep = executeTopInferences(queue, executedInferenceSignatures);
                totalInferences += inferencesThisStep;
                // System.out.println("FC Step " + (step + 1) + ": Made " + inferencesThisStep + " inferences.");

                if (inferencesThisStep == 0) {
                    // System.out.println("FC Step " + (step + 1) + ": Quiescence reached.");
                    break;
                }
            }
            if (totalInferences > 0)
                System.out.printf("--- Inference: Forward Chaining finished. Total inferences: %d ---%n", totalInferences);
        }

        /**
         * Gathers potential inferences, sorted by importance.
         */
        private PriorityQueue<PotentialInference> gatherPotentialInferences() {
            var queue = new PriorityQueue<>(
                    Comparator.<PotentialInference, Double>comparing(inf -> inf.priority).reversed()
            );
            final long currentTime = mem.time.get();

            Predicate<Atom> isConfidentEnough = a -> a.truth().conf() > TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE;

            // Gather Deductions (INHERITANCE, PREDICTIVE_IMPLICATION)
            Stream.of(Link.Type.INHERITANCE, Link.Type.PREDICTIVE_IMPLICATION)
                    .flatMap(mem::links)
                    .filter(this::isValidBinaryLink).filter(isConfidentEnough)
                    .forEach(linkAB -> {
                        var bId = linkAB.targets.get(1);
                        mem.linksWithTarget(bId) // Find links *starting* with B: B->C
                                .filter(linkBC -> linkBC.type == linkAB.type && isValidBinaryLink(linkBC) && linkBC.targets.getFirst().equals(bId))
                                .filter(isConfidentEnough)
                                .forEach(linkBC -> queue.add(new PotentialInference(InferenceRule.DEDUCTION, currentTime, linkAB, linkBC)));
                    });

            // Gather Inversions
            Stream.of(Link.Type.INHERITANCE, Link.Type.PREDICTIVE_IMPLICATION)
                    .flatMap(mem::links)
                    .filter(this::isValidBinaryLink).filter(isConfidentEnough)
                    .forEach(linkAB -> queue.add(new PotentialInference(InferenceRule.INVERSION, currentTime, linkAB)));

            // Gather Modus Ponens
            Stream.of(Link.Type.INHERITANCE, Link.Type.PREDICTIVE_IMPLICATION)
                    .flatMap(mem::links)
                    .filter(this::isValidBinaryLink).filter(isConfidentEnough)
                    .forEach(linkAB -> atom(linkAB.targets.getFirst()).ifPresent(premiseA -> {
                        if (isConfidentEnough.test(premiseA)) {
                            queue.add(new PotentialInference(InferenceRule.MODUS_PONENS, currentTime, premiseA, linkAB));
                        }
                    }));

            // TODO: Add potential Temporal Rule applications (e.g., Persistence)
            // TODO: Add potential HOL Rule applications (e.g., ForAll Instantiation)

            return queue;
        }

        /**
         * Executes the highest-priority inferences from the queue.
         */
        private int executeTopInferences(PriorityQueue<PotentialInference> queue, Set<String> executedSignatures) {
            var inferencesMade = 0;
            var processedCount = 0;
            while (!queue.isEmpty() && processedCount < FORWARD_CHAINING_BATCH_SIZE) {
                var potential = queue.poll();
                var signature = potential.signature;

                if (!executedSignatures.contains(signature)) {
                    Optional<?> resultOpt = empty();
                    // Ensure premises still exist and are valid before executing
                    var premisesValid = Arrays.stream(potential.premises)
                            .allMatch(p -> atom(p.id).isPresent());

                    if (premisesValid) {
                        // Add other rules here
                        resultOpt = switch (potential.ruleType) {
                            case DEDUCTION -> deduce((Link) potential.premises[0], (Link) potential.premises[1]);
                            case INVERSION -> invert((Link) potential.premises[0]);
                            case MODUS_PONENS -> modusPonens(potential.premises[0], (Link) potential.premises[1]);
                            default -> resultOpt;
                        };
                    }

                    executedSignatures.add(signature); // Mark as attempted (even if failed or premises vanished)
                    if (potential.ruleType == InferenceRule.DEDUCTION) { // Avoid A->B->C vs C<-B<-A redundancy
                        executedSignatures.add(potential.getSignatureSwapped());
                    }

                    if (resultOpt.isPresent()) {
                        inferencesMade++;
                        // System.out.printf("  FC Executed: %s -> %s (Priority: %.3f)%n", potential.ruleType, ((Atom)resultOpt.get()).id(), potential.priority);
                    }
                    processedCount++;
                }
            }
            return inferencesMade;
        }

        /**
         * Performs backward chaining query with unification.
         */
        public List<Answer> backwardChain(Atom queryPattern, int maxDepth) {
            return backwardChainRecursive(queryPattern, maxDepth, Bind.EMPTY_BIND, new HashSet<>());
        }

        private List<Answer> backwardChainRecursive(Atom queryPattern, int depth, Bind bind, Set<String> visited) {
            // Apply current bind to the query pattern
            var q = unify.substitute(queryPattern, bind);
            var visitedId = q.id + bind.hashCode(); // ID includes bind state

            if (depth <= 0 || !visited.add(visitedId))
                return Collections.emptyList(); // Depth limit or cycle detected

            List<Answer> results = new ArrayList<>();
            var nextDepth = depth - 1;

            // 1. Direct Match in memory (after substitution)
            atom(q.id).ifPresent(match -> {
                if (match.truth().conf() > TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE) {
                    // Check if the concrete match from memory unifies with the potentially var query
                    unify.unify(q, match, bind)
                            .ifPresent(finalBind -> results.add(new Answer(finalBind, match)));
                }
            });

            // 2. Rule-Based Derivation (Backward Application using Unification)

            // Try Modus Ponens Backward: Query B, find A and A->B
            results.addAll(tryModusPonensBackward(q, nextDepth, bind, visited));

            // Try Deduction Backward: Query A->C, find A->B and B->C
            if (q instanceof Link queryLink && isValidBinaryLink(queryLink))
                results.addAll(tryDeductionBackward(queryLink, nextDepth, bind, visited));

            // Try Inversion Backward: Query B->A, find A->B
            if (q instanceof Link queryLink && isValidBinaryLink(queryLink))
                results.addAll(tryInversionBackward(queryLink, nextDepth, bind, visited));


            // Try ForAll Instantiation Backward: Query P(a), find ForAll(?X, P(?X)) rule
            results.addAll(tryForAllBackward(q, nextDepth, bind, visited));

            // TODO: Try Temporal Rules Backward (e.g., HoldsAt via Persistence/Initiates/Terminates)


            visited.remove(visitedId); // Backtrack
            return results; // Combine results from all successful paths
        }

        // --- Backward Rule Helpers ---

        private List<Answer> tryModusPonensBackward(Atom queryB, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            // Collect implications A->B, where B unifies with queryB
            Stream.of(Link.Type.INHERITANCE, Link.Type.PREDICTIVE_IMPLICATION)
                    .flatMap(mem::links)
                    .filter(this::isValidBinaryLink)
                    .forEach(ruleAB -> {
                        var potentialB = atom(ruleAB.targets.get(1)).orElse(null);
                        if (potentialB == null) return;

                        var potentialA = atom(ruleAB.targets.get(0)).orElse(null);
                        if (potentialA == null) return;

                        // Try to unify the rule's consequent (B) with the query (B)
                        unify.unify(potentialB, queryB, bind).ifPresent(bindB -> {
                            // If unified, create subgoal to prove the premise (A) using bind from B unification
                            var subgoalA = unify.substitute(potentialA, bindB);
                            var resultsA = backwardChainRecursive(subgoalA, depth, bindB, visited);

                            for (var resA : resultsA) {
                                // And confirm the rule A->B itself holds with sufficient confidence
                                var rulePattern = unify.substitute(ruleAB, resA.bind);
                                var resultsAB = backwardChainRecursive(rulePattern, depth, resA.bind, visited);

                                for (var resAB : resultsAB) {
                                    // If both subgoals met, calculate the inferred certainty for B via MP
                                    modusPonens(resA.inferredAtom, (Link) resAB.inferredAtom).ifPresent(inferredB -> {
                                        // Final unification check with the original query and final bind
                                        unify.unify(queryB, inferredB, resAB.bind).ifPresent(finalBind ->
                                                results.add(new Answer(finalBind, inferredB))
                                        );
                                    });
                                }
                            }
                        });
                    });
            return results;
        }

        private List<Answer> tryDeductionBackward(Link queryAC, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            var aIdQuery = queryAC.targets.get(0);
            var cIdQuery = queryAC.targets.get(1);

            // Iterate through all links A->B of the same type
            mem.links(queryAC.type)
                    .filter(this::isValidBinaryLink)
                    .forEach(ruleAB -> {
                        var targets = ruleAB.targets;
                        var aIdRule = targets.get(0);
                        var bIdRule = targets.get(1);

                        // Unify query A with rule A
                        unify.unifyAtomId(aIdQuery, aIdRule, bind).ifPresent(bindA -> {
                            // Create subgoal B->C pattern, substituting bind from A unification
                            var subgoalBCPattern = new Link(queryAC.type, List.of(bIdRule, cIdQuery), Truth.UNKNOWN, null);
                            var subgoalBC = unify.substitute(subgoalBCPattern, bindA);

                            // Recurse: Prove A->B (the rule itself)
                            var resultsAB = backwardChainRecursive(ruleAB, depth, bindA, visited);
                            for (var resAB : resultsAB) {
                                // Recurse: Prove B->C
                                var resultsBC = backwardChainRecursive(subgoalBC, depth, resAB.bind, visited);
                                for (var resBC : resultsBC) {
                                    // If subgoals met, perform deduction forward to get inferred A->C
                                    deduce((Link) resAB.inferredAtom, (Link) resBC.inferredAtom).ifPresent(inferredAC -> {
                                        // Final unification with original query and final bind
                                        unify.unify(queryAC, inferredAC, resBC.bind).ifPresent(finalBind ->
                                                results.add(new Answer(finalBind, inferredAC))
                                        );
                                    });
                                }
                            }
                        });
                    });
            return results;
        }

        private List<Answer> tryInversionBackward(Link queryBA, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            var bIdQuery = queryBA.targets.get(0);
            var aIdQuery = queryBA.targets.get(1);

            // Collect rules A->B of the same type
            mem.links(queryBA.type)
                    .filter(this::isValidBinaryLink)
                    .forEach(ruleAB -> {
                        var aIdRule = ruleAB.targets.get(0);
                        var bIdRule = ruleAB.targets.get(1);

                        // Unify query B with rule B, and query A with rule A simultaneously
                        unify.unifyAtomId(bIdQuery, bIdRule, bind)
                                .flatMap(b1 -> unify.unifyAtomId(aIdQuery, aIdRule, b1))
                                .ifPresent(initBind -> {
                                    // If compatible, subgoal is to prove A->B
                                    var resultsAB = backwardChainRecursive(ruleAB, depth, initBind, visited);
                                    for (var resAB : resultsAB) {
                                        // If subgoal met, perform inversion forward
                                        invert((Link) resAB.inferredAtom).ifPresent(inferredBA -> {
                                            // Final unification check
                                            unify.unify(queryBA, inferredBA, resAB.bind).ifPresent(finalBind ->
                                                    results.add(new Answer(finalBind, inferredBA))
                                            );
                                        });
                                    }
                                });
                    });
            return results;
        }

        private List<Answer> tryForAllBackward(Atom queryInstance, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            mem.links(Link.Type.FOR_ALL).forEach(forAllLink -> {
                var targets = forAllLink.targets;
                var targetCount = targets.size();
                if (targetCount >= 2) { // Should be FOR_ALL(Var1, Var2..., Body)
                    //var varIds = targets.subList(0, targetCount - 1);
                    var bodyId = targets.getLast();

                    // Try to unify the query instance with the body pattern
                    atom(bodyId).flatMap(bodyPattern -> unify.unify(bodyPattern, queryInstance, bind)).ifPresent(matchBind -> {
                        // If unification succeeds, the instance might hold if the ForAll rule itself holds.
                        // Check the ForAll rule's confidence recursively.
                        var forAllResults = backwardChainRecursive(forAllLink, depth, matchBind, visited);
                        for (var resForAll : forAllResults) {
                            // The instance is supported with the confidence of the ForAll rule.
                            // Substitute final bind into the original query instance.
                            var finalInstance = unify.substitute(queryInstance, resForAll.bind);
                            var supportedInstance = finalInstance.withCertainty(resForAll.inferredAtom.truth());
                            results.add(new Answer(resForAll.bind, supportedInstance));
                        }
                    });
                }
            });
            return results;
        }


        // --- Planning ---

        /**
         * Attempts to plan a sequence of actions to achieve a goal state.
         */
        public Optional<List<Atom>> planToActionSequence(Atom goalPattern, int maxPlanDepth, int maxSearchDepth) {
            System.out.println("\n--- Inference: Planning ---");
            System.out.println("Goal Pattern: " + goalPattern.id);
            atom(goalPattern.id).ifPresent(g -> g.updateImportance(IMPORTANCE_BOOST_ON_GOAL_FOCUS, mem.time.get()));

            // Use backward search from the goal
            return planRecursive(goalPattern, new LinkedList<>(), maxPlanDepth, maxSearchDepth, new HashSet<>());
        }

        private Optional<List<Atom>> planRecursive(Atom currentGoalPattern, LinkedList<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoalPatterns) {
            var goalPatternId = currentGoalPattern.id + currentPlan.hashCode(); // State includes current plan context
            var indent = "  ".repeat(PLANNING_DEFAULT_MAX_PLAN_DEPTH - planDepthRemaining);

            if (planDepthRemaining <= 0 || !visitedGoalPatterns.add(goalPatternId)) {
                // System.out.println(indent + "-> Stop (Depth/Cycle on " + currentGoalPattern.id() + ")");
                return empty();
            }

            // 1. Check if goal already holds (using backward chaining query)
            var goalResults = backwardChain(currentGoalPattern, searchDepth);
            // Find the best result based on confidence and strength
            var bestResult = goalResults.stream()
                    .max(Comparator.comparingDouble(r -> r.inferredAtom.truth().strength * r.inferredAtom.truth().conf()));

            if (bestResult.isPresent() && bestResult.get().inferredAtom.truth().strength > 0.7 && bestResult.get().inferredAtom.truth().conf() > 0.5) {
                // System.out.println(indent + "-> Goal Holds: " + currentGoalPattern.id() + " (via " + bestResult.get().inferredAtom.id() + ")");
                return Optional.of(new ArrayList<>(currentPlan)); // Return copy of current plan (success)
            }

            // 2. Find actions that could achieve the goal
            var potentialSteps = findActionsAchieving(currentGoalPattern, searchDepth);
            // Sort potential steps (e.g., by confidence of effect, heuristic) - higher confidence first
            potentialSteps.sort(Comparator.comparingDouble(PotentialPlanStep::confidence).reversed());

            // 3. Try each potential action
            for (var step : potentialSteps) {
                // System.out.println(indent + "-> Considering Action: " + step.action().id() + " (Conf: " + String.format("%.3f", step.confidence()) + ")");

                // Check if action is already in the current plan to avoid trivial loops
                if (currentPlan.stream().anyMatch(a -> a.id.equals(step.action.id))) {
                    continue;
                }

                // Create new visited set for this branch to allow revisiting goals in different plan contexts
                var branchVisited = new HashSet<>(visitedGoalPatterns);

                // Recursively plan to satisfy preconditions
                var planWithPreconditionsOpt = satisfyPreconditions(
                        step.preconditions, currentPlan, planDepthRemaining - 1, searchDepth, branchVisited);

                // If preconditions satisfied, add this action to the plan
                if (planWithPreconditionsOpt.isPresent()) {
                    var planFound = planWithPreconditionsOpt.get();
                    // Add the current action *after* its preconditions are met
                    planFound.addLast(step.action);
                    // System.out.println(indent + "--> Plan Step Found: " + step.action().id());
                    return Optional.of(planFound); // Found a complete plan for this branch
                }
            }

            visitedGoalPatterns.remove(goalPatternId); // Backtrack
            // System.out.println(indent + "-> No plan found from state for " + currentGoalPattern.id());
            return empty();
        }

        /**
         * Finds actions that might achieve the goalPattern based on memory rules.
         * TODO use 'searchDepth'
         */
        private List<PotentialPlanStep> findActionsAchieving(Atom goalPattern, int searchDepth) {
            List<PotentialPlanStep> steps = new ArrayList<>();

            // Find Predictive Implications: Sequence(Preconditions..., Action) => Goal
            mem.links(Link.Type.PREDICTIVE_IMPLICATION)
                    // .filter(rule -> rule.targets().size() == 2) // Premise -> Consequence
                    .forEach(rule -> {
                        if (rule.targets.size() != 2) return; // Skip malformed rules
                        var premiseId = rule.targets.get(0);
                        var consequenceId = rule.targets.get(1);

                        var consequenceAtomOpt = atom(consequenceId);
                        if (consequenceAtomOpt.isEmpty()) return;
                        var consequenceAtom = consequenceAtomOpt.get();

                        // Check if rule's consequence unifies with the current goal pattern
                        unify.unify(consequenceAtom, goalPattern, Bind.EMPTY_BIND).ifPresent(bind -> {
                            // If consequence matches, examine the premise
                            atom(premiseId).ifPresent(premiseAtom -> {
                                // Case 1: Premise is Sequence(State..., Action)
                                if (premiseAtom instanceof Link premiseLink && premiseLink.type == Link.Type.SEQUENCE) {
                                    var actionOpt = premiseLink.targets.stream()
                                            .map(mem::atom).filter(Optional::isPresent).map(Optional::get)
                                            .filter(this::isActionAtom) // Find the action within the sequence
                                            .findFirst();

                                    actionOpt.ifPresent(actionAtom -> {
                                        // Preconditions are the other elements in the sequence
                                        var preconditions = premiseLink.targets.stream()
                                                .filter(id -> !id.equals(actionAtom.id))
                                                .map(mem::atom).filter(Optional::isPresent).map(Optional::get)
                                                .map(precond -> unify.substitute(precond, bind)) // Apply bind from goal unification
                                                .toList();
                                        var confidence = rule.truth().conf() * premiseLink.truth().conf();
                                        steps.add(new PotentialPlanStep(actionAtom, preconditions, confidence));
                                    });
                                }
                                // Case 2: Premise is directly an Action (implies no preconditions in this rule)
                                else if (isActionAtom(premiseAtom)) {
                                    steps.add(new PotentialPlanStep(premiseAtom, Collections.emptyList(), rule.truth().conf()));
                                }
                                // Case 3: Premise requires further backward chaining to find an action (more complex, not fully implemented here)
                                // else { /* Could try backward chaining on the premise to find a path involving an action */ }
                            });
                        });
                    });

            // TODO: Add checks for INITIATES links (Event Calculus style)
            // If Goal is HoldsAt(Fluent), look for Action -> Initiates(Fluent)

            return steps;
        }


        /**
         * Helper to recursively satisfy a list of preconditions for planning.
         */
        private Optional<LinkedList<Atom>> satisfyPreconditions(List<Atom> preconditions, LinkedList<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoalPatterns) {
            if (preconditions.isEmpty()) {
                return Optional.of(new LinkedList<>(currentPlan)); // Base case: success, return copy
            }

            var nextPrecondition = preconditions.getFirst();
            var remainingPreconditions = preconditions.subList(1, preconditions.size());

            // Recursively plan to achieve the next precondition
            // Pass a *copy* of the current plan, as this branch might fail
            var planForPreconditionOpt = planRecursive(nextPrecondition, new LinkedList<>(currentPlan), planDepthRemaining, searchDepth, visitedGoalPatterns);

            if (planForPreconditionOpt.isPresent()) {
                // Precondition achieved. The returned list is the *complete* plan up to this point.
                // Now, try to satisfy the *remaining* preconditions starting from the state achieved by this sub-plan.
                var planAfterPrecondition = new LinkedList<>(planForPreconditionOpt.get());

                // Check depth again after sub-plan potentially used steps
                var depthRemainingAfterSubPlan = planDepthRemaining - (planAfterPrecondition.size() - currentPlan.size());
                if (depthRemainingAfterSubPlan < 0) return empty(); // Sub-plan exceeded depth limit

                return satisfyPreconditions(remainingPreconditions, planAfterPrecondition, depthRemainingAfterSubPlan, searchDepth, visitedGoalPatterns);
            } else {
                // Failed to satisfy this precondition
                return empty();
            }
        }

        // --- Helpers ---

        private boolean isValidBinaryLink(Atom atom) {
            if (!(atom instanceof Link link) || link.targets.size() != 2) return false;
            return link.type == Link.Type.INHERITANCE || link.type == Link.Type.PREDICTIVE_IMPLICATION;
        }

        private boolean isActionAtom(Atom atom) {
            // Define convention: Action atoms are Nodes with names starting "Action:"
            return atom instanceof Node node && node.name.startsWith("Action:");
            // Alternative: check for specific link type like EXECUTION if used
        }

        private double calculateInferredImportance(Atom... premises) {
            final long currentTime = mem.time.get();
            var premiseImportanceProduct = Arrays.stream(premises)
                    .mapToDouble(p -> p.getCurrentImportance(currentTime) * p.truth().conf())
                    .reduce(1, (a, b) -> a * b); // Combine importance, weighted by confidence
            return IMPORTANCE_BOOST_ON_ACCESS + premiseImportanceProduct * IMPORTANCE_BOOST_INFERRED_FACTOR;
        }

        // --- Helper Records/Enums for Inference Control ---
        private enum InferenceRule {DEDUCTION, INVERSION, MODUS_PONENS, FOR_ALL_INSTANTIATION, TEMPORAL_PERSISTENCE}

        /**
         * Represents a potential inference step for prioritization in forward chaining.
         */
        private static class PotentialInference {
            final InferenceRule ruleType;
            final Atom[] premises;
            final double priority; // Combined importance * confidence of premises
            final String signature; // Unique ID for visited set

            PotentialInference(InferenceRule type, long currentTime, Atom... premises) {
                this.ruleType = type;
                this.premises = premises;
                // Priority based on product of premise importances weighted by confidence
                this.priority = Arrays.stream(premises)
                        .mapToDouble(p ->
                                p.getCurrentImportance(currentTime) * p.truth().conf())
                        .reduce(1, (a, b) -> a * b);
                this.signature = ruleType + ":" + Arrays.stream(premises).map(atom -> atom.id).sorted().collect(Collectors.joining("|"));
            }

            String getSignatureSwapped() { // For symmetric rules like Deduction A->B->C vs C<-B<-A
                return premises.length == 2 ?
                    ruleType + ":" + Stream.of(premises[1].id, premises[0].id).sorted().collect(Collectors.joining("|")) :
                    signature;
            }
        }

        /**
         * Represents a potential step in a plan.
         */
        private record PotentialPlanStep(Atom action, List<Atom> preconditions, double confidence) {
        }
    }

    /**
     * Abstract base class for all elements in the Knowledge Base (Nodes and Links).
     * Manages identity, certainty, importance, and access time. Uses VarHandles for
     * efficient, thread-safe updates to volatile importance fields.
     */
    public abstract static sealed class Atom permits Node, Link {
        private static final VarHandle STI, LTI, LAST_ACCESS_TIME;

        static {
            try {
                var l = MethodHandles.lookup();
                STI = l.findVarHandle(Atom.class, "sti", double.class);
                LTI = l.findVarHandle(Atom.class, "lti", double.class);
                LAST_ACCESS_TIME = l.findVarHandle(Atom.class, "lastAccessTime", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public final String id;

        private Truth truth; // Mutable certainty

        // Volatile fields managed by VarHandles for atomic updates
        @SuppressWarnings("unused")
        private volatile double sti; // STI
        @SuppressWarnings("unused")
        private volatile double lti;  // LTI
        @SuppressWarnings("unused")
        private volatile long lastAccessTime;


        protected Atom(String id, Truth truth) {
            this.id = Objects.requireNonNull(id);
            this.truth = Objects.requireNonNull(truth);
            // Importance initialized later via initializeImportance or implicitly
        }

        public final Truth truth() {
            return truth;
        }

        // Internal setter used by memory revision
        final void truth(Truth truth) {
            this.truth = truth;
        }

        private void sti(double x) {
            STI.setVolatile(this, x);
        }

        public void lti(double x) {
            LTI.setVolatile(this, x);
        }

        public double lti() {
            return (double) LTI.getVolatile(this);
        }

        public double sti() {
            return (double) STI.getVolatile(this);
        }

        /**
         * Called once upon creation or first learning
         */
        final void importance(double sti, long currentTime) {
            var clampedSTI = unitize(sti);
            sti(clampedSTI);
            lti(clampedSTI * IMPORTANCE_INITIAL_LTI_FACTOR); // LTI starts lower
            updateAccessTime(currentTime);
        }

        /**
         * Atomically updates importance based on a boost event and decays existing STI/LTI slightly.
         */
        final void updateImportance(double boost, long currentTime) {
            boost = Math.max(0, boost); // Ensure non-negative boost
            // Atomically update STI: newSTI = min(1.0, currentSTI * decay + boost)
            double currentSTI;
            double newSTI;
            do {
                currentSTI = sti();
                newSTI = Math.min(1.0, currentSTI * (1.0 - IMPORTANCE_STI_DECAY_RATE * 0.1) + boost); // Slight decay on access
            } while (!STI.compareAndSet(this, currentSTI, newSTI));

            // Atomically update LTI: newLTI = min(1.0, currentLTI * decay + newSTI * learning_rate)
            double currentLTI;
            double newLTI;
            do {
                currentLTI = lti();
                newLTI = Math.min(1.0, currentLTI * (1.0 - IMPORTANCE_LTI_DECAY_RATE * 0.1) + newSTI * IMPORTANCE_STI_TO_LTI_RATE);
            } while (!LTI.compareAndSet(this, currentLTI, newLTI));

            updateAccessTime(currentTime); // Also update access time on importance boost
        }

        /**
         * Atomically decays importance (called periodically by maintenance task).
         * TODO consider param currentTime?
         */
        final void decayImportance(long currentTime) {
            var currentSTI = sti();
            var decayedSTI = currentSTI * (1 - IMPORTANCE_STI_DECAY_RATE);
            sti(decayedSTI); // Direct set is fine here if only maintenance thread calls decay

            var currentLTI = lti();
            // LTI also learns from the decayed STI value
            var decayedLTI = currentLTI * (1 - IMPORTANCE_LTI_DECAY_RATE) + decayedSTI * IMPORTANCE_STI_TO_LTI_RATE * 0.1; // Slow learn during decay
            lti(Math.min(1, decayedLTI));
        }

        /**
         * Updates the last access time atomically.
         */
        final void updateAccessTime(long time) {
            LAST_ACCESS_TIME.setVolatile(this, time);
        }

        /**
         * Calculates a combined importance score, factoring STI, LTI, recency, and confidence.
         */
        public final double getCurrentImportance(long currentTime) {
            var lastAccess = lastAccessTime();

            double timeSinceAccess = Math.max(0, currentTime - lastAccess);
            // Recency factor: decays exponentially, influences mostly STI contribution
            var recencyFactor = Math.exp(-timeSinceAccess / (FORGETTING_CHECK_INTERVAL_MS * 3.0)); // Decay over ~3 cycles

            // Combine STI (weighted by recency) and LTI
            var combinedImportance = sti() * recencyFactor * 0.5 + lti() * 0.5;
            // Modulate by confidence - less confident atoms are less important overall
            return combinedImportance * truth.conf();
        }

        public long lastAccessTime() {
            return (long) LAST_ACCESS_TIME.getVolatile(this);
        }

        /**
         * Abstract method for creating a copy with a different Certainty.
         */
        public abstract Atom withCertainty(Truth newCertainty);

        @Override
        public final boolean equals(Object o) {
            if (this == o) return true;
            return o != null && getClass() == o.getClass() && id.equals(((Atom) o).id);
        }

        @Override
        public final int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return String.format("%s %s", id, truth);
            // Verbose: String.format("%s %s <S=%.2f L=%.2f>", id, certainty, (double)sti(), (double)lti());
        }
    }

    /**
     * Node Atom: Represents concepts, entities, predicates, actions, fluents, etc.
     */
    public static sealed class Node extends Atom {
        public final String name; // The human-readable name

        public Node(String name, Truth certainty) {
            super(id(name), certainty);
            this.name = name;
        }

        /**
         * Use <> for clarity
         */
        public static String id(String name) {
            return "N<" + name + ">";
        }

        @Override
        public Atom withCertainty(Truth newCertainty) {
            var newNode = new Node(this.name, newCertainty);
            // Copy importance state - assumes temporary copy, memory manages learned atom's state
            newNode.importance(sti(), lastAccessTime());
            newNode.lti(lti());
            return newNode;
        }

        @Override
        public String toString() {
            return String.format("%s %s", name, truth());
        } // More readable default
    }

    /**
     * Variable Node: Special Node type used in patterns and rules (HOL).
     * certainty=unknown, ID includes prefix
     * TODO consider '$var' syntax
     */
    public static final class Var extends Node {
        public static final String EMPTY = varID("");

        public Var(String name) {
            super(VARIABLE_PREFIX + name, Truth.UNKNOWN); // Importance handled by memory (usually protected)
        }

        public static String varID(String name) {
            return "V<" + VARIABLE_PREFIX + name + ">";
        }

        @Override
        public Atom withCertainty(Truth newCertainty) {
            // Creating a copy of a variable doesn't make sense with different certainty
            return new Var(name.substring(VARIABLE_PREFIX.length()));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Link Atom: Represents relationships, rules, sequences, logical connectives between Atoms.
     */
    public static final class Link extends Atom {
        public final Type type;
        public final List<String> targets; // Immutable list of target Atom IDs
        private Time time; // Optional, mutable time specification

        public Link(Type type, List<String> targets, Truth truth, Time time) {
            super(id(type, targets), truth);
            this.type = Objects.requireNonNull(type);
            this.targets = List.copyOf(targets); // Ensure immutable list TODO avoid unnecessary copies
            this.time = time;
        }

        public static String id(Type type, Collection<String> targets) {
            // Sort targets for order-independent types to ensure canonical ID
            var idTargets = type.commutative && targets.size() > 1 ? targets.stream().sorted().toList() : targets;
            // Simple, readable format: TYPE(Target1,Target2,...)
            return type + "(" + String.join(",", idTargets) + ")";
        }

        @Nullable
        public Time time() {
            return time;
        }

        // Internal setter used by memory revision
        void setTime(Time time) {
            this.time = time;
        }

        @Override
        public Atom withCertainty(Truth newCertainty) {
            //TODO if newCertainty is equal, do we need a new instance?
            var l = new Link(type, targets, newCertainty, time);
            l.importance(sti(), lastAccessTime()); // Copy importance state
            l.lti(lti());
            return l;
        }

        @Override
        public String toString() {
            var timeStr = (time != null) ? " " + time : "";
            return String.format("%s %s%s", super.toString(), timeStr, ""); // Let super handle ID + certainty
            // Verbose: String.format("%s %s%s %s", type, targets, timeStr, certainty());
        }

        // --- Link Types Enum ---
        public enum Type {
            // Core Semantic Links
            INHERITANCE(false),     // IS-A (Subtype -> Supertype)
            SIMILARITY(true),       // Similar concepts
            INSTANCE(false),        // Instance -> Type
            MEMBER(false),          // Member -> Collection
            EVALUATION(false),      // Predicate applied to arguments (Predicate, Arg1, Arg2...)
            EXECUTION(false),       // Represents an action execution event (Action, Agent, Object?, Time?)

            // Logical Links
            AND(true), OR(true), NOT(false), // Boolean logic (Targets are propositions)
            PREDICTIVE_IMPLICATION(false), // If A then (likely/eventually) B (A, B)

            // Temporal Links (Often wrap other links/nodes)
            SEQUENCE(false),        // A occurs then B occurs (A, B)
            SIMULTANEOUS(true),     // A and B occur together (A, B)
            HOLDS_AT(false),        // Fluent/Predicate holds at a specific time/interval (FluentAtom, TimeSpecAtom?) - Often wraps Evaluation
            // Event Calculus Style (optional, requires specific temporal reasoning rules)
            INITIATES(false),       // Action initiates Fluent (Action, Fluent)
            TERMINATES(false),      // Action terminates Fluent (Action, Fluent)

            // Higher-Order Logic Links
            FOR_ALL(false),         // Universal quantifier (Var1, Var2..., BodyAtom)
            EXISTS(false);          // Existential quantifier (Var1, Var2..., BodyAtom)

            public final boolean commutative;

            Type(boolean commutative) {
                this.commutative = commutative;
            }
        }
    }

    /**
     * Immutable record representing probabilistic truth value (Strength and Confidence).
     * Strength: Probability or degree of truth (0.0 to 1.0).
     * Count: Amount of evidence supporting this value.
     */
    public record Truth(double strength, double evi) {
        public static final Truth DEFAULT = Truth.of(0.5, 0.0); // Default prior: ignorance
        public static final Truth TRUE = Truth.of(1, 10.0); // Strong positive evidence
        public static final Truth FALSE = Truth.of(0.0, 10.0); // Strong negative evidence
        public static final Truth UNKNOWN = Truth.of(0.5, 0.1); // Explicitly unknown/low evidence

        // Canonical constructor ensures values are clamped

        /**
         * Factory method for convenience.
         */
        public static Truth of(double strength, double count) {
            return new Truth(unitize(strength), Math.max(0, count));
        }

        /**
         * Confidence: Derived measure of certainty based on count (0.0 to 1.0).
         * Calculates confidence: count / (count + sensitivity). Higher count -> higher confidence.
         */
        public double conf() {
            return evi / (evi + TRUTH_DEFAULT_SENSITIVITY);
        }

        /**
         * Merges this Certainty with another, producing a combined value.
         * This implements Bayesian revision: weighted average of strengths based on counts.
         */
        public Truth merge(Truth other) {
            var oc = other.evi;
            if (oc == 0) return this;
            var tc = this.evi;
            if (tc == 0) return other;

            var totalCount = tc + oc;
            // Weighted average strength: (s1*c1 + s2*c2) / (c1 + c2)
            var mergedStrength = (this.strength * tc + other.strength * oc) / totalCount;

            return Truth.of(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("<s:%.3f, c:%.2f, conf:%.3f>", strength, evi, conf());
        }
    }

    /**
     * Immutable record representing temporal scope (point, interval, or relative duration).
     * Times are typically based on the engine's logical time clock.
     * Durations are relative.
     */
    public record Time(long start, long end, boolean isRelative) {
        // Common instances
        public static final Time ETERNAL = new Time(Long.MIN_VALUE, Long.MAX_VALUE, false); // Always holds
        public static final Time IMMEDIATE = new Time(0, 0, true); // Relative, zero duration/delay

        // --- Factory Methods ---

        /**
         * Creates a time point (absolute or relative).
         */
        public static Time at(long timePoint, boolean isRelative) {
            return new Time(timePoint, timePoint, isRelative);
        }

        public static Time at(long timePoint) {
            return new Time(timePoint, timePoint, false);
        } // Absolute point default

        /**
         * Creates a time interval (absolute or relative).
         */
        public static Time between(long startTime, long endTime, boolean isRelative) {
            if (!isRelative && endTime < startTime)
                throw new IllegalArgumentException("Absolute end time must be >= start time");
            return new Time(startTime, endTime, isRelative);
        }

        public static Time between(long startTime, long endTime) {
            return between(startTime, endTime, false);
        } // Absolute interval default

        /**
         * Creates a relative duration.
         */
        public static Time range(long duration) {
            return new Time(0, Math.max(0, duration), true);
        }

        /**
         * Composes two TimeSpecs, typically for sequence delays. Adds durations.
         */
        public static Time compose(Time t1, Time t2) {
            if (t1 == null) return t2;
            if (t2 == null) return t1;
            if (!t1.isRelative || !t2.isRelative) {
                // Cannot easily compose absolute times without context, return wider interval?
                return Time.between(Math.min(t1.start, t2.start), Math.max(t1.end, t2.end), false); // Simplistic merge
            }
            // Add relative durations
            return Time.range(t1.range() + t2.range());
        }

        public static Time merge(Link a, Link b) {
            return Time.merge(a.time(), b.time());
        }

        /**
         * Merges two time specs, typically representing combined evidence. Returns the encompassing interval.
         */
        public static Time merge(Time a, Time b) {
            if (a == null) return b;
            if (b == null || b == a) return a;
            // Simple merge: take the union of the time intervals, assume absolute if either is
            var mergedRelative = a.isRelative && b.isRelative;
            var mergedStart = Math.min(a.start, b.start);
            var mergedEnd = Math.max(a.end, b.end);
            return Time.between(mergedStart, mergedEnd, mergedRelative);
        }

        // --- Operations ---

        // --- Accessors ---
        public boolean isPoint() {
            return start == end;
        }

        public long range() {
            return end - start;
        } // Meaningful mostly for relative intervals

        /**
         * Inverts a relative duration (e.g., for backward temporal reasoning).
         */
        public Time inverse() {
            return isRelative ? Time.range(-this.range()) : this; // Cannot invert absolute time
        }

        @Override
        public String toString() {
            var prefix = isRelative ? "Rel" : "Abs";
            return isPoint() ? String.format("T:%s@%d", prefix, start) : String.format("T:%s[%d,%d]", prefix, start, end);
        }
    }

    /**
     * Record holding results from backward chaining queries, including variable bind
     */
    public record Answer(Bind bind, Atom inferredAtom) {
    }


    // === Unification Logic                                              ===


    /**
     * Represents variable bind during unification. Immutable.
     */
    public record Bind(Map<String, String> map) {
        public static final Bind EMPTY_BIND = new Bind(Collections.emptyMap());

        public Bind {
            map = Map.copyOf(map); // Ensure immutability //TODO avoid duplication
        }

        public final String getOrElse(String varId, String orElse) {
            var v = get(varId);
            return v == null ? orElse : v;
        }

        @Nullable
        public String get(String varId) {
            return map.get(varId);
        }

        /**
         * Creates new bind extended with one more mapping. Returns empty Optional if inconsistent.
         */
        public Optional<Bind> put(String varId, String valueId) {
            var mk = get(varId);
            if (mk != null) { // Variable already bound
                return mk.equals(valueId) ? Optional.of(this) : empty(); // Consistent?
            }
            if (map.containsValue(varId)) { // Trying to bind X to Y where Y is already bound to Z
                // Check for cycles or inconsistencies: If valueId is a variable already bound to something else.
                if (valueId.startsWith(Var.EMPTY)) {
                    var mv = get(valueId);
                    if (mv != null && !mv.equals(varId)) return empty();
                }
                // Avoid occurs check for simplicity here, assume valid structure
            }

            var newMap = new HashMap<>(map);
            newMap.put(varId, valueId);
            return Optional.of(new Bind(newMap));
        }

        /**
         * Merges two sets of bind Returns empty Optional if inconsistent.
         */
        public Optional<Bind> merge(Bind other) {
            Map<String, String> mergedMap = new HashMap<>(this.map);
            for (var entry : other.map.entrySet()) {
                var varId = entry.getKey();
                var valueId = entry.getValue();
                if (mergedMap.containsKey(varId)) {
                    if (!mergedMap.get(varId).equals(valueId)) {
                        return empty(); // Inconsistent bind
                    }
                } else {
                    mergedMap.put(varId, valueId);
                }
            }
            // Check for transitive inconsistencies (e.g., X=Y in this, Y=Z in other, but X=A in merged) - simple check
            for (var entry : mergedMap.entrySet()) {
                var currentVal = entry.getValue();
                if (mergedMap.containsKey(currentVal) && !mergedMap.get(currentVal).equals(entry.getKey())) {
                    // Check requires deeper graph traversal for full consistency, simple check here
                    // Example: X=Y, Y=X is okay. X=Y, Y=Z, Z=X okay. X=Y, Y=Z, X=A is not.
                    // For now, allow basic merge.
                }
            }

            return Optional.of(new Bind(mergedMap));
        }

        @Override
        public String toString() {
            return map.toString();
        }

        /** follow bind chain for an ID (might be variable or concrete) */
        private String follow(String id) {
            var at = id;
            var next = get(at);
            if (next == null)
                return at;
            var visited = new HashSet<>();
            while (visited.add(at)) {
                if (next.startsWith(Var.EMPTY)) {
                    at = next;
                    next = get(at);
                    if (next==null)
                        break;
                } else {
                    return next; // Bound to concrete ID
                }
            }
            return at; // Unbound or cycle detected, return last ID found
        }

    }

    /**
     * Performs unification between Atom patterns.
     */
    public static class Unify {
        private final Memory mem; // Needed to resolve IDs to Atoms if necessary

        public Unify(Memory mem) {
            this.mem = mem;
        }

        /**
         * Unifies two Atoms, returning updated bind if successful.
         */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind init) {
            // Dereference variables in both pattern and instance based on initial bind
            var p = substitute(pattern, init);
            var i = substitute(instance, init);

            if (p instanceof Var varP)
                return init.put(varP.id, i.id);

            if (i instanceof Var varI)
                return init.put(varI.id, p.id);


            // Must be same Atom type (Node or Link)
            if (p.getClass() != i.getClass())
                return empty();

            if (p instanceof Node nodeP && i instanceof Node nodeI) {
                // Nodes unify only if they are identical
                return nodeP.id.equals(nodeI.id) ? Optional.of(init) : empty();
            }

            if (p instanceof Link linkP && i instanceof Link linkI) {
                // Links unify if type matches and all targets unify recursively
                if (linkP.type != linkI.type || linkP.targets.size() != linkI.targets.size()) {
                    return empty();
                }

                var currentBind = init;
                for (var j = 0; j < linkP.targets.size(); j++) {
                    var targetPId = linkP.targets.get(j);
                    var targetIId = linkI.targets.get(j);

                    // Recursively unify target IDs
                    var result = unifyAtomId(targetPId, targetIId, currentBind);
                    if (result.isEmpty())
                        return empty(); // Unification failed for a target

                    currentBind = result.get();
                }
                return Optional.of(currentBind); // All targets unified successfully
            }

            return empty(); // Should not happen if Atoms are Nodes or Links
        }

        /**
         * Unifies two Atom IDs, resolving them to Atoms if needed.
         */
        public Optional<Bind> unifyAtomId(String patternId, String instanceId, Bind bind) {
            var pId = bind.getOrElse(patternId, patternId);
            var iId = bind.getOrElse(instanceId, instanceId);

            if (pId.equals(iId)) return Optional.of(bind);

            if (pId.startsWith(Var.EMPTY)) {
                return bind.put(pId, iId);
            }
            if (iId.startsWith(Var.EMPTY)) {
                return bind.put(iId, pId);
            }

            // If neither are variables and they are not equal, they don't unify directly by ID.
            // However, if they represent complex structures (Links), we need to unify their structure.
            var patternAtomOpt = mem.atom(pId);
            var instanceAtomOpt = mem.atom(iId);

            if (patternAtomOpt.isPresent() && instanceAtomOpt.isPresent()) {
                // Delegate to full Atom unification
                return unify(patternAtomOpt.get(), instanceAtomOpt.get(), bind);
            } else {
                // If atoms cannot be retrieved, assume non-match if IDs differ
                return empty();
            }
        }


        /**
         * Applies bind to substitute variables in an Atom pattern.
         */
        public Atom substitute(Atom pattern, Bind bind) {
            if (bind.map.isEmpty()) return pattern; // No substitutions needed

            if (pattern instanceof Var var) {
                // Follow the bind chain until a non-variable or unbound variable is found
                var currentVarId = var.id;
                var boundValueId = bind.get(currentVarId);
                var visited = new HashSet<>();
                while (boundValueId != null && visited.add(currentVarId)) {
                    if (boundValueId.startsWith(Var.EMPTY)) {
                        currentVarId = boundValueId;
                        boundValueId = bind.get(currentVarId);
                    } else {
                        // Bound to a concrete value ID, retrieve the corresponding Atom
                        return mem.atom(boundValueId).orElse(pattern); // Return original if bound value doesn't exist
                    }
                }
                // If loop detected or unbound, return the last variable in the chain or original
                return mem.atom(currentVarId).orElse(pattern);
            }

            if (pattern instanceof Link link) {
                var changed = false;
                var originalTargets = link.targets;
                List<String> newTargets = new ArrayList<>(originalTargets.size());
                for (var targetId : originalTargets) {
                    // Recursively substitute in targets
                    var targetPattern = mem.atom(targetId).orElse(null); // Get Atom for target ID
                    String newTargetId;
                    if (targetPattern != null) {
                        var substitutedTarget = substitute(targetPattern, bind);
                        newTargetId = substitutedTarget.id;
                        if (!targetId.equals(newTargetId)) changed = true;
                    } else {
                        // If target doesn't exist, maybe it's a variable? Check bind directly.
                        newTargetId = bind.follow(targetId);
                        if (!targetId.equals(newTargetId)) changed = true;
                    }
                    newTargets.add(newTargetId);
                }

                if (changed) {
                    // Create a new Link with substituted targets, keeping original certainty/time/importance
                    var newLink = new Link(link.type, newTargets, link.truth(), link.time());
                    newLink.importance(link.sti(), link.lastAccessTime());
                    newLink.lti(link.lti());
                    return newLink;
                } else {
                    return link; // No change
                }
            }

            // Node that is not a variable - no substitution possible
            return pattern;
        }

    }

    /**
     * Result of perception: a collection of Atoms representing observed facts/fluents.
     */
    public record Percept(Collection<Atom> perceivedAtoms) {
    }

    /**
     * Result of executing an action: the new perceived state and any reward received.
     */
    public record Act(Collection<Atom> newStateAtoms, double reward) {
    }

    // --- BasicGridWorld Example Implementation (Self-contained) ---
    static class BasicGridWorld implements Game {
        private final Cog cle;
        private final int worldSize;
        private final int goalX, goalY;
        private final int maxSteps = 25;
        // Pre-created Atom schemas for efficiency
        private final Node agentNode;
        private final Node moveN, moveS, moveE, moveW;
        private final Node atLocationPred;
        private final ConcurrentMap<String, Node> positionNodes = new ConcurrentHashMap<>(); // Cache pos nodes
        private int agentX = 0, agentY = 0;
        private int steps = 0;

        BasicGridWorld(Cog cle, int size, int goalX, int goalY) {
            this.cle = cle;
            this.worldSize = size;
            this.goalX = goalX;
            this.goalY = goalY;

            // Create core nodes used by the environment/agent representation
            this.agentNode = cle.getOrCreateNode("Self");
            this.atLocationPred = cle.getOrCreateNode("Predicate:AtLocation");
            this.moveN = cle.getOrCreateNode("Action:MoveNorth");
            this.moveS = cle.getOrCreateNode("Action:MoveSouth");
            this.moveE = cle.getOrCreateNode("Action:MoveEast");
            this.moveW = cle.getOrCreateNode("Action:MoveWest");
        }

        private Node getPositionNode(int x, int y) {
            var name = String.format("Pos_%d_%d", x, y);
            return positionNodes.computeIfAbsent(name, cle::getOrCreateNode);
        }

        @Override
        public Percept perceive() {
            var currentPosNode = getPositionNode(agentX, agentY);
            // State representation: HoldsAt(Evaluation(AtLocation, Self, Pos_X_Y), Time)
            // 1. Create the inner Evaluation link: Evaluation(AtLocation, Self, Pos_X_Y)
            var atLocationEval = cle.learn(Link.Type.EVALUATION, Truth.TRUE, 0.8, atLocationPred, agentNode, currentPosNode);
            // 2. Create the outer HoldsAt link: HoldsAt(InnerLink, Time)
            Atom agentAtLink = cle.learn(Link.Type.HOLDS_AT, Truth.TRUE, 0.9,
                    Time.at(cle.iteration()), atLocationEval);

            // We return the HoldsAt link as the primary perception atom
            return new Percept(List.of(agentAtLink));
        }

        @Override
        public List<Atom> actions() {
            List<Atom> actions = new ArrayList<>(4);
            // Return the Node schemas representing actions
            if (agentY < worldSize - 1) actions.add(moveN);
            if (agentY > 0) actions.add(moveS);
            if (agentX < worldSize - 1) actions.add(moveE);
            if (agentX > 0) actions.add(moveW);
            return actions;
        }

        @Override
        public Act exe(Atom actionSchema) {
            steps++;
            var reward = -0.05; // Small cost for moving

            String actionName;
            actionName = actionSchema instanceof Node node ? node.name : "UnknownAction";

            switch (actionName) {
                case "Action:MoveNorth":
                    if (agentY < worldSize - 1) agentY++;
                    else reward -= 0.2;
                    break; // Penalty for bump
                case "Action:MoveSouth":
                    if (agentY > 0) agentY--;
                    else reward -= 0.2;
                    break;
                case "Action:MoveEast":
                    if (agentX < worldSize - 1) agentX++;
                    else reward -= 0.2;
                    break;
                case "Action:MoveWest":
                    if (agentX > 0) agentX--;
                    else reward -= 0.2;
                    break;
                default:
                    System.err.println("WARN: Unknown action executed in GridWorld: " + actionSchema.id);
                    reward -= 1.0;
                    break;
            }

            if (agentX == goalX && agentY == goalY) {
                reward = 1.0; // Goal reward
                System.out.println("GridWorld: Agent reached the goal!");
            } else if (!running() && !(agentX == goalX && agentY == goalY)) {
                reward = -1.0; // Penalty for running out of time
                System.out.println("GridWorld: Agent ran out of steps.");
            }

            // Return new state perception and reward
            return new Act(perceive().perceivedAtoms, reward);
        }

        @Override
        public boolean running() {
            return steps < maxSteps && !(agentX == goalX && agentY == goalY);
        }
    }


    // === Nested Static Class: AgentController                           ===

    public class Agent {
        private final RandomGenerator random = new Random();
        private Atom lastActionAtom = null;
        private Atom previousStateAtom = null;
        private Atom currentGoalAtom = null;

        /**
         * Runs the main agent perceive-reason-act-learn loop.
         */
        public void runLoop(Game env, Atom goal, int maxCycles) {
            System.out.println("\n--- Agent: Starting Run ---");
            this.currentGoalAtom = goal;
            mem.atom(goal.id).ifPresent(g -> g.updateImportance(IMPORTANCE_BOOST_ON_GOAL_FOCUS, iteration()));
            System.out.println("Initial Goal: " + currentGoalAtom.id + " " + currentGoalAtom.truth());

            // Initial perception
            var initialPerception = env.perceive();
            previousStateAtom = perceiveAndLearnState(initialPerception.perceivedAtoms);

            for (var cycle = 0; cycle < maxCycles && env.running(); cycle++) {
                var currentTime = iteration(); // Use shared logical time
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, currentTime);
                if (previousStateAtom != null) {
                    System.out.println("Current State Atom: " + previousStateAtom.id);
                    // Optional: Display current state details
                    // mem.getLinksWithTarget(previousStateAtom.id())
                    //    .filter(l -> l.type() == Link.Type.MEMBER) // Assuming state composed of MEMBER links
                    //   .forEach(m -> System.out.println("  - " + m.targets().get(0)));
                } else {
                    System.out.println("Current State Atom: Unknown");
                }


                // 1. Reason (Optional: Focused Forward Chaining based on current state/goal)
                // inference.forwardChain(1); // Limited, broad forward chaining

                // 2. Plan & Select Action
                var selectedActionOpt = selectAction(env);

                if (selectedActionOpt.isEmpty()) {
                    System.out.println("Agent: No suitable action found or planned. Idling.");
                    lastActionAtom = null; // No action taken
                    // Optional: decay importance or take other idle action?
                } else {
                    lastActionAtom = selectedActionOpt.get();
                    System.out.println("Agent: Selected Action: " + lastActionAtom.id);

                    // 3. Execute Action
                    var actionResult = env.exe(lastActionAtom);

                    // 4. Perceive Outcome
                    var currentStateAtom = perceiveAndLearnState(actionResult.newStateAtoms);

                    // 5. Learn from Experience
                    learnFromExperience(currentStateAtom, actionResult.reward, currentTime);

                    // Update state for next loop
                    previousStateAtom = currentStateAtom;
                }

                // 6. Goal Check
                if (checkGoalAchieved()) {
                    System.out.println("*** Agent: Goal Achieved! (" + currentGoalAtom.id + ") ***");
                    break; // Terminate loop on goal achievement
                }

                // Optional delay
                // try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            System.out.println("--- Agent: Run Finished ---");
        }

        /**
         * Processes raw perception, learns atoms, and creates/updates a composite state atom.
         */
        private Atom perceiveAndLearnState(Collection<Atom> perceivedAtoms) {
            final var currentTime = iteration();
            List<String> presentFluentIds = Collections.synchronizedList(new ArrayList<>()); // Thread-safe list

            // Learn individual perceived atoms with high confidence and boost importance
            perceivedAtoms.parallelStream().forEach(pAtom -> {
                // Assign high confidence based on perception count
                var perceivedCertainty = Truth.of(pAtom.truth().strength, AGENT_DEFAULT_PERCEPTION_COUNT);
                var currentPercept = pAtom.withCertainty(perceivedCertainty);

                // Add time information if it's a temporal predicate/fluent
                if (currentPercept instanceof Link link) {
                    if (link.type == Link.Type.HOLDS_AT || link.type == Link.Type.EVALUATION) {
                        link.setTime(Time.at(currentTime));
                    }
                }

                // Learn the atom, boosting its importance significantly
                var learnedAtom = mem.learn(currentPercept);
                learnedAtom.updateImportance(IMPORTANCE_BOOST_ON_PERCEPTION, currentTime);

                // If atom represents a "true" fluent/state, add its ID for composite state
                if (learnedAtom.truth().strength > 0.5 && learnedAtom.truth().conf() > 0.5) {
                    // Filter for relevant state atoms (e.g., HOLDS_AT links, specific EVALUATION links)
                    if (learnedAtom instanceof Link link) {
                        if (link.type == Link.Type.HOLDS_AT) {
                            presentFluentIds.add(learnedAtom.id);
                        }
                    } else {
                        if (learnedAtom instanceof Node node && node.name.startsWith("State:")) { // Or just nodes representing state
                            presentFluentIds.add(learnedAtom.id);
                        }
                    }
                    // Add other conditions as needed based on state representation conventions
                }
            });

            // Create a composite state representation (e.g., StateNode linked to its fluent members)
            if (presentFluentIds.isEmpty()) {
                var emptyState = mem.node("State:Empty", Truth.TRUE, 0.8);
                emptyState.updateImportance(IMPORTANCE_BOOST_ON_PERCEPTION, currentTime);
                return emptyState;
            }

            Collections.sort(presentFluentIds); // Ensure canonical representation
            // Simple ID based on sorted fluent IDs hash
            var stateName = "State:" + Math.abs(String.join("|", presentFluentIds).hashCode());
            var stateNode = mem.node(stateName, Truth.TRUE, 0.9); // High STI for current state
            stateNode.updateImportance(IMPORTANCE_BOOST_ON_PERCEPTION, currentTime);

            // Optional: Explicitly link state node to its members for better reasoning
            // for (String fluentId : presentFluentIds) {
            //     mem.learnLinkByIds(Link.Type.MEMBER, Certainty.TRUE, 0.7, stateNode.id(), fluentId);
            // }

            return stateNode;
        }

        /**
         * Selects an action using planning, reactive evaluation, or exploration.
         */
        private Optional<Atom> selectAction(Game env) {
            var availableActions = env.actions();
            if (availableActions.isEmpty()) return empty();

            // 1. Try Planning towards the current goal
            var planOpt = inference.planToActionSequence(currentGoalAtom,
                    PLANNING_DEFAULT_MAX_PLAN_DEPTH,
                    INFERENCE_DEFAULT_MAX_DEPTH);
            if (planOpt.isPresent() && !planOpt.get().isEmpty()) {
                var firstActionInPlan = planOpt.get().getFirst(); // Get the first action recommended by the plan
                // Check if the planned action is actually available now
                if (availableActions.stream().anyMatch(a -> a.id.equals(firstActionInPlan.id))) {
                    System.out.println("Agent: Selecting action from plan: " + firstActionInPlan.id);
                    return Optional.of(firstActionInPlan);
                } else {
                    System.out.println("Agent: Planned action " + firstActionInPlan.id + " not available. Evaluating alternatives.");
                }
            } else {
                System.out.println("Agent: Planning failed or yielded empty plan.");
            }

            // 2. Fallback: Reactive Selection based on learned utility ("GoodAction" or predicted reward)
            System.out.println("Agent: Falling back to reactive selection...");
            Map<Atom, Double> actionUtilities = new ConcurrentHashMap<>();
            var goodActionNode = mem.node("GoodAction"); // Target node for utility
            if (goodActionNode != null) {

                availableActions.parallelStream().forEach(action -> {
                    // Query the estimated utility: Inheritance(Action, GoodAction)
                    var utilityQuery = new Link(Link.Type.INHERITANCE, List.of(action.id, goodActionNode.id), Truth.UNKNOWN, null);
                    var results = query(utilityQuery, 2); // Shallow search for utility

                    var utility = results.stream()
                            .mapToDouble(r -> r.inferredAtom.truth().strength * r.inferredAtom.truth().conf()) // Combine strength & confidence
                            .max().orElse(0); // Max utility found

                    // Alternative/Combined: Predict immediate reward: PredictiveImplication(Action, Reward)
                    // ... (similar query for reward prediction) ...

                    actionUtilities.put(action, utility);
                });
            }

            var bestActionEntry = actionUtilities.entrySet().stream()
                    .filter(entry -> entry.getValue() > AGENT_UTILITY_THRESHOLD_FOR_SELECTION) // Only consider actions with some positive utility
                    .max(Map.Entry.comparingByValue());

            if (bestActionEntry.isPresent()) {
                System.out.printf("Agent: Selecting action by max utility: %s (Utility: %.3f)%n",
                        bestActionEntry.get().getKey().id, bestActionEntry.get().getValue());
                return Optional.of(bestActionEntry.get().getKey());
            }

            // 3. Final Fallback: Exploration (Random Action)
            if (random.nextDouble() < AGENT_RANDOM_ACTION_PROBABILITY) {
                System.out.println("Agent: No preferred action. Selecting randomly for exploration.");
                return Optional.of(availableActions.get(random.nextInt(availableActions.size())));
            } else {
                // If not exploring, and no good options, maybe pick the least bad one or idle. Let's pick random for now.
                System.out.println("Agent: No preferred action and not exploring. Selecting randomly.");
                return Optional.of(availableActions.get(random.nextInt(availableActions.size())));
            }
        }

        /**
         * Learns from the outcome (new state, reward) of the last executed action.
         */
        private void learnFromExperience(Atom currentStateAtom, double reward, long currentTime) {
            if (previousStateAtom == null || lastActionAtom == null) return; // Cannot learn without context

            // 1. Learn State Transition: PredictiveImplication(Sequence(PrevState, Action), CurrentState)
            var sequenceLink = mem.link(Link.Type.SEQUENCE, Truth.TRUE, 0.8, null, previousStateAtom, lastActionAtom);
            var transitionCertainty = Truth.of(1.0, AGENT_DEFAULT_LEARNING_COUNT); // Observed transition
            var effectDelay = Time.range(1); // Assume minimal delay for now
            var transitionLink = mem.link(Link.Type.PREDICTIVE_IMPLICATION, transitionCertainty, 0.8, effectDelay, sequenceLink, currentStateAtom);
            // System.out.println("  Learn: Transition " + transitionLink.id() + " " + transitionLink.certainty());

            // 2. Learn Reward Association (State-Reward): PredictiveImplication(CurrentState, Reward)
            // Normalize reward to [0, 1] for strength? Or use raw value? Let's normalize.
            var normalizedRewardStrength = Math.max(0.0, Math.min(1.0, (reward + 1.0) / 2.0)); // Simple [-1,1] -> [0,1] scaling; adjust if reward range differs
            var rewardCertainty = Truth.of(normalizedRewardStrength, AGENT_DEFAULT_LEARNING_COUNT * 0.5); // Less count than transition
            var rewardNode = mem.node("Reward");

            if (rewardCertainty.strength > 0.05 && rewardCertainty.strength < 0.95) { // Learn if reward is non-neutral
                var stateRewardLink = mem.link(Link.Type.PREDICTIVE_IMPLICATION, rewardCertainty, 0.7, Time.IMMEDIATE, currentStateAtom, rewardNode);
                // System.out.println("  Learn: State-Reward " + stateRewardLink.id() + " " + stateRewardLink.certainty());
            }

            // 3. Learn Action Utility (Action -> GoodAction): Inheritance(Action, GoodAction)
            // This is a simple form of reinforcement learning update.
            var goodActionNode = mem.node("GoodAction");
            if (goodActionNode != null) {
                // Evidence strength based on reward sign, count based on learning rate
                var utilityEvidence = Truth.of(reward > 0 ? 1.0 : 0.0, AGENT_DEFAULT_LEARNING_COUNT);
                if (Math.abs(reward) > 0.01) { // Only update utility if reward is significant
                    var utilityLink = new Link(Link.Type.INHERITANCE, List.of(lastActionAtom.id, goodActionNode.id), utilityEvidence, null);
                    utilityLink.importance(0.7, currentTime);
                    var learnedUtility = mem.learn(utilityLink); // Learn/revise the utility link
                    System.out.printf("  Learn: Action-Utility Update %s -> %s%n", learnedUtility.id, learnedUtility.truth());
                }
            }
        }

        /**
         * Checks if the current goal state is achieved with sufficient confidence.
         */
        private boolean checkGoalAchieved() {
            if (currentGoalAtom == null) return false;
            var results = query(currentGoalAtom, 2); // Shallow check is usually sufficient
            return results.stream().anyMatch(r -> r.inferredAtom.truth().strength > 0.9 && r.inferredAtom.truth().conf() > 0.8);
        }
    }

} // End of CognitiveLogicEngine class
