import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
* Enhanced Probabilistic Logic Network (EPLN) - Core Engine and Agent Framework.
*
* This single, consolidated class implements a significantly enhanced Probabilistic Logic Network,
* addressing key limitations of simpler versions and incorporating advanced concepts like
* attention, forgetting, flexible perception/action, basic planning, temporal logic
* based on Event Calculus principles, higher-order logic elements, and improved scalability heuristics.
*
* Design Goals:
* - Computationally Powerful: Integrates temporal, causal, and basic higher-order reasoning.
* - Efficient: Implements attention/forgetting for bounded memory usage and indexed lookups.
* - Elegant & Extensible: Modular design with clear interfaces and abstractions.
* - Self-Documenting: Clear naming, structure, and Javadoc minimize external documentation needs.
* - Consolidated & Deduplicated: Single-class structure with minimal redundancy.
*
* Key Enhancements Implemented:
* - Attention & Forgetting: Atoms have AttentionValue (STI/LTI) and a forgetting mechanism prunes low-importance atoms, enabling indefinite runs within heap limits.
* - Perception/Action API: Generic `PerceptionMapper` and `ActionExecutor` interfaces allow fluent integration with arbitrary external systems/environments.
* - Planning: Basic goal-directed backward-chaining planner (`planAction`) searches for action sequences.
* - Temporal Logic: Incorporates Event Calculus concepts (`Initiate`, `Terminate`, `HoldsAt`, `HoldsThroughout`, `PredictiveImplication`, `Sequence`) via dedicated LinkTypes and inference rules. Time is represented via TimeNodes.
* - Higher-Order Logic: Supports `VariableNode` and basic `ForAllLink` / `ExistsLink` for quantification and schema representation (unification support is foundational).
* - Scalability: Uses indexing (`linksByType`, `linksByTarget`) for faster lookups than exhaustive searches. Inference uses heuristics (e.g., attention value).
*
* Based on concepts from standard PLN literature and the provided "Temporal and Causal Inference" text.
  */
  public class ProbabilisticLogicNetwork {

  // --- Configuration Constants ---
  private static final double DEFAULT_EVIDENCE_SENSITIVITY = 1.0; // k in confidence formula
  private static final double INFERENCE_CONFIDENCE_DISCOUNT = 0.9; // Confidence reduction per inference step
  private static final double FORGETTING_RATE = 0.05; // % STI decay per cycle
  private static final double STI_TO_LTI_TRANSFER_RATE = 0.1; // % STI transferred to LTI per cycle
  private static final double DEFAULT_INITIAL_STI = 0.1; // Initial STI for new atoms
  private static final double DEFAULT_INITIAL_LTI = 0.01; // Initial LTI for new atoms
  private static final double ATTENTION_BUMP_AMOUNT = 0.2; // STI increase when atom is used
  private static final double FORGET_PRUNE_THRESHOLD = 0.05; // Combined importance threshold for pruning
  private static final int MAX_BACKWARD_CHAIN_DEPTH = 5; // Default planning/query depth limit
  private static final int MAX_FORWARD_CHAIN_STEPS = 2; // Default forward inference steps per cycle
  private static final double GOAL_SATISFACTION_THRESHOLD = 0.9; // Min strength for goal satisfaction

  // --- Core Knowledge Base ---
  private final ConcurrentMap<String, Atom> knowledgeBase = new ConcurrentHashMap<>(1024);
  // --- Indices for Scalability ---
  private final ConcurrentMap<LinkType, ConcurrentMap<String, Link>> linksByType = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Set<String>> linksByTarget = new ConcurrentHashMap<>(); // Map target ID -> Set<Link ID>
  private final ConcurrentMap<String, Set<String>> linksBySource = new ConcurrentHashMap<>(); // Map source ID -> Set<Link ID> (useful for some queries)

  private final AtomicLong atomIdCounter = new AtomicLong(0); // Simple way to get unique internal IDs if needed

  // --- Agent State & Control ---
  private String currentGoalId = null; // ID of the primary goal Atom
  private String currentStateAtomId = null; // ID of the Atom representing the current perceived state
  private PerceptionMapper perceptionMapper = new DefaultPerceptionMapper(); // Pluggable perception strategy
  private ActionExecutor actionExecutor = null; // Pluggable action execution strategy

  // --- Timing & Forgetting Control ---
  private volatile long lastForgettingTime = System.nanoTime();
  private final long forgettingIntervalNanos = TimeUnit.SECONDS.toNanos(10); // Run forgetting periodically


    // ========================================================================
    // === Public API Methods =================================================
    // ========================================================================

    /**
     * Adds or updates an Atom in the knowledge base.
     * If an Atom with the same ID exists, performs revision on TruthValue and updates AttentionValue.
     * Automatically updates relevant indices.
     *
     * @param atom The Atom to add or update.
     * @return The resulting Atom in the knowledge base (potentially revised/updated).
     */
    public synchronized <T extends Atom> T addAtom(T atom) {
        Atom existing = knowledgeBase.compute(atom.id, (id, current) -> {
            if (current == null) {
                // New atom - update indices
                updateIndicesOnAdd(atom);
                return atom;
            } else {
                // Existing atom - revise TV, update AV
                current.tv = current.tv.merge(atom.tv);
                current.av = current.av.merge(atom.av); // Merge attention (e.g., sum STI, weighted avg LTI?)
                current.av.bumpSTI(ATTENTION_BUMP_AMOUNT); // Bump attention on revision
                // Indices don't need update unless targets change (which requires new ID)
                return current;
            }
        });
        return (T) existing; // Cast back to original type
    }

    /**
     * Gets an Atom by its ID, potentially bumping its short-term importance.
     *
     * @param id The ID of the Atom.
     * @param bumpAttention If true, increases the Atom's STI.
     * @return The Atom, or null if not found.
     */
    public Atom getAtom(String id, boolean bumpAttention) {
        Atom atom = knowledgeBase.get(id);
        if (atom != null && bumpAttention) {
            atom.av.bumpSTI(ATTENTION_BUMP_AMOUNT);
        }
        return atom;
    }

    /** Convenience method for getAtom without bumping attention. */
    public Atom getAtom(String id) {
        return getAtom(id, false);
    }

    /**
     * Gets a Node by its name, creating it with default TV/AV if it doesn't exist.
     *
     * @param name The name of the node.
     * @return The existing or newly created Node.
     */
    public Node getOrCreateNode(String name) {
        String nodeId = Node.generateId(name);
        return (Node) knowledgeBase.computeIfAbsent(nodeId, id -> {
            Node newNode = new Node(name, TruthValue.DEFAULT, AttentionValue.DEFAULT);
            updateIndicesOnAdd(newNode);
            return newNode;
        });
    }

    /**
     * Gets a TimeNode representing a specific time point, creating it if necessary.
     * @param time Time value (e.g., timestamp, simulation step).
     * @return The TimeNode.
     */
     public TimeNode getOrCreateTimeNode(long time) {
        String nodeId = TimeNode.generateId(time);
         return (TimeNode) knowledgeBase.computeIfAbsent(nodeId, id -> {
            TimeNode newNode = new TimeNode(time, TruthValue.CERTAIN, AttentionValue.DEFAULT); // Time points are certain
            updateIndicesOnAdd(newNode);
            return newNode;
        });
    }


    /**
     * Creates a Link Atom and adds it to the KB, performing revision if it exists.
     * Uses Atom IDs as targets.
     *
     * @param type The type of the link.
     * @param tv The TruthValue of the link.
     * @param av The AttentionValue of the link.
     * @param targetIds Varargs list of target Atom IDs.
     * @return The existing or newly created Link.
     */
    public Link addLink(LinkType type, TruthValue tv, AttentionValue av, String... targetIds) {
        Link link = new Link(type, Arrays.asList(targetIds), tv, av);
        return (Link) addAtom(link); // addAtom handles revision and indexing
    }

    /**
     * Convenience method to create a Link using Atom objects as targets.
     */
    public Link addLink(LinkType type, TruthValue tv, AttentionValue av, Atom... targets) {
        List<String> targetIds = Arrays.stream(targets).map(a -> a.id).collect(Collectors.toList());
        Link link = new Link(type, targetIds, tv, av);
        return (Link) addAtom(link);
    }

     /**
     * Removes an atom from the knowledge base and associated indices.
     * USE WITH CAUTION - Does not handle dangling references in other atoms yet.
     * Primarily intended for use by the forgetting mechanism.
     *
     * @param atomId The ID of the atom to remove.
     * @return The removed atom, or null if it wasn't found.
     */
    public synchronized Atom removeAtom(String atomId) {
        Atom removed = knowledgeBase.remove(atomId);
        if (removed != null) {
            updateIndicesOnRemove(removed);
            // TODO: Implement logic to find and potentially remove/update other atoms
            // that reference the removed atomId in their target lists. This is complex.
        }
        return removed;
    }


    /**
     * Sets the strategy for mapping external perception data to PLN Atoms.
     * @param mapper The PerceptionMapper implementation.
     */
    public void setPerceptionMapper(PerceptionMapper mapper) {
        this.perceptionMapper = Objects.requireNonNull(mapper);
    }

    /**
     * Sets the strategy for executing actions represented by PLN Atoms.
     * @param executor The ActionExecutor implementation.
     */
    public void setActionExecutor(ActionExecutor executor) {
        this.actionExecutor = Objects.requireNonNull(executor);
    }

    /**
     * Perceives the external state using the configured PerceptionMapper.
     * Creates/updates Atoms representing the current state and returns the ID
     * of the primary state Atom.
     *
     * @param externalState A map or object representing the raw state data.
     * @return The ID of the Atom representing the perceived state.
     */
    public String perceiveState(Object externalState) {
        if (perceptionMapper == null) {
            throw new IllegalStateException("PerceptionMapper is not set.");
        }
        // Mapper translates external state and adds necessary atoms to this PLN instance
        this.currentStateAtomId = perceptionMapper.mapStateToAtomId(externalState, this);
        // Periodically run housekeeping
        runHousekeeping();
        return this.currentStateAtomId;
    }

    /**
     * Selects an action to achieve the current goal, based on the current state,
     * using the planning mechanism.
     *
     * @return The ID of the selected action Atom (e.g., an ExecutionLink), or null if no plan found.
     */
    public String selectAction() {
        if (currentStateAtomId == null || currentGoalId == null) {
            System.err.println("Warning: Cannot select action - current state or goal not set.");
            return null;
        }
        Atom goalAtom = getAtom(currentGoalId);
        Atom stateAtom = getAtom(currentStateAtomId);
        if (goalAtom == null || stateAtom == null) {
             System.err.println("Warning: Cannot select action - state or goal atom not found in KB.");
             return null;
        }

        // Check if goal already met
        if (isGoalSatisfied(goalAtom, stateAtom)) {
             System.out.println("Goal " + currentGoalId + " already satisfied.");
             return null; // No action needed
        }


        List<Link> plan = planAction(goalAtom, stateAtom, MAX_BACKWARD_CHAIN_DEPTH);

        if (plan != null && !plan.isEmpty()) {
            // Return the first action in the plan
            // More sophisticated execution would handle the whole sequence
            return plan.get(0).id;
        } else {
            System.out.println("No plan found to achieve goal: " + currentGoalId);
            // TODO: Implement exploration strategy (e.g., select random action)
            return null;
        }
    }

     /**
     * Executes a planned action using the configured ActionExecutor.
     *
     * @param actionAtomId The ID of the action Atom (typically an ExecutionLink) to execute.
     * @return True if execution was successfully initiated, false otherwise.
     */
    public boolean executeAction(String actionAtomId) {
        if (actionExecutor == null) {
            throw new IllegalStateException("ActionExecutor is not set.");
        }
        if (actionAtomId == null) {
             System.err.println("Warning: Cannot execute null action ID.");
             return false;
        }
        Atom actionAtom = getAtom(actionAtomId, true); // Bump attention for executed action
        if (!(actionAtom instanceof Link)) {
            System.err.println("Warning: Action ID " + actionAtomId + " does not correspond to a Link.");
            return false;
        }
        // Executor interacts with the external environment/system
        return actionExecutor.executeActionAtom((Link) actionAtom, this);
    }


    /**
     * Learns from the outcome of the last action.
     * Creates/updates predictive links based on state transitions and rewards.
     * Runs forward chaining to integrate new knowledge.
     *
     * @param previousStateId ID of the state before the action.
     * @param executedActionId ID of the action performed.
     * @param resultingStateId ID of the state after the action.
     * @param reward Numerical reward received after the action.
     */
    public void learnFromExperience(String previousStateId, String executedActionId, String resultingStateId, double reward) {
        if (previousStateId == null || executedActionId == null || resultingStateId == null) {
            System.err.println("Warning: Skipping learning due to null state/action IDs.");
            return;
        }
        System.out.println("Learning: " + previousStateId + " + " + executedActionId + " -> " + resultingStateId + " (Reward: " + reward + ")");

        Atom prevS = getAtom(previousStateId, true);
        Atom action = getAtom(executedActionId, true);
        Atom resultS = getAtom(resultingStateId, true); // Bump attention for involved atoms

        if(prevS == null || action == null || resultS == null) {
             System.err.println("Warning: Skipping learning - atoms not found for IDs.");
             return;
        }

        // --- 1. Learn State Transition using PredictiveImplication ---
        // We represent the precondition as `HoldsAt(State, T1) AND Executes(Action, T1)`
        // For simplicity here, let's create a link from the combination of PrevState and Action
        // to the ResultingState. A Sequence link might be appropriate.

        // Create a representation of the action occurring in the previous state
        // Using Sequence for now: Sequence(PrevState, Action)
        // TODO: Use proper temporal representation with time points if available
        Link sequence = addLink(LinkType.SEQUENCE, TruthValue.CERTAIN, AttentionValue.DEFAULT, prevS.id, action.id);


        // Create the predictive link: Sequence(PrevState, Action) => ResultState
        // Strength = 1.0 (observed), Count = 1.0 (single observation) - adjust count sensitivity as needed
        TruthValue transitionTV = new TruthValue(1.0, 1.0); // High confidence for direct observation
        addLink(LinkType.PREDICTIVE_IMPLICATION, transitionTV, AttentionValue.DEFAULT, sequence.id, resultS.id);
        System.out.println("  Learned Transition: " + sequence.id + " => " + resultS.id + " " + transitionTV);


        // --- 2. Learn Reward Association ---
        if (reward != 0.0) {
            Node rewardNode = getOrCreateNode("RewardSignal"); // General reward concept
            // Link the *resulting state* to the reward signal
            // Strength reflects reward magnitude (normalized?), count reflects observation
            double rewardStrength = Math.max(0.0, Math.min(1.0, (reward + 1.0) / 2.0)); // Simple normalization [-1, 1] -> [0, 1] ? Adjust as needed.
            TruthValue rewardTV = new TruthValue(rewardStrength, 1.0);
            addLink(LinkType.PREDICTIVE_IMPLICATION, rewardTV, AttentionValue.DEFAULT, resultS.id, rewardNode.id);
             System.out.println("  Learned Reward Assoc: " + resultS.id + " => " + rewardNode.id + " " + rewardTV);

             // Optionally: Link the Action (in context) to reward
             addLink(LinkType.PREDICTIVE_IMPLICATION, rewardTV, AttentionValue.DEFAULT, sequence.id, rewardNode.id);
             System.out.println("  Learned Action Reward Assoc: " + sequence.id + " => " + rewardNode.id + " " + rewardTV);
        }

        // --- 3. Run Forward Chaining ---
        // Integrate the newly learned information with existing knowledge
        System.out.println("  Running forward chaining...");
        forwardChain(MAX_FORWARD_CHAIN_STEPS); // Run a few steps of inference

         // --- 4. Optional: Update Goal Status ---
         // If the resulting state satisfies the goal, potentially update goal-related atoms
         // (Handled implicitly by planner checking goal satisfaction)

         // --- 5. Housekeeping ---
         runHousekeeping();
    }

    /**
     * Sets the current goal for the agent.
     * @param goalId The ID of the Atom representing the desired goal state or condition.
     */
    public void setCurrentGoal(String goalId) {
        Objects.requireNonNull(goalId, "Goal ID cannot be null");
        Atom goalAtom = getAtom(goalId, true); // Bump attention on goal
        if (goalAtom == null) {
            System.err.println("Warning: Setting goal to non-existent Atom ID: " + goalId + ". Make sure goal Atom is added.");
        }
        System.out.println("Setting goal: " + goalId);
        this.currentGoalId = goalId;
    }

    /**
     * Runs the core agent loop for a number of steps.
     * Requires PerceptionMapper and ActionExecutor to be set.
     *
     * @param environment The external environment providing state and rewards.
     * @param maxSteps The maximum number of steps to run.
     */
    public void runAgentLoop(Environment environment, int maxSteps) {
        if (perceptionMapper == null || actionExecutor == null) {
            throw new IllegalStateException("PerceptionMapper and ActionExecutor must be set before running the agent loop.");
        }
        if (currentGoalId == null) {
             System.err.println("Warning: Running agent loop without a goal set.");
        }

        System.out.println("--- Starting Agent Loop (Max Steps: " + maxSteps + ") ---");
        String prevStateId = perceiveState(environment.getCurrentState()); // Initial perception

        for (int step = 0; step < maxSteps && !environment.isTerminated(); step++) {
            System.out.println("\n--- Agent Step " + (step + 1) + " ---");
            System.out.println("Current State Atom: " + currentStateAtomId);
            System.out.println("Current Goal Atom: " + currentGoalId);

            // 1. Select Action based on current state and goal
            String actionId = selectAction();

            if (actionId == null) {
                System.out.println("No suitable action found or goal already met. Waiting/Exploring (Not Implemented).");
                 // TODO: Implement exploration / waiting strategy
                 // For now, just skip to perception of next state if no action
                 prevStateId = perceiveState(environment.getCurrentState()); // Re-perceive
                 continue;
            }
             System.out.println("Selected Action: " + actionId);


            // 2. Execute Action
            boolean executed = executeAction(actionId);
            if (!executed) {
                System.err.println("Failed to execute action: " + actionId);
                // Potentially learn about action failure?
                 prevStateId = perceiveState(environment.getCurrentState()); // Re-perceive
                 continue;
            }

            // 3. Perceive Outcome (new state and reward)
            Object newState = environment.getCurrentState();
            double reward = environment.getReward();
            String newStateId = perceiveState(newState); // Updates currentStateAtomId

            // 4. Learn from Experience
            learnFromExperience(prevStateId, actionId, newStateId, reward);

            // 5. Update previous state for next iteration
            prevStateId = newStateId; // The state resulting from the action becomes the previous state for the next step

             // (Housekeeping is run periodically within perceiveState/learn)
        }
        System.out.println("--- Agent Loop Finished ---");
    }


    // ========================================================================
    // === Core Inference Methods =============================================
    // ========================================================================

    /**
     * Performs forward chaining inference for a limited number of steps.
     * Applies available inference rules (Deduction, Temporal, etc.) based on
     * currently existing Atoms, potentially adding new derived Atoms.
     * Uses indexing and heuristics (like AttentionValue) to prioritize.
     *
     * @param maxSteps Maximum number of inference cycles.
     * @return The number of new inferences made.
     */
    public int forwardChain(int maxSteps) {
        int totalInferences = 0;
        Set<String> activatedAtomIds = new HashSet<>(); // Atoms potentially involved in new inferences this round

        // Seed activation based on recent changes or high attention?
        // For simplicity, consider all atoms initially, but prioritize by AV.
         knowledgeBase.values().stream()
            .sorted(Comparator.comparingDouble((Atom a) -> a.av.getTotalImportance()).reversed())
            .limit(1000) // Limit initial consideration set for scalability
            .forEach(a -> activatedAtomIds.add(a.id));


        for (int step = 0; step < maxSteps; step++) {
            int inferencesThisStep = 0;
            Set<String> newlyDerived = new HashSet<>(); // IDs of atoms derived in this step

            // Convert current activated IDs to Atoms, sorted by importance
            List<Atom> activeAtoms = activatedAtomIds.stream()
                                          .map(id -> getAtom(id))
                                          .filter(Objects::nonNull)
                                          .sorted(Comparator.comparingDouble((Atom a) -> a.av.getTotalImportance()).reversed())
                                          .collect(Collectors.toList());

            if (activeAtoms.isEmpty()) break; // No more atoms to process

            for (Atom atom : activeAtoms) {
                // Try applying rules where 'atom' is a premise
                inferencesThisStep += applyForwardRules(atom, newlyDerived);
            }

            if (inferencesThisStep == 0) {
                //System.out.println("FC Step " + (step + 1) + ": No new inferences.");
                break; // Converged for this round
            }

             System.out.println("FC Step " + (step + 1) + ": Made " + inferencesThisStep + " new inferences.");
             totalInferences += inferencesThisStep;

            // Prepare for next step: activate newly derived atoms and potentially neighbors
            activatedAtomIds.clear();
            activatedAtomIds.addAll(newlyDerived);
            // TODO: Add spreading activation logic (activate neighbors of newly derived atoms)
        }
        return totalInferences;
    }


    /**
     * Performs backward chaining to find evidence for, or a plan to achieve, a target Atom.
     *
     * @param targetId The ID of the target Atom (query or goal).
     * @param maxDepth Maximum recursion depth.
     * @return The potentially updated/verified target Atom, or null if not found/derivable.
     */
    public Atom backwardChain(String targetId, int maxDepth) {
        return backwardChainRecursive(targetId, maxDepth, new HashSet<>());
    }


    /**
     * Plans a sequence of actions to achieve a goal state from a current state.
     * Uses backward chaining from the goal.
     *
     * @param goalAtom The Atom representing the goal state/condition.
     * @param currentStateAtom The Atom representing the current state.
     * @param maxDepth Maximum planning depth.
     * @return A list of action Links (e.g., ExecutionLinks) representing the plan, or null/empty list if no plan found.
     */
    public List<Link> planAction(Atom goalAtom, Atom currentStateAtom, int maxDepth) {
         System.out.println("Planning: Goal=" + goalAtom.id + ", CurrentState=" + currentStateAtom.id);
         LinkedList<Link> plan = new LinkedList<>();
         Set<String> visitedGoals = new HashSet<>();

         if (planRecursive(goalAtom.id, currentStateAtom.id, maxDepth, plan, visitedGoals)) {
             System.out.println("Plan found: " + plan.stream().map(l -> l.id).collect(Collectors.joining(" -> ")));
             return plan;
         } else {
             System.out.println("No plan found.");
             return null;
         }
    }

    // ========================================================================
    // === Internal Helper Methods ============================================
    // ========================================================================

     /** Recursive helper for backward chaining / planning. */
     private boolean planRecursive(String currentGoalId, String startStateId, int depth, LinkedList<Link> plan, Set<String> visitedGoals) {
        System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "PlanStep: Seeking " + currentGoalId + " (Depth " + depth + ")");

        if (depth <= 0) return false; // Depth limit reached
        if (!visitedGoals.add(currentGoalId)) return false; // Cycle detected

        Atom currentGoalAtom = getAtom(currentGoalId, true);
        if (currentGoalAtom == null) return false; // Goal doesn't exist

        // Base Case: Is the current goal already satisfied by the startStateId?
        // This check needs context - for planning, does startStateId imply currentGoalId?
        // Simplistic check: are they the same? More complex: Check for Inheritance(startStateId, currentGoalId)
        if (isGoalSatisfiedCheck(currentGoalId, startStateId)) {
            System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "-> Goal " + currentGoalId + " satisfied by state " + startStateId);
            return true; // Goal achievable from this state (base case)
        }

        // Find rules (PredictiveImplications) that conclude the currentGoalId
        // Rule: Sequence(State, Action) => Goal
        // Rule: PrevGoal => Goal (intermediate step)

        // Find PredictiveImplication Links targeting the current goal
        List<Link> potentialRules = findLinks(LinkType.PREDICTIVE_IMPLICATION, currentGoalId) // Find links concluding currentGoal
                                      .stream()
                                      .filter(link -> link.targets.size() == 2 && link.targets.get(1).equals(currentGoalId))
                                      .sorted(Comparator.comparingDouble((Link l) -> l.av.getTotalImportance() * l.tv.getConfidence()).reversed())
                                      .collect(Collectors.toList());


         System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "-> Found " + potentialRules.size() + " potential rules concluding " + currentGoalId);

        for (Link rule : potentialRules) {
            String premiseId = rule.targets.get(0); // What causes the goal?
            Atom premiseAtom = getAtom(premiseId, true);
            if (premiseAtom == null) continue;

             System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "  Trying rule: " + premiseId + " => " + currentGoalId);


            // Is the premise an action sequence: Sequence(SomeState, Action)?
            if (premiseAtom instanceof Link && ((Link) premiseAtom).type == LinkType.SEQUENCE && ((Link) premiseAtom).targets.size() == 2) {
                Link sequenceLink = (Link) premiseAtom;
                String requiredStateId = sequenceLink.targets.get(0);
                String actionId = sequenceLink.targets.get(1); // This is the action we need!

                Atom actionAtom = getAtom(actionId);
                 if (!(actionAtom instanceof Link && ((Link)actionAtom).type == LinkType.EXECUTION)) {
                     // Ensure the action part is actually an executable action type
                     // This check depends on how actions are represented (e.g., ExecutionLink)
                     // System.out.println("   Premise action " + actionId + " is not an ExecutionLink.");
                     // Continue checking if premise IS the action itself (simpler model)
                     if (!(getAtom(actionId) instanceof Node)) { // Allow simple action nodes too?
                        continue;
                     }
                 }

                 System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "    Action identified: " + actionId + ", requires state: " + requiredStateId);


                // Recursively plan to achieve the requiredState from the startState
                if (planRecursive(requiredStateId, startStateId, depth - 1, plan, visitedGoals)) {
                    // If the required state is reachable, add the action to the plan
                    Atom finalActionAtom = getAtom(actionId);
                    if (finalActionAtom instanceof Link) { // Add the action link itself
                       plan.addLast((Link)finalActionAtom);
                       System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "    Added action " + actionId + " to plan.");
                       return true; // Plan found for this branch
                    } else if (finalActionAtom instanceof Node) {
                        // If action is just a node, find/create the corresponding ExecutionLink to add to plan
                        // This requires a convention, e.g., ExecutionLink(TryPredicate, ActionNode)
                         Link execLink = findOrCreateExecutionLink((Node)finalActionAtom);
                         if (execLink != null) {
                             plan.addLast(execLink);
                              System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "    Added execution of node " + finalActionAtom.id + " to plan via " + execLink.id);
                             return true;
                         }
                    }
                }
            } else {
                 // Premise is not an action sequence, treat it as an intermediate goal state
                 System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "    Treating premise " + premiseId + " as intermediate goal.");
                 if (planRecursive(premiseId, startStateId, depth - 1, plan, visitedGoals)) {
                     return true; // Plan found through intermediate goal
                 }
            }
        }

        visitedGoals.remove(currentGoalId); // Backtrack
        return false; // No plan found from this goal state
     }

     /** Simple check if a goal is met by a state. Extend as needed. */
     private boolean isGoalSatisfiedCheck(String goalId, String stateId) {
         if (goalId.equals(stateId)) return true;
         // Check for direct implication: State => Goal
         return findLinks(LinkType.INHERITANCE, goalId).stream()
                 .anyMatch(link -> link.targets.size() == 2 &&
                                 link.targets.get(0).equals(stateId) &&
                                 link.targets.get(1).equals(goalId) &&
                                 link.tv.strength >= GOAL_SATISFACTION_THRESHOLD &&
                                 link.tv.getConfidence() > 0.5); // Need reasonable confidence too
     }

    /** Find or create a standard way to represent executing an action node */
    private Link findOrCreateExecutionLink(Node actionNode) {
        // Convention: Execution("Try", actionNode)
        Node tryPredicate = getOrCreateNode("Try"); // Represents the intention/command to execute
        // Check if link already exists
         String linkId = Link.generateIdStatic(LinkType.EXECUTION, Arrays.asList(tryPredicate.id, actionNode.id));
         Atom existing = getAtom(linkId);
         if(existing instanceof Link) {
             return (Link)existing;
         }
         // Create it if not found
         return addLink(LinkType.EXECUTION, TruthValue.CERTAIN, AttentionValue.DEFAULT, tryPredicate.id, actionNode.id);
    }


    /** Apply relevant forward inference rules originating from a given atom. */
    private int applyForwardRules(Atom premise, Set<String> newlyDerived) {
        int inferencesMade = 0;
        if (!(premise instanceof Link)) {
            return 0; // Most rules operate on links as premises
        }
        Link linkPremise = (Link) premise;

        // --- Rule Application ---
        // Example: Deduction A->B, B->C => A->C
        if (linkPremise.type == LinkType.INHERITANCE && linkPremise.targets.size() == 2) {
            String aId = linkPremise.targets.get(0);
            String bId = linkPremise.targets.get(1);

            // Find links B->C
             findLinks(LinkType.INHERITANCE, null).stream() // Search all Inheritance links
                .filter(linkBC -> linkBC.targets.size() == 2 && linkBC.targets.get(0).equals(bId))
                .forEach(linkBC -> {
                    String cId = linkBC.targets.get(1);
                    Link inferredAC = deduction(linkPremise, linkBC);
                    if (inferredAC != null && !knowledgeBase.containsKey(inferredAC.id)) { // Check if truly new
                        newlyDerived.add(inferredAC.id);
                        // System.out.println("  FC-Ded: " + linkPremise.id + ", " + linkBC.id + " => " + inferredAC.id + " " + inferredAC.tv);
                    }
                });
            inferencesMade += newlyDerived.size(); // Count potential deductions triggered
        }

        // Example: Temporal Deduction PredImp(A, B), PredImp(B, C) => PredImp(A, C)
        if (linkPremise.type == LinkType.PREDICTIVE_IMPLICATION && linkPremise.targets.size() == 2) {
            String aId = linkPremise.targets.get(0);
            String bId = linkPremise.targets.get(1);

            // Find links PredImp(B, C)
             findLinks(LinkType.PREDICTIVE_IMPLICATION, null).stream()
                 .filter(linkBC -> linkBC.targets.size() == 2 && linkBC.targets.get(0).equals(bId))
                 .forEach(linkBC -> {
                    String cId = linkBC.targets.get(1);
                     Link inferredAC = temporalDeduction(linkPremise, linkBC);
                     if (inferredAC != null && !knowledgeBase.containsKey(inferredAC.id)) {
                         newlyDerived.add(inferredAC.id);
                         // System.out.println("  FC-TempDed: " + linkPremise.id + ", " + linkBC.id + " => " + inferredAC.id + " " + inferredAC.tv);
                     }
                 });
             inferencesMade += newlyDerived.size(); // Count potential deductions triggered
        }


        // Example: Event Calculus Rule: Initiate(E, t1), Persistent(E), not TerminatedSometimeIn(E, [t1, t2]) -> HoldsThroughout(E, [t1, t2])
        // This requires more complex pattern matching and temporal reasoning not fully implemented here.
        // Placeholder logic:
        if (linkPremise.type == LinkType.INITIATE && linkPremise.targets.size() == 2) {
            String eventId = linkPremise.targets.get(0);
            String timeId = linkPremise.targets.get(1);
             // Check if Event is Persistent, Check for Termination links, etc.
             // If conditions met, derive HoldsThroughout link.
             // Atom derived = applyEventCalculusRule1(linkPremise);
             // if (derived != null) newlyDerived.add(derived.id);
        }


        // TODO: Add more forward rules (Inversion, AND/OR rules, Quantifier rules, other Temporal rules)

        return newlyDerived.size(); // Return count of actually new atoms derived *from this premise*
    }


    /** Recursive helper for backward chaining (querying). */
    private Atom backwardChainRecursive(String targetId, int depth, Set<String> visited) {
        // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "BC: Seeking " + targetId + " (Depth " + depth + ")");

        if (depth <= 0) return getAtom(targetId); // Max depth reached
        if (!visited.add(targetId)) return getAtom(targetId); // Cycle detected

        Atom currentTarget = getAtom(targetId, true); // Bump attention

        // --- Try rules that could conclude the target ---
        List<Atom> potentialEvidence = new ArrayList<>();
        if (currentTarget != null) potentialEvidence.add(currentTarget); // Existing knowledge is evidence

        // Find rules (Links) that have targetId as their consequence (typically the last target)
        findLinks(null, targetId).stream() // Find any link type targeting the goal
            .filter(link -> link.targets.size() > 0 && link.targets.get(link.targets.size() - 1).equals(targetId))
            .sorted(Comparator.comparingDouble((Link l) -> l.av.getTotalImportance() * l.tv.getConfidence()).reversed())
            .forEach(ruleLink -> {
                // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "BC: Considering rule " + ruleLink.id);
                // Recursively try to satisfy the premises of the ruleLink
                boolean premisesSatisfied = true;
                List<Atom> premiseAtoms = new ArrayList<>();
                // Assuming premises are all targets except the last one
                for (int i = 0; i < ruleLink.targets.size() - 1; i++) {
                    String premiseId = ruleLink.targets.get(i);
                    Atom premiseAtom = backwardChainRecursive(premiseId, depth - 1, visited);
                    if (premiseAtom == null || premiseAtom.tv.getConfidence() < 0.1) { // Premise must be reasonably confident
                        // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Premise " + premiseId + " failed.");
                        premisesSatisfied = false;
                        break;
                    }
                    premiseAtoms.add(premiseAtom);
                }

                if (premisesSatisfied) {
                    // If all premises satisfied, apply the rule forward to derive new evidence for the target
                    Atom derivedEvidence = applyRuleForward(ruleLink, premiseAtoms);
                    if (derivedEvidence != null && derivedEvidence.id.equals(targetId)) {
                        // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Derived new evidence for " + targetId + " via rule " + ruleLink.id + ": " + derivedEvidence.tv);
                        potentialEvidence.add(derivedEvidence); // Add the newly derived version
                    }
                }
            });


        // Combine all found evidence using revision
        Atom combinedEvidence = null;
        if (!potentialEvidence.isEmpty()) {
            combinedEvidence = potentialEvidence.get(0); // Start with the first piece
            for (int i = 1; i < potentialEvidence.size(); i++) {
                 // Create a temporary merged atom for calculation, don't add to KB directly here
                 // unless we intend BC to modify the KB (which it currently does via applyRuleForward -> addAtom)
                 combinedEvidence = new Atom(targetId, combinedEvidence.tv.merge(potentialEvidence.get(i).tv), combinedEvidence.av.merge(potentialEvidence.get(i).av)) {
                     @Override protected String generateId() { return id; } // Anonymous class for temporary merge
                 };

            }
             // If BC is purely for query, return the combined temporary atom.
             // If BC is meant to update the KB, add/revise the combined atom:
             // addAtom(combinedEvidence); // This would revise the KB entry
        }

         // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "BC: Result for " + targetId + ": " + (combinedEvidence != null ? combinedEvidence.tv : "Not Found/Derived"));
         visited.remove(targetId); // Allow revisiting in different branches

         // Return the atom directly from KB, as applyRuleForward->addAtom has updated it.
         // If BC was purely querying, return combinedEvidence instead.
        return getAtom(targetId);
    }


    /** Simulate applying a rule forward given satisfied premises (for BC). */
    private Atom applyRuleForward(Link ruleLink, List<Atom> premiseAtoms) {
        // This is a simplified dispatcher based on rule type
        // It should mirror the logic in the forward chaining rule applications
        // Note: This currently modifies the KB by calling addAtom internally

        if (ruleLink.type == LinkType.INHERITANCE && premiseAtoms.size() == 1) {
             // Simplification: Assumes premiseAtoms contains [A, B] for A->B, B->C rule.
             // Proper BC needs better premise tracking.
             // Let's assume the call comes from finding rule B->C and satisfying premise B.
             // We need to find the corresponding A->B premise. This structure needs rethink.

             // --- RETHINK BC structure or applyRuleForward arguments ---
             // For now, return null as the logic is complex based on current BC structure
             return null;

        } else if (ruleLink.type == LinkType.PREDICTIVE_IMPLICATION && premiseAtoms.size() == 1) {
             // Similar issue as above. Need context of which rule was used.
             return null;
        }

        // TODO: Implement forward application for other rule types used in BC

        return null; // Rule application not implemented for this type/context
    }

    /** Performs PLN Deduction: A->B, B->C => A->C. */
    private Link deduction(Link linkAB, Link linkBC) {
        // Basic checks already done in caller (forwardChain)
        String aId = linkAB.targets.get(0);
        String bId = linkAB.targets.get(1); // Assumes bId matches linkBC.targets.get(0)
        String cId = linkBC.targets.get(1);

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId);
        Atom nodeC = getAtom(cId);

        if (nodeA == null || nodeB == null || nodeC == null) return null; // Nodes must exist

        double sAB = linkAB.tv.strength;
        double sBC = linkBC.tv.strength;
        double nAB = linkAB.tv.count;
        double nBC = linkBC.tv.count;

        // Using simplified formula requiring P(B) and P(C) (approximated by node strength)
        double sB = nodeB.tv.strength;
        double sC = nodeC.tv.strength;
        double sAC;

        if (Math.abs(1.0 - sB) < 1e-9) { // Avoid division by zero if P(B) is ~1
            sAC = sBC; // Simplified assumption if B is almost certain
        } else if (sB < 1e-9) { // If P(B) is ~0
             sAC = 0.0; // Cannot deduce anything through an impossible intermediate
        }
        else {
            sAC = sAB * sBC + (1.0 - sAB) * Math.max(0,(sC - sB * sBC)) / (1.0 - sB); // Ensure numerator >= 0
        }
        sAC = Math.max(0.0, Math.min(1.0, sAC)); // Clamp result

        // Combine counts: Discounted minimum (heuristic)
        double nAC = INFERENCE_CONFIDENCE_DISCOUNT * Math.min(nAB, nBC);
        // Factor in node counts/confidence? Maybe Math.min(nAB, nBC, nodeB.tv.count * nodeB.tv.getConfidence())? Keep simple for now.

        TruthValue tvAC = new TruthValue(sAC, nAC);
        AttentionValue avAC = linkAB.av.infer(linkBC.av); // Inherit/combine attention

        return addLink(LinkType.INHERITANCE, tvAC, avAC, aId, cId); // addLink handles revision
    }

     /** Performs Temporal Deduction: PredImp(A, B), PredImp(B, C) => PredImp(A, C). */
     private Link temporalDeduction(Link predAB, Link predBC) {
        // Similar logic to standard deduction, but uses PredictiveImplication
        String aId = predAB.targets.get(0);
        String bId = predAB.targets.get(1); // Assumes bId matches predBC.targets.get(0)
        String cId = predBC.targets.get(1);

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId);
        Atom nodeC = getAtom(cId);

        // Check if nodes exist (optional, adds robustness)
        if (nodeA == null || nodeB == null || nodeC == null) return null;

        double sAB = predAB.tv.strength;
        double sBC = predBC.tv.strength;
        double nAB = predAB.tv.count;
        double nBC = predBC.tv.count;

        // Apply the same deduction formula (assuming it applies to predictive strength)
        // A more nuanced model would consider time intervals.
        double sB = nodeB.tv.strength; // P(B) approx
        double sC = nodeC.tv.strength; // P(C) approx
        double sAC;
         if (Math.abs(1.0 - sB) < 1e-9) { sAC = sBC; }
         else if (sB < 1e-9) { sAC = 0.0; }
         else { sAC = sAB * sBC + (1.0 - sAB) * Math.max(0,(sC - sB * sBC)) / (1.0 - sB); }
         sAC = Math.max(0.0, Math.min(1.0, sAC));

        // Combine counts
        double nAC = INFERENCE_CONFIDENCE_DISCOUNT * Math.min(nAB, nBC);

        TruthValue tvAC = new TruthValue(sAC, nAC);
        AttentionValue avAC = predAB.av.infer(predBC.av);

        // TODO: Handle composition of time intervals if PredictiveImplication includes them.

        return addLink(LinkType.PREDICTIVE_IMPLICATION, tvAC, avAC, aId, cId);
    }


    // TODO: Implement Inversion, Temporal rules, HOL rules (unification, instantiation)


    /** Performs periodic housekeeping: forgetting, AV decay. */
    private synchronized void runHousekeeping() {
        long now = System.nanoTime();
        if (now - lastForgettingTime > forgettingIntervalNanos) {
            // System.out.println("Running housekeeping...");
            decayAttentionValues();
            forgetLowImportanceAtoms();
            lastForgettingTime = now;
             // Request GC after significant pruning, helps manage heap pressure
             // System.gc(); // Use cautiously, might impact performance
        }
    }


    /** Decays STI and transfers some to LTI across the knowledge base. */
    private void decayAttentionValues() {
        // System.out.println("Decaying attention values...");
        int count = 0;
        for (Atom atom : knowledgeBase.values()) {
            atom.av.decay(FORGETTING_RATE, STI_TO_LTI_TRANSFER_RATE);
            count++;
        }
        // System.out.println("Decayed AV for " + count + " atoms.");
    }

    /** Removes atoms with combined importance below a threshold. */
    private void forgetLowImportanceAtoms() {
        // System.out.println("Forgetting low importance atoms...");
        List<String> toRemove = new ArrayList<>();
        for (Atom atom : knowledgeBase.values()) {
            // Protect essential atoms (e.g., core predicates, maybe goal atoms?)
            if (isEssential(atom)) continue;

            if (atom.av.getTotalImportance() < FORGET_PRUNE_THRESHOLD) {
                toRemove.add(atom.id);
            }
        }

        if (!toRemove.isEmpty()) {
            System.out.println("Forgetting " + toRemove.size() + " atoms below threshold " + FORGET_PRUNE_THRESHOLD);
            for (String id : toRemove) {
                removeAtom(id); // Uses the synchronized removeAtom method
            }
            System.out.println("KB size after forgetting: " + knowledgeBase.size());
        }
    }

     /** Determines if an atom should be protected from forgetting. */
     private boolean isEssential(Atom atom) {
         // Example: Protect the current goal, core predicates like "RewardSignal", "Try"
         if (atom.id.equals(currentGoalId)) return true;
         if (atom instanceof Node) {
             String name = ((Node) atom).name;
             if (name.equals("RewardSignal") || name.equals("Try") /*|| name.startsWith("Action_") */) { // Protect action primitives?
                 return true;
             }
         }
         // TODO: Add more sophisticated criteria (e.g., part of active plan, recently used heavily)
         return false;
     }

    // --- Index Maintenance ---

    private void updateIndicesOnAdd(Atom atom) {
        if (atom instanceof Link) {
            Link link = (Link) atom;
            linksByType.computeIfAbsent(link.type, k -> new ConcurrentHashMap<>()).put(link.id, link);

            // Update linksByTarget index
            if (!link.targets.isEmpty()) {
                // Index by primary target? Or all targets? Let's index by all for flexibility.
                for (String targetId : link.targets) {
                     linksByTarget.computeIfAbsent(targetId, k -> ConcurrentHashMap.newKeySet()).add(link.id);
                }
                 // Update linksBySource (assuming first target is source for directed links)
                 if (link.type.isDirected() && link.targets.size() > 0) {
                     linksBySource.computeIfAbsent(link.targets.get(0), k -> ConcurrentHashMap.newKeySet()).add(link.id);
                 }
            }
        }
        // No specific index for Nodes currently, beyond the main KB map.
    }

     private void updateIndicesOnRemove(Atom atom) {
        if (atom instanceof Link) {
            Link link = (Link) atom;
            ConcurrentMap<String, Link> typeMap = linksByType.get(link.type);
            if (typeMap != null) {
                typeMap.remove(link.id);
                if (typeMap.isEmpty()) {
                    linksByType.remove(link.type);
                }
            }

            // Update linksByTarget
            if (!link.targets.isEmpty()) {
                 for (String targetId : link.targets) {
                     Set<String> targetSet = linksByTarget.get(targetId);
                     if (targetSet != null) {
                         targetSet.remove(link.id);
                         if (targetSet.isEmpty()) {
                             linksByTarget.remove(targetId);
                         }
                     }
                 }
                  // Update linksBySource
                 if (link.type.isDirected() && link.targets.size() > 0) {
                      String sourceId = link.targets.get(0);
                      Set<String> sourceSet = linksBySource.get(sourceId);
                     if (sourceSet != null) {
                         sourceSet.remove(link.id);
                         if (sourceSet.isEmpty()) {
                             linksBySource.remove(sourceId);
                         }
                     }
                 }
            }
        }
        // No node-specific indices to update.
    }

    /** Helper to find links, using indices. */
    private Stream<Link> findLinks(LinkType type, String targetId) {
        Stream<Link> stream;
        if (type != null && targetId != null) {
            // Find specific type targeting specific ID
            Set<String> targetLinks = linksByTarget.get(targetId);
            Map<String, Link> typeLinks = linksByType.get(type);
            if (targetLinks == null || typeLinks == null) return Stream.empty();
            stream = targetLinks.stream()
                               .map(linkId -> typeLinks.get(linkId))
                               .filter(Objects::nonNull)
                               .filter(link -> link.targets.contains(targetId)); // Double check target
        } else if (type != null) {
            // Find all links of a specific type
            Map<String, Link> typeMap = linksByType.get(type);
            if (typeMap == null) return Stream.empty();
            stream = typeMap.values().stream();
        } else if (targetId != null) {
            // Find all links targeting specific ID
            Set<String> targetLinks = linksByTarget.get(targetId);
            if (targetLinks == null) return Stream.empty();
            stream = targetLinks.stream()
                               .map(linkId -> knowledgeBase.get(linkId)) // Get from main KB
                               .filter(Objects::nonNull)
                               .filter(atom -> atom instanceof Link)
                               .map(atom -> (Link) atom)
                               .filter(link -> link.targets.contains(targetId)); // Double check target
        } else {
            // Find all links (use main KB)
            stream = knowledgeBase.values().stream()
                               .filter(atom -> atom instanceof Link)
                               .map(atom -> (Link) atom);
        }
        return stream;
    }


    // ========================================================================
    // === Inner Classes (Atoms, TV, AV, etc.) ================================
    // ========================================================================


    /** Base class for all knowledge elements (Nodes and Links). */
    public abstract static class Atom {
        public final String id; // Unique identifier, typically derived from content
        public TruthValue tv;
        public AttentionValue av;
        public final long creationTimestamp;

        protected Atom(String id, TruthValue tv, AttentionValue av) {
             // ID generation is handled by subclasses before calling super OR explicitly passed
             this.id = Objects.requireNonNull(id, "Atom ID cannot be null");
             this.tv = Objects.requireNonNull(tv, "TruthValue cannot be null");
             this.av = Objects.requireNonNull(av, "AttentionValue cannot be null");
             this.creationTimestamp = System.nanoTime();
        }

         // Constructor used when ID is generated by subclass after fields are set
         protected Atom(TruthValue tv, AttentionValue av) {
            this.id = generateId(); // Call subclass implementation
             this.tv = Objects.requireNonNull(tv, "TruthValue cannot be null");
             this.av = Objects.requireNonNull(av, "AttentionValue cannot be null");
             this.creationTimestamp = System.nanoTime();
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
            return id + " " + tv + " " + av;
        }

        /** Generate a canonical ID based on the Atom's content. Must be implemented by subclasses. */
        protected abstract String generateId();
    }

    /** Represents concepts, entities, constants. */
    public static class Node extends Atom {
        public final String name; // Human-readable name/symbol

        public Node(String name, TruthValue tv, AttentionValue av) {
            super(generateId(name), tv, av); // Generate ID first
            this.name = name;
        }

        @Override
        protected String generateId() {
             return generateId(this.name); // Use static version
        }

        public static String generateId(String nodeName) {
             return "Node(" + nodeName + ")";
        }

        @Override
        public String toString() {
            // More concise representation
            return name + " " + tv + " " + av;
        }
    }

    /** Represents variables used in higher-order logic constructs. */
    public static class VariableNode extends Node {
        public VariableNode(String name, TruthValue tv, AttentionValue av) {
            super(name, tv, av); // Name should follow convention, e.g., "$var" or "Var:name"
        }

         @Override
         protected String generateId() {
             return generateId(this.name);
         }

        public static String generateId(String varName) {
             // Ensure variable names are distinct from concept nodes
             return "Var(" + varName + ")";
        }
    }

     /** Represents time points or intervals. */
     public static class TimeNode extends Node {
         public final long timeValue; // Can represent timestamp or simulation step

         public TimeNode(long time, TruthValue tv, AttentionValue av) {
             super(String.valueOf(time), tv, av); // Name is the time value string
             this.timeValue = time;
         }

         @Override
         protected String generateId() {
             return generateId(this.timeValue);
         }

         public static String generateId(long timeValue) {
              return "Time(" + timeValue + ")";
         }
     }


    /** Represents relationships between Atoms. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets; // Ordered list of Atom IDs

        public Link(LinkType type, List<String> targets, TruthValue tv, AttentionValue av) {
            // ID generation needs type and targets. Call super *after* these are set.
            this.type = type;
            // Ensure canonical order for ID generation for commutative types
            this.targets = type.isCommutative()
                         ? targets.stream().sorted().collect(Collectors.toList())
                         : new ArrayList<>(targets); // Keep order for non-commutative

             // Call super constructor that calls generateId()
             super(tv, av);
        }

        @Override
        protected String generateId() {
             // Use static method for consistency
             return generateIdStatic(this.type, this.targets);
        }

        /** Static method to generate ID, usable before object creation */
        public static String generateIdStatic(LinkType type, List<String> targetIds) {
            List<String> idsForHash = type.isCommutative()
                                     ? targetIds.stream().sorted().collect(Collectors.toList())
                                     : targetIds;
             // Use a more robust representation than simple toString()
             StringBuilder sb = new StringBuilder();
             sb.append(type.name()).append("(");
             boolean first = true;
             for(String id : idsForHash) {
                 if (!first) sb.append(",");
                 sb.append(id);
                 first = false;
             }
             sb.append(")");
             return sb.toString();
        }


        @Override
        public String toString() {
            return generateIdStatic(type, targets) + " " + tv + " " + av;
        }
    }

    /** Defines the types of relationships (Links) representable. */
    public enum LinkType {
        // --- Core Logical / Structural ---
        INHERITANCE(true, false), // Probabilistic Implication P(Target|Source) - Directed
        SIMILARITY(true, true),   // Symmetric Association - Undirected
        EVALUATION(true, false),  // Predicate Application: Predicate(Arg1, Arg2...) - Directed (Predicate -> Args)
        EXECUTION(true, false),   // Action/Schema Execution: Execute(Action, Args...) - Directed
        AND(false, true),         // Logical Conjunction - Undirected
        OR(false, true),          // Logical Disjunction - Undirected
        NOT(true, false),         // Logical Negation (typically unary) - Directed?

        // --- Temporal Logic (Event Calculus inspired) ---
        SEQUENCE(false, false),   // Temporal Sequence A then B - Directed, Non-commutative
        PREDICTIVE_IMPLICATION(true, false), // A predicts B (A happens -> B happens later) - Directed
        EVENTUAL_PREDICTIVE_IMPLICATION(true, false), // If A persists, B eventually happens - Directed
        INITIATE(true, false),    // Action/Event initiates Fluent/State at Time - Directed (Action -> Fluent, Time)
        TERMINATE(true, false),   // Action/Event terminates Fluent/State at Time - Directed
        HOLDS_AT(true, false),    // Fluent/State holds at TimePoint - Directed (Fluent -> Time)
        HOLDS_THROUGHOUT(true, false), // Fluent/State holds during TimeInterval - Directed (Fluent -> Interval)
        PERSISTENT(true, false), // Property: Fluent tends to persist (Unary link on Fluent)
        // Temporal Conjunctions
        SIM_AND(false, true),     // Simultaneous AND (A and B hold during same interval) - Undirected
        SIM_OR(false, true),      // Simultaneous OR (A or B hold during same interval) - Undirected

        // --- Higher-Order Logic ---
        FOR_ALL(true, false),     // Universal Quantifier: ForAll(VarList, Scope) - Directed
        EXISTS(true, false),      // Existential Quantifier: Exists(VarList, Scope) - Directed
        VARIABLE_SCOPE(true, false), // Defines scope for variables (e.g. lambda)
        EQUIVALENCE(true, true); // Stronger symmetric relationship

        private final boolean directed;
        private final boolean commutative; // Does order of targets matter for ID generation/semantics?

        LinkType(boolean directed, boolean commutative) {
            this.directed = directed;
            this.commutative = commutative;
        }

        public boolean isDirected() { return directed; }
        public boolean isCommutative() { return commutative; }
    }

    /** Represents uncertain truth: strength and count (evidence amount). */
    public static class TruthValue {
        public static final TruthValue DEFAULT = new TruthValue(0.5, 0.0); // Max ignorance
        public static final TruthValue CERTAIN = new TruthValue(1.0, 100.0); // High certainty (adjust count as needed)
        public static final TruthValue IMPOSSIBLE = new TruthValue(0.0, 100.0); // High certainty of falsehood

        public final double strength; // Probability-like value [0, 1]
        public final double count;    // Amount of evidence (non-negative)

        public TruthValue(double strength, double count) {
            this.strength = Math.max(0.0, Math.min(1.0, strength));
            this.count = Math.max(0.0, count);
        }

        /** Confidence calculation: c = N / (N + k). */
        public double getConfidence() {
            if (count == 0) return 0.0;
            return count / (count + DEFAULT_EVIDENCE_SENSITIVITY);
        }

        /** Revision rule: Weighted average based on counts. */
        public TruthValue merge(TruthValue other) {
            if (other == null) return this;
            double totalCount = this.count + other.count;
            if (totalCount < 1e-9) { // Avoid division by zero if both counts are effectively zero
                return new TruthValue((this.strength + other.strength) / 2.0, 0.0); // Average strength, zero count
            }
            double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new TruthValue(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("<s=%.3f, c=%.2f, w=%.3f>", strength, count, getConfidence());
        }
    }

    /** Represents Attention Value: Short-Term Importance (STI) and Long-Term Importance (LTI). */
    public static class AttentionValue {
        public static final AttentionValue DEFAULT = new AttentionValue(DEFAULT_INITIAL_STI, DEFAULT_INITIAL_LTI);

        private volatile double sti; // Short-term importance (decays quickly)
        private volatile double lti; // Long-term importance (decays slowly, accumulates STI)

        public AttentionValue(double sti, double lti) {
            this.sti = Math.max(0.0, sti);
            this.lti = Math.max(0.0, lti);
        }

        public synchronized double getSTI() { return sti; }
        public synchronized double getLTI() { return lti; }

        /** Combined importance heuristic (simple sum for now). */
        public synchronized double getTotalImportance() {
            return sti + lti;
        }

        /** Increase STI when atom is accessed/used. */
        public synchronized void bumpSTI(double amount) {
            this.sti = Math.min(1.0, this.sti + amount); // Cap STI at 1.0? Or allow higher?
        }

        /** Decay STI, transfer some to LTI. */
        public synchronized void decay(double decayRate, double transferRate) {
            double transferred = this.sti * transferRate;
            this.sti = this.sti * (1.0 - decayRate - transferRate);
            this.lti = this.lti * (1.0 - (decayRate * 0.1)) + transferred; // LTI decays much slower

            // Ensure non-negative
            this.sti = Math.max(0.0, this.sti);
            this.lti = Math.max(0.0, this.lti);
        }

        /** Combine AV from premises during inference (heuristic). */
        public AttentionValue infer(AttentionValue other) {
             // Simple heuristic: average LTI, max STI? Or sum decayed STI?
             double newSTI = Math.max(this.sti, other.sti) * (1.0 - FORGETTING_RATE); // Inherit max decayed STI
             double newLTI = (this.lti + other.lti) / 2.0; // Average LTI
             return new AttentionValue(newSTI, newLTI);
        }

         /** Combine AV during revision (heuristic). */
         public AttentionValue merge(AttentionValue other) {
             if (other == null) return this;
             // Weighted average based on total importance? Or simple average/max?
             double totalImportance = this.getTotalImportance() + other.getTotalImportance();
             double mergedSTI = totalImportance > 1e-9 ? (this.sti * this.getTotalImportance() + other.sti * other.getTotalImportance()) / totalImportance : (this.sti + other.sti) / 2.0;
             double mergedLTI = totalImportance > 1e-9 ? (this.lti * this.getTotalImportance() + other.lti * other.getTotalImportance()) / totalImportance : (this.lti + other.lti) / 2.0;
             // Or just sum STI and average LTI? Let's try weighted average for now.
             return new AttentionValue(mergedSTI, mergedLTI);
         }


        @Override
        public String toString() {
            return String.format("[sti=%.3f, lti=%.3f]", sti, lti);
        }
    }

    // ========================================================================
    // === Interfaces for Perception/Action/Environment =======================
    // ========================================================================

    /** Maps external system state to Atoms in the PLN knowledge base. */
    @FunctionalInterface
    public interface PerceptionMapper {
        /**
         * Processes the external state, creates/updates relevant Atoms in the PLN,
         * and returns the ID of the primary Atom representing the overall current state.
         *
         * @param externalState The raw state data from the environment/system.
         * @param pln The ProbabilisticLogicNetwork instance to update.
         * @return The ID of the Atom representing the perceived state.
         */
        String mapStateToAtomId(Object externalState, ProbabilisticLogicNetwork pln);
    }

    /** Executes actions represented by PLN Links in an external system. */
    @FunctionalInterface
    public interface ActionExecutor {
        /**
         * Interprets the action Link and triggers the corresponding action in the
         * external environment/system.
         *
         * @param actionAtom The Link representing the action to perform (e.g., ExecutionLink).
         * @param pln The ProbabilisticLogicNetwork instance (e.g., to access atom details).
         * @return True if the action was successfully initiated, false otherwise.
         */
        boolean executeActionAtom(Link actionAtom, ProbabilisticLogicNetwork pln);
    }

    /** Abstract representation of the external world the agent interacts with. */
    public interface Environment {
        /** Get the current perceivable state. */
        Object getCurrentState();
        /** Get the reward obtained since the last action. */
        double getReward();
        /** Check if the environment simulation should terminate. */
        boolean isTerminated();
        // Note: Environment doesn't need performAction; ActionExecutor handles that.
        // Note: Environment doesn't need getAvailableActions; Planner discovers actions via PLN links.
    }

    // ========================================================================
    // === Default Implementations and Example Usage ==========================
    // ========================================================================

     /** Default mapper creating simple state nodes based on map features. */
     public static class DefaultPerceptionMapper implements PerceptionMapper {
         @Override
         public String mapStateToAtomId(Object externalState, ProbabilisticLogicNetwork pln) {
             if (!(externalState instanceof Map)) {
                 throw new IllegalArgumentException("DefaultPerceptionMapper expects a Map state.");
             }
             Map<?, ?> stateMap = (Map<?, ?>) externalState;
             List<String> featureNodeIds = new ArrayList<>();
             TruthValue observedTV = new TruthValue(1.0, 10.0); // High confidence for observation

             for (Map.Entry<?, ?> entry : stateMap.entrySet()) {
                 String featureName = entry.getKey().toString();
                 Object featureValue = entry.getValue();
                 // Create node for the feature itself
                 // Node featureNode = pln.getOrCreateNode("Feature_" + featureName);

                 // Create node for the specific value if it's significant (e.g., boolean true, or high numeric value)
                 boolean valueIsTrue = (featureValue instanceof Boolean && (Boolean) featureValue) ||
                                       (featureValue instanceof Number && ((Number)featureValue).doubleValue() > 0.5);

                 if (valueIsTrue) {
                     String valueNodeName = featureName; // Simplified: Feature name represents true state
                     Node valueNode = pln.getOrCreateNode(valueNodeName);
                     // Update the node's truth value based on observation
                     pln.addAtom(new Node(valueNodeName, observedTV, AttentionValue.DEFAULT)); // Use addAtom for revision
                     featureNodeIds.add(valueNode.id);
                 }
                 // Optionally represent false values with low strength or NOT links
             }

             // Create a composite state Atom (e.g., an AND link or a dedicated State node)
             if (featureNodeIds.isEmpty()) {
                 Node nullState = pln.getOrCreateNode("State_Null");
                 pln.addAtom(new Node("State_Null", observedTV, AttentionValue.DEFAULT));
                 return nullState.id;
             }

             Collections.sort(featureNodeIds); // Canonical ID
             // Represent state as a specific node ID based on combined features
             String stateName = "State(" + String.join("&", featureNodeIds.stream()
                     .map(id -> pln.getAtom(id).id) // Use the canonical ID from the atom
                     .collect(Collectors.toList())) + ")";

             Node stateNode = pln.getOrCreateNode(stateName);
             pln.addAtom(new Node(stateNode.name, observedTV, AttentionValue.DEFAULT)); // Revise state node TV/AV
             return stateNode.id;
         }
     }

    /** Example Environment: Simple Binary World */
    public static class SimpleBinaryWorld implements Environment {
        boolean isStateA = true;
        int steps = 0;
        final int maxSteps = 20;
        String lastAction = "None";

        @Override
        public Object getCurrentState() {
            Map<String, Boolean> state = new HashMap<>();
            state.put("IsStateA", isStateA);
            state.put("IsStateB", !isStateA);
            return state;
        }

        @Override
        public double getReward() {
            // Reward for being in state B, small penalty otherwise
            return !isStateA ? 1.0 : -0.1;
        }

        @Override
        public boolean isTerminated() {
            return steps >= maxSteps;
        }

        // Action execution logic - needs to be triggered by ActionExecutor
        public void toggleState() {
            isStateA = !isStateA;
             System.out.println("  World: State toggled. Now in " + (isStateA ? "A" : "B"));
             lastAction = "Toggle";
             steps++;
        }
        public void doNothing() {
             System.out.println("  World: Did nothing. State remains " + (isStateA ? "A" : "B"));
             lastAction = "Nothing";
             steps++;
        }
        public String getLastAction() { return lastAction; }
    }

     /** Example Action Executor for the SimpleBinaryWorld */
     public static class SimpleWorldActionExecutor implements ActionExecutor {
         private final SimpleBinaryWorld world;

         public SimpleWorldActionExecutor(SimpleBinaryWorld world) {
             this.world = world;
         }

         @Override
         public boolean executeActionAtom(Link actionAtom, ProbabilisticLogicNetwork pln) {
              // Expected action format: ExecutionLink(Try, ActionNode)
              if (actionAtom.type == LinkType.EXECUTION && actionAtom.targets.size() == 2) {
                  String actionNodeId = actionAtom.targets.get(1); // Second target is the action itself
                  Atom actionNode = pln.getAtom(actionNodeId);
                  if (actionNode instanceof Node) {
                      String actionName = ((Node) actionNode).name;
                       System.out.println("Executor: Received action command: " + actionName);
                      if ("Action_Toggle".equals(actionName)) {
                          world.toggleState();
                          return true;
                      } else if ("Action_DoNothing".equals(actionName)) {
                           world.doNothing();
                           return true;
                      } else {
                          System.err.println("Executor: Unknown action name: " + actionName);
                          return false;
                      }
                  }
              }
              System.err.println("Executor: Invalid action atom format: " + actionAtom);
              return false;
         }
     }


    // --- Main Method for Example ---
    public static void main(String[] args) {
        System.out.println("--- Enhanced PLN Demo ---");
        ProbabilisticLogicNetwork pln = new ProbabilisticLogicNetwork();

        // --- Setup Environment and Agent Components ---
        SimpleBinaryWorld world = new SimpleBinaryWorld();
        pln.setPerceptionMapper(new DefaultPerceptionMapper()); // Use default map-based mapper
        pln.setActionExecutor(new SimpleWorldActionExecutor(world));

        // --- Define Actions in PLN ---
        Node toggleAction = pln.getOrCreateNode("Action_Toggle");
        Node nothingAction = pln.getOrCreateNode("Action_DoNothing");
        // Optional: Define execution links if planner needs them explicitly
        pln.findOrCreateExecutionLink(toggleAction);
        pln.findOrCreateExecutionLink(nothingAction);


        // --- Define Goal in PLN ---
        // Goal: Be in State B. Represented by the node for the feature "IsStateB".
        Node goalNode = pln.getOrCreateNode("IsStateB");
        pln.setCurrentGoal(goalNode.id); // Set the goal for the agent

        // --- Run Agent Loop ---
        pln.runAgentLoop(world, 15); // Run for 15 steps

        // --- Inspect Results ---
        System.out.println("\n--- Final PLN State ---");
        System.out.println("KB Size: " + pln.knowledgeBase.size());

        System.out.println("\nGoal Atom ("+goalNode.id+"): " + pln.getAtom(goalNode.id));

        // Check learned predictive links involving reward
        System.out.println("\nLinks predicting reward:");
        Node rewardNode = pln.getOrCreateNode("RewardSignal");
         pln.findLinks(LinkType.PREDICTIVE_IMPLICATION, rewardNode.id).forEach(link -> {
             if(link.targets.size() == 2 && link.targets.get(1).equals(rewardNode.id)) {
                 String causeId = link.targets.get(0);
                 System.out.println("  " + causeId + " => Reward : " + link.tv + " " + link.av);
             }
         });


        // Example Query: What state does Toggle lead to from StateA?
        System.out.println("\nQuery: Result of Action_Toggle from State(Node(IsStateA))?");
        Node stateANode = pln.getOrCreateNode("IsStateA");
        Node stateAComposite = pln.getOrCreateNode("State(Node(IsStateA))"); // Get the composite state node
        Link toggleExec = pln.findOrCreateExecutionLink(toggleAction);

        // Create the sequence link representing the action in context
        Link sequence = new Link(LinkType.SEQUENCE, Arrays.asList(stateAComposite.id, toggleExec.id), TruthValue.DEFAULT, AttentionValue.DEFAULT); // Use the exec link ID
        // Find predictive implications starting from this sequence
         pln.findLinks(LinkType.PREDICTIVE_IMPLICATION, null)
            .filter(link -> link.targets.size() == 2 && link.targets.get(0).equals(sequence.id))
            .forEach(link -> {
                System.out.println("  Predicted outcome: " + link.targets.get(1) + " with TV " + link.tv);
            });

        // Run forgetting manually to test
        System.out.println("\nRunning manual forgetting cycle...");
        pln.decayAttentionValues();
        pln.forgetLowImportanceAtoms();
        System.out.println("KB Size after manual forgetting: " + pln.knowledgeBase.size());
    }
}