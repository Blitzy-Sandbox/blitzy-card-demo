/*
 * ****************************************************************************
 * Program     : InventoryCountGateTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Turns three counted claims into executable gates, so none can
 *               drift back into prose: the 636 seed rows the ASCII fixtures and
 *               the inline DUSRSECJ users contain, the 17 data transfer objects
 *               the BMS symbolic maps require, and the 37 executable
 *               @ExceptionHandler methods the six controllers declare.
 * Source      : app/data/ASCII/** (the nine fixtures, 626 rows)
 *               + app/jcl/DUSRSECJ.jcl (the ten inline SYSUT1 users)
 *               + app/cpy-bms/** (the seventeen symbolic maps)
 *               + app/csd/CARDDEMO.CSD (the seventeen sourced transactions the
 *                 six controllers expose)
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
import org.springframework.web.bind.annotation.ExceptionHandler;

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
 *   <li><strong>37 executable {@code @ExceptionHandler} methods.</strong> Neither 36 nor the raw-token 40 a
 *       naive grep reports - the token also appears in Javadoc prose, which is why this counts ANNOTATED
 *       METHODS through reflection instead of matching text.
 *   </ul>
 */
@DisplayName("Counted claims, held as gates: 636 seed rows, 17 DTOs, 37 exception handlers")
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
    private static final int EXPECTED_DTO_COUNT = 26;

    /** The corrected executable exception-handler count. */
    private static final int EXPECTED_HANDLER_COUNT = 37;

    /** Per-table seed row counts, in the order the migration inserts them. */
    private static final Map<String, Integer> EXPECTED_ROWS_PER_TABLE = expectedRowsPerTable();

    /** The repository root, located once and reused. */
    private static final Path ROOT = repositoryRoot();

    /**
     * The six controllers, each paired with the number of handlers it declares.
     *
     * @return one entry per controller
     */
    private static Map<Class<?>, Integer> expectedHandlersPerController() {
        final Map<Class<?>, Integer> expected = new LinkedHashMap<>();
        expected.put(AccountController.class, 8);
        expected.put(BillingController.class, 7);
        expected.put(CardController.class, 7);
        expected.put(MenuController.class, 3);
        expected.put(ReportController.class, 5);
        expected.put(TransactionController.class, 7);
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
    @DisplayName("26 data transfer objects, not the stale 16 or 17")
    final class DataTransferObjectCount {

        @Test
        @DisplayName("the dto package holds exactly 26 types, excluding its package documentation")
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
                            "17 request-and-projection types plus 9 added when the REST surface stopped "
                                    + "returning entities: 8 response envelopes - account update, account "
                                    + "view, bill payment, card, card list, report submission, transaction "
                                    + "and transaction list - and the ApiMasking helper they share. "
                                    + "SignOnResponse remains the one with no BMS symbolic map, because CICS "
                                    + "returned identity in the COMMAREA rather than on a screen, which is "
                                    + "exactly why a map-driven count missed it")
                    .hasSize(EXPECTED_DTO_COUNT)
                    .contains("SignOnResponse.java", "AccountViewResponse.java", "ApiMasking.java");
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
    @DisplayName("37 executable @ExceptionHandler methods, neither 36 nor the raw-token 40")
    final class ExceptionHandlerCount {

        @Test
        @DisplayName("the six controllers declare exactly 37 annotated methods in total")
        void theSixControllersDeclareThirtySevenHandlers() {
            final long total = expectedHandlersPerController().keySet().stream()
                    .mapToLong(InventoryCountGateTest::handlerCount)
                    .sum();
            assertThat(total)
                    .as(
                            "counted as ANNOTATED METHODS, not as occurrences of the token: the token also "
                                    + "appears in Javadoc prose on these classes, which is how a text scan "
                                    + "reaches 40")
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
}
