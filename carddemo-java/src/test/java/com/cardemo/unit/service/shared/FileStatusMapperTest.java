package com.cardemo.unit.service.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.service.shared.FileStatusMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-JVM behavioural-parity unit test for
 * {@link com.cardemo.service.shared.FileStatusMapper FileStatusMapper}, the single typed
 * translation point that replaces the scattered COBOL two-byte {@code FILE STATUS} comparisons of
 * the canonical batch posting program {@code app/cbl/CBTRN02C.cbl}.
 *
 * <h2>Parity target &amp; provenance</h2>
 * <p>The behaviour asserted here is translated from the frozen AWS CardDemo COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference and is <strong>never
 * copied</strong> into this repository (AAP &sect;0.7.2); only its observable behaviour &mdash; the
 * {@code FILE STATUS} code &rarr; typed-exception contract (AAP &sect;0.4.1 tech-spec L640;
 * status&rarr;exception rule &sect;0.7.5 tech-spec L1063) &mdash; is verified. Every assertion
 * encodes COBOL-identical behaviour with no added strictness and no relaxation (Minimal Change
 * Clause, &sect;0.7.1).</p>
 *
 * <h2>Contract under test</h2>
 * <ul>
 *   <li>{@code "00"} (APPL-AOK, {@code CBTRN02C.cbl} L239/L347) &rarr; normal flow, no throw;</li>
 *   <li>{@code "10"} (APPL-EOF, {@code CBTRN02C.cbl} L351) &rarr; normal flow, no throw;</li>
 *   <li>{@code "23"} (INVALID KEY, {@code CBTRN02C.cbl} L481) &rarr; {@link RecordNotFoundException}
 *       (a {@link CardDemoException} subtype &rarr; HTTP&nbsp;404);</li>
 *   <li>{@code "22"} (duplicate key on WRITE) &rarr; {@link DuplicateRecordException}
 *       (a {@link CardDemoException} subtype &rarr; HTTP&nbsp;409);</li>
 *   <li>{@code "35"} (dataset not available on OPEN) and any unmapped/{@code null} code &rarr;
 *       {@link IllegalStateException} mirroring {@code 9999-ABEND-PROGRAM}
 *       ({@code CBTRN02C.cbl} L250) &rarr; HTTP&nbsp;500 &mdash; deliberately <em>not</em> a
 *       {@link CardDemoException}.</li>
 * </ul>
 *
 * <h2>Key parity nuance</h2>
 * <p>End-of-file ({@code "10"}) is a <strong>normal control signal, not an error</strong>: in
 * COBOL it sets {@code END-OF-FILE = 'Y'} ({@code CBTRN02C.cbl} L351/L360-361) to terminate the
 * sequential {@code PERFORM UNTIL END-OF-FILE = 'Y'} read loop (L202) and never reaches the abend
 * path, so {@code verify("10", ...)} must <em>not</em> throw.</p>
 *
 * <h2>Scope boundary</h2>
 * <p>The four-digit business <em>reject codes</em> ({@code 100}/{@code 101}/{@code 102}/{@code 103})
 * live in {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} ({@code CBTRN02C.cbl} L181; e.g. {@code 102}
 * "OVERLIMIT TRANSACTION" at L410-411) and are owned by the batch validation processor, <em>not</em>
 * by this two-byte {@code FILE STATUS} mapper. The boundary test asserts {@code "102"} is treated as
 * an unknown file status purely by type exclusion, without referencing any business-reject exception
 * type.</p>
 *
 * <h2>Why this is a plain JUnit&nbsp;5 test (no Spring)</h2>
 * <p>{@link FileStatusMapper} is a stateless, immutable {@code @Service} with no collaborators: it
 * depends only on the JDK, the {@code FileStatus} enum and the {@code com.cardemo.exception}
 * hierarchy. It is therefore instantiated directly ({@code new FileStatusMapper()}) and exercised
 * with <em>no</em> {@code @SpringBootTest}, application context, database, AWS, Testcontainers,
 * Mockito or reflection. The suite performs no I/O and runs in milliseconds.</p>
 *
 * @see FileStatusMapper
 * @see CardDemoException
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 */
class FileStatusMapperTest {

    /**
     * The system under test. {@link FileStatusMapper} is stateless, immutable and thread-safe, so a
     * single shared instance is reused across every test without any risk of cross-test state.
     */
    private final FileStatusMapper mapper = new FileStatusMapper();

    @Nested
    @DisplayName("isSuccess(String) & isEndOfFile(String) — pure '00'/'10' equality predicates (CBTRN02C L239/L347, L351)")
    class Predicates {

        @Test
        @DisplayName("isSuccess: '00' (APPL-AOK) is the only success code")
        void isSuccessTrueOnlyForDoubleZero() {
            assertThat(mapper.isSuccess("00")).isTrue();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"10", "22", "23", "35", "99"})
        @DisplayName("isSuccess: every non-'00' code (and null) is not success")
        void isSuccessFalseForEverythingElse(final String code) {
            // Mirrors the total, null-safe COBOL IF *-STATUS = '00' equality test, which simply
            // evaluates false (it never abends) for any other value.
            assertThat(mapper.isSuccess(code)).isFalse();
        }

        @Test
        @DisplayName("isEndOfFile: '10' (APPL-EOF) is the only end-of-file code")
        void isEndOfFileTrueOnlyForTen() {
            assertThat(mapper.isEndOfFile("10")).isTrue();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"00", "22", "23", "35", "99"})
        @DisplayName("isEndOfFile: every non-'10' code (and null) is not end-of-file")
        void isEndOfFileFalseForEverythingElse(final String code) {
            assertThat(mapper.isEndOfFile(code)).isFalse();
        }
    }

    @Nested
    @DisplayName("verify(code, entityType, key) — abend vs. recoverable vs. success parity (CBTRN02C 9999-ABEND-PROGRAM)")
    class VerifyWithContext {

        @Test
        @DisplayName("'00' (APPL-AOK) returns normally — successful I/O is not an error")
        void successDoesNotThrow() {
            assertThatCode(() -> mapper.verify("00", "Account", "123"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'10' (APPL-EOF) returns normally — EOF is a normal control signal, NOT an error")
        void endOfFileDoesNotThrow() {
            // THE critical parity nuance: '10' sets END-OF-FILE = 'Y' (CBTRN02C L351/L360-361)
            // terminating PERFORM UNTIL END-OF-FILE = 'Y' (L202); it never reaches
            // 9999-ABEND-PROGRAM, so verify() must NOT throw on end-of-file.
            assertThatCode(() -> mapper.verify("10", "Transaction", "TXN1"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'23' (INVALID KEY) throws RecordNotFoundException — a recoverable CardDemoException carrying the key")
        void recordNotFoundIsRecoverableDomainException() {
            // COBOL parity: '23' is handled gracefully (IF TCATBALF-STATUS = '00' OR '23',
            // CBTRN02C L481) — record-not-found is a recoverable branch, not an abend.
            assertThatThrownBy(() -> mapper.verify("23", "Account", "123"))
                    .isInstanceOf(RecordNotFoundException.class)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("123")
                    .hasMessageContaining("Account");
        }

        @Test
        @DisplayName("'22' (duplicate key on WRITE) throws DuplicateRecordException — a recoverable CardDemoException carrying the key")
        void duplicateKeyIsRecoverableDomainException() {
            assertThatThrownBy(() -> mapper.verify("22", "Transaction", "TXN1"))
                    .isInstanceOf(DuplicateRecordException.class)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("TXN1");
        }

        @Test
        @DisplayName("'35' (dataset not available) abends as IllegalStateException — NOT a CardDemoException")
        void fileNotAvailableAbends() {
            // COBOL parity: a non-'00' OPEN status routes to 9999-ABEND-PROGRAM (CBTRN02C L250).
            // The Java parity is a hard, unrecoverable IllegalStateException distinct from the
            // recoverable CardDemoException domain hierarchy.
            assertThatThrownBy(() -> mapper.verify("35", "Account", "123"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CardDemoException.class)
                    .hasMessageContaining("35");
        }

        @Test
        @DisplayName("unknown code '99' abends as IllegalStateException with a clear message — no raw IllegalArgumentException leak")
        void unknownCodeAbendsWithoutLeak() {
            // FileStatus.fromCode("99") raises IllegalArgumentException; verify() must guard it and
            // re-throw the unrecoverable IllegalStateException (mirroring 9999-ABEND-PROGRAM)
            // rather than let the raw IllegalArgumentException escape. IllegalStateException and
            // IllegalArgumentException are sibling RuntimeException types, so asserting the exact
            // IllegalStateException type already proves the guard fired.
            assertThatThrownBy(() -> mapper.verify("99", "Account", "123"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CardDemoException.class)
                    .isNotInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @ParameterizedTest
        @ValueSource(strings = {"37", "47", "90"})
        @DisplayName("any other unmapped 2-byte status abends as IllegalStateException (not a CardDemoException)")
        void otherUnmappedCodesAbend(final String unmapped) {
            assertThatThrownBy(() -> mapper.verify(unmapped, "Account", "123"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CardDemoException.class);
        }
    }

    @Nested
    @DisplayName("reject code 102 is NOT a FILE STATUS — boundary owned by batch validation, not FileStatusMapper")
    class RejectCodeBoundary {

        @Test
        @DisplayName("'102' (reject-code-shaped input) is an unknown file status → IllegalStateException, NOT a CardDemoException")
        void rejectCodeShapedInputIsNotAFileStatus() {
            // CBTRN02C L181 holds the four-digit business reject codes in
            // WS-VALIDATION-FAIL-REASON PIC 9(04) (for example 102 'OVERLIMIT TRANSACTION' at
            // L410-411). Those belong to the batch validation processor, NOT to the two-byte
            // FILE STATUS mapper. The mapper must therefore treat "102" as an unknown file status
            // and abend — never misinterpreting it as the over-limit business reject and never
            // routing to CreditLimitExceededException (deliberately not imported here, so the
            // boundary is asserted purely by CardDemoException type exclusion).
            assertThatThrownBy(() -> mapper.verify("102", "Account", "999"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CardDemoException.class);
        }
    }

    @Nested
    @DisplayName("verify(code) — single-arg overload (no entity/key context) parity")
    class VerifyNoContext {

        @Test
        @DisplayName("'00' (success) and '10' (EOF) return normally — neither is an error")
        void successAndEofDoNotThrow() {
            assertThatCode(() -> mapper.verify("00")).doesNotThrowAnyException();
            assertThatCode(() -> mapper.verify("10")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'23' throws RecordNotFoundException (type only — no entity/key context to assert)")
        void recordNotFoundTypeOnly() {
            // The no-context overload yields a generic description-based message (no key), so only
            // the recoverable exception TYPE is asserted here, not a key substring.
            assertThatThrownBy(() -> mapper.verify("23"))
                    .isInstanceOf(RecordNotFoundException.class)
                    .isInstanceOf(CardDemoException.class);
        }

        @Test
        @DisplayName("'22' throws DuplicateRecordException (type only — no entity/key context to assert)")
        void duplicateKeyTypeOnly() {
            assertThatThrownBy(() -> mapper.verify("22"))
                    .isInstanceOf(DuplicateRecordException.class)
                    .isInstanceOf(CardDemoException.class);
        }

        @Test
        @DisplayName("'35' (file not available) and unknown '99' abend as IllegalStateException — NOT a CardDemoException")
        void abendCodesThrowIllegalState() {
            assertThatThrownBy(() -> mapper.verify("35"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CardDemoException.class);
            assertThatThrownBy(() -> mapper.verify("99"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CardDemoException.class);
        }
    }
}
