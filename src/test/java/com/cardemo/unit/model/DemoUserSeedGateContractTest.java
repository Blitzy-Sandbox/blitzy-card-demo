/*
 * ****************************************************************************
 * Program     : DemoUserSeedGateContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Pins the two stored-data protections that no single file can
 *               demonstrate, because each is a relationship between files.
 *               ONE - the ten demonstration principals of DUSRSECJ.jcl are
 *               installed only where a demonstration corpus is the point. The
 *               seed is gated on the Flyway placeholder seeddemousers, which
 *               defaults to FALSE in the base profile, is TRUE only in local and
 *               test, and is FALSE explicitly in prod. Because the gate lives
 *               inside the migration and Flyway checksums the RAW script text
 *               before substitution, one file with one checksum applies in every
 *               environment and validate-on-migrate stays meaningful.
 *               TWO - the card verification value is absent from the operational
 *               target outright: no column, no seeded value, no entity field, no
 *               request or response component.
 * Source      : app/jcl/DUSRSECJ.jcl      (SYSUT1 DD * - the ten CSUSR01Y rows,
 *                                          every one carrying the literal
 *                                          plaintext password PASSWORD)
 *               app/cpy/CSUSR01Y.cpy      (the 80-byte user security layout)
 *               app/cpy/CVACT02Y.cpy      (CARD-CVV-CD PIC 9(03) - the frozen
 *                                          field contract, retained in app/ as
 *                                          the reference of record)
 *               app/cbl/CBACT04C.cbl      (canonical banner form, L1-L21)
 *               CONTRIBUTING.md, NOTICE   (style and licence conventions)
 *                                                                  @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License.
 * ****************************************************************************
 */

package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.entity.Card;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the stored-data protections applied for review findings F1 and F13.
 *
 * <p>Both were findings before they were tests, and both are relationships between files rather than
 * properties of any one file. That is why the profile text and the migration text are read from disk
 * instead of a context being booted: what needs proving is that <em>no</em> profile opens the gate
 * unintentionally and that the migration carries exactly one gate, which a started context cannot
 * demonstrate because it only ever shows the single value that happened to win.
 *
 * <p>The end-to-end behaviour of the gate - ten principals installed when open, none when closed, with
 * identical schema-history checksums either way - is proven by running the real migration against a real
 * PostgreSQL instance. That evidence is recorded in the validation-gate documentation rather than
 * reproduced here, because it needs a database and this tier does not take one.
 */
@DisplayName("Stored-data protection: a gated demo seed, and no verification value anywhere")
class DemoUserSeedGateContractTest {

    /** The Flyway placeholder that gates the demonstration principals. */
    private static final String PLACEHOLDER = "seeddemousers";

    /** The seed migration, read as text so the gate can be counted rather than inferred. */
    private static final Path SEED_MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V3__seed_data.sql");

    /** The schema migration, read as text so the absent column can be asserted. */
    private static final Path SCHEMA_MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V1__create_schema.sql");

    /** Profiles that must leave the gate closed. */
    private static final List<String> CLOSED_PROFILES =
            List.of("application.yml", "application-prod.yml");

    /** Profiles that open the gate, a demonstration corpus being the point of both. */
    private static final List<String> OPEN_PROFILES =
            List.of("application-local.yml", "application-test.yml");

    /** The ten principals of {@code app/jcl/DUSRSECJ.jcl}, in the order the seed lists them. */
    private static final List<String> DEMO_PRINCIPALS = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /**
     * Matches the placeholder declaration and captures the value, tolerating any indentation.
     *
     * <p>Anchored on the property name rather than on a fixed indentation so that a profile which nests
     * the key at a different depth is still read, and so a value of {@code true} cannot hide behind
     * reformatting.
     */
    private static final Pattern GATE_VALUE =
            Pattern.compile("^\\s*" + PLACEHOLDER + ":\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    /**
     * Reads a classpath resource as UTF-8 text.
     *
     * @param resourceName the resource to read, relative to the classpath root
     * @return the resource content
     * @throws IOException if the resource cannot be read
     */
    private static String profile(final String resourceName) throws IOException {
        try (InputStream stream =
                DemoUserSeedGateContractTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(stream).as("%s must be on the classpath", resourceName).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads a repository file as UTF-8 text.
     *
     * @param path the repository-relative path to read
     * @return the file content
     * @throws IOException if the file cannot be read
     */
    private static String fileAt(final Path path) throws IOException {
        assertThat(path).as("%s must exist", path).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Extracts the single gate value declared by a profile.
     *
     * @param profileText the profile content
     * @return the declared value, lower-cased
     */
    private static String gateValueOf(final String profileText) {
        final Matcher matcher = GATE_VALUE.matcher(profileText);
        assertThat(matcher.find()).as("the profile must declare %s exactly once", PLACEHOLDER).isTrue();
        final String value = matcher.group(1).toLowerCase(Locale.ROOT);
        assertThat(matcher.find()).as("%s must not be declared twice in one profile", PLACEHOLDER)
                .isFalse();
        return value;
    }

    @Nested
    @DisplayName("F1 - the demonstration principals are installed only where they are the point")
    class SeedGate {

        @ParameterizedTest(name = "{0} leaves the gate closed")
        @ValueSource(strings = {"application.yml", "application-prod.yml"})
        @DisplayName("the base and production profiles both close the gate, and prod does so explicitly")
        void closedProfilesCloseTheGate(final String profileName) throws IOException {
            assertThat(gateValueOf(profile(profileName)))
                    .as("%s must not install a published credential", profileName)
                    .isEqualTo("false");
        }

        @ParameterizedTest(name = "{0} opens the gate")
        @ValueSource(strings = {"application-local.yml", "application-test.yml"})
        @DisplayName("the local and test profiles open the gate, keeping the fixture corpus available")
        void openProfilesOpenTheGate(final String profileName) throws IOException {
            assertThat(gateValueOf(profile(profileName)))
                    .as("%s must keep the AAP-mandated demonstration corpus available", profileName)
                    .isEqualTo("true");
        }

        /**
         * The default is closed, which is the property that makes the guard survive a new profile.
         *
         * <p>A guard that had to be remembered in each added profile would be one forgotten profile away
         * from being no guard at all. Asserting the base value is therefore asserting the failure mode,
         * not merely the value: an unrecognised profile inherits {@code false} and installs nothing.
         */
        @Test
        @DisplayName("the base profile default is closed, so an unlisted profile installs nothing")
        void theDefaultIsClosed() throws IOException {
            assertThat(gateValueOf(profile("application.yml"))).isEqualTo("false");
            assertThat(CLOSED_PROFILES).hasSize(2);
            assertThat(OPEN_PROFILES).hasSize(2);
        }

        /**
         * Exactly one gate governs the seed, and it governs the whole of it.
         *
         * <p>The count matters in both directions. Two placeholders would mean a second statement could
         * be opened independently of the first, and none would mean the rows install unconditionally. The
         * gate is also asserted to sit in a {@code WHERE} clause, because that is what makes it apply to
         * the row source as a whole rather than to one tuple.
         */
        @Test
        @DisplayName("the migration carries exactly one gate, in a WHERE clause over the whole row source")
        void theMigrationCarriesExactlyOneGate() throws IOException {
            final String seed = fileAt(SEED_MIGRATION);

            assertThat(seed.split(Pattern.quote("${" + PLACEHOLDER + "}"), -1).length - 1)
                    .as("exactly one placeholder reference governs the seed")
                    .isEqualTo(1);
            assertThat(seed).contains("WHERE ${" + PLACEHOLDER + "} = TRUE;");
        }

        /**
         * All ten principals sit behind the one gate, none having been left outside it.
         *
         * <p>Asserted by position rather than by presence: every identifier must appear after the
         * {@code SELECT} that the gate's {@code WHERE} clause filters, so a row appended above the gated
         * statement - the natural way for this protection to regress - fails here.
         */
        @Test
        @DisplayName("all ten principals sit behind the gate, none left outside it")
        void everyPrincipalSitsBehindTheGate() throws IOException {
            final String seed = fileAt(SEED_MIGRATION);
            final int gatedSelect = seed.indexOf("FROM (VALUES");
            final int gateClause = seed.indexOf("WHERE ${" + PLACEHOLDER + "} = TRUE;");

            assertThat(gatedSelect).as("the gated row source must be present").isNotNegative();
            assertThat(gateClause).as("the gate must follow the row source").isGreaterThan(gatedSelect);
            assertThat(DEMO_PRINCIPALS).allSatisfy(principal -> {
                final int at = seed.indexOf("('" + principal + "'");
                assertThat(at).as("%s must be seeded", principal).isNotNegative();
                assertThat(at).as("%s must sit inside the gated row source", principal)
                        .isGreaterThan(gatedSelect).isLessThan(gateClause);
            });
        }

        /**
         * No principal is stored as the plaintext the legacy job carried.
         *
         * <p>{@code app/jcl/DUSRSECJ.jcl} gives all ten the literal password {@code PASSWORD}. The seed
         * must carry only digests, so the plaintext is asserted absent and the digest form asserted
         * present ten times over, at the cost factor the transformation rules fix.
         */
        @Test
        @DisplayName("every seeded principal carries a BCrypt cost-10 digest and no plaintext")
        void everyPrincipalIsHashed() throws IOException {
            final String seed = fileAt(SEED_MIGRATION);

            assertThat(seed).as("the legacy plaintext must never be stored")
                    .doesNotContain("'PASSWORD'");
            assertThat(seed.split(Pattern.quote("'$2a$10$"), -1).length - 1)
                    .as("ten cost-10 digests, one per principal")
                    .isEqualTo(DEMO_PRINCIPALS.size());
        }

        /**
         * A closed gate installs nothing at all, rather than a substitute principal.
         *
         * <p>The alternative to a known credential is no credential. The schema declares no locked,
         * expired or must-reset state for a bootstrap account to be parked in, so none is invented; an
         * operator provisions one out of band. This asserts that no second, ungated insert into the
         * principal table crept in to fill the gap.
         */
        @Test
        @DisplayName("a closed gate installs no substitute bootstrap principal")
        void aClosedGateInstallsNoSubstitute() throws IOException {
            final String seed = fileAt(SEED_MIGRATION);

            assertThat(seed.split(Pattern.quote("INSERT INTO user_security"), -1).length - 1)
                    .as("exactly one insert targets the principal table, and it is the gated one")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("F13 - the card verification value is absent from the operational target")
    class VerificationValueAbsent {

        /**
         * Whether a name mentions a card verification value in any of its usual spellings.
         *
         * @param name the identifier to fold and test
         * @return {@code true} if the name refers to a verification value
         */
        private boolean mentionsVerificationValue(final String name) {
            return name.toLowerCase(Locale.ROOT).contains("cvv");
        }

        /**
         * Returns the SQL identifier text of a line, with comments and quoted literals removed.
         *
         * <p>Stripping single-quoted literals is required, not cosmetic. One seeded BCrypt digest is
         * {@code $2a$10$yC2yWBCVvR2Mp...}, which contains the letters {@code CVv}; a case-insensitive
         * substring search over the raw line therefore reports a verification value in a password hash.
         * The property under test is that no <em>identifier</em> names a verification column, so the
         * literals - where a digest, a name or an address legitimately holds arbitrary text - are removed
         * before the search. This is the same distinction the schema itself draws between a column name
         * and a column value.
         *
         * @param line one line of a migration script
         * @return the line's identifier text, possibly empty
         */
        private String identifierTextOf(final String line) {
            final String withoutComment = line.contains("--")
                    ? line.substring(0, line.indexOf("--"))
                    : line;
            return withoutComment.replaceAll("'[^']*'", "''");
        }

        /**
         * Lists every line of a migration whose identifier text names a verification value.
         *
         * @param migration the migration to scan
         * @return the offending lines, empty when the protection holds
         * @throws IOException if the migration cannot be read
         */
        private List<String> verificationIdentifierLines(final Path migration) throws IOException {
            return fileAt(migration).lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .filter(line -> mentionsVerificationValue(identifierTextOf(line)))
                    .toList();
        }

        @Test
        @DisplayName("the schema declares no verification column on any table")
        void theSchemaDeclaresNoVerificationColumn() throws IOException {
            assertThat(verificationIdentifierLines(SCHEMA_MIGRATION))
                    .as("no uncommented line of the baseline may declare a verification column")
                    .isEmpty();
        }

        @Test
        @DisplayName("the seed supplies no verification value for any of the fifty cards")
        void theSeedSuppliesNoVerificationValue() throws IOException {
            assertThat(verificationIdentifierLines(SEED_MIGRATION))
                    .as("no uncommented line of the seed may name a verification column")
                    .isEmpty();
        }

        /**
         * The literal-stripping helper is itself exercised, so the two probes above cannot pass vacuously.
         *
         * <p>A scan that silently stripped too much would report a clean file no matter what it contained.
         * This pins both directions: a quoted digest carrying the letters {@code CVv} is not a finding, and
         * a bare column declaration is.
         */
        @Test
        @DisplayName("the scan distinguishes a column named for a verification value from a digest holding one")
        void theScanDistinguishesIdentifiersFromLiterals() {
            assertThat(mentionsVerificationValue(identifierTextOf(
                    "  ('USER0001', '$2a$10$yC2yWBCVvR2MpALAjIxBj.PhUCay2jxyYaHiIJDtOC95LotiiHoD2', 'U'),")))
                    .as("a digest that happens to contain the letters is not a verification column")
                    .isFalse();
            assertThat(mentionsVerificationValue(identifierTextOf("  card_cvv_cd CHAR(3) NOT NULL,")))
                    .as("a column declaration is detected")
                    .isTrue();
            assertThat(mentionsVerificationValue(identifierTextOf("  card_num, card_cvv_cd, -- trailing")))
                    .as("a column list entry is detected even with a trailing comment")
                    .isTrue();
        }

        /**
         * Neither the entity nor any type in the update request graph declares a verification value.
         *
         * <p>Asserted structurally across the whole graph rather than by planting a specimen value and
         * checking it does not appear. Since the field no longer exists there is no value to plant, so a
         * value-based assertion would pass unconditionally; absence of the member is both the stronger
         * property and the one a future change would have to break to regress.
         */
        @Test
        @DisplayName("no entity field, accessor or request component declares a verification value")
        void noJavaMemberDeclaresAVerificationValue() {
            assertThat(Card.class.getDeclaredFields())
                    .noneMatch(field -> mentionsVerificationValue(field.getName()));
            assertThat(Card.class.getDeclaredMethods())
                    .noneMatch(method -> mentionsVerificationValue(method.getName()));
            assertThat(List.of(CardUpdateRequest.class, CardUpdateRequest.CardDetails.class,
                    CardUpdateRequest.CardData.class, CardUpdateRequest.ExpiraionDate.class))
                    .allSatisfy(type -> {
                        final RecordComponent[] components = type.getRecordComponents();
                        assertThat(components).as("%s is a record", type.getSimpleName()).isNotNull();
                        assertThat(components)
                                .noneMatch(component ->
                                        mentionsVerificationValue(component.getName()));
                    });
        }

        /**
         * The frozen copybook geometry is untouched, the removal being a target-side decision only.
         *
         * <p>{@code app/cpy/CVACT02Y.cpy} remains the reference of record and still declares
         * {@code CARD-CVV-CD}. The migration is additive and {@code app/} is frozen, so the copybook must
         * still carry the field even though the operational target does not. Asserting this keeps the two
         * claims from being confused: the value is not persisted, and the reference is not edited.
         */
        @Test
        @DisplayName("the frozen copybook still declares the field, app/ being untouched")
        void theFrozenCopybookIsUnchanged() throws IOException {
            assertThat(fileAt(Path.of("app", "cpy", "CVACT02Y.cpy")))
                    .as("the reference of record must still carry the field contract")
                    .contains("CARD-CVV-CD");
        }
    }
}
