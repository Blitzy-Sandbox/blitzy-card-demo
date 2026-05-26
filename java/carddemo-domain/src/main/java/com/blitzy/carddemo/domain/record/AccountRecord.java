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
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.util.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable Java record translated from the COBOL {@code ACCOUNT-RECORD}
 * copybook at {@code app/cpy/CVACT01Y.cpy} (record length 300 bytes).
 *
 * <p>Authority for this translation:
 * <ul>
 *   <li>AAP &sect;0.4.1 (Copybooks &rarr; Java records table) &mdash;
 *       AccountRecord &larr; CVACT01Y.cpy, 300 bytes, 13 components</li>
 *   <li>AAP &sect;0.6.9 (Copybook-to-Record Translation Table)</li>
 *   <li>AAP &sect;0.3.2 (Records pattern: every copybook 01-level group
 *       becomes a Java {@code record})</li>
 *   <li>AAP &sect;0.6.1 (Decimal Arithmetic Fidelity &mdash; 5 BigDecimal
 *       monetary fields at scale 2)</li>
 *   <li>AAP &sect;0.6.4 (Date Semantics &mdash; 3 {@link LocalDate} fields)</li>
 *   <li>AAP &sect;0.6.5 (File I/O Exactness &mdash; byte-for-byte round-trip
 *       invariant: {@code parse(b).encode()} equals {@code b} for every
 *       well-formed 300-byte buffer)</li>
 *   <li>AAP &sect;0.1.3 (Decimal scale preservation: {@code "1.20"} must
 *       NOT be normalized to {@code "1.2"})</li>
 * </ul>
 *
 * <h2>COBOL layout (verbatim, 300 bytes total)</h2>
 * <pre>{@code
 * 01 ACCOUNT-RECORD.
 *    05 ACCT-ID                  PIC 9(11).      [ 0..10 ]   (11 bytes)
 *    05 ACCT-ACTIVE-STATUS       PIC X(01).      [11..11 ]   ( 1 byte )
 *    05 ACCT-CURR-BAL            PIC S9(10)V99.  [12..23 ]   (12 bytes)
 *    05 ACCT-CREDIT-LIMIT        PIC S9(10)V99.  [24..35 ]   (12 bytes)
 *    05 ACCT-CASH-CREDIT-LIMIT   PIC S9(10)V99.  [36..47 ]   (12 bytes)
 *    05 ACCT-OPEN-DATE           PIC X(10).      [48..57 ]   (10 bytes)
 *    05 ACCT-EXPIRAION-DATE      PIC X(10).      [58..67 ]   (10 bytes)
 *    05 ACCT-REISSUE-DATE        PIC X(10).      [68..77 ]   (10 bytes)
 *    05 ACCT-CURR-CYC-CREDIT     PIC S9(10)V99.  [78..89 ]   (12 bytes)
 *    05 ACCT-CURR-CYC-DEBIT      PIC S9(10)V99.  [90..101]   (12 bytes)
 *    05 ACCT-ADDR-ZIP            PIC X(10).      [102..111]  (10 bytes)
 *    05 ACCT-GROUP-ID            PIC X(10).      [112..121]  (10 bytes)
 *    05 FILLER                   PIC X(178).     [122..299]  (178 bytes)
 * Total: 11+1+12+12+12+10+10+10+12+12+10+10+178 = 300 bytes
 * }</pre>
 *
 * <h2>Fixture encoding</h2>
 * <p>The companion fixture {@code app/data/ASCII/acctdata.txt} encodes each
 * record as 300 ASCII bytes (plus a trailing {@code \n} record separator):
 * <ul>
 *   <li>Numeric {@code PIC 9(11)} fields: zero-padded ASCII digits
 *       ({@code "00000000001"})</li>
 *   <li>Signed numeric {@code PIC S9(10)V99} fields ({@code USAGE DISPLAY}):
 *       12 ASCII bytes where the last byte carries the trailing digit AND
 *       the sign via overpunch:
 *       <code>'&#123;'</code>=+0, {@code A}..{@code I}=+1..+9,
 *       <code>'&#125;'</code>=-0, {@code J}..{@code R}=-1..-9.
 *       Example: <code>"00000001940{"</code> decodes to {@code +194.00}.
 *       This is decoded via {@link Decimals#parseZonedDecimal} and re-encoded
 *       via {@link Decimals#encodeZonedDecimal}.</li>
 *   <li>Date {@code PIC X(10)} fields: ISO-8601 {@code YYYY-MM-DD}
 *       ({@code "2014-11-20"})</li>
 *   <li>String {@code PIC X(n)} fields: space-padded right
 *       ({@code "A000000000"})</li>
 *   <li>FILLER: 178 spaces by convention (preserved verbatim as
 *       {@link #filler() byte[178]})</li>
 * </ul>
 *
 * <h2>Method-choice note: zoned-decimal vs. COMP-3</h2>
 * <p>The COBOL declaration {@code PIC S9(10)V99} does not specify a
 * {@code USAGE} clause, so COBOL defaults to {@code USAGE DISPLAY} (zoned
 * decimal). This is the format used in {@code app/data/ASCII/*.txt} fixtures.
 * Per AAP &sect;0.6.5 the byte-for-byte round-trip invariant is asserted
 * against these fixtures, so this record uses
 * {@link Decimals#parseZonedDecimal} / {@link Decimals#encodeZonedDecimal}
 * (USAGE DISPLAY ASCII codec) rather than
 * {@link Decimals#parseSignedPacked} / {@link Decimals#encodeSignedPacked}
 * (COMP-3 binary BCD codec). The COMP-3 codec is the wrong codec for this
 * data and would throw an {@link IllegalArgumentException} when it reached
 * the overpunch byte (e.g., {@code 0x7B} which has a low nibble of
 * {@code 0xB}, not a valid COMP-3 sign nibble of {@code 0xC}/{@code 0xD}/
 * {@code 0xF}).
 *
 * <h2>{@code ACCT-EXPIRAION-DATE} typo preservation</h2>
 * <p>The COBOL copybook contains the typographical error
 * {@code EXPIRAION} (missing 'T' &mdash; should be {@code EXPIRATION}). Per
 * AAP &sect;0.7.1 ("If a COBOL paragraph contains dead code or obvious bugs,
 * translate it faithfully and flag it in a MIGRATION_NOTES.md; do not 'fix'
 * it in this refactor."), the Java component is therefore named
 * {@link #acctExpiraionDate()} verbatim.
 *
 * <h2>Byte fidelity invariants</h2>
 * <p>Per AAP &sect;0.6.5 the {@link #parse(byte[])} factory and
 * {@link #encode()} method are mutual inverses for every well-formed
 * 300-byte input buffer produced by COBOL {@code DISPLAY} of an account
 * record:
 * <pre>{@code
 *   byte[] in = ...; // 300 bytes from acctdata.txt
 *   AccountRecord rec = AccountRecord.parse(in);
 *   byte[] out = rec.encode();
 *   assert Arrays.equals(in, out);
 * }</pre>
 *
 * <h2>Scale preservation</h2>
 * <p>Per AAP &sect;0.1.3 the {@link BigDecimal} components are normalized
 * to scale {@value #MONETARY_SCALE} in the compact canonical constructor
 * using {@link RoundingMode#HALF_EVEN} (banker's rounding). This preserves
 * trailing zeros (e.g., {@code BigDecimal.valueOf(1.2)} stored as
 * {@code "1.20"}), which is critical for byte-for-byte output fidelity.
 *
 * <h2>Equality semantics</h2>
 * <p>This record overrides {@link #equals(Object)} and {@link #hashCode()}
 * (overriding the record-default behavior) because:
 * <ul>
 *   <li>{@link BigDecimal#equals(Object)} considers {@code "1.20"} and
 *       {@code "1.2"} as unequal (different scale), whereas business
 *       equality requires {@code 1.20 == 1.2}. This is solved by
 *       {@link BigDecimal#compareTo(BigDecimal)} {@code == 0}.</li>
 *   <li>The {@code byte[]} {@link #filler()} component requires
 *       {@link Arrays#equals(byte[], byte[])} for value equality, not
 *       reference equality.</li>
 * </ul>
 * The {@link #hashCode()} implementation is consistent with {@code equals}
 * by stripping trailing zeros before hashing each {@link BigDecimal}, and
 * by using {@link Arrays#hashCode(byte[])} for {@code filler}.
 *
 * <h2>Usage</h2>
 * Loaded from {@code app/data/ASCII/acctdata.txt} (REFERENCE fixture per
 * AAP &sect;0.4.1) &mdash; 50 records, 300 bytes each. Used by:
 * <ul>
 *   <li>{@code CbAct01C} (sequential ACCTFILE reader)</li>
 *   <li>{@code CbAct04C} (interest calculation)</li>
 *   <li>{@code CbTrn02C} (transaction posting, paragraph
 *       2800-UPDATE-ACCOUNT-REC)</li>
 *   <li>{@code CoActVwC} (account view online)</li>
 *   <li>{@code CoActUpC} (account update online)</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Instances of this record are immutable (the {@code byte[]}
 * {@link #filler()} component is defensively cloned both on construction
 * and on accessor read). Records are safe to share across platform and
 * virtual threads per AAP &sect;0.6.6.
 *
 * @see Decimals
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVACT01Y",
        sourcePath = "app/cpy/CVACT01Y.cpy",
        notes = "300-byte ACCOUNT-RECORD with 5 BigDecimal monetary fields; "
                + "ACCT-EXPIRAION-DATE typo preserved verbatim per AAP §0.7.1; "
                + "zoned-decimal USAGE DISPLAY codec (Decimals.parseZonedDecimal) "
                + "is used for monetary fields to match the app/data/ASCII/acctdata.txt fixture format"
)
public record AccountRecord(
        long acctId,
        char acctActiveStatus,
        BigDecimal acctCurrBal,
        BigDecimal acctCreditLimit,
        BigDecimal acctCashCreditLimit,
        LocalDate acctOpenDate,
        LocalDate acctExpiraionDate,
        LocalDate acctReissueDate,
        BigDecimal acctCurrCycCredit,
        BigDecimal acctCurrCycDebit,
        String acctAddrZip,
        String acctGroupId,
        byte[] filler) {

    // -----------------------------------------------------------------
    // Layout constants (offsets and lengths derived directly from the
    // COBOL copybook 01-level group structure; sum of all field lengths
    // equals RECORD_LENGTH = 300).
    // -----------------------------------------------------------------

    /** Total record length in bytes per the COBOL copybook (300). */
    public static final int RECORD_LENGTH = 300;

    /**
     * Monetary scale for all {@code PIC S9(10)V99} fields in this record
     * (2 decimal places). Per AAP &sect;0.1.3, this scale is preserved in
     * all 5 BigDecimal components: {@code "1.20"} stays {@code "1.20"},
     * never {@code "1.2"}.
     */
    public static final int MONETARY_SCALE = 2;

    /** Offset of {@code ACCT-ID PIC 9(11)} within the 300-byte buffer (0). */
    public static final int ACCT_ID_OFFSET = 0;
    /** Length of {@code ACCT-ID PIC 9(11)} in bytes (11). */
    public static final int ACCT_ID_LENGTH = 11;

    /** Offset of {@code ACCT-ACTIVE-STATUS PIC X(01)} (11). */
    public static final int ACCT_ACTIVE_STATUS_OFFSET = 11;
    /** Length of {@code ACCT-ACTIVE-STATUS PIC X(01)} (1). */
    public static final int ACCT_ACTIVE_STATUS_LENGTH = 1;

    /** Offset of {@code ACCT-CURR-BAL PIC S9(10)V99} (12). */
    public static final int ACCT_CURR_BAL_OFFSET = 12;
    /** Length of {@code ACCT-CURR-BAL PIC S9(10)V99} in zoned-decimal bytes (12). */
    public static final int ACCT_CURR_BAL_LENGTH = 12;

    /** Offset of {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} (24). */
    public static final int ACCT_CREDIT_LIMIT_OFFSET = 24;
    /** Length of {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} (12). */
    public static final int ACCT_CREDIT_LIMIT_LENGTH = 12;

    /** Offset of {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} (36). */
    public static final int ACCT_CASH_CREDIT_LIMIT_OFFSET = 36;
    /** Length of {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} (12). */
    public static final int ACCT_CASH_CREDIT_LIMIT_LENGTH = 12;

    /** Offset of {@code ACCT-OPEN-DATE PIC X(10)} (48). */
    public static final int ACCT_OPEN_DATE_OFFSET = 48;
    /** Length of {@code ACCT-OPEN-DATE PIC X(10)} (10). */
    public static final int ACCT_OPEN_DATE_LENGTH = 10;

    /** Offset of {@code ACCT-EXPIRAION-DATE PIC X(10)} (58). */
    public static final int ACCT_EXPIRAION_DATE_OFFSET = 58;
    /** Length of {@code ACCT-EXPIRAION-DATE PIC X(10)} (10). */
    public static final int ACCT_EXPIRAION_DATE_LENGTH = 10;

    /** Offset of {@code ACCT-REISSUE-DATE PIC X(10)} (68). */
    public static final int ACCT_REISSUE_DATE_OFFSET = 68;
    /** Length of {@code ACCT-REISSUE-DATE PIC X(10)} (10). */
    public static final int ACCT_REISSUE_DATE_LENGTH = 10;

    /** Offset of {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} (78). */
    public static final int ACCT_CURR_CYC_CREDIT_OFFSET = 78;
    /** Length of {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} (12). */
    public static final int ACCT_CURR_CYC_CREDIT_LENGTH = 12;

    /** Offset of {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} (90). */
    public static final int ACCT_CURR_CYC_DEBIT_OFFSET = 90;
    /** Length of {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} (12). */
    public static final int ACCT_CURR_CYC_DEBIT_LENGTH = 12;

    /** Offset of {@code ACCT-ADDR-ZIP PIC X(10)} (102). */
    public static final int ACCT_ADDR_ZIP_OFFSET = 102;
    /** Length of {@code ACCT-ADDR-ZIP PIC X(10)} (10). */
    public static final int ACCT_ADDR_ZIP_LENGTH = 10;

    /** Offset of {@code ACCT-GROUP-ID PIC X(10)} (112). */
    public static final int ACCT_GROUP_ID_OFFSET = 112;
    /** Length of {@code ACCT-GROUP-ID PIC X(10)} (10). */
    public static final int ACCT_GROUP_ID_LENGTH = 10;

    /** Offset of trailing {@code FILLER PIC X(178)} (122). */
    public static final int FILLER_OFFSET = 122;
    /** Length of trailing {@code FILLER PIC X(178)} in bytes (178). */
    public static final int FILLER_LENGTH = 178;

    /**
     * ISO-8601 date formatter for {@code YYYY-MM-DD} dates. Bound to
     * {@link DateTimeFormatter#ISO_LOCAL_DATE} so the parser is strict
     * about format (rejects values like {@code "2014-1-20"} or
     * {@code "20141120"}). Private because external callers should use
     * the {@link #parse(byte[])} factory rather than format dates
     * themselves.
     */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // -----------------------------------------------------------------
    // Compact canonical constructor (JEP 513 Flexible Constructor Bodies):
    // validation BEFORE field assignment + scale normalization +
    // defensive copy of mutable byte[] component.
    // -----------------------------------------------------------------

    /**
     * Compact canonical constructor.
     *
     * <p>Uses JEP 513 (Flexible Constructor Bodies, finalized in Java 25)
     * to validate arguments BEFORE the implicit canonical field
     * assignment. Per AAP &sect;0.6.3 this is the right place for
     * COBOL-style input validation because validation can short-circuit
     * binding when an invariant is violated.
     *
     * <p>Invariants enforced:
     * <ul>
     *   <li>{@code acctId &ge; 0} (PIC 9(11) is unsigned in COBOL)</li>
     *   <li>None of the {@link BigDecimal}, {@link LocalDate}, or
     *       {@link String} components may be {@code null}</li>
     *   <li>{@link #acctAddrZip()} must fit in
     *       {@value #ACCT_ADDR_ZIP_LENGTH} characters</li>
     *   <li>{@link #acctGroupId()} must fit in
     *       {@value #ACCT_GROUP_ID_LENGTH} characters</li>
     *   <li>{@link #filler()} must be exactly {@value #FILLER_LENGTH}
     *       bytes (the COBOL FILLER size)</li>
     * </ul>
     *
     * <p>Normalizations applied:
     * <ul>
     *   <li>All 5 monetary BigDecimal components are rescaled to
     *       {@value #MONETARY_SCALE} via
     *       {@link BigDecimal#setScale(int, RoundingMode)} with
     *       {@link RoundingMode#HALF_EVEN} (banker's rounding). Per AAP
     *       &sect;0.1.3 this preserves trailing zeros: {@code 1.2} stored
     *       as {@code 1.20}, never the other way around.</li>
     *   <li>{@link #filler()} is defensively cloned to insulate this
     *       record from later mutation of the caller's array.</li>
     * </ul>
     *
     * @throws NullPointerException if any non-primitive component is
     *         {@code null}
     * @throws IllegalArgumentException if {@code acctId} is negative,
     *         a string component exceeds its declared length, or
     *         {@code filler} is not exactly {@value #FILLER_LENGTH} bytes
     */
    public AccountRecord {
        // Non-null validation for all reference components (object types
        // can be null even when the record component is declared as a
        // value-bearing type).
        Objects.requireNonNull(acctCurrBal, "acctCurrBal");
        Objects.requireNonNull(acctCreditLimit, "acctCreditLimit");
        Objects.requireNonNull(acctCashCreditLimit, "acctCashCreditLimit");
        Objects.requireNonNull(acctOpenDate, "acctOpenDate");
        Objects.requireNonNull(acctExpiraionDate, "acctExpiraionDate");
        Objects.requireNonNull(acctReissueDate, "acctReissueDate");
        Objects.requireNonNull(acctCurrCycCredit, "acctCurrCycCredit");
        Objects.requireNonNull(acctCurrCycDebit, "acctCurrCycDebit");
        Objects.requireNonNull(acctAddrZip, "acctAddrZip");
        Objects.requireNonNull(acctGroupId, "acctGroupId");
        Objects.requireNonNull(filler, "filler");

        // Numeric range validation: PIC 9(11) is unsigned.
        if (acctId < 0L) {
            throw new IllegalArgumentException(
                    "acctId must be non-negative; got " + acctId);
        }

        // String length validation: PIC X(10) fields cannot hold more
        // than 10 ASCII characters without losing data on encode.
        if (acctAddrZip.length() > ACCT_ADDR_ZIP_LENGTH) {
            throw new IllegalArgumentException(
                    "acctAddrZip exceeds " + ACCT_ADDR_ZIP_LENGTH
                            + " chars; got length=" + acctAddrZip.length()
                            + " value='" + acctAddrZip + "'");
        }
        if (acctGroupId.length() > ACCT_GROUP_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "acctGroupId exceeds " + ACCT_GROUP_ID_LENGTH
                            + " chars; got length=" + acctGroupId.length()
                            + " value='" + acctGroupId + "'");
        }

        // FILLER byte-array length validation: must match copybook width.
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH
                            + " bytes; got " + filler.length);
        }

        // Normalize BigDecimal scale to MONETARY_SCALE using HALF_EVEN
        // (banker's rounding). Per AAP §0.1.3 this preserves trailing
        // zeros for byte-for-byte fidelity with COBOL DISPLAY format.
        acctCurrBal = acctCurrBal.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
        acctCreditLimit = acctCreditLimit.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
        acctCashCreditLimit = acctCashCreditLimit.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
        acctCurrCycCredit = acctCurrCycCredit.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
        acctCurrCycDebit = acctCurrCycDebit.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);

        // Defensive clone of the mutable byte[] component. Records are
        // shallow-immutable by default; without this clone, callers
        // could mutate `filler` after construction and observe the
        // mutation through this record (violating immutability).
        filler = filler.clone();
    }

    // -----------------------------------------------------------------
    // Defensive accessor for the mutable byte[] component.
    // -----------------------------------------------------------------

    /**
     * Returns a defensive copy of the 178-byte FILLER region. The clone
     * prevents external mutation of this record's internal state through
     * the returned reference, preserving record immutability per AAP
     * &sect;0.3.2 (Records pattern).
     *
     * <p>The default record accessor would return the underlying array
     * reference directly, which would allow {@code rec.filler()[0] = 0}
     * to mutate the record. This override closes that hole.
     *
     * @return a new {@value #FILLER_LENGTH}-byte array equal in content
     *         to the stored FILLER region
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // -----------------------------------------------------------------
    // Static factory: parse(byte[])
    // -----------------------------------------------------------------

    /**
     * Decodes a {@value #RECORD_LENGTH}-byte buffer into an
     * {@code AccountRecord} per the COBOL {@code ACCOUNT-RECORD} copybook
     * layout at {@code app/cpy/CVACT01Y.cpy}.
     *
     * <p>Field codecs:
     * <ul>
     *   <li>{@code ACCT-ID PIC 9(11)}: zero-padded ASCII digits decoded
     *       via {@link Long#parseLong(String)}</li>
     *   <li>{@code ACCT-ACTIVE-STATUS PIC X(01)}: single byte converted
     *       to a {@code char} (low 8 bits)</li>
     *   <li>5 monetary {@code PIC S9(10)V99} fields: 12-byte zoned
     *       decimal ({@code USAGE DISPLAY} with sign overpunch on the
     *       rightmost byte) decoded via
     *       {@link Decimals#parseZonedDecimal} with scale
     *       {@value #MONETARY_SCALE}</li>
     *   <li>3 date {@code PIC X(10)} fields: ISO-8601 {@code YYYY-MM-DD}
     *       decoded via {@link LocalDate#parse(CharSequence,
     *       DateTimeFormatter)} with {@link #DATE_FMT}</li>
     *   <li>2 string {@code PIC X(10)} fields: ASCII text preserved
     *       verbatim including trailing space padding</li>
     *   <li>{@code FILLER PIC X(178)}: 178 bytes copied verbatim via
     *       {@link Arrays#copyOfRange(byte[], int, int)}</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.6.5 the byte-for-byte round-trip invariant
     * holds: for every 300-byte buffer {@code b} produced by COBOL
     * {@code DISPLAY} of an account record,
     * {@code AccountRecord.parse(b).encode()} equals {@code b}.
     *
     * @param buffer the 300-byte input buffer
     * @return a fully-populated {@code AccountRecord}
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} is not
     *         {@value #RECORD_LENGTH}, or if any field's bytes are
     *         malformed (non-digit in numeric, invalid sign overpunch,
     *         malformed date, etc.)
     */
    public static AccountRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "AccountRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        // ACCT-ID PIC 9(11) — zero-padded ASCII numeric.
        long acctId = Long.parseLong(new String(
                buffer, ACCT_ID_OFFSET, ACCT_ID_LENGTH, StandardCharsets.US_ASCII));

        // ACCT-ACTIVE-STATUS PIC X(01) — single ASCII character.
        char acctActiveStatus = (char) (buffer[ACCT_ACTIVE_STATUS_OFFSET] & 0xFF);

        // 5 monetary fields PIC S9(10)V99 — zoned-decimal USAGE DISPLAY
        // with sign overpunch on the rightmost byte. Per AAP §0.6.1 use
        // the centralized Decimals facade for all monetary parsing.
        BigDecimal acctCurrBal = Decimals.parseZonedDecimal(
                buffer, ACCT_CURR_BAL_OFFSET, ACCT_CURR_BAL_LENGTH, MONETARY_SCALE);
        BigDecimal acctCreditLimit = Decimals.parseZonedDecimal(
                buffer, ACCT_CREDIT_LIMIT_OFFSET, ACCT_CREDIT_LIMIT_LENGTH, MONETARY_SCALE);
        BigDecimal acctCashCreditLimit = Decimals.parseZonedDecimal(
                buffer, ACCT_CASH_CREDIT_LIMIT_OFFSET, ACCT_CASH_CREDIT_LIMIT_LENGTH,
                MONETARY_SCALE);

        // 3 date fields PIC X(10) — ISO-8601 YYYY-MM-DD via java.time.
        LocalDate acctOpenDate = parseDate(buffer, ACCT_OPEN_DATE_OFFSET);
        LocalDate acctExpiraionDate = parseDate(buffer, ACCT_EXPIRAION_DATE_OFFSET);
        LocalDate acctReissueDate = parseDate(buffer, ACCT_REISSUE_DATE_OFFSET);

        // Remaining 2 monetary fields.
        BigDecimal acctCurrCycCredit = Decimals.parseZonedDecimal(
                buffer, ACCT_CURR_CYC_CREDIT_OFFSET, ACCT_CURR_CYC_CREDIT_LENGTH,
                MONETARY_SCALE);
        BigDecimal acctCurrCycDebit = Decimals.parseZonedDecimal(
                buffer, ACCT_CURR_CYC_DEBIT_OFFSET, ACCT_CURR_CYC_DEBIT_LENGTH,
                MONETARY_SCALE);

        // 2 string fields PIC X(10) — preserved verbatim including
        // trailing spaces (significant for byte-for-byte round-trip).
        String acctAddrZip = new String(
                buffer, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH,
                StandardCharsets.US_ASCII);
        String acctGroupId = new String(
                buffer, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH,
                StandardCharsets.US_ASCII);

        // FILLER PIC X(178) — preserve verbatim. The canonical
        // constructor will clone this defensively, so the slice
        // returned here can be passed directly.
        byte[] filler = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new AccountRecord(
                acctId, acctActiveStatus,
                acctCurrBal, acctCreditLimit, acctCashCreditLimit,
                acctOpenDate, acctExpiraionDate, acctReissueDate,
                acctCurrCycCredit, acctCurrCycDebit,
                acctAddrZip, acctGroupId,
                filler);
    }

    // -----------------------------------------------------------------
    // Instance method: encode()
    // -----------------------------------------------------------------

    /**
     * Encodes this record as a {@value #RECORD_LENGTH}-byte ASCII buffer
     * suitable for sequential I/O via {@code java.nio.file}.
     *
     * <p>The buffer is initialized with ASCII spaces ({@code 0x20}); each
     * field then overwrites its slice with its encoded byte sequence.
     * The padding strategy per field type:
     * <ul>
     *   <li>Numeric ({@code PIC 9(n)}): zero-padded LEFT to fill the
     *       declared width</li>
     *   <li>String ({@code PIC X(n)}): space-padded RIGHT (the buffer's
     *       default space-fill remains in any unused trailing bytes)</li>
     *   <li>Signed monetary ({@code PIC S9(10)V99}): delegated to
     *       {@link Decimals#encodeZonedDecimal}, which produces 12 ASCII
     *       bytes with sign overpunch on the rightmost byte</li>
     *   <li>Date ({@code PIC X(10)}): formatted as ISO-8601
     *       {@code YYYY-MM-DD} via {@link #DATE_FMT}, copied verbatim</li>
     *   <li>FILLER ({@code PIC X(178)}): copied verbatim from the
     *       internal byte[] component &mdash; preserves any structure
     *       inherited from upstream COBOL writes</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.6.5 byte-for-byte round-trip invariant:
     * {@code AccountRecord.parse(b).encode()} equals {@code b} for every
     * well-formed COBOL DISPLAY input buffer.
     *
     * @return a new {@value #RECORD_LENGTH}-byte buffer containing the
     *         COBOL DISPLAY encoding of this record
     */
    public byte[] encode() {
        // Initialize buffer with ASCII spaces (0x20). This matches the
        // COBOL DISPLAY default padding for any field whose encoder
        // writes fewer bytes than its declared width (e.g., a short
        // ACCT-GROUP-ID).
        byte[] out = new byte[RECORD_LENGTH];
        Arrays.fill(out, (byte) 0x20);

        // ACCT-ID PIC 9(11): zero-pad LEFT.
        // String.format("%011d", id) produces exactly 11 ASCII digit
        // bytes for any non-negative long up to 99,999,999,999 (the
        // PIC 9(11) maximum). The canonical constructor rejects
        // negative acctId so the formatted result is always 11 chars.
        String idStr = String.format("%0" + ACCT_ID_LENGTH + "d", acctId);
        byte[] idBytes = idStr.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, out, ACCT_ID_OFFSET, ACCT_ID_LENGTH);

        // ACCT-ACTIVE-STATUS PIC X(01): low 8 bits of the char.
        out[ACCT_ACTIVE_STATUS_OFFSET] = (byte) acctActiveStatus;

        // 5 monetary fields PIC S9(10)V99: zoned-decimal USAGE DISPLAY
        // with sign overpunch via the centralized Decimals facade.
        System.arraycopy(
                Decimals.encodeZonedDecimal(acctCurrBal, ACCT_CURR_BAL_LENGTH, MONETARY_SCALE),
                0, out, ACCT_CURR_BAL_OFFSET, ACCT_CURR_BAL_LENGTH);
        System.arraycopy(
                Decimals.encodeZonedDecimal(acctCreditLimit, ACCT_CREDIT_LIMIT_LENGTH, MONETARY_SCALE),
                0, out, ACCT_CREDIT_LIMIT_OFFSET, ACCT_CREDIT_LIMIT_LENGTH);
        System.arraycopy(
                Decimals.encodeZonedDecimal(acctCashCreditLimit, ACCT_CASH_CREDIT_LIMIT_LENGTH, MONETARY_SCALE),
                0, out, ACCT_CASH_CREDIT_LIMIT_OFFSET, ACCT_CASH_CREDIT_LIMIT_LENGTH);

        // 3 date fields PIC X(10): YYYY-MM-DD via java.time formatter.
        writeDate(out, ACCT_OPEN_DATE_OFFSET, acctOpenDate);
        writeDate(out, ACCT_EXPIRAION_DATE_OFFSET, acctExpiraionDate);
        writeDate(out, ACCT_REISSUE_DATE_OFFSET, acctReissueDate);

        // Remaining 2 monetary fields.
        System.arraycopy(
                Decimals.encodeZonedDecimal(acctCurrCycCredit, ACCT_CURR_CYC_CREDIT_LENGTH, MONETARY_SCALE),
                0, out, ACCT_CURR_CYC_CREDIT_OFFSET, ACCT_CURR_CYC_CREDIT_LENGTH);
        System.arraycopy(
                Decimals.encodeZonedDecimal(acctCurrCycDebit, ACCT_CURR_CYC_DEBIT_LENGTH, MONETARY_SCALE),
                0, out, ACCT_CURR_CYC_DEBIT_OFFSET, ACCT_CURR_CYC_DEBIT_LENGTH);

        // 2 string fields PIC X(10): space-pad RIGHT (the Arrays.fill at
        // the top of this method already populated the slice with 0x20;
        // writeString overwrites only the leading bytes that the string
        // actually contains).
        writeString(out, acctAddrZip, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH);
        writeString(out, acctGroupId, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH);

        // FILLER PIC X(178): verbatim copy from the internal byte[].
        // Use the internal array (not the cloned accessor result) to
        // avoid an unnecessary allocation; this method does not expose
        // the array reference to callers.
        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);

        return out;
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    /**
     * Writes a string into a slice of the output buffer, copying up to
     * {@code length} ASCII bytes. Any bytes beyond {@code value.length()}
     * within the slice are NOT touched (the caller is responsible for
     * having pre-filled the slice with the desired padding byte &mdash;
     * {@link #encode()} pre-fills the entire buffer with ASCII space
     * before calling this helper).
     *
     * @param out    the destination buffer
     * @param value  the string to encode as ASCII (must not be null;
     *               canonical constructor enforces non-null)
     * @param offset starting position within {@code out}
     * @param length declared width of the field
     */
    private static void writeString(byte[] out, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
    }

    /**
     * Parses a {@value #ACCT_OPEN_DATE_LENGTH}-byte slice as an
     * ISO-8601 {@code YYYY-MM-DD} date. The byte slice is converted to
     * an ASCII string via {@link StandardCharsets#US_ASCII} (the COBOL
     * DISPLAY default codepage for the fixture files) and parsed by
     * {@link LocalDate#parse(CharSequence, DateTimeFormatter)} with
     * {@link #DATE_FMT} (ISO_LOCAL_DATE).
     *
     * @param buffer source buffer
     * @param offset starting position (one of
     *               {@link #ACCT_OPEN_DATE_OFFSET},
     *               {@link #ACCT_EXPIRAION_DATE_OFFSET}, or
     *               {@link #ACCT_REISSUE_DATE_OFFSET})
     * @return the parsed {@link LocalDate}
     * @throws java.time.format.DateTimeParseException if the slice is
     *         not a valid {@code YYYY-MM-DD} string
     */
    private static LocalDate parseDate(byte[] buffer, int offset) {
        String s = new String(buffer, offset, ACCT_OPEN_DATE_LENGTH,
                StandardCharsets.US_ASCII);
        return LocalDate.parse(s, DATE_FMT);
    }

    /**
     * Writes a {@link LocalDate} as a 10-byte ASCII slice using
     * {@code YYYY-MM-DD} format. The canonical constructor enforces
     * non-null dates, so this helper does not handle the null case.
     *
     * @param out    destination buffer
     * @param offset starting position
     * @param date   the date to encode (must not be null)
     */
    private static void writeDate(byte[] out, int offset, LocalDate date) {
        byte[] bytes = date.format(DATE_FMT).getBytes(StandardCharsets.US_ASCII);
        // ISO_LOCAL_DATE always produces exactly 10 chars for any
        // LocalDate within the four-digit-year range (year 0000-9999);
        // the only way to fall outside that range is to use a custom
        // chronology, which java.time + LocalDate does not. So a fixed
        // length copy is safe.
        System.arraycopy(bytes, 0, out, offset, ACCT_OPEN_DATE_LENGTH);
    }

    // -----------------------------------------------------------------
    // equals / hashCode / toString — override the record default to
    // get (a) BigDecimal scale-aware equality via compareTo and (b)
    // byte[] value-equality via Arrays.equals.
    // -----------------------------------------------------------------

    /**
     * Value equality comparing all 13 components, with scale-aware
     * comparison for {@link BigDecimal} components and content
     * comparison for the {@code byte[]} {@link #filler()} component.
     *
     * <p>The compact canonical constructor normalizes all monetary
     * BigDecimal components to scale {@value #MONETARY_SCALE} on
     * construction, so for instances produced through the normal path
     * (parse, factory, or constructor) the scale-aware
     * {@link BigDecimal#compareTo} and the strict
     * {@link BigDecimal#equals} would always agree. The
     * {@code compareTo}-based comparison here is the safer choice and
     * is consistent with the corresponding scale-stripping
     * {@link #hashCode()} implementation.
     *
     * @param o the object to compare
     * @return {@code true} if {@code o} is an {@code AccountRecord}
     *         with all components equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountRecord that)) {
            return false;
        }
        return acctId == that.acctId
                && acctActiveStatus == that.acctActiveStatus
                && acctCurrBal.compareTo(that.acctCurrBal) == 0
                && acctCreditLimit.compareTo(that.acctCreditLimit) == 0
                && acctCashCreditLimit.compareTo(that.acctCashCreditLimit) == 0
                && acctOpenDate.equals(that.acctOpenDate)
                && acctExpiraionDate.equals(that.acctExpiraionDate)
                && acctReissueDate.equals(that.acctReissueDate)
                && acctCurrCycCredit.compareTo(that.acctCurrCycCredit) == 0
                && acctCurrCycDebit.compareTo(that.acctCurrCycDebit) == 0
                && acctAddrZip.equals(that.acctAddrZip)
                && acctGroupId.equals(that.acctGroupId)
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}: each
     * {@link BigDecimal} is stripped of trailing zeros before hashing so
     * that values that compare equal by {@code compareTo} hash to the
     * same value, and the {@code byte[]} {@link #filler()} component is
     * hashed via {@link Arrays#hashCode(byte[])} for content-based
     * hashing.
     *
     * @return a hash code consistent with {@code equals}
     */
    @Override
    public int hashCode() {
        int result = Long.hashCode(acctId);
        result = 31 * result + Character.hashCode(acctActiveStatus);
        result = 31 * result + acctCurrBal.stripTrailingZeros().hashCode();
        result = 31 * result + acctCreditLimit.stripTrailingZeros().hashCode();
        result = 31 * result + acctCashCreditLimit.stripTrailingZeros().hashCode();
        result = 31 * result + acctOpenDate.hashCode();
        result = 31 * result + acctExpiraionDate.hashCode();
        result = 31 * result + acctReissueDate.hashCode();
        result = 31 * result + acctCurrCycCredit.stripTrailingZeros().hashCode();
        result = 31 * result + acctCurrCycDebit.stripTrailingZeros().hashCode();
        result = 31 * result + acctAddrZip.hashCode();
        result = 31 * result + acctGroupId.hashCode();
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    /**
     * Human-readable summary. The default record {@code toString} would
     * print the {@code byte[]} {@link #filler()} component as a
     * reference identity (e.g., {@code [B@7b3300e5}); this override
     * prints a length summary instead. All other components use their
     * default string forms.
     *
     * <p>This method is used only for diagnostic logging and test
     * output; production serialization MUST go through {@link #encode()}.
     *
     * @return a string of the form {@code AccountRecord[acctId=...,
     *         ..., filler=<178 bytes>]}
     */
    @Override
    public String toString() {
        return "AccountRecord[acctId=" + acctId
                + ", acctActiveStatus=" + acctActiveStatus
                + ", acctCurrBal=" + acctCurrBal
                + ", acctCreditLimit=" + acctCreditLimit
                + ", acctCashCreditLimit=" + acctCashCreditLimit
                + ", acctOpenDate=" + acctOpenDate
                + ", acctExpiraionDate=" + acctExpiraionDate
                + ", acctReissueDate=" + acctReissueDate
                + ", acctCurrCycCredit=" + acctCurrCycCredit
                + ", acctCurrCycDebit=" + acctCurrCycDebit
                + ", acctAddrZip='" + acctAddrZip + '\''
                + ", acctGroupId='" + acctGroupId + '\''
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }
}
