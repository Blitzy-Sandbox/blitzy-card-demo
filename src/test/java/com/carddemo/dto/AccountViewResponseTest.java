package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.assertJsonNumberIsPlain;
import static com.carddemo.dto.DtoTestSupport.assertPanMaskedLast4;
import static com.carddemo.dto.DtoTestSupport.assertScale;
import static com.carddemo.dto.DtoTestSupport.fromJson;
import static com.carddemo.dto.DtoTestSupport.objectMapper;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.toJson;
import static com.carddemo.dto.DtoTestSupport.validate;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountViewResponse}, the REST payload for the
 * <strong>Account View</strong> use case (CICS transaction {@code CAVW}). The
 * DTO flattens three frozen COBOL constructs (referenced by source SHA
 * {@code 27d6c6f}, never copied into the target): the BMS screen
 * {@code app/cpy-bms/COACTVW.CPY}, the account record
 * {@code app/cpy/CVACT01Y.cpy} (ACCOUNT-RECORD, RECLN&nbsp;300), and the
 * customer record {@code app/cpy/CVCUS01Y.cpy} (CUSTOMER-RECORD, RECLN&nbsp;500).
 *
 * <p>These tests pin the migration fidelity contracts that make the DTO a
 * faithful, REST-safe stand-in for what the 3270 operator saw on the account
 * view panel. Each concern is exercised as its own {@link Nested} group:</p>
 * <ol>
 *   <li><strong>JSON round-trip</strong> &mdash; a fully-populated response
 *       survives a serialize/deserialize cycle unchanged and exposes exactly the
 *       thirty mapped account + customer keys.</li>
 *   <li><strong>Decimal fidelity (AAP &sect;0.8.2)</strong> &mdash; every one of
 *       the five {@code PIC S9(10)V99} money fields
 *       ({@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT},
 *       {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-CURR-CYC-CREDIT},
 *       {@code ACCT-CURR-CYC-DEBIT}) is a {@link BigDecimal} that serializes as a
 *       plain, scale-2 literal (for example {@code 5000.00}, never {@code 5.0E3})
 *       and is never a floating-point type.</li>
 *   <li><strong>Date contract</strong> &mdash; the {@code X(10)} date fields map
 *       to {@link LocalDate} and serialize as ISO {@code yyyy-MM-dd} strings, not
 *       numeric arrays or epochs.</li>
 *   <li><strong>Customer fields &amp; FICO</strong> &mdash; {@code CUST-SSN} is a
 *       masked {@link String} (decision log D-023), {@code CUST-FICO-CREDIT-SCORE}
 *       is an {@link Integer}, and numeric COBOL keys such as {@code ACCT-ID}
 *       stay {@link String} to preserve fixed width and leading zeros.</li>
 *   <li><strong>Version passthrough</strong> &mdash; the JPA {@code @Version}
 *       echo ({@link Long}) round-trips unchanged for optimistic-lock reuse
 *       (AAP &sect;0.8.4).</li>
 * </ol>
 *
 * <p>The suite is framework-free: it uses JUnit&nbsp;5 and AssertJ with the
 * shared {@link DtoTestSupport} helpers (which mirror the production
 * serialization contract) and loads no Spring context or Testcontainers.</p>
 */
class AccountViewResponseTest {

    // ---------------------------------------------------------------------
    // Shared fixture values. Every value is deliberately within the DTO's
    // declared @Size limits so the fully-populated fixture is also a valid
    // Bean-Validation instance. All identifiers and the SSN are synthetic.
    // ---------------------------------------------------------------------

    /** {@code ACCT-ID PIC 9(11)} kept as a String; exactly eleven characters with leading zeros. */
    private static final String ACCOUNT_ID = "00000000123";

    /** Fixed width (characters) of {@code ACCT-ID}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)}. */
    private static final String ACTIVE_STATUS = "Y";

    /** Fixed decimal scale of every {@code PIC S9(10)V99} money field. */
    private static final int MONEY_SCALE = 2;

    /** {@code ACCT-CURR-BAL} plain, scale-2 literal. */
    private static final String CURRENT_BALANCE_LITERAL = "1234.56";

    /** {@code ACCT-CREDIT-LIMIT} plain, scale-2 literal. */
    private static final String CREDIT_LIMIT_LITERAL = "5000.00";

    /** {@code ACCT-CASH-CREDIT-LIMIT} plain, scale-2 literal. */
    private static final String CASH_CREDIT_LIMIT_LITERAL = "1000.00";

    /** {@code ACCT-CURR-CYC-CREDIT} plain, scale-2 literal. */
    private static final String CURRENT_CYCLE_CREDIT_LITERAL = "250.00";

    /** {@code ACCT-CURR-CYC-DEBIT} plain, scale-2 literal. */
    private static final String CURRENT_CYCLE_DEBIT_LITERAL = "2000.00";

    /** {@code ACCT-OPEN-DATE PIC X(10)}. */
    private static final LocalDate OPEN_DATE = LocalDate.of(2020, 1, 15);

    /** {@code ACCT-EXPIRAION-DATE PIC X(10)} (legacy copybook misspelling retained). */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2027, 12, 31);

    /** {@code ACCT-REISSUE-DATE PIC X(10)}. */
    private static final LocalDate REISSUE_DATE = LocalDate.of(2024, 6, 1);

    /** {@code ACCT-GROUP-ID PIC X(10)}. */
    private static final String ACCOUNT_GROUP_ID = "GOLD001";

    /** JPA {@code @Version} echo (optimistic-lock token). */
    private static final Long VERSION = 7L;

    /** {@code CUST-ID PIC 9(09)} kept as a String; exactly nine characters with leading zeros. */
    private static final String CUSTOMER_ID = "000000456";

    /** {@code CUST-FIRST-NAME PIC X(25)}. */
    private static final String FIRST_NAME = "JOHN";

    /** {@code CUST-MIDDLE-NAME PIC X(25)}. */
    private static final String MIDDLE_NAME = "QUINCY";

    /** {@code CUST-LAST-NAME PIC X(25)}. */
    private static final String LAST_NAME = "PUBLIC";

    /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    private static final String ADDRESS_LINE_1 = "123 MAIN STREET";

    /** {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    private static final String ADDRESS_LINE_2 = "APT 4B";

    /** {@code CUST-ADDR-LINE-3 PIC X(50)} &mdash; the screen "city" (ACSCITY). */
    private static final String CITY = "SEATTLE";

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    private static final String STATE_CODE = "WA";

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    private static final String COUNTRY_CODE = "USA";

    /** {@code CUST-ADDR-ZIP PIC X(10)}. */
    private static final String ZIP_CODE = "98101";

    /** {@code CUST-PHONE-NUM-1 PIC X(15)}. */
    private static final String PHONE_1 = "206-555-0100";

    /** {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    private static final String PHONE_2 = "206-555-0199";

    /**
     * Synthetic {@code CUST-SSN PIC 9(09)} supplied to the constructor. It is not a
     * real Social Security Number; the canonical constructor masks it on the way in.
     */
    private static final String RAW_SSN = "123456789";

    /** The masked form the DTO must store and serialize for {@link #RAW_SSN} (D-023). */
    private static final String MASKED_SSN = "*****6789";

    /** The four SSN digits that remain visible after masking. */
    private static final String SSN_LAST4 = "6789";

    /** Fixed width (characters) of {@code CUST-SSN}, preserved by masking. */
    private static final int SSN_WIDTH = 9;

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    private static final String GOVT_ID = "DL-1234567";

    /** {@code CUST-DOB-YYYY-MM-DD PIC X(10)}. */
    private static final LocalDate DATE_OF_BIRTH = LocalDate.of(1985, 3, 20);

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    private static final String EFT_ACCOUNT_ID = "EFT0001234";

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
    private static final String PRIMARY_CARD_IND = "Y";

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} within the 300&ndash;850 domain. */
    private static final Integer FICO_SCORE = 720;

    /** The five {@code PIC S9(10)V99} money component names, in record order. */
    private static final String[] MONEY_FIELDS = {
        "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit"
    };

    /** The four {@code X(10)} date component names. */
    private static final String[] DATE_FIELDS = {
        "openDate", "expirationDate", "reissueDate", "dateOfBirth"
    };

    /**
     * Every JSON key the DTO must expose, one per mapped record component (order is
     * irrelevant here because the assertion compares membership regardless of order).
     */
    private static final String[] EXPECTED_JSON_KEYS = {
        "accountId", "activeStatus", "currentBalance", "creditLimit", "cashCreditLimit",
        "currentCycleCredit", "currentCycleDebit", "openDate", "expirationDate", "reissueDate",
        "accountGroupId", "version", "customerId", "firstName", "middleName",
        "lastName", "addressLine1", "addressLine2", "city", "stateCode",
        "countryCode", "zipCode", "phoneNumber1", "phoneNumber2", "ssn",
        "govtIssuedId", "dateOfBirth", "eftAccountId", "primaryCardHolderIndicator", "ficoScore"
    };

    // ---------------------------------------------------------------------
    // Fixture factories and reflection helper.
    // ---------------------------------------------------------------------

    /**
     * Builds a fully-populated response with caller-supplied money values, reusing
     * the shared field constants for every other component. Keeping the five money
     * inputs parameterized lets the money tests probe scale normalization without
     * repeating the thirty-argument constructor call.
     *
     * @param currentBalance    ACCT-CURR-BAL
     * @param creditLimit       ACCT-CREDIT-LIMIT
     * @param cashCreditLimit   ACCT-CASH-CREDIT-LIMIT
     * @param currentCycleCredit ACCT-CURR-CYC-CREDIT
     * @param currentCycleDebit ACCT-CURR-CYC-DEBIT
     * @return a populated {@link AccountViewResponse}
     */
    private static AccountViewResponse withMoney(
            BigDecimal currentBalance,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit) {
        return new AccountViewResponse(
                ACCOUNT_ID,
                ACTIVE_STATUS,
                currentBalance,
                creditLimit,
                cashCreditLimit,
                currentCycleCredit,
                currentCycleDebit,
                OPEN_DATE,
                EXPIRATION_DATE,
                REISSUE_DATE,
                ACCOUNT_GROUP_ID,
                VERSION,
                CUSTOMER_ID,
                FIRST_NAME,
                MIDDLE_NAME,
                LAST_NAME,
                ADDRESS_LINE_1,
                ADDRESS_LINE_2,
                CITY,
                STATE_CODE,
                COUNTRY_CODE,
                ZIP_CODE,
                PHONE_1,
                PHONE_2,
                RAW_SSN,
                GOVT_ID,
                DATE_OF_BIRTH,
                EFT_ACCOUNT_ID,
                PRIMARY_CARD_IND,
                FICO_SCORE);
    }

    /**
     * A fully-populated, valid response whose five money fields carry the canonical
     * plain scale-2 sample literals.
     *
     * @return a populated {@link AccountViewResponse}
     */
    private static AccountViewResponse fullyPopulated() {
        return withMoney(
                new BigDecimal(CURRENT_BALANCE_LITERAL),
                new BigDecimal(CREDIT_LIMIT_LITERAL),
                new BigDecimal(CASH_CREDIT_LIMIT_LITERAL),
                new BigDecimal(CURRENT_CYCLE_CREDIT_LITERAL),
                new BigDecimal(CURRENT_CYCLE_DEBIT_LITERAL));
    }

    /**
     * Locates the record component of {@link AccountViewResponse} with the given
     * name, for type-level assertions via {@link Class#getRecordComponents()}.
     *
     * @param name the record component name to find
     * @return the matching {@link RecordComponent}
     */
    private static RecordComponent componentNamed(String name) {
        for (RecordComponent component : AccountViewResponse.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError(
                "AccountViewResponse has no record component named '" + name + "'");
    }

    @Nested
    @DisplayName("JSON round-trip")
    class RoundTripJson {

        @Test
        @DisplayName("a fully-populated response round-trips unchanged "
                + "(scale-2 money and the masked SSN are both stable)")
        void roundTripsWithoutLoss() {
            AccountViewResponse original = fullyPopulated();

            AccountViewResponse restored = roundTrip(original, AccountViewResponse.class);

            // This DTO masks the SSN and normalizes money in its canonical
            // constructor, so `original` already carries the masked SSN and scale-2
            // money. Both transforms are idempotent and scale-stable, so the
            // round-tripped instance equals the original by value (record equality).
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("serialized JSON exposes exactly the thirty mapped account + customer keys")
        void exposesAllExpectedJsonKeys() {
            String json = toJson(fullyPopulated());

            Map<String, Object> tree = fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(tree.keySet()).containsExactlyInAnyOrder(EXPECTED_JSON_KEYS);
        }
    }

    @Nested
    @DisplayName("Money: PLAIN notation and scale 2 for all five S9(10)V99 fields")
    class MoneyPrecision {

        @Test
        @DisplayName("all five money fields serialize as plain scale-2 literals (never scientific)")
        void allFiveMoneyFieldsSerializePlainScaleTwo() {
            AccountViewResponse dto = fullyPopulated();

            String json = toJson(dto);

            // PLAIN literal, never scientific notation (for example 5000.00, not 5.0E3).
            assertJsonNumberIsPlain(json, "currentBalance", CURRENT_BALANCE_LITERAL);
            assertJsonNumberIsPlain(json, "creditLimit", CREDIT_LIMIT_LITERAL);
            assertJsonNumberIsPlain(json, "cashCreditLimit", CASH_CREDIT_LIMIT_LITERAL);
            assertJsonNumberIsPlain(json, "currentCycleCredit", CURRENT_CYCLE_CREDIT_LITERAL);
            assertJsonNumberIsPlain(json, "currentCycleDebit", CURRENT_CYCLE_DEBIT_LITERAL);

            // Each in-memory value carries the fixed V99 scale of 2.
            assertScale(dto.currentBalance(), MONEY_SCALE);
            assertScale(dto.creditLimit(), MONEY_SCALE);
            assertScale(dto.cashCreditLimit(), MONEY_SCALE);
            assertScale(dto.currentCycleCredit(), MONEY_SCALE);
            assertScale(dto.currentCycleDebit(), MONEY_SCALE);
        }

        @Test
        @DisplayName("the canonical constructor normalizes any money input to the V99 scale of 2")
        void normalizesMoneyScaleToTwo() {
            // Supply values whose natural scale is not 2; the constructor must
            // setScale(2, HALF_UP) so the DTO always carries PIC S9(10)V99 precision.
            AccountViewResponse dto = withMoney(
                    new BigDecimal("5"),      // scale 0 -> 5.00
                    new BigDecimal("10.1"),   // scale 1 -> 10.10
                    new BigDecimal("0.005"),  // scale 3 -> 0.01 (HALF_UP)
                    new BigDecimal("250"),    // scale 0 -> 250.00
                    new BigDecimal("2000"));  // scale 0 -> 2000.00

            assertScale(dto.currentBalance(), MONEY_SCALE);
            assertScale(dto.creditLimit(), MONEY_SCALE);
            assertScale(dto.cashCreditLimit(), MONEY_SCALE);
            assertScale(dto.currentCycleCredit(), MONEY_SCALE);
            assertScale(dto.currentCycleDebit(), MONEY_SCALE);

            assertThat(dto.currentBalance().toPlainString()).isEqualTo("5.00");
            assertThat(dto.creditLimit().toPlainString()).isEqualTo("10.10");
            assertThat(dto.cashCreditLimit().toPlainString()).isEqualTo("0.01");
        }

        @Test
        @DisplayName("every money component is BigDecimal, never a floating-point type")
        void allFiveMoneyComponentsAreBigDecimal() {
            for (String field : MONEY_FIELDS) {
                Class<?> type = componentNamed(field).getType();
                assertThat(type)
                        .as("money component '%s' must be BigDecimal", field)
                        .isEqualTo(BigDecimal.class);
                assertThat(type)
                        .as("money component '%s' must never be a floating-point type", field)
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
        }
    }

    @Nested
    @DisplayName("Dates: ISO yyyy-MM-dd strings, never arrays or epochs")
    class DateContract {

        @Test
        @DisplayName("all date fields serialize as ISO textual strings")
        void datesSerializeAsIsoStrings() throws JsonProcessingException {
            String json = toJson(fullyPopulated());
            JsonNode tree = objectMapper().readTree(json);

            for (String field : DATE_FIELDS) {
                JsonNode node = tree.get(field);
                assertThat(node).as("date field '%s' must be present", field).isNotNull();
                assertThat(node.isTextual())
                        .as("date field '%s' must serialize as a JSON string, not an array or number",
                                field)
                        .isTrue();
                assertThat(node.asText())
                        .as("date field '%s' must be ISO yyyy-MM-dd", field)
                        .matches("\\d{4}-\\d{2}-\\d{2}");
            }
        }

        @Test
        @DisplayName("each date serializes to its exact ISO literal")
        void datesHaveExpectedIsoValues() throws JsonProcessingException {
            String json = toJson(fullyPopulated());
            JsonNode tree = objectMapper().readTree(json);

            assertThat(tree.get("openDate").asText()).isEqualTo("2020-01-15");
            assertThat(tree.get("expirationDate").asText()).isEqualTo("2027-12-31");
            assertThat(tree.get("reissueDate").asText()).isEqualTo("2024-06-01");
            assertThat(tree.get("dateOfBirth").asText()).isEqualTo("1985-03-20");
        }

        @Test
        @DisplayName("every date component is java.time.LocalDate")
        void dateComponentsAreLocalDate() {
            for (String field : DATE_FIELDS) {
                assertThat(componentNamed(field).getType())
                        .as("date component '%s' must be LocalDate", field)
                        .isEqualTo(LocalDate.class);
            }
        }
    }

    @Nested
    @DisplayName("Customer fields: SSN masking, FICO, and String identifiers")
    class CustomerFieldsAndFico {

        @Test
        @DisplayName("ssn is a masked String (D-023), never serialized as a number")
        void ssnIsMaskedStringNeverNumeric() throws JsonProcessingException {
            AccountViewResponse dto = fullyPopulated();

            // Decision-log D-023: the canonical constructor masks CUST-SSN to its
            // last four digits, so a full nine-digit SSN can never be exposed. The
            // masked value keeps the String type and the 9-character record width;
            // only the concealed leading digits differ from the legacy screen.
            assertThat(dto.ssn()).isEqualTo(MASKED_SSN).hasSize(SSN_WIDTH);
            assertPanMaskedLast4(dto.ssn(), SSN_LAST4);

            JsonNode node = objectMapper().readTree(toJson(dto)).get("ssn");
            assertThat(node.isTextual()).as("ssn must serialize as a JSON string").isTrue();
            assertThat(node.isNumber()).as("ssn must never serialize as a JSON number").isFalse();
            assertThat(node.asText()).isEqualTo(MASKED_SSN);
        }

        @Test
        @DisplayName("the ssn component is a String, never a numeric type")
        void ssnComponentIsString() {
            assertThat(componentNamed("ssn").getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("ficoScore is an Integer that round-trips unchanged")
        void ficoScoreIsIntegerAndRoundTrips() throws JsonProcessingException {
            AccountViewResponse dto = fullyPopulated();

            assertThat(componentNamed("ficoScore").getType()).isEqualTo(Integer.class);

            JsonNode node = objectMapper().readTree(toJson(dto)).get("ficoScore");
            assertThat(node.isNumber()).as("ficoScore must serialize as a JSON number").isTrue();
            assertThat(node.asInt()).isEqualTo(FICO_SCORE);

            assertThat(roundTrip(dto, AccountViewResponse.class).ficoScore()).isEqualTo(FICO_SCORE);
        }

        @Test
        @DisplayName("accountId stays a String, preserving its 11-digit width and leading zeros")
        void accountIdIsStringPreservingWidth() throws JsonProcessingException {
            AccountViewResponse dto = fullyPopulated();

            assertThat(componentNamed("accountId").getType()).isEqualTo(String.class);

            JsonNode node = objectMapper().readTree(toJson(dto)).get("accountId");
            assertThat(node.isTextual())
                    .as("accountId must serialize as a JSON string to preserve leading zeros")
                    .isTrue();
            assertThat(node.asText()).isEqualTo(ACCOUNT_ID).hasSize(ACCOUNT_ID_WIDTH);

            assertThat(roundTrip(dto, AccountViewResponse.class).accountId())
                    .isEqualTo(ACCOUNT_ID)
                    .hasSize(ACCOUNT_ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("Version: optimistic-lock token passthrough")
    class VersionPassthrough {

        @Test
        @DisplayName("version (Long) round-trips unchanged and serializes as a number")
        void versionRoundTripsUnchanged() throws JsonProcessingException {
            AccountViewResponse dto = fullyPopulated();

            assertThat(componentNamed("version").getType()).isEqualTo(Long.class);

            JsonNode node = objectMapper().readTree(toJson(dto)).get("version");
            assertThat(node.isNumber()).as("version must serialize as a JSON number").isTrue();
            assertThat(node.asLong()).isEqualTo(VERSION);

            assertThat(roundTrip(dto, AccountViewResponse.class).version()).isEqualTo(VERSION);
        }
    }

    @Nested
    @DisplayName("Bean Validation constraints")
    class BeanValidation {

        @Test
        @DisplayName("a fully-populated response satisfies every declared @Size constraint")
        void fullyPopulatedIsValid() {
            assertThat(validate(fullyPopulated())).isEmpty();
        }
    }
}
