package com.vsergeychik.carddemo.account.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The account master record, {@code 01 ACCOUNT-RECORD} of copybook {@code app/cpy/CVACT01Y.cpy},
 * exactly <strong>300</strong> bytes wide.
 *
 * <p>This is the single Java type for that copybook. Eleven COBOL programs {@code COPY CVACT01Y} -
 * {@code CBACT01C}, {@code CBACT04C}, {@code CBSTM03A}, {@code CBTRN01C}, {@code CBTRN02C},
 * {@code COACTUPC}, {@code COACTVWC}, {@code COBIL00C}, {@code COCRDSLC}, {@code COCRDUPC} and
 * {@code COTRN02C} - so this type is shared by all of their Java counterparts and is never duplicated
 * per program. Every offset is a named constant here precisely so that no consumer ever re-derives
 * one.
 *
 * <h2>The declared layout</h2>
 *
 * <p>Offsets are absolute and 0-based. The copybook line is given for each field so that a reviewer
 * with {@code CVACT01Y.cpy} open can check this table line by line.
 *
 * <pre>
 *   offset  line  COBOL field               PICTURE      bytes  Java type
 *   ------  ----  ------------------------  -----------  -----  ---------------------
 *        0  L5    ACCT-ID                   9(11)           11  long
 *       11  L6    ACCT-ACTIVE-STATUS        X(01)            1  String
 *       12  L7    ACCT-CURR-BAL             S9(10)V99       12  BigDecimal, scale 2
 *       24  L8    ACCT-CREDIT-LIMIT         S9(10)V99       12  BigDecimal, scale 2
 *       36  L9    ACCT-CASH-CREDIT-LIMIT    S9(10)V99       12  BigDecimal, scale 2
 *       48  L10   ACCT-OPEN-DATE            X(10)           10  String
 *       58  L11   ACCT-EXPIRAION-DATE       X(10)           10  String   (sic - see below)
 *       68  L12   ACCT-REISSUE-DATE         X(10)           10  String
 *       78  L13   ACCT-CURR-CYC-CREDIT      S9(10)V99       12  BigDecimal, scale 2
 *       90  L14   ACCT-CURR-CYC-DEBIT       S9(10)V99       12  BigDecimal, scale 2
 *      102  L15   ACCT-ADDR-ZIP             X(10)           10  String   (no consumers)
 *      112  L16   ACCT-GROUP-ID             X(10)           10  String
 *      122  L17   FILLER                    X(178)         178  reserved span
 *   ------------------------------------------------------------ total 300
 * </pre>
 *
 * <p>Two independent proofs of the 300-byte width, either of which a reviewer can check in seconds:
 * the copybook's own header comment reads {@code Data-structure for account entity (RECLN 300)} at
 * {@code CVACT01Y.cpy:L2}; and the file description in {@code app/cbl/CBACT04C.cbl:L85-L87} declares
 * {@code 01 FD-ACCTFILE-REC.} as {@code 05 FD-ACCT-ID PIC 9(11).} plus
 * {@code 05 FD-ACCT-DATA PIC X(289).}, and 11 + 289 = 300.
 *
 * <h2>Why a signed monetary field is 12 bytes and not 13</h2>
 *
 * <p>{@code PIC S9(10)V99} occupies exactly {@code p + s} = 12 bytes. There is <strong>no separate
 * sign byte</strong>: the sign is overpunched into the trailing byte of the zoned {@code DISPLAY}
 * field. Reserving a sign byte for each of the five monetary fields would total 305 and the layout
 * self-check in {@link RecordLayout} would reject it immediately, naming that exact cause.
 *
 * <p>The overpunch is not an assumption - it is visible in the fixture. Every one of the 50 records in
 * {@code app/data/ASCII/acctdata.txt} is exactly 300 bytes, and the trailing byte of all five monetary
 * fields is the literal character <code>&#123;</code> in every record, which is the zoned encoding of
 * "positive, low-order digit 0". Record 1 therefore holds <code>00000001940&#123;</code> for
 * {@code ACCT-CURR-BAL}, denoting {@code +1940.00} at scale 2. The full table is
 * <code>&#123;</code>&nbsp;=&nbsp;+0, {@code A} to {@code I} = +1 to +9, <code>&#125;</code>&nbsp;=&nbsp;-0,
 * and {@code J} to {@code R} = -1 to -9. No negative value occurs in that fixture, but both signs are
 * carried faithfully because {@code COBIL00C:L234} computes
 * {@code ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} and can drive a balance below zero.
 *
 * <p>The zoned encoding itself lives in {@link FixedWidthCodec}, in one reviewable place. This class
 * declares geometry and delegates every byte-level decision.
 *
 * <h2>Deliberate divergences that must not be "corrected"</h2>
 *
 * <ul>
 *   <li><strong>{@code ACCT-EXPIRAION-DATE} is misspelled in the copybook, and stays misspelled
 *       here</strong> - see {@link #getAcctExpiraionDate()}. The field name is part of the record
 *       contract that field-for-field parity diffing compares against, so renaming it would produce a
 *       diff that is very hard to trace back to its cause. The misspelling is systemic rather than a
 *       local typo: {@code app/cpy/CVACT02Y.cpy:L9} declares the same one as
 *       {@code CARD-EXPIRAION-DATE}, and no correctly spelled {@code *-EXPIRATION-DATE} item exists
 *       anywhere in {@code app/cpy}, so there is no "correct" form to prefer.</li>
 *   <li><strong>{@code ACCT-ADDR-ZIP} has no consumers and is modelled anyway</strong> - see
 *       {@link #getAcctAddrZip()}. It is referenced by none of the 28 COBOL programs, is absent from
 *       {@code CBACT01C}'s display paragraph and absent from {@code COACTUPC}'s change-detection
 *       comparison. It nevertheless occupies bytes 102 to 111: dropping it would shorten the record to
 *       290 bytes and shift every following offset.</li>
 *   <li><strong>{@code FILLER} is a first-class span, not an implicit gap</strong> - see
 *       {@link #getFiller()}. Its 178 bytes are declared like any other field so that the record's
 *       width is provable rather than assumed.</li>
 *   <li><strong>No status enumeration exists</strong> - the copybook declares no {@code 88}-level
 *       condition names and no {@code VALUE} clauses at all, so none are invented here. See
 *       {@link #getAcctActiveStatus()}.</li>
 * </ul>
 *
 * <h2>Storage model</h2>
 *
 * <p>State is held as the record's 300 declared bytes rather than as separate Java fields, which is
 * what makes three required behaviours fall out rather than be simulated. A field's raw span,
 * overpunch included, is readable exactly as COBOL's {@code DISPLAY} of that field would emit it; a
 * record decoded from stored bytes re-encodes byte-identically because its {@code FILLER} and any
 * unmodelled residue are retained verbatim; and every accessor observes the field's declared width,
 * so a value that cannot fit is truncated at the moment it is stored rather than at some later
 * boundary.
 *
 * <p>Instances are <strong>mutable</strong>, matching the COBOL record area they stand for: the
 * interest calculator adds to a balance and zeroes both cycle amounts before rewriting the record
 * ({@code app/cbl/CBACT04C.cbl:L352-L356}). Instances are consequently <strong>not thread-safe</strong>
 * and must not be shared across threads without external synchronisation - which matches their usage,
 * as each batch step and each request handles its own record. This class holds no static mutable
 * state whatsoever: the layout, its descriptors and every constant are deeply immutable, so nothing
 * here is shared between records.
 *
 * <h2>Scope</h2>
 *
 * <p>This is a data contract, not a service. It carries no business decision, no persistence mapping
 * and no framework annotation: no dataset name appears here, and reaching the underlying dataset is
 * the repository's concern. Deliberately absent are any {@code java.time} conversion of the three date
 * fields, any comparison helper, and any validation the COBOL does not itself perform.
 *
 * @see FixedWidthCodec
 * @see CobolDecimal
 */
public final class AccountRecord {

    // =================================================================================================
    // Record geometry. Every constant below is transcribed from app/cpy/CVACT01Y.cpy and cites its
    // line, so the whole layout can be verified against the copybook without reading any logic.
    // =================================================================================================

    /**
     * The declared record width in bytes, from {@code CVACT01Y.cpy:L2}
     * ({@code RECLN 300}) and independently from {@code CBACT04C.cbl:L85-L87} (11 + 289).
     *
     * <p>Also the length {@code COBIL00C:L348} passes as {@code LENGTH OF ACCOUNT-RECORD}.
     */
    public static final int RECORD_LENGTH = 300;

    /**
     * {@code p} in the {@code PIC S9(p)V99} of all five monetary fields: 10 integer digit positions.
     *
     * <p>Every monetary store truncates to at most this many integer digits, keeping the low-order
     * digits, because no program in this codebase uses {@code ON SIZE ERROR}.
     */
    public static final int MONETARY_INTEGER_DIGITS = 10;

    /**
     * {@code s} in the {@code PIC S9(10)V(s)} of all five monetary fields: 2 fraction digit positions.
     *
     * <p>Delegated to {@link CobolDecimal#MONETARY_SCALE} rather than restated, so the system has one
     * definition of monetary scale.
     */
    public static final int MONETARY_SCALE = CobolDecimal.MONETARY_SCALE;

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L5    05  ACCT-ID                 PIC 9(11).
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name of the primary key, verbatim: {@code ACCT-ID}. */
    public static final String ACCT_ID_NAME = "ACCT-ID";

    /** Absolute 0-based offset of {@link #ACCT_ID_NAME}: byte 0. */
    public static final int ACCT_ID_OFFSET = 0;

    /** Declared width of {@link #ACCT_ID_NAME}: 11 bytes, from {@code PIC 9(11)}. */
    public static final int ACCT_ID_LENGTH = 11;

    /** Descriptor for {@link #ACCT_ID_NAME}: unsigned numeric, bytes 0 through 10. */
    public static final FieldSpan SPAN_ACCT_ID =
            FieldSpan.unsignedNumeric(ACCT_ID_NAME, ACCT_ID_OFFSET, ACCT_ID_LENGTH);

    /**
     * The width in bytes of the VSAM primary key, which is the width of {@link #ACCT_ID_NAME}: 11.
     *
     * <p>This is the value {@code app/cbl/COBIL00C.cbl:L350} supplies as
     * {@code KEYLENGTH (LENGTH OF ACCT-ID)} on its keyed read of the account dataset. It is named
     * here so that no repository or caller re-derives it.
     */
    public static final int KEY_LENGTH = ACCT_ID_LENGTH;

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L6    05  ACCT-ACTIVE-STATUS      PIC X(01).
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-ACTIVE-STATUS}. */
    public static final String ACCT_ACTIVE_STATUS_NAME = "ACCT-ACTIVE-STATUS";

    /** Absolute 0-based offset of {@link #ACCT_ACTIVE_STATUS_NAME}: byte 11. */
    public static final int ACCT_ACTIVE_STATUS_OFFSET = 11;

    /** Declared width of {@link #ACCT_ACTIVE_STATUS_NAME}: 1 byte, from {@code PIC X(01)}. */
    public static final int ACCT_ACTIVE_STATUS_LENGTH = 1;

    /** Descriptor for {@link #ACCT_ACTIVE_STATUS_NAME}: alphanumeric, byte 11. */
    public static final FieldSpan SPAN_ACCT_ACTIVE_STATUS = FieldSpan.alphanumeric(
            ACCT_ACTIVE_STATUS_NAME, ACCT_ACTIVE_STATUS_OFFSET, ACCT_ACTIVE_STATUS_LENGTH);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L7    05  ACCT-CURR-BAL           PIC S9(10)V99.
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-CURR-BAL}. */
    public static final String ACCT_CURR_BAL_NAME = "ACCT-CURR-BAL";

    /** Absolute 0-based offset of {@link #ACCT_CURR_BAL_NAME}: byte 12. */
    public static final int ACCT_CURR_BAL_OFFSET = 12;

    /**
     * Declared width of {@link #ACCT_CURR_BAL_NAME}: 12 bytes, being {@code p + s} = 10 + 2.
     *
     * <p>Twelve, not thirteen: the sign is overpunched into the trailing byte.
     */
    public static final int ACCT_CURR_BAL_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /** Descriptor for {@link #ACCT_CURR_BAL_NAME}: signed zoned scale 2, bytes 12 through 23. */
    public static final FieldSpan SPAN_ACCT_CURR_BAL = FieldSpan.signedScaled(
            ACCT_CURR_BAL_NAME, ACCT_CURR_BAL_OFFSET, MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L8    05  ACCT-CREDIT-LIMIT       PIC S9(10)V99.
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-CREDIT-LIMIT}. */
    public static final String ACCT_CREDIT_LIMIT_NAME = "ACCT-CREDIT-LIMIT";

    /** Absolute 0-based offset of {@link #ACCT_CREDIT_LIMIT_NAME}: byte 24. */
    public static final int ACCT_CREDIT_LIMIT_OFFSET = 24;

    /** Declared width of {@link #ACCT_CREDIT_LIMIT_NAME}: 12 bytes, being {@code p + s} = 10 + 2. */
    public static final int ACCT_CREDIT_LIMIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /** Descriptor for {@link #ACCT_CREDIT_LIMIT_NAME}: signed zoned scale 2, bytes 24 through 35. */
    public static final FieldSpan SPAN_ACCT_CREDIT_LIMIT = FieldSpan.signedScaled(
            ACCT_CREDIT_LIMIT_NAME, ACCT_CREDIT_LIMIT_OFFSET, MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L9    05  ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99.
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-CASH-CREDIT-LIMIT}. */
    public static final String ACCT_CASH_CREDIT_LIMIT_NAME = "ACCT-CASH-CREDIT-LIMIT";

    /** Absolute 0-based offset of {@link #ACCT_CASH_CREDIT_LIMIT_NAME}: byte 36. */
    public static final int ACCT_CASH_CREDIT_LIMIT_OFFSET = 36;

    /** Declared width of {@link #ACCT_CASH_CREDIT_LIMIT_NAME}: 12 bytes, {@code p + s} = 10 + 2. */
    public static final int ACCT_CASH_CREDIT_LIMIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /** Descriptor for {@link #ACCT_CASH_CREDIT_LIMIT_NAME}: signed zoned scale 2, bytes 36 to 47. */
    public static final FieldSpan SPAN_ACCT_CASH_CREDIT_LIMIT = FieldSpan.signedScaled(
            ACCT_CASH_CREDIT_LIMIT_NAME, ACCT_CASH_CREDIT_LIMIT_OFFSET,
            MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L10   05  ACCT-OPEN-DATE          PIC X(10).
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-OPEN-DATE}. */
    public static final String ACCT_OPEN_DATE_NAME = "ACCT-OPEN-DATE";

    /** Absolute 0-based offset of {@link #ACCT_OPEN_DATE_NAME}: byte 48. */
    public static final int ACCT_OPEN_DATE_OFFSET = 48;

    /** Declared width of {@link #ACCT_OPEN_DATE_NAME}: 10 bytes, from {@code PIC X(10)}. */
    public static final int ACCT_OPEN_DATE_LENGTH = 10;

    /** Descriptor for {@link #ACCT_OPEN_DATE_NAME}: alphanumeric, bytes 48 through 57. */
    public static final FieldSpan SPAN_ACCT_OPEN_DATE = FieldSpan.alphanumeric(
            ACCT_OPEN_DATE_NAME, ACCT_OPEN_DATE_OFFSET, ACCT_OPEN_DATE_LENGTH);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L11   05  ACCT-EXPIRAION-DATE     PIC X(10).
    //
    // The copybook spells it EXPIRAION. That spelling is reproduced here verbatim and deliberately;
    // see the class Javadoc and getAcctExpiraionDate() for why it must not be corrected.
    // ------------------------------------------------------------------------------------------------

    /**
     * Copybook item name, verbatim: {@code ACCT-EXPIRAION-DATE}.
     *
     * <p>The spelling is the copybook's, reproduced exactly. It is the name field-for-field parity
     * diffing matches on, so it is data rather than prose and is not corrected. See
     * {@link #getAcctExpiraionDate()}.
     */
    public static final String ACCT_EXPIRAION_DATE_NAME = "ACCT-EXPIRAION-DATE";

    /** Absolute 0-based offset of {@link #ACCT_EXPIRAION_DATE_NAME}: byte 58. */
    public static final int ACCT_EXPIRAION_DATE_OFFSET = 58;

    /** Declared width of {@link #ACCT_EXPIRAION_DATE_NAME}: 10 bytes, from {@code PIC X(10)}. */
    public static final int ACCT_EXPIRAION_DATE_LENGTH = 10;

    /** Descriptor for {@link #ACCT_EXPIRAION_DATE_NAME}: alphanumeric, bytes 58 through 67. */
    public static final FieldSpan SPAN_ACCT_EXPIRAION_DATE = FieldSpan.alphanumeric(
            ACCT_EXPIRAION_DATE_NAME, ACCT_EXPIRAION_DATE_OFFSET, ACCT_EXPIRAION_DATE_LENGTH);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L12   05  ACCT-REISSUE-DATE       PIC X(10).
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-REISSUE-DATE}. */
    public static final String ACCT_REISSUE_DATE_NAME = "ACCT-REISSUE-DATE";

    /** Absolute 0-based offset of {@link #ACCT_REISSUE_DATE_NAME}: byte 68. */
    public static final int ACCT_REISSUE_DATE_OFFSET = 68;

    /** Declared width of {@link #ACCT_REISSUE_DATE_NAME}: 10 bytes, from {@code PIC X(10)}. */
    public static final int ACCT_REISSUE_DATE_LENGTH = 10;

    /** Descriptor for {@link #ACCT_REISSUE_DATE_NAME}: alphanumeric, bytes 68 through 77. */
    public static final FieldSpan SPAN_ACCT_REISSUE_DATE = FieldSpan.alphanumeric(
            ACCT_REISSUE_DATE_NAME, ACCT_REISSUE_DATE_OFFSET, ACCT_REISSUE_DATE_LENGTH);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L13   05  ACCT-CURR-CYC-CREDIT    PIC S9(10)V99.
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-CURR-CYC-CREDIT}. */
    public static final String ACCT_CURR_CYC_CREDIT_NAME = "ACCT-CURR-CYC-CREDIT";

    /** Absolute 0-based offset of {@link #ACCT_CURR_CYC_CREDIT_NAME}: byte 78. */
    public static final int ACCT_CURR_CYC_CREDIT_OFFSET = 78;

    /** Declared width of {@link #ACCT_CURR_CYC_CREDIT_NAME}: 12 bytes, {@code p + s} = 10 + 2. */
    public static final int ACCT_CURR_CYC_CREDIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /** Descriptor for {@link #ACCT_CURR_CYC_CREDIT_NAME}: signed zoned scale 2, bytes 78 to 89. */
    public static final FieldSpan SPAN_ACCT_CURR_CYC_CREDIT = FieldSpan.signedScaled(
            ACCT_CURR_CYC_CREDIT_NAME, ACCT_CURR_CYC_CREDIT_OFFSET,
            MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L14   05  ACCT-CURR-CYC-DEBIT     PIC S9(10)V99.
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-CURR-CYC-DEBIT}. */
    public static final String ACCT_CURR_CYC_DEBIT_NAME = "ACCT-CURR-CYC-DEBIT";

    /** Absolute 0-based offset of {@link #ACCT_CURR_CYC_DEBIT_NAME}: byte 90. */
    public static final int ACCT_CURR_CYC_DEBIT_OFFSET = 90;

    /** Declared width of {@link #ACCT_CURR_CYC_DEBIT_NAME}: 12 bytes, {@code p + s} = 10 + 2. */
    public static final int ACCT_CURR_CYC_DEBIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /** Descriptor for {@link #ACCT_CURR_CYC_DEBIT_NAME}: signed zoned scale 2, bytes 90 to 101. */
    public static final FieldSpan SPAN_ACCT_CURR_CYC_DEBIT = FieldSpan.signedScaled(
            ACCT_CURR_CYC_DEBIT_NAME, ACCT_CURR_CYC_DEBIT_OFFSET,
            MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L15   05  ACCT-ADDR-ZIP           PIC X(10).
    //
    // Referenced by none of the 28 COBOL programs, and modelled regardless: it occupies bytes 102
    // through 111, so removing it would shift every following offset. See getAcctAddrZip().
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-ADDR-ZIP}. */
    public static final String ACCT_ADDR_ZIP_NAME = "ACCT-ADDR-ZIP";

    /** Absolute 0-based offset of {@link #ACCT_ADDR_ZIP_NAME}: byte 102. */
    public static final int ACCT_ADDR_ZIP_OFFSET = 102;

    /** Declared width of {@link #ACCT_ADDR_ZIP_NAME}: 10 bytes, from {@code PIC X(10)}. */
    public static final int ACCT_ADDR_ZIP_LENGTH = 10;

    /** Descriptor for {@link #ACCT_ADDR_ZIP_NAME}: alphanumeric, bytes 102 through 111. */
    public static final FieldSpan SPAN_ACCT_ADDR_ZIP = FieldSpan.alphanumeric(
            ACCT_ADDR_ZIP_NAME, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L16   05  ACCT-GROUP-ID           PIC X(10).
    // ------------------------------------------------------------------------------------------------

    /** Copybook item name, verbatim: {@code ACCT-GROUP-ID}. */
    public static final String ACCT_GROUP_ID_NAME = "ACCT-GROUP-ID";

    /** Absolute 0-based offset of {@link #ACCT_GROUP_ID_NAME}: byte 112. */
    public static final int ACCT_GROUP_ID_OFFSET = 112;

    /** Declared width of {@link #ACCT_GROUP_ID_NAME}: 10 bytes, from {@code PIC X(10)}. */
    public static final int ACCT_GROUP_ID_LENGTH = 10;

    /** Descriptor for {@link #ACCT_GROUP_ID_NAME}: alphanumeric, bytes 112 through 121. */
    public static final FieldSpan SPAN_ACCT_GROUP_ID = FieldSpan.alphanumeric(
            ACCT_GROUP_ID_NAME, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH);

    // ------------------------------------------------------------------------------------------------
    // CVACT01Y.cpy:L17   05  FILLER                  PIC X(178).
    //
    // Declared like any other span. Omitting it would make the record 122 bytes and break every
    // consumer; treating it as an implicit gap would make the 300-byte width unprovable.
    // ------------------------------------------------------------------------------------------------

    /** Absolute 0-based offset of the trailing {@code FILLER}: byte 122. */
    public static final int FILLER_OFFSET = 122;

    /** Declared width of the trailing {@code FILLER}: 178 bytes, from {@code PIC X(178)}. */
    public static final int FILLER_LENGTH = 178;

    /**
     * Descriptor for the trailing {@code FILLER}, bytes 122 through 299.
     *
     * <p>Declared with no {@code VALUE} literal, because the copybook declares none, so a freshly
     * allocated record initialises these 178 bytes to the charset's space byte - which is exactly
     * what every record in {@code app/data/ASCII/acctdata.txt} contains.
     */
    public static final FieldSpan SPAN_FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    // =================================================================================================
    // The layout. Constructing it runs FixedWidthRecord's self-check, which verifies that the thirteen
    // declared spans are contiguous from offset 0, that no name repeats, and that their widths sum to
    // exactly RECORD_LENGTH. A transcription error therefore fails at class initialisation, naming the
    // offending descriptor, rather than surfacing later as a byte that reads back wrong.
    // =================================================================================================

    /**
     * The complete layout of {@code 01 ACCOUNT-RECORD}: thirteen spans, in copybook declaration order,
     * totalling {@link #RECORD_LENGTH} bytes.
     *
     * <p>Exposed so that repositories, the parity harness and its field differ all address fields
     * through the one declared geometry instead of restating offsets. {@link RecordLayout} copies its
     * span list defensively and {@link FieldSpan} is a record of immutable components, so this
     * constant is deeply immutable and safe to share.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            SPAN_ACCT_ID,
            SPAN_ACCT_ACTIVE_STATUS,
            SPAN_ACCT_CURR_BAL,
            SPAN_ACCT_CREDIT_LIMIT,
            SPAN_ACCT_CASH_CREDIT_LIMIT,
            SPAN_ACCT_OPEN_DATE,
            SPAN_ACCT_EXPIRAION_DATE,
            SPAN_ACCT_REISSUE_DATE,
            SPAN_ACCT_CURR_CYC_CREDIT,
            SPAN_ACCT_CURR_CYC_DEBIT,
            SPAN_ACCT_ADDR_ZIP,
            SPAN_ACCT_GROUP_ID,
            SPAN_FILLER);

    // =================================================================================================
    // Instance state: the record's declared bytes, plus the charset-bound codec that reads and writes
    // them. Both are final; neither is shared with any other instance.
    // =================================================================================================

    /** The 300 declared bytes. Mutable content, immutable width and charset. */
    private final FixedWidthRecord record;

    /** The codec bound to {@link #record}'s charset; owns every byte-level encoding decision. */
    private final FixedWidthCodec codec;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Allocates an initialised, empty account record in the given code page.
     *
     * <p>Every span is initialised as the copybook implies: {@code PIC X} and {@code FILLER} spans to
     * spaces, {@code PIC 9} and {@code PIC S9} spans to zeros. The record is therefore
     * {@link #RECORD_LENGTH} bytes wide and internally consistent before a single field is set, and
     * its {@code FILLER} already matches what the fixtures contain.
     *
     * @param charset the code page the record's bytes are in - {@code US-ASCII} for the ASCII
     *                fixtures, an EBCDIC charset such as {@code IBM037} for mainframe data. Required:
     *                fixed-width mainframe data is bytes in a specific code page, so the platform
     *                default is never assumed
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *                                  character and the space to exactly one byte, since a zoned
     *                                  {@code DISPLAY} field of {@code n} digits occupies exactly
     *                                  {@code n} bytes
     */
    public AccountRecord(Charset charset) {
        this.codec = new FixedWidthCodec(charset);
        this.record = codec.newRecord(LAYOUT);
    }

    /**
     * Wraps stored bytes, for reading and updating an existing record.
     *
     * <p>The bytes are copied, so the caller's array and this record cannot alter one another. Every
     * byte is retained verbatim, {@code FILLER} included, which is what makes decoding and re-encoding
     * a stored record byte-identical.
     *
     * @param image   exactly {@link #RECORD_LENGTH} bytes as stored
     * @param charset the code page {@code image} is in
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #RECORD_LENGTH} bytes
     *                                  long, or {@code charset} is unusable for zoned data
     */
    private AccountRecord(byte[] image, Charset charset) {
        this.codec = new FixedWidthCodec(charset);
        this.record = codec.wrap(image, LAYOUT);
    }

    /**
     * Decodes a stored 300-byte account record.
     *
     * <p>This is the read side of {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD}
     * ({@code app/cbl/CBACT01C.cbl:L93}) and of the keyed read at
     * {@code app/cbl/COBIL00C.cbl:L345-L352}.
     *
     * <p>A short row must be widened before it reaches this method rather than tolerated here, because
     * silently accepting one would let every field offset drift; {@link FixedWidthCodec} provides
     * {@code padToDeclaredWidth} for the one dataset in this system whose fixture omits a trailing
     * {@code FILLER}, which is not this one - all 50 rows of {@code app/data/ASCII/acctdata.txt} are
     * already exactly 300 bytes.
     *
     * @param image   exactly {@link #RECORD_LENGTH} bytes as stored
     * @param charset the code page {@code image} is in
     * @return a record over a private copy of {@code image}
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #RECORD_LENGTH} bytes
     */
    public static AccountRecord decode(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "Stored bytes are required to decode an account record; use "
                + "new AccountRecord(Charset) to allocate an empty one");
        Objects.requireNonNull(charset, "A charset is required to decode an account record: the "
                + "stored bytes are in a specific code page, which is never assumed");
        return new AccountRecord(image, charset);
    }

    /**
     * Decodes a stored account record given as text, for the character-oriented ASCII fixtures.
     *
     * <p>The text is encoded with {@code charset} and then decoded exactly as
     * {@link #decode(byte[], Charset)} does, so a row from {@code app/data/ASCII/acctdata.txt} can be
     * read as the line of text it is. It must be exactly {@link #RECORD_LENGTH} characters, and
     * because the charset is required to be single-byte for zoned data, that is also
     * {@link #RECORD_LENGTH} bytes.
     *
     * @param image   exactly {@link #RECORD_LENGTH} characters as stored, trailing spaces included
     * @param charset the code page to encode {@code image} in
     * @return a record over the encoded bytes
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} does not encode to exactly
     *                                  {@link #RECORD_LENGTH} bytes
     */
    public static AccountRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "Stored text is required to decode an account record");
        Objects.requireNonNull(charset, "A charset is required to decode an account record: the "
                + "stored characters become bytes in a specific code page, which is never assumed");
        return new AccountRecord(image.getBytes(charset), charset);
    }


    // =================================================================================================
    // The record as a whole. This is the group item: COBOL addresses ACCOUNT-RECORD itself as often as
    // it addresses the fields inside it, and the 300-byte image is what parity is judged on.
    // =================================================================================================

    /**
     * The declared record width in bytes, always {@link #RECORD_LENGTH}.
     *
     * @return 300
     */
    public int recordLength() {
        return record.recordLength();
    }

    /**
     * The code page this record's bytes are in, as supplied at construction.
     *
     * @return the charset, never {@code null}
     */
    public Charset charset() {
        return record.charset();
    }

    /**
     * The complete 300-byte image, ready to be written back to the dataset.
     *
     * <p>This is the write side of {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}
     * ({@code app/cbl/CBACT04C.cbl:L356}), and it is the authoritative source for a parity fingerprint:
     * comparison is performed on these bytes, never on {@link #toString()}.
     *
     * <p>A record decoded with {@link #decode(byte[], Charset)} and re-encoded here returns the
     * original bytes exactly, because nothing is normalised on the way in and {@code FILLER} is
     * retained verbatim.
     *
     * <p>A copy is returned, so mutating it cannot alter this record.
     *
     * @return a copy of all {@link #RECORD_LENGTH} bytes
     */
    public byte[] toByteArray() {
        return record.toByteArray();
    }

    /**
     * The complete record rendered as its {@link #RECORD_LENGTH} characters.
     *
     * <p>This is {@code DISPLAY ACCOUNT-RECORD} ({@code app/cbl/CBACT01C.cbl:L78}), which emits the
     * group item's stored bytes as they are: zoned monetary fields appear with their sign overpunch,
     * {@code ACCT-ID} appears zero-filled to 11 digits, and the 178 {@code FILLER} bytes appear as
     * trailing spaces. Nothing is trimmed, reformatted or elided.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String toFixedWidthString() {
        return record.readString(0, RECORD_LENGTH);
    }

    // =================================================================================================
    // Raw span access. COBOL's DISPLAY of an individual field emits that field's STORED BYTES, which
    // for a signed zoned field includes the sign overpunch: ACCT-CURR-BAL renders as 00000001940{ and
    // not as 1940.00. AccountBalanceJob fingerprints exactly those characters, so every field's raw
    // span is readable alongside its typed value.
    // =================================================================================================

    /**
     * Reads any declared span as its stored characters, without interpretation.
     *
     * <p>Exactly {@code field.length()} characters are returned: nothing is trimmed, and a zoned
     * numeric span retains its sign overpunch.
     *
     * @param field one of this class's {@code SPAN_} descriptors
     * @return the span's stored characters
     * @throws NullPointerException      if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if {@code field} does not lie within this record, which can
     *                                   only happen if a descriptor from another record type is passed
     */
    public String raw(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required; pass one of AccountRecord's "
                + "SPAN_ constants so the offset comes from the declared layout");
        return record.readSpan(field);
    }

    /**
     * Reads any declared span as its stored bytes.
     *
     * @param field one of this class's {@code SPAN_} descriptors
     * @return a copy of the span's stored bytes, exactly {@code field.length()} of them
     * @throws NullPointerException      if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if {@code field} does not lie within this record
     */
    public byte[] rawBytes(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required; pass one of AccountRecord's "
                + "SPAN_ constants so the offset comes from the declared layout");
        return record.readSpanBytes(field);
    }

    /**
     * {@code ACCT-ID} as stored: 11 zero-filled digits, for example {@code 00000000001}.
     *
     * <p>This is what {@code DISPLAY 'ACCT-ID :' ACCT-ID} emits at
     * {@code app/cbl/CBACT01C.cbl:L119}, and it is identical to {@link #keyImage()}.
     *
     * @return exactly {@link #ACCT_ID_LENGTH} characters
     */
    public String rawAcctId() {
        return raw(SPAN_ACCT_ID);
    }

    /**
     * {@code ACCT-ACTIVE-STATUS} as stored: 1 character.
     *
     * @return exactly {@link #ACCT_ACTIVE_STATUS_LENGTH} character
     */
    public String rawAcctActiveStatus() {
        return raw(SPAN_ACCT_ACTIVE_STATUS);
    }

    /**
     * {@code ACCT-CURR-BAL} as stored: 12 characters including the sign overpunch, for example
     * <code>00000001940&#123;</code> for {@code +1940.00}.
     *
     * @return exactly {@link #ACCT_CURR_BAL_LENGTH} characters
     */
    public String rawAcctCurrBal() {
        return raw(SPAN_ACCT_CURR_BAL);
    }

    /**
     * {@code ACCT-CREDIT-LIMIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CREDIT_LIMIT_LENGTH} characters
     */
    public String rawAcctCreditLimit() {
        return raw(SPAN_ACCT_CREDIT_LIMIT);
    }

    /**
     * {@code ACCT-CASH-CREDIT-LIMIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CASH_CREDIT_LIMIT_LENGTH} characters
     */
    public String rawAcctCashCreditLimit() {
        return raw(SPAN_ACCT_CASH_CREDIT_LIMIT);
    }

    /**
     * {@code ACCT-OPEN-DATE} as stored: 10 characters.
     *
     * @return exactly {@link #ACCT_OPEN_DATE_LENGTH} characters
     */
    public String rawAcctOpenDate() {
        return raw(SPAN_ACCT_OPEN_DATE);
    }

    /**
     * {@code ACCT-EXPIRAION-DATE} as stored: 10 characters. Spelling per the copybook.
     *
     * @return exactly {@link #ACCT_EXPIRAION_DATE_LENGTH} characters
     */
    public String rawAcctExpiraionDate() {
        return raw(SPAN_ACCT_EXPIRAION_DATE);
    }

    /**
     * {@code ACCT-REISSUE-DATE} as stored: 10 characters.
     *
     * @return exactly {@link #ACCT_REISSUE_DATE_LENGTH} characters
     */
    public String rawAcctReissueDate() {
        return raw(SPAN_ACCT_REISSUE_DATE);
    }

    /**
     * {@code ACCT-CURR-CYC-CREDIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CURR_CYC_CREDIT_LENGTH} characters
     */
    public String rawAcctCurrCycCredit() {
        return raw(SPAN_ACCT_CURR_CYC_CREDIT);
    }

    /**
     * {@code ACCT-CURR-CYC-DEBIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CURR_CYC_DEBIT_LENGTH} characters
     */
    public String rawAcctCurrCycDebit() {
        return raw(SPAN_ACCT_CURR_CYC_DEBIT);
    }

    /**
     * {@code ACCT-ADDR-ZIP} as stored: 10 characters.
     *
     * <p>No COBOL program reads this field, and {@code CBACT01C}'s display paragraph omits it. It is
     * exposed for completeness of the record contract and for parity diffing, which compares every
     * declared field rather than only the consumed ones.
     *
     * @return exactly {@link #ACCT_ADDR_ZIP_LENGTH} characters
     */
    public String rawAcctAddrZip() {
        return raw(SPAN_ACCT_ADDR_ZIP);
    }

    /**
     * {@code ACCT-GROUP-ID} as stored: 10 characters, right-space-padded.
     *
     * @return exactly {@link #ACCT_GROUP_ID_LENGTH} characters
     */
    public String rawAcctGroupId() {
        return raw(SPAN_ACCT_GROUP_ID);
    }

    // =================================================================================================
    // Typed field access. Reads decode the stored span; writes encode into it at the field's declared
    // width, so a value that does not fit is truncated where COBOL truncates it.
    // =================================================================================================

    /**
     * {@code ACCT-ID}, the primary key, as a number.
     *
     * <p>{@code PIC 9(11)} is an unsigned integer of 11 digits, which fits a {@code long} exactly.
     *
     * @return the account identifier
     */
    public long getAcctId() {
        return codec.readPic9(record, SPAN_ACCT_ID);
    }

    /**
     * Stores {@code ACCT-ID}, zero-filling to 11 digits.
     *
     * <p>A value with more than 11 digits is truncated on the <strong>left</strong>, which is the
     * COBOL rule for a numeric receiver and the opposite of the rule for character data.
     *
     * @param acctId the account identifier; must not be negative, since {@code PIC 9} declares no
     *               sign position and therefore has no representation for one
     * @throws IllegalArgumentException if {@code acctId} is negative
     */
    public void setAcctId(long acctId) {
        codec.writePic9(record, SPAN_ACCT_ID, acctId);
    }

    /**
     * {@code ACCT-ACTIVE-STATUS} as stored, untrimmed.
     *
     * <p>Deliberately a raw {@code PIC X(01)} character and not an enumeration or a boolean. The
     * copybook declares no {@code 88}-level condition names and no {@code VALUE} clauses, so there is
     * no set of legal values to enumerate, and the programs compare and move the character directly -
     * {@code IF ACCT-ACTIVE-STATUS EQUAL ACUP-OLD-ACTIVE-STATUS} at
     * {@code app/cbl/COACTUPC.cbl:L4115} and {@code MOVE ACCT-ACTIVE-STATUS TO ACSTTUSO} at
     * {@code app/cbl/COACTVWC.cbl:L473}. Introducing an enumeration would reject values the COBOL
     * accepts.
     *
     * @return exactly 1 character, a space in a freshly allocated record
     */
    public String getAcctActiveStatus() {
        return codec.readPicX(record, SPAN_ACCT_ACTIVE_STATUS);
    }

    /**
     * Stores {@code ACCT-ACTIVE-STATUS} into its 1-character span.
     *
     * <p>Longer input is truncated on the <strong>right</strong> and shorter input is space-padded on
     * the right, per the COBOL alphanumeric move rule.
     *
     * @param acctActiveStatus the status character; may be empty, which stores a space
     * @throws NullPointerException if {@code acctActiveStatus} is {@code null}
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        writePicX(SPAN_ACCT_ACTIVE_STATUS, acctActiveStatus, "acctActiveStatus");
    }

    /**
     * {@code ACCT-CURR-BAL}, the current balance, at scale exactly 2.
     *
     * <p>Compare it with {@link BigDecimal#compareTo(BigDecimal)} and never with
     * {@link BigDecimal#equals(Object)}: {@code equals} is scale-sensitive, so a scale-2 zero is not
     * {@code equals} to {@link BigDecimal#ZERO} and a test written that way would invert the zero
     * check at {@code app/cbl/COBIL00C.cbl:L198} ({@code IF ACCT-CURR-BAL <= ZEROS}).
     *
     * @return the balance, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCurrBal() {
        return codec.readMonetary(record, SPAN_ACCT_CURR_BAL);
    }

    /**
     * Stores {@code ACCT-CURR-BAL} at its declared {@code PIC S9(10)V99}.
     *
     * <p>Receives the result of {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}
     * ({@code app/cbl/CBACT04C.cbl:L352}), {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL}
     * ({@code app/cbl/CBTRN02C.cbl:L547}) and
     * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} ({@code app/cbl/COBIL00C.cbl:L234}),
     * the last of which can drive the balance negative.
     *
     * @param acctCurrBal the balance to store, of any scale
     * @throws NullPointerException if {@code acctCurrBal} is {@code null}
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        writeMonetary(SPAN_ACCT_CURR_BAL, acctCurrBal, "acctCurrBal");
    }

    /**
     * {@code ACCT-CREDIT-LIMIT} at scale exactly 2.
     *
     * <p>Read by {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} at
     * {@code app/cbl/CBTRN02C.cbl:L407}, the over-limit stage of the validation cascade.
     *
     * @return the credit limit, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCreditLimit() {
        return codec.readMonetary(record, SPAN_ACCT_CREDIT_LIMIT);
    }

    /**
     * Stores {@code ACCT-CREDIT-LIMIT} at its declared {@code PIC S9(10)V99}.
     *
     * @param acctCreditLimit the credit limit to store, of any scale
     * @throws NullPointerException if {@code acctCreditLimit} is {@code null}
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        writeMonetary(SPAN_ACCT_CREDIT_LIMIT, acctCreditLimit, "acctCreditLimit");
    }

    /**
     * {@code ACCT-CASH-CREDIT-LIMIT} at scale exactly 2.
     *
     * @return the cash credit limit, with {@link BigDecimal#scale()} of exactly
     *         {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCashCreditLimit() {
        return codec.readMonetary(record, SPAN_ACCT_CASH_CREDIT_LIMIT);
    }

    /**
     * Stores {@code ACCT-CASH-CREDIT-LIMIT} at its declared {@code PIC S9(10)V99}.
     *
     * @param acctCashCreditLimit the cash credit limit to store, of any scale
     * @throws NullPointerException if {@code acctCashCreditLimit} is {@code null}
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        writeMonetary(SPAN_ACCT_CASH_CREDIT_LIMIT, acctCashCreditLimit, "acctCashCreditLimit");
    }


    /**
     * {@code ACCT-OPEN-DATE} as stored, untrimmed: 10 characters in {@code YYYY-MM-DD} shape.
     *
     * <p>A raw {@code String} rather than a {@code java.time} type, for the reasons given on
     * {@link #getAcctExpiraionDate()}. Use {@link #getAcctOpenDateYear()},
     * {@link #getAcctOpenDateMonth()} and {@link #getAcctOpenDateDay()} for its components.
     *
     * @return exactly {@link #ACCT_OPEN_DATE_LENGTH} characters, all spaces in a fresh record
     */
    public String getAcctOpenDate() {
        return codec.readPicX(record, SPAN_ACCT_OPEN_DATE);
    }

    /**
     * Stores {@code ACCT-OPEN-DATE}, space-padding or right-truncating to 10 characters.
     *
     * @param acctOpenDate the date text; may be blank or partial, which the COBOL date-edit engine
     *                     relies on being storable
     * @throws NullPointerException if {@code acctOpenDate} is {@code null}
     */
    public void setAcctOpenDate(String acctOpenDate) {
        writePicX(SPAN_ACCT_OPEN_DATE, acctOpenDate, "acctOpenDate");
    }

    /**
     * {@code ACCT-EXPIRAION-DATE} as stored, untrimmed: 10 characters in {@code YYYY-MM-DD} shape.
     *
     * <p><strong>The name reproduces the copybook's misspelling deliberately.</strong>
     * {@code app/cpy/CVACT01Y.cpy:L11} declares {@code ACCT-EXPIRAION-DATE}, and that name is part of
     * the record contract this migration must preserve field-for-field. The misspelling is systemic
     * rather than a local slip: {@code app/cpy/CVACT02Y.cpy:L9} carries the identical
     * {@code CARD-EXPIRAION-DATE}, and no correctly spelled {@code *-EXPIRATION-DATE} item exists
     * anywhere in {@code app/cpy}. Renaming this to {@code acctExpirationDate} would break field-level
     * diffing and produce a parity failure that is expensive to trace, so it must be left alone.
     *
     * <p><strong>The type is deliberately {@code String} and not a date.</strong> Two independent
     * reasons: {@code app/cbl/CBTRN02C.cbl:L414} evaluates
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, a lexicographic comparison of
     * characters against the first ten characters of a timestamp, whose semantics a date object would
     * change; and the field must be able to hold blank or invalid content, which {@code COACTUPC}'s
     * date-edit engine depends on and which no date type permits.
     *
     * @return exactly {@link #ACCT_EXPIRAION_DATE_LENGTH} characters, all spaces in a fresh record
     */
    public String getAcctExpiraionDate() {
        return codec.readPicX(record, SPAN_ACCT_EXPIRAION_DATE);
    }

    /**
     * Stores {@code ACCT-EXPIRAION-DATE}, space-padding or right-truncating to 10 characters.
     *
     * <p>Spelling per the copybook; see {@link #getAcctExpiraionDate()}.
     *
     * @param acctExpiraionDate the date text; may be blank or partial
     * @throws NullPointerException if {@code acctExpiraionDate} is {@code null}
     */
    public void setAcctExpiraionDate(String acctExpiraionDate) {
        writePicX(SPAN_ACCT_EXPIRAION_DATE, acctExpiraionDate, "acctExpiraionDate");
    }

    /**
     * {@code ACCT-REISSUE-DATE} as stored, untrimmed: 10 characters in {@code YYYY-MM-DD} shape.
     *
     * @return exactly {@link #ACCT_REISSUE_DATE_LENGTH} characters, all spaces in a fresh record
     */
    public String getAcctReissueDate() {
        return codec.readPicX(record, SPAN_ACCT_REISSUE_DATE);
    }

    /**
     * Stores {@code ACCT-REISSUE-DATE}, space-padding or right-truncating to 10 characters.
     *
     * @param acctReissueDate the date text; may be blank or partial
     * @throws NullPointerException if {@code acctReissueDate} is {@code null}
     */
    public void setAcctReissueDate(String acctReissueDate) {
        writePicX(SPAN_ACCT_REISSUE_DATE, acctReissueDate, "acctReissueDate");
    }

    /**
     * {@code ACCT-CURR-CYC-CREDIT} at scale exactly 2.
     *
     * <p>Read by {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
     * DALYTRAN-AMT} at {@code app/cbl/CBTRN02C.cbl:L403-L405}.
     *
     * @return the cycle credit, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCurrCycCredit() {
        return codec.readMonetary(record, SPAN_ACCT_CURR_CYC_CREDIT);
    }

    /**
     * Stores {@code ACCT-CURR-CYC-CREDIT} at its declared {@code PIC S9(10)V99}.
     *
     * <p>To reproduce {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} ({@code app/cbl/CBACT04C.cbl:L353}) call
     * {@link #zeroAcctCurrCycCredit()}, which stores a scale-2 zero rather than
     * {@link BigDecimal#ZERO}.
     *
     * @param acctCurrCycCredit the cycle credit to store, of any scale
     * @throws NullPointerException if {@code acctCurrCycCredit} is {@code null}
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        writeMonetary(SPAN_ACCT_CURR_CYC_CREDIT, acctCurrCycCredit, "acctCurrCycCredit");
    }

    /**
     * {@code ACCT-CURR-CYC-DEBIT} at scale exactly 2.
     *
     * @return the cycle debit, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCurrCycDebit() {
        return codec.readMonetary(record, SPAN_ACCT_CURR_CYC_DEBIT);
    }

    /**
     * Stores {@code ACCT-CURR-CYC-DEBIT} at its declared {@code PIC S9(10)V99}.
     *
     * <p>To reproduce {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} ({@code app/cbl/CBACT04C.cbl:L354}) call
     * {@link #zeroAcctCurrCycDebit()}.
     *
     * @param acctCurrCycDebit the cycle debit to store, of any scale
     * @throws NullPointerException if {@code acctCurrCycDebit} is {@code null}
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        writeMonetary(SPAN_ACCT_CURR_CYC_DEBIT, acctCurrCycDebit, "acctCurrCycDebit");
    }

    /**
     * Reproduces {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} ({@code app/cbl/CBACT04C.cbl:L353}).
     *
     * <p>Stores zero <em>at scale 2</em>, so the span serialises as
     * <code>00000000000&#123;</code>. {@link BigDecimal#ZERO} has scale 0 and would be a defect here,
     * which is exactly why this is a named operation rather than a call the caller has to remember to
     * scale.
     */
    public void zeroAcctCurrCycCredit() {
        writeMonetary(SPAN_ACCT_CURR_CYC_CREDIT, CobolDecimal.monetaryZero(), "acctCurrCycCredit");
    }

    /**
     * Reproduces {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} ({@code app/cbl/CBACT04C.cbl:L354}).
     *
     * <p>Stores zero at scale 2; see {@link #zeroAcctCurrCycCredit()}.
     */
    public void zeroAcctCurrCycDebit() {
        writeMonetary(SPAN_ACCT_CURR_CYC_DEBIT, CobolDecimal.monetaryZero(), "acctCurrCycDebit");
    }

    /**
     * {@code ACCT-ADDR-ZIP} as stored, untrimmed: 10 characters.
     *
     * <p><strong>No COBOL program references this field.</strong> A search of all 28 programs finds no
     * use of {@code ACCT-ADDR-ZIP}: {@code CBACT01C}'s display paragraph omits it, and
     * {@code COACTUPC}'s change-detection comparison omits it too. It is modelled regardless, because
     * it occupies bytes 102 through 111 and removing it would shorten the record to 290 bytes and
     * shift {@code ACCT-GROUP-ID} and {@code FILLER}. Preserving behaviour includes preserving what is
     * unused, so it must not be deleted as dead.
     *
     * <p>Its content in {@code app/data/ASCII/acctdata.txt} is {@code A000000000} in all 50 records,
     * which is also a disclosure-group identifier value and so looks misplaced. It is not: the
     * copybook is authoritative, and the neighbouring blank {@code ACCT-GROUP-ID} is behaviourally
     * consistent, because {@code app/cbl/CBACT04C.cbl:L210} moves that blank group id into the
     * disclosure key, the keyed read misses, and {@code L437} substitutes {@code 'DEFAULT'} - for which
     * {@code discgrp.txt} does carry rows. The offsets are correct; the fixture is simply populated
     * that way.
     *
     * @return exactly {@link #ACCT_ADDR_ZIP_LENGTH} characters
     */
    public String getAcctAddrZip() {
        return codec.readPicX(record, SPAN_ACCT_ADDR_ZIP);
    }

    /**
     * Stores {@code ACCT-ADDR-ZIP}, space-padding or right-truncating to 10 characters.
     *
     * @param acctAddrZip the value to store
     * @throws NullPointerException if {@code acctAddrZip} is {@code null}
     */
    public void setAcctAddrZip(String acctAddrZip) {
        writePicX(SPAN_ACCT_ADDR_ZIP, acctAddrZip, "acctAddrZip");
    }

    /**
     * {@code ACCT-GROUP-ID} as stored, untrimmed and right-space-padded to 10 characters.
     *
     * <p>Deliberately not trimmed. {@code app/cbl/CBACT04C.cbl:L210} moves this field whole into the
     * disclosure-group key, so its trailing spaces are part of the key it builds, and
     * {@code app/cbl/COACTUPC.cbl:L4139} folds it with {@code FUNCTION LOWER-CASE} for comparison
     * without trimming it. A blank group id therefore reads as ten spaces, not as an empty string.
     *
     * @return exactly {@link #ACCT_GROUP_ID_LENGTH} characters
     */
    public String getAcctGroupId() {
        return codec.readPicX(record, SPAN_ACCT_GROUP_ID);
    }

    /**
     * Stores {@code ACCT-GROUP-ID}, space-padding or right-truncating to 10 characters.
     *
     * @param acctGroupId the group identifier
     * @throws NullPointerException if {@code acctGroupId} is {@code null}
     */
    public void setAcctGroupId(String acctGroupId) {
        writePicX(SPAN_ACCT_GROUP_ID, acctGroupId, "acctGroupId");
    }

    /**
     * The trailing {@code FILLER} as stored: 178 characters.
     *
     * <p>Spaces in a freshly allocated record, which matches every record in
     * {@code app/data/ASCII/acctdata.txt}; in a decoded record, whatever the stored bytes hold, so
     * that re-encoding is byte-identical. There is deliberately no setter: {@code FILLER} is reserved
     * storage that no program assigns, and its presence at full width is what makes the record's
     * 300-byte total provable.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String getFiller() {
        return raw(SPAN_FILLER);
    }

    // =================================================================================================
    // Date components. COBOL reference modification is ONE-based: ACCT-OPEN-DATE(1:4) is the year,
    // (6:2) the month and (9:2) the day of a YYYY-MM-DD value. COACTUPC applies exactly these three
    // slices to all three dates, twice - when saving the pre-change image at L3833-L3843 and again in
    // the change-detection comparison at L4126-L4136 - which is eighteen sites in one program alone.
    // The one-based to zero-based conversion therefore lives in exactly one place, referenceModify,
    // rather than being re-derived at each of them: an off-by-one here is the single most likely
    // defect in this translation.
    // =================================================================================================

    /** One-based start of the year within a {@code YYYY-MM-DD} value, as COBOL writes it: {@code 1}. */
    public static final int YEAR_START = 1;

    /** Width of the year component: 4 characters, the {@code 4} in {@code (1:4)}. */
    public static final int YEAR_LENGTH = 4;

    /** One-based start of the month within a {@code YYYY-MM-DD} value: {@code 6}, skipping the dash. */
    public static final int MONTH_START = 6;

    /** Width of the month component: 2 characters, the {@code 2} in {@code (6:2)}. */
    public static final int MONTH_LENGTH = 2;

    /** One-based start of the day within a {@code YYYY-MM-DD} value: {@code 9}. */
    public static final int DAY_START = 9;

    /** Width of the day component: 2 characters, the {@code 2} in {@code (9:2)}. */
    public static final int DAY_LENGTH = 2;

    /**
     * Applies COBOL reference modification to a value: {@code value(oneBasedStart:length)}.
     *
     * <p>This is the only place the one-based to zero-based conversion is performed. A COBOL
     * {@code (1:4)} is Java {@code substring(0, 4)}, a {@code (6:2)} is {@code substring(5, 7)} and a
     * {@code (9:2)} is {@code substring(8, 10)}.
     *
     * <p>Short and {@code null} content is space-padded to the requested width rather than throwing.
     * COBOL reference modification of a fixed-width {@code PIC X(10)} field always has ten characters
     * available, so within this class the request is always satisfiable; the padding exists because
     * this helper is also the safe way to slice a value that has not yet been stored into a span, and
     * a {@code StringIndexOutOfBoundsException} would be a poor way to learn that a date arrived
     * blank.
     *
     * @param value         the sending value; {@code null} is treated as entirely blank
     * @param oneBasedStart the one-based character position to start at, as written in the COBOL; at
     *                      least 1
     * @param length        the number of characters to take; at least 1
     * @return exactly {@code length} characters, space-padded on the right if {@code value} does not
     *         reach that far
     * @throws IllegalArgumentException if {@code oneBasedStart} is below 1 or {@code length} is below 1
     */
    public static String referenceModify(String value, int oneBasedStart, int length) {
        if (oneBasedStart < 1) {
            throw new IllegalArgumentException("Reference modification starts at position "
                    + oneBasedStart + "; COBOL positions are one-based, so the first character is 1");
        }
        if (length < 1) {
            throw new IllegalArgumentException("Reference modification requests " + length
                    + " character(s); a reference-modified span covers at least 1");
        }
        // The single one-based to zero-based conversion in this class.
        int from = oneBasedStart - 1;
        if (value == null || from >= value.length()) {
            return " ".repeat(length);
        }
        int to = Math.min(value.length(), from + length);
        String slice = value.substring(from, to);
        if (slice.length() < length) {
            return slice + " ".repeat(length - slice.length());
        }
        return slice;
    }

    /**
     * The year component of {@code ACCT-OPEN-DATE}, reproducing {@code ACCT-OPEN-DATE(1:4)}
     * ({@code app/cbl/COACTUPC.cbl:L3831}).
     *
     * @return 4 characters; spaces where the date is blank
     */
    public String getAcctOpenDateYear() {
        return referenceModify(getAcctOpenDate(), YEAR_START, YEAR_LENGTH);
    }

    /**
     * The month component of {@code ACCT-OPEN-DATE}, reproducing {@code ACCT-OPEN-DATE(6:2)}
     * ({@code app/cbl/COACTUPC.cbl:L3833}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctOpenDateMonth() {
        return referenceModify(getAcctOpenDate(), MONTH_START, MONTH_LENGTH);
    }

    /**
     * The day component of {@code ACCT-OPEN-DATE}, reproducing {@code ACCT-OPEN-DATE(9:2)}
     * ({@code app/cbl/COACTUPC.cbl:L3834}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctOpenDateDay() {
        return referenceModify(getAcctOpenDate(), DAY_START, DAY_LENGTH);
    }

    /**
     * The year component of {@code ACCT-EXPIRAION-DATE}, reproducing
     * {@code ACCT-EXPIRAION-DATE(1:4)} ({@code app/cbl/COACTUPC.cbl:L3838}).
     *
     * @return 4 characters; spaces where the date is blank
     */
    public String getAcctExpiraionDateYear() {
        return referenceModify(getAcctExpiraionDate(), YEAR_START, YEAR_LENGTH);
    }

    /**
     * The month component of {@code ACCT-EXPIRAION-DATE}, reproducing
     * {@code ACCT-EXPIRAION-DATE(6:2)} ({@code app/cbl/COACTUPC.cbl:L3839}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctExpiraionDateMonth() {
        return referenceModify(getAcctExpiraionDate(), MONTH_START, MONTH_LENGTH);
    }

    /**
     * The day component of {@code ACCT-EXPIRAION-DATE}, reproducing
     * {@code ACCT-EXPIRAION-DATE(9:2)} ({@code app/cbl/COACTUPC.cbl:L3840}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctExpiraionDateDay() {
        return referenceModify(getAcctExpiraionDate(), DAY_START, DAY_LENGTH);
    }

    /**
     * The year component of {@code ACCT-REISSUE-DATE}, reproducing {@code ACCT-REISSUE-DATE(1:4)}
     * ({@code app/cbl/COACTUPC.cbl:L3843}).
     *
     * @return 4 characters; spaces where the date is blank
     */
    public String getAcctReissueDateYear() {
        return referenceModify(getAcctReissueDate(), YEAR_START, YEAR_LENGTH);
    }

    /**
     * The month component of {@code ACCT-REISSUE-DATE}, reproducing {@code ACCT-REISSUE-DATE(6:2)}.
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctReissueDateMonth() {
        return referenceModify(getAcctReissueDate(), MONTH_START, MONTH_LENGTH);
    }

    /**
     * The day component of {@code ACCT-REISSUE-DATE}, reproducing {@code ACCT-REISSUE-DATE(9:2)}.
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctReissueDateDay() {
        return referenceModify(getAcctReissueDate(), DAY_START, DAY_LENGTH);
    }


    // =================================================================================================
    // Key exposure. COBIL00C:L349-L350 reads the account dataset with RIDFLD (ACCT-ID) and
    // KEYLENGTH (LENGTH OF ACCT-ID), so the key is the 11-byte zero-filled ACCT-ID image. Both the
    // image and its width are named here so that no repository re-derives either.
    // =================================================================================================

    /**
     * This record's primary key as its stored 11-character image, zero-filled.
     *
     * <p>For {@code ACCT-ID} 1 the image is {@code 00000000001}. This is the value a keyed read passes
     * as {@code RIDFLD}, and its width is {@link #KEY_LENGTH}.
     *
     * @return exactly {@link #KEY_LENGTH} characters
     */
    public String keyImage() {
        return raw(SPAN_ACCT_ID);
    }

    /**
     * The 11-character zero-filled key image for an account identifier, without needing a record.
     *
     * <p>A repository looking an account up by identifier has a number and no record yet, so it needs
     * the key image before it can read. Providing it here keeps the {@code PIC 9(11)} move rule -
     * zero-fill on the left, truncate on the left - in one place rather than letting each caller pad a
     * string itself.
     *
     * @param acctId  the account identifier; must not be negative, as {@code PIC 9} has no sign
     *                position
     * @param charset the code page the key will be written in; required rather than assumed, because
     *                the digits become bytes in a specific code page
     * @return exactly {@link #KEY_LENGTH} characters
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code acctId} is negative, or {@code charset} cannot encode
     *                                  digits as single bytes
     */
    public static String keyImage(long acctId, Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to build a key image: the digits become "
                + "bytes in a specific code page, which is never assumed");
        return new FixedWidthCodec(charset).movePic9(acctId, KEY_LENGTH);
    }

    // =================================================================================================
    // Private write helpers. Every cross-width assignment in this class routes through one of these
    // two, and therefore through the codec's explicitly named move rules, rather than through a plain
    // Java assignment: COBOL truncates a PIC X receiver on the RIGHT and a numeric receiver on the
    // LEFT, and choosing the direction deliberately is the whole point of naming the operation.
    // =================================================================================================

    /**
     * Stores a character value into an alphanumeric span, space-padding or right-truncating it.
     *
     * @param field     the receiving span
     * @param value     the sending value
     * @param parameter the caller's parameter name, so a null argument names the field the caller wrote
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private void writePicX(FieldSpan field, String value, String parameter) {
        Objects.requireNonNull(value, () -> parameter + " must not be null; " + field.name()
                + " is PIC X(" + field.length() + ") and has no null representation - store an empty "
                + "or blank string to blank the field");
        codec.writePicX(record, field, value);
    }

    /**
     * Stores a monetary value into a signed zoned span at the field's declared
     * {@code PIC S9(10)V99}.
     *
     * <p>The value is put through {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} at
     * {@code (}{@link #MONETARY_INTEGER_DIGITS}{@code , }{@link #MONETARY_SCALE}{@code )} before being
     * encoded, so the receiving picture is stated explicitly at every store in this class rather than
     * left to be inferred. That truncates excess fraction digits toward zero - {@code RoundingMode.DOWN},
     * because the keyword {@code ROUNDED} appears nowhere in the 28 programs, so COBOL truncates on
     * store - and then discards excess high-order digits, keeping the low-order ones and the sign,
     * because no program uses {@code ON SIZE ERROR}. The codec applies the same operation as it
     * encodes; it is idempotent, so applying it here as well changes no result and makes the declared
     * picture visible at the call site.
     *
     * @param field     the receiving span
     * @param value     the sending value, of any scale
     * @param parameter the caller's parameter name, for the null message
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private void writeMonetary(FieldSpan field, BigDecimal value, String parameter) {
        Objects.requireNonNull(value, () -> parameter + " must not be null; " + field.name()
                + " is PIC S9(" + MONETARY_INTEGER_DIGITS + ")V" + "9".repeat(MONETARY_SCALE)
                + " and has no null representation - store zero to clear the field");
        codec.writeMonetary(record, field,
                CobolDecimal.storeAtPicture(value, MONETARY_INTEGER_DIGITS, MONETARY_SCALE));
    }

    // =================================================================================================
    // Identity and diagnostics.
    // =================================================================================================

    /**
     * Compares two account records by their complete stored bytes.
     *
     * <p>Byte-image equality is chosen deliberately, for two reasons. It is what COBOL does: comparing
     * the group item {@code ACCOUNT-RECORD} compares all 300 bytes, {@code FILLER} included, so two
     * records that agree on every modelled field but differ in reserved storage are genuinely not the
     * same record. And it sidesteps a real trap: {@link BigDecimal#equals(Object)} is scale-sensitive,
     * so a field-by-field {@code equals} would have to rely on every monetary value having been
     * normalised to scale 2 - which this class does guarantee on store, but which is a fragile thing
     * for equality to depend on. The stored image has no such ambiguity.
     *
     * <p>Records in different code pages are therefore unequal, because their bytes differ. That is the
     * correct answer for a type whose contract is its bytes.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} is an {@code AccountRecord} with identical stored bytes
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountRecord that)) {
            return false;
        }
        return Arrays.equals(record.toByteArray(), that.record.toByteArray());
    }

    /**
     * A hash consistent with {@link #equals(Object)}, derived from the stored bytes.
     *
     * <p>Note that this record is mutable, so its hash changes when a field is stored. Do not use an
     * {@code AccountRecord} as a key in a hash-based collection while it is still being modified.
     *
     * @return the hash of the 300-byte image
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(record.toByteArray());
    }

    /**
     * A diagnostic rendering, field by field, showing each span's stored characters.
     *
     * <p>Intended for logs, assertion messages and debugging. It is deliberately <strong>not</strong>
     * the parity fingerprint: comparison and diffing use {@link #toByteArray()} or
     * {@link #toFixedWidthString()}, which are the record's actual contract. This format may change
     * without notice; those two may not.
     *
     * <p>Each monetary field is shown as its stored image, sign overpunch included, because that is
     * what the corresponding COBOL {@code DISPLAY} emits. The 178-byte {@code FILLER} is summarised
     * rather than printed, so a log line stays readable.
     *
     * @return a single-line description of every declared field
     */
    @Override
    public String toString() {
        String filler = getFiller();
        String fillerSummary = filler.isBlank()
                ? FILLER_LENGTH + " spaces"
                : FILLER_LENGTH + " bytes, not blank";
        return "AccountRecord["
                + ACCT_ID_NAME + "=" + rawAcctId()
                + ", " + ACCT_ACTIVE_STATUS_NAME + "='" + rawAcctActiveStatus() + "'"
                + ", " + ACCT_CURR_BAL_NAME + "=" + rawAcctCurrBal()
                + ", " + ACCT_CREDIT_LIMIT_NAME + "=" + rawAcctCreditLimit()
                + ", " + ACCT_CASH_CREDIT_LIMIT_NAME + "=" + rawAcctCashCreditLimit()
                + ", " + ACCT_OPEN_DATE_NAME + "='" + rawAcctOpenDate() + "'"
                + ", " + ACCT_EXPIRAION_DATE_NAME + "='" + rawAcctExpiraionDate() + "'"
                + ", " + ACCT_REISSUE_DATE_NAME + "='" + rawAcctReissueDate() + "'"
                + ", " + ACCT_CURR_CYC_CREDIT_NAME + "=" + rawAcctCurrCycCredit()
                + ", " + ACCT_CURR_CYC_DEBIT_NAME + "=" + rawAcctCurrCycDebit()
                + ", " + ACCT_ADDR_ZIP_NAME + "='" + rawAcctAddrZip() + "'"
                + ", " + ACCT_GROUP_ID_NAME + "='" + rawAcctGroupId() + "'"
                + ", FILLER=" + fillerSummary
                + ", charset=" + charset().name()
                + "]";
    }
}

