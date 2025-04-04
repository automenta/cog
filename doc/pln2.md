# Revise, unify, and enhance the code.
- Prioritization: Implements attention and forgetting.  Consider a 'token allocator' for regulating various kinds of system resources.
- Perception/Action: Very simplistic mappings are used. Real-world agents need complex perception and motor control.  Provide a developer-friendly API for rapidly and fluently developing Environments to interface with arbitrary systems. 
- Planning: selectAction uses a very basic strategy. Real planning would involve deeper backward chaining, searching through sequences, and handling resource constraints.
- Temporal Logic: The use of SEQUENCE links is basic. A full implementation would use the more detailed Event Calculus concepts (initiate, terminate, holds) and associated PLN rules from "Temporal and Causal Inference", provided below.
- Higher-Order Logic: Quantifiers, variables, and complex schema manipulation added as extensions, leveraging the existing Atom structure.
- Scalability: The exhaustive searches in forwardChain and the simple backward chaining helpers are not scalable to large knowledge bases. More sophisticated indexing and heuristic inference control (like described in the PLN text, e.g., using importance or bandit algorithms) would be necessary.

## Deliver a single, self-documenting Java class that is:
- Complete (fully functional)
- Correct (bug-free and logically sound)
- Compact (minimal code footprint)
- Consolidated (avoids unnecessary separation)
- Deduplicated (no redundant logic)
- Modular (logically organized, supporting abstraction)
- Self-documenting (uses clear naming and structure, minimizing comments)

## The result should be, in comparison to this version:
- More computationally powerful
- More efficient (faster execution, lower memory usage)
- More elegant
- More extensible


```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Redesigned Probabilistic Logic Networks (PLN) - Simplified Core (SCPLN).
 *
 * This class provides a redesigned implementation of core PLN concepts, aiming for
 * simplicity, efficiency, computational power, elegance, and extensibility,
 * while retaining essential functionality for uncertain reasoning.
 *
 * Key Design Principles:
 * 1.  **Simplicity:** Drastically simplified TruthValue representation and inference formulas.
 * 2.  **Atom-Centric:** Knowledge represented as Atoms (Nodes and Links) in a graph.
 * 3.  **Probabilistic Core:** Uses probability rules where applicable, with simple heuristics elsewhere.
 * 4.  **Two-Component TruthValue:** `(strength, count)` representing belief strength and evidence amount.
 * 5.  **Core Inference:** Focuses on Deduction, Inversion, and Revision as fundamental operations.
 * 6.  **Extensibility:** Modular design allowing for future addition of features (quantifiers, advanced temporal logic, etc.).
 * 7.  **Self-Documentation:** Clear naming and structure minimize the need for extensive comments.
 *
 * Discarded/Simplified PLN Concepts:
 * -   Indefinite/Distributional TruthValues: Replaced by simpler (strength, count).
 * -   Complex Deduction Variants (Concept Geometry): Using simplified independence-based formula.
 * -   Complex Revision/Count Formulas: Using simple weighted average and count addition/discounting.
 * -   Explicit Fuzzy Logic, Detailed Pattern Theory, Third-Order Probabilities for Quantifiers.
 * -   Explicit long Inference Trails (relying on revision confidence dynamics).
 * -   Detailed Event Calculus, specific HOJ/HOS markers.
 */
public class SimpleCorePLN {

// --- Configuration ---
    /**
     * Default evidence sensitivity parameter (k in confidence = N / (N + k)).
     * Higher k means confidence increases slower with evidence count N.
     */
    private static final double DEFAULT_EVIDENCE_SENSITIVITY = 1.0;

    /**
     * Confidence discount factor applied during inference rules (except revision).
     * Represents uncertainty introduced by the inference process itself. Value < 1.
     */
    private static final double INFERENCE_CONFIDENCE_DISCOUNT_FACTOR = 0.75;

    /** Threshold for confidence below which an atom might be ignored or pruned. */
    private static final double MIN_CONFIDENCE_THRESHOLD = 0.01;

    // --- Knowledge Representation ---
    // --- Knowledge Base ---
    private final Map<String, Atom> knowledgeBase = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        SimpleCorePLN scpln = new SimpleCorePLN();
        scpln.runExample();
    }

    /**
     * Adds or updates an Atom in the knowledge base.
     * If an Atom with the same ID exists, performs revision.
     *
     * @param atom The Atom to add or update.
     * @return The resulting Atom in the knowledge base (potentially revised).
     */
    public synchronized Atom addAtom(Atom atom) {
        return knowledgeBase.compute(atom.id, (id, existing) -> {
            if (existing == null) {
                return atom;
            } else {
                existing.tv = existing.tv.merge(atom.tv);
                // System.out.println("Revised: " + existing.id + " -> " + existing.tv);
                return existing;
            }
        });
    }

    /** Gets an Atom by its ID. */
    public Atom getAtom(String id) {
        return knowledgeBase.get(id);
    }

    /** Gets a Node by its name, creating it with default TV if it doesn't exist. */
    public Node getOrCreateNode(String name) {
        String nodeId = "Node(" + name + ")";
        return (Node) knowledgeBase.computeIfAbsent(nodeId, id -> new Node(name, TruthValue.DEFAULT));
    }

    /** Creates a Link Atom and adds it to the KB, performing revision if it exists. */
    public Link addLink(Link.LinkType type, TruthValue tv, String... targetIds) {
        Link link = new Link(type, Arrays.asList(targetIds), tv);
        return (Link) addAtom(link);
    }

    public Link addLink(Link.LinkType type, TruthValue tv, Atom... targets) {
        List<String> targetIds = Arrays.stream(targets).map(a -> a.id).collect(Collectors.toList());
        Link link = new Link(type, targetIds, tv);
        return (Link) addAtom(link);
    }

    /**
     * Performs PLN Deduction.
     * Premises: Inheritance(A, B), Inheritance(B, C)
     * Conclusion: Inheritance(A, C)
     * Uses simplified formula: sAC = sAB * sBC + 0.5 * (1 - sAB) [Heuristic, ignores term probabilities]
     * or optionally the formula needing term probabilities:
     * sAC = sAB*sBC + (1-sAB)*(sC - sB*sBC)/(1-sB)
     *
     * @param linkAB Inheritance(A, B)
     * @param linkBC Inheritance(B, C)
     * @param useTermProbabilities If true, use the formula requiring P(B) and P(C).
     * @return The inferred Inheritance(A, C) Link, or null if inference is not possible.
     */
    public Link deduction(Link linkAB, Link linkBC, boolean useTermProbabilities) {
        if (linkAB == null || linkBC == null ||
                linkAB.type != Link.LinkType.INHERITANCE || linkBC.type != Link.LinkType.INHERITANCE ||
                linkAB.targets.size() != 2 || linkBC.targets.size() != 2) {
            return null; // Invalid input
        }

        String aId = linkAB.targets.get(0); // Source of first link
        String bId1 = linkAB.targets.get(1); // Target of first link
        String bId2 = linkBC.targets.get(0); // Source of second link
        String cId = linkBC.targets.get(1); // Target of second link

        // Check if the intermediate node matches
        if (!bId1.equals(bId2)) {
            return null;
        }
        String bId = bId1;

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId);
        Atom nodeC = getAtom(cId);

        if (nodeA == null || nodeB == null || nodeC == null) {
            System.err.println("Deduction Warning: Missing node(s) A, B, or C.");
            return null; // Cannot perform deduction without nodes
        }

        double sAB = linkAB.tv.strength;
        double sBC = linkBC.tv.strength;
        double nAB = linkAB.tv.count;
        double nBC = linkBC.tv.count;

        double sAC;
        if (useTermProbabilities) {
            // Use formula requiring term probabilities P(B) and P(C)
            // We assume Node strength represents its probability
            double sB = nodeB.tv.strength;
            double sC = nodeC.tv.strength;

            if (Math.abs(1.0 - sB) < 1e-9) {
                // Avoid division by zero/near-zero if P(B) is 1 (or very close)
                // If P(B)=1, then P(C|A) = P(C|B) = sBC (assuming B covers everything relevant for A->C)
                sAC = sBC;
            } else {
                sAC = sAB * sBC + (1.0 - sAB) * (sC - sB * sBC) / (1.0 - sB);
            }
            // Clamp result to [0, 1] as formula can sometimes exceed bounds
            sAC = Math.max(0.0, Math.min(1.0, sAC));

        } else {
            // Use simplified heuristic formula: sAC = sAB * sBC + 0.5 * (1 - sAB)
            sAC = sAB * sBC + 0.5 * (1.0 - sAB);
        }

        // Combine counts using a simple discounting heuristic
        double nAC = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * Math.min(nAB, nBC);
        // Optionally factor in node counts? For simplicity, let's not for now.
        // double nAC = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * Math.min(nAB, nBC, nodeB.tv.count, nodeC.tv.count);

        TruthValue tvAC = new TruthValue(sAC, nAC);

        // Create and add/revise the new link
        return addLink(Link.LinkType.INHERITANCE, tvAC, aId, cId);
    }

    /**
     * Performs PLN Inversion (related to Bayes' Rule).
     * Premise: Inheritance(A, B)
     * Conclusion: Inheritance(B, A)
     * Formula: sBA = sAB * sA / sB
     * Requires term probabilities P(A) and P(B).
     *
     * @param linkAB Inheritance(A, B)
     * @return The inferred Inheritance(B, A) Link, or null if inference is not possible.
     */
    public Link inversion(Link linkAB) {
        if (linkAB == null || linkAB.type != Link.LinkType.INHERITANCE || linkAB.targets.size() != 2) {
            return null; // Invalid input
        }

        String aId = linkAB.targets.get(0); // Source
        String bId = linkAB.targets.get(1); // Target

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId);

        if (nodeA == null || nodeB == null) {
            System.err.println("Inversion Warning: Missing node(s) A or B.");
            return null; // Cannot perform inversion without nodes
        }

        double sAB = linkAB.tv.strength;
        double nAB = linkAB.tv.count;
        double sA = nodeA.tv.strength; // P(A)
        double sB = nodeB.tv.strength; // P(B)

        double sBA;
        if (sB < 1e-9) {
            // Avoid division by zero. If P(B)=0, what is P(A|B)? Undefined.
            // Return a maximally uncertain result.
            System.err.println("Inversion Warning: P(B) is near zero for B=" + bId);
            sBA = 0.5; // Assign default strength
            nAB = 0;   // Assign zero count
        } else {
            sBA = sAB * sA / sB;
            // Clamp result to [0, 1] as formula can sometimes exceed bounds
            sBA = Math.max(0.0, Math.min(1.0, sBA));
        }


        // Combine counts using a simple discounting heuristic
        double nBA = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * nAB;
        // Optionally factor in node counts?
        // double nBA = INFERENCE_CONFIDENCE_DISCOUNT_FACTOR * Math.min(nAB, nodeA.tv.count, nodeB.tv.count);


        TruthValue tvBA = new TruthValue(sBA, nBA);

        // Create and add/revise the new link (note reversed targets)
        return addLink(Link.LinkType.INHERITANCE, tvBA, bId, aId);
    }

    /**
     * Performs simple forward chaining inference for a number of steps.
     * Applies deduction and inversion rules exhaustively at each step.
     *
     * @param maxSteps The maximum number of inference cycles to perform.
     */
    public void forwardChain(int maxSteps) {
        System.out.println("\n--- Starting Forward Chaining (Max Steps: " + maxSteps + ") ---");
        Set<String> processedLinks = new HashSet<>(); // Avoid redundant processing in one step

        for (int step = 0; step < maxSteps; step++) {
            System.out.println("Step " + (step + 1));
            List<Atom> currentAtoms = new ArrayList<>(knowledgeBase.values());
            int inferencesMadeThisStep = 0;
            processedLinks.clear();

            // --- Try Deduction ---
            List<Link> inheritanceLinks = currentAtoms.stream()
                    .filter(a -> a instanceof Link && ((Link) a).type == Link.LinkType.INHERITANCE)
                    .map(a -> (Link) a)
                    .collect(Collectors.toList());

            for (Link linkAB : inheritanceLinks) {
                for (Link linkBC : inheritanceLinks) {
                    // Ensure the link structure A->B, B->C
                    if (linkAB.targets.size() == 2 && linkBC.targets.size() == 2 &&
                            linkAB.targets.get(1).equals(linkBC.targets.get(0))) {
                        String premisePairId = linkAB.id + "|" + linkBC.id;
                        if (!processedLinks.contains(premisePairId)) {
                            Link inferredAC = deduction(linkAB, linkBC, true); // Use term probs
                            if (inferredAC != null) {
                                System.out.println("  Deduction: " + linkAB.id + ", " + linkBC.id + " => " + inferredAC.id + " " + inferredAC.tv);
                                inferencesMadeThisStep++;
                            }
                            processedLinks.add(premisePairId);
                            processedLinks.add(linkBC.id + "|" + linkAB.id); // Avoid reverse check
                        }
                    }
                }
            }

            // --- Try Inversion ---
            // Create a copy to avoid ConcurrentModificationException if inversion adds links
            List<Link> currentInheritanceLinks = new ArrayList<>(inheritanceLinks);
            for (Link linkAB : currentInheritanceLinks) {
                if (!processedLinks.contains(linkAB.id + "|inv")) {
                    Link inferredBA = inversion(linkAB);
                    if (inferredBA != null) {
                        System.out.println("  Inversion: " + linkAB.id + " => " + inferredBA.id + " " + inferredBA.tv);
                        inferencesMadeThisStep++;
                    }
                    processedLinks.add(linkAB.id + "|inv");
                }
            }

            if (inferencesMadeThisStep == 0) {
                System.out.println("No new inferences made. Stopping.");
                break;
            }
        }
        System.out.println("--- Forward Chaining Finished ---");
    }


    // --- Inference Rules ---

    /**
     * Performs simple backward chaining to find evidence for a target Atom.
     * Very basic implementation: looks for direct rules (deduction/inversion)
     * that could produce the target and recursively checks premises.
     * Does not handle complex variable unification or sophisticated pruning.
     *
     * @param targetId The ID of the target Atom.
     * @param maxDepth Maximum recursion depth.
     * @return The potentially updated/verified target Atom, or null if not found/derived.
     */
    public Atom backwardChain(String targetId, int maxDepth) {
        System.out.println("\n--- Starting Backward Chaining (Target: " + targetId + ", Max Depth: " + maxDepth + ") ---");
        Atom result = backwardChainRecursive(targetId, maxDepth, new HashSet<>());
        System.out.println("--- Backward Chaining Finished ---");
        return result;
    }

    private Atom backwardChainRecursive(String targetId, int depth, Set<String> visited) {
        System.out.println("  ".repeat(maxDepth - depth) + "BC: Seeking " + targetId + " (Depth " + depth + ")");

        if (depth <= 0) {
            System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Max depth reached.");
            return getAtom(targetId); // Return existing knowledge if depth limit reached
        }
        if (visited.contains(targetId)) {
            System.out.println("  ".repeat(maxDepth - depth + 1) + "-> Already visited.");
            return getAtom(targetId); // Avoid cycles
        }
        visited.add(targetId);

        Atom currentTargetAtom = getAtom(targetId);
        // We aim to potentially *improve* the confidence of the current atom

        // --- Try rules that could produce the target ---
        // Example: If target is Inheritance(A, C), try deduction

        // 1. Can Deduction produce targetId?
        // Target must be an Inheritance Link, e.g., Inheritance(A, C)
        // Need to find potential B, and check for Inheritance(A, B) and Inheritance(B, C)
        List<Link> potentialConclusionsDeduction = findPotentialDeductionConclusions(targetId, depth, visited);


        // 2. Can Inversion produce targetId?
        // Target must be an Inheritance Link, e.g., Inheritance(B, A)
        // Need to check for Inheritance(A, B)
        Link potentialConclusionInversion = findPotentialInversionConclusion(targetId, depth, visited);

        // --- Combine Evidence (Revision) ---
        // In this simple version, we just add any derived evidence back to the KB,
        // which handles revision automatically via addAtom.
        // A more sophisticated BC would collect all evidence paths and revise at the end.

        // The recursive calls to find premises (inside the findPotential* methods)
        // will have already updated the KB via addAtom.
        // So, we just return the potentially updated target atom from the KB.

        Atom finalAtom = getAtom(targetId);
        System.out.println("  ".repeat(maxDepth - depth) + "BC: Result for " + targetId + ": " + (finalAtom != null ? finalAtom.tv : "Not Found"));
        visited.remove(targetId); // Allow revisiting in different branches
        return finalAtom;
    }

    // --- Inference Control (Simplified) ---

    // Helper for BC: Find premises for Deduction
    private List<Link> findPotentialDeductionConclusions(String targetAC_Id, int depth, Set<String> visited) {
        List<Link> conclusions = new ArrayList<>();
        Atom targetAtom = getAtom(targetAC_Id);
        // Check if target IS an Inheritance link A->C
        if (!(targetAtom instanceof Link) || ((Link) targetAtom).type != Link.LinkType.INHERITANCE || ((Link) targetAtom).targets.size() != 2) {
            // Target cannot be produced by deduction
            return conclusions;
        }
        String targetA_Id = ((Link) targetAtom).targets.get(0);
        String targetC_Id = ((Link) targetAtom).targets.get(1);

        System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Deduction: Target is " + targetA_Id + "->" + targetC_Id);


        // Iterate through *all* atoms to find potential intermediate B nodes
        for (Atom potentialB : knowledgeBase.values()) {
            if (potentialB instanceof Node) {
                String potentialB_Id = potentialB.id;
                // Look for required premises: A->B and B->C
                String premiseAB_Id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(targetA_Id, potentialB_Id), TruthValue.DEFAULT).id;
                String premiseBC_Id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(potentialB_Id, targetC_Id), TruthValue.DEFAULT).id;

                System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Deduction: Trying intermediate B=" + potentialB_Id);


                // Recursively try to find/derive premises
                Atom premiseAB_Atom = backwardChainRecursive(premiseAB_Id, depth - 1, visited);
                Atom premiseBC_Atom = backwardChainRecursive(premiseBC_Id, depth - 1, visited);

                if (premiseAB_Atom instanceof Link && premiseBC_Atom instanceof Link &&
                        ((Link) premiseAB_Atom).type == Link.LinkType.INHERITANCE &&
                        ((Link) premiseBC_Atom).type == Link.LinkType.INHERITANCE) {
                    // If premises found/derived, perform deduction to update target
                    System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Deduction: Found premises for B=" + potentialB_Id + ", applying deduction...");
                    Link conclusion = deduction((Link) premiseAB_Atom, (Link) premiseBC_Atom, true);
                    if (conclusion != null) {
                        conclusions.add(conclusion); // The addLink inside deduction handles revision
                    }
                } else {
                    // System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Deduction: Could not find/derive premises for B=" + potentialB_Id);
                }
            }
        }
        return conclusions; // Returns list of potentially revised target links
    }

    // Helper for BC: Find premise for Inversion
    private Link findPotentialInversionConclusion(String targetBA_Id, int depth, Set<String> visited) {
        Atom targetAtom = getAtom(targetBA_Id);
        // Check if target IS an Inheritance link B->A
        if (!(targetAtom instanceof Link) || ((Link) targetAtom).type != Link.LinkType.INHERITANCE || ((Link) targetAtom).targets.size() != 2) {
            // Target cannot be produced by inversion
            return null;
        }
        String targetB_Id = ((Link) targetAtom).targets.get(0);
        String targetA_Id = ((Link) targetAtom).targets.get(1);

        System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Inversion: Target is " + targetB_Id + "->" + targetA_Id);


        // Look for required premise: A->B
        String premiseAB_Id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(targetA_Id, targetB_Id), TruthValue.DEFAULT).id;

        System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Inversion: Seeking premise " + premiseAB_Id);


        // Recursively try to find/derive premise
        Atom premiseAB_Atom = backwardChainRecursive(premiseAB_Id, depth - 1, visited);

        if (premiseAB_Atom instanceof Link && ((Link) premiseAB_Atom).type == Link.LinkType.INHERITANCE) {
            System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Inversion: Found premise, applying inversion...");
            Link conclusion = inversion((Link) premiseAB_Atom);
            return conclusion; // addLink inside inversion handles revision
        } else {
            System.out.println("  ".repeat(maxDepth - depth + 1) + "BC/Inversion: Could not find/derive premise.");
            return null;
        }
    }

    public void runExample() {
        System.out.println("--- SCPLN Example ---");

        // Define some nodes (Concepts)
        Node cat = new Node("cat", new TruthValue(0.1, 10)); // P(cat) = 0.1, based on 10 observations
        Node mammal = new Node("mammal", new TruthValue(0.2, 20)); // P(mammal) = 0.2, N=20
        Node animal = new Node("animal", new TruthValue(0.5, 50)); // P(animal) = 0.5, N=50
        Node cute = new Node("cute", new TruthValue(0.3, 15));   // P(cute) = 0.3, N=15
        Node pet = new Node("pet", new TruthValue(0.15, 25));  // P(pet) = 0.15, N=25

        addAtom(cat);
        addAtom(mammal);
        addAtom(animal);
        addAtom(cute);
        addAtom(pet);

        // Define some initial knowledge (Links)
        // Cats are mammals (with high certainty)
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.95, 30), cat.id, mammal.id);
        // Mammals are animals (with high certainty)
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.98, 40), mammal.id, animal.id);
        // Animals are cute (moderately certain)
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.6, 10), animal.id, cute.id);
        // Pets are cute (fairly certain)
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.8, 20), pet.id, cute.id);
        // Cats are pets (quite certain)
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.9, 18), cat.id, pet.id);


        System.out.println("\nInitial Knowledge Base:");
        knowledgeBase.values().forEach(System.out::println);

        // --- Test Forward Chaining ---
        forwardChain(2); // Run for 2 steps

        System.out.println("\nKnowledge Base after Forward Chaining:");
        knowledgeBase.values().forEach(System.out::println);

        // Example: Check derived knowledge
        String cat_animal_id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(cat.id, animal.id), TruthValue.DEFAULT).id;
        Atom cat_animal = getAtom(cat_animal_id);
        System.out.println("\nDerived Cat->Animal: " + (cat_animal != null ? cat_animal.tv : "Not derived"));

        String mammal_cat_id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(mammal.id, cat.id), TruthValue.DEFAULT).id;
        Atom mammal_cat = getAtom(mammal_cat_id);
        System.out.println("Derived Mammal->Cat (Inversion): " + (mammal_cat != null ? mammal_cat.tv : "Not derived"));

        // --- Add conflicting information and test Revision ---
        System.out.println("\n--- Testing Revision ---");
        // Add new, slightly conflicting evidence that cats are mammals
        addLink(Link.LinkType.INHERITANCE, new TruthValue(0.90, 10), cat.id, mammal.id);

        System.out.println("Knowledge Base after adding conflicting Cat->Mammal info:");
        knowledgeBase.values().stream()
                .filter(a -> a.id.equals(new Link(Link.LinkType.INHERITANCE, Arrays.asList(cat.id, mammal.id), TruthValue.DEFAULT).id))
                .forEach(System.out::println);
        // Expected: Strength between 0.95 and 0.90, Count = 30 + 10 = 40

        // --- Test Backward Chaining ---
        // Try to derive/verify Cat -> Cute
        String cat_cute_id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(cat.id, cute.id), TruthValue.DEFAULT).id;
        backwardChain(cat_cute_id, 3); // Allow depth for Cat->Animal->Cute or Cat->Pet->Cute

        Atom cat_cute = getAtom(cat_cute_id);
        System.out.println("\nResult of Backward Chaining for Cat->Cute: " + (cat_cute != null ? cat_cute.tv : "Not Found/Derived"));

        // Try to derive/verify Animal -> Cat (expected low confidence via inversion)
        String animal_cat_id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(animal.id, cat.id), TruthValue.DEFAULT).id;
        backwardChain(animal_cat_id, 3);

        Atom animal_cat = getAtom(animal_cat_id);
        System.out.println("\nResult of Backward Chaining for Animal->Cat: " + (animal_cat != null ? animal_cat.tv : "Not Found/Derived"));


    }

    /** Base class for all knowledge elements (Nodes and Links). */
    public abstract static class Atom {
        public final String id; // Unique identifier, typically derived from content
        public TruthValue tv;

        protected Atom(String id, TruthValue tv) {
            this.id = id;
            this.tv = tv;
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
            return id + " " + tv;
        }

        /** Generate a canonical ID based on the Atom's content. */
        protected abstract String generateId();
    }

    /** Represents concepts, entities, constants, or variables. */
    public static class Node extends Atom {
        public final String name; // Human-readable name/symbol

        public Node(String name, TruthValue tv) {
            super(null, tv); // ID will be generated
            this.name = name;
            // Reassign id after name is set
            ((Atom) this).id = generateId();
        }

        @Override
        protected String generateId() {
            return "Node(" + name + ")";
        }

        @Override
        public String toString() {
            return name + " " + tv;
        }
    }


    // --- Example Usage ---

    /** Represents relationships between Atoms. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets; // IDs of Atoms involved in the link

        public Link(LinkType type, List<String> targets, TruthValue tv) {
            super(null, tv); // ID will be generated
            this.type = type;
            // Sort targets for canonical ID generation
            this.targets = targets.stream().sorted().collect(Collectors.toList());
            // Reassign id after type/targets are set
            ((Atom) this).id = generateId();
        }

        @Override
        protected String generateId() {
            return type + targets.toString();
        }

        @Override
        public String toString() {
            return type + targets.toString() + " " + tv;
        }

        public enum LinkType {
            INHERITANCE, // Asymmetric probabilistic implication P(Target|Source)
            SIMILARITY,  // Symmetric association
            EVALUATION,  // Predicate application: Predicate(Args...)
            EXECUTION,   // Schema execution output: Schema(Args...) -> Output
            AND, OR, NOT,// Logical combinations
            SEQUENCE     // Temporal sequence (Placeholder)
// Other types like EQUIVALENCE, FOR_ALL, EXISTS could be added
        }
    }

    /**
     * Represents uncertain truth: strength of belief and amount of evidence.
     * Strength: Probability-like value [0, 1].
     * Count: Non-negative value representing the amount of evidence.
     */
    public static class TruthValue {
        // Default TV represents maximum ignorance
        public static final TruthValue DEFAULT = new TruthValue(0.5, 0.0);
        public final double strength;
        public final double count;

        public TruthValue(double strength, double count) {
            // Clamp strength to [0, 1] and count to non-negative
            this.strength = Math.max(0.0, Math.min(1.0, strength));
            this.count = Math.max(0.0, count);
        }

        /** Calculates confidence based on count and sensitivity factor. */
        public double getConfidence() {
            return count / (count + DEFAULT_EVIDENCE_SENSITIVITY);
        }

        /**
         * Merges this TruthValue with another using weighted averaging (Revision).
         * Assumes the two TVs represent evidence about the *same* atom.
         */
        public TruthValue merge(TruthValue other) {
            if (other == null) return this;
            double totalCount = this.count + other.count;
            if (totalCount == 0) {
                // Avoid division by zero; return default or average strength if counts are zero
                return new TruthValue((this.strength + other.strength) / 2.0, 0.0);
            }
            double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new TruthValue(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("<s=%.3f, c=%.2f, w=%.3f>", strength, count, getConfidence());
        }
    }
}
```

```java
import java.util.*;
import java.util.stream.Collectors;

/**
 * Autonomous Agent based on Redesigned Probabilistic Logic Networks (SCPLN).
 *
 * This class extends the SimpleCorePLN to create an autonomous agent capable of
 * learning from experience, exhibiting reflectivity, and demonstrating basic
 * self-metaprogramming capabilities through logical inference on its own state
 * and knowledge.
 *
 * Key Agent Features:
 * 1.  **Embodied Logic:** The SCPLN knowledge base represents the agent's beliefs about the world and itself.
 * 2.  **Learning Cycle:** Perceives, acts, observes outcomes, learns consequences (predictive links), and reasons (forward chaining).
 * 3.  **Goal-Driven:** Acts to achieve goals represented as desired states (Atoms with high strength/confidence).
 * 4.  **Reflectivity:** Internal states (goals, confidence, recent actions) and even strategies can be represented as Atoms, allowing the agent to reason about itself.
 * 5.  **Self-Metaprogramming (Logical):** By inferring the utility of different strategies (represented as Atoms), the agent can logically adapt its action-selection process.
 * 6.  **Recursive Elegance:** The same SCPLN inference mechanisms operate on both external world knowledge and internal reflective knowledge.
 * 7.  **Self-Unification (Conceptual):** The agent identifies patterns and relationships within its knowledge base (including its own structure/behavior patterns) through standard inference.
 */
public class AutonomousSCPLN {

    // --- Configuration ---
    private static final double REWARD_ATOM_STRENGTH = 1.0; // Strength of a "reward occurred" atom
    private static final double REWARD_OBSERVATION_COUNT = 5.0; // Evidence for reward observation
    private static final double STATE_OBSERVATION_COUNT = 10.0; // Evidence for state observation
    private static final double ACTION_CONFIDENCE_DISCOUNT = 0.9; // Confidence discount for inferred action consequences
    private static final String GOAL_PREFIX = "Goal_";
    private static final String STATE_PREFIX = "State_";
    private static final String ACTION_PREFIX = "Action_";
    private static final String STRATEGY_PREFIX = "Strategy_";
    private static final String REWARD_NODE_NAME = "RewardReceived";
    private static final String DEFAULT_STRATEGY_NAME = "Default_TryPredictedReward";
    // Internal SCPLN instance for knowledge and reasoning
    private final SimpleCorePLN pln;
    // Agent State (Represented also within PLN where useful)
    private String currentGoalId = null; // ID of the Atom representing the current primary goal
    private String lastActionId = null;
    private String previousStateId = null; // Represents the perceived state before the last action
    // Simple strategy representation
    private String activeStrategyId;


    public AutonomousSCPLN() {
        this.pln = new SimpleCorePLN();
        setupInitialState();
    }

    // --- Main Example Runner ---
    public static void main(String[] args) {
        AutonomousSCPLN agent = new AutonomousSCPLN();
        Environment world = new SimpleBinaryWorld();

        // Define the goal: Be in State B
        Map<String, Double> goalFeatures = new HashMap<>();
        goalFeatures.put("IsStateB", 1.0); // We want IsStateB to be true

        agent.run(world, 15, goalFeatures);

        // Inspect final knowledge
        System.out.println("\n--- Inspecting Final Knowledge ---");
        SimpleCorePLN pln = agent.getPln();
        System.out.println("Knowledge Base Size: " + pln.knowledgeBase.size());

        // Check learned utility of actions
        SimpleCorePLN.Node toggleAction = pln.getOrCreateNode(ACTION_PREFIX + "ToggleState");
        SimpleCorePLN.Node nothingAction = pln.getOrCreateNode(ACTION_PREFIX + "DoNothing");
        SimpleCorePLN.Node goodActionConcept = pln.getOrCreateNode("GoodAction");

        String toggleUtilityId = new SimpleCorePLN.Link(SimpleCorePLN.Link.LinkType.INHERITANCE, Arrays.asList(toggleAction.id, goodActionConcept.id), SimpleCorePLN.TruthValue.DEFAULT).id;
        String nothingUtilityId = new SimpleCorePLN.Link(SimpleCorePLN.Link.LinkType.INHERITANCE, Arrays.asList(nothingAction.id, goodActionConcept.id), SimpleCorePLN.TruthValue.DEFAULT).id;

        System.out.println("Learned Utility (ToggleState -> GoodAction): " + pln.getAtom(toggleUtilityId));
        System.out.println("Learned Utility (DoNothing -> GoodAction): " + pln.getAtom(nothingUtilityId));
        // Expected: ToggleState should have higher inferred utility (strength/confidence) than DoNothing because it leads to reward state B.

        // Check learned state consequence
        SimpleCorePLN.Node stateA = pln.getOrCreateNode(STATE_PREFIX + "IsStateA");
        SimpleCorePLN.Node stateB = pln.getOrCreateNode(STATE_PREFIX + "IsStateB");
        SimpleCorePLN.Node rewardNode = pln.getOrCreateNode(REWARD_NODE_NAME);

        String stateA_RewardId = new SimpleCorePLN.Link(SimpleCorePLN.Link.LinkType.INHERITANCE, Arrays.asList(stateA.id, rewardNode.id), SimpleCorePLN.TruthValue.DEFAULT).id;
        String stateB_RewardId = new SimpleCorePLN.Link(SimpleCorePLN.Link.LinkType.INHERITANCE, Arrays.asList(stateB.id, rewardNode.id), SimpleCorePLN.TruthValue.DEFAULT).id;

        System.out.println("Learned Consequence (StateA -> Reward): " + pln.getAtom(stateA_RewardId));
        System.out.println("Learned Consequence (StateB -> Reward): " + pln.getAtom(stateB_RewardId));
        // Expected: StateB -> Reward link should have higher strength/confidence.


    }

    /** Sets up initial nodes like Reward and default strategy */
    private void setupInitialState() {
        // Ensure Reward node exists
        pln.getOrCreateNode(REWARD_NODE_NAME);
        // Set up default strategy
        SimpleCorePLN.Node defaultStrategy = pln.getOrCreateNode(STRATEGY_PREFIX + DEFAULT_STRATEGY_NAME);
        // Make the default strategy highly confident initially
        defaultStrategy.tv = new SimpleCorePLN.TruthValue(1.0, 100.0);
        pln.addAtom(defaultStrategy);
        activeStrategyId = defaultStrategy.id;
        System.out.println("Agent Initialized. Active Strategy: " + activeStrategyId);
    }

    /**
     * Represents the current environmental state as Atoms in the PLN knowledge base.
     *
     * @param state The current state map from the environment.
     * @return The ID of the Atom representing the composite current state.
     */
    private String perceiveState(Map<String, Double> state) {
        // Simple perception: Create a node for each feature above threshold,
        // and combine them into a single state representation (e.g., an AND link).
        // A more robust implementation would handle continuous values, relationships etc.
        List<String> featureNodeIds = new ArrayList<>();
        for (Map.Entry<String, Double> entry : state.entrySet()) {
            // For simplicity, threshold features
            if (entry.getValue() > 0.5) {
                String featureName = entry.getKey();
                SimpleCorePLN.Node featureNode = pln.getOrCreateNode(featureName);
                // Update observation - high confidence for direct perception
                featureNode.tv = new SimpleCorePLN.TruthValue(entry.getValue(), STATE_OBSERVATION_COUNT);
                pln.addAtom(featureNode);
                featureNodeIds.add(featureNode.id);
            }
        }

        // Create a composite state representation (e.g., an AND link of active features)
        // Sorting ensures a canonical ID for the same state features.
        Collections.sort(featureNodeIds);
        if (featureNodeIds.isEmpty()) {
            SimpleCorePLN.Node nullState = pln.getOrCreateNode(STATE_PREFIX + "Null");
            nullState.tv = new SimpleCorePLN.TruthValue(1.0, STATE_OBSERVATION_COUNT);
            pln.addAtom(nullState);
            return nullState.id;
        }

        // Represent state as a specific node ID based on features for simplicity here.
        // A Link (e.g., AND) representation is more semantically correct but adds complexity.
        String stateName = STATE_PREFIX + String.join("_", featureNodeIds.stream().map(id -> pln.getAtom(id).toString().split(" ")[0]).collect(Collectors.toList()));
        SimpleCorePLN.Node stateNode = pln.getOrCreateNode(stateName);
        stateNode.tv = new SimpleCorePLN.TruthValue(1.0, STATE_OBSERVATION_COUNT); // State is observed
        pln.addAtom(stateNode);
        return stateNode.id;
    }

    /**
     * Learns from the outcome of the last action.
     * Creates predictive links: (PreviousState, Action) -> CurrentState
     * Creates outcome links: CurrentState -> Reward (if any)
     * Runs forward chaining to integrate knowledge.
     *
     * @param currentStateId The ID of the currently perceived state Atom.
     * @param reward         The reward received after the last action.
     */
    private void learnFromExperience(String currentStateId, double reward) {
        if (previousStateId == null || lastActionId == null) {
            System.out.println("Learning: Skipping (no previous state/action)");
            return; // Cannot learn without prior context
        }

        System.out.println("Learning: PrevState=" + previousStateId + ", Action=" + lastActionId + ", CurrState=" + currentStateId + ", Reward=" + reward);

        // 1. Learn State Transition: Sequence(PreviousState, Action) -> CurrentState
        // Represent the action consequence. Use high confidence because it was observed.
        String sequenceId = STATE_PREFIX + previousStateId + "_" + lastActionId;
        SimpleCorePLN.Node sequenceNode = pln.getOrCreateNode(sequenceId);
        sequenceNode.tv = new SimpleCorePLN.TruthValue(1.0, STATE_OBSERVATION_COUNT); // Representing the occurrence of the sequence
        pln.addAtom(sequenceNode);

        pln.addLink(SimpleCorePLN.Link.LinkType.INHERITANCE, // Or a specific PREDICTION type
                new SimpleCorePLN.TruthValue(1.0, STATE_OBSERVATION_COUNT), // Observed transition
                sequenceNode.id,
                currentStateId);

        // 2. Learn Reward Association: CurrentState -> Reward (if reward > 0)
        if (reward > 0.0) {
            SimpleCorePLN.Node rewardNode = pln.getOrCreateNode(REWARD_NODE_NAME);
            // Represent this specific reward instance
            SimpleCorePLN.Node rewardInstance = pln.getOrCreateNode("RewardInstance_" + System.nanoTime());
            rewardInstance.tv = new SimpleCorePLN.TruthValue(REWARD_ATOM_STRENGTH * reward, REWARD_OBSERVATION_COUNT); // Scale strength by reward amount?
            pln.addAtom(rewardInstance);

            // Link the state that led to reward
            pln.addLink(SimpleCorePLN.Link.LinkType.INHERITANCE, // Or PREDICTION
                    new SimpleCorePLN.TruthValue(1.0, REWARD_OBSERVATION_COUNT), // Observed consequence
                    currentStateId, // The state where reward was received
                    rewardInstance.id); // Link to the specific reward instance

            // Generalize: state -> general reward concept (will be revised over time)
            pln.addLink(SimpleCorePLN.Link.LinkType.INHERITANCE, // Or PREDICTION
                    new SimpleCorePLN.TruthValue(REWARD_ATOM_STRENGTH, REWARD_OBSERVATION_COUNT),
                    currentStateId,
                    rewardNode.id); // Link state to the general concept of reward

            System.out.println("  Learned: " + currentStateId + " -> " + rewardNode.id);
        } else {
            // Optionally learn non-reward: CurrentState -> NOT Reward
            SimpleCorePLN.Node rewardNode = pln.getOrCreateNode(REWARD_NODE_NAME);
            pln.addLink(SimpleCorePLN.Link.LinkType.INHERITANCE,
                    new SimpleCorePLN.TruthValue(0.0, REWARD_OBSERVATION_COUNT), // Observed non-reward
                    currentStateId,
                    rewardNode.id);
            // Or create a NOT link if NOT type exists
        }


        // 3. Run Inference to integrate new knowledge
        System.out.println("  Running forward chaining to integrate experience...");
        pln.forwardChain(1); // Run 1 step of inference to propagate effects

        // 4. Reflective Learning (Example: Assess Action Utility)
        // If reward was received, strengthen the belief that the last action
        // (in the previous state) is a "GoodAction". This requires representing actions.
        SimpleCorePLN.Node actionNode = pln.getAtom(lastActionId) instanceof SimpleCorePLN.Node ? (SimpleCorePLN.Node) pln.getAtom(lastActionId) : null;
        if (actionNode != null) {
            SimpleCorePLN.Node goodActionNode = pln.getOrCreateNode("GoodAction"); // Reflective concept
            double newStrength = (reward > 0.0) ? 1.0 : 0.0;
            double newCount = ACTION_CONFIDENCE_DISCOUNT; // Low confidence initially
            // Infer: Action -> GoodAction (based on reward)
            SimpleCorePLN.Link actionUtilityLink = pln.addLink(
                    SimpleCorePLN.Link.LinkType.INHERITANCE,
                    new SimpleCorePLN.TruthValue(newStrength, newCount),
                    actionNode.id,
                    goodActionNode.id
            );
            System.out.println("  Reflective Learning: Assessed utility of " + actionNode.name + " -> " + actionUtilityLink.tv);
        }
    }


    /**
     * Selects an action based on the current strategy and goals.
     * This implementation uses a simple default strategy:
     * - Find actions predicted to lead (eventually) to the goal state.
     * - Prefer actions deemed "GoodAction" based on reflective learning.
     * - Fallback to random exploration if no good action is predicted.
     *
     * @param env The current environment.
     * @return The ID of the selected action Atom, or null if no action is chosen.
     */
    private String selectAction(Environment env) {
        if (currentGoalId == null) {
            System.out.println("Action Selection: No goal set.");
            return selectRandomAction(env); // Explore if no goal
        }

        SimpleCorePLN.Atom goalAtom = pln.getAtom(currentGoalId);
        if (goalAtom == null) {
            System.out.println("Action Selection: Goal Atom " + currentGoalId + " not found.");
            return selectRandomAction(env);
        }
        System.out.println("Action Selection: Goal is " + currentGoalId + ", Strategy is " + activeStrategyId);


        // --- Strategy Application (Simple Example) ---
        // A more complex agent would infer which strategy to use.
        // Here we just use the active one.
        SimpleCorePLN.Atom strategyAtom = pln.getAtom(activeStrategyId);
        String strategyName = (strategyAtom instanceof SimpleCorePLN.Node) ? ((SimpleCorePLN.Node) strategyAtom).name : "UnknownStrategy";


        List<String> availableActions = env.getAvailableActions();
        Map<String, Double> actionUtilities = new HashMap<>();
        SimpleCorePLN.Node goodActionConcept = pln.getOrCreateNode("GoodAction"); // Reflective concept

        // Evaluate available actions based on strategy
        if (strategyName.equals(STRATEGY_PREFIX + DEFAULT_STRATEGY_NAME)) {
            // Default Strategy: Find actions predicted to lead to goal, weighted by learned utility.
            System.out.println("  Using Strategy: " + DEFAULT_STRATEGY_NAME);

            // Use backward chaining (or simplified forward search) to find promising actions
            // For simplicity, let's simulate looking for links:
            // Action -> GoodAction (high confidence/strength)
            // AND potentially Sequence(CurrentState, Action) -> GoalState (future work)
            for (String actionName : availableActions) {
                SimpleCorePLN.Node actionNode = pln.getOrCreateNode(ACTION_PREFIX + actionName);
                String linkId = new SimpleCorePLN.Link(SimpleCorePLN.Link.LinkType.INHERITANCE, Arrays.asList(actionNode.id, goodActionConcept.id), SimpleCorePLN.TruthValue.DEFAULT).id;
                SimpleCorePLN.Atom utilityLink = pln.getAtom(linkId);

                double utility = 0.0;
                if (utilityLink != null) {
                    // Utility = strength * confidence (simple heuristic)
                    utility = utilityLink.tv.strength * utilityLink.tv.getConfidence();
                    System.out.println("    Action " + actionName + " has learned utility: " + utility + " " + utilityLink.tv);
                } else {
                    System.out.println("    Action " + actionName + " has no learned utility yet.");
                    utility = 0.1; // Small default utility for exploration
                }
                actionUtilities.put(actionNode.id, utility);
            }

        } else {
            System.out.println("  Unknown strategy: " + strategyName + ". Falling back to random.");
            return selectRandomAction(env);
        }


        // Choose the best action based on calculated utilities
        String bestActionId = actionUtilities.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // Add exploration noise (e.g., epsilon-greedy)
        if (bestActionId == null || Math.random() < 0.1) { // 10% exploration
            System.out.println("  Exploring: Choosing random action.");
            return selectRandomAction(env);
        }

        System.out.println("  Selected Action: " + bestActionId + " with utility " + actionUtilities.get(bestActionId));
        return bestActionId;

    }

    private String selectRandomAction(Environment env) {
        List<String> actions = env.getAvailableActions();
        if (actions.isEmpty()) {
            return null;
        }
        String actionName = actions.get(new Random().nextInt(actions.size()));
        return pln.getOrCreateNode(ACTION_PREFIX + actionName).id;
    }

    /**
     * Executes the main agent loop.
     *
     * @param env        The environment to interact with.
     * @param maxSteps   Maximum number of steps to run.
     * @param goalStateFeatures Features defining the goal state.
     */
    public void run(Environment env, int maxSteps, Map<String, Double> goalStateFeatures) {
        // Define the goal Atom based on features
        // In a real scenario, goals might be more complex or dynamic
        String goalStateName = STATE_PREFIX + "GOAL_" + goalStateFeatures.entrySet().stream()
                .filter(e -> e.getValue() > 0.5)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.joining("_"));
        SimpleCorePLN.Node goalNode = pln.getOrCreateNode(goalStateName);
        // Set the goal with high "desire" (represented as high count/strength if needed for reasoning)
        // For selection, we just need the ID.
        this.currentGoalId = goalNode.id;
        System.out.println("Agent Goal Set To: " + this.currentGoalId);

        Map<String, Double> initialState = env.getCurrentState();
        previousStateId = perceiveState(initialState); // Initial perception

        for (int step = 0; step < maxSteps && !env.isTerminated(); step++) {
            System.out.println("\n--- Agent Step " + (step + 1) + " ---");
            System.out.println("Current State Atom: " + previousStateId);

            // 2. Select Action
            lastActionId = selectAction(env);
            if (lastActionId == null) {
                System.out.println("No action available/selected. Stopping.");
                break;
            }

            // Extract action name from Atom ID for the environment
            String actionName = pln.getAtom(lastActionId) instanceof SimpleCorePLN.Node
                    ? ((SimpleCorePLN.Node) pln.getAtom(lastActionId)).name.substring(ACTION_PREFIX.length())
                    : "UnknownAction";
            System.out.println("Executing Action: " + actionName + " (Atom: " + lastActionId + ")");


            // 3. Execute Action
            env.performAction(actionName);

            // 4. Perceive Outcome
            Map<String, Double> currentState = env.getCurrentState();
            double reward = env.getReward();
            String currentStateId = perceiveState(currentState);

            // 5. Learn
            learnFromExperience(currentStateId, reward);

            // Update state for next loop
            previousStateId = currentStateId;

            // Reflective Metaprogramming Example: Switch strategy if stuck
            if (step > 10 && reward <= 0) { // Very simple heuristic
                // Consider switching strategy if no reward for a while
                // This requires reasoning about strategy effectiveness
                // E.g., Infer Inheritance(CurrentStrategy, BadStrategy)
                // For now, just print a message
                System.out.println("  (Reflective thought: No reward recently, maybe strategy " + activeStrategyId + " isn't working?)");
            }
        }

        System.out.println("\n--- Agent Run Finished ---");
        System.out.println("Final Knowledge Base Size: " + pln.knowledgeBase.size());
    }

    // --- Getters ---
    public SimpleCorePLN getPln() {
        return pln;
    }


    /**
     * Represents the agent's interaction point with its environment.
     * Needs to be implemented for a specific environment.
     */
    public interface Environment {
        /** Get the current perceivable state of the environment. */
        Map<String, Double> getCurrentState(); // Map<featureName, featureValue>

        /** Get the reward obtained since the last action. */
        double getReward();

        /** Perform an action in the environment. */
        void performAction(String actionName);

        /** List available actions in the current state. */
        List<String> getAvailableActions();

        /** Check if the simulation/environment should terminate. */
        boolean isTerminated();
    }

    // --- Simple Environment Example ---
    static class SimpleBinaryWorld implements Environment {
        final int maxSteps = 15;
        boolean stateA = true; // Start in state A
        int steps = 0;

        @Override
        public Map<String, Double> getCurrentState() {
            Map<String, Double> state = new HashMap<>();
            state.put("IsStateA", stateA ? 1.0 : 0.0);
            state.put("IsStateB", !stateA ? 1.0 : 0.0);
            return state;
        }

        @Override
        public double getReward() {
            // Reward for being in state B
            return !stateA ? 1.0 : 0.0;
        }

        @Override
        public void performAction(String actionName) {
            steps++;
            if ("ToggleState".equals(actionName)) {
                stateA = !stateA;
                System.out.println("  World: State toggled. Now in " + (stateA ? "A" : "B"));
            } else {
                System.out.println("  World: Unknown action '" + actionName + "' performed. No change.");
            }
        }

        @Override
        public List<String> getAvailableActions() {
            return Arrays.asList("ToggleState", "DoNothing"); // Added DoNothing for variety
        }

        @Override
        public boolean isTerminated() {
            return steps >= maxSteps;
        }
    }
}
```

----

# Temporal and Causal Inference

Consider the question of doing temporal and causal logic in PLN, and give an example involving the use of PLN to control a virtually embodied agent in a simulated environment:

14.1 Introduction

While not as subtle mathematically as HOI, temporal logic is an extremely im- portant topic conceptually, as the vast majority of human commonsense reasoning involves reasoning about events as they exist in, interrelate throughout, and change, over time. We argue that, via elaboration of a probabilistic event calculus and a few related special relationship types, temporal logic can be reduced to stan- dard PLN plus some special bookkeeping regarding time-distributions. Causal inference, in our view, builds on temporal logic but involves other no- tions as well, thus introducing further subtlety. Here we will merely scratch the surface of the topic, outlining how notions of causality fit into the overall PLN framework.

14.2 A Probabilistic Event Calculus

The Event Calculus (Kowalski & Sergo, 1986; Miller & Shanahan, 1991), a descendant of Situation Calculus (McCarthy & Hayes, 1969), is perhaps the best- fleshed-out attempt to apply predicate logic to the task of reasoning about com- monsense events. A recent book by Erik Mueller (2006) reviews the application of Event Calculus to the solution of a variety of “commonsense inference problems,” defined as simplified abstractions of real-world situations. This section briefly describes a variation of Event Calculus called Probabilistic Event Calculus, in which the strict implications from standard Event Calculus are replaced with probabilistic implications. Other changes are also introduced, such as a repositioning of events and actions in the basic event ontology, and the intro- duction of a simpler mechanism to avoid the use of circumscription for avoiding the “frame problem.” These changes make it much easier to use Event Calculus

type reasoning within PLN. The ideas in this section will be followed up in the following one, which introduces specific PLN relationship types oriented toward temporal reasoning. We suggest that the variant of event calculus presented here, as well as being easily compatible with PLN, also results in a more “cognitively natural” sort of Event Calculus than the usual variants, though of course this sort of claim is hard to substantiate rigorously and we will not pursue this line of argument extensively here, restricting ourselves to a few simple points.  Essentially, these points elabo- rate in the temporal-inference context the general points made in the Introduction regarding the overall importance of probability theory in a logical inference context:

• There is growing evidence for probabilistic calculations in the human brain, whereas neural bases for crisp predicate logic and higher-order logic mechanisms like circumscription have never been identified even preliminarily. • The use of probabilistic implications makes clearer how reasoning about events may interoperate with perceptions of events (given that perception is generally uncertain) and data mining of regularities from streams of perceptions of events (which also will generally produce regularities with uncertain truth-values). • As will be discussed in the final subsection, the pragmatic resolution of the “frame problem” seems more straightforward using a probabilistic variant of Event Calculus, in which different events can have different levels of persistence.

14.2.1 A Simple Event Ontology

Probabilistic Event Calculus, as we define it here, involves the following categories of entity:

    • events 
        o fluents 
    • temporal predicates 
        o holding 
        o initiation 
        o termination 
        o persistence 
    • actions 
    • time distributions 
        o time points 
        o time intervals 
        o general time distributions

“time distribution” refers to a probability density over the time axis; i.e., it assigns a probability value to each interval of time. Time points are considered pragmatically as time distributions that are bump-shaped and supported on a small interval around their mean (true instantaneity being an unrealistic notion both psy- chologically and physically). Time intervals are considered as time distributions corresponding to characteristic functions of intervals. The probabilistic predicates utilized begin with the functions:

    hold(event)
    initiate(event)
    terminate(event)

These functions are assumed to map from events into probabilistic predicates whose inputs are time distributions, and whose outputs are probabilistic truth- values. The class of “events” may be considered pragmatically as the domain of these functions. Based on these three basic functions, we may construct various other probabilistic predicates, such as:

    holdsAt(event, time point) \= (hold (event))(time point)
    initiatedAt(event,time point) \= (initiate(event))(time point)
    terminatedAt(event, time point) \= (terminate(event))(time point)
    holdsThroughout(event, time interval) \= (hold (event))(time interval)
    initiatedThroughout(event, time interval) \= (initiate (event))(time interval)
    terminatedThroughout(event, time interval) \= (terminate (event))(time interval)
    holdsSometimeIn(event, time interval)  \= “There exists a time point t in time interval T so that holdsAt(E,t)”
    initiatedSometimeIn(event, time interval) \= “There exists a time point t in time interval T so that initiatedAt(E,t)”
    terminatedSometimeIn(event, time interval) \= “There exists a time point t in time interval T so that terminatedAt(E,t)”

It may seem at first that the interval-based predicates could all be defined in terms of the time-point-based predicates using universal and existential quantifica- tion, but this isn’t quite the case. Initiation and termination may sometimes be considered as processes occupying non-instantaneous stretches of time, so that a process initiating over an interval does not imply that the process initiates at each point within that interval. Using the SatisfyingSet operator, we may also define some useful schemata corresponding to the above predicates. For example, we may define SS\_InitiatedAt via

    Equivalence Member $X (SatSet InitatedAt(event, \*)) ExOut SS\_InitiatedAt(event) $X

which means that, for instance, SS\_InitiatedAt(shaving\_event\_33) denotes the time at which the event shaving\_event\_33 was initiated. We will use a similar no- tation for schemata associated with other temporal predicates, below. Next, there are various important properties that may be associated with events, for example persistence and continuity.

    Persistent(event)
    Continuous(event)
    Increasing(event)
    Decreasing(event)

which are (like hold, initiate, and terminate) functions outputting probabilistic predicates mapping time distributions into probabilistic truth-values. Persistence indicates that the truth-value of an event can be expected to remain roughly constant over time from the point at which is initiated until the point at which the event is terminated. A “fluent” is then defined as an event that is persis- tent throughout its lifetime. Continuous, Increasing, and Decreasing apply to non- persistent events, and indicate that the truth-value of the event can be expected to {vary continuously, increase, or decrease} over time. For example, to say that the event of clutching (e.g., the agent clutching a ball) is persistent involves the predicate (isPersistent(clutching))(\[-infinity,infinity\]).

Note that this predicate may be persistent throughout all time even if it is not true throughout all time; the property of persistence just says that once the event is ini- tiated its truth-value remains roughly constant until it is terminated. Other temporal predicates may be defined in terms of these. For example, an “action” may be defined as an initiation and termination of some event that is as- sociated with some agent (which is different from the standard Event Calculus definition of action). Next, there is also use for further derived constructs such as

    initiates(action, event)

indicating that a certain action initiates a certain event or

    done(event)

which is true at time-point t if the event terminated before t (i.e., before the sup- port of the time distribution representing t). Finally, it is worth noting that in a logic system like PLN the above predicates may be nested within markers indicating them as hypothetical knowledge. This enables Probabilistic Event Calculus to be utilized much like Situation Calculus (McCarthy, 1986), in which hypothetical events play a critical role.

14.2.2 The “Frame Problem”

A major problem in practical applications of Event Calculus is the “frame problem” (McCarthy, 1986; Mueller, 2006), which – as usually construed in AI – refers to the problem of giving AI reasoning systems implicit knowledge about which aspects of a real-world situation should be assumed not to change during a certain period of time. More generally, in philosophy the “frame problem” may be construed as the problem of how a rational mind should bound the set of beliefs to change when a certain action is performed. This section contains some brief con- ceptual comments on the frame problem and its relationship to Probabilistic Event Calculus and PLN in general. For instance, if I tell you that I am in a room with a table in the center of it and four chairs around it, and then one of the chairs falls down, you will naturally as- sume that the three other chairs did not also fall down – and also that, for instance, the whole house didn’t fall down as well (perhaps because of an earthquake). There are really two points here:

1. The assumption that, unless there is some special reason to believe oth- erwise, objects will generally stay where they are; this is  an aspect of what is sometimes known as the “commonsense law of inertia” (Muel- ler, 2006).

Probabilistic Logic Networks 284 2\. The fact that, even though the above assumption is often violated in re- ality, it is beneficial to assume it holds for the sake of making inference tractable. The inferential conclusions obtained may then be used, or not, in any particular case depending on whether the underlying as- sumptions apply there.

We can recognize the above as a special case of what we earlier called “default inference,” adopting terminology from the nonmonotonic reasoning community. After discussing some of the particulars of the frame problem, we will return to the issue of its treatment in the default reasoning context. The original strategy John McCarthy proposed for solving the frame problem (at least partially) was to introduce the formal-logical notion of circumscription (McCarthy, 1986). For example, if we know

initiatesDuring(chair falls down, T1)

regarding some time interval T1, then the circumscription of holdsDuring and T1 in this formula is

initiatesDuring(x,T1) \<==\> x \= “chair falls down”

Basically this just is a fancy mathematical way of saying that no other events are initiated in this interval except the one event of the chair falling down. If multiple events are initiated during the interval, then one can circumscribe the combination of events, arriving at the assertion that no other events but the ones in the given set occur. This approach has known shortcomings, which have been worked around via various mechanisms including the simple addition of an axiom stating that events are by default persistent in the sense given above (see Reiter, 1991; Sandewall, 1998). Mueller (2006) uses circumscription together with work- arounds to avoid the problems classically found with it. However, none of these mechanisms is really satisfactory. In a real-world sce- nario there are always various things happening; one can’t simply assume that nothing else happens except a few key events one wants to reason about. Rather, a more pragmatic approach is to assume, for the purpose of doing an inference, that nothing important and unexpected happens that is directly relevant to the relation- ships one is reasoning about. Event persistence must be assumed probabilistically rather than crisply; and just as critically, it need be assumed only for appropriate properties of appropriate events that are known or suspected to be closely related to the events being reasoned about in a given act of reasoning. This latter issue (the constraints on the assumption of persistence) is not ade- quately handled in most treatments of formal commonsense reasoning, because these treatments handle “toy domains” in which reasoning engines are fed small numbers of axioms and asked to reason upon them. This is quite different from the situation of an embodied agent that receives a massive stream of data from its sen- sors at nearly all times, and must define its own reasoning problems and its own

285 Chapter 14: Temporal and Causal Inference relevant contexts. Thus, the real trickiness of the “frame problem” is not exactly what the logical-AI community has generally made it out to be; they have side- stepped the main problem due to their focus on toy problems. Once a relevant context is identified, it is relatively straightforward for an AI reasoning system to say “Let us, for the sake of drawing a relatively straightfor- ward inference, make the provisional assumption that all events of appropriate category in this context (e.g., perhaps: all events involving spatial location of in- animate household objects) are persistent unless specified otherwise.” Information about persistence doesn’t have to be explicitly articulated about each relevant ob- ject in the context, any more than an AI system needs to explicitly record the knowledge that each human has legs – it can derive that Ben has legs from the fact that most humans have legs; and it can derive that Ben’s refrigerator is stationary from the fact that most household objects are stationary. The hard part is actually identifying the relevant context, and understanding the relevant categories (e.g., refrigerators don’t move around much, but people do). This must be done induc- tively; e.g., by knowledge of what contexts have been useful for similar inferences in the past. This is the crux of the frame problem:

• Understanding what sorts of properties of what sorts of objects tend to be persistent in what contexts (i.e., learning specific empirical prob- abilistic patterns regarding the Persistent predicate mentioned above) • Understanding what is a natural context to use for modeling persis- tence, in the context of a particular inference (e.g., if reasoning about what happens indoors, one can ignore the out of doors even it’s just a few feet away through the wall, because interactions between the in- doors and out of doors occur only infrequently)

And this, it seems, is just plain old “AI inference” – not necessarily easy, but without any obvious specialness related to the temporal nature of the content ma- terial. As noted earlier, the main challenge with this sort of inference is making it efficient, which may be done, for instance, by use of a domain-appropriate ontol- ogy to guide the default inference (allowing rapid estimation of when one is in a case where the default assumption of location-persistence will not apply).

14.3 Temporal Logic Relationships

In principle one can do temporal inference in PLN without adding any new constructs, aside from the predicates introduced above and labeled Probabilistic Event Calculus. One can simply use standard higher-order PLN links to interrelate event calculus predicates, and carry out temporal inference in this way. However, it seems this is not the most effective approach. To do temporal inference in PLN efficiently and elegantly, it’s best to introduce some new relationship types: pre- dictive implication (PredictiveImplication and EventualPredictiveImplication) and

Probabilistic Logic Networks 286 sequential and simultaneous conjunction and disjunction. Here we introduce these concepts, and then describe their usage for inferring appropriate behaviors to con- trol an agent in a simulation world.

14.3.1 Sequential AND

Conceptually, the basic idea of sequential AND is that

SeqAND (A1,  ..., An )

should be “just like AND but with the additional rule that the order of the items in the sequence corresponds to the temporal order in which they occur.” Similarly, SimOR and SimAND (“Sim” for Simultaneous)  may be used to define parallelism within an SeqAND list; e.g., the following pseudocode for holding up a conven- ience store:

SeqAND enter store SimOR kill clerk knock clerk unconscious steal money leave store

However, there is some subtlety lurking beneath the surface here. The simplis- tic interpretations of SeqAND as “AND plus sequence” is not always adequate, as the event calculus notions introduced above reveal. Attention must be paid to the initiations and terminations of events. The basic idea of sequential AND must be decomposed into multiple notions, based on disambiguating properties we call dis- jointness and eventuality. Furthermore, there is some additional subtlety because the same sequential logical link types need to be applied to both terms and predi- cates. Applied to terms, the definition of a basic, non-disjoint, binary SeqAND is

SeqAND A B \<s, T\>

iff

AND AND A B Initiation(B) – Initiation(A) lies in interval T

287 Chapter 14: Temporal and Causal Inference Basically, what this says is “B starts x seconds after A starts, where x lies in the in- terval T.” Note that in the above we use \<s, T\> to denote a truth-value with strength s and time-interval parameter T. For instance,

SeqAND \<.8,(0s,120s)\> shaving\_event\_33 showering\_event\_43

means

AND AND shaving\_event\_33 showering\_event\_43 SS\_Initiation(showering\_event\_43)– SS\_Initiation(shaving\_event\_33) in \[0s, 120s\]

On the other hand, the definition of a basic disjoint binary SeqAND between terms is

DisjointSeqAND A B \<s, T\>

iff

AND AND A B Initiation(B) – Termination(A) lies in interval T

Basically, what this says is “B starts x seconds after A finishes, where x lies in the interval T” – a notion quite different from plain old SeqAND. EventualSeqAND and DisjointEventualSeqAND are defined similarly, but without specifying any particular time interval. For example,

EventualSeqAND A B

iff

AND AND A B Evaluation after List SS\_Initiation(B)

Probabilistic Logic Networks 288 SS\_Initiation(A)

Next, there are several natural ways to define (ordinary or disjoint) SeqAND as applied to predicates. The method we have chosen makes use of a simple variant of “situation semantics” (Barwise 1983). Consider P and Q as predicates that ap- ply to some situation; e.g.,

P(S) \= shave(S) \= true if shaving occurs in situation S

Q(S) \= shower(S) \= true if showering occurs in situation S

Let

timeof(P,S) \= the set of times at which the predicate P is true in situation S

Then

SeqAND P Q

is also a predicate that applies to a situation; i.e.,

(SeqAND P Q)(S) \<s,T\>

is defined to be true of situation S iff

AND ~~AND P(S) Q(S) timeof(Q,S) – timeof(P,S) intersects interval T~~

In the case of an n-ary sequential AND, the time interval T must be replaced by a series of time intervals; e.g.,

SeqAND \<s,(T1,…,Tn)\> A1 ... An-1 An

is a shorthand for

289 Chapter 14: Temporal and Causal Inference AND ~~SeqAND A1 A2 … SeqAND An-1 An~~

Simultaneous conjunction and disjunction are somewhat simpler to handle. We can simply say, for instance,

SimAND A B \<s, T\>

iff

AND HoldsThroughout(B,T) HoldsThroughout(A,T)

and make a similar definition for SimOr. Extension from terms to predicates using situation semantics works analogously for simultaneous links as for sequential ones. Related link types ExistentialSimAND and ExistentialSimOR, defined e.g. via

SimOR A B \<s, T\>

iff

OR HoldsSometimeIn(B,T) HoldsSometimeIn(A,T)

may also be useful.

14.3.2 Predictive Implication

Next, having introduced the temporal versions of conjunction (and disjunction), we introduce the temporal version of implication: the relationship ExtensionalPredictiveImplication P Q \<s,T\> which is defined as

ExtensionalImplication ~~P(S) \[SeqAND( P , Q) \](S)~~

Probabilistic Logic Networks 290 There is a related notion of DisjointPredictiveImplication defined in terms of Dis- jointSeqAND. PredictiveImplication may also be meaningfully defined intensionally; i.e.,

IntensionalPredictiveImplication P Q \<s,T\> may be defined as

IntensionalImplication ~~P(S) \[SeqAND( P , Q) \](S) and of course there is also mixed PredictiveImplication, which is, in fact, the most commonly useful kind.~~

14.3.3 Eventual Predictive Implication

Predictive implication is an important concept but applying it to certain kinds of practical situations can be awkward. It turns out to be useful to also introduce a specific relationship type with the semantics “If X continues for long enough, then Y will occur.” In PLN this is called EventualPredictiveImplication, so that, e.g., we may say

EventualPredictiveImplication starve die

EventualPredictiveImplication run sweat

Formally, for events X and Y

EventualPredictiveImplication P Q

may be considered as a shorthand for

Implication ~~P(S) \[EventualSeqAND( P , Q) \](S)~~

There are also purely extensional and intensional versions, and there is a notion of DisjointEventualPredictiveImplication as well.

291 Chapter 14: Temporal and Causal Inference 14.3.4 Predictive Chains

Finally, we have found use for the notion of a predictive chain, where

PredictiveChain \<s,(T1,…,Tn)\> A1 ... An-1 An

means

PredictiveImplication \<s,Tn\> SeqAND \<(T1,…,Tn-1)\> A1 ... An-1 An

For instance,

PredictiveChain Teacher is thirsty I go to the water machine I get a cup I fill the cup I bring the cup to teacher Teacher is happy

Disjoint and eventual predictive chains may be introduced in an obvious way.

14.3.5 Inference on Temporal Relationships

Inference on temporal-logical relationships must use both the traditional truth- values and the probability of temporal precedence; e.g., in figuring out whether

PredictiveImplication A B PredictiveImplication B C |- PredictiveImplication A C

one must calculate the truth-value of

Probabilistic Logic Networks 292

Implication A C

but also the odds that in fact A occurs before C. The key point here, conceptually, is that the probabilistic framework may be applied to time intervals, allowing PLN to serve as a probabilistic temporal logic not just a probabilistic static logic. In the context of indefinite probabilities, the use of time distributions may be viewed as adding an additional level of Monte Carlo calculation.  In handling each premise in an inference, one may integrate over all time-points, weighting each one by its probability according to the premise’s time distribution. This means that for each collection of (premise, time point) pairs, one does a whole inference; and then one revises the results, using the weightings of the premises’ time distri- butions.

14.4 Application to Making a Virtual Agent Learn to Play Fetch

As an example of PLN temporal inference, we describe here experiments that were carried out using temporal PLN to control the learning engine of a humanoid virtual agent carrying out simple behaviors in a 3D simulation world. Specifically, we run through in detail how PLN temporal inference was used to enable the agent to learn to play the game of fetch in the AGISim game world. The application described here was not a pure PLN application, but PLN’s temporal inference capability lay at its core. More broadly, the application in- volved the Novamente Cognition Engine architecture and the application of PLN, in combination with other simpler modules, within this architecture. The NCE is extremely flexible, incorporating a variety of carefully inter-coordinated learning processes, but the experiments described in this section relied primarily on the in- tegration of PLN inference with statistical pattern mining based perception and functional program execution based agent control. From the PLN and Novamente point of view, the experiments reported here are interesting mostly as a “smoke test” for embodied, inference-based reinforcement learning, to indicate that the basic mechanisms required for doing this sort of in- ferential learning are integrated adequately and working correctly. One final preliminary note: Learning to play fetch in the manner described here requires the assumption that the system already has learned (or been provided in- trinsically with the capability for) how to recognize objects: balls, teachers, and the like. Object recognition in AGISim is something the NCE is able to learn, given the relatively low-bandwidth nature of the perceptions coming into it from AGISim, but that was not the focus of the reported work. Instead, we will take this capability for granted here and focus on perception/action/cognition integration specific to the “fetch” task.

293 Chapter 14: Temporal and Causal Inference 14.4.1 The Game of Fetch

“Fetch” is a game typically played between a human and a dog. The basic idea is a simple one: the human throws an object and says “fetch,” the dog runs to the object, picks it up, and brings it back to the human, who then rewards the dog for correct behavior. In our learning experiments, the teacher (a humanoid agent in AGISim) plays the role of the human and the Novamente-controlled agent plays the role of the dog. In more complex AGISim experiments, the teacher is actually controlled by a human being, who delivers rewards to Novamente by using the Reward controls on the AGISim user interface. Due to the simplicity of the current task, in the “fetch” experiments reported here the human controller was replaced by auto- mated control code. The critical aspect of this automated teacher is partial reward. That is, you can’t teach a dog (or a baby Novamente) to play fetch simply by re- warding it when it successfully retrieves the object and brings it to you, and re- warding it not at all otherwise – because the odds of it ever carrying out this “cor- rect” behavior by random experimentation in the first place would be very low.. What is needed for instruction to be successful is for there to be a structure of par- tial rewards in place. We used here a modest approach with only one partial re- ward preceding the final reward for the target behavior. The partial reward is ini- tially given for the agent when it manages to lift the ball from the ground, and once it has learnt to repeat this behavior we switch to the final stage where we only reward the agent when it proceeds to take the ball to the teacher and drop it there.

14.4.2 Perceptual Pattern Mining

Pattern mining, within the NCE, is a process that identifies frequent or other- wise significant patterns in a large body of data. The process is independent of whether the data is perceptual or related to actions, cognition, etc. However, it is often associated with perceptual data and abstractions from perceptual data. In principle, everything obtained via pattern mining could also be obtained via in- ference, but pattern mining has superior performance in many applications. The pattern mining relevant to the fetch learning consists of simply recogniz- ing frequent sequences of events such as

SequentialAND SimultaneousAND I am holding the ball I am near the teacher I get more reward.

Probabilistic Logic Networks 294 which in full-fledged PLN notation is

SequentialAND SimultaneousAND EvaluationLink holding ball EvaluationLink near ListLink (me, teacher) Reward

14.4.3 Particularities of PLN as Applied to Learning to Play Fetch

From a PLN perspective, learning to play fetch is a simple instance of back- ward chaining inference – a standard inference control mechanism whose cus- tomization to the PLN context was briefly described in the previous chapter. The goal of the NCE in this context is to maximize reward, and the goal of the PLN backward chainer is to find some way to prove that if some actionable predicates become true, then the truth-value of

EvaluationLink(Reward)

is maximized. This inference is facilitated by assuming that any action can be tried out; i.e., the trying of actions is considered to be in the axiom set of the in- ference. (An alternative, also workable approach is to set a PredictiveImplica- tionLink($1, Reward) as the target of the inference, and launch the inference to fill in the variable slot $1 with a sequence of actions.) PredictiveImplicationLink is a Novamente Link type that combines logical (probabilistic) implication with temporal precedence. Basically, the backward chainer is being asked to construct an Atom that implies reward in the future. Each PredictiveImplicationLink contains a time-distribution indicating how long the target is supposed to occur after the source does; in this case the time- distribution must be centered on the rough length of time that a single episode of the “fetch” game occupies. To learn how to play fetch, The NCE must repeatedly invoke PLN backward chaining on a knowledge base consisting of Atoms that are constantly being acted upon by perceptual pattern mining as discussed above. PLN learns logical knowl- edge about circumstances that imply reward, and then a straightforward process called predicate schematization produces NCE objects called “executable sche- mata,” which are then executed. This causes the system to carry out actions, which in turn lead to new perceptions, which give PLN more information to

295 Chapter 14: Temporal and Causal Inference guide its reasoning and lead to the construction of new procedures, etc. In order to carry out very simple inferences about schema execution as re- quired in the fetch example, PLN uses two primitive predicates:

• try(X), indicating that the schema X is executed • can(X), indicating that the necessary preconditions of schema X are fulfilled, so that the successful execution of X will be possible

Furthermore, the following piece of knowledge is assumed to be known by the system, and is provided to the NCE as an axiom:

PredictiveImplication SimultaneousAnd Evaluation try X Evaluation can X Evaluation done X

This simply means that if the system can do X, and tries to do X, then at some later point in time, it has done X. Note that this implication may also be used probabilistically in cases where it is not certain whether or not the system can do X.  Note that in essentially every case, the truth value of Evaluation can X will be uncertain (even if an action is extremely simple, there’s always some pos- sibility of an error message from the actuator), so that the output of this Predic- tiveImplication will essentially never be crisp. The proper use of the “can” predicate necessitates that we mine the history of occasions in which a certain action succeeded and occasions in which it did not. This allows us to create PredictiveImplications that embody the knowledge of the preconditions for successfully carrying out the action. In the inference experi- ments reported here, we use a simpler approach because the basic mining prob- lem is so easy; we just assume that “can” holds for all actions, and push the sta- tistics of success/failure into the respective truth-values of the PredictiveImplications produced by pattern mining. Next, we must explain a few shorthands and peculiarities that we introduced when adapting the PLN rules to carry out the temporal inference required in the fetch example. The ModusPonensRule used here is simply a probabilistic version of the standard Boolean modus ponens, as described earlier. It can also be applied to PredictiveImplications, insofar as the system keeps track of the structure of the proof tree so as to maintain the (temporally) proper order of arguments. By fol- lowing the convention that the order in which the arguments to modus ponens must be applied is always the same as the related temporal order, we may extract a plan of consecutive actions, in an unambiguous order, directly from the proof tree. Relatedly, the AndRules used are of the form

Probabilistic Logic Networks 296 A B |- A & B

and can be supplied with a temporal inference formula so as to make them appli- cable for creating SequentialANDs. We will also make use of what we call the SimpleANDRule, which embodies a simplistic independence assumption and finds the truth-value of a whole conjunction based only on the truth-values of its individual constituents, without trying to take advantage of the truth-values possi- bly known to hold for partial conjunctions. We use a “macro rule” called RewritingRule, which is defined as a composition of AndRule and ModusPonensRule. It is used as a shorthand for converting atoms from one form to another when we have a Boolean true implication at our dis- posal. What we call CrispUnificationRule is a “bookkeeping rule” that serves simply to produce, from a variable-laden expression (Atom defined by a ForAll, ThereEx- ists or VariableScope relationship), a version in which one or more variables have been bound. The truth-value of the resulting atom is the same as that of the quanti- fied expression itself. Finally, we define the specific predicates used as primitives for this learning experiment, which enables us to abstract away from any actual motor learning:

• Reward – a built-in sensation corresponding to the Novamente agent get- ting Reward, either via the AGISim teaching interface or otherwise hav- ing its internal Reward indicator stimulated • goto – a persistent event; goto(x) means the agent is going to x • lift – an action; lift(x) means the agent lifts x • drop – an action; drop(x) means the agent drops x if it is currently holding x (and when this happens close to an agent T, we can interpret that informally as “giving” x to T) • TeacherSay – a percept; TeacherSay(x) means that the teacher utters the string x • holding – a persistent event; holding(x) means the agent is holding x

14.4.4 Learning to Play Fetch Via PLN Backward Chaining

Next, we show a PLN inference trajectory that results in learning to play fetch once we have proceeded into the final reward stage. This trajectory is one of many produced by PLN in various indeterministic learning runs. When acted upon by the NCE’s predicate schematization process, the conclusion of this trajectory (de- picted graphically in Figure 1\) produces the simple schema (executable procedure)

297 Chapter 14: Temporal and Causal Inference

try goto Ball try lift Ball try goto Teacher try drop Ball.

Figure 1\. Graphical depiction of the final logical plan learned for carrying out the fetch task

It is quite striking to see how much work PLN and the perception system need to go through to get to this relatively simple plan, resulting in an even simpler logical procedure\! Nevertheless, the computational work required to do this sort of inference is quite minimal, and the key point is that the inference is done by a general-purpose inference engine that was not at all tailored for this particular task. The inference target was

EvaluationLink \<0.80, 0.0099\> Reward: PredicateNode \<1, 0\>.

The final truth-value found for the EvaluationLink is of the form \<strength, weight of evidence\>, meaning that the inference process initially found a way to achieve the Reward with a strength of 0.80, but with a weight of evidence of only .0099 (the rather unforgiving scaling factor of which originates from the internals of the perception pattern miner). Continuing the run makes the strength and weight of evidence increase toward 1.0. The numbers such as \[9053948\] following nodes and links indicate the “han- dle” of the entity in the NCE’s knowledge store, and serve as unique identifiers. The target was produced by applying ModusPonensRule to the combination of

Probabilistic Logic Networks 298 PredictiveImplicationLink \<0.8,0.01\> \[9053948\] SequentialAndLink \<1,0\> \[9053937\] EvaluationLink \<1,0\> \[905394208\] "holdingObject":PredicateNode \<0,0\> \[6560272\] ListLink \[7890576\] "Ball":ConceptNode \<0,0\> \[6582640\] EvaluationLink \<1,0\> \[905389520\] "done":PredicateNode \<0,0\> \[6606960\] ListLink \[6873008\] ExecutionLink \[6888032\] "goto":GroundedSchemaNode \<0,0\> \[6553792\] ListLink \[6932096\] "Teacher":ConceptNode \<0,0\> \[6554000\] EvaluationLink \<1,0\> \[905393440\] "try":PredicateNode \<0,0\> \[6552272\] ListLink \[7504864\] ExecutionLink \[7505792\] "drop":GroundedSchemaNode \<0,0\> \[6564640\] ListLink \[7506928\] "Ball":ConceptNode \<0,0\> \[6559856\] EvaluationLink \<1,0\> \[905391056\] "Reward":PredicateNode \<1,0\> \[191\]

(the plan fed to predicate schematization, and shown in Figure 1\) and

SequentialAndLink \<1,0.01\> \[840895904\] EvaluationLink \<1,0.01\> \[104300720\] "holdingObject":PredicateNode \<0,0\> \[6560272\] ListLink \[7890576\] "Ball":ConceptNode \<0,0\> \[6582640\] EvaluationLink \<1,1\> \[72895584\] "done":PredicateNode \<0,0\> \[6606960\] ListLink \[6873008\] ExecutionLink \[6888032\] "goto":GroundedSchemaNode \<0,0\> \[6553792\] ListLink \[6932096\] "Teacher":ConceptNode \<0,0\> \[6554000\] EvaluationLink \<1,1\> \[104537344\] "try":PredicateNode \<0,0\> \[6552272\] ListLink \[7504864\] ExecutionLink \[7505792\] "drop":GroundedSchemaNode \<0,0\> \[6564640\] ListLink \[7506928\] "Ball":ConceptNode \<0,0\> \[6559856\]

299 Chapter 14: Temporal and Causal Inference Next, the SequentialANDLink \[840895904\] was produced by applying Simple- ANDRule to its three child EvaluationLinks. The EvaluationLink \[104300720\] was produced by applying ModusPonensRule to

PredictiveImplicationLink \<1,0.01\> \[39405248\] SequentialANDLink \<1,0\> \[39403472\] EvaluationLink \<1,0\> \[39371040\] "done":PredicateNode \<0,0\> \[6606960\] ListLink \[7511296\] ExecutionLink \[7490272\] "goto":GroundedSchemaNode\<0,0\> \[6553792\] ListLink \[7554976\] "Ball":ConceptNode \<0,0\> \[6558784\] EvaluationLink \<1,0\> \[39402640\] “done":PredicateNode \<0,0\> \[6606960\] ListLink \[7851408\] ExecutionLink \[7865376\] "lift":GroundedSchemaNode \<0,0\> \[6553472\] ListLink \[7890576\] "Ball":ConceptNode \<0,0\> \[6582640\] EvaluationLink \<1,0\> \[39404448\] "holdingObject":PredicateNode \<0,0\> \[6560272\] ListLink \[7890576\] "Ball":ConceptNode \<0,0\> \[6582640\]

which was mined from perception data (which includes proprioceptive observation data indicating that the agent has completed an elementary action), and to

SequentialANDLink \<1,1\> \[104307776\] EvaluationLink \<1,1\> \[72926800\] "done":PredicateNode \<0,0\> \[6606960\] ListLink \[7511296\] ExecutionLink \[7490272\] "goto":GroundedSchemaNode \<0,0\> \[6553792\] ListLink \[7554976\] "Ball":ConceptNode \<0,0\> \[6558784\] EvaluationLink \<1,1\> \[72913264\] "done":PredicateNode \<0,0\> \[6606960\] ListLink \[7851408\] ExecutionLink \[7865376\] "lift":GroundedSchemaNode \<0,0\> \[6553472\] ListLink \[7890576\] "Ball":ConceptNode \<0,0\> \[6582640\].

Probabilistic Logic Networks 300

The SequentialANDLink \[104307776\] was produced by applying SimpleAN- DRule to its two child EvaluationLinks. The EvaluationLink \[72926800\] was pro- duced by applying RewritingRule to

EvaluationLink \<1,1\> \[72916304\] "try":PredicateNode \<0,0\> \[6552272\] ListLink \[7511296\] ExecutionLink \[7490272\] "goto":GroundedSchemaNode \<0,0\> \[6553792\] ListLink \[7554976\] "Ball":ConceptNode \<0,0\> \[6558784\] and

EvaluationLink \<1,1\> \[72923504\] "can":PredicateNode \<1,0\> \[6566128\] ListLink \[7511296\] ExecutionLink \[7490272\] "goto":GroundedSchemaNode \<0,0\> \[6553792\] ListLink \[7554976\] "Ball":ConceptNode \<0,0\> \[6558784\].

The EvaluationLink \[72916304\], as well as all other try statements, were con- sidered axiomatic and technically produced by applying the CrispUnificationRule to

ForallLink \<1,1\> \[6579808\] ListLink \<1,0\> \[6564144\] "$A":VariableNode \<1,0\> \[6563968\] EvaluationLink \<1,0\> \[6579200\] "try":PredicateNode \<0,0\> \[6552272\] ListLink \<1,0\> \[6564144\] "$A":VariableNode \<1,0\> \[6563968\]

The EvaluationLink \[72923504\], as well as all other can statements, were con- sidered axiomatic and technically produced by applying CrispUnificationRule to:

ForallLink \<1,1\> \[6559424\] ListLink \<1,0\> \[6564496\] "$B":VariableNode \<1,0\> \[6564384\] EvaluationLink \<1,0\> \[6550720\] "can":PredicateNode \<1,0\> \[6566128\] ListLink \<1,0\> \[6564496\] "$B":VariableNode \<1,0\> \[6564384\]

301 Chapter 14: Temporal and Causal Inference The EvaluationLink \[72913264\] was produced by applying RewritingRule to

EvaluationLink \<1,1\> \[72903504\] "try":PredicateNode \<0,0\> \[6552272\] ListLink \[7851408\] ExecutionLink \[7865376\] "lift":GroundedSchemaNode \<0,0\> \[6553472\] ListLink \[7890576\] "Ball":ConceptNode \<0,0\> \[6582640\]

and

EvaluationLink \<1,1\> \[72909968\] "can":PredicateNode \<1,0\> \[6566128\] ListLink \[7851408\] ExecutionLink \[7865376\] "lift":GroundedSchemaNode \<0,0\> \[6553472\] ListLink \[7890576\] "Ball":ConceptNode \<0,0\> \[6582640\]

And finally, returning to the first PredictiveImplicationLink’s children, Evalua- tionLink \[72895584\] was produced by applying RewritingRule to the axioms

EvaluationLink \<1,1\> \[72882160\] "try":PredicateNode \<0,0\> \[6552272\] ListLink \[6873008\] ExecutionLink \[6888032\] "goto":GroundedSchemaNode \<0,0\> \[6553792\] ListLink \[6932096\] "Teacher":ConceptNode \<0,0\> \[6554000\]

and

EvaluationLink \<1,1\> \[72888224\] "can":PredicateNode \<1,0\> \[6566128\] ListLink \[6873008\] ExecutionLink \[6888032\] "goto":GroundedSchemaNode \<0,0\> \[6553792\] ListLink \[6932096\] "Teacher":ConceptNode \<0,0\> \[6554000\]

In conclusion, we have given a relatively detailed treatment of a simple learn- ing experiment – learning to play fetch – conducted with the NCE integrative AI system in the AGISim simulation world. Our approach was to first build an inte- grative AI architecture we believe to be capable of highly general learning, and

Probabilistic Logic Networks 302 only then apply it to the fetch test, while making minimal parameter adjustment to the specifics of the learning problem. This means that in learning to play fetch the system has to deal with perception, action, and cognition modules that are not fetch-specific, but are rather intended to be powerful enough to deal with a wide variety of learning tasks corresponding to the full range of levels of cognitive de- velopment. Ultimately, in a problem this simple the general-intelligence infrastructure of the NCE and the broad sophistication of PLN don’t add all that much. There exist much simpler systems with equal fetch-playing prowess. For instance, the PLN system’s capability of powerful analogical reasoning is not being used at all here, and its use in an embodiment context is a topic for another paper. However, this sort of simple integrated learning lays the foundation for more complex embodied learning based on integrated cognition, the focus of much of our ongoing work.

14.5 Causal Inference

Temporal inference, as we have seen, is relatively conceptually simple from a probabilistic perspective. It leads to a number of new link types and a fair amount of bookkeeping complication (the node-and-link constructs shown in the context of the fetch example won’t win any prizes for elegance), but is not fundamentally conceptually problematic. The tricky issues that arise, such as the frame problem, are really more basic AI issues than temporal inference issues in particular. Next, what about causality? This turns out to be a much subtler matter. There is much evidence that human causal inference is pragmatic and heterogeneous rather than purely mathematical (see discussion and references in Goertzel 2006). One illustration of this is the huge variance in the concept of causality that exists among various humans and human groups (Smith 2003). Given this, it’s not to be expected that PLN or any other logical framework could, in itself, give a thorough foundation for understanding causality. But even so, there are interesting connec- tions to be drawn between PLN and aspects of causal inference. Predictive implication, as discussed above, allows us to discuss temporal corre- lation in a pragmatic way. But this brings us to what is perhaps the single most key conceptual point regarding causation: correlation and causation are distinct. To take the classic example, if a rooster regularly crows before dawn, we do not want to infer that he causes the sun to rise. In general, if X appears to cause Y, it may actually be due to Z causing both X and Y, with Y appearing later than X. We can only be sure that this is not the case if we have a way to identify alternative causes and test them in comparison to the causes we think are real. Or, as in the rooster/dawn case, we may have background knowledge that makes the “X causes Y” scenario intrinsically implausible in terms of the existence of potential causal mechanisms.

303 Chapter 14: Temporal and Causal Inference Let’s consider this example in a little more detail. In the case of roosters and dawn, clearly we have both implication and temporal precedence. Hence there will be a PredictiveImplication from “rooster crows” to “sun rises.” But will the rea- soning system conclude from this PredictiveImplication that if a rooster happens to crow at 1 AM the sun is going to rise really early that morning – say, at 2 AM? How is this elementary error avoided? There are a couple of answers here. The first has to do with the inten- sion/extension distinction. It says:  The strength of this particular PredictiveImpli- cation may be set high by direct observation, but it will be drastically lowered by inference from more general background knowledge. Specifically, much of this in- ference will be intensional in nature, as opposed to the purely extensional infor- mation (direct evidence-counting) that is used to conclude that roosters crowing imply sun rising. We thus conclude that one signifier of bogus causal relationships is when

ExtensionalPredictiveImplication A B has a high strength but

IntensionalPredictiveImplication A B has a low strength. In the case of

A \= rooster crows B \= sun rises the weight-of-evidence of the intensional relationship is much higher than that of the extensional relationship, so that the overall PredictiveImplication relationship comes out with a fairly low strength. To put it more concretely, if the reasoning system had never seen roosters crow except an hour before sunrise, and had never seen the sun rise except after rooster crowing, the posited causal relation might indeed be created. What would keep it from surviving for long would be some knowledge about the mechanisms underly- ing sunrise. If the system knows that the sun is very large and rooster crows are physically insignificant forces, then this tells it that there are many possible con- texts in which rooster crows would not precede the sun rising. Conjectural reason- ing about these possible contexts leads to negative evidence in favor of the impli- cation PredictiveImplication rooster\_crows sun\_rises which counterbalances – probably overwhelmingly – the positive evidence in fa- vor of this relationship derived from empirical observation. More concretely, one has the following pieces of evidence:

Probabilistic Logic Networks 304 PredictiveImplication \<.00, .99\> small\_physical\_force movement\_of\_large\_object PredictiveImplication \<.99,.99\> rooster\_crows small\_physical\_force PredictiveImplication \<.99, .99\> sun\_rises movement\_of\_large\_object PredictiveImplication \<.00,.99\> rooster\_crows sun\_rises which must be merged with PredictiveImplication rooster\_crows sun\_rises  \<1,c\> derived from direct observation. So it all comes down to: How much more confi- dent is the system that a small force can’t move a large object, than that rooster crows always precede the sunrise? How big is the parameter we’ve denoted c compared to the confidence we’ve arbitrarily set at .99? Of course, for this illustrative example we’ve chosen only one of many general world-facts that contradicts the hypothesis that rooster crows cause the sunrise… in reality many, many such facts combine to effect this contradiction. This simple example just illustrates the general point that reasoning can invoke background knowledge to contradict the simplistic “correlation implies causation” conclusions that sometimes arise from direct empirical observation.

14.5.1 Aspects of Causality Missed by a Purely Logical Analysis

In this section we will briefly discuss a couple of aspects of causal inference that seem to go beyond pure probabilistic logic – and yet are fairly easily inte- grable into a PLN-based framework. This sort of discussion highlights what we feel will ultimately be the greatest value of the PLN formalism; it formulates logi- cal inference in a way that fits in naturally with a coherent overall picture of cog- nitive function. Here we will content ourselves with a very brief sketch of these ideas, as to pursue it further would lead us too far afield.

14.5.1.1 Simplicity of Causal Mechanisms

The first idea we propose has to do with the notion of causal mechanism. The basic idea is, given a potential cause-effect pair, to seek a concrete function map- ping the cause to the effect, and to consider the causality as more substantial if this function is simpler. In PLN terms, this means that one is not only looking at the

305 Chapter 14: Temporal and Causal Inference IntensionalPredictiveImplication relationship underlying a posited causal relation- ship, but one is weighting the count of this relationship more highly if the Predi- cates involved in deriving the relationship are simpler. This heuristic for count- biasing means that one is valuing simple causal mechanisms as opposed to com- plex ones. The subtlety lies in the definition of the “simplicity” of a predicate, which relies on pattern theory (Goertzel 2006\) as introduced above in the context of intensional inference.

14.5.1.2 Distal Causes, Enabling Conditions

As another indication of the aspects of the human judgment of causality that are omitted by a purely logical analysis, consider the distinction between local and distal causes. For example, does an announcement by Greenspan cause the market to change, or is he just responding to changed economic conditions on interest rates, and they are the ultimate cause? Or, to take another example, suppose a man named Bill drops a stone, breaking a car windshield. Do we want to blame (assign causal status to) Bill for dropping the stone that broke the car windshield, or his act of releasing the stone, or perhaps the anger behind his action, or his childhood mistreatment by the owner of the car, or even the law of gravity pulling the rock down? Most commonly we would cite Bill as the cause because he was a free agent. But different causal ascriptions will be optimal in different contexts: typi- cally, childhood mistreatment would be a mitigating factor in legal proceedings in such a case. Related to this is the distinction between causes and so-called enabling condi- tions. Enabling conditions predictively imply their “effect,” but they display no significant variation within the context considered pertinent. For, example oxygen is necessary to use a match to start a fire, but because it is normally always present we usually ignore it as a cause, and it would be called an enabling condition. If it really is always present, we can ignore it in practice; the problem occurs when it is very often present but sometimes is not, as for example when new unforeseen conditions occur. We believe it is fairly straightforward to explain phenomena like distal causes and enabling conditions, but only at the cost of introducing some notions that exist in Novamente but not in PLN proper. In Novamente, Atoms are associated with quantitative “importance” values as well as truth-values. The importance value of an Atom has to do with how likely it is estimated to be that this Atom will be use- ful to the system in the future. There are short- and long-term importance values associated with different future time horizons. Importance may be assessed via PLN inference, but this is PLN inference based regarding propositions about how useful a given Atom has been over a given time interval. It seems that the difference between a cause and an enabling condition often has to do with nonlogical factors. For instance, in Novamente PLN Atoms are as- sociated not only with truth-values but also with other numbers called attention

Probabilistic Logic Networks 306 values, including for instance “importance” values indicating the expected utility of the system to thinking about the Atom. For instance, the relationship

PredictiveImplication oxygen fire

may have a high strength and count, but it is going to have a very low importance unless the AI system in question is dealing with some cases where there is insuffi- cient oxygen available to light fires. A similar explanation may help with the dis- tinction between distal and local causes. Local causes are the ones associated with more important predictive implications – where importance needs to be assigned, by a reasoning system, based on inferences regarding which relationships are more likely to be useful in future inferences.

307
