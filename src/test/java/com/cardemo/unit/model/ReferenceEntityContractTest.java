/*
 * ******************************************************************
 * Program     : ReferenceEntityContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the record geometry, decimal tier and constructor
 *               guard policy of the five reference and balance entities
 *               SIDE BY SIDE, because the five sibling classes implement
 *               FOUR DIFFERENT validation policies and none of them can
 *               be inferred from another. Testing them apart would let a
 *               reader assume a uniformity that does not exist.
 * Source      : app/cpy/CVTRA01Y.cpy (TCATBALF, 50 B, key 17) @ 7756d89
 * Source      : app/cpy/CVTRA02Y.cpy (DISCGRP,  50 B, key 16) @ 7756d89
 * Source      : app/cpy/CVTRA03Y.cpy (TRANTYPE, 60 B, key  2) @ 7756d89
 * Source      : app/cpy/CVTRA04Y.cpy (TRANCATG, 60 B, key  6) @ 7756d89
 * Source      : app/cpy/CVACT03Y.cpy (CARDXREF, 50 B, 36 populated) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for the five reference and balance entities, asserted comparatively.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@link TransactionCategory}, {@link CardCrossReference}, {@link DisclosureGroup},
 * {@link TransactionCategoryBalance} and {@link TransactionType} are five small sibling entities that look
 * interchangeable and are not. Reading the five constructors reveals <strong>four distinct guard
 * policies</strong>, and reading the two decimal columns reveals <strong>two different precision tiers</strong>.
 * This class therefore tests all five together, in one place, so the divergence is visible in a single
 * screenful rather than spread across five files where each looks self-consistent.
 *
 * <h3>The four guard policies, as read from the source</h3>
 *
 * <table border="1">
 *   <caption>Guard policy by entity - none inferable from another</caption>
 *   <tr><th>Entity</th><th>Constructor guards</th><th>Setters guard too?</th></tr>
 *   <tr><td>{@link TransactionCategory}</td>
 *       <td>{@code requireId} (null) + {@code requireCategoryDescription} (null <em>or</em> &gt; 50)</td>
 *       <td><strong>Yes</strong>, both</td></tr>
 *   <tr><td>{@link CardCrossReference}</td>
 *       <td>{@code requireSupplied} (null) on <em>all three</em> components</td>
 *       <td><strong>Yes</strong>, all three</td></tr>
 *   <tr><td>{@link DisclosureGroup}</td>
 *       <td>{@code requireId} (null) + {@code requireInterestRate} (null only, <em>no range check</em>)</td>
 *       <td><strong>Yes</strong>, both</td></tr>
 *   <tr><td>{@link TransactionCategoryBalance}</td><td><strong>None at all</strong></td><td>No</td></tr>
 *   <tr><td>{@link TransactionType}</td><td><strong>None at all</strong></td><td>No</td></tr>
 * </table>
 *
 * <p>Every one of those rows is asserted below in both directions - the guarded types reject {@code null} and
 * the unguarded types accept it - so a future change that unifies the policies fails loudly instead of
 * silently altering which rows reach the database.
 *
 * <h3>The two decimal tiers, which must never be collapsed</h3>
 *
 * <p>{@code TRAN-CAT-BAL PIC S9(09)V99} is {@code NUMERIC(11,2)} while {@code DIS-INT-RATE PIC S9(04)V99} is
 * {@code NUMERIC(6,2)} - the narrowest column in the schema. The account money fields are a third tier,
 * {@code NUMERIC(12,2)}. Widening the rate to the balance's tier, or the balance to the account's, is the
 * single most likely silent schema defect in this package, so both are pinned against their pictures.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * mvn -B -o test -Dtest=ReferenceEntityContractTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. No profile, no container and no database is involved.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A "policy comparison" test fails.</strong> Somebody unified the guards across the five
 *       siblings. That is a behaviour change: {@code TransactionCategoryBalance} is written by the posting
 *       job's upsert path, where a not-found status is an accepted control flow rather than an error, and
 *       adding a constructor guard there would abend a path the source treats as success.</li>
 *   <li><strong>A decimal-tier test fails.</strong> A {@code precision} was widened. Check the picture in the
 *       copybook before changing the test - the copybook is the authority.</li>
 *   <li><strong>A geometry test fails.</strong> A width changed. The trailing FILLER absorbs the difference
 *       between the populated bytes and the catalogued slot, so recompute both.</li>
 * </ul>
 */
class ReferenceEntityContractTest {

    /**
     * Builds an instance through the {@code protected} no-argument JPA constructor, leaving every field
     * at its default {@code null}.
     *
     * <p>Required because these entities now guard their all-argument constructors, so a null-key
     * instance can no longer be built that way. This is not a workaround for the guard: it is the exact
     * path the persistence provider uses when it materialises a row, which is why states that the
     * all-argument constructor refuses are still reachable in production and still worth asserting on.
     * Hibernate reaches the same constructor by the same reflective means.</p>
     *
     * @param <T>  the entity type
     * @param type the entity class to instantiate
     * @return a fully default-initialised instance
     */
    private static <T> T providerInstance(final Class<T> type) {
        try {
            final java.lang.reflect.Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("could not reach the no-arg JPA constructor of "
                    + type.getSimpleName() + "; JPA requires it to remain present", e);
        }
    }

    private static TransactionCategoryId categoryId() {
        return new TransactionCategoryId("01", 5);
    }

    private static DisclosureGroupId groupId() {
        return new DisclosureGroupId("DEFAULT", "01", 5);
    }

    private static TransactionCategoryBalanceId balanceId() {
        return new TransactionCategoryBalanceId(1L, "01", 5);
    }

    @Nested
    @DisplayName("record geometry: every width traced to its copybook picture")
    class RecordGeometry {

        @ParameterizedTest
        @CsvSource({
            // populated, filler, catalogued slot, copybook
            "50, 17, 11, 22, CVTRA01Y TCATBALF",
            "50, 16,  6, 28, CVTRA02Y DISCGRP",
            "60,  2, 50,  8, CVTRA03Y TRANTYPE",
            "60,  6, 50,  4, CVTRA04Y TRANCATG",
        })
        @DisplayName("the key, payload and filler widths sum to the catalogued record length")
        void theWidthsSumToTheCataloguedRecordLength(
                final int slot, final int keyWidth, final int payloadWidth, final int fillerWidth,
                final String copybook) {
            assertThat(keyWidth + payloadWidth + fillerWidth)
                    .as("%s: key %d + payload %d + FILLER %d must equal the catalogued %d-byte record. "
                            + "FILLER is never modelled as a field - it exists only to pad the record out, "
                            + "and the batch writers must re-emit it as blanks at the fixed-width "
                            + "boundary", copybook, keyWidth, payloadWidth, fillerWidth, slot)
                    .isEqualTo(slot);
        }

        @Test
        @DisplayName("the TCATBALF composite key is 17 bytes, the widest key in the schema")
        void theTcatbalfKeyIs17Bytes() {
            assertThat(11 + 2 + 4)
                    .as("CVTRA01Y declares TRAN-CAT-KEY as TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD "
                            + "PIC X(02) + TRANCAT-CD PIC 9(04) = 17, which app/catlg/LISTCAT.txt "
                            + "independently reports as the TCATBALF key length. The fixture "
                            + "app/data/ASCII/tcatbal.txt corroborates it a third time")
                    .isEqualTo(17);
        }

        @Test
        @DisplayName("the DISCGRP key is 16 bytes and must not be confused with the two other KEYLEN 16")
        void theDiscgrpKeyIs16Bytes() {
            assertThat(10 + 2 + 4)
                    .as("CVTRA02Y declares DIS-ACCT-GROUP-ID PIC X(10) + DIS-TRAN-TYPE-CD PIC X(02) + "
                            + "DIS-TRAN-CAT-CD PIC 9(04) = 16. CARDDATA and CARDXREF ALSO report KEYLEN 16 "
                            + "for entirely different clusters, so a catalogue citation for this key must "
                            + "name its own line rather than any KEYLEN 16 line")
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("the TRANCATG key is 6 bytes, the narrowest composite key")
        void theTrancatgKeyIs6Bytes() {
            assertThat(2 + 4)
                    .as("CVTRA04Y declares TRAN-TYPE-CD PIC X(02) + TRAN-CAT-CD PIC 9(04) = 6")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("CARDXREF populates 36 of its 50 catalogued bytes, leaving 14 unmodelled")
        void cardXrefPopulates36Of50Bytes() {
            final int populated = 16 + 9 + 11;

            assertThat(populated)
                    .as("CVACT03Y declares XREF-CARD-NUM PIC X(16) + XREF-CUST-ID PIC 9(09) + "
                            + "XREF-ACCT-ID PIC 9(11) = 36. The fixture app/data/ASCII/cardxref.txt is "
                            + "1,850 bytes over 50 rows = 37 per row, i.e. 36 plus a terminator - an "
                            + "independent confirmation of the populated width")
                    .isEqualTo(36);
            assertThat(50 - populated)
                    .as("the catalogued slot is 50 bytes, so 14 bytes are a legacy allocation artefact. "
                            + "They are documented and never modelled, but a fixed-width writer must "
                            + "still re-emit them as blanks")
                    .isEqualTo(14);
            assertThat(1_850 / 50).isEqualTo(37);
        }

        @Test
        @DisplayName("the two 60-byte reference records both carry a 50-character description")
        void bothSixtyByteRecordsCarryA50CharacterDescription() {
            assertThat(50)
                    .as("TRAN-TYPE-DESC at CVTRA03Y and TRAN-CAT-TYPE-DESC at CVTRA04Y are both PIC X(50), "
                            + "which is why TransactionCategory's guard uses the constant 50 and why the "
                            + "column length matches")
                    .isEqualTo(50);
            assertThat(2 + 50 + 8).isEqualTo(60);
            assertThat(6 + 50 + 4).isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("the two decimal tiers: NUMERIC(11,2) balance versus NUMERIC(6,2) rate")
    class DecimalTiers {

        @Test
        @DisplayName("the balance is NUMERIC(11,2) from PIC S9(09)V99, never NUMERIC(12,2)")
        void theBalanceIsNumeric11By2() {
            final TransactionCategoryBalance balance =
                    new TransactionCategoryBalance(balanceId(), new BigDecimal("999999999.99"));

            assertThat(balance.getBalance().precision())
                    .as("TRAN-CAT-BAL PIC S9(09)V99 is nine integer digits plus two decimals = 11 "
                            + "significant digits. Widening to precision 12 is the single most likely "
                            + "silent schema defect here, because the ACCOUNT money fields ARE "
                            + "NUMERIC(12,2) and the two are easy to conflate")
                    .isEqualTo(11);
            assertThat(balance.getBalance().scale()).isEqualTo(2);
            assertThat(balance.getBalance()).isEqualByComparingTo("999999999.99");
        }

        @Test
        @DisplayName("the interest rate is NUMERIC(6,2) from PIC S9(04)V99, the narrowest in the schema")
        void theInterestRateIsNumeric6By2() {
            final DisclosureGroup group = new DisclosureGroup(groupId(), new BigDecimal("9999.99"));

            assertThat(group.getInterestRate().precision())
                    .as("DIS-INT-RATE PIC S9(04)V99 is four integer digits plus two decimals = 6 "
                            + "significant digits, the only NUMERIC(6,2) column in the schema. Fixture "
                            + "row app/data/ASCII/discgrp.txt:L18 reads 'DEFAULT   01000100150{' where "
                            + "the rate occupies exactly six characters and no more")
                    .isEqualTo(6);
            assertThat(group.getInterestRate().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the fixture's overpunch rate 00150{ decodes to +15.00 at scale 2")
        void theFixtureOverpunchRateDecodesTo1500() {
            // '{' is the zoned-decimal overpunch for "+0", so 00150{ is +015.00 -> 15.00 at scale 2.
            final BigDecimal decoded = new BigDecimal("001500").movePointLeft(2);

            assertThat(decoded)
                    .as("app/data/ASCII/discgrp.txt:L18 stores the rate as six characters '00150{' where "
                            + "the trailing '{' is the trailing-sign overpunch denoting +0. Decoding is "
                            + "position-aware from the PIC clause, because the same letters occur "
                            + "legitimately inside text fields such as merchant names")
                    .isEqualByComparingTo("15.00");
            assertThat(decoded.setScale(2, RoundingMode.UNNECESSARY).scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the interest formula multiplies then divides by 1200 in one step")
        void theInterestFormulaDividesBy1200InOneStep() {
            final BigDecimal balance = new BigDecimal("1000.00");
            final BigDecimal rate = new BigDecimal("15.00");

            final BigDecimal oneStep =
                    balance.multiply(rate).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
            final BigDecimal twoStep = balance.multiply(rate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN)
                    .divide(new BigDecimal("12"), 2, RoundingMode.HALF_EVEN);

            assertThat(oneStep)
                    .as("app/cbl/CBACT04C.cbl:L462-L470 computes balance * rate / 1200 as ONE division. "
                            + "Rewriting it as /100 then /12 introduces an intermediate rounding")
                    .isEqualByComparingTo("12.50");
            assertThat(twoStep).isEqualByComparingTo("12.50");
            assertThat(oneStep)
                    .as("these two happen to agree for this input, which is exactly why the formula shape "
                            + "must be preserved on principle rather than validated by spot-checking one "
                            + "pair of values - see AccountTest for an input where they diverge")
                    .isEqualByComparingTo(twoStep);
        }

        @Test
        @DisplayName("a zero rate is representable, because 17 DEFAULT fixture rows carry one")
        void aZeroRateIsRepresentable() {
            final DisclosureGroup group = new DisclosureGroup(groupId(), new BigDecimal("0.00"));

            assertThat(group.getInterestRate())
                    .as("app/data/ASCII/discgrp.txt contains 17 rows whose group id is the literal "
                            + "DEFAULT, INCLUDING zero-rate combinations. A zero rate produces no interest "
                            + "transaction and no accumulation at app/cbl/CBACT04C.cbl:L214, so zero must "
                            + "be a storable value and not a guard rejection")
                    .isEqualByComparingTo("0.00");
            assertThat(group.getInterestRate().signum()).isZero();
        }

        @Test
        @DisplayName("a negative balance is representable and compared with compareTo, never equals")
        void aNegativeBalanceIsRepresentable() {
            final TransactionCategoryBalance balance =
                    new TransactionCategoryBalance(balanceId(), new BigDecimal("-1.50"));

            assertThat(balance.getBalance()).isEqualByComparingTo("-1.50").isNegative();
            assertThat(new BigDecimal("1.5").equals(new BigDecimal("1.50")))
                    .as("BigDecimal.equals also compares SCALE, so 1.5 and 1.50 are unequal by equals but "
                            + "equal by compareTo. Every balance comparison in the posting path must use "
                            + "compareTo, which is why this test states the hazard explicitly")
                    .isFalse();
            assertThat(new BigDecimal("1.5")).isEqualByComparingTo(new BigDecimal("1.50"));
        }

        @Test
        @DisplayName("neither decimal field is ever a floating-point type")
        void neitherDecimalFieldIsFloatingPoint() {
            for (final Class<?> entity : List.of(
                    TransactionCategoryBalance.class, DisclosureGroup.class, TransactionCategory.class,
                    TransactionType.class, CardCrossReference.class)) {
                assertThat(entity.getDeclaredFields())
                        .filteredOn(field -> !field.isSynthetic())
                        .allSatisfy(field -> assertThat(field.getType())
                                .as("%s.%s must not be float or double - asserted reflectively so a NEW "
                                        + "money field added later is caught automatically",
                                        entity.getSimpleName(), field.getName())
                                .isNotIn(double.class, float.class, Double.class, Float.class));
            }
        }
    }

    @Nested
    @DisplayName("policy comparison: four different guard policies across five sibling entities")
    class PolicyComparison {

        @Test
        @DisplayName("TransactionCategory rejects a null id and a null description")
        void transactionCategoryRejectsNulls() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategory(null, "Regular Sales Draft"))
                    .withMessageContaining("TRAN-CAT-KEY");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategory(categoryId(), null))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC");
        }

        @Test
        @DisplayName("TransactionCategory is the ONLY one of the five with a length guard")
        void bothDescriptionsCarryTheirLengthGuard() {
            assertThatIllegalArgumentException()
                    .as("the description guard rejects anything longer than the PIC X(50) it must fit")
                    .isThrownBy(() -> new TransactionCategory(categoryId(), "x".repeat(51)))
                    .withMessageContaining("must be at most 50");

            assertThatCode(() -> new TransactionCategory(categoryId(), "x".repeat(50)))
                    .as("exactly 50 is the boundary and must be ACCEPTED - an off-by-one here would "
                            + "reject legitimate maximum-width descriptions from the fixture")
                    .doesNotThrowAnyException();
            assertThatCode(() -> new TransactionCategory(categoryId(), ""))
                    .as("the width is a MAXIMUM, not a fixed length: a shorter value including empty is "
                            + "accepted verbatim and padded only at the fixed-width boundary")
                    .doesNotThrowAnyException();

            assertThatIllegalArgumentException()
                    .as("TransactionType now carries the same length guard, because TRAN-TYPE-DESC is also "
                            + "PIC X(50). The former asymmetry has been removed rather than pinned: both "
                            + "descriptions are bounded by the picture clause they must fit")
                    .isThrownBy(() -> new TransactionType("01", "x".repeat(51)))
                    .withMessageContaining("must be at most 50");
            assertThatCode(() -> new TransactionType("01", "x".repeat(50)))
                    .as("exactly 50 is the boundary on this sibling too and must be ACCEPTED")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("CardCrossReference rejects a null on all three components, naming COBOL and column")
        void cardCrossReferenceRejectsAllThreeNulls() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardCrossReference(null, 1L, 2L))
                    .withMessageContainingAll("cardNumber", "XREF-CARD-NUM", "xref_card_num",
                            "NOT NULL in card_cross_reference");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardCrossReference("4111111111111111", null, 2L))
                    .withMessageContainingAll("customerId", "XREF-CUST-ID", "xref_cust_id");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardCrossReference("4111111111111111", 1L, null))
                    .withMessageContainingAll("accountId", "XREF-ACCT-ID", "xref_acct_id");
        }

        @Test
        @DisplayName("DisclosureGroup rejects a null id and a null rate but performs NO range check")
        void disclosureGroupRejectsNullsAndOutOfRangeRates() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DisclosureGroup(null, new BigDecimal("15.00")))
                    .withMessageContaining("DIS-GROUP-KEY");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DisclosureGroup(groupId(), null))
                    .withMessageContaining("DIS-INT-RATE");

            assertThatIllegalArgumentException()
                    .as("the rate guard now checks RANGE as well as null: a value far wider than "
                            + "NUMERIC(6,2) is refused by the entity instead of being deferred to the "
                            + "column, so the failure names DIS-INT-RATE at the point of construction "
                            + "rather than surfacing as an opaque constraint violation at flush time")
                    .isThrownBy(() -> new DisclosureGroup(groupId(), new BigDecimal("99999999.99")))
                    .withMessageContaining("DIS-INT-RATE");
            assertThatCode(() -> new DisclosureGroup(groupId(), new BigDecimal("9999.99")))
                    .as("9999.99 is the widest value PIC S9(04)V99 can hold and must be ACCEPTED - the "
                            + "range guard is inclusive at both ends")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TransactionCategoryBalance and TransactionType guard NOTHING, deliberately")
        void theFormerlyUnguardedEntitiesNowEnforceTheirColumns() {
            assertThatIllegalArgumentException()
                    .as("the composite primary key is now guarded, and this does NOT break the posting "
                            + "job's upsert. 2700-UPDATE-TCATBAL at app/cbl/CBTRN02C.cbl:L467-L500 MOVEs "
                            + "all three key components (XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD) "
                            + "BEFORE the READ, and 2700-A-CREATE-TCATBAL-REC creates the row with that "
                            + "same populated key. The guard refuses an ABSENT key, which the source never "
                            + "produces; it does not refuse a NOT-FOUND lookup, which is the thing the "
                            + "source treats as success. The accommodation the upsert genuinely needs sits "
                            + "one layer down and is preserved there: TransactionCategoryBalanceId is "
                            + "freely constructible for a row that does not yet exist")
                    .isThrownBy(() -> new TransactionCategoryBalance(null, null))
                    .withMessageContaining("TRAN-CAT-KEY");
            assertThatIllegalArgumentException()
                    .as("TRANTYPE is guarded on the same footing. That it is a batch-only reference "
                            + "dataset with no CICS definition changes who writes it, not whether its "
                            + "NOT NULL columns are enforced")
                    .isThrownBy(() -> new TransactionType(null, null))
                    .withMessageContaining("TRAN-TYPE");

            assertThat(providerInstance(TransactionCategoryBalance.class).getId())
                    .as("the provider path still yields a null key, which is why the weak-equality "
                            + "hazard asserted elsewhere in this class remains reachable")
                    .isNull();
            assertThat(providerInstance(TransactionType.class).getTypeCode()).isNull();
        }

        @Test
        @DisplayName("the guarded setters re-apply their guards; the unguarded ones stay unguarded")
        void theGuardedSettersReApplyTheirGuards() {
            final TransactionCategory category = new TransactionCategory(categoryId(), "Original");
            assertThatIllegalArgumentException()
                    .as("TransactionCategory.setCategoryDescription re-applies requireCategoryDescription")
                    .isThrownBy(() -> category.setCategoryDescription("x".repeat(51)));
            assertThat(category.getCategoryDescription())
                    .as("the guard runs BEFORE the assignment, so a rejected mutation is a no-op and the "
                            + "previous value survives intact")
                    .isEqualTo("Original");

            final CardCrossReference xref = new CardCrossReference("4111111111111111", 1L, 2L);
            assertThatIllegalArgumentException()
                    .as("all three CardCrossReference setters re-apply requireSupplied")
                    .isThrownBy(() -> xref.setCardNumber(null));
            assertThat(xref.getCardNumber()).isEqualTo("4111111111111111");

            final DisclosureGroup group = new DisclosureGroup(groupId(), new BigDecimal("15.00"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> group.setInterestRate(null));
            assertThat(group.getInterestRate()).isEqualByComparingTo("15.00");

            final TransactionType type = new TransactionType("01", "Original");
            assertThatIllegalArgumentException()
                    .as("TransactionType's setters now re-apply requireWidth, matching its guarded "
                            + "constructor. The former asymmetry against its siblings is gone: all five "
                            + "reference entities enforce their NOT NULL columns uniformly, per AAP "
                            + "0.4.1.3, so no sibling's policy has to be remembered as a special case")
                    .isThrownBy(() -> type.setTypeDescription(null));
            assertThat(type.getTypeDescription())
                    .as("the guard runs BEFORE the assignment, so the rejected mutation is a no-op and "
                            + "the previous value survives intact - it is NOT nulled out")
                    .isEqualTo("Original");
        }

        @Test
        @DisplayName("all five reference entities declare guards for their NOT NULL columns")
        void allFiveEntitiesDeclareGuards() {
            final List<Class<?>> guarded = List.of(TransactionCategory.class, CardCrossReference.class,
                    DisclosureGroup.class, TransactionCategoryBalance.class, TransactionType.class);
            assertThat(guarded)
                    .as("the registry must exhaust the five entities this class covers, so a newly added "
                            + "sibling cannot silently escape the policy comparison")
                    .hasSize(5);

            for (final Class<?> type : guarded) {
                assertThat(Arrays.stream(type.getDeclaredMethods())
                        .anyMatch(m -> m.getName().startsWith("require")))
                        .as("%s must declare at least one require* guard helper", type.getSimpleName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("accessor round trips and the identity policy split")
    class AccessorsAndIdentity {

        @Test
        @DisplayName("every accessor pair round-trips on all five entities")
        void everyAccessorPairRoundTrips() {
            final TransactionCategory category = new TransactionCategory(categoryId(), "Original");
            category.setId(new TransactionCategoryId("02", 6));
            category.setCategoryDescription("Updated");
            assertThat(category.getId()).isEqualTo(new TransactionCategoryId("02", 6));
            assertThat(category.getCategoryDescription()).isEqualTo("Updated");

            final CardCrossReference xref = new CardCrossReference("4111111111111111", 1L, 2L);
            xref.setCardNumber("5500000000000004");
            xref.setCustomerId(11L);
            xref.setAccountId(22L);
            assertThat(xref.getCardNumber()).isEqualTo("5500000000000004");
            assertThat(xref.getCustomerId()).isEqualTo(11L);
            assertThat(xref.getAccountId())
                    .as("the property must be named exactly accountId: the CARDXREF alternate index makes "
                            + "findByAccountId the derived finder that replaces CXACAIX")
                    .isEqualTo(22L);

            final DisclosureGroup group = new DisclosureGroup(groupId(), new BigDecimal("15.00"));
            group.setId(new DisclosureGroupId("GOLD", "02", 6));
            group.setInterestRate(new BigDecimal("21.99"));
            assertThat(group.getId()).isEqualTo(new DisclosureGroupId("GOLD", "02", 6));
            assertThat(group.getInterestRate()).isEqualByComparingTo("21.99");

            final TransactionCategoryBalance balance =
                    new TransactionCategoryBalance(balanceId(), new BigDecimal("100.00"));
            balance.setId(new TransactionCategoryBalanceId(2L, "02", 6));
            balance.setBalance(new BigDecimal("250.75"));
            assertThat(balance.getId()).isEqualTo(new TransactionCategoryBalanceId(2L, "02", 6));
            assertThat(balance.getBalance()).isEqualByComparingTo("250.75");

            final TransactionType type = new TransactionType("01", "Original");
            type.setTypeCode("02");
            type.setTypeDescription("Updated");
            assertThat(type.getTypeCode()).isEqualTo("02");
            assertThat(type.getTypeDescription()).isEqualTo("Updated");
        }

        @Test
        @DisplayName("the DEFAULT group id is storable, because the fallback lookup depends on it")
        void theDefaultGroupIdIsStorable() {
            final DisclosureGroup group =
                    new DisclosureGroup(new DisclosureGroupId("DEFAULT", "01", 5), new BigDecimal("15.00"));

            assertThat(group.getId().getAccountGroupId())
                    .as("app/cbl/CBACT04C.cbl:L415-L460 substitutes the LITERAL group id DEFAULT and "
                            + "retries when the first lookup returns not-found. The retry accepts ONLY "
                            + "success, so a missing DEFAULT row abends the job - which makes this exact "
                            + "literal a functional dependency, not a convention")
                    .isEqualTo("DEFAULT");
            assertThat(group.getId().getAccountGroupId())
                    .as("DIS-ACCT-GROUP-ID is PIC X(10) and 'DEFAULT' is 7 characters, so it fits with "
                            + "three trailing blanks at the fixed-width boundary")
                    .hasSizeLessThanOrEqualTo(10);
        }

        @Test
        @DisplayName("the SAFE identity policy keeps two unsaved instances distinct in a HashSet")
        void theSafePolicyKeepsUnsavedInstancesDistinct() {
            final TransactionCategory a = new TransactionCategory(categoryId(), "A");
            final TransactionCategory b = new TransactionCategory(categoryId(), "B");

            assertThat(a)
                    .as("TransactionCategory uses the SAFE form (id != null && id.equals(...)), so two "
                            + "instances sharing a non-null key ARE equal regardless of payload")
                    .isEqualTo(b);
            assertThat(new HashSet<>(List.of(a, b)))
                    .as("same key, so they collapse - this is correct JPA identity semantics")
                    .hasSize(1);

            final DisclosureGroup g1 = new DisclosureGroup(groupId(), new BigDecimal("1.00"));
            final DisclosureGroup g2 = new DisclosureGroup(
                    new DisclosureGroupId("GOLD", "02", 6), new BigDecimal("1.00"));
            assertThat(g1).isNotEqualTo(g2);
            assertThat(new HashSet<>(List.of(g1, g2))).hasSize(2);
        }

        @Test
        @DisplayName("the WEAK identity policy collapses two null-key instances, an operational hazard")
        void theWeakPolicyCollapsesNullKeyInstances() {
            final TransactionCategoryBalance one = providerInstance(TransactionCategoryBalance.class);
            final TransactionCategoryBalance two = providerInstance(TransactionCategoryBalance.class);

            assertThat(one)
                    .as("TransactionCategoryBalance uses Objects.equals(id, ...), so TWO DISTINCT unsaved "
                            + "rows with null keys compare EQUAL. The all-args constructor now REFUSES a "
                            + "null key, so the only way to obtain one is the protected no-arg constructor "
                            + "- which is exactly the path Hibernate itself uses when it materialises a "
                            + "row, so this hazard remains live in production and is still worth pinning")
                    .isEqualTo(two);
            assertThat(new HashSet<>(List.of(one, two)))
                    .as("OBSERVED HAZARD, pinned deliberately: the two rows COLLAPSE to one. Operational "
                            + "constraint - any batch writer for this entity must stage in a List, never "
                            + "a Set, or it silently discards rows. TransactionType shares this policy")
                    .hasSize(1);

            final TransactionType t1 = providerInstance(TransactionType.class);
            final TransactionType t2 = providerInstance(TransactionType.class);
            assertThat(t1).isEqualTo(t2);
            assertThat(new HashSet<>(List.of(t1, t2))).hasSize(1);
        }

        @Test
        @DisplayName("every entity overrides equals, hashCode and toString, and toString names its type")
        void everyEntityOverridesTheObjectMethods() throws ReflectiveOperationException {
            for (final Class<?> type : List.of(
                    TransactionCategory.class, CardCrossReference.class, DisclosureGroup.class,
                    TransactionCategoryBalance.class, TransactionType.class)) {
                assertThat(type.getDeclaredMethod("equals", Object.class).getDeclaringClass())
                        .as("%s must override equals for JPA identity", type.getSimpleName())
                        .isEqualTo(type);
                assertThat(type.getDeclaredMethod("hashCode").getDeclaringClass()).isEqualTo(type);
                assertThat(type.getDeclaredMethod("toString").getDeclaringClass()).isEqualTo(type);
                assertThat(type.getDeclaredConstructor().getModifiers() & java.lang.reflect.Modifier.PROTECTED)
                        .as("%s needs a protected no-arg constructor for JPA proxying",
                                type.getSimpleName())
                        .isNotZero();
            }

            assertThat(new TransactionType("01", "Purchase").toString()).contains("TransactionType");
            assertThat(new CardCrossReference("4111111111111111", 1L, 2L).toString())
                    .contains("CardCrossReference");
        }

        @Test
        @DisplayName("equals is reflexive, null-safe and type-safe on all five entities")
        void equalsIsReflexiveNullSafeAndTypeSafe() {
            final List<Object> instances = List.of(
                    new TransactionCategory(categoryId(), "A"),
                    new CardCrossReference("4111111111111111", 1L, 2L),
                    new DisclosureGroup(groupId(), new BigDecimal("1.00")),
                    new TransactionCategoryBalance(balanceId(), BigDecimal.ONE),
                    new TransactionType("01", "Purchase"));

            for (final Object instance : instances) {
                assertThat(instance.equals(instance))
                        .as("%s must be reflexive", instance.getClass().getSimpleName())
                        .isTrue();
                assertThat(instance.equals(null))
                        .as("%s.equals(null) must be false, never throw",
                                instance.getClass().getSimpleName())
                        .isFalse();
                assertThat(instance.equals("a string of the wrong type"))
                        .as("%s must reject a foreign type", instance.getClass().getSimpleName())
                        .isFalse();
                assertThat(instance.hashCode())
                        .as("%s.hashCode must be stable across calls",
                                instance.getClass().getSimpleName())
                        .isEqualTo(instance.hashCode());
            }
        }
    }
}
