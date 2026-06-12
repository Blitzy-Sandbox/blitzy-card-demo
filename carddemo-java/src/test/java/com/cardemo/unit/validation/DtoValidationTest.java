package com.cardemo.unit.validation;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.enums.UserType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JVM behavioural-parity unit test for the CardDemo request <strong>DTOs</strong>
 * under {@code com.cardemo.model.dto} and their <strong>Jakarta Bean Validation</strong>
 * constraints.
 *
 * <p>This test pins the migrated <em>external-interface contract</em>: it proves that the
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x DTOs preserve <em>exactly</em> the field names,
 * field widths and shape-edits of the legacy 3270 BMS symbolic maps. On the mainframe each
 * online screen received its operator keystrokes through a BMS symbolic input map; the
 * {@code COPY CSSETATY} field-edit templates were the COBOL stand-in for declarative
 * field validation. In the migration that role is taken by the {@code @Valid} gate on the
 * REST controllers and the Jakarta {@code @NotBlank}/{@code @Size}/{@code @Pattern}
 * annotations declared on these DTOs (AAP &sect;0.4.2). This class asserts those annotations
 * directly, screen by screen.</p>
 *
 * <h2>Parity target (frozen COBOL baseline at commit SHA {@code 27d6c6f})</h2>
 * <p>The COBOL/BMS source is read-only reference and is <strong>never copied</strong> into
 * this repository (AAP &sect;0.7.2); only its <em>field contract</em> &mdash; the
 * {@code PIC X(n)} widths and the digit-shape edits &mdash; is asserted here. The verified
 * contract sources are:</p>
 * <ul>
 *   <li>{@code app/cpy-bms/COSGN00.CPY} &mdash; sign-on map ({@code USERIDI}/{@code PASSWDI}
 *       {@code PIC X(8)}) &rarr; {@link SignOnRequest}.</li>
 *   <li>{@code app/cpy-bms/COBIL00.CPY} &mdash; bill-payment map ({@code ACTIDINI}
 *       {@code PIC X(11)}, {@code CONFIRMI} {@code PIC X(1)}) &rarr; {@link BillPaymentRequest}.</li>
 *   <li>{@code app/cpy-bms/CORPT00.CPY} &mdash; report-criteria map (three {@code X(1)} type
 *       flags, {@code X(2)}/{@code X(4)} date parts, {@code X(1)} confirm) &rarr;
 *       {@link ReportRequest}.</li>
 *   <li>{@code app/cpy-bms/COUSR01.CPY} &mdash; user-add map ({@code FNAMEI}/{@code LNAMEI}
 *       {@code PIC X(20)}, {@code USERIDI}/{@code PASSWDI} {@code PIC X(8)}) &rarr;
 *       {@link UserSecurityDto}.</li>
 *   <li>{@code app/cpy-bms/COACTUP.CPY} &mdash; account-update map ({@code ACSFNAMI}/
 *       {@code ACSMNAMI}/{@code ACSLNAMI} {@code PIC X(25)}, {@code ACSSTTEI} {@code X(2)},
 *       {@code ACSZIPCI} {@code X(5)}) &rarr; {@link AccountDto}.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; the canonical account-update program whose edit
 *       paragraphs define the field-rule semantics reproduced here (for example
 *       &quot;Account number must be a non zero 11 digit number&quot;).</li>
 * </ul>
 *
 * <h2>Two decisive parity nuances locked by this test</h2>
 * <ol>
 *   <li><strong>Shape-edit vs. value/range-edit are separated.</strong> Bean Validation on a
 *       DTO checks only <em>digit-shape</em> and <em>length</em> &mdash; the BMS/{@code PIC}
 *       editing layer. It does <strong>not</strong> check value ranges: a report
 *       {@code startMonth} of {@code "13"} is two digits and therefore produces
 *       <strong>no</strong> DTO violation; the {@code 1..12} range check (the COBOL
 *       {@code CSUTLDPY}/{@code CEEDAYS} edit, mirrored by &quot;Card expiry month must be
 *       between 1 and 12&quot; in {@code COACTUPC.cbl}) belongs downstream to
 *       {@code service/shared/DateValidationService}. This preserves the COBOL separation of
 *       shape-edit (BMS) from value-edit (CSUTLDPY).</li>
 *   <li><strong>The 20-vs-25 name-length split is preserved.</strong> A <em>user</em> name is
 *       {@code PIC X(20)} ({@code COUSR01}) while a <em>customer/account</em> name is
 *       {@code PIC X(25)} ({@code COACTUP}). A 21-character user {@code firstName} must
 *       therefore violate, whereas the very same 21-character value is legal for an
 *       {@link AccountDto} customer name.</li>
 * </ol>
 *
 * <h2>Why this is a plain JUnit&nbsp;5 test (no Spring)</h2>
 * <p>The constraints are exercised through a <strong>programmatic</strong>
 * {@link jakarta.validation.Validator} obtained from
 * {@link Validation#buildDefaultValidatorFactory()}. There is <em>no</em>
 * {@code @SpringBootTest}, application context, database, AWS, Testcontainers, Mockito or
 * reflection: a DTO is built with its public no-argument constructor and setters, handed to
 * {@link Validator#validate(Object, Class[])}, and the resulting
 * {@link ConstraintViolation} set is asserted by property path. Every assertion encodes the
 * exact BMS/{@code PIC} field rule with no relaxation and no added strictness (AAP
 * &sect;0.7.1, 100% behavioural-parity gate; &sect;0.7.8 zero-warning / no-unsafe-code).</p>
 *
 * @see jakarta.validation.Validator
 * @see com.cardemo.model.dto.SignOnRequest
 * @see com.cardemo.model.dto.UserSecurityDto
 * @see com.cardemo.model.dto.AccountDto
 */
@DisplayName("DTO Jakarta Bean Validation parity (BMS PIC field contracts, SHA 27d6c6f)")
class DtoValidationTest {

    /**
     * The shared {@link ValidatorFactory}. Built once for the whole class in
     * {@link #init()} and released in {@link #close()} so the test makes a single,
     * self-contained, pure-JVM use of the Bean Validation provider (Hibernate Validator,
     * supplied transitively by {@code spring-boot-starter-validation}).
     */
    private static ValidatorFactory factory;

    /**
     * The programmatic {@link Validator} under which every DTO instance is validated. It is
     * obtained from {@link #factory} and shared across all nested test groups; a
     * {@link Validator} is thread-safe and stateless, so a single instance is correct.
     */
    private static Validator validator;

    /**
     * Builds the default validator factory and validator once before any test runs.
     *
     * <p>This is the self-contained programmatic bootstrap mandated by the file
     * specification &mdash; it deliberately avoids a Spring context so the test is a fast,
     * isolated unit test executed by {@code maven-surefire-plugin}.</p>
     */
    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the validator factory after all tests have run, freeing the provider's
     * resources. Guarded against {@code null} so a bootstrap failure cannot mask itself
     * with a {@link NullPointerException} during teardown.
     */
    @AfterAll
    static void close() {
        if (factory != null) {
            factory.close();
        }
    }

    /**
     * Returns {@code true} when {@code violations} contains at least one constraint
     * violation whose property path equals {@code field}.
     *
     * <p>Matching on the exact property-path string (rather than the violation message or
     * annotation type) keeps every assertion tied to a concrete DTO <em>field name</em>,
     * which is precisely the external-interface contract being pinned. The wildcard bounds
     * ({@code <? extends ConstraintViolation<?>>}) let the helper accept the
     * {@code Set<ConstraintViolation<T>>} returned for any DTO type {@code T}.</p>
     *
     * @param violations the violation set returned by {@link Validator#validate};
     *                    never {@code null}
     * @param field      the exact DTO property name to look for (for example
     *                   {@code "userId"})
     * @return {@code true} if at least one violation targets {@code field};
     *         {@code false} otherwise
     */
    private static boolean hasViolation(Set<? extends ConstraintViolation<?>> violations, String field) {
        return violations.stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field));
    }

    // ----------------------------------------------------------------------------------
    // SignOnRequest  (<- app/cpy-bms/COSGN00.CPY ; online tx CC00 ; program COSGN00C)
    // USERIDI PIC X(8) -> userId @NotBlank @Size(max=8)
    // PASSWDI PIC X(8) -> password @NotBlank @Size(max=8) (WRITE_ONLY)
    // ----------------------------------------------------------------------------------

    /**
     * Validation parity for {@link SignOnRequest}: both inputs are mandatory
     * ({@code @NotBlank}) eight-character fields ({@code @Size(max = 8)}), faithful to the
     * {@code COSGN00} symbolic map's {@code USERIDI}/{@code PASSWDI} {@code PIC X(8)} inputs
     * and the empty-field edits in {@code COSGN00C}.
     */
    @Nested
    @DisplayName("SignOnRequest <- COSGN00.CPY (userId/password PIC X(8), mandatory)")
    class SignOnRequestTests {

        @Test
        @DisplayName("valid 8-char userId + 8-char password yields no violations")
        void validCredentialsHaveNoViolations() {
            SignOnRequest dto = new SignOnRequest();
            dto.setUserId("USER0001");   // 8 chars
            dto.setPassword("PASS0001"); // 8 chars

            Set<ConstraintViolation<SignOnRequest>> violations = validator.validate(dto);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("empty userId is rejected (COSGN00 USERIDI @NotBlank)")
        void emptyUserIdViolatesNotBlank() {
            SignOnRequest dto = new SignOnRequest();
            dto.setUserId("");           // blank -> @NotBlank fires
            dto.setPassword("PASS0001"); // keep valid

            Set<ConstraintViolation<SignOnRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "userId")).isTrue();
        }

        @Test
        @DisplayName("null userId is rejected (COSGN00 USERIDI @NotBlank)")
        void nullUserIdViolatesNotBlank() {
            SignOnRequest dto = new SignOnRequest();
            dto.setUserId(null);         // null -> @NotBlank fires
            dto.setPassword("PASS0001"); // keep valid

            Set<ConstraintViolation<SignOnRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "userId")).isTrue();
        }

        @Test
        @DisplayName("9-char userId is rejected (COSGN00 USERIDI PIC X(8) @Size max8)")
        void overLengthUserIdViolatesSize() {
            SignOnRequest dto = new SignOnRequest();
            dto.setUserId("USER00012");  // 9 chars -> @Size(max=8) fires
            dto.setPassword("PASS0001"); // keep valid

            Set<ConstraintViolation<SignOnRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "userId")).isTrue();
        }

        @Test
        @DisplayName("empty password is rejected (COSGN00 PASSWDI @NotBlank)")
        void emptyPasswordViolatesNotBlank() {
            SignOnRequest dto = new SignOnRequest();
            dto.setUserId("USER0001");   // keep valid
            dto.setPassword("");         // blank -> @NotBlank fires

            Set<ConstraintViolation<SignOnRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "password")).isTrue();
        }

        @Test
        @DisplayName("9-char password is rejected (COSGN00 PASSWDI PIC X(8) @Size max8)")
        void overLengthPasswordViolatesSize() {
            SignOnRequest dto = new SignOnRequest();
            dto.setUserId("USER0001");   // keep valid
            dto.setPassword("PASSWORD9"); // 9 chars -> @Size(max=8) fires

            Set<ConstraintViolation<SignOnRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "password")).isTrue();
        }
    }

    // ----------------------------------------------------------------------------------
    // BillPaymentRequest  (<- app/cpy-bms/COBIL00.CPY ; online tx CB00 ; program COBIL00C)
    // ACTIDINI PIC X(11) -> accountId @NotBlank @Size(max=11) @Pattern("\\d{1,11}")
    // CONFIRMI PIC X(1)  -> confirm  @Size(max=1)
    // ----------------------------------------------------------------------------------

    /**
     * Validation parity for {@link BillPaymentRequest}: the account id is a mandatory
     * 1-to-11 digit key ({@code @NotBlank @Size(max = 11) @Pattern("\\d{1,11}")}, faithful to
     * {@code COBIL00} {@code ACTIDINI PIC X(11)} and the COBOL &quot;non zero 11 digit
     * number&quot; edit), and the confirmation flag is a single character
     * ({@code @Size(max = 1)}, {@code CONFIRMI PIC X(1)}).
     */
    @Nested
    @DisplayName("BillPaymentRequest <- COBIL00.CPY (accountId PIC X(11) digits; confirm PIC X(1))")
    class BillPaymentRequestTests {

        @Test
        @DisplayName("valid 11-digit accountId + 'Y' confirm yields no violations")
        void validRequestHasNoViolations() {
            BillPaymentRequest dto = new BillPaymentRequest();
            dto.setAccountId("12345678901"); // 11 digits
            dto.setConfirm("Y");

            Set<ConstraintViolation<BillPaymentRequest>> violations = validator.validate(dto);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("empty accountId is rejected (COBIL00 ACTIDINI @NotBlank; '' also fails \\d{1,11})")
        void emptyAccountIdIsRejected() {
            BillPaymentRequest dto = new BillPaymentRequest();
            dto.setAccountId("");  // blank -> @NotBlank fires; "" also not a member of \d{1,11}
            dto.setConfirm("Y");

            Set<ConstraintViolation<BillPaymentRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "accountId")).isTrue();
        }

        @Test
        @DisplayName("11 non-digit accountId is rejected (COBIL00 ACTIDINI @Pattern \\d{1,11})")
        void nonNumericAccountIdViolatesPattern() {
            BillPaymentRequest dto = new BillPaymentRequest();
            dto.setAccountId("ABCDEFGHIJK"); // 11 chars but non-digit -> @Pattern fires
            dto.setConfirm("Y");

            Set<ConstraintViolation<BillPaymentRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "accountId")).isTrue();
        }

        @Test
        @DisplayName("12-digit accountId is rejected (COBIL00 ACTIDINI PIC X(11) @Size max11)")
        void overLengthAccountIdIsRejected() {
            BillPaymentRequest dto = new BillPaymentRequest();
            dto.setAccountId("123456789012"); // 12 digits -> @Size(max=11) fires (Pattern too)
            dto.setConfirm("Y");

            Set<ConstraintViolation<BillPaymentRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "accountId")).isTrue();
        }

        @Test
        @DisplayName("2-char confirm is rejected (COBIL00 CONFIRMI PIC X(1) @Size max1)")
        void overLengthConfirmViolatesSize() {
            BillPaymentRequest dto = new BillPaymentRequest();
            dto.setAccountId("12345678901"); // keep valid
            dto.setConfirm("YN");            // 2 chars -> @Size(max=1) fires

            Set<ConstraintViolation<BillPaymentRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "confirm")).isTrue();
        }
    }

    // ----------------------------------------------------------------------------------
    // ReportRequest  (<- app/cpy-bms/CORPT00.CPY ; online tx CR00 ; program CORPT00C)
    // MONTHLYI/YEARLYI/CUSTOMI PIC X(1) -> monthly/yearly/custom @Size(max=1)
    // SDTMMI/SDTDDI/EDTMMI/EDTDDI PIC X(2) -> *Month/*Day @Size(max=2) @Pattern("\\d{0,2}")
    // SDTYYYYI/EDTYYYYI PIC X(4) -> *Year @Size(max=4) @Pattern("\\d{0,4}")
    // CONFIRMI PIC X(1) -> confirm @Size(max=1)
    // ----------------------------------------------------------------------------------

    /**
     * Validation parity for {@link ReportRequest}: every report-criteria field is optional at
     * the boundary (each constraint admits {@code null}/empty), the date parts carry only a
     * digit-shape edit ({@code @Pattern("\\d{0,n}")}) plus the {@code PIC X(n)} width
     * ({@code @Size}), and &mdash; the decisive nuance &mdash; <strong>no value/range check
     * lives here</strong>: the {@code 1..12} month range is the downstream
     * {@code DateValidationService}'s responsibility, exactly as the COBOL split the BMS
     * shape-edit from the {@code CSUTLDPY} value-edit.
     */
    @Nested
    @DisplayName("ReportRequest <- CORPT00.CPY (shape-edit only; range lives in DateValidationService)")
    class ReportRequestTests {

        @Test
        @DisplayName("valid monthly + well-formed custom range yields no violations")
        void validReportCriteriaHasNoViolations() {
            ReportRequest dto = new ReportRequest();
            dto.setMonthly("Y");
            dto.setStartMonth("06");
            dto.setStartDay("15");
            dto.setStartYear("2024");
            dto.setEndMonth("06");
            dto.setEndDay("20");
            dto.setEndYear("2024");
            dto.setConfirm("Y");

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(dto);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("fresh all-null DTO yields no violations (every constraint admits null)")
        void allNullReportCriteriaHasNoViolations() {
            ReportRequest dto = new ReportRequest();

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(dto);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("5-char startYear is rejected (CORPT00 SDTYYYYI PIC X(4) @Size max4)")
        void overLengthStartYearViolatesSize() {
            ReportRequest dto = new ReportRequest();
            dto.setStartYear("12345"); // 5 chars -> @Size(max=4) fires

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "startYear")).isTrue();
        }

        @Test
        @DisplayName("non-numeric startMonth 'ab' is rejected (CORPT00 SDTMMI @Pattern \\d{0,2})")
        void nonNumericStartMonthViolatesPattern() {
            ReportRequest dto = new ReportRequest();
            dto.setStartMonth("ab"); // 2 chars, non-digit -> @Pattern fires (length ok)

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "startMonth")).isTrue();
        }

        /**
         * PARITY NUANCE &mdash; <strong>shape, not range</strong>. {@code startMonth = "13"}
         * is two digits, so it satisfies both {@code @Size(max = 2)} and
         * {@code @Pattern("\\d{0,2}")} and produces <strong>no</strong> DTO violation. The
         * {@code 1..12} month-range rule is deliberately <em>not</em> a Bean Validation
         * concern: it is enforced downstream by {@code service/shared/DateValidationService}
         * (the {@code java.time}-based replacement for {@code CSUTLDPY}/{@code CEEDAYS}),
         * mirroring &quot;Card expiry month must be between 1 and 12&quot; in
         * {@code COACTUPC.cbl}. This preserves the COBOL separation of BMS shape-edit from
         * value-edit and is asserted here so the boundary cannot silently tighten.
         */
        @Test
        @DisplayName("startMonth '13' yields NO violation (range 1..12 is DateValidationService, not the DTO)")
        void outOfRangeButWellShapedStartMonthHasNoViolation() {
            ReportRequest dto = new ReportRequest();
            dto.setStartMonth("13"); // 2 digits: valid SHAPE; the 1..12 RANGE is not checked here

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "startMonth")).isFalse();
        }

        @Test
        @DisplayName("3-char startMonth is rejected (CORPT00 SDTMMI PIC X(2) @Size max2)")
        void overLengthStartMonthIsRejected() {
            ReportRequest dto = new ReportRequest();
            dto.setStartMonth("123"); // 3 chars -> @Size(max=2) fires (Pattern \d{0,2} also)

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "startMonth")).isTrue();
        }

        @Test
        @DisplayName("2-char confirm is rejected (CORPT00 CONFIRMI PIC X(1) @Size max1)")
        void overLengthConfirmViolatesSize() {
            ReportRequest dto = new ReportRequest();
            dto.setConfirm("AB"); // 2 chars -> @Size(max=1) fires

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "confirm")).isTrue();
        }
    }

    // ----------------------------------------------------------------------------------
    // UserSecurityDto  (<- app/cpy-bms/COUSR01.CPY ; user-admin programs COUSR00C-COUSR03C)
    // USERIDI PIC X(8)   -> userId    @NotBlank(OnAdd)            @Size(max=8)
    // FNAMEI  PIC X(20)  -> firstName @NotBlank(OnAdd,OnUpdate)   @Size(max=20)   << 20, NOT 25
    // LNAMEI  PIC X(20)  -> lastName  @NotBlank(OnAdd,OnUpdate)   @Size(max=20)   << 20, NOT 25
    // PASSWDI PIC X(8)   -> password  @NotBlank(OnAdd,OnUpdate)   @Size(max=8) (WRITE_ONLY)
    // USRTYPEI PIC X(1)  -> userType  @NotNull(OnAdd,OnUpdate)    (UserType enum)
    //
    // NOTE: the @NotBlank/@NotNull guards are GROUP-SCOPED to OnAdd/OnUpdate; the @Size
    // guards are always-on (Default group). Under validator.validate(dto) [Default group]
    // only @Size is checked, so a blank mandatory field produces NO violation there; the
    // mandatory edits fire only under the OnAdd/OnUpdate groups (the COUSR01C add path).
    // ----------------------------------------------------------------------------------

    /**
     * Validation parity for {@link UserSecurityDto}. Two contracts are pinned here:
     * <ul>
     *   <li>the always-on {@code @Size} widths &mdash; user {@code firstName}/{@code lastName}
     *       are {@code PIC X(20)} ({@code COUSR01} {@code FNAMEI}/{@code LNAMEI}), distinct
     *       from the {@code PIC X(25)} customer names on {@link AccountDto}; {@code userId}
     *       and {@code password} are {@code PIC X(8)}; and</li>
     *   <li>the <strong>group-scoped</strong> mandatory edits &mdash; the
     *       {@code @NotBlank}/{@code @NotNull} guards are bound to
     *       {@link UserSecurityDto.OnAdd}/{@code OnUpdate}, reproducing the COBOL behaviour
     *       that an empty user id / name / password / type is rejected on the
     *       <em>add</em> path ({@code COUSR01C}) but the field-width edit applies on every
     *       path. They therefore fire only when validation runs against the
     *       {@code OnAdd} group, not under the default group.</li>
     * </ul>
     */
    @Nested
    @DisplayName("UserSecurityDto <- COUSR01.CPY (names PIC X(20); group-scoped mandatory edits)")
    class UserSecurityDtoTests {

        @Test
        @DisplayName("fully-populated user is valid under both the default and OnAdd groups")
        void fullyPopulatedUserIsValid() {
            UserSecurityDto dto = newPopulatedUser();

            // Default group: only the always-on @Size widths are checked.
            assertThat(validator.validate(dto)).isEmpty();
            // OnAdd group: the @NotBlank/@NotNull mandatory edits are checked too.
            assertThat(validator.validate(dto, UserSecurityDto.OnAdd.class)).isEmpty();
        }

        @Test
        @DisplayName("user firstName over 20 chars is rejected (COUSR01 FNAMEI PIC X(20) @Size max20)")
        void overLengthFirstNameViolatesSize() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setFirstName("x".repeat(21)); // 21 chars -> @Size(max=20) fires (always-on)

            Set<ConstraintViolation<UserSecurityDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "firstName")).isTrue();
        }

        @Test
        @DisplayName("user firstName of exactly 20 chars is accepted (COUSR01 FNAMEI PIC X(20))")
        void atLimitFirstNameHasNoViolation() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setFirstName("x".repeat(20)); // exactly 20 -> within @Size(max=20)

            Set<ConstraintViolation<UserSecurityDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "firstName")).isFalse();
        }

        @Test
        @DisplayName("user lastName over 20 chars is rejected (COUSR01 LNAMEI PIC X(20) @Size max20)")
        void overLengthLastNameViolatesSize() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setLastName("x".repeat(21)); // 21 chars -> @Size(max=20) fires

            Set<ConstraintViolation<UserSecurityDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "lastName")).isTrue();
        }

        @Test
        @DisplayName("9-char userId is rejected (COUSR01 USERIDI PIC X(8) @Size max8)")
        void overLengthUserIdViolatesSize() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setUserId("USERID012"); // 9 chars -> @Size(max=8) fires

            Set<ConstraintViolation<UserSecurityDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "userId")).isTrue();
        }

        @Test
        @DisplayName("9-char password is rejected (COUSR01 PASSWDI PIC X(8) @Size max8)")
        void overLengthPasswordViolatesSize() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setPassword("x".repeat(9)); // 9 chars -> @Size(max=8) fires

            Set<ConstraintViolation<UserSecurityDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "password")).isTrue();
        }

        /**
         * PARITY NUANCE &mdash; the mandatory edits are <strong>group-scoped</strong>. With
         * every mandatory field blank/{@code null}, the default-group validation sees no
         * violation (the {@code @Size} widths all admit empty/{@code null}); the
         * {@code @NotBlank}/{@code @NotNull} guards fire only when the {@code OnAdd} group is
         * requested, reproducing the COBOL {@code COUSR01C} add-time empty-field edits.
         */
        @Test
        @DisplayName("blank mandatory fields: no violation under default group, rejected under OnAdd")
        void mandatoryFieldsAreGroupScoped() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setUserId("");      // blank
            dto.setFirstName("");   // blank
            dto.setLastName("");    // blank
            dto.setPassword("");    // blank
            dto.setUserType(null);  // null

            // Default group: @NotBlank/@NotNull are NOT in this group, and "" satisfies
            // every @Size(max=n); so there is no violation at all.
            assertThat(validator.validate(dto)).isEmpty();

            // OnAdd group: the mandatory edits now fire on each mandatory field.
            Set<ConstraintViolation<UserSecurityDto>> onAdd =
                    validator.validate(dto, UserSecurityDto.OnAdd.class);
            assertThat(hasViolation(onAdd, "userId")).isTrue();
            assertThat(hasViolation(onAdd, "firstName")).isTrue();
            assertThat(hasViolation(onAdd, "lastName")).isTrue();
            assertThat(hasViolation(onAdd, "password")).isTrue();
            assertThat(hasViolation(onAdd, "userType")).isTrue();
        }

        /**
         * Builds a fully-valid single-user payload (8-char user id and password, short
         * names, {@link UserType#ADMIN}) that passes under both the default and {@code OnAdd}
         * groups. Centralised so the &quot;valid&quot; baseline is defined once.
         *
         * @return a populated, fully-valid {@link UserSecurityDto}
         */
        private UserSecurityDto newPopulatedUser() {
            UserSecurityDto dto = new UserSecurityDto();
            dto.setUserId("ADMIN001"); // 8 chars
            dto.setFirstName("John");
            dto.setLastName("Smith");
            dto.setPassword("PASS0001"); // 8 chars
            dto.setUserType(UserType.ADMIN);
            return dto;
        }
    }

    // ----------------------------------------------------------------------------------
    // AccountDto  (<- app/cpy-bms/COACTUP.CPY / COACTVW.CPY ; programs COACTVWC / COACTUPC)
    // ACTSIDI  PIC X(11) -> accountId  @Size(max=11) @Pattern("\\d{1,11}")
    // ACSTTUSI PIC X(1)  -> accountStatus @Size(max=1)
    // ACSFNAMI/ACSMNAMI/ACSLNAMI PIC X(25) -> first/middle/lastName @Size(max=25)  << 25, NOT 20
    // ACSSTTEI PIC X(2)  -> state @Size(max=2) ; ACSZIPCI PIC X(5) -> zipCode @Size(max=5)
    // customerId @Size(max=9) @Pattern("\\d{1,9}") ; ssn @Size(max=12)
    // (money fields are BigDecimal and carry no Jakarta constraint -> not validation-tested)
    // ----------------------------------------------------------------------------------

    /**
     * Validation parity for {@link AccountDto}: representative {@code @Size}/{@code @Pattern}
     * field edits drawn from the account-update map. The decisive assertion is that a
     * customer name accepts up to <strong>25</strong> characters ({@code COACTUP}
     * {@code ACSFNAMI PIC X(25)}) &mdash; a width that must remain distinct from the
     * {@code PIC X(20)} <em>user</em> name on {@link UserSecurityDto}. Money fields are
     * {@link java.math.BigDecimal} with no Jakarta constraint and are intentionally not
     * exercised here (and never touched with {@code float}/{@code double}, AAP &sect;0.7.3).
     */
    @Nested
    @DisplayName("AccountDto <- COACTUP.CPY (customer names PIC X(25); accountId digits; state/zip)")
    class AccountDtoTests {

        @Test
        @DisplayName("valid account baseline yields no violations")
        void validAccountBaselineHasNoViolations() {
            AccountDto dto = new AccountDto();
            dto.setAccountId("12345678901"); // 11 digits
            dto.setFirstName("John");
            dto.setState("CA");
            dto.setZipCode("90210");

            Set<ConstraintViolation<AccountDto>> violations = validator.validate(dto);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("customer firstName over 25 chars is rejected (COACTUP ACSFNAMI PIC X(25) @Size max25)")
        void overLengthCustomerFirstNameViolatesSize() {
            AccountDto dto = new AccountDto();
            dto.setFirstName("x".repeat(26)); // 26 chars -> @Size(max=25) fires

            Set<ConstraintViolation<AccountDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "firstName")).isTrue();
        }

        /**
         * Cross-reference for the 20-vs-25 split: a 21-character {@code firstName} that MUST
         * violate on {@link UserSecurityDto} (user name {@code PIC X(20)}) is well within the
         * customer-name {@code PIC X(25)} width here, so it must NOT violate. A 25-character
         * value is therefore the at-limit boundary and is accepted.
         */
        @Test
        @DisplayName("customer firstName of exactly 25 chars is accepted (distinct from user PIC X(20))")
        void atLimitCustomerFirstNameHasNoViolation() {
            AccountDto dto = new AccountDto();
            dto.setFirstName("x".repeat(25)); // exactly 25 -> within @Size(max=25); 21 (user-illegal) also ok

            Set<ConstraintViolation<AccountDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "firstName")).isFalse();
        }

        @Test
        @DisplayName("3-char state is rejected (COACTUP ACSSTTEI PIC X(2) @Size max2)")
        void overLengthStateViolatesSize() {
            AccountDto dto = new AccountDto();
            dto.setState("CAL"); // 3 chars -> @Size(max=2) fires

            Set<ConstraintViolation<AccountDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "state")).isTrue();
        }

        @Test
        @DisplayName("6-char zipCode is rejected (COACTUP ACSZIPCI PIC X(5) @Size max5)")
        void overLengthZipCodeViolatesSize() {
            AccountDto dto = new AccountDto();
            dto.setZipCode("123456"); // 6 chars -> @Size(max=5) fires

            Set<ConstraintViolation<AccountDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "zipCode")).isTrue();
        }

        @Test
        @DisplayName("non-numeric accountId is rejected (COACTUPC 'non zero 11 digit number' @Pattern \\d{1,11})")
        void nonNumericAccountIdViolatesPattern() {
            AccountDto dto = new AccountDto();
            dto.setAccountId("ABC"); // non-digit -> @Pattern("\\d{1,11}") fires

            Set<ConstraintViolation<AccountDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "accountId")).isTrue();
        }

        @Test
        @DisplayName("12-digit accountId is rejected (account id PIC X(11) @Size max11)")
        void overLengthAccountIdIsRejected() {
            AccountDto dto = new AccountDto();
            dto.setAccountId("123456789012"); // 12 digits -> @Size(max=11) fires (Pattern too)

            Set<ConstraintViolation<AccountDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "accountId")).isTrue();
        }
    }

    // ----------------------------------------------------------------------------------
    // CardDto  (<- BMS card maps COCRDLI/COCRDSL/COCRDUP via CardDto)
    // accountId   @Size(max=11) @Pattern("\\d{1,11}")
    // cardNumber  @Size(max=16) @Pattern("\\d{0,16}")   (16-digit PAN; empty allowed)
    // embossedName @Size(max=50) ; activeStatus @Size(max=1)
    // expiryMonth/expiryDay @Size(max=2) @Pattern("\\d{0,2}") ; expiryYear @Size(max=4) @Pattern("\\d{0,4}")
    // (verified production field names are embossedName/activeStatus, not nameOnCard/cardStatus)
    // ----------------------------------------------------------------------------------

    /**
     * Validation parity for {@link CardDto}: the 16-digit card number is the focus
     * ({@code @Size(max = 16) @Pattern("\\d{0,16}")}). Because the pattern is
     * {@code \\d{0,16}} (zero digits permitted), an <em>empty</em> card number is well-formed
     * and must <strong>not</strong> violate &mdash; the &quot;present and exactly 16&quot;
     * rule is a downstream service concern, not a boundary edit. Field names follow the
     * verified production DTO ({@code cardNumber}, {@code activeStatus}, {@code expiryMonth}).
     */
    @Nested
    @DisplayName("CardDto <- BMS card maps (cardNumber PIC X(16) digits; empty allowed)")
    class CardDtoTests {

        @Test
        @DisplayName("valid 16-digit cardNumber + 11-digit accountId yields no violations")
        void validCardBaselineHasNoViolations() {
            CardDto dto = new CardDto();
            dto.setCardNumber("1234567890123456"); // 16 digits
            dto.setAccountId("12345678901");       // 11 digits

            Set<ConstraintViolation<CardDto>> violations = validator.validate(dto);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("17-char cardNumber is rejected (card number PIC X(16) @Size max16)")
        void overLengthCardNumberViolatesSize() {
            CardDto dto = new CardDto();
            dto.setCardNumber("x".repeat(17)); // 17 chars -> @Size(max=16) fires

            Set<ConstraintViolation<CardDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "cardNumber")).isTrue();
        }

        /**
         * Empty card number is well-formed: both {@code @Size(max = 16)} and
         * {@code @Pattern("\\d{0,16}")} admit the empty string (zero digits), so there is no
         * boundary violation. This pins the {@code \\d{0,16}} (not {@code \\d{16}}) contract.
         */
        @Test
        @DisplayName("empty cardNumber yields NO violation (\\d{0,16} and @Size both admit empty)")
        void emptyCardNumberHasNoViolation() {
            CardDto dto = new CardDto();
            dto.setCardNumber(""); // empty -> matches \d{0,16} and within @Size(max=16)

            Set<ConstraintViolation<CardDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "cardNumber")).isFalse();
        }

        @Test
        @DisplayName("non-numeric cardNumber '12AB' is rejected (@Pattern \\d{0,16})")
        void nonNumericCardNumberViolatesPattern() {
            CardDto dto = new CardDto();
            dto.setCardNumber("12AB"); // length ok but non-digit -> @Pattern fires

            Set<ConstraintViolation<CardDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "cardNumber")).isTrue();
        }

        @Test
        @DisplayName("3-char expiryMonth is rejected (expiry month PIC X(2) @Size max2)")
        void overLengthExpiryMonthViolatesSize() {
            CardDto dto = new CardDto();
            dto.setExpiryMonth("123"); // 3 chars -> @Size(max=2) fires (Pattern \d{0,2} also)

            Set<ConstraintViolation<CardDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "expiryMonth")).isTrue();
        }

        @Test
        @DisplayName("2-char activeStatus is rejected (active status PIC X(1) @Size max1)")
        void overLengthActiveStatusViolatesSize() {
            CardDto dto = new CardDto();
            dto.setActiveStatus("YN"); // 2 chars -> @Size(max=1) fires

            Set<ConstraintViolation<CardDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "activeStatus")).isTrue();
        }
    }

    // ----------------------------------------------------------------------------------
    // TransactionDto  (<- BMS transaction maps COTRN00/COTRN01/COTRN02 via TransactionDto)
    // transactionId @Size(max=16) ; accountId @Size(max=11) @Pattern("\\d{0,11}")
    // cardNumber @Size(max=16) @Pattern("\\d{0,16}") ; typeCode @Size(max=2) ; categoryCode @Size(max=4)
    // source @Size(max=10) ; description @Size(max=60) ; merchantId @Size(max=9) @Pattern("\\d{0,9}")
    // merchantName @Size(max=30) ; merchantCity @Size(max=25) ; merchantZip @Size(max=10) ; confirm @Size(max=1)
    // (amount is BigDecimal and carries no Jakarta constraint -> not validation-tested)
    // ----------------------------------------------------------------------------------

    /**
     * Validation parity for {@link TransactionDto}: representative width edits on the longer
     * transaction fields &mdash; {@code description} is {@code @Size(max = 60)},
     * {@code cardNumber} is {@code @Size(max = 16) @Pattern("\\d{0,16}")}, {@code typeCode} is
     * {@code @Size(max = 2)}. The monetary {@code amount} is {@link java.math.BigDecimal} with
     * no Jakarta constraint and is intentionally not exercised (and never touched with
     * {@code float}/{@code double}, AAP &sect;0.7.3).
     */
    @Nested
    @DisplayName("TransactionDto <- BMS transaction maps (description PIC X(60); cardNumber X(16); typeCode X(2))")
    class TransactionDtoTests {

        @Test
        @DisplayName("valid description + 16-digit cardNumber yields no violations")
        void validTransactionBaselineHasNoViolations() {
            TransactionDto dto = new TransactionDto();
            dto.setDescription("Coffee shop purchase");
            dto.setCardNumber("1234567890123456"); // 16 digits

            Set<ConstraintViolation<TransactionDto>> violations = validator.validate(dto);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("61-char description is rejected (description PIC X(60) @Size max60)")
        void overLengthDescriptionViolatesSize() {
            TransactionDto dto = new TransactionDto();
            dto.setDescription("x".repeat(61)); // 61 chars -> @Size(max=60) fires

            Set<ConstraintViolation<TransactionDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "description")).isTrue();
        }

        @Test
        @DisplayName("description of exactly 60 chars is accepted (description PIC X(60))")
        void atLimitDescriptionHasNoViolation() {
            TransactionDto dto = new TransactionDto();
            dto.setDescription("x".repeat(60)); // exactly 60 -> within @Size(max=60)

            Set<ConstraintViolation<TransactionDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "description")).isFalse();
        }

        @Test
        @DisplayName("17-char cardNumber is rejected (card number PIC X(16) @Size max16)")
        void overLengthCardNumberViolatesSize() {
            TransactionDto dto = new TransactionDto();
            dto.setCardNumber("x".repeat(17)); // 17 chars -> @Size(max=16) fires

            Set<ConstraintViolation<TransactionDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "cardNumber")).isTrue();
        }

        @Test
        @DisplayName("3-char typeCode is rejected (type code PIC X(2) @Size max2)")
        void overLengthTypeCodeViolatesSize() {
            TransactionDto dto = new TransactionDto();
            dto.setTypeCode("ABC"); // 3 chars -> @Size(max=2) fires

            Set<ConstraintViolation<TransactionDto>> violations = validator.validate(dto);

            assertThat(hasViolation(violations, "typeCode")).isTrue();
        }
    }
}
