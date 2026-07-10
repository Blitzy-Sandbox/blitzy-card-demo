package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.assertJsonNumberIsPlain;
import static com.carddemo.dto.DtoTestSupport.assertScale;
import static com.carddemo.dto.DtoTestSupport.fromJson;
import static com.carddemo.dto.DtoTestSupport.objectMapper;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static com.carddemo.dto.DtoTestSupport.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountUpdateResponse}, the REST payload returned after a
 * <strong>successful</strong> account update (CICS transaction {@code CAUP}). The
 * DTO is the headless translation of the post-update redisplay that the legacy
 * program {@code COACTUPC} rendered on the {@code COACTUP} BMS map once a change
 * was committed &mdash; the refreshed account/customer values plus a confirmation
 * message in the {@code INFOMSG PIC X(45)} field (referenced by source SHA
 * {@code 27d6c6f}: {@code app/cpy-bms/COACTUP.CPY}, {@code app/cpy/CVACT01Y.cpy},
 * {@code app/cpy/CVCUS01Y.cpy}).
 *
 * <p>Rather than duplicate the ~30 account and customer attributes, the response
 * <strong>composes</strong> {@link AccountViewResponse}: the exact same
 * denormalized snapshot the Account View use case returns. These tests therefore
 * focus on the three concerns that composition introduces, each exercised as its
 * own {@link Nested} group:</p>
 * <ol>
 *   <li><strong>Composite JSON round-trip</strong> &mdash; a response wrapping a
 *       fully-populated {@link AccountViewResponse} survives a serialize/deserialize
 *       cycle unchanged and serializes to {@code {"account": { ... }, "message":
 *       ..., "version": ...}} with {@code account} as a nested object.</li>
 *   <li><strong>Nested money fidelity (AAP &sect;0.8.2)</strong> &mdash; the five
 *       {@code PIC S9(10)V99} money fields carried by the <em>nested</em> account
 *       still serialize as plain, scale-2 literals (for example {@code 1234.56},
 *       never {@code 1.23456E3}); the decimal rule must survive nesting.</li>
 *   <li><strong>Version &amp; message</strong> &mdash; the top-level optimistic-lock
 *       {@link AccountUpdateResponse#version() version} ({@link Long}) and the
 *       confirmation {@link AccountUpdateResponse#message() message}
 *       ({@link String}) round-trip unchanged, and the {@code updated(...)}
 *       factories derive the version from the composed snapshot (AAP &sect;0.8.4).</li>
 * </ol>
 *
 * <p>The suite is framework-free: JUnit&nbsp;5 and AssertJ with the shared
 * {@link DtoTestSupport} helpers (which mirror the production serialization
 * contract). It loads no Spring context and no Testcontainers. Construction of the
 * composed {@link AccountViewResponse} mirrors the patterns in
 * {@code AccountViewResponseTest} so the two suites stay consistent.</p>
 */
class AccountUpdateResponseTest {

    // ---------------------------------------------------------------------
    // Shared fixture values for the composed AccountViewResponse snapshot.
    // Every value is within the nested DTO's declared @Size limits and all
    // identifiers / the SSN are synthetic. The set mirrors AccountViewResponseTest
    // so the two suites construct the snapshot identically.
    // ---------------------------------------------------------------------

    /** Fixed decimal scale of every {@code PIC S9(10)V99} money field. */
    private static final int MONEY_SCALE = 2;

    /** {@code ACCT-ID PIC 9(11)} kept as a String; eleven characters with leading zeros. */
    private static final String ACCOUNT_ID = "00000000123";

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)}. */
    private static final String ACTIVE_STATUS = "Y";

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

    /**
     * JPA {@code @Version} optimistic-lock token. It is both the composed
     * snapshot's version and the response's top-level version, matching the
     * production invariant that the top-level token mirrors {@code account.version()}.
     */
    private static final Long VERSION = 7L;

    /** {@code CUST-ID PIC 9(09)} kept as a String; nine characters with leading zeros. */
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
     * real Social Security Number; the nested DTO's canonical constructor masks it
     * on the way in (decision log D-023), and masking is idempotent so the composite
     * still round-trips unchanged.
     */
    private static final String RAW_SSN = "123456789";

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

    /**
     * A caller-supplied confirmation message used to exercise the
     * {@link AccountUpdateResponse#updated(AccountViewResponse, String)} overload
     * and prove that a non-default {@code message} round-trips faithfully.
     */
    private static final String CUSTOM_MESSAGE = "Balance and credit limit updated";

    /** Every top-level JSON key the composite response must expose. */
    private static final String[] EXPECTED_TOP_LEVEL_KEYS = {"account", "message", "version"};

    // ---------------------------------------------------------------------
    // Fixture factories and a literal-preserving nested-object extractor.
    // ---------------------------------------------------------------------

    /**
     * Builds a fully-populated composed snapshot with caller-supplied money values,
     * reusing the shared field constants for every other component. Parameterizing
     * only the five money inputs lets the money tests probe scale fidelity without
     * repeating the thirty-argument constructor call.
     *
     * @param currentBalance     ACCT-CURR-BAL
     * @param creditLimit        ACCT-CREDIT-LIMIT
     * @param cashCreditLimit    ACCT-CASH-CREDIT-LIMIT
     * @param currentCycleCredit ACCT-CURR-CYC-CREDIT
     * @param currentCycleDebit  ACCT-CURR-CYC-DEBIT
     * @return a populated {@link AccountViewResponse}
     */
    private static AccountViewResponse accountWithMoney(
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
     * A fully-populated composed snapshot whose five money fields carry the
     * canonical plain scale-2 sample literals.
     *
     * @return a populated {@link AccountViewResponse}
     */
    private static AccountViewResponse fullyPopulatedAccount() {
        return accountWithMoney(
                new BigDecimal(CURRENT_BALANCE_LITERAL),
                new BigDecimal(CREDIT_LIMIT_LITERAL),
                new BigDecimal(CASH_CREDIT_LIMIT_LITERAL),
                new BigDecimal(CURRENT_CYCLE_CREDIT_LITERAL),
                new BigDecimal(CURRENT_CYCLE_DEBIT_LITERAL));
    }

    /**
     * A fully-populated success response: the canonical snapshot wrapped with the
     * standard {@link AccountUpdateResponse#SUCCESS_MESSAGE} and the shared
     * optimistic-lock {@link #VERSION}.
     *
     * @return a populated {@link AccountUpdateResponse}
     */
    private static AccountUpdateResponse fullyPopulatedResponse() {
        return new AccountUpdateResponse(
                fullyPopulatedAccount(),
                AccountUpdateResponse.SUCCESS_MESSAGE,
                VERSION);
    }

    /**
     * Extracts the verbatim JSON text of a top-level object-valued field, preserving
     * the exact numeric literals emitted by the serializer.
     *
     * <p>This is the key to proving the money rule <em>survives nesting</em>. The
     * raw substring must be used rather than re-serializing a parsed
     * {@code JsonNode} subtree, because a {@code DecimalNode} normalizes away
     * trailing zeros by default in Jackson&nbsp;2.19 (the
     * {@code STRIP_TRAILING_BIGDECIMAL_ZEROES} behaviour), which would turn a
     * plainly-serialized {@code 1234.56} into {@code 1.23456E3}. Walking the
     * document with a streaming {@link JsonParser} and slicing the original text on
     * the token character offsets returns the account object exactly as written, so
     * the shared {@link DtoTestSupport#assertJsonNumberIsPlain} can then assert on
     * the nested money fields as if they were top-level. Only non-deprecated
     * streaming API is used ({@code currentTokenLocation()},
     * {@code currentLocation()}, {@code currentName()}), keeping the file clean
     * under {@code -Xlint:all}.</p>
     *
     * @param json            the full JSON document to inspect
     * @param objectFieldName the name of the top-level object-valued field to slice
     * @return the verbatim JSON text of the named object (from its opening
     *         <code>{</code> to its matching closing <code>}</code>)
     */
    private static String rawObjectFieldJson(String json, String objectFieldName) {
        try (JsonParser parser = objectMapper().getFactory().createParser(json)) {
            int depth = 0;
            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                } else if (token == JsonToken.FIELD_NAME
                        && depth == 1
                        && objectFieldName.equals(parser.currentName())) {
                    JsonToken valueToken = parser.nextToken();
                    assertThat(valueToken)
                            .as("JSON field '%s' must be a nested object", objectFieldName)
                            .isEqualTo(JsonToken.START_OBJECT);
                    // currentTokenLocation() points at the opening '{'; after
                    // skipChildren() the parser sits on the matching '}', and
                    // currentLocation() points just past it. Slicing on these
                    // char offsets yields the object text verbatim.
                    long start = parser.currentTokenLocation().getCharOffset();
                    parser.skipChildren();
                    long end = parser.currentLocation().getCharOffset();
                    return json.substring((int) start, (int) end);
                }
            }
        } catch (IOException e) {
            throw new AssertionError(
                    "Failed to extract nested object '" + objectFieldName + "' from JSON: " + json, e);
        }
        throw new AssertionError(
                "JSON must contain a nested object field named '" + objectFieldName + "': " + json);
    }

    @Nested
    @DisplayName("Composite JSON round-trip")
    class CompositeRoundTrip {

        @Test
        @DisplayName("a response wrapping a fully-populated account round-trips unchanged")
        void compositeRoundTripsWithoutLoss() {
            AccountUpdateResponse original = fullyPopulatedResponse();

            AccountUpdateResponse restored = roundTrip(original, AccountUpdateResponse.class);

            // Record value equality: the nested AccountViewResponse already carries
            // scale-2 money and a masked SSN (both idempotent under its canonical
            // constructor), so the whole composite is stable across the cycle.
            assertThat(restored).isEqualTo(original);
            assertThat(restored.account()).isEqualTo(original.account());
        }

        @Test
        @DisplayName("serialized JSON exposes exactly the account, message and version keys")
        void exposesExactlyTheTopLevelKeys() {
            String json = toJson(fullyPopulatedResponse());

            Map<String, Object> tree = fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(tree.keySet()).containsExactlyInAnyOrder(EXPECTED_TOP_LEVEL_KEYS);
        }

        @Test
        @DisplayName("the account key is a nested JSON object carrying the account attributes")
        void accountKeyIsNestedObject() throws JsonProcessingException {
            String json = toJson(fullyPopulatedResponse());

            JsonNode tree = objectMapper().readTree(json);
            JsonNode accountNode = tree.get("account");

            assertThat(accountNode).as("account node must be present").isNotNull();
            assertThat(accountNode.isObject())
                    .as("account must serialize as a nested JSON object, not a scalar")
                    .isTrue();
            // Prove it is genuinely the composed snapshot, not an empty stand-in.
            assertThat(accountNode.has("accountId")).as("nested account exposes accountId").isTrue();
            assertThat(accountNode.has("currentBalance"))
                    .as("nested account exposes currentBalance")
                    .isTrue();
            assertThat(accountNode.get("accountId").asText()).isEqualTo(ACCOUNT_ID);

            // The sibling top-level scalars remain scalars.
            assertThat(tree.get("message").isTextual()).as("message is a JSON string").isTrue();
            assertThat(tree.get("version").isNumber()).as("version is a JSON number").isTrue();
        }
    }

    @Nested
    @DisplayName("Nested money fidelity: PLAIN notation and scale 2 survive composition")
    class NestedMoneyFidelity {

        @Test
        @DisplayName("all five nested money fields serialize as plain scale-2 literals (never scientific)")
        void nestedMoneySerializesPlainScaleTwo() {
            AccountUpdateResponse response = fullyPopulatedResponse();

            String json = toJson(response);
            // Navigate into the account node: slice the nested object out verbatim so
            // the shared PLAIN assertion reads the nested money fields as depth-1
            // fields while preserving the exact serialized literals.
            String accountJson = rawObjectFieldJson(json, "account");

            assertJsonNumberIsPlain(accountJson, "currentBalance", CURRENT_BALANCE_LITERAL);
            assertJsonNumberIsPlain(accountJson, "creditLimit", CREDIT_LIMIT_LITERAL);
            assertJsonNumberIsPlain(accountJson, "cashCreditLimit", CASH_CREDIT_LIMIT_LITERAL);
            assertJsonNumberIsPlain(accountJson, "currentCycleCredit", CURRENT_CYCLE_CREDIT_LITERAL);
            assertJsonNumberIsPlain(accountJson, "currentCycleDebit", CURRENT_CYCLE_DEBIT_LITERAL);

            // The in-memory nested values also carry the fixed V99 scale of 2.
            assertScale(response.account().currentBalance(), MONEY_SCALE);
            assertScale(response.account().creditLimit(), MONEY_SCALE);
            assertScale(response.account().cashCreditLimit(), MONEY_SCALE);
            assertScale(response.account().currentCycleCredit(), MONEY_SCALE);
            assertScale(response.account().currentCycleDebit(), MONEY_SCALE);
        }

        @Test
        @DisplayName("the nested account serializes identically whether composed or standalone")
        void nestedAccountMatchesStandaloneSerialization() {
            AccountUpdateResponse response = fullyPopulatedResponse();

            String extractedAccountJson = rawObjectFieldJson(toJson(response), "account");
            String standaloneAccountJson = toJson(response.account());

            // Composition must not alter the account's serialized form; equal text
            // confirms the nested money (and every other field) is byte-for-byte the
            // same as the Account View payload the client already trusts.
            assertThat(extractedAccountJson).isEqualTo(standaloneAccountJson);
        }
    }

    @Nested
    @DisplayName("Version and message: optimistic-lock token and confirmation passthrough")
    class VersionAndMessage {

        @Test
        @DisplayName("version (Long) round-trips unchanged and serializes as a top-level number")
        void versionRoundTripsAsNumber() throws JsonProcessingException {
            AccountUpdateResponse response = fullyPopulatedResponse();

            JsonNode node = objectMapper().readTree(toJson(response)).get("version");
            assertThat(node.isNumber()).as("version must serialize as a JSON number").isTrue();
            assertThat(node.asLong()).isEqualTo(VERSION);

            assertThat(roundTrip(response, AccountUpdateResponse.class).version()).isEqualTo(VERSION);
        }

        @Test
        @DisplayName("message round-trips unchanged and serializes as a top-level string")
        void messageRoundTripsAsString() throws JsonProcessingException {
            AccountUpdateResponse response = new AccountUpdateResponse(
                    fullyPopulatedAccount(), CUSTOM_MESSAGE, VERSION);

            JsonNode node = objectMapper().readTree(toJson(response)).get("message");
            assertThat(node.isTextual()).as("message must serialize as a JSON string").isTrue();
            assertThat(node.asText()).isEqualTo(CUSTOM_MESSAGE);

            assertThat(roundTrip(response, AccountUpdateResponse.class).message()).isEqualTo(CUSTOM_MESSAGE);
        }

        @Test
        @DisplayName("updated(account) uses the standard success message and mirrors the account version")
        void updatedFactoryDerivesSuccessMessageAndVersion() {
            AccountViewResponse account = fullyPopulatedAccount();

            AccountUpdateResponse response = AccountUpdateResponse.updated(account);

            assertThat(response.message()).isEqualTo(AccountUpdateResponse.SUCCESS_MESSAGE);
            assertThat(response.message()).isEqualTo("Account updated successfully");
            // Optimistic-lock token surfaced at the top level mirrors account.version().
            assertThat(response.version()).isEqualTo(account.version()).isEqualTo(VERSION);
            assertThat(response.account()).isEqualTo(account);
        }

        @Test
        @DisplayName("updated(account, message) carries the caller message and still mirrors the version")
        void updatedFactoryOverloadCarriesCustomMessage() {
            AccountViewResponse account = fullyPopulatedAccount();

            AccountUpdateResponse response = AccountUpdateResponse.updated(account, CUSTOM_MESSAGE);

            assertThat(response.message()).isEqualTo(CUSTOM_MESSAGE);
            assertThat(response.version()).isEqualTo(account.version());
            assertThat(roundTrip(response, AccountUpdateResponse.class)).isEqualTo(response);
        }
    }
}
