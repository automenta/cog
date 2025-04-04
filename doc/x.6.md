import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
* Enhanced Probabilistic Logic Networks (PLN) Engine.
*
* This class provides a significantly enhanced implementation of PLN concepts,
* integrating features for attention, forgetting, advanced temporal/causal reasoning,
* higher-order logic, improved planning, a flexible perception/action API,
* and scalability considerations, all within a single, self-documenting class.
*
* Key Enhancements based on Requirements:
* 1.  **Attention & Forgetting:** Atoms possess Short-Term Importance (STI) and Long-Term Importance (LTI).
*     A forgetting mechanism periodically removes low-importance atoms to manage memory (prevent OOM).
* 2.  **Perception/Action API:** A developer-friendly `Environment` interface allows interfacing
*     with arbitrary systems, using PLN Atoms for state representation and actions.
* 3.  **Planning:** Enhanced backward chaining (`planToActionSequence`) searches for action sequences
*     to achieve goals, leveraging temporal and causal links.
* 4.  **Temporal Logic (Event Calculus):** Incorporates `INITIATES`, `TERMINATES`, `HOLDS_AT`,
*     `PREDICTIVE_IMPLICATION`, `SEQUENCE`, etc., based on the provided text, enabling richer temporal reasoning.
*     Includes `TimeSpec` for representing time points and intervals.
* 5.  **Higher-Order Logic (HOL):** Supports `VariableNode`, `ForAllLink`, `ExistsLink`, and unification
*     within inference rules for more expressive knowledge representation and reasoning.
* 6.  **Scalability:** Implements basic indexing (by type, target) for faster premise lookup.
*     Forward chaining uses importance heuristics for selecting inferences. Backward chaining prioritizes relevant subgoals.
*
* Design Principles Retained/Emphasized:
* -   **Atom-Centric:** Knowledge graph of Nodes and Links.
* -   **Probabilistic:** Simple `TruthValue` (strength, count), probabilistic rules.
* -   **Core Inference:** Deduction, Abduction (via Inversion), Revision, Induction (implicit via pattern mining).
* -   **Modularity:** Logical separation of concerns (KB, Inference, Agent Control) within the single class structure.
* -   **Extensibility:** Designed to accommodate further PLN features.
* -   **Self-Documentation:** Clear naming, structure, and Javadoc minimize external documentation needs.
      */
      public class EnhancedPLN {

// --- Configuration ---
private static final double DEFAULT_EVIDENCE_SENSITIVITY = 1.0; // k in confidence = N / (N + k)
private static final double INFERENCE_CONFIDENCE_DISCOUNT_FACTOR = 0.9; // Confidence reduction per inference step
private static final double MIN_CONFIDENCE_THRESHOLD = 0.01; // Minimum confidence to consider an atom "true enough"
private static final double MIN_IMPORTANCE_THRESHOLD = 0.01; // STI threshold for forgetting
private static final double STI_DECAY_RATE = 0.1; // Decay factor for STI per cycle/access
private static final double LTI_UPDATE_RATE = 0.05; // How much STI contributes to LTI
private static final double FORGETTING_CYCLE_INTERVAL_MS = 10000; // How often to run the forgetting cycle
private static final int MAX_KB_SIZE_BEFORE_FORGET = 10000; // Trigger forgetting if KB exceeds this size
private static final int FORWARD_CHAIN_BATCH_SIZE = 50; // Max inferences per forward chain step based on importance
private static final String VARIABLE_PREFIX = "$"; // Prefix for variable names

// --- Core Components ---
private final KnowledgeBase knowledgeBase;
private final InferenceEngine inferenceEngine;
private final AgentController agentController;
private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

// --- Constructor ---
public EnhancedPLN() {
this.knowledgeBase = new KnowledgeBase();
this.inferenceEngine = new InferenceEngine(this.knowledgeBase);
this.agentController = new AgentController(this.knowledgeBase, this.inferenceEngine);
// Schedule periodic forgetting task
scheduler.scheduleAtFixedRate(knowledgeBase::forgetLowImportanceAtoms,
FORGETTING_CYCLE_INTERVAL_MS, FORGETTING_CYCLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
}

// --- Public API ---

/** Adds or updates an Atom, performing revision and updating importance. */
public Atom addAtom(Atom atom) {
return knowledgeBase.addAtom(atom);
}

/** Gets an Atom by its ID, updating its importance. */
public Optional<Atom> getAtom(String id) {
return knowledgeBase.getAtom(id);
}

/** Gets a Node by name, creating it if absent. */
public Node getOrCreateNode(String name) {
return knowledgeBase.getOrCreateNode(name, TruthValue.DEFAULT, 1.0); // Default importance for creation
}

/** Creates or retrieves a VariableNode. */
public VariableNode getOrCreateVariableNode(String name) {
return knowledgeBase.getOrCreateVariableNode(name);
}

/** Creates a Link and adds/revises it in the KB. */
public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, Atom... targets) {
return knowledgeBase.addLink(type, tv, initialSTI, targets);
}

/** Creates a Link and adds/revises it in the KB using target IDs. */
public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, String... targetIds) {
return knowledgeBase.addLink(type, tv, initialSTI, targetIds);
}

/** Performs forward chaining inference based on importance heuristics. */
public void forwardChain(int maxSteps) {
inferenceEngine.forwardChain(maxSteps);
}

/**
    * Performs backward chaining to query the truth value of a target Atom pattern.
    * Supports variables and unification.
    *
    * @param queryAtom The Atom pattern to query (can contain variables).
    * @param maxDepth Maximum recursion depth.
    * @return A list of bindings (variable assignments) and the corresponding inferred Atom for each successful proof path.
      */
      public List<QueryResult> query(Atom queryAtom, int maxDepth) {
      return inferenceEngine.backwardChain(queryAtom, maxDepth);
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
      return inferenceEngine.planToActionSequence(goalAtom, maxPlanDepth, maxSearchDepth);
      }

/** Runs the agent's perceive-reason-act cycle in a given environment. */
public void runAgent(Environment environment, Atom goal, int maxSteps) {
agentController.run(environment, goal, maxSteps);
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
System.out.println("EnhancedPLN scheduler shut down.");
}

// --- Getters for internal components (optional, for inspection/debugging) ---
public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
public InferenceEngine getInferenceEngine() { return inferenceEngine; }
public AgentController getAgentController() { return agentController; }

// --- Main Example Runner ---
public static void main(String[] args) {
EnhancedPLN pln = new EnhancedPLN();
try {
pln.runEnhancedExample();
} finally {
pln.shutdown(); // Ensure scheduler is stopped
}
}

// ========================================================================
// --- Inner Class: Knowledge Base (Handles Storage, Indexing, Forgetting) ---
// ========================================================================
public static class KnowledgeBase {
private final ConcurrentMap<String, Atom> atoms = new ConcurrentHashMap<>();
// --- Indices for Scalability ---
private final ConcurrentMap<Link.LinkType, ConcurrentSkipListSet<String>> linksByType = new ConcurrentHashMap<>();
private final ConcurrentMap<String, ConcurrentSkipListSet<String>> linksByTarget = new ConcurrentHashMap<>(); // TargetID -> Set<LinkID>

     /** Adds or updates an Atom, handling revision and index updates. */
     public synchronized Atom addAtom(Atom atom) {
         Atom result = atoms.compute(atom.id, (id, existing) -> {
             if (existing == null) {
                 atom.updateImportance(1.0); // Boost STI on initial add
                 return atom;
             } else {
                 TruthValue revisedTV = existing.tv.merge(atom.tv);
                 // Update importance based on revision novelty/confirmation
                 double importanceBoost = calculateImportanceBoost(existing.tv, atom.tv, revisedTV);
                 existing.tv = revisedTV;
                 existing.updateImportance(importanceBoost);
                 // System.out.println("Revised: " + existing.id + " -> " + existing.tv + " STI: " + existing.shortTermImportance);
                 return existing;
             }
         });

         // Update indices if it's a Link
         if (result instanceof Link) {
             updateIndices((Link) result);
         }

         // Trigger forgetting if KB grows too large
         if (atoms.size() > MAX_KB_SIZE_BEFORE_FORGET) {
              CompletableFuture.runAsync(this::forgetLowImportanceAtoms); // Run async
         }
         return result;
     }

     /** Retrieves an Atom, updating its STI. */
     public Optional<Atom> getAtom(String id) {
         Atom atom = atoms.get(id);
         if (atom != null) {
             atom.updateImportance(0.1); // Small boost on access
         }
         return Optional.ofNullable(atom);
     }

     /** Gets a Node by name, creating if absent. */
     public Node getOrCreateNode(String name, TruthValue tv, double initialSTI) {
         String nodeId = Node.generateIdFromName(name);
         return (Node) atoms.computeIfAbsent(nodeId, id -> {
             Node newNode = new Node(name, tv);
             newNode.shortTermImportance = initialSTI; // Set initial importance
             newNode.longTermImportance = initialSTI * LTI_UPDATE_RATE;
             return newNode;
         });
     }

     /** Gets or creates a VariableNode. */
     public VariableNode getOrCreateVariableNode(String name) {
         String varId = VariableNode.generateIdFromName(name);
         return (VariableNode) atoms.computeIfAbsent(varId, id -> new VariableNode(name));
     }

     /** Creates and adds/revises a Link. */
     public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, Atom... targets) {
         List<String> targetIds = Arrays.stream(targets).map(a -> a.id).collect(Collectors.toList());
         Link link = new Link(type, targetIds, tv, null); // TimeSpec initially null
         link.shortTermImportance = initialSTI;
         link.longTermImportance = initialSTI * LTI_UPDATE_RATE;
         return (Link) addAtom(link);
     }

     /** Creates and adds/revises a Link using target IDs. */
      public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, String... targetIds) {
         Link link = new Link(type, Arrays.asList(targetIds), tv, null);
         link.shortTermImportance = initialSTI;
         link.longTermImportance = initialSTI * LTI_UPDATE_RATE;
         return (Link) addAtom(link);
     }

     /** Removes an atom and updates indices. */
     private synchronized void removeAtom(String id) {
         Atom removed = atoms.remove(id);
         if (removed instanceof Link) {
             removeIndices((Link) removed);
         }
     }

     /** Updates indices for a given link. */
     private void updateIndices(Link link) {
         linksByType.computeIfAbsent(link.type, k -> new ConcurrentSkipListSet<>()).add(link.id);
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
     }

     /** Retrieves links of a specific type. */
     public Stream<Link> getLinksByType(Link.LinkType type) {
         return Optional.ofNullable(linksByType.get(type)).orElse(Collections.emptySet())
                 .stream()
                 .map(this::getAtom)
                 .filter(Optional::isPresent).map(Optional::get)
                 .map(a -> (Link)a); // Assumes IDs point to valid Links
     }

     /** Retrieves links that have a specific Atom as a target. */
     public Stream<Link> getLinksByTarget(String targetId) {
         return Optional.ofNullable(linksByTarget.get(targetId)).orElse(Collections.emptySet())
                 .stream()
                 .map(this::getAtom)
                 .filter(Optional::isPresent).map(Optional::get)
                 .map(a -> (Link)a);
     }

     /** Retrieves all atoms currently in the knowledge base. */
      public Collection<Atom> getAllAtoms() {
         return atoms.values();
     }

     /** Implements the forgetting mechanism. */
     public synchronized void forgetLowImportanceAtoms() {
         long startTime = System.currentTimeMillis();
         int initialSize = atoms.size();
         List<String> toRemove = new ArrayList<>();

         atoms.values().forEach(atom -> {
             atom.decayImportance();
             // Simple threshold based forgetting - more sophisticated logic could consider dependencies
             if (atom.shortTermImportance < MIN_IMPORTANCE_THRESHOLD && atom.longTermImportance < MIN_IMPORTANCE_THRESHOLD * 2) {
                // Check if it's a critical structural atom (e.g., core predicate) - simplistic check
                boolean isCritical = atom instanceof Node && ( ((Node)atom).name.equals("Reward") || ((Node)atom).name.startsWith("Action_") || ((Node)atom).name.startsWith("Predicate_"));
                if (!isCritical) {
                    toRemove.add(atom.id);
                }
             }
         });

         if (!toRemove.isEmpty()) {
             System.out.printf("Forgetting: Removing %d atoms (below STI<%.3f) from KB size %d...%n",
                     toRemove.size(), MIN_IMPORTANCE_THRESHOLD, initialSize);
             toRemove.forEach(this::removeAtom); // Uses synchronized removeAtom
         }
         long duration = System.currentTimeMillis() - startTime;
          if (!toRemove.isEmpty() || duration > 10) { // Log if something happened or took time
             System.out.printf("Forgetting cycle completed in %d ms. New KB size: %d%n", duration, atoms.size());
         }
     }

     /** Calculates importance boost based on revision novelty/strength change. */
     private double calculateImportanceBoost(TruthValue oldTV, TruthValue newTV, TruthValue revisedTV) {
         double strengthChange = Math.abs(revisedTV.strength - oldTV.strength);
         double confidenceChange = Math.abs(revisedTV.getConfidence() - oldTV.getConfidence());
         // Boost more for significant changes or high-confidence confirmations
         return (strengthChange + confidenceChange) * (1.0 + newTV.getConfidence());
     }
}

// ========================================================================
// --- Inner Class: Inference Engine (Handles Reasoning Rules) ---
// ========================================================================
public static class InferenceEngine {
private final KnowledgeBase kb;

     public InferenceEngine(KnowledgeBase kb) {
         this.kb = kb;
     }

     /** Performs PLN Deduction: (A->B, B->C) => A->C */
     public Optional<Link> deduction(Link linkAB, Link linkBC) {
         if (linkAB == null || linkBC == null ||
             (linkAB.type != Link.LinkType.INHERITANCE && linkAB.type != Link.LinkType.PREDICTIVE_IMPLICATION) ||
             (linkBC.type != Link.LinkType.INHERITANCE && linkBC.type != Link.LinkType.PREDICTIVE_IMPLICATION) ||
             linkAB.targets.size() != 2 || linkBC.targets.size() != 2 ||
             !linkAB.targets.get(1).equals(linkBC.targets.get(0))) {
             return Optional.empty(); // Invalid input or structure mismatch
         }

         String aId = linkAB.targets.get(0);
         String bId = linkAB.targets.get(1); // Common element
         String cId = linkBC.targets.get(1);

         Optional<Atom> nodeAOpt = kb.getAtom(aId);
         Optional<Atom> nodeBOpt = kb.getAtom(bId);
         Optional<Atom> nodeCOpt = kb.getAtom(cId);

         if (!nodeAOpt.isPresent() || !nodeBOpt.isPresent() || !nodeCOpt.isPresent()) {
              System.err.println("Deduction Warning: Missing node(s) A, B, or C.");
             return Optional.empty();
         }

         TruthValue tvAB = linkAB.tv;
         TruthValue tvBC = linkBC.tv;
         TruthValue tvB = nodeBOpt.get().tv;
         TruthValue tvC = nodeCOpt.get().tv;

         // Simplified PLN Deduction Formula (avoids term probabilities if needed)
         // sAC = sAB * sBC + (1 - sAB) * (sC - sB * sBC) / (1 - sB) [Requires P(B), P(C)]
         // Heuristic: sAC = sAB * sBC + 0.5 * (1 - sAB) * (1 - sBC)
         double sAC;
         double sAB = tvAB.strength;
         double sBC = tvBC.strength;
         double sB = tvB.strength;
         double sC = tvC.strength;

          if (Math.abs(1.0 - sB) < 1e-9) { // Avoid division by zero if P(B) is 1
              sAC = sBC; // If B is certain, A->C depends only on B->C given A->B
          } else if (sB < 1e-9) { // Avoid division by zero if P(B) is 0
              sAC = sAB * sBC; // If B is impossible, the second term vanishes
          }
          else {
              // Full formula requiring term probabilities
              sAC = sAB * sBC + (1.0 - sAB) * (sC - sB * sBC) / (1.0 - sB);
          }
         sAC = Math.max(0.0, Math.min(1.0, sAC)); // Clamp result

         // Combine counts using discounting
         double nAC = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * Math.min(tvAB.count, tvBC.count);
         // Factor in confidence of intermediate node B?
         nAC *= tvB.getConfidence();

         TruthValue tvAC = new TruthValue(sAC, nAC);

         // Determine link type (Inheritance if both premises are, otherwise Predictive)
         Link.LinkType resultType = (linkAB.type == Link.LinkType.INHERITANCE && linkBC.type == Link.LinkType.INHERITANCE)
                                    ? Link.LinkType.INHERITANCE
                                    : Link.LinkType.PREDICTIVE_IMPLICATION;

         // Handle TimeSpec for PredictiveImplication
         TimeSpec timeAC = null;
         if (resultType == Link.LinkType.PREDICTIVE_IMPLICATION) {
             timeAC = TimeSpec.add(linkAB.time, linkBC.time);
         }

         Link inferredLink = new Link(resultType, Arrays.asList(aId, cId), tvAC, timeAC);
         inferredLink.updateImportance(linkAB.shortTermImportance * linkBC.shortTermImportance); // Combine premise importance
         return Optional.of(kb.addAtom(inferredLink)).map(a -> (Link)a);
     }

     /** Performs PLN Inversion (Abduction/Bayes): (A->B) => (B->A) */
     public Optional<Link> inversion(Link linkAB) {
         if (linkAB == null ||
            (linkAB.type != Link.LinkType.INHERITANCE && linkAB.type != Link.LinkType.PREDICTIVE_IMPLICATION) ||
             linkAB.targets.size() != 2) {
             return Optional.empty();
         }

         String aId = linkAB.targets.get(0);
         String bId = linkAB.targets.get(1);

         Optional<Atom> nodeAOpt = kb.getAtom(aId);
         Optional<Atom> nodeBOpt = kb.getAtom(bId);

         if (!nodeAOpt.isPresent() || !nodeBOpt.isPresent()) {
              System.err.println("Inversion Warning: Missing node(s) A or B.");
             return Optional.empty();
         }

         TruthValue tvAB = linkAB.tv;
         TruthValue tvA = nodeAOpt.get().tv;
         TruthValue tvB = nodeBOpt.get().tv;

         // Formula: sBA = sAB * sA / sB (Requires P(A), P(B))
         double sBA;
         if (tvB.strength < 1e-9) {
             System.err.println("Inversion Warning: P(B) is near zero for B=" + bId);
             sBA = 0.5; // Max uncertainty if P(B) is zero
             tvAB = new TruthValue(sBA, 0); // Reset evidence
         } else {
             sBA = tvAB.strength * tvA.strength / tvB.strength;
             sBA = Math.max(0.0, Math.min(1.0, sBA)); // Clamp
         }

         // Combine counts using discounting, factor in node confidences
         double nBA = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * tvAB.count * tvA.getConfidence() * tvB.getConfidence();
         TruthValue tvBA = new TruthValue(sBA, nBA);

         // Inverted link type remains the same (Inheritance or Predictive)
         // Inverted TimeSpec for PredictiveImplication
         TimeSpec timeBA = (linkAB.time != null) ? linkAB.time.invert() : null;

         Link inferredLink = new Link(linkAB.type, Arrays.asList(bId, aId), tvBA, timeBA); // Note reversed targets
         inferredLink.updateImportance(linkAB.shortTermImportance);
         return Optional.of(kb.addAtom(inferredLink)).map(a -> (Link)a);
     }

     /** Performs PLN Revision (handled by KnowledgeBase.addAtom) */
     // public Optional<Atom> revision(Atom atom1, Atom atom2) { ... } // Merged into addAtom

     /** Performs PLN Analogy (Placeholder - complex, involves structure mapping) */
     public Optional<Atom> analogy(Atom sourceContext, Atom targetContext, Atom sourceRelation) {
         System.err.println("Analogy inference not implemented yet.");
         // Requires finding structural similarities between contexts and mapping relations.
         return Optional.empty();
     }

     /** Implements basic unification for variables. */
     public Optional<Map<String, String>> unify(Atom pattern, Atom fact, Map<String, String> initialBindings) {
         Map<String, String> bindings = new HashMap<>(initialBindings);
         LinkedList<Atom[]> queue = new LinkedList<>();
         queue.add(new Atom[]{pattern, fact});

         while (!queue.isEmpty()) {
             Atom[] pair = queue.poll();
             Atom p = pair[0];
             Atom f = pair[1];

             // Resolve variables in pattern using current bindings
             while (p instanceof VariableNode && bindings.containsKey(p.id)) {
                 String boundId = bindings.get(p.id);
                 Optional<Atom> boundAtom = kb.getAtom(boundId);
                 if (!boundAtom.isPresent()) return Optional.empty(); // Bound variable refers to non-existent atom
                 p = boundAtom.get();
             }

             // Standard unification cases
             if (p instanceof VariableNode) {
                 if (!p.id.equals(f.id)) { // Avoid binding variable to itself trivially
                     // OCCURS CHECK (simplified: check if variable is in fact's structure - needed for full HOL)
                     // if (containsVariable(f, (VariableNode)p)) return Optional.empty();
                     bindings.put(p.id, f.id);
                 }
             } else if (p.getClass() != f.getClass()) {
                 return Optional.empty(); // Different types cannot unify
             } else if (p instanceof Node && !(p instanceof VariableNode)) { // Constant Nodes
                 if (!p.id.equals(f.id)) return Optional.empty();
             } else if (p instanceof Link) {
                 Link pLink = (Link) p;
                 Link fLink = (Link) f;
                 if (pLink.type != fLink.type || pLink.targets.size() != fLink.targets.size()) {
                     return Optional.empty();
                 }
                 // Recursively unify targets
                 for (int i = 0; i < pLink.targets.size(); i++) {
                      Optional<Atom> pTargetOpt = kb.getAtom(pLink.targets.get(i));
                      Optional<Atom> fTargetOpt = kb.getAtom(fLink.targets.get(i));
                      if (!pTargetOpt.isPresent() || !fTargetOpt.isPresent()) return Optional.empty(); // Target atom missing
                      queue.add(new Atom[]{pTargetOpt.get(), fTargetOpt.get()});
                 }
             }
              // Ignore TruthValue and Importance during unification
         }
         return Optional.of(bindings);
     }


     /** Applies bindings to substitute variables in an Atom pattern. */
     public Optional<Atom> substitute(Atom pattern, Map<String, String> bindings) {
         if (pattern instanceof VariableNode && bindings.containsKey(pattern.id)) {
             return kb.getAtom(bindings.get(pattern.id));
         } else if (pattern instanceof Link) {
             Link link = (Link) pattern;
             List<String> newTargetIds = new ArrayList<>();
             for (String targetId : link.targets) {
                 Optional<Atom> targetAtomOpt = kb.getAtom(targetId);
                 if (!targetAtomOpt.isPresent()) return Optional.empty(); // Target missing

                 Optional<Atom> substitutedTargetOpt = substitute(targetAtomOpt.get(), bindings);
                 if (!substitutedTargetOpt.isPresent()) return Optional.empty(); // Substitution failed for target
                 newTargetIds.add(substitutedTargetOpt.get().id);
             }
             // Create a new Link instance with substituted targets (TV/Importance not copied here)
             // Use getAtom to potentially find an existing identical link after substitution
              Link substitutedLink = new Link(link.type, newTargetIds, link.tv, link.time);
              return kb.getAtom(substitutedLink.id).isPresent() ? kb.getAtom(substitutedLink.id) : Optional.of(substitutedLink);

         } else {
             return Optional.of(pattern); // Nodes (non-variable) or already substituted atoms
         }
     }


     /** Performs forward chaining using importance heuristics. */
     public void forwardChain(int maxSteps) {
         System.out.println("\n--- Starting Forward Chaining (Max Steps: " + maxSteps + ") ---");
         Set<String> inferredInStep = new HashSet<>(); // Track inferences per step

         for (int step = 0; step < maxSteps; step++) {
             inferredInStep.clear();
             int inferencesMadeThisStep = 0;

             // Prioritize potential inferences based on premise importance (STI * Confidence)
             PriorityQueue<PotentialInference> queue = new PriorityQueue<>(
                 Comparator.<PotentialInference, Double>comparing(inf -> inf.importance).reversed()
             );

             // --- Gather potential Deductions ---
             kb.getLinksByType(Link.LinkType.INHERITANCE)
               .forEach(linkAB -> kb.getLinksByTarget(linkAB.targets.get(1)) // Find links B->C where target(AB) = source(BC)
                                   .filter(linkBC -> linkBC.type == Link.LinkType.INHERITANCE && linkBC.targets.get(0).equals(linkAB.targets.get(1)))
                                   .forEach(linkBC -> queue.add(new PotentialInference(InferenceRuleType.DEDUCTION, linkAB, linkBC))));
             kb.getLinksByType(Link.LinkType.PREDICTIVE_IMPLICATION)
               .forEach(linkAB -> kb.getLinksByTarget(linkAB.targets.get(1))
                                   .filter(linkBC -> linkBC.type == Link.LinkType.PREDICTIVE_IMPLICATION && linkBC.targets.get(0).equals(linkAB.targets.get(1)))
                                   .forEach(linkBC -> queue.add(new PotentialInference(InferenceRuleType.DEDUCTION, linkAB, linkBC))));


             // --- Gather potential Inversions ---
              Stream.concat(kb.getLinksByType(Link.LinkType.INHERITANCE), kb.getLinksByType(Link.LinkType.PREDICTIVE_IMPLICATION))
                    .forEach(linkAB -> queue.add(new PotentialInference(InferenceRuleType.INVERSION, linkAB)));


             // --- Gather potential Temporal Inferences (Persistence Example) ---
             // If HoldsAt(F, T1) and Persistent(F) and NOT Terminated(F, T1<t<T2), infer HoldsAt(F, T2)
             kb.getLinksByType(Link.LinkType.HOLDS_AT).forEach(holdsAt -> {
                 // Simplified check: Look for a Persistence link for the fluent
                 String fluentId = holdsAt.targets.get(0);
                 boolean isPersistent = kb.getLinksByTarget(fluentId)
                                          .anyMatch(link -> link.type == Link.LinkType.EVALUATION &&
                                                           link.targets.get(0).equals(kb.getOrCreateNode("Predicate_Persistent", TruthValue.TRUE, 0.1).id)); // Check if Persistent(Fluent) exists
                 if (isPersistent && holdsAt.time != null) {
                     // Placeholder: Would need to check for termination events in the interval
                     // queue.add(new PotentialInference(InferenceRuleType.TEMPORAL_PERSISTENCE, holdsAt));
                 }
             });


             // --- Execute top N inferences based on importance ---
             int processedCount = 0;
             while (!queue.isEmpty() && processedCount < FORWARD_CHAIN_BATCH_SIZE) {
                 PotentialInference potential = queue.poll();
                 String inferenceId = potential.getUniqueId(); // ID based on premises

                 if (!inferredInStep.contains(inferenceId)) {
                     Optional<Link> result = Optional.empty();
                     switch (potential.ruleType) {
                         case DEDUCTION:
                             result = deduction(potential.premises[0], potential.premises[1]);
                             break;
                         case INVERSION:
                             result = inversion(potential.premises[0]);
                             break;
                         // Add cases for other rules (Temporal, etc.)
                     }

                     if (result.isPresent()) {
                         System.out.printf("  FC Step %d: %s -> %s %s (Importance: %.3f)%n",
                                 step + 1, potential.ruleType, result.get().id, result.get().tv, potential.importance);
                         inferencesMadeThisStep++;
                         inferredInStep.add(inferenceId); // Mark premises as used this step
                         if (potential.ruleType == InferenceRuleType.DEDUCTION) {
                             inferredInStep.add(potential.getUniqueIdSwapped()); // Avoid A->B, B->C and B->C, A->B if B=A
                         }
                     }
                     processedCount++;
                 }
             }


             System.out.printf("Step %d completed. Inferences made: %d. Queue size: %d%n",
                     step + 1, inferencesMadeThisStep, queue.size());

             if (inferencesMadeThisStep == 0 && queue.isEmpty()) {
                 System.out.println("No new inferences possible. Stopping Forward Chaining.");
                 break;
             }
              // Decay importance of all atoms slightly after each step
              // kb.getAllAtoms().forEach(Atom::decayImportance); // Optional: Decay even unused atoms
         }
         System.out.println("--- Forward Chaining Finished ---");
     }


     /** Performs backward chaining to find bindings for a query Atom. */
     public List<QueryResult> backwardChain(Atom queryAtom, int maxDepth) {
         return backwardChainRecursive(queryAtom, maxDepth, new HashMap<>(), new HashSet<>());
     }

     private List<QueryResult> backwardChainRecursive(Atom queryAtom, int depth, Map<String, String> bindings, Set<String> visited) {
         String queryId = queryAtom.id + bindings.toString(); // Unique ID for this query state
          // System.out.println("  ".repeat(maxDepth - depth) + "BC: Seeking " + queryAtom.id + " Bindings: " + bindings + " (Depth " + depth + ")");


         if (depth <= 0) {
              // System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Max depth reached.");
             return Collections.emptyList();
         }
         if (visited.contains(queryId)) {
              // System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Cycle detected.");
             return Collections.emptyList();
         }
         visited.add(queryId);

         List<QueryResult> results = new ArrayList<>();

         // 1. Direct Match: Check if a matching atom exists in KB after substitution
         Optional<Atom> substitutedQueryOpt = substitute(queryAtom, bindings);
         if (substitutedQueryOpt.isPresent()) {
             Atom substitutedQuery = substitutedQueryOpt.get();
             Optional<Atom> directMatch = kb.getAtom(substitutedQuery.id);
             if (directMatch.isPresent() && directMatch.get().tv.getConfidence() > MIN_CONFIDENCE_THRESHOLD) {
                  // System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Found direct match: " + directMatch.get().id + " " + directMatch.get().tv);
                 results.add(new QueryResult(bindings, directMatch.get()));
             }
         } else {
              // System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Substitution failed for query.");
              visited.remove(queryId);
              return Collections.emptyList(); // Cannot proceed if substitution fails
         }


         // 2. Inference Rules: Try to derive the query using rules backward
         // --- Try Deduction Backward: Target A->C, seek A->B and B->C ---
         if (queryAtom instanceof Link && ((Link) queryAtom).type == Link.LinkType.INHERITANCE && queryAtom.targets.size() == 2) {
             Link targetAC = (Link) queryAtom;
             String targetA_id = targetAC.targets.get(0);
             String targetC_id = targetAC.targets.get(1);

             // Iterate through all nodes as potential intermediate B
             for (Atom potentialB : kb.getAllAtoms()) {
                 if (potentialB instanceof Node && !(potentialB instanceof VariableNode)) {
                     String potentialB_id = potentialB.id;
                     // Create premise patterns: A->B and B->C
                     Link premiseAB_pattern = new Link(Link.LinkType.INHERITANCE, Arrays.asList(targetA_id, potentialB_id), TruthValue.UNKNOWN, null);
                     Link premiseBC_pattern = new Link(Link.LinkType.INHERITANCE, Arrays.asList(potentialB_id, targetC_id), TruthValue.UNKNOWN, null);

                     // Recursively seek premise A->B
                     List<QueryResult> resultsAB = backwardChainRecursive(premiseAB_pattern, depth - 1, bindings, visited);
                     for (QueryResult resAB : resultsAB) {
                         // Recursively seek premise B->C with updated bindings from A->B result
                         List<QueryResult> resultsBC = backwardChainRecursive(premiseBC_pattern, depth - 1, resAB.bindings, visited);
                         for (QueryResult resBC : resultsBC) {
                             // Found both premises A->B and B->C
                             Optional<Link> inferredAC = deduction((Link) resAB.inferredAtom, (Link) resBC.inferredAtom);
                             if (inferredAC.isPresent() && inferredAC.get().tv.getConfidence() > MIN_CONFIDENCE_THRESHOLD) {
                                  // System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Derived via Deduction (B=" + potentialB_id + "): " + inferredAC.get().id + " " + inferredAC.get().tv);
                                 results.add(new QueryResult(resBC.bindings, inferredAC.get()));
                             }
                         }
                     }
                 }
             }
         }

         // --- Try Inversion Backward: Target B->A, seek A->B ---
          if (queryAtom instanceof Link && ((Link) queryAtom).type == Link.LinkType.INHERITANCE && queryAtom.targets.size() == 2) {
              Link targetBA = (Link) queryAtom;
              String targetB_id = targetBA.targets.get(0);
              String targetA_id = targetBA.targets.get(1);

              // Create premise pattern A->B
              Link premiseAB_pattern = new Link(Link.LinkType.INHERITANCE, Arrays.asList(targetA_id, targetB_id), TruthValue.UNKNOWN, null);

              // Recursively seek premise A->B
              List<QueryResult> resultsAB = backwardChainRecursive(premiseAB_pattern, depth - 1, bindings, visited);
              for (QueryResult resAB : resultsAB) {
                  Optional<Link> inferredBA = inversion((Link) resAB.inferredAtom);
                  if (inferredBA.isPresent() && inferredBA.get().tv.getConfidence() > MIN_CONFIDENCE_THRESHOLD) {
                       // System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Derived via Inversion: " + inferredBA.get().id + " " + inferredBA.get().tv);
                      results.add(new QueryResult(resAB.bindings, inferredBA.get()));
                  }
              }
          }

         // --- Try Unification with ForAll/Exists Rules (Instantiation/Skolemization) ---
         // If query is P(c) and KB has ForAll($X, P($X)), unify c with $X.
         // If query is Exists($X, P($X)) and KB has P(c), unify $X with c.
         // (Simplified - full implementation requires careful handling of quantifier scope)
         kb.getLinksByType(Link.LinkType.FOR_ALL).forEach(forAllLink -> {
             if (forAllLink.targets.size() >= 2) { // Expecting [VariableList, Body]
                 Atom bodyPattern = kb.getAtom(forAllLink.targets.get(forAllLink.targets.size() - 1)).orElse(null);
                 if (bodyPattern != null) {
                     Optional<Map<String, String>> unificationResult = unify(bodyPattern, queryAtom, bindings);
                     unificationResult.ifPresent(finalBindings -> {
                         // If unification succeeds, the ForAll statement supports the query
                         // The inferred atom is the query atom itself, but supported by the ForAll rule
                         // We need to adjust the TV based on the ForAll link's TV
                         TruthValue inferredTV = queryAtom.tv.merge(forAllLink.tv); // Simplistic merge
                          // System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Supported by ForAll: " + forAllLink.id);
                         results.add(new QueryResult(finalBindings, queryAtom.withTruthValue(inferredTV))); // Return query atom with potentially updated TV
                     });
                 }
             }
         });


         // --- Try Temporal Rules Backward ---
         // Example: Target HoldsAt(F, T2), seek HoldsAt(F, T1), Persistent(F), NOT Terminated(...)
         if (queryAtom instanceof Link && ((Link) queryAtom).type == Link.LinkType.HOLDS_AT) {
             // Placeholder for backward temporal reasoning logic
         }


          visited.remove(queryId); // Allow revisiting this state via different paths

          // Filter results to keep only the highest confidence one for each unique binding set? Optional.
          return results;
     }


     /** Attempts to plan a sequence of actions to achieve a goal. */
     public Optional<List<Atom>> planToActionSequence(Atom goalAtom, int maxPlanDepth, int maxSearchDepth) {
         System.out.println("\n--- Planning Action Sequence ---");
         System.out.println("Goal: " + goalAtom.id);
         List<Atom> plan = new ArrayList<>();
         return planRecursive(goalAtom, plan, maxPlanDepth, maxSearchDepth, new HashSet<>());
     }

     private Optional<List<Atom>> planRecursive(Atom currentGoal, List<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoals) {
         String goalId = currentGoal.id;
          System.out.println("  ".repeat(MAX_KB_SIZE_BEFORE_FORGET - planDepthRemaining) + "Plan: Seeking " + goalId + " (Plan Depth Left: " + planDepthRemaining + ")");


         if (planDepthRemaining <= 0) {
             System.out.println("  ".repeat(MAX_KB_SIZE_BEFORE_FORGET - planDepthRemaining + 1) + "-> Max plan depth reached.");
             return Optional.empty();
         }
         if (visitedGoals.contains(goalId)) {
              System.out.println("  ".repeat(MAX_KB_SIZE_BEFORE_FORGET - planDepthRemaining + 1) + "-> Goal cycle detected.");
             return Optional.empty();
         }

         // 1. Check if goal is already true (or achievable without action)
         List<QueryResult> goalCheckResults = backwardChain(currentGoal, searchDepth);
         if (!goalCheckResults.isEmpty() && goalCheckResults.stream().anyMatch(r -> r.inferredAtom.tv.getConfidence() > 0.75)) { // Heuristic threshold
              System.out.println("  ".repeat(MAX_KB_SIZE_BEFORE_FORGET - planDepthRemaining + 1) + "-> Goal already holds/derivable.");
             return Optional.of(new ArrayList<>(currentPlan)); // Success - return current plan
         }

         visitedGoals.add(goalId);

         // 2. Find actions (PredictiveImplications) that could achieve the goal
         // Look for links like: PredictiveImplication(Sequence(State, Action), Goal)
         // Or simpler: PredictiveImplication(Action, Goal) or Initiates(Action, GoalFluent)
         List<PotentialPlanStep> potentialSteps = new ArrayList<>();

         // Find actions that INITIATE the goal (if goal is a fluent state)
         if (currentGoal instanceof Node) { // Assuming goal is a state/fluent node for now
             kb.getLinksByType(Link.LinkType.INITIATES)
               .filter(initiatesLink -> initiatesLink.targets.size() == 2 && initiatesLink.targets.get(1).equals(goalId))
               .map(initiatesLink -> kb.getAtom(initiatesLink.targets.get(0))) // Get the action Atom
               .filter(Optional::isPresent).map(Optional::get)
               .filter(actionAtom -> actionAtom instanceof Link && ((Link)actionAtom).type == Link.LinkType.EXECUTION) // Ensure it's an executable action
               .forEach(actionAtom -> {
                   // Precondition is implicitly 'can(Action)' or specific preconditions if defined
                   Atom canPredicate = kb.getOrCreateNode("Predicate_Can", TruthValue.TRUE, 0.1);
                   Atom canActionLink = new Link(Link.LinkType.EVALUATION, Arrays.asList(canPredicate.id, actionAtom.id), TruthValue.UNKNOWN, null);
                   potentialSteps.add(new PotentialPlanStep(actionAtom, Collections.singletonList(canActionLink), initiatesLink.tv.getConfidence() * actionAtom.tv.getConfidence()));
               });
         }

         // Find PREDICTIVE_IMPLICATION links ending in the goal
          kb.getLinksByType(Link.LinkType.PREDICTIVE_IMPLICATION)
            .filter(predLink -> predLink.targets.size() == 2 && predLink.targets.get(1).equals(goalId))
            .forEach(predLink -> {
                kb.getAtom(predLink.targets.get(0)).ifPresent(premiseAtom -> {
                    // If premise is an action itself:
                    if (premiseAtom instanceof Link && ((Link)premiseAtom).type == Link.LinkType.EXECUTION) {
                         Atom canPredicate = kb.getOrCreateNode("Predicate_Can", TruthValue.TRUE, 0.1);
                         Atom canActionLink = new Link(Link.LinkType.EVALUATION, Arrays.asList(canPredicate.id, premiseAtom.id), TruthValue.UNKNOWN, null);
                         potentialSteps.add(new PotentialPlanStep(premiseAtom, Collections.singletonList(canActionLink), predLink.tv.getConfidence() * premiseAtom.tv.getConfidence()));
                    }
                    // If premise is a SEQUENCE involving an action: Seq(State, Action) -> Goal
                    else if (premiseAtom instanceof Link && ((Link)premiseAtom).type == Link.LinkType.SEQUENCE && premiseAtom.targets.size() >= 2) {
                        // Find the action within the sequence
                        Optional<Atom> actionInSeqOpt = ((Link)premiseAtom).targets.stream()
                            .map(id -> kb.getAtom(id))
                            .filter(Optional::isPresent).map(Optional::get)
                            .filter(a -> a instanceof Link && ((Link)a).type == Link.LinkType.EXECUTION)
                            .findFirst();
                        if (actionInSeqOpt.isPresent()) {
                            // Other elements of the sequence become preconditions
                            List<Atom> preconditions = ((Link)premiseAtom).targets.stream()
                                .map(id -> kb.getAtom(id))
                                .filter(Optional::isPresent).map(Optional::get)
                                .filter(a -> !a.id.equals(actionInSeqOpt.get().id))
                                .collect(Collectors.toList());
                            potentialSteps.add(new PotentialPlanStep(actionInSeqOpt.get(), preconditions, predLink.tv.getConfidence() * premiseAtom.tv.getConfidence()));
                        }
                    }
                    // Could add more cases: AND link premises, etc.
                });
            });


         // 3. Sort potential actions by confidence/utility (heuristic) and try them
         potentialSteps.sort(Comparator.<PotentialPlanStep, Double>comparing(s -> s.confidence).reversed());

         for (PotentialPlanStep step : potentialSteps) {
             System.out.println("  ".repeat(MAX_KB_SIZE_BEFORE_FORGET - planDepthRemaining + 1) + "-> Considering Action: " + step.action.id + " (Conf: " + step.confidence + ")");

             // Try to satisfy all preconditions recursively
             Optional<List<Atom>> preconditionPlanOpt = satisfyPreconditions(step.preconditions, currentPlan, planDepthRemaining -1, searchDepth, visitedGoals);

             if (preconditionPlanOpt.isPresent()) {
                 // Preconditions met! Add action to plan and return.
                 List<Atom> finalPlan = preconditionPlanOpt.get();
                 finalPlan.add(step.action); // Add the action *after* its preconditions are met
                 System.out.println("  ".repeat(MAX_KB_SIZE_BEFORE_FORGET - planDepthRemaining + 1) + "--> Plan Found!");
                 return Optional.of(finalPlan);
             }
         }

         // 4. No suitable action found
         visitedGoals.remove(goalId); // Backtrack
         System.out.println("  ".repeat(MAX_KB_SIZE_BEFORE_FORGET - planDepthRemaining + 1) + "-> No path found from here.");
         return Optional.empty();
     }

     // Helper to recursively satisfy a list of preconditions
     private Optional<List<Atom>> satisfyPreconditions(List<Atom> preconditions, List<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoals) {
         if (preconditions.isEmpty()) {
             return Optional.of(new ArrayList<>(currentPlan)); // Base case: no preconditions
         }

         Atom nextPrecondition = preconditions.get(0);
         List<Atom> remainingPreconditions = preconditions.subList(1, preconditions.size());

         // Recursively plan to achieve the first precondition
         Optional<List<Atom>> planForPreconditionOpt = planRecursive(nextPrecondition, currentPlan, planDepthRemaining, searchDepth, visitedGoals);

         if (planForPreconditionOpt.isPresent()) {
             // If successful, try to satisfy the rest of the preconditions with the updated plan and remaining depth
             return satisfyPreconditions(remainingPreconditions, planForPreconditionOpt.get(), planDepthRemaining, searchDepth, visitedGoals);
         } else {
             // Failed to satisfy this precondition
             return Optional.empty();
         }
     }


     // --- Helper Classes for Inference Control ---
     private enum InferenceRuleType { DEDUCTION, INVERSION, TEMPORAL_PERSISTENCE /* ... other rules */ }

     private static class PotentialInference {
         final InferenceRuleType ruleType;
         final Link[] premises;
         final double importance;

         PotentialInference(InferenceRuleType type, Link... premises) {
             this.ruleType = type;
             this.premises = premises;
             // Calculate importance heuristic (e.g., product of premise STI * confidence)
             this.importance = Arrays.stream(premises)
                                   .mapToDouble(p -> p.shortTermImportance * p.tv.getConfidence())
                                   .reduce(1.0, (a, b) -> a * b);
         }

         String getUniqueId() { // Generate ID based on premise IDs for visited set
             return ruleType + ":" + Arrays.stream(premises).map(p -> p.id).sorted().collect(Collectors.joining("|"));
         }
          String getUniqueIdSwapped() { // For symmetric rules like deduction
              if (premises.length == 2) {
                  return ruleType + ":" + Arrays.asList(premises[1].id, premises[0].id).stream().sorted().collect(Collectors.joining("|"));
              }
              return getUniqueId();
          }
     }

      private static class PotentialPlanStep {
         final Atom action;
         final List<Atom> preconditions;
         final double confidence; // Heuristic confidence/utility

         PotentialPlanStep(Atom action, List<Atom> preconditions, double confidence) {
             this.action = action;
             this.preconditions = preconditions;
             this.confidence = confidence;
         }
     }
}


    // ========================================================================
    // --- Inner Class: Agent Controller (Handles Perceive-Reason-Act Loop) ---
    // ========================================================================
    public static class AgentController {
        private final KnowledgeBase kb;
        private final InferenceEngine engine;
        private String lastActionId = null;
        private String previousStateAtomId = null; // Represents composite state

        public AgentController(KnowledgeBase kb, InferenceEngine engine) {
            this.kb = kb;
            this.engine = engine;
        }

        /** Runs the main agent loop. */
        public void run(Environment env, Atom goal, int maxSteps) {
            System.out.println("\n--- Starting Agent Run ---");
            System.out.println("Goal: " + goal.id + " " + goal.tv);

            // Initial Perception
            PerceptionResult initialPerception = env.perceive();
            previousStateAtomId = perceiveState(initialPerception.stateAtoms);

            for (int step = 0; step < maxSteps && !env.isTerminated(); step++) {
                System.out.printf("\n--- Agent Step %d ---%n", step + 1);
                System.out.println("Current State Atom: " + previousStateAtomId);
                kb.getAtom(previousStateAtomId).ifPresent(a -> System.out.println("Current State TV: " + a.tv));

                // 1. Reason (Optional Forward Chaining)
                // engine.forwardChain(1); // Run some inference based on current state

                // 2. Plan & Select Action
                Optional<Atom> selectedActionOpt = selectAction(env, goal);

                if (!selectedActionOpt.isPresent()) {
                    System.out.println("No suitable action found or planned. Stopping.");
                    break;
                }
                lastActionId = selectedActionOpt.get().id;
                System.out.println("Selected Action: " + lastActionId);

                // 3. Execute Action
                ActionResult actionResult = env.executeAction(selectedActionOpt.get());

                // 4. Perceive Outcome
                String currentStateAtomId = perceiveState(actionResult.newStateAtoms);

                // 5. Learn from Experience
                learnFromExperience(currentStateAtomId, actionResult.reward);

                // Update state for next loop
                previousStateAtomId = currentStateAtomId;

                 // Simple check if goal reached
                 kb.getAtom(goal.id).ifPresent(g -> {
                     if (g.tv.strength > 0.9 && g.tv.getConfidence() > 0.8) {
                         System.out.println("*** Goal potentially reached! *** " + g.id + " " + g.tv);
                         // Could terminate here based on goal satisfaction
                     }
                 });
            }
            System.out.println("\n--- Agent Run Finished ---");
        }

        /** Processes environment perception into KB Atoms. */
        private String perceiveState(Collection<Atom> perceivedAtoms) {
            List<String> presentFluentIds = new ArrayList<>();
            // Add/update perceived atoms in KB
            for (Atom pAtom : perceivedAtoms) {
                // Assign high confidence to direct perception
                Atom currentPercept = pAtom.withTruthValue(new TruthValue(pAtom.tv.strength, 10.0)); // High count for perception
                kb.addAtom(currentPercept);
                if (currentPercept.tv.strength > 0.5) { // Consider fluents with strength > 0.5 as "present"
                    presentFluentIds.add(currentPercept.id);
                }
            }

            // Create a composite state representation (e.g., an AND link or a dedicated State Node)
            // Using a State Node for simplicity here.
            Collections.sort(presentFluentIds); // Canonical ID
            String stateName = "State_" + String.join("_", presentFluentIds);
            Node stateNode = kb.getOrCreateNode(stateName, TruthValue.TRUE, 1.0); // State is currently true
            return stateNode.id;
        }

        /** Selects an action using planning or reactive strategies. */
        private Optional<Atom> selectAction(Environment env, Atom goal) {
            // 1. Try Planning
            Optional<List<Atom>> planOpt = engine.planToActionSequence(goal, 5, 3); // Short plan depth for example
            if (planOpt.isPresent() && !planOpt.get().isEmpty()) {
                Atom nextAction = planOpt.get().get(0); // Take the first action in the plan
                System.out.println("Planning successful. Next action: " + nextAction.id);
                // Ensure the action is actually available in the environment
                if (env.getAvailableActions().stream().anyMatch(a -> a.id.equals(nextAction.id))) {
                    return Optional.of(nextAction);
                } else {
                     System.out.println("Planned action " + nextAction.id + " not available! Falling back.");
                }
            } else {
                System.out.println("Planning failed or produced empty plan.");
            }

            // 2. Fallback: Reactive Selection (e.g., based on predicted reward/utility)
            System.out.println("Falling back to reactive action selection...");
            List<Atom> availableActions = env.getAvailableActions();
            if (availableActions.isEmpty()) {
                return Optional.empty();
            }

            Map<Atom, Double> actionUtilities = new HashMap<>();
            Node rewardNode = kb.getOrCreateNode("Reward", TruthValue.UNKNOWN, 0.1); // General reward concept

            for (Atom action : availableActions) {
                // Query: PredictiveImplication(action, Reward)
                Link queryLink = new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(action.id, rewardNode.id), TruthValue.UNKNOWN, null);
                List<QueryResult> results = engine.backwardChain(queryLink, 2); // Shallow search for direct utility

                double maxUtility = 0.0;
                if (!results.isEmpty()) {
                    maxUtility = results.stream()
                                      .mapToDouble(r -> r.inferredAtom.tv.strength * r.inferredAtom.tv.getConfidence())
                                      .max().orElse(0.0);
                }
                 actionUtilities.put(action, maxUtility + 0.01); // Add small epsilon for exploration
                 System.out.println("  Action: " + action.id + " Predicted Utility: " + maxUtility);
            }

             // Choose best available action based on utility
             return actionUtilities.entrySet().stream()
                 .max(Map.Entry.comparingByValue())
                 .map(Map.Entry::getKey);

            // 3. Fallback: Random Exploration (if no other strategy works)
            // if (availableActions.isEmpty()) return Optional.empty();
            // return Optional.of(availableActions.get(new Random().nextInt(availableActions.size())));
        }

        /** Learns from the consequences of the last action. */
        private void learnFromExperience(String currentStateId, double reward) {
            if (previousStateAtomId == null || lastActionId == null) {
                System.out.println("Learning: Skipping (no previous state/action)");
                return;
            }

             System.out.printf("Learning: PrevState=%s, Action=%s, CurrState=%s, Reward=%.2f%n",
                     previousStateAtomId, lastActionId, currentStateId, reward);

            // 1. Learn State Transition: Sequence(PreviousState, Action) -> CurrentState
            // Represent the sequence as a node for simplicity, or use SequenceLink
            String sequenceName = "Seq_" + previousStateAtomId + "_" + lastActionId;
            Node sequenceNode = kb.getOrCreateNode(sequenceName, TruthValue.TRUE, 1.0); // Sequence just occurred

            // Create the predictive link
            Link transitionLink = kb.addLink(Link.LinkType.PREDICTIVE_IMPLICATION,
                    new TruthValue(1.0, 5.0), // High confidence for observed transition
                    1.0, // High initial importance
                    sequenceNode.id, currentStateId);
             transitionLink.time = TimeSpec.MILLISECONDS(100); // Assume a small delay for example
             kb.addAtom(transitionLink); // Re-add to update time if needed
             System.out.println("  Learned Transition: " + transitionLink.id + " " + transitionLink.tv);


            // 2. Learn Reward Association: CurrentState -> Reward (if reward received)
            if (reward > 0.0) {
                Node rewardNode = kb.getOrCreateNode("Reward", TruthValue.UNKNOWN, 0.1); // General reward concept
                // Strengthen link: CurrentState -> Reward
                Link rewardLink = kb.addLink(Link.LinkType.PREDICTIVE_IMPLICATION,
                        new TruthValue(reward, 5.0), // Strength based on reward amount, high confidence
                        1.0,
                        currentStateId, rewardNode.id);
                 rewardLink.time = TimeSpec.IMMEDIATE;
                 kb.addAtom(rewardLink);
                 System.out.println("  Learned Reward Assoc: " + rewardLink.id + " " + rewardLink.tv);

                // Also associate the action taken in the previous state with reward
                 Link actionRewardLink = kb.addLink(Link.LinkType.PREDICTIVE_IMPLICATION,
                         new TruthValue(reward, 3.0), // Slightly lower confidence than direct state->reward
                         0.8,
                         lastActionId, rewardNode.id);
                 actionRewardLink.time = TimeSpec.MILLISECONDS(150); // Action -> Reward takes slightly longer
                 kb.addAtom(actionRewardLink);
                 System.out.println("  Learned Action Utility: " + actionRewardLink.id + " " + actionRewardLink.tv);

            } else {
                 // Optionally learn non-reward association (e.g., Strength=0)
                 Node rewardNode = kb.getOrCreateNode("Reward", TruthValue.UNKNOWN, 0.1);
                 Link nonRewardLink = kb.addLink(Link.LinkType.PREDICTIVE_IMPLICATION,
                         new TruthValue(0.0, 2.0), // Learn that this state leads to no reward
                         0.5,
                         currentStateId, rewardNode.id);
                 nonRewardLink.time = TimeSpec.IMMEDIATE;
                 kb.addAtom(nonRewardLink);
                 // System.out.println("  Learned Non-Reward Assoc: " + nonRewardLink.id + " " + nonRewardLink.tv);
            }

            // 3. Optional: Run forward chaining to integrate knowledge
            // engine.forwardChain(1);
        }
    }


    // ========================================================================
    // --- Inner Class: Environment Interface and Example ---
    // ========================================================================

    /** Defines the interface for an environment the PLN agent can interact with. */
    public interface Environment {
        /** Perceive the current state, returning relevant Atoms. */
        PerceptionResult perceive();

        /** Get a list of currently available actions represented as Atoms. */
        List<Atom> getAvailableActions();

        /** Execute the chosen action Atom in the environment. */
        ActionResult executeAction(Atom actionAtom);

        /** Check if the environment simulation has terminated. */
        boolean isTerminated();
    }

    /** Holds the result of perception. */
    public static class PerceptionResult {
        public final Collection<Atom> stateAtoms; // Atoms representing the current state fluents
        // Could add other perceptual info (e.g., raw sensor data)

        public PerceptionResult(Collection<Atom> stateAtoms) {
            this.stateAtoms = Collections.unmodifiableCollection(stateAtoms);
        }
    }

    /** Holds the result of executing an action. */
    public static class ActionResult {
        public final Collection<Atom> newStateAtoms; // Atoms representing the state after the action
        public final double reward;

        public ActionResult(Collection<Atom> newStateAtoms, double reward) {
            this.newStateAtoms = Collections.unmodifiableCollection(newStateAtoms);
            this.reward = reward;
        }
    }

    /** Simple example environment: Grid world where agent moves towards a goal. */
    static class SimpleGridWorld implements Environment {
        private final EnhancedPLN pln; // Reference to create Atoms
        private int agentX = 0, agentY = 0;
        private final int goalX = 3, goalY = 3;
        private final int worldSize = 5;
        private int steps = 0;
        private final int maxSteps = 20;

        // Pre-create action atoms
        private final Atom moveNorth, moveSouth, moveEast, moveWest;
        private final Node agentAtPredicate, goalAtPredicate;


        SimpleGridWorld(EnhancedPLN pln) {
            this.pln = pln;
            // Create action schema nodes (ExecutionLinks)
            moveNorth = pln.addLink(Link.LinkType.EXECUTION, TruthValue.TRUE, 1.0, pln.getOrCreateNode("ActionSchema_MoveNorth"));
            moveSouth = pln.addLink(Link.LinkType.EXECUTION, TruthValue.TRUE, 1.0, pln.getOrCreateNode("ActionSchema_MoveSouth"));
            moveEast = pln.addLink(Link.LinkType.EXECUTION, TruthValue.TRUE, 1.0, pln.getOrCreateNode("ActionSchema_MoveEast"));
            moveWest = pln.addLink(Link.LinkType.EXECUTION, TruthValue.TRUE, 1.0, pln.getOrCreateNode("ActionSchema_MoveWest"));
            // Predicates
            agentAtPredicate = pln.getOrCreateNode("Predicate_AgentAt", TruthValue.TRUE, 1.0);
            goalAtPredicate = pln.getOrCreateNode("Predicate_GoalAt", TruthValue.TRUE, 1.0);

             // Add static goal information
             Node goalPosNode = pln.getOrCreateNode(String.format("Pos_%d_%d", goalX, goalY));
             pln.addLink(Link.LinkType.EVALUATION, TruthValue.TRUE, 1.0, goalAtPredicate, goalPosNode);

        }

        @Override
        public PerceptionResult perceive() {
            List<Atom> currentFluents = new ArrayList<>();
            // Agent Location Fluent
            Node agentPosNode = pln.getOrCreateNode(String.format("Pos_%d_%d", agentX, agentY));
            Link agentAtLink = new Link(Link.LinkType.EVALUATION, Arrays.asList(agentAtPredicate.id, agentPosNode.id), TruthValue.TRUE, null);
            currentFluents.add(agentAtLink);

            // Optional: Add adjacent cell info, etc.
            return new PerceptionResult(currentFluents);
        }

        @Override
        public List<Atom> getAvailableActions() {
            List<Atom> actions = new ArrayList<>();
            if (agentY < worldSize - 1) actions.add(moveNorth);
            if (agentY > 0) actions.add(moveSouth);
            if (agentX < worldSize - 1) actions.add(moveEast);
            if (agentX > 0) actions.add(moveWest);
            return actions;
        }

        @Override
        public ActionResult executeAction(Atom actionAtom) {
            steps++;
            double reward = -0.1; // Small cost for moving

            String actionSchemaName = pln.getKnowledgeBase().getAtom(actionAtom.targets.get(0))
                                       .map(a -> ((Node)a).name).orElse("UnknownAction");


            int oldX = agentX, oldY = agentY;
            switch (actionSchemaName) {
                case "ActionSchema_MoveNorth": if (agentY < worldSize - 1) agentY++; break;
                case "ActionSchema_MoveSouth": if (agentY > 0) agentY--; break;
                case "ActionSchema_MoveEast":  if (agentX < worldSize - 1) agentX++; break;
                case "ActionSchema_MoveWest":  if (agentX > 0) agentX--; break;
                default: System.err.println("Unknown action executed: " + actionAtom.id); break;
            }
             System.out.printf("  World: Executed %s. Agent moved from (%d,%d) to (%d,%d)%n",
                     actionSchemaName, oldX, oldY, agentX, agentY);


            if (agentX == goalX && agentY == goalY) {
                reward = 10.0; // Big reward at goal
                System.out.println("  World: Goal Reached!");
            }

            // Return new state perception and reward
            PerceptionResult perception = perceive();
            return new ActionResult(perception.stateAtoms, reward);
        }

        @Override
        public boolean isTerminated() {
            return steps >= maxSteps || (agentX == goalX && agentY == goalY);
        }
    }


    // ========================================================================
    // --- Core Data Structures (Atom, Node, Link, TruthValue, TimeSpec) ---
    // ========================================================================

    /** Base class for all knowledge elements (Nodes and Links). */
    public abstract static class Atom {
        public final String id; // Unique identifier, typically derived from content
        public TruthValue tv;
        public double shortTermImportance; // STI: Decays quickly, boosted by access/inference
        public double longTermImportance;  // LTI: Changes slowly, reflects historical relevance

        protected Atom(String id, TruthValue tv, double initialSTI, double initialLTI) {
            this.id = id;
            this.tv = tv;
            this.shortTermImportance = initialSTI;
            this.longTermImportance = initialLTI;
        }

        /** Updates STI and LTI based on an importance boost event. */
        public synchronized void updateImportance(double boost) {
            this.shortTermImportance = Math.min(1.0, this.shortTermImportance + boost); // Cap STI at 1
            // LTI increases based on current STI and update rate
            this.longTermImportance = Math.min(1.0, this.longTermImportance * (1.0 - LTI_UPDATE_RATE) + this.shortTermImportance * LTI_UPDATE_RATE);
        }

        /** Decays importance over time or lack of use. */
        public synchronized void decayImportance() {
            this.shortTermImportance *= (1.0 - STI_DECAY_RATE);
            // LTI decays much slower or not at all unless explicitly reduced
             // this.longTermImportance *= (1.0 - LTI_DECAY_RATE);
        }

         /** Creates a copy of the atom with a different TruthValue. */
         public abstract Atom withTruthValue(TruthValue newTV);


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return id.equals(((Atom) o).id);
        }

        @Override
        public int hashCode() { return id.hashCode(); }

        @Override
        public String toString() {
            return String.format("%s %s <STI=%.2f, LTI=%.2f>", id, tv, shortTermImportance, longTermImportance);
        }

        /** Generate a canonical ID based on the Atom's content. */
        protected abstract String generateId();
    }

    /** Represents concepts, entities, constants. */
    public static class Node extends Atom {
        public final String name; // Human-readable name/symbol

        public Node(String name, TruthValue tv) {
            super(generateIdFromName(name), tv, 0.1, 0.01); // Default initial importance
            this.name = name;
        }

        @Override
        protected String generateId() { return generateIdFromName(name); }
        public static String generateIdFromName(String name) { return "Node(" + name + ")"; }

         @Override
         public Atom withTruthValue(TruthValue newTV) {
             Node newNode = new Node(this.name, newTV);
             newNode.shortTermImportance = this.shortTermImportance;
             newNode.longTermImportance = this.longTermImportance;
             return newNode;
         }

        @Override
        public String toString() {
             return String.format("%s %s <STI=%.2f, LTI=%.2f>", name, tv, shortTermImportance, longTermImportance);
        }
    }

    /** Represents variables in higher-order logic expressions. */
    public static class VariableNode extends Node {
        public VariableNode(String name) {
            // Variables typically don't have inherent truth values or importance in the same way
            super(VARIABLE_PREFIX + name, TruthValue.UNKNOWN);
             this.shortTermImportance = 0; // Variables themselves aren't forgotten
             this.longTermImportance = 0;
        }
        @Override
        protected String generateId() { return generateIdFromName(name); }
        public static String generateIdFromName(String name) { return "Var(" + name + ")"; }

         @Override
         public Atom withTruthValue(TruthValue newTV) {
             // Variables don't really change TV, return self or a new instance if needed
             return new VariableNode(this.name.substring(VARIABLE_PREFIX.length()));
         }
    }


    /** Represents relationships between Atoms. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets; // Ordered list of target Atom IDs
        public TimeSpec time; // Optional temporal information

        public Link(LinkType type, List<String> targets, TruthValue tv, TimeSpec time) {
            // ID depends on type and targets (order matters for some types like Implication)
            super(generateId(type, targets), tv, 0.1, 0.01); // Default initial importance
            this.type = type;
            // Store targets in the provided order
            this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
            this.time = time;
        }

        @Override
        protected String generateId() { return generateId(type, targets); }

        public static String generateId(LinkType type, List<String> targets) {
            // For symmetric links (AND, OR, SIMILARITY), sort targets for canonical ID
            boolean symmetric = EnumSet.of(LinkType.AND, LinkType.OR, LinkType.SIMILARITY, LinkType.EQUIVALENCE, LinkType.SIMULTANEOUS_AND, LinkType.SIMULTANEOUS_OR).contains(type);
            List<String> idTargets = symmetric ? targets.stream().sorted().collect(Collectors.toList()) : targets;
            return type + idTargets.toString();
        }

         @Override
         public Atom withTruthValue(TruthValue newTV) {
             Link newLink = new Link(this.type, this.targets, newTV, this.time);
             newLink.shortTermImportance = this.shortTermImportance;
             newLink.longTermImportance = this.longTermImportance;
             return newLink;
         }

        @Override
        public String toString() {
            String timeStr = (time != null) ? " " + time : "";
            return String.format("%s%s %s <STI=%.2f, LTI=%.2f>", type, targets, tv, timeStr, shortTermImportance, longTermImportance);
        }

        // Expanded Link Types including Temporal and HOL
        public enum LinkType {
            // Core Logical / Structural
            INHERITANCE,    // Probabilistic Implication P(Target|Source) - Asymmetric
            SIMILARITY,     // Symmetric Association
            EQUIVALENCE,    // Strong symmetric association (A <=> B)
            EVALUATION,     // Predicate application: Predicate(Arg1, Arg2...) -> TruthValue
            EXECUTION,      // Action/Schema execution reference: ActionSchema(Args...) -> Outcome/State
            AND, OR, NOT,   // Logical combinations (probabilistic versions)
            MEMBER,         // Set membership (e.g., Member(instance, class))
            INSTANCE,       // Instance relationship (dual of Member)

            // Temporal Logic (Event Calculus inspired)
            SEQUENCE,               // Temporal sequence: A happens, then B happens
            SIMULTANEOUS_AND,       // A and B happen concurrently over an interval
            SIMULTANEOUS_OR,        // A or B happen concurrently over an interval
            PREDICTIVE_IMPLICATION, // If A happens at T1, B is likely to happen at T2 (T2 > T1)
            EVENTUAL_PREDICTIVE_IMPLICATION, // If A persists, B will eventually happen
            HOLDS_AT,               // Fluent F holds at time point T
            HOLDS_THROUGHOUT,       // Fluent F holds during time interval T
            INITIATES,              // Event/Action E initiates Fluent F at time T
            TERMINATES,             // Event/Action E terminates Fluent F at time T
            // Could add: Happens, Occurs, Clipped, Declipped etc. from full Event Calculus

            // Higher-Order Logic / Variables
            FOR_ALL,        // Universal Quantifier: ForAll ($X, P($X))
            EXISTS,         // Existential Quantifier: Exists ($X, P($X))
            VARIABLE_SCOPE, // Defines scope for variables (less common if using ForAll/Exists)

            // Causal (Often derived from Temporal + Background Knowledge)
            CAUSAL_IMPLICATION // Explicit causal link (use with caution, often inferred)
        }
    }

    /** Represents uncertain truth: strength and count (evidence). */
    public static class TruthValue {
        public static final TruthValue DEFAULT = new TruthValue(0.5, 0.0); // Max ignorance
        public static final TruthValue TRUE = new TruthValue(1.0, 10.0); // Reasonably confident true
        public static final TruthValue FALSE = new TruthValue(0.0, 10.0); // Reasonably confident false
        public static final TruthValue UNKNOWN = new TruthValue(0.5, 0.1); // Low confidence unknown

        public final double strength; // [0, 1] probability-like value
        public final double count;    // >= 0 amount of evidence

        public TruthValue(double strength, double count) {
            this.strength = Math.max(0.0, Math.min(1.0, strength));
            this.count = Math.max(0.0, count);
        }

        /** Confidence calculation: c = count / (count + k). */
        public double getConfidence() {
            return count / (count + DEFAULT_EVIDENCE_SENSITIVITY);
        }

        /** Revision: Merges this TV with another using weighted averaging. */
        public TruthValue merge(TruthValue other) {
            if (other == null) return this;
            double totalCount = this.count + other.count;
            if (totalCount < 1e-9) {
                return new TruthValue((this.strength + other.strength) / 2.0, 0.0); // Average strength if no evidence
            }
            double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new TruthValue(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("<s=%.3f, c=%.2f, conf=%.3f>", strength, count, getConfidence());
        }
    }

    /** Represents temporal information (point or interval). */
    public static class TimeSpec {
        public static final TimeSpec IMMEDIATE = new TimeSpec(0, 0); // Represents effectively zero duration/delay

        public final long startTime; // Start time (e.g., milliseconds since epoch, or relative)
        public final long endTime;   // End time (equal to startTime for points)

        private TimeSpec(long start, long end) {
            this.startTime = start;
            this.endTime = Math.max(start, end); // Ensure end >= start
        }

        public static TimeSpec POINT(long time) {
            return new TimeSpec(time, time);
        }
        public static TimeSpec INTERVAL(long start, long end) {
            return new TimeSpec(start, end);
        }
        public static TimeSpec MILLISECONDS(long duration) { // Represents a relative duration/delay
             return new TimeSpec(0, duration); // Assume relative start at 0 for delays
        }


        public boolean isPoint() { return startTime == endTime; }
        public long getDuration() { return endTime - startTime; }

        /** Adds two time specs, assuming they represent relative delays/durations. */
        public static TimeSpec add(TimeSpec t1, TimeSpec t2) {
            if (t1 == null) return t2;
            if (t2 == null) return t1;
            // Simple addition of durations for sequential events
            return TimeSpec.MILLISECONDS(t1.getDuration() + t2.getDuration());
        }

         /** Inverts a time spec, useful for backward reasoning or inversion. */
         public TimeSpec invert() {
             // For a relative duration, inversion might mean negative duration
             return TimeSpec.MILLISECONDS(-this.getDuration());
         }


        @Override
        public String toString() {
            if (isPoint()) return String.format("T=%d", startTime);
            else return String.format("T=[%d,%d]", startTime, endTime);
        }
    }

     /** Represents a query result from backward chaining. */
     public static class QueryResult {
         public final Map<String, String> bindings;
         public final Atom inferredAtom; // The specific atom instance supporting the query

         public QueryResult(Map<String, String> bindings, Atom inferredAtom) {
             this.bindings = Collections.unmodifiableMap(new HashMap<>(bindings));
             this.inferredAtom = inferredAtom;
         }

         @Override
         public String toString() {
             return "QueryResult{" +
                    "bindings=" + bindings +
                    ", inferredAtom=" + inferredAtom +
                    '}';
         }
     }


    // --- Enhanced Example Usage ---
    public void runEnhancedExample() {
        System.out.println("--- Enhanced PLN Example ---");

        // --- Setup Nodes ---
        Node cat = getOrCreateNode("cat");
        Node mammal = getOrCreateNode("mammal");
        Node animal = getOrCreateNode("animal");
        Node pet = getOrCreateNode("pet");
        Node dog = getOrCreateNode("dog");
        Node chases = getOrCreateNode("Predicate_Chases");
        Node hasFur = getOrCreateNode("Predicate_HasFur");
        Node ball = getOrCreateNode("ball");
        Node frisbee = getOrCreateNode("frisbee");
        Node person = getOrCreateNode("person");

        // --- Add Initial Knowledge (Inheritance, Evaluation) ---
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.95, 20), 1.0, cat, mammal);
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.98, 50), 1.0, mammal, animal);
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.90, 30), 1.0, dog, mammal);
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.99, 40), 1.0, dog, pet);
        addLink(Link.LinkType.EVALUATION, new TruthValue(0.8, 15), 0.8, hasFur, mammal); // Most mammals have fur
        addLink(Link.LinkType.EVALUATION, new TruthValue(0.7, 10), 0.7, chases, dog, ball); // Dogs often chase balls

        System.out.println("\nInitial Knowledge Base Size: " + knowledgeBase.atoms.size());
        knowledgeBase.getAllAtoms().stream().limit(10).forEach(System.out::println); // Print sample

        // --- Test Forward Chaining (Deduction) ---
        System.out.println("\n--- Running Forward Chaining ---");
        forwardChain(2);

        System.out.println("\n--- Checking Derived Knowledge ---");
        // Check if Dog -> Animal was derived
        Link dogAnimalQuery = new Link(Link.LinkType.INHERITANCE, Arrays.asList(dog.id, animal.id), TruthValue.UNKNOWN, null);
        getAtom(dogAnimalQuery.id).ifPresent(a -> System.out.println("Derived Dog->Animal: " + a));

        // Check if Dog -> HasFur was derived (via Dog->Mammal, Mammal->HasFur)
        Link dogHasFurQuery = new Link(Link.LinkType.EVALUATION, Arrays.asList(hasFur.id, dog.id), TruthValue.UNKNOWN, null);
         // Note: Simple deduction doesn't handle Evaluation propagation well. Need specific rules.
         // Let's query it with backward chaining instead.

        // --- Test Backward Chaining & Unification ---
        System.out.println("\n--- Running Backward Chaining ---");
        VariableNode varX = getOrCreateVariableNode("X");
        VariableNode varY = getOrCreateVariableNode("Y");

        // Query: What chases balls? Exists($X, Chases($X, ball))
        Atom chasesBallQuery = new Link(Link.LinkType.EVALUATION, Arrays.asList(chases.id, varX.id, ball.id), TruthValue.UNKNOWN, null);
        System.out.println("Query: " + chasesBallQuery.id);
        List<QueryResult> results1 = query(chasesBallQuery, 3);
        System.out.println("Results:");
        results1.forEach(System.out::println);
        // Expected: Should find binding $X=Node(dog)

        // Query: Does a dog chase something? Exists($Y, Chases(dog, $Y))
        Atom dogChasesQuery = new Link(Link.LinkType.EVALUATION, Arrays.asList(chases.id, dog.id, varY.id), TruthValue.UNKNOWN, null);
        System.out.println("\nQuery: " + dogChasesQuery.id);
        List<QueryResult> results2 = query(dogChasesQuery, 3);
        System.out.println("Results:");
        results2.forEach(System.out::println);
        // Expected: Should find binding $Y=Node(ball)

         // Query: Does a cat have fur? HasFur(cat)
         Atom catHasFurQuery = new Link(Link.LinkType.EVALUATION, Arrays.asList(hasFur.id, cat.id), TruthValue.UNKNOWN, null);
         System.out.println("\nQuery: " + catHasFurQuery.id);
         // This requires a rule like: Inheritance(A,B) ^ Evaluation(P,B) => Evaluation(P,A)
         // Add this rule manually for demonstration (normally learned or built-in)
         Node varA = getOrCreateNode("$A"); Node varB = getOrCreateNode("$B"); Node varP = getOrCreateNode("$P");
         // Rule: ForAll $A,$B,$P ( Inheritance($A,$B) ^ Evaluation($P,$B) => Evaluation($P,$A) )
         // Simplified: Add specific instance derived from Cat->Mammal, HasFur(Mammal)
         addLink(Link.LinkType.EVALUATION, new TruthValue(0.75, 10), 0.5, hasFur, cat); // Manually add expected result for now

         List<QueryResult> results3 = query(catHasFurQuery, 4); // Increased depth
         System.out.println("Results:");
         results3.forEach(System.out::println);


        // --- Test Attention & Forgetting ---
        System.out.println("\n--- Testing Attention & Forgetting ---");
        System.out.println("Dog Atom Importance: " + getAtom(dog.id).map(a -> String.format("STI=%.2f, LTI=%.2f", a.shortTermImportance, a.longTermImportance)).orElse("N/A"));
        System.out.println("Accessing 'dog' atom...");
        getAtom(dog.id); // Access should boost STI
        System.out.println("Dog Atom Importance after access: " + getAtom(dog.id).map(a -> String.format("STI=%.2f, LTI=%.2f", a.shortTermImportance, a.longTermImportance)).orElse("N/A"));

        // Add many unimportant atoms to trigger forgetting potentially
        System.out.println("Adding dummy atoms...");
        for (int i = 0; i < 50; i++) {
            getOrCreateNode("DummyNode_" + i).tv = TruthValue.UNKNOWN; // Low count -> low confidence -> low importance boost
        }
        System.out.println("KB size before explicit forget: " + knowledgeBase.atoms.size());
        knowledgeBase.forgetLowImportanceAtoms(); // Manually trigger forget cycle
        System.out.println("KB size after explicit forget: " + knowledgeBase.atoms.size());
        System.out.println("Dog Atom still present? " + getAtom(dog.id).isPresent());


        // --- Test Agent Control & Planning (Simple Grid World) ---
        System.out.println("\n--- Testing Agent Control in SimpleGridWorld ---");
        SimpleGridWorld gridWorld = new SimpleGridWorld(this);

        // Define Goal: AgentAt(Pos_3_3)
        Node goalPos = getOrCreateNode("Pos_3_3");
        Node agentAtPred = getOrCreateNode("Predicate_AgentAt");
        Atom goalAtom = new Link(Link.LinkType.EVALUATION, Arrays.asList(agentAtPred.id, goalPos.id), TruthValue.TRUE, null);

        // Add some basic world knowledge (simplified - normally learned)
        // Example: Moving East increases X coord
        // ForAll $X,$Y ( HoldsAt(AgentAt(Pos_$X_$Y), T1) ^ Executes(MoveEast, T1) => HoldsAt(AgentAt(Pos_($X+1)_$Y), T2) )
        // Simplified: Add direct predictive links for actions leading towards goal from origin
         Node pos00 = getOrCreateNode("Pos_0_0");
         Node pos10 = getOrCreateNode("Pos_1_0");
         Node agentAt00 = new Link(Link.LinkType.EVALUATION, Arrays.asList(agentAtPred.id, pos00.id), TruthValue.TRUE, null);
         Node agentAt10 = new Link(Link.LinkType.EVALUATION, Arrays.asList(agentAtPred.id, pos10.id), TruthValue.TRUE, null);
         Atom moveEastAction = gridWorld.moveEast; // Get the action atom

         // If AgentAt(0,0) and MoveEast -> AgentAt(1,0)
         Link rule1_seq = addLink(Link.LinkType.SEQUENCE, TruthValue.TRUE, 0.9, agentAt00, moveEastAction);
         addLink(Link.LinkType.PREDICTIVE_IMPLICATION, new TruthValue(0.99, 10), 0.9, rule1_seq, agentAt10);


        runAgent(gridWorld, goalAtom, 15);

        System.out.println("\n--- Example Finished ---");
    }
}