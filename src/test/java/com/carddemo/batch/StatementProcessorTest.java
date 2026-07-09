package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Security-focused unit tests for {@link StatementProcessor}, guarding the two data-protection
 * fixes raised by the CP3 code review:
 *
 * <ul>
 *   <li><strong>Stored XSS (CRITICAL).</strong> The HTML statement embeds customer- and
 *       transaction-supplied text (name, address lines, transaction id and description). Every such
 *       value must be HTML-escaped so that markup in the source data cannot inject active content
 *       into the rendered statement.</li>
 *   <li><strong>PAN in logs (MAJOR).</strong> The diagnostic {@code DEBUG} lines emitted while a
 *       statement is assembled must mask the card number to its last four digits; a full Primary
 *       Account Number (PAN) must never reach a log line.</li>
 * </ul>
 *
 * <p>The tests drive the real {@link StatementProcessor#process(CardXref)} path with a mocked
 * {@link StatementFileService} collaborator (the CALL&rarr;bean seam), assert on the rendered
 * {@link StatementDocument#htmlLines()} for the escaping guarantee, and attach a Logback
 * {@link ListAppender} to the processor's logger for the masking guarantee. No database, Spring
 * context or AWS resource is required, keeping the committed suite deterministic and fast.</p>
 */
class StatementProcessorTest {

    /** A 16-digit sample PAN; its last four digits are {@code 1111}. */
    private static final String SAMPLE_CARD_NUMBER = "4111111111111111";

    /** The masked rendering the fixes must produce: twelve asterisks then the last four digits. */
    private static final String MASKED_CARD_NUMBER = "************1111";

    private final StatementFileService statementFileService = mock(StatementFileService.class);
    private final StatementProcessor processor = new StatementProcessor(statementFileService);

    @Test
    @DisplayName("HTML statement escapes malicious markup in customer name, address and transaction text")
    void escapesMaliciousCustomerAndTransactionContentInHtml() {
        final Account account = sampleAccount();
        final Customer customer = maliciousCustomer();
        final CardXref xref = sampleXref();

        when(statementFileService.getAccount(account.getAcctId())).thenReturn(Optional.of(account));
        when(statementFileService.getCustomer(customer.getCustId())).thenReturn(Optional.of(customer));
        when(statementFileService.getCard(anyString())).thenReturn(Optional.empty());
        when(statementFileService.buildStatementLines(SAMPLE_CARD_NUMBER))
                .thenReturn(List.of(maliciousTransactionLine()));

        final StatementDocument document = processor.process(xref);
        assertThat(document).isNotNull();

        final String html = String.join("\n", document.htmlLines());

        // No raw attacker markup survives into the rendered HTML: with every '<' and '>' escaped,
        // no active element (a <script> block, an <img onerror> handler, a </script> break-out) can
        // form. The residual literal text "onerror=alert(1)" is intentionally left inert inside the
        // escaped "&lt;img ... &gt;" span and carries no execution risk.
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
        final Logger logger = (Logger) LoggerFactory.getLogger(StatementProcessor.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        final Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            // An XREF whose account key is null takes the "unresolved key" DEBUG branch, which logs
            // the card number; the value must arrive masked.
            final CardXref unresolved = new CardXref();
            unresolved.setXrefCardNum(SAMPLE_CARD_NUMBER);
            unresolved.setXrefAcctId(null);
            unresolved.setXrefCustId(null);

            final StatementDocument document = processor.process(unresolved);
            assertThat(document).isNull();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        final List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(message -> message.contains(SAMPLE_CARD_NUMBER));
        assertThat(messages).anyMatch(message -> message.contains(MASKED_CARD_NUMBER));
    }

    // ------------------------------------------------------------------------------------------------
    // Sample-record helpers.
    // ------------------------------------------------------------------------------------------------

    private static Account sampleAccount() {
        final Account account = new Account();
        account.setAcctId(9990001111L);
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        return account;
    }

    private static CardXref sampleXref() {
        final CardXref xref = new CardXref();
        xref.setXrefCardNum(SAMPLE_CARD_NUMBER);
        xref.setXrefAcctId(9990001111L);
        xref.setXrefCustId(77001L);
        return xref;
    }

    /** A customer whose name and address carry HTML-injection payloads (no embedded spaces in the
     *  name token, so the {@code DELIMITED BY ' '} name assembly keeps the payload intact). */
    private static Customer maliciousCustomer() {
        final Customer customer = new Customer();
        customer.setCustId(77001L);
        customer.setCustFirstName("<script>alert('xss')</script>");
        customer.setCustLastName("PUBLIC");
        customer.setCustAddrLine1("<img src=x onerror=alert(1)>");
        customer.setCustAddrLine2("Suite <b>100</b>");
        customer.setCustAddrLine3("AUSTIN");
        customer.setCustAddrStateCd("TX");
        customer.setCustAddrZip("75001");
        customer.setCustFicoCreditScore(720);
        return customer;
    }

    /** A statement line whose description carries a script-injection payload. */
    private static StatementTransactionDto maliciousTransactionLine() {
        return new StatementTransactionDto(
                SAMPLE_CARD_NUMBER,
                "0000000000000001",
                "01",
                "5000",
                "POS",
                "<script>alert('t')</script>",
                new BigDecimal("42.00"),
                "123456789",
                "ACME",
                "AUSTIN",
                "75001",
                "2022-07-19-23.12.31.000000",
                "2022-07-20-01.00.00.000000");
    }
}
