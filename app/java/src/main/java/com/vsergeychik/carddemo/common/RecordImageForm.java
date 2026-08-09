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
 * <h2>Why this exists</h2>
 * A VSAM record is a byte string. {@link DatasetRelation} settles how a dataset is <em>addressed</em> -
 * one relation, whose column {@value DatasetRelation#RECORD_IMAGE_COLUMN_INDEX} carries the whole record
 * image - but addressing a column says nothing about what JDBC type that column has, and that is a
 * separate decision with the same power to corrupt every record. Before this class the decision was made
 * six times, differently: the account master read {@code getString} and bound {@code setString}, the card
 * file read {@code getBytes} and fell back to {@code getString} but bound {@code setString}, the
 * cross-reference and the date-parameter reader read {@code getString}, and both statement writers bound
 * {@code setBytes}. Those cannot all be right about one deployment. Whichever is wrong is wrong
 * <em>silently</em>: a driver asked for a character value converts the stored bytes through a code page
 * of its own choosing, and a byte-parity migration that hands its record images to a driver's default
 * conversion has given away the only thing it was built to prove.
 *
 * <p>So the representation is stated once, in configuration, and every read, every write and every
 * comparison operand goes through this type. There is no second opinion and no per-class fallback - a
 * fallback is what let the card file read bytes from a column it wrote characters to.
 *
 * <h2>Why it is configuration and not a constant</h2>
 * Because it is a property of the deployment's driver, not of this module. The prompt mandates JDBC to
 * the existing VSAM backend and names no driver; the technical plan records that the data-access driver
 * is a deployment-time input and that production connectivity cannot be exercised from this build
 * (residual risk R-E). A gateway that presents a KSDS as a character column and one that presents it as
 * a binary column are both real, and both are reachable with the same Java. What must never happen is
 * for the choice to be implicit, so {@link #parse(String)} accepts exactly the two names, rejects
 * everything else by name, and the property that supplies it carries <strong>no default</strong> -
 * a deployment that never stated its representation fails at startup instead of corrupting records.
 *
 * <h2>What "byte-exact" means here, and why both forms achieve it</h2>
 * {@link #BINARY} is exact by construction: the bytes stored are the bytes returned.
 *
 * <p>{@link #CHARACTER} is exact because every code page this module supports is a <em>single-byte</em>
 * one - {@code IBM037} for the mainframe datasets, {@code US-ASCII} for the fixtures - so a record's
 * character form and its byte form have the same length and correspond one for one over the bytes that
 * code page assigns. Every conversion goes through {@link FixedWidthRecord}, which reports an unmappable
 * byte rather than substituting for it, so a byte the configured code page does not assign fails loudly
 * at the read that meets it rather than arriving changed.
 *
 * <h2>One known limit of the character form, and what to do about it</h2>
 * There is exactly one byte value that the character form cannot carry <em>and</em> cannot report:
 * {@code X'25'}, the EBCDIC line feed. The JDK's {@code IBM037} charmap decodes it to {@code U+000A} and
 * encodes {@code U+000A} back to {@code X'15'}, the EBCDIC next-line, so a record containing it would come
 * back with that one byte changed and no exception anywhere - the character is mappable in both
 * directions, just not to itself. Every other one of the 256 byte values round-trips under
 * {@code IBM037}, and this is measured rather than assumed: {@code RecordImageFormTest} asserts both the
 * 255 that survive and the one that does not.
 *
 * <p>The consequence is a deployment decision rather than a defect to code around, because the fold
 * happens inside the driver's own bytes-to-text conversion and Java never sees the original byte. A
 * deployment whose records can contain {@code X'25'} - a control byte, which none of this estate's twelve
 * datasets is expected to hold in a {@code PIC X} or zoned {@code PIC 9} field - must configure
 * {@link #BINARY}, which has no conversion to fold anything. That option existing is most of the reason
 * this type has two constants rather than one. That is a real property and not an assumption: it is what
 * makes a zoned {@code DISPLAY} sign overpunch survive the round trip, {@code X'C0'} being the
 * {@code '{'} that carries {@code +0} and {@code X'D0'} the {@code '}'} that carries {@code -0} in
 * {@code IBM037}. {@link #requireSingleByteCodePage(Charset)} checks what makes that true - one byte per
 * character, and the pad and sign characters intact - rather than trusting it, because a multi-byte code
 * page would not even preserve a record's length and one that substituted for {@code '{'} would change a
 * sign silently.
 *
 * <h2>Comparison and ordering</h2>
 * A key predicate is an escaped {@code LIKE} pattern and a browse advance is a {@code >}, {@code <} or
 * {@code >=} against the same column ({@link DatasetRelation}). Those operands are record-image operands
 * and are therefore bound in the same representation as the image itself, through
 * {@link #bindOperand(PreparedStatement, int, String, Charset)}. Binding an image as bytes while
 * comparing it against a character operand is precisely the incompatibility this class removes: the
 * comparison would then run in the driver's collation for a type the column does not have.
 *
 * <p>The ordering <em>clause</em> stays where it already is, in {@link DatasetRelation}, which names the
 * one column every dataset is ordered by. What this class contributes to ordering is that the column
 * being ordered holds one representation rather than two, so ascending record-image order is ascending
 * key order for the same reason in every dataset.
 *
 * <h2>Design constraints observed</h2>
 * An enum, so the set of representations is closed and exhaustively switchable. Pure JDK - {@code java.sql}
 * and {@code java.nio.charset} only - with no Spring import, because everything in {@code common/} is
 * reachable from the web side, the batch side and the copybook models alike. Stateless, with no mutable
 * static field (practice B9, gate G53), so one instance is safely shared by every repository. Nothing
 * here knows a dataset name, a record width or a column name: those belong to configuration and to
 * {@link DatasetRelation}, and this type is about JDBC types alone.
 *
 * @see DatasetRelation#RECORD_IMAGE_COLUMN_INDEX
 */
public enum RecordImageForm {

    /**
     * The record image is a <strong>character</strong> value in the configured dataset code page.
     *
     * <p>Read with {@link ResultSet#getString(int)} and bound with
     * {@link PreparedStatement#setString(int, String)}. The bytes are recovered by encoding the returned
     * text in the dataset charset, which is exact because that charset is total and single-byte - see
     * {@link #requireSingleByteCodePage(Charset)}.
     *
     * <p>This is the representation a gateway that surfaces a KSDS as a {@code CHAR}/{@code VARCHAR}
     * column presents, and it is the one the in-memory relations this module is tested against use.
     *
     * <h2>The deployment contract this constant carries: a bytewise collation</h2>
     * <p><strong>A deployment choosing this form must guarantee, and document, that the record-image
     * column's collation is bytewise - binary or code-point - in the configured dataset code page.</strong>
     * Reading the column is not the only thing done with it: a keyed read composes a positional
     * {@code LIKE} over it - one {@code _} wildcard per byte before the key, the key's escaped bytes,
     * then {@code %} - and a browse composes {@code ORDER BY} it
     * ({@link DatasetRelation}), and a COBOL KSDS matches a key by its <em>bytes</em> and returns records
     * in ascending order of those bytes. Under this form both of those become the backend's own character
     * comparison, so the backend's collation <em>is</em> the key semantics.
     *
     * <p>A collation that is not bytewise does not fail; it answers wrongly, in ways nothing reports:
     * <ul>
     *   <li>case-insensitive - card number {@code 'A1'} and {@code 'a1'} become one key;</li>
     *   <li>trailing-blank equivalent - an eight-character user id matches a shorter one;</li>
     *   <li>accent- or width-folding - two distinct stored keys become one, and a folding in which one
     *       character stands for two breaks the byte offsets the {@code _} run encodes;</li>
     *   <li>linguistic ordering - {@code 'a'} sorts before {@code 'B'}, so a browse returns records in an
     *       order the COBOL never produces and every page boundary moves with it.</li>
     * </ul>
     *
     * <p>{@link #BINARY} exists partly for this: a binary column has no collation to get wrong, because
     * its equality and its ordering <em>are</em> its bytes'. It is therefore the correct choice for any
     * byte-addressed dataset whose backend collation is unknown or outside the deployment's control.
     *
     * <p>Nothing in this module can check the guarantee - there is no production connectivity to
     * interrogate (residual risk R-E) - so it is written down at each of the three places the decision is
     * visible: here, on {@code DataSourceConfig.carddemoRecordImageForm}, and beside
     * {@value #FORM_PROPERTY} in {@code application.yml}.
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
     * The record image is a <strong>binary</strong> value: the stored bytes, unconverted.
     *
     * <p>Read with {@link ResultSet#getBytes(int)} and bound with
     * {@link PreparedStatement#setBytes(int, byte[])}, so no code-page conversion happens in the driver
     * at all. A comparison operand is a record-image operand and is therefore encoded in the dataset
     * charset before it is bound, which keeps the operand and the column in one representation.
     *
     * <p>This is the representation a gateway that surfaces a KSDS as a {@code BINARY}/{@code VARBINARY}
     * column presents, and it is the strictest of the two: it cannot be wrong about a code page because
     * it never applies one.
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

    /**
     * The configuration property that states the representation, and the only supported way to change
     * it.
     *
     * <p>It carries no default, deliberately. A defaulted key would let a deployment that never named
     * its driver's record-image type start anyway and then transfer every record through a conversion
     * nobody chose - the same class of silent corruption a defaulted code page produces, and the reason
     * {@code carddemo.charset.dataset} carries no default either.
     */
    public static final String FORM_PROPERTY = "carddemo.record-image.form";

    /** The bean name the configuration publishes the resolved representation under. */
    public static final String FORM_BEAN_NAME = "carddemoRecordImageForm";

    /**
     * Reads one row's whole record image, exactly as stored.
     *
     * <p>The value is <strong>not</strong> trimmed and not padded: a fixed-width record's trailing bytes
     * are {@code FILLER} and are part of it, and a width that disagrees with the copybook is a finding
     * for the caller to report rather than something to repair here.
     *
     * @param resultSet   the row, already positioned
     * @param columnIndex the 1-based ordinal of the record-image column, which is
     *                    {@link DatasetRelation#RECORD_IMAGE_COLUMN_INDEX} for every dataset in this
     *                    module
     * @param charset     the configured dataset code page
     * @return the stored bytes, or {@code null} when the column holds no value - a condition every
     *         caller classifies rather than ignores
     * @throws SQLException             if the driver cannot supply the column
     * @throws NullPointerException     if {@code resultSet} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code columnIndex} is not positive
     */
    public abstract byte[] readImage(ResultSet resultSet, int columnIndex, Charset charset)
            throws SQLException;

    /**
     * Binds a whole record image as one statement parameter.
     *
     * @param statement      the statement being prepared
     * @param parameterIndex the 1-based parameter ordinal
     * @param image          the record's bytes, already at its declared width
     * @param charset        the configured dataset code page
     * @throws SQLException             if the driver refuses the value
     * @throws NullPointerException     if {@code statement}, {@code image} or {@code charset} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code parameterIndex} is not positive
     */
    public abstract void bindImage(PreparedStatement statement, int parameterIndex, byte[] image,
                                   Charset charset) throws SQLException;

    /**
     * Binds a comparison operand - a keyed {@code LIKE} pattern, or the image a browse advances past -
     * in the same representation as the image it is compared with.
     *
     * <p>An operand arrives as text because {@link DatasetRelation.KeySpan#pattern(String)} composes it
     * from a key image and this module's wildcard and escape characters. What this method settles is that
     * it is <em>bound</em> the way the column is stored, so the comparison runs in one representation
     * rather than across two.
     *
     * @param statement      the statement being prepared
     * @param parameterIndex the 1-based parameter ordinal
     * @param operand        the pattern or image to compare against
     * @param charset        the configured dataset code page
     * @throws SQLException             if the driver refuses the value
     * @throws NullPointerException     if {@code statement}, {@code operand} or {@code charset} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code parameterIndex} is not positive
     */
    public abstract void bindOperand(PreparedStatement statement, int parameterIndex, String operand,
                                     Charset charset) throws SQLException;

    /**
     * The record image as the object a template-managed parameter list carries.
     *
     * <p>For the call sites that hand a parameter array to a template rather than preparing a statement
     * themselves. It is the same decision as {@link #bindImage}, expressed as a value.
     *
     * @param image   the record's bytes
     * @param charset the configured dataset code page
     * @return the parameter object: a {@link String} under {@link #CHARACTER}, a defensive copy of the
     *         bytes under {@link #BINARY}
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     */
    public abstract Object imageParameter(byte[] image, Charset charset);

    /**
     * The comparison operand as the object a template-managed parameter list carries.
     *
     * @param operand the pattern or image to compare against
     * @param charset the configured dataset code page
     * @return the parameter object: the text itself under {@link #CHARACTER}, its bytes under
     *         {@link #BINARY}
     * @throws NullPointerException if {@code operand} or {@code charset} is {@code null}
     */
    public abstract Object operandParameter(String operand, Charset charset);

    /**
     * Resolves the configured name, accepting exactly the two representations and nothing else.
     *
     * <p>Case-insensitive and whitespace-tolerant, because a configuration value is typed by a person;
     * strict about everything else, because a name this method did not recognise would otherwise become
     * a default. The failure message names the property and lists what it accepts, so a deployment that
     * mistyped it is told what to type.
     *
     * @param configured the configured value
     * @return the representation it names
     * @throws IllegalArgumentException if {@code configured} is {@code null}, blank, or not one of the
     *                                  two accepted names
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
     * Checks that a code page can carry a record image at all: one byte per character, and the pad and
     * sign characters intact.
     *
     * <p><strong>One byte per character</strong> is what makes a copybook offset a byte offset. It is
     * checked by decoding all {@value #BYTE_VALUE_COUNT} byte values and re-encoding the result: a
     * single-byte code page returns exactly as many bytes as it was given, and {@code UTF-8} or
     * {@code UTF-16} does not. Without it a {@code PIC X(20)} field would start somewhere that depends on
     * the data preceding it, and {@link #CHARACTER}'s character form would not even have the record's
     * length.
     *
     * <p><strong>The pad and sign characters intact</strong> is what makes a stored value survive. A
     * persisted numeric field in this estate is zoned {@code DISPLAY} - not one of the twenty-eight
     * copybooks declares {@code COMP-3} - so its sign is an overpunch in its final byte, and the code
     * page is the whole of the numeric representation. A code page that substituted for {@code '{'} would
     * turn {@code +0} into something else with no error to report, so the characters this module writes
     * signs and padding with are round-tripped explicitly.
     *
     * <p>Deliberately <em>not</em> checked: that every one of the 256 byte values survives. It does under
     * {@code IBM037}, which assigns them all, and it does not under {@code US-ASCII}, which assigns 128 -
     * yet {@code US-ASCII} is the correct and configured code page for the nine text fixtures, whose
     * bytes are all printable. Which code page suits which data is what {@code carddemo.charset.dataset}
     * states. A byte the configured code page cannot represent is not passed over silently either: every
     * conversion here goes through {@link FixedWidthRecord}, which reports an unmappable character rather
     * than substituting for it, so such a record fails loudly at the read that meets it.
     *
     * @param charset the code page to check
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if the charset is not single-byte, or cannot carry the pad and
     *                                  sign characters this module depends on
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

    /**
     * The characters a record's own structure is written with: the {@code PIC X} pad, the {@code PIC 9}
     * fill, the ten digits, and the twenty zoned sign overpunches.
     *
     * <p>{@code '{'} through {@code 'I'} carry a positive sign with digits 0 to 9 and {@code '}'} through
     * {@code 'R'} carry a negative one, which is why the whole of both runs is here rather than just the
     * two zeros: a balance ending in any digit relies on its own overpunch surviving.
     */
    private static final String STRUCTURAL_CHARACTERS =
            " 0123456789{ABCDEFGHI}JKLMNOPQR";

    /** How many distinct values one byte has: the domain the single-byte check spans. */
    private static final int BYTE_VALUE_COUNT = 256;

    /** Rejects a missing code page, which is the whole point of this class's existence. */
    private static void requireCharset(Charset charset) {
        Objects.requireNonNull(charset, "A dataset code page is required: a record image is bytes in a "
                + "specific code page, and this module never falls back to the platform default");
    }

    /** Rejects a missing record image. */
    private static void requireImage(byte[] image) {
        Objects.requireNonNull(image, "A record image is required to bind one; an absent record is an "
                + "end-of-file outcome, not a value to write");
    }

    /** Rejects a missing comparison operand. */
    private static void requireOperand(String operand) {
        Objects.requireNonNull(operand, "A comparison operand is required; a keyed predicate is composed "
                + "from a key of its declared width and never from nothing");
    }

    /** Rejects a non-positive parameter or column ordinal: JDBC ordinals are 1-based. */
    private static void requirePositiveOrdinal(int ordinal, String subject) {
        if (ordinal < 1) {
            throw new IllegalArgumentException("A JDBC " + subject + " ordinal is 1-based, so " + ordinal
                    + " addresses nothing");
        }
    }

    /** The shared guard of every read. */
    private static void requireReadArguments(ResultSet resultSet, int columnIndex, Charset charset) {
        Objects.requireNonNull(resultSet, "A result set is required to read a record image from");
        requirePositiveOrdinal(columnIndex, "column");
        requireCharset(charset);
    }

    /** The shared guard of every image bind. */
    private static void requireBindArguments(PreparedStatement statement, int parameterIndex,
                                             byte[] image, Charset charset) {
        Objects.requireNonNull(statement, "A prepared statement is required to bind a record image to");
        requirePositiveOrdinal(parameterIndex, "parameter");
        requireImage(image);
        requireCharset(charset);
    }

    /** The shared guard of every operand bind. */
    private static void requireOperandArguments(PreparedStatement statement, int parameterIndex,
                                                String operand, Charset charset) {
        Objects.requireNonNull(statement, "A prepared statement is required to bind an operand to");
        requirePositiveOrdinal(parameterIndex, "parameter");
        requireOperand(operand);
        requireCharset(charset);
    }
}
