package com.vsergeychik.carddemo.common;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/**
 * The single authority on how a dataset's record image crosses JDBC: as characters, or as bytes.
 *
 * <p>A deployment whose records can contain {@code X'25'} - a control byte, which none of this estate's
 * twelve datasets is expected to hold in a {@code PIC X} or zoned {@code PIC 9} field - must configure
 * {@link #BINARY}, which has no conversion to fold anything.
 */
public enum RecordImageForm {
    /**
     * The record image is a character value in the configured dataset code page, read with
     * {@link ResultSet#getString(int)} and bound with {@link PreparedStatement#setString(int, String)}.
     *
     * <p>A deployment choosing this form must guarantee a bytewise collation - binary or code-point - on the
     * record-image column, because a keyed read composes a positional {@code LIKE} over that column and a
     * browse composes {@code ORDER BY} it, which makes the backend's collation the KSDS key semantics: a
     * case-insensitive, blank-folding, accent-folding or linguistic collation answers wrongly rather than
     * failing. {@link #BINARY} has no collation to get wrong and is the correct choice wherever that
     * guarantee is unknown; nothing in this module can check it.
     */
    CHARACTER {
        @Override
        public byte[] readImage(ResultSet resultSet, int columnIndex, Charset charset)
                throws SQLException {
            requireReadArguments(resultSet, columnIndex, charset);
            String image = resultSet.getString(columnIndex);
            return image == null
                    ? null
                    : FixedWidthRecord.encodeStrictly(image, charset, "a stored record image");
        }

        @Override
        public void bindImage(PreparedStatement statement, int parameterIndex, byte[] image,
                              Charset charset) throws SQLException {
            requireBindArguments(statement, parameterIndex, image, charset);
            statement.setString(parameterIndex,
                    FixedWidthRecord.decodeText(image, charset, "a record image being written"));
        }

        @Override
        public void bindOperand(PreparedStatement statement, int parameterIndex, String operand,
                                Charset charset) throws SQLException {
            requireOperandArguments(statement, parameterIndex, operand, charset);
            statement.setString(parameterIndex, operand);
        }

        @Override
        public Object imageParameter(byte[] image, Charset charset) {
            requireImage(image);
            requireCharset(charset);
            return FixedWidthRecord.decodeText(image, charset, "a record image being written");
        }

        @Override
        public Object operandParameter(String operand, Charset charset) {
            requireOperand(operand);
            requireCharset(charset);
            return operand;
        }
    },

    /**
     * The record image is a binary value: the stored bytes, unconverted.
     */
    BINARY {
        @Override
        public byte[] readImage(ResultSet resultSet, int columnIndex, Charset charset)
                throws SQLException {
            requireReadArguments(resultSet, columnIndex, charset);
            return resultSet.getBytes(columnIndex);
        }

        @Override
        public void bindImage(PreparedStatement statement, int parameterIndex, byte[] image,
                              Charset charset) throws SQLException {
            requireBindArguments(statement, parameterIndex, image, charset);
            statement.setBytes(parameterIndex, image.clone());
        }

        @Override
        public void bindOperand(PreparedStatement statement, int parameterIndex, String operand,
                                Charset charset) throws SQLException {
            requireOperandArguments(statement, parameterIndex, operand, charset);
            statement.setBytes(parameterIndex,
                    FixedWidthRecord.encodeStrictly(operand, charset, "a comparison operand"));
        }

        @Override
        public Object imageParameter(byte[] image, Charset charset) {
            requireImage(image);
            requireCharset(charset);
            return image.clone();
        }

        @Override
        public Object operandParameter(String operand, Charset charset) {
            requireOperand(operand);
            requireCharset(charset);
            return FixedWidthRecord.encodeStrictly(operand, charset, "a comparison operand");
        }
    };

    public static final String FORM_PROPERTY = "carddemo.record-image.form";

    public static final String FORM_BEAN_NAME = "carddemoRecordImageForm";

    /**
     * Reads one row's whole record image, exactly as stored.
     *
     * @param resultSet the row, already positioned
     * @param columnIndex the 1-based ordinal of the record-image column, which is
     *     {@link DatasetRelation#RECORD_IMAGE_COLUMN_INDEX} for every dataset in this module
     * @param charset the configured dataset code page
     * @return the stored bytes, or {@code null} when the column holds no value - a condition every caller
     *     classifies rather than ignores
     * @throws SQLException if the driver cannot supply the column
     * @throws NullPointerException if {@code resultSet} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code columnIndex} is not positive
     */
    public abstract byte[] readImage(ResultSet resultSet, int columnIndex, Charset charset)
            throws SQLException;

    /**
     * Binds a whole record image as one statement parameter.
     *
     * @param statement the statement being prepared
     * @param parameterIndex the 1-based parameter ordinal
     * @param image the record's bytes, already at its declared width
     * @param charset the configured dataset code page
     * @throws SQLException if the driver refuses the value
     * @throws NullPointerException if {@code statement}, {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code parameterIndex} is not positive
     */
    public abstract void bindImage(PreparedStatement statement, int parameterIndex, byte[] image,
                                   Charset charset) throws SQLException;

    /**
     * Binds a comparison operand - a keyed {@code LIKE} pattern, or the image a browse advances past - in
     * the same representation as the image it is compared with.
     *
     * @param statement the statement being prepared
     * @param parameterIndex the 1-based parameter ordinal
     * @param operand the pattern or image to compare against
     * @param charset the configured dataset code page
     * @throws SQLException if the driver refuses the value
     * @throws NullPointerException if {@code statement}, {@code operand} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code parameterIndex} is not positive
     */
    public abstract void bindOperand(PreparedStatement statement, int parameterIndex, String operand,
                                     Charset charset) throws SQLException;

    /**
     * The record image as the object a template-managed parameter list carries.
     *
     * @param image the record's bytes
     * @param charset the configured dataset code page
     * @return the parameter object: a {@link String} under {@link #CHARACTER}, a defensive copy of the
     *     bytes under {@link #BINARY}
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     */
    public abstract Object imageParameter(byte[] image, Charset charset);

    /**
     * The comparison operand as the object a template-managed parameter list carries.
     *
     * @param operand the pattern or image to compare against
     * @param charset the configured dataset code page
     * @return the parameter object: the text itself under {@link #CHARACTER}, its bytes under
     *     {@link #BINARY}
     * @throws NullPointerException if {@code operand} or {@code charset} is {@code null}
     */
    public abstract Object operandParameter(String operand, Charset charset);

    /**
     * Resolves the configured name, accepting exactly the two representations and nothing else.
     *
     * @param configured the configured value
     * @return the representation it names
     * @throws IllegalArgumentException if {@code configured} is {@code null}, blank, or not one of the two
     *     accepted names
     */
    public static RecordImageForm parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Property '" + FORM_PROPERTY + "' is required and was "
                    + (configured == null ? "not set" : "blank")
                    + ". It states how the deployment's driver presents a dataset's record image, and "
                    + "there is no safe default: reading a record through a representation nobody chose "
                    + "lets the driver pick a code-page conversion, which corrupts every record "
                    + "silently. Set it to " + CHARACTER.name() + " or " + BINARY.name() + ".");
        }
        String candidate = configured.strip().toUpperCase(Locale.ROOT);
        for (RecordImageForm form : values()) {
            if (form.name().equals(candidate)) {
                return form;
            }
        }
        throw new IllegalArgumentException("Property '" + FORM_PROPERTY + "' is '" + configured
                + "', which names no record-image representation. It must be " + CHARACTER.name()
                + " - the record image is a character column in the configured dataset code page - or "
                + BINARY.name() + " - the record image is a binary column carrying the stored bytes "
                + "unconverted.");
    }

    /**
     * Checks that a code page can carry a record image at all: one byte per character, and the pad and sign
     * characters intact.
     *
     * @param charset the code page to check
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if the charset is not single-byte, or cannot carry the pad and sign
     *     characters this module depends on
     */
    public static void requireSingleByteCodePage(Charset charset) {
        requireCharset(charset);
        byte[] every = new byte[BYTE_VALUE_COUNT];
        for (int value = 0; value < BYTE_VALUE_COUNT; value++) {
            every[value] = (byte) value;
        }
        byte[] roundTripped = new String(every, charset).getBytes(charset);
        if (roundTripped.length != every.length) {
            throw new IllegalArgumentException("Code page '" + charset.name() + "' cannot carry a record "
                    + "image: encoding its own decoding of all " + BYTE_VALUE_COUNT + " byte values "
                    + "yielded " + roundTripped.length + " byte(s), so it is not a single-byte code "
                    + "page. A fixed-width record is addressed by absolute byte offset and its numeric "
                    + "fields carry their sign as a zoned overpunch, both of which require one byte per "
                    + "character. IBM037 for the EBCDIC datasets and US-ASCII for the text fixtures both "
                    + "qualify.");
        }
        for (char character : STRUCTURAL_CHARACTERS.toCharArray()) {
            byte[] encoded = String.valueOf(character).getBytes(charset);
            if (encoded.length != 1
                    || new String(encoded, charset).charAt(0) != character) {
                throw new IllegalArgumentException("Code page '" + charset.name() + "' cannot carry the "
                        + "character '" + character + "' without changing it. That character is one this "
                        + "module writes padding or a zoned sign overpunch with - '{' carries +0 and '}' "
                        + "carries -0 - so a code page that alters it alters stored values, and it does "
                        + "so with no error to report.");
            }
        }
    }

    private static final String STRUCTURAL_CHARACTERS =
            " 0123456789{ABCDEFGHI}JKLMNOPQR";

    private static final int BYTE_VALUE_COUNT = 256;

    private static void requireCharset(Charset charset) {
        Objects.requireNonNull(charset, "A dataset code page is required: a record image is bytes in a "
                + "specific code page, and this module never falls back to the platform default");
    }

    private static void requireImage(byte[] image) {
        Objects.requireNonNull(image, "A record image is required to bind one; an absent record is an "
                + "end-of-file outcome, not a value to write");
    }

    private static void requireOperand(String operand) {
        Objects.requireNonNull(operand, "A comparison operand is required; a keyed predicate is composed "
                + "from a key of its declared width and never from nothing");
    }

    private static void requirePositiveOrdinal(int ordinal, String subject) {
        if (ordinal < 1) {
            throw new IllegalArgumentException("A JDBC " + subject + " ordinal is 1-based, so " + ordinal
                    + " addresses nothing");
        }
    }

    private static void requireReadArguments(ResultSet resultSet, int columnIndex, Charset charset) {
        Objects.requireNonNull(resultSet, "A result set is required to read a record image from");
        requirePositiveOrdinal(columnIndex, "column");
        requireCharset(charset);
    }

    private static void requireBindArguments(PreparedStatement statement, int parameterIndex,
                                             byte[] image, Charset charset) {
        Objects.requireNonNull(statement, "A prepared statement is required to bind a record image to");
        requirePositiveOrdinal(parameterIndex, "parameter");
        requireImage(image);
        requireCharset(charset);
    }

    private static void requireOperandArguments(PreparedStatement statement, int parameterIndex,
                                                String operand, Charset charset) {
        Objects.requireNonNull(statement, "A prepared statement is required to bind an operand to");
        requirePositiveOrdinal(parameterIndex, "parameter");
        requireOperand(operand);
        requireCharset(charset);
    }
}
