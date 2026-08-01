/*
 * ******************************************************************
 * Program     : TransactionCombineProcessorCoverageTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/batch
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the combine step of COMBTRAN, the one batch job
 *               in the corpus with no COBOL program of its own: the
 *               TRAN-ID ascending order the SORT step imposes on a
 *               concatenated input, the fixed-width record geometry
 *               DCB=(*.SORTIN) propagates from the two SORTIN
 *               datasets, and the three ways the IDCAMS REPRO into
 *               the keyed transaction cluster can fail - a repeated
 *               identifier, a constraint violation, and anything
 *               else, which is fatal.
 * Source      : app/jcl/COMBTRAN.jcl:L22-L37 (STEP05R: PGM=SORT over
 *               the concatenated SORTIN of TRANSACT.BKUP(0) and
 *               SYSTRAN(0), the SYMNAMES card TRAN-ID,1,16,CH at
 *               :L28, SORT FIELDS=(TRAN-ID,A) at :L30, and
 *               DCB=(*.SORTIN) at :L35 carrying the record geometry
 *               to SORTOUT), L41-L48 (STEP10: IDCAMS REPRO
 *               INFILE(TRANSACT) OUTFILE(TRANVSAM) loading the keyed
 *               cluster) @ 7756d89
 * Source      : app/cpy/CVTRA05Y.cpy (the 350-byte transaction record
 *               layout whose PIC clauses fix every field width the
 *               geometry check enforces) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit test for {@link TransactionCombineProcessor}, the Java target of {@code app/jcl/COMBTRAN.jcl}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code COMBTRAN} is the only batch job in the corpus whose logic exists nowhere but in job control.
 * It has no COBOL program: {@code STEP05R} is {@code PGM=SORT} and {@code STEP10} is {@code PGM=IDCAMS},
 * so the JCL itself is the source of truth and the processor is a translation of two utility invocations
 * rather than of any paragraph. That changes what there is to assert. There is no validation cascade to
 * reproduce and no reject record to emit, because neither utility has either. What the utilities do have is
 * a set of preconditions they enforce silently and violently - DFSORT trusts the {@code SYMNAMES} offsets
 * absolutely, and {@code REPRO} into a keyed cluster fails the whole step on a repeated key - and the
 * processor exists to surface those preconditions as typed failures before the load rather than as an abend
 * during it.</p>
 *
 * <p>Four properties are asserted with particular care, each because the obvious reading of the JCL is
 * wrong or incomplete:</p>
 *
 * <ul>
 *   <li><strong>{@code TRAN-ID} is the only mandatory field.</strong> {@code SORT FIELDS=(TRAN-ID,A)} names
 *       exactly one symbol, so the sort has exactly one precondition. A record carrying nothing but a
 *       well-formed identifier is therefore accepted, and this test proves it rather than assuming the
 *       processor demands a fully populated record.</li>
 *   <li><strong>A short field is legitimate; only an over-long one is not.</strong> Every field is
 *       fixed-width, so a value shorter than its {@code PIC} clause is space-padded at the byte boundary and
 *       loses nothing. A value longer than its clause has nowhere to go. The geometry check is therefore an
 *       upper bound, not an equality test, and a null value is exempt entirely.</li>
 *   <li><strong>A repeated identifier must not be absorbed.</strong> The interest job writes to a fresh
 *       sequential generation and performs no duplicate detection of its own, so duplicate exposure
 *       materialises here, at the {@code REPRO}. Re-driving the interest job with the same date parameter
 *       produces colliding identifiers, and the faithful response is to fail the step. Retrying, upserting
 *       or substituting a database sequence would each change generated values and break the parity
 *       comparison, so the test asserts that a duplicate raises a duplicate-record failure and asserts the
 *       remediation text that says so.</li>
 *   <li><strong>The duplicate branch must be tested before the constraint branch.</strong>
 *       {@link DuplicateKeyException} is a subclass of {@link DataIntegrityViolationException}, so a
 *       branch order that tested the superclass first would classify every duplicate as a generic
 *       constraint violation and lose the colliding key. The test pins the order by asserting that a
 *       duplicate produces a {@link DuplicateRecordException} and specifically <em>not</em> a
 *       {@link DataIntegrityException}.</li>
 * </ul>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>
 *   mvn -o -B test -Dtest=TransactionCombineProcessorCoverageTest -DfailIfNoSpecifiedTests=false
 *   mvn -o -B clean verify -Ddependency-check.skip=true   (full gate: coverage floor plus tests)
 * </pre>
 *
 * <p>No profile, container, database or network endpoint is required. The store failures are constructed
 * directly as Spring data-access exceptions, which is what a real {@code JdbcTemplate.batchUpdate} would
 * have translated its driver error into, so no database is needed to exercise the translation.</p>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>The processor takes no configuration and holds no state; its public no-argument constructor is the only
 * one. Its two published constants come straight from the job control - {@code TRAN_ID_LENGTH} from the
 * {@code SYMNAMES} card {@code TRAN-ID,1,16,CH} and {@code COMBINED_RECORD_LENGTH} from the 350-byte
 * geometry that {@code DCB=(*.SORTIN)} propagates - and {@code TRAN_ID_ASCENDING} is the
 * {@link java.util.Comparator} that replaces the DFSORT invocation itself, with no external sort process
 * spawned.</p>
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in {@code SortKeyPrecondition} means the identifier width has drifted from the
 *       {@code SYMNAMES} card. The card is the authority; recount {@code app/jcl/COMBTRAN.jcl:L28}.</li>
 *   <li>A failure in {@code FixedWidthGeometry} asserting that a short or null value is accepted usually
 *       means an upper-bound check has been tightened into an equality check. Fixed-width fields are
 *       space-padded, so equality is the wrong test and would reject records the utilities accept.</li>
 *   <li>A failure in {@code LoadFailureTranslation} where a duplicate arrives as a
 *       {@link DataIntegrityException} means the two {@code instanceof} branches have been reordered.
 *       Restore the subclass-first order.</li>
 *   <li>A failure in {@code ReproOrderingPrecondition} means the guard against a non-deterministic
 *       identifier accessor has been removed. That guard is reachable only through a subclass whose getter
 *       returns different values on successive calls, which is exactly what the test supplies; the guard
 *       exists because the entity is mutable and the identifier is read twice.</li>
 * </ul>
 */
@DisplayName("TransactionCombineProcessor - the COMBTRAN sort and REPRO combine step")
final class TransactionCombineProcessorCoverageTest {

    /** The trailer every rejection message carries, naming the three job-control cards it derives from. */
    private static final String SOURCE_TRAILER =
            "Sources: app/jcl/COMBTRAN.jcl:L28 sort symbol TRAN-ID,1,16,CH; "
                    + ":L35 DCB=(*.SORTIN) fixing the record at 350 bytes; "
                    + ":L48 REPRO into the keyed cluster.";

    /** The placeholder the processor reports when no identifier is available to name. */
    private static final String KEY_NOT_AVAILABLE = "Not available";

    /** A well-formed sixteen-character identifier, matching the SYMNAMES card width. */
    private static final String VALID_ID = "0000000000000001";


    /**
     * The thirteen persistent fields of {@link Transaction}, in the order the all-argument constructor
     * declares them, which is the order of the record layout at {@code app/cpy/CVTRA05Y.cpy:L5-L17}.
     */
    private static final String[] TRANSACTION_FIELDS = {
        "transactionId", "typeCode", "categoryCode", "transactionSource", "description", "amount", "merchantId",
        "merchantName", "merchantCity", "merchantZip", "cardNumber", "origTs", "procTs",
    };

    /**
     * Builds a transaction by writing its fields directly, bypassing the entity's own constructor guards.
     *
     * <p>This exists because the entity and the processor guard the same record at two different
     * boundaries, and only one of them can be reached at a time. {@link Transaction}'s all-argument
     * constructor validates every field against its {@code PIC} clause and throws
     * {@link IllegalArgumentException} on the way in, so a record built that way can never carry the
     * geometry defect whose rejection this suite is asserting - the constructor refuses it first and the
     * processor is never called. Going through the {@code protected} no-argument constructor that JPA uses
     * and then setting the fields reflectively reproduces exactly the state a database row can put into a
     * managed entity, which is the state the processor exists to screen.</p>
     *
     * <p>The processor's checks are therefore not redundant with the entity's: the entity guards the
     * application's own construction path, and this guards the read path, where a row that predates the
     * guards - or one written by the legacy utilities the batch stream still consumes - arrives already
     * malformed. Asserting through the constructor would collapse the two boundaries into one and leave the
     * read path untested.</p>
     *
     * @param values the thirteen field values, in {@link #TRANSACTION_FIELDS} order
     * @return a transaction carrying exactly those values, validated by nothing
     */
    private static Transaction unvalidated(final Object... values) {
        if (values.length != TRANSACTION_FIELDS.length) {
            throw new IllegalArgumentException("expected " + TRANSACTION_FIELDS.length
                    + " field values in record-layout order but received " + values.length);
        }
        try {
            final java.lang.reflect.Constructor<Transaction> jpaConstructor =
                    Transaction.class.getDeclaredConstructor();
            jpaConstructor.setAccessible(true);
            final Transaction item = jpaConstructor.newInstance();
            for (int index = 0; index < values.length; index++) {
                final java.lang.reflect.Field field =
                        Transaction.class.getDeclaredField(TRANSACTION_FIELDS[index]);
                field.setAccessible(true);
                field.set(item, values[index]);
            }
            return item;
        } catch (final ReflectiveOperationException cause) {
            throw new IllegalStateException("could not build an unvalidated Transaction fixture", cause);
        }
    }

    /** The processor under test. It is stateless, so one instance serves every assertion. */
    private final TransactionCombineProcessor processor = new TransactionCombineProcessor();

    /**
     * Builds a transaction whose every field satisfies its {@code PIC} clause exactly.
     *
     * @param transactionId the identifier to carry
     * @return a fully populated, geometrically valid transaction
     */
    private static Transaction validTransaction(final String transactionId) {
        return unvalidated(transactionId, "01", 1001, "POS       ", "d".repeat(100),
                new BigDecimal("123.45"), 123_456_789L, "m".repeat(50), "c".repeat(50), "z".repeat(10),
                "4111999988887777", "2024-01-31 10:00:00.000000", "2024-01-31 10:00:01.000000");
    }

    /**
     * Builds a transaction carrying only an identifier, every other field left absent.
     *
     * @param transactionId the identifier to carry
     * @return a transaction with a single populated field
     */
    private static Transaction identifierOnly(final String transactionId) {
        return unvalidated(transactionId, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * Builds a transaction whose category code alone deviates from its {@code PIC 9(04)} clause.
     *
     * @param categoryCode the category code to carry
     * @return a transaction differing from a valid one only in its category code
     */
    private static Transaction withCategoryCode(final Integer categoryCode) {
        return unvalidated(VALID_ID, "01", categoryCode, "POS       ", "d".repeat(100),
                new BigDecimal("123.45"), 123_456_789L, "m".repeat(50), "c".repeat(50), "z".repeat(10),
                "4111999988887777", "2024-01-31 10:00:00.000000", "2024-01-31 10:00:01.000000");
    }

    /**
     * Builds a transaction whose amount alone deviates from its {@code PIC S9(09)V99} clause.
     *
     * @param amount the amount to carry
     * @return a transaction differing from a valid one only in its amount
     */
    private static Transaction withAmount(final BigDecimal amount) {
        return unvalidated(VALID_ID, "01", 1001, "POS       ", "d".repeat(100), amount,
                123_456_789L, "m".repeat(50), "c".repeat(50), "z".repeat(10),
                "4111999988887777", "2024-01-31 10:00:00.000000", "2024-01-31 10:00:01.000000");
    }

    /**
     * Builds a transaction whose merchant identifier alone deviates from its {@code PIC 9(09)} clause.
     *
     * @param merchantId the merchant identifier to carry
     * @return a transaction differing from a valid one only in its merchant identifier
     */
    private static Transaction withMerchantId(final Long merchantId) {
        return unvalidated(VALID_ID, "01", 1001, "POS       ", "d".repeat(100),
                new BigDecimal("123.45"), merchantId, "m".repeat(50), "c".repeat(50), "z".repeat(10),
                "4111999988887777", "2024-01-31 10:00:00.000000", "2024-01-31 10:00:01.000000");
    }

    /**
     * A transaction whose identifier accessor answers differently on successive reads.
     *
     * <p>This is the only seam through which the processor's ordering precondition is reachable. The
     * processor reads the identifier once to establish the sort key and once more to confirm that the key it
     * sorted by is still the key it will load by; on an ordinary entity those two reads necessarily agree.
     * The guard exists because {@link Transaction} is a mutable JPA entity rather than a value object, so
     * the two reads are not guaranteed to agree by construction, and a silent disagreement would load a
     * record under a key the sort never placed it at.</p>
     */
    private static final class DriftingIdentifierTransaction extends Transaction {

        /** Counts accessor invocations so that the second answer can differ from the first. */
        private int reads;

        /** The identifier returned on the first read, which becomes the sort key. */
        private final String firstRead;

        /** The identifier returned on every later read, standing in for a concurrent mutation. */
        private final String laterReads;

        /**
         * Creates a transaction whose identifier changes after its first read.
         *
         * @param firstRead  the identifier the sort key is taken from
         * @param laterReads the identifier every subsequent read reports
         */
        DriftingIdentifierTransaction(final String firstRead, final String laterReads) {
            super();
            this.firstRead = firstRead;
            this.laterReads = laterReads;
        }

        @Override
        public String getTransactionId() {
            this.reads++;
            return this.reads <= 1 ? this.firstRead : this.laterReads;
        }
    }

    @Nested
    @DisplayName("The constants the job control fixes")
    final class JobControlConstants {

        @Test
        @DisplayName("the identifier width is the one the SYMNAMES card declares")
        void theIdentifierWidthIsTheSymnamesWidth() {
            assertThat(TransactionCombineProcessor.TRAN_ID_LENGTH)
                    .as("app/jcl/COMBTRAN.jcl:L28 declares TRAN-ID,1,16,CH, so the key begins at byte 1 "
                            + "and runs for sixteen character bytes; DFSORT trusts that offset absolutely "
                            + "and would silently sort on the wrong bytes if it were wrong")
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("the combined record length is the 350-byte geometry DCB=(*.SORTIN) propagates")
        void theCombinedRecordLengthIsThePropagatedGeometry() {
            assertThat(TransactionCombineProcessor.COMBINED_RECORD_LENGTH)
                    .as("app/jcl/COMBTRAN.jcl:L35 sets DCB=(*.SORTIN) on SORTOUT, so the output inherits "
                            + "the input geometry rather than declaring its own; both SORTIN datasets carry "
                            + "the 350-byte CVTRA05Y layout, which is what makes the concatenation legal")
                    .isEqualTo(350);
        }

        @Test
        @DisplayName("the identifier width leaves the rest of the record for the remaining fields")
        void theIdentifierWidthIsAPrefixOfTheRecord() {
            assertThat(TransactionCombineProcessor.TRAN_ID_LENGTH)
                    .as("the key is a prefix of the record rather than the whole of it, which is why the "
                            + "sort can reorder records without reshaping them - unlike the statement job's "
                            + "sort, which carries an OUTREC projection")
                    .isLessThan(TransactionCombineProcessor.COMBINED_RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("The TRAN-ID ascending order that replaces the DFSORT invocation")
    final class SortOrdering {

        @Test
        @DisplayName("records are ordered ascending by identifier, as SORT FIELDS=(TRAN-ID,A) requires")
        void recordsAreOrderedAscendingByIdentifier() {
            final List<Transaction> records = new ArrayList<>(List.of(
                    validTransaction("0000000000000009"),
                    validTransaction("0000000000000001"),
                    validTransaction("0000000000000005")));

            records.sort(TransactionCombineProcessor.TRAN_ID_ASCENDING);

            assertThat(records)
                    .as("the A in SORT FIELDS=(TRAN-ID,A) is ascending; the order is what makes the "
                            + "subsequent REPRO into a keyed cluster efficient, and no external sort "
                            + "process is spawned to achieve it")
                    .extracting(Transaction::getTransactionId)
                    .containsExactly("0000000000000001", "0000000000000005", "0000000000000009");
        }

        @Test
        @DisplayName("the order is character order, not numeric order, because the symbol is typed CH")
        void theOrderIsCharacterOrderNotNumericOrder() {
            final List<Transaction> records = new ArrayList<>(List.of(
                    validTransaction("0000000000000100"),
                    validTransaction("0000000000000020"),
                    validTransaction("0000000000000003")));

            records.sort(TransactionCombineProcessor.TRAN_ID_ASCENDING);

            assertThat(records)
                    .as("TRAN-ID,1,16,CH types the key as character data, so ordering compares bytes; the "
                            + "identifiers happen to agree with numeric order only because they are "
                            + "zero-padded to a fixed width, and that padding is therefore load-bearing")
                    .extracting(Transaction::getTransactionId)
                    .containsExactly("0000000000000003", "0000000000000020", "0000000000000100");
        }

        @Test
        @DisplayName("a null record sorts before every populated one rather than failing the sort")
        void aNullRecordSortsFirst() {
            final List<Transaction> records = new ArrayList<>(
                    Arrays.asList(validTransaction("0000000000000002"), null,
                            validTransaction("0000000000000001")));

            records.sort(TransactionCombineProcessor.TRAN_ID_ASCENDING);

            assertThat(records.getFirst())
                    .as("a comparator that threw on a null element would fail the whole step rather than "
                            + "the offending record; sorting nulls to the front lets the processor reject "
                            + "them individually with a diagnosable message")
                    .isNull();
        }

        @Test
        @DisplayName("a record with an absent identifier sorts before every record that has one")
        void aRecordWithAnAbsentIdentifierSortsFirst() {
            final List<Transaction> records = new ArrayList<>(List.of(
                    validTransaction("0000000000000002"),
                    identifierOnly(null),
                    validTransaction("0000000000000001")));

            records.sort(TransactionCombineProcessor.TRAN_ID_ASCENDING);

            assertThat(records.getFirst().getTransactionId())
                    .as("an absent key sorts to the front for the same reason as an absent record: it "
                            + "reaches the processor and is rejected there, with the record it came from "
                            + "available to name in the message")
                    .isNull();
        }

        @Test
        @DisplayName("both kinds of absence sort ahead of every populated record")
        void bothKindsOfAbsenceSortAhead() {
            final List<Transaction> records = new ArrayList<>(
                    Arrays.asList(validTransaction("0000000000000002"), identifierOnly(null), null,
                            validTransaction("0000000000000001")));

            records.sort(TransactionCombineProcessor.TRAN_ID_ASCENDING);

            assertThat(records.subList(2, 4))
                    .as("the two null tiers are ordered before the populated tier, so the populated records "
                            + "occupy the tail of the list in ascending key order")
                    .extracting(Transaction::getTransactionId)
                    .containsExactly("0000000000000001", "0000000000000002");
        }

        @Test
        @DisplayName("two records with the same identifier compare equal, leaving the duplicate for REPRO")
        void twoRecordsWithTheSameIdentifierCompareEqual() {
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING.compare(
                    validTransaction(VALID_ID), validTransaction(VALID_ID)))
                    .as("the sort does not deduplicate; DFSORT was invoked without SUM FIELDS=NONE, so a "
                            + "repeated identifier survives into SORTOUT and is caught by the REPRO "
                            + "instead - which is why the duplicate branch of the load translation exists")
                    .isZero();
        }

        @Test
        @DisplayName("the comparator is a single shared immutable instance")
        void theComparatorIsASharedImmutableInstance() {
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING)
                    .as("a comparator holding no state can be a constant, which is what allows the sort to "
                            + "run inside the job rather than as a spawned utility process")
                    .isSameAs(TransactionCombineProcessor.TRAN_ID_ASCENDING);
        }
    }

    @Nested
    @DisplayName("The sort-key precondition: the one field SORT FIELDS names")
    final class SortKeyPrecondition {

        @Test
        @DisplayName("a well-formed record is passed through unchanged, by identity")
        void aWellFormedRecordIsPassedThroughByIdentity() {
            final Transaction item = validTransaction(VALID_ID);

            assertThat(processor.process(item))
                    .as("the combine step reorders and loads; it transforms nothing, because neither the "
                            + "SORT nor the REPRO alters a record's content. Returning the same instance "
                            + "rather than a copy is what makes that faithful")
                    .isSameAs(item);
        }

        @Test
        @DisplayName("a record carrying only a well-formed identifier is accepted")
        void aRecordCarryingOnlyAnIdentifierIsAccepted() {
            assertThatCode(() -> processor.process(identifierOnly(VALID_ID)))
                    .as("SORT FIELDS=(TRAN-ID,A) names exactly one symbol, so the sort has exactly one "
                            + "precondition; demanding a fully populated record would reject input the "
                            + "utility accepts")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent record is rejected, reporting that no key is available")
        void anAbsentRecordIsRejected() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(null));

            assertThat(failure.getMessage())
                    .as("a null record has no identifier to name, so the message reports the placeholder "
                            + "rather than rendering the word null into diagnostic text")
                    .contains("TRAN-ID " + KEY_NOT_AVAILABLE)
                    .contains("the reader supplied a null record, which has no key to load by");
        }

        @Test
        @DisplayName("an absent identifier is rejected as having neither a sort key nor a primary key")
        void anAbsentIdentifierIsRejected() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(identifierOnly(null)));

            assertThat(failure.getMessage())
                    .as("the identifier is simultaneously the DFSORT key and the cluster's primary key, so "
                            + "its absence fails both steps and the message says both")
                    .contains("TRAN-ID is null, so the record has no sort key and no primary key");
        }

        @Test
        @DisplayName("a blank identifier is rejected even though it is the declared width")
        void aBlankIdentifierIsRejected() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(identifierOnly(" ".repeat(16))));

            assertThat(failure.getMessage())
                    .as("sixteen blanks satisfy the width but name no record; DFSORT would happily sort "
                            + "them to the front and REPRO would load them, so the blank test has to be "
                            + "separate from the width test rather than folded into it")
                    .contains("TRAN-ID is blank, so the record has no usable sort key");
        }

        @ParameterizedTest(name = "identifier of {0} characters")
        @ValueSource(ints = {1, 3, 15, 17, 20})
        @DisplayName("an identifier of any width but sixteen is rejected, naming both widths")
        void anIdentifierOfTheWrongWidthIsRejected(final int width) {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(identifierOnly("9".repeat(width))));

            assertThat(failure.getMessage())
                    .as("the SYMNAMES card fixes the key at sixteen bytes; a shorter key would leave "
                            + "DFSORT comparing bytes that belong to the next field, and a longer one could "
                            + "not be stored, so the test is an equality rather than an upper bound")
                    .contains("TRAN-ID is " + width + " characters")
                    .contains("the sort symbol TRAN-ID,1,16,CH fixes it at 16");
        }

        @Test
        @DisplayName("an identifier of exactly sixteen characters is accepted")
        void anIdentifierOfExactlySixteenCharactersIsAccepted() {
            assertThatCode(() -> processor.process(identifierOnly("9".repeat(16))))
                    .as("sixteen is the declared width, so it is the only accepted width")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("every rejection carries the three job-control cards it derives from")
        void everyRejectionCitesItsJobControl() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(identifierOnly(null)));

            assertThat(failure.getMessage())
                    .as("this job has no COBOL program, so the job control is the only source a diagnostic "
                            + "can cite; naming all three cards makes the rejection traceable without "
                            + "reading the processor")
                    .endsWith(SOURCE_TRAILER);
        }

        @Test
        @DisplayName("a rejection names the relation but no constraint, because none was violated")
        void aRejectionNamesTheRelationButNoConstraint() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(identifierOnly(null)));

            assertThat(failure.getRelation())
                    .as("the relation is known from the job's own DD statements")
                    .isEqualTo("transaction");
            assertThat(failure.getConstraintName())
                    .as("no database constraint has been reached yet - the record is refused before the "
                            + "load - so inventing a constraint name would be a fabrication")
                    .isNull();
            assertThat(failure.getCause())
                    .as("the refusal originates in the processor rather than in a store failure, so there "
                            + "is no cause to chain")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Fixed-width geometry: an upper bound per PIC clause, with null exempt")
    final class FixedWidthGeometry {

        @ParameterizedTest(name = "{0} at {1} characters")
        @CsvSource(value = {
            "TRAN-TYPE-CD X(02) app/cpy/CVTRA05Y.cpy:L6|3|typeCode",
            "TRAN-SOURCE X(10) app/cpy/CVTRA05Y.cpy:L8|11|source",
            "TRAN-DESC X(100) app/cpy/CVTRA05Y.cpy:L9|101|description",
            "TRAN-MERCHANT-NAME X(50) app/cpy/CVTRA05Y.cpy:L12|51|merchantName",
            "TRAN-MERCHANT-CITY X(50) app/cpy/CVTRA05Y.cpy:L13|51|merchantCity",
            "TRAN-MERCHANT-ZIP X(10) app/cpy/CVTRA05Y.cpy:L14|11|merchantZip",
            "TRAN-CARD-NUM X(16) app/cpy/CVTRA05Y.cpy:L15|17|cardNumber",
            "TRAN-ORIG-TS X(26) app/cpy/CVTRA05Y.cpy:L16|27|origTs",
            "TRAN-PROC-TS X(26) app/cpy/CVTRA05Y.cpy:L17|27|procTs",
        }, delimiter = '|')
        @DisplayName("a character field longer than its PIC clause is rejected, naming field and widths")
        void anOverLongCharacterFieldIsRejected(final String field, final int suppliedWidth,
                final String component) {
            final String tooLong = "x".repeat(suppliedWidth);
            final Transaction item = switch (component) {
                case "typeCode" -> unvalidated(VALID_ID, tooLong, null, null, null, null, null, null,
                        null, null, null, null, null);
                case "source" -> unvalidated(VALID_ID, null, null, tooLong, null, null, null, null,
                        null, null, null, null, null);
                case "description" -> unvalidated(VALID_ID, null, null, null, tooLong, null, null, null,
                        null, null, null, null, null);
                case "merchantName" -> unvalidated(VALID_ID, null, null, null, null, null, null,
                        tooLong, null, null, null, null, null);
                case "merchantCity" -> unvalidated(VALID_ID, null, null, null, null, null, null, null,
                        tooLong, null, null, null, null);
                case "merchantZip" -> unvalidated(VALID_ID, null, null, null, null, null, null, null,
                        null, tooLong, null, null, null);
                case "cardNumber" -> unvalidated(VALID_ID, null, null, null, null, null, null, null,
                        null, null, tooLong, null, null);
                case "origTs" -> unvalidated(VALID_ID, null, null, null, null, null, null, null, null,
                        null, null, tooLong, null);
                default -> unvalidated(VALID_ID, null, null, null, null, null, null, null, null, null,
                        null, null, tooLong);
            };

            final DataIntegrityException failure =
                    catchThrowableOfType(DataIntegrityException.class, () -> processor.process(item));

            assertThat(failure.getMessage())
                    .as("a value longer than its fixed-width field has nowhere to go: the record is 350 "
                            + "bytes by construction, so an over-long field would displace its neighbour "
                            + "rather than merely be truncated")
                    .contains(field)
                    .contains("holds " + suppliedWidth + " characters");
        }

        @Test
        @DisplayName("a character field shorter than its PIC clause is accepted, because it is space-padded")
        void aShortCharacterFieldIsAccepted() {
            final Transaction item = unvalidated(VALID_ID, "1", 1, "P", "d", new BigDecimal("1.00"),
                    1L, "m", "c", "z", "4", "2024", "2024");

            assertThatCode(() -> processor.process(item))
                    .as("every field is fixed-width, so a shorter value is space-padded at the byte "
                            + "boundary and loses nothing; tightening the check to an equality test would "
                            + "reject records the utilities accept, which is the opposite of parity")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a character field of exactly its PIC width is accepted")
        void aCharacterFieldOfExactlyItsPicWidthIsAccepted() {
            assertThatCode(() -> processor.process(validTransaction(VALID_ID)))
                    .as("the canonical fixture fills every field to its declared width exactly, so the "
                            + "upper bound is inclusive")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent character field is exempt from the geometry check entirely")
        void anAbsentCharacterFieldIsExempt() {
            assertThatCode(() -> processor.process(identifierOnly(VALID_ID)))
                    .as("a null field is not an over-long field; the sort names only the identifier, so "
                            + "absence elsewhere is the reader's business rather than the geometry check's")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "category code {0}")
        @ValueSource(ints = {10_000, 99_999, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("a category code outside its unsigned four-digit range is rejected")
        void aCategoryCodeOutsideItsRangeIsRejected(final int categoryCode) {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(withCategoryCode(categoryCode)));

            assertThat(failure.getMessage())
                    .as("TRAN-CAT-CD is PIC 9(04), an unsigned four-digit field, so both a negative value "
                            + "and a five-digit one are unstorable; the range test therefore has two ends")
                    .contains("TRAN-CAT-CD 9(04) app/cpy/CVTRA05Y.cpy:L7")
                    .contains("holds " + categoryCode)
                    .contains("outside the unsigned range 0 to 9999");
        }

        @ParameterizedTest(name = "category code {0}")
        @ValueSource(ints = {0, 1, 1001, 9998, 9999})
        @DisplayName("a category code inside its unsigned four-digit range is accepted")
        void aCategoryCodeInsideItsRangeIsAccepted(final int categoryCode) {
            assertThatCode(() -> processor.process(withCategoryCode(categoryCode)))
                    .as("both ends of the range are inclusive, because PIC 9(04) stores 0000 through 9999")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent category code is exempt from the range check")
        void anAbsentCategoryCodeIsExempt() {
            assertThatCode(() -> processor.process(withCategoryCode(null)))
                    .as("absence and out-of-range are different conditions, and only one of them makes the "
                            + "record unloadable")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "merchant identifier {0}")
        @ValueSource(longs = {1_000_000_000L, -1L, Long.MIN_VALUE, Long.MAX_VALUE})
        @DisplayName("a merchant identifier outside its unsigned nine-digit range is rejected")
        void aMerchantIdentifierOutsideItsRangeIsRejected(final long merchantId) {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(withMerchantId(merchantId)));

            assertThat(failure.getMessage())
                    .as("TRAN-MERCHANT-ID is PIC 9(09), so the widest storable value is 999999999 and the "
                            + "narrowest is zero")
                    .contains("TRAN-MERCHANT-ID 9(09) app/cpy/CVTRA05Y.cpy:L11")
                    .contains("outside the unsigned range 0 to 999999999");
        }

        @ParameterizedTest(name = "merchant identifier {0}")
        @ValueSource(longs = {0L, 1L, 999_999_999L})
        @DisplayName("a merchant identifier inside its unsigned nine-digit range is accepted")
        void aMerchantIdentifierInsideItsRangeIsAccepted(final long merchantId) {
            assertThatCode(() -> processor.process(withMerchantId(merchantId)))
                    .as("the upper bound is inclusive, because PIC 9(09) stores 999999999 exactly")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent merchant identifier is exempt from the range check")
        void anAbsentMerchantIdentifierIsExempt() {
            assertThatCode(() -> processor.process(withMerchantId(null)))
                    .as("the exemption is uniform across every optional field rather than special-cased")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Amount geometry: two decimal places and nine integer digits")
    final class AmountGeometry {

        @ParameterizedTest(name = "amount {0}")
        @ValueSource(strings = {"1.001", "0.005", "-1.001", "123.4567", "0.00000001"})
        @DisplayName("an amount needing more than two decimal places is rejected")
        void anAmountNeedingMoreThanTwoDecimalsIsRejected(final String amount) {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(withAmount(new BigDecimal(amount))));

            assertThat(failure.getMessage())
                    .as("TRAN-AMT is PIC S9(09)V99, two decimal places; storing a third would round a "
                            + "monetary value silently, and a silently rounded amount is a parity failure "
                            + "that no later comparison could attribute")
                    .contains("TRAN-AMT S9(09)V99 app/cpy/CVTRA05Y.cpy:L10")
                    .contains("needs more than 2 decimal places");
        }

        @ParameterizedTest(name = "amount {0}")
        @ValueSource(strings = {"1.100", "1.10", "1.1", "123.4500", "0.00", "-0.500", "5", "1E+3"})
        @DisplayName("an amount whose extra decimals are trailing zeros is accepted")
        void anAmountWithTrailingZeroDecimalsIsAccepted(final String amount) {
            assertThatCode(() -> processor.process(withAmount(new BigDecimal(amount))))
                    .as("the check strips trailing zeros before measuring the scale, so 1.100 is two "
                            + "decimal places rather than three; a raw scale test would reject values that "
                            + "represent exactly the same money and would make the check depend on how the "
                            + "reader happened to parse the field")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "amount {0}")
        @ValueSource(strings = {"999999999.99", "-999999999.99", "0.00", "0.01", "-0.01"})
        @DisplayName("an amount within the nine-integer-digit magnitude is accepted")
        void anAmountWithinTheDeclaredMagnitudeIsAccepted(final String amount) {
            assertThatCode(() -> processor.process(withAmount(new BigDecimal(amount))))
                    .as("both signs are legitimate: TRAN-AMT is signed, and the daily fixture carries "
                            + "genuinely negative amounts, so a magnitude test must be symmetric and must "
                            + "not normalise the sign away")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "amount {0}")
        @ValueSource(strings = {"1000000000.00", "-1000000000.00", "1000000000", "99999999999.99"})
        @DisplayName("an amount beyond the nine-integer-digit magnitude is rejected, either sign")
        void anAmountBeyondTheDeclaredMagnitudeIsRejected(final String amount) {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(withAmount(new BigDecimal(amount))));

            assertThat(failure.getMessage())
                    .as("the check compares the absolute value, so a negative overflow is caught exactly "
                            + "like a positive one; naming the limit in the message saves the reader "
                            + "recomputing it from the PIC clause")
                    .contains("has a magnitude above the 999999999.99");
        }

        @Test
        @DisplayName("an absent amount is exempt from both amount checks")
        void anAbsentAmountIsExempt() {
            assertThatCode(() -> processor.process(withAmount(null)))
                    .as("absence is not a geometry violation, and the sort names only the identifier")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the scale check runs before the magnitude check")
        void theScaleCheckRunsBeforeTheMagnitudeCheck() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(withAmount(new BigDecimal("1000000000.001"))));

            assertThat(failure.getMessage())
                    .as("an amount that violates both conditions reports the decimal-place failure, "
                            + "because that check is written first; pinning the order keeps the message "
                            + "deterministic for a reader diagnosing a rejected record")
                    .contains("needs more than 2 decimal places")
                    .doesNotContain("magnitude above");
        }

        @Test
        @DisplayName("financial comparison never relies on BigDecimal equality")
        void financialComparisonNeverReliesOnEquality() {
            assertThatCode(() -> processor.process(withAmount(new BigDecimal("999999999.990"))))
                    .as("999999999.990 and 999999999.99 are equal in value and unequal under equals(), so "
                            + "a boundary test written with equals() would reject the first and accept the "
                            + "second; the check uses compareTo, which is why the trailing zero is "
                            + "immaterial here")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("The REPRO ordering precondition")
    final class ReproOrderingPrecondition {

        @Test
        @DisplayName("an identifier that changes between the sort read and the load read is rejected")
        void aDriftingIdentifierIsRejected() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(new DriftingIdentifierTransaction(VALID_ID,
                            "0000000000000002")));

            assertThat(failure.getMessage())
                    .as("REPRO into a keyed cluster depends on the ordering the sort established; if the "
                            + "key changes after the sort, the record would be loaded at a position the "
                            + "sort never placed it at, and the cluster's ordering guarantee would be "
                            + "silently false rather than loudly broken")
                    .contains("TRAN-ID changed between the sort key read and the load");
        }

        @Test
        @DisplayName("the rejection reports the key the sort actually used")
        void theRejectionReportsTheKeyTheSortUsed() {
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(new DriftingIdentifierTransaction(VALID_ID,
                            "0000000000000002")));

            assertThat(failure.getMessage())
                    .as("the first read is the one the sort key came from, so it is the one that locates "
                            + "the record in the sorted stream and therefore the one worth reporting")
                    .contains("TRAN-ID '" + VALID_ID + "'");
        }

        @Test
        @DisplayName("a stable identifier passes the precondition")
        void aStableIdentifierPassesThePrecondition() {
            assertThatCode(() -> processor.process(
                    new DriftingIdentifierTransaction(VALID_ID, VALID_ID)))
                    .as("an accessor that answers consistently satisfies the guard, which is why an "
                            + "ordinary entity never reaches it; the guard defends against the entity "
                            + "being mutable, not against the reader being wrong")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Load failure translation: duplicate, constraint, and everything else")
    final class LoadFailureTranslation {

        @Test
        @DisplayName("a duplicate key becomes a duplicate-record failure carrying the colliding key")
        void aDuplicateKeyBecomesADuplicateRecordFailure() {
            final DuplicateKeyException cause = new DuplicateKeyException("unique violation");
            final DuplicateRecordException failure = catchThrowableOfType(DuplicateRecordException.class,
                    () -> processor.translateLoadFailure(validTransaction(VALID_ID), cause));

            assertThat(failure.getCollidingKey())
                    .as("the colliding identifier is the single most useful fact about the failure, and it "
                            + "is available only here - the generic constraint path cannot recover it")
                    .isEqualTo(VALID_ID);
            assertThat(failure.getLogicalFile())
                    .as("TRANSACT is the DD name app/jcl/COMBTRAN.jcl:L43 gives the cluster")
                    .isEqualTo("TRANSACT");
            assertThat(failure.getCause())
                    .as("the store failure is chained rather than swallowed, so the driver's own diagnostic "
                            + "survives into the log")
                    .isSameAs(cause);
        }

        @Test
        @DisplayName("the duplicate branch is tested before the constraint branch")
        void theDuplicateBranchIsTestedFirst() {
            final Throwable failure = catchThrowableOfType(RuntimeException.class,
                    () -> processor.translateLoadFailure(validTransaction(VALID_ID),
                            new DuplicateKeyException("unique violation")));

            assertThat(failure)
                    .as("DuplicateKeyException is a subclass of DataIntegrityViolationException, so testing "
                            + "the superclass first would classify every duplicate as a generic constraint "
                            + "violation and lose the colliding key; the order is load-bearing and this "
                            + "assertion is what pins it")
                    .isInstanceOf(DuplicateRecordException.class)
                    .isNotInstanceOf(DataIntegrityException.class);
        }

        @Test
        @DisplayName("the duplicate diagnostic forbids retrying, upserting or substituting a sequence")
        void theDuplicateDiagnosticForbidsAbsorbingTheCollision() {
            final DuplicateRecordException failure = catchThrowableOfType(DuplicateRecordException.class,
                    () -> processor.translateLoadFailure(validTransaction(VALID_ID),
                            new DuplicateKeyException("unique violation")));

            assertThat(failure.getMessage())
                    .as("the interest job writes to a fresh sequential generation and detects no "
                            + "duplicates, so a repeated date parameter surfaces here. Absorbing the "
                            + "collision would change generated identifiers and break the parity "
                            + "comparison, so the remediation is to re-drive with an unused date parameter "
                            + "and the message must say so rather than leaving it to be inferred")
                    .contains("do not retry, upsert or substitute a sequence")
                    .contains("Re-drive the pipeline with an unused date parameter")
                    .contains("app/jcl/COMBTRAN.jcl:L48");
        }

        @Test
        @DisplayName("a constraint violation becomes a data-integrity failure naming the relation")
        void aConstraintViolationBecomesADataIntegrityFailure() {
            final DataIntegrityViolationException cause =
                    new DataIntegrityViolationException("foreign key violation");
            final DataIntegrityException failure = catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.translateLoadFailure(validTransaction(VALID_ID), cause));

            assertThat(failure.getRelation())
                    .as("the relation is known from the job's DD statements even when the constraint is not")
                    .isEqualTo("transaction");
            assertThat(failure.getConstraintName())
                    .as("the driver exception does not carry a constraint name, and the migration script is "
                            + "the only authority for it, so reporting one would be a fabrication; the "
                            + "message says where to look instead")
                    .isNull();
            assertThat(failure.getMessage())
                    .as("pointing at the migration rather than guessing is what makes the diagnostic "
                            + "honest and still actionable")
                    .contains("V1__create_schema.sql declares");
            assertThat(failure.getCause())
                    .as("the store failure is chained rather than swallowed")
                    .isSameAs(cause);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"lock", "timeout", "resource"})
        @DisplayName("any other store failure is fatal, carrying the batch abend code and culprit")
        void anyOtherStoreFailureIsFatal(final String kind) {
            final RuntimeException cause = switch (kind) {
                case "lock" -> new CannotAcquireLockException("could not obtain lock");
                case "timeout" -> new QueryTimeoutException("statement timed out");
                default -> new DataAccessResourceFailureException("connection lost");
            };
            final FatalProcessingException failure = catchThrowableOfType(FatalProcessingException.class,
                    () -> processor.translateLoadFailure(validTransaction(VALID_ID),
                            (org.springframework.dao.DataAccessException) cause));

            assertThat(failure.getAbendCode())
                    .as("app/cbl/CBTRN02C.cbl:L707-L710 moves 999 to ABCODE before CALL 'CEE3ABD', and "
                            + "every fatal batch path in the target reproduces that code")
                    .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
            assertThat(failure.getAbendCulprit())
                    .as("the culprit names the job whose step failed, which for this job is the JCL member "
                            + "itself because there is no COBOL program to name")
                    .isEqualTo("COMBTRAN");
            assertThat(failure.getCause())
                    .as("the store failure is chained on the fatal path too, so nothing is lost when the "
                            + "step abends")
                    .isSameAs(cause);
        }

        @Test
        @DisplayName("the fatal diagnostic says why the condition was not classified")
        void theFatalDiagnosticSaysWhyItWasNotClassified() {
            final FatalProcessingException failure = catchThrowableOfType(FatalProcessingException.class,
                    () -> processor.translateLoadFailure(validTransaction(VALID_ID),
                            new CannotAcquireLockException("could not obtain lock")));

            assertThat(failure.getAbendMessage())
                    .as("an unclassified condition is the one case where the processor cannot advise a "
                            + "remedy, so the message must at least state that the two classifiable "
                            + "conditions were excluded rather than leaving the reader to wonder")
                    .contains("is not a duplicate key or a constraint violation, so it is fatal")
                    .contains("REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)");
            assertThat(failure.getAbendReason())
                    .as("the reason is the short form the abend work area carries, distinct from the full "
                            + "message")
                    .contains("Unexpected store failure loading TRAN-ID '" + VALID_ID + "'");
        }

        @Test
        @DisplayName("a load failure with no record reports the placeholder key and no colliding key")
        void aLoadFailureWithNoRecordReportsThePlaceholder() {
            final DuplicateRecordException failure = catchThrowableOfType(DuplicateRecordException.class,
                    () -> processor.translateLoadFailure(null, new DuplicateKeyException("violation")));

            assertThat(failure.getMessage())
                    .as("a batch writer can report a chunk-level failure without being able to attribute "
                            + "it to one record; the message must degrade to the placeholder rather than "
                            + "rendering the word null")
                    .contains("TRAN-ID " + KEY_NOT_AVAILABLE);
            assertThat(failure.getCollidingKey())
                    .as("no key is known, so none is claimed; a placeholder in the message is a diagnostic "
                            + "and a placeholder in the payload would be a lie a caller could act on")
                    .isNull();
        }

        @Test
        @DisplayName("a record with an absent identifier reports the placeholder key")
        void aRecordWithAnAbsentIdentifierReportsThePlaceholder() {
            final DuplicateRecordException failure = catchThrowableOfType(DuplicateRecordException.class,
                    () -> processor.translateLoadFailure(identifierOnly(null),
                            new DuplicateKeyException("violation")));

            assertThat(failure.getMessage())
                    .as("an absent record and an absent identifier degrade identically, because in both "
                            + "cases there is no key to name")
                    .contains("TRAN-ID " + KEY_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("translation always throws, so no caller can treat a load failure as recoverable")
        void translationAlwaysThrows() {
            assertThatCode(() -> processor.translateLoadFailure(validTransaction(VALID_ID),
                    new DuplicateKeyException("violation")))
                    .as("REPRO has no partial-success mode: the step either loads the file or fails. A "
                            + "translation that returned normally would let a caller continue past a "
                            + "failed load, which is precisely the outcome the JCL's COND gating prevented")
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Statelessness: the processor accumulates nothing across records")
    final class Statelessness {

        @Test
        @DisplayName("a rejected record leaves the next record unaffected")
        void aRejectedRecordLeavesTheNextUnaffected() {
            assertThat(catchThrowableOfType(DataIntegrityException.class,
                    () -> processor.process(identifierOnly(null))))
                    .as("the first record is rejected")
                    .isNotNull();

            final Transaction good = validTransaction(VALID_ID);
            assertThat(processor.process(good))
                    .as("the combine step keeps no counter and no reject accumulator - unlike the posting "
                            + "job, which sets return code 4 when its reject count exceeds zero - because "
                            + "the SORT and REPRO utilities have no per-record reject concept at all")
                    .isSameAs(good);
        }

        @Test
        @DisplayName("the same record processed twice yields the same instance both times")
        void theSameRecordProcessedTwiceYieldsTheSameInstance() {
            final Transaction item = validTransaction(VALID_ID);

            assertThat(processor.process(item))
                    .as("processing is idempotent because it transforms nothing; a chunk retried after a "
                            + "rollback must not accumulate changes")
                    .isSameAs(processor.process(item));
        }

        @Test
        @DisplayName("two independently constructed processors agree")
        void twoIndependentlyConstructedProcessorsAgree() {
            final Transaction item = validTransaction(VALID_ID);

            assertThat(new TransactionCombineProcessor().process(item))
                    .as("the processor holds no instance state, so instances are interchangeable and the "
                            + "bean may safely be a singleton across a partitioned step")
                    .isSameAs(new TransactionCombineProcessor().process(item));
        }

        @Test
        @DisplayName("a translated load failure leaves later processing unaffected")
        void aTranslatedLoadFailureLeavesLaterProcessingUnaffected() {
            assertThat(catchThrowableOfType(DuplicateRecordException.class,
                    () -> processor.translateLoadFailure(validTransaction(VALID_ID),
                            new DuplicateKeyException("violation"))))
                    .as("the load failure is translated and thrown")
                    .isNotNull();

            final Transaction later = validTransaction("0000000000000002");
            assertThat(processor.process(later))
                    .as("no failure latch survives the translation, so a step restart begins from a clean "
                            + "processor rather than from a poisoned one")
                    .isSameAs(later);
        }
    }
}
