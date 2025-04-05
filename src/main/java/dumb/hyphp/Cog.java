//package dumb.hyphp;
//
//import org.jetbrains.annotations.Nullable;
//
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicLong;
//import java.util.concurrent.atomic.AtomicReference;
//import java.util.function.Function;
//import java.util.function.Supplier;
//import java.util.random.RandomGenerator;
//import java.util.stream.Collectors;
//import java.util.stream.Stream;
//
//import static java.util.Collections.emptyList;
//import static java.util.Collections.emptyMap;
//
///**
// * Cog - Cognitive Logic Engine (Hyperon Synthesis)
// * <p>
// * A high-performance, compact cognitive architecture inspired by Hyperon/MeTTa, synthesizing
// * the best design elements from various prototypes. It features a unified AtomSpace,
// * homoiconic MeTTa evaluation, grounded atoms, probabilistic truth, and attention dynamics,
// * all within a single, optimized Java class structure.
// * <p>
// * ## Core Design Principles:
// * - **Unified Atom Representation:** All knowledge (symbols, variables, expressions, code, data)
// *   is represented as immutable `Atom` records (`Sym`, `Var`, `Expr`, `Gat`).
// * - **AtomSpace Repository:** Stores unique Atom instances and their associated mutable metadata (`Value`).
// * - **Homoiconic MeTTa Execution:** The `MeTTa` interpreter evaluates Atoms by matching and rewriting
// *   them according to equality rules (`=`) stored within the AtomSpace itself.
// * - **Atomic Metadata (`Value`):** An immutable `Value` record holds `Truth`, importance (`sti`, `lti`),
// *   and access time, managed atomically per Atom via `AtomicReference` for thread-safety and consistency.
// * - **Grounded Atoms (`Gat`):** Bridge to Java data and functionality, enabling interaction and extension.
// * - **Pattern Matching & Unification:** A core, efficient unification engine (`Unify`) supports queries
// *   and rule application using variables.
// * - **Importance Dynamics:** Attention allocation (STI/LTI) guides memory management (forgetting).
// * - **Consolidated & Optimized:** Single-file design, minimal code, modern Java features, concurrency primitives.
// *
// * @version 4.0 - Synthesis
// */
//public final class Cog {
//
//    // --- Configuration ---
//    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0;
//    private static final double TRUTH_MIN_CONFIDENCE = 0.01;
//    private static final Truth TRUTH_UNKNOWN = Truth.of(0.5, 0.1);
//    private static final Truth TRUTH_TRUE = Truth.of(1.0, 10.0);
//    private static final Truth TRUTH_FALSE = Truth.of(0.0, 10.0);
//
//    private static final int METTA_DEFAULT_DEPTH = 10;
//    private static final int METTA_MAX_RESULTS = 100;
//
//    private static final double IMP_INITIAL_STI = 0.1;
//    private static final double IMP_INITIAL_LTI_FACTOR = 0.1;
//    private static final double IMP_STI_DECAY = 0.05;
//    private static final double IMP_LTI_DECAY = 0.005;
//    private static final double IMP_STI_TO_LTI = 0.02;
//    private static final double IMP_BOOST_ACCESS = 0.05;
//    private static final double IMP_BOOST_REVISION = 0.4;
//    private static final double IMP_BOOST_GOAL = 0.9;
//    private static final double IMP_BOOST_PERCEPT = 0.7;
//    private static final double IMP_MIN_FORGET = 0.01;
//    private static final long FORGET_INTERVAL_MS = 10000;
//    private static final int FORGET_MAX_SIZE = 20000;
//    private static final int FORGET_TARGET_FACTOR = 80; // %
//
//    private static final int AGENT_DEFAULT_PERCEPT_COUNT = 5;
//    private static final double AGENT_DEFAULT_LEARN_COUNT = 1.0;
//    private static final double AGENT_RANDOM_ACTION_PROB = 0.05;
//
//    private static final String VAR_PREFIX = "$";
//    private static final Set<String> PROTECTED_SYMS = Set.of("=", ":", "->", "Type", "True", "False", "Self", "Goal", "Action", "If", "match", "add", "remove");
//
//    // --- Core Symbols (cached) ---
//    public static final Sym SYM_EQ = Sym.of("=");
//    public static final Sym SYM_COLON = Sym.of(":");
//    public static final Sym SYM_ARROW = Sym.of("->");
//    public static final Sym SYM_TYPE = Sym.of("Type");
//    public static final Sym SYM_TRUE = Sym.of("True");
//    public static final Sym SYM_FALSE = Sym.of("False");
//    public static final Sym SYM_SELF = Sym.of("Self");
//    public static final Sym SYM_IF = Sym.of("If");
//    public static final Sym SYM_MATCH = Sym.of("match");
//    public static final Sym SYM_ADD = Sym.of("add");
//    public static final Sym SYM_REMOVE = Sym.of("remove");
//    public static final Sym SYM_NIL = Sym.of("Nil"); // Placeholder for empty results maybe
//
//    // --- System Components ---
//    private final AtomSpace space;
//    private final MeTTa interpreter;
//    private final Agent agent;
//    private final ScheduledExecutorService scheduler;
//    private final AtomicLong time = new AtomicLong(0);
//
//    public Cog() {
//        this.space = new AtomSpace(time::get);
//        this.interpreter = new MeTTa(this.space);
//        this.agent = new Agent(this);
//        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
//            var t = new Thread(r, "CogMaint");
//            t.setDaemon(true);
//            return t;
//        });
//        scheduler.scheduleAtFixedRate(space::decayAndForget, FORGET_INTERVAL_MS, FORGET_INTERVAL_MS, TimeUnit.MILLISECONDS);
//        Bootstrap.initialize(this);
//        System.out.println(STR."\{Cog.class.getSimpleName()} Initialized.");
//    }
//
//    // --- Public API Facade ---
//    public AtomSpace space() { return space; }
//    public MeTTa interpreter() { return interpreter; }
//    public Agent agent() { return agent; }
//    public long time() { return time.incrementAndGet(); } // Use this to advance time explicitly
//
//    public Sym sym(String name) { return space.addAtom(Sym.of(name)); }
//    public static Var var(String name) { return space.addAtom(Var.of(name)); }
//    public Expr expr(Atom... children) { return space.addAtom(Expr.of(children)); }
//    public Expr expr(List<Atom> children) { return space.addAtom(Expr.of(children)); }
//    public <A extends Atom> A add(A atom) { return space.addAtom(atom); }
//    public Gat<Object> val(Object value) { return space.groundedValue(value); } // Simple grounded value
//    public Gat<Function<MeTTa.ExecContext, Stream<Atom>>> func(String name, Function<MeTTa.ExecContext, Stream<Atom>> func) { return space.groundedFunc(name, func); }
//
//    public Expr addEq(Atom lhs, Atom rhs) { return add(expr(SYM_EQ, lhs, rhs)); }
//    public Expr addType(Atom instance, Atom type) { return add(expr(SYM_COLON, instance, type)); }
//
//    public Stream<Atom> evaluate(Atom atom) { return interpreter.evaluate(atom); }
//    public Stream<Bindings> query(Atom pattern) { return space.query(pattern); }
//    public Optional<Atom> get(Atom atom) { return space.getAtom(atom); }
//    public Optional<Value> getValue(Atom atom) { return space.getValue(atom); }
//    public void updateValue(Atom atom, Function<Value, Value> updater) { space.updateValue(atom, updater); }
//
//    public void runAgent(Environment env, Atom goal, int maxCycles) { agent.run(env, goal, maxCycles); }
//    public void shutdown() { // Cleanly shut down the scheduler
//        scheduler.shutdown();
//        try { if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow(); }
//        catch (InterruptedException e) { scheduler.shutdownNow(); Thread.currentThread().interrupt(); }
//        System.out.println("Cog scheduler shut down.");
//    }
//
//    // --- Main Demo ---
//    public static void main(String[] args) {
//        var cog = new Cog();
//        try {
//            System.out.println("\n--- Cog Synthesis Demonstration ---");
//
//            // 1. Basic Evaluation & Grounded Atoms
//            System.out.println("\n[1] Basic Evaluation & Grounded Atoms:");
//            cog.addEq(cog.expr(cog.sym("+"), cog.var("x"), cog.var("y")), cog.expr(cog.sym("GroundedPlus"), cog.var("x"), cog.var("y")));
//            var sumExpr = cog.expr(cog.sym("+"), cog.val(10.0), cog.val(5.5));
//            System.out.println(STR."Evaluating: \{sumExpr}");
//            cog.evaluate(sumExpr).forEach(r -> System.out.println(STR." -> Result: \{r}")); // Expect Gat(15.5)
//
//            var ifExpr = cog.expr(cog.SYM_IF, cog.SYM_TRUE, cog.sym("ThenBranch"), cog.sym("ElseBranch"));
//            System.out.println(STR."Evaluating: \{ifExpr}");
//            cog.evaluate(ifExpr).forEach(r -> System.out.println(STR." -> Result: \{r}")); // Expect Sym(ThenBranch)
//
//            // 2. Pattern Matching & Simple Inference
//            System.out.println("\n[2] Pattern Matching & Simple Inference:");
//            var socrates = cog.sym("Socrates"); var human = cog.sym("Human"); var mortal = cog.sym("Mortal");
//            cog.addType(socrates, human);
//            cog.addEq(cog.expr(mortal, cog.var("x")), cog.expr(cog.SYM_COLON, cog.var("x"), human)); // (= (Mortal $x) (: $x Human))
//            System.out.println(STR."Is Socrates Mortal? Query: (Mortal Socrates)");
//            cog.evaluate(cog.expr(mortal, socrates)).forEach(r -> System.out.println(STR." -> Result: \{r} (Truth: \{cog.getValue(r).map(Value::truth).orElse(TRUTH_UNKNOWN)})")); // Should evaluate to (: Socrates Human), ideally True if type exists
//
//            System.out.println("\nQuerying for Humans:");
//            cog.query(cog.expr(cog.SYM_COLON, cog.var("who"), human))
//               .forEach(b -> System.out.println(STR." -> Found: \{b}"));
//
//            // 3. Agent Simulation
//            System.out.println("\n[3] Agent Simulation:");
//            var world = new SimpleGridWorld(cog, 3, 2, 2); // 3x3, goal at (2,2)
//            var goalAtom = cog.expr(cog.sym("At"), cog.SYM_SELF, cog.sym("Pos_2_2")); // Goal: (At Self Pos_2_2)
//            cog.runAgent(world, goalAtom, 15);
//
//            // 4. Forgetting Check
//            System.out.println("\n[4] Forgetting Check:");
//            for (int i = 0; i < 50; i++) { // Add some temporary atoms
//                Atom temp = cog.sym(STR."TempAtom\{i}");
//                cog.updateValue(temp, v -> v.withImportance(0.001, 0.0001));
//            }
//            var sizeBefore = cog.space.size();
//            System.out.println(STR."Atomspace size before wait: \{sizeBefore}");
//            System.out.println("Waiting for maintenance cycle...");
//            try { Thread.sleep(FORGET_INTERVAL_MS + 1000); } catch (InterruptedException ignored) {}
//            var sizeAfter = cog.space.size();
//            System.out.println(STR."Atomspace size after wait: \{sizeAfter} (Removed: \{sizeBefore - sizeAfter})");
//
//        } catch (Exception e) {
//            System.err.println("\n--- ERROR during demonstration ---");
//            e.printStackTrace();
//        } finally {
//            cog.shutdown();
//        }
//        System.out.println("\n--- Cog Synthesis Demonstration Finished ---");
//    }
//
//    // =======================================================================
//    // === Core Data Structures: Atom & Value ================================
//    // =======================================================================
//
//    /** Base type for all elements in the AtomSpace. Atoms are immutable records. */
//    public sealed interface Atom permits Sym, Var, Expr, Gat { String id(); } // Canonical identifier
//
//    /** Symbol Atom: Represents named constants, concepts, functions. Interned for efficiency. */
//    public record Sym(String name) implements Atom {
//        private static final ConcurrentMap<String, Sym> INTERN_CACHE = new ConcurrentHashMap<>();
//        public static Sym of(String name) { return INTERN_CACHE.computeIfAbsent(Objects.requireNonNull(name), Sym::new); }
//        @Override public String id() { return name; }
//        @Override public String toString() { return name; }
//    }
//
//    /** Variable Atom: Used in patterns and rules. */
//    public record Var(String name) implements Atom {
//        public static Var of(String name) { return new Var(name.startsWith(VAR_PREFIX) ? name.substring(1) : name); } // Ensure no prefix internally
//        @Override public String id() { return VAR_PREFIX + name; }
//        @Override public String toString() { return id(); }
//    }
//
//    /** Expression Atom: Represents composite structures (MeTTa lists/trees). */
//    public class Expr implements Atom {
//        private static final ConcurrentMap<List<Atom>, Expr> INTERN_CACHE = new ConcurrentHashMap<>();
//
//        private final List<Atom> children;
//        // Cache computed ID for performance
//        private final String computedId;
//
//        // Private constructor, use factory methods
//        private Expr(List<Atom> children) {
//            this.children = children; // Assumes input list is already immutable copy
//            this.computedId = "(" + children.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")";
//        }
//
//        public static Expr of(Atom... children) { return of(Arrays.asList(children)); }
//        public static Expr of(List<Atom> children) {
//            // Ensure children list is immutable before using as cache key
//            List<Atom> immutableChildren = List.copyOf(Objects.requireNonNull(children));
//            // Use computeIfAbsent for interned expressions
//            return INTERN_CACHE.computeIfAbsent(immutableChildren, Expr::new);
//        }
//
//        @Override public String id() { return computedId; }
//        @Override public String toString() { return computedId; }
//        public Atom head() { return children.isEmpty() ? null : children.get(0); }
//        public List<Atom> tail() { return children.isEmpty() ? emptyList() : children.subList(1, children.size()); }
//        @Override public boolean equals(Object o) { return this == o || (o instanceof Expr e && computedId.equals(e.computedId)); } // ID based equality after interning
//        @Override public int hashCode() { return computedId.hashCode(); }
//    }
//
//    /** Grounded Atom: Wraps external Java objects or executable functions. */
//    public record Gat<T>(String name, @Nullable T value, @Nullable Function<MeTTa.ExecContext, Stream<Atom>> executor) implements Atom {
//        // Constructor for data
//        public Gat(String name, T value) { this(name, value, null); }
//        // Constructor for function
//        public Gat(String name, Function<MeTTa.ExecContext, Stream<Atom>> executor) { this(name, null, Objects.requireNonNull(executor)); }
//
//        @Override public String id() { return name; } // Use provided name as ID for grounded atoms
//        public boolean isFunction() { return executor != null; }
//        @Override public String toString() { return name; } // Represent by name for clarity
//    }
//
//    /** Represents probabilistic truth (Strength, Count/Evidence). Immutable. */
//    public record Truth(double strength, double count) {
//        public static Truth of(double s, double c) { return new Truth(s, c); }
//        public Truth { // Validate inputs in canonical constructor
//            strength = Math.max(0.0, Math.min(1.0, strength));
//            count = Math.max(0.0, count);
//        }
//        public double confidence() { return count / (count + TRUTH_DEFAULT_SENSITIVITY); }
//        public Truth merge(Truth other) { // Bayesian merge based on counts
//            if (other == null || other.count == 0) return this;
//            if (this.count == 0) return other;
//            double totalCount = this.count + other.count;
//            return new Truth((this.strength * this.count + other.strength * other.count) / totalCount, totalCount);
//        }
//        @Override public String toString() { return String.format("<s:%.2f,c:%.1f>", strength, count); }
//    }
//
//    /** Immutable record holding all metadata for an Atom. */
//    public record Value(Truth truth, double sti, double lti, long lastAccessTime) {
//        // Default value for newly created atoms
//        public static Value initialValue(long time) {
//            return new Value(TRUTH_UNKNOWN, IMP_INITIAL_STI, IMP_INITIAL_STI * IMP_INITIAL_LTI_FACTOR, time);
//        }
//        public Value withTruth(Truth t) { return new Value(t, sti, lti, lastAccessTime); }
//        public Value withTime(long t) { return new Value(truth, sti, lti, t); }
//        public Value withImportance(double s, double l) { return new Value(truth, s, l, lastAccessTime); }
//
//        /** Calculate combined importance score, considering recency. */
//        public double currentImportance(long now) {
//            double timeSinceAccess = Math.max(0, now - lastAccessTime);
//            double recency = Math.exp(-timeSinceAccess / (FORGET_INTERVAL_MS * 3.0)); // Decay over ~3 cycles
//            return Math.max(0, Math.min(1, (sti * recency * 0.6 + lti * 0.4) * truth.confidence())); // Modulated by confidence
//        }
//
//        /** Returns a new Value with boosted importance. */
//        public Value boost(double amount, long now) {
//            if (amount <= 0) return this.withTime(now);
//            double newSti = Math.min(1.0, sti + amount);
//            double ltiBoost = newSti * IMP_STI_TO_LTI * amount; // Learn more if boost is large
//            double newLti = Math.min(1.0, lti + ltiBoost);
//            return new Value(truth, newSti, newLti, now);
//        }
//
//        /** Returns a new Value with decayed importance. */
//        public Value decay(long now) {
//            double decayedSti = sti * (1.0 - IMP_STI_DECAY);
//            double decayedLti = lti * (1.0 - IMP_LTI_DECAY) + decayedSti * IMP_STI_TO_LTI * 0.1; // Slow learn during decay
//            return new Value(truth, Math.max(0, decayedSti), Math.max(0, Math.min(1, decayedLti)), lastAccessTime); // Keep last access time
//        }
//        @Override public String toString() { return String.format("%s Imp<s:%.2f,l:%.2f,t:%d>", truth, sti, lti, lastAccessTime); }
//    }
//
//    // --- Unification & Bindings ---
//
//    /** Represents variable bindings during unification. Immutable. */
//    public record Bindings(Map<Var, Atom> map) {
//        public static final Bindings EMPTY = new Bindings(emptyMap());
//        public Bindings { map = Map.copyOf(map); } // Ensure immutable map
//
//        public Optional<Atom> get(Var var) { return Optional.ofNullable(map.get(var)); }
//        public boolean isEmpty() { return map.isEmpty(); }
//
//        /** Creates new bindings by adding one mapping, checking for consistency. */
//        public Optional<Bindings> bind(Var var, Atom value, Unify.Context ctx) {
//            Atom existing = map.get(var);
//            if (existing != null) { // Variable already bound
//                return Unify.unify(existing, value, this, ctx).isPresent() ? Optional.of(this) : Optional.empty(); // Check consistency
//            }
//            if (Unify.occursCheck(var, value, this)) return Optional.empty(); // Occurs check
//            Map<Var, Atom> newMap = new HashMap<>(map); newMap.put(var, value);
//            return Optional.of(new Bindings(newMap));
//        }
//
//        /** Merges two sets of bindings, checking for consistency. */
//        public Optional<Bindings> merge(Bindings other, Unify.Context ctx) {
//            if (other.isEmpty()) return Optional.of(this); if (this.isEmpty()) return Optional.of(other);
//            Bindings current = this;
//            for (var entry : other.map.entrySet()) {
//                Optional<Bindings> next = current.bind(entry.getKey(), entry.getValue(), ctx);
//                if (next.isEmpty()) return Optional.empty();
//                current = next.get();
//            }
//            return Optional.of(current);
//        }
//        @Override public String toString() { return map.toString(); }
//    }
//
//    /** Unification logic. */
//    public static class Unify {
//        record Context() {} // Placeholder for future context (e.g., type checking space)
//        record StackFrame(Atom pattern, Atom instance) {} // For iterative unification
//
//        /** Unifies pattern against instance, respecting initial bindings. */
//        static Optional<Bindings> unify(Atom pattern, Atom instance, Bindings bindings, Context ctx) {
//            Deque<StackFrame> stack = new ArrayDeque<>();
//            stack.push(new StackFrame(pattern, instance));
//            Optional<Bindings> currentBindingsOpt = Optional.of(bindings);
//
//            while (!stack.isEmpty() && currentBindingsOpt.isPresent()) {
//                StackFrame frame = stack.pop();
//                Bindings currentBindings = currentBindingsOpt.get();
//                Atom p = substitute(frame.pattern, currentBindings); // Apply bindings before comparing
//                Atom i = substitute(frame.instance, currentBindings);
//
//                if (p.equals(i)) continue; // Match
//
//                if (p instanceof Var pVar) {
//                    currentBindingsOpt = currentBindings.bind(pVar, i, ctx); continue;
//                }
//                if (i instanceof Var iVar) {
//                    currentBindingsOpt = currentBindings.bind(iVar, p, ctx); continue;
//                }
//
//                // Must be same type and structure if not variables
//                if (!(p instanceof Expr pExpr && i instanceof Expr iExpr)) return Optional.empty();
//
//                List<Atom> pChildren = pExpr.children();
//                List<Atom> iChildren = iExpr.children();
//                if (pChildren.size() != iChildren.size()) return Optional.empty();
//
//                // Push children pairs in reverse order to process head first
//                for (int j = pChildren.size() - 1; j >= 0; j--) {
//                    stack.push(new StackFrame(pChildren.get(j), iChildren.get(j)));
//                }
//            }
//            return currentBindingsOpt;
//        }
//
//        /** Applies bindings to substitute variables recursively. */
//        static Atom substitute(Atom atom, Bindings bindings) {
//            if (bindings.isEmpty()) return atom;
//            return switch (atom) {
//                case Var v -> bindings.get(v).map(val -> substitute(val, bindings)).orElse(v);
//                case Expr e -> {
//                    List<Atom> children = e.children();
//                    List<Atom> newChildren = null; // Lazily allocated
//                    for (int i = 0; i < children.size(); i++) {
//                        Atom child = children.get(i);
//                        Atom substituted = substitute(child, bindings);
//                        if (child != substituted) { // Check object identity for change
//                            if (newChildren == null) newChildren = new ArrayList<>(children.subList(0, i));
//                            newChildren.add(substituted);
//                        } else if (newChildren != null) {
//                            newChildren.add(child);
//                        }
//                    }
//                    yield newChildren == null ? e : Expr.of(newChildren); // Return new Expr only if changed
//                }
//                default -> atom; // Sym or Gat
//            };
//        }
//
//        /** Simple occurs check to prevent infinite recursion during binding. */
//        static boolean occursCheck(Var var, Atom expression, Bindings bindings) {
//            return switch (substitute(expression, bindings)) { // Check against substituted expression
//                case Var v -> v.equals(var);
//                case Expr e -> e.children().stream().anyMatch(child -> occursCheck(var, child, bindings));
//                default -> false;
//            };
//        }
//    }
//
//    // --- AtomSpace & Interpreter ---
//
//    /** Result of a successful pattern match query. */
//    public record QueryResult(Atom resultAtom, Bindings bindings) {}
//
//    /** Stores Atoms and their metadata, provides querying and evaluation capabilities. */
//    public static class AtomSpace {
//        private final ConcurrentMap<Atom, AtomicReference<Value>> storage = new ConcurrentHashMap<>(16384);
//        // Simple index: Head Atom -> Set<Expr> (using Atom object directly as key)
//        private final ConcurrentMap<Atom, ConcurrentSkipListSet<Atom>> indexByHead = new ConcurrentHashMap<>(1024);
//        private final Supplier<Long> timeSource;
//
//        public AtomSpace(Supplier<Long> timeSource) { this.timeSource = timeSource; }
//
//        /** Adds an Atom, initializing metadata if new, or updates access time. */
//        public <A extends Atom> A addAtom(@Nullable A atom) {
//            if (atom == null) throw new NullPointerException("Cannot add null atom");
//            long now = timeSource.get();
//            AtomicReference<Value> valueRef = storage.computeIfAbsent(atom, k -> {
//                updateIndex(atom); // Index only truly new atoms
//                return new AtomicReference<>(Value.initialValue(now));
//            });
//            valueRef.updateAndGet(v -> v.withTime(now)); // Always update access time
//            // Atom interning (Sym, Expr) ensures the returned object is the canonical one
//            @SuppressWarnings("unchecked") A canonical = (A) atom;
//            return canonical;
//        }
//
//        /** Retrieves an Atom if present, returning the canonical instance. */
//        public Optional<Atom> getAtom(Atom atom) {
//            AtomicReference<Value> valueRef = storage.get(atom);
//            if (valueRef != null) {
//                valueRef.updateAndGet(v -> v.boost(IMP_BOOST_ACCESS, timeSource.get())); // Boost on access
//                return Optional.of(atom); // Return input atom as it's equal to stored one
//            }
//            return Optional.empty();
//        }
//
//        /** Retrieves an Atom by its String ID. Less efficient than getAtom(Atom). */
//        public Optional<Atom> getAtom(String id) {
//            // This requires finding the Atom object matching the ID. O(N) without an ID index.
//            // If IDs are structured (e.g., Symbol name), we can optimize lookups for certain types.
//            if (id.startsWith(VAR_PREFIX)) return Optional.of(Var.of(id));
//            if (!id.startsWith("(")) { // Assume Symbol if no parentheses
//                Sym sym = Sym.of(id);
//                return storage.containsKey(sym) ? getAtom(sym) : Optional.empty();
//            }
//            // TODO: Parse expression ID and look up? Complex. Avoid ID lookups for expressions if possible.
//            // Fallback iteration (slow):
//            for (Atom atom : storage.keySet()) {
//                if (atom.id().equals(id)) return getAtom(atom);
//            }
//            return Optional.empty();
//        }
//
//        /** Retrieves the current Value metadata for an Atom. */
//        public Optional<Value> getValue(Atom atom) {
//            AtomicReference<Value> ref = storage.get(atom);
//            return (ref != null) ? Optional.of(ref.get()) : Optional.empty();
//        }
//
//        /** Atomically updates the Value metadata for an Atom. */
//        public void updateValue(Atom atom, Function<Value, Value> updater) {
//            AtomicReference<Value> ref = storage.get(atom);
//            if (ref != null) {
//                ref.updateAndGet(current -> {
//                    Value updated = updater.apply(current);
//                    double boost = IMP_BOOST_ACCESS + revisionBoost(current.truth, updated.truth);
//                    return updated.boost(boost, timeSource.get()).withTime(timeSource.get()); // Ensure time is updated
//                });
//            } else { // Atom might not exist yet, add it with updated default
//                addAtom(atom); // Add it first
//                ref = storage.get(atom); // Get the reference
//                if (ref != null) ref.updateAndGet(v -> updater.apply(v).boost(IMP_BOOST_ACCESS, timeSource.get()).withTime(timeSource.get()));
//            }
//        }
//
//        // Convenience factories ensuring atoms are managed by this space
//        public Sym symbol(String name) { return addAtom(Sym.of(name)); }
//        public Var variable(String name) { return addAtom(Var.of(name)); }
//        public Expr expression(Atom... children) { return addAtom(Expr.of(children)); }
//        public Expr expression(List<Atom> children) { return addAtom(Expr.of(children)); }
//        public <T> Gat<Object> groundedValue(T value) { // Simple value wrapper
//             String id = STR."Val<\{value.getClass().getSimpleName()}:\{value}>"; // Basic ID
//             return addAtom(new Gat<>(id, value));
//        }
//        public Gat<Function<MeTTa.ExecContext, Stream<Atom>>> groundedFunc(String name, Function<MeTTa.ExecContext, Stream<Atom>> func) {
//             return addAtom(new Gat<>(name, func));
//        }
//
//        /** Performs pattern matching query. */
//        public Stream<Bindings> query(Atom pattern) {
//            return Unify.unify(pattern, var("_DUMMY_INSTANCE_VAR_"), Bindings.EMPTY, new Unify.Context()) // Unify pattern with a variable
//                .stream() // Should produce one binding if pattern has no vars, multiple if it does
//                .flatMap(bindings -> findMatchesForPattern(pattern, bindings)); // Find instances matching the (partially) bound pattern
//        }
//
//        // Internal helper for query - finds matching instances based on pattern and initial bindings
//        private Stream<Bindings> findMatchesForPattern(Atom pattern, Bindings patternBindings) {
//            // Use indices to find candidates based on concrete parts of the pattern
//            Set<Atom> candidates = findCandidates(pattern, patternBindings);
//            var ctx = new Unify.Context(); // Create unification context
//
//            return candidates.stream()
//                .map(this::getAtom) // Retrieve canonical atom & boost importance
//                .filter(Optional::isPresent)
//                .map(Optional::get)
//                .filter(candidate -> getValue(candidate).map(v -> v.truth.confidence() >= TRUTH_MIN_CONFIDENCE).orElse(false)) // Check confidence
//                .flatMap(candidate -> Unify.unify(pattern, candidate, patternBindings, ctx).stream()); // Attempt full unification
//        }
//
//        // Find candidate atoms using indices based on concrete parts of the pattern
//        private Set<Atom> findCandidates(Atom pattern, Bindings bindings) {
//            Set<Atom> potentialCandidates = ConcurrentHashMap.newKeySet();
//            // Resolve the pattern partially using initial bindings to find concrete parts
//            Atom resolvedPattern = Unify.substitute(pattern, bindings);
//
//            if (resolvedPattern instanceof Expr expr && expr.head() != null && !(expr.head() instanceof Var)) {
//                // If head is concrete, use head index
//                potentialCandidates.addAll(indexByHead.getOrDefault(expr.head(), Set.of()));
//            } else if (resolvedPattern instanceof Sym || resolvedPattern instanceof Gat) {
//                // If pattern is a concrete symbol or grounded atom, look for it directly
//                if (storage.containsKey(resolvedPattern)) potentialCandidates.add(resolvedPattern);
//                 // Also check index? Maybe it appears inside other expressions?
//            }
//
//            // If index lookup yielded few results or pattern is very generic, consider broader search?
//            // For now, just return candidates found via head index or direct match.
//            // A full query implementation would traverse the pattern and combine index results.
//            if(potentialCandidates.isEmpty() && !(resolvedPattern instanceof Expr)) {
//                // No candidates from index, and it's not an expression -> maybe a direct match or variable?
//                // This path is tricky, might need full scan for generic variable patterns.
//                // Let's assume the caller handles variable patterns appropriately or the index helps.
//            }
//
//             // If still empty, fallback (carefully) - potentially scan all atoms (SLOW)
//             if (potentialCandidates.isEmpty() && storage.size() < 5000) { // Heuristic limit for full scan fallback
//                 potentialCandidates.addAll(storage.keySet());
//             } else if (potentialCandidates.isEmpty()) {
//                 // Avoid full scan on large spaces if index yields nothing
//                 // System.err.println("WARN: Query pattern potentially inefficient, index yielded no candidates: " + pattern);
//             }
//
//            return potentialCandidates;
//        }
//
//        /** Decay importance and forget low-importance atoms. */
//        synchronized void decayAndForget() {
//            long now = timeSource.get();
//            int initialSize = storage.size();
//            if (initialSize == 0) return;
//
//            List<Atom> toRemove = new CopyOnWriteArrayList<>(); // Use COW list if removing during iteration
//            storage.forEach((atom, valueRef) -> {
//                Value current = valueRef.get();
//                Value decayed = current.decay(now);
//                if (decayed != current) valueRef.set(decayed); // Update only if changed
//
//                // Check forget threshold *after* decay
//                if (decayed.currentImportance(now) < IMP_MIN_FORGET) {
//                    boolean isProtected = (atom instanceof Sym s && PROTECTED_SYMS.contains(s.name())) || (atom instanceof Var);
//                    if (!isProtected) toRemove.add(atom);
//                }
//            });
//
//            int removedCount = 0;
//            if (!toRemove.isEmpty()) {
//                int targetSize = (FORGET_MAX_SIZE * FORGET_TARGET_FACTOR) / 100;
//                boolean memoryPressure = initialSize > FORGET_MAX_SIZE;
//                int removalTargetCount = memoryPressure ? Math.max(0, initialSize - targetSize) : toRemove.size();
//
//                // Sort candidates to remove least important first (if removing selectively)
//                if (memoryPressure && toRemove.size() > removalTargetCount) {
//                     toRemove.sort(Comparator.comparingDouble(atom -> getValue(atom).map(v -> v.currentImportance(now)).orElse(1.0)));
//                }
//
//                for (int i = 0; i < Math.min(toRemove.size(), removalTargetCount); i++) {
//                    if (removeAtomInternal(toRemove.get(i))) removedCount++;
//                }
//            }
//
//            if (removedCount > 0) System.out.printf("AtomSpace Maintenance: Removed %d atoms. Size %d -> %d.%n", removedCount, initialSize, storage.size());
//        }
//
//        // --- Internal Helpers ---
//        private void updateIndex(Atom atom) {
//             if (atom instanceof Expr e && e.head() != null) {
//                 indexByHead.computeIfAbsent(e.head(), k -> ConcurrentHashMap.newKeySet()).add(atom);
//             }
//        }
//
//        private boolean removeAtomInternal(Atom atom) {
//             if (storage.remove(atom) != null) {
//                 if (atom instanceof Expr e && e.head() != null) {
//                     indexByHead.computeIfPresent(e.head(), (k, v) -> { v.remove(atom); return v.isEmpty() ? null : v; });
//                 }
//                 return true;
//             }
//             return false;
//        }
//
//        private double revisionBoost(Truth prev, Truth next) {
//             if (prev == null || next == null) return 0;
//             double confChange = Math.abs(next.confidence() - prev.confidence());
//             return Math.min(IMP_BOOST_REVISION, confChange * 0.5) * next.confidence(); // Boost based on confidence change
//        }
//
//        public int size() { return storage.size(); }
//    }
//
//    /** MeTTa Interpreter: Evaluates Atoms via rule rewriting. */
//    public static class MeTTa {
//        private final AtomSpace space;
//        public record ExecContext(List<Atom> args, Bindings bindings) {} // Context for GroundedAtom execution
//
//        public MeTTa(AtomSpace space) { this.space = space; }
//
//        /** Evaluate an Atom with default depth. */
//        public Stream<Atom> evaluate(Atom atom) { return evaluate(atom, METTA_DEFAULT_DEPTH); }
//
//        /** Evaluate an Atom with specified max recursion depth. */
//        public Stream<Atom> evaluate(Atom atom, int maxDepth) {
//            return evaluateRecursive(atom, Bindings.EMPTY, maxDepth, new HashSet<>())
//                   .distinct() // Ensure unique results
//                   .limit(METTA_MAX_RESULTS); // Limit overall results
//        }
//
//        private Stream<Atom> evaluateRecursive(Atom atom, Bindings bindings, int depth, Set<Atom> visited) {
//            Atom currentAtom = Unify.substitute(atom, bindings); // Apply bindings first
//            if (depth <= 0 || !visited.add(currentAtom)) { // Check depth & cycles
//                return Stream.of(currentAtom); // Return current atom if limit/cycle
//            }
//
//            Stream<Atom> resultStream = Stream.empty();
//            try {
//                resultStream = switch (currentAtom) {
//                    // Variables and Grounded Data evaluate to themselves
//                    case Var v -> Stream.of(v);
//                    case Gat<?> g when !g.isFunction() -> Stream.of(g);
//                    // Symbols evaluate to themselves unless equality rules match
//                    case Sym s -> handleEquality(s, bindings, depth, visited);
//                    // Expressions are the main target for evaluation
//                    case Expr e -> {
//                        // 1. Handle grounded function execution if head is grounded
//                        if (e.head() instanceof Gat<?> headFunc && headFunc.isFunction()) {
//                            yield handleGroundedExec(e, headFunc, bindings, depth, visited);
//                        }
//                        // 2. Otherwise, handle via equality rules
//                        yield handleEquality(e, bindings, depth, visited);
//                    }
//                    // Grounded function evaluated directly? Return itself? Or error?
//                    case Gat<?> g when g.isFunction() -> Stream.of(g); // Treat standalone func as self for now
//                };
//            } finally {
//                visited.remove(currentAtom); // Backtrack visited set for this path
//            }
//            return resultStream;
//        }
//
//        // Handles evaluation by trying to match equality rules (= target $Result)
//        private Stream<Atom> handleEquality(Atom target, Bindings bindings, int depth, Set<Atom> visited) {
//            Var resultVar = Var.of("_Result"); // Standard temporary variable
//            Expr query = Expr.of(SYM_EQ, target, resultVar);
//
//            // Find matching equality rules in the space
//            Stream<Atom> results = space.query(query)
//                .flatMap(matchBindings ->
//                    matchBindings.get(resultVar) // Get the bound result template
//                        .map(template -> {
//                            // Merge query bindings with current evaluation bindings
//                            Bindings merged = bindings.merge(matchBindings, new Unify.Context()).orElse(bindings);
//                            // Recursively evaluate the template with merged bindings
//                            return evaluateRecursive(template, merged, depth - 1, new HashSet<>(visited));
//                        })
//                        .orElse(Stream.empty()) // Should not happen if query binds $Result
//                );
//
//            // If no equality rules matched, the atom evaluates to itself (potentially after evaluating children)
//            return results.findFirst().isPresent() ? results : fallbackEvaluateChildren(target, bindings, depth, visited);
//        }
//
//        // Fallback if no equality rule matches: evaluate children (applicative order)
//        private Stream<Atom> fallbackEvaluateChildren(Atom atom, Bindings bindings, int depth, Set<Atom> visited) {
//            if (!(atom instanceof Expr e)) return Stream.of(atom); // Only expressions have children to evaluate
//
//            List<Atom> children = e.children();
//            List<Atom> evaluatedChildren = new ArrayList<>(children.size());
//            boolean changed = false;
//            for (Atom child : children) {
//                // Evaluate each child, taking the first result for simplicity
//                Atom evaluatedChild = evaluateRecursive(child, bindings, depth - 1, new HashSet<>(visited))
//                                          .findFirst()
//                                          .orElse(Unify.substitute(child, bindings)); // Fallback to substituted child
//                evaluatedChildren.add(evaluatedChild);
//                if (!evaluatedChild.equals(child)) changed = true;
//            }
//            // If children evaluation resulted in changes, return the new expression, otherwise the original
//            return Stream.of(changed ? Expr.of(evaluatedChildren) : atom);
//        }
//
//        // Handles execution of grounded functions found as the head of an expression
//        private Stream<Atom> handleGroundedExec(Expr expr, Gat<?> headFunc, Bindings bindings, int depth, Set<Atom> visited) {
//            // Eagerly evaluate arguments
//            List<Atom> args = expr.tail();
//            List<Atom> evaluatedArgs = new ArrayList<>(args.size());
//            for (Atom arg : args) {
//                // Take first result of arg evaluation for simplicity
//                Atom evaluated = evaluateRecursive(arg, bindings, depth - 1, new HashSet<>(visited))
//                                   .findFirst().orElse(Unify.substitute(arg, bindings));
//                evaluatedArgs.add(evaluated);
//            }
//
//            // Execute the grounded function
//            var execContext = new ExecContext(evaluatedArgs, bindings);
//            @SuppressWarnings("unchecked") // Type checked by isFunction and constructor logic
//            var executor = (Function<ExecContext, Stream<Atom>>) headFunc.executor();
//
//            // Execute and recursively evaluate the results
//            return executor.apply(execContext)
//                .flatMap(result -> evaluateRecursive(result, bindings, depth - 1, new HashSet<>(visited)));
//        }
//    }
//
//    // --- Agent & Environment ---
//    public interface Environment {
//        List<Atom> perceive(Cog cog); // Return list of Atoms representing current percepts
//        List<Atom> availableActions(Cog cog); // Return list of action Atoms agent can perform
//        EnvironmentOutcome executeAction(Cog cog, Atom action); // Execute action, return outcome
//        boolean isRunning(); // Check if the simulation/environment should continue
//    }
//    public record EnvironmentOutcome(List<Atom> newPercepts, double reward) {}
//
//    public class Agent {
//        private final AtomSpace space;
//        private final MeTTa interpreter;
//        private final RandomGenerator random = RandomGenerator.getDefault();
//        private Atom currentState = null; // Atom representing the agent's current perceived state
//
//        // Agent-specific symbols (could be passed in or discovered)
//        private final Sym STATE = Sym.of("State");
//        private final Sym HAS = Sym.of("Has"); // e.g. (Has Self Key)
//        private final Sym ACTION = Sym.of("Action"); // e.g., (Action Move North)
//        private final Sym UTILITY = Sym.of("Utility");
//        private final Sym GOAL = Sym.of("Goal");
//        private final Sym ACHIEVES = Sym.of("Achieves"); // e.g. (Achieves (Action A) GoalState)
//
//        public Agent() { this.space = Cog.this.space; this.interpreter = Cog.this.interpreter; }
//
//        public void run(Environment env, Atom goalPattern, int maxCycles) {
//            System.out.println("\n--- Agent Run Start ---");
//            Atom goalAtom = space.addAtom(goalPattern);
//            updateValue(goalAtom, v -> v.boost(IMP_BOOST_GOAL, Cog.this.time()));
//            System.out.println(STR."Agent Goal: \{goalAtom}");
//
//            currentState = processPerception(env.perceive(Cog.this));
//
//            for (int cycle = 0; cycle < maxCycles && env.isRunning(); cycle++) {
//                long time = Cog.this.time(); // Use Cog's time
//                System.out.println(STR."\n--- Agent Cycle \{cycle + 1} (Time: \{time}) ---");
//                System.out.println(STR."Current State: \{currentState}");
//
//                if (isGoalAchieved(goalAtom)) { System.out.println("*** Agent: Goal Achieved! ***"); break; }
//
//                Optional<Atom> actionOpt = selectAction(env.availableActions(Cog.this), goalAtom);
//
//                if (actionOpt.isPresent()) {
//                    Atom action = actionOpt.get();
//                    System.out.println(STR."Selected Action: \{action}");
//                    EnvironmentOutcome outcome = env.executeAction(Cog.this, action); // Pass action symbol/expr
//                    System.out.printf("Action Outcome - Reward: %.2f%n", outcome.reward());
//                    Atom nextState = processPerception(outcome.newPercepts());
//                    learn(currentState, action, nextState, outcome.reward());
//                    currentState = nextState;
//                } else { System.out.println("Agent: No action selected."); }
//            }
//            System.out.println("--- Agent Run Finished ---");
//        }
//
//        private boolean isGoalAchieved(Atom goalPattern) {
//            // Check if the current state satisfies the goal pattern via query/unification
//            return space.query(goalPattern).findAny().isPresent(); // Simple check: does goal pattern exist/match?
//            // More robust: Evaluate a goal predicate: evaluate(expr(IsGoalMet, currentState)) == TRUE
//        }
//
//        private Atom processPerception(List<Atom> percepts) {
//            List<String> factIds = Collections.synchronizedList(new ArrayList<>());
//            percepts.forEach(p -> {
//                Atom atom = space.addAtom(p);
//                updateValue(atom, v -> v.withTruth(TRUTH_TRUE.merge(v.truth)).boost(IMP_BOOST_PERCEPT, Cog.this.time()));
//                factIds.add(atom.id());
//            });
//            Collections.sort(factIds); // Canonical representation
//            String stateHash = Integer.toHexString(String.join(";", factIds).hashCode());
//            return space.addAtom(space.symbol(STR."State_\{stateHash}")); // Represent state by a unique symbol
//        }
//
//        private Optional<Atom> selectAction(List<Atom> availableActions, Atom goal) {
//            if (availableActions.isEmpty()) return Optional.empty();
//
//            // 1. Planning: Query for actions achieving the goal
//            // Query: (Achieves $action GoalPattern)
//            Var actionVar = space.variable("action");
//            Expr planQuery = space.expression(ACHIEVES, actionVar, goal);
//            Optional<Atom> planAction = space.query(planQuery)
//                .map(qr -> qr.bindings().get(actionVar))
//                .filter(Optional::isPresent).map(Optional::get)
//                .filter(availableActions::contains) // Check if planned action is available
//                .findFirst();
//
//            if (planAction.isPresent()) { System.out.println(STR."Agent: Selecting action from plan: \{planAction.get()}"); return planAction; }
//
//            // 2. Reactive: Evaluate utility
//            Var valueVar = space.variable("value");
//            Optional<Atom> bestReactive = availableActions.stream()
//                .map(action -> {
//                    Expr utilityQuery = space.expression(UTILITY, action); // Evaluate (Utility Action)
//                    double util = interpreter.evaluate(utilityQuery)
//                        .flatMap(v -> v instanceof Gat<?> g && g.value() instanceof Number n ? Stream.of(n.doubleValue()) : Stream.empty())
//                        .max(Double::compare).orElse(0.0);
//                    return Map.entry(action, util);
//                })
//                .filter(e -> e.getValue() > 0.0) // Basic threshold
//                .max(Map.Entry.comparingByValue())
//                .map(Map.Entry::getKey);
//
//            if (bestReactive.isPresent()) { System.out.println(STR."Agent: Selecting reactive action: \{bestReactive.get()}"); return bestReactive; }
//
//            // 3. Explore/Fallback
//            if (random.nextDouble() < AGENT_RANDOM_ACTION_PROB) {
//                 System.out.println("Agent: Selecting random action (exploration).");
//                 return Optional.of(availableActions.get(random.nextInt(availableActions.size())));
//            }
//
//            // 4. Default if nothing else worked
//            System.out.println("Agent: No plan/utility, selecting random.");
//            return Optional.of(availableActions.get(random.nextInt(availableActions.size())));
//        }
//
//        private void learn(Atom prevState, Atom action, Atom nextState, double reward) {
//            if (prevState == null || action == null || nextState == null) return;
//
//            // Learn transition: (= (transition $prevState $action) $nextState) ; or Implies/Achieves
//            Expr transitionRule = Cog.this.addEq(space.expression(space.symbol("Transition"), prevState, action), nextState);
//            updateValue(transitionRule, v -> v.withTruth(v.truth.merge(Truth.of(1.0, AGENT_DEFAULT_LEARN_COUNT))));
//
//            // Learn utility: (= (Utility $action) $newValue)
//            Expr utilityExpr = space.expression(UTILITY, action);
//            Value currentVal = space.getValue(utilityExpr).orElse(Value.initialValue(0)); // Assume utility expr itself holds value? Risky.
//             // Need utility value, not just expression. Query or Eval needed.
//             // Let's store utility value directly: (= (UtilityValue Action) (Value 0.8))
//             Expr utilityValueExpr = space.expression(space.symbol("UtilityValue"), action);
//             double currentUtil = interpreter.evaluate(utilityValueExpr)
//                 .filter(a -> a instanceof Gat<?> g && g.value() instanceof Number)
//                 .mapToDouble(a -> ((Number)((Gat<?>)a).value()).doubleValue())
//                 .findFirst().orElse(0.0);
//
//            double learningRate = 0.1;
//            double newUtil = currentUtil * (1.0 - learningRate) + reward * learningRate;
//            Atom utilValueAtom = Cog.this.val(newUtil); // Grounded number
//
//            // Add/Update rule (= (UtilityValue Action) (Value ...))
//            Expr utilityRule = Cog.this.addEq(utilityValueExpr, space.expression(space.symbol("Value"), utilValueAtom)); // Wrap value
//            updateValue(utilityRule, v -> v.withTruth(TRUTH_TRUE)); // Utility rules are definitions
//
//            System.out.printf("  Learn: Utility for %s -> %.3f%n", action.id(), newUtil);
//        }
//    }
//
//    // --- Simple Grid World Environment ---
//    static class SimpleGridWorld implements Environment {
//        private final Cog cog; private final int size; private final int goalX, goalY;
//        private int x=0, y=0; private int steps=0; private final int maxSteps=15;
//        private final Sym AT, POS_XY, MOVE_N, MOVE_S, MOVE_E, MOVE_W;
//
//        SimpleGridWorld(Cog cog, int sz, int gx, int gy) {
//            this.cog = cog; size=sz; goalX=gx; goalY=gy;
//            AT=cog.sym("At"); POS_XY=cog.sym("Pos"); // Use (Pos X Y)
//            MOVE_N=cog.sym("MoveN"); MOVE_S=cog.sym("MoveS"); MOVE_E=cog.sym("MoveE"); MOVE_W=cog.sym("MoveW");
//        }
//        private Expr currentPosExpr() { return cog.expr(POS_XY, cog.val(x), cog.val(y)); }
//        @Override public List<Atom> perceive(Cog cog) { return List.of(cog.expr(AT, cog.SYM_SELF, currentPosExpr())); }
//        @Override public List<Atom> availableActions(Cog cog) {
//            List<Atom> actions = new ArrayList<>(4);
//            if(y<size-1) actions.add(MOVE_N); if(y>0) actions.add(MOVE_S);
//            if(x<size-1) actions.add(MOVE_E); if(x>0) actions.add(MOVE_W);
//            return actions.stream().map(cog::add).toList(); // Ensure actions exist in space
//        }
//        @Override public EnvironmentOutcome executeAction(Cog cog, Atom action) {
//            steps++; double reward = -0.05;
//                 if (action.equals(MOVE_N) && y<size-1) y++; else if (action.equals(MOVE_S) && y>0) y--;
//            else if (action.equals(MOVE_E) && x<size-1) x++; else if (action.equals(MOVE_W) && x>0) x--;
//            else reward -= 0.2; // Bump penalty
//            boolean atGoal = x==goalX && y==goalY;
//            if (atGoal) reward = 1.0; else if (!isRunning()) reward = -0.5;
//            if (atGoal) System.out.println("GridWorld: Goal Reached!");
//            return new EnvironmentOutcome(perceive(cog), reward);
//        }
//        @Override public boolean isRunning() { return steps < maxSteps && !(x==goalX && y==goalY); }
//    }
//
//    // --- Bootstrap Helper ---
//    private static class Bootstrap {
//        static void initialize(Cog cog) {
//            // Grounded Arithmetic
//            var plus = cog.func("GroundedPlus", ctx -> {
//                if (ctx.args().size() != 2) return Stream.empty();
//                Optional<Double> d1 = parseNum(ctx.args().get(0));
//                Optional<Double> d2 = parseNum(ctx.args().get(1));
//                return (d1.isPresent() && d2.isPresent()) ? Stream.of(cog.val(d1.get() + d2.get())) : Stream.empty();
//            });
//            // Link symbolic '+' to grounded version
//            cog.addEq(cog.expr(cog.sym("+"), cog.var("a"), cog.var("b")), cog.expr(plus, cog.var("a"), cog.var("b")));
//
//            // Grounded 'match'
//            var match = cog.func(SYM_MATCH.name(), ctx -> { // Use SYM_MATCH for name consistency
//                 if (ctx.args().size() != 2) return Stream.empty(); // (match Pattern Template) - Self implicit
//                 return cog.space().query(ctx.args().get(0)) // Query with pattern
//                     .map(qr -> Unify.substitute(ctx.args().get(1), qr.bindings())); // Apply template
//            });
//
//            // Grounded 'add' atom
//             var add = cog.func(SYM_ADD.name(), ctx -> {
//                 if (ctx.args().isEmpty()) return Stream.empty();
//                 return Stream.of(cog.add(ctx.args().get(0))); // Add the atom, return canonical instance
//             });
//              // Grounded 'remove' atom
//              var remove = cog.func(SYM_REMOVE.name(), ctx -> {
//                  if (ctx.args().isEmpty()) return Stream.empty();
//                  boolean success = cog.space.removeAtomInternal(ctx.args().get(0)); // Use internal remove
//                  return Stream.of(success ? SYM_TRUE : SYM_FALSE);
//              });
//
//            // Define 'If' rules
//            cog.addEq(cog.expr(SYM_IF, SYM_TRUE, cog.var("then"), cog.var("else")), cog.var("then"));
//            cog.addEq(cog.expr(SYM_IF, SYM_FALSE, cog.var("then"), cog.var("else")), cog.var("else"));
//        }
//        static Optional<Double> parseNum(Atom a) {
//            return (a instanceof Gat<?> g && g.value() instanceof Number n) ? Optional.of(n.doubleValue()) : Optional.empty();
//        }
//    }
//
//    // Utility Pair record
//    private record Pair<A, B>(A a, B b) {}
//}