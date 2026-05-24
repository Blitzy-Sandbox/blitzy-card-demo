/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Routing facade for COBOL {@code CALL identifier} and
 * {@code EXEC CICS XCTL PROGRAM(identifier)} constructs where the target
 * program name is supplied at runtime. The original COBOL programs (notably
 * the menus {@code COMEN01C} and {@code COADM01C}) dispatch to a per-option
 * program name by writing it into {@code WS-PGMNAME} or
 * {@code CDEMO-TO-PROGRAM} and then invoking the call.
 *
 * <p>Per AAP &sect;0.4.1 (table row "Dynamic CALL routing") this Java
 * registry maps COBOL program names (e.g., {@code "COSGN00C"},
 * {@code "COACTVWC"}) to the {@link Function} that invokes the corresponding
 * Java translation. The Function takes a {@link CardDemoCommarea} (the
 * DFHCOMMAREA pass-through) and returns the commarea that the callee would
 * have written back. Static {@code CALL "ABC123"} sites translate to direct
 * method calls and do not use this registry.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>Lookup is by exact uppercase 8-character COBOL program-id.
 *       Trailing spaces in the source COBOL {@code PIC X(08)} value are
 *       trimmed before lookup.</li>
 *   <li>Returns {@link Optional#empty()} when the program-id is unknown so
 *       callers can replicate the COBOL "program not found" branch (e.g.,
 *       set an error message and re-display the menu).</li>
 *   <li>Registration is single-shot: re-registering the same program-id is
 *       rejected so the wiring graph stays explicit.</li>
 * </ul>
 *
 * <p>The registry is not thread-safe for concurrent {@code register} calls,
 * matching the COBOL initialization model (one PROCEDURE DIVISION runs at
 * startup to wire everything). Concurrent lookups via {@link #lookup} and
 * {@link #invoke} are safe once registration is complete.
 */
@CobolProgram(
        value = "ProgramRegistry",
        sourcePath = "synthetic (no direct COBOL copybook)",
        notes = "Dynamic CALL/XCTL routing facade; replaces variable-program COBOL CALL identifier"
)
public final class ProgramRegistry {

    /** The set of well-known COBOL program-ids carried in CDEMO-TO-PROGRAM. */
    public static final String CO_SGN_00C = "COSGN00C";
    public static final String CO_MEN_01C = "COMEN01C";
    public static final String CO_ADM_01C = "COADM01C";
    public static final String CO_ACT_VW_C = "COACTVWC";
    public static final String CO_ACT_UP_C = "COACTUPC";
    public static final String CO_CRD_LI_C = "COCRDLIC";
    public static final String CO_CRD_SL_C = "COCRDSLC";
    public static final String CO_CRD_UP_C = "COCRDUPC";
    public static final String CO_TRN_00C = "COTRN00C";
    public static final String CO_TRN_01C = "COTRN01C";
    public static final String CO_TRN_02C = "COTRN02C";
    public static final String CO_RPT_00C = "CORPT00C";
    public static final String CO_BIL_00C = "COBIL00C";
    public static final String CO_USR_00C = "COUSR00C";
    public static final String CO_USR_01C = "COUSR01C";
    public static final String CO_USR_02C = "COUSR02C";
    public static final String CO_USR_03C = "COUSR03C";

    private final Map<String, Function<CardDemoCommarea, CardDemoCommarea>> handlers =
            new HashMap<>();

    public ProgramRegistry() {
        // Public no-arg constructor for explicit registration at the composition root
    }

    /**
     * Registers a program-id with the handler that performs the equivalent
     * COBOL behavior. The handler takes the inbound DFHCOMMAREA and returns
     * the (possibly mutated) commarea that the COBOL program would have
     * written back. Re-registration of the same program-id raises an
     * {@link IllegalStateException} so wiring errors surface eagerly.
     *
     * @param programId the 1..8-character COBOL program-id (e.g., {@code "COSGN00C"})
     * @param handler   the Java handler to invoke
     * @throws NullPointerException  if {@code programId} or {@code handler} is null
     * @throws IllegalStateException if {@code programId} is already registered
     */
    public void register(String programId,
                         Function<CardDemoCommarea, CardDemoCommarea> handler) {
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(handler, "handler");
        String key = normalize(programId);
        if (handlers.containsKey(key)) {
            throw new IllegalStateException(
                    "Program already registered: '" + key + "'");
        }
        handlers.put(key, handler);
    }

    /**
     * Looks up the handler for a program-id without invoking it. Returns
     * empty when the program-id is unknown so callers can produce the
     * COBOL "PROGRAM NOT FOUND" / DFHRESP(NOTFND) outcome.
     */
    public Optional<Function<CardDemoCommarea, CardDemoCommarea>> lookup(String programId) {
        if (programId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(normalize(programId)));
    }

    /**
     * Invokes the registered handler for {@code programId}, passing the
     * supplied commarea. Equivalent to a COBOL {@code EXEC CICS XCTL
     * PROGRAM(programId) COMMAREA(commarea)}.
     *
     * @param programId the COBOL program-id to invoke
     * @param commarea  the inbound commarea (nullable for the entry XCTL)
     * @return the commarea the called program wrote back
     * @throws IllegalArgumentException if {@code programId} is unknown
     */
    public CardDemoCommarea invoke(String programId, CardDemoCommarea commarea) {
        Function<CardDemoCommarea, CardDemoCommarea> handler =
                lookup(programId).orElseThrow(() -> new IllegalArgumentException(
                        "Unknown program-id: '" + programId + "'"));
        return handler.apply(commarea);
    }

    /**
     * Returns {@code true} when the supplied program-id has a registered
     * handler. Useful for the COBOL "is this option valid?" check before
     * dispatching from a menu.
     */
    public boolean isRegistered(String programId) {
        if (programId == null) {
            return false;
        }
        return handlers.containsKey(normalize(programId));
    }

    /**
     * Returns the count of registered handlers. Primarily for test
     * assertions and startup logging.
     */
    public int size() {
        return handlers.size();
    }

    /**
     * COBOL program-ids are {@code PIC X(08)} so callers may pass the
     * variable contents with trailing spaces. We normalize by trimming
     * trailing whitespace and uppercasing so {@code "cosgn00c "} and
     * {@code "COSGN00C"} resolve to the same handler.
     */
    private static String normalize(String programId) {
        // Trim trailing spaces only (COBOL pads on the right)
        int end = programId.length();
        while (end > 0 && Character.isWhitespace(programId.charAt(end - 1))) {
            end--;
        }
        return programId.substring(0, end).toUpperCase();
    }
}
