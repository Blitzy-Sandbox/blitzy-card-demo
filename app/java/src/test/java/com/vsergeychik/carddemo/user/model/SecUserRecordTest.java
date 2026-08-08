package com.vsergeychik.carddemo.user.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SecUserRecord}, the 80-byte {@code USRSEC} record from
 * {@code app/cpy/CSUSR01Y.cpy}.
 *
 * <p>Plain JUnit 5 with no Spring context and no {@code JobLauncher}: every decision in the class
 * under test is reachable directly, which is the property that makes the branch-coverage bar
 * attainable without an HTTP or batch layer in the path.
 *
 * <p>The expectations here are transcribed from the reference sources by hand rather than restated
 * from the implementation, so what is asserted is the copybook's own arithmetic:
 * <ul>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} - the six field offsets and widths, and the total of 80;</li>
 *   <li>{@code app/jcl/DUSRSECJ.jcl} lines 35-44 - the ten 57-character seed cards, reproduced
 *       verbatim, together with {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)};</li>
 *   <li>the {@code USRSEC} EBCDIC dataset under {@code app/data/EBCDIC} - 800 bytes of real binary
 *       data, read as the byte-level oracle;</li>
 *   <li>{@code app/cpy-bms/COUSR02.CPY} and {@code app/cbl/COUSR02C.cbl} lines 219-234 - the
 *       equal-width screen fields whose change tests depend on reads not being trimmed.</li>
 * </ul>
 *
 * <p>Two charsets are used throughout, both named explicitly and never defaulted: {@code IBM037} for
 * the EBCDIC dataset and {@code US-ASCII} for character-level cases.
 *
 * <h2>Acceptance gates enforced directly here</h2>
 * <ul>
 *   <li><strong>G19</strong> - the record is byte-identical in width to its copybook declaration,
 *       namely 80.</li>
 *   <li><strong>G21</strong> - the {@code SEC-USR-FILLER} span is present and space-filled in every
 *       serialised record. A round trip that produced 57 bytes would mean the filler was dropped, and
 *       that is precisely what the round-trip assertions catch.</li>
 *   <li><strong>G17</strong> - field images are reachable by COBOL name, in declaration order, for a
 *       differ that compares field by field.</li>
 *   <li><strong>G41</strong> - the password is held and compared as plaintext, and is kept out of the
 *       record's rendering.</li>
 * </ul>
 */
class SecUserRecordTest {

    /** The code page of the reference {@code USRSEC} dataset. Never defaulted. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The code page used for character-level cases. Never defaulted. */
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** The literal password every seeded user carries, from {@code app/jcl/DUSRSECJ.jcl}. */
    private static final String SEED_PASSWORD = "PASSWORD";

    /**
     * The ten in-stream seed cards of {@code app/jcl/DUSRSECJ.jcl} lines 35-44, verbatim.
     *
     * <p>Each is exactly 57 characters - the record's first five fields and nothing more. The
     * dataset's {@code LRECL=80,RECFM=FB} right-pads each by the 23 bytes of
     * {@code SEC-USR-FILLER}, which is why these must be widened before they can be decoded.
     */
    private static final List<String> SEED_CARDS = List.of(
            "ADMIN001MARGARET            GOLD                PASSWORDA",
            "ADMIN002RUSSELL             RUSSELL             PASSWORDA",
            "ADMIN003RAYMOND             WHITMORE            PASSWORDA",
            "ADMIN004EMMANUEL            CASGRAIN            PASSWORDA",
            "ADMIN005GRANVILLE           LACHAPELLE          PASSWORDA",
            "USER0001LAWRENCE            THOMAS              PASSWORDU",
            "USER0002AJITH               KUMAR               PASSWORDU",
            "USER0003LAURITZ             ALME                PASSWORDU",
            "USER0004AVERARDO            MAZZI               PASSWORDU",
            "USER0005LEE                 TING                PASSWORDU");

    @Nested
    @DisplayName("Record geometry, checked against the copybook")
    class Geometry {

        @Test
        @DisplayName("the layout builds and its spans sum to exactly 80")
        void layoutSelfCheckPasses() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            assertThat(SecUserRecord.LAYOUT.recordLength()).isEqualTo(80);
            assertThat(SecUserRecord.LAYOUT.storageSpans()).hasSize(6);
            assertThat(SecUserRecord.LAYOUT.redefinitions()).isEmpty();
            assertThat(SecUserRecord.SPANS).hasSize(6);

            int declared = SecUserRecord.LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length)
                    .sum();
            assertThat(declared).isEqualTo(SecUserRecord.RECORD_LENGTH);
            assertThat(SecUserRecord.SEC_USR_ID_LENGTH
                    + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH
                    + SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(80);
        }

        @ParameterizedTest(name = "{0} at offset {1}, {2} bytes")
        @CsvSource({
                "SEC-USR-ID,0,8",
                "SEC-USR-FNAME,8,20",
                "SEC-USR-LNAME,28,20",
                "SEC-USR-PWD,48,8",
                "SEC-USR-TYPE,56,1",
                "SEC-USR-FILLER,57,23"
        })
        @DisplayName("every field sits where the copybook puts it, the named filler included")
        void fieldOffsetsAndLengths(String cobolName, int offset, int length) {
            FieldSpan span = SecUserRecord.LAYOUT.span(cobolName);
            assertThat(span.name()).isEqualTo(cobolName);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
        }

        @Test
        @DisplayName("the spans run contiguously from offset 0 with no gap and no overlap")
        void spansAreContiguous() {
            int cursor = 0;
            for (FieldSpan span : SecUserRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset()).isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("SEC-USR-FILLER is a resolvable named span, not an anonymous gap")
        void fillerIsNamedAndResolvable() {
            FieldSpan filler = SecUserRecord.LAYOUT.span(SecUserRecord.FIELD_SEC_USR_FILLER);
            assertThat(filler).isEqualTo(SecUserRecord.SPAN_SEC_USR_FILLER);
            assertThat(filler.kind().filler())
                    .as("declared as a named alphanumeric item so it stays referable")
                    .isFalse();
            assertThat(SecUserRecord.LAYOUT.hasSpan(SecUserRecord.FIELD_SEC_USR_FILLER)).isTrue();
        }

        @Test
        @DisplayName("the key geometry mirrors KEYS(8,0)")
        void keyGeometryMatchesTheClusterDefinition() {
            assertThat(SecUserRecord.KEY_OFFSET).isZero();
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(SecUserRecord.SEC_USR_ID_OFFSET);
        }

        @Test
        @DisplayName("the group/field name asymmetry of the copybook is preserved verbatim")
        void cobolNamesAreVerbatim() {
            assertThat(SecUserRecord.GROUP_NAME).isEqualTo("SEC-USER-DATA");
            assertThat(SecUserRecord.FIELD_SEC_USR_ID).isEqualTo("SEC-USR-ID");
            assertThat(SecUserRecord.FIELD_SEC_USR_FNAME).isEqualTo("SEC-USR-FNAME");
            assertThat(SecUserRecord.FIELD_SEC_USR_LNAME).isEqualTo("SEC-USR-LNAME");
            assertThat(SecUserRecord.FIELD_SEC_USR_PWD).isEqualTo("SEC-USR-PWD");
            assertThat(SecUserRecord.FIELD_SEC_USR_TYPE).isEqualTo("SEC-USR-TYPE");
            assertThat(SecUserRecord.FIELD_SEC_USR_FILLER).isEqualTo("SEC-USR-FILLER");
            assertThat(SecUserRecord.GROUP_NAME).contains("USER");
            assertThat(SecUserRecord.FIELD_SEC_USR_ID).startsWith("SEC-USR-");
        }
    }

    @Nested
    @DisplayName("Serialisation, and the filler that must never be dropped")
    class Serialisation {

        @Test
        @DisplayName("a round trip is exactly 80 bytes and byte-identical")
        void roundTripIsByteIdentical() {
            for (byte[] row : ebcdicRows()) {
                SecUserRecord decoded = SecUserRecord.decode(row, EBCDIC);
                byte[] reencoded = SecUserRecord.encode(decoded, EBCDIC);
                assertThat(reencoded)
                        .as("a 57-byte result would mean SEC-USR-FILLER was dropped")
                        .hasSize(SecUserRecord.RECORD_LENGTH)
                        .isEqualTo(row);
            }
        }

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"IBM037", "US-ASCII"})
        @DisplayName("an unassigned filler is emitted as 23 spaces in the code page's own space byte")
        void fillerIsSpaceFilled(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            SecUserRecord record = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                    "U", charset);
            byte[] encoded = SecUserRecord.encode(record, charset);

            assertThat(encoded).hasSize(SecUserRecord.RECORD_LENGTH);
            byte space = " ".getBytes(charset)[0];
            for (int index = SecUserRecord.SEC_USR_FILLER_OFFSET;
                 index < SecUserRecord.RECORD_LENGTH;
                 index++) {
                assertThat(encoded[index]).as("byte %d", index).isEqualTo(space);
            }
            assertThat(new String(encoded, charset)
                    .substring(SecUserRecord.SEC_USR_FILLER_OFFSET))
                    .isEqualTo(" ".repeat(SecUserRecord.SEC_USR_FILLER_LENGTH));
        }

        @Test
        @DisplayName("blank() is an all-spaces area of the declared widths")
        void blankIsAllSpaces() {
            SecUserRecord blank = SecUserRecord.blank();
            assertThat(blank.secUsrId()).isEqualTo(" ".repeat(8));
            assertThat(blank.secUsrFname()).isEqualTo(" ".repeat(20));
            assertThat(blank.secUsrLname()).isEqualTo(" ".repeat(20));
            assertThat(blank.secUsrPwd()).isEqualTo(" ".repeat(8));
            assertThat(blank.secUsrType()).isEqualTo(" ");
            assertThat(blank.secUsrFiller()).isEqualTo(" ".repeat(23));
            assertThat(SecUserRecord.encode(blank, ASCII)).hasSize(80);
        }

        @Test
        @DisplayName("a canonical construction survives a full cycle under either code page")
        void canonicalConstructionRoundTrips() {
            SecUserRecord record = new SecUserRecord("ADMIN001", pad("MARGARET", 20),
                    pad("GOLD", 20), SEED_PASSWORD, "A", " ".repeat(23));
            assertThat(SecUserRecord.decode(SecUserRecord.encode(record, EBCDIC), EBCDIC))
                    .isEqualTo(record);
            assertThat(SecUserRecord.decode(SecUserRecord.encode(record, ASCII), ASCII))
                    .isEqualTo(record);
        }

        @Test
        @DisplayName("the codec-taking overloads behave identically to the charset-taking ones")
        void codecOverloadsAgree() {
            FixedWidthCodec codec = new FixedWidthCodec(EBCDIC);
            byte[] row = ebcdicRows().get(3);
            assertThat(SecUserRecord.decode(row, codec)).isEqualTo(SecUserRecord.decode(row, EBCDIC));

            SecUserRecord record = SecUserRecord.decode(row, codec);
            assertThat(SecUserRecord.encode(record, codec))
                    .isEqualTo(SecUserRecord.encode(record, EBCDIC));
        }
    }

    @Nested
    @DisplayName("PIC X semantics: padded on write, never trimmed on read")
    class PictureXSemantics {

        @Test
        @DisplayName("reads keep their padding at the full declared width")
        void readsAreNotTrimmed() {
            SecUserRecord admin = SecUserRecord.decode(ebcdicRows().get(0), EBCDIC);
            assertThat(admin.secUsrId()).isEqualTo("ADMIN001").hasSize(8);
            assertThat(admin.secUsrFname()).isEqualTo("MARGARET" + " ".repeat(12)).hasSize(20);
            assertThat(admin.secUsrLname()).isEqualTo("GOLD" + " ".repeat(16)).hasSize(20);
            assertThat(admin.secUsrPwd()).isEqualTo(SEED_PASSWORD).hasSize(8);
            assertThat(admin.secUsrType()).isEqualTo("A").hasSize(1);
            assertThat(admin.secUsrFiller()).hasSize(23).isBlank();
        }

        @Test
        @DisplayName("an equal-width screen field compares equal, which is what COUSR02C relies on")
        void changeDetectionParityHolds() {
            SecUserRecord admin = SecUserRecord.decode(ebcdicRows().get(0), EBCDIC);
            // COUSR02.CPY declares FNAMEI PIC X(20), so CICS delivers it padded to 20.
            String unchanged = "MARGARET" + " ".repeat(12);
            String changed = "MARGARETHE" + " ".repeat(10);

            assertThat(admin.secUsrFname()).isEqualTo(unchanged);
            assertThat(admin.secUsrFname()).isNotEqualTo(changed);
            // Had the accessor trimmed, the unchanged case would have compared unequal and the
            // program would have recorded a spurious modification.
            assertThat(admin.secUsrFname().trim()).isNotEqualTo(unchanged);
        }

        @Test
        @DisplayName("of(...) pads a short sending value on the right")
        void movePadsShortValues() {
            SecUserRecord record = SecUserRecord.of("AB", "JO", "LI", "PW", "U", ASCII);
            assertThat(record.secUsrId()).isEqualTo("AB      ").hasSize(8);
            assertThat(record.secUsrFname()).isEqualTo("JO" + " ".repeat(18)).hasSize(20);
            assertThat(record.secUsrLname()).isEqualTo("LI" + " ".repeat(18)).hasSize(20);
            assertThat(record.secUsrPwd()).isEqualTo("PW      ").hasSize(8);
            assertThat(record.secUsrType()).isEqualTo("U");
            assertThat(record.secUsrFiller()).isEqualTo(" ".repeat(23));
        }

        @Test
        @DisplayName("of(...) truncates an over-long sending value on the RIGHT, as PIC X does")
        void moveTruncatesOnTheRight() {
            SecUserRecord record = SecUserRecord.of("ADMIN0019", "A".repeat(25), "B".repeat(25),
                    "PASSWORD9", "AU", ASCII);
            assertThat(record.secUsrId()).isEqualTo("ADMIN001");
            assertThat(record.secUsrFname()).isEqualTo("A".repeat(20));
            assertThat(record.secUsrLname()).isEqualTo("B".repeat(20));
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD);
            assertThat(record.secUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("of(...) can carry an explicit filler forward, as an update path needs")
        void moveCanCarryAnExplicitFiller() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            SecUserRecord record = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                    "U", "Z".repeat(30), codec);
            assertThat(record.secUsrFiller()).isEqualTo("Z".repeat(23)).hasSize(23);
            assertThat(SecUserRecord.encode(record, ASCII)).hasSize(80);
        }

        @Test
        @DisplayName("this type neither trims nor upper-cases what it is given")
        void noNormalisationIsApplied() {
            SecUserRecord record = SecUserRecord.of("user0001", "lawrence", "thomas", "password",
                    "u", ASCII);
            assertThat(record.secUsrId()).isEqualTo("user0001");
            assertThat(record.secUsrFname()).startsWith("lawrence");
            assertThat(record.secUsrType()).isEqualTo("u");
        }
    }

    @Nested
    @DisplayName("The seeded USRSEC data")
    class SeedData {

        @Test
        @DisplayName("all ten cards are 57 characters and decode once widened to 80")
        void seedCardsDecodeAfterWidening() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            for (int index = 0; index < SEED_CARDS.size(); index++) {
                String card = SEED_CARDS.get(index);
                assertThat(card).as("card %d", index).hasSize(57);

                byte[] widened = codec.padToDeclaredWidth(card.getBytes(ASCII),
                        SecUserRecord.RECORD_LENGTH);
                assertThat(widened).hasSize(80);

                SecUserRecord record = SecUserRecord.decode(widened, ASCII);
                assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD);
                assertThat(record.secUsrFiller()).hasSize(23).isBlank();
                assertThat(record.secUsrType()).isEqualTo(index < 5 ? "A" : "U");
                assertThat(record.secUsrId())
                        .isEqualTo(index < 5 ? "ADMIN00" + (index + 1) : "USER000" + (index - 4));
            }
        }

        @ParameterizedTest(name = "{1} / {2} / {3}")
        @CsvSource({
                "0,ADMIN001,MARGARET,GOLD,A",
                "2,ADMIN003,RAYMOND,WHITMORE,A",
                "4,ADMIN005,GRANVILLE,LACHAPELLE,A",
                "5,USER0001,LAWRENCE,THOMAS,U",
                "7,USER0003,LAURITZ,ALME,U",
                "9,USER0005,LEE,TING,U"
        })
        @DisplayName("named users decode from the real EBCDIC dataset")
        void namedUsersDecode(int index, String id, String firstName, String lastName, String type) {
            SecUserRecord record = SecUserRecord.decode(ebcdicRows().get(index), EBCDIC);
            assertThat(record.secUsrId()).isEqualTo(id);
            assertThat(record.secUsrFname()).isEqualTo(pad(firstName, 20));
            assertThat(record.secUsrLname()).isEqualTo(pad(lastName, 20));
            assertThat(record.secUsrType()).isEqualTo(type);
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD);
        }

        @Test
        @DisplayName("the dataset is 800 bytes of ten records, each with a 23-space filler")
        void ebcdicDatasetCrossCheck() {
            List<byte[]> rows = ebcdicRows();
            assertThat(rows).hasSize(10);
            for (byte[] row : rows) {
                assertThat(row).hasSize(80);
                assertThat(SecUserRecord.decode(row, EBCDIC).secUsrFiller())
                        .isEqualTo(" ".repeat(23));
            }
        }

        @Test
        @DisplayName("the seed cards and the EBCDIC dataset agree field for field")
        void seedCardsAgreeWithTheDataset() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            List<byte[]> rows = ebcdicRows();
            for (int index = 0; index < rows.size(); index++) {
                SecUserRecord fromDataset = SecUserRecord.decode(rows.get(index), EBCDIC);
                SecUserRecord fromCard = SecUserRecord.decode(codec.padToDeclaredWidth(
                        SEED_CARDS.get(index).getBytes(ASCII), 80), ASCII);
                assertThat(fromCard).as("record %d", index).isEqualTo(fromDataset);
                assertThat(fromCard.fieldImages()).isEqualTo(fromDataset.fieldImages());
            }
        }
    }

    @Nested
    @DisplayName("Field access by COBOL name, the key, and rendering")
    class AccessAndRendering {

        @Test
        @DisplayName("the key equals the encoded record's leading eight bytes")
        void keyMatchesTheLeadingBytes() {
            for (byte[] row : ebcdicRows()) {
                SecUserRecord record = SecUserRecord.decode(row, EBCDIC);
                byte[] encoded = SecUserRecord.encode(record, EBCDIC);
                String leading = new String(Arrays.copyOfRange(encoded, SecUserRecord.KEY_OFFSET,
                        SecUserRecord.KEY_OFFSET + SecUserRecord.KEY_LENGTH), EBCDIC);
                assertThat(record.key()).isEqualTo(record.secUsrId()).isEqualTo(leading);
            }
        }

        @Test
        @DisplayName("field images are complete, in declaration order, and unmodifiable")
        void fieldImagesHonourTheDifferContract() {
            SecUserRecord record = SecUserRecord.decode(ebcdicRows().get(0), EBCDIC);
            assertThat(record.fieldImages().keySet()).containsExactly(
                    "SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME",
                    "SEC-USR-PWD", "SEC-USR-TYPE", "SEC-USR-FILLER");
            assertThat(record.fieldImages()).containsEntry("SEC-USR-ID", "ADMIN001");
            assertThat(record.fieldImages()).containsEntry("SEC-USR-PWD", SEED_PASSWORD);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> record.fieldImages().put("SEC-USR-ID", "X"));
        }

        @Test
        @DisplayName("every field resolves by its COBOL name")
        void imageResolvesEveryField() {
            SecUserRecord record = SecUserRecord.decode(ebcdicRows().get(0), EBCDIC);
            assertThat(record.image("SEC-USR-ID")).isEqualTo("ADMIN001");
            assertThat(record.image("SEC-USR-FNAME")).isEqualTo(pad("MARGARET", 20));
            assertThat(record.image("SEC-USR-LNAME")).isEqualTo(pad("GOLD", 20));
            assertThat(record.image("SEC-USR-PWD")).isEqualTo(SEED_PASSWORD);
            assertThat(record.image("SEC-USR-TYPE")).isEqualTo("A");
            assertThat(record.image("SEC-USR-FILLER")).isEqualTo(" ".repeat(23));
        }

        @ParameterizedTest(name = "\"{0}\" is rejected")
        @ValueSource(strings = {"SEC-USER-FNAME", "sec-usr-fname", "FILLER", "SEC_USR_ID", ""})
        @DisplayName("an unknown or misspelled field name is rejected, never answered with null")
        void imageRejectsUnknownNames(String unknown) {
            SecUserRecord record = SecUserRecord.blank();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.image(unknown))
                    .withMessageContaining("SEC-USER-DATA");
        }

        @Test
        @DisplayName("a null field name is rejected")
        void imageRejectsNull() {
            SecUserRecord record = SecUserRecord.blank();
            assertThatNullPointerException().isThrownBy(() -> record.image(null));
        }

        @Test
        @DisplayName("the rendering withholds the password and both names, and keeps the key legible")
        void renderingOmitsThePassword() {
            SecUserRecord record = SecUserRecord.decode(ebcdicRows().get(0), EBCDIC);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain(SEED_PASSWORD);
            assertThat(rendered).contains("<omitted>");
            // SEC-USR-ID is the VSAM key and the value COSGN00C matches on, so a sign-on parity
            // failure is diagnosed from it and no other field will do. It stays.
            assertThat(rendered).contains("SEC-USER-DATA", "ADMIN001");
            // SEC-USR-FNAME and SEC-USR-LNAME are personal data. The seed card's names were
            // published verbatim by the earlier rendering; now only their shape is reported, which
            // is what still finds a PIC X(20) field holding the wrong number of characters.
            assertThat(rendered).doesNotContain("MARGARET", "GOLD");
            assertThat(rendered).contains("SEC-USR-FNAME=[text len=20]", "SEC-USR-LNAME=[text len=20]");
            // Nothing stored changed: every value is still reachable by name, at full width.
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD);
            assertThat(record.image("SEC-USR-PWD")).isEqualTo(SEED_PASSWORD);
            assertThat(record.secUsrFname()).isEqualTo(pad("MARGARET", 20));
            assertThat(record.secUsrLname()).isEqualTo(pad("GOLD", 20));
            assertThat(record.image("SEC-USR-FNAME")).isEqualTo(pad("MARGARET", 20));
            assertThat(record.image("SEC-USR-LNAME")).isEqualTo(pad("GOLD", 20));
            assertThat(record.fieldImages().get("SEC-USR-FNAME")).isEqualTo(pad("MARGARET", 20));
            assertThat(record.fieldImages().get("SEC-USR-LNAME")).isEqualTo(pad("GOLD", 20));
        }

        @Test
        @DisplayName("a non-obvious password is still withheld from the rendering")
        void renderingWithholdsAnyPassword() {
            SecUserRecord record = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", "S3cr3tPW",
                    "U", ASCII);
            assertThat(record.toString()).doesNotContain("S3cr3tPW").contains("<omitted>");
        }

        @Test
        @DisplayName("no name reaches the rendering, however unusual it is")
        void renderingWithholdsAnyName() {
            // A surname is not guessable, so a guard that only rejected the seed cards' names would
            // pass while still publishing every real one. Assert on values that appear in no fixture.
            SecUserRecord record = SecUserRecord.of("USER0002", "ZQXVOLYA", "TREMBLAY-OKONKWO",
                    SEED_PASSWORD, "U", ASCII);
            assertThat(record.toString())
                    .doesNotContain("ZQXVOLYA")
                    .doesNotContain("TREMBLAY-OKONKWO")
                    .contains("SEC-USR-FNAME=[text len=20]", "SEC-USR-LNAME=[text len=20]");
            assertThat(record.secUsrFname()).isEqualTo(pad("ZQXVOLYA", 20));
            assertThat(record.secUsrLname()).isEqualTo(pad("TREMBLAY-OKONKWO", 20));
        }

        @Test
        @DisplayName("a blank name is reported as blank rather than as a width, which is a real defect")
        void renderingDistinguishesABlankNameFromAPresentOne() {
            // The shape is reported because the shape is what a fixed-width defect looks like: an
            // entirely blank SEC-USR-LNAME is a user card that was written without one.
            SecUserRecord record = SecUserRecord.of("USER0003", "ANNA", "", SEED_PASSWORD, "U",
                    ASCII);
            assertThat(record.toString())
                    .contains("SEC-USR-FNAME=[text len=20]")
                    .contains("SEC-USR-LNAME=[blank]");
        }

        @Test
        @DisplayName("a control character in a rendered field cannot forge a second log line")
        void renderingCannotForgeALogLine() {
            // SEC-USR-ID and SEC-USR-TYPE are PIC X spans read straight out of USRSEC, so either can
            // hold CR or LF. Rendered raw, the value appends a log line of its own choosing - CWE-117.
            SecUserRecord record = SecUserRecord.of("A\r\nFAKE", "ANNA", "SMITH", SEED_PASSWORD,
                    "\n", ASCII);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain("\r").doesNotContain("\n");
            assertThat(rendered.lines()).hasSize(1);
            assertThat(rendered).contains("X'0D'", "X'0A'");
            // The escape is lossless: the stored bytes are untouched and still say what was there.
            assertThat(record.secUsrId()).isEqualTo("A\r\nFAKE ");
            assertThat(record.secUsrType()).isEqualTo("\n");
        }

        @Test
        @DisplayName("identity covers all six fields, the password and the filler included")
        void identityCoversAllSixFields() {
            SecUserRecord base = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                    "U", ASCII);
            assertThat(base).isEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", ASCII));
            assertThat(base).hasSameHashCodeAs(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", ASCII));

            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0002", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", ASCII));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAURITZ", "THOMAS",
                    SEED_PASSWORD, "U", ASCII));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "ALME",
                    SEED_PASSWORD, "U", ASCII));
            assertThat(base).as("the password is part of the record's identity")
                    .isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", "OTHERPWD",
                            "U", ASCII));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "A", ASCII));
            assertThat(base).as("the filler is part of the record's identity")
                    .isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                            "U", "X".repeat(23), new FixedWidthCodec(ASCII)));
        }
    }

    @Nested
    @DisplayName("Failure modes, which fail loudly rather than adjusting silently")
    class FailureModes {

        @ParameterizedTest(name = "{0} byte(s)")
        @ValueSource(ints = {0, 1, 36, 50, 57, 79, 81, 160})
        @DisplayName("decoding rejects any byte count that is not exactly 80")
        void decodeRejectsAnyOtherLength(int length) {
            byte[] wrongWidth = new byte[length];
            Arrays.fill(wrongWidth, " ".getBytes(EBCDIC)[0]);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(wrongWidth, EBCDIC))
                    .withMessageContaining("80");
        }

        @Test
        @DisplayName("a short row names the filler and the widening step in its failure message")
        void shortRowFailureIsDiagnostic() {
            byte[] fiftySeven = SEED_CARDS.get(0).getBytes(ASCII);
            assertThat(fiftySeven).hasSize(57);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(fiftySeven, ASCII))
                    .withMessageContaining("SEC-USR-FILLER")
                    .withMessageContaining("57");
        }

        @Test
        @DisplayName("null arguments are rejected on every entry point")
        void nullArgumentsAreRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(null, EBCDIC));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(null, EBCDIC));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(new byte[80], (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(),
                            (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.of(null, "A", "B", "P", "U", ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.of("USER0001", null, "B", "P", "U", ASCII));
        }

        @ParameterizedTest(name = "widths {0}/{1}/{2}/{3}/{4}/{5}")
        @CsvSource({
                "7,20,20,8,1,23",
                "9,20,20,8,1,23",
                "8,19,20,8,1,23",
                "8,20,21,8,1,23",
                "8,20,20,7,1,23",
                "8,20,20,8,2,23",
                "8,20,20,8,1,22",
                "8,20,20,8,1,24"
        })
        @DisplayName("the constructor rejects any component that is not exactly its declared width")
        void constructorRejectsWrongWidths(int id, int firstName, int lastName, int password,
                                           int type, int filler) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SecUserRecord(
                    " ".repeat(id), " ".repeat(firstName), " ".repeat(lastName),
                    " ".repeat(password), " ".repeat(type), " ".repeat(filler)));
        }

        @Test
        @DisplayName("a rejected width names the offending field, its declared size and what it got")
        void wrongWidthFailureIsDiagnostic() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SecUserRecord("ADMIN001", "GOLD", " ".repeat(20),
                            " ".repeat(8), "A", " ".repeat(23)))
                    .withMessageContaining("SEC-USR-FNAME")
                    .withMessageContaining("20")
                    .withMessageContaining("4");
        }

        @Test
        @DisplayName("a null component is rejected naming the field it belongs to")
        void constructorRejectsNullComponents() {
            assertThatNullPointerException().isThrownBy(() -> new SecUserRecord(
                    null, " ".repeat(20), " ".repeat(20), " ".repeat(8), "A", " ".repeat(23)))
                    .withMessageContaining("SEC-USR-ID");
            assertThatNullPointerException().isThrownBy(() -> new SecUserRecord(
                    " ".repeat(8), " ".repeat(20), " ".repeat(20), " ".repeat(8), "A", null))
                    .withMessageContaining("SEC-USR-FILLER");
        }

        @Test
        @DisplayName("a multi-byte code page is refused, since offsets are absolute byte positions")
        void multiByteCharsetIsRefused() {
            Charset utf16 = Charset.forName("UTF-16");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(), utf16));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Right-pads to a declared width, the way a COBOL {@code PIC X} field holds a short value. */
    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * The {@code USRSEC} EBCDIC dataset, split into its 80-byte records.
     *
     * <p>Read strictly read-only: this dataset is part of the parity oracle and is never written.
     */
    private static List<byte[]> ebcdicRows() {
        try {
            byte[] raw = Files.readAllBytes(usrsecDataset());
            assertThat(raw).hasSize(800);
            List<byte[]> rows = new ArrayList<>();
            for (int offset = 0; offset < raw.length; offset += SecUserRecord.RECORD_LENGTH) {
                rows.add(Arrays.copyOfRange(raw, offset, offset + SecUserRecord.RECORD_LENGTH));
            }
            return rows;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read the USRSEC reference dataset", failure);
        }
    }

    /**
     * Locates the {@code USRSEC} reference dataset by walking up from the working directory until the
     * EBCDIC reference tree is found, so the tests do not depend on which directory the build was
     * launched from.
     *
     * <p>The file is matched on the {@code USRSEC} qualifier rather than spelled out as a full
     * dataset name, because no mainframe dataset name belongs in Java source: production dataset
     * names are resolved from configuration, and repeating one here - even for a read-only test
     * oracle - would undermine a scan that checks for exactly that.
     */
    private static Path usrsecDataset() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path ebcdic = candidate.resolve("app/data/EBCDIC");
            if (Files.isDirectory(ebcdic)) {
                try (var entries = Files.list(ebcdic)) {
                    return entries
                            .filter(path -> path.getFileName().toString().contains("USRSEC"))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "No USRSEC dataset under " + ebcdic
                                            + "; it is the byte-level parity oracle"));
                } catch (IOException failure) {
                    throw new UncheckedIOException("Could not list " + ebcdic, failure);
                }
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("The EBCDIC reference tree was not found above "
                + Path.of("").toAbsolutePath() + "; it holds the byte-level parity oracle");
    }
}
