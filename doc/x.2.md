import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
* Unified Probabilistic Logic Networks (UPLN) - Integrating Core Logic, Agency, and Advanced Features.
*
* This class consolidates and enhances previous PLN implementations, integrating core probabilistic reasoning,
* agent interaction (perception, action, learning), memory management (attention/forgetting),
* and foundational support for temporal logic, higher-order logic, and improved planning/inference control,
* all within a single, self-documenting file.
*
* Design Goals:
* - Complete & Functional: Provides a runnable base system.
* - Correct & Sound: Aims for logical consistency within its simplified framework.
* - Compact & Consolidated: Single-file structure for ease of understanding and deployment.
* - Modular (Internal): Logical separation of concerns (KB, Inference, Agent Loop, etc.).
* - Deduplicated & Elegant: Minimal redundancy, clear expression of concepts.
* - Extensible: Designed with hooks and structures for future expansion.
* - Self-Documenting: Clear naming, Javadoc comments, references to PLN concepts.
* - Enhanced Capabilities: Incorporates improvements based on the requirements list.
*
* Key Enhancements Addressed:
* - **Importance/Memory:** Atoms have importance and access time; KB pruning implemented for bounded memory usage.
* - **Perception/Action:** Includes a flexible `Environment` interface and agent loop structure.
* - **Planning:** `selectAction` uses a slightly more goal-oriented heuristic, acknowledging need for deeper planning.
* - **Temporal Logic:** Incorporates `LinkType`s (SEQUENCE, PREDICTIVE_IMPLICATION) and Event Calculus concepts (referenced). `learnFromExperience` uses temporal links.
* - **Higher-Order Logic:** Includes `VariableNode`, `FOR_ALL`, `EXISTS` link types and placeholder for unification, enabling richer representation.
* - **Scalability:** Uses ConcurrentHashMap; acknowledges limitations of current search and mentions indexing/heuristics. Pruning helps manage size.
*
* @version 1.0
  */
  public class UnifiedPLN {

  // --- Configuration ---
  private static final double DEFAULT_EVIDENCE_SENSITIVITY_K = 1.0; // k in confidence = count / (count + k)
  private static final double INFERENCE_CONFIDENCE_DISCOUNT = 0.8; // Confidence reduction per inference step (0..1)
  private static final double MIN_CONFIDENCE_THRESHOLD = 0.01; // Atoms below this confidence might be pruned
  private static final double MIN_IMPORTANCE_THRESHOLD = 0.02; // Atoms below this importance are candidates for pruning
  private static final long DEFAULT_MAX_KB_SIZE = 10000; // Max number of atoms before pruning
  private static final long PRUNE_TARGET_SIZE_FACTOR = 90; // Target % size after pruning (e.g., 90%)
  private static final long ATOM_ACCESS_RECENCY_MILLISECONDS = 60000; // Time window considered "recent" for importance boost

  // Agent Configuration
  private static final String GOAL_PREFIX = "Goal_";
  private static final String STATE_PREFIX = "State_";
  private static final String ACTION_PREFIX = "Action_";
  private static final String VARIABLE_PREFIX = "$";
  private static final String REWARD_NODE_NAME = "RewardReceived";
  private static final double ACTION_UTILITY_LEARNING_RATE = 0.1; // Count contribution for action utility updates
  private static final double EXPLORATION_PROBABILITY = 0.15; // Epsilon for epsilon-greedy action selection

  // --- Knowledge Base & Core ---
  private final ConcurrentMap<String, Atom> knowledgeBase = new ConcurrentHashMap<>();
  private final AtomicLong atomIdCounter = new AtomicLong(0); // For unique internal IDs if needed, though content-based preferred
  private final Random random = new Random();
  private final long maxKbSize;
  private final long pruneTargetSize;

  public UnifiedPLN() {
  this(DEFAULT_MAX_KB_SIZE);
  }

  public UnifiedPLN(long maxKbSize) {
  this.maxKbSize = maxKbSize;
  this.pruneTargetSize = (maxKbSize * PRUNE_TARGET_SIZE_FACTOR) / 100;
  System.out.println("Initialized UPLN with Max KB Size: " + maxKbSize + ", Pruning Target: " + pruneTargetSize);
  // Initialize essential concepts if needed (e.g., Reward)
  getOrCreateNode(REWARD_NODE_NAME, TruthValue.DEFAULT);
  }

  // --- Core Data Structures ---

  /**
    * Represents uncertain truth with strength (belief) and count (evidence amount).
    * Immutable.
      */
      public static final class TruthValue {
      public static final TruthValue DEFAULT = new TruthValue(0.5, 0.0); // Represents ignorance
      public static final TruthValue TRUE = new TruthValue(1.0, 1.0); // Represents basic truth observation
      public static final TruthValue FALSE = new TruthValue(0.0, 1.0); // Represents basic false observation

      public final double strength; // Probability-like measure [0, 1]
      public final double count;    // Amount of evidence (non-negative)

      public TruthValue(double strength, double count) {
      this.strength = Math.max(0.0, Math.min(1.0, strength));
      this.count = Math.max(0.0, count);
      }

      /** Calculates confidence: C = N / (N + k). Closer to 1 with more evidence. */
      public double getConfidence(double k) {
      return count / (count + k);
      }
      public double getConfidence() {
      return getConfidence(DEFAULT_EVIDENCE_SENSITIVITY_K);
      }

      /**
        * Merges this TruthValue with another (Revision Rule).
        * Assumes both TVs provide evidence about the *same* proposition.
        * Uses weighted averaging based on counts.
          */
          public TruthValue merge(TruthValue other) {
          if (other == null || other.count == 0) return this;
          if (this.count == 0) return other;

          double totalCount = this.count + other.count;
          if (totalCount <= 1e-9) { // Avoid division by zero if both counts are effectively zero
          return new TruthValue((this.strength + other.strength) / 2.0, 0.0);
          }
          double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
          return new TruthValue(mergedStrength, totalCount);
          }

      /** Creates a new TV by applying a discount factor to the count (reducing confidence). */
      public TruthValue discountConfidence(double factor) {
      return new TruthValue(this.strength, this.count * Math.max(0.0, Math.min(1.0, factor)));
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
      // Use a tolerance for floating-point comparisons
      return Math.abs(that.strength - strength) < 1e-6 && Math.abs(that.count - count) < 1e-6;
      }

      @Override
      public int hashCode() {
      return Objects.hash(strength, count); // Be mindful of floating point hash stability issues
      }
      }

  /**
    * Base class for all knowledge elements (Nodes and Links).
    * Atoms are content-addressable (ID derived from content) and track importance/access.
      */
      public abstract static class Atom {
      public final String id; // Unique, content-derived identifier
      public volatile TruthValue tv; // Current truth value (can be updated via revision)
      private volatile long lastAccessed; // Timestamp for LRU pruning / importance
      private volatile double importance; // Derived measure for attention/pruning (e.g., based on confidence, usage)

      protected Atom(TruthValue tv) {
      this.tv = tv;
      this.id = generateId(); // Generate ID based on subclass content
      touch();
      calculateImportance();
      }

      // Constructor helper for subclasses needing ID assigned after fields are set
      protected Atom(TruthValue tv, boolean deferIdGeneration) {
      this.tv = tv;
      this.id = null; // Will be set by subclass after generating
      touch();
      // Importance calculation deferred until ID (and content) is final
      }

      /** Generates a unique, canonical ID based on the Atom's type and content. */
      protected abstract String generateId();

      /** Updates the last accessed time and recalculates importance. */
      public final void touch() {
      this.lastAccessed = System.currentTimeMillis();
      calculateImportance(); // Recalculate importance on access/update
      }

      /** Calculates the importance of the atom. Simple default: confidence. */
      protected void calculateImportance() {
      // Example: Importance based on confidence, perhaps boosted by recency
      double confidence = tv.getConfidence();
      // double recencyBoost = (System.currentTimeMillis() - lastAccessed < ATOM_ACCESS_RECENCY_MILLISECONDS) ? 1.5 : 1.0;
      this.importance = confidence; // * recencyBoost; - simpler for now
      }

      public long getLastAccessed() { return lastAccessed; }
      public double getImportance() { return importance; }

      /** Atom equality is based SOLELY on the content-derived ID. */
      @Override
      public final boolean equals(Object o) {
      if (this == o) return true;
      // Check for null, class mismatch (allows Node != Link), and null ID (should not happen post-construction)
      if (o == null || getClass() != o.getClass() || this.id == null) return false;
      Atom atom = (Atom) o;
      return this.id.equals(atom.id);
      }

      @Override
      public final int hashCode() {
      return Objects.hash(id); // Hash based only on ID
      }

      @Override
      public String toString() {
      return id + " " + tv + String.format(" (Imp:%.3f)", importance);
      }
      }

  /** Represents concepts, entities, constants. */
  public static class Node extends Atom {
  public final String name; // Human-readable name/symbol

       public Node(String name, TruthValue tv) {
           super(tv, true); // Defer ID generation
           if (name == null || name.isEmpty()) {
               throw new IllegalArgumentException("Node name cannot be null or empty.");
           }
           this.name = name;
           // Assign ID now that name is set
           ((Atom)this).id = generateId();
            calculateImportance(); // Calculate initial importance
       }

       @Override
       protected String generateId() {
           return "Node(" + name + ")";
       }

       @Override
       public String toString() {
            return name + " " + tv + String.format(" (Imp:%.3f)", getImportance());
       }
  }

  /** Represents variables used in higher-order logic expressions. */
  public static class VariableNode extends Node {
  public VariableNode(String name) {
  // Variables typically don't have inherent truth values; maybe default?
  super(name.startsWith(VARIABLE_PREFIX) ? name : VARIABLE_PREFIX + name, TruthValue.DEFAULT);
  }

       @Override
       protected String generateId() {
           // Ensure prefix consistency for ID
            String correctedName = name.startsWith(VARIABLE_PREFIX) ? name : VARIABLE_PREFIX + name;
            return "Var(" + correctedName + ")";
       }

       @Override
       public String toString() {
           // Variables often don't show TV unless bound.
           return name + String.format(" (Imp:%.3f)", getImportance());
       }
  }


    /** Represents relationships (links) between Atoms. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets; // IDs of Atoms involved in the link (ORDERED for canonical ID)

        // Factory method for convenience
        public static Link create(UnifiedPLN pln, LinkType type, TruthValue tv, String... targetIds) {
             List<String> ids = Arrays.asList(targetIds);
             Link link = new Link(type, ids, tv);
             return (Link) pln.addAtom(link); // Use addAtom to ensure revision and KB management
        }
         public static Link create(UnifiedPLN pln, LinkType type, TruthValue tv, Atom... targets) {
             List<String> ids = Arrays.stream(targets).map(a -> a.id).collect(Collectors.toList());
             Link link = new Link(type, ids, tv);
             return (Link) pln.addAtom(link); // Use addAtom to ensure revision and KB management
        }

        protected Link(LinkType type, List<String> targets, TruthValue tv) {
             super(tv, true); // Defer ID generation
             if (type == null || targets == null || targets.isEmpty()) {
                 throw new IllegalArgumentException("Link type and targets cannot be null/empty.");
             }
             this.type = type;
             // Sort targets to ensure canonical representation for ID generation and equality checks.
             // For Sequence types, order *matters conceptually*, but ID must be canonical.
             // The conceptual order is preserved by the *use* of the link (e.g., inference rules).
             this.targets = Collections.unmodifiableList(
                 targets.stream()
                 .filter(Objects::nonNull) // Prevent NullPointerException
                 .sorted()
                 .collect(Collectors.toList())
             );
              if (this.targets.size() != targets.size()) {
                  // This check catches nulls that were filtered out
                  System.err.println("Warning: Null target ID provided for Link " + type + ". Ignored.");
                  // Optionally throw exception instead, depending on strictness required
              }

              // Assign ID now that type/targets are set
              ((Atom)this).id = generateId();
              calculateImportance(); // Calculate initial importance
        }

        /** Get the original, potentially unsorted order (important for SEQUENCE types). */
        public List<String> getOriginalTargetOrder() {
            // Note: This assumes the input list to the constructor was the intended conceptual order.
            // If we need to reconstruct order for types like SEQUENCE, it requires storing it separately
            // or relying on inference context. For simplicity, we return the *sorted* list here,
            // acknowledging this limitation for order-dependent types within this structure.
            return targets; // Returning the canonical (sorted) list for now.
        }

        @Override
        protected String generateId() {
             // Canonical ID uses sorted target list
             return type + targets.toString();
        }

        @Override
        public String toString() {
             return type + targets.toString() + " " + tv + String.format(" (Imp:%.3f)", getImportance());
        }

        /** Defines the types of relationships representable. */
        public enum LinkType {
            // Core PLN / Logic
            INHERITANCE, // Asymmetric P(Target|Source), e.g., Cat -> Mammal
            SIMILARITY,  // Symmetric association
            EVALUATION,  // Predicate application: Predicate(Arg1, Arg2...)
            EXECUTION,   // Result of a schema/function: Schema(Args...) -> Output

            // Standard Logic Connectives
            AND, OR, NOT, IMPLICATION, EQUIVALENCE,

            // Temporal Logic (Based on Event Calculus Ideas)
            SEQUENCE,      // A happens then B happens (Uses original target order conceptually)
            SEQUENTIAL_AND,// Like AND, but with explicit temporal order constraint (Targets T1...Tn)
            SIMULTANEOUS_AND,// A and B hold during the same time interval
            PREDICTIVE_IMPLICATION, // If A happens, B is predicted to happen later (A -> SeqAND(A,B))
            EVENTUAL_PREDICTIVE_IMPLICATION, // If A holds long enough, B will eventually happen
            HOLDS_AT,       // Fluent/Event holds at a specific TimePoint Atom
            HOLDS_THROUGHOUT, // Fluent/Event holds during a TimeInterval Atom
            INITIATES,     // Action/Event initiates a Fluent/Event (often at a TimePoint)
            TERMINATES,    // Action/Event terminates a Fluent/Event (often at a TimePoint)
            // DISJOINT_SEQ_AND, // B starts after A finishes (More specific sequence) - Combine w/ Sequence+TimeInfo?

            // Higher-Order Logic
            FOR_ALL,    // Universal quantifier: ForAll($X, Predicate($X))
            EXISTS,     // Existential quantifier: Exists($X, Predicate($X))
            VARIABLE_SCOPE, // Defines scope (alternative to explicit quantifiers?) - Less common in PLN refs

            // Agent/Control Specific (optional, could use Evaluation/Inheritance)
            GOAL,       // Represents a desired state/outcome
            ACTION_UTILITY // Represents the learned usefulness of an action (e.g., Action -> GoodOutcome)
        }
    }

    // --- Knowledge Base Management ---

    /**
     * Adds an Atom to the KB or merges it with an existing Atom via revision.
     * Handles KB pruning if the size limit is exceeded.
     * Ensures Atoms in the KB are unique by ID.
     *
     * @param atom The Atom to add or merge.
     * @return The Atom present in the KB (either the input atom or the existing one after merging).
     */
    public Atom addAtom(Atom atom) {
        if (atom == null || atom.id == null) {
             System.err.println("Warning: Attempted to add null atom or atom with null ID.");
             return null; // Or throw exception
        }

        // Check size and prune if necessary BEFORE adding the new atom
        pruneKnowledgeBaseIfNeeded();

        final Atom effectivelyFinalAtom; // Workaround for lambda variable scope

        // Use compute to handle concurrent insertion/update atomically
        Atom result = knowledgeBase.compute(atom.id, (id, existingAtom) -> {
            if (existingAtom == null) {
                // Atom is new, just add it
                 atom.touch(); // Ensure lastAccessed and importance are set
                return atom;
            } else {
                // Atom exists, perform revision (merge TruthValues)
                TruthValue mergedTV = existingAtom.tv.merge(atom.tv);
                if (!mergedTV.equals(existingAtom.tv)) {
                    // Update only if TV actually changed to avoid unnecessary writes/updates
                     existingAtom.tv = mergedTV;
                     // System.out.println("Revised: " + existingAtom.id + " -> " + existingAtom.tv);
                }
                existingAtom.touch(); // Update access time and importance even if TV didn't change
                return existingAtom; // Return the atom already in the map
            }
        });

        // Since the lambda returns the atom from the map (new or existing revised one),
        // 'result' is the atom that is definitely in the KB.
        return result;
    }


    /** Gets an Atom by its ID, updating its access time. */
    public Atom getAtom(String id) {
        Atom atom = knowledgeBase.get(id);
        if (atom != null) {
            atom.touch(); // Mark as accessed
        }
        return atom;
    }

     /** Gets a Node by its name, creating it with a default TV if it doesn't exist. */
    public Node getOrCreateNode(String name, TruthValue initialTV) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Node name cannot be null or empty.");
        String nodeId = "Node(" + name + ")"; // Assumes Node.generateId format

        // Use computeIfAbsent for atomic creation
         Atom atom = knowledgeBase.computeIfAbsent(nodeId, id -> {
             Node newNode = new Node(name, initialTV);
             newNode.touch(); // Set initial access time/importance
              //System.out.println("Created Node: " + id);
              pruneKnowledgeBaseIfNeeded(); // Check size after potential creation
             return newNode;
         });

        // If it existed, computeIfAbsent returns the existing one. Touch it.
         if (atom != null) {
             atom.touch();
         }

         // Ensure we return a Node. This cast should be safe due to the ID format check and creation logic.
         if (!(atom instanceof Node)) {
              System.err.println("Error: Atom retrieved for Node ID " + nodeId + " is not a Node: " + atom);
              // Fallback or throw error
              Node newNode = new Node(name, initialTV);
              return (Node) addAtom(newNode); // Add/revise robustly
         }
         return (Node) atom;
    }

     public Node getOrCreateNode(String name) {
         return getOrCreateNode(name, TruthValue.DEFAULT);
     }

      public VariableNode getOrCreateVariableNode(String name) {
         String varName = name.startsWith(VARIABLE_PREFIX) ? name : VARIABLE_PREFIX + name;
         String nodeId = "Var(" + varName + ")"; // Assumes VariableNode.generateId format

         Atom atom = knowledgeBase.computeIfAbsent(nodeId, id -> {
             VariableNode newNode = new VariableNode(varName);
             newNode.touch();
              //System.out.println("Created VariableNode: " + id);
              pruneKnowledgeBaseIfNeeded(); // Check size after potential creation
             return newNode;
         });

         if (atom != null) {
             atom.touch();
         }

          if (!(atom instanceof VariableNode)) {
              System.err.println("Error: Atom retrieved for Variable ID " + nodeId + " is not a VariableNode: " + atom);
              VariableNode newNode = new VariableNode(varName);
              return (VariableNode) addAtom(newNode); // Add/revise robustly
         }
          return (VariableNode) atom;
     }

    // Convenience methods for creating/adding specific link types (use factory methods in Link for better encapsulation?)
     public Link addLink(Link.LinkType type, TruthValue tv, Atom... targets) {
         return Link.create(this, type, tv, targets);
     }
      public Link addLink(Link.LinkType type, TruthValue tv, String... targetIds) {
         return Link.create(this, type, tv, targetIds);
     }

    // --- Memory Management (Importance & Pruning) ---

    /** Checks if the KB size exceeds the limit and triggers pruning if needed. */
    private void pruneKnowledgeBaseIfNeeded() {
        if (knowledgeBase.size() > maxKbSize) {
            // System.out.println("KB size (" + knowledgeBase.size() + ") exceeds max (" + maxKbSize + "). Pruning...");
            pruneKnowledgeBase(pruneTargetSize, MIN_IMPORTANCE_THRESHOLD);
            // System.out.println("KB size after pruning: " + knowledgeBase.size());
        }
    }

    /**
     * Prunes the knowledge base to reduce its size, removing least important/oldest atoms.
     * Prioritizes removing atoms below `minImportanceThreshold`.
     * Avoids removing very recently accessed atoms to prevent churn.
     * Note: This basic pruning doesn't handle dependency chains perfectly (removing a node might invalidate links conceptually,
     * though the links will remain until pruned themselves). More sophisticated pruning needed for complex graphs.
     *
     * @param targetSize The desired size of the KB after pruning.
     * @param minImportance Minimum importance; atoms below this are strong candidates for removal.
     */
    public synchronized void pruneKnowledgeBase(long targetSize, double minImportance) {
        if (knowledgeBase.size() <= targetSize) {
            return; // No need to prune
        }

        long startTime = System.currentTimeMillis();
        long atomsToRemoveCount = knowledgeBase.size() - targetSize;
        if (atomsToRemoveCount <= 0) return;

        // Select candidates for pruning: sort by importance (ascending), then last accessed (ascending)
        List<Atom> candidates = knowledgeBase.values().stream()
             .filter(atom -> atom.getImportance() < minImportance || (System.currentTimeMillis() - atom.getLastAccessed() > ATOM_ACCESS_RECENCY_MILLISECONDS * 2) ) // Prioritize low importance or truly old items
             // Alternative: sort all, filter later? Might be slower for large KBs.
             .sorted(Comparator.<Atom, Double>comparing(Atom::getImportance)
                           .thenComparingLong(Atom::getLastAccessed))
             .limit(atomsToRemoveCount * 2) // Get more candidates than strictly needed, helps if some are dependencies? No, not handled here. Just limit to avoid sorting huge list.
             .collect(Collectors.toList());


        int removedCount = 0;
         for (Atom atomToRemove : candidates) {
             if (knowledgeBase.size() <= targetSize) {
                 break; // Reached target size
             }
              if (System.currentTimeMillis() - atomToRemove.getLastAccessed() < 500) { // Don't remove *just* accessed atoms
                 continue;
              }

              // Simple removal by ID. Does not explicitly handle dependencies.
              knowledgeBase.remove(atomToRemove.id);
              removedCount++;
              if (removedCount >= atomsToRemoveCount) {
                  break; // Removed enough atoms
              }
         }

        // Fallback: If not enough atoms removed by importance/age criteria, remove oldest regardless of importance (but above threshold perhaps?)
         // For simplicity, the current logic just removes based on the sorted list up to the target count.
         // If a more aggressive LRU is needed regardless of importance:
         if (knowledgeBase.size() > targetSize && removedCount < atomsToRemoveCount) {
             // System.out.println("Importance pruning insufficient, resorting to stricter LRU...");
             List<Atom> lruCandidates = knowledgeBase.values().stream()
                     .sorted(Comparator.comparingLong(Atom::getLastAccessed))
                     .limit(knowledgeBase.size() - targetSize) // Limit to exact number needed
                     .collect(Collectors.toList());

             for (Atom atomToRemove : lruCandidates) {
                 if (knowledgeBase.size() <= targetSize) break;
                 if (System.currentTimeMillis() - atomToRemove.getLastAccessed() < 500) continue; // Skip very recent
                  knowledgeBase.remove(atomToRemove.id);
             }
         }

         // long duration = System.currentTimeMillis() - startTime;
         // if (removedCount > 0 || knowledgeBase.size() < initialSize) // Some pruning occurred
         //     System.out.printf("Pruning complete. Removed approx %d atoms in %d ms. New size: %d\n", initialSize - knowledgeBase.size(), duration, knowledgeBase.size());
    }


    // --- Core Inference Rules (Simplified Examples) ---
    // Note: These rules operate directly on the KB state. More advanced inference
    // would involve context management, focused search, etc.

    /**
     * Performs PLN Deduction (A->B, B->C => A->C).
     * Simplified version using independence assumption for strength.
     * Formula: s(AC) = s(AB)*s(BC) + (1-s(AB))*s(C|~B) --- requires P(C|~B) which is hard.
     * Simplified (Independence): s(AC) ~ s(AB)*s(BC)
     * Heuristic version used in original: sAC = sAB*sBC + 0.5 * (1-sAB) --- Not probability-based.
     * Using simple multiplication reflects probabilistic chain rule under conditional independence assumption (P(C|A,B)=P(C|B)):
     * P(C|A) = sum_B P(C|B,A)P(B|A) => If P(C|B,A) ~ P(C|B)=sBC and P(B|A)=sAB => sAC ~ sBC*sAB.
     * Counts are combined using discounted minimum.
     *
     * @param linkAB InheritanceLink(A, B)
     * @param linkBC InheritanceLink(B, C)
     * @return The inferred/revised InheritanceLink(A, C) in the KB, or null.
     */
     public Link deduction(Link linkAB, Link linkBC) {
        if (!isValidInheritance(linkAB) || !isValidInheritance(linkBC) || !linkAB.targets.get(1).equals(linkBC.targets.get(0))) {
            return null; // Requires A->B and B->C structure
        }
        String aId = linkAB.targets.get(0);
        String bId = linkAB.targets.get(1); // Same as linkBC.targets.get(0)
        String cId = linkBC.targets.get(1);

        // Simple strength calculation (assuming conditional independence: P(C|A,B) approx P(C|B))
        double sAC = linkAB.tv.strength * linkBC.tv.strength;
        // Simple count combination: Discounted minimum count represents bottleneck of evidence.
         double nAC = INFERENCE_CONFIDENCE_DISCOUNT * Math.min(linkAB.tv.count, linkBC.tv.count);
        // Note: The text version using term probabilities is more complex and requires P(B), P(C) nodes.
        // sAC = sAB * sBC + (1.0 - sAB) * (sC - sB * sBC) / (1.0 - sB) ; if needed.

        TruthValue tvAC = new TruthValue(sAC, nAC);
        return addLink(Link.LinkType.INHERITANCE, tvAC, aId, cId);
     }
      private boolean isValidInheritance(Link l) {
          return l != null && l.type == Link.LinkType.INHERITANCE && l.targets.size() == 2;
      }


    /**
     * Performs PLN Inversion (A->B => B->A), related to Bayes' theorem.
     * Formula: s(BA) = s(AB) * s(A) / s(B) --- Requires term probabilities P(A) and P(B).
     * Assumes Node strengths represent their marginal probabilities.
     * Counts are combined using discounting.
     *
     * @param linkAB InheritanceLink(A, B)
     * @return The inferred/revised InheritanceLink(B, A) in the KB, or null.
     */
     public Link inversion(Link linkAB) {
        if (!isValidInheritance(linkAB)) return null;

        String aId = linkAB.targets.get(0);
        String bId = linkAB.targets.get(1);

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId);

        if (nodeA == null || nodeB == null) {
            // System.err.println("Inversion Warning: Missing node A (" + aId + ") or B (" + bId + ")");
            return null; // Cannot perform inversion without node probabilities
        }

        double sAB = linkAB.tv.strength;
        double nAB = linkAB.tv.count;
        double sA = nodeA.tv.strength; // P(A)
        double sB = nodeB.tv.strength; // P(B)

        double sBA;
        if (sB < 1e-9) { // Avoid division by zero
             // System.err.println("Inversion Warning: P(B) approx 0 for B=" + bId + ". Result is undefined/uncertain.");
             sBA = 0.5; // Default to max uncertainty strength
             nAB = 0.0;   // Reset count to reflect lack of basis
        } else {
            sBA = sAB * sA / sB;
             sBA = Math.max(0.0, Math.min(1.0, sBA)); // Clamp result to [0, 1]
        }

         double nBA = INFERENCE_CONFIDENCE_DISCOUNT * Math.min(nAB, Math.min(nodeA.tv.count, nodeB.tv.count)); // Factor in node counts

        TruthValue tvBA = new TruthValue(sBA, nBA);
        // Add link with targets reversed
        return addLink(Link.LinkType.INHERITANCE, tvBA, bId, aId);
     }

      // --- Temporal & Higher-Order Inference (Placeholders/Simplified) ---

      /**
       * Example Rule: Predictive Modus Ponens
       * If PredictiveImplication(A, B) holds and A holds now, infer B will hold later.
       * Needs temporal context/time representation. Simplified: create B with discounted TV.
       */
      public Atom predictiveModusPonens(Link predictiveLink, Atom causeAtom) {
          if (predictiveLink == null || causeAtom == null ||
               predictiveLink.type != Link.LinkType.PREDICTIVE_IMPLICATION ||
               predictiveLink.targets.size() != 2 ||
              !predictiveLink.targets.get(0).equals(causeAtom.id)) {
               return null; // Invalid input
          }
          String effectAtomId = predictiveLink.targets.get(1);
          Atom existingEffect = getAtom(effectAtomId);

          // Calculate resulting TV for the effect B.
          // Strength depends on P(B|A)=s(Link) and P(A)=s(Cause). Simplest: s(B) ~ s(Link)*s(Cause).
          // Count reflects evidence from both link and cause, discounted.
          double sEffect = predictiveLink.tv.strength * causeAtom.tv.strength;
           double nCount = INFERENCE_CONFIDENCE_DISCOUNT * Math.min(predictiveLink.tv.count, causeAtom.tv.count);
           TruthValue inferredTV = new TruthValue(sEffect, nCount);

           // If effect atom doesn't exist, create it conceptually (need its type info)
           // For simplicity, assume effect atom exists or can be created with default TV.
           // In a real system, we'd need a way to know B's type (Node/Link).
           // Let's just attempt to add/revise it using its ID.
            Atom baseEffect = getAtom(effectAtomId);
            if (baseEffect == null) {
                 // Cannot materialize the effect without knowing its structure (Node name, Link targets)
                  System.err.println("Predictive Modus Ponens Error: Effect atom " + effectAtomId + " unknown.");
                  return null;
            }

            // Create a temporary Atom structure for the inferred result to use addAtom's revision
            Atom inferredAtom;
            if (baseEffect instanceof Node) {
                 inferredAtom = new Node(((Node)baseEffect).name, inferredTV);
            } else if (baseEffect instanceof Link) {
                 // This requires knowing the original targets and type of the effect link, which isn't stored here easily.
                 // Hacky: reconstruct if possible, or just update TV directly (less safe concurrency).
                 // Safer: Only update TV of existing atom if structure matches known effectAtomId.
                 System.err.println("Predictive Modus Ponens on Link effects not fully implemented.");
                 // Simplified: Create a placeholder node representing the link? No, inconsistent.
                 // Return the existing effect atom with revised TV? Requires modification of addAtom flow.
                 // Safest for now: Return null or require effect atom to be a Node.
                  return null;
            } else {
                  System.err.println("Predictive Modus Ponens Error: Unknown atom type for effect: " + baseEffect);
                  return null;
            }

           // Add/revise the inferred effect into the KB
           return addAtom(inferredAtom);
      }

       /**
       * Placeholder for Unification Rule (From Fetch Example: CrispUnificationRule)
       * Binds variables in a quantified expression (FOR_ALL, EXISTS) or pattern to specific values.
       * This is complex, involving pattern matching and substitution.
       * Simplified: If pattern has variable $X and instance is Atom C, return pattern with $X replaced by C's ID.
       * Truth value handling depends on the quantifier type and rule semantics.
       *
       * @param patternAtom Typically a Link with variables (e.g., FOR_ALL $X, Eval(Pred, $X))
       * @param instanceAtoms Map of VariableNode ID to concrete Atom ID/Value for binding.
       * @return A new Atom representing the instantiated pattern, or null on failure.
       */
       public Atom unificationAndInstantiation(Atom patternAtom, Map<String, String> bindings) {
           // Basic placeholder - requires recursive traversal and substitution
           System.out.println("Unification/Instantiation rule needs full implementation.");
            if (patternAtom instanceof Node && ((Node) patternAtom).name.startsWith(VARIABLE_PREFIX)) {
                 String varName = ((Node) patternAtom).name;
                 if (bindings.containsKey(varName)) {
                     return getAtom(bindings.get(varName)); // Return the bound atom
                 } else {
                     return patternAtom; // Unbound variable remains
                 }
            } else if (patternAtom instanceof Link) {
                 Link linkPattern = (Link) patternAtom;
                 List<String> instantiatedTargets = new ArrayList<>();
                 boolean changed = false;
                 for (String targetId : linkPattern.getOriginalTargetOrder()) { // Need original order if sequence matters
                     Atom targetAtom = getAtom(targetId); // Need to handle target structure recursively
                      if (targetAtom instanceof Node && ((Node) targetAtom).name.startsWith(VARIABLE_PREFIX)) {
                          String varName = ((Node) targetAtom).name;
                          if (bindings.containsKey(varName)) {
                             instantiatedTargets.add(bindings.get(varName));
                              changed = true;
                          } else {
                              instantiatedTargets.add(targetId); // Keep variable
                          }
                      } else {
                         // Recursively instantiate complex targets? Omitted for brevity.
                          instantiatedTargets.add(targetId);
                      }
                 }
                 if (changed) {
                     // Create a new Link with instantiated targets. Truth value may need adjustment.
                     // Using original TV for simplicity here. Add/revise the new link.
                      return addLink(linkPattern.type, linkPattern.tv, instantiatedTargets.toArray(new String[0]));
                 } else {
                     return patternAtom; // No change
                 }
            }
           return patternAtom; // No variables found at top level
       }


    // --- Inference Control ---

    /**
     * Performs simple forward chaining for a number of steps.
     * Applies basic inference rules (Deduction, Inversion) somewhat exhaustively.
     * Note: Exhaustive search is NOT scalable. Real systems need heuristics, focus, indexing.
     *
     * @param maxSteps Max inference cycles.
     */
    public void forwardChain(int maxSteps) {
        // System.out.println("\n--- Starting Forward Chaining (Max Steps: " + maxSteps + ") ---");
         int totalInferences = 0;

        // Optimization: Consider only working with recently updated/added atoms.
        // Requires tracking changes, adds complexity. Simple version iterates all potential premises.

        for (int step = 0; step < maxSteps; step++) {
            // System.out.println("Step " + (step + 1));
            List<Atom> currentAtoms = new ArrayList<>(knowledgeBase.values()); // Snapshot
             int inferencesThisStep = 0;
             Set<String> processedPremisePairs = new HashSet<>(); // Avoid re-applying same rule to same premises in one step

            // Collect relevant links for efficiency (still suboptimal without indexing)
             List<Link> inheritanceLinks = currentAtoms.stream()
                    .filter(a -> a instanceof Link && ((Link) a).type == Link.LinkType.INHERITANCE && a.tv.getConfidence() > MIN_CONFIDENCE_THRESHOLD)
                    .map(a -> (Link) a)
                    .collect(Collectors.toList());

             // TODO: Add other link types (Predictive, etc.) for forward chaining rules if implemented.

            // --- Try Deduction ---
             for (Link linkAB : inheritanceLinks) {
                 for (Link linkBC : inheritanceLinks) {
                     if (isValidInheritance(linkAB) && isValidInheritance(linkBC) && linkAB.targets.get(1).equals(linkBC.targets.get(0))) {
                          String pairId = linkAB.id + "::" + linkBC.id;
                         if (processedPremisePairs.add(pairId)) { // Check if already processed this pair
                              Link inferred = deduction(linkAB, linkBC);
                              if (inferred != null && inferred.tv.count > Math.max(linkAB.tv.count, linkBC.tv.count) * 0.1) { // Crude check for significant result
                                  // System.out.println("  Deduction: " + linkAB.id + " + " + linkBC.id + " => " + inferred.id + " " + inferred.tv);
                                   inferencesThisStep++;
                              }
                          }
                      }
                 }
             }

             // --- Try Inversion ---
              // Apply to a snapshot to avoid concurrent modification issues if inversion adds links immediately?
              // Current addAtom is thread-safe, so iterating over the list *should* be okay,
              // but new additions might not be seen in this step's iteration.
              Set<String> invertedInThisStep = new HashSet<>();
             for (Link linkAB : inheritanceLinks) {
                 if (invertedInThisStep.add(linkAB.id)) { // Process each link for inversion only once per step
                      Link inferred = inversion(linkAB);
                      if (inferred != null && inferred.tv.count > linkAB.tv.count * 0.1) { // Crude check
                         // System.out.println("  Inversion: " + linkAB.id + " => " + inferred.id + " " + inferred.tv);
                          inferencesThisStep++;
                      }
                  }
             }

              // TODO: Add calls to other forward rules (Temporal, HO) here.

             totalInferences += inferencesThisStep;
             if (inferencesThisStep == 0) {
                 // System.out.println("No new inferences made this step. Stopping chaining.");
                 break;
             }
        }
         // System.out.println("--- Forward Chaining Finished (" + totalInferences + " total inferences attempted) ---");
    }


    /**
     * Performs basic backward chaining to find evidence for a target Atom ID.
     * Tries to derive the target using available inference rules (currently Deduction, Inversion).
     * Extremely simplified: Limited depth, no sophisticated variable handling or goal decomposition.
     * Does NOT currently implement the fetch example's specific backward chaining logic for action sequences.
     * Scalability: Exhaustive search for relevant rules, unsuitable for large KBs. Indexing is needed.
     *
     * @param targetId ID of the goal Atom.
     * @param maxDepth Maximum recursion depth.
     * @return The potentially updated/verified target Atom from the KB, or null if not found/derivable.
     */
     public Atom backwardChain(String targetId, int maxDepth) {
         System.out.println("\n--- Starting Backward Chaining (Target: " + targetId + ", Max Depth: " + maxDepth + ") ---");
         Atom result = backwardChainRecursive(targetId, maxDepth, new HashSet<>());
         System.out.println("--- Backward Chaining Finished (Final TV for " + targetId + ": " + (result != null ? result.tv : "Not Found") + ") ---");
         return result;
     }

     // Recursive helper for backward chaining
     private Atom backwardChainRecursive(String targetId, int depth, Set<String> visited) {
         String indent = "  ".repeat(maxDepth - depth);
          // System.out.println(indent + "BC Seek: " + targetId + " (Depth " + depth + ")");

          Atom existingAtom = getAtom(targetId); // Get current state from KB

         if (depth <= 0) {
             // System.out.println(indent + "-> Max depth reached.");
              return existingAtom; // Return whatever evidence exists at max depth
         }
         if (!visited.add(targetId)) { // Add targetId to visited set; returns false if already present
             // System.out.println(indent + "-> Cycle detected or already visited in this path.");
              return existingAtom; // Avoid infinite loops
         }
         // Note: Atom could have been pruned between check and here in highly concurrent scenarios, though unlikely.

         // Try to find rules that *conclude* with targetId
         Atom derivedAtom = null; // Store the best derivation found

         // --- Attempt Deduction ---
         // If target is Inh(A, C), look for Inh(A, B) and Inh(B, C) for all possible B.
          Optional<Pair<String, String>> acPair = parseInheritanceTarget(targetId);
          if (acPair.isPresent()) {
              String aId = acPair.get().first;
              String cId = acPair.get().second;
               // System.out.println(indent + "BC Check: Can Deduce " + aId + "->" + cId + "?");

               // Iterate through ALL nodes as potential intermediates 'B'. Highly inefficient!
               // Indexing (e.g., map from node ID to incoming/outgoing links) is essential here.
                List<Link> potentialPremises = findPotentialDeductionPremises(aId, cId, depth, visited);
                 for(int i=0; i< potentialPremises.size(); i+=2){
                     Link premiseAB = potentialPremises.get(i);
                     Link premiseBC = potentialPremises.get(i+1);
                      if (premiseAB != null && premiseBC != null) {
                         // System.out.println(indent + " Found Deduction premises: " + premiseAB.id + ", " + premiseBC.id);
                          Link conclusion = deduction(premiseAB, premiseBC); // Applies rule & revises in KB
                         derivedAtom = strongerOf(derivedAtom, conclusion); // Keep track of best derivation
                      }
                 }
           }

          // --- Attempt Inversion ---
          // If target is Inh(B, A), look for Inh(A, B)
          Optional<Pair<String, String>> baPair = parseInheritanceTarget(targetId);
           if (baPair.isPresent()) {
              String bId = baPair.get().first;
              String aId = baPair.get().second;
               // System.out.println(indent + "BC Check: Can Invert to " + bId + "->" + aId + "?");

                Link premiseAB = findPotentialInversionPremise(aId, bId, depth, visited);
                if (premiseAB != null) {
                     // System.out.println(indent + " Found Inversion premise: " + premiseAB.id);
                     Link conclusion = inversion(premiseAB); // Applies rule & revises in KB
                    derivedAtom = strongerOf(derivedAtom, conclusion);
                 }
           }

            // --- Attempt Temporal / HO Rules ---
            // TODO: Add backward rules for PredictiveImplication, Sequence, FOR_ALL, EXISTS etc.
            // E.g., If target is B, look for PredictiveImplication(A, B) and recursively seek A.
             // E.g., If target is P(C), look for FOR_ALL $X, P($X) and apply instantiation if C matches.


           visited.remove(targetId); // Allow visiting this node again via different paths

           // Return the strongest evidence found: the originally existing atom or the best derived one.
            Atom finalResult = strongerOf(existingAtom, derivedAtom);
             // if(finalResult != null) System.out.println(indent + "BC Result for " + targetId + ": " + finalResult.tv);
            return finalResult;
     }

      // Helper to parse "Inheritance[Node(A), Node(B)]" into ("Node(A)", "Node(B)")
      private Optional<Pair<String, String>> parseInheritanceTarget(String targetId) {
          if (targetId != null && targetId.startsWith("INHERITANCE[") && targetId.endsWith("]")) {
               String content = targetId.substring("INHERITANCE[".length(), targetId.length() - 1);
              String[] parts = content.split(", "); // Assumes standard List.toString() format
              if (parts.length == 2) {
                  return Optional.of(new Pair<>(parts[0], parts[1]));
              }
          }
          return Optional.empty();
      }

      // Helper: Find premises A->B and B->C needed to deduce target A->C
      // Inefficient exhaustive search placeholder.
       private List<Link> findPotentialDeductionPremises(String aId, String cId, int depth, Set<String> visited) {
          List<Link> foundPremises = new ArrayList<>();
           knowledgeBase.values().stream() // Iterate all atoms! Inefficient!
                   .filter(atom -> atom instanceof Node) // Find potential 'B' nodes
                   .forEach(potentialB -> {
                      String bId = potentialB.id;
                       // Construct the potential premise IDs
                       // Note: Link ID depends on sorted targets. Ensure constructor logic matches ID generation.
                      String premiseAB_Id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(aId, bId), TruthValue.DEFAULT).id;
                      String premiseBC_Id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(bId, cId), TruthValue.DEFAULT).id;

                       // Recursively seek premises
                      Atom premiseAB = backwardChainRecursive(premiseAB_Id, depth - 1, new HashSet<>(visited)); // Clone visited set for parallel branches
                      Atom premiseBC = backwardChainRecursive(premiseBC_Id, depth - 1, new HashSet<>(visited));

                       if (premiseAB instanceof Link && premiseBC instanceof Link) {
                           foundPremises.add((Link)premiseAB);
                           foundPremises.add((Link)premiseBC);
                       }
                   });
           return foundPremises;
      }

        // Helper: Find premise A->B needed to invert to target B->A
      // Inefficient placeholder.
      private Link findPotentialInversionPremise(String aId, String bId, int depth, Set<String> visited) {
            String premiseAB_Id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(aId, bId), TruthValue.DEFAULT).id;
           Atom premiseAB = backwardChainRecursive(premiseAB_Id, depth - 1, visited); // Can share visited set here as it's sequential? No, could loop back. Clone. new HashSet<>(visited));
            if (premiseAB instanceof Link) {
               return (Link) premiseAB;
           }
           return null;
      }

      // Helper to return the atom with stronger evidence (higher confidence or count)
      private Atom strongerOf(Atom a1, Atom a2) {
          if (a1 == null) return a2;
          if (a2 == null) return a1;
          // Compare confidence first, then count as tie-breaker
           double conf1 = a1.tv.getConfidence();
           double conf2 = a2.tv.getConfidence();
          if (Math.abs(conf1 - conf2) > 1e-6) {
              return conf1 > conf2 ? a1 : a2;
          } else {
              return a1.tv.count >= a2.tv.count ? a1 : a2; // Higher count wins tie
          }
      }


    // --- Agent Components ---

    /**
     * Represents the agent's interaction interface with its environment.
     */
    public interface Environment {
        /** Gets the current perceivable state features and their values [0, 1]. */
        Map<String, Double> getCurrentStateFeatures();

        /** Gets the scalar reward received since the last action. */
        double getReward();

        /** Performs the named action in the environment. */
        void performAction(String actionName);

        /** Returns a list of currently possible action names. */
        List<String> getAvailableActions();

        /** Checks if the environment episode has ended. */
        boolean isTerminated();

        /** Resets the environment for a new episode. */
        void reset();
    }


    /**
     * The main execution loop for the agent.
     * @param env The environment instance.
     * @param maxEpisodes Number of episodes to run.
     * @param maxStepsPerEpisode Max steps within each episode.
     * @param goalDefinition A map defining the goal state features and desired values.
     */
    public void runAgent(Environment env, int maxEpisodes, int maxStepsPerEpisode, Map<String, Double> goalDefinition) {
         String goalAtomId = defineGoalAtom(goalDefinition);
        System.out.println("Agent Starting. Goal: " + goalAtomId);

        for (int episode = 1; episode <= maxEpisodes; episode++) {
            env.reset();
             Map<String, Double> initialState = env.getCurrentStateFeatures();
            String previousStateAtomId = perceiveAndRepresentState(initialState);
            String lastActionAtomId = null;
            double totalEpisodeReward = 0;

            System.out.println("\n--- Episode " + episode + " Starting ---");

            for (int step = 1; step <= maxStepsPerEpisode; step++) {
                // 1. Perceive current state (already done for step 1)
                 System.out.println("Step " + step + ": State=" + previousStateAtomId);

                // 2. Select Action based on goal and learned knowledge
                 lastActionAtomId = selectAction(env, previousStateAtomId, goalAtomId);
                 if (lastActionAtomId == null) {
                      System.out.println("No action selected/available. Ending episode.");
                     break;
                 }
                  // Extract action name (assumes ActionNode naming convention)
                  String actionName = getActionNameFromId(lastActionAtomId);
                  System.out.println("  Executing: " + actionName);


                 // 3. Execute Action in Environment
                 env.performAction(actionName);

                 // 4. Perceive Outcome (New State and Reward)
                  Map<String, Double> newStateFeatures = env.getCurrentStateFeatures();
                  double reward = env.getReward();
                  String currentStateAtomId = perceiveAndRepresentState(newStateFeatures);
                  totalEpisodeReward += reward;
                   System.out.println("  Outcome: NewState=" + currentStateAtomId + ", Reward=" + reward);

                  // 5. Learn from Experience (Update KB based on transition and reward)
                 learnFromExperience(previousStateAtomId, lastActionAtomId, currentStateAtomId, reward, goalAtomId);

                 // 6. Reasoning Step (integrate knowledge) - Optional, can be intensive
                 // forwardChain(1); // Run a single step of forward chaining

                 // Prepare for next step
                 previousStateAtomId = currentStateAtomId;

                  // 7. Check for termination
                  if (env.isTerminated()) {
                       System.out.println("Environment terminated episode.");
                      break;
                  }
             } // End step loop

             System.out.printf("--- Episode %d Finished. Total Reward: %.2f. KB Size: %d ---\n",
                               episode, totalEpisodeReward, knowledgeBase.size());

            // Periodic intense reasoning or KB cleanup between episodes?
            // forwardChain(3);
            // pruneKnowledgeBase(pruneTargetSize, MIN_IMPORTANCE_THRESHOLD * 0.5); // More aggressive prune maybe

        } // End episode loop

        System.out.println("\nAgent Run Finished.");
    }

      private String getActionNameFromId(String actionAtomId) {
          Atom actionAtom = getAtom(actionAtomId);
           if (actionAtom instanceof Node && actionAtom.id.startsWith("Node(" + ACTION_PREFIX)) {
               return ((Node) actionAtom).name.substring(ACTION_PREFIX.length());
           }
           System.err.println("Warning: Could not determine action name from ID: " + actionAtomId);
           return "UNKNOWN_ACTION"; // Fallback
      }


    /** Creates/updates a Goal Atom in the KB based on feature definition. */
     private String defineGoalAtom(Map<String, Double> goalDefinition) {
         // Represent goal as a complex state or use a dedicated GoalLink/Node.
         // Simple approach: Node named based on key features.
         String goalFeaturesString = goalDefinition.entrySet().stream()
                .filter(e -> e.getValue() > 0.5) // Include features desired to be 'true'
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.joining("_"));
         if (goalFeaturesString.isEmpty()) goalFeaturesString = "DefaultGoal";

         String goalNodeName = GOAL_PREFIX + goalFeaturesString;
         Node goalNode = getOrCreateNode(goalNodeName, new TruthValue(1.0, 100.0)); // High confidence goal
         // Optionally add links defining the goal features, e.g., using EVALUATION:
         // addLink(Link.LinkType.EVALUATION, TruthValue.TRUE, goalNode, featureNode);
         return goalNode.id;
     }

     /**
     * Represents the current environment state perception as Atoms in the KB.
     * Creates/updates Nodes for salient features and potentially a composite state Atom.
     *
     * @param stateFeatures Map of feature names to their values [0,1].
     * @return The ID of the Atom representing the composite current state.
     */
    private String perceiveAndRepresentState(Map<String, Double> stateFeatures) {
        List<String> activeFeatureNodeIds = new ArrayList<>();
         TruthValue observationTV = new TruthValue(1.0, 1.0); // Base confidence for observation

        for (Map.Entry<String, Double> entry : stateFeatures.entrySet()) {
            String featureName = entry.getKey();
            double featureValue = entry.getValue(); // Strength of the feature observation

            // Create/update node for the feature itself
            Node featureNode = getOrCreateNode(featureName, new TruthValue(featureValue, observationTV.count)); // Update with observed strength/confidence
             // Simplistic: If feature is strongly present, add to composite state representation
             if (featureValue > 0.7) { // Threshold for being 'active' in the composite state
                 activeFeatureNodeIds.add(featureNode.id);
             }

             // Could also create EVALUATION links like Eval(FeatureName, Self, Value), but adds complexity.
         }

        // Create a composite state node (simple approach based on active features)
         Collections.sort(activeFeatureNodeIds); // Canonical order
         String compositeStateName;
         if (activeFeatureNodeIds.isEmpty()) {
            compositeStateName = STATE_PREFIX + "Empty";
        } else {
            // Extract node names for readability (Node(Name) -> Name)
            String featureNames = activeFeatureNodeIds.stream()
                                   .map(id -> getAtom(id) instanceof Node ? ((Node)getAtom(id)).name : id)
                                   .collect(Collectors.joining("_"));
            compositeStateName = STATE_PREFIX + featureNames;
         }

         // Add/update the composite state node, representing the current overall situation.
         Node stateNode = getOrCreateNode(compositeStateName, new TruthValue(1.0, 2.0)); // Observed state = True, moderate confidence

        // Optionally link the composite state to its features:
         // for (String featureId : activeFeatureNodeIds) {
         //    addLink(Link.LinkType.INHERITANCE, TruthValue.TRUE, stateNode.id, featureId); // State -> Feature component
         // }

         return stateNode.id;
    }

    /**
     * Learns from the latest experience (State -> Action -> NewState, Reward).
     * Creates/updates PredictiveImplicationLinks and potentially action utility links.
     * References Temporal Logic concepts.
     *
     * @param prevStateId ID of the state before the action.
     * @param actionId ID of the action taken.
     * @param currentStateId ID of the state after the action.
     * @param reward Reward received after the action.
     * @param goalAtomId ID of the current goal (for utility calculation).
     */
     private void learnFromExperience(String prevStateId, String actionId, String currentStateId, double reward, String goalAtomId) {
         if (prevStateId == null || actionId == null || currentStateId == null) {
             System.out.println("Learning: Skipping (incomplete S-A-S' info)");
             return;
         }

         TruthValue observationEvidence = new TruthValue(1.0, 1.0); // Basic count for one observation
         TruthValue causalStrength = new TruthValue(1.0, observationEvidence.count); // Assume direct causality for observed transition

         // 1. Learn State Transition Prediction: PredictiveImplication(AND(prevState, action), currentState)
         //    This requires representing AND(prevState, action). Simpler: Use a Sequence.
         //    Represent Sequence(prevState, action) -> currentState. Sequence could be node or link.
         //    Using SEQUENTIAL_AND Link (Targets ordered conceptually [prev, action]):
          String sequenceId = Link.LinkType.SEQUENTIAL_AND + Arrays.asList(prevStateId, actionId).stream().sorted().collect(Collectors.toList()).toString(); // ID canonical
          Link sequenceLink = addLink(Link.LinkType.SEQUENTIAL_AND, observationEvidence, prevStateId, actionId); // Create the sequence link if needed

          // Now the prediction: Sequence -> CurrentState
         addLink(Link.LinkType.PREDICTIVE_IMPLICATION, causalStrength, sequenceLink.id, currentStateId);


         // 2. Learn Reward Association:
          Node rewardNode = getOrCreateNode(REWARD_NODE_NAME);
          if (reward > 0.1) { // Threshold for positive reward
              // Strengthen link: CurrentState -> RewardNode
               TruthValue rewardTV = new TruthValue(1.0, reward * 2.0); // Stronger evidence for higher reward
               addLink(Link.LinkType.PREDICTIVE_IMPLICATION, rewardTV, currentStateId, rewardNode.id);
          } else if (reward < -0.1) { // Negative reward (punishment)
              // Strengthen link: CurrentState -> NOT RewardNode (needs NOT link/node representation)
              // Simple approach: Link with low strength CurrentState -> RewardNode
               TruthValue noRewardTV = new TruthValue(0.0, Math.abs(reward) * 2.0);
               addLink(Link.LinkType.PREDICTIVE_IMPLICATION, noRewardTV, currentStateId, rewardNode.id);
          }
         // If reward is near zero, could add evidence for TV(0.5, low_count).


          // 3. Reflective Learning: Update Action Utility (Simple Heuristic)
          //    Did this action (in the prev state) lead towards the goal or reward?
           boolean gotReward = reward > 0.1;
           // Heuristic: Did we get closer to the goal? (Requires defining goal similarity/distance)
           // For simplicity, use reward as the main driver for utility update.
           Node utilityConcept;
           TruthValue utilityUpdateTV;
           double utilityEvidence = ACTION_UTILITY_LEARNING_RATE; // How much this experience counts

           if(gotReward) {
               utilityConcept = getOrCreateNode("GoodAction"); // Concept for beneficial actions
                utilityUpdateTV = new TruthValue(1.0, utilityEvidence);
           } else {
                utilityConcept = getOrCreateNode("BadAction"); // Concept for detrimental actions
                 utilityUpdateTV = new TruthValue(1.0, utilityEvidence); // Evidence that action leads to BadAction concept
                 // Alternatively, could decrease strength of Action->GoodAction link.
           }

           // Add/Revise: Action -> GoodAction/BadAction
          addLink(Link.LinkType.ACTION_UTILITY, utilityUpdateTV, actionId, utilityConcept.id);
           // System.out.println("  Updated utility for action " + actionId + " towards " + utilityConcept.id);

     }


     /**
     * Selects an action based on available actions, goal, and learned knowledge.
     * Uses a simple heuristic: explores occasionally, otherwise chooses action
     * estimated to be best based on predicted outcomes (leading to reward/goal)
     * and learned direct utility (Action -> GoodAction links).
     * This is NOT deep planning (like backward chaining search for sequences).
     *
     * @param env Environment to get available actions.
     * @param currentStateId Current state Atom ID.
     * @param goalAtomId Goal Atom ID.
     * @return The ID of the selected action Atom.
     */
     private String selectAction(Environment env, String currentStateId, String goalAtomId) {
        List<String> availableActionNames = env.getAvailableActions();
        if (availableActionNames.isEmpty()) {
             System.out.println("Warning: No available actions.");
            return null;
        }

        // --- Exploration vs Exploitation ---
         if (random.nextDouble() < EXPLORATION_PROBABILITY) {
             System.out.println("  Action Selection: Exploring (Random Choice)");
              String randomActionName = availableActionNames.get(random.nextInt(availableActionNames.size()));
              return getOrCreateNode(ACTION_PREFIX + randomActionName).id; // Create/get node for the action
         }

        // --- Exploitation: Evaluate available actions ---
         System.out.println("  Action Selection: Exploiting (Evaluating options)");
         Map<String, Double> actionScores = new HashMap<>();
          Node rewardNode = getOrCreateNode(REWARD_NODE_NAME);
          Node goodActionNode = getOrCreateNode("GoodAction");
         // Node badActionNode = getOrCreateNode("BadAction"); // Optional


        for (String actionName : availableActionNames) {
            Node actionNode = getOrCreateNode(ACTION_PREFIX + actionName);
            String actionId = actionNode.id;
            double score = 0.0; // Default score (slight anti-bias for unknown actions?)

            // 1. Check Learned Direct Utility (Action -> GoodAction / BadAction)
             Link utilityLinkGood = (Link) getAtom(new Link(Link.LinkType.ACTION_UTILITY, Arrays.asList(actionId, goodActionNode.id), TruthValue.DEFAULT).id);
             // Link utilityLinkBad = (Link) getAtom(new Link(Link.LinkType.ACTION_UTILITY, Arrays.asList(actionId, badActionNode.id), TruthValue.DEFAULT).id);

            if (utilityLinkGood != null) {
                score += utilityLinkGood.tv.strength * utilityLinkGood.tv.getConfidence(); // Weighted strength
             }
            // if (utilityLinkBad != null) {
            //    score -= utilityLinkBad.tv.strength * utilityLinkBad.tv.getConfidence();
            // }

            // 2. Estimate Outcome Utility (Check if predicted state leads to Reward/Goal)
             //    Simplified check: Find PredictiveImplication(Seq(State,Action), NextState)
             //    Then check if PredictiveImplication(NextState, RewardNode/GoalNode) exists.
              String sequenceId = new Link(Link.LinkType.SEQUENTIAL_AND, Arrays.asList(currentStateId, actionId), TruthValue.DEFAULT).id;
              List<Link> predictions = findLinksStartingWith(sequenceId, Link.LinkType.PREDICTIVE_IMPLICATION);

              double maxPredictedRewardStrength = 0.0;
              for(Link prediction : predictions) {
                  if (prediction.targets.size() == 2) {
                     String predictedStateId = prediction.targets.get(1);
                      // Check if predicted state leads to reward
                      Link stateToRewardLink = (Link) getAtom(new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(predictedStateId, rewardNode.id), TruthValue.DEFAULT).id);
                      if (stateToRewardLink != null) {
                           // Estimate combined probability: P(Next|Seq) * P(Reward|Next)
                           double predictedRewardStrength = prediction.tv.strength * stateToRewardLink.tv.strength;
                           // Use confidence as weight? P(Next|Seq) * w1 * P(Reward|Next) * w2 ? Too complex here.
                           maxPredictedRewardStrength = Math.max(maxPredictedRewardStrength, predictedRewardStrength);
                       }
                      // TODO: Add similar check for predictedStateId -> goalAtomId (using INHERITANCE or SIMILARITY?)
                  }
              }
             score += maxPredictedRewardStrength * 0.5; // Add weighted prediction score (factor adjustable)


              actionScores.put(actionId, score);
              System.out.printf("    Action %s evaluated. Utility=%.3f, PredictedReward=%.3f -> Score=%.3f\n", actionName, score - (maxPredictedRewardStrength*0.5), maxPredictedRewardStrength, score);
         }

        // --- Choose Best Action ---
         String bestActionId = actionScores.entrySet().stream()
                 .max(Map.Entry.comparingByValue())
                 .map(Map.Entry::getKey)
                 // Fallback to random if all scores are zero or map is empty (shouldn't happen if actions exist)
                  .orElseGet(() -> {
                       System.out.println("  Warning: No best action found, choosing random.");
                       String randomActionName = availableActionNames.get(random.nextInt(availableActionNames.size()));
                      return getOrCreateNode(ACTION_PREFIX + randomActionName).id;
                  });

         System.out.println("  Selected: " + getActionNameFromId(bestActionId) + " (Score: " + actionScores.getOrDefault(bestActionId, -99.9) + ")");
         return bestActionId;
     }


      // Helper to find links efficiently (placeholder for proper indexing)
      private List<Link> findLinksStartingWith(String sourceId, Link.LinkType type) {
          List<Link> results = new ArrayList<>();
           knowledgeBase.values().stream()
              .filter(a -> a instanceof Link)
              .map(a -> (Link)a)
              .filter(l -> l.type == type && l.targets.size() > 0 && l.targets.get(0).equals(sourceId)) // Assumes source is first element (not canonical order!) - Needs refinement based on Link type semantics
              .forEach(results::add);
           return results;
      }


    // --- Main Method & Example Usage ---
    public static void main(String[] args) {
        UnifiedPLN pln = new UnifiedPLN(500); // Smaller KB size for demo

        // --- Basic KB Operations Example ---
         System.out.println("--- Basic KB Example ---");
         Node cat = pln.getOrCreateNode("Cat", new TruthValue(0.1, 5)); // P(Cat) ~ 0.1, N=5
         Node mammal = pln.getOrCreateNode("Mammal", new TruthValue(0.3, 10));
         Node animal = pln.getOrCreateNode("Animal", new TruthValue(0.6, 20));

         Link catMammal = pln.addLink(Link.LinkType.INHERITANCE, new TruthValue(0.95, 15), cat, mammal); // Cats are mammals
         Link mammalAnimal = pln.addLink(Link.LinkType.INHERITANCE, new TruthValue(0.98, 18), mammal, animal); // Mammals are animals

         System.out.println("Initial KB:");
         pln.knowledgeBase.values().forEach(System.out::println);

        // --- Inference Example ---
         System.out.println("\n--- Inference Example ---");
         pln.forwardChain(2);

         System.out.println("\nKB after Forward Chaining:");
         pln.knowledgeBase.values().forEach(System.out::println);

         String catAnimalId = new Link(Link.LinkType.INHERITANCE, Arrays.asList(cat.id, animal.id), TruthValue.DEFAULT).id;
         System.out.println("Query Cat->Animal (derived): " + pln.getAtom(catAnimalId));

         String animalCatId = new Link(Link.LinkType.INHERITANCE, Arrays.asList(animal.id, cat.id), TruthValue.DEFAULT).id;
         System.out.println("Query Animal->Cat (derived by inversion): " + pln.getAtom(animalCatId));


        // --- Agent Example ---
         System.out.println("\n--- Agent Simulation Example ---");
         SimpleBinaryWorld world = new SimpleBinaryWorld(15);
         Map<String, Double> goal = new HashMap<>();
         goal.put("IsInStateB", 1.0); // Goal is to be in State B

         pln.runAgent(world, 5, 20, goal); // Run 5 episodes, max 20 steps each


        // --- Inspect Agent Learning ---
         System.out.println("\n--- Final Agent Knowledge Inspection ---");
         System.out.println("Final KB Size: " + pln.knowledgeBase.size());

         Node actionToggle = pln.getOrCreateNode(ACTION_PREFIX + "Toggle");
         Node actionNothing = pln.getOrCreateNode(ACTION_PREFIX + "DoNothing");
         Node goodAction = pln.getOrCreateNode("GoodAction");
         Node badAction = pln.getOrCreateNode("BadAction");

         System.out.println("Utility Toggle->Good: " + pln.getAtom(new Link(Link.LinkType.ACTION_UTILITY, Arrays.asList(actionToggle.id, goodAction.id), TruthValue.DEFAULT).id));
         System.out.println("Utility Nothing->Good: " + pln.getAtom(new Link(Link.LinkType.ACTION_UTILITY, Arrays.asList(actionNothing.id, goodAction.id), TruthValue.DEFAULT).id));
        System.out.println("Utility Toggle->Bad: " + pln.getAtom(new Link(Link.LinkType.ACTION_UTILITY, Arrays.asList(actionToggle.id, badAction.id), TruthValue.DEFAULT).id));
        System.out.println("Utility Nothing->Bad: " + pln.getAtom(new Link(Link.LinkType.ACTION_UTILITY, Arrays.asList(actionNothing.id, badAction.id), TruthValue.DEFAULT).id));

         Node stateA = pln.getOrCreateNode(STATE_PREFIX + "IsInStateA");
         Node stateB = pln.getOrCreateNode(STATE_PREFIX + "IsInStateB");
         Node reward = pln.getOrCreateNode(REWARD_NODE_NAME);

         // Query learned reward prediction from state B (should be high)
        String seqIdStateB = new Link(Link.LinkType.SEQUENTIAL_AND, Arrays.asList(stateB.id), TruthValue.DEFAULT).id; // Need sequence with action leading to stateB
         // Query directly StateB -> Reward link
        System.out.println("Reward prediction StateB->Reward: " + pln.getAtom(new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(stateB.id, reward.id), TruthValue.DEFAULT).id));
         System.out.println("Reward prediction StateA->Reward: " + pln.getAtom(new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(stateA.id, reward.id), TruthValue.DEFAULT).id));


    }

    // --- Simple Environment Example Implementation ---
    public static class SimpleBinaryWorld implements Environment {
        private final int maxStepsPerEpisode;
        private boolean isStateA;
        private int currentStep;

        public SimpleBinaryWorld(int maxSteps) {
             this.maxStepsPerEpisode = maxSteps;
             reset();
        }

        @Override
        public Map<String, Double> getCurrentStateFeatures() {
            Map<String, Double> features = new HashMap<>();
            features.put("IsInStateA", isStateA ? 1.0 : 0.0);
            features.put("IsInStateB", !isStateA ? 1.0 : 0.0);
            return features;
        }

        @Override
        public double getReward() {
            // Reward for being in State B
             return isStateA ? -0.1 : 1.0; // Small penalty for A, large reward for B
        }

        @Override
        public void performAction(String actionName) {
            currentStep++;
            if ("Toggle".equals(actionName)) {
                isStateA = !isStateA;
                // System.out.println("  World: Toggled. Now in " + (isStateA ? "A" : "B"));
            } else if ("DoNothing".equals(actionName)) {
                // System.out.println("  World: Did Nothing. Still in " + (isStateA ? "A" : "B"));
                 // No state change
            } else {
                 System.out.println("  World: Unknown action '" + actionName + "'");
            }
        }

        @Override
        public List<String> getAvailableActions() {
            return Arrays.asList("Toggle", "DoNothing");
        }

        @Override
        public boolean isTerminated() {
            return currentStep >= maxStepsPerEpisode;
        }

        @Override
        public void reset() {
            this.isStateA = true; // Always start in state A
            this.currentStep = 0;
            // System.out.println("World Reset. Starting in State A.");
        }
    }

    // --- Utility Pair Class ---
     private static class Pair<U, V> {
        final U first;
        final V second;
        Pair(U first, V second) { this.first = first; this.second = second; }
        // Basic equals/hashCode if needed, not required for current usage
    }

} // End of UnifiedPLN class