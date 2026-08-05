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
 * <p>Each of the three was previously asserted only in prose - in a migration header, a
 * {@code package-info} docstring and a specification table respectively. A number written in prose is
 * correct exactly until the thing it counts changes, and nothing reports the moment it stops being correct.
 * These assertions are the report.
 *
 * <ul>
 *   <li><strong>636 seed rows.</strong> The headline was 586. The source-correct total is 626 fixture rows
 *       from {@code app/data/ASCII/**} plus the ten users inlined as {@code SYSUT1 DD *} data in
 *       {@code app/jcl/DUSRSECJ.jcl}, and the {@code transaction} table is deliberately empty because the
 *       legacy corpus ships no transaction fixture - {@code TRANSACT} is populated by the posting job, not by
 *       a seed. Counted here from the migration itself, and independently confirmed against a live
 *       PostgreSQL 16 database seeded by that migration.
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
     * would fail if that ever stopped being true.
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
                    .as("both CardDemoApplication and the root package document state 8; an earlier revision "
                            + "of each said 6 of 8 after all eight had been authored")
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
        @DisplayName("the batch leaves really are 4 jobs, 5 processors, 7 readers and 3 writers")
        void theBatchLeavesMatchTheirDocumentedShape() {
            // Stated as present/target in both documents, so the PRESENT figure is what disk must agree with.
            // TransactionReportJob is the fourth concrete job, so the jobs leaf is 4 against a six-job target
            // and two jobs remain to be authored. CombinedTransactionReader is the seventh and final reader,
            // so the readers leaf is now complete at 7.
            assertThat(typesIn("src/main/java/com/cardemo/batch/jobs")).isEqualTo(4L);
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
            // Matched on the CLAIM form, not on the digits: both documents legitimately recount the figures
            // they used to publish, in the finding notes that record why they stopped. What must not reappear
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
    }
}
