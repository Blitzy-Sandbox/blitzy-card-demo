package com.carddemo.unit.dto;

import com.carddemo.dto.UserDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit test for {@link UserDto}, the user-administration DTO family
 * mapped from CICS transactions CU00-CU03 (programs {@code COUSR00C}, {@code COUSR01C},
 * {@code COUSR02C}, {@code COUSR03C}) and their BMS field contracts at source commit
 * SHA {@code 27d6c6f}.
 *
 * <p>This suite is deliberately framework-light: it uses a standalone jakarta Bean
 * Validation {@link Validator} (Hibernate Validator reference implementation) and plain
 * reflection — <strong>no</strong> {@code @SpringBootTest}, Spring context, Testcontainers
 * or Mockito. It pins the four defining, name-drift-resistant requirements of the contract:
 * <ul>
 *   <li>{@code CreateRequest} carries no {@code @NotBlank}: presence is validated at the
 *       service layer for byte-exact COUSR01C parity (P-2; DECISION_LOG D-056), so an
 *       all-blank (within-length) request raises no DTO-layer violation, while the five
 *       {@code @Size} upper bounds remain;</li>
 *   <li>{@code UpdateRequest.password} is OPTIONAL (a blank value is allowed);</li>
 *   <li>{@code DeleteResponse} carries NO {@code password} component;</li>
 *   <li>{@code userType} is a width-1 {@link String} (never an enum).</li>
 * </ul>
 *
 * <p>The {@code password} value is treated as sensitive and is therefore only ever passed
 * into a DTO constructor; it is never logged or printed.
 */
@DisplayName("UserDto - user-admin DTO contract (COUSR00-COUSR03 @ 27d6c6f): record shape, no-float, bean-validation")
class UserDtoTest {

    /** Bean-validation factory held for the whole class and released in {@link #tearDown()}. */
    private static ValidatorFactory validatorFactory;

    /** Standalone jakarta Bean Validation validator (no Spring application context). */
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    // ------------------------------------------------------------------
    // Reflection / assertion helpers
    // ------------------------------------------------------------------

    /** Returns the ordered component names of a record class via reflection. */
    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Asserts that no component of the given record class is a binary floating-point type.
     * Monetary and width-bound fields must never be {@code double}/{@code float} (decimal
     * exactness, AAP 0.6.1).
     */
    private static void assertNoFloatingPointComponents(Class<?> recordType) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            assertThat(component.getType())
                    .as("component '%s' of %s must not be floating-point",
                            component.getName(), recordType.getSimpleName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    /** Collects the distinct violated property paths (as strings) from a violation set. */
    private static Set<String> violatedPaths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------
    // Phase 2 - record shape (reflection on all five nested records)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UserSummary components are exactly [userId, firstName, lastName, userType] in order")
    void userSummaryComponentsInOrder() {
        assertThat(componentNames(UserDto.UserSummary.class))
                .containsExactly("userId", "firstName", "lastName", "userType");
    }

    @Test
    @DisplayName("ListResponse components are [pageNumber, userIdFilter, users] and 'users' is a java.util.List")
    void listResponseComponentsInOrder() {
        assertThat(componentNames(UserDto.ListResponse.class))
                .containsExactly("pageNumber", "userIdFilter", "users");

        RecordComponent users = Arrays.stream(UserDto.ListResponse.class.getRecordComponents())
                .filter(component -> "users".equals(component.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(users.getType()).isEqualTo(List.class);
    }

    @Test
    @DisplayName("CreateRequest has 5 components in order [firstName, lastName, userId, password, userType]")
    void createRequestHasFiveComponentsInOrder() {
        assertThat(UserDto.CreateRequest.class.getRecordComponents()).hasSize(5);
        assertThat(componentNames(UserDto.CreateRequest.class))
                .containsExactly("firstName", "lastName", "userId", "password", "userType");
    }

    @Test
    @DisplayName("UpdateRequest components are exactly [userId, firstName, lastName, password, userType] in order")
    void updateRequestComponentsInOrder() {
        assertThat(componentNames(UserDto.UpdateRequest.class))
                .containsExactly("userId", "firstName", "lastName", "password", "userType");
    }

    @Test
    @DisplayName("DeleteResponse has NO 'password' component and carries [userId, firstName, lastName, userType]")
    void deleteResponseHasNoPasswordComponent() {
        List<String> names = componentNames(UserDto.DeleteResponse.class);
        assertThat(names).doesNotContain("password");
        assertThat(names).contains("userId", "firstName", "lastName", "userType");
    }

    // ------------------------------------------------------------------
    // Phase 3 - no floating-point components anywhere in the DTO family
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no nested record component is double/float/Double/Float (decimal exactness, AAP 0.6.1)")
    void noFloatingPointComponents() {
        assertNoFloatingPointComponents(UserDto.UserSummary.class);
        assertNoFloatingPointComponents(UserDto.ListResponse.class);
        assertNoFloatingPointComponents(UserDto.CreateRequest.class);
        assertNoFloatingPointComponents(UserDto.UpdateRequest.class);
        assertNoFloatingPointComponents(UserDto.DeleteResponse.class);
    }

    // ------------------------------------------------------------------
    // Phase 4 - bean validation (violating + passing, propertyPath asserts)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CreateRequest: all-blank fields raise NO DTO-layer violation (presence delegated to UserAddService for byte-exact text)")
    void createRequestAllBlankHasNoDtoViolation() {
        // P-2 parity: CreateRequest deliberately carries no @NotBlank. Blank "" satisfies
        // every @Size(max=N) (length 0), so an all-blank request raises ZERO DTO-layer
        // violations and reaches UserAddService, which emits the byte-exact COUSR01C
        // PROCESS-ENTER-KEY literals ("First Name can NOT be empty...", etc.) in the
        // legacy field order. Service-layer coverage lives in UserAddServiceTest's ordered
        // empty-field cascade. See DECISION_LOG D-056.
        Set<ConstraintViolation<UserDto.CreateRequest>> violations =
                validator.validate(new UserDto.CreateRequest("", "", "", "", ""));
        assertThat(violations)
                .as("with @NotBlank removed, all-blank (within-length) fields must raise no DTO-layer violation")
                .isEmpty();
    }

    @Test
    @DisplayName("CreateRequest: a fully-populated valid request produces no violations")
    void createRequestValidPasses() {
        UserDto.CreateRequest request =
                new UserDto.CreateRequest("John", "Doe", "jdoe1", "secret12", "A");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("CreateRequest: a 9-char userId breaches @Size(max=8); 8 chars passes")
    void createRequestNineCharUserIdFailsSize() {
        Set<ConstraintViolation<UserDto.CreateRequest>> tooLong =
                validator.validate(new UserDto.CreateRequest("John", "Doe", "123456789", "secret12", "A"));
        assertThat(violatedPaths(tooLong)).contains("userId");

        UserDto.CreateRequest atLimit =
                new UserDto.CreateRequest("John", "Doe", "12345678", "secret12", "A");
        assertThat(validator.validate(atLimit)).isEmpty();
    }

    @Test
    @DisplayName("CreateRequest: userType is width 1 - 'AB' breaches @Size(max=1); 'A' passes")
    void createRequestUserTypeWidthOne() {
        Set<ConstraintViolation<UserDto.CreateRequest>> tooWide =
                validator.validate(new UserDto.CreateRequest("John", "Doe", "jdoe1", "secret12", "AB"));
        assertThat(violatedPaths(tooWide)).contains("userType");

        UserDto.CreateRequest widthOne =
                new UserDto.CreateRequest("John", "Doe", "jdoe1", "secret12", "A");
        assertThat(validator.validate(widthOne)).isEmpty();
    }

    @Test
    @DisplayName("UpdateRequest: password is OPTIONAL - a blank password produces no 'password' violation")
    void updateRequestPasswordOptionalBlankAllowed() {
        Set<ConstraintViolation<UserDto.UpdateRequest>> violations =
                validator.validate(new UserDto.UpdateRequest("jdoe1", "John", "Doe", "", "A"));
        assertThat(violations)
                .noneMatch(violation -> "password".equals(violation.getPropertyPath().toString()));
    }

    @Test
    @DisplayName("UpdateRequest: userId remains @NotBlank - a blank userId fires on 'userId'")
    void updateRequestUserIdStillNotBlank() {
        Set<ConstraintViolation<UserDto.UpdateRequest>> violations =
                validator.validate(new UserDto.UpdateRequest("", "John", "Doe", "secret12", "A"));
        assertThat(violatedPaths(violations)).contains("userId");
    }

    @Test
    @DisplayName("UpdateRequest: password is still @Size(max=8) - 9 chars breaches; 8 chars passes")
    void updateRequestNineCharPasswordFailsSize() {
        Set<ConstraintViolation<UserDto.UpdateRequest>> tooLong =
                validator.validate(new UserDto.UpdateRequest("jdoe1", "John", "Doe", "123456789", "A"));
        assertThat(violatedPaths(tooLong)).contains("password");

        UserDto.UpdateRequest atLimit =
                new UserDto.UpdateRequest("jdoe1", "John", "Doe", "12345678", "A");
        assertThat(validator.validate(atLimit)).isEmpty();
    }

    @Test
    @DisplayName("ListResponse: @Valid cascades into UserSummary - an over-long firstName fires at users[0].firstName")
    void listResponseCascadesToUserSummary() {
        UserDto.UserSummary oversized =
                new UserDto.UserSummary("u1", "A".repeat(21), "Doe", "A");
        UserDto.ListResponse response =
                new UserDto.ListResponse("1", "", List.of(oversized));

        Set<ConstraintViolation<UserDto.ListResponse>> violations = validator.validate(response);
        assertThat(violations).anyMatch(violation -> {
            String path = violation.getPropertyPath().toString();
            return path.startsWith("users[") && path.contains("firstName");
        });
    }
}
