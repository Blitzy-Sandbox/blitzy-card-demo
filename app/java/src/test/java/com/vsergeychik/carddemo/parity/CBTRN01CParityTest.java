package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.transaction.DalyTranRepository;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.ExecutionSummary;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The twenty-case behavioural-parity gate for {@code app/cbl/CBTRN01C.cbl} - the orphan - against its Java
 * translation {@link TransactionPostingJob}.
 */
@DisplayName("CBTRN01C parity - the orphan that opens six files, posts nothing, and looks up one card too many")
class CBTRN01CParityTest {
    private static final String PROGRAM = "CBTRN01C";

    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.BATCH_JOB;

    private static final Charset ASCII = ParityHarness.FIXTURE_CHARSET;

    private static final String DALYTRAN = "DALYTRAN";

    private static final String CUSTFILE = "CUSTFILE";

    private static final String XREFFILE = "XREFFILE";

    private static final String CARDFILE = "CARDFILE";

    private static final String ACCTFILE = "ACCTFILE";

    private static final String TRANFILE = "TRANFILE";

    private static final List<String> ALL_DDS =
            List.of(DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, ACCTFILE, TRANFILE);

    private static final List<String> READ_DDS = List.of(DALYTRAN, XREFFILE, ACCTFILE);

    private static final FixedWidthCodec ACCOUNT_KEY_CODEC =
            new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

    private static final int DALYTRAN_WIDTH = 350;

    private static final int CUSTFILE_WIDTH = 500;

    private static final int XREF_WIDTH = 50;

    private static final int XREF_FIXTURE_WIDTH = 36;

    private static final int CARDFILE_WIDTH = 150;

    private static final int ACCTFILE_WIDTH = 300;

    private static final int TRANFILE_WIDTH = 350;

    private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBTRN01C";

    private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBTRN01C";

    private static final String ERROR_OPENING_DALYTRAN = "ERROR OPENING DAILY TRANSACTION FILE";

    private static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTOMER FILE";

    private static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    private static final String ERROR_OPENING_CARDFILE = "ERROR OPENING CARD FILE";

    private static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT FILE";

    private static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    private static final String ERROR_READING_DALYTRAN = "ERROR READING DAILY TRANSACTION FILE";

    private static final String ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTOMER FILE";

    private static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    private static final String ERROR_CLOSING_CARDFILE = "ERROR CLOSING CARD FILE";

    private static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    private static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    private static final String INVALID_CARD_NUMBER_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    private static final String SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    private static final String XREF_CARD_NUMBER_PREFIX = "CARD NUMBER: ";

    private static final String XREF_ACCOUNT_ID_PREFIX = "ACCOUNT ID : ";

    private static final String XREF_CUSTOMER_ID_PREFIX = "CUSTOMER ID: ";

    private static final String INVALID_ACCOUNT_NUMBER_FOUND = "INVALID ACCOUNT NUMBER FOUND";

    private static final String SUCCESSFUL_READ_OF_ACCOUNT_FILE = "SUCCESSFUL READ OF ACCOUNT FILE";

    private static final String ACCOUNT_NOT_FOUND_PREFIX = "ACCOUNT ";

    private static final String ACCOUNT_NOT_FOUND_SUFFIX = " NOT FOUND";

    private static final String CARD_NOT_VERIFIED_PREFIX = "CARD NUMBER ";

    private static final String CARD_NOT_VERIFIED_SUFFIX =
            " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-";

    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    private static final String FILE_STATUS_PREFIX = "FILE STATUS IS: NNNN";

    private static final String STATUS_OK = "00";

    private static final String STATUS_END_OF_FILE = "10";

    private static final String STATUS_DUPLICATE = "22";

    private static final String STATUS_NOT_FOUND = "23";

    private static final String STATUS_OPEN_FAILED = "35";

    private static final String STATUS_PERMANENT_ERROR = "9\u0000";

    private static final int RETURN_CODE_OK = 0;

    private static final int RETURN_CODE_FATAL = 12;

    private static final int CICS_RESP_NORMAL = 0;

    private static final int CICS_RESP_NOTFND = 13;

    private static final int CICS_RESP_DUPREC = 14;

    private static final int CARD_NUMBER_WIDTH = 16;

    private static final int CUSTOMER_ID_WIDTH = 9;

    private static final int ACCOUNT_ID_WIDTH = 11;

    private static final int TRANSACTION_ID_WIDTH = 16;

    private static final int DALYTRAN_CARD_NUM_OFFSET = 262;

    private static final String CARD_ONE = "4859452612877065";

    private static final String TRAN_ID_ONE = "0000000000683580";

    private static final int CUST_ONE = 7;

    private static final long ACCT_ONE = 7L;

    private static final String CARD_TWO = "0927987108636232";

    private static final String TRAN_ID_TWO = "0000000001774260";

    private static final int CUST_TWO = 20;

    private static final long ACCT_TWO = 20L;

    private static final String CARD_ABSENT = "9999999999999999";

    private static final int CUST_ABSENT = 999999999;

    private static final long ACCT_ABSENT = 99999999999L;

    private static final String AMOUNT_POSITIVE = "0000005047G";

    private static final String AMOUNT_NEGATIVE = "0000009190}";

    private static final BigDecimal AMOUNT_NEGATIVE_VALUE = new BigDecimal("-919.00");

    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    private static final String PROCESS_TIMESTAMP_BLANK = "";

    private static final String MERCHANT_ID = "800000000";

    private static final String FIXTURE_ROOT = "fixtures/";

    private static final String DAILYTRAN_FIXTURE = "dailytran.txt";

    private static final String CARDXREF_FIXTURE = "cardxref.txt";

    private static final String ACCTDATA_FIXTURE = "acctdata.txt";

    private static final String CARDDATA_FIXTURE = "carddata.txt";

    private static final String CUSTDATA_FIXTURE = "custdata.txt";

    private static final int SEEDED_FIXTURE_ROWS = 105;

    private static final String TRACE_OPEN = "OPEN ";

    private static final String TRACE_READ = "READ ";

    private static final String TRACE_CLOSE = "CLOSE ";

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("CBTRN01C parity: every case produces zero differences")
    void producesNoDifferences(ParityScenario scenario) {
        PostingRun unit = new PostingRun(scenario);

        FieldDiffer.DiffResult result =
                ParityHarness.usAscii().judge(scenario.parityCase(), UNIT_KIND, unit);

        assertThat(result.count())
                .withFailMessage("%s", result.render())
                .isZero();
        assertThat(unit.operations())
                .as("the reads %s issued, in order and with their keys, as the case declares them: "
                        + "CBTRN01C reads DALYTRAN sequentially at :167 and XREFFILE and ACCTFILE by key "
                        + "at :202-208 and :226-232, and reads CUSTFILE, CARDFILE and TRANFILE never",
                        scenario.caseId())
                .containsExactlyElementsOf(scenario.parityCase().expectedOperations());
        assertThat(unit.openAndCloseTrace())
                .as("the opens and closes %s issued, in order. The sequence is not declared per case "
                        + "because it is not a property of the case: MAIN-PARA opens the six at "
                        + ":157-162 and closes the six at :188-193 in one fixed order, and how far it "
                        + "gets follows from which verb the case scripts a failure at. So the "
                        + "expectation is derived from the declared stimulus and the transcribed source "
                        + "order, while the observation is what the stubs were actually asked for - two "
                        + "derivations that agree only if the translation issues the same verbs in the "
                        + "same order", scenario.caseId())
                .containsExactlyElementsOf(derivedOpenAndCloseTrace(scenario.parityCase()));
    }

    private static List<String> derivedOpenAndCloseTrace(ParityCase parityCase) {
        Injection injection = injectionFrom(parityCase.unitStimulus());
        List<String> entries = new ArrayList<>(ALL_DDS.size() * 2);
        for (String dataset : ALL_DDS) {
            entries.add(TRACE_OPEN + dataset);
            if (!STATUS_OK.equals(effectiveOpenStatus(injection, dataset))) {
                return List.copyOf(entries);
            }
        }
        if (injection.readStatus() != null) {
            return List.copyOf(entries);
        }
        for (String dataset : ALL_DDS) {
            entries.add(TRACE_CLOSE + dataset);
            boolean refused = CARDFILE.equals(dataset) && injection.cardCloseRefused();
            if (refused || !STATUS_OK.equals(injection.closeStatusOf(dataset))) {
                return List.copyOf(entries);
            }
        }
        return List.copyOf(entries);
    }

    private static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = ParityHarness.casesOf(PROGRAM).stream()
                .map(ParityScenario::new)
                .toList();
        requireCompleteCaseSet(scenarios);
        return scenarios;
    }

    private static ParityScenario scenarioOf(int ordinal) {
        return cases().get(ordinal - 1);
    }

    private static void requireCompleteCaseSet(List<ParityScenario> scenarios) {
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException(PROGRAM + " declares " + scenarios.size()
                    + " parity case(s) but the gate requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + ". A short set is not a smaller gate, it is a "
                    + "gate that passes without asking the questions.");
        }
        for (int ordinal = 1; ordinal <= scenarios.size(); ordinal++) {
            String expected = ParityHarness.caseId(ordinal);
            ParityCase declared = scenarios.get(ordinal - 1).parityCase();
            if (!expected.equals(declared.caseId())) {
                throw new IllegalStateException("Case " + ordinal + " of " + PROGRAM + " is declared "
                        + declared.caseId() + " where the set requires " + expected
                        + ". The identifiers are positional: a gap or a repeat means a case nobody is "
                        + "running, or one running twice while another runs not at all.");
            }
            if (!PROGRAM.equals(declared.program())) {
                throw new IllegalStateException("Case " + expected + " names program "
                        + declared.program() + " but this class gates " + PROGRAM + ", whose cases live "
                        + "in " + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + '/');
            }
        }
    }

    @Test
    @DisplayName("the program declares exactly twenty cases, case01 through case20 (G15)")
    void theProgramDeclaresExactlyTwentyCases() {
        List<ParityScenario> declared = cases();

        assertThat(declared).hasSize(ParityHarness.CASES_PER_PROGRAM);
        assertThat(declared.stream().map(ParityScenario::caseId).toList())
                .containsExactlyElementsOf(expectedCaseIds())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("all twenty cases declare CBTRN01C, BATCH_JOB, no screen members and no expected write")
    void everyCaseDeclaresTheSameContract() {
        for (ParityScenario scenario : cases()) {
            ParityCase declared = scenario.parityCase();

            assertThat(declared.program()).isEqualTo(PROGRAM);
            assertThat(declared.unitKind()).isEqualTo(UNIT_KIND);
            assertThat(declared.description()).isNotBlank();
            assertThat(declared.screenRequest())
                    .as("%s is a batch case: CBTRN01C contains no EXEC CICS and drives no map",
                            scenario.caseId())
                    .isNull();
            assertThat(declared.expectedResponse()).isNull();
            assertThat(declared.expectedWrites())
                    .as("%s - CBTRN01C issues no WRITE and no REWRITE anywhere, and opens all six of "
                            + "its files OPEN INPUT, so an expected write would assert behaviour the "
                            + "program cannot have", scenario.caseId())
                    .isEmpty();
            assertThat(declared.inputs().keySet())
                    .as("%s seeds only datasets this program declares a SELECT for", scenario.caseId())
                    .isSubsetOf(ALL_DDS);
        }
    }

    @Test
    @DisplayName("no case declares a job parameter: CBTRN01C is an orphan with no JCL and no PARM")
    void everyCaseDeclaresNoJobParameter() {
        for (ParityScenario scenario : cases()) {
            assertThat(scenario.parityCase().jobParameters())
                    .as("%s - there is no EXEC PGM=CBTRN01C in app/jcl or app/proc, so there is no "
                            + "PARM to parse and no LINKAGE SECTION to receive one", scenario.caseId())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("every writes-channel expectation is zero rows: the program posts nothing, anywhere")
    void everyCaseAssertsThatNothingWasWritten() {
        int writesExpectations = 0;
        for (ParityScenario scenario : cases()) {
            for (ParityCase.ExpectedDataset expectation : scenario.parityCase().expectedDatasets()) {
                if (expectation.channel() != ParityCase.DatasetChannel.WRITES) {
                    continue;
                }
                writesExpectations++;
                assertThat(expectation.rowCount())
                        .as("%s expects %s to have been written to", scenario.caseId(),
                                expectation.dataset())
                        .isZero();
                assertThat(expectation.recordLength())
                        .as("%s leaves %s's record width unasserted; the width is what proves every "
                                + "FILLER span was accounted for", scenario.caseId(),
                                expectation.dataset())
                        .isEqualTo(widthOf(expectation.dataset()));
            }
        }
        assertThat(writesExpectations)
                .as("the twenty cases between them must assert the empty writes channel of every "
                        + "dataset a run reaches, or the claim that nothing is posted is untested")
                .isGreaterThanOrEqualTo(ALL_DDS.size());
    }

    @Test
    @DisplayName("the class stem, the program name and parity/CBTRN01C/ agree")
    void resourceConventionIsHonoured() {
        assertThat(CBTRN01CParityTest.class.getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
    }

    @Test
    @DisplayName("the transcribed COBOL literals are the ones the translation emits")
    void theCobolLiteralsMatch() {
        assertThat(START_BANNER).isEqualTo(TransactionPostingJob.START_BANNER);
        assertThat(END_BANNER).isEqualTo(TransactionPostingJob.END_BANNER);
        assertThat(ERROR_OPENING_DALYTRAN).isEqualTo(TransactionPostingJob.ERROR_OPENING_DALYTRAN);
        assertThat(ERROR_OPENING_CUSTFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_CUSTFILE);
        assertThat(ERROR_OPENING_XREFFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_XREFFILE);
        assertThat(ERROR_OPENING_CARDFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_CARDFILE);
        assertThat(ERROR_OPENING_ACCTFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_ACCTFILE);
        assertThat(ERROR_OPENING_TRANFILE).isEqualTo(TransactionPostingJob.ERROR_OPENING_TRANFILE);
        assertThat(ERROR_READING_DALYTRAN).isEqualTo(TransactionPostingJob.ERROR_READING_DALYTRAN);
        assertThat(ERROR_CLOSING_CUSTFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_CUSTFILE);
        assertThat(ERROR_CLOSING_XREFFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_XREFFILE);
        assertThat(ERROR_CLOSING_CARDFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_CARDFILE);
        assertThat(ERROR_CLOSING_ACCTFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_ACCTFILE);
        assertThat(ERROR_CLOSING_TRANFILE).isEqualTo(TransactionPostingJob.ERROR_CLOSING_TRANFILE);
        assertThat(INVALID_CARD_NUMBER_FOR_XREF)
                .isEqualTo(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
        assertThat(SUCCESSFUL_READ_OF_XREF).isEqualTo(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF);
        assertThat(XREF_CARD_NUMBER_PREFIX).isEqualTo(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX);
        assertThat(XREF_ACCOUNT_ID_PREFIX).isEqualTo(TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX);
        assertThat(XREF_CUSTOMER_ID_PREFIX).isEqualTo(TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX);
        assertThat(INVALID_ACCOUNT_NUMBER_FOUND)
                .isEqualTo(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
        assertThat(SUCCESSFUL_READ_OF_ACCOUNT_FILE)
                .isEqualTo(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
        assertThat(ACCOUNT_NOT_FOUND_PREFIX).isEqualTo(TransactionPostingJob.ACCOUNT_NOT_FOUND_PREFIX);
        assertThat(ACCOUNT_NOT_FOUND_SUFFIX).isEqualTo(TransactionPostingJob.ACCOUNT_NOT_FOUND_SUFFIX);
        assertThat(CARD_NOT_VERIFIED_PREFIX).isEqualTo(TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX);
        assertThat(CARD_NOT_VERIFIED_SUFFIX).isEqualTo(TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX);
        assertThat(ABENDING_PROGRAM).isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
        assertThat(FILE_STATUS_PREFIX).isEqualTo(FileStatus.DISPLAY_PREFIX);
    }

    @Test
    @DisplayName("the transcribed record widths, file statuses and CICS responses match the module's")
    void theCopybookWidthsAndStatusVocabularyMatch() {
        assertThat(DALYTRAN_WIDTH).isEqualTo(DalyTranRecord.RECORD_LENGTH);
        assertThat(CUSTFILE_WIDTH).isEqualTo(CustomerRecord.RECORD_LENGTH);
        assertThat(XREF_WIDTH).isEqualTo(CardXrefRecord.RECORD_LENGTH);
        assertThat(XREF_FIXTURE_WIDTH)
                .isEqualTo(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH)
                .isEqualTo(XREF_WIDTH - CardXrefRecord.FILLER_LENGTH);
        assertThat(CARDFILE_WIDTH).isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(ACCTFILE_WIDTH).isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(TRANFILE_WIDTH).isEqualTo(TranRecord.RECORD_LENGTH);

        assertThat(STATUS_OK).isEqualTo(FileStatus.OK);
        assertThat(STATUS_END_OF_FILE).isEqualTo(FileStatus.END_OF_FILE);
        assertThat(STATUS_DUPLICATE).isEqualTo(FileStatus.DUPLICATE);
        assertThat(STATUS_NOT_FOUND).isEqualTo(FileStatus.NOT_FOUND);
        assertThat(STATUS_PERMANENT_ERROR)
                .isEqualTo(TransactionPostingJob.PERMANENT_ERROR_STATUS)
                .hasSize(FileStatus.STATUS_LENGTH);
        assertThat(STATUS_OPEN_FAILED)
                .as("'35' must be none of the four statuses the guards name, so that it reaches the "
                        + "ELSE MOVE 12 arm of whichever paragraph reported it")
                .isNotIn(FileStatus.OK, FileStatus.END_OF_FILE, FileStatus.DUPLICATE,
                        FileStatus.NOT_FOUND);

        assertThat(CICS_RESP_NORMAL).isEqualTo(FileStatus.NORMAL);
        assertThat(CICS_RESP_NOTFND).isEqualTo(FileStatus.NOTFND);
        assertThat(CICS_RESP_DUPREC).isEqualTo(FileStatus.DUPREC);
        assertThat(RETURN_CODE_OK).isEqualTo(AbendException.RETURN_CODE_OK);
        assertThat(RETURN_CODE_FATAL).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
    }

    @Test
    @DisplayName("every row every case seeds is byte-identical to a row of app/data/ASCII")
    void theSeedRowsAreTheShippedFixtureRows() {
        Map<String, List<String>> fixtures = Map.of(
                DALYTRAN, fixtureRows(DAILYTRAN_FIXTURE),
                XREFFILE, fixtureRows(CARDXREF_FIXTURE),
                ACCTFILE, fixtureRows(ACCTDATA_FIXTURE),
                CARDFILE, fixtureRows(CARDDATA_FIXTURE),
                CUSTFILE, fixtureRows(CUSTDATA_FIXTURE));
        List<String> daily = fixtures.get(DALYTRAN);
        List<String> crossReference = fixtures.get(XREFFILE);
        List<String> accounts = fixtures.get(ACCTFILE);

        assertThat(daily).hasSize(300).allMatch(row -> row.length() == DALYTRAN_WIDTH);
        assertThat(crossReference).hasSize(50).allMatch(row -> row.length() == XREF_FIXTURE_WIDTH);
        assertThat(accounts).hasSize(50).allMatch(row -> row.length() == ACCTFILE_WIDTH);

        int seededRows = 0;
        for (ParityScenario scenario : cases()) {
            for (Map.Entry<String, ParityCase.DatasetInput> seeded
                    : scenario.parityCase().inputs().entrySet()) {
                List<String> fixture = fixtures.get(seeded.getKey());
                if (fixture == null) {
                    assertThat(seeded.getValue().rows())
                            .as("%s seeds %s, for which app/data/ASCII ships no fixture, so the only "
                                    + "honest content for it is none", scenario.caseId(),
                                    seeded.getKey())
                            .isEmpty();
                    continue;
                }
                for (String row : seeded.getValue().rows()) {
                    seededRows++;
                    assertThat(fixture)
                            .as("%s seeds a %s row of %d character(s) that app/data/ASCII does not hold",
                                    scenario.caseId(), seeded.getKey(), row.length())
                            .contains(row);
                }
            }
        }
        assertThat(seededRows)
                .as("the twenty cases seed real rows rather than none: a set that seeded nothing would "
                        + "satisfy the loop above vacuously")
                .isEqualTo(SEEDED_FIXTURE_ROWS);

        assertThat(dailyTransactionRowOne()).isEqualTo(daily.get(0));
        assertThat(dailyTransactionRowTwo()).isEqualTo(daily.get(1));
        assertThat(crossReference).contains(xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE),
                xrefFixtureRow(CARD_TWO, CUST_TWO, ACCT_TWO));
        assertThat(accounts).contains(accountRowOne(), accountRowTwo());
        assertThat(accounts)
                .as("no account row carries %s, which is what makes it usable as an absent key",
                        ACCT_ABSENT)
                .noneMatch(row -> row.startsWith(digits(ACCT_ABSENT, ACCOUNT_ID_WIDTH)));
        assertThat(crossReference)
                .as("no cross-reference row carries %s, which is what makes it usable as an absent key",
                        CARD_ABSENT)
                .noneMatch(row -> row.startsWith(CARD_ABSENT));
    }

    @Test
    @DisplayName("the 36-byte cross-reference row pads to the 50 bytes CVACT03Y declares (G16)")
    void theCrossReferenceRowPadsFromThirtySixToFifty() {
        String fixtureRow = xrefFixtureRow(CARD_ONE, CUST_ONE, ACCT_ONE);
        String recordImage = xrefRecordImage(CARD_ONE, CUST_ONE, ACCT_ONE);

        assertThat(fixtureRow).hasSize(XREF_FIXTURE_WIDTH);
        assertThat(recordImage).hasSize(XREF_WIDTH)
                .startsWith(fixtureRow)
                .endsWith(blanks(CardXrefRecord.FILLER_LENGTH));
        assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.normaliseSeedRow(fixtureRow,
                XREFFILE)).isEqualTo(recordImage);
    }

    @Test
    @DisplayName("every cross-reference seed declares the 36-to-50 pad and nothing else does")
    void everyCrossReferenceSeedDeclaresThePad() {
        for (ParityScenario scenario : cases()) {
            ParityCase declared = scenario.parityCase();
            boolean seedsRows = declared.inputs().containsKey(XREFFILE)
                    && !declared.inputs().get(XREFFILE).declaredEmpty();

            assertThat(declared.normalisations())
                    .as("%s seeds %d cross-reference row set(s)", scenario.caseId(), seedsRows ? 1 : 0)
                    .containsExactlyElementsOf(seedsRows
                            ? List.of(new ParityCase.DatasetNormalisation(XREFFILE,
                                    ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                            : List.of());
        }
    }

    @Test
    @DisplayName("a negative zoned overpunch decodes to a scale-2 BigDecimal, not to a positive amount")
    void theNegativeOverpunchDecodesThroughTheCodec() {
        FixedWidthCodec codec = new FixedWidthCodec(ASCII);
        DalyTranRecord record = DalyTranRecord.decode(
                codec.encodeImage(dailyTransactionRowTwo(), "a DALYTRAN row"), ASCII);

        assertThat(record.dalytranAmtImage()).isEqualTo(AMOUNT_NEGATIVE);
        assertThat(record.dalytranAmt())
                .isEqualByComparingTo(AMOUNT_NEGATIVE_VALUE)
                .isNegative();
        assertThat(record.dalytranAmt().scale()).isEqualTo(2);
        assertThat(record.displayImage())
                .as("DISPLAY DALYTRAN-RECORD at :168 writes the whole group item, overpunch and all")
                .hasSize(DALYTRAN_WIDTH)
                .isEqualTo(dailyTransactionRowTwo());
    }

    @Test
    @DisplayName("Gate G13 - the orphan job, its step and its tasklet are all real and launchable")
    void theOrphanJobIsRunnable() {
        PostingRun unit = new PostingRun(scenarioOf(4));
        TransactionPostingJob job = unit.jobOverEmptyDatasets();

        assertThat(job.transactionPostingJob()).isNotNull();
        assertThat(job.transactionPostingJob().getName()).isEqualTo(TransactionPostingJob.JOB_NAME);
        assertThat(job.transactionPostingStep()).isNotNull();
        assertThat(job.transactionPostingStep().getName()).isEqualTo(TransactionPostingJob.STEP_NAME);
        assertThat(job.transactionPostingTasklet()).isNotNull();
        assertThat(job.jobParameters().getParameters())
                .as("no JCL declares an EXEC card for CBTRN01C, so the job takes no parameter")
                .isEmpty();
    }

    @Test
    @DisplayName("Gate G13 - no schedule, no runner, no launcher: the orphan runs only when asked")
    void nothingTriggersTheOrphanJob() {
        assertThat(TransactionPostingJob.class.getDeclaredAnnotations())
                .as("@Configuration and nothing else; @Scheduled or @EnableScheduling here would run "
                        + "the orphan on a timer")
                .hasSize(1);
        assertThat(TransactionPostingJob.class.getInterfaces())
                .as("no CommandLineRunner, no ApplicationRunner, no InitializingBean")
                .isEmpty();
        assertThat(Stream.of(TransactionPostingJob.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .as("no JobLauncher, runner or scheduler is held as state")
                .noneMatch(name -> name.contains("JobLauncher") || name.contains("Runner")
                        || name.contains("Scheduler"));
        assertThat(Stream.of(TransactionPostingJob.class.getDeclaredMethods())
                .flatMap(method -> Stream.of(method.getDeclaredAnnotations()))
                .map(annotation -> annotation.annotationType().getName()))
                .as("no method carries a scheduling annotation")
                .noneMatch(name -> name.contains("Scheduled"));
    }

    @Test
    @DisplayName("the account and transaction close failures abend with their own literals and RC 12")
    void theRemainingTwoCloseArmsAbendWithTheirOwnLiterals() {
        for (Map.Entry<String, String> arm : Map.of(ACCTFILE, ERROR_CLOSING_ACCTFILE,
                TRANFILE, ERROR_CLOSING_TRANFILE).entrySet()) {
            PostingRun unit = new PostingRun(scenarioOf(4),
                    Injection.closeFailure(arm.getKey(), STATUS_OPEN_FAILED));

            AbendException abend = unit.runExpectingAbend();

            assertThat(unit.lines())
                    .as("the close of %s failed, so its own paragraph's literal is displayed",
                            arm.getKey())
                    .containsExactlyElementsOf(concat(List.of(
                            List.of(START_BANNER),
                            skipLines(blanks(CARD_NUMBER_WIDTH), blanks(TRANSACTION_ID_WIDTH)),
                            abendLines(arm.getValue(), STATUS_OPEN_FAILED))));
            assertThat(unit.trace())
                    .as("every close up to and including %s was issued, and none after it", arm.getKey())
                    .containsExactlyElementsOf(concat(List.of(openTrace(ALL_DDS.size()),
                            recordTrace(false), closeTrace(ALL_DDS.indexOf(arm.getKey()) + 1))));
            assertThat(abend.getReturnCode())
                    .as("MOVE 12 TO APPL-RESULT precedes CALL 'CEE3ABD' at :473 (gate G35)")
                    .isEqualTo(RETURN_CODE_FATAL);
            assertThat(abend.getProgram()).isEqualTo(PROGRAM);
        }
    }

    @Test
    @DisplayName("CUSTFILE and CARDFILE are opened and closed but never read, both of them non-empty")
    void theCustomerAndCardFilesAreNeverRead() {
        PostingRun unit = new PostingRun(scenarioOf(11));

        ExecutionSummary summary = unit.run();

        assertThat(summary.recordsRead())
                .as("case11 is the case that seeds all six datasets, so 'never read' is a statement "
                        + "about files that hold rows rather than about files that are empty")
                .isEqualTo(2);
        assertThat(unit.customerFileVerbs())
                .as("0100-CUSTFILE-OPEN and 9100-CUSTFILE-CLOSE are the only customer-file verbs")
                .isNotEmpty()
                .noneMatch(verb -> verb.startsWith("read"));
        assertThat(unit.cardFileVerbs())
                .as("0300-CARDFILE-OPEN and 9300-CARDFILE-CLOSE are the only card-file verbs")
                .isNotEmpty()
                .noneMatch(verb -> verb.startsWith("read"));
        assertThat(unit.transactionFileVerbs())
                .as("0500-TRANFILE-OPEN and 9500-TRANFILE-CLOSE are the only transaction-file verbs: "
                        + "the posting this job's name promises is exactly the write it never issues")
                .isNotEmpty()
                .noneMatch(verb -> verb.startsWith("read") || verb.startsWith("write")
                        || verb.startsWith("rewrite") || verb.startsWith("openOutput"));
        assertThat(unit.trace())
                .as("no trace entry reads either file")
                .doesNotContain(TRACE_READ + CUSTFILE, TRACE_READ + CARDFILE, TRACE_READ + TRANFILE);
    }

    @Test
    @DisplayName("xrefLookups is always recordsRead + 1: the stale lookup happens on every run")
    void everyRunPerformsOneLookupBeyondItsRecordCount() {
        for (ParityScenario scenario : List.of(scenarioOf(4), scenarioOf(1), scenarioOf(10))) {
            PostingRun unit = new PostingRun(scenario);

            ExecutionSummary summary = unit.run();

            assertThat(summary.xrefLookups())
                    .as("%s read %d record(s)", scenario.caseId(), summary.recordsRead())
                    .isEqualTo(summary.recordsRead() + 1);
            assertThat(summary.postEndOfFileLookups()).isOne();
            assertThat(summary.recordImageLinesDisplayed())
                    .as("one DISPLAY DALYTRAN-RECORD per record read, from the mainline at :168 only")
                    .isEqualTo(summary.recordsRead());
            assertThat(summary.returnCode()).isEqualTo(RETURN_CODE_OK);
            assertThat(summary.completedCleanly()).isTrue();
        }
    }

    private record ParityScenario(ParityCase parityCase) {
        private ParityScenario {
            Objects.requireNonNull(parityCase, "A ParityCase is required: it is the expectation side");
        }

        private String caseId() {
            return parityCase.caseId();
        }

        @Override
        public String toString() {
            return parityCase.caseId();
        }
    }

    private static Injection injectionFrom(ParityCase.UnitStimulus stimulus) {
        if (!stimulus.operationScript().isEmpty() || !stimulus.linkage().isEmpty()
                || !stimulus.stepStatuses().isEmpty() || !stimulus.environment().isEmpty()) {
            throw new IllegalArgumentException("CBTRN01C takes no linkage, calls no subprogram, follows "
                    + "no job step and runs under no environment variant: it is an orphan with no JCL "
                    + "at all, so its only stimulus is its seeded rows and the outcome of one of its "
                    + "eighteen file call sites.");
        }
        Map<String, String> openStatus = new LinkedHashMap<>();
        Map<String, String> closeStatus = new LinkedHashMap<>();
        Integer cardOpenResp = null;
        boolean cardCloseRefused = false;
        String readStatus = null;
        int goodReads = 0;
        String xrefStatus = null;
        String accountStatus = null;
        for (Map.Entry<String, ParityCase.CallSiteOutcome> declared
                : stimulus.callSiteOutcomes().entrySet()) {
            String site = declared.getKey();
            ParityCase.CallSiteOutcome outcome = declared.getValue();
            String dataset = datasetOfCallSite(site);
            if (site.equals(TRACE_OPEN.strip() + '-' + dataset)) {
                if (CARDFILE.equals(dataset) && outcome.resp() != null) {
                    cardOpenResp = outcome.resp();
                } else {
                    openStatus.put(dataset, statusOf(site, outcome));
                }
            } else if (site.equals(TRACE_CLOSE.strip() + '-' + dataset)) {
                if (CARDFILE.equals(dataset)) {
                    requireRefusal(site, outcome);
                    cardCloseRefused = true;
                } else {
                    closeStatus.put(dataset, statusOf(site, outcome));
                }
            } else if (DALYTRAN.equals(dataset)) {
                readStatus = statusOf(site, outcome);
                goodReads = outcome.recordsBefore();
            } else if (XREFFILE.equals(dataset)) {
                xrefStatus = statusOf(site, outcome);
            } else {
                accountStatus = statusOf(site, outcome);
            }
        }
        return new Injection(openStatus, closeStatus, cardOpenResp, cardCloseRefused, readStatus,
                goodReads, xrefStatus, accountStatus);
    }

    private static String datasetOfCallSite(String site) {
        for (String verb : List.of(TRACE_OPEN, TRACE_READ, TRACE_CLOSE)) {
            String prefix = verb.strip() + '-';
            if (!site.startsWith(prefix)) {
                continue;
            }
            String dataset = site.substring(prefix.length());
            if (!ALL_DDS.contains(dataset)) {
                throw new IllegalArgumentException("Call site " + site + " names DD " + dataset
                        + ", which CBTRN01C declares no SELECT for. The six it declares are "
                        + ALL_DDS + '.');
            }
            if (TRACE_READ.equals(verb) && !READ_DDS.contains(dataset)) {
                throw new IllegalArgumentException("Call site " + site + " scripts a read of " + dataset
                        + ", which CBTRN01C opens and closes but never reads. The three it reads are "
                        + READ_DDS + ", so an outcome here would be scripted for a call that is never "
                        + "made and the case would assert nothing.");
            }
            return dataset;
        }
        throw new IllegalArgumentException("Call site " + site + " is not one of CBTRN01C's eighteen: "
                + "each is OPEN-, READ- or CLOSE- followed by one of " + ALL_DDS + '.');
    }

    private static String statusOf(String site, ParityCase.CallSiteOutcome outcome) {
        if (outcome.resp() != null) {
            throw new IllegalArgumentException("Call site " + site + " declares a CICS RESP, but the "
                    + "only open this module reports as a response is OPEN-" + CARDFILE + "'s. Declare "
                    + "a two-character FILE STATUS instead.");
        }
        if (outcome.status() != null) {
            return outcome.status();
        }
        return STATUS_PERMANENT_ERROR;
    }

    private static void requireRefusal(String site, ParityCase.CallSiteOutcome outcome) {
        if (!outcome.isRefused() || outcome.status() != null) {
            throw new IllegalArgumentException("Call site " + site + " reports no status at all: "
                    + "9300-CARDFILE-CLOSE ends a browse, and ending a browse either succeeds or is "
                    + "refused. Declare \"refused\": true.");
        }
    }

    private record Injection(Map<String, String> openStatus, Map<String, String> closeStatus,
                             Integer cardOpenResp, boolean cardCloseRefused, String readStatus,
                             int goodReadsBeforeReadStatus, String xrefStatus, String accountStatus) {
        private Injection {
            openStatus = requireStatusesByDd(openStatus, "openStatus");
            closeStatus = requireStatusesByDd(closeStatus, "closeStatus");
            if (readStatus != null && readStatus.length() != 2) {
                throw new IllegalArgumentException("A FILE STATUS is exactly two characters, as "
                        + "DALYTRAN-STATUS is declared at app/cbl/CBTRN01C.cbl:100-102; got '"
                        + readStatus + '\'');
            }
            if (goodReadsBeforeReadStatus < 0) {
                throw new IllegalArgumentException("A run cannot read " + goodReadsBeforeReadStatus
                        + " records before its read fails");
            }
        }

        private static Injection none() {
            return new Injection(Map.of(), Map.of(), null, false, null, 0, null, null);
        }

        private static Injection openFailure(String dataset, String status) {
            return new Injection(Map.of(dataset, status), Map.of(), null, false, null, 0, null, null);
        }

        private static Injection cardOpenResp(int resp) {
            return new Injection(Map.of(), Map.of(), resp, false, null, 0, null, null);
        }

        private static Injection closeFailure(String dataset, String status) {
            return new Injection(Map.of(), Map.of(dataset, status), null, false, null, 0, null, null);
        }

        private static Injection refusedCardClose() {
            return new Injection(Map.of(), Map.of(), null, true, null, 0, null, null);
        }

        private static Injection readFailure(int goodReads, String status) {
            return new Injection(Map.of(), Map.of(), null, false, status, goodReads, null, null);
        }

        private static Injection xrefStatus(String status) {
            return new Injection(Map.of(), Map.of(), null, false, null, 0, status, null);
        }

        private static Injection accountStatus(String status) {
            return new Injection(Map.of(), Map.of(), null, false, null, 0, null, status);
        }

        private String openStatusOf(String dataset) {
            return openStatus.getOrDefault(dataset, STATUS_OK);
        }

        private String closeStatusOf(String dataset) {
            return closeStatus.getOrDefault(dataset, STATUS_OK);
        }

        private int cardOpenRespOrNormal() {
            return cardOpenResp == null ? CICS_RESP_NORMAL : cardOpenResp;
        }

        private static Map<String, String> requireStatusesByDd(Map<String, String> source,
                String member) {
            Objects.requireNonNull(source, "Injection." + member + " is required; use Map.of()");
            for (Map.Entry<String, String> entry : source.entrySet()) {
                if (!ALL_DDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Injection." + member + " names DD "
                            + entry.getKey() + ", which CBTRN01C declares no SELECT for, so nothing "
                            + "would ever ask for it and the injection would force nothing. The six it "
                            + "declares are " + ALL_DDS + '.');
                }
                if (entry.getValue() == null || entry.getValue().length() != 2) {
                    throw new IllegalArgumentException("Injection." + member + '[' + entry.getKey()
                            + "] is not a two-character FILE STATUS");
                }
            }
            return Map.copyOf(source);
        }
    }

    private static String effectiveOpenStatus(Injection injection, String dataset) {
        return CARDFILE.equals(dataset)
                ? batchStatusOfCicsResponse(injection.cardOpenRespOrNormal())
                : injection.openStatusOf(dataset);
    }

    private static String batchStatusOfCicsResponse(int resp) {
        if (resp == CICS_RESP_NORMAL) {
            return STATUS_OK;
        }
        if (resp == CICS_RESP_NOTFND) {
            return STATUS_NOT_FOUND;
        }
        if (resp == CICS_RESP_DUPREC) {
            return STATUS_DUPLICATE;
        }
        return STATUS_PERMANENT_ERROR;
    }

    private static final class PostingRun implements ParityHarness.ParityUnit {
        private final ParityScenario scenario;

        private final Injection injection;

        private final List<String> lines = new ArrayList<>();

        private final List<String> trace = new ArrayList<>();

        private final List<ParityCase.ExpectedOperation> operations = new ArrayList<>();

        private final Set<String> openedOk = new LinkedHashSet<>();

        private CustomerRepository customerRepository;

        private CustomerRepository.CustomerFile custfile;

        private CardRepository cardRepository;

        private CardRepository.CardBrowse cardBrowse;

        private TransactionRepository transactionRepository;

        private TransactionRepository.InputFile tranfile;

        private ExecutionSummary summary;

        private AbendException abend;

        private PostingRun(ParityScenario scenario) {
            this(scenario, injectionFrom(scenario.parityCase().unitStimulus()));
        }

        private PostingRun(ParityScenario scenario, Injection injection) {
            this.scenario = Objects.requireNonNull(scenario, "A scenario is required");
            this.injection = Objects.requireNonNull(injection, "An Injection is required");
        }

        @Override
        public ParityHarness.UnitOutcome invoke(ParityHarness.Invocation invocation) {
            TransactionPostingJob job = job(seededInputs(invocation));
            ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();
            try {
                summary = job.execute(lines::add);
                record(recorder, invocation);
                recorder.returnCode(summary.returnCode());
            } catch (AbendException raised) {
                abend = raised;
                record(recorder, invocation);
                throw raised;
            }
            return null;
        }

        private void record(ParityHarness.UnitOutcome.Builder recorder,
                ParityHarness.Invocation invocation) {
            for (String dataset : openedOk) {
                recorder.openedWithoutWriting(dataset, layoutOf(dataset));
                recorder.finalState(dataset, layoutOf(dataset), rowsOf(invocation, dataset));
            }
            for (String line : lines) {
                recorder.display(line);
            }
        }

        private ExecutionSummary run() {
            ParityHarness.usAscii().run(scenario.parityCase(), UNIT_KIND, this);
            if (summary == null) {
                throw new IllegalStateException("Case " + scenario.caseId() + " abended, so it has no "
                        + "ExecutionSummary. Use runExpectingAbend() for a case that abends.");
            }
            return summary;
        }

        private AbendException runExpectingAbend() {
            ParityHarness.usAscii().run(scenario.parityCase(), UNIT_KIND, this);
            if (abend == null) {
                throw new IllegalStateException("Case " + scenario.caseId() + " was expected to abend "
                        + "and returned normally instead, which means the arm under test was never "
                        + "reached and the assertions below would pass for the wrong reason.");
            }
            return abend;
        }

        private TransactionPostingJob jobOverEmptyDatasets() {
            return job(new SeededInputs(List.of(), Map.of(), Map.of()));
        }

        private List<String> lines() {
            return List.copyOf(lines);
        }

        private List<String> trace() {
            return List.copyOf(trace);
        }

        private List<ParityCase.ExpectedOperation> operations() {
            return List.copyOf(operations);
        }

        private List<String> openAndCloseTrace() {
            return trace.stream().filter(entry -> !entry.startsWith(TRACE_READ)).toList();
        }

        private List<String> customerFileVerbs() {
            return verbsOn(customerRepository, custfile);
        }

        private List<String> cardFileVerbs() {
            return verbsOn(cardRepository, cardBrowse);
        }

        private List<String> transactionFileVerbs() {
            return verbsOn(transactionRepository, tranfile);
        }

        private static List<String> verbsOn(Object... collaborators) {
            List<String> names = new ArrayList<>();
            for (Object collaborator : collaborators) {
                mockingDetails(collaborator).getInvocations()
                        .forEach(invocation -> names.add(invocation.getMethod().getName()));
            }
            return List.copyOf(names);
        }

        private String opened(String dataset, String status) {
            trace.add(TRACE_OPEN + dataset);
            if (STATUS_OK.equals(status)) {
                openedOk.add(dataset);
            }
            return status;
        }

        private int openedWithResponse(String dataset, int resp) {
            trace.add(TRACE_OPEN + dataset);
            if (STATUS_OK.equals(batchStatusOfCicsResponse(resp))) {
                openedOk.add(dataset);
            }
            return resp;
        }

        private String closed(String dataset, String status) {
            trace.add(TRACE_CLOSE + dataset);
            return status;
        }

        private void readNext() {
            trace.add(TRACE_READ + DALYTRAN);
            operations.add(new ParityCase.ExpectedOperation(DALYTRAN,
                    ParityCase.RepositoryOperation.READ_NEXT, null));
        }

        private void readByKey(String dataset, String key) {
            trace.add(TRACE_READ + dataset);
            String trimmed = key == null ? "" : key.strip();
            operations.add(new ParityCase.ExpectedOperation(dataset,
                    ParityCase.RepositoryOperation.READ, trimmed.isEmpty() ? null : trimmed));
        }

        private TransactionPostingJob job(SeededInputs inputs) {
            DalyTranRepository dalyTran = stubDalyTranRepository(inputs.daily());
            CardXrefRepository cardXref = stubCardXrefRepository(inputs.crossReference());
            AccountRepository account = stubAccountRepository(inputs.accounts());
            customerRepository = stubCustomerRepository();
            cardRepository = stubCardRepository();
            transactionRepository = stubTransactionRepository();
            return new TransactionPostingJob(batchConfig(), dalyTran, customerRepository, cardXref,
                    cardRepository, account, transactionRepository,
                    new SuppliedProvider<SysoutSink>(null));
        }

        private DalyTranRepository stubDalyTranRepository(List<DalyTranRecord> records) {
            DalyTranRepository repository = mock(DalyTranRepository.class);
            DalyTranRepository.DalytranFile dalytranFile = mock(DalyTranRepository.DalytranFile.class);
            when(repository.datasetCharset()).thenReturn(ASCII);
            when(repository.open()).thenReturn(dalytranFile);
            when(dalytranFile.openStatus())
                    .thenAnswer(call -> opened(DALYTRAN, injection.openStatusOf(DALYTRAN)));
            when(dalytranFile.closeFile())
                    .thenAnswer(call -> closed(DALYTRAN, injection.closeStatusOf(DALYTRAN)));
            Deque<DalyTranRepository.ReadResult> reads = readQueue(records);
            when(dalytranFile.readNext()).thenAnswer(call -> {
                readNext();
                return reads.isEmpty() ? DalyTranRepository.ReadResult.endOfFile() : reads.removeFirst();
            });
            return repository;
        }

        private Deque<DalyTranRepository.ReadResult> readQueue(List<DalyTranRecord> records) {
            Deque<DalyTranRepository.ReadResult> queue = new ArrayDeque<>();
            int good = injection.readStatus() == null
                    ? records.size()
                    : Math.min(injection.goodReadsBeforeReadStatus(), records.size());
            for (int index = 0; index < good; index++) {
                queue.add(DalyTranRepository.ReadResult.found(records.get(index)));
            }
            queue.add(injection.readStatus() == null
                    ? DalyTranRepository.ReadResult.endOfFile()
                    : DalyTranRepository.ReadResult.other(injection.readStatus()));
            return queue;
        }

        private CustomerRepository stubCustomerRepository() {
            CustomerRepository repository = mock(CustomerRepository.class);
            custfile = mock(CustomerRepository.CustomerFile.class);
            when(repository.openInput()).thenReturn(custfile);
            when(custfile.openStatus())
                    .thenAnswer(call -> opened(CUSTFILE, injection.openStatusOf(CUSTFILE)));
            when(custfile.closeFile())
                    .thenAnswer(call -> closed(CUSTFILE, injection.closeStatusOf(CUSTFILE)));
            return repository;
        }

        private CardXrefRepository stubCardXrefRepository(Map<String, CardXrefRecord> rows) {
            CardXrefRepository repository = mock(CardXrefRepository.class);
            CardXrefRepository.BrowseCursor xrefCursor = mock(CardXrefRepository.BrowseCursor.class);
            when(repository.addressing(any(), anyString(), isNull(), anyString()))
                    .thenReturn(repository);
            when(repository.openBrowse()).thenReturn(xrefCursor);
            when(xrefCursor.openStatus())
                    .thenAnswer(call -> opened(XREFFILE, injection.openStatusOf(XREFFILE)));
            when(xrefCursor.closeBrowse())
                    .thenAnswer(call -> closed(XREFFILE, injection.closeStatusOf(XREFFILE)));
            when(repository.readByCardNumber(anyString())).thenAnswer(call -> {
                readByKey(XREFFILE, call.getArgument(0, String.class));
                CardXrefRecord found = rows.get(call.<String>getArgument(0));
                if (injection.xrefStatus() != null) {
                    return forcedXrefOutcome(injection.xrefStatus(), found);
                }
                return found == null
                        ? CardXrefRepository.ReadResult.notFound(XREFFILE)
                        : CardXrefRepository.ReadResult.found(XREFFILE, found, storedImage(found));
            });
            return repository;
        }

        private static CardXrefRepository.ReadResult forcedXrefOutcome(String status,
                CardXrefRecord found) {
            if (STATUS_NOT_FOUND.equals(status)) {
                return CardXrefRepository.ReadResult.notFound(XREFFILE);
            }
            if (STATUS_DUPLICATE.equals(status)) {
                if (found == null) {
                    throw new IllegalStateException("A '" + STATUS_DUPLICATE + "' outcome carries the "
                            + "first matching record, because CICS returns one alongside the duplicate "
                            + "condition, so the case must seed the row it duplicates.");
                }
                return CardXrefRepository.ReadResult.duplicate(XREFFILE, found, storedImage(found),
                        CICS_RESP_DUPREC);
            }
            return CardXrefRepository.ReadResult.other(XREFFILE, status);
        }

        private CardRepository stubCardRepository() {
            CardRepository repository = mock(CardRepository.class);
            cardBrowse = mock(CardRepository.CardBrowse.class);
            when(repository.addressing(any(), anyString())).thenReturn(repository);
            when(repository.openBrowse(anyString(), any())).thenReturn(cardBrowse);
            when(cardBrowse.openResp())
                    .thenAnswer(call -> openedWithResponse(CARDFILE, injection.cardOpenRespOrNormal()));
            doAnswer(call -> {
                closed(CARDFILE, STATUS_OK);
                if (injection.cardCloseRefused()) {
                    throw new IllegalStateException("the card browse could not be ended");
                }
                return null;
            }).when(cardBrowse).endBrowse();
            return repository;
        }

        private AccountRepository stubAccountRepository(Map<Long, AccountRecord> rows) {
            AccountRepository repository = mock(AccountRepository.class);
            AccountRepository.AccountFile acctfile = mock(AccountRepository.AccountFile.class);
            when(repository.open(AccountRepository.OpenMode.INPUT)).thenReturn(acctfile);
            when(acctfile.openStatus())
                    .thenAnswer(call -> opened(ACCTFILE, injection.openStatusOf(ACCTFILE)));
            when(acctfile.closeFile())
                    .thenAnswer(call -> closed(ACCTFILE, injection.closeStatusOf(ACCTFILE)));
            when(acctfile.readByKey(anyLong())).thenAnswer(call -> {
                readByKey(ACCTFILE, ACCOUNT_KEY_CODEC.movePic9(call.getArgument(0, Long.class),
                        ACCOUNT_ID_WIDTH));
                if (injection.accountStatus() != null) {
                    return AccountRepository.ReadResult.of(injection.accountStatus());
                }
                AccountRecord found = rows.get(call.<Long>getArgument(0));
                return found == null
                        ? AccountRepository.ReadResult.notFound()
                        : AccountRepository.ReadResult.found(found);
            });
            return repository;
        }

        private TransactionRepository stubTransactionRepository() {
            TransactionRepository repository = mock(TransactionRepository.class);
            tranfile = mock(TransactionRepository.InputFile.class);
            when(repository.openInput(any(DatasetBinding.class))).thenReturn(tranfile);
            when(tranfile.openStatus())
                    .thenAnswer(call -> opened(TRANFILE, injection.openStatusOf(TRANFILE)));
            when(tranfile.closeInput())
                    .thenAnswer(call -> closed(TRANFILE, injection.closeStatusOf(TRANFILE)));
            return repository;
        }
    }

    private record SeededInputs(List<DalyTranRecord> daily,
                                Map<String, CardXrefRecord> crossReference,
                                Map<Long, AccountRecord> accounts) {
    }

    private static SeededInputs seededInputs(ParityHarness.Invocation invocation) {
        FixedWidthCodec codec = invocation.codec();
        List<DalyTranRecord> daily = new ArrayList<>();
        for (String row : rowsOf(invocation, DALYTRAN)) {
            daily.add(DalyTranRecord.decode(row, ASCII));
        }
        Map<String, CardXrefRecord> crossReference = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, XREFFILE)) {
            CardXrefRecord record = CardXrefRecord.decode(
                    codec.encodeImage(row, "an " + XREFFILE + " row"), codec);
            crossReference.put(record.xrefCardNum(), record);
        }
        Map<Long, AccountRecord> accounts = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, ACCTFILE)) {
            AccountRecord record = AccountRecord.decode(row, ASCII);
            accounts.put(record.getAcctId(), record);
        }
        return new SeededInputs(List.copyOf(daily), Map.copyOf(crossReference), Map.copyOf(accounts));
    }

    private static List<String> rowsOf(ParityHarness.Invocation invocation, String dataset) {
        return invocation.hasDataset(dataset) ? invocation.dataset(dataset).rows() : List.of();
    }

    private static String storedImage(CardXrefRecord record) {
        return new String(record.encode(ASCII), ASCII);
    }

    private static final String TEST_DSNAME_PREFIX = "TEST.CBTRN01C.";

    private static BatchConfig batchConfig() {
        JobContracts contracts = new JobContracts();
        contracts.put(TransactionPostingJob.JOB_KEY, new JobContract(PROGRAM, List.of(),
                List.of(new StepContract(TransactionPostingJob.STEP_NAME, PROGRAM, false)), null,
                Map.of()));
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, bindings());
    }

    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        for (String dataset : ALL_DDS) {
            catalogue.put(dataset, binding(dataset));
        }
        return catalogue;
    }

    private static DatasetBinding binding(String dataset) {
        boolean sequential = DALYTRAN.equals(dataset);
        return new DatasetBinding(TEST_DSNAME_PREFIX + dataset,
                sequential ? "sequential" : DatasetBinding.KSDS,
                false,
                "FB",
                sequential ? Integer.valueOf(0) : null,
                widthOf(dataset),
                copybookOf(dataset),
                sequential ? null : Integer.valueOf(keyLengthOf(dataset)),
                sequential ? null : Integer.valueOf(0),
                null,
                null);
    }

    private static final class SuppliedProvider<T> implements ObjectProvider<T> {
        private final T bean;

        private SuppliedProvider(T bean) {
            this.bean = bean;
        }

        @Override
        public T getObject() {
            if (bean == null) {
                throw new NoSuchBeanDefinitionException("no bean of this type is published");
            }
            return bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }

        @Override
        public Stream<T> stream() {
            return bean == null ? Stream.empty() : Stream.of(bean);
        }
    }

    private static int widthOf(String dataset) {
        return switch (dataset) {
            case DALYTRAN -> DALYTRAN_WIDTH;
            case CUSTFILE -> CUSTFILE_WIDTH;
            case XREFFILE -> XREF_WIDTH;
            case CARDFILE -> CARDFILE_WIDTH;
            case ACCTFILE -> ACCTFILE_WIDTH;
            case TRANFILE -> TRANFILE_WIDTH;
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    private static String copybookOf(String dataset) {
        return switch (dataset) {
            case DALYTRAN -> "CVTRA06Y";
            case CUSTFILE -> "CVCUS01Y";
            case XREFFILE -> "CVACT03Y";
            case CARDFILE -> "CVACT02Y";
            case ACCTFILE -> "CVACT01Y";
            case TRANFILE -> "CVTRA05Y";
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    private static int keyLengthOf(String dataset) {
        return switch (dataset) {
            case CUSTFILE -> CUSTOMER_ID_WIDTH;
            case XREFFILE, CARDFILE, TRANFILE -> CARD_NUMBER_WIDTH;
            case ACCTFILE -> ACCOUNT_ID_WIDTH;
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    private static RecordLayout layoutOf(String dataset) {
        return switch (dataset) {
            case DALYTRAN -> DalyTranRecord.LAYOUT;
            case CUSTFILE -> CustomerRecord.LAYOUT;
            case XREFFILE -> CardXrefRecord.LAYOUT;
            case CARDFILE -> CardRecord.LAYOUT;
            case ACCTFILE -> AccountRecord.LAYOUT;
            case TRANFILE -> TranRecord.LAYOUT;
            default -> throw new IllegalArgumentException(unknownDataset(dataset));
        };
    }

    private static String unknownDataset(String dataset) {
        return "CBTRN01C declares no SELECT for dataset " + dataset + "; the six it declares at "
                + "app/cbl/CBTRN01C.cbl:29-62 are " + ALL_DDS + '.';
    }

    private static Seeds seeds() {
        return new Seeds();
    }

    private static final class Seeds {
        private final Map<String, ParityCase.DatasetInput> inputs = new LinkedHashMap<>();

        private Seeds with(String dataset, ParityCase.DatasetInput input) {
            inputs.put(dataset, input);
            return this;
        }

        private Map<String, ParityCase.DatasetInput> build() {
            return new LinkedHashMap<>(inputs);
        }
    }

    private static ParityCase.DatasetInput rows(String... images) {
        return new ParityCase.DatasetInput(List.of(images), null, null, null, null, null, null);
    }

    private static List<String> xrefFoundLines(String cardNumber, String customerIdImage,
            String accountIdImage) {
        return List.of(SUCCESSFUL_READ_OF_XREF,
                XREF_CARD_NUMBER_PREFIX + exact(cardNumber, CARD_NUMBER_WIDTH, "XREF-CARD-NUM"),
                XREF_ACCOUNT_ID_PREFIX + exact(accountIdImage, ACCOUNT_ID_WIDTH, "XREF-ACCT-ID"),
                XREF_CUSTOMER_ID_PREFIX + exact(customerIdImage, CUSTOMER_ID_WIDTH, "XREF-CUST-ID"));
    }

    private static List<String> xrefFoundLines(String cardNumber, int customerId, long accountId) {
        return xrefFoundLines(cardNumber, digits(customerId, CUSTOMER_ID_WIDTH),
                digits(accountId, ACCOUNT_ID_WIDTH));
    }

    private static List<String> skipLines(String cardNumber, String transactionId) {
        return List.of(INVALID_CARD_NUMBER_FOR_XREF,
                CARD_NOT_VERIFIED_PREFIX + exact(cardNumber, CARD_NUMBER_WIDTH, "DALYTRAN-CARD-NUM")
                        + CARD_NOT_VERIFIED_SUFFIX
                        + exact(transactionId, TRANSACTION_ID_WIDTH, "DALYTRAN-ID"));
    }

    private static List<String> accountNotFoundLines(String accountIdImage) {
        return List.of(INVALID_ACCOUNT_NUMBER_FOUND,
                ACCOUNT_NOT_FOUND_PREFIX + exact(accountIdImage, ACCOUNT_ID_WIDTH, "ACCT-ID")
                        + ACCOUNT_NOT_FOUND_SUFFIX);
    }

    private static List<String> accountNotFoundLines(long accountId) {
        return accountNotFoundLines(digits(accountId, ACCOUNT_ID_WIDTH));
    }

    private static List<String> abendLines(String errorText, String status) {
        return List.of(errorText, fileStatusLine(status), ABENDING_PROGRAM);
    }

    private static String fileStatusLine(String status) {
        char first = status.charAt(0);
        char second = status.charAt(1);
        String image = first == '9' || !isSingleByteDigit(first) || !isSingleByteDigit(second)
                ? first + String.format("%03d", ((int) second) & 0xFF)
                : "00" + status;
        return FILE_STATUS_PREFIX + image;
    }

    private static boolean isSingleByteDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static List<String> openTrace(int count) {
        List<String> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(TRACE_OPEN + ALL_DDS.get(index));
        }
        return List.copyOf(entries);
    }

    private static List<String> closeTrace(int count) {
        List<String> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(TRACE_CLOSE + ALL_DDS.get(index));
        }
        return List.copyOf(entries);
    }

    private static List<String> recordTrace(boolean accountRead) {
        List<String> entries = new ArrayList<>(3);
        entries.add(TRACE_READ + DALYTRAN);
        entries.add(TRACE_READ + XREFFILE);
        if (accountRead) {
            entries.add(TRACE_READ + ACCTFILE);
        }
        return List.copyOf(entries);
    }

    private static List<String> concat(List<List<String>> parts) {
        List<String> flattened = new ArrayList<>();
        for (List<String> part : parts) {
            flattened.addAll(part);
        }
        return List.copyOf(flattened);
    }

    private static String dalytranRow(String transactionId, String typeCode, String categoryCode,
            String source, String description, String amountImage, String merchantId,
            String merchantName, String merchantCity, String merchantZip, String cardNumber,
            String originTimestamp, String processTimestamp) {
        String image = exact(transactionId, TRANSACTION_ID_WIDTH, "DALYTRAN-ID")
                + exact(typeCode, 2, "DALYTRAN-TYPE-CD")
                + exact(categoryCode, 4, "DALYTRAN-CAT-CD")
                + pad(source, 10)
                + pad(description, 100)
                + exact(amountImage, 11, "DALYTRAN-AMT")
                + exact(merchantId, 9, "DALYTRAN-MERCHANT-ID")
                + pad(merchantName, 50)
                + pad(merchantCity, 50)
                + pad(merchantZip, 10)
                + exact(cardNumber, CARD_NUMBER_WIDTH, "DALYTRAN-CARD-NUM")
                + pad(originTimestamp, 26)
                + pad(processTimestamp, 26)
                + blanks(20);
        return requireWidth(image, DALYTRAN_WIDTH, copybookOf(DALYTRAN));
    }

    private static String dailyTransactionRowOne() {
        return dalytranRow(TRAN_ID_ONE, "01", "0001", "POS TERM", "Purchase at Abshire-Lowe",
                AMOUNT_POSITIVE, MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112", CARD_ONE,
                ORIGIN_TIMESTAMP, PROCESS_TIMESTAMP_BLANK);
    }

    private static String dailyTransactionRowTwo() {
        return dalytranRow(TRAN_ID_TWO, "03", "0001", "OPERATOR",
                "Return item at Nitzsche, Nicolas and Lowe", AMOUNT_NEGATIVE, MERCHANT_ID,
                "Nitzsche, Nicolas and Lowe", "Fidelshire", "53378", CARD_TWO, ORIGIN_TIMESTAMP,
                PROCESS_TIMESTAMP_BLANK);
    }

    private static String accountRow(long accountId, String activeStatus, String currentBalance,
            String creditLimit, String cashCreditLimit, String openDate, String expiraionDate,
            String reissueDate, String currentCycleCredit, String currentCycleDebit,
            String addressZip, String groupId) {
        String image = digits(accountId, ACCOUNT_ID_WIDTH)
                + exact(activeStatus, 1, "ACCT-ACTIVE-STATUS")
                + exact(currentBalance, 12, "ACCT-CURR-BAL")
                + exact(creditLimit, 12, "ACCT-CREDIT-LIMIT")
                + exact(cashCreditLimit, 12, "ACCT-CASH-CREDIT-LIMIT")
                + exact(openDate, 10, "ACCT-OPEN-DATE")
                + exact(expiraionDate, 10, "ACCT-EXPIRAION-DATE")
                + exact(reissueDate, 10, "ACCT-REISSUE-DATE")
                + exact(currentCycleCredit, 12, "ACCT-CURR-CYC-CREDIT")
                + exact(currentCycleDebit, 12, "ACCT-CURR-CYC-DEBIT")
                + pad(addressZip, 10)
                + pad(groupId, 10)
                + blanks(178);
        return requireWidth(image, ACCTFILE_WIDTH, copybookOf(ACCTFILE));
    }

    private static String accountRowOne() {
        return accountRow(ACCT_ONE, "Y", "00000001930{", "00000020650{", "00000002640{", "2012-10-12",
                "2024-12-13", "2024-12-13", "00000000000{", "00000000000{", "A000000000", "");
    }

    private static String accountRowTwo() {
        return accountRow(ACCT_TWO, "Y", "00000003690{", "00000037670{", "00000010400{", "2014-02-27",
                "2024-03-13", "2024-03-13", "00000000000{", "00000000000{", "A000000000", "");
    }

    private static String xrefFixtureRow(String cardNumber, int customerId, long accountId) {
        String row = exact(cardNumber, CARD_NUMBER_WIDTH, CardXrefRecord.XREF_CARD_NUM_NAME)
                + digits(customerId, CUSTOMER_ID_WIDTH)
                + digits(accountId, ACCOUNT_ID_WIDTH);
        return requireWidth(row, XREF_FIXTURE_WIDTH, copybookOf(XREFFILE));
    }

    private static String xrefRecordImage(String cardNumber, int customerId, long accountId) {
        return requireWidth(xrefFixtureRow(cardNumber, customerId, accountId)
                + blanks(CardXrefRecord.FILLER_LENGTH), XREF_WIDTH, copybookOf(XREFFILE));
    }

    private static String blanks(int width) {
        return " ".repeat(width);
    }

    private static String pad(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException('\'' + value + "' is " + value.length()
                    + " characters and the receiver is PIC X(" + width + "). A COBOL MOVE would truncate "
                    + "on the right; state the truncated image outright rather than letting a composer "
                    + "shorten it.");
        }
        return value + blanks(width - value.length());
    }

    private static String exact(String value, int width, String field) {
        if (value.length() != width) {
            throw new IllegalArgumentException(field + " is declared " + width + " characters and '"
                    + value + "' is " + value.length() + ". A zoned numeric span is zero-filled to its "
                    + "width and a key fills it exactly, so neither may be padded here.");
        }
        return value;
    }

    private static String digits(long value, int width) {
        return exact(String.format("%0" + width + "d", value), width, "a PIC 9(" + width + ") item");
    }

    private static String requireWidth(String image, int width, String copybook) {
        if (image.length() != width) {
            throw new IllegalStateException("A record composed from " + copybook + "'s spans measures "
                    + image.length() + " characters where the copybook declares " + width
                    + ". A record short by exactly one span is a dropped FILLER, and every offset after "
                    + "it is wrong.");
        }
        return image;
    }

    private static List<String> expectedCaseIds() {
        List<String> identifiers = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            identifiers.add(ParityHarness.caseId(ordinal));
        }
        return List.copyOf(identifiers);
    }

    private record FullPass(List<String> lines, List<String> trace,
                            List<ParityCase.ExpectedRecord> finalStateRows) {
    }

    private static List<String> fixtureRows(String fixture) {
        String resource = FIXTURE_ROOT + fixture;
        try (InputStream stream =
                CBTRN01CParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture " + resource + " is not on the test classpath. "
                        + "The nine fixtures are byte-for-byte copies of app/data/ASCII and are what "
                        + "every parity case is seeded from.");
            }
            List<String> rows = new ArrayList<>();
            for (String row : new String(stream.readAllBytes(), ASCII).split("\n", -1)) {
                if (row.isEmpty()) {
                    continue;
                }
                if (row.indexOf('\r') >= 0) {
                    throw new IllegalStateException("A row of fixture " + resource + " contains a "
                            + "carriage return, so the working tree translated the line endings and "
                            + "every row is one byte wider than its copybook. Restore the fixture rather "
                            + "than stripping the byte here.");
                }
                rows.add(row);
            }
            return List.copyOf(rows);
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading fixture " + resource + " failed", failure);
        }
    }
}
