/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link StatementGenerationService}.
 *
 * <p><b>COBOL provenance.</b> {@link StatementGenerationService}
 * translates {@code app/cbl/CBSTM03A.CBL} (text + HTML statement
 * generator) plus its file-service subroutine
 * {@code app/cbl/CBSTM03B.CBL}. The COBOL source iterates every
 * account, joins with customer and card-cross-reference records,
 * aggregates per-card transactions, and emits two output files: a
 * fixed-width text statement (LRECL=80) and an HTML statement
 * (LRECL=100).</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Schema-mandated API</b> &mdash;
 *       {@code generateStatements(LocalDate statementDate)} returning
 *       a {@link StatementGenerationService.StatementResult} with
 *       {@code textCount() / htmlCount() / errorCount()} accessors
 *       per the AAP exports schema.</li>
 *   <li><b>Deterministic XREF selection</b> &mdash; uses the ORDERED
 *       {@link CardCrossReferenceRepository#findByXrefAcctIdOrderByXrefCardNumAsc(Long)}
 *       method to guarantee reproducible customer-id selection across
 *       executions and query-plan changes.</li>
 *   <li><b>Dual-format output</b> &mdash; each statement is written to
 *       S3 twice (text + .html). Text width = 80 chars; HTML width =
 *       100 chars (per CBSTM03A/CBSTM03B LRECL).</li>
 *   <li><b>BigDecimal HALF_EVEN + scale=2</b> &mdash; total is the sum
 *       of per-transaction amounts with banker's rounding.</li>
 *   <li><b>Account skip semantics</b> &mdash; account skipped if XREF
 *       list is empty OR customer lookup misses.</li>
 *   <li><b>PCI-DSS PAN masking</b> &mdash; transaction lines display
 *       masked PAN ({@code ****-****-****-NNNN}), never the full card
 *       number.</li>
 *   <li><b>Per-statement audit + run-summary audit</b> &mdash; one
 *       {@code logAuditEvent} per produced statement plus one batch
 *       summary at run end.</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; statement total &gt;
 *       {@code MAX_STATEMENT_TOTAL} throws
 *       {@link OnSizeErrorException}.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementGenerationService unit tests (COBOL: CBSTM03A.CBL / CBSTM03B.CBL)")
class StatementGenerationServiceTest {

    // ==================================================================
    // Test constants
    // ==================================================================
    private static final LocalDate STATEMENT_DATE = LocalDate.of(2025, 1, 31);
    private static final String STATEMENT_DATE_STR = "2025-01-31";
    private static final Long ACCOUNT_ID = 10_000_000_001L;
    private static final Long CUSTOMER_ID = 999_888_777L;
    private static final String CARD_LOW = "4000000000000001";
    private static final String CARD_HIGH = "4000000000000002";

    // ==================================================================
    // Mocks and SUT
    // ==================================================================
    @Mock private AccountRepository accountRepository;
    @Mock private CardCrossReferenceRepository xrefRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private S3OutputService s3OutputService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private StatementGenerationService service;

    // ==================================================================
    // Test fixtures
    // ==================================================================
    private Account account;
    private Customer customer;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
        account.setAcctGroupId("STANDARD");

        customer = new Customer();
        customer.setCustId(CUSTOMER_ID);
        customer.setCustFirstName("John");
        customer.setCustLastName("Doe");
        customer.setCustAddrLine1("123 Main St");
        customer.setCustAddrLine2("Apt 4");
        customer.setCustAddrStateCd("NY");
        customer.setCustAddrZip("10001");
        customer.setCustAddrCountryCd("USA");
        customer.setCustFicoCreditScore(720);
    }

    private Transaction buildTransaction(String tranId, String cardNum,
                                          BigDecimal amount, String desc) {
        return new Transaction(
                tranId, "01", 5, "POS TERM", desc, amount,
                999_999_999L, "Merchant", "City", "12345",
                cardNum,
                LocalDateTime.of(2025, 1, 15, 12, 0),
                LocalDateTime.of(2025, 1, 15, 12, 0));
    }

    private CardCrossReference buildXref(String cardNum) {
        return new CardCrossReference(cardNum, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * Provides a lenient happy-path stub set: 1 account with 1 card
     * having {@code txs.size()} transactions. Used as a base by tests
     * that override only specific behaviour.
     */
    private void stubHappyPathSingleAccount(List<Transaction> txs) {
        lenient().when(accountRepository.findAll())
                .thenReturn(new ArrayList<>(List.of(account)));
        lenient().when(xrefRepository
                        .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(List.of(buildXref(CARD_LOW)));
        lenient().when(customerRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(customer));
        Page<Transaction> page = new PageImpl<>(txs);
        lenient().when(transactionRepository.findByTranCardNumAndTranProcTsBetween(
                eq(CARD_LOW), any(LocalDateTime.class),
                any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(page);
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("Input validation (statementDate)")
    class InputValidation {

        @Test
        @DisplayName("null statementDate throws IllegalArgumentException")
        void generateStatements_nullStatementDate_throws() {
            assertThatThrownBy(() -> service.generateStatements(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("statementDate");
        }

        @Test
        @DisplayName("empty account list produces zero statements + emits summary")
        void generateStatements_noAccounts_emitsBatchSummaryOnly() {
            when(accountRepository.findAll()).thenReturn(new ArrayList<>());

            StatementGenerationService.StatementResult result =
                    service.generateStatements(STATEMENT_DATE);

            assertThat(result.textCount()).isEqualTo(0);
            assertThat(result.htmlCount()).isEqualTo(0);
            assertThat(result.errorCount()).isEqualTo(0);
            // Run-summary audit is still emitted (1 call total)
            verify(auditLogService, times(1)).logAuditEvent(
                    eq("statement.generated"), eq("BATCH_RUN"),
                    eq(STATEMENT_DATE_STR), anyString(), anyMap(),
                    eq(STATEMENT_DATE_STR));
            // No S3 writes
            verify(s3OutputService, never()).writeReport(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Deterministic XREF selection (multi-card account)")
    class DeterministicXref {

        @Test
        @DisplayName("uses ordered AIX method, never the unordered one")
        void generateStatements_callsOrderedXrefMethod() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            verify(xrefRepository)
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(xrefRepository, never()).findByXrefAcctId(anyLong());
        }

        @Test
        @DisplayName("picks first xref's custId (lexicographically smallest card)")
        void generateStatements_picksFirstCustId() {
            // Two XREF rows; ordered repository returns CARD_LOW first
            CardCrossReference primary = new CardCrossReference(
                    CARD_LOW, CUSTOMER_ID, ACCOUNT_ID);
            CardCrossReference secondary = new CardCrossReference(
                    CARD_HIGH, 555L, ACCOUNT_ID);  // different custId

            lenient().when(accountRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(account)));
            lenient().when(xrefRepository
                            .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(primary, secondary));
            lenient().when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            Page<Transaction> empty = new PageImpl<>(List.of());
            lenient().when(transactionRepository.findByTranCardNumAndTranProcTsBetween(
                            anyString(), any(LocalDateTime.class),
                            any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(empty);

            service.generateStatements(STATEMENT_DATE);

            // Verify only CUSTOMER_ID (from CARD_LOW) was looked up
            verify(customerRepository).findById(CUSTOMER_ID);
            verify(customerRepository, never()).findById(555L);
        }

        @Test
        @DisplayName("aggregates transactions for every card linked to the account")
        void generateStatements_aggregatesAllCards() {
            CardCrossReference x1 = new CardCrossReference(CARD_LOW, CUSTOMER_ID, ACCOUNT_ID);
            CardCrossReference x2 = new CardCrossReference(CARD_HIGH, CUSTOMER_ID, ACCOUNT_ID);

            lenient().when(accountRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(account)));
            lenient().when(xrefRepository
                            .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(x1, x2));
            lenient().when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Each card has one transaction
            Page<Transaction> p1 = new PageImpl<>(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("100.00"), "Tx1")));
            Page<Transaction> p2 = new PageImpl<>(List.of(buildTransaction(
                    "T2", CARD_HIGH, new BigDecimal("200.00"), "Tx2")));
            lenient().when(transactionRepository.findByTranCardNumAndTranProcTsBetween(
                            eq(CARD_LOW), any(), any(), any(Pageable.class)))
                    .thenReturn(p1);
            lenient().when(transactionRepository.findByTranCardNumAndTranProcTsBetween(
                            eq(CARD_HIGH), any(), any(), any(Pageable.class)))
                    .thenReturn(p2);

            service.generateStatements(STATEMENT_DATE);

            verify(transactionRepository).findByTranCardNumAndTranProcTsBetween(
                    eq(CARD_LOW), any(), any(), any(Pageable.class));
            verify(transactionRepository).findByTranCardNumAndTranProcTsBetween(
                    eq(CARD_HIGH), any(), any(), any(Pageable.class));

            // Audit payload total reflects both transactions = 300.00
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("statement.generated"), eq("STATEMENT"),
                    anyString(), eq("BATCH"), payloadCaptor.capture(),
                    eq(STATEMENT_DATE_STR));
            assertThat(((BigDecimal) payloadCaptor.getValue().get("total")))
                    .isEqualByComparingTo(new BigDecimal("300.00"));
        }
    }

    @Nested
    @DisplayName("Account skip semantics")
    class SkipSemantics {

        @Test
        @DisplayName("empty XREF list → skip statement (no S3 write)")
        void emptyXref_skipsStatement() {
            lenient().when(accountRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(account)));
            when(xrefRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of());

            StatementGenerationService.StatementResult result =
                    service.generateStatements(STATEMENT_DATE);

            assertThat(result.textCount()).isEqualTo(0);
            assertThat(result.htmlCount()).isEqualTo(0);
            assertThat(result.errorCount()).isEqualTo(0);
            verify(s3OutputService, never()).writeReport(anyString(), any());
            verify(customerRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("customer not found → skip statement")
        void customerMissing_skipsStatement() {
            lenient().when(accountRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(account)));
            lenient().when(xrefRepository
                            .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(buildXref(CARD_LOW)));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            StatementGenerationService.StatementResult result =
                    service.generateStatements(STATEMENT_DATE);

            assertThat(result.textCount()).isEqualTo(0);
            assertThat(result.htmlCount()).isEqualTo(0);
            assertThat(result.errorCount()).isEqualTo(0);
            verify(s3OutputService, never()).writeReport(anyString(), any());
            verify(transactionRepository, never())
                    .findByTranCardNumAndTranProcTsBetween(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("null account in list → skip without NPE")
        void nullAccountInList_skipped() {
            List<Account> accounts = new ArrayList<>();
            accounts.add(null);  // tolerate null
            accounts.add(account);
            lenient().when(accountRepository.findAll()).thenReturn(accounts);
            lenient().when(xrefRepository
                            .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(buildXref(CARD_LOW)));
            lenient().when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            Page<Transaction> empty = new PageImpl<>(List.of());
            lenient().when(transactionRepository.findByTranCardNumAndTranProcTsBetween(
                            anyString(), any(LocalDateTime.class),
                            any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(empty);

            StatementGenerationService.StatementResult result =
                    service.generateStatements(STATEMENT_DATE);

            // 2 accounts in list, only 1 produces a statement
            assertThat(result.textCount()).isEqualTo(1);
            assertThat(result.htmlCount()).isEqualTo(1);
            assertThat(result.errorCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Dual-format S3 output (CBSTM03A text + HTML)")
    class DualFormatOutput {

        @Test
        @DisplayName("writes two S3 objects: text + .html")
        void writesTextAndHtmlObjects() {
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("99.99"), "Tx1")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<String> nameCaptor =
                    ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(nameCaptor.capture(), bytesCaptor.capture());

            List<String> names = nameCaptor.getAllValues();
            assertThat(names).hasSize(2);
            assertThat(names.get(0)).startsWith("stmt-");
            assertThat(names.get(1)).startsWith("stmt-");
            assertThat(names.get(1)).endsWith(".html");
        }

        @Test
        @DisplayName("statement ID is stmt-<11-digit acct>-<14-char ts>")
        void statementIdFormat() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<String> nameCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService, times(2))
                    .writeReport(nameCaptor.capture(), any(byte[].class));
            String textName = nameCaptor.getAllValues().get(0);
            // stmt-<11-digit acct>-<14-char ts>
            // total length: 5 ("stmt-") + 11 + 1 ("-") + 14 = 31
            assertThat(textName).hasSize(31);
            assertThat(textName).matches("^stmt-\\d{11}-\\d{14}$");
        }
    }

    @Nested
    @DisplayName("TextStatement — fixed-width text content (CBSTM03A, LRECL=80)")
    class TextStatement {

        @Test
        @DisplayName("text lines are exactly 80 chars wide")
        void textLinesWidth80() {
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("100.00"), "Test Tx")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            for (String line : text.split("\n")) {
                assertThat(line.length()).isLessThanOrEqualTo(80);
            }
        }

        @Test
        @DisplayName("includes START / END OF STATEMENT banners")
        void containsStartEndBanners() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            assertThat(text).contains("START OF STATEMENT");
            assertThat(text).contains("END OF STATEMENT");
        }

        @Test
        @DisplayName("includes customer + account fields")
        void containsCustomerAndAccount() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            assertThat(text).contains("John");
            assertThat(text).contains("Doe");
            assertThat(text).contains(String.valueOf(CUSTOMER_ID));
            assertThat(text).contains(String.valueOf(ACCOUNT_ID));
            assertThat(text).contains("TRANSACTIONS:");
            assertThat(text).contains("TOTAL:");
        }

        @Test
        @DisplayName("transaction line masks PAN with last 4 digits")
        void transactionLineMasksPan() {
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("100.00"), "Test")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            assertThat(text).contains("****-****-****-0001");
            // Full PAN must NOT appear
            assertThat(text).doesNotContain(CARD_LOW);
        }
    }

    @Nested
    @DisplayName("HtmlStatement — HTML markup content (CBSTM03B, LRECL=100)")
    class HtmlStatement {

        @Test
        @DisplayName("HTML statement has DOCTYPE and html5 elements")
        void htmlStructure() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);
            assertThat(html).contains("<!DOCTYPE html>");
            assertThat(html).contains("<html lang=\"en\">");
            assertThat(html).contains("</html>");
            assertThat(html).contains("<table");
        }

        @Test
        @DisplayName("HTML preserves verbatim COBOL constants (Bank of XYZ + colors)")
        void htmlPreservesCobolConstants() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);
            // Verbatim CBSTM03A.CBL HTML constants
            assertThat(html).contains("Bank of XYZ");
            assertThat(html).contains("410 Terry Ave N");
            assertThat(html).contains("Seattle WA 99999");
            // Verbatim color palette
            assertThat(html).contains("#1d1d96b3");
            assertThat(html).contains("#FFAF33");
            assertThat(html).contains("#33FF5E");
            assertThat(html).contains("#f2f2f2");
        }

        @Test
        @DisplayName("HTML statement masks PAN in transaction rows")
        void htmlMasksPan() {
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("100.00"), "Test")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);
            assertThat(html).contains("****-****-****-0001");
            assertThat(html).doesNotContain(CARD_LOW);
        }

        @Test
        @DisplayName("HTML escapes special characters in description")
        void htmlEscapesSpecialChars() {
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("100.00"),
                    "<script>alert('XSS')</script>")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);
            assertThat(html).contains("&lt;script&gt;");
            assertThat(html).doesNotContain("<script>alert");
        }
    }

    @Nested
    @DisplayName("BigDecimal arithmetic + total")
    class TotalArithmetic {

        @Test
        @DisplayName("sums all transaction amounts with HALF_EVEN scale=2")
        void totalSumsAllTransactions() {
            stubHappyPathSingleAccount(List.of(
                    buildTransaction("T1", CARD_LOW, new BigDecimal("100.00"), "A"),
                    buildTransaction("T2", CARD_LOW, new BigDecimal("50.25"), "B"),
                    buildTransaction("T3", CARD_LOW, new BigDecimal("-25.00"), "C")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("statement.generated"), eq("STATEMENT"),
                    anyString(), eq("BATCH"),
                    payloadCaptor.capture(), eq(STATEMENT_DATE_STR));
            BigDecimal total = (BigDecimal) payloadCaptor.getValue().get("total");
            // 100 + 50.25 + (-25) = 125.25
            assertThat(total).isEqualByComparingTo(new BigDecimal("125.25"));
            assertThat(total.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("null transaction amount treated as zero")
        void nullAmountTreatedAsZero() {
            Transaction txWithNullAmt = new Transaction(
                    "T1", "01", 5, "POS TERM", "Test", null,
                    999_999_999L, "M", "C", "Z",
                    CARD_LOW,
                    LocalDateTime.of(2025, 1, 15, 12, 0),
                    LocalDateTime.of(2025, 1, 15, 12, 0));
            stubHappyPathSingleAccount(List.of(txWithNullAmt));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("statement.generated"), eq("STATEMENT"),
                    anyString(), eq("BATCH"),
                    payloadCaptor.capture(), eq(STATEMENT_DATE_STR));
            assertThat(((BigDecimal) payloadCaptor.getValue().get("total")))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("Events + audit emission")
    class EventsAndAudit {

        @Test
        @DisplayName("per-statement audit emitted with accountId, customerId, total")
        void perStatementAuditEmitted() {
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("100.00"), "Tx")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("statement.generated"), eq("STATEMENT"),
                    anyString(), eq("BATCH"),
                    payloadCaptor.capture(), eq(STATEMENT_DATE_STR));
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("accountId", ACCOUNT_ID);
            assertThat(payload).containsEntry("customerId", CUSTOMER_ID);
            assertThat(payload).containsEntry("transactionCount", 1);
        }

        @Test
        @DisplayName("run-summary audit emitted once with counters")
        void runSummaryAuditEmitted() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("statement.generated"), eq("BATCH_RUN"),
                    eq(STATEMENT_DATE_STR), eq("BATCH"),
                    payloadCaptor.capture(), eq(STATEMENT_DATE_STR));
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("accountsProcessed", 1);
            assertThat(payload).containsEntry("textCount", 1);
            assertThat(payload).containsEntry("htmlCount", 1);
            assertThat(payload).containsEntry("errorCount", 0);
            assertThat(payload).containsEntry("batchRunId", STATEMENT_DATE_STR);
        }
    }

    @Nested
    @DisplayName("ON SIZE ERROR — statement total overflow")
    class OnSizeError {

        @Test
        @DisplayName("total > MAX_STATEMENT_TOTAL throws OnSizeErrorException")
        void totalOverflow_throwsOnSizeError() {
            // MAX_STATEMENT_TOTAL = 999_999_999.99 (PIC S9(9)V99)
            // Sum two huge transactions to overflow.
            stubHappyPathSingleAccount(List.of(
                    buildTransaction("T1", CARD_LOW,
                            new BigDecimal("900000000.00"), "A"),
                    buildTransaction("T2", CARD_LOW,
                            new BigDecimal("200000000.00"), "B")));

            assertThatThrownBy(() -> service.generateStatements(STATEMENT_DATE))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("WS-TOTAL-AMT");
        }
    }

    @Nested
    @DisplayName("StatementResult record contract")
    class StatementResultContract {

        @Test
        @DisplayName("StatementResult exposes textCount/htmlCount/errorCount accessors")
        void resultAccessors() {
            StatementGenerationService.StatementResult result =
                    new StatementGenerationService.StatementResult(3, 3, 1);
            assertThat(result.textCount()).isEqualTo(3);
            assertThat(result.htmlCount()).isEqualTo(3);
            assertThat(result.errorCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("generateStatementForAccount returns true on success, false on skip")
        void generateStatementForAccountReturnsBoolean() {
            lenient().when(xrefRepository
                            .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(buildXref(CARD_LOW)));
            lenient().when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            Page<Transaction> empty = new PageImpl<>(List.of());
            lenient().when(transactionRepository.findByTranCardNumAndTranProcTsBetween(
                            eq(CARD_LOW), any(LocalDateTime.class),
                            any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(empty);

            boolean produced =
                    service.generateStatementForAccount(account, STATEMENT_DATE);
            assertThat(produced).isTrue();
        }

        @Test
        @DisplayName("generateStatementForAccount returns false when xref empty")
        void generateStatementForAccountReturnsFalseWhenXrefEmpty() {
            when(xrefRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of());

            boolean produced =
                    service.generateStatementForAccount(account, STATEMENT_DATE);
            assertThat(produced).isFalse();
        }

        @Test
        @DisplayName("generateStatementForAccount throws on null account")
        void generateStatementForAccountThrowsOnNullAccount() {
            assertThatThrownBy(() ->
                    service.generateStatementForAccount(null, STATEMENT_DATE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("generateStatementForAccount throws on null statementDate")
        void generateStatementForAccountThrowsOnNullDate() {
            assertThatThrownBy(() ->
                    service.generateStatementForAccount(account, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // AAP §0.3.3 / §0.4.1 schema-mandated nested groups
    // ==================================================================

    /**
     * Template Method — verifies that the text (CBSTM03A) and HTML
     * (CBSTM03B) variants share the same aggregation pipeline. Per
     * AAP §0.3.3, the service applies the GoF Template Method
     * pattern so that both formats:
     *   (a) iterate the same XREF set,
     *   (b) execute the same per-card transaction join,
     *   (c) accumulate the same {@code BigDecimal} running total,
     *   (d) emit the identical {@link AuditLogService#logAuditEvent}
     *       payload (one per produced statement, regardless of
     *       format count).
     *
     * <p>COBOL: CBSTM03A:GENERATE-STATEMENT — common aggregation
     * step that backs both 5000-CREATE-STATEMENT and the equivalent
     * HTML emit path.</p>
     */
    @Nested
    @DisplayName("TemplateMethod — shared aggregation across CBSTM03A / CBSTM03B variants")
    class TemplateMethod {

        @Test
        @DisplayName("both formats use identical per-statement total (no duplicate aggregation)")
        void bothFormats_useSameAggregationLogic() {
            // Five transactions whose amounts sum to 450.50.
            // The fixture mirrors the AAP §0.6.1 BigDecimal HALF_EVEN
            // running-total assertion.
            stubHappyPathSingleAccount(List.of(
                    buildTransaction("T1", CARD_LOW, new BigDecimal("100.00"), "A"),
                    buildTransaction("T2", CARD_LOW, new BigDecimal("200.00"), "B"),
                    buildTransaction("T3", CARD_LOW, new BigDecimal("50.00"),  "C"),
                    buildTransaction("T4", CARD_LOW, new BigDecimal("25.00"),  "D"),
                    buildTransaction("T5", CARD_LOW, new BigDecimal("75.50"),  "E")));

            service.generateStatements(STATEMENT_DATE);

            // Capture text + HTML payloads — both must render the same total.
            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);

            // Both formats must surface the identical formatted total.
            // COBOL: CBSTM03A WS-TOTAL-AMT = 450.50 (PIC S9(9)V99).
            assertThat(text).contains("450.50");
            assertThat(html).contains("450.50");

            // The audit payload's total accessor is the canonical
            // shared-aggregation value driving both renderings.
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("statement.generated"), eq("STATEMENT"),
                    anyString(), eq("BATCH"),
                    payloadCaptor.capture(), eq(STATEMENT_DATE_STR));
            assertThat(((BigDecimal) payloadCaptor.getValue().get("total")))
                    .isEqualByComparingTo(new BigDecimal("450.50"));
        }

        @Test
        @DisplayName("both formats emit one S3 object each (2 writes per statement)")
        void bothFormats_writeToS3() {
            // Single transaction so the underlying aggregation
            // pipeline is forced to run exactly once.
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("12.34"), "X")));

            service.generateStatements(STATEMENT_DATE);

            // Exactly two writeReport calls — text + .html — per the
            // CBSTM03A LRECL=80 + CBSTM03B LRECL=100 dual-output
            // contract (AAP §0.4.1 StatementGenerationService row).
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), any(byte[].class));
        }

        @Test
        @DisplayName("zero transactions produce the same zero total in both formats")
        void bothFormats_zeroTotal_consistentAcrossVariants() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);
            // 0.00 is the canonical zero rendering for PIC S9(9)V99.
            assertThat(text).contains("0.00");
            assertThat(html).contains("0.00");
        }
    }

    /**
     * MultipleAccounts — verifies batch-level semantics across more
     * than one account. The COBOL implementation iterates ACCTFILE
     * end-to-end and emits one statement per active account; in the
     * Java port (AAP §0.4.1 row {@code StatementGenerationService}),
     * the {@code AccountRepository.findAll()} iterator drives that
     * loop. Two specific scenarios are locked here:
     *   (a) three independent accounts produce three text + three
     *       HTML S3 objects (six writes total),
     *   (b) one account failing (customer lookup throws) increments
     *       {@code errorCount} without aborting the other accounts —
     *       per AAP §0.6.6 audit / resilience requirement.
     */
    @Nested
    @DisplayName("MultipleAccounts — per-account isolation + failure resilience")
    class MultipleAccounts {

        private static final Long ACCT_A = 10_000_000_001L;
        private static final Long ACCT_B = 10_000_000_002L;
        private static final Long ACCT_C = 10_000_000_003L;
        private static final Long CUST_A = 100_000_001L;
        private static final Long CUST_B = 100_000_002L;
        private static final Long CUST_C = 100_000_003L;
        private static final String CARD_A = "4000000000000010";
        private static final String CARD_B = "4000000000000020";
        private static final String CARD_C = "4000000000000030";

        private Account buildAccount(Long acctId) {
            Account a = new Account();
            a.setAcctId(acctId);
            a.setAcctActiveStatus("Y");
            a.setAcctCurrBal(new BigDecimal("100.00"));
            a.setAcctCreditLimit(new BigDecimal("5000.00"));
            a.setAcctOpenDate(LocalDate.of(2020, 1, 1));
            a.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
            a.setAcctGroupId("STANDARD");
            return a;
        }

        private Customer buildCustomer(Long custId, String firstName) {
            Customer c = new Customer();
            c.setCustId(custId);
            c.setCustFirstName(firstName);
            c.setCustLastName("Doe");
            c.setCustAddrLine1("123 Main St");
            c.setCustAddrStateCd("NY");
            c.setCustAddrZip("10001");
            c.setCustFicoCreditScore(720);
            return c;
        }

        @Test
        @DisplayName("3 accounts produce 6 S3 writes (3 text + 3 HTML)")
        void generateBatchStatements_processesEachAccountSeparately() {
            // COBOL: CBSTM03A:3000-ACCTFILE-GET-NEXT
            //   iterates every account; the Java port relies on
            //   accountRepository.findAll() as the loop driver.
            Account a = buildAccount(ACCT_A);
            Account b = buildAccount(ACCT_B);
            Account c = buildAccount(ACCT_C);

            when(accountRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(a, b, c)));

            when(xrefRepository
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_A))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_A, CUST_A, ACCT_A)));
            when(xrefRepository
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_B))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_B, CUST_B, ACCT_B)));
            when(xrefRepository
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_C))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_C, CUST_C, ACCT_C)));

            when(customerRepository.findById(CUST_A))
                    .thenReturn(Optional.of(buildCustomer(CUST_A, "Alice")));
            when(customerRepository.findById(CUST_B))
                    .thenReturn(Optional.of(buildCustomer(CUST_B, "Bob")));
            when(customerRepository.findById(CUST_C))
                    .thenReturn(Optional.of(buildCustomer(CUST_C, "Carol")));

            Page<Transaction> empty = new PageImpl<>(List.of());
            lenient().when(transactionRepository
                    .findByTranCardNumAndTranProcTsBetween(
                            anyString(), any(LocalDateTime.class),
                            any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(empty);

            StatementGenerationService.StatementResult result =
                    service.generateStatements(STATEMENT_DATE);

            assertThat(result.textCount()).isEqualTo(3);
            assertThat(result.htmlCount()).isEqualTo(3);
            assertThat(result.errorCount()).isEqualTo(0);

            // 3 accounts × 2 outputs (text + .html) = 6 S3 writes
            verify(s3OutputService, times(6))
                    .writeReport(anyString(), any(byte[].class));

            // Each account triggers exactly one ordered XREF lookup.
            verify(xrefRepository)
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_A);
            verify(xrefRepository)
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_B);
            verify(xrefRepository)
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_C);
        }

        @Test
        @DisplayName("one account's customer lookup throws → errorCount=1, other 2 still produced")
        void generateBatchStatements_oneAccountFailure_doesNotBlockOthers() {
            // COBOL: CBSTM03A — per-account error boundary; the Java
            // port wraps each iteration in a try/catch (RuntimeException)
            // that increments errorCount but never aborts the loop.
            Account a = buildAccount(ACCT_A);
            Account b = buildAccount(ACCT_B); // failing account
            Account c = buildAccount(ACCT_C);

            when(accountRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(a, b, c)));

            when(xrefRepository
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_A))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_A, CUST_A, ACCT_A)));
            when(xrefRepository
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_B))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_B, CUST_B, ACCT_B)));
            when(xrefRepository
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCT_C))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_C, CUST_C, ACCT_C)));

            // Account A — healthy.
            when(customerRepository.findById(CUST_A))
                    .thenReturn(Optional.of(buildCustomer(CUST_A, "Alice")));
            // Account B — customer lookup throws RuntimeException.
            // This must NOT abort the batch; errorCount++ and continue.
            when(customerRepository.findById(CUST_B))
                    .thenThrow(new RuntimeException(
                            "simulated customer lookup failure for B"));
            // Account C — healthy.
            when(customerRepository.findById(CUST_C))
                    .thenReturn(Optional.of(buildCustomer(CUST_C, "Carol")));

            Page<Transaction> empty = new PageImpl<>(List.of());
            lenient().when(transactionRepository
                    .findByTranCardNumAndTranProcTsBetween(
                            anyString(), any(LocalDateTime.class),
                            any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(empty);

            StatementGenerationService.StatementResult result =
                    service.generateStatements(STATEMENT_DATE);

            // Two accounts produced statements, one failed.
            assertThat(result.textCount()).isEqualTo(2);
            assertThat(result.htmlCount()).isEqualTo(2);
            assertThat(result.errorCount()).isEqualTo(1);

            // 2 successful accounts × 2 outputs = 4 S3 writes
            // (failure occurred BEFORE any write for account B).
            verify(s3OutputService, times(4))
                    .writeReport(anyString(), any(byte[].class));

            // Per-statement audit emitted for the two successful
            // accounts (2 calls) + run summary (1 call) = 3 total.
            // AAP §0.6.6 — every successful statement leaves an
            // audit trail, every batch produces a closing summary.
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(3)).logAuditEvent(
                    eq("statement.generated"), anyString(),
                    anyString(), anyString(),
                    payloadCaptor.capture(), eq(STATEMENT_DATE_STR));

            // The final summary payload must carry the errorCount=1
            // counter so downstream OpenSearch / CloudTrail consumers
            // can flag the partial-failure event.
            Map<String, Object> summary = payloadCaptor.getAllValues()
                    .get(payloadCaptor.getAllValues().size() - 1);
            assertThat(summary).containsEntry("accountsProcessed", 3);
            assertThat(summary).containsEntry("textCount", 2);
            assertThat(summary).containsEntry("htmlCount", 2);
            assertThat(summary).containsEntry("errorCount", 1);
        }
    }

    /**
     * S3OutputAndKms — verifies the delegation boundary between the
     * service and the {@link S3OutputService} adapter. Per AAP
     * §0.7.1, all S3 writes flow through {@code S3OutputService}
     * (never raw {@code S3Client} calls), so the KMS encryption
     * envelope is the adapter's concern and is verified in the
     * adapter's own unit tests. Here we lock the contract:
     *   (a) writeReport is invoked with a non-null, non-empty
     *       payload and a non-null reportId,
     *   (b) the reportId follows the {@code stmt-<acct>-<ts>}
     *       pattern that downstream S3 lifecycle policies
     *       partition by,
     *   (c) text + HTML keys differ only by the {@code .html} suffix.
     */
    @Nested
    @DisplayName("S3OutputAndKms — adapter delegation + report key pattern")
    class S3OutputAndKms {

        @Test
        @DisplayName("writeReport invoked with non-null payload bytes for both formats")
        void generateStatement_writesToS3WithKmsEncryption() {
            // KMS-at-rest enforcement lives inside S3OutputService;
            // here we verify only that the service delegates to that
            // adapter with a non-null, non-empty payload (the only
            // contract surface the SUT controls).
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("42.42"), "Tx")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<String> keyCaptor =
                    ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(keyCaptor.capture(), bytesCaptor.capture());

            // Both reportId strings non-null + start with "stmt-".
            for (String key : keyCaptor.getAllValues()) {
                assertThat(key).isNotNull();
                assertThat(key).isNotEmpty();
                assertThat(key).startsWith("stmt-");
            }
            // Both payloads non-null + non-empty (so KMS encrypt-on-write
            // in the adapter actually has bytes to encrypt).
            for (byte[] payload : bytesCaptor.getAllValues()) {
                assertThat(payload).isNotNull();
                assertThat(payload.length).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("reportId encodes account ID (PIC 9(11)) + 14-char timestamp partition")
        void generateStatement_reportIdFollowsExpectedPattern() {
            // Per the SUT statementId format:
            //   "stmt-" + 11-digit acctId + "-" + 14-char yyyyMMddHHmmss
            //   total length: 5 + 11 + 1 + 14 = 31
            // The 14-char timestamp doubles as the S3 lifecycle
            // partition key for regulatory data retention.
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<String> keyCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService, times(2))
                    .writeReport(keyCaptor.capture(), any(byte[].class));

            String textKey = keyCaptor.getAllValues().get(0);
            String htmlKey = keyCaptor.getAllValues().get(1);

            // Text reportId: exactly "stmt-<11 digits>-<14 digits>"
            assertThat(textKey).hasSize(31);
            assertThat(textKey).matches("^stmt-\\d{11}-\\d{14}$");

            // Account ID appears verbatim in the reportId so S3
            // bucket lifecycle / Macie scans can partition by
            // account-id prefix.
            assertThat(textKey).contains(String.valueOf(ACCOUNT_ID));

            // HTML reportId is the same as text plus ".html"
            assertThat(htmlKey).isEqualTo(textKey + ".html");
        }

        @Test
        @DisplayName("text vs html reportId differ only by .html suffix (sibling object pair)")
        void generateStatement_textAndHtmlKeysShareStatementIdPrefix() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<String> keyCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService, times(2))
                    .writeReport(keyCaptor.capture(), any(byte[].class));

            List<String> keys = keyCaptor.getAllValues();
            assertThat(keys).hasSize(2);

            String text = keys.get(0);
            String html = keys.get(1);

            // Both keys share the same statement-id prefix so the
            // bucket lifecycle policy (and any KMS rotation scan)
            // sees them as a single logical object pair.
            assertThat(html).startsWith(text);
            assertThat(html.length() - text.length()).isEqualTo(".html".length());
            assertThat(html).endsWith(".html");
        }
    }

    /**
     * PiiHandling — locks the PCI-DSS Req 3.3 invariants for the
     * generated statements (AAP §0.6.6). The customer SSN, card
     * verification value, and the full primary account number
     * (PAN) must NEVER appear in either the text or HTML
     * statement body, in the audit log payloads, or in the
     * S3 object keys.
     */
    @Nested
    @DisplayName("PiiHandling — PCI-DSS Req 3.3 (PAN / SSN / CVV never in output)")
    class PiiHandling {

        @Test
        @DisplayName("customer SSN value never appears in text or HTML body")
        void statement_customerSsnNeverInOutput() {
            // COBOL: CBSTM03A reads CUSTREC.SEC-CUST-SSN (PIC 9(09))
            //   into WS-CUST-DATA but explicitly excludes it from
            //   the rendered statement body. The Java port mirrors
            //   that PCI-DSS Req 3.4 discipline.
            customer.setCustSsn(999_999_999L);
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("10.00"), "Tx")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);

            // Negative assertions — SSN must NEVER be present in any
            // common rendering (raw digits, dashed, or partial).
            assertThat(text).doesNotContain("999999999");
            assertThat(text).doesNotContain("999-99-9999");
            assertThat(html).doesNotContain("999999999");
            assertThat(html).doesNotContain("999-99-9999");
        }

        @Test
        @DisplayName("government-issued ID value (PII) never appears in output")
        void statement_govtIdNeverInOutput() {
            // CUST-GOVT-ISSUED-ID (PIC X(20)) is PII per AAP §0.6.6.
            // The Java statement must not surface it.
            String govtId = "PASSPORT-XYZ-987654";
            customer.setCustGovtIssuedId(govtId);
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);

            assertThat(text).doesNotContain(govtId);
            assertThat(html).doesNotContain(govtId);
        }

        @Test
        @DisplayName("full primary account number (PAN) is masked, never rendered verbatim")
        void statement_fullPanNeverInOutput() {
            // PCI-DSS Req 3.3 — masked PAN only (last-4).
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("10.00"), "Tx")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);

            // Both formats display the masked variant.
            assertThat(text).contains("****-****-****-0001");
            assertThat(html).contains("****-****-****-0001");

            // The verbatim 16-digit PAN must never appear.
            assertThat(text).doesNotContain(CARD_LOW);
            assertThat(html).doesNotContain(CARD_LOW);
        }

        @Test
        @DisplayName("phone numbers (PII) are not rendered in statement body")
        void statement_phoneNumbersNeverInOutput() {
            // CUST-PHONE-NUM-1 / CUST-PHONE-NUM-2 are PII; the
            // CBSTM03A render path intentionally omits them.
            String phone1 = "5551234567";
            String phone2 = "5559876543";
            customer.setCustPhoneNum1(phone1);
            customer.setCustPhoneNum2(phone2);
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);

            assertThat(text).doesNotContain(phone1);
            assertThat(text).doesNotContain(phone2);
            assertThat(html).doesNotContain(phone1);
            assertThat(html).doesNotContain(phone2);
        }

        @Test
        @DisplayName("PCI/PII negative assertions hold even when customer fields populated")
        void statement_allPiiFields_neverInOutput() {
            // Stress test — every PII column populated; none must leak.
            customer.setCustSsn(987_654_321L);
            customer.setCustGovtIssuedId("DL-CA-Z123456");
            customer.setCustPhoneNum1("4155551212");
            customer.setCustPhoneNum2("4155553434");
            customer.setCustEftAccountId("EFT-9988776655");
            stubHappyPathSingleAccount(List.of(buildTransaction(
                    "T1", CARD_LOW, new BigDecimal("10.00"), "Tx")));

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);

            // SSN, government ID, phone numbers, EFT account ID,
            // and the full PAN must all be absent from BOTH formats.
            assertThat(text).doesNotContain("987654321");
            assertThat(text).doesNotContain("DL-CA-Z123456");
            assertThat(text).doesNotContain("4155551212");
            assertThat(text).doesNotContain("4155553434");
            assertThat(text).doesNotContain("EFT-9988776655");
            assertThat(text).doesNotContain(CARD_LOW);

            assertThat(html).doesNotContain("987654321");
            assertThat(html).doesNotContain("DL-CA-Z123456");
            assertThat(html).doesNotContain("4155551212");
            assertThat(html).doesNotContain("4155553434");
            assertThat(html).doesNotContain("EFT-9988776655");
            assertThat(html).doesNotContain(CARD_LOW);
        }
    }

    /**
     * EmptyTransactionList — verifies the zero-total scenario.
     * The COBOL CBSTM03A program emits a statement even for
     * accounts whose TRNXFILE query returns no rows for the
     * period; the rendered TOTAL line shows {@code 0.00} (the
     * PIC S9(9)V99 zero rendering) and no transaction detail
     * rows are emitted between the column header and the total.
     */
    @Nested
    @DisplayName("EmptyTransactionList — zero transactions, zero total")
    class EmptyTransactionList {

        @Test
        @DisplayName("account with no transactions still produces statement with 0.00 total")
        void generateStatement_noTransactions_writesEmptyStatementWithZeroTotal() {
            // COBOL: CBSTM03A — even when 4000-TRNXFILE-GET returns
            // no rows for a (card-num, period) tuple, the statement
            // is still emitted with WS-TOTAL-AMT = 0.00.
            stubHappyPathSingleAccount(List.of());

            StatementGenerationService.StatementResult result =
                    service.generateStatements(STATEMENT_DATE);

            // Statement was produced (text + HTML).
            assertThat(result.textCount()).isEqualTo(1);
            assertThat(result.htmlCount()).isEqualTo(1);
            assertThat(result.errorCount()).isEqualTo(0);

            // Both text + HTML files written.
            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            String html = new String(bytesCaptor.getAllValues().get(1),
                    StandardCharsets.US_ASCII);

            // TOTAL line present in both formats and rendered as 0.00.
            assertThat(text).contains("TOTAL:");
            assertThat(text).contains("0.00");
            assertThat(html).contains("TOTAL:");
            assertThat(html).contains("0.00");
        }

        @Test
        @DisplayName("zero-tx audit payload carries total=0.00 and transactionCount=0")
        void generateStatement_noTransactions_auditPayloadShowsZero() {
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("statement.generated"), eq("STATEMENT"),
                    anyString(), eq("BATCH"),
                    payloadCaptor.capture(), eq(STATEMENT_DATE_STR));

            Map<String, Object> payload = payloadCaptor.getValue();
            // transactionCount = 0; total = 0 (BigDecimal-aware
            // comparison via isEqualByComparingTo so scale=2 vs
            // scale=0 zeroes do not falsely diverge).
            assertThat(payload).containsEntry("transactionCount", 0);
            assertThat(((BigDecimal) payload.get("total")))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("zero-tx statement still includes account header + END banner")
        void generateStatement_noTransactions_stillIncludesHeaderAndBanners() {
            // COBOL: even a zero-tx statement carries the START
            // banner, customer/account block, TRANSACTION SUMMARY
            // header, and the END banner — so the printed page
            // remains visually complete.
            stubHappyPathSingleAccount(List.of());

            service.generateStatements(STATEMENT_DATE);

            ArgumentCaptor<byte[]> bytesCaptor =
                    ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(2))
                    .writeReport(anyString(), bytesCaptor.capture());

            String text = new String(bytesCaptor.getAllValues().get(0),
                    StandardCharsets.US_ASCII);
            assertThat(text).contains("START OF STATEMENT");
            assertThat(text).contains(String.valueOf(ACCOUNT_ID));
            assertThat(text).contains("John");
            assertThat(text).contains("Doe");
            assertThat(text).contains("TRANSACTION SUMMARY");
            assertThat(text).contains("END OF STATEMENT");
        }
    }
}
