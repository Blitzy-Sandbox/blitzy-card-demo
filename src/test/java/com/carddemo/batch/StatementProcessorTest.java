package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.carddemo.dto.StatementTransactionDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.service.StatementFileService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Pure, fast unit test for {@link StatementProcessor}, the chunk-step
 * {@code ItemProcessor<CardXref, StatementDocument>} that assembles one account statement per card
 * cross-reference. It is the Java translation of the COBOL batch program {@code CBSTM03A}
 * ({@code 5000-CREATE-STATEMENT}), whose {@code CALL 'CBSTM03B'} file-I/O linkage is reworked into a
 * constructor-injected {@link StatementFileService} bean &mdash; the canonical AAP &sect;0.4.3
 * CALL&rarr;bean transformation. The frozen COBOL source is referenced read-only at commit SHA
 * {@code 27d6c6f} (never copied into this repository).
 *
 * <p>The single collaborator {@link StatementFileService} is a Mockito mock (the CALL&rarr;bean
 * seam), so the suite loads no Spring context and touches no database, Testcontainers, Docker, or
 * live AWS &mdash; it exercises the real {@link StatementProcessor#process(CardXref)} path in
 * milliseconds and feeds JaCoCo line coverage (Gate&nbsp;8). Domain entities are constructed as real
 * instances (not mocks) so entity reference-equality can be asserted and Mockito strict stubbing is
 * never tripped by incidental getter calls during rendering.</p>
 *
 * <p>Coverage is grouped by the behaviour under test:</p>
 * <ul>
 *   <li><strong>Happy-path assembly</strong> &mdash; a resolvable cross-reference yields a fully
 *       populated {@link StatementDocument} whose {@code cardNumber()} echoes the driving row, whose
 *       joined entities are the resolved instances, and whose transaction lines preserve source
 *       order; a missing (optional) card does not suppress the statement, and {@code null}
 *       transaction lines coalesce to an empty list.</li>
 *   <li><strong>Line-width invariants</strong> &mdash; every plain-text line is bounded to the
 *       {@code FD-STMTFILE-REC PIC X(80)} record length and every HTML line to the
 *       {@code FD-HTMLFILE-REC PIC X(100)} record length ({@code CREASTMT.JCL} {@code LRECL=80/100}),
 *       even when the source fields far exceed those widths.</li>
 *   <li><strong>Missing-parent filtering</strong> &mdash; an unresolved account or customer yields
 *       {@code null} (the idiomatic Spring Batch "filter this item" signal); each specific absence is
 *       isolated so the assertion is not over-broadened.</li>
 *   <li><strong>Constructor contract</strong> and retained <strong>security guards</strong>
 *       (stored-XSS escaping of the HTML rendition and last-four PAN masking in DEBUG diagnostics).</li>
 * </ul>
 *
 * @see StatementProcessor
 * @see StatementDocument
 * @see StatementFileService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor — CBSTM03A statement assembly (CALL 'CBSTM03B' → StatementFileService, SHA 27d6c6f)")
class StatementProcessorTest {

    /** A well-known, non-real sample PAN whose last four digits are {@code 1111}. */
    private static final String SAMPLE_CARD_NUMBER = "4111111111111111";

    /** The masked rendering the PAN-protection guard must produce: twelve {@code '*'} then {@code 1111}. */
    private static final String MASKED_CARD_NUMBER = "************1111";

    /** Driving account key ({@code XREF-ACCT-ID}). */
    private static final Long ACCT_ID = 9990001111L;

    /** Driving customer key ({@code XREF-CUST-ID}). */
    private static final Long CUST_ID = 77001L;

    /**
     * The sole collaborator, mocked as the {@code CALL 'CBSTM03B'} replacement. Fresh per test
     * (re-initialized by {@link MockitoExtension}), so stubs never leak between tests.
     */
    @Mock
    private StatementFileService statementFileService;

    /** System under test, reconstructed per test against the freshly injected mock. */
    private StatementProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new StatementProcessor(statementFileService);
    }

    // ------------------------------------------------------------------------------------------------
    // Record builders — real domain instances (never mocks) so reference-equality holds and strict
    // stubbing is not tripped by the many getter calls performed while rendering a statement.
    // ------------------------------------------------------------------------------------------------

    /** Builds a driving cross-reference row from its three navigation keys. */
    private static CardXref xref(String cardNum, Long acctId, Long custId) {
        CardXref x = new CardXref();
        x.setXrefCardNum(cardNum);
        x.setXrefAcctId(acctId);
        x.setXrefCustId(custId);
        return x;
    }

    /** Builds an account with an id and a current balance (scale-2 {@link BigDecimal}). */
    private static Account account(Long acctId, String currentBalance) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setAcctCurrBal(new BigDecimal(currentBalance));
        return a;
    }

    /** Builds a fully-populated customer with a representative name and address block. */
    private static Customer customer(Long custId) {
        Customer c = new Customer();
        c.setCustId(custId);
        c.setCustFirstName("JOHN");
        c.setCustMiddleName("Q");
        c.setCustLastName("PUBLIC");
        c.setCustAddrLine1("100 MAIN STREET");
        c.setCustAddrLine2("APT 4B");
        c.setCustAddrLine3("AUSTIN");
        c.setCustAddrStateCd("TX");
        c.setCustAddrCountryCd("USA");
        c.setCustAddrZip("78701");
        c.setCustFicoCreditScore(720);
        return c;
    }

    /** Builds a card master record keyed by the sample PAN. */
    private static Card card(String cardNum) {
        Card c = new Card();
        c.setCardNum(cardNum);
        c.setCardAcctId(ACCT_ID);
        c.setCardEmbossedName("JOHN Q PUBLIC");
        return c;
    }

    /** Builds a statement transaction line (the flattened {@code COSTM01 TRNX-RECORD} projection). */
    private static StatementTransactionDto txn(String transactionId, String description, String amount) {
        return new StatementTransactionDto(
                SAMPLE_CARD_NUMBER,
                transactionId,
                "01",
                "5000",
                "POS",
                description,
                new BigDecimal(amount),
                "123456789",
                "ACME STORE",
                "AUSTIN",
                "75001",
                "2022-07-19-23.12.31.000000",
                "2022-07-20-01.00.00.000000");
    }

    // =================================================================================================
    // Phase 2 — happy-path assembly (CBSTM03A 5000-CREATE-STATEMENT)
    // =================================================================================================

    @Nested
    @DisplayName("Happy-path assembly")
    class HappyPathAssembly {

        @Test
        @DisplayName("resolves entities + transactions into a StatementDocument (order preserved)")
        void returnsAssembledDocument() {
            CardXref xref = xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID);
            Account account = account(ACCT_ID, "1234.56");
            Customer customer = customer(CUST_ID);
            Card card = card(SAMPLE_CARD_NUMBER);
            List<StatementTransactionDto> txns = List.of(
                    txn("0000000000000001", "COFFEE SHOP", "12.34"),
                    txn("0000000000000002", "GROCERY STORE", "56.78"),
                    txn("0000000000000003", "FUEL STATION", "90.12"));

            when(statementFileService.getAccount(ACCT_ID)).thenReturn(Optional.of(account));
            when(statementFileService.getCustomer(CUST_ID)).thenReturn(Optional.of(customer));
            when(statementFileService.getCard(SAMPLE_CARD_NUMBER)).thenReturn(Optional.of(card));
            when(statementFileService.buildStatementLines(SAMPLE_CARD_NUMBER)).thenReturn(txns);

            StatementDocument document = processor.process(xref);

            assertThat(document).isNotNull();
            assertThat(document.cardNumber()).isEqualTo(SAMPLE_CARD_NUMBER);
            assertThat(document.cardXref()).isSameAs(xref);
            assertThat(document.account()).isSameAs(account);
            assertThat(document.customer()).isSameAs(customer);
            assertThat(document.card()).isSameAs(card);
            assertThat(document.transactionLines()).containsExactlyElementsOf(txns);
            assertThat(document.textLines()).isNotEmpty();
            assertThat(document.htmlLines()).isNotEmpty();
        }

        @Test
        @DisplayName("a missing card does NOT suppress the statement (card is optional)")
        void cardMissingDoesNotSuppressStatement() {
            CardXref xref = xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID);
            when(statementFileService.getAccount(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID, "0.00")));
            when(statementFileService.getCustomer(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));
            when(statementFileService.getCard(SAMPLE_CARD_NUMBER)).thenReturn(Optional.empty());
            when(statementFileService.buildStatementLines(SAMPLE_CARD_NUMBER))
                    .thenReturn(List.of(txn("0000000000000001", "COFFEE SHOP", "12.34")));

            StatementDocument document = processor.process(xref);

            assertThat(document).isNotNull();
            assertThat(document.card()).isNull();
            assertThat(document.cardNumber()).isEqualTo(SAMPLE_CARD_NUMBER);
        }

        @Test
        @DisplayName("null transaction lines coalesce to an empty list")
        void nullTransactionLinesCoalesceToEmpty() {
            CardXref xref = xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID);
            when(statementFileService.getAccount(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID, "0.00")));
            when(statementFileService.getCustomer(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));
            when(statementFileService.getCard(SAMPLE_CARD_NUMBER)).thenReturn(Optional.empty());
            when(statementFileService.buildStatementLines(SAMPLE_CARD_NUMBER)).thenReturn(null);

            StatementDocument document = processor.process(xref);

            assertThat(document).isNotNull();
            assertThat(document.transactionLines()).isEmpty();
            // The text/HTML renderings are still produced (banner/header lines) even with no txns.
            assertThat(document.textLines()).isNotEmpty();
            assertThat(document.htmlLines()).isNotEmpty();
        }
    }

    // =================================================================================================
    // Phase 3 — line-width invariants (byte-parity precursor; fixed FD record lengths)
    // =================================================================================================

    @Nested
    @DisplayName("Line-width invariants")
    class LineWidthInvariants {

        /**
         * A customer whose fields far exceed the fixed statement field widths, forcing the processor's
         * defensive {@code bound(...)} to truncate every rendered line to its record length.
         */
        private Customer overWidthCustomer() {
            Customer c = new Customer();
            c.setCustId(CUST_ID);
            c.setCustFirstName("X".repeat(60));
            c.setCustMiddleName("Y".repeat(60));
            c.setCustLastName("Z".repeat(60));
            c.setCustAddrLine1("A".repeat(120));
            c.setCustAddrLine2("B".repeat(120));
            c.setCustAddrLine3("C".repeat(120));
            c.setCustAddrStateCd("TX");
            c.setCustAddrCountryCd("USA");
            c.setCustAddrZip("9".repeat(20));
            c.setCustFicoCreditScore(999);
            return c;
        }

        /** Stubs a fully-resolvable statement whose source data intentionally overflows every field. */
        private void stubOverWidthStatement() {
            when(statementFileService.getAccount(ACCT_ID))
                    .thenReturn(Optional.of(account(ACCT_ID, "9999999999.99")));
            when(statementFileService.getCustomer(CUST_ID)).thenReturn(Optional.of(overWidthCustomer()));
            when(statementFileService.getCard(SAMPLE_CARD_NUMBER))
                    .thenReturn(Optional.of(card(SAMPLE_CARD_NUMBER)));
            when(statementFileService.buildStatementLines(SAMPLE_CARD_NUMBER)).thenReturn(List.of(
                    txn("0000000000000001", "D".repeat(150), "12345.67"),
                    txn("0000000000000002", "E".repeat(150), "-987.65")));
        }

        @Test
        @DisplayName("every plain-text line is <= 80 characters (FD-STMTFILE-REC PIC X(80))")
        void textLinesNeverExceedRecordLength() {
            stubOverWidthStatement();

            StatementDocument document = processor.process(xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID));

            assertThat(document).isNotNull();
            assertThat(document.textLines()).isNotEmpty();
            assertThat(document.textLines()).allSatisfy(line ->
                    assertThat(line.length()).isLessThanOrEqualTo(StatementProcessor.TEXT_RECORD_LENGTH));
        }

        @Test
        @DisplayName("every HTML line is <= 100 characters (FD-HTMLFILE-REC PIC X(100))")
        void htmlLinesNeverExceedRecordLength() {
            stubOverWidthStatement();

            StatementDocument document = processor.process(xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID));

            assertThat(document).isNotNull();
            assertThat(document.htmlLines()).isNotEmpty();
            assertThat(document.htmlLines()).allSatisfy(line ->
                    assertThat(line.length()).isLessThanOrEqualTo(StatementProcessor.HTML_RECORD_LENGTH));
        }

        @Test
        @DisplayName("record-length constants match the COBOL LRECL (80 text / 100 HTML)")
        void recordLengthConstantsMatchCobolLrecl() {
            assertThat(StatementProcessor.TEXT_RECORD_LENGTH).isEqualTo(80);
            assertThat(StatementProcessor.HTML_RECORD_LENGTH).isEqualTo(100);
        }
    }

    // =================================================================================================
    // Phase 4 — filtering: unresolved / missing parent yields null (item filtered from the chunk)
    // =================================================================================================

    @Nested
    @DisplayName("Missing-parent filtering (process returns null → item filtered)")
    class MissingParentFiltering {

        @Test
        @DisplayName("account lookup empty → null (customer present, isolating the account absence)")
        void accountLookupEmptyYieldsNull() {
            // getAccount and getCustomer are BOTH invoked before the null guard, so both stubs are
            // used; a present customer isolates the account absence as the sole cause of the filter.
            when(statementFileService.getAccount(ACCT_ID)).thenReturn(Optional.empty());
            when(statementFileService.getCustomer(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

            assertThat(processor.process(xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID))).isNull();
        }

        @Test
        @DisplayName("customer lookup empty → null (account present, isolating the customer absence)")
        void customerLookupEmptyYieldsNull() {
            when(statementFileService.getAccount(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID, "1.00")));
            when(statementFileService.getCustomer(CUST_ID)).thenReturn(Optional.empty());

            assertThat(processor.process(xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID))).isNull();
        }

        @Test
        @DisplayName("null account id → null without touching the file service")
        void nullAccountIdYieldsNull() {
            assertThat(processor.process(xref(SAMPLE_CARD_NUMBER, null, CUST_ID))).isNull();
            verifyNoInteractions(statementFileService);
        }

        @Test
        @DisplayName("null customer id → null without touching the file service")
        void nullCustomerIdYieldsNull() {
            assertThat(processor.process(xref(SAMPLE_CARD_NUMBER, ACCT_ID, null))).isNull();
            verifyNoInteractions(statementFileService);
        }
    }

    // =================================================================================================
    // Constructor contract (CALL 'CBSTM03B' → constructor-injected bean, AAP §0.4.3)
    // =================================================================================================

    @Nested
    @DisplayName("Constructor contract")
    class ConstructorContract {

        @Test
        @DisplayName("rejects a null StatementFileService")
        void rejectsNullService() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementProcessor(null))
                    .withMessageContaining("statementFileService must not be null");
        }
    }

    // =================================================================================================
    // Retained security guards — stored-XSS escaping (HTML rendition) + PAN masking (DEBUG logs)
    // =================================================================================================

    @Nested
    @DisplayName("Security guards (stored-XSS escaping + PAN masking)")
    class SecurityGuards {

        @Test
        @DisplayName("HTML statement escapes malicious markup in customer and transaction text")
        void escapesMaliciousContentInHtml() {
            CardXref xref = xref(SAMPLE_CARD_NUMBER, ACCT_ID, CUST_ID);
            when(statementFileService.getAccount(ACCT_ID))
                    .thenReturn(Optional.of(account(ACCT_ID, "1234.56")));
            when(statementFileService.getCustomer(CUST_ID)).thenReturn(Optional.of(maliciousCustomer()));
            when(statementFileService.getCard(SAMPLE_CARD_NUMBER)).thenReturn(Optional.empty());
            when(statementFileService.buildStatementLines(SAMPLE_CARD_NUMBER))
                    .thenReturn(List.of(maliciousTransactionLine()));

            StatementDocument document = processor.process(xref);
            assertThat(document).isNotNull();

            String html = String.join("\n", document.htmlLines());
            // No raw attacker markup survives into the rendered HTML: with every '<'/'>' escaped, no
            // active element (a <script> block, an <img onerror> handler, a </script> break-out) forms.
            assertThat(html)
                    .doesNotContain("<script>")
                    .doesNotContain("</script>")
                    .doesNotContain("<img");
            // Every injected metacharacter is emitted in its escaped form instead.
            assertThat(html)
                    .contains("&lt;script&gt;")
                    .contains("&lt;/script&gt;")
                    .contains("&lt;img");
        }

        @Test
        @DisplayName("DEBUG diagnostics mask the PAN (never emit the full card number)")
        void debugDiagnosticsMaskThePan() {
            Logger logger = (Logger) LoggerFactory.getLogger(StatementProcessor.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            Level previousLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
            try {
                // A null account key takes the "unresolved key" DEBUG branch, which logs the card
                // number; the value must arrive masked, never as the full PAN.
                assertThat(processor.process(xref(SAMPLE_CARD_NUMBER, null, null))).isNull();
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
            }

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            assertThat(messages).isNotEmpty();
            assertThat(messages).noneMatch(message -> message.contains(SAMPLE_CARD_NUMBER));
            assertThat(messages).anyMatch(message -> message.contains(MASKED_CARD_NUMBER));
        }

        /**
         * A customer whose name and address carry HTML-injection payloads. The name payload contains
         * no embedded space, so the {@code STRING ... DELIMITED BY ' '} name assembly keeps it intact
         * and the escaping guarantee is exercised end to end.
         */
        private Customer maliciousCustomer() {
            Customer c = new Customer();
            c.setCustId(CUST_ID);
            c.setCustFirstName("<script>alert('xss')</script>");
            c.setCustLastName("PUBLIC");
            c.setCustAddrLine1("<img src=x onerror=alert(1)>");
            c.setCustAddrLine2("Suite <b>100</b>");
            c.setCustAddrLine3("AUSTIN");
            c.setCustAddrStateCd("TX");
            c.setCustAddrCountryCd("USA");
            c.setCustAddrZip("75001");
            c.setCustFicoCreditScore(720);
            return c;
        }

        /** A statement line whose description carries a script-injection payload. */
        private StatementTransactionDto maliciousTransactionLine() {
            return txn("0000000000000001", "<script>alert('t')</script>", "42.00");
        }
    }
}
