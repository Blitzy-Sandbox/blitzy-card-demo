/*
 * ******************************************************************
 * Program     : TransactionSource.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 enumeration
 * Function    : The two literal TRAN-SOURCE values assigned in the
 *               COBOL corpus. TRAN-SOURCE is PIC X(10) occupying
 *               bytes 23-32 of the 350 byte transaction record. Only
 *               two of the four assignment sites move a literal; the
 *               remaining two pass arbitrary ten character text
 *               through unchanged, so this type names the two known
 *               literals and is deliberately NOT the persisted type
 *               of the column.
 * Source      : app/cpy/CVTRA05Y.cpy:L8 @ 7756d89
 * Source      : app/cpy/CVTRA06Y.cpy:L8 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L484 @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L222 @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L454 @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L428 @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.enums;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The two literal {@code TRAN-SOURCE} values assigned anywhere in the frozen CardDemo COBOL corpus.
 *
 * <p>This is a pure value type. It holds no mutable state, performs no I/O, reads no configuration, emits
 * no log output and depends on nothing outside {@code java.util}. Every method is a total, pure function
 * of its arguments and of the two immutable constants declared below.
 *
 * <h2>Field contract: {@code PIC X(10)} at bytes 23-32</h2>
 *
 * <p>{@code app/cpy/CVTRA05Y.cpy:L8} declares {@code 05 TRAN-SOURCE PIC X(10).} within the record whose
 * copybook header at {@code app/cpy/CVTRA05Y.cpy:L2} reads
 * {@code Data-structure for TRANsaction record (RECLN = 350)}. The three fields that precede it -
 * {@code TRAN-ID X(16)}, {@code TRAN-TYPE-CD X(02)} and {@code TRAN-CAT-CD 9(04)} - occupy 22 bytes, so
 * the field spans <strong>bytes 23 to 32 inclusive</strong>. The staging record mirrors it at the very
 * same offset: {@code app/cpy/CVTRA06Y.cpy:L8} declares {@code 05 DALYTRAN-SOURCE PIC X(10).}
 *
 * <p>A COBOL alphanumeric field is left justified and blank filled, so the external form of any value is
 * padded on the right to exactly {@value #FIELD_LENGTH} characters. That padding is load bearing: the
 * transaction image is emitted at a fixed 350 bytes and the reject image at 430, so a value of the wrong
 * width shifts every following field and corrupts the record. The two accessors keep the two forms
 * distinct and unambiguous - {@link #getCode()} returns the unpadded literal exactly as the COBOL
 * {@code MOVE} spells it, and {@link #getFixedWidthValue()} returns the ten character padded form for
 * fixed width emission.
 *
 * <h2>This enum is deliberately NOT the persisted type of the column</h2>
 *
 * <p><strong>Severity Medium.</strong> The {@code TRAN-SOURCE} column is <em>not</em> a closed domain, so
 * {@code transactionSource} on {@code com.cardemo.model.entity.Transaction} and on
 * {@code com.cardemo.model.entity.DailyTransaction} is a {@code String} mapped to {@code CHAR(10)}, never
 * this enum, and carries no {@code jakarta.persistence.Enumerated} annotation and no attribute converter.
 * This type names the two <em>known literals</em> only; it is a recognition aid, not the domain of the
 * column. Narrowing the mapping to an enumerated type would reject or mangle legitimate legacy data and
 * would break the parity gates that compare emitted records against the frozen baseline.
 *
 * <h2>The four assignment sites, and why only two are literals</h2>
 *
 * <p>The census must be taken with a <strong>whitespace tolerant</strong> pattern. The naive
 * {@code grep -rn "TO TRAN-SOURCE" app/cbl/} reports only three sites, because
 * {@code app/cbl/CBTRN02C.cbl:L428} is written with four spaces between {@code TO} and the field name.
 * The correct verification step is {@code grep -rnE "TO +TRAN-SOURCE" app/cbl/}, which reports exactly
 * four:
 *
 * <ol>
 *   <li>{@code app/cbl/CBACT04C.cbl:L484} - {@code MOVE 'System' TO TRAN-SOURCE}. A literal, modelled
 *       here as {@link #SYSTEM}.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:L222} - {@code MOVE 'POS TERM' TO TRAN-SOURCE}. A literal, modelled
 *       here as {@link #POS_TERMINAL}.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl:L454} - {@code MOVE TRNSRCI OF COTRN2AI TO TRAN-SOURCE}. <em>Not</em>
 *       a literal. {@code app/cpy-bms/COTRN02.CPY:L84} declares {@code 02 TRNSRCI PIC X(10).}, and the
 *       trailing {@code I} marks a BMS <em>input</em> field, so this statement moves raw terminal
 *       keystrokes straight into the record with no validation, no table lookup and no domain check of
 *       any kind.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L428} - {@code MOVE DALYTRAN-SOURCE TO TRAN-SOURCE}, inside
 *       {@code 2000-POST-TRANSACTION}. <em>Not</em> a literal. It copies bytes 23-32 of the staging
 *       record verbatim, without examining them.</li>
 * </ol>
 *
 * <p>Sites 3 and 4 are the proof that the column is open: whatever ten characters reach a screen field or
 * a staging record reach the column unchanged. The correct model is therefore <strong>two named literals
 * plus arbitrary ten character text flowing through untouched</strong>.
 *
 * <h2>Why {@code OPERATOR} is not a third constant</h2>
 *
 * <p><strong>Severity Medium.</strong> A census of bytes 23-32 across the 300 rows of the Gate 1 fixture,
 * {@code cut -c23-32 app/data/ASCII/dailytran.txt | sort | uniq -c}, yields exactly two distinct values:
 * 250 rows of {@code POS TERM} and <strong>50 rows of {@code OPERATOR}</strong>, each padded to ten
 * characters, which independently corroborates both the 23-32 offsets and the {@code X(10)} width.
 *
 * <p>Fifty occurrences make {@code OPERATOR} look like an obvious third constant. It is not, and the
 * evidence is conclusive: {@code OPERATOR} has <strong>no literal assignment site anywhere</strong> - it
 * does not occur in {@code app/cbl}, {@code app/cpy} or {@code app/cpy-bms} at all. It reaches the
 * transaction record purely as <em>data</em>, through the {@code app/cbl/CBTRN02C.cbl:L428} pass through.
 * Promoting it to a constant would assert that this enum is the complete domain of the column, which it
 * demonstrably is not, and would invite the enum typed persistence mapping ruled out above - silently
 * rejecting or mangling one fifth of the reference fixture and failing the fixture parity gates.
 *
 * <p>The lookups below encode that conclusion directly: {@link #fromCode(String)} and
 * {@link #fromCodeIgnoreCase(String)} return an empty {@link Optional} for {@code OPERATOR} and for every
 * other unrecognised value. They never throw and never fall back to an invented constant, because
 * unrecognised text is legitimate, expected production data rather than an error.
 *
 * @see #getCode()
 * @see #getFixedWidthValue()
 * @see #fromCode(String)
 * @see #fromCodeIgnoreCase(String)
 */
public enum TransactionSource {

    /**
     * The source marker written on system generated interest transactions by the interest calculation
     * batch program.
     *
     * <p>COBOL literal, verbatim: {@code 'System'}. It is <strong>six characters in mixed case</strong> -
     * a capital {@code S} followed by lower case {@code ystem} - and is neither upper cased nor lower
     * cased anywhere in the corpus. Padded into {@code X(10)} it is {@code "System"} followed by four
     * spaces, so {@link #getFixedWidthValue()} returns a ten character value whose last four characters
     * are blanks.
     *
     * <p>Assigned at {@code app/cbl/CBACT04C.cbl:L484}, inside paragraph {@code 1300-B-WRITE-TX} which
     * begins at {@code app/cbl/CBACT04C.cbl:L473}. The surrounding statements set {@code TRAN-TYPE-CD} to
     * {@code '01'} (L482) and {@code TRAN-CAT-CD} to {@code '05'} (L483), build {@code TRAN-DESC} by
     * concatenating {@code 'Int. for a/c '} with the account identifier (L485-L489), and move one
     * generated timestamp into both {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} (L497-L498).
     */
    SYSTEM("System"),

    /**
     * The source marker written on online bill payment transactions.
     *
     * <p>COBOL literal, verbatim: {@code 'POS TERM'}. It is <strong>eight upper case characters with a
     * single internal space</strong> between {@code POS} and {@code TERM}; that space is part of the
     * value and must never be stripped, collapsed or replaced. Padded into {@code X(10)} it is
     * {@code "POS TERM"} followed by two spaces.
     *
     * <p>Assigned at {@code app/cbl/COBIL00C.cbl:L222}. The surrounding statements set
     * {@code TRAN-TYPE-CD} to {@code '02'} (L220) and {@code TRAN-CAT-CD} to {@code 2} (L221), set
     * {@code TRAN-DESC} to {@code 'BILL PAYMENT - ONLINE'} (L223), move the <strong>entire</strong>
     * current account balance into {@code TRAN-AMT} (L224, the payment is always the full balance),
     * and set {@code TRAN-MERCHANT-ID} to {@code 999999999} (L226) with {@code TRAN-MERCHANT-NAME}
     * {@code 'BILL PAYMENT'} (L227).
     *
     * <p>This is also the value carried by 250 of the 300 rows of {@code app/data/ASCII/dailytran.txt},
     * where it arrives as staged input data rather than from this literal assignment.
     */
    POS_TERMINAL("POS TERM");

    /**
     * The declared external width of {@code TRAN-SOURCE} and of {@code DALYTRAN-SOURCE} in characters,
     * taken from {@code PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8} and {@code app/cpy/CVTRA06Y.cpy:L8}
     * and independently corroborated by the fixture census, in which every observed value of bytes 23-32
     * is exactly this many characters wide.
     */
    public static final int FIELD_LENGTH = 10;

    /**
     * Case sensitive index from unpadded literal to constant, used by {@link #fromCode(String)}.
     *
     * <p>Built once during class initialisation and immutable thereafter. Only {@link Map#get(Object)} is
     * ever called on it, so no behaviour depends on its iteration order.
     */
    private static final Map<String, TransactionSource> BY_CODE;

    /**
     * Index from the upper cased literal to constant, used by {@link #fromCodeIgnoreCase(String)}. Keys
     * are folded with {@link Locale#ROOT} so the mapping cannot vary with the host default locale.
     *
     * <p>Built once during class initialisation and immutable thereafter. Only {@link Map#get(Object)} is
     * ever called on it, so no behaviour depends on its iteration order.
     */
    private static final Map<String, TransactionSource> BY_UPPER_CASE_CODE;

    static {
        final Map<String, TransactionSource> byCode = new LinkedHashMap<>();
        final Map<String, TransactionSource> byUpperCaseCode = new LinkedHashMap<>();
        for (final TransactionSource source : values()) {
            byCode.put(source.code, source);
            byUpperCaseCode.put(source.code.toUpperCase(Locale.ROOT), source);
        }
        BY_CODE = Map.copyOf(byCode);
        BY_UPPER_CASE_CODE = Map.copyOf(byUpperCaseCode);
    }

    /** The COBOL literal exactly as spelled in the source, without the {@code X(10)} blank padding. */
    private final String code;

    /** The {@link #code} right padded with spaces to exactly {@value #FIELD_LENGTH} characters. */
    private final String fixedWidthValue;

    /**
     * Binds a constant to its COBOL literal and derives the fixed width form from it.
     *
     * <p>The padded form is <em>derived</em> rather than declared a second time, so the literal has a
     * single point of truth and the two forms cannot drift apart. Padding uses
     * {@link String#repeat(int)} rather than a format string, which keeps the result independent of the
     * default locale by construction.
     *
     * @param code the COBOL literal, verbatim and unpadded; never {@code null} and never longer than
     *             {@value #FIELD_LENGTH} characters, both of which hold for the two constants declared
     *             above and are enforced by class initialisation failing loudly if a future edit
     *             violates them
     */
    TransactionSource(final String code) {
        this.code = code;
        this.fixedWidthValue = code + " ".repeat(FIELD_LENGTH - code.length());
    }

    /**
     * Returns the COBOL literal exactly as the source spells it, with no blank padding applied.
     *
     * <p>The value is byte for byte what the {@code MOVE} statement carries: {@code System} in mixed case
     * for {@link #SYSTEM} and {@code POS TERM} in upper case with one internal space for
     * {@link #POS_TERMINAL}. Use this form for comparison, logging and JSON. For writing into a fixed
     * width record use {@link #getFixedWidthValue()} instead, because this form is shorter than the
     * declared field and would shift every following field of the record.
     *
     * <p>Pure function with no side effects. Never returns {@code null} or an empty string.
     *
     * <p><strong>Error modes: none.</strong> The accessor takes no argument, reads one immutable field
     * assigned at class initialisation, and cannot fail or throw.
     *
     * @return the unpadded COBOL literal; six characters for {@link #SYSTEM}, eight for
     *         {@link #POS_TERMINAL}
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the literal right padded with spaces to exactly {@value #FIELD_LENGTH} characters, which is
     * the external form of the {@code PIC X(10)} field.
     *
     * <p>The padding rule is the COBOL alphanumeric rule: the value is left justified and the remainder
     * of the field is blank filled, so {@link #SYSTEM} yields {@code System} plus four spaces and
     * {@link #POS_TERMINAL} yields {@code POS TERM} plus two spaces. Use this form, and only this form,
     * when emitting bytes 23-32 of the 350 byte transaction image or of the 430 byte reject image; using
     * {@link #getCode()} there would silently corrupt the record.
     *
     * <p>Pure function with no side effects. The returned string is always exactly
     * {@value #FIELD_LENGTH} characters long.
     *
     * <p><strong>Error modes: none.</strong> The accessor takes no argument, reads one immutable field
     * derived once at class initialisation, and cannot fail or throw.
     *
     * @return the ten character, right padded external form of this source marker
     */
    public String getFixedWidthValue() {
        return fixedWidthValue;
    }

    /**
     * Resolves external text to the constant carrying that literal, matching case sensitively.
     *
     * <p>Leading and trailing whitespace is removed before matching, so both the unpadded literal
     * {@code System} and the fixed width form {@code System} followed by four spaces resolve to
     * {@link #SYSTEM}. Both forms legitimately occur - the first in memory, the second as a slice of a
     * fixed width record - so no length precondition is imposed on the argument. Internal whitespace is
     * preserved, which is what allows {@code POS TERM} to resolve at all.
     *
     * <p>Matching is case sensitive by design, because the literals are reproduced byte for byte from the
     * source and {@code 'System'} is genuinely mixed case. Use
     * {@link #fromCodeIgnoreCase(String)} where tolerant recognition is wanted.
     *
     * <p><strong>Error modes: none.</strong> This method never throws and never invents a fallback
     * constant. An empty result is returned for a {@code null} argument, for an empty or all whitespace
     * argument, and for any value that is not one of the two literals - including {@code OPERATOR}, which
     * occurs 50 times in {@code app/data/ASCII/dailytran.txt} yet has no assignment site in the corpus.
     * An empty result therefore means "not one of the two known literals", which is an ordinary and
     * expected outcome rather than an error.
     *
     * <p>Pure function with no side effects.
     *
     * @param rawValue the external text to resolve, padded or unpadded; may be {@code null}, empty or
     *                 blank, each of which yields an empty result
     * @return the matching constant, or an empty {@link Optional} if the text is absent, blank or not one
     *         of the two known literals
     */
    public static Optional<TransactionSource> fromCode(final String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        final String stripped = rawValue.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(stripped));
    }

    /**
     * Resolves external text to the constant carrying that literal, ignoring case differences.
     *
     * <p>Behaves exactly as {@link #fromCode(String)} - whitespace stripped at both ends, internal
     * whitespace preserved, no length precondition - except that the comparison folds case. Folding uses
     * {@link Locale#ROOT} on both the stored keys and the argument, never the host default locale: under
     * a Turkish default locale {@code "System".toUpperCase()} produces a dotless capital I and the lookup
     * would silently stop matching, which is precisely the environment specific behaviour this method
     * must not have.
     *
     * <p>This tolerant form is intended for recognising text arriving from outside the batch pipeline,
     * where casing cannot be relied upon. It is <strong>not</strong> for parity critical paths: anything
     * that reproduces legacy output must keep the literals byte exact and should use
     * {@link #fromCode(String)}.
     *
     * <p><strong>Error modes: none.</strong> As with {@link #fromCode(String)}, this method never throws
     * and never invents a fallback constant; unrecognised text simply yields an empty result.
     *
     * <p>Pure function with no side effects.
     *
     * @param rawValue the external text to resolve, in any casing, padded or unpadded; may be
     *                 {@code null}, empty or blank, each of which yields an empty result
     * @return the matching constant, or an empty {@link Optional} if the text is absent, blank or not one
     *         of the two known literals
     */
    public static Optional<TransactionSource> fromCodeIgnoreCase(final String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        final String stripped = rawValue.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_UPPER_CASE_CODE.get(stripped.toUpperCase(Locale.ROOT)));
    }
}
