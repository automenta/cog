import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
* Enhanced Probabilistic Logic Network (PLN) System.
*
* This class provides a comprehensive, unified implementation of core PLN concepts,
* incorporating advanced features like attention/forgetting for memory management,
* a flexible perception/action API, enhanced planning via backward chaining,
* foundational temporal and causal logic based on Event Calculus principles,
* basic higher-order logic elements (variables), and considerations for scalability.
*
* GOALS:
* - Importance: Implements attention/forgetting to run indefinitely without OOM.
* - Perception/Action: Provides a developer-friendly API for environment interfacing.
* - Planning: Enhances action selection with backward chaining and goal-seeking.
* - Temporal Logic: Incorporates core Event Calculus concepts (initiate, terminate, holds) and PLN temporal links.
* - Higher-Order Logic: Adds basic variable support.
* - Scalability: Uses indexing and importance heuristics to mitigate exhaustive search.
*
* DESIGN:
* - Unified: Single class consolidating core logic, agent capabilities, and advanced features.
* - Atom-Centric KB: Knowledge graph using `Atom` (Node, Link) primitives.
* - Probabilistic TV: `TruthValue` stores strength (belief) and count (evidence amount).
* - Core Inference: Deduction, Inversion, Modus Ponens, plus Temporal variants.
* - Attention/Forgetting: Periodic pruning based on Atom importance (confidence, recency).
* - Environment API: `processPercepts`, `selectAction`, `processFeedback` methods for interaction.
* - Temporal Reasoning: Specialized LinkTypes (`SEQUENCE`, `PREDICTIVE_IMPLICATION`, etc.) and predicates (`Initiate`, `Terminate`, `Holds`).
* - Planning via BC: `backwardChain` used to find action sequences satisfying goals.
* - Extensible: Modular structure allows adding new inference rules, link types, etc.
* - Self-Documenting: Clear naming and structure; Javadoc explains key components.
    */
    public class PLNSystem {

// --- Configuration Constants ---

/** Default evidence sensitivity parameter (k in confidence = count / (count + k)). */
public static final double DEFAULT_EVIDENCE_SENSITIVITY = 1.0;
/** Confidence discount factor for standard inference steps (excluding revision). */
public static final double INFERENCE_CONFIDENCE_DISCOUNT_FACTOR = 0.9;
/** Confidence discount factor specific to temporal inference propagation. */
public static final double TEMPORAL_INFERENCE_DISCOUNT_FACTOR = 0.8;
/** Minimum confidence threshold for an Atom to be considered significant or retained during pruning. */
public static final double MIN_CONFIDENCE_THRESHOLD = 0.01;
/** Minimum strength threshold for an Atom to be considered significant. */
public static final double MIN_STRENGTH_THRESHOLD = 0.01;
/** Maximum number of Atoms allowed in the knowledge base before pruning is triggered. */
public static final int MAX_KB_SIZE = 10000;
/** Fraction of Atoms to remove during pruning when MAX_KB_SIZE is exceeded. */
public static final double PRUNING_FRACTION = 0.2;
/** Time decay factor for Atom importance (applied during pruning). Lower values mean faster decay. */
public static final double IMPORTANCE_TIME_DECAY_FACTOR = 0.995;
/** Maximum depth for backward chaining searches. */
public static final int MAX_BC_DEPTH = 5;
/** Number of forward chaining steps to run after learning from experience. */
public static final int FC_STEPS_AFTER_LEARNING = 1;
/** Default strength assigned to newly perceived facts. */
public static final double PERCEPTION_STRENGTH = 1.0;
/** Default count assigned to newly perceived facts. */
public static final double PERCEPTION_COUNT = 10.0;
/** Default count assigned to reward observations. */
public static final double REWARD_COUNT = 5.0;
/** Default count for inferred action utility links. */
public static final double ACTION_UTILITY_COUNT = 1.0;
/** Prefix for Goal nodes. */
public static final String GOAL_PREFIX = "Goal_";
/** Prefix for State nodes/links. */
public static final String STATE_PREFIX = "State_";
/** Prefix for Action nodes. */
public static final String ACTION_PREFIX = "Action_";
/** Name of the general reward concept node. */
public static final String REWARD_NODE_NAME = "Reward";
/** Name of the concept representing beneficial actions. */
public static final String GOOD_ACTION_NODE_NAME = "GoodAction";
/** Predicate name for initiation events. */
public static final String INITIATE_PREDICATE_NAME = "Initiate";
/** Predicate name for termination events. */
public static final String TERMINATE_PREDICATE_NAME = "Terminate";
/** Predicate name for holding fluents. */
public static final String HOLDS_PREDICATE_NAME = "Holds";
/** Predicate name indicating an action was attempted. */
public static final String TRY_PREDICATE_NAME = "Try";
/** Predicate name indicating an action was completed. */
public static final String DONE_PREDICATE_NAME = "Done";
/** Predicate name indicating preconditions for an action are met. */
public static final String CAN_PREDICATE_NAME = "Can";

// --- Core Knowledge Base & State ---

private final ConcurrentMap<String, Atom> knowledgeBase = new ConcurrentHashMap<>(MAX_KB_SIZE * 2);
// --- Indexes for Scalability ---
/** Maps LinkType to Set of Link IDs of that type. */
private final ConcurrentMap<LinkType, ConcurrentSkipListSet<String>> typeIndex = new ConcurrentHashMap<>();
/** Maps Atom ID to Set of Link IDs where the Atom is a target. */
private final ConcurrentMap<String, ConcurrentSkipListSet<String>> targetIndex = new ConcurrentHashMap<>();
private final AtomicLong globalTimeStep = new AtomicLong(0);
private final Random random = new Random();

// --- Main Execution / Example ---

public static void main(String[] args) {
PLNSystem pln = new PLNSystem();
pln.runTemporalFetchExample();
pln.demonstrateForgetting();
}

/**
    * Runs an example demonstrating temporal reasoning and learning in a simulated environment.
    * Uses a simplified "fetch" scenario based on the provided text.
      */
      public void runTemporalFetchExample() {
      System.out.println("\n--- Running Temporal Fetch Example ---");

      // Setup initial concepts
      getOrCreateNode("Ball");
      getOrCreateNode("Teacher");
      getOrCreateNode(REWARD_NODE_NAME);
      getOrCreateNode(GOOD_ACTION_NODE_NAME);

      // Add primitive actions (as nodes)
      ActionNode goToBallAction = createActionNode("GoToBall");
      ActionNode liftBallAction = createActionNode("LiftBall");
      ActionNode goToTeacherAction = createActionNode("GoToTeacher");
      ActionNode dropBallAction = createActionNode("DropBall");
      List<ActionNode> availableActions = Arrays.asList(goToBallAction, liftBallAction, goToTeacherAction, dropBallAction);

      // Define Goal: Have the ball dropped near the teacher (simplified representation)
      Node ballDroppedNearTeacher = getOrCreateNode(STATE_PREFIX + "BallDroppedNearTeacher");
      addAtom(new GoalNode(ballDroppedNearTeacher.id, new TruthValue(1.0, 100.0)));
      String goalId = GOAL_PREFIX + ballDroppedNearTeacher.id;

      // Simulate interaction loop (highly simplified)
      String currentStateId = getOrCreateNode(STATE_PREFIX + "Initial").id; // Start state
      Map<String, Object> currentPercepts = new HashMap<>();
      currentPercepts.put("InitialState", true);

      int maxSteps = 15;
      boolean goalAchieved = false;
      System.out.println("Goal: " + goalId);

      // Inject axiom: try(X) & can(X) => done(X) (PredictiveImplication)
      // Using variables requires a more complex setup, simplified here:
      // Assume 'can(X)' is implicitly true for this example if not contradicted.
      // Axiom: try(Action) => done(Action) [Predictive]
      // Representing Try(GoToBall) -> Done(GoToBall) etc. needs explicit setup or HOL rules.

      // Pre-load some plausible (but uncertain) predictive knowledge that the agent might learn
      addPredictiveImplication(
      createEvaluationLink(TRY_PREDICATE_NAME, goToBallAction).id,
      createEvaluationLink(HOLDS_PREDICATE_NAME, getOrCreateNode("NearBall")).id,
      new TruthValue(0.7, 2.0) // Maybe works
      );
      addPredictiveImplication(
      createEvaluationLink(HOLDS_PREDICATE_NAME, getOrCreateNode("NearBall")).id, // If NearBall
      createEvaluationLink(TRY_PREDICATE_NAME, liftBallAction).id,                // And Try(LiftBall)
      createEvaluationLink(HOLDS_PREDICATE_NAME, getOrCreateNode("HoldingBall")).id,
      new TruthValue(0.8, 2.0) // Lifting usually works if near
      );
      addPredictiveImplication(
      createEvaluationLink(HOLDS_PREDICATE_NAME, getOrCreateNode("HoldingBall")).id, // If HoldingBall
      createEvaluationLink(TRY_PREDICATE_NAME, goToTeacherAction).id,             // And Try(GoToTeacher)
      createEvaluationLink(HOLDS_PREDICATE_NAME, getOrCreateNode("NearTeacher")).id,
      new TruthValue(0.9, 2.0) // Navigation reliable
      );
      addPredictiveImplication(
      createEvaluationLink(HOLDS_PREDICATE_NAME, getOrCreateNode("NearTeacher")).id,  // If NearTeacher & HoldingBall (implicit via sequence)
      createEvaluationLink(TRY_PREDICATE_NAME, dropBallAction).id,                // And Try(DropBall)
      ballDroppedNearTeacher.id,                                                    // Then BallDroppedNearTeacher
      new TruthValue(0.85, 2.0) // Dropping works
      );


        System.out.println("\n--- Simulating Agent Steps ---");
        for (int step = 1; step <= maxSteps && !goalAchieved; step++) {
            globalTimeStep.incrementAndGet();
            System.out.println("\n--- Step " + step + " (Time: " + globalTimeStep.get() + ") ---");
            System.out.println("Current State Percepts: " + currentPercepts);
            currentStateId = processPercepts(currentPercepts); // Perceive and represent state
            System.out.println("Current State Atom: " + getAtom(currentStateId));

            // Select action using planning (backward chaining)
            System.out.println("Planning towards goal: " + goalId);
            List<String> actionPlanIds = plan(goalId, currentStateId, MAX_BC_DEPTH);

            if (actionPlanIds != null && !actionPlanIds.isEmpty()) {
                String selectedActionId = actionPlanIds.get(0); // Execute first action in plan
                System.out.println("Selected Action: " + getAtom(selectedActionId));

                // Simulate action execution & outcome
                Map<String, Object> nextPercepts = new HashMap<>();
                double reward = 0.0;

                // Highly simplified world simulation logic based on action
                String actionName = ((Node)getAtom(selectedActionId)).name;
                if (actionName.contains("GoToBall")) nextPercepts.put("NearBall", true);
                else if (actionName.contains("LiftBall") && currentPercepts.containsKey("NearBall")) nextPercepts.put("HoldingBall", true);
                else if (actionName.contains("GoToTeacher") && currentPercepts.containsKey("HoldingBall")) nextPercepts.put("NearTeacher", true);
                else if (actionName.contains("DropBall") && currentPercepts.containsKey("NearTeacher")) {
                     nextPercepts.put("BallDroppedNearTeacher", true);
                     reward = 1.0; // GOAL!
                     goalAchieved = true;
                } else { // Action failed or NOP
                     nextPercepts.putAll(currentPercepts); // State mostly unchanged
                }


                // Learn from outcome
                String outcomeStateId = processFeedback(selectedActionId, reward, nextPercepts);

                currentPercepts = nextPercepts; // Update world state for next iteration
                System.out.println("Outcome State: " + getAtom(outcomeStateId) + ", Reward: " + reward);
                 if(goalAchieved) System.out.println(">>> Goal Achieved! <<<");

            } else {
                System.out.println("Planning failed or no path found. Taking random action.");
                // Simplified: Just stop if planning fails in this example
                break;
            }
            // Perform some forward inference to consolidate knowledge
            forwardChain(FC_STEPS_AFTER_LEARNING);
        }
        if (!goalAchieved) {
             System.out.println("--- Goal Not Achieved within " + maxSteps + " steps ---");
        }
        System.out.println("--- Temporal Fetch Example Finished ---");
        System.out.println("Final KB Size: " + knowledgeBase.size());

        // Example Query: What is the inferred relation between GoToBall and NearBall?
        String queryLinkId = new Link(LinkType.PREDICTIVE_IMPLICATION,
                                      Arrays.asList(createEvaluationLink(TRY_PREDICATE_NAME, goToBallAction).id,
                                                    createEvaluationLink(HOLDS_PREDICATE_NAME, getOrCreateNode("NearBall")).id),
                                      TruthValue.DEFAULT).id;
        System.out.println("Query Result (Try(GoToBall) -> Holds(NearBall)): " + getAtom(queryLinkId));
         // Example Query: Check confidence in final goal state after achieving it
         System.out.println("Query Result (Goal State): " + getAtom(ballDroppedNearTeacher.id));

    }

     /** Demonstrates the forgetting mechanism by adding atoms and pruning. */
     public void demonstrateForgetting() {
         System.out.println("\n--- Demonstrating Forgetting ---");
         int initialSize = knowledgeBase.size();
         System.out.println("Initial KB Size: " + initialSize);

         int atomsToAdd = MAX_KB_SIZE * 2; // Add more atoms than the limit
         for (int i = 0; i < atomsToAdd; i++) {
             // Add atoms with varying initial importance (via count)
             double count = random.nextDouble() * 10.0;
             Node n = new Node("TempNode_" + i, new TruthValue(random.nextDouble(), count));
             addAtom(n);
             // Simulate access to roughly half of them
             if (i % 2 == 0) {
                 Atom accessed = getAtom(n.id); // This updates lastAccessedTime
                 if(accessed != null) accessed.incrementImportance(); // Boost importance slightly on access
             }
         }

         System.out.println("KB Size after adding " + atomsToAdd + " atoms: " + knowledgeBase.size());
         assert knowledgeBase.size() > MAX_KB_SIZE;

         pruneKnowledgeBase(true); // Force pruning

         System.out.println("KB Size after pruning: " + knowledgeBase.size());
         assert knowledgeBase.size() <= MAX_KB_SIZE * (1.0 - PRUNING_FRACTION + 0.1); // Allow slight overshoot

         // Show some remaining atoms (likely those accessed or with high initial count)
         System.out.println("Sample remaining atoms:");
         knowledgeBase.values().stream().limit(5).forEach(System.out::println);
         System.out.println("--- Forgetting Demonstration Finished ---");
     }

    // --- Core Knowledge Base Operations ---

    /**
     * Adds or updates an Atom in the knowledge base. Handles revision if exists.
     * Updates indices and checks if pruning is needed.
     *
     * @param atom The Atom to add/update.
     * @return The resulting Atom in the knowledge base (potentially revised).
     */
    public synchronized Atom addAtom(Atom atom) {
        if (atom == null) return null;

        Atom currentAtom = knowledgeBase.compute(atom.id, (id, existing) -> {
            if (existing == null) {
                atom.updateAccessTime(globalTimeStep.get());
                return atom;
            } else {
                TruthValue revisedTV = existing.tv.revise(atom.tv);
                if (!revisedTV.equals(existing.tv)) { // Avoid updates if TV hasn't changed meaningfully
                    existing.tv = revisedTV;
                    existing.lastAccessedTime = globalTimeStep.get(); // Revision counts as access
                    // Optionally increase importance on revision?
                    existing.baseImportance += atom.baseImportance > 0 ? atom.baseImportance : 1.0; // Accumulate importance on revision
                    // System.out.println("Revised: " + existing.id + " -> " + existing.tv);
                } else {
                   existing.updateAccessTime(globalTimeStep.get()); // Still update access time even if TV unchanged
                }
                return existing;
            }
        });

        // Update indexes
        if (currentAtom instanceof Link) {
            Link link = (Link) currentAtom;
            typeIndex.computeIfAbsent(link.type, k -> new ConcurrentSkipListSet<>()).add(link.id);
            for (String targetId : link.targets) {
                targetIndex.computeIfAbsent(targetId, k -> new ConcurrentSkipListSet<>()).add(link.id);
            }
        }

        // Check if pruning is needed
        if (knowledgeBase.size() > MAX_KB_SIZE) {
            pruneKnowledgeBase(false); // Prune if size exceeds max
        }

        return currentAtom;
    }


    /**
     * Retrieves an Atom by its ID, updating its access time and importance.
     * @param id The unique ID of the Atom.
     * @return The Atom, or null if not found.
     */
    public Atom getAtom(String id) {
        Atom atom = knowledgeBase.get(id);
        if (atom != null) {
            atom.updateAccessTime(globalTimeStep.get());
            atom.incrementImportance(); // Increment importance on access
        }
        return atom;
    }

    /** Removes an atom completely from the KB and indices. */
    private synchronized void removeAtom(String id) {
        Atom atom = knowledgeBase.remove(id);
        if (atom != null) {
            // Remove from indices
            if (atom instanceof Link) {
                Link link = (Link) atom;
                Set<String> typeSet = typeIndex.get(link.type);
                if (typeSet != null) {
                    typeSet.remove(id);
                    if (typeSet.isEmpty()) typeIndex.remove(link.type); // Clean up empty sets
                }
                for (String targetId : link.targets) {
                    Set<String> targetSet = targetIndex.get(targetId);
                    if (targetSet != null) {
                        targetSet.remove(id);
                         if (targetSet.isEmpty()) targetIndex.remove(targetId);
                    }
                }
            }
            // Also remove links *targeting* this atom? No, let pruning handle dangling refs if necessary.
        }
    }


    /** Gets a Node by name, creating it with default TV if absent. */
    public Node getOrCreateNode(String name) {
        String nodeId = Node.generateId(name);
        // Use computeIfAbsent to ensure thread-safe creation and addition
        return (Node) knowledgeBase.computeIfAbsent(nodeId, id -> {
             Node newNode = new Node(name, TruthValue.DEFAULT);
             newNode.updateAccessTime(globalTimeStep.get()); // Set initial access time
             // No need to call addAtom here, computeIfAbsent handles insertion
              // No need to update index here, Node isn't indexed separately currently
             return newNode;
         });
    }


    /** Creates and adds/revises a Link Atom. */
    public Link addLink(LinkType type, TruthValue tv, String... targetIds) {
        return (Link) addAtom(new Link(type, Arrays.asList(targetIds), tv));
    }

    /** Creates and adds/revises a Link Atom using Atom objects as targets. */
    public Link addLink(LinkType type, TruthValue tv, Atom... targets) {
        List<String> targetIds = Arrays.stream(targets).map(a -> a.id).collect(Collectors.toList());
        return (Link) addAtom(new Link(type, targetIds, tv));
    }

    /** Convenience for creating Evaluation links: Predicate(Arg1, Arg2, ...). */
    public EvaluationLink createEvaluationLink(String predicateName, Atom... args) {
         Node predicateNode = getOrCreateNode(predicateName);
         List<String> targetIds = new ArrayList<>();
         targetIds.add(predicateNode.id); // Predicate first
         Arrays.stream(args).map(a -> a.id).forEach(targetIds::add); // Then arguments
         return (EvaluationLink) addAtom(new EvaluationLink(targetIds, TruthValue.DEFAULT)); // Default TV unless specified
    }
     public EvaluationLink createEvaluationLink(String predicateName, String... argIds) {
         Node predicateNode = getOrCreateNode(predicateName);
         List<String> targetIds = new ArrayList<>();
         targetIds.add(predicateNode.id); // Predicate first
         targetIds.addAll(Arrays.asList(argIds)); // Then arguments
         return (EvaluationLink) addAtom(new EvaluationLink(targetIds, TruthValue.DEFAULT)); // Default TV unless specified
     }

    /** Convenience for creating PredictiveImplication links. */
    public Link addPredictiveImplication(String premiseId, String consequenceId, TruthValue tv) {
         // Requires premise to occur temporally BEFORE consequence for predictive nature
         // This isn't strictly enforced by link structure alone but is the semantic intent.
         return addLink(LinkType.PREDICTIVE_IMPLICATION, tv, premiseId, consequenceId);
    }

    /** Convenience for creating Action nodes. */
     public ActionNode createActionNode(String actionName) {
        return (ActionNode) addAtom(new ActionNode(actionName, TruthValue.DEFAULT));
     }


    // --- Attention and Forgetting (Memory Management) ---

    /**
     * Prunes the knowledge base by removing the least important Atoms if size exceeds limit.
     * Importance is based on confidence, recency (last access time), and base importance.
     *
     * @param force Whether to prune even if below MAX_KB_SIZE (useful for testing).
     */
    public synchronized void pruneKnowledgeBase(boolean force) {
        int currentSize = knowledgeBase.size();
        if (!force && currentSize <= MAX_KB_SIZE) {
            return; // No need to prune yet
        }

        int targetSize = (int) (MAX_KB_SIZE * (1.0 - PRUNING_FRACTION));
        int numToRemove = currentSize - targetSize;
        if (numToRemove <= 0) return; // Already at or below target

        System.out.println("INFO: Pruning KB from " + currentSize + " to ~" + targetSize + " atoms (removing " + numToRemove + ")");

        long currentTime = globalTimeStep.get();

        List<Atom> sortedAtoms = knowledgeBase.values().stream()
            .sorted(Comparator.comparingDouble(a -> a.getImportance(currentTime))) // Sort by ascending importance
            .collect(Collectors.toList());

        // Protect essential atoms (e.g., core predicates, goals?) maybe later? For now, prune purely by importance.
        int removedCount = 0;
        for (Atom atom : sortedAtoms) {
            if (removedCount >= numToRemove) break;
            // Maybe add checks here to avoid removing critical atoms (e.g., active goals, core predicates)
             // if(isProtected(atom)) continue;
             removeAtom(atom.id);
             removedCount++;
        }
         System.out.println("INFO: Pruning complete. Removed " + removedCount + " atoms.");
    }

    // --- Perception/Action API ---

    /**
     * Processes incoming sensory percepts, converting them into Atoms and adding to the KB.
     * Represents the state as a combination of true features/evaluations.
     *
     * @param percepts A map where keys are percept names and values are their readings (e.g., Double, Boolean, String).
     * @return The ID of an Atom representing the current composite state (e.g., a StateNode or AND link).
     */
    public String processPercepts(Map<String, Object> percepts) {
        List<String> activeFeatureIds = new ArrayList<>();
        TruthValue observationTV = new TruthValue(PERCEPTION_STRENGTH, PERCEPTION_COUNT);

        for (Map.Entry<String, Object> entry : percepts.entrySet()) {
            String perceptName = entry.getKey();
            Object value = entry.getValue();
            Atom featureAtom = null;

            // Simple conversion based on type - extend as needed
            if (value instanceof Boolean && (Boolean) value) {
                featureAtom = addAtom(new Node(perceptName, observationTV));
            } else if (value instanceof Double && (Double) value > 0.5) { // Threshold numeric values
                 featureAtom = addAtom(new Node(perceptName, new TruthValue((Double) value, PERCEPTION_COUNT)));
            } else if (value instanceof String) {
                 // Could represent as Node("perceptName=value") or EvaluationLink(PerceptName, ValueNode)
                 featureAtom = addAtom(new Node(perceptName + "=" + value, observationTV));
            }
             // Add more complex handling for structured objects, lists etc. if needed

             if (featureAtom != null) {
                 activeFeatureIds.add(featureAtom.id);
             }
        }

        // Create a composite state representation
        if (activeFeatureIds.isEmpty()) {
             return addAtom(new StateNode("NullState", TruthValue.DEFAULT)).id;
        }

         Collections.sort(activeFeatureIds); // Canonical ID
         String stateName = STATE_PREFIX + activeFeatureIds.stream()
             .map(id -> knowledgeBase.get(id).getShortName()) // Use a shorter representation for ID
             .collect(Collectors.joining("_AND_"));

         // Represent state as a unique Node for simplicity, or use an AND Link
          StateNode stateNode = new StateNode(stateName, observationTV);
          return addAtom(stateNode).id;

         // Alternative: AND Link Representation
         // Link stateLink = new Link(LinkType.AND, activeFeatureIds, observationTV);
         // return addAtom(stateLink).id;
    }


    /**
     * Processes feedback received after performing an action.
     * Learns associations between (prevState, action) -> outcomeState and outcomeState -> reward.
     *
     * @param lastActionId The ID of the action Atom that was just executed.
     * @param reward The numerical reward received.
     * @param outcomeStatePercepts The percepts defining the state AFTER the action.
     * @return The ID of the Atom representing the outcome state.
     */
     public String processFeedback(String lastActionId, double reward, Map<String, Object> outcomeStatePercepts) {
        String outcomeStateId = processPercepts(outcomeStatePercepts);
        Atom outcomeStateAtom = getAtom(outcomeStateId);
        Atom lastActionAtom = getAtom(lastActionId);

        if (outcomeStateAtom == null || lastActionAtom == null) {
            System.err.println("Warning: Cannot process feedback, outcome state or action atom not found.");
            return outcomeStateId; // Return ID even if atom retrieval failed for some reason
        }

        System.out.println("Learning: Action=" + lastActionAtom.getShortName() + ", OutcomeState=" + outcomeStateAtom.getShortName() + ", Reward=" + reward);

        // --- Learn State Transition: Action -> OutcomeState (Predictive) ---
        // Simple predictive link: If action 'Try(A)' resulted in 'StateS', create PredictiveImplication(Try(A), StateS)
        // More robust: Sequence(PreviousState, Try(Action)) -> OutcomeState
        // Requires storing previous state - let's assume for now planning considers current state.
        // Create link Try(Action) -> OutcomeState (This simplifies, ignoring prev state)
         Node tryPredicate = getOrCreateNode(TRY_PREDICATE_NAME);
         EvaluationLink tryActionLink = createEvaluationLink(TRY_PREDICATE_NAME, lastActionAtom);
         addPredictiveImplication(tryActionLink.id, outcomeStateId, new TruthValue(1.0, PERCEPTION_COUNT)); // Observed consequence
         System.out.println("  Learned Transition (Simplified): " + tryActionLink.getShortName() + " -> " + outcomeStateAtom.getShortName());

        // --- Learn Reward Association: OutcomeState -> Reward ---
        Node rewardNode = getOrCreateNode(REWARD_NODE_NAME);
        if (reward > 0.0) {
            // Add link: OutcomeState -> Reward
             TruthValue rewardTV = new TruthValue(Math.min(1.0, reward), REWARD_COUNT); // Cap strength at 1
             addPredictiveImplication(outcomeStateId, rewardNode.id, rewardTV);
             System.out.println("  Learned Reward Link: " + outcomeStateAtom.getShortName() + " -> " + rewardNode.getShortName() + " " + rewardTV);

            // --- Reflective Learning: Associate Action with "GoodAction" ---
             Node goodActionNode = getOrCreateNode(GOOD_ACTION_NODE_NAME);
             // If positive reward, strengthen Action -> GoodAction
             TruthValue utilityTV = new TruthValue(1.0, ACTION_UTILITY_COUNT); // Increment count slightly per positive reward
             Link utilityLink = addLink(LinkType.INHERITANCE, utilityTV, lastActionId, goodActionNode.id);
             System.out.println("  Learned Utility Link: " + lastActionAtom.getShortName() + " -> " + goodActionNode.getShortName() + " " + utilityLink.tv);

        } else {
             // If no reward (or negative), weaken OutcomeState -> Reward link
             TruthValue noRewardTV = new TruthValue(0.0, REWARD_COUNT);
             addPredictiveImplication(outcomeStateId, rewardNode.id, noRewardTV);
             System.out.println("  Learned Non-Reward Link: " + outcomeStateAtom.getShortName() + " -> " + rewardNode.getShortName() + " " + noRewardTV);

             // Optionally weaken Action -> GoodAction
             Node goodActionNode = getOrCreateNode(GOOD_ACTION_NODE_NAME);
             TruthValue nonUtilityTV = new TruthValue(0.0, ACTION_UTILITY_COUNT);
             Link utilityLink = addLink(LinkType.INHERITANCE, nonUtilityTV, lastActionId, goodActionNode.id);
             System.out.println("  Learned Non-Utility Link: " + lastActionAtom.getShortName() + " -> " + goodActionNode.getShortName() + " " + utilityLink.tv);
        }

        // Perform forward chaining to integrate learned knowledge
         forwardChain(FC_STEPS_AFTER_LEARNING);

        return outcomeStateId;
     }


    /**
     * Selects an action to achieve a given goal, based on the current state and learned knowledge.
     * Uses backward chaining (planning) to find a sequence of actions.
     *
     * @param goalId ID of the goal Atom (often a StateNode or EvaluationLink).
     * @param currentStateId ID of the Atom representing the current state.
     * @param availableActionIds List of IDs of ActionNodes currently available to execute.
     * @return The ID of the selected ActionNode Atom, or null if no plan is found or no actions available.
     */
    public String selectAction(String goalId, String currentStateId, List<String> availableActionIds) {
        if (goalId == null || currentStateId == null || availableActionIds == null || availableActionIds.isEmpty()) {
             System.err.println("Warning: Cannot select action - missing goal, state, or available actions.");
             return null;
        }
        Atom goalAtom = getAtom(goalId);
         Atom currentStateAtom = getAtom(currentStateId);
         if(goalAtom == null || currentStateAtom == null) {
              System.err.println("Warning: Cannot select action - goal or current state atom not found.");
             return null;
         }

        System.out.println("Selecting action to achieve Goal: " + goalAtom.getShortName() + " from State: " + currentStateAtom.getShortName());

        // Use planning (backward chaining) to find a path
        List<String> planActionIds = plan(goalId, currentStateId, MAX_BC_DEPTH);

        if (planActionIds != null && !planActionIds.isEmpty()) {
             // Find the first action in the plan that is actually available now
             for (String actionId : planActionIds) {
                 if (availableActionIds.contains(actionId)) {
                     System.out.println("Selected Action from Plan: " + getAtom(actionId).getShortName());
                     return actionId;
                 } else {
                     System.out.println("  Action from plan (" + getAtom(actionId).getShortName() + ") not currently available.");
                 }
             }
             System.out.println("Plan found, but no available actions match the first step(s).");
        } else {
            System.out.println("No plan found via backward chaining.");
        }

        // Fallback Strategy: Choose available action with highest "GoodAction" utility
        System.out.println("Fallback: Choosing available action based on learned utility (-> GoodAction).");
        Node goodActionNode = getOrCreateNode(GOOD_ACTION_NODE_NAME);
        String bestFallbackAction = null;
        double bestUtility = -1.0;

        for (String actionId : availableActionIds) {
            String utilityLinkId = Link.generateId(LinkType.INHERITANCE, Arrays.asList(actionId, goodActionNode.id));
            Atom utilityLink = getAtom(utilityLinkId);
            double utility = 0.0;
            if (utilityLink != null) {
                 // Utility = strength * confidence (heuristic)
                 utility = utilityLink.tv.strength * utilityLink.tv.getConfidence();
                 System.out.println("  Action: " + getAtom(actionId).getShortName() + ", Utility: " + String.format("%.3f", utility) + " " + utilityLink.tv);
            }
            if(utility > bestUtility){
                bestUtility = utility;
                bestFallbackAction = actionId;
            }
        }

        if (bestFallbackAction != null && bestUtility > 0.0) { // Only choose if some positive utility exists
             System.out.println("Selected Fallback Action (Max Utility): " + getAtom(bestFallbackAction).getShortName());
            return bestFallbackAction;
        }

        // Final Fallback: Random Exploration
        System.out.println("No plan or useful fallback. Choosing random action.");
        return availableActionIds.get(random.nextInt(availableActionIds.size()));
    }


    // --- Planning (Enhanced Backward Chaining) ---

    /**
     * Attempts to find a sequence of actions leading from a current state towards a goal state.
     * Uses backward chaining, prioritizing paths involving `PREDICTIVE_IMPLICATION` or `SEQUENCE` links.
     *
     * @param goalId The ID of the target goal Atom.
     * @param currentStateId The ID of the current state Atom (used for context, not fully implemented for path validation).
     * @param maxDepth Maximum recursion depth for the search.
     * @return A list of ActionNode IDs representing the first steps of a plan, or null/empty list if no plan found. The list is ordered, starting with the action to take first.
     */
     public List<String> plan(String goalId, String currentStateId, int maxDepth) {
        System.out.println("  Planning (BC): Target=" + getAtom(goalId) + ", MaxDepth=" + maxDepth);
        Set<String> visited = new HashSet<>();
        // Find paths (sequences of links) that conclude the goalId
        List<LinkedList<Atom>> potentialPaths = findPathsRecursive(goalId, maxDepth, visited);

        if (potentialPaths.isEmpty()) {
             System.out.println("  Planning (BC): No paths found concluding the goal.");
             return null;
        }

         // Evaluate paths - find the most promising one (e.g., highest confidence, shortest)
         // Extract the initial action sequence from the best path.
         // Simple heuristic: Choose path with highest overall confidence product, shortest length as tie-breaker.
         LinkedList<Atom> bestPath = potentialPaths.stream()
              .max(Comparator.<LinkedList<Atom>, Double>comparing(path -> calculatePathConfidence(path))
                           .thenComparing(path -> -path.size())) // Use negative size for shorter preference
              .orElse(null);

         if (bestPath == null) {
               System.out.println("  Planning (BC): Could not determine best path.");
             return null;
         }

         System.out.println("  Planning (BC): Best path found (Confidence: " + String.format("%.4f", calculatePathConfidence(bestPath)) + ", Length: "+bestPath.size()+")");
          bestPath.forEach(a -> System.out.println("    " + a));


         // Extract the sequence of action nodes from the path
         List<String> actionPlan = extractActionSequence(bestPath);
          System.out.println("  Planning (BC): Extracted Action Plan: " + actionPlan.stream().map(id -> getAtom(id).getShortName()).collect(Collectors.toList()));


         // TODO: Validate plan against currentStateId? Requires forward simulation or more stateful planning.

        return actionPlan;
     }


    /** Recursive helper for backward chaining to find paths leading to a target. */
    private List<LinkedList<Atom>> findPathsRecursive(String targetId, int depth, Set<String> visited) {
        List<LinkedList<Atom>> foundPaths = new ArrayList<>();
        Atom targetAtom = getAtom(targetId);

        if (depth <= 0 || visited.contains(targetId) || targetAtom == null) {
            // System.out.println("  ".repeat(MAX_BC_DEPTH - depth) + "BC: Stop at " + targetId + (visited.contains(targetId)?" (visited)":"") + (depth<=0?" (depth)":""));
            return foundPaths; // Stop recursion
        }
        visited.add(targetId);
         // System.out.println("  ".repeat(MAX_BC_DEPTH - depth) + "BC: Seeking " + targetId + " (Depth " + depth + ")");


        // Find rules (Links) that *conclude* the targetId
        // Prioritize rules relevant to planning: PredictiveImplication, Sequence, Inheritance (for action utility)
        Stream<Link> potentialPremiseLinks = Stream.concat(
                // 1. PredictiveImplication: Premise -> Target
                findLinksConcluding(targetId, LinkType.PREDICTIVE_IMPLICATION),
                // 2. Sequence: [..., Premise] -> Target (using SeqAND or similar structure)
                findLinksConcluding(targetId, LinkType.SEQUENCE) // Need specific rules for Sequence decomposition
                // 3. Inheritance (for utility): Action -> GoodAction (or similar utility goal)
                 // findLinksConcluding(targetId, LinkType.INHERITANCE)
                // 4. Standard Deduction: Need specific rule implementation
        ).distinct(); // Avoid processing the same link twice if found via multiple indexes


        potentialPremiseLinks.forEach(premiseLink -> {
            if (premiseLink.tv.getConfidence() < MIN_CONFIDENCE_THRESHOLD || premiseLink.tv.strength < MIN_STRENGTH_THRESHOLD) return; // Skip low-confidence links

            // System.out.println("  ".repeat(MAX_BC_DEPTH - depth + 1) + "BC: Considering Rule: " + premiseLink);

            // --- Handle PredictiveImplication: Premise -> Target ---
             if (premiseLink.type == LinkType.PREDICTIVE_IMPLICATION && premiseLink.targets.size() == 2 && premiseLink.targets.get(1).equals(targetId)) {
                String premiseId = premiseLink.targets.get(0);
                 List<LinkedList<Atom>> subPaths = findPathsRecursive(premiseId, depth - 1, new HashSet<>(visited)); // Use copy of visited for branching
                 for (LinkedList<Atom> subPath : subPaths) {
                     subPath.addFirst(premiseLink); // Add the rule used
                     foundPaths.add(subPath);
                 }
                 // If premise itself is a directly achievable state/action (base case)
                 Atom premiseAtom = getAtom(premiseId);
                 if (subPaths.isEmpty() && premiseAtom != null) {
                     // Check if premise is an ActionNode or a state easily achieved
                     // Simplification: if it's just a node, consider it a potential starting point for this branch
                     if (premiseAtom instanceof Node) { // || could be a simple state
                         LinkedList<Atom> basePath = new LinkedList<>();
                         basePath.add(premiseLink); // The rule
                         basePath.add(premiseAtom); // The premise node (might be an action)
                         foundPaths.add(basePath);
                     }
                 }
            }
            // --- Handle Sequence (Placeholder) ---
            // Requires parsing the Sequence structure and finding precursors.
            // Example: Sequence(A, B, C). To achieve C, need to achieve Sequence(A, B). Recurse on that.
             else if (premiseLink.type == LinkType.SEQUENCE) {
                 // Implementation depends heavily on how sequences are structured (e.g., nested pairs, list)
                 // Simplified: Assume Sequence(Premise, Target) structure for now
                  if (premiseLink.targets.size() == 2 && premiseLink.targets.get(1).equals(targetId)) {
                     String sequencePremiseId = premiseLink.targets.get(0);
                     // Recurse... (similar logic to PredictiveImplication)
                  }
             }
             // --- Handle other rules (Deduction, Inversion etc.) ---
              // Needs specific implementations within the backward chaining context
        });


         // Add base case: If the target itself is an action, it's a path of length 1
         if (targetAtom instanceof ActionNode) {
             LinkedList<Atom> actionPath = new LinkedList<>();
             actionPath.add(targetAtom);
             foundPaths.add(actionPath);
         }
         // If target is a state node that might be considered 'achieved' or axiomatic?

        visited.remove(targetId); // Backtrack: remove from visited for other branches
        return foundPaths;
    }

     /** Calculate a heuristic confidence score for a path (product of link confidences). */
     private double calculatePathConfidence(LinkedList<Atom> path) {
         double confidence = 1.0;
         for (Atom atom : path) {
             if (atom instanceof Link) { // Only links contribute to confidence calculation here
                 confidence *= atom.tv.getConfidence(); // Could also use strength or a combination
             }
         }
         return confidence;
     }

     /** Extract ActionNode IDs from a plan path. */
      private List<String> extractActionSequence(LinkedList<Atom> path) {
          List<String> actionIds = new ArrayList<>();
          for (Atom atom : path) {
              // Find action nodes OR EvaluationLinks representing 'Try(Action)'
              if (atom instanceof ActionNode) {
                  actionIds.add(atom.id);
              } else if (atom instanceof EvaluationLink) {
                  EvaluationLink eval = (EvaluationLink) atom;
                  if (eval.getPredicateName().equals(TRY_PREDICATE_NAME) && eval.getArguments().size() == 1) {
                      String potentialActionId = eval.getArguments().get(0).id;
                      if (getAtom(potentialActionId) instanceof ActionNode) {
                          actionIds.add(potentialActionId);
                      }
                  }
              }
          }
          // The path is often constructed backward, needs reversal? Depends on findPathsRecursive logic.
          // Let's assume findPathsRecursive prepends, so the order is [RuleN, ..., Rule1, Action1] -> Reverse it.
           Collections.reverse(actionIds);
          return actionIds;
      }


     /** Helper to find links concluding a target (i.e., targetId is the last element or specific consequence position). */
     private Stream<Link> findLinksConcluding(String targetId, LinkType type) {
        Set<String> candidates = new HashSet<>();
         // Index lookup by type
         Optional.ofNullable(typeIndex.get(type)).ifPresent(candidates::addAll);
         // Index lookup by target (more general, needs filtering) - Can be slow if many links target 'targetId'
         // Optional.ofNullable(targetIndex.get(targetId)).ifPresent(candidates::addAll);

         return candidates.stream()
            .map(knowledgeBase::get)
            .filter(Objects::nonNull)
            .filter(atom -> atom instanceof Link)
            .map(atom -> (Link) atom)
            .filter(link -> link.type == type) // Filter again if using targetIndex
            .filter(link -> !link.targets.isEmpty() && link.targets.get(link.targets.size() - 1).equals(targetId)); // Basic check: target is last
             // Specific rules might need different checks (e.g., PredImp consequence is targetId)
     }


    // --- Core Inference Rules ---

    /**
     * Performs PLN Deduction: (A->B, B->C) => (A->C).
     * Uses the simplified formula from the original code for non-probabilistic case,
     * or the full formula if term probabilities (node strengths) are available.
     * Handles INHERITANCE and PREDICTIVE_IMPLICATION.
     *
     * @param linkAB Premise link (Inheritance or PredictiveImplication).
     * @param linkBC Premise link (Inheritance or PredictiveImplication of the same type).
     * @return The inferred link A->C, added/revised in the KB, or null.
     */
    public Link deduction(Link linkAB, Link linkBC) {
        if (linkAB == null || linkBC == null || linkAB.targets.size() != 2 || linkBC.targets.size() != 2 ||
            linkAB.type != linkBC.type ||
            (linkAB.type != LinkType.INHERITANCE && linkAB.type != LinkType.PREDICTIVE_IMPLICATION)) {
            return null; // Invalid input or types
        }

        String aId = linkAB.targets.get(0);
        String bId1 = linkAB.targets.get(1);
        String bId2 = linkBC.targets.get(0);
        String cId = linkBC.targets.get(1);

        if (!bId1.equals(bId2)) return null; // Intermediate node mismatch

        Atom nodeA = getAtom(aId); Atom nodeB = getAtom(bId1); Atom nodeC = getAtom(cId);
        if (nodeA == null || nodeB == null || nodeC == null) return null; // Missing nodes

        double sAB = linkAB.tv.strength; double nAB = linkAB.tv.count;
        double sBC = linkBC.tv.strength; double nBC = linkBC.tv.count;
        double sB = nodeB.tv.strength;   double sC = nodeC.tv.strength;

        double sAC;
        if (Math.abs(1.0 - sB) < 1e-9) { // Handle P(B) = 1 case
            sAC = sBC;
        } else {
             // Full formula requiring P(B) and P(C) (approximated by node strengths)
            sAC = sAB * sBC + (1.0 - sAB) * (sC - sB * sBC) / (1.0 - sB);
        }
        sAC = Math.max(0.0, Math.min(1.0, sAC)); // Clamp result

        // Combine counts: Discounted minimum, possibly factor in node counts?
         double discount = (linkAB.type == LinkType.PREDICTIVE_IMPLICATION) ? TEMPORAL_INFERENCE_DISCOUNT_FACTOR : INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;
        double nAC = discount * Math.min(nAB, nBC);
        // Optional: Further discount based on intermediate node confidence
        // nAC *= nodeB.tv.getConfidence();

        TruthValue tvAC = new TruthValue(sAC, nAC);
        return addLink(linkAB.type, tvAC, aId, cId);
    }

    /**
     * Performs PLN Inversion (Bayes Rule): (A->B) => (B->A).
     * Handles INHERITANCE and PREDICTIVE_IMPLICATION. Requires term probabilities P(A), P(B).
     *
     * @param linkAB Premise link A->B.
     * @return The inferred link B->A, added/revised in the KB, or null.
     */
    public Link inversion(Link linkAB) {
        if (linkAB == null || linkAB.targets.size() != 2 ||
           (linkAB.type != LinkType.INHERITANCE && linkAB.type != LinkType.PREDICTIVE_IMPLICATION)) {
            return null; // Invalid input
        }

        String aId = linkAB.targets.get(0); String bId = linkAB.targets.get(1);
        Atom nodeA = getAtom(aId); Atom nodeB = getAtom(bId);
        if (nodeA == null || nodeB == null) return null; // Missing nodes

        double sAB = linkAB.tv.strength; double nAB = linkAB.tv.count;
        double sA = nodeA.tv.strength;   double sB = nodeB.tv.strength;

        double sBA;
        if (sB < 1e-9) { // Avoid division by zero
            System.err.println("Warning: Inversion P(B) near zero for B=" + bId);
            sBA = 0.5; // Max uncertainty
            nAB = 0.0; // Zero count for inversion result
        } else {
            sBA = sAB * sA / sB;
            sBA = Math.max(0.0, Math.min(1.0, sBA)); // Clamp
        }

         double discount = (linkAB.type == LinkType.PREDICTIVE_IMPLICATION) ? TEMPORAL_INFERENCE_DISCOUNT_FACTOR : INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;
        double nBA = discount * nAB;
        // Optional: Factor in node counts/confidence?
        // nBA = discount * Math.min(nAB, nodeA.tv.count, nodeB.tv.count);

        TruthValue tvBA = new TruthValue(sBA, nBA);
        return addLink(linkAB.type, tvBA, bId, aId); // Note reversed targets
    }

     /**
      * Performs Probabilistic Modus Ponens: (A, A->B) => B.
      * Handles INHERITANCE and PREDICTIVE_IMPLICATION.
      * Formula: sB = sA * sAB + sB_prior * (1 - sA) -> Simplified: sB = sA * sAB + 0.5 * (1-sA) if no prior
      * Here, we update the existing TV of B using the evidence from A and A->B.
      *
      * @param premiseAtom The 'A' atom.
      * @param implicationLink The 'A->B' link.
      * @return The potentially updated 'B' atom, or null.
      */
     public Atom modusPonens(Atom premiseAtom, Link implicationLink) {
         if (premiseAtom == null || implicationLink == null || implicationLink.targets.size() != 2 ||
            (implicationLink.type != LinkType.INHERITANCE && implicationLink.type != LinkType.PREDICTIVE_IMPLICATION) ||
            !implicationLink.targets.get(0).equals(premiseAtom.id)) {
             return null; // Invalid input
         }

         String bId = implicationLink.targets.get(1);
         Atom conclusionAtom = getAtom(bId);
         if (conclusionAtom == null) return null; // Conclusion node must exist

         double sA = premiseAtom.tv.strength;       double nA = premiseAtom.tv.count;
         double sAB = implicationLink.tv.strength; double nAB = implicationLink.tv.count;
         double sB_prior = conclusionAtom.tv.strength; double nB_prior = conclusionAtom.tv.count; // Existing belief in B

          // Calculate the strength of B derived *solely* from this MP step
         // Simplified formula: P(B|A) ≈ sA * sAB. Ignore prior influence for calculation of derived strength.
         // Better: Use full formula if possible. P(B) = P(B|A)P(A) + P(B|~A)P(~A)
         // Assuming P(B|~A) ≈ sB_prior (or 0.5 if no prior?), P(~A) = 1-sA
          // Using simpler Bayesian update inspired approach: treat this as new evidence
          double derived_sB = sA * sAB; // Simplified strength contribution from this rule
          derived_sB = Math.max(0.0, Math.min(1.0, derived_sB));

          // Calculate the count/confidence for this derived piece of evidence
         double discount = (implicationLink.type == LinkType.PREDICTIVE_IMPLICATION) ? TEMPORAL_INFERENCE_DISCOUNT_FACTOR : INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;
         double derived_nB = discount * Math.min(nA, nAB);

         if (derived_nB < 1e-6) return conclusionAtom; // Derived evidence is negligible

          // Create a temporary TruthValue representing the evidence from this inference
          TruthValue evidenceTV = new TruthValue(derived_sB, derived_nB);

         // Revise the existing belief in B with this new evidence
         TruthValue revisedTV = conclusionAtom.tv.revise(evidenceTV);

          // Update the conclusion atom in the KB
          conclusionAtom.tv = revisedTV;
          conclusionAtom.updateAccessTime(globalTimeStep.get()); // Mark as accessed
          addAtom(conclusionAtom); // Use addAtom to ensure indices etc. are handled if needed (though it modifies in place here)

         System.out.println("  MP: " + premiseAtom.getShortName() + ", " + implicationLink.getShortName() + " => " + conclusionAtom.getShortName() + " revised to " + revisedTV);
         return conclusionAtom;
     }


    // --- Inference Control (Forward Chaining - Simplified) ---

    /**
     * Performs forward chaining inference for a limited number of steps or until quiescence.
     * Applies Deduction, Inversion, Modus Ponens using indexed lookups.
     *
     * @param maxSteps Maximum inference cycles.
     */
    public void forwardChain(int maxSteps) {
        System.out.println("--- Forward Chaining (Max Steps: " + maxSteps + ") ---");
        Set<String> linksProcessedInStep = new HashSet<>(); // Avoid re-processing within one step

        for (int step = 0; step < maxSteps; step++) {
             int inferencesMadeThisStep = 0;
             linksProcessedInStep.clear();
             long stepStartTime = System.currentTimeMillis();

             // Use index to get candidate links efficiently
             Set<String> candidateInheritanceIds = typeIndex.getOrDefault(LinkType.INHERITANCE, ConcurrentSkipListSet.of());
             Set<String> candidatePredictiveIds = typeIndex.getOrDefault(LinkType.PREDICTIVE_IMPLICATION, ConcurrentSkipListSet.of());
             List<Link> currentLinks = Stream.concat(candidateInheritanceIds.stream(), candidatePredictiveIds.stream())
                                            .map(this::getAtom) // Update access time/importance
                                            .filter(Objects::nonNull)
                                            .filter(a -> a instanceof Link)
                                            .map(a -> (Link)a)
                                            .collect(Collectors.toList()); // Snapshot for iteration

            // --- Try Deduction & Modus Ponens ---
            for (Link linkAB : currentLinks) {
                 if(linkAB.targets.size() != 2) continue;
                 String bId = linkAB.targets.get(1);

                 // Find links starting with B (B->C for Deduction)
                  Set<String> linksStartingWithB = targetIndex.entrySet().stream()
                      .filter(entry -> entry.getKey().equals(bId)) // Find links where B is a target (might be A->B, check later)
                      .flatMap(entry -> entry.getValue().stream())
                      .collect(Collectors.toSet()); // Get link IDs where B is *some* target

                 // For Modus Ponens, find premise A matching linkAB's source
                 Atom premiseA = getAtom(linkAB.targets.get(0));
                 if (premiseA != null && premiseA.tv.getConfidence() > MIN_CONFIDENCE_THRESHOLD) {
                     if (!linksProcessedInStep.contains(premiseA.id + "|" + linkAB.id)) {
                        Atom updatedB = modusPonens(premiseA, linkAB);
                         if (updatedB != null) { // ModusPonens updates in place, returns atom if successful
                             inferencesMadeThisStep++;
                             linksProcessedInStep.add(premiseA.id + "|" + linkAB.id);
                         }
                     }
                 }


                  // Optimized Deduction: Iterate through links where B is the *source*
                  // Need an index Map<String, Set<String>> sourceIndex for this. Let's approximate with targetIndex.
                   for (String linkIdBC : targetIndex.getOrDefault(bId, ConcurrentSkipListSet.of())) { // Check links where B is a target
                       Atom bcAtom = getAtom(linkIdBC);
                       if (bcAtom instanceof Link) {
                          Link linkBC = (Link) bcAtom;
                           // Ensure linkBC is B->C structure and same type as linkAB
                           if(linkBC.targets.size() == 2 && linkBC.targets.get(0).equals(bId) && linkBC.type == linkAB.type) {
                                String premisePairId = linkAB.id + "|" + linkBC.id;
                                if (!linksProcessedInStep.contains(premisePairId)) {
                                     Link inferredAC = deduction(linkAB, linkBC);
                                    if (inferredAC != null) {
                                         System.out.println("  Deduction: " + linkAB.getShortName() + ", " + linkBC.getShortName() + " => " + inferredAC.getShortName() + " " + inferredAC.tv);
                                        inferencesMadeThisStep++;
                                    }
                                     linksProcessedInStep.add(premisePairId);
                                     linksProcessedInStep.add(linkBC.id + "|" + linkAB.id); // Avoid reverse check
                                }
                           }
                       }
                   }


                 // --- Try Inversion (moved inside loop for locality) ---
                  String inversionId = linkAB.id + "|inv";
                 if (!linksProcessedInStep.contains(inversionId)) {
                     Link inferredBA = inversion(linkAB);
                      if (inferredBA != null) {
                           System.out.println("  Inversion: " + linkAB.getShortName() + " => " + inferredBA.getShortName() + " " + inferredBA.tv);
                          inferencesMadeThisStep++;
                      }
                     linksProcessedInStep.add(inversionId);
                 }
             }


            long stepEndTime = System.currentTimeMillis();
             System.out.println("Step " + (step + 1) + " finished in " + (stepEndTime - stepStartTime) + "ms. Inferences made: " + inferencesMadeThisStep);

            if (inferencesMadeThisStep == 0) {
                 System.out.println("Quiescence reached. Stopping forward chaining.");
                 break;
            }
        }
        System.out.println("--- Forward Chaining Finished ---");
    }

    // --- Higher-Order Logic Primitives (Basic) ---

     /** Basic unification support (Placeholder - requires significant implementation). */
     private boolean unify(Atom pattern, Atom instance, Map<VariableNode, String> bindings) {
         // TODO: Implement unification logic comparing pattern (potentially with variables) and instance.
         // Needs to handle Node-Node, Variable-Node, Link-Link recursively.
         System.err.println("Warning: Unification not implemented.");
         return pattern.id.equals(instance.id); // Fallback to exact match if no variables
     }

    // --- Internal Data Structures ---

    /** Represents uncertain truth: strength (belief) and count (evidence amount). */
    public static class TruthValue {
        public static final TruthValue DEFAULT = new TruthValue(0.5, 0.0);
        public static final TruthValue ZERO = new TruthValue(0.0, 1.0); // Useful for non-reward
        public static final TruthValue FULL = new TruthValue(1.0, 1.0); // Useful for axioms/direct perception

        public final double strength; // [0, 1]
        public final double count;    // Non-negative, virtual evidence count

        public TruthValue(double strength, double count) {
            this.strength = Math.max(0.0, Math.min(1.0, strength));
            this.count = Math.max(0.0, count);
        }

        /** Calculates confidence based on count and sensitivity. confidence = count / (count + k) */
        public double getConfidence() {
            return count / (count + DEFAULT_EVIDENCE_SENSITIVITY);
        }

        /**
         * Revision: Merges this TV with other evidence about the same atom.
         * Uses weighted averaging based on counts.
         */
        public TruthValue revise(TruthValue other) {
            if (other == null || other.count == 0) return this;
             if (this.count == 0) return other; // If current has no evidence, adopt other

            double totalCount = this.count + other.count;
            // Avoid division by zero (shouldn't happen if counts > 0 checked)
            // if (totalCount < 1e-9) return DEFAULT;

            double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new TruthValue(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("<s=%.3f, c=%.2f, w=%.3f>", strength, count, getConfidence());
        }

        @Override
        public boolean equals(Object o) {
             if (this == o) return true;
             if (o == null || getClass() != o.getClass()) return false;
             TruthValue that = (TruthValue) o;
             // Use tolerance for floating point comparison
             return Math.abs(that.strength - strength) < 1e-6 && Math.abs(that.count - count) < 1e-6;
        }

        @Override
        public int hashCode() {
            return Objects.hash(strength, count); // Approximate hash
        }
    }


    /** Base class for all knowledge elements (Nodes and Links). */
    public abstract static class Atom {
        public final String id; // Unique identifier, derived from content
        public TruthValue tv;
        public volatile long lastAccessedTime; // For forgetting/importance (use volatile for visibility)
        public volatile double baseImportance; // Base value, increases on creation/revision

        protected Atom(String id, TruthValue tv) {
             this.id = Objects.requireNonNull(id, "Atom ID cannot be null");
             this.tv = Objects.requireNonNull(tv, "TruthValue cannot be null");
             this.lastAccessedTime = 0; // Initialized by addAtom or getAtom
             this.baseImportance = 1.0; // Default base importance
        }

         // Abstract method ensures subclasses generate ID correctly *before* constructor finishes
         // protected Atom(TruthValue tv) { this.tv = tv; this.id = generateId(); }
         // protected abstract String generateId(); // Constructor timing issue with final fields


         public void updateAccessTime(long currentTime) {
            this.lastAccessedTime = currentTime;
         }

          public void incrementImportance() {
              // Simple linear increase on access, could be more complex
              this.baseImportance += 0.1;
          }

        /** Calculates current importance based on confidence, recency, and base importance. */
        public double getImportance(long currentTime) {
             double confidence = tv.getConfidence();
             // Time decay penalty: importance decreases the longer it hasn't been accessed
             double timeSinceAccess = Math.max(0, currentTime - lastAccessedTime);
             double recencyFactor = Math.pow(IMPORTANCE_TIME_DECAY_FACTOR, timeSinceAccess);

             // Combine factors (example heuristic)
             // Weight confidence heavily, recency moderately, base importance as a baseline
             return (confidence * 10.0 + recencyFactor * 1.0 + baseImportance * 0.1);
        }

         /** Provides a short, human-readable name (override in subclasses). */
         public String getShortName() {
            return id; // Default implementation
         }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Atom atom = (Atom) o;
            return id.equals(atom.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            // Include importance metrics for debugging
            // return String.format("%s %s (Importance: %.3f, LastAccess: %d)", getShortName(), tv, getImportance(System.currentTimeMillis()/1000), lastAccessedTime);
             return String.format("%s %s", getShortName(), tv); // Cleaner default output
        }
    }


    /** Represents concepts, entities, constants. */
    public static class Node extends Atom {
        public final String name; // Human-readable name/symbol

         // Private constructor called by factory methods or subclasses
         protected Node(String name, TruthValue tv, Function<String, String> idGenerator) {
              super(idGenerator.apply(name), tv);
              this.name = name;
         }

         // Standard Node
         public Node(String name, TruthValue tv) {
              this(name, tv, Node::generateId);
         }

         public static String generateId(String name) {
            return "Node(" + name + ")";
         }

         @Override
         public String getShortName() {
             return name;
         }
    }

     /** Node specifically representing an executable action. */
     public static class ActionNode extends Node {
          public ActionNode(String actionName, TruthValue tv) {
               super(ACTION_PREFIX + actionName, tv, ActionNode::generateId);
          }
          public static String generateId(String name) { return "Action(" + name + ")"; }
          @Override
          public String getShortName() { return name; } // Inherits name field
     }

      /** Node specifically representing a state (often composite). */
      public static class StateNode extends Node {
           public StateNode(String stateName, TruthValue tv) {
                super(STATE_PREFIX + stateName, tv, StateNode::generateId);
           }
           public static String generateId(String name) { return "State(" + name + ")"; }
           @Override
           public String getShortName() { return name; }
      }

       /** Node specifically representing a goal. */
       public static class GoalNode extends Node {
            public GoalNode(String goalTargetId, TruthValue desireTV) {
                 super(GOAL_PREFIX + goalTargetId, desireTV, GoalNode::generateId);
            }
            public static String generateId(String name) { return "Goal(" + name + ")"; }
           @Override
           public String getShortName() { return name; }
       }

    /** Represents variables for higher-order logic (basic). */
    public static class VariableNode extends Node {
        public VariableNode(String name) {
            super(name, TruthValue.DEFAULT, VariableNode::generateId); // Variables often have default TV initially
        }
        public static String generateId(String name) {
             // Ensure variable names are distinct from regular nodes, e.g., using a prefix '$'
             return "Var(" + (name.startsWith("$") ? name : "$" + name) + ")";
         }
          @Override
          public String getShortName() {
              return name.startsWith("$") ? name : "$" + name;
          }
    }


    /** Represents relationships between Atoms. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets; // IDs of Atoms involved, ORDER MATTERS for some types (Seq, Imp)

        public Link(LinkType type, List<String> targetIds, TruthValue tv) {
            super(generateId(type, targetIds), tv);
            this.type = type;
            // DO NOT SORT for types where order matters (Sequence, Implication, Evaluation)
            if (type.isOrderIndependent()) {
                 this.targets = targetIds.stream().sorted().collect(Collectors.toList());
            } else {
                 this.targets = Collections.unmodifiableList(new ArrayList<>(targetIds));
            }
             // Re-generate ID based on potentially sorted list if order-independent
             if (type.isOrderIndependent() && !this.id.equals(generateId(type, this.targets))) {
                 throw new IllegalStateException("ID generation mismatch after sorting for order-independent link.");
                 // This shouldn't happen if generateId handles sorting correctly
             }
        }

        public static String generateId(LinkType type, List<String> targetIds) {
            List<String> idsForHash = targetIds;
            if (type.isOrderIndependent()) {
                idsForHash = targetIds.stream().sorted().collect(Collectors.toList());
            }
             // Use a more robust delimiter than just toString() which includes spaces/commas
             String targetString = String.join("|", idsForHash);
            return type + "(" + targetString + ")";
        }

        @Override
         public String getShortName() {
             String targetStr = targets.stream()
                                      .map(id -> {
                                          // Attempt to get the short name of the target atom
                                           Atom targetAtom = /* Should we lookup here? Potentially slow. */ null; // knowledgeBase.get(id);
                                           return targetAtom != null ? targetAtom.getShortName() : id; // Fallback to ID
                                      })
                                      .collect(Collectors.joining(", "));
             return type + "(" + targetStr + ")";
         }

        public enum LinkType {
            // Core Logical / Structural
            INHERITANCE(true),   // A -> B (probabilistic subclass/property)
            SIMILARITY(true),    // A <-> B (symmetric association)
            AND(true),           // Conjunction
            OR(true),            // Disjunction
            NOT(false),          // Negation (typically targets single Atom)
            EQUIVALENCE(true),   // A <=> B (strong symmetric)

            // Evaluation / Execution
            EVALUATION(false),    // Predicate(Arg1, Arg2...) - Order matters (Predicate first)
            EXECUTION(false),     // Schema(Args...) -> Result (Placeholder)

            // Temporal Logic (Order Matters for most)
            SEQUENCE(false),          // Sequence(A, B, C...) - Ordered events
            PREDICTIVE_IMPLICATION(false), // Premise -> Consequence (temporal implication)
            EVENTUAL_PREDICTIVE_IMPLICATION(false), // Premise -> Consequence (eventually)
            PREDICTIVE_CHAIN(false), // PredictiveImplication(Sequence(A,B), C) etc.
            INITIATE(false),         // Link representing initiation event (e.g., Eval(Initiate, Event, Time))
            TERMINATE(false),        // Link representing termination event (e.g., Eval(Terminate, Event, Time))
            HOLDS(false),            // Link representing a fluent holding (e.g., Eval(Holds, Fluent, Time))
            SIMULTANEOUS_AND(true),  // SimAND(A, B) - Occur concurrently
            SIMULTANEOUS_OR(true),   // SimOR(A, B) - Occur concurrently

            // Higher Order Logic (Order Matters for scope/body)
            FOR_ALL(false),      // ForAll([Var1, Var2...], BodyLink)
            EXISTS(false);       // Exists([Var1, Var2...], BodyLink)

            private final boolean orderIndependent;

             LinkType(boolean orderIndependent) {
                 this.orderIndependent = orderIndependent;
             }

             public boolean isOrderIndependent() {
                 return orderIndependent;
             }
        }
    }

     /** Link specifically for Evaluation: Predicate(Arg1, Arg2...). */
     public static class EvaluationLink extends Link {
          // Assumes target list is [PredicateNodeID, Arg1ID, Arg2ID, ...]
          public EvaluationLink(List<String> targetIds, TruthValue tv) {
              super(LinkType.EVALUATION, targetIds, tv);
              if (targetIds == null || targetIds.isEmpty()) {
                   throw new IllegalArgumentException("EvaluationLink requires at least a predicate ID.");
              }
               // Optional: Check if first target is actually a Predicate Node?
          }

          public Atom getPredicate() {
              // Need access to KB here, which static nested class doesn't have directly.
              // Pass KB instance or use a lookup method if needed frequently.
              // return PLNSystem.this.getAtom(targets.get(0)); // Example if KB is accessible
              if(targets.isEmpty()) return null;
               return new Node(targets.get(0).replace("Node(", "").replace(")", ""), TruthValue.DEFAULT); // HACK: return dummy based on ID
          }
          public String getPredicateName() {
               if(targets.isEmpty()) return "NULL_PREDICATE";
               // Extract name assuming "Node(PredicateName)" format
                String predicateId = targets.get(0);
                if (predicateId.startsWith("Node(") && predicateId.endsWith(")")) {
                     return predicateId.substring(5, predicateId.length() - 1);
                }
                return predicateId; // Fallback to full ID
          }

           public List<Atom> getArguments() {
               // Need access to KB
               // return targets.stream().skip(1).map(PLNSystem.this::getAtom).collect(Collectors.toList());
               return Collections.emptyList(); // Placeholder
           }


          @Override
          public String getShortName() {
               String predName = getPredicateName();
               String argsStr = targets.stream().skip(1)
                                       .map(id -> id.replace("Node(","").replace(")", "").replace(ACTION_PREFIX, "")) // Simplify common patterns
                                       .collect(Collectors.joining(", "));
               return predName + "(" + argsStr + ")";
          }
     }


     // --- Temporal Primitives (Illustrative - Need Further Development) ---
     // These could be represented using EvaluationLinks (e.g., Eval(Initiate, Event, Time))
     // or specialized Link types if frequent direct manipulation is needed.

     /** Represents time information (Placeholder). */
     public static class TimeValue {
          long timePoint; // Or interval start/end, or distribution parameters
          // TODO: Implement time representation and comparison logic
     }

     // --- Helper Methods ---
     // (Add any other utility methods needed)

}