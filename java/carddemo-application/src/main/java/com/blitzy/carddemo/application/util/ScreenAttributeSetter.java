/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.util;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.util.Objects;

/**
 * Java translation of the {@code CSSETATY} COBOL procedure copybook at
 * {@code app/cpy/CSSETATY.cpy}. The original copybook is not a data
 * structure; it is a procedure template parameterised by
 * {@code (TESTVAR1), (SCRNVAR2), (MAPNAME3)} that the application program
 * inlines via {@code COPY ... REPLACING} to color the named output field
 * red and (optionally) overwrite its value with {@code '*'} when the
 * field flag indicates an error or blank value and the program is
 * processing a re-entry (CDEMO-PGM-REENTER).
 *
 * <pre>{@code
 *  IF  (FLG-(TESTVAR1)-NOT-OK
 *  OR   FLG-(TESTVAR1)-BLANK)
 *  AND CDEMO-PGM-REENTER
 *      MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
 *      IF FLG-(TESTVAR1)-BLANK
 *          MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 *      END-IF
 *  END-IF
 * }</pre>
 *
 * <p>In Java the BMS attribute bytes (C-suffix) and value bytes (O-suffix)
 * are consolidated on the output DTOs into an {@code AttributeMode} enum
 * (UNPROTECTED, PROTECTED, PROTECTED_HIGHLIGHTED, ERROR). This utility
 * provides the equivalent transformation: given the field flag (OK / NOT_OK /
 * BLANK), the current attribute mode, the current display value, and the
 * page-context (ENTER vs REENTER), return the {@link Outcome} (new mode and
 * new value) that the COBOL template would have produced.
 *
 * <p>This class is stateless; all methods are static. It carries
 * {@link CobolProgram} for traceability to the source copybook.
 */
@CobolProgram(
        value = "CSSETATY",
        sourcePath = "app/cpy/CSSETATY.cpy",
        notes = "Procedure copybook: set field attribute to RED and value to '*' on re-entry error/blank"
)
public final class ScreenAttributeSetter {

    /** The placeholder value the COBOL template writes when a field is blank. */
    public static final String BLANK_PLACEHOLDER = "*";

    private ScreenAttributeSetter() {
        // Utility class: no instances
    }

    /**
     * Flag value parallel to the COBOL {@code FLG-<field>-OK / -NOT-OK / -BLANK}
     * 88-level conditions. Modeled as a Java enum so call sites can exhaustively
     * switch over the three possibilities. The three flag conditions in the
     * COBOL copybooks (e.g., {@code FLG-ACCT-ID-OK}, {@code FLG-ACCT-ID-NOT-OK},
     * {@code FLG-ACCT-ID-BLANK}) are mutually exclusive.
     */
    public enum FieldFlag {
        /** {@code FLG-(field)-OK}: validation passed. */
        OK,
        /** {@code FLG-(field)-NOT-OK}: validation failed (value was supplied but invalid). */
        NOT_OK,
        /** {@code FLG-(field)-BLANK}: required field was left blank. */
        BLANK
    }

    /**
     * Page context parallel to the COBOL {@code CDEMO-PGM-ENTER / -REENTER}
     * 88-level on {@code CDEMO-PGM-CONTEXT}. The error-coloring only fires
     * during a re-entry (i.e., a SEND-MAP after a failed RECEIVE-MAP), so the
     * caller must pass this in.
     */
    public enum PageContext {
        /** {@code CDEMO-PGM-ENTER}: first display of the screen. */
        ENTER,
        /** {@code CDEMO-PGM-REENTER}: re-display after validation. */
        REENTER
    }

    /**
     * Carrier for the (new attribute mode, new display value) pair produced
     * by the COBOL CSSETATY template. Modeled as a record so a single call
     * can update both bindings without ordering hazards.
     *
     * @param mode  the resulting BMS attribute mode (RED becomes
     *              {@link AttributeMode#ERROR}; otherwise unchanged)
     * @param value the resulting display value ({@link #BLANK_PLACEHOLDER}
     *              when the input was blank and the program is in REENTER;
     *              otherwise unchanged)
     */
    public record Outcome(AttributeMode mode, String value) {
        public Outcome {
            Objects.requireNonNull(mode, "mode");
            value = value == null ? "" : value;
        }
    }

    /**
     * Compact mode enum equivalent to the BMS DTO {@code AttributeMode} on
     * each output record. The four states correspond to the BMS attribute
     * bytes that the COBOL paragraph would have written. We use a local
     * enum (rather than reaching across to the BMS DTO {@code AttributeMode})
     * so the utility remains free of cyclic dependencies between application
     * subpackages. Callers translate to/from their DTO's enum as needed.
     */
    public enum AttributeMode {
        /** BMS UNPROT; editable field. */
        UNPROTECTED,
        /** BMS ASKIP,NORM; display-only field. */
        PROTECTED,
        /** BMS ASKIP,BRT; highlighted protected field. */
        PROTECTED_HIGHLIGHTED,
        /** BMS UNPROT,BRT,DFHRED; editable field flagged in error. */
        ERROR
    }

    /**
     * Mirrors the COBOL CSSETATY template: returns the new attribute mode
     * and display value for a single field, given its validation flag, the
     * current mode/value, and the page context. The transformation rule:
     *
     * <ol>
     *   <li>If {@code context} is {@link PageContext#ENTER} the original mode
     *       and value are returned unchanged (the COBOL OUTER IF only fires
     *       on REENTER).</li>
     *   <li>If {@code flag} is {@link FieldFlag#OK} the original mode and
     *       value are returned unchanged.</li>
     *   <li>If {@code flag} is {@link FieldFlag#NOT_OK} the mode becomes
     *       {@link AttributeMode#ERROR}; the value is unchanged (the operator
     *       sees what they typed, colored red).</li>
     *   <li>If {@code flag} is {@link FieldFlag#BLANK} the mode becomes
     *       {@link AttributeMode#ERROR} and the value becomes
     *       {@link #BLANK_PLACEHOLDER}.</li>
     * </ol>
     *
     * @param flag         the validation flag for this field
     * @param context      the page context (ENTER or REENTER)
     * @param currentMode  the current BMS attribute mode for this field
     * @param currentValue the current display value (may be {@code null}; coerced to "")
     * @return the resulting {@link Outcome}; never {@code null}
     * @throws NullPointerException if {@code flag}, {@code context}, or
     *                              {@code currentMode} is {@code null}
     */
    public static Outcome setIfError(FieldFlag flag,
                                     PageContext context,
                                     AttributeMode currentMode,
                                     String currentValue) {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(currentMode, "currentMode");
        String value = currentValue == null ? "" : currentValue;

        if (context == PageContext.ENTER) {
            return new Outcome(currentMode, value);
        }
        return switch (flag) {
            case OK     -> new Outcome(currentMode, value);
            case NOT_OK -> new Outcome(AttributeMode.ERROR, value);
            case BLANK  -> new Outcome(AttributeMode.ERROR, BLANK_PLACEHOLDER);
        };
    }

    /**
     * Convenience overload: returns just the {@link AttributeMode} an error
     * field would acquire, without touching the value. Useful when the
     * caller manages the value directly.
     *
     * @param flag        the validation flag
     * @param context     the page context
     * @param currentMode the current mode
     * @return the resulting mode
     */
    public static AttributeMode modeFor(FieldFlag flag,
                                        PageContext context,
                                        AttributeMode currentMode) {
        return setIfError(flag, context, currentMode, "").mode();
    }
}
