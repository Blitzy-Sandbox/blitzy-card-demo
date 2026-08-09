package com.vsergeychik.carddemo.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.AddressField;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.BasicDetail;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlFixedLine;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlRecordSink;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlStatementFile;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.JdbcHtmlRecordSink;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.TransactionField;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

/**
 * Behavioural-parity tests for {@link StatementHtmlWriter}, the owner of the 100-byte
 * {@code HTMLFILE} record of {@code app/cbl/CBSTM03A.CBL}.
 *
 * <h2>Provenance of every expected value: static derivation, not a captured run</h2>
 *
 * <p>Not one expectation in this file was captured from an execution of the legacy program. The
 * COBOL cannot be run in this environment at all, for eight independently verified reasons - no
 * z/OS runtime, indexed file support disabled in the only available compiler, subprogram linkage
 * that cannot produce an executable, a copybook that will not parse, no Language Environment
 * {@code CEE*} services, no CICS emulator, EBCDIC fixtures needing binary handling, and no
 * alternative compiler installable. Every value below was therefore <strong>derived statically</strong>
 * by reading the cited source line and applying the documented COBOL rule to it: the fixed-format
 * continuation rule for a literal, the alphanumeric {@code MOVE} rule for a width change, the
 * {@code STRING ... DELIMITED BY} rule for a composition, and the {@code PICTURE} clause for a
 * width. Each test names the line it was derived from, so a reviewer can re-derive it by hand.
 *
 * <p>That is a real difference in kind, and it is stated rather than glossed: a captured
 * expectation cannot encode a misreading, a derived one can. The mitigation is redundancy. Every
 * literal is asserted against an expected value written out independently of the enum in
 * {@link #expectedFixedLiterals()}, so a transcription slip has to be made identically twice to
 * survive; and every continued literal is asserted a third time in {@link ContinuationRule} against
 * its two source fragments joined by the compiler's own rule, so a slip has to be made three times
 * in three different shapes.
 *
 * <h2>The reference tree is evidence, and is never touched at run time</h2>
 *
 * <p>{@code app/cbl}, {@code app/cpy}, {@code app/jcl}, {@code app/csd} and {@code app/data} are
 * read-only evidence: they are the only oracle behavioural parity has, and this suite neither writes
 * to them nor <em>reads</em> from them while it runs (practice B3, gate G5). There is no
 * {@code Files.read}, no {@code Path.of}, no relative walk out of the Maven module and no
 * {@code Assumptions.assumeTrue} guarding a file that may not be there - a check that can silently
 * skip is not a check. The source was read while these tests were being written; what ships is the
 * derived value together with the line it came from.
 *
 * <p>Nothing here runs a Spring context, a {@code JobLauncher} or an HTTP layer, and nothing touches
 * a filesystem, a database or a network. The writer's sink is injected as a lambda that collects the
 * 100-byte images, so the bytes and their order are asserted directly (gate G51, practice B10), and
 * the run is deterministic: no clock, no locale, no default charset, no randomness and no order
 * dependence between tests (practice B7, gate G54).
 *
 * <h2>The record is 100 bytes, not 80 (gate G20, risk R-G)</h2>
 *
 * <p>{@code app/jcl/CREASTMT.JCL} declares the {@code HTMLFILE} DD twice, with two different
 * {@code LRECL}s. L69, in the {@code STEP030 EXEC PGM=IEFBR14,COND=(0,NE)} step that merely
 * pre-deletes the previous run's report, says {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)}. L94, in
 * the {@code STEP040 EXEC PGM=CBSTM03A,COND=(0,NE)} step that actually <strong>creates</strong> the
 * file, says {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}. The creating step is authoritative, and
 * {@code app/cbl/CBSTM03A.CBL:L47} settles it independently with
 * {@code 01 FD-HTMLFILE-REC PIC X(100)}. The conflict is recorded here rather than reconciled: 80 is
 * never used, and no average or first-wins rule is applied (practice B4).
 *
 * <h2>Two charsets that must never be conflated</h2>
 *
 * <p>{@code HTML-L04} is the literal {@code <meta charset="utf-8">}. That is <em>payload text</em> -
 * a byte sequence transcribed from a COBOL {@code VALUE} clause, describing how a browser should
 * later read the finished document. It says nothing whatsoever about how the dataset is encoded. The
 * dataset code page is a separate, injected {@link Charset} from {@code config/CobolCharsetConfig},
 * and it is what the 100 bytes are actually written in. {@link DatasetCodePageIsNotThePayloadCharset}
 * exists to hold those two apart, because collapsing them is a plausible-looking mistake that would
 * change every byte of every record.
 *
 * <h2>User-specified rules</h2>
 *
 * <p><strong>{@code review_rules} returns exactly one line - "No user rules provided." - and that
 * single line is the entire document, so no user rule governs this file.</strong> Their absence is
 * not licence to lower the bar. The twelve enterprise practices of the migration plan bind in their
 * place, and the ones bearing on this suite are cited inline throughout: B1 (only the test libraries
 * already on the closed classpath - JUnit Jupiter, Mockito, AssertJ - with no coordinate, version or
 * {@code pom.xml} edit), B2 (no {@code spring-batch-test}, which is not in the dependency set, and no
 * application context), B3 (the reference tree is never read or written at run time), B4 (this file is
 * the only artefact added, and conflicts are documented rather than silently resolved), B5 (the
 * declared-but-unwritten {@code HTML-L23} group and the never-selected {@code HTML-LTDS} literal are
 * both asserted to survive untouched), B6 (nothing security-related, and no escaping or masking is
 * introduced), B7 (deterministic and non-interactive), B8 (the charset is always named, imports are
 * explicit with no wildcard, and no mainframe dataset-name literal appears here), B9 (no mutable
 * static state; a fresh writer and a fresh handle per test), B11 (offsets and widths asserted by hand,
 * with no COBOL or copybook parser) and B12 (statically derived expectations, escalated as such rather
 * than presented as captured).
 */
@DisplayName("StatementHtmlWriter - the 100-byte HTMLFILE record of CBSTM03A")
class StatementHtmlWriterTest {

    /** The account-master record width, reused to build a deliberately wrong binding. */
    private static final int WRONG_RECORD_LENGTH_FROM_PREDELETE_STEP = 80;

    /** A dataset name shaped like the real one, but not the real one. */
    private static final String TEST_DSNAME = "TEST.M2.STATEMNT.HTML";

    /** The single space that a padded field is filled with. */
    private static final char SPACE = ' ';

    /** Collected records, in emission order. */
    private List<byte[]> emitted;

    /** The writer under test, wired with a mocked template and the ASCII code page. */
    private StatementHtmlWriter writer;

    /** The open handle. */
    private HtmlStatementFile file;

    /** The mocked template; only the default sink ever touches it. */
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void openWithACollectingSink() {
        this.jdbcTemplate = mock(JdbcTemplate.class);
        this.writer = newWriter(this.jdbcTemplate, StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
        this.emitted = new ArrayList<>();
        this.file = this.writer.open(record -> {
            this.emitted.add(record);
            return FileStatus.OK;
        });
    }

    /**
     * Builds a writer over a synthetic {@code carddemo.datasets} catalogue.
     *
     * @param template     the template to inject
     * @param recordLength the record length the {@code HTMLFILE} binding will declare
     * @param dsname       the dataset name the binding will declare
     * @return the writer
     */
    private static StatementHtmlWriter newWriter(final JdbcTemplate template, final int recordLength,
                                                 final String dsname) {
        return newWriter(template, recordLength, dsname, RecordImageForm.CHARACTER);
    }

    /**
     * A writer over the given template, geometry and record-image representation.
     *
     * <p>The representation is a parameter because it is a property of the deployment's driver and both
     * of its values have to be exercised: the sink's binding is asserted under each.
     *
     * @param template      the template the sink issues its insert through
     * @param recordLength  the configured record width
     * @param dsname        the configured dataset name
     * @param form          how a record image crosses JDBC
     * @return the writer
     */
    private static StatementHtmlWriter newWriter(final JdbcTemplate template, final int recordLength,
                                                 final String dsname, final RecordImageForm form) {
        return new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                bindingsFor(dsname, recordLength), form);
    }

    /**
     * A synthetic {@code carddemo.datasets} catalogue holding only the {@code HTMLFILE} binding, at
     * the correct 100-byte width.
     *
     * <p>Built in memory rather than loaded from {@code application-test.yml}, so no Spring context
     * is involved (gate G51, practice B2) and no mainframe dataset name enters this file (gate G46).
     *
     * @param dsname the dataset name the binding will declare
     * @return the catalogue
     */
    private static DatasetBindings bindingsFor(final String dsname) {
        return bindingsFor(dsname, StatementHtmlWriter.RECORD_LENGTH);
    }

    /**
     * A synthetic {@code carddemo.datasets} catalogue holding only the {@code HTMLFILE} binding.
     *
     * <p>The record length is a parameter because the constructor's width guard - the mechanical
     * enforcement of gate G20 - has to be driven with the wrong width as well as the right one.
     *
     * @param dsname       the dataset name the binding will declare
     * @param recordLength the record length the binding will declare
     * @return the catalogue
     */
    private static DatasetBindings bindingsFor(final String dsname, final int recordLength) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(StatementHtmlWriter.HTMLFILE_DD_NAME, new DatasetBinding(
                dsname, "sequential", false, "FB", StatementHtmlWriter.BLOCK_SIZE, recordLength,
                null, null, null, null, null));
        return bindings;
    }

    /**
     * The last collected record, decoded as ASCII.
     *
     * @return the record as text, all 100 characters of it
     */
    private String lastRecordText() {
        assertThat(this.emitted).isNotEmpty();
        return new String(this.emitted.get(this.emitted.size() - 1), StandardCharsets.US_ASCII);
    }

    /**
     * Every collected record, decoded as ASCII, in emission order.
     *
     * @return the records as text
     */
    private List<String> allRecordText() {
        return this.emitted.stream()
                .map(record -> new String(record, StandardCharsets.US_ASCII))
                .toList();
    }

    // =============================================================================================

    @Nested
    @DisplayName("The record is 100 bytes, not 80 - gate G20, risk R-G")
    class RecordWidthIsOneHundredNotEighty {

        @Test
        @DisplayName("RECORD_LENGTH is 100: CREASTMT.JCL:L94 LRECL=100 and CBSTM03A.CBL:L47 PIC X(100)")
        void recordLengthIsOneHundred() {
            assertThat(StatementHtmlWriter.RECORD_LENGTH).isEqualTo(100);
            assertThat(StatementHtmlWriterTest.this.writer.recordLength()).isEqualTo(100);
        }

        @Test
        @DisplayName("BLOCK_SIZE is 800 from the creating step, and is documentation only")
        void blockSizeIsEightHundred() {
            assertThat(StatementHtmlWriter.BLOCK_SIZE).isEqualTo(800);
            assertThat(StatementHtmlWriterTest.this.writer.blockSize()).isEqualTo(800);
        }

        @Test
        @DisplayName("Construction is refused when the binding declares the pre-delete step's 80")
        void constructionIsRefusedForTheEightyByteWidthOfTheIefbr14PredeleteStep() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> newWriter(template, WRONG_RECORD_LENGTH_FROM_PREDELETE_STEP,
                            TEST_DSNAME))
                    .withMessageContaining("record-length is 80")
                    .withMessageContaining("LRECL=80")
                    .withMessageContaining("LRECL=100")
                    .withMessageContaining("L69")
                    .withMessageContaining("L94")
                    .withMessageContaining("PIC X(100)");
        }

        @Test
        @DisplayName("Any other declared width is refused too, not only 80")
        void constructionIsRefusedForAnyOtherWidth() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> newWriter(template, 133, TEST_DSNAME))
                    .withMessageContaining("record-length is 133");
        }

        @Test
        @DisplayName("A missing HTMLFILE binding is refused by the catalogue, not defaulted")
        void aMissingBindingIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings empty = new DatasetBindings();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                            empty, RecordImageForm.CHARACTER))
                    .withMessageContaining(StatementHtmlWriter.HTMLFILE_DD_NAME);
        }

        @Test
        @DisplayName("The verified binding is exposed for inspection")
        void theBindingIsExposed() {
            DatasetBinding binding = StatementHtmlWriterTest.this.writer.datasetBinding();
            assertThat(binding.recordLength()).isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(binding.blockSize()).isEqualTo(StatementHtmlWriter.BLOCK_SIZE);
            assertThat(binding.dsname()).isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("The injected charset is the one supplied, never a platform default")
        void theCharsetIsTheInjectedOne() {
            Charset charset = StatementHtmlWriterTest.this.writer.datasetCharset();
            assertThat(charset).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("The dataset is addressed by the HTMLFILE binding key - CBSTM03A.CBL:L40, gate G46")
        void theDatasetIsAddressedByItsBindingKey() {
            // CBSTM03A.CBL:L40 declares SELECT HTML-FILE ASSIGN TO HTMLFILE, and CREASTMT.JCL names
            // the same DD at L67 and L92. The key is carried verbatim, in the upper case both the JCL
            // and application.yml use, so configuration can be diffed against JCL line by line.
            assertThat(StatementHtmlWriter.HTMLFILE_DD_NAME).isEqualTo("HTMLFILE");
            assertThat(StatementHtmlWriterTest.this.writer.datasetBinding().dsname())
                    .isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("No mainframe dataset name is known to this suite: it comes from configuration")
        void noMainframeDatasetNameIsKnownHere() {
            // Gate G46: the real name lives only in application.yml, as
            // carddemo.datasets.HTMLFILE.dsname. This suite supplies a synthetic one, and the writer
            // reports back exactly what it was configured with - it neither defaults nor rewrites it.
            assertThat(TEST_DSNAME).doesNotContain("CARDDEMO").doesNotStartWith("AWS.");
            assertThat(StatementHtmlWriterTest.this.writer.datasetBinding().dsname())
                    .doesNotContain("CARDDEMO");
        }

        @Test
        @DisplayName("The COBOL names of the record area and the four lines are carried verbatim")
        void theCobolLineNamesAreCarriedVerbatim() {
            assertThat(StatementHtmlWriter.RECORD_AREA_NAME).isEqualTo("FD-HTMLFILE-REC");
            assertThat(StatementHtmlWriter.FIXED_LINE_NAME).isEqualTo("HTML-FIXED-LN");
            assertThat(StatementHtmlWriter.ADDRESS_LINE_NAME).isEqualTo("HTML-ADDR-LN");
            assertThat(StatementHtmlWriter.BASIC_LINE_NAME).isEqualTo("HTML-BSIC-LN");
            assertThat(StatementHtmlWriter.TRANSACTION_LINE_NAME).isEqualTo("HTML-TRAN-LN");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The 34 fixed literals - CBSTM03A.CBL:L150-L211")
    class FixedLiteralCatalogue {

        @Test
        @DisplayName("Exactly 34 constants, in COBOL declaration order")
        void thereAreThirtyFourConstantsInDeclarationOrder() {
            assertThat(HtmlFixedLine.values()).hasSize(34);
            assertThat(HtmlFixedLine.values()[0]).isEqualTo(HtmlFixedLine.HTML_L01);
            assertThat(HtmlFixedLine.values()[8]).isEqualTo(HtmlFixedLine.HTML_LTRS);
            assertThat(HtmlFixedLine.values()[33]).isEqualTo(HtmlFixedLine.HTML_L80);
        }

        @ParameterizedTest(name = "{0} = [{1}]")
        @MethodSource(
                "com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#expectedFixedLiterals")
        @DisplayName("Each literal is byte-exact")
        void eachLiteralIsByteExact(final HtmlFixedLine line, final String expected) {
            assertThat(line.literal()).isEqualTo(expected);
            assertThat(line.literalLength()).isEqualTo(expected.length());
        }

        @Test
        @DisplayName("HTML-L08 has TWO spaces after <table, not one")
        void htmlL08HasTwoSpacesAfterTheTableTag() {
            assertThat(HtmlFixedLine.HTML_L08.literal()).startsWith("<table  align=");
            assertThat(HtmlFixedLine.HTML_L08.literal()).doesNotContain("<table align=");
        }

        @Test
        @DisplayName("The colspan cells have NO space after 'padding:0px 5px;'")
        void theColspanCellsHaveNoSpaceAfterThePadding() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L10, HtmlFixedLine.HTML_L15,
                    HtmlFixedLine.HTML_L22_35, HtmlFixedLine.HTML_L30_42)) {
                assertThat(line.literal()).contains("padding:0px 5px;background-color:");
            }
        }

        @Test
        @DisplayName("The width cells DO have a space after 'padding:0px 5px;'")
        void theWidthCellsHaveASpaceAfterThePadding() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L47, HtmlFixedLine.HTML_L50,
                    HtmlFixedLine.HTML_L53, HtmlFixedLine.HTML_L58, HtmlFixedLine.HTML_L61,
                    HtmlFixedLine.HTML_L64)) {
                assertThat(line.literal()).contains("padding:0px 5px; background-color:");
            }
        }

        @ParameterizedTest
        @EnumSource(HtmlFixedLine.class)
        @DisplayName("No literal exceeds the PIC X(100) field it is SET into")
        void noLiteralExceedsOneHundredCharacters(final HtmlFixedLine line) {
            assertThat(line.literalLength())
                    .isBetween(1, StatementHtmlWriter.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The compound COBOL names are preserved, hyphens and all")
        void theCompoundNamesArePreserved() {
            assertThat(HtmlFixedLine.HTML_L22_35.cobolName()).isEqualTo("HTML-L22-35");
            assertThat(HtmlFixedLine.HTML_L30_42.cobolName()).isEqualTo("HTML-L30-42");
            assertThat(HtmlFixedLine.HTML_LTRS.cobolName()).isEqualTo("HTML-LTRS");
        }

        @ParameterizedTest
        @EnumSource(HtmlFixedLine.class)
        @DisplayName("Every constant resolves by its own COBOL name")
        void everyConstantResolvesByCobolName(final HtmlFixedLine line) {
            assertThat(HtmlFixedLine.ofCobolName(line.cobolName())).isSameAs(line);
            assertThat(HtmlFixedLine.isDeclared(line.cobolName())).isTrue();
        }

        @Test
        @DisplayName("An unknown COBOL name is refused with the full list of declared names")
        void anUnknownCobolNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> HtmlFixedLine.ofCobolName("HTML-L99"))
                    .withMessageContaining("HTML-L99")
                    .withMessageContaining("HTML-L01");
        }

        @Test
        @DisplayName("Name matching is case-sensitive and exact, as COBOL's own resolution is")
        void nameMatchingIsCaseSensitive() {
            assertThat(HtmlFixedLine.isDeclared("html-l01")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML_L01")).isFalse();
            assertThat(HtmlFixedLine.isDeclared(null)).isFalse();
        }

        @Test
        @DisplayName("A null COBOL name is rejected outright")
        void aNullCobolNameIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> HtmlFixedLine.ofCobolName(null));
        }

        @Test
        @DisplayName("HTML-L22-35 is ONE value written at L551 and again at L610, not two values")
        void htmlL22To35IsOneValueEmittedAtTwoPoints() {
            // The compound COBOL name records the reuse: output line 22, in 5100-WRITE-HTML-HEADER,
            // and output line 35, in 5200-WRITE-HTML-NMADBS, are the same literal. There is therefore
            // exactly one constant, and emitting it twice must produce two identical records.
            assertThat(HtmlFixedLine.isDeclared("HTML-L22")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L35")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L22-35")).isTrue();

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L22_35);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L22_35);

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records).hasSize(2);
            assertThat(records.get(1)).isEqualTo(records.get(0));
            assertThat(records.get(0)).isEqualTo(padded(HtmlFixedLine.HTML_L22_35.literal()));
        }

        @Test
        @DisplayName("HTML-L30-42 is ONE value written at L600 and again at L640, not two values")
        void htmlL30To42IsOneValueEmittedAtTwoPoints() {
            assertThat(HtmlFixedLine.isDeclared("HTML-L30")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L42")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L30-42")).isTrue();

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L30_42);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L30_42);

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records).hasSize(2);
            assertThat(records.get(1)).isEqualTo(records.get(0));
            assertThat(records.get(0)).isEqualTo(padded(HtmlFixedLine.HTML_L30_42.literal()));
        }

        @Test
        @DisplayName("HTML-L10 is reused too, at L441 in the footer and L526 in the header")
        void htmlL10IsAlsoReused() {
            // Not flagged by the compound-name convention, because its name carries a single number,
            // but the program does SET HTML-L10 TO TRUE twice: at L526 in 5100-WRITE-HTML-HEADER and
            // at L441 in the eight-record footer. One constant, two emission points, same bytes.
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L10);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L10);

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(1)).isEqualTo(records.get(0));
            assertThat(records.get(0))
                    .isEqualTo(padded("<td colspan=\"3\" style=\"padding:0px 5px;"
                            + "background-color:#1d1d96b3;\">"));
        }

        @Test
        @DisplayName("HTML-LTDS survives although CBSTM03A never selects it - practice B5")
        void htmlLtdsSurvivesAlthoughNeverSelected() {
            // The declaration at CBSTM03A.CBL:L161 has no SET HTML-LTDS TO TRUE anywhere in the
            // program's 925 lines - it is the only one of the thirty-four with no write site, and its
            // paired HTML-LTDE has thirteen. Dead-but-declared code is preserved, not tidied away
            // (practice B5), so the constant stays declared, resolvable and correct. What is NOT done
            // is giving it a write site: this test emits it to prove the value is right, which is a
            // test emitting a record, not the translated program acquiring one.
            assertThat(HtmlFixedLine.isDeclared("HTML-LTDS")).isTrue();
            assertThat(HtmlFixedLine.ofCobolName("HTML-LTDS")).isSameAs(HtmlFixedLine.HTML_LTDS);
            assertThat(HtmlFixedLine.HTML_LTDS.literal()).isEqualTo("<td>");
            assertThat(HtmlFixedLine.HTML_LTDE.literal()).isEqualTo("</td>");

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_LTDS);

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded("<td>"));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Every emitted record is exactly 100 bytes - gates G19 and G20")
    class EveryRecordIsOneHundredBytes {

        @ParameterizedTest
        @EnumSource(HtmlFixedLine.class)
        @DisplayName("SET HTML-Lxx TO TRUE then WRITE FROM HTML-FIXED-LN")
        void everyFixedLineIsOneHundredBytes(final HtmlFixedLine line) {
            FileStatus.Outcome outcome =
                    StatementHtmlWriterTest.this.writer.writeFixedLine(
                            StatementHtmlWriterTest.this.file, line);

            assertThat(outcome).isEqualTo(FileStatus.Outcome.OK);
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);
            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded(line.literal()));
        }

        @Test
        @DisplayName("HTML-L11 is 59 declared bytes padded to 100")
        void theAccountHeadingIsOneHundredBytes() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "00000000011");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<h3>Statement for Account Number: 00000000011         </h3>"));
        }

        @Test
        @DisplayName("The name line is 100 bytes")
        void theNameLineIsOneHundredBytes() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "MARGARET GOLD");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(AddressField.class)
        @DisplayName("Each address line is 100 bytes")
        void eachAddressLineIsOneHundredBytes(final AddressField field) {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, field, "410 TERRY AVE N");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(BasicDetail.class)
        @DisplayName("Each basic-detail line is 100 bytes")
        void eachBasicDetailLineIsOneHundredBytes(final BasicDetail detail) {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, detail, "123456789");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(TransactionField.class)
        @DisplayName("Each transaction line is 100 bytes")
        void eachTransactionLineIsOneHundredBytes(final TransactionField field) {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, field, "0000000000000001");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @Test
        @DisplayName("All ten STRING-built shapes plus a literal and the heading: twelve records, all 100")
        void allTwelveShapesAreOneHundredBytes() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeFixedLine(handle, HtmlFixedLine.HTML_L01);
            local.writeAccountHeading(handle, "00000000011");
            local.writeNameLine(handle, "A  B");
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "L1");
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_2, "L2");
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_3, "L3");
            local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "1");
            local.writeBasicDetail(handle, BasicDetail.CURRENT_BALANCE, "000001234.56-");
            local.writeBasicDetail(handle, BasicDetail.FICO_SCORE, "700");
            local.writeTransactionField(handle, TransactionField.TRAN_ID, "T1");
            local.writeTransactionField(handle, TransactionField.TRAN_DETAILS, "D1");
            local.writeTransactionField(handle, TransactionField.TRAN_AMOUNT, "        1.00 ");

            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(12);
            assertThat(StatementHtmlWriterTest.this.emitted)
                    .allSatisfy(record ->
                            assertThat(record).hasSize(StatementHtmlWriter.RECORD_LENGTH));
            assertThat(handle.recordsWritten()).isEqualTo(12L);
        }

        @Test
        @DisplayName("One call emits exactly ONE record: nothing is ever wrapped onto a second")
        void oneCallEmitsExactlyOneRecord() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            // The widest content each shape can carry, so if anything were going to spill onto a
            // second record it would be here. Every call must still produce exactly one.
            local.writeFixedLine(handle, HtmlFixedLine.HTML_L08);
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);

            local.writeAccountHeading(handle, "9".repeat(20));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(2);

            local.writeNameLine(handle, "N".repeat(75));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(3);

            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_3, "C".repeat(80));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(4);

            local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "A".repeat(20));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(5);

            local.writeTransactionField(handle, TransactionField.TRAN_DETAILS, "D".repeat(49));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(6);

            assertThat(StatementHtmlWriterTest.this.emitted)
                    .allSatisfy(record ->
                            assertThat(record).hasSize(StatementHtmlWriter.RECORD_LENGTH));
            assertThat(handle.recordsWritten()).isEqualTo(6L);
        }

        @Test
        @DisplayName("No shape can overflow 100: the widest is the 80-byte third address line at 89")
        void noShapeCanOverflowTheRecord() {
            // The reason nothing is ever wrapped is arithmetic, not luck, so the arithmetic is stated
            // rather than left implicit. Each shape's widest possible composition, derived from the
            // declared widths in CBSTM03A.CBL's STATEMENT-LINES group and the literal operands of its
            // STRING statements:
            int nameLine = StatementHtmlWriter.STYLED_PARAGRAPH_OPEN_TAG.length()   // 26
                    + StatementHtmlWriter.L23_NAME_LENGTH                          // 50
                    + StatementHtmlWriter.TWO_SPACE_SEPARATOR.length()             //  2
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();            //  4  = 82
            int widestAddress = StatementHtmlWriter.PARAGRAPH_OPEN_TAG.length()     //  3
                    + AddressField.ADDRESS_LINE_3.declaredLength()                 // 80
                    + StatementHtmlWriter.TWO_SPACE_SEPARATOR.length()             //  2
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();            //  4  = 89
            int widestBasicDetail = BasicDetail.ACCOUNT_ID.label().length()         // 24
                    + BasicDetail.ACCOUNT_ID.declaredLength()                      // 20
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();            //  4  = 48
            int widestTransaction = StatementHtmlWriter.PARAGRAPH_OPEN_TAG.length() //  3
                    + TransactionField.TRAN_DETAILS.declaredLength()               // 49
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();            //  4  = 56

            assertThat(nameLine).isEqualTo(82);
            assertThat(widestAddress).isEqualTo(89);
            assertThat(widestBasicDetail).isEqualTo(48);
            assertThat(widestTransaction).isEqualTo(56);
            assertThat(StatementHtmlWriter.HTML_L11_LENGTH).isEqualTo(59);

            // The widest of all five, and the widest fixed literal, both fit with room to spare - so
            // right-truncation at 100 is a stated invariant of this writer rather than a live path,
            // and there is no input a caller can supply that reaches it.
            int widestShape = Math.max(Math.max(nameLine, widestAddress),
                    Math.max(widestBasicDetail, widestTransaction));
            assertThat(widestShape).isEqualTo(89)
                    .isLessThan(StatementHtmlWriter.RECORD_LENGTH);
            for (HtmlFixedLine line : HtmlFixedLine.values()) {
                assertThat(line.literalLength())
                        .as("literal %s must fit the PIC X(100) it is SET into", line.cobolName())
                        .isLessThanOrEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("COBOL STRING ... DELIMITED BY semantics")
    class StringDelimitedBySemantics {

        @Test
        @DisplayName("DELIMITED BY '  ' stops at the first two consecutive spaces")
        void twoSpaceDelimiterStopsAtTheFirstRun() {
            assertThat(StatementHtmlWriter.delimitedBy("AL  SMITH",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("AL");
        }

        @Test
        @DisplayName("DELIMITED BY '  ' transfers the whole item when there is no two-space run")
        void twoSpaceDelimiterTransfersEverythingWhenNotFound() {
            assertThat(StatementHtmlWriter.delimitedBy("ALSMITH",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("ALSMITH");
        }

        @Test
        @DisplayName("A SINGLE space is not the delimiter - this is not a trim")
        void aSingleSpaceIsNotTheDelimiter() {
            assertThat(StatementHtmlWriter.delimitedBy("AL SMITH",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("AL SMITH");
        }

        @Test
        @DisplayName("An all-spaces item transfers nothing: the run starts at position 1")
        void anAllSpacesItemTransfersNothing() {
            assertThat(StatementHtmlWriter.delimitedBy("    ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEmpty();
        }

        @Test
        @DisplayName("A one-character item shorter than the delimiter transfers whole")
        void anItemShorterThanTheDelimiterTransfersWhole() {
            assertThat(StatementHtmlWriter.delimitedBy(" ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo(" ");
            assertThat(StatementHtmlWriter.delimitedBy("",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEmpty();
        }

        @Test
        @DisplayName("DELIMITED BY '*' on an asterisk-free item keeps the trailing spaces")
        void asteriskDelimiterKeepsTrailingSpaces() {
            assertThat(StatementHtmlWriter.delimitedBy("12345      ",
                    StatementHtmlWriter.ASTERISK_DELIMITER)).isEqualTo("12345      ");
        }

        @Test
        @DisplayName("DELIMITED BY '*' does stop at an asterisk, when there is one")
        void asteriskDelimiterStopsAtAnAsterisk() {
            assertThat(StatementHtmlWriter.delimitedBy("AB*CD",
                    StatementHtmlWriter.ASTERISK_DELIMITER)).isEqualTo("AB");
        }

        @Test
        @DisplayName("A partial delimiter match does not end the transfer")
        void aPartialMatchDoesNotEndTheTransfer() {
            // 'X Y Z ' has single spaces only, so the two-space scan matches at no position and must
            // fall through the inner-loop mismatch break at every one of them.
            assertThat(StatementHtmlWriter.delimitedBy("X Y Z ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("X Y Z ");
        }

        @Test
        @DisplayName("A run at the very last scannable position is still found, not missed")
        void aRunAtTheLastPositionIsStillFound() {
            // The scan's final candidate start is length - delimiter length. 'ABC  ' puts the
            // two-space run exactly there, so this is the boundary case that separates a correct
            // left-to-right scan from one that stops an index early and silently transfers the
            // delimiter itself.
            assertThat(StatementHtmlWriter.delimitedBy("ABC  ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("ABC");
            // One character shorter, so the run is incomplete and nothing is found.
            assertThat(StatementHtmlWriter.delimitedBy("ABC ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("ABC ");
            // A run at the very first position transfers nothing at all.
            assertThat(StatementHtmlWriter.delimitedBy("  ABC",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEmpty();
        }

        @Test
        @DisplayName("A delimiter longer than one character is matched as a SEQUENCE, not per byte")
        void aMultiCharacterDelimiterIsMatchedAsASequence() {
            // COBOL looks for the delimiter as a character sequence at every position. A per-character
            // search would stop at the first space of 'A B  C' and yield 'A', which is a different
            // program - so the distinction is asserted rather than assumed.
            assertThat(StatementHtmlWriter.delimitedBy("A B  C",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("A B");
            assertThat(StatementHtmlWriter.delimitedBy("A B  C",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isNotEqualTo("A");
        }

        @Test
        @DisplayName("DELIMITED BY SIZE is the identity, and is named so the call site reads as COBOL")
        void delimitedBySizeIsTheIdentity() {
            assertThat(StatementHtmlWriter.delimitedBySize("  ")).isEqualTo("  ");
            assertThat(StatementHtmlWriter.delimitedBySize("")).isEmpty();
        }

        @Test
        @DisplayName("An empty delimiter is rejected: no DELIMITED BY phrase can express it")
        void anEmptyDelimiterIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBy("ABC", ""))
                    .withMessageContaining("DELIMITED BY");
        }

        @Test
        @DisplayName("Null operands are rejected")
        void nullOperandsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBy(null, "*"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBy("ABC", null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBySize(null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The name line - CBSTM03A.CBL:L560-L568, a bare WRITE with no FROM")
    class NameLine {

        @Test
        @DisplayName("A name with two consecutive spaces is truncated at them")
        void aNameIsTruncatedAtTheFirstTwoSpaceRun() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "MARGARET  GOLD");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">MARGARET  </p>"));
        }

        @Test
        @DisplayName("A name with single spaces only transfers whole, then is padded to 50 and stopped there")
        void aNameWithSingleSpacesTransfersWhole() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "MARGARET GOLD");

            // MOVE ST-NAME TO L23-NAME pads to 50, so the two-space run begins right after the text.
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">MARGARET GOLD  </p>"));
        }

        @Test
        @DisplayName("An all-spaces name contributes nothing at all")
        void anAllSpacesNameContributesNothing() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">  </p>"));
        }

        @Test
        @DisplayName("A name of exactly 50 characters with no two-space run transfers all 50")
        void aFiftyCharacterNameTransfersWhole() {
            String fifty = "A".repeat(StatementHtmlWriter.L23_NAME_LENGTH);
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    fifty);

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">" + fifty + "  </p>"));
        }

        @Test
        @DisplayName("ST-NAME PIC X(75) is truncated on the RIGHT to L23-NAME PIC X(50) first")
        void stNameIsTruncatedToFiftyCharactersFirst() {
            String seventyFive = "B".repeat(75);
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    seventyFive);

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<p style=\"font-size:16px\">"
                            + "B".repeat(StatementHtmlWriter.L23_NAME_LENGTH) + "  </p>"));
        }

        @Test
        @DisplayName("The record area holds the composed line, because the WRITE had no FROM clause")
        void theRecordAreaHoldsTheComposedLine() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "X");

            assertThat(StatementHtmlWriterTest.this.file.recordArea().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }

        @Test
        @DisplayName("MOVE SPACES clears the receiver, so a long line never leaves a tail behind")
        void moveSpacesClearsTheReceiverBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeNameLine(handle, "Z".repeat(StatementHtmlWriter.L23_NAME_LENGTH));
            local.writeNameLine(handle, "Q");

            assertThat(StatementHtmlWriterTest.this.allRecordText().get(1))
                    .isEqualTo(padded("<p style=\"font-size:16px\">Q  </p>"));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The address lines - CBSTM03A.CBL:L569-L592")
    class AddressLines {

        @Test
        @DisplayName("ST-ADD1 and ST-ADD2 send from PIC X(50); ST-ADD3 from PIC X(80)")
        void theDeclaredWidthsAreFiftyFiftyAndEighty() {
            assertThat(AddressField.ADDRESS_LINE_1.declaredLength()).isEqualTo(50);
            assertThat(AddressField.ADDRESS_LINE_2.declaredLength()).isEqualTo(50);
            assertThat(AddressField.ADDRESS_LINE_3.declaredLength()).isEqualTo(80);
            assertThat(AddressField.ADDRESS_LINE_1.cobolName()).isEqualTo("ST-ADD1");
            assertThat(AddressField.ADDRESS_LINE_2.cobolName()).isEqualTo("ST-ADD2");
            assertThat(AddressField.ADDRESS_LINE_3.cobolName()).isEqualTo("ST-ADD3");
        }

        @Test
        @DisplayName("An address is wrapped and stopped at its first two-space run")
        void anAddressIsWrappedAndStopped() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1,
                    "410 TERRY AVE N");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>410 TERRY AVE N  </p>"));
        }

        @Test
        @DisplayName("An address that already contains a two-space run is cut there")
        void anAddressWithAnInternalRunIsCutThere() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_2,
                    "SUITE 5  BUILDING 2");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>SUITE 5  </p>"));
        }

        @Test
        @DisplayName("A blank address still yields a well-formed empty paragraph")
        void aBlankAddressYieldsAnEmptyParagraph() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_3, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>  </p>"));
        }

        @Test
        @DisplayName("The 80-byte third line accepts its full width without overflowing 100")
        void theThirdLineAcceptsItsFullWidth() {
            String eighty = "C".repeat(80);
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_3, eighty);

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>" + eighty + "  </p>"));
            assertThat(StatementHtmlWriterTest.this.lastRecordText()).hasSize(100);
        }

        @Test
        @DisplayName("A value longer than the declared width is truncated on the right first")
        void anOverWideValueIsTruncatedOnTheRight() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1,
                    "D".repeat(60));

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>" + "D".repeat(50) + "  </p>"));
        }

        @Test
        @DisplayName("The record area holds the moved line, because the WRITE had a FROM clause")
        void theRecordAreaHoldsTheMovedLine() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1, "X");

            assertThat(StatementHtmlWriterTest.this.file.addressLine().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
            assertThat(StatementHtmlWriterTest.this.file.recordArea().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The basic-detail lines - CBSTM03A.CBL:L613-L633")
    class BasicDetailLines {

        @Test
        @DisplayName("Each label is exactly 24 characters, spaces counted from the source")
        void eachLabelIsTwentyFourCharacters() {
            assertThat(BasicDetail.ACCOUNT_ID.label()).isEqualTo("<p>Account ID         : ");
            assertThat(BasicDetail.CURRENT_BALANCE.label()).isEqualTo("<p>Current Balance    : ");
            assertThat(BasicDetail.FICO_SCORE.label()).isEqualTo("<p>FICO Score         : ");
            for (BasicDetail detail : BasicDetail.values()) {
                assertThat(detail.label()).hasSize(24);
            }
        }

        @Test
        @DisplayName("The value's declared width comes from the label identity")
        void theDeclaredWidthsAreTwentyThirteenAndTwenty() {
            assertThat(BasicDetail.ACCOUNT_ID.declaredLength()).isEqualTo(20);
            assertThat(BasicDetail.CURRENT_BALANCE.declaredLength()).isEqualTo(13);
            assertThat(BasicDetail.FICO_SCORE.declaredLength()).isEqualTo(20);
            assertThat(BasicDetail.ACCOUNT_ID.cobolName()).isEqualTo("ST-ACCT-ID");
            assertThat(BasicDetail.CURRENT_BALANCE.cobolName()).isEqualTo("ST-CURR-BAL");
            assertThat(BasicDetail.FICO_SCORE.cobolName()).isEqualTo("ST-FICO-SCORE");
        }

        @Test
        @DisplayName("DELIMITED BY '*' keeps the value's trailing spaces - the whole field transfers")
        void theValuesTrailingSpacesSurvive() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.ACCOUNT_ID, "00000000011");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>Account ID         : 00000000011         </p>"));
        }

        @Test
        @DisplayName("The already-edited balance image is embedded verbatim, mask and sign included")
        void theEditedBalanceIsEmbeddedVerbatim() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.CURRENT_BALANCE,
                    "000001234.56-");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>Current Balance    : 000001234.56-</p>"));
        }

        @Test
        @DisplayName("A blank value still transfers its 20 spaces, because nothing is trimmed")
        void aBlankValueTransfersItsPadding() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.FICO_SCORE, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>FICO Score         : " + " ".repeat(20) + "</p>"));
        }

        @Test
        @DisplayName("The record area holds the moved HTML-BSIC-LN")
        void theRecordAreaHoldsTheMovedBasicLine() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.ACCOUNT_ID, "1");

            assertThat(StatementHtmlWriterTest.this.file.basicLine().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The transaction lines - CBSTM03A.CBL:L686-L716")
    class TransactionLines {

        @Test
        @DisplayName("The declared widths are 16, 49 and 13")
        void theDeclaredWidthsAreSixteenFortyNineAndThirteen() {
            assertThat(TransactionField.TRAN_ID.declaredLength()).isEqualTo(16);
            assertThat(TransactionField.TRAN_DETAILS.declaredLength()).isEqualTo(49);
            assertThat(TransactionField.TRAN_AMOUNT.declaredLength()).isEqualTo(13);
            assertThat(TransactionField.TRAN_ID.cobolName()).isEqualTo("ST-TRANID");
            assertThat(TransactionField.TRAN_DETAILS.cobolName()).isEqualTo("ST-TRANDT");
            assertThat(TransactionField.TRAN_AMOUNT.cobolName()).isEqualTo("ST-TRANAMT");
        }

        @Test
        @DisplayName("A 16-character transaction identifier fills its field exactly")
        void theTransactionIdentifierFillsItsField() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_ID,
                    "2022071800000001");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>2022071800000001</p>"));
        }

        @Test
        @DisplayName("A description is right-padded to 49 and every space transfers")
        void theDescriptionIsPaddedToFortyNine() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_DETAILS, "GROCERIES");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>GROCERIES" + " ".repeat(40) + "</p>"));
        }

        @Test
        @DisplayName("A description longer than 49 characters is truncated on the right")
        void anOverLongDescriptionIsTruncatedOnTheRight() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_DETAILS,
                    "E".repeat(100));

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>" + "E".repeat(49) + "</p>"));
        }

        @Test
        @DisplayName("The already-edited amount image is embedded verbatim, leading spaces included")
        void theEditedAmountIsEmbeddedVerbatim() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_AMOUNT,
                    "     1234.56 ");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>     1234.56 </p>"));
        }

        @Test
        @DisplayName("The record area holds the moved HTML-TRAN-LN")
        void theRecordAreaHoldsTheMovedTransactionLine() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_AMOUNT, "1");

            assertThat(StatementHtmlWriterTest.this.file.transactionLine().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("HTML-L11 - the account heading, CBSTM03A.CBL:L529-L530")
    class AccountHeading {

        @Test
        @DisplayName("The group is 34 + 20 + 5 = 59 declared bytes")
        void theGroupIsFiftyNineBytes() {
            assertThat(StatementHtmlWriter.HTML_L11_LENGTH).isEqualTo(59);
            assertThat(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX).hasSize(34);
            assertThat(StatementHtmlWriter.ACCOUNT_HEADING_SUFFIX).hasSize(5);
            assertThat(StatementHtmlWriter.L11_ACCT_LENGTH).isEqualTo(20);
            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.recordLength()).isEqualTo(59);
        }

        @Test
        @DisplayName("The 34-character FILLER ends with a space, inside the literal")
        void theFillerLiteralEndsWithASpace() {
            assertThat(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX)
                    .isEqualTo("<h3>Statement for Account Number: ")
                    .endsWith(": ");
        }

        @Test
        @DisplayName("PIC 9(11) into PIC X(20) is left-justified with leading zeros kept")
        void theAccountIdentifierIsLeftJustifiedWithLeadingZeros() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "00000000001");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<h3>Statement for Account Number: 00000000001         </h3>"));
        }

        @Test
        @DisplayName("A blank identifier still leaves the two FILLERs intact")
        void aBlankIdentifierLeavesTheFillersIntact() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<h3>Statement for Account Number: " + " ".repeat(20) + "</h3>"));
        }

        @Test
        @DisplayName("The FILLERs are re-initialised on every call, so no previous value survives")
        void theFillersAreReinitialisedEveryCall() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeAccountHeading(handle, "99999999999");
            local.writeAccountHeading(handle, "1");

            assertThat(StatementHtmlWriterTest.this.allRecordText().get(1)).isEqualTo(padded(
                    "<h3>Statement for Account Number: 1                   </h3>"));
        }

        @Test
        @DisplayName("An over-wide identifier is truncated on the right to 20 characters")
        void anOverWideIdentifierIsTruncatedOnTheRight() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "9".repeat(30));

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<h3>Statement for Account Number: " + "9".repeat(20) + "</h3>"));
        }

        @Test
        @DisplayName("The three spans sit at absolute offsets 0..33, 34..53 and 54..58 - L213-L216")
        void theThreeSpansSitAtTheirDeclaredOffsets() {
            // Asserted as offsets rather than only through the composed text, because an offset is
            // what a copybook actually declares and a dropped FILLER shifts every byte after it while
            // still producing text that looks plausible (practice B11, gate G21).
            List<FieldSpan> spans = StatementHtmlWriter.HTML_L11_LAYOUT.spans();
            assertThat(spans).hasSize(3);

            FieldSpan leadingFiller = spans.get(0);
            assertThat(leadingFiller.name()).isEqualTo("FILLER");
            assertThat(leadingFiller.offset()).isZero();
            assertThat(leadingFiller.length()).isEqualTo(34);
            assertThat(leadingFiller.endOffsetExclusive()).isEqualTo(34);
            assertThat(leadingFiller.initialValue())
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX);

            FieldSpan account = spans.get(1);
            assertThat(account.name()).isEqualTo(StatementHtmlWriter.L11_ACCT_FIELD_NAME);
            assertThat(account.offset()).isEqualTo(34);
            assertThat(account.length()).isEqualTo(StatementHtmlWriter.L11_ACCT_LENGTH);
            assertThat(account.endOffsetExclusive()).isEqualTo(54);

            FieldSpan trailingFiller = spans.get(2);
            assertThat(trailingFiller.name()).isEqualTo("FILLER");
            assertThat(trailingFiller.offset()).isEqualTo(54);
            assertThat(trailingFiller.length()).isEqualTo(5);
            assertThat(trailingFiller.endOffsetExclusive())
                    .isEqualTo(StatementHtmlWriter.HTML_L11_LENGTH);
            assertThat(trailingFiller.initialValue())
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_SUFFIX);

            // The spans are contiguous from zero and total exactly 59: no gap, no overlap, nothing
            // dropped. Both FILLERs are storage, and there is no REDEFINES anywhere in this group.
            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.storageSpans()).hasSize(3);
            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.redefinitions()).isEmpty();
            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.span(
                    StatementHtmlWriter.L11_ACCT_FIELD_NAME).offset()).isEqualTo(34);
        }

        @Test
        @DisplayName("The emitted record carries those spans at those offsets, then 41 pad bytes")
        void theEmittedRecordCarriesTheSpansAtTheirOffsets() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "00000000011");

            String record = StatementHtmlWriterTest.this.lastRecordText();
            assertThat(record).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(record.substring(0, 34))
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX);
            assertThat(record.substring(34, 54)).isEqualTo("00000000011         ");
            assertThat(record.substring(54, 59))
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_SUFFIX);
            // WRITE ... FROM HTML-L11 at L530 moves a 59-byte group into a 100-byte record area, so
            // bytes 59..99 are the receiver's own padding - 41 of them.
            assertThat(record.substring(59))
                    .isEqualTo(String.valueOf(SPACE).repeat(41))
                    .hasSize(41);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("HTML-L23 - declared but never written, preserved rather than deleted")
    class DeclaredButUnwrittenNameGroup {

        @Test
        @DisplayName("The group is 26 + 50 = 76 declared bytes")
        void theGroupIsSeventySixBytes() {
            assertThat(StatementHtmlWriter.HTML_L23_LENGTH).isEqualTo(76);
            assertThat(StatementHtmlWriter.STYLED_PARAGRAPH_OPEN_TAG).hasSize(26);
            assertThat(StatementHtmlWriter.L23_NAME_LENGTH).isEqualTo(50);
            assertThat(StatementHtmlWriter.HTML_L23_LAYOUT.recordLength()).isEqualTo(76);
        }

        @Test
        @DisplayName("It materialises as its declared 76 bytes, L23-NAME padded to 50")
        void itMaterialisesAsSeventySixBytes() {
            byte[] group = StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("MARGARET GOLD");

            assertThat(group).hasSize(76);
            assertThat(new String(group, StandardCharsets.US_ASCII)).isEqualTo(
                    "<p style=\"font-size:16px\">MARGARET GOLD" + " ".repeat(37));
        }

        @Test
        @DisplayName("It is genuinely different from the STRING-built line: no </p> and full padding")
        void itDiffersFromTheStringBuiltLine() {
            String group = new String(StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("A"), StandardCharsets.US_ASCII);
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "A");

            assertThat(group).doesNotContain(StatementHtmlWriter.PARAGRAPH_CLOSE_TAG);
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .contains(StatementHtmlWriter.PARAGRAPH_CLOSE_TAG);
            assertThat(group).isNotEqualTo(StatementHtmlWriterTest.this.lastRecordText());
        }

        @Test
        @DisplayName("A name over 50 characters is truncated on the right, exactly as MOVE does")
        void anOverWideNameIsTruncated() {
            byte[] group = StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("F".repeat(75));

            assertThat(new String(group, StandardCharsets.US_ASCII))
                    .isEqualTo("<p style=\"font-size:16px\">" + "F".repeat(50));
        }

        @Test
        @DisplayName("A null name is rejected")
        void aNullNameIsRejected() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> StatementHtmlWriterTest.this.writer.composeNameParagraphGroup(null));
        }

        @Test
        @DisplayName("The two spans sit at absolute offsets 0..25 and 26..75 - L218-L220")
        void theTwoSpansSitAtTheirDeclaredOffsets() {
            List<FieldSpan> spans = StatementHtmlWriter.HTML_L23_LAYOUT.spans();
            assertThat(spans).hasSize(2);

            FieldSpan filler = spans.get(0);
            assertThat(filler.name()).isEqualTo("FILLER");
            assertThat(filler.offset()).isZero();
            assertThat(filler.length()).isEqualTo(26);
            assertThat(filler.endOffsetExclusive()).isEqualTo(26);
            assertThat(filler.initialValue())
                    .isEqualTo(StatementHtmlWriter.STYLED_PARAGRAPH_OPEN_TAG);

            FieldSpan name = spans.get(1);
            assertThat(name.name()).isEqualTo(StatementHtmlWriter.L23_NAME_FIELD_NAME);
            assertThat(name.offset()).isEqualTo(26);
            assertThat(name.length()).isEqualTo(StatementHtmlWriter.L23_NAME_LENGTH);
            assertThat(name.endOffsetExclusive()).isEqualTo(StatementHtmlWriter.HTML_L23_LENGTH);

            // Unlike HTML-L11, this group has no trailing FILLER: it ends with L23-NAME, which is why
            // its 76 bytes contain no '</p>' at all.
            assertThat(StatementHtmlWriter.HTML_L23_LENGTH).isEqualTo(26 + 50);
        }

        @Test
        @DisplayName("Materialising the group emits NOTHING: CBSTM03A never writes it - practice B5")
        void materialisingTheGroupEmitsNoRecord() {
            // This is the assertion that actually enforces B5 for this group. CBSTM03A declares
            // HTML-L23 at L217-L220 and then never touches it: the token appears on exactly one line
            // of the whole program, its own declaration. There is no SET, no MOVE and no WRITE of it.
            // So materialising it must be an entirely passive operation - it produces 76 bytes for a
            // caller to inspect and puts NO record on the file.
            byte[] group = StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("MARGARET GOLD");

            assertThat(group).hasSize(StatementHtmlWriter.HTML_L23_LENGTH);
            assertThat(StatementHtmlWriterTest.this.emitted).isEmpty();
            assertThat(StatementHtmlWriterTest.this.file.recordsWritten()).isZero();
            // Composing it repeatedly still emits nothing, and the handle stays open and untouched.
            StatementHtmlWriterTest.this.writer.composeNameParagraphGroup("A");
            StatementHtmlWriterTest.this.writer.composeNameParagraphGroup("");
            assertThat(StatementHtmlWriterTest.this.emitted).isEmpty();
            assertThat(StatementHtmlWriterTest.this.file.isOpen()).isTrue();
        }

        @Test
        @DisplayName("No public operation emits the group, and none can: every record is 100 bytes")
        void noPublicOperationEmitsTheGroup() {
            // Two independent guarantees, because B5 is about what the translated program does NOT do
            // and an absence is easy to lose by accident.
            //
            // First: the writer exposes no group-emitting operation. Every emit method is named
            // write* and takes the handle as its first parameter; composeNameParagraphGroup is the
            // only public operation that mentions the group, it takes no handle, and it returns the
            // bytes instead of writing them. So there is no writeNameGroup, and adding one would show
            // up here.
            List<String> emitOperations = new ArrayList<>();
            for (Method method : StatementHtmlWriter.class.getDeclaredMethods()) {
                if (method.getName().startsWith("write")) {
                    emitOperations.add(method.getName());
                }
            }
            assertThat(emitOperations)
                    .isNotEmpty()
                    .containsExactlyInAnyOrder("writeFixedLine", "writeAccountHeading",
                            "writeNameLine", "writeAddressLine", "writeBasicDetail",
                            "writeTransactionField", "writeFrom");
            // Both spellings tested literally rather than through toLowerCase(), which consults the
            // default locale and would make this assertion locale-dependent (practice B7).
            assertThat(emitOperations)
                    .noneMatch(name -> name.contains("Group") || name.contains("group"));

            // Second: even if one existed, a 76-byte record is not representable. The record area is
            // allocated at exactly RECORD_LENGTH and every emitted image is that wide, so the group's
            // declared width can never be a record width.
            StatementHtmlWriterTest.this.writer.writeNameLine(
                    StatementHtmlWriterTest.this.file, "MARGARET GOLD");
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);
            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(StatementHtmlWriter.HTML_L23_LENGTH)
                    .isNotEqualTo(StatementHtmlWriter.RECORD_LENGTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("MOVE SPACES clears each scratch line - CBSTM03A.CBL:L561, L569, L613, L686")
    class ScratchBufferClearing {

        /**
         * The three scratch lines of {@code app/cbl/CBSTM03A.CBL:L221-L223} are each
         * {@code PIC X(100)} and each is reused for several records. COBOL's {@code STRING} overlays
         * from the receiver's leftmost position and leaves everything beyond the last transferred
         * character exactly as it found it - which is precisely why every one of the ten
         * {@code STRING} sites in the program is preceded by an explicit {@code MOVE SPACES}.
         *
         * <p>Each test below writes a LONG composition and then a SHORTER one through the same
         * buffer. Without the clear, the tail of the first record would still be sitting in the
         * buffer and would leak into the second - the classic stale-tail defect, and one that only
         * ever shows up from the second record of a run onwards, which is why a freshly allocated
         * buffer is not evidence of anything.
         */
        @Test
        @DisplayName("HTML-ADDR-LN: a 59-character line then a 10-character one leaves no tail")
        void theAddressLineIsClearedBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            // 3 + 50 + 2 + 4 = 59 characters of content.
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "L".repeat(50));
            // 3 + 1 + 2 + 4 = 10 characters of content.
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "X");

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(0)).isEqualTo(padded("<p>" + "L".repeat(50) + "  </p>"));
            assertThat(records.get(1))
                    .isEqualTo(padded("<p>X  </p>"))
                    .doesNotContain("L");
            assertThat(handle.addressLine().readString(10, 90))
                    .isEqualTo(String.valueOf(SPACE).repeat(90));
        }

        @Test
        @DisplayName("HTML-BSIC-LN: a 48-character line then a 41-character one leaves no tail")
        void theBasicLineIsClearedBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            // 24 + 20 + 4 = 48 characters of content.
            local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "9".repeat(20));
            // 24 + 13 + 4 = 41 characters of content - seven shorter, so seven bytes could leak.
            local.writeBasicDetail(handle, BasicDetail.CURRENT_BALANCE, "000000012.34-");

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(0)).isEqualTo(
                    padded("<p>Account ID         : " + "9".repeat(20) + "</p>"));
            assertThat(records.get(1))
                    .isEqualTo(padded("<p>Current Balance    : 000000012.34-</p>"))
                    .doesNotContain("9999");
            assertThat(handle.basicLine().readString(41, 59))
                    .isEqualTo(String.valueOf(SPACE).repeat(59));
        }

        @Test
        @DisplayName("HTML-TRAN-LN: a 56-character line then a 20-character one leaves no tail")
        void theTransactionLineIsClearedBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            // 3 + 49 + 4 = 56 characters of content.
            local.writeTransactionField(handle, TransactionField.TRAN_DETAILS, "D".repeat(49));
            // 3 + 13 + 4 = 20 characters of content - thirty-six shorter. The amount is the already
            // edited 13-character image of PIC Z(9).99-: nine digit positions with the leading zeros
            // suppressed to spaces, the point, two more digits, and a blank sign position.
            local.writeTransactionField(handle, TransactionField.TRAN_AMOUNT, "       12.34 ");

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(0)).isEqualTo(padded("<p>" + "D".repeat(49) + "</p>"));
            assertThat(records.get(1))
                    .isEqualTo(padded("<p>       12.34 </p>"))
                    .doesNotContain("D");
            assertThat(handle.transactionLine().readString(20, 80))
                    .isEqualTo(String.valueOf(SPACE).repeat(80));
        }

        @Test
        @DisplayName("The three buffers are independent: writing one never disturbs another")
        void theThreeBuffersAreIndependent() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "ADDRESS");
            local.writeBasicDetail(handle, BasicDetail.FICO_SCORE, "700");
            local.writeTransactionField(handle, TransactionField.TRAN_ID, "TRAN0000000000001");

            // Each buffer still holds its own last composition, and each is exactly what was emitted.
            assertThat(handle.addressLine().readString(0, StatementHtmlWriter.RECORD_LENGTH))
                    .isEqualTo(padded("<p>ADDRESS  </p>"));
            assertThat(handle.basicLine().readString(0, StatementHtmlWriter.RECORD_LENGTH))
                    .isEqualTo(padded("<p>FICO Score         : 700"
                            + String.valueOf(SPACE).repeat(17) + "</p>"));
            assertThat(handle.transactionLine().readString(0, StatementHtmlWriter.RECORD_LENGTH))
                    .isEqualTo(padded("<p>TRAN000000000000</p>"));
        }

        @Test
        @DisplayName("Every buffer is 100 bytes wide - CBSTM03A.CBL:L47, L149, L221-L223")
        void everyBufferIsOneHundredBytesWide() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            assertThat(handle.recordArea().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.fixedLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.addressLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.basicLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.transactionLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            // The one exception, and it is declared as one: HTML-L11 is a 59-byte group, not a
            // PIC X(100) line, and the WRITE ... FROM is what pads it.
            assertThat(handle.accountHeadingLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.HTML_L11_LENGTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The dataset code page is not the payload's charset declaration - practice B8")
    class DatasetCodePageIsNotThePayloadCharset {

        /** A single-byte EBCDIC code page, the one {@code application.yml} names for the datasets. */
        private static final String EBCDIC_CODE_PAGE = "IBM037";

        @Test
        @DisplayName("HTML-L04 declares utf-8 as CONTENT: CBSTM03A.CBL:L153, transcribed not obeyed")
        void theMetaCharsetIsContentNotConfiguration() {
            // The literal is a byte sequence transcribed from a COBOL VALUE clause. It tells a browser
            // how to read the finished document later; it says nothing about how this dataset is
            // encoded, and this class must not read it as configuration.
            assertThat(HtmlFixedLine.HTML_L04.literal()).isEqualTo("<meta charset=\"utf-8\">");
            assertThat(StatementHtmlWriterTest.this.writer.datasetCharset())
                    .isEqualTo(StandardCharsets.US_ASCII)
                    .isNotEqualTo(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("The same literal is written in the INJECTED code page, not in the one it names")
        void theRecordIsWrittenInTheInjectedCodePage() {
            Charset ebcdic = Charset.forName(EBCDIC_CODE_PAGE);
            List<byte[]> ebcdicRecords = new ArrayList<>();
            StatementHtmlWriter ebcdicWriter = new StatementHtmlWriter(
                    mock(JdbcTemplate.class), ebcdic, bindingsFor(TEST_DSNAME),
                    RecordImageForm.CHARACTER);
            HtmlStatementFile ebcdicFile = ebcdicWriter.open(record -> {
                ebcdicRecords.add(record);
                return FileStatus.OK;
            });

            ebcdicWriter.writeFixedLine(ebcdicFile, HtmlFixedLine.HTML_L04);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L04);

            byte[] asEbcdic = ebcdicRecords.get(0);
            byte[] asAscii = StatementHtmlWriterTest.this.emitted.get(0);

            // Same 100 bytes of content, two different code pages, two different byte images. Both
            // decode back to the identical literal under their own charset - which is the whole point:
            // the record's encoding follows the injected Charset and nothing else.
            assertThat(asEbcdic).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(asAscii).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(asEbcdic).isNotEqualTo(asAscii);
            assertThat(new String(asEbcdic, ebcdic))
                    .isEqualTo(padded(HtmlFixedLine.HTML_L04.literal()));
            assertThat(new String(asAscii, StandardCharsets.US_ASCII))
                    .isEqualTo(padded(HtmlFixedLine.HTML_L04.literal()));
        }

        @Test
        @DisplayName("The pad byte follows the code page too: EBCDIC space x'40', ASCII space x'20'")
        void thePadByteFollowsTheCodePage() {
            Charset ebcdic = Charset.forName(EBCDIC_CODE_PAGE);
            List<byte[]> ebcdicRecords = new ArrayList<>();
            StatementHtmlWriter ebcdicWriter = new StatementHtmlWriter(
                    mock(JdbcTemplate.class), ebcdic, bindingsFor(TEST_DSNAME),
                    RecordImageForm.CHARACTER);
            HtmlStatementFile ebcdicFile = ebcdicWriter.open(record -> {
                ebcdicRecords.add(record);
                return FileStatus.OK;
            });

            // HTML-L03 is six characters, so bytes 6..99 are pure padding in both code pages.
            ebcdicWriter.writeFixedLine(ebcdicFile, HtmlFixedLine.HTML_L03);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L03);

            assertThat(ebcdicRecords.get(0)[99]).isEqualTo((byte) 0x40);
            assertThat(StatementHtmlWriterTest.this.emitted.get(0)[99]).isEqualTo((byte) 0x20);
            assertThat(ebcdicFile.recordArea().spacePadByte()).isEqualTo((byte) 0x40);
            assertThat(StatementHtmlWriterTest.this.file.recordArea().spacePadByte())
                    .isEqualTo((byte) 0x20);
        }

        @Test
        @DisplayName("Composed lines follow the code page as well, not only the fixed literals")
        void composedLinesFollowTheCodePageToo() {
            Charset ebcdic = Charset.forName(EBCDIC_CODE_PAGE);
            List<byte[]> ebcdicRecords = new ArrayList<>();
            StatementHtmlWriter ebcdicWriter = new StatementHtmlWriter(
                    mock(JdbcTemplate.class), ebcdic, bindingsFor(TEST_DSNAME),
                    RecordImageForm.CHARACTER);
            HtmlStatementFile ebcdicFile = ebcdicWriter.open(record -> {
                ebcdicRecords.add(record);
                return FileStatus.OK;
            });

            ebcdicWriter.writeBasicDetail(ebcdicFile, BasicDetail.ACCOUNT_ID, "00000000011");
            ebcdicWriter.writeAccountHeading(ebcdicFile, "00000000011");
            byte[] group = ebcdicWriter.composeNameParagraphGroup("MARGARET GOLD");

            assertThat(new String(ebcdicRecords.get(0), ebcdic)).isEqualTo(
                    padded("<p>Account ID         : 00000000011         </p>"));
            assertThat(new String(ebcdicRecords.get(1), ebcdic)).isEqualTo(
                    padded("<h3>Statement for Account Number: 00000000011         </h3>"));
            assertThat(group).hasSize(StatementHtmlWriter.HTML_L23_LENGTH);
            assertThat(new String(group, ebcdic)).isEqualTo(
                    "<p style=\"font-size:16px\">MARGARET GOLD"
                            + String.valueOf(SPACE).repeat(37));
            assertThat(ebcdicWriter.datasetCharset()).isEqualTo(ebcdic);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("No escaping and no trimming - practice B6")
    class NoEscapingAndNoTrimming {

        @Test
        @DisplayName("A customer name containing < > & is emitted raw, byte for byte")
        void aNameWithMarkupCharactersIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "<b>A & B</b>");

            String record = StatementHtmlWriterTest.this.lastRecordText();
            assertThat(record)
                    .isEqualTo(padded("<p style=\"font-size:16px\"><b>A & B</b>  </p>"));
            assertThat(record).doesNotContain("&lt;").doesNotContain("&gt;")
                    .doesNotContain("&amp;").doesNotContain("&quot;").doesNotContain("&#");
        }

        @Test
        @DisplayName("An address containing a quotation mark and an ampersand is emitted raw")
        void anAddressWithMarkupCharactersIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1,
                    "\"O'HARA\" & SONS");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>\"O'HARA\" & SONS  </p>"));
        }

        @Test
        @DisplayName("A basic-detail value containing markup is emitted raw and untrimmed")
        void aBasicDetailValueWithMarkupIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.FICO_SCORE, "<script>");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>FICO Score         : <script>" + " ".repeat(12) + "</p>"));
        }

        @Test
        @DisplayName("A transaction description containing markup is emitted raw")
        void aTransactionDescriptionWithMarkupIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_DETAILS, "A<B>C&D");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>A<B>C&D" + " ".repeat(42) + "</p>"));
        }

        @Test
        @DisplayName("Nothing is masked or redacted: the account identifier appears in full")
        void nothingIsMaskedOrRedacted() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.ACCOUNT_ID, "12345678901");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .contains("12345678901")
                    .doesNotContain("*")
                    .doesNotContain("REDACTED");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Sink injectability, ordering and lifecycle")
    class SinkAndLifecycle {

        @Test
        @DisplayName("Records reach the sink in exact call order, nothing reordered or deduplicated")
        void recordsReachTheSinkInCallOrder() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTRS);
            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTRS);
            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTDE);
            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTRE);

            assertThat(StatementHtmlWriterTest.this.allRecordText()).containsExactly(
                    padded("<tr>"), padded("<tr>"), padded("</td>"), padded("</tr>"));
        }

        @Test
        @DisplayName("A fresh handle starts open, at zero records, with the sink's open status")
        void aFreshHandleStartsOpen() {
            assertThat(StatementHtmlWriterTest.this.file.isOpen()).isTrue();
            assertThat(StatementHtmlWriterTest.this.file.recordsWritten()).isZero();
            assertThat(StatementHtmlWriterTest.this.file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(StatementHtmlWriterTest.this.file.sink()).isNotNull();
            assertThat(StatementHtmlWriterTest.this.file.fixedLine().recordLength()).isEqualTo(100);
            assertThat(StatementHtmlWriterTest.this.file.accountHeadingLine().recordLength())
                    .isEqualTo(59);
        }

        @Test
        @DisplayName("A non-OK open status is recorded, not acted on: CBSTM03A:L293 has no guard")
        void aNonOkOpenStatusIsRecordedNotActedOn() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                    new StatusReportingSink(FileStatus.OK, "35", FileStatus.OK));

            assertThat(handle.openStatus()).isEqualTo("35");
            assertThat(handle.isOpen()).isTrue();
        }

        @Test
        @DisplayName("close returns the sink's own close status as an outcome")
        void closeReturnsTheSinksCloseStatus() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                    new StatusReportingSink(FileStatus.OK, FileStatus.OK,
                            StatementHtmlWriter.PERMANENT_ERROR_STATUS));

            assertThat(StatementHtmlWriterTest.this.writer.close(handle))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(handle.isOpen()).isFalse();
        }

        @Test
        @DisplayName("A clean close reports OK")
        void aCleanCloseReportsOk() {
            assertThat(StatementHtmlWriterTest.this.writer.close(StatementHtmlWriterTest.this.file))
                    .isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("A second close is refused: CBSTM03A closes once, at L339")
        void aSecondCloseIsRefused() {
            StatementHtmlWriterTest.this.writer.close(StatementHtmlWriterTest.this.file);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> StatementHtmlWriterTest.this.writer
                            .close(StatementHtmlWriterTest.this.file))
                    .withMessageContaining("closed")
                    .withMessageContaining("L339");
        }

        @Test
        @DisplayName("Every write is refused after close")
        void everyWriteIsRefusedAfterClose() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;
            local.close(handle);

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeFixedLine(handle, HtmlFixedLine.HTML_L01));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeAccountHeading(handle, "1"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeNameLine(handle, "A"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "A"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "A"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeTransactionField(handle, TransactionField.TRAN_ID, "A"));
            assertThat(StatementHtmlWriterTest.this.emitted).isEmpty();
        }

        @Test
        @DisplayName("A null handle, sink, line, identity or value is rejected")
        void nullArgumentsAreRejected() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.open((HtmlRecordSink) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.close(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeFixedLine(null, HtmlFixedLine.HTML_L01));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeFixedLine(handle, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeAccountHeading(handle, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeNameLine(handle, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeAddressLine(handle, null, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeAddressLine(handle,
                            AddressField.ADDRESS_LINE_1, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeBasicDetail(handle, null, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeBasicDetail(handle,
                            BasicDetail.ACCOUNT_ID, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeTransactionField(handle, null, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeTransactionField(handle,
                            TransactionField.TRAN_ID, null));
        }

        @Test
        @DisplayName("A sink returning a null status from open, write or close is rejected")
        void aSinkReturningNullIsRejected() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.open(new StatusReportingSink(FileStatus.OK, null,
                            FileStatus.OK)))
                    .withMessageContaining("open()");

            HtmlStatementFile nullWrite = local.open(
                    new StatusReportingSink(null, FileStatus.OK, FileStatus.OK));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeFixedLine(nullWrite, HtmlFixedLine.HTML_L01))
                    .withMessageContaining("write(byte[])");

            HtmlStatementFile nullClose = local.open(
                    new StatusReportingSink(FileStatus.OK, FileStatus.OK, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.close(nullClose))
                    .withMessageContaining("close()");
        }

        @Test
        @DisplayName("The default HtmlRecordSink open and close report OK without doing anything")
        void theDefaultOpenAndCloseReportOk() {
            HtmlRecordSink lambda = record -> FileStatus.OK;

            assertThat(lambda.open()).isEqualTo(FileStatus.OK);
            assertThat(lambda.close()).isEqualTo(FileStatus.OK);
        }

        /**
         * Every {@code FILE STATUS} a sink can report becomes the outcome
         * {@link FileStatus#outcomeOfStatus(String)} classifies it as, and none of them becomes an
         * exception. This writer <strong>surfaces</strong> status; it never abends.
         *
         * <p>That division is {@code CBSTM03A}'s own. The program keeps its
         * {@code CALL 'CEE3ABD'} at {@code L923}, in the caller, and its file handling reports a
         * two-character code - compare {@code WS-M03B-RC PIC X(02)} at {@code L80} and the
         * {@code EVALUATE WS-M03B-RC} guard at {@code L353-L359}, whose {@code WHEN OTHER} arm is what
         * decides to abend. Deciding is {@code StatementGenerationJobA}'s job, so all five arms have
         * to reach it intact, including the writer's own permanent-error code.
         *
         * @param status  the two-character status the sink reports
         * @param outcome the outcome the writer must return for it
         */
        @ParameterizedTest(name = "FILE STATUS ''{0}'' -> {1}")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#writeStatuses")
        @DisplayName("Every FILE STATUS a sink reports is surfaced as its outcome, never thrown")
        void everyReportedStatusIsSurfacedAsItsOutcome(final String status,
                                                       final FileStatus.Outcome outcome) {
            List<byte[]> records = new ArrayList<>();
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(record -> {
                records.add(record);
                return status;
            });

            FileStatus.Outcome reported = StatementHtmlWriterTest.this.writer.writeFixedLine(
                    handle, HtmlFixedLine.HTML_L01);

            assertThat(reported).isEqualTo(outcome);
            // The record was still handed to the sink and still counted: a status is a report about
            // what happened, not a veto applied beforehand.
            assertThat(records).hasSize(1);
            assertThat(records.get(0)).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.recordsWritten()).isEqualTo(1L);
            assertThat(handle.isOpen()).isTrue();
        }

        @Test
        @DisplayName("The writer's own permanent-error status is '30', and it classifies as OTHER")
        void thePermanentErrorStatusClassifiesAsOther() {
            // '30' is declared on the writer rather than on FileStatus because no COBOL program in
            // app/cbl tests for it: every batch program guards '00', '10', '23' or '22' and abends on
            // anything else. Classifying it as OTHER puts it in exactly that WHEN OTHER arm, so a
            // caller's control flow is unchanged.
            assertThat(StatementHtmlWriter.PERMANENT_ERROR_STATUS).isEqualTo("30");
            assertThat(FileStatus.outcomeOfStatus(StatementHtmlWriter.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(StatementHtmlWriter.PERMANENT_ERROR_STATUS).isNotEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("close() surfaces every status too, and still closes the handle")
        void closeSurfacesEveryStatusAndStillCloses() {
            for (String status : List.of(FileStatus.OK, FileStatus.END_OF_FILE,
                    StatementHtmlWriter.PERMANENT_ERROR_STATUS)) {
                HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                        new StatusReportingSink(FileStatus.OK, FileStatus.OK, status));

                FileStatus.Outcome reported =
                        StatementHtmlWriterTest.this.writer.close(handle);

                assertThat(reported)
                        .as("close outcome for FILE STATUS '%s'", status)
                        .isEqualTo(FileStatus.outcomeOfStatus(status));
                // A non-OK close still closes: CBSTM03A closes once at L339 with no guard, so the
                // handle must not be left open for a caller to write through again.
                assertThat(handle.isOpen()).isFalse();
            }
        }

        @Test
        @DisplayName("A sink reporting a failure is surfaced as Outcome.OTHER, not thrown")
        void aFailingSinkIsSurfacedAsAnOutcome() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                    record -> StatementHtmlWriter.PERMANENT_ERROR_STATUS);

            assertThat(StatementHtmlWriterTest.this.writer
                    .writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("Two handles are fully independent - no shared state anywhere")
        void twoHandlesAreIndependent() {
            List<byte[]> other = new ArrayList<>();
            HtmlStatementFile second = StatementHtmlWriterTest.this.writer.open(record -> {
                other.add(record);
                return FileStatus.OK;
            });

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L01);
            StatementHtmlWriterTest.this.writer.writeFixedLine(second, HtmlFixedLine.HTML_L80);

            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);
            assertThat(other).hasSize(1);
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<!DOCTYPE html>"));
            assertThat(new String(other.get(0), StandardCharsets.US_ASCII))
                    .isEqualTo(padded("</html>"));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The default JdbcTemplate-backed sink")
    class DefaultJdbcSink {

        @Test
        @DisplayName("It issues one single-column INSERT per record, with no DDL and no column name")
        void itIssuesOneSingleColumnInsertPerRecord() {
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.dsname()).isEqualTo(TEST_DSNAME);
            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
            assertThat(sink.insertStatement())
                    .doesNotContain("CREATE").doesNotContain("ALTER").doesNotContain("(RECORD");
        }

        @Test
        @DisplayName("The dotted name is ONE delimited identifier, not a qualified SQL reference")
        void theDottedNameIsOneDelimitedIdentifier() {
            // This is the defect F07 named. The statement used to be assembled as prefix + raw name
            // + suffix, so a name like A.B.C.D reached the parser as a four-part qualified reference
            // and addressed - at best - nothing. Quoting makes it one object.
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.insertStatement()).startsWith("INSERT INTO \"").contains("\" VALUES (?)");
            assertThat(sink.insertStatement()).doesNotContain("INTO " + TEST_DSNAME);
        }

        @Test
        @DisplayName("Both statement writers render the same dataset name identically (F07)")
        void bothWritersRenderTheSameNameIdentically() {
            // The two writers face one deployment driver whose syntax neither can exercise here, so
            // if they rendered a name differently at most one of them could be right. Bound to the
            // same name they must produce the same statement, character for character - and both are
            // pinned to DatasetRelation, so that equality is structural rather than coincidental.
            String shared = "TEST.M2.SHARED.SEQ";

            DatasetBindings textCatalogue = new DatasetBindings();
            textCatalogue.put("STMTFILE", new DatasetBinding(shared, "sequential", false, "FB",
                    8000, 80, null, null, null, null, null));
            StatementTextWriter textWriter = new StatementTextWriter(mock(JdbcTemplate.class),
                    StandardCharsets.US_ASCII, textCatalogue, RecordImageForm.CHARACTER);

            JdbcHtmlRecordSink htmlSink = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, shared).defaultSink();

            assertThat(htmlSink.insertStatement()).isEqualTo(textWriter.insertStatement());
        }

        @Test
        @DisplayName("A successful write reports OK and clears any previous failure")
        void aSuccessfulWriteReportsOk() {
            when(StatementHtmlWriterTest.this.jdbcTemplate.update(anyString(),
                    any(PreparedStatementSetter.class))).thenReturn(1);
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.write(new byte[StatementHtmlWriter.RECORD_LENGTH]))
                    .isEqualTo(FileStatus.OK);
            assertThat(sink.lastFailure()).isEmpty();
        }

        @Test
        @DisplayName("binds the record image through the configured representation, never an untyped "
                + "argument")
        void theRecordImageIsBoundThroughTheConfiguredForm() throws SQLException {
            // Not merely a stylistic match with StatementTextWriter, and no longer this sink's decision.
            // An untyped argument leaves the driver to pick a type for a byte[]; naming the type states
            // it. WHICH type is right is a property of the deployment's driver, so it comes from
            // configuration and every reader and writer in the module asks for the same one. Both forms
            // are exercised here, so neither is theoretical.
            for (RecordImageForm form : RecordImageForm.values()) {
                DataSource dataSource = mock(DataSource.class);
                Connection connection = mock(Connection.class);
                PreparedStatement statement = mock(PreparedStatement.class);
                when(dataSource.getConnection()).thenReturn(connection);
                when(connection.prepareStatement(anyString())).thenReturn(statement);

                byte[] image = new byte[StatementHtmlWriter.RECORD_LENGTH];
                java.util.Arrays.fill(image, (byte) ' ');
                image[0] = (byte) 'X';

                assertThat(newWriter(new JdbcTemplate(dataSource), StatementHtmlWriter.RECORD_LENGTH,
                        TEST_DSNAME, form).defaultSink().write(image)).isEqualTo(FileStatus.OK);

                verify(connection).prepareStatement("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
                if (form == RecordImageForm.BINARY) {
                    verify(statement).setBytes(1, image);
                    verify(statement, never()).setString(anyInt(), anyString());
                } else {
                    verify(statement).setString(1,
                            new String(image, StandardCharsets.US_ASCII));
                    verify(statement, never()).setBytes(anyInt(), any());
                }
                verify(statement).executeUpdate();
            }
        }

        @Test
        @DisplayName("A DataAccessException becomes the permanent-error status and is retained")
        void aDataAccessExceptionBecomesThePermanentErrorStatus() {
            doThrow(new DataAccessResourceFailureException("no driver"))
                    .when(StatementHtmlWriterTest.this.jdbcTemplate)
                    .update(anyString(), any(PreparedStatementSetter.class));
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.write(new byte[StatementHtmlWriter.RECORD_LENGTH]))
                    .isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            assertThat(FileStatus.outcomeOfStatus(StatementHtmlWriter.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            // The diagnosis survives; the exception does not. A driver's message is prose the backend
            // composed around the record it refused, and an HTML statement record carries a customer's
            // name, address and transactions - so publishing the exception published those (CWE-532), in
            // text a control character could split into a forged log entry (CWE-117). What remains is
            // what an operator acts on.
            assertThat(sink.lastFailure()).isPresent();
            DatasetRelation.BackendDiagnostic reported = sink.lastFailure().orElseThrow();
            assertThat(reported.exceptionType())
                    .isEqualTo(DataAccessResourceFailureException.class.getName());
            assertThat(reported.toString()).doesNotContain("no driver");
            assertThat(reported.describe()).doesNotContain("no driver");
        }

        @Test
        @DisplayName("a driver message carrying record content never reaches lastFailure()")
        void aDriverMessageCarryingRecordContentIsNotRetained() {
            String customer = "MARGARET GOLD, 1 HIGH STREET";
            doThrow(new DataAccessResourceFailureException("rejected: " + customer,
                    new java.sql.SQLException("value '" + customer + "' too long", "22001", 1)))
                    .when(StatementHtmlWriterTest.this.jdbcTemplate)
                    .update(anyString(), any(PreparedStatementSetter.class));
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.write(new byte[StatementHtmlWriter.RECORD_LENGTH]))
                    .isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            DatasetRelation.BackendDiagnostic reported = sink.lastFailure().orElseThrow();
            assertThat(reported.toString()).doesNotContain(customer);
            assertThat(reported.describe()).doesNotContain(customer);
            // The codes that distinguish one refusal from another are all still there.
            assertThat(reported.sqlState()).isEqualTo("22001");
            assertThat(reported.vendorCode()).isEqualTo(1);
        }

        @Test
        @DisplayName("open() uses the default sink, and its records go through the template")
        void openUsesTheDefaultSink() {
            when(StatementHtmlWriterTest.this.jdbcTemplate.update(anyString(),
                    any(PreparedStatementSetter.class))).thenReturn(1);
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open();

            assertThat(StatementHtmlWriterTest.this.writer
                    .writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(handle.recordsWritten()).isEqualTo(1L);
        }

        @Test
        @DisplayName("A filesystem location - what the test profile binds - is refused, with guidance")
        void aFilesystemLocationIsRefused() {
            StatementHtmlWriter fileBound = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "/tmp/carddemo/statement.html");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(fileBound::defaultSink)
                    .withMessageContaining("cannot be addressed as a dataset")
                    .withMessageContaining("open(HtmlRecordSink)")
                    .withCauseInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @DisplayName("Every shape a z/OS dataset name cannot take is refused, not merely quoted")
        @ValueSource(strings = {
            "A.B; DROP TABLE C",
            "A.B'C",
            "A.B\"C",
            "A.B C",
            "A.B,C",
            "A.B(C",
            "TOOLONGQUALIFIER.B",
            "9BAD.START",
            "A..B",
            "A.B.",
            "classpath:fixtures/statement.html",
        })
        void everyUnusableShapeIsRefused(final String candidate) {
            // A grammar, not an allowlist. The allowlist this replaced admitted parentheses and
            // repeated hyphens anywhere in a name, and admitted a nine-character qualifier, while its
            // own documentation claimed comment markers were refused (F20).
            StatementHtmlWriter bound = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, candidate);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(bound::defaultSink);
        }

        @Test
        @DisplayName("An empty configured name is refused")
        void anEmptyNameIsRefused() {
            StatementHtmlWriter blank = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(blank::defaultSink)
                    .withMessageContaining("dsname is not configured");
        }

        @Test
        @DisplayName("An absent configured name is refused")
        void anAbsentNameIsRefused() {
            StatementHtmlWriter absent = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(absent::defaultSink)
                    .withMessageContaining("dsname is not configured");
        }

        @Test
        @DisplayName("A generation-qualified name is accepted: the grammar admits the (+n) suffix")
        void aGenerationQualifiedNameIsAccepted() {
            // The three generation-data-group outputs in application.yml carry this form, so the
            // grammar has to admit it. National characters and a hyphen inside a qualifier are legal
            // too; an underscore is not, which is why the earlier expectation for this case used a
            // name z/OS would itself have rejected.
            StatementHtmlWriter gdg = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TEST.M2-A.B$C@D#E.SEQ(+1)");

            assertThatCode(gdg::defaultSink).doesNotThrowAnyException();
            assertThat(gdg.defaultSink().insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2-A.B$C@D#E.SEQ(+1)\" VALUES (?)");
        }

        @Test
        @DisplayName("Construction still succeeds under a fixture-backed binding, so G3 holds")
        void constructionStillSucceedsUnderAFixtureBackedBinding() {
            StatementHtmlWriter fileBound = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "/tmp/carddemo/statement.html");

            assertThat(fileBound.recordLength()).isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThatCode(() -> fileBound.open(record -> FileStatus.OK))
                    .doesNotThrowAnyException();
        }
    }

    // =============================================================================================

    /**
     * A {@link JdbcTemplate} whose {@code execute(ConnectionCallback)} really runs the callback
     * against a driver that reports {@code reportedQuote} as its identifier quote string.
     *
     * <p>The callback is invoked rather than stubbed away, so the body under test - ask the connection
     * for its metadata, ask the metadata for its quote - is the thing being exercised.
     *
     * @param reportedQuote what {@code getIdentifierQuoteString()} answers, or {@code null} to make
     *                      {@code getMetaData()} itself answer {@code null}
     * @return the template
     * @throws SQLException never; declared because the mocked driver methods declare it
     */
    private static JdbcTemplate driverReporting(final String reportedQuote) throws SQLException {
        JdbcTemplate template = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        if (reportedQuote == null) {
            when(connection.getMetaData()).thenReturn(null);
        } else {
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(metaData.getIdentifierQuoteString()).thenReturn(reportedQuote);
            when(connection.getMetaData()).thenReturn(metaData);
        }
        doAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        }).when(template).execute(ArgumentMatchers.<ConnectionCallback<String>>any());
        return template;
    }

    @Nested
    @DisplayName("The dataset name is one delimited identifier - F14, SQL statement structure")
    class DelimitedDatasetIdentifier {

        @Test
        @DisplayName("The configured name is quoted, so it contributes one identifier and no tokens")
        void theConfiguredNameIsQuoted() {
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2.STATEMNT.HTML\" VALUES (?)");
            assertThat(sink.identifierQuote()).isEqualTo("\"");
        }

        @Test
        @DisplayName("A name shaped like a column reference cannot become one")
        void aNameShapedLikeAColumnReferenceCannotBecomeOne() {
            StatementHtmlWriter targeted = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TARGET(COLUMN)");

            // Two barriers stand between a configured name and a SQL statement, and this name never
            // reaches the second. The dataset-name grammar admits only a relative generation inside
            // parentheses, so "TARGET(COLUMN)" is refused outright rather than delimited: no statement
            // is composed at all, which is a stronger outcome than composing one that happens to be
            // safe. The escaping barrier itself is asserted directly further down, against a name that
            // carries a quotation mark.
            assertThatIllegalStateException().isThrownBy(targeted::defaultSink)
                    .withMessageContaining("cannot be addressed as a dataset")
                    .satisfies(refused -> assertThat(refused.getCause())
                            .hasMessageContaining("not a well-formed z/OS dataset name")
                            .hasMessageContaining("relative generation"));
        }

        @Test
        @DisplayName("A comment marker cannot comment out the rest of the statement")
        void aCommentMarkerCannotCommentOutTheStatement() {
            StatementHtmlWriter commented = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "A.B--C");

            String statement = commented.defaultSink().insertStatement();

            assertThat(statement).isEqualTo("INSERT INTO \"A.B--C\" VALUES (?)");
            // The parameter is still there: nothing after the name has been commented away.
            assertThat(statement).endsWith(" VALUES (?)");
            // And the comment marker is bracketed by the delimiters rather than opening a comment.
            assertThat(statement.indexOf("--")).isGreaterThan(statement.indexOf('"'));
            assertThat(statement.indexOf("--")).isLessThan(statement.lastIndexOf('"'));
        }

        @Test
        @DisplayName("A generation-qualified name survives unchanged inside the delimiters")
        void aGenerationQualifiedNameSurvivesUnchanged() {
            // Every character here is one a z/OS qualifier admits - letters, digits, a hyphen and the
            // three national characters - followed by the relative generation the configuration
            // actually uses for the generation-data-group outputs. All of it survives the delimiting
            // verbatim, which is what a deployment confirming its own statement needs to see.
            StatementHtmlWriter gdg = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TEST.M2-A9$@#.SEQ(+1)");

            assertThat(gdg.defaultSink().insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2-A9$@#.SEQ(+1)\" VALUES (?)");
        }

        @Test
        @DisplayName("An underscore is not a z/OS qualifier character, so such a name is refused")
        void anUnderscoreIsRefused() {
            // The grammar is a grammar rather than a list of forbidden characters, which is why it
            // refuses a name no platform would accept even though nothing about an underscore is
            // dangerous in SQL. A name that is not a dataset name is not addressed as one.
            StatementHtmlWriter underscored = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TEST.M2_A.SEQ");

            assertThatIllegalStateException().isThrownBy(underscored::defaultSink)
                    .withMessageContaining("cannot be addressed as a dataset");
        }

        @Test
        @DisplayName("The driver's own quote character is used when it reports one")
        void theDriversOwnQuoteCharacterIsUsed() throws SQLException {
            StatementHtmlWriter backtick = newWriter(driverReporting("`"),
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            JdbcHtmlRecordSink sink = backtick.defaultSink();

            assertThat(sink.identifierQuote()).isEqualTo("`");
            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO `TEST.M2.STATEMNT.HTML` VALUES (?)");
        }

        @Test
        @DisplayName("A driver that reports no quoting support gets the SQL-standard quote")
        void aDriverThatReportsNoQuotingSupportGetsTheStandardQuote() throws SQLException {
            // The JDBC contract defines a single space as "identifier quoting is not supported".
            StatementHtmlWriter unquoting = newWriter(driverReporting(" "),
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            assertThat(unquoting.defaultSink().identifierQuote()).isEqualTo("\"");
        }

        @Test
        @DisplayName("A driver whose metadata is absent gets the SQL-standard quote")
        void aDriverWhoseMetadataIsAbsentGetsTheStandardQuote() throws SQLException {
            StatementHtmlWriter noMetadata = newWriter(driverReporting(null),
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            assertThat(noMetadata.defaultSink().identifierQuote()).isEqualTo("\"");
        }

        @Test
        @DisplayName("An unreachable driver still composes a delimited statement, and does not throw")
        void anUnreachableDriverStillComposesADelimitedStatement() {
            JdbcTemplate unreachable = mock(JdbcTemplate.class);
            doThrow(new DataAccessResourceFailureException("no driver"))
                    .when(unreachable).execute(ArgumentMatchers.<ConnectionCallback<String>>any());
            StatementHtmlWriter writer = newWriter(unreachable,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            JdbcHtmlRecordSink sink = writer.defaultSink();

            assertThat(sink.identifierQuote()).isEqualTo("\"");
            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2.STATEMNT.HTML\" VALUES (?)");
        }

        @Test
        @DisplayName("A quote character inside the name is doubled, the SQL-standard escape")
        void aQuoteCharacterInsideTheNameIsDoubled() {
            // The allowlist refuses a quotation mark in a configured name, so this asserts the
            // escape directly - the second, independent barrier, on the assumption the first is gone.
            assertThat(StatementHtmlWriter.insertStatement("ODD\"NAME", "\""))
                    .isEqualTo("INSERT INTO \"ODD\"\"NAME\" VALUES (?)");
            assertThat(StatementHtmlWriter.insertStatement("A`B", "`"))
                    .isEqualTo("INSERT INTO `A``B` VALUES (?)");
        }

        @Test
        @DisplayName("A name that tries to close its own identifier cannot escape it")
        void aNameThatTriesToCloseItsOwnIdentifierCannotEscapeIt() {
            String statement = StatementHtmlWriter.insertStatement("X\"; DROP TABLE Y; --", "\"");

            // The injected closing quote is doubled, so it is a literal character in the name rather
            // than the end of the identifier, and everything after it stays inside.
            assertThat(statement)
                    .isEqualTo("INSERT INTO \"X\"\"; DROP TABLE Y; --\" VALUES (?)");
            assertThat(statement).endsWith(" VALUES (?)");
        }

        @Test
        @DisplayName("The composer refuses a blank or absent quote rather than emitting a bare name")
        void theComposerRefusesABlankOrAbsentQuote() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StatementHtmlWriter.insertStatement(TEST_DSNAME, " "))
                    .withMessageContaining("cannot be blank")
                    .withMessageContaining("normaliseIdentifierQuote");
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementHtmlWriter.insertStatement(TEST_DSNAME, null))
                    .withMessageContaining("identifier quote");
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementHtmlWriter.insertStatement(null, "\""))
                    .withMessageContaining("HTMLFILE");
        }

        @Test
        @DisplayName("Normalisation substitutes the standard quote for null and blank, and only those")
        void normalisationSubstitutesTheStandardQuoteForNullAndBlank() {
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote(null)).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote(" ")).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("")).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("`")).isEqualTo("`");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("\"")).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("[")).isEqualTo("[");
        }

        @Test
        @DisplayName("Record bytes stay parameter-bound: the name is the only interpolated text")
        void recordBytesStayParameterBound() {
            when(StatementHtmlWriterTest.this.jdbcTemplate.update(anyString(), any(Object[].class)))
                    .thenReturn(1);
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();
            byte[] record = new byte[StatementHtmlWriter.RECORD_LENGTH];
            java.util.Arrays.fill(record, (byte) '\'');

            assertThat(sink.write(record)).isEqualTo(FileStatus.OK);

            // Exactly one positional parameter, and no record byte anywhere in the statement text.
            assertThat(sink.insertStatement()).containsOnlyOnce("?");
            assertThat(sink.insertStatement()).doesNotContain("'");
        }

        @Test
        @DisplayName("The record width is untouched by the quoting change: still 100 bytes, gate G20")
        void theRecordWidthIsUntouched() {
            assertThat(StatementHtmlWriter.RECORD_LENGTH).isEqualTo(100);
            assertThat(StatementTextWriter.RECORD_LENGTH).isEqualTo(80);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The eleven continued literals - the fixed-format continuation rule applied by hand")
    class ContinuationRule {

        /**
         * Eleven of the thirty-four literals are continued across two source lines, and every one of
         * them is asserted here as the explicit join of its two fragments.
         *
         * <p>The rule being reproduced is COBOL's fixed-format continuation: the continued line
         * carries an alphanumeric literal with <em>no</em> closing quotation mark, the next line
         * carries a hyphen in column 7 and reopens the literal with a quotation mark, and the
         * continuation begins with the character immediately after that quotation mark.
         * <strong>Nothing is inserted at the join</strong> - no space, no newline and no concatenation
         * operator.
         *
         * <p>One subtlety decides whether that reading is right, and it is settled by the source's own
         * geometry rather than by convention. Any spaces at the end of a continued line, through
         * column 72, would be <em>inside</em> the literal. In this source every continued line is
         * exactly 72 characters long, so each first fragment ends precisely at column 72 and carries
         * no trailing spaces at all - which is why the plain join is correct here. That is what
         * {@link #noFragmentCarriesWhitespaceAtTheJoin()} pins.
         *
         * @param line           the constant under test
         * @param sourceLines    the two source lines it is continued across, for the failure message
         * @param firstFragment  the characters from the opening quotation mark to column 72
         * @param secondFragment the characters after the continuation line's reopening quotation mark
         */
        @ParameterizedTest(name = "{1} {0}")
        @MethodSource(
                "com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#continuedLiterals")
        @DisplayName("Each continued literal is its two fragments joined with nothing between")
        void theJoinInsertsNothing(final HtmlFixedLine line, final String sourceLines,
                                   final String firstFragment, final String secondFragment) {
            assertThat(line.literal())
                    .as("%s is continued across %s", line.cobolName(), sourceLines)
                    .isEqualTo(firstFragment + secondFragment);
            assertThat(line.literalLength())
                    .isEqualTo(firstFragment.length() + secondFragment.length());

            // The two characters that actually meet at the boundary, asserted individually so a
            // failure says which side drifted rather than dumping two 80-character strings.
            assertThat(line.literal().charAt(firstFragment.length() - 1))
                    .as("last character of the first fragment of %s", line.cobolName())
                    .isEqualTo(firstFragment.charAt(firstFragment.length() - 1));
            assertThat(line.literal().charAt(firstFragment.length()))
                    .as("first character of the continuation of %s", line.cobolName())
                    .isEqualTo(secondFragment.charAt(0));
        }

        /**
         * The negative form of the same rule: inserting a space at the join - the single most likely
         * way to mistranscribe a continued literal, because a human reading two lines sees a line
         * break where the compiler sees none - produces a different value in every one of the eleven
         * cases.
         *
         * @param line           the constant under test
         * @param sourceLines    the two source lines it is continued across
         * @param firstFragment  the first fragment
         * @param secondFragment the continuation
         */
        @ParameterizedTest(name = "{1} {0}")
        @MethodSource(
                "com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#continuedLiterals")
        @DisplayName("Inserting a space at the join would change the value, and does not match")
        void insertingASpaceAtTheJoinWouldNotMatch(final HtmlFixedLine line,
                                                   final String sourceLines,
                                                   final String firstFragment,
                                                   final String secondFragment) {
            assertThat(line.literal())
                    .as("%s, continued across %s, must not carry an inserted space", line.cobolName(),
                            sourceLines)
                    .isNotEqualTo(firstFragment + SPACE + secondFragment);
            assertThat(line.literal()).doesNotContain("\n").doesNotContain("\r")
                    .doesNotContain("\t").doesNotContain("'");
        }

        @Test
        @DisplayName("No fragment carries whitespace at the join: every continued line is 72 columns")
        void noFragmentCarriesWhitespaceAtTheJoin() {
            continuedLiterals().forEach(arguments -> {
                Object[] parts = arguments.get();
                String firstFragment = (String) parts[2];
                String secondFragment = (String) parts[3];
                assertThat(firstFragment).as("first fragment of %s", parts[0])
                        .doesNotEndWith(String.valueOf(SPACE));
                assertThat(secondFragment).as("continuation of %s", parts[0])
                        .doesNotStartWith(String.valueOf(SPACE));
            });
        }

        @Test
        @DisplayName("Exactly eleven of the thirty-four literals are continued; twenty-three are not")
        void exactlyElevenLiteralsAreContinued() {
            assertThat(continuedLiterals()).hasSize(11);
            assertThat(HtmlFixedLine.values()).hasSize(34);
            assertThat(HtmlFixedLine.values().length - 11).isEqualTo(23);
        }

        @Test
        @DisplayName("The four colspan cells share ONE first fragment ending at 'padding:0px 5px;'")
        void theColspanCellsShareOneFirstFragment() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L10, HtmlFixedLine.HTML_L15,
                    HtmlFixedLine.HTML_L22_35, HtmlFixedLine.HTML_L30_42)) {
                assertThat(line.literal())
                        .as("%s opens with the shared colspan fragment", line.cobolName())
                        .startsWith(COLSPAN_FIRST_FRAGMENT);
            }
            // The shared fragment ends AT the semicolon, so the absence of a space before
            // background-color is a property of the boundary itself, not of any one literal.
            assertThat(COLSPAN_FIRST_FRAGMENT).endsWith("padding:0px 5px;").hasSize(39);
        }

        @Test
        @DisplayName("The six width cells split the word 'background-color' across the join")
        void theWidthCellsSplitTheWordBackgroundColour() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L47, HtmlFixedLine.HTML_L50,
                    HtmlFixedLine.HTML_L53, HtmlFixedLine.HTML_L58, HtmlFixedLine.HTML_L61,
                    HtmlFixedLine.HTML_L64)) {
                assertThat(line.literal())
                        .as("%s carries the space the colspan cells do not", line.cobolName())
                        .contains("padding:0px 5px; background-color:");
            }
            // Three first fragments, each used twice: once with #33FF5E and once with #f2f2f2. Each
            // ends at the hyphen of "background-", so the continuation begins "color:" and the word
            // is reassembled only by the join.
            assertThat(WIDTH_FIRST_FRAGMENT_25).endsWith("background-").hasSize(50);
            assertThat(WIDTH_FIRST_FRAGMENT_55).endsWith("background-").hasSize(50);
            assertThat(WIDTH_FIRST_FRAGMENT_20).endsWith("background-").hasSize(50);
            assertThat(HtmlFixedLine.HTML_L47.literal()).startsWith(WIDTH_FIRST_FRAGMENT_25);
            assertThat(HtmlFixedLine.HTML_L58.literal()).startsWith(WIDTH_FIRST_FRAGMENT_25);
            assertThat(HtmlFixedLine.HTML_L50.literal()).startsWith(WIDTH_FIRST_FRAGMENT_55);
            assertThat(HtmlFixedLine.HTML_L61.literal()).startsWith(WIDTH_FIRST_FRAGMENT_55);
            assertThat(HtmlFixedLine.HTML_L53.literal()).startsWith(WIDTH_FIRST_FRAGMENT_20);
            assertThat(HtmlFixedLine.HTML_L64.literal()).startsWith(WIDTH_FIRST_FRAGMENT_20);
        }

        @Test
        @DisplayName("HTML-L08's join falls mid-word: 'styl' + 'e=' - CBSTM03A.CBL:L157-L158")
        void theTableJoinFallsMidWord() {
            assertThat(TABLE_FIRST_FRAGMENT).endsWith("styl").hasSize(39);
            assertThat(HtmlFixedLine.HTML_L08.literal())
                    .startsWith(TABLE_FIRST_FRAGMENT)
                    .contains("style=\"width:70%;")
                    .hasSize(85);
        }
    }


    // =============================================================================================

    /**
     * The expected literal of every {@link HtmlFixedLine} constant, written out independently of the
     * enum so a transcription error has to be made twice to survive.
     *
     * @return one argument pair per constant
     */
    static Stream<Arguments> expectedFixedLiterals() {
        return Stream.of(
                Arguments.of(HtmlFixedLine.HTML_L01, "<!DOCTYPE html>"),
                Arguments.of(HtmlFixedLine.HTML_L02, "<html lang=\"en\">"),
                Arguments.of(HtmlFixedLine.HTML_L03, "<head>"),
                Arguments.of(HtmlFixedLine.HTML_L04, "<meta charset=\"utf-8\">"),
                Arguments.of(HtmlFixedLine.HTML_L05, "<title>HTML Table Layout</title>"),
                Arguments.of(HtmlFixedLine.HTML_L06, "</head>"),
                Arguments.of(HtmlFixedLine.HTML_L07, "<body style=\"margin:0px;\">"),
                Arguments.of(HtmlFixedLine.HTML_L08, "<table  align=\"center\" frame=\"box\" "
                        + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">"),
                Arguments.of(HtmlFixedLine.HTML_LTRS, "<tr>"),
                Arguments.of(HtmlFixedLine.HTML_LTRE, "</tr>"),
                Arguments.of(HtmlFixedLine.HTML_LTDS, "<td>"),
                Arguments.of(HtmlFixedLine.HTML_LTDE, "</td>"),
                Arguments.of(HtmlFixedLine.HTML_L10, "<td colspan=\"3\" style=\"padding:0px 5px;"
                        + "background-color:#1d1d96b3;\">"),
                Arguments.of(HtmlFixedLine.HTML_L15, "<td colspan=\"3\" style=\"padding:0px 5px;"
                        + "background-color:#FFAF33;\">"),
                Arguments.of(HtmlFixedLine.HTML_L16,
                        "<p style=\"font-size:16px\">Bank of XYZ</p>"),
                Arguments.of(HtmlFixedLine.HTML_L17, "<p>410 Terry Ave N</p>"),
                Arguments.of(HtmlFixedLine.HTML_L18, "<p>Seattle WA 99999</p>"),
                Arguments.of(HtmlFixedLine.HTML_L22_35, "<td colspan=\"3\" style=\""
                        + "padding:0px 5px;background-color:#f2f2f2;\">"),
                Arguments.of(HtmlFixedLine.HTML_L30_42, "<td colspan=\"3\" style=\""
                        + "padding:0px 5px;background-color:#33FFD1; text-align:center;\">"),
                Arguments.of(HtmlFixedLine.HTML_L31,
                        "<p style=\"font-size:16px\">Basic Details</p>"),
                Arguments.of(HtmlFixedLine.HTML_L43,
                        "<p style=\"font-size:16px\">Transaction Summary</p>"),
                Arguments.of(HtmlFixedLine.HTML_L47, "<td style=\"width:25%; padding:0px 5px; "
                        + "background-color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L48, "<p style=\"font-size:16px\">Tran ID</p>"),
                Arguments.of(HtmlFixedLine.HTML_L50, "<td style=\"width:55%; padding:0px 5px; "
                        + "background-color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L51,
                        "<p style=\"font-size:16px\">Tran Details</p>"),
                Arguments.of(HtmlFixedLine.HTML_L53, "<td style=\"width:20%; padding:0px 5px; "
                        + "background-color:#33FF5E; text-align:right;\">"),
                Arguments.of(HtmlFixedLine.HTML_L54, "<p style=\"font-size:16px\">Amount</p>"),
                Arguments.of(HtmlFixedLine.HTML_L58, "<td style=\"width:25%; padding:0px 5px; "
                        + "background-color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L61, "<td style=\"width:55%; padding:0px 5px; "
                        + "background-color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L64, "<td style=\"width:20%; padding:0px 5px; "
                        + "background-color:#f2f2f2; text-align:right;\">"),
                Arguments.of(HtmlFixedLine.HTML_L75, "<h3>End of Statement</h3>"),
                Arguments.of(HtmlFixedLine.HTML_L78, "</table>"),
                Arguments.of(HtmlFixedLine.HTML_L79, "</body>"),
                Arguments.of(HtmlFixedLine.HTML_L80, "</html>"));
    }

    // =============================================================================================
    // The first fragments of the continued literals, transcribed from the source lines they sit on.
    //
    // Each is the text from its opening quotation mark through column 72 - the whole of the literal
    // the continued line contributes. They are named because they are SHARED: the four colspan cells
    // all use COLSPAN_FIRST_FRAGMENT, and the six width cells use only three fragments between them,
    // each twice. Naming them is what lets the sharing itself be asserted, so a change to one cell
    // cannot silently diverge from its twin.
    // =============================================================================================

    /**
     * {@code app/cbl/CBSTM03A.CBL:L157} - the first fragment of {@code HTML-L08}, 39 characters.
     *
     * <p>It ends mid-word, at {@code styl}, and carries <strong>two spaces</strong> after
     * {@code <table} rather than one.
     */
    private static final String TABLE_FIRST_FRAGMENT =
            "<table  align=\"center\" frame=\"box\" styl";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L163}, {@code L165}, {@code L174} and {@code L177} - the single
     * 39-character first fragment shared by all four colspan cells.
     *
     * <p>It ends at the semicolon of {@code padding:0px 5px;}, which is why those four literals have
     * no space before {@code background-color} while the six width cells do.
     */
    private static final String COLSPAN_FIRST_FRAGMENT =
            "<td colspan=\"3\" style=\"padding:0px 5px;";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L184} and {@code L199} - the 50-character first fragment shared by
     * {@code HTML-L47} and {@code HTML-L58}, which differ only in their continuation's colour.
     */
    private static final String WIDTH_FIRST_FRAGMENT_25 =
            "<td style=\"width:25%; padding:0px 5px; background-";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L189} and {@code L202} - the 50-character first fragment shared by
     * {@code HTML-L50} and {@code HTML-L61}.
     */
    private static final String WIDTH_FIRST_FRAGMENT_55 =
            "<td style=\"width:55%; padding:0px 5px; background-";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L194} and {@code L205} - the 50-character first fragment shared by
     * {@code HTML-L53} and {@code HTML-L64}.
     */
    private static final String WIDTH_FIRST_FRAGMENT_20 =
            "<td style=\"width:20%; padding:0px 5px; background-";

    /**
     * The eleven continued literals, each as {@code (constant, source lines, first fragment,
     * continuation)}.
     *
     * <p>The fragments are transcribed from the two source lines exactly as they appear there, so the
     * join in the test is the compiler's own rule and not a restatement of the finished value. That is
     * deliberately redundant with {@link #expectedFixedLiterals()}: the same literal is asserted once
     * against an independently written whole value and once against its two halves, in two different
     * shapes, so a transcription slip has to be made three times to survive.
     *
     * @return one four-part argument set per continued literal
     */
    /**
     * Every {@code FILE STATUS} a sink can report, paired with the {@link FileStatus.Outcome} the
     * writer must surface for it.
     *
     * <p>The four named statuses are the estate's whole shared vocabulary - the codes the batch
     * programs in {@code app/cbl} actually test for - plus the writer's own permanent-error code,
     * which is deliberately outside that vocabulary and therefore lands in the {@code WHEN OTHER}
     * arm.
     *
     * @return one status-and-outcome pair per arm
     */
    static Stream<Arguments> writeStatuses() {
        return Stream.of(
                Arguments.of(FileStatus.OK, FileStatus.Outcome.OK),
                Arguments.of(FileStatus.END_OF_FILE, FileStatus.Outcome.END_OF_FILE),
                Arguments.of(FileStatus.DUPLICATE, FileStatus.Outcome.DUPLICATE),
                Arguments.of(FileStatus.NOT_FOUND, FileStatus.Outcome.NOT_FOUND),
                Arguments.of(StatementHtmlWriter.PERMANENT_ERROR_STATUS,
                        FileStatus.Outcome.OTHER));
    }

    static Stream<Arguments> continuedLiterals() {
        return Stream.of(
                Arguments.of(HtmlFixedLine.HTML_L08, "L157-L158", TABLE_FIRST_FRAGMENT,
                        "e=\"width:70%; font:12px Segoe UI,sans-serif;\">"),
                Arguments.of(HtmlFixedLine.HTML_L10, "L163-L164", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#1d1d96b3;\">"),
                Arguments.of(HtmlFixedLine.HTML_L15, "L165-L166", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#FFAF33;\">"),
                Arguments.of(HtmlFixedLine.HTML_L22_35, "L174-L175", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#f2f2f2;\">"),
                Arguments.of(HtmlFixedLine.HTML_L30_42, "L177-L178", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#33FFD1; text-align:center;\">"),
                Arguments.of(HtmlFixedLine.HTML_L47, "L184-L185", WIDTH_FIRST_FRAGMENT_25,
                        "color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L50, "L189-L190", WIDTH_FIRST_FRAGMENT_55,
                        "color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L53, "L194-L195", WIDTH_FIRST_FRAGMENT_20,
                        "color:#33FF5E; text-align:right;\">"),
                Arguments.of(HtmlFixedLine.HTML_L58, "L199-L200", WIDTH_FIRST_FRAGMENT_25,
                        "color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L61, "L202-L203", WIDTH_FIRST_FRAGMENT_55,
                        "color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L64, "L205-L206", WIDTH_FIRST_FRAGMENT_20,
                        "color:#f2f2f2; text-align:right;\">"));
    }

    /**
     * Right-space pads an expected line to the 100-byte record width, exactly as the COBOL
     * {@code PIC X(100)} receiver does.
     *
     * @param content the expected content
     * @return the content padded to {@link StatementHtmlWriter#RECORD_LENGTH} characters
     */
    private static String padded(final String content) {
        return content
                + String.valueOf(SPACE).repeat(StatementHtmlWriter.RECORD_LENGTH
                        - content.length());
    }

    /**
     * A sink that reports exactly the statuses it was constructed with, so the writer's handling of
     * each can be driven independently. Collects nothing; the tests that need the bytes use a lambda.
     */
    private static final class StatusReportingSink implements HtmlRecordSink {

        /** The status {@link #write(byte[])} reports. */
        private final String writeStatus;

        /** The status {@link #open()} reports. */
        private final String openStatus;

        /** The status {@link #close()} reports. */
        private final String closeStatus;

        /**
         * @param writeStatus what write reports, possibly {@code null}
         * @param openStatus  what open reports, possibly {@code null}
         * @param closeStatus what close reports, possibly {@code null}
         */
        StatusReportingSink(final String writeStatus, final String openStatus,
                            final String closeStatus) {
            this.writeStatus = writeStatus;
            this.openStatus = openStatus;
            this.closeStatus = closeStatus;
        }

        @Override
        public String write(final byte[] record) {
            return this.writeStatus;
        }

        @Override
        public String open() {
            return this.openStatus;
        }

        @Override
        public String close() {
            return this.closeStatus;
        }
    }
}
