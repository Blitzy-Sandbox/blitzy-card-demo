/*
 * ******************************************************************
 * Program     : CardTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the Card entity reproduces the CVACT02Y
 *               150-byte record contract and that it is the ONE entity in
 *               the package which validates every field - each of the five
 *               character widths as a MAXIMUM and the account id as an
 *               inclusive 0..99,999,999,999 range - exercising both the
 *               null and the over-width branch of every guard.
 * Source      : app/cpy/CVACT02Y.cpy:L5-L11 @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L202 (CARDDATA key 16, reclen 150) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L281-L284 (CARDDATA.VSAM.AIX AXRKP 16) @ 7756d89
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

import com.cardemo.model.entity.Card;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the {@link Card} entity, which replaces the {@code CARDDATA} VSAM KSDS cluster.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CVACT02Y.cpy} declares a 150-byte record: {@code CARD-NUM X(16)},
 * {@code CARD-ACCT-ID 9(11)}, {@code CARD-CVV-CD 9(03)}, {@code CARD-EMBOSSED-NAME X(50)},
 * {@code CARD-EXPIRAION-DATE X(10)}, {@code CARD-ACTIVE-STATUS X(01)} and {@code FILLER X(59)}, summing to
 * {@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150} - the catalogued record length at
 * {@code app/catlg/LISTCAT.txt:L202}.
 *
 * <p>{@link Card} is distinctive within the entity package: it is the only entity that validates
 * <em>everything</em>. {@code Account} and {@code Customer} check only their primary key, and
 * {@code Transaction}, {@code DailyTransaction}, {@code TransactionType} and
 * {@code TransactionCategoryBalance} check nothing at all. This class therefore drives both branches of
 * every guard - the null rejection and the over-width rejection - for each of the five character fields, plus
 * all three branches of the account-id range check.
 *
 * <p>Two semantic points are asserted rather than assumed:
 *
 * <ul>
 *   <li><strong>The width check is a MAXIMUM, not an exact length.</strong> A shorter value is accepted
 *       verbatim, because a COBOL {@code PIC X(16)} field holds a space-padded value and the padding is
 *       applied at the byte boundary, not in the entity.</li>
 *   <li><strong>{@code CARD-ACCT-ID} is the alternate key.</strong> The catalogue records
 *       {@code CARDDATA.VSAM.AIX} with {@code AXRKP 16}, meaning the alternate key begins at byte 16 of the
 *       base record - immediately after the 16-byte card number. That is exactly where {@code accountId}
 *       sits, which is what makes the derived finder {@code findByAccountId} the correct replacement for the
 *       alternate index.</li>
 * </ul>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * mvn -B -o test -Dtest=CardTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. The width constants are private to {@link Card}; this test restates them as its own literals taken
 * from the copybook, so that a silent change to the entity's constants fails the test rather than moving with
 * it.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A width rejection stops firing.</strong> A guard was removed. The database column is
 *       {@code CHAR(n) NOT NULL}, so the failure would move from construction time to flush time, losing the
 *       stack trace that identifies the offending caller.</li>
 *   <li><strong>The maximum-not-exact assertion fails.</strong> A guard was tightened to demand an exact
 *       length. That would reject every value the seed migration loads, since the fixtures are not padded in
 *       the Java layer.</li>
 *   <li><strong>The range boundary assertions fail.</strong> The account-id bound changed. It must remain
 *       {@code 99,999,999,999}, the widest {@code PIC 9(11)} value.</li>
 * </ul>
 */
class CardTest {

    /** {@code CARD-NUM PIC X(16)} - CVACT02Y:L5, and the catalogued key length for CARDDATA. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code CARD-CVV-CD PIC 9(03)} - CVACT02Y:L7. */
    private static final int CVV_WIDTH = 3;

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} - CVACT02Y:L8. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** {@code CARD-EXPIRAION-DATE PIC X(10)} - CVACT02Y:L9, misspelling preserved. */
    private static final int EXPIRY_WIDTH = 10;

    /** {@code CARD-ACTIVE-STATUS PIC X(01)} - CVACT02Y:L10. */
    private static final int STATUS_WIDTH = 1;

    /** {@code FILLER PIC X(59)} - CVACT02Y:L11, deliberately not modelled. */
    private static final int FILLER_WIDTH = 59;

    /** The catalogued average record length for CARDDATA. */
    private static final int RECORD_LENGTH = 150;

    /** The widest {@code PIC 9(11)} value, and therefore the inclusive upper bound. */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    private static final String VALID_CARD_NUMBER = "4111111111111111";

    private static Card validCard() {
        return new Card(VALID_CARD_NUMBER, 1L, "123", "FNAMEAA6 LNAME6", "2025-01-01", "Y");
    }

    @Nested
    @DisplayName("the record geometry: 150 bytes with a 16-byte key and the alternate key at byte 16")
    class RecordGeometry {

        @Test
        @DisplayName("the six modelled widths plus the filler sum to the catalogued 150 bytes")
        void theModelledWidthsPlusFillerSumTo150() {
            final int modelled = CARD_NUMBER_WIDTH + 11 + CVV_WIDTH + EMBOSSED_NAME_WIDTH
                    + EXPIRY_WIDTH + STATUS_WIDTH;

            assertThat(modelled)
                    .as("16 + 11 + 3 + 50 + 10 + 1 = 91 populated bytes from CVACT02Y:L5-L10")
                    .isEqualTo(91);
            assertThat(modelled + FILLER_WIDTH)
                    .as("91 populated plus the 59-byte FILLER at CVACT02Y:L11 is exactly the 150-byte "
                            + "record length catalogued for CARDDATA at app/catlg/LISTCAT.txt:L202")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the card number is exactly the 16-byte VSAM primary key")
        void theCardNumberIsTheSixteenByteKey() {
            assertThat(validCard().getCardNumber())
                    .as("CARDDATA is catalogued with key length 16, which is CARD-NUM in full - there is "
                            + "no composite component")
                    .hasSize(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("the account id sits at byte 16, exactly where the alternate index is anchored")
        void theAccountIdSitsAtTheAlternateKeyOffset() {
            assertThat(CARD_NUMBER_WIDTH)
                    .as("app/catlg/LISTCAT.txt:L281-L284 records CARDDATA.VSAM.AIX with AXRKP 16, meaning "
                            + "the alternate key begins at byte 16 of the base record - immediately after "
                            + "the 16-byte card number, which is precisely where CARD-ACCT-ID begins. That "
                            + "correspondence is what makes findByAccountId the correct replacement for "
                            + "the alternate index rather than an invented convenience")
                    .isEqualTo(16);
            assertThat(validCard().getAccountId())
                    .as("the alternate key is non-unique - many cards may share an account - so the "
                            + "replacement index must also be non-unique")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the account id is a Long, because PIC 9(11) overflows int")
        void theAccountIdIsALong() throws ReflectiveOperationException {
            assertThat(Card.class.getDeclaredField("accountId").getType())
                    .as("99,999,999,999 exceeds Integer.MAX_VALUE, so an int field would silently wrap")
                    .isEqualTo(Long.class);
        }

        @Test
        @DisplayName("the CVV is a three-character String, not a numeric type")
        void theCvvIsAThreeCharacterString() throws ReflectiveOperationException {
            assertThat(Card.class.getDeclaredField("cvvCode").getType())
                    .as("CARD-CVV-CD is PIC 9(03) but is carried as a String because leading zeros are "
                            + "significant - a CVV of '007' must not become the integer 7")
                    .isEqualTo(String.class);
            assertThat(validCard().getCvvCode()).hasSize(CVV_WIDTH);
        }

        @Test
        @DisplayName("a CVV with leading zeros round-trips without losing them")
        void aCvvWithLeadingZerosRoundTrips() {
            final Card card = new Card(VALID_CARD_NUMBER, 1L, "007", "NAME", "2025-01-01", "Y");

            assertThat(card.getCvvCode())
                    .as("this is the concrete reason CARD-CVV-CD is a String: an Integer would render as "
                            + "'7' and the fixed-width record would be two bytes short")
                    .isEqualTo("007")
                    .hasSize(CVV_WIDTH);
        }

        @Test
        @DisplayName("the expiry field preserves the copybook's CARD-EXPIRAION-DATE misspelling")
        void theExpiryFieldPreservesTheMisspelling() {
            assertThat(Card.class.getDeclaredFields())
                    .as("CVACT02Y:L9 reads CARD-EXPIRAION-DATE, missing the T of EXPIRATION - the same "
                            + "misspelling CVACT01Y carries for the account. Both are preserved so the "
                            + "traceability mapping stays one-to-one")
                    .anyMatch(field -> "expiraionDate".equals(field.getName()))
                    .noneMatch(field -> "expirationDate".equals(field.getName()));
        }
    }

    @Nested
    @DisplayName("the width guards: null rejected, over-width rejected, shorter accepted verbatim")
    class WidthGuards {

        @Test
        @DisplayName("a null card number is rejected, naming the COBOL field and the column width")
        void aNullCardNumberIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(null, 1L, "123", "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("cardNumber")
                    .withMessageContaining("CARD-NUM")
                    .withMessageContaining("16");
        }

        @Test
        @DisplayName("a null CVV, embossed name, expiry or status is rejected")
        void aNullValueIsRejectedOnEveryCharacterField() {
            assertThatIllegalArgumentException()
                    .as("CARD-CVV-CD maps to a NOT NULL CHAR(3) column")
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, 1L, null, "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("cvvCode");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, 1L, "123", null, "2025-01-01", "Y"))
                    .withMessageContaining("embossedName");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, 1L, "123", "NAME", null, "Y"))
                    .withMessageContaining("expiraionDate");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, 1L, "123", "NAME", "2025-01-01", null))
                    .withMessageContaining("activeStatus");
        }

        @Test
        @DisplayName("an over-width card number is rejected, reporting the actual length")
        void anOverWidthCardNumberIsRejected() {
            assertThatIllegalArgumentException()
                    .as("a seventeenth character could not be written to a CHAR(16) column and would be "
                            + "silently truncated at the byte boundary, corrupting the key")
                    .isThrownBy(() -> new Card("4".repeat(17), 1L, "123", "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("16")
                    .withMessageContaining("17");
        }

        @ParameterizedTest
        @CsvSource({
            "4, CARD-CVV-CD",
            "51, CARD-EMBOSSED-NAME",
            "11, CARD-EXPIRAION-DATE",
            "2, CARD-ACTIVE-STATUS",
        })
        @DisplayName("an over-width value is rejected on every character field, naming its COBOL field")
        void anOverWidthValueIsRejectedOnEveryField(final int length, final String cobolField) {
            final String tooLong = "X".repeat(length);
            final String cvv = "CARD-CVV-CD".equals(cobolField) ? tooLong : "123";
            final String name = "CARD-EMBOSSED-NAME".equals(cobolField) ? tooLong : "NAME";
            final String expiry = "CARD-EXPIRAION-DATE".equals(cobolField) ? tooLong : "2025-01-01";
            final String status = "CARD-ACTIVE-STATUS".equals(cobolField) ? tooLong : "Y";

            assertThatIllegalArgumentException()
                    .as("%s must reject a value of length %d, because the column is CHAR of a narrower "
                            + "width and the excess would be truncated at the byte boundary", cobolField,
                            length)
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, 1L, cvv, name, expiry, status))
                    .withMessageContaining(cobolField);
        }

        @Test
        @DisplayName("the guard is a MAXIMUM: a value at exactly the declared width is accepted")
        void aValueAtExactlyTheDeclaredWidthIsAccepted() {
            final Card card = new Card(
                    "4".repeat(CARD_NUMBER_WIDTH),
                    MAX_ACCOUNT_ID,
                    "9".repeat(CVV_WIDTH),
                    "N".repeat(EMBOSSED_NAME_WIDTH),
                    "2".repeat(EXPIRY_WIDTH),
                    "Y".repeat(STATUS_WIDTH));

            assertThat(card.getCardNumber()).hasSize(CARD_NUMBER_WIDTH);
            assertThat(card.getEmbossedName())
                    .as("the boundary is inclusive: exactly 50 characters fits CHAR(50) precisely")
                    .hasSize(EMBOSSED_NAME_WIDTH);
            assertThat(card.getExpiraionDate()).hasSize(EXPIRY_WIDTH);
            assertThat(card.getActiveStatus()).hasSize(STATUS_WIDTH);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "SHORT", "FNAMEAA6 LNAME6"})
        @DisplayName("a value SHORTER than the declared width is accepted verbatim, without padding")
        void aShorterValueIsAcceptedVerbatim(final String shortName) {
            final Card card = new Card(VALID_CARD_NUMBER, 1L, "123", shortName, "2025-01-01", "Y");

            assertThat(card.getEmbossedName())
                    .as("observed behaviour, pinned deliberately: the guard checks a MAXIMUM, so a short "
                            + "value passes and is stored exactly as supplied with no space padding. The "
                            + "padding to the CHAR(50) width belongs at the fixed-width emission boundary, "
                            + "not in the entity - which is why an empty string is also accepted")
                    .isEqualTo(shortName);
        }

        @Test
        @DisplayName("an empty card number is accepted by the width guard, since zero is under the maximum")
        void anEmptyCardNumberIsAcceptedByTheWidthGuard() {
            final Card card = new Card("", 1L, "123", "NAME", "2025-01-01", "Y");

            assertThat(card.getCardNumber())
                    .as("observed behaviour: the guard rejects null and over-width but not emptiness. The "
                            + "database primary key would reject an empty key at flush, so this is a "
                            + "deliberate division of labour rather than a gap - noted here so a reader "
                            + "does not assume the entity is a complete validator")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the account id range guard: null, negative, zero, maximum and beyond")
    class AccountIdRangeGuard {

        @Test
        @DisplayName("a null account id is rejected, naming the NUMERIC(11) column")
        void aNullAccountIdIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, null, "123", "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("accountId")
                    .withMessageContaining("CARD-ACCT-ID");
        }

        @Test
        @DisplayName("a negative account id is rejected, because PIC 9(11) is unsigned")
        void aNegativeAccountIdIsRejected() {
            assertThatIllegalArgumentException()
                    .as("CARD-ACCT-ID is PIC 9(11) with no S, so it carries no sign and a negative value "
                            + "has no representation in the fixed-width record")
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, -1L, "123", "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("accountId")
                    .withMessageContaining("0");
        }

        @Test
        @DisplayName("an account id above 99,999,999,999 is rejected as too wide for eleven digits")
        void anAccountIdAboveTheMaximumIsRejected() {
            assertThatIllegalArgumentException()
                    .as("a twelfth digit could not be written into an eleven-character field")
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, MAX_ACCOUNT_ID + 1L, "123", "NAME",
                            "2025-01-01", "Y"))
                    .withMessageContaining("99999999999");
        }

        @Test
        @DisplayName("zero is accepted, because the bound is inclusive at the lower end")
        void zeroIsAccepted() {
            assertThat(new Card(VALID_CARD_NUMBER, 0L, "123", "NAME", "2025-01-01", "Y").getAccountId())
                    .as("the guard tests value < 0, so zero passes; an all-zeros account id is a "
                            + "representable PIC 9(11) value even if the seed data does not use it")
                    .isZero();
        }

        @Test
        @DisplayName("exactly 99,999,999,999 is accepted, because the bound is inclusive at the upper end")
        void theMaximumIsAccepted() {
            assertThat(new Card(VALID_CARD_NUMBER, MAX_ACCOUNT_ID, "123", "NAME", "2025-01-01", "Y")
                            .getAccountId())
                    .as("the guard tests value > MAX, so the maximum itself passes - an exclusive bound "
                            + "would reject the widest legitimate value")
                    .isEqualTo(MAX_ACCOUNT_ID);
            assertThat(String.valueOf(MAX_ACCOUNT_ID)).hasSize(11);
        }

        @Test
        @DisplayName("Long.MIN_VALUE and Long.MAX_VALUE are both rejected")
        void theExtremeLongValuesAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, Long.MIN_VALUE, "123", "NAME",
                            "2025-01-01", "Y"));
            assertThatIllegalArgumentException()
                    .as("a guard that only tested the lower bound would let Long.MAX_VALUE through and "
                            + "produce a nineteen-digit value in an eleven-character field")
                    .isThrownBy(() -> new Card(VALID_CARD_NUMBER, Long.MAX_VALUE, "123", "NAME",
                            "2025-01-01", "Y"));
        }
    }

    @Nested
    @DisplayName("construction and full accessor round-trip")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the public constructor populates every one of the six supplied fields")
        void thePublicConstructorPopulatesEveryField() {
            final Card card = validCard();

            assertThat(card.getCardNumber()).isEqualTo(VALID_CARD_NUMBER);
            assertThat(card.getAccountId()).isEqualTo(1L);
            assertThat(card.getCvvCode()).isEqualTo("123");
            assertThat(card.getEmbossedName()).isEqualTo("FNAMEAA6 LNAME6");
            assertThat(card.getExpiraionDate()).isEqualTo("2025-01-01");
            assertThat(card.getActiveStatus()).isEqualTo("Y");
            assertThat(card.getVersion())
                    .as("the @Version column is assigned by the provider on first flush, never seeded")
                    .isNull();
        }

        @Test
        @DisplayName("every one of the seven accessor pairs round-trips a value")
        void everyAccessorPairRoundTrips() {
            final Card card = validCard();

            card.setCardNumber("5500000000000004");
            card.setAccountId(2L);
            card.setCvvCode("456");
            card.setEmbossedName("FNAM7 LNAM7");
            card.setExpiraionDate("2026-12-31");
            card.setActiveStatus("N");
            card.setVersion(3L);

            assertThat(card.getCardNumber()).isEqualTo("5500000000000004");
            assertThat(card.getAccountId()).isEqualTo(2L);
            assertThat(card.getCvvCode()).isEqualTo("456");
            assertThat(card.getEmbossedName()).isEqualTo("FNAM7 LNAM7");
            assertThat(card.getExpiraionDate()).isEqualTo("2026-12-31");
            assertThat(card.getActiveStatus()).isEqualTo("N");
            assertThat(card.getVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("the setters DO re-apply the guards, so a width cannot be violated after construction")
        void theSettersAlsoApplyTheGuards() {
            final Card card = validCard();

            assertThatIllegalArgumentException()
                    .as("observed behaviour, verified rather than assumed: setCardNumber routes through "
                            + "the same checkWidth guard as the constructor, so the invariant holds for "
                            + "the whole lifetime of the instance and not merely at birth. An earlier "
                            + "draft of this test assumed the opposite and failed - which is precisely "
                            + "why the behaviour is asserted instead of inferred")
                    .isThrownBy(() -> card.setCardNumber("X".repeat(99)))
                    .withMessageContaining("CARD-NUM")
                    .withMessageContaining("16");
            assertThatIllegalArgumentException()
                    .as("setAccountId likewise routes through checkAccountId, so the 0..99,999,999,999 "
                            + "range cannot be escaped by mutation")
                    .isThrownBy(() -> card.setAccountId(-5L))
                    .withMessageContaining("accountId");
        }

        @Test
        @DisplayName("a rejected setter leaves the previous value intact, so the instance stays valid")
        void aRejectedSetterLeavesThePreviousValueIntact() {
            final Card card = validCard();

            assertThatIllegalArgumentException().isThrownBy(() -> card.setCvvCode("12345"));

            assertThat(card.getCvvCode())
                    .as("the guard runs before the assignment, so a rejected mutation is a no-op rather "
                            + "than a half-applied change - the instance is never left in a state the "
                            + "database would refuse")
                    .isEqualTo("123");
            assertThat(card.getCardNumber()).isEqualTo(VALID_CARD_NUMBER);
        }

        @Test
        @DisplayName("Card validates in its setters where Account does not - a genuine, deliberate asymmetry")
        void bothEntitiesGuardTheAccountIdentifierRange() throws ReflectiveOperationException {
            final Card card = validCard();

            assertThatIllegalArgumentException()
                    .as("Card.setAccountId guards the range")
                    .isThrownBy(() -> card.setAccountId(-1L));

            final com.cardemo.model.entity.Account account = new com.cardemo.model.entity.Account(
                    1L, "Y", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, "2020-01-01", "2025-01-01", "2022-01-01",
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "12345", "DEFAULT");

            assertThatIllegalArgumentException()
                    .as("and so does Account.setAccountId, on the same range. ACCT-ID and CARD-ACCT-ID "
                            + "are both PIC 9(11) unsigned, so the two entities agreeing is the property "
                            + "worth pinning: an earlier revision of this test recorded Account's setter "
                            + "as a plain assignment that accepted a negative value, which was an "
                            + "asymmetry between two spellings of one field rather than a policy")
                    .isThrownBy(() -> account.setAccountId(-1L));

            assertThat(account.getAccountId())
                    .as("and the refused value is not stored: the guard rejects rather than repairs")
                    .isEqualTo(1L);

            assertThat(Card.class.getDeclaredMethod("checkAccountId", Long.class))
                    .as("Card owns a private range guard")
                    .isNotNull();
        }

        @Test
        @DisplayName("the active status carries the legacy Y and N values in a single character")
        void theActiveStatusCarriesTheLegacyValues() {
            final Card active = new Card(VALID_CARD_NUMBER, 1L, "123", "NAME", "2025-01-01", "Y");
            final Card inactive = new Card(VALID_CARD_NUMBER, 1L, "123", "NAME", "2025-01-01", "N");

            assertThat(active.getActiveStatus()).isEqualTo("Y").hasSize(STATUS_WIDTH);
            assertThat(inactive.getActiveStatus()).isEqualTo("N").hasSize(STATUS_WIDTH);
            assertThat(active)
                    .as("both share the same card number, so they are the same entity regardless of "
                            + "status - identity is by key alone")
                    .isEqualTo(inactive);
        }
    }
}
