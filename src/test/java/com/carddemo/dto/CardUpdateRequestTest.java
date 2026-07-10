package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Framework-free unit tests for {@link CardUpdateRequest}, the REST request body of
 * the CardDemo <em>Card Update</em> transaction (online transaction {@code CCUP},
 * legacy program {@code COCRDUPC}) in the COBOL&rarr;Java&nbsp;25 / Spring&nbsp;Boot
 * 3.5.11 migration.
 *
 * <p>The DTO is the idiomatic Spring translation of the input fields of the
 * {@code COCRDUP} BMS map (symbolic copybook {@code app/cpy-bms/COCRDUP.CPY})
 * carrying the mutable attributes of the {@code CARD-RECORD} layout (copybook
 * {@code app/cpy/CVACT02Y.cpy}, record length 150). Both sources are referenced
 * read-only by commit SHA {@code 27d6c6f}; no COBOL is reproduced here. Its Jakarta
 * Bean-Validation constraints reproduce the fixed-width picture clauses and the BMS
 * field edits, so the REST boundary rejects exactly the malformed input the 3270
 * screen rejected — preserving the interface contract of the migrated transaction:</p>
 * <ul>
 *   <li>{@code accountId} &larr; {@code ACCTSID} / {@code CARD-ACCT-ID 9(11)} —
 *       {@code @NotBlank}, {@code @Size(max = 11)}, {@code @Pattern("\\d{1,11}")}.</li>
 *   <li>{@code cardNumber} &larr; {@code CARDSID} / {@code CARD-NUM X(16)} —
 *       {@code @NotBlank}, {@code @Size(max = 16)}, {@code @Pattern("\\d{1,16}")}.</li>
 *   <li>{@code embossedName} &larr; {@code CRDNAME} / {@code CARD-EMBOSSED-NAME X(50)}
 *       — {@code @Size(max = 50)} only (optional; a null/absent value is legal).</li>
 *   <li>{@code activeStatus} &larr; {@code CRDSTCD} / {@code CARD-ACTIVE-STATUS X(01)}
 *       — {@code @Size(max = 1)} and {@code @Pattern("[YN]")} (optional).</li>
 *   <li>{@code expirationDate} &larr; the composed {@code EXPMON}/{@code EXPYEAR}/
 *       {@code EXPDAY} fields, persisted as {@code CARD-EXPIRAION-DATE X(10)} — an
 *       unconstrained {@link LocalDate} bound from an ISO-8601 string.</li>
 *   <li>{@code version} — the JPA {@code @Version} optimistic-lock value echoed back
 *       on update (preserving the read-then-rewrite concurrency of {@code COCRDUPC});
 *       unconstrained {@link Long}.</li>
 * </ul>
 *
 * <p>Four concerns are exercised, one per phase, plus a diagnostic-safety guard:</p>
 * <ol>
 *   <li><strong>Valid instances</strong> — a well-formed request and the field-width
 *       boundaries produce zero violations, and the optional components accept
 *       {@code null}.</li>
 *   <li><strong>Constraint failures</strong> — every violation is asserted by its
 *       property path ({@link ConstraintViolation#getPropertyPath()}) and by the
 *       offending constraint annotation, so an accidental annotation change breaks
 *       the build.</li>
 *   <li><strong>No CVV</strong> — the record declares no CVV component, so the
 *       {@code CARD-CVV-CD 9(03)} field present in {@code CVACT02Y} can never surface
 *       on this REST-facing DTO.</li>
 *   <li><strong>JSON binding / round-trip</strong> — the request binds from the wire
 *       contract (the ISO date and numeric version included) and round-trips without
 *       loss.</li>
 * </ol>
 *
 * <p>The suite loads no Spring context and uses no database, container, mock, file,
 * or network I/O: it relies solely on the shared programmatic
 * {@link jakarta.validation.Validator} and production-mirroring {@code ObjectMapper}
 * exposed by {@link DtoTestSupport}, so it runs in milliseconds and contributes fast
 * line coverage toward the Gate&nbsp;8 (&ge;80%) JaCoCo threshold. The card numbers
 * and identifiers below are obvious, non-secret test fixtures; design rationale lives
 * in {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("CardUpdateRequest — validation, no-CVV safety, and JSON binding")
class CardUpdateRequestTest {

    /** A valid 11-digit account id at the {@code CARD-ACCT-ID 9(11)} width. */
    private static final String VALID_ACCOUNT_ID = "00000000012";

    /** A valid 16-digit card number (PAN) at the {@code CARD-NUM X(16)} width. */
    private static final String VALID_CARD_NUMBER = "4111111111111111";

    /** A representative embossed name well within the {@code X(50)} width. */
    private static final String VALID_EMBOSSED_NAME = "JOHN Q CARDHOLDER";

    /** An embossed name at the exact {@code CARD-EMBOSSED-NAME X(50)} boundary. */
    private static final String MAX_EMBOSSED_NAME = "A".repeat(50);

    /** A valid single-character active-status flag ({@code Y} = active). */
    private static final String VALID_ACTIVE_STATUS = "Y";

    /** A valid future expiration date bound from the ISO string {@code 2027-12-31}. */
    private static final LocalDate VALID_EXPIRATION_DATE = LocalDate.of(2027, 12, 31);

    /** A valid optimistic-lock version echoed back on update. */
    private static final Long VALID_VERSION = 0L;

    /**
     * A full, unmasked PAN with a distinctive last four ({@link #MASK_TEST_LAST4}).
     * Used exclusively as a negative-assertion needle: it must never appear in
     * {@link CardUpdateRequest#toString()} output.
     */
    private static final String MASK_TEST_CARD_NUMBER = "4111111111113456";

    /** The only portion of {@link #MASK_TEST_CARD_NUMBER} a masked PAN may reveal. */
    private static final String MASK_TEST_LAST4 = "3456";

    /**
     * Builds the canonical, fully-valid sample request shared across the tests.
     *
     * @return a fully-populated {@link CardUpdateRequest} that passes every constraint
     */
    private static CardUpdateRequest validRequest() {
        return new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                VALID_CARD_NUMBER,
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);
    }

    // ------------------------------------------------------------------
    // Phase 1 — valid instances (programmatic Bean-Validation Validator).
    // A well-formed request, the exact COBOL field-width boundaries, and
    // null optional components must all produce zero violations.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A fully-populated, well-formed request has zero violations")
    void validRequestHasNoViolations() {
        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(validRequest());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Values at the exact COBOL field widths (11/16/50/1) produce no violations")
    void maximumWidthValuesAreAccepted() {
        // accountId 11 digits, cardNumber 16 digits, embossedName 50 chars, and a
        // single-character active status are each at their legacy picture-clause width.
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                VALID_CARD_NUMBER,
                MAX_EMBOSSED_NAME,
                "N",
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Null optional fields (embossedName, activeStatus, expirationDate, version) are valid")
    void nullOptionalFieldsAreValid() {
        // embossedName carries only @Size; activeStatus's @Size and @Pattern both skip
        // null per the Bean-Validation spec; expirationDate and version are unconstrained.
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                VALID_CARD_NUMBER,
                null,
                null,
                null,
                null);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 2 — constraint failures, asserted by property path + the
    // offending constraint annotation.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An embossedName longer than 50 characters violates @Size(max=50) on 'embossedName'")
    void embossedNameLongerThanFiftyViolatesSize() {
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                VALID_CARD_NUMBER,
                "A".repeat(51),
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("embossedName");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        });
    }

    @ParameterizedTest(name = "activeStatus [{0}] violates @Pattern")
    @ValueSource(strings = {"X", "A", "1", "y", "n"})
    @DisplayName("A single character outside {Y,N} violates @Pattern on 'activeStatus'")
    void invalidActiveStatusValueViolatesPattern(String invalidStatus) {
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                VALID_CARD_NUMBER,
                VALID_EMBOSSED_NAME,
                invalidStatus,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("activeStatus");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
        });
    }

    @Test
    @DisplayName("A 2-character activeStatus violates BOTH @Size(max=1) and @Pattern on 'activeStatus'")
    void activeStatusLongerThanOneViolatesSizeAndPattern() {
        // "YN" is two characters (over the X(01) width) and does not match the
        // single-character [YN] regex, so both constraints fire on the same path.
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                VALID_CARD_NUMBER,
                VALID_EMBOSSED_NAME,
                "YN",
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("activeStatus"));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class));
    }

    @ParameterizedTest(name = "blank accountId [{0}] violates @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only accountId violates @NotBlank on 'accountId'")
    void blankAccountIdViolatesNotBlank(String blankAccountId) {
        // @NotBlank reproduces the CICS RECEIVE MAP mandatory-field edit. A non-null
        // blank value additionally trips @Pattern, so assert the NotBlank violation is
        // present and that every violation stays on the 'accountId' path.
        CardUpdateRequest request = new CardUpdateRequest(
                blankAccountId,
                VALID_CARD_NUMBER,
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("accountId"));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class));
    }

    @ParameterizedTest(name = "blank cardNumber [{0}] violates @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only cardNumber violates @NotBlank on 'cardNumber'")
    void blankCardNumberViolatesNotBlank(String blankCardNumber) {
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                blankCardNumber,
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("cardNumber"));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class));
    }

    @Test
    @DisplayName("A non-numeric accountId (letters) violates @Pattern on 'accountId'")
    void nonNumericAccountIdViolatesPattern() {
        // Eleven characters (within @Size) but containing a letter: only @Pattern fires.
        CardUpdateRequest request = new CardUpdateRequest(
                "1234567890A",
                VALID_CARD_NUMBER,
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("accountId");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
        });
    }

    @Test
    @DisplayName("A 12-digit accountId violates BOTH @Size(max=11) and @Pattern on 'accountId'")
    void accountIdExceedingElevenViolatesSizeAndPattern() {
        CardUpdateRequest request = new CardUpdateRequest(
                "1".repeat(12),
                VALID_CARD_NUMBER,
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("accountId"));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class));
    }

    @Test
    @DisplayName("A non-numeric cardNumber (letters) violates @Pattern on 'cardNumber'")
    void nonNumericCardNumberViolatesPattern() {
        // Sixteen characters (within @Size) but containing a letter: only @Pattern fires.
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                "1".repeat(15) + "A",
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("cardNumber");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
        });
    }

    @Test
    @DisplayName("A 17-digit cardNumber violates BOTH @Size(max=16) and @Pattern on 'cardNumber'")
    void cardNumberExceedingSixteenViolatesSizeAndPattern() {
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                "1".repeat(17),
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        Set<ConstraintViolation<CardUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("cardNumber"));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class));
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class));
    }

    // ------------------------------------------------------------------
    // Phase 3 — sensitive-data safety. CARD-CVV-CD 9(03) exists in the
    // source card record but must never surface on this REST request.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The request record exposes no CVV component")
    void recordExposesNoCvvComponent() {
        // CARD-CVV-CD 9(03) is present in CVACT02Y but is not an input field on the
        // COCRDUP screen, so it must never reach the request DTO.
        DtoTestSupport.assertNoComponentNamed(CardUpdateRequest.class, "cvv");
    }

    @Test
    @DisplayName("The request record exposes exactly the six documented components")
    void recordExposesExactlyExpectedComponents() {
        // componentNames() lower-cases each record component name. Asserting the exact
        // set reconfirms the intended contract: no cvv, and precisely the six fields
        // the Card Update screen submits.
        assertThat(DtoTestSupport.componentNames(CardUpdateRequest.class))
                .containsExactlyInAnyOrder(
                        "accountid",
                        "cardnumber",
                        "embossedname",
                        "activestatus",
                        "expirationdate",
                        "version");
    }

    // ------------------------------------------------------------------
    // Phase 4 — JSON binding / round-trip. The request must bind from the
    // on-the-wire contract (ISO date + numeric version) and survive a
    // serialize/deserialize cycle with full record equality.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Deserializes a valid JSON body, binding the ISO date and numeric version")
    void deserializesValidJsonBody() {
        String json = """
                {
                  "accountId": "00000000012",
                  "cardNumber": "4111111111111111",
                  "embossedName": "JOHN Q CARDHOLDER",
                  "activeStatus": "Y",
                  "expirationDate": "2027-12-31",
                  "version": 7
                }
                """;

        CardUpdateRequest request = DtoTestSupport.fromJson(json, CardUpdateRequest.class);

        assertThat(request.accountId()).isEqualTo("00000000012");
        assertThat(request.cardNumber()).isEqualTo("4111111111111111");
        assertThat(request.embossedName()).isEqualTo("JOHN Q CARDHOLDER");
        assertThat(request.activeStatus()).isEqualTo("Y");
        assertThat(request.expirationDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(request.version()).isEqualTo(7L);
    }

    @Test
    @DisplayName("A JSON round-trip preserves every component (record equality)")
    void jsonRoundTripPreservesAllComponents() {
        CardUpdateRequest original = validRequest();

        CardUpdateRequest restored = DtoTestSupport.roundTrip(original, CardUpdateRequest.class);

        // A record derives equals() from every component, so equality proves each field
        // survived the serialize/deserialize cycle unchanged.
        assertThat(restored).isEqualTo(original);
        assertThat(restored.expirationDate()).isEqualTo(VALID_EXPIRATION_DATE);
        assertThat(restored.version()).isEqualTo(VALID_VERSION);
    }

    @Test
    @DisplayName("expirationDate serializes as an ISO-8601 string, not a numeric timestamp array")
    void serializedJsonRendersExpirationDateAsIsoString() {
        String json = DtoTestSupport.toJson(validRequest());

        assertThat(json).contains("\"expirationDate\":\"2027-12-31\"");
    }

    // ------------------------------------------------------------------
    // Diagnostic-safety guard — the compiler-generated record toString()
    // is overridden to mask the PAN, so a request never leaks a full card
    // number into a log line, stack trace, or error message.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toString() masks the card number to its last four digits and leaks no full PAN")
    void toStringMasksCardNumber() {
        CardUpdateRequest request = new CardUpdateRequest(
                VALID_ACCOUNT_ID,
                MASK_TEST_CARD_NUMBER,
                VALID_EMBOSSED_NAME,
                VALID_ACTIVE_STATUS,
                VALID_EXPIRATION_DATE,
                VALID_VERSION);

        String rendered = request.toString();

        assertThat(rendered)
                .as("toString() must never leak the full PAN")
                .doesNotContain(MASK_TEST_CARD_NUMBER);
        assertThat(rendered)
                .as("toString() should reveal only the last four PAN digits, behind a mask")
                .contains(MASK_TEST_LAST4)
                .contains("*");
        assertThat(rendered)
                .as("toString() should still expose the non-sensitive accountId")
                .contains(VALID_ACCOUNT_ID);

        // Masking is presentation-only: the accessor still returns the real PAN so the
        // service layer can perform the card lookup/rewrite.
        assertThat(request.cardNumber()).isEqualTo(MASK_TEST_CARD_NUMBER);
    }
}
