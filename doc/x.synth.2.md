Okay, synthesizing the best aspects of the provided implementations into a single, enhanced Java class is a complex task. The goal is to create `UltimatePLN`, focusing on modularity (within the single class structure), robustness, feature completeness, and clarity.

This implementation draws heavily on the architectural style of **F** (Facade + Internal Classes), the unify/BC approach of **F/C**, the STI/LTI model of **A/D/F**, the forgetting mechanisms of **D/F**, the planning concepts of **C/E/F**, and the comprehensive link types and temporal concepts present across most versions. It uses modern Java features like `record` and `Optional`.

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * # Ultimate Probabilistic Logic Network (UltimatePLN)
 *
 * This class represents a synthesized, enhanced implementation of Probabilistic Logic Networks,
 * merging the strongest features from multiple conceptual versions (A-F). It aims for:
 * - **Modularity:** Achieved via nested static classes (KB, Inference, Agent) within this single file.
 * - **Completeness:** Integrates core PLN reasoning, attention, learning, planning, temporal logic, and HOL basics.
 * - **Autonomy:** Includes a basic agent control loop capable of interacting with an environment.
 * - **Cognitive Power:** Combines probabilistic reasoning, temporal concepts, planning, and basic unify.
 * - **Efficiency:** Uses indexing, importance-based heuristics, and concurrent collections.
 * - **Elegance & Extensibility:** Clear structure, modern Java usage, designed for adding features.
 * - **Self-Documentation:** Clear naming, Javadoc, and logical structure minimize external documentation needs.
 *
 * ## Key Synthesized Features:
 * - **Architecture:** Facade pattern (`UltimatePLN`) with encapsulated components (`KnowledgeBase`, `InferenceEngine`, `AgentController`). (Inspired by F)
 * - **Knowledge Representation:** Atom-centric KB using concurrent collections and indexing (Type, Target). (Common, similar to F/E/D/C)
 * - **TruthValue:** Immutable `record` for `TruthValue(strength, count)`. (Standard, refined)
 * - **Atom Importance:** Explicit STI/LTI fields within `Atom`, updated via boost/decay methods. (Model from A/D/F)
 * - **Forgetting:** Periodic and size-triggered mechanism based on STI/LTI thresholds, protecting essential atoms. (Combined D/F/A approach)
 * - **Inference:** Robust Deduction/Inversion using term probabilities, Modus Ponens. Forward chaining uses importance-prioritized queue. (Synthesized C/E/F/D)
 * - **Backward Chaining:** Supports querying with unify (`QueryResult`) and planning (`planToActionSequence`). (Inspired by F/C/E)
 * - **Planning:** Goal-directed BC seeking action sequences, leveraging temporal/causal links, with utility/random fallback. (Synthesized F/C/E/D)
 * - **Temporal Logic:** Includes `TimeSpec` record, Event Calculus link types (`INITIATES`, etc.), and temporal reasoning in inference/planning. (Comprehensive synthesis)
 * - **Higher-Order Logic:** `VariableNode`, `ForAll`/`Exists` links, basic unify integrated into inference. (Synthesized C/F)
 * - **Agent Control:** Encapsulated `AgentController` with a perceive-reason-select-act-learn loop. (Inspired by F/D)
 * - **Environment API:** Clear interfaces (`Environment`, `PerceptionResult`, `ActionResult`). (Similar to F)
 * - **Learning:** Learns state transitions (`Seq(State,Action)->Next`) and reward associations (State/Action -> Reward/Utility). (Synthesized C/F/D/E)
 *
 * @version 1.0 Synthesis
 */
public class UltimatePLN {

    // --- Configuration Constants ---
    private static final double DEFAULT_EVIDENCE_SENSITIVITY = 1.0; // k in confidence = N / (N + k)
    private static final double INFERENCE_CONFIDENCE_DISCOUNT_FACTOR = 0.9; // General inference uncertainty
    private static final double TEMPORAL_INFERENCE_DISCOUNT_FACTOR = 0.8; // Higher uncertainty for temporal predictions
    private static final double MIN_CONFIDENCE_THRESHOLD = 0.01; // Min confidence to be considered valid evidence
    private static final double MIN_STRENGTH_THRESHOLD = 0.01; // Min strength to be considered potentially relevant
    private static final double MIN_IMPORTANCE_FORGET_THRESHOLD = 0.01; // STI threshold for forgetting candidates
    private static final double STI_DECAY_RATE = 0.05; // Multiplicative decay factor for STI per cycle
    private static final double LTI_UPDATE_RATE = 0.02; // How much STI contributes to LTI increase per update
    private static final double LTI_DECAY_RATE = 0.001; // Slow decay for LTI over time
    private static final double IMPORTANCE_BOOST_ON_ACCESS = 0.05; // STI boost on getAtom/use
    private static final double IMPORTANCE_BOOST_ON_REVISION_MAX = 0.5; // Max STI boost when revising an atom
    private static final double IMPORTANCE_BOOST_ON_GOAL = 0.8; // STI boost for goal atoms
    private static final long FORGETTING_CYCLE_INTERVAL_MS = 15000; // Check forgetting every 15 seconds
    private static final int MAX_KB_SIZE_BEFORE_FORGET = 20000; // Trigger forgetting check if KB exceeds this size
    private static final int FORWARD_CHAIN_BATCH_SIZE = 100; // Max inferences per FC step
    private static final int DEFAULT_MAX_BC_DEPTH = 5; // Default query/planning depth
    private static final int DEFAULT_MAX_PLAN_DEPTH = 7; // Max actions in a plan sequence
    private static final double DEFAULT_PERCEPTION_COUNT = 5.0; // Default evidence for perceived facts
    private static final double DEFAULT_ACTION_UTILITY_COUNT = 1.0; // Default evidence for learned utility
    private static final String VARIABLE_PREFIX = "$"; // Convention for variable names
    private static final Set<String> PROTECTED_NODE_NAMES = Set.of("Reward", "GoodAction"); // Nodes protected from forgetting

    // --- Core Components (Final Fields) ---
    private final KnowledgeBase kb;
    private final InferenceEngine inference;
    private final AgentController agent;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong globalTime = new AtomicLong(0); // Simple global time ticker

    // --- Constructor ---
    public UltimatePLN() {
        this.kb = new KnowledgeBase(this::getGlobalTime); // Pass time provider
        this.inference = new InferenceEngine(this.kb);
        this.agent = new AgentController(this.kb, this.inference);

        // Schedule periodic forgetting task
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UltimatePLN-Maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(kb::forgetLowImportanceAtoms,
                FORGETTING_CYCLE_INTERVAL_MS, FORGETTING_CYCLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        System.out.println("UltimatePLN Initialized.");
    }

    // --- Public Facade Methods ---

    /** Retrieves the current global time step. */
    public long getGlobalTime() {
        return globalTime.get();
    }

    /** Increments the global time step. */
    public long incrementGlobalTime() {
        return globalTime.incrementAndGet();
    }

    /** Adds or updates an Atom, performing revision and updating importance. */
    public Atom addAtom(Atom atom) {
        return kb.addAtom(atom);
    }

    /** Gets an Atom by its ID, updating its importance. Returns Optional. */
    public Optional<Atom> getAtom(String id) {
        return kb.getAtom(id);
    }

    /** Gets a Node by name, creating it if absent with default settings. */
    public Node getOrCreateNode(String name) {
        return kb.getOrCreateNode(name, TruthValue.DEFAULT, 0.1); // Default low importance
    }

    /** Gets a Node by name, creating it if absent with specified settings. */
    public Node getOrCreateNode(String name, TruthValue tv, double initialSTI) {
        return kb.getOrCreateNode(name, tv, initialSTI);
    }

    /** Creates or retrieves a VariableNode. */
    public VariableNode getOrCreateVariableNode(String name) {
        return kb.getOrCreateVariableNode(name);
    }

    /** Creates a TimeSpec representing a specific time point. */
    public TimeSpec createTimePoint(long time) {
        return TimeSpec.of(time);
    }

    /** Creates a TimeSpec representing a time interval. */
    public TimeSpec createTimeInterval(long start, long end) {
        return TimeSpec.of(start, end);
    }

     /** Creates a Link and adds/revises it in the KB. */
    public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, Atom... targets) {
        return kb.addLink(type, tv, initialSTI, null, targets); // No time spec by default
    }

    /** Creates a Link with a TimeSpec and adds/revises it in the KB. */
    public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, TimeSpec time, Atom... targets) {
        return kb.addLink(type, tv, initialSTI, time, targets);
    }

    /** Creates a Link and adds/revises it in the KB using target IDs. */
    public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, String... targetIds) {
        return kb.addLink(type, tv, initialSTI, null, targetIds); // No time spec by default
    }

    /** Creates a Link with a TimeSpec and adds/revises it in the KB using target IDs. */
    public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, TimeSpec time, String... targetIds) {
        return kb.addLink(type, tv, initialSTI, time, targetIds);
    }

    /** Performs forward chaining inference based on importance heuristics. */
    public void forwardChain(int maxSteps) {
        inference.forwardChain(maxSteps);
    }

    /**
     * Performs backward chaining to query the truth value of a target Atom pattern.
     * Supports variables and unify.
     *
     * @param queryAtom The Atom pattern to query (can contain variables).
     * @param maxDepth Maximum recursion depth.
     * @return A list of bind (variable assignments) and the corresponding inferred Atom for each successful proof path.
     */
    public List<QueryResult> query(Atom queryAtom, int maxDepth) {
        return inference.backwardChain(queryAtom, maxDepth);
    }

    /**
     * Attempts to find a sequence of actions (represented as Atoms, e.g., ExecutionLinks)
     * that are predicted to lead to the desired goal state.
     *
     * @param goalAtom The Atom representing the desired goal state.
     * @param maxPlanDepth Maximum length of the action sequence.
     * @param maxSearchDepth Maximum recursion depth for finding preconditions.
     * @return An optional list of action Atoms representing the plan, or empty if no plan found.
     */
    public Optional<List<Atom>> planToActionSequence(Atom goalAtom, int maxPlanDepth, int maxSearchDepth) {
        return inference.planToActionSequence(goalAtom, maxPlanDepth, maxSearchDepth);
    }

    /** Runs the agent's perceive-reason-act cycle in a given environment. */
    public void runAgent(Environment environment, Atom goal, int maxSteps) {
        agent.run(environment, goal, maxSteps);
    }

    /** Shuts down the background scheduler (e.g., for forgetting). */
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
        System.out.println("UltimatePLN scheduler shut down.");
    }

    // --- Getters for internal components (optional, for inspection/debugging) ---
    public KnowledgeBase getKnowledgeBase() { return kb; }
    public InferenceEngine getInferenceEngine() { return inference; }
    public AgentController getAgentController() { return agent; }

    // --- Main Method (Comprehensive Example) ---
    public static void main(String[] args) {
        UltimatePLN pln = new UltimatePLN();
        try {
            System.out.println("--- UltimatePLN Comprehensive Demo ---");

            // --- 1. Basic Knowledge & Inference ---
            System.out.println("\n[1] Basic Knowledge & Forward Chaining:");
            Node cat = pln.getOrCreateNode("Cat");
            Node mammal = pln.getOrCreateNode("Mammal");
            Node animal = pln.getOrCreateNode("Animal");
            Node dog = pln.getOrCreateNode("Dog");
            Node predator = pln.getOrCreateNode("Predator");
            Node hasClaws = pln.getOrCreateNode("HasClaws"); // Treat as node concept for simplicity

            pln.addLink(Link.LinkType.INHERITANCE, TruthValue.of(0.95, 20), 0.8, cat, mammal);
            pln.addLink(Link.LinkType.INHERITANCE, TruthValue.of(0.98, 50), 0.9, mammal, animal);
            pln.addLink(Link.LinkType.INHERITANCE, TruthValue.of(0.90, 30), 0.8, dog, mammal);
            pln.addLink(Link.LinkType.INHERITANCE, TruthValue.of(0.7, 10), 0.6, cat, predator);
            pln.addLink(Link.LinkType.INHERITANCE, TruthValue.of(0.8, 15), 0.7, predator, hasClaws);

            pln.forwardChain(2); // Run inference

            System.out.println("\nChecking derived knowledge:");
            pln.getAtom(Link.generateId(Link.LinkType.INHERITANCE, List.of(dog.id, animal.id)))
               .ifPresent(a -> System.out.println(" -> Derived Dog->Animal: " + a)); // Should exist
            pln.getAtom(Link.generateId(Link.LinkType.INHERITANCE, List.of(cat.id, hasClaws.id)))
               .ifPresent(a -> System.out.println(" -> Derived Cat->HasClaws: " + a)); // Should exist

            // --- 2. Backward Chaining Query & Unification ---
            System.out.println("\n[2] Backward Chaining & Unification:");
            VariableNode varX = pln.getOrCreateVariableNode("X");
            Node chasesPred = pln.getOrCreateNode("Predicate_Chases");
            Node ball = pln.getOrCreateNode("Ball");
            pln.addLink(Link.LinkType.EVALUATION, TruthValue.of(0.7, 10), 0.7, chasesPred, dog, ball); // Dog chases Ball

            Atom query = new Link(Link.LinkType.EVALUATION, List.of(chasesPred.id, varX.id, ball.id), TruthValue.UNKNOWN, null);
            System.out.println("Query: What chases the ball? " + query.id);
            List<QueryResult> results = pln.query(query, 3);
            results.forEach(res -> System.out.println(" -> Result: " + res));

            // --- 3. Temporal Logic & Planning ---
            System.out.println("\n[3] Temporal Logic & Planning:");
            Node agent = pln.getOrCreateNode("Agent");
            Node key = pln.getOrCreateNode("Key");
            Node door = pln.getOrCreateNode("Door");
            Node locationA = pln.getOrCreateNode("LocationA");
            Node locationB = pln.getOrCreateNode("LocationB");
            Node hasKeyFluent = pln.getOrCreateNode("Fluent_HasKey");
            Node doorOpenFluent = pln.getOrCreateNode("Fluent_DoorOpen");
            Node atLocationPred = pln.getOrCreateNode("Predicate_AtLocation");
            Node pickupAction = pln.getOrCreateNode("Action_PickupKey");
            Node openAction = pln.getOrCreateNode("Action_OpenDoor");
            Node moveToBAction = pln.getOrCreateNode("Action_MoveToB");

            // Initial State: Agent at A, doesn't have key, door closed
            long t0 = pln.incrementGlobalTime();
            pln.addLink(Link.LinkType.HOLDS_AT, TruthValue.TRUE, 1.0, TimeSpec.of(t0), atLocationPred, agent, locationA);
            pln.addLink(Link.LinkType.HOLDS_AT, TruthValue.FALSE, 1.0, TimeSpec.of(t0), hasKeyFluent, agent); // Not HasKey
            pln.addLink(Link.LinkType.HOLDS_AT, TruthValue.FALSE, 1.0, TimeSpec.of(t0), doorOpenFluent, door); // Not DoorOpen

            // Rules:
            // PickupKey initiates HasKey (if at LocA)
            Link pickupPrecond = new Link(Link.LinkType.HOLDS_AT, List.of(atLocationPred.id, agent.id, locationA.id), TruthValue.TRUE, null); // AtLocA required
            Link pickupEffect = new Link(Link.LinkType.HOLDS_AT, List.of(hasKeyFluent.id, agent.id), TruthValue.TRUE, null); // Effect: HasKey
            pln.addLink(Link.LinkType.INITIATES, TruthValue.of(0.9, 10), 0.9, TimeSpec.ofDuration(100), pickupAction, pickupEffect); // Initiates link
             // Add predictive implication with precondition for planning
             // Seq(Holds(AtLocA), Action(PickupKey)) => Holds(HasKey)
             Link pickupSeq = pln.addLink(Link.LinkType.SEQUENCE, TruthValue.TRUE, 0.9, pickupPrecond, pickupAction);
             pln.addLink(Link.LinkType.PREDICTIVE_IMPLICATION, TruthValue.of(0.9, 10), 0.9, TimeSpec.ofDuration(100), pickupSeq, pickupEffect);


            // OpenDoor initiates DoorOpen (if HasKey)
            Link openPrecond = new Link(Link.LinkType.HOLDS_AT, List.of(hasKeyFluent.id, agent.id), TruthValue.TRUE, null); // HasKey required
            Link openEffect = new Link(Link.LinkType.HOLDS_AT, List.of(doorOpenFluent.id, door.id), TruthValue.TRUE, null); // Effect: DoorOpen
            pln.addLink(Link.LinkType.INITIATES, TruthValue.of(0.8, 10), 0.8, TimeSpec.ofDuration(200), openAction, openEffect);
            Link openSeq = pln.addLink(Link.LinkType.SEQUENCE, TruthValue.TRUE, 0.9, openPrecond, openAction);
            pln.addLink(Link.LinkType.PREDICTIVE_IMPLICATION, TruthValue.of(0.8, 10), 0.8, TimeSpec.ofDuration(200), openSeq, openEffect);

             // MoveToB changes location (if door open)
             Link movePrecond = new Link(Link.LinkType.HOLDS_AT, List.of(doorOpenFluent.id, door.id), TruthValue.TRUE, null); // DoorOpen required
             Link moveEffect = new Link(Link.LinkType.HOLDS_AT, List.of(atLocationPred.id, agent.id, locationB.id), TruthValue.TRUE, null); // Effect: AtLocB
             // Add termination of AtLocA? EVALUATION links better for states? Use HOLDS_AT for consistency.
             Link moveSeq = pln.addLink(Link.LinkType.SEQUENCE, TruthValue.TRUE, 0.9, movePrecond, moveToBAction);
             pln.addLink(Link.LinkType.PREDICTIVE_IMPLICATION, TruthValue.of(0.95, 10), 0.9, TimeSpec.ofDuration(300), moveSeq, moveEffect);


            // Goal: Agent At Location B
            Atom goal = moveEffect; // Goal is the link representing Agent At Location B

            System.out.println("\nPlanning to achieve goal: " + goal.id);
            Optional<List<Atom>> planOpt = pln.planToActionSequence(goal, 5, 3);

            planOpt.ifPresentOrElse(
                    plan -> {
                        System.out.println("Plan Found:");
                        plan.forEach(action -> System.out.println(" -> " + action.id));
                    },
                    () -> System.out.println("No plan found.")
            );

            // --- 4. Agent Simulation (Placeholder - Requires Environment Impl) ---
            System.out.println("\n[4] Agent Simulation (Conceptual):");
            System.out.println("Initialize Environment, set goal Atom, call pln.runAgent(env, goal, steps)");
            // Example: BasicGridWorld gridWorld = new BasicGridWorld(pln);
            //          pln.runAgent(gridWorld, goal, 20);


             // --- 5. Forgetting Check ---
             System.out.println("\n[5] Forgetting Check:");
             System.out.println("KB size before wait: " + pln.kb.atoms.size());
             System.out.println("Waiting for forgetting cycle...");
             try { Thread.sleep(FORGETTING_CYCLE_INTERVAL_MS + 2000); } catch (InterruptedException ignored) {}
             System.out.println("KB size after wait: " + pln.kb.atoms.size());


        } catch (Exception e) {
            System.err.println("An error occurred during the demo: " + e.getMessage());
            e.printStackTrace();
        } finally {
            pln.shutdown(); // Ensure scheduler is stopped
        }
        System.out.println("\n--- UltimatePLN Demo Finished ---");
    }


    // ========================================================================
    // === Nested Static Class: KnowledgeBase                               ===
    // ========================================================================
    public static class KnowledgeBase {
        private final ConcurrentMap<String, Atom> atoms = new ConcurrentHashMap<>(16, 0.75f, Runtime.getRuntime().availableProcessors());
        // Indices
        private final ConcurrentMap<Link.LinkType, ConcurrentSkipListSet<String>> linksByType = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, ConcurrentSkipListSet<String>> linksByTarget = new ConcurrentHashMap<>();
        private final Function<Void, Long> timeProvider; // Function to get current time

        public KnowledgeBase(Function<Void, Long> timeProvider) {
            this.timeProvider = timeProvider;
            // Initialize maps for all link types to avoid computeIfAbsent later in critical paths
            for (Link.LinkType type : Link.LinkType.values()) {
                linksByType.put(type, new ConcurrentSkipListSet<>());
            }
        }

        /** Adds or updates an Atom, handling revision and index updates. */
        public synchronized Atom addAtom(Atom atom) {
            final long currentTime = timeProvider.apply(null);
            Atom result = atoms.compute(atom.id, (id, existing) -> {
                if (existing == null) {
                    atom.updateAccessTime(currentTime);
                    atom.updateImportance(0.1); // Small boost on initial add
                    return atom;
                } else {
                    TruthValue oldTV = existing.tv;
                    TruthValue revisedTV = existing.tv.merge(atom.tv);
                    existing.tv = revisedTV; // Update TV
                    existing.updateAccessTime(currentTime); // Update access time

                    // Update importance based on revision novelty/confirmation
                    double boost = calculateImportanceBoost(oldTV, atom.tv, revisedTV);
                    existing.updateImportance(boost + atom.shortTermImportance * 0.1); // Also factor in importance of new evidence

                    // Merge TimeSpec if applicable (e.g., averaging, extending interval - needs specific logic)
                    if (existing instanceof Link && atom instanceof Link) {
                         ((Link)existing).time = TimeSpec.merge(((Link)existing).time, ((Link)atom).time);
                    }

                    // System.out.println("Revised: " + existing.id + " -> " + existing.tv + " STI: " + existing.shortTermImportance);
                    return existing;
                }
            });

            // Update indices if it's a Link
            if (result instanceof Link) {
                updateIndices((Link) result);
            }

            // Trigger forgetting check if KB grows too large
            if (atoms.size() > MAX_KB_SIZE_BEFORE_FORGET) {
                // Avoid running directly in synchronized block
                 CompletableFuture.runAsync(this::forgetLowImportanceAtoms);
            }
            return result;
        }

        /** Retrieves an Atom, updating its STI. */
        public Optional<Atom> getAtom(String id) {
            Atom atom = atoms.get(id);
            if (atom != null) {
                atom.updateAccessTime(timeProvider.apply(null));
                atom.updateImportance(IMPORTANCE_BOOST_ON_ACCESS); // Boost on access
            }
            return Optional.ofNullable(atom);
        }

        /** Gets a Node by name, creating if absent. */
        public Node getOrCreateNode(String name, TruthValue tv, double initialSTI) {
            String nodeId = Node.generateIdFromName(name);
            // Use computeIfAbsent for thread-safe creation
            return (Node) atoms.computeIfAbsent(nodeId, id -> {
                Node newNode = new Node(name, tv);
                newNode.shortTermImportance = initialSTI;
                newNode.longTermImportance = initialSTI * LTI_UPDATE_RATE;
                newNode.updateAccessTime(timeProvider.apply(null));
                return newNode;
            });
        }

        /** Gets or creates a VariableNode. */
        public VariableNode getOrCreateVariableNode(String name) {
            String varId = VariableNode.generateIdFromName(name);
            return (VariableNode) atoms.computeIfAbsent(varId, id -> {
                 VariableNode vn = new VariableNode(name);
                 vn.updateAccessTime(timeProvider.apply(null));
                 return vn;
            });
        }

        /** Creates and adds/revises a Link. */
        public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, TimeSpec time, Atom... targets) {
            List<String> targetIds = Arrays.stream(targets)
                                           .filter(Objects::nonNull)
                                           .map(a -> a.id)
                                           .collect(Collectors.toList());
            return createAndAddLinkInternal(type, tv, initialSTI, time, targetIds);
        }

        /** Creates and adds/revises a Link using target IDs. */
        public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, TimeSpec time, String... targetIds) {
             if (targetIds == null || targetIds.length == 0 || Arrays.stream(targetIds).anyMatch(Objects::isNull)) {
                 System.err.println("ERROR: Attempted to create link with null or empty targets. Type: " + type);
                 // Consider throwing exception or returning null marker
                 return null;
             }
            return createAndAddLinkInternal(type, tv, initialSTI, time, Arrays.asList(targetIds));
        }

        private Link createAndAddLinkInternal(Link.LinkType type, TruthValue tv, double initialSTI, TimeSpec time, List<String> targetIds) {
             // Ensure targets exist? Optional check for robustness.
             // for (String targetId : targetIds) {
             //     if (!atoms.containsKey(targetId)) {
             //        System.err.println("Warning: Creating link with non-existent target: " + targetId);
             //     }
             // }
             Link link = new Link(type, targetIds, tv, time);
             link.shortTermImportance = initialSTI;
             link.longTermImportance = initialSTI * LTI_UPDATE_RATE;
             return (Link) addAtom(link); // Use addAtom for revision/indexing
        }


        /** Removes an atom and updates indices. Needs external synchronization if called outside addAtom. */
        private void removeAtomInternal(String id) {
            Atom removed = atoms.remove(id);
            if (removed instanceof Link) {
                removeIndices((Link) removed);
            }
        }

        /** Updates indices for a given link. Assumes link is already in the main map. */
        private void updateIndices(Link link) {
            linksByType.get(link.type).add(link.id); // Map should be pre-populated
            for (String targetId : link.targets) {
                linksByTarget.computeIfAbsent(targetId, k -> new ConcurrentSkipListSet<>()).add(link.id);
            }
        }

        /** Removes indices for a given link. */
        private void removeIndices(Link link) {
            Optional.ofNullable(linksByType.get(link.type)).ifPresent(set -> set.remove(link.id));
            for (String targetId : link.targets) {
                Optional.ofNullable(linksByTarget.get(targetId)).ifPresent(set -> set.remove(link.id));
            }
            // Clean up empty index sets? Can be done periodically or here.
             // if (linksByType.get(link.type).isEmpty()) linksByType.remove(link.type);
             // if (linksByTarget.get(targetId) != null && linksByTarget.get(targetId).isEmpty()) linksByTarget.remove(targetId);
        }

        /** Retrieves links of a specific type. Updates importance of accessed links. */
        public Stream<Link> getLinksByType(Link.LinkType type) {
            return linksByType.getOrDefault(type, ConcurrentSkipListSet.of()) // Use default empty set
                    .stream()
                    .map(this::getAtom) // Use getAtom to update importance
                    .filter(Optional::isPresent).map(Optional::get)
                    .filter(Link.class::isInstance) // Ensure it's a Link
                    .map(Link.class::cast);
        }

        /** Retrieves links that have a specific Atom as a target. Updates importance. */
        public Stream<Link> getLinksByTarget(String targetId) {
            return linksByTarget.getOrDefault(targetId, ConcurrentSkipListSet.of())
                    .stream()
                    .map(this::getAtom) // Use getAtom to update importance
                    .filter(Optional::isPresent).map(Optional::get)
                    .filter(Link.class::isInstance)
                    .map(Link.class::cast);
        }

        /** Retrieves all atoms currently in the knowledge base. Does NOT update importance. */
        public Collection<Atom> getAllAtoms() {
            return atoms.values();
        }

        /** Implements the forgetting mechanism. Called periodically and by size trigger. */
        public synchronized void forgetLowImportanceAtoms() {
            final long currentTime = timeProvider.apply(null);
            int initialSize = atoms.size();
            if (initialSize <= MAX_KB_SIZE_BEFORE_FORGET / 2) return; // Don't prune if very small

            List<Atom> candidates = new ArrayList<>(atoms.values());

            // Calculate importance and decay in one pass
            candidates.forEach(atom -> {
                 atom.decayImportance();
                 // Consider marking importance explicitly here if needed for sorting
             });

             // Sort by current importance (ascending)
             candidates.sort(Comparator.comparingDouble(a -> a.getCurrentImportance(currentTime)));

             int removalTargetCount = Math.max(0, initialSize - MAX_KB_SIZE_BEFORE_FORGET); // How many to aim to remove
             int removedCount = 0;

             // Remove low-importance atoms, protecting critical ones
             for (Atom atom : candidates) {
                 if (removedCount >= removalTargetCount && initialSize - removedCount < MAX_KB_SIZE_BEFORE_FORGET * 0.8) {
                     break; // Stop if we've removed enough or reached a comfortable size
                 }

                 double currentImportance = atom.getCurrentImportance(currentTime);
                 if (currentImportance < MIN_IMPORTANCE_FORGET_THRESHOLD) {
                     boolean isProtected = (atom instanceof Node && PROTECTED_NODE_NAMES.contains(((Node) atom).name))
                                            || (atom instanceof VariableNode); // Protect variables
                     // Add other protection logic (e.g., part of active goal/plan) if AgentController provides info

                     if (!isProtected) {
                         removeAtomInternal(atom.id); // Use internal remove without sync contention loop
                         removedCount++;
                     }
                 } else {
                     // Since sorted, all remaining atoms have higher importance
                     break;
                 }
             }

            if (removedCount > 0) {
                System.out.printf("INFO: Forget Cycle: Removed %d atoms (below importance < %.3f). KB size %d -> %d.%n",
                        removedCount, MIN_IMPORTANCE_FORGET_THRESHOLD, initialSize, atoms.size());
            }
        }

        /** Calculates importance boost based on revision novelty/strength change. */
        private double calculateImportanceBoost(TruthValue oldTV, TruthValue newTV, TruthValue revisedTV) {
            double strengthChange = Math.abs(revisedTV.strength - oldTV.strength);
            double confidenceChange = Math.abs(revisedTV.getConfidence() - oldTV.getConfidence());
            // Boost more for significant changes or high-confidence confirmations
            double boost = (strengthChange * 0.5 + confidenceChange) * (1.0 + newTV.getConfidence());
            return Math.min(IMPORTANCE_BOOST_ON_REVISION_MAX, boost); // Cap the boost
        }
    }


    // ========================================================================
    // === Nested Static Class: InferenceEngine                           ===
    // ========================================================================
    public static class InferenceEngine {
        private final KnowledgeBase kb;

        public InferenceEngine(KnowledgeBase kb) { this.kb = kb; }

        /** Performs PLN Deduction: (A->B, B->C) => A->C */
        public Optional<Link> deduction(Link linkAB, Link linkBC) {
             if (!isValidPremiseForDeduction(linkAB) || !isValidPremiseForDeduction(linkBC) || linkAB.type != linkBC.type) {
                 return Optional.empty();
             }

             final String aId = linkAB.targets.get(0);
             final String bId = linkAB.targets.get(1);
             final String cId = linkBC.targets.get(1);
             if (!bId.equals(linkBC.targets.get(0))) return Optional.empty(); // Must chain correctly A->B, B->C

             // Fetching nodes updates their importance, which is desirable
             Optional<Atom> nodeAOpt = kb.getAtom(aId);
             Optional<Atom> nodeBOpt = kb.getAtom(bId);
             Optional<Atom> nodeCOpt = kb.getAtom(cId);
             if (nodeAOpt.isEmpty() || nodeBOpt.isEmpty() || nodeCOpt.isEmpty()) return Optional.empty();

             TruthValue tvAB = linkAB.tv; TruthValue tvBC = linkBC.tv;
             TruthValue tvB = nodeBOpt.get().tv; TruthValue tvC = nodeCOpt.get().tv;

             double sAC;
             double sB = tvB.strength;
             if (Math.abs(1.0 - sB) < 1e-9) { sAC = tvBC.strength; }
             else if (sB < 1e-9) { sAC = tvAB.strength * tvBC.strength; } // Simplified if P(B)=0
             else { sAC = tvAB.strength * tvBC.strength + (1.0 - tvAB.strength) * (tvC.strength - sB * tvBC.strength) / (1.0 - sB); }
             sAC = Math.max(0.0, Math.min(1.0, sAC));

             double discount = (linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION) ? TEMPORAL_INFERENCE_DISCOUNT_FACTOR : INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;
             double nAC = discount * Math.min(tvAB.count, tvBC.count) * tvB.getConfidence(); // Factor in confidence of B
             TruthValue tvAC = TruthValue.of(sAC, nAC);

             TimeSpec timeAC = (linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION) ? TimeSpec.add(linkAB.time, linkBC.time) : null;
             double inferredSTI = IMPORTANCE_BOOST_ON_ACCESS + (linkAB.shortTermImportance + linkBC.shortTermImportance) * 0.3; // Inherit importance

             return Optional.of(kb.addLink(linkAB.type, tvAC, inferredSTI, timeAC, aId, cId));
        }

        private boolean isValidPremiseForDeduction(Link link) {
            return link != null && link.targets.size() == 2 &&
                   (link.type == Link.LinkType.INHERITANCE || link.type == Link.LinkType.PREDICTIVE_IMPLICATION);
        }


        /** Performs PLN Inversion (Abduction/Bayes): (A->B) => (B->A) */
        public Optional<Link> inversion(Link linkAB) {
             if (!isValidPremiseForDeduction(linkAB)) return Optional.empty(); // Use same check as deduction

             String aId = linkAB.targets.get(0);
             String bId = linkAB.targets.get(1);
             Optional<Atom> nodeAOpt = kb.getAtom(aId);
             Optional<Atom> nodeBOpt = kb.getAtom(bId);
             if (nodeAOpt.isEmpty() || nodeBOpt.isEmpty()) return Optional.empty();

             TruthValue tvAB = linkAB.tv; TruthValue tvA = nodeAOpt.get().tv; TruthValue tvB = nodeBOpt.get().tv;

             double sBA;
             double nBA = 0.0;
             if (tvB.strength < 1e-9) { sBA = 0.5; } // Max uncertainty if P(B)=0
             else { sBA = Math.max(0.0, Math.min(1.0, tvAB.strength * tvA.strength / tvB.strength)); } // Clamp

             double discount = (linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION) ? TEMPORAL_INFERENCE_DISCOUNT_FACTOR : INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;
             if (tvB.strength >= 1e-9) { // Only assign count if calculation was valid
                 nBA = discount * tvAB.count * tvA.getConfidence() * tvB.getConfidence();
             }
             TruthValue tvBA = TruthValue.of(sBA, nBA);

             TimeSpec timeBA = (linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION && linkAB.time != null) ? linkAB.time.invert() : null;
             double inferredSTI = IMPORTANCE_BOOST_ON_ACCESS + linkAB.shortTermImportance * 0.5;

             return Optional.of(kb.addLink(linkAB.type, tvBA, inferredSTI, timeBA, bId, aId)); // Reversed targets
        }

        /** Performs Probabilistic Modus Ponens: (A, A->B) => Update B. */
        public Optional<Atom> modusPonens(Atom premiseA, Link implicationLink) {
             if (premiseA == null || !isValidPremiseForDeduction(implicationLink) || !implicationLink.targets.get(0).equals(premiseA.id)) {
                 return Optional.empty();
             }

             String bId = implicationLink.targets.get(1);
             Optional<Atom> conclusionAtomOpt = kb.getAtom(bId);
             if (conclusionAtomOpt.isEmpty()) return Optional.empty(); // Conclusion must exist

             Atom conclusionAtom = conclusionAtomOpt.get();
             TruthValue tvA = premiseA.tv; TruthValue tvAB = implicationLink.tv;

             // Calculate derived evidence for B from this specific inference step
             double derived_sB = tvA.strength * tvAB.strength; // Simplified contribution
             derived_sB = Math.max(0.0, Math.min(1.0, derived_sB));

             double discount = (implicationLink.type == Link.LinkType.PREDICTIVE_IMPLICATION) ? TEMPORAL_INFERENCE_DISCOUNT_FACTOR : INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;
             double derived_nB = discount * Math.min(tvA.count, tvAB.count);

             if (derived_nB < 1e-6) return Optional.of(conclusionAtom); // Negligible evidence

             TruthValue evidenceTV = TruthValue.of(derived_sB, derived_nB);
             Atom evidenceAtom = conclusionAtom.withTruthValue(evidenceTV); // Create atom representing just this evidence
             evidenceAtom.shortTermImportance = IMPORTANCE_BOOST_ON_ACCESS + (premiseA.shortTermImportance + implicationLink.shortTermImportance) * 0.4;

             // Add/revise this evidence into the KB atom for B
             Atom revisedB = kb.addAtom(evidenceAtom);

             System.out.printf("  MP: %s, %s => %s revised to %s%n", premiseA.id, implicationLink.id, revisedB.id, revisedB.tv);
             return Optional.of(revisedB);
        }

        /** Performs forward chaining using importance heuristics. */
        public void forwardChain(int maxSteps) {
            System.out.println("--- Inference: Starting Forward Chaining (Max Steps: " + maxSteps + ") ---");
            Set<String> executedInferences = new HashSet<>(); // Avoid redundant work

            for (int step = 0; step < maxSteps; step++) {
                PriorityQueue<PotentialInference> queue = gatherPotentialInferences();
                if (queue.isEmpty()) {
                    System.out.println("FC Step " + (step + 1) + ": No potential inferences found.");
                    break;
                }

                int inferencesMadeThisStep = executeTopInferences(queue, executedInferences);
                System.out.println("FC Step " + (step + 1) + ": Made " + inferencesMadeThisStep + " inferences.");

                if (inferencesMadeThisStep == 0) {
                    System.out.println("FC Step " + (step + 1) + ": Quiescence reached.");
                    break;
                }
            }
            System.out.println("--- Inference: Forward Chaining Finished ---");
        }

        private PriorityQueue<PotentialInference> gatherPotentialInferences() {
             PriorityQueue<PotentialInference> queue = new PriorityQueue<>(
                 Comparator.<PotentialInference, Double>comparing(inf -> inf.importance).reversed()
             );
             final long currentTime = kb.timeProvider.apply(null); // Get current time for importance calc

             // Gather Deductions (Inheritance & Predictive)
             Stream.concat(kb.getLinksByType(Link.LinkType.INHERITANCE), kb.getLinksByType(Link.LinkType.PREDICTIVE_IMPLICATION))
                 .filter(this::isValidPremiseForDeduction)
                 .forEach(linkAB -> {
                     kb.getLinksByTarget(linkAB.targets.get(1)) // Links potentially starting with B
                       .filter(linkBC -> linkBC.type == linkAB.type && isValidPremiseForDeduction(linkBC) && linkBC.targets.get(0).equals(linkAB.targets.get(1)))
                       .forEach(linkBC -> queue.add(new PotentialInference(InferenceRuleType.DEDUCTION, currentTime, linkAB, linkBC)));
                 });

             // Gather Inversions
             Stream.concat(kb.getLinksByType(Link.LinkType.INHERITANCE), kb.getLinksByType(Link.LinkType.PREDICTIVE_IMPLICATION))
                    .filter(this::isValidPremiseForDeduction)
                    .forEach(linkAB -> queue.add(new PotentialInference(InferenceRuleType.INVERSION, currentTime, linkAB)));

            // Gather Modus Ponens
             Stream.concat(kb.getLinksByType(Link.LinkType.INHERITANCE), kb.getLinksByType(Link.LinkType.PREDICTIVE_IMPLICATION))
                 .filter(this::isValidPremiseForDeduction)
                 .forEach(linkAB -> {
                      kb.getAtom(linkAB.targets.get(0)).ifPresent(premiseA -> {
                          if (premiseA.tv.getConfidence() > MIN_CONFIDENCE_THRESHOLD) { // Only if premise is somewhat confident
                               queue.add(new PotentialInference(InferenceRuleType.MODUS_PONENS, currentTime, premiseA, linkAB));
                          }
                      });
                 });

             // TODO: Gather Temporal Rules (Persistence, Initiate/Terminate effects)
             // TODO: Gather HOL Rules (Instantiation)

             return queue;
        }

         private int executeTopInferences(PriorityQueue<PotentialInference> queue, Set<String> executedInferences) {
             int inferencesMade = 0;
             int processedCount = 0;
             while (!queue.isEmpty() && processedCount < FORWARD_CHAIN_BATCH_SIZE) {
                 PotentialInference potential = queue.poll();
                 String inferenceId = potential.getUniqueId();

                 if (!executedInferences.contains(inferenceId)) {
                     Optional<?> resultOpt = Optional.empty();
                     switch (potential.ruleType) {
                         case DEDUCTION:    resultOpt = deduction(potential.premises[0], potential.premises[1]); break;
                         case INVERSION:    resultOpt = inversion(potential.premises[0]); break;
                         case MODUS_PONENS: resultOpt = modusPonens(potential.premises[0], potential.premises[1]); break;
                         // Add other rules
                     }

                     executedInferences.add(inferenceId); // Mark as attempted
                     if (potential.ruleType == InferenceRuleType.DEDUCTION) {
                         executedInferences.add(potential.getUniqueIdSwapped()); // Avoid double check
                     }

                     if (resultOpt.isPresent()) {
                          inferencesMade++;
                          // System.out.printf("  FC Executed: %s -> %s (Importance: %.3f)%n", potential.ruleType, ((Atom)resultOpt.get()).id, potential.importance);
                     }
                     processedCount++;
                 }
             }
             return inferencesMade;
         }

        /** Performs backward chaining query with unify. */
        public List<QueryResult> backwardChain(Atom queryAtom, int maxDepth) {
             return backwardChainRecursive(queryAtom, maxDepth, new HashMap<>(), new HashSet<>());
        }

        private List<QueryResult> backwardChainRecursive(Atom queryAtom, int depth, Map<String, String> bind, Set<String> visited) {
             // Apply current bind to the query before proceeding
             Optional<Atom> currentQueryOpt = substitute(queryAtom, bind);
             if (currentQueryOpt.isEmpty()) return Collections.emptyList(); // Substitution failed
             Atom currentQuery = currentQueryOpt.get();

             String visitedId = currentQuery.id + bind.hashCode(); // Unique ID for state + bind
             // System.out.println("  ".repeat(DEFAULT_MAX_BC_DEPTH - depth) + "BC: Query " + currentQuery.id + " Depth " + depth);

             if (depth <= 0 || visited.contains(visitedId)) {
                 // System.out.println("  ".repeat(DEFAULT_MAX_BC_DEPTH - depth) + "-> Stop (Depth/Cycle)");
                 return Collections.emptyList();
             }
             visited.add(visitedId);

             List<QueryResult> results = new ArrayList<>();

             // 1. Direct Match in KB
             kb.getAtom(currentQuery.id).ifPresent(match -> {
                 if (match.tv.getConfidence() > MIN_CONFIDENCE_THRESHOLD) {
                     // System.out.println("  ".repeat(DEFAULT_MAX_BC_DEPTH - depth) + "-> Direct Match: " + match);
                     results.add(new QueryResult(bind, match));
                 }
             });

             // 2. Rule-Based Derivation (Backward Application)

             // Try Deduction Backward: If query is A->C, seek A->B and B->C
             if (currentQuery instanceof Link && currentQuery.targets.size() == 2 &&
                 (currentQuery.type == Link.LinkType.INHERITANCE || currentQuery.type == Link.LinkType.PREDICTIVE_IMPLICATION))
             {
                 results.addAll(tryDeductionBackward(currentQuery, depth, bind, visited));
             }

             // Try Inversion Backward: If query is B->A, seek A->B
             if (currentQuery instanceof Link && currentQuery.targets.size() == 2 &&
                 (currentQuery.type == Link.LinkType.INHERITANCE || currentQuery.type == Link.LinkType.PREDICTIVE_IMPLICATION))
             {
                  results.addAll(tryInversionBackward(currentQuery, depth, bind, visited));
             }

             // Try Modus Ponens Backward: If query is B, seek A and A->B
             results.addAll(tryModusPonensBackward(currentQuery, depth, bind, visited));


             // Try ForAll Instantiation: If query P(c) matches body ForAll($X, P($X))
             results.addAll(tryForAllInstantiation(currentQuery, depth, bind, visited));

             // TODO: Try Temporal Rules Backward (e.g., HoldsAt, Initiates)


             visited.remove(visitedId); // Backtrack
             return results; // Combine and return results from all paths
        }

        // --- Backward Rule Helpers (Sketch) ---

        private List<QueryResult> tryDeductionBackward(Atom queryAC, int depth, Map<String, String> bind, Set<String> visited) {
            List<QueryResult> results = new ArrayList<>();
            Link linkAC = (Link) queryAC;
            String aId = linkAC.targets.get(0); String cId = linkAC.targets.get(1);

            // Iterate through potential intermediate nodes B (could optimize this)
            kb.getAllAtoms().stream()
              .filter(Node.class::isInstance).filter(a -> !(a instanceof VariableNode)) // Concrete nodes only
              .forEach(nodeB -> {
                  String bId = nodeB.id;
                  // Create subgoal patterns A->B and B->C
                  Atom subgoalAB = new Link(linkAC.type, List.of(aId, bId), TruthValue.UNKNOWN, null);
                  Atom subgoalBC = new Link(linkAC.type, List.of(bId, cId), TruthValue.UNKNOWN, null);

                  // Recurse on first subgoal A->B
                  List<QueryResult> resultsAB = backwardChainRecursive(subgoalAB, depth - 1, bind, visited);
                  for (QueryResult resAB : resultsAB) {
                      // Recurse on second subgoal B->C with updated bind
                      List<QueryResult> resultsBC = backwardChainRecursive(subgoalBC, depth - 1, resAB.bind, visited);
                      for (QueryResult resBC : resultsBC) {
                          // Apply deduction forward to get confidence for A->C
                          deduction((Link)resAB.inferredAtom, (Link)resBC.inferredAtom).ifPresent(inferredAC -> {
                               // Check if inferredAC unifies with original queryAC using final bind
                               unify(queryAC, inferredAC, resBC.bind).ifPresent(finalBindings ->
                                   results.add(new QueryResult(finalBindings, inferredAC))
                               );
                          });
                      }
                  }
              });
            return results;
        }

        private List<QueryResult> tryInversionBackward(Atom queryBA, int depth, Map<String, String> bind, Set<String> visited) {
             List<QueryResult> results = new ArrayList<>();
             Link linkBA = (Link) queryBA;
             String bId = linkBA.targets.get(0); String aId = linkBA.targets.get(1);

             // Create subgoal pattern A->B
             Atom subgoalAB = new Link(linkBA.type, List.of(aId, bId), TruthValue.UNKNOWN, null);

             // Recurse on subgoal A->B
             List<QueryResult> resultsAB = backwardChainRecursive(subgoalAB, depth - 1, bind, visited);
             for (QueryResult resAB : resultsAB) {
                 inversion((Link)resAB.inferredAtom).ifPresent(inferredBA -> {
                      unify(queryBA, inferredBA, resAB.bind).ifPresent(finalBindings ->
                          results.add(new QueryResult(finalBindings, inferredBA))
                      );
                 });
             }
             return results;
        }

         private List<QueryResult> tryModusPonensBackward(Atom queryB, int depth, Map<String, String> bind, Set<String> visited) {
             List<QueryResult> results = new ArrayList<>();
             String bId = queryB.id;

             // Find potential implication links A->B ending in B
             kb.getLinksByTarget(bId)
               .filter(linkAB -> (linkAB.type == Link.LinkType.INHERITANCE || linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION)
                               && linkAB.targets.size() == 2 && linkAB.targets.get(1).equals(bId))
               .forEach(linkAB -> {
                   String aId = linkAB.targets.get(0);
                   // Create subgoal pattern A
                   kb.getAtom(aId).ifPresent(subgoalA -> { // A needs to be a concrete Atom pattern here
                       // Recurse on subgoal A
                       List<QueryResult> resultsA = backwardChainRecursive(subgoalA, depth - 1, bind, visited);
                       for (QueryResult resA : resultsA) {
                            // Recurse on the link A->B itself to confirm its confidence
                            List<QueryResult> resultsAB = backwardChainRecursive(linkAB, depth - 1, resA.bind, visited);
                            for (QueryResult resAB : resultsAB) {
                                 // Apply Modus Ponens forward (simulated - just get TV)
                                 // Need a way to calculate the resulting TV for B without modifying KB here
                                 double sA = resA.inferredAtom.tv.strength;
                                 double sAB = resAB.inferredAtom.tv.strength;
                                 double derived_sB = Math.max(0.0, Math.min(1.0, sA * sAB));
                                 double nA = resA.inferredAtom.tv.count;
                                 double nAB = resAB.inferredAtom.tv.count;
                                 double discount = (linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION) ? TEMPORAL_INFERENCE_DISCOUNT_FACTOR : INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;
                                 double derived_nB = discount * Math.min(nA, nAB);
                                 TruthValue inferredTV_B = TruthValue.of(derived_sB, derived_nB);

                                 if(inferredTV_B.getConfidence() > MIN_CONFIDENCE_THRESHOLD) {
                                     Atom inferredB = queryB.withTruthValue(inferredTV_B); // Create B atom with inferred TV
                                      unify(queryB, inferredB, resAB.bind).ifPresent(finalBindings ->
                                         results.add(new QueryResult(finalBindings, inferredB))
                                     );
                                 }
                            }
                       }
                   });
               });
             return results;
         }

          private List<QueryResult> tryForAllInstantiation(Atom queryInstance, int depth, Map<String, String> bind, Set<String> visited) {
             List<QueryResult> results = new ArrayList<>();
             kb.getLinksByType(Link.LinkType.FOR_ALL).forEach(forAllLink -> {
                 if (forAllLink.targets.size() >= 2) {
                     Atom bodyPattern = kb.getAtom(forAllLink.targets.get(forAllLink.targets.size() - 1)).orElse(null);
                     if (bodyPattern != null) {
                         unify(bodyPattern, queryInstance, bind).ifPresent(finalBindings -> {
                              // Confirm the ForAll link itself has sufficient confidence
                              List<QueryResult> forAllResults = backwardChainRecursive(forAllLink, depth-1, finalBindings, visited);
                              for(QueryResult resForAll : forAllResults) {
                                  // If rule holds, the instance is supported with the rule's TV
                                  Atom supportedInstance = queryInstance.withTruthValue(resForAll.inferredAtom.tv);
                                  results.add(new QueryResult(resForAll.bind, supportedInstance));
                              }
                         });
                     }
                 }
             });
             return results;
         }

        /** Attempts to plan a sequence of actions to achieve a goal. */
        public Optional<List<Atom>> planToActionSequence(Atom goalAtom, int maxPlanDepth, int maxSearchDepth) {
            System.out.println("--- Inference: Planning Action Sequence ---");
            System.out.println("Goal: " + goalAtom.id);
            return planRecursive(goalAtom, new LinkedList<>(), maxPlanDepth, maxSearchDepth, new HashSet<>());
        }

        // Recursive planner helper
        private Optional<List<Atom>> planRecursive(Atom currentGoal, LinkedList<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoals) {
            String goalId = currentGoal.id;
            String indent = "  ".repeat(DEFAULT_MAX_PLAN_DEPTH - planDepthRemaining);
            System.out.println(indent + "Plan: Seeking " + goalId + " (Depth Left: " + planDepthRemaining + ")");

            if (planDepthRemaining <= 0 || visitedGoals.contains(goalId)) {
                 System.out.println(indent + "-> Stop (Depth/Cycle)");
                return Optional.empty();
            }

            // Check if goal holds (using backward chaining)
            List<QueryResult> goalResults = backwardChain(currentGoal, searchDepth);
            if (!goalResults.isEmpty() && goalResults.stream().anyMatch(r -> r.inferredAtom.tv.strength > 0.8 && r.inferredAtom.tv.getConfidence() > 0.5)) {
                System.out.println(indent + "-> Goal Holds");
                return Optional.of(new ArrayList<>(currentPlan)); // Return copy
            }

            visitedGoals.add(goalId);
            List<PotentialPlanStep> potentialSteps = gatherPotentialPlanSteps(currentGoal);
            potentialSteps.sort(Comparator.<PotentialPlanStep, Double>comparing(s -> s.confidence).reversed()); // Best first

            for (PotentialPlanStep step : potentialSteps) {
                System.out.println(indent + "-> Trying Action: " + step.action.id + " (Conf: " + String.format("%.3f", step.confidence) + ")");

                // Create new visited set for this branch to allow revisiting goals in different plan contexts
                Set<String> branchVisited = new HashSet<>(visitedGoals);

                // Try to satisfy preconditions recursively
                Optional<List<Atom>> planWithPreconditionsOpt = satisfyPreconditions(
                        step.preconditions, currentPlan, planDepthRemaining - 1, searchDepth, branchVisited);

                if (planWithPreconditionsOpt.isPresent()) {
                     List<Atom> planFound = planWithPreconditionsOpt.get();
                     planFound.add(step.action); // Add the current action *after* preconditions
                     System.out.println(indent + "--> Plan Step Found: " + step.action.id);
                     return Optional.of(planFound); // Found a complete plan for this branch
                }
            }

            visitedGoals.remove(goalId); // Backtrack
             System.out.println(indent + "-> No plan found from here");
            return Optional.empty();
        }

         // Gathers actions that might achieve the currentGoal
         private List<PotentialPlanStep> gatherPotentialPlanSteps(Atom currentGoal) {
            List<PotentialPlanStep> steps = new ArrayList<>();
            String goalId = currentGoal.id;

             // 1. Check INITIATES links: Action -> Initiates(Goal)
             // Assumes goal is a Fluent Node
             if (currentGoal instanceof Node) {
                  kb.getLinksByType(Link.LinkType.INITIATES)
                    .filter(link -> link.targets.size() >= 2 && link.targets.get(1).equals(goalId)) // Target 1 is the fluent
                    .forEach(initiatesLink -> {
                        kb.getAtom(initiatesLink.targets.get(0)).ifPresent(actionAtom -> { // Target 0 is the action
                             // Simple Case: No explicit preconditions in INITIATES link itself
                             // Real planner needs to query Can(Action) or specific preconditions
                             Atom canPredicate = kb.getOrCreateNode("Predicate_Can", TruthValue.TRUE, 0.1);
                             Atom canActionLink = new Link(Link.LinkType.EVALUATION, List.of(canPredicate.id, actionAtom.id), TruthValue.UNKNOWN, null);
                             steps.add(new PotentialPlanStep(actionAtom, List.of(canActionLink), initiatesLink.tv.getConfidence()));
                        });
                    });
             }

             // 2. Check PREDICTIVE_IMPLICATION links: Premise -> Goal
             kb.getLinksByType(Link.LinkType.PREDICTIVE_IMPLICATION)
               .filter(link -> link.targets.size() == 2 && link.targets.get(1).equals(goalId))
               .forEach(predLink -> {
                   kb.getAtom(predLink.targets.get(0)).ifPresent(premiseAtom -> {
                       // a) Premise is directly an Action
                       if (isActionAtom(premiseAtom)) {
                           Atom canPredicate = kb.getOrCreateNode("Predicate_Can", TruthValue.TRUE, 0.1);
                           Atom canActionLink = new Link(Link.LinkType.EVALUATION, List.of(canPredicate.id, premiseAtom.id), TruthValue.UNKNOWN, null);
                           steps.add(new PotentialPlanStep(premiseAtom, List.of(canActionLink), predLink.tv.getConfidence()));
                       }
                       // b) Premise is a Sequence containing an Action: Seq(PreState, Action) -> Goal
                       else if (premiseAtom instanceof Link && ((Link)premiseAtom).type == Link.LinkType.SEQUENCE && premiseAtom.targets.size() >= 2) {
                           Link sequenceLink = (Link) premiseAtom;
                           Optional<Atom> actionOpt = sequenceLink.targets.stream().map(kb::getAtom).filter(Optional::isPresent).map(Optional::get).filter(this::isActionAtom).findFirst();
                           actionOpt.ifPresent(actionAtom -> {
                               List<Atom> preconditions = sequenceLink.targets.stream().filter(id -> !id.equals(actionAtom.id)).map(kb::getAtom).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
                               steps.add(new PotentialPlanStep(actionAtom, preconditions, predLink.tv.getConfidence() * sequenceLink.tv.getConfidence()));
                           });
                       }
                   });
               });

             // TODO: Add other ways actions can achieve goals (e.g., via deduction involving action effects)

            return steps;
         }

        // Helper to recursively satisfy a list of preconditions for planning
        private Optional<List<Atom>> satisfyPreconditions(List<Atom> preconditions, LinkedList<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoals) {
             if (preconditions.isEmpty()) {
                 return Optional.of(new ArrayList<>(currentPlan)); // Base case: success
             }

             Atom nextPrecondition = preconditions.get(0);
             List<Atom> remainingPreconditions = preconditions.subList(1, preconditions.size());

             // Recursively plan to achieve the next precondition
             Optional<List<Atom>> planForPreconditionOpt = planRecursive(nextPrecondition, currentPlan, planDepthRemaining, searchDepth, visitedGoals);

             if (planForPreconditionOpt.isPresent()) {
                 // Precondition achieved, update plan and recurse on remaining preconditions
                 // Note: planRecursive returns the *full* plan including currentPlan steps + new steps
                 LinkedList<Atom> updatedPlan = new LinkedList<>(planForPreconditionOpt.get());
                 return satisfyPreconditions(remainingPreconditions, updatedPlan, planDepthRemaining, searchDepth, visitedGoals);
             } else {
                 // Failed to satisfy this precondition
                 return Optional.empty();
             }
        }

         private boolean isActionAtom(Atom atom) {
             // Define how actions are represented (e.g., specific Node type, EXECUTION link)
             return atom instanceof Link && ((Link)atom).type == Link.LinkType.EXECUTION;
              // Or if using ActionNode: return atom instanceof ActionNode;
         }


        // --- Helper Classes for Inference/Planning Control ---
        private enum InferenceRuleType { DEDUCTION, INVERSION, MODUS_PONENS, FOR_ALL_INSTANTIATION, TEMPORAL_PERSISTENCE }

        private static class PotentialInference {
            final InferenceRuleType ruleType;
            final Atom[] premises; // Can be Atom or Link depending on rule
            final double importance;
            final String uniqueId; // Pre-calculated ID for visited set

            PotentialInference(InferenceRuleType type, long currentTime, Atom... premises) {
                this.ruleType = type;
                this.premises = premises;
                this.importance = Arrays.stream(premises)
                                      .mapToDouble(p -> p.getCurrentImportance(currentTime) * p.tv.getConfidence())
                                      .reduce(1.0, (a, b) -> a * b); // Product of importance * confidence
                 this.uniqueId = ruleType + ":" + Arrays.stream(premises).map(p -> p.id).sorted().collect(Collectors.joining("|"));
            }
            String getUniqueId() { return uniqueId; }
            String getUniqueIdSwapped() {
                if (premises.length == 2) { // For symmetric rules like Deduction
                    return ruleType + ":" + Stream.of(premises[1].id, premises[0].id).sorted().collect(Collectors.joining("|"));
                } return uniqueId;
            }
        }

         private static record PotentialPlanStep(Atom action, List<Atom> preconditions, double confidence) {}
    }


    // ========================================================================
    // === Nested Static Class: AgentController                           ===
    // ========================================================================
    public static class AgentController {
        private final KnowledgeBase kb;
        private final InferenceEngine inference;
        private String lastActionId = null;
        private String previousStateAtomId = null; // Composite state atom ID
        private final Random random = new Random();

        public AgentController(KnowledgeBase kb, InferenceEngine engine) {
            this.kb = kb;
            this.inference = engine;
        }

        /** Runs the main agent loop. */
        public void run(Environment env, Atom goal, int maxSteps) {
            System.out.println("--- Agent: Starting Run ---");
            System.out.println("Goal: " + goal.id + " " + goal.tv);
            kb.getAtom(goal.id).ifPresent(g -> g.updateImportance(IMPORTANCE_BOOST_ON_GOAL)); // Boost goal importance

            PerceptionResult initialPerception = env.perceive();
            previousStateAtomId = perceiveState(initialPerception.stateAtoms());

            for (int step = 0; step < maxSteps && !env.isTerminated(); step++) {
                long time = ((UltimatePLN)kb.timeProvider).incrementGlobalTime(); // Use parent's time method
                System.out.printf("\n--- Agent Step %d (Time: %d) ---%n", step + 1, time);
                System.out.println("Current State Atom: " + previousStateAtomId);
                // kb.getAtom(previousStateAtomId).ifPresent(a -> System.out.println("Current State TV: " + a.tv));

                // 1. Reason (Optional Forward Chaining)
                 inference.forwardChain(1); // Limited forward inference

                // 2. Plan & Select Action
                Optional<Atom> selectedActionOpt = selectAction(env, goal);

                if (selectedActionOpt.isEmpty()) {
                    System.out.println("Agent: No suitable action found or planned. Stopping.");
                    break;
                }
                lastActionId = selectedActionOpt.get().id;
                System.out.println("Agent: Selected Action: " + lastActionId);

                // 3. Execute Action
                ActionResult actionResult = env.executeAction(selectedActionOpt.get());

                // 4. Perceive Outcome
                String currentStateAtomId = perceiveState(actionResult.newStateAtoms());

                // 5. Learn from Experience
                learnFromExperience(currentStateAtomId, actionResult.reward());

                // Update state for next loop
                previousStateAtomId = currentStateAtomId;

                // Check if goal reached (more robust check)
                 List<QueryResult> goalQueryResults = inference.backwardChain(goal, 2); // Shallow check
                 if (!goalQueryResults.isEmpty() && goalQueryResults.stream().anyMatch(r -> r.inferredAtom().tv().strength() > 0.9 && r.inferredAtom().tv().getConfidence() > 0.7)) {
                     System.out.println("*** Agent: Goal Achieved! *** " + goal.id);
                     // Terminate or set new goal?
                     break;
                 }

                 // Slight delay for realism/observation
                 try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            System.out.println("--- Agent: Run Finished ---");
        }

        /** Processes environment perception into KB Atoms and a composite state atom. */
        private String perceiveState(Collection<Atom> perceivedAtoms) {
            List<String> presentFluentIds = new ArrayList<>();
            long currentTime = kb.timeProvider.apply(null);

            for (Atom pAtom : perceivedAtoms) {
                // Create or update the perceived atom with high confidence and current time
                TruthValue perceivedTV = TruthValue.of(pAtom.tv().strength(), DEFAULT_PERCEPTION_COUNT);
                Atom currentPercept = pAtom.withTruthValue(perceivedTV);
                if (currentPercept instanceof Link) ((Link)currentPercept).time = TimeSpec.of(currentTime);
                // Boost importance of perceived atoms
                currentPercept.shortTermImportance = 0.6; currentPercept.longTermImportance *= 0.9; // High STI, slight LTI decay if not reinforced
                Atom kbAtom = kb.addAtom(currentPercept);

                // Determine if fluent is "present" based on strength and confidence
                if (kbAtom.tv().strength() > 0.5 && kbAtom.tv().getConfidence() > 0.1) {
                    presentFluentIds.add(kbAtom.id());
                }
            }

            // Create a composite state representation (State Node)
            if (presentFluentIds.isEmpty()) {
                 return kb.getOrCreateNode("State_Empty", TruthValue.TRUE, 0.5).id();
            }
            Collections.sort(presentFluentIds);
            String stateName = "State_" + String.join("_", presentFluentIds);
            Node stateNode = kb.getOrCreateNode(stateName, TruthValue.TRUE, 0.8); // High STI for current state
            // Add HOLDS_AT links for the state node itself
             kb.addLink(Link.LinkType.HOLDS_AT, TruthValue.TRUE, 0.8, TimeSpec.of(currentTime), stateNode);

            return stateNode.id();
        }


        /** Selects an action using planning or reactive strategies. */
        private Optional<Atom> selectAction(Environment env, Atom goal) {
            List<Atom> availableActions = env.getAvailableActions();
            if (availableActions.isEmpty()) return Optional.empty();

            // 1. Try Planning
            Optional<List<Atom>> planOpt = inference.planToActionSequence(goal, DEFAULT_MAX_PLAN_DEPTH, DEFAULT_MAX_BC_DEPTH);
            if (planOpt.isPresent()) {
                for (Atom plannedAction : planOpt.get()) {
                    if (availableActions.stream().anyMatch(a -> a.id().equals(plannedAction.id()))) {
                        System.out.println("Agent: Selecting action from plan: " + plannedAction.id());
                        return Optional.of(plannedAction);
                    }
                }
                System.out.println("Agent: Plan found, but no planned actions are available.");
            } else {
                System.out.println("Agent: Planning failed.");
            }

            // 2. Fallback: Reactive Selection based on predicted Reward
            System.out.println("Agent: Falling back to reactive selection (predicted reward)...");
            Node rewardNode = kb.getOrCreateNode("Reward"); // Standard reward node
            Map<Atom, Double> actionUtilities = new ConcurrentHashMap<>(); // Use concurrent map for potential parallel evaluation

             availableActions.parallelStream().forEach(action -> {
                 Link rewardQuery = new Link(Link.LinkType.PREDICTIVE_IMPLICATION, List.of(action.id(), rewardNode.id()), TruthValue.UNKNOWN, null);
                 List<QueryResult> results = inference.backwardChain(rewardQuery, 2); // Shallow search
                 double utility = results.stream()
                                       .mapToDouble(r -> r.inferredAtom().tv().strength() * r.inferredAtom().tv().getConfidence())
                                       .max().orElse(0.0); // Max predicted utility
                  actionUtilities.put(action, utility);
             });


            Optional<Map.Entry<Atom, Double>> bestActionEntry = actionUtilities.entrySet().stream()
                .max(Map.Entry.comparingByValue());

            if (bestActionEntry.isPresent() && bestActionEntry.get().getValue() > 0.01) { // Threshold for utility
                 System.out.println("Agent: Selecting action by max utility: " + bestActionEntry.get().getKey().id() + " (Utility: " + bestActionEntry.get().getValue() + ")");
                 return Optional.of(bestActionEntry.get().getKey());
            }

            // 3. Final Fallback: Random Exploration
            System.out.println("Agent: No planned or useful reactive action. Selecting randomly.");
            return Optional.of(availableActions.get(random.nextInt(availableActions.size())));
        }

        /** Learns from the consequences of the last action. */
        private void learnFromExperience(String currentStateId, double reward) {
            if (previousStateAtomId == null || lastActionId == null) return;
            long currentTime = kb.timeProvider.apply(null);

            // 1. Learn State Transition: Seq(PreviousState, Action) -> CurrentState
            // Note: ActionID here should represent the *executed* action instance if available,
            // otherwise use the schema ID. Assume lastActionId is schema for now.
            Link sequenceLink = kb.addLink(Link.LinkType.SEQUENCE, TruthValue.TRUE, 0.8, null, previousStateAtomId, lastActionId);
            Link transitionLink = kb.addLink(Link.LinkType.PREDICTIVE_IMPLICATION,
                    TruthValue.of(1.0, DEFAULT_PERCEPTION_COUNT), 0.9, // Observed transition
                    TimeSpec.ofDuration(10), // Assume small default delay
                    sequenceLink.id(), currentStateId);
             System.out.println("  Learn: Transition " + transitionLink.id() + " " + transitionLink.tv());

            // 2. Learn Reward Association: CurrentState -> Reward
            Node rewardNode = kb.getOrCreateNode("Reward");
            TruthValue rewardTV = TruthValue.of(Math.max(0.0, Math.min(1.0, reward)), DEFAULT_PERCEPTION_COUNT * 0.5); // Normalize reward to [0,1]? Use lower count than perception.
            if (rewardTV.strength() > 0.1 || rewardTV.strength() < 0.9) { // Learn if reward is significantly non-neutral
                 Link stateRewardLink = kb.addLink(Link.LinkType.PREDICTIVE_IMPLICATION, rewardTV, 0.8, TimeSpec.IMMEDIATE, currentStateId, rewardNode.id());
                 System.out.println("  Learn: State-Reward " + stateRewardLink.id() + " " + stateRewardLink.tv());
            }

            // 3. Learn Action Utility: Action -> GoodAction (Inheritance)
            if (Math.abs(reward) > 0.1) { // Learn utility if reward is non-negligible
                Node goodActionNode = kb.getOrCreateNode("GoodAction");
                TruthValue utilityEvidenceTV = TruthValue.of(reward > 0 ? 1.0 : 0.0, DEFAULT_ACTION_UTILITY_COUNT); // Binary good/bad evidence
                Link utilityLink = kb.addLink(Link.LinkType.INHERITANCE, utilityEvidenceTV, 0.7, lastActionId, goodActionNode.id());
                System.out.println("  Learn: Action-Utility " + utilityLink.id() + " " + utilityLink.tv());
            }
        }
    }

    // ========================================================================
    // === Core Data Structures                                           ===
    // ========================================================================

    /** Base class for Atoms with common importance/access logic. */
    public abstract static class Atom {
        private final String id;
        private TruthValue tv;
        protected volatile double shortTermImportance; // STI
        protected volatile double longTermImportance;  // LTI
        protected volatile long lastAccessTime;

        protected Atom(String id, TruthValue tv, double initialSTI, double initialLTI) {
            this.id = Objects.requireNonNull(id);
            this.tv = Objects.requireNonNull(tv);
            this.shortTermImportance = Math.max(0.0, initialSTI);
            this.longTermImportance = Math.max(0.0, initialLTI);
            this.lastAccessTime = 0; // Set by KB on add/access
        }

        public String id() { return id; }
        public TruthValue tv() { return tv; }

        /** Updates importance based on a boost event (e.g., access, inference use). */
        public synchronized void updateImportance(double boost) {
            boost = Math.max(0, boost); // Ensure boost is non-negative
            this.shortTermImportance = Math.min(1.0, this.shortTermImportance + boost);
            this.longTermImportance = Math.min(1.0, this.longTermImportance * (1.0 - LTI_UPDATE_RATE) + this.shortTermImportance * LTI_UPDATE_RATE);
        }

        /** Decays importance over time (called periodically). */
        public synchronized void decayImportance() {
            this.shortTermImportance *= (1.0 - STI_DECAY_RATE);
            this.longTermImportance *= (1.0 - LTI_DECAY_RATE);
        }

        /** Updates the last access time. */
        public void updateAccessTime(long time) {
            this.lastAccessTime = time;
        }

        /** Calculates a combined importance score for sorting/pruning. */
        public double getCurrentImportance(long currentTime) {
             double timeSinceAccess = Math.max(0, currentTime - lastAccessTime);
             // Recency factor decays exponentially based on time since last access
             double recencyFactor = Math.exp(-timeSinceAccess / (FORGETTING_CYCLE_INTERVAL_MS * 5.0)); // Decay over ~5 cycles
             // Combine STI, LTI, and recency
             return (shortTermImportance * 0.4 + longTermImportance * 0.5 + recencyFactor * 0.1) * tv.getConfidence(); // Weighted sum, scaled by confidence
        }

        /** Abstract method for creating a copy with a different TruthValue. */
        public abstract Atom withTruthValue(TruthValue newTV);

        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; return id.equals(((Atom) o).id); }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public String toString() { return String.format("%s %s <S=%.2f L=%.2f>", id, tv, shortTermImportance, longTermImportance); }
    }

    /** Node Atom representing concepts, entities, etc. */
    public static class Node extends Atom {
        public final String name;

        public Node(String name, TruthValue tv) {
            super(generateIdFromName(name), tv, 0.1, 0.01);
            this.name = name;
        }
        public static String generateIdFromName(String name) { return "N(" + name + ")"; }

        @Override public Atom withTruthValue(TruthValue newTV) {
             Node newNode = new Node(this.name, newTV);
             newNode.shortTermImportance = this.shortTermImportance; newNode.longTermImportance = this.longTermImportance; newNode.lastAccessTime = this.lastAccessTime;
             return newNode;
        }
        @Override public String toString() { return String.format("%s %s <S=%.2f L=%.2f>", name, tv, shortTermImportance, longTermImportance); }
    }

    /** Variable Node Atom for HOL. */
    public static class VariableNode extends Node {
        public VariableNode(String name) {
            super(VARIABLE_PREFIX + name, TruthValue.UNKNOWN);
            this.shortTermImportance = 0; this.longTermImportance = 1.0; // Variables are structurally important
        }
        public static String generateIdFromName(String name) { return "V(" + name + ")"; }

        @Override public Atom withTruthValue(TruthValue newTV) { return new VariableNode(this.name.substring(VARIABLE_PREFIX.length())); }
        @Override public String toString() { return String.format("%s <Var>", name); }
    }

    /** Link Atom representing relationships. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets;
        public TimeSpec time; // Optional

        public Link(LinkType type, List<String> targets, TruthValue tv, TimeSpec time) {
            super(generateId(type, targets), tv, 0.1, 0.01);
            this.type = Objects.requireNonNull(type);
            this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
            this.time = time;
        }

        public static String generateId(LinkType type, List<String> targets) {
            List<String> idTargets = type.isOrderIndependent ? targets.stream().sorted().collect(Collectors.toList()) : targets;
            return type + idTargets.toString().replace(" ", ""); // Compact ID
        }

        @Override public Atom withTruthValue(TruthValue newTV) {
             Link newLink = new Link(this.type, this.targets, newTV, this.time);
             newLink.shortTermImportance = this.shortTermImportance; newLink.longTermImportance = this.longTermImportance; newLink.lastAccessTime = this.lastAccessTime;
             return newLink;
        }
        @Override public String toString() {
            String timeStr = (time != null) ? " " + time : "";
            return String.format("%s%s%s %s <S=%.2f L=%.2f>", type, targets, timeStr, tv, shortTermImportance, longTermImportance);
        }

        public enum LinkType {
            // Core
            INHERITANCE(false), SIMILARITY(true), EQUIVALENCE(true),
            EVALUATION(false), EXECUTION(false), MEMBER(false), INSTANCE(false),
            // Logical
            AND(true), OR(true), NOT(false),
            // Temporal
            SEQUENCE(false), SIMULTANEOUS_AND(true), SIMULTANEOUS_OR(true),
            PREDICTIVE_IMPLICATION(false), EVENTUAL_PREDICTIVE_IMPLICATION(false),
            HOLDS_AT(false), HOLDS_THROUGHOUT(false), INITIATES(false), TERMINATES(false),
            // HOL
            FOR_ALL(false), EXISTS(false);

            public final boolean isOrderIndependent;
            LinkType(boolean orderIndependent) { this.isOrderIndependent = orderIndependent; }
        }
    }

    /** Immutable TruthValue record. */
    public static record TruthValue(double strength, double count) {
        public static final TruthValue DEFAULT = new TruthValue(0.5, 0.0);
        public static final TruthValue TRUE = new TruthValue(1.0, 10.0);
        public static final TruthValue FALSE = new TruthValue(0.0, 10.0);
        public static final TruthValue UNKNOWN = new TruthValue(0.5, 0.1);

        // Canonical constructor for validation/normalization
        public TruthValue {
            strength = Math.max(0.0, Math.min(1.0, strength));
            count = Math.max(0.0, count);
        }
        // Factory method for convenience
        public static TruthValue of(double strength, double count) { return new TruthValue(strength, count); }

        public double getConfidence() { return count / (count + DEFAULT_EVIDENCE_SENSITIVITY); }

        public TruthValue merge(TruthValue other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;
            double totalCount = this.count + other.count;
            double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new TruthValue(mergedStrength, totalCount);
        }
        @Override public String toString() { return String.format("<s=%.3f, c=%.2f, w=%.3f>", strength, count, getConfidence()); }
    }

    /** Immutable TimeSpec record. */
    public static record TimeSpec(long startTime, long endTime) {
        public static final TimeSpec IMMEDIATE = new TimeSpec(0, 0); // Relative, zero duration

        // Canonical constructor for validation
        public TimeSpec { if (endTime < startTime) throw new IllegalArgumentException("End time must be >= start time"); }

        public static TimeSpec of(long timePoint) { return new TimeSpec(timePoint, timePoint); }
        public static TimeSpec of(long start, long end) { return new TimeSpec(start, end); }
        public static TimeSpec ofDuration(long duration) { return new TimeSpec(0, Math.max(0, duration)); } // Relative duration

        public boolean isPoint() { return startTime == endTime; }
        public long getDuration() { return endTime - startTime; }

        public static TimeSpec add(TimeSpec t1, TimeSpec t2) { // Add relative durations/delays
            if (t1 == null) return t2; if (t2 == null) return t1;
            return ofDuration(t1.getDuration() + t2.getDuration());
        }
        public TimeSpec invert() { return ofDuration(-this.getDuration()); } // Invert relative duration

        public static TimeSpec merge(TimeSpec t1, TimeSpec t2) { // Combine evidence intervals?
             if (t1 == null) return t2; if (t2 == null) return t1;
             // Simplistic: return interval covering both
              return of(Math.min(t1.startTime, t2.startTime), Math.max(t1.endTime, t2.endTime));
         }

        @Override public String toString() { return isPoint() ? String.format("T=%d", startTime) : String.format("T=[%d,%d]", startTime, endTime); }
    }

    /** Record for Backward Chaining results. */
    public static record QueryResult(Map<String, String> bind, Atom inferredAtom) {}


    // ========================================================================
    // === Interfaces for Environment Interaction                         ===
    // ========================================================================
    public interface Environment {
        PerceptionResult perceive();
        List<Atom> getAvailableActions(); // Return action *schema* atoms
        ActionResult executeAction(Atom actionAtom); // Execute action *schema* atom
        boolean isTerminated();
    }
    public static record PerceptionResult(Collection<Atom> stateAtoms) {}
    public static record ActionResult(Collection<Atom> newStateAtoms, double reward) {}

    // --- BasicGridWorld Example Implementation (moved inside for single file) ---
    static class BasicGridWorld implements Environment {
         private final UltimatePLN pln;
         private int agentX = 0, agentY = 0;
         private final int goalX = 2, goalY = 2, worldSize = 4, maxSteps = 15;
         private int steps = 0;
         private final Atom moveN, moveS, moveE, moveW, agentAtPred; // Action schemas

         BasicGridWorld(UltimatePLN pln) {
             this.pln = pln;
             // Create action schemas (Nodes in this example for simplicity)
             moveN = pln.getOrCreateNode("Action_MoveNorth"); moveS = pln.getOrCreateNode("Action_MoveSouth");
             moveE = pln.getOrCreateNode("Action_MoveEast"); moveW = pln.getOrCreateNode("Action_MoveWest");
             agentAtPred = pln.getOrCreateNode("Predicate_AtLocation");
         }

         @Override public PerceptionResult perceive() {
             Node agentPosNode = pln.getOrCreateNode(String.format("Pos_%d_%d", agentX, agentY));
             // Represent state as HoldsAt(AtLocation(Agent, Pos_X_Y), Time)
             Atom agentAtLink = pln.addLink(Link.LinkType.HOLDS_AT, TruthValue.TRUE, 1.0,
                                            TimeSpec.of(pln.getGlobalTime()), agentAtPred, pln.getOrCreateNode("Agent"), agentPosNode);
             return new PerceptionResult(List.of(agentAtLink));
         }
         @Override public List<Atom> getAvailableActions() {
             List<Atom> actions = new ArrayList<>();
             if (agentY < worldSize - 1) actions.add(moveN); if (agentY > 0) actions.add(moveS);
             if (agentX < worldSize - 1) actions.add(moveE); if (agentX > 0) actions.add(moveW);
             return actions;
         }
         @Override public ActionResult executeAction(Atom actionAtom) {
             steps++; double reward = -0.1; // Move cost
             switch (actionAtom.id()) { // Assumes actionAtom is the Node schema
                 case "N(Action_MoveNorth)": if (agentY < worldSize - 1) agentY++; break;
                 case "N(Action_MoveSouth)": if (agentY > 0) agentY--; break;
                 case "N(Action_MoveEast)":  if (agentX < worldSize - 1) agentX++; break;
                 case "N(Action_MoveWest)":  if (agentX > 0) agentX--; break;
             }
             if (agentX == goalX && agentY == goalY) { reward = 10.0; }
             return new ActionResult(perceive().stateAtoms(), reward);
         }
         @Override public boolean isTerminated() { return steps >= maxSteps || (agentX == goalX && agentY == goalY); }
     }

} // End of UltimatePLN class
```

**Explanation of Synthesis Choices and Improvements:**

1.  **Architecture:** Adopted F's structure with nested static classes (`KnowledgeBase`, `InferenceEngine`, `AgentController`) inside the main `UltimatePLN` facade. This provides logical separation and better organization within the single-file constraint.
2.  **KB & Indexing:** Uses `ConcurrentHashMap` for atoms and `ConcurrentSkipListSet` for indices (Type, Target). `KnowledgeBase` encapsulates storage, retrieval, indexing, and forgetting.
3.  **Data Records:** `TruthValue`, `TimeSpec`, `QueryResult`, `PerceptionResult`, `ActionResult` are implemented as immutable Java `record`s for conciseness and safety.
4.  **Atom Importance:** `Atom` base class has `volatile double shortTermImportance` and `longTermImportance`. `updateImportance` and `decayImportance` methods provide clear logic. `getCurrentImportance` includes a recency factor.
5.  **Forgetting:** `KnowledgeBase.forgetLowImportanceAtoms` is triggered periodically *and* by size checks. It sorts by `getCurrentImportance`, respects thresholds, and includes basic protection for critical nodes.
6.  **Inference Engine:**
    *   Implements Deduction and Inversion using the full formulas requiring term probabilities (node strengths).
    *   Implements Modus Ponens that correctly revises the conclusion atom's `TruthValue`.
    *   `forwardChain` uses a `PriorityQueue<PotentialInference>` based on importance, processing in batches.
    *   `backwardChain` (query) integrates unify (`unify`, `substitute` - basic implementation) and returns `QueryResult` records. It includes backward application logic sketches for Deduction, Inversion, MP, and ForAll.
7.  **Planning:** `planToActionSequence` uses a recursive helper (`planRecursive`) leveraging BC. It gathers `PotentialPlanStep`s (considering `INITIATES` and `PREDICTIVE_IMPLICATION`), sorts them, and recursively tries to satisfy preconditions.
8.  **Temporal Logic:** `TimeSpec` record is implemented for points, intervals, and durations. Temporal link types are included. `PREDICTIVE_IMPLICATION` links can carry `TimeSpec`. Time is considered in temporal inference (basic `add`). `HOLDS_AT` links are used in perception/state representation.
9.  **Higher-Order Logic:** `VariableNode` is defined. Basic `unify` and `substitute` methods are included in `InferenceEngine`. BC includes sketches for handling `ForAll` instantiation.
10. **Agent Control:** `AgentController` encapsulates the loop. `perceiveState` creates a composite State Node. `selectAction` prioritizes planning, falls back to reactive utility (based on learned `Action->Reward` links), then random exploration. `learnFromExperience` learns `Seq(State,Action)->NextState` and reward links.
11. **Environment API:** Clean interfaces using records for results. A `BasicGridWorld` example is included within the class.
12. **Global Time:** A simple `AtomicLong` managed by the main class provides a time source for `KnowledgeBase` and potentially the `AgentController`/`Environment`.
13. **Error Handling/Logging:** Basic `System.out` and `System.err` messages are included for tracing and warnings. `Optional` is used for methods that might not return a value.
14. **Concurrency:** Uses concurrent collections and `synchronized` blocks where necessary (e.g., in `KnowledgeBase` modification methods). Importance fields are `volatile`.

This `UltimatePLN` represents a significant step towards a feature-rich, robust, and extensible PLN system within a single Java class structure. It addresses the core requirements by synthesizing the best design patterns and features observed in the provided examples.