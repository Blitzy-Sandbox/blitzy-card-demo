/*
 * ******************************************************************
 * Program     : ConfigurationContractTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test
 * Function    : Pins the two conversion parsers COTRN02C uses asymmetrically, the
 *               edited display mask, the AID navigation tokens, and the fail-fast
 *               persistence contract that keeps the migrations owning the schema.
 * Source      : app/cbl/COTRN02C.cbl:L204,L218 (plain numeric conversion for the
 *               account id and card number), :L383,L456 (currency-aware conversion
 *               for the amount), :L58-L59 (the edited mask) @ 7756d89
 * Source      : app/cpy/CSSTRPFY.cpy (YYYY-STORE-PFKEY, EIBAID tokens) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt (the physical layout the migrations own)
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.config.JpaConfig;
import com.cardemo.config.WebConfig;
import com.cardemo.exception.ValidationException;
import com.cardemo.service.admin.UserUpdateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link WebConfig} and {@link JpaConfig}, the two configuration classes that carry behaviour
 * rather than only wiring.
 *
 * <h2>What it does</h2>
 *
 * <p>The centre of this class is the <strong>two-parser asymmetry</strong> of
 * {@code app/cbl/COTRN02C.cbl}. That program deliberately uses two different numeric conversions: the plain
 * one for the account identifier at {@code :L204} and the card number at {@code :L218}, and the
 * <em>currency-aware</em> one for the amount at {@code :L383} and {@code :L456}. The distinction is
 * behavioural, not stylistic - the currency-aware form tolerates a currency symbol and separators that the
 * plain form rejects. Collapsing the two into one parser breaks parity in one of two directions depending on
 * which survives: a single lenient parser accepts identifiers the legacy system refuses, and a single strict
 * parser refuses amounts it accepts. Both directions are asserted here, against the same inputs, so the
 * asymmetry cannot quietly disappear.
 *
 * <p>The <strong>edited display mask</strong> is pinned alongside it. {@code :L58-L59} declares a signed field
 * of eight integer digits and two decimals, so the round trip through the screen field is lossy in a specific,
 * reproducible way: a magnitude that needs nine integer digits wraps rather than overflowing. That is what the
 * legacy field did, and it is asserted rather than corrected.
 *
 * <p>{@link JpaConfig} is included because its guard is a <strong>fail-fast security and correctness
 * boundary</strong>, not a preference. It refuses to start unless the migrations own the schema
 * ({@code ddl-auto=validate}), the provider is time-zone pinned, and SQL logging is off - the last because
 * statement and bind-parameter logging would put the customer social security number and the seeded password
 * hashes into the log stream. Each refusal is asserted individually, because a guard that only checked the
 * first key would let the rest drift.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run alone with {@code ./mvnw -B -ntp -o test -Dtest=ConfigurationContractTest -Djacoco.skip=true}, or with
 * the unit tier via {@code ./mvnw -B -ntp test}. The {@code -Dtest} separator is a comma, never a plus.
 *
 * <p>No Spring context is started. Both classes are constructed directly - {@link WebConfig}'s converters are
 * public static nested types, and {@link JpaConfig} takes its nine values as constructor arguments - so the
 * behaviour is reachable without a context, and the property <em>binding</em> is left to the integration tier
 * which boots a real one.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Nine persistence keys participate, each supplied literally to the constructor rather than read from a
 * profile: {@code spring.jpa.hibernate.ddl-auto} (must be {@code validate}),
 * {@code spring.jpa.properties.hibernate.jdbc.time_zone} (must be {@code UTC}),
 * {@code spring.jpa.open-in-view} and {@code spring.jpa.show-sql} (must be false), and the five Flyway keys.
 * All default to empty in production, which the guard treats as unconfigured rather than as a permissive
 * default.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in the asymmetry group means the two parsers converged. Restore the distinction; which one
 *       survived determines whether identifiers or amounts now diverge.</li>
 *   <li>A failure in the mask group means the wrap behaviour was "fixed" into an overflow error. The legacy
 *       field wrapped, and the report echo is compared against it.</li>
 *   <li>A failure in the persistence-guard group means a key stopped being enforced. The two most serious are
 *       {@code show-sql} - which would log personal data - and {@code ddl-auto}, which would let the provider
 *       reshape a table away from its copybook record layout.</li>
 *   <li>A failure in the navigation group means the attention-identifier vocabulary moved. It is deliberately
 *       <em>not</em> a shared type on {@link com.cardemo.config.WebConfig}: an application-wide action enum
 *       and its request-parameter converter were removed because nothing bound them, and because the corpus
 *       has no single vocabulary to bind - {@code app/cpy/CSSTRPFY.cpy} is copied into a
 *       {@code PROCEDURE DIVISION}, so each program's arms are that program's own. The group asserts both
 *       halves of that: the absence of the shared type, and the presence of a per-conversation enum carrying
 *       exactly the arms its own source distinguishes.</li>
 *   </ul>
 */
@DisplayName("WebConfig and JpaConfig - conversion parsers, the edited mask and the persistence guard")
class ConfigurationContractTest {

    private static final WebConfig.StrictIdentifierConverter IDENTIFIERS =
            new WebConfig.StrictIdentifierConverter();

    private static final WebConfig.CurrencyAwareAmountConverter AMOUNTS =
            new WebConfig.CurrencyAwareAmountConverter();

    private static final WebConfig.EditedAmountPrinter PRINTER = new WebConfig.EditedAmountPrinter();

    /** A fully compliant persistence configuration, from which each test varies exactly one value. */
    private static JpaConfig compliantJpaConfig() {
        return new JpaConfig("validate", "false", "false", "UTC", "true", "false", "true", "true", "false");
    }

    @Nested
    @DisplayName("the two-parser asymmetry of COTRN02C")
    class TwoParserAsymmetry {

        @ParameterizedTest
        @CsvSource({
            "1,            1",
            "00000000001,  1",
            "0000000000000042, 42",
            "9999999999999999, 9999999999999999",
        })
        @DisplayName("the strict parser accepts digits and strips leading zeros")
        void theStrictParserAcceptsDigits(final String input, final long expected) {
            assertThat(IDENTIFIERS.convert(input)).isEqualTo(Long.valueOf(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {"$100", "1,000", "1 000", "12.34", "-1", "+1", "1a", "abc", "١٢٣"})
        @DisplayName("the strict parser REFUSES everything the currency-aware one tolerates")
        void theStrictParserRefusesCurrencyForms(final String input) {
            assertThatExceptionOfType(ValidationException.class)
                    .as("app/cbl/COTRN02C.cbl:L204 and :L218 use the PLAIN conversion for the account id and "
                            + "the card number, so a lenient parser here would accept input the legacy "
                            + "program refuses")
                    .isThrownBy(() -> IDENTIFIERS.convert(input));
        }

        @Test
        @DisplayName("an identifier longer than sixteen significant digits is refused")
        void anOverlongIdentifierIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> IDENTIFIERS.convert("12345678901234567"));
        }

        @Test
        @DisplayName("leading zeros do not count towards the sixteen-digit ceiling")
        void leadingZerosDoNotCountTowardsTheCeiling() {
            assertThat(IDENTIFIERS.convert("0000000000000000000000001"))
                    .as("a zero-padded PIC X(16) field is still a one")
                    .isEqualTo(Long.valueOf(1L));
        }

        @ParameterizedTest
        @CsvSource({
            "100.00,     100.00",
            "$100.00,    100.00",
            "'1,000.00', 1000.00",
            "'$1,234.56', 1234.56",
            "-50.00,     -50.00",
            "50.00-,     -50.00",
            "+50.00,     50.00",
        })
        @DisplayName("the currency-aware parser tolerates symbols, separators and trailing signs")
        void theCurrencyAwareParserToleratesCurrencyForms(final String input, final String expected) {
            assertThat(AMOUNTS.convert(input))
                    .as("app/cbl/COTRN02C.cbl:L383 and :L456 use the CURRENCY-AWARE conversion for the "
                            + "amount, so a strict parser here would refuse input the legacy program accepts")
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("a doubled sign is refused by the currency-aware parser")
        void aDoubledSignIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> AMOUNTS.convert("-100.00-"));
        }

        @Test
        @DisplayName("both parsers answer null for a blank, so an optional parameter stays optional")
        void bothParsersAnswerNullForBlank() {
            assertThat(IDENTIFIERS.convert("")).isNull();
            assertThat(IDENTIFIERS.convert("   ")).isNull();
            assertThat(IDENTIFIERS.convert(null)).isNull();
            assertThat(AMOUNTS.convert("")).isNull();
            assertThat(AMOUNTS.convert("   ")).isNull();
            assertThat(AMOUNTS.convert(null)).isNull();
        }

        @Test
        @DisplayName("the two parsers genuinely disagree on the same input, which is the whole point")
        void theTwoParsersDisagreeOnTheSameInput() {
            final String currencyForm = "$1,234.56";

            assertThat(AMOUNTS.convert(currencyForm)).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThatExceptionOfType(ValidationException.class)
                    .as("if this ever stops throwing, the two parsers have converged and one of the two "
                            + "COBOL conversion intrinsics has been lost")
                    .isThrownBy(() -> IDENTIFIERS.convert(currencyForm));
        }
    }

    @Nested
    @DisplayName("the edited display mask of COTRN02C:L58-L59")
    class EditedMask {

        @Test
        @DisplayName("the mask constants match the PIC clause exactly")
        void theMaskConstantsMatchThePicClause() {
            assertThat(WebConfig.AMOUNT_EDITED_MASK).isEqualTo("+99999999.99");
            assertThat(WebConfig.AMOUNT_MASK_INTEGER_DIGITS).isEqualTo(8);
            assertThat(WebConfig.AMOUNT_SCALE).isEqualTo(2);
            assertThat(WebConfig.AMOUNT_EDITED_LENGTH).isEqualTo(12);
            assertThat(WebConfig.AMOUNT_ROUNDING_MODE)
                    .as("no financial rounding in this project may be HALF_UP")
                    .isEqualTo(RoundingMode.HALF_EVEN);
        }

        @ParameterizedTest
        @CsvSource({
            "100.00,   +00000100.00",
            "-100.00,  -00000100.00",
            "0.00,     +00000000.00",
            "0.005,    +00000000.00",
            "0.015,    +00000000.02",
        })
        @DisplayName("the printer renders a mandatory sign, eight integer digits and two decimals")
        void thePrinterRendersTheLegacyMask(final String amount, final String expected) {
            assertThat(PRINTER.print(new BigDecimal(amount), Locale.ROOT))
                    .isEqualTo(expected)
                    .hasSize(WebConfig.AMOUNT_EDITED_LENGTH);
        }

        @Test
        @DisplayName("a null amount renders empty rather than the four characters n-u-l-l")
        void aNullAmountRendersEmpty() {
            assertThat(PRINTER.print(null, Locale.ROOT)).isEmpty();
        }

        @Test
        @DisplayName("the render is locale-independent, so a comma locale cannot change the wire format")
        void theRenderIsLocaleIndependent() {
            final BigDecimal amount = new BigDecimal("1234.56");

            assertThat(PRINTER.print(amount, Locale.GERMANY))
                    .as("a locale-sensitive separator would change the emitted record bytes")
                    .isEqualTo(PRINTER.print(amount, Locale.ROOT));
        }
    }

    @Nested
    @DisplayName("the AID navigation vocabulary of CSSTRPFY, and where it lives")
    class NavigationTokens {

        /**
         * The six arms {@code app/cpy/CSSTRPFY.cpy} distinguishes, in the order {@code YYYY-STORE-PFKEY}
         * evaluates {@code EIBAID}: the enter key, the four function keys the corpus binds, and the
         * {@code WHEN OTHER} arm that reports an invalid key and writes nothing.
         */
        private static final List<String> COUSR02C_ARMS =
                List.of("ENTER", "PF3", "PF4", "PF5", "PF12", "OTHER");

        @Test
        @DisplayName("no shared navigation vocabulary is published by the web configuration")
        void theWebConfigurationPublishesNoNavigationVocabulary() {
            assertThat(WebConfig.class.getDeclaredClasses())
                    .as("a single application-wide action enum was removed because the converter that bound "
                            + "it was unreachable; re-adding it would restore dead code, and it would also "
                            + "flatten six per-screen vocabularies into one that fits none of them")
                    .noneSatisfy(nested -> assertThat(nested.getSimpleName()).contains("Navigation"));
        }

        @Test
        @DisplayName("the vocabulary lives with the conversation that owns it, one enum per screen")
        void eachConversationOwnsItsOwnVocabulary() {
            assertThat(UserUpdateService.AttentionIdentifier.class.getEnclosingClass())
                    .as("app/cpy/CSSTRPFY.cpy is copied into a PROCEDURE DIVISION, so its arms are a property "
                            + "of the program that copies it, not of a shared configuration class")
                    .isEqualTo(UserUpdateService.class);
        }

        @Test
        @DisplayName("the six CSSTRPFY arms are declared, in source order, with nothing invented")
        void theSixArmsAreDeclaredInSourceOrder() {
            assertThat(UserUpdateService.AttentionIdentifier.values())
                    .extracting(Enum::name)
                    .as("app/cbl/COUSR02C.cbl:109-127 distinguishes exactly these arms and no others")
                    .containsExactlyElementsOf(COUSR02C_ARMS);
        }

        @Test
        @DisplayName("an unmapped key is an arm of the enum, not an exception, exactly as WHEN OTHER is")
        void anUnmappedKeyIsAnArmRatherThanAnException() {
            assertThat(UserUpdateService.AttentionIdentifier.valueOf("OTHER"))
                    .as("app/cbl/COUSR02C.cbl:127 reports a message and continues; it does not abend, so no "
                            + "refusal type belongs on this path")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("the fail-fast persistence contract")
    class PersistenceContract {

        @Test
        @DisplayName("a compliant configuration verifies without complaint")
        void aCompliantConfigurationVerifies() {
            compliantJpaConfig().verifyPersistenceContract();
            assertThat(compliantJpaConfig().persistenceContractGuard())
                    .as("the guard is exposed as an InitializingBean so it runs at startup, not on first use")
                    .isNotNull();
        }

        @Test
        @DisplayName("ddl-auto other than validate is refused, so the provider cannot reshape a table")
        void ddlAutoMustBeValidate() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("update or create-drop would let the provider move a column away from its copybook "
                            + "record layout, which no test would notice")
                    .isThrownBy(() -> new JpaConfig("update", "false", "false", "UTC", "true", "false",
                            "true", "true", "false").verifyPersistenceContract());
        }

        @Test
        @DisplayName("show-sql true is refused, because it would log SSNs and password hashes")
        void showSqlMustBeFalse() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("bind-parameter logging is the single most direct route from this configuration to "
                            + "personal data in the log stream")
                    .isThrownBy(() -> new JpaConfig("validate", "false", "true", "UTC", "true", "false",
                            "true", "true", "false").verifyPersistenceContract());
        }

        @Test
        @DisplayName("a non-UTC provider time zone is refused")
        void theTimeZoneMustBeUtc() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new JpaConfig("validate", "false", "false", "Europe/London", "true",
                            "false", "true", "true", "false").verifyPersistenceContract());
        }

        @Test
        @DisplayName("open-in-view true is refused")
        void openInViewMustBeFalse() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new JpaConfig("validate", "true", "false", "UTC", "true", "false",
                            "true", "true", "false").verifyPersistenceContract());
        }

        @Test
        @DisplayName("each of the five Flyway guarantees is enforced individually")
        void eachFlywayGuaranteeIsEnforced() {
            // enabled must be true - without migration there is no schema to validate against
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> new JpaConfig(
                    "validate", "false", "false", "UTC", "false", "false", "true", "true", "false")
                    .verifyPersistenceContract());
            // baseline-on-migrate must be false - it would adopt an unknown schema as the starting point
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> new JpaConfig(
                    "validate", "false", "false", "UTC", "true", "true", "true", "true", "false")
                    .verifyPersistenceContract());
            // validate-on-migrate must be true - an edit to an applied migration must be reported
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> new JpaConfig(
                    "validate", "false", "false", "UTC", "true", "false", "false", "true", "false")
                    .verifyPersistenceContract());
            // clean-disabled must be true - clean destroys every object in the schema
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> new JpaConfig(
                    "validate", "false", "false", "UTC", "true", "false", "true", "false", "false")
                    .verifyPersistenceContract());
            // out-of-order must be false - the seed migration needs the indexes the previous one creates
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> new JpaConfig(
                    "validate", "false", "false", "UTC", "true", "false", "true", "true", "true")
                    .verifyPersistenceContract());
        }

        @Test
        @DisplayName("an unconfigured key is refused rather than defaulted permissively")
        void anUnconfiguredKeyIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("every property defaults to empty, so an absent key must fail loudly")
                    .isThrownBy(() -> new JpaConfig("", "false", "false", "UTC", "true", "false", "true",
                            "true", "false"));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new JpaConfig("validate", "", "false", "UTC", "true", "false", "true",
                            "true", "false"));
        }
    }
}
