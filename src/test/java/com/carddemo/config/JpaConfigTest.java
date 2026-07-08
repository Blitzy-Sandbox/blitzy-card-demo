package com.carddemo.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Fast, context-free unit test for {@link JpaConfig}'s fail-fast startup guard
 * {@code assertNonMutatingDdlAuto()}.
 *
 * <p>The single behavior under test is the {@code @PostConstruct} guard that inspects
 * {@code spring.jpa.hibernate.ddl-auto} and refuses to boot when it resolves to a
 * schema-mutating value. Flyway owns the migrated schema (V1&rarr;V2&rarr;V3), so the
 * signed packed-decimal COBOL fields (for example {@code WS-TEMP-BAL PIC S9(09)V99} in
 * the posting program {@code app/cbl/CBTRN02C.cbl} @ SHA {@code 27d6c6f}) become
 * {@code NUMERIC(p,s)} columns that Hibernate must only {@code validate} — never rewrite.
 * This test asserts both guard branches:</p>
 * <ul>
 *   <li>the four mutating values ({@code create}, {@code create-drop}, {@code update},
 *       {@code drop}) — including mixed-case / whitespace-padded variants that exercise the
 *       {@code trim().toLowerCase(Locale.ROOT)} normalization — throw
 *       {@link IllegalStateException}; and</li>
 *   <li>the non-mutating values ({@code validate}, {@code none}, in any case) as well as an
 *       unset, empty, or blank property (all of which default to {@code validate}) start
 *       cleanly with no exception.</li>
 * </ul>
 *
 * <p>The test bootstraps no Spring {@code ApplicationContext} and touches no database, file,
 * or network: each case constructs a fresh {@link JpaConfig} over a {@link MockEnvironment}
 * and invokes the guard directly (the method is package-private and this test shares the
 * {@code com.carddemo.config} package, so no reflection is required). The design rationale for
 * the guard is documented in {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("JpaConfig — ddl-auto fail-fast guard (assertNonMutatingDdlAuto)")
class JpaConfigTest {

    /** Property key the production guard resolves from the active profile. */
    private static final String DDL_AUTO_KEY = "spring.jpa.hibernate.ddl-auto";

    /**
     * Builds a fresh {@link JpaConfig} whose injected {@link MockEnvironment} exposes the
     * supplied {@code ddl-auto} value. A new instance is created per case so no state leaks
     * between invocations.
     *
     * @param ddlAuto the raw {@code spring.jpa.hibernate.ddl-auto} value to expose
     * @return a {@code JpaConfig} ready for a direct {@code assertNonMutatingDdlAuto()} call
     */
    private static JpaConfig configWithDdlAuto(String ddlAuto) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(DDL_AUTO_KEY, ddlAuto);
        return new JpaConfig(environment);
    }

    // ---------------------------------------------------------------------
    // Forbidden (schema-mutating) values -> IllegalStateException
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "ddl-auto=''{0}'' is rejected")
    @ValueSource(strings = {"create", "create-drop", "update", "drop"})
    @DisplayName("Schema-mutating ddl-auto values are rejected with a descriptive IllegalStateException")
    void mutatingDdlAutoValuesAreRejected(String mutatingValue) {
        JpaConfig config = configWithDdlAuto(mutatingValue);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, config::assertNonMutatingDdlAuto);

        // Resilient substring assertions: verify the message is informative without
        // hardcoding the full sentence (which is documentation, not a contract).
        String message = ex.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("ddl-auto"),
                () -> "message should name the offending property 'ddl-auto': " + ex.getMessage());
        assertTrue(message.contains("refusing to start"),
                () -> "message should state it is refusing to start: " + ex.getMessage());
    }

    @ParameterizedTest(name = "ddl-auto=''{0}'' is normalized then rejected")
    @ValueSource(strings = {"  CREATE  ", "UPDATE", " Drop ", "Create-Drop"})
    @DisplayName("Mixed-case / whitespace-padded mutating values are normalized (trim + lowercase) then rejected")
    void mutatingDdlAutoValuesAreNormalizedThenRejected(String paddedMutatingValue) {
        JpaConfig config = configWithDdlAuto(paddedMutatingValue);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, config::assertNonMutatingDdlAuto);

        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("ddl-auto"),
                () -> "message should name the offending property 'ddl-auto': " + ex.getMessage());
    }

    // ---------------------------------------------------------------------
    // Allowed (non-mutating) values -> no exception
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "ddl-auto=''{0}'' is permitted")
    @ValueSource(strings = {"validate", "none", "VALIDATE", "None"})
    @DisplayName("Non-mutating ddl-auto values (validate/none, any case) start cleanly")
    void nonMutatingDdlAutoValuesAreAllowed(String allowedValue) {
        JpaConfig config = configWithDdlAuto(allowedValue);

        assertDoesNotThrow(config::assertNonMutatingDdlAuto);
    }

    @Test
    @DisplayName("Unset, empty, or blank ddl-auto defaults to 'validate' and starts cleanly")
    void absentEmptyOrBlankDdlAutoDefaultsToValidate() {
        // Absent: the property is never set, so the guard falls back to its "validate" default.
        JpaConfig absentConfig = new JpaConfig(new MockEnvironment());
        assertDoesNotThrow(absentConfig::assertNonMutatingDdlAuto);

        // Empty: present but empty normalizes to "" which is not in the forbidden set.
        JpaConfig emptyConfig = configWithDdlAuto("");
        assertDoesNotThrow(emptyConfig::assertNonMutatingDdlAuto);

        // Blank: surrounding whitespace trims to "" — still treated as validate.
        JpaConfig blankConfig = configWithDdlAuto("   ");
        assertDoesNotThrow(blankConfig::assertNonMutatingDdlAuto);
    }
}
