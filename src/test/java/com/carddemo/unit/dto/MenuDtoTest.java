package com.carddemo.unit.dto;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.carddemo.dto.MenuDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit tests for {@link MenuDto} and its three nested records
 * ({@code MenuOption}, {@code MenuResponse}, {@code MenuSelectionRequest}).
 *
 * <p>The contract under test is derived byte-accurately from the BMS mapsets
 * {@code app/bms/COMEN01.bms} (main menu, program {@code COMEN01C}) and
 * {@code app/bms/COADM01.bms} (admin menu, program {@code COADM01C}) @
 * {@code 27d6c6f}: a {@code TITLE01} heading ({@code PIC X(40)}), twelve option
 * slots {@code OPTN001I}..{@code OPTN012I} (each {@code PIC X(40)}), and a
 * two-character option-entry field {@code OPTIONI} ({@code PIC X(2)}, numeric).
 * Because the two maps differ only in their static heading literal, a single
 * {@code MenuDto} serves both menus; this suite exercises the twelve-slot
 * screen contract once.</p>
 *
 * <p>These are standalone tests: they rely on a jakarta Bean Validation
 * {@link Validator} only &mdash; no Spring context, Testcontainers, or Mockito.
 * They verify the record shape (via reflection), the decimal-exactness
 * guardrail (no floating-point components), and the declared constraints (via
 * validation), including the {@code @Valid} cascade over the option list.</p>
 */
class MenuDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -----------------------------------------------------------------
    // Record shape (reflection on the nested records)
    // -----------------------------------------------------------------

    @Test
    void menuOptionHasTwoComponentsInOrder() {
        RecordComponent[] components = MenuDto.MenuOption.class.getRecordComponents();
        assertThat(components)
                .hasSize(2)
                .extracting(RecordComponent::getName)
                .containsExactly("optionNumber", "optionName");
    }

    @Test
    void menuResponseHasTwoComponentsInOrder() {
        RecordComponent[] components = MenuDto.MenuResponse.class.getRecordComponents();
        assertThat(components)
                .hasSize(2)
                .extracting(RecordComponent::getName)
                .containsExactly("title", "options");
        // The option list is exposed as a java.util.List (erased component type).
        assertThat(components[1].getType()).isEqualTo(List.class);
    }

    @Test
    void menuSelectionRequestHasOneComponent() {
        RecordComponent[] components = MenuDto.MenuSelectionRequest.class.getRecordComponents();
        assertThat(components)
                .hasSize(1)
                .extracting(RecordComponent::getName)
                .containsExactly("option");
    }

    // -----------------------------------------------------------------
    // Decimal-exactness guardrail (AAP 0.6.1): no floating-point components
    // -----------------------------------------------------------------

    @Test
    void noFloatingPointComponents() {
        assertNoFloatingPointComponents(MenuDto.MenuOption.class);
        assertNoFloatingPointComponents(MenuDto.MenuResponse.class);
        assertNoFloatingPointComponents(MenuDto.MenuSelectionRequest.class);
    }

    private static void assertNoFloatingPointComponents(Class<?> recordType) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            assertThat(component.getType())
                    .as("component '%s' of %s must not be floating-point (AAP 0.6.1)",
                            component.getName(), recordType.getSimpleName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    // -----------------------------------------------------------------
    // Bean-validation constraints (each: a violating case and a passing case)
    // -----------------------------------------------------------------

    @Test
    void optionNumberTooLongFailsSize() {
        // "123" is three characters: exceeds @Size(max = 2) on optionNumber.
        Set<ConstraintViolation<MenuDto.MenuOption>> violations =
                validator.validate(new MenuDto.MenuOption("123", "Accounts"));
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("optionNumber");

        // A two-digit code is within the width contract and passes.
        assertThat(validator.validate(new MenuDto.MenuOption("12", "Accounts"))).isEmpty();
    }

    @Test
    void optionNumberNonDigitFailsPattern() {
        // "ab" is within @Size(max = 2) but is not numeric: fails @Pattern("\\d{0,2}").
        Set<ConstraintViolation<MenuDto.MenuOption>> violations =
                validator.validate(new MenuDto.MenuOption("ab", "Accounts"));
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("optionNumber");

        // A single digit satisfies both @Size and @Pattern.
        assertThat(validator.validate(new MenuDto.MenuOption("9", "Accounts"))).isEmpty();
    }

    @Test
    void optionNameTooLongFailsSize() {
        // Forty-one characters exceeds @Size(max = 40) on optionName (OPTN0nnI X(40)).
        String tooLongName = "X".repeat(41);
        Set<ConstraintViolation<MenuDto.MenuOption>> violations =
                validator.validate(new MenuDto.MenuOption("1", tooLongName));
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("optionName");

        // Exactly forty characters is the upper bound and passes.
        String maxName = "X".repeat(40);
        assertThat(validator.validate(new MenuDto.MenuOption("1", maxName))).isEmpty();
    }

    @Test
    void titleTooLongFailsSize() {
        // Forty-one characters exceeds @Size(max = 40) on title (TITLE01 X(40)).
        String tooLongTitle = "T".repeat(41);
        Set<ConstraintViolation<MenuDto.MenuResponse>> violations =
                validator.validate(new MenuDto.MenuResponse(tooLongTitle, List.of()));
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("title");

        // Exactly forty characters is the upper bound and passes.
        String maxTitle = "T".repeat(40);
        assertThat(validator.validate(new MenuDto.MenuResponse(maxTitle, List.of()))).isEmpty();
    }

    @Test
    void menuSelectionBlankFailsNotBlank() {
        // A blank selection violates @NotBlank on option (OPTIONI is required input).
        Set<ConstraintViolation<MenuDto.MenuSelectionRequest>> violations =
                validator.validate(new MenuDto.MenuSelectionRequest(""));
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("option");
    }

    @Test
    void menuSelectionThreeCharFailsSize() {
        // "123" is three characters: exceeds @Size(max = 2) on option (OPTIONI X(2)).
        Set<ConstraintViolation<MenuDto.MenuSelectionRequest>> violations =
                validator.validate(new MenuDto.MenuSelectionRequest("123"));
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("option");

        // A single digit satisfies @NotBlank, @Size, and @Pattern("\\d{1,2}").
        assertThat(validator.validate(new MenuDto.MenuSelectionRequest("5"))).isEmpty();
    }

    @Test
    void twelveSlotMenuResponseValidates() {
        // Exercises the full twelve-slot screen contract (OPTN001..OPTN012) with a
        // valid title; every element is cascade-validated and must pass cleanly.
        List<MenuDto.MenuOption> options = List.of(
                new MenuDto.MenuOption("1", "Account View"),
                new MenuDto.MenuOption("2", "Account Update"),
                new MenuDto.MenuOption("3", "Card List"),
                new MenuDto.MenuOption("4", "Card Detail"),
                new MenuDto.MenuOption("5", "Card Update"),
                new MenuDto.MenuOption("6", "Transaction List"),
                new MenuDto.MenuOption("7", "Transaction View"),
                new MenuDto.MenuOption("8", "Transaction Add"),
                new MenuDto.MenuOption("9", "Bill Payment"),
                new MenuDto.MenuOption("10", "Report Submit"),
                new MenuDto.MenuOption("11", "Account Reports"),
                new MenuDto.MenuOption("12", "User Administration"));
        MenuDto.MenuResponse response = new MenuDto.MenuResponse("Main Menu", options);

        assertThat(validator.validate(response)).isEmpty();
    }

    @Test
    void cascadeReportsNestedViolation() {
        // A nested MenuOption with an over-length name must surface through the
        // @Valid cascade as a path such as "options[0].optionName".
        String tooLongName = "Z".repeat(41);
        MenuDto.MenuResponse response = new MenuDto.MenuResponse(
                "Main Menu", List.of(new MenuDto.MenuOption("1", tooLongName)));
        Set<ConstraintViolation<MenuDto.MenuResponse>> violations = validator.validate(response);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .anyMatch(path -> path.startsWith("options[") && path.endsWith("optionName"));
    }
}
