package com.vsergeychik.carddemo.account.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

/**
 * The disclosure group record of {@code app/cpy/CVTRA02Y.cpy}, exactly <strong>50 bytes</strong>.
 *
 * <p>One Java type per copybook: this is the single model for {@code CVTRA02Y}, shared by every
 * consumer rather than re-declared per program. It has exactly one consumer, the interest
 * calculator translated from {@code app/cbl/CBACT04C.cbl}, and it supplies the interest
 * <em>rate</em> to the only monetary formula in the batch tier. A single byte of misalignment here
 * corrupts every interest amount the system computes, which is why every offset and width below is
 * a named constant carrying its copybook line citation.
 *
 * <h2>The copybook, transcribed</h2>
 * <pre>
 * L2  * Data-structure for disclosure group (RECLN = 50)
 * L4    01  DIS-GROUP-RECORD.
 * L5        05  DIS-GROUP-KEY.
 * L6            10 DIS-ACCT-GROUP-ID   PIC X(10).
 * L7            10 DIS-TRAN-TYPE-CD    PIC X(02).
 * L8            10 DIS-TRAN-CAT-CD     PIC 9(04).
 * L9        05  DIS-INT-RATE           PIC S9(04)V99.
 * L10       05  FILLER                 PIC X(28).
 * </pre>
 *
 * <table>
 *   <caption>Byte geometry, 0-based Java offsets</caption>
 *   <tr><th>Offset</th><th>Copybook line</th><th>COBOL item</th><th>PICTURE</th>
 *       <th>Bytes</th><th>Java</th></tr>
 *   <tr><td>0</td><td>L6</td><td>DIS-ACCT-GROUP-ID</td><td>X(10)</td><td>10</td>
 *       <td>{@link String}</td></tr>
 *   <tr><td>10</td><td>L7</td><td>DIS-TRAN-TYPE-CD</td><td>X(02)</td><td>2</td>
 *       <td>{@link String}</td></tr>
 *   <tr><td>12</td><td>L8</td><td>DIS-TRAN-CAT-CD</td><td>9(04)</td><td>4</td>
 *       <td>{@code int}</td></tr>
 *   <tr><td>16</td><td>L9</td><td>DIS-INT-RATE</td><td>S9(04)V99</td><td>6</td>
 *       <td>{@link BigDecimal} scale 2</td></tr>
 *   <tr><td>22</td><td>L10</td><td>FILLER</td><td>X(28)</td><td>28</td>
 *       <td>reserved span</td></tr>
 * </table>
 *
 * <p>{@code 10 + 2 + 4 + 6 + 28 = 50}, and {@code DIS-GROUP-KEY} - the concatenation of the first
 * three items - is <strong>16</strong> bytes.
 *
 * <h2>The 16-versus-17 byte key, and why it is the most expensive mistake available here</h2>
 *
 * <p>The sibling copybook {@code app/cpy/CVTRA01Y.cpy} declares a genuinely <strong>17</strong>-byte
 * key, and its record <em>also</em> totals 50 bytes. That is precisely what makes confusing the two
 * invisible to any width check:
 * <pre>
 *   CVTRA01Y  TRAN-CAT-KEY  = TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04) = 17
 *             TRAN-CAT-BAL  S9(09)V99 = 11 ; FILLER X(22) = 22                        total = 50
 *   CVTRA02Y  DIS-GROUP-KEY = DIS-ACCT-GROUP-ID X(10) + DIS-TRAN-TYPE-CD X(02)
 *                                                     + DIS-TRAN-CAT-CD  9(04) = 16
 *             DIS-INT-RATE  S9(04)V99 =  6 ; FILLER X(28) = 28                        total = 50
 * </pre>
 * The whole difference is the first sub-field: {@code X(10)} here against {@code 9(11)} there. Take
 * 17 and {@code DIS-INT-RATE} is read from bytes 17-22 instead of 16-21, every rate is shifted one
 * byte and silently corrupted, and the record still totals 50 so nothing fails until the interest
 * figures are diffed. <strong>The 17-byte key belongs to {@code CVTRA01Y} and must never be copied
 * into this record.</strong>
 *
 * <p>Three independent proofs that 16 is correct, all re-verified against this checkout:
 * <ol>
 *   <li><b>The copybook arithmetic.</b> {@code 10 + 2 + 4 = 16}.</li>
 *   <li><b>The consuming program's own FD.</b> {@code app/cbl/CBACT04C.cbl:L77-L82} declares
 *       {@code 01 FD-DISCGRP-REC.} / {@code 05 FD-DISCGRP-KEY.} with
 *       {@code 10 FD-DIS-ACCT-GROUP-ID PIC X(10).}, {@code 10 FD-DIS-TRAN-TYPE-CD PIC X(02).} and
 *       {@code 10 FD-DIS-TRAN-CAT-CD PIC 9(04).}, followed by
 *       {@code 05 FD-DISCGRP-DATA PIC X(34).} The program that reads the file splits its 50 bytes
 *       <strong>16 / 34</strong>. A 17-byte key contradicts it.</li>
 *   <li><b>The fixture.</b> {@code app/data/ASCII/discgrp.txt} holds 51 rows of exactly 50 bytes.
 *       Bytes 16-21 of each row hold exactly three distinct values - {@code 00000&#123;} in 30 rows,
 *       {@code 00150&#123;} in 15 and {@code 00250&#123;} in 6 - which decode at scale 2 to
 *       {@code 0.00}, {@code 15.00} and {@code 25.00}: clean annual percentage rates. Read from
 *       17-22 instead and every one of them is garbage.</li>
 * </ol>
 *
 * <p>The declaration order of {@link #layout()} turns that reasoning into a machine check. The
 * {@code DIS-GROUP-KEY} descriptor is a group overlay placed immediately after the last of its three
 * elementary members, at which point exactly 16 bytes of storage have been declared. A
 * {@link RecordLayout} rejects an overlay that reaches past the storage declared ahead of it, so a
 * 17-byte key fails <em>at class initialisation</em> with a message naming the offending descriptor,
 * rather than surviving to corrupt arithmetic. That is the one check a total-width assertion cannot
 * make, because both candidate layouts sum to 50.
 *
 * <h2>The sign is overpunched: {@code S9(04)V99} is 6 bytes, never 7</h2>
 *
 * <p>{@code DIS-INT-RATE} occupies four integer digit positions plus two fraction positions and
 * <strong>no sign byte at all</strong> - the sign is overpunched into the trailing byte under
 * standard zoned-decimal rules. Reserving a seventh byte for it would make the record 51 bytes and
 * shift the trailing {@code FILLER}. Row 1 of the fixture decodes as
 * {@code DIS-ACCT-GROUP-ID=[A000000000]}, {@code DIS-TRAN-TYPE-CD=[01]},
 * {@code DIS-TRAN-CAT-CD=[0001]}, {@code DIS-INT-RATE=[00150&#123;]},
 * {@code FILLER=[0000000000000000000000000000]}: the trailing {@code &#123;} is the overpunch for
 * "positive, low-order digit 0", so the digits are {@code 001500} and the value is {@code +15.00}.
 * All 51 fixture rows are positive, but both signs decode and encode correctly because the
 * overpunch alphabet lives in {@link FixedWidthCodec} and is not reimplemented here.
 *
 * <h2>Numeric policy</h2>
 *
 * <p>{@code ROUNDED} appears <strong>zero times</strong> across all 28 COBOL programs, so COBOL
 * truncates excess fractional digits on store and {@link java.math.RoundingMode#DOWN} is the only
 * faithful mode. Every store of {@code DIS-INT-RATE} therefore routes through
 * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} at precision
 * {@value #DIS_INT_RATE_INTEGER_DIGITS} and scale {@value #DIS_INT_RATE_SCALE}, which truncates the
 * fraction toward zero and then discards integer digits beyond the receiver's four, exactly as COBOL
 * does in the absence of {@code ON SIZE ERROR} - which likewise appears nowhere. No binary
 * floating-point type appears anywhere in this file: every scaled value is a {@link BigDecimal} at
 * its declared scale. Nor is any rounding mode named here - the one place a rounding mode is written
 * is {@link CobolDecimal}, and it is truncation toward zero.
 *
 * <p>{@code DIS-INT-RATE} is an <strong>annual percentage</strong>. The interest calculator divides
 * it by 1200 to obtain a monthly fraction - {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL *
 * DIS-INT-RATE) / 1200} at {@code app/cbl/CBACT04C.cbl:L464-L465} - so the fixture's {@code 15.00}
 * and {@code 25.00} mean 15% and 25% APR, and {@code 0.00} means the group earns no interest.
 *
 * <h2>The zero test is a value comparison, and it gates two paragraphs</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl:L214-L217} reads:
 * <pre>{@code
 * IF DIS-INT-RATE NOT = 0
 *     PERFORM 1300-COMPUTE-INTEREST
 *     PERFORM 1400-COMPUTE-FEES
 * END-IF
 * }</pre>
 * That is a <em>value-based</em> test on a scale-2 field, so it is exposed here as
 * {@link #disIntRateIsZero()} and {@link #disIntRateIsNotZero()}, implemented with
 * {@link BigDecimal#compareTo(BigDecimal)} and never with {@link BigDecimal#equals(Object)}:
 * {@code new BigDecimal("0.00").equals(BigDecimal.ZERO)} is <strong>{@code false}</strong>, because
 * {@code equals} compares scale as well as value. An {@code equals}-based test would report every
 * zero rate as non-zero and run the interest-and-fees block for all of them - and 30 of the 51
 * fixture rows carry {@code 0.00}, so it would corrupt the majority of an interest run. Centralising
 * the predicate here is what stops the one consumer getting it wrong.
 *
 * <h2>Preserved as declared, not tidied</h2>
 *
 * <p>The copybook declares no {@code 88}-level condition name and no {@code VALUE} clause, so no
 * enum and no status constant is invented here. The three {@code DIS-GROUP-KEY} sub-items are never
 * referenced <em>by those names</em> in any of the 28 programs - {@code CBACT04C} reaches the same
 * bytes through its FD-side {@code FD-DIS-*} names - and {@code FILLER} is never referenced at all.
 * All four are modelled regardless, because all four occupy declared bytes and dropping any of them
 * would move every offset after it.
 *
 * <h2>FILLER: space-filled when fresh, preserved when decoded</h2>
 *
 * <p>{@code FILLER X(28)} is a first-class span, not an implicit gap. Two behaviours apply, and the
 * asymmetry is deliberate rather than an inconsistency:
 * <ul>
 *   <li>a <strong>freshly constructed</strong> record initialises the span to 28 spaces, the COBOL
 *       convention for a character {@code FILLER} that declares no {@code VALUE};</li>
 *   <li>a record <strong>decoded from stored bytes</strong> retains that source's filler bytes
 *       verbatim, so decode-then-encode is byte-identical.</li>
 * </ul>
 * The second behaviour matters because {@code app/data/ASCII/discgrp.txt} carries 28 <em>zeros</em>
 * in the filler of all 51 rows, not spaces. Preserving them lets a fixture survive a round trip
 * through the parity harness unchanged. Both behaviours keep the span present at its declared width,
 * which is what the record-width check verifies. Note also that no COBOL program ever
 * <em>writes</em> this record - {@code CBACT04C} only performs {@code READ DISCGRP-FILE INTO
 * DIS-GROUP-RECORD} at {@code app/cbl/CBACT04C.cbl:L416} and {@code L444} - so the write path is not
 * parity-observable and exists to serve fixture seeding.
 *
 * <h2>Using this type</h2>
 *
 * <p>The charset is always an explicit parameter: fixed-width mainframe data is bytes in a specific
 * code page, and a platform default is the classic silent corrupter of it. Pass the ASCII code page
 * for the text fixtures and the EBCDIC one for the binary datasets.
 *
 * <p>Accessors follow the copybook item names mechanically, so the correspondence is auditable at a
 * glance: the no-argument form reads and the one-argument form writes. Writes apply COBOL
 * {@code MOVE} semantics rather than plain assignment - {@code PIC X} pads and truncates on the
 * <em>right</em>, {@code PIC 9} zero-fills and truncates on the <em>left</em> - which is what makes
 * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} at {@code app/cbl/CBACT04C.cbl:L437} store
 * {@code 'DEFAULT   '} in a {@code PIC X(10)} field. Reads are <strong>never trimmed</strong>: the
 * fixture's {@code 'DEFAULT   '} and {@code 'ZEROAPR   '} are padded to 10 and that padding is part
 * of the field's value.
 *
 * <p>Example, reproducing the key construction at {@code app/cbl/CBACT04C.cbl:L210-L214}:
 * <pre>{@code
 * DisclosureGroupRecord group = DisclosureGroupRecord.decode(rowBytes, StandardCharsets.US_ASCII);
 * if (group.disIntRateIsNotZero()) {
 *     BigDecimal monthly = CobolDecimal.monthlyInterest(categoryBalance, group.disIntRate());
 *     ...
 * }
 * }</pre>
 *
 * <h2>Mutability</h2>
 *
 * <p>The 50-byte area is mutable, exactly as a COBOL record area is, but strictly per instance: it
 * is allocated in the constructor, never shared, and never handed out - every accessor that exposes
 * bytes returns a copy. There is no static mutable state of any kind: the descriptors and the layout
 * are deeply immutable records held in {@code static final} fields, and no instance is cached.
 * Because the area is mutable, an instance must not be used as a hash-map key while it is still
 * being populated.
 *
 * <p>Because the legacy COBOL cannot be executed in this environment, the parity baseline is
 * statically derived and this file <em>is</em> the evidence. It is written so that a reviewer with
 * {@code app/cpy/CVTRA02Y.cpy} open can confirm every byte in under a minute, and can see at a
 * glance that the key is 16.
 *
 * @see CobolDecimal
 * @see FixedWidthCodec
 * @see FixedWidthRecord
 */
public final class DisclosureGroupRecord {

    // =================================================================================================
    // Geometry. Every offset and width is a named constant carrying its copybook line citation, so the
    // whole record can be diffed against app/cpy/CVTRA02Y.cpy by eye. Do not compute any of these from
    // another record's shape: CVTRA01Y's key is 17 bytes and its record also totals 50.
    // =================================================================================================

    /**
     * The declared record width in bytes, from the copybook header
     * {@code Data-structure for disclosure group (RECLN = 50)} at {@code app/cpy/CVTRA02Y.cpy:L2}.
     * Independently corroborated by {@code FD-DISCGRP-KEY} 16 + {@code FD-DISCGRP-DATA X(34)} at
     * {@code app/cbl/CBACT04C.cbl:L78-L82}, and by all 51 rows of {@code app/data/ASCII/discgrp.txt}
     * measuring exactly 50 bytes.
     */
    public static final int RECORD_LENGTH = 50;

    /** The {@code DIS-GROUP-KEY} group item name, {@code app/cpy/CVTRA02Y.cpy:L5}. */
    public static final String DIS_GROUP_KEY_NAME = "DIS-GROUP-KEY";

    /** {@code DIS-GROUP-KEY} begins the record, {@code app/cpy/CVTRA02Y.cpy:L5}. */
    public static final int DIS_GROUP_KEY_OFFSET = 0;

    /**
     * {@code DIS-GROUP-KEY} is <strong>16</strong> bytes: {@code X(10)} + {@code X(02)} +
     * {@code 9(04)}, {@code app/cpy/CVTRA02Y.cpy:L6-L8}. It is <em>not</em> 17 - that is
     * {@code TRAN-CAT-KEY} of {@code app/cpy/CVTRA01Y.cpy}, whose record also totals 50 bytes.
     */
    public static final int DIS_GROUP_KEY_LENGTH = 16;

    /** The {@code DIS-ACCT-GROUP-ID} item name, {@code app/cpy/CVTRA02Y.cpy:L6}. */
    public static final String DIS_ACCT_GROUP_ID_NAME = "DIS-ACCT-GROUP-ID";

    /** {@code DIS-ACCT-GROUP-ID} starts at byte 0, {@code app/cpy/CVTRA02Y.cpy:L6}. */
    public static final int DIS_ACCT_GROUP_ID_OFFSET = 0;

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)}, {@code app/cpy/CVTRA02Y.cpy:L6}. */
    public static final int DIS_ACCT_GROUP_ID_LENGTH = 10;

    /** The {@code DIS-TRAN-TYPE-CD} item name, {@code app/cpy/CVTRA02Y.cpy:L7}. */
    public static final String DIS_TRAN_TYPE_CD_NAME = "DIS-TRAN-TYPE-CD";

    /** {@code DIS-TRAN-TYPE-CD} starts at byte 10, {@code app/cpy/CVTRA02Y.cpy:L7}. */
    public static final int DIS_TRAN_TYPE_CD_OFFSET = 10;

    /**
     * {@code DIS-TRAN-TYPE-CD PIC X(02)}, {@code app/cpy/CVTRA02Y.cpy:L7}. Declared
     * <strong>alphanumeric</strong> even though the fixture's values read {@code 01}, {@code 02},
     * {@code 03} and so on; converting it to a number would strip the leading zero and break the
     * key bytes.
     */
    public static final int DIS_TRAN_TYPE_CD_LENGTH = 2;

    /** The {@code DIS-TRAN-CAT-CD} item name, {@code app/cpy/CVTRA02Y.cpy:L8}. */
    public static final String DIS_TRAN_CAT_CD_NAME = "DIS-TRAN-CAT-CD";

    /** {@code DIS-TRAN-CAT-CD} starts at byte 12, {@code app/cpy/CVTRA02Y.cpy:L8}. */
    public static final int DIS_TRAN_CAT_CD_OFFSET = 12;

    /** {@code DIS-TRAN-CAT-CD PIC 9(04)}, {@code app/cpy/CVTRA02Y.cpy:L8}. */
    public static final int DIS_TRAN_CAT_CD_LENGTH = 4;

    /** The {@code DIS-INT-RATE} item name, {@code app/cpy/CVTRA02Y.cpy:L9}. */
    public static final String DIS_INT_RATE_NAME = "DIS-INT-RATE";

    /**
     * {@code DIS-INT-RATE} starts at byte <strong>16</strong>, {@code app/cpy/CVTRA02Y.cpy:L9} -
     * immediately after the 16-byte key. This is the single offset the 17-byte-key mistake gets
     * wrong, and the fixture proves it: bytes 16-21 of every row of
     * {@code app/data/ASCII/discgrp.txt} hold a well-formed 6-character zoned image.
     */
    public static final int DIS_INT_RATE_OFFSET = 16;

    /**
     * {@code p} in {@code DIS-INT-RATE PIC S9(04)V99}: four digit positions left of the implied
     * decimal point, {@code app/cpy/CVTRA02Y.cpy:L9}.
     */
    public static final int DIS_INT_RATE_INTEGER_DIGITS = 4;

    /**
     * {@code s} in {@code DIS-INT-RATE PIC S9(04)V99}: two digit positions right of the implied
     * decimal point, {@code app/cpy/CVTRA02Y.cpy:L9}. Taken from
     * {@link CobolDecimal#MONETARY_SCALE} because every scaled numeric in this system is scale 2.
     */
    public static final int DIS_INT_RATE_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code DIS-INT-RATE} occupies {@code p + s} = <strong>6</strong> bytes and reserves
     * <strong>no</strong> byte for its sign, which is overpunched into the trailing byte. A seventh
     * byte would make the record 51 and fail the width check.
     */
    public static final int DIS_INT_RATE_LENGTH = DIS_INT_RATE_INTEGER_DIGITS + DIS_INT_RATE_SCALE;

    /**
     * The reserved COBOL name of the trailing unnamed span, {@code app/cpy/CVTRA02Y.cpy:L10}. It is
     * non-referable in COBOL and is exposed here only so a field-by-field differ can label the span.
     */
    public static final String FILLER_NAME = "FILLER";

    /** {@code FILLER} starts at byte 22, {@code app/cpy/CVTRA02Y.cpy:L10}. */
    public static final int FILLER_OFFSET = 22;

    /**
     * {@code FILLER PIC X(28)}, {@code app/cpy/CVTRA02Y.cpy:L10}. Omitting it would leave the layout
     * 28 bytes short of 50 and move nothing - because the layout would refuse to be constructed at
     * all, which is the point.
     */
    public static final int FILLER_LENGTH = 28;

    /**
     * The literal the interest calculator falls back to when a disclosure group is not found:
     * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} at {@code app/cbl/CBACT04C.cbl:L437}, reached
     * only when the first read returns file status {@code '23'} at {@code L436}.
     *
     * <p>Seven characters, moved into a {@code PIC X(10)} receiver, so storing it through
     * {@link #disAcctGroupId(String)} yields the 10-byte image {@code "DEFAULT   "} - which is
     * exactly how the group id appears in 17 of the 51 rows of {@code app/data/ASCII/discgrp.txt}.
     * Note that {@code '00'} <em>and</em> {@code '23'} are both accepted by the program at
     * {@code L422}, so a missing disclosure group is a normal path rather than an error; this type
     * adds no validation that would reject it.
     */
    public static final String DEFAULT_ACCT_GROUP_ID = "DEFAULT";

    // =================================================================================================
    // Descriptors. FieldSpan and RecordLayout are deeply immutable records - RecordLayout copies its
    // span list with List.copyOf - so these static finals are genuinely immutable and hold no mutable
    // state. No array is declared and none can escape.
    // =================================================================================================

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} at byte 0, {@code app/cpy/CVTRA02Y.cpy:L6}. */
    public static final FieldSpan DIS_ACCT_GROUP_ID_SPAN = FieldSpan.alphanumeric(
            DIS_ACCT_GROUP_ID_NAME, DIS_ACCT_GROUP_ID_OFFSET, DIS_ACCT_GROUP_ID_LENGTH);

    /** {@code DIS-TRAN-TYPE-CD PIC X(02)} at byte 10, {@code app/cpy/CVTRA02Y.cpy:L7}. */
    public static final FieldSpan DIS_TRAN_TYPE_CD_SPAN = FieldSpan.alphanumeric(
            DIS_TRAN_TYPE_CD_NAME, DIS_TRAN_TYPE_CD_OFFSET, DIS_TRAN_TYPE_CD_LENGTH);

    /** {@code DIS-TRAN-CAT-CD PIC 9(04)} at byte 12, {@code app/cpy/CVTRA02Y.cpy:L8}. */
    public static final FieldSpan DIS_TRAN_CAT_CD_SPAN = FieldSpan.unsignedNumeric(
            DIS_TRAN_CAT_CD_NAME, DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH);

    /**
     * {@code DIS-GROUP-KEY} at byte 0, {@code app/cpy/CVTRA02Y.cpy:L5}, as a 16-byte group overlay
     * over the same backing storage as its three elementary members. A COBOL group item is treated
     * as alphanumeric, hence {@link PictureKind#ALPHANUMERIC}.
     *
     * <p>Declared through {@code FieldSpan.redefining} because a flattened layout represents a group
     * item as an overlay: it views storage the elementary members already account for, so it neither
     * advances the layout cursor nor contributes to the record total. Reading the whole key and
     * reading its three parts therefore address one span and can never disagree.
     *
     * <p>The width is stated as {@link #DIS_GROUP_KEY_LENGTH}, which the layout cross-checks against
     * the storage its members actually declare - see {@link #layout()}.
     */
    public static final FieldSpan DIS_GROUP_KEY_SPAN = FieldSpan.redefining(
            DIS_GROUP_KEY_NAME, DIS_GROUP_KEY_OFFSET, DIS_GROUP_KEY_LENGTH,
            PictureKind.ALPHANUMERIC);

    /**
     * {@code DIS-INT-RATE PIC S9(04)V99} at byte 16, {@code app/cpy/CVTRA02Y.cpy:L9}.
     *
     * <p>Declared by digit counts rather than by byte width, so the span can only ever be
     * {@code 4 + 2 = 6} bytes wide and a phantom sign byte cannot be reintroduced.
     */
    public static final FieldSpan DIS_INT_RATE_SPAN = FieldSpan.signedScaled(
            DIS_INT_RATE_NAME, DIS_INT_RATE_OFFSET, DIS_INT_RATE_INTEGER_DIGITS,
            DIS_INT_RATE_SCALE);

    /**
     * {@code FILLER PIC X(28)} at byte 22, {@code app/cpy/CVTRA02Y.cpy:L10}. It declares no
     * {@code VALUE}, so a freshly initialised record fills it with spaces.
     */
    public static final FieldSpan FILLER_SPAN = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete, self-checking layout of the record.
     *
     * <p>The declaration order below is the copybook's, with one deliberate adjustment that a
     * flattened layout forces: the {@code DIS-GROUP-KEY} group descriptor follows its three
     * elementary members rather than preceding them, because an overlay must view storage that has
     * already been declared. That placement is not merely tolerated, it is load-bearing. At the point
     * the overlay is declared, exactly {@link #DIS_GROUP_KEY_LENGTH} bytes of storage exist, so a key
     * width of 17 would be rejected here - at class initialisation - with a message naming the
     * descriptor. It is the only mechanical guard against the 16-versus-17 confusion, because a
     * total-width check cannot distinguish the two layouts: both sum to 50.
     *
     * <p>Construction additionally proves that the storage spans are contiguous from byte 0 with no
     * gap and no overlap, and that they sum to exactly {@link #RECORD_LENGTH}. Dropping the trailing
     * {@code FILLER} would leave the total 28 bytes short and fail here; reserving a sign byte for
     * {@code DIS-INT-RATE} would make it 51 and likewise fail here.
     */
    private static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            DIS_ACCT_GROUP_ID_SPAN,
            DIS_TRAN_TYPE_CD_SPAN,
            DIS_TRAN_CAT_CD_SPAN,
            DIS_GROUP_KEY_SPAN,
            DIS_INT_RATE_SPAN,
            FILLER_SPAN);

    // =================================================================================================
    // Instance state. Both fields are final; the codec is immutable; the record area is mutable exactly
    // as a COBOL record area is, but is per-instance and is never handed out.
    // =================================================================================================

    /** The codec that applies COBOL {@code MOVE}, zoned-decimal and {@code FILLER} semantics. */
    private final FixedWidthCodec codec;

    /** This record's own 50-byte area. Never shared, never returned by reference. */
    private final FixedWidthRecord area;

    /**
     * Wraps an already-built area. Private so that every public entry point states its charset
     * explicitly and no caller can supply an area of the wrong width.
     */
    private DisclosureGroupRecord(FixedWidthCodec codec, FixedWidthRecord area) {
        this.codec = codec;
        this.area = area;
    }

    /**
     * Allocates a fresh, initialised record: the two character items and the {@code FILLER} are
     * space-filled and the two numeric items are zero-filled, which is the COBOL
     * {@code INITIALIZE} convention for the declared pictures.
     *
     * <p>The freshly initialised {@code DIS-INT-RATE} image is {@code "000000"}, the unsigned zoned
     * form, which reads back as {@code 0.00}. Storing a value through
     * {@link #disIntRate(BigDecimal)} replaces it with the overpunched form, so {@code 15.00}
     * becomes {@code "00150&#123;"}. No COBOL program writes this record, so neither image is
     * parity-observable; the distinction is documented only so a fixture round trip is predictable.
     *
     * @param charset the code page of the record's bytes, stated explicitly - the ASCII code page for
     *                {@code app/data/ASCII} text fixtures, the EBCDIC one for binary datasets. Never
     *                the platform default
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode each digit, each sign
     *                                  overpunch character and the space to exactly one byte
     */
    public DisclosureGroupRecord(Charset charset) {
        this.codec = new FixedWidthCodec(charset);
        this.area = this.codec.newRecord(LAYOUT);
    }

    /**
     * Decodes a stored 50-byte image, which is the {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD}
     * of {@code app/cbl/CBACT04C.cbl:L416} and {@code L444}.
     *
     * <p>The bytes are copied, not aliased, and are retained verbatim: the source's {@code FILLER}
     * bytes survive, so re-encoding this record reproduces the input byte for byte. That is what lets
     * the 28 zeros in every row of {@code app/data/ASCII/discgrp.txt} round-trip unchanged.
     *
     * <p>A row whose width is not exactly {@link #RECORD_LENGTH} is <strong>rejected</strong> rather
     * than padded or truncated. Every row of this record's fixture is exactly 50 bytes, so a
     * different width means the layout and the data disagree and silently absorbing it would let
     * every field offset drift.
     *
     * @param record  the stored bytes, exactly {@link #RECORD_LENGTH} of them
     * @param charset the code page of those bytes, stated explicitly
     * @return a record over a copy of {@code record}
     * @throws NullPointerException     if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@link #RECORD_LENGTH} bytes,
     *                                  or {@code charset} is not a usable single-byte code page
     */
    public static DisclosureGroupRecord decode(byte[] record, Charset charset) {
        Objects.requireNonNull(record, "Stored bytes are required to decode a DIS-GROUP-RECORD; "
                + "call the (Charset) constructor to allocate a fresh, initialised record instead");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return new DisclosureGroupRecord(codec, codec.wrap(record, LAYOUT));
    }

    /**
     * Decodes a stored image supplied as text, for a caller that has read the row as a line of
     * {@code app/data/ASCII/discgrp.txt}. The image is encoded under {@code charset} and then decoded
     * exactly as {@link #decode(byte[], Charset)} does.
     *
     * @param image   the row image, exactly {@link #RECORD_LENGTH} characters under {@code charset}
     * @param charset the code page of the row, stated explicitly
     * @return a record over the encoded image
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if the image does not encode to exactly
     *                                  {@link #RECORD_LENGTH} bytes, or {@code charset} is not a
     *                                  usable single-byte code page
     */
    public static DisclosureGroupRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A row image is required to decode a disclosure group record");
        Objects.requireNonNull(charset, "A charset is required: a row image is characters and the "
                + "record is bytes, so the code page that maps between them must be stated "
                + "explicitly and is never derived from the platform");
        return decode(FixedWidthRecord.encodeText(image, charset, "a DIS-GROUP-RECORD image"),
                charset);
    }

    /**
     * The record's self-checking layout, for a repository or a field-by-field differ that needs to
     * address spans by descriptor or enumerate them by name.
     *
     * @return the immutable layout; its span list is unmodifiable
     */
    public static RecordLayout layout() {
        return LAYOUT;
    }

    // =================================================================================================
    // DIS-ACCT-GROUP-ID  PIC X(10)  offset 0   app/cpy/CVTRA02Y.cpy:L6
    // =================================================================================================

    /**
     * Reads {@code DIS-ACCT-GROUP-ID}, <strong>untrimmed</strong>: exactly
     * {@value #DIS_ACCT_GROUP_ID_LENGTH} characters, trailing spaces included.
     *
     * <p>The padding is part of the field's value, and a field-by-field differ compares it. Of the 51
     * rows of {@code app/data/ASCII/discgrp.txt}, 17 read {@code "A000000000"} and the other 34 read
     * {@code "DEFAULT   "} and {@code "ZEROAPR   "} - both right-padded to 10. Trimming here would
     * silently discard those bytes and make a real difference invisible.
     *
     * @return the field's {@value #DIS_ACCT_GROUP_ID_LENGTH} characters
     */
    public String disAcctGroupId() {
        return codec.readPicX(area, DIS_ACCT_GROUP_ID_SPAN);
    }

    /**
     * Writes {@code DIS-ACCT-GROUP-ID} with COBOL alphanumeric {@code MOVE} semantics: shorter values
     * are padded on the right with spaces and longer ones are truncated on the <strong>right</strong>,
     * because a {@code PIC X} receiver is filled from its leftmost position.
     *
     * <p>This reproduces {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} at
     * {@code app/cbl/CBACT04C.cbl:L437}, where a seven-character literal lands in a {@code PIC X(10)}
     * field as {@code "DEFAULT   "}. A plain Java assignment would neither pad nor truncate, and the
     * resulting key bytes would be wrong with nothing to signal it.
     *
     * @param value the sending value; may be shorter or longer than
     *              {@value #DIS_ACCT_GROUP_ID_LENGTH}, and may be empty
     * @throws NullPointerException if {@code value} is {@code null}; move an empty string to blank the
     *                              field
     */
    public void disAcctGroupId(String value) {
        codec.writePicX(area, DIS_ACCT_GROUP_ID_SPAN, value);
    }

    // =================================================================================================
    // DIS-TRAN-TYPE-CD  PIC X(02)  offset 10   app/cpy/CVTRA02Y.cpy:L7
    // =================================================================================================

    /**
     * Reads {@code DIS-TRAN-TYPE-CD}, untrimmed: exactly {@value #DIS_TRAN_TYPE_CD_LENGTH}
     * characters.
     *
     * <p>Deliberately a {@link String} and never a number. The fixture's values read {@code "01"}
     * through {@code "07"} and so look numeric, but the copybook declares the item {@code PIC X(02)};
     * decoding it as an {@code int} would strip the leading zero and corrupt the key bytes on the way
     * back out.
     *
     * @return the field's {@value #DIS_TRAN_TYPE_CD_LENGTH} characters
     */
    public String disTranTypeCd() {
        return codec.readPicX(area, DIS_TRAN_TYPE_CD_SPAN);
    }

    /**
     * Writes {@code DIS-TRAN-TYPE-CD} with COBOL alphanumeric {@code MOVE} semantics - right-padded
     * with spaces, truncated on the right - reproducing
     * {@code MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD} at {@code app/cbl/CBACT04C.cbl:L212}, an
     * {@code X(02)} to {@code X(02)} move where sender and receiver are the same width.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void disTranTypeCd(String value) {
        codec.writePicX(area, DIS_TRAN_TYPE_CD_SPAN, value);
    }

    // =================================================================================================
    // DIS-TRAN-CAT-CD  PIC 9(04)  offset 12   app/cpy/CVTRA02Y.cpy:L8
    // =================================================================================================

    /**
     * Reads {@code DIS-TRAN-CAT-CD} as an {@code int}. A {@code PIC 9(n)} item with {@code n} of 9 or
     * fewer maps to {@code int}, and this one declares four digits.
     *
     * @return the value the field's four digits denote; {@code 1} for the fixture's {@code "0001"}
     * @throws IllegalArgumentException if the span does not hold four digits, which means the record
     *                                  is misaligned rather than merely unexpected
     */
    public int disTranCatCd() {
        return codec.readPic9AsInt(area, DIS_TRAN_CAT_CD_SPAN);
    }

    /**
     * The raw {@value #DIS_TRAN_CAT_CD_LENGTH}-character zero-filled image of
     * {@code DIS-TRAN-CAT-CD}, for building the composite key byte-exactly and for a differ that
     * compares stored images rather than decoded values.
     *
     * @return the field's four characters, for example {@code "0001"}
     */
    public String disTranCatCdImage() {
        return area.readSpan(DIS_TRAN_CAT_CD_SPAN);
    }

    /**
     * Writes {@code DIS-TRAN-CAT-CD} with COBOL numeric {@code MOVE} semantics: zero-filled on the
     * <strong>left</strong> to four digits, and truncated on the <strong>left</strong> when the value
     * has more, because a numeric receiver is aligned on its implied decimal point and so keeps its
     * low-order digits. {@code 1} stores as {@code "0001"} and {@code 12345} stores as {@code "2345"},
     * never {@code "1234"}. COBOL reports that loss only under {@code ON SIZE ERROR}, which appears
     * nowhere in this codebase, so nothing is thrown here either.
     *
     * <p>This reproduces {@code MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD} at
     * {@code app/cbl/CBACT04C.cbl:L211}, a {@code 9(04)} to {@code 9(04)} move.
     *
     * @param value the sending value; must not be negative, because {@code PIC 9} is an unsigned
     *              picture with no sign position
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void disTranCatCd(int value) {
        codec.writePic9(area, DIS_TRAN_CAT_CD_SPAN, value);
    }

    /**
     * Writes {@code DIS-TRAN-CAT-CD} from a digit string, which is the shape of a COBOL
     * {@code MOVE '05' TO} a numeric receiver: an alphanumeric literal into a numeric field,
     * zero-filled and truncated on the left exactly as {@link #disTranCatCd(int)} is.
     *
     * @param digits the sending digits; must be non-empty and contain only {@code '0'} to {@code '9'}
     * @throws NullPointerException     if {@code digits} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is empty or holds a non-digit
     */
    public void disTranCatCd(String digits) {
        codec.writePic9(area, DIS_TRAN_CAT_CD_SPAN, digits);
    }

    // =================================================================================================
    // DIS-GROUP-KEY  16 bytes  offset 0   app/cpy/CVTRA02Y.cpy:L5  - a group overlay, read only
    // =================================================================================================

    /**
     * The composite {@code DIS-GROUP-KEY} as its {@value #DIS_GROUP_KEY_LENGTH} stored characters,
     * untrimmed - the concatenation of {@code DIS-ACCT-GROUP-ID}, {@code DIS-TRAN-TYPE-CD} and
     * {@code DIS-TRAN-CAT-CD}, read from the same backing bytes those three accessors address.
     *
     * <p><strong>Sixteen</strong> characters, not seventeen. Seventeen is {@code TRAN-CAT-KEY} of
     * {@code app/cpy/CVTRA01Y.cpy}, and {@code app/cbl/CBACT04C.cbl:L78-L82} splits this record
     * 16 / 34.
     *
     * <p>Read only by design, mirroring the program: {@code app/cbl/CBACT04C.cbl:L210-L212} builds
     * the key by moving into the three sub-items and then reads the file by the group, so a caller
     * sets the parts and reads the whole. There is deliberately no setter for the group, because no
     * COBOL statement in this codebase moves into it.
     *
     * @return the key's {@value #DIS_GROUP_KEY_LENGTH} characters, for example
     *         {@code "A00000000001" + "0001"}
     */
    public String disGroupKey() {
        return area.readSpan(DIS_GROUP_KEY_SPAN);
    }

    /**
     * The composite {@code DIS-GROUP-KEY} as a fresh {@value #DIS_GROUP_KEY_LENGTH}-byte array, for a
     * repository issuing a keyed read.
     *
     * @return a copy of the key's bytes
     */
    public byte[] disGroupKeyBytes() {
        return area.readSpanBytes(DIS_GROUP_KEY_SPAN);
    }

    // =================================================================================================
    // DIS-INT-RATE  PIC S9(04)V99  offset 16, 6 bytes   app/cpy/CVTRA02Y.cpy:L9
    // =================================================================================================

    /**
     * Reads {@code DIS-INT-RATE} as a {@link BigDecimal} whose {@link BigDecimal#scale()} is exactly
     * {@value #DIS_INT_RATE_SCALE}.
     *
     * <p>The six stored characters are a zoned {@code DISPLAY} image whose trailing character carries
     * both the low-order digit and the sign, so {@code "00150&#123;"} decodes to {@code 15.00} and the
     * corresponding negative overpunch decodes to {@code -15.00}. The unsigned form
     * {@code "001500"}, which a freshly initialised record holds before any store, also decodes to
     * {@code 15.00}.
     *
     * <p>The value is an <strong>annual percentage</strong>: the interest calculator divides it by
     * 1200 at {@code app/cbl/CBACT04C.cbl:L464-L465}, so {@code 15.00} is 15% APR.
     *
     * @return the rate at scale {@value #DIS_INT_RATE_SCALE}
     * @throws IllegalArgumentException if the span does not hold a valid signed zoned image, which
     *                                  means the record is misaligned
     */
    public BigDecimal disIntRate() {
        return codec.readSignedScaled(area, DIS_INT_RATE_SPAN, DIS_INT_RATE_SCALE);
    }

    /**
     * The raw {@value #DIS_INT_RATE_LENGTH}-character zoned image of {@code DIS-INT-RATE}, sign
     * overpunch included - {@code "00150&#123;"} for {@code +15.00}. Exposed so a differ can compare
     * the stored bytes, and so a reviewer can see the overpunch character directly.
     *
     * @return the field's {@value #DIS_INT_RATE_LENGTH} characters
     */
    public String disIntRateImage() {
        return area.readSpan(DIS_INT_RATE_SPAN);
    }

    /**
     * Writes {@code DIS-INT-RATE} into its declared {@code PIC S9(04)V99} receiver.
     *
     * <p>Storing follows COBOL exactly, in this order: excess fractional digits are truncated
     * <em>toward zero</em>, so {@code 1.239} stores as {@code 1.23} and {@code -1.239} stores as
     * {@code -1.23}; then integer digits beyond {@value #DIS_INT_RATE_INTEGER_DIGITS} are discarded,
     * the field keeping its low-order digits and the value's sign. Neither loss is reported, because
     * {@code ROUNDED} and {@code ON SIZE ERROR} both appear zero times across all 28 programs. The
     * digits are then zero-filled on the left to six characters and the trailing one is replaced by
     * its sign overpunch.
     *
     * <p>Both truncations are delegated to
     * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} at precision
     * {@value #DIS_INT_RATE_INTEGER_DIGITS} and scale {@value #DIS_INT_RATE_SCALE}, which is the one
     * place in this system where a scale and a rounding mode are named. This class never constructs a
     * {@link BigDecimal} and never calls {@code setScale} itself.
     *
     * @param value the rate to store
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void disIntRate(BigDecimal value) {
        codec.writeSignedScaled(area, DIS_INT_RATE_SPAN, value, DIS_INT_RATE_SCALE);
    }

    /**
     * Whether {@code DIS-INT-RATE} is zero <em>by value</em>.
     *
     * <p>Implemented with {@link BigDecimal#compareTo(BigDecimal)} and deliberately not with
     * {@link BigDecimal#equals(Object)}, because {@code equals} compares scale as well as value:
     * {@code new BigDecimal("0.00").equals(BigDecimal.ZERO)} is <strong>{@code false}</strong>, and
     * this field always reads at scale {@value #DIS_INT_RATE_SCALE}. An {@code equals}-based test
     * would therefore report every zero rate as non-zero. Since 30 of the 51 rows of
     * {@code app/data/ASCII/discgrp.txt} carry {@code 0.00}, that single mistake would invert the
     * dominant branch of an interest run.
     *
     * @return {@code true} when the rate is zero at any scale
     */
    public boolean disIntRateIsZero() {
        return disIntRate().compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Whether {@code DIS-INT-RATE} is non-zero by value - the exact condition of
     * {@code IF DIS-INT-RATE NOT = 0} at {@code app/cbl/CBACT04C.cbl:L214}, which gates
     * {@code PERFORM 1300-COMPUTE-INTEREST} at {@code L215} and
     * {@code PERFORM 1400-COMPUTE-FEES} at {@code L216}.
     *
     * <p>Exposed alongside {@link #disIntRateIsZero()} so the one consumer reads like its COBOL and
     * cannot reintroduce the {@code equals}-on-zero defect. The fee paragraph it gates is itself a
     * documented no-op stub - {@code app/cbl/CBACT04C.cbl:L517-L519} is literally
     * {@code * To be implemented} followed by {@code EXIT.} - and must remain one; that is the
     * consumer's concern, but it is why this predicate matters.
     *
     * @return {@code true} when the rate is not zero
     */
    public boolean disIntRateIsNotZero() {
        return !disIntRateIsZero();
    }

    // =================================================================================================
    // FILLER  PIC X(28)  offset 22   app/cpy/CVTRA02Y.cpy:L10
    // =================================================================================================

    /**
     * The {@code FILLER} span as its {@value #FILLER_LENGTH} stored characters.
     *
     * <p>{@code FILLER} is non-referable in COBOL and is never read by any program, but it occupies
     * declared bytes and is therefore a first-class span here. Exposing it lets a round-trip test
     * assert that the span survives decode and re-encode unchanged.
     *
     * <p>Its content depends on provenance, and the asymmetry is intended: a freshly constructed
     * record holds {@value #FILLER_LENGTH} spaces, while a record decoded from stored bytes holds
     * whatever the source held - and every row of {@code app/data/ASCII/discgrp.txt} holds
     * {@value #FILLER_LENGTH} <em>zeros</em>. Both keep the span present at its declared width.
     *
     * @return the span's {@value #FILLER_LENGTH} characters
     */
    public String filler() {
        return area.readSpan(FILLER_SPAN);
    }

    /**
     * The {@code FILLER} span as a fresh {@value #FILLER_LENGTH}-byte array.
     *
     * @return a copy of the span's bytes
     */
    public byte[] fillerBytes() {
        return area.readSpanBytes(FILLER_SPAN);
    }

    // =================================================================================================
    // Whole-record access.
    // =================================================================================================

    /**
     * The complete {@value #RECORD_LENGTH}-byte image, as a fresh array. This is the serialised form a
     * repository writes and the form a parity round trip compares.
     *
     * <p>A copy is returned, never the internal area, so mutating the result cannot change this
     * record behind its owner's back.
     *
     * @return a copy of all {@value #RECORD_LENGTH} bytes
     */
    public byte[] encode() {
        return area.toByteArray();
    }

    /**
     * The complete {@value #RECORD_LENGTH}-character image, decoded under this record's charset. The
     * character-level counterpart of {@link #encode()}, for comparing against a line of
     * {@code app/data/ASCII/discgrp.txt}.
     *
     * @return the record's {@value #RECORD_LENGTH} characters
     */
    public String encodeToString() {
        return area.readString(0, RECORD_LENGTH);
    }

    /**
     * The declared record width, always {@value #RECORD_LENGTH}. Present so a caller can assert the
     * geometry without reaching for the constant.
     *
     * @return {@value #RECORD_LENGTH}
     */
    public int recordLength() {
        return area.recordLength();
    }

    /**
     * The code page this record's bytes are held in, as supplied at construction and never derived
     * from the platform.
     *
     * @return the charset
     */
    public Charset charset() {
        return area.charset();
    }

    // =================================================================================================
    // Object contract. Deliberately over the decoded items rather than over the raw bytes - see below.
    // =================================================================================================

    /**
     * The five declared items in copybook order, as an immutable tuple. Comparison and hashing both
     * delegate to it so that {@link #equals(Object)} carries exactly one decision point per
     * {@link Object} contract requirement and none per field, which keeps every branch in this class
     * trivially reachable from a plain unit test.
     */
    private List<Object> items() {
        return List.of(disAcctGroupId(), disTranTypeCd(), disTranCatCd(), disIntRate(), filler());
    }

    /**
     * Value equality over all five declared items of the copybook, {@code FILLER} included, so no
     * declared byte is silently excluded from identity.
     *
     * <p>{@link BigDecimal#equals(Object)} is scale-sensitive, but {@code DIS-INT-RATE} is normalised
     * to scale {@value #DIS_INT_RATE_SCALE} on every store <em>and</em> every read, so the comparison
     * is consistent. Testing that field <em>against zero</em> nevertheless requires
     * {@link BigDecimal#compareTo(BigDecimal)} - use {@link #disIntRateIsZero()} rather than comparing
     * to {@link BigDecimal#ZERO} with {@code equals}.
     *
     * <p>This is item equality, not byte equality, and the two can differ in one specific way: the
     * unsigned zoned image {@code "001500"} and the overpunched image {@code "00150&#123;"} both
     * decode to {@code 15.00}, so records holding them compare equal here while their bytes differ.
     * Where byte identity is what matters - a parity round trip, for instance - compare
     * {@link #encode()} instead. For the same reason a freshly constructed record, whose
     * {@code FILLER} is spaces, is not equal to one decoded from a fixture row, whose {@code FILLER}
     * is zeros.
     *
     * <p>The charset is not part of identity: the items are already decoded text and numbers, so a
     * record read from an EBCDIC dataset equals one read from the equivalent ASCII fixture.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a disclosure group record with equal items
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DisclosureGroupRecord that)) {
            return false;
        }
        return items().equals(that.items());
    }

    /**
     * Hashes the same five items {@link #equals(Object)} compares, so the two are consistent.
     *
     * <p>The backing area is mutable, exactly as a COBOL record area is, so an instance must not be
     * used as a hash-map key while it is still being populated.
     *
     * @return the tuple's hash code
     */
    @Override
    public int hashCode() {
        return items().hashCode();
    }

    /**
     * A diagnostic rendering naming every item as the copybook spells it, with the character items
     * quoted so their trailing padding is visible and the rate shown both decoded and as its stored
     * zoned image.
     *
     * <p>The padding and the raw image are included precisely because they are what a parity failure
     * turns on: {@code "DEFAULT   "} against {@code "DEFAULT"}, or a filler of zeros against one of
     * spaces, is invisible in a rendering that trims.
     *
     * @return the record's items, never {@code null}
     */
    @Override
    public String toString() {
        return "DisclosureGroupRecord["
                + DIS_ACCT_GROUP_ID_NAME + "='" + disAcctGroupId() + "', "
                + DIS_TRAN_TYPE_CD_NAME + "='" + disTranTypeCd() + "', "
                + DIS_TRAN_CAT_CD_NAME + "=" + disTranCatCdImage() + " (" + disTranCatCd() + "), "
                + DIS_INT_RATE_NAME + "=" + disIntRate() + " (image '" + disIntRateImage() + "'), "
                + FILLER_NAME + "='" + filler() + "', charset=" + charset().name() + ']';
    }
}
