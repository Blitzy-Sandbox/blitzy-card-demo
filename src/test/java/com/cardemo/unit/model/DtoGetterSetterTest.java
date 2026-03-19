package com.cardemo.unit.model;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive unit tests for all 8 DTO classes (excluding CommArea which has
 * its own test class). Exercises every getter/setter pair and both constructor
 * variants to maximize code coverage for the API contract layer mapped from
 * BMS symbolic map copybooks.
 */
class DtoGetterSetterTest {

    // ══════════════════════════════════════════════════════════════════════
    // AccountDto (← COACTVW.CPY + COACTUP.CPY, 31 fields)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AccountDto")
    class AccountDtoTests {

        @Test
        @DisplayName("All getters and setters round-trip for financial fields")
        void testFinancialFields() {
            AccountDto dto = new AccountDto();
            dto.setAcctId("00000000001");
            dto.setAcctActiveStatus("Y");
            dto.setAcctCurrBal(new BigDecimal("5000.50"));
            dto.setAcctCreditLimit(new BigDecimal("10000.00"));
            dto.setAcctCashCreditLimit(new BigDecimal("3000.00"));
            dto.setAcctCurrCycCredit(new BigDecimal("1200.00"));
            dto.setAcctCurrCycDebit(new BigDecimal("800.00"));

            assertThat(dto.getAcctId()).isEqualTo("00000000001");
            assertThat(dto.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(dto.getAcctCurrBal()).isEqualByComparingTo("5000.50");
            assertThat(dto.getAcctCreditLimit()).isEqualByComparingTo("10000.00");
            assertThat(dto.getAcctCashCreditLimit()).isEqualByComparingTo("3000.00");
            assertThat(dto.getAcctCurrCycCredit()).isEqualByComparingTo("1200.00");
            assertThat(dto.getAcctCurrCycDebit()).isEqualByComparingTo("800.00");
        }

        @Test
        @DisplayName("All date and customer-related fields")
        void testDateAndCustomerFields() {
            AccountDto dto = new AccountDto();
            dto.setAcctOpenDate(LocalDate.of(2020, 1, 1));
            dto.setAcctExpDate(LocalDate.of(2030, 12, 31));
            dto.setAcctReissueDate(LocalDate.of(2025, 6, 15));
            dto.setAcctGroupId("DEFAULT");
            dto.setCustId("C001");
            dto.setCustFname("John");
            dto.setCustMname("Q");
            dto.setCustLname("Doe");
            dto.setCustAddr1("123 Main St");
            dto.setCustAddr2("Apt 4B");
            dto.setCustCity("New York");
            dto.setCustState("NY");
            dto.setCustZip("10001");
            dto.setCustCountry("US");
            dto.setCustPhone1("2125551234");
            dto.setCustPhone2("2125559999");
            dto.setCustSsn("123456789");
            dto.setCustDob(LocalDate.of(1980, 5, 15));
            dto.setCustFicoScore("750");
            dto.setCustGovtId("DL12345");
            dto.setCustEftAcct("EFT001");
            dto.setCustProfileFlag("Y");
            dto.setStmtNum("STMT001");
            dto.setVersion(3);

            assertThat(dto.getAcctOpenDate()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(dto.getAcctExpDate()).isEqualTo(LocalDate.of(2030, 12, 31));
            assertThat(dto.getAcctReissueDate()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(dto.getAcctGroupId()).isEqualTo("DEFAULT");
            assertThat(dto.getCustId()).isEqualTo("C001");
            assertThat(dto.getCustFname()).isEqualTo("John");
            assertThat(dto.getCustMname()).isEqualTo("Q");
            assertThat(dto.getCustLname()).isEqualTo("Doe");
            assertThat(dto.getCustAddr1()).isEqualTo("123 Main St");
            assertThat(dto.getCustAddr2()).isEqualTo("Apt 4B");
            assertThat(dto.getCustCity()).isEqualTo("New York");
            assertThat(dto.getCustState()).isEqualTo("NY");
            assertThat(dto.getCustZip()).isEqualTo("10001");
            assertThat(dto.getCustCountry()).isEqualTo("US");
            assertThat(dto.getCustPhone1()).isEqualTo("2125551234");
            assertThat(dto.getCustPhone2()).isEqualTo("2125559999");
            assertThat(dto.getCustSsn()).isEqualTo("123456789");
            assertThat(dto.getCustDob()).isEqualTo(LocalDate.of(1980, 5, 15));
            assertThat(dto.getCustFicoScore()).isEqualTo("750");
            assertThat(dto.getCustGovtId()).isEqualTo("DL12345");
            assertThat(dto.getCustEftAcct()).isEqualTo("EFT001");
            assertThat(dto.getCustProfileFlag()).isEqualTo("Y");
            assertThat(dto.getStmtNum()).isEqualTo("STMT001");
            assertThat(dto.getVersion()).isEqualTo(3);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TransactionDto (← COTRN00.CPY + COTRN01.CPY + COTRN02.CPY, 13 fields)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TransactionDto")
    class TransactionDtoTests {

        @Test
        @DisplayName("All getters and setters")
        void testAllFields() {
            TransactionDto dto = new TransactionDto();
            dto.setTranId("0000000001000001");
            dto.setTranTypeCd("SA");
            dto.setTranCatCd("5001");
            dto.setTranSource("POS TERM");
            dto.setTranDesc("Grocery purchase");
            dto.setTranAmt(new BigDecimal("42.50"));
            dto.setTranCardNum("4000123456789012");
            dto.setTranMerchId("MERCH001");
            dto.setTranMerchName("Corner Store");
            dto.setTranMerchCity("New York");
            dto.setTranMerchZip("10001");
            LocalDateTime now = LocalDateTime.now();
            dto.setTranOrigTs(now);
            dto.setTranProcTs(now.plusMinutes(1));

            assertThat(dto.getTranId()).isEqualTo("0000000001000001");
            assertThat(dto.getTranTypeCd()).isEqualTo("SA");
            assertThat(dto.getTranCatCd()).isEqualTo("5001");
            assertThat(dto.getTranSource()).isEqualTo("POS TERM");
            assertThat(dto.getTranDesc()).isEqualTo("Grocery purchase");
            assertThat(dto.getTranAmt()).isEqualByComparingTo("42.50");
            assertThat(dto.getTranCardNum()).isEqualTo("4000123456789012");
            assertThat(dto.getTranMerchId()).isEqualTo("MERCH001");
            assertThat(dto.getTranMerchName()).isEqualTo("Corner Store");
            assertThat(dto.getTranMerchCity()).isEqualTo("New York");
            assertThat(dto.getTranMerchZip()).isEqualTo("10001");
            assertThat(dto.getTranOrigTs()).isEqualTo(now);
            assertThat(dto.getTranProcTs()).isEqualTo(now.plusMinutes(1));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CardDto (← COCRDLI.CPY + COCRDSL.CPY + COCRDUP.CPY, 7 fields)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CardDto")
    class CardDtoTests {

        @Test
        @DisplayName("All getters and setters")
        void testAllFields() {
            CardDto dto = new CardDto();
            dto.setCardNum("4000123456789012");
            dto.setCardAcctId("ACCT001");
            dto.setCardEmbossedName("JOHN DOE");
            dto.setCardExpDate(LocalDate.of(2028, 12, 31));
            dto.setCardActiveStatus("Y");
            dto.setCardCvvCd("999");
            dto.setVersion(1);

            assertThat(dto.getCardNum()).isEqualTo("4000123456789012");
            assertThat(dto.getCardAcctId()).isEqualTo("ACCT001");
            assertThat(dto.getCardEmbossedName()).isEqualTo("JOHN DOE");
            assertThat(dto.getCardExpDate()).isEqualTo(LocalDate.of(2028, 12, 31));
            assertThat(dto.getCardActiveStatus()).isEqualTo("Y");
            assertThat(dto.getCardCvvCd()).isEqualTo("999");
            assertThat(dto.getVersion()).isEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SignOnRequest (← COSGN00.CPY, 2 fields)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SignOnRequest")
    class SignOnRequestTests {

        @Test
        @DisplayName("Getters and setters")
        void testFields() {
            SignOnRequest req = new SignOnRequest();
            req.setUserId("USER01");
            req.setPassword("PASS123");
            assertThat(req.getUserId()).isEqualTo("USER01");
            assertThat(req.getPassword()).isEqualTo("PASS123");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SignOnResponse
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SignOnResponse")
    class SignOnResponseTests {

        @Test
        @DisplayName("All getters and setters")
        void testAllFields() {
            SignOnResponse resp = new SignOnResponse();
            resp.setToken("abc-123");
            resp.setUserType(UserType.ADMIN);
            resp.setUserId("ADMIN01");
            resp.setToTranId("TR02");
            resp.setToProgram("COMEN01C");
            assertThat(resp.getToken()).isEqualTo("abc-123");
            assertThat(resp.getUserType()).isEqualTo(UserType.ADMIN);
            assertThat(resp.getUserId()).isEqualTo("ADMIN01");
            assertThat(resp.getToTranId()).isEqualTo("TR02");
            assertThat(resp.getToProgram()).isEqualTo("COMEN01C");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BillPaymentRequest (← COBIL00.CPY)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BillPaymentRequest")
    class BillPaymentRequestTests {

        @Test
        @DisplayName("Getters and setters")
        void testFields() {
            BillPaymentRequest req = new BillPaymentRequest();
            req.setAccountId("ACCT001");
            req.setConfirmIndicator("Y");
            assertThat(req.getAccountId()).isEqualTo("ACCT001");
            assertThat(req.getConfirmIndicator()).isEqualTo("Y");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ReportRequest (← CORPT00.CPY)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ReportRequest")
    class ReportRequestTests {

        @Test
        @DisplayName("Default constructor and all getters/setters")
        void testAllFields() {
            ReportRequest req = new ReportRequest();
            req.setMonthly(true);
            req.setYearly(false);
            req.setCustom(true);
            req.setStartDate(LocalDate.of(2025, 1, 1));
            req.setEndDate(LocalDate.of(2025, 12, 31));
            req.setConfirm("Y");

            assertThat(req.isMonthly()).isTrue();
            assertThat(req.isYearly()).isFalse();
            assertThat(req.isCustom()).isTrue();
            assertThat(req.getStartDate()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(req.getEndDate()).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(req.getConfirm()).isEqualTo("Y");
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            ReportRequest req = new ReportRequest(false, true, false,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "N");
            assertThat(req.isMonthly()).isFalse();
            assertThat(req.isYearly()).isTrue();
            assertThat(req.isCustom()).isFalse();
            assertThat(req.getConfirm()).isEqualTo("N");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UserSecurityDto (← COUSR00-03.CPY)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UserSecurityDto")
    class UserSecurityDtoTests {

        @Test
        @DisplayName("All getters and setters")
        void testAllFields() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setSecUsrId("USR001");
            dto.setSecUsrFname("John");
            dto.setSecUsrLname("Doe");
            dto.setSecUsrPwd("SecurePass123");
            dto.setSecUsrType(UserType.USER);

            assertThat(dto.getSecUsrId()).isEqualTo("USR001");
            assertThat(dto.getSecUsrFname()).isEqualTo("John");
            assertThat(dto.getSecUsrLname()).isEqualTo("Doe");
            assertThat(dto.getSecUsrPwd()).isEqualTo("SecurePass123");
            assertThat(dto.getSecUsrType()).isEqualTo(UserType.USER);
        }
    }
}
