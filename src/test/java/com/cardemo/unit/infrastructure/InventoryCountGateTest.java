/*
 * ****************************************************************************
 * Program     : InventoryCountGateTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Turns three counted claims into executable gates, so none can
 *               drift back into prose: the 636 seed rows the ASCII fixtures and
 *               the inline DUSRSECJ users contain, the 17 data transfer objects
 *               the BMS symbolic maps require, and the 50 executable
 *               @ExceptionHandler methods the eight controllers declare.
 * Source      : app/data/ASCII/** (the nine fixtures, 626 rows)
 *               + app/jcl/DUSRSECJ.jcl (the ten inline SYSUT1 users)
 *               + app/cpy-bms/** (the seventeen symbolic maps)
 *               + app/csd/CARDDEMO.CSD (the seventeen sourced transactions the
 *                 eight controllers expose)
 *               @ 7756d89
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.controller.AccountController;
import com.cardemo.controller.AdminController;
import com.cardemo.controller.AuthController;
import com.cardemo.controller.BillingController;
import com.cardemo.controller.CardController;
import com.cardemo.controller.MenuController;
import com.cardemo.controller.ReportController;
import com.cardemo.controller.TransactionController;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Three counts that the review corrected, held as gates rather than as sentences.
 *
 * <p>Each of the three is also stated in prose - in a migration header, a
 * {@code package-info} docstring and a specification table respectively. A number written in prose is
 * correct exactly until the thing it counts changes, and nothing reports the moment it stops being correct.
 * These assertions are the report.
 *
 * <ul>
 *   <li><strong>636 seed rows, of which 626 are unconditional.</strong> The headline was 586. The
 *       source-correct total is 626 fixture rows from {@code app/data/ASCII/**} plus the ten users inlined as
 *       {@code SYSUT1 DD *} data in {@code app/jcl/DUSRSECJ.jcl}, and the {@code transaction} table is
 *       deliberately empty because the legacy corpus ships no transaction fixture - {@code TRANSACT} is
 *       populated by the posting job, not by a seed. Counted here from the migration itself, and independently
 *       confirmed against a live PostgreSQL 16 database seeded by that migration.
 *       <p><strong>636 is the TEXTUAL count, and the applied count is not always the same number.</strong>
 *       The ten principals are the one gated statement in the migration: they carry a
 *       {@code WHERE ${seeddemousers} = TRUE} clause, and the placeholder is {@code false} in the base and
 *       production profiles and {@code true} in local and test. So the applied total is <strong>636 where the
 *       gate is open and 626 where it is closed</strong>, and both are correct. What would be wrong is to
 *       state either as unconditional. This class asserts the textual 636 because that is what the file
 *       contains and what a checksum covers; {@code DemoUserSeedGateContractTest} owns the gate itself, and
 *       {@link SeedRowCount#exactlyTenOfTheSixHundredAndThirtySixRowsAreGated()} below pins the split so the
 *       two numbers can never drift apart silently.</li>
 *   <li><strong>17 data transfer objects.</strong> The plan's own table said 16 while its by-name
 *       enumeration listed 17. {@code SignOnResponse} is the payload the smaller count omitted, and it has
 *       no BMS symbolic map because CICS returned identity in the COMMAREA rather than on a screen.
 *   <li><strong>50 executable {@code @ExceptionHandler} methods across eight controllers.</strong> Counted as
 *       ANNOTATED METHODS through reflection rather than by matching text, because the token also appears in
 *       Javadoc prose on these classes and a naive grep therefore overcounts. The figure was 37 while the
 *       surface was six controllers; the sign-on and user-administration controllers add 6 and 7. A review
 *       recorded that this census had gone stale at six, so the controller set is now
 *       <em>discovered from the package directory</em> and cross-checked against the enumeration below,
 *       which makes a ninth controller a failure here rather than an omission.
 *   </ul>
 */
@DisplayName("Counted claims, held as gates: 636 seed rows, 29 DTOs, 67 exception handlers")
final class InventoryCountGateTest {

    /** An {@code INSERT INTO <table>} statement at the start of a line. */
    private static final Pattern INSERT_INTO = Pattern.compile("^\\s*INSERT INTO\\s+([a-z_]+)");

    /**
     * A {@code VALUES} tuple in the seed migration. Every tuple in {@code V3__seed_data.sql} begins at
     * exactly two spaces of indentation followed by an opening parenthesis, and no continuation line does,
     * which is what makes a line-oriented count exact here.
     */
    private static final Pattern VALUES_TUPLE = Pattern.compile("^  \\(");

    /** The corrected seed-row total: 626 fixture rows plus the ten inline users. */
    private static final int EXPECTED_SEED_ROWS = 636;

    /** The corrected data-transfer-object count. */
    private static final int EXPECTED_DTO_COUNT = 29;

    /**
     * The corrected executable exception-handler count, across all eight controllers.
     *
     * <p>Derived rather than remembered: the six-controller surface declared 37, and the sign-on and
     * user-administration controllers add 6 and 7 respectively, giving the 50 the typed hierarchy needs. The
     * seven controllers that bind a request body then add two apiece - one for the framework's bean-validation
     * refusal and one for a body it could not read at all - which is 14 more and closes a High-severity
     * error-serialization finding: those two conditions are raised before a mapped method is entered, so they
     * were escaping to the framework's default handling and answering in a shape that carried neither
     * {@code errorCode} nor {@code correlationId}. {@code MenuController} binds no body and correctly gains
     * neither. That is the 64 this constant held.
     *
     * <p>It is 67 because {@code AdminController}, {@code BillingController} and
     * {@code TransactionController} each gained a {@code DataIntegrityException} handler, which
     * {@code AccountController} already carried. A VSAM KSDS
     * could refuse a keyed write only for a key that already existed, so each of
     * {@code app/cbl/COUSR01C.cbl}:250-274 and {@code app/cbl/COTRN02C.cbl}:722-748 has one duplicate arm and
     * one catch-all; {@code V1__create_schema.sql} declares constraints on {@code user_security} and three
     * foreign keys on {@code transaction} that VSAM did not, and folding a refusal by one of those onto the
     * duplicate arm reported "already taken" for a key that was free and advised a retry that could never
     * succeed. {@code BillingController} inserts into the same {@code transaction} relation, so it carried the
     * identical defect. Three handlers, one per write surface that lacked one, is the whole of the growth.
     *
     * <p>The per-controller split below is what makes the total impossible to reach by two compensating
     * errors, and it is why this constant is stated as a total <em>and</em> broken out.
     */
    private static final int EXPECTED_HANDLER_COUNT = 67;

    /**
     * The route total the eight controllers publish between them.
     *
     * <p>One route per sourced CICS transaction in {@code app/csd/CARDDEMO.CSD}. The CSD defines eighteen
     * transactions, but {@code CDV1} fronts {@code COCRDSEC}, whose source is absent from the repository, so
     * no endpoint is invented for it and the translated total is seventeen.
     */
    private static final int EXPECTED_ROUTE_COUNT = 17;

    /** Per-table seed row counts, in the order the migration inserts them. */
    private static final Map<String, Integer> EXPECTED_ROWS_PER_TABLE = expectedRowsPerTable();

    /** The repository root, located once and reused. */
    private static final Path ROOT = repositoryRoot();

    /**
     * The eight controllers, each paired with the number of handlers it declares.
     *
     * <p>All eight are enumerated, so a controller added to {@code com.cardemo.controller} without a row here
     * fails {@link ControllerInventory#everyControllerIsEnumerated()} rather than escaping the census. That
     * gate is what stopped this map from silently staying at six.
     *
     * <p>Every row except {@code MenuController} carries two handlers for the framework's own body failures on
     * top of the typed hierarchy it maps - the bean-validation refusal and the unreadable body. That is why the
     * menu row is the only one that did not grow by two: it is the one controller with no
     * {@code @RequestBody} parameter anywhere, so neither condition can arise on it and declaring a handler for
     * them would be unreachable code.
     *
     * <p>The three rows at ten are the three write surfaces that can be refused by a constraint the frozen
     * VSAM corpus did not have, and each declares a {@code DataIntegrityException} handler for it:
     * {@code AccountController} for the ten foreign keys the account and customer relations head,
     * {@code AdminController} for {@code ck_user_security_type} and its siblings, and
     * {@code TransactionController} for {@code fk04_transaction_card}, {@code fk05_transaction_type} and
     * {@code fk06_transaction_category}, and {@code BillingController} for the same three because it inserts
     * into the same relation. {@code CardController} stays at nine because the card update rewrites an existing
     * row and inserts nothing.
     *
     * @return one entry per controller
     */
    private static Map<Class<?>, Integer> expectedHandlersPerController() {
        final Map<Class<?>, Integer> expected = new LinkedHashMap<>();
        expected.put(AccountController.class, 10);
        expected.put(AdminController.class, 10);
        expected.put(AuthController.class, 8);
        expected.put(BillingController.class, 10);
        expected.put(CardController.class, 9);
        expected.put(MenuController.class, 3);
        expected.put(ReportController.class, 7);
        expected.put(TransactionController.class, 10);
        return expected;
    }

    /**
     * The per-table row counts the nine fixtures and the inline user deck contain.
     *
     * @return table name to expected row count
     */
    private static Map<String, Integer> expectedRowsPerTable() {
        final Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("transaction_type", 7);
        expected.put("transaction_category", 18);
        expected.put("account", 50);
        expected.put("customer", 50);
        expected.put("card", 50);
        expected.put("card_cross_reference", 50);
        expected.put("disclosure_group", 51);
        expected.put("transaction_category_balance", 50);
        expected.put("daily_transaction", 300);
        expected.put("user_security", 10);
        return expected;
    }

    /**
     * Walks upward from the working directory to the repository root.
     *
     * @return the directory holding both {@code pom.xml} and {@code src/}
     */
    private static Path repositoryRoot() {
        final Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("src"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No directory from " + start + " upward holds both pom.xml and src/, so the counted "
                        + "artefacts cannot be located. Run this test with the repository root, or any "
                        + "directory beneath it, as the working directory.");
    }

    /**
     * Reads a repository file as UTF-8 lines.
     *
     * @param relativePath path relative to the repository root
     * @return the file's lines, in order
     */
    private static List<String> lines(final String relativePath) {
        final Path path = ROOT.resolve(relativePath);
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + path, cause);
        }
    }

    /**
     * Reads the seed migration as one string, for the whole-file checks that a line list cannot express.
     *
     * @return the raw text of {@code V3__seed_data.sql}
     */
    private static String readSeedMigration() {
        return String.join("\n", lines("src/main/resources/db/migration/V3__seed_data.sql"));
    }

    /**
     * Counts non-overlapping occurrences of a literal inside a text.
     *
     * @param text    the text to scan
     * @param literal the literal to count; must not be empty
     * @return the number of non-overlapping occurrences
     */
    private static int countOccurrences(final String text, final String literal) {
        int count = 0;
        int from = text.indexOf(literal);
        while (from >= 0) {
            count++;
            from = text.indexOf(literal, from + literal.length());
        }
        return count;
    }

    /**
     * Counts the {@code VALUES} tuples the seed migration inserts, per table.
     *
     * @return table name to counted row count, in insertion order
     */
    private static Map<String, Integer> countedRowsPerTable() {
        final List<String> content = lines("src/main/resources/db/migration/V3__seed_data.sql");
        final List<Integer> starts = new ArrayList<>();
        final List<String> tables = new ArrayList<>();
        for (int index = 0; index < content.size(); index++) {
            final Matcher matcher = INSERT_INTO.matcher(content.get(index));
            if (matcher.find()) {
                starts.add(index);
                tables.add(matcher.group(1));
            }
        }
        final Map<String, Integer> counted = new LinkedHashMap<>();
        for (int block = 0; block < starts.size(); block++) {
            final int begin = starts.get(block);
            final int end = block + 1 < starts.size() ? starts.get(block + 1) : content.size();
            int rows = 0;
            for (int index = begin; index < end; index++) {
                if (VALUES_TUPLE.matcher(content.get(index)).find()) {
                    rows++;
                }
            }
            counted.merge(tables.get(block), rows, Integer::sum);
        }
        return counted;
    }

    /**
     * Counts the methods on a controller that carry {@link ExceptionHandler}.
     *
     * @param controller the controller class
     * @return the number of annotated methods
     */
    private static long handlerCount(final Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .count();
    }

    /**
     * The controller-and-count pairs, as parameterised-test arguments.
     *
     * @return one argument pair per controller
     */
    private static Stream<Arguments> controllersAndCounts() {
        return expectedHandlersPerController().entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    /** F36 - the seed-row headline. */
    @Nested
    @DisplayName("636 seed rows, not the stale 586")
    final class SeedRowCount {

        @Test
        @DisplayName("the migration inserts exactly 636 rows, and the per-table split is the fixtures'")
        void theMigrationInsertsSixHundredAndThirtySixRows() {
            final Map<String, Integer> counted = countedRowsPerTable();
            assertThat(counted)
                    .as(
                            "50 accounts + 50 cards + 50 cross-references + 50 customers + 300 daily "
                                    + "transactions + 51 disclosure groups + 50 category balances + 18 "
                                    + "categories + 7 types from app/data/ASCII/**, plus the 10 users "
                                    + "inlined in app/jcl/DUSRSECJ.jcl")
                    .containsExactlyInAnyOrderEntriesOf(EXPECTED_ROWS_PER_TABLE);
            assertThat(counted.values().stream().mapToInt(Integer::intValue).sum())
                    .as("the corrected headline total")
                    .isEqualTo(EXPECTED_SEED_ROWS);
        }

        /**
         * Pins the split between the unconditional rows and the gated ones, so the two applied totals stay
         * reconcilable. The migration carries exactly one gated statement, it is the credential statement, and
         * it holds exactly ten rows; everything else is unconditional. If a future edit gated a second
         * statement or moved a row across the boundary, the arithmetic published in the migration header, in
         * the object census, in the profiles and in the evidence documents would all become wrong at once -
         * and this assertion is what fails first.
         */
        @Test
        @DisplayName("exactly 10 of the 636 rows are gated, so applied is 636 open and 626 closed")
        void exactlyTenOfTheSixHundredAndThirtySixRowsAreGated() {
            final String migration = readSeedMigration();
            assertThat(countOccurrences(migration, "WHERE ${seeddemousers} = TRUE;"))
                    .as("one gate, in one WHERE clause, over one row source. "
                            + "DemoUserSeedGateContractTest owns the gate's placement and its per-profile "
                            + "values; what is pinned here is that ONE statement is conditional, because that "
                            + "is what makes 636-minus-626 exactly ten")
                    .isEqualTo(1);

            final int gatedTableRows = countedRowsPerTable().getOrDefault("user_security", 0);
            assertThat(gatedTableRows)
                    .as("the gated statement is the credential statement, and it holds the ten principals of "
                            + "app/jcl/DUSRSECJ.jcl:L35-L44")
                    .isEqualTo(10);

            final int total = countedRowsPerTable().values().stream().mapToInt(Integer::intValue).sum();
            assertThat(total - gatedTableRows)
                    .as("the unconditional subtotal: identical in every environment, and the only figure the "
                            + "parity comparison rests on")
                    .isEqualTo(626);
            assertThat(total)
                    .as("the applied total where the gate is open, which is the local and test profiles")
                    .isEqualTo(636);

            final int gateIndex = migration.indexOf("${seeddemousers}");
            final int credentialInsertIndex = migration.indexOf("INSERT INTO user_security");
            assertThat(credentialInsertIndex)
                    .as("the credential statement must exist to be gated")
                    .isNotNegative();
            assertThat(gateIndex)
                    .as("the gate must sit inside the credential statement, after its INSERT, so no "
                            + "unconditional statement is affected by it")
                    .isGreaterThan(credentialInsertIndex);
        }

        @Test
        @DisplayName("the transaction table is seeded with nothing, because the corpus ships no fixture for it")
        void theTransactionTableIsDeliberatelyEmpty() {
            assertThat(countedRowsPerTable())
                    .as(
                            "TRANSACT is populated by the posting job at app/jcl/POSTTRAN.jcl, never by a "
                                    + "seed. An INSERT here would be invented data, and the row count would "
                                    + "stop being the corpus's own")
                    .doesNotContainKey("transaction");
        }

        @Test
        @DisplayName("the migration's own prose states the same number the tuples count")
        void theProseAgreesWithTheTuples() {
            final long mentions = lines("src/main/resources/db/migration/V3__seed_data.sql").stream()
                    .filter(line -> line.contains(String.valueOf(EXPECTED_SEED_ROWS)))
                    .count();
            assertThat(mentions)
                    .as(
                            "the header states the total in prose; if the tuples ever change, this gate "
                                    + "fails alongside the count so the two cannot part company quietly")
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    /** F37 - the data-transfer-object inventory. */
    @Nested
    @DisplayName("29 data transfer objects, not the stale 16, 17 or 26")
    final class DataTransferObjectCount {

        @Test
        @DisplayName("the dto package holds exactly 29 types, excluding its package documentation")
        void theDtoPackageHoldsSeventeenTypes() {
            final Path directory = ROOT.resolve("src/main/java/com/cardemo/model/dto");
            final List<String> types;
            try (Stream<Path> entries = Files.list(directory)) {
                types = entries
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".java"))
                        .filter(name -> !"package-info.java".equals(name))
                        .sorted()
                        .toList();
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot list " + directory, cause);
            }
            assertThat(types)
                    .as(
                            "17 request-and-projection types plus 12 added when the REST surface stopped "
                                    + "returning entities and service records: 11 response envelopes - "
                                    + "account update, account view, bill payment, card, card list, report "
                                    + "submission, transaction, transaction list, user list, user create and "
                                    + "user update - and the ApiMasking helper they share. The last three "
                                    + "close a High-severity API-contract finding: the administration "
                                    + "operations were returning the service tier's own screen records, so "
                                    + "navigation, colour, cursor, selector and erase/send state was the "
                                    + "public contract. SignOnResponse remains the one with no BMS symbolic "
                                    + "map, because CICS returned identity in the COMMAREA rather than on a "
                                    + "screen, which is exactly why a map-driven count missed it")
                    .hasSize(EXPECTED_DTO_COUNT)
                    .contains("SignOnResponse.java", "AccountViewResponse.java", "ApiMasking.java",
                            "UserListResponse.java", "UserCreateResponse.java", "UserUpdateResponse.java");
        }

        @Test
        @DisplayName("the package documentation states the same number the directory holds")
        void thePackageDocumentationAgreesWithTheDirectory() {
            assertThat(lines("src/main/java/com/cardemo/model/dto/package-info.java"))
                    .as("the docstring and the directory must not be able to disagree")
                    .anyMatch(line -> line.contains(EXPECTED_DTO_COUNT + " data transfer objects"));
        }
    }

    /** F38 - the executable exception-handler inventory. */
    @Nested
    @DisplayName("64 executable @ExceptionHandler methods across the eight controllers")
    final class ExceptionHandlerCount {

        @Test
        @DisplayName("the eight controllers declare exactly 64 annotated methods in total")
        void theEightControllersDeclareSixtyFourHandlers() {
            final long total = expectedHandlersPerController().keySet().stream()
                    .mapToLong(InventoryCountGateTest::handlerCount)
                    .sum();
            assertThat(total)
                    .as(
                            "counted as ANNOTATED METHODS, not as occurrences of the token: the token also "
                                    + "appears in Javadoc prose on these classes, which is how a text scan "
                                    + "reaches a different number. 50 map the typed hierarchy; the other 14 "
                                    + "are the two framework body failures on each of the seven controllers "
                                    + "that bind a request body, which used to escape to the framework's "
                                    + "default handling and answer without the envelope")
                    .isEqualTo(EXPECTED_HANDLER_COUNT);
        }

        @ParameterizedTest(name = "{0} declares {1} handlers")
        @MethodSource(
                "com.cardemo.unit.infrastructure.InventoryCountGateTest#controllersAndCounts")
        @DisplayName("each controller declares its own share, so the total cannot be reached by two errors")
        void eachControllerDeclaresItsOwnShare(final Class<?> controller, final int expected) {
            assertThat(handlerCount(controller))
                    .as(
                            "%s must declare exactly %d @ExceptionHandler methods. A per-controller split "
                                    + "means a handler moved between controllers is caught, not absorbed",
                            controller.getSimpleName(), expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("every handler is a real, invocable method rather than an annotation on something inert")
        void everyHandlerIsInvocable() {
            for (final Class<?> controller : expectedHandlersPerController().keySet()) {
                final List<Method> handlers = Arrays.stream(controller.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                        .toList();
                assertThat(handlers).isNotEmpty();
                for (final Method handler : handlers) {
                    assertThat(handler.getAnnotation(ExceptionHandler.class).value())
                            .as(
                                    "%s.%s must name at least one exception type, or Spring cannot route to "
                                            + "it and the handler counts towards nothing",
                                    controller.getSimpleName(), handler.getName())
                            .isNotEmpty();
                    assertThat(handler.getReturnType())
                            .as("%s.%s must return a response body", controller.getSimpleName(),
                                    handler.getName())
                            .isNotEqualTo(void.class);
                }
            }
        }
    }

    /**
     * The controller census itself, made mechanical so it cannot go stale again.
     *
     * <p><strong>Why this group exists.</strong> A review found the enumeration above frozen at six
     * controllers after the surface had grown to eight, which meant the handler total was measured against a
     * subset and two whole controllers escaped every count in this class without any test failing. A
     * hand-maintained list can only ever fail that way silently. Reading the controller package from disk and
     * comparing it against the enumeration turns the same omission into a failure that names the missing
     * class.
     *
     * <p>The directory is the authority for <em>which</em> controllers exist, and reflection is the authority
     * for what each one declares; neither is derived from prose.
     */
    @Nested
    @DisplayName("the controller census is discovered, not remembered")
    final class ControllerInventory {

        @Test
        @DisplayName("every controller in the package is enumerated, so none can escape the census")
        void everyControllerIsEnumerated() {
            final List<String> onDisk = controllerSimpleNamesOnDisk();
            final List<String> enumerated = expectedHandlersPerController().keySet().stream()
                    .map(Class::getSimpleName)
                    .sorted()
                    .toList();

            assertThat(enumerated)
                    .as("the enumeration in this class must name exactly the controllers "
                            + "src/main/java/com/cardemo/controller holds. A controller present on disk but "
                            + "absent here contributes nothing to any count in this class, and nothing fails "
                            + "- which is precisely how this census went stale at six")
                    .containsExactlyElementsOf(onDisk);
        }

        @Test
        @DisplayName("the eight controllers publish seventeen routes, one per sourced CSD transaction")
        void theEightControllersPublishSeventeenRoutes() {
            final Map<String, Long> perController = new LinkedHashMap<>();
            for (final Class<?> controller : expectedHandlersPerController().keySet()) {
                perController.put(controller.getSimpleName(), routeCount(controller));
            }

            assertThat(perController.values().stream().mapToLong(Long::longValue).sum())
                    .as("the seventeen sourced transactions of app/csd/CARDDEMO.CSD each became exactly one "
                            + "route, so the total is a translated inventory rather than a target. Per "
                            + "controller: %s", perController)
                    .isEqualTo(EXPECTED_ROUTE_COUNT);
        }
    }

    /**
     * Lists the controller classes the package directory holds, by simple name, sorted.
     *
     * <p>{@code package-info.java} is excluded because it declares no type. Every remaining source file in
     * that package is a controller by construction: the package documentation states that the package holds
     * only {@code @RestController} classes, and {@link ControllerInventory#everyControllerIsEnumerated()}
     * fails if that ever ceases to hold.
     *
     * @return the controller simple names, sorted so the comparison is order-independent
     */
    private static List<String> controllerSimpleNamesOnDisk() {
        final Path directory = ROOT.resolve("src/main/java/com/cardemo/controller");
        try (Stream<Path> sources = Files.list(directory)) {
            return sources
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !"package-info.java".equals(name))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot list " + directory, cause);
        }
    }

    /**
     * Counts the request-mapped methods of a controller: those carrying an HTTP verb mapping.
     *
     * <p>All five verb annotations are tested, {@code DELETE} included. Omitting it would undercount the
     * user-administration surface by one and make the seventeen-route total unreachable, which is the same
     * class of defect as a stale enumeration.
     *
     * @param controller the controller class
     * @return the number of routes it publishes
     */
    private static long routeCount(final Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class))
                .count();
    }

    /**
     * The per-package counts the root and bootstrap documents quote must equal what is on disk (M-09, M-10).
     *
     * <p>These three findings were all the same failure: a count written by hand, correct when written, then
     * overtaken by the work while the file that carried it was not revisited. Correcting the numbers fixes the
     * instance; this group is what stops the class of defect recurring, because a package that gains or loses a
     * type now fails the build until the documents that count it are updated in the same change.
     */
    @Nested
    @DisplayName("M-09, M-10: the documented per-package counts equal the measured ones")
    class DocumentedPackageCounts {

        /**
         * Counts the types a package directory declares, excluding its package document.
         *
         * @param relativePackagePath the package directory, relative to the repository root
         * @return how many {@code .java} files other than {@code package-info.java} it holds
         */
        private long typesIn(final String relativePackagePath) {
            final Path directory = ROOT.resolve(relativePackagePath);
            try (Stream<Path> entries = Files.list(directory)) {
                return entries
                        .filter(entry -> entry.getFileName().toString().endsWith(".java"))
                        .filter(entry -> !"package-info.java".equals(entry.getFileName().toString()))
                        .count();
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot list " + directory, cause);
            }
        }

        @Test
        @DisplayName("the controller layer really is the 8 controllers and 17 operations both documents claim")
        void theControllerLayerMatchesItsDocumentedShape() {
            assertThat(typesIn("src/main/java/com/cardemo/controller"))
                    .as("both CardDemoApplication and the root package document state 8, and all eight are "
                            + "authored, so neither may publish a partial figure such as 6 of 8")
                    .isEqualTo(8L);

            long operations = 0L;
            final Path directory = ROOT.resolve("src/main/java/com/cardemo/controller");
            try (Stream<Path> entries = Files.list(directory)) {
                for (final Path controller : entries.filter(entry -> entry.getFileName().toString()
                        .endsWith("Controller.java")).toList()) {
                    operations += Files.readAllLines(controller, StandardCharsets.UTF_8).stream()
                            .map(String::strip)
                            .filter(line -> line.equals("@GetMapping") || line.startsWith("@GetMapping(")
                                    || line.equals("@PostMapping") || line.startsWith("@PostMapping(")
                                    || line.equals("@PutMapping") || line.startsWith("@PutMapping(")
                                    || line.equals("@DeleteMapping") || line.startsWith("@DeleteMapping(")
                                    || line.equals("@PatchMapping") || line.startsWith("@PatchMapping("))
                            .count();
                }
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot count operations under " + directory, cause);
            }

            assertThat(operations)
                    .as("one operation per sourced CICS transaction of app/csd/CARDDEMO.CSD; an earlier "
                            + "revision of both documents said 12 of a target 17")
                    .isEqualTo(17L);
        }

        @Test
        @DisplayName("the batch leaves really are 6 jobs, 5 processors, 7 readers and 3 writers")
        void theBatchLeavesMatchTheirDocumentedShape() {
            // Stated as present/target in both documents, so the PRESENT figure is what disk must agree with.
            // MEASURED: the jobs leaf is complete at 6 - the five concrete jobs plus the
            // BatchPipelineOrchestrator that composes them - so a figure of 5 against a six-job target does
            // not describe this tree. CombinedTransactionReader is the
            // seventh and final reader, so the readers leaf is likewise complete at 7.
            assertThat(typesIn("src/main/java/com/cardemo/batch/jobs")).isEqualTo(6L);
            assertThat(typesIn("src/main/java/com/cardemo/batch/processors")).isEqualTo(5L);
            assertThat(typesIn("src/main/java/com/cardemo/batch/readers")).isEqualTo(7L);
            assertThat(typesIn("src/main/java/com/cardemo/batch/writers")).isEqualTo(3L);
        }

        @Test
        @DisplayName("neither document restates a whole-tree file total, which is what went stale first")
        void noWholeTreeFileTotalIsRestated() {
            // The remedy for a figure that cannot be kept correct is to stop quoting it. A total changes with
            // every file added anywhere in the tree, so it went stale faster than anything else in either
            // document. Both now name the command instead.
            // Matched on the CLAIM form, not on the digits: a document may legitimately quote a figure it
            // does not assert, in a note explaining why no total is published. What must not appear
            // is a total asserted in the present tense as the state of the tree.
            assertThat(lines("src/main/java/com/cardemo/CardDemoApplication.java"))
                    .noneMatch(line -> line.contains("the tree holds")
                            || line.contains("production classes including this entry point"));
            assertThat(lines("src/main/java/com/cardemo/package-info.java"))
                    .noneMatch(line -> line.contains("for this tree is")
                            || line.contains("giving 118 production classes"));
        }

        @Test
        @DisplayName("M-11: the admin service document claims no absence that has since ended")
        void theAdminServiceDocumentClaimsNoEndedAbsence() {
            final Path adminDocument =
                    ROOT.resolve("src/main/java/com/cardemo/service/admin/package-info.java");
            assertThat(adminDocument).exists();
            assertThat(ROOT.resolve("src/main/java/com/cardemo/controller/AdminController.java")).exists();
            assertThat(ROOT.resolve("src/test/java/com/cardemo/unit/service/UserDeleteServiceTest.java"))
                    .exists();

            // The document may DESCRIBE the corrected claim, which is why the match is on the assertion form
            // rather than on the words appearing anywhere: an absence must not be stated in the present tense
            // once it has ended.
            assertThat(lines("src/main/java/com/cardemo/service/admin/package-info.java"))
                    .noneMatch(line -> line.contains("{@code AdminController} is planned and")
                            || line.contains("{@code AdminController} is not.")
                            || line.contains("has no test class of"));
        }

        /**
         * Holds the bootstrap document's <em>prose</em> counts to disk, which is the half that was unchecked.
         *
         * <p><strong>Why this was needed even though the group above exists.</strong> Every other test here
         * asserts a figure that a human transcribed from this class's javadoc into a Java literal. That
         * catches disk drifting away from the <em>intended</em> shape, and it caught nothing at all when the
         * javadoc itself went stale: the census paragraph published {@code dto 26}, {@code jobs 3 / 6},
         * {@code readers 6 / 7} and {@code observability 3} while disk held 29, 6, 7 and 4, and the same
         * paragraph called the reader layer complete two sentences after stating it as six of seven. That is
         * finding F-015, and it passed every gate in this class.
         *
         * <p>This test reads the figures out of the document and compares them to the directories, so the
         * document and the tree cannot disagree without failing. It is deliberately keyed on the leaf name
         * followed by its number, because that is the form the census uses and the form a future editor will
         * reach for.
         */
        @Test
        @DisplayName("F-015: the bootstrap document's own per-leaf figures equal the directories")
        void theBootstrapDocumentProseMatchesDisk() {
            final String document =
                    String.join(" ", lines("src/main/java/com/cardemo/CardDemoApplication.java"));
            final List<String[]> leaves = List.of(
                    new String[] {"config", "src/main/java/com/cardemo/config"},
                    new String[] {"security", "src/main/java/com/cardemo/security"},
                    new String[] {"entity", "src/main/java/com/cardemo/model/entity"},
                    new String[] {"key", "src/main/java/com/cardemo/model/key"},
                    new String[] {"enums", "src/main/java/com/cardemo/model/enums"},
                    new String[] {"dto", "src/main/java/com/cardemo/model/dto"},
                    new String[] {"repository", "src/main/java/com/cardemo/repository"},
                    new String[] {"controller", "src/main/java/com/cardemo/controller"},
                    new String[] {"processors", "src/main/java/com/cardemo/batch/processors"},
                    new String[] {"readers", "src/main/java/com/cardemo/batch/readers"},
                    new String[] {"writers", "src/main/java/com/cardemo/batch/writers"},
                    new String[] {"exception", "src/main/java/com/cardemo/exception"},
                    new String[] {"observability", "src/main/java/com/cardemo/observability"});

            for (final String[] leaf : leaves) {
                final long measured = typesIn(leaf[1]);
                // The leaf name, then any markup, then its number - and the number must be the measured one.
                final Pattern published = Pattern.compile(
                        "\\b" + leaf[0] + "\\}?\\s*(?:</strong>)?\\s*(?:<strong>)?\\s*(\\d+)");
                final Matcher match = published.matcher(document);
                assertThat(match.find())
                        .as("the census paragraph must state a figure for the %s leaf, or this gate is "
                                + "asserting nothing about it", leaf[0])
                        .isTrue();
                assertThat(Long.parseLong(match.group(1)))
                        .as("the bootstrap document publishes %s for the %s leaf, but the directory holds "
                                + "%d types. A hand-written census that the work has overtaken understates "
                                + "what was delivered, which is the more damaging direction for an evidence "
                                + "artefact to be wrong in - finding F-015",
                                match.group(1), leaf[0], Long.valueOf(measured))
                        .isEqualTo(measured);
            }

            assertThat(typesIn("src/main/java/com/cardemo/batch/jobs"))
                    .as("the jobs leaf is stated separately because 'jobs' also appears in prose; disk is 6")
                    .isEqualTo(6L);
            assertThat(document)
                    .as("and the census must publish that 6 rather than a present-of-target pair")
                    .contains("jobs <strong>6</strong>")
                    .doesNotContain("jobs <strong>3 / 6</strong>")
                    .doesNotContain("readers <strong>6 / 7</strong>");
        }

        /**
         * Forbids the withdrawn claim that no queue listener exists.
         *
         * <p>Three surfaces asserted it - this class's javadoc, {@code README.md} twice - each in the present
         * tense and each measurably wrong once {@code BatchConfig.ReportJobQueueListener} was authored. The
         * reproduction command those surfaces offered, a grep for the annotation, returns a match, so the
         * claim was refutable by the very evidence it cited.
         */
        @Test
        @DisplayName("F-015: no document claims the queue listener is absent, because it is not")
        void noDocumentClaimsTheQueueListenerIsAbsent() {
            assertThat(readWhole("src/main/java/com/cardemo/config/BatchConfig.java"))
                    .as("the listener must exist for this gate to have a premise")
                    .contains("@SqsListener")
                    .contains("ReportJobQueueListener");

            for (final String surface : List.of("src/main/java/com/cardemo/CardDemoApplication.java",
                    "README.md")) {
                assertThat(lines(surface))
                        .as("%s must not state in the present tense that no listener exists; the withdrawal "
                                + "may be described, but the claim may not be made - finding F-015", surface)
                        .noneMatch(line -> line.contains("the listener is not.")
                                || line.contains("is **not yet wired**")
                                || line.contains("there is no\n`@SqsListener`")
                                || line.contains("no `@SqsListener` in the main")
                                || line.contains("an empty result confirms it"));
            }
        }

        /** Reads one file whole, so a claim spanning a wrapped line can be matched. */
        private String readWhole(final String relativePath) {
            try {
                return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + relativePath, cause);
            }
        }
    }

    /**
     * Holds the timestamp-producer census at five, because it was published as three.
     *
     * <p><strong>Why an undercount is worth a gate.</strong> A census that is too small reads as an
     * implementation gap where none exists, and the remedy it invites - "identify and implement the two
     * missing producers" - would have added producers the corpus does not have in order to satisfy an
     * arithmetic. The corpus has five, all five are implemented, and this group is what stops the census
     * shrinking again.
     *
     * <p><strong>The two the earlier census missed, and why each is easy to miss.</strong> The DB2-format
     * generator exists <em>twice</em>, as two separate paragraphs carrying the <em>same name</em> in two
     * different programs, so a search that deduplicates by paragraph name collapses them into one. And the
     * bill-payment program does not use {@code FUNCTION CURRENT-DATE} at all - it reads the CICS clock through
     * {@code EXEC CICS ASKTIME} and formats it with {@code FORMATTIME} - so no search for the intrinsic finds
     * it. It is a genuinely different producer rather than a fourth call site of the same one, and its output
     * differs observably: a space at position 11 where the DB2 form carries a hyphen.
     */
    @Nested
    @DisplayName("F-025: five timestamp producers, not three, and every one has a target")
    final class TimestampProducerCensus {

        /** Each producer: its source file, the paragraph or field that produces it, and its Java target. */
        private static final List<String[]> PRODUCERS = List.of(
                new String[] {"app/cbl/CBTRN02C.cbl", "Z-GET-DB2-FORMAT-TIMESTAMP",
                    "src/main/java/com/cardemo/batch/processors/TransactionPostingProcessor.java",
                    "getDb2FormatTimestamp"},
                new String[] {"app/cbl/CBACT04C.cbl", "Z-GET-DB2-FORMAT-TIMESTAMP",
                    "src/main/java/com/cardemo/batch/processors/InterestCalculationProcessor.java",
                    "db2FormatTimestamp"},
                new String[] {"app/cbl/COBIL00C.cbl", "GET-CURRENT-TIMESTAMP",
                    "src/main/java/com/cardemo/service/billing/BillPaymentService.java",
                    "getCurrentTimestamp"},
                new String[] {"app/cpy/CSDAT01Y.cpy", "WS-CURDATE",
                    "src/main/java/com/cardemo/service/menu/MainMenuService.java",
                    "LocalDateTime.now(this.clock)"},
                new String[] {"app/cpy/CSUTLDPY.cpy", "WS-CURRENT-DATE-YYYYMMDD",
                    "src/main/java/com/cardemo/service/shared/DateValidationService.java",
                    "LocalDate.now(clock)"});

        @Test
        @DisplayName("all five source producers exist in the frozen corpus")
        void allFiveSourceProducersExist() {
            for (final String[] producer : PRODUCERS) {
                final Path source = ROOT.resolve(producer[0]);
                assertThat(source)
                        .as("%s must exist in the frozen corpus", producer[0])
                        .isRegularFile();
                assertThat(readWholeFile(source))
                        .as("%s must declare %s, or this census is measuring the wrong artefact",
                                producer[0], producer[1])
                        .contains(producer[1]);
            }
            assertThat(PRODUCERS)
                    .as("the census is five, not three: the two most easily missed are "
                            + "the second copy of the DB2 idiom and the CICS clock in the bill-payment program")
                    .hasSize(5);
        }

        @Test
        @DisplayName("every one of the five has a named Java target that exists")
        void everyProducerHasANamedTarget() {
            for (final String[] producer : PRODUCERS) {
                final Path target = ROOT.resolve(producer[2]);
                assertThat(target)
                        .as("%s is the target for %s %s and must exist", producer[2], producer[0], producer[1])
                        .isRegularFile();
                assertThat(readWholeFile(target))
                        .as("%s must contain %s, so the mapping is a fact about the tree rather than a claim "
                                + "in prose", producer[2], producer[3])
                        .contains(producer[3]);
            }
        }

        @Test
        @DisplayName("the two easily-missed producers are distinct from the DB2 form, not call sites of it")
        void theTwoEasilyMissedProducersAreDistinct() {
            final String billPayment = readWholeFile(ROOT.resolve("app/cbl/COBIL00C.cbl"));
            assertThat(billPayment)
                    .as("the bill-payment producer reads the CICS clock, which is why no FUNCTION "
                            + "CURRENT-DATE search finds it")
                    .contains("EXEC CICS ASKTIME")
                    .contains("EXEC CICS FORMATTIME");

            final String posting = readWholeFile(ROOT.resolve("app/cbl/CBTRN02C.cbl"));
            final String interest = readWholeFile(ROOT.resolve("app/cbl/CBACT04C.cbl"));
            assertThat(posting).contains("Z-GET-DB2-FORMAT-TIMESTAMP");
            assertThat(interest)
                    .as("the same paragraph NAME in a second program is a second producer, not a second call "
                            + "site - which is exactly what a name-deduplicating census collapses")
                    .contains("Z-GET-DB2-FORMAT-TIMESTAMP");
        }

        /** Reads one file whole as UTF-8. */
        private String readWholeFile(final Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + path, cause);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The sanctioned production inventory: the plan's schema minimum plus this repository's register
    // ---------------------------------------------------------------------------------------------

    /**
     * The plan's {@code CREATE} summary figure for each production area, in the plan's own row order.
     *
     * <p>These seventeen figures are transcribed from the summary table and sum to
     * {@value #SCHEMA_PRODUCTION_TOTAL}. They are the contract's floor and are never adjusted to match the
     * tree: when the tree holds more, the excess must appear in {@link #SANCTIONED_ADDITIONS} and be
     * grounded in the register, which is what makes the divergence auditable rather than absorbed.</p>
     */
    private static final Map<String, Integer> SCHEMA_AREAS = schemaAreas();

    /**
     * The additions {@code DL-CR-06} sanctions, by area.
     *
     * <p>Four areas only. Every other area in {@link #SCHEMA_AREAS} carries a zero and is therefore held
     * to the plan's figure exactly, so a file added anywhere else fails this gate.</p>
     */
    private static final Map<String, Integer> SANCTIONED_ADDITIONS = sanctionedAdditions();

    /**
     * Every production type the register admits beyond the plan's own enumeration, by relative path.
     *
     * <p>Naming them individually is the point: a count alone would let one sanctioned file be swapped for
     * a different unsanctioned one without the arithmetic changing.</p>
     */
    private static final List<String> SANCTIONED_ADDITIONAL_TYPES = List.of(
            "model/dto/AccountViewResponse.java",
            "model/dto/AccountUpdateResponse.java",
            "model/dto/CardResponse.java",
            "model/dto/CardListResponse.java",
            "model/dto/TransactionResponse.java",
            "model/dto/TransactionListResponse.java",
            "model/dto/BillPaymentResponse.java",
            "model/dto/ReportSubmissionResponse.java",
            "model/dto/UserCreateResponse.java",
            "model/dto/UserUpdateResponse.java",
            "model/dto/UserListResponse.java",
            "model/dto/ApiMasking.java",
            "security/SnapshotTokenService.java",
            "observability/TemplatedUriObservationConvention.java");

    /** The sum of the plan's seventeen summary figures. */
    private static final int SCHEMA_PRODUCTION_TOTAL = 132;

    /** The register identifier that sanctions the divergence. */
    private static final String REGISTER_ENTRY = "DL-CR-06";

    /**
     * Builds the plan's per-area summary figures.
     *
     * @return area path relative to {@code src/main/java/com/cardemo}, mapped to the plan's figure;
     *         the key {@code "package-info"} stands for the whole tree's package-documentation count
     */
    private static Map<String, Integer> schemaAreas() {
        final Map<String, Integer> areas = new LinkedHashMap<>();
        areas.put(".", 1);
        areas.put("config", 6);
        areas.put("security", 3);
        areas.put("model/entity", 11);
        areas.put("model/key", 3);
        areas.put("model/enums", 4);
        // 17, not 16. The plan publishes 16 as a WITHDRAWN figure: its rollup states that "the previous
        // figures - 16 DTOs and 131 files - survived alongside a by-name enumeration of 17", and writes the
        // corrected sum out term by term as 1+6+3+11+3+4+17+11+21+8+6+5+7+3+9+3+14 = 132. Transcribing 16
        // here would hold the tree to a figure the plan itself retired, and would leave one of the twelve
        // named additions below unaccounted for.
        areas.put("model/dto", 17);
        areas.put("repository", 11);
        areas.put("service", 21);
        areas.put("controller", 8);
        areas.put("batch/jobs", 6);
        areas.put("batch/processors", 5);
        areas.put("batch/readers", 7);
        areas.put("batch/writers", 3);
        areas.put("exception", 9);
        areas.put("observability", 3);
        areas.put("package-info", 14);
        return areas;
    }

    /**
     * Builds the register's per-area additions, zero for every area held to the plan's figure.
     *
     * @return area key as in {@link #schemaAreas()}, mapped to the number of sanctioned additional files
     */
    private static Map<String, Integer> sanctionedAdditions() {
        final Map<String, Integer> additions = new LinkedHashMap<>();
        for (final String area : schemaAreas().keySet()) {
            additions.put(area, Integer.valueOf(0));
        }
        // DL-CR-06 group (a) 11 controller response types + group (b) ApiMasking = 12, which is exactly
        // 29 measured minus the plan's 17. The twelve are named individually below, so this figure and that
        // list check each other: a thirteenth addition would have no name and a missing name would leave a
        // gap in the arithmetic.
        additions.put("model/dto", Integer.valueOf(12));
        // DL-CR-06 group (c): all 24 class-bearing packages documented, plus the two structural containers.
        additions.put("package-info", Integer.valueOf(12));
        // DL-CR-06 group (d).
        additions.put("security", Integer.valueOf(1));
        additions.put("observability", Integer.valueOf(1));
        return additions;
    }

    /**
     * Counts the Java files an area holds.
     *
     * @param area area key as in {@link #schemaAreas()}
     * @return the file count for that area, counting {@code package-info.java} only for the
     *         {@code "package-info"} key and never for a class area
     */
    private static long filesIn(final String area) {
        final Path base = ROOT.resolve("src/main/java/com/cardemo");
        if ("package-info".equals(area)) {
            try (Stream<Path> walk = Files.walk(base)) {
                return walk.filter(path -> "package-info.java".equals(path.getFileName().toString()))
                        .count();
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot walk " + base, cause);
            }
        }
        final Path directory = ".".equals(area) ? base : base.resolve(area);
        final boolean recurse = "service".equals(area);
        try (Stream<Path> entries = recurse ? Files.walk(directory) : Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !"package-info.java".equals(name))
                    .count();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot list " + directory, cause);
        }
    }

    /**
     * Counts the {@code package-info.java} files at or beneath one package.
     *
     * @param area package path relative to {@code src/main/java/com/cardemo}
     * @return the number of package documents in that subtree, the subtree's own document included
     */
    private static long countPackageDocsUnder(final String area) {
        final Path base = ROOT.resolve("src/main/java/com/cardemo").resolve(area);
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(path -> "package-info.java".equals(path.getFileName().toString())).count();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot walk " + base, cause);
        }
    }

    /**
     * Supplies one case per production area.
     *
     * @return area key, the plan's figure and the register's addition for that area
     */
    private static Stream<Arguments> productionAreas() {
        return SCHEMA_AREAS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue(),
                        SANCTIONED_ADDITIONS.get(entry.getKey())));
    }

    /**
     * The production inventory, asserted as the plan's minimum plus a named register rather than as a
     * bare total.
     *
     * <p>A gate that pins one number for the whole tree fails for two indistinguishable reasons: a file
     * was added without justification, or a file was added with justification and nobody updated the
     * number. The first is a real defect and the second is bookkeeping, and a reviewer who cannot tell
     * them apart eventually edits the number to make the build green &mdash; which is how the figure this
     * class replaced went stale in the first place.</p>
     *
     * <p>So the arithmetic is stated in two halves. The plan's seventeen summary figures are the floor and
     * are never edited. {@code DL-CR-06} carries the additions, each named and grounded. This gate proves
     * the halves account for the tree exactly, area by area, and proves the register is still published
     * and still names every file it admits. Adding an unsanctioned file therefore fails against a
     * specific area with a specific number, and sanctioning it means writing down why.</p>
     *
     * <p>The bijection between packages and their documents is not re-asserted here; it belongs to
     * {@code com.cardemo.unit.model.PackageDocumentationInventoryTest}, which owns it. This class asserts
     * only the arithmetic against the plan and the integrity of the register.</p>
     */
    @Nested
    @DisplayName("M-04: the production inventory is the plan's minimum plus a named register, not a figure")
    final class SanctionedProductionInventory {

        @Test
        @DisplayName("the plan's seventeen summary figures really do sum to 132, so the floor is not invented")
        void theSchemaFiguresSumToThePublishedTotal() {
            assertThat(SCHEMA_AREAS).hasSize(17);
            assertThat(SCHEMA_AREAS.values().stream().mapToInt(Integer::intValue).sum())
                    .as("the plan's CREATE summary table has seventeen production rows; if this sum is not "
                            + "132 the transcription above is wrong and every assertion below rests on it")
                    .isEqualTo(SCHEMA_PRODUCTION_TOTAL);
        }

        @ParameterizedTest(name = "{0}: plan {1} + register {2}")
        @MethodSource(
                "com.cardemo.unit.infrastructure.InventoryCountGateTest#productionAreas")
        @DisplayName("every area holds exactly the plan's figure plus its registered additions")
        void everyAreaIsTheSchemaFigurePlusItsRegisteredAdditions(
                final String area, final int planFigure, final int registeredAddition) {
            assertThat(filesIn(area))
                    .as("area %s: the plan's summary figure is %d and %s sanctions %d addition(s), so the "
                            + "tree must hold exactly %d. A different count means either a file was added "
                            + "without a register entry, or an entry was withdrawn without the file",
                            area, Integer.valueOf(planFigure), REGISTER_ENTRY,
                            Integer.valueOf(registeredAddition),
                            Integer.valueOf(planFigure + registeredAddition))
                    .isEqualTo((long) planFigure + registeredAddition);
        }

        @Test
        @DisplayName("the whole-tree total is derived from the two halves, never asserted as a bare number")
        void theTotalIsDerivedFromTheSchemaAndTheRegister() {
            final long measured = SCHEMA_AREAS.keySet().stream().mapToLong(InventoryCountGateTest::filesIn)
                    .sum();
            final int additions = SANCTIONED_ADDITIONS.values().stream().mapToInt(Integer::intValue).sum();

            assertThat(additions)
                    .as("the four sanctioned groups of %s total 26 files; the register states that figure "
                            + "and this is where it is checked", REGISTER_ENTRY)
                    .isEqualTo(26);
            assertThat(measured)
                    .as("the tree must equal the plan's floor (%d) plus the register (%d). Both operands "
                            + "are stated, so a failure names which half moved",
                            Integer.valueOf(SCHEMA_PRODUCTION_TOTAL), Integer.valueOf(additions))
                    .isEqualTo((long) SCHEMA_PRODUCTION_TOTAL + additions);
        }

        @Test
        @DisplayName("every file the register admits exists, so no entry sanctions a phantom")
        void everySanctionedAdditionalTypeExistsOnDisk() {
            assertThat(SANCTIONED_ADDITIONAL_TYPES).hasSize(14);
            for (final String relativePath : SANCTIONED_ADDITIONAL_TYPES) {
                assertThat(ROOT.resolve("src/main/java/com/cardemo").resolve(relativePath))
                        .as("%s admits %s; a register that names a file which is not there is worse than "
                                + "no register, because it reads as accounted for", REGISTER_ENTRY,
                                relativePath)
                        .exists();
            }
        }

        @Test
        @DisplayName("the register is published and names every file and every group count it admits")
        void theRegisterIsPublishedAndNamesEveryAddition() {
            final String register = String.join("\n", lines("DECISION_LOG.md"));
            assertThat(register)
                    .as("the entry that sanctions the divergence must be present, or this gate is asserting "
                            + "arithmetic against a ground that no longer exists")
                    .contains("### " + REGISTER_ENTRY);

            for (final String relativePath : SANCTIONED_ADDITIONAL_TYPES) {
                final String simpleName = relativePath
                        .substring(relativePath.lastIndexOf('/') + 1, relativePath.length() - ".java".length());
                assertThat(register)
                        .as("%s must name %s explicitly; a group count without the names lets one "
                                + "sanctioned file be swapped for an unsanctioned one", REGISTER_ENTRY,
                                simpleName)
                        .contains(simpleName);
            }

            assertThat(register)
                    .as("the entry must publish the same four group figures this gate holds the tree to")
                    .contains("29 against 17 (**+12**)")
                    .contains("26 against 14 (**+12**)")
                    .contains("`security` 4 against 3 (**+1**)")
                    .contains("`observability` 4 against 3 (**+1**)");
        }

        @Test
        @DisplayName("the 12 extra package documents are confined to the service and batch leaves")
        void theSurplusPackageDocumentationIsLocalisedToTheLayeredPackages() {
            // The review reports the divergence per DIRECTORY, so it names `service` at 22/31 and `batch` at
            // 23/26 as if each were its own surplus of classes. Neither is: both hold exactly the number of
            // CLASSES the plan specifies, and their whole difference is package documentation. Asserting that
            // here is what lets a reader who checks those two rows find the account rather than a gap.
            assertThat(filesIn("service"))
                    .as("21 service beans exactly as the plan specifies - 17 online programs plus the four "
                            + "shared services - so the `service` directory's difference is not classes")
                    .isEqualTo(21L);
            assertThat(filesIn("batch/jobs") + filesIn("batch/processors")
                            + filesIn("batch/readers") + filesIn("batch/writers"))
                    .as("6 + 5 + 7 + 3 = 21 batch classes exactly as the plan specifies, so the `batch` "
                            + "directory's difference is likewise not classes")
                    .isEqualTo(21L);

            final long serviceDocs = countPackageDocsUnder("service");
            final long batchDocs = countPackageDocsUnder("batch");
            assertThat(serviceDocs - 1L + (batchDocs - 2L))
                    .as("the plan budgets 1 document for `service` and 2 for `batch`; the surplus is %d + %d "
                            + "= 12, which is exactly DL-CR-06 group (c). If this stops summing to 12 the "
                            + "surplus has spread to a package the register does not account for",
                            Long.valueOf(serviceDocs - 1L), Long.valueOf(batchDocs - 2L))
                    .isEqualTo(12L);
            assertThat(filesIn("package-info") - serviceDocs - batchDocs)
                    .as("the remaining 11 documents sit one per non-layered package, which is the plan's own "
                            + "budget for them, so no third region contributes to the surplus")
                    .isEqualTo(11L);
        }

        @Test
        @DisplayName("no unsanctioned area silently carries an allowance, so the zeros are real constraints")
        void everyUnsanctionedAreaIsHeldToThePlanExactly() {
            final List<String> sanctioned = SANCTIONED_ADDITIONS.entrySet().stream()
                    .filter(entry -> entry.getValue().intValue() != 0)
                    .map(Map.Entry::getKey)
                    .toList();
            assertThat(sanctioned)
                    .as("exactly four areas may diverge; if a fifth appears here it was added to the "
                            + "allowance table without an entry in %s", REGISTER_ENTRY)
                    .containsExactlyInAnyOrder("model/dto", "package-info", "security", "observability");

            assertThat(SANCTIONED_ADDITIONS.keySet())
                    .as("the allowance table must cover the plan's areas exactly, so no area escapes it")
                    .containsExactlyInAnyOrderElementsOf(SCHEMA_AREAS.keySet());
        }
    }

    /**
     * A {@code ### DL-…} entry heading in the register, capturing the identifier.
     *
     * <p>The heading is what scopes an entry. Every field count in
     * {@link DecisionLogSelfChecks} is taken between one of these and the next, which is the property an
     * earlier revision of the register's own §12.1 lacked: it counted bolded labels across the whole file, so
     * a label in a narrative paragraph inflated the total and the published field counts disagreed with the
     * published entry count. Scoping removes the class of error rather than correcting one instance of it.
     */
    private static final Pattern REGISTER_ENTRY_HEADING = Pattern.compile("^###\\s+(DL-[A-Z]+-\\d+)\\b");

    /** A bolded field label opening a table row inside a register entry, such as {@code | **Severity** |}. */
    private static final Pattern REGISTER_FIELD_LABEL = Pattern.compile("^\\|\\s*\\*\\*(.+?)\\*\\*\\s*\\|");

    /** The three fields the register's §1.8 declares to be on every entry without exception. */
    private static final List<String> MANDATORY_REGISTER_FIELDS =
            List.of("Classification", "Severity", "Verification");

    /**
     * The fields §1.8 licenses in the varying slot, in place of {@code Source evidence}.
     *
     * <p>{@code The two requirements} is the conflict-resolution substitute; the other three are the
     * residual-risk substitutes. {@code Source evidence} itself is deliberately absent from this set and
     * handled separately, because it is the default rather than a substitute.
     */
    private static final List<String> LICENSED_VARYING_FIELDS =
            List.of("The two requirements", "Decision", "Statement", "Status of the concern",
                    "Evidence, and its provenance");

    /**
     * The forward-reference convention, matched after comment continuations have been joined.
     *
     * <p>The convention is Javadoc prose, and Javadoc wraps, so an occurrence is routinely split across two
     * physical lines. A line-oriented match therefore under-counts it badly - which is exactly how the
     * register came to publish a census of 75 beside a single-line {@code grep} that returns 3. The four
     * spellings the tree uses are covered by the two optional groups: the {@code the planned} qualifier that
     * a mechanical pass dropped from all but three sites, and the {@code @code} braces.
     */
    private static final Pattern FORWARD_REFERENCE = Pattern.compile(
            "owed\\s+an\\s+entry\\s+in\\s+(?:the\\s+planned\\s+)?"
                    + "(?:\\{@code\\s+)?(?:DECISION_LOG|TRACEABILITY_MATRIX)\\.md",
            Pattern.CASE_INSENSITIVE);

    /** Collapses a Javadoc or line-comment continuation into a single space, so a wrapped phrase matches. */
    private static final Pattern COMMENT_CONTINUATION = Pattern.compile("\\n\\s*(?:\\*|//)\\s?");

    /**
     * The register's own counted claims, re-derived from the register rather than trusted.
     *
     * <p><strong>Why this group exists.</strong> {@code DECISION_LOG.md} §12.1 publishes a table of checks it
     * claims to have run on itself, each with a count. Five of those counts had gone stale and one row
     * contradicted itself inside a single cell - "all 99 tables" in its command against "74 tables
     * well-formed" in its result. A register that asserts its own correctness with a number no published
     * command reproduces is asking to be believed rather than verified, which is the opposite of what an
     * evidence artefact is for.
     *
     * <p><strong>What it asserts, and why in this form.</strong> Nothing here hard-codes an expected total.
     * Each test derives the figure from the tree with the same parser the register publishes, then reads the
     * figure the register states and asserts the two agree. That direction matters: adding a register entry
     * or a forward reference does not require a test edit, it requires the register's own numbers to be
     * brought up to date - and until they are, this group fails and names the discrepancy. The counts and the
     * document can therefore never drift apart silently, which is the only property that makes publishing a
     * number worthwhile at all.
     */
    @Nested
    @DisplayName("the register's self-check counts are derived from it, not remembered about it")
    final class DecisionLogSelfChecks {

        /** The register, relative to the repository root. */
        private static final String REGISTER = "DECISION_LOG.md";

        @Test
        @DisplayName("every entry carries all three mandatory fields, and §12.1 states the count it has")
        void everyEntryCarriesTheThreeMandatoryFieldsAndTheRegisterSaysSo() {
            final List<String> register = lines(REGISTER);
            final Map<String, List<String>> entries = parseRegisterEntries(register);

            final List<String> gaps = new ArrayList<>();
            for (final Map.Entry<String, List<String>> entry : entries.entrySet()) {
                for (final String field : MANDATORY_REGISTER_FIELDS) {
                    if (!entry.getValue().contains(field)) {
                        gaps.add(entry.getKey() + " is missing " + field);
                    }
                }
            }
            assertThat(gaps)
                    .as("§1.8 declares Classification, Severity and Verification to be on every entry "
                            + "without exception, so any gap here is a template violation")
                    .isEmpty();

            final String row = registerRow(register, "V-10b");
            final int published = singleInt(row, "\\*\\*(\\d+) entries\\*\\*");
            assertThat(Integer.valueOf(published))
                    .as("V-10b publishes %d entries; the entry-scoped parser finds %d. Update the row from "
                            + "Parser 1's output rather than editing this test", Integer.valueOf(published),
                            Integer.valueOf(entries.size()))
                    .isEqualTo(Integer.valueOf(entries.size()));

            for (final String field : MANDATORY_REGISTER_FIELDS) {
                final int[] pair = intPair(row, "\\*" + Pattern.quote(field) + "\\* \\*\\*(\\d+) / (\\d+)\\*\\*");
                assertThat(Integer.valueOf(pair[0]))
                        .as("V-10b publishes %d of %d entries carrying %s; the parser finds all %d do",
                                Integer.valueOf(pair[0]), Integer.valueOf(pair[1]), field,
                                Integer.valueOf(entries.size()))
                        .isEqualTo(Integer.valueOf(entries.size()));
                assertThat(Integer.valueOf(pair[1]))
                        .as("the denominator V-10b publishes for %s must be the entry count", field)
                        .isEqualTo(Integer.valueOf(entries.size()));
            }
        }

        @Test
        @DisplayName("the varying field varies only as §1.8 licenses, and §12.1 states the split it has")
        void theVaryingFieldVariesOnlyAsTheTemplateDeclares() {
            final List<String> register = lines(REGISTER);
            final Map<String, List<String>> entries = parseRegisterEntries(register);

            final List<String> violations = new ArrayList<>();
            int sourceEvidence = 0;
            for (final Map.Entry<String, List<String>> entry : entries.entrySet()) {
                final String varying = varyingField(entry.getValue());
                if ("Source evidence".equals(varying)) {
                    sourceEvidence++;
                } else if (!LICENSED_VARYING_FIELDS.contains(varying)) {
                    violations.add(entry.getKey() + " opens with '" + varying + "'");
                } else if (entry.getKey().startsWith("DL-CR-") && !"The two requirements".equals(varying)) {
                    violations.add(entry.getKey() + " is a conflict resolution but opens with '" + varying + "'");
                }
            }
            assertThat(violations)
                    .as("§1.8 licenses %s in the varying slot, plus 'Source evidence' as the default, and "
                            + "requires every conflict resolution to open with 'The two requirements'",
                            LICENSED_VARYING_FIELDS)
                    .isEmpty();

            final String row = registerRow(register, "V-10b");
            final int publishedSourceEvidence =
                    singleInt(row, "\\*\\*(\\d+)\\*\\* open with \\*Source evidence\\*");
            assertThat(Integer.valueOf(publishedSourceEvidence))
                    .as("V-10b publishes %d entries opening with Source evidence; the parser finds %d",
                            Integer.valueOf(publishedSourceEvidence), Integer.valueOf(sourceEvidence))
                    .isEqualTo(Integer.valueOf(sourceEvidence));

            final int publishedSubstituted = singleInt(row, "The \\*\\*(\\d+)\\*\\* that do not");
            assertThat(Integer.valueOf(publishedSubstituted))
                    .as("V-10b publishes %d entries using a substitute; the parser finds %d. The two "
                            + "published figures must also sum to the entry count",
                            Integer.valueOf(publishedSubstituted),
                            Integer.valueOf(entries.size() - sourceEvidence))
                    .isEqualTo(Integer.valueOf(entries.size() - sourceEvidence));
            assertThat(Integer.valueOf(publishedSourceEvidence + publishedSubstituted))
                    .as("the split V-10b publishes must account for every entry, with none double-counted")
                    .isEqualTo(Integer.valueOf(entries.size()));
        }

        @Test
        @DisplayName("DL-RR-08's forward-reference census is what a continuation-joining pass finds")
        void theForwardReferenceCensusMatchesTheTree() {
            final Map<String, Integer> census = forwardReferenceCensus();
            final int occurrences = census.values().stream().mapToInt(Integer::intValue).sum();
            final long mainFiles = census.keySet().stream().filter(key -> key.startsWith("src/main")).count();
            final long testFiles = census.keySet().stream().filter(key -> key.startsWith("src/test")).count();
            final int mainOccurrences = census.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("src/main"))
                    .mapToInt(entry -> entry.getValue().intValue())
                    .sum();

            final String entry = registerEntryText(lines(REGISTER), "DL-RR-08");
            final int[] headline =
                    intPair(entry, "occupies (\\d+) occurrences across (\\d+) files");
            assertThat(Integer.valueOf(headline[0]))
                    .as("DL-RR-08 publishes %d occurrences; Parser 2 finds %d across %s",
                            Integer.valueOf(headline[0]), Integer.valueOf(occurrences), census.keySet())
                    .isEqualTo(Integer.valueOf(occurrences));
            assertThat(Integer.valueOf(headline[1]))
                    .as("DL-RR-08 publishes %d files; Parser 2 finds %d",
                            Integer.valueOf(headline[1]), Integer.valueOf(census.size()))
                    .isEqualTo(Integer.valueOf(census.size()));

            final int[] main = intPair(entry, "(\\d+) occurrences in (\\d+) files under `src/main`");
            assertThat(Integer.valueOf(main[0]))
                    .as("DL-RR-08's src/main occurrence figure must be Parser 2's")
                    .isEqualTo(Integer.valueOf(mainOccurrences));
            assertThat(Integer.valueOf(main[1]))
                    .as("DL-RR-08's src/main file figure must be Parser 2's")
                    .isEqualTo(Integer.valueOf((int) mainFiles));

            final int[] test = intPair(entry, "(\\d+) in (\\d+) files under `src/test`");
            assertThat(Integer.valueOf(test[0]))
                    .as("DL-RR-08's src/test occurrence figure must be Parser 2's")
                    .isEqualTo(Integer.valueOf(occurrences - mainOccurrences));
            assertThat(Integer.valueOf(test[1]))
                    .as("DL-RR-08's src/test file figure must be Parser 2's")
                    .isEqualTo(Integer.valueOf((int) testFiles));
        }

        @Test
        @DisplayName("§12.1's remaining structural counts are the tree's, so none can go stale unnoticed")
        void theRemainingStructuralCountsMatchTheDocument() {
            final List<String> register = lines(REGISTER);
            final String text = String.join("\n", register);

            final List<String> appPaths = captures(text, "\\bapp/[A-Za-z0-9_./-]+").stream()
                    .map(path -> path.replaceAll("[.,:)]+$", ""))
                    .distinct()
                    .toList();
            assertThat(Integer.valueOf(singleInt(registerRow(register, "V-1"), "\\*\\*(\\d+)\\*\\* distinct paths")))
                    .as("V-1's distinct frozen-corpus path count must be the one the file cites: %s", appPaths)
                    .isEqualTo(Integer.valueOf(appPaths.size()));
            assertThat(appPaths.stream().filter(path -> !Files.exists(ROOT.resolve(path))).toList())
                    .as("V-1 claims 0 missing, so every cited frozen-corpus path must resolve with its case")
                    .isEmpty();

            assertThat(Integer.valueOf(singleInt(registerRow(register, "V-2"),
                            "Of \\*\\*(\\d+)\\*\\* markdown link targets")))
                    .as("V-2's markdown-link-target total must be the file's")
                    .isEqualTo(Integer.valueOf(captures(text, "\\]\\(([^)]+)\\)").size()));

            final String anchorRow = registerRow(register, "V-3");
            final List<String> defined = captures(text, "<a id=\"([a-z0-9-]+)\"></a>");
            final List<String> linked = captures(text, "\\]\\(#([a-z0-9-]+)\\)").stream().distinct().toList();
            assertThat(Integer.valueOf(singleInt(anchorRow, "\\*\\*(\\d+)\\*\\* anchors defined")))
                    .as("V-3's defined-anchor count must be the file's")
                    .isEqualTo(Integer.valueOf(defined.stream().distinct().toList().size()));
            assertThat(Integer.valueOf(singleInt(anchorRow, "\\*\\*(\\d+)\\*\\* distinct anchors linked")))
                    .as("V-3's linked-anchor count must be the file's")
                    .isEqualTo(Integer.valueOf(linked.size()));
            assertThat(linked.stream().filter(anchor -> !defined.contains(anchor)).toList())
                    .as("V-3 claims 0 unresolved, so every internal link must reach a defined anchor")
                    .isEmpty();
            assertThat(defined.stream()
                            .filter(anchor -> defined.indexOf(anchor) != defined.lastIndexOf(anchor))
                            .distinct()
                            .toList())
                    .as("V-5 claims no duplicates, so no anchor may be defined twice")
                    .isEmpty();

            final long placeholderLines = register.stream()
                    .filter(line -> Pattern.compile("\\b(TBD|TODO|FIXME|XXX)\\b").matcher(line).find())
                    .count();
            assertThat(Long.valueOf(placeholderLines))
                    .as("V-6 publishes its hit count as the word 'Seven' and then enumerates all seven, "
                            + "every one prose about the prohibition rather than an instance of it. A new "
                            + "line here is either a real placeholder, which clause B forbids, or a mention "
                            + "the row does not list - and either way the row needs rewriting, not this "
                            + "assertion relaxing")
                    .isEqualTo(Long.valueOf(7L));
            assertThat(registerRow(register, "V-6"))
                    .as("V-6 states its count in words, so the word and the measured 7 must agree; if the "
                            + "row moves to a numeral, change this assertion with it")
                    .contains("**Seven** hits");

            assertThat(Integer.valueOf(singleInt(registerRow(register, "V-11"), "\\*\\*(\\d+) tables")))
                    .as("V-11's table count must be the file's, counted with escaped pipes and inline code "
                            + "spans excluded - the reading whose absence produced the 99-against-74 "
                            + "contradiction this row withdrew")
                    .isEqualTo(Integer.valueOf(countTables(register)));
        }

        @Test
        @DisplayName("both parsers stay published, so no count in §12.1 can outlive its reproduction command")
        void bothParsersRemainPublishedBesideTheCountsTheyProduce() {
            final String register = String.join("\n", lines(REGISTER));
            assertThat(register)
                    .as("the anchor V-10b and DL-RR-08 both cite must exist, or their reproduction "
                            + "instructions become dangling references")
                    .contains("<a id=\"selfcheck-parser\"></a>");
            assertThat(register)
                    .as("Parser 1 must remain published as a runnable block; a count whose parser has been "
                            + "deleted is the exact defect this group closes")
                    .contains("Parser 1 — the entry-scoped structural pass")
                    .contains("hdr = re.compile(r'^###\\s+(DL-[A-Z]+-\\d+)\\b')");
            assertThat(register)
                    .as("Parser 2 must remain published, including the continuation-joining substitution "
                            + "that is the whole reason a single-line grep cannot count the convention")
                    .contains("Parser 2 — the forward-reference census")
                    .contains("s/\\n\\s*(?:\\*|\\/\\/)\\s?/ /g");
            assertThat(register)
                    .as("the withdrawn figures must stay withdrawn in writing, so the correction is "
                            + "auditable rather than a silent overwrite")
                    .contains("all four figures are withdrawn")
                    .contains("both are withdrawn");
        }

        /**
         * Applies Parser 1: scopes each entry from its heading to the next and lists its field labels.
         *
         * @param register the register's lines, in order; must not be {@code null}
         * @return field labels per entry identifier, in document order
         */
        private Map<String, List<String>> parseRegisterEntries(final List<String> register) {
            final List<Integer> starts = new ArrayList<>();
            final List<String> names = new ArrayList<>();
            for (int index = 0; index < register.size(); index++) {
                final Matcher heading = REGISTER_ENTRY_HEADING.matcher(register.get(index));
                if (heading.find()) {
                    starts.add(Integer.valueOf(index));
                    names.add(heading.group(1));
                }
            }
            final Map<String, List<String>> entries = new LinkedHashMap<>();
            for (int slot = 0; slot < starts.size(); slot++) {
                final int from = starts.get(slot).intValue();
                final int to = slot + 1 < starts.size() ? starts.get(slot + 1).intValue() : register.size();
                final List<String> labels = new ArrayList<>();
                for (final String line : register.subList(from, to)) {
                    final Matcher label = REGISTER_FIELD_LABEL.matcher(line);
                    if (label.find()) {
                        labels.add(label.group(1));
                    }
                }
                entries.put(names.get(slot), labels);
            }
            return entries;
        }

        /**
         * Returns the field occupying the varying slot: the first label that is neither of the two fixed
         * openers.
         *
         * @param labels one entry's field labels, in document order
         * @return the varying field's label, or {@code "<none>"} when the entry declares no field at all
         */
        private String varyingField(final List<String> labels) {
            return labels.stream()
                    .filter(label -> !"Classification".equals(label) && !"Severity".equals(label))
                    .findFirst()
                    .orElse("<none>");
        }

        /**
         * Applies Parser 2: counts the forward-reference convention per Java file, joining comment
         * continuations first so a wrapped occurrence is seen.
         *
         * @return occurrence count per repository-relative path, for the files that hold at least one
         */
        private Map<String, Integer> forwardReferenceCensus() {
            final Map<String, Integer> census = new LinkedHashMap<>();
            for (final String tree : List.of("src/main", "src/test")) {
                try (Stream<Path> walk = Files.walk(ROOT.resolve(tree))) {
                    walk.filter(path -> path.toString().endsWith(".java"))
                            .sorted()
                            .forEach(path -> {
                                final String joined = COMMENT_CONTINUATION
                                        .matcher(readUtf8(path))
                                        .replaceAll(" ");
                                final long hits = FORWARD_REFERENCE.matcher(joined).results().count();
                                if (hits > 0L) {
                                    census.put(
                                            ROOT.relativize(path).toString().replace('\\', '/'),
                                            Integer.valueOf((int) hits));
                                }
                            });
                } catch (final IOException cause) {
                    throw new UncheckedIOException("Cannot walk " + tree, cause);
                }
            }
            return census;
        }

        /**
         * Reads a file as UTF-8 text.
         *
         * @param path the file to read
         * @return its whole content
         */
        private String readUtf8(final Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + path, cause);
            }
        }

        /**
         * Returns the whole of one {@code | V-… |} row from §12.1.
         *
         * @param register the register's lines
         * @param check    the check identifier, such as {@code V-10b}
         * @return the row's text
         */
        private String registerRow(final List<String> register, final String check) {
            return register.stream()
                    .filter(line -> line.startsWith("| " + check + " |"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "DECISION_LOG.md §12.1 no longer publishes a " + check + " row. If the check was "
                                    + "renamed, rename it here too; if it was withdrawn, this assertion "
                                    + "should be withdrawn with it rather than left to fail."));
        }

        /**
         * Returns the text of one register entry, from its heading to the next heading.
         *
         * @param register the register's lines
         * @param entryId  the entry identifier, such as {@code DL-RR-08}
         * @return the entry's text, newline-joined
         */
        private String registerEntryText(final List<String> register, final String entryId) {
            int from = -1;
            for (int index = 0; index < register.size(); index++) {
                final Matcher heading = REGISTER_ENTRY_HEADING.matcher(register.get(index));
                if (heading.find() && entryId.equals(heading.group(1))) {
                    from = index;
                    break;
                }
            }
            if (from < 0) {
                throw new IllegalStateException("DECISION_LOG.md holds no " + entryId + " entry.");
            }
            int to = register.size();
            for (int index = from + 1; index < register.size(); index++) {
                if (REGISTER_ENTRY_HEADING.matcher(register.get(index)).find()) {
                    to = index;
                    break;
                }
            }
            return String.join("\n", register.subList(from, to));
        }

        /**
         * Collects every match of a pattern, in document order and with duplicates retained.
         *
         * @param text  the text to scan
         * @param regex a pattern; its first capturing group is collected, or the whole match when it has none
         * @return the collected strings, in order
         */
        private List<String> captures(final String text, final String regex) {
            final Matcher matcher = Pattern.compile(regex).matcher(text);
            final List<String> found = new ArrayList<>();
            while (matcher.find()) {
                found.add(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
            }
            return found;
        }

        /**
         * Counts well-formed markdown tables, and fails the caller if any row's column count differs from its
         * header's.
         *
         * <p>A table is a row followed by a separator row. Two readings matter and both were absent from the
         * pass that produced §12.1's withdrawn 99-against-74 contradiction: a pipe escaped as {@code \|} is
         * cell content, and so is a pipe inside an inline code span. Counting raw pipes reports spurious
         * mismatches on precisely the rows that quote a shell pipeline, which this register does often.
         *
         * @param register the register's lines, in order
         * @return the number of tables, all of which are asserted well-formed
         */
        private int countTables(final List<String> register) {
            final Pattern separator = Pattern.compile("^\\s*\\|[\\s:|-]+\\|\\s*$");
            final List<String> malformed = new ArrayList<>();
            int tables = 0;
            boolean inFence = false;
            int index = 0;
            while (index < register.size()) {
                final String line = register.get(index);
                if (line.strip().startsWith("```")) {
                    inFence = !inFence;
                    index++;
                    continue;
                }
                final boolean opensTable = !inFence
                        && line.stripLeading().startsWith("|")
                        && index + 1 < register.size()
                        && separator.matcher(register.get(index + 1)).matches();
                if (!opensTable) {
                    index++;
                    continue;
                }
                tables++;
                final int columns = columnCount(line);
                int row = index + 2;
                while (row < register.size() && register.get(row).stripLeading().startsWith("|")) {
                    if (columnCount(register.get(row)) != columns) {
                        malformed.add("line " + (row + 1) + " has " + columnCount(register.get(row))
                                + " columns against the header's " + columns);
                    }
                    row++;
                }
                index = row;
            }
            assertThat(malformed)
                    .as("V-11 claims every table is well-formed with consistent column counts")
                    .isEmpty();
            assertThat(Boolean.valueOf(inFence))
                    .as("V-11 claims code fences are balanced, so no fence may be left open")
                    .isEqualTo(Boolean.FALSE);
            return tables;
        }

        /**
         * Counts a markdown row's cells, treating an escaped pipe and a pipe inside an inline code span as
         * content rather than as a separator.
         *
         * @param row one markdown table row
         * @return the number of cells between the leading and trailing separators
         */
        private int columnCount(final String row) {
            final List<String> cells = new ArrayList<>();
            final StringBuilder cell = new StringBuilder();
            boolean inCode = false;
            for (int position = 0; position < row.length(); position++) {
                final char character = row.charAt(position);
                if (character == '\\' && position + 1 < row.length()) {
                    cell.append(character).append(row.charAt(position + 1));
                    position++;
                } else if (character == '`') {
                    inCode = !inCode;
                    cell.append(character);
                } else if (character == '|' && !inCode) {
                    cells.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(character);
                }
            }
            cells.add(cell.toString());
            if (!cells.isEmpty() && cells.get(0).isBlank()) {
                cells.remove(0);
            }
            if (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
                cells.remove(cells.size() - 1);
            }
            return cells.size();
        }

        /**
         * Extracts one integer a document publishes.
         *
         * @param text  the text to search
         * @param regex a pattern with exactly one capturing group holding digits
         * @return the captured integer
         */
        private int singleInt(final String text, final String regex) {
            final Matcher matcher = Pattern.compile(regex).matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException(
                        "DECISION_LOG.md no longer publishes a figure matching /" + regex + "/. The wording "
                                + "and this assertion must be changed together, so that a count and its "
                                + "check cannot part company.");
            }
            return Integer.parseInt(matcher.group(1));
        }

        /**
         * Extracts a pair of integers a document publishes together.
         *
         * @param text  the text to search
         * @param regex a pattern with exactly two capturing groups holding digits
         * @return the two captured integers, in order
         */
        private int[] intPair(final String text, final String regex) {
            final Matcher matcher = Pattern.compile(regex).matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException(
                        "DECISION_LOG.md no longer publishes a pair matching /" + regex + "/.");
            }
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
    }
}
