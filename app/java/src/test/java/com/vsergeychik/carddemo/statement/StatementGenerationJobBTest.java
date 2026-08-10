package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Operation;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Request;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Response;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Session;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavioural verification of {@link StatementGenerationJobB} against
 * {@code app/cbl/CBSTM03B.CBL}.
 *
 * <p>Every assertion names the COBOL line or the validation gate it stands for, because the value of a
 * parity test is not that it passes but that a reader can tell what it would mean if it failed.
 *
 * <p>No Spring context and no {@code JobLauncher} is involved anywhere in this class (practice B10, gate
 * G51): the subject is constructed directly with a mocked {@link JdbcTemplate}, a hand-built binding
 * catalogue and an explicitly named charset, so every branch is reachable from plain JUnit 5.
 *
 * <h2>Governing standards</h2>
 *
 * <p><strong>No user rules were provided for this project.</strong> The rules document consists of the
 * single line "No user rules provided", and that one line is the whole of it - there is nothing further
 * to read. Its absence is not licence to lower the bar, so this class is held instead to the twelve
 * enterprise best-practice substitutes the specification declares binding, and each is named at the
 * assertion it governs rather than only listed here:
 *
 * <ul>
 *   <li><strong>B1/B2</strong> - only the test libraries the build already declares: JUnit Jupiter,
 *       Mockito and AssertJ. No dependency is added and the build file is not touched. In particular
 *       {@code spring-batch-test} is <em>not</em> declared, so {@code JobLauncherTestUtils} is
 *       unavailable - and irrelevant, because the subject is a {@code @Component} and not a
 *       {@code Job}.</li>
 *   <li><strong>B3</strong> - nothing under {@code app/cbl}, {@code app/cpy}, {@code app/jcl},
 *       {@code app/csd} or {@code app/data} is written, and nothing there is <em>read</em> either: the
 *       fixture bytes this class uses arrive through the test classpath at
 *       {@code /fixtures/*.txt}, never through a relative filesystem walk out of the module (gate
 *       G5).</li>
 *   <li><strong>B4</strong> - this file is the only artefact added. No helper class and no new fixture
 *       resource: the nine derived fixtures are a fixed, shared set, so where no fixture exists - and
 *       none exists for {@code TRNXFILE} - the rows are composed here from the copybook's own offsets
 *       instead of a tenth file being invented.</li>
 *   <li><strong>B5</strong> - {@code 88 M03B-WRITE} and {@code 88 M03B-REWRITE}
 *       ({@code app/cbl/CBSTM03B.CBL:107-108}) are declared and tested nowhere in the program body.
 *       They stay dead: every assertion about them pins the silent no-op, and not one of them expects
 *       a write or a rewrite to happen.</li>
 *   <li><strong>B6</strong> - no credential, password or token appears in any test datum.</li>
 *   <li><strong>B7</strong> - deterministic and non-interactive: no clock, locale, time zone,
 *       randomness, network or test-ordering dependence anywhere (gate G54).</li>
 *   <li><strong>B8</strong> - the code page is always named explicitly and never defaulted; imports are
 *       individually spelled with no wildcard (gate G52); no production dataset name is written in
 *       Java, the four names used here are test names supplied through the binding catalogue exactly as
 *       configuration would supply them (gate G46).</li>
 *   <li><strong>B9</strong> - no static mutable state, in the subject or in this test (gate G53); the
 *       four cursors and four {@code FILE STATUS} areas live on a per-execution session, which is
 *       asserted rather than assumed.</li>
 *   <li><strong>B10</strong> - these tests ship with the implementation, not after it.</li>
 *   <li><strong>B11</strong> - every width and offset is asserted by hand against the copybook and the
 *       {@code FD}. No third-party copybook parser is used to derive an expectation.</li>
 *   <li><strong>B12</strong> - see the provenance note below.</li>
 * </ul>
 *
 * <h2>Provenance of every expectation (practice B12)</h2>
 *
 * <p><strong>No expectation in this class was captured from a running COBOL program, and none claims to
 * be.</strong> Every one is <em>statically derived</em> - read off the cited line of
 * {@code app/cbl/CBSTM03B.CBL}, {@code app/cbl/CBSTM03A.CBL}, the four copybooks
 * ({@code COSTM01.CPY}, {@code CVACT03Y.cpy}, {@code CUSTREC.cpy}, {@code CVACT01Y.cpy}),
 * {@code app/jcl/CREASTMT.JCL} or {@code app/csd/CARDDEMO.CSD}, cross-checked between at least two of
 * them wherever two of them speak to the same fact. The 350-byte record and its 32-byte key, for
 * instance, are asserted because {@code COSTM01.CPY}'s fields sum to 350 <em>and</em>
 * {@code CREASTMT.JCL} defines the cluster {@code KEYS(32 0) RECORDSIZE(350 350)} - two independent
 * statements of one geometry.
 *
 * <p>Executing the legacy programs to capture a baseline is not possible in this environment, and one of
 * the verified blockers is visible in this subroutine's own inputs: {@code app/cpy/CUSTREC.cpy} - the
 * copybook behind {@code CUSTFILE}'s 500-byte record - carries <strong>literal TAB characters in the
 * source margin on lines 6 through 22</strong>, which a COBOL compiler rejects outright rather than
 * treating as blanks, so the {@code CUSTFILE} half of this contract could not be compiled here even if
 * every other blocker were removed. That is why the file's width is asserted from the copybook's field
 * arithmetic and from {@code CBSTM03B.CBL:71-73}'s {@code X(09) + X(491)} split, and never from a
 * captured run.
 *
 * <p>A reader who needs to know what a failure means can therefore always find the answer in the source
 * line the assertion names, which is the whole point of deriving rather than recording.
 */
@DisplayName("StatementGenerationJobB - the CBSTM03B four-file data-access subroutine")
class StatementGenerationJobBTest {

    /** The code page every test states explicitly; never a platform default (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The column name the fake backend describes at the record-image ordinal. */
    private static final String DESCRIBED_COLUMN = "RECORD_IMAGE";

    /** Test dataset names. Deliberately not the production ones, and never read from Java in the subject. */
    private static final String TRNX_DS = "TEST.M2.CARDDEMO.TRXFL.VSAM.KSDS";
    private static final String XREF_DS = "TEST.M2.CARDDEMO.CARDXREF.VSAM.KSDS";
    private static final String CUST_DS = "TEST.M2.CARDDEMO.CUSTDATA.VSAM.KSDS";
    private static final String ACCT_DS = "TEST.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    /** The backends this test has stubbed, one per mocked template. */
    private final Map<JdbcTemplate, Backend> backends = new LinkedHashMap<>();

    // =================================================================================================
    // Fixtures.
    // =================================================================================================

    /**
     * A well-formed binding for one of the four datasets.
     *
     * @param dsname       the dataset name
     * @param recordLength the copybook width
     * @param keyLength    the {@code RECORD KEY} width
     * @return the binding
     */
    private static DatasetBinding ksds(String dsname, int recordLength, int keyLength) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
                "COPYBOOK", keyLength, null, null, null);
    }

    /**
     * The four-entry catalogue, all four bindings well formed.
     *
     * @return the catalogue
     */
    private static DatasetBindings validBindings() {
        return bindings(
                ksds(TRNX_DS, TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH),
                ksds(XREF_DS, CardXrefRecord.RECORD_LENGTH, CardXrefRecord.XREF_CARD_NUM_LENGTH),
                ksds(CUST_DS, Stm03CustomerRecord.RECORD_LENGTH, Stm03CustomerRecord.KEY_LENGTH),
                ksds(ACCT_DS, AccountRecord.RECORD_LENGTH, AccountRecord.ACCT_ID_LENGTH));
    }

    /**
     * A catalogue built from four explicit bindings, so a test can spoil exactly one of them.
     *
     * @param trnx the {@code TRNXFILE} binding
     * @param xref the {@code XREFFILE} binding
     * @param cust the {@code CUSTFILE} binding
     * @param acct the {@code ACCTFILE} binding
     * @return the catalogue
     */
    private static DatasetBindings bindings(DatasetBinding trnx, DatasetBinding xref,
                                            DatasetBinding cust, DatasetBinding acct) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(StatementGenerationJobB.TRNXFILE_DD, trnx);
        catalogue.put(StatementGenerationJobB.XREFFILE_DD, xref);
        catalogue.put(StatementGenerationJobB.CUSTFILE_DD, cust);
        catalogue.put(StatementGenerationJobB.ACCTFILE_DD, acct);
        return catalogue;
    }

    /**
     * The subject over a mocked template.
     *
     * @param jdbcTemplate the mocked template
     * @return the subject
     */
    private static StatementGenerationJobB subroutine(JdbcTemplate jdbcTemplate) {
        return new StatementGenerationJobB(jdbcTemplate, validBindings(), ASCII,
                RecordImageForm.CHARACTER);
    }

    /**
     * The backend behind a mocked template, created on first use.
     *
     * @param jdbcTemplate the mocked template
     * @return its backend
     */
    private Backend backend(JdbcTemplate jdbcTemplate) {
        return backends.computeIfAbsent(jdbcTemplate, Backend::new);
    }

    /**
     * A record image of a declared width whose leading bytes are a key.
     *
     * @param key          the key characters
     * @param recordLength the declared record width
     * @return the image, right-padded with {@code '.'} so padding is visible in a failure message
     */
    private static String row(String key, int recordLength) {
        StringBuilder image = new StringBuilder(recordLength);
        image.append(key);
        while (image.length() < recordLength) {
            image.append('.');
        }
        return image.toString();
    }

    /**
     * The record width of one of the four DDs.
     *
     * @param dd the DD name
     * @return its copybook width
     */
    private static int widthOf(String dd) {
        return switch (dd) {
            case StatementGenerationJobB.TRNXFILE_DD -> StatementGenerationJobB.TRNXFILE_RECORD_LENGTH;
            case StatementGenerationJobB.XREFFILE_DD -> StatementGenerationJobB.XREFFILE_RECORD_LENGTH;
            case StatementGenerationJobB.CUSTFILE_DD -> StatementGenerationJobB.CUSTFILE_RECORD_LENGTH;
            default -> StatementGenerationJobB.ACCTFILE_RECORD_LENGTH;
        };
    }

    /**
     * The dataset name of one of the four DDs.
     *
     * @param dd the DD name
     * @return its test dataset name
     */
    private static String datasetOf(String dd) {
        return switch (dd) {
            case StatementGenerationJobB.TRNXFILE_DD -> TRNX_DS;
            case StatementGenerationJobB.XREFFILE_DD -> XREF_DS;
            case StatementGenerationJobB.CUSTFILE_DD -> CUST_DS;
            default -> ACCT_DS;
        };
    }

    /** The two DDs whose {@code ACCESS MODE IS SEQUENTIAL}. */
    private static List<String> sequentialDds() {
        return List.of(StatementGenerationJobB.TRNXFILE_DD, StatementGenerationJobB.XREFFILE_DD);
    }

    /** The two DDs whose {@code ACCESS MODE IS RANDOM}. */
    private static List<String> randomDds() {
        return List.of(StatementGenerationJobB.CUSTFILE_DD, StatementGenerationJobB.ACCTFILE_DD);
    }

    /** All four DD names. */
    private static List<String> allDds() {
        return StatementGenerationJobB.DD_NAMES;
    }

    /** The caller's key width for a random DD. */
    private static int callerKeyOf(String dd) {
        return StatementGenerationJobB.CUSTFILE_DD.equals(dd)
                ? StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH
                : StatementGenerationJobB.ACCTFILE_CALLER_KEY_LENGTH;
    }

    /** A digit key of the caller's width for a random DD, so the numeric receiver accepts it. */
    private static String digitKeyOf(String dd) {
        return "1".repeat(callerKeyOf(dd));
    }

    /**
     * Which DDs' paragraphs actually contain an {@code IF} for a given condition name.
     *
     * <p>Read straight off {@code app/cbl/CBSTM03B.CBL:133-229}: {@code IF M03B-OPEN} and
     * {@code IF M03B-CLOSE} appear in all four paragraphs; {@code IF M03B-READ} appears only in the two
     * whose {@code SELECT} says {@code ACCESS MODE IS SEQUENTIAL} (:141, :165); {@code IF M03B-READ-K}
     * only in the two that say {@code RANDOM} (:188, :213); and <strong>{@code IF M03B-WRITE} and
     * {@code IF M03B-REWRITE} appear nowhere at all</strong> - the two condition names are declared at
     * :107-108 and never tested, which is the fact practice B5 requires be preserved.
     *
     * @param operation the condition name
     * @return the DDs whose paragraph tests it; empty for the two dead ones
     */
    private static List<String> ddsTesting(Operation operation) {
        return switch (operation) {
            case OPEN, CLOSE -> allDds();
            case READ -> sequentialDds();
            case READ_K -> randomDds();
            case WRITE, REWRITE -> List.of();
        };
    }

    // =================================================================================================
    // Derived fixture rows, and the TRNXFILE rows there is no fixture for.
    //
    // Practice B3: every byte below arrives either through the TEST CLASSPATH at /fixtures/*.txt or from
    // the copybook's own offsets. Nothing walks out of the module to app/data/ASCII, and nothing under
    // app/ is opened, so this class cannot make the parity oracle depend on the runner's filesystem.
    // =================================================================================================

    /** The derived CARDXREF fixture on the test classpath - the XREFFILE record's real data. */
    private static final String CARDXREF_FIXTURE = "/fixtures/cardxref.txt";

    /** The derived ACCTDATA fixture on the test classpath - the ACCTFILE record's real data. */
    private static final String ACCTDATA_FIXTURE = "/fixtures/acctdata.txt";

    /** The derived CUSTDATA fixture on the test classpath - the CUSTFILE record's real data. */
    private static final String CUSTDATA_FIXTURE = "/fixtures/custdata.txt";

    /**
     * How wide a stored {@code cardxref} row actually is - <strong>36</strong> bytes, where
     * {@code app/cpy/CVACT03Y.cpy} declares 50.
     *
     * <p>Risk R-F: the fixture omits the copybook's trailing {@code FILLER X(14)} entirely, so a row has
     * to be widened to its declared width before it can stand in for a VSAM record. The widening belongs
     * to the shared codec's normaliser - and to the parity harness that uses it - and emphatically
     * <em>not</em> to {@link CardXrefRecord}, whose job is to describe the 50-byte record the copybook
     * declares rather than to accommodate a short fixture (gate G16).
     */
    private static final int CARDXREF_STORED_ROW_WIDTH = 36;

    /** A codec at the code page this class states explicitly; the only widener and mover used here. */
    private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

    /**
     * Every row of a derived fixture, exactly as stored and deliberately un-widened.
     *
     * <p>A fresh list per call, so no state is shared between tests and no test depends on another
     * having run (practice B9, gate G53).
     *
     * @param resource the classpath resource path
     * @return the rows, in file order, with any line terminator removed
     */
    private static List<String> fixtureRows(String resource) {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = StatementGenerationJobBTest.class.getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("the derived fixture must be on the test classpath at %s; it is copied from "
                            + "app/data/ASCII into app/java/src/test/resources/fixtures, which is the "
                            + "only way this test may reach fixture bytes (practice B3)", resource)
                    .isNotNull();
            String content = new String(stream.readAllBytes(), ASCII);
            for (String line : content.split("\n", -1)) {
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the derived fixture " + resource, failure);
        }
        return rows;
    }

    /**
     * One row of the {@code cardxref} fixture, widened from its stored 36 bytes to the 50 the copybook
     * declares by right-padding with spaces - which is exactly what the omitted {@code FILLER X(14)}
     * holds, since {@code CVACT03Y} gives it no {@code VALUE}.
     *
     * @param oneBasedRow which row to take, 1-based as the fixture is read
     * @return a 50-character record image
     */
    private String widenedCardXrefRow(int oneBasedRow) {
        String stored = fixtureRows(CARDXREF_FIXTURE).get(oneBasedRow - 1);
        assertThat(stored).as("stored cardxref rows are %s bytes wide - risk R-F",
                CARDXREF_STORED_ROW_WIDTH).hasSize(CARDXREF_STORED_ROW_WIDTH);
        return codec.padToDeclaredWidth(stored, CardXrefRecord.RECORD_LENGTH);
    }

    /**
     * A {@code TRNXFILE} record image composed from {@code app/cpy/COSTM01.CPY}'s own offsets.
     *
     * <p>There is <strong>no</strong> {@code TRNX} fixture to draw on, and inventing one would add a
     * tenth file to a fixed nine-file set (practice B4). The reason there is none is structural rather
     * than an oversight: the {@code TRXFL} cluster does not exist until
     * {@code app/jcl/CREASTMT.JCL} builds it at run time - STEP010 sorts the {@code TRANSACT} dataset
     * into a sequential file and STEP020 {@code REPRO}s that into the cluster STEP005 defined with
     * {@code KEYS(32 0) RECORDSIZE(350 350)}. So the row is built here, field by field, at the offsets
     * the copybook declares: {@code TRNX-CARD-NUM X(16)}@0, {@code TRNX-ID X(16)}@16,
     * {@code TRNX-TYPE-CD X(02)}@32, {@code TRNX-CAT-CD 9(04)}@34, {@code TRNX-SOURCE X(10)}@38,
     * {@code TRNX-DESC X(100)}@48, {@code TRNX-AMT S9(09)V99}@148 (11 bytes),
     * {@code TRNX-MERCHANT-ID 9(09)}@159, merchant name/city/zip @168/@218/@268,
     * {@code TRNX-ORIG-TS X(26)}@278, {@code TRNX-PROC-TS X(26)}@304 and {@code FILLER X(20)}@330,
     * summing to 350.
     *
     * <p>The amount is stored through {@link CobolDecimal}, so the only rounding mode that can reach the
     * record is {@link CobolDecimal#COBOL_ROUNDING} - {@code RoundingMode.DOWN} - which is the sole
     * faithful choice because {@code ROUNDED} appears nowhere in the legacy source (gate G24).
     *
     * @param cardNumber the 16-byte card number, which is the leading half of the composite key
     * @param transactionId the 16-byte transaction id, which is the trailing half
     * @param amount the transaction amount, stored at the declared scale by truncation
     * @return a 350-character record image
     */
    private String trnxRow(String cardNumber, String transactionId, BigDecimal amount) {
        TrnxRecord record = TrnxRecord.newRecord(ASCII);
        record.writeTrnxCardNum(cardNumber);
        record.writeTrnxId(transactionId);
        record.writeTrnxTypeCd("01");
        record.writeTrnxCatCd(5001);
        record.writeTrnxSource("POS TERM  ");
        record.writeTrnxDesc("STATEMENT LINE, COMPOSED FROM COSTM01.CPY OFFSETS");
        record.writeTrnxAmt(CobolDecimal.storeMonetary(amount));
        record.writeTrnxMerchantId(123456789);
        record.writeTrnxMerchantName("MERCHANT NAME");
        record.writeTrnxMerchantCity("MERCHANT CITY");
        record.writeTrnxMerchantZip("12345-6789");
        record.writeTrnxOrigTs("2022-07-18 12:00:00.000000");
        record.writeTrnxProcTs("2022-07-19 12:00:00.000000");
        String image = record.recordImage();
        assertThat(image).as("COSTM01.CPY's fields sum to %s bytes, which CREASTMT.JCL:29-32 states "
                + "independently as RECORDSIZE(350 350)", TrnxRecord.RECORD_LENGTH)
                .hasSize(TrnxRecord.RECORD_LENGTH);
        return image;
    }

    // =================================================================================================
    // Gate G12 - a @Component, never a Spring Batch Job.
    // =================================================================================================

    @Nested
    @DisplayName("Gate G12 - it is a Spring @Component and not a Spring Batch Job")
    class GateG12 {

        @Test
        @DisplayName("carries @Component, the stereotype CBSTM03B.CBL:7's BATCH COBOL Subroutine deserves")
        void carriesComponentStereotype() {
            assertThat(StatementGenerationJobB.class
                    .isAnnotationPresent(org.springframework.stereotype.Component.class)).isTrue();
        }

        @Test
        @DisplayName("carries no Spring Batch or Spring configuration stereotype of any kind")
        void carriesNoBatchStereotype() {
            List<String> forbidden = List.of(
                    "org.springframework.context.annotation.Configuration",
                    "org.springframework.boot.autoconfigure.batch.BatchProperties",
                    "org.springframework.batch.core.configuration.annotation.EnableBatchProcessing");
            List<String> present = new ArrayList<>();
            for (java.lang.annotation.Annotation annotation
                    : StatementGenerationJobB.class.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (forbidden.contains(name) || name.startsWith("org.springframework.batch")) {
                    present.add(name);
                }
            }
            assertThat(present).as("gate G12: CBSTM03B has no EXEC PGM= in app/jcl, no CSD PROGRAM "
                    + "definition, and 13 CALL sites in CBSTM03A - it is a subroutine").isEmpty();
        }

        @Test
        @DisplayName("implements no Spring Batch type and extends nothing from that package")
        void implementsNoBatchType() {
            List<String> batchTypes = new ArrayList<>();
            for (Class<?> implemented : StatementGenerationJobB.class.getInterfaces()) {
                if (implemented.getName().startsWith("org.springframework.batch")) {
                    batchTypes.add(implemented.getName());
                }
            }
            assertThat(batchTypes).isEmpty();
            assertThat(StatementGenerationJobB.class.getSuperclass()).isEqualTo(Object.class);
        }

        @Test
        @DisplayName("no method anywhere on it returns a Spring Batch Job, Step or JobExecution")
        void noMethodReturnsABatchType() {
            // Stronger than "declares no @Bean": a Job-shaped return value would let the class be
            // counted among the ten Job beans by anyone assembling them, however it was annotated. The
            // nested types are walked too, because Session and the records are part of its API surface.
            List<String> offenders = new ArrayList<>();
            List<Class<?>> surface = new ArrayList<>();
            surface.add(StatementGenerationJobB.class);
            surface.addAll(Arrays.asList(StatementGenerationJobB.class.getDeclaredClasses()));
            for (Class<?> type : surface) {
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    String returned = method.getReturnType().getName();
                    if (returned.startsWith("org.springframework.batch")) {
                        offenders.add(type.getSimpleName() + "." + method.getName() + " -> " + returned);
                    }
                }
            }
            assertThat(offenders).as("gate G12 and implicit requirement I3: CBSTM03B.CBL:7 declares a "
                    + "BATCH COBOL Subroutine, there is no EXEC PGM=CBSTM03B in app/jcl, app/proc or "
                    + "app/csd, and CBSTM03A calls it at thirteen sites - so nothing here may present "
                    + "itself as a launchable Job").isEmpty();
        }

        @Test
        @DisplayName("declares no bean factory method, so it can produce neither a Job nor a Step")
        void declaresNoBeanMethod() {
            List<String> beanMethods = new ArrayList<>();
            for (java.lang.reflect.Method method : StatementGenerationJobB.class.getDeclaredMethods()) {
                for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName()
                            .equals("org.springframework.context.annotation.Bean")) {
                        beanMethods.add(method.getName());
                    }
                }
            }
            assertThat(beanMethods).isEmpty();
        }
    }

    // =================================================================================================
    // Gate G53 / practice B9 - no static mutable state, and sessions are independent.
    // =================================================================================================

    @Nested
    @DisplayName("Practice B9 and gate G53 - all mutable state lives on the session")
    class StatelessComponent {

        @Test
        @DisplayName("declares no non-final static field, in the class or in any nested type")
        void declaresNoStaticMutableField() {
            List<String> offenders = new ArrayList<>();
            List<Class<?>> types = new ArrayList<>();
            types.add(StatementGenerationJobB.class);
            types.addAll(Arrays.asList(StatementGenerationJobB.class.getDeclaredClasses()));
            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())
                            && !field.isSynthetic()) {
                        offenders.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }
            assertThat(offenders).as("gate G53").isEmpty();
        }

        @Test
        @DisplayName("holds no cursor and no FILE STATUS field of its own; every instance field is final")
        void holdsNoCursorOrStatusField() {
            List<String> offenders = new ArrayList<>();
            for (Field field : StatementGenerationJobB.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())
                        && !field.isSynthetic()) {
                    offenders.add(field.getName());
                }
            }
            assertThat(offenders).as("the component is stateless; CBSTM03B's four cursors and four "
                    + "FILE STATUS areas live on the Session").isEmpty();
        }

        @Test
        @DisplayName("two sessions browse to independent positions and neither observes the other")
        void sessionsAreIndependent() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            backend(template).storing(TRNX_DS, List.of(
                    row("A", widthOf(dd)), row("B", widthOf(dd)), row("C", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);

            Session first = subject.newSession();
            Session second = subject.newSession();
            subject.open(first, dd);
            subject.open(second, dd);

            // Advance the first session twice and the second once.
            subject.readNext(first, dd);
            subject.readNext(first, dd);
            Response secondRead = subject.readNext(second, dd);

            assertThat(first.position(dd)).isEqualTo(2);
            assertThat(second.position(dd)).isEqualTo(1);
            assertThat(secondRead.recordImage(widthOf(dd))).startsWith("A");
            assertThat(subject.readNext(first, dd).recordImage(widthOf(dd))).startsWith("C");
            assertThat(subject.readNext(second, dd).recordImage(widthOf(dd))).startsWith("B");
        }

        @Test
        @DisplayName("a closed session refuses every operation, because no COBOL path could produce one")
        void closedSessionRefusesOperations() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            assertThat(session.isClosed()).isFalse();
            session.close();
            assertThat(session.isClosed()).isTrue();
            // Idempotent.
            assertThatNoException().isThrownBy(session::close);

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> subject.open(session, StatementGenerationJobB.TRNXFILE_DD));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> subject.trnxFileProc(session,
                            Request.read(StatementGenerationJobB.TRNXFILE_DD)));
        }

        @Test
        @DisplayName("a session holds a cursor for exactly the four DD names and refuses any other")
        void sessionHoldsExactlyFourCursors() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            for (String dd : allDds()) {
                assertThat(session.status(dd)).isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
                assertThat(session.isOpen(dd)).isFalse();
                assertThat(session.position(dd)).isZero();
                assertThat(session.isExhausted(dd)).isFalse();
            }
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> session.status("NOSUCHDD"))
                    .withMessageContaining("WHEN OTHER");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> session.isOpen(null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> session.position("READTRNX"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> session.isExhausted("NOSUCHDD"));
        }

        @Test
        @DisplayName("closing a session forgets positions but does not manufacture a CLOSE status")
        void closingASessionAssignsNoStatus() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.XREFFILE_DD;
            backend(template).storing(XREF_DS, List.of(row("A", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            subject.readNext(session, dd);
            assertThat(session.status(dd)).isEqualTo(FileStatus.OK);

            session.close();
            // The last status observed survives; a run unit ending is not a CLOSE statement.
            assertThat(session.status(dd)).isEqualTo(FileStatus.OK);
            assertThat(session.isOpen(dd)).isFalse();
            assertThat(session.position(dd)).isEqualTo(1);
        }
    }

    // =================================================================================================
    // The 1040-byte linkage contract.
    // =================================================================================================

    @Nested
    @DisplayName("The 1040-byte LK-M03B-AREA contract - CBSTM03B.CBL:99-112")
    class LinkageArea {

        @Test
        @DisplayName("AREA_LENGTH is 1040 and the six field widths sum to it")
        void areaLengthIs1040() {
            assertThat(StatementGenerationJobB.AREA_LENGTH).isEqualTo(1040);
            assertThat(StatementGenerationJobB.DD_LENGTH
                    + StatementGenerationJobB.OPER_LENGTH
                    + StatementGenerationJobB.RC_LENGTH
                    + StatementGenerationJobB.KEY_LENGTH
                    + StatementGenerationJobB.KEY_LN_LENGTH
                    + StatementGenerationJobB.FLDT_LENGTH).isEqualTo(1040);
        }

        @Test
        @DisplayName("the offsets are 0/8/9/11/36/40 and the widths 8/1/2/25/4/1000")
        void offsetsAndWidths() {
            assertThat(StatementGenerationJobB.DD_OFFSET).isZero();
            assertThat(StatementGenerationJobB.OPER_OFFSET).isEqualTo(8);
            assertThat(StatementGenerationJobB.RC_OFFSET).isEqualTo(9);
            assertThat(StatementGenerationJobB.KEY_OFFSET).isEqualTo(11);
            assertThat(StatementGenerationJobB.KEY_LN_OFFSET).isEqualTo(36);
            assertThat(StatementGenerationJobB.FLDT_OFFSET).isEqualTo(40);

            assertThat(StatementGenerationJobB.DD_LENGTH).isEqualTo(8);
            assertThat(StatementGenerationJobB.OPER_LENGTH).isEqualTo(1);
            assertThat(StatementGenerationJobB.RC_LENGTH).isEqualTo(2);
            assertThat(StatementGenerationJobB.KEY_LENGTH).isEqualTo(25);
            assertThat(StatementGenerationJobB.KEY_LN_LENGTH).isEqualTo(4);
            assertThat(StatementGenerationJobB.FLDT_LENGTH).isEqualTo(1000);
        }

        @Test
        @DisplayName("AREA_LAYOUT declares the six COBOL field names at exactly those offsets and widths")
        void layoutMatchesTheCopybook() {
            List<FixedWidthRecord.FieldSpan> spans = StatementGenerationJobB.AREA_LAYOUT.spans();
            assertThat(StatementGenerationJobB.AREA_LAYOUT.recordLength()).isEqualTo(1040);
            assertThat(spans).hasSize(6);
            assertThat(spans.stream().map(FixedWidthRecord.FieldSpan::name).toList()).containsExactly(
                    "LK-M03B-DD", "LK-M03B-OPER", "LK-M03B-RC", "LK-M03B-KEY", "LK-M03B-KEY-LN",
                    "LK-M03B-FLDT");
            assertThat(spans.stream().map(FixedWidthRecord.FieldSpan::offset).toList())
                    .containsExactly(0, 8, 9, 11, 36, 40);
            assertThat(spans.stream().map(FixedWidthRecord.FieldSpan::length).toList())
                    .containsExactly(8, 1, 2, 25, 4, 1000);
        }

        @Test
        @DisplayName("LK-M03B-KEY-LN is a zoned S9(4) DISPLAY field, four bytes, not a COMP halfword")
        void keyLengthIsZonedDisplay() {
            FixedWidthRecord.FieldSpan span = StatementGenerationJobB.AREA_LAYOUT.spans().get(4);
            assertThat(span.name()).isEqualTo("LK-M03B-KEY-LN");
            assertThat(span.length()).isEqualTo(4);
            assertThat(span.kind()).isEqualTo(FixedWidthRecord.PictureKind.SIGNED_SCALED);
        }

        @Test
        @DisplayName("a request round-trips through the 1040-byte area image unchanged")
        void areaImageRoundTrips() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Request original = Request.readKeyed(StatementGenerationJobB.CUSTFILE_DD,
                    "123456789", StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH);

            byte[] area = subject.toAreaImage(original, null);
            assertThat(area).hasSize(1040);
            assertThat(subject.fromAreaImage(area)).isEqualTo(original);
        }

        @Test
        @DisplayName("the area image places each field at its declared offset, key length zoned-overpunched")
        void areaImagePlacesFieldsByOffset() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Request request = Request.readKeyed(StatementGenerationJobB.ACCTFILE_DD,
                    "12345678901", StatementGenerationJobB.ACCTFILE_CALLER_KEY_LENGTH);
            Response response = new Response(FileStatus.OK, StatementGenerationJobB.SPACES_FLDT);

            String image = new String(subject.toAreaImage(request, response), ASCII);
            assertThat(image.substring(0, 8)).isEqualTo("ACCTFILE");
            assertThat(image.substring(8, 9)).isEqualTo("K");
            assertThat(image.substring(9, 11)).isEqualTo("00");
            assertThat(image.substring(11, 36)).isEqualTo("12345678901              ");
            // PIC S9(4) DISPLAY, value 11: digits "0011" with the low-order digit overpunched positive.
            assertThat(image.substring(36, 40)).isEqualTo("001A");
            assertThat(image.substring(40)).isEqualTo(StatementGenerationJobB.SPACES_FLDT);
        }

        @Test
        @DisplayName("the response half supplies rc and fldt; a null response renders the inbound area")
        void areaImageTakesStatusFromWhicheverHalfIsGiven() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Request request = Request.read(StatementGenerationJobB.TRNXFILE_DD);
            Response response = new Response(FileStatus.END_OF_FILE,
                    StatementGenerationJobB.SPACES_FLDT);

            assertThat(new String(subject.toAreaImage(request, response), ASCII).substring(9, 11))
                    .isEqualTo(FileStatus.END_OF_FILE);
            assertThat(new String(subject.toAreaImage(request, null), ASCII).substring(9, 11))
                    .isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("an unrecognised operation byte renders as a space and parses back as null")
        void unrecognisedOperationByteRoundTrips() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Request request = Request.unrecognisedOperation(StatementGenerationJobB.TRNXFILE_DD);

            byte[] area = subject.toAreaImage(request, null);
            assertThat(new String(area, ASCII).substring(8, 9))
                    .isEqualTo(StatementGenerationJobB.UNRECOGNISED_OPER_IMAGE);
            assertThat(subject.fromAreaImage(area).oper()).isNull();
        }

        @Test
        @DisplayName("a mis-sized area image is rejected rather than read at the wrong offsets")
        void misSizedAreaIsRejected() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.fromAreaImage(new byte[1039]));
        }

        @Test
        @DisplayName("SPACES_FLDT is 1000 spaces - what MOVE SPACES TO WS-M03B-FLDT establishes")
        void spacesFldtIsAThousandSpaces() {
            assertThat(StatementGenerationJobB.SPACES_FLDT).hasSize(1000).isBlank();
        }
    }

    // =================================================================================================
    // The Request and Response value types.
    // =================================================================================================

    @Nested
    @DisplayName("Request and Response - the typed projection of the shared area")
    class ValueTypes {

        @Test
        @DisplayName("dd and key are fitted to their PIC X widths: padded right, truncated right")
        void picXFieldsAreFitted() {
            Request padded = Request.open("AB");
            assertThat(padded.dd()).isEqualTo("AB      ");
            assertThat(padded.key()).hasSize(25);

            Request truncated = new Request("TOOLONGNAME", Operation.OPEN, FileStatus.OK,
                    "K".repeat(30), 1, StatementGenerationJobB.SPACES_FLDT);
            assertThat(truncated.dd()).isEqualTo("TOOLONGN");
            assertThat(truncated.key()).isEqualTo("K".repeat(25));
        }

        @Test
        @DisplayName("a status or record area of the wrong width is rejected, never padded into place")
        void wrongWidthsAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new Request(
                    "TRNXFILE", Operation.OPEN, "0", Request.blankKey(), 0,
                    StatementGenerationJobB.SPACES_FLDT));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new Request(
                    "TRNXFILE", Operation.OPEN, FileStatus.OK, Request.blankKey(), 0, "short"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Response("000", StatementGenerationJobB.SPACES_FLDT));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Response(FileStatus.OK, "short"));
        }

        @Test
        @DisplayName("every required field is non-null")
        void nullFieldsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new Request(
                    null, Operation.OPEN, FileStatus.OK, Request.blankKey(), 0,
                    StatementGenerationJobB.SPACES_FLDT));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new Request(
                    "TRNXFILE", Operation.OPEN, null, Request.blankKey(), 0,
                    StatementGenerationJobB.SPACES_FLDT));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new Request(
                    "TRNXFILE", Operation.OPEN, FileStatus.OK, null, 0,
                    StatementGenerationJobB.SPACES_FLDT));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new Request(
                    "TRNXFILE", Operation.OPEN, FileStatus.OK, Request.blankKey(), 0, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Response(null, StatementGenerationJobB.SPACES_FLDT));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Response(FileStatus.OK, null));
        }

        @Test
        @DisplayName("usedKey() is COBOL (1:n) - the FIRST n bytes, 1-based becoming substring(0, n)")
        void usedKeyIsTheLeadingPrefix() {
            // 25 bytes: nine significant, then deliberate garbage the length must exclude.
            String key = "123456789" + "ZZZZZZZZZZZZZZZZ";
            assertThat(key).hasSize(25);
            Request request = Request.readKeyed(StatementGenerationJobB.CUSTFILE_DD, key, 9);
            assertThat(request.usedKey()).isEqualTo("123456789");

            Request eleven = Request.readKeyed(StatementGenerationJobB.ACCTFILE_DD,
                    "12345678901" + "ZZZZZZZZZZZZZZ", 11);
            assertThat(eleven.usedKey()).isEqualTo("12345678901");
        }

        @ParameterizedTest(name = "key length {0} is refused rather than given invented behaviour")
        @ValueSource(ints = {-1, 0, 26, 1000})
        @DisplayName("a key length of 0 or one past 25 is refused - no COBOL behaviour exists to copy")
        void nonPositiveOrOverlongKeyLengthIsRefused(int keyLength) {
            // Why refusal is the faithful answer, derived rather than invented:
            //
            // 1. app/cbl/CBSTM03B.CBL:189 and :214 are MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN). Reference
            //    modification is 1-based and its length must be at least 1 and must not run past the
            //    25-byte field, so (1:0) and (1:26) address nothing a COBOL implementation defines. A
            //    compiler diagnoses it only when range checking is switched on; otherwise the result is
            //    unpredictable. There is therefore NO observable legacy behaviour to reproduce here.
            //
            // 2. The caller never issues such a call. app/cbl/CBSTM03A.CBL:373 and :397 do set the field
            //    to zero, but the very next statement is COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID
            //    (:374) or LENGTH OF XREF-ACCT-ID (:398), so the zero is never live when CALL 'CBSTM03B'
            //    executes - it is a two-statement initialise-then-assign idiom, not a value in flight.
            //
            // 3. So the choice is between refusing the impossible request and manufacturing a FILE STATUS
            //    for it. Manufacturing one would be worse than useless: the caller's guard chains at
            //    :736 and :353-359 would then interpret an invented status as though the file had
            //    spoken, and a caller defect would surface as a data condition. Refusing keeps the defect
            //    where it belongs, which is why this is an IllegalArgumentException and not an rc.
            Request request = new Request(StatementGenerationJobB.CUSTFILE_DD, Operation.READ_K,
                    FileStatus.OK, Request.blankKey(), keyLength,
                    StatementGenerationJobB.SPACES_FLDT);
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(request::usedKey);

            // And the zero the caller momentarily holds is representable in the area itself - it is only
            // using it as a slice length that is refused. The request survives construction; nothing
            // pre-emptively rejects the intermediate state CBSTM03A:373 genuinely passes through.
            assertThat(request.keyLength()).isEqualTo(keyLength);
        }

        @Test
        @DisplayName("the four factories spell the four calls CBSTM03A actually makes")
        void factoriesMatchTheCallSites() {
            assertThat(Request.open("TRNXFILE").oper()).isEqualTo(Operation.OPEN);
            assertThat(Request.read("TRNXFILE").oper()).isEqualTo(Operation.READ);
            assertThat(Request.readKeyed("CUSTFILE", "1", 1).oper()).isEqualTo(Operation.READ_K);
            assertThat(Request.close("TRNXFILE").oper()).isEqualTo(Operation.CLOSE);
            assertThat(Request.unrecognisedOperation("TRNXFILE").oper()).isNull();
            // Every call site does MOVE ZERO TO WS-M03B-RC and MOVE SPACES TO WS-M03B-FLDT first.
            assertThat(Request.read("TRNXFILE").rc()).isEqualTo(FileStatus.OK);
            assertThat(Request.read("TRNXFILE").fldt()).isEqualTo(StatementGenerationJobB.SPACES_FLDT);
            assertThat(Request.blankKey()).hasSize(25).isBlank();
        }

        @Test
        @DisplayName("Response classifies its status and exposes the caller's group MOVE")
        void responseClassifiesAndProjects() {
            String area = row("HELLO", 1000);
            assertThat(new Response(FileStatus.OK, area).ok()).isTrue();
            assertThat(new Response(FileStatus.OK, area).outcome()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(new Response(FileStatus.END_OF_FILE, area).endOfFile()).isTrue();
            assertThat(new Response(FileStatus.END_OF_FILE, area).ok()).isFalse();
            assertThat(new Response(FileStatus.NOT_FOUND, area).outcome())
                    .isEqualTo(FileStatus.Outcome.NOT_FOUND);
            assertThat(new Response(StatementGenerationJobB.NOT_OPEN_FOR_READ_STATUS, area).outcome())
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(new Response(FileStatus.OK, area).recordImage(5)).isEqualTo("HELLO");
        }

        @ParameterizedTest(name = "recordImage({0}) is refused")
        @ValueSource(ints = {0, -1, 1001})
        void recordImageRejectsAnImpossibleWidth(int width) {
            Response response = new Response(FileStatus.OK, StatementGenerationJobB.SPACES_FLDT);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.recordImage(width));
        }
    }

    // =================================================================================================
    // Practice B5 - six operation codes, two of them dead and staying dead.
    // =================================================================================================

    @Nested
    @DisplayName("Practice B5 - all six 88-levels declared, WRITE and REWRITE dead and preserved")
    class OperationCodes {

        @Test
        @DisplayName("exactly six operations, with the literals of CBSTM03B.CBL:103-108")
        void sixCodes() {
            assertThat(Operation.values()).hasSize(6);
            assertThat(Operation.OPEN.code()).isEqualTo('O');
            assertThat(Operation.CLOSE.code()).isEqualTo('C');
            assertThat(Operation.READ.code()).isEqualTo('R');
            assertThat(Operation.READ_K.code()).isEqualTo('K');
            assertThat(Operation.WRITE.code()).isEqualTo('W');
            assertThat(Operation.REWRITE.code()).isEqualTo('Z');
        }

        @Test
        @DisplayName("exactly WRITE and REWRITE report themselves declared-but-unused in the source")
        void deadCodesAreIdentifiable() {
            assertThat(Arrays.stream(Operation.values())
                    .filter(Operation::declaredButUnusedInSource).toList())
                    .containsExactly(Operation.WRITE, Operation.REWRITE);
        }

        @ParameterizedTest(name = "no DD honours {0}")
        @EnumSource(value = Operation.class, names = {"WRITE", "REWRITE"})
        void noDdHonoursADeadCode(Operation dead) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            for (String dd : allDds()) {
                assertThat(subject.supportedOperations(dd)).doesNotContain(dead);
            }
        }

        @ParameterizedTest(name = "ofCode({0}) resolves")
        @EnumSource(Operation.class)
        void ofCodeResolvesEveryDeclaredCode(Operation operation) {
            assertThat(Operation.ofCode(operation.code())).contains(operation);
            assertThat(operation.image()).hasSize(1)
                    .isEqualTo(String.valueOf(operation.code()));
        }

        @ParameterizedTest(name = "ofCode('{0}') is empty - a fall-through, not an error")
        @ValueSource(chars = {' ', 'A', 'x', '0', '?'})
        void ofCodeIsEmptyForAnUnknownByte(char code) {
            assertThat(Operation.ofCode(code)).isEmpty();
        }
    }

    // =================================================================================================
    // Gate G48 - the five dispatch paths.
    // =================================================================================================

    @Nested
    @DisplayName("Gate G48 - all four DD arms plus WHEN OTHER, in CBSTM03B.CBL:118-126 order")
    class GateG48Dispatch {

        @Test
        @DisplayName("DD_NAMES is the EVALUATE arm order: TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE (G30)")
        void evaluateArmOrderIsPreserved() {
            assertThat(StatementGenerationJobB.DD_NAMES).containsExactly(
                    "TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE");
            assertThat(StatementGenerationJobB.DD_NAMES)
                    .allSatisfy(dd -> assertThat(dd).hasSize(StatementGenerationJobB.DD_LENGTH));
        }

        @ParameterizedTest(name = "{0} dispatches to its own paragraph and reports a real status")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void everyRecognisedDdDispatches(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            assertThat(subject.recognises(dd)).isTrue();
            Response opened = subject.open(session, dd);
            assertThat(opened.rc()).isEqualTo(FileStatus.OK);
            assertThat(session.isOpen(dd)).isTrue();
        }

        @ParameterizedTest(name = "unknown DD ''{0}'' touches nothing and leaves the status untouched")
        @ValueSource(strings = {"NOSUCHDD", "READTRNX", "        ", "TRNXFIL", "trnxfile"})
        void whenOtherIsANoOp(String unknown) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            // Establish a distinctive inbound status so "unchanged" is observable.
            Request request = new Request(unknown, Operation.READ, FileStatus.END_OF_FILE,
                    Request.blankKey(), 0, StatementGenerationJobB.SPACES_FLDT);
            Response response = subject.call(session, request);

            assertThat(response.rc()).as("WHEN OTHER -> GO TO 9999-GOBACK assigns nothing")
                    .isEqualTo(FileStatus.END_OF_FILE);
            assertThat(response.fldt()).isEqualTo(StatementGenerationJobB.SPACES_FLDT);
            assertThat(backend.statementsSent()).as("no I/O at all").isEmpty();
            for (String dd : allDds()) {
                assertThat(session.status(dd)).isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
                assertThat(session.isOpen(dd)).isFalse();
            }
        }

        @Test
        @DisplayName("recognises() answers false for an unknown name and for null")
        void recognisesRejectsUnknownNames() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            assertThat(subject.recognises("NOSUCHDD")).isFalse();
            assertThat(subject.recognises(null)).isFalse();
        }

        @Test
        @DisplayName("the accessors require a known DD and say how to reach WHEN OTHER instead")
        void accessorsRequireAKnownDd() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.datasetName("NOSUCHDD"))
                    .withMessageContaining("WHEN OTHER");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.recordLength(null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.supportedOperations("NOSUCHDD"));
        }

        @Test
        @DisplayName("a paragraph method operates on its own file whatever LK-M03B-DD happens to carry")
        void aParagraphOperatesOnItsOwnFile() {
            // 1000-TRNXFILE-PROC opens TRNX-FILE; by the time control reaches it, EVALUATE LK-M03B-DD has
            // already decided, and the paragraph names its file directly. Calling it with a mismatched DD
            // name therefore still operates on TRNXFILE - it does not re-dispatch and it does not refuse.
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            Response response = subject.trnxFileProc(session, Request.open("XREFFILE"));
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(session.isOpen(StatementGenerationJobB.TRNXFILE_DD)).isTrue();
            assertThat(session.isOpen(StatementGenerationJobB.XREFFILE_DD)).isFalse();
        }

        @Test
        @DisplayName("call() requires both arguments")
        void callRequiresItsArguments() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.call(null, Request.open("TRNXFILE")));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.call(session, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.trnxFileProc(null, Request.open("TRNXFILE")));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.trnxFileProc(session, null));
        }

        @Test
        @DisplayName("the four per-DD paragraph methods are reachable directly, without the dispatcher")
        void perDdParagraphsAreDirectlyCallable() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            assertThat(subject.trnxFileProc(session, Request.open("TRNXFILE")).rc())
                    .isEqualTo(FileStatus.OK);
            assertThat(subject.xrefFileProc(session, Request.open("XREFFILE")).rc())
                    .isEqualTo(FileStatus.OK);
            assertThat(subject.custFileProc(session, Request.open("CUSTFILE")).rc())
                    .isEqualTo(FileStatus.OK);
            assertThat(subject.acctFileProc(session, Request.open("ACCTFILE")).rc())
                    .isEqualTo(FileStatus.OK);
        }
    }

    // =================================================================================================
    // The asymmetric per-DD operation matrix, and the silent no-op fall-through.
    // =================================================================================================

    @Nested
    @DisplayName("The per-DD operation matrix - three operations each, and the sets differ")
    class OperationMatrix {

        @ParameterizedTest(name = "{0} honours OPEN, READ, CLOSE and has no keyed read")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#sequentialDds")
        void sequentialDdsHonourThreeOperations(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            assertThat(subject.supportedOperations(dd)).containsExactlyInAnyOrder(
                    Operation.OPEN, Operation.READ, Operation.CLOSE);
        }

        @ParameterizedTest(name = "{0} honours OPEN, READ-K, CLOSE and has no sequential read")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#randomDds")
        void randomDdsHonourThreeOperations(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            assertThat(subject.supportedOperations(dd)).containsExactlyInAnyOrder(
                    Operation.OPEN, Operation.READ_K, Operation.CLOSE);
        }

        @Test
        @DisplayName("the returned capability set is unmodifiable")
        void capabilitySetIsUnmodifiable() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Set<Operation> capabilities =
                    subroutine(template).supportedOperations(StatementGenerationJobB.TRNXFILE_DD);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> capabilities.add(Operation.WRITE));
        }

        /**
         * The whole 4 DD x 6 operation-code space, plus the unrecognised-byte case.
         *
         * <p>Every combination is driven, and each is asserted to be either the honoured operation or the
         * silent no-op returning the stale status. This is the table the class documentation states, made
         * executable.
         *
         * @return one argument set per DD and operation
         */
        static List<org.junit.jupiter.params.provider.Arguments> everyDdAndOperation() {
            List<org.junit.jupiter.params.provider.Arguments> cases = new ArrayList<>();
            for (String dd : StatementGenerationJobB.DD_NAMES) {
                for (Operation operation : Operation.values()) {
                    cases.add(org.junit.jupiter.params.provider.Arguments.of(dd, operation));
                }
            }
            return cases;
        }

        @ParameterizedTest(name = "{1} against {0}")
        @MethodSource("everyDdAndOperation")
        @DisplayName("an unsupported operation is a silent no-op returning the STALE FILE STATUS")
        void unsupportedOperationIsASilentNoOp(String dd, Operation operation) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template).storing(datasetOf(dd),
                    List.of(row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            // Establish a known, distinctive stale status by opening the file: the status becomes '00'.
            subject.open(session, dd);
            assertThat(session.status(dd)).isEqualTo(FileStatus.OK);
            int statementsAfterOpen = backend.statementsSent().size();

            Request request = operation == Operation.READ_K
                    ? Request.readKeyed(dd, digitKeyOf(dd), callerKeyOf(dd))
                    : new Request(dd, operation, FileStatus.END_OF_FILE, Request.blankKey(), 0,
                            StatementGenerationJobB.SPACES_FLDT);
            Response response = subject.call(session, request);

            boolean supported = subject.supportedOperations(dd).contains(operation);
            if (supported) {
                assertThat(response.rc()).as("an honoured operation reports its own outcome")
                        .isIn(FileStatus.OK, StatementGenerationJobB.ALREADY_OPEN_STATUS);
            } else {
                assertThat(response.rc()).as("gate: unsupported op returns the file's LAST-KNOWN status, "
                        + "because nnn900-EXIT's MOVE is outside every IF").isEqualTo(FileStatus.OK);
                assertThat(response.fldt()).as("no record was read, so the area is unchanged")
                        .isEqualTo(request.fldt());
                assertThat(backend.statementsSent()).as("no I/O whatsoever")
                        .hasSize(statementsAfterOpen);
                assertThat(session.isOpen(dd)).as("the open state is untouched").isTrue();
            }
        }

        @ParameterizedTest(name = "an unrecognised operation byte against {0} is a silent no-op")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void unrecognisedOperationByteIsASilentNoOp(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            int afterOpen = backend.statementsSent().size();

            Response response = subject.call(session, Request.unrecognisedOperation(dd));
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(backend.statementsSent()).hasSize(afterOpen);
        }

        @ParameterizedTest(name = "an unsupported op before any OPEN returns the untouched status of {0}")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void staleStatusBeforeAnyOperationIsTheUntouchedArea(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            Response response = subject.call(session, new Request(dd, Operation.WRITE, FileStatus.OK,
                    Request.blankKey(), 0, StatementGenerationJobB.SPACES_FLDT));
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
            assertThat(backend.statementsSent()).isEmpty();
        }

        @ParameterizedTest(name = "READ-K against the sequential DD {0} performs no I/O")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#sequentialDds")
        void keyedReadAgainstASequentialDdIsANoOp(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template).storing(datasetOf(dd),
                    List.of(row("1234567890", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            int afterOpen = backend.statementsSent().size();

            Response response = subject.readByKey(session, dd, "1234567890", 10);
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.fldt()).isEqualTo(StatementGenerationJobB.SPACES_FLDT);
            assertThat(backend.statementsSent()).hasSize(afterOpen);
        }

        @ParameterizedTest(name = "plain READ against the random DD {0} performs no I/O")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#randomDds")
        void sequentialReadAgainstARandomDdIsANoOp(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template).storing(datasetOf(dd),
                    List.of(row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            int afterOpen = backend.statementsSent().size();

            Response response = subject.readNext(session, dd);
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.fldt()).isEqualTo(StatementGenerationJobB.SPACES_FLDT);
            assertThat(backend.statementsSent()).hasSize(afterOpen);
        }
    }

    // =================================================================================================
    // Gate G47 - the FILE STATUS outcomes, and the independence of the four status areas.
    // =================================================================================================

    @Nested
    @DisplayName("Gate G47 - FILE STATUS outcomes per operation, and four independent status areas")
    class GateG47Statuses {

        @ParameterizedTest(name = "a successful sequential read of {0} reports '00'")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#sequentialDds")
        void successfulSequentialReadReportsOk(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row("AAA", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readNext(session, dd);
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.recordImage(widthOf(dd))).isEqualTo(row("AAA", widthOf(dd)));
        }

        @ParameterizedTest(name = "reading past the last record of {0} reports '10', idempotently")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#sequentialDds")
        void sequentialReadPastTheEndReportsEndOfFile(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd),
                    List.of(row("AAA", widthOf(dd)), row("BBB", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.OK);
            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.OK);
            Response atEnd = subject.readNext(session, dd);
            assertThat(atEnd.rc()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(atEnd.fldt()).as("AT END does not disturb the INTO receiver")
                    .isEqualTo(StatementGenerationJobB.SPACES_FLDT);
            assertThat(session.isExhausted(dd)).isTrue();
            // Reported again, and again: CBSTM03A sets END-OF-FILE and never resumes.
            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(session.position(dd)).isEqualTo(2);
        }

        @Test
        @DisplayName("an empty sequential dataset reports '10' on its very first read")
        void emptyDatasetReportsEndOfFileImmediately() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            backend(template).storing(TRNX_DS, List.of());
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.END_OF_FILE);
        }

        @ParameterizedTest(name = "a keyed read of {0} finding the record reports '00'")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#randomDds")
        void keyedReadFindingTheRecordReportsOk(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String key = digitKeyOf(dd);
            backend(template).storing(datasetOf(dd), List.of(row(key, widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, key, callerKeyOf(dd));
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.recordImage(widthOf(dd))).isEqualTo(row(key, widthOf(dd)));
        }

        @ParameterizedTest(name = "a keyed read of {0} for a missing key reports '23'")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#randomDds")
        void keyedReadForAMissingKeyReportsNotFound(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd),
                    List.of(row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, "9".repeat(callerKeyOf(dd)),
                    callerKeyOf(dd));
            assertThat(response.rc()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(response.fldt()).as("INVALID KEY does not disturb the INTO receiver")
                    .isEqualTo(StatementGenerationJobB.SPACES_FLDT);
            assertThat(response.outcome()).isEqualTo(FileStatus.Outcome.NOT_FOUND);
        }

        @Test
        @DisplayName("the four status areas are independent - a CUSTFILE miss leaves TRNXFILE alone")
        void theFourStatusAreasAreIndependent() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template)
                    .storing(TRNX_DS, List.of(row("AAA", StatementGenerationJobB.TRNXFILE_RECORD_LENGTH)))
                    .storing(XREF_DS, List.of())
                    .storing(CUST_DS, List.of())
                    .storing(ACCT_DS, List.of(row("12345678901",
                            StatementGenerationJobB.ACCTFILE_RECORD_LENGTH)));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            for (String dd : allDds()) {
                subject.open(session, dd);
            }

            // TRNXFILE reads a record: '00'.
            subject.readNext(session, StatementGenerationJobB.TRNXFILE_DD);
            // XREFFILE runs out: '10'.
            subject.readNext(session, StatementGenerationJobB.XREFFILE_DD);
            // CUSTFILE misses: '23'.
            subject.readByKey(session, StatementGenerationJobB.CUSTFILE_DD, "123456789",
                    StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH);
            // ACCTFILE finds: '00'.
            subject.readByKey(session, StatementGenerationJobB.ACCTFILE_DD, "12345678901",
                    StatementGenerationJobB.ACCTFILE_CALLER_KEY_LENGTH);

            assertThat(session.status(StatementGenerationJobB.TRNXFILE_DD)).isEqualTo(FileStatus.OK);
            assertThat(session.status(StatementGenerationJobB.XREFFILE_DD))
                    .isEqualTo(FileStatus.END_OF_FILE);
            assertThat(session.status(StatementGenerationJobB.CUSTFILE_DD))
                    .isEqualTo(FileStatus.NOT_FOUND);
            assertThat(session.status(StatementGenerationJobB.ACCTFILE_DD)).isEqualTo(FileStatus.OK);
        }

        @ParameterizedTest(name = "re-opening an already-open {0} reports '41' and changes nothing")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void reopeningAnOpenFileReports41(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row("AAA", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            if (subject.supportedOperations(dd).contains(Operation.READ)) {
                subject.readNext(session, dd);
            }
            int positionBefore = session.position(dd);

            Response response = subject.open(session, dd);
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.ALREADY_OPEN_STATUS);
            assertThat(session.position(dd)).as("a refused open does not reposition")
                    .isEqualTo(positionBefore);
            assertThat(session.isOpen(dd)).isTrue();
        }

        @ParameterizedTest(name = "closing a not-open {0} reports '42'")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void closingANotOpenFileReports42(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            assertThat(subject.close(session, dd).rc())
                    .isEqualTo(StatementGenerationJobB.NOT_OPEN_STATUS);
            subject.open(session, dd);
            assertThat(subject.close(session, dd).rc()).isEqualTo(FileStatus.OK);
            assertThat(session.isOpen(dd)).isFalse();
            assertThat(subject.close(session, dd).rc())
                    .isEqualTo(StatementGenerationJobB.NOT_OPEN_STATUS);
        }

        @ParameterizedTest(name = "reading a not-open {0} reports '47'")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void readingANotOpenFileReports47(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template).storing(datasetOf(dd),
                    List.of(row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            Response response = subject.supportedOperations(dd).contains(Operation.READ)
                    ? subject.readNext(session, dd)
                    : subject.readByKey(session, dd, digitKeyOf(dd), callerKeyOf(dd));
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.NOT_OPEN_FOR_READ_STATUS);
            assertThat(backend.statementsSent()).isEmpty();
        }

        @Test
        @DisplayName("a read after a CLOSE reports '47' too, because every OPEN here is OPEN INPUT")
        void readAfterCloseReports47() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            backend(template).storing(TRNX_DS, List.of(row("AAA", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            subject.close(session, dd);
            assertThat(subject.readNext(session, dd).rc())
                    .isEqualTo(StatementGenerationJobB.NOT_OPEN_FOR_READ_STATUS);
        }

        @Test
        @DisplayName("re-opening after a CLOSE repositions at the start of the file")
        void reopenAfterCloseRepositions() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.XREFFILE_DD;
            backend(template).storing(XREF_DS,
                    List.of(row("AAA", widthOf(dd)), row("BBB", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            subject.open(session, dd);
            assertThat(subject.readNext(session, dd).recordImage(3)).isEqualTo("AAA");
            assertThat(subject.readNext(session, dd).recordImage(3)).isEqualTo("BBB");
            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.END_OF_FILE);
            subject.close(session, dd);

            subject.open(session, dd);
            assertThat(session.position(dd)).isZero();
            assertThat(session.isExhausted(dd)).isFalse();
            assertThat(subject.readNext(session, dd).recordImage(3)).isEqualTo("AAA");
        }

        @ParameterizedTest(name = "an unreachable {0} reports the permanent-error status at OPEN")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void unreachableDatasetFailsTheOpen(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).failing(datasetOf(dd));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            Response response = subject.open(session, dd);
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
            assertThat(response.outcome()).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(session.isOpen(dd)).isFalse();
        }

        @Test
        @DisplayName("a dataset presenting no record-image column fails the OPEN, not the first read")
        void datasetWithoutARecordImageColumnFailsTheOpen() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).describingNoColumn(TRNX_DS);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            assertThat(subject.open(session, StatementGenerationJobB.TRNXFILE_DD).rc())
                    .isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a backend refusal during a sequential read is reported, not mistaken for '10'")
        void backendRefusalDuringASequentialReadIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            Backend backend = backend(template).storing(TRNX_DS, List.of(row("AAA", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            backend.failing(TRNX_DS);

            Response response = subject.readNext(session, dd);
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
            assertThat(session.isExhausted(dd)).as("an unreachable dataset has not ended").isFalse();
        }

        @Test
        @DisplayName("a backend refusal during a keyed read is reported, not mistaken for '23'")
        void backendRefusalDuringAKeyedReadIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.CUSTFILE_DD;
            Backend backend = backend(template).storing(CUST_DS, List.of(row("123456789", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            backend.failing(CUST_DS);

            assertThat(subject.readByKey(session, dd, "123456789", 9).rc())
                    .isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a keyed read whose template yields no result object at all is not a miss")
        void keyedReadYieldingNoResultObjectIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            backend(template).yieldingNothing(ACCT_DS);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            assertThat(subject.readByKey(session, dd, "12345678901", 11).rc())
                    .isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a sequential read whose template yields no result object is an end of browse")
        void sequentialReadYieldingNoResultObjectEndsTheBrowse() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.XREFFILE_DD;
            backend(template).yieldingNothing(XREF_DS);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.END_OF_FILE);
        }

        @Test
        @DisplayName("a row present but carrying no record image is an I/O defect, not an end of file")
        void rowWithoutARecordImageIsAnIoDefect() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            backend(template).storing(TRNX_DS, Arrays.asList((String) null));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readNext(session, dd);
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
            assertThat(session.isExhausted(dd)).isFalse();
        }

        @Test
        @DisplayName("a keyed row carrying no record image is reported, never treated as absent")
        void keyedRowWithoutARecordImageIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.CUSTFILE_DD;
            backend(template).storing(CUST_DS, Arrays.asList((String) null));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            assertThat(subject.readByKey(session, dd, "123456789", 9).rc())
                    .isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
        }

        @ParameterizedTest(name = "a short row of {0} reports '04' AND delivers the record")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void misSizedRowReportsARecordLengthConflict(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            // One byte short of the copybook width: the layout and the data disagree.
            String stored = row(digitKeyOf(dd), widthOf(dd) - 1);
            backend(template).storing(datasetOf(dd), List.of(stored));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.supportedOperations(dd).contains(Operation.READ)
                    ? subject.readNext(session, dd)
                    : subject.readByKey(session, dd, digitKeyOf(dd), callerKeyOf(dd));

            // FILE STATUS '04', not a permanent error. In COBOL the READ succeeded; only the record's
            // length disagrees with the file's fixed attributes. Nine sites in app/cbl/CBSTM03A.CBL
            // accept exactly this status - IF WS-M03B-RC = '00' OR '04', including the first TRNXFILE
            // read at :748 - and every one of those arms is dead if this class cannot produce it.
            assertThat(response.rc()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            assertThat(FileStatus.isRecordLengthConflict(response.rc())).isTrue();
            assertThat(response.rc()).isNotEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);

            // And the record area IS delivered, because READ INTO transferred it. The receiver is
            // PIC X(1000), so the short record lands left-justified and the remainder is spaces.
            assertThat(response.fldt())
                    .as("the record area is filled, not left at SPACES: '04' is a successful read")
                    .isNotEqualTo(StatementGenerationJobB.SPACES_FLDT)
                    .hasSize(StatementGenerationJobB.FLDT_LENGTH)
                    .startsWith(stored);
            assertThat(response.fldt().substring(stored.length()))
                    .isEqualTo(" ".repeat(StatementGenerationJobB.FLDT_LENGTH - stored.length()));
        }

        @ParameterizedTest(name = "an over-wide row of {0} reports '04' and is truncated on the right")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void anOverWideRowIsTruncatedOnTheRight(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            // Wider than the PIC X(1000) receiver, so the MOVE has to discard the overflow rather than
            // refuse: COBOL fills a PIC X receiver from the left and truncates on the RIGHT.
            String stored = row(digitKeyOf(dd), StatementGenerationJobB.FLDT_LENGTH + 7);
            backend(template).storing(datasetOf(dd), List.of(stored));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.supportedOperations(dd).contains(Operation.READ)
                    ? subject.readNext(session, dd)
                    : subject.readByKey(session, dd, digitKeyOf(dd), callerKeyOf(dd));

            assertThat(response.rc()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            assertThat(response.fldt())
                    .hasSize(StatementGenerationJobB.FLDT_LENGTH)
                    .isEqualTo(stored.substring(0, StatementGenerationJobB.FLDT_LENGTH));
        }

        @Test
        @DisplayName("a conforming row still reports '00', so '04' is not reported for every read")
        void aConformingRowStillReportsOk() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            String stored = row(digitKeyOf(dd), widthOf(dd));
            backend(template).storing(datasetOf(dd), List.of(stored));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readNext(session, dd);

            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(FileStatus.isRecordLengthConflict(response.rc())).isFalse();
            assertThat(response.recordImage(widthOf(dd))).isEqualTo(stored);
        }

        @Test
        @DisplayName("a keyed read matching more than one row is refused, not resolved by taking the first")
        void duplicateKeyedMatchIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            String key = "12345678901";
            backend(template).storing(ACCT_DS, List.of(
                    row(key, widthOf(dd)), row(key, widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, key, 11);

            // FD-ACCT-ID is the RECORD KEY of an indexed file (app/cbl/CBSTM03B.CBL:52), so a unique
            // key matching two rows is an integrity defect in the backing relation and not a condition
            // VSAM can present. Returning the first match with '00' would hand CBSTM03A one of two
            // accounts chosen by whatever order the backend produced, with nothing to say a choice was
            // made, and the statement it composed would be plausible and possibly wrong. '22' would be
            // no better - no program in the estate compares against it, so it would be an invented
            // status. The permanent-error status reaches the caller's WHEN OTHER arm, which abends.
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
            assertThat(response.rc()).isNotEqualTo(FileStatus.OK)
                    .isNotEqualTo(FileStatus.DUPLICATE);
            assertThat(response.fldt())
                    .as("no record is handed over at all, so nothing arbitrary can be used")
                    .isEqualTo(StatementGenerationJobB.SPACES_FLDT);
        }

        @Test
        @DisplayName("a key matching exactly one row still succeeds, so the probe is not over-eager")
        void aUniqueKeyedMatchStillSucceeds() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            String key = "12345678901";
            String other = "99999999999";
            backend(template).storing(ACCT_DS, List.of(
                    row(key, widthOf(dd)), row(other, widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, key, 11);

            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.recordImage(widthOf(dd))).isEqualTo(row(key, widthOf(dd)));
        }

        @Test
        @DisplayName("a stored byte outside the dataset code page is reported as an I/O defect")
        void undecodableRowIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.XREFFILE_DD;
            // A character with no US-ASCII encoding, so decoding the stored row must refuse.
            backend(template).storing(XREF_DS, List.of(row("\u00e9AA", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            assertThat(subject.readNext(session, dd).rc())
                    .isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("the permanent-error status is class 9x and lands on every caller's WHEN OTHER arm")
        void permanentErrorStatusIsClassNine() {
            assertThat(StatementGenerationJobB.PERMANENT_ERROR_STATUS).hasSize(2).startsWith("9");
            assertThat(FileStatus.outcomeOfStatus(StatementGenerationJobB.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            // And the four logic-error statuses are the standard COBOL ones, all on WHEN OTHER.
            assertThat(StatementGenerationJobB.ALREADY_OPEN_STATUS).isEqualTo("41");
            assertThat(StatementGenerationJobB.NOT_OPEN_STATUS).isEqualTo("42");
            assertThat(StatementGenerationJobB.NOT_OPEN_FOR_READ_STATUS).isEqualTo("47");
            assertThat(StatementGenerationJobB.UNTOUCHED_STATUS).isEqualTo("  ");
        }
    }

    // =================================================================================================
    // READ ... INTO LK-M03B-FLDT - the group move into the 1000-byte receiver.
    // =================================================================================================

    @Nested
    @DisplayName("READ ... INTO LK-M03B-FLDT - a 350/50/500/300-byte record into an X(1000) receiver")
    class ReadIntoPadding {

        @ParameterizedTest(name = "a record of {0} lands left-justified in 1000 bytes, remainder spaces")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void recordIsLeftJustifiedAndSpacePadded(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            int width = widthOf(dd);
            String key = digitKeyOf(dd);
            String stored = row(key, width);
            backend(template).storing(datasetOf(dd), List.of(stored));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.supportedOperations(dd).contains(Operation.READ)
                    ? subject.readNext(session, dd)
                    : subject.readByKey(session, dd, key, callerKeyOf(dd));

            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.fldt()).hasSize(StatementGenerationJobB.FLDT_LENGTH);
            assertThat(response.fldt().substring(0, width)).isEqualTo(stored);
            assertThat(response.fldt().substring(width))
                    .as("the remainder of the X(1000) receiver is spaces").isBlank();
            assertThat(response.recordImage(width)).isEqualTo(stored);
        }

        @Test
        @DisplayName("the four record widths are 350 / 50 / 500 / 300, taken from the copybook models")
        void theFourRecordWidths() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            assertThat(subject.recordLength(StatementGenerationJobB.TRNXFILE_DD)).isEqualTo(350);
            assertThat(subject.recordLength(StatementGenerationJobB.XREFFILE_DD)).isEqualTo(50);
            assertThat(subject.recordLength(StatementGenerationJobB.CUSTFILE_DD)).isEqualTo(500);
            assertThat(subject.recordLength(StatementGenerationJobB.ACCTFILE_DD)).isEqualTo(300);

            assertThat(StatementGenerationJobB.TRNXFILE_RECORD_LENGTH)
                    .isEqualTo(TrnxRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(StatementGenerationJobB.XREFFILE_RECORD_LENGTH)
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(StatementGenerationJobB.CUSTFILE_RECORD_LENGTH)
                    .isEqualTo(Stm03CustomerRecord.RECORD_LENGTH).isEqualTo(500);
            assertThat(StatementGenerationJobB.ACCTFILE_RECORD_LENGTH)
                    .isEqualTo(AccountRecord.RECORD_LENGTH).isEqualTo(300);
        }

        @Test
        @DisplayName("every FD record splits into its key plus its data span, exactly as CBSTM03B:58-78")
        void fdSplitsSumToTheRecordWidth() {
            assertThat(StatementGenerationJobB.TRNXFILE_KEY_LENGTH
                    + StatementGenerationJobB.TRNXFILE_ACCT_DATA_LENGTH).isEqualTo(350);
            assertThat(StatementGenerationJobB.XREFFILE_KEY_LENGTH
                    + StatementGenerationJobB.XREFFILE_DATA_LENGTH).isEqualTo(50);
            assertThat(StatementGenerationJobB.CUSTFILE_KEY_LENGTH
                    + StatementGenerationJobB.CUSTFILE_DATA_LENGTH).isEqualTo(500);
            assertThat(StatementGenerationJobB.ACCTFILE_KEY_LENGTH
                    + StatementGenerationJobB.ACCTFILE_ACCT_DATA_LENGTH).isEqualTo(300);
        }

        @Test
        @DisplayName("practice B5 - FD-ACCT-DATA survives as TWO spans, 318 and 289, neither merged")
        void fdAcctDataIsDeclaredTwice() {
            // CBSTM03B.CBL:63, inside the TRNXFILE record.
            assertThat(StatementGenerationJobB.TRNXFILE_ACCT_DATA_LENGTH).isEqualTo(318)
                    .isEqualTo(TrnxRecord.TRNX_REST_LENGTH);
            // CBSTM03B.CBL:78, inside the ACCTFILE record.
            assertThat(StatementGenerationJobB.ACCTFILE_ACCT_DATA_LENGTH).isEqualTo(289);
            assertThat(StatementGenerationJobB.TRNXFILE_ACCT_DATA_LENGTH)
                    .as("one COBOL name, two different widths, in two different FD records - preserved")
                    .isNotEqualTo(StatementGenerationJobB.ACCTFILE_ACCT_DATA_LENGTH);
        }

        @Test
        @DisplayName("the TRNXFILE key is the 32-byte composite CREASTMT.JCL declares as KEYS(32 0)")
        void trnxKeyIsThirtyTwoBytes() {
            assertThat(StatementGenerationJobB.TRNXFILE_KEY_LENGTH).isEqualTo(32)
                    .isEqualTo(TrnxRecord.TRNX_CARD_NUM_LENGTH + TrnxRecord.TRNX_ID_LENGTH);
        }
    }

    // =================================================================================================
    // FILE STATUS '04', end to end. CBSTM03A has ten status-checking sites whose behaviour turns on
    // '04' - nine that accept it and three that abend on it - and every one of them is dead code unless
    // THIS class can produce the status. These tests prove the producer and the consumer agree, so the
    // agreement cannot be broken from either side without a failure here.
    // =================================================================================================

    @Nested
    @DisplayName("FILE STATUS '04' is producible, and is the status CBSTM03A's nine guards accept")
    class RecordLengthConflictReachability {

        @Test
        @DisplayName("the status this class produces is the very constant CBSTM03A tests against")
        void theProducerAndTheConsumerShareOneConstant() {
            // Not two literals that happen to read the same. StatementGenerationJobA takes its constant
            // from FileStatus, so a change on either side is a change on both.
            assertThat(StatementGenerationJobA.STATUS_RECORD_LENGTH_CONFLICT)
                    .isSameAs(FileStatus.RECORD_LENGTH_CONFLICT)
                    .isEqualTo("04");
        }

        @ParameterizedTest(name = "a short row of {0} yields a status CBSTM03A's OPEN/CLOSE guards accept")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void theStatusProducedIsAcceptedByTheNineGuards(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row(digitKeyOf(dd), widthOf(dd) - 1)));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.supportedOperations(dd).contains(Operation.READ)
                    ? subject.readNext(session, dd)
                    : subject.readByKey(session, dd, digitKeyOf(dd), callerKeyOf(dd));

            // IF WS-M03B-RC = '00' OR '04' - the nine accepting sites, expressed once as a predicate.
            assertThat(StatementGenerationJobA.isOkOrRecordLengthConflict(response.rc()))
                    .as("the arms at CBSTM03A:736, :748, :771, :789, :807, :862, :879, :895 and :911 are "
                            + "reachable only if this read produces a status they accept")
                    .isTrue();
            // And the three rejecting sites still reject it: the loop read's EVALUATE (:836-847) and the
            // two keyed reads (:379-386, :403-410) all test plain '00'.
            assertThat(FileStatus.isOk(response.rc()))
                    .as("the loop read and the two keyed reads test '00' alone, so they still abend")
                    .isFalse();
        }

        @Test
        @DisplayName("an OPEN and a CLOSE of a conforming dataset still report '00', not '04'")
        void aConformingDatasetReportsOkThroughout() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            backend(template).storing(datasetOf(dd), List.of(row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            assertThat(subject.open(session, dd).rc()).isEqualTo(FileStatus.OK);
            assertThat(subject.readNext(session, dd).rc()).isEqualTo(FileStatus.OK);
            assertThat(subject.close(session, dd).rc()).isEqualTo(FileStatus.OK);
        }
    }

    // =================================================================================================
    // The keyed read: reference modification, then the receiver's own MOVE rule.
    // =================================================================================================

    @Nested
    @DisplayName("Keyed read - LK-M03B-KEY (1:LK-M03B-KEY-LN) into two differently-typed receivers")
    class KeyedRead {

        @Test
        @DisplayName("the caller's key widths are 9 for CUSTFILE and 11 for ACCTFILE, from CVACT03Y")
        void callerKeyWidths() {
            assertThat(StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH)
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_LENGTH).isEqualTo(9)
                    .isEqualTo(StatementGenerationJobB.CUSTFILE_KEY_LENGTH);
            assertThat(StatementGenerationJobB.ACCTFILE_CALLER_KEY_LENGTH)
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(11)
                    .isEqualTo(StatementGenerationJobB.ACCTFILE_KEY_LENGTH);
        }

        @Test
        @DisplayName("key length 9 selects the FIRST nine bytes; trailing garbage is ignored (CUSTFILE)")
        void custFileKeyUsesTheLeadingNineBytes() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.CUSTFILE_DD;
            Backend backend = backend(template)
                    .storing(CUST_DS, List.of(row("123456789", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            // 25 bytes: nine significant, then garbage the reference modification must exclude.
            Response response = subject.readByKey(session, dd, "123456789ZZZZZZZZZZZZZZZZ", 9);
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(backend.patternsBound()).hasSize(1);
            assertThat(backend.patternsBound().get(0)).startsWith("123456789")
                    .as("the pattern is confined to the 9-byte key span at offset 0").endsWith("%");
        }

        @Test
        @DisplayName("key length 11 selects the FIRST eleven bytes; trailing garbage is ignored (ACCTFILE)")
        void acctFileKeyUsesTheLeadingElevenBytes() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            Backend backend = backend(template)
                    .storing(ACCT_DS, List.of(row("12345678901", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, "12345678901ZZZZZZZZZZZZZZ", 11);
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(backend.patternsBound().get(0)).startsWith("12345678901");
        }

        @Test
        @DisplayName("a short key into the X(09) receiver is space-padded on the RIGHT - the PIC X rule")
        void shortKeyIntoAnAlphanumericReceiverPadsRight() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.CUSTFILE_DD;
            Backend backend = backend(template).storing(CUST_DS, List.of(row("12       ", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, "12", 2);
            assertThat(backend.patternsBound().get(0)).as("FD-CUST-ID PIC X(09): fill from the left")
                    .startsWith("12       ");
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("a short key into the 9(11) receiver is zero-filled on the LEFT - the PIC 9 rule")
        void shortKeyIntoANumericReceiverFillsLeft() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            Backend backend = backend(template).storing(ACCT_DS, List.of(row("00000000042", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, "42", 2);
            assertThat(backend.patternsBound().get(0))
                    .as("FD-ACCT-ID PIC 9(11): align on the implied decimal point, zero-fill left")
                    .startsWith("00000000042");
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("an over-long key into the 9(11) receiver keeps the LOW-order digits")
        void overLongKeyIntoANumericReceiverTruncatesLeft() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            Backend backend = backend(template).storing(ACCT_DS, List.of());
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            // Thirteen digits into an eleven-digit receiver: the surviving digits are the last eleven.
            subject.readByKey(session, dd, "1234567890123", 13);
            assertThat(backend.patternsBound().get(0)).startsWith("34567890123");
        }

        @Test
        @DisplayName("a non-numeric key for the 9(11) receiver is a caller-contract violation, not a status")
        void nonNumericKeyForANumericReceiverIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            backend(template).storing(ACCT_DS, List.of());
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> subject.readByKey(session, dd, "ABCDEFGHIJK", 11));
        }

        @Test
        @DisplayName("a LIKE metacharacter in the key is escaped, so it cannot match keys it does not name")
        void keyMetacharactersAreEscaped() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.CUSTFILE_DD;
            Backend backend = backend(template).storing(CUST_DS, List.of(
                    row("123456789", widthOf(dd)), row("%%%%%%%%%", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, "%%%%%%%%%", 9);
            assertThat(backend.patternsBound().get(0)).contains("\\%");
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.recordImage(9)).isEqualTo("%%%%%%%%%");
        }
    }

    // =================================================================================================
    // Constructor guards - everything checkable, checked at startup.
    // =================================================================================================

    @Nested
    @DisplayName("Constructor guards - configuration defects fail at startup, not at the first read")
    class ConstructorGuards {

        @Test
        @DisplayName("all four collaborators are required")
        void collaboratorsAreRequired() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationJobB(null, validBindings(), ASCII,
                            RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, null, ASCII,
                            RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, validBindings(), null,
                            RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, validBindings(), ASCII, null));
        }

        @Test
        @DisplayName("a multi-byte code page is refused, because a fixed-width record is bytes")
        void multiByteCharsetIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, validBindings(),
                            StandardCharsets.UTF_16, RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("an absent binding is refused, naming the configuration key to add")
        void absentBindingIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings incomplete = validBindings();
            incomplete.remove(StatementGenerationJobB.CUSTFILE_DD);
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, incomplete, ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining(StatementGenerationJobB.CUSTFILE_DD);
        }

        @ParameterizedTest(name = "a wrong record length for {0} is refused - gate G19")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void wrongRecordLengthIsRefused(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings spoiled = validBindings();
            DatasetBinding original = spoiled.get(dd);
            spoiled.put(dd, new DatasetBinding(original.dsname(), DatasetBinding.KSDS, false, "FB",
                    null, original.recordLength() + 1, "COPYBOOK", original.keyLength(), null, null,
                    null));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, spoiled, ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("record length");
        }

        @ParameterizedTest(name = "an absent key length for {0} is refused")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void absentKeyLengthIsRefused(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings spoiled = validBindings();
            DatasetBinding original = spoiled.get(dd);
            spoiled.put(dd, new DatasetBinding(original.dsname(), DatasetBinding.KSDS, false, "FB",
                    null, original.recordLength(), "COPYBOOK", null, null, null, null));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, spoiled, ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("key-length");
        }

        @ParameterizedTest(name = "a wrong key length for {0} is refused")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void wrongKeyLengthIsRefused(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings spoiled = validBindings();
            DatasetBinding original = spoiled.get(dd);
            spoiled.put(dd, new DatasetBinding(original.dsname(), DatasetBinding.KSDS, false, "FB",
                    null, original.recordLength(), "COPYBOOK", original.keyLength() - 1, null, null,
                    null));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, spoiled, ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("key-length");
        }

        @Test
        @DisplayName("a non-zero key offset is refused - every RECORD KEY here is the first FD field")
        void nonZeroKeyOffsetIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings spoiled = validBindings();
            String dd = StatementGenerationJobB.XREFFILE_DD;
            DatasetBinding original = spoiled.get(dd);
            spoiled.put(dd, new DatasetBinding(original.dsname(), DatasetBinding.KSDS, false, "FB",
                    null, original.recordLength(), "COPYBOOK", original.keyLength(), 4, null, null));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> new StatementGenerationJobB(template, spoiled, ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("key-offset");
        }

        @ParameterizedTest(name = "a dataset name of ''{0}'' is refused - gate G46")
        @ValueSource(strings = {"", "   "})
        void blankDatasetNameIsRefused(String dsname) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> subroutineWithDsname(dsname))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("an absent dataset name is refused too, not only a blank one")
        void absentDatasetNameIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> subroutineWithDsname(null))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("a malformed dataset name is refused by the module's shared z/OS name grammar")
        void malformedDatasetNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subroutineWithDsname("9BAD..NAME"));
        }

        /**
         * Builds the subject with {@code TRNXFILE}'s dataset name replaced.
         *
         * @param dsname the name to substitute
         * @return the constructed subject, if construction is permitted at all
         */
        private StatementGenerationJobB subroutineWithDsname(String dsname) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings spoiled = validBindings();
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            DatasetBinding original = spoiled.get(dd);
            spoiled.put(dd, new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null,
                    original.recordLength(), "COPYBOOK", original.keyLength(), null, null, null));
            return new StatementGenerationJobB(template, spoiled, ASCII, RecordImageForm.CHARACTER);
        }

        @Test
        @DisplayName("the four dataset names come from configuration and are exposed unchanged (G46)")
        void datasetNamesComeFromConfiguration() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            assertThat(subject.datasetName(StatementGenerationJobB.TRNXFILE_DD)).isEqualTo(TRNX_DS);
            assertThat(subject.datasetName(StatementGenerationJobB.XREFFILE_DD)).isEqualTo(XREF_DS);
            assertThat(subject.datasetName(StatementGenerationJobB.CUSTFILE_DD)).isEqualTo(CUST_DS);
            assertThat(subject.datasetName(StatementGenerationJobB.ACCTFILE_DD)).isEqualTo(ACCT_DS);
        }

        @Test
        @DisplayName("practice B4 - a record-format disagreement is NOT reconciled and NOT rejected")
        void recordFormatIsNotAsserted() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            // The CSD says RECORDFORMAT(V) for three of these datasets while the JCL says RECFM=FB.
            // The Java layer treats length as copybook-fixed and asserts neither side of the conflict.
            DatasetBindings variable = validBindings();
            for (String dd : allDds()) {
                DatasetBinding original = variable.get(dd);
                variable.put(dd, new DatasetBinding(original.dsname(), DatasetBinding.KSDS, false, "V",
                        null, original.recordLength(), "COPYBOOK", original.keyLength(), null, null,
                        null));
            }
            assertThatNoException().isThrownBy(() -> new StatementGenerationJobB(template, variable,
                    ASCII, RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("only the three access paths the COBOL uses are ever sent - no write statement")
        void onlyTheThreeAccessPathsAreSent() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template)
                    .storing(TRNX_DS, List.of(row("A", StatementGenerationJobB.TRNXFILE_RECORD_LENGTH)))
                    .storing(CUST_DS, List.of(row("123456789",
                            StatementGenerationJobB.CUSTFILE_RECORD_LENGTH)));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, StatementGenerationJobB.TRNXFILE_DD);
            subject.readNext(session, StatementGenerationJobB.TRNXFILE_DD);
            subject.readNext(session, StatementGenerationJobB.TRNXFILE_DD);
            subject.open(session, StatementGenerationJobB.CUSTFILE_DD);
            subject.readByKey(session, StatementGenerationJobB.CUSTFILE_DD, "123456789", 9);

            assertThat(backend.statementsSent()).isNotEmpty().allSatisfy(sql -> assertThat(sql)
                    .as("gate G44 - all four files are OPEN INPUT; this class never writes")
                    .startsWith("SELECT")
                    .doesNotContain("INSERT").doesNotContain("UPDATE").doesNotContain("DELETE")
                    .doesNotContain("FOR UPDATE"));
        }

        @Test
        @DisplayName("the record-image column is described once per dataset, not once per read")
        void theColumnIsDescribedOncePerDataset() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            Backend backend = backend(template).storing(TRNX_DS, List.of(
                    row("A", widthOf(dd)), row("B", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            subject.readNext(session, dd);
            subject.readNext(session, dd);
            subject.close(session, dd);
            subject.open(session, dd);

            assertThat(backend.statementsSent().stream().filter(sql -> sql.endsWith("WHERE 1 = 0"))
                    .count()).isEqualTo(1);
        }
    }

    // =================================================================================================
    // The whole (DD x operation) surface, in one table. app/cbl/CBSTM03B.CBL:118-128 crossed with
    // :103-108 - five dispatch outcomes by seven operation bytes.
    // =================================================================================================

    /**
     * Every DD name a caller could hand over, crossed with every operation byte.
     *
     * <p>Six DD names - the four {@code EVALUATE} arms plus two that take {@code WHEN OTHER} - by seven
     * operation bytes - the six {@code 88}-levels plus one that names none of them. Forty-two cells, and
     * every one of them is a distinct path through {@code 0000-START}: a dispatch to a paragraph that
     * honours the operation, a dispatch to a paragraph that falls through it, or no dispatch at all.
     *
     * <p>A table rather than forty-two near-identical methods, so that adding a DD name or an operation
     * widens the coverage automatically instead of inviting a copy-paste.
     *
     * @return one argument set per cell: the DD name, then the operation or {@code null} for a byte no
     *         {@code 88}-level names
     */
    static List<Arguments> everyDdCrossedWithEveryOperation() {
        List<String> dds = new ArrayList<>(StatementGenerationJobB.DD_NAMES);
        // Both unknown names are exactly X(08) wide, because that is the field EVALUATE compares.
        dds.add("NOSUCHDD");
        dds.add(" ".repeat(StatementGenerationJobB.DD_LENGTH));
        List<Operation> operations = new ArrayList<>(Arrays.asList(Operation.values()));
        operations.add(null);
        List<Arguments> cells = new ArrayList<>(dds.size() * operations.size());
        for (String dd : dds) {
            for (Operation operation : operations) {
                cells.add(Arguments.of(dd, operation));
            }
        }
        return cells;
    }

    @Nested
    @DisplayName("The full dispatch surface - 6 DD names x 7 operation bytes, all 42 cells")
    class FullDispatchSurface {

        /**
         * A status this subroutine can never itself produce, used as the inbound sentinel.
         *
         * <p>{@code '22'} is the duplicate-key status, and every one of the four files is opened
         * {@code OPEN INPUT} ({@code app/cbl/CBSTM03B.CBL:136, :160, :184, :209}), so no path here can
         * raise it. That makes it the ideal marker for "untouched": if it comes back, the value was
         * carried, not computed.
         */
        private static final String SENTINEL_RC = FileStatus.DUPLICATE;

        @ParameterizedTest(name = "operation {1} against DD ''{0}''")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest"
                + "#everyDdCrossedWithEveryOperation")
        @DisplayName("every cell is one of exactly three outcomes, and which one is decided by the source")
        void everyCellIsClassifiedByTheSource(String dd, Operation operation) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template);
            for (String known : allDds()) {
                backend.storing(datasetOf(known), List.of(row(digitKeyOf(
                        StatementGenerationJobB.ACCTFILE_DD), widthOf(known))));
            }
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            String key = Request.blankKey();
            int keyLength = 0;
            if (operation == Operation.READ_K && randomDds().contains(dd)) {
                key = digitKeyOf(dd);
                keyLength = callerKeyOf(dd);
            }
            Request request = new Request(dd, operation, SENTINEL_RC, key, keyLength,
                    StatementGenerationJobB.SPACES_FLDT);
            int before = backend.statementsSent().size();
            Response response = subject.call(session, request);

            if (!subject.recognises(dd)) {
                // Outcome 1 - WHEN OTHER -> GO TO 9999-GOBACK (CBSTM03B.CBL:127-131). Nothing is
                // dispatched, nothing is assigned, and LK-M03B-RC comes back exactly as it went in.
                assertThat(response.rc()).as("the WHEN OTHER arm returns the caller's own status")
                        .isEqualTo(SENTINEL_RC);
                assertThat(response.fldt()).isEqualTo(request.fldt());
                assertThat(backend.statementsSent()).hasSize(before);
                return;
            }
            if (operation != null && subject.supportedOperations(dd).contains(operation)) {
                // Outcome 2 - a guard matched, the operation ran, and nnn900-EXIT moved a status the
                // operation itself produced. Whatever it is, it is NOT the sentinel.
                assertThat(response.rc()).as("an honoured operation reports its own file status")
                        .isNotEqualTo(SENTINEL_RC)
                        .isEqualTo(session.status(dd));
                return;
            }
            // Outcome 3 - the paragraph was entered, no guard matched, and control fell out of the third
            // IF straight into nnn900-EXIT, which still performed MOVE <file>-STATUS TO LK-M03B-RC. So
            // the status is overwritten - with the file's own stale area - and no I/O happened.
            assertThat(response.rc()).as("the fall-through overwrites the caller's status with the "
                            + "file's own, unlike the WHEN OTHER arm which overwrites nothing")
                    .isNotEqualTo(SENTINEL_RC)
                    .isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
            assertThat(response.fldt()).isEqualTo(request.fldt());
            assertThat(backend.statementsSent()).as("no I/O on a fall-through").hasSize(before);
        }

        @ParameterizedTest(name = "88 M03B-{0} is driven in both its true and its false state")
        @EnumSource(Operation.class)
        @DisplayName("gate G50 - all six condition names exercised TRUE and FALSE, 24 boolean outcomes")
        void bothStatesOfEveryConditionName(Operation operation) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend backend = backend(template);
            for (String dd : allDds()) {
                backend.storing(datasetOf(dd), List.of(row("K", widthOf(dd))));
            }
            StatementGenerationJobB subject = subroutine(template);

            // The six VALUE literals at CBSTM03B.CBL:103-108 are distinct, so one operation byte can
            // satisfy exactly one condition name - which is what makes "true" and "false" well defined.
            assertThat(Operation.ofCode(operation.code())).contains(operation);

            List<String> testing = ddsTesting(operation);
            for (String dd : allDds()) {
                // A FRESH session each time, so the baseline is the never-established status area and any
                // movement away from it is proof the guard was taken.
                Session session = subject.newSession();
                String key = operation == Operation.READ_K ? digitKeyOf(dd) : Request.blankKey();
                int keyLength = operation == Operation.READ_K ? callerKeyOf(dd) : 0;
                Response response = subject.call(session, new Request(dd, operation, FileStatus.OK, key,
                        keyLength, StatementGenerationJobB.SPACES_FLDT));

                if (testing.contains(dd)) {
                    assertThat(response.rc())
                            .as("IF M03B-%s IS tested in %s's paragraph, so the condition is evaluated "
                                    + "TRUE and the guarded statement runs", operation, dd)
                            .isNotEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
                } else {
                    assertThat(response.rc())
                            .as("IF M03B-%s is NOT tested in %s's paragraph - every guard there evaluates "
                                    + "FALSE and control falls out of the third IF into nnn900-EXIT",
                                    operation, dd)
                            .isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
                }
            }
        }

        @Test
        @DisplayName("the guard census matches the source: OPEN and CLOSE x4, READ x2, READ-K x2, dead x0")
        void theGuardCensusMatchesTheSource() {
            // Twelve IF statements in total across the four paragraphs, and the two dead condition names
            // contribute none. If a later change gave WRITE a guard, this census would fail before any
            // behavioural test noticed (practice B5).
            assertThat(ddsTesting(Operation.OPEN)).hasSize(4);
            assertThat(ddsTesting(Operation.CLOSE)).hasSize(4);
            assertThat(ddsTesting(Operation.READ)).containsExactly(
                    StatementGenerationJobB.TRNXFILE_DD, StatementGenerationJobB.XREFFILE_DD);
            assertThat(ddsTesting(Operation.READ_K)).containsExactly(
                    StatementGenerationJobB.CUSTFILE_DD, StatementGenerationJobB.ACCTFILE_DD);
            assertThat(ddsTesting(Operation.WRITE)).isEmpty();
            assertThat(ddsTesting(Operation.REWRITE)).isEmpty();

            int guards = 0;
            for (Operation operation : Operation.values()) {
                guards += ddsTesting(operation).size();
                // And the census agrees with what the subject itself publishes as its capability sets.
                for (String dd : allDds()) {
                    JdbcTemplate template = mock(JdbcTemplate.class);
                    assertThat(subroutine(template).supportedOperations(dd).contains(operation))
                            .isEqualTo(ddsTesting(operation).contains(dd));
                }
            }
            assertThat(guards).as("three IF statements in each of the four paragraphs").isEqualTo(12);
        }

        @Test
        @DisplayName("the table really is 42 cells: 6 DD names x 7 operation bytes, none duplicated")
        void theTableCoversTheWholeSurface() {
            List<Arguments> cells = everyDdCrossedWithEveryOperation();
            assertThat(cells).hasSize(42);
            Set<String> distinct = new LinkedHashSet<>();
            for (Arguments cell : cells) {
                distinct.add(cell.get()[0] + "|" + cell.get()[1]);
            }
            assertThat(distinct).as("no cell is driven twice").hasSize(cells.size());
            assertThat(Operation.values()).as("six 88-levels at CBSTM03B.CBL:103-108").hasSize(6);
            assertThat(StatementGenerationJobB.DD_NAMES).as("four EVALUATE arms").hasSize(4);
        }
    }

    // =================================================================================================
    // The no-I/O guarantee, asserted on the collaborator itself. Counting statements proves the subject
    // sent none; verifyNoInteractions proves it did not so much as touch the template - which is the
    // stronger statement, and the one that would still hold if the subject grew a new access path.
    // =================================================================================================

    @Nested
    @DisplayName("Every no-op path leaves the data-access collaborator completely untouched")
    class NoOpPathsTouchNothing {

        /**
         * The four DDs crossed with the operations each of them does <em>not</em> honour.
         *
         * @return one argument set per unsupported (DD, operation) pair
         */
        static List<Arguments> unsupportedPairs() {
            List<Arguments> pairs = new ArrayList<>();
            for (String dd : StatementGenerationJobB.DD_NAMES) {
                boolean sequential = List.of(StatementGenerationJobB.TRNXFILE_DD,
                        StatementGenerationJobB.XREFFILE_DD).contains(dd);
                for (Operation operation : Operation.values()) {
                    boolean supported = operation == Operation.OPEN || operation == Operation.CLOSE
                            || (sequential ? operation == Operation.READ : operation == Operation.READ_K);
                    if (!supported) {
                        pairs.add(Arguments.of(dd, operation));
                    }
                }
            }
            return pairs;
        }

        @ParameterizedTest(name = "{1} against {0} never reaches the template at all")
        @MethodSource("unsupportedPairs")
        @DisplayName("an unsupported operation performs no interaction whatsoever with the JdbcTemplate")
        void unsupportedOperationsDoNotTouchTheTemplate(String dd, Operation operation) {
            // Deliberately UNSTUBBED: if the subject touched it, the interaction would be recorded and
            // the verification below would fail. There is nothing to stub, because nothing should run.
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            String key = operation == Operation.READ_K ? digitKeyOf(dd) : Request.blankKey();
            int keyLength = operation == Operation.READ_K ? callerKeyOf(dd) : 0;
            Response response = subject.call(session, new Request(dd, operation, FileStatus.OK, key,
                    keyLength, StatementGenerationJobB.SPACES_FLDT));

            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
            verifyNoInteractions(template);
        }

        @ParameterizedTest(name = "the dead operation {0} touches no template on any of the four DDs")
        @EnumSource(value = Operation.class, names = {"WRITE", "REWRITE"})
        @DisplayName("practice B5 - WRITE and REWRITE stay dead: no I/O, no status of their own")
        void theTwoDeadOperationsTouchNothing(Operation dead) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            for (String dd : allDds()) {
                Response response = subject.call(session, new Request(dd, dead, FileStatus.OK,
                        Request.blankKey(), 0, StatementGenerationJobB.SPACES_FLDT));
                assertThat(response.rc()).as("%s against %s reports the file's own untouched area",
                        dead, dd).isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
                assertThat(session.isOpen(dd)).as("%s cannot open a file", dead).isFalse();
            }
            verifyNoInteractions(template);
        }

        @ParameterizedTest(name = "unknown DD ''{0}'' touches no template")
        @ValueSource(strings = {"NOSUCHDD", "READTRNX", "        ", "TRNXFIL", "trnxfile", " TRNXFIL"})
        @DisplayName("gate G48 (a) - WHEN OTHER performs no interaction and returns the RC unchanged")
        void unknownDdNamesTouchNothing(String unknown) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            // '04' as the sentinel this time: a real COBOL status, so the assertion cannot pass by the
            // value happening to be impossible.
            Response response = subject.call(session, new Request(unknown, Operation.OPEN,
                    FileStatus.RECORD_LENGTH_CONFLICT, Request.blankKey(), 0,
                    StatementGenerationJobB.SPACES_FLDT));

            assertThat(response.rc()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            verifyNoInteractions(template);
        }

        @ParameterizedTest(name = "''{0}'' is NOT unknown - X(08) truncates it into TRNXFILE")
        @ValueSource(strings = {"TRNXFILE ", "TRNXFILEX", "TRNXFILE.RECORD"})
        @DisplayName("an over-long DD name is right-truncated to X(08) and therefore DOES match")
        void anOverLongDdNameTruncatesIntoTheRecognisedName(String overLong) {
            // The trap this pins: EVALUATE LK-M03B-DD compares an eight-byte field, and a MOVE into
            // PIC X(08) discards whatever will not fit - on the RIGHT. So a name that merely starts with
            // TRNXFILE is not an unknown DD at all; it is TRNXFILE. Asserting the canonicalisation
            // itself, with no session and no call, keeps the fact separate from what dispatch then does.
            Request request = new Request(overLong, Operation.OPEN, FileStatus.OK, Request.blankKey(), 0,
                    StatementGenerationJobB.SPACES_FLDT);
            assertThat(request.dd()).hasSize(StatementGenerationJobB.DD_LENGTH)
                    .isEqualTo(StatementGenerationJobB.TRNXFILE_DD);

            JdbcTemplate template = mock(JdbcTemplate.class);
            assertThat(subroutine(template).recognises(request.dd())).isTrue();
            verifyNoInteractions(template);
        }

        @ParameterizedTest(name = "an unrecognised operation byte against {0} touches no template")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        @DisplayName("an operation byte naming no 88-level is a fall-through, and touches nothing")
        void unrecognisedOperationBytesTouchNothing(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            Response response = subject.call(session, Request.unrecognisedOperation(dd));

            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.UNTOUCHED_STATUS);
            verifyNoInteractions(template);
        }

        @ParameterizedTest(name = "after a successful OPEN, an unsupported op against {0} adds no I/O")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        @DisplayName("gate G48 (b) - the fall-through returns the '00' a real OPEN left, and adds no I/O")
        void staleStatusAfterASuccessfulOperationAddsNoInteraction(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row("K", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            assertThat(subject.open(session, dd).rc()).isEqualTo(FileStatus.OK);
            // Everything the OPEN legitimately did is now accounted for; anything after this point is
            // an interaction the fall-through caused, and there must be none.
            clearInvocations(template);

            Response response = subject.call(session, new Request(dd, Operation.WRITE, FileStatus.OK,
                    Request.blankKey(), 0, StatementGenerationJobB.SPACES_FLDT));

            assertThat(response.rc()).as("nnn900-EXIT moves the status the OPEN left behind")
                    .isEqualTo(FileStatus.OK);
            assertThat(session.isOpen(dd)).isTrue();
            verifyNoMoreInteractions(template);
        }
    }

    // =================================================================================================
    // Gate G47, completed: which FILE STATUS outcomes each DD can actually reach, and which it cannot.
    // The set differs per DD, and the difference is the same asymmetry the SELECTs declare.
    // =================================================================================================

    @Nested
    @DisplayName("Gate G47 - the reachable FILE STATUS outcomes, per DD, and the two that are not")
    class ReachableOutcomes {

        @ParameterizedTest(name = "{0} can report OK")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        @DisplayName("'00' is reachable on all four DDs - the OPEN every caller performs first")
        void okIsReachableEverywhere(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row("K", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            Response response = subject.open(session, dd);
            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            assertThat(response.outcome()).isEqualTo(Outcome.OK);
            assertThat(response.ok()).isTrue();
        }

        @ParameterizedTest(name = "{0} can report END-OF-FILE, and its caller has a '10' arm")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#sequentialDds")
        @DisplayName("'10' is reachable on exactly the two SEQUENTIAL DDs (CBSTM03A:353 and :837)")
        void endOfFileIsReachableOnTheSequentialDds(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row("K", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);
            subject.readNext(session, dd);

            Response past = subject.readNext(session, dd);
            assertThat(past.rc()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(past.outcome()).isEqualTo(Outcome.END_OF_FILE);
            assertThat(past.endOfFile()).isTrue();
        }

        @ParameterizedTest(name = "{0} cannot report END-OF-FILE, and its caller has no '10' arm")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#randomDds")
        @DisplayName("'10' is unreachable on the two RANDOM DDs - there is no sequential read to end")
        void endOfFileIsUnreachableOnTheRandomDds(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            // A plain READ is not honoured here, so no repetition of it can ever exhaust a browse; and a
            // keyed read that finds nothing is '23', never '10'. That is exactly why CBSTM03A's CUSTFILE
            // and ACCTFILE EVALUATEs (:379 and :403) declare no '10' arm while its two read loops do.
            for (int attempt = 0; attempt < 3; attempt++) {
                Response response = subject.readNext(session, dd);
                assertThat(response.rc()).isNotEqualTo(FileStatus.END_OF_FILE);
                assertThat(response.endOfFile()).isFalse();
            }
            Response missed = subject.readByKey(session, dd, "9".repeat(callerKeyOf(dd)),
                    callerKeyOf(dd));
            assertThat(missed.rc()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(missed.endOfFile()).isFalse();
        }

        @ParameterizedTest(name = "{0} can report NOT-FOUND")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#randomDds")
        @DisplayName("'23' is reachable on exactly the two RANDOM DDs - INVALID KEY on a keyed read")
        void notFoundIsReachableOnTheRandomDds(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, "9".repeat(callerKeyOf(dd)),
                    callerKeyOf(dd));
            assertThat(response.rc()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(response.outcome()).isEqualTo(Outcome.NOT_FOUND);
        }

        @ParameterizedTest(name = "{0} cannot report NOT-FOUND")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#sequentialDds")
        @DisplayName("'23' is unreachable on the two SEQUENTIAL DDs - they honour no keyed read")
        void notFoundIsUnreachableOnTheSequentialDds(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(row("K", widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, "9".repeat(25), 25);
            assertThat(response.rc()).as("a keyed read here is a fall-through, not a miss")
                    .isEqualTo(FileStatus.OK);
            assertThat(response.outcome()).isNotEqualTo(Outcome.NOT_FOUND);
        }

        @ParameterizedTest(name = "{0} can report an OTHER outcome")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        @DisplayName("an OTHER outcome is reachable on all four - the arm every caller abends on")
        void otherIsReachableEverywhere(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).failing(datasetOf(dd));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();

            Response response = subject.open(session, dd);
            assertThat(response.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(response.ok()).isFalse();
            assertThat(response.endOfFile()).isFalse();
        }

        @ParameterizedTest(name = "no operation on {0} can report the duplicate-key status '22'")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        @DisplayName("'22' is unreachable on every DD, because OPEN INPUT admits no write to duplicate")
        void duplicateIsUnreachableAnywhere(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            // Two rows that both match any key, so the one condition that could plausibly be called a
            // duplicate is present in the data itself.
            backend(template).storing(datasetOf(dd), List.of(row(digitKeyOf(dd), widthOf(dd)),
                    row(digitKeyOf(dd), widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            for (Operation operation : Operation.values()) {
                String key = operation == Operation.READ_K ? digitKeyOf(dd) : Request.blankKey();
                int keyLength = operation == Operation.READ_K ? callerKeyOf(dd) : 0;
                Response response = subject.call(session, new Request(dd, operation, FileStatus.OK, key,
                        keyLength, StatementGenerationJobB.SPACES_FLDT));
                assertThat(response.rc()).as("%s against %s must not report '22'", operation, dd)
                        .isNotEqualTo(FileStatus.DUPLICATE);
                assertThat(FileStatus.isDuplicate(response.rc())).isFalse();
                assertThat(response.outcome()).isNotEqualTo(Outcome.DUPLICATE);
            }
        }

        @Test
        @DisplayName("'22' is nevertheless a status the differ would recognise - it is unreachable, not unknown")
        void duplicateRemainsAClassifiableStatus() {
            // The distinction matters: the class cannot produce '22', but nothing pretends the value has
            // no meaning. A caller handed one from anywhere else still classifies it correctly.
            Response fabricated = new Response(FileStatus.DUPLICATE, StatementGenerationJobB.SPACES_FLDT);
            assertThat(fabricated.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(fabricated.ok()).isFalse();
            assertThat(FileStatus.DUPLICATE).hasSize(FileStatus.STATUS_LENGTH);
        }

        @Test
        @DisplayName("'04' is returned as itself and never normalised to '00' - the nine guards need both")
        void recordLengthConflictIsNotNormalised() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.XREFFILE_DD;
            // One byte short of the copybook's declared width: the READ succeeds, the length disagrees.
            backend(template).storing(datasetOf(dd),
                    List.of(row("K", StatementGenerationJobB.XREFFILE_RECORD_LENGTH - 1)));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readNext(session, dd);

            assertThat(response.rc()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            assertThat(response.rc()).isNotEqualTo(FileStatus.OK);
            assertThat(response.ok()).as("isOk is reserved for '00'").isFalse();
            // app/cbl/CBSTM03A.CBL's nine guards read IF WS-M03B-RC = '00' OR '04', so both values pass
            // there and only there; its four EVALUATEs name '00' and, at two of them, '10' - never '04'.
            assertThat(acceptedByTheNineGuards(response.rc())).isTrue();
            assertThat(acceptedByTheNineGuards(FileStatus.OK)).isTrue();
            assertThat(acceptedByTheNineGuards(FileStatus.END_OF_FILE)).isFalse();
            assertThat(acceptedByTheNineGuards(FileStatus.NOT_FOUND)).isFalse();
        }

        /**
         * {@code IF WS-M03B-RC = '00' OR '04'} - the guard at
         * {@code app/cbl/CBSTM03A.CBL:736, :748, :771, :789, :807, :862, :879, :895, :911}, written out
         * so the acceptance rule is executable rather than described.
         *
         * @param rc the status this subroutine returned
         * @return whether those nine call sites would continue rather than abend
         */
        private boolean acceptedByTheNineGuards(String rc) {
            return FileStatus.OK.equals(rc) || FileStatus.RECORD_LENGTH_CONFLICT.equals(rc);
        }
    }

    // =================================================================================================
    // Copybook geometry: the REDEFINES pair over one backing span, the FILLER spans that must be
    // emitted, and the OCCURS tables this subroutine does not have.
    // =================================================================================================

    @Nested
    @DisplayName("Copybook geometry - REDEFINES (G34), FILLER (G21) and OCCURS (G33)")
    class CopybookGeometry {

        @Test
        @DisplayName("gate G34 - TRNX-KEY and TRNX-REST are two accessors over ONE backing span")
        void theRedefinesPairSharesOneBackingSpan() {
            assertThat(TrnxRecord.TRNX_KEY.redefinition()).as("TRNX-KEY redefines storage").isTrue();
            assertThat(TrnxRecord.TRNX_REST.redefinition()).as("TRNX-REST redefines storage").isTrue();
            // The two group spans tile the record exactly, and each covers its own elementary children:
            // 0..31 is FD-TRNXS-ID (card 16 + id 16) and 32..349 is FD-ACCT-DATA X(318).
            assertThat(TrnxRecord.TRNX_KEY.offset()).isEqualTo(0);
            assertThat(TrnxRecord.TRNX_KEY.length()).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_REST.offset()).isEqualTo(TrnxRecord.TRNX_KEY.endOffsetExclusive());
            assertThat(TrnxRecord.TRNX_REST.length()).isEqualTo(318);
            assertThat(TrnxRecord.TRNX_REST.endOffsetExclusive())
                    .isEqualTo(TrnxRecord.RECORD_LENGTH);

            // Round-trip through the group accessor, then read the elementary ones: one storage, two
            // views, and no cached copy in between.
            TrnxRecord record = TrnxRecord.newRecord(ASCII);
            record.writeTrnxKey("4444333322221111" + "0000000000000042");
            assertThat(record.readTrnxCardNum()).isEqualTo("4444333322221111");
            assertThat(record.readTrnxId()).isEqualTo("0000000000000042");
            // ... and back the other way, which is the half a cached implementation would fail.
            record.writeTrnxCardNum("1111222233334444");
            assertThat(record.readTrnxKey()).startsWith("1111222233334444");
            assertThat(record.readTrnxKey()).hasSize(TrnxRecord.TRNX_KEY_LENGTH);
        }

        @Test
        @DisplayName("gate G34 - LK-M03B-KEY-LN's four bytes read as a number and as characters agree")
        void theKeyLengthSpanReadsTwoWays() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            StatementGenerationJobB subject = subroutine(template);
            byte[] area = subject.toAreaImage(Request.readKeyed(
                    StatementGenerationJobB.CUSTFILE_DD, "000000011" + " ".repeat(16),
                    StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH), null);

            // The numeric view, through the subject's own parser.
            assertThat(subject.fromAreaImage(area).keyLength())
                    .isEqualTo(StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH);
            // The character view of the very same four bytes at offset 36. S9(4) DISPLAY is zoned, so
            // the digits are readable as text and the sign is overpunched into the last of them - which
            // is what makes this four bytes rather than a two-byte COMP halfword.
            String zoned = new String(area, StatementGenerationJobB.KEY_LN_OFFSET,
                    StatementGenerationJobB.KEY_LN_LENGTH, ASCII);
            assertThat(zoned).hasSize(4);
            assertThat(zoned).startsWith("000");
            assertThat(codec.decodeSignedScaled(zoned, 0)).isEqualByComparingTo(
                    BigDecimal.valueOf(StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH));
        }

        /**
         * The four record types this subroutine reads, each with the {@code FILLER} its copybook
         * declares. Every one of these spans must be emitted, and emitted as spaces, or every offset
         * after it - and the total width - is wrong.
         *
         * @return one argument set per DD: the DD name, the {@code FILLER} span and the record width
         */
        static List<Arguments> fillerSpans() {
            return List.of(
                    Arguments.of(StatementGenerationJobB.TRNXFILE_DD, TrnxRecord.FILLER,
                            TrnxRecord.RECORD_LENGTH),
                    Arguments.of(StatementGenerationJobB.XREFFILE_DD, CardXrefRecord.FILLER,
                            CardXrefRecord.RECORD_LENGTH),
                    Arguments.of(StatementGenerationJobB.CUSTFILE_DD, Stm03CustomerRecord.FILLER,
                            Stm03CustomerRecord.RECORD_LENGTH),
                    Arguments.of(StatementGenerationJobB.ACCTFILE_DD, AccountRecord.SPAN_FILLER,
                            AccountRecord.RECORD_LENGTH));
        }

        @ParameterizedTest(name = "{0}''s FILLER is declared and ends the record")
        @MethodSource("fillerSpans")
        @DisplayName("gate G21 - every record's FILLER is declared, and the width proves it is emitted")
        void everyFillerIsDeclaredAndAccountedFor(String dd, FieldSpan filler, int recordLength) {
            assertThat(filler.name()).as("a reserved span is named FILLER").isEqualTo("FILLER");
            assertThat(filler.endOffsetExclusive())
                    .as("%s's FILLER is the last span, so the record ends where it ends", dd)
                    .isEqualTo(recordLength);
            assertThat(recordLength).isEqualTo(widthOf(dd));
            // The two the brief singles out, asserted as literals so a transcription slip cannot hide
            // behind a constant that moved with it.
            if (StatementGenerationJobB.XREFFILE_DD.equals(dd)) {
                assertThat(filler.offset()).isEqualTo(36);
                assertThat(filler.length()).isEqualTo(14);
            }
            if (StatementGenerationJobB.ACCTFILE_DD.equals(dd)) {
                assertThat(filler.offset()).isEqualTo(122);
                assertThat(filler.length()).isEqualTo(178);
            }
        }

        @ParameterizedTest(name = "the FILLER inside a record {0} returns is space-filled")
        @MethodSource("fillerSpans")
        @DisplayName("gate G21 - FILLER arrives as spaces in LK-M03B-FLDT, never as data or as nothing")
        void fillerArrivesAsSpaces(String dd, FieldSpan filler, int recordLength) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            // A row whose FILLER span is spaces and whose other bytes are not, so "spaces" cannot pass
            // by the whole record happening to be blank.
            StringBuilder stored = new StringBuilder("X".repeat(recordLength));
            for (int index = filler.offset(); index < filler.endOffsetExclusive(); index++) {
                stored.setCharAt(index, ' ');
            }
            // The two RANDOM files are reached by key, and ACCTFILE's RECORD KEY is FD-ACCT-ID PIC 9(11)
            // (CBSTM03B.CBL:77), so its leading bytes must be digits: a numeric MOVE of 'X' is refused,
            // deliberately, rather than silently zeroed. Digits for CUSTFILE's X(09) key too, which
            // costs nothing and keeps the two random cases symmetrical.
            String key = randomDds().contains(dd) ? digitKeyOf(dd) : null;
            if (key != null) {
                stored.replace(0, key.length(), key);
            }
            backend(template).storing(datasetOf(dd), List.of(stored.toString()));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = key == null
                    ? subject.readNext(session, dd)
                    : subject.readByKey(session, dd, key, callerKeyOf(dd));
            assertThat(response.rc()).isEqualTo(FileStatus.OK);

            String image = response.recordImage(recordLength);
            assertThat(image).hasSize(recordLength);
            assertThat(image.substring(filler.offset(), filler.endOffsetExclusive()))
                    .as("%s's FILLER X(%s) at offset %s", dd, filler.length(), filler.offset())
                    .isEqualTo(" ".repeat(filler.length()));
            assertThat(image.charAt(filler.offset() - 1)).as("the byte before FILLER is data")
                    .isEqualTo('X');
        }

        @Test
        @DisplayName("the four models' widths and RECORD KEYs are exactly what the four FDs declare")
        void theModelConstantsMatchTheFds() {
            // TRNXFILE - FD-TRNXS-ID is the first 32 bytes (card 16 + id 16), which CREASTMT.JCL:29-32
            // states independently as KEYS(32 0), and the record is 350.
            assertThat(TrnxRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(TrnxRecord.TRNX_KEY_OFFSET).isZero();
            assertThat(TrnxRecord.TRNX_KEY_LENGTH).isEqualTo(32);
            assertThat(StatementGenerationJobB.TRNXFILE_KEY_LENGTH)
                    .isEqualTo(TrnxRecord.TRNX_KEY_LENGTH);

            // XREFFILE - the base RECORD KEY is FD-XREF-CARD-NUM X(16) at offset 0. The 11-byte
            // XREF-ACCT-ID is the ALTERNATE key (CXACAIX's path over the same base), which this
            // subroutine never browses on: CBSTM03B declares one RECORD KEY per SELECT, and the
            // alternate exists here only because CBSTM03A reads the account id out of the record it was
            // handed. Both are asserted so the distinction cannot quietly become two datasets.
            assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET).isZero();
            assertThat(StatementGenerationJobB.XREFFILE_KEY_LENGTH)
                    .isEqualTo(CardXrefRecord.XREF_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardXrefRecord.XREF_ACCT_ID_LENGTH).as("the alternate key").isEqualTo(11);
            assertThat(CardXrefRecord.XREF_ACCT_ID_OFFSET).isEqualTo(25);

            // CUSTFILE - FD-CUST-ID X(09) at offset 0, record 500.
            assertThat(Stm03CustomerRecord.RECORD_LENGTH).isEqualTo(500);
            assertThat(Stm03CustomerRecord.KEY_OFFSET).isZero();
            assertThat(StatementGenerationJobB.CUSTFILE_KEY_LENGTH)
                    .isEqualTo(Stm03CustomerRecord.KEY_LENGTH).isEqualTo(9);

            // ACCTFILE - FD-ACCT-ID PIC 9(11) at offset 0, record 300.
            assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
            assertThat(AccountRecord.ACCT_ID_OFFSET).isZero();
            assertThat(StatementGenerationJobB.ACCTFILE_KEY_LENGTH)
                    .isEqualTo(AccountRecord.ACCT_ID_LENGTH).isEqualTo(11);
        }

        @Test
        @DisplayName("gate G33 - this subroutine indexes no OCCURS table, and here is the proof")
        void thereIsNoOccursTable() {
            // CBSTM03B.CBL declares four FDs, four two-byte status areas and one linkage area, and not
            // one OCCURS among them - so the 1-based-to-0-based conversion that gate G33 exists to catch
            // has no site in this class. The 51-by-10 statement table with its OCCURS lives in
            // app/cbl/CBSTM03A.CBL, and StatementGenerationJobA is where it is asserted.
            //
            // Stated as an assertion rather than only as a comment: a repeating group would show up as a
            // repeated span name, and there is none in the linkage area or in any of the four records.
            assertNoRepeatedSpanName(StatementGenerationJobB.AREA_LAYOUT.spans(), "LK-M03B-AREA");
            assertNoRepeatedSpanName(TrnxRecord.layout().spans(), "TRNX-RECORD");
            assertNoRepeatedSpanName(CardXrefRecord.LAYOUT.spans(), "CARD-XREF-RECORD");
            assertNoRepeatedSpanName(Stm03CustomerRecord.LAYOUT.spans(), "CUSTOMER-RECORD");
            assertNoRepeatedSpanName(AccountRecord.LAYOUT.spans(), "ACCOUNT-RECORD");
            assertThat(StatementGenerationJobB.AREA_LAYOUT.spans())
                    .as("the linkage area is six elementary items and no table").hasSize(6);
        }

        /**
         * Asserts that no name other than {@code FILLER} appears twice in a layout, which is what
         * "declares no repeating group" means structurally.
         *
         * @param spans  the layout's spans
         * @param record the record's copybook name, for the failure message
         */
        private void assertNoRepeatedSpanName(List<FieldSpan> spans, String record) {
            Set<String> seen = new LinkedHashSet<>();
            for (FieldSpan span : spans) {
                if ("FILLER".equals(span.name())) {
                    continue;
                }
                assertThat(seen.add(span.name()))
                        .as("%s declares '%s' more than once, which would mean a repeating group",
                                record, span.name())
                        .isTrue();
            }
        }
    }

    // =================================================================================================
    // The real data. Three of the four files have a derived fixture on the TEST CLASSPATH; the fourth
    // has none anywhere, because its dataset does not exist until CREASTMT.JCL builds it.
    // =================================================================================================

    @Nested
    @DisplayName("Derived fixture rows - real CARDXREF, CUSTDATA and ACCTDATA bytes, and built TRNX rows")
    class DerivedFixtureRows {

        @Test
        @DisplayName("gate G16 / risk R-F - a 36-byte cardxref row is widened to the declared 50")
        void aStoredCardXrefRowIsWidenedToItsDeclaredWidth() {
            String stored = fixtureRows(CARDXREF_FIXTURE).get(0);
            assertThat(stored).hasSize(CARDXREF_STORED_ROW_WIDTH);

            String widened = codec.padToDeclaredWidth(stored, CardXrefRecord.RECORD_LENGTH);

            assertThat(widened).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(widened).isEqualTo(stored + " ".repeat(CardXrefRecord.FILLER_LENGTH));
            // The 14 bytes the fixture omits are exactly CVACT03Y's trailing FILLER, and spaces are what
            // it holds because the copybook gives it no VALUE.
            assertThat(CardXrefRecord.FILLER_OFFSET).isEqualTo(CARDXREF_STORED_ROW_WIDTH);
            assertThat(widened.substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the widening belongs to the shared codec, not to CardXrefRecord - which refuses 36")
        void theModelRefusesAShortImageSoTheCodecMustWiden() {
            String stored = fixtureRows(CARDXREF_FIXTURE).get(0);

            // CardXrefRecord describes the 50-byte record CVACT03Y declares. Teaching it to accept 36
            // would push a fixture's shortcoming into the model every consumer shares, so it refuses,
            // and the normalisation stays in the codec where the parity harness also performs it.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecord.decode(stored.getBytes(ASCII), ASCII));
            assertThatNoException().isThrownBy(() -> CardXrefRecord.decode(
                    codec.padToDeclaredWidth(stored, CardXrefRecord.RECORD_LENGTH).getBytes(ASCII),
                    ASCII));
        }

        @Test
        @DisplayName("XREFFILE reads a real widened cross-reference row into LK-M03B-FLDT")
        void xrefFileReturnsARealWidenedRow() {
            String dd = StatementGenerationJobB.XREFFILE_DD;
            String widened = widenedCardXrefRow(1);
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(widened));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readNext(session, dd);

            assertThat(response.rc()).as("a widened row conforms, so '00' and not '04'")
                    .isEqualTo(FileStatus.OK);
            assertThat(response.recordImage(CardXrefRecord.RECORD_LENGTH)).isEqualTo(widened);
            // The caller's own MOVE WS-M03B-FLDT TO CARD-XREF-RECORD, performed here to prove the bytes
            // land where the copybook says: card number, then customer id, then account id.
            CardXrefRecord decoded = CardXrefRecord.decode(
                    response.recordImage(CardXrefRecord.RECORD_LENGTH).getBytes(ASCII), ASCII);
            assertThat(decoded.xrefCardNum())
                    .isEqualTo(widened.substring(0, CardXrefRecord.XREF_CARD_NUM_LENGTH));
            assertThat(decoded.xrefCustId()).isEqualTo(Integer.parseInt(widened.substring(
                    CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_CUST_ID_OFFSET + CardXrefRecord.XREF_CUST_ID_LENGTH)));
            assertThat(decoded.xrefAcctId()).isEqualTo(Long.parseLong(widened.substring(
                    CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_OFFSET + CardXrefRecord.XREF_ACCT_ID_LENGTH)));
            // And the rest of the X(1000) receiver is spaces, because READ ... INTO is a group MOVE.
            assertThat(response.fldt().substring(CardXrefRecord.RECORD_LENGTH))
                    .isEqualTo(" ".repeat(StatementGenerationJobB.FLDT_LENGTH
                            - CardXrefRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("CUSTFILE reads a real 500-byte customer row by its real 9-byte key")
        void custFileReturnsARealRowByKey() {
            String dd = StatementGenerationJobB.CUSTFILE_DD;
            String stored = fixtureRows(CUSTDATA_FIXTURE).get(0);
            assertThat(stored).hasSize(Stm03CustomerRecord.RECORD_LENGTH);
            String key = stored.substring(Stm03CustomerRecord.KEY_OFFSET,
                    Stm03CustomerRecord.KEY_OFFSET + Stm03CustomerRecord.KEY_LENGTH);
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(stored));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            // The caller pads the key into X(25) and states its length separately - CBSTM03A.CBL:372-374.
            Response response = subject.readByKey(session, dd,
                    key + " ".repeat(StatementGenerationJobB.KEY_LENGTH - key.length()),
                    StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH);

            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            String image = response.recordImage(Stm03CustomerRecord.RECORD_LENGTH);
            assertThat(image).isEqualTo(stored);
            Stm03CustomerRecord decoded = Stm03CustomerRecord.decode(image, ASCII);
            assertThat(decoded.custIdValue(ASCII)).isEqualTo(Integer.parseInt(key));
            assertThat(decoded.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .as("CUSTREC's trailing FILLER is spaces in the real data too")
                    .isEqualTo(" ".repeat(Stm03CustomerRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("ACCTFILE reads a real 300-byte account row by its real 11-digit key")
        void acctFileReturnsARealRowByKey() {
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            String stored = fixtureRows(ACCTDATA_FIXTURE).get(0);
            assertThat(stored).hasSize(AccountRecord.RECORD_LENGTH);
            String key = stored.substring(AccountRecord.ACCT_ID_OFFSET,
                    AccountRecord.ACCT_ID_OFFSET + AccountRecord.ACCT_ID_LENGTH);
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(stored));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd,
                    key + " ".repeat(StatementGenerationJobB.KEY_LENGTH - key.length()),
                    StatementGenerationJobB.ACCTFILE_CALLER_KEY_LENGTH);

            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            String image = response.recordImage(AccountRecord.RECORD_LENGTH);
            assertThat(image).isEqualTo(stored);
            AccountRecord decoded = AccountRecord.decode(image, ASCII);
            assertThat(decoded.getAcctId()).isEqualTo(Long.parseLong(key));
            assertThat(decoded.raw(AccountRecord.SPAN_FILLER))
                    .as("CVACT01Y's FILLER X(178) at offset 122 is spaces in the real data too")
                    .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("TRNXFILE reads a row composed here from COSTM01.CPY's offsets - there is no fixture")
        void trnxFileReturnsARowComposedFromTheCopybook() {
            String dd = StatementGenerationJobB.TRNXFILE_DD;
            String cardNumber = fixtureRows(CARDXREF_FIXTURE).get(0)
                    .substring(0, CardXrefRecord.XREF_CARD_NUM_LENGTH);
            // Three decimal places offered to a S9(09)V99 receiver: COBOL truncates, and CobolDecimal is
            // the only route by which a value reaches the record, so DOWN is the only mode in play.
            BigDecimal offered = new BigDecimal("1234.567");
            String stored = trnxRow(cardNumber, "0000000000000042", offered);
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(datasetOf(dd), List.of(stored));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readNext(session, dd);

            assertThat(response.rc()).isEqualTo(FileStatus.OK);
            String image = response.recordImage(TrnxRecord.RECORD_LENGTH);
            assertThat(image).isEqualTo(stored);
            // The 32-byte composite key is the first two fields, exactly as CREASTMT.JCL's KEYS(32 0).
            assertThat(image.substring(TrnxRecord.TRNX_CARD_NUM_OFFSET,
                    TrnxRecord.TRNX_CARD_NUM_OFFSET + TrnxRecord.TRNX_CARD_NUM_LENGTH))
                    .isEqualTo(cardNumber);
            assertThat(image.substring(TrnxRecord.TRNX_ID_OFFSET,
                    TrnxRecord.TRNX_ID_OFFSET + TrnxRecord.TRNX_ID_LENGTH))
                    .isEqualTo("0000000000000042");
            TrnxRecord decoded = TrnxRecord.decode(image.getBytes(ASCII), ASCII);
            assertThat(decoded.readTrnxAmt())
                    .as("S9(09)V99 truncates the third decimal, because ROUNDED appears nowhere in the "
                            + "legacy source: RoundingMode.DOWN, and never a rounding-to-nearest mode")
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(decoded.readTrnxAmt().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            assertThat(decoded.readFiller())
                    .as("COSTM01's trailing FILLER X(20) at offset 330")
                    .isEqualTo(" ".repeat(TrnxRecord.FILLER_LENGTH));
            assertThat(response.fldt().substring(TrnxRecord.RECORD_LENGTH))
                    .isEqualTo(" ".repeat(StatementGenerationJobB.FLDT_LENGTH
                            - TrnxRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("the three derived fixtures are the real data: 50 rows each, at 36 / 500 / 300 bytes")
        void theDerivedFixturesAreTheRealShapes() {
            assertThat(fixtureRows(CARDXREF_FIXTURE)).hasSize(50)
                    .allSatisfy(r -> assertThat(r).hasSize(CARDXREF_STORED_ROW_WIDTH));
            assertThat(fixtureRows(CUSTDATA_FIXTURE)).hasSize(50)
                    .allSatisfy(r -> assertThat(r).hasSize(Stm03CustomerRecord.RECORD_LENGTH));
            assertThat(fixtureRows(ACCTDATA_FIXTURE)).hasSize(50)
                    .allSatisfy(r -> assertThat(r).hasSize(AccountRecord.RECORD_LENGTH));
        }
    }

    // =================================================================================================
    // The fake backend. An in-memory relation per dataset, driven through the subject's own statement
    // creators and extractors so what is exercised is the subject's SQL and its decoding, not a stub's
    // idea of them.
    // =================================================================================================

    /**
     * A stubbed {@link JdbcTemplate} backed by in-memory relations keyed by dataset name.
     */
    private static final class Backend {

        /** How many rows a keyed read may transfer - the subject's own duplicate-detection limit. */
        private static final int KEYED_LIMIT = 2;

        /** What each dataset holds, keyed by dataset name. */
        private final Map<String, List<String>> stored = new LinkedHashMap<>();

        /** The datasets that cannot be reached. */
        private final Set<String> failing = new LinkedHashSet<>();

        /** The datasets whose template yields no result object at all. */
        private final Set<String> yieldingNothing = new LinkedHashSet<>();

        /** The datasets that describe no record-image column. */
        private final Set<String> describingNoColumn = new LinkedHashSet<>();

        /** Every statement sent, in order. */
        private final List<String> statementsSent = new ArrayList<>();

        /** Every parameter bound to a keyed read, in order. */
        private final List<String> patternsBound = new ArrayList<>();

        /**
         * @param template the template to stub
         */
        Backend(JdbcTemplate template) {
            when(template.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(this::describe);
            when(template.query(any(PreparedStatementCreator.class),
                            ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenAnswer(this::preparedRead);
        }

        Backend storing(String dataset, List<String> rows) {
            stored.put(dataset, rows);
            return this;
        }

        Backend failing(String dataset) {
            failing.add(dataset);
            return this;
        }

        Backend yieldingNothing(String dataset) {
            yieldingNothing.add(dataset);
            return this;
        }

        Backend describingNoColumn(String dataset) {
            describingNoColumn.add(dataset);
            return this;
        }

        List<String> statementsSent() {
            return List.copyOf(statementsSent);
        }

        List<String> patternsBound() {
            return List.copyOf(patternsBound);
        }

        /**
         * Answers the metadata describe by driving the subject's own extractor over stubbed metadata.
         *
         * @param invocation the template call
         * @return the column name the subject read out of the metadata
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        private Object describe(InvocationOnMock invocation) throws SQLException {
            String sql = invocation.getArgument(0);
            statementsSent.add(sql);
            requireReachable(sql);
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            int columns = describingNoColumn.contains(datasetOf(sql)) ? 0 : 1;
            return extractor.extractData(describedResultSet(columns));
        }

        /**
         * Answers any prepared read: a keyed one by evaluating the composed predicate, a browse one by
         * driving the subject's own extractor over the row it should see.
         *
         * @param invocation the template call
         * @return what that read yields
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        private Object preparedRead(InvocationOnMock invocation) throws SQLException {
            PreparedStatementCreator creator = invocation.getArgument(0);
            Connection connection = mock(Connection.class);
            PreparedStatement prepared = mock(PreparedStatement.class);
            List<String> captured = new ArrayList<>();
            List<String> sql = new ArrayList<>();
            when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
                sql.add(prepare.getArgument(0));
                return prepared;
            });
            doAnswer(bind -> {
                captured.add(bind.getArgument(1));
                return null;
            }).when(prepared).setString(eq(1), anyString());
            creator.createPreparedStatement(connection);

            String statement = sql.get(0);
            statementsSent.add(statement);
            requireReachable(statement);
            if (yieldingNothing.contains(datasetOf(statement))) {
                return null;
            }
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            if (statement.contains("LIKE")) {
                String pattern = captured.get(0);
                patternsBound.add(pattern);
                return extractor.extractData(rowsResultSet(keyedMatches(statement, pattern)));
            }
            // A browse read: one row, the first or the first strictly after the bound image.
            String after = captured.isEmpty() ? null : captured.get(0);
            List<String> rows = rowsOf(statement);
            int index = nextBrowseRowIndex(rows, after);
            return extractor.extractData(rowsResultSet(
                    index >= 0 ? Arrays.asList(rows.get(index)) : List.of()));
        }

        /** The rows a keyed pattern selects, up to the subject's transfer limit. */
        private List<String> keyedMatches(String statement, String pattern) {
            Pattern matcher = likeAsRegex(pattern);
            List<String> matches = new ArrayList<>();
            for (String stored : rowsOf(statement)) {
                if (matches.size() == KEYED_LIMIT) {
                    break;
                }
                if (stored == null || matcher.matcher(stored).matches()) {
                    matches.add(stored);
                }
            }
            return matches;
        }

        /**
         * Which seeded row a browse read should see.
         *
         * <p>An index rather than the row itself, because a seeded {@code null} is a row that is present
         * and unreadable and has to stay distinguishable from no row at all.
         */
        private static int nextBrowseRowIndex(List<String> rows, String after) {
            for (int index = 0; index < rows.size(); index++) {
                String row = rows.get(index);
                if (after == null || (row != null && row.compareTo(after) > 0)) {
                    return index;
                }
            }
            return -1;
        }

        /** A result set carrying the given images, in order. */
        private static ResultSet rowsResultSet(List<String> images) throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            int[] cursor = {-1};
            when(resultSet.next()).thenAnswer(call -> ++cursor[0] < images.size());
            when(resultSet.getString(1)).thenAnswer(call ->
                    cursor[0] >= 0 && cursor[0] < images.size() ? images.get(cursor[0]) : null);
            return resultSet;
        }

        /** An empty result set whose metadata reports {@code columns} columns. */
        private static ResultSet describedResultSet(int columns) throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(columns);
            when(metaData.getColumnName(1)).thenReturn(columns >= 1 ? DESCRIBED_COLUMN : null);
            when(resultSet.getMetaData()).thenReturn(metaData);
            when(resultSet.next()).thenReturn(false);
            return resultSet;
        }

        private void requireReachable(String sql) {
            if (failing.contains(datasetOf(sql))) {
                throw new DataAccessResourceFailureException("the dataset cannot be reached");
            }
        }

        private List<String> rowsOf(String sql) {
            return stored.getOrDefault(datasetOf(sql), List.of());
        }

        /** Which seeded dataset a statement addresses, read from the delimited identifier it carries. */
        private static String datasetOf(String sql) {
            for (String candidate : List.of(TRNX_DS, XREF_DS, CUST_DS, ACCT_DS)) {
                if (sql.contains("\"" + candidate + "\"")) {
                    return candidate;
                }
            }
            // No seeded dataset is named in the statement. Returning a sentinel rather than throwing
            // keeps a statement this fake does not recognise observable as "no rows" instead of aborting
            // the test with a stack trace that hides which statement was actually sent.
            return "UNKNOWN";
        }

        /** Translates a SQL {@code LIKE} pattern into the regular expression it denotes. */
        private static Pattern likeAsRegex(String like) {
            StringBuilder regex = new StringBuilder(like.length() * 2);
            for (int index = 0; index < like.length(); index++) {
                char character = like.charAt(index);
                if (character == '\\' && index + 1 < like.length()) {
                    regex.append(Pattern.quote(String.valueOf(like.charAt(++index))));
                } else if (character == '_') {
                    regex.append('.');
                } else if (character == '%') {
                    regex.append(".*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(character)));
                }
            }
            return Pattern.compile(regex.toString(), Pattern.DOTALL);
        }
    }
}
