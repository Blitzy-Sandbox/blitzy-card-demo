package com.cardemo.batch.processors;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.AccountStatement;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the per-account
 * <strong>statement assembly</strong> logic of the legacy AWS CardDemo batch
 * program <strong>{@code CBSTM03A}</strong> (Statement Generator) in the
 * greenfield Java&nbsp;25 LTS + Spring Boot&nbsp;3.5.x migration.
 *
 * <h2>Provenance</h2>
 * <p>Translated from COBOL {@code app/cbl/CBSTM03A.CBL} at the frozen legacy
 * baseline commit SHA {@code 27d6c6f}. The COBOL source is
 * <strong>read-only</strong> reference material and is <strong>never copied</strong>
 * into this repository; traceability is by commit SHA only (AAP &sect;0.7.2). Per
 * the <strong>Minimal Change Clause</strong> (AAP &sect;0.7.1) this migration
 * reproduces the COBOL statement layout and content <em>exactly</em> &mdash; no
 * relabelling, no reformatting, no extra fields &mdash; and documents every
 * technology substitution at its point of use. The application base package is
 * {@code com.cardemo} (decision D-006).</p>
 *
 * <h2>What this processor reproduces ({@code CBSTM03A} main loop)</h2>
 * <p>{@code CBSTM03A}'s {@code 1000-MAINLINE} walks the {@code CARDXREF} file
 * ({@code 1000-XREFFILE-GET-NEXT}); for each cross-reference it reads the owning
 * customer ({@code 2000-CUSTFILE-GET}) and account ({@code 3000-ACCTFILE-GET}),
 * builds the statement header ({@code 5000-CREATE-STATEMENT} + the HTML helpers
 * {@code 5100-WRITE-HTML-HEADER} / {@code 5200-WRITE-HTML-NMADBS}), then emits one
 * detail row per transaction ({@code 6000-WRITE-TRANS}) while accumulating the
 * expense running total, and finally writes the total line and the closing
 * banners/footer ({@code 4000-TRNXFILE-GET}). This processor performs that whole
 * per-cross-reference assembly for a <em>single</em> input item and hands the two
 * assembled bodies to the downstream writer via an {@link AccountStatement}
 * carrier. The Spring Batch reader supplies the cross-references (the COBOL
 * {@code 1000-XREFFILE-GET-NEXT} browse) one at a time.</p>
 *
 * <h3>Output carrier (cross-folder coordination contract)</h3>
 * <p>The processor emits {@link AccountStatement} &mdash; an immutable carrier
 * defined once in {@code com.cardemo.model.dto} and consumed by
 * {@code com.cardemo.batch.writers.StatementWriter}. The COBOL program wrote two
 * files: {@code STMT-FILE} (an {@code FD} record of {@code PIC X(80)}, the
 * plain-text statement) and {@code HTML-FILE} (an {@code FD} record of
 * {@code PIC X(100)}, the HTML statement). In the migrated pipeline this
 * processor <em>assembles</em> both bodies and the writer <em>persists</em> them
 * to their respective sinks (S3 objects in the target, per AAP &sect;0.4.1); the
 * <strong>writer</strong>, not this processor, decides the destination object
 * keys / file names. The carrier's {@code totalExpense} component carries the
 * COBOL {@code WS-TOTAL-AMT} running total; the in-memory transaction row shape
 * is the "altered reporting layout" copybook {@code COSTM01}
 * ({@code 01 TRNX-RECORD}). An integrating agent must create/reuse the
 * {@link AccountStatement} carrier (an immutable {@code record}/POJO) in
 * {@code com.cardemo.model.dto} (or {@code com.cardemo.batch}); it is not owned by
 * this processor package.</p>
 *
 * <h2>Technology substitutions (documented at point of change)</h2>
 * <ul>
 *   <li><strong>VSAM keyed READ + {@code CBSTM03B} I/O subroutine &rarr; Spring
 *       Data repositories.</strong> {@code CBSTM03A} performs all file I/O by
 *       {@code CALL 'CBSTM03B'} (a generic OPEN/READ/READ-K/CLOSE subroutine).
 *       The keyed reads of {@code CUSTFILE} and {@code ACCTFILE}
 *       ({@code 2000-CUSTFILE-GET} / {@code 3000-ACCTFILE-GET}) become
 *       {@link CustomerRepository#findById(Object)} and
 *       {@link AccountRepository#findById(Object)}; an absent record &mdash; which
 *       in COBOL drives a non-zero {@code WS-M03B-RC} and
 *       {@code 9999-ABEND-PROGRAM} ({@code CALL 'CEE3ABD'}) &mdash; maps to a
 *       {@link RecordNotFoundException} (VSAM {@code FILE STATUS '23'}, AAP
 *       &sect;0.7.5).</li>
 *   <li><strong>Fixed 2-D pre-buffer {@code WS-TRNX-TABLE} &rarr; per-card
 *       query.</strong> {@code CBSTM03A} pre-buffers transactions into a fixed
 *       2-D working-storage table ({@code OCCURS 51} cards &times; {@code OCCURS
 *       10} transactions), filled by repeatedly calling {@code CBSTM03B}, then in
 *       {@code 4000-TRNXFILE-GET} replays the rows whose card number matches the
 *       current cross-reference. That whole scaffold is replaced idiomatically by
 *       a single {@link TransactionRepository#findByTranCardNum(String)} query
 *       &mdash; the per-card {@code OCCURS} limits (51&times;10) were storage
 *       constraints with no business meaning, so all of the card's transactions
 *       are included (Minimal Change Clause: behavior preserved, storage
 *       scaffolding dropped).</li>
 *   <li><strong>PSA/TCB/TIOT control-block inspection and {@code ALTER}/{@code GO
 *       TO} scaffolding &rarr; omitted.</strong> {@code CBSTM03A}'s PSA/TCB/TIOT
 *       control-block peeking (the {@code SET ADDRESS OF ...} chain that walks the
 *       z/OS Task I/O Table to {@code DISPLAY} DD names) and its
 *       {@code ALTER}-driven {@code GO TO} file-open dispatcher are z/OS runtime
 *       diagnostics with no business effect; they are intentionally omitted
 *       (Minimal Change Clause &mdash; no behavior to preserve).</li>
 *   <li><strong>{@code STMT-FILE} / {@code HTML-FILE} writes &rarr; writer
 *       scope.</strong> This processor performs <em>no</em> persistence or file
 *       I/O; it only assembles the two bodies. The actual writes are the
 *       {@code StatementWriter}'s responsibility (the {@code com.cardemo.batch.writers}
 *       boundary).</li>
 * </ul>
 *
 * <h2>Decimal precision (AAP &sect;0.7.3)</h2>
 * <p>Every monetary value &mdash; the account balance ({@code ACCT-CURR-BAL},
 * {@code PIC S9(10)V99}), each transaction amount ({@code TRNX-AMT},
 * {@code PIC S9(9)V99}) and the running total ({@code WS-TOTAL-AMT},
 * {@code PIC S9(9)V99}) &mdash; is handled with {@link BigDecimal}; no
 * {@code float}/{@code double} is used anywhere. The picture-edited renderings
 * preserve the exact COBOL formats: {@code ST-CURR-BAL PIC 9(9).99-} (nine
 * leading-zero integer digits, two decimals, trailing sign) and
 * {@code ST-TRANAMT}/{@code ST-TOTAL-TRAMT PIC Z(9).99-} (nine zero-suppressed
 * integer positions, two decimals, trailing sign).</p>
 *
 * <h2>Statelessness and thread-safety</h2>
 * <p>The processor holds only its three injected repositories (all stateless,
 * thread-safe Spring beans) and is therefore itself stateless and safe for the
 * concurrent invocation a chunk-oriented step may apply. The running total and
 * all builders are method-local, recreated per {@link #process(CardCrossReference)}
 * call, exactly mirroring the COBOL {@code MOVE 0 TO WS-TOTAL-AMT} reset performed
 * before each statement's transaction loop.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see AccountStatement
 * @see CardCrossReference
 * @see TransactionRepository#findByTranCardNum(String)
 */
@Component
public class StatementProcessor implements ItemProcessor<CardCrossReference, AccountStatement> {

    // ---------------------------------------------------------------------
    // Fixed-width field lengths (COBOL PIC widths from CBSTM03A STATEMENT-LINES)
    // ---------------------------------------------------------------------

    /** Width of a {@code STMT-FILE} text record: {@code FD-STMTFILE-REC PIC X(80)}. */
    private static final int TEXT_LINE_WIDTH = 80;
    /** {@code ST-NAME PIC X(75)} &mdash; the composed customer name field. */
    private static final int NAME_WIDTH = 75;
    /** {@code ST-ADD1}/{@code ST-ADD2 PIC X(50)} &mdash; address lines 1 and 2. */
    private static final int ADDR12_WIDTH = 50;
    /** {@code ST-ADD3 PIC X(80)} &mdash; the composed city/state/country/zip line. */
    private static final int ADDR3_WIDTH = 80;
    /** {@code L23-NAME PIC X(50)} &mdash; the HTML name field (truncation of {@code ST-NAME}). */
    private static final int HTML_NAME_WIDTH = 50;
    /** {@code ST-ACCT-ID}/{@code ST-FICO-SCORE PIC X(20)} &mdash; the basic-details value fields. */
    private static final int VALUE20_WIDTH = 20;
    /** {@code ST-TRANID PIC X(16)} &mdash; the transaction-id detail field. */
    private static final int TRANID_WIDTH = 16;
    /** {@code ST-TRANDT PIC X(49)} &mdash; the transaction-description detail field. */
    private static final int TRANDESC_WIDTH = 49;
    /** {@code ACCT-ID PIC 9(11)} integer digit count (the account-id numeric width). */
    private static final int ACCT_ID_DIGITS = 11;
    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} integer digit count. */
    private static final int FICO_DIGITS = 3;
    /** Integer-digit count of the picture-edited amount fields {@code 9(9)}/{@code Z(9)}. */
    private static final int AMOUNT_INT_DIGITS = 9;

    // ---------------------------------------------------------------------
    // Text banner / label literals (COBOL FILLER VALUEs from STATEMENT-LINES).
    // Built from explicit parts so every embedded space count is verifiable
    // against the COBOL PIC widths.
    // ---------------------------------------------------------------------

    /** {@code ST-LINE0}: {@code 31*'*'} + {@code "START OF STATEMENT"} + {@code 31*'*'} (= 80). */
    private static final String BANNER_START =
            "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
    /** {@code ST-LINE15}: {@code 32*'*'} + {@code "END OF STATEMENT"} + {@code 32*'*'} (= 80). */
    private static final String BANNER_END =
            "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);
    /** {@code ST-LINE5}/{@code ST-LINE10}/{@code ST-LINE12}: a full 80-column rule of {@code '-'}. */
    private static final String RULE_LINE = "-".repeat(TEXT_LINE_WIDTH);

    /** {@code ST-LINE7} label: {@code 'Account ID         :'} ({@code PIC X(20)}). */
    private static final String LBL_ACCOUNT_ID = "Account ID" + " ".repeat(9) + ":";
    /** {@code ST-LINE8} label: {@code 'Current Balance    :'} ({@code PIC X(20)}). */
    private static final String LBL_CURRENT_BALANCE = "Current Balance" + " ".repeat(4) + ":";
    /** {@code ST-LINE9} label: {@code 'FICO Score         :'} ({@code PIC X(20)}). */
    private static final String LBL_FICO_SCORE = "FICO Score" + " ".repeat(9) + ":";
    /** {@code ST-LINE6} centred caption: {@code "Basic Details"} padded to {@code PIC X(14)}. */
    private static final String CAPTION_BASIC_DETAILS = "Basic Details ";
    /** {@code ST-LINE11} centred caption: {@code 'TRANSACTION SUMMARY '} ({@code PIC X(20)}). */
    private static final String CAPTION_TRANSACTION_SUMMARY = "TRANSACTION SUMMARY ";
    /** {@code ST-LINE13} column header for the transaction id: {@code 'Tran ID         '} ({@code X(16)}). */
    private static final String HDR_TRAN_ID = "Tran ID" + " ".repeat(9);
    /** {@code ST-LINE13} column header for the details: {@code 'Tran Details    '} ({@code X(51)}). */
    private static final String HDR_TRAN_DETAILS = "Tran Details    ";
    /** {@code ST-LINE13} column header for the amount: {@code '  Tran Amount'} ({@code X(13)}). */
    private static final String HDR_TRAN_AMOUNT = "  Tran Amount";
    /** {@code ST-LINE14A} caption preceding the running total: {@code 'Total EXP:'} ({@code X(10)}). */
    private static final String CAPTION_TOTAL_EXP = "Total EXP:";

    // ---------------------------------------------------------------------
    // HTML literals (COBOL HTML-LINES 88-level VALUEs from CBSTM03A).
    // Continuation lines in the copybook are reconstructed into single strings.
    // ---------------------------------------------------------------------

    private static final String HTML_DOCTYPE = "<!DOCTYPE html>";
    private static final String HTML_OPEN = "<html lang=\"en\">";
    private static final String HTML_HEAD_OPEN = "<head>";
    private static final String HTML_META = "<meta charset=\"utf-8\">";
    private static final String HTML_TITLE = "<title>HTML Table Layout</title>";
    private static final String HTML_HEAD_CLOSE = "</head>";
    private static final String HTML_BODY_OPEN = "<body style=\"margin:0px;\">";
    private static final String HTML_TABLE_OPEN =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
    private static final String HTML_TR_OPEN = "<tr>";
    private static final String HTML_TR_CLOSE = "</tr>";
    private static final String HTML_TD_CLOSE = "</td>";
    /** {@code HTML-L10}: full-width dark banner cell ({@code background-color:#1d1d96b3}). */
    private static final String HTML_TD_TITLEBAR =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
    /** {@code HTML-L15}: full-width amber bank-header cell ({@code background-color:#FFAF33}). */
    private static final String HTML_TD_BANKHDR =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
    /** {@code HTML-L22-35}: full-width light cell ({@code background-color:#f2f2f2}). */
    private static final String HTML_TD_LIGHT =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
    /** {@code HTML-L30-42}: full-width centred section-title cell ({@code background-color:#33FFD1}). */
    private static final String HTML_TD_SECTION =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
    private static final String HTML_BANK_NAME = "<p style=\"font-size:16px\">Bank of XYZ</p>";
    private static final String HTML_BANK_ADDR1 = "<p>410 Terry Ave N</p>";
    private static final String HTML_BANK_ADDR2 = "<p>Seattle WA 99999</p>";
    private static final String HTML_TITLE_BASIC_DETAILS = "<p style=\"font-size:16px\">Basic Details</p>";
    private static final String HTML_TITLE_TRAN_SUMMARY = "<p style=\"font-size:16px\">Transaction Summary</p>";
    /** {@code HTML-L47}: header cell for the Tran ID column ({@code #33FF5E}, left). */
    private static final String HTML_TD_HDR_ID =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_HDR_TRAN_ID = "<p style=\"font-size:16px\">Tran ID</p>";
    /** {@code HTML-L50}: header cell for the Tran Details column ({@code #33FF5E}, left). */
    private static final String HTML_TD_HDR_DETAILS =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_HDR_TRAN_DETAILS = "<p style=\"font-size:16px\">Tran Details</p>";
    /** {@code HTML-L53}: header cell for the Amount column ({@code #33FF5E}, right). */
    private static final String HTML_TD_HDR_AMOUNT =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
    private static final String HTML_HDR_AMOUNT = "<p style=\"font-size:16px\">Amount</p>";
    /** {@code HTML-L58}: detail cell for the Tran ID column ({@code #f2f2f2}, left). */
    private static final String HTML_TD_DET_ID =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    /** {@code HTML-L61}: detail cell for the Tran Details column ({@code #f2f2f2}, left). */
    private static final String HTML_TD_DET_DETAILS =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    /** {@code HTML-L64}: detail cell for the Amount column ({@code #f2f2f2}, right). */
    private static final String HTML_TD_DET_AMOUNT =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
    private static final String HTML_END_OF_STATEMENT = "<h3>End of Statement</h3>";
    private static final String HTML_TABLE_CLOSE = "</table>";
    private static final String HTML_BODY_CLOSE = "</body>";
    private static final String HTML_CLOSE = "</html>";
    /** {@code HTML-L11} prefix: {@code '<h3>Statement for Account Number: '} ({@code PIC X(34)}). */
    private static final String HTML_STMT_FOR_ACCT_PREFIX = "<h3>Statement for Account Number: ";
    /** Plain paragraph open tag {@code <p>} (COBOL STRING literal {@code '<p>'}). */
    private static final String HTML_P_OPEN = "<p>";
    /** 16px paragraph open tag (COBOL STRING literal {@code '<p style="font-size:16px">'}). */
    private static final String HTML_P_FONT16_OPEN = "<p style=\"font-size:16px\">";
    /** Paragraph close tag {@code </p>} (COBOL STRING literal {@code '</p>'}). */
    private static final String HTML_P_CLOSE = "</p>";
    /** Heading-3 close tag {@code </h3>} (closes {@code HTML-L11}). */
    private static final String HTML_H3_CLOSE = "</h3>";
    /**
     * Trailing {@code "  </p>"} appended to the HTML name/address paragraphs:
     * COBOL strings these with {@code '  ' DELIMITED BY SIZE} (two literal spaces)
     * followed by {@code '</p>'}, so the two spaces are preserved exactly.
     */
    private static final String HTML_TWOSPACE_P_CLOSE = "  </p>";
    /** {@code HTML-BSIC-LN} prefix for the account id: {@code '<p>Account ID         : '}. */
    private static final String HTML_BSIC_ACCT_PREFIX = "<p>Account ID" + " ".repeat(9) + ": ";
    /** {@code HTML-BSIC-LN} prefix for the current balance: {@code '<p>Current Balance    : '}. */
    private static final String HTML_BSIC_BAL_PREFIX = "<p>Current Balance" + " ".repeat(4) + ": ";
    /** {@code HTML-BSIC-LN} prefix for the FICO score: {@code '<p>FICO Score         : '}. */
    private static final String HTML_BSIC_FICO_PREFIX = "<p>FICO Score" + " ".repeat(9) + ": ";

    // ---------------------------------------------------------------------
    // Injected collaborators (replace the CBSTM03B I/O subroutine).
    // ---------------------------------------------------------------------

    /** Customer dataset access ({@code CUSTFILE} keyed read, {@code 2000-CUSTFILE-GET}). */
    private final CustomerRepository customerRepository;
    /** Account dataset access ({@code ACCTFILE} keyed read, {@code 3000-ACCTFILE-GET}). */
    private final AccountRepository accountRepository;
    /** Transaction dataset access (replaces the {@code WS-TRNX-TABLE} pre-buffer). */
    private final TransactionRepository transactionRepository;

    /**
     * Constructs the processor with its three data-access collaborators. These
     * replace the single COBOL {@code CBSTM03B} I/O subroutine that
     * {@code CBSTM03A} called for every OPEN/READ/READ-K/CLOSE operation: keyed
     * customer/account reads and the per-card transaction scan are each served by
     * their own Spring Data repository.
     *
     * @param customerRepository    keyed customer access ({@code CUSTFILE})
     * @param accountRepository     keyed account access ({@code ACCTFILE})
     * @param transactionRepository per-card transaction access (replaces
     *                              {@code WS-TRNX-TABLE} + {@code CBSTM03B})
     */
    @Autowired
    public StatementProcessor(CustomerRepository customerRepository,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Assembles the dual-format (plain-text + HTML) statement for a single card
     * cross-reference, reproducing one full pass of {@code CBSTM03A}'s
     * {@code 1000-MAINLINE} body for that cross-reference.
     *
     * <p>Steps, in COBOL order:</p>
     * <ol>
     *   <li>Resolve the owning customer ({@code 2000-CUSTFILE-GET}) and account
     *       ({@code 3000-ACCTFILE-GET}) by keyed read; a missing record &mdash;
     *       which abends the COBOL job &mdash; raises {@link RecordNotFoundException}
     *       (FILE STATUS {@code '23'}).</li>
     *   <li>Load this card's transactions with a single
     *       {@link TransactionRepository#findByTranCardNum(String)} query,
     *       replacing the {@code WS-TRNX-TABLE} pre-buffer. Transaction order is
     *       taken as the repository returns it (no invented sort), matching the
     *       COBOL sequential read order.</li>
     *   <li>Build the text header and the HTML header/name/address/basic-details
     *       ({@code 5000-CREATE-STATEMENT}, {@code 5100}, {@code 5200}).</li>
     *   <li>Emit one detail row per transaction in both formats
     *       ({@code 6000-WRITE-TRANS}) while accumulating the expense running
     *       total ({@code ADD TRNX-AMT TO WS-TOTAL-AMT}).</li>
     *   <li>Append the total line and the closing banner/footer
     *       ({@code 4000-TRNXFILE-GET} tail).</li>
     * </ol>
     *
     * <p>The running total is reset to {@link BigDecimal#ZERO} at the start of
     * every call, mirroring the COBOL {@code MOVE 0 TO WS-TOTAL-AMT} performed
     * before each statement's transaction loop. Exactly one statement is produced
     * per cross-reference; this method <strong>never returns {@code null}</strong>.
     * An account with no transactions still yields a statement whose header and
     * basic-details sections are present, whose Transaction Summary is empty and
     * whose {@code totalExpense} is {@link BigDecimal#ZERO}.</p>
     *
     * @param xref the card cross-reference supplied by the reader (COBOL
     *             {@code 1000-XREFFILE-GET-NEXT}); not {@code null}
     * @return the assembled {@link AccountStatement} carrier; never {@code null}
     * @throws RecordNotFoundException if the customer or account keyed read finds
     *                                 no record (COBOL abend &rarr; FILE STATUS
     *                                 {@code '23'})
     */
    @Override
    public AccountStatement process(CardCrossReference xref) {
        // ---- COBOL 2000-CUSTFILE-GET: keyed read of CUSTFILE by XREF-CUST-ID. ----
        // VSAM keyed READ via CBSTM03B -> CustomerRepository.findById; an absent
        // record drives 9999-ABEND-PROGRAM in COBOL -> RecordNotFoundException ("23").
        final Long custId = xref.getXrefCustId();
        final Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> new RecordNotFoundException("Customer", String.valueOf(custId)));

        // ---- COBOL 3000-ACCTFILE-GET: keyed read of ACCTFILE by XREF-ACCT-ID. ----
        final Long acctId = xref.getXrefAcctId();
        final Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException("Account", String.valueOf(acctId)));

        // ---- COBOL WS-TRNX-TABLE (OCCURS 51 x 10) pre-buffer + CBSTM03B I/O ----
        // replaced by a single per-card query. The fixed 51x10 OCCURS limits were
        // storage constraints with no business meaning; every one of the card's
        // transactions is included (Minimal Change Clause: behavior preserved,
        // storage scaffolding dropped). Read order is preserved exactly as the
        // repository returns it -- no sort is invented (4000-TRNXFILE-GET replayed
        // the buffer in fill/read order).
        final String cardNumber = xref.getXrefCardNum();
        final List<Transaction> transactions = transactionRepository.findByTranCardNum(cardNumber);

        // ---- COBOL 5000-CREATE-STATEMENT: compose the picture-edited header fields. ----
        final String stName = padRight(composeName(customer), NAME_WIDTH);              // ST-NAME    PIC X(75)
        final String stAdd1 = padRight(customer.getCustAddrLine1(), ADDR12_WIDTH);      // ST-ADD1    PIC X(50)
        final String stAdd2 = padRight(customer.getCustAddrLine2(), ADDR12_WIDTH);      // ST-ADD2    PIC X(50)
        final String stAdd3 = padRight(composeAddressLine3(customer), ADDR3_WIDTH);     // ST-ADD3    PIC X(80)
        final String stAcctId = formatAccountId(account.getAcctId());                   // ST-ACCT-ID PIC X(20) (from ACCT-ID 9(11))
        final String stCurrBal = formatBalancePic9(account.getAcctCurrBal());           // ST-CURR-BAL PIC 9(9).99-
        final String stFico = formatFico(customer.getCustFicoCreditScore());            // ST-FICO-SCORE PIC X(20) (from 9(03))

        final List<String> text = new ArrayList<>();
        final List<String> html = new ArrayList<>();

        // ===== Text banner (ST-LINE0) + HTML header (5100) + HTML name/addr/basic (5200) =====
        text.add(textLine(BANNER_START));                                               // ST-LINE0
        appendHtmlHeader(html, stAcctId);                                               // 5100-WRITE-HTML-HEADER
        appendHtmlNameAddressBasics(html, stName, stAdd1, stAdd2, stAdd3,
                stAcctId, stCurrBal, stFico);                                           // 5200-WRITE-HTML-NMADBS

        // ===== Text header lines ST-LINE1..ST-LINE13 (with interleaved rules) =====
        text.add(textLine(stName + " ".repeat(5)));                                     // ST-LINE1 (X75 + 5)
        text.add(textLine(stAdd1 + " ".repeat(30)));                                    // ST-LINE2 (X50 + 30)
        text.add(textLine(stAdd2 + " ".repeat(30)));                                    // ST-LINE3 (X50 + 30)
        text.add(textLine(stAdd3));                                                     // ST-LINE4 (X80)
        text.add(textLine(RULE_LINE));                                                  // ST-LINE5
        text.add(textLine(" ".repeat(33) + CAPTION_BASIC_DETAILS + " ".repeat(33)));    // ST-LINE6
        text.add(textLine(RULE_LINE));                                                  // ST-LINE5 (repeated)
        text.add(textLine(LBL_ACCOUNT_ID + stAcctId + " ".repeat(40)));                 // ST-LINE7
        text.add(textLine(LBL_CURRENT_BALANCE + stCurrBal + " ".repeat(7) + " ".repeat(40))); // ST-LINE8 (two FILLERs: 7 + 40)
        text.add(textLine(LBL_FICO_SCORE + stFico + " ".repeat(40)));                   // ST-LINE9
        text.add(textLine(RULE_LINE));                                                  // ST-LINE10
        text.add(textLine(" ".repeat(30) + CAPTION_TRANSACTION_SUMMARY + " ".repeat(30))); // ST-LINE11
        text.add(textLine(RULE_LINE));                                                  // ST-LINE12
        text.add(textLine(HDR_TRAN_ID + padRight(HDR_TRAN_DETAILS, 51) + HDR_TRAN_AMOUNT)); // ST-LINE13
        text.add(textLine(RULE_LINE));                                                  // ST-LINE12 (repeated)

        // ===== COBOL: MOVE 0 TO WS-TOTAL-AMT, then 4000-TRNXFILE-GET loop (6000-WRITE-TRANS). =====
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (final Transaction txn : transactions) {
            final BigDecimal amt = txn.getTranAmt();
            final String stTranId = padRight(txn.getTranId(), TRANID_WIDTH);            // ST-TRANID  PIC X(16)
            final String stTranDt = padRight(txn.getTranDesc(), TRANDESC_WIDTH);        // ST-TRANDT  PIC X(49) (TRNX-DESC X(100) truncated)
            final String stTranAmt = formatAmountPicZ(amt);                             // ST-TRANAMT PIC Z(9).99-

            // ST-LINE14: id + ' ' + desc + '$' + amount  (6000-WRITE-TRANS text row).
            text.add(textLine(stTranId + " " + stTranDt + "$" + stTranAmt));
            // 6000-WRITE-TRANS HTML detail rows.
            appendHtmlTransaction(html, stTranId, stTranDt, stTranAmt);
            // COBOL: ADD TRNX-AMT TO WS-TOTAL-AMT (accumulate after writing the row).
            totalExpense = totalExpense.add(amt == null ? BigDecimal.ZERO : amt);
        }

        // ===== COBOL 4000-TRNXFILE-GET tail: total line + closing banner (text) and footer (HTML). =====
        final String stTotal = formatAmountPicZ(totalExpense);                          // ST-TOTAL-TRAMT PIC Z(9).99-
        text.add(textLine(RULE_LINE));                                                  // ST-LINE12
        text.add(textLine(CAPTION_TOTAL_EXP + " ".repeat(56) + "$" + stTotal));         // ST-LINE14A
        text.add(textLine(BANNER_END));                                                 // ST-LINE15
        appendHtmlFooter(html);                                                         // </td></tr></table></body></html>

        final String textBody = String.join("\n", text);
        final String htmlBody = String.join("\n", html);

        // COBOL emits exactly one statement per cross-reference; never null.
        return new AccountStatement(account.getAcctId(), cardNumber, textBody, htmlBody, totalExpense);
    }

    // =====================================================================
    // HTML section builders (each mirrors a COBOL paragraph's SET/WRITE chain;
    // every WRITE FD-HTMLFILE-REC becomes one appended line).
    // =====================================================================

    /**
     * Appends the HTML document head and bank-header rows, reproducing
     * {@code 5100-WRITE-HTML-HEADER} up to (and including) the opening of the
     * name/address cell that {@code 5200} continues.
     *
     * @param html     the accumulating HTML line list
     * @param stAcctId the picture-edited account id ({@code L11-ACCT PIC X(20)})
     */
    private static void appendHtmlHeader(List<String> html, String stAcctId) {
        html.add(HTML_DOCTYPE);
        html.add(HTML_OPEN);
        html.add(HTML_HEAD_OPEN);
        html.add(HTML_META);
        html.add(HTML_TITLE);
        html.add(HTML_HEAD_CLOSE);
        html.add(HTML_BODY_OPEN);
        html.add(HTML_TABLE_OPEN);
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_TITLEBAR);
        html.add(HTML_STMT_FOR_ACCT_PREFIX + stAcctId + HTML_H3_CLOSE);                 // HTML-L11
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_BANKHDR);
        html.add(HTML_BANK_NAME);                                                       // HTML-L16
        html.add(HTML_BANK_ADDR1);                                                      // HTML-L17
        html.add(HTML_BANK_ADDR2);                                                      // HTML-L18
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_LIGHT);                                                        // HTML-L22-35 (open cell continued in 5200)
    }

    /**
     * Appends the HTML name, address, Basic Details and Transaction Summary
     * header rows, reproducing {@code 5200-WRITE-HTML-NMADBS}.
     *
     * <p>The name and the three address paragraphs use the COBOL
     * {@code DELIMITED BY '  '} (two-space) trim before the {@code '  ' DELIMITED
     * BY SIZE} + {@code '</p>'} suffix; the basic-detail values use
     * {@code DELIMITED BY '*'}, i.e. the full fixed-width field is emitted.</p>
     *
     * @param html      the accumulating HTML line list
     * @param stName    {@code ST-NAME PIC X(75)} (truncated to {@code L23-NAME X(50)})
     * @param stAdd1    {@code ST-ADD1 PIC X(50)}
     * @param stAdd2    {@code ST-ADD2 PIC X(50)}
     * @param stAdd3    {@code ST-ADD3 PIC X(80)}
     * @param stAcctId  {@code ST-ACCT-ID PIC X(20)}
     * @param stCurrBal {@code ST-CURR-BAL PIC 9(9).99-}
     * @param stFico    {@code ST-FICO-SCORE PIC X(20)}
     */
    private static void appendHtmlNameAddressBasics(List<String> html, String stName,
            String stAdd1, String stAdd2, String stAdd3,
            String stAcctId, String stCurrBal, String stFico) {
        // L23-NAME = ST-NAME moved to PIC X(50); then DELIMITED BY '  ' (two spaces).
        final String htmlName = delimitedByDoubleSpace(stName.substring(0, HTML_NAME_WIDTH));
        html.add(HTML_P_FONT16_OPEN + htmlName + HTML_TWOSPACE_P_CLOSE);                // name <p>
        html.add(HTML_P_OPEN + delimitedByDoubleSpace(stAdd1) + HTML_TWOSPACE_P_CLOSE); // address line 1
        html.add(HTML_P_OPEN + delimitedByDoubleSpace(stAdd2) + HTML_TWOSPACE_P_CLOSE); // address line 2
        html.add(HTML_P_OPEN + delimitedByDoubleSpace(stAdd3) + HTML_TWOSPACE_P_CLOSE); // address line 3
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_SECTION);                                                      // HTML-L30-42
        html.add(HTML_TITLE_BASIC_DETAILS);                                             // HTML-L31
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_LIGHT);                                                        // HTML-L22-35
        html.add(HTML_BSIC_ACCT_PREFIX + stAcctId + HTML_P_CLOSE);                      // Account ID
        html.add(HTML_BSIC_BAL_PREFIX + stCurrBal + HTML_P_CLOSE);                      // Current Balance
        html.add(HTML_BSIC_FICO_PREFIX + stFico + HTML_P_CLOSE);                        // FICO Score
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_SECTION);                                                      // HTML-L30-42
        html.add(HTML_TITLE_TRAN_SUMMARY);                                              // HTML-L43
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_HDR_ID);                                                       // HTML-L47
        html.add(HTML_HDR_TRAN_ID);                                                     // HTML-L48
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TD_HDR_DETAILS);                                                  // HTML-L50
        html.add(HTML_HDR_TRAN_DETAILS);                                                // HTML-L51
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TD_HDR_AMOUNT);                                                   // HTML-L53
        html.add(HTML_HDR_AMOUNT);                                                      // HTML-L54
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
    }

    /**
     * Appends one transaction's HTML detail row set, reproducing
     * {@code 6000-WRITE-TRANS}'s HTML emission.
     *
     * @param html      the accumulating HTML line list
     * @param stTranId  {@code ST-TRANID PIC X(16)}
     * @param stTranDt  {@code ST-TRANDT PIC X(49)}
     * @param stTranAmt {@code ST-TRANAMT PIC Z(9).99-}
     */
    private static void appendHtmlTransaction(List<String> html, String stTranId,
            String stTranDt, String stTranAmt) {
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_DET_ID);                                                       // HTML-L58
        html.add(HTML_P_OPEN + stTranId + HTML_P_CLOSE);
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TD_DET_DETAILS);                                                  // HTML-L61
        html.add(HTML_P_OPEN + stTranDt + HTML_P_CLOSE);
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TD_DET_AMOUNT);                                                   // HTML-L64
        html.add(HTML_P_OPEN + stTranAmt + HTML_P_CLOSE);
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
    }

    /**
     * Appends the closing "End of Statement" banner row and the document-closing
     * tags, reproducing the tail of {@code 4000-TRNXFILE-GET}.
     *
     * @param html the accumulating HTML line list
     */
    private static void appendHtmlFooter(List<String> html) {
        html.add(HTML_TR_OPEN);
        html.add(HTML_TD_TITLEBAR);                                                     // HTML-L10
        html.add(HTML_END_OF_STATEMENT);                                                // HTML-L75
        html.add(HTML_TD_CLOSE);
        html.add(HTML_TR_CLOSE);
        html.add(HTML_TABLE_CLOSE);                                                     // HTML-L78
        html.add(HTML_BODY_CLOSE);                                                      // HTML-L79
        html.add(HTML_CLOSE);                                                           // HTML-L80
    }

    // =====================================================================
    // Field composition helpers (COBOL STRING ... DELIMITED BY ' ' semantics).
    // =====================================================================

    /**
     * Composes {@code ST-NAME} exactly as COBOL {@code 5000-CREATE-STATEMENT}
     * does: {@code STRING CUST-FIRST-NAME DELIMITED BY ' '  ' '  CUST-MIDDLE-NAME
     * DELIMITED BY ' '  ' '  CUST-LAST-NAME DELIMITED BY ' '  ' '}. Each name part
     * contributes the characters <em>before its first space</em>
     * ({@code DELIMITED BY ' '}); the parts are joined with single literal spaces
     * and a trailing space. An empty middle name therefore yields two consecutive
     * spaces between first and last name &mdash; a faithful reproduction of the
     * COBOL behavior, preserved per the Minimal Change Clause.
     *
     * @param customer the resolved customer record
     * @return the raw composed name (before the {@code ST-NAME PIC X(75)} fit)
     */
    private static String composeName(Customer customer) {
        final String first = tokenBeforeFirstSpace(customer.getCustFirstName());
        final String middle = tokenBeforeFirstSpace(customer.getCustMiddleName());
        final String last = tokenBeforeFirstSpace(customer.getCustLastName());
        return first + " " + middle + " " + last + " ";
    }

    /**
     * Composes {@code ST-ADD3} exactly as COBOL {@code 5000-CREATE-STATEMENT}
     * does: {@code STRING CUST-ADDR-LINE-3 DELIMITED BY ' '  ' '
     * CUST-ADDR-STATE-CD DELIMITED BY ' '  ' '  CUST-ADDR-COUNTRY-CD DELIMITED BY
     * ' '  ' '  CUST-ADDR-ZIP DELIMITED BY ' '  ' '}. Each part contributes the
     * characters before its first space and the parts are joined with single
     * literal spaces and a trailing space.
     *
     * @param customer the resolved customer record
     * @return the raw composed address line 3 (before the {@code ST-ADD3 PIC X(80)} fit)
     */
    private static String composeAddressLine3(Customer customer) {
        final String addr3 = tokenBeforeFirstSpace(customer.getCustAddrLine3());
        final String state = tokenBeforeFirstSpace(customer.getCustAddrStateCd());
        final String country = tokenBeforeFirstSpace(customer.getCustAddrCountryCd());
        final String zip = tokenBeforeFirstSpace(customer.getCustAddrZip());
        return addr3 + " " + state + " " + country + " " + zip + " ";
    }

    /**
     * Reproduces COBOL {@code STRING ... DELIMITED BY ' '} for a single source
     * field: returns the characters up to (but not including) the first space. A
     * {@code null} field is treated as all-spaces and yields the empty string; a
     * field with no space is returned whole.
     *
     * @param value the source field value (may be {@code null})
     * @return the substring before the first space, or {@code ""}
     */
    private static String tokenBeforeFirstSpace(String value) {
        final String safe = value == null ? "" : value;
        final int idx = safe.indexOf(' ');
        return idx < 0 ? safe : safe.substring(0, idx);
    }

    /**
     * Reproduces COBOL {@code STRING ... DELIMITED BY '  '} (two spaces): returns
     * the characters up to (but not including) the first run of two consecutive
     * spaces. Used by the HTML name/address paragraphs to strip the fixed-width
     * trailing padding.
     *
     * @param value the source field value (never {@code null} from callers)
     * @return the substring before the first double-space, or the whole value
     */
    private static String delimitedByDoubleSpace(String value) {
        final int idx = value.indexOf("  ");
        return idx < 0 ? value : value.substring(0, idx);
    }

    // =====================================================================
    // Picture-edit helpers (reproduce COBOL numeric-edited MOVE targets).
    // =====================================================================

    /**
     * Renders a {@link BigDecimal} in the COBOL edited picture {@code 9(9).99-}
     * ({@code ST-CURR-BAL}): nine integer digits with leading zeros, a decimal
     * point, two decimal digits, and a trailing sign ({@code '-'} for negative,
     * a space for zero/positive). Always 13 characters. If the integer part
     * exceeds nine digits the high-order digits are dropped, matching a COBOL
     * {@code MOVE} into the smaller {@code 9(9)} target.
     *
     * @param value the amount (a {@code null} is treated as zero)
     * @return the 13-character picture-edited string
     */
    private static String formatBalancePic9(BigDecimal value) {
        final BigDecimal v = value == null ? BigDecimal.ZERO : value;
        final boolean negative = v.signum() < 0;
        final BigDecimal scaled = v.abs().setScale(2, RoundingMode.HALF_EVEN);
        String digits = scaled.unscaledValue().toString();           // non-negative; e.g. 100050 for 1000.50
        while (digits.length() < AMOUNT_INT_DIGITS + 2) {            // ensure >= 9 integer + 2 fraction
            digits = "0" + digits;
        }
        final String fraction = digits.substring(digits.length() - 2);
        String integer = digits.substring(0, digits.length() - 2);
        if (integer.length() > AMOUNT_INT_DIGITS) {                 // COBOL MOVE truncates high-order
            integer = integer.substring(integer.length() - AMOUNT_INT_DIGITS);
        }
        return integer + "." + fraction + (negative ? '-' : ' ');
    }

    /**
     * Renders a {@link BigDecimal} in the COBOL edited picture {@code Z(9).99-}
     * ({@code ST-TRANAMT} / {@code ST-TOTAL-TRAMT}): nine integer positions with
     * leading-zero <em>suppression</em> (leading zeros become spaces, and an
     * all-zero integer part becomes nine spaces), a decimal point, two decimal
     * digits, and a trailing sign ({@code '-'} for negative, a space for
     * zero/positive). Always 13 characters.
     *
     * @param value the amount (a {@code null} is treated as zero)
     * @return the 13-character picture-edited string
     */
    private static String formatAmountPicZ(BigDecimal value) {
        final BigDecimal v = value == null ? BigDecimal.ZERO : value;
        final boolean negative = v.signum() < 0;
        final BigDecimal scaled = v.abs().setScale(2, RoundingMode.HALF_EVEN);
        String digits = scaled.unscaledValue().toString();
        while (digits.length() < AMOUNT_INT_DIGITS + 2) {
            digits = "0" + digits;
        }
        final String fraction = digits.substring(digits.length() - 2);
        String integer = digits.substring(0, digits.length() - 2);
        if (integer.length() > AMOUNT_INT_DIGITS) {                 // COBOL MOVE truncates high-order
            integer = integer.substring(integer.length() - AMOUNT_INT_DIGITS);
        }
        // Zero-suppress: drop leading zeros (all-zero -> empty), then right-justify
        // within nine positions with leading spaces (the 'Z' edit character).
        final String stripped = stripLeadingZeros(integer);
        final String intField = " ".repeat(AMOUNT_INT_DIGITS - stripped.length()) + stripped;
        return intField + "." + fraction + (negative ? '-' : ' ');
    }

    /**
     * Strips leading {@code '0'} characters from a non-negative digit string,
     * returning {@code ""} when the value is all zeros (the COBOL {@code Z} edit
     * suppresses every leading zero, leaving the field blank for a zero value).
     *
     * @param digits a string of decimal digits
     * @return the digits with leading zeros removed (empty when all zeros)
     */
    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    /**
     * Renders the account id as {@code ST-ACCT-ID PIC X(20)} does: the account id
     * ({@code ACCT-ID PIC 9(11)}) is first formatted as eleven digits with
     * leading zeros (high-order digits dropped if longer, matching a COBOL
     * {@code MOVE} into {@code 9(11)}), then left-justified into a 20-character
     * field padded with trailing spaces.
     *
     * @param acctId the account id (a {@code null} is treated as zero)
     * @return the 20-character account-id field
     */
    private static String formatAccountId(Long acctId) {
        final long value = acctId == null ? 0L : Math.abs(acctId);
        String digits = Long.toString(value);
        if (digits.length() < ACCT_ID_DIGITS) {
            digits = "0".repeat(ACCT_ID_DIGITS - digits.length()) + digits;
        } else if (digits.length() > ACCT_ID_DIGITS) {
            digits = digits.substring(digits.length() - ACCT_ID_DIGITS);
        }
        return padRight(digits, VALUE20_WIDTH);
    }

    /**
     * Renders the FICO score as {@code ST-FICO-SCORE PIC X(20)} does: the score
     * ({@code CUST-FICO-CREDIT-SCORE PIC 9(03)}) is first formatted as three
     * digits with leading zeros (high-order digits dropped if longer), then
     * left-justified into a 20-character field padded with trailing spaces.
     *
     * @param fico the FICO credit score (a {@code null} is treated as zero)
     * @return the 20-character FICO field
     */
    private static String formatFico(Integer fico) {
        final int value = fico == null ? 0 : Math.abs(fico);
        String digits = Integer.toString(value);
        if (digits.length() < FICO_DIGITS) {
            digits = "0".repeat(FICO_DIGITS - digits.length()) + digits;
        } else if (digits.length() > FICO_DIGITS) {
            digits = digits.substring(digits.length() - FICO_DIGITS);
        }
        return padRight(digits, VALUE20_WIDTH);
    }

    // =====================================================================
    // Fixed-width helpers (COBOL alphanumeric MOVE into a PIC X(n) target).
    // =====================================================================

    /**
     * Fits a value into a fixed-width text record, reproducing the
     * {@code FD-STMTFILE-REC PIC X(80)} contract: the value is right-padded with
     * spaces (or truncated) to exactly {@link #TEXT_LINE_WIDTH} characters.
     *
     * @param content the line content (already composed to 80 columns by callers)
     * @return an exactly 80-character line
     */
    private static String textLine(String content) {
        return padRight(content, TEXT_LINE_WIDTH);
    }

    /**
     * Reproduces a COBOL alphanumeric {@code MOVE} into a {@code PIC X(width)}
     * target: a shorter (or {@code null}) value is left-justified and padded on
     * the right with spaces; a longer value is truncated to {@code width}
     * characters. The result is always exactly {@code width} characters.
     *
     * @param value the source value (may be {@code null}, treated as all-spaces)
     * @param width the fixed field width (the COBOL {@code PIC X} length)
     * @return the value fitted to exactly {@code width} characters
     */
    private static String padRight(String value, int width) {
        final String safe = value == null ? "" : value;
        if (safe.length() == width) {
            return safe;
        }
        if (safe.length() > width) {
            return safe.substring(0, width);
        }
        return safe + " ".repeat(width - safe.length());
    }
}
