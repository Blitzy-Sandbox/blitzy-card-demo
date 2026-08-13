package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.CardRepository.CardWriteResult;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;

import java.util.Objects;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

/**
 * The write path of {@code app/cbl/COCRDUPC.cbl} - the two paragraphs that lock a card record, decide
 * whether anyone changed it while the screen was being filled in, and rewrite it.
 *
 * <p>The card number and the account identifier are deliberately not compared, because the COBOL does not
 * compare them.
 */
@Service
public class CardUpdateService {
    private static final Log LOG = LogFactory.getLog(CardUpdateService.class);

    /**
     * {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '} ({@code app/cbl/COCRDUPC.cbl:251-252}) - the CICS
     * file name both the read-for-update at {@code :1428} and the rewrite at {@code :1478} name.
     */
    public static final String CICS_FILE_NAME = "CARDDAT ";

    /**
     * {@code LENGTH OF WS-CARD-RID-CARDNUM} ({@code app/cbl/COCRDUPC.cbl:129,1431}): the sixteen characters
     * of {@code WS-CARD-RID-CARDNUM PIC X(16)}, which is the {@code KEYLENGTH} the read-for-update states
     * and the width of the {@code CARDDAT} primary key.
     */
    public static final int CARD_KEY_LENGTH = CardRecord.CARD_NUM_LENGTH;

    /**
     * {@code LENGTH OF CARD-UPDATE-RECORD}: {@code 150}, the length the rewrite at
     * {@code app/cbl/COCRDUPC.cbl:1480} states, and the same {@code 150} that {@code app/cpy/CVACT02Y.cpy}
     * declares.
     */
    public static final int CARD_UPDATE_RECORD_LENGTH = CardUpdateRecord.RECORD_LENGTH;

    /**
     * The declared width of {@code WS-RETURN-MSG PIC X(75)} ({@code app/cbl/COCRDUPC.cbl:173}) - the single
     * field that carries every outcome of this write path, and the field whose all-spaces state
     * {@code 88 WS-RETURN-MSG-OFF} at {@code :174} names.
     */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /**
     * {@code LIT-LOWER PIC X(26) VALUE 'abcdefghijklmnopqrstuvwxyz'} ({@code app/cbl/COCRDUPC.cbl:262-263})
     * - the twenty-six characters {@code INSPECT ... CONVERTING} at {@code :1499-1501} converts from.
     */
    public static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /**
     * {@code LIT-UPPER PIC X(26) VALUE 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'} ({@code app/cbl/COCRDUPC.cbl:260-261})
     * - the twenty-six characters {@code INSPECT ... CONVERTING} at {@code :1499-1501} converts to.
     */
    public static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * The value {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} names ({@code app/cbl/COCRDUPC.cbl:174}):
     * {@value #RETURN_MESSAGE_LENGTH} spaces, which is the state {@code SET WS-RETURN-MSG-OFF TO TRUE} at
     * {@code :384} establishes at the top of every pass.
     */
    public static final String RETURN_MESSAGE_OFF = " ".repeat(RETURN_MESSAGE_LENGTH);

    /**
     * {@code 88 COULD-NOT-LOCK-FOR-UPDATE} ({@code app/cbl/COCRDUPC.cbl:205-206}) - the literal
     * {@code SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE} at {@code :1446} moves into {@code WS-RETURN-MSG}, byte
     * for byte.
     */
    public static final String MSG_COULD_NOT_LOCK_FOR_UPDATE = "Could not lock record for update";

    /**
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} ({@code app/cbl/COCRDUPC.cbl:207-208}) - the literal
     * {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} at {@code :1511} moves into {@code WS-RETURN-MSG},
     * byte for byte.
     */
    public static final String MSG_DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /**
     * {@code 88 LOCKED-BUT-UPDATE-FAILED} ({@code app/cbl/COCRDUPC.cbl:209-210}) - the literal
     * {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code :1491} moves into {@code WS-RETURN-MSG}, byte
     * for byte.
     */
    public static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    /**
     * {@code MOVE 'READ' TO ERROR-OPNAME} - the operation name {@code COCRDUPC} uses when it composes
     * {@code WS-FILE-ERROR-MESSAGE} for a failed read ({@code app/cbl/COCRDUPC.cbl:1407}).
     */
    public static final String READ_OPERATION_NAME = "READ";

    /**
     * The operation name for a failed rewrite, the counterpart of {@link #READ_OPERATION_NAME}.
     */
    public static final String REWRITE_OPERATION_NAME = "REWRITE";

    private static final int ZONED_DIGIT_NIBBLE_MASK = 0x0F;

    private static final int MAX_DIGIT_NIBBLE = 9;

    private static final int DECIMAL_RADIX = 10;

    private static final int NO_ZONED_DIGIT = 0;

    private static final char LOWEST_CONVERTED_CHARACTER = 'a';

    private static final char HIGHEST_CONVERTED_CHARACTER = 'z';

    private static final char SPACE = ' ';

    private static final String UNIT_OF_WORK_DESCRIPTION =
            "9200-WRITE-PROCESSING (app/cbl/COCRDUPC.cbl:1420-1496)";

    private final CardRepository cardRepository;

    private final DatasetUnitOfWork unitOfWork;

    public CardUpdateService(CardRepository cardRepository, DatasetUnitOfWork unitOfWork) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "A CARDDAT repository is required: 9200-WRITE-PROCESSING both reads the card record "
                        + "for update and rewrites it, and neither goes anywhere but through it");
        this.unitOfWork = Objects.requireNonNull(unitOfWork,
                "A unit of work is required: the read-for-update at app/cbl/COCRDUPC.cbl:1427 takes a "
                        + "record lock that must be held through 9300-CHECK-CHANGE-IN-REC and the "
                        + "rewrite at :1477, and CardRepository refuses to issue FOR UPDATE outside "
                        + "one");
    }

    /**
     * {@code 9200-WRITE-PROCESSING} ({@code app/cbl/COCRDUPC.cbl:1420-1496}): locks the card record,
     * refuses the update if it changed under the screen, and otherwise rewrites it at full width.
     *
     * <p>{@code INPUT-ERROR} is set unconditionally; {@code COULD-NOT-LOCK-FOR-UPDATE} is set only when the
     * return message is still off, so that a lock failure never overwrites an earlier and more specific
     * message.
     *
     * @param workArea the {@code CVCRD01Y} work area, read for {@code CC-CARD-NUM} ({@code :1425}) and
     *     {@code CC-ACCT-ID-N} ({@code :1463})
     * @param oldDetails {@code CCUP-OLD-DETAILS} - the snapshot the screen was painted from, and what
     *     {@code 9300-CHECK-CHANGE-IN-REC} compares against
     * @param newDetails {@code CCUP-NEW-DETAILS} - what the user typed, and the source of every staged
     *     value
     * @param returnMessage the current content of {@code WS-RETURN-MSG} ({@code :173}), which decides the
     *     {@code :1445} guard
     * @param codec the codec carrying the code page, and the owner of every pad, truncate and concatenate
     *     rule used here
     * @return the outcome, never {@code null}
     * @throws NullPointerException if {@code workArea}, {@code oldDetails}, {@code newDetails} or
     *     {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code oldDetails} is not the {@link DetailGroup#OLD} group or
     *     {@code newDetails} is not the {@link DetailGroup#NEW} group
     */
    public WriteResult writeProcessing(CardScreenState workArea,
                                       CardDetails oldDetails,
                                       CardDetails newDetails,
                                       String returnMessage,
                                       FixedWidthCodec codec) {
        Objects.requireNonNull(workArea, "The CVCRD01Y work area is required: 9200-WRITE-PROCESSING "
                + "reads CC-CARD-NUM at app/cbl/COCRDUPC.cbl:1425 and CC-ACCT-ID-N at :1463 from it");
        Objects.requireNonNull(codec, "A codec is required: every move in this paragraph is a COBOL "
                + "MOVE with a declared width, and the codec owns the pad and truncate rules");
        requireGroup(oldDetails, DetailGroup.OLD, "CCUP-OLD-DETAILS", "1503-1509");
        requireGroup(newDetails, DetailGroup.NEW, "CCUP-NEW-DETAILS", "1461-1475");

        return unitOfWork.execute(UNIT_OF_WORK_DESCRIPTION,
                () -> writeProcessingUnderLock(workArea, oldDetails, newDetails, returnMessage, codec));
    }

    private WriteResult writeProcessingUnderLock(CardScreenState workArea,
                                                 CardDetails oldDetails,
                                                 CardDetails newDetails,
                                                 String returnMessage,
                                                 FixedWidthCodec codec) {
        String cardRid = codec.movePicX(workArea.getCcCardNum(), CARD_KEY_LENGTH);

        CardReadResult read = cardRepository.readForUpdateByCardNumber(cardRid);

        if (read.resp() != FileStatus.NORMAL) {
            boolean messageOff = isReturnMessageOff(returnMessage);
            String message = messageOff ? MSG_COULD_NOT_LOCK_FOR_UPDATE : returnMessage;
            LOG.warn("A read-for-update of " + CICS_FILE_NAME.trim() + " did not take the lock: "
                    + "RESP=" + read.resp() + ", RESP2=" + read.resp2() + ". The update is abandoned "
                    + "and no record has been changed. The return message was "
                    + (messageOff ? "off, so the could-not-lock message is now set"
                            : "already set by an earlier paragraph, so it is left as it stands"));
            return new WriteResult(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE, true, message, oldDetails,
                    Optional.empty(), Optional.empty(), Optional.of(READ_OPERATION_NAME),
                    read.resp(), read.resp2());
        }

        CardRecord locked = read.requireRecord();

        ChangeCheck check = checkChangeInRec(locked, oldDetails, codec);
        if (check.dataWasChanged()) {
            LOG.info("The " + CICS_FILE_NAME.trim() + " record changed after the screen was painted, "
                    + "so the update is refused and no record has been changed. Items that differ: "
                    + check.describeDifferences() + ". The CCUP-OLD-DETAILS snapshot has been "
                    + "refreshed from the stored record so the screen can be repainted with the "
                    + "values that actually won.");
            return new WriteResult(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE, false,
                    MSG_DATA_WAS_CHANGED_BEFORE_UPDATE, check.oldDetails(), Optional.empty(),
                    Optional.empty(), Optional.empty(), read.resp(), read.resp2());
        }

        CardUpdateRecord staged = stageUpdateRecord(newDetails, workArea.getCcAcctIdN(), codec);
        String stagedCvvImage = redefinedCvvImage(newDetails.cvvCd(), codec);

        CardWriteResult written = cardRepository.rewrite(asCardRecord(staged));

        if (written.resp() != FileStatus.NORMAL) {
            LOG.error("A rewrite of the locked " + CICS_FILE_NAME.trim() + " record failed: RESP="
                    + written.resp() + ", RESP2=" + written.resp2() + ". The lock was taken and the "
                    + "concurrency check passed, so this is a backend failure rather than a stale "
                    + "screen.");
            return new WriteResult(WriteOutcome.LOCKED_BUT_UPDATE_FAILED, false,
                    MSG_LOCKED_BUT_UPDATE_FAILED, oldDetails, Optional.of(staged),
                    Optional.of(stagedCvvImage), Optional.of(REWRITE_OPERATION_NAME),
                    written.resp(), written.resp2());
        }

        LOG.info("A " + CICS_FILE_NAME.trim() + " record was rewritten at its full "
                + CARD_UPDATE_RECORD_LENGTH + " bytes.");
        return new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                returnMessage, oldDetails, Optional.of(staged),
                Optional.of(stagedCvvImage), Optional.empty(), written.resp(),
                written.resp2());
    }

    /**
     * {@code 9300-CHECK-CHANGE-IN-REC} ({@code app/cbl/COCRDUPC.cbl:1498-1523}): decides whether the record
     * that was just locked still matches the snapshot the screen was painted from.
     *
     * @param record the record just read under the lock, {@code CARD-RECORD} at {@code :1432}
     * @param oldDetails {@code CCUP-OLD-DETAILS}, the snapshot to compare against
     * @param codec the codec, which renders the record's {@code PIC 9(03)} CVV into the snapshot's
     *     three-character {@code PIC X(3)} form so like is compared with like
     * @return the check outcome, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code oldDetails} is not the {@link DetailGroup#OLD} group
     */
    public ChangeCheck checkChangeInRec(CardRecord record, CardDetails oldDetails,
                                        FixedWidthCodec codec) {
        Objects.requireNonNull(record, "The record read under the lock is required: "
                + "9300-CHECK-CHANGE-IN-REC compares it against the snapshot the screen was painted "
                + "from, and that comparison is the whole of the concurrency control");
        Objects.requireNonNull(codec, "A codec is required: CARD-CVV-CD is PIC 9(03) and "
                + "CCUP-OLD-CVV-CD is PIC X(3), so one has to be rendered into the other's form");
        requireGroup(oldDetails, DetailGroup.OLD, "CCUP-OLD-DETAILS", "1503-1509");

        CardRecord folded = foldEmbossedName(record);

        String storedCvvImage = folded.cardCvvCdImage(codec);
        boolean cvvMatches = storedCvvImage.equals(oldDetails.cvvCd());
        boolean nameMatches = folded.cardEmbossedName().equals(oldDetails.crdname());
        boolean yearMatches = folded.cardExpiraionDateYear().equals(oldDetails.expyear());
        boolean monthMatches = folded.cardExpiraionDateMonth().equals(oldDetails.expmon());
        boolean dayMatches = folded.cardExpiraionDateDay().equals(oldDetails.expday());
        boolean statusMatches = folded.cardActiveStatus().equals(oldDetails.crdstcd());

        if (cvvMatches && nameMatches && yearMatches && monthMatches && dayMatches && statusMatches) {
            return new ChangeCheck(false, oldDetails, folded, false, false, false, false, false,
                    false);
        }

        // Each move is written out rather than looped, because each has its own source item in the COBOL
        // and the field-by-field correspondence is what parity is checked on.
        CardDetails refreshed = oldDetails
                .withCvvCd(codec.movePicX(storedCvvImage, CardDetails.CVV_CD_LENGTH))
                .withCrdname(codec.movePicX(folded.cardEmbossedName(), CardDetails.CRDNAME_LENGTH))
                .withExpyear(codec.movePicX(folded.cardExpiraionDateYear(),
                        CardDetails.EXPYEAR_LENGTH))
                .withExpmon(codec.movePicX(folded.cardExpiraionDateMonth(),
                        CardDetails.EXPMON_LENGTH))
                .withExpday(codec.movePicX(folded.cardExpiraionDateDay(), CardDetails.EXPDAY_LENGTH))
                .withCrdstcd(codec.movePicX(folded.cardActiveStatus(), CardDetails.CRDSTCD_LENGTH));

        return new ChangeCheck(true, refreshed, folded, !cvvMatches, !nameMatches, !yearMatches,
                !monthMatches, !dayMatches, !statusMatches);
    }

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} ({@code app/cbl/COCRDUPC.cbl:174}): whether
     * {@code WS-RETURN-MSG} is still clear, which is the condition guarding
     * {@code SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE} at {@code :1445-1447}.
     *
     * <p>{@code COCRDUPC} declares this condition on its own {@code 05 WS-RETURN-MSG PIC X(75)} at
     * {@code :173} and never touches {@code CCARD-RETURN-MSG}, whose {@code 88 CCARD-RETURN-MSG-OFF} at
     * {@code app/cpy/CVCRD01Y.cpy:30} does test {@code LOW-VALUES}.
     *
     * @param returnMessage the current content of {@code WS-RETURN-MSG}, or {@code null} for the cleared
     *     state
     * @return {@code true} when every one of the {@value #RETURN_MESSAGE_LENGTH} characters is a space
     */
    public static boolean isReturnMessageOff(String returnMessage) {
        if (returnMessage == null) {
            return true;
        }
        int examined = Math.min(returnMessage.length(), RETURN_MESSAGE_LENGTH);
        for (int index = 0; index < examined; index++) {
            if (returnMessage.charAt(index) != SPACE) {
                return false;
            }
        }
        // Any characters beyond the declared width are outside the field and cannot make it non-blank; any
        // characters short of it are the space padding a PIC X(75) receiver would already hold.
        return true;
    }

    /**
     * {@code INSPECT ... CONVERTING LIT-LOWER TO LIT-UPPER} ({@code app/cbl/COCRDUPC.cbl:1499-1501}, with
     * the two literals declared at {@code :260-263}).
     *
     * <p>The width is therefore preserved exactly, which matters because the result is compared against a
     * {@code PIC X(50)} item and written back into one.
     *
     * @param value the characters to convert; may be empty
     * @return {@code value} with each of {@code 'a'} to {@code 'z'} replaced by its counterpart in
     *     {@link #LIT_UPPER}, and every other character untouched
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String convertingLowerToUpper(String value) {
        Objects.requireNonNull(value, "A value is required to convert; INSPECT ... CONVERTING acts on "
                + "a field, and a field always has content");
        char[] characters = value.toCharArray();
        boolean converted = false;
        for (int index = 0; index < characters.length; index++) {
            char character = characters[index];
            if (character >= LOWEST_CONVERTED_CHARACTER && character <= HIGHEST_CONVERTED_CHARACTER) {
                characters[index] = LIT_UPPER.charAt(character - LOWEST_CONVERTED_CHARACTER);
                converted = true;
            }
        }
        return converted ? new String(characters) : value;
    }

    /**
     * Step 1 of {@code 9300-CHECK-CHANGE-IN-REC}: the record with
     * {@code INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER} applied
     * ({@code app/cbl/COCRDUPC.cbl:1499-1501}).
     *
     * @param record the record read under the lock
     * @return the record with its embossed name folded, or the same instance when the name held no
     *     lower-case letter
     * @throws NullPointerException if {@code record} is {@code null}
     */
    public static CardRecord foldEmbossedName(CardRecord record) {
        Objects.requireNonNull(record, "A record is required to fold its embossed name");
        String folded = convertingLowerToUpper(record.cardEmbossedName());
        if (folded.equals(record.cardEmbossedName())) {
            return record;
        }
        return record.withCardEmbossedName(folded);
    }

    /**
     * The first half of the CVV {@code REDEFINES} round-trip: {@code MOVE CCUP-NEW-CVV-CD TO CARD-CVV-CD-X}
     * ({@code app/cbl/COCRDUPC.cbl:1464}).
     *
     * @param ccupNewCvvCd the sending value, {@code CCUP-NEW-CVV-CD}
     * @param codec the codec supplying the alphanumeric move rule
     * @return exactly {@value CardUpdateRecord#CARD_UPDATE_CVV_CD_LENGTH} characters, verbatim
     * @throws NullPointerException if either argument is {@code null}
     */
    public static String redefinedCvvImage(String ccupNewCvvCd, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to move CCUP-NEW-CVV-CD into "
                + "CARD-CVV-CD-X; the pad and truncate direction is the codec's rule, not this "
                + "method's");
        Objects.requireNonNull(ccupNewCvvCd, "A CCUP-NEW-CVV-CD value is required; the COBOL item is "
                + "PIC X(3) and always has content - three spaces, in practice");
        return codec.movePicX(ccupNewCvvCd, CardUpdateRecord.CARD_UPDATE_CVV_CD_LENGTH);
    }

    /**
     * The second half of the CVV {@code REDEFINES} round-trip:
     * {@code MOVE CARD-CVV-CD-N TO CARD-UPDATE-CVV-CD} ({@code app/cbl/COCRDUPC.cbl:1465}), reading a span
     * written as alphanumeric back through its {@code PIC 9} view.
     *
     * <p>Two properties make it total, and it therefore never throws on the content of a CVV item: a space
     * is {@code 0x40} in {@code IBM037} and {@code 0x20} in {@code US-ASCII}.
     *
     * @param cvvImage the three characters occupying the span, as
     *     {@link #redefinedCvvImage(String, FixedWidthCodec)} produced them
     * @param codec the codec, which states the code page the bytes are read in
     * @return the value the span's zoned digits denote, between 0 and 999
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if a character has no representation in the configured code page,
     *     which is a code-page defect rather than a data one
     */
    public static int zonedDigitsValue(String cvvImage, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to read a zoned span: the digit lives in "
                + "each byte's low-order nibble, so the code page has to be stated and is never "
                + "assumed");
        Objects.requireNonNull(cvvImage, "A span image is required to read it as PIC 9");
        byte[] bytes = codec.encodeImage(cvvImage, CardUpdateRecord.CARD_UPDATE_CVV_CD.name());
        int value = 0;
        for (byte encoded : bytes) {
            int nibble = encoded & ZONED_DIGIT_NIBBLE_MASK;
            int digit = nibble <= MAX_DIGIT_NIBBLE ? nibble : NO_ZONED_DIGIT;
            value = value * DECIMAL_RADIX + digit;
        }
        return value;
    }

    /**
     * Steps 5 of {@code 9200-WRITE-PROCESSING}: {@code INITIALIZE CARD-UPDATE-RECORD} and the six moves
     * that follow it ({@code app/cbl/COCRDUPC.cbl:1461-1475}).
     *
     * @param newDetails {@code CCUP-NEW-DETAILS}; must be the {@link DetailGroup#NEW} group
     * @param acctId {@code CC-ACCT-ID-N}, the work area's numeric account view
     * @param codec the codec owning the move and concatenation rules
     * @return the staged {@link #CARD_UPDATE_RECORD_LENGTH}-byte record
     * @throws NullPointerException if {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code newDetails} is not the {@link DetailGroup#NEW} group, or
     *     {@code acctId} does not fit {@code PIC 9(11)}
     */
    public CardUpdateRecord stageUpdateRecord(CardDetails newDetails, long acctId,
                                              FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to stage CARD-UPDATE-RECORD: six of the "
                + "seven statements at app/cbl/COCRDUPC.cbl:1461-1475 are COBOL MOVEs or a STRING, and "
                + "the codec owns both rules");
        requireGroup(newDetails, DetailGroup.NEW, "CCUP-NEW-DETAILS", "1461-1475");

        return new CardUpdateRecord(
                codec.movePicX(newDetails.cardid(), CardUpdateRecord.CARD_UPDATE_NUM_LENGTH),
                acctId,
                zonedDigitsValue(redefinedCvvImage(newDetails.cvvCd(), codec), codec),
                codec.movePicX(newDetails.crdname(),
                        CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH),
                CardUpdateRecord.compose(newDetails, codec),
                codec.movePicX(newDetails.crdstcd(),
                        CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_LENGTH));
    }

    /**
     * Presents the staged {@code CARD-UPDATE-RECORD} as the {@code app/cpy/CVACT02Y.cpy} record the
     * repository rewrites.
     *
     * @param staged the staged record
     * @return the same seven fields as a {@link CardRecord}
     * @throws NullPointerException if {@code staged} is {@code null}
     */
    public static CardRecord asCardRecord(CardUpdateRecord staged) {
        Objects.requireNonNull(staged, "A staged CARD-UPDATE-RECORD is required; there is no partial "
                + "rewrite, because app/cbl/COCRDUPC.cbl:1480 states the length as LENGTH OF "
                + "CARD-UPDATE-RECORD and that record is a full " + CARD_UPDATE_RECORD_LENGTH
                + " bytes");
        return new CardRecord(staged.cardUpdateNum(),
                staged.cardUpdateAcctId(),
                staged.cardUpdateCvvCd(),
                staged.cardUpdateEmbossedName(),
                staged.cardUpdateExpiraionDate(),
                staged.cardUpdateActiveStatus());
    }

    /**
     * {@code WS-RETURN-MSG} as a {@code PIC X(75)} field holds it: right-padded with spaces to
     * {@value #RETURN_MESSAGE_LENGTH} characters and truncated on the right when longer, with {@code null}
     * read as the cleared state {@code app/cbl/COCRDUPC.cbl:384} establishes.
     *
     * @param returnMessage the message, or {@code null} for the cleared state
     * @return exactly {@value #RETURN_MESSAGE_LENGTH} characters
     */
    private static String atReturnMessageWidth(String returnMessage) {
        if (returnMessage == null) {
            return RETURN_MESSAGE_OFF;
        }
        if (returnMessage.length() == RETURN_MESSAGE_LENGTH) {
            return returnMessage;
        }
        if (returnMessage.length() > RETURN_MESSAGE_LENGTH) {
            return returnMessage.substring(0, RETURN_MESSAGE_LENGTH);
        }
        return returnMessage + " ".repeat(RETURN_MESSAGE_LENGTH - returnMessage.length());
    }

    private static void requireGroup(CardDetails details, DetailGroup expected, String cobolName,
                                     String sourceLines) {
        Objects.requireNonNull(details, "A " + cobolName + " snapshot is required by "
                + "app/cbl/COCRDUPC.cbl:" + sourceLines);
        if (details.group() != expected) {
            throw new IllegalArgumentException("app/cbl/COCRDUPC.cbl:" + sourceLines + " reads "
                    + cobolName + ", but the supplied snapshot is " + details.group().groupName()
                    + ". The two groups have identical shapes and opposite roles, so passing one where "
                    + "the other belongs would compare or stage the wrong values silently");
        }
    }

    /**
     * {@code EVALUATE} is first-match-wins with {@code WHEN OTHER} last, so this enum's constants appear in
     * the source's order and {@link #firstMatchWinsPosition()} states it explicitly.
     *
     * <p>A boolean triple would permit states the COBOL cannot represent and would let the evaluation order
     * be inverted by accident.
     */
    public enum WriteOutcome {
        /**
         * {@code 88 COULD-NOT-LOCK-FOR-UPDATE} - the read-for-update at
         * {@code app/cbl/COCRDUPC.cbl:1427-1436} did not return {@code DFHRESP(NORMAL)}, so nothing was
         * locked, nothing was compared and nothing was written.
         */
        COULD_NOT_LOCK_FOR_UPDATE(ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                MSG_COULD_NOT_LOCK_FOR_UPDATE, "88 COULD-NOT-LOCK-FOR-UPDATE"),

        /**
         * {@code 88 LOCKED-BUT-UPDATE-FAILED} - the lock was taken and the concurrency check passed, but
         * the rewrite at {@code app/cbl/COCRDUPC.cbl:1477-1483} did not return {@code DFHRESP(NORMAL)}
         * ({@code :1488-1492}).
         */
        LOCKED_BUT_UPDATE_FAILED(ChangeAction.CHANGES_OKAYED_BUT_FAILED,
                MSG_LOCKED_BUT_UPDATE_FAILED, "88 LOCKED-BUT-UPDATE-FAILED"),

        /**
         * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} - the record changed under the screen, so the rewrite
         * was refused ({@code app/cbl/COCRDUPC.cbl:1511}).
         */
        DATA_WAS_CHANGED_BEFORE_UPDATE(ChangeAction.SHOW_DETAILS,
                MSG_DATA_WAS_CHANGED_BEFORE_UPDATE, "88 DATA-WAS-CHANGED-BEFORE-UPDATE"),

        /**
         * {@code WHEN OTHER} - the record was locked, it had not changed, and the full-width rewrite
         * succeeded.
         */
        CHANGES_OKAYED_AND_DONE(ChangeAction.CHANGES_OKAYED_AND_DONE, null, "WHEN OTHER");

        private final String changeActionCode;

        private final String returnMessage;

        private final String cobolCondition;

        WriteOutcome(String changeActionCode, String returnMessage, String cobolCondition) {
            this.changeActionCode = changeActionCode;
            this.returnMessage = returnMessage;
            this.cobolCondition = cobolCondition;
        }

        /**
         * The {@code CCUP-CHANGE-ACTION} character this outcome maps to - {@code "L"}, {@code "F"},
         * {@code "S"} or {@code "C"} ({@code app/cbl/COCRDUPC.cbl:281-290}).
         *
         * @return one character
         */
        public String changeActionCode() {
            return changeActionCode;
        }

        /**
         * The {@code CCUP-CHANGE-ACTION} value as the commarea carries it, so the controller sets the
         * screen state without re-deriving the character.
         *
         * @return the change action
         */
        public ChangeAction changeAction() {
            return ChangeAction.of(changeActionCode);
        }

        /**
         * The {@code WS-RETURN-MSG} literal this outcome's {@code 88}-level names.
         *
         * @return the literal, or empty for {@link #CHANGES_OKAYED_AND_DONE}, which sets no condition
         */
        public Optional<String> returnMessageLiteral() {
            return Optional.ofNullable(returnMessage);
        }

        /**
         * The COBOL condition name, or {@code WHEN OTHER}, that selects this outcome.
         *
         * @return the condition, verbatim
         */
        public String cobolCondition() {
            return cobolCondition;
        }

        /**
         * This outcome's 1-based position in the caller's {@code EVALUATE TRUE}
         * ({@code app/cbl/COCRDUPC.cbl:992-1001}), stated so that a test can assert the order rather than
         * trust it - the order is behaviour, and an {@code EVALUATE} is first-match-wins.
         *
         * @return 1 for the first {@code WHEN}, rising to 4 for {@code WHEN OTHER}
         */
        public int firstMatchWinsPosition() {
            return ordinal() + 1;
        }

        /**
         * Whether reaching this outcome means the record was rewritten.
         *
         * @return {@code true} only for {@link #CHANGES_OKAYED_AND_DONE}
         */
        public boolean isRewritten() {
            return this == CHANGES_OKAYED_AND_DONE;
        }
    }

    /**
     * What {@code 9300-CHECK-CHANGE-IN-REC} concluded ({@code app/cbl/COCRDUPC.cbl:1498-1523}).
     *
     * @param dataWasChanged whether {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} was reached
     *     ({@code :1511}) - and therefore whether the caller must return without rewriting
     * @param oldDetails {@code CCUP-OLD-DETAILS}: refreshed from the stored record when the data changed
     *     ({@code :1512-1517}), and handed back untouched when it had not
     * @param foldedRecord the locked record after {@code INSPECT ... CONVERTING} ({@code :1499-1501}) -
     *     what was actually compared, which is not always what was read
     * @param cvvDiffers whether {@code CARD-CVV-CD} differed from {@code CCUP-OLD-CVV-CD} ({@code :1503})
     * @param embossedNameDiffers whether the folded {@code CARD-EMBOSSED-NAME} differed from
     *     {@code CCUP-OLD-CRDNAME} ({@code :1504})
     * @param expiryYearDiffers whether {@code CARD-EXPIRAION-DATE(1:4)} differed from
     *     {@code CCUP-OLD-EXPYEAR} ({@code :1505})
     * @param expiryMonthDiffers whether {@code CARD-EXPIRAION-DATE(6:2)} differed from
     *     {@code CCUP-OLD-EXPMON} ({@code :1506})
     * @param expiryDayDiffers whether {@code CARD-EXPIRAION-DATE(9:2)} differed from
     *     {@code CCUP-OLD-EXPDAY} ({@code :1507})
     * @param activeStatusDiffers whether {@code CARD-ACTIVE-STATUS} differed from {@code CCUP-OLD-CRDSTCD}
     *     ({@code :1508})
     */
    public record ChangeCheck(boolean dataWasChanged,
                              CardDetails oldDetails,
                              CardRecord foldedRecord,
                              boolean cvvDiffers,
                              boolean embossedNameDiffers,
                              boolean expiryYearDiffers,
                              boolean expiryMonthDiffers,
                              boolean expiryDayDiffers,
                              boolean activeStatusDiffers) {
        public ChangeCheck {
            Objects.requireNonNull(oldDetails, "A check result carries the CCUP-OLD-DETAILS snapshot "
                    + "the caller must repaint from; it is never absent");
            Objects.requireNonNull(foldedRecord, "A check result carries the folded record it actually "
                    + "compared; it is never absent");
            if (oldDetails.group() != DetailGroup.OLD) {
                throw new IllegalArgumentException("9300-CHECK-CHANGE-IN-REC compares against "
                        + DetailGroup.OLD.groupName() + ", so its result carries that group, but the "
                        + "supplied snapshot is " + oldDetails.group().groupName());
            }
            boolean anyDiffers = cvvDiffers || embossedNameDiffers || expiryYearDiffers
                    || expiryMonthDiffers || expiryDayDiffers || activeStatusDiffers;
            if (dataWasChanged != anyDiffers) {
                throw new IllegalArgumentException("app/cbl/COCRDUPC.cbl:1503-1509 joins its six "
                        + "equalities with AND, so the record changed exactly when at least one of them "
                        + "failed; this result claims dataWasChanged=" + dataWasChanged + " with "
                        + (anyDiffers ? "at least one" : "no") + " differing item");
            }
        }

        /**
         * The COBOL names of the items that differed, comma-separated, or a fixed phrase when none did.
         *
         * @return a human-readable list of COBOL field names, never {@code null} and never containing a
         *     field's content
         */
        public String describeDifferences() {
            if (!dataWasChanged) {
                return "none - all six compared items matched";
            }
            StringBuilder differences = new StringBuilder();
            appendIf(differences, cvvDiffers, "CARD-CVV-CD");
            appendIf(differences, embossedNameDiffers, "CARD-EMBOSSED-NAME");
            appendIf(differences, expiryYearDiffers, "CARD-EXPIRAION-DATE(1:4)");
            appendIf(differences, expiryMonthDiffers, "CARD-EXPIRAION-DATE(6:2)");
            appendIf(differences, expiryDayDiffers, "CARD-EXPIRAION-DATE(9:2)");
            appendIf(differences, activeStatusDiffers, "CARD-ACTIVE-STATUS");
            return differences.toString();
        }

        private static void appendIf(StringBuilder differences, boolean differs, String fieldName) {
            if (!differs) {
                return;
            }
            if (differences.length() > 0) {
                differences.append(", ");
            }
            differences.append(fieldName);
        }
    }

    /**
     * What {@code 9200-WRITE-PROCESSING} concluded ({@code app/cbl/COCRDUPC.cbl:1420-1496}), in the shape
     * the caller's {@code EVALUATE TRUE} at {@code :992-1001} consumes.
     *
     * @param outcome which of the four arms was reached
     * @param inputError {@code 88 INPUT-ERROR} ({@code app/cbl/COCRDUPC.cbl:55})
     * @param returnMessage {@code WS-RETURN-MSG PIC X(75)} ({@code :173}) as it stands after the paragraph,
     *     at exactly {@value CardUpdateService#RETURN_MESSAGE_LENGTH} characters
     * @param oldDetails {@code CCUP-OLD-DETAILS}
     * @param cardUpdateRecord the staged {@code CARD-UPDATE-RECORD} ({@code :1461-1475}), present only once
     *     staging happened - so absent on the lock arm and on the changed arm, and present on the failed and
     *     succeeded arms
     * @param cardUpdateCvvCdImage the verbatim three characters the CVV {@code REDEFINES} round-trip
     *     produced at {@code :1464}, present whenever {@code cardUpdateRecord} is
     * @param failedOperation {@code ERROR-OPNAME} ({@code :1407}) - {@code "READ"} or {@code "REWRITE"} -
     *     so the controller can compose {@code WS-FILE-ERROR-MESSAGE} without re-deriving which operation
     *     failed
     * @param resp {@code WS-RESP-CD} ({@code :41}) from the last operation performed, raw and unmapped
     * @param resp2 {@code WS-REAS-CD} ({@code :43}) from the last operation performed, raw and unmapped
     */
    public record WriteResult(WriteOutcome outcome,
                              boolean inputError,
                              String returnMessage,
                              CardDetails oldDetails,
                              Optional<CardUpdateRecord> cardUpdateRecord,
                              Optional<String> cardUpdateCvvCdImage,
                              Optional<String> failedOperation,
                              int resp,
                              int resp2) {
        public WriteResult {
            Objects.requireNonNull(outcome, "A write result carries the arm it landed on; it is never "
                    + "absent, because app/cbl/COCRDUPC.cbl:992-1001 always selects one");
            Objects.requireNonNull(oldDetails, "A write result carries CCUP-OLD-DETAILS - refreshed on "
                    + "the changed arm and unchanged elsewhere - so the screen can be repainted");
            Objects.requireNonNull(cardUpdateRecord, "Use Optional.empty() for an arm that staged no "
                    + "record, never null");
            Objects.requireNonNull(cardUpdateCvvCdImage, "Use Optional.empty() for an arm that staged "
                    + "no record, never null");
            Objects.requireNonNull(failedOperation, "Use Optional.empty() for an arm where no operation "
                    + "failed, never null");
            if (oldDetails.group() != DetailGroup.OLD) {
                throw new IllegalArgumentException("A write result carries "
                        + DetailGroup.OLD.groupName() + ", but the supplied snapshot is "
                        + oldDetails.group().groupName());
            }
            boolean lockArm = outcome == WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE;
            if (inputError != lockArm) {
                throw new IllegalArgumentException("app/cbl/COCRDUPC.cbl:1444 is the only "
                        + "SET INPUT-ERROR TO TRUE in 9200-WRITE-PROCESSING and it sits on the "
                        + "could-not-lock arm, so inputError holds exactly there; this result claims "
                        + "inputError=" + inputError + " on the " + outcome.cobolCondition() + " arm");
            }
            if (cardUpdateRecord.isPresent() != cardUpdateCvvCdImage.isPresent()) {
                throw new IllegalArgumentException("The staged record and its verbatim CVV image are "
                        + "produced by the same statements at app/cbl/COCRDUPC.cbl:1461-1475, so they "
                        + "are present together or absent together");
            }
            returnMessage = atReturnMessageWidth(returnMessage);
        }

        /**
         * The {@code CCUP-CHANGE-ACTION} value the caller's {@code EVALUATE} arm sets for this outcome
         * ({@code app/cbl/COCRDUPC.cbl:992-1001}).
         *
         * @return {@code 'L'}, {@code 'F'}, {@code 'S'} or {@code 'C'} as a change action
         */
        public ChangeAction changeAction() {
            return outcome.changeAction();
        }

        public boolean isRewritten() {
            return outcome.isRewritten();
        }

        /**
         * Whether {@code 9300-CHECK-CHANGE-IN-REC} refused the update, in which case {@link #oldDetails()}
         * is the refreshed snapshot the screen must be repainted from.
         *
         * @return {@code true} only for {@link WriteOutcome#DATA_WAS_CHANGED_BEFORE_UPDATE}
         */
        public boolean isDataWasChangedBeforeUpdate() {
            return outcome == WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE;
        }

        /**
         * A diagnostic rendering that withholds the staged card verification value.
         *
         * <p>{@link #cardUpdateCvvCdImage()} is untouched: the parity surface is the accessor and the byte
         * image, and this rendering has no COBOL counterpart.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "WriteResult[outcome=" + outcome
                    + ", inputError=" + inputError
                    + ", returnMessage=" + DiagnosticText.singleLine(returnMessage)
                    + ", oldDetails=" + oldDetails
                    + ", cardUpdateRecord=" + cardUpdateRecord
                    + ", cardUpdateCvvCdImage="
                    + cardUpdateCvvCdImage.map(DiagnosticText::omitted).orElse(DiagnosticText.ABSENT)
                    + ", failedOperation=" + failedOperation.map(DiagnosticText::singleLine)
                            .orElse(DiagnosticText.ABSENT)
                    + ", resp=" + resp + ", resp2=" + resp2 + ']';
        }
    }
}
