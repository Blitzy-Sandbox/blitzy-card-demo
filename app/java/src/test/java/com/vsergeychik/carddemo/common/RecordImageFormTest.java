package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The record-image representation contract, and the round trip it exists to guarantee.
 *
 * <h2>What is being proved, and why it needed proving</h2>
 * Before {@link RecordImageForm} the six dataset accesses in this module each decided for themselves how
 * a record image crosses JDBC: the account master read {@code getString} and bound {@code setString}, the
 * card file read {@code getBytes} and <em>fell back</em> to {@code getString}, the cross-reference and the
 * date-parameter reader read {@code getString}, and both statement writers bound {@code setBytes}. Those
 * cannot all be right about one deployment's driver, and being wrong is silent: a driver asked for a
 * character value converts the stored bytes through a code page of its own choosing and hands back a
 * plausible record of the right length holding the wrong values.
 *
 * <p>So two things are asserted here, and the second is the one that matters:
 * <ol>
 *   <li>each representation reads and binds through exactly one JDBC type, and <strong>never</strong>
 *       falls back to the other;</li>
 *   <li>a record survives a full store-and-fetch round trip <strong>byte for byte</strong> under both
 *       representations and under both configured code pages, including its trailing spaces and both the
 *       positive and the negative zoned sign overpunch.</li>
 * </ol>
 *
 * <p>The round trip runs against a real in-memory relation rather than a mock, once with a character
 * record-image column and once with a binary one, because a mock cannot demonstrate that a driver returns
 * what it was given. The overpunch cases are not decorative: not one of the twenty-eight copybooks
 * declares {@code COMP-3}, so every persisted numeric field is zoned {@code DISPLAY} and carries its sign
 * in its final byte - {@code X'C0'} is the {@code '{'} that means {@code +0} and {@code X'D0'} the
 * {@code '}'} that means {@code -0} under {@code IBM037}. A transport that lost that byte would turn a
 * credit into a debit with nothing to show for it.
 */
@DisplayName("RecordImageForm - one representation, and a byte-exact round trip under each")
class RecordImageFormTest {

    /** The mainframe code page: assigns all 256 byte values. */
    private static final Charset IBM037 = Charset.forName("IBM037");

    /** The fixture code page: assigns 128, and is what the nine ASCII fixtures are in. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The ordinal of the record-image column, which is the first in every dataset this module reads. */
    private static final int COLUMN = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /** A distinct in-memory relation per test, so no test can observe another's rows. */
    private static final java.util.concurrent.atomic.AtomicInteger DATABASE_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    // =============================================================================================
    // The round trip. The finding's own acceptance criterion.
    // =============================================================================================

    @Nested
    @DisplayName("A stored record comes back byte for byte")
    class RoundTrip {

        @ParameterizedTest(name = "{0} under IBM037")
        @EnumSource(RecordImageForm.class)
        @DisplayName("an IBM037 account image survives with its trailing spaces and both sign "
                + "overpunches")
        void anIbm037ImageSurvivesUnderBothForms(RecordImageForm form) throws SQLException {
            byte[] stored = accountImage(IBM037);

            assertThat(storeAndFetch(form, IBM037, stored))
                    .as("the whole 300-byte image, unchanged")
                    .isEqualTo(stored);
        }

        @ParameterizedTest(name = "{0} under US-ASCII")
        @EnumSource(RecordImageForm.class)
        @DisplayName("a US-ASCII fixture image survives with its trailing spaces and both sign "
                + "overpunches")
        void anAsciiImageSurvivesUnderBothForms(RecordImageForm form) throws SQLException {
            byte[] stored = accountImage(ASCII);

            assertThat(storeAndFetch(form, ASCII, stored)).isEqualTo(stored);
        }

        @ParameterizedTest(name = "{0} keeps a positive-zero overpunch")
        @EnumSource(RecordImageForm.class)
        @DisplayName("the positive-zero overpunch is the byte it was stored as, not a digit")
        void thePositiveZeroOverpunchSurvives(RecordImageForm form) throws SQLException {
            // ACCT-CURR-BAL of the first fixture record is 0000001940{ - the closing brace IS the sign,
            // and it is what tells +0.00 from -0.00 in the low-order digit position.
            byte[] stored = "0000001940{".getBytes(IBM037);
            assertThat(stored[stored.length - 1]).isEqualTo((byte) 0xC0);

            byte[] fetched = storeAndFetch(form, IBM037, stored);

            assertThat(fetched).isEqualTo(stored);
            assertThat(fetched[fetched.length - 1]).as("X'C0' is '{' and means +0").isEqualTo((byte) 0xC0);
        }

        @ParameterizedTest(name = "{0} keeps a negative-zero overpunch")
        @EnumSource(RecordImageForm.class)
        @DisplayName("the negative-zero overpunch is the byte it was stored as, and is not confused "
                + "with the positive one")
        void theNegativeZeroOverpunchSurvives(RecordImageForm form) throws SQLException {
            byte[] stored = "0000001940}".getBytes(IBM037);
            assertThat(stored[stored.length - 1]).isEqualTo((byte) 0xD0);

            byte[] fetched = storeAndFetch(form, IBM037, stored);

            assertThat(fetched).isEqualTo(stored);
            assertThat(fetched).isNotEqualTo("0000001940{".getBytes(IBM037));
        }

        @ParameterizedTest(name = "{0} keeps every trailing space")
        @EnumSource(RecordImageForm.class)
        @DisplayName("trailing FILLER spaces are neither trimmed nor counted differently")
        void trailingFillerSpacesSurvive(RecordImageForm form) throws SQLException {
            // CVACT01Y ends with FILLER X(178). A transport that trimmed the tail would return a record
            // of the wrong width, and every offset in the next record read from the same stream would be
            // right while this one's total width silently was not.
            byte[] stored = accountImage(IBM037);

            byte[] fetched = storeAndFetch(form, IBM037, stored);

            assertThat(fetched).hasSize(300);
            for (int index = 122; index < 300; index++) {
                assertThat(fetched[index]).as("FILLER byte at offset %d", index)
                        .isEqualTo(" ".getBytes(IBM037)[0]);
            }
        }

        @Test
        @DisplayName("BINARY keeps every one of the 256 byte values, because it converts nothing")
        void binaryKeepsEveryByteValue() throws SQLException {
            byte[] stored = everyByteValue();

            assertThat(storeAndFetch(RecordImageForm.BINARY, IBM037, stored)).isEqualTo(stored);
        }

        @Test
        @DisplayName("CHARACTER keeps 255 of the 256 IBM037 byte values, and X'25' is the exception")
        void characterKeepsEveryByteValueExceptTheEbcdicLineFeed() throws SQLException {
            // The one measured limit of the character form, asserted rather than described. The JDK's
            // IBM037 charmap decodes X'25' - the EBCDIC line feed - to U+000A and encodes U+000A back to
            // X'15', the EBCDIC next-line: mappable both ways, just not to itself, so no exception can be
            // raised and the byte comes back changed. A deployment whose records can hold that control
            // byte configures BINARY, which has no conversion to fold anything, and that is most of the
            // reason both forms exist. Every OTHER byte value survives, including all twenty sign
            // overpunches and both pad characters.
            byte[] stored = everyByteValue();

            byte[] fetched = storeAndFetch(RecordImageForm.CHARACTER, IBM037, stored);

            assertThat(fetched).hasSameSizeAs(stored);
            for (int value = 0; value < 256; value++) {
                if (value == 0x25) {
                    assertThat(fetched[value]).as("X'25' folds to X'15' in the JDK's IBM037 charmap")
                            .isEqualTo((byte) 0x15);
                } else {
                    assertThat(fetched[value]).as("byte X'%02X'", value).isEqualTo((byte) value);
                }
            }
        }

        /** All 256 byte values, in order, as one image. */
        private byte[] everyByteValue() {
            byte[] every = new byte[256];
            for (int value = 0; value < 256; value++) {
                every[value] = (byte) value;
            }
            return every;
        }

        /**
         * Stores an image in a fresh in-memory relation and fetches it back through the representation.
         *
         * <p>The column's SQL type follows the representation, which is the point: {@code CHARACTER}
         * describes a deployment whose gateway presents a character column and {@code BINARY} one whose
         * gateway presents a binary column, so each form is exercised against the column it claims to
         * address.
         *
         * @param form    the representation under test
         * @param charset the dataset code page
         * @param image   the bytes to store
         * @return the bytes fetched back
         * @throws SQLException if the in-memory relation refuses the round trip
         */
        private byte[] storeAndFetch(RecordImageForm form, Charset charset, byte[] image)
                throws SQLException {
            String table = "RECIMG" + DATABASE_SEQUENCE.incrementAndGet();
            JdbcTemplate template = template(table,
                    form == RecordImageForm.BINARY ? "VARBINARY(1024)" : "VARCHAR(1024)", charset);

            template.execute((Connection connection) -> {
                try (PreparedStatement insert =
                             connection.prepareStatement("INSERT INTO " + table + " VALUES (?)")) {
                    form.bindImage(insert, 1, image, charset);
                    insert.executeUpdate();
                }
                return null;
            });

            List<byte[]> fetched = template.query("SELECT * FROM " + table,
                    (resultSet, rowNumber) -> form.readImage(resultSet, COLUMN, charset));
            assertThat(fetched).hasSize(1);
            return fetched.get(0);
        }
    }

    // =============================================================================================
    // One representation, no fallback. The other half of the finding.
    // =============================================================================================

    @Nested
    @DisplayName("Each representation uses one JDBC type and never the other")
    class NoFallback {

        @Test
        @DisplayName("CHARACTER reads getString and does not touch getBytes")
        void characterReadsTextOnly() throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString(COLUMN)).thenReturn("ABC");

            assertThat(RecordImageForm.CHARACTER.readImage(resultSet, COLUMN, ASCII))
                    .isEqualTo("ABC".getBytes(ASCII));
            verify(resultSet, never()).getBytes(anyInt());
        }

        @Test
        @DisplayName("BINARY reads getBytes and does not touch getString")
        void binaryReadsBytesOnly() throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getBytes(COLUMN)).thenReturn("ABC".getBytes(ASCII));

            assertThat(RecordImageForm.BINARY.readImage(resultSet, COLUMN, ASCII))
                    .isEqualTo("ABC".getBytes(ASCII));
            verify(resultSet, never()).getString(anyInt());
        }

        @ParameterizedTest(name = "{0} reports an absent column as absent")
        @EnumSource(RecordImageForm.class)
        @DisplayName("a column holding no value yields no image, and no second attempt is made")
        void anAbsentColumnYieldsNoImage(RecordImageForm form) throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString(COLUMN)).thenReturn(null);
            when(resultSet.getBytes(COLUMN)).thenReturn(null);

            assertThat(form.readImage(resultSet, COLUMN, ASCII)).isNull();
        }

        @Test
        @DisplayName("CHARACTER binds setString for an image and for a comparison operand")
        void characterBindsText() throws SQLException {
            PreparedStatement statement = mock(PreparedStatement.class);

            RecordImageForm.CHARACTER.bindImage(statement, 1, "ABC".getBytes(ASCII), ASCII);
            RecordImageForm.CHARACTER.bindOperand(statement, 2, "AB%", ASCII);

            verify(statement).setString(1, "ABC");
            verify(statement).setString(2, "AB%");
            verify(statement, never()).setBytes(anyInt(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("BINARY binds setBytes for an image and for a comparison operand")
        void binaryBindsBytes() throws SQLException {
            PreparedStatement statement = mock(PreparedStatement.class);

            RecordImageForm.BINARY.bindImage(statement, 1, "ABC".getBytes(ASCII), ASCII);
            RecordImageForm.BINARY.bindOperand(statement, 2, "AB%", ASCII);

            verify(statement).setBytes(1, "ABC".getBytes(ASCII));
            verify(statement).setBytes(2, "AB%".getBytes(ASCII));
            verify(statement, never()).setString(anyInt(), anyString());
        }

        @Test
        @DisplayName("an image parameter and an operand parameter carry the representation's own type")
        void parametersCarryTheRepresentationsType() {
            assertThat(RecordImageForm.CHARACTER.imageParameter("AB".getBytes(ASCII), ASCII))
                    .isInstanceOf(String.class).isEqualTo("AB");
            assertThat(RecordImageForm.CHARACTER.operandParameter("A%", ASCII))
                    .isInstanceOf(String.class).isEqualTo("A%");
            assertThat(RecordImageForm.BINARY.imageParameter("AB".getBytes(ASCII), ASCII))
                    .isInstanceOf(byte[].class).isEqualTo("AB".getBytes(ASCII));
            assertThat(RecordImageForm.BINARY.operandParameter("A%", ASCII))
                    .isInstanceOf(byte[].class).isEqualTo("A%".getBytes(ASCII));
        }

        @ParameterizedTest(name = "{0} hands out a copy rather than the caller's array")
        @EnumSource(RecordImageForm.class)
        @DisplayName("a bound image cannot be mutated through the array the caller passed")
        void aBoundImageIsCopied(RecordImageForm form) {
            byte[] mine = "AB".getBytes(ASCII);

            Object parameter = form.imageParameter(mine, ASCII);
            mine[0] = (byte) 'Z';

            if (parameter instanceof byte[] bytes) {
                assertThat(bytes).isEqualTo("AB".getBytes(ASCII));
            } else {
                assertThat(parameter).isEqualTo("AB");
            }
        }
    }

    // =============================================================================================
    // Resolution from configuration. No default, and no name accepted that is not implemented.
    // =============================================================================================

    @Nested
    @DisplayName("The representation is resolved from configuration, never defaulted")
    class Resolution {

        @ParameterizedTest(name = "'{0}' resolves to CHARACTER")
        @ValueSource(strings = { "CHARACTER", "character", "Character", "  CHARACTER  " })
        @DisplayName("the character name resolves, case-insensitively and whitespace-tolerantly")
        void characterResolves(String configured) {
            assertThat(RecordImageForm.parse(configured)).isSameAs(RecordImageForm.CHARACTER);
        }

        @ParameterizedTest(name = "'{0}' resolves to BINARY")
        @ValueSource(strings = { "BINARY", "binary", "Binary", "\tbinary\n" })
        @DisplayName("the binary name resolves, case-insensitively and whitespace-tolerantly")
        void binaryResolves(String configured) {
            assertThat(RecordImageForm.parse(configured)).isSameAs(RecordImageForm.BINARY);
        }

        @ParameterizedTest(name = "'{0}' is refused")
        @ValueSource(strings = { "CHAR", "VARCHAR", "BLOB", "TEXT", "utf8", "1", "CHARACTERS" })
        @DisplayName("a name this module does not implement is refused, and the message names the key")
        void anUnimplementedNameIsRefused(String configured) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordImageForm.parse(configured))
                    .withMessageContaining(RecordImageForm.FORM_PROPERTY)
                    .withMessageContaining("CHARACTER")
                    .withMessageContaining("BINARY");
        }

        @Test
        @DisplayName("an absent or blank value is refused rather than defaulted")
        void anAbsentValueIsRefused() {
            // The whole reason the property carries no default. A deployment that never said how its
            // driver presents a record image must not start and then hand every record to a conversion
            // nobody chose - which is the same class of silent corruption a defaulted code page produces.
            assertThatIllegalArgumentException().isThrownBy(() -> RecordImageForm.parse(null))
                    .withMessageContaining("not set");
            assertThatIllegalArgumentException().isThrownBy(() -> RecordImageForm.parse("   "))
                    .withMessageContaining("blank");
        }

        @Test
        @DisplayName("the property and bean names are the ones the configuration and both profiles use")
        void thePropertyAndBeanNamesAreStable() {
            assertThat(RecordImageForm.FORM_PROPERTY).isEqualTo("carddemo.record-image.form");
            assertThat(RecordImageForm.FORM_BEAN_NAME).isEqualTo("carddemoRecordImageForm");
        }

        @Test
        @DisplayName("there are exactly two representations, so the set is closed")
        void theSetOfRepresentationsIsClosed() {
            assertThat(RecordImageForm.values())
                    .containsExactly(RecordImageForm.CHARACTER, RecordImageForm.BINARY);
        }
    }

    // =============================================================================================
    // The code-page guard: one byte per character, and the structural characters intact.
    // =============================================================================================

    @Nested
    @DisplayName("A code page that cannot carry a record image is refused")
    class CodePageGuard {

        @Test
        @DisplayName("both configured code pages are accepted")
        void bothConfiguredCodePagesAreAccepted() {
            RecordImageForm.requireSingleByteCodePage(IBM037);
            RecordImageForm.requireSingleByteCodePage(ASCII);
        }

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(strings = { "UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE" })
        @DisplayName("a multi-byte code page is refused: a copybook offset must be a byte offset")
        void aMultiByteCodePageIsRefused(String name) {
            // Two ways a multi-byte code page fails, and both are refusals: UTF-8 does not preserve the
            // length of an arbitrary byte run, and UTF-16 preserves it while encoding the space character
            // to two bytes. Either way a PIC X(20) field would begin somewhere that depends on the data
            // in front of it, so the message names the code page and says it cannot carry a record image.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordImageForm.requireSingleByteCodePage(Charset.forName(name)))
                    .withMessageContaining(Charset.forName(name).name())
                    .withMessageContaining("cannot carry");
        }

        @Test
        @DisplayName("a code page whose two directions disagree is refused, not merely a multi-byte one")
        void aCodePageWhoseDirectionsDisagreeIsRefused() {
            // The second half of the guard, and the case only it can catch. A code page can be
            // single-byte in both directions and still not carry a record, if what it encodes a character
            // to is not what it decodes that byte back to. That is not hypothetical: it is BD-01's own
            // scenario expressed as a charset - a deployment whose driver renders the record image with
            // one code page while the module encodes with another. Modelled here by pairing IBM037's
            // encoder with US-ASCII's decoder, so ' ' encodes to X'40' and decodes back to '@'.
            //
            // The length check cannot see this: decoding 256 bytes yields 256 characters and encoding
            // them yields 256 bytes, so the widths agree while the values do not.
            Charset disagreeing = new DisagreeingCodePage();

            assertThat(new String(new byte[256], disagreeing).getBytes(disagreeing))
                    .as("it is single-byte in both directions, so the width check passes")
                    .hasSize(256);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordImageForm.requireSingleByteCodePage(disagreeing))
                    .withMessageContaining("without changing it")
                    .withMessageContaining("overpunch");
        }

        @Test
        @DisplayName("a missing code page is refused rather than replaced by the platform default")
        void aMissingCodePageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> RecordImageForm.requireSingleByteCodePage(null));
        }

        @Test
        @DisplayName("the sign overpunches and the pad characters survive both configured code pages")
        void theStructuralCharactersSurvive() {
            // The guard's second half, stated directly: '{' through 'I' carry a positive sign with digits
            // 0 to 9 and '}' through 'R' a negative one, so a balance ending in any digit depends on its
            // own overpunch surviving - not just the two zeros.
            for (Charset charset : List.of(IBM037, ASCII)) {
                for (char character : " 0123456789{ABCDEFGHI}JKLMNOPQR".toCharArray()) {
                    byte[] encoded = String.valueOf(character).getBytes(charset);
                    assertThat(encoded).as("'%s' under %s", character, charset.name()).hasSize(1);
                    assertThat(new String(encoded, charset).charAt(0))
                            .as("'%s' under %s", character, charset.name()).isEqualTo(character);
                }
            }
        }
    }

    // =============================================================================================
    // Argument guards. Each is individually reachable, so none is an unreachable branch.
    // =============================================================================================

    @Nested
    @DisplayName("Arguments are checked, so a defect surfaces where it was made")
    class ArgumentGuards {

        @ParameterizedTest(name = "{0} refuses a null result set, charset or image")
        @EnumSource(RecordImageForm.class)
        @DisplayName("a null result set, statement, image, operand or charset is refused")
        void nullsAreRefused(RecordImageForm form) {
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);

            assertThatNullPointerException()
                    .isThrownBy(() -> form.readImage(null, COLUMN, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.readImage(resultSet, COLUMN, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.bindImage(null, 1, new byte[1], ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.bindImage(statement, 1, null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.bindImage(statement, 1, new byte[1], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.bindOperand(null, 1, "A", ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.bindOperand(statement, 1, null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.bindOperand(statement, 1, "A", null));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.imageParameter(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.imageParameter(new byte[1], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.operandParameter(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> form.operandParameter("A", null));
        }

        @ParameterizedTest(name = "{0} refuses a non-positive ordinal")
        @EnumSource(RecordImageForm.class)
        @DisplayName("a JDBC ordinal below one addresses nothing and is refused")
        void nonPositiveOrdinalsAreRefused(RecordImageForm form) {
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> form.readImage(resultSet, 0, ASCII))
                    .withMessageContaining("1-based");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> form.bindImage(statement, 0, new byte[1], ASCII))
                    .withMessageContaining("1-based");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> form.bindOperand(statement, -1, "A", ASCII))
                    .withMessageContaining("1-based");
        }

        @Test
        @DisplayName("a character the code page cannot represent is reported, never substituted")
        void anUnmappableCharacterIsReported() {
            // US-ASCII assigns 128 of the 256 byte values, which is correct for the nine text fixtures
            // and wrong for a byte outside them. Such a byte is not passed over: every conversion goes
            // through FixedWidthRecord, which reports rather than substituting, so a record the configured
            // code page cannot carry fails at the read that meets it instead of arriving corrupted.
            PreparedStatement statement = mock(PreparedStatement.class);

            assertThatIllegalArgumentException().isThrownBy(() ->
                    RecordImageForm.BINARY.bindOperand(statement, 1, "\u00E9", ASCII));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    RecordImageForm.BINARY.operandParameter("\u00E9", ASCII));
        }
    }

    /**
     * A code page that encodes with {@code IBM037} and decodes with {@code US-ASCII}.
     *
     * <p>Single-byte in both directions - so it passes the width half of the guard - and self-inconsistent,
     * because what it encodes a character to is not what it decodes that byte back to. It models the
     * deployment hazard BD-01 was raised about: a driver rendering the record image in one code page while
     * the module encodes in another. Declared here rather than in the module because no real code page
     * behaves this way; the guard exists for the pairing, not for a charset.
     */
    private static final class DisagreeingCodePage extends Charset {

        /** The code page whose encoder is used. */
        private static final Charset ENCODING_SIDE = Charset.forName("IBM037");

        /** The code page whose decoder is used - deliberately not {@link #ENCODING_SIDE}. */
        private static final Charset DECODING_SIDE = StandardCharsets.US_ASCII;

        DisagreeingCodePage() {
            super("X-CARDDEMO-DISAGREEING", null);
        }

        @Override
        public boolean contains(Charset other) {
            return false;
        }

        @Override
        public java.nio.charset.CharsetEncoder newEncoder() {
            return ENCODING_SIDE.newEncoder();
        }

        @Override
        public java.nio.charset.CharsetDecoder newDecoder() {
            return DECODING_SIDE.newDecoder();
        }
    }

    /**
     * A template over a fresh single-column in-memory relation.
     *
     * @param table       the relation's name
     * @param columnType  the record-image column's SQL type
     * @param charset     the code page the connection's own text conversion must not disturb
     * @return the template
     */
    private static JdbcTemplate template(String table, String columnType, Charset charset) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:recordimageform" + table + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("CREATE TABLE " + table + " (RECORD_IMAGE " + columnType + ")");
        assertThat(charset).as("a code page is always named, never defaulted").isNotNull();
        return template;
    }

    // =============================================================================================
    // The deployment contract CHARACTER carries, and cannot enforce.
    // =============================================================================================

    @Nested
    @DisplayName("The bytewise-collation requirement is stated at every place the form is decided")
    class TheCollationContract {

        /** Repository-relative path of the shipped default profile. */
        private static final String APPLICATION_YML = "app/java/src/main/resources/application.yml";

        /** Repository-relative path of the class that binds the form to a bean. */
        private static final String DATA_SOURCE_CONFIG =
                "app/java/src/main/java/com/vsergeychik/carddemo/config/DataSourceConfig.java";

        /** Repository-relative path of this type's own source. */
        private static final String RECORD_IMAGE_FORM =
                "app/java/src/main/java/com/vsergeychik/carddemo/common/RecordImageForm.java";

        // -----------------------------------------------------------------------------------------
        // Why these are guarded by a test at all.
        //
        // Under CHARACTER the record image is not merely read: DatasetRelation composes a positional
        // LIKE over that column for a keyed read and an ORDER BY over it for a browse. A COBOL KSDS
        // matches a key by its BYTES and browses in ascending order of those bytes, so under this form
        // the backend's collation IS the key semantics. A collation that folds case, folds accents,
        // treats trailing blanks as equivalent or orders linguistically does not fail - it returns the
        // wrong record, or a browse in an order the COBOL never produces, with no error anywhere.
        //
        // Nothing in this build can check that (no production connectivity - residual risk R-E), so the
        // only enforcement available is that the requirement is written down where the decision gets
        // made. That makes the words themselves the deliverable, and a deliverable with no test is one
        // a later edit removes silently. These tests assert the SUBSTANCE - the collation requirement,
        // both comparing operations, and the BINARY alternative - not the prose, so rewording is free
        // and deletion is not.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("beside the key in application.yml, in the block attached to it")
        void theConfiguredKeyCarriesIt() {
            String block = documentationAbove(read(repositoryFile(APPLICATION_YML)), "  record-image:",
                    line -> line.isBlank() || line.stripLeading().startsWith("#"));

            assertContractSubstance(block, "the application.yml block attached to record-image:");
        }

        @Test
        @DisplayName("on the bean method that binds it, in that method's own Javadoc")
        void theBindingCarriesIt() {
            String javadoc = javadocAbove(read(repositoryFile(DATA_SOURCE_CONFIG)),
                    "public RecordImageForm carddemoRecordImageForm(");

            assertContractSubstance(javadoc, "the carddemoRecordImageForm Javadoc");
            assertThat(javadoc)
                    .as("and says plainly that this build cannot verify it, so a reader does not assume "
                            + "some startup check already has")
                    .containsIgnoringCase("cannot");
        }

        @Test
        @DisplayName("on the CHARACTER constant, so the contract travels with the type")
        void theConstantCarriesIt() {
            String javadoc = javadocAbove(read(repositoryFile(RECORD_IMAGE_FORM)), "    CHARACTER {");

            assertContractSubstance(javadoc, "the CHARACTER constant's Javadoc");
        }

        @Test
        @DisplayName("and the two operations it constrains are the two DatasetRelation actually composes")
        void theContractDescribesTheRealStatements() {
            // The contract is only worth stating if it names the operations that exist. DatasetRelation
            // composes exactly these two comparisons of the image column, so if either were renamed or
            // removed the wording above would be describing something that no longer happens.
            String relation = read(repositoryFile(
                    "app/java/src/main/java/com/vsergeychik/carddemo/common/DatasetRelation.java"));

            assertThat(relation)
                    .as("a keyed read compares the image column with an escaped LIKE")
                    .contains("\" LIKE ? ESCAPE '\"");
            assertThat(relation)
                    .as("and a browse orders by that same column")
                    .contains("\" ORDER BY \"");
        }

        /**
         * Requires a block of documentation to carry the whole contract rather than a fragment of it.
         *
         * @param documentation the block attached to the declaration under test
         * @param where         a description of that block, for the failure message
         */
        private void assertContractSubstance(String documentation, String where) {
            assertThat(documentation)
                    .as(where + " states the collation the backend must provide")
                    .containsIgnoringCase("collation");
            assertThat(documentation)
                    .as(where + " says which collation: a bytewise one")
                    .containsIgnoringCase("bytewise");
            assertThat(documentation)
                    .as(where + " names the keyed comparison that makes the collation matter")
                    .containsIgnoringCase("LIKE");
            assertThat(documentation)
                    .as(where + " names the ordered comparison too, which is the browse")
                    .containsIgnoringCase("ORDER BY");
            assertThat(documentation)
                    .as(where + " gives the deployment that cannot guarantee it somewhere to go")
                    .containsIgnoringCase("BINARY");
        }

        /**
         * The contiguous run of comment and blank lines immediately above a line, which is what
         * "documented at the key" means in a YAML document.
         *
         * @param text        the whole document
         * @param declaration the line the documentation is attached to
         * @param isComment   whether a line belongs to a documentation block
         * @return the attached block, newest line last
         */
        private String documentationAbove(String text, String declaration,
                java.util.function.Predicate<String> isComment) {
            List<String> lines = text.lines().toList();
            int at = -1;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).startsWith(declaration)) {
                    at = index;
                    break;
                }
            }
            assertThat(at).as("found the declaration '" + declaration + "'").isNotNegative();

            int first = at;
            while (first > 0 && isComment.test(lines.get(first - 1))) {
                first--;
            }
            assertThat(first)
                    .as("'" + declaration + "' has a documentation block attached to it at all")
                    .isLessThan(at);
            return String.join("\n", lines.subList(first, at));
        }

        /**
         * The Javadoc comment immediately above a declaration.
         *
         * @param source      the whole source file
         * @param declaration the declaration text to find
         * @return the Javadoc attached to it
         */
        private String javadocAbove(String source, String declaration) {
            int at = source.indexOf(declaration);
            assertThat(at).as("found the declaration '" + declaration + "'").isNotNegative();
            int opened = source.lastIndexOf("/**", at);
            assertThat(opened)
                    .as("'" + declaration + "' has a Javadoc comment attached to it at all")
                    .isNotNegative();
            int closed = source.indexOf("*/", opened);
            assertThat(closed).as("that Javadoc comment is terminated").isLessThan(at);
            return source.substring(opened, closed);
        }
    }

    /**
     * Locates a repository-relative path by walking upwards from the working directory.
     *
     * <p>The same approach {@code NoRawBackendDiagnosticTest} and {@code CardDemoApplicationTest} use, so
     * every suite that reads the checkout does it one way.
     *
     * @param relativePath the repository-relative path
     * @return the resolved path
     */
    private static Path repositoryFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath());
    }

    /**
     * Reads a checkout file as UTF-8.
     *
     * @param source the file to read
     * @return its text
     */
    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + source, unreadable);
        }
    }

    /**
     * A 300-byte {@code CVACT01Y} account image with both sign overpunches and its {@code FILLER} tail.
     *
     * <p>Modelled on the first record of {@code app/data/ASCII/acctdata.txt}: an eleven-digit key, an
     * active flag, a positive current balance ending in a {@code '{'} overpunch, a negative cycle-credit
     * ending in a {@code '}'} one, and 178 trailing {@code FILLER} spaces.
     *
     * @param charset the code page to render it in
     * @return exactly 300 bytes
     */
    private static byte[] accountImage(Charset charset) {
        String image = "00000000001"          // ACCT-ID           PIC 9(11)
                + "Y"                          // ACCT-ACTIVE-STATUS PIC X(01)
                + "0000001940{"                // ACCT-CURR-BAL     S9(10)V99, +0 overpunch
                + "0000005000}"                // ACCT-CREDIT-LIMIT S9(10)V99, -0 overpunch
                + "00000250000"                // ACCT-CASH-LIMIT
                + "2022-01-01"                 // ACCT-OPEN-DATE
                + "2028-12-31"                 // ACCT-EXPIRAION-DATE (misspelled in the copybook)
                + "2022-06-30"                 // ACCT-REISSUE-DATE
                + "0000012300"                 // ACCT-CURR-CYC-CREDIT (10 of its 12 positions)
                + " ".repeat(2)
                + "0000000000";                // ACCT-CURR-CYC-DEBIT (10 of its 12 positions)
        StringBuilder padded = new StringBuilder(image);
        while (padded.length() < 300) {
            padded.append(' ');
        }
        byte[] bytes = padded.toString().getBytes(charset);
        assertThat(bytes).as("CVACT01Y declares a 300-byte record").hasSize(300);
        return bytes;
    }
}
