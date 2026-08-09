package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.NumericIntrinsics;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import java.math.BigDecimal;
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
 * The write path of {@code app/cbl/COACTUPC.cbl} - the two paragraphs that lock the account and
 * customer records, decide whether anybody changed either of them while the screen was being filled
 * in, and rewrite both.
 *
 * <p>Three pieces of {@code COACTUPC} live here, and nothing else does:
 * <ul>
 *   <li>{@code 9600-WRITE-PROCESSING} ({@code app/cbl/COACTUPC.cbl:3889-4106}) becomes
 *       {@link #writeProcessing(String, NavigationContext, AccountUpdateDetails,
 *       AccountUpdateDetails, String, FixedWidthCodec)};</li>
 *   <li>{@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4109-4194}) becomes
 *       {@link #checkChangeInRec(AccountRecord, CustomerRecord, AccountUpdateDetails,
 *       FixedWidthCodec)};</li>
 *   <li>the program's five - and only five - {@code COMPUTE} statements
 *       ({@code app/cbl/COACTUPC.cbl:1079,1093,1107,1121,1135}) become
 *       {@link #computeCreditLimit(String, BigDecimal)} and its four siblings.</li>
 * </ul>
 * The screen painting, the {@code RECEIVE MAP} handling, the field edits, the
 * {@code CSSETATY} error highlighting and the {@code XCTL} chaining of the same program are the
 * controller's, not this class's. This class is where the decisions are, which is the whole point of
 * it: gate G51 requires the business logic to sit in a service so a parity test can reach every
 * branch with no HTTP layer and no {@code JobLauncher} in the path. Every method below is reachable
 * from a plain JUnit test that does nothing more than
 * {@code new AccountUpdateService(mockAccountRepository, mockCustomerRepository)}.
 *
 * <h2>The migration brief names the wrong paragraph, and the correction matters</h2>
 * <p>The Agent Action Plan derives this file from {@code COACTUPC} paragraph
 * {@code 9300-CHECK-CHANGE-IN-REC}. <strong>That label does not exist in {@code COACTUPC}.</strong>
 * Three facts settle it, each read from the source rather than inferred:
 * <ul>
 *   <li>{@code COACTUPC}'s {@code 9300-} paragraph is {@code 9300-GETACCTDATA-BYACCT} at
 *       {@code app/cbl/COACTUPC.cbl:3701} - a {@code READ} paragraph with no comparison in it;</li>
 *   <li>the optimistic-concurrency paragraph is {@code 9700-CHECK-CHANGE-IN-REC} at
 *       {@code :4109}, whose exit label is at {@code :4194} and whose sole invocation is
 *       {@code PERFORM 9700-CHECK-CHANGE-IN-REC THRU 9700-CHECK-CHANGE-IN-REC-EXIT} at
 *       {@code :3947-3948};</li>
 *   <li>{@code 9300-CHECK-CHANGE-IN-REC} is {@code COCRDUPC}'s label
 *       ({@code app/cbl/COCRDUPC.cbl:1498-1521}), and it belongs to the sibling
 *       {@link com.vsergeychik.carddemo.card.CardUpdateService}.</li>
 * </ul>
 * The brief's intent - reproduce the optimistic check - is right; only the label is wrong, and the
 * {@code 9700} paragraph is what this class implements. Recorded here rather than silently
 * accommodated (practice B4).
 *
 * <h2>This is genuine COBOL-side optimistic concurrency, and it stays that way (gates G43, G44)</h2>
 * <p>{@code 9700-CHECK-CHANGE-IN-REC} is not a modernisation. It is already in the 1990s source: the
 * program re-reads both records under a lock and compares thirty-five items against the snapshot the
 * screen was painted from, and only rewrites when every one of them still agrees. That comparison
 * <strong>is</strong> the concurrency control, and it is preserved item for item.
 *
 * <p><strong>No version column, no timestamp column, no row-version annotation and no schema change
 * of any kind may be introduced here.</strong> A version column would be the textbook Java answer and
 * it is forbidden twice over: gate G44 forbids DDL and entity annotations outright, and gate G43
 * requires this specific comparison, on these specific items, in this specific order. The account
 * identifier, the customer identifier and {@code ACCT-ADDR-ZIP} are deliberately <em>not</em>
 * compared, because the COBOL does not compare them.
 *
 * <h2>COBOL reference modification is 1-based; this is the highest off-by-one risk in the file</h2>
 * <p>{@code :4131-4143} slices each of the three ten-byte account dates three times, and
 * {@code :4174-4179} slices the ten-byte date of birth. The mapping to Java is fixed and is restated
 * at every use site through {@link AccountRecord#referenceModify(String, int, int)}:
 * <table border="1">
 *   <caption>Ten-byte {@code YYYY-MM-DD} slices, COBOL 1-based to Java 0-based</caption>
 *   <tr><th>COBOL</th><th>Characters</th><th>Java</th><th>Content</th></tr>
 *   <tr><td>{@code (1:4)}</td><td>1 to 4</td><td>{@code substring(0, 4)}</td><td>{@code YYYY}</td></tr>
 *   <tr><td>{@code (6:2)}</td><td>6 to 7</td><td>{@code substring(5, 7)}</td><td>{@code MM}</td></tr>
 *   <tr><td>{@code (9:2)}</td><td>9 to 10</td><td>{@code substring(8, 10)}</td><td>{@code DD}</td></tr>
 * </table>
 * <p>Characters 5 and 8 - Java indices 4 and 7 - are the two {@code '-'} separators, and they are
 * <strong>never compared</strong>. That is not an oversight to tidy up: a stored record whose
 * separators differ from the snapshot's compares <em>equal</em> in COBOL, and it must compare equal
 * here too. Collapsing the three slices into one whole-string comparison would change the answer.
 *
 * <h2>The date-of-birth offsets are asymmetric, and making them symmetric would break the check</h2>
 * <p>{@code :4174-4179} reads, verbatim:
 * <pre>
 * AND CUST-DOB-YYYY-MM-DD (1:4)  EQUAL  ACUP-OLD-CUST-DOB-YYYY-MM-DD (1:4)
 * AND CUST-DOB-YYYY-MM-DD (6:2)  EQUAL  ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)
 * AND CUST-DOB-YYYY-MM-DD (9:2)  EQUAL  ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)
 * </pre>
 * <p>The two sides use different offsets because they are different widths.
 * {@code CUST-DOB-YYYY-MM-DD} is {@code PIC X(10)} ({@code app/cpy/CVCUS01Y.cpy:19}) and holds
 * {@code YYYY-MM-DD} with separators. {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD} is
 * {@code PIC X(08)} ({@code app/cbl/COACTUPC.cbl:747}) and holds {@code YYYYMMDD} with none -
 * {@code 9500-STORE-FETCHED-DATA} fills it three slices at a time at {@code :3858-3860}, through the
 * {@code ACUP-OLD-CUST-DOB-PARTS REDEFINES} overlay at {@code :748-751}. So {@code (5:2)} and
 * {@code (7:2)} are the month and day of an eight-character field, exactly as {@code (6:2)} and
 * {@code (9:2)} are the month and day of a ten-character one. Both offset sets are reproduced
 * verbatim, and this class models the old snapshot's date as its three declared parts so the
 * asymmetry is expressed by construction rather than by arithmetic.
 *
 * <p>The three account dates carry the same {@code X(08)} three-part shape ({@code :685-702}), but
 * {@code 9700} compares those against the <em>named</em> part sub-items rather than by offset, which
 * is why only the date of birth shows raw asymmetric offsets in the source text.
 *
 * <p>The field is {@code CUST-DOB-YYYY-MM-DD}, with hyphens, because it comes from
 * {@code CVCUS01Y}. {@code app/cpy/CUSTREC.cpy} spells its near-duplicate
 * {@code CUST-DOB-YYYYMMDD}, and that is a different type owned by
 * {@code com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord}. The two are never
 * interchanged.
 *
 * <h2>The case folding runs in opposite directions in the two blocks</h2>
 * <p>{@code :4144-4145} folds the account group identifier to <strong>lower</strong> case:
 * {@code FUNCTION LOWER-CASE (ACCT-GROUP-ID) EQUAL FUNCTION LOWER-CASE (ACUP-OLD-GROUP-ID)}. The
 * eight customer text comparisons at {@code :4152-4173} fold to <strong>upper</strong> case. Both
 * directions are case-insensitive, so the asymmetry changes no outcome for ASCII letters - but it is
 * in the source, it is visible in a diff, and it is preserved rather than harmonised (practice B5).
 * Both folds go through {@link Locale#ROOT} so that a host locale can never turn a Turkish dotless
 * {@code i} into a mismatch.
 *
 * <h2>Seven of the customer comparisons are NOT folded</h2>
 * <p>{@code CUST-ADDR-ZIP}, {@code CUST-PHONE-NUM-1}, {@code CUST-PHONE-NUM-2}, {@code CUST-SSN},
 * {@code CUST-EFT-ACCOUNT-ID}, {@code CUST-PRI-CARD-HOLDER-IND} and
 * {@code CUST-FICO-CREDIT-SCORE} are compared exactly, with no {@code FUNCTION UPPER-CASE} around
 * them ({@code :4169-4172,4181-4191}). {@code CUST-EFT-ACCOUNT-ID} and
 * {@code CUST-PRI-CARD-HOLDER-IND} are the interesting pair, because both are {@code PIC X} items
 * that could plausibly hold letters: a stored {@code 'y'} against a snapshot {@code 'Y'} is a
 * <em>change</em> here, where the same difference in {@code CUST-FIRST-NAME} is not. Adding a fold
 * would silently loosen the concurrency check.
 *
 * <h2>{@code ACCT-UPDATE-RECORD} is NOT {@code CVACT01Y}, and that is a legacy defect (practice B5)</h2>
 * <p>This is the most consequential finding in this file and it is easy to miss, because the group at
 * {@code app/cbl/COACTUPC.cbl:418-433} is captioned "Data-structure for account entity (RECLN 300)"
 * and does total 300 bytes. It is still not the account master's layout.
 * <table border="1">
 *   <caption>{@code ACCT-UPDATE-RECORD} against {@code app/cpy/CVACT01Y.cpy}, by offset</caption>
 *   <tr><th>Offset</th><th>{@code CVACT01Y} (the stored record)</th><th>{@code ACCT-UPDATE-RECORD}</th></tr>
 *   <tr><td>0-101</td><td>identifier through {@code ACCT-CURR-CYC-DEBIT}</td><td>identical</td></tr>
 *   <tr><td>102-111</td><td>{@code ACCT-ADDR-ZIP PIC X(10)}</td>
 *       <td><strong>{@code ACCT-UPDATE-GROUP-ID PIC X(10)}</strong></td></tr>
 *   <tr><td>112-121</td><td>{@code ACCT-GROUP-ID PIC X(10)}</td>
 *       <td><strong>{@code FILLER}</strong></td></tr>
 *   <tr><td>122-299</td><td>{@code FILLER PIC X(178)}</td><td>{@code FILLER}, continued</td></tr>
 * </table>
 * <p>{@code ACCT-UPDATE-RECORD} simply has no {@code ACCT-ADDR-ZIP} item, so everything after
 * {@code ACCT-CURR-CYC-DEBIT} sits ten bytes early and the trailing reserved span is
 * {@value #ACCT_UPDATE_FILLER_LENGTH} bytes rather than 178. The rewrite at {@code :4065-4071}
 * states {@code LENGTH(LENGTH OF ACCT-UPDATE-RECORD)}, so it is a <strong>full-width 300-byte
 * rewrite</strong>. The consequence is exact and observable: every successful account update through
 * {@code COACTUPC} writes the new group identifier over the stored {@code ACCT-ADDR-ZIP} and leaves
 * the real {@code ACCT-GROUP-ID} span blank.
 *
 * <p>That is <strong>reproduced, not repaired</strong>. Inserting the missing zip item would change
 * two hundred bytes of every rewritten record and would be a new business rule. The staging is
 * therefore written against {@code ACCT-UPDATE-RECORD}'s own offsets - see
 * {@link #stageAccountUpdateImage(AccountUpdateDetails, FixedWidthCodec)} - and
 * {@link WriteResult#acctUpdateRecordImage()} exposes the verbatim 300 characters so a field-by-field
 * differ sees what was actually staged rather than what a reader might assume.
 *
 * <p>{@code CUST-UPDATE-RECORD} ({@code :434-456}) has no such problem: it is byte-identical to
 * {@code app/cpy/CVCUS01Y.cpy} at all 500 bytes. Its caption says "(RECLN 300)", which is simply
 * wrong; the caption is a comment and is not reproduced anywhere.
 *
 * <h2>The four outcomes are one field, not four flags</h2>
 * <p>{@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} ({@code :517}),
 * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} ({@code :519}),
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} ({@code :521}) and {@code LOCKED-BUT-UPDATE-FAILED}
 * ({@code :523}) are <strong>not four flags</strong>. They are four {@code 88}-level condition names
 * on one {@code 05 WS-RETURN-MSG PIC X(75)} at {@code :479}, each naming a different message literal.
 * {@code SET ... TO TRUE} moves the literal in; {@code IF ...} compares the field against it. Three
 * consequences follow, and all three are modelled directly:
 * <ul>
 *   <li>the outcomes are <em>mutually exclusive by construction</em> - one field, one value - which is
 *       why {@link WriteOutcome} is a single discriminant rather than four booleans;</li>
 *   <li>the {@code IF WS-RETURN-MSG-OFF} guards at {@code :3911} and {@code :3939} exist to stop a
 *       lock failure overwriting an <em>earlier, more specific</em> message that some prior paragraph
 *       already placed - {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code :480} is that test;</li>
 *   <li>the caller's {@code EVALUATE TRUE} at {@code :2603-2614} is literally a first-match-wins
 *       comparison of that field against three of the four literals, with {@code WHEN OTHER} last - so
 *       {@link WriteOutcome}'s declaration order is the source's order, and it is load-bearing
 *       (gate G30).</li>
 * </ul>
 *
 * <p><strong>A second legacy defect, in that same {@code EVALUATE}.</strong> It has arms for
 * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}, {@code LOCKED-BUT-UPDATE-FAILED} and
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, and <strong>none for
 * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}</strong>. A customer record that cannot be locked therefore
 * falls through to {@code WHEN OTHER} and is reported to the operator as
 * {@code ACUP-CHANGES-OKAYED-AND-DONE} - success - even though nothing was written. That is
 * reproduced: {@link WriteOutcome#COULD_NOT_LOCK_CUST_FOR_UPDATE} carries the {@code 'C'} action code
 * and {@link WriteOutcome#firstMatchWinsPosition()} reports the {@code WHEN OTHER} position, while
 * {@link WriteOutcome#isRewritten()} still answers {@code false} so no caller can mistake the two for
 * the same thing.
 *
 * <h2>The lock-failure guard is on the message only, and the rewrite failures carry no guard</h2>
 * <p>Both lock arms run {@code SET INPUT-ERROR TO TRUE} unconditionally and set their message only
 * {@code IF WS-RETURN-MSG-OFF} ({@code :3910-3915} and {@code :3938-3943}). Both rewrite arms
 * ({@code :4076-4081} and {@code :4096-4103}) set {@code LOCKED-BUT-UPDATE-FAILED} with
 * <strong>no</strong> guard and do <strong>not</strong> set {@code INPUT-ERROR}. The asymmetry is in
 * the source and is preserved exactly.
 *
 * <p>The customer rewrite arm additionally issues {@code EXEC CICS SYNCPOINT ROLLBACK}
 * ({@code :4099-4101}) - the account rewrite that already succeeded is backed out. Java has no
 * {@code SYNCPOINT}, so {@link WriteResult#syncpointRollbackRequested()} reports it and the caller's
 * transaction boundary performs it; the flag is {@code true} for exactly one outcome, which is
 * asserted in {@link WriteResult}'s own constructor rather than left to convention.
 *
 * <h2>Numbers are {@link BigDecimal} at scale 2, truncated, and never a binary approximation</h2>
 * <p>The five monetary items are {@code PIC S9(10)V99} in both {@code CVACT01Y} and the
 * {@code ACUP-OLD} / {@code ACUP-NEW} groups, so every value here is scale
 * {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE} with
 * {@value #MONETARY_INTEGER_DIGITS} integer digits, stored through {@link CobolDecimal}. The keyword
 * {@code ROUNDED} appears <strong>zero</strong> times in all twenty-eight programs, so COBOL truncates
 * and {@link CobolDecimal#COBOL_ROUNDING} is the only faithful mode (gates G22, G24). Those two
 * absences - no binary approximation type, no round-half mode of any kind - are asserted by grep
 * rather than argued, so this paragraph deliberately names none of the forbidden tokens.
 *
 * <p>The monetary comparisons in {@code 9700} use {@link BigDecimal#compareTo(BigDecimal)} rather than
 * {@link BigDecimal#equals(Object)}. {@code equals} makes {@code 0.00} and {@code 0.0} unequal because
 * their scales differ, and a scale difference is not a change to a {@code PIC S9(10)V99} field.
 *
 * <h2>No state lives on this bean (practice B9, gate G53)</h2>
 * <p>{@code ACUP-OLD-DETAILS}, {@code ACUP-NEW-DETAILS}, {@code WS-XREF-RID},
 * {@code ACCT-UPDATE-RECORD} and {@code CUST-UPDATE-RECORD} are COBOL {@code WORKING-STORAGE}, and in
 * CICS that storage belongs to one task. Here they are <strong>parameters and return values</strong>,
 * never fields: this is a singleton, so a field would leak one request's account details into
 * another's and would make every test order-dependent. The only instance fields are the two injected
 * repositories, both {@code final}; every other member is {@code static final} and immutable.
 *
 * @see AccountRepository#readForUpdate(String)
 * @see AccountRepository#rewrite(AccountRecord)
 * @see CustomerRepository#readForUpdate(String)
 * @see CustomerRepository#rewrite(CustomerRecord)
 * @see com.vsergeychik.carddemo.card.CardUpdateService
 */
@Service
public class AccountUpdateService {

    /**
     * The log. Named for the class, and deliberately given nothing sensitive: this write path handles
     * social security numbers, dates of birth, government-issued identifiers, addresses and telephone
     * numbers, and none of them is ever logged. What is logged is the outcome name, the response pair
     * and - on the changed path - the <em>names</em> of the items that differed, which is what a
     * support engineer needs and is not itself customer data.
     */
    private static final Log LOG = LogFactory.getLog(AccountUpdateService.class);

    // =================================================================================================
    // Declared geometry and literals, each traceable to one line of app/cbl/COACTUPC.cbl. No width and
    // no literal is ever written as a bare value at a call site (practice B8).
    // =================================================================================================

    /**
     * {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '}
     * ({@code app/cbl/COACTUPC.cbl:573-574}) - the CICS file name both the read-for-update at
     * {@code :3895} and the rewrite at {@code :4066} name. The trailing space is part of the value,
     * because the item is eight characters wide and the name is seven.
     *
     * <p>Held for the diagnostics only, and it is a CICS <em>file</em> name rather than a dataset name.
     * The dataset itself is reached through {@link AccountRepository}, which resolves the binding from
     * configuration, so no hard-coded dataset name appears anywhere in this file (gate G46).
     */
    public static final String ACCT_CICS_FILE_NAME = "ACCTDAT ";

    /**
     * {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '}
     * ({@code app/cbl/COACTUPC.cbl:575-576}) - the CICS file name the read-for-update at {@code :3923}
     * and the rewrite at {@code :4086} name.
     */
    public static final String CUST_CICS_FILE_NAME = "CUSTDAT ";

    /**
     * {@code LENGTH OF WS-CARD-RID-ACCT-ID-X} ({@code app/cbl/COACTUPC.cbl:382-383,3898}): the eleven
     * characters of the {@code PIC X(11)} {@code REDEFINES} view over {@code WS-CARD-RID-ACCT-ID
     * PIC 9(11)} at {@code :381}. That is the {@code KEYLENGTH} the read-for-update states and the
     * width of the {@code ACCTDAT} primary key.
     *
     * <p>The record identification field travels as the <em>character</em> view, not the numeric one
     * (gate G34: two typed accessors over one span). {@code :3892} moves {@code CC-ACCT-ID} - itself
     * {@code PIC X(11)}, {@code app/cpy/CVCRD01Y.cpy:34} - into the numeric item, and {@code :3897}
     * hands the redefinition to {@code RIDFLD}. So a blank or partly typed screen field reaches the
     * file as spaces and the read simply reports no such record, which is a case the program has code
     * for; parsing the key as a number would turn that into a rejection the program never performs.
     */
    public static final int ACCT_KEY_LENGTH = AccountRepository.KEY_LENGTH;

    /**
     * {@code LENGTH OF WS-CARD-RID-CUST-ID-X} ({@code app/cbl/COACTUPC.cbl:379-380,3926}): the nine
     * characters of the {@code PIC X(09)} {@code REDEFINES} view over {@code WS-CARD-RID-CUST-ID
     * PIC 9(09)} at {@code :378}, and the width of the {@code CUSTDAT} primary key.
     */
    public static final int CUST_KEY_LENGTH = CustomerRepository.KEY_LENGTH;

    /**
     * {@code LENGTH OF ACCT-UPDATE-RECORD}: {@value}, the length the rewrite at
     * {@code app/cbl/COACTUPC.cbl:4069} states, and the same 300 that {@code app/cpy/CVACT01Y.cpy}
     * declares. The two records agree on their total and disagree on their offsets - see this class's
     * documentation.
     */
    public static final int ACCT_UPDATE_RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    /**
     * {@code LENGTH OF CUST-UPDATE-RECORD}: {@value}, the length the rewrite at
     * {@code app/cbl/COACTUPC.cbl:4089} states, and the same 500 that {@code app/cpy/CVCUS01Y.cpy}
     * declares. Here the layouts agree at every byte.
     */
    public static final int CUST_UPDATE_RECORD_LENGTH = CustomerRecord.RECORD_LENGTH;

    /**
     * The declared width of {@code WS-RETURN-MSG PIC X(75)}
     * ({@code app/cbl/COACTUPC.cbl:479}) - the single field that carries every outcome of this write
     * path, and the field whose all-spaces state {@code 88 WS-RETURN-MSG-OFF} at {@code :480} names.
     */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} ({@code app/cbl/COACTUPC.cbl:480}) rendered as the
     * value it names: {@value #RETURN_MESSAGE_LENGTH} spaces.
     */
    public static final String RETURN_MESSAGE_OFF = " ".repeat(RETURN_MESSAGE_LENGTH);

    /**
     * {@code 88 COULD-NOT-LOCK-ACCT-FOR-UPDATE} ({@code app/cbl/COACTUPC.cbl:517-518}), byte-exact.
     * Set at {@code :3912}, and only when the message slot is still off.
     */
    public static final String MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE =
            "Could not lock account record for update";

    /**
     * {@code 88 COULD-NOT-LOCK-CUST-FOR-UPDATE} ({@code app/cbl/COACTUPC.cbl:519-520}), byte-exact.
     * Set at {@code :3940}, and only when the message slot is still off.
     *
     * <p>The caller's {@code EVALUATE TRUE} at {@code :2603-2614} has no arm that tests this literal;
     * see {@link WriteOutcome#COULD_NOT_LOCK_CUST_FOR_UPDATE}.
     */
    public static final String MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE =
            "Could not lock customer record for update";

    /**
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} ({@code app/cbl/COACTUPC.cbl:521-522}), byte-exact.
     * Set at {@code :4146} or {@code :4189}, whichever comparison block failed first, and set
     * unconditionally - there is no {@code WS-RETURN-MSG-OFF} guard on either.
     */
    public static final String MSG_DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /**
     * {@code 88 LOCKED-BUT-UPDATE-FAILED} ({@code app/cbl/COACTUPC.cbl:523-524}), byte-exact. Set at
     * {@code :4079} when the account rewrite fails and at {@code :4098} when the customer rewrite
     * fails, in both cases unconditionally.
     */
    public static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    /**
     * The operation name a diagnostic reports for {@code EXEC CICS READ ... UPDATE}, matching
     * {@code ERROR-OPNAME} in the {@code WS-FILE-ERROR-MESSAGE} group at
     * {@code app/cbl/COACTUPC.cbl:392-395}.
     */
    public static final String READ_OPERATION_NAME = "READ";

    /**
     * The operation name a diagnostic reports for {@code EXEC CICS REWRITE}, matching
     * {@code ERROR-OPNAME} at {@code app/cbl/COACTUPC.cbl:392-395}.
     */
    public static final String REWRITE_OPERATION_NAME = "REWRITE";

    /**
     * {@code p} of {@code PIC S9(10)V99}: the ten digit positions left of the implied decimal point,
     * shared by all five monetary items of {@code CVACT01Y}, of {@code ACUP-OLD-ACCT-DATA}
     * ({@code app/cbl/COACTUPC.cbl:675-706}), of {@code ACUP-NEW-ACCT-DATA} ({@code :768-784}) and of
     * {@code ACCT-UPDATE-RECORD} ({@code :424-431}).
     */
    public static final int MONETARY_INTEGER_DIGITS = AccountRecord.MONETARY_INTEGER_DIGITS;

    /**
     * The stored width of a {@code PIC S9(10)V99} zoned {@code DISPLAY} item: twelve characters, the
     * sign carried as an overpunch on the last of them. This is also the declared width of the
     * {@code PIC X(12)} items that {@code ACUP-OLD-CURR-BAL-N} and its four siblings redefine.
     */
    public static final int MONETARY_IMAGE_LENGTH =
            MONETARY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE;

    /**
     * The declared width of {@code ACUP-OLD-OPEN-YEAR} and every other year part
     * ({@code app/cbl/COACTUPC.cbl:687}), and of the {@code (1:4)} slice of a stored ten-byte date.
     */
    public static final int DATE_YEAR_LENGTH = AccountRecord.YEAR_LENGTH;

    /**
     * The declared width of {@code ACUP-OLD-OPEN-MON}, {@code ACUP-OLD-OPEN-DAY} and every other month
     * or day part ({@code app/cbl/COACTUPC.cbl:688-689}), and of the {@code (6:2)} and {@code (9:2)}
     * slices of a stored ten-byte date.
     */
    public static final int DATE_PART_LENGTH = AccountRecord.MONTH_LENGTH;

    /**
     * The declared width of {@code ACUP-OLD-OPEN-DATE PIC X(08)}
     * ({@code app/cbl/COACTUPC.cbl:685}) and of {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)}
     * ({@code :747}): the separator-free {@code YYYYMMDD} form the screen snapshot holds, as against
     * the ten-character {@code YYYY-MM-DD} form the records hold.
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
     * ({@code app/cbl/COACTUPC.cbl:3977,3979}), and the character at positions 5 and 8 of every stored
     * date - the two positions {@code 9700} never compares.
     */
    public static final String DATE_SEPARATOR = "-";

    // -------------------------------------------------------------------------------------------------
    // ACCT-UPDATE-RECORD's own offsets, app/cbl/COACTUPC.cbl:418-433. These are NOT CVACT01Y's offsets
    // from ACCT_UPDATE_GROUP_ID_OFFSET onward; see this class's documentation for why that is a legacy
    // defect and why it is reproduced.
    // -------------------------------------------------------------------------------------------------

    /** {@code ACCT-UPDATE-ID PIC 9(11)} at {@code app/cbl/COACTUPC.cbl:422} - offset 0, width 11. */
    public static final int ACCT_UPDATE_ID_OFFSET = 0;

    /** {@code ACCT-UPDATE-ACTIVE-STATUS PIC X(01)} at {@code :423} - offset 11, width 1. */
    public static final int ACCT_UPDATE_ACTIVE_STATUS_OFFSET =
            ACCT_UPDATE_ID_OFFSET + AccountRecord.ACCT_ID_LENGTH;

    /** {@code ACCT-UPDATE-CURR-BAL PIC S9(10)V99} at {@code :424} - offset 12, width 12. */
    public static final int ACCT_UPDATE_CURR_BAL_OFFSET = ACCT_UPDATE_ACTIVE_STATUS_OFFSET
            + AccountRecord.ACCT_ACTIVE_STATUS_LENGTH;

    /** {@code ACCT-UPDATE-CREDIT-LIMIT PIC S9(10)V99} at {@code :425} - offset 24, width 12. */
    public static final int ACCT_UPDATE_CREDIT_LIMIT_OFFSET =
            ACCT_UPDATE_CURR_BAL_OFFSET + MONETARY_IMAGE_LENGTH;

    /** {@code ACCT-UPDATE-CASH-CREDIT-LIMIT PIC S9(10)V99} at {@code :426} - offset 36, width 12. */
    public static final int ACCT_UPDATE_CASH_CREDIT_LIMIT_OFFSET =
            ACCT_UPDATE_CREDIT_LIMIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /** {@code ACCT-UPDATE-OPEN-DATE PIC X(10)} at {@code :427} - offset 48, width 10. */
    public static final int ACCT_UPDATE_OPEN_DATE_OFFSET =
            ACCT_UPDATE_CASH_CREDIT_LIMIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /**
     * {@code ACCT-UPDATE-EXPIRAION-DATE PIC X(10)} at {@code :428} - offset 58, width 10. The name is
     * missing the {@code T} of {@code EXPIRATION}, exactly as {@code app/cpy/CVACT01Y.cpy:11} spells
     * it. No correctly-spelled form exists anywhere in the reference tree, and the parity differ
     * compares fields <em>by name</em>, so renaming it would make a genuine difference invisible to
     * the only check able to catch it (implicit requirement I1).
     */
    public static final int ACCT_UPDATE_EXPIRAION_DATE_OFFSET =
            ACCT_UPDATE_OPEN_DATE_OFFSET + STORED_DATE_LENGTH;

    /** {@code ACCT-UPDATE-REISSUE-DATE PIC X(10)} at {@code :429} - offset 68, width 10. */
    public static final int ACCT_UPDATE_REISSUE_DATE_OFFSET =
            ACCT_UPDATE_EXPIRAION_DATE_OFFSET + STORED_DATE_LENGTH;

    /** {@code ACCT-UPDATE-CURR-CYC-CREDIT PIC S9(10)V99} at {@code :430} - offset 78, width 12. */
    public static final int ACCT_UPDATE_CURR_CYC_CREDIT_OFFSET =
            ACCT_UPDATE_REISSUE_DATE_OFFSET + STORED_DATE_LENGTH;

    /** {@code ACCT-UPDATE-CURR-CYC-DEBIT PIC S9(10)V99} at {@code :431} - offset 90, width 12. */
    public static final int ACCT_UPDATE_CURR_CYC_DEBIT_OFFSET =
            ACCT_UPDATE_CURR_CYC_CREDIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /**
     * {@code ACCT-UPDATE-GROUP-ID PIC X(10)} at {@code :432} - offset <strong>102</strong>, width 10.
     *
     * <p>This is the defect. {@code app/cpy/CVACT01Y.cpy:16} places {@code ACCT-ADDR-ZIP} at offset
     * 102 and {@code ACCT-GROUP-ID} at 112, and {@code ACCT-UPDATE-RECORD} declares no zip item at
     * all, so the group identifier is written ten bytes early - straight over the stored zip - and the
     * real group span is left inside the trailing reserved area. Asserted rather than assumed:
     * {@code ACCT_UPDATE_GROUP_ID_OFFSET} equals {@link AccountRecord#ACCT_ADDR_ZIP_OFFSET}, which is
     * checked in this class's static initialiser.
     */
    public static final int ACCT_UPDATE_GROUP_ID_OFFSET =
            ACCT_UPDATE_CURR_CYC_DEBIT_OFFSET + MONETARY_IMAGE_LENGTH;

    /** The offset of {@code FILLER PIC X(188)} at {@code :433} - offset 112. */
    public static final int ACCT_UPDATE_FILLER_OFFSET =
            ACCT_UPDATE_GROUP_ID_OFFSET + AccountRecord.ACCT_GROUP_ID_LENGTH;

    /**
     * The declared width of {@code FILLER PIC X(188)} at {@code app/cbl/COACTUPC.cbl:433} -
     * {@value}, ten bytes wider than {@code CVACT01Y}'s {@code FILLER PIC X(178)} because
     * {@code ACCT-UPDATE-RECORD} carries no {@code ACCT-ADDR-ZIP}.
     *
     * <p>The span is emitted as {@value} spaces (gate G21). Two source facts make that a decision
     * rather than a default, and both are worth stating: COBOL's unqualified {@code INITIALIZE} at
     * {@code :3956} does <em>not</em> touch {@code FILLER} items, and a {@code WORKING-STORAGE} item
     * declared without a {@code VALUE} clause has no standard-defined initial content. Gate G21 fixes
     * the answer at spaces, which makes the 300-byte image deterministic and makes the total width
     * fail immediately if the span is ever omitted.
     */
    public static final int ACCT_UPDATE_FILLER_LENGTH =
            ACCT_UPDATE_RECORD_LENGTH - ACCT_UPDATE_FILLER_OFFSET;

    /**
     * The value {@link #testNumvalC(String)} returns for an argument that conforms - the same
     * {@code 0} the five {@code IF FUNCTION TEST-NUMVAL-C(...) = 0} guards at
     * {@code app/cbl/COACTUPC.cbl:1078,1092,1106,1120,1134} test for.
     *
     * <p>Read from {@link NumericIntrinsics} rather than restated, so the constant and the scan that
     * produces it cannot drift apart.
     */
    public static final int NUMVAL_CONFORMS = NumericIntrinsics.CONFORMS;

    /**
     * The declared width of the five monetary screen fields - {@code ACRDLIMI}, {@code ACSHLIMI},
     * {@code ACURBALI}, {@code ACRCYCRI} and {@code ACRCYDBI}, all {@code PIC X(15)} in
     * {@code app/cpy-bms/COACTUP.CPY:90,114,138,144,156} - and of the five
     * {@code ALPHA-VARS-FOR-DATA-EDITING} staging items at {@code app/cbl/COACTUPC.cbl:412-416}.
     *
     * <p><strong>Fifteen, not twelve.</strong> The migration brief describes the {@code -N} items as
     * numeric redefinitions of "the {@code X(15)} screen fields"; they are not. The staging items
     * {@code ACUP-NEW-CREDIT-LIMIT-X} and its four siblings are {@code PIC X(15)} and live in
     * {@code 05 ALPHA-VARS-FOR-DATA-EDITING} under {@code WS-MISC-STORAGE} ({@code :411-416}). The
     * {@code -N} items redefine a <em>different</em>, {@value #MONETARY_IMAGE_LENGTH}-character span -
     * {@code ACUP-NEW-CREDIT-LIMIT PIC X(12)} inside {@code ACUP-NEW-ACCT-DATA} ({@code :768-770}),
     * which is a separate {@code 01} group. One consequence is visible in the source:
     * {@code INITIALIZE ACUP-NEW-DETAILS} at {@code :1047} clears the twelve-character spans and
     * leaves the fifteen-character staging items untouched (practice B4).
     */
    public static final int SCREEN_MONETARY_LENGTH = 15;

    /**
     * {@code LOW-VALUES} at the width of a monetary screen field: {@value #SCREEN_MONETARY_LENGTH}
     * copies of the lowest character in the collating sequence.
     *
     * <p>{@code MOVE LOW-VALUES TO ACUP-NEW-CREDIT-LIMIT-X} ({@code app/cbl/COACTUPC.cbl:1075}) is what
     * the not-supplied arm performs, and {@code 1250-EDIT-SIGNED-9V2} then recognises it with
     * {@code IF WS-EDIT-SIGNED-NUMBER-9V2-X EQUAL LOW-VALUES} at {@code :2184}. Spaces would <em>also</em>
     * satisfy that test, so the distinction makes no difference to the edit's outcome - but it is a
     * different byte image, and a field-by-field differ compares bytes.
     */
    public static final String LOW_VALUES_IMAGE =
            String.valueOf('\u0000').repeat(SCREEN_MONETARY_LENGTH);

    /**
     * The character {@code 1100-RECEIVE-MAP} treats as "not supplied" alongside spaces
     * ({@code app/cbl/COACTUPC.cbl:1073,1087,1101,1115,1129}). It is also
     * {@code FieldAttributeSetter}'s error marker, which is how it gets onto the screen in the first
     * place: a field the previous pass flagged comes back carrying {@code '*'}.
     */
    public static final String NOT_SUPPLIED_MARKER = "*";

    /** The opening parenthesis the two telephone {@code STRING}s emit ({@code :4035,4043}). */
    private static final String PHONE_OPEN = "(";

    /** The closing parenthesis the two telephone {@code STRING}s emit ({@code :4037,4045}). */
    private static final String PHONE_CLOSE = ")";

    /** The hyphen the two telephone {@code STRING}s emit ({@code :4039,4047}). */
    private static final String PHONE_HYPHEN = "-";

    /** A single space, the pad character of every {@code PIC X} receiver. */
    private static final char SPACE = ' ';

    /** The decimal point {@code FUNCTION NUMVAL-C} accepts. */
    private static final char DECIMAL_POINT = '.';

    /** The digit-grouping separator {@code FUNCTION NUMVAL-C} accepts inside the integer part. */
    private static final char DIGIT_SEPARATOR = ',';

    /** The default currency sign {@code FUNCTION NUMVAL-C} accepts; no {@code CURRENCY} clause overrides it. */
    private static final char CURRENCY_SIGN = '$';

    /** The plus sign {@code FUNCTION NUMVAL-C} accepts in either the leading or the trailing position. */
    private static final char PLUS_SIGN = '+';

    /** The minus sign {@code FUNCTION NUMVAL-C} accepts in either the leading or the trailing position. */
    private static final char MINUS_SIGN = '-';

    /** The trailing credit indicator {@code FUNCTION NUMVAL-C} treats as a negative sign. */
    private static final String CREDIT_INDICATOR = "CR";

    /** The trailing debit indicator {@code FUNCTION NUMVAL-C} treats as a negative sign. */
    private static final String DEBIT_INDICATOR = "DB";

    /** One, named so the {@code NUMVAL-C} scan reads as a scan rather than as arithmetic. */
    private static final int ONE = 1;

    static {
        // The defect is asserted, not assumed. If a later reader "corrects" ACCT-UPDATE-RECORD by
        // inserting the missing ACCT-ADDR-ZIP item, this fails at class-initialisation time with an
        // explanation, rather than silently changing two hundred bytes of every rewritten record.
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

    // =================================================================================================
    // Injected collaborators. Two repositories, both final, and nothing else (practice B9, gate G53).
    // =================================================================================================

    /**
     * The {@code ACCTDAT} dataset, reached through the repository that resolves its binding from
     * configuration. Backs {@code EXEC CICS READ ... UPDATE FILE(LIT-ACCTFILENAME)} at
     * {@code app/cbl/COACTUPC.cbl:3894-3903} and {@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME)} at
     * {@code :4065-4071}.
     */
    private final AccountRepository accountRepository;

    /**
     * The {@code CUSTDAT} dataset. Backs {@code EXEC CICS READ ... UPDATE FILE(LIT-CUSTFILENAME)} at
     * {@code app/cbl/COACTUPC.cbl:3922-3931} and {@code EXEC CICS REWRITE FILE(LIT-CUSTFILENAME)} at
     * {@code :4085-4091}.
     */
    private final CustomerRepository customerRepository;

    /**
     * Constructor injection, which is the only injection this class supports: both collaborators are
     * {@code final}, so a partially wired instance cannot exist and a test can supply two mocks
     * without a Spring context (practice B9, gate G53).
     *
     * @param accountRepository  the {@code ACCTDAT} dataset; must not be {@code null}
     * @param customerRepository the {@code CUSTDAT} dataset; must not be {@code null}
     * @throws NullPointerException if either repository is {@code null}
     */
    /**
     * What the unit of work is doing, for a failure to name it by.
     *
     * <p>Names the paragraph and the two datasets, because a unit of work that fails to commit has to be
     * traceable to the COBOL it was reproducing.
     */
    private static final String UNIT_OF_WORK_DESCRIPTION =
            "9600-WRITE-PROCESSING (app/cbl/COACTUPC.cbl:3889-4106): lock ACCTDAT and CUSTDAT, compare "
                    + "both against the painted screen, and rewrite both at full declared width";

    /**
     * The unit-of-work boundary the whole lock, compare and rewrite sequence runs inside.
     *
     * <p><strong>Why the service owns the boundary and not the caller.</strong> In CICS a task always has
     * a unit of work: {@code EXEC CICS READ ... UPDATE} at {@code app/cbl/COACTUPC.cbl:3894} takes a lock
     * that the task holds until its syncpoint, which is what lets {@code 9700-CHECK-CHANGE-IN-REC}
     * compare at {@code :3947} and {@code REWRITE} write at {@code :4065} against a record nobody else
     * could have moved in between. This paragraph <em>is</em> the task's body, so the boundary belongs
     * here. Left to a caller it was nowhere: {@link AccountRepository#readForUpdate(String)} refuses to
     * issue {@code FOR UPDATE} outside a transaction - correctly, because the lock would end with the
     * statement - so the write path could not execute at all.
     *
     * <p>One boundary for the whole sequence, not one per operation. Two units of work would release the
     * account lock before the customer record was read, which is precisely the interleaving the
     * concurrency check exists to detect and cannot detect from inside.
     */
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

    // =================================================================================================
    // 9600-WRITE-PROCESSING, app/cbl/COACTUPC.cbl:3889-4106. Seven steps, in the source's order.
    // =================================================================================================

    /**
     * {@code 9600-WRITE-PROCESSING} ({@code app/cbl/COACTUPC.cbl:3889-4106}): locks the account and
     * customer records, refuses the update if either lock fails or if either record changed under the
     * screen, and otherwise stages and rewrites both.
     *
     * <p>Seven steps, and the order is the contract:
     * <ol>
     *   <li><strong>{@code :3892}</strong> {@code MOVE CC-ACCT-ID TO WS-CARD-RID-ACCT-ID}, and
     *       {@code :3897} hands the {@code PIC X(11)} redefinition {@code WS-CARD-RID-ACCT-ID-X} to
     *       {@code RIDFLD}. Eleven characters, not a number;</li>
     *   <li><strong>{@code :3894-3903}</strong> {@code EXEC CICS READ FILE(LIT-ACCTFILENAME) UPDATE},
     *       then {@code :3907-3916}: on anything but {@code DFHRESP(NORMAL)},
     *       {@code SET INPUT-ERROR TO TRUE} unconditionally and
     *       {@code SET COULD-NOT-LOCK-ACCT-FOR-UPDATE TO TRUE} <em>only if</em>
     *       {@code WS-RETURN-MSG-OFF}, then {@code GO TO 9600-WRITE-PROCESSING-EXIT}. The customer file
     *       is never touched on this path;</li>
     *   <li><strong>{@code :3920}</strong> {@code MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID}, then
     *       <strong>{@code :3922-3931}</strong> the identical read against
     *       {@code LIT-CUSTFILENAME}, with the identical guard at {@code :3935-3944} setting
     *       {@code COULD-NOT-LOCK-CUST-FOR-UPDATE};</li>
     *   <li><strong>{@code :3947-3952}</strong> {@code PERFORM 9700-CHECK-CHANGE-IN-REC THRU
     *       9700-CHECK-CHANGE-IN-REC-EXIT}, then {@code IF DATA-WAS-CHANGED-BEFORE-UPDATE GO TO
     *       9600-WRITE-PROCESSING-EXIT} - return with nothing written;</li>
     *   <li><strong>{@code :3956-4001}</strong> {@code INITIALIZE ACCT-UPDATE-RECORD} and the eleven
     *       account moves, three of which are {@code STRING ... DELIMITED BY SIZE} date compositions;
     *       then <strong>{@code :4006-4061}</strong> {@code INITIALIZE CUST-UPDATE-RECORD} and the
     *       eighteen customer moves, three of which are {@code STRING}s;</li>
     *   <li><strong>{@code :4065-4081}</strong> {@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME)} at
     *       full declared length, then {@code IF} not normal
     *       {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} - with <em>no</em>
     *       {@code WS-RETURN-MSG-OFF} guard and <em>no</em> {@code SET INPUT-ERROR};</li>
     *   <li><strong>{@code :4085-4103}</strong> the customer rewrite, whose failure arm additionally
     *       issues {@code EXEC CICS SYNCPOINT ROLLBACK} to back the account rewrite out.</li>
     * </ol>
     *
     * <p><strong>The account rewrite writes {@code ACCT-UPDATE-RECORD}'s layout, not
     * {@code CVACT01Y}'s.</strong> See this class's documentation: the staged image carries the group
     * identifier at offset {@value #ACCT_UPDATE_GROUP_ID_OFFSET}, over the stored
     * {@code ACCT-ADDR-ZIP}, and blanks the real {@code ACCT-GROUP-ID} span. That is what the COBOL
     * does and it is reproduced.
     *
     * <p><strong>Nothing is mutated.</strong> {@code oldDetails} and {@code newDetails} are immutable
     * records and are only read; the staged images come back on the result. The two records read under
     * the lock are consumed by the change check and are not written to.
     *
     * @param ccAcctId          {@code CC-ACCT-ID PIC X(11)} ({@code app/cpy/CVCRD01Y.cpy:34}) - the
     *                          work area's account identifier, which {@code :3892} moves into the
     *                          record identification field. Taken as characters and neither trimmed nor
     *                          parsed; {@code null} is read as the not-supplied state and becomes
     *                          {@value #ACCT_KEY_LENGTH} spaces
     * @param navigationContext the {@code COCOM01Y} commarea, read for {@code CDEMO-CUST-ID} at
     *                          {@code :3920} and for nothing else. Not modified
     * @param oldDetails        {@code ACUP-OLD-DETAILS} ({@code :669-756}) - the snapshot the screen was
     *                          painted from, and what {@code 9700-CHECK-CHANGE-IN-REC} compares
     *                          against. Must be the {@link DetailGroup#OLD} group
     * @param newDetails        {@code ACUP-NEW-DETAILS} ({@code :757-855}) - what the user typed, and
     *                          the source of every staged value. Must be the {@link DetailGroup#NEW}
     *                          group
     * @param returnMessage     the current content of {@code WS-RETURN-MSG} ({@code :479}), which
     *                          decides the two {@code :3911} and {@code :3939} guards. {@code null} is
     *                          accepted and means the cleared state {@code :876} establishes on every
     *                          pass - see {@link #isReturnMessageOff(String)}
     * @param codec             the codec carrying the code page, and the owner of every pad, truncate
     *                          and concatenate rule used here
     * @return the outcome, never {@code null}
     * @throws NullPointerException     if {@code navigationContext}, {@code oldDetails},
     *                                  {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code oldDetails} is not the {@link DetailGroup#OLD} group or
     *                                  {@code newDetails} is not the {@link DetailGroup#NEW} group
     */
    public WriteResult writeProcessing(String ccAcctId,
                                      NavigationContext navigationContext,
                                      AccountUpdateDetails oldDetails,
                                      AccountUpdateDetails newDetails,
                                      String returnMessage,
                                      FixedWidthCodec codec) {
        // The argument checks sit OUTSIDE the boundary deliberately. A wrong detail group or a missing
        // codec is a programming error, not dataset work, and opening a transaction to reject one would
        // put a connection behind a failure that never touches a dataset.
        Objects.requireNonNull(navigationContext, "The COCOM01Y commarea is required: "
                + "9600-WRITE-PROCESSING reads CDEMO-CUST-ID from it at app/cbl/COACTUPC.cbl:3920 to "
                + "build the CUSTDAT record identification field");
        Objects.requireNonNull(codec, "A codec is required: every move in this paragraph is a COBOL "
                + "MOVE with a declared width, and the codec owns the pad and truncate rules");
        requireGroup(oldDetails, DetailGroup.OLD, "ACUP-OLD-DETAILS", "669-756");
        requireGroup(newDetails, DetailGroup.NEW, "ACUP-NEW-DETAILS", "757-855");

        try {
            return unitOfWork.execute(UNIT_OF_WORK_DESCRIPTION, () -> {
                WriteResult result = writeProcessingUnderLock(ccAcctId, navigationContext, oldDetails,
                        newDetails, returnMessage, codec);
                if (result.syncpointRollbackRequested()) {
                    // :4099-4101 EXEC CICS SYNCPOINT ROLLBACK. The account rewrite has already
                    // succeeded and the customer rewrite has not, so the account change must not stand.
                    //
                    // A throw is the rollback, and it is this module's established mechanism rather than
                    // a second one: DatasetUnitOfWork.commitRefusal documents that
                    // TransactionAspectSupport.currentTransactionStatus() raises NoTransactionException
                    // inside a TransactionTemplate body - the template does not publish its status - so a
                    // rollback-only flag is not reachable from here, and publishing one through a
                    // thread-local of our own would be the static mutable state practice B9 forbids.
                    // TransactionTemplate rolls back on any unchecked exception, so throwing IS the
                    // SYNCPOINT ROLLBACK.
                    //
                    // The COBOL then falls through to 9600-WRITE-PROCESSING-EXIT and its caller reports
                    // LOCKED-BUT-UPDATE-FAILED, so the result has to survive the rollback: it travels on
                    // the throw and is returned below. That is a rollback the task continues past, which
                    // is exactly what EXEC CICS SYNCPOINT ROLLBACK is.
                    throw new SyncpointRollback(result);
                }
                return result;
            });
        } catch (SyncpointRollback rolledBack) {
            return rolledBack.result();
        }
    }

    /**
     * {@code 9600-WRITE-PROCESSING}'s body, running inside the unit of work its locks need.
     *
     * <p>Every step is the source's, in the source's order. The only thing this method does not do is
     * open or close the boundary, which is {@link #writeProcessing} above.
     *
     * @param ccAcctId          the work area's account identifier
     * @param navigationContext the commarea, read for {@code CDEMO-CUST-ID}
     * @param oldDetails        the snapshot the screen was painted from
     * @param newDetails        what the user typed
     * @param returnMessage     the current {@code WS-RETURN-MSG}
     * @param codec             the codec carrying the code page and the move rules
     * @return the outcome; never {@code null}
     */
    private WriteResult writeProcessingUnderLock(String ccAcctId,
                                                 NavigationContext navigationContext,
                                                 AccountUpdateDetails oldDetails,
                                                 AccountUpdateDetails newDetails,
                                                 String returnMessage,
                                                 FixedWidthCodec codec) {
        String pendingMessage = atReturnMessageWidth(returnMessage);

        // Step 1, :3892 and :3897. The RID travels as the X(11) REDEFINES view, so a blank or
        // partly-typed screen field reaches the file as spaces rather than as a parse failure.
        String accountRid = acctRidImage(ccAcctId, codec);

        // Step 2, :3894-3903. EXEC CICS READ ... UPDATE. The lock, and the record to compare against.
        AccountRepository.ReadResult accountRead = accountRepository.readForUpdate(accountRid);

        // Step 3, :3907-3916. Could we lock the account record? Every non-normal response lands on the
        // same arm and abandons the update - a deleted record included.
        if (!accountRead.isFound()) {
            // :3910 SET INPUT-ERROR TO TRUE - unconditional.
            // :3911-3913 SET COULD-NOT-LOCK-ACCT-FOR-UPDATE TO TRUE - ONLY when the message is off.
            // Both statements sit inside the ELSE, but only the second sits inside the IF, and that
            // difference is the whole subtlety of this arm: a more specific message placed by an
            // earlier paragraph survives.
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
            // :3915 GO TO 9600-WRITE-PROCESSING-EXIT.
            return new WriteResult(WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE, true, false, message,
                    accountRead.status(), accountRead.cicsResp(), Optional.of(READ_OPERATION_NAME),
                    Optional.of(ACCT_CICS_FILE_NAME), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        // The lock was taken, so the record is present. ReadResult's own constructor rejects a found
        // result with no record, so this states the invariant rather than re-testing it.
        AccountRecord lockedAccount = accountRead.account().orElseThrow(
                () -> new IllegalStateException("A found ACCTDAT read carries the decoded record; "
                        + "AccountRepository.ReadResult enforces that at construction, so reaching "
                        + "here means the contract was bypassed"));

        // Step 4, :3920 and :3926. MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID, then RIDFLD takes the
        // X(09) redefinition. CDEMO-CUST-ID is PIC 9(09), so this is a numeric-to-numeric move and the
        // key is zero-filled to nine digits - unlike the account key, which starts life as characters.
        String customerRid = custRidImage(navigationContext.custId(), codec);

        // Step 5, :3922-3931. The identical read against CUSTDAT.
        CustomerRepository.ReadResult customerRead = customerRepository.readForUpdate(customerRid);

        // Step 6, :3935-3944. Could we lock the customer record? Same shape, same guard, different
        // message.
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
            // :3943 GO TO 9600-WRITE-PROCESSING-EXIT.
            return new WriteResult(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE, true, false, message,
                    customerRead.status(), customerRead.cicsResp(), Optional.of(READ_OPERATION_NAME),
                    Optional.of(CUST_CICS_FILE_NAME), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        CustomerRecord lockedCustomer = customerRead.customer().orElseThrow(
                () -> new IllegalStateException("A found CUSTDAT read carries the decoded record; "
                        + "CustomerRepository.ReadResult enforces that at construction, so reaching "
                        + "here means the contract was bypassed"));

        // Step 7, :3947-3952. PERFORM 9700-CHECK-CHANGE-IN-REC THRU ...-EXIT, then the caller-side
        // guard. The paragraph's own GO TO at :4147 and :4190 leaves the PERFORM range and lands on
        // 9600-WRITE-PROCESSING-EXIT, so the explicit IF at :3950-3952 is deliberately redundant with
        // it. It is kept because the source keeps it: both paths mean "do not rewrite", and the
        // explicit test documents that (practice B5).
        ChangeCheck check = checkChangeInRec(lockedAccount, lockedCustomer, oldDetails, codec);
        if (check.dataWasChanged()) {
            LOG.info("A record changed after the screen was painted, so the update is refused and "
                    + "nothing has been written. " + check.describeDifferences());
            return new WriteResult(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE, false, false,
                    atReturnMessageWidth(MSG_DATA_WAS_CHANGED_BEFORE_UPDATE), FileStatus.OK,
                    OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(check));
        }

        // Step 8, :3956-4061. Stage both records. Both are staged before either rewrite is issued,
        // which is why a failed account rewrite still reports a staged customer image.
        String accountImage = stageAccountUpdateImage(newDetails, codec);
        String customerImage = stageCustomerUpdateImage(newDetails, codec);

        // Step 9, :4065-4071. EXEC CICS REWRITE FILE(LIT-ACCTFILENAME) FROM(ACCT-UPDATE-RECORD)
        // LENGTH(LENGTH OF ACCT-UPDATE-RECORD) - a full-width 300-byte rewrite, the FILLER included.
        AccountRepository.WriteResult accountWritten =
                accountRepository.rewrite(AccountRecord.decode(accountImage, codec.charset()));

        // Step 10, :4076-4081. Did the account update succeed? No WS-RETURN-MSG-OFF guard on this arm
        // and no SET INPUT-ERROR either - unlike steps 3 and 6.
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

        // Step 11, :4085-4091. The customer rewrite, also at full declared length.
        CustomerRepository.WriteResult customerWritten =
                customerRepository.rewrite(CustomerRecord.decode(customerImage, codec.charset()));

        // Step 12, :4096-4103. Did the customer update succeed? Same message as step 10, plus
        // EXEC CICS SYNCPOINT ROLLBACK at :4099-4101 to back the account rewrite out. Java has no
        // SYNCPOINT, so the request is reported and the caller's transaction boundary performs it.
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

        // Fall through to the paragraph's exit with no message condition set, which is precisely the
        // caller's WHEN OTHER at :2612-2613: SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE.
        LOG.info("An " + ACCT_CICS_FILE_NAME.trim() + " record was rewritten at its full "
                + ACCT_UPDATE_RECORD_LENGTH + " bytes and a " + CUST_CICS_FILE_NAME.trim()
                + " record at its full " + CUST_UPDATE_RECORD_LENGTH + " bytes.");
        return new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false, pendingMessage,
                customerWritten.status(), customerWritten.cicsResp(), Optional.empty(),
                Optional.empty(), Optional.of(accountImage), Optional.of(customerImage),
                Optional.of(check));
    }

    // =================================================================================================
    // 9700-CHECK-CHANGE-IN-REC, app/cbl/COACTUPC.cbl:4109-4194. The concurrency control itself.
    // =================================================================================================

    /**
     * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4109-4194}): decides whether the
     * two records that were just locked still match the snapshot the screen was painted from.
     *
     * <p>The paragraph is two {@code IF ... CONTINUE ELSE SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE /
     * GO TO 9600-WRITE-PROCESSING-EXIT END-IF} blocks. Both are single conditions joined entirely with
     * {@code AND}, so each block passes only when every one of its comparisons holds, and the
     * <strong>first failing block short-circuits the paragraph</strong> - the {@code GO TO} at
     * {@code :4147} leaves before the customer block at {@code :4152} is ever evaluated. That ordering
     * is reproduced exactly (gate G30), and it is observable: when both records changed, the reported
     * differences name account items only.
     *
     * <p><strong>Block 1, the account master ({@code :4115-4148}), sixteen comparisons.</strong>
     * <ul>
     *   <li>{@code ACCT-ACTIVE-STATUS} exactly, {@code PIC X(01)} against {@code PIC X(01)};</li>
     *   <li>the five monetary items - balance, credit limit, cash credit limit, cycle credit, cycle
     *       debit - as {@code PIC S9(10)V99} against the {@code ACUP-OLD-...-N} redefinitions of the
     *       {@code PIC X(12)} snapshot spans. Compared with
     *       {@link BigDecimal#compareTo(BigDecimal)}, never {@link BigDecimal#equals(Object)}, so a
     *       scale difference cannot manufacture a mismatch;</li>
     *   <li>three slices each of {@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} - misspelled in
     *       the copybook and misspelled here - and {@code ACCT-REISSUE-DATE}, at {@code (1:4)},
     *       {@code (6:2)} and {@code (9:2)}, against the named year, month and day parts of the
     *       corresponding {@code PIC X(08)} snapshot item. The {@code '-'} separators at positions 5
     *       and 8 are never compared;</li>
     *   <li>{@code ACCT-GROUP-ID} through {@code FUNCTION LOWER-CASE} on <em>both</em> sides
     *       ({@code :4144-4145}) - lower, in contrast to block 2's upper.</li>
     * </ul>
     *
     * <p><strong>Block 2, the customer ({@code :4152-4191}), nineteen comparisons.</strong> Eight are
     * folded with {@code FUNCTION UPPER-CASE}: the three name parts, the three address lines, the state
     * code and the country code, plus the government-issued identifier at {@code :4180-4181}. Eight are
     * compared exactly and unfolded: the postal code, both telephone numbers, the social security
     * number, the electronic funds transfer account identifier, the primary-cardholder indicator and
     * the credit score. The remaining three are the date-of-birth slices, whose asymmetric offsets are
     * explained in this class's documentation.
     *
     * <p><strong>Neither record is mutated.</strong> {@code COCRDUPC}'s equivalent paragraph folds the
     * embossed name in the record area with {@code INSPECT ... CONVERTING} before comparing;
     * {@code COACTUPC} does no such thing - it wraps both operands in {@code FUNCTION} calls, which
     * produce values and leave storage alone. So there is no refreshed snapshot to hand back here, and
     * {@link ChangeCheck} carries none.
     *
     * @param account     {@code ACCOUNT-RECORD} as read under the lock at
     *                    {@code app/cbl/COACTUPC.cbl:3899}; must not be {@code null}
     * @param customer    {@code CUSTOMER-RECORD} as read under the lock at {@code :3927}; must not be
     *                    {@code null}
     * @param oldDetails  {@code ACUP-OLD-DETAILS} ({@code :669-756}) - the snapshot the screen was
     *                    painted from. Must be the {@link DetailGroup#OLD} group
     * @param codec       the codec carrying the code page; used to render the record's
     *                    {@code PIC S9(10)V99} spans and its declared widths consistently with the
     *                    snapshot's
     * @return what the comparison concluded, never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
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

        // Block 1, :4115-4148. Evaluated first, and its failure means block 2 is never reached.
        Set<ComparedItem> accountDifferences = compareAccountMaster(account, oldDetails.acctData());
        if (!accountDifferences.isEmpty()) {
            // :4146 SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE, then :4147 GO TO
            // 9600-WRITE-PROCESSING-EXIT - a non-local jump out of the PERFORM range and into the
            // caller's exit label. Reported as a status here, which has the same observable outcome.
            return ChangeCheck.changed(Block.ACCOUNT_MASTER, accountDifferences);
        }

        // Block 2, :4152-4192. Reached only when every one of block 1's sixteen comparisons held.
        Set<ComparedItem> customerDifferences = compareCustomer(customer, oldDetails.custData(), codec);
        if (!customerDifferences.isEmpty()) {
            return ChangeCheck.changed(Block.CUSTOMER, customerDifferences);
        }

        // Both blocks reached their CONTINUE, so nothing changed and the caller stages and rewrites.
        return ChangeCheck.unchanged();
    }

    /**
     * Block 1 of {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4115-4145}): the sixteen
     * account-master comparisons, evaluated in source order.
     *
     * <p>Every comparison is evaluated even after one has failed. That is deliberate and it is not a
     * departure: all sixteen are pure comparisons of values already in hand, so evaluating them all
     * cannot be observed, and doing so lets {@link ChangeCheck#describeDifferences()} name every item
     * that differs rather than only the first. What <em>is</em> observable - that block 2 is skipped
     * entirely - is preserved by the caller.
     *
     * @param account the record read under the lock
     * @param old     the {@code ACUP-OLD-ACCT-DATA} snapshot
     * @return the items that differ, in declaration order; empty when the block reached its
     *         {@code CONTINUE}
     */
    private static Set<ComparedItem> compareAccountMaster(AccountRecord account, AccountData old) {
        Set<ComparedItem> differences = EnumSet.noneOf(ComparedItem.class);

        // :4115 IF ACCT-ACTIVE-STATUS EQUAL ACUP-OLD-ACTIVE-STATUS. PIC X(01) both sides.
        addIfDiffers(differences, ComparedItem.ACCT_ACTIVE_STATUS,
                account.getAcctActiveStatus().equals(old.activeStatus()));

        // :4117-4127 the five monetary items, PIC S9(10)V99 against the ACUP-OLD-...-N redefinitions.
        // compareTo, not equals: a scale difference is not a change to a scale-2 field.
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

        // :4129-4131 ACCT-OPEN-DATE(1:4)/(6:2)/(9:2) against the named ACUP-OLD-OPEN-YEAR/-MON/-DAY
        // parts. The record getters own the one-based to zero-based conversion.
        addIfDiffers(differences, ComparedItem.ACCT_OPEN_DATE_YEAR,
                account.getAcctOpenDateYear().equals(old.openYear()));
        addIfDiffers(differences, ComparedItem.ACCT_OPEN_DATE_MONTH,
                account.getAcctOpenDateMonth().equals(old.openMon()));
        addIfDiffers(differences, ComparedItem.ACCT_OPEN_DATE_DAY,
                account.getAcctOpenDateDay().equals(old.openDay()));

        // :4133-4135 the same triple for ACCT-EXPIRAION-DATE. The name is missing the T of EXPIRATION
        // in app/cpy/CVACT01Y.cpy:11, in app/cbl/COACTUPC.cbl:681 and here (implicit requirement I1).
        addIfDiffers(differences, ComparedItem.ACCT_EXPIRAION_DATE_YEAR,
                account.getAcctExpiraionDateYear().equals(old.expYear()));
        addIfDiffers(differences, ComparedItem.ACCT_EXPIRAION_DATE_MONTH,
                account.getAcctExpiraionDateMonth().equals(old.expMon()));
        addIfDiffers(differences, ComparedItem.ACCT_EXPIRAION_DATE_DAY,
                account.getAcctExpiraionDateDay().equals(old.expDay()));

        // :4137-4139 the same triple for ACCT-REISSUE-DATE.
        addIfDiffers(differences, ComparedItem.ACCT_REISSUE_DATE_YEAR,
                account.getAcctReissueDateYear().equals(old.reissueYear()));
        addIfDiffers(differences, ComparedItem.ACCT_REISSUE_DATE_MONTH,
                account.getAcctReissueDateMonth().equals(old.reissueMon()));
        addIfDiffers(differences, ComparedItem.ACCT_REISSUE_DATE_DAY,
                account.getAcctReissueDateDay().equals(old.reissueDay()));

        // :4141-4145 FUNCTION LOWER-CASE (ACCT-GROUP-ID) EQUAL FUNCTION LOWER-CASE (ACUP-OLD-GROUP-ID).
        // LOWER here; block 2 folds UPPER. The asymmetry is in the source and is preserved.
        addIfDiffers(differences, ComparedItem.ACCT_GROUP_ID,
                lowerCase(account.getAcctGroupId()).equals(lowerCase(old.groupId())));

        return Collections.unmodifiableSet(differences);
    }

    /**
     * Block 2 of {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4152-4191}): the
     * nineteen customer comparisons, evaluated in source order.
     *
     * @param customer the record read under the lock
     * @param old      the {@code ACUP-OLD-CUST-DATA} snapshot
     * @param codec    the codec, used to render {@code CUST-SSN PIC 9(09)} and
     *                 {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} against their {@code PIC X}
     *                 redefinitions
     * @return the items that differ, in declaration order; empty when the block reached its
     *         {@code CONTINUE}
     */
    private static Set<ComparedItem> compareCustomer(CustomerRecord customer, CustomerData old,
                                                     FixedWidthCodec codec) {
        Set<ComparedItem> differences = EnumSet.noneOf(ComparedItem.class);

        // :4152-4157 the three name parts, FUNCTION UPPER-CASE on both sides.
        addIfDiffers(differences, ComparedItem.CUST_FIRST_NAME,
                upperCase(customer.getCustFirstName()).equals(upperCase(old.firstName())));
        addIfDiffers(differences, ComparedItem.CUST_MIDDLE_NAME,
                upperCase(customer.getCustMiddleName()).equals(upperCase(old.middleName())));
        addIfDiffers(differences, ComparedItem.CUST_LAST_NAME,
                upperCase(customer.getCustLastName()).equals(upperCase(old.lastName())));

        // :4158-4163 the three address lines, also folded.
        addIfDiffers(differences, ComparedItem.CUST_ADDR_LINE_1,
                upperCase(customer.getCustAddrLine1()).equals(upperCase(old.addrLine1())));
        addIfDiffers(differences, ComparedItem.CUST_ADDR_LINE_2,
                upperCase(customer.getCustAddrLine2()).equals(upperCase(old.addrLine2())));
        addIfDiffers(differences, ComparedItem.CUST_ADDR_LINE_3,
                upperCase(customer.getCustAddrLine3()).equals(upperCase(old.addrLine3())));

        // :4164-4167 the state and country codes, folded.
        addIfDiffers(differences, ComparedItem.CUST_ADDR_STATE_CD,
                upperCase(customer.getCustAddrStateCd()).equals(upperCase(old.addrStateCd())));
        addIfDiffers(differences, ComparedItem.CUST_ADDR_COUNTRY_CD,
                upperCase(customer.getCustAddrCountryCd()).equals(upperCase(old.addrCountryCd())));

        // :4168-4171 the postal code, both telephone numbers and the social security number, all
        // compared EXACTLY. No fold, deliberately: a difference of case in any of these is a change.
        addIfDiffers(differences, ComparedItem.CUST_ADDR_ZIP,
                customer.getCustAddrZip().equals(old.addrZip()));
        addIfDiffers(differences, ComparedItem.CUST_PHONE_NUM_1,
                customer.getCustPhoneNum1().equals(old.phoneNum1()));
        addIfDiffers(differences, ComparedItem.CUST_PHONE_NUM_2,
                customer.getCustPhoneNum2().equals(old.phoneNum2()));
        // CUST-SSN is PIC 9(09) and ACUP-OLD-CUST-SSN is the PIC 9(09) redefinition of a PIC X(09)
        // span, so the snapshot's characters are rendered through the same zoned form the record uses.
        addIfDiffers(differences, ComparedItem.CUST_SSN,
                customer.custSsnImage(codec).equals(old.ssnImage(codec)));

        // :4172-4173 the government-issued identifier, folded.
        addIfDiffers(differences, ComparedItem.CUST_GOVT_ISSUED_ID,
                upperCase(customer.getCustGovtIssuedId()).equals(upperCase(old.govtIssuedId())));

        // :4174-4179 THE ASYMMETRIC DATE-OF-BIRTH OFFSETS. Left side (1:4)/(6:2)/(9:2) over a ten-byte
        // YYYY-MM-DD record field; right side (1:4)/(5:2)/(7:2) over an eight-byte YYYYMMDD snapshot
        // field. Both are reproduced verbatim; the snapshot's three declared parts ARE its (1:4),
        // (5:2) and (7:2) slices, per the ACUP-OLD-CUST-DOB-PARTS REDEFINES at :748-751. Making the
        // two offset sets symmetric would break the check.
        addIfDiffers(differences, ComparedItem.CUST_DOB_YEAR,
                custDobSlice(customer, AccountRecord.YEAR_START, DATE_YEAR_LENGTH)
                        .equals(old.dobYear()));
        addIfDiffers(differences, ComparedItem.CUST_DOB_MONTH,
                custDobSlice(customer, AccountRecord.MONTH_START, DATE_PART_LENGTH)
                        .equals(old.dobMon()));
        addIfDiffers(differences, ComparedItem.CUST_DOB_DAY,
                custDobSlice(customer, AccountRecord.DAY_START, DATE_PART_LENGTH)
                        .equals(old.dobDay()));

        // :4181-4191 the last three, all compared exactly.
        addIfDiffers(differences, ComparedItem.CUST_EFT_ACCOUNT_ID,
                customer.getCustEftAccountId().equals(old.eftAccountId()));
        addIfDiffers(differences, ComparedItem.CUST_PRI_CARD_HOLDER_IND,
                customer.getCustPriCardHolderInd().equals(old.priHolderInd()));
        addIfDiffers(differences, ComparedItem.CUST_FICO_CREDIT_SCORE,
                customer.custFicoCreditScoreImage(codec).equals(old.ficoScoreImage(codec)));

        return Collections.unmodifiableSet(differences);
    }

    /**
     * One reference-modified slice of {@code CUST-DOB-YYYY-MM-DD}, the ten-character
     * {@code app/cpy/CVCUS01Y.cpy:19} field.
     *
     * <p>Routed through {@link AccountRecord#referenceModify(String, int, int)} because that method is
     * the single place in this codebase where the COBOL one-based to Java zero-based conversion is
     * performed, and having one implementation of it is worth more than having it live in the customer
     * package. It is a pure string operation and carries no account semantics.
     *
     * @param customer      the record read under the lock
     * @param oneBasedStart the one-based position, as the COBOL writes it: 1, 6 or 9
     * @param length        the slice width: 4 for the year, 2 for the month and the day
     * @return exactly {@code length} characters
     */
    private static String custDobSlice(CustomerRecord customer, int oneBasedStart, int length) {
        return AccountRecord.referenceModify(customer.getCustDobYyyyMmDd(), oneBasedStart, length);
    }

    /**
     * Records {@code item} as differing when {@code matches} is {@code false}.
     *
     * <p>The parameter is the <em>match</em> rather than the difference so that every call site reads
     * as the COBOL condition it reproduces - {@code EQUAL} - instead of as its negation.
     *
     * @param differences the accumulator
     * @param item        the item compared
     * @param matches     whether the COBOL {@code EQUAL} condition held
     */
    private static void addIfDiffers(Set<ComparedItem> differences, ComparedItem item,
                                    boolean matches) {
        if (!matches) {
            differences.add(item);
        }
    }

    // =================================================================================================
    // The five COMPUTE statements, app/cbl/COACTUPC.cbl:1079, 1093, 1107, 1121 and 1135. These are the
    // ONLY five COMPUTEs in the program - verified by counting every occurrence of the verb - and each
    // gets its own method so each gets its own targeted assertion (gate G28).
    //
    // All five share one shape, from 1100-RECEIVE-MAP:
    //
    //     IF  <screen field> = '*' OR <screen field> = SPACES
    //         MOVE LOW-VALUES           TO ACUP-NEW-<item>-X
    //     ELSE
    //         MOVE <screen field>       TO ACUP-NEW-<item>-X
    //         IF FUNCTION TEST-NUMVAL-C(ACUP-NEW-<item>-X) = 0
    //            COMPUTE ACUP-NEW-<item>-N = FUNCTION NUMVAL-C(<operand>)
    //         ELSE
    //            CONTINUE
    //         END-IF
    //     END-IF
    //
    // Two things about that shape are load-bearing. The guard always tests the STAGING copy, while the
    // conversion's operand is the map field at three sites and the staging copy at the other two - an
    // asymmetry reproduced through NumvalArgument. And the ELSE is CONTINUE, not an assignment: a
    // non-conforming value leaves the -N span holding whatever it already held.
    // =================================================================================================

    /**
     * {@code COMPUTE ACUP-NEW-CREDIT-LIMIT-N = FUNCTION NUMVAL-C(ACRDLIMI OF CACTUPAI)}
     * ({@code app/cbl/COACTUPC.cbl:1079-1080}), together with the {@code '*'} / {@code SPACES} arm at
     * {@code :1073-1075} and the {@code TEST-NUMVAL-C} guard at {@code :1078}.
     *
     * <p>The conversion's operand is the <strong>map field</strong>, {@code ACRDLIMI OF CACTUPAI}.
     *
     * @param screenField {@code ACRDLIMI PIC X(15)} as received; {@code null} is read as the
     *                    not-supplied state, which is what an omitted payload field means
     * @param priorValue  the {@code -N} span's content before the statement, which a non-conforming
     *                    value leaves in place because the {@code ELSE} is {@code CONTINUE};
     *                    {@code null} is read as a freshly initialised span, that is scale-2 zero
     * @param codec       the codec, which owns the {@code PIC X(15)} receiving rule
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
     * <p>The conversion's operand is the <strong>map field</strong>, {@code ACSHLIMI OF CACTUPAI}.
     *
     * @param screenField {@code ACSHLIMI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue  the {@code -N} span's content before the statement; {@code null} is scale-2
     *                    zero
     * @param codec       the codec, which owns the {@code PIC X(15)} receiving rule
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
     * <p>The conversion's operand is the <strong>staging copy</strong>,
     * {@code ACUP-NEW-CURR-BAL-X} - not {@code ACURBALI OF CACTUPAI}. One of two sites that reads the
     * copy rather than the map field.
     *
     * @param screenField {@code ACURBALI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue  the {@code -N} span's content before the statement; {@code null} is scale-2
     *                    zero
     * @param codec       the codec, which owns the {@code PIC X(15)} receiving rule
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
     * <p>The conversion's operand is the <strong>map field</strong>, {@code ACRCYCRI OF CACTUPAI}.
     *
     * @param screenField {@code ACRCYCRI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue  the {@code -N} span's content before the statement; {@code null} is scale-2
     *                    zero
     * @param codec       the codec, which owns the {@code PIC X(15)} receiving rule
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
     * <p>The conversion's operand is the <strong>staging copy</strong>,
     * {@code ACUP-NEW-CURR-CYC-DEBIT-X}. The second of the two sites that read the copy.
     *
     * @param screenField {@code ACRCYDBI PIC X(15)} as received; {@code null} is the not-supplied state
     * @param priorValue  the {@code -N} span's content before the statement; {@code null} is scale-2
     *                    zero
     * @param codec       the codec, which owns the {@code PIC X(15)} receiving rule
     * @return what the statement left behind, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static MonetaryEdit computeCurrCycDebit(String screenField, BigDecimal priorValue,
                                                  FixedWidthCodec codec) {
        return editSignedNumber(screenField, priorValue, NumvalArgument.STAGING_COPY, codec,
                "ACUP-NEW-CURR-CYC-DEBIT-N", "1135-1136");
    }

    /**
     * The one body behind all five {@code COMPUTE} sites, so the five cannot drift apart.
     *
     * <p>The receiver is {@code PIC S9(10)V99}, so the converted value is stored through
     * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} at
     * {@value #MONETARY_INTEGER_DIGITS} integer digits and
     * {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE} decimal places. The keyword
     * {@code ROUNDED} appears nowhere in the program - nor in any of the twenty-eight - so the excess
     * fraction is truncated, and a value too large for ten integer digits wraps exactly as a COBOL
     * store without {@code ON SIZE ERROR} does (gates G24, G28).
     *
     * @param screenField    the map field as received, or {@code null} for the not-supplied state
     * @param priorValue     the receiver's content before the statement, or {@code null} for a freshly
     *                       initialised span
     * @param numvalArgument which operand the source names in the {@code NUMVAL-C} call
     * @param codec          the codec, which owns the {@code PIC X(15)} receiving rule
     * @param cobolReceiver  the receiving item's COBOL name, for the returned diagnostics
     * @param sourceLines    the {@code app/cbl/COACTUPC.cbl} line range of the {@code COMPUTE}
     * @return what the statement left behind
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    private static MonetaryEdit editSignedNumber(String screenField,
                                                BigDecimal priorValue,
                                                NumvalArgument numvalArgument,
                                                FixedWidthCodec codec,
                                                String cobolReceiver,
                                                String sourceLines) {
        Objects.requireNonNull(codec, "A codec is required: MOVE <screen field> TO ACUP-NEW-...-X is a "
                + "PIC X(" + SCREEN_MONETARY_LENGTH + ") move and the codec owns that rule");

        // A PIC S9(10)V99 span that INITIALIZE has just cleared holds zoned zero, so an absent prior
        // value is scale-2 zero rather than null. CobolDecimal.storeMonetary re-states the scale of a
        // supplied one, because a caller may hand over a scale-4 intermediate.
        BigDecimal prior = priorValue == null
                ? CobolDecimal.monetaryZero()
                : CobolDecimal.storeMonetary(priorValue);

        // The map field at its declared width. A null payload member is an omitted screen field, which
        // reaches the program as spaces - the same state the SPACES arm tests for.
        String mapField = codec.movePicX(screenField == null ? "" : screenField,
                SCREEN_MONETARY_LENGTH);

        // The '*' comparison is against a PIC X(15) item, so COBOL space-extends the one-character
        // literal to fifteen before comparing; writing it any other way would make '*' followed by
        // fourteen spaces fail to match, which is exactly the value FieldAttributeSetter puts there.
        boolean notSupplied = mapField.equals(codec.movePicX(NOT_SUPPLIED_MARKER,
                SCREEN_MONETARY_LENGTH))
                || mapField.equals(" ".repeat(SCREEN_MONETARY_LENGTH));

        if (notSupplied) {
            // MOVE LOW-VALUES TO ACUP-NEW-...-X. No TEST-NUMVAL-C is evaluated on this arm and the -N
            // span keeps its prior content; 1250-EDIT-SIGNED-9V2 will reject the field at :2184-2196.
            return new MonetaryEdit(cobolReceiver, sourceLines, numvalArgument, LOW_VALUES_IMAGE, true,
                    OptionalInt.empty(), false, prior);
        }

        // MOVE <screen field> TO ACUP-NEW-...-X. Same width both sides, so the staging copy and the map
        // field hold identical characters from here on.
        String stagingCopy = mapField;

        // The guard always names the STAGING copy, at all five sites.
        int conformance = testNumvalC(stagingCopy);
        if (conformance != NUMVAL_CONFORMS) {
            // ELSE CONTINUE. The -N span is not assigned, so it keeps whatever it held.
            return new MonetaryEdit(cobolReceiver, sourceLines, numvalArgument, stagingCopy, false,
                    OptionalInt.of(conformance), false, prior);
        }

        // The conversion's operand, which is the map field at three sites and the staging copy at two.
        String operand = numvalArgument == NumvalArgument.STAGING_COPY ? stagingCopy : mapField;
        BigDecimal computed = CobolDecimal.storeAtPicture(numvalC(operand), MONETARY_INTEGER_DIGITS,
                CobolDecimal.MONETARY_SCALE);
        return new MonetaryEdit(cobolReceiver, sourceLines, numvalArgument, stagingCopy, false,
                OptionalInt.of(conformance), true, computed);
    }

    // =================================================================================================
    // The primitives. Each is one COBOL statement or one COBOL declaration, named after it, so a
    // reviewer can check it against the source without reading the paragraph around it. Each is public
    // and, where it needs no instance state, static - so a test can drive it in isolation, which is what
    // makes the branch bar reachable (practice B7, gate G49).
    // =================================================================================================

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} ({@code app/cbl/COACTUPC.cbl:480}): whether
     * {@code WS-RETURN-MSG} is still clear, which is the condition guarding
     * {@code SET COULD-NOT-LOCK-ACCT-FOR-UPDATE TO TRUE} at {@code :3911-3913} and
     * {@code SET COULD-NOT-LOCK-CUST-FOR-UPDATE TO TRUE} at {@code :3939-3941}.
     *
     * <p><strong>Spaces, not {@code LOW-VALUES}.</strong> {@code COACTUPC} declares this condition on
     * its own {@code 05 WS-RETURN-MSG PIC X(75)} at {@code :479}. {@code :876} runs
     * {@code SET WS-RETURN-MSG-OFF TO TRUE} unconditionally at the top of every pass, which is
     * {@code MOVE SPACES}, so "off" means {@value #RETURN_MESSAGE_LENGTH} spaces.
     *
     * <p>{@code null} is read as clear, for the same reason: "no message was supplied" and "the message
     * is off" are the same state, and an omitted payload field deserialising to {@code null} is exactly
     * that case. A shorter value is space-padded first, because a COBOL {@code PIC X(75)} item is always
     * seventy-five characters wide.
     *
     * @param returnMessage the current content of {@code WS-RETURN-MSG}, or {@code null} for the cleared
     *                      state
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
     * {@code FUNCTION UPPER-CASE}, as {@code app/cbl/COACTUPC.cbl:4152-4173} and {@code :4180-4181}
     * apply it to sixteen operands - eight fields on each side of eight comparisons.
     *
     * @param value the operand; must not be {@code null}
     * @return {@code value} with its lower-case letters folded up, at exactly {@code value}'s length
     * @throws NullPointerException if {@code value} is {@code null}
     * @see #lowerCase(String)
     */
    public static String upperCase(String value) {
        return fold(value, true);
    }

    /**
     * {@code FUNCTION LOWER-CASE}, as {@code app/cbl/COACTUPC.cbl:4144-4145} applies it to
     * {@code ACCT-GROUP-ID} and {@code ACUP-OLD-GROUP-ID}.
     *
     * <p>This is the <em>only</em> lower-case fold in the paragraph; the customer block folds upward.
     * The asymmetry changes no outcome for the letters these fields can hold, but it is what the source
     * says and it is preserved (practice B5).
     *
     * @param value the operand; must not be {@code null}
     * @return {@code value} with its upper-case letters folded down, at exactly {@code value}'s length
     * @throws NullPointerException if {@code value} is {@code null}
     * @see #upperCase(String)
     */
    public static String lowerCase(String value) {
        return fold(value, false);
    }

    /**
     * The one implementation behind {@link #upperCase(String)} and {@link #lowerCase(String)}.
     *
     * <p>Two properties are required of it, and neither is free.
     *
     * <p><strong>Locale independence.</strong> The fold is performed through {@link Locale#ROOT}
     * explicitly. Left to the host default, a Turkish locale would fold {@code i} to a dotted capital
     * and turn a matching name into a reported change, so the concurrency check's answer would depend on
     * where the server happens to run.
     *
     * <p><strong>Length preservation.</strong> Every operand here is a fixed-width {@code PIC X} item
     * being compared against another of the same declared width, so a fold that changed the length would
     * shift every subsequent character and make the comparison meaningless. {@code String.toUpperCase}
     * can do exactly that - German sharp {@code s} folds to two characters - so the fold is applied one
     * character at a time and a conversion that does not yield exactly one character is discarded in
     * favour of the original. COBOL's {@code FUNCTION UPPER-CASE} is defined on the single-byte native
     * character set and is length-preserving by construction, and both code pages this system uses are
     * single-byte, so discarding the multi-character case is the faithful choice rather than a
     * concession.
     *
     * @param value   the operand; must not be {@code null}
     * @param toUpper {@code true} for {@code FUNCTION UPPER-CASE}, {@code false} for
     *                {@code FUNCTION LOWER-CASE}
     * @return the folded value, at exactly {@code value}'s length
     * @throws NullPointerException if {@code value} is {@code null}
     */
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
     * <p>Characters, not a number. {@code CC-ACCT-ID} is itself {@code PIC X(11)}
     * ({@code app/cpy/CVCRD01Y.cpy:34}), so the value that reaches the file is whatever the screen
     * supplied, space-padded to eleven. A blank or partly typed identifier therefore produces a
     * not-found read - a case the program has code for - rather than a parse failure, which it does not.
     *
     * @param ccAcctId {@code CC-ACCT-ID} as the work area holds it; {@code null} is read as blank
     * @param codec    the codec, which owns the {@code PIC X(11)} receiving rule
     * @return exactly {@value #ACCT_KEY_LENGTH} characters
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
     * <p>Unlike the account key, this one starts life numeric: {@code CDEMO-CUST-ID} is
     * {@code PIC 9(09)} and {@code WS-CARD-RID-CUST-ID} is {@code PIC 9(09)}, so the move is
     * numeric-to-numeric and the result is zero-filled on the <em>left</em> to nine digits. Passing the
     * characters through a {@code PIC X} rule instead would right-pad and produce a different key.
     *
     * @param cdemoCustId {@code CDEMO-CUST-ID} from the commarea
     * @param codec       the codec, which owns the {@code PIC 9(09)} receiving rule
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
     * <p>{@code DELIMITED BY SIZE} means each sending item contributes its <em>full declared width</em>,
     * so the transfer is 4 + 1 + 2 + 1 + 2 = {@value #STORED_DATE_LENGTH} characters into a
     * {@code PIC X(10)} receiver. It fills the receiver exactly, which is why no question arises about
     * what {@code STRING} does with the tail - it reaches the end.
     *
     * <p>{@code :3992} runs {@code MOVE ACCT-REISSUE-DATE TO ACCT-UPDATE-REISSUE-DATE} immediately
     * before the reissue {@code STRING} overwrites all ten characters of the same receiver. That move is
     * dead and it stays dead: it is not reproduced as a separate step because it has no observable
     * effect, and the fact is recorded here rather than left for a reader to rediscover (practice B4).
     *
     * @param year  the year part at its declared {@value #DATE_YEAR_LENGTH} characters; {@code null} is
     *              read as blank
     * @param month the month part at its declared {@value #DATE_PART_LENGTH} characters
     * @param day   the day part at its declared {@value #DATE_PART_LENGTH} characters
     * @param codec the codec, which owns both the declared-width rule and the concatenation
     * @return exactly {@value #STORED_DATE_LENGTH} characters in {@code YYYY-MM-DD} shape
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
     * {@code STRING '(' <a> ')' <b> '-' <c> DELIMITED BY SIZE INTO CUST-UPDATE-PHONE-NUM-1} at
     * {@code app/cbl/COACTUPC.cbl:4035-4041} and the same for number two at {@code :4043-4049}.
     *
     * <p>The transfer is 1 + 3 + 1 + 3 + 1 + 4 = 13 characters into a {@code PIC X(15)} receiver, so
     * <strong>{@code STRING} does not reach the last two characters</strong> and leaves them exactly as
     * they were. They are spaces, because {@code INITIALIZE CUST-UPDATE-RECORD} at {@code :4006} put
     * them there. The result is therefore thirteen characters followed by two spaces - which is
     * precisely the {@code FILLER PIC X(2)} that closes the
     * {@code ACUP-NEW-CUST-PHONE-NUM-1-X REDEFINES} overlay at {@code :819-827}.
     *
     * <p>This method returns the thirteen characters. Padding them to fifteen is the receiver's job, and
     * {@link CustomerRecord#setCustPhoneNum1(String)} does it through the same {@code PIC X} rule, so
     * the two spaces are attributable to the {@code INITIALIZE} rather than invented here.
     *
     * @param areaCode   {@code ACUP-NEW-CUST-PHONE-NUM-1A PIC X(3)}, the {@code (2:3)} slice of the
     *                   fifteen-character span; {@code null} is read as blank
     * @param prefix     {@code ACUP-NEW-CUST-PHONE-NUM-1B PIC X(3)}, the {@code (6:3)} slice
     * @param lineNumber {@code ACUP-NEW-CUST-PHONE-NUM-1C PIC X(4)}, the {@code (10:4)} slice
     * @param codec      the codec, which owns both the declared-width rule and the concatenation
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
     * <p><strong>This is where the layout defect is reproduced.</strong> The image is assembled against
     * {@code ACCT-UPDATE-RECORD}'s own offsets ({@code :418-433}), which are not
     * {@code app/cpy/CVACT01Y.cpy}'s from offset {@value #ACCT_UPDATE_GROUP_ID_OFFSET} onward: the
     * group identifier goes where the stored record keeps {@code ACCT-ADDR-ZIP}, and the stored
     * {@code ACCT-GROUP-ID} span falls inside the {@value #ACCT_UPDATE_FILLER_LENGTH}-character
     * reserved area and is blanked. {@link AccountRecord}'s setters cannot be used for this, precisely
     * because they know the correct layout; the image is therefore built span by span.
     *
     * <p>The moves themselves are performed in the source's statement order, which differs from the
     * declaration order - {@code :3968-3974} moves the two cycle amounts before {@code :3976-3999}
     * composes the three dates. Since each move targets a distinct span the resulting bytes are the
     * same either way, and the locals below are ordered as the source writes them so a reviewer can
     * follow it line by line.
     *
     * @param newDetails {@code ACUP-NEW-DETAILS}; must be the {@link DetailGroup#NEW} group
     * @param codec      the codec, which owns every pad, truncate and concatenate rule used here
     * @return exactly {@value #ACCT_UPDATE_RECORD_LENGTH} characters
     * @throws NullPointerException     if {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code newDetails} is not the {@link DetailGroup#NEW} group
     * @throws IllegalStateException    if the assembled image is not exactly
     *                                  {@value #ACCT_UPDATE_RECORD_LENGTH} characters, which would mean
     *                                  a span was omitted
     */
    public static String stageAccountUpdateImage(AccountUpdateDetails newDetails,
                                                FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to stage ACCT-UPDATE-RECORD");
        requireGroup(newDetails, DetailGroup.NEW, "ACUP-NEW-DETAILS", "757-855");
        AccountData data = newDetails.acctData();

        // :3960 MOVE ACUP-NEW-ACCT-ID TO ACCT-UPDATE-ID. The 9(11) REDEFINES view of the X(11) span.
        String id = codec.movePic9(data.acctId(), AccountRecord.ACCT_ID_LENGTH);
        // :3962 MOVE ACUP-NEW-ACTIVE-STATUS TO ACCT-UPDATE-ACTIVE-STATUS.
        String activeStatus = codec.movePicX(data.activeStatus(),
                AccountRecord.ACCT_ACTIVE_STATUS_LENGTH);
        // :3964 MOVE ACUP-NEW-CURR-BAL-N TO ACCT-UPDATE-CURR-BAL. S9(10)V99 both sides.
        String currBal = monetaryImage(data.currBal(), codec);
        // :3966 MOVE ACUP-NEW-CREDIT-LIMIT-N TO ACCT-UPDATE-CREDIT-LIMIT.
        String creditLimit = monetaryImage(data.creditLimit(), codec);
        // :3968-3969 MOVE ACUP-NEW-CASH-CREDIT-LIMIT-N TO ACCT-UPDATE-CASH-CREDIT-LIMIT.
        String cashCreditLimit = monetaryImage(data.cashCreditLimit(), codec);
        // :3971-3972 MOVE ACUP-NEW-CURR-CYC-CREDIT-N TO ACCT-UPDATE-CURR-CYC-CREDIT.
        String currCycCredit = monetaryImage(data.currCycCredit(), codec);
        // :3974 MOVE ACUP-NEW-CURR-CYC-DEBIT-N TO ACCT-UPDATE-CURR-CYC-DEBIT.
        String currCycDebit = monetaryImage(data.currCycDebit(), codec);
        // :3976-3982 STRING the open date.
        String openDate = composeStoredDate(data.openYear(), data.openMon(), data.openDay(), codec);
        // :3984-3990 STRING the expiry date, into the misspelled receiver.
        String expiraionDate = composeStoredDate(data.expYear(), data.expMon(), data.expDay(), codec);
        // :3992-3999 the dead MOVE, then STRING the reissue date over all ten characters.
        String reissueDate = composeStoredDate(data.reissueYear(), data.reissueMon(), data.reissueDay(),
                codec);
        // :4001 MOVE ACUP-NEW-GROUP-ID TO ACCT-UPDATE-GROUP-ID - ten characters at offset
        // ACCT_UPDATE_GROUP_ID_OFFSET, which is where the stored record keeps ACCT-ADDR-ZIP.
        String groupId = codec.movePicX(data.groupId(), AccountRecord.ACCT_GROUP_ID_LENGTH);

        // Assembled in offset order, which is also ACCT-UPDATE-RECORD's declaration order.
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
                // FILLER PIC X(188) at :433, emitted as spaces (gate G21). Omitting it would leave the
                // image short, which the check below turns into an immediate failure.
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
     * @param codec      the codec, which owns every pad, truncate and concatenate rule used here
     * @return exactly {@value #CUST_UPDATE_RECORD_LENGTH} characters
     * @throws NullPointerException     if {@code newDetails} or {@code codec} is {@code null}
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
     * <p>Unlike the account record, {@code CUST-UPDATE-RECORD} ({@code :434-456}) is byte-identical to
     * {@code app/cpy/CVCUS01Y.cpy}: nine identifier digits, three twenty-five-character names, three
     * fifty-character address lines, the state and country codes, the postal code, two
     * fifteen-character telephone numbers, nine social-security digits, the twenty-character
     * government-issued identifier, the ten-character date of birth, the ten-character transfer
     * account identifier, the primary-cardholder indicator, three credit-score digits and
     * {@code FILLER PIC X(168)}. Every span agrees, so this staging goes through
     * {@link CustomerRecord}'s own setters, which carry the widths and the {@code PIC X} and
     * {@code PIC 9} receiving rules.
     *
     * <p>{@code new CustomerRecord()} <em>is</em> the {@code INITIALIZE}: character spans spaces,
     * numeric spans zero, and the trailing {@code FILLER} 168 spaces. The record's caption in
     * {@code COACTUPC} says "(RECLN 300)", which is simply wrong - it is 500 - and being a comment it
     * has no counterpart here beyond this note.
     *
     * <p>All eighteen named spans are written, so the {@code INITIALIZE}'s only surviving contribution
     * is the {@code FILLER} and the two trailing characters of each telephone number that the
     * {@code STRING}s do not reach.
     *
     * @param newDetails {@code ACUP-NEW-DETAILS}; must be the {@link DetailGroup#NEW} group
     * @param codec      the codec, which owns the concatenation rules for the three {@code STRING}s
     * @return a fully staged record, never {@code null}
     * @throws NullPointerException     if {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code newDetails} is not the {@link DetailGroup#NEW} group
     */
    public static CustomerRecord stageCustomerUpdateRecord(AccountUpdateDetails newDetails,
                                                          FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to stage CUST-UPDATE-RECORD");
        requireGroup(newDetails, DetailGroup.NEW, "ACUP-NEW-DETAILS", "757-855");
        CustomerData data = newDetails.custData();

        // :4006 INITIALIZE CUST-UPDATE-RECORD.
        CustomerRecord staged = new CustomerRecord();

        // :4010 MOVE ACUP-NEW-CUST-ID TO CUST-UPDATE-ID - the 9(09) REDEFINES view.
        staged.setCustId(data.custId());
        // :4011-4016 the three name parts.
        staged.setCustFirstName(data.firstName());
        staged.setCustMiddleName(data.middleName());
        staged.setCustLastName(data.lastName());
        // :4017-4024 the three address lines.
        staged.setCustAddrLine1(data.addrLine1());
        staged.setCustAddrLine2(data.addrLine2());
        staged.setCustAddrLine3(data.addrLine3());
        // :4025-4030 the state and country codes.
        staged.setCustAddrStateCd(data.addrStateCd());
        staged.setCustAddrCountryCd(data.addrCountryCd());
        // :4031 the postal code.
        staged.setCustAddrZip(data.addrZip());
        // :4035-4049 the two telephone STRINGs, each writing 13 of 15 characters and leaving the last
        // two as the INITIALIZE left them.
        staged.setCustPhoneNum1(composePhoneNumber(data.phoneNum1A(), data.phoneNum1B(),
                data.phoneNum1C(), codec));
        staged.setCustPhoneNum2(composePhoneNumber(data.phoneNum2A(), data.phoneNum2B(),
                data.phoneNum2C(), codec));
        // :4052 MOVE ACUP-NEW-CUST-SSN TO CUST-UPDATE-SSN - the 9(09) REDEFINES view of the
        // three-part X group at :832-836.
        staged.setCustSsn(data.ssn());
        // :4053-4054 the government-issued identifier.
        staged.setCustGovtIssuedId(data.govtIssuedId());
        // :4054-4059 STRING the date of birth, filling all ten characters.
        staged.setCustDobYyyyMmDd(composeStoredDate(data.dobYear(), data.dobMon(), data.dobDay(),
                codec));
        // :4061-4062 the transfer account identifier.
        staged.setCustEftAccountId(data.eftAccountId());
        // :4063-4064 the primary-cardholder indicator.
        staged.setCustPriCardHolderInd(data.priHolderInd());
        // :4065-4066 MOVE ACUP-NEW-CUST-FICO-SCORE TO CUST-UPDATE-FICO-CREDIT-SCORE - the 9(03) view.
        staged.setCustFicoCreditScore(data.ficoScore());

        return staged;
    }

    /**
     * A {@code PIC S9(10)V99} value as the twelve zoned {@code DISPLAY} characters the span holds - the
     * character view of every monetary item, alongside the {@link BigDecimal} numeric view.
     *
     * @param value the value; must not be {@code null}
     * @param codec the codec, which owns the zoned encoding and the sign overpunch
     * @return exactly {@value #MONETARY_IMAGE_LENGTH} characters
     */
    private static String monetaryImage(BigDecimal value, FixedWidthCodec codec) {
        return codec.encodeSignedScaled(value, MONETARY_INTEGER_DIGITS, CobolDecimal.MONETARY_SCALE);
    }

    /**
     * {@code FUNCTION NUMVAL-C}, as {@code app/cbl/COACTUPC.cbl:1080,1094,1108,1122,1136} apply it.
     *
     * <p>Returns the numeric value of a character representation that may carry a sign, a currency sign,
     * digit-grouping commas and a decimal point, as a {@link BigDecimal} so that every digit survives.
     * The argument format is the one the intrinsic documents, with spaces permitted between the
     * elements:
     *
     * <pre>{@code [+|-] [$] digits[,digits]... [.[digits]] [+|-|CR|DB]}</pre>
     *
     * <p><strong>An argument that does not conform yields zero.</strong> COBOL leaves that case
     * undefined, so a deterministic choice has to be made, and zero is chosen for consistency with the
     * two other implementations of the COBOL numeric intrinsics in this codebase, which document the
     * same convention for the same reason. It does not change what this program accepts: all five call
     * sites are guarded by {@code IF FUNCTION TEST-NUMVAL-C(...) = 0}, so a non-conforming value never
     * reaches the conversion.
     *
     * <p>Delegated to {@link NumericIntrinsics}, which is the module's one implementation of the
     * intrinsic. The grammar the standard defines - which characters may appear, in what order, and
     * where a currency sign or a trailing sign is safe - is stated once there rather than restated in
     * every program that calls the function.
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
     * @return {@value #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of
     *         the first character in error, or the argument's length plus one when it holds no digit at
     *         all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return NumericIntrinsics.testNumvalC(image);
    }

    /**
     * Advances past a run of spaces, which {@code FUNCTION NUMVAL-C} permits between the elements of its
     * argument.
     *
     * @param image the argument
     * @param from  the index to start at
     * @return the index of the first character at or after {@code from} that is not a space, or the
     *         argument's length when none is
     */
    private static int skipSpaces(String image, int from) {
        int index = from;
        while (index < image.length() && image.charAt(index) == SPACE) {
            index++;
        }
        return index;
    }

    /**
     * Whether a character is one of the two signs {@code FUNCTION NUMVAL-C} accepts in either the
     * leading or the trailing position.
     *
     * @param character the character to test
     * @return {@code true} for {@code '+'} and {@code '-'}
     */
    private static boolean isSign(char character) {
        return character == PLUS_SIGN || character == MINUS_SIGN;
    }

    /**
     * Whether a character is one of the ten ASCII digits. Deliberately not
     * {@link Character#isDigit(char)}, which accepts the decimal digits of every Unicode script;
     * {@code FUNCTION NUMVAL-C} accepts the ten characters of the native single-byte code page and
     * nothing else.
     *
     * @param character the character to test
     * @return {@code true} for {@code '0'} through {@code '9'}
     */
    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * Rejects a detail group that is not the one a method requires, so a snapshot cannot be passed where
     * the typed values belong or the other way round.
     *
     * <p>{@code ACUP-OLD-DETAILS} and {@code ACUP-NEW-DETAILS} have the same shape and are adjacent
     * declarations ({@code app/cbl/COACTUPC.cbl:669} and {@code :757}), which makes transposing them the
     * single easiest mistake to make in this write path - and one whose symptom would be a concurrency
     * check that always passes. The discriminant makes it a compile-time-shaped runtime refusal instead
     * of a silent behaviour change.
     *
     * @param details    the group supplied
     * @param expected   the group required
     * @param cobolName  the COBOL name of the required group, for the message
     * @param cobolLines the {@code app/cbl/COACTUPC.cbl} line range of its declaration
     * @throws NullPointerException     if {@code details} is {@code null}
     * @throws IllegalArgumentException if {@code details} is the other group
     */
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

    // =================================================================================================
    // The value objects. Every one of them is COBOL WORKING-STORAGE that has become a parameter or a
    // return value rather than a field (practice B9, gate G53).
    // =================================================================================================

    /**
     * Which of the two identically shaped detail groups a value carries:
     * {@code ACUP-OLD-DETAILS} ({@code app/cbl/COACTUPC.cbl:669-756}) or {@code ACUP-NEW-DETAILS}
     * ({@code :757-855}).
     *
     * <p>The two declarations differ in exactly two respects, neither of which changes their layout:
     * {@code ACUP-NEW-CUST-SSN-X} is a three-part group ({@code :832-836}) where
     * {@code ACUP-OLD-CUST-SSN-X} is a single {@code PIC X(09)} item ({@code :743}), and
     * {@code ACUP-NEW-CUST-FICO-SCORE} carries {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}
     * ({@code :853-854}) which the old group has no counterpart for. Everything else is byte-for-byte
     * identical, which is why transposing them would be so easy and so damaging.
     */
    public enum DetailGroup {

        /**
         * {@code ACUP-OLD-DETAILS} - the snapshot {@code 9500-STORE-FETCHED-DATA} took from the records
         * when the screen was painted ({@code app/cbl/COACTUPC.cbl:3814-3884}), and the right-hand side
         * of every comparison in {@code 9700-CHECK-CHANGE-IN-REC}.
         */
        OLD("ACUP-OLD-DETAILS"),

        /**
         * {@code ACUP-NEW-DETAILS} - what {@code 1100-RECEIVE-MAP} put there from the screen
         * ({@code app/cbl/COACTUPC.cbl:1047-1425}), and the source of every value
         * {@code 9600-WRITE-PROCESSING} stages.
         */
        NEW("ACUP-NEW-DETAILS");

        /** The COBOL group name. */
        private final String groupName;

        /**
         * @param groupName the COBOL group name
         */
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
     *
     * <p>Three of the five sites convert the map field and two convert the staging copy, and the two
     * hold identical characters because the {@code MOVE} that fills the copy is the immediately
     * preceding statement. The distinction is therefore invisible in the result and entirely visible in
     * the source, which is exactly the kind of thing a like-for-like migration records rather than
     * normalises (practice B4).
     */
    public enum NumvalArgument {

        /**
         * The symbolic-map field: {@code ACRDLIMI OF CACTUPAI} at
         * {@code app/cbl/COACTUPC.cbl:1080}, {@code ACSHLIMI OF CACTUPAI} at {@code :1094} and
         * {@code ACRCYCRI OF CACTUPAI} at {@code :1122}.
         */
        MAP_FIELD("<screen field> OF CACTUPAI"),

        /**
         * The {@code ALPHA-VARS-FOR-DATA-EDITING} staging copy: {@code ACUP-NEW-CURR-BAL-X} at
         * {@code app/cbl/COACTUPC.cbl:1108} and {@code ACUP-NEW-CURR-CYC-DEBIT-X} at {@code :1136}.
         */
        STAGING_COPY("ACUP-NEW-<item>-X");

        /** How the source spells the operand. */
        private final String cobolOperand;

        /**
         * @param cobolOperand how the source spells the operand
         */
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
     * What one of the five {@code COMPUTE} statements left behind, together with everything the arms
     * around it decided.
     *
     * <p>Returning the whole picture rather than only the value is what makes the statement assertable.
     * A value of scale-2 zero is produced by three different paths - a genuinely zero screen field, a
     * not-supplied field whose {@code -N} span was already zero, and a non-conforming field whose span
     * was already zero - and a parity case has to be able to tell them apart.
     *
     * @param cobolReceiver  the receiving item's COBOL name, for example
     *                       {@code "ACUP-NEW-CREDIT-LIMIT-N"}
     * @param sourceLines    the {@code app/cbl/COACTUPC.cbl} line range of the {@code COMPUTE}
     * @param numvalArgument which operand the source's {@code NUMVAL-C} call names
     * @param stagingImage   what the {@code PIC X(15)} staging item holds afterwards: the screen field
     *                       at its declared width, or {@link #LOW_VALUES_IMAGE} on the not-supplied arm
     * @param notSupplied    whether the {@code '*'} or {@code SPACES} arm was taken, in which case no
     *                       conformance test was evaluated and no conversion was performed
     * @param testNumvalC    what {@code FUNCTION TEST-NUMVAL-C} reported, or empty when the
     *                       not-supplied arm meant it was never evaluated
     * @param computed       whether the {@code COMPUTE} actually executed - true only when the field was
     *                       supplied and conformed
     * @param value          the {@code -N} span's content afterwards, always at scale
     *                       {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE}: the
     *                       converted value when {@code computed}, otherwise the span's prior content
     */
    public record MonetaryEdit(String cobolReceiver,
                              String sourceLines,
                              NumvalArgument numvalArgument,
                              String stagingImage,
                              boolean notSupplied,
                              OptionalInt testNumvalC,
                              boolean computed,
                              BigDecimal value) {

        /**
         * Rejects a result whose parts contradict each other.
         *
         * @throws NullPointerException     if any reference component is {@code null}
         * @throws IllegalArgumentException if {@code stagingImage} is not exactly
         *                                  {@value #SCREEN_MONETARY_LENGTH} characters, if
         *                                  {@code value} is not at the monetary scale, or if
         *                                  {@code notSupplied}, {@code testNumvalC} and
         *                                  {@code computed} do not describe one of the three arms the
         *                                  statement has
         */
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
         * Whether the field was supplied but did not conform, which is the {@code ELSE CONTINUE} arm -
         * the one that leaves the receiving span holding its prior content.
         *
         * @return {@code true} on the non-conforming arm only
         */
        public boolean isNotValid() {
            return !notSupplied && !computed;
        }
    }

    /**
     * {@code ACUP-OLD-ACCT-DATA} ({@code app/cbl/COACTUPC.cbl:670-707}) or
     * {@code ACUP-NEW-ACCT-DATA} ({@code :758-785}) - the account half of a detail group.
     *
     * <p>Every component is stored at its declared width or scale by the canonical constructor, so the
     * comparisons in {@code 9700-CHECK-CHANGE-IN-REC} are between operands of equal width and are
     * therefore plain equality, exactly as COBOL's are.
     *
     * <p>The three dates are held as their <strong>three declared parts</strong> rather than as one
     * eight-character string, because that is how the group declares them: {@code ACUP-OLD-OPEN-DATE
     * PIC X(08)} with {@code ACUP-OLD-OPEN-DATE-PARTS REDEFINES} splitting it into a
     * {@value #DATE_YEAR_LENGTH}-character year and two {@value #DATE_PART_LENGTH}-character parts
     * ({@code :685-689}). {@link #openDate()} is the base view over the same span, so both accessors
     * exist and neither is derived from a guess (gate G34).
     *
     * @param acctId          {@code ACUP-OLD-ACCT-ID} / {@code ACUP-NEW-ACCT-ID}, the {@code PIC 9(11)}
     *                        redefinition of the {@code PIC X(11)} span at {@code :672-674}
     * @param activeStatus    {@code ACUP-...-ACTIVE-STATUS PIC X(01)} at {@code :675}
     * @param currBal         {@code ACUP-...-CURR-BAL-N PIC S9(10)V99} at {@code :677-679}
     * @param creditLimit     {@code ACUP-...-CREDIT-LIMIT-N PIC S9(10)V99} at {@code :680-682}
     * @param cashCreditLimit {@code ACUP-...-CASH-CREDIT-LIMIT-N PIC S9(10)V99} at {@code :683-685}
     * @param openYear        {@code ACUP-...-OPEN-YEAR PIC X(4)} at {@code :687}
     * @param openMon         {@code ACUP-...-OPEN-MON PIC X(2)} at {@code :688}
     * @param openDay         {@code ACUP-...-OPEN-DAY PIC X(2)} at {@code :689}
     * @param expYear         {@code ACUP-...-EXP-YEAR PIC X(4)} at {@code :694}
     * @param expMon          {@code ACUP-...-EXP-MON PIC X(2)} at {@code :695}
     * @param expDay          {@code ACUP-...-EXP-DAY PIC X(2)} at {@code :696}
     * @param reissueYear     {@code ACUP-...-REISSUE-YEAR PIC X(4)} at {@code :701}
     * @param reissueMon      {@code ACUP-...-REISSUE-MON PIC X(2)} at {@code :702}
     * @param reissueDay      {@code ACUP-...-REISSUE-DAY PIC X(2)} at {@code :703}
     * @param currCycCredit   {@code ACUP-...-CURR-CYC-CREDIT-N PIC S9(10)V99} at {@code :704-706}
     * @param currCycDebit    {@code ACUP-...-CURR-CYC-DEBIT-N PIC S9(10)V99} at {@code :707-709}
     * @param groupId         {@code ACUP-...-GROUP-ID PIC X(10)} at {@code :710}
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

        /** The declared width of {@code ACUP-OLD-ACCT-ID-X PIC X(11)} ({@code :672}). */
        public static final int ACCT_ID_LENGTH = AccountRecord.ACCT_ID_LENGTH;

        /** The declared width of {@code ACUP-OLD-ACTIVE-STATUS PIC X(01)} ({@code :675}). */
        public static final int ACTIVE_STATUS_LENGTH = AccountRecord.ACCT_ACTIVE_STATUS_LENGTH;

        /** The declared width of {@code ACUP-OLD-GROUP-ID PIC X(10)} ({@code :710}). */
        public static final int GROUP_ID_LENGTH = AccountRecord.ACCT_GROUP_ID_LENGTH;

        /**
         * Normalises every component to its declared width or scale, so no comparison in this class has
         * to.
         *
         * @throws NullPointerException if any reference component is {@code null}
         */
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
         * The group as an unqualified {@code INITIALIZE} leaves it
         * ({@code app/cbl/COACTUPC.cbl:1047} for the new group, {@code :3813} for the old): character
         * spans holding their declared width in spaces, numeric spans zero.
         *
         * @return a fully blank group, never {@code null}
         */
        public static AccountData initialize() {
            BigDecimal zero = CobolDecimal.monetaryZero();
            return new AccountData(0L, "", zero, zero, zero, "", "", "", "", "", "", "", "", "",
                    zero, zero, "");
        }

        /**
         * {@code ACUP-...-ACCT-ID-X}, the {@code PIC X(11)} character view of the same span
         * {@link #acctId()} reads as {@code PIC 9(11)} - the second of the two accessors gate G34
         * requires over one span.
         *
         * @param codec the codec, which owns the {@code PIC 9} zero-fill rule
         * @return exactly {@value #ACCT_ID_LENGTH} digits
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String acctIdImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render ACUP-...-ACCT-ID-X");
            return codec.movePic9(acctId, ACCT_ID_LENGTH);
        }

        /**
         * {@code ACUP-...-OPEN-DATE PIC X(08)}, the base view over the same span the three open-date
         * parts redefine ({@code app/cbl/COACTUPC.cbl:685-689}).
         *
         * @return exactly {@value #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String openDate() {
            return openYear + openMon + openDay;
        }

        /**
         * {@code ACUP-...-EXPIRAION-DATE PIC X(08)}, the base view over the three expiry parts
         * ({@code app/cbl/COACTUPC.cbl:692-696}). Misspelled in the source and misspelled here.
         *
         * @return exactly {@value #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String expiraionDate() {
            return expYear + expMon + expDay;
        }

        /**
         * {@code ACUP-...-REISSUE-DATE PIC X(08)}, the base view over the three reissue parts
         * ({@code app/cbl/COACTUPC.cbl:699-703}).
         *
         * @return exactly {@value #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String reissueDate() {
            return reissueYear + reissueMon + reissueDay;
        }

        /**
         * {@code ACUP-...-CURR-BAL PIC X(12)}, the character view of the span {@link #currBal()} reads
         * as {@code PIC S9(10)V99} ({@code app/cbl/COACTUPC.cbl:677-679}).
         *
         * @param codec the codec, which owns the zoned encoding and the sign overpunch
         * @return exactly {@value #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String currBalImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(currBal, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CREDIT-LIMIT PIC X(12)}, the character view of {@link #creditLimit()}.
         *
         * @param codec the codec
         * @return exactly {@value #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String creditLimitImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(creditLimit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CASH-CREDIT-LIMIT PIC X(12)}, the character view of
         * {@link #cashCreditLimit()}.
         *
         * @param codec the codec
         * @return exactly {@value #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String cashCreditLimitImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(cashCreditLimit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CURR-CYC-CREDIT PIC X(12)}, the character view of {@link #currCycCredit()}.
         *
         * @param codec the codec
         * @return exactly {@value #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String currCycCreditImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(currCycCredit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code ACUP-...-CURR-CYC-DEBIT PIC X(12)}, the character view of {@link #currCycDebit()}.
         *
         * @param codec the codec
         * @return exactly {@value #MONETARY_IMAGE_LENGTH} characters
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String currCycDebitImage(FixedWidthCodec codec) {
            return requireCodec(codec).encodeSignedScaled(currCycDebit, MONETARY_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * A diagnostic rendering that names the record without disclosing its money.
         *
         * <p>The generated record rendering printed all seventeen components verbatim, which put the
         * account number, the current balance, both credit limits and both cycle amounts into any log line
         * that rendered one (CWE-532), and let a fixed-width field carrying CR or LF forge a second line
         * (CWE-117).
         *
         * <p>The account number is masked to its last four digits - enough to tell one record from another
         * while diagnosing a parity failure, which is what {@link SensitiveDiagnostics.Disclosure#IDENTIFIER}
         * is for - and every monetary item is withheld. The date parts are integers a caller supplied and
         * carry no personal data, so they stay; the status flag and the group id are escaped rather than
         * interpolated, because both are {@code PIC X} and can hold any byte moved into them.
         *
         * <p>Accessors and the {@code Image} methods are untouched: this rendering has no COBOL
         * counterpart, so withholding from it costs no observable behaviour.
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
     * {@code ACUP-OLD-CUST-DATA} ({@code app/cbl/COACTUPC.cbl:711-756}) or
     * {@code ACUP-NEW-CUST-DATA} ({@code :799-855}) - the customer half of a detail group.
     *
     * <p>Two shapes here need naming, because both are {@code REDEFINES} overlays and both are used.
     *
     * <p><strong>The telephone numbers.</strong> Each is one {@code PIC X(15)} item with an overlay
     * splitting it as {@code FILLER X(1)}, area code {@code X(3)}, {@code FILLER X(1)}, prefix
     * {@code X(3)}, {@code FILLER X(1)}, line number {@code X(4)}, {@code FILLER X(2)}
     * ({@code :724-741} and {@code :811-828}). The three {@code FILLER} bytes hold {@code '('},
     * {@code ')'} and {@code '-'}, which is exactly what the {@code STRING} at {@code :4035-4041}
     * writes back - so the composed form and the three parts are two views of one span, and
     * {@link #phoneNum1A()}, {@link #phoneNum1B()} and {@link #phoneNum1C()} read the parts out of the
     * composed value rather than storing them twice (gate G34).
     *
     * <p><strong>The date of birth.</strong> {@code ACUP-...-CUST-DOB-YYYY-MM-DD} is {@code PIC X(08)}
     * with a three-part overlay ({@code :747-751}), and it holds {@code YYYYMMDD} with <em>no</em>
     * separators - which is why {@code 9700-CHECK-CHANGE-IN-REC} slices it at {@code (1:4)},
     * {@code (5:2)} and {@code (7:2)} while slicing the record's ten-character field at {@code (1:4)},
     * {@code (6:2)} and {@code (9:2)}. Holding the three parts makes that asymmetry structural instead
     * of arithmetic.
     *
     * @param custId        {@code ACUP-...-CUST-ID}, the {@code PIC 9(09)} redefinition of the
     *                      {@code PIC X(09)} span at {@code :713-715}
     * @param firstName     {@code ACUP-...-CUST-FIRST-NAME PIC X(25)} at {@code :716}
     * @param middleName    {@code ACUP-...-CUST-MIDDLE-NAME PIC X(25)} at {@code :717}
     * @param lastName      {@code ACUP-...-CUST-LAST-NAME PIC X(25)} at {@code :718}
     * @param addrLine1     {@code ACUP-...-CUST-ADDR-LINE-1 PIC X(50)} at {@code :719}
     * @param addrLine2     {@code ACUP-...-CUST-ADDR-LINE-2 PIC X(50)} at {@code :720}
     * @param addrLine3     {@code ACUP-...-CUST-ADDR-LINE-3 PIC X(50)} at {@code :721}
     * @param addrStateCd   {@code ACUP-...-CUST-ADDR-STATE-CD PIC X(02)} at {@code :722}
     * @param addrCountryCd {@code ACUP-...-CUST-ADDR-COUNTRY-CD PIC X(03)} at {@code :723}
     * @param addrZip       {@code ACUP-...-CUST-ADDR-ZIP PIC X(10)} at {@code :724}
     * @param phoneNum1     {@code ACUP-...-CUST-PHONE-NUM-1 PIC X(15)} at {@code :725}
     * @param phoneNum2     {@code ACUP-...-CUST-PHONE-NUM-2 PIC X(15)} at {@code :734}
     * @param ssn           {@code ACUP-...-CUST-SSN}, the {@code PIC 9(09)} redefinition at
     *                      {@code :744-746}. In the new group the span underneath is the three-part
     *                      {@code ACUP-NEW-CUST-SSN-X} of {@code :832-836}; in the old group it is a
     *                      single {@code PIC X(09)} item
     * @param govtIssuedId  {@code ACUP-...-CUST-GOVT-ISSUED-ID PIC X(20)} at {@code :746}
     * @param dobYear       {@code ACUP-...-CUST-DOB-YEAR PIC X(4)} at {@code :749}
     * @param dobMon        {@code ACUP-...-CUST-DOB-MON PIC X(2)} at {@code :750}
     * @param dobDay        {@code ACUP-...-CUST-DOB-DAY PIC X(2)} at {@code :751}
     * @param eftAccountId  {@code ACUP-...-CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code :752}
     * @param priHolderInd  {@code ACUP-...-CUST-PRI-HOLDER-IND PIC X(01)} at {@code :753}
     * @param ficoScore     {@code ACUP-...-CUST-FICO-SCORE}, the {@code PIC 9(03)} redefinition at
     *                      {@code :754-756}. The new group carries
     *                      {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} over it
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
         * ({@code app/cbl/COACTUPC.cbl:725,734}), which is also
         * {@code CUST-PHONE-NUM-1 PIC X(15)} in {@code app/cpy/CVCUS01Y.cpy:14}.
         */
        public static final int PHONE_NUM_LENGTH = CustomerRecord.CUST_PHONE_NUM_1.length();

        /**
         * The one-based position of {@code ACUP-...-CUST-PHONE-NUM-1A} inside the fifteen-character
         * span: 2, because a single {@code FILLER PIC X(1)} precedes it ({@code :726-728}).
         */
        public static final int PHONE_AREA_CODE_START = 2;

        /** The declared width of {@code ACUP-...-CUST-PHONE-NUM-1A PIC X(3)} ({@code :728}). */
        public static final int PHONE_AREA_CODE_LENGTH = 3;

        /**
         * The one-based position of {@code ACUP-...-CUST-PHONE-NUM-1B}: 6, after the area code and the
         * closing parenthesis ({@code :729-730}).
         */
        public static final int PHONE_PREFIX_START =
                PHONE_AREA_CODE_START + PHONE_AREA_CODE_LENGTH + ONE;

        /** The declared width of {@code ACUP-...-CUST-PHONE-NUM-1B PIC X(3)} ({@code :730}). */
        public static final int PHONE_PREFIX_LENGTH = 3;

        /**
         * The one-based position of {@code ACUP-...-CUST-PHONE-NUM-1C}: 10, after the prefix and the
         * hyphen ({@code :731-732}).
         */
        public static final int PHONE_LINE_NUMBER_START = PHONE_PREFIX_START + PHONE_PREFIX_LENGTH + ONE;

        /** The declared width of {@code ACUP-...-CUST-PHONE-NUM-1C PIC X(4)} ({@code :732}). */
        public static final int PHONE_LINE_NUMBER_LENGTH = 4;

        /** The declared width of {@code ACUP-...-CUST-ID-X PIC X(09)} ({@code :713}). */
        public static final int CUST_ID_LENGTH = CustomerRecord.CUST_ID.length();

        /** The declared width of {@code ACUP-...-CUST-SSN-X PIC X(09)} ({@code :743}). */
        public static final int SSN_LENGTH = CustomerRecord.CUST_SSN.length();

        /** The declared width of {@code ACUP-...-CUST-FICO-SCORE-X PIC X(03)} ({@code :754}). */
        public static final int FICO_SCORE_LENGTH = CustomerRecord.CUST_FICO_CREDIT_SCORE.length();

        /**
         * Normalises every character component to its declared width, taking the widths from
         * {@link CustomerRecord}'s spans - which are the same widths {@code ACUP-OLD-CUST-DATA} and
         * {@code CUST-UPDATE-RECORD} declare, verified span by span.
         *
         * @throws NullPointerException if any reference component is {@code null}
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
         * The group as an unqualified {@code INITIALIZE} leaves it: character spans holding their
         * declared width in spaces, numeric spans zero.
         *
         * @return a fully blank group, never {@code null}
         */
        public static CustomerData initialize() {
            return new CustomerData(0, "", "", "", "", "", "", "", "", "", "", "", 0, "", "", "", "",
                    "", "", 0);
        }

        /**
         * {@code ACUP-...-CUST-ID-X}, the {@code PIC X(09)} character view of the span
         * {@link #custId()} reads as {@code PIC 9(09)} (gate G34).
         *
         * @param codec the codec, which owns the {@code PIC 9} zero-fill rule
         * @return exactly nine digits
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String custIdImage(FixedWidthCodec codec) {
            return requireCodec(codec).movePic9(custId, CUST_ID_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-SSN-X}, the character view of the span {@link #ssn()} reads as
         * {@code PIC 9(09)}. This is the form {@code 9700-CHECK-CHANGE-IN-REC} compares at
         * {@code app/cbl/COACTUPC.cbl:4171}, because the record's {@code CUST-SSN} is also
         * {@code PIC 9(09)} and one form has to be rendered into the other's.
         *
         * @param codec the codec, which owns the {@code PIC 9} zero-fill rule
         * @return exactly nine digits
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public String ssnImage(FixedWidthCodec codec) {
            return requireCodec(codec).movePic9(ssn, SSN_LENGTH);
        }

        /**
         * {@code ACUP-...-CUST-FICO-SCORE-X}, the character view of the span {@link #ficoScore()} reads
         * as {@code PIC 9(03)}, and the form compared at {@code app/cbl/COACTUPC.cbl:4191}.
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
         * <p>Eight characters and <strong>no separators</strong>, unlike the record's
         * {@code CUST-DOB-YYYY-MM-DD PIC X(10)}. That difference is the whole reason
         * {@code 9700-CHECK-CHANGE-IN-REC} slices the two sides at different offsets.
         *
         * @return exactly {@value #SNAPSHOT_DATE_LENGTH} characters in {@code YYYYMMDD} shape
         */
        public String custDobYyyyMmDd() {
            return dobYear + dobMon + dobDay;
        }

        /**
         * A diagnostic rendering that withholds every personally identifying component (CWE-532).
         *
         * <p>A record's generated {@code toString} publishes every component verbatim, and this subgroup
         * holds a customer's names, postal address, both telephone numbers, social security number,
         * government-issued identifier, date of birth and electronic-funds account identifier. Anything
         * that renders the object - a log statement, an assertion message, a debugger's variable view -
         * would put all of that into a log file, which is precisely the disclosure the module's
         * structural guard forbids.
         *
         * <p><strong>Nothing about the data changes here, and nothing about parity changes.</strong>
         * Every component remains fully available through its own accessor, through
         * {@link #ssnImage(FixedWidthCodec)}, {@link #custDobYyyyMmDd()} and the rest, and through
         * {@link #stageCustomerUpdateImage(AccountUpdateDetails, FixedWidthCodec)}, which is what
         * {@code 9600-WRITE-PROCESSING} actually writes. Only this rendering withholds anything, and it
         * withholds it only because something rendered the object rather than because a caller asked for
         * a value by name.
         *
         * <p>The customer identifier is <strong>masked to its last four digits</strong> rather than left
         * legible. It is the {@code CUSTDAT} key {@code 9600-WRITE-PROCESSING} reads on at
         * {@code app/cbl/COACTUPC.cbl:3920-3931}, so a reader diagnosing a parity failure still needs to
         * tell one record from another - four digits do that - but a whole customer number alongside the
         * described name and address fields would re-identify the person the rest of this rendering is
         * careful not to name. {@link SensitiveDiagnostics.Disclosure#IDENTIFIER} is the module's stated
         * treatment for a customer key and this now follows it.
         *
         * <p>The two-character state and three-character country codes, the holder indicator and the
         * credit score carry no personal data and stay legible - but they are escaped to a single line
         * rather than interpolated raw, because a fixed-width field can hold any byte a caller moved into
         * it and a CR or LF among them would forge a second log line (CWE-117).
         *
         * @return a single-line description with every identifying component withheld, never
         *         {@code null}
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
     * <p>The nesting mirrors the source exactly. {@code 05 ACUP-OLD-DETAILS} contains
     * {@code 10 ACUP-OLD-ACCT-DATA} and {@code 10 ACUP-OLD-CUST-DATA}, and
     * {@code 9700-CHECK-CHANGE-IN-REC}'s two comparison blocks correspond one-to-one to those two
     * subgroups - which is why they are separate types rather than one flat record of thirty-seven
     * components.
     *
     * @param group     which of the two groups this is
     * @param acctData  the {@code ...-ACCT-DATA} subgroup
     * @param custData  the {@code ...-CUST-DATA} subgroup
     */
    public record AccountUpdateDetails(DetailGroup group, AccountData acctData,
                                      CustomerData custData) {

        /**
         * Rejects an incomplete group.
         *
         * @throws NullPointerException if any component is {@code null}
         */
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
         * This group with a different account subgroup, leaving the customer subgroup and the
         * discriminant alone.
         *
         * @param newAcctData the replacement subgroup
         * @return a new group, never {@code null}
         * @throws NullPointerException if {@code newAcctData} is {@code null}
         */
        public AccountUpdateDetails withAcctData(AccountData newAcctData) {
            return new AccountUpdateDetails(group, newAcctData, custData);
        }

        /**
         * This group with a different customer subgroup, leaving the account subgroup and the
         * discriminant alone.
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
         * <p>The generated record rendering recursed into both components, so the whole of
         * {@link AccountData} and {@link CustomerData} reached the log through it. Naming them explicitly
         * means each is rendered by its own override, and a component added here later cannot slip past
         * unclassified.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "ACUP-" + group + "-DETAILS[acctData=" + acctData + ", custData=" + custData + ']';
        }
    }

    /**
     * The COBOL {@code PIC X} receiving rule, applied to a value being placed into a fixed-width
     * character span: right-space-padded when short, right-truncated when long.
     *
     * <p>Used by the two detail subgroups' canonical constructors, so every component of a group is at
     * its declared width before any comparison touches it. That is what lets
     * {@code 9700-CHECK-CHANGE-IN-REC}'s comparisons be plain equality: COBOL compares two
     * {@code PIC X(n)} items of the same length character by character, and both sides being normalised
     * makes the Java comparison the same operation.
     *
     * @param value         the sending value; must not be {@code null}
     * @param declaredWidth the receiver's declared width
     * @param cobolName     the receiver's COBOL name, for the message
     * @return exactly {@code declaredWidth} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    /**
     * What a withheld number renders as, in place of {@code CUST-SSN}'s nine digits.
     *
     * <p>A fixed token rather than a length, because {@code CUST-SSN PIC 9(09)} is always nine digits,
     * so a length would be a constant that tells a reader nothing while still confirming the field was
     * populated.
     */
    private static final String WITHHELD_NUMBER = "<withheld>";

    /** What a withheld date renders as, in place of {@code CUST-DOB-YYYY-MM-DD}'s eight characters. */
    private static final String WITHHELD_DATE = "<withheld date>";

    /** What a withheld text field renders as when it holds something. */
    private static final String WITHHELD_TEXT_PREFIX = "<withheld text, length=";

    /** The closing bracket of a withheld text field's rendering. */
    private static final String WITHHELD_TEXT_SUFFIX = ">";

    /** What a withheld text field renders as when it is entirely blank, which discloses nothing. */
    private static final String WITHHELD_BLANK = "<blank>";

    /** What an absent staged image renders as; a present one is described by its width. */
    private static final String WITHHELD_ABSENT = "<absent>";

    /**
     * Describes a personally identifying text field without publishing it (CWE-532).
     *
     * <p>The length is disclosed and the content is not, because a length is what makes a fixed-width
     * parity problem diagnosable - a {@code PIC X(25)} field rendering at length 20 is a bug worth
     * seeing - while the characters themselves are the personal data. A blank field is named as such
     * rather than described by length, since it discloses nothing and is worth distinguishing from a
     * populated field when reading a log.
     *
     * <p>No {@code null} guard, and that is deliberate rather than an omission: every caller is a
     * component of {@link CustomerData}, whose canonical constructor puts each of them through
     * {@link #picX(String, int, String)} first, and that rejects {@code null}. A guard here would be a
     * branch nothing can reach.
     *
     * @param value the field's content, already normalised to its declared width and never {@code null}
     * @return a description that never contains any character of {@code value}, never {@code null}
     */
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
            // COBOL fills a PIC X receiver from the left and discards the overflow.
            return value.substring(0, declaredWidth);
        }
        return value + String.valueOf(SPACE).repeat(declaredWidth - value.length());
    }

    /**
     * States a monetary component's scale, applied by the account subgroup's canonical constructor.
     *
     * <p>{@link CobolDecimal#storeMonetary(BigDecimal)} truncates to scale
     * {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE} rather than rounding,
     * because {@code ROUNDED} appears nowhere in the source (gate G24). A caller handing over a
     * higher-scale intermediate therefore gets the same result the COBOL store would give.
     *
     * @param value     the value; must not be {@code null}
     * @param cobolName the receiver's COBOL name, for the message
     * @return {@code value} at exactly the monetary scale
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static BigDecimal monetary(BigDecimal value, String cobolName) {
        Objects.requireNonNull(value, cobolName + " is PIC S9(" + MONETARY_INTEGER_DIGITS
                + ")V99, so it holds a value and is never absent; a blank span is zero, not null");
        return CobolDecimal.storeMonetary(value);
    }

    /**
     * Refuses a missing codec from any of the value objects' image accessors, with one message rather
     * than eleven.
     *
     * @param codec the codec
     * @return {@code codec}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    private static FixedWidthCodec requireCodec(FixedWidthCodec codec) {
        return Objects.requireNonNull(codec, "A codec is required to render a fixed-width span as the "
                + "characters it holds: the code page is never assumed");
    }

    /**
     * Which of the two comparison blocks of {@code 9700-CHECK-CHANGE-IN-REC} a difference was found in.
     *
     * <p>The order is the source's, and it matters: the account block's {@code GO TO} at
     * {@code app/cbl/COACTUPC.cbl:4147} leaves the paragraph, so the customer block at {@code :4152} is
     * never evaluated when the account block fails (gate G30). The source's own comment at
     * {@code :4149-4151} explains the split as being "for easier reading", and speculates about using
     * it to update only one file - which it does not do.
     */
    public enum Block {

        /**
         * The account master block, {@code app/cbl/COACTUPC.cbl:4115-4148} - sixteen comparisons,
         * evaluated first.
         */
        ACCOUNT_MASTER("Account Master data", "4115-4148"),

        /**
         * The customer block, {@code app/cbl/COACTUPC.cbl:4152-4192} - nineteen comparisons, reached
         * only when all sixteen of the account block's held.
         */
        CUSTOMER("Customer  data", "4152-4192");

        /** The source's own caption for the block, verbatim. */
        private final String cobolCaption;

        /** The block's {@code app/cbl/COACTUPC.cbl} line range. */
        private final String sourceLines;

        /**
         * @param cobolCaption the source's caption
         * @param sourceLines  the line range
         */
        Block(String cobolCaption, String sourceLines) {
            this.cobolCaption = cobolCaption;
            this.sourceLines = sourceLines;
        }

        /**
         * The source's own caption for this block, verbatim - including the two consecutive spaces in
         * {@code "Customer  data"} at {@code app/cbl/COACTUPC.cbl:4149}, which is reproduced rather
         * than tidied.
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
     *
     * <p>Declaring this per item rather than leaving it implicit in the comparison code is what makes
     * the folding policy - upper here, lower there, and neither for eight of the customer items -
     * assertable from a test rather than only readable in the implementation.
     */
    public enum Comparison {

        /**
         * A direct {@code EQUAL} between two {@code PIC X} or two {@code PIC 9} items of the same
         * declared width, with no intervening {@code FUNCTION} call. A difference of case <em>is</em> a
         * difference.
         */
        EXACT,

        /**
         * Both operands wrapped in {@code FUNCTION LOWER-CASE}. Used once, on
         * {@code ACCT-GROUP-ID} at {@code app/cbl/COACTUPC.cbl:4144-4145}.
         */
        LOWER_CASE_FOLDED,

        /**
         * Both operands wrapped in {@code FUNCTION UPPER-CASE}. Used on eight customer items at
         * {@code app/cbl/COACTUPC.cbl:4152-4167} and {@code :4180-4181}.
         */
        UPPER_CASE_FOLDED,

        /**
         * A {@code PIC S9(10)V99} {@code EQUAL} between the record's value and the
         * {@code ACUP-OLD-...-N} redefinition of the snapshot's {@code PIC X(12)} span. Performed with
         * {@link BigDecimal#compareTo(BigDecimal)} so a scale difference cannot manufacture a mismatch.
         */
        MONETARY
    }

    /**
     * Every item {@code 9700-CHECK-CHANGE-IN-REC} compares, in the order the source writes them:
     * sixteen account-master items then nineteen customer items, thirty-five in all.
     *
     * <p>Each constant carries its COBOL name verbatim, the block it belongs to, how its operands are
     * treated and the line that compares it. The names include the reference-modification suffixes -
     * {@code "ACCT-OPEN-DATE(1:4)"} rather than a paraphrase - because a field-by-field differ compares
     * <em>names</em>, and a name that does not appear in the source is a name nobody can trace.
     */
    public enum ComparedItem {

        /** {@code ACCT-ACTIVE-STATUS EQUAL ACUP-OLD-ACTIVE-STATUS} ({@code :4115}). */
        ACCT_ACTIVE_STATUS("ACCT-ACTIVE-STATUS", Block.ACCOUNT_MASTER, Comparison.EXACT, "4115"),

        /** {@code ACCT-CURR-BAL EQUAL ACUP-OLD-CURR-BAL-N} ({@code :4117}). */
        ACCT_CURR_BAL("ACCT-CURR-BAL", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4117"),

        /** {@code ACCT-CREDIT-LIMIT EQUAL ACUP-OLD-CREDIT-LIMIT-N} ({@code :4119}). */
        ACCT_CREDIT_LIMIT("ACCT-CREDIT-LIMIT", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4119"),

        /** {@code ACCT-CASH-CREDIT-LIMIT EQUAL ACUP-OLD-CASH-CREDIT-LIMIT-N} ({@code :4121}). */
        ACCT_CASH_CREDIT_LIMIT("ACCT-CASH-CREDIT-LIMIT", Block.ACCOUNT_MASTER, Comparison.MONETARY,
                "4121"),

        /** {@code ACCT-CURR-CYC-CREDIT EQUAL ACUP-OLD-CURR-CYC-CREDIT-N} ({@code :4123}). */
        ACCT_CURR_CYC_CREDIT("ACCT-CURR-CYC-CREDIT", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4123"),

        /** {@code ACCT-CURR-CYC-DEBIT EQUAL ACUP-OLD-CURR-CYC-DEBIT-N} ({@code :4125}). */
        ACCT_CURR_CYC_DEBIT("ACCT-CURR-CYC-DEBIT", Block.ACCOUNT_MASTER, Comparison.MONETARY, "4125"),

        /** {@code ACCT-OPEN-DATE(1:4) EQUAL ACUP-OLD-OPEN-YEAR} ({@code :4127}). */
        ACCT_OPEN_DATE_YEAR("ACCT-OPEN-DATE(1:4)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4127"),

        /** {@code ACCT-OPEN-DATE(6:2) EQUAL ACUP-OLD-OPEN-MON} ({@code :4128}). */
        ACCT_OPEN_DATE_MONTH("ACCT-OPEN-DATE(6:2)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4128"),

        /** {@code ACCT-OPEN-DATE(9:2) EQUAL ACUP-OLD-OPEN-DAY} ({@code :4129}). */
        ACCT_OPEN_DATE_DAY("ACCT-OPEN-DATE(9:2)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4129"),

        /**
         * {@code ACCT-EXPIRAION-DATE(1:4) EQUAL ACUP-OLD-EXP-YEAR} ({@code :4131}). The name is missing
         * the {@code T} of {@code EXPIRATION} in every place the reference tree spells it, and it is
         * preserved (implicit requirement I1).
         */
        ACCT_EXPIRAION_DATE_YEAR("ACCT-EXPIRAION-DATE(1:4)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4131"),

        /** {@code ACCT-EXPIRAION-DATE(6:2) EQUAL ACUP-OLD-EXP-MON} ({@code :4132}). */
        ACCT_EXPIRAION_DATE_MONTH("ACCT-EXPIRAION-DATE(6:2)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4132"),

        /** {@code ACCT-EXPIRAION-DATE(9:2) EQUAL ACUP-OLD-EXP-DAY} ({@code :4133}). */
        ACCT_EXPIRAION_DATE_DAY("ACCT-EXPIRAION-DATE(9:2)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4133"),

        /** {@code ACCT-REISSUE-DATE(1:4) EQUAL ACUP-OLD-REISSUE-YEAR} ({@code :4135}). */
        ACCT_REISSUE_DATE_YEAR("ACCT-REISSUE-DATE(1:4)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4135"),

        /** {@code ACCT-REISSUE-DATE(6:2) EQUAL ACUP-OLD-REISSUE-MON} ({@code :4136}). */
        ACCT_REISSUE_DATE_MONTH("ACCT-REISSUE-DATE(6:2)", Block.ACCOUNT_MASTER, Comparison.EXACT,
                "4136"),

        /** {@code ACCT-REISSUE-DATE(9:2) EQUAL ACUP-OLD-REISSUE-DAY} ({@code :4137}). */
        ACCT_REISSUE_DATE_DAY("ACCT-REISSUE-DATE(9:2)", Block.ACCOUNT_MASTER, Comparison.EXACT, "4137"),

        /**
         * {@code FUNCTION LOWER-CASE (ACCT-GROUP-ID) EQUAL FUNCTION LOWER-CASE (ACUP-OLD-GROUP-ID)}
         * ({@code :4139-4140}). The only lower-case fold in the paragraph.
         */
        ACCT_GROUP_ID("ACCT-GROUP-ID", Block.ACCOUNT_MASTER, Comparison.LOWER_CASE_FOLDED, "4139"),

        /** {@code FUNCTION UPPER-CASE (CUST-FIRST-NAME) EQUAL ...} ({@code :4152-4153}). */
        CUST_FIRST_NAME("CUST-FIRST-NAME", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4152"),

        /** {@code FUNCTION UPPER-CASE (CUST-MIDDLE-NAME) EQUAL ...} ({@code :4154-4155}). */
        CUST_MIDDLE_NAME("CUST-MIDDLE-NAME", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4154"),

        /** {@code FUNCTION UPPER-CASE (CUST-LAST-NAME) EQUAL ...} ({@code :4156-4157}). */
        CUST_LAST_NAME("CUST-LAST-NAME", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4156"),

        /** {@code FUNCTION UPPER-CASE (CUST-ADDR-LINE-1) EQUAL ...} ({@code :4158-4159}). */
        CUST_ADDR_LINE_1("CUST-ADDR-LINE-1", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4158"),

        /** {@code FUNCTION UPPER-CASE (CUST-ADDR-LINE-2) EQUAL ...} ({@code :4160-4161}). */
        CUST_ADDR_LINE_2("CUST-ADDR-LINE-2", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4160"),

        /** {@code FUNCTION UPPER-CASE (CUST-ADDR-LINE-3) EQUAL ...} ({@code :4162-4163}). */
        CUST_ADDR_LINE_3("CUST-ADDR-LINE-3", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4162"),

        /** {@code FUNCTION UPPER-CASE (CUST-ADDR-STATE-CD) EQUAL ...} ({@code :4164-4165}). */
        CUST_ADDR_STATE_CD("CUST-ADDR-STATE-CD", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED, "4164"),

        /** {@code FUNCTION UPPER-CASE (CUST-ADDR-COUNTRY-CD) EQUAL ...} ({@code :4166-4167}). */
        CUST_ADDR_COUNTRY_CD("CUST-ADDR-COUNTRY-CD", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED,
                "4166"),

        /**
         * {@code CUST-ADDR-ZIP EQUAL ACUP-OLD-CUST-ADDR-ZIP} ({@code :4168}) - unfolded, so a
         * difference of case in a postal code is a change.
         */
        CUST_ADDR_ZIP("CUST-ADDR-ZIP", Block.CUSTOMER, Comparison.EXACT, "4168"),

        /** {@code CUST-PHONE-NUM-1 EQUAL ACUP-OLD-CUST-PHONE-NUM-1} ({@code :4169}) - unfolded. */
        CUST_PHONE_NUM_1("CUST-PHONE-NUM-1", Block.CUSTOMER, Comparison.EXACT, "4169"),

        /** {@code CUST-PHONE-NUM-2 EQUAL ACUP-OLD-CUST-PHONE-NUM-2} ({@code :4170}) - unfolded. */
        CUST_PHONE_NUM_2("CUST-PHONE-NUM-2", Block.CUSTOMER, Comparison.EXACT, "4170"),

        /** {@code CUST-SSN EQUAL ACUP-OLD-CUST-SSN} ({@code :4171}) - two {@code PIC 9(09)} items. */
        CUST_SSN("CUST-SSN", Block.CUSTOMER, Comparison.EXACT, "4171"),

        /** {@code FUNCTION UPPER-CASE (CUST-GOVT-ISSUED-ID) EQUAL ...} ({@code :4172-4173}). */
        CUST_GOVT_ISSUED_ID("CUST-GOVT-ISSUED-ID", Block.CUSTOMER, Comparison.UPPER_CASE_FOLDED,
                "4172"),

        /**
         * {@code CUST-DOB-YYYY-MM-DD (1:4) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (1:4)}
         * ({@code :4174-4175}) - the one slice of the three whose offsets do agree.
         */
        CUST_DOB_YEAR("CUST-DOB-YYYY-MM-DD(1:4)", Block.CUSTOMER, Comparison.EXACT, "4174"),

        /**
         * {@code CUST-DOB-YYYY-MM-DD (6:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)}
         * ({@code :4176-4177}) - <strong>different offsets on the two sides</strong>, because the record
         * field is ten characters with separators and the snapshot's is eight without.
         */
        CUST_DOB_MONTH("CUST-DOB-YYYY-MM-DD(6:2)", Block.CUSTOMER, Comparison.EXACT, "4176"),

        /**
         * {@code CUST-DOB-YYYY-MM-DD (9:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)}
         * ({@code :4178-4179}) - the second asymmetric pair.
         */
        CUST_DOB_DAY("CUST-DOB-YYYY-MM-DD(9:2)", Block.CUSTOMER, Comparison.EXACT, "4178"),

        /**
         * {@code CUST-EFT-ACCOUNT-ID EQUAL ACUP-OLD-CUST-EFT-ACCOUNT-ID} ({@code :4181-4182}) -
         * unfolded, even though it is a {@code PIC X(10)} item that can hold letters.
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

        /** The COBOL name of the record-side operand, verbatim. */
        private final String cobolName;

        /** Which comparison block this item belongs to. */
        private final Block block;

        /** How the two operands are treated. */
        private final Comparison comparison;

        /** The {@code app/cbl/COACTUPC.cbl} line the comparison starts on. */
        private final String sourceLine;

        /**
         * @param cobolName  the record-side operand's COBOL name
         * @param block      the comparison block
         * @param comparison how the operands are treated
         * @param sourceLine the line the comparison starts on
         */
        ComparedItem(String cobolName, Block block, Comparison comparison, String sourceLine) {
            this.cobolName = cobolName;
            this.block = block;
            this.comparison = comparison;
            this.sourceLine = sourceLine;
        }

        /**
         * The COBOL name of the record-side operand, verbatim, including any
         * reference-modification suffix.
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

        /**
         * How this item's two operands are treated.
         *
         * @return the comparison kind, never {@code null}
         */
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
     * What {@code 9600-WRITE-PROCESSING} concluded ({@code app/cbl/COACTUPC.cbl:3889-4106}), in the
     * shape the caller's {@code EVALUATE TRUE} at {@code :2603-2614} consumes.
     *
     * <p>These are five arms of one 75-character field, not five flags - see this class's
     * documentation - so exactly one of them holds at a time. <strong>The declaration order is the
     * caller's {@code WHEN} order</strong> and it is load-bearing (gate G30):
     *
     * <pre>
     * EVALUATE TRUE
     *    WHEN COULD-NOT-LOCK-ACCT-FOR-UPDATE   SET ACUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE
     *    WHEN LOCKED-BUT-UPDATE-FAILED         SET ACUP-CHANGES-OKAYED-BUT-FAILED TO TRUE
     *    WHEN DATA-WAS-CHANGED-BEFORE-UPDATE   SET ACUP-SHOW-DETAILS              TO TRUE
     *    WHEN OTHER                            SET ACUP-CHANGES-OKAYED-AND-DONE   TO TRUE
     * END-EVALUATE
     * </pre>
     *
     * <p><strong>Four arms for five outcomes.</strong> There is no
     * {@code WHEN COULD-NOT-LOCK-CUST-FOR-UPDATE}, so a customer record that could not be locked
     * reaches {@code WHEN OTHER} and is reported as success. That is a defect in the legacy program and
     * it is reproduced: see {@link #COULD_NOT_LOCK_CUST_FOR_UPDATE}.
     */
    public enum WriteOutcome {

        /**
         * {@code 88 COULD-NOT-LOCK-ACCT-FOR-UPDATE} - the {@code ACCTDAT} read-for-update at
         * {@code app/cbl/COACTUPC.cbl:3894-3903} returned anything but {@code DFHRESP(NORMAL)}. The
         * customer file is never read on this path and nothing is written. The caller's first
         * {@code WHEN}, at {@code :2604-2605}.
         */
        COULD_NOT_LOCK_ACCT_FOR_UPDATE("L", MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE,
                "COULD-NOT-LOCK-ACCT-FOR-UPDATE", 1, false),

        /**
         * {@code 88 LOCKED-BUT-UPDATE-FAILED} - both locks were taken and the concurrency check passed,
         * but a rewrite failed. Reached from the account rewrite at
         * {@code app/cbl/COACTUPC.cbl:4076-4081} or the customer rewrite at {@code :4096-4103}; only the
         * latter also requests a rollback. The caller's second {@code WHEN}, at {@code :2606-2607}.
         */
        LOCKED_BUT_UPDATE_FAILED("F", MSG_LOCKED_BUT_UPDATE_FAILED, "LOCKED-BUT-UPDATE-FAILED", 2,
                false),

        /**
         * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} - a record changed under the screen, so the update
         * is refused and nothing is written. The caller's third {@code WHEN}, at {@code :2608-2609},
         * whose action is {@code SET ACUP-SHOW-DETAILS TO TRUE} - the {@code 'S'} code, which repaints
         * the detail screen rather than reporting a failure.
         */
        DATA_WAS_CHANGED_BEFORE_UPDATE("S", MSG_DATA_WAS_CHANGED_BEFORE_UPDATE,
                "DATA-WAS-CHANGED-BEFORE-UPDATE", 3, false),

        /**
         * {@code 88 COULD-NOT-LOCK-CUST-FOR-UPDATE} - the {@code CUSTDAT} read-for-update at
         * {@code app/cbl/COACTUPC.cbl:3922-3931} returned anything but {@code DFHRESP(NORMAL)}. The
         * account record is already locked; nothing is written.
         *
         * <p><strong>The caller has no arm for this.</strong> {@code :2603-2614} tests three of the four
         * message literals and this is the one it omits, so control reaches {@code WHEN OTHER} and the
         * operator is told the changes were committed. Hence the {@code "C"} action code and the
         * {@code WHEN OTHER} position below - both are what the program does. {@link #isRewritten()}
         * still answers {@code false}, because nothing was written, so a caller that asks the right
         * question gets the right answer even though the legacy screen does not.
         */
        COULD_NOT_LOCK_CUST_FOR_UPDATE("C", MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE,
                "COULD-NOT-LOCK-CUST-FOR-UPDATE", 4, false),

        /**
         * {@code WHEN OTHER} - both locks were taken, nothing had changed, and both rewrites succeeded.
         * The message field is left exactly as the caller supplied it, which is normally off. The
         * caller's fourth arm, at {@code app/cbl/COACTUPC.cbl:2612-2613}.
         */
        CHANGES_OKAYED_AND_DONE("C", null, "WHEN OTHER", 4, true);

        /** The {@code ACUP-CHANGE-ACTION PIC X(1)} value the caller sets ({@code :652-668}). */
        private final String changeActionCode;

        /** The {@code WS-RETURN-MSG} literal this outcome names, or {@code null} for {@code WHEN OTHER}. */
        private final String returnMessage;

        /** The {@code 88}-level condition name, or {@code "WHEN OTHER"}. */
        private final String cobolCondition;

        /** The one-based position of the matching {@code WHEN} arm; 4 is {@code WHEN OTHER}. */
        private final int firstMatchWinsPosition;

        /** Whether reaching this outcome means both records were rewritten. */
        private final boolean rewritten;

        /**
         * @param changeActionCode       the {@code ACUP-CHANGE-ACTION} value
         * @param returnMessage          the message literal, or {@code null}
         * @param cobolCondition         the condition name
         * @param firstMatchWinsPosition the {@code WHEN} arm position
         * @param rewritten              whether both records were rewritten
         */
        WriteOutcome(String changeActionCode, String returnMessage, String cobolCondition,
                    int firstMatchWinsPosition, boolean rewritten) {
            this.changeActionCode = changeActionCode;
            this.returnMessage = returnMessage;
            this.cobolCondition = cobolCondition;
            this.firstMatchWinsPosition = firstMatchWinsPosition;
            this.rewritten = rewritten;
        }

        /**
         * The {@code ACUP-CHANGE-ACTION PIC X(1)} value the caller's {@code EVALUATE} sets for this
         * outcome ({@code app/cbl/COACTUPC.cbl:652-668}): {@code "L"} lock error, {@code "F"} failed,
         * {@code "S"} show details, {@code "C"} okayed and done.
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
         * {@code EVALUATE TRUE} ({@code app/cbl/COACTUPC.cbl:2603-2614}). Position 4 is
         * {@code WHEN OTHER}, and two outcomes share it - see
         * {@link #COULD_NOT_LOCK_CUST_FOR_UPDATE}.
         *
         * @return 1, 2, 3 or 4
         */
        public int firstMatchWinsPosition() {
            return firstMatchWinsPosition;
        }

        /**
         * Whether reaching this outcome means both records were rewritten. True for exactly one
         * outcome: {@link #CHANGES_OKAYED_AND_DONE}.
         *
         * @return {@code true} only when the write path ran to completion
         */
        public boolean isRewritten() {
            return rewritten;
        }

        /**
         * Whether this outcome is a lock failure, which is the only shape that sets
         * {@code 88 INPUT-ERROR} ({@code app/cbl/COACTUPC.cbl:3910} and {@code :3938}) and the only one
         * whose message is placed under the {@code WS-RETURN-MSG-OFF} guard.
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
     * <p>The COBOL communicates only through {@code WS-RETURN-MSG}, so a faithful minimum would be one
     * boolean. The block and the item set are added for two reasons that cost nothing at runtime: a
     * parity case has to be able to assert <em>which</em> comparison failed rather than only that one
     * did, and a support engineer reading a log needs the names without being given the values.
     *
     * @param dataWasChanged  whether {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} was set
     * @param failingBlock    which block short-circuited the paragraph, or empty when neither did
     * @param differingItems  the items that differed <em>within that block</em>, in declaration order.
     *                        Never contains items from both blocks, because the first failure leaves the
     *                        paragraph before the second block is evaluated
     */
    public record ChangeCheck(boolean dataWasChanged, Optional<Block> failingBlock,
                             Set<ComparedItem> differingItems) {

        /**
         * Rejects a result whose parts contradict each other, so a caller can rely on the invariant
         * rather than re-derive it.
         *
         * @throws NullPointerException     if {@code failingBlock} or {@code differingItems} is
         *                                  {@code null}
         * @throws IllegalArgumentException if the three components do not agree, or if the items span
         *                                  both blocks
         */
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
         * The unchanged arm: both blocks reached their {@code CONTINUE}, so the caller stages and
         * rewrites.
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
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code items} is empty or contains an item from another
         *                                  block
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
         * The COBOL names of the items that differed, comma-separated and prefixed with the block they
         * were found in, or a fixed phrase when nothing differed.
         *
         * <p>Names only. No value is included, ever: the compared items include the social security
         * number, the date of birth, the government-issued identifier, the address and both telephone
         * numbers, and this string is written to the log.
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
     * @param outcome                    which of the five arms was reached. Never {@code null}, and the
     *                                   only thing the caller has to switch on
     * @param inputError                 {@code 88 INPUT-ERROR} ({@code :173}). Set unconditionally by
     *                                   {@code :3910} and {@code :3938} and by nothing else in this
     *                                   paragraph, so it is {@code true} for the two lock failures
     *                                   alone. Carried separately from {@code outcome} because the two
     *                                   are genuinely separate in the source: the
     *                                   {@code WS-RETURN-MSG-OFF} guard can suppress the message
     *                                   without suppressing the error
     * @param syncpointRollbackRequested {@code EXEC CICS SYNCPOINT ROLLBACK} ({@code :4099-4101}). True
     *                                   for the customer rewrite failure only, where the account
     *                                   rewrite has already succeeded and must be backed out. Java has
     *                                   no {@code SYNCPOINT}, so the caller's transaction boundary
     *                                   performs it
     * @param returnMessage              {@code WS-RETURN-MSG PIC X(75)} ({@code :479}) as it stands
     *                                   after the paragraph, at exactly its declared width
     * @param fileStatus                 the two-character status of the last dataset operation this
     *                                   paragraph performed - {@link FileStatus#OK} where every
     *                                   operation succeeded or where none was reached
     * @param cicsResp                   {@code WS-RESP-CD}, present when the backend reported a CICS
     *                                   response for that operation
     * @param failedOperation            {@link #READ_OPERATION_NAME} or
     *                                   {@link #REWRITE_OPERATION_NAME} when an operation failed,
     *                                   otherwise empty - the {@code ERROR-OPNAME} of
     *                                   {@code WS-FILE-ERROR-MESSAGE} ({@code :392-395})
     * @param failedFileName             {@link #ACCT_CICS_FILE_NAME} or
     *                                   {@link #CUST_CICS_FILE_NAME} when an operation failed,
     *                                   otherwise empty - the {@code ERROR-FILE} of the same group
     * @param acctUpdateRecordImage      the staged {@code ACCT-UPDATE-RECORD}, verbatim, present once
     *                                   the change check passed. Exposed so a field-by-field differ can
     *                                   see the group identifier sitting at offset
     *                                   {@value #ACCT_UPDATE_GROUP_ID_OFFSET} rather than where
     *                                   {@code CVACT01Y} would put it
     * @param custUpdateRecordImage      the staged {@code CUST-UPDATE-RECORD}, verbatim, present under
     *                                   the same condition
     * @param changeCheck                what {@code 9700-CHECK-CHANGE-IN-REC} concluded, present
     *                                   whenever it ran - that is, whenever both locks were taken
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

        /**
         * Rejects a result whose parts contradict the paragraph they describe.
         *
         * @throws NullPointerException     if any reference component is {@code null}
         * @throws IllegalArgumentException if {@code returnMessage} is not exactly
         *                                  {@value #RETURN_MESSAGE_LENGTH} characters, if
         *                                  {@code fileStatus} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, if either
         *                                  staged image is the wrong width, or if any flag or optional
         *                                  disagrees with {@code outcome}
         */
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
         * <p>Worth preferring over the action code: {@link WriteOutcome#COULD_NOT_LOCK_CUST_FOR_UPDATE}
         * carries the same {@code "C"} code as success, because the caller's {@code EVALUATE} has no arm
         * for it, and this method is what tells the two apart.
         *
         * @return {@code true} only for {@link WriteOutcome#CHANGES_OKAYED_AND_DONE}
         */
        public boolean isRewritten() {
            return outcome.isRewritten();
        }

        /**
         * Whether {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} holds - the caller's cue to repaint the
         * detail screen rather than report a failure.
         *
         * @return {@code true} only for {@link WriteOutcome#DATA_WAS_CHANGED_BEFORE_UPDATE}
         */
        public boolean isDataWasChangedBeforeUpdate() {
            return outcome == WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE;
        }

        /**
         * A diagnostic rendering that withholds the two staged record images (CWE-532).
         *
         * <p>The generated {@code toString} would publish both images in full, and
         * {@code CUST-UPDATE-RECORD} is the whole 500-byte customer record: it contains the social
         * security number at its declared offset, the government-issued identifier, the date of birth,
         * both telephone numbers and the postal address, all as legible characters. That is the same
         * disclosure {@link CustomerData#toString()} withholds, reaching a log by a different route, so
         * it is withheld the same way. The account image is withheld for symmetry and because it carries
         * the account's balances.
         *
         * <p>Each image's <em>length</em> is disclosed, because the length is the parity-relevant fact -
         * gates G19 and G21 are about widths - and a length cannot disclose a value. Everything else
         * about the result is rendered plainly: the outcome, the flags, the file status, the response
         * code and the change check are all diagnostic metadata carrying no personal data.
         *
         * <p><strong>Nothing about the data changes here.</strong> Both images remain fully available
         * through {@link #acctUpdateRecordImage()} and {@link #custUpdateRecordImage()} for the parity
         * comparison, byte for byte, untrimmed.
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

        /**
         * Describes a staged record image by its width alone.
         *
         * @param image the image, present only when the paragraph reached the rewrites
         * @return {@code "<absent>"} or a width description, never {@code null} and never containing any
         *         character of the image
         */
        private static String imageDescription(Optional<String> image) {
            return image.map(staged -> WITHHELD_TEXT_PREFIX + staged.length() + WITHHELD_TEXT_SUFFIX)
                    .orElse(WITHHELD_ABSENT);
        }
    }

    /**
     * Carries a completed {@link WriteResult} out through a rollback.
     *
     * <p>{@code EXEC CICS SYNCPOINT ROLLBACK} at {@code app/cbl/COACTUPC.cbl:4099-4101} backs out the
     * account rewrite and the task then <em>continues</em> - it falls through to
     * {@code 9600-WRITE-PROCESSING-EXIT} and its caller reports {@code LOCKED-BUT-UPDATE-FAILED} to the
     * screen. So the rollback must not lose the result, and a plain {@code throw} of a failure would.
     *
     * <p>Unchecked, because {@link org.springframework.transaction.support.TransactionTemplate} rolls
     * back on unchecked exceptions and that is the only rollback signal available inside its body - see
     * {@link DatasetUnitOfWork#commitRefusal(String, String)}, which documents why. It never escapes
     * {@link #writeProcessing}: it is thrown inside the boundary and caught immediately outside it, so no
     * caller can see it and no caller has to know it exists.
     *
     * <p>Stack trace and suppression are both disabled. This is control flow, not a fault: there is
     * nothing about the throw site an operator needs, and filling in a trace on a path that runs whenever
     * a customer rewrite fails would be cost with no reader.
     */
    private static final class SyncpointRollback extends RuntimeException {

        /** Serialisation identity, required of every {@link RuntimeException} subclass. */
        private static final long serialVersionUID = 1L;

        /** The outcome the paragraph reached before it asked for the rollback. */
        private final transient WriteResult result;

        /**
         * @param result the outcome to carry out through the rollback
         */
        private SyncpointRollback(WriteResult result) {
            super("EXEC CICS SYNCPOINT ROLLBACK (app/cbl/COACTUPC.cbl:4099-4101): the ACCTDAT rewrite "
                    + "succeeded and the CUSTDAT rewrite did not, so the unit of work is rolled back and "
                    + "the paragraph's outcome is carried out to the caller", null, false, false);
            this.result = result;
        }

        /**
         * The outcome to return once the rollback has happened.
         *
         * @return the result; never {@code null}
         */
        private WriteResult result() {
            return result;
        }
    }
}
