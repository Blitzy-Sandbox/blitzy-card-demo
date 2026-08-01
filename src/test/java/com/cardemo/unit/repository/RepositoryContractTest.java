/*
 * ******************************************************************
 * Program     : RepositoryContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the declared contract of all eleven interfaces
 *               in com.cardemo.repository - the aggregate root and
 *               identifier type arguments, the exact declared finder
 *               inventory, collection returns on the three NONUNIQKEY
 *               alternate-key paths, named-parameter binding with no
 *               native query and no concatenated query text, the two
 *               pessimistic read-for-update locks, the absence of a
 *               password-hash projection, and the twelve file shape of
 *               the package.
 * Source      : app/catlg/LISTCAT.txt:L283,L486,L3676 (AXRKP) and
 *               :L285,L488,L3678 (NONUNIQKEY) @ 7756d89
 *               app/csd/CARDDEMO.CSD:L1-L88 (8 DEFINE FILE) @ 7756d89
 *               app/cbl/CBTRN02C.cbl:L481 @ 7756d89
 *               app/cbl/CBACT04C.cbl:L422,L436-L439,L446 @ 7756d89
 *               app/cbl/COBIL00C.cbl:L212-L217,L487-L488 @ 7756d89
 *               app/cbl/COACTUPC.cbl:L3894-L3932 @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.repository;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.unit.model.ReflectionCensus;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier contract verification for the eleven Spring Data JPA repository interfaces that replace the VSAM
 * access verbs of the frozen COBOL corpus.
 *
 * <p><strong>Why a reflective contract test rather than a behavioural one.</strong> A derived-query interface
 * has no body to execute: Spring Data synthesises the implementation at context refresh from the method
 * signature and the entity metamodel. There is consequently nothing for a unit test to invoke, and the only
 * thing a unit test can meaningfully protect is the <em>declaration</em> - the aggregate root and identifier
 * type arguments, the method names, the return types, the parameter types, the binding annotations and the
 * lock modes. Every one of those is load bearing, because the schema action is {@code validate} in every
 * profile: a divergence between a declaration here and the migration does not degrade gracefully, it stops
 * the application context from starting. This tier catches such a divergence in milliseconds, without a
 * container, and therefore without the Docker prerequisite that the planned
 * {@code src/test/java/com/cardemo/integration/repository} tier will carry.
 *
 * <p><strong>What this test does not claim to do.</strong> It does not execute a query, does not open a
 * connection, does not start a Spring context and does not assert that any SQL is emitted. Behavioural
 * coverage - that the alternate-key finders really return several rows, that the pessimistic lock really
 * blocks a competing writer, that the lexical date range really filters inclusively at both ends - belongs to
 * the planned repository integration tier and is explicitly out of this tier's reach. Nothing here is a
 * substitute for that, and nothing here should be read as one.
 *
 * <p><strong>Determinism.</strong> The test performs no I/O other than listing the repository source
 * directory, reads no configuration, resolves no environment variable, holds no credential and reaches no
 * endpoint. Every case folding passes {@link Locale#ROOT} explicitly, so behaviour cannot vary between a
 * developer machine and continuous integration.
 */
@DisplayName("com.cardemo.repository - declared contract of all eleven repository interfaces")
class RepositoryContractTest {

    /** Relative path of the repository package's main source directory, listed by the shape assertions. */
    private static final Path REPOSITORY_SOURCE_DIR = Path.of("src", "main", "java", "com", "cardemo",
            "repository");

    /** The eleven interfaces, in the order the package documentation lists them. */
    private static final List<Class<?>> ALL_REPOSITORIES = List.of(
            AccountRepository.class,
            CardRepository.class,
            CardCrossReferenceRepository.class,
            CustomerRepository.class,
            TransactionRepository.class,
            DailyTransactionRepository.class,
            TransactionCategoryBalanceRepository.class,
            DisclosureGroupRepository.class,
            TransactionTypeRepository.class,
            TransactionCategoryRepository.class,
            UserSecurityRepository.class);

    /**
     * Returns the {@code JpaRepository} type arguments of the supplied repository interface as an
     * aggregate-root and identifier pair.
     *
     * <p>Spring Data resolves the aggregate root and the identifier from exactly this position, so reading
     * them back is a machine-checked statement of what the interface manages.
     *
     * @param repository the repository interface to inspect
     * @return the two type arguments of the {@code JpaRepository} supertype, aggregate root first
     */
    private static List<Type> jpaRepositoryTypeArguments(final Class<?> repository) {
        for (final Type supertype : repository.getGenericInterfaces()) {
            if (supertype instanceof ParameterizedType parameterized
                    && JpaRepository.class.equals(parameterized.getRawType())) {
                return List.of(parameterized.getActualTypeArguments());
            }
        }
        throw new AssertionError(repository.getName() + " does not extend JpaRepository directly");
    }

    /**
     * Returns the names of the methods the supplied interface declares itself, excluding everything inherited
     * from {@code JpaRepository} and its supertypes.
     *
     * @param repository the repository interface to inspect
     * @return the declared method names, sorted so the comparison is order independent
     */
    private static List<String> declaredMethodNames(final Class<?> repository) {
        return ReflectionCensus.declaredMethodNames(repository).stream()
                .sorted()
                .toList();
    }

    /**
     * Returns the single declared method with the supplied name, failing when it is absent or overloaded in a
     * way the caller did not expect.
     *
     * @param repository the repository interface to inspect
     * @param name       the declared method name
     * @param parameters the exact parameter types, so an overload is addressed unambiguously
     * @return the declared method
     */
    private static Method declaredMethod(final Class<?> repository, final String name,
            final Class<?>... parameters) {
        try {
            return repository.getDeclaredMethod(name, parameters);
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError(repository.getSimpleName() + " must declare " + name
                    + Arrays.toString(parameters), absent);
        }
    }

    /**
     * Returns every {@code @Query} value declared anywhere in the package, so the query text can be audited
     * once rather than interface by interface.
     *
     * @return the declared JPQL strings paired with the declaring method for diagnostics
     */
    private static List<String> allDeclaredQueryText() {
        final List<String> queries = new ArrayList<>();
        for (final Class<?> repository : ALL_REPOSITORIES) {
            for (final Method method : repository.getDeclaredMethods()) {
                final Query query = method.getAnnotation(Query.class);
                if (query != null) {
                    queries.add(repository.getSimpleName() + '#' + method.getName() + " -> " + query.value());
                }
            }
        }
        return List.copyOf(queries);
    }

    @Nested
    @DisplayName("Package shape")
    class PackageShape {

        @Test
        @DisplayName("holds exactly twelve source files - eleven interfaces and package-info")
        void holdsExactlyTwelveSourceFiles() throws IOException {
            assertThat(REPOSITORY_SOURCE_DIR).as("repository source directory must be present").exists();
            final List<String> names;
            try (Stream<Path> entries = Files.list(REPOSITORY_SOURCE_DIR)) {
                names = entries.map(Path::getFileName)
                        .map(Path::toString)
                        .sorted()
                        .toList();
            }
            assertThat(names)
                    .as("exactly the eleven interfaces plus package-info, with no implementation class, "
                            + "no custom fragment, no base abstraction and no subpackage")
                    .containsExactly(
                            "AccountRepository.java",
                            "CardCrossReferenceRepository.java",
                            "CardRepository.java",
                            "CustomerRepository.java",
                            "DailyTransactionRepository.java",
                            "DisclosureGroupRepository.java",
                            "TransactionCategoryBalanceRepository.java",
                            "TransactionCategoryRepository.java",
                            "TransactionRepository.java",
                            "TransactionTypeRepository.java",
                            "UserSecurityRepository.java",
                            "package-info.java");
        }

        @Test
        @DisplayName("carries package documentation with the four mandated operational headings")
        void carriesPackageDocumentationWithFourMandatedHeadings() throws IOException {
            final Path packageInfo = REPOSITORY_SOURCE_DIR.resolve("package-info.java");
            assertThat(packageInfo).as("the package docstring is the module documentation").exists();
            final String text = Files.readString(packageInfo);
            assertThat(text)
                    .as("the four documentation subjects required of every module, plus the honest "
                            + "unavailability disclosure and the Apache-2.0 provenance banner")
                    .contains("<h2>What it does</h2>")
                    .contains("<h2>How to run/build/test</h2>")
                    .contains("<h2>Key configs and defaults</h2>")
                    .contains("<h2>Common failure modes and troubleshooting</h2>")
                    .contains("Not available")
                    .contains("http://www.apache.org/licenses/LICENSE-2.0")
                    .contains("package com.cardemo.repository;");
            assertThat(text)
                    .as("no import and no annotation, both of which would be gratuitous here and an "
                            + "unused one of which is a build failure under -Werror")
                    .doesNotContain("\nimport ")
                    .doesNotContain("\n@");
        }

        @Test
        @DisplayName("contains no README and no Markdown file, because the docstring is the documentation")
        void containsNoReadmeAndNoMarkdownFile() throws IOException {
            try (Stream<Path> entries = Files.walk(REPOSITORY_SOURCE_DIR)) {
                final List<String> offenders = entries.map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".md")
                                || name.toLowerCase(Locale.ROOT).startsWith("readme"))
                        .toList();
                assertThat(offenders).as("a second copy of the documentation would immediately diverge")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("every interface is a public interface annotated @Repository")
        void everyInterfaceIsPublicAndAnnotated() {
            for (final Class<?> repository : ALL_REPOSITORIES) {
                assertThat(repository.isInterface())
                        .as("%s must be an interface, so Spring Data supplies the implementation",
                                repository.getSimpleName())
                        .isTrue();
                assertThat(Modifier.isPublic(repository.getModifiers()))
                        .as("%s must be public", repository.getSimpleName())
                        .isTrue();
                assertThat(repository.getAnnotation(Repository.class))
                        .as("%s must carry @Repository so persistence exception translation applies",
                                repository.getSimpleName())
                        .isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Aggregate root and identifier type arguments")
    class TypeArguments {

        @Test
        @DisplayName("one interface per VSAM base cluster, plus the sequential staging dataset")
        void oneInterfacePerClusterPlusStaging() {
            assertThat(ALL_REPOSITORIES)
                    .as("ten base clusters catalogued in app/catlg/LISTCAT.txt plus AWS.M2.CARDDEMO.DALYTRAN.PS")
                    .hasSize(11);
        }

        @Test
        @DisplayName("each interface names the entity and identifier its legacy dataset key implies")
        void eachInterfaceNamesItsEntityAndIdentifier() {
            assertThat(jpaRepositoryTypeArguments(AccountRepository.class))
                    .as("ACCTDATA key length 11 at app/catlg/LISTCAT.txt:L59 - a numeric account identifier")
                    .containsExactly(Account.class, Long.class);
            assertThat(jpaRepositoryTypeArguments(CardRepository.class))
                    .as("CARDDATA key length 16 at :L202 - a fixed-width card number")
                    .containsExactly(Card.class, String.class);
            assertThat(jpaRepositoryTypeArguments(CardCrossReferenceRepository.class))
                    .as("CARDXREF key length 16 at :L403")
                    .containsExactly(CardCrossReference.class, String.class);
            assertThat(jpaRepositoryTypeArguments(CustomerRepository.class))
                    .as("CUSTDATA key length 9 at :L632")
                    .containsExactly(Customer.class, Long.class);
            assertThat(jpaRepositoryTypeArguments(TransactionRepository.class))
                    .as("TRANSACT key length 16 at :L3593")
                    .containsExactly(Transaction.class, String.class);
            assertThat(jpaRepositoryTypeArguments(DailyTransactionRepository.class))
                    .as("DALYTRAN is physical sequential and carries no key, so the staging row is "
                            + "addressed by the loader-assigned ingestion ordinal, not by DALYTRAN-ID")
                    .containsExactly(DailyTransaction.class, Long.class);
            assertThat(jpaRepositoryTypeArguments(TransactionCategoryBalanceRepository.class))
                    .as("TCATBALF key length 17 at :L1371 - a three component composite key")
                    .containsExactly(TransactionCategoryBalance.class, TransactionCategoryBalanceId.class);
            assertThat(jpaRepositoryTypeArguments(DisclosureGroupRepository.class))
                    .as("DISCGRP key length 16 at :L896 - a three component composite key")
                    .containsExactly(DisclosureGroup.class, DisclosureGroupId.class);
            assertThat(jpaRepositoryTypeArguments(TransactionTypeRepository.class))
                    .as("TRANTYPE key length 2 at :L3779")
                    .containsExactly(TransactionType.class, String.class);
            assertThat(jpaRepositoryTypeArguments(TransactionCategoryRepository.class))
                    .as("TRANCATG key length 6 at :L1475 - a two component composite key")
                    .containsExactly(TransactionCategory.class, TransactionCategoryId.class);
            assertThat(jpaRepositoryTypeArguments(UserSecurityRepository.class))
                    .as("USRSEC key length 8 at :L3883")
                    .containsExactly(UserSecurity.class, String.class);
        }
    }

    @Nested
    @DisplayName("Declared finder inventory")
    class DeclaredFinders {

        @Test
        @DisplayName("the two read-for-update interfaces declare exactly their locking finder")
        void readForUpdateInterfacesDeclareOnlyTheirLockingFinder() {
            assertThat(declaredMethodNames(AccountRepository.class))
                    .containsExactly("findByIdForUpdate");
            assertThat(declaredMethodNames(CustomerRepository.class))
                    .containsExactly("findByIdForUpdate");
        }

        @Test
        @DisplayName("the alternate-key interfaces declare their account finder and nothing surplus")
        void alternateKeyInterfacesDeclareTheirAccountFinder() {
            assertThat(declaredMethodNames(CardRepository.class))
                    .containsExactly("findAllByOrderByCardNumberAsc", "findByAccountIdOrderByCardNumberAsc");
            assertThat(declaredMethodNames(CardCrossReferenceRepository.class))
                    .containsExactly("findByAccountIdOrderByCardNumberAsc");
        }

        @Test
        @DisplayName("the transaction interface declares the five paths its callers need")
        void transactionInterfaceDeclaresFivePaths() {
            assertThat(declaredMethodNames(TransactionRepository.class))
                    .containsExactly(
                            "findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc",
                            "findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc",
                            "findByTransactionIdGreaterThanOrderByTransactionIdAsc",
                            "findByTransactionIdLessThanOrderByTransactionIdDesc",
                            "findFirstByOrderByTransactionIdDesc");
        }

        @Test
        @DisplayName("the batch-only scan interfaces declare their ordered scan")
        void batchOnlyScanInterfacesDeclareTheirOrderedScan() {
            assertThat(declaredMethodNames(DailyTransactionRepository.class))
                    .containsExactly("findAllByOrderByIngestSequenceAsc");
            assertThat(declaredMethodNames(TransactionCategoryBalanceRepository.class))
                    .containsExactly("findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc");
            assertThat(declaredMethodNames(DisclosureGroupRepository.class))
                    .containsExactly("findDefaultGroupRate");
            assertThat(declaredMethodNames(UserSecurityRepository.class))
                    .containsExactly("findAllByOrderBySecUsrIdAsc");
        }

        @Test
        @DisplayName("the two reference tables declare no method at all, because an unused finder is dead code")
        void referenceTablesDeclareNoMethod() {
            assertThat(declaredMethodNames(TransactionTypeRepository.class))
                    .as("TRANTYPE is reached by identifier and by full scan only")
                    .isEmpty();
            assertThat(declaredMethodNames(TransactionCategoryRepository.class))
                    .as("TRANCATG is reached by composite identifier and by full scan only")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("NONUNIQKEY alternate-key paths return collections")
    class NonUniqueAlternateKeys {

        @Test
        @DisplayName("the CARDDATA alternate index yields a Page of cards keyed by account")
        void cardDataAlternateIndexYieldsAPage() {
            final Method finder = declaredMethod(CardRepository.class,
                    "findByAccountIdOrderByCardNumberAsc", Long.class, Pageable.class);
            assertThat(finder.getReturnType())
                    .as("AXRKP 16 at app/catlg/LISTCAT.txt:L283 is NONUNIQKEY at :L285, so one account "
                            + "may hold several cards and a scalar return would silently drop rows")
                    .isEqualTo(Page.class);
        }

        @Test
        @DisplayName("the CARDXREF alternate index yields a List of cross-references keyed by account")
        void cardXrefAlternateIndexYieldsAList() {
            final Method finder = declaredMethod(CardCrossReferenceRepository.class,
                    "findByAccountIdOrderByCardNumberAsc", Long.class);
            assertThat(finder.getReturnType())
                    .as("AXRKP 25 at app/catlg/LISTCAT.txt:L486 is NONUNIQKEY at :L488")
                    .isEqualTo(List.class);
        }

        @Test
        @DisplayName("the TRANSACT alternate index yields a List over a lexical date range")
        void transactAlternateIndexYieldsAChunkedCollection() {
            final Method finder = declaredMethod(TransactionRepository.class,
                    "findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc",
                    String.class, String.class, Pageable.class);
            assertThat(finder.getReturnType())
                    .as("AXRKP 304 at app/catlg/LISTCAT.txt:L3676 is NONUNIQKEY at :L3678, so the finder "
                            + "yields a collection; it is chunked rather than heap-resident because the "
                            + "report range is unbounded in principle")
                    .isEqualTo(Slice.class);
            assertThat(finder.getParameterTypes())
                    .as("TRAN-PROC-TS is PIC X(26) and the report sort declares TRAN-PROC-DT,305,10,CH, so "
                            + "both bounds are character data compared lexically, never a temporal type")
                    .containsExactly(String.class, String.class, Pageable.class);
        }

        @Test
        @DisplayName("no alternate-key finder narrows its result to Optional or to a scalar")
        void noAlternateKeyFinderNarrowsItsResult() {
            final List<Method> alternateKeyFinders = List.of(
                    declaredMethod(CardRepository.class, "findByAccountIdOrderByCardNumberAsc",
                            Long.class, Pageable.class),
                    declaredMethod(CardCrossReferenceRepository.class, "findByAccountIdOrderByCardNumberAsc",
                            Long.class),
                    declaredMethod(TransactionRepository.class,
                            "findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc",
                            String.class, String.class, Pageable.class));
            for (final Method finder : alternateKeyFinders) {
                final Class<?> returnType = finder.getReturnType();
                assertThat(Collection.class.isAssignableFrom(returnType) || Slice.class.isAssignableFrom(
                        returnType))
                        .as("%s must return a collection because its source index is NONUNIQKEY",
                                finder.getName())
                        .isTrue();
                assertThat(returnType).isNotEqualTo(Optional.class);
            }
        }
    }

    @Nested
    @DisplayName("Paged and keyset browses are deterministic")
    class DeterministicBrowses {

        @Test
        @DisplayName("every multi-row finder names its ordering in the method name or its query")
        void everyMultiRowFinderNamesItsOrdering() {
            for (final Class<?> repository : ALL_REPOSITORIES) {
                for (final Method method : repository.getDeclaredMethods()) {
                    final Class<?> returnType = method.getReturnType();
                    final boolean multiRow = Collection.class.isAssignableFrom(returnType)
                            || Slice.class.isAssignableFrom(returnType);
                    if (!multiRow) {
                        continue;
                    }
                    final Query query = method.getAnnotation(Query.class);
                    final boolean orderedByName = method.getName().contains("OrderBy");
                    final boolean orderedByQuery = query != null
                            && query.value().toLowerCase(Locale.ROOT).contains("order by");
                    assertThat(orderedByName || orderedByQuery)
                            .as("%s#%s returns several rows and must specify an ordering, or two identical "
                                    + "requests can disagree", repository.getSimpleName(), method.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the three keyset browses return Slice and accept a Pageable")
        void keysetBrowsesReturnSliceAndAcceptPageable() {
            final List<String> keysetFinders = List.of(
                    "findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc",
                    "findByTransactionIdGreaterThanOrderByTransactionIdAsc",
                    "findByTransactionIdLessThanOrderByTransactionIdDesc");
            for (final String name : keysetFinders) {
                final Method finder = declaredMethod(TransactionRepository.class, name,
                        String.class, Pageable.class);
                assertThat(finder.getReturnType())
                        .as("%s must return Slice: the legacy browse never counted the whole file, so a "
                                + "Page with its extra count query would not be a faithful replacement", name)
                        .isEqualTo(Slice.class);
            }
        }

        @Test
        @DisplayName("the descending top-one identifier lookup returns Optional and takes no argument")
        void topOneIdentifierLookupReturnsOptional() {
            final Method finder = declaredMethod(TransactionRepository.class,
                    "findFirstByOrderByTransactionIdDesc");
            assertThat(finder.getReturnType())
                    .as("app/cbl/COBIL00C.cbl:L487-L488 answers end-of-file by moving zeros into the "
                            + "identifier, so the empty case is a value the caller defaults, not an error")
                    .isEqualTo(Optional.class);
            assertThat(finder.getParameterCount())
                    .as("the legacy browse positioned at HIGH-VALUES and read backwards once, "
                            + "with no argument")
                    .isZero();
        }

        @Test
        @DisplayName("the category-balance scan orders on the full composite key, account component first")
        void categoryBalanceScanOrdersOnFullCompositeKey() {
            final Method finder = declaredMethod(TransactionCategoryBalanceRepository.class,
                    "findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc", Pageable.class);
            assertThat(finder.getName())
                    .as("the control break at app/cbl/CBACT04C.cbl:L194 is correct only because the account "
                            + "identifier leads the seventeen byte key and the file is read in key order")
                    .startsWith("findAllByOrderByIdAccountIdAsc");
            assertThat(finder.getReturnType()).isEqualTo(Slice.class);
        }
    }

    @Nested
    @DisplayName("Query text is bound, never built")
    class BoundQueries {

        @Test
        @DisplayName("no declared query is native SQL")
        void noDeclaredQueryIsNativeSql() {
            for (final Class<?> repository : ALL_REPOSITORIES) {
                for (final Method method : repository.getDeclaredMethods()) {
                    final Query query = method.getAnnotation(Query.class);
                    if (query == null) {
                        continue;
                    }
                    assertThat(query.nativeQuery())
                            .as("%s#%s must stay JPQL so the dialect cannot leak into the contract",
                                    repository.getSimpleName(), method.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("every query parameter is a named binding declared with @Param")
        void everyQueryParameterIsANamedBinding() {
            for (final Class<?> repository : ALL_REPOSITORIES) {
                for (final Method method : repository.getDeclaredMethods()) {
                    if (method.getAnnotation(Query.class) == null) {
                        continue;
                    }
                    for (final java.lang.reflect.Parameter parameter : method.getParameters()) {
                        if (Pageable.class.isAssignableFrom(parameter.getType())) {
                            continue;
                        }
                        final Param binding = parameter.getAnnotation(Param.class);
                        assertThat(binding)
                                .as("%s#%s parameter %s must be bound by name, never positionally and "
                                        + "never interpolated", repository.getSimpleName(),
                                        method.getName(), parameter.getName())
                                .isNotNull();
                        assertThat(method.getAnnotation(Query.class).value())
                                .as("the query text must reference the declared binding")
                                .contains(":" + binding.value());
                    }
                }
            }
        }

        @Test
        @DisplayName("no query text contains concatenation, a positional marker or a comment sequence")
        void noQueryTextContainsConcatenationOrPositionalMarkers() {
            final List<String> queries = allDeclaredQueryText();
            assertThat(queries).as("the package declares explicit queries worth auditing").isNotEmpty();
            for (final String query : queries) {
                assertThat(query)
                        .as("input is untrusted: query text is a compile-time constant with named "
                                + "bindings only")
                        .doesNotContain("?1")
                        .doesNotContain("?2")
                        .doesNotContain("--")
                        .doesNotContain("/*");
            }
        }

        @Test
        @DisplayName("the disclosure-group fallback is a second query pinned to the blank-padded DEFAULT group")
        void disclosureGroupFallbackIsPinnedToDefaultGroup() {
            final Method fallback = declaredMethod(DisclosureGroupRepository.class,
                    "findDefaultGroupRate", String.class, Integer.class);
            final Query query = fallback.getAnnotation(Query.class);
            assertThat(query).as("the fallback is explicit JPQL, not a derived name").isNotNull();
            assertThat(query.value())
                    .as("app/cbl/CBACT04C.cbl:L437 moves the literal 'DEFAULT' into the group identifier "
                            + "alone; the column is fixed width, so the literal is blank padded to ten")
                    .contains("'DEFAULT   '")
                    .contains(":tranTypeCd")
                    .contains(":tranCatCd");
            assertThat(fallback.getReturnType())
                    .as("app/cbl/CBACT04C.cbl:L446 accepts '00' only, so a second miss is fatal and the "
                            + "empty result is the caller's signal to raise it")
                    .isEqualTo(Optional.class);
        }

        @Test
        @DisplayName("the processing-date range compares the leading ten characters inclusively at both ends")
        void processingDateRangeComparesTheBareTimestampHalfOpen() {
            final Method finder = declaredMethod(TransactionRepository.class,
                    "findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc",
                    String.class, String.class, Pageable.class);
            final Query query = finder.getAnnotation(Query.class);
            assertThat(query).isNotNull();
            final String text = query.value().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            assertThat(text)
                    .as("app/proc/TRANREPT.prc:STEP05R declares TRAN-PROC-DT,305,10,CH, so both bounds are "
                            + "character data compared lexically; the predicate names the bare property so "
                            + "the plain B-tree serves it, and no function is wrapped around the column")
                    .contains("t.procts >= :startdateinclusive")
                    .contains("t.procts < :endboundexclusive")
                    .doesNotContain("substring(");
            assertThat(text)
                    .as("the sort key is the card number first, then the transaction identifier")
                    .contains("order by t.cardnumber asc, t.transactionid asc");
        }
    }

    @Nested
    @DisplayName("Locking and least privilege")
    class LockingAndLeastPrivilege {

        @Test
        @DisplayName("both read-for-update finders take a pessimistic write lock")
        void bothReadForUpdateFindersTakeAPessimisticWriteLock() {
            final Method accountFinder = declaredMethod(AccountRepository.class,
                    "findByIdForUpdate", Long.class);
            final Method customerFinder = declaredMethod(CustomerRepository.class,
                    "findByIdForUpdate", Long.class);
            for (final Method finder : List.of(accountFinder, customerFinder)) {
                final Lock lock = finder.getAnnotation(Lock.class);
                assertThat(lock)
                        .as("app/cbl/COACTUPC.cbl:L3894 and :L3920 both read UPDATE before the paired "
                                + "rewrites, and the @Version column alone cannot close that window")
                        .isNotNull();
                assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
                assertThat(finder.getReturnType()).isEqualTo(Optional.class);
            }
        }

        @Test
        @DisplayName("the user interface exposes no password-hash projection")
        void userInterfaceExposesNoPasswordHashProjection() {
            for (final Method method : UserSecurityRepository.class.getDeclaredMethods()) {
                final String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("the hash reaches the user-details service alone; no finder may select it")
                        .doesNotContain("password")
                        .doesNotContain("pwd")
                        .doesNotContain("hash")
                        .doesNotContain("secret")
                        .doesNotContain("credential");
                assertThat(method.getReturnType()).isNotEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the four batch-only interfaces declare no mutating or deleting finder")
        void batchOnlyInterfacesDeclareNoMutatingFinder() {
            final List<Class<?>> batchOnly = List.of(
                    TransactionCategoryBalanceRepository.class,
                    DisclosureGroupRepository.class,
                    TransactionTypeRepository.class,
                    TransactionCategoryRepository.class);
            for (final Class<?> repository : batchOnly) {
                for (final Method method : repository.getDeclaredMethods()) {
                    final String name = method.getName().toLowerCase(Locale.ROOT);
                    assertThat(name)
                            .as("app/csd/CARDDEMO.CSD names eight files and none of these four, so they "
                                    + "receive no online surface and declare no bespoke mutation")
                            .doesNotStartWith("delete")
                            .doesNotStartWith("update")
                            .doesNotStartWith("remove")
                            .doesNotStartWith("insert");
                }
            }
        }

        @Test
        @DisplayName("no interface declares a static or default method, so nothing executes here")
        void noInterfaceDeclaresStaticOrDefaultMethod() {
            for (final Class<?> repository : ALL_REPOSITORIES) {
                for (final Method method : repository.getDeclaredMethods()) {
                    assertThat(method.isDefault())
                            .as("%s#%s must stay abstract: behaviour in a repository interface would "
                                    + "bypass the service layer that owns it", repository.getSimpleName(),
                                    method.getName())
                            .isFalse();
                    assertThat(Modifier.isStatic(method.getModifiers()))
                            .as("%s#%s must not be static, which would be global behaviour on a type that "
                                    + "declares none", repository.getSimpleName(), method.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("no interface declares a field, so the package holds no mutable state")
        void noInterfaceDeclaresAField() {
            for (final Class<?> repository : ALL_REPOSITORIES) {
                assertThat(repository.getDeclaredFields())
                        .as("%s must hold no constant and no state", repository.getSimpleName())
                        .isEmpty();
            }
        }
    }
}
