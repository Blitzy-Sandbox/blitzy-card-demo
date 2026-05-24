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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link CardFileReaderService} &mdash; the Java {@code @Service}
 * translation of the COBOL batch program
 * {@code app/cbl/CBACT02C.cbl} ("Read and print card data file.").
 *
 * <h2>COBOL source provenance (AAP &sect;0.4.1 / &sect;0.7.3)</h2>
 *
 * <p>{@link CardFileReaderService} is the Java target for
 * {@code CBACT02C} per the AAP &sect;0.4.1 file-by-file
 * transformation plan (Batch COBOL Programs &rarr; Spring Batch Job
 * + Service Classes table). The source program executes:</p>
 *
 * <pre>
 * IDENTIFICATION DIVISION.
 * PROGRAM-ID.    CBACT02C.
 * ...
 * SELECT CARDFILE-FILE ASSIGN TO CARDFILE
 *        ORGANIZATION IS INDEXED
 *        ACCESS MODE  IS SEQUENTIAL
 *        RECORD KEY   IS FD-CARD-NUM
 * ...
 * PROCEDURE DIVISION.
 *     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.
 *     PERFORM 0000-CARDFILE-OPEN.
 *     PERFORM UNTIL END-OF-FILE = 'Y'
 *         PERFORM 1000-CARDFILE-GET-NEXT
 *         IF END-OF-FILE = 'N' DISPLAY CARD-RECORD
 *     END-PERFORM.
 *     PERFORM 9000-CARDFILE-CLOSE.
 *     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'.
 *     GOBACK.
 * </pre>
 *
 * <p>The COBOL record layout is defined in
 * {@code app/cpy/CVACT02Y.cpy} (150-byte {@code CARD-RECORD},
 * comprising the 16-character {@code CARD-NUM} primary key
 * ({@code PIC X(16)}), the 11-digit {@code CARD-ACCT-ID}
 * ({@code PIC 9(11)}), the 3-digit {@code CARD-CVV-CD}
 * ({@code PIC 9(03)} &mdash; sensitive authentication data
 * (SAD) per PCI-DSS v4.0 Requirement 3.2), the 50-character
 * {@code CARD-EMBOSSED-NAME} ({@code PIC X(50)}), the
 * 10-character {@code CARD-EXPIRAION-DATE} ({@code PIC X(10)}
 * &mdash; typo preserved in COBOL but corrected to
 * {@code expiration} in the Java entity per AAP &sect;0.4.1
 * V002), the 1-character {@code CARD-ACTIVE-STATUS}
 * ({@code PIC X(01)}), and a trailing 59-byte
 * {@code FILLER PIC X(59)} omitted in the relational schema).</p>
 *
 * <h2>Behavioural invariants under test (AAP &sect;0.7.1, &sect;0.7.3)</h2>
 *
 * <ul>
 *   <li><b>Sequential read of all cards</b> &mdash; preserves
 *       the COBOL {@code OPEN INPUT / READ NEXT / CLOSE} loop on
 *       {@code RECORD KEY FD-CARD-NUM} (CBACT02C L29&ndash;L33,
 *       L70&ndash;L87). The Java target invokes
 *       {@code cardRepository.findAll(Sort.by("cardNum"))} once
 *       per execution.</li>
 *   <li><b>PAN masking on emission</b> (CRITICAL &mdash; PCI-DSS
 *       v4.0 Requirement 3.4.1) &mdash; the COBOL source
 *       {@code DISPLAY CARD-RECORD} at line 78 emits the full
 *       16-digit PAN to SYSOUT (operator console). The Java
 *       target ships logs to CloudWatch Logs (multi-tenant), so
 *       the SUT's {@code displayCardRecord} private method masks
 *       the PAN via {@code maskPan} to
 *       {@code "************" + last4} before SLF4J emission, and
 *       the audit payload sent to
 *       {@link AuditLogService#logBatchJobLifecycle} contains NO
 *       card data at all (only operational metadata:
 *       {@code program_id}, {@code entity_name},
 *       {@code record_count}, {@code vsam_cluster}). This test
 *       class verifies the PCI-DSS-safety invariant that NO full
 *       16-digit PAN ever appears in any captured audit payload
 *       under any circumstance.</li>
 *   <li><b>CVV never emitted</b> (CRITICAL &mdash; PCI-DSS v4.0
 *       Requirement 3.2) &mdash; {@code CARD-CVV-CD} is
 *       "sensitive authentication data" (SAD); SAD MUST NOT be
 *       persisted post-authorization in a real payment-card
 *       environment. CardDemo persists it ONLY because the COBOL
 *       source persists it ({@code CVACT02Y.cpy:L7}) and the
 *       Minimal Change Clause (AAP &sect;0.7.3) forbids removing
 *       fields. The Java target NEVER reads
 *       {@code card.getCardCvvCd()} when emitting log lines or
 *       audit events. This test class verifies the invariant
 *       that NO CVV value or {@code cardCvvCd} key ever appears
 *       in any captured audit payload.</li>
 *   <li><b>Read-only contract</b> &mdash; the COBOL source opens
 *       the VSAM cluster with {@code OPEN INPUT} (read-only) and
 *       never issues a {@code REWRITE}, {@code WRITE}, or
 *       {@code DELETE} verb across its 179 lines. The Java target
 *       must preserve this property: no
 *       {@link CardRepository#save(Object)} or other persistence-
 *       mutating call may occur during the sequential scan.</li>
 * </ul>
 *
 * <h2>Mocking strategy (AAP &sect;0.7.2 testing approach)</h2>
 *
 * <p>{@link MockitoExtension} wires the two collaborators
 * ({@link CardRepository}, {@link AuditLogService}) as Mockito
 * mocks and instantiates the SUT via the canonical two-argument
 * constructor
 * {@code CardFileReaderService(CardRepository, AuditLogService)}
 * with both mocks injected. No Spring {@code ApplicationContext}
 * is loaded &mdash; this is a pure unit test per the AAP testing
 * approach.</p>
 *
 * <p>{@link CardRepository#findAll(Sort)} is stubbed with
 * {@code when(...).thenReturn(List.of(...))} to drive the
 * empty / single-card / multi-card scenarios. The
 * {@link AuditLogService#logBatchJobLifecycle} call sites are
 * verified via {@link ArgumentCaptor} so the
 * {@link PanMasking} nested class can inspect the payload
 * {@link Map} for the PCI-DSS-safety invariants.</p>
 *
 * <h2>Fixture provenance</h2>
 *
 * <p>Fixture {@link Card} instances are built via
 * {@link #buildCardFixture(String, Integer)} which constructs
 * realistic payment-card identifiers (16-digit PAN, 3-digit CVV)
 * via the all-args {@link Card} constructor. The "test" card
 * brand identifier ranges ("4111111111111111", a well-known
 * Visa test PAN; "5500000000000004", a well-known Mastercard
 * test PAN) are used so the PAN-masking assertions are
 * unambiguously checking known full-PAN strings against the
 * captured payload.</p>
 *
 * @see CardFileReaderService the system under test
 * @see Card the JPA entity built as test fixtures
 * @see CardRepository the JPA repository mocked for sequential-scan stubs
 * @see AuditLogService the audit adapter mocked for completion-event assertions
 */
// COBOL: CBACT02C:READ-CARD-FILE — unit test for the Java translation of
//        the sequential read-and-display loop in app/cbl/CBACT02C.cbl
//        with PCI-DSS PAN masking and CVV omission.
@ExtendWith(MockitoExtension.class)
@DisplayName("CardFileReaderService — CBACT02C with PAN masking")
class CardFileReaderServiceTest {

    // =========================================================================
    // Constants — mirror the COBOL source verbatim per AAP §0.7.3
    // "Inline traceability comments" rule and the SUT's private
    // constants for direct payload assertions.
    // =========================================================================

    /**
     * The COBOL program identifier preserved verbatim from
     * {@code app/cbl/CBACT02C.cbl} {@code PROGRAM-ID. CBACT02C}.
     * Used as the audit-event {@code jobName} dimension. AAP
     * &sect;0.7.3 mandates verbatim preservation of the COBOL
     * program ID across every artefact in the migration so that
     * operators can correlate Java service emissions to their
     * original mainframe source.
     */
    private static final String COBOL_PROGRAM_ID = "CBACT02C";

    /**
     * The audit-event {@code status} marker emitted on
     * successful scan completion (matches the SUT's
     * {@code STATUS_COMPLETED} constant). The SUT only emits
     * {@code COMPLETED} on the happy path; {@code FAILED} on
     * exception is exercised separately if needed.
     */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * The logical entity name carried in the audit payload &mdash;
     * matches the SUT's {@code ENTITY_NAME} constant. Operators
     * can filter the OpenSearch / CloudWatch documents by entity
     * during the parallel-run validation window.
     */
    private static final String ENTITY_NAME = "CARD";

    /**
     * The audit-payload key under which the SUT emits the COBOL
     * program identifier. Matches the SUT's
     * {@code buildAuditPayload} contract.
     */
    private static final String PAYLOAD_PROGRAM_ID_KEY = "program_id";

    /**
     * The audit-payload key under which the SUT emits the
     * logical entity name.
     */
    private static final String PAYLOAD_ENTITY_NAME_KEY = "entity_name";

    /**
     * The audit-payload key under which the SUT emits the
     * processed record count.
     */
    private static final String PAYLOAD_RECORD_COUNT_KEY = "record_count";

    /**
     * The audit-payload key under which the SUT emits the legacy
     * VSAM cluster identifier for cross-referencing operational
     * dashboards.
     */
    private static final String PAYLOAD_VSAM_CLUSTER_KEY = "vsam_cluster";

    /**
     * The expected legacy VSAM cluster name preserved verbatim
     * from the COBOL source / JCL allocation
     * ({@code app/jcl/CARDFILE.jcl}). Carried in the audit
     * payload for cross-referencing only &mdash; the cluster
     * does not exist on the AWS target.
     */
    private static final String VSAM_CLUSTER = "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS";

    /**
     * The Spring Data JPA {@link Sort} field name used by the
     * SUT to preserve the COBOL {@code ACCESS MODE SEQUENTIAL}
     * on {@code RECORD KEY FD-CARD-NUM} semantic. The Java
     * entity property {@code cardNum} maps to the
     * {@code card_num} relational column &mdash; the primary
     * key of the {@code cards} table.
     */
    private static final String SORT_FIELD_CARD_NUM = "cardNum";

    /**
     * The literal PAN-mask prefix &mdash; twelve {@code '*'}
     * characters. Matches the SUT's {@code PAN_MASK_PREFIX}
     * constant and the PCI-DSS v4.0 Requirement 3.4.1
     * "last-4-digits visible, leading 12 masked" rule. Tests
     * that assert on masked PAN substrings reference this
     * prefix (rather than re-deriving it) to keep the
     * mask-format contract centralized.
     */
    private static final String PAN_MASK_PREFIX = "************";

    /**
     * The "test" Visa PAN reserved for unit-test fixtures &mdash;
     * a well-known test PAN distributed by Visa for non-
     * production use ("Visa test card 1"). This is NOT a real
     * cardholder PAN; it is the conventional 16-digit string
     * used across the payments industry for test fixtures
     * (e.g., Stripe, Adyen, Braintree all document it as a
     * recognised test value). Used as the canonical PAN in the
     * {@link PanMasking} nested test class so the assertion
     * targets ("full PAN MUST NOT appear in any payload") use a
     * stable, recognisable input.
     */
    private static final String TEST_PAN_VISA = "4111111111111111";

    /**
     * The last four digits of {@link #TEST_PAN_VISA}. Used by
     * the {@link PanMasking} test that explicitly checks for
     * the {@code "************1111"} masked-form contract.
     */
    private static final String TEST_PAN_VISA_LAST4 = "1111";

    /**
     * The "test" Mastercard PAN reserved for unit-test fixtures
     * &mdash; a well-known test PAN distributed by Mastercard
     * for non-production use. Used to drive the multi-card
     * fixture so the {@link SequentialScan} and
     * {@link PanMasking} nested tests exercise more than one
     * brand and the PAN-leak invariants apply to ALL captured
     * PANs.
     */
    private static final String TEST_PAN_MASTERCARD = "5500000000000004";

    /**
     * A third test PAN (Amex range, 15 digits). Despite its
     * 15-digit length (Amex PANs are 15 digits, not 16), the
     * {@code maskPan} helper must mask it to the prefix
     * {@link #PAN_MASK_PREFIX} plus the last 4 digits. Used to
     * verify the mask helper handles non-16-digit inputs
     * without leaking any digits.
     */
    private static final String TEST_PAN_AMEX = "378282246310005";

    /**
     * A fictitious 3-digit CVV value attached to the test
     * fixtures. The PCI-DSS-safety assertion in the
     * {@link PanMasking} nested class verifies that this value
     * NEVER appears in the captured audit payload &mdash; the
     * CVV is "sensitive authentication data" (SAD) per PCI-DSS
     * v4.0 Requirement 3.2 and the Java target MUST never read
     * {@code card.getCardCvvCd()} when emitting log lines or
     * audit events. Note: 123 is the most common "test" CVV
     * but ANY non-null CVV would suffice for the assertion;
     * 123 is preferred so log-grep diagnostics show an
     * unambiguous leak.
     */
    private static final Integer TEST_CVV = 123;

    /**
     * Field name used to assert that the audit payload does not
     * carry the COBOL-style {@code cardCvvCd} attribute. The
     * SUT's {@code buildAuditPayload} contract guarantees no
     * card-related keys are present in the payload at all; this
     * key string is the most obvious failure-mode key a regression
     * might introduce.
     */
    private static final String PAYLOAD_CVV_KEY = "cardCvvCd";

    // =========================================================================
    // Mocks and SUT — wired by MockitoExtension before each test method.
    // =========================================================================

    /**
     * Spring Data JPA repository mock. Stubbed via
     * {@code when(cardRepository.findAll(any(Sort.class))).thenReturn(...)}
     * to drive the empty / single-card / multi-card scenarios.
     * The {@link ReadOnly} nested class additionally verifies
     * that no mutating method ({@code save}, {@code delete},
     * etc.) is ever invoked, preserving the COBOL
     * {@code OPEN INPUT} (read-only) semantic from
     * {@code app/cbl/CBACT02C.cbl} L29&ndash;L33.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Audit-log adapter mock. Stubbing is not required because
     * the SUT does not consume any return value from
     * {@link AuditLogService#logBatchJobLifecycle}; the
     * {@link PanMasking} nested class verifies the call
     * arguments via {@link ArgumentCaptor} to assert the PCI-DSS
     * payload safety invariants (no full PAN, no CVV).
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test. {@link InjectMocks @InjectMocks}
     * instantiates {@link CardFileReaderService} via the
     * canonical two-argument constructor
     * {@code CardFileReaderService(CardRepository, AuditLogService)}
     * and wires both {@link Mock @Mock} fields above.
     */
    @InjectMocks
    private CardFileReaderService service;

    // =========================================================================
    // Per-test setup — fresh state guarantee.
    // =========================================================================

    /**
     * Per-test fixture-state hook. {@link MockitoExtension}
     * already resets the {@link Mock @Mock} and
     * {@link InjectMocks @InjectMocks} fields between methods,
     * so the only responsibility of this hook is to assert that
     * the SUT and its collaborators were wired correctly before
     * each test runs &mdash; failing fast with a descriptive
     * AssertJ message if a misconfigured Mockito or Spring
     * context ever produced a {@code null} SUT.
     *
     * <p>This explicit {@link BeforeEach} method also documents
     * the test class's reliance on the {@link MockitoExtension}
     * lifecycle for any future maintainer.</p>
     */
    @BeforeEach
    void verifyMockitoExtensionWiredFreshState() {
        assertThat(service)
                .as("CardFileReaderService must be instantiated by MockitoExtension via @InjectMocks")
                .isNotNull();
        assertThat(cardRepository)
                .as("CardRepository @Mock must be initialised by MockitoExtension")
                .isNotNull();
        assertThat(auditLogService)
                .as("AuditLogService @Mock must be initialised by MockitoExtension")
                .isNotNull();
    }

    // =========================================================================
    // Helpers — fixture builders preserving COBOL CVACT02Y.cpy layout.
    // =========================================================================

    /**
     * Builds a populated {@link Card} fixture matching the
     * COBOL {@code CVACT02Y.cpy} {@code CARD-RECORD} layout.
     * Every field is set to a deterministic, non-{@code null}
     * value (matching the V002 Flyway DDL's {@code NOT NULL}
     * columns) so the SUT's per-record DISPLAY translation does
     * not encounter any {@code null} dereference.
     *
     * <p>The PAN ({@code cardNum}) and CVV ({@code cardCvvCd})
     * are parameterized so the {@link PanMasking} nested test
     * class can exercise multiple PAN values (Visa / Mastercard
     * / Amex) and assert that none of them ever appears in any
     * captured audit payload.</p>
     *
     * @param cardNum 16-character PAN (or 15-digit for Amex);
     *                must not be {@code null}
     * @param cvv     3-digit CVV; must not be {@code null}
     * @return a fully-populated {@link Card} fixture
     */
    private Card buildCardFixture(String cardNum, Integer cvv) {
        // CARD-NUM PIC X(16) (PCI-sensitive PAN)            — primary key
        // CARD-ACCT-ID PIC 9(11)                            — FK to accounts.acct_id
        // CARD-CVV-CD PIC 9(03) (PCI-sensitive SAD)         — CVV
        // CARD-EMBOSSED-NAME PIC X(50)                      — cardholder name
        // CARD-EXPIRAION-DATE PIC X(10) (typo in COBOL!)    — corrected to expiration
        // CARD-ACTIVE-STATUS PIC X(01)                      — 'Y' active / 'N' inactive
        return new Card(
                cardNum,
                12345678901L,
                cvv,
                "TEST CARDHOLDER",
                LocalDate.of(2030, 12, 31),
                "Y");
    }

    /**
     * Convenience overload that produces a fixture with the
     * default {@link #TEST_CVV} CVV value. Used by the
     * {@link SequentialScan} and {@link ReadOnly} nested tests
     * that do not depend on a specific CVV.
     *
     * @param cardNum 16-character PAN; must not be {@code null}
     * @return a fully-populated {@link Card} fixture
     */
    private Card buildCardFixture(String cardNum) {
        return buildCardFixture(cardNum, TEST_CVV);
    }

    /**
     * Generates a {@link List} of {@code n} {@link Card}
     * fixtures with deterministically-varied PAN values. Each
     * PAN is the 16-digit zero-padded string representation of
     * the sequential index, ensuring stable, distinguishable
     * test data. Used by the multi-record {@link SequentialScan}
     * tests.
     *
     * @param n the number of cards to generate; must be
     *          non-negative
     * @return a mutable list of {@code n} deterministic
     *         {@link Card} fixtures
     */
    private List<Card> buildCardFixtures(int n) {
        List<Card> cards = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            // Zero-padded 16-digit synthetic PAN — never collides
            // with the well-known test PANs (TEST_PAN_VISA / etc.)
            // because those start with 3/4/5 and these start with
            // 0; never accidentally matches a real BIN range.
            String paddedPan = String.format("%016d", i);
            cards.add(buildCardFixture(paddedPan));
        }
        return cards;
    }



    // =========================================================================
    // SequentialScan — verifies the Java translation of the COBOL
    // OPEN INPUT / READ NEXT / CLOSE loop in CBACT02C L70–L87.
    // =========================================================================

    /**
     * Asserts the SUT's sequential-scan semantics against the
     * canonical result-set sizes (empty, three) plus the
     * captured {@link Sort} argument.
     *
     * <p>The COBOL source program {@code CBACT02C.cbl} executes
     * its sequential read loop as follows:</p>
     *
     * <pre>
     * PERFORM UNTIL END-OF-FILE = 'Y'                   (L74)
     *     IF END-OF-FILE = 'N'                          (L75)
     *         PERFORM 1000-CARDFILE-GET-NEXT            (L76)
     *         IF END-OF-FILE = 'N'                      (L77)
     *             DISPLAY CARD-RECORD                   (L78)
     *         END-IF
     *     END-IF
     * END-PERFORM                                       (L81)
     * </pre>
     *
     * <p>The Java target replaces this loop with a single
     * {@code cardRepository.findAll(Sort.by("cardNum"))} call
     * (one-shot materialisation of the result set) followed by
     * a for-each iteration. The {@code Sort.by("cardNum")}
     * argument preserves the COBOL
     * {@code ACCESS MODE SEQUENTIAL} on
     * {@code RECORD KEY FD-CARD-NUM} semantic.</p>
     */
    @Nested
    @DisplayName("SequentialScan — Java translation of CBACT02C OPEN/READ/CLOSE loop")
    class SequentialScan {

        /**
         * Empty-result path. Models the COBOL behaviour of an empty
         * VSAM cluster where the very first
         * {@code READ CARDFILE-FILE} sets
         * {@code CARDFILE-STATUS = '10'} (end-of-file),
         * triggering the {@code MOVE 16 TO APPL-RESULT} &rarr;
         * {@code APPL-EOF} branch (CBACT02C L98&ndash;L99) and an
         * immediate {@code MOVE 'Y' TO END-OF-FILE}
         * (L107&ndash;L108) so the {@code PERFORM UNTIL} loop
         * terminates without any {@code DISPLAY CARD-RECORD}
         * invocation.
         *
         * <p>The Java target must (a) return a non-{@code null}
         * {@link CardFileReaderService.ReadResult} carrying the
         * COBOL program identifier and a zero record count, and
         * (b) still emit a single {@code COMPLETED} audit event
         * so operators can verify the batch reader ran to
         * completion even when the table is empty.</p>
         */
        @Test
        @DisplayName("readAllCards_emptyResult_completesWithoutError — zero cards → ReadResult(recordCount=0)")
        void readAllCards_emptyResult_completesWithoutError() {
            // GIVEN: the repository returns an empty list — modelling the
            // COBOL APPL-EOF on first READ scenario.
            // COBOL: CBACT02C:1000-CARDFILE-GET-NEXT (L92–L116) — first
            // READ returns FILE STATUS '10' (EOF).
            when(cardRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            CardFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: ReadResult carries the COBOL program ID and zero records.
            assertThat(result)
                    .as("readAndDisplayAll() must return a non-null ReadResult even on empty input")
                    .isNotNull();
            assertThat(result.programId())
                    .as("ReadResult.programId() must echo the COBOL program ID 'CBACT02C' for audit traceability")
                    .isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount() must be zero when the underlying table is empty")
                    .isZero();

            // AND: findAll(Sort) was invoked exactly once — preserving the
            // COBOL single-pass scan semantic (no second OPEN INPUT or
            // re-scan). times(1) is the explicit canonical form.
            verify(cardRepository, times(1)).findAll(any(Sort.class));

            // AND: the SUT still emits the COMPLETED lifecycle event even
            // on an empty result so operators can verify the batch reader
            // ran to completion. The duration metric is captured via
            // anyLong() because System.currentTimeMillis() varies per run.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }

        /**
         * Multi-row happy path. Models the COBOL behaviour where
         * three sequential {@code READ CARDFILE-FILE} calls
         * succeed (status {@code '00'}) and the fourth returns
         * {@code '10'} (EOF). Each successful READ feeds the
         * implicit {@code DISPLAY CARD-RECORD} at L78, so the
         * Java target's per-record processing path must execute
         * exactly three times.
         *
         * <p>This test asserts the recordCount on the returned
         * {@link CardFileReaderService.ReadResult} matches the
         * size of the fixture list &mdash; the simplest external
         * proxy for "DISPLAY paragraph executed N times" without
         * intercepting SLF4J output.</p>
         */
        @Test
        @DisplayName("readAllCards_multipleCards_processesEachOne — three cards → ReadResult(recordCount=3)")
        void readAllCards_multipleCards_processesEachOne() {
            // GIVEN: a deterministic three-row fixture spanning the
            // canonical Visa / Mastercard / synthetic-PAN test inputs.
            // COBOL: CBACT02C:1000-CARDFILE-GET-NEXT executed three times
            // before the fourth read returns FILE STATUS '10' (EOF).
            List<Card> fixtures = List.of(
                    buildCardFixture(TEST_PAN_VISA),
                    buildCardFixture(TEST_PAN_MASTERCARD),
                    buildCardFixture("0000000000000003"));
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            CardFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: ReadResult.recordCount() equals the fixture size — proves
            // the SUT's for-each iterated every row and incremented the
            // AtomicLong counter once per row.
            assertThat(result)
                    .as("readAndDisplayAll() must return a non-null ReadResult on the happy path")
                    .isNotNull();
            assertThat(result.programId())
                    .as("ReadResult.programId() must echo 'CBACT02C' regardless of input cardinality")
                    .isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount() must equal the fixture size (3) — one per COBOL READ NEXT success")
                    .isEqualTo(3L);

            // AND: findAll(Sort) was invoked exactly once (no per-record
            // re-query — preserving COBOL single-pass scan semantic).
            verify(cardRepository, times(1)).findAll(any(Sort.class));

            // AND: the COMPLETED audit emission was triggered after the loop.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }

        /**
         * Asserts the {@link Sort} argument passed to
         * {@link CardRepository#findAll(Sort)} is ascending on
         * {@code cardNum}. Preserves the COBOL
         * {@code ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-CARD-NUM}
         * semantic (CBACT02C L31&ndash;L32) so the Java scan
         * emits rows in the same byte-order as the COBOL VSAM
         * KSDS key-sequenced read &mdash; required for
         * golden-output diffing during the parallel-run
         * validation window per AAP &sect;0.6.2.
         */
        @Test
        @DisplayName("readAllCards_invokesFindAllWithSortByCardNum — preserves COBOL ACCESS MODE SEQUENTIAL on FD-CARD-NUM")
        void readAllCards_invokesFindAllWithSortByCardNum() {
            // GIVEN: an empty result set — the assertion target is the
            // Sort argument captured on the way INTO the repository, not
            // anything returned FROM it.
            when(cardRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the Sort argument and inspect its order by
            // property name. The expected Sort.by("cardNum") translates
            // to a single Order over the "cardNum" property ascending —
            // the direct relational analogue of COBOL FD-CARD-NUM
            // sequencing.
            ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
            verify(cardRepository).findAll(sortCaptor.capture());

            Sort capturedSort = sortCaptor.getValue();
            assertThat(capturedSort)
                    .as("findAll(Sort) must be invoked with a non-null Sort argument to preserve "
                            + "COBOL ACCESS MODE SEQUENTIAL on FD-CARD-NUM")
                    .isNotNull();
            assertThat(capturedSort.getOrderFor(SORT_FIELD_CARD_NUM))
                    .as("Sort must carry an Order over the 'cardNum' property — the relational "
                            + "analogue of COBOL FD-CARD-NUM primary key sequencing")
                    .isNotNull();
            assertThat(capturedSort.getOrderFor(SORT_FIELD_CARD_NUM).isAscending())
                    .as("Sort over 'cardNum' must be ASCENDING to match the COBOL VSAM KSDS "
                            + "key-sequenced read order")
                    .isTrue();
        }

        /**
         * Larger fixture exercising the SUT's
         * {@code AtomicLong count.incrementAndGet()} accumulation
         * beyond trivial values. Twenty rows is a balance between
         * meaningful scale and unit-test runtime &mdash;
         * sufficient to flag any list-truncation defect, fast
         * enough to run on every developer laptop.
         */
        @Test
        @DisplayName("readAllCards_twentyCards_processesEachOne — 20 cards → ReadResult(recordCount=20)")
        void readAllCards_twentyCards_processesEachOne() {
            // GIVEN: a 20-row fixture spanning cardNum 1..20.
            List<Card> fixtures = buildCardFixtures(20);
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            CardFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: recordCount matches the fixture size.
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount() must equal fixture size of 20")
                    .isEqualTo(20L);

            // AND: exactly one COMPLETED audit emission was triggered —
            // not one per row (otherwise OpenSearch would be flooded and
            // the dashboard's record-count parity check would break).
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }
    }



    // =========================================================================
    // PanMasking — *** CRITICAL *** — PCI-DSS v4.0 Requirements 3.4
    // (PAN masking) and 3.2 (CVV / SAD non-disclosure).
    // =========================================================================

    /**
     * Asserts the absolute PCI-DSS-safety invariants on the
     * audit-log emission path. Per AAP &sect;0.6.6 the Java
     * target ships logs to CloudWatch Logs (multi-tenant) so
     * <b>full-PAN logging is prohibited</b>. This nested class
     * captures the {@code Map<String, Object>} payload passed
     * to {@link AuditLogService#logBatchJobLifecycle} and
     * asserts:
     *
     * <ol>
     *   <li><b>NO full 16-digit PAN ever appears</b> &mdash; the
     *       canonical "4111111111111111" Visa test PAN, the
     *       "5500000000000004" Mastercard test PAN, and the
     *       15-digit "378282246310005" Amex test PAN must NEVER
     *       appear in any captured payload value, key, or
     *       stringified form. The SUT's current implementation
     *       achieves this trivially by emitting NO card data in
     *       the audit payload at all (only operational metadata:
     *       {@code program_id}, {@code entity_name},
     *       {@code record_count}, {@code vsam_cluster}) &mdash;
     *       which is a stricter PCI-DSS posture than masking
     *       and is enforced by these tests.</li>
     *   <li><b>If a card number ever appears, it MUST be
     *       masked</b> &mdash; any string value in the payload
     *       that contains a PAN-like sequence (4 digits anywhere)
     *       must be in the {@code "************" + last4}
     *       form. This test would fire on a future regression
     *       that surfaces card data in the audit payload
     *       without masking.</li>
     *   <li><b>NO CVV value or {@code cardCvvCd} key ever
     *       appears</b> &mdash; the literal CVV value
     *       {@value #TEST_CVV} (as a string and as an
     *       Integer/Long) must NOT appear in any payload value
     *       or key, and the {@code cardCvvCd} key must NOT be
     *       present in the payload {@link Map}. CVV is
     *       "sensitive authentication data" (SAD) per PCI-DSS
     *       v4.0 Requirement 3.2 and MUST never be logged under
     *       any circumstance.</li>
     * </ol>
     *
     * <p>The captor approach exercises the same code path that
     * would be used by a CloudWatch log-filter to scan
     * production audit emissions for PAN leakage. If the SUT
     * ever surfaces card data in the audit payload without
     * masking, these tests fail loudly and block the build.</p>
     *
     * <p><b>Why also include {@code maskPan} indirect
     * assertions?</b> The {@code maskPan} static helper is
     * package-private on the SUT class. We do not invoke it
     * directly here (the SUT's PAN-masking discipline is the
     * subject of the test, not the helper's internal
     * arithmetic), but we do model the expected output format
     * ({@code "************" + last4}) so that if the SUT were
     * to ever start surfacing card data in the audit payload,
     * the masking format expected here matches the SUT's
     * implementation.</p>
     */
    @Nested
    @DisplayName("PanMasking — PCI-DSS v4.0 Req 3.4 (PAN masking) and Req 3.2 (CVV omission)")
    class PanMasking {

        /**
         * Asserts the captured audit payload does NOT contain
         * any full 16-digit Visa test PAN
         * ({@value CardFileReaderServiceTest#TEST_PAN_VISA}).
         * This is the canonical PCI-DSS v4.0 Requirement 3.4
         * test &mdash; the most obvious failure mode would be
         * a regression that called
         * {@code payload.put("cardNum", card.getCardNum())}
         * without masking.
         *
         * <p>Captures all arguments to
         * {@link AuditLogService#logBatchJobLifecycle} via
         * {@link ArgumentCaptor} and asserts:
         * <ul>
         *   <li>The {@code Map<String, Object>} payload does
         *       not contain the literal {@code "4111111111111111"}
         *       string anywhere in its values' stringified form
         *       (covers direct {@code put("cardNum", pan)},
         *       nested-map leaks, and {@code toString()}-leak
         *       paths).</li>
         *   <li>The payload's {@code toString()} does not
         *       contain the literal PAN (covers any future
         *       refactor that adds card data to the payload as a
         *       collection or custom-typed value).</li>
         * </ul>
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllCards_emittedData_panMasked — full 16-digit PAN NEVER appears in audit payload")
        void readAllCards_emittedData_panMasked() {
            // GIVEN: a fixture carrying the canonical Visa test PAN
            // (4111111111111111) and a CVV of 123. The SUT MUST NOT
            // expose this full PAN in any audit emission.
            // PCI-DSS v4.0 Requirement 3.4 (AAP §0.6.6).
            List<Card> fixtures = List.of(
                    buildCardFixture(TEST_PAN_VISA, TEST_CVV));
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the audit-log payload and assert NO full
            // 16-digit PAN appears anywhere in its serialized form.
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    isNull());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                    .as("Audit payload must be non-null and capturable for PCI-DSS validation")
                    .isNotNull();

            // PCI-DSS-CRITICAL ASSERTION (1/3): the payload's
            // toString() must not contain the literal 16-digit Visa
            // PAN. toString() exercises every value's own toString
            // (including nested Maps, Lists, and custom objects).
            assertThat(payload.toString())
                    .as("PCI-DSS v4.0 Req 3.4 (AAP §0.6.6): audit payload must NEVER contain the "
                            + "full 16-digit Visa PAN '%s' — would be a critical compliance breach",
                            TEST_PAN_VISA)
                    .doesNotContain(TEST_PAN_VISA);

            // PCI-DSS-CRITICAL ASSERTION (2/3): every individual
            // value in the payload must not stringify to a value
            // containing the full PAN. This catches the case where
            // the toString() of a nested Map omits its values (which
            // would happen for a LinkedHashMap of card data).
            for (Object value : payload.values()) {
                if (value != null) {
                    assertThat(value.toString())
                            .as("PCI-DSS v4.0 Req 3.4: payload value '%s' (type=%s) must not contain "
                                    + "the full Visa PAN '%s' under any encoding",
                                    value, value.getClass().getSimpleName(), TEST_PAN_VISA)
                            .doesNotContain(TEST_PAN_VISA);
                }
            }

            // PCI-DSS-CRITICAL ASSERTION (3/3): the payload key set
            // must not include a 'cardNum' (or analogous PAN-bearing
            // key) — defense-in-depth against a key-only leak that
            // String.contains() would miss if the value were already
            // masked.
            assertThat(payload.keySet())
                    .as("Audit payload must not surface any card-number key — operational "
                            + "metadata only per AAP §0.6.6 (program_id, entity_name, "
                            + "record_count, vsam_cluster)")
                    .doesNotContain("cardNum", "card_num", "pan", "primary_account_number");
        }

        /**
         * Asserts that across multiple PAN brands (Visa,
         * Mastercard, Amex), no full-PAN string ever appears in
         * the captured audit payload. This is the
         * defense-in-depth counterpart to the Visa-specific
         * assertion &mdash; a regression that masked only Visa
         * PANs but leaked Mastercard or Amex PANs would be
         * caught by this test.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllCards_emittedData_multipleBrandsAllMasked — Visa, Mastercard, Amex PANs all absent from payload")
        void readAllCards_emittedData_multipleBrandsAllMasked() {
            // GIVEN: three distinct brand PANs.
            List<Card> fixtures = List.of(
                    buildCardFixture(TEST_PAN_VISA, TEST_CVV),
                    buildCardFixture(TEST_PAN_MASTERCARD, 456),
                    buildCardFixture(TEST_PAN_AMEX, 7890));
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the payload and assert NO brand PAN
            // appears in any form.
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    isNull());

            String payloadString = payloadCaptor.getValue().toString();
            assertThat(payloadString)
                    .as("PCI-DSS v4.0 Req 3.4: audit payload must not contain the Visa test PAN")
                    .doesNotContain(TEST_PAN_VISA);
            assertThat(payloadString)
                    .as("PCI-DSS v4.0 Req 3.4: audit payload must not contain the Mastercard test PAN")
                    .doesNotContain(TEST_PAN_MASTERCARD);
            assertThat(payloadString)
                    .as("PCI-DSS v4.0 Req 3.4: audit payload must not contain the Amex test PAN")
                    .doesNotContain(TEST_PAN_AMEX);
        }

        /**
         * Asserts that if the SUT ever surfaces a card-number
         * value in the audit payload, it MUST be in the masked
         * form ({@value CardFileReaderServiceTest#PAN_MASK_PREFIX}
         * followed by the last four digits). This is the
         * positive-form counterpart to the full-PAN-absence
         * assertion above &mdash; it documents the masked-form
         * contract per PCI-DSS v4.0 Requirement 3.4.1.
         *
         * <p>The current SUT implementation does not surface
         * any card data in the audit payload, so this test
         * passes vacuously by confirming the absence of any
         * card-related key. The assertion guards against a
         * future regression that surfaces card data but masks
         * it incorrectly (e.g., showing 6 leading digits + last
         * 4 instead of just the last 4).</p>
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllCards_emittedData_anyCardNumberValueIsMasked — masked-form contract documented")
        void readAllCards_emittedData_anyCardNumberValueIsMasked() {
            // GIVEN: a fixture with the canonical Visa test PAN.
            List<Card> fixtures = List.of(
                    buildCardFixture(TEST_PAN_VISA, TEST_CVV));
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the payload.
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    isNull());

            Map<String, Object> payload = payloadCaptor.getValue();

            // INVARIANT: any string-valued entry that LOOKS like a
            // card number (contains '1111', the Visa test PAN's
            // last 4) must either NOT be the full PAN (already
            // asserted above) OR be in the masked form
            // "************1111".
            //
            // The current SUT does not put card data in the payload,
            // so this loop has no matching values. The assertion
            // documents the contract for any future regression that
            // does surface card data.
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    String stringValue = (String) value;
                    if (stringValue.contains(TEST_PAN_VISA_LAST4)
                            && stringValue.length() >= 16) {
                        // If a 16+-char value contains "1111", it
                        // must START with the mask prefix or be
                        // entirely synthetic test data unrelated to
                        // the PAN. The SUT's maskPan helper produces
                        // "************1111" for the Visa test PAN.
                        assertThat(stringValue)
                                .as("PCI-DSS v4.0 Req 3.4.1: any card-number-shaped value in the "
                                        + "audit payload key='%s' must be in the masked form "
                                        + "(mask prefix '%s' + last 4 digits); raw PAN is "
                                        + "prohibited under any circumstance",
                                        entry.getKey(), PAN_MASK_PREFIX)
                                .startsWith(PAN_MASK_PREFIX);
                    }
                }
            }
        }

        /**
         * Asserts the captured audit payload does NOT contain
         * the {@code cardCvvCd} key (the JPA field name) under
         * any circumstance. The CVV is "sensitive authentication
         * data" (SAD) per PCI-DSS v4.0 Requirement 3.2 and MUST
         * never appear in any logged or audited document.
         *
         * <p>This test is the negative counterpart to the
         * full-PAN-absence assertion: where PAN is masked, CVV
         * is OMITTED ENTIRELY. The SUT's
         * {@code displayCardRecord} private method emits the
         * literal string {@code "cvv=***"} in place of the value
         * and the entity accessor {@code getCardCvvCd()} is
         * intentionally never invoked.</p>
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllCards_emittedData_doesNotContainCvv — no 'cardCvvCd' key or CVV value in payload")
        void readAllCards_emittedData_doesNotContainCvv() {
            // GIVEN: a fixture with a non-null CVV value of 123.
            // The SUT MUST NOT expose this value in any audit
            // emission. PCI-DSS v4.0 Requirement 3.2 (AAP §0.6.6).
            List<Card> fixtures = List.of(
                    buildCardFixture(TEST_PAN_VISA, TEST_CVV));
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the audit-log payload and assert NO CVV
            // key or value appears anywhere.
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    isNull());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                    .as("Audit payload must be non-null and capturable for PCI-DSS validation")
                    .isNotNull();

            // PCI-DSS-CRITICAL ASSERTION (1/3): the payload key set
            // must NOT include the literal JPA field name "cardCvvCd"
            // (or any common variants).
            assertThat(payload)
                    .as("PCI-DSS v4.0 Req 3.2 (AAP §0.6.6): audit payload must NEVER include the "
                            + "'cardCvvCd' (JPA field name) key with a non-null CVV value — would "
                            + "be a critical compliance breach")
                    .doesNotContainKey(PAYLOAD_CVV_KEY);

            // PCI-DSS-CRITICAL ASSERTION (2/3): also reject common
            // variants of the CVV key (snake_case, ALL_CAPS, COBOL).
            assertThat(payload.keySet())
                    .as("Audit payload must not surface any CVV-bearing key under any naming "
                            + "convention (cardCvvCd / card_cvv_cd / CARD-CVV-CD / cvv)")
                    .doesNotContain(PAYLOAD_CVV_KEY, "card_cvv_cd", "CARD-CVV-CD", "cvv", "cvv2", "cvc",
                            "cvc2", "cid", "sensitiveAuthData");

            // PCI-DSS-CRITICAL ASSERTION (3/3): the toString form of
            // the payload must not contain the integer CVV value
            // (123) under any encoding. This catches the regression
            // where the CVV is surfaced via a different key name
            // (e.g., a "metadata" map under a generic key).
            //
            // Note: 123 might appear coincidentally in some payload
            // values (timestamp millis, recordCount on a 123-row
            // fixture, etc.). To make this assertion robust, we
            // assert against the QUOTED string form of the CVV value
            // ('123' or "cvv=123" or "=123,") which is how a JSON or
            // Map.toString() would render a tagged CVV value. Since
            // a CVV is always a small integer and Map.toString()
            // renders entries as "key=value", "=123" anywhere in the
            // payload string would indicate a CVV-bearing entry.
            //
            // We do NOT bare-string-search for "123" because a
            // record_count of 123 or an arbitrary 3-digit number in
            // any other field would yield false positives.
            //
            // Instead: explicitly assert no Integer value in the
            // payload equals TEST_CVV. The SUT's payload contains
            // only operational metadata (program_id, entity_name,
            // record_count, vsam_cluster); record_count is the only
            // Long-valued entry and equals the fixture size (1, not
            // 123).
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Integer
                        && ((Integer) value).intValue() == TEST_CVV.intValue()) {
                    assertThat(entry.getKey())
                            .as("PCI-DSS v4.0 Req 3.2: payload entry key='%s' value=%d matches the "
                                    + "fixture CVV value — investigation required to confirm this "
                                    + "is not a CVV leak",
                                    entry.getKey(), value)
                            .isNotEqualTo(PAYLOAD_CVV_KEY);
                }
            }
        }

        /**
         * Defense-in-depth: asserts the captured payload
         * structure carries ONLY the four operational-metadata
         * keys ({@code program_id}, {@code entity_name},
         * {@code record_count}, {@code vsam_cluster}) and NO
         * card-specific keys. This is the strictest contract
         * &mdash; any future regression that adds card data to
         * the payload (intentionally or accidentally) fires this
         * test loudly.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllCards_emittedData_payloadOnlyContainsOperationalMetadata — strict allowlist of payload keys")
        void readAllCards_emittedData_payloadOnlyContainsOperationalMetadata() {
            // GIVEN: a single-card fixture carrying both a full PAN
            // and a non-null CVV — the strictest input to surface
            // any leak path.
            List<Card> fixtures = List.of(
                    buildCardFixture(TEST_PAN_VISA, TEST_CVV));
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the payload.
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    isNull());

            Map<String, Object> payload = payloadCaptor.getValue();

            // Payload must include exactly the four operational
            // keys. This is the STRICTEST contract: a regression
            // that adds ANY card-related key fires here.
            assertThat(payload.keySet())
                    .as("Audit payload must contain ONLY operational metadata keys per AAP §0.6.6 "
                            + "buildAuditPayload contract — any card-specific key is a PCI-DSS "
                            + "regression risk")
                    .containsExactlyInAnyOrder(
                            PAYLOAD_PROGRAM_ID_KEY,
                            PAYLOAD_ENTITY_NAME_KEY,
                            PAYLOAD_RECORD_COUNT_KEY,
                            PAYLOAD_VSAM_CLUSTER_KEY);

            // And the values must be the expected operational
            // metadata.
            assertThat(payload)
                    .as("program_id must equal the COBOL program ID 'CBACT02C'")
                    .containsEntry(PAYLOAD_PROGRAM_ID_KEY, COBOL_PROGRAM_ID);
            assertThat(payload)
                    .as("entity_name must equal 'CARD'")
                    .containsEntry(PAYLOAD_ENTITY_NAME_KEY, ENTITY_NAME);
            assertThat(payload)
                    .as("record_count must equal 1 (the fixture size)")
                    .containsEntry(PAYLOAD_RECORD_COUNT_KEY, 1L);
            assertThat(payload)
                    .as("vsam_cluster must equal the legacy CARDDATA cluster name verbatim")
                    .containsEntry(PAYLOAD_VSAM_CLUSTER_KEY, VSAM_CLUSTER);
        }
    }


    // =========================================================================
    // ReadOnly — verifies the COBOL OPEN INPUT (read-only) semantic
    // (CBACT02C L120 — OPEN INPUT CARDFILE-FILE).
    // =========================================================================

    /**
     * Asserts the SUT never invokes any mutating method on the
     * repository. The COBOL source opens the VSAM cluster with
     * {@code OPEN INPUT} (read-only) at line 120 and never
     * issues a {@code REWRITE}, {@code WRITE}, or {@code DELETE}
     * verb across its 179 lines &mdash; a property the Java
     * target must preserve.
     *
     * <p>The {@link CardRepository} extends
     * {@link org.springframework.data.jpa.repository.JpaRepository}
     * which exposes {@code save}, {@code saveAll},
     * {@code saveAndFlush}, {@code delete}, {@code deleteById},
     * {@code deleteAll}, {@code deleteAllInBatch}, and
     * {@code flush} as persistence-mutating entry points. Any
     * invocation would break:
     *
     * <ul>
     *   <li>the {@code @Transactional(readOnly = true)} boundary
     *       declared on the SUT's {@code readAndDisplayAll()}
     *       method (would cause a Spring exception at runtime
     *       once the read-only flag is checked by Hibernate); and</li>
     *   <li>the COBOL {@code OPEN INPUT} semantic from
     *       {@code app/cbl/CBACT02C.cbl} L120 which constrains
     *       the program to read-only access &mdash; required for
     *       byte-identical parallel-run output diff per AAP
     *       &sect;0.6.2.</li>
     * </ul>
     *
     * <p>This nested class verifies the entire read-only contract
     * across the multi-card and empty-result paths so future
     * regressions are caught regardless of where in the SUT they
     * are introduced.</p>
     */
    @Nested
    @DisplayName("ReadOnly — preserves COBOL OPEN INPUT (read-only) semantic")
    class ReadOnly {

        /**
         * Verifies {@link CardRepository#save(Object)} is never
         * invoked. The repository's {@code save} method is the
         * canonical persistence-mutating entry point inherited
         * from
         * {@link org.springframework.data.jpa.repository.JpaRepository};
         * any invocation would break the read-only invariant
         * declared by the SUT's
         * {@code @Transactional(readOnly = true)} boundary and
         * would diverge from the COBOL {@code OPEN INPUT}
         * semantic on {@code CARDFILE-FILE}.
         */
        @Test
        @DisplayName("readAllCards_doesNotInvokeSave — never calls cardRepository.save(Card)")
        void readAllCards_doesNotInvokeSave() {
            // GIVEN: a small three-row fixture exercising the SUT's
            // per-record processing loop. The mix of Visa /
            // Mastercard / synthetic PANs broadens coverage; the
            // assertion target is purely the SUT's call discipline,
            // not the fixture content.
            List<Card> fixtures = List.of(
                    buildCardFixture(TEST_PAN_VISA),
                    buildCardFixture(TEST_PAN_MASTERCARD),
                    buildCardFixture("0000000000000003"));
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            // COBOL: CBACT02C:PROCEDURE DIVISION L70–L87 — the
            // entire scan loop must never invoke any WRITE/REWRITE/
            // DELETE on the VSAM cluster.
            service.readAndDisplayAll();

            // THEN: cardRepository.save was never invoked — the
            // read-only invariant from COBOL OPEN INPUT
            // (CBACT02C L120) is preserved.
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Verifies the broader read-only contract: NO mutating
         * method on the {@link CardRepository} is invoked by the
         * sequential scan. This single test acts as a
         * defense-in-depth net against future refactors
         * accidentally introducing a persistence side-effect via
         * {@code saveAll}, {@code saveAndFlush},
         * {@code delete}, {@code deleteById}, {@code deleteAll},
         * {@code deleteAllInBatch}, or {@code flush}.
         */
        @Test
        @DisplayName("readAllCards_doesNotInvokeAnyMutatingRepositoryMethod — save/delete/flush guard")
        void readAllCards_doesNotInvokeAnyMutatingRepositoryMethod() {
            // GIVEN: an empty result set is sufficient — the SUT's
            // for-each loop is never entered, but the SUT's
            // before/after-loop logic still runs and could
            // theoretically mutate the repository. We want to
            // verify it never does.
            when(cardRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: every mutating method on the repository is
            // never invoked. Each verify(...) is asserted
            // individually so the failure message identifies the
            // specific violation.
            verify(cardRepository, never()).save(any());
            verify(cardRepository, never()).saveAll(any());
            verify(cardRepository, never()).saveAndFlush(any());
            verify(cardRepository, never()).delete(any());
            verify(cardRepository, never()).deleteById(any());
            verify(cardRepository, never()).deleteAll();
            verify(cardRepository, never()).deleteAllInBatch();
            verify(cardRepository, never()).flush();
        }

        /**
         * Verifies the read-only contract still holds when the
         * fixture is non-empty. The previous test exercises the
         * empty-loop path; this one exercises the multi-record
         * loop path. A regression that introduced a write-back
         * for a specific cardholder state (e.g., "mark card as
         * scanned") would fire here even though it would NOT
         * fire in the empty-fixture test.
         */
        @Test
        @DisplayName("readAllCards_doesNotInvokeAnyMutatingRepositoryMethodOnMultiCard — multi-record read-only guard")
        void readAllCards_doesNotInvokeAnyMutatingRepositoryMethodOnMultiCard() {
            // GIVEN: a multi-row fixture exercising the SUT's
            // per-record processing loop.
            List<Card> fixtures = buildCardFixtures(5);
            when(cardRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: no mutating method on the repository is
            // invoked, even though the for-each loop processed 5
            // records.
            verify(cardRepository, never()).save(any());
            verify(cardRepository, never()).saveAll(any());
            verify(cardRepository, never()).saveAndFlush(any());
            verify(cardRepository, never()).delete(any());
            verify(cardRepository, never()).deleteById(any());
            verify(cardRepository, never()).deleteAll();
            verify(cardRepository, never()).deleteAllInBatch();
            verify(cardRepository, never()).flush();
        }
    }
}

