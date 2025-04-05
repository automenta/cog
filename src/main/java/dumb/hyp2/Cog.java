package dumb.hyp2;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * Cog - Cognitive Logic Engine (Synthesized Hyperon-Inspired Iteration)
 * <p>
 * A unified cognitive architecture implementing a Hyperon/MeTTa-like symbolic system.
 * It integrates parsing, pattern matching, rule-based rewriting, probabilistic reasoning,
 * attention mechanisms, and agent interaction within a single, consolidated Java class structure.
 * <p>
 * ## Core Design:
 * - **Atomspace:** Central knowledge store holding immutable `Atom` objects (Symbols, Variables, Expressions, Grounded). Managed by {@link Memory}.
 * - **Unified Representation:** All concepts, relations, rules, procedures, types, states, goals are Atoms.
 * - **MeTTa Syntax:** Uses a LISP-like syntax for representing Atoms textually. Includes a {@link MettaParser} for conversion.
 * - **MeTTa Execution:** An {@link Interp} evaluates expressions by matching against equality (`=`) rules via {@link Unify}.
 * - **Homoiconicity:** Code (MeTTa rules/expressions) *is* data (Atoms), enabling reflection and self-modification.
 * - **Grounded Atoms:** {@link Is} bridges to Java code/data for I/O, math, environment interaction, etc.
 * - **Metadata:** Immutable {@link Value} records (holding {@link Truth}, {@link Pri}, {@link Time}) associated with Atoms via {@link AtomicReference} for atomic updates.
 * - **Probabilistic & Importance Driven:** Truth values handle uncertainty; Importance values (STI/LTI) guide attention and forgetting.
 * - **Agent Framework:** Includes a basic {@link Agent} model demonstrating perception, reasoning (via evaluation), action, and learning (by adding rules).
 * - **Modern Java:** Leverages Records, Sealed Interfaces, Streams, Concurrent Collections, AtomicReference for robustness and conciseness.
 * <p>
 * ## Key Enhancements (Iteration 4):
 * - **MeTTa Parser:** Introduced {@link MettaParser} to parse MeTTa syntax strings into Atoms.
 * - **Bootstrap via MeTTa:** Core rules and symbols are now defined using MeTTa syntax strings, parsed during bootstrap.
 * - **Examples via MeTTa:** The `main` method demonstration uses parsed MeTTa strings extensively.
 * - **Consolidated Helpers:** Atom creation helpers (`S`, `V`, `E`, `G`) remain for programmatic use, alongside parser methods (`parse`, `load`).
 * - **Minor Optimizations:** Refined ID caching, index usage, and forgetting logic.
 *
 * @version 4.1 - Parser Integration
 */
public final class Cog {

    // --- Configuration Constants ---
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0; // k in confidence = count / (count + k)
    private static final double TRUTH_MIN_CONFIDENCE_MATCH = 0.01; // Min confidence for an atom to be considered in matching/relevance
    private static final double TRUTH_REVISION_CONFIDENCE_THRESHOLD = 0.1; // Min confidence diff for revision boost

    private static final double IMPORTANCE_INITIAL_STI = 0.2;
    private static final double IMPORTANCE_INITIAL_LTI_FACTOR = 0.1;
    private static final double IMPORTANCE_STI_DECAY_RATE = 0.08; // Decay per maintenance cycle
    private static final double IMPORTANCE_LTI_DECAY_RATE = 0.008;
    private static final double IMPORTANCE_STI_TO_LTI_RATE = 0.02;
    private static final double IMPORTANCE_BOOST_ON_ACCESS = 0.08;
    private static final double IMPORTANCE_BOOST_ON_REVISION_MAX = 0.5;
    private static final double IMPORTANCE_BOOST_ON_GOAL_FOCUS = 0.95;
    private static final double IMPORTANCE_BOOST_ON_PERCEPTION = 0.75;
    private static final double IMPORTANCE_MIN_FORGET_THRESHOLD = 0.015; // Combined importance threshold
    private static final long FORGETTING_CHECK_INTERVAL_MS = 12000;
    private static final int FORGETTING_MAX_MEM_SIZE_TRIGGER = 18000;
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // %

    private static final int INTERPRETER_DEFAULT_MAX_DEPTH = 15; // Max recursive evaluation depth
    private static final int INTERPRETER_MAX_RESULTS = 50; // Limit non-deterministic results per evaluation step

    private static final double AGENT_DEFAULT_PERCEPTION_COUNT = 8.0;
    private static final double AGENT_LEARNED_RULE_COUNT = 1.5;
    private static final double AGENT_UTILITY_THRESHOLD = 0.1;
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05;

    private static final String VAR_PREFIX = "$"; // MeTTa variable prefix convention
    private static final Set<String> PROTECTED_SYMBOLS = Set.of( // Symbols protected from forgetting
            "=", ":", "->", "Type", "True", "False", "Nil", "Self", "Goal", "State", "Action",
            "match", "eval", "add-atom", "remove-atom", "get-value", "If", "+", "-", "*", "/", // Core grounded ops
            "Number", "Bool", "&self" // Core types and self ref
    );

    // --- Core Symbols (initialized in CoreBootstrap) ---
    // Use static fields for easy access after bootstrap.
    public static /*Sym*/Atom SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_SELF, SYMBOL_NIL; // Represents empty list/result often

    // --- System Components ---
    public final Memory space;
    public final Unify unify;
    public final Agent agent;
    private final Interp interp;
    private final MettaParser parser;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong logicalTime = new AtomicLong(0);

    /** Initializes the Cog engine, including the Atomspace, Interpreter, Agent, Parser, and maintenance scheduler. */
    public Cog() {
        this.space = new Memory(this::getLogicalTime);
        this.unify = new Unify(this.space);
        this.interp = new Interp(this); // Pass Cog reference for access to space, etc.
        this.agent = new Agent(this);
        this.parser = new MettaParser(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "Cog-Maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::performMaintenance, FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        new CoreBootstrap(this).initialize(); // Initialize core symbols, types, grounded atoms via parsing

        System.out.println("Cog (v4.1 Parser Integration) Initialized. Bootstrap Size: " + space.size());
    }

    // --- Main Demonstration ---
    public static void main(String[] args) {
        var cog = new Cog();
        try {
            System.out.println("\n--- Cog Synthesized Test Suite (v4.1) ---");

            // --- [1] Parsing & Atom Basics ---
            printSectionHeader(1, "Parsing & Atom Basics");
            var red = cog.parse("Red");
            var x = cog.parse("$x");
            var lovesFritz = cog.parse("(Loves Self Fritz)");
            var numPi = cog.parse("3.14159"); // Parsed as Grounded<Double>
            var strHello = cog.parse("\"Hello\""); // Parsed as Grounded<String>
            var boolTrue = cog.parse("True"); // Parsed as Symbol("True")

            cog.add(red);
            cog.add(x);
            cog.add(lovesFritz);
            cog.add(numPi);
            cog.add(strHello);

            System.out.println("Parsed Atoms: " + red + ", " + x + ", " + lovesFritz + ", " + numPi + ", " + strHello + ", " + boolTrue);
            System.out.println("Retrieved Fritz Atom: " + cog.space.getAtom(lovesFritz));
            System.out.println("Retrieved Pi Atom by ID: " + cog.space.getAtom(numPi.id()));
            System.out.println("Default Value for Red: " + cog.space.value(red));

            cog.space.updateTruth(red, new Truth(0.9, 5.0));
            cog.space.boost(red, 0.5);
            System.out.println("Updated Value for Red: " + cog.space.value(red));

            cog.space.updateTruth(red, new Truth(0.95, 15.0)); // Merge truth
            System.out.println("Merged Value for Red: " + cog.space.value(red));

            // --- [2] Unification ---
            printSectionHeader(2, "Unification");
            var likes = cog.S("Likes"); // Programmatic creation still useful
            var sam = cog.S("Sam");
            var pizza = cog.S("Pizza");
            var fact1 = cog.parse("(Likes Sam Pizza)");
            var pattern1 = cog.parse("(Likes Sam $w)");
            var pattern2 = cog.parse("(Likes $p Pizza)");
            var pattern3 = cog.parse("(Likes $p $w)");
            var patternFail = cog.parse("(Hates Sam Pizza)");

            cog.add(fact1); // Add the fact to the space

            testUnification(cog, "Simple Match", pattern1, fact1);
            testUnification(cog, "Variable Match 1", pattern2, fact1);
            testUnification(cog, "Variable Match 2", pattern3, fact1);
            testUnification(cog, "Mismatch", patternFail, fact1);
            testUnification(cog, "Occurs Check Fail", x, cog.parse("(Likes $x)"));

            // --- [3] MeTTa Evaluation - Simple Rules (Defined via Parser in Bootstrap) ---
            printSectionHeader(3, "MeTTa Evaluation - Simple Rules (Peano)");
            // Rules for 'add' are now loaded during bootstrap from MeTTa string
            // (= (add Z $n) $n)
            // (= (add (S $m) $n) (S (add $m $n)))
            var exprAdd1 = cog.parse("(add (S Z) (S Z))");
            var exprAdd2 = cog.parse("(add (S (S Z)) (S Z))");

            evaluateAndPrint(cog, "Peano Add 1+1", exprAdd1); // Expect (S (S Z))
            evaluateAndPrint(cog, "Peano Add 2+1", exprAdd2); // Expect (S (S (S Z)))

            // --- [4] MeTTa Evaluation - Grounded Atoms & Control Flow (Defined via Parser in Bootstrap) ---
            printSectionHeader(4, "MeTTa Evaluation - Grounded Atoms & Control Flow");
            // Rules like (= (+ $a $b) (Grounded+ $a $b)) are in bootstrap
            var exprArith1 = cog.parse("(+ 5.0 3.0)");
            evaluateAndPrint(cog, "Arithmetic 5+3", exprArith1); // Expect Grounded<Double:8.0>

            var exprArith2 = cog.parse("(* (+ 2.0 3.0) 4.0)"); // (* (+ 2 3) 4)
            evaluateAndPrint(cog, "Arithmetic (2+3)*4", exprArith2); // Expect Grounded<Double:20.0>

            var exprComp = cog.parse("(> 5.0 3.0)");
            evaluateAndPrint(cog, "Comparison 5 > 3", exprComp); // Expect True

            // If rules are in bootstrap: (= (If True $then $else) $then), (= (If False $then $else) $else)
            var exprIfTrue = cog.parse("(If True ResultA ResultB)");
            evaluateAndPrint(cog, "If True", exprIfTrue); // Expect ResultA

            var exprIfCond = cog.parse("(If (> 5.0 3.0) FiveIsGreater ThreeIsGreater)");
            evaluateAndPrint(cog, "If (5 > 3)", exprIfCond); // Expect FiveIsGreater

            // --- [5] Pattern Matching Query (`match`) ---
            printSectionHeader(5, "Pattern Matching Query");
            cog.load("""
                    (Likes Sam Pizza)
                    (Likes Dean Pizza)
                    (Likes Sam Apples)
                    """);

            var queryPizza = cog.parse("(Likes $p Pizza)");
            System.out.println("Querying: " + queryPizza);
            var pizzaResults = cog.query(queryPizza);
            printQueryResults(pizzaResults); // Expect matches for Sam and Dean

            // Query using the 'match' grounded function
            var matchExpr = cog.parse("(match &self (Likes $p Pizza) $p)"); // Match in default space, return $p
            evaluateAndPrint(cog, "Grounded Match Query", matchExpr); // Expect Grounded<List:[Sam, Dean]>

            // --- [6] Truth & Importance Values ---
            printSectionHeader(6, "Truth & Importance Values");
            cog.load("(: Penguin Bird)");
            var penguinFlies = cog.add(cog.parse("(Flies Penguin)"));

            cog.space.updateTruth(penguinFlies, new Truth(0.1, 20.0)); // Penguins likely don't fly
            cog.space.boost(penguinFlies, 0.7);
            System.out.println("Penguin Flies Value: " + cog.space.value(penguinFlies));

            var birdFlies = cog.add(cog.parse("(Flies Bird)")); // Generic bird likely flies
            cog.space.updateTruth(birdFlies, new Truth(0.9, 15.0));
            System.out.println("Bird Flies Value: " + cog.space.value(birdFlies));

            // --- [7] Forgetting ---
            printSectionHeader(7, "Forgetting");
            var protectedAtom = cog.S("ImportantConcept"); // Still useful to get ref programmatically
            cog.add(protectedAtom); // Ensure it exists
            cog.space.boost(protectedAtom, 1.0); // Make very important initially
            System.out.println("Protected atom: " + protectedAtom + ", Initial Value: " + cog.space.value(protectedAtom));

            System.out.println("Adding 100 low-importance temporary atoms...");
            for (var i = 0; i < 100; i++) {
                var temp = cog.S("Temp_" + i + "_" + UUID.randomUUID().toString().substring(0, 4)); // Unique name
                cog.add(temp);
                cog.space.updateValue(temp, v -> v.withImportance(new Pri(0.001, 0.0001))); // Very low importance
            }
            System.out.println("Atomspace size before wait: " + cog.space.size());
            System.out.println("Waiting for maintenance cycle (approx " + FORGETTING_CHECK_INTERVAL_MS / 1000 + "s)...");
            Thread.sleep(FORGETTING_CHECK_INTERVAL_MS + 2000); // Wait longer than interval

            var sizeAfter = cog.space.size();
            System.out.println("Atomspace size after wait: " + sizeAfter);
            var protectedCheck = cog.space.getAtom(protectedAtom); // Check if protected atom still exists
            System.out.println("Protected atom '" + protectedAtom/*.name()*/ + "' still exists: " + protectedCheck.isPresent());
            System.out.println("Protected atom value after wait: " + protectedCheck.flatMap(cog.space::value)); // Importance should have decayed slightly

            // --- [8] Agent Simulation ---
            printSectionHeader(8, "Agent Simulation");
            var env = new SimpleGame(cog);
            var agentGoal = cog.parse("(State Self AtGoal)"); // Goal defined via MeTTa
            cog.runAgent(env, agentGoal, 10); // Run for a few steps

            // Query learned utilities after agent run
            System.out.println("\nQuerying learned utilities:");
            var utilQuery = cog.parse("(Utility $action)");
            var utilResults = cog.query(utilQuery); // Query the utility values directly
            printQueryResults(utilResults);

            // --- [9] Metaprogramming (Adding rules via MeTTa 'add-atom') ---
            printSectionHeader(9, "Metaprogramming");
            // Define the rule to add as a MeTTa string
            var ruleToAddString = "(= (NewPredicate ConceptA) ResultValue)";
            var ruleToAddAtom = cog.parse(ruleToAddString); // Parse the rule

            // Use the grounded 'add-atom' function to add the rule via evaluation
            // Need to quote the atom to prevent it from being evaluated before add-atom call
            // Option 1: Add programmatically (simpler for this case)
            // var addRuleExpr = cog.E(cog.S("add-atom"), ruleToAddAtom);
            // Option 2: Try parsing an expression containing the rule atom (needs careful quoting or representation)
            // Let's use the programmatic approach for clarity here.
            // If we wanted to parse it, it might look like: (add-atom '(= (NewPredicate ConceptA) ResultValue))
            // But we don't have a standard quote mechanism yet in the parser/interpreter.
            var addAtomSym = cog.S("add-atom");
            var addRuleExpr = cog.E(addAtomSym, ruleToAddAtom); // Create expression programmatically

            System.out.println("Evaluating meta-expression to add rule: " + addRuleExpr);
            evaluateAndPrint(cog, "Add Rule Meta", addRuleExpr); // Should return the added rule Atom

            // Verify the rule works by evaluating the new predicate
            var testExpr = cog.parse("(NewPredicate ConceptA)");
            System.out.println("\nEvaluating new predicate after meta-add: " + testExpr);
            evaluateAndPrint(cog, "Test New Rule", testExpr); // Expect [ResultValue]

            // --- [10] Loading Multiple Atoms ---
            printSectionHeader(10, "Loading Multiple Atoms");
            String multiAtomData = """
                ; Some comments
                (fact One)
                (: Two Number) ; Type declaration
                (= (greet $name) (Concat "Hello, " $name "!"))
                """;
            System.out.println("Loading MeTTa block:\n" + multiAtomData);
            List<Atom> loaded = cog.load(multiAtomData);
            System.out.println("Loaded " + loaded.size() + " atoms:");
            loaded.forEach(a -> System.out.println(" - " + a + " | Value: " + cog.space.value(a)));
            System.out.println("Querying (fact $x):");
            printQueryResults(cog.query(cog.parse("(fact $x)")));

        } catch (Exception e) {
            System.err.println("\n--- ERROR during demonstration ---");
            e.printStackTrace();
        } finally {
            cog.shutdown();
        }
        System.out.println("\n--- Cog Synthesized Test Suite Finished ---");
    }

    // --- Helper Methods for Testing/Demo ---
    private static void printSectionHeader(int sectionNum, String title) {
        System.out.printf("\n--- [%d] %s ---\n", sectionNum, title);
    }

    private static void testUnification(Cog cog, String testName, Atom pattern, Atom instance) {
        System.out.print("Unifying (" + testName + "): " + pattern + " with " + instance + " -> ");
        var result = cog.unify.unify(pattern, instance, Bind.EMPTY);
        System.out.println(result.isPresent() ? "Success: " + result.get() : "Failure");
    }

    private static void evaluateAndPrint(Cog cog, String testName, Atom expression) {
        System.out.println("Evaluating (" + testName + "): " + expression);
        var results = cog.eval(expression); // Use default depth
        System.out.print(" -> Results: [");
        System.out.print(results.stream().map(Atom::toString).collect(Collectors.joining(", ")));
        System.out.println("]");
        // Optionally print truth/importance
        // results.forEach(r -> System.out.println("      Value: " + cog.space.value(r)));
    }

    private static void printQueryResults(List<Answer> results) {
        if (results.isEmpty()) {
            System.out.println(" -> No matches found.");
        } else {
            results.forEach(qr -> System.out.println(" -> Match: " + qr.resultAtom() + " | Bindings: " + qr.bind));
        }
    }

    // --- Public API ---

    /** Parses a single MeTTa expression string into an Atom. */
    public Atom parse(String metta) {
        return parser.parse(metta);
    }

    /** Parses a string containing multiple MeTTa expressions (separated by whitespace/newlines) and adds them to the Atomspace. Comments (starting with ';') are ignored. */
    public List<Atom> load(String mettaCode) {
        return parser.parseAll(mettaCode).stream()
                .map(this::add) // Add each parsed atom to the space
                .toList();
    }

    /** Creates/retrieves a Symbol Atom. */
    public Atom S(String name) {
        return space.sym(name);
    }

    /** Creates/retrieves a Variable Atom. */
    public Var V(String name) {
        return space.var(name);
    }

    /** Creates/retrieves an Expression Atom. */
    public Expr E(Atom... children) {
        return space.expr(List.of(children));
    }

    /** Creates/retrieves an Expression Atom. */
    public Expr E(List<Atom> children) {
        return space.expr(children);
    }

    /** Creates/retrieves a Grounded Atom wrapping a non-executable Java value. */
    public <T> Is<T> G(T value) {
        return space.is(value);
    }

    /** Creates/retrieves a Grounded Atom wrapping an executable Java function, identified by name. */
    public Is<Function<List<Atom>, Optional<Atom>>> G(String name, Function<List<Atom>, Optional<Atom>> exec) {
        return space.isExe(name, exec);
    }

    /** Returns the current logical time of the system. */
    public long getLogicalTime() {
        return logicalTime.get();
    }

    /** Increments and returns the logical time. Typically called by the agent or simulation loop. */
    public long tick() {
        return logicalTime.incrementAndGet();
    }

    /** Adds an Atom to the Atomspace, returning the canonical instance. */
    public <A extends Atom> A add(A atom) {
        return space.add(atom);
    }

    /** Convenience method to add an equality rule `(= premise conclusion)`. */
    public Expr EQ(Atom premise, Atom conclusion) {
        return add(E(SYMBOL_EQ, premise, conclusion));
    }

    /** Convenience method to add a type assertion `(: instance type)`. */
    public Expr TYPE(Atom instance, Atom type) {
        return add(E(SYMBOL_COLON, instance, type));
    }

    /** Evaluates an expression using the MeTTa interpreter with default depth. */
    public List<Atom> eval(Atom expr) {
        return interp.eval(expr, INTERPRETER_DEFAULT_MAX_DEPTH);
    }

    /** Evaluates an expression using the MeTTa interpreter with specified max depth. */
    public List<Atom> eval(Atom expr, int maxDepth) {
        return interp.eval(expr, maxDepth);
    }

    /** Convenience method to get the single 'best' result (highest confidence * strength). */
    public Optional<Atom> evalBest(Atom expr) {
        return eval(expr).stream().max(Comparator.comparingDouble(a -> space.valueOrDefault(a).getWeightedTruth()));
    }

    /** Performs a pattern matching query against the Atomspace. */
    public List<Answer> query(Atom pattern) {
        return space.query(pattern);
    }

    /** Runs the agent loop within a specified environment and goal. */
    public void runAgent(Game env, Atom goal, int maxCycles) {
        agent.run(env, goal, maxCycles);
    }

    /** Shuts down the maintenance scheduler gracefully. */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Cog scheduler shut down.");
    }

    /** Performs periodic maintenance (decay and forgetting). */
    private void performMaintenance() {
        space.decayAndForget();
    }

    // --- Core Data Structures ---

    /** Enumeration of the different types of Atoms. */
    public enum AtomType {SYMBOL, VARIABLE, EXPRESSION, GROUNDED}

    /**
     * Base sealed interface for all elements in the Atomspace. Atoms are structurally immutable.
     * Equality and hashcode are based on the structural identifier {@link #id()}.
     */
    public sealed interface Atom {
        /** A unique string identifier representing the structure of the Atom. */
        String id();

        /** Returns the type of this Atom. */
        AtomType type();

        // Convenience casting methods
        default Sym asSymbol() { return (Sym) this; }
        default Var asVariable() { return (Var) this; }
        default Expr asExpression() { return (Expr) this; }
        default Is<?> asGrounded() { return (Is<?>) this; }

        @Override boolean equals(Object other);
        @Override int hashCode();
        @Override String toString();
    }

    /** Represents named constants, interned for efficiency via {@link Memory}. */
    public record Sym(String name) implements Atom {
        // Keep a static cache for interning symbols across different Cog instances if needed,
        // but primary management is within Memory per Cog instance.
        private static final ConcurrentMap<String, Sym> SYMBOL_CACHE = new ConcurrentHashMap<>();

        public static Sym of(String name) {
            // Note: Memory.add ensures canonical instance *within* the space.
            // This cache is more for potential static sharing or symbol creation outside a space.
            return SYMBOL_CACHE.computeIfAbsent(name, Sym::new);
        }

        @Override public String id() { return name; }
        @Override public AtomType type() { return AtomType.SYMBOL; }
        @Override public String toString() { return name; }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Sym s && name.equals(s.name));
        }
    }

    /** Represents a variable, distinguished by the '$' prefix in its ID. */
    public record Var(String name) implements Atom {
        public Var {
            if (name.startsWith(VAR_PREFIX))
                throw new IllegalArgumentException("Variable name should not include prefix '" + VAR_PREFIX + "'");
        }
        @Override public String id() { return VAR_PREFIX + name; }
        @Override public AtomType type() { return AtomType.VARIABLE; }
        @Override public String toString() { return id(); }
        @Override public int hashCode() { return id().hashCode(); }
        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Var v && id().equals(v.id()));
        }
    }

    /** Represents a composite expression (MeTTa lists/trees). Structural equality uses cached ID. */
    public record Expr(String computedId, List<Atom> children) implements Atom {
        // Cache IDs for performance - relies on children being canonical Atoms from Memory
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>();

        /** Creates an Expression, ensuring children list is immutable and calculating/caching the structural ID. */
        public Expr(List<Atom> inputChildren) {
            this(idCache.computeIfAbsent(List.copyOf(inputChildren), Expr::computeIdInternal), List.copyOf(inputChildren));
        }

        private static String computeIdInternal(List<Atom> childList) {
            return "(" + childList.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")";
        }

        @Override public String id() { return computedId; }
        @Override public AtomType type() { return AtomType.EXPRESSION; }

        /** Returns the first element (head) of the expression, or null if empty. */
        public @Nullable Atom head() { return children.isEmpty() ? null : children.getFirst(); }
        /** Returns a list containing all elements except the first (tail). Returns empty list if empty. */
        public List<Atom> tail() { return children.isEmpty() ? emptyList() : children.subList(1, children.size()); }

        @Override public String toString() { return id(); } // Use ID for concise representation

        @Override public int hashCode() { return computedId.hashCode(); }
        @Override public boolean equals(Object o) {
            // Efficient check using cached ID, assumes IDs are unique structural hashes.
            return this == o || (o instanceof Expr ea && computedId.equals(ea.computedId));
        }
    }

    /** Wraps external Java values or executable logic. Equality uses the provided ID. */
    public record Is<T>(String id, @Nullable T value, @Nullable Function<List<Atom>, Optional<Atom>> executor) implements Atom {

        /** Creates a Grounded Atom for a non-executable value, deriving an ID automatically. */
        public Is(T value) { this(deriveId(value), value, null); }

        /** Creates a Grounded Atom for a non-executable value with a specific ID. */
        public Is(String id, T value) { this(id, value, null); }

        /** Creates a Grounded Atom for executable logic, using its name as the ID. */
        public Is(String name, Function<List<Atom>, Optional<Atom>> executor) { this(name, null, executor); }

        // Helper to create a default ID based on type and value hash
        private static <T> String deriveId(T value) {
            if (value == null) return "Gnd<null:null>";
            // Simple types often have good toString(), use hash for complex/long ones
            String valStr = value.toString();
            String typeName = value.getClass().getSimpleName();
            String valuePart = (value instanceof String || value instanceof Number || value instanceof Boolean || valStr.length() < 30)
                    ? valStr : String.valueOf(valStr.hashCode());
            return "Gnd<" + typeName + ":" + valuePart + ">";
        }

        @Override public AtomType type() { return AtomType.GROUNDED; }
        public boolean isExecutable() { return executor != null; }
        public Optional<Atom> execute(List<Atom> args) { return isExecutable() ? executor.apply(args) : Optional.empty(); }

        @Override public String toString() {
            if (isExecutable()) return "GndFunc<" + id + ">";
            var valueStr = String.valueOf(value);
            // Provide slightly more informative default toString for common grounded types
            if (value instanceof String) return "\"" + valueStr + "\"";
            if (value instanceof Number || value instanceof Boolean) return valueStr;
            // Fallback to detailed representation
            return "Gnd<" + (value != null ? value.getClass().getSimpleName() : "null") + ":"
                    + (valueStr.length() > 30 ? valueStr.substring(0, 27) + "..." : valueStr)
                    + ">"; // Shorten long value strings
        }

        @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) {
            // ID uniquely identifies the grounded concept/value/function.
            return this == o || (o instanceof Is<?> ga && id.equals(ga.id));
        }
    }

    // --- Metadata Records ---

    /** Immutable record holding Truth, Importance, and Time metadata. */
    public record Value(Truth truth, Pri importance, Time time) {
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);

        // Factory methods for creating updated Value instances
        public Value withTruth(Truth newTruth) { return new Value(newTruth, importance, time); }
        public Value withImportance(Pri newImportance) { return new Value(truth, newImportance, time); }
        public Value withTime(Time newTime) { return new Value(truth, importance, newTime); }
        public Value updateTime(long now) { return new Value(truth, importance, new Time(now)); }

        /** Boosts importance and updates access time. Returns a new Value instance. */
        public Value boost(double boostAmount, long now) {
            return this.withImportance(importance.boost(boostAmount)).updateTime(now);
        }

        /** Decays importance. Returns a new Value instance. Does not update access time. */
        public Value decay(long now) { // 'now' available for future complex decay models
            return this.withImportance(importance.decay());
        }

        /** Calculates combined current importance modulated by confidence and recency. */
        public double getCurrentImportance(long now) {
            double timeSinceAccess = Math.max(0, now - time.time());
            // Recency factor: decays exponentially over roughly 3 maintenance cycles
            var recencyFactor = Math.exp(-timeSinceAccess / (FORGETTING_CHECK_INTERVAL_MS * 3.0));
            return importance.getCurrent(recencyFactor) * truth.confidence();
        }

        /** Convenience method for weighted truth (confidence * strength). */
        public double getWeightedTruth() {
            return truth.confidence() * truth.strength;
        }

        @Override public String toString() { return truth + " " + importance + " " + time; }
    }

    /** Immutable record for probabilistic truth (Strength, Count/Evidence). */
    public record Truth(double strength, double count) {
        public static final Truth TRUE = new Truth(1.0, 10.0); // Reasonably confident True
        public static final Truth FALSE = new Truth(0.0, 10.0); // Reasonably confident False
        public static final Truth UNKNOWN = new Truth(0.5, 0.1); // Default, low confidence

        /** Canonical constructor ensures strength is [0,1] and count >= 0. */
        public Truth {
            strength = Math.max(0.0, Math.min(1.0, strength));
            count = Math.max(0.0, count);
        }

        /** Calculates confidence based on count and sensitivity factor. */
        public double confidence() { return count / (count + TRUTH_DEFAULT_SENSITIVITY); }

        /** Merges with another TruthValue using Bayesian-like weighted average based on counts. */
        public Truth merge(Truth other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;
            var totalCount = this.count + other.count;
            var mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new Truth(mergedStrength, totalCount);
        }

        @Override public String toString() { return String.format("TV<s:%.3f, c:%.2f>", strength, count); }
    }

    /** Immutable record for Short-Term (STI) and Long-Term (LTI) Importance. */
    public record Pri(double sti, double lti) {
        public static final Pri DEFAULT = new Pri(IMPORTANCE_INITIAL_STI, IMPORTANCE_INITIAL_STI * IMPORTANCE_INITIAL_LTI_FACTOR);

        private static double unitize(double v) { return Math.max(0.0, Math.min(1.0, v)); }

        /** Canonical constructor ensures values are [0,1]. */
        public Pri { sti = unitize(sti); lti = unitize(lti); }

        /** Returns a new ImportanceValue after applying decay. LTI learns slowly from decayed STI. */
        public Pri decay() {
            var decayedSti = sti * (1 - IMPORTANCE_STI_DECAY_RATE);
            // LTI decays slower and gains a fraction of the decayed STI value
            var ltiGain = sti * IMPORTANCE_STI_DECAY_RATE * IMPORTANCE_STI_TO_LTI_RATE; // LTI learns from what STI *lost*
            var decayedLti = lti * (1 - IMPORTANCE_LTI_DECAY_RATE) + ltiGain;
            return new Pri(decayedSti, decayedLti);
        }

        /** Returns a new ImportanceValue after applying boost. Boost affects STI directly, LTI indirectly. */
        public Pri boost(double boostAmount) {
            if (boostAmount <= 0) return this;
            var boostedSti = unitize(sti + boostAmount); // Additive boost to STI, capped at 1
            // LTI boost is proportional to the STI change and the boost amount itself
            var ltiBoostFactor = IMPORTANCE_STI_TO_LTI_RATE * Math.abs(boostAmount);
            var boostedLti = unitize(lti + (boostedSti - sti) * ltiBoostFactor); // LTI learns based on STI *increase*
            return new Pri(boostedSti, boostedLti);
        }

        /** Calculates combined importance, weighted by a recency factor (applied externally). */
        public double getCurrent(double recencyFactor) {
            // Weighted average, giving more weight to STI modulated by recency
            return sti * recencyFactor * 0.6 + lti * 0.4;
        }

        @Override public String toString() { return String.format("IV<sti:%.3f, lti:%.3f>", sti, lti); }
    }

    /** Immutable record representing a discrete time point (logical time). */
    public record Time(long time) {
        public static final Time DEFAULT = new Time(0L);
        @Override public String toString() { return "Time<" + time + ">"; }
    }

    // --- Atomspace / Memory Management ---

    /** Central knowledge repository managing Atoms and their metadata (Values). */
    public static class Memory {
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024); // ID -> Atom lookup (canonical store)
        private final ConcurrentMap<Atom, AtomicReference<Value>> storage = new ConcurrentHashMap<>(1024); // Atom -> Value Ref
        // Index: Head Atom -> Set<ExpressionAtom ID> (for faster rule/query lookup)
        private final ConcurrentMap<Atom, ConcurrentSkipListSet<String>> indexByHead = new ConcurrentHashMap<>();
        // Potential future indices: indexByContainedAtom, indexByType

        private final Supplier<Long> timeSource;

        public Memory(Supplier<Long> timeSource) { this.timeSource = timeSource; }

        /**
         * Adds an atom or retrieves the canonical instance if it already exists.
         * Initializes or updates metadata (access time, small boost).
         */
        @SuppressWarnings("unchecked") // Cast to A is safe due to computeIfAbsent logic
        public <A extends Atom> A add(A atom) {
            // Ensure canonical Atom instance is used/stored by checking ID first
            var canonicalAtom = (A) atomsById.computeIfAbsent(atom.id(), id -> atom);

            long now = timeSource.get();
            // Ensure metadata exists and update access time/boost slightly
            var valueRef = storage.computeIfAbsent(canonicalAtom, k -> {
                var initialValue = Value.DEFAULT.withImportance(new Pri(IMPORTANCE_INITIAL_STI, IMPORTANCE_INITIAL_STI * IMPORTANCE_INITIAL_LTI_FACTOR)).updateTime(now);
                updateIndices(k); // Index the new atom when metadata is first created
                return new AtomicReference<>(initialValue);
            });
            // Apply small access boost even if atom already existed
            valueRef.updateAndGet(v -> v.boost(IMPORTANCE_BOOST_ON_ACCESS * 0.1, now));

            checkMemoryAndTriggerForgetting(); // Trigger forgetting if memory grows too large
            return canonicalAtom;
        }

        /** Retrieves an Atom instance by its unique ID string, boosting its importance. */
        public Optional<Atom> getAtom(String id) {
            return Optional.ofNullable(atomsById.get(id)).map(this::boostAndGet);
        }

        /** Retrieves the canonical Atom instance if present, boosting its importance. */
        public Optional<Atom> getAtom(Atom atom) {
            // Use atomsById to ensure we boost the canonical instance
            return Optional.ofNullable(atomsById.get(atom.id())).map(this::boostAndGet);
        }

        private Atom boostAndGet(Atom atom) {
            updateValue(atom, v -> v.boost(IMPORTANCE_BOOST_ON_ACCESS, timeSource.get()));
            return atom;
        }

        /** Retrieves the current Value (Truth, Importance, Time) for an Atom. */
        public Optional<Value> value(Atom atom) {
            // Use canonical atom from atomsById map to look up value
            return Optional.ofNullable(atomsById.get(atom.id()))
                    .flatMap(canonicalAtom -> Optional.ofNullable(storage.get(canonicalAtom)))
                    .map(AtomicReference::get);
        }

        /** Retrieves the current Value or returns Value.DEFAULT if the atom is not in the space. */
        public Value valueOrDefault(Atom atom) { return value(atom).orElse(Value.DEFAULT); }

        /** Atomically updates the Value associated with an Atom using an updater function. Also applies revision boost if truth changes significantly. */
        public void updateValue(Atom atom, Function<Value, Value> updater) {
            var canonicalAtom = atomsById.get(atom.id());
            if (canonicalAtom == null) return; // Atom not in space

            var valueRef = storage.get(canonicalAtom);
            if (valueRef != null) { // Check if metadata exists (should always exist if canonicalAtom exists)
                long now = timeSource.get();
                valueRef.updateAndGet(current -> {
                    var updated = updater.apply(current).updateTime(now); // Apply update and set time
                    // Calculate revision boost based on confidence change
                    double confidenceDiff = updated.truth.confidence() - current.truth.confidence();
                    double boost = (confidenceDiff > TRUTH_REVISION_CONFIDENCE_THRESHOLD)
                            ? IMPORTANCE_BOOST_ON_REVISION_MAX * updated.truth.confidence() // Boost proportional to new confidence
                            : 0.0;
                    return boost > 0 ? updated.boost(boost, now) : updated; // Apply boost if significant change
                });
            }
        }

        /** Updates the truth value, merging with existing truth using Bayesian logic. */
        public void updateTruth(Atom atom, Truth newTruth) {
            updateValue(atom, v -> v.withTruth(v.truth.merge(newTruth)));
        }

        /** Boosts the importance of an atom. */
        public void boost(Atom atom, double amount) {
            updateValue(atom, v -> v.boost(amount, timeSource.get()));
        }

        // --- Atom Factory Methods (Add to this Space) ---
        public Atom sym(String name) { return add(Sym.of(name)); }
        public Var var(String name) { return add(new Var(name)); }
        public Expr expr(List<Atom> children) { return add(new Expr(children)); }
        public Expr expr(Atom... children) { return add(new Expr(List.of(children))); }
        public <T> Is<T> is(T value) { return add(new Is<>(value)); }
        public <T> Is<T> is(String id, T value) { return add(new Is<>(id, value)); }
        public Is<Function<List<Atom>, Optional<Atom>>> isExe(String name, Function<List<Atom>, Optional<Atom>> executor) {
            return add(new Is<>(name, executor));
        }

        /**
         * Performs pattern matching using the Unification engine. Uses indices to find candidates efficiently.
         */
        public List<Answer> query(Atom pattern) {
            // Ensure pattern exists in the space & boost it slightly
            Atom queryPattern = add(pattern); // Use canonical instance
            boost(queryPattern, IMPORTANCE_BOOST_ON_ACCESS * 0.2);

            // Find candidate atoms using indices
            Stream<Atom> candidateStream;
            if (queryPattern instanceof Expr pExpr && pExpr.head() != null) {
                // Primary strategy: Use index for expressions starting with the same head.
                var head = pExpr.head();
                candidateStream = indexByHead.getOrDefault(head, new ConcurrentSkipListSet<>()).stream()
                        .map(this::getAtom) // Get Atom from ID
                        .flatMap(Optional::stream);
                // Also consider a direct match for the pattern itself if it's complex
                candidateStream = Stream.concat(candidateStream, Stream.of(queryPattern).filter(storage::containsKey));
            } else if (queryPattern instanceof Var) {
                // Variable matches everything initially, stream all stored atoms. Costly!
                // Consider adding constraints or limiting variable-only queries if performance is critical.
                candidateStream = storage.keySet().stream();
            } else { // Symbol or Grounded Atom pattern
                // Direct match is the most likely case.
                candidateStream = Stream.of(queryPattern).filter(storage::containsKey);
                // Could also check indices if Symbols/Grounded atoms are indexed by containment in the future.
            }

            // Filter candidates by minimum confidence and perform unification
            List<Answer> results = new ArrayList<>();
            var unification = new Unify(this); // Create Unify instance (lightweight)
            int checkCount = 0;
            // Limit the number of candidates to check to prevent runaway queries
            int maxChecks = Math.min(5000 + size() / 10, 15000); // Dynamic limit based on space size

            Iterator<Atom> candidateIterator = candidateStream.iterator();
            while (candidateIterator.hasNext() && results.size() < INTERPRETER_MAX_RESULTS && checkCount < maxChecks) {
                Atom candidate = candidateIterator.next();
                checkCount++;

                // Filter by confidence *before* expensive unification
                Value candidateValue = valueOrDefault(candidate);
                if (candidateValue.truth.confidence() < TRUTH_MIN_CONFIDENCE_MATCH) {
                    continue;
                }

                // Attempt unification
                unification.unify(queryPattern, candidate, Bind.EMPTY)
                        .ifPresent(bind -> {
                            boost(candidate, IMPORTANCE_BOOST_ON_ACCESS); // Boost successful matches
                            results.add(new Answer(candidate, bind));
                        });
            }
            // Sort results by weighted truth value (confidence * strength) - higher is better
            results.sort(Comparator.comparingDouble((Answer ans) -> valueOrDefault(ans.resultAtom()).getWeightedTruth()).reversed());

            return results.stream().limit(INTERPRETER_MAX_RESULTS).toList(); // Apply final limit
        }


        /** Decays importance of all atoms and removes ("forgets") those below the importance threshold. */
        synchronized void decayAndForget() {
            final long now = timeSource.get();
            var initialSize = storage.size();
            if (initialSize == 0) return;

            List<Atom> candidatesForRemoval = new ArrayList<>();
            int decayCount = 0;

            // 1. Decay and identify removal candidates in one pass
            for (var entry : storage.entrySet()) {
                var atom = entry.getKey();
                var valueRef = entry.getValue();

                // Skip protected symbols and variables from potential removal list
                var isProtected = (atom instanceof Sym s && PROTECTED_SYMBOLS.contains(s.name())) || atom instanceof Var;

                // Atomically decay the value
                var decayedValue = valueRef.updateAndGet(v -> v.decay(now));
                decayCount++;

                // Check if eligible for removal based on importance (if not protected)
                if (!isProtected && decayedValue.getCurrentImportance(now) < IMPORTANCE_MIN_FORGET_THRESHOLD) {
                    candidatesForRemoval.add(atom);
                }
            }

            int removedCount = 0;
            // 2. Forget if memory pressure exists or a significant number of low-importance atoms found
            int targetSize = FORGETTING_MAX_MEM_SIZE_TRIGGER * FORGETTING_TARGET_MEM_SIZE_FACTOR / 100;
            boolean memoryPressure = initialSize > FORGETTING_MAX_MEM_SIZE_TRIGGER;
            boolean significantLowImportance = candidatesForRemoval.size() > initialSize * 0.05; // e.g., > 5% are candidates

            if (!candidatesForRemoval.isEmpty() && (memoryPressure || significantLowImportance || initialSize > targetSize)) {
                // Sort candidates by current importance (lowest first)
                candidatesForRemoval.sort(Comparator.comparingDouble(atom -> valueOrDefault(atom).getCurrentImportance(now)));

                // Determine how many to remove
                int removalTargetCount = memoryPressure
                        ? Math.max(0, initialSize - targetSize) // Remove excess under pressure
                        : candidatesForRemoval.size();          // Remove all candidates otherwise (up to sorted list size)

                int actuallyRemoved = 0;
                for (Atom atomToRemove : candidatesForRemoval) {
                    if (actuallyRemoved >= removalTargetCount) break;
                    // Re-check threshold, as importance might change slightly due to time effects
                    if (valueOrDefault(atomToRemove).getCurrentImportance(now) < IMPORTANCE_MIN_FORGET_THRESHOLD) {
                        if (removeAtomInternal(atomToRemove)) {
                            actuallyRemoved++;
                        }
                    }
                }
                removedCount = actuallyRemoved;
            }

            if (removedCount > 0 || decayCount > 0 && initialSize > 10) { // Avoid logging spam for tiny spaces
                System.out.printf("Atomspace Maintenance: Decayed %d atoms. Removed %d low-importance atoms. Size %d -> %d.%n",
                        decayCount, removedCount, initialSize, storage.size());
            }
        }

        /** Internal helper to remove an atom and update indices. Returns true if removed. */
        boolean removeAtomInternal(Atom atom) {
            if (storage.remove(atom) != null) {
                atomsById.remove(atom.id());
                removeIndices(atom);
                return true;
            }
            return false;
        }

        /** Checks memory size and triggers asynchronous forgetting if threshold exceeded. */
        private void checkMemoryAndTriggerForgetting() {
            if (storage.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER) {
                CompletableFuture.runAsync(this::decayAndForget);
            }
        }

        // --- Index Management ---
        private void updateIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null) {
                indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id());
            }
            // Add other indexing logic here (e.g., by type, by contained atom)
        }

        private void removeIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null) {
                indexByHead.computeIfPresent(e.head(), (k, v) -> {
                    v.remove(atom.id());
                    return v.isEmpty() ? null : v; // Remove set entirely if empty
                });
            }
            // Add removal logic for other indices here
        }

        /** Returns the number of atoms currently in the Atomspace. */
        public int size() { return storage.size(); }
    }


    // --- Unification Engine ---

    /** Represents variable bindings resulting from unification. Immutable. */
    public record Bind(Map<Var, Atom> map) {
        public static final Bind EMPTY = new Bind(emptyMap());

        /** Canonical constructor ensures the internal map is immutable. */
        public Bind { map = Map.copyOf(map); }

        public boolean isEmpty() { return map.isEmpty(); }
        public Optional<Atom> get(Var var) { return Optional.ofNullable(map.get(var)); }

        /** Recursively resolves bindings for a variable, handling chained bindings and detecting cycles. */
        public Optional<Atom> getRecursive(Var var) {
            Atom current = map.get(var);
            if (current == null) return Optional.empty();

            Set<Var> visited = new HashSet<>();
            visited.add(var);

            while (current instanceof Var v) {
                if (!visited.add(v)) return Optional.empty(); // Cycle detected!
                Atom next = map.get(v);
                if (next == null) return Optional.of(v); // Return the last unbound variable in the chain
                current = next;
            }
            return Optional.of(current); // Return the final concrete value (non-variable)
        }

        @Override public String toString() {
            return map.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    }

    /** Result of a successful pattern match query, containing the matched atom and bindings. */
    public record Answer(Atom resultAtom, Bind bind) {}

    /** Performs unification between Atoms using an iterative, stack-based approach. */
    public static class Unify {
        private final Memory space; // Reference to space (currently unused, but potentially for type checks)

        public Unify(Memory space) { this.space = space; }

        /**
         * Attempts to unify a pattern Atom with an instance Atom, starting with initial bindings.
         * Returns an Optional containing the resulting Bindings if successful, or empty otherwise.
         */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>();
            stack.push(new Pair<>(pattern, instance));
            Bind currentBindings = initialBind;

            while (!stack.isEmpty()) {
                Pair<Atom, Atom> task = stack.pop();
                // Apply current bindings *before* comparison/processing
                Atom p = substitute(task.a(), currentBindings);
                Atom i = substitute(task.b(), currentBindings);

                // 1. Identical after substitution? Success for this pair.
                if (p.equals(i)) continue;

                // 2. Pattern is Variable? Try to bind it.
                if (p instanceof Var pVar) {
                    if (containsVariable(i, pVar)) return Optional.empty(); // Occurs check fail
                    currentBindings = mergeBinding(currentBindings, pVar, i);
                    if (currentBindings == null) return Optional.empty(); // Merge conflict
                    continue;
                }

                // 3. Instance is Variable? Try to bind it. (Symmetrical to case 2)
                if (i instanceof Var iVar) {
                    if (containsVariable(p, iVar)) return Optional.empty(); // Occurs check fail
                    currentBindings = mergeBinding(currentBindings, iVar, p);
                    if (currentBindings == null) return Optional.empty(); // Merge conflict
                    continue;
                }

                // 4. Both are Expressions? Unify children.
                if (p instanceof Expr pExpr && i instanceof Expr iExpr) {
                    var pChildren = pExpr.children();
                    var iChildren = iExpr.children();
                    if (pChildren.size() != iChildren.size()) return Optional.empty(); // Different arity
                    // Push child pairs onto stack in reverse order for LIFO processing
                    for (int j = pChildren.size() - 1; j >= 0; j--) {
                        stack.push(new Pair<>(pChildren.get(j), iChildren.get(j)));
                    }
                    continue;
                }

                // 5. Mismatch in type or structure (and not covered above)? Failure.
                return Optional.empty();
            }
            // Stack is empty and no failures encountered? Success.
            return Optional.of(currentBindings);
        }

        /** Applies bindings to an Atom, recursively replacing variables with their bound values. */
        public Atom substitute(Atom atom, Bind bind) {
            if (bind.isEmpty() || !(atom instanceof Var || atom instanceof Expr)) {
                return atom; // No bindings or not substitutable type
            }

            return switch (atom) {
                case Var var ->
                    // Resolve recursively, then substitute the result. If no binding, return var itself.
                        bind.getRecursive(var)
                                .map(val -> substitute(val, bind)) // Substitute recursively *after* resolving
                                .orElse(var);
                case Expr expr -> {
                    boolean changed = false;
                    List<Atom> newChildren = new ArrayList<>(expr.children().size());
                    for (Atom child : expr.children()) {
                        Atom substitutedChild = substitute(child, bind);
                        if (child != substitutedChild) changed = true;
                        newChildren.add(substitutedChild);
                    }
                    // Return new Expr only if children actually changed, preserving object identity otherwise
                    yield changed ? new Expr(newChildren) : expr;
                }
                default -> atom; // Symbols, Grounded Atoms are not substituted into
            };
        }

        // Occurs check: Checks if a variable `var` occurs within `expr` after substitution.
        private boolean containsVariable(Atom expr, Var var) {
            return switch (expr) {
                case Var v -> v.equals(var); // Direct match
                case Expr e -> e.children().stream().anyMatch(c -> containsVariable(c, var)); // Check children recursively
                default -> false; // Symbols, Grounded atoms cannot contain variables
            };
        }

        // Merges a new binding (var -> value) into existing bindings.
        // Returns updated bindings or null if there's a conflict.
        private @Nullable Bind mergeBinding(Bind current, Var var, Atom value) {
            Optional<Atom> existingBindingOpt = current.getRecursive(var);

            if (existingBindingOpt.isPresent()) {
                // Variable already bound, unify the existing value with the new value.
                // If they unify, the resulting bindings (which might be augmented) are returned.
                // If they don't unify, it's a conflict, return null.
                return unify(existingBindingOpt.get(), value, current).orElse(null);
            } else {
                // Variable not bound yet, add the new binding.
                Map<Var, Atom> newMap = new HashMap<>(current.map());
                newMap.put(var, value);
                return new Bind(newMap);
            }
        }
    }


    // --- MeTTa Interpreter ---

    /** Evaluates MeTTa expressions using unification against equality rules stored in the Atomspace. */
    public class Interp {
        private final Cog cog; // Access to Cog components like space, unify

        public Interp(Cog cog) {
            this.cog = cog;
        }

        /** Applies bindings to an Atom. Delegates to Unify. */
        public Atom substitute(Atom atom, Bind bind) {
            return cog.unify.substitute(atom, bind);
        }

        /** Evaluates an Atom with a specified maximum recursion depth. */
        public List<Atom> eval(Atom atom, int maxDepth) {
            Set<String> visitedInPath = new HashSet<>(); // Detect cycles within a single evaluation path
            List<Atom> results = evalRecursive(atom, maxDepth, visitedInPath);

            // If evaluation yielded no results (e.g., only cycles or depth limit), return original atom.
            if (results.isEmpty()) {
                return List.of(atom);
            }

            // Filter distinct results and limit the total number.
            // Sorting by value can happen here or in the caller (e.g., evalBest)
            return results.stream()
                    .distinct()
                    .limit(INTERPRETER_MAX_RESULTS)
                    .toList();
        }

        /** Recursive evaluation helper with depth tracking and cycle detection. */
        private List<Atom> evalRecursive(Atom atom, int depth, Set<String> visitedInPath) {
            // 1. Base Cases: Depth limit, cycle detected, or non-evaluatable atom type
            if (depth <= 0) return List.of(atom); // Depth limit reached
            if (!visitedInPath.add(atom.id())) return List.of(atom); // Cycle detected in this path

            List<Atom> combinedResults = new ArrayList<>();
            try {
                switch (atom) {
                    // Variables and Symbols evaluate to themselves (unless part of matched rule later)
                    case Sym s -> combinedResults.add(s);
                    case Var v -> combinedResults.add(v);
                    // Non-executable Grounded Atoms evaluate to themselves
                    case Is<?> ga when !ga.isExecutable() -> combinedResults.add(ga);

                    // Expressions are the main target for evaluation
                    case Expr expr -> {
                        // Strategy 1: Try specific equality rules `(= <expr> $result)`
                        Var resultVar = V("evalRes" + depth); // Unique var name per depth
                        Atom specificQuery = E(SYMBOL_EQ, expr, resultVar);
                        List<Answer> specificMatches = cog.space.query(specificQuery);

                        if (!specificMatches.isEmpty()) {
                            for (Answer match : specificMatches) {
                                Optional<Atom> target = match.bind.get(resultVar);
                                if (target.isPresent()) {
                                    // Recursively evaluate the result of the rule
                                    combinedResults.addAll(evalRecursive(target.get(), depth - 1, new HashSet<>(visitedInPath)));
                                    if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                                }
                            }
                            // If specific rules found results, often we don't proceed further, but MeTTa allows non-determinism.
                            // Let's continue to general rules unless we hit the result limit.
                        }

                        // Strategy 2: Try general equality rules `(= <pattern> <template>)` if no specific match or still need results
                        // Avoid re-running if already at result limit
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS) {
                            Var pVar = V("p" + depth);
                            Var tVar = V("t" + depth);
                            Atom generalQuery = E(SYMBOL_EQ, pVar, tVar);
                            List<Answer> ruleMatches = cog.space.query(generalQuery);

                            for (Answer ruleMatch : ruleMatches) {
                                Atom pattern = ruleMatch.bind.get(pVar).orElse(null);
                                Atom template = ruleMatch.bind.get(tVar).orElse(null);

                                // Skip if rule is invalid or is the specific rule already tried
                                if (pattern == null || template == null || pattern.equals(expr)) continue;

                                // Try to unify the expression with the rule's pattern
                                Optional<Bind> exprBindOpt = cog.unify.unify(pattern, expr, Bind.EMPTY);
                                if (exprBindOpt.isPresent()) {
                                    // If unification succeeds, substitute bindings into the template and evaluate it
                                    Atom result = substitute(template, exprBindOpt.get());
                                    combinedResults.addAll(evalRecursive(result, depth - 1, new HashSet<>(visitedInPath)));
                                    if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                                }
                            }
                        }

                        // Strategy 3: Try executing head if it's a Grounded Function (Applicative Order Evaluation)
                        // Only if no rules applied or still need results
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS && expr.head() instanceof Is<?> ga && ga.isExecutable()) {
                            List<Atom> evaluatedArgs = new ArrayList<>();
                            boolean argEvalOk = true;
                            // Evaluate arguments first
                            for (Atom arg : expr.tail()) {
                                List<Atom> argResults = evalRecursive(arg, depth - 1, new HashSet<>(visitedInPath));
                                // Require exactly one result for functional application (simplification)
                                if (argResults.size() != 1) {
                                    // Could also choose the 'best' result via evalBest if multiple results allowed.
                                    argEvalOk = false;
                                    break;
                                }
                                evaluatedArgs.add(argResults.getFirst());
                            }

                            if (argEvalOk) {
                                // Execute the grounded function with evaluated arguments
                                Optional<Atom> execResult = ga.execute(evaluatedArgs);
                                if (execResult.isPresent()) {
                                    // Evaluate the result of the grounded function execution
                                    combinedResults.addAll(evalRecursive(execResult.get(), depth - 1, new HashSet<>(visitedInPath)));
                                }
                            }
                        }

                        // Strategy 4: No rule applied or execution possible? Evaluate children and reconstruct (if changed)
                        // This handles cases like `(Cons (add 1 1) Nil)` -> `(Cons 2 Nil)`
                        // Only do this if strategies 1-3 yielded no results.
                        if (combinedResults.isEmpty()) {
                            boolean childrenChanged = false;
                            List<Atom> evaluatedChildren = new ArrayList<>();
                            if (expr.head() != null) {
                                List<Atom> headResults = evalRecursive(expr.head(), depth - 1, new HashSet<>(visitedInPath));
                                if (headResults.size() == 1) { // Require single head result
                                    Atom newHead = headResults.getFirst();
                                    evaluatedChildren.add(newHead);
                                    if (!newHead.equals(expr.head())) childrenChanged = true;
                                } else {
                                    evaluatedChildren.add(expr.head()); // Keep original if eval failed/ambiguous
                                }
                            }
                            for (Atom child : expr.tail()) {
                                List<Atom> childResults = evalRecursive(child, depth - 1, new HashSet<>(visitedInPath));
                                if (childResults.size() == 1) { // Require single child result
                                    Atom newChild = childResults.getFirst();
                                    evaluatedChildren.add(newChild);
                                    if (!newChild.equals(child)) childrenChanged = true;
                                } else {
                                    evaluatedChildren.add(child); // Keep original
                                }
                            }
                            // If any child changed, return the new expression, otherwise the original
                            combinedResults.add(childrenChanged ? cog.E(evaluatedChildren) : expr);
                        }

                        // If after all strategies, no results were added, the expression evaluates to itself.
                        if (combinedResults.isEmpty()) {
                            combinedResults.add(expr);
                        }
                    }
                    // Executable Grounded atoms (when evaluated directly, not as expr head) - currently evaluate to self
                    // Could potentially execute with empty args: ga.execute(emptyList())? TBD.
                    case Is<?> ga -> combinedResults.add(ga);
                }
            } finally {
                visitedInPath.remove(atom.id()); // Backtrack: remove from visited set for this path
            }
            return combinedResults;
        }
    }


    // --- Agent Framework ---

    /** Interface for the environment the agent interacts with. */
    public interface Game {
        /** Returns a list of Atoms representing the agent's current perception. */
        List<Atom> perceive(Cog cog);

        /** Returns a list of Atoms representing available actions in the current state. */
        List<Atom> actions(Cog cog, Atom currentState);

        /** Executes an action, returning the new percepts and reward. */
        Act exe(Cog cog, Atom action);

        /** Returns true if the environment/game simulation should continue running. */
        boolean isRunning();
    }

    /** Represents the result of an agent's action in the environment. */
    public record Act(List<Atom> newPercepts, double reward) {}

    /** A simple game environment for agent demonstration. */
    static class SimpleGame implements Game {
        private final Atom posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred, selfSym;
        private Atom currentStateSymbol; // Internal state of the game
        private final Cog cog;

        SimpleGame(Cog cog) {
            this.cog = cog;
            // Define game symbols within the Cog's space
            posA = cog.S("Pos_A");
            posB = cog.S("Pos_B");
            posGoal = cog.S("AtGoal");
            moveAtoB = cog.S("Move_A_B");
            moveBtoGoal = cog.S("Move_B_Goal");
            moveOther = cog.S("Move_Other");
            statePred = cog.S("State"); // Standard predicate for state facts
            selfSym = cog.S("Self");    // Standard symbol for the agent itself
            currentStateSymbol = posA;  // Initial state
        }

        @Override
        public List<Atom> perceive(Cog cog) {
            // The agent perceives its current state as a fact
            return List.of(cog.E(statePred, selfSym, currentStateSymbol));
        }

        @Override
        public List<Atom> actions(Cog cog, Atom currentStateAtom) {
            // Available actions depend on the internal state symbol
            if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
            if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
            return List.of(moveOther); // Only 'other' action if at goal or error state
        }

        @Override
        public Act exe(Cog cog, Atom actionSymbol) {
            double reward = -0.1; // Small cost for taking any action
            boolean stateChanged = false;

            if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) {
                currentStateSymbol = posB;
                reward = 0.1; // Small reward for correct move
                stateChanged = true;
                System.out.println("Env: Moved A -> B");
            } else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) {
                currentStateSymbol = posGoal;
                reward = 1.0; // Large reward for reaching the goal
                stateChanged = true;
                System.out.println("Env: Moved B -> Goal!");
            } else if (actionSymbol.equals(moveOther)) {
                // 'Move_Other' does nothing but incurs cost
                reward = -0.2;
                System.out.println("Env: Executed 'Move_Other'");
            }
            else {
                // Invalid action for the current state
                reward = -0.5; // Penalty for invalid action
                System.out.println("Env: Invalid action attempted: " + actionSymbol.id() + " from state " + currentStateSymbol.id());
            }

            // Return new perception based on potentially updated state, and the calculated reward
            return new Act(perceive(cog), reward);
        }

        @Override
        public boolean isRunning() {
            // The game stops when the goal state is reached
            return !currentStateSymbol.equals(posGoal);
        }
    }


    /** Agent implementing perceive-evaluate-act-learn cycle using MeTTa evaluation and learning. */
    public class Agent {
        private final Cog cog;
        private final RandomGenerator random = RandomGenerator.getDefault();
        // Standard symbols used by the agent logic
        private final Atom STATE_PRED, GOAL_PRED, ACTION_PRED, UTILITY_PRED, IMPLIES_PRED, SEQ_PRED, SELF_SYM;
        private @Nullable Atom currentAgentStateAtom = null; // Agent's representation of the current state

        public Agent(Cog cog) {
            this.cog = cog;
            // Define or retrieve standard agent symbols
            this.STATE_PRED = cog.S("State");
            this.GOAL_PRED = cog.S("Goal");
            this.ACTION_PRED = cog.S("Action"); // Maybe unused directly, actions are usually symbols
            this.UTILITY_PRED = cog.S("Utility"); // Predicate for utility facts/rules
            this.IMPLIES_PRED = cog.S("Implies"); // Predicate for learned transition rules
            this.SEQ_PRED = cog.S("Seq");       // Represents sequence of state/action
            this.SELF_SYM = cog.S("Self");      // Agent's identifier
        }

        /** Runs the agent loop within a given environment, pursuing a goal pattern. */
        public void run(Game env, Atom goalPattern, int maxCycles) {
            System.out.println("\n--- Agent Run Start ---");
            initializeGoal(goalPattern);
            // Initial perception processing
            currentAgentStateAtom = processPerception(env.perceive(cog));

            for (int cycle = 0; cycle < maxCycles && env.isRunning(); cycle++) {
                long time = cog.tick(); // Advance logical time
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, time);
                System.out.println("Agent State: " + currentAgentStateAtom);

                // Check if goal is achieved based on agent's current knowledge
                if (isGoalAchieved(goalPattern)) {
                    System.out.println("*** Agent: Goal Achieved! ***");
                    break;
                }

                // Decide on an action
                Optional<Atom> actionOpt = selectAction(env.actions(cog, currentAgentStateAtom));

                if (actionOpt.isPresent()) {
                    Atom action = actionOpt.get();
                    System.out.println("Agent: Selected Action: " + action);

                    // Execute action in environment
                    Act result = env.exe(cog, action);

                    // Process new percepts and update state representation
                    Atom nextState = processPerception(result.newPercepts);

                    // Learn from the experience (state transition and utility)
                    learn(currentAgentStateAtom, action, nextState, result.reward);

                    // Update agent's current state
                    currentAgentStateAtom = nextState;
                } else {
                    System.out.println("Agent: No action available or selected. Idling.");
                    // Optionally, agent could perform internal reasoning (evaluation) even when idling
                }
            }
            if (!env.isRunning() && !isGoalAchieved(goalPattern)) {
                System.out.println("--- Agent Run Finished (Environment Stopped) ---");
            } else if (!isGoalAchieved(goalPattern)){
                System.out.println("--- Agent Run Finished (Max Cycles Reached) ---");
            } else {
                System.out.println("--- Agent Run Finished ---");
            }
        }

        /** Adds the goal to the Atomspace and boosts its importance. */
        private void initializeGoal(Atom goalPattern) {
            // Represent the goal as a fact `(Goal <goal_pattern>)`
            Atom goalAtom = cog.add(cog.E(GOAL_PRED, goalPattern));
            cog.space.boost(goalAtom, IMPORTANCE_BOOST_ON_GOAL_FOCUS);
            // Also boost the pattern itself slightly
            cog.space.boost(goalPattern, IMPORTANCE_BOOST_ON_GOAL_FOCUS * 0.8);
            System.out.println("Agent: Goal initialized -> " + goalAtom);
        }

        /** Checks if the goal pattern currently holds true in the Atomspace. */
        private boolean isGoalAchieved(Atom goalPattern) {
            // Query the space to see if the goal pattern currently matches any facts
            boolean achieved = !cog.space.query(goalPattern).isEmpty();
            if (achieved) {
                System.out.println("Agent: Goal check query successful for: " + goalPattern);
            }
            return achieved;
        }

        /** Processes percepts, adds them to the space, and returns a representative state Atom. */
        private Atom processPerception(List<Atom> percepts) {
            if (percepts.isEmpty()) {
                // Handle empty perception? Return previous state or a default 'Unknown' state?
                return currentAgentStateAtom != null ? currentAgentStateAtom : cog.S("State_Unknown");
            }
            // Add each percept as a fact with high truth and boost importance
            List<String> perceptIDs = Collections.synchronizedList(new ArrayList<>()); // For stable hashing
            percepts.forEach(p -> {
                Atom factAtom = cog.add(p); // Add the percept atom itself
                cog.space.updateTruth(factAtom, new Truth(1.0, AGENT_DEFAULT_PERCEPTION_COUNT)); // High confidence perception
                cog.space.boost(factAtom, IMPORTANCE_BOOST_ON_PERCEPTION);
                perceptIDs.add(factAtom.id()); // Collect IDs for state hashing
            });

            // Create a single Atom representing the combined state based on percept IDs
            // Simple approach: Sort IDs and hash the joined string
            Collections.sort(perceptIDs);
            String stateHash = Integer.toHexString(String.join("|", perceptIDs).hashCode());
            Atom stateAtom = cog.S("State_" + stateHash); // Create a symbol representing this unique state configuration

            // Ensure this state symbol exists and is marked as current/true
            cog.add(stateAtom);
            cog.space.updateTruth(stateAtom, Truth.TRUE);
            cog.space.boost(stateAtom, IMPORTANCE_BOOST_ON_PERCEPTION); // Boost the state symbol itself

            return stateAtom;
        }

        /** Selects an action based on learned utility or explores randomly. */
        private Optional<Atom> selectAction(List<Atom> availableActions) {
            if (availableActions.isEmpty()) return Optional.empty();

            // 1. Evaluate utility of available actions
            Map<Atom, Double> utilities = new ConcurrentHashMap<>();
            Var valVar = cog.V("val"); // Variable to capture utility value in query

            // Use parallel stream for potential speedup if many actions or complex utility eval
            availableActions.parallelStream().forEach(action -> {
                Atom actionAtom = cog.add(action); // Ensure canonical action atom
                // Query for the utility rule: (= (Utility <actionAtom>) $val)
                Atom utilQuery = cog.E(SYMBOL_EQ, cog.E(UTILITY_PRED, actionAtom), valVar);
                // Find the best (highest value) utility associated with this action
                double utility = cog.space.query(utilQuery).stream()
                        .map(answer -> answer.bind().get(valVar)) // Get the binding for $val
                        .flatMap(Optional::stream)
                        .filter(atom -> atom instanceof Is<?> g && g.value() instanceof Number) // Ensure it's a grounded number
                        .mapToDouble(atom -> ((Number) ((Is<?>) atom).value()).doubleValue())
                        .max() // Find the maximum defined utility (if multiple rules exist)
                        .orElse(0.0); // Default utility is 0 if no rule found

                // Store utility if above a minimum threshold (to avoid selecting clearly bad actions)
                if (utility > AGENT_UTILITY_THRESHOLD) {
                    utilities.put(actionAtom, utility);
                }
            });

            // 2. Select best action based on utility
            Optional<Map.Entry<Atom, Double>> bestAction = utilities.entrySet().stream()
                    .max(Map.Entry.comparingByValue());

            if (bestAction.isPresent()) {
                System.out.printf("Agent: Selecting by utility: %s (Util: %.3f)%n", bestAction.get().getKey(), bestAction.get().getValue());
                return Optional.of(bestAction.get().getKey());
            }

            // 3. Exploration: If no high-utility action found, explore randomly
            if (random.nextDouble() < AGENT_RANDOM_ACTION_PROBABILITY || utilities.isEmpty()) {
                System.out.println("Agent: Selecting random action (exploration or no known good options).");
                // Add the randomly chosen action to the space if it wasn't already evaluated
                return Optional.of(cog.add(availableActions.get(random.nextInt(availableActions.size()))));
            }

            // 4. Fallback: If exploration roll failed, but there were *some* utilities below threshold,
            // pick the least bad one known. Otherwise, random (covered by step 3).
            // This case is less likely if AGENT_UTILITY_THRESHOLD > 0.
            Optional<Map.Entry<Atom, Double>> leastBadAction = utilities.entrySet().stream()
                    .max(Map.Entry.comparingByValue()); // Max of the below-threshold utilities
            if (leastBadAction.isPresent()) {
                System.out.printf("Agent: Selecting least bad known action: %s (Util: %.3f)%n", leastBadAction.get().getKey(), leastBadAction.get().getValue());
                return Optional.of(leastBadAction.get().getKey());
            }

            // Should be covered by random selection, but as a final fallback:
            System.out.println("Agent: Fallback random action selection.");
            return Optional.of(cog.add(availableActions.get(random.nextInt(availableActions.size()))));
        }


        /** Learns state transition rules and updates action utility based on experience. */
        private void learn(Atom prevState, Atom action, Atom nextState, double reward) {
            // Basic Q-learning / Temporal Difference learning update for utility
            if (prevState == null || action == null || nextState == null) return; // Need valid transition

            // 1. Learn/Update Transition Rule: (Implies (Seq <prevState> <action>) <nextState>)
            // This rule captures "If I was in prevState and did action, I ended up in nextState"
            Atom sequence = cog.add(cog.E(SEQ_PRED, prevState, action));
            Atom implication = cog.add(cog.E(IMPLIES_PRED, sequence, nextState));
            // Update truth based on observation count (simple reinforcement)
            cog.space.updateTruth(implication, new Truth(1.0, AGENT_LEARNED_RULE_COUNT)); // Reinforce this transition
            cog.space.boost(implication, IMPORTANCE_BOOST_ON_ACCESS * 1.2); // Boost learned rules slightly more

            // 2. Learn/Update Utility Rule: (= (Utility <action>) <value>)
            // Q(s,a) = Q(s,a) + alpha * (reward + gamma * max_a'(Q(s',a')) - Q(s,a))
            // Simplified version (no lookahead / gamma=0, or using direct reward):
            // U(a) = U(a) + alpha * (reward - U(a))

            Var valVar = cog.V("val");
            Atom utilityPattern = cog.E(UTILITY_PRED, action); // e.g., (Utility Move_A_B)
            Atom utilityRulePattern = cog.E(SYMBOL_EQ, utilityPattern, valVar); // e.g., (= (Utility Move_A_B) $val)

            // Find the current utility value atom associated with this action
            Optional<Atom> currentUtilityValueAtomOpt = cog.space.query(utilityRulePattern).stream()
                    .map(answer -> answer.bind().get(valVar))
                    .flatMap(Optional::stream)
                    .filter(atom -> atom instanceof Is<?> g && g.value() instanceof Number)
                    .findFirst(); // Assume one primary utility rule per action for simplicity

            double currentUtility = currentUtilityValueAtomOpt
                    .map(atom -> ((Number)((Is<?>)atom).value()).doubleValue())
                    .orElse(0.0); // Default utility is 0

            double learningRate = 0.2; // Alpha
            // Simple update: move utility towards the immediate reward received
            double newUtility = currentUtility + learningRate * (reward - currentUtility);

            // Create the new utility value as a Grounded Atom
            Atom newUtilityValueAtom = cog.G(newUtility);

            // Create or update the utility rule in the space
            // (= (Utility <action>) <newUtilityValueAtom>)
            Atom newUtilityRule = cog.EQ(utilityPattern, newUtilityValueAtom); // EQ adds the rule to the space

            // Set high truth for the newly learned/updated rule
            cog.space.updateTruth(newUtilityRule, Truth.TRUE); // Mark this as the current best utility estimate
            cog.space.boost(newUtilityRule, IMPORTANCE_BOOST_ON_ACCESS * 1.5); // Boost utility rules strongly

            // Optional: Decay or remove the *old* utility rule atom if it existed
            // currentUtilityValueAtomOpt.ifPresent(oldValAtom -> {
            //     cog.space.query(cog.E(SYMBOL_EQ, utilityPattern, oldValAtom)) // Find the specific old rule
            //         .forEach(oldRuleAns -> cog.space.removeAtomInternal(oldRuleAns.resultAtom()));
            // });
            // For simplicity, let's allow multiple rules and rely on query sorting/selection or forgetting.

            System.out.printf("  Learn: Action %s Utility %.3f -> %.3f (Reward: %.2f)%n", action.id(), currentUtility, newUtility, reward);
        }
    }


    // --- MeTTa Parser ---

    /** Parses MeTTa syntax strings into Cog Atoms. */
    private static class MettaParser {
        private final Cog cog; // Needed to create atoms within the Cog's space context

        MettaParser(Cog cog) { this.cog = cog; }

        private Atom parseSymbolOrGrounded(String text) {
            // Handle special symbols recognised as grounded values or core symbols
            return switch (text) {
                case "True" -> SYMBOL_TRUE; // Use canonical True symbol
                case "False" -> SYMBOL_FALSE; // Use canonical False symbol
                case "Nil" -> SYMBOL_NIL; // Use canonical Nil symbol
                default -> cog.S(text); // Default to a regular Symbol
            };
        }

        private String unescapeString(String s) {
            return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
        }

        private enum TokenType { LPAREN, RPAREN, SYMBOL, VARIABLE, NUMBER, STRING, COMMENT, EOF }
        private record Token(TokenType type, String text, int line, int col) {}

        private List<Token> tokenize(String text) {
            List<Token> tokens = new ArrayList<>();
            int line = 1;
            int col = 1;
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                int startCol = col;

                if (Character.isWhitespace(c)) {
                    if (c == '\n') { line++; col = 1; } else { col++; }
                    i++;
                    continue;
                }

                if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(", line, startCol)); i++; col++; continue; }
                if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")", line, startCol)); i++; col++; continue; }

                // Comment: ';' to end of line
                if (c == ';') {
                    int start = i;
                    while (i < text.length() && text.charAt(i) != '\n') i++;
                    // Optionally add comment token or just skip
                    // tokens.add(new Token(TokenType.COMMENT, text.substring(start, i), line, startCol));
                    // We skip comments by default as they aren't part of the structure
                    if (i < text.length() && text.charAt(i) == '\n') { line++; col = 1; i++; } else { col = 1; } // Reset col after EOL or EOF
                    continue;
                }

                // String literal: "..."
                if (c == '"') {
                    int start = i;
                    i++; col++; // Skip opening quote
                    StringBuilder sb = new StringBuilder();
                    boolean escaped = false;
                    while (i < text.length()) {
                        char nc = text.charAt(i);
                        if (nc == '"' && !escaped) {
                            i++; col++; // Skip closing quote
                            tokens.add(new Token(TokenType.STRING, text.substring(start, i), line, startCol));
                            break;
                        }
                        if (nc == '\n') throw new MettaParseException("Unterminated string literal at line " + line);
                        sb.append(nc);
                        escaped = (nc == '\\' && !escaped);
                        i++; col++;
                    }
                    if (i == text.length() && (tokens.isEmpty() || tokens.getLast().type != TokenType.STRING)) {
                        throw new MettaParseException("Unterminated string literal at end of input");
                    }
                    continue;
                }

                // Variable: $...
                if (c == VAR_PREFIX.charAt(0)) {
                    int start = i;
                    i++; col++;
                    while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')') {
                        i++; col++;
                    }
                    String varName = text.substring(start, i);
                    if (varName.length() == 1) throw new MettaParseException("Invalid variable name '$' at line " + line);
                    tokens.add(new Token(TokenType.VARIABLE, varName, line, startCol));
                    continue;
                }

                // Symbol or Number
                int start = i;
                boolean maybeNumber = Character.isDigit(c) || (c == '-' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)));
                boolean hasDot = false;
                boolean hasExp = false;

                while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')' && text.charAt(i) != ';') {
                    char nc = text.charAt(i);
                    if (nc == '.') {
                        if (hasDot || hasExp) maybeNumber = false; // Multiple dots or dot after E
                        else hasDot = true;
                    } else if (nc == 'e' || nc == 'E') {
                        if (hasExp) maybeNumber = false; // Multiple E's
                        else hasExp = true;
                    } else if (nc == '+' || nc == '-') {
                        // Sign only valid at start or after E
                        if (i != start && !(hasExp && (text.charAt(i-1) == 'e' || text.charAt(i-1) == 'E'))) {
                            maybeNumber = false;
                        }
                    } else if (!Character.isDigit(nc)) {
                        maybeNumber = false;
                    }
                    i++; col++;
                }
                String value = text.substring(start, i);

                try {
                    if (maybeNumber) {
                        Double.parseDouble(value); // Check if valid number format
                        tokens.add(new Token(TokenType.NUMBER, value, line, startCol));
                    } else {
                        tokens.add(new Token(TokenType.SYMBOL, value, line, startCol));
                    }
                } catch (NumberFormatException e) {
                    // If parsing as number failed, treat as symbol
                    tokens.add(new Token(TokenType.SYMBOL, value, line, startCol));
                }
            }
            // tokens.add(new Token(TokenType.EOF, "", line, col)); // Don't add EOF, just return list
            return tokens;
        }

        // Simple peekable iterator helper
        private static class PeekableIterator<T> implements Iterator<T> {
            private final Iterator<T> iterator;
            private T nextElement;

            public PeekableIterator(Iterator<T> iterator) {
                this.iterator = iterator;
                advance();
            }

            @Override public boolean hasNext() { return nextElement != null; }
            @Override public T next() {
                if (nextElement == null) throw new NoSuchElementException();
                T current = nextElement;
                advance();
                return current;
            }
            public T peek() {
                if (nextElement == null) throw new NoSuchElementException();
                return nextElement;
            }
            private void advance() { nextElement = iterator.hasNext() ? iterator.next() : null; }
        }

        // Wrap iterator for parsing methods
        private Atom parseAtomFromTokens(PeekableIterator<Token> it) {
            if (!it.hasNext()) throw new MettaParseException("Unexpected end of input");
            Token token = it.next(); // Consume the token
            return switch (token.type) {
                case LPAREN -> parseExprFromTokens(it);
                case VARIABLE -> cog.V(token.text.substring(VAR_PREFIX.length()));
                case SYMBOL -> parseSymbolOrGrounded(token.text);
                case NUMBER -> cog.G(Double.parseDouble(token.text));
                case STRING -> cog.G(unescapeString(token.text.substring(1, token.text.length() - 1)));
                case RPAREN -> throw new MettaParseException("Unexpected ')' at line " + token.line);
                case COMMENT -> {
                    if (!it.hasNext()) throw new MettaParseException("Input ended with a comment");
                    yield parseAtomFromTokens(it); // Skip comment and parse next
                }
                case EOF -> throw new MettaParseException("Unexpected EOF");
            };
        }

        private Expr parseExprFromTokens(PeekableIterator<Token> it) {
            List<Atom> children = new ArrayList<>();
            while (true) {
                if (!it.hasNext()) throw new MettaParseException("Unterminated expression, unexpected end of input");
                Token next = it.peek();
                if (next.type == TokenType.RPAREN) {
                    it.next(); // Consume ')'
                    return cog.E(children);
                }
                if (next.type == TokenType.COMMENT) {
                    it.next(); // Consume comment
                    continue; // Skip comment
                }
                children.add(parseAtomFromTokens(it)); // Parse child atom
            }
        }
        public Atom parse(String text) {
            var tokens = tokenize(text);
            if (tokens.isEmpty()) throw new MettaParseException("Cannot parse empty input");
            var it = new PeekableIterator<>(tokens.iterator());
            Atom result = parseAtomFromTokens(it);
            while (it.hasNext()) { // Check for trailing non-comment tokens
                if (it.peek().type != TokenType.COMMENT) {
                    Token trailing = it.next();
                    throw new MettaParseException("Extra token found after main expression: '" + trailing.text + "' at line " + trailing.line);
                }
                it.next(); // Consume comment
            }
            return result;
        }

        public List<Atom> parseAll(String text) {
            var tokens = tokenize(text);
            List<Atom> results = new ArrayList<>();
            var it = new PeekableIterator<>(tokens.iterator());
            while (it.hasNext()) {
                // Skip leading comments
                while (it.hasNext() && it.peek().type == TokenType.COMMENT) {
                    it.next();
                }
                if (it.hasNext()) { // Check again after skipping comments
                    results.add(parseAtomFromTokens(it));
                }
            }
            return results;
        }

    }

    /** Custom exception for MeTTa parsing errors. */
    public static class MettaParseException extends RuntimeException {
        public MettaParseException(String message) { super(message); }
    }

    // --- Utility Classes ---
    private record Pair<A, B>(A a, B b) {} // Simple pair for internal use (e.g., Unify stack)

    // Helper for unitizing values (clamping between 0 and 1) - moved from Pri constructor
    // Useful if needed elsewhere, but currently only used there. Keep static if broader utility emerges.
    // private static double unitize(double v) { return Math.max(0.0, Math.min(1.0, v)); }


    // --- Bootstrap Logic ---

    /** Initializes core symbols, types, and grounded functions using the MettaParser. */
    private static class CoreBootstrap {
        private final Cog cog;

        CoreBootstrap(Cog cog) { this.cog = cog; }

        void initialize() {
            // Assign core symbols (parser will retrieve/create canonical instances)
            Cog.SYMBOL_EQ = cog.S("=");
            Cog.SYMBOL_COLON = cog.S(":");
            Cog.SYMBOL_ARROW = cog.S("->");
            Cog.SYMBOL_TYPE = cog.S("Type");
            Cog.SYMBOL_TRUE = cog.S("True");
            Cog.SYMBOL_FALSE = cog.S("False");
            Cog.SYMBOL_SELF = cog.S("Self");
            Cog.SYMBOL_NIL = cog.S("Nil");

            // Define core types and grounded functions programmatically
            // (Parser handles data, but functions need Java lambda definitions)
            initializeGroundedFunctions();

            // Load core MeTTa rules and type definitions from string
            String bootstrapMetta = """
                ; Basic Types
                (: = Type)
                (: : Type)
                (: -> Type)
                (: Type Type)
                (: True Type)
                (: False Type)
                (: Nil Type)
                (: Number Type)
                (: String Type)
                (: Bool Type)

                ; Booleans belong to Bool type
                (: True Bool)
                (: False Bool)

                ; Peano Arithmetic Example Rules
                (= (add Z $n) $n)
                (= (add (S $m) $n) (S (add $m $n)))

                ; Basic Arithmetic/Comparison rules linking symbols to grounded functions
                (= (+ $a $b) (Grounded+ $a $b))
                (= (- $a $b) (Grounded- $a $b))
                (= (* $a $b) (Grounded* $a $b))
                (= (/ $a $b) (Grounded/ $a $b))
                (= (== $a $b) (Grounded== $a $b))
                (= (> $a $b) (Grounded> $a $b))
                (= (< $a $b) (Grounded< $a $b))
                ; (= (<= $a $b) (Not (> $a $b))) ; Example derived rule
                ; (= (>= $a $b) (Not (< $a $b))) ; Example derived rule

                ; If control structure rules
                (= (If True $then $else) $then)
                (= (If False $then $else) $else)

                ; Optional: Basic list processing (example)
                ; (= (head (Cons $h $t)) $h)
                ; (= (tail (Cons $h $t)) $t)
            """;
            cog.load(bootstrapMetta);

            // Ensure core symbols have high confidence/importance from the start
            Stream.of(SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_NIL, SYMBOL_SELF)
                    .forEach(sym -> {
                        cog.space.updateTruth(sym, Truth.TRUE); // Mark as definitely existing
                        cog.space.boost(sym, 1.0); // Max importance boost initially
                    });
        }

        private void initializeGroundedFunctions() {
            // --- Core Operations ---
            cog.G("match", args -> { // (match <space> <pattern> <template>)
                if (args.size() != 3) return Optional.empty();
                // Use target space if provided as Grounded<Memory>, else default space
                Memory targetSpace = (args.get(0) instanceof Is<?> g && g.value() instanceof Memory ts) ? ts : cog.space;
                Atom pattern = args.get(1);
                Atom template = args.get(2);
                var queryResults = targetSpace.query(pattern);
                // Apply template substitution for each result binding
                List<Atom> results = queryResults.stream()
                        .map(answer -> cog.interp.substitute(template, answer.bind()))
                        .toList();
                return Optional.of(cog.G(results)); // Return results as Grounded<List<Atom>>
            });

            cog.G("eval", args -> args.isEmpty() ? Optional.empty() : cog.evalBest(args.getFirst())); // (eval <expr>) -> best result

            cog.G("add-atom", args -> args.isEmpty() ? Optional.empty() : Optional.of(cog.add(args.getFirst()))); // (add-atom <atom>) -> adds atom

            cog.G("remove-atom", args -> { // (remove-atom <atom>) -> True/False
                boolean removed = !args.isEmpty() && cog.space.removeAtomInternal(args.getFirst());
                return Optional.of(removed ? SYMBOL_TRUE : SYMBOL_FALSE);
            });

            cog.G("get-value", args -> args.isEmpty() ? Optional.empty() : Optional.of(cog.G(cog.space.value(args.getFirst())))); // (get-value <atom>) -> Grounded<Optional<Value>>

            cog.G("&self", args -> Optional.of(cog.G(cog.space))); // (&self) -> Grounded<Memory> reference to own space

            // --- Arithmetic / Comparison --- (Internal implementation detail)
            cog.G("Grounded+", args -> applyNumericOp(args, cog, Double::sum));
            cog.G("Grounded-", args -> applyNumericOp(args, cog, (a, b) -> a - b));
            cog.G("Grounded*", args -> applyNumericOp(args, cog, (a, b) -> a * b));
            cog.G("Grounded/", args -> applyNumericOp(args, cog, (a, b) -> Math.abs(b) < 1e-12 ? Double.NaN : a / b)); // Avoid division by zero
            cog.G("Grounded==", args -> applyNumericComp(args, cog, (a, b) -> Math.abs(a - b) < 1e-9)); // Tolerance for float equality
            cog.G("Grounded>", args -> applyNumericComp(args, cog, (a, b) -> a > b));
            cog.G("Grounded<", args -> applyNumericComp(args, cog, (a, b) -> a < b));

            // --- String Ops --- (Example)
            cog.G("Concat", args -> { // (Concat $a $b ...) -> concatenated string
                String result = args.stream()
                        .map(CoreBootstrap::getStringValue) // Evaluate/get string value of each arg
                        .flatMap(Optional::stream)
                        .collect(Collectors.joining());
                return Optional.of(cog.G(result));
            });
        }

        // --- Helpers for Grounded Functions ---

        // Applies a binary double operation, evaluating arguments if necessary.
        private static Optional<Atom> applyNumericOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            if (args.size() != 2) return Optional.empty(); // Expect exactly two args
            Optional<Double> n1 = getNumericValue(args.get(0), cog);
            Optional<Double> n2 = getNumericValue(args.get(1), cog);
            // If both args resolve to numbers, apply the operation
            return n1.isPresent() && n2.isPresent()
                    ? Optional.of(cog.G(op.apply(n1.get(), n2.get())))
                    : Optional.empty();
        }

        // Applies a binary double comparison, evaluating arguments if necessary.
        private static Optional<Atom> applyNumericComp(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            if (args.size() != 2) return Optional.empty();
            Optional<Double> n1 = getNumericValue(args.get(0), cog);
            Optional<Double> n2 = getNumericValue(args.get(1), cog);
            // If both resolve, return True or False symbol
            return n1.isPresent() && n2.isPresent()
                    ? Optional.of(op.test(n1.get(), n2.get()) ? SYMBOL_TRUE : SYMBOL_FALSE)
                    : Optional.empty();
        }

        // Extracts Double value from an Atom, evaluating it first if needed.
        private static Optional<Double> getNumericValue(@Nullable Atom atom, Cog cog) {
            if (atom == null) return Optional.empty();
            // If atom is already Grounded<Number>, use its value directly.
            if (atom instanceof Is<?> g && g.value() instanceof Number n) {
                return Optional.of(n.doubleValue());
            }
            // Otherwise, evaluate the atom and check if the *best* result is Grounded<Number>.
            return cog.evalBest(atom)
                    .filter(res -> res instanceof Is<?> g && g.value() instanceof Number)
                    .map(res -> ((Number)((Is<?>) res).value()).doubleValue());
        }

        // Extracts String value from an Atom, evaluating it first if needed.
        private static Optional<String> getStringValue(@Nullable Atom atom) {
            if (atom == null) return Optional.empty();
            // If atom is already Grounded<String>, use its value directly.
            if (atom instanceof Is<?> g && g.value() instanceof String s) {
                return Optional.of(s);
            }
            // Symbols evaluate to their name
            if (atom instanceof Sym s) {
                return Optional.of(s.name());
            }
            // Numbers convert to string
            if (atom instanceof Is<?> g && g.value() instanceof Number n) {
                return Optional.of(n.toString());
            }
            // Booleans convert to "True"/"False"
            if (SYMBOL_TRUE.equals(atom)) return Optional.of("True");
            if (SYMBOL_FALSE.equals(atom)) return Optional.of("False");

            // TODO: Add evaluation step like getNumericValue if needed?
            // For Concat, maybe direct conversion is sufficient.
            // If evaluation is needed:
            // return cog.evalBest(atom)... filter Is<String> ...
            return Optional.empty(); // Could not get string value
        }
    }
}
