package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.AccountBalanceReaderJob;
import com.vsergeychik.carddemo.account.AccountBalanceReaderJob.SysoutSink;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The twenty-case parity gate for {@code app/cbl/CBACT02C.cbl}, judged field by field against
 * {@link AccountBalanceReaderJob}.
 */
@DisplayName("CBACT02C parity - read and print the card data file (app/jcl/READCARD.jcl STEP05)")
final class CBACT02CParityTest {
    private static final String PROGRAM = AccountBalanceReaderJob.PROGRAM_ID;

    private static final String OPEN_SITE = "OPEN-" + AccountBalanceReaderJob.DD_NAME;

    private static final String READ_SITE = "READ-" + AccountBalanceReaderJob.DD_NAME;

    private static final String CLOSE_SITE = "CLOSE-" + AccountBalanceReaderJob.DD_NAME;

    private static final String PARITY_DSNAME = "CARDDEMO.PARITY.CBACT02C.CARDFILE";

    private static final String RECORD_FORMAT = "FB";

    private static final String COPYBOOK = "CVACT02Y";

    private static final Integer KEY_OFFSET = Integer.valueOf(CardRecord.CARD_NUM_OFFSET);

    private static final int EVERY_SEEDED_ROW = -1;

    private static final int MONETARY_IMAGE_LENGTH = 12;

    private static final int MONETARY_SCALE = 2;

    private static final BigDecimal NEGATIVE_OVERPUNCH_VALUE = new BigDecimal("-194.00");

    private static final BigDecimal POSITIVE_OVERPUNCH_VALUE = new BigDecimal("194.00");

    private static final char POSITIVE_OVERPUNCH_ZERO =
            FixedWidthRecord.ZonedSign.overpunch(0, false);

    private static final int CASE12_INDEX = 11;

    private static final int CASE17_INDEX = 16;

    private static final int CASE18_INDEX = 17;

    private static final int CASE19_INDEX = 18;

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the field-by-field diff count is zero")
    void theDiffCountIsZero(final ParityCase parityCase) {
        final FieldDiffer.DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, ParityCase.UnitKind.BATCH_JOB, CBACT02CParityTest::driveCbact02c);

        assertThat(result.count())
                .withFailMessage(() -> "The parity gate for " + PROGRAM + '/' + parityCase.caseId()
                        + " found " + result.count() + " difference(s), and the criterion is a diff "
                        + "count of zero across all " + ParityHarness.CASES_PER_PROGRAM
                        + " cases - so this module is incomplete until every one of them is resolved. "
                        + "The expectations come from app/cbl/CBACT02C.cbl, app/cpy/CVACT02Y.cpy, "
                        + "app/jcl/READCARD.jcl and app/data/ASCII/carddata.txt, none of which may be "
                        + "edited to make a difference go away."
                        + System.lineSeparator() + result.render())
                .isZero();
    }

    @Nested
    @DisplayName("The case set is exactly the gate the criterion names")
    class TheCaseSet {
        @Test
        @DisplayName("exactly twenty cases arrive, case01 through case20, in ascending order")
        void theGateIsTwentyOrderedCases() {
            final List<ParityCase> loaded = cases();

            final List<String> expectedIds = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
            for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
                expectedIds.add(ParityHarness.caseId(ordinal));
            }

            assertThat(loaded)
                    .as("the cases loaded from parity/%s/. The criterion is a diff count of zero "
                            + "across all %d cases, and a set of four satisfies that vacuously.",
                            PROGRAM, ParityHarness.CASES_PER_PROGRAM)
                    .hasSize(ParityHarness.CASES_PER_PROGRAM);
            assertThat(loaded.stream().map(ParityCase::caseId).toList())
                    .as("case order, which the parameterised test above relies on for its indices")
                    .containsExactlyElementsOf(expectedIds);
        }

        @Test
        @DisplayName("every case pins CBACT02C's own contract: BATCH_JOB, no PARM, one DD, no write")
        void everyCaseMatchesTheReadcardContract() {
            assertThat(cases()).allSatisfy(parityCase -> {
                assertThat(parityCase.program()).isEqualTo(PROGRAM);

                assertThat(parityCase.unitKind()).isEqualTo(ParityCase.UnitKind.BATCH_JOB);
                assertThat(parityCase.screenRequest()).isNull();
                assertThat(parityCase.expectedResponse()).isNull();

                assertThat(parityCase.jobParameters()).isEmpty();

                assertThat(parityCase.inputs()).containsOnlyKeys(AccountBalanceReaderJob.DD_NAME);

                assertThat(parityCase.expectedWrites()).isEmpty();
                assertThat(parityCase.expectedFinalState()).isEmpty();
                assertThat(parityCase.expectedDatasets()).isEmpty();

                assertThat(parityCase.normalisations()).isEmpty();

                assertThat(parityCase.expectedMessages()).isNotEmpty()
                        .allSatisfy(message -> assertThat(message.channel())
                                .isEqualTo(ParityCase.MessageChannel.DISPLAY_LINE));

                assertThat(parityCase.expectedReturnCode())
                        .isIn(AbendException.RETURN_CODE_OK, AbendException.RETURN_CODE_IO_ERROR);
            });
        }

        @Test
        @DisplayName("every case's expected lines are the ones the production constants emit")
        void everyExpectedLineTracesToAConstant() {
            final List<String> permitted = List.of(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.END_BANNER,
                    AccountBalanceReaderJob.OPEN_ERROR_TEXT,
                    AccountBalanceReaderJob.READ_ERROR_TEXT,
                    AccountBalanceReaderJob.CLOSE_ERROR_TEXT,
                    AbendException.ABEND_DISPLAY_TEXT,
                    FileStatus.toDisplayLine(FileStatus.END_OF_FILE),
                    FileStatus.toDisplayLine(FileStatus.DUPLICATE),
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS));

            assertThat(cases()).allSatisfy(parityCase ->
                    assertThat(parityCase.expectedMessages()).allSatisfy(message -> {
                        if (message.text().length() == CardRecord.RECORD_LENGTH) {
                            return;
                        }
                        assertThat(message.text())
                                .as("a diagnostic line of %s that no production constant emits",
                                        PROGRAM)
                                .isIn(permitted);
                    }));
        }

        @Test
        @DisplayName("every record line is a seeded row, verbatim, in file order, at the declared 150"
                + " bytes")
        void everyRecordLineIsASeededRowInFileOrder() {
            final ParityHarness harness = ParityHarness.usAscii();

            assertThat(cases()).allSatisfy(parityCase -> {
                final List<String> seeded = harness.seed(parityCase)
                        .get(AccountBalanceReaderJob.DD_NAME).rows();

                assertThat(seeded).allSatisfy(row ->
                        assertThat(row)
                                .as("a seeded %s row, which app/cpy/CVACT02Y.cpy declares as "
                                        + "RECLN %d", AccountBalanceReaderJob.DD_NAME,
                                        CardRecord.RECORD_LENGTH)
                                .hasSize(CardRecord.RECORD_LENGTH));

                assertThat(parityCase.expectedMessages()).allSatisfy(message ->
                        assertThat(message.text().length())
                                .as("expected line width in %s/%s", PROGRAM, parityCase.caseId())
                                .isLessThanOrEqualTo(CardRecord.RECORD_LENGTH));

                final List<String> displayed = recordLinesOf(parityCase);
                assertThat(displayed.size())
                        .as("%s/%s displays more records than its input holds", PROGRAM,
                                parityCase.caseId())
                        .isLessThanOrEqualTo(seeded.size());
                assertThat(displayed)
                        .as("the records %s/%s displays must be the leading rows of its own input, "
                                + "byte for byte and in order", PROGRAM, parityCase.caseId())
                        .containsExactlyElementsOf(seeded.subList(0, displayed.size()));
            });
        }

        private List<String> recordLinesOf(final ParityCase parityCase) {
            return parityCase.expectedMessages().stream()
                    .map(ParityCase.EmittedMessage::text)
                    .filter(text -> text.length() == CardRecord.RECORD_LENGTH)
                    .toList();
        }
    }

    @Nested
    @DisplayName("The comparison has teeth")
    class TheComparisonHasTeeth {
        @Test
        @DisplayName("changing one byte of one expected record line makes the gate fail")
        void aPerturbedRecordLineIsRejected() {
            final ParityCase clean = cases().get(CASE17_INDEX);
            final ParityCase perturbed = withFirstRecordLinePerturbed(clean);

            final FieldDiffer.DiffResult result = ParityHarness.usAscii()
                    .judge(perturbed, ParityCase.UnitKind.BATCH_JOB,
                            CBACT02CParityTest::driveCbact02c);

            assertThat(result.count())
                    .as("a single altered byte inside the FILLER span must be reported. If this "
                            + "passes, the zero the gate reports for the other twenty cases is not "
                            + "evidence of anything.")
                    .isPositive();
            assertThat(result.render()).contains(clean.caseId());
        }

        @Test
        @DisplayName("changing the expected RETURN-CODE makes the gate fail")
        void aPerturbedReturnCodeIsRejected() {
            final ParityCase clean = cases().get(CASE12_INDEX);
            final ParityCase perturbed = withReturnCode(clean, AbendException.RETURN_CODE_OK);

            final FieldDiffer.DiffResult result = ParityHarness.usAscii()
                    .judge(perturbed, ParityCase.UnitKind.BATCH_JOB,
                            CBACT02CParityTest::driveCbact02c);

            assertThat(result.count())
                    .as("case12 abends with 12 from :101, so expecting 0 must be reported. A batch "
                            + "exit status carries that value out to the process, which is what makes "
                            + "a JCL COND test on a following step behave as it does today.")
                    .isPositive();
        }

        private ParityCase withFirstRecordLinePerturbed(final ParityCase original) {
            final List<ParityCase.EmittedMessage> messages =
                    new ArrayList<>(original.expectedMessages());
            for (int index = 0; index < messages.size(); index++) {
                final ParityCase.EmittedMessage message = messages.get(index);
                if (message.text().length() != CardRecord.RECORD_LENGTH) {
                    continue;
                }
                final String perturbed =
                        message.text().substring(0, CardRecord.RECORD_LENGTH - 1) + '!';
                messages.set(index, new ParityCase.EmittedMessage(message.channel(), perturbed));
                return copyOf(original, messages, original.expectedReturnCode());
            }
            throw new IllegalStateException("Case " + PROGRAM + '/' + original.caseId()
                    + " declares no " + CardRecord.RECORD_LENGTH + "-character record line, so there "
                    + "is nothing to perturb. Point this guard at a case that displays a record.");
        }

        private ParityCase withReturnCode(final ParityCase original, final int returnCode) {
            return copyOf(original, original.expectedMessages(), returnCode);
        }

        private ParityCase copyOf(final ParityCase original,
                                  final List<ParityCase.EmittedMessage> messages,
                                  final int returnCode) {
            return new ParityCase(original.program(), original.caseId(), original.description(),
                    original.unitKind(), original.inputs(), original.jobParameters(),
                    original.screenRequest(), original.expectedResponse(), original.expectedWrites(),
                    original.expectedFinalState(), Integer.valueOf(returnCode), messages,
                    original.normalisations());
        }
    }

    @Nested
    @DisplayName("The abend is 9999-ABEND-PROGRAM, byte for byte and code for code")
    class TheAbend {
        @Test
        @DisplayName("a failing read displays three lines and abends with RETURN-CODE 12, ABCODE 999,"
                + " TIMING 0")
        void theReadErrorArmAbendsExactlyAsTheSourceDoes() {
            final ParityHarness harness = ParityHarness.usAscii();
            final Charset datasetCharset = harness.charset();
            final List<String> emitted = new ArrayList<>();
            final SysoutSink sysout = emitted::add;

            final CardRepository cardRepository = stubbedCardMaster(
                    Scenario.readFailingAfter(0, Terminator.NOT_FOUND),
                    ParityHarness.SeededDataset.empty(AccountBalanceReaderJob.DD_NAME,
                            CardRecord.RECORD_LENGTH, datasetCharset),
                    datasetCharset);
            final AccountBalanceReaderJob job = jobOver(cardRepository, datasetCharset, sysout);

            assertThatExceptionOfType(AbendException.class)
                    .as("PERFORM 9999-ABEND-PROGRAM at :113 does not return")
                    .isThrownBy(() -> job.execute(sysout))
                    .satisfies(abend -> {
                        assertThat(abend.getReturnCode())
                                .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
                        assertThat(abend.getProgram()).isEqualTo(PROGRAM);
                        assertThat(abend.getAbendCode())
                                .hasValue(AbendException.STANDARD_ABEND_CODE);
                        assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
                    });

            assertThat(emitted).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.READ_ERROR_TEXT,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);

            requireNoWriteWasAttempted(cardRepository);
        }

        @Test
        @DisplayName("the status line is the COBOL literal plus the IO-STATUS-04 image, with the"
                + " trailing NNNN intact")
        void theStatusLineKeepsTheLiteralNnnn() {
            assertThat(FileStatus.toDisplayLine(FileStatus.NOT_FOUND))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0023");
            assertThat(FileStatus.toDisplayLine(FileStatus.DUPLICATE))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0022");
            assertThat(FileStatus.toDisplayLine(FileStatus.END_OF_FILE))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0010");

            assertThat(FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");

            assertThat(FileStatus.DISPLAY_PREFIX)
                    .as("the trailing NNNN is part of the literal at :168 and :172, not a placeholder")
                    .endsWith("NNNN");
        }
    }

    @Nested
    @DisplayName("A displayed record is the row's own bytes")
    class TheRecordImage {
        @Test
        @DisplayName("the blank-FILLER rows display 150 characters whose last 59 are spaces, and the"
                + " unread row displays nothing")
        void theFillerSpanIsPresentAndSpaceFilled() {
            final ParityCase geometry = cases().get(CASE17_INDEX);
            final List<String> seeded = geometry.inputs()
                    .get(AccountBalanceReaderJob.DD_NAME).rows();

            assertThat(seeded)
                    .as("case17 seeds three rows and displays two, which is the asymmetry it exists "
                            + "to state")
                    .hasSize(3)
                    .allSatisfy(row -> {
                        assertThat(row).hasSize(CardRecord.RECORD_LENGTH);
                        assertThat(row.substring(CardRecord.FILLER_OFFSET))
                                .as("CVACT02Y's trailing FILLER PIC X(59), which declares no VALUE "
                                        + "and is therefore space-filled")
                                .hasSize(CardRecord.FILLER_LENGTH)
                                .isBlank();
                    });

            assertThat(recordLinesOf(geometry))
                    .as("the leading two of case17's three seeded rows, and nothing else")
                    .containsExactly(seeded.get(0), seeded.get(1));
            assertThat(recordLinesOf(geometry))
                    .as("the third row is seeded but never read, so it is never displayed")
                    .doesNotContain(seeded.get(2));
        }

        @Test
        @DisplayName("a negative zoned overpunch in the FILLER survives, and a re-encode would destroy"
                + " it")
        void theNegativeOverpunchIsPassedThroughVerbatim() {
            final ParityCase overpunched = cases().get(CASE18_INDEX);
            final String seeded = onlySeededRow(overpunched);
            final Charset datasetCharset = ParityHarness.FIXTURE_CHARSET;
            final FixedWidthCodec codec = new FixedWidthCodec(datasetCharset);

            final String monetaryImage = seeded.substring(CardRecord.FILLER_OFFSET,
                    CardRecord.FILLER_OFFSET + MONETARY_IMAGE_LENGTH);
            final FixedWidthCodec.SignedZoned decoded =
                    codec.decodeSignedZoned(monetaryImage, MONETARY_SCALE);

            assertThat(decoded.negative())
                    .as("'}' closes the negative overpunch alphabet and carries digit zero with it")
                    .isTrue();
            assertThat(decoded.signedValue()).isEqualByComparingTo(NEGATIVE_OVERPUNCH_VALUE);
            assertThat(decoded.signedValue().scale())
                    .as("a PIC S9(10)V99 field reports exactly its declared scale")
                    .isEqualTo(MONETARY_SCALE);

            assertThat(recordLinesOf(overpunched)).containsExactly(seeded);
            assertThat(CardRecord.decodeImage(seeded, datasetCharset).encodeToImage(datasetCharset))
                    .as("a re-encode of the decoded fields is NOT the row, so the pass-through at :78"
                            + " is load-bearing rather than incidental")
                    .isNotEqualTo(seeded)
                    .hasSize(CardRecord.RECORD_LENGTH)
                    .startsWith(seeded.substring(0, CardRecord.FILLER_OFFSET));
        }

        @Test
        @DisplayName("the same twelve digits closed by '{' carry the positive overpunch alphabet")
        void thePositiveOverpunchAlphabetIsTheSameImageWithItsSignByteReplaced() {
            final String negativeImage = onlySeededRow(cases().get(CASE18_INDEX))
                    .substring(CardRecord.FILLER_OFFSET,
                            CardRecord.FILLER_OFFSET + MONETARY_IMAGE_LENGTH);
            final String positiveImage = negativeImage.substring(0, MONETARY_IMAGE_LENGTH - 1)
                    + POSITIVE_OVERPUNCH_ZERO;
            final FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

            final FixedWidthCodec.SignedZoned decoded =
                    codec.decodeSignedZoned(positiveImage, MONETARY_SCALE);

            assertThat(decoded.negative())
                    .as("'{' closes the positive overpunch alphabet and carries digit zero with it")
                    .isFalse();
            assertThat(decoded.signedValue()).isEqualByComparingTo(POSITIVE_OVERPUNCH_VALUE);
            assertThat(decoded.signedValue().scale())
                    .as("a PIC S9(10)V99 field reports exactly its declared scale")
                    .isEqualTo(MONETARY_SCALE);
            assertThat(decoded.signedValue().negate())
                    .as("the two alphabets differ in the sign and in nothing else")
                    .isEqualByComparingTo(NEGATIVE_OVERPUNCH_VALUE);
        }

        @Test
        @DisplayName("the close-failure case displays every seeded row and still loses the end banner")
        void theCloseFailureCaseCompletesItsReadPassAndLosesTheEndBanner() {
            final ParityCase closeFailure = cases().get(CASE19_INDEX);
            final List<String> seeded = ParityHarness.usAscii().seed(closeFailure)
                    .get(AccountBalanceReaderJob.DD_NAME).rows();
            final List<String> lines = closeFailure.expectedMessages().stream()
                    .map(ParityCase.EmittedMessage::text)
                    .toList();

            assertThat(recordLinesOf(closeFailure))
                    .as("case19's read pass completes in full, so :78 emits one line per seeded row")
                    .containsExactlyElementsOf(seeded)
                    .isNotEmpty();

            assertThat(lines)
                    .as(":85 sits after :83, so an abending CLOSE loses the end banner even though "
                            + "nothing was wrong with the data")
                    .doesNotContain(AccountBalanceReaderJob.END_BANNER)
                    .containsSubsequence(AccountBalanceReaderJob.START_BANNER,
                            AccountBalanceReaderJob.CLOSE_ERROR_TEXT,
                            FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS),
                            AbendException.ABEND_DISPLAY_TEXT)
                    .as("the failure is unambiguously at the close site, not the open or the read")
                    .doesNotContain(AccountBalanceReaderJob.OPEN_ERROR_TEXT,
                            AccountBalanceReaderJob.READ_ERROR_TEXT);
            assertThat(closeFailure.expectedReturnCode())
                    .as(":142 ADD 12 TO ZERO GIVING APPL-RESULT, never the 8 :137 deposits")
                    .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        }

        @Test
        @DisplayName("exactly case18 and case19 exercise the close site's error arm")
        void theCloseErrorArmIsForcedByExactlyTheTwoDeclaredCases() {
            assertThat(cases()).filteredOn(parityCase -> parityCase.expectedMessages().stream()
                            .anyMatch(message -> AccountBalanceReaderJob.CLOSE_ERROR_TEXT
                                    .equals(message.text())))
                    .as("the CLOSE cannot be made to fail by any input, so exactly the two declared "
                            + "cases force it")
                    .extracting(ParityCase::caseId)
                    .containsExactly(cases().get(CASE18_INDEX).caseId(),
                            cases().get(CASE19_INDEX).caseId());
        }

        private String onlySeededRow(final ParityCase parityCase) {
            final List<String> rows =
                    parityCase.inputs().get(AccountBalanceReaderJob.DD_NAME).rows();
            assertThat(rows)
                    .as("%s/%s is a single-row geometry case", PROGRAM, parityCase.caseId())
                    .hasSize(1);
            return rows.get(0);
        }

        private List<String> recordLinesOf(final ParityCase parityCase) {
            return parityCase.expectedMessages().stream()
                    .map(ParityCase.EmittedMessage::text)
                    .filter(text -> text.length() == CardRecord.RECORD_LENGTH)
                    .toList();
        }
    }

    @Nested
    @DisplayName("The wiring is READCARD's, and no dataset name reaches Java")
    class TheWiring {
        @Test
        @DisplayName("the job resolves its dataset through the CARDFILE binding and publishes SYSOUT")
        void theJobResolvesItsDdNameThroughConfiguration() {
            final List<String> emitted = new ArrayList<>();
            final SysoutSink sysout = emitted::add;
            final AccountBalanceReaderJob job = jobOver(mock(CardRepository.class),
                    ParityHarness.FIXTURE_CHARSET, sysout);

            assertThat(job.cardfileDatasetName()).isEqualTo(PARITY_DSNAME);
            assertThat(job.sysoutSink())
                    .as("//SYSOUT DD SYSOUT=* at :27 - the seam a parity case captures through")
                    .isSameAs(sysout);
            assertThat(emitted).isEmpty();
        }

        @Test
        @DisplayName("the step sequence is READCARD's single ungated STEP05 running CBACT02C")
        void theStepSequenceIsTheOneTheJclDeclares() {
            assertThat(AccountBalanceReaderJob.DD_NAME).isEqualTo("CARDFILE");
            assertThat(AccountBalanceReaderJob.STEP_NAME).isEqualTo("STEP05");
            assertThat(AccountBalanceReaderJob.REQUIRED_STEPS)
                    .as("app/jcl/READCARD.jcl declares one step and nothing precedes it, so nothing "
                            + "carries COND=(0,NE)")
                    .singleElement()
                    .satisfies(step -> {
                        assertThat(step.name()).isEqualTo(AccountBalanceReaderJob.STEP_NAME);
                        assertThat(step.program()).isEqualTo(PROGRAM);
                        assertThat(step.requirePrecedingExitCodeZero()).isFalse();
                    });
        }

        @Test
        @DisplayName("neither a job repository nor a transaction manager is resolved on this path")
        void nothingResolvesTheBuilderCollaborators() {
            final ObjectProvider<Object> unresolvable = new UnresolvableProvider<>();

            assertThat(unresolvable.stream()).isEmpty();
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a launcher in the path would relocate the commit boundaries the line "
                            + "ordering of this program depends on, so asking must fail loudly")
                    .isThrownBy(unresolvable::getObject)
                    .withMessageContaining(PROGRAM);
        }
    }

    private static ParityHarness.UnitOutcome driveCbact02c(final ParityHarness.Invocation invocation) {
        final Scenario scenario = Scenario.from(invocation.stimulus());
        final Charset datasetCharset = invocation.charset();
        final CardRepository cardRepository = stubbedCardMaster(
                scenario, invocation.dataset(AccountBalanceReaderJob.DD_NAME), datasetCharset);

        final SysoutSink sysout = line -> invocation.recorder().display(line);

        final AccountBalanceReaderJob job = jobOver(cardRepository, datasetCharset, sysout);

        AbendException abended = null;
        try {
            job.execute(sysout);
        } catch (final AbendException abend) {
            abended = abend;
        }

        requireNoWriteWasAttempted(cardRepository);

        if (abended != null) {
            throw abended;
        }
        invocation.recorder().returnCode(AbendException.RETURN_CODE_OK);
        return null;
    }

    private static void requireNoWriteWasAttempted(final CardRepository cardRepository) {
        verify(cardRepository, never()).rewrite(any());
        verify(cardRepository, never()).readForUpdateByCardNumber(any());
        verify(cardRepository, never()).readByCardNumber(any());
        verify(cardRepository, never()).readByAccountIdViaAltIndex(anyLong());
        verify(cardRepository, never()).readByAccountIdViaAltIndex(any(String.class));
        verify(cardRepository, never()).startBrowse(any(), any());
    }

    private static CardRepository stubbedCardMaster(final Scenario scenario,
                                                    final ParityHarness.SeededDataset cardfile,
                                                    final Charset datasetCharset) {
        final CardRepository cardRepository = mock(CardRepository.class);
        when(cardRepository.addressing(any(), eq(AccountBalanceReaderJob.DD_NAME)))
                .thenReturn(cardRepository);

        if (scenario.openRefused()) {
            when(cardRepository.openBrowse(eq(AccountBalanceReaderJob.LOWEST_CARD_NUMBER_KEY),
                    eq(BrowseDirection.FORWARD)))
                    .thenThrow(new IllegalStateException(
                            "The " + AccountBalanceReaderJob.DD_NAME + " binding could not be resolved "
                                    + "for this parity case, which is how a dataset that cannot be "
                                    + "reached at all reports itself"));
            return cardRepository;
        }

        final CardBrowse cardBrowse = mock(CardBrowse.class);
        when(cardRepository.openBrowse(eq(AccountBalanceReaderJob.LOWEST_CARD_NUMBER_KEY),
                eq(BrowseDirection.FORWARD))).thenReturn(cardBrowse);
        when(cardBrowse.openResp()).thenReturn(scenario.openResp());

        final List<CardReadResult> sequence = readSequence(scenario, cardfile, datasetCharset);
        when(cardBrowse.readNext()).thenReturn(sequence.get(0),
                sequence.subList(1, sequence.size()).toArray(CardReadResult[]::new));

        if (scenario.closeRefused()) {
            doThrow(new IllegalStateException(
                    "The " + AccountBalanceReaderJob.DD_NAME + " browse could not be released, which is "
                            + "how a CLOSE that fails reports itself"))
                    .when(cardBrowse).endBrowse();
        }
        return cardRepository;
    }

    private static List<CardReadResult> readSequence(final Scenario scenario,
                                                     final ParityHarness.SeededDataset cardfile,
                                                     final Charset datasetCharset) {
        final List<String> rows = cardfile.rows();
        final int delivered = scenario.recordsDelivered() == EVERY_SEEDED_ROW
                ? rows.size()
                : scenario.recordsDelivered();
        if (delivered > rows.size()) {
            throw new IllegalStateException("Scenario for " + PROGRAM + " asks for " + delivered
                    + " delivered record(s) but only " + rows.size() + " row(s) were seeded. The "
                    + "scenario and the case file's \"inputs\" describe the same run and must agree.");
        }

        final List<CardReadResult> sequence = new ArrayList<>(delivered + 1);
        for (int index = 0; index < delivered; index++) {
            final String image = rows.get(index);
            sequence.add(CardReadResult.normal(
                    CardRecord.decodeImage(image, datasetCharset), image));
        }
        sequence.add(terminatingRead(scenario, rows, delivered, datasetCharset));
        return sequence;
    }

    private static CardReadResult terminatingRead(final Scenario scenario,
                                                  final List<String> rows,
                                                  final int delivered,
                                                  final Charset datasetCharset) {
        return switch (scenario.terminator()) {
            case END_OF_FILE -> CardReadResult.endOfFile();
            case NOT_FOUND -> CardReadResult.notFound();
            case DUPLICATE_KEY -> duplicateKeyRead(rows, delivered, datasetCharset);
            case INVALID_REQUEST -> CardReadResult.failed(FileStatus.INVREQ);
        };
    }

    private static CardReadResult duplicateKeyRead(final List<String> rows,
                                                   final int delivered,
                                                   final Charset datasetCharset) {
        if (delivered >= rows.size()) {
            throw new IllegalStateException("A duplicate-key read reports a record alongside its "
                    + "status, so the case must seed at least " + (delivered + 1) + " row(s) for "
                    + PROGRAM + "; it seeded " + rows.size() + '.');
        }
        final String image = rows.get(delivered);
        return CardReadResult.duplicateKey(CardRecord.decodeImage(image, datasetCharset), image);
    }

    private static AccountBalanceReaderJob jobOver(final CardRepository cardRepository,
                                                   final Charset datasetCharset,
                                                   final SysoutSink sysout) {
        return new AccountBalanceReaderJob(parityBatchConfig(), cardRepository, datasetCharset,
                new PublishedSysoutSink(sysout));
    }

    private static BatchConfig parityBatchConfig() {
        final DatasetBinding cardfile = new DatasetBinding(PARITY_DSNAME, DatasetBinding.KSDS, false,
                RECORD_FORMAT, null, CardRecord.RECORD_LENGTH, COPYBOOK, CardRecord.CARD_NUM_LENGTH,
                KEY_OFFSET, null, null);

        final DatasetBindings datasetBindings = new DatasetBindings();
        datasetBindings.put(AccountBalanceReaderJob.DD_NAME, cardfile);
        datasetBindings.put(CardRepository.BASE_DD_NAME, cardfile);

        final JobContracts jobContracts = new JobContracts();
        jobContracts.put(AccountBalanceReaderJob.JOB_KEY,
                new JobContract(AccountBalanceReaderJob.PROGRAM_ID, null,
                        AccountBalanceReaderJob.REQUIRED_STEPS, null, null));

        return new BatchConfig(new UnresolvableProvider<>(), new UnresolvableProvider<>(),
                jobContracts, datasetBindings);
    }

    private record PublishedSysoutSink(SysoutSink sink) implements ObjectProvider<SysoutSink> {
        @Override
        public SysoutSink getObject() {
            return sink;
        }

        @Override
        public Stream<SysoutSink> stream() {
            return Stream.of(sink);
        }
    }

    private static final class UnresolvableProvider<T> implements ObjectProvider<T> {
        @Override
        public T getObject() {
            throw new IllegalStateException("A parity run of " + PROGRAM + " reaches the program body "
                    + "directly through AccountBalanceReaderJob.execute(SysoutSink) and builds no Job "
                    + "and no Step, so it publishes neither a JobRepository nor a transaction manager. "
                    + "Something asked for one, which means the path under test is no longer the "
                    + "tasklet body - and a launcher in the path would relocate the commit boundaries "
                    + "the line ordering of this program depends on.");
        }

        @Override
        public Stream<T> stream() {
            return Stream.empty();
        }
    }

    private enum Terminator {
        END_OF_FILE,

        NOT_FOUND,

        DUPLICATE_KEY,

        INVALID_REQUEST;

        private static Terminator ofResponse(final String site, final int resp) {
            return switch (resp) {
                case FileStatus.NOTFND -> NOT_FOUND;
                case FileStatus.DUPREC -> DUPLICATE_KEY;
                case FileStatus.INVREQ -> INVALID_REQUEST;
                default -> throw new IllegalArgumentException("Call site " + site + " declares RESP "
                        + resp + ", which is not one of the three this loop ends fatally on: "
                        + FileStatus.NOTFND + " (NOTFND), " + FileStatus.DUPREC + " (DUPREC) and "
                        + FileStatus.INVREQ + " (INVREQ). An orderly end of file is declared by naming "
                        + "no read site at all.");
            };
        }
    }

    private record Scenario(int openResp,
                            boolean openRefused,
                            int recordsDelivered,
                            Terminator terminator,
                            boolean closeRefused) {
        private static Scenario wholeFile() {
            return new Scenario(FileStatus.NORMAL, false, EVERY_SEEDED_ROW, Terminator.END_OF_FILE,
                    false);
        }

        private static Scenario openReporting(final int openResp) {
            return new Scenario(openResp, false, 0, Terminator.END_OF_FILE, false);
        }

        private static Scenario openRefusedOutright() {
            return new Scenario(FileStatus.NORMAL, true, 0, Terminator.END_OF_FILE, false);
        }

        private static Scenario readFailingAfter(final int recordsDelivered,
                                                final Terminator terminator) {
            return new Scenario(FileStatus.NORMAL, false, recordsDelivered, terminator, false);
        }

        private static Scenario closeFailing() {
            return new Scenario(FileStatus.NORMAL, false, EVERY_SEEDED_ROW, Terminator.END_OF_FILE,
                    true);
        }

        private static Scenario from(final ParityCase.UnitStimulus stimulus) {
            if (!stimulus.operationScript().isEmpty() || !stimulus.linkage().isEmpty()
                    || !stimulus.stepStatuses().isEmpty() || !stimulus.environment().isEmpty()) {
                throw new IllegalArgumentException(PROGRAM + " calls no subprogram, takes no linkage, "
                        + "follows no conditional job step and runs under no environmental variant: its "
                        + "only stimulus is its seeded rows and the outcome of one of its three call "
                        + "sites.");
            }
            int openResp = FileStatus.NORMAL;
            boolean openRefused = false;
            int recordsDelivered = EVERY_SEEDED_ROW;
            Terminator terminator = Terminator.END_OF_FILE;
            boolean closeRefused = false;
            for (final Map.Entry<String, ParityCase.CallSiteOutcome> declared
                    : stimulus.callSiteOutcomes().entrySet()) {
                final String site = declared.getKey();
                final ParityCase.CallSiteOutcome outcome = declared.getValue();
                switch (site) {
                    case OPEN_SITE -> {
                        if (outcome.isRefused()) {
                            openRefused = true;
                        } else {
                            openResp = requireResponse(site, outcome);
                        }
                    }
                    case CLOSE_SITE -> {
                        requireRefusal(site, outcome);
                        closeRefused = true;
                    }
                    case READ_SITE -> {
                        recordsDelivered = outcome.recordsBefore();
                        terminator = Terminator.ofResponse(site, requireResponse(site, outcome));
                    }
                    default -> throw new IllegalArgumentException("Call site " + site + " is not one "
                            + "of " + PROGRAM + "'s three: " + OPEN_SITE + ", " + READ_SITE + " and "
                            + CLOSE_SITE + ". A site nothing answers to arranges nothing, and the case "
                            + "would assert the opposite of what it says.");
                    }
            }
            return new Scenario(openResp, openRefused, recordsDelivered, terminator, closeRefused);
        }

        private static int requireResponse(final String site,
                                           final ParityCase.CallSiteOutcome outcome) {
            if (outcome.status() != null || outcome.resp() == null) {
                throw new IllegalArgumentException("Call site " + site + " must declare a CICS RESP. "
                        + "CardRepository reports this program's outcomes as responses and "
                        + AccountBalanceReaderJob.class.getSimpleName() + " translates them to a FILE "
                        + "STATUS, so declaring the status directly would skip the translation this "
                        + "case exists to judge.");
            }
            return outcome.resp();
        }

        private static void requireRefusal(final String site,
                                          final ParityCase.CallSiteOutcome outcome) {
            if (!outcome.isRefused() || outcome.resp() != null || outcome.status() != null) {
                throw new IllegalArgumentException("Call site " + site + " reports no outcome of its "
                        + "own: ending a browse either succeeds or is refused. Declare "
                        + "\"refused\": true.");
            }
        }
    }
}
