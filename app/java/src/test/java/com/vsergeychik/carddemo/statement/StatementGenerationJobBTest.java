package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Operation;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Request;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Response;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Session;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
        void nonPositiveOrOverlongKeyLengthIsRefused(int keyLength) {
            Request request = new Request(StatementGenerationJobB.CUSTFILE_DD, Operation.READ_K,
                    FileStatus.OK, Request.blankKey(), keyLength,
                    StatementGenerationJobB.SPACES_FLDT);
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(request::usedKey);
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

        @ParameterizedTest(name = "a mis-sized row of {0} is refused as a status, gate G19")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementGenerationJobBTest#allDds")
        void misSizedRowIsRefusedAsAStatus(String dd) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            // One byte short of the copybook width: the layout and the data disagree.
            backend(template).storing(datasetOf(dd),
                    List.of(row(digitKeyOf(dd), widthOf(dd) - 1)));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.supportedOperations(dd).contains(Operation.READ)
                    ? subject.readNext(session, dd)
                    : subject.readByKey(session, dd, digitKeyOf(dd), callerKeyOf(dd));
            assertThat(response.rc()).isEqualTo(StatementGenerationJobB.PERMANENT_ERROR_STATUS);
            assertThat(response.fldt()).isEqualTo(StatementGenerationJobB.SPACES_FLDT);
        }

        @Test
        @DisplayName("a keyed read matching more than one row returns the first with '00', as VSAM would")
        void duplicateKeyedMatchReturnsTheFirstWithOk() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            String dd = StatementGenerationJobB.ACCTFILE_DD;
            String key = "12345678901";
            backend(template).storing(ACCT_DS, List.of(
                    row(key, widthOf(dd)), row(key, widthOf(dd))));
            StatementGenerationJobB subject = subroutine(template);
            Session session = subject.newSession();
            subject.open(session, dd);

            Response response = subject.readByKey(session, dd, key, 11);
            assertThat(response.rc()).as("'22' is a WRITE-time condition no program compares against; "
                    + "inventing it here would be inventing a status").isEqualTo(FileStatus.OK);
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
            return Optional.<String>empty().orElse("UNKNOWN");
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
