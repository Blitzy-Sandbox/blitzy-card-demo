package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.FieldDiffer.DatasetOutput;
import com.vsergeychik.carddemo.parity.FieldDiffer.Diff;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffKind;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.Fingerprint;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Self-tests for {@link FieldDiffer}, the deterministic judge of this migration.
 *
 * <p>Every module's acceptance gate is stated as "diff count = 0", and {@link FieldDiffer} is what
 * computes that number. An untested judge is therefore the single most dangerous class in the build:
 * a differ that under-reports turns the gate into a rubber stamp, and the failure mode is silent by
 * construction, because a false-clean result looks exactly like a correct one. Nothing else in the
 * suite can catch it - the parity cases all consume this class, so any blindness in it is inherited
 * by all 560 of them at once. Hence this file, and hence its emphasis: most of what follows checks
 * that a difference the differ could plausibly smooth away is in fact <em>reported</em>.
 *
 * <h2>What is asserted, and why each one earns its place</h2>
 * <ol>
 *   <li><strong>Field granularity.</strong> A record with three wrong fields must yield
 *       <em>three</em> diffs, each naming its own COBOL field with that field's declared offset and
 *       length. One opaque "not equal" would leave a reviewer to find the offending byte by hand,
 *       which for a 300-byte record is exactly the work the differ exists to do.</li>
 *   <li><strong>Trailing spaces and leading zeros are values, not formatting.</strong> A COBOL
 *       {@code PIC X} field is space-padded to its declared width and a {@code PIC 9} field is
 *       zero-filled; both paddings are part of the value. With 2,795 {@code MOVE} statements across
 *       the 28 programs - truncating on the right for alphanumeric receivers and on the left for
 *       numeric ones - padding is the dominant parity risk in the whole codebase, well ahead of
 *       arithmetic. A differ that trimmed would be blind to most of it.</li>
 *   <li><strong>Signed fields: the stored byte form decides, and the decoded number explains.</strong>
 *       The zoned {@code DISPLAY} form overpunches the sign into the trailing byte, so the differ
 *       decodes through the codec - which lets a case state its expectation as {@code "194.00"} and
 *       match the canonical stored image {@code 00000001940&#123;}, and which is what puts a readable
 *       quantity in the explanation. What the decoding does <em>not</em> do is make two different byte
 *       images equal. The unsigned zone-F rendering {@code 000000019400} denotes the same quantity and
 *       is still reported as a difference, because a COBOL store into {@code PIC S9(10)V99} always
 *       overpunches and so that image is one no program in the codebase writes; a judge that accepted
 *       it would be blind to the very defect overpunching exists to expose. The bytes are the record
 *       contract; the number is the diagnosis. Both halves are pinned below -
 *       {@link SignedZonedFields#theStoredOverpunchImageDenotesTheDocumentedValue()} for the decoding,
 *       {@link SignedZonedFields#twoImagesOfTheSameValueAreStillADifference()} for the authority - and
 *       the arithmetic is checked against real fixture bytes because it is easy to get wrong by a
 *       factor of ten.</li>
 *   <li><strong>Total record width, and therefore {@code FILLER}.</strong> Omitting a {@code FILLER}
 *       span shifts every offset after it; a width check is the cheapest way to catch that and it is
 *       asserted to report once, against the record, rather than as a cascade of field noise.</li>
 *   <li><strong>The two normalisations, and only the two.</strong> Each must be inert unless the case
 *       declares it, must fire only for its own width pair, and must announce itself when it
 *       fires.</li>
 *   <li><strong>Determinism.</strong> The same comparison run twice must produce byte-identical
 *       ordered output, and no diff may ever be truncated from the report.</li>
 * </ol>
 *
 * <h2>Provenance of the data used here</h2>
 * <p>The record images below are real. The account row is read from {@code fixtures/acctdata.txt} on
 * the test classpath, whose first row opens
 * <code>00000000001Y00000001940&#123;00000020200&#123;00000010200&#123;2014-11-20</code>; the
 * cross-reference row is read from {@code fixtures/cardxref.txt}, whose rows measure exactly
 * <strong>36</strong> characters against the 50 its copybook declares. The 57-character
 * {@code USRSEC} row is transcribed from the ten in-stream rows of {@code app/jcl/DUSRSECJ.jcl}, the
 * job that seeds that dataset, because no {@code usrsec} fixture file exists - the seed data lives in
 * the JCL itself. Nothing under {@code app/cpy}, {@code app/cbl}, {@code app/jcl} or
 * {@code app/data} is opened at test runtime; the reference trees are the parity oracle and stay
 * read-only.
 *
 * <p>Field geometry is likewise never restated here. The three layouts used - 50-byte
 * {@link CardXrefRecord#LAYOUT}, 80-byte {@link SecUserRecord#LAYOUT} and 300-byte
 * {@link AccountRecord#LAYOUT} - are the model classes' own published, self-checking layouts, so a
 * transcription error could not agree with them by accident.
 *
 * <h2>Governing rules</h2>
 * <p>{@code review_rules} reports <strong>no user rules provided</strong> for this project, confirmed
 * for this file, so no project rule governs it and none has been invented. The enterprise practices
 * the Agent Action Plan puts in their place bind instead:
 * <ul>
 *   <li><strong>B1 / B2</strong> - JUnit Jupiter and AssertJ only, both arriving through
 *       {@code spring-boot-starter-test} under the Spring Boot 3.5.16 parent. No new dependency, no
 *       third-party copybook parser, no whole-document assertion library, no Lombok.</li>
 *   <li><strong>B3</strong> - the reference trees are never opened or written.</li>
 *   <li><strong>B4</strong> - no difference is normalised away to make an assertion pass; where a
 *       measured byte is surprising it is asserted as measured and explained.</li>
 *   <li><strong>B7</strong> - deterministic and non-interactive: no clock, no random, no ordering
 *       that depends on a hash-ordered collection, and {@link DeterminismAndCompleteness} asserts
 *       reproducibility outright.</li>
 *   <li><strong>B8</strong> - explicit over implicit: no wildcard imports (gate G52), the
 *       {@link Charset} is named at every byte boundary, and scale 2 with
 *       {@link RoundingMode#DOWN} is named at every numeric expectation (gates G23, G24).</li>
 *   <li><strong>B9</strong> - no static mutable state (gate G53): every static member here is
 *       {@code final} over an immutable type, and the differ under test is rebuilt per test
 *       instance.</li>
 * </ul>
 *
 * @see FieldDiffer
 * @see ParityCase
 */
@DisplayName("FieldDiffer - the deterministic judge, itself judged")
class FieldDifferSelfJudgedTest {

    // =================================================================================================
    // Charsets, identifiers and dataset binding keys.
    // =================================================================================================

    /** The fixtures are US-ASCII, stated rather than defaulted (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** An eight-character COBOL program name, the only shape {@link ParityCase} accepts. */
    private static final String PROGRAM = "CBACT01C";

    /** A case identifier in the mandated {@code case01}-{@code case20} range. */
    private static final String CASE_ID = "case01";

    /** Why the case exists; {@link ParityCase} requires a non-blank description. */
    private static final String DESCRIPTION = "FieldDiffer self-test";

    /** Binding key for the account master dataset. Never a literal dataset name (gate G46). */
    private static final String ACCOUNT = "ACCTFILE";

    /** Binding key for the card cross-reference dataset. */
    private static final String XREF = "CARDXREF";

    /** Binding key for the user security dataset. */
    private static final String USRSEC = "USRSEC";

    /** The pseudo-scope a return-code difference is reported against. */
    private static final String RETURN_CODE_SCOPE = "<return-code>";

    /** The pseudo-scope an emitted-message difference is reported against. */
    private static final String MESSAGES_SCOPE = "<messages>";

    /** The pseudo-field a whole-record difference is reported against. */
    private static final String RECORD_SCOPE_FIELD = "<record>";

    // =================================================================================================
    // Fixture locations and the transcribed USRSEC seed row.
    // =================================================================================================

    /** Classpath copy of {@code app/data/ASCII/acctdata.txt}: 50 rows of 300 characters. */
    private static final String ACCOUNT_FIXTURE = "fixtures/acctdata.txt";

    /** Classpath copy of {@code app/data/ASCII/cardxref.txt}: 50 rows of <strong>36</strong>. */
    private static final String XREF_FIXTURE = "fixtures/cardxref.txt";

    /**
     * The first of the ten {@code USRSEC} rows seeded in-stream by {@code app/jcl/DUSRSECJ.jcl},
     * transcribed verbatim: {@code SEC-USR-ID X(08)} + {@code SEC-USR-FNAME X(20)} +
     * {@code SEC-USR-LNAME X(20)} + {@code SEC-USR-PWD X(08)} + {@code SEC-USR-TYPE X(01)}, which is
     * 57 characters and stops short of the 80 the copybook declares.
     *
     * <p>The password span holds the literal word {@code PASSWORD}. That is demonstration seed data
     * committed to the repository in the JCL itself, carries no credential value, and is quoted here
     * only because the parity contract is byte-level and this span's content is part of it.
     */
    private static final String USRSEC_ROW_57 =
            "ADMIN001MARGARET            GOLD                PASSWORDA";

    /** Bytes the {@code USRSEC} normalisation must supply: {@code SEC-USR-FILLER PIC X(23)}. */
    private static final int USRSEC_FILLER_WIDTH = 23;

    /** Bytes the cross-reference normalisation must supply: {@code FILLER PIC X(14)}. */
    private static final int XREF_FILLER_WIDTH = 14;

    // =================================================================================================
    // COBOL field names, written as a fixture author writes them: verbatim, and case-sensitive.
    // =================================================================================================

    /** {@code XREF-CARD-NUM PIC X(16)} at offset 0. */
    private static final String XREF_CARD_NUM = "XREF-CARD-NUM";

    /** {@code XREF-CUST-ID PIC 9(09)} at offset 16. */
    private static final String XREF_CUST_ID = "XREF-CUST-ID";

    /** {@code XREF-ACCT-ID PIC 9(11)} at offset 25. */
    private static final String XREF_ACCT_ID = "XREF-ACCT-ID";

    /** The name the first unnamed {@code FILLER} span of a record is addressed by. */
    private static final String FILLER = "FILLER";

    /** {@code ACCT-ID PIC 9(11)} at offset 0. */
    private static final String ACCT_ID = "ACCT-ID";

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)} at offset 11. */
    private static final String ACCT_ACTIVE_STATUS = "ACCT-ACTIVE-STATUS";

    /** {@code ACCT-CURR-BAL PIC S9(10)V99} at offset 12, twelve characters wide. */
    private static final String ACCT_CURR_BAL = "ACCT-CURR-BAL";

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at offset 24. */
    private static final String ACCT_CREDIT_LIMIT = "ACCT-CREDIT-LIMIT";

    /** {@code ACCT-OPEN-DATE PIC X(10)} at offset 48. */
    private static final String ACCT_OPEN_DATE = "ACCT-OPEN-DATE";

    /**
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} at offset 58 - misspelled in
     * {@code app/cpy/CVACT01Y.cpy} and therefore misspelled here. Correcting it would leave the diff
     * hunting for a field the decoder never produces, which is a silent parity break rather than a
     * loud one.
     */
    private static final String ACCT_EXPIRAION_DATE = "ACCT-EXPIRAION-DATE";

    /** {@code SEC-USR-ID PIC X(08)} at offset 0. */
    private static final String SEC_USR_ID = "SEC-USR-ID";

    /** {@code SEC-USR-LNAME PIC X(20)} at offset 28. */
    private static final String SEC_USR_LNAME = "SEC-USR-LNAME";

    /** {@code SEC-USR-FILLER PIC X(23)} at offset 57 - named in the copybook, so named here. */
    private static final String SEC_USR_FILLER = "SEC-USR-FILLER";

    // =================================================================================================
    // The unit under test. An instance field, so JUnit's per-method instance gives every test its own
    // and nothing is shared (practice B9). Immutable in any case: the differ holds only its codec.
    // =================================================================================================

    /** The differ, over a codec that names US-ASCII explicitly. */
    private final FieldDiffer differ = FieldDiffer.forCharset(ASCII);

    // =================================================================================================
    // Builders. Deliberately thin: a helper that computed an expectation could agree with a wrong
    // implementation, so every expected value below is written out rather than derived.
    // =================================================================================================

    /** A case pinning the given expectations, return code 0, no messages, no normalisations. */
    private static ParityCase caseOf(ExpectedRecord... expected) {
        return caseOf(List.of(expected), 0, List.of(), List.of());
    }

    /** A case pinning the given expectations and the given normalisations. */
    private static ParityCase caseOf(List<Normalisation> normalisations, ExpectedRecord... expected) {
        return caseOf(List.of(expected), 0, List.of(), normalisations);
    }

    /**
     * The full case constructor, with the members this suite never varies fixed.
     *
     * <p>The expectations are pinned on the <strong>write</strong> channel, which is the channel
     * {@link #wrote(String, RecordLayout, String...)} fills, and every message is a variable-width
     * COBOL {@code DISPLAY} line. A declared normalisation is bound to the dataset it describes and
     * that dataset is seeded, because a normalisation pads the rows a case seeds - it is owned by the
     * seeding side and never by the comparison.
     */
    private static ParityCase caseOf(List<ExpectedRecord> expected,
                                     int returnCode,
                                     List<String> messages,
                                     List<Normalisation> normalisations) {
        return new ParityCase(PROGRAM, CASE_ID, DESCRIPTION, UnitKind.BATCH_JOB,
                seedsFor(expected, normalisations), Map.of(), null, null,
                expected, List.of(), returnCode, displayLines(messages),
                boundNormalisations(expected, normalisations));
    }

    /**
     * Each expected message as the {@code DISPLAY} line a batch program writes.
     *
     * @param messages the expected texts, in order
     * @return the expectations on the variable-width DISPLAY channel
     */
    private static List<EmittedMessage> displayLines(List<String> messages) {
        List<EmittedMessage> lines = new ArrayList<>(messages.size());
        for (String text : messages) {
            lines.add(new EmittedMessage(MessageChannel.DISPLAY_LINE, text));
        }
        return lines;
    }

    /**
     * Binds each declared normalisation to the dataset it describes among the expectations.
     *
     * @param expected the expectations, whose datasets are the candidates
     * @param declared the normalisations the case declares
     * @return one binding per declaration, in declaration order
     */
    private static List<DatasetNormalisation> boundNormalisations(List<ExpectedRecord> expected,
                                                                 List<Normalisation> declared) {
        List<DatasetNormalisation> bound = new ArrayList<>(declared.size());
        for (Normalisation kind : declared) {
            bound.add(new DatasetNormalisation(datasetFor(kind, expected), kind));
        }
        return bound;
    }

    /** The dataset a normalisation binds to: the expectation naming it, else its own first DD name. */
    private static String datasetFor(Normalisation kind, List<ExpectedRecord> expected) {
        for (ExpectedRecord expectation : expected) {
            if (kind.describes(expectation.dataset())) {
                return expectation.dataset();
            }
        }
        return kind.datasets().iterator().next();
    }

    /** Seeds each normalised dataset, since a declaration for an unseeded dataset can never fire. */
    private static Map<String, DatasetInput> seedsFor(List<ExpectedRecord> expected,
                                                     List<Normalisation> declared) {
        Map<String, DatasetInput> seeds = new LinkedHashMap<>();
        for (Normalisation kind : declared) {
            seeds.put(datasetFor(kind, expected),
                    DatasetInput.ofRows(List.of("x".repeat(kind.sourceWidth()))));
        }
        return seeds;
    }

    /**
     * One row as <strong>seeding</strong> leaves it: the pad applied once, where it is owned.
     *
     * @param dataset the dataset the row is seeded into
     * @param kind    the normalisation the case declares for it
     * @param row     the row at its fixture width
     * @return the row at its copybook width
     */
    private static String seeded(String dataset, Normalisation kind, String row) {
        return new DatasetNormalisation(dataset, kind).normaliseSeedRow(row);
    }

    /** An expectation pinning named fields only. */
    private static ExpectedRecord pinning(String dataset, int rowIndex, Map<String, String> fields) {
        return new ExpectedRecord(dataset, rowIndex, fields, null);
    }

    /** An expectation pinning the complete record image only. */
    private static ExpectedRecord pinningImage(String dataset, int rowIndex, String image) {
        return new ExpectedRecord(dataset, rowIndex, Map.of(), image);
    }

    /**
     * Field expectations in declaration order.
     *
     * <p>{@link LinkedHashMap} rather than {@link Map#of}: several assertions below turn on the order
     * a fixture declares its fields in, and {@code Map.of} has no defined iteration order.
     *
     * @param namesAndValues alternating field name and expected value
     * @return the expectations, in the order given
     */
    private static Map<String, String> fields(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("fields(...) takes name/value pairs, but was given "
                    + namesAndValues.length + " argument(s)");
        }
        Map<String, String> pinned = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            pinned.put(namesAndValues[index], namesAndValues[index + 1]);
        }
        return pinned;
    }

    /** One dataset's rows, encoded with the explicitly named test charset. */
    private static DatasetOutput output(String dataset, RecordLayout layout, String... rows) {
        return DatasetOutput.ofImages(dataset, layout, List.of(rows), ASCII);
    }

    /** A fingerprint carrying one written dataset, return code 0 and no emitted lines. */
    private static Fingerprint wrote(String dataset, RecordLayout layout, String... rows) {
        return Fingerprint.of(List.of(output(dataset, layout, rows)), List.of(), null, 0, List.of());
    }

    /** A fingerprint carrying an explicit return code and message list. */
    private static Fingerprint wrote(int returnCode, List<String> messages, DatasetOutput... outputs) {
        return Fingerprint.of(List.of(outputs), List.of(), null, returnCode, displayLines(messages));
    }

    // =================================================================================================
    // Record images. Read from the classpath fixtures, or transcribed from the seeding JCL.
    // =================================================================================================

    /** Row 1 of the account fixture: exactly 300 characters, sign overpunch intact. */
    private static String accountRow() {
        return fixtureRow(ACCOUNT_FIXTURE, AccountRecord.RECORD_LENGTH);
    }

    /** Row 1 of the cross-reference fixture as shipped: exactly 36 characters, not 50. */
    private static String xrefRow36() {
        return fixtureRow(XREF_FIXTURE, CardXrefRecord.FILLER_OFFSET);
    }

    /** Row 1 of the cross-reference fixture, hand-widened to its declared 50. */
    private static String xrefRow50() {
        return xrefRow36() + " ".repeat(XREF_FILLER_WIDTH);
    }

    /** The transcribed {@code USRSEC} seed row, hand-widened to its declared 80. */
    private static String usrsecRow80() {
        return USRSEC_ROW_57 + " ".repeat(USRSEC_FILLER_WIDTH);
    }

    /**
     * A copy of {@code row} with one span replaced, which is how a "wrong record" is produced without
     * disturbing any other byte.
     *
     * @param row the original image
     * @param span the span to overwrite
     * @param value the replacement, exactly the span's declared width
     * @return the modified image, the same total width as {@code row}
     */
    private static String replacing(String row, FieldSpan span, String value) {
        assertThat(value)
                .as("a replacement for %s must be exactly its declared width, or the record's total "
                        + "width would change and a different assertion would fire", span.describe())
                .hasSize(span.length());
        return row.substring(0, span.offset()) + value
                + row.substring(span.offset() + span.length());
    }

    /**
     * The first row of a classpath fixture, decoded with the explicitly named charset and checked
     * against its expected width so a fixture swap cannot silently weaken this suite.
     *
     * @param resource the classpath location, always under {@code fixtures/}
     * @param expectedWidth the width every row of that fixture is measured to have
     * @return the first row, without its line terminator
     */
    private static String fixtureRow(String resource, int expectedWidth) {
        List<String> rows = fixtureRows(resource);
        assertThat(rows).as("%s must carry at least one row", resource).isNotEmpty();
        assertThat(rows.get(0)).as("row 1 of %s", resource).hasSize(expectedWidth);
        return rows.get(0);
    }

    /**
     * Every row of a classpath fixture. Reads only the derived copy under {@code fixtures/}; the
     * authoritative {@code app/data/ASCII} tree is the parity oracle and is never opened (practice
     * B3).
     *
     * @param resource the classpath location
     * @return the rows, in file order, with line terminators removed
     */
    private static List<String> fixtureRows(String resource) {
        List<String> rows = new ArrayList<>();
        try (InputStream stream =
                     FieldDifferTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test classpath", resource).isNotNull();
            for (String line : new String(stream.readAllBytes(), ASCII).split("\n")) {
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not read " + resource, problem);
        }
        return rows;
    }

    /**
     * The differences this suite is about, excluding {@link DiffKind#INCOMPLETE_EXPECTATION}.
     *
     * <p>Every fixture in this file names one or two fields deliberately, because isolating a single
     * comparison behaviour is the whole method: a test about how a wrong balance is reported must not
     * also have to state the account id, the group id and the {@code FILLER}. Under the completeness
     * contract such an expectation is <em>also</em> reported as not accounting for its whole record,
     * which is a true finding about the fixture and a distraction from the behaviour under test.
     *
     * <p>Nothing hides behind this filter. The kind is proved reachable, proved to name every uncovered
     * span, and proved to count toward {@link DiffResult#count()} exactly like every other kind, in the
     * {@code Completeness} nest of {@code FieldDifferTest}; {@link #allKindsOf(DiffResult)} is the
     * unfiltered view this file uses where the claim is about producibility; and the gate itself reads
     * the unfiltered {@link DiffResult#count()}, so a partial fixture still fails a real module gate.
     *
     * @param result the comparison result
     * @return its differences about the output, in traversal order
     */
    private static List<Diff> outputDiffs(DiffResult result) {
        List<Diff> output = new ArrayList<>();
        for (Diff diff : result.entries()) {
            if (diff.kind() != DiffKind.INCOMPLETE_EXPECTATION) {
                output.add(diff);
            }
        }
        return output;
    }

    /**
     * How many differences the result carries about the output, on the same footing as
     * {@link #outputDiffs(DiffResult)}.
     *
     * @param result the comparison result
     * @return the count of differences about the output
     */
    private static int outputCount(DiffResult result) {
        return outputDiffs(result).size();
    }

    /**
     * Whether the result is clean about the output, on the same footing as
     * {@link #outputDiffs(DiffResult)}.
     *
     * @param result the comparison result
     * @return {@code true} when nothing about the output differs
     */
    private static boolean outputIsClean(DiffResult result) {
        return outputDiffs(result).isEmpty();
    }

    /**
     * Every kind the result carries, filtering nothing - the view a producibility claim needs.
     *
     * @param result the comparison result
     * @return every kind present, in traversal order
     */
    private static List<DiffKind> allKindsOf(DiffResult result) {
        List<DiffKind> kinds = new ArrayList<>();
        for (Diff diff : result.entries()) {
            kinds.add(diff.kind());
        }
        return kinds;
    }

    /** The kinds of every diff about the output, in traversal order. */
    private static List<DiffKind> kindsOf(DiffResult result) {
        List<DiffKind> kinds = new ArrayList<>();
        for (Diff diff : outputDiffs(result)) {
            kinds.add(diff.kind());
        }
        return kinds;
    }

    /** The single diff of a result that must carry exactly one. */
    private static Diff onlyDiff(DiffResult result) {
        assertThat(outputDiffs(result)).as("expected exactly one difference: %s", result.render())
                .hasSize(1);
        return outputDiffs(result).get(0);
    }


    // =================================================================================================
    // 1. A clean comparison. The baseline the gate is stated against.
    // =================================================================================================

    @Nested
    @DisplayName("A clean comparison reports a diff count of zero")
    class CleanComparison {

        @Test
        @DisplayName("every field pinned to its stored value gives count 0 and isClean")
        void everyFieldPinnedCorrectlyIsClean() {
            ParityCase parityCase = caseOf(pinning(XREF, 0, fields(
                    XREF_CARD_NUM, "0500024453765740",
                    XREF_CUST_ID, "000000050",
                    XREF_ACCT_ID, "00000000050",
                    FILLER, " ".repeat(XREF_FILLER_WIDTH))));

            DiffResult result =
                    differ.compare(parityCase, wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result)).isZero();
            assertThat(outputIsClean(result)).isTrue();
            assertThat(outputDiffs(result)).isEmpty();
            assertThat(parityCase.normalisations())
                    .as("a full-width row needs no pad, and none is declared")
                    .isEmpty();
            assertThat(result.render()).contains("0 differences", "CLEAN, the gate is satisfied");
        }

        @Test
        @DisplayName("a whole-record image pinned to the stored bytes is clean")
        void wholeRecordImagePinnedCorrectlyIsClean() {
            DiffResult result = differ.compare(
                    caseOf(pinningImage(XREF, 0, xrefRow50())),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputIsClean(result)).isTrue();
        }

        @Test
        @DisplayName("the real 300-byte account row is clean, misspelled field name and FILLER included")
        void realAccountRowIsClean() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_ID, "00000000001",
                            ACCT_ACTIVE_STATUS, "Y",
                            ACCT_CURR_BAL, "194.00",
                            ACCT_CREDIT_LIMIT, "2020.00",
                            ACCT_OPEN_DATE, "2014-11-20",
                            ACCT_EXPIRAION_DATE, "2025-05-20",
                            FILLER, " ".repeat(AccountRecord.FILLER_LENGTH)))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("a unit that wrote nothing and set return code 0 is clean")
        void aUnitThatWroteNothingIsClean() {
            DiffResult result = differ.compare(caseOf(), Fingerprint.ofReturnCode(0));

            assertThat(outputIsClean(result)).isTrue();
            assertThat(result.program()).isEqualTo(PROGRAM);
            assertThat(result.caseId()).isEqualTo(CASE_ID);
        }
    }


    // =================================================================================================
    // 2. Field granularity. The property the whole class exists to deliver (gate G17).
    // =================================================================================================

    @Nested
    @DisplayName("Comparison is field by field, never whole strings")
    class FieldGranularity {

        @Test
        @DisplayName("one wrong field yields exactly one diff, located by offset and length")
        void oneWrongFieldYieldsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000051"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.dataset()).isEqualTo(XREF);
            assertThat(diff.rowIndex()).isZero();
            assertThat(diff.fieldName()).isEqualTo(XREF_CUST_ID);
            assertThat(diff.offset()).isEqualTo(CardXrefRecord.XREF_CUST_ID_OFFSET);
            assertThat(diff.length()).isEqualTo(CardXrefRecord.XREF_CUST_ID_LENGTH);
            // XREF-CUST-ID names one customer. The difference is reported in full - dataset, row,
            // field, offset, width - with the two values rendered as digests that differ.
            assertThat(diff.expected())
                    .doesNotContain("000000051")
                    .contains("<identifier>", "len=9", "sha256=");
            assertThat(diff.actual())
                    .doesNotContain("000000050")
                    .contains("<identifier>", "len=9", "sha256=");
            assertThat(diff.expected()).isNotEqualTo(diff.actual());
        }

        @Test
        @DisplayName("three wrong fields yield THREE diffs, one per field - not one for the record")
        void threeWrongFieldsYieldThreeDiffs() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(
                            XREF_CARD_NUM, "9999999999999999",
                            XREF_CUST_ID, "000000051",
                            XREF_ACCT_ID, "00000000051"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result)).isEqualTo(3);
            assertThat(outputDiffs(result))
                    .extracting(Diff::fieldName)
                    .containsExactly(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID);
            assertThat(outputDiffs(result)).extracting(Diff::kind)
                    .containsOnly(DiffKind.VALUE_MISMATCH);
        }

        @Test
        @DisplayName("diffs come out in COPYBOOK order, whatever order the fixture declares them")
        void diffOrderIsCopybookOrderNotFixtureOrder() {
            // Declared back to front on purpose: a reviewer reads the failure down the record the same
            // way the copybook reads, whichever way the fixture author happened to type it.
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(
                            XREF_ACCT_ID, "00000000051",
                            XREF_CUST_ID, "000000051",
                            XREF_CARD_NUM, "9999999999999999"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputDiffs(result))
                    .extracting(Diff::fieldName)
                    .containsExactly(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID);
        }

        @Test
        @DisplayName("a whole-record expectation still localises each difference to its own span")
        void wholeRecordImageDiffLocalisesToSpans() {
            String wrong = replacing(
                    replacing(xrefRow50(), CardXrefRecord.XREF_CUST_ID, "000000051"),
                    CardXrefRecord.XREF_ACCT_ID, "00000000051");

            DiffResult result = differ.compare(
                    caseOf(pinningImage(XREF, 0, wrong)),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result)).isEqualTo(2);
            assertThat(outputDiffs(result))
                    .extracting(Diff::fieldName)
                    .containsExactly(XREF_CUST_ID, XREF_ACCT_ID);
            assertThat(outputDiffs(result)).extracting(Diff::offset).containsExactly(
                    CardXrefRecord.XREF_CUST_ID_OFFSET, CardXrefRecord.XREF_ACCT_ID_OFFSET);
        }

        @Test
        @DisplayName("a field wrong in both fields and expectedBytes counts once, not twice")
        void aFieldPinnedTwiceIsCountedOnce() {
            String wrong = replacing(xrefRow50(), CardXrefRecord.XREF_CUST_ID, "000000051");

            DiffResult result = differ.compare(
                    caseOf(new ExpectedRecord(XREF, 0, fields(XREF_CUST_ID, "000000051"), wrong)),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result))
                    .as("the diff count must count distinct findings: %s", result.render())
                    .isEqualTo(1);
            assertThat(onlyDiff(result).fieldName()).isEqualTo(XREF_CUST_ID);
        }
    }


    // =================================================================================================
    // 3. PIC X - trailing spaces are part of the value.
    // =================================================================================================

    @Nested
    @DisplayName("PIC X comparison is at full declared width and never trims")
    class AlphanumericPadding {

        @Test
        @DisplayName("an expectation short of the declared width IS a difference")
        void anExpectationShortOfTheDeclaredWidthIsADifference() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0, fields(SEC_USR_LNAME, "GOLD"))),
                    wrote(USRSEC, SecUserRecord.LAYOUT,
                            seeded(USRSEC, Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            // SEC-USR-LNAME is a person's name, so both sides render as a class, a length and a digest.
            // The lengths are the finding - 4 against the span's 20 - and they are both still stated.
            assertThat(diff.actual())
                    .doesNotContain("GOLD")
                    .contains("<personal>", "len=20", "sha256=");
            assertThat(diff.expected()).contains("<personal>", "len=4");
            assertThat(diff.explanation())
                    .contains("differ ONLY in trailing spaces")
                    .contains("space-padded to its declared width");
        }

        @Test
        @DisplayName("the same value written out to the declared width is clean")
        void theSameValueAtFullWidthIsClean() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0, fields(
                                    SEC_USR_ID, "ADMIN001",
                                    SEC_USR_LNAME, "GOLD                "))),
                    wrote(USRSEC, SecUserRecord.LAYOUT,
                            seeded(USRSEC, Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)));

            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("render makes the invisible visible: trailing spaces counted, length stated")
        void renderCountsTrailingSpacesAndStatesLength() {
            // Asserted on ACCT-GROUP-ID rather than on a name: this is a test about how a value is
            // RENDERED, and a classified value renders as a class, a length and a digest, which has no
            // trailing space left in it to count. ACCT-GROUP-ID is PIC X(10), unclassified, and ten
            // spaces in the fixture - so the observed side is nothing but the padding this test is about.
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(AccountRecord.ACCT_GROUP_ID_NAME, "GOLD"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(onlyDiff(result).render())
                    .contains("'GOLD' (len=4)")
                    .contains("10 trailing space(s) (len=10)")
                    .contains("offset " + AccountRecord.ACCT_GROUP_ID_OFFSET)
                    .contains("length " + AccountRecord.ACCT_GROUP_ID_LENGTH);
        }

        @Test
        @DisplayName("a FILLER span is a comparable field, so a non-space byte in it is reported")
        void aFillerSpanIsComparable() {
            String polluted = replacing(xrefRow50(), CardXrefRecord.FILLER,
                    "X" + " ".repeat(XREF_FILLER_WIDTH - 1));

            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(FILLER, " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, polluted));

            assertThat(onlyDiff(result).fieldName()).isEqualTo(FILLER);
        }
    }


    // =================================================================================================
    // 4. PIC 9 - leading zeros are part of the value.
    // =================================================================================================

    @Nested
    @DisplayName("PIC 9 comparison keeps leading zeros significant")
    class NumericZeroFill {

        @Test
        @DisplayName("an unpadded numeric expectation IS a difference, and is named as one")
        void anUnpaddedNumericExpectationIsADifference() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "50"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.explanation())
                    .contains("unsigned zoned PIC 9")
                    .contains("differ ONLY in leading zeros")
                    .contains("zero-filled on the left for PIC 9");
        }

        @Test
        @DisplayName("the zero-filled image of the same number is clean")
        void theZeroFilledImageIsClean() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputIsClean(result)).isTrue();
        }
    }


    // =================================================================================================
    // 5. PIC S9(p)V99 - decoded through the codec's overpunch reader into BigDecimal at scale 2 with
    //    RoundingMode.DOWN (gates G23, G24). The decoding is what makes a decimal expectation matchable
    //    and what makes an explanation readable; it is not a licence to treat two different stored
    //    images as equal. Parity is decided on the bytes.
    // =================================================================================================

    @Nested
    @DisplayName("Signed zoned fields - the stored byte form is authoritative, the decoded number "
            + "explains it")
    class SignedZonedFields {

        @Test
        @DisplayName("the stored image 00000001940{ denotes 194.00 - twelve digits, no sign byte")
        void theStoredOverpunchImageDenotesTheDocumentedValue() {
            // Stated directly as well as through a comparison, because this is the one arithmetic in
            // the differ that is easy to get wrong by a factor of ten. PIC S9(10)V99 occupies twelve
            // characters - ten integer digits and two fraction digits - and the sign consumes no
            // position of its own, which is the only reading under which CVACT01Y sums to its
            // documented 300 bytes. Reading the trailing byte as a separate sign character would
            // leave eleven digits and yield 1940.00.
            assertThat(differ.codec().decodeSignedScaled("00000001940{",
                    CobolDecimal.MONETARY_SCALE))
                    .isEqualByComparingTo(new BigDecimal("194.00"));

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_CURR_BAL, "194.00",
                            ACCT_CREDIT_LIMIT, "2020.00"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("two images of the same value are still a difference: the bytes are the contract")
        void twoImagesOfTheSameValueAreStillADifference() {
            // The unsigned zoned form and the positive-overpunch form denote the same number and are
            // not the same bytes, and the bytes are what the record contract is written in. A COBOL
            // store into PIC S9(10)V99 overpunches the sign into the trailing character, so the
            // unsigned rendering is one no COBOL program writes - and a judge that accepted it would
            // be unable to see the very defect that overpunching exists to make visible. Reporting it
            // is therefore not an invented difference; it is the gate working.
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "000000019400"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.explanation())
                    .contains("difference in stored FORM, not in quantity")
                    .contains("unsigned zone-F rendering")
                    .contains("194.00");
        }

        @ParameterizedTest(name = "{0} decodes to {1}, canonical={2}")
        @CsvSource({
            "00000001940{, 194.00, true",
            "00000001940A, 194.01, true",
            "000000019400, 194.00, false",
            "00000001940}, -194.00, true",
            "00000001940J, -194.01, true"
        })
        @DisplayName("every overpunch form the fixtures and the codec produce is compared correctly")
        void everyOverpunchFormComparesCorrectly(String image, String value, boolean canonical) {
            String row = replacing(accountRow(), AccountRecord.SPAN_ACCT_CURR_BAL, image);
            Fingerprint fingerprint = wrote(ACCOUNT, AccountRecord.LAYOUT, row);

            assertThat(differ.codec().decodeSignedScaled(image, CobolDecimal.MONETARY_SCALE))
                    .as("%s denotes %s", image, value)
                    .isEqualByComparingTo(new BigDecimal(value));
            assertThat(outputIsClean(differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, image))), fingerprint)))
                    .as("pinning the exact bytes %s must always be clean", image)
                    .isTrue();

            // A literal expectation is encoded to the one image a COBOL store of that value produces,
            // so it is satisfied by the canonical form and by nothing else. Four of these five images
            // ARE that form. The fifth is the unsigned zone-F rendering of the same quantity, and the
            // literal does not accept it - that is the false pass the byte-level gate closes, not a
            // false failure.
            DiffResult againstTheLiteral = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, value))), fingerprint);
            if (canonical) {
                assertThat(outputIsClean(againstTheLiteral))
                        .as("%s is the canonical image of %s", image, value)
                        .isTrue();
            } else {
                assertThat(onlyDiff(againstTheLiteral).explanation())
                        .as("%s denotes %s but is not the image a COBOL store writes", image, value)
                        .contains("unsigned zone-F rendering");
            }

            BigDecimal offByAPenny = new BigDecimal(value).add(new BigDecimal("0.01"));
            assertThat(outputCount(differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, offByAPenny.toPlainString()))),
                    fingerprint)))
                    .as("%s must NOT compare equal to %s", image, offByAPenny)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a value wrong by a factor of ten is reported, and the message names both sides")
        void aFactorOfTenIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "1940.00"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.explanation())
                    .contains("differs numerically: expected 1940.00, decoded 194.00")
                    .contains("at scale " + CobolDecimal.MONETARY_SCALE)
                    .contains("with rounding " + RoundingMode.DOWN)
                    .contains("truncation toward zero, because ROUNDED appears zero times");
        }

        @Test
        @DisplayName("the overpunch byte is named in the failure text, with its code point")
        void theOverpunchByteIsMadeVisible() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "1940.00"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(onlyDiff(result).explanation())
                    .contains("The trailing byte is '{' (0x7B)")
                    .contains("POSITIVE sign overpunch");
        }

        @Test
        @DisplayName("an undecodable field is REPORTED, not thrown - one bad byte cannot hide the rest")
        void anUndecodableFieldIsReportedNotThrown() {
            String row = replacing(accountRow(), AccountRecord.SPAN_ACCT_CURR_BAL, "00000001940*");

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_CURR_BAL, "194.00",
                            ACCT_EXPIRAION_DATE, "1999-01-01"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, row));

            // Two diffs, not an exception and not one: an escaping exception would abandon the
            // comparison and hide every difference after it, which is the opposite of judging.
            assertThat(kindsOf(result))
                    .containsExactly(DiffKind.UNDECODABLE_FIELD, DiffKind.VALUE_MISMATCH);
            assertThat(outputDiffs(result).get(0).explanation())
                    .contains("not a valid signed zoned DISPLAY image")
                    .contains("neither a digit nor a recognised sign overpunch");
        }

        @Test
        @DisplayName("an expectation that is neither an image nor a literal is REPORTED, not thrown")
        void aMalformedExpectationIsReportedNotThrown() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "no-such-value"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MALFORMED_EXPECTATION);
            assertThat(diff.explanation())
                    .contains("neither a zoned image")
                    .contains("nor a decimal literal")
                    .contains("denotes 194.00");
        }

        @Test
        @DisplayName("no monetary comparison uses a half-rounding or a directional mode")
        void theRoundingPolicyIsTruncation() {
            // ROUNDED appears zero times in all 28 COBOL programs, so COBOL truncates on store and
            // truncation is the only faithful choice here.
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
        }
    }


    // =================================================================================================
    // 6. Total record width, which is how an omitted FILLER is caught (gates G19, G21).
    // =================================================================================================

    @Nested
    @DisplayName("Total record width is checked before any field of the record is compared")
    class RecordWidthAndFiller {

        @Test
        @DisplayName("a record short by its trailing FILLER is reported as RECORD_WIDTH_MISMATCH")
        void anOmittedFillerIsAWidthMismatch() {
            String withoutFiller = accountRow().substring(0, AccountRecord.FILLER_OFFSET);

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_ID, "00000000001"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, withoutFiller));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
            assertThat(diff.fieldName()).isEqualTo(RECORD_SCOPE_FIELD);
            assertThat(diff.offset()).isZero();
            assertThat(diff.length()).isEqualTo(AccountRecord.RECORD_LENGTH);
            assertThat(diff.expected()).isEqualTo(String.valueOf(AccountRecord.RECORD_LENGTH));
            assertThat(diff.actual()).isEqualTo(String.valueOf(AccountRecord.FILLER_OFFSET));
            assertThat(diff.explanation())
                    .contains("a FILLER was omitted, which shifts every offset after it")
                    .contains("The case declares no normalisation")
                    .contains("must not be padded away");
        }

        @Test
        @DisplayName("a width mismatch skips the field pass, so it is ONE finding and not a cascade")
        void aWidthMismatchSkipsTheFieldPass() {
            String withoutFiller = accountRow().substring(0, AccountRecord.FILLER_OFFSET);

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_ID, "99999999999",
                            ACCT_ACTIVE_STATUS, "N",
                            ACCT_OPEN_DATE, "1999-01-01"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, withoutFiller));

            assertThat(kindsOf(result)).containsExactly(DiffKind.RECORD_WIDTH_MISMATCH);
            assertThat(outputDiffs(result).get(0).explanation())
                    .contains("Field comparison is skipped for this record");
        }

        @Test
        @DisplayName("an expected image of the wrong width is reported against the expectation")
        void anExpectedImageOfTheWrongWidthIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinningImage(ACCOUNT, 0,
                            accountRow().substring(0, AccountRecord.FILLER_OFFSET))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
            assertThat(diff.explanation())
                    .contains("the expected record image is 122 byte(s) wide")
                    .contains("The expectation itself is the wrong width here");
        }

        @Test
        @DisplayName("a record one byte too WIDE is reported too, never quietly truncated")
        void anOverWideRecordIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50() + " "));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @ParameterizedTest(name = "{0} declares {1} bytes")
        @CsvSource({
            "CardXref, 50",
            "SecUser, 80",
            "Account, 300"
        })
        @DisplayName("the declared widths the differ enforces are the copybooks' own")
        void declaredWidthsAreTheCopybooksOwn(String record, int width) {
            Map<String, RecordLayout> layouts = new LinkedHashMap<>();
            layouts.put("CardXref", CardXrefRecord.LAYOUT);
            layouts.put("SecUser", SecUserRecord.LAYOUT);
            layouts.put("Account", AccountRecord.LAYOUT);

            assertThat(layouts.get(record).recordLength()).isEqualTo(width);
        }
    }


    // =================================================================================================
    // 7. The two normalisations - inert unless declared, and never a third.
    // =================================================================================================

    @Nested
    @DisplayName("Exactly two width normalisations exist, and this class owns both")
    class TheTwoNormalisations {

        @Test
        @DisplayName("cardxref 36 to 50: with the normalisation declared the compare is clean")
        void cardxrefPadMakesTheCompareClean() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.CARDXREF_FILLER_PAD_36_TO_50),
                            pinning(XREF, 0, fields(
                                    XREF_CARD_NUM, "0500024453765740",
                                    XREF_CUST_ID, "000000050",
                                    XREF_ACCT_ID, "00000000050",
                                    FILLER, " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, seeded(XREF,
                            Normalisation.CARDXREF_FILLER_PAD_36_TO_50, xrefRow36())));

            // The FILLER pin is the point: it proves the pad supplied fourteen SPACES, which is what
            // COBOL writes into a FILLER carrying no VALUE.
            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("cardxref 36 to 50: without it the very same row is a width mismatch")
        void cardxrefRowWithoutTheNormalisationIsAWidthMismatch() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow36()));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("USRSEC 57 to 80: with the normalisation declared the compare is clean")
        void usrsecPadMakesTheCompareClean() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0, fields(
                                    SEC_USR_ID, "ADMIN001",
                                    SEC_USR_LNAME, "GOLD                ",
                                    SEC_USR_FILLER, " ".repeat(USRSEC_FILLER_WIDTH)))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, seeded(USRSEC,
                            Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)));

            assertThat(outputCount(result)).as(result.render()).isZero();
            assertThat(usrsecRow80()).hasSize(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("USRSEC 57 to 80: without it the very same row is a width mismatch")
        void usrsecRowWithoutTheNormalisationIsAWidthMismatch() {
            DiffResult result = differ.compare(
                    caseOf(pinning(USRSEC, 0, fields(SEC_USR_ID, "ADMIN001"))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW_57));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("a normalisation fires ONLY for its own width pair, never as a general escape hatch")
        void aNormalisationFiresOnlyForItsOwnWidthPair() {
            // Two barriers, and each refuses the escape hatch on its own. The first is the binding:
            // the cross-reference pad names the DD names that reach CVACT03Y's record, and USRSEC is
            // not one of them, so the declaration itself cannot be written.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DatasetNormalisation(USRSEC,
                            Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                    .withMessageContaining("does not describe");

            // The second is the width pair: even asked for the dataset it does describe, the pad
            // refuses a row that is neither 36 nor 50 rather than padding an arbitrary width up.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Normalisation.CARDXREF_FILLER_PAD_36_TO_50
                            .normaliseSeedRow(USRSEC_ROW_57, XREF))
                    .withMessageContaining("cannot be normalised")
                    .withMessageContaining("pads 36 to 50");

            // So the 57-byte row reaches the comparison as it stands: one width mismatch.
            DiffResult result = differ.compare(
                    caseOf(pinning(USRSEC, 0, fields(SEC_USR_ID, "ADMIN001"))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW_57));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("a pad that fires announces itself, naming widths, copybook and absent span")
        void aPadThatFiresAnnouncesItself() {
            ParityCase parityCase = caseOf(List.of(Normalisation.CARDXREF_FILLER_PAD_36_TO_50),
                    pinning(XREF, 0, fields(XREF_CUST_ID, "000000050")));

            DiffResult result = differ.compare(parityCase,
                    wrote(XREF, CardXrefRecord.LAYOUT, seeded(XREF,
                            Normalisation.CARDXREF_FILLER_PAD_36_TO_50, xrefRow36())));

            assertThat(outputIsClean(result)).isTrue();

            // The pad is applied by the seeding side, so its announcement is the case's own
            // declaration rather than a note appended to the verdict - and that declaration carries
            // every fact a reviewer needs: which dataset, which widths, how much was added, and out
            // of which copybook's absent span.
            assertThat(parityCase.normalisations()).singleElement().satisfies(declared -> {
                assertThat(declared.dataset()).isEqualTo(XREF);
                assertThat(declared.kind()).isEqualTo(Normalisation.CARDXREF_FILLER_PAD_36_TO_50);
                assertThat(declared.kind().sourceWidth()).isEqualTo(36);
                assertThat(declared.kind().targetWidth()).isEqualTo(50);
                assertThat(declared.kind().padWidth()).isEqualTo(14);
                assertThat(declared.kind().copybook()).isEqualTo("CVACT03Y");
                assertThat(declared.kind().absentSpan()).isEqualTo("FILLER PIC X(14)");
            });
        }

        @Test
        @DisplayName("fifty padded rows need ONE declaration, not fifty")
        void repeatedPaddingNeedsOneDeclaration() {
            List<ExpectedRecord> expectations = new ArrayList<>();
            for (int row = 0; row < 3; row++) {
                expectations.add(pinning(XREF, row, fields(FILLER, " ".repeat(XREF_FILLER_WIDTH))));
            }
            ParityCase parityCase = caseOf(expectations, 0, List.of(),
                    List.of(Normalisation.CARDXREF_FILLER_PAD_36_TO_50));
            DatasetNormalisation declared = parityCase.normalisations().get(0);

            // One declaration, applied to every row of its dataset in seeding order - and idempotent,
            // so a row already at its copybook width passes through untouched rather than growing.
            List<String> seededRows = declared.normaliseSeedRows(
                    List.of(xrefRow36(), xrefRow36(), xrefRow50()));

            DiffResult result = differ.compare(parityCase,
                    wrote(XREF, CardXrefRecord.LAYOUT, seededRows.get(0), seededRows.get(1),
                            seededRows.get(2)));

            assertThat(outputIsClean(result)).isTrue();
            assertThat(parityCase.normalisations()).hasSize(1);
            assertThat(seededRows).allSatisfy(row -> assertThat(row).hasSize(50));
        }

        @Test
        @DisplayName("only two normalisations exist, and each carries its own evidence")
        void onlyTwoNormalisationsExist() {
            assertThat(Normalisation.values()).hasSize(2);

            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.sourceWidth()).isEqualTo(36);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.targetWidth()).isEqualTo(50);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.padWidth())
                    .isEqualTo(XREF_FILLER_WIDTH);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.copybook()).isEqualTo("CVACT03Y");
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.absentSpan())
                    .isEqualTo("FILLER PIC X(14)");

            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth()).isEqualTo(57);
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.targetWidth()).isEqualTo(80);
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.padWidth())
                    .isEqualTo(USRSEC_FILLER_WIDTH);
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.copybook()).isEqualTo("CSUSR01Y");
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.absentSpan())
                    .isEqualTo("SEC-USR-FILLER PIC X(23)");
        }

        @ParameterizedTest(name = "{0} rows measure {1} bytes")
        @CsvSource({
            "fixtures/acctdata.txt, 300",
            "fixtures/carddata.txt, 150",
            "fixtures/cardxref.txt, 36",
            "fixtures/custdata.txt, 500",
            "fixtures/dailytran.txt, 350",
            "fixtures/discgrp.txt, 50",
            "fixtures/tcatbal.txt, 50",
            "fixtures/trancatg.txt, 60",
            "fixtures/trantype.txt, 60"
        })
        @DisplayName("cardxref is the ONLY fixture that deviates, so no third normalisation is needed")
        void cardxrefIsTheOnlyDeviatingFixture(String resource, int width) {
            assertThat(fixtureRows(resource))
                    .as("every row of %s", resource)
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row).hasSize(width));
        }
    }


    // =================================================================================================
    // 8. The COBOL RETURN-CODE, which becomes the batch exit status.
    // =================================================================================================

    @Nested
    @DisplayName("The RETURN-CODE is compared, because it is the batch exit status")
    class ReturnCode {

        @Test
        @DisplayName("a differing return code is reported against its own pseudo-scope")
        void aDifferingReturnCodeIsReported() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 8, List.of(), List.of()), Fingerprint.ofReturnCode(0));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RETURN_CODE_MISMATCH);
            assertThat(diff.dataset()).isEqualTo(RETURN_CODE_SCOPE);
            assertThat(diff.fieldName()).isEqualTo("RETURN-CODE");
            assertThat(diff.rowIndex()).isEqualTo(Diff.NOT_APPLICABLE);
            assertThat(diff.offset()).isEqualTo(Diff.NOT_APPLICABLE);
            assertThat(diff.length()).isEqualTo(Diff.NOT_APPLICABLE);
            assertThat(diff.expected()).isEqualTo("8");
            assertThat(diff.actual()).isEqualTo("0");
            assertThat(diff.explanation())
                    .contains("the case expects RETURN-CODE 8 but the unit reported 0")
                    .contains(FileStatus.APPL_EOF + " (end of file)")
                    .contains("JCL COND gating");
        }

        @ParameterizedTest(name = "return code {0}")
        @ValueSource(ints = {0, 3, 4, 8, 12, 16})
        @DisplayName("every return code this migration produces compares equal to itself")
        void everyProducedReturnCodeComparesEqualToItself(int returnCode) {
            assertThat(outputIsClean(differ.compare(
                    caseOf(List.of(), returnCode, List.of(), List.of()),
                    Fingerprint.ofReturnCode(returnCode)))).isTrue();

            int different = returnCode == 0 ? 8 : 0;
            assertThat(outputCount(differ.compare(
                    caseOf(List.of(), returnCode, List.of(), List.of()),
                    Fingerprint.ofReturnCode(different)))).isEqualTo(1);
        }
    }


    // =================================================================================================
    // 9. Emitted lines, compared positionally and byte-exactly.
    // =================================================================================================

    @Nested
    @DisplayName("Emitted lines are compared positionally and byte-exactly")
    class EmittedMessages {

        @Test
        @DisplayName("a differing count is reported when the shared prefix matches")
        void aDifferingCountIsReported() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("FIRST")));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MESSAGE_COUNT_MISMATCH);
            assertThat(diff.dataset()).isEqualTo(MESSAGES_SCOPE);
            assertThat(diff.explanation())
                    .contains("expects 2 emitted line(s) but the unit emitted 1");
        }

        @Test
        @DisplayName("a differing line is reported at its own position")
        void aDifferingLineIsReportedAtItsPosition() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("FIRST", "OTHER")));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MESSAGE_MISMATCH);
            assertThat(diff.rowIndex()).isEqualTo(1);
            assertThat(diff.expected()).isEqualTo("SECOND");
            assertThat(diff.actual()).isEqualTo("OTHER");
        }

        @Test
        @DisplayName("count and position are both reported: a reviewer needs both")
        void countAndPositionAreBothReported() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("OTHER")));

            assertThat(kindsOf(result))
                    .containsExactly(DiffKind.MESSAGE_COUNT_MISMATCH, DiffKind.MESSAGE_MISMATCH);
        }

        @Test
        @DisplayName("two file-status lines get a hint narrowing the difference to the status image")
        void fileStatusLinesGetATargetedHint() {
            // The trailing NNNN is genuinely part of the COBOL literal, not a placeholder, so a real
            // line reads FILE STATUS IS: NNNN0000.
            assertThat(FileStatus.toDisplayLine(FileStatus.OK)).isEqualTo("FILE STATUS IS: NNNN0000");

            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of(FileStatus.toDisplayLine(FileStatus.NOT_FOUND)),
                            List.of()),
                    wrote(0, List.of(FileStatus.toDisplayLine(FileStatus.OK))));

            assertThat(onlyDiff(result).explanation())
                    .contains("Both lines are file-status DISPLAY lines")
                    .contains(FileStatus.DISPLAY_PREFIX)
                    .contains("IO-STATUS-04 image differs: expected '0023', observed '0000'")
                    .contains("Emit these lines through FileStatus.toDisplayLine");
        }

        @Test
        @DisplayName("an empty string is a legitimate expected line - COBOL DISPLAY emits one")
        void anEmptyLineIsALegitimateExpectation() {
            assertThat(outputIsClean(differ.compare(
                    caseOf(List.of(), 0, List.of(""), List.of()),
                    wrote(0, List.of(""))))).isTrue();

            assertThat(outputCount(differ.compare(
                    caseOf(List.of(), 0, List.of(""), List.of()),
                    wrote(0, List.of(" "))))).isEqualTo(1);
        }
    }


    // =================================================================================================
    // 10. Missing and extra records. Both count toward the diff count.
    // =================================================================================================

    @Nested
    @DisplayName("A record that is absent, and a record that should not be there, both count")
    class MissingAndExtraRecords {

        @Test
        @DisplayName("an expectation against a dataset the unit never wrote is one diff")
        void anUnwrittenDatasetIsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_ID, "00000000001"))),
                    Fingerprint.ofReturnCode(0));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MISSING_RECORD);
            assertThat(diff.fieldName()).isEqualTo(RECORD_SCOPE_FIELD);
            assertThat(diff.actual()).isNull();
            assertThat(diff.explanation())
                    .contains("wrote no record at all to dataset " + ACCOUNT)
                    .contains("no output dataset at all");
        }

        @Test
        @DisplayName("an expectation past the last written row is a missing record, and the row that "
                + "IS there was never expected")
        void aRowPastTheEndIsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 1, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            // Two findings, and both are true: the expectation at index 1 has nothing to compare
            // against, AND the row the unit did write at index 0 is one no expectation addresses. A
            // judge that reported only the first would let the second through, which is the direction
            // that lets a wrong record pass.
            assertThat(kindsOf(result))
                    .containsExactly(DiffKind.MISSING_RECORD, DiffKind.EXTRA_RECORD);
            Diff diff = outputDiffs(result).get(0);
            assertThat(diff.rowIndex()).isEqualTo(1);
            assertThat(diff.explanation())
                    .contains("holds 1 row(s), so there is no row at 0-based index 1");
            assertThat(outputDiffs(result).get(1).rowIndex()).isZero();
        }

        @Test
        @DisplayName("an expectation against an opened-but-unwritten dataset is one diff")
        void anEmptyDatasetIsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(0, List.of(), DatasetOutput.empty(XREF, CardXrefRecord.LAYOUT)));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.MISSING_RECORD);
        }

        @Test
        @DisplayName("a missing record is ONE diff, not one per field it would have carried")
        void aMissingRecordIsOneDiffNotOnePerField() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 1, fields(
                            XREF_CARD_NUM, "0500024453765740",
                            XREF_CUST_ID, "000000050",
                            XREF_ACCT_ID, "00000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            // Three fields were pinned on the absent record and exactly ONE missing-record difference
            // is reported for it - the fields of a record that was never written are not independently
            // wrong, they are collectively absent. The row the unit did write at index 0 is a separate
            // finding of its own, and does not dilute this one.
            assertThat(outputDiffs(result))
                    .filteredOn(diff -> diff.kind() == DiffKind.MISSING_RECORD)
                    .singleElement()
                    .satisfies(diff -> assertThat(diff.expected())
                            .as("the expected side names the fields the record would have carried")
                            .contains(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID));
        }

        @Test
        @DisplayName("one row too many is a parity failure in its own right")
        void oneRowTooManyIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.EXTRA_RECORD);
            assertThat(diff.rowIndex()).isEqualTo(1);
            assertThat(diff.expected()).isNull();
            assertThat(diff.explanation())
                    .contains("wrote 2 row(s) to dataset " + XREF + " but the case expects 1")
                    .contains("Extra output is a parity failure in its own right");
        }

        @Test
        @DisplayName("every extra row is reported, in ascending row order")
        void everyExtraRowIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50(), xrefRow50()));

            assertThat(outputDiffs(result)).extracting(Diff::rowIndex).containsExactly(1, 2);
            assertThat(outputDiffs(result)).extracting(Diff::kind)
                    .containsOnly(DiffKind.EXTRA_RECORD);
        }

        @Test
        @DisplayName("a dataset the case says nothing about is reported: silence is not permission")
        void rowsInAnUnaddressedDatasetAreReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(0, List.of(),
                            output(XREF, CardXrefRecord.LAYOUT, xrefRow50()),
                            output(ACCOUNT, AccountRecord.LAYOUT, accountRow(), accountRow())));

            // A judge driven by the expectation keys can never reach a dataset no expectation names,
            // so a unit that wrote an output the COBOL does not have would pass clean - the single
            // most consequential blind spot a parity judge can have, because the whole question is
            // whether the unit did what the COBOL does and nothing else. The pass is therefore driven
            // by what the unit produced: an expectation the case does not state is a positive
            // assertion that nothing was produced, not an absence of interest.
            assertThat(outputIsClean(result)).isFalse();
            assertThat(outputDiffs(result)).singleElement().satisfies(diff -> {
                assertThat(diff.kind()).isEqualTo(DiffKind.EXTRA_DATASET);
                assertThat(diff.dataset()).isEqualTo(ACCOUNT);
                assertThat(diff.actual()).isEqualTo("2 row(s)");
            });
        }
    }


    // =================================================================================================
    // 11. Field names are the copybook's own, verbatim.
    // =================================================================================================

    @Nested
    @DisplayName("Field names are the copybook's own, and an unknown one fails loudly")
    class FieldNaming {

        @Test
        @DisplayName("the misspelled ACCT-EXPIRAION-DATE is the contract; the corrected spelling is not")
        void theMisspelledNameIsTheContract() {
            assertThat(ACCT_EXPIRAION_DATE)
                    .as("app/cpy/CVACT01Y.cpy misspells it, and the model preserves the misspelling")
                    .isEqualTo(AccountRecord.ACCT_EXPIRAION_DATE_NAME);

            assertThat(outputIsClean(differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_EXPIRAION_DATE, "2025-05-20"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow())))).isTrue();

            DiffResult corrected = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields("ACCT-EXPIRATION-DATE", "2025-05-20"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(corrected);
            assertThat(diff.kind()).isEqualTo(DiffKind.FIELD_ABSENT_IN_FINGERPRINT);
            assertThat(diff.explanation())
                    .contains("declares no such span")
                    .contains("ACCT-EXPIRAION-DATE is spelled that way on purpose")
                    .contains("Addressable here:");
        }

        @Test
        @DisplayName("an unknown field name lists every addressable name, so the fix takes seconds")
        void anUnknownFieldNameListsTheAddressableNames() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields("XREF-CARDNUM", "0500024453765740"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(onlyDiff(result).explanation())
                    .contains(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID, FILLER);
        }

        @Test
        @DisplayName("the first FILLER is addressed as FILLER, never as FILLER-1")
        void theFirstFillerIsAddressedWithoutAnOrdinal() {
            assertThat(outputIsClean(differ.compare(
                    caseOf(pinning(XREF, 0, fields(FILLER, " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50())))).isTrue();

            DiffResult withOrdinal = differ.compare(
                    caseOf(pinning(XREF, 0, fields("FILLER-1", " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(onlyDiff(withOrdinal).kind())
                    .as("one name, one spelling: an alias would be a silently tolerated ambiguity")
                    .isEqualTo(DiffKind.FIELD_ABSENT_IN_FINGERPRINT);
        }

        @Test
        @DisplayName("a copybook-named filler keeps its own name, as SEC-USR-FILLER does")
        void aNamedFillerKeepsItsName() {
            assertThat(outputIsClean(differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0,
                                    fields(SEC_USR_FILLER, " ".repeat(USRSEC_FILLER_WIDTH)))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, seeded(USRSEC,
                            Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)))))
                    .isTrue();
        }
    }


    // =================================================================================================
    // 12. Determinism, traversal order and completeness of the report.
    // =================================================================================================

    @Nested
    @DisplayName("The report is deterministic, ordered and never truncated")
    class DeterminismAndCompleteness {

        @Test
        @DisplayName("the same comparison run twice produces identical ordered output")
        void theSameComparisonTwiceIsIdentical() {
            ParityCase parityCase = caseOf(List.of(
                            pinning(XREF, 0, fields(
                                    XREF_CARD_NUM, "9999999999999999",
                                    XREF_CUST_ID, "000000051",
                                    XREF_ACCT_ID, "00000000051"))),
                    8, List.of("FIRST", "SECOND"), List.of());
            Fingerprint fingerprint = wrote(0, List.of("OTHER"),
                    output(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50()));

            DiffResult first = differ.compare(parityCase, fingerprint);
            DiffResult second = differ.compare(parityCase, fingerprint);

            assertThat(outputCount(second)).isEqualTo(outputCount(first));
            assertThat(second.render()).isEqualTo(first.render());
            assertThat(kindsOf(second)).isEqualTo(kindsOf(first));
        }

        @Test
        @DisplayName("traversal is records, then extra rows, then the return code, then the messages")
        void traversalOrderIsFullyDetermined() {
            DiffResult result = differ.compare(
                    caseOf(List.of(pinning(XREF, 0, fields(XREF_CUST_ID, "000000051"))),
                            8, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("OTHER"),
                            output(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50())));

            assertThat(kindsOf(result)).containsExactly(
                    DiffKind.VALUE_MISMATCH,
                    DiffKind.EXTRA_RECORD,
                    DiffKind.RETURN_CODE_MISMATCH,
                    DiffKind.MESSAGE_COUNT_MISMATCH,
                    DiffKind.MESSAGE_MISMATCH);
        }

        @Test
        @DisplayName("every difference is rendered - the list is never capped or summarised")
        void everyDifferenceIsRendered() {
            Map<String, String> everySpanWrong = new LinkedHashMap<>();
            for (FieldSpan span : AccountRecord.LAYOUT.spans()) {
                everySpanWrong.put(span.name(),
                        span.kind() == PictureKind.SIGNED_SCALED ? "0.01" : "?");
            }

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, everySpanWrong)),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            int spans = AccountRecord.LAYOUT.spans().size();
            assertThat(outputCount(result)).isEqualTo(spans);
            assertThat(result.render())
                    .contains(spans + " differences")
                    .contains("[1/" + spans + "]")
                    .contains("[" + spans + "/" + spans + "]")
                    .contains("the gate requires a diff count of 0");
        }

        @Test
        @DisplayName("a result is immutable once produced, so two tests cannot interfere")
        void aResultIsImmutable() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 8, List.of(), List.of()), Fingerprint.ofReturnCode(0));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.entries().add(null));
        }

        @Test
        @DisplayName("a result names its program and case, so a failure is traceable from the header")
        void aResultNamesItsProgramAndCase() {
            DiffResult result = differ.compare(caseOf(), Fingerprint.ofReturnCode(0));

            assertThat(result.program()).isEqualTo(PROGRAM);
            assertThat(result.caseId()).isEqualTo(CASE_ID);
            assertThat(result.render()).startsWith("Parity comparison " + PROGRAM + "/" + CASE_ID);
            assertThat(result.toString()).isEqualTo(result.render());
        }

        @Test
        @DisplayName("no diff kind is advisory: every kind the differ can report counts")
        void everyDiffKindCounts() {
            assertThat(DiffKind.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                    "VALUE_MISMATCH",
                    "MISSING_RECORD",
                    "EXTRA_RECORD",
                    "EXTRA_DATASET",
                    "RECORD_WIDTH_MISMATCH",
                    "FIELD_ABSENT_IN_FINGERPRINT",
                    "UNDECODABLE_FIELD",
                    "MALFORMED_EXPECTATION",
                    "RESPONSE_MISMATCH",
                    "SEND_COUNT_MISMATCH",
                    "RETURN_CODE_MISMATCH",
                    "MESSAGE_MISMATCH",
                    "MESSAGE_CHANNEL_MISMATCH",
                    "MESSAGE_COUNT_MISMATCH",
                    "INCOMPLETE_EXPECTATION",
                    "MISSING_DATASET",
                    "DATASET_ROW_COUNT_MISMATCH",
                    "DATASET_WIDTH_MISMATCH");
        }
    }


    // =================================================================================================
    // 13. Construction. The charset is a parameter, never a platform default.
    // =================================================================================================

    @Nested
    @DisplayName("The differ is constructed over an explicit codec and an explicit charset")
    class ConstructionContract {

        @Test
        @DisplayName("a null charset is rejected: an encoding is never derived from the platform")
        void aNullCharsetIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> FieldDiffer.forCharset(null));
        }

        @Test
        @DisplayName("a null codec is rejected")
        void aNullCodecIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> new FieldDiffer(null));
        }

        @Test
        @DisplayName("the codec supplied at construction is the one reported and used")
        void theSuppliedCodecIsTheOneReported() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThat(new FieldDiffer(codec).codec()).isSameAs(codec);
            assertThat(differ.codec().charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("neither argument to compare may be null - use ofReturnCode for a silent unit")
        void compareRejectsNullArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> differ.compare(null, Fingerprint.ofReturnCode(0)));
            assertThatNullPointerException()
                    .isThrownBy(() -> differ.compare(caseOf(), null));
        }
    }
}
