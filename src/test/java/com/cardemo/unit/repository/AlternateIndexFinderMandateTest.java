/*
 * ******************************************************************
 * Program     : AlternateIndexFinderMandateTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Pins Transformation Rule 5: the three VSAM alternate indexes
 *               each keep a derived finder and a non-unique B-tree index, so
 *               that the one finder with no production caller cannot be
 *               deleted as dead code when the corpus is what leaves it unused.
 * Source      : app/catlg/LISTCAT.txt        (3 AIX + 3 PATH)
 *               app/cbl/COCRDSLC.cbl:L784    (only CARDAIX read, unreachable)
 *               app/cbl/COCRDLIC.cbl:L1386   (base-cluster filter instead)
 *               @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.TransactionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Transformation Rule 5, made executable: three alternate indexes, three derived finders, three B-tree
 * indexes.
 *
 * <p>The frozen catalogue records three alternate indexes over the CardDemo clusters, each with a path -
 * {@code CARDDATA.VSAM.AIX}, {@code CARDXREF.VSAM.AIX} and {@code TRANSACT.VSAM.AIX}. The migration plan maps
 * each onto a derived finder plus a matching non-unique B-tree index, and records a <em>missing</em> derived
 * finder as a defect in its own right - the earlier count of two rather than three is called out as an error
 * precisely because it would have left one finder and one index unwritten.
 *
 * <p>Two of the three finders have production callers. The third,
 * {@link CardRepository#findByAccountIdOrderByCardNumberAsc(Long, org.springframework.data.domain.Pageable)},
 * has none - because {@code CARDAIX} is read by exactly one {@code PROCEDURE DIVISION} statement in the whole
 * corpus, {@code app/cbl/COCRDSLC.cbl:L784}, inside a paragraph that is never performed and whose
 * record-identification field is never populated. That asymmetry is a property of the source, and it makes the
 * unused finder look deletable when it is not.
 *
 * <p>This class exists so that it cannot be deleted quietly. Removing the finder, renaming it, or dropping the
 * index that backs it fails a test here rather than passing review as tidying. The index assertions match on
 * table and column rather than on index name, so a rename of the {@code idx_} prefix cannot
 * break them - the mandate is about the index existing on the alternate key, not about what it is called.
 */
@DisplayName("Transformation Rule 5: three alternate indexes, three finders, three B-tree indexes")
class AlternateIndexFinderMandateTest {

    /** The migration that creates the alternate-key indexes, read from the classpath. */
    private static final String MIGRATION = "db/migration/V2__create_indexes.sql";

    /** Matches a {@code CREATE INDEX} statement, capturing its table and its column list. */
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "CREATE\\s+INDEX\\s+\\S+\\s+ON\\s+\"?(\\w+)\"?\\s+USING\\s+btree\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    /** The migration text, loaded once. */
    private static String migration;

    @BeforeAll
    static void loadMigration() throws IOException {
        try (InputStream source = AlternateIndexFinderMandateTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            migration = new String(Objects.requireNonNull(source, MIGRATION + " must be on the classpath")
                    .readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("1. Each alternate index has its derived finder")
    class EachIndexHasItsFinder {

        @Test
        @DisplayName("CARDDATA.VSAM.AIX -> CardRepository.findByAccountIdOrderByCardNumberAsc")
        void theCardAlternateIndexHasItsFinder() {
            // The one finder with no production caller. app/cbl/COCRDSLC.cbl:L784 is the corpus's only
            // procedure-division read of CARDAIX and it sits in an unperformed paragraph, so no faithful
            // call site exists - which is exactly why this assertion is here.
            final Method finder = declaredFinder(CardRepository.class,
                    "findByAccountIdOrderByCardNumberAsc");
            assertThat(finder.getReturnType().getName())
                    .isEqualTo("org.springframework.data.domain.Page");
            assertThat(finder.getParameterTypes()[0]).isEqualTo(Long.class);
        }

        @Test
        @DisplayName("CARDXREF.VSAM.AIX -> CardCrossReferenceRepository"
                + ".findFirstByAccountIdOrderByCardNumberAsc")
        void theCrossReferenceAlternateIndexHasItsFinder() {
            // The finder returns Optional, not List, and the mandate is unaffected by that: what this
            // asserts is that the alternate index has a derived consumer, and First...OrderByCardNumberAsc
            // is derived from the same accountId predicate over the same ordering, with LIMIT 1 added. The
            // read it models is EXEC CICS READ against CXACAIX - the .AIX.PATH - and a keyed read through a
            // VSAM path yields exactly one record. The alternate key remains non-unique, which is why the
            // ordering clause is part of the name: it is what makes "the first row" deterministic.
            final Method finder = declaredFinder(CardCrossReferenceRepository.class,
                    "findFirstByAccountIdOrderByCardNumberAsc");
            assertThat(finder.getReturnType()).isEqualTo(Optional.class);
            assertThat(finder.getParameterTypes()[0]).isEqualTo(Long.class);
        }

        @Test
        @DisplayName("TRANSACT.VSAM.AIX -> TransactionRepository processing-timestamp finder")
        void theTransactionAlternateIndexHasItsFinder() {
            assertThat(declaredFinder(TransactionRepository.class,
                    "findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc")).isNotNull();
        }

        @Test
        @DisplayName("The three finders key on the three alternate keys and nothing else")
        void thereAreExactlyThree() {
            // A fourth alternate-key finder would mean either an index the catalogue does not record or a
            // speculative query; both are forbidden by the same rule that mandates these three.
            assertThat(alternateKeyFinderCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("2. Each derived finder has its B-tree index")
    class EachFinderHasItsIndex {

        @Test
        @DisplayName("card(card_acct_id) is indexed, at the alternate key AXRKP 16 of the base record")
        void theCardAlternateKeyIsIndexed() {
            assertThat(indexedColumnsOn("card")).contains("card_acct_id");
        }

        @Test
        @DisplayName("card_cross_reference(xref_acct_id) is indexed")
        void theCrossReferenceAlternateKeyIsIndexed() {
            assertThat(indexedColumnsOn("card_cross_reference")).contains("xref_acct_id");
        }

        @Test
        @DisplayName("transaction(tran_proc_ts) is indexed, at the alternate key AXRKP 304")
        void theTransactionAlternateKeyIsIndexed() {
            assertThat(indexedColumnsOn("transaction")).contains("tran_proc_ts");
        }

        @Test
        @DisplayName("Every index the migration creates is non-unique, as every alternate key is")
        void everyIndexIsNonUnique() {
            // app/catlg/LISTCAT.txt records all three alternate indexes as non-unique, so a UNIQUE index
            // here would reject data the legacy clusters accepted.
            assertThat(migration.toUpperCase(Locale.ROOT)).doesNotContain("CREATE UNIQUE INDEX");
        }

        @Test
        @DisplayName("The migration creates exactly three indexes: one per alternate index, no more")
        void thereAreExactlyThreeIndexes() {
            assertThat(CREATE_INDEX.matcher(migration).results().count()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("3. The entities expose the alternate keys the finders derive from")
    class TheEntitiesExposeTheKeys {

        @Test
        @DisplayName("Each entity declares the property its finder keys on")
        void eachEntityDeclaresItsAlternateKeyProperty() {
            // The two account finders are derived from the property name, so Card.accountId and
            // CardCrossReference.accountId must be spelled exactly that way or Spring Data cannot derive
            // them. The transaction finder is an explicit @Query keyed on Transaction.procTs, which is why
            // the property name there is the COBOL field name rather than the finder's own wording.
            assertThat(hasProperty(Card.class, "accountId"))
                    .as("CARD-ACCT-ID, the alternate key of CARDDATA.VSAM.AIX")
                    .isTrue();
            assertThat(hasProperty(CardCrossReference.class, "accountId"))
                    .as("XREF-ACCT-ID, the alternate key of CARDXREF.VSAM.AIX")
                    .isTrue();
            assertThat(hasProperty(Transaction.class, "procTs"))
                    .as("TRAN-PROC-TS, the alternate key of TRANSACT.VSAM.AIX at AXRKP 304")
                    .isTrue();
        }
    }

    /**
     * Resolves a declared method by name, failing the test when it is absent.
     *
     * @param repository the repository interface to inspect, never {@code null}
     * @param name the method name the mandate requires, never {@code null}
     * @return the method, never {@code null}
     */
    private static Method declaredFinder(final Class<?> repository, final String name) {
        final List<Method> matches = Arrays.stream(repository.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
        assertThat(matches)
                .as("%s must declare %s; Transformation Rule 5 maps one derived finder onto each of the "
                        + "three alternate indexes", repository.getSimpleName(), name)
                .hasSize(1);
        return matches.get(0);
    }

    /**
     * Counts the finders across the three repositories that key on an alternate key.
     *
     * @return the count
     */
    private static long alternateKeyFinderCount() {
        // Matched on the PREFIX, deliberately, and on both spellings of it. The cross-reference finder is
        // findFirstByAccountIdOrderByCardNumberAsc: a keyed read through the .AIX.PATH yields exactly one
        // record, so the derived name carries First and returns Optional, and it is still a finder whose
        // predicate is the accountId alternate key. Matching a bare "contains AccountId" instead would count
        // findByCardNumberAndAccountId and findByIdAndAccountIdForUpdate on CardRepository, which key on the
        // PRIMARY key with the account as a scoping guard and are not alternate-index consumers at all.
        return Arrays.stream(CardRepository.class.getDeclaredMethods())
                .filter(method -> isAccountAlternateKeyFinder(method.getName()))
                .count()
                + Arrays.stream(CardCrossReferenceRepository.class.getDeclaredMethods())
                        .filter(method -> isAccountAlternateKeyFinder(method.getName()))
                        .count()
                + Arrays.stream(TransactionRepository.class.getDeclaredMethods())
                        .filter(method -> method.getName().startsWith("findByProcessingTimestamp"))
                        .count();
    }

    /**
     * Reports whether a derived finder name keys on the {@code accountId} alternate key.
     *
     * @param name the declared method name, never {@code null}
     * @return {@code true} when the name derives its predicate from {@code accountId} alone
     */
    private static boolean isAccountAlternateKeyFinder(final String name) {
        return name.startsWith("findByAccountId") || name.startsWith("findFirstByAccountId");
    }

    /**
     * Reads the indexed column lists the migration declares for one table.
     *
     * @param table the unquoted table name, never {@code null}
     * @return the column names indexed on that table, never {@code null}
     */
    private static List<String> indexedColumnsOn(final String table) {
        final Matcher matcher = CREATE_INDEX.matcher(migration);
        final List<String> columns = new java.util.ArrayList<>();
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(table)) {
                for (String column : matcher.group(2).split(",")) {
                    columns.add(column.trim().replace("\"", ""));
                }
            }
        }
        return columns;
    }

    /**
     * Answers whether an entity declares a field of the given name.
     *
     * @param entity the entity class, never {@code null}
     * @param property the field name, never {@code null}
     * @return {@code true} when the field is declared
     */
    private static boolean hasProperty(final Class<?> entity, final String property) {
        return Arrays.stream(entity.getDeclaredFields())
                .anyMatch(field -> field.getName().equals(property));
    }
}
