package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.NumericIntrinsics;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

/**
 * The write path of {@code app/cbl/COACTUPC.cbl} - the two paragraphs that lock the account and customer
 * records, decide whether anybody changed either of them while the screen was being filled in, and rewrite
 * both.
 *
 * <p>The account identifier, the customer identifier and {@code ACCT-ADDR-ZIP} are deliberately not
 * compared, because the COBOL does not compare them.
 */
@Service
public class AccountUpdateService {
    private static final Log LOG = LogFactory.getLog(AccountUpdateService.class);

    /**
     * {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '} ({@code app/cbl/COACTUPC.cbl:573-574}) - the CICS
     * file name both the read-for-update at {@code :3895} and the rewrite at {@code :4066} name.
     */
    public static final String ACCT_CICS_FILE_NAME = "ACCTDAT ";

    /**
     * {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '} ({@code app/cbl/COACTUPC.cbl:575-576}) - the CICS
     * file name the read-for-update at {@code :3923} and the rewrite at {@code :4086} name.
     */
    public static final String CUST_CICS_FILE_NAME = "CUSTDAT ";

    /**
     * {@code LENGTH OF WS-CARD-RID-ACCT-ID-X} ({@code app/cbl/COACTUPC.cbl:382-383,3898}): the eleven
     * characters of the {@code PIC X(11)} {@code REDEFINES} view over {@code WS-CARD-RID-ACCT-ID PIC 9(11)}
     * at {@code :381}.
     */
    public static final int ACCT_KEY_LENGTH = AccountRepository.KEY_LENGTH;

    /**
     * {@code LENGTH OF WS-CARD-RID-CUST-ID-X} ({@code app/cbl/COACTUPC.cbl:379-380,3926}): the nine
     * characters of the {@code PIC X(09)} {@code REDEFINES} view over {@code WS-CARD-RID-CUST-ID PIC 9(09)}
     * at {@code :378}, and the width of the {@code CUSTDAT} primary key.
     */
    public static final int CUST_KEY_LENGTH = CustomerRepository.KEY_LENGTH;

    /**
     * {@code LENGTH OF ACCT-UPDATE-RECORD}: {@value}, the length the rewrite at
     * {@code app/cbl/COACTUPC.cbl:4069} states, and the same 300 that {@code app/cpy/CVACT01Y.cpy}
     * declares.
     */
    public static final int ACCT_UPDATE_RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    /**
     * {@code LENGTH OF CUST-UPDATE-RECORD}: {@value}, the length the rewrite at
     * {@code app/cbl/COACTUPC.cbl:4089} states, and the same 500 that {@code app/cpy/CVCUS01Y.cpy}
     * declares.
     */
    public static final int CUST_UPDATE_RECORD_LENGTH = CustomerRecord.RECORD_LENGTH;

    /**
     * The declared width of {@code WS-RETURN-MSG PIC X(75)} ({@code app/cbl/COACTUPC.cbl:479}) - the single
     * field that carries every outcome of this write path, and the field whose all-spaces state
     * {@code 88 WS-RETURN-MSG-OFF} at {@code :480} names.
     */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} ({@code app/cbl/COACTUPC.cbl:480}) rendered as the value it
     * names: {@value #RETURN_MESSAGE_LENGTH} spaces.
     */
    public static final String RETURN_MESSAGE_OFF = " ".repeat(RETURN_MESSAGE_LENGTH);

    /**
     * {@code 88 COULD-NOT-LOCK-ACCT-FOR-UPDATE} ({@code app/cbl/COACTUPC.cbl:517-518}), byte-exact.
     */
    public static final String MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE =
            "Could not lock account record for update";

    /**
     * {@code 88 COULD-NOT-LOCK-CUST-FOR-UPDATE} ({@code app/cbl/COACTUPC.cbl:519-520}), byte-exact.
     */
    public static final String MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE =
            "Could not lock customer record for update";

    /**
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} ({@code app/cbl/COACTUPC.cbl:521-522}), byte-exact.
     */
    public static final String MSG_DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /**
     * {@code 88 LOCKED-BUT-UPDATE-FAILED} ({@code app/cbl/COACTUPC.cbl:523-524}), byte-exact.
     */
    public static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    /**
     * The operation name a diagnostic reports for {@code EXEC CICS READ ... UPDATE}, matching
     * {@code ERROR-OPNAME} in the {@code WS-FILE-ERROR-MESSAGE} group at
     * {@code app/cbl/COACTUPC.cbl:392-395}.
     */
    public static final String READ_OPERATION_NAME = "READ";

    /**
     * The operation name a diagnostic reports for {@code EXEC CICS REWRITE}, matching {@code ERROR-OPNAME}
     * at {@code app/cbl/COACTUPC.cbl:392-395}.
     */
    public static final String REWRITE_OPERATION_NAME = "REWRITE";

    /**
     * {@code p} of {@code PIC S9(10)V99}: the ten digit positions left of the implied decimal point, shared
     * by all five monetary items of {@code CVACT01Y}, of {@code ACUP-OLD-ACCT-DATA}
     * ({@code app/cbl/COACTUPC.cbl:675-706}), of {@code ACUP-NEW-ACCT-DATA} ({@code :768-784}) and of
     * {@code ACCT-UPDATE-RECORD} ({@code :424-431}).
     */
    public static final int MONETARY_INTEGER_DIGITS = AccountRecord.MONETARY_INTEGER_DIGITS;

    /**
     * The stored width of a {@code PIC S9(10)V99} zoned {@code DISPLAY} item: twelve characters, the sign
     * carried as an overpunch on the last of them.
     */
    public static final int MONETARY_IMAGE_LENGTH =
            MONETARY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE;

    /**
     * The declared width of {@code ACUP-OLD-OPEN-YEAR} and every other year part
     * ({@code app/cbl/COACTUPC.cbl:687}), and of the {@code (1:4)} slice of a stored ten-byte date.
     */
    public static final int DATE_YEAR_LENGTH = AccountRecord.YEAR_LENGTH;

    /**
     * The declared width of {@code ACUP-OLD-OPEN-MON}, {@code ACUP-OLD-OPEN-DAY} and every other month or
     * day part ({@code app/cbl/COACTUPC.cbl:688-689}), and of the {@code (6:2)} and {@code (9:2)} slices of
     * a stored ten-byte date.
     */
    public static final int DATE_PART_LENGTH = AccountRecord.MONTH_LENGTH;

    /**
     * The declared width of {@code ACUP-OLD-OPEN-DATE PIC X(08)} ({@code app/cbl/COACTUPC.cbl:685}) and of
     * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} ({@code :747}): the separator-free {@code YYYYMMDD}
     * form the screen snapshot holds, as against the ten-character {@code YYYY-MM-DD} form the records
     * hold.
     */
    public static final int SNAPSHOT_DATE_LENGTH = DATE_YEAR_LENGTH + 2 * DATE_PART_LENGTH;

    /**
     * The declared width of a stored date - {@code ACCT-OPEN-DATE PIC X(10)} and its two siblings in
     * {@code app/cpy/CVACT01Y.cpy}, and {@code CUST-DOB-YYYY-MM-DD PIC X(10)} in
     * {@code app/cpy/CVCUS01Y.cpy} - and the width the three {@code STRING ... DELIMITED BY SIZE}
     * statements at {@code app/cbl/COACTUPC.cbl:3976-3999} fill exactly.
     */
    public static final int STORED_DATE_LENGTH = AccountRecord.ACCT_OPEN_DATE_LENGTH;

    /**
     * The separator the three date {@code STRING} statements interleave
     * ({@code app/cbl/COACTUPC.cbl:3977,3979}), and the character at positions 5 and 8 of every stored date
     * - the two positions {@code 9700} never compares.
     */
    public static final String DATE_SEPARATOR = "-";

    /**
     * {@code ACCT-UPDATE-ID PIC 9(11)} at {@code app/cbl/COACTUPC.cbl:422} - offset 0, width 11.
     */
    public static final int ACCT_UPDATE_ID_OFFSET = 0;

    /**
     * {@code ACCT-UPDATE-ACTIVE-STATUS PIC X(01)} at {@code :423} - offset 11, width 1.
     */
    public static final int ACCT_UPDATE_ACTIVE_STATUS_OFFSET =
            ACCT_UPDATE_ID_OFFSET + AccountRecord.ACCT_ID_LENGTH;

    /**
     * {@code ACCT-UPDATE-CURR-BAL PIC S9(10)V99} at {@code :424} - offset 12, width 12.
     */
    public static final int ACCT_UPDATE_CURR_BAL_OFFSET = ACCT_UPDATE_ACTIVE_STATUS_OFFSET
            + AccountRecord.ACCT_ACTIVE_STATUS_LENGTH;

    /**
     * {@code ACCT-UPDATE-CREDIT-LIMIT PIC S9(10)V99} at {@code :425} - offset 24, width 12.
     */
    public static final int ACCT_UPDATE_CREDIT_LIMIT_OFFSET =
            ACCT_UPDATE_CURR_BAL_OFFSET + MONETARY_IMAGE_LENGTH;

    /**
     * {@code ACCT-UPDATE-CASH-CREDIT-LIMIT PIC S9(10)V99} at {@code :426} - offset 36, width 12.
     */
    public static final int ACCT_UPDATE_CASH_CREDIT_LIMIT_OFFSET =
            ACCT_UPDATE_CREDIT_LIMIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /**
     * {@code ACCT-UPDATE-OPEN-DATE PIC X(10)} at {@code :427} - offset 48, width 10.
     */
    public static final int ACCT_UPDATE_OPEN_DATE_OFFSET =
            ACCT_UPDATE_CASH_CREDIT_LIMIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /**
     * {@code ACCT-UPDATE-EXPIRAION-DATE PIC X(10)} at {@code :428} - offset 58, width 10.
     */
    public static final int ACCT_UPDATE_EXPIRAION_DATE_OFFSET =
            ACCT_UPDATE_OPEN_DATE_OFFSET + STORED_DATE_LENGTH;

    /**
     * {@code ACCT-UPDATE-REISSUE-DATE PIC X(10)} at {@code :429} - offset 68, width 10.
     */
    public static final int ACCT_UPDATE_REISSUE_DATE_OFFSET =
            ACCT_UPDATE_EXPIRAION_DATE_OFFSET + STORED_DATE_LENGTH;

    /**
     * {@code ACCT-UPDATE-CURR-CYC-CREDIT PIC S9(10)V99} at {@code :430} - offset 78, width 12.
     */
    public static final int ACCT_UPDATE_CURR_CYC_CREDIT_OFFSET =
            ACCT_UPDATE_REISSUE_DATE_OFFSET + STORED_DATE_LENGTH;

    /**
     * {@code ACCT-UPDATE-CURR-CYC-DEBIT PIC S9(10)V99} at {@code :431} - offset 90, width 12.
     */
    public static final int ACCT_UPDATE_CURR_CYC_DEBIT_OFFSET =
            ACCT_UPDATE_CURR_CYC_CREDIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /**
     * {@code ACCT-UPDATE-GROUP-ID PIC X(10)} at {@code :432} - offset 102, width 10.
     */
    public static final int ACCT_UPDATE_GROUP_ID_OFFSET =
            ACCT_UPDATE_CURR_CYC_DEBIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /**
     * The offset of {@code FILLER PIC X(188)} at {@code :433} - offset 112.
     */
    public static final int ACCT_UPDATE_FILLER_OFFSET =
            ACCT_UPDATE_GROUP_ID_OFFSET + AccountRecord.ACCT_GROUP_ID_LENGTH;

    /**
     * The declared width of {@code FILLER PIC X(188)} at {@code app/cbl/COACTUPC.cbl:433} - {@value}, ten
     * bytes wider than {@code CVACT01Y}'s {@code FILLER PIC X(178)} because {@code ACCT-UPDATE-RECORD}
     * carries no {@code ACCT-ADDR-ZIP}.
     */
    public static final int ACCT_UPDATE_FILLER_LENGTH =
            ACCT_UPDATE_RECORD_LENGTH - ACCT_UPDATE_FILLER_OFFSET;

    /**
     * The value {@link #testNumvalC(String)} returns for an argument that conforms - the same {@code 0} the
     * five {@code IF FUNCTION TEST-NUMVAL-C(...) = 0} guards at
     * {@code app/cbl/COACTUPC.cbl:1078,1092,1106,1120,1134} test for.
     */
    public static final int NUMVAL_CONFORMS = NumericIntrinsics.CONFORMS;

    /**
     * The declared width of the five monetary screen fields - {@code ACRDLIMI}, {@code ACSHLIMI},
     * {@code ACURBALI}, {@code ACRCYCRI} and {@code ACRCYDBI}, all {@code PIC X(15)} in
     * {@code app/cpy-bms/COACTUP.CPY:90,114,138,144,156} - and of the five
     * {@code ALPHA-VARS-FOR-DATA-EDITING} staging items at {@code app/cbl/COACTUPC.cbl:412-416}.
     */
    public static final int SCREEN_MONETARY_LENGTH = 15;

    /**
     * {@code LOW-VALUES} at the width of a monetary screen field: {@value #SCREEN_MONETARY_LENGTH} copies
     * of the lowest character in the collating sequence.
     */
    public static final String LOW_VALUES_IMAGE =
            String.valueOf('\u0000').repeat(SCREEN_MONETARY_LENGTH);

    /**
     * The character {@code 1100-RECEIVE-MAP} treats as "not supplied" alongside spaces
     * ({@code app/cbl/COACTUPC.cbl:1073,1087,1101,1115,1129}).
     */
    public static final String NOT_SUPPLIED_MARKER = "*";

    private static final String PHONE_OPEN = "(";

    private static final String PHONE_CLOSE = ")";

    private static final String PHONE_HYPHEN = "-";

    private static final char SPACE = ' ';

    private static final char DECIMAL_POINT = '.';

    private static final char DIGIT_SEPARATOR = ',';

    private static final char CURRENCY_SIGN = '$';

    private static final char PLUS_SIGN = '+';

    private static final char MINUS_SIGN = '-';

    private static final String CREDIT_INDICATOR = "CR";

    private static final String DEBIT_INDICATOR = "DB";

    private static final int ONE = 1;

    static {
        if (ACCT_UPDATE_GROUP_ID_OFFSET != AccountRecord.ACCT_ADDR_ZIP_OFFSET) {
            throw new AssertionError("app/cbl/COACTUPC.cbl:418-433 declares no ACCT-ADDR-ZIP item, so "
                    + "ACCT-UPDATE-GROUP-ID lands at offset " + AccountRecord.ACCT_ADDR_ZIP_OFFSET
                    + " - exactly where app/cpy/CVACT01Y.cpy places ACCT-ADDR-ZIP - and this build "
                    + "computes " + ACCT_UPDATE_GROUP_ID_OFFSET + ". The overlay is a legacy defect "
                    + "that is reproduced deliberately (practice B5); it must not be repaired.");
        }
        if (ACCT_UPDATE_FILLER_OFFSET != AccountRecord.ACCT_GROUP_ID_OFFSET) {
            throw new AssertionError("ACCT-UPDATE-RECORD's FILLER must begin where CVACT01Y places "
                    + "ACCT-GROUP-ID, at offset " + AccountRecord.ACCT_GROUP_ID_OFFSET
                    + ", because the rewrite blanks that span; this build computes "
                    + ACCT_UPDATE_FILLER_OFFSET);
        }
    }

    private final AccountRepository accountRepository;

    private final CustomerRepository customerRepository;

    private static final String UNIT_OF_WORK_DESCRIPTION =
            "9600-WRITE-PROCESSING (app/cbl/COACTUPC.cbl:3889-4106): lock ACCTDAT and CUSTDAT, compare "
                    + "both against the painted screen, and rewrite both at full declared width";

    private final DatasetUnitOfWork unitOfWork;

    public AccountUpdateService(AccountRepository accountRepository,
                               CustomerRepository customerRepository,
                               DatasetUnitOfWork unitOfWork) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "An ACCTDAT repository is required: 9600-WRITE-PROCESSING both reads the account "
                        + "record for update at app/cbl/COACTUPC.cbl:3894 and rewrites it at :4065");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "A CUSTDAT repository is required: 9600-WRITE-PROCESSING both reads the customer "
                        + "record for update at app/cbl/COACTUPC.cbl:3922 and rewrites it at :4085");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A unit-of-work boundary is required: a "
                + "CICS task always has one, and the two READ ... UPDATE locks this paragraph takes are "
                + "worthless outside it - AccountRepository.readForUpdate refuses to issue FOR UPDATE "
                + "when no transaction is open, so without this the write path cannot run at all");
    }

    private void requireDatasetCodePage(FixedWidthCodec codec) {
        Charset acctCharset = accountRepository.datasetCharset();
        Charset custCharset = customerRepository.datasetCharset();
        if (!codec.charset().equals(acctCharset) || !codec.charset().equals(custCharset)) {
            throw new IllegalArgumentException("The codec passed to 9600-WRITE-PROCESSING carries code "
                    + "page " + codec.charset().name() + ", but the account master is stored in "
                    + acctCharset.name() + " and the customer master in " + custCharset.name() + ". "
                    + "The staged 300-byte and 500-byte images are decoded with this codec and written "
                    + "verbatim, so a mismatch would corrupt every byte outside the invariant range "
                    + "instead of failing; the caller must pass the active dataset codec.");
        }
    }

    public WriteResult writeProcessing(String ccAcctId,
                                      NavigationContext navigationContext,
                                      AccountUpdateDetails oldDetails,
                                      AccountUpdateDetails newDetails,
                                      String returnMessage,
                                      FixedWidthCodec codec) {
        Objects.requireNonNull(navigationContext, "The COCOM01Y commarea is required: "
                + "9600-WRITE-PROCESSING reads CDEMO-CUST-ID from it at app/cbl/COACTUPC.cbl:3920 to "
                + "build the CUSTDAT record identification field");
        Objects.requireNonNull(codec, "A codec is required: every move in this paragraph is a COBOL "
                + "MOVE with a declared width, and the codec owns the pad and truncate rules");
        requireGroup(oldDetails, DetailGroup.OLD, "ACUP-OLD-DETAILS", "669-756");
        requireGroup(newDetails, DetailGroup.NEW, "ACUP-NEW-DETAILS", "757-855");
        requireDatasetCodePage(codec);

        try {
            return unitOfWork.execute(UNIT_OF_WORK_DESCRIPTION, () -> {
                WriteResult result = writeProcessingUnderLock(ccAcctId, navigationContext, oldDetails,
                        newDetails, returnMessage, codec);
                if (result.syncpointRollbackRequested()) {
                    // That is a rollback the task continues past, which is exactly what EXEC CICS SYNCPOINT
                    // ROLLBACK is.
                    throw new SyncpointRollback(result);
                }
                return result;
            });
        } catch (SyncpointRollback rolledBack) {
            return rolledBack.result();
        }
    }

    private WriteResult writeProcessingUnderLock(String ccAcctId,
                                                 NavigationContext navigationContext,
                                                 AccountUpdateDetails oldDetails,
                                                 AccountUpdateDetails newDetails,
                                                 String returnMessage,
                                                 FixedWidthCodec codec) {
        String pendingMessage = atReturnMessageWidth(returnMessage);

        String accountRid = acctRidImage(ccAcctId, codec);

        AccountRepository.ReadResult accountRead = accountRepository.readForUpdate(accountRid);

        if (!accountRead.isFound()) {
            boolean messageOff = isReturnMessageOff(pendingMessage);
            String message = messageOff
                    ? atReturnMessageWidth(MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE)
                    : pendingMessage;
            LOG.warn("A read-for-update of " + ACCT_CICS_FILE_NAME.trim() + " did not take the lock: "
                    + "FILE STATUS " + accountRead.status() + ". The update is abandoned, the "
                    + CUST_CICS_FILE_NAME.trim() + " record is never read, and nothing has been "
                    + "changed. The return message was " + (messageOff
                            ? "off, so the could-not-lock-account message is now set"
                            : "already set by an earlier paragraph, so it is left as it stands"));
            return new WriteResult(WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE, true, false, message,
                    accountRead.status(), accountRead.cicsResp(), Optional.of(READ_OPERATION_NAME),
                    Optional.of(ACCT_CICS_FILE_NAME), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        AccountRecord lockedAccount = accountRead.account().orElseThrow(
                () -> new IllegalStateException("A found ACCTDAT read carries the decoded record; "
                        + "AccountRepository.ReadResult enforces that at construction, so reaching "
                        + "here means the contract was bypassed"));

        String customerRid = custRidImage(navigationContext.custId(), codec);

        CustomerRepository.ReadResult customerRead = customerRepository.readForUpdate(customerRid);

        if (!customerRead.isFound()) {
            boolean messageOff = isReturnMessageOff(pendingMessage);
            String message = messageOff
                    ? atReturnMessageWidth(MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE)
                    : pendingMessage;
            LOG.warn("A read-for-update of " + CUST_CICS_FILE_NAME.trim() + " did not take the lock: "
                    + "FILE STATUS " + customerRead.status() + ". The " + ACCT_CICS_FILE_NAME.trim()
                    + " record is already locked but nothing has been written. The return message was "
                    + (messageOff
                            ? "off, so the could-not-lock-customer message is now set"
                            : "already set by an earlier paragraph, so it is left as it stands")
                    + ". Note that app/cbl/COACTUPC.cbl:2603-2614 has no EVALUATE arm for this "
                    + "message, so the caller reports it to the operator as success - a legacy defect "
                    + "that is reproduced, not repaired.");
            return new WriteResult(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE, true, false, message,
                    customerRead.status(), customerRead.cicsResp(), Optional.of(READ_OPERATION_NAME),
                    Optional.of(CUST_CICS_FILE_NAME), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        CustomerRecord lockedCustomer = customerRead.customer().orElseThrow(
                () -> new IllegalStateException("A found CUSTDAT read carries the decoded record; "
                        + "CustomerRepository.ReadResult enforces that at construction, so reaching "
                        + "here means the contract was bypassed"));

        // The paragraph's own GO TO at :4147 and :4190 leaves the PERFORM range and lands on
        // 9600-WRITE-PROCESSING-EXIT, so the explicit IF at :3950-3952 is deliberately redundant with it.
        ChangeCheck check = checkChangeInRec(lockedAccount, lockedCustomer, oldDetails, codec);
        if (check.dataWasChanged()) {
            LOG.info("A record changed after the screen was painted, so the update is refused and "
                    + "nothing has been written. " + check.describeDifferences());
            return new WriteResult(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE, false, false,
                    atReturnMessageWidth(MSG_DATA_WAS_CHANGED_BEFORE_UPDATE), FileStatus.OK,
                    OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(check));
        }

        String accountImage = stageAccountUpdateImage(newDetails, codec);
        String customerImage = stageCustomerUpdateImage(newDetails, codec);

        AccountRepository.WriteResult accountWritten =
                accountRepository.rewrite(AccountRecord.decode(accountImage, codec.charset()));

        if (!accountWritten.isWritten()) {
            LOG.error("A rewrite of the locked " + ACCT_CICS_FILE_NAME.trim() + " record failed: "
                    + "FILE STATUS " + accountWritten.status() + ". The locks were taken and the "
                    + "concurrency check passed, so this is a backend failure rather than a stale "
                    + "screen. app/cbl/COACTUPC.cbl:4076-4081 issues no SYNCPOINT ROLLBACK here, "
                    + "because nothing had yet been written when it failed.");
            return new WriteResult(WriteOutcome.LOCKED_BUT_UPDATE_FAILED, false, false,
                    atReturnMessageWidth(MSG_LOCKED_BUT_UPDATE_FAILED), accountWritten.status(),
                    accountWritten.cicsResp(), Optional.of(REWRITE_OPERATION_NAME),
                    Optional.of(ACCT_CICS_FILE_NAME), Optional.of(accountImage),
                    Optional.of(customerImage), Optional.of(check));
        }

        // Step 11, :4085-4091. The customer rewrite, also at full declared length - and issued against the
        // row step 5 locked rather than against the key inside the staged image.
        //
        // The two keys have different provenance and that is the whole reason this goes through the hold.
        // The lock was taken with CDEMO-CUST-ID out of the communication area (:3919); CUST-UPDATE-ID comes
        // from ACUP-NEW-CUST-ID (:4009), which :1234-1240 moved out of the screen's ACSTNUMI. On a 3270
        // they cannot differ - :3531 moves DFHBMPRF onto ACSTNUMA, so the field is protected and
        // modified-data-tagged and the terminal can only return what the program painted, which is why
        // :1222 calls the customer identifier "actually not editable" - but a REST payload carries no such
        // guarantee. EXEC CICS REWRITE has no RIDFLD and can only replace the held record, so the held
        // image is what names the row; a staged image whose key disagrees with it is refused with
        // DFHRESP(INVREQ) and no statement is issued, which lands on step 12 below exactly as any other
        // refused customer rewrite does - including its SYNCPOINT ROLLBACK of the account rewrite.
        CustomerRepository.WriteResult customerWritten = customerRepository.rewriteHeld(
                customerRead.requireStoredImage(),
                CustomerRecord.decode(customerImage, codec.charset()));

        if (!customerWritten.isWritten()) {
            LOG.error("A rewrite of the locked " + CUST_CICS_FILE_NAME.trim() + " record failed: "
                    + "FILE STATUS " + customerWritten.status() + ". The " + ACCT_CICS_FILE_NAME.trim()
                    + " rewrite had already succeeded, so app/cbl/COACTUPC.cbl:4099-4101 issues "
                    + "EXEC CICS SYNCPOINT ROLLBACK; this result requests that rollback and the unit "
                    + "of work must perform it, or the two datasets are left disagreeing.");
            return new WriteResult(WriteOutcome.LOCKED_BUT_UPDATE_FAILED, false, true,
                    atReturnMessageWidth(MSG_LOCKED_BUT_UPDATE_FAILED), customerWritten.status(),
                    customerWritten.cicsResp(), Optional.of(REWRITE_OPERATION_NAME),
                    Optional.of(CUST_CICS_FILE_NAME), Optional.of(accountImage),
                    Optional.of(customerImage), Optional.of(check));
        }

        LOG.info("An " + ACCT_CICS_FILE_NAME.trim() + " record was rewritten at its full "
                + ACCT_UPDATE_RECORD_LENGTH + " bytes and a " + CUST_CICS_FILE_NAME.trim()
                + " record at its full " + CUST_UPDATE_RECORD_LENGTH + " bytes.");
        return new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false, pendingMessage,
                customerWritten.status(), customerWritten.cicsResp(), Optional.empty(),
                Optional.empty(), Optional.of(accountImage), Optional.of(customerImage),
                Optional.of(check));
    }

    /**
     * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4109-4194}): decides whether the two
     * records that were just locked still match the snapshot the screen was painted from.
     *
     * <p>{@code ACCT-ACTIVE-STATUS} exactly, {@code PIC X(01)} against {@code PIC X(01)}; the five monetary
     * items - balance, credit limit, cash credit limit, cycle credit, cycle debit - as
     * {@code PIC S9(10)V99} against the {@code ACUP-OLD-...-N} redefinitions of the {@code PIC X(12)}
     * snapshot spans.
     *
     * @param account {@code ACCOUNT-RECORD} as read under the lock at {@code app/cbl/COACTUPC.cbl:3899};
     *     must not be {@code null}
     * @param customer {@code CUSTOMER-RECORD} as read under the lock at {@code :3927}; must not be
     *     {@code null}
     * @param oldDetails {@code ACUP-OLD-DETAILS} ({@code :669-756}) - the snapshot the screen was painted
     *     from
     * @param codec the shared fixed-width codec that performs the COBOL move
     * @return what the comparison concluded, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code oldDetails} is not the {@link DetailGroup#OLD} group
     */
    public ChangeCheck checkChangeInRec(AccountRecord account,
                                       CustomerRecord customer,
                                       AccountUpdateDetails oldDetails,
                                       FixedWidthCodec codec) {
        Objects.requireNonNull(account, "The ACCOUNT-RECORD read under the lock is required: "
                + "9700-CHECK-CHANGE-IN-REC compares it against the snapshot the screen was painted "
                + "from, and that comparison is the whole of the concurrency control");
        Objects.requireNonNull(customer, "The CUSTOMER-RECORD read under the lock is required: "
                + "block 2 of 9700-CHECK-CHANGE-IN-REC compares nineteen of its items");
        Objects.requireNonNull(codec, "A codec is required: CUST-SSN and CUST-FICO-CREDIT-SCORE are "
                + "PIC 9 items while their snapshot counterparts are PIC X redefinitions, so one form "
                + "has to be rendered into the other's");
        requireGroup(oldDetails, DetailGroup.OLD, "ACUP-OLD-DETAILS", "669-756");

        Set<ComparedItem> accountDifferences = compareAccountMaster(account, oldDetails.acctData());
        if (!accountDifferences.isEmpty()) {
            return ChangeCheck.changed(Block.ACCOUNT_MASTER, accountDifferences);
        }

        Set<ComparedItem> customerDifferences = compareCustomer(customer, oldDetails.custData(), codec);
        if (!customerDifferences.isEmpty()) {
            return ChangeCheck.changed(Block.CUSTOMER, customerDifferences);
        }

        return ChangeCheck.unchanged();
    }

    private static Set<ComparedItem> compareAccountMaster(AccountRecord account, AccountData old) {
        Set<ComparedItem> differences = EnumSet.noneOf(ComparedItem.class);

        addIfDiffers(differences, ComparedItem.ACCT_ACTIVE_STATUS,
                account.getAcctActiveStatus().equals(old.activeStatus()));

        addIfDiffers(differences, ComparedItem.ACCT_CURR_BAL,
                account.getAcctCurrBal().compareTo(old.currBal()) == 0);
        addIfDiffers(differences, ComparedItem.ACCT_CREDIT_LIMIT,
                account.getAcctCreditLimit().compareTo(old.creditLimit()) == 0);
        addIfDiffers(differences, ComparedItem.ACCT_CASH_CREDIT_LIMIT,
                account.getAcctCashCreditLimit().compareTo(old.cashCreditLimit()) == 0);
        addIfDiffers(differences, ComparedItem.ACCT_CURR_CYC_CREDIT,
                account.getAcctCurrCycCredit().compareTo(old.currCycCredit()) == 0);
        addIfDiffers(differences, ComparedItem.ACCT_CURR_CYC_DEBIT,
                account.getAcctCurrCycDebit().compareTo(old.currCycDebit()) == 0);

        addIfDiffers(differences, ComparedItem.ACCT_OPEN_DATE_YEAR,
                account.getAcctOpenDateYear().equals(old.openYear()));
        addIfDiffers(differences, ComparedItem.ACCT_OPEN_DATE_MONTH,
                account.getAcctOpenDateMonth().equals(old.openMon()));
        addIfDiffers(differences, ComparedItem.ACCT_OPEN_DATE_DAY,
                account.getAcctOpenDateDay().equals(old.openDay()));

        addIfDiffers(differences, ComparedItem.ACCT_EXPIRAION_DATE_YEAR,
                account.getAcctExpiraionDateYear().equals(old.expYear()));
        addIfDiffers(differences, ComparedItem.ACCT_EXPIRAION_DATE_MONTH,
                account.getAcctExpiraionDateMonth().equals(old.expMon()));
        addIfDiffers(differences, ComparedItem.ACCT_EXPIRAION_DATE_DAY,
                account.getAcctExpiraionDateDay().equals(old.expDay()));

        addIfDiffers(differences, ComparedItem.ACCT_REISSUE_DATE_YEAR,
                account.getAcctReissueDateYear().equals(old.reissueYear()));
        addIfDiffers(differences, ComparedItem.ACCT_REISSUE_DATE_MONTH,
                account.getAcctReissueDateMonth().equals(old.reissueMon()));
        addIfDiffers(differences, ComparedItem.ACCT_REISSUE_DATE_DAY,
                account.getAcctReissueDateDay().equals(old.reissueDay()));

        addIfDiffers(differences, ComparedItem.ACCT_GROUP_ID,
                lowerCase(account.getAcctGroupId()).equals(lowerCase(old.groupId())));

        return Collections.unmodifiableSet(differences);
    }

    private static Set<ComparedItem> compareCustomer(CustomerRecord customer, CustomerData old,
                                                     FixedWidthCodec codec) {
        Set<ComparedItem> differences = EnumSet.noneOf(ComparedItem.class);

        addIfDiffers(differences, ComparedItem.CUST_FIRST_NAME,
                upperCase(customer.getCustFirstName()).equals(upperCase(old.firstName())));
        addIfDiffers(differences, ComparedItem.CUST_MIDDLE_NAME,
                upperCase(customer.getCustMiddleName()).equals(upperCase(old.middleName())));
        addIfDiffers(differences, ComparedItem.CUST_LAST_NAME,
                upperCase(customer.getCustLastName()).equals(upperCase(old.lastName())));

        addIfDiffers(differences, ComparedItem.CUST_ADDR_LINE_1,
                upperCase(customer.getCustAddrLine1()).equals(upperCase(old.addrLine1())));
        addIfDiffers(differences, ComparedItem.CUST_ADDR_LINE_2,
                upperCase(customer.getCustAddrLine2()).equals(upperCase(old.addrLine2())));
        addIfDiffers(differences, ComparedItem.CUST_ADDR_LINE_3,
                upperCase(customer.getCustAddrLine3()).equals(upperCase(old.addrLine3())));

        addIfDiffers(differences, ComparedItem.CUST_ADDR_STATE_CD,
                upperCase(customer.getCustAddrStateCd()).equals(upperCase(old.addrStateCd())));
        addIfDiffers(differences, ComparedItem.CUST_ADDR_COUNTRY_CD,
                upperCase(customer.getCustAddrCountryCd()).equals(upperCase(old.addrCountryCd())));

        addIfDiffers(differences, ComparedItem.CUST_ADDR_ZIP,
                customer.getCustAddrZip().equals(old.addrZip()));
        addIfDiffers(differences, ComparedItem.CUST_PHONE_NUM_1,
                customer.getCustPhoneNum1().equals(old.phoneNum1()));
        addIfDiffers(differences, ComparedItem.CUST_PHONE_NUM_2,
                customer.getCustPhoneNum2().equals(old.phoneNum2()));
        addIfDiffers(differences, ComparedItem.CUST_SSN,
                customer.custSsnImage(codec).equals(old.ssnImage(codec)));

        addIfDiffers(differences, ComparedItem.CUST_GOVT_ISSUED_ID,
                upperCase(customer.getCustGovtIssuedId()).equals(upperCase(old.govtIssuedId())));

        addIfDiffers(differences, ComparedItem.CUST_DOB_YEAR,
                custDobSlice(customer, AccountRecord.YEAR_START, DATE_YEAR_LENGTH)
                        .equals(old.dobYear()));
        addIfDiffers(differences, ComparedItem.CUST_DOB_MONTH,
                custDobSlice(customer, AccountRecord.MONTH_START, DATE_PART_LENGTH)
                        .equals(old.dobMon()));
        addIfDiffers(differences, ComparedItem.CUST_DOB_DAY,
                custDobSlice(customer, AccountRecord.DAY_START, DATE_PART_LENGTH)
                        .equals(old.dobDay()));

        addIfDiffers(differences, ComparedItem.CUST_EFT_ACCOUNT_ID,
                customer.getCustEftAccountId().equals(old.eftAccountId()));
        addIfDiffers(differences, ComparedItem.CUST_PRI_CARD_HOLDER_IND,
                customer.getCustPriCardHolderInd().equals(old.priHolderInd()));
        addIfDiffers(differences, ComparedItem.CUST_FICO_CREDIT_SCORE,
                customer.custFicoCreditScoreImage(codec).equals(old.ficoScoreImage(codec)));

        return Collections.unmodifiableSet(differences);
    }

    private static String custDobSlice(CustomerRecord customer, int oneBasedStart, int length) {
        return AccountRecord.referenceModify(customer.getCustDobYyyyMmDd(), oneBasedStart, length);
    }

    private static void addIfDiffers(Set<ComparedItem> differences, ComparedItem item,
                                    boolean matches) {
        if (!matches) {
            differences.add(item);
        }
    }

    /**
     * {@code COMPUTE ACUP-NEW-CREDIT-LIMIT-N = FUNCTION NUMVAL-C(ACRDLIMI OF CACTUPAI)}
     * ({@code app/cbl/COACTUPC.cbl:1079-1080}), together with the {@code '*'} / {@code SPACES} arm at
     * {@code :1073-1075} and the {@code TEST-NUMVAL-C} guard at {@code :1078}.
     *
     * @param screenField {@code ACRDLIMI PIC X(15)} as received; {@code null} is read as the not-supplied
     *     state, which is what an omitted payload field means
     * @param priorValue the {@code -N} span's content before the statement, which a non-conforming value
     *     leaves in place because the {@code ELSE} is {@code CONTINUE}; {@code null} is read as a freshly
     *     initialised span
     * @param codec the codec, which owns the {@code PIC X(15)} receiving rule
     * @return what the statement left behind, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static MonetaryEdit computeCreditLimit(String screenField, BigDecimal priorValue,
                                                 FixedWidthCodec codec) {
        return editSignedNumber(screenField, priorValue, NumvalArgument.MAP_FIELD, codec,
                "ACUP-NEW-CREDIT-LIMIT-N", "1079-1080");
    }

    /**
     * {@code COMPUTE ACUP-NEW-CASH-CREDIT-LIMIT-N = FUNCTION NUMVAL-C(ACSHLIMI OF CACTUPAI)}
     * ({@code app/cbl/COACTUPC.cbl:1093-1094}), with the not-supplied arm at {@code :1087-1089} and the
     * guard at {@code :1092}.
     *
     * @param screenField {@code ACSHLIMI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue the {@code -N} span's content before the statement; {@code null} is scale-2 zero
     * @param codec the codec, which owns the {@code PIC X(15)} receiving rule
     * @return what the statement left behind, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static MonetaryEdit computeCashCreditLimit(String screenField, BigDecimal priorValue,
                                                     FixedWidthCodec codec) {
        return editSignedNumber(screenField, priorValue, NumvalArgument.MAP_FIELD, codec,
                "ACUP-NEW-CASH-CREDIT-LIMIT-N", "1093-1094");
    }

    /**
     * {@code COMPUTE ACUP-NEW-CURR-BAL-N = FUNCTION NUMVAL-C(ACUP-NEW-CURR-BAL-X)}
     * ({@code app/cbl/COACTUPC.cbl:1107-1108}), with the not-supplied arm at {@code :1101-1103} and the
     * guard at {@code :1106}.
     *
     * @param screenField {@code ACURBALI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue the {@code -N} span's content before the statement; {@code null} is scale-2 zero
     * @param codec the codec, which owns the {@code PIC X(15)} receiving rule
     * @return what the statement left behind, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static MonetaryEdit computeCurrBal(String screenField, BigDecimal priorValue,
                                             FixedWidthCodec codec) {
        return editSignedNumber(screenField, priorValue, NumvalArgument.STAGING_COPY, codec,
                "ACUP-NEW-CURR-BAL-N", "1107-1108");
    }

    /**
     * {@code COMPUTE ACUP-NEW-CURR-CYC-CREDIT-N = FUNCTION NUMVAL-C(ACRCYCRI OF CACTUPAI)}
     * ({@code app/cbl/COACTUPC.cbl:1121-1122}), with the not-supplied arm at {@code :1115-1117} and the
     * guard at {@code :1120}.
     *
     * @param screenField {@code ACRCYCRI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue the {@code -N} span's content before the statement; {@code null} is scale-2 zero
     * @param codec the codec, which owns the {@code PIC X(15)} receiving rule
     * @return what the statement left behind, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static MonetaryEdit computeCurrCycCredit(String screenField, BigDecimal priorValue,
                                                   FixedWidthCodec codec) {
        return editSignedNumber(screenField, priorValue, NumvalArgument.MAP_FIELD, codec,
                "ACUP-NEW-CURR-CYC-CREDIT-N", "1121-1122");
    }

    /**
     * {@code COMPUTE ACUP-NEW-CURR-CYC-DEBIT-N = FUNCTION NUMVAL-C(ACUP-NEW-CURR-CYC-DEBIT-X)}
     * ({@code app/cbl/COACTUPC.cbl:1135-1136}), with the not-supplied arm at {@code :1129-1131} and the
     * guard at {@code :1134}.
     *
     * @param screenField {@code ACRCYDBI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue the {@code -N} span's content before the statement; {@code null} is scale-2 zero
     * @param codec the codec, which owns the {@code PIC X(15)} receiving rule
     * @return what the statement left behind, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static MonetaryEdit computeCurrCycDebit(String screenField, BigDecimal priorValue,
                                                  FixedWidthCodec codec) {
        return editSignedNumber(screenField, priorValue, NumvalArgument.STAGING_COPY, codec,
                "ACUP-NEW-CURR-CYC-DEBIT-N", "1135-1136");
    }

    private static MonetaryEdit editSignedNumber(String screenField,
                                                BigDecimal priorValue,
                                                NumvalArgument numvalArgument,
                                                FixedWidthCodec codec,
                                                String cobolReceiver,
                                                String sourceLines) {
        Objects.requireNonNull(codec, "A codec is required: MOVE <screen field> TO ACUP-NEW-...-X is a "
                + "PIC X(" + SCREEN_MONETARY_LENGTH + ") move and the codec owns that rule");

        BigDecimal prior = priorValue == null
                ? CobolDecimal.monetaryZero()
                : CobolDecimal.storeMonetary(priorValue);

        String mapField = codec.movePicX(screenField == null ? "" : screenField,
                SCREEN_MONETARY_LENGTH);

        // The '*' comparison is against a PIC X(15) item, so COBOL space-extends the one-character literal
        // to fifteen before comparing; writing it any other way would make '*' followed by fourteen spaces
        // fail to match, which is exactly the value FieldAttributeSetter puts there.
        boolean notSupplied = mapField.equals(codec.movePicX(NOT_SUPPLIED_MARKER,
                SCREEN_MONETARY_LENGTH))
                || mapField.equals(" ".repeat(SCREEN_MONETARY_LENGTH));

        if (notSupplied) {
            return new MonetaryEdit(cobolReceiver, sourceLines, numvalArgument, LOW_VALUES_IMAGE, true,
                    OptionalInt.empty(), false, prior);
        }

        String stagingCopy = mapField;

        int conformance = testNumvalC(stagingCopy);
        if (conformance != NUMVAL_CONFORMS) {
            return new MonetaryEdit(cobolReceiver, sourceLines, numvalArgument, stagingCopy, false,
                    OptionalInt.of(conformance), false, prior);
        }

        String operand = numvalArgument == NumvalArgument.STAGING_COPY ? stagingCopy : mapField;
        BigDecimal computed = CobolDecimal.storeAtPicture(numvalC(operand), MONETARY_INTEGER_DIGITS,
                CobolDecimal.MONETARY_SCALE);
        return new MonetaryEdit(cobolReceiver, sourceLines, numvalArgument, stagingCopy, false,
                OptionalInt.of(conformance), true, computed);
    }

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} ({@code app/cbl/COACTUPC.cbl:480}): whether
     * {@code WS-RETURN-MSG} is still clear, which is the condition guarding
     * {@code SET COULD-NOT-LOCK-ACCT-FOR-UPDATE TO TRUE} at {@code :3911-3913} and
     * {@code SET COULD-NOT-LOCK-CUST-FOR-UPDATE TO TRUE} at {@code :3939-3941}.
     *
     * <p>A shorter value is space-padded first, because a COBOL {@code PIC X(75)} item is always
     * seventy-five characters wide.
     *
     * @param returnMessage the current content of {@code WS-RETURN-MSG}, or {@code null} for the cleared
     *     state
     * @return {@code true} when every one of the {@value #RETURN_MESSAGE_LENGTH} characters is a space
     */
    public static boolean isReturnMessageOff(String returnMessage) {
        return RETURN_MESSAGE_OFF.equals(atReturnMessageWidth(returnMessage));
    }

    /**
     * Presents a message at the declared width of {@code WS-RETURN-MSG PIC X(75)}, applying the
     * {@code PIC X} receiving rule: right-space-padded when short, right-truncated when long.
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

    /**
     * {@code FUNCTION UPPER-CASE}, as {@code app/cbl/COACTUPC.cbl:4152-4173} and {@code :4180-4181} apply
     * it to sixteen operands - eight fields on each side of eight comparisons.
     *
     * @param value the operand; must not be {@code null}
     * @return {@code value} with its lower-case letters folded up, at exactly {@code value}'s length
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String upperCase(String value) {
        return fold(value, true);
    }

    /**
     * {@code FUNCTION LOWER-CASE}, as {@code app/cbl/COACTUPC.cbl:4144-4145} applies it to
     * {@code ACCT-GROUP-ID} and {@code ACUP-OLD-GROUP-ID}.
     *
     * @param value the operand; must not be {@code null}
     * @return {@code value} with its upper-case letters folded down, at exactly {@code value}'s length
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String lowerCase(String value) {
        return fold(value, false);
    }

    private static String fold(String value, boolean toUpper) {
        Objects.requireNonNull(value, "A FUNCTION UPPER-CASE or FUNCTION LOWER-CASE operand is "
                + "required; every field compared in 9700-CHECK-CHANGE-IN-REC is a fixed-width PIC X "
                + "item and so is never absent");
        StringBuilder folded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            String character = String.valueOf(value.charAt(index));
            String converted = toUpper
                    ? character.toUpperCase(Locale.ROOT)
                    : character.toLowerCase(Locale.ROOT);
            folded.append(converted.length() == character.length() ? converted : character);
        }
        return folded.toString();
    }

    /**
     * {@code MOVE CC-ACCT-ID TO WS-CARD-RID-ACCT-ID} ({@code app/cbl/COACTUPC.cbl:3892}) presented as
     * {@code WS-CARD-RID-ACCT-ID-X}, the {@code PIC X(11)} redefinition {@code RIDFLD} names at
     * {@code :3897}.
     *
     * @param ccAcctId {@code CC-ACCT-ID} as the work area holds it; {@code null} is read as blank
     * @param codec the codec, which owns the {@code PIC X(11)} receiving rule
     * @return exactly {@link #ACCT_KEY_LENGTH} characters
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static String acctRidImage(String ccAcctId, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required: MOVE CC-ACCT-ID TO WS-CARD-RID-ACCT-ID is "
                + "a PIC X(" + ACCT_KEY_LENGTH + ") move and the codec owns that rule");
        return codec.movePicX(ccAcctId == null ? "" : ccAcctId, ACCT_KEY_LENGTH);
    }

    /**
     * {@code MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID} ({@code app/cbl/COACTUPC.cbl:3920}) presented as
     * {@code WS-CARD-RID-CUST-ID-X}, the {@code PIC X(09)} redefinition {@code RIDFLD} names at
     * {@code :3926}.
     *
     * @param cdemoCustId {@code CDEMO-CUST-ID} from the commarea
     * @param codec the codec, which owns the {@code PIC 9(09)} receiving rule
     * @return exactly {@link #CUST_KEY_LENGTH} digits
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static String custRidImage(int cdemoCustId, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required: MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID "
                + "is a PIC 9(" + CUST_KEY_LENGTH + ") move and the codec owns that rule");
        return codec.movePic9(cdemoCustId, CUST_KEY_LENGTH);
    }

    /**
     * The three date compositions of {@code 9600-WRITE-PROCESSING}:
     * {@code STRING <year> '-' <month> '-' <day> DELIMITED BY SIZE INTO <receiver>} at
     * {@code app/cbl/COACTUPC.cbl:3976-3982} (open date), {@code :3984-3990}
     * ({@code ACCT-UPDATE-EXPIRAION-DATE}), {@code :3993-3999} (reissue date) and {@code :4054-4059}
     * ({@code CUST-UPDATE-DOB-YYYY-MM-DD}).
     *
     * @param year the year part at its declared {@link #DATE_YEAR_LENGTH} characters; {@code null} is read
     *     as blank
     * @param month the month part at its declared {@link #DATE_PART_LENGTH} characters
     * @param day the day part at its declared {@link #DATE_PART_LENGTH} characters
     * @param codec the codec, which owns both the declared-width rule and the concatenation
     * @return exactly {@link #STORED_DATE_LENGTH} characters in {@code YYYY-MM-DD} shape
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static String composeStoredDate(String year, String month, String day,
                                          FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required: STRING ... DELIMITED BY SIZE contributes "
                + "each operand's full declared width and the codec owns that concatenation");
        return codec.concatenateDelimitedBySize(
                codec.movePicX(year == null ? "" : year, DATE_YEAR_LENGTH),
                DATE_SEPARATOR,
                codec.movePicX(month == null ? "" : month, DATE_PART_LENGTH),
                DATE_SEPARATOR,
                codec.movePicX(day == null ? "" : day, DATE_PART_LENGTH));
    }

    /**
     * The two telephone compositions of {@code 9600-WRITE-PROCESSING}:
     * {@code STRING '(' ')' '-' <c> DELIMITED BY SIZE INTO CUST-UPDATE-PHONE-NUM-1} at
     * {@code app/cbl/COACTUPC.cbl:4035-4041} and the same for number two at {@code :4043-4049}.
     *
     * <p>The transfer is 1 + 3 + 1 + 3 + 1 + 4 = 13 characters into a {@code PIC X(15)} receiver, so
     * {@code STRING} does not reach the last two characters and leaves them exactly as they were.
     *
     * @param areaCode {@code ACUP-NEW-CUST-PHONE-NUM-1A PIC X(3)}, the {@code (2:3)} slice of the
     *     fifteen-character span; {@code null} is read as blank
     * @param prefix {@code ACUP-NEW-CUST-PHONE-NUM-1B PIC X(3)}, the {@code (6:3)} slice
     * @param lineNumber {@code ACUP-NEW-CUST-PHONE-NUM-1C PIC X(4)}, the {@code (10:4)} slice
     * @param codec the codec, which owns both the declared-width rule and the concatenation
     * @return exactly 13 characters in {@code (AAA)BBB-CCCC} shape
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static String composePhoneNumber(String areaCode, String prefix, String lineNumber,
                                           FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required: STRING '(' ... DELIMITED BY SIZE "
                + "contributes each operand's full declared width and the codec owns that "
                + "concatenation");
        return codec.concatenateDelimitedBySize(
                PHONE_OPEN,
                codec.movePicX(areaCode == null ? "" : areaCode, CustomerData.PHONE_AREA_CODE_LENGTH),
                PHONE_CLOSE,
                codec.movePicX(prefix == null ? "" : prefix, CustomerData.PHONE_PREFIX_LENGTH),
                PHONE_HYPHEN,
                codec.movePicX(lineNumber == null ? "" : lineNumber,
                        CustomerData.PHONE_LINE_NUMBER_LENGTH));
    }

    /**
     * {@code INITIALIZE ACCT-UPDATE-RECORD} and the eleven moves that follow it
     * ({@code app/cbl/COACTUPC.cbl:3956-4001}), rendered as the 300-character image the rewrite at
     * {@code :4065-4071} sends.
     *
     * @param newDetails {@code ACUP-NEW-DETAILS}; must be the {@link DetailGroup#NEW} group
     * @param codec the codec, which owns every pad, truncate and concatenate rule used here
     * @return exactly {@link #ACCT_UPDATE_RECORD_LENGTH} characters
     * @throws NullPointerException if {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code newDetails} is not the {@link DetailGroup#NEW} group
     * @throws IllegalStateException if the assembled image is not exactly
     *     {@link #ACCT_UPDATE_RECORD_LENGTH} characters, which would mean a span was omitted
     */
    public static String stageAccountUpdateImage(AccountUpdateDetails newDetails,
                                                FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to stage ACCT-UPDATE-RECORD");
        requireGroup(newDetails, DetailGroup.NEW, "ACUP-NEW-DETAILS", "757-855");
        AccountData data = newDetails.acctData();

        String id = codec.movePic9(data.acctId(), AccountRecord.ACCT_ID_LENGTH);
        String activeStatus = codec.movePicX(data.activeStatus(),
                AccountRecord.ACCT_ACTIVE_STATUS_LENGTH);
        String currBal = monetaryImage(data.currBal(), codec);
        String creditLimit = monetaryImage(data.creditLimit(), codec);
        String cashCreditLimit = monetaryImage(data.cashCreditLimit(), codec);
        String currCycCredit = monetaryImage(data.currCycCredit(), codec);
        String currCycDebit = monetaryImage(data.currCycDebit(), codec);
        String openDate = composeStoredDate(data.openYear(), data.openMon(), data.openDay(), codec);
        String expiraionDate = composeStoredDate(data.expYear(), data.expMon(), data.expDay(), codec);
        String reissueDate = composeStoredDate(data.reissueYear(), data.reissueMon(), data.reissueDay(),
                codec);
        String groupId = codec.movePicX(data.groupId(), AccountRecord.ACCT_GROUP_ID_LENGTH);

        String image = id
                + activeStatus
                + currBal
                + creditLimit
                + cashCreditLimit
                + openDate
                + expiraionDate
                + reissueDate
                + currCycCredit
                + currCycDebit
                + groupId
                + " ".repeat(ACCT_UPDATE_FILLER_LENGTH);

        if (image.length() != ACCT_UPDATE_RECORD_LENGTH) {
            throw new IllegalStateException("ACCT-UPDATE-RECORD is "
                    + ACCT_UPDATE_RECORD_LENGTH + " characters and this image is " + image.length()
                    + "; a span has been omitted or mis-sized, and the rewrite at "
                    + "app/cbl/COACTUPC.cbl:4065-4071 sends the whole declared length");
        }
        return image;
    }

    /**
     * {@code INITIALIZE CUST-UPDATE-RECORD} and the eighteen moves that follow it
     * ({@code app/cbl/COACTUPC.cbl:4006-4061}), rendered as the 500-character image the rewrite at
     * {@code :4085-4091} sends.
     *
     * @param newDetails {@code ACUP-NEW-DETAILS}; must be the {@link DetailGroup#NEW} group
     * @param codec the codec, which owns every pad, truncate and concatenate rule used here
     * @return exactly {@link #CUST_UPDATE_RECORD_LENGTH} characters
     * @throws NullPointerException if {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code newDetails} is not the {@link DetailGroup#NEW} group
     */
    public static String stageCustomerUpdateImage(AccountUpdateDetails newDetails,
                                                 FixedWidthCodec codec) {
        return stageCustomerUpdateRecord(newDetails, codec).recordImage(codec);
    }

    /**
     * {@code INITIALIZE CUST-UPDATE-RECORD} and its eighteen moves
     * ({@code app/cbl/COACTUPC.cbl:4006-4061}), as a {@link CustomerRecord}.
     *
     * @param newDetails {@code ACUP-NEW-DETAILS}; must be the {@link DetailGroup#NEW} group
     * @param codec the codec, which owns the concatenation rules for the three {@code STRING}s
     * @return a fully staged record, never {@code null}
     * @throws NullPointerException if {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code newDetails} is not the {@link DetailGroup#NEW} group
     */
    public static CustomerRecord stageCustomerUpdateRecord(AccountUpdateDetails newDetails,
                                                          FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to stage CUST-UPDATE-RECORD");
        requireGroup(newDetails, DetailGroup.NEW, "ACUP-NEW-DETAILS", "757-855");
        CustomerData data = newDetails.custData();

        CustomerRecord staged = new CustomerRecord();

        staged.setCustId(data.custId());
        staged.setCustFirstName(data.firstName());
        staged.setCustMiddleName(data.middleName());
        staged.setCustLastName(data.lastName());
        staged.setCustAddrLine1(data.addrLine1());
        staged.setCustAddrLine2(data.addrLine2());
        staged.setCustAddrLine3(data.addrLine3());
        staged.setCustAddrStateCd(data.addrStateCd());
        staged.setCustAddrCountryCd(data.addrCountryCd());
        staged.setCustAddrZip(data.addrZip());
        staged.setCustPhoneNum1(composePhoneNumber(data.phoneNum1A(), data.phoneNum1B(),
                data.phoneNum1C(), codec));
        staged.setCustPhoneNum2(composePhoneNumber(data.phoneNum2A(), data.phoneNum2B(),
                data.phoneNum2C(), codec));
        staged.setCustSsn(data.ssn());
        staged.setCustGovtIssuedId(data.govtIssuedId());
        staged.setCustDobYyyyMmDd(composeStoredDate(data.dobYear(), data.dobMon(), data.dobDay(),
                codec));
        staged.setCustEftAccountId(data.eftAccountId());
        staged.setCustPriCardHolderInd(data.priHolderInd());
        staged.setCustFicoCreditScore(data.ficoScore());

        return staged;
    }

    private static String monetaryImage(BigDecimal value, FixedWidthCodec codec) {
        return codec.encodeSignedScaled(value, MONETARY_INTEGER_DIGITS, CobolDecimal.MONETARY_SCALE);
    }

    /**
     * {@code FUNCTION NUMVAL-C}, as {@code app/cbl/COACTUPC.cbl:1080,1094,1108,1122,1136} apply it.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or zero when it does not conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numvalC(String image) {
        return NumericIntrinsics.numvalC(image);
    }

    /**
     * {@code FUNCTION TEST-NUMVAL-C}, as the five guards at
     * {@code app/cbl/COACTUPC.cbl:1078,1092,1106,1120,1134} and the one at {@code :2201} apply it.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@link #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of the
     *     first character in error, or the argument's length plus one when it holds no digit at all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return NumericIntrinsics.testNumvalC(image);
    }

    private static int skipSpaces(String image, int from) {
        int index = from;
        while (index < image.length() && image.charAt(index) == SPACE) {
            index++;
        }
        return index;
    }

    private static boolean isSign(char character) {
        return character == PLUS_SIGN || character == MINUS_SIGN;
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static void requireGroup(AccountUpdateDetails details, DetailGroup expected,
                                    String cobolName, String cobolLines) {
        Objects.requireNonNull(details, "The " + cobolName + " group (app/cbl/COACTUPC.cbl:"
                + cobolLines + ") is required");
        if (details.group() != expected) {
            throw new IllegalArgumentException("This operation reads " + cobolName
                    + " (app/cbl/COACTUPC.cbl:" + cobolLines + "), so it requires the "
                    + expected.groupName() + " group, but the supplied details are the "
                    + details.group().groupName() + " group. The two have identical shapes, so nothing "
                    + "but this check would catch the transposition.");
        }
    }

    /**
     * Which of the two identically shaped detail groups a value carries: {@code ACUP-OLD-DETAILS}
     * ({@code app/cbl/COACTUPC.cbl:669-756}) or {@code ACUP-NEW-DETAILS} ({@code :757-855}).
     */
    public enum DetailGroup {
        /**
         * {@code ACUP-OLD-DETAILS} - the snapshot {@code 9500-STORE-FETCHED-DATA} took from the records
         * when the screen was painted ({@code app/cbl/COACTUPC.cbl:3814-3884}), and the right-hand side of
         * every comparison in {@code 9700-CHECK-CHANGE-IN-REC}.
         */
        OLD("ACUP-OLD-DETAILS"),

        /**
         * {@code ACUP-NEW-DETAILS} - what {@code 1100-RECEIVE-MAP} put there from the screen
         * ({@code app/cbl/COACTUPC.cbl:1047-1425}), and the source of every value
         * {@code 9600-WRITE-PROCESSING} stages.
         */
        NEW("ACUP-NEW-DETAILS");

        private final String groupName;

        DetailGroup(String groupName) {
            this.groupName = groupName;
        }

        /**
         * The COBOL group name, verbatim.
         *
         * @return {@code "ACUP-OLD-DETAILS"} or {@code "ACUP-NEW-DETAILS"}
         */
        public String groupName() {
            return groupName;
        }
    }

    /**
     * Which operand a {@code COMPUTE ... FUNCTION NUMVAL-C(...)} statement names.
     */
    public enum NumvalArgument {
        /**
         * The symbolic-map field: {@code ACRDLIMI OF CACTUPAI} at {@code app/cbl/COACTUPC.cbl:1080},
         * {@code ACSHLIMI OF CACTUPAI} at {@code :1094} and {@code ACRCYCRI OF CACTUPAI} at {@code :1122}.
         */
        MAP_FIELD("<screen field> OF CACTUPAI"),

        /**
         * The {@code ALPHA-VARS-FOR-DATA-EDITING} staging copy: {@code ACUP-NEW-CURR-BAL-X} at
         * {@code app/cbl/COACTUPC.cbl:1108} and {@code ACUP-NEW-CURR-CYC-DEBIT-X} at {@code :1136}.
         */
        STAGING_COPY("ACUP-NEW-<item>-X");

        private final String cobolOperand;

        NumvalArgument(String cobolOperand) {
            this.cobolOperand = cobolOperand;
        }

        /**
         * How the source spells the operand, for diagnostics.
         *
         * @return the operand's COBOL shape
         */
        public String cobolOperand() {
            return cobolOperand;
        }
    }

    /**
     * What one of the five {@code COMPUTE} statements left behind, together with everything the arms around
     * it decided.
     *
     * @param cobolReceiver the receiving item's COBOL name, for example {@code "ACUP-NEW-CREDIT-LIMIT-N"}
     * @param sourceLines the {@code app/cbl/COACTUPC.cbl} line range of the {@code COMPUTE}
     * @param numvalArgument which operand the source's {@code NUMVAL-C} call names
     * @param stagingImage what the {@code PIC X(15)} staging item holds afterwards: the screen field at its
     *     declared width, or {@link #LOW_VALUES_IMAGE} on the not-supplied arm
     * @param notSupplied whether the {@code '*'} or {@code SPACES} arm was taken, in which case no
     *     conformance test was evaluated and no conversion was performed
     * @param testNumvalC what {@code FUNCTION TEST-NUMVAL-C} reported, or empty when the not-supplied arm
     *     meant it was never evaluated
     * @param computed whether the {@code COMPUTE} actually executed - true only when the field was supplied
     *     and conformed
     * @param value the {@code -N} span's content afterwards, always at scale
     *     {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE}: the converted value when
     *     {@code computed}
     */
    public record MonetaryEdit(String cobolReceiver,
                              String sourceLines,
                              NumvalArgument numvalArgument,
                              String stagingImage,
                              boolean notSupplied,
                              OptionalInt testNumvalC,
                              boolean computed,
                              BigDecimal value) {
        public MonetaryEdit {
            Objects.requireNonNull(cobolReceiver, "A monetary edit names its COBOL receiver");
            Objects.requireNonNull(sourceLines, "A monetary edit names its source lines");
            Objects.requireNonNull(numvalArgument, "A monetary edit names the operand the source's "
                    + "NUMVAL-C call uses");
            Objects.requireNonNull(stagingImage, "A monetary edit carries what the PIC X("
                    + SCREEN_MONETARY_LENGTH + ") staging item holds afterwards");
            Objects.requireNonNull(testNumvalC, "A monetary edit carries an empty conformance report "
                    + "rather than a null one, so no null escapes the type");
            Objects.requireNonNull(value, "A monetary edit carries the -N span's content afterwards");
            if (stagingImage.length() != SCREEN_MONETARY_LENGTH) {
                throw new IllegalArgumentException("ACUP-NEW-<item>-X is PIC X("
                        + SCREEN_MONETARY_LENGTH + ") (app/cbl/COACTUPC.cbl:412-416) and this image is "
                        + stagingImage.length() + " character(s)");
            }
            if (value.scale() != CobolDecimal.MONETARY_SCALE) {
                throw new IllegalArgumentException("The receiver is PIC S9("
                        + MONETARY_INTEGER_DIGITS + ")V99, so its content is at scale "
                        + CobolDecimal.MONETARY_SCALE + " and this value is at scale " + value.scale());
            }
            if (notSupplied && testNumvalC.isPresent()) {
                throw new IllegalArgumentException("The '*' / SPACES arm at "
                        + "app/cbl/COACTUPC.cbl:1073-1075 performs MOVE LOW-VALUES and nothing else, so "
                        + "FUNCTION TEST-NUMVAL-C is never evaluated on it");
            }
            if (notSupplied && computed) {
                throw new IllegalArgumentException("The '*' / SPACES arm performs no COMPUTE, so a "
                        + "not-supplied edit cannot report one as executed");
            }
            if (!notSupplied && testNumvalC.isEmpty()) {
                throw new IllegalArgumentException("The supplied arm always evaluates FUNCTION "
                        + "TEST-NUMVAL-C, at app/cbl/COACTUPC.cbl:1078 and its four siblings, so its "
                        + "result is never absent");
            }
            if (computed != (!notSupplied && testNumvalC.orElse(-ONE) == NUMVAL_CONFORMS)) {
                throw new IllegalArgumentException("The COMPUTE executes exactly when the field was "
                        + "supplied and FUNCTION TEST-NUMVAL-C reported " + NUMVAL_CONFORMS
                        + "; this edit claims computed=" + computed + " with notSupplied="
                        + notSupplied + " and conformance=" + testNumvalC);
            }
        }

        /**
         * Whether the field was supplied but did not conform, which is the {@code ELSE CONTINUE} arm - the
         * one that leaves the receiving span holding its prior content.
         *
         * @return {@code true} on the non-conforming arm only
         */
        public boolean isNotValid() {
            return !notSupplied && !computed;
        }
    }

    /**
     * {@code ACUP-OLD-ACCT-DATA} ({@code app/cbl/COACTUPC.cbl:670-707}) or {@code ACUP-NEW-ACCT-DATA}
     * ({@code :758-785}) - the account half of a detail group.
     *
     * <p>Every component is stored at its declared width or scale by the canonical constructor, so the
     * comparisons in {@code 9700-CHECK-CHANGE-IN-REC} are between operands of equal width and are therefore
     * plain equality, exactly as COBOL's are.
     *
     * @param acctId {@code ACUP-OLD-ACCT-ID} / {@code ACUP-NEW-ACCT-ID}, the {@code PIC 9(11)} redefinition
     *     of the {@code PIC X(11)} span at {@code :672-674}
     * @param activeStatus {@code ACUP-...-ACTIVE-STATUS PIC X(01)} at {@code :675}
     * @param currBal {@code ACUP-...-CURR-BAL-N PIC S9(10)V99} at {@code :677-679}
     * @param creditLimit {@code ACUP-...-CREDIT-LIMIT-N PIC S9(10)V99} at {@code :680-682}
     * @param cashCreditLimit {@code ACUP-...-CASH-CREDIT-LIMIT-N PIC S9(10)V99} at {@code :683-685}
     * @param openYear {@code ACUP-...-OPEN-YEAR PIC X(4)} at {@code :687}
     * @param openMon {@code ACUP-...-OPEN-MON PIC X(2)} at {@code :688}
     * @param openDay {@code ACUP-...-OPEN-DAY PIC X(2)} at {@code :689}
     * @param expYear {@code ACUP-...-EXP-YEAR PIC X(4)} at {@code :694}
     * @param expMon {@code ACUP-...-EXP-MON PIC X(2)} at {@code :695}
     * @param expDay {@code ACUP-...-EXP-DAY PIC X(2)} at {@code :696}
     * @param reissueYear {@code ACUP-...-REISSUE-YEAR PIC X(4)} at {@code :701}
     * @param reissueMon {@code ACUP-...-REISSUE-MON PIC X(2)} at {@code :702}
     * @param reissueDay {@code ACUP-...-REISSUE-DAY PIC X(2)} at {@code :703}
     * @param currCycCredit {@code ACUP-...-CURR-CYC-CREDIT-N PIC S9(10)V99} at {@code :704-706}
     * @param currCycDebit {@code ACUP-...-CURR-CYC-DEBIT-N PIC S9(10)V99} at {@code :707-709}
     * @param groupId {@code ACUP-...-GROUP-ID PIC X(10)} at {@code :710}
     */
    public record AccountData(long acctId,
                             String activeStatus,
                             BigDecimal currBal,
                             BigDecimal creditLimit,
                             BigDecimal cashCreditLimit,
                             String openYear,
                             String openMon,
                             String openDay,
                             String expYear,
                             String expMon,
                             String expDay,
                             String reissueYear,
                             String reissueMon,
                             String reissueDay,
                             BigDecimal currCycCredit,
                             BigDecimal currCycDebit,
                             String groupId) {
        /**
         * The declared width of {@code ACUP-OLD-ACCT-ID-X PIC X(11)} ({@code :672}).
         */
        public static final int ACCT_ID_LENGTH = AccountRecord.ACCT_ID_LENGTH;

        /**
         * The declared width of {@code ACUP-OLD-ACTIVE-STATUS PIC X(01)} ({@code :675}).
         */
        public static final int ACTIVE_STATUS_LENGTH = AccountRecord.ACCT_ACTIVE_STATUS_LENGTH;

        /**
         * The declared width of {@code ACUP-OLD-GROUP-ID PIC X(10)} ({@code :710}).
         */
        public static final int GROUP_ID_LENGTH = AccountRecord.ACCT_GROUP_ID_LENGTH;

        public AccountData {
            activeStatus = picX(activeStatus, ACTIVE_STATUS_LENGTH, "ACUP-...-ACTIVE-STATUS");
            currBal = monetary(currBal, "ACUP-...-CURR-BAL-N");
            creditLimit = monetary(creditLimit, "ACUP-...-CREDIT-LIMIT-N");
            cashCreditLimit = monetary(cashCreditLimit, "ACUP-...-CASH-CREDIT-LIMIT-N");
            openYear = picX(openYear, DATE_YEAR_LENGTH, "ACUP-...-OPEN-YEAR");
            openMon = picX(openMon, DATE_PART_LENGTH, "ACUP-...-OPEN-MON");
            openDay = picX(openDay, DATE_PART_LENGTH, "ACUP-...-OPEN-DAY");
            expYear = picX(expYear, DATE_YEAR_LENGTH, "ACUP-...-EXP-YEAR");
            expMon = picX(expMon, DATE_PART_LENGTH, "ACUP-...-EXP-MON");
            expDay = picX(expDay, DATE_PART_LENGTH, "ACUP-...-EXP-DAY");
            reissueYear = picX(reissueYear, DATE_YEAR_LENGTH, "ACUP-...-REISSUE-YEAR");
            reissueMon = picX(reissueMon, DATE_PART_LENGTH, "ACUP-...-REISSUE-MON");
            reissueDay = picX(reissueDay, DATE_PART_LENGTH, "ACUP-...-REISSUE-DAY");
            currCycCredit = monetary(currCycCredit, "ACUP-...-CURR-CYC-CREDIT-N");
            currCycDebit = monetary(currCycDebit, "ACUP-...-CURR-CYC-DEBIT-N");
            groupId = picX(groupId, GROUP_ID_LENGTH, "ACUP-...-GROUP-ID");
        }

        /**
         * The group as an unqualified {@code INITIALIZE} leaves it ({@code app/cbl/COACTUPC.cbl:1047} for
         * the new group, {@code :3813} for the old): character spans holding their declared width in
         * spaces, numeric spans zero.
         *
         * @return a fully blank group, never {@code null}
         */
        public static AccountData initialize() {
            BigDecimal zero = CobolDecimal.monetaryZero();
            return new AccountData(0L, "", zero, zero, zero, "", "", "", "", "", "", "", "", "",
                    zero, zero, "");
        }

        public String acctIdImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render ACUP-...-ACCT-ID-X");
            return codec.movePic9(acctId, ACCT_ID_LENGTH);
        }

        /**
         * {@code ACUP-...-OPEN-DATE PIC X(08)}, the base view over the same span the three open-date parts
         * redefine ({@code app/cbl/COACTUPC.cbl:685-689}).
         *
         * @return exactly {@link #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String openDate() {
            return openYear + openMon + openDay;
        }

        /**
         * {@code ACUP-...-EXPIRAION-DATE PIC X(08)}, the base view over the three expiry parts
         * ({@code app/cbl/COACTUPC.cbl:692-696}).
         *
         * @return exactly {@link #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String expiraionDate() {
            return expYear + expMon + expDay;
        }

        /**
         * {@code ACUP-...-REISSUE-DATE PIC X(08)}, the base view over the three reissue parts
         * ({@code app/cbl/COACTUPC.cbl:699-703}).
         *
         * @return exactly {@link #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String reissueDate() {
            return reissueYear + reissueMon + reissueDay;
        }

        /**
         * {@code ACUP-...-CURR-BAL PIC X(12)}, the character view of the span {@link #currBal()} reads as
         * {@code PIC S9(10)V99} ({@code app/cbl/COACTUPC.cbl:677-679}).
         *
         * @param codec the codec, which owns the zoned encoding and the sign overpunch
         * @return exactly {@link #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String currBalImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(currBal, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CREDIT-LIMIT PIC X(12)}, the character view of {@link #creditLimit()}.
         *
         * @param codec the shared fixed-width codec that performs the COBOL move
         * @return exactly {@link #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String creditLimitImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(creditLimit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CASH-CREDIT-LIMIT PIC X(12)}, the character view of {@link #cashCreditLimit()}.
         *
         * @param codec the shared fixed-width codec that performs the COBOL move
         * @return exactly {@link #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String cashCreditLimitImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(cashCreditLimit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CURR-CYC-CREDIT PIC X(12)}, the character view of {@link #currCycCredit()}.
         *
         * @param codec the shared fixed-width codec that performs the COBOL move
         * @return exactly {@link #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String currCycCreditImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(currCycCredit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CURR-CYC-DEBIT PIC X(12)}, the character view of {@link #currCycDebit()}.
         *
         * @param codec the shared fixed-width codec that performs the COBOL move
         * @return exactly {@link #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String currCycDebitImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(currCycDebit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * A diagnostic rendering that names the record without disclosing its money.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "ACUP-<group>-ACCT-DATA["
                    + "ACCT-ID='" + SensitiveDiagnostics.maskIdentifier(acctId, ACCT_ID_LENGTH) + "', "
                    + "ACCT-ACTIVE-STATUS=" + DiagnosticText.singleLine(activeStatus) + ", "
                    + "ACCT-CURR-BAL=" + WITHHELD_NUMBER + ", "
                    + "ACCT-CREDIT-LIMIT=" + WITHHELD_NUMBER + ", "
                    + "ACCT-CASH-CREDIT-LIMIT=" + WITHHELD_NUMBER + ", "
                    + "ACCT-OPEN-DATE=" + openYear + '-' + openMon + '-' + openDay + ", "
                    + "ACCT-EXPIRAION-DATE=" + expYear + '-' + expMon + '-' + expDay + ", "
                    + "ACCT-REISSUE-DATE=" + reissueYear + '-' + reissueMon + '-' + reissueDay + ", "
                    + "ACCT-CURR-CYC-CREDIT=" + WITHHELD_NUMBER + ", "
                    + "ACCT-CURR-CYC-DEBIT=" + WITHHELD_NUMBER + ", "
                    + "ACCT-GROUP-ID=" + DiagnosticText.singleLine(groupId)
                    + "]";
        }
    }

    /**
     * {@code ACUP-OLD-CUST-DATA} ({@code app/cbl/COACTUPC.cbl:711-756}) or {@code ACUP-NEW-CUST-DATA}
     * ({@code :799-855}) - the customer half of a detail group.
     *
     * @param custId {@code ACUP-...-CUST-ID}, the {@code PIC 9(09)} redefinition of the {@code PIC X(09)}
     *     span at {@code :713-715}
     * @param firstName {@code ACUP-...-CUST-FIRST-NAME PIC X(25)} at {@code :716}
     * @param middleName {@code ACUP-...-CUST-MIDDLE-NAME PIC X(25)} at {@code :717}
     * @param lastName {@code ACUP-...-CUST-LAST-NAME PIC X(25)} at {@code :718}
     * @param addrLine1 {@code ACUP-...-CUST-ADDR-LINE-1 PIC X(50)} at {@code :719}
     * @param addrLine2 {@code ACUP-...-CUST-ADDR-LINE-2 PIC X(50)} at {@code :720}
     * @param addrLine3 {@code ACUP-...-CUST-ADDR-LINE-3 PIC X(50)} at {@code :721}
     * @param addrStateCd {@code ACUP-...-CUST-ADDR-STATE-CD PIC X(02)} at {@code :722}
     * @param addrCountryCd {@code ACUP-...-CUST-ADDR-COUNTRY-CD PIC X(03)} at {@code :723}
     * @param addrZip {@code ACUP-...-CUST-ADDR-ZIP PIC X(10)} at {@code :724}
     * @param phoneNum1 {@code ACUP-...-CUST-PHONE-NUM-1 PIC X(15)} at {@code :725}
     * @param phoneNum2 {@code ACUP-...-CUST-PHONE-NUM-2 PIC X(15)} at {@code :734}
     * @param ssn {@code ACUP-...-CUST-SSN}, the {@code PIC 9(09)} redefinition at {@code :744-746}
     * @param govtIssuedId {@code ACUP-...-CUST-GOVT-ISSUED-ID PIC X(20)} at {@code :746}
     * @param dobYear {@code ACUP-...-CUST-DOB-YEAR PIC X(4)} at {@code :749}
     * @param dobMon {@code ACUP-...-CUST-DOB-MON PIC X(2)} at {@code :750}
     * @param dobDay {@code ACUP-...-CUST-DOB-DAY PIC X(2)} at {@code :751}
     * @param eftAccountId {@code ACUP-...-CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code :752}
     * @param priHolderInd {@code ACUP-...-CUST-PRI-HOLDER-IND PIC X(01)} at {@code :753}
     * @param ficoScore {@code ACUP-...-CUST-FICO-SCORE}, the {@code PIC 9(03)} redefinition at
     *     {@code :754-756}
     */
    public record CustomerData(int custId,
                              String firstName,
                              String middleName,
                              String lastName,
                              String addrLine1,
                              String addrLine2,
                              String addrLine3,
                              String addrStateCd,
                              String addrCountryCd,
                              String addrZip,
                              String phoneNum1,
                              String phoneNum2,
                              int ssn,
                              String govtIssuedId,
                              String dobYear,
                              String dobMon,
                              String dobDay,
                              String eftAccountId,
                              String priHolderInd,
                              int ficoScore) {
        /**
         * The declared width of the two telephone items and of their overlays' total
         * ({@code app/cbl/COACTUPC.cbl:725,734}), which is also {@code CUST-PHONE-NUM-1 PIC X(15)} in
         * {@code app/cpy/CVCUS01Y.cpy:14}.
         */
        public static final int PHONE_NUM_LENGTH = CustomerRecord.CUST_PHONE_NUM_1.length();

        /**
         * The one-based position of {@code ACUP-...-CUST-PHONE-NUM-1A} inside the fifteen-character span:
         * 2, because a single {@code FILLER PIC X(1)} precedes it ({@code :726-728}).
         */
        public static final int PHONE_AREA_CODE_START = 2;

        /**
         * The declared width of {@code ACUP-...-CUST-PHONE-NUM-1A PIC X(3)} ({@code :728}).
         */
        public static final int PHONE_AREA_CODE_LENGTH = 3;

        /**
         * The one-based position of {@code ACUP-...-CUST-PHONE-NUM-1B}: 6, after the area code and the
         * closing parenthesis ({@code :729-730}).
         */
        public static final int PHONE_PREFIX_START =
                PHONE_AREA_CODE_START + PHONE_AREA_CODE_LENGTH + ONE;

        /**
         * The declared width of {@code ACUP-...-CUST-PHONE-NUM-1B PIC X(3)} ({@code :730}).
         */
        public static final int PHONE_PREFIX_LENGTH = 3;

        /**
         * The one-based position of {@code ACUP-...-CUST-PHONE-NUM-1C}: 10, after the prefix and the hyphen
         * ({@code :731-732}).
         */
        public static final int PHONE_LINE_NUMBER_START = PHONE_PREFIX_START + PHONE_PREFIX_LENGTH + ONE;

        /**
         * The declared width of {@code ACUP-...-CUST-PHONE-NUM-1C PIC X(4)} ({@code :732}).
         */
        public static final int PHONE_LINE_NUMBER_LENGTH = 4;

        /**
         * The declared width of {@code ACUP-...-CUST-ID-X PIC X(09)} ({@code :713}).
         */
        public static final int CUST_ID_LENGTH = CustomerRecord.CUST_ID.length();

        /**
         * The declared width of {@code ACUP-...-CUST-SSN-X PIC X(09)} ({@code :743}).
         */
        public static final int SSN_LENGTH = CustomerRecord.CUST_SSN.length();

        /**
         * The declared width of {@code ACUP-...-CUST-FICO-SCORE-X PIC X(03)} ({@code :754}).
         */
        public static final int FICO_SCORE_LENGTH = CustomerRecord.CUST_FICO_CREDIT_SCORE.length();

        /**
         * Normalises every character component to its declared width, taking the widths from
         * {@link CustomerRecord}'s spans - which are the same widths {@code ACUP-OLD-CUST-DATA} and
         * {@code CUST-UPDATE-RECORD} declare, verified span by span.
         */
        public CustomerData {
            firstName = picX(firstName, CustomerRecord.CUST_FIRST_NAME.length(),
                    "ACUP-...-CUST-FIRST-NAME");
            middleName = picX(middleName, CustomerRecord.CUST_MIDDLE_NAME.length(),
                    "ACUP-...-CUST-MIDDLE-NAME");
            lastName = picX(lastName, CustomerRecord.CUST_LAST_NAME.length(),
                    "ACUP-...-CUST-LAST-NAME");
            addrLine1 = picX(addrLine1, CustomerRecord.CUST_ADDR_LINE_1.length(),
                    "ACUP-...-CUST-ADDR-LINE-1");
            addrLine2 = picX(addrLine2, CustomerRecord.CUST_ADDR_LINE_2.length(),
                    "ACUP-...-CUST-ADDR-LINE-2");
            addrLine3 = picX(addrLine3, CustomerRecord.CUST_ADDR_LINE_3.length(),
                    "ACUP-...-CUST-ADDR-LINE-3");
            addrStateCd = picX(addrStateCd, CustomerRecord.CUST_ADDR_STATE_CD.length(),
                    "ACUP-...-CUST-ADDR-STATE-CD");
            addrCountryCd = picX(addrCountryCd, CustomerRecord.CUST_ADDR_COUNTRY_CD.length(),
                    "ACUP-...-CUST-ADDR-COUNTRY-CD");
            addrZip = picX(addrZip, CustomerRecord.CUST_ADDR_ZIP.length(), "ACUP-...-CUST-ADDR-ZIP");
            phoneNum1 = picX(phoneNum1, PHONE_NUM_LENGTH, "ACUP-...-CUST-PHONE-NUM-1");
            phoneNum2 = picX(phoneNum2, PHONE_NUM_LENGTH, "ACUP-...-CUST-PHONE-NUM-2");
            govtIssuedId = picX(govtIssuedId, CustomerRecord.CUST_GOVT_ISSUED_ID.length(),
                    "ACUP-...-CUST-GOVT-ISSUED-ID");
            dobYear = picX(dobYear, DATE_YEAR_LENGTH, "ACUP-...-CUST-DOB-YEAR");
            dobMon = picX(dobMon, DATE_PART_LENGTH, "ACUP-...-CUST-DOB-MON");
            dobDay = picX(dobDay, DATE_PART_LENGTH, "ACUP-...-CUST-DOB-DAY");
            eftAccountId = picX(eftAccountId, CustomerRecord.CUST_EFT_ACCOUNT_ID.length(),
                    "ACUP-...-CUST-EFT-ACCOUNT-ID");
            priHolderInd = picX(priHolderInd, CustomerRecord.CUST_PRI_CARD_HOLDER_IND.length(),
                    "ACUP-...-CUST-PRI-HOLDER-IND");
        }

        /**
         * The group as an unqualified {@code INITIALIZE} leaves it: character spans holding their declared
         * width in spaces, numeric spans zero.
         *
         * @return a fully blank group, never {@code null}
         */
        public static CustomerData initialize() {
            return new CustomerData(0, "", "", "", "", "", "", "", "", "", "", "", 0, "", "", "", "",
                    "", "", 0);
        }

        public String custIdImage(FixedWidthCodec codec) {
            return requireCodec(codec).movePic9(custId, CUST_ID_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-SSN-X}, the character view of the span {@link #ssn()} reads as
         * {@code PIC 9(09)}.
         *
         * @param codec the codec, which owns the {@code PIC 9} zero-fill rule
         * @return exactly nine digits
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String ssnImage(FixedWidthCodec codec) {
            return requireCodec(codec).movePic9(ssn, SSN_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-FICO-SCORE-X}, the character view of the span {@link #ficoScore()} reads as
         * {@code PIC 9(03)}, and the form compared at {@code app/cbl/COACTUPC.cbl:4191}.
         *
         * @param codec the codec, which owns the {@code PIC 9} zero-fill rule
         * @return exactly three digits
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String ficoScoreImage(FixedWidthCodec codec) {
            return requireCodec(codec).movePic9(ficoScore, FICO_SCORE_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-PHONE-NUM-1A}, the {@code (2:3)} slice of {@link #phoneNum1()}.
         *
         * @return exactly {@value #PHONE_AREA_CODE_LENGTH} characters
         */
        public String phoneNum1A() {
            return AccountRecord.referenceModify(phoneNum1, PHONE_AREA_CODE_START,
                    PHONE_AREA_CODE_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-PHONE-NUM-1B}, the {@code (6:3)} slice of {@link #phoneNum1()}.
         *
         * @return exactly {@value #PHONE_PREFIX_LENGTH} characters
         */
        public String phoneNum1B() {
            return AccountRecord.referenceModify(phoneNum1, PHONE_PREFIX_START, PHONE_PREFIX_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-PHONE-NUM-1C}, the {@code (10:4)} slice of {@link #phoneNum1()}.
         *
         * @return exactly {@value #PHONE_LINE_NUMBER_LENGTH} characters
         */
        public String phoneNum1C() {
            return AccountRecord.referenceModify(phoneNum1, PHONE_LINE_NUMBER_START,
                    PHONE_LINE_NUMBER_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-PHONE-NUM-2A}, the {@code (2:3)} slice of {@link #phoneNum2()}.
         *
         * @return exactly {@value #PHONE_AREA_CODE_LENGTH} characters
         */
        public String phoneNum2A() {
            return AccountRecord.referenceModify(phoneNum2, PHONE_AREA_CODE_START,
                    PHONE_AREA_CODE_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-PHONE-NUM-2B}, the {@code (6:3)} slice of {@link #phoneNum2()}.
         *
         * @return exactly {@value #PHONE_PREFIX_LENGTH} characters
         */
        public String phoneNum2B() {
            return AccountRecord.referenceModify(phoneNum2, PHONE_PREFIX_START, PHONE_PREFIX_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-PHONE-NUM-2C}, the {@code (10:4)} slice of {@link #phoneNum2()}.
         *
         * @return exactly {@value #PHONE_LINE_NUMBER_LENGTH} characters
         */
        public String phoneNum2C() {
            return AccountRecord.referenceModify(phoneNum2, PHONE_LINE_NUMBER_START,
                    PHONE_LINE_NUMBER_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-DOB-YYYY-MM-DD PIC X(08)}, the base view over the same span the three
         * date-of-birth parts redefine ({@code app/cbl/COACTUPC.cbl:747-751}).
         *
         * @return exactly {@link #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String custDobYyyyMmDd() {
            return dobYear + dobMon + dobDay;
        }

        /**
         * A diagnostic rendering that withholds every personally identifying component (CWE-532).
         *
         * @return a single-line description with every identifying component withheld, never {@code null}
         */
        @Override
        public String toString() {
            return "ACUP-<group>-CUST-DATA["
                    + "CUST-ID='" + SensitiveDiagnostics.maskIdentifier(custId, CUST_ID_LENGTH)
                    + "', "
                    + "CUST-FIRST-NAME=" + withheld(firstName) + ", "
                    + "CUST-MIDDLE-NAME=" + withheld(middleName) + ", "
                    + "CUST-LAST-NAME=" + withheld(lastName) + ", "
                    + "CUST-ADDR-LINE-1=" + withheld(addrLine1) + ", "
                    + "CUST-ADDR-LINE-2=" + withheld(addrLine2) + ", "
                    + "CUST-ADDR-LINE-3=" + withheld(addrLine3) + ", "
                    + "CUST-ADDR-STATE-CD=" + DiagnosticText.singleLine(addrStateCd) + ", "
                    + "CUST-ADDR-COUNTRY-CD=" + DiagnosticText.singleLine(addrCountryCd) + ", "
                    + "CUST-ADDR-ZIP=" + withheld(addrZip) + ", "
                    + "CUST-PHONE-NUM-1=" + withheld(phoneNum1) + ", "
                    + "CUST-PHONE-NUM-2=" + withheld(phoneNum2) + ", "
                    + "CUST-SSN=" + WITHHELD_NUMBER + ", "
                    + "CUST-GOVT-ISSUED-ID=" + withheld(govtIssuedId) + ", "
                    + "CUST-DOB-YYYY-MM-DD=" + WITHHELD_DATE + ", "
                    + "CUST-EFT-ACCOUNT-ID=" + withheld(eftAccountId) + ", "
                    + "CUST-PRI-CARD-HOLDER-IND=" + DiagnosticText.singleLine(priHolderInd) + ", "
                    + "CUST-FICO-CREDIT-SCORE=" + DiagnosticText.singleLine(String.valueOf(ficoScore))
                    + "]";
        }
    }

    /**
     * {@code ACUP-OLD-DETAILS} ({@code app/cbl/COACTUPC.cbl:669-756}) or {@code ACUP-NEW-DETAILS}
     * ({@code :757-855}), whole: the discriminant plus the two halves the COBOL declares under it.
     *
     * @param group which of the two groups this is
     * @param acctData the {@code ...-ACCT-DATA} subgroup
     * @param custData the {@code ...-CUST-DATA} subgroup
     */
    public record AccountUpdateDetails(DetailGroup group, AccountData acctData,
                                      CustomerData custData) {
        public AccountUpdateDetails {
            Objects.requireNonNull(group, "A detail group is either ACUP-OLD-DETAILS or "
                    + "ACUP-NEW-DETAILS; the two have identical shapes, so which one it is has to be "
                    + "carried rather than inferred");
            Objects.requireNonNull(acctData, "A detail group always contains its ...-ACCT-DATA "
                    + "subgroup (app/cbl/COACTUPC.cbl:670 and :758)");
            Objects.requireNonNull(custData, "A detail group always contains its ...-CUST-DATA "
                    + "subgroup (app/cbl/COACTUPC.cbl:711 and :799)");
        }

        /**
         * The group as {@code INITIALIZE ACUP-OLD-DETAILS} ({@code app/cbl/COACTUPC.cbl:3813}) or
         * {@code INITIALIZE ACUP-NEW-DETAILS} ({@code :1047}) leaves it.
         *
         * @param group which group to build
         * @return a fully blank group, never {@code null}
         * @throws NullPointerException if {@code group} is {@code null}
         */
        public static AccountUpdateDetails initialize(DetailGroup group) {
            return new AccountUpdateDetails(group, AccountData.initialize(),
                    CustomerData.initialize());
        }

        /**
         * This group with a different account subgroup, leaving the customer subgroup and the discriminant
         * alone.
         *
         * @param newAcctData the replacement subgroup
         * @return a new group, never {@code null}
         * @throws NullPointerException if {@code newAcctData} is {@code null}
         */
        public AccountUpdateDetails withAcctData(AccountData newAcctData) {
            return new AccountUpdateDetails(group, newAcctData, custData);
        }

        /**
         * This group with a different customer subgroup, leaving the account subgroup and the discriminant
         * alone.
         *
         * @param newCustData the replacement subgroup
         * @return a new group, never {@code null}
         * @throws NullPointerException if {@code newCustData} is {@code null}
         */
        public AccountUpdateDetails withCustData(CustomerData newCustData) {
            return new AccountUpdateDetails(group, acctData, newCustData);
        }

        /**
         * A diagnostic rendering that delegates to the two subgroups' own safe renderings.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "ACUP-" + group + "-DETAILS[acctData=" + acctData + ", custData=" + custData + ']';
        }
    }

    private static final String WITHHELD_NUMBER = "<withheld>";

    private static final String WITHHELD_DATE = "<withheld date>";

    private static final String WITHHELD_TEXT_PREFIX = "<withheld text, length=";

    private static final String WITHHELD_TEXT_SUFFIX = ">";

    private static final String WITHHELD_BLANK = "<blank>";

    private static final String WITHHELD_ABSENT = "<absent>";

    private static String withheld(String value) {
        if (value.isBlank()) {
            return WITHHELD_BLANK;
        }
        return WITHHELD_TEXT_PREFIX + value.length() + WITHHELD_TEXT_SUFFIX;
    }

    private static String picX(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, cobolName + " is a fixed-width PIC X item, so it holds its "
                + "declared " + declaredWidth + " characters and is never absent; move an empty string "
                + "or SPACES to blank it");
        if (value.length() == declaredWidth) {
            return value;
        }
        if (value.length() > declaredWidth) {
            return value.substring(0, declaredWidth);
        }
        return value + String.valueOf(SPACE).repeat(declaredWidth - value.length());
    }

    private static BigDecimal monetary(BigDecimal value, String cobolName) {
        Objects.requireNonNull(value, cobolName + " is PIC S9(" + MONETARY_INTEGER_DIGITS
                + ")V99, so it holds a value and is never absent; a blank span is zero, not null");
        return CobolDecimal.storeMonetary(value);
    }

    private static FixedWidthCodec requireCodec(FixedWidthCodec codec) {
        return Objects.requireNonNull(codec, "A codec is required to render a fixed-width span as the "
                + "characters it holds: the code page is never assumed");
    }

    /**
     * Which of the two comparison blocks of {@code 9700-CHECK-CHANGE-IN-REC} a difference was found in.
     */
    public enum Block {
        /**
         * The account master block, {@code app/cbl/COACTUPC.cbl:4115-4148} - sixteen comparisons, evaluated
         * first.
         */
        ACCOUNT_MASTER("Account Master data", "4115-4148"),

        /**
         * The customer block, {@code app/cbl/COACTUPC.cbl:4152-4192} - nineteen comparisons, reached only
         * when all sixteen of the account block's held.
         */
        CUSTOMER("Customer  data", "4152-4192");

        private final String cobolCaption;

        private final String sourceLines;

        Block(String cobolCaption, String sourceLines) {
            this.cobolCaption = cobolCaption;
            this.sourceLines = sourceLines;
        }

        /**
         * The source's own caption for this block, verbatim - including the two consecutive spaces in
         * {@code "Customer data"} at {@code app/cbl/COACTUPC.cbl:4149}, which is reproduced rather than
         * tidied.
         *
         * @return the caption
         */
        public String cobolCaption() {
            return cobolCaption;
        }

        /**
         * The block's line range in {@code app/cbl/COACTUPC.cbl}.
         *
         * @return the line range
         */
        public String sourceLines() {
            return sourceLines;
        }
    }

    /**
     * How one comparison in {@code 9700-CHECK-CHANGE-IN-REC} treats its two operands.
     */
    public enum Comparison {
        /**
         * A direct {@code EQUAL} between two {@code PIC X} or two {@code PIC 9} items of the same declared
         * width, with no intervening {@code FUNCTION} call.
         */
        EXACT,

        /**
         * Both operands wrapped in {@code FUNCTION LOWER-CASE}.
         */
        LOWER_CASE_FOLDED,

        /**
         * Both operands wrapped in {@code FUNCTION UPPER-CASE}.
         */
        UPPER_CASE_FOLDED,

        MONETARY
    }

    /**
     * Every item {@code 9700-CHECK-CHANGE-IN-REC} compares, in the order the source writes them: sixteen
     * account-master items then nineteen customer items, thirty-five in all.
     */
    public enum ComparedItem {
        /**
         * {@code ACCT-ACTIVE-STATUS EQUAL ACUP-OLD-ACTIVE-STATUS} ({@code :4115}).
         */
        ACCT_ACTIVE_STATUS("ACCT-ACTIVE-STATUS", Block.ACCOUNT_MASTER, Comparison.EXACT, "4115"),

        /**
         * {@code ACCT-CURR-BAL EQUAL ACUP-OLD-CURR-BAL-N} ({@code :4117}).
         */
        ACCT_CURR_BAL("ACCT-CURR-BAL", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4117"),

        /**
         * {@code ACCT-CREDIT-LIMIT EQUAL ACUP-OLD-CREDIT-LIMIT-N} ({@code :4119}).
         */
        ACCT_CREDIT_LIMIT("ACCT-CREDIT-LIMIT", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4119"),

        /**
         * {@code ACCT-CASH-CREDIT-LIMIT EQUAL ACUP-OLD-CASH-CREDIT-LIMIT-N} ({@code :4121}).
         */
        ACCT_CASH_CREDIT_LIMIT("ACCT-CASH-CREDIT-LIMIT", Block.ACCOUNT_MASTER, Comparison.MONETARY,
                "4121"),

        /**
         * {@code ACCT-CURR-CYC-CREDIT EQUAL ACUP-OLD-CURR-CYC-CREDIT-N} ({@code :4123}).
         */
        ACCT_CURR_CYC_CREDIT("ACCT-CURR-CYC-CREDIT", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4123"),

        /**
         * {@code ACCT-CURR-CYC-DEBIT EQUAL ACUP-OLD-CURR-CYC-DEBIT-N} ({@code :4125}).
         */
        ACCT_CURR_CYC_DEBIT("ACCT-CURR-CYC-DEBIT", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4125"),

        /**
         * {@code ACCT-OPEN-DATE(1:4) EQUAL ACUP-OLD-OPEN-YEAR} ({@code :4127}).
         */
        ACCT_OPEN_DATE_YEAR("ACCT-OPEN-DATE(1:4)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4127"),

        /**
         * {@code ACCT-OPEN-DATE(6:2) EQUAL ACUP-OLD-OPEN-MON} ({@code :4128}).
         */
        ACCT_OPEN_DATE_MONTH("ACCT-OPEN-DATE(6:2)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4128"),

        /**
         * {@code ACCT-OPEN-DATE(9:2) EQUAL ACUP-OLD-OPEN-DAY} ({@code :4129}).
         */
        ACCT_OPEN_DATE_DAY("ACCT-OPEN-DATE(9:2)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4129"),

        /**
         * {@code ACCT-EXPIRAION-DATE(1:4) EQUAL ACUP-OLD-EXP-YEAR} ({@code :4131}).
         */
        ACCT_EXPIRAION_DATE_YEAR("ACCT-EXPIRAION-DATE(1:4)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4131"),

        /**
         * {@code ACCT-EXPIRAION-DATE(6:2) EQUAL ACUP-OLD-EXP-MON} ({@code :4132}).
         */
        ACCT_EXPIRAION_DATE_MONTH("ACCT-EXPIRAION-DATE(6:2)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4132"),

        /**
         * {@code ACCT-EXPIRAION-DATE(9:2) EQUAL ACUP-OLD-EXP-DAY} ({@code :4133}).
         */
        ACCT_EXPIRAION_DATE_DAY("ACCT-EXPIRAION-DATE(9:2)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4133"),

        /**
         * {@code ACCT-REISSUE-DATE(1:4) EQUAL ACUP-OLD-REISSUE-YEAR} ({@code :4135}).
         */
        ACCT_REISSUE_DATE_YEAR("ACCT-REISSUE-DATE(1:4)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4135"),

        /**
         * {@code ACCT-REISSUE-DATE(6:2) EQUAL ACUP-OLD-REISSUE-MON} ({@code :4136}).
         */
        ACCT_REISSUE_DATE_MONTH("ACCT-REISSUE-DATE(6:2)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4136"),

        /**
         * {@code ACCT-REISSUE-DATE(9:2) EQUAL ACUP-OLD-REISSUE-DAY} ({@code :4137}).
         */
        ACCT_REISSUE_DATE_DAY("ACCT-REISSUE-DATE(9:2)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4137"),

        /**
         * {@code FUNCTION LOWER-CASE (ACCT-GROUP-ID) EQUAL FUNCTION LOWER-CASE (ACUP-OLD-GROUP-ID)}
         * ({@code :4139-4140}).
         */
        ACCT_GROUP_ID("ACCT-GROUP-ID", Block.ACCOUNT_MASTER, Comparison.LOWER_CASE_FOLDED, "4139"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-FIRST-NAME) EQUAL ...} ({@code :4152-4153}).
         */
        CUST_FIRST_NAME("CUST-FIRST-NAME", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4152"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-MIDDLE-NAME) EQUAL ...} ({@code :4154-4155}).
         */
        CUST_MIDDLE_NAME("CUST-MIDDLE-NAME", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4154"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-LAST-NAME) EQUAL ...} ({@code :4156-4157}).
         */
        CUST_LAST_NAME("CUST-LAST-NAME", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4156"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-ADDR-LINE-1) EQUAL ...} ({@code :4158-4159}).
         */
        CUST_ADDR_LINE_1("CUST-ADDR-LINE-1", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4158"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-ADDR-LINE-2) EQUAL ...} ({@code :4160-4161}).
         */
        CUST_ADDR_LINE_2("CUST-ADDR-LINE-2", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4160"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-ADDR-LINE-3) EQUAL ...} ({@code :4162-4163}).
         */
        CUST_ADDR_LINE_3("CUST-ADDR-LINE-3", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4162"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-ADDR-STATE-CD) EQUAL ...} ({@code :4164-4165}).
         */
        CUST_ADDR_STATE_CD("CUST-ADDR-STATE-CD", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4164"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-ADDR-COUNTRY-CD) EQUAL ...} ({@code :4166-4167}).
         */
        CUST_ADDR_COUNTRY_CD("CUST-ADDR-COUNTRY-CD", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED,
                "4166"),

        /**
         * {@code CUST-ADDR-ZIP EQUAL ACUP-OLD-CUST-ADDR-ZIP} ({@code :4168}) - unfolded, so a difference of
         * case in a postal code is a change.
         */
        CUST_ADDR_ZIP("CUST-ADDR-ZIP", Block.CUSTOMER, Comparison.EXACT, "4168"),

        /**
         * {@code CUST-PHONE-NUM-1 EQUAL ACUP-OLD-CUST-PHONE-NUM-1} ({@code :4169}) - unfolded.
         */
        CUST_PHONE_NUM_1("CUST-PHONE-NUM-1", Block.CUSTOMER, Comparison.EXACT, "4169"),

        /**
         * {@code CUST-PHONE-NUM-2 EQUAL ACUP-OLD-CUST-PHONE-NUM-2} ({@code :4170}) - unfolded.
         */
        CUST_PHONE_NUM_2("CUST-PHONE-NUM-2", Block.CUSTOMER, Comparison.EXACT, "4170"),

        /**
         * {@code CUST-SSN EQUAL ACUP-OLD-CUST-SSN} ({@code :4171}) - two {@code PIC 9(09)} items.
         */
        CUST_SSN("CUST-SSN", Block.CUSTOMER, Comparison.EXACT, "4171"),

        /**
         * {@code FUNCTION UPPER-CASE (CUST-GOVT-ISSUED-ID) EQUAL ...} ({@code :4172-4173}).
         */
        CUST_GOVT_ISSUED_ID("CUST-GOVT-ISSUED-ID", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED,
                "4172"),

        /**
         * {@code CUST-DOB-YYYY-MM-DD (1:4) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (1:4)} ({@code :4174-4175}) -
         * the one slice of the three whose offsets do agree.
         */
        CUST_DOB_YEAR("CUST-DOB-YYYY-MM-DD(1:4)", Block.CUSTOMER, Comparison.EXACT, "4174"),

        /**
         * {@code CUST-DOB-YYYY-MM-DD (6:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)} ({@code :4176-4177}) -
         * different offsets on the two sides, because the record field is ten characters with separators
         * and the snapshot's is eight without.
         */
        CUST_DOB_MONTH("CUST-DOB-YYYY-MM-DD(6:2)", Block.CUSTOMER, Comparison.EXACT, "4176"),

        /**
         * {@code CUST-DOB-YYYY-MM-DD (9:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)} ({@code :4178-4179}) -
         * the second asymmetric pair.
         */
        CUST_DOB_DAY("CUST-DOB-YYYY-MM-DD(9:2)", Block.CUSTOMER, Comparison.EXACT, "4178"),

        /**
         * {@code CUST-EFT-ACCOUNT-ID EQUAL ACUP-OLD-CUST-EFT-ACCOUNT-ID} ({@code :4181-4182}) - unfolded,
         * even though it is a {@code PIC X(10)} item that can hold letters.
         */
        CUST_EFT_ACCOUNT_ID("CUST-EFT-ACCOUNT-ID", Block.CUSTOMER, Comparison.EXACT, "4181"),

        /**
         * {@code CUST-PRI-CARD-HOLDER-IND EQUAL ACUP-OLD-CUST-PRI-HOLDER-IND} ({@code :4183-4186}) -
         * unfolded, so a stored {@code 'y'} against a snapshot {@code 'Y'} is a change.
         */
        CUST_PRI_CARD_HOLDER_IND("CUST-PRI-CARD-HOLDER-IND", Block.CUSTOMER, Comparison.EXACT, "4183"),

        /**
         * {@code CUST-FICO-CREDIT-SCORE EQUAL ACUP-OLD-CUST-FICO-SCORE} ({@code :4187}) - two
         * {@code PIC 9(03)} items.
         */
        CUST_FICO_CREDIT_SCORE("CUST-FICO-CREDIT-SCORE", Block.CUSTOMER, Comparison.EXACT, "4187");

        private final String cobolName;

        private final Block block;

        private final Comparison comparison;

        private final String sourceLine;

        ComparedItem(String cobolName, Block block, Comparison comparison, String sourceLine) {
            this.cobolName = cobolName;
            this.block = block;
            this.comparison = comparison;
            this.sourceLine = sourceLine;
        }

        /**
         * The COBOL name of the record-side operand, verbatim, including any reference-modification suffix.
         *
         * @return the name, never {@code null}
         */
        public String cobolName() {
            return cobolName;
        }

        /**
         * Which of the two comparison blocks this item is compared in.
         *
         * @return the block, never {@code null}
         */
        public Block block() {
            return block;
        }

        public Comparison comparison() {
            return comparison;
        }

        /**
         * The {@code app/cbl/COACTUPC.cbl} line the comparison starts on.
         *
         * @return the line number as text, never {@code null}
         */
        public String sourceLine() {
            return sourceLine;
        }
    }

    /**
     * What {@code 9600-WRITE-PROCESSING} concluded ({@code app/cbl/COACTUPC.cbl:3889-4106}), in the shape
     * the caller's {@code EVALUATE TRUE} at {@code :2603-2614} consumes.
     */
    public enum WriteOutcome {
        /**
         * {@code 88 COULD-NOT-LOCK-ACCT-FOR-UPDATE} - the {@code ACCTDAT} read-for-update at
         * {@code app/cbl/COACTUPC.cbl:3894-3903} returned anything but {@code DFHRESP(NORMAL)}.
         */
        COULD_NOT_LOCK_ACCT_FOR_UPDATE("L", MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE,
                "COULD-NOT-LOCK-ACCT-FOR-UPDATE", 1, false),

        /**
         * {@code 88 LOCKED-BUT-UPDATE-FAILED} - both locks were taken and the concurrency check passed, but
         * a rewrite failed.
         */
        LOCKED_BUT_UPDATE_FAILED("F", MSG_LOCKED_BUT_UPDATE_FAILED, "LOCKED-BUT-UPDATE-FAILED", 2,
                false),

        /**
         * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} - a record changed under the screen, so the update is
         * refused and nothing is written.
         */
        DATA_WAS_CHANGED_BEFORE_UPDATE("S", MSG_DATA_WAS_CHANGED_BEFORE_UPDATE,
                "DATA-WAS-CHANGED-BEFORE-UPDATE", 3, false),

        /**
         * {@code 88 COULD-NOT-LOCK-CUST-FOR-UPDATE} - the {@code CUSTDAT} read-for-update at
         * {@code app/cbl/COACTUPC.cbl:3922-3931} returned anything but {@code DFHRESP(NORMAL)}.
         */
        COULD_NOT_LOCK_CUST_FOR_UPDATE("C", MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE,
                "COULD-NOT-LOCK-CUST-FOR-UPDATE", 4, false),

        /**
         * {@code WHEN OTHER} - both locks were taken, nothing had changed, and both rewrites succeeded.
         */
        CHANGES_OKAYED_AND_DONE("C", null, "WHEN OTHER", 4, true);

        private final String changeActionCode;

        private final String returnMessage;

        private final String cobolCondition;

        private final int firstMatchWinsPosition;

        private final boolean rewritten;

        WriteOutcome(String changeActionCode, String returnMessage, String cobolCondition,
                    int firstMatchWinsPosition, boolean rewritten) {
            this.changeActionCode = changeActionCode;
            this.returnMessage = returnMessage;
            this.cobolCondition = cobolCondition;
            this.firstMatchWinsPosition = firstMatchWinsPosition;
            this.rewritten = rewritten;
        }

        /**
         * The {@code ACUP-CHANGE-ACTION PIC X(1)} value the caller's {@code EVALUATE} sets for this outcome
         * ({@code app/cbl/COACTUPC.cbl:652-668}): {@code "L"} lock error, {@code "F"} failed, {@code "S"}
         * show details, {@code "C"} okayed and done.
         *
         * @return one character, never {@code null}
         */
        public String changeActionCode() {
            return changeActionCode;
        }

        /**
         * The {@code WS-RETURN-MSG} literal this outcome names, or empty for {@code WHEN OTHER}, which
         * names none and leaves the field as it stood.
         *
         * @return the literal, or empty
         */
        public Optional<String> returnMessageLiteral() {
            return Optional.ofNullable(returnMessage);
        }

        /**
         * The {@code 88}-level condition name, or {@code "WHEN OTHER"} for the default arm.
         *
         * @return the condition name, never {@code null}
         */
        public String cobolCondition() {
            return cobolCondition;
        }

        /**
         * The one-based position of the {@code WHEN} arm that matches this outcome in the caller's
         * {@code EVALUATE TRUE} ({@code app/cbl/COACTUPC.cbl:2603-2614}).
         *
         * @return 1, 2, 3 or 4
         */
        public int firstMatchWinsPosition() {
            return firstMatchWinsPosition;
        }

        /**
         * Whether reaching this outcome means both records were rewritten.
         *
         * @return {@code true} only when the write path ran to completion
         */
        public boolean isRewritten() {
            return rewritten;
        }

        /**
         * Whether this outcome is a lock failure, which is the only shape that sets {@code 88 INPUT-ERROR}
         * ({@code app/cbl/COACTUPC.cbl:3910} and {@code :3938}) and the only one whose message is placed
         * under the {@code WS-RETURN-MSG-OFF} guard.
         *
         * @return {@code true} for the two lock failures
         */
        public boolean isLockFailure() {
            return this == COULD_NOT_LOCK_ACCT_FOR_UPDATE || this == COULD_NOT_LOCK_CUST_FOR_UPDATE;
        }
    }

    /**
     * What {@code 9700-CHECK-CHANGE-IN-REC} concluded ({@code app/cbl/COACTUPC.cbl:4109-4194}).
     *
     * @param dataWasChanged whether {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} was set
     * @param failingBlock which block short-circuited the paragraph, or empty when neither did
     * @param differingItems the items that differed within that block, in declaration order
     */
    public record ChangeCheck(boolean dataWasChanged, Optional<Block> failingBlock,
                             Set<ComparedItem> differingItems) {
        public ChangeCheck {
            Objects.requireNonNull(failingBlock, "A check result carries an empty failing block rather "
                    + "than a null one, so no null escapes the type");
            Objects.requireNonNull(differingItems, "A check result carries an empty item set rather "
                    + "than a null one");
            differingItems = Collections.unmodifiableSet(differingItems.isEmpty()
                    ? EnumSet.noneOf(ComparedItem.class)
                    : EnumSet.copyOf(differingItems));
            if (dataWasChanged != !differingItems.isEmpty()) {
                throw new IllegalArgumentException("app/cbl/COACTUPC.cbl:4115-4145 and :4152-4191 join "
                        + "their comparisons with AND, so a record changed exactly when at least one of "
                        + "them failed; this result claims dataWasChanged=" + dataWasChanged + " with "
                        + differingItems.size() + " differing item(s)");
            }
            if (dataWasChanged != failingBlock.isPresent()) {
                throw new IllegalArgumentException("A changed result names the block that "
                        + "short-circuited the paragraph and an unchanged one names none; this result "
                        + "claims dataWasChanged=" + dataWasChanged + " with failingBlock="
                        + failingBlock);
            }
            for (ComparedItem item : differingItems) {
                if (failingBlock.isPresent() && item.block() != failingBlock.get()) {
                    throw new IllegalArgumentException("The GO TO at app/cbl/COACTUPC.cbl:4147 leaves "
                            + "the paragraph, so the customer block is never evaluated when the account "
                            + "block fails; a result cannot carry items from both blocks, and "
                            + item.cobolName() + " belongs to " + item.block() + " while the failing "
                            + "block is " + failingBlock.get());
                }
            }
        }

        /**
         * The unchanged arm: both blocks reached their {@code CONTINUE}, so the caller stages and rewrites.
         *
         * @return a result carrying no difference, never {@code null}
         */
        public static ChangeCheck unchanged() {
            return new ChangeCheck(false, Optional.empty(), EnumSet.noneOf(ComparedItem.class));
        }

        /**
         * The changed arm: one block failed, so {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} is set and the
         * paragraph leaves through {@code 9600-WRITE-PROCESSING-EXIT}.
         *
         * @param block the block that failed
         * @param items the items that differed within it; must not be empty
         * @return a result carrying the difference, never {@code null}
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code items} is empty or contains an item from another block
         */
        public static ChangeCheck changed(Block block, Set<ComparedItem> items) {
            Objects.requireNonNull(block, "A changed result names the block that failed");
            Objects.requireNonNull(items, "A changed result carries the items that differed");
            if (items.isEmpty()) {
                throw new IllegalArgumentException("A block fails only when at least one of its "
                        + "comparisons fails, so a changed result carries at least one item");
            }
            return new ChangeCheck(true, Optional.of(block), items);
        }

        /**
         * The COBOL names of the items that differed, comma-separated and prefixed with the block they were
         * found in, or a fixed phrase when nothing differed.
         *
         * @return a human-readable summary, never {@code null} and never containing a field's content
         */
        public String describeDifferences() {
            if (!dataWasChanged) {
                return "Nothing differs: all " + ComparedItem.values().length + " compared items "
                        + "matched.";
            }
            StringBuilder summary = new StringBuilder("The comparison stopped in the ");
            summary.append(failingBlock.map(Block::cobolCaption).orElse("unknown"))
                    .append(" block (app/cbl/COACTUPC.cbl:")
                    .append(failingBlock.map(Block::sourceLines).orElse("unknown"))
                    .append("); items that differ: ");
            boolean first = true;
            for (ComparedItem item : differingItems) {
                if (!first) {
                    summary.append(", ");
                }
                summary.append(item.cobolName());
                first = false;
            }
            return summary.toString();
        }
    }

    /**
     * What {@code 9600-WRITE-PROCESSING} left behind ({@code app/cbl/COACTUPC.cbl:3889-4106}).
     *
     * @param outcome which of the five arms was reached
     * @param inputError {@code 88 INPUT-ERROR} ({@code :173})
     * @param syncpointRollbackRequested {@code EXEC CICS SYNCPOINT ROLLBACK} ({@code :4099-4101})
     * @param returnMessage {@code WS-RETURN-MSG PIC X(75)} ({@code :479}) as it stands after the paragraph,
     *     at exactly its declared width
     * @param fileStatus the two-character status of the last dataset operation this paragraph performed -
     *     {@link FileStatus#OK} where every operation succeeded or where none was reached
     * @param cicsResp {@code WS-RESP-CD}, present when the backend reported a CICS response for that
     *     operation
     * @param failedOperation {@link #READ_OPERATION_NAME} or {@link #REWRITE_OPERATION_NAME} when an
     *     operation failed, otherwise empty - the {@code ERROR-OPNAME} of {@code WS-FILE-ERROR-MESSAGE}
     *     ({@code :392-395})
     * @param failedFileName {@link #ACCT_CICS_FILE_NAME} or {@link #CUST_CICS_FILE_NAME} when an operation
     *     failed, otherwise empty - the {@code ERROR-FILE} of the same group
     * @param acctUpdateRecordImage the staged {@code ACCT-UPDATE-RECORD}, verbatim, present once the change
     *     check passed
     * @param custUpdateRecordImage the staged {@code CUST-UPDATE-RECORD}, verbatim, present under the same
     *     condition
     * @param changeCheck what {@code 9700-CHECK-CHANGE-IN-REC} concluded, present whenever it ran - that
     *     is, whenever both locks were taken
     */
    public record WriteResult(WriteOutcome outcome,
                             boolean inputError,
                             boolean syncpointRollbackRequested,
                             String returnMessage,
                             String fileStatus,
                             OptionalInt cicsResp,
                             Optional<String> failedOperation,
                             Optional<String> failedFileName,
                             Optional<String> acctUpdateRecordImage,
                             Optional<String> custUpdateRecordImage,
                             Optional<ChangeCheck> changeCheck) {
        public WriteResult {
            Objects.requireNonNull(outcome, "A write result names which arm of "
                    + "9600-WRITE-PROCESSING was reached");
            Objects.requireNonNull(returnMessage, "A write result carries WS-RETURN-MSG as it stands "
                    + "afterwards; the cleared state is " + RETURN_MESSAGE_LENGTH + " spaces, not null");
            Objects.requireNonNull(fileStatus, "A write result carries the last dataset operation's "
                    + "status");
            Objects.requireNonNull(cicsResp, "A write result carries an empty response rather than a "
                    + "null one");
            Objects.requireNonNull(failedOperation, "A write result carries an empty operation name "
                    + "rather than a null one");
            Objects.requireNonNull(failedFileName, "A write result carries an empty file name rather "
                    + "than a null one");
            Objects.requireNonNull(acctUpdateRecordImage, "A write result carries an empty account "
                    + "image rather than a null one");
            Objects.requireNonNull(custUpdateRecordImage, "A write result carries an empty customer "
                    + "image rather than a null one");
            Objects.requireNonNull(changeCheck, "A write result carries an empty change check rather "
                    + "than a null one");

            if (returnMessage.length() != RETURN_MESSAGE_LENGTH) {
                throw new IllegalArgumentException("WS-RETURN-MSG is PIC X(" + RETURN_MESSAGE_LENGTH
                        + ") (app/cbl/COACTUPC.cbl:479) and this message is " + returnMessage.length()
                        + " character(s)");
            }
            if (fileStatus.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A file status is exactly "
                        + FileStatus.STATUS_LENGTH + " characters and this one is "
                        + fileStatus.length());
            }
            if (inputError != outcome.isLockFailure()) {
                throw new IllegalArgumentException("SET INPUT-ERROR TO TRUE appears at "
                        + "app/cbl/COACTUPC.cbl:3910 and :3938 and nowhere else in this paragraph, so "
                        + "it holds for the two lock failures alone; this result claims inputError="
                        + inputError + " for outcome " + outcome);
            }
            if (syncpointRollbackRequested && outcome != WriteOutcome.LOCKED_BUT_UPDATE_FAILED) {
                throw new IllegalArgumentException("EXEC CICS SYNCPOINT ROLLBACK is issued only by the "
                        + "customer rewrite failure arm at app/cbl/COACTUPC.cbl:4099-4101, which is "
                        + WriteOutcome.LOCKED_BUT_UPDATE_FAILED + "; this result requests it for "
                        + outcome);
            }
            if (syncpointRollbackRequested
                    && !failedFileName.orElse("").equals(CUST_CICS_FILE_NAME)) {
                throw new IllegalArgumentException("Only the " + CUST_CICS_FILE_NAME.trim()
                        + " rewrite failure rolls back, because only then has the "
                        + ACCT_CICS_FILE_NAME.trim() + " rewrite already succeeded; this result "
                        + "requests a rollback for " + failedFileName);
            }
            if (failedOperation.isPresent() != failedFileName.isPresent()) {
                throw new IllegalArgumentException("A failed operation always names both the operation "
                        + "and the file, matching ERROR-OPNAME and ERROR-FILE at "
                        + "app/cbl/COACTUPC.cbl:392-395");
            }
            boolean stagedExpected = outcome == WriteOutcome.LOCKED_BUT_UPDATE_FAILED
                    || outcome == WriteOutcome.CHANGES_OKAYED_AND_DONE;
            if (acctUpdateRecordImage.isPresent() != stagedExpected
                    || custUpdateRecordImage.isPresent() != stagedExpected) {
                throw new IllegalArgumentException("app/cbl/COACTUPC.cbl:3956-4061 stages both records "
                        + "before either rewrite is issued, so both images are present exactly when the "
                        + "paragraph reached the rewrites; outcome " + outcome + " carries account="
                        + acctUpdateRecordImage.isPresent() + ", customer="
                        + custUpdateRecordImage.isPresent());
            }
            if (acctUpdateRecordImage.isPresent()
                    && acctUpdateRecordImage.get().length() != ACCT_UPDATE_RECORD_LENGTH) {
                throw new IllegalArgumentException("ACCT-UPDATE-RECORD is "
                        + ACCT_UPDATE_RECORD_LENGTH + " characters and this image is "
                        + acctUpdateRecordImage.get().length());
            }
            if (custUpdateRecordImage.isPresent()
                    && custUpdateRecordImage.get().length() != CUST_UPDATE_RECORD_LENGTH) {
                throw new IllegalArgumentException("CUST-UPDATE-RECORD is "
                        + CUST_UPDATE_RECORD_LENGTH + " characters and this image is "
                        + custUpdateRecordImage.get().length());
            }
            if (changeCheck.isPresent() == outcome.isLockFailure()) {
                throw new IllegalArgumentException("PERFORM 9700-CHECK-CHANGE-IN-REC at "
                        + "app/cbl/COACTUPC.cbl:3947-3948 runs only after both locks were taken, so its "
                        + "result is present for every outcome except the two lock failures; outcome "
                        + outcome + " carries changeCheck=" + changeCheck.isPresent());
            }
            if (changeCheck.isPresent() && changeCheck.get().dataWasChanged()
                    != (outcome == WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE)) {
                throw new IllegalArgumentException("The guard at app/cbl/COACTUPC.cbl:3950-3952 leaves "
                        + "the paragraph exactly when the check found a change, so the two cannot "
                        + "disagree; outcome " + outcome + " carries dataWasChanged="
                        + changeCheck.get().dataWasChanged());
            }
        }

        /**
         * The {@code ACUP-CHANGE-ACTION} value the caller's {@code EVALUATE} sets, equivalent to
         * {@code outcome().changeActionCode()}.
         *
         * @return one character, never {@code null}
         */
        public String changeActionCode() {
            return outcome.changeActionCode();
        }

        /**
         * Whether both records were rewritten, equivalent to {@code outcome().isRewritten()}.
         *
         * @return {@code true} only for {@link WriteOutcome#CHANGES_OKAYED_AND_DONE}
         */
        public boolean isRewritten() {
            return outcome.isRewritten();
        }

        /**
         * Whether {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} holds - the caller's cue to repaint the detail
         * screen rather than report a failure.
         *
         * @return {@code true} only for {@link WriteOutcome#DATA_WAS_CHANGED_BEFORE_UPDATE}
         */
        public boolean isDataWasChangedBeforeUpdate() {
            return outcome == WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE;
        }

        /**
         * A diagnostic rendering that withholds the two staged record images (CWE-532).
         *
         * @return a single-line description with both staged images withheld, never {@code null}
         */
        @Override
        public String toString() {
            return "WriteResult["
                    + "outcome=" + outcome + ", "
                    + "inputError=" + inputError + ", "
                    + "syncpointRollbackRequested=" + syncpointRollbackRequested + ", "
                    + "returnMessage='" + returnMessage.strip() + "', "
                    + "fileStatus='" + fileStatus + "', "
                    + "cicsResp=" + cicsResp + ", "
                    + "failedOperation=" + failedOperation + ", "
                    + "failedFileName=" + failedFileName + ", "
                    + "acctUpdateRecordImage=" + imageDescription(acctUpdateRecordImage) + ", "
                    + "custUpdateRecordImage=" + imageDescription(custUpdateRecordImage) + ", "
                    + "changeCheck=" + changeCheck
                    + "]";
        }

        private static String imageDescription(Optional<String> image) {
            return image.map(staged -> WITHHELD_TEXT_PREFIX + staged.length() + WITHHELD_TEXT_SUFFIX)
                    .orElse(WITHHELD_ABSENT);
        }
    }

    /**
     * Carries a completed {@link WriteResult} out through a rollback.
     */
    private static final class SyncpointRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient WriteResult result;

        private SyncpointRollback(WriteResult result) {
            super("EXEC CICS SYNCPOINT ROLLBACK (app/cbl/COACTUPC.cbl:4099-4101): the ACCTDAT rewrite "
                    + "succeeded and the CUSTDAT rewrite did not, so the unit of work is rolled back and "
                    + "the paragraph's outcome is carried out to the caller", null, false, false);
            this.result = result;
        }

        private WriteResult result() {
            return result;
        }
    }
}
