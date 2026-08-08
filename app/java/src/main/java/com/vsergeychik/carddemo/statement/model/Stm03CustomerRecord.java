package com.vsergeychik.carddemo.statement.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code CUSTOMER-RECORD} of {@code app/cpy/CUSTREC.cpy} - the 500-byte {@code CUSTFILE} record
 * as the statement job sees it.
 *
 * <h2>Provenance</h2>
 * Transcribed field for field from {@code app/cpy/CUSTREC.cpy}, whose own header comment declares
 * the width: {@code Data-structure for Customer entity (RECLN 500)}. The copybook declares
 * {@code 01 CUSTOMER-RECORD.} followed by nineteen flat {@code 05} items, the last of them
 * {@code FILLER PIC X(168)}. It is copied by exactly one program, {@code app/cbl/CBSTM03A.CBL:55}.
 *
 * <p>The width is corroborated independently rather than taken on trust. {@code CBSTM03B.CBL:72-73}
 * splits the same record in its {@code FD} as {@code FD-CUST-ID PIC X(09)} plus
 * {@code FD-CUST-DATA PIC X(491)}, and {@code 9 + 491 = 500}. The offsets below were derived by
 * <strong>summing the declared lengths</strong>, never by reading column positions: lines 6 to 22 of
 * the copybook begin with two literal TAB characters and line 11 carries trailing tabs after its
 * period, while lines 5 and 23 are space-indented, so the file's apparent columns are inconsistent.
 * Those same tabs are why GnuCOBOL cannot parse the copybook at all, which is one of the eight
 * verified blockers behind this migration's static-derivation parity baseline.
 *
 * <h2>⚠ This type must never be merged with the customer domain's {@code CustomerRecord}</h2>
 * {@code app/cpy/CUSTREC.cpy} and {@code app/cpy/CVCUS01Y.cpy} are, after whitespace normalisation,
 * identical but for two lines: this copybook spells the date of birth
 * <strong>{@code CUST-DOB-YYYYMMDD}</strong> where {@code CVCUS01Y} spells it
 * {@code CUST-DOB-YYYY-MM-DD}, and their version footers differ by one second. Everything else
 * matches - the same {@code 01 CUSTOMER-RECORD} group name, the same nineteen fields in the same
 * order with the same {@code PICTURE} clauses, the same trailing {@code FILLER X(168)}, the same
 * 500-byte total.
 *
 * <p>That single field <em>name</em> is the entire reason two Java types exist, and it is why the
 * following are all forbidden:
 * <ul>
 *   <li><strong>Never</strong> collapse this type into the customer domain's {@code CustomerRecord}.
 *   <li><strong>Never</strong> substitute one for the other at a call site. Both 500-byte types
 *       coexist inside the statement flow: {@code CBSTM03B} owns {@code CUSTFILE} <em>and</em> the
 *       other datasets, so both models are live at once and are never interchangeable.
 *   <li><strong>Never</strong> add a converter, mapper, adapter or {@code from(...)} factory between
 *       them. The Agent Action Plan forbids the converter by name.
 *   <li><strong>Never</strong> rename, normalise or hyphenate {@code CUST-DOB-YYYYMMDD}. Its Java
 *       identifier transliterates the copybook spelling as-is: {@link #custDobYyyymmdd()}.
 * </ul>
 *
 * <p>The reason this matters mechanically is worth stating plainly, because it is the one defect in
 * this file that no width check and no byte comparison can catch. Parity verification diffs records
 * <strong>field by field, keyed by field name</strong>. Both records are 500 bytes with byte-identical
 * spans, so merging them would leave every width check, every {@code FILLER} check and every
 * byte-image comparison green while silently erasing a field name that is part of the migration
 * contract. It would only ever surface as a name-keyed diff. (AAP &sect;0.3.3, &sect;0.8.2 and
 * practice B5 - preserve behaviour, including its oddities.)
 *
 * <p>A second oddity travels with that field and is likewise preserved rather than corrected: the
 * name says {@code YYYYMMDD} but the data does not. Row 1 of {@code app/data/ASCII/custdata.txt}
 * carries {@code 1961-06-08} in this span - a hyphenated ISO date in a field whose name promises
 * eight contiguous digits. The span is {@code PIC X(10)}, so it holds whatever ten bytes the dataset
 * holds; this class neither validates nor reformats it.
 *
 * <h2>Fixed width is the wire format</h2>
 * Every field is addressed by its absolute 0-based offset, and each offset constant below is derived
 * from the preceding field's offset plus its length so that the summation to 500 is visible in the
 * source and cannot drift. {@code FILLER X(168)} at offset 332 is a first-class, emitted span, not a
 * gap and not an implicit remainder: drop it and the record is 332 bytes and every subsequent byte
 * offset in the dataset shifts. {@link #LAYOUT} makes that failure immediate rather than silent,
 * because {@link RecordLayout} refuses to be constructed unless its spans are contiguous from offset
 * 0 and total exactly {@link #RECORD_LENGTH}.
 *
 * <h2>No packed decimal, and no {@code BigDecimal} anywhere</h2>
 * This copybook declares no signed picture, no {@code V}-scaled picture and no {@code COMP-3}. Its
 * only numeric fields are unsigned zoned {@code DISPLAY} integers - {@code CUST-ID} and
 * {@code CUST-SSN} at {@code PIC 9(09)}, and {@code CUST-FICO-CREDIT-SCORE} at {@code PIC 9(03)} -
 * so there is no scale to hold and no rounding policy to apply. This class therefore declares no
 * {@code BigDecimal} and deliberately does not reach for the module's decimal helper: importing a
 * rounding policy that no field in this record can ever need would be coupling without cause.
 * {@code double} and {@code float} are prohibited outright and appear nowhere.
 *
 * <h2>Reads are never trimmed</h2>
 * {@code CBSTM03A.CBL:462-478} consumes the names and the address through
 * {@code STRING CUST-FIRST-NAME DELIMITED BY ' '}, which depends on each value still carrying its
 * right-hand space padding: the delimiter is what ends the value. A {@code PIC X} field is
 * space-padded on write and is <em>not</em> trimmed on read unless the COBOL trims. Every accessor
 * here consequently returns the span exactly as it sits in the record, padding included, and no
 * convenience trimming accessor is offered. Where {@code STRING ... DELIMITED BY} semantics are
 * wanted they belong to the statement writer that owns the report layout, not to this model.
 *
 * <h2>The charset is always the caller's, never assumed</h2>
 * Every method that touches bytes takes an explicit {@link Charset} - {@code IBM037} for the EBCDIC
 * datasets, {@code US-ASCII} for the text fixtures. Nothing here imports the charset configuration,
 * nothing calls a no-argument {@code getBytes()}, and no method falls back to a platform default. The
 * value itself is charset-free: it holds character images, and a code page is needed only when those
 * images become bytes or bytes become images.
 *
 * <h2>Shape and thread safety</h2>
 * An immutable {@code record}: its eighteen referable field images are its components, in copybook
 * order, and the compact constructor rejects a {@code null} for any of them. There is no setter, no
 * mutable static state, no Spring annotation, no persistence annotation and no DDL - a copybook
 * record is a value, and this module reaches its datasets through hand-written fixed-width access
 * rather than an object-relational mapping. Instances are therefore freely shareable across threads,
 * and {@link #encode(Charset)}, {@link #groupImage(Charset)} and the span accessors each build their
 * own record area, so no state is shared between calls.
 *
 * @param custId                the {@code CUST-ID PIC 9(09)} image, the {@code CUSTFILE} KSDS key
 * @param custFirstName         the {@code CUST-FIRST-NAME PIC X(25)} image, space-padded, untrimmed
 * @param custMiddleName        the {@code CUST-MIDDLE-NAME PIC X(25)} image, space-padded, untrimmed
 * @param custLastName          the {@code CUST-LAST-NAME PIC X(25)} image, space-padded, untrimmed
 * @param custAddrLine1         the {@code CUST-ADDR-LINE-1 PIC X(50)} image
 * @param custAddrLine2         the {@code CUST-ADDR-LINE-2 PIC X(50)} image
 * @param custAddrLine3         the {@code CUST-ADDR-LINE-3 PIC X(50)} image
 * @param custAddrStateCd       the {@code CUST-ADDR-STATE-CD PIC X(02)} image
 * @param custAddrCountryCd     the {@code CUST-ADDR-COUNTRY-CD PIC X(03)} image
 * @param custAddrZip           the {@code CUST-ADDR-ZIP PIC X(10)} image
 * @param custPhoneNum1         the {@code CUST-PHONE-NUM-1 PIC X(15)} image
 * @param custPhoneNum2         the {@code CUST-PHONE-NUM-2 PIC X(15)} image
 * @param custSsn               the {@code CUST-SSN PIC 9(09)} image
 * @param custGovtIssuedId      the {@code CUST-GOVT-ISSUED-ID PIC X(20)} image
 * @param custDobYyyymmdd       the {@code CUST-DOB-YYYYMMDD PIC X(10)} image - the field whose
 *                              <em>name</em> is this type's entire reason for existing, spelled
 *                              exactly as {@code CUSTREC.cpy} spells it and never as
 *                              {@code CVCUS01Y.cpy} does
 * @param custEftAccountId      the {@code CUST-EFT-ACCOUNT-ID PIC X(10)} image
 * @param custPriCardHolderInd  the {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} image
 * @param custFicoCreditScore   the {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} image
 * @see #LAYOUT
 * @see #decode(String, Charset)
 * @see #encode(Charset)
 */
public record Stm03CustomerRecord(String custId,
                                  String custFirstName,
                                  String custMiddleName,
                                  String custLastName,
                                  String custAddrLine1,
                                  String custAddrLine2,
                                  String custAddrLine3,
                                  String custAddrStateCd,
                                  String custAddrCountryCd,
                                  String custAddrZip,
                                  String custPhoneNum1,
                                  String custPhoneNum2,
                                  String custSsn,
                                  String custGovtIssuedId,
                                  String custDobYyyymmdd,
                                  String custEftAccountId,
                                  String custPriCardHolderInd,
                                  String custFicoCreditScore) {

    // =================================================================================================
    // Offsets and lengths, one named pair per copybook item.
    //
    // Every offset is written as "the previous field's offset plus its length" rather than as a bare
    // integer. The arithmetic is then the copybook's own arithmetic, visible in the source, and a
    // reviewer holding CUSTREC.cpy open beside this block can confirm each line by eye. Inserting,
    // widening or removing a field cannot leave a stale offset behind, and the running total is
    // checked against RECORD_LENGTH by LAYOUT's own self-check below.
    //
    // The literal value each constant evaluates to is stated in a trailing comment so the table can
    // also be read at a glance.
    // =================================================================================================

    /**
     * The declared record width: {@code 500} bytes, exactly as {@code CUSTREC.cpy}'s header comment
     * states and as {@code CBSTM03B}'s {@code 9 + 491} {@code FD} split corroborates.
     *
     * <p>{@code StatementGenerationJobB} reads this constant by name so that no dataset width is
     * written as a literal anywhere in the statement flow.
     */
    public static final int RECORD_LENGTH = 500;

    /** Offset of {@code CUST-ID PIC 9(09)}: {@code 0}. */
    public static final int CUST_ID_OFFSET = 0;

    /** Length of {@code CUST-ID PIC 9(09)}: {@code 9}. */
    public static final int CUST_ID_LENGTH = 9;

    /** Offset of {@code CUST-FIRST-NAME PIC X(25)}: {@code 9}. */
    public static final int CUST_FIRST_NAME_OFFSET = CUST_ID_OFFSET + CUST_ID_LENGTH;

    /** Length of {@code CUST-FIRST-NAME PIC X(25)}: {@code 25}. */
    public static final int CUST_FIRST_NAME_LENGTH = 25;

    /** Offset of {@code CUST-MIDDLE-NAME PIC X(25)}: {@code 34}. */
    public static final int CUST_MIDDLE_NAME_OFFSET = CUST_FIRST_NAME_OFFSET + CUST_FIRST_NAME_LENGTH;

    /** Length of {@code CUST-MIDDLE-NAME PIC X(25)}: {@code 25}. */
    public static final int CUST_MIDDLE_NAME_LENGTH = 25;

    /** Offset of {@code CUST-LAST-NAME PIC X(25)}: {@code 59}. */
    public static final int CUST_LAST_NAME_OFFSET =
            CUST_MIDDLE_NAME_OFFSET + CUST_MIDDLE_NAME_LENGTH;

    /** Length of {@code CUST-LAST-NAME PIC X(25)}: {@code 25}. */
    public static final int CUST_LAST_NAME_LENGTH = 25;

    /** Offset of {@code CUST-ADDR-LINE-1 PIC X(50)}: {@code 84}. */
    public static final int CUST_ADDR_LINE_1_OFFSET = CUST_LAST_NAME_OFFSET + CUST_LAST_NAME_LENGTH;

    /** Length of {@code CUST-ADDR-LINE-1 PIC X(50)}: {@code 50}. */
    public static final int CUST_ADDR_LINE_1_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-LINE-2 PIC X(50)}: {@code 134}. */
    public static final int CUST_ADDR_LINE_2_OFFSET =
            CUST_ADDR_LINE_1_OFFSET + CUST_ADDR_LINE_1_LENGTH;

    /** Length of {@code CUST-ADDR-LINE-2 PIC X(50)}: {@code 50}. */
    public static final int CUST_ADDR_LINE_2_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-LINE-3 PIC X(50)}: {@code 184}. */
    public static final int CUST_ADDR_LINE_3_OFFSET =
            CUST_ADDR_LINE_2_OFFSET + CUST_ADDR_LINE_2_LENGTH;

    /** Length of {@code CUST-ADDR-LINE-3 PIC X(50)}: {@code 50}. */
    public static final int CUST_ADDR_LINE_3_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-STATE-CD PIC X(02)}: {@code 234}. */
    public static final int CUST_ADDR_STATE_CD_OFFSET =
            CUST_ADDR_LINE_3_OFFSET + CUST_ADDR_LINE_3_LENGTH;

    /** Length of {@code CUST-ADDR-STATE-CD PIC X(02)}: {@code 2}. */
    public static final int CUST_ADDR_STATE_CD_LENGTH = 2;

    /** Offset of {@code CUST-ADDR-COUNTRY-CD PIC X(03)}: {@code 236}. */
    public static final int CUST_ADDR_COUNTRY_CD_OFFSET =
            CUST_ADDR_STATE_CD_OFFSET + CUST_ADDR_STATE_CD_LENGTH;

    /** Length of {@code CUST-ADDR-COUNTRY-CD PIC X(03)}: {@code 3}. */
    public static final int CUST_ADDR_COUNTRY_CD_LENGTH = 3;

    /** Offset of {@code CUST-ADDR-ZIP PIC X(10)}: {@code 239}. */
    public static final int CUST_ADDR_ZIP_OFFSET =
            CUST_ADDR_COUNTRY_CD_OFFSET + CUST_ADDR_COUNTRY_CD_LENGTH;

    /** Length of {@code CUST-ADDR-ZIP PIC X(10)}: {@code 10}. */
    public static final int CUST_ADDR_ZIP_LENGTH = 10;

    /** Offset of {@code CUST-PHONE-NUM-1 PIC X(15)}: {@code 249}. */
    public static final int CUST_PHONE_NUM_1_OFFSET = CUST_ADDR_ZIP_OFFSET + CUST_ADDR_ZIP_LENGTH;

    /** Length of {@code CUST-PHONE-NUM-1 PIC X(15)}: {@code 15}. */
    public static final int CUST_PHONE_NUM_1_LENGTH = 15;

    /** Offset of {@code CUST-PHONE-NUM-2 PIC X(15)}: {@code 264}. */
    public static final int CUST_PHONE_NUM_2_OFFSET =
            CUST_PHONE_NUM_1_OFFSET + CUST_PHONE_NUM_1_LENGTH;

    /** Length of {@code CUST-PHONE-NUM-2 PIC X(15)}: {@code 15}. */
    public static final int CUST_PHONE_NUM_2_LENGTH = 15;

    /** Offset of {@code CUST-SSN PIC 9(09)}: {@code 279}. */
    public static final int CUST_SSN_OFFSET = CUST_PHONE_NUM_2_OFFSET + CUST_PHONE_NUM_2_LENGTH;

    /** Length of {@code CUST-SSN PIC 9(09)}: {@code 9}. */
    public static final int CUST_SSN_LENGTH = 9;

    /** Offset of {@code CUST-GOVT-ISSUED-ID PIC X(20)}: {@code 288}. */
    public static final int CUST_GOVT_ISSUED_ID_OFFSET = CUST_SSN_OFFSET + CUST_SSN_LENGTH;

    /** Length of {@code CUST-GOVT-ISSUED-ID PIC X(20)}: {@code 20}. */
    public static final int CUST_GOVT_ISSUED_ID_LENGTH = 20;

    /**
     * Offset of {@code CUST-DOB-YYYYMMDD PIC X(10)}: {@code 308}.
     *
     * <p>The un-hyphenated spelling is the copybook's own and is the single difference between
     * {@code CUSTREC.cpy} and {@code CVCUS01Y.cpy}. It is never normalised to
     * {@code CUST-DOB-YYYY-MM-DD}.
     */
    public static final int CUST_DOB_YYYYMMDD_OFFSET =
            CUST_GOVT_ISSUED_ID_OFFSET + CUST_GOVT_ISSUED_ID_LENGTH;

    /** Length of {@code CUST-DOB-YYYYMMDD PIC X(10)}: {@code 10}. */
    public static final int CUST_DOB_YYYYMMDD_LENGTH = 10;

    /** Offset of {@code CUST-EFT-ACCOUNT-ID PIC X(10)}: {@code 318}. */
    public static final int CUST_EFT_ACCOUNT_ID_OFFSET =
            CUST_DOB_YYYYMMDD_OFFSET + CUST_DOB_YYYYMMDD_LENGTH;

    /** Length of {@code CUST-EFT-ACCOUNT-ID PIC X(10)}: {@code 10}. */
    public static final int CUST_EFT_ACCOUNT_ID_LENGTH = 10;

    /** Offset of {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}: {@code 328}. */
    public static final int CUST_PRI_CARD_HOLDER_IND_OFFSET =
            CUST_EFT_ACCOUNT_ID_OFFSET + CUST_EFT_ACCOUNT_ID_LENGTH;

    /** Length of {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}: {@code 1}. */
    public static final int CUST_PRI_CARD_HOLDER_IND_LENGTH = 1;

    /** Offset of {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}: {@code 329}. */
    public static final int CUST_FICO_CREDIT_SCORE_OFFSET =
            CUST_PRI_CARD_HOLDER_IND_OFFSET + CUST_PRI_CARD_HOLDER_IND_LENGTH;

    /** Length of {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}: {@code 3}. */
    public static final int CUST_FICO_CREDIT_SCORE_LENGTH = 3;

    /**
     * Offset of the trailing {@code FILLER PIC X(168)}: {@code 332}.
     *
     * <p>Declared exactly like any other field, because it is one. The reserved span is emitted as
     * spaces on every write; omitting it would make the record 332 bytes and shift every subsequent
     * byte offset in the dataset.
     */
    public static final int FILLER_OFFSET =
            CUST_FICO_CREDIT_SCORE_OFFSET + CUST_FICO_CREDIT_SCORE_LENGTH;

    /** Length of the trailing {@code FILLER PIC X(168)}: {@code 168}, taking the record to 500. */
    public static final int FILLER_LENGTH = 168;

    /**
     * Offset of the {@code CUSTFILE} KSDS key: {@code 0}.
     *
     * <p>{@code CBSTM03B.CBL:44-47} declares {@code SELECT CUST-FILE ASSIGN TO CUSTFILE ORGANIZATION
     * IS INDEXED ACCESS MODE IS RANDOM RECORD KEY IS FD-CUST-ID}, so {@code CUST-ID} is the record
     * key and the dataset is read keyed-only - {@code 3000-CUSTFILE-PROC} implements open, keyed read
     * and close, and nothing else. {@code StatementGenerationJobB} reads this constant and
     * {@link #KEY_LENGTH} by name so that no key geometry is written as a literal there.
     */
    public static final int KEY_OFFSET = CUST_ID_OFFSET;

    /** Length of the {@code CUSTFILE} KSDS key {@code CUST-ID}: {@code 9}. */
    public static final int KEY_LENGTH = CUST_ID_LENGTH;

    // =================================================================================================
    // The descriptor table: one FieldSpan per copybook item, in declaration order, built from the
    // constants above so that a name, an offset, a length and a PICTURE category are stated exactly
    // once each.
    //
    // Every descriptor is an immutable record and the list that holds them is copied defensively by
    // RecordLayout, so the table is static final AND genuinely unmodifiable - a caller cannot reach
    // through it and there is no mutable static state anywhere in this class.
    //
    // Field names are carried VERBATIM as the copybook spells them, hyphens and all, because parity
    // diffing is keyed by name.
    // =================================================================================================

    /** {@code 05 CUST-ID PIC 9(09).} - the {@code CUSTFILE} KSDS key, at offset 0, 9 bytes. */
    public static final FieldSpan CUST_ID =
            FieldSpan.unsignedNumeric("CUST-ID", CUST_ID_OFFSET, CUST_ID_LENGTH);

    /** {@code 05 CUST-FIRST-NAME PIC X(25).} - offset 9, 25 bytes. */
    public static final FieldSpan CUST_FIRST_NAME =
            FieldSpan.alphanumeric("CUST-FIRST-NAME", CUST_FIRST_NAME_OFFSET, CUST_FIRST_NAME_LENGTH);

    /** {@code 05 CUST-MIDDLE-NAME PIC X(25).} - offset 34, 25 bytes. */
    public static final FieldSpan CUST_MIDDLE_NAME = FieldSpan.alphanumeric("CUST-MIDDLE-NAME",
            CUST_MIDDLE_NAME_OFFSET, CUST_MIDDLE_NAME_LENGTH);

    /** {@code 05 CUST-LAST-NAME PIC X(25).} - offset 59, 25 bytes. */
    public static final FieldSpan CUST_LAST_NAME =
            FieldSpan.alphanumeric("CUST-LAST-NAME", CUST_LAST_NAME_OFFSET, CUST_LAST_NAME_LENGTH);

    /** {@code 05 CUST-ADDR-LINE-1 PIC X(50).} - offset 84, 50 bytes. */
    public static final FieldSpan CUST_ADDR_LINE_1 = FieldSpan.alphanumeric("CUST-ADDR-LINE-1",
            CUST_ADDR_LINE_1_OFFSET, CUST_ADDR_LINE_1_LENGTH);

    /** {@code 05 CUST-ADDR-LINE-2 PIC X(50).} - offset 134, 50 bytes. */
    public static final FieldSpan CUST_ADDR_LINE_2 = FieldSpan.alphanumeric("CUST-ADDR-LINE-2",
            CUST_ADDR_LINE_2_OFFSET, CUST_ADDR_LINE_2_LENGTH);

    /** {@code 05 CUST-ADDR-LINE-3 PIC X(50).} - offset 184, 50 bytes. */
    public static final FieldSpan CUST_ADDR_LINE_3 = FieldSpan.alphanumeric("CUST-ADDR-LINE-3",
            CUST_ADDR_LINE_3_OFFSET, CUST_ADDR_LINE_3_LENGTH);

    /** {@code 05 CUST-ADDR-STATE-CD PIC X(02).} - offset 234, 2 bytes. */
    public static final FieldSpan CUST_ADDR_STATE_CD = FieldSpan.alphanumeric("CUST-ADDR-STATE-CD",
            CUST_ADDR_STATE_CD_OFFSET, CUST_ADDR_STATE_CD_LENGTH);

    /** {@code 05 CUST-ADDR-COUNTRY-CD PIC X(03).} - offset 236, 3 bytes. */
    public static final FieldSpan CUST_ADDR_COUNTRY_CD = FieldSpan.alphanumeric(
            "CUST-ADDR-COUNTRY-CD", CUST_ADDR_COUNTRY_CD_OFFSET, CUST_ADDR_COUNTRY_CD_LENGTH);

    /** {@code 05 CUST-ADDR-ZIP PIC X(10).} - offset 239, 10 bytes. */
    public static final FieldSpan CUST_ADDR_ZIP =
            FieldSpan.alphanumeric("CUST-ADDR-ZIP", CUST_ADDR_ZIP_OFFSET, CUST_ADDR_ZIP_LENGTH);

    /** {@code 05 CUST-PHONE-NUM-1 PIC X(15).} - offset 249, 15 bytes. */
    public static final FieldSpan CUST_PHONE_NUM_1 = FieldSpan.alphanumeric("CUST-PHONE-NUM-1",
            CUST_PHONE_NUM_1_OFFSET, CUST_PHONE_NUM_1_LENGTH);

    /** {@code 05 CUST-PHONE-NUM-2 PIC X(15).} - offset 264, 15 bytes. */
    public static final FieldSpan CUST_PHONE_NUM_2 = FieldSpan.alphanumeric("CUST-PHONE-NUM-2",
            CUST_PHONE_NUM_2_OFFSET, CUST_PHONE_NUM_2_LENGTH);

    /** {@code 05 CUST-SSN PIC 9(09).} - offset 279, 9 bytes, unsigned zoned {@code DISPLAY}. */
    public static final FieldSpan CUST_SSN =
            FieldSpan.unsignedNumeric("CUST-SSN", CUST_SSN_OFFSET, CUST_SSN_LENGTH);

    /** {@code 05 CUST-GOVT-ISSUED-ID PIC X(20).} - offset 288, 20 bytes. */
    public static final FieldSpan CUST_GOVT_ISSUED_ID = FieldSpan.alphanumeric("CUST-GOVT-ISSUED-ID",
            CUST_GOVT_ISSUED_ID_OFFSET, CUST_GOVT_ISSUED_ID_LENGTH);

    /**
     * {@code 05 CUST-DOB-YYYYMMDD PIC X(10).} - offset 308, 10 bytes.
     *
     * <p>The name is the one and only structural difference between {@code app/cpy/CUSTREC.cpy} and
     * {@code app/cpy/CVCUS01Y.cpy}, and it is carried here exactly as {@code CUSTREC.cpy} spells it.
     * The span is alphanumeric, so it holds whatever ten bytes the dataset holds - including the
     * hyphenated {@code 1961-06-08} that {@code app/data/ASCII/custdata.txt} actually carries in a
     * field whose name promises eight contiguous digits. Neither the name nor the content is
     * corrected.
     */
    public static final FieldSpan CUST_DOB_YYYYMMDD = FieldSpan.alphanumeric("CUST-DOB-YYYYMMDD",
            CUST_DOB_YYYYMMDD_OFFSET, CUST_DOB_YYYYMMDD_LENGTH);

    /** {@code 05 CUST-EFT-ACCOUNT-ID PIC X(10).} - offset 318, 10 bytes. */
    public static final FieldSpan CUST_EFT_ACCOUNT_ID = FieldSpan.alphanumeric("CUST-EFT-ACCOUNT-ID",
            CUST_EFT_ACCOUNT_ID_OFFSET, CUST_EFT_ACCOUNT_ID_LENGTH);

    /** {@code 05 CUST-PRI-CARD-HOLDER-IND PIC X(01).} - offset 328, 1 byte. */
    public static final FieldSpan CUST_PRI_CARD_HOLDER_IND = FieldSpan.alphanumeric(
            "CUST-PRI-CARD-HOLDER-IND", CUST_PRI_CARD_HOLDER_IND_OFFSET,
            CUST_PRI_CARD_HOLDER_IND_LENGTH);

    /** {@code 05 CUST-FICO-CREDIT-SCORE PIC 9(03).} - offset 329, 3 bytes, unsigned zoned. */
    public static final FieldSpan CUST_FICO_CREDIT_SCORE = FieldSpan.unsignedNumeric(
            "CUST-FICO-CREDIT-SCORE", CUST_FICO_CREDIT_SCORE_OFFSET, CUST_FICO_CREDIT_SCORE_LENGTH);

    /**
     * {@code 05 FILLER PIC X(168).} - offset 332, 168 bytes, and the span that takes the record to
     * its declared 500.
     *
     * <p>It is declared here as a positioned, length-bearing entry exactly like every named field,
     * never inferred as the gap between the last field and the end of the record. It carries no
     * {@code VALUE}, so it is emitted as spaces in the record's own code page.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete, self-checking layout of {@code CUSTOMER-RECORD}: nineteen contiguous spans from
     * offset 0 totalling exactly {@link #RECORD_LENGTH} bytes.
     *
     * <p>{@link RecordLayout} verifies that geometry as it is constructed and copies the span list
     * defensively, so this constant is both immutable and proof that the transcription adds up. If
     * the trailing {@code FILLER} were dropped the layout would declare 332 bytes against a record
     * length of 500 and construction would fail immediately, naming the shortfall - which is what
     * turns a dropped {@code FILLER} from a silent corruption of every downstream offset into a
     * precisely located error. There is no {@code REDEFINES} overlay in this copybook, so every span
     * here occupies storage.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            CUST_ID,
            CUST_FIRST_NAME,
            CUST_MIDDLE_NAME,
            CUST_LAST_NAME,
            CUST_ADDR_LINE_1,
            CUST_ADDR_LINE_2,
            CUST_ADDR_LINE_3,
            CUST_ADDR_STATE_CD,
            CUST_ADDR_COUNTRY_CD,
            CUST_ADDR_ZIP,
            CUST_PHONE_NUM_1,
            CUST_PHONE_NUM_2,
            CUST_SSN,
            CUST_GOVT_ISSUED_ID,
            CUST_DOB_YYYYMMDD,
            CUST_EFT_ACCOUNT_ID,
            CUST_PRI_CARD_HOLDER_IND,
            CUST_FICO_CREDIT_SCORE,
            FILLER);

    /**
     * Validates that every field image is present.
     *
     * <p>{@code null} is rejected and every other image is <strong>received</strong>: the move rule its
     * receiver's {@code PICTURE} implies is applied here, so each component holds exactly its declared
     * width from the moment the record exists. A value narrower than its receiver is padded and a wider
     * one truncated, in the direction that {@code PICTURE} dictates - exactly as COBOL does, and exactly
     * as {@link #encode(Charset)} would later have done - so construction still succeeds for every input
     * it ever accepted. What is no longer possible is for a component accessor, {@code equals} and
     * {@code encode} to disagree about what this record contains. See {@link #received(String,
     * FieldSpan)} for the numeric-versus-group-move distinction. An instance produced by
     * {@link #decode(String, Charset)} is unaffected either way, because each of its images is already
     * read from its declared span.
     *
     * @throws NullPointerException if any field image is {@code null}; to blank a field supply spaces
     *                              or an empty string, which {@link #encode(Charset)} pads out
     */
    public Stm03CustomerRecord {
        custId = received(custId, CUST_ID);
        custFirstName = received(custFirstName, CUST_FIRST_NAME);
        custMiddleName = received(custMiddleName, CUST_MIDDLE_NAME);
        custLastName = received(custLastName, CUST_LAST_NAME);
        custAddrLine1 = received(custAddrLine1, CUST_ADDR_LINE_1);
        custAddrLine2 = received(custAddrLine2, CUST_ADDR_LINE_2);
        custAddrLine3 = received(custAddrLine3, CUST_ADDR_LINE_3);
        custAddrStateCd = received(custAddrStateCd, CUST_ADDR_STATE_CD);
        custAddrCountryCd = received(custAddrCountryCd, CUST_ADDR_COUNTRY_CD);
        custAddrZip = received(custAddrZip, CUST_ADDR_ZIP);
        custPhoneNum1 = received(custPhoneNum1, CUST_PHONE_NUM_1);
        custPhoneNum2 = received(custPhoneNum2, CUST_PHONE_NUM_2);
        custSsn = received(custSsn, CUST_SSN);
        custGovtIssuedId = received(custGovtIssuedId, CUST_GOVT_ISSUED_ID);
        custDobYyyymmdd = received(custDobYyyymmdd, CUST_DOB_YYYYMMDD);
        custEftAccountId = received(custEftAccountId, CUST_EFT_ACCOUNT_ID);
        custPriCardHolderInd = received(custPriCardHolderInd, CUST_PRI_CARD_HOLDER_IND);
        custFicoCreditScore = received(custFicoCreditScore, CUST_FICO_CREDIT_SCORE);
    }

    /**
     * Receives a field image, refusing {@code null} and applying the receiver's own width rule.
     *
     * <p>The rule is the {@code MOVE} the field's {@code PICTURE} implies, and it is applied
     * <strong>here</strong> rather than at encode time so that a component accessor, {@code equals},
     * {@code hashCode} and {@code encode} can never describe different records. A component that held
     * thirty characters for a {@code PIC X(25)} field would be reporting a state the copybook's field
     * cannot occupy, and the fact that {@code encode} would later narrow it does not make the
     * intervening observations true.
     *
     * <p>Nothing is <em>refused</em> for its width, which matters: COBOL does not refuse either. A
     * narrow value is padded and a wide one truncated, so construction still succeeds for exactly the
     * inputs it always did - the difference is only that the field now holds the result of the move
     * rather than the argument to it.
     *
     * <h4>Why a digit image and a blank image are padded in opposite directions</h4>
     * A numeric receiver is aligned on its implied decimal point, so a short digit image is
     * <em>left</em>-zero-filled and a long one loses its high-order digits: {@code "42"} in
     * {@code CUST-ID} becomes {@code "000000042"}, which is what {@link #area(FixedWidthCodec)} has
     * always written and what a COBOL numeric {@code MOVE} into a nine-digit receiver does.
     *
     * <p>But this record can also legitimately hold a <em>non-numeric</em> image in a numeric span, and
     * that is not corruption: {@code app/cbl/CBSTM03A.CBL:388} populates the whole record with
     * {@code MOVE WS-M03B-FLDT TO CUSTOMER-RECORD}, a <strong>group</strong> alphanumeric move which
     * ignores the elementary pictures entirely and copies bytes, so a blank {@code CUSTFILE} record
     * arrives as five hundred spaces. Such an image is therefore width-normalised alphanumerically
     * rather than rejected - a record area holds bytes, and what those bytes mean stays the caller's
     * question, answered by {@link #custIdValue(Charset)} and its siblings, which do report a
     * non-numeric span as an error.
     *
     * @param image the candidate field image
     * @param field the span whose declared width and {@code PICTURE} category fix the rule
     * @return exactly {@code field.length()} characters
     * @throws NullPointerException if {@code image} is {@code null}
     */
    private static String received(String image, FieldSpan field) {
        Objects.requireNonNull(image, "The image of " + field.name() + " is required; a "
                + "CUSTOMER-RECORD field is a fixed-width span that always holds bytes, so to blank "
                + "it supply spaces or an empty string rather than null");
        if (field.kind() == PictureKind.UNSIGNED_NUMERIC && isAllDigits(image)) {
            return PICTURE_RULES.movePic9(image, field.length());
        }
        return PICTURE_RULES.movePicX(image, field.length());
    }

    /**
     * Whether an image consists wholly of digits, and so is a numeric {@code MOVE}'s subject.
     *
     * <p>An empty image is not: there are no digits to align, so it is padded as the group move would
     * pad it. That keeps a blank field and an empty string indistinguishable, which they are in COBOL.
     *
     * @param image the image to inspect
     * @return {@code true} when {@code image} is non-empty and every character is {@code '0'} to
     *         {@code '9'}
     */
    private static boolean isAllDigits(String image) {
        if (image.isEmpty()) {
            return false;
        }
        for (int position = 0; position < image.length(); position++) {
            char character = image.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code PICTURE} move rules, applied when an image enters a component.
     *
     * <p>Static and shared, and safe: {@link FixedWidthCodec} is immutable and the two rules used -
     * {@link FixedWidthCodec#movePicX(String, int)} and {@link FixedWidthCodec#movePic9(String, int)} -
     * count characters and digits without rendering a byte or consulting a code page, so their answers
     * are identical under {@code IBM037} and {@code US-ASCII} alike. Every method here that actually
     * produces bytes still takes its {@link Charset} explicitly from the caller.
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // Decoding. This is where the 1000-byte-to-500-byte truncation lives.
    // =================================================================================================

    /**
     * Decodes a {@code CUSTOMER-RECORD} from a record image, reading only the leading
     * {@link #RECORD_LENGTH} characters.
     *
     * <p><strong>Accepting an over-long image is required behaviour, not defensive coding.</strong>
     * {@code CBSTM03B} reads the 500-byte {@code CUSTFILE} record into {@code LK-M03B-FLDT}, which is
     * declared {@code PIC X(1000)} at {@code CBSTM03B.CBL:83}, so the caller receives a 1000-byte
     * space-padded span. {@code CBSTM03A.CBL:388} then performs
     * {@code MOVE WS-M03B-FLDT TO CUSTOMER-RECORD}, and a COBOL alphanumeric {@code MOVE} fills its
     * receiver from the left and discards the overflow - the surviving bytes are the leading 500.
     * {@code StatementGenerationJobB} deliberately hands over the raw span without decoding it, so
     * this method is the one place that truncation happens.
     *
     * <p>The truncation is therefore routed through {@link FixedWidthCodec#movePicX(String, int)},
     * whose name states the direction, rather than through a bare {@code substring} whose direction a
     * reader would have to infer. The same call pads a short image on the right with spaces, which is
     * what the same {@code MOVE} does when the sending field is narrower than its receiver.
     *
     * <p>Each field is then read straight from its declared span with no trimming and no numeric
     * interpretation, so a blank numeric span decodes to spaces rather than raising - a record area
     * holds bytes, and what those bytes mean is the caller's question, answered by
     * {@link #custIdValue(Charset)} and its siblings.
     *
     * @param image   the record image; may be exactly {@link #RECORD_LENGTH} characters, longer - the
     *                1000-character case above - or shorter, in which case it is space-padded
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the decoded record, its eighteen field images each exactly its declared width
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  digits, the sign overpunch characters and the space, or if the
     *                                  leading {@link #RECORD_LENGTH} characters do not encode to
     *                                  exactly {@link #RECORD_LENGTH} bytes under it
     */
    public static Stm03CustomerRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A record image is required to decode a CUSTOMER-RECORD; call "
                + "blank(Charset) for an initialised, empty record instead");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        String leading = codec.movePicX(image, RECORD_LENGTH);
        return fromArea(codec.wrap(codec.encodeImage(leading, "a CUSTOMER-RECORD image"), LAYOUT));
    }

    /**
     * Decodes a {@code CUSTOMER-RECORD} from record bytes, reading only the leading
     * {@link #RECORD_LENGTH} bytes.
     *
     * <p>Delegates to {@link #decode(String, Charset)} so that the leading-bytes rule has exactly one
     * implementation in this class and stays auditable in one place. The delegation is exact rather
     * than approximate because every one of this copybook's nineteen items is {@code PIC X} or
     * unsigned zoned {@code PIC 9} - character data, one byte per character, with no
     * {@code COMP-3} and no binary field anywhere in it - and because the codec rejects any code page
     * that does not encode the digits, the sign overpunch characters and the space to exactly one
     * byte.
     *
     * @param source  the record bytes; may be exactly {@link #RECORD_LENGTH} bytes, longer - the
     *                1000-byte {@code LK-M03B-FLDT} case - or shorter, in which case it is
     *                space-padded
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the decoded record
     * @throws NullPointerException     if {@code source} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page
     */
    public static Stm03CustomerRecord decode(byte[] source, Charset charset) {
        Objects.requireNonNull(source, "Record bytes are required to decode a CUSTOMER-RECORD");
        Objects.requireNonNull(charset, "A charset is required to decode CUSTFILE bytes: fixed-width "
                + "mainframe data is bytes in a specific code page, so the code page is stated "
                + "explicitly and never derived from the platform");
        return decode(FixedWidthRecord.decodeText(source, charset, "a CUSTOMER-RECORD image"),
                charset);
    }

    /**
     * An initialised, empty {@code CUSTOMER-RECORD}: every alphanumeric span and the trailing
     * {@code FILLER} space-filled, and the three unsigned numeric spans zero-filled, following the
     * COBOL {@code INITIALIZE} convention.
     *
     * <p>Useful as the starting point for a hand-built record and as the fixed point of the
     * round-trip: {@code blank(cs).encode(cs)} is a valid 500-byte {@code CUSTOMER-RECORD} image.
     *
     * @param charset the code page whose space and zero bytes fill the record
     * @return a record whose field images are the initialised spans
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page
     */
    public static Stm03CustomerRecord blank(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return fromArea(codec.newRecord(LAYOUT));
    }

    /**
     * Reads all eighteen referable spans out of a record area, in copybook order and exactly as they
     * sit in it - no trimming, no numeric parsing, no reformatting. {@code FILLER} is not read
     * because it is not a referable COBOL name; it remains part of the record's bytes.
     */
    private static Stm03CustomerRecord fromArea(FixedWidthRecord area) {
        return new Stm03CustomerRecord(
                area.readSpan(CUST_ID),
                area.readSpan(CUST_FIRST_NAME),
                area.readSpan(CUST_MIDDLE_NAME),
                area.readSpan(CUST_LAST_NAME),
                area.readSpan(CUST_ADDR_LINE_1),
                area.readSpan(CUST_ADDR_LINE_2),
                area.readSpan(CUST_ADDR_LINE_3),
                area.readSpan(CUST_ADDR_STATE_CD),
                area.readSpan(CUST_ADDR_COUNTRY_CD),
                area.readSpan(CUST_ADDR_ZIP),
                area.readSpan(CUST_PHONE_NUM_1),
                area.readSpan(CUST_PHONE_NUM_2),
                area.readSpan(CUST_SSN),
                area.readSpan(CUST_GOVT_ISSUED_ID),
                area.readSpan(CUST_DOB_YYYYMMDD),
                area.readSpan(CUST_EFT_ACCOUNT_ID),
                area.readSpan(CUST_PRI_CARD_HOLDER_IND),
                area.readSpan(CUST_FICO_CREDIT_SCORE));
    }

    // =================================================================================================
    // Encoding. Alphanumeric spans pad and truncate on the RIGHT, numeric spans on the LEFT, and the
    // trailing FILLER is emitted as 168 spaces. All three rules come from the module's single MOVE
    // implementation rather than being restated here.
    // =================================================================================================

    /**
     * Serialises this record to exactly {@link #RECORD_LENGTH} bytes.
     *
     * <p>The area starts from {@link FixedWidthCodec#newRecord(RecordLayout)}, so the trailing
     * {@code FILLER PIC X(168)} is already space-filled in the caller's own code page before any field
     * is written and remains so afterwards - it is emitted, never omitted. Each of the eighteen
     * referable fields is then placed by its declared {@code PICTURE} category:
     * <ul>
     *   <li>{@code PIC X} through {@link FixedWidthCodec#writePicX}, which left justifies, pads on the
     *       right with spaces and truncates on the right;
     *   <li>{@code PIC 9} through {@link FixedWidthCodec#writePic9}, which right justifies, pads on the
     *       left with zeros and truncates on the left.
     * </ul>
     * The two directions are opposite, which is exactly why neither is written out by hand here.
     *
     * <p><strong>A numeric image that is not all digits is reported rather than written</strong>,
     * because a zoned {@code DISPLAY} field holds only the digits {@code 0} to {@code 9}. This applies
     * to every method here that turns the record into bytes - {@link #encode(Charset)},
     * {@link #groupImage(Charset)}, {@link #spanImage(FieldSpan, Charset)},
     * {@link #spanBytes(FieldSpan, Charset)}, {@link #fieldImages(Charset)} and the three value
     * accessors - because all of them apply the record's declared {@code PICTURE} semantics.
     *
     * <p>That strictness is safe here, and the evidence is in the COBOL rather than in an assumption.
     * {@code CBSTM03A.CBL:379-388} reads {@code CUSTFILE} and then evaluates the return code:
     * {@code WHEN '00' CONTINUE}, {@code WHEN OTHER} display and
     * {@code PERFORM 9999-ABEND-PROGRAM}. The subsequent
     * {@code MOVE WS-M03B-FLDT TO CUSTOMER-RECORD} is therefore reached only after a <em>successful</em>
     * keyed read, so the only state this model can ever hold in the statement flow is a real
     * {@code CUSTFILE} record - and every row of {@code app/data/ASCII/custdata.txt} carries digits in
     * all three numeric spans. Writing is unreachable from the other direction too:
     * {@code CBSTM03B} opens {@code CUSTFILE} with {@code OPEN INPUT} and implements only open, keyed
     * read and close, so no statement path ever emits a customer record at all. A blank or corrupted
     * numeric span consequently means the data is wrong, and reporting it precisely - naming the field
     * and the offending character - beats propagating it into a 500-byte image that looks plausible.
     *
     * @param charset the code page to encode into, stated explicitly by the caller
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, if
     *                                  a numeric field image is not all digits, or if an alphanumeric
     *                                  image encodes to more bytes than its span holds
     */
    public byte[] encode(Charset charset) {
        return area(new FixedWidthCodec(charset)).toByteArray();
    }

    /**
     * This record's whole {@link #RECORD_LENGTH}-byte group image as characters - the
     * {@code 01 CUSTOMER-RECORD} group viewed in one piece.
     *
     * <p>Provided for parity fingerprinting, where a written record is captured whole before being
     * decomposed into named fields, and for the round-trip assertion that a decoded image re-encodes
     * unchanged.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return exactly {@link #RECORD_LENGTH} characters
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or
     *                                  if a field image cannot be placed in its span
     */
    public String groupImage(Charset charset) {
        return area(new FixedWidthCodec(charset)).readString(0, RECORD_LENGTH);
    }

    /**
     * The characters of one declared span, read out of this record's encoded area.
     *
     * <p>Pass one of this class's {@code FieldSpan} constants - {@link #CUST_ID},
     * {@link #CUST_DOB_YYYYMMDD}, {@link #FILLER} and the rest - to read a field, including
     * {@code FILLER}, which no accessor exposes and which a width assertion needs to see.
     *
     * @param field   the span to read, normally one of this class's constants
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the span's characters, exactly as they sit in the record and never trimmed
     * @throws NullPointerException      if {@code field} or {@code charset} is {@code null}
     * @throws IndexOutOfBoundsException if {@code field} reaches past {@link #RECORD_LENGTH}
     * @throws IllegalArgumentException  if {@code charset} is not a suitable single-byte code page, or
     *                                   if a field image cannot be placed in its span
     */
    public String spanImage(FieldSpan field, Charset charset) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span; pass one "
                + "of this class's FieldSpan constants, such as CUST_DOB_YYYYMMDD");
        return area(new FixedWidthCodec(charset)).readSpan(field);
    }

    /**
     * The raw bytes of one declared span, read out of this record's encoded area.
     *
     * <p>The byte-level counterpart of {@link #spanImage(FieldSpan, Charset)}, for assertions that
     * have to see the actual pad bytes - {@code 0x40} and {@code 0xF0} under {@code IBM037},
     * {@code 0x20} and {@code 0x30} under {@code US-ASCII} - rather than the characters they decode
     * to. The returned array is a fresh copy.
     *
     * @param field   the span to read, normally one of this class's constants
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return a fresh array of exactly {@code field.length()} bytes
     * @throws NullPointerException      if {@code field} or {@code charset} is {@code null}
     * @throws IndexOutOfBoundsException if {@code field} reaches past {@link #RECORD_LENGTH}
     * @throws IllegalArgumentException  if {@code charset} is not a suitable single-byte code page, or
     *                                   if a field image cannot be placed in its span
     */
    public byte[] spanBytes(FieldSpan field, Charset charset) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span; pass one "
                + "of this class's FieldSpan constants, such as CUST_DOB_YYYYMMDD");
        return area(new FixedWidthCodec(charset)).readSpanBytes(field);
    }

    /**
     * This record decomposed into its named field images, in copybook declaration order.
     *
     * <p>This is the shape parity verification consumes: a record is compared
     * <strong>field by field, keyed by field name</strong>, not as one string, and the key set here is
     * the copybook's own field names spelled verbatim. It is also the one place where this type's
     * distinctness from the customer domain's 500-byte record becomes visible at runtime, because the
     * two key sets differ at exactly one entry - {@code CUST-DOB-YYYYMMDD} here against
     * {@code CUST-DOB-YYYY-MM-DD} there.
     *
     * <p>{@code FILLER} is absent, because {@code FILLER} is not a referable COBOL name and so is not
     * a comparable field; its bytes are still present in {@link #encode(Charset)} and readable through
     * {@link #spanImage(FieldSpan, Charset)}.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return an unmodifiable, insertion-ordered map of eighteen copybook field names to their exact
     *         span images
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or
     *                                  if a field image cannot be placed in its span
     */
    public Map<String, String> fieldImages(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return Collections.unmodifiableMap(
                codec.deserialise(LAYOUT, area(codec).toByteArray()));
    }

    // =================================================================================================
    // The three numeric fields, as values.
    //
    // CUSTREC.cpy declares no signed picture, no V-scaled picture and no COMP-3, so these are the only
    // numeric fields in the record and all three are unsigned zoned DISPLAY integers. CUST-ID and
    // CUST-SSN are PIC 9(09) and map to long; CUST-FICO-CREDIT-SCORE is PIC 9(03) and maps to int.
    // There is nothing here to scale and nothing to round, which is why this class holds no BigDecimal
    // and does not import the module's decimal helper.
    //
    // Each accessor takes an explicit Charset, exactly as every other byte-facing method here does, and
    // reads the value out of the encoded record area rather than out of the raw component. A narrow
    // image is therefore interpreted after zero-filling, so "42" in CUST-ID reads back as 42 - which is
    // what COBOL does with a numeric MOVE into a nine-digit receiver. The zero-filled fixed-width
    // character image of each of these fields is the component accessor itself: custId(),
    // custSsn() and custFicoCreditScore().
    // =================================================================================================

    /**
     * {@code CUST-ID} as a value: the nine-digit {@code CUSTFILE} KSDS key.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the key, {@code 0} to {@code 999999999}
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or
     *                                  if the {@code CUST-ID} span does not hold nine digits - a
     *                                  blank or corrupted key is reported rather than silently read as
     *                                  zero
     */
    public int custIdValue(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPic9AsInt(area(codec), CUST_ID);
    }

    /**
     * {@code CUST-SSN} as a value: the nine-digit social security number, exactly as the copybook
     * declares it - {@code PIC 9(09)}, unformatted and unmasked.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the number, {@code 0} to {@code 999999999}
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or
     *                                  if the {@code CUST-SSN} span does not hold nine digits
     */
    public int custSsnValue(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPic9AsInt(area(codec), CUST_SSN);
    }

    /**
     * {@code CUST-FICO-CREDIT-SCORE} as a value: the three-digit score that
     * {@code CBSTM03A.CBL:485} moves onto the statement line with
     * {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE}.
     *
     * <p>Three digits cannot overflow an {@code int}, and no range check is applied beyond that:
     * {@code PIC 9(03)} constrains the field to {@code 000} through {@code 999} and the COBOL asserts
     * nothing further, so neither does this. A score outside the usual FICO band is data, not an error.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the score, {@code 0} to {@code 999}
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or
     *                                  if the {@code CUST-FICO-CREDIT-SCORE} span does not hold three
     *                                  digits
     */
    public int custFicoCreditScoreValue(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPic9AsInt(area(codec), CUST_FICO_CREDIT_SCORE);
    }

    /**
     * A diagnostic rendering that names every field as {@code CUSTREC} spells it and withholds the
     * personal data, per {@link SensitiveDiagnostics}.
     *
     * <p>The override exists because this is a {@code record}, and a record's generated
     * {@code toString} renders every component. This record's components are a complete identity: legal
     * name, three address lines, two telephone numbers, date of birth, social security number,
     * government-issued identifier and an electronic funds transfer account. Inheriting the generated
     * rendering meant that logging one statement-run record disclosed all of it.
     *
     * <p>The treatment matches {@code customer/model/CustomerRecord}, which models the near-identical
     * {@code CVCUS01Y} layout, so the two cannot diverge in what they disclose about the same customer.
     * Credentials are withheld outright; names, addresses, telephone numbers, zip and date of birth
     * report their length only; {@code CUST-ID} is masked to its last four digits; and the state code,
     * country code, primary-card-holder indicator and FICO score stay legible because none identifies a
     * person once the identifier is masked.
     *
     * <p>{@code equals} and {@code hashCode} remain as the record generates them, over every component:
     * they are value semantics, they disclose nothing, and the parity harness compares whole records
     * with them. {@link #fieldImages(Charset)} and {@link #encode(Charset)} still return the real values,
     * because a caller asks for those by name.
     *
     * @return a rendering safe to log, naming all 18 fields
     */
    @Override
    public String toString() {
        return "Stm03CustomerRecord{CUST-ID="
                + SensitiveDiagnostics.maskIdentifier(custId)
                + ", CUST-FIRST-NAME=" + SensitiveDiagnostics.describeText(custFirstName)
                + ", CUST-MIDDLE-NAME=" + SensitiveDiagnostics.describeText(custMiddleName)
                + ", CUST-LAST-NAME=" + SensitiveDiagnostics.describeText(custLastName)
                + ", CUST-ADDR-LINE-1=" + SensitiveDiagnostics.describeText(custAddrLine1)
                + ", CUST-ADDR-LINE-2=" + SensitiveDiagnostics.describeText(custAddrLine2)
                + ", CUST-ADDR-LINE-3=" + SensitiveDiagnostics.describeText(custAddrLine3)
                + ", CUST-ADDR-STATE-CD=[" + custAddrStateCd
                + "], CUST-ADDR-COUNTRY-CD=[" + custAddrCountryCd
                + "], CUST-ADDR-ZIP=" + SensitiveDiagnostics.describeText(custAddrZip)
                + ", CUST-PHONE-NUM-1=" + SensitiveDiagnostics.describeText(custPhoneNum1)
                + ", CUST-PHONE-NUM-2=" + SensitiveDiagnostics.describeText(custPhoneNum2)
                + ", CUST-SSN=" + SensitiveDiagnostics.redacted()
                + ", CUST-GOVT-ISSUED-ID=" + SensitiveDiagnostics.redacted()
                + ", CUST-DOB-YYYYMMDD=" + SensitiveDiagnostics.describeText(custDobYyyymmdd)
                + ", CUST-EFT-ACCOUNT-ID=" + SensitiveDiagnostics.redacted()
                + ", CUST-PRI-CARD-HOLDER-IND=[" + custPriCardHolderInd
                + "], CUST-FICO-CREDIT-SCORE=" + custFicoCreditScore
                + '}';
    }

    /**
     * Builds and populates the record area. Every public method that needs bytes goes through this one
     * private helper, so the write order is stated exactly once.
     *
     * <p>Each component is placed into its span <strong>verbatim</strong>, because the canonical
     * constructor has already put it through that field's own {@code MOVE} rule and it is therefore
     * already exactly {@code field.length()} characters wide. No justification or padding can apply at
     * this point, and re-deriving the image through a picture-specific write would apply the rule twice.
     *
     * <p>This is also what keeps {@code decode} then {@code encode} total. A {@code PIC 9} write demands
     * digits, but the constructor deliberately admits a numeric span that holds none - a group
     * {@code MOVE} such as {@code app/cbl/CBSTM03A.CBL:388} ignores the elementary pictures underneath
     * and copies bytes, so a blank {@code CUSTFILE} record legitimately arrives as 500 spaces. Demanding
     * digits here would leave such a record readable but impossible to write back, which is a one-way
     * door and not a rule COBOL has. The digit requirement belongs to the numeric <em>views</em> -
     * {@link #custIdValue(Charset)}, {@link #custSsnValue(Charset)} and
     * {@link #custFicoCreditScoreValue(Charset)} - and it is enforced there.
     */
    private FixedWidthRecord area(FixedWidthCodec codec) {
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        area.writeSpan(CUST_ID, custId);
        area.writeSpan(CUST_FIRST_NAME, custFirstName);
        area.writeSpan(CUST_MIDDLE_NAME, custMiddleName);
        area.writeSpan(CUST_LAST_NAME, custLastName);
        area.writeSpan(CUST_ADDR_LINE_1, custAddrLine1);
        area.writeSpan(CUST_ADDR_LINE_2, custAddrLine2);
        area.writeSpan(CUST_ADDR_LINE_3, custAddrLine3);
        area.writeSpan(CUST_ADDR_STATE_CD, custAddrStateCd);
        area.writeSpan(CUST_ADDR_COUNTRY_CD, custAddrCountryCd);
        area.writeSpan(CUST_ADDR_ZIP, custAddrZip);
        area.writeSpan(CUST_PHONE_NUM_1, custPhoneNum1);
        area.writeSpan(CUST_PHONE_NUM_2, custPhoneNum2);
        area.writeSpan(CUST_SSN, custSsn);
        area.writeSpan(CUST_GOVT_ISSUED_ID, custGovtIssuedId);
        area.writeSpan(CUST_DOB_YYYYMMDD, custDobYyyymmdd);
        area.writeSpan(CUST_EFT_ACCOUNT_ID, custEftAccountId);
        area.writeSpan(CUST_PRI_CARD_HOLDER_IND, custPriCardHolderInd);
        area.writeSpan(CUST_FICO_CREDIT_SCORE, custFicoCreditScore);
        return area;
    }

}
