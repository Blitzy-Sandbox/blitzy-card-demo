/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.application;

import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;

/**
 * Dynamic program dispatch registry that translates COBOL CICS
 * {@code XCTL PROGRAM(<variable>)} and dynamic-name {@code CALL <variable>}
 * constructs to Java method invocations.
 *
 * <p>Static {@code CALL} constructs with literal program names (for example
 * {@code CALL 'CSUTLDTC'}, {@code CALL 'CBSTM03B'}) are NOT routed through
 * this registry; per AAP &sect;0.1.2 they become direct method calls on a
 * constructor-injected collaborator. This registry is used ONLY when the
 * target program name is held in a working-storage field at runtime &mdash;
 * the most common pattern is {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} where
 * {@code CDEMO-TO-PROGRAM} is an 8-byte {@code PIC X(08)} field in
 * {@code app/cpy/COCOM01Y.cpy:CARDDEMO-COMMAREA}.
 *
 * <h2>COBOL Dispatch Sites Translated by This Registry</h2>
 * Verified via {@code grep -n "XCTL PROGRAM" app/cbl/*.cbl}:
 * <ul>
 *   <li>{@code app/cbl/COMEN01C.cbl:153} &mdash;
 *       {@code XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))}
 *       (main-menu option dispatch; the option index selects one of the
 *       {@code CDEMO-MENU-OPT-PGMNAME(i)} entries from
 *       {@code app/cpy/COMEN02Y.cpy}).</li>
 *   <li>{@code app/cbl/COMEN01C.cbl:176},
 *       {@code app/cbl/COADM01C.cbl:166},
 *       {@code app/cbl/COBIL00C.cbl:282},
 *       {@code app/cbl/CORPT00C.cbl:549},
 *       {@code app/cbl/COTRN00C.cbl:193,519},
 *       {@code app/cbl/COTRN01C.cbl:206},
 *       {@code app/cbl/COTRN02C.cbl:509},
 *       {@code app/cbl/COUSR00C.cbl:197,207,515},
 *       {@code app/cbl/COUSR01C.cbl:176},
 *       {@code app/cbl/COUSR02C.cbl:259},
 *       {@code app/cbl/COUSR03C.cbl:206}
 *       &mdash; {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} (return-to-caller
 *       pattern; the previous menu / signon writes its own program-id into
 *       {@code CDEMO-TO-PROGRAM} so the current program transfers back).</li>
 *   <li>{@code app/cbl/COADM01C.cbl:143} &mdash;
 *       {@code XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))}
 *       (admin-menu option dispatch; the option index selects one of the
 *       {@code CDEMO-ADMIN-OPT-PGMNAME(i)} entries from
 *       {@code app/cpy/COADM02Y.cpy}).</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Registration is performed by the composition root ({@code carddemo-app})
 * at startup; each online program registers itself under its COBOL
 * {@code PROGRAM-ID} (for example {@code "COSGN00C"}). The
 * {@link #invoke(String, CardDemoCommarea) invoke} method looks up the
 * registered handler and calls it. Unknown program names produce an
 * {@link UnknownProgramException} &mdash; there is NO fallback to reflection
 * (AAP &sect;0.7.4 forbids reflection unless faithfully translating an
 * existing COBOL construct, and the CICS {@code XCTL} macro is a service
 * call rather than a COBOL-language feature, so a method-reference registry
 * is the cleanest faithful translation).
 *
 * <h2>COBOL PROGRAM-IDs Eligible for Registration</h2>
 * Per AAP &sect;0.4.1 the composition root will register exactly these
 * COBOL PROGRAM-IDs (all uppercase, 8 characters each). They are exposed
 * as {@code public static final String} constants on this class for
 * compile-time use at every call site (so that callers reference
 * {@code ProgramRegistry.CO_SGN_00C} rather than re-typing the literal
 * {@code "COSGN00C"} and risking typos):
 * <pre>
 *   Online (CICS): COSGN00C, COMEN01C, COADM01C,
 *                  COACTVWC, COACTUPC,
 *                  COCRDLIC, COCRDSLC, COCRDUPC,
 *                  COTRN00C, COTRN01C, COTRN02C,
 *                  COBIL00C, CORPT00C,
 *                  COUSR00C, COUSR01C, COUSR02C, COUSR03C
 *
 *   Batch (rarely dispatched via XCTL; normally invoked by JCL EXEC PGM=,
 *   which is the composition root's responsibility, but listed for
 *   completeness):
 *                  CBACT01C, CBACT02C, CBACT03C, CBACT04C,
 *                  CBCUS01C, CBTRN01C, CBTRN02C, CBTRN03C,
 *                  CBSTM03A, CBSTM03B
 *
 *   Subroutine:    CSUTLDTC (typically invoked via direct method call,
 *                  not registry).
 * </pre>
 *
 * <h2>Thread-Safety</h2>
 * The internal {@link Map} is a plain {@link HashMap}; registration is a
 * startup-time concern performed sequentially by the composition root.
 * After registration is complete, the registry is effectively immutable
 * and concurrent reads ({@link #invoke}, {@link #isRegistered},
 * {@link #registeredPrograms}) are safe per the
 * {@code java.util.Map}-after-publication memory-model guarantee provided
 * that the registry reference is published safely (for example via a
 * {@code final} field on the composition root, which is the documented
 * usage pattern). Per AAP &sect;0.6.6 batch run context flows through
 * {@code ScopedValue}; no {@code ThreadLocal} or {@code synchronized}
 * blocks are introduced here.
 *
 * <h2>Why No {@link CobolProgram &#64;CobolProgram} on the Class?</h2>
 * This class is intentionally a Java idiom replacement for the CICS
 * {@code XCTL} macro and has no direct COBOL ancestor. Per AAP
 * &sect;0.7.1 traceability rules, the {@link CobolProgram} annotation
 * is reserved for direct COBOL program / copybook / paragraph
 * translations. The {@link CobolProgram} import is retained so that
 * Javadoc {&#64;link CobolProgram} references in this file's documentation
 * resolve correctly.
 *
 * <h2>Why Throw an Exception Instead of Returning {@code Optional}?</h2>
 * COBOL {@code XCTL PROGRAM(<bad>)} abends with the CICS condition
 * {@code PGMIDERR}. AAP &sect;0.7.1 mandates identical observable
 * outcomes for translated error paths; an exception preserves the COBOL
 * abend semantics. An {@code Optional<ProgramHandler>} return would
 * silently change error-handling at every call site.
 *
 * @see com.blitzy.carddemo.domain.commarea.CardDemoCommarea
 * @see CobolProgram
 * @see ProgramHandler
 * @see UnknownProgramException
 * @since 1.0.0
 */
public final class ProgramRegistry {

    //--------------------------------------------------------------------------
    // Nested Types
    //--------------------------------------------------------------------------

    /**
     * Functional contract for a registered COBOL program handler.
     *
     * <p>The contract mirrors COBOL
     * {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)} semantics: the
     * caller passes a (nullable) commarea, the callee processes it and
     * returns the (possibly updated) commarea to the caller. A
     * {@code null} input commarea models the COBOL {@code EIBCALEN = 0}
     * case where no commarea is present (first-time entry from a CICS
     * transaction launched without one).
     *
     * <p>This is an explicit nominal type rather than a
     * {@code java.util.function.Function<CardDemoCommarea, CardDemoCommarea>}
     * because:
     * <ul>
     *   <li>It documents the semantic role: "a handler dispatched by a
     *       COBOL XCTL", not "any function from commarea to commarea".</li>
     *   <li>It enables additional methods to be added later (for example
     *       {@code String programId()}) without breaking existing
     *       registrations.</li>
     *   <li>Compile errors at registration sites point to a domain term
     *       rather than to a generic functional type.</li>
     * </ul>
     */
    @FunctionalInterface
    public interface ProgramHandler {
        /**
         * Invokes the registered program with the given commarea.
         *
         * @param commarea the input commarea; may be {@code null} for
         *                 first-time entry ({@code EIBCALEN = 0})
         * @return the (possibly updated) commarea returned to the caller;
         *         implementations should return a non-null commarea once
         *         they have run (use a default-initialized
         *         {@link CardDemoCommarea} if no state needs to flow back)
         */
        CardDemoCommarea handle(CardDemoCommarea commarea);
    }

    /**
     * Thrown when {@link ProgramRegistry#invoke(String, CardDemoCommarea)}
     * is called with a program name that has not been registered. This
     * corresponds to the COBOL {@code PGMIDERR} CICS abend condition
     * (program-id error). Per AAP &sect;0.7.1 ("preserve existing error
     * codes &hellip; with identical observable outcomes"), this exception
     * is the Java analogue of the CICS abend.
     *
     * <p>It extends {@link RuntimeException} (unchecked) so that
     * translated paragraphs do not need to declare it in their throws
     * clauses, matching the COBOL convention where abends propagate
     * without a declared throws contract.
     */
    public static final class UnknownProgramException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String programName;

        /**
         * Constructs an exception for the given normalized COBOL program
         * name.
         *
         * @param programName the normalized (uppercase, right-trimmed)
         *                    COBOL PROGRAM-ID that was not found; must
         *                    be non-null
         * @throws NullPointerException if {@code programName} is null
         */
        public UnknownProgramException(String programName) {
            super("No handler registered for COBOL program: "
                    + Objects.requireNonNull(programName, "programName"));
            this.programName = programName;
        }

        /**
         * @return the COBOL program name (normalized: uppercase,
         *         right-trimmed) that was not found in the registry
         */
        public String programName() {
            return programName;
        }
    }

    //--------------------------------------------------------------------------
    // Public COBOL PROGRAM-ID constants
    //
    // Exposed as compile-time constants so call sites use
    //   programRegistry.invoke(ProgramRegistry.CO_SGN_00C, commarea)
    // rather than the bare literal "COSGN00C" (which would risk typos and
    // would not be findable via Java's "find usages" tooling). The names
    // follow Java upper-snake-case convention; each value is the exact
    // 8-character COBOL PROGRAM-ID. This complements the @CobolProgram
    // traceability annotation on the corresponding translated classes
    // (e.g., CoSgn00C is annotated @CobolProgram("COSGN00C")).
    //--------------------------------------------------------------------------

    /** COBOL PROGRAM-ID for the signon program; see {@code app/cbl/COSGN00C.cbl}. */
    public static final String CO_SGN_00C = "COSGN00C";

    /** COBOL PROGRAM-ID for the main menu; see {@code app/cbl/COMEN01C.cbl}. */
    public static final String CO_MEN_01C = "COMEN01C";

    /** COBOL PROGRAM-ID for the admin menu; see {@code app/cbl/COADM01C.cbl}. */
    public static final String CO_ADM_01C = "COADM01C";

    /** COBOL PROGRAM-ID for the account view program; see {@code app/cbl/COACTVWC.cbl}. */
    public static final String CO_ACT_VW_C = "COACTVWC";

    /** COBOL PROGRAM-ID for the account update program; see {@code app/cbl/COACTUPC.cbl}. */
    public static final String CO_ACT_UP_C = "COACTUPC";

    /** COBOL PROGRAM-ID for the credit-card list program; see {@code app/cbl/COCRDLIC.cbl}. */
    public static final String CO_CRD_LI_C = "COCRDLIC";

    /** COBOL PROGRAM-ID for the credit-card view program; see {@code app/cbl/COCRDSLC.cbl}. */
    public static final String CO_CRD_SL_C = "COCRDSLC";

    /** COBOL PROGRAM-ID for the credit-card update program; see {@code app/cbl/COCRDUPC.cbl}. */
    public static final String CO_CRD_UP_C = "COCRDUPC";

    /** COBOL PROGRAM-ID for the transaction list program; see {@code app/cbl/COTRN00C.cbl}. */
    public static final String CO_TRN_00C = "COTRN00C";

    /** COBOL PROGRAM-ID for the transaction view program; see {@code app/cbl/COTRN01C.cbl}. */
    public static final String CO_TRN_01C = "COTRN01C";

    /** COBOL PROGRAM-ID for the transaction add program; see {@code app/cbl/COTRN02C.cbl}. */
    public static final String CO_TRN_02C = "COTRN02C";

    /** COBOL PROGRAM-ID for the transaction reports bridge; see {@code app/cbl/CORPT00C.cbl}. */
    public static final String CO_RPT_00C = "CORPT00C";

    /** COBOL PROGRAM-ID for the bill-payment program; see {@code app/cbl/COBIL00C.cbl}. */
    public static final String CO_BIL_00C = "COBIL00C";

    /** COBOL PROGRAM-ID for the user-list (security) program; see {@code app/cbl/COUSR00C.cbl}. */
    public static final String CO_USR_00C = "COUSR00C";

    /** COBOL PROGRAM-ID for the user-add (security) program; see {@code app/cbl/COUSR01C.cbl}. */
    public static final String CO_USR_01C = "COUSR01C";

    /** COBOL PROGRAM-ID for the user-update (security) program; see {@code app/cbl/COUSR02C.cbl}. */
    public static final String CO_USR_02C = "COUSR02C";

    /** COBOL PROGRAM-ID for the user-delete (security) program; see {@code app/cbl/COUSR03C.cbl}. */
    public static final String CO_USR_03C = "COUSR03C";

    //--------------------------------------------------------------------------
    // Instance state
    //--------------------------------------------------------------------------

    /**
     * Map of normalized COBOL PROGRAM-ID (uppercase, right-trimmed) to
     * the registered handler. Initialized empty; populated by
     * {@link #register(String, ProgramHandler)} calls from the
     * composition root.
     */
    private final Map<String, ProgramHandler> handlers;

    //--------------------------------------------------------------------------
    // Constructors
    //--------------------------------------------------------------------------

    /**
     * Creates an empty registry. The composition root
     * ({@code carddemo-app}) is expected to populate it via
     * {@link #register(String, ProgramHandler)} at startup, before any
     * application class invokes {@link #invoke(String, CardDemoCommarea)}.
     */
    public ProgramRegistry() {
        this.handlers = new HashMap<>();
    }

    /**
     * Creates a registry pre-populated with the provided handlers. Useful
     * for unit tests that need a fixed mapping without calling
     * {@link #register(String, ProgramHandler)} repeatedly.
     *
     * <p>Entries are copied through {@link #register(String, ProgramHandler)}
     * so name normalization and null-checking are applied uniformly.
     * The input map is not retained; subsequent mutations to it do not
     * affect the registry.
     *
     * @param initial map of COBOL PROGRAM-ID &rarr; handler; must be
     *                non-null (the map itself may be empty)
     * @throws NullPointerException     if {@code initial} is null, or if
     *                                  any key or value within it is null
     * @throws IllegalArgumentException if any key is blank after
     *                                  normalization
     * @throws IllegalStateException    if {@code initial} contains two
     *                                  entries that normalize to the
     *                                  same key
     */
    public ProgramRegistry(Map<String, ProgramHandler> initial) {
        Objects.requireNonNull(initial, "initial");
        this.handlers = new HashMap<>();
        initial.forEach(this::register);
    }

    //--------------------------------------------------------------------------
    // Public API
    //--------------------------------------------------------------------------

    /**
     * Registers a program handler under the given COBOL
     * {@code PROGRAM-ID}. Program names are normalized to uppercase and
     * right-trimmed before insertion, matching how COBOL stores them in
     * {@code PIC X(08)} working-storage fields (8-byte space-padded).
     *
     * <p>Re-registration of the same normalized name is rejected so that
     * accidental double-wiring at the composition root surfaces eagerly
     * rather than silently overwriting a previous registration.
     *
     * @param programName the COBOL PROGRAM-ID (for example
     *                    {@code "COSGN00C"}, {@code "COMEN01C"}); must be
     *                    non-null and non-blank after normalization
     * @param handler     the handler to invoke when this program is
     *                    dispatched; must be non-null
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if {@code programName} is blank
     *                                  after normalization
     * @throws IllegalStateException    if a handler is already registered
     *                                  for this normalized name
     */
    public void register(String programName, ProgramHandler handler) {
        Objects.requireNonNull(programName, "programName");
        Objects.requireNonNull(handler, "handler");
        String normalized = normalize(programName);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("programName must not be blank");
        }
        ProgramHandler previous = handlers.putIfAbsent(normalized, handler);
        if (previous != null) {
            throw new IllegalStateException(
                    "Handler already registered for program: " + normalized);
        }
    }

    /**
     * Invokes the registered handler for the given COBOL program name,
     * passing the supplied commarea. This is the Java analogue of
     * {@code EXEC CICS XCTL PROGRAM(<name>) COMMAREA(...)}.
     *
     * <p>The program name is normalized (uppercase, right-trimmed) before
     * lookup so that callers may pass the raw contents of a COBOL
     * {@code PIC X(08)} field (which may include trailing spaces) without
     * pre-trimming.
     *
     * @param programName the COBOL PROGRAM-ID to dispatch; must be
     *                    non-null
     * @param commarea    the commarea to pass to the callee; may be
     *                    {@code null} ({@code EIBCALEN = 0} case)
     * @return the commarea returned by the callee
     * @throws NullPointerException    if {@code programName} is null
     * @throws UnknownProgramException if no handler is registered under
     *                                 the normalized name (analogous to
     *                                 the CICS {@code PGMIDERR} abend)
     */
    public CardDemoCommarea invoke(String programName, CardDemoCommarea commarea) {
        Objects.requireNonNull(programName, "programName");
        String normalized = normalize(programName);
        ProgramHandler handler = handlers.get(normalized);
        if (handler == null) {
            throw new UnknownProgramException(normalized);
        }
        return handler.handle(commarea);
    }

    /**
     * Tests whether a handler is registered for the given COBOL program
     * name. Useful for the COBOL "is this option valid?" check before
     * dispatching from a menu &mdash; for example, the
     * {@code app/cbl/COMEN01C.cbl} translation in {@code CoMen01C}
     * verifies {@code isRegistered(pgmName)} before calling
     * {@code invoke(pgmName, outbound)} so that an unknown menu option
     * surfaces as a user-facing error message rather than an unchecked
     * {@link UnknownProgramException}.
     *
     * @param programName the COBOL PROGRAM-ID to check; if {@code null}
     *                    this method returns {@code false} (mirroring
     *                    the COBOL initialized-spaces case)
     * @return {@code true} if a handler is registered for the normalized
     *         name; {@code false} otherwise
     */
    public boolean isRegistered(String programName) {
        if (programName == null) {
            return false;
        }
        return handlers.containsKey(normalize(programName));
    }

    /**
     * Returns an unmodifiable view of the set of all normalized COBOL
     * program names currently registered. The returned set is backed by
     * the live registry: subsequent {@link #register} calls become
     * visible through the returned set, but the returned set itself
     * rejects all mutation attempts with
     * {@link UnsupportedOperationException}.
     *
     * <p>Iteration order is unspecified ({@link HashMap} ordering); do
     * not rely on it for behavioral correctness.
     *
     * @return an unmodifiable {@link Set} of registered COBOL
     *         PROGRAM-IDs (uppercase, right-trimmed)
     */
    public Set<String> registeredPrograms() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    //--------------------------------------------------------------------------
    // Internal helpers
    //--------------------------------------------------------------------------

    /**
     * Normalizes a COBOL program name by right-trimming whitespace and
     * uppercasing. COBOL stores program names in {@code PIC X(08)}
     * fields that are space-padded on the right; this method matches
     * that storage convention so that {@code "COSGN00C"},
     * {@code "cosgn00c"}, and {@code "COSGN00C  "} all resolve to the
     * same handler.
     *
     * <p>Implementation notes:
     * <ul>
     *   <li>Uses {@link String#stripTrailing()} (Java 11+) to remove all
     *       trailing whitespace per the Unicode whitespace property
     *       &mdash; this is a strict superset of COBOL's ASCII space
     *       (0x20), covering any non-conforming input that may sneak in
     *       through a UTF-8 fixture file.</li>
     *   <li>Uses {@link Locale#ROOT} for the uppercase conversion so the
     *       result is locale-insensitive (for example, in a Turkish
     *       locale, {@code "i".toUpperCase()} produces {@code "\u0130"}
     *       rather than {@code "I"}; {@code Locale.ROOT} avoids this).
     *       COBOL PROGRAM-IDs are ASCII, so the locale-insensitive
     *       conversion produces the COBOL-expected byte values
     *       deterministically.</li>
     * </ul>
     *
     * @param programName non-null input
     * @return the right-trimmed, uppercase form
     */
    private static String normalize(String programName) {
        return programName.stripTrailing().toUpperCase(Locale.ROOT);
    }
}
