package com.cardemo.unit.model;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive unit tests for all 11 JPA entity classes and 3 composite key classes.
 * Exercises constructors, getter/setter round-trips, and equality contracts for the
 * complete data model mapped from COBOL VSAM KSDS record layouts.
 */
class EntityGetterSetterTest {

    // ══════════════════════════════════════════════════════════════════════
    // Account (← CVACT01Y.cpy, 300-byte record)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Account entity")
    class AccountTests {

        @Test
        @DisplayName("Default constructor and all getters/setters")
        void testDefaultConstructorAndGetterSetters() {
            Account a = new Account();
            a.setAcctId("00000000001");
            a.setAcctActiveStatus("Y");
            a.setAcctCurrBal(new BigDecimal("5000.50"));
            a.setAcctCreditLimit(new BigDecimal("10000.00"));
            a.setAcctCashCreditLimit(new BigDecimal("3000.00"));
            a.setAcctOpenDate(LocalDate.of(2020, 1, 15));
            a.setAcctExpDate(LocalDate.of(2030, 12, 31));
            a.setAcctReissueDate(LocalDate.of(2025, 6, 15));
            a.setAcctCurrCycCredit(new BigDecimal("1200.00"));
            a.setAcctCurrCycDebit(new BigDecimal("800.00"));
            a.setAcctAddrZip("10001");
            a.setAcctGroupId("GRP001");
            a.setVersion(1);

            assertThat(a.getAcctId()).isEqualTo("00000000001");
            assertThat(a.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(a.getAcctCurrBal()).isEqualByComparingTo("5000.50");
            assertThat(a.getAcctCreditLimit()).isEqualByComparingTo("10000.00");
            assertThat(a.getAcctCashCreditLimit()).isEqualByComparingTo("3000.00");
            assertThat(a.getAcctOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));
            assertThat(a.getAcctExpDate()).isEqualTo(LocalDate.of(2030, 12, 31));
            assertThat(a.getAcctReissueDate()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(a.getAcctCurrCycCredit()).isEqualByComparingTo("1200.00");
            assertThat(a.getAcctCurrCycDebit()).isEqualByComparingTo("800.00");
            assertThat(a.getAcctAddrZip()).isEqualTo("10001");
            assertThat(a.getAcctGroupId()).isEqualTo("GRP001");
            assertThat(a.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            Account a = new Account("00000000002", "N",
                    new BigDecimal("100.00"), new BigDecimal("5000.00"),
                    new BigDecimal("1000.00"), LocalDate.of(2021, 3, 1),
                    LocalDate.of(2028, 3, 1), LocalDate.of(2024, 3, 1),
                    new BigDecimal("500.00"), new BigDecimal("200.00"),
                    "90210", "DEFAULT");
            assertThat(a.getAcctId()).isEqualTo("00000000002");
            assertThat(a.getAcctActiveStatus()).isEqualTo("N");
            assertThat(a.getAcctCreditLimit()).isEqualByComparingTo("5000.00");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Card (← CVACT02Y.cpy, 150-byte record)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Card entity")
    class CardTests {

        @Test
        @DisplayName("Default constructor and all getters/setters")
        void testGetterSetters() {
            Card c = new Card();
            c.setCardNum("4000123456789012");
            c.setCardAcctId("ACCT001");
            c.setCardCvvCd("123");
            c.setCardActiveStatus("Y");
            c.setCardEmbossedName("JOHN DOE");
            c.setCardExpDate(LocalDate.of(2028, 12, 31));
            c.setVersion(2);

            assertThat(c.getCardNum()).isEqualTo("4000123456789012");
            assertThat(c.getCardAcctId()).isEqualTo("ACCT001");
            assertThat(c.getCardCvvCd()).isEqualTo("123");
            assertThat(c.getCardActiveStatus()).isEqualTo("Y");
            assertThat(c.getCardEmbossedName()).isEqualTo("JOHN DOE");
            assertThat(c.getCardExpDate()).isEqualTo(LocalDate.of(2028, 12, 31));
            assertThat(c.getVersion()).isEqualTo(2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Customer (← CVCUS01Y.cpy, 500-byte record)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Customer entity")
    class CustomerTests {

        @Test
        @DisplayName("Default constructor and all 18 getters/setters")
        void testAllGetterSetters() {
            Customer c = new Customer();
            c.setCustId("0000000001");
            c.setCustFirstName("John");
            c.setCustMiddleName("Q");
            c.setCustLastName("Doe");
            c.setCustAddrLine1("123 Main St");
            c.setCustAddrLine2("Suite 100");
            c.setCustAddrLine3("");
            c.setCustAddrStateCd("NY");
            c.setCustAddrCountryCd("US");
            c.setCustAddrZip("10001");
            c.setCustPhoneNum1("2125551234");
            c.setCustPhoneNum2("2125559999");
            c.setCustSsn("123456789");
            c.setCustGovtIssuedId("DL12345");
            c.setCustDob(LocalDate.of(1980, 5, 15));
            c.setCustEftAccountId("EFT001");
            c.setCustPriCardHolderInd("Y");
            c.setCustFicoCreditScore((short) 750);

            assertThat(c.getCustId()).isEqualTo("0000000001");
            assertThat(c.getCustFirstName()).isEqualTo("John");
            assertThat(c.getCustMiddleName()).isEqualTo("Q");
            assertThat(c.getCustLastName()).isEqualTo("Doe");
            assertThat(c.getCustAddrLine1()).isEqualTo("123 Main St");
            assertThat(c.getCustAddrLine2()).isEqualTo("Suite 100");
            assertThat(c.getCustAddrLine3()).isEqualTo("");
            assertThat(c.getCustAddrStateCd()).isEqualTo("NY");
            assertThat(c.getCustAddrCountryCd()).isEqualTo("US");
            assertThat(c.getCustAddrZip()).isEqualTo("10001");
            assertThat(c.getCustPhoneNum1()).isEqualTo("2125551234");
            assertThat(c.getCustPhoneNum2()).isEqualTo("2125559999");
            assertThat(c.getCustSsn()).isEqualTo("123456789");
            assertThat(c.getCustGovtIssuedId()).isEqualTo("DL12345");
            assertThat(c.getCustDob()).isEqualTo(LocalDate.of(1980, 5, 15));
            assertThat(c.getCustEftAccountId()).isEqualTo("EFT001");
            assertThat(c.getCustPriCardHolderInd()).isEqualTo("Y");
            assertThat(c.getCustFicoCreditScore()).isEqualTo((short) 750);
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            Customer c = new Customer("C01", "Jane", "M", "Smith",
                    "456 Oak Ave", "", "", "CA", "US", "90210",
                    "3105551111", "", "987654321", "ID001",
                    LocalDate.of(1990, 12, 1), "EFT002", "N", (short) 680);
            assertThat(c.getCustId()).isEqualTo("C01");
            assertThat(c.getCustLastName()).isEqualTo("Smith");
            assertThat(c.getCustFicoCreditScore()).isEqualTo((short) 680);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Transaction (← CVTRA05Y.cpy, 350-byte record)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Transaction entity")
    class TransactionTests {

        @Test
        @DisplayName("Default constructor and all getters/setters")
        void testAllGetterSetters() {
            Transaction t = new Transaction();
            t.setTranId("0000000001000001");
            t.setTranTypeCd("SA");
            t.setTranCatCd((short) 5001);
            t.setTranSource("POS TERM");
            t.setTranDesc("Grocery purchase");
            t.setTranAmt(new BigDecimal("42.50"));
            t.setTranMerchantId("MERCH001");
            t.setTranMerchantName("Corner Store");
            t.setTranMerchantCity("New York");
            t.setTranMerchantZip("10001");
            t.setTranCardNum("4000123456789012");
            LocalDateTime now = LocalDateTime.now();
            t.setTranOrigTs(now);
            t.setTranProcTs(now.plusMinutes(1));

            assertThat(t.getTranId()).isEqualTo("0000000001000001");
            assertThat(t.getTranTypeCd()).isEqualTo("SA");
            assertThat(t.getTranCatCd()).isEqualTo((short) 5001);
            assertThat(t.getTranSource()).isEqualTo("POS TERM");
            assertThat(t.getTranDesc()).isEqualTo("Grocery purchase");
            assertThat(t.getTranAmt()).isEqualByComparingTo("42.50");
            assertThat(t.getTranMerchantId()).isEqualTo("MERCH001");
            assertThat(t.getTranMerchantName()).isEqualTo("Corner Store");
            assertThat(t.getTranMerchantCity()).isEqualTo("New York");
            assertThat(t.getTranMerchantZip()).isEqualTo("10001");
            assertThat(t.getTranCardNum()).isEqualTo("4000123456789012");
            assertThat(t.getTranOrigTs()).isEqualTo(now);
            assertThat(t.getTranProcTs()).isEqualTo(now.plusMinutes(1));
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            LocalDateTime ts = LocalDateTime.of(2025, 6, 15, 10, 30, 0);
            Transaction t = new Transaction("T001", "SA", (short) 5001,
                    "OPERATOR", "Refund", new BigDecimal("25.00"),
                    "M001", "Test Merch", "Boston", "02101",
                    "4111111111111111", ts, ts.plusSeconds(30));
            assertThat(t.getTranId()).isEqualTo("T001");
            assertThat(t.getTranAmt()).isEqualByComparingTo("25.00");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DailyTransaction (← CVTRA06Y.cpy, staging entity)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DailyTransaction entity")
    class DailyTransactionTests {

        @Test
        @DisplayName("Default constructor and all getters/setters")
        void testAllGetterSetters() {
            DailyTransaction d = new DailyTransaction();
            d.setDalytranId("D001");
            d.setDalytranTypeCd("SA");
            d.setDalytranCatCd((short) 5001);
            d.setDalytranSource("POS TERM");
            d.setDalytranDesc("Daily purchase");
            d.setDalytranAmt(new BigDecimal("99.99"));
            d.setDalytranMerchantId("DM001");
            d.setDalytranMerchantName("Daily Store");
            d.setDalytranMerchantCity("Chicago");
            d.setDalytranMerchantZip("60601");
            d.setDalytranCardNum("4222222222222222");
            LocalDateTime ts = LocalDateTime.of(2025, 7, 1, 9, 0);
            d.setDalytranOrigTs(ts);
            d.setDalytranProcTs(ts.plusMinutes(5));

            assertThat(d.getDalytranId()).isEqualTo("D001");
            assertThat(d.getDalytranTypeCd()).isEqualTo("SA");
            assertThat(d.getDalytranCatCd()).isEqualTo((short) 5001);
            assertThat(d.getDalytranSource()).isEqualTo("POS TERM");
            assertThat(d.getDalytranDesc()).isEqualTo("Daily purchase");
            assertThat(d.getDalytranAmt()).isEqualByComparingTo("99.99");
            assertThat(d.getDalytranMerchantId()).isEqualTo("DM001");
            assertThat(d.getDalytranMerchantName()).isEqualTo("Daily Store");
            assertThat(d.getDalytranMerchantCity()).isEqualTo("Chicago");
            assertThat(d.getDalytranMerchantZip()).isEqualTo("60601");
            assertThat(d.getDalytranCardNum()).isEqualTo("4222222222222222");
            assertThat(d.getDalytranOrigTs()).isEqualTo(ts);
            assertThat(d.getDalytranProcTs()).isEqualTo(ts.plusMinutes(5));
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            LocalDateTime ts = LocalDateTime.of(2025, 8, 1, 12, 0);
            DailyTransaction d = new DailyTransaction("D002", "SA", (short) 5002,
                    "OPERATOR", "Batch entry", new BigDecimal("150.00"),
                    "DM002", "Batch Store", "Denver", "80201",
                    "4333333333333333", ts, ts.plusMinutes(10));
            assertThat(d.getDalytranId()).isEqualTo("D002");
            assertThat(d.getDalytranAmt()).isEqualByComparingTo("150.00");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CardCrossReference (← CVACT03Y.cpy, 50-byte record)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CardCrossReference entity")
    class CardCrossReferenceTests {

        @Test
        @DisplayName("Getters and setters")
        void testGetterSetters() {
            CardCrossReference xref = new CardCrossReference();
            xref.setXrefCardNum("4000123456789012");
            xref.setXrefCustId("CUST001");
            xref.setXrefAcctId("ACCT001");
            assertThat(xref.getXrefCardNum()).isEqualTo("4000123456789012");
            assertThat(xref.getXrefCustId()).isEqualTo("CUST001");
            assertThat(xref.getXrefAcctId()).isEqualTo("ACCT001");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UserSecurity (← CSUSR01Y.cpy, 80-byte record)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UserSecurity entity")
    class UserSecurityTests {

        @Test
        @DisplayName("Getters and setters")
        void testGetterSetters() {
            UserSecurity u = new UserSecurity();
            u.setSecUsrId("ADMIN001");
            u.setSecUsrFname("Admin");
            u.setSecUsrLname("User");
            u.setSecUsrPwd("hashedpwd");
            u.setSecUsrType(UserType.ADMIN);
            assertThat(u.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(u.getSecUsrFname()).isEqualTo("Admin");
            assertThat(u.getSecUsrLname()).isEqualTo("User");
            assertThat(u.getSecUsrPwd()).isEqualTo("hashedpwd");
            assertThat(u.getSecUsrType()).isEqualTo(UserType.ADMIN);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TransactionType (← CVTRA03Y.cpy, 60-byte record)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TransactionType entity")
    class TransactionTypeTests {

        @Test
        @DisplayName("Getters and setters")
        void testGetterSetters() {
            TransactionType tt = new TransactionType();
            tt.setTranType("SA");
            tt.setTranTypeDesc("Sale");
            assertThat(tt.getTranType()).isEqualTo("SA");
            assertThat(tt.getTranTypeDesc()).isEqualTo("Sale");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TransactionCategory (← CVTRA04Y.cpy, composite key)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TransactionCategory entity")
    class TransactionCategoryTests {

        @Test
        @DisplayName("Getters and setters with composite key")
        void testGetterSetters() {
            TransactionCategory tc = new TransactionCategory();
            TransactionCategoryId id = new TransactionCategoryId("SA", (short) 5001);
            tc.setId(id);
            tc.setTranCatTypeDesc("Grocery");
            assertThat(tc.getId()).isEqualTo(id);
            assertThat(tc.getTranCatTypeDesc()).isEqualTo("Grocery");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TransactionCategoryBalance (← CVTRA01Y.cpy, composite key)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TransactionCategoryBalance entity")
    class TransactionCategoryBalanceTests {

        @Test
        @DisplayName("Getters and setters with composite key")
        void testGetterSetters() {
            TransactionCategoryBalance tcb = new TransactionCategoryBalance();
            TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                    "ACCT001", "SA", (short) 5001);
            tcb.setId(id);
            tcb.setTranCatBal(new BigDecimal("1500.00"));
            assertThat(tcb.getId()).isEqualTo(id);
            assertThat(tcb.getTranCatBal()).isEqualByComparingTo("1500.00");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DisclosureGroup (← CVTRA02Y.cpy, composite key)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DisclosureGroup entity")
    class DisclosureGroupTests {

        @Test
        @DisplayName("Getters and setters with composite key")
        void testGetterSetters() {
            DisclosureGroup dg = new DisclosureGroup();
            DisclosureGroupId id = new DisclosureGroupId("GRP001", "SA", (short) 5001);
            dg.setId(id);
            dg.setDisIntRate(new BigDecimal("18.99"));
            assertThat(dg.getId()).isEqualTo(id);
            assertThat(dg.getDisIntRate()).isEqualByComparingTo("18.99");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Composite Key Classes
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TransactionCategoryBalanceId composite key")
    class TransactionCategoryBalanceIdTests {

        @Test
        @DisplayName("No-args constructor and setters")
        void testNoArgsConstructor() {
            TransactionCategoryBalanceId id = new TransactionCategoryBalanceId();
            id.setAcctId("ACCT1");
            id.setTypeCode("SA");
            id.setCatCode((short) 5001);
            assertThat(id.getAcctId()).isEqualTo("ACCT1");
            assertThat(id.getTypeCode()).isEqualTo("SA");
            assertThat(id.getCatCode()).isEqualTo((short) 5001);
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            TransactionCategoryBalanceId id = new TransactionCategoryBalanceId("A1", "SA", (short) 100);
            assertThat(id.getAcctId()).isEqualTo("A1");
            assertThat(id.getTypeCode()).isEqualTo("SA");
            assertThat(id.getCatCode()).isEqualTo((short) 100);
        }

        @Test
        @DisplayName("equals and hashCode")
        void testEqualsHashCode() {
            TransactionCategoryBalanceId id1 = new TransactionCategoryBalanceId("A", "SA", (short) 1);
            TransactionCategoryBalanceId id2 = new TransactionCategoryBalanceId("A", "SA", (short) 1);
            TransactionCategoryBalanceId id3 = new TransactionCategoryBalanceId("B", "SA", (short) 1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
            assertThat(id1).isNotEqualTo(id3);
        }

        @Test
        @DisplayName("equals with null and different type")
        void testEqualsEdgeCases() {
            TransactionCategoryBalanceId id = new TransactionCategoryBalanceId("A", "SA", (short) 1);
            assertThat(id.equals(null)).isFalse();
            assertThat(id.equals("not a key")).isFalse();
            assertThat(id.equals(id)).isTrue();
        }
    }

    @Nested
    @DisplayName("DisclosureGroupId composite key")
    class DisclosureGroupIdTests {

        @Test
        @DisplayName("No-args constructor and setters")
        void testNoArgsConstructor() {
            DisclosureGroupId id = new DisclosureGroupId();
            id.setGroupId("GRP1");
            id.setTypeCode("SA");
            id.setCatCode((short) 5001);
            assertThat(id.getGroupId()).isEqualTo("GRP1");
            assertThat(id.getTypeCode()).isEqualTo("SA");
            assertThat(id.getCatCode()).isEqualTo((short) 5001);
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            DisclosureGroupId id = new DisclosureGroupId("G1", "SA", (short) 100);
            assertThat(id.getGroupId()).isEqualTo("G1");
        }

        @Test
        @DisplayName("equals and hashCode")
        void testEqualsHashCode() {
            DisclosureGroupId id1 = new DisclosureGroupId("G", "SA", (short) 1);
            DisclosureGroupId id2 = new DisclosureGroupId("G", "SA", (short) 1);
            DisclosureGroupId id3 = new DisclosureGroupId("X", "SA", (short) 1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
            assertThat(id1).isNotEqualTo(id3);
        }

        @Test
        @DisplayName("equals edge cases")
        void testEqualsEdgeCases() {
            DisclosureGroupId id = new DisclosureGroupId("G", "SA", (short) 1);
            assertThat(id.equals(null)).isFalse();
            assertThat(id.equals("string")).isFalse();
            assertThat(id.equals(id)).isTrue();
        }
    }

    @Nested
    @DisplayName("TransactionCategoryId composite key")
    class TransactionCategoryIdTests {

        @Test
        @DisplayName("No-args constructor and setters")
        void testNoArgsConstructor() {
            TransactionCategoryId id = new TransactionCategoryId();
            id.setTypeCode("SA");
            id.setCatCode((short) 5001);
            assertThat(id.getTypeCode()).isEqualTo("SA");
            assertThat(id.getCatCode()).isEqualTo((short) 5001);
        }

        @Test
        @DisplayName("All-args constructor")
        void testAllArgsConstructor() {
            TransactionCategoryId id = new TransactionCategoryId("SA", (short) 5001);
            assertThat(id.getTypeCode()).isEqualTo("SA");
            assertThat(id.getCatCode()).isEqualTo((short) 5001);
        }

        @Test
        @DisplayName("equals and hashCode")
        void testEqualsHashCode() {
            TransactionCategoryId id1 = new TransactionCategoryId("SA", (short) 1);
            TransactionCategoryId id2 = new TransactionCategoryId("SA", (short) 1);
            TransactionCategoryId id3 = new TransactionCategoryId("XX", (short) 1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
            assertThat(id1).isNotEqualTo(id3);
        }

        @Test
        @DisplayName("equals edge cases")
        void testEqualsEdgeCases() {
            TransactionCategoryId id = new TransactionCategoryId("SA", (short) 1);
            assertThat(id.equals(null)).isFalse();
            assertThat(id.equals(42)).isFalse();
            assertThat(id.equals(id)).isTrue();
        }

        @Test
        @DisplayName("toString includes fields")
        void testToString() {
            TransactionCategoryId id = new TransactionCategoryId("SA", (short) 5001);
            assertThat(id.toString()).contains("SA");
        }
    }
}
