package com.vsergeychik.carddemo.user.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 80-byte {@code USRSEC} security-user record, the Java projection of {@code app/cpy/CSUSR01Y.cpy}.
 *
 * <p>Decoding the {@code USRSEC} EBCDIC dataset confirms the outcome: every one of its ten records carries
 * exactly 23 trailing spaces.
 *
 * @param secUsrId {@code SEC-USR-ID PIC X(08)} - the 8-byte primary key, at offset 0
 * @param secUsrFname {@code SEC-USR-FNAME PIC X(20)} - first name, space-padded to 20
 * @param secUsrLname {@code SEC-USR-LNAME PIC X(20)} - last name, space-padded to 20
 * @param secUsrPwd {@code SEC-USR-PWD PIC X(08)} - plaintext password, space-padded to 8
 * @param secUsrType {@code SEC-USR-TYPE PIC X(01)} - user type, {@code 'A'} admin or {@code 'U'} regular,
 *     as {@code COSGN00C} routes on
 * @param secUsrFiller {@code SEC-USR-FILLER PIC X(23)} - the named trailing span, normally 23 spaces
 */
public record SecUserRecord(String secUsrId,
                            String secUsrFname,
                            String secUsrLname,
                            String secUsrPwd,
                            String secUsrType,
                            String secUsrFiller) {
    // Declared here, on the type that owns the copybook, so that the repository never hard-codes a width or
    // a key length of its own.

    /**
     * The declared record width in bytes, from {@code app/cpy/CSUSR01Y.cpy} and corroborated by
     * {@code RECORDSIZE(80,80)} and {@code LRECL=80} in {@code app/jcl/DUSRSECJ.jcl}.
     */
    public static final int RECORD_LENGTH = 80;

    public static final int KEY_OFFSET = 0;

    /**
     * The primary key width in bytes, from {@code KEYS(8,0)}.
     */
    public static final int KEY_LENGTH = 8;

    /**
     * The copybook's group item name, spelled {@code SEC-USER-DATA} with {@code USER} in full - in contrast
     * to the {@code SEC-USR-} prefix of its subordinate items.
     */
    public static final String GROUP_NAME = "SEC-USER-DATA";

    /**
     * COBOL name of {@link #secUsrId()}.
     */
    public static final String FIELD_SEC_USR_ID = "SEC-USR-ID";

    /**
     * COBOL name of {@link #secUsrFname()}.
     */
    public static final String FIELD_SEC_USR_FNAME = "SEC-USR-FNAME";

    /**
     * COBOL name of {@link #secUsrLname()}.
     */
    public static final String FIELD_SEC_USR_LNAME = "SEC-USR-LNAME";

    /**
     * COBOL name of {@link #secUsrPwd()}.
     */
    public static final String FIELD_SEC_USR_PWD = "SEC-USR-PWD";

    /**
     * COBOL name of {@link #secUsrType()}.
     */
    public static final String FIELD_SEC_USR_TYPE = "SEC-USR-TYPE";

    /**
     * COBOL name of {@link #secUsrFiller()}.
     */
    public static final String FIELD_SEC_USR_FILLER = "SEC-USR-FILLER";

    /**
     * Absolute 0-based offset of {@code SEC-USR-ID}.
     */
    public static final int SEC_USR_ID_OFFSET = 0;

    /**
     * Declared width of {@code SEC-USR-ID PIC X(08)}.
     */
    public static final int SEC_USR_ID_LENGTH = 8;

    /**
     * Absolute 0-based offset of {@code SEC-USR-FNAME}.
     */
    public static final int SEC_USR_FNAME_OFFSET = 8;

    /**
     * Declared width of {@code SEC-USR-FNAME PIC X(20)}.
     */
    public static final int SEC_USR_FNAME_LENGTH = 20;

    /**
     * Absolute 0-based offset of {@code SEC-USR-LNAME}.
     */
    public static final int SEC_USR_LNAME_OFFSET = 28;

    /**
     * Declared width of {@code SEC-USR-LNAME PIC X(20)}.
     */
    public static final int SEC_USR_LNAME_LENGTH = 20;

    /**
     * Absolute 0-based offset of {@code SEC-USR-PWD}.
     */
    public static final int SEC_USR_PWD_OFFSET = 48;

    /**
     * Declared width of {@code SEC-USR-PWD PIC X(08)}.
     */
    public static final int SEC_USR_PWD_LENGTH = 8;

    /**
     * Absolute 0-based offset of {@code SEC-USR-TYPE}.
     */
    public static final int SEC_USR_TYPE_OFFSET = 56;

    /**
     * Declared width of {@code SEC-USR-TYPE PIC X(01)}.
     */
    public static final int SEC_USR_TYPE_LENGTH = 1;

    /**
     * Absolute 0-based offset of {@code SEC-USR-FILLER}.
     */
    public static final int SEC_USR_FILLER_OFFSET = 57;

    /**
     * Declared width of {@code SEC-USR-FILLER PIC X(23)}.
     */
    public static final int SEC_USR_FILLER_LENGTH = 23;

    /**
     * Descriptor for {@code SEC-USR-ID PIC X(08)} at offset 0.
     */
    public static final FieldSpan SPAN_SEC_USR_ID =
            FieldSpan.alphanumeric(FIELD_SEC_USR_ID, SEC_USR_ID_OFFSET, SEC_USR_ID_LENGTH);

    /**
     * Descriptor for {@code SEC-USR-FNAME PIC X(20)} at offset 8.
     */
    public static final FieldSpan SPAN_SEC_USR_FNAME =
            FieldSpan.alphanumeric(FIELD_SEC_USR_FNAME, SEC_USR_FNAME_OFFSET, SEC_USR_FNAME_LENGTH);

    /**
     * Descriptor for {@code SEC-USR-LNAME PIC X(20)} at offset 28.
     */
    public static final FieldSpan SPAN_SEC_USR_LNAME =
            FieldSpan.alphanumeric(FIELD_SEC_USR_LNAME, SEC_USR_LNAME_OFFSET, SEC_USR_LNAME_LENGTH);

    /**
     * Descriptor for {@code SEC-USR-PWD PIC X(08)} at offset 48.
     */
    public static final FieldSpan SPAN_SEC_USR_PWD =
            FieldSpan.alphanumeric(FIELD_SEC_USR_PWD, SEC_USR_PWD_OFFSET, SEC_USR_PWD_LENGTH);

    /**
     * Descriptor for {@code SEC-USR-TYPE PIC X(01)} at offset 56.
     */
    public static final FieldSpan SPAN_SEC_USR_TYPE =
            FieldSpan.alphanumeric(FIELD_SEC_USR_TYPE, SEC_USR_TYPE_OFFSET, SEC_USR_TYPE_LENGTH);

    /**
     * Descriptor for {@code SEC-USR-FILLER PIC X(23)} at offset 57.
     */
    public static final FieldSpan SPAN_SEC_USR_FILLER =
            FieldSpan.alphanumeric(FIELD_SEC_USR_FILLER, SEC_USR_FILLER_OFFSET,
                    SEC_USR_FILLER_LENGTH);

    /**
     * The six descriptors in copybook declaration order, {@code SEC-USR-FILLER} included.
     */
    public static final List<FieldSpan> SPANS = List.of(
            SPAN_SEC_USR_ID,
            SPAN_SEC_USR_FNAME,
            SPAN_SEC_USR_LNAME,
            SPAN_SEC_USR_PWD,
            SPAN_SEC_USR_TYPE,
            SPAN_SEC_USR_FILLER);

    /**
     * The validated record layout, and the machine-checked proof of this record's geometry.
     */
    public static final RecordLayout LAYOUT = new RecordLayout(RECORD_LENGTH, SPANS);

    private static final String PASSWORD_PLACEHOLDER = "<omitted>";

    /**
     * Validates that every component is present and is exactly its declared width, so the class invariant
     * "an accessor always returns the full fixed-width value" holds for every instance however it was
     * built.
     *
     * <p>Callers holding values of some other width should use
     * {@link #of(String, String, String, String, String, Charset)}, which applies the COBOL alphanumeric
     * {@code MOVE} rule deliberately.
     */
    public SecUserRecord {
        secUsrId = requireDeclaredWidth(secUsrId, SPAN_SEC_USR_ID);
        secUsrFname = requireDeclaredWidth(secUsrFname, SPAN_SEC_USR_FNAME);
        secUsrLname = requireDeclaredWidth(secUsrLname, SPAN_SEC_USR_LNAME);
        secUsrPwd = requireDeclaredWidth(secUsrPwd, SPAN_SEC_USR_PWD);
        secUsrType = requireDeclaredWidth(secUsrType, SPAN_SEC_USR_TYPE);
        secUsrFiller = requireDeclaredWidth(secUsrFiller, SPAN_SEC_USR_FILLER);
    }

    /**
     * Builds a record from values of any width by applying the COBOL alphanumeric {@code MOVE} rule to
     * each, and sets {@code SEC-USR-FILLER} to its 23 spaces.
     *
     * @param secUsrId the user id; padded or right-truncated to 8
     * @param secUsrFname the first name; padded or right-truncated to 20
     * @param secUsrLname the last name; padded or right-truncated to 20
     * @param secUsrPwd the plaintext password; padded or right-truncated to 8
     * @param secUsrType the user type; padded or right-truncated to 1
     * @param charset the code page, supplied explicitly and never defaulted
     * @return a record whose every field is exactly its declared width
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the characters
     *     a fixed-width record relies on
     */
    public static SecUserRecord of(String secUsrId,
                                   String secUsrFname,
                                   String secUsrLname,
                                   String secUsrPwd,
                                   String secUsrType,
                                   Charset charset) {
        return of(secUsrId, secUsrFname, secUsrLname, secUsrPwd, secUsrType, defaultFiller(),
                new FixedWidthCodec(charset));
    }

    /**
     * Builds a record from values of any width, including an explicit {@code SEC-USR-FILLER}, using an
     * already-constructed codec.
     *
     * @param secUsrId the user id; padded or right-truncated to 8
     * @param secUsrFname the first name; padded or right-truncated to 20
     * @param secUsrLname the last name; padded or right-truncated to 20
     * @param secUsrPwd the plaintext password; padded or right-truncated to 8
     * @param secUsrType the user type; padded or right-truncated to 1
     * @param secUsrFiller the trailing named span; padded or right-truncated to 23
     * @param codec the charset-bound codec that applies the {@code PIC X} move rule
     * @return a record whose every field is exactly its declared width
     * @throws NullPointerException if any argument is {@code null}
     */
    public static SecUserRecord of(String secUsrId,
                                   String secUsrFname,
                                   String secUsrLname,
                                   String secUsrPwd,
                                   String secUsrType,
                                   String secUsrFiller,
                                   FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC X move rule; it carries "
                + "the code page, which is never assumed");
        return new SecUserRecord(
                movePicX(codec, secUsrId, SPAN_SEC_USR_ID),
                movePicX(codec, secUsrFname, SPAN_SEC_USR_FNAME),
                movePicX(codec, secUsrLname, SPAN_SEC_USR_LNAME),
                movePicX(codec, secUsrPwd, SPAN_SEC_USR_PWD),
                movePicX(codec, secUsrType, SPAN_SEC_USR_TYPE),
                movePicX(codec, secUsrFiller, SPAN_SEC_USR_FILLER));
    }

    /**
     * An all-spaces record of the declared widths, the equivalent of a freshly initialised
     * {@code SEC-USER-DATA} area.
     *
     * @return a record whose six fields are 8, 20, 20, 8, 1 and 23 spaces respectively
     */
    public static SecUserRecord blank() {
        return new SecUserRecord(
                spaces(SEC_USR_ID_LENGTH),
                spaces(SEC_USR_FNAME_LENGTH),
                spaces(SEC_USR_LNAME_LENGTH),
                spaces(SEC_USR_PWD_LENGTH),
                spaces(SEC_USR_TYPE_LENGTH),
                defaultFiller());
    }

    /**
     * Serialises this record to exactly {@link #RECORD_LENGTH} bytes.
     *
     * @param record the record to serialise
     * @param charset the code page, supplied explicitly and never defaulted
     * @return exactly {@link #RECORD_LENGTH} bytes in {@code charset}
     * @throws NullPointerException if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the characters
     *     a fixed-width record relies on
     */
    public static byte[] encode(SecUserRecord record, Charset charset) {
        return encode(record, new FixedWidthCodec(charset));
    }

    /**
     * Serialises this record to exactly {@link #RECORD_LENGTH} bytes using an already-constructed codec,
     * for a repository that owns one.
     *
     * @param record the record to serialise
     * @param codec the charset-bound codec
     * @return exactly {@link #RECORD_LENGTH} bytes in the codec's code page
     * @throws NullPointerException if {@code record} or {@code codec} is {@code null}
     */
    public static byte[] encode(SecUserRecord record, FixedWidthCodec codec) {
        Objects.requireNonNull(record, "A record is required to serialise; call blank() for an "
                + "all-spaces " + GROUP_NAME + " area");
        Objects.requireNonNull(codec, "A codec is required to serialise a record; it carries the "
                + "code page, which is never assumed");
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, SPAN_SEC_USR_ID, record.secUsrId());
        codec.writePicX(area, SPAN_SEC_USR_FNAME, record.secUsrFname());
        codec.writePicX(area, SPAN_SEC_USR_LNAME, record.secUsrLname());
        codec.writePicX(area, SPAN_SEC_USR_PWD, record.secUsrPwd());
        codec.writePicX(area, SPAN_SEC_USR_TYPE, record.secUsrType());
        codec.writePicX(area, SPAN_SEC_USR_FILLER, record.secUsrFiller());
        return area.toByteArray();
    }

    /**
     * Deserialises exactly {@link #RECORD_LENGTH} bytes into a record, reading every field untrimmed.
     *
     * @param bytes exactly {@link #RECORD_LENGTH} bytes
     * @param charset the code page, supplied explicitly and never defaulted
     * @return the decoded record, every field at its full declared width
     * @throws NullPointerException if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #RECORD_LENGTH}, or
     *     {@code charset} is not a single-byte code page for the characters a fixed-width record relies on
     */
    public static SecUserRecord decode(byte[] bytes, Charset charset) {
        return decode(bytes, new FixedWidthCodec(charset));
    }

    /**
     * Deserialises exactly {@link #RECORD_LENGTH} bytes using an already-constructed codec, for a
     * repository that owns one.
     *
     * @param bytes exactly {@link #RECORD_LENGTH} bytes
     * @param codec the charset-bound codec
     * @return the decoded record, every field at its full declared width and untrimmed
     * @throws NullPointerException if {@code bytes} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #RECORD_LENGTH}
     */
    public static SecUserRecord decode(byte[] bytes, FixedWidthCodec codec) {
        Objects.requireNonNull(bytes, "Record bytes are required to decode a " + GROUP_NAME
                + " record; call blank() for an all-spaces record instead");
        Objects.requireNonNull(codec, "A codec is required to decode a record; it carries the code "
                + "page, which is never assumed");
        if (bytes.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("Supplied " + bytes.length + " byte(s) for "
                    + GROUP_NAME + ", which app/cpy/CSUSR01Y.cpy declares as exactly " + RECORD_LENGTH
                    + " byte(s). A fixed-width record is never partially decoded: a row that is "
                    + "short because the trailing " + FIELD_SEC_USR_FILLER + " X("
                    + SEC_USR_FILLER_LENGTH + ") is absent from the source data - as in the "
                    + "57-character seed cards of app/jcl/DUSRSECJ.jcl - must be right-padded to "
                    + RECORD_LENGTH + " before it reaches here");
        }
        FixedWidthRecord area = codec.wrap(bytes, LAYOUT);
        return new SecUserRecord(
                codec.readPicX(area, SPAN_SEC_USR_ID),
                codec.readPicX(area, SPAN_SEC_USR_FNAME),
                codec.readPicX(area, SPAN_SEC_USR_LNAME),
                codec.readPicX(area, SPAN_SEC_USR_PWD),
                codec.readPicX(area, SPAN_SEC_USR_TYPE),
                codec.readPicX(area, SPAN_SEC_USR_FILLER));
    }

    // The parity differ compares field by field BY NAME, so it needs to reach a value through the
    // copybook's own spelling without knowing this type's accessors.

    /**
     * Every field keyed by its COBOL name, in copybook declaration order, {@code SEC-USR-FILLER} included.
     *
     * @return an unmodifiable, insertion-ordered map of all six COBOL field names to their images
     */
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        images.put(FIELD_SEC_USR_ID, secUsrId);
        images.put(FIELD_SEC_USR_FNAME, secUsrFname);
        images.put(FIELD_SEC_USR_LNAME, secUsrLname);
        images.put(FIELD_SEC_USR_PWD, secUsrPwd);
        images.put(FIELD_SEC_USR_TYPE, secUsrType);
        images.put(FIELD_SEC_USR_FILLER, secUsrFiller);
        // Collections.unmodifiableMap over a LinkedHashMap, deliberately NOT Map.copyOf: the copy factory
        // returns an unordered map, which would silently scramble copybook declaration order and leave a
        // field-by-field differ reporting its differences in an arbitrary sequence.
        return Collections.unmodifiableMap(images);
    }

    /**
     * One field's raw fixed-width image, looked up by its COBOL name.
     *
     * @param fieldName the COBOL item name, verbatim and case-sensitive, for example
     *     {@code "SEC-USR-FNAME"}
     * @return the field's untrimmed image, exactly its declared width
     * @throws NullPointerException if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is not one of this record's six fields
     */
    public String image(String fieldName) {
        Objects.requireNonNull(fieldName, "A field name is required to read a " + GROUP_NAME
                + " field by name");
        Map<String, String> images = fieldImages();
        String found = images.get(fieldName);
        if (found == null) {
            throw new IllegalArgumentException("'" + fieldName + "' is not a field of " + GROUP_NAME
                    + "; app/cpy/CSUSR01Y.cpy declares " + images.keySet()
                    + " and names are case-sensitive");
        }
        return found;
    }

    /**
     * The VSAM primary key of this record, which is {@code SEC-USR-ID} in full.
     *
     * @return the 8-character key image, space-padded and untrimmed
     */
    public String key() {
        return secUsrId;
    }

    /**
     * A diagnostic rendering that never includes the password, and never the user's name either.
     *
     * <p>Two fields declared {@code PIC X(20)} that turn out to hold nineteen characters is a real defect
     * and the shape is what finds it; who the person is has never once been needed to diagnose one.
     *
     * @return a single-line description of the record with the password and both names withheld
     */
    @Override
    public String toString() {
        return GROUP_NAME + "["
                + FIELD_SEC_USR_ID + "='" + SensitiveDiagnostics.plain(secUsrId) + "', "
                + FIELD_SEC_USR_FNAME + "=" + SensitiveDiagnostics.describeText(secUsrFname) + ", "
                + FIELD_SEC_USR_LNAME + "=" + SensitiveDiagnostics.describeText(secUsrLname) + ", "
                + FIELD_SEC_USR_PWD + "=" + PASSWORD_PLACEHOLDER + ", "
                + FIELD_SEC_USR_TYPE + "='" + SensitiveDiagnostics.plain(secUsrType) + "', "
                + FIELD_SEC_USR_FILLER + ".length=" + secUsrFiller.length()
                + "]";
    }

    private static String requireDeclaredWidth(String value, FieldSpan field) {
        Objects.requireNonNull(value, "Field '" + field.name() + "' of " + GROUP_NAME + " is "
                + "required; a COBOL PIC X field is never absent, so move SPACES to blank it");
        if (value.length() != field.length()) {
            throw new IllegalArgumentException("Field '" + field.name() + "' of " + GROUP_NAME
                    + " is declared PIC X(" + field.length() + ") but was given " + value.length()
                    + " character(s). This type holds fields at exactly their declared width and "
                    + "never silently pads or truncates, so that an accessor always returns the "
                    + "full space-padded value the parity differ compares. Use of(...) to apply the "
                    + "COBOL alphanumeric MOVE rule deliberately");
        }
        return value;
    }

    private static String movePicX(FixedWidthCodec codec, String value, FieldSpan field) {
        Objects.requireNonNull(value, "A sending value is required for field '" + field.name()
                + "' of " + GROUP_NAME + "; move an empty string or SPACES to blank it");
        return codec.movePicX(value, field.length());
    }

    /**
     * The 23 spaces a {@code SEC-USR-FILLER} that was never assigned holds.
     */
    private static String defaultFiller() {
        return spaces(SEC_USR_FILLER_LENGTH);
    }

    private static String spaces(int count) {
        return " ".repeat(count);
    }
}
