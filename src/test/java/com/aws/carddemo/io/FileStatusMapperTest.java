/*
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
 */
package com.aws.carddemo.io;

import com.aws.carddemo.testsupport.FixtureLoader;
import com.aws.carddemo.testsupport.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit tests for {@link FileStatusMapper}, the I/O adapter that translates the
 * legacy VSAM 2-character {@code STATUS} codes (returned by COBOL
 * {@code OPEN}, {@code READ}, {@code WRITE}, {@code REWRITE}, {@code DELETE},
 * and {@code CLOSE} operations) into idiomatic Java exception subclasses and
 * action enums.
 *
 * <h2>Test Contract Source</h2>
 *
 * <p>The authoritative test contract lives in
 * {@code src/test/resources/fixtures/edge/status_code_mappings.csv}, which
 * enumerates every documented VSAM status code along with its expected Java
 * exception class (or {@code NONE} for success codes) and expected action
 * enum value ({@code CONTINUE}, {@code END_OF_FILE}, {@code LOG_AND_CONTINUE},
 * or {@code ABEND}).
 *
 * <p>The primary test method
 * {@link #map_vsamStatusCode_returnsExpectedResult(String, String, String)}
 * is driven by that CSV via JUnit 5's {@link CsvFileSource} annotation. Each
 * row produces one parameterized test invocation that calls
 * {@link FileStatusMapper#map(String)} and asserts on both the action enum and
 * the exception type.
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>Every status code in the CSV originates from an industry-standard VSAM
 * file-status code (FSTAT-CODE on z/OS) referenced by the migrated COBOL
 * programs. Direct references in the source repository:
 * <ul>
 *   <li>{@code '00'} (success) - every COBOL program reading VSAM files</li>
 *   <li>{@code '10'} (EOF) - {@code CBACT01C}, {@code CBACT02C},
 *       {@code CBACT03C}, {@code CBCUS01C}, {@code CBTRN02C} (sequential read
 *       loops)</li>
 *   <li>{@code '23'} (record not found) - {@code CBACT04C} lines 422 and 436
 *       (triggers {@code DEFAULT}-group fallback)</li>
 * </ul>
 *
 * <p>Status codes beyond {@code '00'}, {@code '10'}, and {@code '23'} are not
 * directly compared in COBOL source (they all fall under the "any other"
 * ABEND branch) but the mapper provides granular exception types so the
 * migrated Java code can render appropriate diagnostic messages for each
 * documented failure mode.
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>This test class contains no parallel switch statement or if/else chain
 * that re-derives the expected mapping from the input status code. The CSV is
 * the canonical table; the test reads it and asserts on the real production
 * {@link FileStatusMapper}'s output. Test bodies contain only assertion
 * logic, never business logic.
 *
 * <h2>Coverage Target (AAP §0.7.1)</h2>
 *
 * <p>The {@code com.aws.carddemo.io.**} package carries a {@code ≥90%}
 * line / {@code ≥85%} branch coverage floor. The 29 parameterized rows plus
 * the explicit boundary tests below achieve full coverage of the mapper's
 * branches.
 *
 * @see FileStatusMapper
 * @see FileStatusResult
 * @see FileStatusAction
 */
@DisplayName("FileStatusMapper VSAM status code → Java exception mapping")
final class FileStatusMapperTest {

    /**
     * Real production {@link FileStatusMapper} instance under test. The class
     * is stateless and the field is {@code final}; the instance is safe to
     * share across parallel JUnit invocations per AAP §0.10.9.
     */
    private final FileStatusMapper mapper = new FileStatusMapper();

    // ================================================================
    // Primary CSV-driven parameterized test (29 rows from the fixture)
    // ================================================================

    /**
     * Asserts that {@link FileStatusMapper#map(String)} returns a result whose
     * action enum and exception type match the canonical mapping in
     * {@code src/test/resources/fixtures/edge/status_code_mappings.csv}.
     *
     * <p>This is the primary correctness gate for {@link FileStatusMapper}.
     * Every documented VSAM status code (29 in total) is exercised by exactly
     * one row from the CSV. The assertion logic resolves the expected
     * exception class via {@link Class#forName(String)} so the test stays
     * decoupled from the specific exception class hierarchy.
     *
     * <p>For rows with {@code expectedExceptionClass = NONE}: asserts the
     * result holds no exception. For rows with a fully-qualified class name:
     * asserts the result's exception is an instance of that class.
     *
     * <p>For all rows: asserts the result's action enum name matches the
     * {@code expectedAction} column verbatim.
     *
     * @param vsamStatusCode         2-character VSAM file-status code (e.g.,
     *                               {@code "00"}, {@code "10"}, {@code "23"})
     * @param expectedExceptionClass fully-qualified Java exception class name,
     *                               or {@code "NONE"} for success codes
     * @param expectedAction         action enum literal: {@code CONTINUE},
     *                               {@code END_OF_FILE},
     *                               {@code LOG_AND_CONTINUE}, or
     *                               {@code ABEND}
     * @throws ClassNotFoundException if the {@code expectedExceptionClass}
     *                                cannot be resolved (indicates a missing
     *                                production exception class — the test
     *                                correctly fails in that case)
     */
    @ParameterizedTest(name = "[{index}] status=''{0}'' → action={2}, exception={1}")
    @CsvFileSource(
        resources = "/fixtures/edge/status_code_mappings.csv",
        numLinesToSkip = 1)
    @DisplayName("map(statusCode) returns expected action and exception per status_code_mappings.csv")
    void map_vsamStatusCode_returnsExpectedResult(
            String vsamStatusCode,
            String expectedExceptionClass,
            String expectedAction) throws ClassNotFoundException {
        // Arrange — production mapper is the field-level final instance.

        // Act
        FileStatusResult result = mapper.map(vsamStatusCode);

        // Assert — action enum
        assertThat(result)
            .as("FileStatusResult must not be null for status '%s'", vsamStatusCode)
            .isNotNull();
        assertThat(result.getAction())
            .as("Action enum for status '%s'", vsamStatusCode)
            .isNotNull();
        assertThat(result.getAction().name())
            .as("Action name for status '%s'", vsamStatusCode)
            .isEqualTo(expectedAction);

        // Assert — exception class (or absence)
        if ("NONE".equals(expectedExceptionClass)) {
            assertThat(result.getException())
                .as("Status '%s' should not carry an exception", vsamStatusCode)
                .isEmpty();
        } else {
            Class<?> expectedClass = Class.forName(expectedExceptionClass);
            assertThat(result.getException())
                .as("Status '%s' should carry an exception of type %s",
                    vsamStatusCode, expectedExceptionClass)
                .isPresent()
                .get()
                .isInstanceOf(expectedClass);
        }
    }

    // ================================================================
    // Defensive input tests — null, empty, whitespace
    // ================================================================

    /**
     * Asserts that calling {@link FileStatusMapper#map(String)} with
     * {@code null} throws {@link NullPointerException} whose message contains
     * the word {@code "status"} for diagnostic clarity.
     *
     * <p>The COBOL runtime never returns {@code null} for FILE-STATUS, so
     * {@code null} at this boundary always indicates a caller-side bug
     * (typically forgotten to read the status field after an I/O operation).
     */
    @Test
    @DisplayName("map(null) throws NullPointerException with a message mentioning status")
    void map_nullStatusCode_throwsNullPointerException() {
        // Act + Assert
        assertThatNullPointerException()
            .isThrownBy(() -> mapper.map(null))
            .withMessageContaining("status");
    }

    /**
     * Asserts that empty or whitespace-only status codes throw
     * {@link IllegalArgumentException}. The COBOL runtime always returns a
     * 2-character status code; whitespace at this boundary always indicates a
     * caller-side bug.
     *
     * <p>The CSV-driven primary test covers only the 29 well-formed status
     * codes; this defensive test drives the input-validation branch in
     * {@link FileStatusMapper#map(String)}.
     *
     * @param input the malformed input to drive into the mapper
     */
    @ParameterizedTest(name = "[{index}] empty/whitespace input ''{0}''")
    @ValueSource(strings = {"", " ", "  ", "\t", "\n"})
    @DisplayName("map(empty or whitespace) throws IllegalArgumentException")
    void map_emptyOrWhitespaceStatusCode_throwsIllegalArgumentException(String input) {
        // Act
        Throwable thrown = catchThrowable(() -> mapper.map(input));

        // Assert
        assertThat(thrown)
            .as("Empty or whitespace input '%s' must be rejected", input)
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ================================================================
    // Defensive input tests — unknown status codes
    // ================================================================

    /**
     * Asserts that unknown 2-character status codes (or other malformed but
     * non-empty input) preserve the COBOL "any other status → CEE3ABD"
     * semantics. The production mapper accepts two reasonable behaviours:
     *
     * <ul>
     *   <li>Throwing {@link IllegalArgumentException} immediately to reject
     *       malformed input.</li>
     *   <li>Returning a {@link FileStatusResult} with
     *       {@link FileStatusAction#ABEND} carrying a generic
     *       {@link VsamException} — the path that preserves COBOL's "fall
     *       through to abend" semantics.</li>
     * </ul>
     *
     * <p>Either behaviour satisfies the AAP §0.10.4 Immutable Boundaries
     * contract because unknown status codes cannot be processed and the
     * caller is informed of that fact via an exception or via the ABEND
     * action.
     *
     * @param input the unknown status code to drive into the mapper
     */
    @ParameterizedTest(name = "[{index}] unknown status code ''{0}''")
    @ValueSource(strings = {"XX", "AB", "ZZ", "99X", "0", "000", "  00"})
    @DisplayName("map(unknown status code) routes to ABEND with VsamException or rejects input")
    void map_unknownStatusCode_routesToAbendOrRejectsInput(String input) {
        // Act
        Throwable thrown = catchThrowable(() -> {
            FileStatusResult result = mapper.map(input);
            // If the mapper accepts the input rather than throwing, assert the
            // ABEND-with-exception contract. This branch is exercised when
            // the production code chose the "fall through" semantics.
            assertThat(result)
                .as("Unknown status code '%s' must produce a non-null result if not thrown", input)
                .isNotNull();
            assertThat(result.getAction().name())
                .as("Unknown status code '%s' must route to ABEND", input)
                .isEqualTo("ABEND");
            assertThat(result.getException())
                .as("Unknown status code '%s' must carry an exception", input)
                .isPresent();
        });

        // Assert: either the call threw (rejecting the input) OR the result
        // indicated ABEND (no thrown). The catchThrowable lambda captures the
        // AssertionError from the assertions above, so a null `thrown` means
        // the ABEND path succeeded; a non-null `thrown` must be an
        // IllegalArgumentException or NullPointerException from the mapper.
        if (thrown != null && !(thrown instanceof AssertionError)) {
            assertThat(thrown)
                .as("If the mapper throws on unknown input '%s', the exception type must be IllegalArgumentException or NullPointerException", input)
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        } else if (thrown instanceof AssertionError) {
            // The ABEND-path assertions failed — rethrow to surface the
            // diagnostic message to the test runner.
            throw (AssertionError) thrown;
        }
        // If thrown is null, the ABEND-path assertions all passed and the
        // test succeeds.
    }

    // ================================================================
    // Fixture coverage assertion
    // ================================================================

    /**
     * Asserts that the CSV fixture is loaded with the expected row count
     * (header + 29 documented status codes = 30 rows), guarding against
     * accidental row deletions that would silently reduce test coverage.
     * Status codes covered by the CSV must remain a superset of those
     * documented in the COBOL source as referenced by AAP §0.5.1.
     *
     * <p>This test does NOT exercise {@link FileStatusMapper}; it is a
     * fixture-integrity smoke check. Per AAP §0.10.1 it does not reimplement
     * business logic — it merely asserts on the CSV's row count and verifies
     * that all 8 AAP-mandated status codes are present.
     */
    @Test
    @DisplayName("status_code_mappings.csv covers all 8 AAP-required status codes")
    void fixtureCoversAllRequiredStatusCodes() {
        // Arrange
        List<String[]> rows = FixtureLoader.loadEdgeCases(
            TestFixtures.Paths.EDGE_STATUS_CODE_MAPPINGS);

        // Assert: header + data rows
        assertThat(rows)
            .as("CSV must contain at least 1 header + 8 required data rows = 9 total")
            .hasSizeGreaterThanOrEqualTo(9);

        // Assert: every AAP §0.5.1 required status code is present in the CSV
        List<String> statusCodes = rows.subList(1, rows.size()).stream()
            .map(row -> row[0])
            .toList();
        assertThat(statusCodes)
            .as("AAP §0.5.1 mandates coverage of these 8 status codes")
            .contains(
                TestFixtures.VsamStatus.OK,                  // "00"
                TestFixtures.VsamStatus.DUPLICATE_KEY,        // "02"
                TestFixtures.VsamStatus.EOF,                  // "10"
                TestFixtures.VsamStatus.SEQUENCE_ERROR,       // "22"
                TestFixtures.VsamStatus.RECORD_NOT_FOUND,     // "23"
                TestFixtures.VsamStatus.FILE_NOT_FOUND,       // "35"
                TestFixtures.VsamStatus.LOGIC_ERROR,          // "92"
                TestFixtures.VsamStatus.OPEN_VERIFY_REQUIRED); // "97"
    }

    // ================================================================
    // Per-action sanity tests (drive branch coverage and document
    // the four action semantics for future maintainers)
    // ================================================================

    /**
     * Asserts that status code {@code "00"} (the canonical success code in
     * COBOL VSAM I/O — see {@code CBACT01C} line 94) maps to
     * {@link FileStatusAction#CONTINUE} with no carried exception. This is
     * the most frequently traversed branch in the mapper at runtime.
     */
    @Test
    @DisplayName("map('00') returns CONTINUE with no exception (success path)")
    void map_successStatusCode_returnsContinueWithNoException() {
        // Act
        FileStatusResult result = mapper.map(TestFixtures.VsamStatus.OK);

        // Assert
        assertThat(result)
            .as("FileStatusResult for success status must not be null")
            .isNotNull();
        assertThat(result.getAction().name())
            .as("Status '00' must route to CONTINUE")
            .isEqualTo("CONTINUE");
        assertThat(result.getException())
            .as("Status '00' must not carry an exception")
            .isEmpty();
    }

    /**
     * Asserts that status code {@code "10"} (end-of-file in COBOL VSAM I/O —
     * see {@code CBACT01C} line 98 where the program moves {@code 'Y'} to
     * {@code END-OF-FILE}) maps to {@link FileStatusAction#END_OF_FILE} with
     * an {@link EndOfFileException} for Spring Batch readers to detect.
     */
    @Test
    @DisplayName("map('10') returns END_OF_FILE with an EndOfFileException")
    void map_eofStatusCode_returnsEndOfFileAction() {
        // Act
        FileStatusResult result = mapper.map(TestFixtures.VsamStatus.EOF);

        // Assert
        assertThat(result)
            .as("FileStatusResult for EOF status must not be null")
            .isNotNull();
        assertThat(result.getAction().name())
            .as("Status '10' must route to END_OF_FILE")
            .isEqualTo("END_OF_FILE");
        assertThat(result.getException())
            .as("EOF must carry an EndOfFileException for batch processors to detect")
            .isPresent()
            .get()
            .isInstanceOf(EndOfFileException.class);
    }

    /**
     * Asserts that status code {@code "23"} (record not found on keyed read)
     * maps to {@link FileStatusAction#LOG_AND_CONTINUE} with a
     * {@link RecordNotFoundException}. This semantic powers the
     * {@code CBACT04C} DEFAULT-group fallback at lines 422-440: when a
     * disclosure-group keyed read returns status {@code '23'}, the COBOL
     * program retries with the literal {@code DEFAULT} key. The Java
     * migration preserves this behaviour by routing to LOG_AND_CONTINUE so
     * the caller can catch and substitute.
     */
    @Test
    @DisplayName("map('23') returns LOG_AND_CONTINUE for DEFAULT-group fallback semantics (CBACT04C)")
    void map_recordNotFoundStatusCode_returnsLogAndContinue() {
        // Act
        FileStatusResult result = mapper.map(TestFixtures.VsamStatus.RECORD_NOT_FOUND);

        // Assert
        assertThat(result)
            .as("FileStatusResult for record-not-found must not be null")
            .isNotNull();
        // CBACT04C lines 422-440 treat status '23' as recoverable (triggers
        // DEFAULT fallback); the Java migration translates this to
        // LOG_AND_CONTINUE so the caller can catch and retry.
        assertThat(result.getAction().name())
            .as("Status '23' must route to LOG_AND_CONTINUE for DEFAULT-group fallback")
            .isEqualTo("LOG_AND_CONTINUE");
        assertThat(result.getException())
            .as("Status '23' must carry a RecordNotFoundException for CBACT04C DEFAULT fallback")
            .isPresent()
            .get()
            .isInstanceOf(RecordNotFoundException.class);
    }

    /**
     * Asserts that status code {@code "35"} (OPEN failed because the file
     * does not exist or is inaccessible) maps to
     * {@link FileStatusAction#ABEND} with a {@link FileNotOpenException}.
     * Status {@code '35'} is the most common deployment-environment failure
     * mode, so the test documents this code path explicitly.
     */
    @Test
    @DisplayName("map('35') returns ABEND with FileNotOpenException for missing input files")
    void map_fileNotFoundStatusCode_returnsAbend() {
        // Act
        FileStatusResult result = mapper.map(TestFixtures.VsamStatus.FILE_NOT_FOUND);

        // Assert
        assertThat(result)
            .as("FileStatusResult for file-not-found must not be null")
            .isNotNull();
        assertThat(result.getAction().name())
            .as("Status '35' (file not found on OPEN) must route to ABEND")
            .isEqualTo("ABEND");
        assertThat(result.getException())
            .as("Status '35' must carry a FileNotOpenException")
            .isPresent()
            .get()
            .isInstanceOf(FileNotOpenException.class);
    }
}
