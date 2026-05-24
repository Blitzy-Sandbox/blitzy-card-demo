/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.util;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;

import java.util.Objects;

/**
 * Java translation of the {@code CSSTRPFY} COBOL procedure copybook at
 * {@code app/cpy/CSSTRPFY.cpy}. The original copybook is a procedure
 * template that maps the CICS {@code EIBAID} byte (the Attention IDentifier
 * raised by the 3270 terminal) to the corresponding {@code CCARD-AID-*}
 * 88-level condition on {@code CCARD-AID} in the {@code CVCRD01Y} work area.
 *
 * <p>The EVALUATE in the COBOL copybook is shown abbreviated:
 * <pre>{@code
 *  EVALUATE TRUE
 *    WHEN EIBAID = DFHENTER  SET CCARD-AID-ENTER TO TRUE
 *    WHEN EIBAID = DFHCLEAR  SET CCARD-AID-CLEAR TO TRUE
 *    WHEN EIBAID = DFHPA1    SET CCARD-AID-PA1   TO TRUE
 *    WHEN EIBAID = DFHPA2    SET CCARD-AID-PA2   TO TRUE
 *    WHEN EIBAID = DFHPF1    SET CCARD-AID-PFK01 TO TRUE
 *    ... (PF2-PF12 map to PFK02-PFK12)
 *    WHEN EIBAID = DFHPF13   SET CCARD-AID-PFK01 TO TRUE  (alias)
 *    ... (PF14-PF24 alias to PFK02-PFK12)
 *  END-EVALUATE
 * }</pre>
 *
 * <p>In Java with finalized JEP 511 (Module Import Declarations) and sealed
 * types the equivalent reads as a single static lookup that returns the
 * {@link AidKey} sealed-type instance corresponding to a CICS DFH-AID
 * symbol or to a PF-key number 1..24. AAP &sect;0.6.10 mandates the
 * sealed-type representation so call sites enjoy compiler-enforced
 * exhaustiveness in their pattern-matching switches.
 *
 * <p>This class is stateless; all methods are static.
 */
@CobolProgram(
        value = "CSSTRPFY",
        sourcePath = "app/cpy/CSSTRPFY.cpy",
        notes = "Procedure copybook: maps CICS EIBAID byte to CCARD-AID-* via AidKey sealed type"
)
public final class PfKeyDecoder {

    private PfKeyDecoder() {
        // Utility class: no instances
    }

    /**
     * The fixed set of CICS AID symbol names that the COBOL copybook
     * recognizes. Each constant is exposed both as a String (for raw
     * comparison against CICS-supplied symbols) and resolved via
     * {@link #decode(String)} below.
     */
    public static final String SYM_ENTER  = "DFHENTER";
    public static final String SYM_CLEAR  = "DFHCLEAR";
    public static final String SYM_PA1    = "DFHPA1";
    public static final String SYM_PA2    = "DFHPA2";
    /** Family prefix for PF keys: SYM_PF + n where n in 1..24. */
    public static final String SYM_PF_PREFIX = "DFHPF";

    /**
     * Maps a CICS AID symbol name ({@code DFHENTER}, {@code DFHCLEAR},
     * {@code DFHPA1}, {@code DFHPA2}, {@code DFHPF1}..{@code DFHPF24}) to the
     * corresponding {@link AidKey} sealed-type instance. Comparisons are
     * case-insensitive and ignore leading/trailing whitespace.
     *
     * <p>PF13..PF24 are alias-mapped to PFK01..PFK12 to preserve the COBOL
     * copybook semantics exactly. Unknown symbols map to
     * {@link AidKey#ENTER} as a defensive default. This matches the COBOL
     * behavior where an unrecognized EIBAID leaves {@code CCARD-AID} at its
     * previously-set value (typically ENTER on first invocation).
     *
     * @param aidSymbol the CICS AID symbol name, or {@code null} for
     *                  defensive default
     * @return the matching {@link AidKey}; never {@code null}
     */
    public static AidKey decode(String aidSymbol) {
        if (aidSymbol == null || aidSymbol.isBlank()) {
            return AidKey.ENTER;
        }
        String s = aidSymbol.trim().toUpperCase();
        return switch (s) {
            case SYM_ENTER -> AidKey.ENTER;
            case SYM_CLEAR -> AidKey.CLEAR;
            case SYM_PA1   -> AidKey.PA1;
            case SYM_PA2   -> AidKey.PA2;
            default -> decodePfSymbol(s);
        };
    }

    /**
     * Decodes a {@code DFHPFn} symbol where {@code n} is 1..24, applying
     * the 13..24 -&gt; 01..12 aliasing per the COBOL copybook. Unrecognized
     * symbols return {@link AidKey#ENTER}.
     */
    private static AidKey decodePfSymbol(String s) {
        if (!s.startsWith(SYM_PF_PREFIX)) {
            return AidKey.ENTER;
        }
        String suffix = s.substring(SYM_PF_PREFIX.length());
        int n;
        try {
            n = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return AidKey.ENTER;
        }
        return decodePfNumber(n);
    }

    /**
     * Maps a PF-key number (1..24) to the corresponding {@link AidKey}.
     * Numbers 13..24 alias to 1..12 per the COBOL copybook. Out-of-range
     * numbers return {@link AidKey#ENTER}.
     *
     * @param pfNumber the PF-key number (1..24); other values default to ENTER
     * @return the matching {@link AidKey}; never {@code null}
     */
    public static AidKey decodePfNumber(int pfNumber) {
        if (pfNumber < 1 || pfNumber > 24) {
            return AidKey.ENTER;
        }
        int normalized = (pfNumber > 12) ? (pfNumber - 12) : pfNumber;
        return switch (normalized) {
            case 1  -> AidKey.PFK01;
            case 2  -> AidKey.PFK02;
            case 3  -> AidKey.PFK03;
            case 4  -> AidKey.PFK04;
            case 5  -> AidKey.PFK05;
            case 6  -> AidKey.PFK06;
            case 7  -> AidKey.PFK07;
            case 8  -> AidKey.PFK08;
            case 9  -> AidKey.PFK09;
            case 10 -> AidKey.PFK10;
            case 11 -> AidKey.PFK11;
            case 12 -> AidKey.PFK12;
            default -> AidKey.ENTER; // unreachable given the guard; for total exhaustiveness
        };
    }

    /**
     * Returns the canonical CICS AID symbol name for an {@link AidKey}.
     * This is the inverse of {@link #decode(String)} and supports test
     * harnesses and structured logging.
     *
     * @param aid the AID instance (non-null)
     * @return the canonical 6..8 character symbol name
     */
    public static String symbolOf(AidKey aid) {
        Objects.requireNonNull(aid, "aid");
        return switch (aid) {
            case AidKey.Enter   _ -> SYM_ENTER;
            case AidKey.Clear   _ -> SYM_CLEAR;
            case AidKey.Pa1     _ -> SYM_PA1;
            case AidKey.Pa2     _ -> SYM_PA2;
            case AidKey.PfKey01 _ -> SYM_PF_PREFIX + "1";
            case AidKey.PfKey02 _ -> SYM_PF_PREFIX + "2";
            case AidKey.PfKey03 _ -> SYM_PF_PREFIX + "3";
            case AidKey.PfKey04 _ -> SYM_PF_PREFIX + "4";
            case AidKey.PfKey05 _ -> SYM_PF_PREFIX + "5";
            case AidKey.PfKey06 _ -> SYM_PF_PREFIX + "6";
            case AidKey.PfKey07 _ -> SYM_PF_PREFIX + "7";
            case AidKey.PfKey08 _ -> SYM_PF_PREFIX + "8";
            case AidKey.PfKey09 _ -> SYM_PF_PREFIX + "9";
            case AidKey.PfKey10 _ -> SYM_PF_PREFIX + "10";
            case AidKey.PfKey11 _ -> SYM_PF_PREFIX + "11";
            case AidKey.PfKey12 _ -> SYM_PF_PREFIX + "12";
        };
    }

    /**
     * Returns the PF-key number (1..12) for a PFKey AID, or 0 for
     * non-PF AIDs (ENTER/CLEAR/PA1/PA2). Convenience accessor for
     * application classes that already pattern-match on {@link AidKey}.
     *
     * @param aid the AID instance (non-null)
     * @return the PF number 1..12, or 0 for non-PF AIDs
     */
    public static int pfNumberOf(AidKey aid) {
        Objects.requireNonNull(aid, "aid");
        return switch (aid) {
            case AidKey.Enter _, AidKey.Clear _,
                 AidKey.Pa1 _, AidKey.Pa2 _ -> 0;
            case AidKey.PfKey01 _ -> 1;
            case AidKey.PfKey02 _ -> 2;
            case AidKey.PfKey03 _ -> 3;
            case AidKey.PfKey04 _ -> 4;
            case AidKey.PfKey05 _ -> 5;
            case AidKey.PfKey06 _ -> 6;
            case AidKey.PfKey07 _ -> 7;
            case AidKey.PfKey08 _ -> 8;
            case AidKey.PfKey09 _ -> 9;
            case AidKey.PfKey10 _ -> 10;
            case AidKey.PfKey11 _ -> 11;
            case AidKey.PfKey12 _ -> 12;
        };
    }
}
