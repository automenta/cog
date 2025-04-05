# Synthesize a complete result by mixing and improving the best design choices from each.

----

# OVERARCHING MANDATE
Identify Java code-paths that can/should be rewritten as MeTTa script to lead the way towards more complete system implementation and self-integration.  These are not necessarily the largest pieces of code to rewrite; instead, be strategic and plan ahead.

# GOALS
- Long-term: Replace significant amounts of Java code with more compact MeTTa script
- Reduce the overall program complexity, and file size
- Integrate and leverage JVM reflection, MethodHandles, proxy classes, codegen, etc: to invoke Java code and access data
- Integrate and leverage higher-order-logic (HOL)
- Support system reflectivity and self-control
- This also supports the system's self-integration as an autonomous system.
- Focus on core components for maximum impact.  Peripheral components are candidates for redesign, so do not invest in them now.

# Deliver a single, self-documenting Java class:
 - Complete (fully functional)
    - Ensure all functionality remains present, in some way.  
    - Complete any missing or planned functionality.
 - Correct (bug-free and logically sound)
 - Compact (minimal codebase size)
   - Use syntax constructs, like Ternary operator, to minimize lines and tokens.
   - Use the latest Java language version's syntax options to best express code 
 - Consolidated (avoids unnecessary separation)
 - Deduplicated (no redundant logic) - introduce helpful abstractions functions, parameters, and classes to share common code.  Apply "don't repeat yourself" principles.
 - Modular (logically organized, supporting abstraction)
 - Self-documenting
   - Clear naming and structure
   - Comment ONLY when code requires explanation, does something which isn't obvious, or deliberately contradicts its implied nature.
   - If a method comment is necessary, a clear one-line comment is preferable to a verbose redundant JavaDoc comment
 - Using the latest version of the Java language and APIs
 - Use @Nullable, but not @NotNull
 - Do not rename identifiers. Names are carefully chosen.