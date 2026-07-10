package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.validation.ConstraintViolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure, framework-free unit test for the menu-entry DTO {@link MenuOption}.
 *
 * <p>{@code MenuOption} is a single selectable entry on a CardDemo menu, migrated from the
 * legacy COBOL menu-option tables in the read-only copybooks (referenced by source SHA
 * {@code 27d6c6f}, not copied into the target):</p>
 * <ul>
 *   <li><b>Main menu</b> &mdash; {@code COMEN02Y} / {@code CDEMO-MENU-OPT}: 10 options, each with a
 *       user-type flag. Option&nbsp;1 is {@code 01 'Account View' 'COACTVWC' 'U'}.</li>
 *   <li><b>Admin menu</b> &mdash; {@code COADM02Y} / {@code CDEMO-ADMIN-OPT}: 4 options with
 *       <em>no</em> user-type field. Option&nbsp;1 is {@code 01 'User List (Security)' 'COUSR00C'}
 *       (so {@code userType} is {@code null} on the Java side).</li>
 * </ul>
 *
 * <p>The COBOL picture clauses drive the field contract this test guards:</p>
 * <ul>
 *   <li>{@code *-OPT-NUM}     {@code PIC 9(02)}  &rarr; {@code int optionNumber} (JSON number)</li>
 *   <li>{@code *-OPT-NAME}    {@code PIC X(35)}  &rarr; {@code String optionName}</li>
 *   <li>{@code *-OPT-PGMNAME} {@code PIC X(08)}  &rarr; {@code String targetProgram} (e.g. {@code COACTVWC})</li>
 *   <li>{@code CDEMO-MENU-OPT-USRTYPE} {@code PIC X(01)} &rarr; {@code String userType}
 *       ({@code "U"}/{@code "A"}; {@code null} for admin-menu options)</li>
 * </ul>
 *
 * <p>The test is organised into three groups mirroring the migration acceptance criteria:
 * JSON round-trip and wire contract (Phase&nbsp;1), field fidelity (Phase&nbsp;2), and record value
 * semantics (Phase&nbsp;3). It loads no Spring context and uses no database, file, or network I/O:
 * every assertion is an in-memory check driven by the shared, production-mirroring Jackson mapper and
 * Bean-Validation validator exposed by {@link DtoTestSupport}. That keeps the suite fast and
 * deterministic while contributing to line coverage (JaCoCo, Gate&nbsp;8).</p>
 */
@DisplayName("MenuOption DTO — menu-entry record (COMEN02Y / COADM02Y)")
class MenuOptionTest {

    /**
     * Canonical main-menu entry from copybook {@code COMEN02Y}, option&nbsp;1
     * ({@code 01 'Account View' 'COACTVWC' 'U'}). {@code userType} {@code "U"} marks the entry as
     * visible to a regular user.
     */
    private static final MenuOption MAIN_MENU_ACCOUNT_VIEW =
            new MenuOption(1, "Account View", "COACTVWC", "U");

    /**
     * Canonical admin-menu entry from copybook {@code COADM02Y}, option&nbsp;1
     * ({@code 01 'User List (Security)' 'COUSR00C'}). The admin menu table has no user-type field, so
     * {@code userType} is {@code null}.
     */
    private static final MenuOption ADMIN_MENU_USER_LIST =
            new MenuOption(1, "User List (Security)", "COUSR00C", null);

    /** The four JSON property names that make up the {@code MenuOption} wire contract. */
    private static final String[] CONTRACT_KEYS = {"optionNumber", "optionName", "targetProgram", "userType"};

    // =====================================================================
    // Phase 1 — JSON round-trip and wire contract
    // =====================================================================

    @Nested
    @DisplayName("Phase 1 — JSON round-trip")
    class JsonRoundTrip {

        @Test
        @DisplayName("round-trips through JSON preserving every component")
        void roundTripPreservesAllComponents() {
            MenuOption original = MAIN_MENU_ACCOUNT_VIEW;

            MenuOption restored = DtoTestSupport.roundTrip(original, MenuOption.class);

            // Record value equality proves the whole tuple survived serialize + deserialize.
            assertThat(restored).isEqualTo(original);
            // Component-by-component to make a regression pinpoint the exact field.
            assertThat(restored.optionNumber()).isEqualTo(1);
            assertThat(restored.optionName()).isEqualTo("Account View");
            assertThat(restored.targetProgram()).isEqualTo("COACTVWC");
            assertThat(restored.userType()).isEqualTo("U");
        }

        @Test
        @DisplayName("serializes exactly the four contract keys and no others")
        void jsonExposesExactlyTheFourContractKeys() {
            String json = DtoTestSupport.toJson(MAIN_MENU_ACCOUNT_VIEW);

            // Re-read the payload as an untyped object map so the assertion inspects the actual
            // wire keys Jackson emits (the record component names), not the Java accessors.
            Map<String, Object> fields = DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(fields).containsOnlyKeys(CONTRACT_KEYS);
        }

        @Test
        @DisplayName("optionNumber serializes as a JSON number (int) and echoes unchanged")
        void optionNumberSerializesAsJsonNumber() {
            MenuOption option = new MenuOption(7, "Transaction View", "COTRN01C", "U");

            String json = DtoTestSupport.toJson(option);
            Map<String, Object> fields = DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });

            // A JSON number deserializes to Integer under an Object binding; a quoted string would
            // have become a String. This proves optionNumber is on the wire as a bare number.
            assertThat(fields.get("optionNumber"))
                    .isInstanceOf(Integer.class)
                    .isEqualTo(7);
        }
    }

    // =====================================================================
    // Phase 2 — field fidelity
    // =====================================================================

    @Nested
    @DisplayName("Phase 2 — field fidelity")
    class FieldFidelity {

        @Test
        @DisplayName("targetProgram echoes the COBOL program id (e.g. COACTVWC) unchanged")
        void targetProgramEchoesCobolProgramIdUnchanged() {
            MenuOption option = new MenuOption(1, "Account View", "COACTVWC", "U");

            // Preserved verbatim as a String, both on the accessor and on the wire.
            assertThat(DtoTestSupport.roundTrip(option, MenuOption.class).targetProgram())
                    .isEqualTo("COACTVWC");
            assertThat(DtoTestSupport.toJson(option)).contains("\"targetProgram\":\"COACTVWC\"");
        }

        @Test
        @DisplayName("userType distinguishes admin (\"A\") from regular user (\"U\") and echoes each")
        void userTypeDistinguishesAdminFromRegularUser() {
            // The record keeps the raw legacy visibility flag: "U" (regular) vs "A" (administrator).
            MenuOption regular = new MenuOption(8, "Transaction Add", "COTRN02C", "U");
            MenuOption admin = new MenuOption(8, "Transaction Add", "COTRN02C", "A");

            assertThat(regular.userType()).isEqualTo("U");
            assertThat(admin.userType()).isEqualTo("A");
            // Differing only by the visibility flag makes them distinct values.
            assertThat(regular).isNotEqualTo(admin);
            // Each flag survives the JSON round-trip verbatim.
            assertThat(DtoTestSupport.roundTrip(regular, MenuOption.class).userType()).isEqualTo("U");
            assertThat(DtoTestSupport.roundTrip(admin, MenuOption.class).userType()).isEqualTo("A");
        }

        @Test
        @DisplayName("userType is null for admin-menu options (COADM02Y has no user-type field) and stays null")
        void userTypeIsNullForAdminMenuOptions() {
            MenuOption adminOption = ADMIN_MENU_USER_LIST;

            assertThat(adminOption.userType()).isNull();

            MenuOption restored = DtoTestSupport.roundTrip(adminOption, MenuOption.class);
            assertThat(restored.userType()).isNull();
            assertThat(restored).isEqualTo(adminOption);
        }

        @Test
        @DisplayName("targetProgram width cap mirrors PIC X(08): 8 chars valid, 9 chars violates")
        void targetProgramWidthCapMirrorsCopybookPicX08() {
            // "COACTVWC" is exactly 8 characters — the copybook PIC X(08) width — so it is valid.
            MenuOption valid = new MenuOption(1, "Account View", "COACTVWC", "U");
            assertThat(DtoTestSupport.validate(valid)).isEmpty();

            // A 9-character program id exceeds @Size(max = 8) and must raise a constraint violation
            // whose property path points at targetProgram.
            MenuOption tooLongProgram = new MenuOption(1, "Account View", "COACTVWCX", "U");
            Set<ConstraintViolation<MenuOption>> violations = DtoTestSupport.validate(tooLongProgram);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("targetProgram"));
        }
    }

    // =====================================================================
    // Phase 3 — record equality
    // =====================================================================

    @Nested
    @DisplayName("Phase 3 — record equality")
    class RecordEquality {

        @Test
        @DisplayName("equal-arg options are equals()/hashCode() consistent; different args are not equal")
        void equalArgOptionsAreEqualAndHashCodeConsistent() {
            MenuOption first = new MenuOption(1, "Account View", "COACTVWC", "U");
            MenuOption second = new MenuOption(1, "Account View", "COACTVWC", "U");
            MenuOption different = new MenuOption(2, "Account Update", "COACTUPC", "U");

            // Record component-wise value semantics: equal args => equal + same hashCode (symmetric).
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).isNotEqualTo(different);
        }

        @Test
        @DisplayName("record value semantics enable list equality (used by MenuResponse list assertions)")
        void recordSemanticsEnableListEquality() {
            List<MenuOption> expected = List.of(
                    new MenuOption(1, "Account View", "COACTVWC", "U"),
                    new MenuOption(2, "Account Update", "COACTUPC", "U"));
            List<MenuOption> actual = List.of(
                    new MenuOption(1, "Account View", "COACTVWC", "U"),
                    new MenuOption(2, "Account Update", "COACTUPC", "U"));

            // Because each element compares by value, whole-list equality holds — the property the
            // menu response tests rely on when asserting the built option lists.
            assertThat(actual).isEqualTo(expected);
            assertThat(actual).containsExactlyElementsOf(expected);
        }
    }
}
