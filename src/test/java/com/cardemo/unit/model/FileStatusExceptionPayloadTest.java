/*
 * ******************************************************************
 * Program     : FileStatusExceptionPayloadTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves the payload contract of the five FILE STATUS
 *               translations - RecordNotFound '23', DuplicateRecord '22',
 *               FileUnavailable '35', FileAccess '9x' and DataIntegrity -
 *               and pins the THREE genuinely different string
 *               normalisation policies they apply, side by side, so that
 *               no policy is ever inferred from a sibling type.
 * Source      : app/cbl/CBTRN02C.cbl:L142-L144 (the universal guard) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L714-L731 (9910-DISPLAY-IO-STATUS) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L467-L500 (2700-UPDATE-TCATBAL upsert) @ 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.enums.FileStatus;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the payload contracts of the five {@code FILE STATUS} translations.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>These five types are deliberately tested in one class rather than five, because the single most
 * error-prone thing about them is that <strong>they do not share a normalisation policy</strong>. Reading any
 * one of them in isolation invites the assumption that its siblings behave the same way. They do not. The
 * three policies actually in use are:
 *
 * <table border="1">
 *   <caption>String normalisation policy by type - all three verified against the source</caption>
 *   <tr><th>Type</th><th>null or blank input</th><th>surrounding whitespace</th></tr>
 *   <tr><td>{@link FileUnavailableException}</td><td>becomes {@code null}</td><td><strong>stripped</strong></td></tr>
 *   <tr><td>{@link RecordNotFoundException}</td><td>becomes {@code null}</td><td><strong>preserved</strong></td></tr>
 *   <tr><td>{@link DuplicateRecordException}</td><td>stored as given</td><td>preserved</td></tr>
 *   <tr><td>{@link DataIntegrityException}</td><td>stored as given</td><td>preserved</td></tr>
 *   <tr><td>{@link FileAccessException}</td><td>delegates to {@link FileStatus#renderIoStatus04}</td><td>n/a</td></tr>
 * </table>
 *
 * <p>Every one of those cells is asserted below, and the comparative section asserts the differences
 * <em>between</em> types directly, so that harmonising them later is a deliberate, visible act rather than a
 * silent one.
 *
 * <p>The second substantive contract here belongs to {@link FileAccessException}. It does not store the raw
 * two-character status; it stores the four-character rendering produced by {@code 9910-DISPLAY-IO-STATUS}
 * ({@code app/cbl/CBTRN02C.cbl:L714-L731}). That routine copies the first byte through and expands the second
 * byte from a binary field into three digits when the status is non-numeric or begins with {@code '9'},
 * otherwise emitting four zeros with the two status characters at positions three and four. A status of
 * {@code "92"} therefore renders as {@code "9050"} - {@code '9'} then the decimal value of the character
 * {@code '2'}, which is 50 - and <strong>not</strong> as {@code "9002"}. That rendering is a contract because
 * the end-to-end gate compares log output against the legacy baseline.
 *
 * <p>A consequence worth stating: because the message-only constructor delegates through the five-argument
 * form with a {@code null} status, {@code new FileAccessException("...").getExpandedStatus()} is
 * <strong>not</strong> {@code null} - it is {@code " 032"}, the rendering of an absent status. That is
 * surprising enough to be asserted explicitly.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=FileStatusExceptionPayloadTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. The rendering constants come from {@link FileStatus}, whose own behaviour is covered by
 * {@code FileStatusTest}; this class asserts only that {@link FileAccessException} <em>delegates</em> to it,
 * plus a small number of concrete literals so the delegation assertion has teeth.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A comparative assertion fails.</strong> Two types were harmonised onto one policy. That may
 *       well be an improvement, but it is a behaviour change to the diagnostic payload and must be a
 *       deliberate decision, not a side effect.</li>
 *   <li><strong>The {@code "9050"} assertion fails.</strong> The expansion was "corrected" to treat the
 *       second byte as a digit. Read {@code L714-L731}: it is a binary field expanded with a three-digit
 *       edit, and the baseline comparison depends on it.</li>
 *   <li><strong>The {@code " 032"} assertion fails.</strong> The delegation for an absent status changed.
 *       Check whether the constructor now short-circuits on {@code null} instead of rendering.</li>
 *   </ul>
 */
class FileStatusExceptionPayloadTest {

    private static final String MESSAGE = "unable to read the account record";

    @Nested
    @DisplayName("RecordNotFoundException - FILE STATUS '23', blank becomes null but whitespace survives")
    class RecordNotFound {

        @Test
        @DisplayName("the message-only constructor leaves both payload elements empty Optionals")
        void messageOnlyConstructorLeavesBothEmpty() {
            final RecordNotFoundException thrown = new RecordNotFoundException(MESSAGE);

            assertThat(thrown.recordType())
                    .as("an empty Optional says 'the thrower did not name a record type', which a handler "
                            + "must distinguish from a type that happens to be blank")
                    .isEmpty();
            assertThat(thrown.recordKey()).isEmpty();
            assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the message-and-cause constructor leaves both empty and preserves the cause")
        void messageAndCauseConstructorPreservesTheCause() {
            final IOException cause = new IOException("VSAM invalid key");
            final RecordNotFoundException thrown = new RecordNotFoundException(MESSAGE, cause);

            assertThat(thrown.recordType()).isEmpty();
            assertThat(thrown.recordKey()).isEmpty();
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the three-argument constructor records the record type and key")
        void threeArgumentConstructorRecordsTypeAndKey() {
            final RecordNotFoundException thrown =
                    new RecordNotFoundException(MESSAGE, "ACCTDAT", "00000000001");

            assertThat(thrown.recordType())
                    .as("naming the logical file is what makes the diagnostic actionable: the posting job "
                            + "reads six datasets and 'not found' alone does not say which")
                    .contains("ACCTDAT");
            assertThat(thrown.recordKey())
                    .as("the 11-character account key at app/catlg/LISTCAT.txt:L59 identifies the record")
                    .contains("00000000001");
        }

        @Test
        @DisplayName("the four-argument constructor records type, key and cause together")
        void fourArgumentConstructorRecordsEverything() {
            final IOException cause = new IOException("status 23");
            final RecordNotFoundException thrown =
                    new RecordNotFoundException(MESSAGE, "CUSTDAT", "000000009", cause);

            assertThat(thrown.recordType()).contains("CUSTDAT");
            assertThat(thrown.recordKey()).contains("000000009");
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "     ", "\t"})
        @DisplayName("a blank type or key becomes an empty Optional rather than a blank present value")
        void blankBecomesEmpty(final String blank) {
            final RecordNotFoundException thrown = new RecordNotFoundException(MESSAGE, blank, blank);

            assertThat(thrown.recordType())
                    .as("a COBOL key field that arrived as spaces carries no information, so reporting it "
                            + "as present would make a renderer print nothing where it promised a value")
                    .isEmpty();
            assertThat(thrown.recordKey()).isEmpty();
        }

        @Test
        @DisplayName("a null type or key becomes an empty Optional without throwing")
        void nullBecomesEmpty() {
            final RecordNotFoundException thrown = new RecordNotFoundException(MESSAGE, null, null);

            assertThat(thrown.recordType()).isEmpty();
            assertThat(thrown.recordKey()).isEmpty();
        }

        @Test
        @DisplayName("surrounding whitespace is PRESERVED, because this type converts blank but does not strip")
        void surroundingWhitespaceIsPreserved() {
            final RecordNotFoundException thrown =
                    new RecordNotFoundException(MESSAGE, "  ACCTDAT ", " 00000000001 ");

            assertThat(thrown.recordType())
                    .as("observed behaviour, pinned deliberately: blankToNull tests isBlank() and returns "
                            + "the ORIGINAL value when it is not blank - no strip is applied. "
                            + "FileUnavailableException, by contrast, does strip. The two are different "
                            + "policies and this assertion is what makes that visible")
                    .contains("  ACCTDAT ");
            assertThat(thrown.recordKey()).contains(" 00000000001 ");
        }

        @Test
        @DisplayName("the accessors are Optional-returning, so no null can escape to a caller")
        void theAccessorsAreOptionalReturning() throws ReflectiveOperationException {
            assertThat(RecordNotFoundException.class.getMethod("recordType").getReturnType())
                    .as("an Optional accessor makes the absent case impossible to ignore, which is why "
                            + "this type and FileUnavailableException use it while the other three expose "
                            + "plain getters")
                    .isEqualTo(java.util.Optional.class);
            assertThat(RecordNotFoundException.class.getMethod("recordKey").getReturnType())
                    .isEqualTo(java.util.Optional.class);
        }

        @Test
        @DisplayName("it models the '23' status, which is an error EXCEPT at the two accepted upsert sites")
        void itModelsTheAcceptedNotFoundStatus() {
            final RecordNotFoundException thrown =
                    new RecordNotFoundException("TCATBAL record not found", "TCATBALF", "00000000001010005");

            assertThat(thrown.recordKey())
                    .as("the composite key is 11 + 2 + 4 = 17 characters per app/catlg/LISTCAT.txt; note "
                            + "that at app/cbl/CBTRN02C.cbl:L467-L500 a '23' on this very file is an "
                            + "ACCEPTED create path and must NOT be thrown - which is why the status "
                            + "mapper, not this type, owns that decision")
                    .contains("00000000001010005")
                    .get()
                    .asString()
                    .hasSize(17);
        }
    }

    @Nested
    @DisplayName("DuplicateRecordException - FILE STATUS '22', no normalisation at all")
    class DuplicateRecord {

        @Test
        @DisplayName("the message-only constructor delegates and leaves both payload elements null")
        void messageOnlyConstructorLeavesBothNull() {
            final DuplicateRecordException thrown = new DuplicateRecordException(MESSAGE);

            assertThat(thrown.getLogicalFile()).isNull();
            assertThat(thrown.getCollidingKey()).isNull();
            assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the message-and-cause constructor delegates and preserves the cause")
        void messageAndCauseConstructorPreservesTheCause() {
            final IOException cause = new IOException("primary key violation");
            final DuplicateRecordException thrown = new DuplicateRecordException(MESSAGE, cause);

            assertThat(thrown.getLogicalFile()).isNull();
            assertThat(thrown.getCollidingKey()).isNull();
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the three-argument constructor records the logical file and the colliding key")
        void threeArgumentConstructorRecordsFileAndKey() {
            final DuplicateRecordException thrown =
                    new DuplicateRecordException(MESSAGE, "TRANSACT", "0000000000000001");

            assertThat(thrown.getLogicalFile()).isEqualTo("TRANSACT");
            assertThat(thrown.getCollidingKey())
                    .as("the 16-character transaction identifier is what collides when the "
                            + "descending-browse generator races, so it is the one value an operator needs")
                    .isEqualTo("0000000000000001")
                    .hasSize(16);
        }

        @Test
        @DisplayName("the four-argument constructor records file, key and cause together")
        void fourArgumentConstructorRecordsEverything() {
            final IOException cause = new IOException("status 22");
            final DuplicateRecordException thrown =
                    new DuplicateRecordException(MESSAGE, "TRANSACT", "2024010100000001", cause);

            assertThat(thrown.getLogicalFile()).isEqualTo("TRANSACT");
            assertThat(thrown.getCollidingKey()).isEqualTo("2024010100000001");
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   ", "  TRANSACT  "})
        @DisplayName("values are stored EXACTLY as supplied, with no blank-to-null and no strip")
        void valuesAreStoredExactlyAsSupplied(final String raw) {
            final DuplicateRecordException thrown = new DuplicateRecordException(MESSAGE, raw, raw);

            assertThat(thrown.getLogicalFile())
                    .as("observed behaviour, pinned deliberately: this type applies NO normalisation - "
                            + "neither the blank-to-null of RecordNotFoundException nor the strip of "
                            + "FileUnavailableException. A blank stays a blank and is reported as present")
                    .isEqualTo(raw)
                    .isNotNull();
            assertThat(thrown.getCollidingKey()).isEqualTo(raw).isNotNull();
        }

        @Test
        @DisplayName("this is the type a repeated interest run must raise, never a silent upsert")
        void itIsTheTypeARepeatedInterestRunMustRaise() {
            final DuplicateRecordException thrown = new DuplicateRecordException(
                    "duplicate transaction identifier during combine load",
                    "TRANSACT",
                    "2024010100000042");

            assertThat(thrown.getCollidingKey())
                    .as("the interest job concatenates its ten-character date parameter with a six-digit "
                            + "suffix, so re-running it with the same date produces colliding identifiers "
                            + "in the combine load step; that must surface as a duplicate and a failed "
                            + "exit status, never as an overwrite")
                    .startsWith("2024010100")
                    .hasSize(16);
        }
    }

    @Nested
    @DisplayName("FileUnavailableException - FILE STATUS '35', blank becomes null AND whitespace strips")
    class FileUnavailable {

        @Test
        @DisplayName("the message-only constructor leaves the resource name absent")
        void messageOnlyConstructorLeavesTheResourceAbsent() {
            final FileUnavailableException thrown = new FileUnavailableException("ACCTDAT is not open");

            assertThat(thrown.resourceName()).isEmpty();
            assertThat(thrown.getMessage()).isEqualTo("ACCTDAT is not open");
        }

        @Test
        @DisplayName("the message-and-cause constructor leaves the resource absent and keeps the cause")
        void messageAndCauseConstructorKeepsTheCause() {
            final IOException cause = new IOException("dataset not allocated");
            final FileUnavailableException thrown = new FileUnavailableException(MESSAGE, cause);

            assertThat(thrown.resourceName()).isEmpty();
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the three-argument constructor records the resource name alongside the cause")
        void threeArgumentConstructorRecordsTheResource() {
            final IOException cause = new IOException("status 35");
            final FileUnavailableException thrown =
                    new FileUnavailableException(MESSAGE, "TCATBALF", cause);

            assertThat(thrown.resourceName())
                    .as("TCATBALF is one of the four batch-only datasets with no CICS definition, so "
                            + "naming it tells an operator immediately that this is a batch-path failure")
                    .contains("TCATBALF");
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("there is deliberately NO (message, resource) constructor without a cause")
        void thereIsNoTwoStringConstructor() {
            assertThat(FileUnavailableException.class.getDeclaredConstructors())
                    .as("observed shape, pinned deliberately: this type offers three constructors, not the "
                            + "four its siblings offer - a resource name may only be supplied together "
                            + "with a cause. Adding a (String, String) overload would be ambiguous "
                            + "against (String, Throwable) at every null call site")
                    .hasSize(3);
        }

        @Test
        @DisplayName("surrounding whitespace IS stripped, unlike every other type in this package")
        void surroundingWhitespaceIsStripped() {
            final FileUnavailableException thrown =
                    new FileUnavailableException(MESSAGE, "   TCATBALF   ", null);

            assertThat(thrown.resourceName())
                    .as("observed behaviour, pinned deliberately: normaliseResourceName calls strip(), "
                            + "which RecordNotFoundException's blankToNull does not. A DD name arrives "
                            + "space-padded to its eight-character field width, so stripping is what makes "
                            + "it usable as a map key or a log token")
                    .contains("TCATBALF");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "      ", "\t", "\n"})
        @DisplayName("a blank resource name becomes an empty Optional")
        void aBlankResourceNameBecomesEmpty(final String blank) {
            final FileUnavailableException thrown = new FileUnavailableException(MESSAGE, blank, null);

            assertThat(thrown.resourceName()).isEmpty();
        }

        @Test
        @DisplayName("a null resource name becomes an empty Optional without throwing")
        void aNullResourceNameBecomesEmpty() {
            final FileUnavailableException thrown = new FileUnavailableException(MESSAGE, null, null);

            assertThat(thrown.resourceName()).isEmpty();
            assertThat(thrown.getCause()).isNull();
        }

        @Test
        @DisplayName("it models the condition the legacy OPENFIL and CLOSEFIL jobs existed to prevent")
        void itModelsTheFileAvailabilityCondition() {
            final FileUnavailableException thrown =
                    new FileUnavailableException("USRSEC unavailable", "USRSEC", null);

            assertThat(thrown.resourceName())
                    .as("app/jcl/OPENFIL.jcl and CLOSEFIL.jcl made datasets available to the online "
                            + "region; their Java counterpart is a health indicator, and this exception is "
                            + "what a request path raises when that readiness does not hold")
                    .contains("USRSEC");
        }
    }

    @Nested
    @DisplayName("FileAccessException - the '9x' family, carrying the four-character expanded status")
    class FileAccess {

        @ParameterizedTest
        @CsvSource({
            "92, 9050",
            "97, 9055",
            "9A, 9065",
            "00, 0000",
            "10, 0010",
            "23, 0023",
            "35, 0035",
        })
        @DisplayName("the status is expanded per 9910-DISPLAY-IO-STATUS, not merely zero-padded")
        void theStatusIsExpandedPerTheLegacyRenderer(final String ioStatus, final String expected) {
            final FileAccessException thrown =
                    new FileAccessException(MESSAGE, ioStatus, "TRANSACT", "READ");

            assertThat(thrown.getExpandedStatus())
                    .as("app/cbl/CBTRN02C.cbl:L714-L731 copies the first byte through and expands the "
                            + "SECOND byte from a binary field into three digits when the status is "
                            + "non-numeric or starts with '9'. Status '%s' therefore renders as '%s' - "
                            + "note '92' becomes 9050 because the character '2' has the decimal value 50, "
                            + "NOT 9002. The end-to-end gate compares this rendering against the legacy "
                            + "baseline", ioStatus, expected)
                    .isEqualTo(expected)
                    .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
        }

        @Test
        @DisplayName("the rendering delegates to FileStatus rather than duplicating the algorithm")
        void theRenderingDelegatesToFileStatus() {
            final String ioStatus = "93";
            final FileAccessException thrown =
                    new FileAccessException(MESSAGE, ioStatus, "ACCTDAT", "REWRITE");

            assertThat(thrown.getExpandedStatus())
                    .as("the four-character rendering exists in exactly one place, FileStatus, so that the "
                            + "log renderer and this exception can never disagree about it")
                    .isEqualTo(FileStatus.renderIoStatus04(ioStatus));
        }

        @Test
        @DisplayName("a message-only construction still yields a rendered status, NOT null")
        void aMessageOnlyConstructionStillYieldsARenderedStatus() {
            final FileAccessException thrown = new FileAccessException(MESSAGE);

            assertThat(thrown.getExpandedStatus())
                    .as("observed behaviour, pinned deliberately because it is surprising: the "
                            + "message-only constructor delegates through the five-argument form with a "
                            + "null status, and renderIoStatus04(null) yields ' 032' - a space followed by "
                            + "the three-digit expansion of a space, whose decimal value is 32. It is NOT "
                            + "null, so a caller must not use null-ness to test whether a status was "
                            + "supplied")
                    .isEqualTo(" 032")
                    .isNotNull()
                    .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
            assertThat(thrown.getLogicalFileName()).isNull();
            assertThat(thrown.getOperation()).isNull();
        }

        @Test
        @DisplayName("a message-and-cause construction also renders the absent status and keeps the cause")
        void aMessageAndCauseConstructionRendersAndKeepsTheCause() {
            final IOException cause = new IOException("physical read error");
            final FileAccessException thrown = new FileAccessException(MESSAGE, cause);

            assertThat(thrown.getExpandedStatus()).isEqualTo(" 032");
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("an explicitly null status renders identically to an omitted one")
        void anExplicitlyNullStatusRendersIdentically() {
            final FileAccessException omitted = new FileAccessException(MESSAGE);
            final FileAccessException explicit =
                    new FileAccessException(MESSAGE, null, "ACCTDAT", "OPEN");

            assertThat(explicit.getExpandedStatus())
                    .as("both routes reach the same renderer with the same argument, so they must agree; a "
                            + "divergence would mean one path short-circuits")
                    .isEqualTo(omitted.getExpandedStatus())
                    .isEqualTo(" 032");
            assertThat(explicit.getLogicalFileName()).isEqualTo("ACCTDAT");
        }

        @Test
        @DisplayName("the four-argument constructor records status, file and operation with no cause")
        void fourArgumentConstructorRecordsTheTriple() {
            final FileAccessException thrown =
                    new FileAccessException(MESSAGE, "92", "DALYTRAN", "READ");

            assertThat(thrown.getExpandedStatus()).isEqualTo("9050");
            assertThat(thrown.getLogicalFileName())
                    .as("the DD name is what an operator matches against the JCL to find the failing step")
                    .isEqualTo("DALYTRAN");
            assertThat(thrown.getOperation())
                    .as("the verb distinguishes an OPEN failure from a READ failure on the same dataset, "
                            + "which are different operational problems")
                    .isEqualTo("READ");
            assertThat(thrown.getCause()).isNull();
        }

        @Test
        @DisplayName("the five-argument constructor records the triple and preserves the cause")
        void fiveArgumentConstructorPreservesTheCause() {
            final IOException cause = new IOException("channel failure");
            final FileAccessException thrown =
                    new FileAccessException(MESSAGE, "97", "TCATBALF", "REWRITE", cause);

            assertThat(thrown.getExpandedStatus()).isEqualTo("9055");
            assertThat(thrown.getLogicalFileName()).isEqualTo("TCATBALF");
            assertThat(thrown.getOperation()).isEqualTo("REWRITE");
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the file name and operation are stored raw, with no normalisation")
        void theFileNameAndOperationAreStoredRaw() {
            final FileAccessException thrown =
                    new FileAccessException(MESSAGE, "92", "  ACCTDAT  ", "  READ  ");

            assertThat(thrown.getLogicalFileName())
                    .as("observed behaviour: only the status is transformed; the other two are stored "
                            + "exactly as supplied, matching DuplicateRecordException's policy rather than "
                            + "FileUnavailableException's")
                    .isEqualTo("  ACCTDAT  ");
            assertThat(thrown.getOperation()).isEqualTo("  READ  ");
        }

        @Test
        @DisplayName("the rendered status always occupies exactly four characters, whatever the input")
        void theRenderedStatusIsAlwaysFourCharacters() {
            assertThat(new FileAccessException(MESSAGE, "9", "F", "OPEN").getExpandedStatus())
                    .as("the legacy field is PIC X(4) and the display is built with a three-digit edit, so "
                            + "a short or malformed status still yields four characters rather than a "
                            + "ragged field the baseline comparison would reject")
                    .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
            assertThat(new FileAccessException(MESSAGE, "999", "F", "OPEN").getExpandedStatus())
                    .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
        }
    }

    @Nested
    @DisplayName("DataIntegrityException - the referential failures across the ten foreign keys")
    class DataIntegrity {

        @Test
        @DisplayName("the message-only constructor leaves both payload elements null")
        void messageOnlyConstructorLeavesBothNull() {
            final DataIntegrityException thrown = new DataIntegrityException(MESSAGE);

            assertThat(thrown.getConstraintName()).isNull();
            assertThat(thrown.getRelation()).isNull();
            assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the message-and-cause constructor leaves both null and preserves the cause")
        void messageAndCauseConstructorPreservesTheCause() {
            final IOException cause = new IOException("foreign key violation");
            final DataIntegrityException thrown = new DataIntegrityException(MESSAGE, cause);

            assertThat(thrown.getConstraintName()).isNull();
            assertThat(thrown.getRelation()).isNull();
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the three-argument constructor records the constraint name and the relation")
        void threeArgumentConstructorRecordsConstraintAndRelation() {
            final DataIntegrityException thrown = new DataIntegrityException(
                    "card references a missing account", "fk_card_account", "card");

            assertThat(thrown.getConstraintName())
                    .as("the constraint name is what appears in the driver's own error, so carrying it "
                            + "makes the Java diagnostic joinable to the database log")
                    .isEqualTo("fk_card_account");
            assertThat(thrown.getRelation()).isEqualTo("card");
        }

        @Test
        @DisplayName("the four-argument constructor records constraint, relation and cause together")
        void fourArgumentConstructorRecordsEverything() {
            final IOException cause = new IOException("23503");
            final DataIntegrityException thrown = new DataIntegrityException(
                    "transaction references a missing card", "fk_transaction_card", "transaction", cause);

            assertThat(thrown.getConstraintName()).isEqualTo("fk_transaction_card");
            assertThat(thrown.getRelation()).isEqualTo("transaction");
            assertThat(thrown.getCause())
                    .as("the SQLSTATE carried by the cause is the machine-readable classification; losing "
                            + "it would leave only prose")
                    .isSameAs(cause);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "  fk_card_account  "})
        @DisplayName("values are stored EXACTLY as supplied, with no blank-to-null and no strip")
        void valuesAreStoredExactlyAsSupplied(final String raw) {
            final DataIntegrityException thrown = new DataIntegrityException(MESSAGE, raw, raw);

            assertThat(thrown.getConstraintName())
                    .as("observed behaviour, pinned deliberately: no normalisation, matching "
                            + "DuplicateRecordException and differing from both RecordNotFoundException and "
                            + "FileUnavailableException")
                    .isEqualTo(raw)
                    .isNotNull();
            assertThat(thrown.getRelation()).isEqualTo(raw).isNotNull();
        }

        @Test
        @DisplayName("it represents a condition the legacy system could not detect at all")
        void itRepresentsAConditionTheLegacySystemCouldNotDetect() {
            final DataIntegrityException thrown = new DataIntegrityException(
                    "cross reference points at a card that does not exist", "fk_xref_card", "card_xref");

            assertThat(thrown.getRelation())
                    .as("VSAM enforced no referential integrity: a cross-reference row could point at an "
                            + "absent card and nothing would notice until a program read it and abended. "
                            + "The ten foreign keys in V1__create_schema.sql make that detectable, and "
                            + "this type is how it surfaces - added capability, not a translation")
                    .isEqualTo("card_xref");
        }
    }

    @Nested
    @DisplayName("the comparative assertions: three policies proven DIFFERENT, side by side")
    class PolicyComparison {

        private static final String PADDED = "   ACCTDAT   ";

        private static final String BLANK = "   ";

        @Test
        @DisplayName("on a padded value, FileUnavailable strips while RecordNotFound does not")
        void onAPaddedValueOnlyFileUnavailableStrips() {
            final String viaUnavailable =
                    new FileUnavailableException(MESSAGE, PADDED, null).resourceName().orElseThrow();
            final String viaNotFound =
                    new RecordNotFoundException(MESSAGE, PADDED, PADDED).recordType().orElseThrow();

            assertThat(viaUnavailable)
                    .as("normaliseResourceName strips; this is the only type in the package that does")
                    .isEqualTo("ACCTDAT");
            assertThat(viaNotFound)
                    .as("blankToNull does not strip, so the padding survives")
                    .isEqualTo(PADDED);
            assertThat(viaUnavailable)
                    .as("the two policies are genuinely different and this is the assertion that proves "
                            + "it; harmonising them would break one of these two expectations and that "
                            + "must be a deliberate decision")
                    .isNotEqualTo(viaNotFound);
        }

        @Test
        @DisplayName("on a blank value, two types report absent and two report a present blank")
        void onABlankValueTheTypesDisagreeAboutAbsence() {
            assertThat(new FileUnavailableException(MESSAGE, BLANK, null).resourceName())
                    .as("blank becomes absent")
                    .isEmpty();
            assertThat(new RecordNotFoundException(MESSAGE, BLANK, BLANK).recordType())
                    .as("blank becomes absent")
                    .isEmpty();
            assertThat(new DuplicateRecordException(MESSAGE, BLANK, BLANK).getLogicalFile())
                    .as("blank stays present as a blank string, NOT null - the opposite of the two above")
                    .isEqualTo(BLANK)
                    .isNotNull();
            assertThat(new DataIntegrityException(MESSAGE, BLANK, BLANK).getConstraintName())
                    .as("blank stays present as a blank string, matching DuplicateRecordException")
                    .isEqualTo(BLANK)
                    .isNotNull();
        }

        @Test
        @DisplayName("the accessor style splits the same way: Optional for the two converting types")
        void theAccessorStyleSplitsTheSameWayAsThePolicy() throws ReflectiveOperationException {
            assertThat(FileUnavailableException.class.getMethod("resourceName").getReturnType())
                    .isEqualTo(java.util.Optional.class);
            assertThat(RecordNotFoundException.class.getMethod("recordType").getReturnType())
                    .isEqualTo(java.util.Optional.class);
            assertThat(DuplicateRecordException.class.getMethod("getLogicalFile").getReturnType())
                    .as("the two types that convert blank to null expose Optional accessors; the two that "
                            + "store raw expose plain getters. The correlation is not a coincidence - a "
                            + "type that can yield null is the one that needs Optional")
                    .isEqualTo(String.class);
            assertThat(DataIntegrityException.class.getMethod("getConstraintName").getReturnType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("all five types remain mutually exclusive so a caller can catch exactly one status")
        void allFiveTypesRemainMutuallyExclusive() {
            assertThat(RecordNotFoundException.class.isAssignableFrom(DuplicateRecordException.class))
                    .as("status '23' and status '22' are different conditions with different handling; "
                            + "catching one must never catch the other")
                    .isFalse();
            assertThat(FileUnavailableException.class.isAssignableFrom(FileAccessException.class))
                    .as("status '35' means the file is not open; the '9x' family means a physical or "
                            + "logical error on a file that is open")
                    .isFalse();
            assertThat(DataIntegrityException.class.isAssignableFrom(RecordNotFoundException.class))
                    .isFalse();
        }
    }
}
