/*
 * ******************************************************************
 * Program     : EntityBoundaryValidationTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the boundary invariant that the package
 *               documentation of com.cardemo.model.entity asserts, for
 *               every one of the eleven entities and at every
 *               application-facing boundary. Three classes of value are
 *               refused - a null, a character value wider than its
 *               picture clause, and a numeric outside the domain its
 *               picture clause declares - and nothing else is: a blank,
 *               a zero, a negative amount and an under-scaled decimal
 *               are all accepted verbatim, and no value is ever
 *               trimmed, padded, case folded, rescaled or rounded.
 *               Also verifies that every guard is a private static
 *               member, that setVersion is deliberately unguarded, and
 *               that no guard message quotes a sensitive value.
 * Source      : app/cpy/CVACT01Y.cpy:L5-L16   (ACCOUNT-RECORD)
 *               app/cpy/CVACT02Y.cpy          (CARD-RECORD)
 *               app/cpy/CVACT03Y.cpy:L5-L7    (CARD-XREF-RECORD)
 *               app/cpy/CVCUS01Y.cpy:L5-L22   (CUSTOMER-RECORD)
 *               app/cpy/CVTRA01Y.cpy:L6-L9    (TRAN-CAT-BAL-RECORD)
 *               app/cpy/CVTRA02Y.cpy:L6-L9    (DIS-GROUP-RECORD)
 *               app/cpy/CVTRA03Y.cpy          (TRAN-TYPE-RECORD)
 *               app/cpy/CVTRA04Y.cpy          (TRAN-CAT-RECORD)
 *               app/cpy/CVTRA05Y.cpy          (TRAN-RECORD)
 *               app/cpy/CVTRA06Y.cpy          (DALYTRAN-RECORD)
 *               app/cpy/CSUSR01Y.cpy:L18-L22  (SEC-USER-DATA)
 *               app/cbl/CBTRN02C.cbl:L547-L552 (negative to cycle debit)
 *               app/cbl/CBACT04C.cbl:L214,L473-L515 (zero rate, zero
 *               merchant id) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts the boundary invariant of {@code com.cardemo.model.entity} as a package-wide property rather than
 * entity by entity, because that is the shape in which the package documentation states it.
 *
 * <p>The invariant under test has two halves, and the second is as load bearing as the first. An entity must
 * refuse a value the fixed-width record layout cannot have produced, and it must accept every value the layout
 * can produce - including the ones that look wrong to a modern eye. A guard that rejected a blank name, a zero
 * balance, a negative cycle debit or a credit score of {@code 001} would make the seeded fixtures unloadable
 * and would break parity with the system of record, so the acceptance cases below are not padding: they are
 * the half of the contract that is easiest to violate by writing a "sensible" validator.
 */
@DisplayName("Entity boundary invariant: refuse what the record cannot hold, accept everything it can")
class EntityBoundaryValidationTest {

    /** The eleven entity types, in the order their copybooks appear in the migration. */
    private static final List<Class<?>> ENTITIES = List.of(
            Account.class,
            com.cardemo.model.entity.Card.class,
            CardCrossReference.class,
            Customer.class,
            Transaction.class,
            DailyTransaction.class,
            TransactionCategoryBalance.class,
            DisclosureGroup.class,
            TransactionType.class,
            com.cardemo.model.entity.TransactionCategory.class,
            com.cardemo.model.entity.UserSecurity.class);

    // ==================================================================
    // 1 - The guards are structurally what the package documentation
    //     says they are: private, static and present on every entity.
    // ==================================================================

    @Nested
    @DisplayName("1. Guard shape: private static on every entity, and setVersion deliberately exempt")
    class GuardShape {

        @Test
        @DisplayName("every entity declares at least one boundary guard, and every guard is private static")
        void everyGuardIsPrivateAndStatic() {
            assertThat(ENTITIES).hasSize(11);

            for (final Class<?> entity : ENTITIES) {
                final List<Method> guards = Arrays.stream(entity.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .filter(method -> method.getName().startsWith("require")
                                || method.getName().startsWith("check"))
                        .toList();

                assertThat(guards)
                        .as("%s must validate its own shape; the package documentation states the "
                                + "invariant for all eleven entities without exception, so an entity "
                                + "with no guard at all would falsify it", entity.getSimpleName())
                        .isNotEmpty();

                assertThat(guards)
                        .as("every guard on %s must be private static. Static is load bearing rather "
                                + "than stylistic: the JPA specification forbids a final entity, so "
                                + "calling an overridable method from a constructor would publish a "
                                + "partially initialised instance, which -Xlint:all -Werror rejects as "
                                + "this-escape", entity.getSimpleName())
                        .allSatisfy(guard -> {
                            assertThat(Modifier.isPrivate(guard.getModifiers()))
                                    .as("%s.%s is private", entity.getSimpleName(), guard.getName())
                                    .isTrue();
                            assertThat(Modifier.isStatic(guard.getModifiers()))
                                    .as("%s.%s is static", entity.getSimpleName(), guard.getName())
                                    .isTrue();
                        });
            }
        }

        @Test
        @DisplayName("setVersion is the one mutator left unguarded, on all four versioned entities")
        void setVersionIsDeliberatelyUnguarded() {
            final List<Class<?>> versioned = ENTITIES.stream()
                    .filter(entity -> Arrays.stream(entity.getDeclaredFields())
                            .anyMatch(field -> "version".equals(field.getName())))
                    .toList();

            assertThat(versioned)
                    .as("the version counter has no counterpart in any copybook, so only the entities "
                            + "that needed optimistic locking carry one")
                    .isNotEmpty();

            for (final Class<?> entity : versioned) {
                assertThatNoException()
                        .as("the counter is provider owned. Refusing a null here would break "
                                + "reconstitution of a detached instance, and refusing a stale value "
                                + "would pre-empt the optimistic lock failure that is the whole point "
                                + "of the column. %s", entity.getSimpleName())
                        .isThrownBy(() -> entity.getDeclaredMethod("setVersion", Long.class)
                                .invoke(instanceOf(entity), (Object) null));
            }
        }

        @Test
        @DisplayName("no entity imports the project exception hierarchy, keeping the model a leaf")
        void noEntityDependsOnTheExceptionHierarchy() {
            for (final Class<?> entity : ENTITIES) {
                final List<String> referenced = Arrays.stream(entity.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .flatMap(method -> Arrays.stream(method.getExceptionTypes()))
                        .map(Class::getName)
                        .toList();

                assertThat(referenced)
                        .as("a data holder that threw com.cardemo.exception would invert the dependency "
                                + "direction of a model leaf; IllegalArgumentException is unchecked and "
                                + "belongs to the platform. %s", entity.getSimpleName())
                        .allSatisfy(name -> assertThat(name).doesNotContain("com.cardemo.exception"));
            }
        }
    }

    // ==================================================================
    // 2 - Nullability. COBOL has no null, so a null is never a value the
    //     source could have produced, and every mapped column is NOT NULL.
    // ==================================================================

    @Nested
    @DisplayName("2. Nullability: a null is refused at the boundary, not deferred to the flush")
    class Nullability {

        @Test
        @DisplayName("Account refuses a null in each of its twelve mapped arguments, naming the property")
        void accountRefusesEveryNull() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> account(null, "Y"))
                    .withMessageContaining("accountId")
                    .withMessageContaining("ACCT-ID PIC 9(11)");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> account(1L, null))
                    .withMessageContaining("activeStatus")
                    .withMessageContaining("ACCT-ACTIVE-STATUS PIC X(01)");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a money field is a packed decimal in the source and always holds a value")
                    .isThrownBy(() -> new Account(1L, "Y", null, BigDecimal.ZERO, BigDecimal.ZERO,
                            "2020-01-01", "2030-01-01", "2025-01-01", BigDecimal.ZERO, BigDecimal.ZERO,
                            "12345", "DEFAULT"))
                    .withMessageContaining("currentBalance");
        }

        @Test
        @DisplayName("every setter refuses a null too, so a staged build cannot smuggle one past the check")
        void settersRefuseNullAsWellAsConstructors() {
            final Account subject = account(1L, "Y");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("validating only the constructor would leave the setter as an open door, and the "
                            + "posting and update paths reach these fields through setters")
                    .isThrownBy(() -> subject.setActiveStatus(null))
                    .withMessageContaining("activeStatus");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.setCurrentCycleDebit(null))
                    .withMessageContaining("currentCycleDebit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.setGroupId(null))
                    .withMessageContaining("groupId");

            assertThat(subject.getActiveStatus())
                    .as("and a refused assignment leaves the previous value in place rather than clearing it")
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("Customer refuses a null in the identifier and in each of its seventeen text fields")
        void customerRefusesEveryNull() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer(null))
                    .withMessageContaining("customerId")
                    .withMessageContaining("CUST-ID PIC 9(09)");

            final Customer subject = customer(1L);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.setSsn(null))
                    .withMessageContaining("ssn")
                    .withMessageContaining("CUST-SSN PIC 9(09)");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.setFicoCreditScore(null))
                    .withMessageContaining("ficoCreditScore");
        }

        @Test
        @DisplayName("Transaction and DailyTransaction refuse a null in every one of their thirteen fields")
        void transactionsRefuseEveryNull() {
            final Transaction posted = transaction();
            final DailyTransaction staged = dailyTransaction();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> posted.setCardNumber(null))
                    .withMessageContaining("cardNumber");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> posted.setAmount(null))
                    .withMessageContaining("amount");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> posted.setMerchantId(null))
                    .withMessageContaining("merchantId");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the staging table holds untrusted business content, but a null is not untrusted "
                            + "content - it is something the 350 byte fixed-width reader cannot emit")
                    .isThrownBy(() -> staged.setAmount(null))
                    .withMessageContaining("amount");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> staged.setCategoryCode(null))
                    .withMessageContaining("categoryCode");
        }

        @Test
        @DisplayName("CardCrossReference reports a null and an over-wide value as different failures")
        void crossReferenceSeparatesNullFromWidth() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardCrossReference(null, 1L, 1L))
                    .withMessageContaining("must not be null");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a null and a 17 character card number are two different defects and must not "
                            + "collapse into one message")
                    .isThrownBy(() -> new CardCrossReference("12345678901234567", 1L, 1L))
                    .withMessageContaining("must be at most 16 characters")
                    .withMessageNotContaining("must not be null");
        }
    }

    // ==================================================================
    // 3 - Width. A value wider than its picture clause cannot have come
    //     out of the fixed-width record at all.
    // ==================================================================

    @Nested
    @DisplayName("3. Width: the picture clause is the bound, and the message reports length not value")
    class Width {

        @Test
        @DisplayName("a value of exactly the declared width is accepted, and one character more is not")
        void theBoundIsInclusive() {
            assertThatNoException()
                    .as("PIC X(10) admits ten characters, so the bound is inclusive; an exclusive bound "
                            + "would reject the fully populated case that is the normal one")
                    .isThrownBy(() -> account(1L, "Y").setGroupId("0123456789"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> account(1L, "Y").setGroupId("01234567890"))
                    .withMessageContaining("must be at most 10 characters")
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("a width failure on a sensitive field reports the length and never the value")
        void sensitiveValuesAreNeverQuoted() {
            final String overWideCardNumber = "4111111111111111999";
            final String overWideTransactionId = "TRAN0000000000001EXTRA";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a validation message is exactly the kind of string that reaches a log, so a "
                            + "card number must not appear in one. TRAN-CARD-NUM is excluded from "
                            + "toString for the same reason")
                    .isThrownBy(() -> transaction().setCardNumber(overWideCardNumber))
                    .withMessageContaining("cardNumber")
                    .withMessageContaining("19")
                    .withMessageNotContaining(overWideCardNumber);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Transaction(overWideTransactionId, "01", 1, "POS", "D",
                            BigDecimal.ONE, 1L, "M", "C", "00000", "4111111111111111",
                            "2025-01-01-00.00.00.000000", "2025-01-01-00.00.00.000000"))
                    .withMessageContaining("transactionId")
                    .withMessageNotContaining(overWideTransactionId);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the customer record carries a social security number, a government identifier "
                            + "and a full address, none of which toString exposes either")
                    .isThrownBy(() -> customer(1L).setSsn("1234567890"))
                    .withMessageContaining("ssn")
                    .withMessageNotContaining("1234567890");
        }

        @Test
        @DisplayName("the failure names the property, the COBOL field and its picture clause")
        void theMessageIsActionableWithoutReadingTheSource() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("naming only a column, which is all a driver level constraint violation can do, "
                            + "leaves the caller to find which property wrote it")
                    .isThrownBy(() -> new TransactionType("ABC", "A DESCRIPTION"))
                    .withMessageContaining("typeCode")
                    .withMessageContaining("TRAN-TYPE PIC X(02)")
                    .withMessageContaining("3");
        }

        @Test
        @DisplayName("the width guard names the correct table, not a column of it")
        void theNullMessageNamesTheTable() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("tran_type is a column and the table is "
                            + "transaction_type, so naming the column here would point the message at the "
                            + "wrong object")
                    .isThrownBy(() -> new TransactionType(null, "A DESCRIPTION"))
                    .withMessageContaining("transaction_type");
        }
    }

    // ==================================================================
    // 4 - Numeric domain, including the scale bound that is NOT redundant
    //     with the column definition.
    // ==================================================================

    @Nested
    @DisplayName("4. Numeric domain: unsigned ranges, signed magnitudes, and the scale bound")
    class NumericDomain {

        @Test
        @DisplayName("an unsigned PIC 9(n) refuses a negative value and accepts zero")
        void unsignedFieldsRefuseNegativesAndAcceptZero() {
            assertThatNoException()
                    .as("zero is an ordinary member of an unsigned domain and is not a sentinel: "
                            + "app/cbl/CBACT04C.cbl:L473-L515 builds its synthetic interest "
                            + "transactions with a merchant identifier of exactly zero")
                    .isThrownBy(() -> transaction().setMerchantId(0L));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("PIC 9(09) carries no S, so a negative merchant identifier is outside the "
                            + "domain the record can represent")
                    .isThrownBy(() -> transaction().setMerchantId(-1L))
                    .withMessageContaining("merchantId")
                    .withMessageContaining("must be between 0 and 999999999");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> transaction().setMerchantId(1_000_000_000L))
                    .withMessageContaining("999999999");
        }

        @ParameterizedTest(name = "a category code of {0} is refused as outside PIC 9(04)")
        @ValueSource(ints = {-1, 10_000, Integer.MAX_VALUE})
        @DisplayName("a four digit unsigned code refuses anything outside 0 through 9999")
        void categoryCodeBoundsAreEnforced(final int candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> transaction().setCategoryCode(candidate))
                    .withMessageContaining("categoryCode")
                    .withMessageContaining("9999");
        }

        @ParameterizedTest(name = "a category code of {0} is accepted as inside PIC 9(04)")
        @ValueSource(ints = {0, 1, 5000, 9999})
        @DisplayName("and accepts both ends of that range, because the bound is inclusive")
        void categoryCodeBoundsAreInclusive(final int candidate) {
            assertThatNoException().isThrownBy(() -> transaction().setCategoryCode(candidate));
        }

        @Test
        @DisplayName("a signed PIC S9(n)V99 accepts a negative amount, which the posting path requires")
        void signedAmountsAcceptNegatives() {
            assertThatNoException()
                    .as("app/cbl/CBTRN02C.cbl:L547-L552 adds a negative amount to the cycle debit "
                            + "accumulator, which is precisely why the over-limit expression subtracts "
                            + "that term. Rejecting or normalising the sign here would change the "
                            + "arithmetic of every over-limit decision")
                    .isThrownBy(() -> {
                        transaction().setAmount(new BigDecimal("-1234.56"));
                        dailyTransaction().setAmount(new BigDecimal("-0.01"));
                        account(1L, "Y").setCurrentCycleDebit(new BigDecimal("-500.00"));
                    });
        }

        @Test
        @DisplayName("no guard applies an absolute value, a rescale or a rounding of any kind")
        void nothingIsNormalised() {
            final Transaction posted = transaction();
            final BigDecimal negativeWithScaleOne = new BigDecimal("-7.5");

            posted.setAmount(negativeWithScaleOne);

            assertThat(posted.getAmount())
                    .as("the value must come back bit for bit as supplied: same sign, same scale, same "
                            + "unscaled value. A guard that quietly returned 7.50 would corrupt a "
                            + "posted balance and would do so invisibly")
                    .isEqualTo(negativeWithScaleOne);
            assertThat(posted.getAmount().scale())
                    .as("a scale below the declared two is accepted and left alone, because -7.5 and "
                            + "-7.50 denote the same amount")
                    .isEqualTo(1);
            assertThat(posted.getAmount().signum()).isEqualTo(-1);
        }

        @Test
        @DisplayName("an over-scaled decimal is refused rather than left for the column to round")
        void theScaleBoundIsNotRedundantWithTheColumn() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("PostgreSQL rounds an over-scaled NUMERIC(11,2) insert half away from zero "
                            + "rather than refusing it. That is a silent alteration of a posted amount, "
                            + "and by a rounding mode that is not the RoundingMode.HALF_EVEN the batch "
                            + "layer uses, so the column is not a substitute for this check")
                    .isThrownBy(() -> transaction().setAmount(new BigDecimal("1.005")))
                    .withMessageContaining("at most 2 decimal digits")
                    .withMessageContaining("HALF_EVEN");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryBalance(
                            categoryBalanceId(), new BigDecimal("0.001")))
                    .withMessageContaining("at most 2 decimal digits");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the interest rate is a rate rather than a balance, and rounding one silently "
                            + "is no better than rounding the other")
                    .isThrownBy(() -> new DisclosureGroup(
                            disclosureGroupId(), new BigDecimal("1.239")))
                    .withMessageContaining("at most 2 decimal digits");
        }

        @Test
        @DisplayName("each entity's magnitude bound follows its own picture clause, not a shared default")
        void magnitudeBoundsAreEntitySpecific() {
            assertThatNoException()
                    .as("ACCT-CURR-BAL is S9(10)V99, so ten integer digits are inside its domain")
                    .isThrownBy(() -> account(1L, "Y").setCurrentBalance(new BigDecimal("9999999999.99")));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> account(1L, "Y").setCurrentBalance(new BigDecimal("10000000000.00")))
                    .withMessageContaining("9999999999.99");

            assertThatNoException()
                    .as("TRAN-AMT is S9(09)V99 - one digit narrower than an account balance, which is "
                            + "why a single shared money bound would be wrong")
                    .isThrownBy(() -> transaction().setAmount(new BigDecimal("999999999.99")));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> transaction().setAmount(new BigDecimal("1000000000.00")))
                    .withMessageContaining("999999999.99");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("TRAN-CAT-BAL is S9(09)V99 and DIS-INT-RATE is only S9(04)V99, so the "
                            + "disclosure group's bound is far tighter than any of the others")
                    .isThrownBy(() -> new DisclosureGroup(disclosureGroupId(), new BigDecimal("10000.00")))
                    .withMessageContaining("9999.99");
        }
    }

    // ==================================================================
    // 5 - The acceptance half of the contract. This is the half a
    //     well-meaning validator breaks.
    // ==================================================================

    @Nested
    @DisplayName("5. Acceptance: blanks, zeroes and legacy oddities must all still load")
    class Acceptance {

        @ParameterizedTest(name = "a group identifier of [{0}] loads unchanged")
        @ValueSource(strings = {"", " ", "          ", "DEFAULT", "default", "  MiXeD  "})
        @DisplayName("a blank or oddly cased fixed-width value is accepted and never trimmed or folded")
        void blanksAndCaseAreLeftAlone(final String candidate) {
            final Account subject = account(1L, "Y");

            subject.setGroupId(candidate);

            assertThat(subject.getGroupId())
                    .as("ACCT-GROUP-ID is legitimately all spaces in app/data/ASCII/acctdata.txt, and "
                            + "the interest calculation's fallback to the DEFAULT group is what handles "
                            + "that. Trimming a blank to null here would corrupt a legitimate value, and "
                            + "case folding would break the deliberate lower-case comparison the account "
                            + "update path performs on this very field")
                    .isEqualTo(candidate);
        }

        @Test
        @DisplayName("a blank 26 character timestamp is accepted, being the normal staged state")
        void aBlankTimestampIsAccepted() {
            assertThatNoException()
                    .as("DALYTRAN-PROC-TS arrives as 26 spaces on every row of the input file, because "
                            + "the posting job is what fills it in. A not-blank guard here would reject "
                            + "the entire fixture")
                    .isThrownBy(() -> dailyTransaction().setProcTs(" ".repeat(26)));
        }

        @Test
        @DisplayName("a credit score below the conventional floor loads, as twenty-one fixture rows require")
        void aLowCreditScoreLoads() {
            assertThatNoException()
                    .as("app/data/ASCII/custdata.txt carries twenty-one of its fifty rows below 300, so "
                            + "a range check would make the seed migration fail on the system of "
                            + "record's own data. Only the three character width is enforced")
                    .isThrownBy(() -> {
                        customer(1L).setFicoCreditScore("001");
                        customer(1L).setFicoCreditScore("274");
                        customer(1L).setFicoCreditScore("999");
                    });
        }

        @Test
        @DisplayName("a zero interest rate loads, being the source's charge-no-interest case")
        void aZeroInterestRateLoads() {
            assertThatNoException()
                    .as("app/cbl/CBACT04C.cbl:L214 produces no interest transaction and no accumulation "
                            + "for a zero rate, and seventeen DEFAULT rows of "
                            + "app/data/ASCII/discgrp.txt include zero-rate combinations")
                    .isThrownBy(() -> new DisclosureGroup(disclosureGroupId(), BigDecimal.ZERO));

            assertThatNoException()
                    .as("and the picture is signed, so a negative rate is inside the domain too")
                    .isThrownBy(() -> new DisclosureGroup(disclosureGroupId(), new BigDecimal("-1.50")));
        }

        @Test
        @DisplayName("no business rule is enforced: an unmatched code or amount is stored without complaint")
        void noBusinessRuleIsEnforcedAtThisBoundary() {
            assertThatNoException()
                    .as("a card number with no cross-reference row is reject code 100 and an amount that "
                            + "breaches a credit limit is 102, both decided by the posting job. "
                            + "Refusing either here would suppress the reject record the job is "
                            + "required to write, which is a behaviour change rather than validation")
                    .isThrownBy(() -> {
                        dailyTransaction().setCardNumber("0000000000000000");
                        dailyTransaction().setAmount(new BigDecimal("999999999.99"));
                        customer(1L).setAddressStateCode("ZZ");
                        customer(1L).setAddressCountryCode("ZZZ");
                    });
        }
    }

    // ==================================================================
    // 6 - The provider path. Every entity keeps a non-public no-arg
    //     constructor, and it is the only permissive path.
    // ==================================================================

    @Nested
    @DisplayName("6. Provider path: a non-public no-arg constructor is the only permissive one")
    class ProviderPath {

        @Test
        @DisplayName("every entity declares a no-arg constructor and none of them is public")
        void theNoArgConstructorIsNeverPublic() {
            for (final Class<?> entity : ENTITIES) {
                final Constructor<?> noArg = noArgConstructorOf(entity);

                assertThat(Modifier.isPublic(noArg.getModifiers()))
                        .as("the provider instantiates an entity reflectively and needs only that the "
                                + "constructor exist, not that it be public. Leaving it public would "
                                + "hand application code a way to build a half-formed instance that "
                                + "then fails a NOT NULL constraint on flush, which is the failure the "
                                + "boundary guards exist to prevent. %s", entity.getSimpleName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the no-arg path leaves every field null, which is what makes the guards costless")
        void theNoArgPathIsTheStagingPath() {
            final Object staged = instanceOf(Account.class);

            assertThat(((Account) staged).getAccountId())
                    .as("populating the fields is the provider's job, so a guard on the all-columns "
                            + "constructor cannot be blocking a legitimate staged build: a constructor "
                            + "that takes all twelve columns is by definition not staged")
                    .isNull();
            assertThat(((Account) staged).getCurrentBalance()).isNull();
            assertThat(((Account) staged).getVersion()).isNull();
        }
    }

    // ==================================================================
    // 7 - No global mutable state, which the package documentation also
    //     asserts and which the new constants could have violated.
    // ==================================================================

    @Nested
    @DisplayName("7. No global mutable state: every static member is a final constant")
    class NoGlobalMutableState {

        @Test
        @DisplayName("every static field on every entity is final, and none is a container or a counter")
        void everyStaticFieldIsAFinalConstant() {
            for (final Class<?> entity : ENTITIES) {
                final List<Field> statics = Arrays.stream(entity.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .filter(field -> Modifier.isStatic(field.getModifiers()))
                        .toList();

                assertThat(statics)
                        .as("the width and range constants introduced for the boundary guards must not "
                                + "have brought mutable static state with them. %s", entity.getSimpleName())
                        .allSatisfy(field -> {
                            assertThat(Modifier.isFinal(field.getModifiers()))
                                    .as("%s.%s is final", entity.getSimpleName(), field.getName())
                                    .isTrue();
                            assertThat(field.getType().getName())
                                    .as("%s.%s is a constant, not a container or accumulator",
                                            entity.getSimpleName(), field.getName())
                                    .doesNotContain("Logger")
                                    .doesNotContain("Collection")
                                    .doesNotContain("List")
                                    .doesNotContain("Map")
                                    .doesNotContain("Set")
                                    .doesNotContain("Atomic")
                                    .doesNotContain("StringBuilder");
                        });
            }
        }
    }

    // ==================================================================
    // Fixtures. Each builds the minimum valid instance of its entity, so
    // that a test asserting one refusal is not accidentally satisfied by
    // a different argument being wrong.
    // ==================================================================

    private static Account account(final Long accountId, final String activeStatus) {
        return new Account(accountId, activeStatus, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "2020-01-01", "2030-01-01", "2025-01-01", BigDecimal.ZERO, BigDecimal.ZERO,
                "12345", "DEFAULT");
    }

    private static Customer customer(final Long customerId) {
        return new Customer(customerId, "FIRST", "MIDDLE", "LAST", "LINE ONE", "LINE TWO", "LINE THREE",
                "NC", "USA", "12345", "555-0100", "555-0101", "123456789", "GOVT-ID", "1961-06-08",
                "0053581756", "Y", "274");
    }

    private static Transaction transaction() {
        return new Transaction("TRAN000000000001", "01", 1, "POS", "A DESCRIPTION",
                new BigDecimal("1.00"), 1L, "MERCHANT", "CITY", "00000", "4111111111111111",
                "2025-01-01-00.00.00.000000", "2025-01-01-00.00.00.000000");
    }

    private static DailyTransaction dailyTransaction() {
        return new DailyTransaction(1L, "DALY000000000001", "01", 1, "POS", "A DESCRIPTION",
                new BigDecimal("1.00"), 1L, "MERCHANT", "CITY", "00000", "4111111111111111",
                "2025-01-01-00.00.00.000000", " ".repeat(26));
    }

    private static TransactionCategoryBalanceId categoryBalanceId() {
        return new TransactionCategoryBalanceId(1L, "01", 1);
    }

    private static DisclosureGroupId disclosureGroupId() {
        return new DisclosureGroupId("DEFAULT", "01", 1);
    }

    /** Builds an instance through the non-public no-arg constructor the persistence provider uses. */
    private static Object instanceOf(final Class<?> entity) {
        try {
            final Constructor<?> noArg = noArgConstructorOf(entity);
            noArg.setAccessible(true);
            return noArg.newInstance();
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("could not instantiate " + entity.getName(), failure);
        }
    }

    private static Constructor<?> noArgConstructorOf(final Class<?> entity) {
        return Arrays.stream(entity.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        entity.getName() + " declares no no-argument constructor, which the JPA "
                                + "specification requires of every entity"));
    }
}
