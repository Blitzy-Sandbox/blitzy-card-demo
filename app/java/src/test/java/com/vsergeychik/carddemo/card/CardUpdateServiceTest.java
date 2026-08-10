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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CardUpdateService} against {@code app/cbl/COCRDUPC.cbl} - the two paragraphs that carry
 * {@code COCRDUPC}'s write path, and with them its optimistic-concurrency control:
 * {@code 9200-WRITE-PROCESSING} at {@code :1420-1494} and {@code 9300-CHECK-CHANGE-IN-REC} at
 * {@code :1498-1521}.
 *
 * <p>The COBOL is the only oracle. This is a like-for-like migration, so where the program does
 * something odd the assertion below asserts the odd thing rather than the reasonable thing: a
 * case-only name difference is not a concurrent change, a separator-only date difference is not a
 * concurrent change, the CVV comparison is on character images and not on numbers, and every
 * successful update stages a blank CVV.
 *
 * <h2>No user rules govern this file</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user-specified rule applies here and none has been
 * invented</strong>. Their absence is not licence to lower the bar: the binding standard is
 * enterprise best practice as the migration plan codifies it in its practices B1-B12, and in
 * particular B1/B2 (only the closed test stack this module's {@code pom.xml} already declares -
 * JUnit Jupiter, Mockito, AssertJ - with no new dependency and no version literal), B3 (the COBOL and
 * the copybooks are cited as the contract and never read at runtime), B5 (behaviour is preserved
 * including its defects), B6 (the security posture is changed in neither direction - the CVV and the
 * card number are compared and asserted in the clear, exactly as the program does, and nothing is
 * masked in a failure message either), B7 (deterministic under
 * {@code mvn -f app/java/pom.xml -B clean verify}), B8 (explicit code page, no wildcard imports), B9
 * (no static mutable state, collaborators constructor-injected) and B11 (explicit byte and field
 * assertions, never reflective deep equality).
 *
 * <h2>The gates this class is answerable for</h2>
 * <ul>
 *   <li><strong>G43</strong> - the optimistic concurrency reproduces {@code 9300-CHECK-CHANGE-IN-REC}
 *       field for field: six comparisons, in the source's order, with <strong>no version column</strong>
 *       anywhere. {@link ConcurrencyMechanism} states that negatively and provably;</li>
 *   <li><strong>G44</strong> - no schema artefact of any kind participates: no DDL, no entity
 *       annotation, no generated key, no timestamp column;</li>
 *   <li><strong>G51</strong> - the business logic is asserted <em>at the service</em>. There is no
 *       servlet-mocking harness here, no Spring Boot test-context annotation, no web-layer slice
 *       annotation, no HTTP transport and no batch job launcher anywhere in the path of any test,
 *       which is what lets an audit for those tokens come back empty;</li>
 *   <li><strong>G50</strong> - both states of every {@code 88}-level the paragraphs touch:
 *       {@code WS-RETURN-MSG-OFF} on and off, the change-detected condition set and clear, and each of
 *       the three outcome conditions against its absence;</li>
 *   <li><strong>G19/G21</strong> - the rewrite image is exactly {@value CardUpdateRecord#RECORD_LENGTH}
 *       bytes with the {@code FILLER PIC X(59)} span space-filled, asserted span by span at its
 *       declared offset;</li>
 *   <li><strong>G47</strong> - each {@code FileStatus} outcome is driven at each repository call site,
 *       the read-for-update and the rewrite alike;</li>
 *   <li><strong>G49</strong> - branch coverage at or above 0.90 for {@code com.vsergeychik.carddemo.card};
 *       this module's JaCoCo configuration applies that ratio per package as well as per bundle, so the
 *       package cannot hide behind another's coverage;</li>
 *   <li><strong>G52</strong> - every import is explicit; <strong>G53</strong> - no static mutable
 *       state; <strong>G54</strong> - the suite runs non-interactively with no watch mode.</li>
 * </ul>
 *
 * <h2>How the subject is built</h2>
 * <p>{@code new CardUpdateService(mock(CardRepository.class), unitOfWork)} and nothing else. The
 * repository is a Mockito stub; the unit of work is a real {@link DatasetUnitOfWork} over a real
 * transaction manager, because the lock surviving from the read to the rewrite is the whole point of
 * the paragraph and a stand-in that merely ran the body would let the arrangement pass while proving
 * nothing. Neither collaborator brings a Spring application context with it.
 *
 * <h2>Declared widths, so a reviewer need not open the copybook</h2>
 * <p>{@code CARD-UPDATE-RECORD} ({@code app/cbl/COCRDUPC.cbl:314-321}) is
 * {@code X(16) + 9(11) + 9(03) + X(50) + X(10) + X(01) + FILLER X(59)}, and
 * {@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150} - byte for byte the {@code CARD-RECORD} of
 * {@code app/cpy/CVACT02Y.cpy}, which is why the rewrite is always full width and never a 91-byte
 * partial one. {@code CCUP-OLD-DETAILS} and {@code CCUP-NEW-DETAILS} ({@code :290-313}) are
 * {@code X(11) + X(16) + X(3) + X(50) + X(4) + X(2) + X(2) + X(1)}, and
 * {@code 11 + 16 + 3 + 50 + 4 + 2 + 2 + 1 = 89} each, so the whole
 * {@code WS-THIS-PROGCOMMAREA} is {@code 1 + 89 + 89 + 150 = 329}.
 *
 * <h2>The code page is named, never defaulted</h2>
 * <p>{@code US-ASCII} throughout, and that is a choice rather than a default: the one place the code
 * page can change an answer is the zoned CVV decode, and the space character that the running program
 * actually feeds it has a low-order nibble of zero in {@code US-ASCII} and in {@code IBM037} alike, so
 * the assertions below hold under either. The one case where the two code pages genuinely diverge - a
 * letter whose low nibble exceeds nine - is called out where it is exercised.
 *
 * @see CardUpdateService#writeProcessing(CardScreenState, CardDetails, CardDetails, String,
 *      FixedWidthCodec)
 * @see CardUpdateService#checkChangeInRec(CardRecord, CardDetails, FixedWidthCodec)
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
     * <p>Deliberately not a stand-in. The whole point of {@code 9200-WRITE-PROCESSING} is that the lock
     * the read takes survives to the rewrite, and only a real transaction can be observed doing that; a
     * stand-in that simply ran the body would let the arrangement pass while proving nothing. An
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

    /**
     * One declared span of a serialized record image, read back as characters.
     *
     * <p>Positional rather than field-wise on purpose: a field accessor would prove the value round
     * trips through the same code that wrote it, whereas reading the bytes at an absolute offset proves
     * the value is <em>where the copybook says it is</em>. Every offset supplied by the callers below is
     * a named constant of the layout, never a bare number, so the assertion and the declaration cannot
     * drift apart.
     *
     * <p>The code page is stated explicitly (practice B8); the caller's codec carries the same one.
     *
     * @param image  the serialized record
     * @param offset the span's absolute 0-based offset
     * @param length the span's declared width
     * @return exactly {@code length} characters
     */
    private String spanOf(byte[] image, int offset, int length) {
        return new String(image, offset, length, StandardCharsets.US_ASCII);
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

        @ParameterizedTest(name = "RESP {0}")
        @ValueSource(ints = {FileStatus.INVREQ, FileStatus.NOTOPEN, FileStatus.LENGERR})
        @DisplayName("gate G47: every WHEN OTHER response at this call site lands on the same arm - "
                + ":1441 tests only for NORMAL")
        void everyOtherResponseLandsOnTheSameArm(int resp) {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.failed(resp));

            WriteResult result = write(matchingOldDetails(storedRecord()), null);

            Assertions.assertThat(result.outcome())
                    .as("app/cbl/COCRDUPC.cbl:1441 compares WS-RESP-CD against DFHRESP(NORMAL) and "
                            + "nothing else, so every other response reaches the ELSE together")
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(result.resp()).isEqualTo(resp);
            Assertions.assertThat(result.inputError()).isTrue();
            verify(cardRepository, never()).rewrite(any());
        }

        @Test
        @DisplayName("gate G47: end-of-file at the read is not normal either, so the update is "
                + "abandoned")
        void endOfFileAbandonsTheUpdate() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.endOfFile());

            WriteResult result = write(matchingOldDetails(storedRecord()), null);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(result.resp()).isEqualTo(FileStatus.ENDFILE);
            verify(cardRepository, never()).rewrite(any());
        }

        @Test
        @DisplayName("gate G47: a duplicate-key read returns a record, yet :1441 still refuses it - "
                + "the record's presence is not the test")
        void aDuplicateKeyReadIsStillNotNormal() {
            // The subtlety worth a test of its own: this arm hands back a CARD-RECORD, so an
            // implementation tempted to branch on "did we get a record?" rather than on "was the
            // response NORMAL?" would sail past the guard, compare against a record CICS never
            // committed to, and rewrite it. app/cbl/COCRDUPC.cbl:1441 tests the response.
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardReadDuplicate(storedRecord()));

            WriteResult result = write(matchingOldDetails(storedRecord()), null);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(result.resp()).isEqualTo(FileStatus.DUPKEY);
            Assertions.assertThat(result.returnMessage().strip())
                    .isEqualTo(CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(result.cardUpdateRecord())
                    .as("nothing was staged, because control left at :1448")
                    .isEmpty();
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
                    .thenReturn(cardRead(storedRecord()));
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
            Assertions.assertThat(result.resp())
                    .as("gate G47: the NORMAL outcome at both call sites - :1441 and :1488 each took "
                            + "the CONTINUE arm, and the response the result carries is the rewrite's")
                    .isEqualTo(FileStatus.NORMAL);
            Assertions.assertThat(result.resp2())
                    .as("no reason code accompanies a normal response")
                    .isEqualTo(FileStatus.NO_REASON_CODE);

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
            CardRecord lowerCased = CardUpdateServiceTest.this.storedRecord()
                    .withCardEmbossedName("john q public");
            CardDetails upperCasedSnapshot = CardUpdateServiceTest.this.matchingOldDetails(
                    CardUpdateServiceTest.this.storedRecord());
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(lowerCased));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(upperCasedSnapshot, null);

            Assertions.assertThat(result.outcome())
                    .as("INSPECT ... CONVERTING folded the stored name to upper case before the "
                            + "comparison, so the two agree")
                    .isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            verify(cardRepository).rewrite(any());

            // :1499-1501 acts on the record area in place, so the record the comparison and the
            // write-back both see carries the FOLDED name - the conversion is not a temporary computed
            // for the comparison and thrown away. CardRecord is immutable, so "in place" surfaces as
            // the folded copy the check reports, while the instance the test handed in is untouched.
            ChangeCheck check = service.checkChangeInRec(lowerCased, upperCasedSnapshot, codec);
            Assertions.assertThat(check.foldedRecord().cardEmbossedName())
                    .as("the record the comparison examined carries the upper-cased name")
                    .isEqualTo(upperCasedSnapshot.crdname())
                    .startsWith("JOHN Q PUBLIC");
            Assertions.assertThat(lowerCased.cardEmbossedName())
                    .as("and the caller's own instance is not mutated underneath it")
                    .startsWith("john q public");
        }

        @Test
        @DisplayName("expiry separators differing at characters 5 and 8 are NOT a concurrent change - "
                + "the reference modifications skip them")
        void differingSeparatorsAreNotAChange() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(CardUpdateServiceTest.this.storedRecord()
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

        @ParameterizedTest(name = "RESP {0}")
        @ValueSource(ints = {FileStatus.INVREQ, FileStatus.NOTOPEN, FileStatus.LENGERR})
        @DisplayName("gate G47: every non-normal rewrite response lands on LOCKED-BUT-UPDATE-FAILED - "
                + ":1488 tests only for NORMAL")
        void everyNonNormalRewriteResponseLandsOnTheSameArm(int resp) {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.failed(resp));

            WriteResult result = write(
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    null);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(result.resp()).isEqualTo(resp);
            Assertions.assertThat(result.returnMessage().strip())
                    .isEqualTo(CardUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(result.failedOperation())
                    .contains(CardUpdateService.REWRITE_OPERATION_NAME);
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
        @DisplayName("gates G19 and G21: every span of the rewrite image sits at its declared offset, "
                + "at its declared width, in the 150 bytes app/cbl/COCRDUPC.cbl:1480 states")
        void everySpanOfTheRewriteImageSitsAtItsDeclaredOffset() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            write(CardUpdateServiceTest.this.matchingOldDetails(
                    CardUpdateServiceTest.this.storedRecord()), null);

            // The bytes the repository was actually handed, not the bytes the service says it staged:
            // the rewrite at :1477-1483 is what reaches the dataset, so it is what must be asserted.
            ArgumentCaptor<CardRecord> written = ArgumentCaptor.forClass(CardRecord.class);
            verify(cardRepository).rewrite(written.capture());
            byte[] image = written.getValue().encode(codec);

            Assertions.assertThat(image)
                    .as("LENGTH OF CARD-UPDATE-RECORD, so 16+11+3+50+10+1+59 = 150 (gate G19)")
                    .hasSize(CardUpdateRecord.RECORD_LENGTH);

            // :1462 MOVE CCUP-NEW-CARDID TO CARD-UPDATE-NUM - PIC X(16) at offset 0.
            Assertions.assertThat(spanOf(image, CardUpdateRecord.CARD_UPDATE_NUM_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_NUM_LENGTH))
                    .as(":1462 CARD-UPDATE-NUM PIC X(16) at offset 0")
                    .isEqualTo(CARD_NUMBER);
            // :1463 MOVE CC-ACCT-ID-N TO CARD-UPDATE-ACCT-ID - PIC 9(11) at 16, so zero-filled from
            // the left. The account is 12345678901, which is already eleven digits; the zero fill is
            // proved by the shorter account in stagesAShortAccountZeroFilled below.
            Assertions.assertThat(spanOf(image, CardUpdateRecord.CARD_UPDATE_ACCT_ID_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_ACCT_ID_LENGTH))
                    .as(":1463 CARD-UPDATE-ACCT-ID PIC 9(11) at offset 16, taken from CC-ACCT-ID-N")
                    .isEqualTo("12345678901")
                    .hasSize(CardUpdateRecord.CARD_UPDATE_ACCT_ID_LENGTH);
            // :1464-1465 the REDEFINES round trip - PIC 9(03) at 27. CCUP-NEW-CVV-CD is never assigned
            // anywhere in COCRDUPC, so the three characters that travel are spaces and the PIC 9(03)
            // span they land in reads as zero (Finding 2, asserted in its own test below).
            Assertions.assertThat(spanOf(image, CardUpdateRecord.CARD_UPDATE_CVV_CD_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_CVV_CD_LENGTH))
                    .as(":1464-1465 CARD-UPDATE-CVV-CD PIC 9(03) at offset 27, having travelled "
                            + "CARD-CVV-CD-X then CARD-CVV-CD-N")
                    .isEqualTo("000");
            // :1466 MOVE CCUP-NEW-CRDNAME TO CARD-UPDATE-EMBOSSED-NAME - PIC X(50) at 30, so the
            // fourteen typed characters are space-padded on the RIGHT to fifty.
            Assertions.assertThat(spanOf(image, CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH))
                    .as(":1466 CARD-UPDATE-EMBOSSED-NAME PIC X(50) at offset 30, space-padded right")
                    .isEqualTo("JANE R CITIZEN" + " ".repeat(
                            CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH
                                    - "JANE R CITIZEN".length()));
            // :1467-1474 STRING ... DELIMITED BY SIZE - PIC X(10) at 80, with the two literal '-'
            // separators at 1-based characters 5 and 8, which are Java indices 4 and 7.
            String expiry = spanOf(image, CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_OFFSET,
                    CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_LENGTH);
            Assertions.assertThat(expiry)
                    .as(":1467-1474 CARD-UPDATE-EXPIRAION-DATE PIC X(10) at offset 80")
                    .isEqualTo("2029-11-30");
            Assertions.assertThat(expiry.charAt(CardUpdateRecord.EXPIRAION_YEAR_END_INDEX))
                    .as("the literal '-' the STRING supplies, at 1-based character 5")
                    .isEqualTo('-');
            Assertions.assertThat(expiry.charAt(CardUpdateRecord.EXPIRAION_MONTH_END_INDEX))
                    .as("the literal '-' the STRING supplies, at 1-based character 8")
                    .isEqualTo('-');
            // :1475 MOVE CCUP-NEW-CRDSTCD TO CARD-UPDATE-ACTIVE-STATUS - PIC X(01) at 90.
            Assertions.assertThat(spanOf(image, CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_LENGTH))
                    .as(":1475 CARD-UPDATE-ACTIVE-STATUS PIC X(01) at offset 90")
                    .isEqualTo("N");
            // :321 FILLER PIC X(59) at 91 - never moved to, so INITIALIZE leaves it spaces (gate G21).
            Assertions.assertThat(spanOf(image, CardUpdateRecord.FILLER_OFFSET,
                            CardUpdateRecord.FILLER_LENGTH))
                    .as(":321 FILLER PIC X(59) at offset 91, space-filled (gate G21)")
                    .isEqualTo(" ".repeat(CardUpdateRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("a shorter account is zero-filled on the LEFT, because CARD-UPDATE-ACCT-ID is "
                + "PIC 9(11)")
        void stagesAShortAccountZeroFilled() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());
            CardScreenState area = CardUpdateServiceTest.this.workArea();
            // The account of the first row of app/data/ASCII/carddata.txt, which is 00000000005.
            area.setCcAcctIdN(5L);

            service.writeProcessing(area,
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    CardUpdateServiceTest.this.typedNewDetails(), null, codec);

            ArgumentCaptor<CardRecord> written = ArgumentCaptor.forClass(CardRecord.class);
            verify(cardRepository).rewrite(written.capture());
            Assertions.assertThat(spanOf(written.getValue().encode(codec),
                            CardUpdateRecord.CARD_UPDATE_ACCT_ID_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_ACCT_ID_LENGTH))
                    .as("a PIC 9 receiver fills from the RIGHT and pads with zeros, the opposite of "
                            + "PIC X - this is the direction COBOL truncation depends on")
                    .isEqualTo("00000000005");
        }

        @Test
        @DisplayName("INITIALIZE at :1461 means no invocation inherits residue from the one before it")
        void initializeLeavesNoResidueBetweenInvocations() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());
            CardDetails snapshot = CardUpdateServiceTest.this.matchingOldDetails(
                    CardUpdateServiceTest.this.storedRecord());

            // First pass: the long typed name and the 2029-11-30 expiry.
            WriteResult first = write(snapshot, null);

            // Second pass, deliberately every value shorter or different, so residue from the first
            // pass would be visible as trailing characters of the previous value rather than as the
            // spaces and zeros INITIALIZE leaves.
            CardDetails shorter = CardUpdateServiceTest.this.typedNewDetails()
                    .withCrdname("AL")
                    .withExpyear("2031")
                    .withExpmon("01")
                    .withExpday("02")
                    .withCrdstcd("Y");
            WriteResult second = service.writeProcessing(CardUpdateServiceTest.this.workArea(),
                    snapshot, shorter, null, codec);

            byte[] secondImage = second.cardUpdateRecord().orElseThrow().encode(codec);
            Assertions.assertThat(spanOf(secondImage,
                            CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH))
                    .as("INITIALIZE clears the span first, so the tail is spaces and not the previous "
                            + "pass's 'NE R CITIZEN'")
                    .isEqualTo("AL" + " ".repeat(
                            CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH - 2));
            Assertions.assertThat(spanOf(secondImage,
                            CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_OFFSET,
                            CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_LENGTH))
                    .isEqualTo("2031-01-02");
            Assertions.assertThat(spanOf(secondImage, CardUpdateRecord.FILLER_OFFSET,
                            CardUpdateRecord.FILLER_LENGTH))
                    .as("the reserved span is re-cleared on every pass")
                    .isEqualTo(" ".repeat(CardUpdateRecord.FILLER_LENGTH));
            Assertions.assertThat(second.cardUpdateRecord())
                    .as("the two passes staged different records, so neither leaked into the other")
                    .isNotEqualTo(first.cardUpdateRecord());
            Assertions.assertThat(first.cardUpdateRecord().orElseThrow().cardUpdateEmbossedName())
                    .as("and the first result is immutable, so the second pass did not rewrite it "
                            + "underneath its holder")
                    .startsWith("JANE R CITIZEN");
        }

        @Test
        @DisplayName("an all-blank NEW group stages exactly what INITIALIZE would leave")
        void anAllBlankNewGroupStagesTheInitialisedImage() {
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = service.writeProcessing(CardUpdateServiceTest.this.workArea(),
                    CardUpdateServiceTest.this.matchingOldDetails(
                            CardUpdateServiceTest.this.storedRecord()),
                    CardDetails.initialised(DetailGroup.NEW), null, codec);

            CardUpdateRecord staged = result.cardUpdateRecord().orElseThrow();
            Assertions.assertThat(staged.cardUpdateNum())
                    .as("a PIC X(16) item INITIALIZE left blank stays sixteen spaces")
                    .isEqualTo(" ".repeat(CardUpdateRecord.CARD_UPDATE_NUM_LENGTH));
            Assertions.assertThat(staged.cardUpdateCvvCd())
                    .as("three spaces through a PIC 9(03) span read as zero")
                    .isZero();
            Assertions.assertThat(staged.cardUpdateExpiraionDate())
                    .as(":1467-1474 still supplies both literal separators, whatever the parts hold")
                    .isEqualTo("    -  -  ");
            Assertions.assertThat(staged.encode(codec))
                    .hasSize(CardUpdateRecord.RECORD_LENGTH);
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
    // The order of the two dataset statements, and the mutual exclusivity of the four arms.
    // =================================================================================================

    /**
     * The two things about {@code 9200-WRITE-PROCESSING} that are structural rather than
     * value-carrying: the read-for-update always precedes the rewrite, and exactly one outcome is
     * signalled per pass.
     */
    @Nested
    @DisplayName("statement order and outcome exclusivity")
    class OrderingAndExclusivity {

        @Test
        @DisplayName("the read-for-update strictly precedes the rewrite - the lock is never taken "
                + "after the write it exists to protect")
        void theReadForUpdateStrictlyPrecedesTheRewrite() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(storedRecord()));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            write(matchingOldDetails(storedRecord()), null);

            // :1427 comes before :1477 in the paragraph, and the order is behaviour rather than style:
            // reading first is what makes 9300-CHECK-CHANGE-IN-REC's comparison mean anything, and the
            // UPDATE option on the read is what stops the record moving in between.
            InOrder statements = inOrder(cardRepository);
            statements.verify(cardRepository).readForUpdateByCardNumber(CARD_NUMBER);
            statements.verify(cardRepository).rewrite(any(CardRecord.class));
            statements.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("neither refusing arm reaches the rewrite at all, so the order cannot invert")
        void neitherRefusingArmReachesTheRewrite() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.notFound());
            WriteResult lockFailed = write(matchingOldDetails(storedRecord()), null);

            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(storedRecord()));
            WriteResult changed = write(matchingOldDetails(storedRecord()).withCrdstcd("N"), null);

            Assertions.assertThat(lockFailed.outcome())
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE);
            Assertions.assertThat(changed.outcome())
                    .isEqualTo(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE);
            // Two passes, two reads, zero rewrites: :1448 and :1456 both leave before :1477.
            verify(cardRepository, times(2)).readForUpdateByCardNumber(anyString());
            verify(cardRepository, never()).rewrite(any());
        }

        @Test
        @DisplayName("exactly one WS-RETURN-MSG condition holds per pass, because the three 88-levels "
                + "share one PIC X(75) field")
        void exactlyOneConditionHoldsPerPass() {
            Set<WriteOutcome> observed = EnumSet.noneOf(WriteOutcome.class);

            for (WriteResult result : List.of(lockRefused(), changeDetected(), rewriteRefused(),
                    updateDone())) {
                observed.add(result.outcome());
                long conditionsHeld = Stream.of(CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE,
                                CardUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE,
                                CardUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED)
                        .filter(literal -> result.returnMessage().equals(atReturnMessageWidth(literal)))
                        .count();
                long expected = result.outcome() == WriteOutcome.CHANGES_OKAYED_AND_DONE ? 0L : 1L;
                Assertions.assertThat(conditionsHeld)
                        .as("app/cbl/COCRDUPC.cbl:205-210 declares the three conditions as 88-levels on "
                                + "the single WS-RETURN-MSG at :173, so at most one of them can hold at "
                                + "once; the " + result.outcome().cobolCondition() + " arm holds "
                                + expected)
                        .isEqualTo(expected);
                Assertions.assertThat(CardUpdateService.isReturnMessageOff(result.returnMessage()))
                        .as("and the WHEN OTHER arm alone leaves the field off")
                        .isEqualTo(expected == 0L);
            }

            Assertions.assertThat(observed)
                    .as("all four arms of app/cbl/COCRDUPC.cbl:992-1001 are reachable and distinct")
                    .containsExactlyInAnyOrder(WriteOutcome.values());
        }

        @Test
        @DisplayName("the four arms carry four distinct CCUP-CHANGE-ACTION codes, so the caller's "
                + "EVALUATE cannot land on two of them")
        void theFourArmsCarryFourDistinctCodes() {
            Set<String> codes = Stream.of(WriteOutcome.values())
                    .map(WriteOutcome::changeActionCode)
                    .collect(Collectors.toUnmodifiableSet());

            Assertions.assertThat(codes)
                    .as("'L' at :993-994, 'F' at :995-996, 'S' at :997-998 and 'C' at :999-1000")
                    .containsExactlyInAnyOrder("L", "F", "S", "C")
                    .hasSameSizeAs(WriteOutcome.values());
            Assertions.assertThat(Stream.of(WriteOutcome.values())
                            .map(WriteOutcome::firstMatchWinsPosition)
                            .distinct()
                            .count())
                    .as("and each names its own position in the first-match-wins chain")
                    .isEqualTo(WriteOutcome.values().length);
        }

        /**
         * The {@code :1441-1449} arm: the read did not take the lock.
         *
         * @return the outcome
         */
        private WriteResult lockRefused() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(CardReadResult.notFound());
            return write(matchingOldDetails(storedRecord()), null);
        }

        /**
         * The {@code :1453-1457} arm: the record changed while the screen was being filled in.
         *
         * @return the outcome
         */
        private WriteResult changeDetected() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(storedRecord()));
            return write(matchingOldDetails(storedRecord()).withCvvCd("999"), null);
        }

        /**
         * The {@code :1488-1492} arm: the lock held, the comparison passed, the rewrite failed.
         *
         * @return the outcome
         */
        private WriteResult rewriteRefused() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(storedRecord()));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.failed(FileStatus.INVREQ));
            return write(matchingOldDetails(storedRecord()), null);
        }

        /**
         * The {@code WHEN OTHER} arm: the record was rewritten and no condition was set.
         *
         * @return the outcome
         */
        private WriteResult updateDone() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(storedRecord()));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());
            return write(matchingOldDetails(storedRecord()), null);
        }

        /**
         * A {@code WS-RETURN-MSG} literal at the field's declared {@code PIC X(75)} width.
         *
         * @param literal the {@code 88}-level literal
         * @return the literal right-padded to {@link CardUpdateService#RETURN_MESSAGE_LENGTH}
         */
        private String atReturnMessageWidth(String literal) {
            return literal + " ".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH - literal.length());
        }
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
        @DisplayName("the CVV comparison is on CHARACTER IMAGES, so '007' and '7  ' differ - "
                + "CCUP-OLD-CVV-CD is PIC X(3) and CARD-CVV-CD is PIC 9(03)")
        void theCvvComparisonIsOnCharacterImagesAndNotOnNumbers() {
            // app/cbl/COCRDUPC.cbl:1503 compares CARD-CVV-CD, declared PIC 9(03) at
            // app/cpy/CVACT02Y.cpy:7, against CCUP-OLD-CVV-CD, declared PIC X(3) at
            // app/cbl/COCRDUPC.cbl:294. A comparison between a numeric item and an alphanumeric one
            // is an alphanumeric comparison in COBOL, so the record's zoned image is what is examined,
            // character by character. The record holds CVV 7, whose PIC 9(03) image is left-zero-filled
            // to "007"; a screen that carried "7  " - the PIC X(3) form of the same digit, filled from
            // the left and space-padded - is therefore a DIFFERENT three characters and trips the
            // check. An implementation that parsed both sides to int and compared numerically would
            // find 7 equal to 7 and let the update through, which is the single subtlest way to lose
            // parity in this paragraph.
            CardRecord stored = storedRecord().withCardCvvCd(7);
            Assertions.assertThat(stored.cardCvvCdImage(codec))
                    .as("PIC 9(03) fills from the RIGHT and pads with zeros")
                    .isEqualTo("007");

            CardDetails spacePadded = matchingOldDetails(stored).withCvvCd("7  ");
            ChangeCheck differs = service.checkChangeInRec(stored, spacePadded, codec);

            Assertions.assertThat(differs.dataWasChanged())
                    .as("'007' and '7  ' are different character images, so the record changed")
                    .isTrue();
            Assertions.assertThat(differs.cvvDiffers()).isTrue();
            Assertions.assertThat(differs.describeDifferences()).isEqualTo("CARD-CVV-CD");
            Assertions.assertThat(differs.oldDetails().cvvCd())
                    .as(":1512 writes the record's own image back, so the repainted screen shows it")
                    .isEqualTo("007");

            // The positive control, so the test cannot pass by comparing everything as unequal: the
            // same record against the same image matches.
            CardDetails zeroFilled = matchingOldDetails(stored).withCvvCd("007");
            ChangeCheck matches = service.checkChangeInRec(stored, zeroFilled, codec);

            Assertions.assertThat(matches.dataWasChanged()).isFalse();
            Assertions.assertThat(matches.cvvDiffers()).isFalse();
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
    // Gates G43 and G44 stated negatively: what the concurrency control is NOT built out of.
    // =================================================================================================

    /**
     * The concurrency control is the re-read-and-compare of {@code 9300-CHECK-CHANGE-IN-REC} and
     * nothing else.
     *
     * <p>A version column is the textbook Java answer to a lost update and it is forbidden twice over.
     * Gate G44 forbids DDL, entity annotations and generated schema outright - adding a column to
     * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} would be a schema change, which the migration plan
     * excludes in its scope boundary. Gate G43 requires <em>this</em> comparison, on <em>these</em> six
     * fields, in <em>this</em> order. So the tests below assert the absence positively rather than
     * leaving it to a comment: nothing is stamped onto the record on its way out, nothing is bumped on
     * its way in, no item of either layout names a version or a timestamp, and the record has no spare
     * byte one could live in even if someone wanted it to.
     */
    @Nested
    @DisplayName("gates G43 and G44 - the mechanism is a re-read-and-compare, with no version column")
    class ConcurrencyMechanism {

        @Test
        @DisplayName("neither layout declares a version, timestamp, row-version or ETag item")
        void noLayoutDeclaresAVersionItem() {
            CardUpdateRecord staged = service.stageUpdateRecord(typedNewDetails(), ACCOUNT_ID, codec);

            Assertions.assertThat(staged.itemValues(codec).keySet())
                    .as("app/cbl/COCRDUPC.cbl:314-321 declares six named items and one FILLER, and "
                            + "these are their verbatim COBOL names")
                    .containsExactly("CARD-UPDATE-NUM", "CARD-UPDATE-ACCT-ID", "CARD-UPDATE-CVV-CD",
                            "CARD-UPDATE-EMBOSSED-NAME", "CARD-UPDATE-EXPIRAION-DATE",
                            "CARD-UPDATE-ACTIVE-STATUS");
            Assertions.assertThat(matchingOldDetails(storedRecord()).itemValues().keySet())
                    .as("app/cbl/COCRDUPC.cbl:290-300 declares eight items and two group items")
                    .noneMatch(this::namesAConcurrencyToken);
            Assertions.assertThat(staged.itemValues(codec).keySet())
                    .noneMatch(this::namesAConcurrencyToken);
        }

        @Test
        @DisplayName("the seven declared spans exhaust the record, so no spare byte exists for one")
        void theDeclaredSpansExhaustTheRecord() {
            int sumOfSpans = CardUpdateRecord.CARD_UPDATE_NUM_LENGTH
                    + CardUpdateRecord.CARD_UPDATE_ACCT_ID_LENGTH
                    + CardUpdateRecord.CARD_UPDATE_CVV_CD_LENGTH
                    + CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH
                    + CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_LENGTH
                    + CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_LENGTH
                    + CardUpdateRecord.FILLER_LENGTH;

            Assertions.assertThat(sumOfSpans)
                    .as("16 + 11 + 3 + 50 + 10 + 1 + 59 = 150, so the image is fully accounted for and "
                            + "a version or timestamp column would have nowhere to sit")
                    .isEqualTo(CardUpdateRecord.RECORD_LENGTH)
                    .isEqualTo(CardRecord.RECORD_LENGTH);
            Assertions.assertThat(CardUpdateRecord.FILLER_OFFSET
                            + CardUpdateRecord.FILLER_LENGTH)
                    .as("the reserved span runs to the last byte and is emitted as spaces, not as a "
                            + "place to hide bookkeeping")
                    .isEqualTo(CardUpdateRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("nothing is stamped onto the record on its way to the rewrite")
        void nothingIsStampedOntoTheRecord() {
            when(cardRepository.readForUpdateByCardNumber(anyString()))
                    .thenReturn(cardRead(storedRecord()));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult result = write(matchingOldDetails(storedRecord()), null);
            CardUpdateRecord staged = result.cardUpdateRecord().orElseThrow();

            ArgumentCaptor<CardRecord> written = ArgumentCaptor.forClass(CardRecord.class);
            verify(cardRepository).rewrite(written.capture());
            Assertions.assertThat(written.getValue().encode(codec))
                    .as("the bytes handed to the rewrite are the staged bytes exactly - no generated "
                            + "key, no row version and no update timestamp is added on the way out")
                    .isEqualTo(staged.encode(codec));
        }

        @Test
        @DisplayName("the verdict is a pure function of the record and the snapshot, so replaying the "
                + "same update is not rejected as stale")
        void theVerdictIsAPureFunctionOfItsTwoInputs() {
            CardRecord stored = storedRecord();
            CardDetails snapshot = matchingOldDetails(stored);
            when(cardRepository.readForUpdateByCardNumber(anyString())).thenReturn(cardRead(stored));
            when(cardRepository.rewrite(any())).thenReturn(CardWriteResult.normal());

            WriteResult first = write(snapshot, null);
            WriteResult replayed = write(snapshot, null);
            CardUpdateService freshInstance = new CardUpdateService(cardRepository, unitOfWork);
            WriteResult onAFreshInstance = freshInstance.writeProcessing(workArea(), snapshot,
                    typedNewDetails(), null, codec);

            Assertions.assertThat(first.outcome()).isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            Assertions.assertThat(replayed.outcome())
                    .as("no version was bumped by the first update, so the identical snapshot is still "
                            + "current and the replay succeeds. A version column would have made this "
                            + "second attempt a stale-write failure, which app/cbl/COCRDUPC.cbl does "
                            + "not do")
                    .isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            Assertions.assertThat(onAFreshInstance.outcome())
                    .as("the service holds no state between calls or between instances (practice B9)")
                    .isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            Assertions.assertThat(replayed.cardUpdateRecord())
                    .isEqualTo(first.cardUpdateRecord());
            Assertions.assertThat(onAFreshInstance.cardUpdateRecord())
                    .isEqualTo(first.cardUpdateRecord());
        }

        @Test
        @DisplayName("the six compared items are the whole mechanism: change one and only one, and the "
                + "verdict flips")
        void theSixComparedItemsAreTheWholeMechanism() {
            CardRecord stored = storedRecord();
            CardDetails current = matchingOldDetails(stored);

            Assertions.assertThat(service.checkChangeInRec(stored, current, codec).dataWasChanged())
                    .isFalse();
            Assertions.assertThat(service
                            .checkChangeInRec(stored, current.withCrdstcd("N"), codec)
                            .dataWasChanged())
                    .as("one differing item is sufficient, because :1503-1509 joins the six with AND")
                    .isTrue();
            Assertions.assertThat(service
                            .checkChangeInRec(stored, current.withAcctid("99999999999"), codec)
                            .dataWasChanged())
                    .as("and nothing outside the six matters, not even the account identifier")
                    .isFalse();
        }

        /**
         * Whether an item name would betray a bookkeeping column smuggled into either layout.
         *
         * @param itemName the verbatim COBOL item name
         * @return {@code true} when the name reads as a concurrency token rather than card data
         */
        private boolean namesAConcurrencyToken(String itemName) {
            String upper = itemName.toUpperCase(Locale.ROOT);
            return upper.contains("VERSION")
                    || upper.contains("TIMESTAMP")
                    || upper.contains("ROWVER")
                    || upper.contains("ROWID")
                    || upper.contains("ETAG")
                    || upper.contains("LAST-UPD")
                    || upper.contains("UPDATED-AT");
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
                            null, snapshot, Optional.of(staged),
                            Optional.of("   "), Optional.empty(), 0, 0))
                    .withMessageContaining("1444");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE, false,
                            null, snapshot, Optional.empty(), Optional.empty(),
                            Optional.empty(), FileStatus.NOTFND, 0));
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, Optional.of(staged),
                            Optional.empty(), Optional.empty(), 0, 0))
                    .withMessageContaining("1461-1475");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, CardDetails.initialised(DetailGroup.NEW),
                            Optional.empty(), Optional.empty(),
                            Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(null, false, null, snapshot,
                            Optional.empty(), Optional.empty(),
                            Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, null, Optional.empty(), Optional.empty(),
                            Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, null, Optional.empty(),
                            Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, Optional.empty(), null,
                            Optional.empty(), 0, 0));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                            null, snapshot, Optional.empty(), Optional.empty(),
                            null, 0, 0));
        }

        @Test
        @DisplayName("WriteResult normalises WS-RETURN-MSG to its declared PIC X(75) width")
        void writeResultNormalisesTheMessageWidth() {
            CardDetails snapshot = matchingOldDetails(storedRecord());

            WriteResult padded = new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false, "hi",
                    snapshot, Optional.empty(), Optional.empty(),
                    Optional.empty(), 0, 0);
            WriteResult exact = new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                    "x".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH), snapshot,
                    Optional.empty(), Optional.empty(),
                    Optional.empty(), 0, 0);
            WriteResult overWide = new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                    "y".repeat(CardUpdateService.RETURN_MESSAGE_LENGTH + 10), snapshot,
                    Optional.empty(), Optional.empty(),
                    Optional.empty(), 0, 0);

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
                return cardRead(stored);
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
                return cardRead(stored);
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

    // =================================================================================================
    // Synthesised read outcomes. A CardReadResult carries the decoded record AND the bytes it was
    // decoded from, because DISPLAY CARD-RECORD (app/cbl/CBACT02C.cbl:78) writes the record area and the
    // area's FILLER X(59) holds whatever the row held. A test constructing an outcome has no row, so the
    // image it supplies is the one a row of exactly this record would carry - which is what these two
    // helpers state, once, rather than at every call site.
    // =================================================================================================

    /**
     * The normal arm over a synthesised row of this record.
     *
     * @param record the record the row would carry
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardRead(CardRecord record) {
        return CardReadResult.normal(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    /**
     * The duplicate-key arm over a synthesised row of this record.
     *
     * @param record the first record sharing the alternate key
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardReadDuplicate(CardRecord record) {
        return CardReadResult.duplicateKey(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }
}
