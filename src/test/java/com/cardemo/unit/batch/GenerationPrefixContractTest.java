/*
 * ******************************************************************
 * Program     : GenerationPrefixContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (object-key namespace contract)
 * Function    : Proves the one shared prefix grammar accepts exactly
 *               the nine spellings the profile declares and refuses
 *               every malformed one by naming the property; proves the
 *               listing form is derived rather than configured; proves
 *               the startup guard refuses two roots that could resolve
 *               to one another and names the relation; and proves the
 *               six classes the review named all delegate to it rather
 *               than validating for themselves.
 * Source      : app/jcl/DEFGDGB.jcl :L24-L45 (four GDG bases);
 *               app/jcl/DALYREJS.jcl :L24-L28 (DALYREJS base);
 *               app/jcl/INTCALC.jcl :L37-L41 (SYSTRAN base);
 *               app/jcl/COMBTRAN.jcl :L33-L37 (TRANSACT.COMBINED base);
 *               app/jcl/PRTCATBL.jcl :L35-L39 (TCATBALF.BKUP base);
 *               app/proc/TRANREPT.prc :L27-L31 (TRANSACT.BKUP base);
 *               app/proc/TRANREPT.prc :L49-L53 (TRANSACT.DALY base);
 *               app/jcl/CREASTMT.JCL :L26-L32 (TRXFL work cluster)
 *                                                          @ 7756d89
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.batch.GenerationPrefixContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one object-key prefix grammar, its listing form, its startup collision guard, and the delegation.
 *
 * <h2>What this proves</h2>
 *
 * <p><strong>Finding m-02, severity Medium.</strong> Six classes each carried a private prefix validator and no
 * two agreed. The weakest accepted a leading separator, a doubled separator, a {@code ..} segment, a control
 * character and interior whitespace, and five of the six silently rewrote the configured value so that the
 * spelling in the profile and the spelling in force could differ with nothing reporting it. No pairwise
 * uniqueness check existed anywhere, so two bases could be configured into one namespace and each would see
 * the other's generations as its own.
 *
 * <p>Three properties are asserted, and the third is the one a grammar test usually omits:
 *
 * <ol>
 *   <li><b>The grammar.</b> Every spelling the profile declares is accepted unchanged, and every malformed
 *       spelling is refused by a message naming the property - because a diagnostic that does not name the
 *       property leaves an operator grepping nine keys.</li>
 *   <li><b>The collision guard.</b> Equality, path ancestry and a bare string prefix are each refused with the
 *       relation named, and the nine roots the profile actually declares pass.</li>
 *   <li><b>The delegation.</b> A shared validator that six classes ignore is not a centralization. Each of the
 *       six sources is read and asserted to reference the contract, and asserted <em>not</em> to have kept a
 *       private normaliser - so a future class that reintroduces one fails here.</li>
 * </ol>
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Dtest=GenerationPrefixContractTest test}. No container, no database, no profile.
 */
@DisplayName("Object-key prefix contract - one grammar, one listing form, one collision guard")
class GenerationPrefixContractTest {

    /** A property name that must appear in every diagnostic. */
    private static final String PROPERTY = "carddemo.aws.s3.gdg-prefixes.systran";

    /** The nine roots the profile declares, in the order the contract binds them. */
    private static final List<String> DECLARED_ROOTS = List.of(
            "gdg/transact-bkup", "gdg/transact-daly", "gdg/transact-combined", "gdg/systran",
            "gdg/dalyrejs", "gdg/tranrept", "gdg/tcatbalf-bkup", "work/trxfl", "transact");

    /**
     * Every class that validates an object-key prefix, relative to the module root.
     *
     * <p>The first six are the ones the review named. The seventh,
     * {@code CombinedTransactionReader}, is the site measurement found afterwards: it carried its own
     * whitespace-stripping, separator-appending validator over {@code TRANSACT.BKUP} and {@code SYSTRAN} -
     * two of the same bases - so a shared grammar that left it out would still have allowed two spellings of
     * one base to be in force at once. It is listed here rather than in a test of its own so that a future
     * ninth site cannot be added beside it without this list being read.
     */
    private static final List<String> DELEGATING_SOURCES = List.of(
            "src/main/java/com/cardemo/batch/jobs/InterestCalculationJob.java",
            "src/main/java/com/cardemo/batch/jobs/CombineTransactionsJob.java",
            "src/main/java/com/cardemo/batch/writers/RejectWriter.java",
            "src/main/java/com/cardemo/batch/writers/TransactionWriter.java",
            "src/main/java/com/cardemo/batch/jobs/StatementGenerationJob.java",
            "src/main/java/com/cardemo/batch/readers/TransactionBackupReader.java",
            "src/main/java/com/cardemo/batch/readers/CombinedTransactionReader.java");

    /**
     * Constructs the contract over a chosen set of roots, in the constructor's own parameter order.
     *
     * @param roots exactly nine values
     * @return the constructed contract
     */
    private static GenerationPrefixContract contractOver(final List<String> roots) {
        assertThat(roots).as("the constructor binds exactly nine roots").hasSize(9);
        return new GenerationPrefixContract(roots.get(0), roots.get(1), roots.get(2), roots.get(3),
                roots.get(4), roots.get(5), roots.get(6), roots.get(7), roots.get(8));
    }

    /**
     * The declared roots with one entry replaced, so a collision can be introduced in isolation.
     *
     * @param index the position to replace
     * @param value the replacement value
     * @return a nine-element list
     */
    private static List<String> declaredRootsWith(final int index, final String value) {
        final List<String> roots = new ArrayList<>(DECLARED_ROOTS);
        roots.set(index, value);
        return roots;
    }

    /**
     * Reads one source file from the module root.
     *
     * @param relativePath the path relative to the module root
     * @return the file's text
     * @throws IOException if the file cannot be read
     */
    private static String sourceOf(final String relativePath) throws IOException {
        final Path path = Path.of(relativePath);
        assertThat(Files.exists(path))
                .as("%s must exist, or the delegation assertions have no subject", relativePath)
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * The grammar: exactly one spelling is accepted, and every other is refused by name.
     */
    @Nested
    @DisplayName("the grammar accepts one spelling and refuses the rest by naming the property")
    class TheGrammar {

        /**
         * Creates the grammar group.
         *
         * <p>Declared explicitly because JUnit builds one instance per test method and this group needs no
         * shared state.
         */
        TheGrammar() {
            // Intentionally empty; every test builds what it needs.
        }

        @Test
        @DisplayName("every root the profile declares is accepted UNCHANGED, so nothing is normalised")
        void everyDeclaredRootIsAcceptedUnchanged() {
            for (final String root : DECLARED_ROOTS) {
                assertThat(GenerationPrefixContract.requireRelativePrefix(root, PROPERTY))
                        .as("%s is a declared value and must pass through byte for byte", root)
                        .isEqualTo(root);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "gdg/systran/", "gdg/systran///", "/gdg/systran", "gdg//systran", "gdg/systran ",
            " gdg/systran", "gdg/../systran", "gdg/./systran", "..", ".", "gdg\\systran",
            "gdg/sys tran", "/", "///"})
        @DisplayName("every malformed spelling is refused, and the message names the property")
        void everyMalformedSpellingIsRefused(final String malformed) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> GenerationPrefixContract.requireRelativePrefix(malformed, PROPERTY))
                    .withMessageContaining(PROPERTY);
        }

        @Test
        @DisplayName("an absent or blank value is refused, and the reason names the bucket-root hazard rather "
                + "than merely reporting a null")
        void anAbsentOrBlankValueIsRefused() {
            for (final String empty : new String[] {null, "", "   ", "\t"}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(() -> GenerationPrefixContract.requireRelativePrefix(empty, PROPERTY))
                        .withMessageContaining(PROPERTY)
                        .withMessageContaining("bucket root");
            }
        }

        @Test
        @DisplayName("a control byte is refused by POSITION and CODE POINT, so a non-printing character is "
                + "diagnosable at all")
        void aControlByteIsRefusedByPositionAndCodePoint() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> GenerationPrefixContract.requireRelativePrefix(
                            "gdg/sys\ntran", PROPERTY))
                    .withMessageContaining(PROPERTY)
                    .withMessageContaining("position 8")
                    .withMessageContaining("code point 10");
        }

        @Test
        @DisplayName("a single deep prefix is accepted, so the grammar restricts the SHAPE of a segment and "
                + "not how many there are")
        void aDeepPrefixIsAccepted() {
            final String deep = "gdg/2026/08/transact-bkup";
            assertThat(GenerationPrefixContract.requireRelativePrefix(deep, PROPERTY)).isEqualTo(deep);
        }
    }

    /**
     * The listing form: derived from the validated value, never configured.
     */
    @Nested
    @DisplayName("the listing form is derived from the validated relative form")
    class TheListingForm {

        /**
         * Creates the listing-form group.
         *
         * <p>Declared explicitly for the same reason as the grammar group.
         */
        TheListingForm() {
            // Intentionally empty; every test builds what it needs.
        }

        @Test
        @DisplayName("exactly one separator is appended, which is what stops gdg/transact-bkup from also "
                + "matching gdg/transact-bkup-shadow")
        void exactlyOneSeparatorIsAppended() {
            assertThat(GenerationPrefixContract.listingPrefixOf("gdg/transact-bkup"))
                    .isEqualTo("gdg/transact-bkup/")
                    .doesNotEndWith("//");
            assertThat("gdg/transact-bkup-shadow/0000000001")
                    .as("the whole point: the shadow base does not match the separated prefix, and would have "
                            + "matched the unseparated one")
                    .doesNotStartWith(GenerationPrefixContract.listingPrefixOf("gdg/transact-bkup"))
                    .startsWith("gdg/transact-bkup");
        }

        @Test
        @DisplayName("a value that already ends in a separator is REFUSED, because it can only mean the "
                + "validated form was skipped")
        void anAlreadySeparatedValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> GenerationPrefixContract.listingPrefixOf("gdg/transact-bkup/"))
                    .withMessageContaining("requireRelativePrefix");
        }

        @Test
        @DisplayName("an absent or blank value is refused rather than yielding a bare separator")
        void anAbsentValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> GenerationPrefixContract.listingPrefixOf(null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> GenerationPrefixContract.listingPrefixOf("  "));
        }
    }

    /**
     * The startup guard: no two roots may resolve to one another's objects.
     */
    @Nested
    @DisplayName("the startup guard refuses two roots that could resolve to one another")
    class TheCollisionGuard {

        /**
         * Creates the collision-guard group.
         *
         * <p>Declared explicitly for the same reason as the grammar group.
         */
        TheCollisionGuard() {
            // Intentionally empty; every test builds what it needs.
        }

        @Test
        @DisplayName("the nine roots the profile actually declares are pairwise safe, so the guard passes on "
                + "the shipped configuration rather than only on a contrived one")
        void theDeclaredRootsArePairwiseSafe() {
            final GenerationPrefixContract contract = contractOver(DECLARED_ROOTS);

            assertThat(contract.configuredRoots())
                    .as("all nine are published so a deployment's effective roots are readable")
                    .hasSize(9)
                    .containsEntry("carddemo.aws.s3.gdg-prefixes.systran", "gdg/systran")
                    .containsEntry("carddemo.aws.s3.work-prefixes.trxfl", "work/trxfl")
                    .containsEntry("carddemo.aws.s3.transaction-object-prefix", "transact");
            assertThat(contract.configuredRoots().values())
                    .as("every declared root is distinct, which is the invariant the guard holds")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("two EQUAL roots are refused, and both property names appear so the pair is actionable")
        void twoEqualRootsAreRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> contractOver(declaredRootsWith(3, "gdg/transact-bkup")))
                    .withMessageContaining("carddemo.aws.s3.gdg-prefixes.transact-bkup")
                    .withMessageContaining("carddemo.aws.s3.gdg-prefixes.systran")
                    .withMessageContaining("they are equal");
        }

        @Test
        @DisplayName("a PATH ANCESTOR is refused, because a listing of the ancestor returns the descendant's "
                + "objects and a (0) read cannot tell them apart")
        void aPathAncestorIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> contractOver(declaredRootsWith(3, "gdg/transact-bkup/systran")))
                    .withMessageContaining("path ancestor");
        }

        @Test
        @DisplayName("a bare STRING PREFIX is refused too, and the message says why it is a near miss rather "
                + "than a collision")
        void aStringPrefixIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> contractOver(declaredRootsWith(3, "gdg/transact-bkup-shadow")))
                    .withMessageContaining("string prefix")
                    .withMessageContaining("appends a separator");
        }

        @Test
        @DisplayName("a malformed root fails the grammar first, naming its own property, so a collision "
                + "message never masks a syntax error")
        void aMalformedRootFailsTheGrammarFirst() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> contractOver(declaredRootsWith(5, "gdg/tranrept/")))
                    .withMessageContaining("carddemo.aws.s3.gdg-prefixes.tranrept")
                    .withMessageContaining("must not end with");
        }

        @Test
        @DisplayName("the published map is unmodifiable, so a caller cannot edit the validated catalogue")
        void thePublishedMapIsUnmodifiable() {
            final GenerationPrefixContract contract = contractOver(DECLARED_ROOTS);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> contract.configuredRoots().put("x", "y"));
        }
    }

    /**
     * The delegation: a shared validator its callers ignore would not be a centralization.
     */
    @Nested
    @DisplayName("every prefix-validating class delegates, and none kept a private normaliser")
    class TheDelegation {

        /**
         * Creates the delegation group.
         *
         * <p>Declared explicitly for the same reason as the grammar group.
         */
        TheDelegation() {
            // Intentionally empty; every test reads what it needs.
        }

        @Test
        @DisplayName("each prefix-validating class references the shared contract")
        void eachOfTheSixReferencesTheSharedContract() throws IOException {
            for (final String relativePath : DELEGATING_SOURCES) {
                assertThat(sourceOf(relativePath))
                        .as("%s must reach the shared grammar rather than carry its own", relativePath)
                        .contains("GenerationPrefixContract.requireRelativePrefix");
            }
        }

        @Test
        @DisplayName("none of them still trims a separator in a loop, which is the shape every one of the "
                + "superseded validators had")
        void noneOfTheSixStillTrimsASeparator() throws IOException {
            for (final String relativePath : DELEGATING_SOURCES) {
                final String source = sourceOf(relativePath);
                final List<String> offending = new ArrayList<>();
                for (final String line : source.split("\n", -1)) {
                    final String trimmed = line.strip();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                        // The prose deliberately records what the superseded validators did, so a raw scan
                        // would fail on the very sentence that says the behaviour is gone.
                        continue;
                    }
                    final boolean loopsOnSeparator =
                            (trimmed.startsWith("while") || trimmed.startsWith("} while"))
                                    && (trimmed.contains("endsWith") || trimmed.contains("startsWith")
                                            || trimmed.contains("charAt"));
                    if (loopsOnSeparator) {
                        offending.add(relativePath + ": " + trimmed);
                    }
                }
                assertThat(offending)
                        .as("a separator-trimming loop is how each superseded validator normalised a prefix; "
                                + "the shared grammar refuses instead, so no such loop should remain")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the reader that needs a trailing separator derives it rather than accepting it")
        void theReaderDerivesItsListingSeparator() throws IOException {
            final String source =
                    sourceOf("src/main/java/com/cardemo/batch/readers/TransactionBackupReader.java");

            assertThat(source)
                    .as("the listing form is derived from the validated relative form, so configuration has "
                            + "exactly one accepted spelling")
                    .contains("GenerationPrefixContract.listingPrefixOf");
            assertThat(source)
                    .as("finding m-02 also corrected this constant: it read gdg/transact-bkup/ while the "
                            + "profile declares gdg/transact-bkup, so the two disagreed by one character and "
                            + "only a profile-less unit test ever saw it")
                    .contains("DEFAULT_GENERATION_PREFIX = \"gdg/transact-bkup\";");
        }

        @Test
        @DisplayName("the concatenated reader derives both of its listing separators, and both of its "
                + "inline defaults are relative")
        void theConcatenatedReaderDerivesBothListingSeparators() throws IOException {
            final String source =
                    sourceOf("src/main/java/com/cardemo/batch/readers/CombinedTransactionReader.java");

            assertThat(source)
                    .as("this reader lists two bases, so it needs the listing form on both; deriving it is "
                            + "what keeps the accepted configuration spelling single")
                    .contains("GenerationPrefixContract.listingPrefixOf");
            assertThat(source)
                    .as("both inline defaults carried a trailing separator while the profile declares "
                            + "neither with one; the defaults are what a profile-less context binds, so they "
                            + "are the spelling the strict grammar would have refused")
                    .contains("DEFAULT_BACKUP_GENERATION_PREFIX = \"gdg/transact-bkup\";")
                    .contains("DEFAULT_SYSTRAN_GENERATION_PREFIX = \"gdg/systran\";");
            // The superseded rule is gone rather than merely bypassed. Stated as "not on the CONFIGURED
            // path" rather than "nowhere in the file", because the two are different claims. Appending a
            // separator to a configured prefix is the normalisation the shared grammar refuses - it gives one
            // decision two accepted spellings. Deriving a listing form from the pinned generation a sibling
            // job hands over at run time is not that: the value is not configuration, no operator writes it,
            // and it arrives in whichever form the launching stage published. So the assertion is that the
            // normalisation survives in exactly one place, and that place is the handoff validator.
            final int normalisations = source.split(java.util.regex.Pattern.quote("stripped + KEY_SEPARATOR"),
                    -1).length - 1;
            assertThat(normalisations)
                    .as("one normalisation only: the configured-prefix path must delegate instead")
                    .isEqualTo(1);
            final int validatorStart = source.indexOf("private static String requirePinnedGeneration(");
            assertThat(validatorStart)
                    .as("the handoff validator must exist for the surviving normalisation to belong to it")
                    .isNotNegative();
            assertThat(source.indexOf("stripped + KEY_SEPARATOR"))
                    .as("and the surviving normalisation sits inside it, not on any configured path")
                    .isGreaterThan(validatorStart);
        }
    }
}
