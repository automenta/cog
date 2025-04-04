import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
* # Unified Probabilistic Logic Networks (PLN) Engine
*
* A single-class, enhanced implementation of core Probabilistic Logic Networks concepts,
* designed for computational power, efficiency, elegance, extensibility, and robustness.
* This version integrates features addressing limitations of simpler PLN implementations, including:
*
* - **Attention & Forgetting:** Implements importance-based memory management to enable
*   indefinite operation within heap constraints by pruning less relevant Atoms.
* - **Perception/Action API:** Provides a developer-friendly `Environment` interface
*   for integrating with external systems or simulations.
* - **Enhanced Planning:** Moves beyond simple action selection towards goal-directed
*   backward-chaining search for action sequences using temporal logic.
* - **Temporal Logic (Probabilistic Event Calculus):** Incorporates core PEC concepts
*   (`Initiates`, `Terminates`, `HoldsAt`, `PredictiveImplication`, `SequentialAnd`)
*   for reasoning about events, states, and actions over time.
* - **Higher-Order Logic (Basic):** Includes support for Variables and Quantifiers (`ForAll`, `Exists`)
*   within the AtomSpace structure, enabling more expressive knowledge representation and basic unification.
* - **Scalability Improvements:** Introduces basic indexing (`linksByType`, `incomingLinks`)
*   to accelerate premise finding during inference, mitigating exhaustive searches.
*
* ## Core Design Principles:
* - **Atom-Centric:** Knowledge is represented as Atoms (Nodes, Links, Variables) in a graph (AtomSpace).
* - **Probabilistic Truth:** Uses a `TruthValue` (strength, count) capturing belief and evidence.
* - **Unified Inference:** Core inference rules (deduction, abduction, induction, revision, temporal)
*   operate uniformly across the AtomSpace.
* - **Modularity:** Logically grouped components (Atom types, Inference rules, Memory management, API).
* - **Self-Documentation:** Clear naming, structure, and Javadoc minimize external documentation needs.
* - **Extensibility:** Designed to accommodate further PLN rules, advanced heuristics, and features.
*
* @version 2.0
* @author AI Language Model (Revision based on user requirements)
  */
  public class UnifiedPLN {

  // --- Configuration Constants ---
  private static final double DEFAULT_EVIDENCE_SENSITIVITY = 1.0; // k in confidence = count / (count + k)
  private static final double INFERENCE_CONFIDENCE_DISCOUNT_FACTOR = 0.8; // Uncertainty added by inference steps
  private static final double MIN_CONFIDENCE_THRESHOLD = 0.01; // Atoms below this confidence might be pruned
  private static final double MIN_IMPORTANCE_THRESHOLD = 0.01; // Atoms below this importance might be pruned
  private static final long DEFAULT_MAX_ATOMSPACE_SIZE = 100_000; // Target size for pruning
  private static final double PRUNE_TARGET_RATIO = 0.9; // Prune down to this fraction of max size
  private static final double DEFAULT_ATOM_IMPORTANCE = 0.5; // Initial importance
  private static final double IMPORTANCE_DECAY_RATE = 0.99; // Multiplicative decay per pruning cycle
  private static final double IMPORTANCE_ACCESS_BOOST = 0.1; // Additive boost on access
  private static final int MAX_BACKWARD_CHAIN_DEPTH = 5; // Default depth limit for planning/querying
  private static final int MAX_FORWARD_CHAIN_STEPS = 3; // Default steps for forward inference saturation

  // --- Core Knowledge Representation (AtomSpace) ---
  private final ConcurrentMap<String, Atom> atomSpace = new ConcurrentHashMap<>();
  private final ConcurrentMap<LinkType, ConcurrentSkipListSet<String>> linksByType = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ConcurrentSkipListSet<String>> incomingLinks = new ConcurrentHashMap<>();
  private final AtomicLong pruneCycleCounter = new AtomicLong(0);
  private final long maxAtomSpaceSize;

  // --- Constructor ---
  public UnifiedPLN(long maxAtomSpaceSize) {
  this.maxAtomSpaceSize = maxAtomSpaceSize;
  // Initialize type index maps
  for (LinkType type : LinkType.values()) {
  linksByType.put(type, new ConcurrentSkipListSet<>());
  }
  }

  public UnifiedPLN() {
  this(DEFAULT_MAX_ATOMSPACE_SIZE);
  }

  // --- AtomSpace Management ---

  /**
    * Adds or updates an Atom in the AtomSpace. Handles revision if the Atom exists.
    * Updates necessary indexes and access metadata.
    *
    * @param atom The Atom to add or revise.
    * @return The Atom as it exists in the AtomSpace after the operation (potentially revised).
      */
      public synchronized Atom addAtom(Atom atom) {
      Atom existingAtom = atomSpace.get(atom.id);
      if (existingAtom != null) {
      // Revision: Merge truth values and update importance/access time
      TruthValue revisedTV = existingAtom.tv.merge(atom.tv);
      existingAtom.tv = revisedTV;
      updateAtomAccess(existingAtom, IMPORTANCE_ACCESS_BOOST); // Boost importance on revision
      // System.out.println("Revised: " + existingAtom.id + " -> " + existingAtom.tv);
      return existingAtom;
      } else {
      // Add new Atom
      atomSpace.put(atom.id, atom);
      updateAtomAccess(atom, 0.0); // Initialize access time, no boost needed here

           // Update Indexes
           if (atom instanceof Link) {
               Link link = (Link) atom;
               linksByType.computeIfAbsent(link.type, k -> new ConcurrentSkipListSet<>()).add(link.id);
               for (String targetId : link.targets) {
                   incomingLinks.computeIfAbsent(targetId, k -> new ConcurrentSkipListSet<>()).add(link.id);
               }
           }
           // Check if pruning is needed after addition
           checkAndPrune();
           return atom;
      }
      }

  /**
    * Retrieves an Atom by its unique ID, updating its access metadata.
    *
    * @param id The ID of the Atom to retrieve.
    * @return The Atom, or null if not found.
      */
      public Atom getAtom(String id) {
      Atom atom = atomSpace.get(id);
      if (atom != null) {
      updateAtomAccess(atom, IMPORTANCE_ACCESS_BOOST);
      }
      return atom;
      }

  /**
    * Retrieves a Node by its name. If it doesn't exist, creates it with default TV and adds it.
    *
    * @param name The name of the Node.
    * @return The existing or newly created Node.
      */
      public Node getOrCreateNode(String name) {
      String nodeId = Node.generateIdFromName(name);
      Atom existing = getAtom(nodeId); // Use getAtom to update access time if it exists
      if (existing instanceof Node) {
      return (Node) existing;
      } else {
      Node newNode = new Node(name, TruthValue.DEFAULT);
      return (Node) addAtom(newNode);
      }
      }

  /**
    * Creates a Link Atom and adds/revises it in the AtomSpace.
    *
    * @param type The type of the Link.
    * @param tv The TruthValue of the Link.
    * @param targets The target Atoms involved in the Link.
    * @return The resulting Link Atom in the AtomSpace.
      */
      public Link addLink(LinkType type, TruthValue tv, Atom... targets) {
      List<String> targetIds = Arrays.stream(targets)
      .map(a -> a.id)
      .collect(Collectors.toList());
      Link link = new Link(type, targetIds, tv);
      return (Link) addAtom(link);
      }

  /**
    * Creates a Link Atom and adds/revises it in the AtomSpace using target IDs.
    *
    * @param type The type of the Link.
    * @param tv The TruthValue of the Link.
    * @param targetIds The IDs of the target Atoms involved in the Link.
    * @return The resulting Link Atom in the AtomSpace.
      */
      public Link addLink(LinkType type, TruthValue tv, List<String> targetIds) {
      Link link = new Link(type, targetIds, tv);
      return (Link) addAtom(link);
      }

  /** Updates the last accessed time and potentially boosts the importance of an Atom. */
  private void updateAtomAccess(Atom atom, double importanceBoost) {
  atom.lastAccessedTime = System.nanoTime();
  atom.importance = Math.min(1.0, atom.importance + importanceBoost); // Cap importance at 1.0
  }

  // --- Attention and Forgetting (Memory Management) ---

  /** Checks if the AtomSpace size exceeds the limit and triggers pruning if necessary. */
  private synchronized void checkAndPrune() {
  if (atomSpace.size() > maxAtomSpaceSize) {
  System.out.println("AtomSpace size (" + atomSpace.size() + ") exceeds limit (" + maxAtomSpaceSize + "). Pruning...");
  pruneKnowledgeBase();
  }
  }

  /**
    * Prunes the AtomSpace to reduce memory usage, removing Atoms with low confidence
    * and/or low importance based on thresholds and decay.
      */
      public synchronized void pruneKnowledgeBase() {
      long currentCycle = pruneCycleCounter.incrementAndGet();
      long targetSize = (long) (maxAtomSpaceSize * PRUNE_TARGET_RATIO);
      int initialSize = atomSpace.size();
      if (initialSize <= targetSize) {
      return; // No pruning needed
      }

      List<Atom> candidates = new ArrayList<>(atomSpace.values());

      // Calculate forgettability score (lower score = more likely to be forgotten)
      // Score considers confidence, importance, and potentially recency (implicitly via importance decay)
      candidates.sort(Comparator.comparingDouble(atom -> {
      double confidence = atom.tv.getConfidence();
      double importance = atom.importance;
      // Decay importance slightly each cycle unless accessed/boosted
      atom.importance *= IMPORTANCE_DECAY_RATE;
      // Score: Lower confidence and lower importance make it more forgettable
      // Add small epsilon to avoid division by zero or extreme values for new atoms
      return (confidence + 0.01) * (importance + 0.01);
      }));

      int removedCount = 0;
      long numberToRemove = initialSize - targetSize;

      for (int i = 0; i < numberToRemove && i < candidates.size(); i++) {
      Atom atomToRemove = candidates.get(i);

           // Basic check: Don't remove highly confident or important atoms easily
           if (atomToRemove.tv.getConfidence() < MIN_CONFIDENCE_THRESHOLD ||
               atomToRemove.importance < MIN_IMPORTANCE_THRESHOLD)
           {
               if (removeAtom(atomToRemove.id)) {
                   removedCount++;
               }
           } else if (atomSpace.size() > targetSize) {
                // If still over target, force removal even if above threshold (least valuable first)
                if (removeAtom(atomToRemove.id)) {
                   removedCount++;
                }
           } else {
               break; // Reached target size or only valuable atoms remain
           }
      }

      System.out.println("Pruning cycle " + currentCycle + ": Removed " + removedCount + " atoms. Size: " + atomSpace.size());
      }

  /**
    * Removes an Atom and updates relevant indexes. Internal helper for pruning.
    *
    * @param id The ID of the atom to remove.
    * @return true if the atom was removed, false otherwise.
      */
      private synchronized boolean removeAtom(String id) {
      Atom removed = atomSpace.remove(id);
      if (removed != null) {
      // Remove from indexes
      if (removed instanceof Link) {
      Link link = (Link) removed;
      Set<String> typedLinks = linksByType.get(link.type);
      if (typedLinks != null) {
      typedLinks.remove(id);
      }
      for (String targetId : link.targets) {
      Set<String> incoming = incomingLinks.get(targetId);
      if (incoming != null) {
      incoming.remove(id);
      if (incoming.isEmpty()) {
      incomingLinks.remove(targetId); // Clean up empty sets
      }
      }
      }
      }
      // Note: We don't recursively remove atoms that *only* point to this one.
      // They might become candidates for removal in subsequent pruning cycles
      // if their importance/confidence drops.
      return true;
      }
      return false;
      }


    // --- Core Inference Rules ---

    /**
     * Performs PLN Deduction: (A -> B), (B -> C) => (A -> C).
     * Uses simplified probabilistic formula, optionally considering term probabilities.
     * Handles basic Variable unification.
     *
     * @param linkAB Inheritance(A, B) or PredictiveImplication(A, B)
     * @param linkBC Inheritance(B, C) or PredictiveImplication(B, C) (must match linkAB type)
     * @param useTermProbabilities If true, attempts to use P(B), P(C) from Node strengths.
     * @return The inferred Link(A, C), or null if inference fails.
     */
    public Link deduction(Link linkAB, Link linkBC, boolean useTermProbabilities) {
        if (linkAB == null || linkBC == null ||
            (linkAB.type != LinkType.INHERITANCE && linkAB.type != LinkType.PREDICTIVE_IMPLICATION) ||
            linkAB.type != linkBC.type || // Types must match for simple deduction
            linkAB.targets.size() != 2 || linkBC.targets.size() != 2) {
            return null;
        }

        String aId = linkAB.targets.get(0);
        String bId1 = linkAB.targets.get(1);
        String bId2 = linkBC.targets.get(0);
        String cId = linkBC.targets.get(1);

        // --- Basic Unification ---
        // Check if the intermediate concepts (B) match or can be unified.
        // This is a very basic check; real unification is more complex.
        Map<String, String> bindings = new HashMap<>();
        if (!canUnify(bId1, bId2, bindings)) {
             return null; // Intermediate concepts don't match
        }
        // Apply bindings if needed (simplistic: assumes B is the only potential variable here)
        // A real system needs full substitution across A, B, C.
        String unifiedBId = applyBindings(bId1, bindings); // or bId2, should be same after unify
        if (!unifiedBId.equals(applyBindings(bId2, bindings))) return null; // Sanity check

        // Get potentially bound A and C
        String boundAId = applyBindings(aId, bindings);
        String boundCId = applyBindings(cId, bindings);

        Atom nodeA = getAtom(boundAId); // Use bound IDs
        Atom nodeB = getAtom(unifiedBId);
        Atom nodeC = getAtom(boundCId);

        // If nodes don't exist, we might still infer the link, but term probs are impossible.
        if (useTermProbabilities && (nodeA == null || nodeB == null || nodeC == null)) {
            // System.err.println("Deduction Warning: Missing node(s) for term probabilities. Using heuristic.");
            useTermProbabilities = false;
        }

        double sAB = linkAB.tv.strength;
        double sBC = linkBC.tv.strength;
        double nAB = linkAB.tv.count;
        double nBC = linkBC.tv.count;

        double sAC;
        if (useTermProbabilities && nodeB != null && nodeC != null) {
            double sB = nodeB.tv.strength;
            double sC = nodeC.tv.strength;
            if (Math.abs(1.0 - sB) < 1e-9) { // Avoid division by zero if P(B) approx 1
                sAC = sBC;
            } else {
                sAC = sAB * sBC + (1.0 - sAB) * (sC - sB * sBC) / (1.0 - sB);
            }
            sAC = Math.max(0.0, Math.min(1.0, sAC)); // Clamp
        } else {
            // Simplified heuristic formula (avoids needing P(B), P(C))
             sAC = sAB * sBC; // Simplest version: P(C|A) approx P(C|B)P(B|A)
            // Or the heuristic from the original code:
            // sAC = sAB * sBC + 0.5 * (1.0 - sAB);
        }

        // Combine counts using discounting
        double nAC = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * Math.min(nAB, nBC);
        // Optionally factor in node counts if available
        // if (nodeB != null) nAC = Math.min(nAC, nodeB.tv.count);

        TruthValue tvAC = new TruthValue(sAC, nAC);

        // Create and add/revise the new link (using the potentially bound A and C)
        return addLink(linkAB.type, tvAC, boundAId, boundCId);
    }


    /**
     * Performs PLN Inversion (related to Bayes' Rule): (A -> B) => (B -> A).
     * Requires term probabilities P(A) and P(B). Handles basic Variables.
     * Formula: sBA = sAB * sA / sB
     *
     * @param linkAB Inheritance(A, B) or PredictiveImplication(A, B)
     * @return The inferred Link(B, A), or null if inference fails.
     */
    public Link inversion(Link linkAB) {
        if (linkAB == null ||
            (linkAB.type != LinkType.INHERITANCE && linkAB.type != LinkType.PREDICTIVE_IMPLICATION) ||
            linkAB.targets.size() != 2) {
            return null;
        }

        String aId = linkAB.targets.get(0);
        String bId = linkAB.targets.get(1);

        // --- Basic Unification (Check if A or B are variables) ---
        // Inversion typically applies to concrete concepts, but let's allow it.
        // We need the nodes to get probabilities, so they can't be unbound variables.
        if (isVariable(aId) || isVariable(bId)) {
            // System.err.println("Inversion Warning: Cannot invert with unbound variables: " + aId + ", " + bId);
            return null; // Cannot perform inversion without concrete nodes for probabilities
        }

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId);

        if (nodeA == null || nodeB == null) {
             // System.err.println("Inversion Warning: Missing node(s) for term probabilities: " + aId + " or " + bId);
            return null; // Cannot perform inversion without node probabilities
        }

        double sAB = linkAB.tv.strength;
        double nAB = linkAB.tv.count;
        double sA = nodeA.tv.strength; // P(A)
        double sB = nodeB.tv.strength; // P(B)

        double sBA;
        double nBA = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * nAB; // Discount count

        if (sB < 1e-9) { // Avoid division by zero
            // If P(B) is 0, P(A|B) is undefined. Return maximum uncertainty.
            sBA = 0.5;
            nBA = 0.0; // No evidence for the inverted relation
        } else {
            sBA = sAB * sA / sB;
            sBA = Math.max(0.0, Math.min(1.0, sBA)); // Clamp
        }

        // Optionally factor in node counts
        // nBA = Math.min(nBA, Math.min(nodeA.tv.count, nodeB.tv.count));

        TruthValue tvBA = new TruthValue(sBA, nBA);

        // Create and add/revise the new link (note reversed targets)
        return addLink(linkAB.type, tvBA, bId, aId);
    }

    // --- Temporal Inference (Example: Persistence) ---

    /**
     * Infers HoldsAt(Fluent, T2) based on InitiatesAt(Fluent, T1) and persistence.
     * Requires: InitiatesAt(F, T1), potentially TerminatesAt(F, T3), and knowledge about F's persistence.
     * This is a simplified version. Real PEC involves checking for termination events.
     *
     * @param initiatesLink Link(INITIATES, F, T1)
     * @param timePointT2 The time point T2 for which HoldsAt is being queried.
     * @return Inferred HoldsAt(F, T2) link, or null.
     */
    public Link applyPersistenceRule(Link initiatesLink, Atom timePointT2) {
        if (initiatesLink == null || initiatesLink.type != LinkType.INITIATES || initiatesLink.targets.size() != 2 ||
            !(timePointT2 instanceof TimePoint)) {
            return null;
        }

        String fluentId = initiatesLink.targets.get(0);
        String timePointT1Id = initiatesLink.targets.get(1);
        Atom timePointT1 = getAtom(timePointT1Id);

        if (!(timePointT1 instanceof TimePoint)) return null;

        // Basic check: T2 must be after T1
        if (((TimePoint) timePointT2).time <= ((TimePoint) timePointT1).time) {
            return null;
        }

        // --- Check for Termination ---
        // A real implementation would search for TerminatesAt(fluentId, T) where T1 < T < T2.
        // Simplified: Assume persistence unless termination is explicitly known *at T2*.
        String terminatesQueryId = Link.generateIdStatic(LinkType.TERMINATES, Arrays.asList(fluentId, timePointT2.id));
        Atom terminatesLink = getAtom(terminatesQueryId);

        if (terminatesLink != null && terminatesLink.tv.getConfidence() > 0.5 && terminatesLink.tv.strength > 0.5) {
            // If termination at T2 is known with some confidence, persistence doesn't hold.
            return addLink(LinkType.HOLDS_AT, new TruthValue(0.0, terminatesLink.tv.count * INFERENCE_CONFIDENCE_DISCOUNT_FACTOR), fluentId, timePointT2.id);
        }

        // --- Infer HoldsAt ---
        // Strength and count inherited from initiation, discounted by inference.
        // A better model would decay strength/confidence over time based on persistence properties of the fluent.
        double holdsStrength = initiatesLink.tv.strength;
        double holdsCount = initiatesLink.tv.count * INFERENCE_CONFIDENCE_DISCOUNT_FACTOR;

        TruthValue holdsTV = new TruthValue(holdsStrength, holdsCount);
        return addLink(LinkType.HOLDS_AT, holdsTV, fluentId, timePointT2.id);
    }


    // --- Higher-Order Logic (Unification - Basic) ---

    /** Checks if an Atom ID represents a variable. */
    private boolean isVariable(String id) {
        return id != null && id.startsWith(VariableNode.PREFIX);
    }

    /**
     * Basic unification check between two Atom IDs.
     * Can bind a variable to a concrete ID or another variable.
     * Does not handle complex cases like function unification or occurs check.
     *
     * @param id1 First ID.
     * @param id2 Second ID.
     * @param bindings Map to store variable bindings (Variable ID -> Concrete ID).
     * @return true if unification is possible, false otherwise.
     */
    private boolean canUnify(String id1, String id2, Map<String, String> bindings) {
        id1 = resolveBinding(id1, bindings); // Follow existing bindings
        id2 = resolveBinding(id2, bindings);

        if (id1.equals(id2)) {
            return true; // Already identical or bound to the same thing
        }
        if (isVariable(id1)) {
            bindings.put(id1, id2); // Bind var1 to id2
            return true;
        }
        if (isVariable(id2)) {
            bindings.put(id2, id1); // Bind var2 to id1
            return true;
        }
        // Neither is a variable, and they are different concrete IDs
        return false;
    }

    /** Follows a chain of bindings to find the ultimate value for an ID. */
    private String resolveBinding(String id, Map<String, String> bindings) {
        while (isVariable(id) && bindings.containsKey(id)) {
            id = bindings.get(id);
        }
        return id;
    }

    /** Applies bindings to an ID. If the ID is a variable with a binding, returns the bound ID. */
    private String applyBindings(String id, Map<String, String> bindings) {
         return resolveBinding(id, bindings);
    }

    /**
     * Applies bindings to a list of target IDs.
     * @param targetIds List of Atom IDs.
     * @param bindings Current variable bindings.
     * @return New list with bound IDs.
     */
    private List<String> applyBindingsToList(List<String> targetIds, Map<String, String> bindings) {
        return targetIds.stream()
                        .map(id -> applyBindings(id, bindings))
                        .collect(Collectors.toList());
    }


    // --- Inference Control ---

    /**
     * Performs forward chaining inference for a fixed number of steps or until quiescence.
     * Uses indexes to find candidate premises more efficiently.
     * Applies Deduction and Inversion rules.
     *
     * @param maxSteps Maximum inference steps.
     */
    public void forwardChain(int maxSteps) {
        System.out.println("\n--- Starting Forward Chaining (Max Steps: " + maxSteps + ") ---");
        Set<String> newlyAddedInStep = new HashSet<>(); // Track atoms added in the current step

        for (int step = 0; step < maxSteps; step++) {
            System.out.println("Step " + (step + 1));
            int inferencesMadeThisStep = 0;
            Set<String> atomsToAddNext = ConcurrentHashMap.newKeySet(); // Collect new atoms to add after iteration

            // Use current snapshot of relevant links to avoid issues with concurrent modification during iteration
            List<Link> currentInheritanceLinks = getLinksByType(LinkType.INHERITANCE);
            List<Link> currentPredictiveLinks = getLinksByType(LinkType.PREDICTIVE_IMPLICATION);
            List<Link> allTemporalLinks = new ArrayList<>(currentInheritanceLinks);
            allTemporalLinks.addAll(currentPredictiveLinks);

            // --- Try Deduction ---
            for (Link linkAB : allTemporalLinks) {
                 if (linkAB.targets.size() != 2) continue;
                 String bId = linkAB.targets.get(1);

                 // Use index to find potential linkBC candidates (B->C where B matches linkAB's target)
                 Set<String> potentialBC_Ids = incomingLinks.getOrDefault(bId, Collections.emptySet());

                 for (String bcId : potentialBC_Ids) {
                     Atom atomBC = atomSpace.get(bcId); // Use direct access, avoid access update during iteration
                     if (atomBC instanceof Link) {
                         Link linkBC = (Link) atomBC;
                         // Ensure structure A->B, B->C and types match
                         if (linkBC.targets.size() == 2 && linkBC.targets.get(0).equals(bId) && linkBC.type == linkAB.type) {
                             Link inferredAC = deduction(linkAB, linkBC, true); // Use term probs if possible
                             if (inferredAC != null && !atomSpace.containsKey(inferredAC.id)) { // Check if novel
                                 // System.out.println("  FC Deduction: " + linkAB.shortId() + ", " + linkBC.shortId() + " => " + inferredAC.shortId() + " " + inferredAC.tv);
                                 atomsToAddNext.add(inferredAC.id); // Add ID to avoid modifying KB during iteration
                                 inferencesMadeThisStep++;
                             } else if (inferredAC != null) {
                                 // If it exists, the deduction call already handled revision via addAtom
                                 // We could potentially track if revision significantly changed the TV
                             }
                         }
                     }
                 }
            }

            // --- Try Inversion ---
            for (Link linkAB : allTemporalLinks) {
                 Link inferredBA = inversion(linkAB);
                 if (inferredBA != null && !atomSpace.containsKey(inferredBA.id)) { // Check if novel
                     // System.out.println("  FC Inversion: " + linkAB.shortId() + " => " + inferredBA.shortId() + " " + inferredBA.tv);
                     atomsToAddNext.add(inferredBA.id);
                     inferencesMadeThisStep++;
                 } else if (inferredBA != null) {
                     // Revision handled by inversion -> addAtom
                 }
            }

            // --- Apply other rules (e.g., Temporal Persistence) ---
            // Example: Check recent INITIATES links and see if persistence applies now
            // This requires a more sophisticated trigger mechanism than exhaustive search

            // --- Add newly inferred atoms ---
            // This simple approach adds all at once. More complex strategies could prioritize.
            for (String newAtomId : atomsToAddNext) {
                 Atom newAtom = atomSpace.get(newAtomId); // It should exist from the inference call
                 if (newAtom != null) {
                     addAtom(newAtom); // Ensure it's fully integrated (indexes, etc.) - though deduction/inversion already did this
                 }
            }

            if (inferencesMadeThisStep == 0) {
                System.out.println("No new inferences made. Stopping forward chaining.");
                break;
            }
        }
        System.out.println("--- Forward Chaining Finished ---");
    }


    /**
     * Performs backward chaining to find evidence for, or a plan to achieve, a target Atom (goal).
     * Uses recursion and indexing. Handles basic variables.
     *
     * @param targetId The ID of the target Atom (can contain variables).
     * @param maxDepth Maximum recursion depth.
     * @return An Atom representing the potentially strengthened target, or a Plan (list of actions), or null.
     */
    public Atom backwardChain(String targetId, int maxDepth) {
        System.out.println("\n--- Starting Backward Chaining (Target: " + targetId + ", Max Depth: " + maxDepth + ") ---");
        Map<String, String> initialBindings = new HashMap<>();
        // We pass an empty set for visited nodes in the current path to detect cycles.
        Atom result = backwardChainRecursive(targetId, initialBindings, maxDepth, new HashSet<>());
        System.out.println("--- Backward Chaining Finished ---");
        return result;
    }

    private Atom backwardChainRecursive(String targetId, Map<String, String> bindings, int depth, Set<String> visitedInPath) {
        String boundTargetId = applyBindings(targetId, bindings);
        // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "BC Seek: " + boundTargetId + " (Depth " + depth + ", Bindings: " + bindings + ")");

        if (depth <= 0) {
            // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Max depth reached.");
            return getAtom(boundTargetId); // Return current knowledge at max depth
        }
        if (visitedInPath.contains(boundTargetId)) {
             // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Cycle detected.");
            return null; // Avoid infinite loops
        }
        visitedInPath.add(boundTargetId); // Mark as visited in this specific path

        Atom currentTargetAtom = getAtom(boundTargetId);
        // If target is already known with high confidence, maybe stop early?
        // if (currentTargetAtom != null && currentTargetAtom.tv.getConfidence() > 0.9) {
        //     visitedInPath.remove(boundTargetId);
        //     return currentTargetAtom;
        // }

        // --- Try rules that could conclude the target ---
        List<Atom> supportingEvidence = new ArrayList<>();
        if (currentTargetAtom != null) {
             supportingEvidence.add(currentTargetAtom); // Existing knowledge is evidence
        }

        // 1. Can Deduction produce the target? (Target must be A->C or Pred(A,C))
        //    Find rules B->C and recursively seek A->B.
        findSupportViaDeduction(boundTargetId, bindings, depth, visitedInPath, supportingEvidence);

        // 2. Can Inversion produce the target? (Target must be B->A or Pred(B,A))
        //    Find rule A->B and recursively seek A and B nodes.
        findSupportViaInversion(boundTargetId, bindings, depth, visitedInPath, supportingEvidence);

        // 3. Can Temporal Rules produce the target? (e.g., Target is HoldsAt(F, T2))
        //    Find rule InitiatesAt(F, T1) and check persistence/termination.
        findSupportViaTemporal(boundTargetId, bindings, depth, visitedInPath, supportingEvidence);

        // 4. Can Quantifier Instantiation produce the target? (Target is P(a))
        //    Find rule ForAll(X, P(X)) and unify.
        findSupportViaQuantifiers(boundTargetId, bindings, depth, visitedInPath, supportingEvidence);


        // --- Combine Evidence (Revision) ---
        // Simple approach: Revise the target atom based on all found supporting evidence.
        // A more sophisticated approach would track proof paths and combine their strengths.
        Atom finalConclusion = null;
        if (!supportingEvidence.isEmpty()) {
            TruthValue combinedTV = TruthValue.ZERO; // Start with no belief
            for (Atom evidence : supportingEvidence) {
                if (evidence != null) { // Check if recursive calls returned null
                     // Ensure evidence actually matches the target after binding resolution
                     if(evidence.id.equals(boundTargetId)) {
                         combinedTV = combinedTV.merge(evidence.tv);
                     } else {
                         // This might happen if recursion found support for a *variable* version
                         // We need a more robust way to handle evidence for patterns vs instances
                     }
                }
            }
            if (combinedTV.count > 0) { // Only create/update if there's some evidence
                 // Create a temporary atom with the combined TV to return
                 // We don't necessarily add this back to the KB unless it's a goal achievement plan
                 finalConclusion = Atom.createShell(boundTargetId, combinedTV);
                 // Optionally, add the revised atom back to the KB
                 // addAtom(finalConclusion);
            }
        }


        // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "BC Result for " + boundTargetId + ": " + (finalConclusion != null ? finalConclusion.tv : "Not Found/Derived"));
        visitedInPath.remove(boundTargetId); // Unmark as visited for other branches
        return finalConclusion;
    }

    // --- Backward Chaining Rule Helpers (Placeholders/Simplified) ---

    private void findSupportViaDeduction(String targetAC_Id, Map<String, String> bindings, int depth, Set<String> visitedInPath, List<Atom> supportingEvidence) {
        // Target must be Link(A, C) type (Inheritance or Predictive)
        Optional<ParsedLink> parsedTarget = ParsedLink.parse(targetAC_Id);
        if (!parsedTarget.isPresent() || parsedTarget.get().targets.size() != 2 ||
            (parsedTarget.get().type != LinkType.INHERITANCE && parsedTarget.get().type != LinkType.PREDICTIVE_IMPLICATION)) {
            return;
        }
        String targetA = parsedTarget.get().targets.get(0);
        String targetC = parsedTarget.get().targets.get(1);
        LinkType targetType = parsedTarget.get().type;

        // Search for potential intermediate nodes B and links B->C
        // Use index: Find all links L(X, C) where type matches targetType
        Set<String> potentialBC_Ids = getLinksByType(targetType).stream()
            .map(this::getAtom) // Fetch atom (updates access)
            .filter(Objects::nonNull)
            .filter(link -> link instanceof Link && ((Link)link).targets.size() == 2)
            .filter(link -> {
                // Check if link target C matches targetC (potentially unifying)
                Map<String, String> tempBindings = new HashMap<>(bindings);
                return canUnify(((Link)link).targets.get(1), targetC, tempBindings);
            })
            .map(atom -> atom.id)
            .collect(Collectors.toSet());


        for (String bcId : potentialBC_Ids) {
            Link linkBC = (Link) getAtom(bcId); // Re-get to update access
            if (linkBC == null) continue;

            String potentialB = linkBC.targets.get(0);

            // Create the required premise ID: A->B (with potentially bound A)
            Map<String, String> currentBindings = new HashMap<>(bindings);
            if (!canUnify(linkBC.targets.get(1), targetC, currentBindings)) continue; // Re-check unification for safety

            String boundTargetA = applyBindings(targetA, currentBindings);
            String premiseAB_Id = Link.generateIdStatic(targetType, Arrays.asList(boundTargetA, potentialB));

            // Recursively seek premise A->B
            Atom premiseAB_Atom = backwardChainRecursive(premiseAB_Id, currentBindings, depth - 1, visitedInPath);

            if (premiseAB_Atom instanceof Link) {
                // Found support! Perform deduction to estimate strength for targetAC
                Link inferredAC = deduction((Link) premiseAB_Atom, linkBC, true);
                if (inferredAC != null) {
                    // Check if the inferred conclusion *actually* matches the original target after bindings
                    Map<String, String> finalBindings = new HashMap<>(currentBindings); // Bindings might update during recursion
                    if (canUnify(inferredAC.id, targetAC_Id, finalBindings)) {
                         // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Supported by Deduction via B=" + potentialB);
                         // Add the *inferred* atom as evidence. The caller will merge TVs.
                         supportingEvidence.add(inferredAC);
                         // Update bindings based on successful path
                         bindings.putAll(finalBindings);
                    }
                }
            }
        }
    }

    private void findSupportViaInversion(String targetBA_Id, Map<String, String> bindings, int depth, Set<String> visitedInPath, List<Atom> supportingEvidence) {
         // Target must be Link(B, A) type (Inheritance or Predictive)
        Optional<ParsedLink> parsedTarget = ParsedLink.parse(targetBA_Id);
        if (!parsedTarget.isPresent() || parsedTarget.get().targets.size() != 2 ||
            (parsedTarget.get().type != LinkType.INHERITANCE && parsedTarget.get().type != LinkType.PREDICTIVE_IMPLICATION)) {
            return;
        }
        String targetB = parsedTarget.get().targets.get(0);
        String targetA = parsedTarget.get().targets.get(1);
        LinkType targetType = parsedTarget.get().type;

        // Required premise is A->B
        String premiseAB_Id_Pattern = Link.generateIdStatic(targetType, Arrays.asList(targetA, targetB)); // May contain variables

        // Recursively seek premise A->B
        Atom premiseAB_Atom = backwardChainRecursive(premiseAB_Id_Pattern, bindings, depth - 1, visitedInPath);

        if (premiseAB_Atom instanceof Link) {
            // Found premise. Perform inversion.
            Link inferredBA = inversion((Link) premiseAB_Atom);
            if (inferredBA != null) {
                 // Check if the inferred conclusion matches the original target after bindings
                 Map<String, String> finalBindings = new HashMap<>(bindings);
                 if (canUnify(inferredBA.id, targetBA_Id, finalBindings)) {
                    // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Supported by Inversion from " + premiseAB_Atom.id);
                    supportingEvidence.add(inferredBA);
                    bindings.putAll(finalBindings);
                 }
            }
        }
    }

     private void findSupportViaTemporal(String targetId, Map<String, String> bindings, int depth, Set<String> visitedInPath, List<Atom> supportingEvidence) {
        // Example: Target is HoldsAt(F, T2)
        Optional<ParsedLink> parsedTarget = ParsedLink.parse(targetId);
        if (!parsedTarget.isPresent() || parsedTarget.get().type != LinkType.HOLDS_AT || parsedTarget.get().targets.size() != 2) {
            return;
        }
        String fluentF = parsedTarget.get().targets.get(0);
        String timeT2Id = parsedTarget.get().targets.get(1);
        Atom timeT2 = getAtom(timeT2Id); // Need the actual time point object

        if (!(timeT2 instanceof TimePoint)) return; // Requires a concrete time point

        // Search for InitiatesAt(F, T1) where T1 < T2
        // This requires searching linksByType(INITIATES) and checking time. Simplified here.
        // Let's assume we find a potential initiation link:
        // String initiatesLinkId = ... search ...;
        // Atom initiatesLinkAtom = backwardChainRecursive(initiatesLinkId, bindings, depth - 1, visitedInPath);
        // if (initiatesLinkAtom instanceof Link) {
        //     Link inferredHoldsAt = applyPersistenceRule((Link) initiatesLinkAtom, timeT2);
        //     if (inferredHoldsAt != null) {
        //         // Check unification and add to evidence...
        //     }
        // }
        // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Temporal rule check (persistence) not fully implemented.");

    }

    private void findSupportViaQuantifiers(String targetId, Map<String, String> bindings, int depth, Set<String> visitedInPath, List<Atom> supportingEvidence) {
        // Example: Target is Predicate(ConstantA)
        // Search for ForAll(X, Predicate(X))
        Set<String> forAllLinks = linksByType.getOrDefault(LinkType.FOR_ALL, Collections.emptySet());

        for (String forAllId : forAllLinks) {
            Link forAllLink = (Link) getAtom(forAllId);
            if (forAllLink == null || forAllLink.targets.size() != 2) continue;

            String variableId = forAllLink.targets.get(0); // e.g., VariableNode($X)
            String quantifiedExprId = forAllLink.targets.get(1); // e.g., Predicate($X)

            // Try to unify the quantified expression with the target
            Map<String, String> tempBindings = new HashMap<>(bindings);
            if (canUnify(quantifiedExprId, targetId, tempBindings)) {
                // If unification succeeds, it means the target is an instance of the universally quantified statement.
                // The evidence strength/count comes from the ForAll link itself.
                // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Supported by ForAll: " + forAllId);
                // Create a temporary atom representing the instantiated evidence
                Atom instantiatedEvidence = Atom.createShell(targetId, forAllLink.tv);
                supportingEvidence.add(instantiatedEvidence);
                bindings.putAll(tempBindings); // Update bindings
                // No recursive call needed here, as the ForAll link is the direct support.
            }
        }
         // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Quantifier rule check (ForAll) basic implementation.");
    }


    // --- Planning ---

    /**
     * Attempts to find a sequence of actions (a plan) to achieve a goal state.
     * Uses backward chaining on PredictiveImplication links.
     * Goal: Typically a Node representing a desired state, e.g., State_GoalAchieved.
     * Actions: Represented by Nodes, e.g., Action_PerformTask.
     * Rules: PredictiveImplication(Precondition, Action) -> Postcondition
     *        PredictiveImplication(StateA, Action) -> StateB
     *        PredictiveImplication(Action, Effect)
     *
     * @param goalId The ID of the goal Atom.
     * @param currentStateId The ID of the current state Atom.
     * @param maxDepth Maximum plan length / recursion depth.
     * @return A list of Action Node IDs representing the plan, or empty list if no plan found.
     */
    public List<String> planActionSequence(String goalId, String currentStateId, int maxDepth) {
        System.out.println("\n--- Starting Planning (Goal: " + goalId + ", Current: " + currentStateId + ", Max Depth: " + maxDepth + ") ---");
        List<PlanStep> planSteps = findPlanRecursive(goalId, currentStateId, new HashMap<>(), maxDepth, new HashSet<>());
        System.out.println("--- Planning Finished ---");

        if (planSteps != null && !planSteps.isEmpty()) {
             System.out.println("Plan Found:");
             planSteps.forEach(step -> System.out.println("  - " + step.actionId + " (Achieves: " + step.achievedStateId + ")"));
             return planSteps.stream().map(step -> step.actionId).collect(Collectors.toList());
        } else {
            System.out.println("No plan found.");
            return Collections.emptyList();
        }
    }

    private List<PlanStep> findPlanRecursive(String goalId, String currentStateId, Map<String, String> bindings, int depth, Set<String> visitedGoals) {
        // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth) + "Plan Seek: " + goalId + " (Depth " + depth + ")");

        if (depth <= 0) return null; // Depth limit reached

        // Check if current state already satisfies the goal (with unification)
        Map<String, String> currentBindings = new HashMap<>(bindings);
        if (canUnify(currentStateId, goalId, currentBindings)) {
            // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Goal matches current state.");
            bindings.putAll(currentBindings); // Update bindings
            return new ArrayList<>(); // Empty plan signifies goal already met
        }

        if (visitedGoals.contains(goalId)) {
            // System.out.println("  ".repeat(MAX_BACKWARD_CHAIN_DEPTH - depth + 1) + "-> Cycle detected in goal stack.");
            return null; // Avoid cyclic planning
        }
        visitedGoals.add(goalId);

        List<PlanStep> bestPlan = null;
        double bestPlanConfidence = -1.0;

        // Find rules (Actions) that could achieve the goal state: PredictiveImplication(Premise, Goal)
        // We are looking for links where the *target* matches the goalId.
        Set<String> potentialProducerIds = incomingLinks.getOrDefault(goalId, Collections.emptySet());

        for (String producerId : potentialProducerIds) {
            Atom producerAtom = getAtom(producerId); // Updates access time
            if (!(producerAtom instanceof Link)) continue;
            Link producerLink = (Link) producerAtom;

            // Rule must be a predictive implication leading to the goal
            if (producerLink.type != LinkType.PREDICTIVE_IMPLICATION || producerLink.targets.size() != 2) continue;

            // Check if the link's target *really* unifies with the goalId (might have variables)
            Map<String, String> ruleBindings = new HashMap<>(bindings);
            if (!canUnify(producerLink.targets.get(1), goalId, ruleBindings)) {
                continue; // This rule doesn't actually produce the specific goal needed
            }

            String premiseId = producerLink.targets.get(0); // This is the state/condition needed *before* the action

            // --- Deconstruct Premise: Is it an Action or a State? ---
            // Simple assumption: If premise involves an Action node, it's likely State->Action->Goal
            // More complex: Premise could be SequentialAnd(State, Action)
            String actionId = null;
            String subGoalId = null; // The state required *before* the action

            Optional<ParsedLink> parsedPremise = ParsedLink.parse(premiseId);
            if (parsedPremise.isPresent() && parsedPremise.get().type == LinkType.SEQUENTIAL_AND && parsedPremise.get().targets.size() == 2) {
                 // Assume format SeqAnd(State, Action) -> Goal
                 String potentialState = parsedPremise.get().targets.get(0);
                 String potentialAction = parsedPremise.get().targets.get(1);
                 // Heuristic: Check if potentialAction looks like an action node ID
                 if (potentialAction.contains("Action_")) { // Simple check based on naming convention
                     actionId = potentialAction;
                     subGoalId = potentialState;
                 } else if (potentialState.contains("Action_")) { // Maybe Action, State order? Less common.
                     actionId = potentialState;
                     subGoalId = potentialAction;
                 }
            } else if (premiseId.contains("Action_")) {
                 // Assume format Action -> Goal, implies the action itself is the subgoal
                 actionId = premiseId;
                 subGoalId = currentStateId; // Need to be able to perform the action from current state (implicit precondition)
                 // A better model uses can(Action) predicates as subgoals
            } else {
                // Assume format State -> Goal (implies some implicit action or direct transition)
                // This path doesn't yield an explicit action for the plan.
                 subGoalId = premiseId;
            }

            if (actionId == null) continue; // Rule doesn't involve an explicit action we can plan

            // Recursively find plan for the subGoal (the state needed before the action)
            List<PlanStep> subPlan = findPlanRecursive(subGoalId, currentStateId, ruleBindings, depth - 1, visitedGoals);

            if (subPlan != null) {
                // Combine subPlan with the current action
                List<PlanStep> currentPlan = new ArrayList<>(subPlan);
                currentPlan.add(new PlanStep(actionId, goalId)); // Add the action that achieves the current goal

                // Evaluate this plan (e.g., based on confidence of the rule used)
                double currentPlanConfidence = producerLink.tv.getConfidence(); // Simple evaluation metric

                if (bestPlan == null || currentPlanConfidence > bestPlanConfidence) {
                    bestPlan = currentPlan;
                    bestPlanConfidence = currentPlanConfidence;
                    bindings.putAll(ruleBindings); // Update bindings with the best path found so far
                }
            }
        }

        visitedGoals.remove(goalId); // Unmark for other planning branches
        return bestPlan;
    }


    // --- Agent Interaction & Learning ---

    /**
     * Processes perceived state information from an Environment into Atoms.
     * Creates Nodes for features and potentially a composite State Node/Link.
     *
     * @param stateFeatures Map of feature names to values (e.g., Double for intensity/probability).
     * @param statePrefix Prefix for state-related node names (e.g., "State_").
     * @param observationCount Evidence count for perceived state atoms.
     * @return The ID of the Atom representing the composite current state.
     */
    public String perceiveState(Map<String, Object> stateFeatures, String statePrefix, double observationCount) {
        List<String> activeFeatureIds = new ArrayList<>();
        for (Map.Entry<String, Object> entry : stateFeatures.entrySet()) {
            String featureName = entry.getKey();
            Object value = entry.getValue();
            double strength = 0.0;

            // Convert value to strength (simple thresholding/mapping)
            if (value instanceof Double) {
                strength = (Double) value;
            } else if (value instanceof Boolean) {
                strength = ((Boolean) value) ? 1.0 : 0.0;
            } else if (value instanceof Number) {
                strength = ((Number) value).doubleValue() > 0 ? 1.0 : 0.0; // Basic check
            } else {
                strength = 1.0; // Assume presence if non-numeric/boolean
            }

            if (strength > 0.1) { // Threshold for considering feature active
                Node featureNode = getOrCreateNode(featureName);
                // Update feature node based on perception (could be separate EVALUATION link)
                addAtom(new Node(featureName, new TruthValue(strength, observationCount)));
                activeFeatureIds.add(featureNode.id);
            }
        }
        Collections.sort(activeFeatureIds); // Canonical order

        if (activeFeatureIds.isEmpty()) {
            return getOrCreateNode(statePrefix + "Null").id;
        }

        // Create a composite state node (simple approach)
        // Format: StatePrefix + Feature1Name + _ + Feature2Name + ...
        String compositeStateName = statePrefix + activeFeatureIds.stream()
            .map(id -> atomSpace.get(id) instanceof Node ? ((Node)atomSpace.get(id)).name : id)
            .collect(Collectors.joining("_"));

        Node stateNode = getOrCreateNode(compositeStateName);
        // Mark this state as currently observed
        addAtom(new Node(compositeStateName, new TruthValue(1.0, observationCount)));
        return stateNode.id;
    }

    /**
     * Learns from the outcome of an action by creating/updating temporal links.
     * Links: SequentialAnd(PreviousState, Action) -> CurrentState
     *        PredictiveImplication(CurrentState, RewardNode) if reward occurred.
     *
     * @param previousStateId ID of the state before the action.
     * @param actionId ID of the action performed.
     * @param currentStateId ID of the state after the action.
     * @param reward Value of the reward received.
     * @param rewardNodeName Name of the node representing reward.
     * @param observationCount Evidence count for observed transitions/rewards.
     */
    public void learnFromExperience(String previousStateId, String actionId, String currentStateId, double reward, String rewardNodeName, double observationCount) {
        if (previousStateId == null || actionId == null || currentStateId == null) {
            System.err.println("Learning Warning: Missing previous state, action, or current state ID.");
            return;
        }

        // 1. Learn State Transition: SequentialAnd(PreviousState, Action) -> CurrentState
        //    Create the sequence link first
        Link sequenceLink = addLink(LinkType.SEQUENTIAL_AND, new TruthValue(1.0, observationCount), previousStateId, actionId);
        //    Then create the predictive implication from the sequence to the result state
        addLink(LinkType.PREDICTIVE_IMPLICATION, new TruthValue(1.0, observationCount), sequenceLink.id, currentStateId);
         System.out.println("Learned Transition: " + sequenceLink.shortId() + " -> " + currentStateId);

        // 2. Learn Reward Association: PredictiveImplication(CurrentState, RewardNode)
        Node rewardNode = getOrCreateNode(rewardNodeName);
        double rewardStrength = (reward > 0) ? 1.0 : 0.0; // Simple binary reward strength
        // Adjust strength based on reward magnitude? e.g., Math.tanh(reward) clamped to [0,1]
        // rewardStrength = Math.max(0.0, Math.min(1.0, Math.tanh(reward)));

        addLink(LinkType.PREDICTIVE_IMPLICATION, new TruthValue(rewardStrength, observationCount), currentStateId, rewardNode.id);
        if (reward > 0) {
             System.out.println("Learned Reward: " + currentStateId + " -> " + rewardNode.name + " <" + rewardStrength + ">");
        } else {
             System.out.println("Learned Non-Reward: " + currentStateId + " -> " + rewardNode.name + " <" + rewardStrength + ">");
        }

        // 3. Optionally run forward chaining to integrate knowledge
        // forwardChain(1);
    }


    // --- Utility Methods ---

    /** Returns the current size of the AtomSpace. */
    public int getAtomSpaceSize() {
        return atomSpace.size();
    }

    /** Retrieves all links of a specific type using the index. */
    public List<Link> getLinksByType(LinkType type) {
        return linksByType.getOrDefault(type, Collections.emptySet()).stream()
                .map(this::getAtom) // Use getAtom to update access time
                .filter(Objects::nonNull)
                .filter(atom -> atom instanceof Link)
                .map(atom -> (Link) atom)
                .collect(Collectors.toList());
    }

    /** Retrieves all links pointing *to* a specific target ID using the index. */
     public List<Link> getIncomingLinks(String targetId) {
        return incomingLinks.getOrDefault(targetId, Collections.emptySet()).stream()
                .map(this::getAtom) // Use getAtom to update access time
                .filter(Objects::nonNull)
                .filter(atom -> atom instanceof Link)
                .map(atom -> (Link) atom)
                .collect(Collectors.toList());
    }

    /** Prints a summary of the AtomSpace contents. */
    public void printAtomSpaceSummary() {
        System.out.println("\n--- AtomSpace Summary (Size: " + atomSpace.size() + ") ---");
        Map<String, Long> countsByType = atomSpace.values().stream()
            .collect(Collectors.groupingBy(a -> a.getClass().getSimpleName(), Collectors.counting()));
        countsByType.forEach((type, count) -> System.out.println("  " + type + ": " + count));

        System.out.println("Links By Type Index:");
        linksByType.forEach((type, ids) -> {
            if (!ids.isEmpty()) System.out.println("  " + type + ": " + ids.size());
        });
         System.out.println("Incoming Links Index Size: " + incomingLinks.size());
        System.out.println("------------------------------------------");
    }

    // --- Main Method (Example Usage) ---
    public static void main(String[] args) {
        System.out.println("--- Unified PLN Engine Demo ---");
        UnifiedPLN pln = new UnifiedPLN(50); // Small AtomSpace for demo pruning

        // --- Basic Knowledge ---
        Node cat = pln.getOrCreateNode("Cat");
        Node mammal = pln.getOrCreateNode("Mammal");
        Node animal = pln.getOrCreateNode("Animal");
        Node predator = pln.getOrCreateNode("Predator");
        Node hasClaws = pln.getOrCreateNode("HasClaws");

        pln.addLink(LinkType.INHERITANCE, new TruthValue(0.95, 20), cat, mammal);
        pln.addLink(LinkType.INHERITANCE, new TruthValue(0.98, 50), mammal, animal);
        pln.addLink(LinkType.INHERITANCE, new TruthValue(0.80, 15), cat, predator);
        pln.addLink(LinkType.INHERITANCE, new TruthValue(0.90, 10), predator, hasClaws);

        System.out.println("Initial AtomSpace Size: " + pln.getAtomSpaceSize());

        // --- Forward Chaining ---
        pln.forwardChain(2);
        String cat_animal_id = Link.generateIdStatic(LinkType.INHERITANCE, Arrays.asList(cat.id, animal.id));
        System.out.println("Inferred Cat->Animal: " + pln.getAtom(cat_animal_id));
        String cat_claws_id = Link.generateIdStatic(LinkType.INHERITANCE, Arrays.asList(cat.id, hasClaws.id));
        System.out.println("Inferred Cat->HasClaws: " + pln.getAtom(cat_claws_id));

        // --- Backward Chaining (Query) ---
        System.out.println("\nQuerying if Animal is a Cat (expect low confidence via inversion):");
        String animal_cat_id = Link.generateIdStatic(LinkType.INHERITANCE, Arrays.asList(animal.id, cat.id));
        Atom result = pln.backwardChain(animal_cat_id, 3);
        System.out.println("Query Result (Animal->Cat): " + result);

        // --- Temporal Logic & Planning Example ---
        Node stateA = pln.getOrCreateNode("State_A");
        Node stateB = pln.getOrCreateNode("State_B");
        Node stateC = pln.getOrCreateNode("State_C"); // Goal State
        Node actionX = pln.getOrCreateNode("Action_X");
        Node actionY = pln.getOrCreateNode("Action_Y");
        Node reward = pln.getOrCreateNode("Reward");

        // Learn some transitions
        pln.learnFromExperience(stateA.id, actionX.id, stateB.id, 0.0, reward.name, 10);
        pln.learnFromExperience(stateB.id, actionY.id, stateC.id, 1.0, reward.name, 10); // stateC gives reward

        System.out.println("\nTrying to plan from State_A to State_C:");
        List<String> plan = pln.planActionSequence(stateC.id, stateA.id, 5);
        System.out.println("Plan found: " + plan);

        // --- Forgetting Demo ---
        System.out.println("\nAdding atoms to trigger pruning (Limit: " + pln.maxAtomSpaceSize + ")");
        for (int i = 0; i < pln.maxAtomSpaceSize; i++) {
            pln.getOrCreateNode("TempNode_" + i); // Add nodes until limit exceeded
            if (pln.getAtomSpaceSize() <= pln.maxAtomSpaceSize * PRUNE_TARGET_RATIO) break; // Stop if pruned
        }
        pln.printAtomSpaceSummary();

        System.out.println("\n--- Demo Finished ---");
    }


    // ========================================================================
    // === Inner Classes: Data Structures =====================================
    // ========================================================================

    /** Represents uncertain truth: strength of belief and amount of evidence. */
    public static class TruthValue {
        public static final TruthValue DEFAULT = new TruthValue(0.5, 0.1); // Slight default count to allow merging
        public static final TruthValue ZERO = new TruthValue(0.0, 0.0);
        public static final TruthValue FULL = new TruthValue(1.0, Double.MAX_VALUE / 2); // High confidence

        public final double strength; // Probability-like value [0, 1]
        public final double count;    // Non-negative evidence amount

        public TruthValue(double strength, double count) {
            this.strength = Math.max(0.0, Math.min(1.0, strength));
            this.count = Math.max(0.0, count);
        }

        /** Calculates confidence: count / (count + k). */
        public double getConfidence() {
            if (count == 0) return 0.0;
            // Avoid potential overflow if count is huge
            if (count > Double.MAX_VALUE - DEFAULT_EVIDENCE_SENSITIVITY) return 1.0;
            return count / (count + DEFAULT_EVIDENCE_SENSITIVITY);
        }

        /** Merges this TV with another using weighted averaging (Revision). */
        public TruthValue merge(TruthValue other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;

            double totalCount = this.count + other.count;
            if (totalCount == 0) return DEFAULT; // Should not happen if counts > 0

            // Weighted average strength
            double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;

            // Clamp totalCount to avoid potential overflow issues if merging repeatedly
            double finalCount = Math.min(totalCount, Double.MAX_VALUE / 2);

            return new TruthValue(mergedStrength, finalCount);
        }

        @Override
        public String toString() {
            return String.format("<s=%.3f, c=%.2f, conf=%.3f>", strength, count, getConfidence());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TruthValue that = (TruthValue) o;
            // Use tolerance for double comparison
            return Math.abs(that.strength - strength) < 1e-9 && Math.abs(that.count - count) < 1e-9;
        }

        @Override
        public int hashCode() {
            return Objects.hash(strength, count);
        }
    }

    // --- Atom Base Class ---
    /** Base class for all knowledge elements (Nodes, Links, Variables). */
    public abstract static class Atom {
        public final String id; // Unique identifier, derived from content
        public TruthValue tv;
        public double importance; // Attention value component [0, 1]
        public long lastAccessedTime; // For recency heuristics in attention/forgetting

        protected Atom(String id, TruthValue tv) {
            this.id = id;
            this.tv = tv;
            this.importance = DEFAULT_ATOM_IMPORTANCE;
            this.lastAccessedTime = System.nanoTime();
        }

        /** Generates the canonical ID for this Atom based on its content. */
        protected abstract String generateId();

        /** Provides a shorter representation, often just the name or type/targets. */
        public String shortId() { return id; }

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
            return id + " " + tv + String.format(" {Imp:%.2f}", importance);
        }

         /** Utility to create a shell Atom (e.g., for returning BC results without full type info). */
         public static Atom createShell(String id, TruthValue tv) {
             return new Atom(id, tv) {
                 @Override protected String generateId() { return this.id; }
             };
         }
    }

    // --- Node Types ---
    /** Represents concepts, entities, constants. */
    public static class Node extends Atom {
        public final String name; // Human-readable name/symbol

        public Node(String name, TruthValue tv) {
            super(generateIdFromName(name), tv);
            this.name = name;
        }

        @Override
        protected String generateId() {
            return generateIdFromName(name);
        }

        public static String generateIdFromName(String name) {
             // Basic sanitization/normalization could happen here
            return "Node(" + name + ")";
        }

        @Override
        public String shortId() { return name; }

         @Override
        public String toString() {
            return name + " " + tv + String.format(" {Imp:%.2f}", importance);
        }
    }

    /** Represents variables used in higher-order logic constructs. */
    public static class VariableNode extends Node {
        public static final String PREFIX = "$"; // Convention for variable names

        public VariableNode(String name, TruthValue tv) {
            // Ensure name starts with prefix for clarity, use Node's constructor logic
            super(name.startsWith(PREFIX) ? name : PREFIX + name, tv);
        }

        @Override
        protected String generateId() {
            // ID includes a marker to distinguish from regular Nodes if needed,
            // but Node's ID generation might suffice if names are unique.
            // Let's rely on the name prefix convention and Node's ID generation.
            return Node.generateIdFromName(name);
        }

         @Override
         public String toString() {
             // Omit TV/Importance for variables usually, as they are placeholders
             return name;
         }
    }

    // --- Time Representation ---
    // Simple time representation; could be expanded (e.g., TimeDistribution)

    /** Base interface for time specifications. */
    public interface TimeSpec { String getId(); }

    /** Represents a specific point in time. */
    public static class TimePoint extends Node implements TimeSpec {
        public final double time;

        public TimePoint(double time, TruthValue tv) {
            super("TimePoint@" + time, tv);
            this.time = time;
        }
        @Override public String getId() { return id; }
    }

    /** Represents an interval of time. */
    public static class TimeInterval extends Node implements TimeSpec {
        public final double startTime;
        public final double endTime;

        public TimeInterval(double startTime, double endTime, TruthValue tv) {
            super("TimeInterval[" + startTime + "," + endTime + "]", tv);
            this.startTime = startTime;
            this.endTime = endTime;
        }
         @Override public String getId() { return id; }
    }


    // --- Link Type ---
    /** Defines the types of relationships (Links) between Atoms. */
    public enum LinkType {
        // Core Logical/Structural
        INHERITANCE,      // Probabilistic Implication P(Target|Source), e.g., Cat -> Mammal
        SIMILARITY,       // Symmetric Association
        EVALUATION,       // Predicate Application, e.g., Color(Ball, Red)
        EXECUTION,        // Schema Execution Result, e.g., Action(Params) -> Result
        AND, OR, NOT,     // Logical Connectives (Probabilistic)
        EQUIVALENCE,      // Bi-directional strong implication

        // Temporal (Probabilistic Event Calculus inspired)
        INITIATES,        // Event/Action initiates Fluent at Time, e.g., Initiates(OpenDoor, DoorOpen, T1)
        TERMINATES,       // Event/Action terminates Fluent at Time, e.g., Terminates(CloseDoor, DoorOpen, T2)
        HOLDS_AT,         // Fluent holds at Time, e.g., HoldsAt(DoorOpen, T3)
        HOLDS_THROUGHOUT, // Fluent holds during Interval, e.g., HoldsThroughout(LightOn, Interval1)
        SEQUENTIAL_AND,   // Events occur in sequence, e.g., SeqAnd(Eat, Sleep)
        SIMULTANEOUS_AND, // Events occur concurrently, e.g., SimAnd(Run, Sweat)
        PREDICTIVE_IMPLICATION, // If Premise occurs, Consequence likely occurs later, e.g., PI(Cloudy, Rain)
        EVENTUAL_PREDICTIVE_IMPLICATION, // If Premise persists, Consequence eventually occurs

        // Higher-Order Logic
        FOR_ALL,          // Universal Quantifier, e.g., ForAll($X, Implies(Human($X), Mortal($X)))
        EXISTS,           // Existential Quantifier, e.g., Exists($Y, Color($Y, Blue))
        VARIABLE_SCOPE,   // Defines scope for variables (less common, often implicit)

        // Meta / Control
        GOAL,             // Represents a desired state or objective
        PLAN              // Represents a sequence of actions
    }

    // --- Link Class ---
    /** Represents relationships between Atoms. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets; // Ordered list of Atom IDs involved

        public Link(LinkType type, List<String> targets, TruthValue tv) {
            super(generateIdStatic(type, targets), tv);
            this.type = type;
            // Store targets in the provided order, ID generation handles canonicalization
            this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
        }

        @Override
        protected String generateId() {
            return generateIdStatic(this.type, this.targets);
        }

        /** Static method for ID generation, usable before object creation. */
        public static String generateIdStatic(LinkType type, List<String> targetIds) {
            // Canonical ID: TypeName(SortedTargetIDs)
            // Sorting ensures same link regardless of target order *in definition*
            // unless order matters semantically (like in INHERITANCE, SEQUENCE).
            List<String> idsForHash = new ArrayList<>(targetIds);
            if (!isOrderSignificant(type)) {
                Collections.sort(idsForHash);
            }
            // Use Streams for efficient joining, handle potential nulls gracefully
            String targetString = idsForHash.stream()
                                            .filter(Objects::nonNull)
                                            .collect(Collectors.joining(","));
            return type.name() + "(" + targetString + ")";
        }

        /** Determines if the order of targets matters for the Link's semantics and ID. */
        private static boolean isOrderSignificant(LinkType type) {
            switch (type) {
                case INHERITANCE:
                case EVALUATION: // Order often matters for predicate arguments
                case EXECUTION:
                case INITIATES:
                case TERMINATES:
                case HOLDS_AT:
                case HOLDS_THROUGHOUT:
                case SEQUENTIAL_AND:
                case PREDICTIVE_IMPLICATION:
                case EVENTUAL_PREDICTIVE_IMPLICATION:
                case FOR_ALL: // Variable first, then expression
                case EXISTS:  // Variable first, then expression
                case VARIABLE_SCOPE:
                case PLAN: // Order of actions matters
                    return true;
                case SIMILARITY:
                case AND:
                case OR:
                case EQUIVALENCE:
                case SIMULTANEOUS_AND:
                // NOT typically has one argument, order irrelevant
                // GOAL typically points to a state, order irrelevant if single target
                default:
                    return false;
            }
        }

        @Override
        public String shortId() {
             String targetStr = targets.stream()
                 .map(id -> {
                     // Attempt to get a shorter name if possible (Node name, Variable name)
                     if (id.startsWith("Node(")) return id.substring(5, id.length() - 1);
                     if (id.startsWith(VariableNode.PREFIX)) return id;
                     // Fallback for complex link targets
                     int parenIndex = id.indexOf('(');
                     return parenIndex > 0 ? id.substring(0, parenIndex) : id;
                 })
                 .collect(Collectors.joining(", "));
            return type.name() + "(" + targetStr + ")";
        }

         @Override
        public String toString() {
            return shortId() + " " + tv + String.format(" {Imp:%.2f}", importance);
        }
    }

    // --- Helper Classes ---

    /** Helper to parse Link IDs back into components (for BC rules). */
    private static class ParsedLink {
        final LinkType type;
        final List<String> targets;

        ParsedLink(LinkType type, List<String> targets) {
            this.type = type;
            this.targets = targets;
        }

        static Optional<ParsedLink> parse(String linkId) {
            if (linkId == null || !linkId.contains("(") || !linkId.endsWith(")")) {
                return Optional.empty();
            }
            int openParen = linkId.indexOf('(');
            String typeStr = linkId.substring(0, openParen);
            String targetsStr = linkId.substring(openParen + 1, linkId.length() - 1);

            try {
                LinkType type = LinkType.valueOf(typeStr);
                List<String> targets = new ArrayList<>();
                if (!targetsStr.isEmpty()) {
                    // This basic split assumes IDs don't contain commas. A robust parser is needed for complex IDs.
                     targets.addAll(Arrays.asList(targetsStr.split(",", -1))); // Split by comma
                }
                return Optional.of(new ParsedLink(type, targets));
            } catch (IllegalArgumentException e) {
                return Optional.empty(); // Invalid LinkType name
            }
        }
    }

     /** Helper class to represent a step in a generated plan. */
    private static class PlanStep {
        final String actionId;
        final String achievedStateId; // The state this action helps achieve (for debugging/info)

        PlanStep(String actionId, String achievedStateId) {
            this.actionId = actionId;
            this.achievedStateId = achievedStateId;
        }

        @Override
        public String toString() {
            return "Action: " + actionId + " (Achieves: " + achievedStateId + ")";
        }
    }

    // ========================================================================
    // === Environment Interaction API ========================================
    // ========================================================================

    /**
     * Interface for defining an environment that the PLN agent can interact with.
     * Allows PLN to perceive states, execute actions, and receive rewards.
     */
    public interface Environment {
        /**
         * Get the current perceivable state of the environment.
         * The map should contain feature names and their current values (e.g., Double, Boolean).
         *
         * @return A map representing the current state.
         */
        Map<String, Object> getCurrentState();

        /**
         * Get the reward obtained since the last action was performed.
         *
         * @return A double representing the reward value.
         */
        double getReward();

        /**
         * Perform a specified action in the environment.
         * The actionName should correspond to an Action Node name known to the PLN.
         *
         * @param actionName The name of the action to perform.
         */
        void performAction(String actionName);

        /**
         * Get a list of action names that are currently available/possible to perform.
         *
         * @return A list of available action names.
         */
        List<String> getAvailableActions();

        /**
         * Check if the environment simulation or interaction should terminate.
         *
         * @return true if the environment has reached a terminal state, false otherwise.
         */
        boolean isTerminated();
    }

} // End of UnifiedPLN class