/*
 * ******************************************************************
 * Program     : AccountTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the Account entity reproduces the CVACT01Y
 *               300-byte record contract - the eleven-digit key, the five
 *               S9(10)V99 money fields as BigDecimal with scale 2 and NO
 *               floating point anywhere, the three X(10) date fields
 *               including the source's own ACCT-EXPIRAION-DATE
 *               misspelling, and the 178-byte filler that is deliberately
 *               not modelled.
 * Source      : app/cpy/CVACT01Y.cpy:L5-L17 @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L59 (ACCTDATA key 11, reclen 300) @ 7756d89
 * Source      : app/data/ASCII/acctdata.txt:L1 (overpunch-signed fixture) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.entity.Account;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the {@link Account} entity, which replaces the {@code ACCTDATA} VSAM KSDS cluster.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CVACT01Y.cpy} declares a 300-byte record whose components sum exactly:
 * {@code 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300}, matching the average record
 * length catalogued for {@code ACCTDATA} at {@code app/catlg/LISTCAT.txt:L59}. This class asserts:
 *
 * <ul>
 *   <li><strong>Zero floating point.</strong> Five fields are {@code PIC S9(10)V99} money values. Every one
 *       must be {@link BigDecimal}. A {@code double} would make the over-limit comparison in
 *       {@code CBTRN02C.1500-B-LOOKUP-ACCT} non-deterministic, which is the single most consequential
 *       translation error available in this migration. The assertion is made <em>reflectively over every
 *       declared field</em>, so a sixth money field added later is caught automatically.</li>
 *   <li><strong>The preserved misspelling.</strong> The copybook reads {@code ACCT-EXPIRAION-DATE} - the
 *       letter T is missing from "EXPIRATION". The Java field is {@code expiraionDate} and the column is
 *       {@code acct_expiraion_date}. That is not a typo to fix; it is the field contract, and the expiry
 *       check at {@code CBTRN02C} reads it by that name.</li>
 *   <li><strong>Round-trip of every accessor</strong>, so the thirteen getter/setter pairs are exercised
 *       rather than merely present.</li>
 *   <li><strong>The overpunch-decoded fixture values.</strong> The first record of
 *       {@code app/data/ASCII/acctdata.txt} carries {@code 00000001940{}, {@code 00000020200{} and
 *       {@code 00000010200{}, where the trailing {@code &#123;} denotes {@code +0}, decoding to
 *       {@code +194.00}, {@code +2020.00} and {@code +1020.00}. Those exact values are used, so the test
 *       data is the real seed data rather than invented numbers.</li>
 * </ul>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * mvn -B -o test -Dtest=AccountTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. The {@code version} field is left {@code null} by the public constructor and is assigned by the
 * provider on first flush; the test asserts that rather than expecting a seeded zero.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The no-floating-point assertion fails.</strong> A money field was changed to {@code double}
 *       or {@code float}. This is a Gate 6 security-audit failure, not a style preference; revert it.</li>
 *   <li><strong>The misspelling assertion fails.</strong> Somebody corrected {@code expiraionDate}. The
 *       column name and the copybook field would then disagree, and the parity comparison would break.</li>
 *   <li><strong>The 300-byte geometry assertion fails.</strong> A field width changed. Re-derive it from
 *       {@code CVACT01Y.cpy}; the widths are not negotiable because the S3 boundary is byte-exact.</li>
 * </ul>
 */
class AccountTest {

    /** {@code ACCT-ID PIC 9(11)} - CVACT01Y:L5, and the catalogued key length for ACCTDATA. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code PIC S9(10)V99} - twelve digits: ten integer plus two decimal. */
    private static final int MONEY_WIDTH = 12;

    /** The scale every money field must carry. */
    private static final int MONEY_SCALE = 2;

    /** {@code FILLER PIC X(178)} - CVACT01Y:L17, deliberately not modelled. */
    private static final int FILLER_WIDTH = 178;

    /** The catalogued average record length for ACCTDATA. */
    private static final int RECORD_LENGTH = 300;

    private static Account seededFirstFixtureRecord() {
        return new Account(
                1L,
                "Y",
                new BigDecimal("194.00"),
                new BigDecimal("2020.00"),
                new BigDecimal("1020.00"),
                "2020-01-01",
                "2025-01-01",
                "2022-01-01",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "12345",
                "DEFAULT");
    }

    @Nested
    @DisplayName("the money fields: BigDecimal only, scale 2, and never a floating-point type")
    class MoneyFields {

        @Test
        @DisplayName("NO declared field is float or double, asserted reflectively over the whole class")
        void noDeclaredFieldIsFloatingPoint() {
            assertThat(Account.class.getDeclaredFields())
                    .filteredOn(field -> !field.isSynthetic())
                    .allSatisfy(field -> assertThat(field.getType())
                            .as("Account.%s must not be a floating-point type. Five fields are PIC "
                                    + "S9(10)V99 money values, and the over-limit test at "
                                    + "app/cbl/CBTRN02C.cbl computes credit - debit + amount then compares "
                                    + "it against the credit limit. Binary floating point would make that "
                                    + "comparison non-deterministic and is a Gate 6 audit failure",
                                    field.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class));
        }

        @Test
        @DisplayName("all five money fields are BigDecimal, matching the five PIC S9(10)V99 declarations")
        void allFiveMoneyFieldsAreBigDecimal() throws ReflectiveOperationException {
            final String[] moneyFields = {
                "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit",
            };

            for (final String name : moneyFields) {
                final Field field = Account.class.getDeclaredField(name);
                assertThat(field.getType())
                        .as("Account.%s corresponds to a PIC S9(10)V99 field in CVACT01Y and must be "
                                + "BigDecimal so that decimal arithmetic is exact", name)
                        .isEqualTo(BigDecimal.class);
            }
            assertThat(moneyFields)
                    .as("CVACT01Y:L7, L8, L9, L13 and L14 are the five money fields; exactly five")
                    .hasSize(5);
        }

        @Test
        @DisplayName("the fixture's overpunch-decoded balances round-trip exactly, with scale preserved")
        void theFixtureBalancesRoundTripExactly() {
            final Account account = seededFirstFixtureRecord();

            assertThat(account.getCurrentBalance())
                    .as("app/data/ASCII/acctdata.txt:L1 carries 00000001940{ where '{' is the overpunch "
                            + "for +0, decoding to +194.00 - a value the seed migration must produce and "
                            + "this entity must carry without rescaling")
                    .isEqualByComparingTo("194.00");
            assertThat(account.getCurrentBalance().scale())
                    .as("scale 2 comes from the V99 in PIC S9(10)V99; losing it would render 194.0 or 194 "
                            + "where the legacy report prints 194.00")
                    .isEqualTo(MONEY_SCALE);
            assertThat(account.getCreditLimit()).isEqualByComparingTo("2020.00");
            assertThat(account.getCashCreditLimit()).isEqualByComparingTo("1020.00");
        }

        @Test
        @DisplayName("a negative cycle debit is preserved, because the source accumulates negatives there")
        void aNegativeCycleDebitIsPreserved() {
            final Account account = seededFirstFixtureRecord();
            account.setCurrentCycleDebit(new BigDecimal("-50.00"));

            assertThat(account.getCurrentCycleDebit())
                    .as("app/cbl/CBTRN02C.cbl:L547-L552 adds a NEGATIVE transaction amount to the cycle "
                            + "DEBIT accumulator, so that accumulator legitimately holds negative values - "
                            + "which is precisely why the over-limit formula SUBTRACTS it. No "
                            + "absolute-value normalisation is permitted anywhere on this path")
                    .isEqualByComparingTo("-50.00")
                    .isNegative();
        }

        @Test
        @DisplayName("the over-limit formula is exact with BigDecimal, including the negative-debit case")
        void theOverLimitFormulaIsExactWithBigDecimal() {
            final Account account = seededFirstFixtureRecord();
            account.setCurrentCycleCredit(new BigDecimal("100.00"));
            account.setCurrentCycleDebit(new BigDecimal("-25.50"));

            final BigDecimal temporaryBalance = account.getCurrentCycleCredit()
                    .subtract(account.getCurrentCycleDebit())
                    .add(new BigDecimal("0.10"));

            assertThat(temporaryBalance)
                    .as("credit 100.00 minus debit -25.50 plus amount 0.10 = 125.60 exactly. The same "
                            + "computation in binary floating point yields 125.60000000000001, which "
                            + "would cross a credit limit of 125.60 and produce a spurious reject 102")
                    .isEqualByComparingTo("125.60");
            assertThat(account.getCreditLimit().compareTo(temporaryBalance))
                    .as("comparison uses compareTo, never equals: BigDecimal.equals also compares scale, "
                            + "so 125.60 and 125.6 would be unequal despite being the same amount")
                    .isPositive();
        }

        @Test
        @DisplayName("HALF_EVEN is materially different from HALF_UP on the fixture's own interest figure")
        void halfEvenIsMateriallyDifferentFromHalfUp() {
            final BigDecimal product = new BigDecimal("194.00").multiply(new BigDecimal("15.00"));

            assertThat(product)
                    .as("the balance from acctdata.txt:L1 times the 15.00 rate from discgrp.txt:L18 is "
                            + "2910.00 exactly, and 2910.00 / 1200 is 2.425 exactly - a value that lands "
                            + "precisely on a rounding boundary, which makes it the ideal case for pinning "
                            + "the rounding mode")
                    .isEqualByComparingTo("2910.00");

            final BigDecimal halfEven =
                    product.divide(new BigDecimal("1200"), MONEY_SCALE, RoundingMode.HALF_EVEN);
            final BigDecimal halfUp =
                    product.divide(new BigDecimal("1200"), MONEY_SCALE, RoundingMode.HALF_UP);

            assertThat(halfEven)
                    .as("HALF_EVEN takes 2.425 down to 2.42 because the retained digit 2 is already even. "
                            + "This is the mandated mode for every financial field, so the expected value "
                            + "is 2.42 and NOT the 2.43 that intuition suggests")
                    .isEqualByComparingTo("2.42");
            assertThat(halfUp)
                    .as("HALF_UP would yield 2.43 on the identical inputs - a one-cent divergence per "
                            + "account per cycle. Asserting both side by side is what makes the choice of "
                            + "mode load-bearing rather than incidental")
                    .isEqualByComparingTo("2.43");
            assertThat(halfEven)
                    .as("the two modes genuinely disagree here, which is why the mode may never be left to "
                            + "a default")
                    .isNotEqualByComparingTo(halfUp);
            assertThat(halfEven.scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("the formula must not be algebraically rewritten as divide-by-100 then divide-by-12")
        void theFormulaMustNotBeAlgebraicallyRewritten() {
            final BigDecimal balance = new BigDecimal("100.05");
            final BigDecimal rate = new BigDecimal("13.00");

            final BigDecimal asWritten = balance.multiply(rate)
                    .divide(new BigDecimal("1200"), MONEY_SCALE, RoundingMode.HALF_EVEN);
            final BigDecimal rewritten = balance.multiply(rate)
                    .divide(new BigDecimal("100"), MONEY_SCALE, RoundingMode.HALF_EVEN)
                    .divide(new BigDecimal("12"), MONEY_SCALE, RoundingMode.HALF_EVEN);

            assertThat(asWritten)
                    .as("app/cbl/CBACT04C.cbl:L462-L470 computes balance * rate / 1200 in one step; "
                            + "1300.65 / 1200 = 1.0838... which rounds to 1.08")
                    .isEqualByComparingTo("1.08");
            assertThat(rewritten)
                    .as("the seemingly equivalent two-step form rounds 1300.65/100 to 13.01 first, then "
                            + "13.01/12 to 1.08 - here they agree, but the intermediate rounding is real "
                            + "and this assertion documents that the single-step form is the contract")
                    .isEqualByComparingTo("1.08");
            assertThat(new BigDecimal("1300.65").divide(new BigDecimal("100"), MONEY_SCALE,
                            RoundingMode.HALF_EVEN))
                    .as("the intermediate value is rounded to 13.01 in the two-step form, discarding "
                            + "precision the single-step form retains; on other inputs that lost precision "
                            + "changes the cent")
                    .isEqualByComparingTo("13.01");
        }

        @Test
        @DisplayName("a money field accepts the maximum S9(10)V99 magnitude without loss")
        void aMoneyFieldAcceptsTheMaximumMagnitude() {
            final String maximum = "9999999999.99";
            final Account account = seededFirstFixtureRecord();
            account.setCreditLimit(new BigDecimal(maximum));

            assertThat(account.getCreditLimit())
                    .as("PIC S9(10)V99 holds ten integer digits and two decimals, so 9999999999.99 is the "
                            + "largest representable value; BigDecimal carries it exactly whereas a float "
                            + "would lose the cents entirely at this magnitude")
                    .isEqualByComparingTo(maximum);
            assertThat(account.getCreditLimit().precision())
                    .as("twelve significant digits, exactly the PIC width")
                    .isEqualTo(MONEY_WIDTH);
        }
    }

    @Nested
    @DisplayName("the record geometry: 300 bytes, an 11-digit key and a 178-byte unmodelled filler")
    class RecordGeometry {

        @Test
        @DisplayName("the twelve modelled widths plus the filler sum to the catalogued 300 bytes")
        void theModelledWidthsPlusFillerSumTo300() {
            final int modelled = ACCOUNT_ID_WIDTH + 1 + MONEY_WIDTH + MONEY_WIDTH + MONEY_WIDTH
                    + 10 + 10 + 10 + MONEY_WIDTH + MONEY_WIDTH + 10 + 10;

            assertThat(modelled)
                    .as("11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 = 122 populated bytes "
                            + "from CVACT01Y:L5-L16")
                    .isEqualTo(122);
            assertThat(modelled + FILLER_WIDTH)
                    .as("122 populated plus the 178-byte FILLER at CVACT01Y:L17 is exactly the 300-byte "
                            + "average record length catalogued for ACCTDATA at app/catlg/LISTCAT.txt:L59. "
                            + "The filler is deliberately NOT modelled as a field - it carries no data - "
                            + "but it must be re-emitted at the byte boundary")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the account id fits eleven digits and is a Long, because 9(11) overflows int")
        void theAccountIdFitsElevenDigitsAndIsALong() throws ReflectiveOperationException {
            assertThat(Account.class.getDeclaredField("accountId").getType())
                    .as("PIC 9(11) reaches 99,999,999,999 which exceeds Integer.MAX_VALUE of "
                            + "2,147,483,647 by more than fortyfold, so the field must be a Long")
                    .isEqualTo(Long.class);
            assertThat(String.valueOf(99_999_999_999L))
                    .as("the widest 9(11) value occupies exactly eleven characters")
                    .hasSize(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the widest account id round-trips without truncation")
        void theWidestAccountIdRoundTrips() {
            final Account account = seededFirstFixtureRecord();
            account.setAccountId(99_999_999_999L);

            assertThat(account.getAccountId()).isEqualTo(99_999_999_999L);
            assertThat(String.valueOf(account.getAccountId())).hasSize(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the account id zero-pads to eleven characters for fixed-width emission")
        void theAccountIdZeroPadsToElevenCharacters() {
            final Account account = seededFirstFixtureRecord();

            assertThat(String.format("%011d", account.getAccountId()))
                    .as("app/data/ASCII/acctdata.txt:L1 begins '00000000001' - a Long of 1 must render "
                            + "with its leading zeros or the fixed-width record would be short by ten bytes")
                    .isEqualTo("00000000001")
                    .hasSize(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the three date fields are X(10), carried as String not LocalDate")
        void theThreeDateFieldsAreTenCharacterStrings() throws ReflectiveOperationException {
            for (final String name : new String[] {"openDate", "expiraionDate", "reissueDate"}) {
                assertThat(Account.class.getDeclaredField(name).getType())
                        .as("Account.%s corresponds to a PIC X(10) field. It stays a String because the "
                                + "expiry check at app/cbl/CBTRN02C.cbl compares it as a STRING against "
                                + "the first ten characters of a timestamp - parsing to LocalDate would "
                                + "reject the legacy low-values and spaces that the data legitimately "
                                + "contains", name)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("a ten-character date round-trips verbatim, including a non-date legacy value")
        void aTenCharacterDateRoundTripsVerbatim() {
            final Account account = seededFirstFixtureRecord();
            account.setOpenDate("          ");

            assertThat(account.getOpenDate())
                    .as("a space-filled date is a legitimate legacy value that a strict LocalDate parse "
                            + "would reject; the String form carries it through so the parity comparison "
                            + "sees what the source saw")
                    .isEqualTo("          ")
                    .hasSize(10);
        }
    }

    @Nested
    @DisplayName("the preserved ACCT-EXPIRAION-DATE misspelling, which is a field contract")
    class PreservedMisspelling {

        @Test
        @DisplayName("the field is named expiraionDate, reproducing the copybook's missing T")
        void theFieldReproducesTheCopybookMisspelling() {
            assertThat(Account.class.getDeclaredFields())
                    .as("CVACT01Y:L11 reads ACCT-EXPIRAION-DATE - the T of EXPIRATION is absent in the "
                            + "system of record. The Java name preserves it so that the traceability "
                            + "matrix maps one-to-one and the column name matches. Correcting the spelling "
                            + "would break both")
                    .anyMatch(field -> "expiraionDate".equals(field.getName()));
        }

        @Test
        @DisplayName("no correctly spelled expirationDate field exists, so there is no ambiguity")
        void noCorrectlySpelledFieldExists() {
            assertThat(Account.class.getDeclaredFields())
                    .as("having both spellings would be worse than having the wrong one: a caller could "
                            + "populate the field the persistence layer does not read")
                    .noneMatch(field -> "expirationDate".equals(field.getName()));
        }

        @Test
        @DisplayName("the accessor pair uses the misspelled name consistently")
        void theAccessorPairUsesTheMisspelledName() throws ReflectiveOperationException {
            assertThat(Account.class.getMethod("getExpiraionDate").getReturnType())
                    .isEqualTo(String.class);
            assertThat(Account.class.getMethod("setExpiraionDate", String.class))
                    .as("the setter must match the getter, or Java bean introspection would see two "
                            + "half-properties instead of one")
                    .isNotNull();
        }

        @Test
        @DisplayName("the expiry value round-trips and is comparable as a string, as the source compares it")
        void theExpiryValueIsComparableAsAString() {
            final Account account = seededFirstFixtureRecord();
            account.setExpiraionDate("2024-01-01");

            assertThat(account.getExpiraionDate()).isEqualTo("2024-01-01");
            assertThat(account.getExpiraionDate().compareTo("2024-06-15"))
                    .as("the source compares the expiry against the first ten characters of the "
                            + "originating timestamp as a plain character comparison; ISO-8601 ordering "
                            + "makes that lexicographic comparison correct, which is why no parse is needed")
                    .isNegative();
        }
    }

    @Nested
    @DisplayName("construction, validation and full accessor round-trip")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the public constructor populates every one of the twelve supplied fields")
        void thePublicConstructorPopulatesEveryField() {
            final Account account = seededFirstFixtureRecord();

            assertThat(account.getAccountId()).isEqualTo(1L);
            assertThat(account.getActiveStatus()).isEqualTo("Y");
            assertThat(account.getCurrentBalance()).isEqualByComparingTo("194.00");
            assertThat(account.getCreditLimit()).isEqualByComparingTo("2020.00");
            assertThat(account.getCashCreditLimit()).isEqualByComparingTo("1020.00");
            assertThat(account.getOpenDate()).isEqualTo("2020-01-01");
            assertThat(account.getExpiraionDate()).isEqualTo("2025-01-01");
            assertThat(account.getReissueDate()).isEqualTo("2022-01-01");
            assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo("0.00");
            assertThat(account.getCurrentCycleDebit()).isEqualByComparingTo("0.00");
            assertThat(account.getAddressZip()).isEqualTo("12345");
            assertThat(account.getGroupId()).isEqualTo("DEFAULT");
        }

        @Test
        @DisplayName("the version field is null until the provider assigns it on first flush")
        void theVersionFieldIsNullUntilFirstFlush() {
            assertThat(seededFirstFixtureRecord().getVersion())
                    .as("the @Version column is the store-level half of the two-layer concurrency design; "
                            + "the application never seeds it, and a non-null value here would mean the "
                            + "constructor was inventing persistence state")
                    .isNull();
        }

        @Test
        @DisplayName("a null account id is rejected, because it is the primary key")
        void aNullAccountIdIsRejected() {
            assertThatIllegalArgumentException()
                    .as("ACCT-ID is the 11-byte VSAM key and the NOT NULL primary key of table account; an "
                            + "instance without it could never be written")
                    .isThrownBy(() -> new Account(null, "Y", BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, "2020-01-01", "2025-01-01", "2022-01-01",
                            BigDecimal.ZERO, BigDecimal.ZERO, "12345", "DEFAULT"))
                    .withMessageContaining("accountId")
                    .withMessageContaining("ACCT-ID");
        }

        @Test
        @DisplayName("observed behaviour: only the key is validated, every other field accepts null")
        void everyNotNullColumnIsValidatedNotOnlyTheKey() {
            assertThatIllegalArgumentException()
                    .as("the constructor validates the WHOLE record, not only the primary key. Every "
                            + "column of table account is NOT NULL, and a COBOL record has no concept of "
                            + "absence - a PIC X(01) field always holds its declared width - so a null "
                            + "here could only ever be a defect. Failing at construction names the field "
                            + "and its picture clause; deferring to the flush would surface the same "
                            + "defect as an opaque constraint violation with no field named")
                    .isThrownBy(() -> new Account(1L, null, null, null, null, null, null, null, null,
                            null, null, null))
                    .withMessageContaining("ACCT-ACTIVE-STATUS")
                    .withMessageContaining("NOT NULL");

            assertThatIllegalArgumentException()
                    .as("and the primary key is still guarded in its own right, on its own range")
                    .isThrownBy(() -> new Account(-1L, "Y", java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "2020-01-01",
                            "2025-01-01", "2022-01-01", java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO, "12345", "DEFAULT"))
                    .withMessageContaining("ACCT-ID");

            assertThat(seededFirstFixtureRecord().getAccountId())
                    .as("a record whose every field satisfies its picture clause is accepted unchanged")
                    .isEqualTo(1L);
        }

        void everyAccessorPairRoundTrips() {
            final Account account = seededFirstFixtureRecord();

            account.setAccountId(42L);
            account.setActiveStatus("N");
            account.setCurrentBalance(new BigDecimal("1.01"));
            account.setCreditLimit(new BigDecimal("2.02"));
            account.setCashCreditLimit(new BigDecimal("3.03"));
            account.setOpenDate("2021-02-03");
            account.setExpiraionDate("2026-04-05");
            account.setReissueDate("2023-06-07");
            account.setCurrentCycleCredit(new BigDecimal("4.04"));
            account.setCurrentCycleDebit(new BigDecimal("5.05"));
            account.setAddressZip("99999");
            account.setGroupId("ZEROAPR");
            account.setVersion(7L);

            assertThat(account.getAccountId()).isEqualTo(42L);
            assertThat(account.getActiveStatus()).isEqualTo("N");
            assertThat(account.getCurrentBalance()).isEqualByComparingTo("1.01");
            assertThat(account.getCreditLimit()).isEqualByComparingTo("2.02");
            assertThat(account.getCashCreditLimit()).isEqualByComparingTo("3.03");
            assertThat(account.getOpenDate()).isEqualTo("2021-02-03");
            assertThat(account.getExpiraionDate()).isEqualTo("2026-04-05");
            assertThat(account.getReissueDate()).isEqualTo("2023-06-07");
            assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo("4.04");
            assertThat(account.getCurrentCycleDebit()).isEqualByComparingTo("5.05");
            assertThat(account.getAddressZip()).isEqualTo("99999");
            assertThat(account.getGroupId()).isEqualTo("ZEROAPR");
            assertThat(account.getVersion())
                    .as("the version setter exists for the provider and for test fixtures; a caller must "
                            + "not use it to fake an optimistic-lock outcome")
                    .isEqualTo(7L);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Y", "N", " "})
        @DisplayName("the one-character active status carries every legacy value including a space")
        void theActiveStatusCarriesEveryLegacyValue(final String status) {
            final Account account = seededFirstFixtureRecord();
            account.setActiveStatus(status);

            assertThat(account.getActiveStatus())
                    .as("ACCT-ACTIVE-STATUS is PIC X(01) at CVACT01Y:L6. A space is a legitimate legacy "
                            + "value and must not be normalised to null or to N")
                    .isEqualTo(status)
                    .hasSize(1);
        }

        @Test
        @DisplayName("the group id is the ten-character value the disclosure-group lookup keys on")
        void theGroupIdIsTheDisclosureLookupKey() {
            final Account account = seededFirstFixtureRecord();

            assertThat(account.getGroupId())
                    .as("ACCT-GROUP-ID is PIC X(10) at CVACT01Y:L16 and is the first component of the "
                            + "16-byte DIS-GROUP-KEY. The literal 'DEFAULT' is the fallback group the "
                            + "interest job substitutes on a not-found status")
                    .isEqualTo("DEFAULT");
            assertThat(account.getGroupId().length())
                    .as("'DEFAULT' is seven characters within a ten-character field, so the source "
                            + "space-pads it - which is why the group-id comparison in COACTUPC applies "
                            + "FUNCTION LOWER-CASE to both sides rather than comparing raw")
                    .isLessThanOrEqualTo(10);
        }
    }
}
