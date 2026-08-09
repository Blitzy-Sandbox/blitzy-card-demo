package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.CardRepository.CardWriteResult;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;

import java.util.Objects;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

/**
 * The write path of {@code app/cbl/COCRDUPC.cbl} - the two paragraphs that lock a card record,
 * decide whether anyone changed it while the screen was being filled in, and rewrite it.
 *
 * <p>Exactly two COBOL paragraphs live here, and nothing else does:
 * <ul>
 *   <li>{@code 9200-WRITE-PROCESSING} ({@code app/cbl/COCRDUPC.cbl:1420-1496}) becomes
 *       {@link #writeProcessing(CardScreenState, CardDetails, CardDetails, String,
 *       FixedWidthCodec)};</li>
 *   <li>{@code 9300-CHECK-CHANGE-IN-REC} ({@code app/cbl/COCRDUPC.cbl:1498-1523}) becomes
 *       {@link #checkChangeInRec(CardRecord, CardDetails, FixedWidthCodec)}.</li>
 * </ul>
 * The screen painting, the field edits, the {@code RECEIVE MAP} handling and the {@code XCTL}
 * chaining of the same program are the controller's, not this class's. This class is where the
 * decisions are, which is the whole point of it: gate G51 requires the business logic to sit in a
 * service so a parity test can reach every branch with no HTTP layer and no {@code JobLauncher} in
 * the path. Every method below is reachable from a plain JUnit test that does nothing more than
 * {@code new CardUpdateService(mockCardRepository)}.
 *
 * <h2>This is genuine COBOL-side optimistic concurrency, and it stays that way (gates G43, G44)</h2>
 * <p>{@code 9300-CHECK-CHANGE-IN-REC} is not a modernisation. It is already in the 1990s source: the
 * program re-reads the record under a lock and compares six fields against the snapshot the screen
 * was painted from, and only rewrites when all six still agree. That comparison <strong>is</strong>
 * the concurrency control, and it is preserved field for field.
 *
 * <p><strong>No version column, no timestamp column, no row-version annotation and no schema change
 * of any kind may be introduced here.</strong> A version column would be the textbook Java answer and
 * it is forbidden twice over: gate G44 forbids DDL and entity annotations outright, and gate G43
 * requires this specific comparison, on these specific six fields, in this specific order. The card
 * number and the account identifier are deliberately <em>not</em> compared, because the COBOL does not
 * compare them.
 *
 * <h2>COBOL reference modification is 1-based; this is the highest off-by-one risk in the file</h2>
 * <p>{@code app/cbl/COCRDUPC.cbl:1505-1507} slices the record's ten-byte date three times. The
 * mapping to Java is fixed and is restated at every use site:
 * <table border="1">
 *   <caption>{@code CARD-EXPIRAION-DATE} slices, COBOL 1-based to Java 0-based</caption>
 *   <tr><th>COBOL</th><th>Characters</th><th>Java</th><th>Content</th></tr>
 *   <tr><td>{@code (1:4)}</td><td>1 to 4</td><td>{@code substring(0, 4)}</td><td>{@code YYYY}</td></tr>
 *   <tr><td>{@code (6:2)}</td><td>6 to 7</td><td>{@code substring(5, 7)}</td><td>{@code MM}</td></tr>
 *   <tr><td>{@code (9:2)}</td><td>9 to 10</td><td>{@code substring(8, 10)}</td><td>{@code DD}</td></tr>
 * </table>
 * <p>Characters 5 and 8 - Java indices 4 and 7 - are the two {@code '-'} separators, and they are
 * <strong>never compared</strong>. That is not an oversight to tidy up: a stored record whose
 * separators differ from the snapshot's compares <em>equal</em> in COBOL, and it must compare equal
 * here too. Collapsing the three slices into one whole-string comparison would change the answer, so
 * the slices are taken through {@link CardRecord#cardExpiraionDateYear()},
 * {@link CardRecord#cardExpiraionDateMonth()} and {@link CardRecord#cardExpiraionDateDay()}, which
 * carry the same offsets as named constants.
 *
 * <h2>{@code CARD-UPDATE-RECORD} is a full 150 bytes, {@code FILLER} included</h2>
 * <p>{@code app/cbl/COCRDUPC.cbl:314-321} declares it as
 * {@code X(16) + 9(11) + 9(03) + X(50) + X(10) + X(01) + FILLER X(59)} =
 * {@value #CARD_UPDATE_RECORD_LENGTH} bytes, byte-identical to {@code app/cpy/CVACT02Y.cpy}. The
 * trailing {@code FILLER PIC X(59)} at {@code :321} is easy to miss, because that line is the only
 * one in the group carrying no {@code CARD-UPDATE} text, and missing it is expensive: the rewrite at
 * {@code :1478-1480} states its length as {@code LENGTH OF CARD-UPDATE-RECORD}, so it is a
 * <strong>full-width rewrite</strong> and never a 91-byte partial one. The reserved span is emitted
 * as 59 spaces (gate G21) and the total is asserted at {@value #CARD_UPDATE_RECORD_LENGTH} (gate
 * G19); {@link CardUpdateRecord} and {@link CardRecord} both enforce the width in their own layouts,
 * so a short image cannot leave this class.
 *
 * <h2>Two field names are misspelled, and both stay misspelled</h2>
 * <p>{@code app/cpy/CVACT02Y.cpy:9} declares {@code CARD-EXPIRAION-DATE} and
 * {@code app/cbl/COCRDUPC.cbl:297,309,319} declare {@code CCUP-OLD-EXPIRAION-DATE},
 * {@code CCUP-NEW-EXPIRAION-DATE} and {@code CARD-UPDATE-EXPIRAION-DATE} - all missing the
 * {@code T} of {@code EXPIRATION}. The Java names reproduce the misspelling deliberately. No
 * correctly-spelled field exists anywhere in the reference tree, so there is no "correct" form to
 * prefer, and the parity differ compares fields <em>by name</em>: renaming one would make a genuine
 * difference invisible to the only check able to catch it.
 *
 * <h2>Numbers here are whole, and there is no rounding anywhere in this file</h2>
 * <p>{@code app/cpy/CVACT02Y.cpy} declares no {@code COMP-3}, no {@code PIC S9} and no {@code V},
 * so nothing this class touches is a scaled or signed decimal. The two numeric items -
 * {@code CARD-UPDATE-ACCT-ID PIC 9(11)} and {@code CARD-UPDATE-CVV-CD PIC 9(03)} - are unsigned
 * whole numbers and are carried as {@code long} and {@code int}. No fixed-point type arises here, no
 * rounding policy arises, and no binary floating-point type appears anywhere in this file (gates G22,
 * G24). Those three absences are asserted by grep rather than argued, so this paragraph deliberately
 * names none of the forbidden tokens.
 *
 * <h2>Every cross-width move goes through the codec</h2>
 * <p>COBOL truncates a {@code PIC X} receiver on the <em>right</em> and a {@code PIC 9} receiver on
 * the <em>left</em>. A plain Java assignment does neither, and {@code MOVE} is the dominant parity
 * risk in this codebase at 2,795 sites, so every move below is written as
 * {@link FixedWidthCodec#movePicX(String, int)} or {@link FixedWidthCodec#movePic9(long, int)} with
 * the receiver's declared width named. The codec is a <em>method parameter</em> rather than an
 * injected collaborator, matching {@link CardUpdateRecord#compose(CardDetails, FixedWidthCodec)} and
 * {@link CardUpdateRecord#matchesSnapshot(CardDetails, FixedWidthCodec)}: it carries the code page,
 * the caller already holds the configured one, and taking it here keeps the code page out of this
 * class and out of any test that has to construct one.
 *
 * <h2>No state lives on this bean (practice B9, gate G53)</h2>
 * <p>{@code CCUP-OLD-DETAILS}, {@code CCUP-NEW-DETAILS} and {@code CARD-UPDATE-RECORD} are COBOL
 * {@code WORKING-STORAGE}, and in CICS that storage belongs to one task. Here they are
 * <strong>parameters and return values</strong>, never fields: this is a singleton, so a field would
 * leak one request's card details into another's and would make every test order-dependent. The only
 * instance field is the injected repository, which is {@code final}; every other {@code static}
 * member is {@code final} and immutable.
 *
 * <h2>Two source oddities are reproduced rather than repaired (practice B5)</h2>
 * <ol>
 *   <li><strong>The non-local {@code GO TO}.</strong>
 *       {@code app/cbl/COCRDUPC.cbl:1518} jumps from inside {@code 9300-CHECK-CHANGE-IN-REC} to
 *       {@code 9200-WRITE-PROCESSING-EXIT} - out of the {@code PERFORM ... THRU} range and into the
 *       <em>caller's</em> exit label. Here the check returns a status and the write path returns
 *       immediately without rewriting, which is the same observable outcome;</li>
 *   <li><strong>The redundant caller-side guard.</strong> Having already jumped out,
 *       {@code :1455-1457} <em>also</em> tests {@code IF DATA-WAS-CHANGED-BEFORE-UPDATE GO TO
 *       9200-WRITE-PROCESSING-EXIT}. Both paths reach the same place. The explicit guard is kept in
 *       {@link #writeProcessing(CardScreenState, CardDetails, CardDetails, String, FixedWidthCodec)}
 *       because it is in the source and it documents the intent; it is not simplified away. The
 *       malformed-looking {@code END-IF EXIT} at {@code :1519} is a quirk of the original and needs
 *       no Java counterpart beyond the {@code return}.</li>
 * </ol>
 *
 * <h2>Two findings that correct the migration brief (practice B4)</h2>
 * <p>Both were established by reading the source directly, and both change what the code has to do.
 * They are recorded here rather than silently accommodated.
 *
 * <p><strong>Finding 1 - {@code WS-RETURN-MSG-OFF} is SPACES, not LOW-VALUES, and it is not a flag
 * at all.</strong> The migration brief states that the guard at {@code :1445} tests
 * {@code CCARD-RETURN-MSG} against {@code LOW-VALUES} per {@code app/cpy/CVCRD01Y.cpy:30}. It does
 * not. {@code COCRDUPC} declares its own {@code 05 WS-RETURN-MSG PIC X(75)} at {@code :173} with
 * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code :174}, and it never references
 * {@code CCARD-RETURN-MSG} anywhere - the only {@code CVCRD01Y} message item it touches is
 * {@code CCARD-ERROR-MSG}, at {@code :547} and {@code :569}. {@code :384} runs
 * {@code SET WS-RETURN-MSG-OFF TO TRUE} unconditionally at the top of every pass, which is
 * {@code MOVE SPACES}. So "off" means {@value #RETURN_MESSAGE_LENGTH} spaces, and
 * {@link #isReturnMessageOff(String)} tests exactly that.
 *
 * <p>The same declaration settles something larger. {@code COULD-NOT-LOCK-FOR-UPDATE} ({@code :205}),
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} ({@code :207}) and {@code LOCKED-BUT-UPDATE-FAILED}
 * ({@code :209}) are <strong>not three flags</strong>. They are three {@code 88}-level condition
 * names on that one 75-byte field, each naming a different message literal. {@code SET ... TO TRUE}
 * moves the literal in; {@code IF ...} compares the field against it. Three consequences follow, and
 * all three are modelled directly:
 * <ul>
 *   <li>the outcomes are <em>mutually exclusive by construction</em> - one field, one value - which is
 *       why {@link WriteOutcome} is a single discriminant rather than three booleans;</li>
 *   <li>the guard at {@code :1445} exists to stop a lock failure overwriting an <em>earlier, more
 *       specific</em> message that some prior paragraph already placed;</li>
 *   <li>the caller's {@code EVALUATE TRUE} at {@code :992-1001} is literally a first-match-wins
 *       comparison of that field against the three literals, with {@code WHEN OTHER} last - so
 *       {@link WriteOutcome}'s declaration order is the source's order, and it is load-bearing
 *       (gate G30).</li>
 * </ul>
 *
 * <p><strong>Finding 2 - {@code CCUP-NEW-CVV-CD} is never assigned, so every card update blanks the
 * CVV.</strong> Searching {@code COCRDUPC} for {@code CVV} yields the {@code REDEFINES} pair at
 * {@code :107-109}, the two declarations at {@code :294} and {@code :306}, the record item at
 * {@code :317}, one read at {@code :1354} that fills {@code CCUP-OLD-CVV-CD} from the record, the
 * round-trip at {@code :1464-1465}, and the compare and write-back at {@code :1503} and {@code :1512}.
 * There is no statement anywhere that assigns {@code CCUP-NEW-CVV-CD}, and there is no CVV field on
 * the {@code COCRDUP} screen for the user to type one into - {@code app/bms/COCRDUP.bms} and
 * {@code app/cpy-bms/COCRDUP.CPY} declare none. {@code :586} runs
 * {@code INITIALIZE CCUP-NEW-DETAILS} on every pass, which leaves that {@code PIC X(3)} item
 * <strong>three spaces</strong>. So the value {@code :1464} sends through the {@code PIC 9(03)} span
 * is blank on every single successful update.
 *
 * <p>This is a defect in the legacy program. It is <strong>reproduced, not repaired</strong>:
 * inventing a CVV, carrying the old one forward or rejecting the update would each be a new business
 * rule. {@link #redefinedCvvImage(String, FixedWidthCodec)} and
 * {@link #zonedDigitsValue(String, FixedWidthCodec)} carry it through, and
 * {@link WriteResult#cardUpdateCvvCdImage()} exposes the verbatim three bytes so a field-by-field
 * differ sees what was actually staged.
 *
 * @see CardRepository#readForUpdateByCardNumber(String)
 * @see CardRepository#rewrite(CardRecord)
 * @see CardUpdateRecord
 */
@Service
public class CardUpdateService {

    /**
     * The log. Named for the class, and deliberately given nothing sensitive: this method handles card
     * numbers, embossed names and CVV codes, and none of them is ever logged. What is logged is the
     * response pair, the outcome name and - on the changed path - the <em>names</em> of the items that
     * differed, which is what a support engineer needs and is not itself cardholder data.
     */
    private static final Log LOG = LogFactory.getLog(CardUpdateService.class);

    // =================================================================================================
    // Declared geometry and literals, each traceable to one line of app/cbl/COCRDUPC.cbl. No width and
    // no literal is ever written as a bare value at a call site.
    // =================================================================================================

    /**
     * {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '}
     * ({@code app/cbl/COCRDUPC.cbl:251-252}) - the CICS file name both the read-for-update at
     * {@code :1428} and the rewrite at {@code :1478} name. The trailing space is part of the value,
     * because the item is eight characters wide and the name is seven.
     *
     * <p>Held here for the diagnostics only, and it is a CICS <em>file</em> name rather than a dataset
     * name. The dataset itself is reached through {@link CardRepository}, which resolves the binding
     * from configuration, so no hard-coded dataset name appears anywhere in this file (gate G46).
     */
    public static final String CICS_FILE_NAME = "CARDDAT ";

    /**
     * {@code LENGTH OF WS-CARD-RID-CARDNUM} ({@code app/cbl/COCRDUPC.cbl:129,1431}): the sixteen
     * characters of {@code WS-CARD-RID-CARDNUM PIC X(16)}, which is the {@code KEYLENGTH} the
     * read-for-update states and the width of the {@code CARDDAT} primary key.
     */
    public static final int CARD_KEY_LENGTH = CardRecord.CARD_NUM_LENGTH;

    /**
     * {@code LENGTH OF CARD-UPDATE-RECORD}: {@code 150}, the length the rewrite at
     * {@code app/cbl/COCRDUPC.cbl:1480} states, and the same {@code 150} that
     * {@code app/cpy/CVACT02Y.cpy} declares. The {@code FILLER PIC X(59)} at
     * {@code app/cbl/COCRDUPC.cbl:321} is inside this total.
     */
    public static final int CARD_UPDATE_RECORD_LENGTH = CardUpdateRecord.RECORD_LENGTH;

    /**
     * The declared width of {@code WS-RETURN-MSG PIC X(75)}
     * ({@code app/cbl/COCRDUPC.cbl:173}) - the single field that carries every outcome of this write
     * path, and the field whose all-spaces state {@code 88 WS-RETURN-MSG-OFF} at {@code :174} names.
     */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /**
     * {@code LIT-LOWER PIC X(26) VALUE 'abcdefghijklmnopqrstuvwxyz'}
     * ({@code app/cbl/COCRDUPC.cbl:262-263}) - the twenty-six characters
     * {@code INSPECT ... CONVERTING} at {@code :1499-1501} converts <em>from</em>.
     */
    public static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /**
     * {@code LIT-UPPER PIC X(26) VALUE 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'}
     * ({@code app/cbl/COCRDUPC.cbl:260-261}) - the twenty-six characters
     * {@code INSPECT ... CONVERTING} at {@code :1499-1501} converts <em>to</em>. Position by position,
     * {@link #LIT_LOWER} maps onto this and onto nothing else.
     */
    public static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * The value {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} names
     * ({@code app/cbl/COCRDUPC.cbl:174}): {@value #RETURN_MESSAGE_LENGTH} spaces, which is the state
     * {@code SET WS-RETURN-MSG-OFF TO TRUE} at {@code :384} establishes at the top of every pass.
     *
     * <p>It is spaces and not {@code LOW-VALUES}; see Finding 1 in this class's documentation.
     */
    public static final String RETURN_MESSAGE_OFF = " ".repeat(RETURN_MESSAGE_LENGTH);

    /**
     * {@code 88 COULD-NOT-LOCK-FOR-UPDATE} ({@code app/cbl/COCRDUPC.cbl:205-206}) - the literal
     * {@code SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE} at {@code :1446} moves into
     * {@code WS-RETURN-MSG}, byte for byte.
     */
    public static final String MSG_COULD_NOT_LOCK_FOR_UPDATE = "Could not lock record for update";

    /**
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} ({@code app/cbl/COCRDUPC.cbl:207-208}) - the literal
     * {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} at {@code :1511} moves into
     * {@code WS-RETURN-MSG}, byte for byte.
     */
    public static final String MSG_DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /**
     * {@code 88 LOCKED-BUT-UPDATE-FAILED} ({@code app/cbl/COCRDUPC.cbl:209-210}) - the literal
     * {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code :1491} moves into
     * {@code WS-RETURN-MSG}, byte for byte.
     */
    public static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    /**
     * {@code MOVE 'READ' TO ERROR-OPNAME} - the operation name {@code COCRDUPC} uses when it composes
     * {@code WS-FILE-ERROR-MESSAGE} for a failed read ({@code app/cbl/COCRDUPC.cbl:1407}). Supplied on
     * the result so the controller can compose the same message without re-deriving which operation
     * failed.
     */
    public static final String READ_OPERATION_NAME = "READ";

    /**
     * The operation name for a failed rewrite, the counterpart of {@link #READ_OPERATION_NAME}.
     * {@code 9200-WRITE-PROCESSING} does not itself compose {@code WS-FILE-ERROR-MESSAGE} on the
     * rewrite arm - {@code :1488-1492} sets only the message condition - so this value exists purely
     * to let the controller name the failing operation in a diagnostic.
     */
    public static final String REWRITE_OPERATION_NAME = "REWRITE";

    /**
     * The mask that selects a zoned byte's low-order four bits, which is where a zoned
     * {@code DISPLAY} byte carries its digit. Used only by
     * {@link #zonedDigitsValue(String, FixedWidthCodec)}.
     */
    private static final int ZONED_DIGIT_NIBBLE_MASK = 0x0F;

    /** The largest value a four-bit nibble can hold and still denote a decimal digit. */
    private static final int MAX_DIGIT_NIBBLE = 9;

    /** The radix a zoned {@code DISPLAY} field counts in: one decimal digit per byte. */
    private static final int DECIMAL_RADIX = 10;

    /**
     * The digit contributed by a byte whose low-order nibble is not a decimal digit. Zero, because
     * such a byte carries no digit at all and zero is the only value a {@code PIC 9} item is ever
     * given for absent content - {@code INITIALIZE} and {@code MOVE ZEROES} both produce it. See
     * {@link #zonedDigitsValue(String, FixedWidthCodec)} for why the case is unreachable from the
     * program's own data path.
     */
    private static final int NO_ZONED_DIGIT = 0;

    /** The lowest character {@link #LIT_LOWER} converts, held as a bound rather than a literal. */
    private static final char LOWEST_CONVERTED_CHARACTER = 'a';

    /** The highest character {@link #LIT_LOWER} converts, held as a bound rather than a literal. */
    private static final char HIGHEST_CONVERTED_CHARACTER = 'z';

    /** A single space, the {@code PIC X} pad character and the whole of {@link #RETURN_MESSAGE_OFF}. */
    private static final char SPACE = ' ';

    /**
     * The one collaborator: the card master, reached only through its repository. {@code final}, and
     * the only instance field this class has.
     */
    private final CardRepository cardRepository;

    /**
     * Creates the service.
     *
     * <p>Constructor injection, one collaborator, no field injection and no {@code JdbcTemplate}
     * (practice B9). It follows that a unit test needs nothing more than
     * {@code new CardUpdateService(mock(CardRepository.class))} to reach every branch below - no
     * Spring context, no {@code MockMvc}, no {@code JobLauncher} (gate G51). The code page is not a
     * constructor argument, because it belongs to the caller's {@link FixedWidthCodec} and is passed
     * per call; pinning one here would defeat {@code carddemo.charset.dataset}.
     *
     * @param cardRepository the {@code CARDDAT} repository, which owns the read-for-update lock and
     *                       the full-width rewrite
     * @throws NullPointerException if {@code cardRepository} is {@code null}
     */
    public CardUpdateService(CardRepository cardRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "A CARDDAT repository is required: 9200-WRITE-PROCESSING both reads the card record "
                        + "for update and rewrites it, and neither goes anywhere but through it");
    }

    // =================================================================================================
    // 9200-WRITE-PROCESSING, app/cbl/COCRDUPC.cbl:1420-1496. Seven steps, in the source's order.
    // =================================================================================================

    /**
     * {@code 9200-WRITE-PROCESSING} ({@code app/cbl/COCRDUPC.cbl:1420-1496}): locks the card record,
     * refuses the update if it changed under the screen, and otherwise rewrites it at full width.
     *
     * <p>The seven steps below are the paragraph's own, in the paragraph's own order. The order is
     * behaviour, not style - reading before checking is what makes the check meaningful, and checking
     * before staging is what stops a stale value being written.
     *
     * <ol>
     *   <li><strong>{@code :1425} Position the key.</strong>
     *       {@code MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM} - the card number, and only the card
     *       number. {@code :1424} carries a commented-out
     *       {@code * MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID}; it is dead and <strong>stays
     *       dead</strong> (practice B5). The read is on the base cluster by card number, never on the
     *       {@code CARDAIX} path by account.</li>
     *   <li><strong>{@code :1427-1436} Read for update.</strong>
     *       {@code EXEC CICS READ FILE(LIT-CARDFILENAME) UPDATE RIDFLD(WS-CARD-RID-CARDNUM)
     *       KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM) INTO(CARD-RECORD) LENGTH(LENGTH OF CARD-RECORD)
     *       RESP RESP2} becomes {@link CardRepository#readForUpdateByCardNumber(String)}. The lock is
     *       the point of the {@code UPDATE} option: the record must not change between being read and
     *       being rewritten.</li>
     *   <li><strong>{@code :1441-1449} Could we lock it?</strong>
     *       <pre>
     *       IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
     *          CONTINUE
     *       ELSE
     *          SET INPUT-ERROR                    TO TRUE
     *          IF  WS-RETURN-MSG-OFF
     *              SET COULD-NOT-LOCK-FOR-UPDATE  TO TRUE
     *          END-IF
     *          GO TO 9200-WRITE-PROCESSING-EXIT
     *       END-IF
     *       </pre>
     *       The nesting is exact and is <strong>not flattened</strong>. {@code INPUT-ERROR} is set
     *       <em>unconditionally</em>; {@code COULD-NOT-LOCK-FOR-UPDATE} is set <em>only</em> when the
     *       return message is still off, so that a lock failure never overwrites an earlier and more
     *       specific message. Compare step 7, which carries no such guard: the asymmetry is real and
     *       survives.</li>
     *   <li><strong>{@code :1453-1457} Did someone change it while we were out?</strong>
     *       {@code PERFORM 9300-CHECK-CHANGE-IN-REC THRU 9300-CHECK-CHANGE-IN-REC-EXIT} followed by
     *       {@code IF DATA-WAS-CHANGED-BEFORE-UPDATE GO TO 9200-WRITE-PROCESSING-EXIT}. The
     *       {@code PERFORM} becomes {@link #checkChangeInRec(CardRecord, CardDetails,
     *       FixedWidthCodec)}. The explicit guard is <em>kept</em> even though the check's own
     *       {@code GO TO} at {@code :1518} has already left the range - both reach the same place, and
     *       the redundancy is the source's (practice B5).</li>
     *   <li><strong>{@code :1461-1475} Stage the update.</strong>
     *       {@code INITIALIZE CARD-UPDATE-RECORD} and six moves, delegated to
     *       {@link #stageUpdateRecord(CardDetails, long, FixedWidthCodec)}.</li>
     *   <li><strong>{@code :1477-1483} Rewrite.</strong>
     *       {@code EXEC CICS REWRITE FILE(LIT-CARDFILENAME) FROM(CARD-UPDATE-RECORD)
     *       LENGTH(LENGTH OF CARD-UPDATE-RECORD) RESP RESP2} becomes
     *       {@link CardRepository#rewrite(CardRecord)}, at the full
     *       {@value #CARD_UPDATE_RECORD_LENGTH} bytes.</li>
     *   <li><strong>{@code :1488-1492} Did the update succeed?</strong>
     *       {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL) CONTINUE ELSE SET LOCKED-BUT-UPDATE-FAILED
     *       TO TRUE}. Note what is <em>absent</em>: no {@code IF WS-RETURN-MSG-OFF} wrapper, unlike
     *       step 3, and no {@code SET INPUT-ERROR}.</li>
     * </ol>
     *
     * <p>The account identifier the staging uses comes from {@code CC-ACCT-ID-N}
     * ({@code :1463}) - the {@code PIC 9(11)} {@code REDEFINES} view of the work area's
     * {@code CC-ACCT-ID PIC X(11)}, {@code app/cpy/CVCRD01Y.cpy:34-36} - and <em>not</em> from
     * {@code CCUP-NEW-ACCTID}. {@link CardScreenState#getCcAcctIdN()} is that view.
     *
     * <p>Nothing is mutated. {@code workArea} is read and never written; {@code oldDetails} and
     * {@code newDetails} are immutable records; the possibly-refreshed snapshot comes back on
     * {@link WriteResult#oldDetails()}.
     *
     * @param workArea      the {@code CVCRD01Y} work area, read for {@code CC-CARD-NUM} ({@code :1425})
     *                      and {@code CC-ACCT-ID-N} ({@code :1463}). Not modified
     * @param oldDetails    {@code CCUP-OLD-DETAILS} - the snapshot the screen was painted from, and
     *                      what {@code 9300-CHECK-CHANGE-IN-REC} compares against. Must be the
     *                      {@link DetailGroup#OLD} group
     * @param newDetails    {@code CCUP-NEW-DETAILS} - what the user typed, and the source of every
     *                      staged value. Must be the {@link DetailGroup#NEW} group
     * @param returnMessage the current content of {@code WS-RETURN-MSG} ({@code :173}), which decides
     *                      the {@code :1445} guard. {@code null} is accepted and means the cleared
     *                      state {@code :384} establishes on every pass - see
     *                      {@link #isReturnMessageOff(String)}
     * @param codec         the codec carrying the code page, and the owner of every pad, truncate and
     *                      concatenate rule used here
     * @return the outcome, never {@code null}
     * @throws NullPointerException     if {@code workArea}, {@code oldDetails}, {@code newDetails} or
     *                                  {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code oldDetails} is not the {@link DetailGroup#OLD} group
     *                                  or {@code newDetails} is not the {@link DetailGroup#NEW} group
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

        // Step 1, :1425. MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM. X(16) into X(16), so the move is
        // width-preserving; it is still routed through the codec so the receiver's declared width is
        // named at the call site rather than assumed. The commented-out MOVE CC-ACCT-ID-N TO
        // WS-CARD-RID-ACCT-ID at :1424 is dead and stays dead: this read is by card number only.
        String cardRid = codec.movePicX(workArea.getCcCardNum(), CARD_KEY_LENGTH);

        // Step 2, :1427-1436. EXEC CICS READ ... UPDATE. The lock, and the record to compare against.
        CardReadResult read = cardRepository.readForUpdateByCardNumber(cardRid);

        // Step 3, :1441-1449. Could we lock the record? Every non-normal response lands on the same
        // arm and abandons the update - a deleted record included.
        if (read.resp() != FileStatus.NORMAL) {
            // :1444 SET INPUT-ERROR TO TRUE - unconditional.
            // :1445-1447 SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE - ONLY when the message is still off.
            // Both statements are inside the ELSE, but only the second is inside the IF, and the
            // difference is the whole subtlety of this arm.
            boolean messageOff = isReturnMessageOff(returnMessage);
            String message = messageOff ? MSG_COULD_NOT_LOCK_FOR_UPDATE : returnMessage;
            LOG.warn("A read-for-update of " + CICS_FILE_NAME.trim() + " did not take the lock: "
                    + "RESP=" + read.resp() + ", RESP2=" + read.resp2() + ". The update is abandoned "
                    + "and no record has been changed. The return message was "
                    + (messageOff ? "off, so the could-not-lock message is now set"
                            : "already set by an earlier paragraph, so it is left as it stands"));
            // :1448 GO TO 9200-WRITE-PROCESSING-EXIT.
            return new WriteResult(WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE, true, message, oldDetails,
                    Optional.empty(), Optional.empty(), Optional.of(READ_OPERATION_NAME),
                    read.resp(), read.resp2());
        }

        // The lock was taken, so the record is present. requireRecord() states that invariant rather
        // than re-testing it: CardReadResult's own constructor rejects a normal result with no record.
        CardRecord locked = read.requireRecord();

        // Step 4, :1453-1457. PERFORM 9300-CHECK-CHANGE-IN-REC THRU ...-EXIT.
        ChangeCheck check = checkChangeInRec(locked, oldDetails, codec);
        if (check.dataWasChanged()) {
            // :1455-1457 IF DATA-WAS-CHANGED-BEFORE-UPDATE GO TO 9200-WRITE-PROCESSING-EXIT.
            // Deliberately redundant with the non-local GO TO at :1518, which has already left the
            // PERFORM range by the time control reaches here. Kept because the source keeps it: both
            // paths mean "do not rewrite", and the explicit test documents that (practice B5).
            LOG.info("The " + CICS_FILE_NAME.trim() + " record changed after the screen was painted, "
                    + "so the update is refused and no record has been changed. Items that differ: "
                    + check.describeDifferences() + ". The CCUP-OLD-DETAILS snapshot has been "
                    + "refreshed from the stored record so the screen can be repainted with the "
                    + "values that actually won.");
            return new WriteResult(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE, false,
                    MSG_DATA_WAS_CHANGED_BEFORE_UPDATE, check.oldDetails(), Optional.empty(),
                    Optional.empty(), Optional.empty(), read.resp(), read.resp2());
        }

        // Step 5, :1461-1475. INITIALIZE CARD-UPDATE-RECORD, then the six moves.
        CardUpdateRecord staged = stageUpdateRecord(newDetails, workArea.getCcAcctIdN(), codec);
        String stagedCvvImage = redefinedCvvImage(newDetails.cvvCd(), codec);

        // Step 6, :1477-1483. EXEC CICS REWRITE ... LENGTH(LENGTH OF CARD-UPDATE-RECORD). Full width:
        // all 150 bytes, the FILLER X(59) at :321 included.
        CardWriteResult written = cardRepository.rewrite(asCardRecord(staged));

        // Step 7, :1488-1492. Did the update succeed? No WS-RETURN-MSG-OFF guard on this arm, and no
        // SET INPUT-ERROR either - unlike step 3.
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

        // Fall through to the paragraph's exit with no message condition set, which is precisely the
        // caller's WHEN OTHER at :999-1000: SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE.
        LOG.info("A " + CICS_FILE_NAME.trim() + " record was rewritten at its full "
                + CARD_UPDATE_RECORD_LENGTH + " bytes.");
        return new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false,
                returnMessage, oldDetails, Optional.of(staged),
                Optional.of(stagedCvvImage), Optional.empty(), written.resp(),
                written.resp2());
    }

    // =================================================================================================
    // 9300-CHECK-CHANGE-IN-REC, app/cbl/COCRDUPC.cbl:1498-1523. The concurrency control itself.
    // =================================================================================================

    /**
     * {@code 9300-CHECK-CHANGE-IN-REC} ({@code app/cbl/COCRDUPC.cbl:1498-1523}): decides whether the
     * record that was just locked still matches the snapshot the screen was painted from.
     *
     * <p>Three steps, and the first one surprises people.
     *
     * <p><strong>Step 1, {@code :1499-1501} - fold the record's embossed name to upper case, in
     * place.</strong>
     * <pre>
     * INSPECT CARD-EMBOSSED-NAME
     * CONVERTING LIT-LOWER
     *         TO LIT-UPPER
     * </pre>
     * This runs <em>before</em> the comparison and it <em>mutates the freshly read record</em>. The
     * consequence is the least obvious behaviour in the paragraph and the easiest to get wrong: a
     * stored name differing from the snapshot <em>only by letter case</em> is <strong>not</strong> a
     * concurrent change, and the update proceeds. The fold is
     * {@link #convertingLowerToUpper(String)} - an explicit twenty-six-pair translation, never
     * {@link String}'s no-argument upper-casing method, which is locale-sensitive and folds characters
     * far outside {@link #LIT_LOWER}.
     *
     * <p><strong>Step 2, {@code :1503-1509} - six equalities, all of which must hold.</strong>
     * <table border="1">
     *   <caption>The comparison, {@code app/cbl/COCRDUPC.cbl:1503-1509}</caption>
     *   <tr><th>Stored record</th><th>Snapshot</th><th>Note</th></tr>
     *   <tr><td>{@code CARD-CVV-CD}</td><td>{@code CCUP-OLD-CVV-CD}</td>
     *       <td>record {@code 9(03)} against screen {@code X(3)}, so the record's zoned image is
     *           compared</td></tr>
     *   <tr><td>{@code CARD-EMBOSSED-NAME}</td><td>{@code CCUP-OLD-CRDNAME}</td>
     *       <td>after step 1's fold; both {@code X(50)}</td></tr>
     *   <tr><td>{@code CARD-EXPIRAION-DATE(1:4)}</td><td>{@code CCUP-OLD-EXPYEAR}</td>
     *       <td>{@code substring(0, 4)}</td></tr>
     *   <tr><td>{@code CARD-EXPIRAION-DATE(6:2)}</td><td>{@code CCUP-OLD-EXPMON}</td>
     *       <td>{@code substring(5, 7)}</td></tr>
     *   <tr><td>{@code CARD-EXPIRAION-DATE(9:2)}</td><td>{@code CCUP-OLD-EXPDAY}</td>
     *       <td>{@code substring(8, 10)}</td></tr>
     *   <tr><td>{@code CARD-ACTIVE-STATUS}</td><td>{@code CCUP-OLD-CRDSTCD}</td>
     *       <td>{@code X(1)}</td></tr>
     * </table>
     * The two {@code '-'} separators at characters 5 and 8 are not compared, so a record whose
     * separators differ but whose {@code YYYY}, {@code MM} and {@code DD} agree compares equal. The
     * card number and the account identifier are not compared either. Neither omission is tidied up.
     *
     * <p><strong>Step 3, {@code :1511-1519} - on any inequality, refresh the snapshot and leave.</strong>
     * {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE}, then six moves that copy the
     * <em>current</em> record values back into the {@code CCUP-OLD-*} items, then
     * {@code GO TO 9200-WRITE-PROCESSING-EXIT}. The write-back is <strong>load-bearing, not
     * defensive cleanup</strong>: it is how the repainted screen shows the user the values that
     * actually won, so the refreshed snapshot is returned on {@link ChangeCheck#oldDetails()} and must
     * reach the response payload. Note that the name written back is the <em>already-folded</em> one,
     * because step 1 mutated the record before the comparison ran.
     *
     * <p>The {@code GO TO} at {@code :1518} leaves the {@code PERFORM ... THRU} range and lands in the
     * <em>caller's</em> exit label. Here it becomes a returned status that
     * {@link #writeProcessing(CardScreenState, CardDetails, CardDetails, String, FixedWidthCodec)}
     * acts on by returning without rewriting. The {@code END-IF EXIT} at {@code :1519} is a quirk of
     * the original and needs no counterpart beyond that return.
     *
     * <p>{@code record} is not mutated - {@link CardRecord} is immutable, so the fold produces a copy,
     * which {@link ChangeCheck#foldedRecord()} exposes. That copy, not the original, is what the
     * comparison and the write-back both use, exactly as the COBOL's in-place {@code INSPECT}
     * arranges.
     *
     * @param record     the record just read under the lock, {@code CARD-RECORD} at {@code :1432}
     * @param oldDetails {@code CCUP-OLD-DETAILS}, the snapshot to compare against. Must be the
     *                   {@link DetailGroup#OLD} group
     * @param codec      the codec, which renders the record's {@code PIC 9(03)} CVV into the
     *                   snapshot's three-character {@code PIC X(3)} form so like is compared with like
     * @return the check outcome, never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
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

        // Step 1, :1499-1501. INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER. In COBOL
        // this mutates the record area in place, before anything is compared; CardRecord is immutable,
        // so the fold yields a copy and that copy is what everything downstream uses.
        CardRecord folded = foldEmbossedName(record);

        // Step 2, :1503-1509. Six equalities, evaluated as the source writes them. The record's CVV is
        // rendered to its three-character zoned image so a PIC 9(03) is not compared against a PIC X(3).
        String storedCvvImage = folded.cardCvvCdImage(codec);
        boolean cvvMatches = storedCvvImage.equals(oldDetails.cvvCd());
        boolean nameMatches = folded.cardEmbossedName().equals(oldDetails.crdname());
        boolean yearMatches = folded.cardExpiraionDateYear().equals(oldDetails.expyear());
        boolean monthMatches = folded.cardExpiraionDateMonth().equals(oldDetails.expmon());
        boolean dayMatches = folded.cardExpiraionDateDay().equals(oldDetails.expday());
        boolean statusMatches = folded.cardActiveStatus().equals(oldDetails.crdstcd());

        if (cvvMatches && nameMatches && yearMatches && monthMatches && dayMatches && statusMatches) {
            // :1509 CONTINUE. Nothing changed, so the snapshot is handed back untouched and the caller
            // proceeds to stage and rewrite.
            return new ChangeCheck(false, oldDetails, folded, false, false, false, false, false,
                    false);
        }

        // Step 3, :1511-1517. Copy the CURRENT values back into CCUP-OLD-*, so the repainted screen
        // shows what actually won. Each move is written out rather than looped, because each has its own
        // source item in the COBOL and the field-by-field correspondence is what parity is checked on.
        CardDetails refreshed = oldDetails
                .withCvvCd(codec.movePicX(storedCvvImage, CardDetails.CVV_CD_LENGTH))
                .withCrdname(codec.movePicX(folded.cardEmbossedName(), CardDetails.CRDNAME_LENGTH))
                .withExpyear(codec.movePicX(folded.cardExpiraionDateYear(),
                        CardDetails.EXPYEAR_LENGTH))
                .withExpmon(codec.movePicX(folded.cardExpiraionDateMonth(),
                        CardDetails.EXPMON_LENGTH))
                .withExpday(codec.movePicX(folded.cardExpiraionDateDay(), CardDetails.EXPDAY_LENGTH))
                .withCrdstcd(codec.movePicX(folded.cardActiveStatus(), CardDetails.CRDSTCD_LENGTH));

        // :1518 GO TO 9200-WRITE-PROCESSING-EXIT - reported to the caller, which returns without
        // rewriting.
        return new ChangeCheck(true, refreshed, folded, !cvvMatches, !nameMatches, !yearMatches,
                !monthMatches, !dayMatches, !statusMatches);
    }

    // =================================================================================================
    // The primitives. Each is one COBOL statement or one COBOL declaration, named after it, so a
    // reviewer can check it against the source without reading the paragraph around it. Each is public
    // and, where it needs no instance state, static - so a test can drive it in isolation, which is what
    // makes the branch bar reachable (practice B7, gate G49).
    // =================================================================================================

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} ({@code app/cbl/COCRDUPC.cbl:174}): whether
     * {@code WS-RETURN-MSG} is still clear, which is the condition guarding
     * {@code SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE} at {@code :1445-1447}.
     *
     * <p><strong>Spaces, not {@code LOW-VALUES}.</strong> {@code COCRDUPC} declares this condition on
     * its own {@code 05 WS-RETURN-MSG PIC X(75)} at {@code :173} and never touches
     * {@code CCARD-RETURN-MSG}, whose {@code 88 CCARD-RETURN-MSG-OFF} at
     * {@code app/cpy/CVCRD01Y.cpy:30} does test {@code LOW-VALUES}. The two conditions are on
     * different fields in different copybooks and must not be conflated; see Finding 1 in this class's
     * documentation.
     *
     * <p>{@code null} is read as clear. That is not looseness: {@code :384} runs
     * {@code SET WS-RETURN-MSG-OFF TO TRUE} unconditionally at the top of every pass, so "no message
     * was supplied" and "the message is off" are the same state, and an omitted payload field
     * deserialising to {@code null} is exactly that case. A shorter value is space-padded to
     * {@value #RETURN_MESSAGE_LENGTH} first, because a COBOL {@code PIC X(75)} item is always
     * seventy-five characters wide.
     *
     * @param returnMessage the current content of {@code WS-RETURN-MSG}, or {@code null} for the
     *                      cleared state
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
        // Any characters beyond the declared width are outside the field and cannot make it non-blank;
        // any characters short of it are the space padding a PIC X(75) receiver would already hold.
        return true;
    }

    /**
     * {@code INSPECT ... CONVERTING LIT-LOWER TO LIT-UPPER} ({@code app/cbl/COCRDUPC.cbl:1499-1501},
     * with the two literals declared at {@code :260-263}).
     *
     * <p>{@code CONVERTING} is a positional character translation: character <em>n</em> of
     * {@link #LIT_LOWER} is replaced by character <em>n</em> of {@link #LIT_UPPER}, and <strong>every
     * other character is left exactly as it is</strong>. That is why this is written as an explicit
     * range test over {@code 'a'} to {@code 'z'} and not as a call to {@link String}'s no-argument
     * upper-casing method:
     * <ul>
     *   <li>that method takes no locale and so uses the JVM's default one, which means the same input
     *       folds differently on a Turkish-locale JVM - {@code 'i'} becomes {@code 'İ'} - and the
     *       concurrency check would then depend on the server's locale;</li>
     *   <li>it also folds characters that are nowhere in {@link #LIT_LOWER}, including accented letters
     *       and {@code 'ß'}, which grows to two characters and would change the field's width;</li>
     *   <li>{@code CONVERTING} does neither. It touches those twenty-six characters and nothing
     *       else.</li>
     * </ul>
     * The width is therefore preserved exactly, which matters because the result is compared against a
     * {@code PIC X(50)} item and written back into one.
     *
     * @param value the characters to convert; may be empty
     * @return {@code value} with each of {@code 'a'} to {@code 'z'} replaced by its counterpart in
     *         {@link #LIT_UPPER}, and every other character untouched
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
                // Positional substitution: LIT_LOWER and LIT_UPPER are the same length and are aligned
                // character for character, so the offset within one indexes the other.
                characters[index] = LIT_UPPER.charAt(character - LOWEST_CONVERTED_CHARACTER);
                converted = true;
            }
        }
        // A char[] wrapped as a String, which involves no code page at all: this whole method works in
        // character space, and which byte represents each character is decided later, by the codec the
        // caller supplies. Stated because the constructor's byte[] sibling WOULD use the platform
        // default, and practice B8 forbids that everywhere in this module.
        return converted ? new String(characters) : value;
    }

    /**
     * Step 1 of {@code 9300-CHECK-CHANGE-IN-REC}: the record with
     * {@code INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER} applied
     * ({@code app/cbl/COCRDUPC.cbl:1499-1501}).
     *
     * <p>The COBOL mutates the record area in place. {@link CardRecord} is immutable, so this returns
     * a copy; the copy is what the comparison and the write-back both use, which produces the same
     * observable behaviour. The fold changes only the embossed name - the CVV, the date and the status
     * are untouched, exactly as the {@code INSPECT} names only that one field.
     *
     * @param record the record read under the lock
     * @return the record with its embossed name folded, or the same instance when the name held no
     *         lower-case letter
     * @throws NullPointerException if {@code record} is {@code null}
     */
    public static CardRecord foldEmbossedName(CardRecord record) {
        Objects.requireNonNull(record, "A record is required to fold its embossed name");
        String folded = convertingLowerToUpper(record.cardEmbossedName());
        if (folded.equals(record.cardEmbossedName())) {
            // Nothing to convert. Returning the same instance rather than a copy is observationally
            // identical, because CardRecord is immutable and its components are already at width.
            return record;
        }
        return record.withCardEmbossedName(folded);
    }

    /**
     * The first half of the CVV {@code REDEFINES} round-trip:
     * {@code MOVE CCUP-NEW-CVV-CD TO CARD-CVV-CD-X} ({@code app/cbl/COCRDUPC.cbl:1464}).
     *
     * <p>{@code CCUP-NEW-CVV-CD} is {@code PIC X(3)} ({@code :306}) and {@code CARD-CVV-CD-X} is
     * {@code PIC X(03)} ({@code :107}), so this is a plain alphanumeric move of three characters into
     * a three-character receiver - space-padded on the right and truncated on the right, through
     * {@link FixedWidthCodec#movePicX(String, int)}. It is <strong>not</strong> a parse: nothing here
     * inspects whether the characters are digits, and nothing here can fail on them.
     *
     * <p>The second half, {@code MOVE CARD-CVV-CD-N TO CARD-UPDATE-CVV-CD} at {@code :1465}, reads
     * those same three bytes back through {@code CARD-CVV-CD-N REDEFINES CARD-CVV-CD-X PIC 9(03)}
     * ({@code :108-109}) - one span, two typed views - and is
     * {@link #zonedDigitsValue(String, FixedWidthCodec)}.
     *
     * <p>In the running program this value is <strong>always three spaces</strong>, because
     * {@code CCUP-NEW-CVV-CD} is never assigned anywhere and {@code :586} initialises it on every
     * pass; see Finding 2 in this class's documentation. The image is returned rather than only the
     * decoded number precisely so that fact stays visible to a field-by-field differ.
     *
     * @param ccupNewCvvCd the sending value, {@code CCUP-NEW-CVV-CD}
     * @param codec        the codec supplying the alphanumeric move rule
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
     * {@code MOVE CARD-CVV-CD-N TO CARD-UPDATE-CVV-CD} ({@code app/cbl/COCRDUPC.cbl:1465}), reading a
     * span written as alphanumeric back through its {@code PIC 9} view.
     *
     * <p>A zoned {@code DISPLAY} byte carries its digit in the <strong>low-order four bits</strong>;
     * the high-order four are the zone. So this decodes byte by byte, in the code page the codec
     * carries - never the platform default - and never with {@code Integer.parseInt}, which would
     * throw on content this path can genuinely see.
     *
     * <p>Two properties make it total, and it therefore never throws on the content of a CVV item:
     * <ul>
     *   <li>a space is {@code 0x40} in {@code IBM037} and {@code 0x20} in {@code US-ASCII}. Its
     *       low-order nibble is {@code 0} in <em>both</em>, so the three-space value the program
     *       actually produces decodes to {@code 0} unambiguously, whichever code page is configured;</li>
     *   <li>a nibble above {@value #MAX_DIGIT_NIBBLE} denotes no digit at all, and contributes
     *       {@value #NO_ZONED_DIGIT} - the value {@code INITIALIZE} and {@code MOVE ZEROES} give a
     *       {@code PIC 9} item with no meaningful content. That case is unreachable from the program's
     *       own data path: {@code CCUP-OLD-CVV-CD} is filled at {@code :1354} from a {@code PIC 9(03)}
     *       record field and so is always digits, and {@code CCUP-NEW-CVV-CD} is only ever spaces. It
     *       is reachable only from a fabricated payload, and a fabricated payload gets a defined answer
     *       instead of a stack trace.</li>
     * </ul>
     *
     * <p><strong>A documented approximation, stated rather than hidden (practice B4).</strong> On the
     * mainframe, {@code MOVE CARD-CVV-CD-N TO CARD-UPDATE-CVV-CD} moves a {@code PIC 9(03)}
     * {@code DISPLAY} item into another {@code PIC 9(03)} {@code DISPLAY} item - same picture, same
     * usage - so the compiler may emit either a verbatim byte copy or a copy with the zone nibbles
     * normalised. Under the first reading the three spaces reach the dataset as spaces; under the
     * second they reach it as {@code "000"}. The two readings differ, no COBOL runtime is available in
     * this environment to settle which (the eight blockers are catalogued in the migration plan), and
     * this module models {@code PIC 9(03)} as an {@code int} throughout -
     * {@link CardRecord#cardCvvCd()} and {@link CardUpdateRecord#cardUpdateCvvCd()} are both
     * {@code int} - so the normalising reading is the one implemented. The verbatim image is carried
     * alongside on {@link WriteResult#cardUpdateCvvCdImage()} so a differ compares bytes and not this
     * decode.
     *
     * @param cvvImage the three characters occupying the span, as
     *                 {@link #redefinedCvvImage(String, FixedWidthCodec)} produced them
     * @param codec    the codec, which states the code page the bytes are read in
     * @return the value the span's zoned digits denote, between 0 and 999
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if a character has no representation in the configured code
     *                                  page, which is a code-page defect rather than a data one
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
     * Steps 5 of {@code 9200-WRITE-PROCESSING}: {@code INITIALIZE CARD-UPDATE-RECORD} and the six
     * moves that follow it ({@code app/cbl/COCRDUPC.cbl:1461-1475}).
     *
     * <p>Statement for statement:
     * <ol>
     *   <li>{@code :1461} {@code INITIALIZE CARD-UPDATE-RECORD} - every {@code PIC X} item to spaces
     *       and every {@code PIC 9} item to zero, which is the state
     *       {@link CardUpdateRecord#initialised()} names. It is not a null or absent default, and it is
     *       what puts 59 spaces in the trailing {@code FILLER} at {@code :321} (gate G21). Every one of
     *       the six named items is overwritten immediately below, so the initialised state survives
     *       only in the reserved span - which is exactly the span that has to carry it;</li>
     *   <li>{@code :1462} {@code MOVE CCUP-NEW-CARDID TO CARD-UPDATE-NUM} - {@code X(16)} to
     *       {@code X(16)};</li>
     *   <li>{@code :1463} {@code MOVE CC-ACCT-ID-N TO CARD-UPDATE-ACCT-ID} - {@code 9(11)} to
     *       {@code 9(11)}, taken from the work area's <em>numeric</em> {@code REDEFINES} view and not
     *       from {@code CCUP-NEW-ACCTID};</li>
     *   <li>{@code :1464-1465} the CVV {@code REDEFINES} round-trip, through
     *       {@link #redefinedCvvImage(String, FixedWidthCodec)} then
     *       {@link #zonedDigitsValue(String, FixedWidthCodec)};</li>
     *   <li>{@code :1466} {@code MOVE CCUP-NEW-CRDNAME TO CARD-UPDATE-EMBOSSED-NAME} - {@code X(50)}
     *       to {@code X(50)}. <strong>Not folded to upper case.</strong> The {@code INSPECT} at
     *       {@code :1499} folds the <em>record read from the file</em>, not the value being written, so
     *       whatever case the user typed is what is stored;</li>
     *   <li>{@code :1467-1474} {@code STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-'
     *       CCUP-NEW-EXPDAY DELIMITED BY SIZE INTO CARD-UPDATE-EXPIRAION-DATE} - each operand
     *       contributes its full declared width, so the result is exactly
     *       {@code 4 + 1 + 2 + 1 + 2 = }{@value CardUpdateRecord#CARD_UPDATE_EXPIRAION_DATE_LENGTH}
     *       characters. Delegated to {@link CardUpdateRecord#compose(CardDetails, FixedWidthCodec)},
     *       which is that one statement and nothing more: no date formatter, no calendar, and no
     *       validation, because the edit paragraphs the controller owns have already run and the
     *       {@code STRING} statement itself validates nothing;</li>
     *   <li>{@code :1475} {@code MOVE CCUP-NEW-CRDSTCD TO CARD-UPDATE-ACTIVE-STATUS} - {@code X(1)}
     *       to {@code X(1)}.</li>
     * </ol>
     *
     * <p>Each alphanumeric move is routed through {@link FixedWidthCodec#movePicX(String, int)} with
     * the receiver's width named, so the truncation direction is chosen deliberately rather than
     * inherited from Java assignment. {@link CardUpdateRecord}'s own constructor would reject an
     * over-wide value rather than pick a direction, which is why the direction is applied here first.
     *
     * @param newDetails {@code CCUP-NEW-DETAILS}; must be the {@link DetailGroup#NEW} group
     * @param acctId     {@code CC-ACCT-ID-N}, the work area's numeric account view
     * @param codec      the codec owning the move and concatenation rules
     * @return the staged {@value #CARD_UPDATE_RECORD_LENGTH}-byte record
     * @throws NullPointerException     if {@code newDetails} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code newDetails} is not the {@link DetailGroup#NEW} group,
     *                                  or {@code acctId} does not fit {@code PIC 9(11)}
     */
    public CardUpdateRecord stageUpdateRecord(CardDetails newDetails, long acctId,
                                              FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to stage CARD-UPDATE-RECORD: six of the "
                + "seven statements at app/cbl/COCRDUPC.cbl:1461-1475 are COBOL MOVEs or a STRING, and "
                + "the codec owns both rules");
        requireGroup(newDetails, DetailGroup.NEW, "CCUP-NEW-DETAILS", "1461-1475");

        // :1461 INITIALIZE, then :1462-1475 the six moves. Written as one construction rather than an
        // initialise-then-mutate sequence because CardUpdateRecord is immutable; the observable result
        // is identical, and the reserved FILLER span is space-filled either way - CardUpdateRecord's
        // layout declares it as a filler span and its encoder writes spaces into it.
        return new CardUpdateRecord(
                // :1462
                codec.movePicX(newDetails.cardid(), CardUpdateRecord.CARD_UPDATE_NUM_LENGTH),
                // :1463 - CC-ACCT-ID-N, the numeric REDEFINES view.
                acctId,
                // :1464-1465 - the two-step REDEFINES round-trip, in that order.
                zonedDigitsValue(redefinedCvvImage(newDetails.cvvCd(), codec), codec),
                // :1466 - the typed case is preserved; the INSPECT at :1499 folds the read record only.
                codec.movePicX(newDetails.crdname(),
                        CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH),
                // :1467-1474 - STRING ... DELIMITED BY SIZE, exactly ten characters.
                CardUpdateRecord.compose(newDetails, codec),
                // :1475
                codec.movePicX(newDetails.crdstcd(),
                        CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_LENGTH));
    }

    /**
     * Presents the staged {@code CARD-UPDATE-RECORD} as the {@code app/cpy/CVACT02Y.cpy} record the
     * repository rewrites.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:314-321} and {@code app/cpy/CVACT02Y.cpy:4-11} declare the same
     * seven spans at the same seven offsets for the same
     * {@value #CARD_UPDATE_RECORD_LENGTH} bytes - {@code X(16)}, {@code 9(11)}, {@code 9(03)},
     * {@code X(50)}, {@code X(10)}, {@code X(01)} and {@code FILLER X(59)} - which is what lets
     * {@code :1478-1480} rewrite the card file directly {@code FROM(CARD-UPDATE-RECORD)}. So this is a
     * change of view over identical bytes and not a conversion: no value is padded, truncated,
     * reformatted or re-derived, because every component already sits at its declared width.
     *
     * <p>It exists as a named method so the equivalence is asserted in one place, and so a test can
     * prove the round trip rather than trust it.
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
     * {@value #RETURN_MESSAGE_LENGTH} characters and truncated on the right when longer, with
     * {@code null} read as the cleared state {@code app/cbl/COCRDUPC.cbl:384} establishes.
     *
     * <p>No codec takes part, and none is needed. The pad character is the space and the rule is the
     * {@code PIC X} rule applied in <em>character</em> space; which byte represents a space is decided
     * later, when the field is encoded, by the code page the encoder is given. This is the same
     * division {@link CardRecord} draws for its own alphanumeric components.
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
            // A PIC X receiver fills from the left and discards the overflow, so the leading characters
            // survive. WS-RETURN-MSG's own 88-level literals are all well inside 75, so this arm is
            // reached only by a caller supplying its own text.
            return returnMessage.substring(0, RETURN_MESSAGE_LENGTH);
        }
        return returnMessage + " ".repeat(RETURN_MESSAGE_LENGTH - returnMessage.length());
    }

    /**
     * Checks that a snapshot is the group the paragraph it is bound for actually reads, so an
     * {@code OLD} snapshot can never be staged as {@code NEW} or compared as though it were.
     *
     * @param details      the snapshot
     * @param expected     the group the COBOL names
     * @param cobolName    that group's COBOL name, for the diagnostic
     * @param sourceLines  the line range of the statements that read it, for the diagnostic
     * @throws NullPointerException     if {@code details} is {@code null}
     * @throws IllegalArgumentException if {@code details} is the other group
     */
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

    // =================================================================================================
    // The outcome contract the controller switches on. app/cbl/COCRDUPC.cbl:992-1001.
    // =================================================================================================

    /**
     * The four mutually exclusive outcomes of {@code 9200-WRITE-PROCESSING}, <strong>declared in the
     * order the caller tests them</strong> ({@code app/cbl/COCRDUPC.cbl:992-1001}):
     * <pre>
     * EVALUATE TRUE
     *    WHEN COULD-NOT-LOCK-FOR-UPDATE
     *         SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE
     *    WHEN LOCKED-BUT-UPDATE-FAILED
     *       SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE
     *    WHEN DATA-WAS-CHANGED-BEFORE-UPDATE
     *        SET CCUP-SHOW-DETAILS            TO TRUE
     *    WHEN OTHER
     *       SET CCUP-CHANGES-OKAYED-AND-DONE   TO TRUE
     * END-EVALUATE
     * </pre>
     *
     * <p><strong>The declaration order is behaviour (gate G30).</strong> {@code EVALUATE} is
     * first-match-wins with {@code WHEN OTHER} last, so this enum's constants appear in the source's
     * order and {@link #firstMatchWinsPosition()} states it explicitly. Modelling the outcome as one
     * discriminant rather than as three booleans is not a convenience either: the three COBOL
     * conditions are {@code 88}-levels on the single field {@code WS-RETURN-MSG PIC X(75)}
     * ({@code app/cbl/COCRDUPC.cbl:173,205,207,209}), so at most one of them can hold at a time and the
     * exclusivity is a property of the source, not an assumption added here. A boolean triple would
     * permit states the COBOL cannot represent and would let the evaluation order be inverted by
     * accident.
     */
    public enum WriteOutcome {

        /**
         * {@code 88 COULD-NOT-LOCK-FOR-UPDATE} - the read-for-update at
         * {@code app/cbl/COCRDUPC.cbl:1427-1436} did not return {@code DFHRESP(NORMAL)}, so nothing was
         * locked, nothing was compared and nothing was written. Tested first, and maps to
         * {@code CCUP-CHANGES-OKAYED-LOCK-ERROR}.
         *
         * <p>{@link WriteResult#inputError()} is {@code true} on this arm and only on this arm,
         * because {@code :1444} is the paragraph's only {@code SET INPUT-ERROR TO TRUE}.
         */
        COULD_NOT_LOCK_FOR_UPDATE(ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                MSG_COULD_NOT_LOCK_FOR_UPDATE, "88 COULD-NOT-LOCK-FOR-UPDATE"),

        /**
         * {@code 88 LOCKED-BUT-UPDATE-FAILED} - the lock was taken and the concurrency check passed,
         * but the rewrite at {@code app/cbl/COCRDUPC.cbl:1477-1483} did not return
         * {@code DFHRESP(NORMAL)} ({@code :1488-1492}). Tested second, and maps to
         * {@code CCUP-CHANGES-OKAYED-BUT-FAILED}.
         */
        LOCKED_BUT_UPDATE_FAILED(ChangeAction.CHANGES_OKAYED_BUT_FAILED,
                MSG_LOCKED_BUT_UPDATE_FAILED, "88 LOCKED-BUT-UPDATE-FAILED"),

        /**
         * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} - the record changed under the screen, so the
         * rewrite was refused ({@code app/cbl/COCRDUPC.cbl:1511}). Tested third, and maps to
         * {@code CCUP-SHOW-DETAILS}: the screen is repainted, and it is repainted from the refreshed
         * snapshot on {@link WriteResult#oldDetails()}.
         */
        DATA_WAS_CHANGED_BEFORE_UPDATE(ChangeAction.SHOW_DETAILS,
                MSG_DATA_WAS_CHANGED_BEFORE_UPDATE, "88 DATA-WAS-CHANGED-BEFORE-UPDATE"),

        /**
         * {@code WHEN OTHER} - the record was locked, it had not changed, and the full-width rewrite
         * succeeded. Maps to {@code CCUP-CHANGES-OKAYED-AND-DONE}.
         *
         * <p>This is the only outcome with no message literal, because it is the only one the paragraph
         * reaches by falling off its end without setting a condition on {@code WS-RETURN-MSG}. It must
         * stay last: as {@code WHEN OTHER} it is the default, and moving it earlier would swallow the
         * three specific arms.
         */
        CHANGES_OKAYED_AND_DONE(ChangeAction.CHANGES_OKAYED_AND_DONE, null, "WHEN OTHER");

        /** The {@code CCUP-CHANGE-ACTION} character the caller's {@code EVALUATE} arm sets. */
        private final String changeActionCode;

        /**
         * The literal this outcome's {@code 88}-level names, moved into {@code WS-RETURN-MSG} by
         * {@code SET ... TO TRUE}, or {@code null} for {@code WHEN OTHER}, which sets none.
         */
        private final String returnMessage;

        /** The COBOL condition or {@code WHEN} clause, for diagnostics and for review. */
        private final String cobolCondition;

        /**
         * @param changeActionCode the {@code CCUP-CHANGE-ACTION} character
         * @param returnMessage    the {@code WS-RETURN-MSG} literal, or {@code null} for
         *                         {@code WHEN OTHER}
         * @param cobolCondition   the COBOL condition name or {@code WHEN} clause
         */
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
         * ({@code app/cbl/COCRDUPC.cbl:992-1001}), stated so that a test can assert the order rather
         * than trust it - the order is behaviour, and an {@code EVALUATE} is first-match-wins.
         *
         * @return 1 for the first {@code WHEN}, rising to 4 for {@code WHEN OTHER}
         */
        public int firstMatchWinsPosition() {
            return ordinal() + 1;
        }

        /**
         * Whether reaching this outcome means the record was rewritten. True for exactly one outcome:
         * the lock arm never reaches the rewrite, the changed arm returns before it, and the failed arm
         * attempted it and was refused.
         *
         * @return {@code true} only for {@link #CHANGES_OKAYED_AND_DONE}
         */
        public boolean isRewritten() {
            return this == CHANGES_OKAYED_AND_DONE;
        }
    }

    // =================================================================================================
    // The two result carriers. Both immutable, so nothing a caller holds can be changed underneath it.
    // =================================================================================================

    /**
     * What {@code 9300-CHECK-CHANGE-IN-REC} concluded ({@code app/cbl/COCRDUPC.cbl:1498-1523}).
     *
     * <p>The six {@code xxxDiffers} components are not in the COBOL - it has one combined {@code IF}
     * and no record of which test failed. They are added for two reasons that cost nothing at runtime
     * and are worth a great deal at review time: they let a support engineer see <em>which</em> item a
     * concurrent update touched without logging any cardholder value, and they let a test assert that
     * each of the six equalities is genuinely wired to its own field rather than to a neighbour, which
     * is the defect a combined boolean would hide. They are strictly derived: when
     * {@link #dataWasChanged()} is false all six are false, and when it is true at least one is true.
     *
     * @param dataWasChanged        whether {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} was
     *                              reached ({@code :1511}) - and therefore whether the caller must
     *                              return without rewriting
     * @param oldDetails            {@code CCUP-OLD-DETAILS}: refreshed from the stored record when the
     *                              data changed ({@code :1512-1517}), and handed back untouched when it
     *                              had not. The refreshed form carries the <em>already-folded</em>
     *                              embossed name, because {@code :1499} mutated the record before the
     *                              comparison ran
     * @param foldedRecord          the locked record after {@code INSPECT ... CONVERTING}
     *                              ({@code :1499-1501}) - what was actually compared, which is not
     *                              always what was read
     * @param cvvDiffers            whether {@code CARD-CVV-CD} differed from {@code CCUP-OLD-CVV-CD}
     *                              ({@code :1503})
     * @param embossedNameDiffers   whether the folded {@code CARD-EMBOSSED-NAME} differed from
     *                              {@code CCUP-OLD-CRDNAME} ({@code :1504})
     * @param expiryYearDiffers     whether {@code CARD-EXPIRAION-DATE(1:4)} differed from
     *                              {@code CCUP-OLD-EXPYEAR} ({@code :1505})
     * @param expiryMonthDiffers    whether {@code CARD-EXPIRAION-DATE(6:2)} differed from
     *                              {@code CCUP-OLD-EXPMON} ({@code :1506})
     * @param expiryDayDiffers      whether {@code CARD-EXPIRAION-DATE(9:2)} differed from
     *                              {@code CCUP-OLD-EXPDAY} ({@code :1507})
     * @param activeStatusDiffers   whether {@code CARD-ACTIVE-STATUS} differed from
     *                              {@code CCUP-OLD-CRDSTCD} ({@code :1508})
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

        /**
         * Rejects a result whose parts contradict each other, so a caller can rely on the invariant
         * rather than re-derive it.
         *
         * @throws NullPointerException     if {@code oldDetails} or {@code foldedRecord} is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code oldDetails} is not the {@link DetailGroup#OLD}
         *                                  group, or if the per-field flags contradict
         *                                  {@code dataWasChanged}
         */
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
         * The COBOL names of the items that differed, comma-separated, or a fixed phrase when none
         * did.
         *
         * <p>Names only. No value is included, ever: five of the six items are cardholder data -
         * the CVV and the embossed name most obviously - and this string is written to the log.
         *
         * @return a human-readable list of COBOL field names, never {@code null} and never containing a
         *         field's content
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

        /**
         * Appends a field name when its flag is set, separating with a comma.
         *
         * @param differences the accumulator
         * @param differs     whether to append
         * @param fieldName   the COBOL field name
         */
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
     * What {@code 9200-WRITE-PROCESSING} concluded ({@code app/cbl/COCRDUPC.cbl:1420-1496}), in the
     * shape the caller's {@code EVALUATE TRUE} at {@code :992-1001} consumes.
     *
     * @param outcome               which of the four arms was reached. Never {@code null}, and the only
     *                              thing the caller has to switch on
     * @param inputError            {@code 88 INPUT-ERROR} ({@code app/cbl/COCRDUPC.cbl:55}). Set
     *                              unconditionally by {@code :1444} and by nothing else in this
     *                              paragraph, so it is {@code true} for
     *                              {@link WriteOutcome#COULD_NOT_LOCK_FOR_UPDATE} alone. It is carried
     *                              separately from {@code outcome} because the two are genuinely
     *                              separate in the source: {@code :1445} can suppress the message
     *                              without suppressing the error
     * @param returnMessage         {@code WS-RETURN-MSG PIC X(75)} ({@code :173}) as it stands after the
     *                              paragraph, at exactly {@value CardUpdateService#RETURN_MESSAGE_LENGTH}
     *                              characters. On the lock arm this is the caller's earlier message when
     *                              one was already set, and the could-not-lock literal when none was -
     *                              which is the {@code :1445} guard's whole effect
     * @param oldDetails            {@code CCUP-OLD-DETAILS}. <strong>Refreshed from the stored record on
     *                              the changed arm</strong> ({@code :1512-1517}) and unchanged on every
     *                              other arm. This is load-bearing: it is how the repainted screen shows
     *                              the user the values that actually won, so it must reach the response
     *                              payload
     * @param cardUpdateRecord      the staged {@code CARD-UPDATE-RECORD}
     *                              ({@code :1461-1475}), present only once staging happened - so absent
     *                              on the lock arm and on the changed arm, and present on the failed and
     *                              succeeded arms
     * @param cardUpdateCvvCdImage  the verbatim three characters the CVV {@code REDEFINES} round-trip
     *                              produced at {@code :1464}, present whenever
     *                              {@code cardUpdateRecord} is. Carried alongside the record's
     *                              {@code int} view because the two can disagree for non-digit content,
     *                              and the bytes are what parity compares
     * @param failedOperation       {@code ERROR-OPNAME} ({@code :1407}) - {@code "READ"} or
     *                              {@code "REWRITE"} - so the controller can compose
     *                              {@code WS-FILE-ERROR-MESSAGE} without re-deriving which operation
     *                              failed. Absent on the two arms where no operation failed
     * @param resp                  {@code WS-RESP-CD} ({@code :41}) from the last operation performed,
     *                              raw and unmapped
     * @param resp2                 {@code WS-REAS-CD} ({@code :43}) from the last operation performed,
     *                              raw and unmapped
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

        /**
         * Normalises the message to its declared width and rejects a result whose parts contradict each
         * other.
         *
         * @throws NullPointerException     if {@code outcome}, {@code oldDetails},
         *                                  {@code cardUpdateRecord}, {@code cardUpdateCvvCdImage} or
         *                                  {@code failedOperation} is {@code null}
         * @throws IllegalArgumentException if {@code oldDetails} is not the {@link DetailGroup#OLD}
         *                                  group, if {@code inputError} does not agree with
         *                                  {@code outcome}, or if the staged record and its CVV image
         *                                  are not both present or both absent
         */
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

        /**
         * Whether the record was rewritten. Equivalent to
         * {@code outcome() == WriteOutcome.CHANGES_OKAYED_AND_DONE}, named so a caller reads intent
         * rather than an identity test.
         *
         * @return {@code true} only when the full-width rewrite succeeded
         */
        public boolean isRewritten() {
            return outcome.isRewritten();
        }

        /**
         * Whether {@code 9300-CHECK-CHANGE-IN-REC} refused the update, in which case
         * {@link #oldDetails()} is the refreshed snapshot the screen must be repainted from.
         *
         * @return {@code true} only for {@link WriteOutcome#DATA_WAS_CHANGED_BEFORE_UPDATE}
         */
        public boolean isDataWasChangedBeforeUpdate() {
            return outcome == WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE;
        }
    }
}
