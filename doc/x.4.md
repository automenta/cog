import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
* Enhanced Probabilistic Logic Networks (EPLN) Engine.
*
* This class provides a significantly revised and enhanced implementation of PLN,
* integrating core reasoning, agent control, temporal logic, attention mechanisms,
* and extensibility features into a single, consolidated framework. It aims for
* computational power, efficiency, elegance, and developer-friendliness.
*
* Key Enhancements & Design Principles:
* 1.  **Unified Architecture:** Combines knowledge representation, inference,
*     learning, planning, and agent control loops within one class structure.
* 2.  **Attention & Forgetting:** Implements importance-based attention dynamics
*     (Short-Term Importance - STI, Long-Term Importance - LTI) and forgetting
*     mechanisms to manage memory usage and focus computation, enabling indefinite
*     operation within heap limits.
* 3.  **Advanced Temporal Logic:** Incorporates concepts from Probabilistic Event
*     Calculus (Initiate, Terminate, Holds) and specific temporal link types
*     (PredictiveImplication, SequentialAnd) for richer temporal reasoning.
* 4.  **Developer-Friendly Environment API:** Provides interfaces
*     (`PerceptionTranslator`, `ActionTranslator`) for easily connecting the EPLN
*     agent to arbitrary external systems or simulations.
* 5.  **Enhanced Planning:** Moves beyond reactive action selection towards
*     goal-directed backward chaining search, considering action sequences and
*     predicted outcomes.
* 6.  **Foundation for Higher-Order Logic:** Includes `VariableNode` and quantifier
*     link types (`FOR_ALL`, `EXISTS`) to support future extensions involving
*     variables and schema manipulation. Basic unify is implicitly handled
*     in pattern matching.
* 7.  **Scalability Improvements:** Introduces indexing (`linksByType`,
*     `linksByTarget`) to accelerate inference searches compared to exhaustive scans.
* 8.  **Modular & Extensible:** Logical components (Atoms, TV, Inference Rules,
*     Agent Loop) are clearly defined, facilitating future extensions.
* 9.  **Self-Documenting:** Employs clear naming conventions and structural
*     organization to maximize readability and minimize reliance on comments.
* 10. **Probabilistic Core:** Retains the two-component TruthValue (strength, count)
*     and probabilistic inference rules, simplifying complex PLN formulas while
*     maintaining uncertainty handling.
*/
public class EnhancedPLN {

    // --- Configuration Constants ---
    private static final double DEFAULT_EVIDENCE_SENSITIVITY_K = 1.0; // k in confidence = count / (count + k)
    private static final double INFERENCE_CONFIDENCE_DISCOUNT = 0.9; // Confidence reduction per inference step
    private static final double FORGETTING_THRESHOLD_STI = 0.01; // STI below which Atoms are candidates for forgetting
    private static final double FORGETTING_THRESHOLD_LTI = 0.001; // LTI below which Atoms are candidates for forgetting
    private static final long FORGETTING_CHECK_INTERVAL_MS = 60000; // How often to check for forgetting (1 minute)
    private static final double STI_DECAY_RATE = 0.05; // Percentage decay of STI per forgetting cycle
    private static final double LTI_DECAY_RATE = 0.01; // Percentage decay of LTI per forgetting cycle
    private static final double IMPORTANCE_BOOST_ON_ACCESS = 0.1; // STI boost when an Atom is used
    private static final double IMPORTANCE_BOOST_ON_GOAL_RELEVANCE = 0.5; // STI boost for goal-related Atoms
    private static final double IMPORTANCE_TRANSFER_FACTOR = 0.1; // How much importance flows during inference
    private static final int DEFAULT_BACKWARD_CHAIN_DEPTH = 5;
    private static final int DEFAULT_FORWARD_CHAIN_STEPS = 1;
    private static final double MIN_CONFIDENCE_FOR_ACTION = 0.1; // Minimum confidence for an action utility link to be considered

    // --- Core Knowledge Representation ---
    private final ConcurrentMap<String, Atom> atomSpace = new ConcurrentHashMap<>(1024);
    private final ConcurrentMap<Link.LinkType, ConcurrentSkipListSet<String>> linksByType = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentSkipListSet<String>> linksByTarget = new ConcurrentHashMap<>(); // TargetID -> Set<LinkID>
    private final ConcurrentMap<String, ConcurrentSkipListSet<String>> linksBySource = new ConcurrentHashMap<>(); // SourceID -> Set<LinkID> (for specific link types)

    // --- Agent State ---
    private String currentGoalId = null;
    private String lastActionId = null;
    private String previousStateAtomId = null;
    private final Random random = new Random();
    private volatile boolean running = false;
    private ScheduledExecutorService maintenanceExecutor;

    // --- Environment Interface ---
    private Environment environment;
    private PerceptionTranslator perceptionTranslator;
    private ActionTranslator actionTranslator;

    // --- Constructor ---
    public EnhancedPLN() {
        // Default translators
        this.perceptionTranslator = new DefaultPerceptionTranslator(this);
        this.actionTranslator = new DefaultActionTranslator(this);
        initializeMaintenance();
    }

    // --- Initialization and Control ---

    /** Initializes the background maintenance thread for decay and forgetting. */
    private void initializeMaintenance() {
        maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("EPLN-Maintenance");
            t.setDaemon(true); // Allow JVM exit even if this thread is running
            return t;
        });
        maintenanceExecutor.scheduleAtFixedRate(this::performMaintenance,
                FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops the maintenance thread. */
    public void shutdown() {
        running = false;
        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdown();
            try {
                if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    maintenanceExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                maintenanceExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("EPLN Maintenance Shutdown.");
    }

    // --- Knowledge Base Access and Modification ---

    /**
     * Adds or updates an Atom in the AtomSpace. Handles revision if exists.
     * Updates importance and indexes.
     *
     * @param atom The Atom to add or update.
     * @return The resulting Atom in the AtomSpace (potentially revised).
     */
    public synchronized Atom addAtom(Atom atom) {
        Atom existingAtom = atomSpace.get(atom.id);
        if (existingAtom != null) {
            // Revision: Merge TruthValues and boost importance
            TruthValue revisedTV = existingAtom.tv.merge(atom.tv);
            existingAtom.tv = revisedTV;
            existingAtom.updateImportance(IMPORTANCE_BOOST_ON_ACCESS + atom.sti); // Boost based on new evidence/access
            existingAtom.touch(); // Update last accessed time
            // System.out.println("Revised: " + existingAtom.id + " -> " + existingAtom.tv + " STI: " + existingAtom.sti);
            return existingAtom;
        } else {
            // New Atom: Add to AtomSpace and indexes
            atom.touch();
            atomSpace.put(atom.id, atom);
            updateIndexes(atom, true); // Add to indexes
            // System.out.println("Added: " + atom.id + " " + atom.tv + " STI: " + atom.sti);
            return atom;
        }
    }

    /**
     * Retrieves an Atom by its ID, updating its importance.
     *
     * @param id The ID of the Atom.
     * @return The Atom, or null if not found.
     */
    public Atom getAtom(String id) {
        Atom atom = atomSpace.get(id);
        if (atom != null) {
            atom.updateImportance(IMPORTANCE_BOOST_ON_ACCESS);
            atom.touch();
        }
        return atom;
    }

    /** Removes an Atom from the AtomSpace and associated indexes. */
    private synchronized void removeAtom(String id) {
        Atom atom = atomSpace.remove(id);
        if (atom != null) {
            updateIndexes(atom, false); // Remove from indexes
            // System.out.println("Forgotten: " + id);
        }
    }

    /** Updates the indexes for a given Atom (add or remove). */
    private synchronized void updateIndexes(Atom atom, boolean add) {
        if (atom instanceof Link) {
            Link link = (Link) atom;
            // Index by Type
            ConcurrentSkipListSet<String> typeSet = linksByType.computeIfAbsent(link.type, k -> new ConcurrentSkipListSet<>());
            if (add) typeSet.add(link.id); else typeSet.remove(link.id);

            // Index by Target
            for (String targetId : link.targets) {
                ConcurrentSkipListSet<String> targetSet = linksByTarget.computeIfAbsent(targetId, k -> new ConcurrentSkipListSet<>());
                if (add) targetSet.add(link.id); else targetSet.remove(link.id);
            }

            // Index by Source (for specific types like Inheritance, PredictiveImplication)
            if (!link.targets.isEmpty() && (link.type == Link.LinkType.INHERITANCE || link.type == Link.LinkType.PREDICTIVE_IMPLICATION)) {
                String sourceId = link.targets.get(0); // Assuming convention: first target is source
                 ConcurrentSkipListSet<String> sourceSet = linksBySource.computeIfAbsent(sourceId, k -> new ConcurrentSkipListSet<>());
                 if (add) sourceSet.add(link.id); else sourceSet.remove(link.id);
            }
        }
        // Could add indexes for Nodes by name prefix, etc. if needed
    }

    /** Gets a Node by name, creating it with default TV/Importance if absent. */
    public Node getOrCreateNode(String name) {
        return getOrCreateNode(name, TruthValue.DEFAULT, 0.1); // Default low initial importance
    }

    /** Gets a Node by name, creating it with specified TV/Importance if absent. */
    public Node getOrCreateNode(String name, TruthValue tv, double initialSTI) {
        String nodeId = Node.generateIdFromName(name);
        Atom existing = getAtom(nodeId); // Use getAtom to update importance if exists
        if (existing instanceof Node) {
            return (Node) existing;
        } else {
            Node newNode = new Node(name, tv);
            newNode.sti = initialSTI;
            newNode.lti = initialSTI / 10.0; // LTI starts lower
            return (Node) addAtom(newNode);
        }
    }

    /** Creates a Link Atom and adds/revises it in the AtomSpace. */
    public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, String... targetIds) {
        return addLink(type, tv, initialSTI, Arrays.asList(targetIds));
    }

     /** Creates a Link Atom and adds/revises it in the AtomSpace. */
    public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, Atom... targets) {
         List<String> targetIds = Arrays.stream(targets)
                                       .filter(Objects::nonNull)
                                       .map(a -> a.id)
                                       .collect(Collectors.toList());
        return addLink(type, tv, initialSTI, targetIds);
    }

    /** Creates a Link Atom and adds/revises it in the AtomSpace. */
    public Link addLink(Link.LinkType type, TruthValue tv, double initialSTI, List<String> targetIds) {
        if (targetIds == null || targetIds.isEmpty() || targetIds.stream().anyMatch(Objects::isNull)) {
             System.err.println("Warning: Attempted to create link with null or empty targets. Type: " + type);
             return null; // Cannot create link without valid targets
        }
        Link link = new Link(type, targetIds, tv);
        link.sti = initialSTI;
        link.lti = initialSTI / 10.0;
        return (Link) addAtom(link);
    }

    // --- Attention and Forgetting ---

    /** Performs decay and forgetting maintenance cycle. */
    private synchronized void performMaintenance() {
        if (!running) return; // Don't run if agent loop isn't active
        long now = System.currentTimeMillis();
        // System.out.println("Performing EPLN Maintenance...");

        List<String> atomsToForget = new ArrayList<>();
        for (Atom atom : atomSpace.values()) {
            // Decay Importance
            atom.decayImportance(STI_DECAY_RATE, LTI_DECAY_RATE);

            // Check Forgetting Thresholds (consider time since last access)
            long timeSinceAccess = now - atom.lastAccessedTime;
            // Increase forgetting likelihood if not accessed for a long time (e.g., > 10 cycles)
            double accessPenalty = Math.min(1.0, timeSinceAccess / (double)(FORGETTING_CHECK_INTERVAL_MS * 10));
            double effectiveSTI = atom.sti * (1.0 - accessPenalty);
            double effectiveLTI = atom.lti * (1.0 - accessPenalty);

            if (effectiveSTI < FORGETTING_THRESHOLD_STI && effectiveLTI < FORGETTING_THRESHOLD_LTI) {
                // Basic check: Don't forget Atoms currently part of the goal or recent plan
                boolean isProtected = (currentGoalId != null && atom.id.equals(currentGoalId)) ||
                                      (lastActionId != null && atom.id.equals(lastActionId)) ||
                                      (previousStateAtomId != null && atom.id.equals(previousStateAtomId));
                // More advanced: Check if atom is crucial structural element (e.g., type definition) - requires schema knowledge

                if (!isProtected) {
                    atomsToForget.add(atom.id);
                }
            }
        }

        if (!atomsToForget.isEmpty()) {
            // System.out.println("Forgetting " + atomsToForget.size() + " atoms.");
            atomsToForget.forEach(this::removeAtom);
        }
        // System.out.println("Maintenance complete. AtomSpace size: " + atomSpace.size());
    }


    // --- Core Inference Rules ---

    /**
     * Probabilistic Deduction (A -> B, B -> C => A -> C).
     * Handles both Inheritance and PredictiveImplication links.
     * Uses simplified formula: sAC = sAB * sBC. (More complex formulas possible).
     * Combines counts and transfers importance.
     *
     * @param linkAB Premise 1 (Inheritance or PredictiveImplication)
     * @param linkBC Premise 2 (Inheritance or PredictiveImplication)
     * @return The inferred Link(A, C), or null if inference fails.
     */
    public Link deduction(Link linkAB, Link linkBC) {
        if (linkAB == null || linkBC == null || linkAB.targets.size() < 2 || linkBC.targets.size() < 2) return null;
        if (linkAB.type != linkBC.type || (linkAB.type != Link.LinkType.INHERITANCE && linkAB.type != Link.LinkType.PREDICTIVE_IMPLICATION)) return null;

        String aId = linkAB.targets.get(0);
        String bId1 = linkAB.targets.get(1);
        String bId2 = linkBC.targets.get(0);
        String cId = linkBC.targets.get(1);

        if (!bId1.equals(bId2)) return null; // Intermediate terms must match

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId1);
        Atom nodeC = getAtom(cId);
        if (nodeA == null || nodeB == null || nodeC == null) return null; // Nodes must exist

        // Basic deduction formula (simplistic, assumes independence)
        double sAC = linkAB.tv.strength * linkBC.tv.strength;

        // Combine counts (minimum, discounted)
        double nAC = INFERENCE_CONFIDENCE_DISCOUNT * Math.min(linkAB.tv.count, linkBC.tv.count);
        TruthValue tvAC = new TruthValue(sAC, nAC);

        // Calculate importance (average of premises, boosted by premise importance)
        double stiAC = IMPORTANCE_TRANSFER_FACTOR * (linkAB.sti + linkBC.sti) / 2.0;

        // Handle time for PredictiveImplication (simple addition of delays, needs refinement)
        TimeValue timeAC = null;
        if (linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION && linkAB.time != null && linkBC.time != null) {
            timeAC = linkAB.time.add(linkBC.time); // Simplistic time combination
        }

        Link inferredLink = new Link(linkAB.type, Arrays.asList(aId, cId), tvAC);
        inferredLink.sti = stiAC;
        inferredLink.lti = stiAC / 10.0;
        inferredLink.time = timeAC;

        // System.out.println("  Deduction: " + linkAB.shortId() + ", " + linkBC.shortId() + " => " + inferredLink.shortId() + " " + inferredLink.tv);
        return (Link) addAtom(inferredLink);
    }

    /**
     * Probabilistic Inversion (A -> B => B -> A).
     * Requires term probabilities P(A) and P(B) (approximated by Node strength).
     * Formula: sBA = sAB * sA / sB.
     *
     * @param linkAB The Inheritance link A -> B.
     * @return The inferred Inheritance link B -> A, or null.
     */
    public Link inversion(Link linkAB) {
        if (linkAB == null || linkAB.type != Link.LinkType.INHERITANCE || linkAB.targets.size() != 2) return null;

        String aId = linkAB.targets.get(0);
        String bId = linkAB.targets.get(1);

        Atom nodeA = getAtom(aId);
        Atom nodeB = getAtom(bId);

        if (!(nodeA instanceof Node) || !(nodeB instanceof Node)) return null; // Requires nodes for probabilities

        double sAB = linkAB.tv.strength;
        double nAB = linkAB.tv.count;
        double sA = nodeA.tv.strength; // P(A)
        double sB = nodeB.tv.strength; // P(B)

        double sBA;
        if (sB < 1e-9) { // Avoid division by zero
            sBA = 0.5; // Max uncertainty if P(B) is zero
            nAB = 0.0; // No evidence
        } else {
            sBA = sAB * sA / sB;
            sBA = Math.max(0.0, Math.min(1.0, sBA)); // Clamp to [0, 1]
        }

        double nBA = INFERENCE_CONFIDENCE_DISCOUNT * nAB;
        TruthValue tvBA = new TruthValue(sBA, nBA);

        double stiBA = IMPORTANCE_TRANSFER_FACTOR * linkAB.sti;

        Link inferredLink = new Link(Link.LinkType.INHERITANCE, Arrays.asList(bId, aId), tvBA); // Note reversed targets
        inferredLink.sti = stiBA;
        inferredLink.lti = stiBA / 10.0;

        // System.out.println("  Inversion: " + linkAB.shortId() + " => " + inferredLink.shortId() + " " + inferredLink.tv);
        return (Link) addAtom(inferredLink);
    }

    /**
     * Probabilistic Modus Ponens (A, A -> B => B).
     * Handles Inheritance and PredictiveImplication.
     *
     * @param premiseA Atom A.
     * @param linkAB   Link A -> B (Inheritance or PredictiveImplication).
     * @return The inferred Atom B, or null.
     */
     public Atom modusPonens(Atom premiseA, Link linkAB) {
        if (premiseA == null || linkAB == null || linkAB.targets.size() < 2) return null;
        if (linkAB.type != Link.LinkType.INHERITANCE && linkAB.type != Link.LinkType.PREDICTIVE_IMPLICATION) return null;
        if (!linkAB.targets.get(0).equals(premiseA.id)) return null; // Premise must match link source

        String bId = linkAB.targets.get(1);
        Atom existingB = getAtom(bId); // Get current state of B
        if (existingB == null) return null; // Target must exist

        // Simplified strength calculation: sB = sA * sAB (Assumes independence)
        // A more accurate formula depends on P(B) which we might not know well.
        double sB_inferred = premiseA.tv.strength * linkAB.tv.strength;

        // Combine counts (minimum, discounted)
        double nB_inferred = INFERENCE_CONFIDENCE_DISCOUNT * Math.min(premiseA.tv.count, linkAB.tv.count);
        TruthValue tvB_inferred = new TruthValue(sB_inferred, nB_inferred);

        // Calculate importance
        double stiB = IMPORTANCE_TRANSFER_FACTOR * (premiseA.sti + linkAB.sti) / 2.0;

        // Create a temporary Atom representing the inferred evidence for B
        Atom inferredEvidenceB = Atom.createShell(bId); // Create shell with correct type if possible
        inferredEvidenceB.tv = tvB_inferred;
        inferredEvidenceB.sti = stiB;
        inferredEvidenceB.lti = stiB / 10.0;
        // Handle time for PredictiveImplication
        if (linkAB.type == Link.LinkType.PREDICTIVE_IMPLICATION && linkAB.time != null) {
             // Need a concept of "current time" to apply the delay
             // For now, just mark the inferred evidence with the potential future time relative to premiseA's time
             if (premiseA.time != null) {
                 inferredEvidenceB.time = premiseA.time.add(linkAB.time);
             } else {
                 // If premise time unknown, maybe use link's interval as uncertainty?
                 inferredEvidenceB.time = linkAB.time;
             }
        }


        // Add/revise the evidence into the actual Atom B
        // System.out.println("  ModusPonens: " + premiseA.shortId() + ", " + linkAB.shortId() + " => " + bId + " (evidence: " + tvB_inferred + ")");
        return addAtom(inferredEvidenceB); // addAtom handles the revision with existingB
    }

    // --- Inference Control ---

    /**
     * Performs forward chaining inference for a limited number of steps.
     * Uses indexes for efficiency. Prioritizes based on premise importance.
     *
     * @param maxSteps Maximum inference steps.
     */
    public void forwardChain(int maxSteps) {
        // System.out.println("--- Starting Forward Chaining (Max Steps: " + maxSteps + ") ---");
        Set<String> processedPremisePairs = new HashSet<>(); // Avoid redundant rule applications

        for (int step = 0; step < maxSteps; step++) {
            int inferencesMadeThisStep = 0;
            processedPremisePairs.clear();

            // --- Deduction (A->B, B->C => A->C) ---
            // Iterate through potential middle terms B
            for (Atom nodeB : getAtomsStream().filter(a -> a instanceof Node).toList()) {
                 // Find links ending at B (X->B) using index
                 Set<String> linksToB_Ids = linksByTarget.getOrDefault(nodeB.id, Collections.emptySet());
                 // Find links starting at B (B->Y) using index
                 Set<String> linksFromB_Ids = linksBySource.getOrDefault(nodeB.id, Collections.emptySet());

                 if (!linksToB_Ids.isEmpty() && !linksFromB_Ids.isEmpty()) {
                     List<Link> linksToB = linksToB_Ids.stream()
                                             .map(this::getAtom)
                                             .filter(a -> a instanceof Link)
                                             .map(a -> (Link)a)
                                             .filter(l -> l.type == Link.LinkType.INHERITANCE || l.type == Link.LinkType.PREDICTIVE_IMPLICATION)
                                             .sorted(Comparator.comparingDouble(Atom::getSTI).reversed()) // Prioritize important premises
                                             .toList();
                     List<Link> linksFromB = linksFromB_Ids.stream()
                                             .map(this::getAtom)
                                             .filter(a -> a instanceof Link)
                                             .map(a -> (Link)a)
                                             .filter(l -> l.type == Link.LinkType.INHERITANCE || l.type == Link.LinkType.PREDICTIVE_IMPLICATION)
                                             .sorted(Comparator.comparingDouble(Atom::getSTI).reversed()) // Prioritize important premises
                                             .toList();

                     for (Link linkAB : linksToB) {
                         for (Link linkBC : linksFromB) {
                             // Check if intermediate node B actually matches (indexes guarantee B is *a* target/source, check specific position)
                             if (linkAB.targets.size() >= 2 && linkBC.targets.size() >=2 && linkAB.targets.get(1).equals(nodeB.id) && linkBC.targets.get(0).equals(nodeB.id)) {
                                 String pairId = linkAB.id + "|" + linkBC.id;
                                 if (!processedPremisePairs.contains(pairId)) {
                                     Link inferredAC = deduction(linkAB, linkBC);
                                     if (inferredAC != null) {
                                         inferencesMadeThisStep++;
                                     }
                                     processedPremisePairs.add(pairId);
                                     // Limit processing per step if needed
                                     if (inferencesMadeThisStep > 1000) break; // Safety break
                                 }
                             }
                         }
                         if (inferencesMadeThisStep > 1000) break;
                     }
                 }
                 if (inferencesMadeThisStep > 1000) break;
            }


            // --- Inversion (A->B => B->A) ---
            // Use index for Inheritance links
            Set<String> inheritanceLinkIds = linksByType.getOrDefault(Link.LinkType.INHERITANCE, Collections.emptySet());
            List<Link> inheritanceLinks = inheritanceLinkIds.stream()
                                            .map(this::getAtom)
                                            .filter(a -> a instanceof Link)
                                            .map(a -> (Link)a)
                                            .sorted(Comparator.comparingDouble(Atom::getSTI).reversed())
                                            .toList();

            for (Link linkAB : inheritanceLinks) {
                 String invId = linkAB.id + "|inv";
                 if (!processedPremisePairs.contains(invId)) {
                     Link inferredBA = inversion(linkAB);
                     if (inferredBA != null) {
                         inferencesMadeThisStep++;
                     }
                     processedPremisePairs.add(invId);
                     if (inferencesMadeThisStep > 1000) break; // Safety break
                 }
            }

             // --- Modus Ponens (A, A->B => B) ---
             // Iterate through potential implications (A->B)
             Set<String> implicationLinkIds = new HashSet<>();
             implicationLinkIds.addAll(linksByType.getOrDefault(Link.LinkType.INHERITANCE, Collections.emptySet()));
             implicationLinkIds.addAll(linksByType.getOrDefault(Link.LinkType.PREDICTIVE_IMPLICATION, Collections.emptySet()));

             List<Link> implicationLinks = implicationLinkIds.stream()
                                             .map(this::getAtom)
                                             .filter(a -> a instanceof Link && ((Link)a).targets.size() >= 2)
                                             .map(a -> (Link)a)
                                             .sorted(Comparator.comparingDouble(Atom::getSTI).reversed())
                                             .toList();

             for (Link linkAB : implicationLinks) {
                 String premiseAId = linkAB.targets.get(0);
                 Atom premiseA = getAtom(premiseAId);
                 if (premiseA != null && premiseA.tv.getConfidence() > 0.1) { // Only apply if premise A has some confidence
                     String mpId = premiseA.id + "|" + linkAB.id;
                     if (!processedPremisePairs.contains(mpId)) {
                         Atom inferredB = modusPonens(premiseA, linkAB);
                         if (inferredB != null) {
                             inferencesMadeThisStep++;
                         }
                         processedPremisePairs.add(mpId);
                         if (inferencesMadeThisStep > 1000) break; // Safety break
                     }
                 }
             }


            if (inferencesMadeThisStep == 0) {
                // System.out.println("No new inferences made in step " + (step + 1) + ". Stopping.");
                break;
            } else {
                 // System.out.println("Step " + (step + 1) + ": Made " + inferencesMadeThisStep + " inferences.");
            }
        }
        // System.out.println("--- Forward Chaining Finished ---");
    }


    /**
     * Performs backward chaining to find evidence or a plan for a target Atom.
     * Uses indexes and importance heuristics. Can search for action sequences.
     *
     * @param targetId The ID of the target Atom (goal).
     * @param maxDepth Maximum recursion depth.
     * @return An Atom representing evidence found for the target, or a plan (e.g., SequentialAndLink of actions), or null.
     */
    public Atom backwardChain(String targetId, int maxDepth) {
        // System.out.println("\n--- Starting Backward Chaining (Target: " + targetId + ", Max Depth: " + maxDepth + ") ---");
        // Use a map to store the best evidence found for each subgoal to avoid redundant work and cycles
        Map<String, Atom> derivedEvidence = new ConcurrentHashMap<>();
        Atom result = backwardChainRecursive(targetId, maxDepth, derivedEvidence, 0);
        // System.out.println("--- Backward Chaining Finished. Result for " + targetId + ": " + (result != null ? result.tv : "None") + " ---");
        return result; // Return the evidence found for the original target
    }

    private Atom backwardChainRecursive(String targetId, int depth, Map<String, Atom> derivedEvidence, int currentDepth) {
        String indent = "  ".repeat(currentDepth);
        // System.out.println(indent + "BC: Seeking " + targetId + " (Depth " + depth + ")");

        if (depth <= 0) {
            // System.out.println(indent + "-> Max depth reached.");
            return getAtom(targetId); // Return existing knowledge at max depth
        }

        // Check cache / cycle detection
        if (derivedEvidence.containsKey(targetId)) {
             // System.out.println(indent + "-> Already sought (returning cached/existing).");
             return derivedEvidence.get(targetId); // Return previously found evidence or null marker
        }
        // Add a placeholder to detect cycles immediately
        derivedEvidence.put(targetId, null); // Placeholder for cycle detection

        Atom currentTargetAtom = getAtom(targetId);
        Atom bestEvidenceFound = currentTargetAtom; // Start with existing knowledge

        // --- Try rules that could conclude targetId ---

        // 1. Can Modus Ponens (A, A->Target => Target) conclude targetId?
        // Find links X -> Target using index
        Set<String> linksToTargetIds = linksByTarget.getOrDefault(targetId, Collections.emptySet());
        List<Link> potentialImplications = linksToTargetIds.stream()
                .map(this::getAtom)
                .filter(a -> a instanceof Link)
                .map(a -> (Link) a)
                .filter(l -> (l.type == Link.LinkType.INHERITANCE || l.type == Link.LinkType.PREDICTIVE_IMPLICATION) && l.targets.size() >= 2 && l.targets.get(1).equals(targetId))
                .sorted(Comparator.comparingDouble(Atom::getSTI).reversed()) // Prioritize important rules
                .toList();

        for (Link linkX_Target : potentialImplications) {
            String premiseXId = linkX_Target.targets.get(0);
            // System.out.println(indent + " BC/MP: Trying rule " + linkX_Target.shortId() + ", seeking premise " + premiseXId);
            Atom premiseEvidence = backwardChainRecursive(premiseXId, depth - 1, derivedEvidence, currentDepth + 1);

            if (premiseEvidence != null && premiseEvidence.tv.getConfidence() > 0.01) { // Found sufficient evidence for premise
                // Apply Modus Ponens to generate evidence for the target
                Atom inferredTargetEvidence = modusPonens(premiseEvidence, linkX_Target);
                if (inferredTargetEvidence != null) {
                     // Merge this new evidence with the best found so far
                     if (bestEvidenceFound == null) {
                         bestEvidenceFound = inferredTargetEvidence;
                     } else {
                         // Merge based on confidence/count (or other criteria)
                         if (inferredTargetEvidence.tv.getConfidence() > bestEvidenceFound.tv.getConfidence()) {
                             bestEvidenceFound = inferredTargetEvidence; // Keep the one derived with higher confidence
                         }
                         // More sophisticated merging could revise TVs
                     }
                     // System.out.println(indent + " BC/MP: Derived evidence for " + targetId + ": " + inferredTargetEvidence.tv);
                }
            }
        }


        // 2. Can Deduction (A->B, B->Target => A->Target) conclude targetId?
        // Target must be an Inheritance or PredictiveImplication link, e.g., A -> Target
        Atom targetAtomShell = Atom.createShell(targetId); // Try to parse ID
        if (targetAtomShell instanceof Link && (((Link)targetAtomShell).type == Link.LinkType.INHERITANCE || ((Link)targetAtomShell).type == Link.LinkType.PREDICTIVE_IMPLICATION) && ((Link)targetAtomShell).targets.size() >= 2) {
            String targetA_Id = ((Link)targetAtomShell).targets.get(0);
            String targetActualTarget_Id = ((Link)targetAtomShell).targets.get(1); // This should match the 'targetId' parameter conceptually if ID parsing worked perfectly, but use parsed value

            // Find potential intermediate nodes B
            // Iterate through all nodes? Or use heuristics? For now, check nodes linked TO targetActualTarget_Id
             Set<String> linksToActualTargetIds = linksByTarget.getOrDefault(targetActualTarget_Id, Collections.emptySet());
             List<Link> potentialBCLinks = linksToActualTargetIds.stream()
                    .map(this::getAtom).filter(Objects::nonNull)
                    .filter(a -> a instanceof Link && ((Link)a).type == ((Link)targetAtomShell).type && ((Link)a).targets.size() >= 2)
                    .map(a -> (Link)a)
                    .filter(l -> l.targets.get(1).equals(targetActualTarget_Id)) // Ensure it's B -> Target
                    .sorted(Comparator.comparingDouble(Atom::getSTI).reversed())
                    .toList();

            for (Link linkBC : potentialBCLinks) {
                String potentialB_Id = linkBC.targets.get(0);
                String premiseAB_Id = new Link(((Link)targetAtomShell).type, Arrays.asList(targetA_Id, potentialB_Id), TruthValue.DEFAULT).id; // Construct needed premise ID

                // System.out.println(indent + " BC/Deduction: Trying intermediate B=" + potentialB_Id + " via rule " + linkBC.shortId() + ", seeking premise " + premiseAB_Id);

                // Recursively seek premise A->B
                Atom premiseAB_Evidence = backwardChainRecursive(premiseAB_Id, depth - 1, derivedEvidence, currentDepth + 1);

                // Recursively seek premise B->Target (we already have linkBC, but check its confidence)
                 Atom premiseBC_Evidence = backwardChainRecursive(linkBC.id, depth - 1, derivedEvidence, currentDepth + 1);


                if (premiseAB_Evidence instanceof Link && premiseBC_Evidence instanceof Link &&
                    premiseAB_Evidence.tv.getConfidence() > 0.01 && premiseBC_Evidence.tv.getConfidence() > 0.01) {
                    // Apply Deduction
                    Atom inferredTargetEvidence = deduction((Link)premiseAB_Evidence, (Link)premiseBC_Evidence);
                     if (inferredTargetEvidence != null) {
                         if (bestEvidenceFound == null || inferredTargetEvidence.tv.getConfidence() > bestEvidenceFound.tv.getConfidence()) {
                             bestEvidenceFound = inferredTargetEvidence;
                         }
                         // System.out.println(indent + " BC/Deduction: Derived evidence for " + targetId + ": " + inferredTargetEvidence.tv);
                     }
                }
            }
        }

        // 3. Can Inversion (Target -> A => A -> Target) conclude targetId?
        // Similar logic to Deduction check, target must be A -> Target link
        if (targetAtomShell instanceof Link && ((Link)targetAtomShell).type == Link.LinkType.INHERITANCE && ((Link)targetAtomShell).targets.size() >= 2) {
             String targetA_Id = ((Link)targetAtomShell).targets.get(0);
             String targetActualTarget_Id = ((Link)targetAtomShell).targets.get(1);
             String premiseTargetA_Id = new Link(Link.LinkType.INHERITANCE, Arrays.asList(targetActualTarget_Id, targetA_Id), TruthValue.DEFAULT).id;

             // System.out.println(indent + " BC/Inversion: Seeking premise " + premiseTargetA_Id);
             Atom premiseTargetA_Evidence = backwardChainRecursive(premiseTargetA_Id, depth - 1, derivedEvidence, currentDepth + 1);

             if (premiseTargetA_Evidence instanceof Link && premiseTargetA_Evidence.tv.getConfidence() > 0.01) {
                 Atom inferredTargetEvidence = inversion((Link)premiseTargetA_Evidence);
                  if (inferredTargetEvidence != null) {
                      if (bestEvidenceFound == null || inferredTargetEvidence.tv.getConfidence() > bestEvidenceFound.tv.getConfidence()) {
                          bestEvidenceFound = inferredTargetEvidence;
                      }
                      // System.out.println(indent + " BC/Inversion: Derived evidence for " + targetId + ": " + inferredTargetEvidence.tv);
                  }
             }
        }


        // --- Planning Specific: Find Actions ---
        // If the target is a state/goal, look for actions predicted to lead to it.
        // Look for PredictiveImplication(Action, Target) or PredictiveImplication(Sequence(..., Action), Target)
        // This part needs more sophisticated plan construction (building SeqAnd links)
        // Simple version: Find single actions X such that PredictiveImplication(X, Target) exists and is strong.
        if (!(targetAtomShell instanceof Link)) { // If target is likely a state/node
             Set<String> linksToTargetIdsAction = linksByTarget.getOrDefault(targetId, Collections.emptySet());
             List<Link> potentialActionLinks = linksToTargetIdsAction.stream()
                    .map(this::getAtom).filter(Objects::nonNull)
                    .filter(a -> a instanceof Link && ((Link)a).type == Link.LinkType.PREDICTIVE_IMPLICATION && ((Link)a).targets.size() >= 2)
                    .map(a -> (Link)a)
                    .filter(l -> l.targets.get(1).equals(targetId)) // Ensure it's X -> Target
                    .sorted(Comparator.comparingDouble((Link l) -> l.tv.strength * l.tv.getConfidence()).reversed()) // Prioritize high-utility predictions
                    .toList();

             for (Link actionLink : potentialActionLinks) {
                 String actionId = actionLink.targets.get(0);
                 Atom actionAtom = getAtom(actionId);
                 // Check if actionId represents an executable action (e.g., starts with "Action_")
                 if (actionAtom instanceof Node && ((Node)actionAtom).name.startsWith(ActionTranslator.ACTION_PREFIX)) {
                     // Found a potential action leading towards the goal.
                     // In a real planner, we'd build a plan sequence. Here, just return the action as potential evidence/plan step.
                     // We need a way to represent the "plan" - maybe return the action Atom itself?
                     // Let's prioritize returning the action if its prediction is stronger than other derived evidence.
                     double actionUtility = actionLink.tv.strength * actionLink.tv.getConfidence();
                     if (bestEvidenceFound == null || actionUtility > bestEvidenceFound.tv.getConfidence()) { // Compare utility to confidence
                          // System.out.println(indent + " BC/Plan: Found potential action " + actionId + " via " + actionLink.shortId() + " with utility " + actionUtility);
                          // Return the *action* atom as a candidate plan step
                          bestEvidenceFound = actionAtom;
                     }
                 }
                 // Could also recursively check if the source X of the link is achievable (if X is not an action itself but an intermediate state)
                 // Atom sourceEvidence = backwardChainRecursive(actionId, depth - 1, derivedEvidence, currentDepth + 1); ...
             }
        }


        // Update cache with the best evidence found
        derivedEvidence.put(targetId, bestEvidenceFound);
        // System.out.println(indent + "BC: Result for " + targetId + ": " + (bestEvidenceFound != null ? bestEvidenceFound.shortId() + " " + bestEvidenceFound.tv : "Not Found"));
        return bestEvidenceFound;
    }


    // --- Agent Loop and Control ---

    /** Sets the environment and translators for the agent. */
    public void initializeAgent(Environment env, PerceptionTranslator pt, ActionTranslator at) {
        this.environment = env;
        this.perceptionTranslator = pt != null ? pt : new DefaultPerceptionTranslator(this);
        this.actionTranslator = at != null ? at : new DefaultActionTranslator(this);
        System.out.println("Agent Initialized with Environment: " + env.getClass().getSimpleName());
    }

    /** Sets the agent's primary goal. */
    public void setGoal(String goalDescription) {
        // Translate goal description into a target Atom ID
        // Simple version: Assume description is the name of the target Node
        this.currentGoalId = Node.generateIdFromName(goalDescription);
        Atom goalAtom = getOrCreateNode(goalDescription, new TruthValue(1.0, 1.0), 1.0); // High desired strength/importance
        goalAtom.updateImportance(IMPORTANCE_BOOST_ON_GOAL_RELEVANCE); // Boost goal importance
        System.out.println("Agent Goal Set To: " + goalAtom);
    }

    /**
     * Runs the agent's perceive-learn-act cycle.
     *
     * @param maxSteps Maximum number of steps to run, or -1 for indefinite.
     */
    public void run(int maxSteps) {
        if (environment == null || perceptionTranslator == null || actionTranslator == null) {
            System.err.println("Agent not initialized. Call initializeAgent() first.");
            return;
        }
        if (currentGoalId == null) {
            System.err.println("No goal set. Call setGoal() first.");
            return;
        }

        running = true;
        System.out.println("--- Starting Agent Run ---");
        System.out.println("Goal: " + getAtom(currentGoalId));

        // Initial perception
        Map<String, Object> currentPercepts = environment.getCurrentPercepts();
        previousStateAtomId = perceptionTranslator.translatePercepts(currentPercepts);
        if (previousStateAtomId != null) getAtom(previousStateAtomId).updateImportance(0.5); // Boost initial state importance

        int step = 0;
        while (running && (maxSteps == -1 || step < maxSteps) && !environment.isTerminated()) {
            System.out.println("\n--- Agent Step " + (step + 1) + " ---");
            Atom currentStateAtom = getAtom(previousStateAtomId);
            System.out.println("Current State Atom: " + (currentStateAtom != null ? currentStateAtom : "None"));

            // 1. Reason (Forward Chaining)
            forwardChain(DEFAULT_FORWARD_CHAIN_STEPS);

            // 2. Plan/Select Action (Backward Chaining from Goal)
            lastActionId = selectAction();
            if (lastActionId == null) {
                System.out.println("No suitable action found. Exploring randomly or stopping.");
                // Implement exploration strategy or stop if no actions possible
                 List<String> availableActions = environment.getAvailableActions();
                 if (availableActions.isEmpty()) {
                     System.out.println("No actions available. Stopping run.");
                     break;
                 }
                 String randomActionName = availableActions.get(random.nextInt(availableActions.size()));
                 lastActionId = actionTranslator.getActionAtomId(randomActionName);
                 System.out.println("Exploring: Selected random action " + randomActionName);
                 if (lastActionId == null) { // Ensure translator creates the atom
                     Node actionNode = getOrCreateNode(ActionTranslator.ACTION_PREFIX + randomActionName, TruthValue.DEFAULT, 0.2);
                     lastActionId = actionNode.id;
                 }
            }

            Atom actionAtom = getAtom(lastActionId);
            if (actionAtom == null) {
                 System.err.println("Error: Selected action Atom ID " + lastActionId + " does not exist.");
                 break;
            }
            System.out.println("Selected Action: " + actionAtom);

            // 3. Execute Action
            String actionCommand = actionTranslator.translateAction(lastActionId);
            if (actionCommand == null) {
                System.err.println("Error: Could not translate action ID " + lastActionId + " to environment command.");
                break;
            }
            System.out.println("Executing: " + actionCommand);
            environment.performAction(actionCommand);

            // 4. Perceive Outcome
            Map<String, Object> nextPercepts = environment.getCurrentPercepts();
            double reward = environment.getReward();
            String currentStateId = perceptionTranslator.translatePercepts(nextPercepts);
            if (currentStateId == null) {
                 System.err.println("Warning: Perception failed to produce a state atom.");
                 // Decide how to handle - skip learning? Use previous state?
                 currentStateId = previousStateAtomId; // Fallback for now
            } else {
                getAtom(currentStateId).updateImportance(0.5); // Boost current state importance
            }


            // 5. Learn from Experience
            learnFromExperience(previousStateAtomId, lastActionId, currentStateId, reward);

            // Update state for next loop
            previousStateAtomId = currentStateId;
            step++;

            // Optional: Check if goal is achieved
            Atom currentGoalAtom = getAtom(currentGoalId);
            Atom achievedStateAtom = getAtom(currentStateId);
            if (achievedStateAtom != null && currentGoalAtom != null && achievedStateAtom.id.equals(currentGoalAtom.id)) {
                 // Simple check: is the current state atom the goal atom?
                 // More complex: check if current state *implies* goal state with high confidence
                 System.out.println("*** Goal Achieved! ***");
                 // Decide whether to stop or set a new goal
                 // running = false; // Example: stop after achieving goal once
            }

            // Small delay for observation/simulation pacing
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); running = false; }
        }

        running = false;
        System.out.println("--- Agent Run Finished ---");
        System.out.println("Final AtomSpace size: " + atomSpace.size());
    }

    /** Selects an action using backward chaining from the current goal. */
    private String selectAction() {
        if (currentGoalId == null) return null;

        // Use backward chaining to find an action predicted to lead to the goal
        Atom planElement = backwardChain(currentGoalId, DEFAULT_BACKWARD_CHAIN_DEPTH);

        if (planElement instanceof Node && ((Node)planElement).name.startsWith(ActionTranslator.ACTION_PREFIX)) {
            // Backward chaining directly returned a promising action
            return planElement.id;
        } else if (planElement instanceof Link && ((Link)planElement).type == Link.LinkType.SEQUENTIAL_AND) {
            // Backward chaining returned a sequence plan (more advanced)
            // Extract the first action from the sequence
            Link planSeq = (Link) planElement;
            if (!planSeq.targets.isEmpty()) {
                Atom firstStep = getAtom(planSeq.targets.get(0));
                // Assume first step is Evaluation(try, Action) or just the Action node
                if (firstStep instanceof Node && ((Node)firstStep).name.startsWith(ActionTranslator.ACTION_PREFIX)) {
                    return firstStep.id;
                } else if (firstStep instanceof Link && ((Link)firstStep).type == Link.LinkType.EVALUATION) {
                     // Look for Action node within Evaluation targets
                     for(String targetId : ((Link)firstStep).targets) {
                         Atom target = getAtom(targetId);
                         if (target instanceof Node && ((Node)target).name.startsWith(ActionTranslator.ACTION_PREFIX)) {
                             return target.id;
                         }
                     }
                }
            }
        }

        // Fallback: If BC didn't find a direct action, query action utilities learned via learnFromExperience
        // Find links Action -> Goal or Action -> GoodAction
        // System.out.println("BC did not yield direct action, checking learned utilities...");
        Map<String, Double> actionUtilities = new HashMap<>();
        Node goodActionConcept = getOrCreateNode("GoodAction"); // Reflective concept

        for (String actionName : environment.getAvailableActions()) {
            String actionId = actionTranslator.getActionAtomId(actionName);
            if (actionId == null) continue; // Action not known? Translator should create it.

            // Check direct prediction: Action -> Goal
            String predLinkGoalId = new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(actionId, currentGoalId), TruthValue.DEFAULT).id;
            Atom predLinkGoal = getAtom(predLinkGoalId);
            double utilityGoal = 0.0;
            if (predLinkGoal != null) {
                utilityGoal = predLinkGoal.tv.strength * predLinkGoal.tv.getConfidence();
            }

            // Check general utility: Action -> GoodAction
            String utilityLinkId = new Link(Link.LinkType.INHERITANCE, Arrays.asList(actionId, goodActionConcept.id), TruthValue.DEFAULT).id;
            Atom utilityLink = getAtom(utilityLinkId);
            double utilityGood = 0.0;
            if (utilityLink != null) {
                utilityGood = utilityLink.tv.strength * utilityLink.tv.getConfidence();
            }

            // Combine utilities (e.g., weighted average or max)
            double combinedUtility = Math.max(utilityGoal, utilityGood * 0.5); // Prioritize direct goal prediction

            if (combinedUtility > MIN_CONFIDENCE_FOR_ACTION) { // Use confidence threshold
                 actionUtilities.put(actionId, combinedUtility);
                 // System.out.println("  Action " + actionName + " utility: " + combinedUtility);
            }
        }

        // Choose best action based on utility
        return actionUtilities.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null); // Return null if no action meets criteria
    }


    /** Learns from the outcome of the last action using temporal links. */
    private void learnFromExperience(String prevStateId, String actionId, String currStateId, double reward) {
        if (prevStateId == null || actionId == null || currStateId == null) {
            // System.out.println("Learning: Skipping (missing state/action info)");
            return;
        }
        Atom prevAtom = getAtom(prevStateId);
        Atom actionAtom = getAtom(actionId);
        Atom currAtom = getAtom(currStateId);
        if (prevAtom == null || actionAtom == null || currAtom == null) {
             System.err.println("Learning: Skipping (Atoms not found for " + prevStateId + ", " + actionId + ", " + currStateId + ")");
             return;
        }

        // System.out.println("Learning: Prev=" + prevAtom.shortId() + ", Act=" + actionAtom.shortId() + ", Curr=" + currAtom.shortId() + ", Reward=" + reward);

        // 1. Learn State Transition: PredictiveImplication(Action@PrevState, CurrentState)
        // We need a way to represent Action@PrevState. Use SequentialAnd(PrevState, Action)?
        // Simpler: PredictiveImplication(Action, CurrentState) - assumes action context is implicit or less important
        // Better: PredictiveImplication(PrevState, CurrentState) IF Action was taken? Needs context link.
        // Let's use PredictiveImplication(Action, CurrentState) and rely on revision over time.
        // Evidence is strong because it was directly observed.
        TruthValue transitionTV = new TruthValue(1.0, 1.0); // Observed transition
        double transitionSTI = 0.5; // Moderate initial importance
        // Add link: Action -> CurrentState (Predictive)
        addLink(Link.LinkType.PREDICTIVE_IMPLICATION, transitionTV, transitionSTI, actionId, currStateId);
        // Optionally add: PrevState -> CurrentState (Predictive, weaker evidence as action is missing)
        // addLink(Link.LinkType.PREDICTIVE_IMPLICATION, new TruthValue(0.8, 0.5), transitionSTI * 0.5, prevStateId, currStateId);


        // 2. Learn Reward Association: PredictiveImplication(Action, Reward) and PredictiveImplication(CurrentState, Reward)
        if (reward != 0.0) { // Learn both positive and negative reward associations
            Node rewardNode = getOrCreateNode("RewardSignal", TruthValue.DEFAULT, 0.8); // General reward concept
            // Create specific reward instance Atom? Or just use the signal? Use signal for simplicity.
            // Update reward node based on observation
            rewardNode.tv = rewardNode.tv.merge(new TruthValue(reward > 0 ? 1.0 : 0.0, 1.0)); // Observed reward value
            addAtom(rewardNode);


            TruthValue rewardTV = new TruthValue(reward > 0 ? 1.0 : 0.0, 1.0); // Strength reflects reward sign
            double rewardSTI = 0.6 + Math.abs(reward) * 0.4; // Higher importance for stronger rewards

            // Link Action -> Reward
            addLink(Link.LinkType.PREDICTIVE_IMPLICATION, rewardTV, rewardSTI, actionId, rewardNode.id);
            // Link CurrentState -> Reward
            addLink(Link.LinkType.PREDICTIVE_IMPLICATION, rewardTV, rewardSTI, currStateId, rewardNode.id);

            // System.out.println("  Learned Reward: " + actionAtom.shortId() + " -> Reward(" + reward + "), " + currAtom.shortId() + " -> Reward(" + reward + ")");

            // 3. Reflective Learning: Update Action Utility (Action -> GoodAction)
            Node goodActionNode = getOrCreateNode("GoodAction", TruthValue.DEFAULT, 0.7); // Reflective concept
            // Utility strength based on reward, low initial count
            TruthValue utilityTV = new TruthValue(reward > 0 ? 1.0 : 0.0, 0.5); // Low count for single observation
            Link utilityLink = addLink(Link.LinkType.INHERITANCE, utilityTV, rewardSTI, actionId, goodActionNode.id);
            // System.out.println("  Updated Utility: " + actionAtom.shortId() + " -> GoodAction " + utilityLink.tv);

        } else {
             // Optionally learn non-reward explicitly if needed (e.g., Action -> NotReward)
        }
    }

    // --- Utility Methods ---

    /** Returns a stream of all Atoms in the AtomSpace. */
    public Stream<Atom> getAtomsStream() {
        return atomSpace.values().stream();
    }

    /** Prints the current state of the AtomSpace (optional, for debugging). */
    public void printAtomSpace(int limit) {
        System.out.println("\n--- AtomSpace (" + atomSpace.size() + " atoms) ---");
        getAtomsStream()
            .sorted(Comparator.comparingDouble(Atom::getSTI).reversed())
            .limit(limit)
            .forEach(System.out::println);
        System.out.println("--- End AtomSpace ---");
    }


    // --- Nested Classes: Atoms, TruthValue, TimeValue ---

    /** Base class for all knowledge elements (Nodes and Links). */
    public abstract static class Atom {
        public final String id; // Unique identifier
        public TruthValue tv;
        public volatile double sti; // Short-Term Importance (activation)
        public volatile double lti; // Long-Term Importance (structural relevance)
        public volatile long lastAccessedTime;
        public TimeValue time; // Optional temporal information (start/end/duration)

        protected Atom(String id, TruthValue tv, double initialSTI, TimeValue time) {
            this.id = id;
            this.tv = tv != null ? tv : TruthValue.DEFAULT;
            this.sti = initialSTI;
            this.lti = initialSTI / 10.0; // LTI starts lower
            this.time = time;
            touch();
        }

        /** Update last accessed time and potentially boost STI slightly. */
        public void touch() {
            this.lastAccessedTime = System.currentTimeMillis();
            // Small base boost on any access? Or rely on explicit boosts?
            // updateImportance(0.01); // Optional small boost
        }

        /** Boosts STI and transfers some boost to LTI. */
        public void updateImportance(double boost) {
            this.sti = Math.min(1.0, this.sti + boost); // Cap STI at 1.0
            // Transfer some STI gain to LTI
            this.lti = Math.min(1.0, this.lti + boost * 0.1); // LTI increases slower, capped at 1.0
        }

        /** Decays STI and LTI over time. */
        public void decayImportance(double stiDecayRate, double ltiDecayRate) {
            this.sti *= (1.0 - stiDecayRate);
            this.lti *= (1.0 - ltiDecayRate);
            // Ensure non-negative
            this.sti = Math.max(0.0, this.sti);
            this.lti = Math.max(0.0, this.lti);
        }

        public double getSTI() { return sti; }
        public double getLTI() { return lti; }

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
            return String.format("%s %s (STI=%.3f, LTI=%.3f)%s",
                    shortId(), tv, sti, lti, (time != null ? " " + time : ""));
        }

        /** Generates the canonical ID for the Atom based on its content. */
        protected abstract String generateId();

        /** Provides a shorter representation of the ID for logging. */
        public String shortId() {
            return id; // Default implementation
        }

        /** Factory method to create a shell Atom based on ID parsing (best effort). */
        public static Atom createShell(String id) {
            if (id == null) return null;
            if (id.startsWith("Node(")) {
                String name = id.substring(5, id.length() - 1);
                return new Node(name, TruthValue.DEFAULT);
            } else if (id.contains("[") && id.contains("]")) { // Likely a Link
                try {
                    String typeStr = id.substring(0, id.indexOf('['));
                    Link.LinkType type = Link.LinkType.valueOf(typeStr);
                    // Cannot reconstruct targets easily without parsing the list string
                    // Return a generic Link shell
                    return new Link(type, Collections.emptyList(), TruthValue.DEFAULT) {
                         @Override public String generateId() { return id; } // Use original ID
                         @Override public String shortId() { return id; }
                    };
                } catch (Exception e) { /* Ignore parsing errors */ }
            }
            // Fallback: create a generic Atom shell
            return new Atom(id, TruthValue.DEFAULT, 0, null) {
                @Override protected String generateId() { return id; }
                 @Override public String shortId() { return id; }
            };
        }
    }

    /** Represents concepts, entities, constants, or variables. */
    public static class Node extends Atom {
        public final String name; // Human-readable name

        public Node(String name, TruthValue tv) {
            this(name, tv, 0.1, null); // Default initial importance and no time
        }

        public Node(String name, TruthValue tv, double initialSTI, TimeValue time) {
            super(generateIdFromName(name), tv, initialSTI, time);
            this.name = name;
        }

        @Override
        protected String generateId() {
            return generateIdFromName(name);
        }

        public static String generateIdFromName(String name) {
             return "Node(" + name + ")";
        }

        @Override
        public String shortId() {
            return name; // Use name for shorter representation
        }
         @Override
        public String toString() {
            // Override for cleaner node representation
            return String.format("%s %s (STI=%.3f, LTI=%.3f)%s",
                    name, tv, sti, lti, (time != null ? " " + time : ""));
        }
    }

    /** Represents variables for use in higher-order logic constructs. */
    public static class VariableNode extends Node {
        public VariableNode(String name) {
            // Variables typically don't have inherent truth values or importance? Or maybe they do?
            super(name, TruthValue.DEFAULT, 0.05, null); // Low initial importance
        }

        @Override
        protected String generateId() {
            // Distinguish variable node IDs
            return "Var(" + name + ")";
        }
         @Override
        public String toString() {
            return String.format("$%s (STI=%.3f, LTI=%.3f)", name, sti, lti); // Simple representation
        }
    }


    /** Represents relationships between Atoms. */
    public static class Link extends Atom {
        public final LinkType type;
        public final List<String> targets; // Ordered list of target Atom IDs

        public Link(LinkType type, List<String> targets, TruthValue tv) {
           this(type, targets, tv, 0.1, null); // Default initial importance and no time
        }

        public Link(LinkType type, List<String> targets, TruthValue tv, double initialSTI, TimeValue time) {
            // ID generated after fields are set
            super(null, tv, initialSTI, time);
            this.type = type;
            // Preserve order for types where it matters (Sequence, Implication)
            // Sort only for symmetric types if needed (Similarity, And, Or)? For simplicity, keep order.
            this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
            // Assign final ID
            ((Atom) this).id = generateId();
        }

        @Override
        protected String generateId() {
            // Canonical ID based on type and ordered targets
            return type + targets.toString();
        }

        @Override
        public String shortId() {
            // Provide a more readable short ID, e.g., Inheritance(Cat, Mammal)
            List<String> shortTargets = targets.stream()
                .map(id -> {
                    // Attempt to get a shorter name if possible (e.g., Node name)
                    if (id.startsWith("Node(")) return id.substring(5, id.length() - 1);
                    if (id.startsWith("Var(")) return "$" + id.substring(4, id.length() - 1);
                    // Fallback to partial ID or full ID if complex
                    return id.length() > 20 ? id.substring(0, 10) + "..." + id.substring(id.length() - 10) : id;
                })
                .collect(Collectors.toList());
            return type + shortTargets.toString();
        }

        public enum LinkType {
            // Core Types
            INHERITANCE,        // Probabilistic Implication P(Target|Source) - A -> B
            SIMILARITY,         // Symmetric Association A <-> B
            EVALUATION,         // Predicate Application: Predicate(Arg1, Arg2...)
            EXECUTION,          // Represents schema execution output: Schema(Args...) -> Output (less used now)
            MEMBER,             // Set Membership: Member(Element, Set)
            LIST,               // Ordered list container (used internally for targets)

            // Logical Connectives
            AND, OR, NOT,       // Standard logical operators
            EQUIVALENCE,        // Strong bidirectional implication

            // Temporal Types (Event Calculus inspired)
            INITIATES,          // Event/Action initiates Fluent/State
            TERMINATES,         // Event/Action terminates Fluent/State
            HOLDS_AT,           // Fluent/State holds at a specific TimePoint
            HOLDS_THROUGHOUT,   // Fluent/State holds during a TimeInterval
            PREDICTIVE_IMPLICATION, // Temporal Implication: If A happens, B likely happens later (A => B over time T)
            EVENTUAL_PREDICTIVE_IMPLICATION, // If A persists, B will eventually happen
            SEQUENTIAL_AND,     // Temporal Sequence: A then B then C...
            SIMULTANEOUS_AND,   // Parallel Occurrence: A and B happen concurrently
            // Could add SimOR, DisjointSeqAND etc. as needed

            // Higher-Order Logic / Variables
            FOR_ALL,            // Universal Quantifier: ForAll(Var, Scope)
            EXISTS,             // Existential Quantifier: Exists(Var, Scope)
            VARIABLE_SCOPE,     // Defines scope for variables (less common)

            // Agent/Internal Types (Examples)
            GOAL,               // Represents an agent goal state
            ACTION_UTILITY,     // Link representing learned utility of an action (e.g., Action -> GoodAction)
            STATE_REPRESENTATION // Link combining features into a state (e.g., AND(FeatureA, FeatureB))
        }
    }

    /** Represents uncertain truth: strength and count (evidence amount). */
    public static class TruthValue {
        public static final TruthValue DEFAULT = new TruthValue(0.5, 0.0); // Max ignorance
        public static final TruthValue TRUE = new TruthValue(1.0, 10.0); // Strong True (example count)
        public static final TruthValue FALSE = new TruthValue(0.0, 10.0); // Strong False (example count)

        public final double strength; // [0, 1], probability-like belief strength
        public final double count;    // >= 0, amount of evidence supporting this strength

        public TruthValue(double strength, double count) {
            this.strength = Math.max(0.0, Math.min(1.0, strength));
            this.count = Math.max(0.0, count);
        }

        /** Calculates confidence based on count. c = n / (n + k) */
        public double getConfidence() {
            return count / (count + DEFAULT_EVIDENCE_SENSITIVITY_K);
        }

        /**
         * Merges this TV with another (Revision). Weighted average based on counts.
         * Assumes evidence pertains to the same proposition.
         */
        public TruthValue merge(TruthValue other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;

            double totalCount = this.count + other.count;
            double mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new TruthValue(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("<s=%.3f, c=%.2f, w=%.3f>", strength, count, getConfidence());
        }
    }

    /** Represents temporal information (point or interval). */
    public static class TimeValue {
        public final long startTime; // Inclusive start time (e.g., milliseconds since epoch or relative step)
        public final long endTime;   // Inclusive end time (equals startTime for a point)

        // Constructor for a time point
        public TimeValue(long timePoint) {
            this.startTime = timePoint;
            this.endTime = timePoint;
        }

        // Constructor for a time interval
        public TimeValue(long startTime, long endTime) {
            this.startTime = Math.min(startTime, endTime);
            this.endTime = Math.max(startTime, endTime);
        }

        public boolean isPoint() {
            return startTime == endTime;
        }

        public long getDuration() {
            return endTime - startTime;
        }

        /** Simple addition for sequencing delays (needs refinement for overlaps etc.) */
        public TimeValue add(TimeValue other) {
             if (other == null) return this;
             // If adding two points, result is point at sum? Ambiguous.
             // If adding intervals/durations? Assume adding delays.
             // Simplistic: add durations, keep start time of first.
             long newEndTime = this.endTime + other.getDuration();
             return new TimeValue(this.startTime, newEndTime);
        }


        @Override
        public String toString() {
            if (isPoint()) {
                return "[T=" + startTime + "]";
            } else {
                return "[T=" + startTime + "-" + endTime + "]";
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TimeValue timeValue = (TimeValue) o;
            return startTime == timeValue.startTime && endTime == timeValue.endTime;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startTime, endTime);
        }
    }


    // --- Environment Interaction Abstraction ---

    /** Interface for environments the EPLN agent can interact with. */
    public interface Environment {
        /** Get the current perceivable state/features. */
        Map<String, Object> getCurrentPercepts();

        /** Get the reward obtained since the last action. */
        double getReward();

        /** Perform an action specified by its name/command. */
        void performAction(String actionCommand);

        /** List available action names/commands in the current state. */
        List<String> getAvailableActions();

        /** Check if the environment simulation should terminate. */
        boolean isTerminated();
    }

    /** Translates raw environment percepts into PLN Atoms. */
    public interface PerceptionTranslator {
        /**
         * Processes percepts and updates the EPLN AtomSpace, returning the ID
         * of the Atom representing the current composite state.
         * @param percepts Raw data from the environment.
         * @return The ID of the Atom representing the current state, or null on failure.
         */
        String translatePercepts(Map<String, Object> percepts);
    }

    /** Translates PLN Action Atoms into environment commands. */
    public interface ActionTranslator {
        String ACTION_PREFIX = "Action_"; // Convention for action node names

        /**
         * Converts a PLN Action Atom ID into a command string for the environment.
         * @param actionAtomId The ID of the selected Action Atom (e.g., "Node(Action_MoveForward)").
         * @return The corresponding command string (e.g., "MoveForward"), or null on failure.
         */
        String translateAction(String actionAtomId);

        /**
         * Gets the canonical Atom ID for a given action name. Creates the Node if needed.
         * @param actionName The name of the action (e.g., "MoveForward").
         * @return The Atom ID (e.g., "Node(Action_MoveForward)").
         */
        String getActionAtomId(String actionName);
    }

    // --- Default Translators (Simple Example) ---

    /** Default: Creates Nodes for features and combines them with AND. */
    public class DefaultPerceptionTranslator implements PerceptionTranslator {
        private final EnhancedPLN pln;
        private static final String STATE_PREFIX = "StateConcept_";

        public DefaultPerceptionTranslator(EnhancedPLN pln) { this.pln = pln; }

        @Override
        public String translatePercepts(Map<String, Object> percepts) {
            List<String> featureAtomIds = new ArrayList<>();
            long currentTime = System.currentTimeMillis(); // Or use environment time if available

            for (Map.Entry<String, Object> entry : percepts.entrySet()) {
                String featureName = entry.getKey();
                Object value = entry.getValue();
                TruthValue featureTV;
                // Simple value mapping (extend as needed)
                if (value instanceof Boolean) {
                    featureTV = ((Boolean) value) ? TruthValue.TRUE : TruthValue.FALSE;
                } else if (value instanceof Number) {
                    double numValue = ((Number) value).doubleValue();
                    // Map number to strength [0,1], assume high confidence for direct perception
                    featureTV = new TruthValue(Math.max(0.0, Math.min(1.0, numValue)), 5.0);
                } else {
                    // Treat other types as present/true if not null
                    featureTV = (value != null) ? TruthValue.TRUE : TruthValue.FALSE;
                }

                Node featureNode = pln.getOrCreateNode(featureName, featureTV, 0.3); // Create/update feature node
                featureNode.time = new TimeValue(currentTime); // Mark perception time
                pln.addAtom(featureNode); // Ensure it's added/revised
                if (featureTV.strength > 0.5 && featureTV.getConfidence() > 0.1) { // Only include reasonably true features in state
                    featureAtomIds.add(featureNode.id);
                }
            }

            if (featureAtomIds.isEmpty()) {
                 Node nullState = pln.getOrCreateNode(STATE_PREFIX + "Null", TruthValue.TRUE, 0.3);
                 nullState.time = new TimeValue(currentTime);
                 pln.addAtom(nullState);
                 return nullState.id;
            }

            // Create a composite state Atom (e.g., AND link or specific State Node)
            // Using a specific Node for simplicity here. Sort features for canonical ID.
            Collections.sort(featureAtomIds);
            String stateName = STATE_PREFIX + featureAtomIds.stream()
                                .map(id -> pln.getAtom(id).shortId())
                                .collect(Collectors.joining("_"));

            Node stateNode = pln.getOrCreateNode(stateName, TruthValue.TRUE, 0.5); // High confidence for composite state
            stateNode.time = new TimeValue(currentTime);
            pln.addAtom(stateNode);

            // Optional: Add links Feature -> StateConcept
            for(String featureId : featureAtomIds) {
                pln.addLink(Link.LinkType.MEMBER, TruthValue.TRUE, 0.2, featureId, stateNode.id);
            }

            return stateNode.id;
        }
    }

    /** Default: Assumes Action Atom name maps directly to command. */
    public class DefaultActionTranslator implements ActionTranslator {
         private final EnhancedPLN pln;

         public DefaultActionTranslator(EnhancedPLN pln) { this.pln = pln; }

        @Override
        public String translateAction(String actionAtomId) {
            Atom atom = pln.getAtom(actionAtomId);
            if (atom instanceof Node && atom.id.startsWith(Node.generateIdFromName(ACTION_PREFIX))) {
                // Assumes ID is "Node(Action_CommandName)"
                String nodeName = ((Node) atom).name;
                if (nodeName.startsWith(ACTION_PREFIX)) {
                    return nodeName.substring(ACTION_PREFIX.length());
                }
            }
            System.err.println("DefaultActionTranslator: Cannot extract command from ID: " + actionAtomId);
            return null; // Failed translation
        }

         @Override
         public String getActionAtomId(String actionName) {
              // Creates the action node if it doesn't exist
              Node actionNode = pln.getOrCreateNode(ACTION_PREFIX + actionName, TruthValue.DEFAULT, 0.2);
              return actionNode.id;
         }
    }


    // --- Main Method for Example Usage ---
    public static void main(String[] args) {
        System.out.println("--- Enhanced PLN Demo ---");
        EnhancedPLN epln = new EnhancedPLN();

        // --- Basic Knowledge & Inference ---
        System.out.println("\n1. Basic Knowledge & Inference:");
        Node cat = epln.getOrCreateNode("Cat", new TruthValue(0.1, 10), 0.5);
        Node mammal = epln.getOrCreateNode("Mammal", new TruthValue(0.2, 20), 0.5);
        Node animal = epln.getOrCreateNode("Animal", new TruthValue(0.5, 50), 0.5);
        Node cute = epln.getOrCreateNode("Cute", new TruthValue(0.3, 15), 0.4);

        epln.addLink(Link.LinkType.INHERITANCE, new TruthValue(0.95, 30), 0.7, cat, mammal);
        epln.addLink(Link.LinkType.INHERITANCE, new TruthValue(0.98, 40), 0.7, mammal, animal);
        epln.addLink(Link.LinkType.INHERITANCE, new TruthValue(0.60, 10), 0.5, animal, cute);

        System.out.println("Initial Atoms:");
        epln.getAtomsStream().forEach(System.out::println);

        epln.forwardChain(2); // Run inference

        System.out.println("\nAtoms after Forward Chaining:");
        String catAnimalId = new Link(Link.LinkType.INHERITANCE, Arrays.asList(cat.id, animal.id), TruthValue.DEFAULT).id;
        System.out.println("Cat -> Animal: " + epln.getAtom(catAnimalId));
        String mammalCatId = new Link(Link.LinkType.INHERITANCE, Arrays.asList(mammal.id, cat.id), TruthValue.DEFAULT).id;
        System.out.println("Mammal -> Cat: " + epln.getAtom(mammalCatId));
        String catCuteId = new Link(Link.LinkType.INHERITANCE, Arrays.asList(cat.id, cute.id), TruthValue.DEFAULT).id;
        System.out.println("Cat -> Cute: " + epln.getAtom(catCuteId));


        // --- Temporal Reasoning Example ---
        System.out.println("\n2. Temporal Reasoning:");
        Node lightSwitch = epln.getOrCreateNode("LightSwitch", TruthValue.DEFAULT, 0.5);
        Node lightOn = epln.getOrCreateNode("LightOn", TruthValue.FALSE, 0.5); // Initially off
        Node flipAction = epln.getOrCreateNode(ActionTranslator.ACTION_PREFIX + "FlipSwitch", TruthValue.DEFAULT, 0.6);

        // Flipping the switch initiates LightOn (almost immediately)
        TimeValue shortDelay = new TimeValue(0, 100); // 0-100ms delay
        epln.addLink(Link.LinkType.INITIATES, TruthValue.TRUE, 0.8, flipAction, lightOn).time = shortDelay;
        // Alternative: PredictiveImplication(FlipAction, LightOn)
        epln.addLink(Link.LinkType.PREDICTIVE_IMPLICATION, TruthValue.TRUE, 0.8, flipAction, lightOn).time = shortDelay;

        System.out.println("Initial Light State: " + epln.getAtom(lightOn.id));
        // Simulate flipping the switch (apply Modus Ponens manually for demo)
        System.out.println("Simulating FlipSwitch...");
        epln.modusPonens(flipAction, (Link)epln.getAtom(new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(flipAction.id, lightOn.id), TruthValue.DEFAULT).id));
        System.out.println("Light State after Flip (via inference): " + epln.getAtom(lightOn.id));


        // --- Attention & Forgetting Demo ---
        System.out.println("\n3. Attention & Forgetting (Simulated):");
        System.out.println("Adding low importance atom...");
        Node forgottenNode = epln.getOrCreateNode("TemporaryData", TruthValue.TRUE, 0.0001); // Very low STI
        System.out.println("AtomSpace size before maintenance: " + epln.atomSpace.size());
        // Manually trigger maintenance for demo (normally runs in background)
        epln.performMaintenance();
        System.out.println("AtomSpace size after maintenance: " + epln.atomSpace.size());
        System.out.println("TemporaryData exists: " + (epln.getAtom(forgottenNode.id) != null));


        // --- Agent Simulation ---
        System.out.println("\n4. Agent Simulation (Simple Binary World):");
        SimpleBinaryWorld world = new SimpleBinaryWorld();
        epln.initializeAgent(world, null, null); // Use default translators
        epln.setGoal("StateConcept_IsStateB"); // Goal is to be in State B

        epln.run(15); // Run agent for 15 steps

        System.out.println("\nFinal Agent Knowledge Highlights:");
        String toggleActionId = epln.actionTranslator.getActionAtomId("ToggleState");
        String doNothingActionId = epln.actionTranslator.getActionAtomId("DoNothing");
        String goodActionId = Node.generateIdFromName("GoodAction");
        String rewardSignalId = Node.generateIdFromName("RewardSignal");
        String stateAId = Node.generateIdFromName("StateConcept_IsStateA");
        String stateBId = Node.generateIdFromName("StateConcept_IsStateB");


        System.out.println("Utility (Toggle -> Good): " + epln.getAtom(new Link(Link.LinkType.INHERITANCE, Arrays.asList(toggleActionId, goodActionId), TruthValue.DEFAULT).id));
        System.out.println("Utility (Nothing -> Good): " + epln.getAtom(new Link(Link.LinkType.INHERITANCE, Arrays.asList(doNothingActionId, goodActionId), TruthValue.DEFAULT).id));
        System.out.println("Prediction (Toggle -> Reward): " + epln.getAtom(new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(toggleActionId, rewardSignalId), TruthValue.DEFAULT).id));
        System.out.println("Prediction (StateA -> Reward): " + epln.getAtom(new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(stateAId, rewardSignalId), TruthValue.DEFAULT).id));
        System.out.println("Prediction (StateB -> Reward): " + epln.getAtom(new Link(Link.LinkType.PREDICTIVE_IMPLICATION, Arrays.asList(stateBId, rewardSignalId), TruthValue.DEFAULT).id));

        epln.printAtomSpace(20); // Print top 20 most important atoms

        // --- Shutdown ---
        epln.shutdown();
        System.out.println("\n--- EPLN Demo Finished ---");
    }


     // --- Simple Environment Example Implementation ---
    static class SimpleBinaryWorld implements Environment {
        boolean isStateA = true; // Start in state A
        int steps = 0;
        final int maxSteps = 15;

        @Override
        public Map<String, Object> getCurrentPercepts() {
            Map<String, Object> percepts = new HashMap<>();
            percepts.put("IsStateA", isStateA);
            percepts.put("IsStateB", !isStateA);
            // Could add step count, etc.
            // percepts.put("StepCount", steps);
            return percepts;
        }

        @Override
        public double getReward() {
            // Reward 1.0 for being in state B, 0 otherwise
            return !isStateA ? 1.0 : 0.0;
        }

        @Override
        public void performAction(String actionCommand) {
            steps++;
            System.out.println("  World: Received action '" + actionCommand + "'");
            if ("ToggleState".equals(actionCommand)) {
                isStateA = !isStateA;
                System.out.println("  World: State toggled. Now in " + (isStateA ? "A" : "B"));
            } else if ("DoNothing".equals(actionCommand)) {
                 System.out.println("  World: Did nothing.");
            } else {
                System.out.println("  World: Unknown action '" + actionCommand + "' performed. No change.");
            }
        }

        @Override
        public List<String> getAvailableActions() {
            return Arrays.asList("ToggleState", "DoNothing");
        }

        @Override
        public boolean isTerminated() {
            return steps >= maxSteps;
        }
    }

} // End of EnhancedPLN class