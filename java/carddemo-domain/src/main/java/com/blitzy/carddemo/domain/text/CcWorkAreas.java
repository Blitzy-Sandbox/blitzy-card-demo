/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.text;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Working storage translated from {@code app/cpy/CVCRD01Y.cpy} (CC-WORK-AREAS).
 * Hosts:
 * <ul>
 *   <li>{@link AidKey}: a sealed hierarchy modelling every CCARD-AID 88-level
 *       condition (16 permits: Enter, Clear, Pa1, Pa2, PfKey01..PfKey12) per
 *       AAP &sect;0.6.10.</li>
 *   <li>{@link CcAcctId}, {@link CcCardNum}, {@link CcCustId}: sealed
 *       hierarchies translating the COBOL {@code REDEFINES} clauses that
 *       overlay an ASCII text view and a numeric view of the same memory
 *       region.</li>
 *   <li>Routing scratch fields used across CICS XCTL boundaries
 *       (CCARD-NEXT-PROG, CCARD-NEXT-MAPSET, CCARD-NEXT-MAP, CCARD-ERROR-MSG,
 *       CCARD-RETURN-MSG).</li>
 * </ul>
 *
 * <pre>{@code
 * 01 CC-WORK-AREAS.
 *    05 CC-WORK-AREA.
 *       10 CCARD-AID                PIC X(5).
 *          88 CCARD-AID-ENTER       VALUE 'ENTER'.
 *          88 CCARD-AID-CLEAR       VALUE 'CLEAR'.
 *          88 CCARD-AID-PA1         VALUE 'PA1  '.
 *          88 CCARD-AID-PA2         VALUE 'PA2  '.
 *          88 CCARD-AID-PFK01..PFK12 VALUE 'PFK01'..'PFK12'.
 *       10 CCARD-NEXT-PROG          PIC X(8).
 *       10 CCARD-NEXT-MAPSET        PIC X(7).
 *       10 CCARD-NEXT-MAP           PIC X(7).
 *       10 CCARD-ERROR-MSG          PIC X(75).
 *       10 CCARD-RETURN-MSG         PIC X(75).
 *       10 CC-ACCT-ID               PIC X(11) VALUE SPACES.
 *       10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11).
 *       10 CC-CARD-NUM              PIC X(16) VALUE SPACES.
 *       10 CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16).
 *       10 CC-CUST-ID               PIC X(09) VALUE SPACES.
 *       10 CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9).
 * }</pre>
 */
@CobolProgram(
        value = "CVCRD01Y",
        sourcePath = "app/cpy/CVCRD01Y.cpy",
        notes = "CC-WORK-AREAS; sealed AidKey (16 permits) + sealed REDEFINES for CC-ACCT-ID / CC-CARD-NUM / CC-CUST-ID; AAP §0.6.10"
)
public final class CcWorkAreas {

    private CcWorkAreas() {
        // Utility class
    }

    /**
     * Sealed hierarchy modelling the AID-key 88-level conditions on
     * {@code CCARD-AID PIC X(5)}. The discriminator factory selects from
     * the 16 closed permits; sites that switch on this MUST be exhaustive.
     */
    public sealed interface AidKey permits AidKey.Enter, AidKey.Clear, AidKey.Pa1, AidKey.Pa2,
            AidKey.PfKey01, AidKey.PfKey02, AidKey.PfKey03, AidKey.PfKey04,
            AidKey.PfKey05, AidKey.PfKey06, AidKey.PfKey07, AidKey.PfKey08,
            AidKey.PfKey09, AidKey.PfKey10, AidKey.PfKey11, AidKey.PfKey12 {

        Enter ENTER = new Enter();
        Clear CLEAR = new Clear();
        Pa1 PA1 = new Pa1();
        Pa2 PA2 = new Pa2();
        PfKey01 PFK01 = new PfKey01();
        PfKey02 PFK02 = new PfKey02();
        PfKey03 PFK03 = new PfKey03();
        PfKey04 PFK04 = new PfKey04();
        PfKey05 PFK05 = new PfKey05();
        PfKey06 PFK06 = new PfKey06();
        PfKey07 PFK07 = new PfKey07();
        PfKey08 PFK08 = new PfKey08();
        PfKey09 PFK09 = new PfKey09();
        PfKey10 PFK10 = new PfKey10();
        PfKey11 PFK11 = new PfKey11();
        PfKey12 PFK12 = new PfKey12();

        /** Returns the 5-character COBOL code (e.g., {@code "ENTER"}, {@code "PFK01"}). */
        String code();

        /**
         * Discriminator factory selecting the permit based on the COBOL
         * 5-character code. Unknown codes throw IllegalArgumentException
         * (mirroring the closed 88-level set).
         */
        static AidKey fromCode(String code) {
            if (code == null) {
                throw new IllegalArgumentException("AID code must not be null");
            }
            String padded = code.length() < 5
                    ? code + " ".repeat(5 - code.length())
                    : code.substring(0, 5);
            return switch (padded) {
                case "ENTER" -> ENTER;
                case "CLEAR" -> CLEAR;
                case "PA1  " -> PA1;
                case "PA2  " -> PA2;
                case "PFK01" -> PFK01;
                case "PFK02" -> PFK02;
                case "PFK03" -> PFK03;
                case "PFK04" -> PFK04;
                case "PFK05" -> PFK05;
                case "PFK06" -> PFK06;
                case "PFK07" -> PFK07;
                case "PFK08" -> PFK08;
                case "PFK09" -> PFK09;
                case "PFK10" -> PFK10;
                case "PFK11" -> PFK11;
                case "PFK12" -> PFK12;
                default -> throw new IllegalArgumentException(
                        "Unknown CCARD-AID code: '" + code + "'");
            };
        }

        record Enter() implements AidKey { @Override public String code() { return "ENTER"; } }
        record Clear() implements AidKey { @Override public String code() { return "CLEAR"; } }
        record Pa1() implements AidKey { @Override public String code() { return "PA1  "; } }
        record Pa2() implements AidKey { @Override public String code() { return "PA2  "; } }
        record PfKey01() implements AidKey { @Override public String code() { return "PFK01"; } }
        record PfKey02() implements AidKey { @Override public String code() { return "PFK02"; } }
        record PfKey03() implements AidKey { @Override public String code() { return "PFK03"; } }
        record PfKey04() implements AidKey { @Override public String code() { return "PFK04"; } }
        record PfKey05() implements AidKey { @Override public String code() { return "PFK05"; } }
        record PfKey06() implements AidKey { @Override public String code() { return "PFK06"; } }
        record PfKey07() implements AidKey { @Override public String code() { return "PFK07"; } }
        record PfKey08() implements AidKey { @Override public String code() { return "PFK08"; } }
        record PfKey09() implements AidKey { @Override public String code() { return "PFK09"; } }
        record PfKey10() implements AidKey { @Override public String code() { return "PFK10"; } }
        record PfKey11() implements AidKey { @Override public String code() { return "PFK11"; } }
        record PfKey12() implements AidKey { @Override public String code() { return "PFK12"; } }
    }

    /**
     * Sealed hierarchy modelling the REDEFINES of {@code CC-ACCT-ID PIC X(11)}
     * by {@code CC-ACCT-ID-N PIC 9(11)}: the same memory region can be viewed
     * as 11 ASCII characters or as an unsigned 11-digit number. Per AAP
     * &sect;0.6.10 this is a sealed interface with one permit per
     * alternative interpretation.
     */
    public sealed interface CcAcctId permits CcAcctId.Text, CcAcctId.Numeric {

        String value();

        /** Account ID viewed as 11-character ASCII. */
        record Text(String value) implements CcAcctId {
            public Text {
                value = value == null ? " ".repeat(11)
                        : value.length() < 11 ? value + " ".repeat(11 - value.length())
                        : value.length() == 11 ? value
                        : value.substring(0, 11);
            }
        }

        /** Account ID viewed as 11-digit unsigned long. */
        record Numeric(long acctId) implements CcAcctId {
            public Numeric {
                if (acctId < 0L || acctId > 99_999_999_999L) {
                    throw new IllegalArgumentException(
                            "acctId must be 0..99,999,999,999, got " + acctId);
                }
            }

            @Override
            public String value() {
                return String.format("%011d", acctId);
            }
        }
    }

    /**
     * Sealed hierarchy modelling the REDEFINES of {@code CC-CARD-NUM PIC X(16)}
     * by {@code CC-CARD-NUM-N PIC 9(16)}.
     */
    public sealed interface CcCardNum permits CcCardNum.Text, CcCardNum.Numeric {

        String value();

        /** PAN viewed as 16-character ASCII. */
        record Text(String value) implements CcCardNum {
            public Text {
                value = value == null ? " ".repeat(16)
                        : value.length() < 16 ? value + " ".repeat(16 - value.length())
                        : value.length() == 16 ? value
                        : value.substring(0, 16);
            }
        }

        /** PAN viewed as a 16-digit unsigned long. */
        record Numeric(long cardNum) implements CcCardNum {
            public Numeric {
                if (cardNum < 0L) {
                    throw new IllegalArgumentException("cardNum must be non-negative");
                }
            }

            @Override
            public String value() {
                return String.format("%016d", cardNum);
            }
        }
    }

    /**
     * Sealed hierarchy modelling the REDEFINES of {@code CC-CUST-ID PIC X(09)}
     * by {@code CC-CUST-ID-N PIC 9(9)}.
     */
    public sealed interface CcCustId permits CcCustId.Text, CcCustId.Numeric {

        String value();

        /** Customer ID viewed as 9-character ASCII. */
        record Text(String value) implements CcCustId {
            public Text {
                value = value == null ? " ".repeat(9)
                        : value.length() < 9 ? value + " ".repeat(9 - value.length())
                        : value.length() == 9 ? value
                        : value.substring(0, 9);
            }
        }

        /** Customer ID viewed as 9-digit unsigned long. */
        record Numeric(long custId) implements CcCustId {
            public Numeric {
                if (custId < 0L || custId > 999_999_999L) {
                    throw new IllegalArgumentException(
                            "custId must be 0..999,999,999, got " + custId);
                }
            }

            @Override
            public String value() {
                return String.format("%09d", custId);
            }
        }
    }

    /** Routing context populated by online programs before XCTL. */
    public record RoutingContext(
            AidKey aid,
            String nextProg,
            String nextMapset,
            String nextMap,
            String errorMsg,
            String returnMsg) {

        public RoutingContext {
            // aid may be null on first entry
            nextProg = nextProg == null ? "" : nextProg;
            nextMapset = nextMapset == null ? "" : nextMapset;
            nextMap = nextMap == null ? "" : nextMap;
            errorMsg = errorMsg == null ? "" : errorMsg;
            returnMsg = returnMsg == null ? "" : returnMsg;
        }

        public static RoutingContext empty() {
            return new RoutingContext(null, "", "", "", "", "");
        }
    }
}
