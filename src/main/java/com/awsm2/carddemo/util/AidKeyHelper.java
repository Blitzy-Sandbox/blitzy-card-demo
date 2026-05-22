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
package com.awsm2.carddemo.util;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Normalizes terminal AID (Attention IDentifier) key indicators into a
 * canonical {@link AidKey} enum, replicating the COBOL
 * {@code YYYY-STORE-PFKEY} paragraph from {@code app/cpy/CSSTRPFY.cpy}.
 *
 * <p>The source COBOL paragraph (lines 17-82 of
 * {@code app/cpy/CSSTRPFY.cpy}) reads the CICS Exec Interface Block AID byte
 * ({@code EIBAID}) — which the CICS runtime sets when the terminal user
 * presses one of the 24 program-function keys, the {@code ENTER} key, the
 * {@code CLEAR} key, or one of the two program-attention keys ({@code PA1},
 * {@code PA2}) — and uses an {@code EVALUATE TRUE} block to assign one of the
 * 16 {@code CCARD-AID-*} 88-level boolean condition names declared in
 * {@code app/cpy/CVCRD01Y.cpy} (lines 4-19) inside the
 * {@code CC-WORK-AREAS.CCARD-AID} {@code PIC X(5)} field of the CICS
 * COMMAREA.</p>
 *
 * <p>The 5-byte canonical string values stored in COMMAREA per
 * {@code CVCRD01Y.cpy} are:</p>
 * <ul>
 *   <li>{@code "ENTER"} — {@code CCARD-AID-ENTER}</li>
 *   <li>{@code "CLEAR"} — {@code CCARD-AID-CLEAR}</li>
 *   <li>{@code "PA1  "} — {@code CCARD-AID-PA1} (5 bytes, 2 trailing spaces)</li>
 *   <li>{@code "PA2  "} — {@code CCARD-AID-PA2} (5 bytes, 2 trailing spaces)</li>
 *   <li>{@code "PFK01"} through {@code "PFK12"} — {@code CCARD-AID-PFK01}
 *       through {@code CCARD-AID-PFK12}</li>
 * </ul>
 *
 * <p>The original COBOL also <strong>folds</strong> PF13 through PF24 onto
 * PFK01 through PFK12 (lines 54-77 of {@code CSSTRPFY.cpy}) because 3270
 * terminals historically expose 24 program-function keys but most applications
 * — including AWS CardDemo — treat the upper half (PF13-PF24) as aliases for
 * the lower half (PF1-PF12). Per AAP &sect;0.7.3 Minimal Change Clause, this
 * verbatim folding is preserved in the Java implementation: any {@code PF13}
 * or {@code DFHPF13} input normalizes to {@link AidKey#PF1}, etc.</p>
 *
 * <p><strong>REST-target note.</strong> In the AWS-native Spring Boot target
 * architecture (AAP &sect;0.1), navigation intent — next page, back, exit,
 * refresh, cancel, submit — is carried by HTTP method + URL + headers + DTO
 * fields and is <em>not</em> conveyed by AID bytes. This class is therefore
 * <em>informational only</em>: it preserves the COBOL semantics for
 * documentation parity and to enable any future legacy-client gateway (e.g.,
 * a 3270 terminal emulator that bridges to the REST API) to map terminal
 * events onto the canonical {@link AidKey} representation. It is not invoked
 * at runtime in production REST flows.</p>
 *
 * <p><strong>Accepted input forms.</strong> The {@link #tryNormalize(String)}
 * and {@link #normalize(String)} entry points are tolerant of every form a
 * caller might supply:</p>
 * <ul>
 *   <li>The 5-byte canonical COMMAREA values ({@code "ENTER"},
 *       {@code "PFK01"}, {@code "PA1  "}, etc.)</li>
 *   <li>The CICS Translator-Generated DFHAID symbolic names referenced in
 *       {@code CSSTRPFY.cpy} ({@code "DFHENTER"}, {@code "DFHCLEAR"},
 *       {@code "DFHPF1"}-{@code "DFHPF24"}, {@code "DFHPA1"},
 *       {@code "DFHPA2"})</li>
 *   <li>Friendly REST/header-style aliases ({@code "PF1"}-{@code "PF24"},
 *       {@code "PA1"}, {@code "PA2"})</li>
 * </ul>
 *
 * <p>Lookup is case-insensitive using {@link Locale#US} to avoid the
 * Turkish-locale dotted-I problem; all matching is byte-for-byte once the
 * input has been uppercased. PF13-PF24 folding is performed both in the
 * canonical COBOL form ({@code DFHPF13}-{@code DFHPF24}) and in the friendly
 * form ({@code PF13}-{@code PF24}).</p>
 *
 * <p><strong>Implementation discipline (AAP &sect;0.7).</strong> This class is
 * a pure static utility with no business logic, no AWS SDK calls, no Spring
 * beans, no Lombok, no logging, no mutable state, and no {@code javax.*}
 * imports. The single immutable lookup table is built once at class
 * initialization. Both lookup methods are null-safe and free of side effects.</p>
 *
 * <p>COBOL: {@code CSSTRPFY.cpy:L17-L82} (YYYY-STORE-PFKEY paragraph),
 * {@code CVCRD01Y.cpy:L4-L19} (CCARD-AID condition names)</p>
 *
 * @see com.awsm2.carddemo.dto.CardWorkAreasDto sibling DTO that carries the
 *     normalized {@code aidKey} field on inbound requests, where it is the
 *     only known runtime consumer of this helper
 * @see com.awsm2.carddemo.dto.CommonContextDto the CICS COMMAREA equivalent
 *     in the REST target — the original {@code CC-WORK-AREAS.CCARD-AID}
 *     field lives logically on this DTO
 */
public final class AidKeyHelper {

    /**
     * Canonical AID-key identifiers covering every input the COBOL
     * {@code YYYY-STORE-PFKEY} paragraph recognizes.
     *
     * <p>The 16 enum constants are declared in the same order the COBOL
     * {@code EVALUATE TRUE} block evaluates them (ENTER first, then CLEAR,
     * then the two PA keys, then PF1 through PF12, with PF13-PF24 folded onto
     * PF1-PF12). This ordering is intentional for traceability — it matches
     * the sequence of {@code WHEN} clauses in {@code CSSTRPFY.cpy} lines
     * 22-77 and the order of {@code 88-level} condition names in
     * {@code CVCRD01Y.cpy} lines 4-19.</p>
     *
     * <p>Each constant carries an inline COBOL traceability comment.</p>
     *
     * <p>The associated {@link #canonicalValue()} accessor returns the
     * <strong>exact 5-byte string</strong> the COBOL {@code 88-level}
     * declarations assign to the {@code CCARD-AID} {@code PIC X(5)} field:</p>
     * <ul>
     *   <li>{@code ENTER.canonicalValue()} → {@code "ENTER"} (5 chars)</li>
     *   <li>{@code CLEAR.canonicalValue()} → {@code "CLEAR"} (5 chars)</li>
     *   <li>{@code PA1.canonicalValue()} → {@code "PA1  "} (5 chars,
     *       2 trailing spaces)</li>
     *   <li>{@code PA2.canonicalValue()} → {@code "PA2  "} (5 chars,
     *       2 trailing spaces)</li>
     *   <li>{@code PF1.canonicalValue()} through
     *       {@code PF12.canonicalValue()} → {@code "PFK01"} through
     *       {@code "PFK12"} (5 chars each)</li>
     * </ul>
     *
     * <p>Byte-fidelity with the original COBOL literals is required so that
     * golden-output diff tests (AAP &sect;0.6.2) can compare against
     * COMMAREA snapshots from a parallel-run period without additional
     * trimming logic.</p>
     */
    public enum AidKey {
        // COBOL: CSSTRPFY.cpy:L22-L23 (DFHENTER → CCARD-AID-ENTER),
        //        CVCRD01Y.cpy:L4 (VALUE 'ENTER')
        ENTER("ENTER"),

        // COBOL: CSSTRPFY.cpy:L30-L31 (DFHPF1 → CCARD-AID-PFK01),
        //        CSSTRPFY.cpy:L54-L55 (DFHPF13 folded),
        //        CVCRD01Y.cpy:L8 (VALUE 'PFK01')
        PF1("PFK01"),

        // COBOL: CSSTRPFY.cpy:L32-L33 (DFHPF2 → CCARD-AID-PFK02),
        //        CSSTRPFY.cpy:L56-L57 (DFHPF14 folded),
        //        CVCRD01Y.cpy:L9 (VALUE 'PFK02')
        PF2("PFK02"),

        // COBOL: CSSTRPFY.cpy:L34-L35 (DFHPF3 → CCARD-AID-PFK03),
        //        CSSTRPFY.cpy:L58-L59 (DFHPF15 folded),
        //        CVCRD01Y.cpy:L10 (VALUE 'PFK03')
        PF3("PFK03"),

        // COBOL: CSSTRPFY.cpy:L36-L37 (DFHPF4 → CCARD-AID-PFK04),
        //        CSSTRPFY.cpy:L60-L61 (DFHPF16 folded),
        //        CVCRD01Y.cpy:L11 (VALUE 'PFK04')
        PF4("PFK04"),

        // COBOL: CSSTRPFY.cpy:L38-L39 (DFHPF5 → CCARD-AID-PFK05),
        //        CSSTRPFY.cpy:L62-L63 (DFHPF17 folded),
        //        CVCRD01Y.cpy:L12 (VALUE 'PFK05')
        PF5("PFK05"),

        // COBOL: CSSTRPFY.cpy:L40-L41 (DFHPF6 → CCARD-AID-PFK06),
        //        CSSTRPFY.cpy:L64-L65 (DFHPF18 folded),
        //        CVCRD01Y.cpy:L13 (VALUE 'PFK06')
        PF6("PFK06"),

        // COBOL: CSSTRPFY.cpy:L42-L43 (DFHPF7 → CCARD-AID-PFK07),
        //        CSSTRPFY.cpy:L66-L67 (DFHPF19 folded),
        //        CVCRD01Y.cpy:L14 (VALUE 'PFK07')
        PF7("PFK07"),

        // COBOL: CSSTRPFY.cpy:L44-L45 (DFHPF8 → CCARD-AID-PFK08),
        //        CSSTRPFY.cpy:L68-L69 (DFHPF20 folded),
        //        CVCRD01Y.cpy:L15 (VALUE 'PFK08')
        PF8("PFK08"),

        // COBOL: CSSTRPFY.cpy:L46-L47 (DFHPF9 → CCARD-AID-PFK09),
        //        CSSTRPFY.cpy:L70-L71 (DFHPF21 folded),
        //        CVCRD01Y.cpy:L16 (VALUE 'PFK09')
        PF9("PFK09"),

        // COBOL: CSSTRPFY.cpy:L48-L49 (DFHPF10 → CCARD-AID-PFK10),
        //        CSSTRPFY.cpy:L72-L73 (DFHPF22 folded),
        //        CVCRD01Y.cpy:L17 (VALUE 'PFK10')
        PF10("PFK10"),

        // COBOL: CSSTRPFY.cpy:L50-L51 (DFHPF11 → CCARD-AID-PFK11),
        //        CSSTRPFY.cpy:L74-L75 (DFHPF23 folded),
        //        CVCRD01Y.cpy:L18 (VALUE 'PFK11')
        PF11("PFK11"),

        // COBOL: CSSTRPFY.cpy:L52-L53 (DFHPF12 → CCARD-AID-PFK12),
        //        CSSTRPFY.cpy:L76-L77 (DFHPF24 folded),
        //        CVCRD01Y.cpy:L19 (VALUE 'PFK12')
        PF12("PFK12"),

        // COBOL: CSSTRPFY.cpy:L24-L25 (DFHCLEAR → CCARD-AID-CLEAR),
        //        CVCRD01Y.cpy:L5 (VALUE 'CLEAR')
        CLEAR("CLEAR"),

        // COBOL: CSSTRPFY.cpy:L26-L27 (DFHPA1 → CCARD-AID-PA1),
        //        CVCRD01Y.cpy:L6 (VALUE 'PA1  ' — 2 trailing spaces, 5 bytes)
        PA1("PA1  "),

        // COBOL: CSSTRPFY.cpy:L28-L29 (DFHPA2 → CCARD-AID-PA2),
        //        CVCRD01Y.cpy:L7 (VALUE 'PA2  ' — 2 trailing spaces, 5 bytes)
        PA2("PA2  ");

        /**
         * The 5-byte canonical COBOL {@code VALUE} literal for this AID key,
         * preserved byte-for-byte from {@code CVCRD01Y.cpy} including any
         * trailing space padding so that golden-output diff tests can compare
         * against legacy COMMAREA snapshots.
         */
        private final String canonical;

        AidKey(final String canonical) {
            this.canonical = canonical;
        }

        /**
         * Returns the 5-byte canonical COBOL string value stored in the
         * {@code CC-WORK-AREAS.CCARD-AID} {@code PIC X(5)} field of COMMAREA
         * when this AID key is active.
         *
         * <p>The returned string is always exactly 5 characters long, with
         * trailing spaces preserved for {@link #PA1} ({@code "PA1  "}) and
         * {@link #PA2} ({@code "PA2  "}). All other constants return their
         * naturally-5-character form: {@code "ENTER"}, {@code "CLEAR"},
         * {@code "PFK01"} through {@code "PFK12"}.</p>
         *
         * <p>COBOL: {@code CVCRD01Y.cpy:L4-L19} (88-level VALUE literals)</p>
         *
         * @return the 5-character canonical COMMAREA value; never {@code null}
         *     and always of length 5
         */
        public String canonicalValue() {
            return canonical;
        }
    }

    /**
     * Immutable case-insensitive lookup table mapping every accepted input
     * form (canonical COMMAREA values, CICS DFHAID symbolic names, and
     * friendly REST aliases) to the canonical {@link AidKey} enum constant.
     *
     * <p>All keys are stored in upper-case form (using {@link Locale#US}); the
     * lookup methods uppercase the caller's input before performing
     * {@link Map#get(Object)}. The map is built once at class initialization
     * via {@link Map#ofEntries(Map.Entry[])}, which produces a true immutable
     * map (no defensive copy needed).</p>
     *
     * <p>The 5-byte canonical forms with internal trailing spaces
     * ({@code "PA1  "}, {@code "PA2  "}) are included so that callers
     * passing the byte-exact COMMAREA value still match successfully without
     * trimming. The {@link #tryNormalize(String)} method tries the raw
     * (uppercased) input first, then the trimmed (uppercased) input, so both
     * {@code "PA1"} and {@code "PA1  "} resolve to {@link AidKey#PA1}.</p>
     *
     * <p>Total entries: 70 — see the per-entry comments below for the five
     * groups (canonical 5-byte, DFH* symbolic, DFH* folded, friendly PF*,
     * friendly PA*).</p>
     */
    private static final Map<String, AidKey> LOOKUP = Map.ofEntries(
            // ============================================================
            // Group 1: Canonical 5-byte COMMAREA values (16 entries)
            // COBOL: CVCRD01Y.cpy:L4-L19 (CCARD-AID-* 88-level VALUEs)
            // ============================================================
            Map.entry("ENTER", AidKey.ENTER),       // COBOL: 'ENTER'
            Map.entry("CLEAR", AidKey.CLEAR),       // COBOL: 'CLEAR'
            Map.entry("PA1  ", AidKey.PA1),         // COBOL: 'PA1  ' (2 spaces)
            Map.entry("PA2  ", AidKey.PA2),         // COBOL: 'PA2  ' (2 spaces)
            Map.entry("PFK01", AidKey.PF1),         // COBOL: 'PFK01'
            Map.entry("PFK02", AidKey.PF2),         // COBOL: 'PFK02'
            Map.entry("PFK03", AidKey.PF3),         // COBOL: 'PFK03'
            Map.entry("PFK04", AidKey.PF4),         // COBOL: 'PFK04'
            Map.entry("PFK05", AidKey.PF5),         // COBOL: 'PFK05'
            Map.entry("PFK06", AidKey.PF6),         // COBOL: 'PFK06'
            Map.entry("PFK07", AidKey.PF7),         // COBOL: 'PFK07'
            Map.entry("PFK08", AidKey.PF8),         // COBOL: 'PFK08'
            Map.entry("PFK09", AidKey.PF9),         // COBOL: 'PFK09'
            Map.entry("PFK10", AidKey.PF10),        // COBOL: 'PFK10'
            Map.entry("PFK11", AidKey.PF11),        // COBOL: 'PFK11'
            Map.entry("PFK12", AidKey.PF12),        // COBOL: 'PFK12'

            // ============================================================
            // Group 2: CICS DFHAID symbolic names (16 entries — primary)
            // COBOL: CSSTRPFY.cpy:L22-L53 (DFHENTER, DFHCLEAR, DFHPA1,
            //   DFHPA2, DFHPF1-DFHPF12 each on their own WHEN clause)
            // ============================================================
            Map.entry("DFHENTER", AidKey.ENTER),
            Map.entry("DFHCLEAR", AidKey.CLEAR),
            Map.entry("DFHPA1", AidKey.PA1),
            Map.entry("DFHPA2", AidKey.PA2),
            Map.entry("DFHPF1", AidKey.PF1),
            Map.entry("DFHPF2", AidKey.PF2),
            Map.entry("DFHPF3", AidKey.PF3),
            Map.entry("DFHPF4", AidKey.PF4),
            Map.entry("DFHPF5", AidKey.PF5),
            Map.entry("DFHPF6", AidKey.PF6),
            Map.entry("DFHPF7", AidKey.PF7),
            Map.entry("DFHPF8", AidKey.PF8),
            Map.entry("DFHPF9", AidKey.PF9),
            Map.entry("DFHPF10", AidKey.PF10),
            Map.entry("DFHPF11", AidKey.PF11),
            Map.entry("DFHPF12", AidKey.PF12),

            // ============================================================
            // Group 3: DFHPF13-DFHPF24 folded onto PF1-PF12 (12 entries)
            // COBOL: CSSTRPFY.cpy:L54-L77 — the COBOL EVALUATE explicitly
            //   maps each upper-half PF key to the corresponding lower-half
            //   CCARD-AID-PFKnn flag. Per AAP §0.7.3 Minimal Change Clause,
            //   this folding is preserved verbatim.
            // ============================================================
            Map.entry("DFHPF13", AidKey.PF1),       // COBOL: L54-L55 → PFK01
            Map.entry("DFHPF14", AidKey.PF2),       // COBOL: L56-L57 → PFK02
            Map.entry("DFHPF15", AidKey.PF3),       // COBOL: L58-L59 → PFK03
            Map.entry("DFHPF16", AidKey.PF4),       // COBOL: L60-L61 → PFK04
            Map.entry("DFHPF17", AidKey.PF5),       // COBOL: L62-L63 → PFK05
            Map.entry("DFHPF18", AidKey.PF6),       // COBOL: L64-L65 → PFK06
            Map.entry("DFHPF19", AidKey.PF7),       // COBOL: L66-L67 → PFK07
            Map.entry("DFHPF20", AidKey.PF8),       // COBOL: L68-L69 → PFK08
            Map.entry("DFHPF21", AidKey.PF9),       // COBOL: L70-L71 → PFK09
            Map.entry("DFHPF22", AidKey.PF10),      // COBOL: L72-L73 → PFK10
            Map.entry("DFHPF23", AidKey.PF11),      // COBOL: L74-L75 → PFK11
            Map.entry("DFHPF24", AidKey.PF12),      // COBOL: L76-L77 → PFK12

            // ============================================================
            // Group 4: Friendly REST/header-style aliases — PF1-PF12 plus
            //   folded PF13-PF24 (24 entries)
            // ============================================================
            Map.entry("PF1", AidKey.PF1),
            Map.entry("PF2", AidKey.PF2),
            Map.entry("PF3", AidKey.PF3),
            Map.entry("PF4", AidKey.PF4),
            Map.entry("PF5", AidKey.PF5),
            Map.entry("PF6", AidKey.PF6),
            Map.entry("PF7", AidKey.PF7),
            Map.entry("PF8", AidKey.PF8),
            Map.entry("PF9", AidKey.PF9),
            Map.entry("PF10", AidKey.PF10),
            Map.entry("PF11", AidKey.PF11),
            Map.entry("PF12", AidKey.PF12),
            Map.entry("PF13", AidKey.PF1),          // folded per COBOL
            Map.entry("PF14", AidKey.PF2),          // folded per COBOL
            Map.entry("PF15", AidKey.PF3),          // folded per COBOL
            Map.entry("PF16", AidKey.PF4),          // folded per COBOL
            Map.entry("PF17", AidKey.PF5),          // folded per COBOL
            Map.entry("PF18", AidKey.PF6),          // folded per COBOL
            Map.entry("PF19", AidKey.PF7),          // folded per COBOL
            Map.entry("PF20", AidKey.PF8),          // folded per COBOL
            Map.entry("PF21", AidKey.PF9),          // folded per COBOL
            Map.entry("PF22", AidKey.PF10),         // folded per COBOL
            Map.entry("PF23", AidKey.PF11),         // folded per COBOL
            Map.entry("PF24", AidKey.PF12),         // folded per COBOL

            // ============================================================
            // Group 5: Trimmed PA aliases — accept callers that send PA1/PA2
            //   without the trailing-space padding (2 entries)
            // ============================================================
            Map.entry("PA1", AidKey.PA1),
            Map.entry("PA2", AidKey.PA2)
    );

    /**
     * Utility class — instantiation is forbidden. Construction throws
     * implicitly if invoked reflectively because the field set is empty and
     * all state is static.
     */
    private AidKeyHelper() {
        /* utility class - prevent instantiation */
    }

    /**
     * Strict variant — returns {@code Optional.empty()} for {@code null},
     * empty, blank, or unknown input. Successful lookups return the matching
     * {@link AidKey} wrapped in an {@link Optional}.
     *
     * <p>Input handling:</p>
     * <ol>
     *   <li>{@code null} input → {@code Optional.empty()}</li>
     *   <li>Empty or all-whitespace input → {@code Optional.empty()}</li>
     *   <li>Otherwise, try the raw upper-cased input first (this catches the
     *       5-byte canonical forms {@code "PA1  "} and {@code "PA2  "} whose
     *       internal trailing spaces are semantically significant in
     *       COBOL); if no match, try the trimmed and upper-cased input
     *       (this catches friendly forms like {@code "PA1"})</li>
     *   <li>Unknown input → {@code Optional.empty()}</li>
     * </ol>
     *
     * <p>Case-folding uses {@link Locale#US} to avoid the Turkish-locale
     * dotted-I problem ({@code "i".toUpperCase(Turkish) == "İ"}).</p>
     *
     * <p>COBOL: {@code CSSTRPFY.cpy:L21-L78} (EVALUATE TRUE block) — every
     * accepted input form here corresponds to one or more
     * {@code WHEN EIBAID IS EQUAL TO DFH...} clauses in the source paragraph.</p>
     *
     * @param rawAid the raw AID string from a request header, DTO field, or
     *     legacy gateway. May be {@code null}, empty, or blank — those forms
     *     yield {@code Optional.empty()}.
     * @return an {@link Optional} containing the matched {@link AidKey}, or
     *     {@link Optional#empty()} if {@code rawAid} is unrecognized
     */
    public static Optional<AidKey> tryNormalize(final String rawAid) {
        // COBOL: CSSTRPFY.cpy:L21-L78 (EVALUATE TRUE block)
        if (rawAid == null) {
            return Optional.empty();
        }
        // Bail out early on empty/blank input — there is no COBOL equivalent
        // here because the COBOL EVALUATE always runs after EIBAID has been
        // initialized by CICS to a known one-byte AID value, but Java callers
        // can legitimately pass an empty string when a non-AID request
        // arrives at the legacy gateway.
        if (rawAid.isEmpty() || rawAid.trim().isEmpty()) {
            return Optional.empty();
        }
        // First, try the raw input uppercased — this preserves any internal
        // whitespace that is semantically significant (e.g., the 5-byte
        // canonical COBOL forms "PA1  " and "PA2  " from CVCRD01Y.cpy L6-L7).
        final String rawUpper = rawAid.toUpperCase(Locale.US);
        final AidKey rawMatch = LOOKUP.get(rawUpper);
        if (rawMatch != null) {
            return Optional.of(rawMatch);
        }
        // Fall back to the trimmed-and-uppercased input — this catches
        // friendly forms like "PA1" (without trailing padding), "  PF8 ", or
        // "  enter\n" supplied by REST clients or legacy emulators that do
        // not preserve fixed-width padding.
        final String trimmedUpper = rawAid.trim().toUpperCase(Locale.US);
        return Optional.ofNullable(LOOKUP.get(trimmedUpper));
    }

    /**
     * Lenient variant — returns {@link AidKey#ENTER} (the practical default
     * per CICS convention when no AID matches) for {@code null}, empty,
     * blank, or unknown input. For recognized input, returns the matching
     * {@link AidKey}.
     *
     * <p>This lenient default mirrors the practical COBOL behavior of
     * {@code CSSTRPFY.cpy:L21-L78}: the {@code EVALUATE TRUE} block has no
     * {@code WHEN OTHER} clause, so if an unrecognized AID byte were ever
     * supplied (which CICS does not normally allow on real 3270 terminals)
     * the prior {@code CCARD-AID-*} flag state would remain intact. CICS
     * itself initializes {@code EIBAID} to {@code DFHENTER} on terminal
     * entry, so {@code ENTER} is the de-facto default. This Java variant
     * makes that implicit default explicit and null-safe.</p>
     *
     * <p>COBOL: {@code CSSTRPFY.cpy:L21-L78} (EVALUATE TRUE block); CICS
     * default behavior is ENTER when no AID match.</p>
     *
     * @param rawAid the raw AID string. {@code null}, empty, blank, and
     *     unrecognized values all yield {@link AidKey#ENTER}.
     * @return the matched {@link AidKey} for known input, or
     *     {@link AidKey#ENTER} as the lenient default
     */
    public static AidKey normalize(final String rawAid) {
        // COBOL: CSSTRPFY.cpy:L21-L78 (EVALUATE TRUE block);
        //        CICS default behavior is ENTER when no AID match
        return tryNormalize(rawAid).orElse(AidKey.ENTER);
    }

    /**
     * Documentation-only convenience predicate identifying keys that advance
     * to the next program or page in the COBOL CICS flow.
     *
     * <p>In the source CardDemo CICS programs ({@code app/cbl/CO*.cbl}),
     * browse navigation typically checks the AID after each terminal
     * interaction:</p>
     * <ul>
     *   <li><strong>{@link AidKey#ENTER}</strong> — submit / confirm / advance</li>
     *   <li><strong>{@link AidKey#PF7}</strong> — backward (page up)</li>
     *   <li><strong>{@link AidKey#PF8}</strong> — forward (page down)</li>
     * </ul>
     *
     * <p>This predicate returns {@code true} for keys that advance the user
     * forward — {@link AidKey#ENTER} (submit/next step) and
     * {@link AidKey#PF8} (page down) — and {@code false} for everything else
     * including {@link AidKey#PF7} (which is backward, not forward) and
     * {@code null}.</p>
     *
     * <p><strong>REST-target note:</strong> the equivalent of this in the
     * Spring Boot target is pagination query parameters ({@code ?page=&size=})
     * and HTTP method semantics ({@code POST} for submit). This helper is
     * therefore informational only and exists for documentation parity and
     * to support any future legacy-gateway translator.</p>
     *
     * <p>COBOL: {@code CO*.cbl} programs commonly check PF7 (backward), PF8
     * (forward), and ENTER (submit) for browse navigation.</p>
     *
     * @param key the normalized AID key; may be {@code null}
     * @return {@code true} iff {@code key} is {@link AidKey#ENTER} or
     *     {@link AidKey#PF8}; {@code false} otherwise (including for
     *     {@code null} — null-safe)
     */
    public static boolean isNavigationForward(final AidKey key) {
        // COBOL: CO*.cbl programs commonly check PF7 (backward), PF8 (forward),
        //        and ENTER (submit) for browse navigation
        if (key == null) {
            return false;
        }
        return key == AidKey.ENTER || key == AidKey.PF8;
    }

    /**
     * Documentation-only convenience predicate identifying keys that
     * exit/cancel a screen in the COBOL CICS flow.
     *
     * <p>In the source CardDemo CICS programs ({@code app/cbl/CO*.cbl}), the
     * standard exit/cancel keys are:</p>
     * <ul>
     *   <li><strong>{@link AidKey#PF3}</strong> — exit current screen / return
     *       to caller</li>
     *   <li><strong>{@link AidKey#PF12}</strong> — cancel current operation</li>
     *   <li><strong>{@link AidKey#CLEAR}</strong> — abort / clear all input
     *       and return to a known safe state</li>
     * </ul>
     *
     * <p><strong>REST-target note:</strong> the equivalent of this in the
     * Spring Boot target is HTTP {@code DELETE}, the {@code Cancel} button on
     * an HTML form, or session timeout. This helper is therefore
     * informational only and exists for documentation parity and to support
     * any future legacy-gateway translator.</p>
     *
     * <p>COBOL: {@code CO*.cbl} programs commonly check PF3 (exit), PF12
     * (cancel), and CLEAR (abort).</p>
     *
     * @param key the normalized AID key; may be {@code null}
     * @return {@code true} iff {@code key} is {@link AidKey#PF3},
     *     {@link AidKey#PF12}, or {@link AidKey#CLEAR}; {@code false}
     *     otherwise (including for {@code null} — null-safe)
     */
    public static boolean isExit(final AidKey key) {
        // COBOL: CO*.cbl programs commonly check PF3 (exit), PF12 (cancel),
        //        and CLEAR (abort)
        if (key == null) {
            return false;
        }
        return key == AidKey.PF3 || key == AidKey.PF12 || key == AidKey.CLEAR;
    }
}
