package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.CardRepository.CardWriteResult;
import com.vsergeychik.carddemo.card.CardUpdateService.ChangeCheck;
import com.vsergeychik.carddemo.card.CardUpdateService.WriteOutcome;
import com.vsergeychik.carddemo.card.CardUpdateService.WriteResult;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CardUpdateService} against {@code app/cbl/COCRDUPC.cbl:1420-1523}.
 *
 * <p>Every test here builds the service with {@code new CardUpdateService(mock(CardRepository.class))}
 * and nothing else - no Spring context, no {@code MockMvc}, no {@code JobLauncher} - which is the
 * arrangement gate G51 exists to require and the reason the decision logic sits in a service at all.
 *
 * <p>The code page is {@code US-ASCII} throughout. That is a deliberate choice rather than a default:
 * the one place the code page can change an answer is the zoned CVV decode, and the space character
 * that the running program actually feeds it has a low-order nibble of zero in {@code US-ASCII} and in
 * {@code IBM037} alike, so the assertions below hold under either. The one case where the two code
 * pages genuinely diverge - a letter whose low nibble exceeds nine - is called out where it is
 * exercised.
 */
@DisplayName("CardUpdateService - COCRDUPC 9200-WRITE-PROCESSING and 9300-CHECK-CHANGE-IN-REC")
class CardUpdateServiceTest {

    /** The card number the fixtures use; {@code CARD-NUM PIC X(16)}, so exactly sixteen digits. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** {@code CARD-ACCT-ID PIC 9(11)}. */
    private static final long ACCOUNT_ID = 12_345_678_901L;

    /** {@code CARD-CVV-CD PIC 9(03)}. */
    private static final int CVV = 747;

    /** {@code CARD-EMBOSSED-NAME PIC X(50)}, given here unpadded. */
    private static final String EMBOSSED_NAME = "JOHN Q PUBLIC";

    /** {@code CARD-EXPIRAION-DATE PIC X(10)} - the copybook's misspelling, kept. */
    private static final String EXPIRY = "2027-03-09";

    /** {@code CARD-ACTIVE-STATUS PIC X(01)}. */
    private static final String ACTIVE_STATUS = "Y";

    /** A message already sitting in {@code WS-RETURN-MSG}, to drive the {@code :1445} guard. */
    private static final String EARLIER_MESSAGE = "Card expiry month must be between 1 and 12";

    private CardRepository cardRepository;
    private DatasetUnitOfWork unitOfWork;
    private CardUpdateService service;
    private FixedWidthCodec codec;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CardRepository.class);
        unitOfWork = realUnitOfWork();
        service = new CardUpdateService(cardRepository, unitOfWork);
        codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
    }

    /**
     * A unit of work over a real transaction manager and a real single connection.
     *
     * <p>Deliberately not a stub. The whole point of {@code 9200-WRITE-PROCESSING} is that the lock the
     * read takes survives to the rewrite, and only a real transaction can be observed doing that; a
     * double that simply ran the body would let the arrangement pass while proving nothing. An
     * in-memory database is used because the subject is the boundary, which is the framework's
     * behaviour rather than the deployment driver's.
     *
     * @return a unit of work whose {@code execute} opens a genuine transaction
     */
    private static DatasetUnitOfWork realUnitOfWork() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:card-update-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        source.setSuppressClose(true);
        DataSource dataSource = source;
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
    }

    // =================================================================================================
    // Fixtures
    // =================================================================================================

    /**
     * The record the repository hands back under the lock.
     *
     * @return the stored record
     */
    private CardRecord storedRecord() {
        return new CardRecord(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, ACTIVE_STATUS);
    }

    /**
     * A {@code CCUP-OLD-DETAILS} snapshot that agrees with {@code record} on all six compared items,
     * so {@code 9300-CHECK-CHANGE-IN-REC} finds nothing changed.
     *
     * @param record the stored record to mirror
     * @return the matching snapshot
     */
    private CardDetails matchingOldDetails(CardRecord record) {
        return new CardDetails(DetailGroup.OLD,
                record.cardAcctIdImage(codec),
                record.cardNum(),
                record.cardCvvCdImage(codec),
                record.cardEmbossedName(),
                record.cardExpiraionDateYear(),
                record.cardExpiraionDateMonth(),
                record.cardExpiraionDateDay(),
                record.cardActiveStatus());
    }

    /**
     * A {@code CCUP-NEW-DETAILS} snapshot carrying what the user typed. Its CVV is three spaces,
     * because {@code CCUP-NEW-CVV-CD} is never assigned anywhere in {@code COCRDUPC} and
     * {@code :586} initialises it on every pass.
     *
     * @return the new-details snapshot
     */
    private CardDetails typedNewDetails() {
        return new CardDetails(DetailGroup.NEW,
                codec.movePic9(ACCOUNT_ID, CardDetails.ACCTID_LENGTH),
                CARD_NUMBER,
                null,
                "JANE R CITIZEN",
                "2029",
                "11",
                "30",
                "N");
    }

    /**
     * The {@code CVCRD01Y} work area {@code 9200-WRITE-PROCESSING} reads its key and account from.
     *
     * @return the work area
     */
    private CardScreenState workArea() {
        CardScreenState state = new CardScreenState();
        state.setCcCardNum(CARD_NUMBER);
        state.setCcAcctIdN(ACCOUNT_ID);
        return state;
    }

    /**
     * Runs the write path with the supplied snapshots and message.
     *
     * @param oldDetails    {@code CCUP-OLD-DETAILS}
     * @param returnMessage {@code WS-RETURN-MSG}, or {@code null} for the cleared state
     * @return the outcome
     */
    private WriteResult write(CardDetails oldDetails, String returnMessage) {
        return service.writeProcessing(workArea(), oldDetails, typedNewDetails(), returnMessage,
                codec);
    }

    // =================================================================================================
    // Step 3, :1441-1449 - the lock arm, and the nesting inside it.
    // =================================================================================================

    @Nested
    @DisplayName("step 3, :1441-1449 - could we lock the record")
    class LockArm {

        @Test
        @DisplayName("a non-normal read with the message off sets the could-not-lock message")
        void nonNormalReadWithMessageOffSetsTheLockMessage() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.notFound());

            WriteResult result = write(matchingOldDetails(storedRecord()), null);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(result.returnMessage())
                    .as(":1446 moves the literal into WS-RETURN-MSG, which is PIC X(75)")
                    .isEqualTo(CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE
                            + " ".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH
                                    - CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE.length()));
            Assertions.assertThat(result.inputError())
                    .as(":1444 SET INPUT-ERROR TO TRUE is unconditional on this arm")
                    .isTrue();
            Assertions.assertThat(result.failedOperation())
                    .contains(CardUpdateService.READ_OPERATION_NAME);
            Assertions.assertThat(result.cardUpdateRecord()).isEmpty();
            Assertions.assertThat(result.cardUpdateCvvCdImage()).isEmpty();
            Assertions.assertThat(result.resp()).isEqualTo(FileStatus.NOTFND);
            verify(cardRepository, never()).rewrite(any());
        }

        @Test
        @DisplayName("a non-normal read with a message already set leaves that message, but still "
                + "reports the input error - the :1445 nesting")
        void nonNormalReadWithMessageAlreadySetLeavesIt() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.reportedFailure(FileStatus.INVREQ, 42));

            WriteResult result = write(matchingOldDetails(storedRecord()), EARLIER_MESSAGE);

            Assertions.assertThat(result.inputError())
                    .as(":1444 is outside the IF, so the error is set either way")
                    .isTrue();
            Assertions.assertThat(result.returnMessage().strip())
                    .as(":1445 suppresses :1446 when a message is already present, so the earlier and "
                            + "more specific message survives")
                    .isEqualTo(EARLIER_MESSAGE);
            Assertions.assertThat(result.returnMessage())
                    .doesNotContain(CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            Assertions.assertThat(result.resp2()).isEqualTo(42);
            verify(cardRepository, never()).rewrite(any());
        }

        @Test
        @DisplayName(":1425 keys the read on the card number alone, never on the account")
        void keysTheReadOnTheCardNumberAlone() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.notFound());

            write(matchingOldDetails(storedRecord()), null);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(cardRepository).readForUpdateByCardNumber(key.capture());
            Assertions.assertThat(key.getValue())
                    .isEqualTo(CARD_NUMBER)
                    .hasSize(CardUpdateService.CARD_KEY_LENGTH);
            verify(cardRepository, never()).readByAccountIdViaAltIndex(any(Long.class));
        }
    }

    // =================================================================================================
    // Steps 4 to 7 - the check, the staging and the rewrite.
    // =================================================================================================

    @Nested
    @DisplayName("steps 4 to 7 - check, stage and rewrite")
    class WriteArms {

        @BeforeEach
        void lockSucceeds() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(storedRecord()));
        }

        @Test
        @DisplayName("all six compared items equal, so the record is rewritten at full width")
        void allSixEqualRewrites() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(matchingOldDetails(storedRecord()), null);

            Assertions.assertThat(result.outcome()).isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            Assertions.assertThat(result.isRewritten()).isTrue();
            Assertions.assertThat(result.isDataWasChangedBeforeUpdate())
                    .as("9300 found nothing changed, so this is not the show-details arm")
                    .isFalse();
            Assertions.assertThat(result.inputError()).isFalse();
            Assertions.assertThat(result.failedOperation()).isEmpty();
            Assertions.assertThat(result.cardUpdateRecord()).isPresent();

            ArgumentCaptor<CardRecord> written = ArgumentCaptor.forClass(CardRecord.class);
            verify(cardRepository).rewrite(written.capture());
            Assertions.assertThat(written.getValue().encode(codec))
                    .as("app/cbl/COCRDUPC.cbl:1480 states LENGTH OF CARD-UPDATE-RECORD, and that "
                            + "record is a full 150 bytes")
                    .hasSize(CardUpdateService.CARD_UPDATE_RECORD_LENGTH);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "com.vsergeychik.carddemo.card.CardUpdateServiceTest#singleItemDifferences")
        @DisplayName("any one of the six items differing refuses the rewrite and refreshes the "
                + "snapshot")
        void oneDifferingItemRefusesTheRewrite(String label, CardDetails staleSnapshot,
                                              String expectedDifference) {
            WriteResult result = write(staleSnapshot, null);

            Assertions.assertThat(result.outcome())
                    .as(label)
                    .isEqualTo(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE);
            Assertions.assertThat(result.isDataWasChangedBeforeUpdate()).isTrue();
            Assertions.assertThat(result.inputError())
                    .as("9300 sets no INPUT-ERROR; only :1444 does, and that is the other arm")
                    .isFalse();
            Assertions.assertThat(result.returnMessage().strip())
                    .isEqualTo(CardUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
            Assertions.assertThat(result.cardUpdateRecord())
                    .as("the paragraph returns before :1461, so nothing is staged")
                    .isEmpty();
            verify(cardRepository, never()).rewrite(any());

            Assertions.assertThat(result.oldDetails())
                    .as(":1512-1517 copies the CURRENT values back so the screen shows what won")
                    .isEqualTo(CardUpdateServiceTest.this
                            .matchingOldDetails(CardUpdateServiceTest.this.storedRecord()));

            ChangeCheck check = service.checkChangeInRec(CardUpdateServiceTest.this.storedRecord(),
                    staleSnapshot, codec);
            Assertions.assertThat(check.describeDifferences())
                    .as("exactly one item differed, and it is the one named")
                    .isEqualTo(expectedDifference);
        }

        @Test
        @DisplayName("an embossed name differing only by letter case is NOT a concurrent change - the "
                + ":1499 INSPECT runs first")
        void caseOnlyNameDifferenceIsNotAChange() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(
                            CardUpdateServiceTest.this.storedRecord()
                                    .withCardEmbossedName("john q public")));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    null);

            Assertions.assertThat(result.outcome())
                    .as("INSPECT ... CONVERTING folded the stored name to upper case before the "
                            + "comparison, so the two agree")
                    .isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            verify(cardRepository).rewrite(any());
        }

        @Test
        @DisplayName("expiry separators differing at characters 5 and 8 are NOT a concurrent change - "
                + "the reference modifications skip them")
        void differingSeparatorsAreNotAChange() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(CardUpdateServiceTest.this.storedRecord()
                            .withCardExpiraionDate("2027/03/09")));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    null);

            Assertions.assertThat(result.outcome())
                    .as("(1:4), (6:2) and (9:2) select YYYY, MM and DD and never the separators, so "
                            + "'2027/03/09' compares equal to '2027-03-09'")
                    .isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
        }

        @Test
        @DisplayName("a failed rewrite reports LOCKED-BUT-UPDATE-FAILED, with no message-off guard")
        void failedRewriteReportsUpdateFailed() {
            when(cardRepository.rewrite(any()))
                    .thenReturn(CardWriteResult.failed(FileStatus.INVREQ));

            WriteResult result = write(
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    EARLIER_MESSAGE);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(result.returnMessage().strip())
                    .as(":1491 is not wrapped in IF WS-RETURN-MSG-OFF, unlike :1445, so it overwrites "
                            + "whatever was there")
                    .isEqualTo(CardUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(result.inputError())
                    .as(":1488-1492 sets no INPUT-ERROR")
                    .isFalse();
            Assertions.assertThat(result.failedOperation())
                    .contains(CardUpdateService.REWRITE_OPERATION_NAME);
            Assertions.assertThat(result.cardUpdateRecord())
                    .as("the record WAS staged; only the write failed")
                    .isPresent();
            Assertions.assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("a succeeded rewrite leaves WS-RETURN-MSG exactly as it was")
        void succeededRewriteLeavesTheMessageAlone() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    null);

            Assertions.assertThat(result.returnMessage())
                    .as("the WHEN OTHER arm sets no condition on WS-RETURN-MSG at all")
                    .isEqualTo(" ".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH));
            Assertions.assertThat(CardUpdateService.isReturnMessageOff(result.returnMessage()))
                    .isTrue();
        }

        @Test
        @DisplayName("the staged record carries the typed values, a full FILLER and the composed date")
        void theStagedRecordIsByteCorrect() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    null);
            CardUpdateRecord staged = result.cardUpdateRecord().orElseThrow();
            byte[] image = staged.encode(codec);

            Assertions.assertThat(image).hasSize(CardUpdateService.CARD_UPDATE_RECORD_LENGTH);
            Assertions.assertThat(new String(image, CardUpdateRecord.FILLER_OFFSET,
                            CardUpdateRecord.FILLER_LENGTH, StandardCharsets.US_ASCII))
                    .as("app/cbl/COCRDUPC.cbl:321 FILLER PIC X(59), space-filled by INITIALIZE "
                            + "(gate G21)")
                    .isEqualTo(" ".repeat(CardUpdateRecord.FILLER_LENGTH));
            Assertions.assertThat(new String(image,
                            CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_LENGTH,
                            StandardCharsets.US_ASCII))
                    .as(":1467-1474 STRING ... DELIMITED BY SIZE yields exactly 4+1+2+1+2 characters")
                    .isEqualTo("2029-11-30");
            Assertions.assertThat(staged.cardUpdateNum()).isEqualTo(CARD_NUMBER);
            Assertions.assertThat(staged.cardUpdateAcctId())
                    .as(":1463 takes CC-ACCT-ID-N, the work area's numeric view")
                    .isEqualTo(ACCOUNT_ID);
            Assertions.assertThat(staged.cardUpdateEmbossedName().strip())
                    .as(":1466 stores what the user typed; the :1499 fold applies to the READ record")
                    .isEqualTo("JANE R CITIZEN");
            Assertions.assertThat(staged.cardUpdateActiveStatus()).isEqualTo("N");
        }

        @Test
        @DisplayName("Finding 2: CCUP-NEW-CVV-CD is never assigned, so every update stages a blank CVV")
        void theNeverAssignedCvvIsStagedBlank() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    null);

            Assertions.assertThat(result.cardUpdateCvvCdImage())
                    .as("the verbatim three bytes INITIALIZE left at app/cbl/COCRDUPC.cbl:586")
                    .contains("   ");
            Assertions.assertThat(result.cardUpdateRecord().orElseThrow().cardUpdateCvvCd())
                    .as("a space has a low-order nibble of zero in US-ASCII and in IBM037 alike")
                    .isZero();
        }
    }

    /**
     * One stale {@code CCUP-OLD-DETAILS} snapshot per compared item, each differing from the stored
     * record in that item and only that item.
     *
     * @return label, snapshot and the field name the check must name
     */
    static Stream<Arguments> singleItemDifferences() {
        CardUpdateServiceTest fixture = new CardUpdateServiceTest();
        fixture.codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
        CardDetails matching = fixture.matchingOldDetails(fixture.storedRecord());
        return Stream.of(
                Arguments.of(":1503 CARD-CVV-CD differs", matching.withCvvCd("999"),
                        "CARD-CVV-CD"),
                Arguments.of(":1504 CARD-EMBOSSED-NAME differs",
                        matching.withCrdname("SOMEONE ELSE"), "CARD-EMBOSSED-NAME"),
                Arguments.of(":1505 CARD-EXPIRAION-DATE(1:4) differs", matching.withExpyear("2028"),
                        "CARD-EXPIRAION-DATE(1:4)"),
                Arguments.of(":1506 CARD-EXPIRAION-DATE(6:2) differs", matching.withExpmon("04"),
                        "CARD-EXPIRAION-DATE(6:2)"),
                Arguments.of(":1507 CARD-EXPIRAION-DATE(9:2) differs", matching.withExpday("10"),
                        "CARD-EXPIRAION-DATE(9:2)"),
                Arguments.of(":1508 CARD-ACTIVE-STATUS differs", matching.withCrdstcd("N"),
                        "CARD-ACTIVE-STATUS"));
    }

    // =================================================================================================
    // 9300-CHECK-CHANGE-IN-REC in isolation.
    // =================================================================================================

    @Nested
    @DisplayName("9300-CHECK-CHANGE-IN-REC, :1498-1523")
    class CheckChangeInRec {

        @Test
        @DisplayName("all six matching reports no change and hands the snapshot back untouched")
        void allSixMatching() {
            CardDetails snapshot = matchingOldDetails(storedRecord());

            ChangeCheck check = service.checkChangeInRec(storedRecord(), snapshot, codec);

            Assertions.assertThat(check.dataWasChanged()).isFalse();
            Assertions.assertThat(check.oldDetails()).isSameAs(snapshot);
            Assertions.assertThat(check.cvvDiffers()).isFalse();
            Assertions.assertThat(check.embossedNameDiffers()).isFalse();
            Assertions.assertThat(check.expiryYearDiffers()).isFalse();
            Assertions.assertThat(check.expiryMonthDiffers()).isFalse();
            Assertions.assertThat(check.expiryDayDiffers()).isFalse();
            Assertions.assertThat(check.activeStatusDiffers()).isFalse();
            Assertions.assertThat(check.describeDifferences())
                    .isEqualTo("none - all six compared items matched");
        }

        @Test
        @DisplayName("the folded record is what was compared, and it is the folded name that is "
                + "written back")
        void theFoldedNameIsWhatIsWrittenBack() {
            CardRecord stored = storedRecord().withCardEmbossedName("mixed Case Name");
            CardDetails snapshot = matchingOldDetails(storedRecord());

            ChangeCheck check = service.checkChangeInRec(stored, snapshot, codec);

            Assertions.assertThat(check.dataWasChanged()).isTrue();
            Assertions.assertThat(check.foldedRecord().cardEmbossedName().strip())
                    .isEqualTo("MIXED CASE NAME");
            Assertions.assertThat(check.oldDetails().crdname().strip())
                    .as(":1513 writes back the value the INSPECT already folded")
                    .isEqualTo("MIXED CASE NAME");
        }

        @Test
        @DisplayName("several items differing are all reported")
        void severalItemsDiffering() {
            CardDetails snapshot = matchingOldDetails(storedRecord())
                    .withCvvCd("111")
                    .withCrdstcd("N");

            ChangeCheck check = service.checkChangeInRec(storedRecord(), snapshot, codec);

            Assertions.assertThat(check.describeDifferences())
                    .isEqualTo("CARD-CVV-CD, CARD-ACTIVE-STATUS");
        }

        @Test
        @DisplayName("the card number and the account identifier are deliberately not compared")
        void keysAreNotCompared() {
            CardDetails snapshot = matchingOldDetails(storedRecord())
                    .withCardid("9999999999999999")
                    .withAcctid("99999999999");

            ChangeCheck check = service.checkChangeInRec(storedRecord(), snapshot, codec);

            Assertions.assertThat(check.dataWasChanged())
                    .as("app/cbl/COCRDUPC.cbl:1503-1509 compares six items and neither key is one "
                            + "of them")
                    .isFalse();
        }

        @Test
        @DisplayName("a NEW snapshot is refused, because the paragraph compares against OLD")
        void refusesTheNewGroup() {
            CardDetails wrongGroup = CardDetails.initialised(DetailGroup.NEW);

            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.checkChangeInRec(storedRecord(), wrongGroup, codec))
                    .withMessageContaining("CCUP-OLD-DETAILS");
        }

        @Test
        @DisplayName("null arguments are refused explicitly")
        void refusesNulls() {
            CardDetails snapshot = matchingOldDetails(storedRecord());
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> service.checkChangeInRec(null, snapshot, codec));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> service.checkChangeInRec(storedRecord(), snapshot, null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> service.checkChangeInRec(storedRecord(), null, codec));
        }
    }

    // =================================================================================================
    // The primitives.
    // =================================================================================================

    @Nested
    @DisplayName("INSPECT ... CONVERTING LIT-LOWER TO LIT-UPPER, :1499-1501")
    class Converting {

        @Test
        @DisplayName("converts all twenty-six pairs and nothing else")
        void convertsAllTwentySixPairs() {
            Assertions.assertThat(CardUpdateService.convertingLowerToUpper(
                            CardUpdateService.LIT_LOWER))
                    .isEqualTo(CardUpdateService.LIT_UPPER);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "abc|ABC",
            "AbC|ABC",
            "A1b-2c |A1B-2C ",
            "123 456|123 456",
            "|"
        })
        @DisplayName("leaves every other character exactly as it is, and never changes the width")
        void leavesEverythingElseAlone(String input, String expected) {
            String source = input == null ? "" : input;
            String want = expected == null ? "" : expected;
            String converted = CardUpdateService.convertingLowerToUpper(source);
            Assertions.assertThat(converted).isEqualTo(want);
            Assertions.assertThat(converted).hasSameSizeAs(source);
        }

        @Test
        @DisplayName("a character outside the twenty-six is not folded, so the width cannot grow")
        void doesNotFoldOutsideTheTwentySix() {
            Assertions.assertThat(CardUpdateService.convertingLowerToUpper("\u00df\u00e9"))
                    .as("the JDK's own upper-casing would turn the first into two characters and the "
                            + "second into an accented capital; CONVERTING touches neither")
                    .isEqualTo("\u00df\u00e9");
        }

        @Test
        @DisplayName("a value with nothing to fold comes back as the very same record")
        void foldIsIdentityWhenThereIsNothingToFold() {
            CardRecord record = storedRecord();
            Assertions.assertThat(CardUpdateService.foldEmbossedName(record)).isSameAs(record);
        }

        @Test
        @DisplayName("a value with something to fold comes back changed, and only in that field")
        void foldChangesOnlyTheName() {
            CardRecord record = storedRecord().withCardEmbossedName("lower case");
            CardRecord folded = CardUpdateService.foldEmbossedName(record);

            Assertions.assertThat(folded).isNotSameAs(record);
            Assertions.assertThat(folded.cardEmbossedName().strip()).isEqualTo("LOWER CASE");
            Assertions.assertThat(folded.cardCvvCd()).isEqualTo(record.cardCvvCd());
            Assertions.assertThat(folded.cardExpiraionDate()).isEqualTo(record.cardExpiraionDate());
            Assertions.assertThat(folded.cardActiveStatus()).isEqualTo(record.cardActiveStatus());
        }

        @Test
        @DisplayName("null is refused")
        void refusesNull() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.convertingLowerToUpper(null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.foldEmbossedName(null));
        }
    }

    @Nested
    @DisplayName("88 WS-RETURN-MSG-OFF VALUE SPACES, :174")
    class ReturnMessageOff {

        @Test
        @DisplayName("null is the cleared state :384 establishes")
        void nullIsCleared() {
            Assertions.assertThat(CardUpdateService.isReturnMessageOff(null)).isTrue();
        }

        @Test
        @DisplayName("seventy-five spaces is off, and so is a shorter run of spaces")
        void spacesAreOff() {
            Assertions.assertThat(CardUpdateService.isReturnMessageOff(
                    CardUpdateService.RETURN_MESSAGE_OFF)).isTrue();
            Assertions.assertThat(CardUpdateService.isReturnMessageOff("")).isTrue();
            Assertions.assertThat(CardUpdateService.isReturnMessageOff("   ")).isTrue();
        }

        @Test
        @DisplayName("binary zeros are NOT off - this condition is SPACES, not LOW-VALUES")
        void lowValuesAreNotOff() {
            Assertions.assertThat(CardUpdateService.isReturnMessageOff("\u0000".repeat(
                            CardUpdateService.RETURN_MESSAGE_LENGTH)))
                    .as("app/cbl/COCRDUPC.cbl:174 declares VALUE SPACES; the LOW-VALUES condition at "
                            + "app/cpy/CVCRD01Y.cpy:30 is on a different field COCRDUPC never touches")
                    .isFalse();
        }

        @Test
        @DisplayName("any message is not off, wherever the first non-space sits")
        void aMessageIsNotOff() {
            Assertions.assertThat(CardUpdateService.isReturnMessageOff(
                    CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE)).isFalse();
            Assertions.assertThat(CardUpdateService.isReturnMessageOff("  x")).isFalse();
        }

        @Test
        @DisplayName("characters past the declared width are outside the field and cannot make it "
                + "non-blank")
        void charactersPastTheWidthAreOutsideTheField() {
            Assertions.assertThat(CardUpdateService.isReturnMessageOff(
                    " ".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH) + "x")).isTrue();
        }
    }

    @Nested
    @DisplayName("the CVV REDEFINES round-trip, :1464-1465")
    class CvvRoundTrip {

        @Test
        @DisplayName("three digits survive the round trip unchanged")
        void digitsRoundTrip() {
            String image = CardUpdateService.redefinedCvvImage("747", codec);
            Assertions.assertThat(image).isEqualTo("747");
            Assertions.assertThat(CardUpdateService.zonedDigitsValue(image, codec)).isEqualTo(747);
        }

        @Test
        @DisplayName("a short or over-wide value is moved by the PIC X rule, truncating on the RIGHT")
        void appliesThePicXMoveRule() {
            Assertions.assertThat(CardUpdateService.redefinedCvvImage("7", codec)).isEqualTo("7  ");
            Assertions.assertThat(CardUpdateService.redefinedCvvImage("74712", codec))
                    .as("a PIC X receiver fills from the left and discards the overflow")
                    .isEqualTo("747");
        }

        @ParameterizedTest
        @CsvSource({
            "'   ', 0",
            "'000', 0",
            "'009', 9",
            "'999', 999",
            "'12A', 121",
            "'1Z3', 103"
        })
        @DisplayName("a non-numeric span decodes by its low-order nibbles and never throws")
        void nonNumericNeverThrows(String image, int expected) {
            Assertions.assertThat(CardUpdateService.zonedDigitsValue(image, codec))
                    .as("US-ASCII 'A' is 0x41 so its nibble is 1; 'Z' is 0x5A so its nibble is 0xA, "
                            + "which denotes no digit and contributes zero")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the round trip is total: no input to it raises a number-format failure")
        void theRoundTripIsTotal() {
            Assertions.assertThatCode(() -> CardUpdateService.zonedDigitsValue(
                            CardUpdateService.redefinedCvvImage("?!*", codec), codec))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null is refused on both halves")
        void refusesNulls() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.redefinedCvvImage(null, codec));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.redefinedCvvImage("747", null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.zonedDigitsValue(null, codec));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.zonedDigitsValue("747", null));
        }
    }

    @Nested
    @DisplayName("staging, :1461-1475")
    class Staging {

        @Test
        @DisplayName("an OLD snapshot is refused, because the paragraph stages from NEW")
        void refusesTheOldGroup() {
            CardDetails wrongGroup = CardDetails.initialised(DetailGroup.OLD);

            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.stageUpdateRecord(wrongGroup, ACCOUNT_ID, codec))
                    .withMessageContaining("CCUP-NEW-DETAILS");
        }

        @Test
        @DisplayName("null arguments are refused")
        void refusesNulls() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> service.stageUpdateRecord(typedNewDetails(), ACCOUNT_ID, null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> service.stageUpdateRecord(null, ACCOUNT_ID, codec));
        }

        @Test
        @DisplayName("asCardRecord is a change of view over identical bytes, not a conversion")
        void asCardRecordPreservesEveryByte() {
            CardUpdateRecord staged = service.stageUpdateRecord(typedNewDetails(), ACCOUNT_ID, codec);

            CardRecord asRecord = CardUpdateService.asCardRecord(staged);

            Assertions.assertThat(asRecord.encode(codec))
                    .as("app/cbl/COCRDUPC.cbl:314-321 and app/cpy/CVACT02Y.cpy:4-11 declare the same "
                            + "seven spans at the same seven offsets")
                    .isEqualTo(staged.encode(codec));
            Assertions.assertThat(CardUpdateService.asCardRecord(staged)).isEqualTo(asRecord);
        }

        @Test
        @DisplayName("asCardRecord refuses null, because there is no partial rewrite")
        void asCardRecordRefusesNull() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.asCardRecord(null));
        }
    }

    // =================================================================================================
    // The outcome contract, app/cbl/COCRDUPC.cbl:992-1001.
    // =================================================================================================

    @Nested
    @DisplayName("the outcome contract, :992-1001")
    class OutcomeContract {

        @Test
        @DisplayName("gate G30: the constants are declared in the caller's EVALUATE order, with "
                + "WHEN OTHER last")
        void firstMatchWinsOrderIsTheSourcesOrder() {
            Assertions.assertThat(WriteOutcome.values())
                    .containsExactly(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE,
                            WriteOutcome.LOCKED_BUT_UPDATE_FAILED,
                            WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE,
                            WriteOutcome.CHANGES_OKAYED_AND_DONE);
            Assertions.assertThat(WriteOutcome.CHANGES_OKAYED_AND_DONE.cobolCondition())
                    .isEqualTo("WHEN OTHER");
            Assertions.assertThat(WriteOutcome.CHANGES_OKAYED_AND_DONE.firstMatchWinsPosition())
                    .isEqualTo(WriteOutcome.values().length);
        }

        @ParameterizedTest
        @EnumSource(WriteOutcome.class)
        @DisplayName("every outcome carries a one-character CCUP-CHANGE-ACTION that the commarea "
                + "recognises, and states its own position")
        void everyOutcomeIsSelfDescribing(WriteOutcome outcome) {
            Assertions.assertThat(outcome.changeActionCode()).hasSize(1);
            Assertions.assertThat(outcome.changeAction().isRecognised()).isTrue();
            Assertions.assertThat(outcome.changeAction().value())
                    .isEqualTo(outcome.changeActionCode());
            Assertions.assertThat(outcome.cobolCondition()).isNotBlank();
            Assertions.assertThat(outcome.firstMatchWinsPosition())
                    .isBetween(1, WriteOutcome.values().length);
        }

        @Test
        @DisplayName("the three specific arms name their WS-RETURN-MSG literal; WHEN OTHER names none")
        void messageLiteralsMatchTheEightyEightLevels() {
            Assertions.assertThat(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE.returnMessageLiteral())
                    .contains(CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(WriteOutcome.LOCKED_BUT_UPDATE_FAILED.returnMessageLiteral())
                    .contains(CardUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE.returnMessageLiteral())
                    .contains(CardUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
            Assertions.assertThat(WriteOutcome.CHANGES_OKAYED_AND_DONE.returnMessageLiteral())
                    .isEmpty();
        }

        @Test
        @DisplayName("only the succeeded arm counts as rewritten")
        void onlyTheSucceededArmIsRewritten() {
            Assertions.assertThat(WriteOutcome.CHANGES_OKAYED_AND_DONE.isRewritten()).isTrue();
            Assertions.assertThat(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE.isRewritten()).isFalse();
            Assertions.assertThat(WriteOutcome.LOCKED_BUT_UPDATE_FAILED.isRewritten()).isFalse();
            Assertions.assertThat(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE.isRewritten())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the result carriers refuse states the source cannot represent")
    class ResultInvariants {

        @Test
        @DisplayName("ChangeCheck refuses flags that contradict its verdict")
        void changeCheckRefusesContradictions() {
            CardDetails snapshot = matchingOldDetails(storedRecord());
            CardRecord record = storedRecord();

            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ChangeCheck(true, snapshot, record, false, false, false,
                            false, false, false))
                    .withMessageContaining("1503-1509");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ChangeCheck(false, snapshot, record, true, false, false,
                            false, false, false));
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ChangeCheck(false,
                            CardDetails.initialised(DetailGroup.NEW), record, false, false, false,
                            false, false, false));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new ChangeCheck(false, null, record, false, false, false,
                            false, false, false));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new ChangeCheck(false, snapshot, null, false, false, false,
                            false, false, false));
        }

        @Test
        @DisplayName("WriteResult ties inputError to the lock arm alone, and the staged record to its "
                + "CVV image")
        void writeResultRefusesContradictions() {
            CardDetails snapshot = matchingOldDetails(storedRecord());
            CardUpdateRecord staged = service.stageUpdateRecord(typedNewDetails(), ACCOUNT_ID, codec);

            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, true,
                            null, snapshot, java.util.Optional.of(staged),
                            java.util.Optional.of("   "), java.util.Optional.empty(), 0, 0))
                    .withMessageContaining("1444");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE, false,
                            null, snapshot, java.util.Optional.empty(), java.util.Optional.empty(),
                            java.util.Optional.empty(), FileStatus.NOTFND, 0));
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, java.util.Optional.of(staged),
                            java.util.Optional.empty(), java.util.Optional.empty(), 0, 0))
                    .withMessageContaining("1461-1475");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, CardDetails.initialised(DetailGroup.NEW),
                            java.util.Optional.empty(), java.util.Optional.empty(),
                            java.util.Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(null, false, null, snapshot,
                            java.util.Optional.empty(), java.util.Optional.empty(),
                            java.util.Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, null, java.util.Optional.empty(), java.util.Optional.empty(),
                            java.util.Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, null, java.util.Optional.empty(),
                            java.util.Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, java.util.Optional.empty(), null,
                            java.util.Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, java.util.Optional.empty(), java.util.Optional.empty(),
                            null, 0, 0));
        }

        @Test
        @DisplayName("WriteResult normalises WS-RETURN-MSG to its declared PIC X(75) width")
        void writeResultNormalisesTheMessageWidth() {
            CardDetails snapshot = matchingOldDetails(storedRecord());

            WriteResult padded = new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false, "hi",
                    snapshot, java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty(), 0, 0);
            WriteResult exact = new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                    "x".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH), snapshot,
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty(), 0, 0);
            WriteResult overWide = new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                    "y".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH + 10), snapshot,
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty(), 0, 0);

            Assertions.assertThat(padded.returnMessage())
                    .hasSize(CardUpdateService.RETURN_MESSAGE_LENGTH)
                    .startsWith("hi ");
            Assertions.assertThat(exact.returnMessage())
                    .hasSize(CardUpdateService.RETURN_MESSAGE_LENGTH);
            Assertions.assertThat(overWide.returnMessage())
                    .as("a PIC X receiver keeps the leading characters")
                    .hasSize(CardUpdateService.RETURN_MESSAGE_LENGTH);
            Assertions.assertThat(padded.changeAction().isChangesOkayedAndDone()).isTrue();
        }
    }

    @Nested
    @DisplayName("construction and argument checking")
    class Construction {

        @Test
        @DisplayName("the repository and the unit of work are both required")
        void theRepositoryIsRequired() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new CardUpdateService(null, unitOfWork))
                    .withMessageContaining("CARDDAT");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new CardUpdateService(mock(CardRepository.class), null))
                    .withMessageContaining("unit of work");
            Assertions.assertThat(new CardUpdateService(mock(CardRepository.class), unitOfWork))
                    .isNotNull();
        }

        @Test
        @DisplayName("the lock, the comparison and the rewrite all happen inside one unit of work")
        void theWholeParagraphRunsInsideOneUnitOfWork() {
            CardRecord stored = storedRecord();
            List<Boolean> insideAUnitOfWork = new ArrayList<>();
            List<Integer> completion = new ArrayList<>();
            when(cardRepository.readForUpdateByCardNumber(anyString())).thenAnswer(invocation -> {
                insideAUnitOfWork.add(DatasetUnitOfWork.active());
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                completion.add(status);
                            }
                        });
                return CardReadResult.normal(stored);
            });
            when(cardRepository.rewrite(any(CardRecord.class))).thenAnswer(invocation -> {
                insideAUnitOfWork.add(DatasetUnitOfWork.active());
                return CardWriteResult.normal();
            });

            WriteResult result = service.writeProcessing(workArea(),
                    matchingOldDetails(stored), typedNewDetails(), null, codec);

            Assertions.assertThat(result.outcome()).isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            // Both statements ran inside the boundary. Without it CardRepository refuses the read
            // outright, and a lenient repository would release the lock before the rewrite - leaving
            // 9300-CHECK-CHANGE-IN-REC passing while protecting nothing.
            Assertions.assertThat(insideAUnitOfWork).containsExactly(true, true);
            // COCRDUPC issues no SYNCPOINT ROLLBACK anywhere, so the boundary commits.
            Assertions.assertThat(completion)
                    .containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            Assertions.assertThat(DatasetUnitOfWork.active())
                    .as("the boundary closes when the paragraph returns, as a task does at RETURN")
                    .isFalse();
        }

        @Test
        @DisplayName("a failed rewrite still commits: :1488-1492 has nothing to back out")
        void aFailedRewriteStillCommits() {
            CardRecord stored = storedRecord();
            List<Integer> completion = new ArrayList<>();
            when(cardRepository.readForUpdateByCardNumber(anyString())).thenAnswer(invocation -> {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                completion.add(status);
                            }
                        });
                return CardReadResult.normal(stored);
            });
            when(cardRepository.rewrite(any(CardRecord.class)))
                    .thenReturn(CardWriteResult.reportedFailure(FileStatus.INVREQ, 42));

            WriteResult result = service.writeProcessing(workArea(),
                    matchingOldDetails(stored), typedNewDetails(), null, codec);

            Assertions.assertThat(result.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(completion)
                    .containsExactly(TransactionSynchronization.STATUS_COMMITTED);
        }

        @Test
        @DisplayName("writeProcessing refuses nulls and mismatched snapshot groups")
        void writeProcessingChecksItsArguments() {
            CardDetails oldDetails = matchingOldDetails(storedRecord());
            CardDetails newDetails = typedNewDetails();
            CardScreenState state = workArea();

            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    service.writeProcessing(null, oldDetails, newDetails, null, codec));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    service.writeProcessing(state, oldDetails, newDetails, null, null));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    service.writeProcessing(state, null, newDetails, null, codec));
            Assertions.assertThatNullPointerException().isThrownBy(() ->
                    service.writeProcessing(state, oldDetails, null, null, codec));
            Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                    service.writeProcessing(state, newDetails.asGroup(DetailGroup.OLD), oldDetails
                            .asGroup(DetailGroup.OLD), null, codec))
                    .withMessageContaining("CCUP-NEW-DETAILS");
            verify(cardRepository, never()).readForUpdateByCardNumber(anyString());
            // Each argument was refused before any boundary opened: an argument defect is the caller's,
            // and opening a transaction to reject one would take a connection to accomplish nothing.
            Assertions.assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("the declared geometry matches the copybook and the program")
        void theDeclaredGeometryIsTheSources() {
            Assertions.assertThat(CardUpdateService.CARD_UPDATE_RECORD_LENGTH).isEqualTo(150);
            Assertions.assertThat(CardUpdateService.CARD_KEY_LENGTH).isEqualTo(16);
            Assertions.assertThat(CardUpdateService.RETURN_MESSAGE_LENGTH).isEqualTo(75);
            Assertions.assertThat(CardUpdateService.RETURN_MESSAGE_OFF)
                    .hasSize(CardUpdateService.RETURN_MESSAGE_LENGTH)
                    .isBlank();
            Assertions.assertThat(CardUpdateService.CICS_FILE_NAME)
                    .as("LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT ' - the trailing space is the value")
                    .isEqualTo("CARDDAT ")
                    .hasSize(8);
            Assertions.assertThat(CardUpdateService.LIT_LOWER).hasSize(26);
            Assertions.assertThat(CardUpdateService.LIT_UPPER)
                    .hasSameSizeAs(CardUpdateService.LIT_LOWER);
            Assertions.assertThat(CardUpdateService.READ_OPERATION_NAME).isEqualTo("READ");
            Assertions.assertThat(CardUpdateService.REWRITE_OPERATION_NAME).isEqualTo("REWRITE");
        }
    }
}
