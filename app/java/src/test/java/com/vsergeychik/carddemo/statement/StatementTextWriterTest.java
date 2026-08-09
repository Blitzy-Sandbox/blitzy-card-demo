package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementTextWriter.RecordSink;
import com.vsergeychik.carddemo.statement.StatementTextWriter.SlotKind;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementFile;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementLine;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementSlot;
import com.vsergeychik.carddemo.common.RecordImageForm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link StatementTextWriter}, the 80-byte {@code STMTFILE} statement record writer.
 *
 * <p>Plain JUnit 5 throughout: no application context, no {@code JobLauncher}, no filesystem and no
 * database. Every record is collected by an in-memory {@link RecordSink}, which is the seam the class
 * under test exposes precisely so that this is possible (practice B10, gate G51). The one place a
 * database type appears at all is {@link JdbcSinkTests}, where a mocked
 * {@link java.sql.PreparedStatement} is used to assert that the whole record image crosses as
 * <em>one parameter</em>, in whichever representation the deployment configured - both are exercised,
 * so neither is theoretical - and even there nothing is connected to anything.
 *
 * <h2>Provenance: every expectation here is STATICALLY DERIVED, and nothing was ever executed</h2>
 * The expected images below were <strong>statically derived</strong> - written by reading
 * {@code app/cbl/CBSTM03A.CBL:L85-L146} span by span and counting {@code PIC} widths, and, for the
 * two masks, by applying IBM Enterprise COBOL's editing rules to the declared pictures. Widths and
 * offsets come from the copybook and the {@code FD}; the record geometry is corroborated by
 * {@code app/jcl/CREASTMT.JCL:L89}.
 *
 * <p><strong>No captured, recorded or replayed COBOL execution baseline exists, and none is claimed
 * anywhere in this file.</strong> Running the twenty-eight legacy programs is impossible in this
 * environment - there is no z/OS runtime, the available compiler has indexed file support disabled
 * and no Language Environment {@code CEE*} services, and {@code app/cpy/CUSTREC.cpy} - which
 * {@code CBSTM03A} copies - does not even parse because of literal tab characters in its margin. The
 * substitution of static derivation for execution capture, and its residual risk, are recorded in the
 * migration plan as risk R-A. Stating the provenance rather than absorbing the limitation is practice
 * <strong>B12</strong>; a reader must never mistake these strings for observed output.
 *
 * <p>That provenance is also what makes them an audit rather than a restatement: they were derived
 * from the copybook rather than read off the implementation, so if the implementation and these
 * strings agree, they agree with the copybook.
 *
 * <h2>The record is 80 bytes, and this dataset has no width conflict</h2>
 * Two independent declarations, and they agree:
 * <ul>
 *   <li>{@code app/cbl/CBSTM03A.CBL:L44-L45} - {@code FD STMT-FILE.} then
 *       {@code 01 FD-STMTFILE-REC PIC X(80).}</li>
 *   <li>{@code app/jcl/CREASTMT.JCL:L89} - on {@code STEP040}, the step that <em>creates</em> the
 *       dataset at {@code L87}, {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)}. The {@code STEP030}
 *       {@code IEFBR14} pre-delete at {@code L72-L75} declares the <em>same</em>
 *       {@code LRECL=80,BLKSIZE=8000}.</li>
 * </ul>
 * So, unlike the sibling HTML dataset - which that same JCL declares at 80 in the pre-delete step and
 * at 100 in the creating step - {@code STMTFILE} carries <strong>no 80-versus-100 conflict</strong>
 * and the migration plan's risk R-G does not apply to it.
 *
 * <p><strong>{@code app/jcl/CREASTMT.JCL:L90} must not be mistaken for a second {@code DCB}.</strong>
 * It is a corrupted, overwritten JCL card: a {@code SPACE=} clause followed, on the same line and
 * after a blank, by the tail of a record-format clause and the tail of an unrelated dataset name.
 * Everything after that blank is a JCL comment, so the card contributes no operand at all.
 * <strong>{@code L89} is the authoritative {@code DCB}</strong> and {@code L91} the authoritative
 * {@code DSN}. The defect is recorded here and left in place in the read-only source rather than
 * repaired, which is practice <strong>B4</strong>; the corrupted text is described rather than quoted
 * so that a mechanical scan of the Java sources for a mainframe dataset name stays a clean signal
 * (gate G46).
 *
 * <h2>The gates asserted here</h2>
 * <ul>
 *   <li><strong>G19, G20</strong> - every rendered line is exactly 80 bytes, in both the freshly
 *       reset and the fully populated state, and the configured record length is cross-checked at
 *       construction.</li>
 *   <li><strong>G21</strong> - {@code FILLER} survives {@code INITIALIZE}. This is the assertion that
 *       catches the highest-risk misreading in the class: implementing the reset as a whole-buffer
 *       blank would still leave every line 80 bytes long, so only a byte-level check finds it.</li>
 *   <li><strong>G22, G23, G24</strong> - the masks take {@link BigDecimal} and truncate; a value that
 *       overflows nine integer digits loses its high-order digit rather than being rounded or
 *       rejected.</li>
 *   <li><strong>G46</strong> - the dataset name reaches the insert statement from configuration; the
 *       tests supply their own, and no mainframe dataset literal is needed by the class.</li>
 *   <li><strong>G47</strong> - the {@code FILE STATUS} outcome of an accepted write is tied to the
 *       COBOL {@code '00'} itself, and a rejected write surfaces the {@code WHEN OTHER} arm rather
 *       than abending: the abend decision belongs to the statement job, not to this writer.</li>
 *   <li><strong>G49</strong> - every branch of the class under test is driven from both sides, so the
 *       {@code statement} package clears its own {@code BRANCH} ratio without leaning on any
 *       other.</li>
 *   <li><strong>G51</strong> - no HTTP layer and no {@code JobLauncher} is in the path of a single
 *       assertion.</li>
 *   <li><strong>G52</strong> - every import is explicit; there is no wildcard import in this
 *       file.</li>
 *   <li><strong>G53</strong> - {@link StructuralTests#everyStaticFieldIsFinal()} walks the class and
 *       its nested types by reflection and asserts there is no mutable static state.</li>
 *   <li><strong>G54</strong> - nothing here depends on the wall clock, the locale, the time zone, the
 *       platform default charset, a random source, the network, the filesystem or the order the tests
 *       run in, so one {@code mvn -B clean verify} is deterministic and non-interactive.</li>
 * </ul>
 *
 * <h2>User-specified rules</h2>
 * {@code review_rules} returns exactly one line - "No user rules provided." - and that single line is
 * the whole document, so <strong>no user rule governs this file</strong>. Its absence is not licence
 * to lower the bar: the migration plan's twelve enterprise practices bind in their place, and the ones
 * bearing on a test file are <strong>B1</strong> (nothing beyond the JUnit 5, Mockito and AssertJ the
 * pom already declares, and no coordinate or version written into test code), <strong>B3</strong> (the
 * COBOL, copybook, JCL and CSD trees are cited as provenance and never read from disk, still less
 * written to), <strong>B4</strong> (the corrupted JCL card above is recorded, not repaired),
 * <strong>B7</strong> and <strong>B8</strong> (above), <strong>B9</strong> (a fresh writer and a fresh
 * handle per test method; no static mutable state), <strong>B10</strong> (these tests ship with the
 * implementation), <strong>B11</strong> (absolute byte offsets and exact widths, asserted against the
 * copybook, with no third-party copybook parser anywhere) and <strong>B12</strong> (above).
 */
@DisplayName("StatementTextWriter - the 80-byte STMTFILE plain-text statement record")
class StatementTextWriterTest {

    /** The code page every test names explicitly; never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page, used to prove the encoding is genuinely the injected one. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * A stand-in dataset name. The real one lives only in {@code application.yml}.
     *
     * <p>Well formed as z/OS requires: four qualifiers of at most eight characters each, every one
     * beginning with a letter. The earlier stand-in here spelled out {@code STATEMENT}, which is nine
     * characters and so is not a qualifier a mainframe would accept - a reminder that a stand-in still
     * has to obey the grammar it stands in for.
     */
    private static final String TEST_DSNAME = "TEST.M2.STATEMNT.PS";

    /** The declared record width, restated here from the COBOL rather than read from the class. */
    private static final int EIGHTY = 80;

    /** The declared width of both edited pictures, restated from the COBOL. */
    private static final int THIRTEEN = 13;

    /**
     * Builds the {@code STMTFILE} binding a test uses, with the record length under the test's
     * control so the constructor's cross-check can be driven both ways.
     *
     * @param recordLength the record length to configure
     * @return a catalogue containing exactly that one binding
     */
    private static DatasetBindings bindings(int recordLength) {
        return bindings(recordLength, "FB");
    }

    /**
     * Builds the {@code STMTFILE} binding a test uses, with both geometry attributes the constructor
     * cross-checks under the test's control so each can be driven either way.
     *
     * @param recordLength the record length to configure
     * @param recordFormat the record format to configure, or {@code null} to omit the key
     * @return a catalogue containing exactly that one binding
     */
    private static DatasetBindings bindings(int recordLength, String recordFormat) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put("STMTFILE", new DatasetBinding(TEST_DSNAME, "sequential", false, recordFormat,
                8000, recordLength, null, null, null, null, null));
        return catalogue;
    }

    /**
     * A writer over the given code page with a correctly configured 80-byte binding.
     *
     * @param charset the dataset code page, named explicitly
     * @return the writer
     */
    private static StatementTextWriter writer(Charset charset) {
        return new StatementTextWriter(new JdbcTemplate(), charset, bindings(EIGHTY),
                RecordImageForm.CHARACTER);
    }

    /** A writer over {@link #ASCII}. */
    private static StatementTextWriter writer() {
        return writer(ASCII);
    }

    /**
     * A correctly configured 80-byte writer whose {@code STMTFILE} binding names an arbitrary
     * location, so the dataset-name checks can be driven over every shape a deployment might supply.
     *
     * @param dsname the configured name, well formed or not, possibly {@code null}
     * @return the writer, which construction always succeeds for - see
     *         {@link JdbcSinkTests#stillConstructsUnderAFixtureBackedBinding()}
     */
    private static StatementTextWriter writerBoundTo(String dsname) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put("STMTFILE", new DatasetBinding(dsname, "sequential", false, "FB", 8000,
                EIGHTY, null, null, null, null, null));
        return new StatementTextWriter(new JdbcTemplate(), ASCII, catalogue, RecordImageForm.CHARACTER);
    }

    /**
     * An in-memory {@link RecordSink}: the whole reason the sink is an interface.
     *
     * <p>Records are appended in the order they arrive, so a test can assert emission order as well
     * as content, and the configured close outcome is settable so the close-reporting path can be
     * driven both ways.
     */
    private static final class CollectingSink implements RecordSink {

        /** Every record handed over, in call order. */
        private final List<byte[]> records = new ArrayList<>();

        /** What {@link #write(byte[])} reports. */
        private final FileStatus.Outcome writeOutcome;

        /** What {@link #close()} reports. */
        private final FileStatus.Outcome closeOutcome;

        /** How many times {@link #close()} was called, so idempotency can be asserted. */
        private int closeCalls;

        CollectingSink() {
            this(FileStatus.Outcome.OK, FileStatus.Outcome.OK);
        }

        CollectingSink(FileStatus.Outcome writeOutcome, FileStatus.Outcome closeOutcome) {
            this.writeOutcome = writeOutcome;
            this.closeOutcome = closeOutcome;
        }

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            records.add(recordImage);
            return writeOutcome;
        }

        @Override
        public FileStatus.Outcome close() {
            closeCalls++;
            return closeOutcome;
        }

        /**
         * The collected records decoded under a named code page.
         *
         * @param charset the code page to decode with
         * @return one string per record, in emission order
         */
        List<String> decoded(Charset charset) {
            List<String> images = new ArrayList<>(records.size());
            for (byte[] record : records) {
                images.add(new String(record, charset));
            }
            return images;
        }
    }

    /**
     * Populates all eleven slots with values chosen to exercise every {@code MOVE} rule at once: an
     * under-width name, equal-width address lines, an account id needing an eleven-digit zero fill, a
     * balance needing high-order truncation, a three-digit score, an over-width description and both
     * amount signs.
     *
     * @param file the handle to populate
     */
    private static void populate(StatementFile file) {
        file.setName("JOHN Q PUBLIC");
        file.setAddressLine1("100 MAIN STREET");
        file.setAddressLine2("APT 3B");
        file.setAddressLine3("SPRINGFIELD IL USA 62701");
        file.setAccountId(1L);
        file.setCurrentBalance(new BigDecimal("9999999999.99"));
        file.setFicoScore(705);
        file.setTransactionId("0000000000000001");
        file.setTransactionDetails("D".repeat(100));
        file.setTransactionAmount(new BigDecimal("-919.00"));
        file.setTotalTransactionAmount(new BigDecimal("0.41"));
    }

    // =============================================================================================

    @Nested
    @DisplayName("Record geometry - 80 bytes per line, 17 lines, 1360 bytes of group")
    class GeometryTests {

        @Test
        @DisplayName("01 STATEMENT-LINES declares 17 line groups, ST-LINE14A included "
                + "(CBSTM03A.CBL:L85-L146)")
        void declaresSeventeenLines() {
            assertThat(StatementLine.values()).hasSize(17);
            assertThat(StatementTextWriter.LINE_COUNT).isEqualTo(17);
            assertThat(StatementLine.valueOf("ST_LINE14A").cobolName()).isEqualTo("ST-LINE14A");
            assertThat(StatementLine.ST_LINE14A.ordinal())
                    .isEqualTo(StatementLine.ST_LINE14.ordinal() + 1);
            assertThat(StatementLine.ST_LINE15.ordinal())
                    .isEqualTo(StatementLine.ST_LINE14A.ordinal() + 1);
        }

        @Test
        @DisplayName("LRECL is 80 and BLKSIZE 8000, declared twice over "
                + "(CBSTM03A.CBL:L45 and CREASTMT.JCL:L89, corroborated by L72-L75)")
        void carriesTheDeclaredWidths() {
            assertThat(StatementTextWriter.RECORD_LENGTH).isEqualTo(EIGHTY);
            assertThat(StatementTextWriter.BLOCK_SIZE).isEqualTo(8000);
            assertThat(StatementTextWriter.DD_NAME).isEqualTo("STMTFILE");
            assertThat(StatementTextWriter.EDITED_AMOUNT_LENGTH).isEqualTo(THIRTEEN);
            assertThat(StatementTextWriter.STATEMENT_LINES_LENGTH).isEqualTo(17 * EIGHTY);
            assertThat(writer().recordLength()).isEqualTo(EIGHTY);
        }

        @ParameterizedTest
        @EnumSource(StatementLine.class)
        @DisplayName("every line sits at ordinal * 80 and is 80 wide (CBSTM03A.CBL:L85-L146)")
        void placesEveryLineAtItsOrdinalOffset(StatementLine line) {
            assertThat(line.offset()).isEqualTo(line.ordinal() * EIGHTY);
            assertThat(line.length()).isEqualTo(EIGHTY);
            assertThat(line.cobolName()).isEqualTo(line.name().replace("ST_LINE", "ST-LINE"));
        }

        @ParameterizedTest
        @EnumSource(StatementLine.class)
        @DisplayName("every freshly reset line renders as exactly 80 characters and 80 bytes "
                + "(CBSTM03A.CBL:L45, L459)")
        void rendersEveryResetLineAtEightyBytes(StatementLine line) {
            StatementFile file = writer().openOutput(new CollectingSink());
            assertThat(file.renderLine(line)).hasSize(EIGHTY);
            assertThat(file.renderLineBytes(line)).hasSize(EIGHTY);
        }

        @ParameterizedTest
        @EnumSource(StatementLine.class)
        @DisplayName("every fully populated line still renders as exactly 80 characters and 80 bytes "
                + "(CBSTM03A.CBL:L45)")
        void rendersEveryPopulatedLineAtEightyBytes(StatementLine line) {
            StatementFile file = writer().openOutput(new CollectingSink());
            populate(file);
            assertThat(file.renderLine(line)).hasSize(EIGHTY);
            assertThat(file.renderLineBytes(line)).hasSize(EIGHTY);
        }

        @ParameterizedTest
        @EnumSource(StatementSlot.class)
        @DisplayName("every slot sits inside its own line and cannot overflow it "
                + "(CBSTM03A.CBL:L85-L146)")
        void placesEverySlotInsideItsLine(StatementSlot slot) {
            assertThat(slot.offset()).isEqualTo(slot.line().offset() + slot.offsetWithinLine());
            assertThat(slot.offsetWithinLine() + slot.length()).isLessThanOrEqualTo(EIGHTY);
            assertThat(slot.cobolName()).startsWith("ST-");
            assertThat(slot.kind()).isNotNull();
        }

        @Test
        @DisplayName("the eleven mutable slots sit at the offsets counted from the group "
                + "(CBSTM03A.CBL:L91, L94, L97, L100, L109, L113, L118, L133, L135, L137, L142)")
        void declaresTheElevenSlotsAtTheirCountedOffsets() {
            assertThat(StatementSlot.values()).hasSize(11);

            assertThat(StatementSlot.ST_NAME.offset()).isEqualTo(80);
            assertThat(StatementSlot.ST_NAME.length()).isEqualTo(75);
            assertThat(StatementSlot.ST_ADD1.offset()).isEqualTo(160);
            assertThat(StatementSlot.ST_ADD2.offset()).isEqualTo(240);
            assertThat(StatementSlot.ST_ADD3.offset()).isEqualTo(320);
            assertThat(StatementSlot.ST_ADD3.length()).isEqualTo(EIGHTY);
            assertThat(StatementSlot.ST_ACCT_ID.offset()).isEqualTo(580);
            assertThat(StatementSlot.ST_CURR_BAL.offset()).isEqualTo(660);
            assertThat(StatementSlot.ST_CURR_BAL.length()).isEqualTo(THIRTEEN);
            assertThat(StatementSlot.ST_FICO_SCORE.offset()).isEqualTo(740);
            assertThat(StatementSlot.ST_TRANID.offset()).isEqualTo(1120);
            assertThat(StatementSlot.ST_TRANDT.offset()).isEqualTo(1137);
            assertThat(StatementSlot.ST_TRANDT.length()).isEqualTo(49);
            assertThat(StatementSlot.ST_TRANAMT.offset()).isEqualTo(1187);
            assertThat(StatementSlot.ST_TOTAL_TRAMT.offset()).isEqualTo(1267);

            // The amount column must line up between a detail line and the total line.
            assertThat(StatementSlot.ST_TOTAL_TRAMT.offsetWithinLine())
                    .isEqualTo(StatementSlot.ST_TRANAMT.offsetWithinLine());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("INITIALIZE STATEMENT-LINES - clears the eleven slots and no FILLER byte")
    class InitializeTests {

        @Test
        @DisplayName("ST-LINE0's banner survives: 31 asterisks, the 18-character literal, 31 "
                + "asterisks (CBSTM03A.CBL:L86-L89)")
        void leavesTheOpeningBannerIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE0);

            assertThat(image.substring(0, 31)).isEqualTo("*".repeat(31));
            assertThat(image.substring(31, 49)).isEqualTo("START OF STATEMENT");
            assertThat(image.substring(49)).isEqualTo("*".repeat(31));
        }

        @Test
        @DisplayName("ST-LINE15's banner survives: 32 asterisks, the 16-character literal, 32 "
                + "asterisks (CBSTM03A.CBL:L143-L146)")
        void leavesTheClosingBannerIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE15);

            assertThat(image.substring(0, 32)).isEqualTo("*".repeat(32));
            assertThat(image.substring(32, 48)).isEqualTo("END OF STATEMENT");
            assertThat(image.substring(48)).isEqualTo("*".repeat(32));
        }

        @Test
        @DisplayName("all three ALL '-' rules survive as 80 hyphens "
                + "(CBSTM03A.CBL:L102, L121, L127)")
        void leavesTheThreeRuleLinesIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String rule = "-".repeat(EIGHTY);

            assertThat(file.renderLine(StatementLine.ST_LINE5)).isEqualTo(rule);
            assertThat(file.renderLine(StatementLine.ST_LINE10)).isEqualTo(rule);
            assertThat(file.renderLine(StatementLine.ST_LINE12)).isEqualTo(rule);
        }

        @Test
        @DisplayName("ST-LINE13's three headings survive at offsets 0, 16 and 67, padded as declared "
                + "(CBSTM03A.CBL:L128-L131)")
        void leavesTheColumnHeadingsIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE13);

            // 'Tran ID         ' is 16 characters and exactly fills X(16).
            assertThat(image.substring(0, 16)).isEqualTo("Tran ID         ");
            // 'Tran Details    ' is 16 characters declared into X(51), so it is padded to 51.
            assertThat(image.substring(16, 67)).isEqualTo("Tran Details    " + " ".repeat(35));
            // '  Tran Amount' begins with TWO leading spaces and exactly fills X(13).
            assertThat(image.substring(67)).isEqualTo("  Tran Amount");
        }

        @Test
        @DisplayName("every FILLER is still there after a populate-then-reset cycle, and the previous "
                + "customer's values are gone (CBSTM03A.CBL:L459)")
        void stillShowsEveryFillerAfterAResetCycle() {
            StatementFile file = writer().openOutput(new CollectingSink());
            populate(file);
            file.initializeStatementLines();

            // The FILLER content is back - or rather, was never touched.
            assertThat(file.renderLine(StatementLine.ST_LINE0).substring(31, 49))
                    .isEqualTo("START OF STATEMENT");
            assertThat(file.renderLine(StatementLine.ST_LINE12)).isEqualTo("-".repeat(EIGHTY));
            assertThat(file.renderLine(StatementLine.ST_LINE13).substring(67))
                    .isEqualTo("  Tran Amount");
            assertThat(file.renderLine(StatementLine.ST_LINE7).substring(0, 20))
                    .isEqualTo("Account ID         :");
            assertThat(file.renderLine(StatementLine.ST_LINE14A).substring(0, 10))
                    .isEqualTo("Total EXP:");
            assertThat(file.renderLine(StatementLine.ST_LINE14).charAt(66)).isEqualTo('$');

            // And the previous customer's values are gone.
            assertThat(file.slotImage(StatementSlot.ST_NAME)).isEqualTo(" ".repeat(75));
            assertThat(file.slotImage(StatementSlot.ST_TRANDT)).isEqualTo(" ".repeat(49));
            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID)).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("INITIALIZE clears an alphanumeric slot to spaces across its full declared width "
                + "(CBSTM03A.CBL:L459)")
        void clearsAlphanumericSlotsToSpaces() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThat(file.slotImage(StatementSlot.ST_NAME)).isEqualTo(" ".repeat(75));
            assertThat(file.slotImage(StatementSlot.ST_ADD1)).isEqualTo(" ".repeat(50));
            assertThat(file.slotImage(StatementSlot.ST_ADD2)).isEqualTo(" ".repeat(50));
            assertThat(file.slotImage(StatementSlot.ST_ADD3)).isEqualTo(" ".repeat(EIGHTY));
            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID)).isEqualTo(" ".repeat(20));
            assertThat(file.slotImage(StatementSlot.ST_FICO_SCORE)).isEqualTo(" ".repeat(20));
            assertThat(file.slotImage(StatementSlot.ST_TRANID)).isEqualTo(" ".repeat(16));
            assertThat(file.slotImage(StatementSlot.ST_TRANDT)).isEqualTo(" ".repeat(49));
        }

        @Test
        @DisplayName("INITIALIZE clears an edited slot to its picture's zero image, not to spaces and "
                + "not to a run of zeros (CBSTM03A.CBL:L459 into L113, L137, L142)")
        void clearsEditedSlotsToTheirPictureZeroImage() {
            StatementFile file = writer().openOutput(new CollectingSink());

            // PIC 9(9).99- retains its leading zeros, so zero is a zero-filled image.
            assertThat(file.slotImage(StatementSlot.ST_CURR_BAL)).isEqualTo("000000000.00 ");
            // PIC Z(9).99- suppresses them, and declares no BLANK WHEN ZERO, so the fraction shows.
            assertThat(file.slotImage(StatementSlot.ST_TRANAMT)).isEqualTo("         .00 ");
            assertThat(file.slotImage(StatementSlot.ST_TOTAL_TRAMT)).isEqualTo("         .00 ");
        }

        @ParameterizedTest
        @EnumSource(SlotKind.class)
        @DisplayName("every slot kind is actually used by at least one slot "
                + "(CBSTM03A.CBL:L85-L146)")
        void everySlotKindIsUsedByAtLeastOneSlot(SlotKind kind) {
            assertThat(StatementSlot.values())
                    .anySatisfy(slot -> assertThat(slot.kind()).isEqualTo(kind));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Literal fidelity - every heading byte, including the awkward spaces")
    class LiteralTests {

        @Test
        @DisplayName("the three labels are each exactly 20 characters, spaces counted from source "
                + "(CBSTM03A.CBL:L108, L112, L117)")
        void rendersTheThreeLabelsExactly() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThat(file.renderLine(StatementLine.ST_LINE7).substring(0, 20))
                    .isEqualTo("Account ID         :")
                    .hasSize(20);
            assertThat(file.renderLine(StatementLine.ST_LINE8).substring(0, 20))
                    .isEqualTo("Current Balance    :")
                    .hasSize(20);
            assertThat(file.renderLine(StatementLine.ST_LINE9).substring(0, 20))
                    .isEqualTo("FICO Score         :")
                    .hasSize(20);
        }

        @Test
        @DisplayName("'Basic Details' is 13 characters padded to its declared X(14) "
                + "(CBSTM03A.CBL:L104-L106)")
        void padsTheBasicDetailsHeading() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE6);

            assertThat(image.substring(0, 33)).isEqualTo(" ".repeat(33));
            assertThat(image.substring(33, 47)).isEqualTo("Basic Details ");
            assertThat(image.substring(47)).isEqualTo(" ".repeat(33));
        }

        @Test
        @DisplayName("'TRANSACTION SUMMARY ' keeps its trailing space inside the quotes and fills all "
                + "20 positions (CBSTM03A.CBL:L122-L125)")
        void keepsTheTrailingSpaceInsideTheSummaryHeading() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE11);

            assertThat(image.substring(30, 50)).isEqualTo("TRANSACTION SUMMARY ").hasSize(20);
            assertThat(image.charAt(49)).isEqualTo(' ');
            assertThat(image.substring(0, 30)).isEqualTo(" ".repeat(30));
            assertThat(image.substring(50)).isEqualTo(" ".repeat(30));
        }

        @Test
        @DisplayName("ST-LINE14's one-space separator is at 16 and its dollar sign at 66 "
                + "(CBSTM03A.CBL:L134, L136)")
        void rendersTheDetailLineSeparators() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE14);

            assertThat(image.charAt(16)).isEqualTo(' ');
            assertThat(image.charAt(66)).isEqualTo('$');
        }

        @Test
        @DisplayName("ST-LINE14A is 'Total EXP:', 56 spaces, a dollar sign and the total "
                + "(CBSTM03A.CBL:L138-L142)")
        void rendersTheTotalLine() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE14A);

            assertThat(image.substring(0, 10)).isEqualTo("Total EXP:");
            assertThat(image.substring(10, 66)).isEqualTo(" ".repeat(56));
            assertThat(image.charAt(66)).isEqualTo('$');
        }
    }

    // =============================================================================================

    /**
     * All seventeen templates, asserted as complete 80-character images rather than span by span.
     *
     * <p>The individual span assertions above say where each literal is; these say that
     * <em>nothing else</em> is anywhere. A single miscounted space, a merged {@code FILLER}, an extra
     * pad byte or a slot at the wrong offset fails here even when every span assertion still passes,
     * which is why the whole-image form is worth stating separately.
     *
     * <p>Each expected image was <strong>statically derived</strong> by walking
     * {@code app/cbl/CBSTM03A.CBL:L85-L146} item by item and writing down each item's declared width
     * and {@code VALUE}, and each is built here span by span - {@code " ".repeat(33)} rather than a
     * long quoted run of spaces - so a reader can check it against the copybook by reading the
     * arithmetic instead of counting characters in a string literal. Every image is itself asserted to
     * be 80 characters before it is compared, so a mistake in the <em>expectation</em> fails as loudly
     * as a mistake in the implementation.
     */
    @Nested
    @DisplayName("The seventeen templates as whole 80-byte images (CBSTM03A.CBL:L85-L146)")
    class TemplateImageTests {

        /**
         * The freshly reset image of every line, in COBOL declaration order.
         *
         * <p>Reset state means: every {@code FILLER} holds its declared {@code VALUE}, every
         * alphanumeric slot holds spaces, and the three edited slots hold their picture's zero image -
         * {@code 9(9).99-} zero-filled at {@code L113}, {@code Z(9).99-} suppressed at {@code L137}
         * and {@code L142}.
         *
         * @return line and expected 80-character image pairs, one per line group
         */
        static Stream<Arguments> resetImages() {
            return Stream.of(
                    // L86-L89: ALL '*' X(31), 'START OF STATEMENT' X(18), ALL '*' X(31). The literal
                    // is itself 18 characters, so VALUE ALL neither repeats nor truncates it.
                    Arguments.of(StatementLine.ST_LINE0,
                            "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31)),
                    // L90-L92: ST-NAME X(75) cleared to spaces, then FILLER SPACES X(05).
                    Arguments.of(StatementLine.ST_LINE1, " ".repeat(75) + " ".repeat(5)),
                    // L93-L95 and L96-L98: ST-ADD1 / ST-ADD2 X(50), then FILLER SPACES X(30).
                    Arguments.of(StatementLine.ST_LINE2, " ".repeat(50) + " ".repeat(30)),
                    Arguments.of(StatementLine.ST_LINE3, " ".repeat(50) + " ".repeat(30)),
                    // L99-L100: ST-ADD3 X(80) fills the line; this line declares no FILLER at all.
                    Arguments.of(StatementLine.ST_LINE4, " ".repeat(80)),
                    // L101-L102: ALL '-' X(80).
                    Arguments.of(StatementLine.ST_LINE5, "-".repeat(80)),
                    // L103-L106: SPACES X(33), 'Basic Details' X(14) - 13 characters, so one trailing
                    // pad space - then SPACES X(33).
                    Arguments.of(StatementLine.ST_LINE6,
                            " ".repeat(33) + "Basic Details" + " " + " ".repeat(33)),
                    // L107-L110: the 20-character label, ST-ACCT-ID X(20) cleared, SPACES X(40).
                    Arguments.of(StatementLine.ST_LINE7,
                            "Account ID         :" + " ".repeat(20) + " ".repeat(40)),
                    // L111-L115: the 20-character label, ST-CURR-BAL 9(9).99- at its zero image, then
                    // TWO separately declared FILLER runs, X(07) and X(40).
                    Arguments.of(StatementLine.ST_LINE8,
                            "Current Balance    :" + "000000000.00 " + " ".repeat(7)
                                    + " ".repeat(40)),
                    // L116-L119: the 20-character label, ST-FICO-SCORE X(20) cleared, SPACES X(40).
                    Arguments.of(StatementLine.ST_LINE9,
                            "FICO Score         :" + " ".repeat(20) + " ".repeat(40)),
                    // L120-L121 and L126-L127: ALL '-' X(80), same as ST-LINE5.
                    Arguments.of(StatementLine.ST_LINE10, "-".repeat(80)),
                    // L122-L125: SPACES X(30), 'TRANSACTION SUMMARY ' X(20) - the literal already
                    // carries its trailing space and so fills all 20 - then SPACES X(30).
                    Arguments.of(StatementLine.ST_LINE11,
                            " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30)),
                    Arguments.of(StatementLine.ST_LINE12, "-".repeat(80)),
                    // L128-L131: 'Tran ID         ' X(16) exactly fills; 'Tran Details    ' is 16
                    // characters declared into X(51) and so is padded by 35; '  Tran Amount' begins
                    // with TWO spaces and exactly fills X(13).
                    Arguments.of(StatementLine.ST_LINE13,
                            "Tran ID         " + "Tran Details    " + " ".repeat(35)
                                    + "  Tran Amount"),
                    // L132-L137: ST-TRANID X(16) cleared, FILLER ' ' X(01), ST-TRANDT X(49) cleared,
                    // FILLER '$' X(01), ST-TRANAMT Z(9).99- at its suppressed zero image.
                    Arguments.of(StatementLine.ST_LINE14,
                            " ".repeat(16) + " " + " ".repeat(49) + "$" + "         .00 "),
                    // L138-L142: 'Total EXP:' X(10), SPACES X(56), FILLER '$' X(01), ST-TOTAL-TRAMT.
                    Arguments.of(StatementLine.ST_LINE14A,
                            "Total EXP:" + " ".repeat(56) + "$" + "         .00 "),
                    // L143-L146: ALL '*' X(32), 'END OF STATEMENT' X(16) - itself 16 characters, so
                    // again no repetition and no truncation - ALL '*' X(32).
                    Arguments.of(StatementLine.ST_LINE15,
                            "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32)));
        }

        @ParameterizedTest(name = "{0} is [{1}]")
        @MethodSource("resetImages")
        @DisplayName("every freshly reset template renders exactly as its spans declare, byte for byte "
                + "(CBSTM03A.CBL:L85-L146)")
        void rendersEveryResetTemplate(StatementLine line, String expected) {
            assertThat(expected)
                    .as("the expectation for %s must itself be 80 characters", line.cobolName())
                    .hasSize(EIGHTY);

            StatementFile file = writer().openOutput(new CollectingSink());

            assertThat(file.renderLine(line)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} returns to [{1}]")
        @MethodSource("resetImages")
        @DisplayName("INITIALIZE returns every template to that same image, clearing the slots and no "
                + "FILLER byte (CBSTM03A.CBL:L459)")
        void returnsEveryTemplateToItsDeclaredImage(StatementLine line, String expected) {
            StatementFile file = writer().openOutput(new CollectingSink());
            populate(file);

            file.initializeStatementLines();

            // This is the whole-image form of gate G21. A reset implemented as "blank the 1360-byte
            // area" would leave every line 80 bytes long and would still clear the slots, so a width
            // check and a slot check both pass - and this assertion is the one that fails, because the
            // banners, the three rules and every heading would have gone with it.
            assertThat(file.renderLine(line)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} reaches the sink as [{1}]")
        @MethodSource("resetImages")
        @DisplayName("the sink receives exactly those bytes, 80 of them, under the injected code page "
                + "(CBSTM03A.CBL:L45, CREASTMT.JCL:L89)")
        void handsThoseSameBytesToTheSink(StatementLine line, String expected) {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            assertThat(file.writeLine(line)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(sink.records).hasSize(1);
            assertThat(sink.records.get(0)).hasSize(EIGHTY);
            assertThat(sink.decoded(ASCII)).containsExactly(expected);
        }

        @Test
        @DisplayName("all seventeen lines are covered, so no template escapes the whole-image check "
                + "(CBSTM03A.CBL:L85-L146)")
        void coversAllSeventeenLines() {
            List<Object> covered = new ArrayList<>();
            resetImages().forEach(arguments -> covered.add(arguments.get()[0]));

            assertThat(covered)
                    .hasSize(StatementTextWriter.LINE_COUNT)
                    .containsExactly((Object[]) StatementLine.values());
        }
    }

    // =============================================================================================

    /**
     * The two numeric-edited masks. Every expected image below is exactly 13 characters and was
     * written by applying the picture's editing rules by hand, which is what makes these assertions
     * an independent check rather than a copy of the implementation.
     */
    @Nested
    @DisplayName("Numeric-edited masks - PIC 9(9).99- and PIC Z(9).99-")
    class MaskTests {

        /**
         * Cases for {@code PIC 9(9).99-}: leading zeros retained, trailing sign position.
         *
         * @return value and expected image pairs
         */
        static Stream<Arguments> zeroFilledCases() {
            return Stream.of(
                    // Positive: the sign position is a SPACE, never a plus.
                    Arguments.of(new BigDecimal("194.00"), "000000194.00 "),
                    // Negative: the sign position is a minus, and the digits are the magnitude.
                    Arguments.of(new BigDecimal("-919.00"), "000000919.00-"),
                    // Zero: no BLANK WHEN ZERO is declared, so the zeros print.
                    Arguments.of(new BigDecimal("0.00"), "000000000.00 "),
                    // A sub-one value keeps all nine integer zeros.
                    Arguments.of(new BigDecimal("0.41"), "000000000.41 "),
                    // The largest value that fits nine integer digits.
                    Arguments.of(new BigDecimal("999999999.99"), "999999999.99 "),
                    // ACCT-CURR-BAL is S9(10)V99: the tenth integer digit is discarded.
                    Arguments.of(new BigDecimal("9999999999.99"), "999999999.99 "),
                    // Truncation keeps the LOW-order nine digits, not the high-order nine.
                    Arguments.of(new BigDecimal("1234567890.12"), "234567890.12 "),
                    // Negative and over-wide together: sign from the sender, digits truncated.
                    Arguments.of(new BigDecimal("-9999999999.99"), "999999999.99-"),
                    // Excess fraction digits are TRUNCATED, never rounded: .239 becomes .23.
                    Arguments.of(new BigDecimal("1.239"), "000000001.23 "),
                    Arguments.of(new BigDecimal("1.999"), "000000001.99 "),
                    // A scale-0 sender is padded up to two fraction digits.
                    Arguments.of(new BigDecimal("7"), "000000007.00 "));
        }

        /**
         * Cases for {@code PIC Z(9).99-}: leading zeros suppressed to spaces.
         *
         * @return value and expected image pairs
         */
        static Stream<Arguments> zeroSuppressedCases() {
            return Stream.of(
                    Arguments.of(new BigDecimal("194.00"), "      194.00 "),
                    Arguments.of(new BigDecimal("-919.00"), "      919.00-"),
                    // Zero suppresses all nine integer positions; the fraction still prints.
                    Arguments.of(new BigDecimal("0.00"), "         .00 "),
                    // Suppression stops at the DECIMAL POINT, so it never reaches the fraction.
                    Arguments.of(new BigDecimal("0.41"), "         .41 "),
                    // A single significant digit leaves eight spaces before it.
                    Arguments.of(new BigDecimal("1.00"), "        1.00 "),
                    // Nothing is suppressed when the leading position is significant.
                    Arguments.of(new BigDecimal("999999999.99"), "999999999.99 "),
                    Arguments.of(new BigDecimal("9999999999.99"), "999999999.99 "),
                    Arguments.of(new BigDecimal("1234567890.12"), "234567890.12 "),
                    Arguments.of(new BigDecimal("-9999999999.99"), "999999999.99-"),
                    Arguments.of(new BigDecimal("1.239"), "        1.23 "),
                    // Truncating away the whole integer part re-enables suppression of all nine.
                    Arguments.of(new BigDecimal("1000000000.00"), "         .00 "),
                    Arguments.of(new BigDecimal("7"), "        7.00 "));
        }

        @ParameterizedTest(name = "9(9).99- of {0} is [{1}]")
        @MethodSource("zeroFilledCases")
        @DisplayName("PIC 9(9).99- retains leading zeros and truncates the tenth integer digit "
                + "(CBSTM03A.CBL:L113, fed from L484 and CVACT01Y.cpy:7)")
        void editsWithLeadingZerosRetained(BigDecimal value, String expected) {
            assertThat(StatementTextWriter.editZeroFilledAmount(value))
                    .isEqualTo(expected)
                    .hasSize(THIRTEEN);
        }

        @ParameterizedTest(name = "Z(9).99- of {0} is [{1}]")
        @MethodSource("zeroSuppressedCases")
        @DisplayName("PIC Z(9).99- suppresses leading zeros to spaces but never the fraction "
                + "(CBSTM03A.CBL:L137, L142)")
        void editsWithLeadingZerosSuppressed(BigDecimal value, String expected) {
            assertThat(StatementTextWriter.editZeroSuppressedAmount(value))
                    .isEqualTo(expected)
                    .hasSize(THIRTEEN);
        }

        @Test
        @DisplayName("the two masks differ only in their integer positions "
                + "(CBSTM03A.CBL:L113 against L137)")
        void differsOnlyInTheIntegerPositions() {
            BigDecimal value = new BigDecimal("194.00");
            String retained = StatementTextWriter.editZeroFilledAmount(value);
            String suppressed = StatementTextWriter.editZeroSuppressedAmount(value);

            assertThat(retained.substring(9)).isEqualTo(suppressed.substring(9));
            assertThat(retained.substring(0, 9)).isEqualTo("000000194");
            assertThat(suppressed.substring(0, 9)).isEqualTo("      194");
        }

        @Test
        @DisplayName("each picture renders CobolDecimal's scale-2 zero as its own zero image "
                + "(CBSTM03A.CBL:L459 into L113, L137, L142)")
        void rendersTheScaleTwoZero() {
            assertThat(CobolDecimal.monetaryZero().scale()).isEqualTo(2);
            assertThat(StatementTextWriter.editZeroFilledAmount(CobolDecimal.monetaryZero()))
                    .isEqualTo("000000000.00 ");
            assertThat(StatementTextWriter.editZeroSuppressedAmount(CobolDecimal.monetaryZero()))
                    .isEqualTo("         .00 ");
        }

        @Test
        @DisplayName("a null value is rejected rather than silently edited as a zero, because the two "
                + "pictures render zero differently (CBSTM03A.CBL:L113 against L137)")
        void rejectsANullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementTextWriter.editZeroFilledAmount(null))
                    .withMessageContaining("monetaryZero");
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementTextWriter.editZeroSuppressedAmount(null));
        }

        @Test
        @DisplayName("both masks truncate through CobolDecimal's named scale and rounding, and never "
                + "round up (no ROUNDED anywhere in the 28 programs; CBSTM03A.CBL:L113, L137)")
        void truncatesThroughTheNamedRoundingPolicy() {
            // The policy is named, not implied: scale 2 because every signed decimal PICTURE in the
            // codebase is V99, and RoundingMode.DOWN because the keyword ROUNDED appears zero times
            // in all 28 COBOL programs, so a store TRUNCATES the excess fraction (gate G24).
            assertThat(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(StatementTextWriter.EDITED_FRACTION_DIGITS)
                    .isEqualTo(2);
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            assertThat(StatementTextWriter.EDITED_INTEGER_DIGITS).isEqualTo(9);

            // A third fraction digit of 9 would round the second UP under any half-up or half-even
            // policy. It must not: the receiver keeps .99, and .999 becoming 1.00 would be a parity
            // failure invisible in every other assertion here.
            BigDecimal overPrecise = new BigDecimal("1.999");
            assertThat(CobolDecimal.storeAtPicture(overPrecise,
                    StatementTextWriter.EDITED_INTEGER_DIGITS,
                    StatementTextWriter.EDITED_FRACTION_DIGITS))
                    .isEqualByComparingTo(new BigDecimal("1.99"));
            assertThat(StatementTextWriter.editZeroFilledAmount(overPrecise))
                    .isEqualTo("000000001.99 ");
            assertThat(StatementTextWriter.editZeroSuppressedAmount(overPrecise))
                    .isEqualTo("        1.99 ");

            // Truncation is towards zero on the negative side too, and the sign position is decided
            // from the SENDING operand rather than from the truncated magnitude.
            assertThat(StatementTextWriter.editZeroFilledAmount(new BigDecimal("-1.999")))
                    .isEqualTo("000000001.99-");
            assertThat(StatementTextWriter.editZeroSuppressedAmount(new BigDecimal("-0.009")))
                    .isEqualTo(" ".repeat(9) + ".00-");
        }

        @Test
        @DisplayName("ACCT-CURR-BAL's tenth integer digit is DROPPED, not rounded and not reported "
                + "(CBSTM03A.CBL:L484 moves CVACT01Y.cpy:7 S9(10)V99 into L113's 9(9).99-)")
        void dropsTheTenthIntegerDigit() {
            // THIS IS REQUIRED PARITY BEHAVIOUR, NOT A DEFECT TO BE FIXED.
            //
            // The sending field ACCT-CURR-BAL is PIC S9(10)V99 - TEN integer digits - and the
            // receiving edited item ST-CURR-BAL is PIC 9(9).99-, which has only NINE integer
            // positions. A COBOL numeric MOVE aligns the operands on their implied decimal point, so
            // the digits that survive are the LOW-order ones and the excess HIGH-order digit is simply
            // discarded. MOVE ... TO ... at L484 carries no ON SIZE ERROR phrase, so the loss is not
            // reported either. Widening the mask to ten positions, rounding, throwing, or flagging the
            // overflow would each change an observable byte of every statement the COBOL prints.
            assertThat(StatementTextWriter.editZeroFilledAmount(new BigDecimal("1234567890.12")))
                    .as("the leading 1 is dropped; the surviving digits are the low-order nine")
                    .isEqualTo("234567890.12 ")
                    .hasSize(THIRTEEN);

            // The largest value that fits, and the smallest that does not, side by side.
            assertThat(StatementTextWriter.editZeroFilledAmount(new BigDecimal("999999999.99")))
                    .isEqualTo("999999999.99 ");
            assertThat(StatementTextWriter.editZeroFilledAmount(new BigDecimal("1000000000.00")))
                    .as("ten digits: the leading 1 goes and nine zeros remain, still printed")
                    .isEqualTo("000000000.00 ");

            // The same rule on the zero-suppressed mask, where losing the only significant digit
            // re-enables suppression of the entire integer run.
            assertThat(StatementTextWriter.editZeroSuppressedAmount(new BigDecimal("1000000000.00")))
                    .isEqualTo("         .00 ");
            assertThat(StatementTextWriter.editZeroSuppressedAmount(new BigDecimal("-1000000000.01")))
                    .as("the sign still comes from the sender even when its magnitude truncates away")
                    .isEqualTo(" ".repeat(9) + ".01-");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Slot setters - COBOL MOVE semantics, never a Java assignment")
    class MoveSemanticsTests {

        @Test
        @DisplayName("a short name is left justified in X(75) and padded on the right "
                + "(CBSTM03A.CBL:L91, L462-L469)")
        void padsAShortNameOnTheRight() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setName("JOHN Q PUBLIC");

            assertThat(file.slotImage(StatementSlot.ST_NAME))
                    .isEqualTo("JOHN Q PUBLIC" + " ".repeat(62))
                    .hasSize(75);
        }

        @Test
        @DisplayName("an over-long name truncates on the RIGHT, keeping the leading characters "
                + "(CBSTM03A.CBL:L91)")
        void truncatesAnOverLongNameOnTheRight() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setName("A".repeat(70) + "ZZZZZZZZZZ");

            assertThat(file.slotImage(StatementSlot.ST_NAME))
                    .isEqualTo("A".repeat(70) + "ZZZZZ")
                    .hasSize(75);
        }

        @Test
        @DisplayName("a 100-character TRNX-DESC truncates to its first 49 characters "
                + "(CBSTM03A.CBL:L677 into L135, COSTM01.CPY:28)")
        void truncatesTheTransactionDescriptionToFortyNine() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setTransactionDetails("A".repeat(49) + "B".repeat(51));

            assertThat(file.slotImage(StatementSlot.ST_TRANDT))
                    .isEqualTo("A".repeat(49))
                    .hasSize(49);
        }

        @Test
        @DisplayName("ACCT-ID zero-fills to eleven digits on the LEFT, then pads X(20) on the right "
                + "(CBSTM03A.CBL:L483 into L109, CVACT01Y.cpy:5)")
        void zeroFillsTheAccountIdThenPadsIt() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setAccountId(1L);

            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID))
                    .isEqualTo("00000000001" + " ".repeat(9))
                    .hasSize(20);
        }

        @Test
        @DisplayName("a full-width account id keeps all eleven digits (CVACT01Y.cpy:5)")
        void keepsAFullWidthAccountId() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setAccountId(99999999999L);

            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID))
                    .isEqualTo("99999999999" + " ".repeat(9));
        }

        @Test
        @DisplayName("a negative account id is rejected, because PIC 9(11) has no sign position "
                + "(CVACT01Y.cpy:5)")
        void rejectsANegativeAccountId() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatIllegalArgumentException().isThrownBy(() -> file.setAccountId(-1L));
        }

        @Test
        @DisplayName("a FICO score zero-fills to three digits, left justified in X(20) "
                + "(CBSTM03A.CBL:L485 into L118, CUSTREC.cpy:22)")
        void zeroFillsTheFicoScore() {
            StatementFile file = writer().openOutput(new CollectingSink());

            file.setFicoScore(705);
            assertThat(file.slotImage(StatementSlot.ST_FICO_SCORE))
                    .isEqualTo("705" + " ".repeat(17));

            file.setFicoScore(12);
            assertThat(file.slotImage(StatementSlot.ST_FICO_SCORE))
                    .isEqualTo("012" + " ".repeat(17));
        }

        @Test
        @DisplayName("a negative FICO score is rejected, because PIC 9(03) has no sign position "
                + "(CUSTREC.cpy:22)")
        void rejectsANegativeFicoScore() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatIllegalArgumentException().isThrownBy(() -> file.setFicoScore(-1));
        }

        @Test
        @DisplayName("the equal-width address lines move through unchanged, padded to their width "
                + "(CBSTM03A.CBL:L470-L471, L676, CUSTREC.cpy:9-10)")
        void movesTheAddressLines() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setAddressLine1("100 MAIN STREET");
            file.setAddressLine2("APT 3B");
            file.setAddressLine3("SPRINGFIELD IL USA 62701");
            file.setTransactionId("0000000000000001");

            assertThat(file.slotImage(StatementSlot.ST_ADD1))
                    .isEqualTo("100 MAIN STREET" + " ".repeat(35));
            assertThat(file.slotImage(StatementSlot.ST_ADD2))
                    .isEqualTo("APT 3B" + " ".repeat(44));
            assertThat(file.slotImage(StatementSlot.ST_ADD3))
                    .isEqualTo("SPRINGFIELD IL USA 62701" + " ".repeat(56));
            assertThat(file.slotImage(StatementSlot.ST_TRANID)).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("each edited amount lands in its own slot without disturbing its line's FILLER "
                + "(CBSTM03A.CBL:L484, L678, L434)")
        void writesTheEditedAmountsIntoTheirSlots() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setCurrentBalance(new BigDecimal("194.00"));
            file.setTransactionAmount(new BigDecimal("-919.00"));
            file.setTotalTransactionAmount(new BigDecimal("0.41"));

            assertThat(file.slotImage(StatementSlot.ST_CURR_BAL)).isEqualTo("000000194.00 ");
            assertThat(file.slotImage(StatementSlot.ST_TRANAMT)).isEqualTo("      919.00-");
            assertThat(file.slotImage(StatementSlot.ST_TOTAL_TRAMT)).isEqualTo("         .41 ");

            // The surrounding FILLER is untouched.
            assertThat(file.renderLine(StatementLine.ST_LINE8))
                    .isEqualTo("Current Balance    :000000194.00 " + " ".repeat(47));
            assertThat(file.renderLine(StatementLine.ST_LINE14A))
                    .isEqualTo("Total EXP:" + " ".repeat(56) + "$         .41 ");
        }

        @Test
        @DisplayName("a fully populated ST-LINE14 renders byte for byte "
                + "(CBSTM03A.CBL:L132-L137, L676-L679)")
        void rendersAFullyPopulatedDetailLine() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setTransactionId("0000000000000001");
            file.setTransactionDetails("D".repeat(100));
            file.setTransactionAmount(new BigDecimal("-919.00"));

            assertThat(file.renderLine(StatementLine.ST_LINE14))
                    .isEqualTo("0000000000000001 " + "D".repeat(49) + "$      919.00-")
                    .hasSize(EIGHTY);
        }

        @Test
        @DisplayName("every setter rejects a null sending value; COBOL has no null "
                + "(CBSTM03A.CBL:L85-L146)")
        void rejectsNullSendingValues() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatNullPointerException().isThrownBy(() -> file.setName(null));
            assertThatNullPointerException().isThrownBy(() -> file.setAddressLine1(null));
            assertThatNullPointerException().isThrownBy(() -> file.setAddressLine2(null));
            assertThatNullPointerException().isThrownBy(() -> file.setAddressLine3(null));
            assertThatNullPointerException().isThrownBy(() -> file.setTransactionId(null));
            assertThatNullPointerException().isThrownBy(() -> file.setTransactionDetails(null));
            assertThatNullPointerException().isThrownBy(() -> file.setCurrentBalance(null));
            assertThatNullPointerException().isThrownBy(() -> file.setTransactionAmount(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> file.setTotalTransactionAmount(null));
        }

        @Test
        @DisplayName("rejects a null slot or line identity when reading back")
        void rejectsNullIdentities() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatNullPointerException().isThrownBy(() -> file.slotImage(null));
            assertThatNullPointerException().isThrownBy(() -> file.renderLine(null));
            assertThatNullPointerException().isThrownBy(() -> file.renderLineBytes(null));
            assertThatNullPointerException().isThrownBy(() -> file.writeLine(null));
        }

        @Test
        @DisplayName("every alphanumeric slot goes through FixedWidthCodec.movePicX, never a Java "
                + "assignment (CBSTM03A.CBL:L462-L471, L676-L677)")
        void pinsEveryAlphanumericMoveToTheCodec() {
            // Pinned to the module's audited alphanumeric MOVE rather than to a hand-written expected
            // string, so the only way this holds is if the setter actually applies that rule. A plain
            // Java assignment neither pads nor truncates, and the resulting defect is invisible at the
            // call site - which is exactly why the rule has one implementation and this is tied to it.
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            String name = "JOHN Q PUBLIC";
            String addressLine1 = "100 MAIN STREET";
            String addressLine2 = "APT 3B";
            String addressLine3 = "SPRINGFIELD IL USA 62701";
            String transactionId = "0000000000000001";
            String overLongDescription = "D".repeat(100);

            StatementFile file = writer().openOutput(new CollectingSink());
            file.setName(name);
            file.setAddressLine1(addressLine1);
            file.setAddressLine2(addressLine2);
            file.setAddressLine3(addressLine3);
            file.setTransactionId(transactionId);
            file.setTransactionDetails(overLongDescription);

            assertThat(file.slotImage(StatementSlot.ST_NAME))
                    .isEqualTo(codec.movePicX(name, 75));
            assertThat(file.slotImage(StatementSlot.ST_ADD1))
                    .isEqualTo(codec.movePicX(addressLine1, 50));
            assertThat(file.slotImage(StatementSlot.ST_ADD2))
                    .isEqualTo(codec.movePicX(addressLine2, 50));
            assertThat(file.slotImage(StatementSlot.ST_ADD3))
                    .isEqualTo(codec.movePicX(addressLine3, EIGHTY));
            assertThat(file.slotImage(StatementSlot.ST_TRANID))
                    .isEqualTo(codec.movePicX(transactionId, 16));
            assertThat(file.slotImage(StatementSlot.ST_TRANDT))
                    .isEqualTo(codec.movePicX(overLongDescription, 49));
        }

        @ParameterizedTest(name = "account {0} and score {1}")
        @CsvSource({
            "0, 0",
            "1, 12",
            "705, 705",
            "99999999999, 999",
        })
        @DisplayName("a numeric sender is zero-filled by movePic9 and then space-padded by movePicX, "
                + "in that order (CBSTM03A.CBL:L483, L485)")
        void pinsEveryNumericMoveToTheCodec(long accountId, int ficoScore) {
            // Two MOVE rules apply in sequence and the ORDER matters: PIC 9 zero-fills on the LEFT,
            // and only then does the resulting digit string enter an alphanumeric receiver, which left
            // justifies and pads on the RIGHT. Composing the two codec calls here states that order
            // explicitly rather than asserting a single string that could be reached either way.
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            StatementFile file = writer().openOutput(new CollectingSink());

            file.setAccountId(accountId);
            file.setFicoScore(ficoScore);

            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID))
                    .isEqualTo(codec.movePicX(
                            codec.movePic9(accountId, StatementTextWriter.ACCOUNT_ID_DIGITS), 20))
                    .hasSize(20);
            assertThat(file.slotImage(StatementSlot.ST_FICO_SCORE))
                    .isEqualTo(codec.movePicX(
                            codec.movePic9(ficoScore, StatementTextWriter.FICO_SCORE_DIGITS), 20))
                    .hasSize(20);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Writing - one record per call, in call order, into an injected sink")
    class WriteTests {

        @Test
        @DisplayName("records reach the sink in call order, repeats included, never reordered or "
                + "coalesced (CBSTM03A.CBL:L488-L502 and L435-L437)")
        void emitsRecordsInCallOrder() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            // The COBOL writes ST-LINE5 twice and ST-LINE12 three times per statement; both
            // repetitions must reach the dataset.
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);
            file.writeLine(StatementLine.ST_LINE5);
            file.writeLine(StatementLine.ST_LINE6);
            file.writeLine(StatementLine.ST_LINE5);
            file.writeLine(StatementLine.ST_LINE12);
            file.writeLine(StatementLine.ST_LINE12);
            file.writeLine(StatementLine.ST_LINE15);

            List<String> emitted = sink.decoded(ASCII);
            assertThat(emitted).hasSize(7).allSatisfy(image -> assertThat(image).hasSize(EIGHTY));
            assertThat(emitted.get(0)).startsWith("*".repeat(31));
            assertThat(emitted.get(1)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(2)).contains("Basic Details");
            assertThat(emitted.get(3)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(4)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(5)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(6)).contains("END OF STATEMENT");
            assertThat(file.recordsWritten()).isEqualTo(7);
        }

        @Test
        @DisplayName("a record carries the values current at the WRITE, so one detail line per "
                + "transaction (CBSTM03A.CBL:L676-L679)")
        void emitsTheValuesCurrentAtWriteTime() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            file.setTransactionId("TRAN0000000000A1");
            file.setTransactionDetails("FIRST PURCHASE");
            file.setTransactionAmount(new BigDecimal("10.00"));
            file.writeLine(StatementLine.ST_LINE14);

            file.setTransactionId("TRAN0000000000A2");
            file.setTransactionDetails("SECOND PURCHASE");
            file.setTransactionAmount(new BigDecimal("-20.50"));
            file.writeLine(StatementLine.ST_LINE14);

            List<String> emitted = sink.decoded(ASCII);
            assertThat(emitted.get(0)).startsWith("TRAN0000000000A1 FIRST PURCHASE")
                    .endsWith("$       10.00 ");
            assertThat(emitted.get(1)).startsWith("TRAN0000000000A2 SECOND PURCHASE")
                    .endsWith("$       20.50-");
        }

        @Test
        @DisplayName("the sink gets a fresh 80-byte array each time, so it may retain it "
                + "(CBSTM03A.CBL:L45)")
        void handsTheSinkAFreshArrayEachTime() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            file.writeLine(StatementLine.ST_LINE12);
            file.writeLine(StatementLine.ST_LINE12);

            assertThat(sink.records).hasSize(2);
            assertThat(sink.records.get(0)).isNotSameAs(sink.records.get(1));
            assertThat(sink.records.get(0)).isEqualTo(sink.records.get(1));
        }

        @Test
        @DisplayName("a rejected write reports the WHEN OTHER arm and still counts "
                + "(CBSTM03A.CBL:L353-L359)")
        void reportsARejectedWriteAsOther() {
            CollectingSink sink = new CollectingSink(FileStatus.Outcome.OTHER,
                    FileStatus.Outcome.OK);
            StatementFile file = writer().openOutput(sink);

            assertThat(file.writeLine(StatementLine.ST_LINE0))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(FileStatus.Outcome.OTHER.batchStatus()).isEmpty();
            assertThat(file.recordsWritten()).isEqualTo(1);
        }

        @Test
        @DisplayName("WRITE ... FROM leaves the line area unchanged, so a line may be written twice "
                + "(CBSTM03A.CBL:L492 and L494)")
        void doesNotMutateTheLineAreaOnWrite() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);
            String before = file.renderLine(StatementLine.ST_LINE0);

            file.writeLine(StatementLine.ST_LINE0);

            assertThat(file.renderLine(StatementLine.ST_LINE0)).isEqualTo(before);
        }

        @ParameterizedTest
        @EnumSource(StatementLine.class)
        @DisplayName("every record reaching the sink is exactly 80 bytes, whether its slots hold "
                + "values shorter or longer than their fields (CBSTM03A.CBL:L45, CREASTMT.JCL:L89)")
        void handsTheSinkExactlyEightyBytesWhateverTheSlotsHold(StatementLine line) {
            // A fixed-length record is fixed-length regardless of what was moved into it, and this is
            // the assertion that makes gate G21 self-enforcing: drop any FILLER from the layout and the
            // total width fails here immediately, for whichever line lost it.
            CollectingSink underFilled = new CollectingSink();
            StatementFile shortValues = writer().openOutput(underFilled);
            shortValues.setName("A");
            shortValues.setAddressLine1("B");
            shortValues.setAddressLine2("C");
            shortValues.setAddressLine3("D");
            shortValues.setTransactionId("E");
            shortValues.setTransactionDetails("F");
            shortValues.setAccountId(0L);
            shortValues.setFicoScore(0);
            shortValues.setCurrentBalance(CobolDecimal.monetaryZero());
            shortValues.setTransactionAmount(CobolDecimal.monetaryZero());
            shortValues.setTotalTransactionAmount(CobolDecimal.monetaryZero());
            shortValues.writeLine(line);

            CollectingSink overFilled = new CollectingSink();
            StatementFile longValues = writer().openOutput(overFilled);
            longValues.setName("N".repeat(200));
            longValues.setAddressLine1("1".repeat(200));
            longValues.setAddressLine2("2".repeat(200));
            longValues.setAddressLine3("3".repeat(200));
            longValues.setTransactionId("T".repeat(200));
            longValues.setTransactionDetails("D".repeat(200));
            longValues.setAccountId(99999999999L);
            longValues.setFicoScore(999);
            longValues.setCurrentBalance(new BigDecimal("9999999999.99"));
            longValues.setTransactionAmount(new BigDecimal("-9999999999.99"));
            longValues.setTotalTransactionAmount(new BigDecimal("-9999999999.99"));
            longValues.writeLine(line);

            assertThat(underFilled.records).hasSize(1);
            assertThat(underFilled.records.get(0)).hasSize(EIGHTY);
            assertThat(underFilled.decoded(ASCII).get(0)).hasSize(EIGHTY);

            assertThat(overFilled.records).hasSize(1);
            assertThat(overFilled.records.get(0)).hasSize(EIGHTY);
            assertThat(overFilled.decoded(ASCII).get(0)).hasSize(EIGHTY);
        }

        @Test
        @DisplayName("an accepted write surfaces COBOL FILE STATUS '00' and a rejected one surfaces "
                + "the WHEN OTHER arm, neither abending (CBSTM03A.CBL:L353-L359, L460)")
        void surfacesTheFileStatusRatherThanAbending() {
            CollectingSink accepting = new CollectingSink();
            StatementFile accepted = writer().openOutput(accepting);

            FileStatus.Outcome outcome = accepted.writeLine(StatementLine.ST_LINE0);

            // '00' is the COBOL FILE STATUS for a completed operation, and the outcome the writer
            // surfaces is tied to that literal rather than merely being some enum constant named OK.
            assertThat(FileStatus.OK).isEqualTo("00");
            assertThat(outcome).isEqualTo(FileStatus.Outcome.OK);
            assertThat(outcome.batchStatus()).contains(FileStatus.OK);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.OK)).isEqualTo(outcome);
            assertThat(FileStatus.isOk(FileStatus.OK)).isTrue();
            assertThat(accepted.recordsWritten()).isEqualTo(1);

            // A rejected write SURFACES a status; it does not abend and does not close the dataset.
            // Deciding to abend belongs to the statement job, which is what CBSTM03A's own
            // EVALUATE ... WHEN OTHER guard at L353-L359 does, so this writer must leave that decision
            // open by reporting rather than throwing.
            CollectingSink rejecting = new CollectingSink(FileStatus.Outcome.OTHER,
                    FileStatus.Outcome.OK);
            StatementFile rejected = writer().openOutput(rejecting);

            assertThatCode(() -> rejected.writeLine(StatementLine.ST_LINE0))
                    .doesNotThrowAnyException();
            assertThat(rejected.writeLine(StatementLine.ST_LINE15))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(FileStatus.Outcome.OTHER.batchStatus())
                    .as("WHEN OTHER is the absence of a recognised FILE STATUS, not a code of its own")
                    .isEmpty();
            assertThat(rejected.isOpen()).isTrue();
            assertThat(rejected.recordsWritten()).isEqualTo(2);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Open and close - OPEN OUTPUT at L293, CLOSE at L339")
    class LifecycleTests {

        @Test
        @DisplayName("a handle opens already reset, open and with nothing written "
                + "(CBSTM03A.CBL:L293, L459)")
        void opensReadyToUse() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThat(file.isOpen()).isTrue();
            assertThat(file.recordsWritten()).isZero();
            assertThat(file.slotImage(StatementSlot.ST_CURR_BAL)).isEqualTo("000000000.00 ");
        }

        @Test
        @DisplayName("each open gets its own line area, so two runs never share slot state "
                + "(CBSTM03A.CBL:L85-L146 is per-run state)")
        void givesEachOpenItsOwnLineArea() {
            StatementTextWriter writer = writer();
            StatementFile first = writer.openOutput(new CollectingSink());
            StatementFile second = writer.openOutput(new CollectingSink());

            first.setName("CUSTOMER ONE");

            assertThat(first.slotImage(StatementSlot.ST_NAME)).startsWith("CUSTOMER ONE");
            assertThat(second.slotImage(StatementSlot.ST_NAME)).isEqualTo(" ".repeat(75));
        }

        @Test
        @DisplayName("CLOSE happens once, reports the sink's outcome and is idempotent afterwards "
                + "(CBSTM03A.CBL:L339)")
        void closesOnceAndIsIdempotent() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.isOpen()).isFalse();
            assertThat(sink.closeCalls).isEqualTo(1);

            // Closing again is reported OK and does not reach the sink a second time.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(sink.closeCalls).isEqualTo(1);
        }

        @Test
        @DisplayName("a failed close reports the WHEN OTHER arm rather than abending "
                + "(CBSTM03A.CBL:L339, L353-L359)")
        void reportsAFailedClose() {
            CollectingSink sink = new CollectingSink(FileStatus.Outcome.OK,
                    FileStatus.Outcome.OTHER);
            StatementFile file = writer().openOutput(sink);

            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("try-with-resources reproduces the OPEN OUTPUT / CLOSE pairing and logs rather "
                + "than throws on a failed close (CBSTM03A.CBL:L293, L339)")
        void supportsTryWithResources() {
            CollectingSink clean = new CollectingSink();
            try (StatementFile file = writer().openOutput(clean)) {
                file.writeLine(StatementLine.ST_LINE0);
            }
            assertThat(clean.closeCalls).isEqualTo(1);
            assertThat(clean.records).hasSize(1);

            // A non-OK close must not throw out of a try-with-resources block.
            CollectingSink failing = new CollectingSink(FileStatus.Outcome.OK,
                    FileStatus.Outcome.OTHER);
            StatementFile file = writer().openOutput(failing);
            file.close();
            assertThat(file.isOpen()).isFalse();
            assertThat(failing.closeCalls).isEqualTo(1);
        }

        @Test
        @DisplayName("a write after CLOSE is refused, naming the record count reached "
                + "(CBSTM03A.CBL:L293 opens once, L339 closes once)")
        void refusesAWriteAfterClose() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.writeLine(StatementLine.ST_LINE0);
            file.closeOutput();

            assertThatIllegalStateException()
                    .isThrownBy(() -> file.writeLine(StatementLine.ST_LINE15))
                    .withMessageContaining("ST-LINE15")
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("1 record(s)");
        }

        @Test
        @DisplayName("rendering still works after CLOSE, because a record area outlives an open "
                + "dataset (CBSTM03A.CBL:L85-L146, L339)")
        void stillRendersAfterClose() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.closeOutput();

            assertThat(file.renderLine(StatementLine.ST_LINE12)).isEqualTo("-".repeat(EIGHTY));
        }

        @Test
        @DisplayName("a null sink is rejected rather than silently discarding records "
                + "(CBSTM03A.CBL:L39 assigns the STMTFILE DD)")
        void rejectsANullSink() {
            StatementTextWriter writer = writer();

            assertThatNullPointerException().isThrownBy(() -> writer.openOutput(null))
                    .withMessageContaining("STMTFILE");
        }
    }

    // =============================================================================================

    /**
     * A sink that breaks its contract by answering {@code null}.
     *
     * <p>Not reachable through the production sink - {@link StatementTextWriter.JdbcRecordSink} always
     * answers - but entirely reachable through the injectable seam, which is the point: the seam exists
     * so a site can substitute its own sink, and a substituted sink is exactly the thing that might
     * return nothing on a path its author did not think about.
     */
    private static final class NullAnsweringSink implements RecordSink {

        /**
         * How many records this sink answers properly before it starts answering {@code null}, or
         * {@link Integer#MAX_VALUE} to answer properly always.
         *
         * <p>Counted rather than switched, because the interesting case is a sink that has already
         * accepted records: the record count the diagnostic names is only meaningful if the run got
         * somewhere first.
         */
        private final int writesBeforeNull;

        /** Whether {@link #close()} answers {@code null}. */
        private final boolean nullOnClose;

        /** How many records reached the sink. */
        private int writeCalls;

        /** How many closes reached the sink. */
        private int closeCalls;

        private NullAnsweringSink(final int writesBeforeNull, final boolean nullOnClose) {
            this.writesBeforeNull = writesBeforeNull;
            this.nullOnClose = nullOnClose;
        }

        /** A sink that answers every write and every close properly. */
        private static NullAnsweringSink compliant() {
            return new NullAnsweringSink(Integer.MAX_VALUE, false);
        }

        @Override
        public FileStatus.Outcome write(final byte[] recordImage) {
            this.writeCalls++;
            return this.writeCalls > this.writesBeforeNull ? null : FileStatus.Outcome.OK;
        }

        @Override
        public FileStatus.Outcome close() {
            this.closeCalls++;
            return this.nullOnClose ? null : FileStatus.Outcome.OK;
        }
    }

    @Nested
    @DisplayName("The sink contract - a null outcome is a defect, not a FILE STATUS")
    class SinkContractTests {

        @Test
        @DisplayName("A null write outcome is rejected at the write, naming the line and the count")
        void aNullWriteOutcomeIsRejected() {
            NullAnsweringSink sink = new NullAnsweringSink(1, false);
            StatementFile file = writer().openOutput(sink);
            file.writeLine(StatementLine.ST_LINE0);

            assertThatNullPointerException()
                    .isThrownBy(() -> file.writeLine(StatementLine.ST_LINE15))
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("write(byte[])")
                    .withMessageContaining("ST-LINE15")
                    .withMessageContaining("1 record(s)")
                    .withMessageContaining("FileStatus.Outcome.OTHER");
        }

        @Test
        @DisplayName("The rejected write does not advance the record count")
        void theRejectedWriteDoesNotAdvanceTheCount() {
            NullAnsweringSink sink = new NullAnsweringSink(0, false);
            StatementFile file = writer().openOutput(sink);

            assertThatNullPointerException()
                    .isThrownBy(() -> file.writeLine(StatementLine.ST_LINE0));

            // The record did reach the sink; what did not happen is the count advancing past a
            // record whose fate is unknown.
            assertThat(sink.writeCalls).isEqualTo(1);
            assertThat(file.recordsWritten()).isZero();
        }

        @Test
        @DisplayName("A null close outcome is rejected at closeOutput(), naming the count")
        void aNullCloseOutcomeIsRejected() {
            NullAnsweringSink sink = new NullAnsweringSink(Integer.MAX_VALUE, true);
            StatementFile file = writer().openOutput(sink);
            file.writeLine(StatementLine.ST_LINE0);

            assertThatNullPointerException()
                    .isThrownBy(file::closeOutput)
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("close()")
                    .withMessageContaining("1 record(s)");
        }

        @Test
        @DisplayName("A handle whose close was rejected is still closed, so the sink is reached once")
        void aRejectedCloseStillClosesTheHandle() {
            NullAnsweringSink sink = new NullAnsweringSink(Integer.MAX_VALUE, true);
            StatementFile file = writer().openOutput(sink);

            assertThatNullPointerException().isThrownBy(file::closeOutput);

            assertThat(file.isOpen()).isFalse();
            assertThat(sink.closeCalls).isEqualTo(1);

            // The second close is the idempotent no-op arm, so it cannot reach the broken sink again.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(sink.closeCalls).isEqualTo(1);
        }

        @Test
        @DisplayName("close() surfaces the contract violation rather than an unrelated NPE later")
        void closeSurfacesTheContractViolation() {
            NullAnsweringSink sink = new NullAnsweringSink(Integer.MAX_VALUE, true);
            StatementFile file = writer().openOutput(sink);

            // Before the fix this NPE came from Outcome.name() on a null, with the sink long gone
            // from the stack; now the message names the sink, the DD and the count.
            assertThatNullPointerException()
                    .isThrownBy(file::close)
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("close()");
        }

        @Test
        @DisplayName("A sink that answers properly is unaffected: OK and OTHER both pass through")
        void aCompliantSinkIsUnaffected() {
            NullAnsweringSink compliant = NullAnsweringSink.compliant();
            StatementFile file = writer().openOutput(compliant);

            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);

            CollectingSink rejecting = new CollectingSink(FileStatus.Outcome.OTHER,
                    FileStatus.Outcome.OTHER);
            StatementFile other = writer().openOutput(rejecting);

            assertThat(other.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(other.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("The interface documents both outcomes as non-null, so the guard is the contract")
        void theInterfaceDocumentsBothOutcomesAsNonNull() {
            // The default close() - the one a sink inherits when it declares nothing - answers OK,
            // which is what makes a null answer a deliberate act rather than an omission.
            RecordSink inheriting = recordImage -> FileStatus.Outcome.OK;

            assertThat(inheriting.close()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(inheriting.write(new byte[StatementTextWriter.RECORD_LENGTH]))
                    .isEqualTo(FileStatus.Outcome.OK);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Construction - configuration-bound, and cross-checked against the copybook")
    class ConstructionTests {

        @Test
        @DisplayName("the STMTFILE target is resolved by DD-name key from carddemo.datasets and "
                + "exposed verbatim (CBSTM03A.CBL:L39, CREASTMT.JCL:L87-L91)")
        void resolvesTheBindingByDdName() {
            StatementTextWriter writer = writer();
            DatasetBinding binding = writer.datasetBinding();

            assertThat(binding.dsname()).isEqualTo(TEST_DSNAME);
            assertThat(binding.organization()).isEqualTo("sequential");
            assertThat(binding.recordFormat()).isEqualTo("FB");
            assertThat(binding.blockSize()).isEqualTo(8000);
            assertThat(binding.recordLength()).isEqualTo(EIGHTY);
        }

        @Test
        @DisplayName("the code page is the injected one, never resolved here and never a platform "
                + "default (carddemo.charset.dataset)")
        void exposesTheInjectedCharset() {
            assertThat(writer(ASCII).datasetCharset()).isEqualTo(ASCII);
            assertThat(writer(EBCDIC).datasetCharset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("a configured record length other than 80 refuses to start, citing both "
                + "declarations (CBSTM03A.CBL:L45 and CREASTMT.JCL:L89)")
        void refusesAWrongRecordLength() {
            DatasetBindings wrong = bindings(100);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII, wrong,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("record-length 100")
                    .withMessageContaining("CBSTM03A.CBL:L45")
                    .withMessageContaining("CREASTMT.JCL:L89");
        }

        @Test
        @DisplayName("a configured record format other than FB refuses to start, whether the key says "
                + "the wrong thing or says nothing (RECFM=FB, CREASTMT.JCL:L89; gate G20)")
        void refusesAWrongRecordFormat() {
            // FB is what makes 'every record is exactly 80 bytes' true - it is the attribute the
            // right-space padding of every statement line rests on. A variable-format binding would
            // leave every other number in this class unchanged while making the padding meaningless,
            // which is exactly the kind of divergence the width check alone cannot see.
            assertThatIllegalStateException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII,
                            bindings(EIGHTY, "V"), RecordImageForm.CHARACTER))
                    .withMessageContaining("record-format 'V'")
                    .withMessageContaining("RECFM=FB")
                    .withMessageContaining("CREASTMT.JCL:L89");

            // An omitted key and a wrong value are different mistakes with different fixes, so the
            // diagnostic must not render a missing value as the four-letter word "null".
            assertThatIllegalStateException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII,
                            bindings(EIGHTY, null), RecordImageForm.CHARACTER))
                    .withMessageContaining("record-format absent");
        }

        @Test
        @DisplayName("accepts the record format however configuration cases it")
        void acceptsTheRecordFormatCaseInsensitively() {
            // A YAML author writing 'fb' has declared fixed blocked; refusing that would be pedantry
            // rather than a check, and the JCL's own casing is not a contract on configuration.
            assertThatCode(() -> new StatementTextWriter(new JdbcTemplate(), ASCII,
                    bindings(EIGHTY, "fb"), RecordImageForm.CHARACTER))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new StatementTextWriter(new JdbcTemplate(), ASCII,
                    bindings(EIGHTY, "FB"), RecordImageForm.CHARACTER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses to start when no STMTFILE binding is configured at all")
        void refusesAMissingBinding() {
            DatasetBindings empty = new DatasetBindings();

            assertThatIllegalStateException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII, empty,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("STMTFILE");
        }

        @Test
        @DisplayName("rejects every null collaborator, naming what is missing and why")
        void rejectsNullCollaborators() {
            DatasetBindings catalogue = bindings(EIGHTY);

            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementTextWriter(null, ASCII, catalogue,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("JdbcTemplate");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), null, catalogue,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("code page");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII, null,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The default JDBC sink - no column list, one parameter, the configured "
            + "representation")
    class JdbcSinkTests {

        @Test
        @DisplayName("builds a column-free insert with the dataset name as a delimited identifier")
        void buildsAColumnFreeInsert() {
            // A multi-part dotted name must survive as ONE identifier rather than being read as a
            // qualified catalogue-schema-table reference. The name used here is deliberately a
            // stand-in: the production dataset name belongs in application.yml alone, so writing it
            // into a Java source - even a test - would put a hit into the negative scan that gate
            // G46 relies on being empty.
            assertThat(writerBoundTo("FIVE.PART.DOTTED.DSN.NAME").insertStatement())
                    .isEqualTo("INSERT INTO \"FIVE.PART.DOTTED.DSN.NAME\" VALUES (?)");
        }

        @Test
        @DisplayName("composes that statement through DatasetRelation, not through text of its own")
        void composesThroughTheOneContract() {
            // Pinned to the module's one data-access contract rather than to a string literal, so a
            // second renderer cannot reappear here and pass: the only way this holds is if the writer
            // asks DatasetRelation. Its sibling StatementHtmlWriter is pinned the same way, which is
            // what makes the two statements identical apart from the dataset they name (F07).
            assertThat(writer().insertStatement())
                    .isEqualTo(DatasetRelation.of(TEST_DSNAME, EIGHTY).insertRecordImage());
        }

        @Test
        @DisplayName("refuses a name that is not a well-formed dataset name, rather than quoting it")
        void refusesAMalformedDatasetName() {
            // An embedded quotation mark was previously doubled and composed. Doubling is the right
            // rule for an identifier that legitimately contains a quote - and a z/OS dataset name
            // never does, so a name carrying one is not a dataset name and is refused outright. The
            // grammar's own verdict travels as the cause so the offending position is not lost.
            StatementTextWriter odd = writerBoundTo("ODD\"NAME");

            assertThatIllegalStateException()
                    .isThrownBy(odd::insertStatement)
                    .withMessageContaining("carddemo.datasets.STMTFILE.dsname")
                    .withMessageContaining("openOutput(RecordSink)")
                    .withCauseInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @DisplayName("refuses every shape a dataset name cannot take, and never composes it")
        @ValueSource(strings = {
            "A.B; DROP TABLE C",
            "A.B--C.D E",
            "A.B'C",
            "TOOLONGQUALIFIER.B",
            "9BAD.START",
            "A..B",
            "/tmp/carddemo/statement.txt",
            "classpath:fixtures/statement.txt",
        })
        void refusesEveryUnusableShape(String candidate) {
            StatementTextWriter bound = writerBoundTo(candidate);

            assertThatIllegalStateException().isThrownBy(bound::insertStatement);
            assertThatIllegalStateException().isThrownBy(bound::openOutput);
        }

        @Test
        @DisplayName("an absent, empty or all-blank dataset name is rejected, naming the property to "
                + "set (CREASTMT.JCL:L91 is the authoritative DSN, read through configuration)")
        void rejectsABlankDatasetName() {
            // Three genuinely distinct shapes, and all three arms of the guard are driven here:
            //   null    - the property is absent altogether, so the first condition short-circuits;
            //   ""      - the property is present but carries nothing, which is the second condition
            //             and the only way to reach it, since a non-empty value goes to the grammar;
            //   "   "   - present and non-empty, so it reaches the dataset-name grammar and is refused
            //             there instead, because a run of blanks is not a qualifier.
            assertThatIllegalStateException()
                    .isThrownBy(() -> writerBoundTo(null).insertStatement())
                    .withMessageContaining("carddemo.datasets.STMTFILE.dsname");
            assertThatIllegalStateException()
                    .isThrownBy(() -> writerBoundTo("").insertStatement())
                    .withMessageContaining("carddemo.datasets.STMTFILE.dsname");
            assertThatIllegalStateException()
                    .isThrownBy(() -> writerBoundTo("   ").insertStatement())
                    .withMessageContaining("carddemo.datasets.STMTFILE.dsname");

            // And none of the three can reach the default sink either, so a misconfigured deployment
            // cannot start writing records to a destination nobody named.
            assertThatIllegalStateException().isThrownBy(() -> writerBoundTo(null).openOutput());
            assertThatIllegalStateException().isThrownBy(() -> writerBoundTo("").openOutput());
            assertThatIllegalStateException().isThrownBy(() -> writerBoundTo("   ").openOutput());

            // An empty name still leaves the bean constructible and the geometry checked, exactly as a
            // fixture-backed location does: the refusal is deferred to the one place that would compose
            // the name into a statement, so the application context still starts (gate G3).
            assertThat(writerBoundTo("").recordLength()).isEqualTo(EIGHTY);
            assertThatCode(() -> writerBoundTo("").openOutput(new CollectingSink()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("still constructs under a fixture-backed binding, so the context starts (G3)")
        void stillConstructsUnderAFixtureBackedBinding() {
            // A deployment may bind STMTFILE to something that is not a dataset name and can never
            // become a SQL identifier - a filesystem location, for instance. Refusing the bean outright
            // would stop the application context from starting, even though every test and the parity
            // harness supply their own sink and never ask for the default one. So construction
            // succeeds, the geometry is still checked, and the refusal waits until something actually
            // asks for the default sink. (The shipped 'test' profile does not do this: since the
            // job-scoped dataset locations were corrected it names only well-formed dataset names.)
            StatementTextWriter fixtureBound = writerBoundTo("/tmp/carddemo/statement.txt");

            assertThat(fixtureBound.recordLength()).isEqualTo(EIGHTY);
            assertThat(fixtureBound.datasetBinding().dsname())
                    .isEqualTo("/tmp/carddemo/statement.txt");
            assertThatCode(() -> fixtureBound.openOutput(new CollectingSink()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the whole 80-byte image is bound as one parameter in the configured "
                + "representation (CBSTM03A.CBL:L45, carddemo.record-image.form)")
        void bindsTheRecordImageThroughTheConfiguredForm() throws SQLException {
            // The image reaches the driver as one parameter in one representation, and WHICH one is the
            // deployment's answer rather than this writer's. It used to be this writer's: it bound bytes
            // while the account, cross-reference and date-parameter access of the same deployment read
            // characters, and nothing reconciled them. Both forms are exercised, so neither is theoretical.
            for (RecordImageForm form : RecordImageForm.values()) {
                DataSource dataSource = Mockito.mock(DataSource.class);
                Connection connection = Mockito.mock(Connection.class);
                PreparedStatement statement = Mockito.mock(PreparedStatement.class);
                Mockito.when(dataSource.getConnection()).thenReturn(connection);
                Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
                Mockito.when(connection.createStatement()).thenReturn(Mockito.mock(Statement.class));

                StatementTextWriter writer = new StatementTextWriter(new JdbcTemplate(dataSource),
                        ASCII, bindings(EIGHTY), form);
                StatementFile file = writer.openOutput();

                assertThat(file.writeLine(StatementLine.ST_LINE12))
                        .isEqualTo(FileStatus.Outcome.OK);

                Mockito.verify(connection)
                        .prepareStatement("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
                if (form == RecordImageForm.BINARY) {
                    Mockito.verify(statement).setBytes(1, "-".repeat(EIGHTY).getBytes(ASCII));
                    Mockito.verify(statement, Mockito.never())
                            .setString(Mockito.anyInt(), Mockito.anyString());
                } else {
                    Mockito.verify(statement).setString(1, "-".repeat(EIGHTY));
                    Mockito.verify(statement, Mockito.never())
                            .setBytes(Mockito.anyInt(), Mockito.any());
                }
                Mockito.verify(statement).executeUpdate();
            }
        }

        @Test
        @DisplayName("maps a rejected write onto the WHEN OTHER outcome instead of throwing")
        void mapsARejectedWriteOntoOther() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Mockito.when(dataSource.getConnection())
                    .thenThrow(new SQLException("dataset unavailable"));

            StatementTextWriter writer = new StatementTextWriter(new JdbcTemplate(dataSource),
                    ASCII, bindings(EIGHTY), RecordImageForm.CHARACTER);
            StatementFile file = writer.openOutput();

            assertThat(file.writeLine(StatementLine.ST_LINE0))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(file.recordsWritten()).isEqualTo(1);
        }

        @Test
        @DisplayName("lets a configuration defect propagate rather than reporting it as a bad write")
        void letsAConfigurationDefectPropagate() {
            // A JdbcTemplate with no DataSource is a wiring defect, not a dataset condition. It
            // raises an unchecked type outside the DataAccessException family, which the sink
            // deliberately does not catch: reporting it as a failed write would make every record
            // abend with a misleading reason instead of failing once, clearly.
            StatementTextWriter subject = writer();

            // The open is now the first statement the sink issues, so that is where the defect
            // surfaces - once, at the top of the run, rather than once per statement line.
            assertThatIllegalStateException().isThrownBy(subject::openOutput);
        }

        @Test
        @DisplayName("closes cleanly when the destination is still there, because the pooled "
                + "connection is released per record and nothing is left to flush")
        void closesCleanly() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(plain);

            StatementTextWriter subject = new StatementTextWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(EIGHTY), RecordImageForm.CHARACTER);
            StatementFile file = subject.openOutput();

            assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("the open establishes the generation and empties it - one describe, one delete "
                + "(OPEN OUTPUT STMT-FILE, CBSTM03A.CBL:L293; CREASTMT.JCL:L87-L91)")
        void theOpenEstablishesAndClearsTheGeneration() throws SQLException {
            // DISP=(NEW,CATLG,DELETE) means this run writes into an empty dataset: the describe
            // resolves the destination and transfers nothing, and the delete is what NEW means. A run
            // that did not clear would leave last month's statements interleaved with this month's.
            // Neither statement defines anything (gate G44).
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement insert = Mockito.mock(PreparedStatement.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(insert);
            Mockito.when(connection.createStatement()).thenReturn(plain);

            StatementTextWriter subject = new StatementTextWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(EIGHTY), RecordImageForm.CHARACTER);
            StatementFile file = subject.openOutput();

            String describe = "SELECT * FROM \"" + TEST_DSNAME + "\" WHERE 1 = 0";
            assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            Mockito.verify(plain).execute(describe);
            Mockito.verify(plain).executeUpdate("DELETE FROM \"" + TEST_DSNAME + "\"");
            Mockito.verifyNoInteractions(insert);

            // The close probes again and clears nothing: exactly one DELETE for the whole run.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            Mockito.verify(plain, Mockito.times(2)).execute(describe);
            Mockito.verify(plain, Mockito.times(1))
                    .executeUpdate("DELETE FROM \"" + TEST_DSNAME + "\"");
        }

        @Test
        @DisplayName("a destination that cannot be opened is reported by the open, and by the close, "
                + "rather than silently reported as OK")
        void aRefusedOpenIsReported() throws SQLException {
            // CBSTM03A declares no FILE STATUS for STMT-FILE, so the job branches on neither outcome -
            // but the outcome must still be the truth, because it is what an operator reads and what a
            // caller that does guard could act on. Reporting '00' over a dataset that is not there is
            // the one answer that cannot be right.
            DataSource dataSource = Mockito.mock(DataSource.class);
            Mockito.when(dataSource.getConnection())
                    .thenThrow(new SQLException("dataset unavailable"));

            StatementTextWriter subject = new StatementTextWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(EIGHTY), RecordImageForm.CHARACTER);
            StatementFile file = subject.openOutput();

            assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("a destination that goes away mid-run is reported by the close "
                + "(CLOSE STMT-FILE, CBSTM03A.CBL:L339)")
        void aRefusedCloseIsReported() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(plain);
            Mockito.when(plain.execute(Mockito.anyString()))
                    .thenReturn(true)
                    .thenThrow(new SQLException("dataset dropped"));

            StatementTextWriter subject = new StatementTextWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(EIGHTY), RecordImageForm.CHARACTER);
            StatementFile file = subject.openOutput();

            assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Encoding - the injected code page, never a platform default")
    class EncodingTests {

        @Test
        @DisplayName("the same characters encode to different bytes under the two code pages, so the "
                + "encoding is demonstrably the injected one (CBSTM03A.CBL:L102)")
        void encodesUnderTheInjectedCodePage() {
            CollectingSink ascii = new CollectingSink();
            CollectingSink ebcdic = new CollectingSink();
            writer(ASCII).openOutput(ascii).writeLine(StatementLine.ST_LINE12);
            writer(EBCDIC).openOutput(ebcdic).writeLine(StatementLine.ST_LINE12);

            byte[] asciiRecord = ascii.records.get(0);
            byte[] ebcdicRecord = ebcdic.records.get(0);

            assertThat(asciiRecord).hasSize(EIGHTY);
            assertThat(ebcdicRecord).hasSize(EIGHTY);
            assertThat(asciiRecord).isNotEqualTo(ebcdicRecord);
            assertThat(asciiRecord[0]).isEqualTo((byte) 0x2D);
            assertThat(ebcdicRecord[0]).isEqualTo((byte) 0x60);

            // The same characters, whichever code page carries them.
            assertThat(ascii.decoded(ASCII)).isEqualTo(ebcdic.decoded(EBCDIC));
        }

        @Test
        @DisplayName("an EBCDIC space is 0x40 where an ASCII space is 0x20, across a whole reset line "
                + "(CBSTM03A.CBL:L90-L92)")
        void encodesSpacesUnderBothCodePages() {
            CollectingSink ebcdic = new CollectingSink();
            writer(EBCDIC).openOutput(ebcdic).writeLine(StatementLine.ST_LINE1);

            assertThat(ebcdic.records.get(0)).containsOnly((byte) 0x40);
        }

        @Test
        @DisplayName("the code page is the one the charset configuration resolves for "
                + "carddemo.charset.dataset, so it is a configured value and never a platform default")
        void takesTheCodePageTheConfigurationResolves() {
            // Resolved the way the application resolves it - through the configuration class itself,
            // from the property values the two profiles state - rather than by naming a Charset
            // constant and hoping the two agree. application.yml states IBM037 because its bindings
            // address the mainframe datasets; application-test.yml states US-ASCII because its bindings
            // address the nine authoritative text fixtures.
            Charset resolvedForFixtures =
                    new CobolCharsetConfig("IBM037", "US-ASCII", "US-ASCII").carddemoDatasetCharset();
            Charset resolvedForDatasets =
                    new CobolCharsetConfig("IBM037", "US-ASCII", "IBM037").carddemoDatasetCharset();

            assertThat(resolvedForFixtures).isEqualTo(ASCII);
            assertThat(resolvedForDatasets).isEqualTo(EBCDIC);
            assertThat(resolvedForDatasets)
                    .as("the two profiles resolve genuinely different code pages")
                    .isNotEqualTo(resolvedForFixtures);

            // Whichever the profile resolved, that is the one the writer reports and encodes with.
            assertThat(writer(resolvedForFixtures).datasetCharset()).isEqualTo(resolvedForFixtures);
            assertThat(writer(resolvedForDatasets).datasetCharset()).isEqualTo(resolvedForDatasets);

            CollectingSink underFixtureCodePage = new CollectingSink();
            CollectingSink underDatasetCodePage = new CollectingSink();
            writer(resolvedForFixtures).openOutput(underFixtureCodePage)
                    .writeLine(StatementLine.ST_LINE13);
            writer(resolvedForDatasets).openOutput(underDatasetCodePage)
                    .writeLine(StatementLine.ST_LINE13);

            assertThat(underFixtureCodePage.records.get(0)).hasSize(EIGHTY);
            assertThat(underDatasetCodePage.records.get(0)).hasSize(EIGHTY);
            assertThat(underFixtureCodePage.records.get(0))
                    .as("a heading line encodes to different bytes under the two resolved code pages")
                    .isNotEqualTo(underDatasetCodePage.records.get(0));
            assertThat(underFixtureCodePage.decoded(resolvedForFixtures))
                    .isEqualTo(underDatasetCodePage.decoded(resolvedForDatasets));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Structure - no mutable static state anywhere (gate G53)")
    class StructuralTests {

        @Test
        @DisplayName("declares every static field final, in the class and in every nested type")
        void everyStaticFieldIsFinal() {
            List<Class<?>> types = new ArrayList<>();
            types.add(StatementTextWriter.class);
            types.addAll(List.of(StatementTextWriter.class.getDeclaredClasses()));

            assertThat(types).hasSizeGreaterThan(4);
            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    // Synthetic fields are compiler and agent artefacts - an enum's $VALUES and
                    // JaCoCo's non-final $jacocoData probe array - and are not this class's state.
                    if (field.isSynthetic()) {
                        continue;
                    }
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .withFailMessage("static field %s.%s is not final",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps every instance field of the writer bean final, so it is never re-wired")
        void everyWriterFieldIsFinal() {
            for (Field field : StatementTextWriter.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .withFailMessage("instance field %s is not final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("exposes exactly one constructor, so wiring cannot be ambiguous")
        void exposesExactlyOneConstructor() {
            assertThat(StatementTextWriter.class.getDeclaredConstructors()).hasSize(1);
        }
    }

    // =================================================================================================
    // The abnormal disposition - the THIRD positional of DISP=(NEW,CATLG,DELETE).
    //
    // app/jcl/CREASTMT.JCL:L87-L91 declares three dispositions for STMTFILE and the writer used to
    // reproduce two. NEW is the open's clear; CATLG is what the close leaves behind; DELETE is what an
    // abended run must leave - which is nothing. For customer statements that is the point: a run that
    // abends part way has written complete, well-formed statements for the accounts it reached and
    // nothing for the rest, and nothing downstream can tell that set apart from a complete one.
    // =================================================================================================

    @Nested
    @DisplayName("The abnormal disposition deletes the statements an abended run wrote")
    class TheAbnormalDisposition {

        /**
         * A real in-memory relation, so the count-then-delete is measured rather than mocked.
         *
         * <p>{@code DB_CLOSE_DELAY=-1} because {@link SimpleDriverDataSource} opens a connection per
         * call: without it H2 would discard the database the moment the connection that created the
         * relation was returned.
         *
         * @return a template over a private H2 database already holding the STMTFILE relation
         */
        private JdbcTemplate liveTemplate() {
            JdbcTemplate template = new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(),
                    "jdbc:h2:mem:stmtfile-disp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
            template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (RECORD_IMAGE CHAR("
                    + EIGHTY + "))");
            return template;
        }

        /** @return how many records the relation holds */
        private int held(JdbcTemplate template) {
            Integer count = template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class);
            return count == null ? 0 : count;
        }

        /** @return a writer over the given live template */
        private StatementTextWriter writerOver(JdbcTemplate template) {
            return new StatementTextWriter(template, ASCII, bindings(EIGHTY),
                    RecordImageForm.CHARACTER);
        }

        @Test
        @DisplayName("every statement line this run wrote is deleted, and the close deleted nothing")
        void theGenerationIsDeleted() {
            JdbcTemplate template = liveTemplate();
            StatementFile file = writerOver(template).openOutput();

            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.writeLine(StatementLine.ST_LINE5)).isEqualTo(FileStatus.Outcome.OK);
            // CATLG: the close leaves the statements where they are. That is the whole distinction.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isEqualTo(2);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isZero();
        }

        @Test
        @DisplayName("a second discard neither issues anything nor contradicts the first")
        void theDiscardIsIdempotent() {
            JdbcTemplate template = liveTemplate();
            StatementFile file = writerOver(template).openOutput();
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isZero();
        }

        @Test
        @DisplayName("a run that wrote no statement deletes nothing and reports OK")
        void anEmptyRunDeletesNothing() {
            JdbcTemplate template = liveTemplate();
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", " ".repeat(EIGHTY));
            StatementFile file = writerOver(template).openOutput(new CollectingSink());

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isOne();
        }

        @Test
        @DisplayName("a relation holding statements this run did not write is left untouched")
        void aCountMismatchIsRefused() {
            // Deliberately loud: deleting statements that had already been issued to customers would be
            // far worse than an operator seeing an outcome.
            JdbcTemplate template = liveTemplate();
            StatementFile file = writerOver(template).openOutput();
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", " ".repeat(EIGHTY));

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(held(template)).isEqualTo(2);
        }

        @Test
        @DisplayName("a count the backend will not state is refused exactly as a wrong count is")
        void anUnstatedCountIsRefused() {
            // The other half of the guard above. A backend answering the count with SQL NULL has not said
            // the generation holds what this run wrote - it has said nothing - and nothing is not
            // permission to delete customer statements. A real COUNT(*) cannot be null, so the one call
            // is bent and everything else, including the open's own clear, runs for real.
            JdbcTemplate live = liveTemplate();
            JdbcTemplate template = Mockito.spy(live);
            StatementFile file = writerOver(template).openOutput();
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);

            Mockito.doReturn(null).when(template)
                    .queryForObject(Mockito.anyString(), Mockito.eq(Integer.class));

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(held(live))
                    .as("refused means untouched: the statement line this run wrote is still there")
                    .isOne();
        }

        @Test
        @DisplayName("a delete that removes a different number than it counted is reported, not called OK")
        void aDeleteRemovingADifferentCountIsReported() {
            // The count agreed and the delete was issued, then removed a different number of rows than the
            // count promised. Reporting OK would tell an operator that DISP=(NEW,CATLG,DELETE) had been
            // honoured for a set of statements that may still be partly present.
            JdbcTemplate template = Mockito.spy(liveTemplate());
            StatementFile file = writerOver(template).openOutput();
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);

            Mockito.doReturn(99).when(template).update(Mockito.anyString());

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("a backend that refuses the disposition is reported, never raised")
        void aRefusedDispositionIsReported() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
            Mockito.when(connection.createStatement()).thenReturn(plain);
            Mockito.when(plain.executeQuery(Mockito.anyString()))
                    .thenThrow(new SQLException("dataset dropped"));

            StatementTextWriter subject = new StatementTextWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(EIGHTY), RecordImageForm.CHARACTER);
            StatementFile file = subject.openOutput();
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);

            assertThatCode(() -> assertThat(file.discardGeneration())
                    .isEqualTo(FileStatus.Outcome.OTHER)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a collector sink reports OK without implementing anything, and the method is a "
                + "default so a lambda still compiles")
        void aCollectorSinkDefaultsToOk() {
            RecordSink minimal = recordImage -> FileStatus.Outcome.OK;
            StatementFile file = writer().openOutput(minimal);
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("a sink answering null from discard is read as OTHER rather than raising")
        void aNullDiscardOutcomeIsReported() {
            StatementFile file = writer().openOutput(new RecordSink() {
                @Override
                public FileStatus.Outcome write(byte[] recordImage) {
                    return FileStatus.Outcome.OK;
                }

                @Override
                public FileStatus.Outcome discard(int recordsWritten) {
                    return null;
                }
            });
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
        }
    }
}
