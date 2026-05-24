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
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
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
 * {@link XrefFileReaderService} &mdash; the Java {@code @Service}
 * translation of the COBOL batch program
 * {@code app/cbl/CBACT03C.cbl} ("Read and print account cross reference
 * data file.").
 *
 * <h2>COBOL source provenance (AAP &sect;0.4.1 / &sect;0.7.3)</h2>
 *
 * <p>{@link XrefFileReaderService} is the Java target for
 * {@code CBACT03C} per the AAP &sect;0.4.1 file-by-file transformation
 * plan (Batch COBOL Programs &rarr; Spring Batch Job + Service Classes
 * table). The source program executes:</p>
 *
 * <pre>
 * IDENTIFICATION DIVISION.
 * PROGRAM-ID.    CBACT03C.
 * ...
 * SELECT XREFFILE-FILE ASSIGN TO XREFFILE
 *        ORGANIZATION IS INDEXED
 *        ACCESS MODE  IS SEQUENTIAL
 *        RECORD KEY   IS FD-XREF-CARD-NUM
 *        FILE STATUS  IS XREFFILE-STATUS.
 * ...
 * PROCEDURE DIVISION.
 *     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.
 *     PERFORM 0000-XREFFILE-OPEN.
 *     PERFORM UNTIL END-OF-FILE = 'Y'
 *         IF END-OF-FILE = 'N'
 *             PERFORM 1000-XREFFILE-GET-NEXT
 *             IF END-OF-FILE = 'N'
 *                 DISPLAY CARD-XREF-RECORD
 *             END-IF
 *         END-IF
 *     END-PERFORM.
 *     PERFORM 9000-XREFFILE-CLOSE.
 *     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.
 *     GOBACK.
 * </pre>
 *
 * <p>The COBOL record layout is defined in
 * {@code app/cpy/CVACT03Y.cpy} (50-byte {@code CARD-XREF-RECORD},
 * comprising the 16-character {@code XREF-CARD-NUM} primary key
 * ({@code PIC X(16)} &mdash; the PAN, cardholder data per PCI-DSS
 * v4.0), the 9-digit {@code XREF-CUST-ID} ({@code PIC 9(09)}), the
 * 11-digit {@code XREF-ACCT-ID} ({@code PIC 9(11)} &mdash; the
 * alternate-index key on which the VSAM {@code CXACAIX} AIX is
 * defined), and a trailing 14-byte {@code FILLER PIC X(14)} omitted
 * in the relational schema).</p>
 *
 * <h2>Behavioural invariants under test (AAP &sect;0.7.1, &sect;0.7.3)</h2>
 *
 * <ul>
 *   <li><b>Sequential read of all cross-reference rows</b> &mdash;
 *       preserves the COBOL {@code OPEN INPUT / READ NEXT / CLOSE}
 *       loop on {@code RECORD KEY FD-XREF-CARD-NUM} (CBACT03C
 *       L29&ndash;L33, L70&ndash;L87). The Java target invokes
 *       {@code cardCrossReferenceRepository.findAll(Sort.by("xrefCardNum"))}
 *       exactly once per execution.</li>
 *   <li><b>PAN masking on emission</b> (CRITICAL &mdash; PCI-DSS
 *       v4.0 Requirement 3.4.1) &mdash; the COBOL source
 *       {@code DISPLAY CARD-XREF-RECORD} at line 78 emits the full
 *       16-digit PAN to SYSOUT (operator console). The Java target
 *       ships logs to CloudWatch Logs (multi-tenant), so the SUT's
 *       {@code displayXrefRecord} private method masks the PAN via
 *       {@code maskPan} to {@code "************" + last4} before
 *       SLF4J emission, and the audit payload sent to
 *       {@link AuditLogService#logBatchJobLifecycle} contains NO
 *       card data at all (only operational metadata:
 *       {@code program_id}, {@code entity_name},
 *       {@code record_count}, {@code vsam_cluster}). This test
 *       class verifies the PCI-DSS-safety invariant that NO full
 *       16-digit PAN ever appears in any captured audit payload
 *       under any circumstance.</li>
 *   <li><b>Read-only contract</b> &mdash; the COBOL source opens
 *       the VSAM cluster with {@code OPEN INPUT} (read-only) at
 *       line 120 and never issues a {@code REWRITE}, {@code WRITE},
 *       or {@code DELETE} verb across its 179 lines. The Java
 *       target must preserve this property: no
 *       {@link CardCrossReferenceRepository#save(Object)} or other
 *       persistence-mutating call may occur during the sequential
 *       scan.</li>
 *   <li><b>Audit emission discipline</b> &mdash; the Java target
 *       emits exactly one {@code COMPLETED} batch-lifecycle event
 *       via {@link AuditLogService#logBatchJobLifecycle} after the
 *       loop terminates, with the COBOL program identifier
 *       ({@code "CBACT03C"}) as {@code jobName}, the
 *       {@code STATUS_COMPLETED} marker, the non-negative duration
 *       in milliseconds, the operational-metadata-only payload,
 *       and a {@code null} correlation ID (the SUT does not source
 *       a correlation ID from MDC).</li>
 * </ul>
 *
 * <h2>Mocking strategy (AAP &sect;0.7.2 testing approach)</h2>
 *
 * <p>{@link MockitoExtension} wires the two collaborators
 * ({@link CardCrossReferenceRepository}, {@link AuditLogService}) as
 * Mockito mocks and instantiates the SUT via the canonical
 * two-argument constructor
 * {@code XrefFileReaderService(CardCrossReferenceRepository, AuditLogService)}
 * with both mocks injected. No Spring {@code ApplicationContext}
 * is loaded &mdash; this is a pure unit test per the AAP testing
 * approach.</p>
 *
 * <p>{@link CardCrossReferenceRepository#findAll(Sort)} is stubbed
 * with {@code when(...).thenReturn(List.of(...))} to drive the
 * empty / single-row / multi-row scenarios. The
 * {@link AuditLogService#logBatchJobLifecycle} call sites are
 * verified via {@link ArgumentCaptor} so the {@link PanMasking}
 * nested class can inspect the payload {@link Map} for the PCI-DSS
 * safety invariants.</p>
 *
 * <h2>Fixture provenance</h2>
 *
 * <p>Fixture {@link CardCrossReference} instances are built via
 * {@link #buildXrefFixture(String)} which constructs realistic
 * 16-digit PAN cross-references via the all-args
 * {@link CardCrossReference} constructor. The "test" card brand
 * identifier ranges ("4111111111111111", a well-known Visa test
 * PAN; "5500000000000004", a well-known Mastercard test PAN) are
 * used so the PAN-masking assertions are unambiguously checking
 * known full-PAN strings against the captured payload.</p>
 *
 * @see XrefFileReaderService the system under test
 * @see CardCrossReference the JPA entity built as test fixtures
 * @see CardCrossReferenceRepository the JPA repository mocked for sequential-scan stubs
 * @see AuditLogService the audit adapter mocked for completion-event assertions
 */
// COBOL: CBACT03C:READ-XREF — unit test for the Java translation of
//        the sequential read-and-display loop in app/cbl/CBACT03C.cbl
//        with PCI-DSS PAN masking and read-only invariant assertions.
@ExtendWith(MockitoExtension.class)
@DisplayName("XrefFileReaderService — CBACT03C with PAN masking")
class XrefFileReaderServiceTest {

    // =========================================================================
    // Constants — mirror the COBOL source verbatim per AAP §0.7.3
    // "Inline traceability comments" rule and the SUT's private
    // constants for direct payload assertions.
    // =========================================================================

    /**
     * The COBOL program identifier preserved verbatim from
     * {@code app/cbl/CBACT03C.cbl} {@code PROGRAM-ID. CBACT03C}.
     * Used as the audit-event {@code jobName} dimension. AAP
     * &sect;0.7.3 mandates verbatim preservation of the COBOL
     * program ID across every artefact in the migration so that
     * operators can correlate Java service emissions to their
     * original mainframe source.
     */
    private static final String COBOL_PROGRAM_ID = "CBACT03C";

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
    private static final String ENTITY_NAME = "CARD_XREF";

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
     * ({@code app/jcl/XREFFILE.jcl}). Carried in the audit
     * payload for cross-referencing only &mdash; the cluster
     * does not exist on the AWS target.
     */
    private static final String VSAM_CLUSTER = "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS";

    /**
     * The Spring Data JPA {@link Sort} field name used by the
     * SUT to preserve the COBOL {@code ACCESS MODE SEQUENTIAL}
     * on {@code RECORD KEY FD-XREF-CARD-NUM} semantic. The Java
     * entity property {@code xrefCardNum} maps to the
     * {@code xref_card_num} relational column &mdash; the primary
     * key of the {@code card_xref} table.
     */
    private static final String SORT_FIELD_XREF_CARD_NUM = "xrefCardNum";

    /**
     * The literal PAN-mask prefix &mdash; twelve {@code '*'}
     * characters. Matches the SUT's {@code MASKED_PAN_FALLBACK}
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
     * for non-production use. Used to drive the multi-row
     * fixture so the {@link SequentialScan} and
     * {@link PanMasking} nested tests exercise more than one
     * brand and the PAN-leak invariants apply to ALL captured
     * PANs.
     */
    private static final String TEST_PAN_MASTERCARD = "5500000000000004";

    /**
     * A third test PAN (Amex range, 15 digits). Despite its
     * 15-digit length (Amex PANs are 15 digits, not 16), the
     * SUT's {@code maskPan} helper must mask it to the prefix
     * {@link #PAN_MASK_PREFIX} plus the last 4 digits. Used to
     * verify the mask helper handles non-16-digit inputs
     * without leaking any digits.
     */
    private static final String TEST_PAN_AMEX = "378282246310005";

    /**
     * Canonical fixture customer ID for the
     * {@link CardCrossReference#xrefCustId} field
     * ({@code XREF-CUST-ID PIC 9(09)} per
     * {@code app/cpy/CVACT03Y.cpy} L6). Fits within the 9-digit
     * range (0..999,999,999) per the COBOL declaration.
     */
    private static final Long TEST_CUST_ID = 123456789L;

    /**
     * Canonical fixture account ID for the
     * {@link CardCrossReference#xrefAcctId} field
     * ({@code XREF-ACCT-ID PIC 9(11)} per
     * {@code app/cpy/CVACT03Y.cpy} L7). Fits within the 11-digit
     * range (0..99,999,999,999) per the COBOL declaration. This
     * is also the alternate-index target field replaced by the
     * PostgreSQL secondary index {@code idx_cardxref_acct_id}
     * (V004) per AAP &sect;0.6.2.
     */
    private static final Long TEST_ACCT_ID = 12345678901L;

    // =========================================================================
    // Mocks and SUT — wired by MockitoExtension before each test method.
    // =========================================================================

    /**
     * Spring Data JPA repository mock. Stubbed via
     * {@code when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(...)}
     * to drive the empty / single-row / multi-row scenarios.
     * The {@link ReadOnly} nested class additionally verifies
     * that no mutating method ({@code save}, {@code delete},
     * etc.) is ever invoked, preserving the COBOL
     * {@code OPEN INPUT} (read-only) semantic from
     * {@code app/cbl/CBACT03C.cbl} L29&ndash;L33 and L120.
     */
    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Audit-log adapter mock. Stubbing is not required because
     * the SUT does not consume any return value from
     * {@link AuditLogService#logBatchJobLifecycle}; the
     * {@link PanMasking} nested class verifies the call
     * arguments via {@link ArgumentCaptor} to assert the PCI-DSS
     * payload safety invariant (no full PAN in payload).
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test. {@link InjectMocks @InjectMocks}
     * instantiates {@link XrefFileReaderService} via the
     * canonical two-argument constructor
     * {@code XrefFileReaderService(CardCrossReferenceRepository, AuditLogService)}
     * and wires both {@link Mock @Mock} fields above.
     */
    @InjectMocks
    private XrefFileReaderService service;

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
                .as("XrefFileReaderService must be instantiated by MockitoExtension via @InjectMocks")
                .isNotNull();
        assertThat(cardCrossReferenceRepository)
                .as("CardCrossReferenceRepository @Mock must be initialised by MockitoExtension")
                .isNotNull();
        assertThat(auditLogService)
                .as("AuditLogService @Mock must be initialised by MockitoExtension")
                .isNotNull();
    }

    // =========================================================================
    // Helpers — fixture builders preserving COBOL CVACT03Y.cpy layout.
    // =========================================================================

    /**
     * Builds a populated {@link CardCrossReference} fixture
     * matching the COBOL {@code CVACT03Y.cpy}
     * {@code CARD-XREF-RECORD} layout. Every field is set to a
     * deterministic, non-{@code null} value (matching the V004
     * Flyway DDL's {@code NOT NULL} columns) so the SUT's
     * per-record DISPLAY translation does not encounter any
     * {@code null} dereference.
     *
     * <p>The PAN ({@code xrefCardNum}) is parameterized so the
     * {@link PanMasking} nested test class can exercise multiple
     * PAN values (Visa / Mastercard / Amex) and assert that
     * none of them ever appears in any captured audit payload.</p>
     *
     * <p>The customer ID and account ID are set to the canonical
     * {@link #TEST_CUST_ID} and {@link #TEST_ACCT_ID} fixture
     * values; the fixture exercises the structural fields, not
     * their cross-referential semantics (which are the concern
     * of {@code AccountViewServiceTest} /
     * {@code TransactionAddServiceTest} /
     * {@code TransactionPostingServiceTest}).</p>
     *
     * @param xrefCardNum 16-character PAN (or 15-digit for Amex);
     *                    must not be {@code null}
     * @return a fully-populated {@link CardCrossReference} fixture
     */
    private CardCrossReference buildXrefFixture(String xrefCardNum) {
        // COBOL: CVACT03Y.cpy L4-L8 — 01 CARD-XREF-RECORD with:
        //   05 XREF-CARD-NUM PIC X(16)   (PCI-sensitive PAN, primary key)
        //   05 XREF-CUST-ID  PIC 9(09)   (FK to customers.cust_id)
        //   05 XREF-ACCT-ID  PIC 9(11)   (FK to accounts.acct_id; AIX target)
        //   05 FILLER        PIC X(14)   (omitted in relational schema)
        return new CardCrossReference(
                xrefCardNum,
                TEST_CUST_ID,
                TEST_ACCT_ID);
    }

    /**
     * Convenience overload that builds a {@link CardCrossReference}
     * fixture with explicit customer and account IDs. Used by
     * tests that need to exercise multi-row fixtures with
     * distinct foreign-key values to mimic a realistic
     * cross-reference table.
     *
     * @param xrefCardNum 16-character PAN; must not be
     *                    {@code null}
     * @param xrefCustId  9-digit customer FK; must not be
     *                    {@code null}
     * @param xrefAcctId  11-digit account FK; must not be
     *                    {@code null}
     * @return a fully-populated {@link CardCrossReference} fixture
     */
    private CardCrossReference buildXrefFixture(String xrefCardNum,
                                                Long xrefCustId,
                                                Long xrefAcctId) {
        return new CardCrossReference(xrefCardNum, xrefCustId, xrefAcctId);
    }

    /**
     * Generates a {@link List} of {@code n}
     * {@link CardCrossReference} fixtures with deterministically
     * varied PAN values. Each PAN is the 16-digit zero-padded
     * string representation of the sequential index, ensuring
     * stable, distinguishable test data. Used by the multi-row
     * {@link SequentialScan} tests.
     *
     * @param n the number of cross-reference rows to generate;
     *          must be non-negative
     * @return a mutable list of {@code n} deterministic
     *         {@link CardCrossReference} fixtures
     */
    private List<CardCrossReference> buildXrefFixtures(int n) {
        List<CardCrossReference> xrefs = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            // Zero-padded 16-digit synthetic PAN — never collides
            // with the well-known test PANs (TEST_PAN_VISA / etc.)
            // because those start with 3/4/5 and these start with
            // 0; never accidentally matches a real BIN range.
            String paddedPan = String.format("%016d", i);
            xrefs.add(buildXrefFixture(paddedPan));
        }
        return xrefs;
    }

    // =========================================================================
    // SequentialScan — verifies the Java translation of the COBOL
    // OPEN INPUT / READ NEXT / CLOSE loop in CBACT03C L70–L87.
    // =========================================================================

    /**
     * Asserts the SUT's sequential-scan semantics against the
     * canonical result-set sizes (empty, three) plus the
     * captured {@link Sort} argument.
     *
     * <p>The COBOL source program {@code CBACT03C.cbl} executes
     * its sequential read loop as follows:</p>
     *
     * <pre>
     * PERFORM UNTIL END-OF-FILE = 'Y'                   (L74)
     *     IF END-OF-FILE = 'N'                          (L75)
     *         PERFORM 1000-XREFFILE-GET-NEXT            (L76)
     *         IF END-OF-FILE = 'N'                      (L77)
     *             DISPLAY CARD-XREF-RECORD              (L78)
     *         END-IF
     *     END-IF
     * END-PERFORM                                       (L81)
     * </pre>
     *
     * <p>The Java target replaces this loop with a single
     * {@code cardCrossReferenceRepository.findAll(Sort.by("xrefCardNum"))}
     * call (one-shot materialisation of the result set)
     * followed by a for-each iteration. The
     * {@code Sort.by("xrefCardNum")} argument preserves the
     * COBOL {@code ACCESS MODE SEQUENTIAL} on
     * {@code RECORD KEY FD-XREF-CARD-NUM} semantic.</p>
     */
    @Nested
    @DisplayName("SequentialScan — Java translation of CBACT03C OPEN/READ/CLOSE loop")
    class SequentialScan {

        /**
         * Empty-result path. Models the COBOL behaviour of an empty
         * VSAM cluster where the very first
         * {@code READ XREFFILE-FILE} sets
         * {@code XREFFILE-STATUS = '10'} (end-of-file),
         * triggering the {@code MOVE 16 TO APPL-RESULT} &rarr;
         * {@code APPL-EOF} branch (CBACT03C L98&ndash;L99) and an
         * immediate {@code MOVE 'Y' TO END-OF-FILE}
         * (L107&ndash;L108) so the {@code PERFORM UNTIL} loop
         * terminates without any {@code DISPLAY CARD-XREF-RECORD}
         * invocation.
         *
         * <p>The Java target must (a) return a non-{@code null}
         * {@link XrefFileReaderService.ReadResult} carrying the
         * COBOL program identifier and a zero record count, and
         * (b) still emit a single {@code COMPLETED} audit event
         * so operators can verify the batch reader ran to
         * completion even when the table is empty.</p>
         */
        @Test
        @DisplayName("readAllXref_emptyResult_completesWithoutError — zero rows → ReadResult(recordCount=0)")
        void readAllXref_emptyResult_completesWithoutError() {
            // GIVEN: the repository returns an empty list — modelling the
            // COBOL APPL-EOF on first READ scenario.
            // COBOL: CBACT03C:1000-XREFFILE-GET-NEXT (L92–L116) — first
            // READ returns FILE STATUS '10' (EOF).
            when(cardCrossReferenceRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            XrefFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: ReadResult carries the COBOL program ID and zero records.
            assertThat(result)
                    .as("readAndDisplayAll() must return a non-null ReadResult even on empty input")
                    .isNotNull();
            assertThat(result.programId())
                    .as("ReadResult.programId() must echo the COBOL program ID 'CBACT03C' for audit traceability")
                    .isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount() must be zero when the underlying table is empty")
                    .isZero();

            // AND: findAll(Sort) was invoked exactly once — preserving the
            // COBOL single-pass scan semantic (no second OPEN INPUT or
            // re-scan). times(1) is the explicit canonical form.
            verify(cardCrossReferenceRepository, times(1)).findAll(any(Sort.class));

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
         * three sequential {@code READ XREFFILE-FILE} calls
         * succeed (status {@code '00'}) and the fourth returns
         * {@code '10'} (EOF). Each successful READ feeds the
         * implicit {@code DISPLAY CARD-XREF-RECORD} at L78, so
         * the Java target's per-record processing path must
         * execute exactly three times.
         *
         * <p>This test asserts the recordCount on the returned
         * {@link XrefFileReaderService.ReadResult} matches the
         * size of the fixture list &mdash; the simplest external
         * proxy for "DISPLAY paragraph executed N times" without
         * intercepting SLF4J output.</p>
         */
        @Test
        @DisplayName("readAllXref_multipleRecords_processesEachOne — three rows → ReadResult(recordCount=3)")
        void readAllXref_multipleRecords_processesEachOne() {
            // GIVEN: a deterministic three-row fixture spanning the
            // canonical Visa / Mastercard / synthetic-PAN test inputs.
            // COBOL: CBACT03C:1000-XREFFILE-GET-NEXT executed three times
            // before the fourth read returns FILE STATUS '10' (EOF).
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA),
                    buildXrefFixture(TEST_PAN_MASTERCARD),
                    buildXrefFixture("0000000000000003"));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            XrefFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: ReadResult.recordCount() equals the fixture size — proves
            // the SUT's for-each iterated every row and incremented the
            // AtomicLong counter once per row.
            assertThat(result)
                    .as("readAndDisplayAll() must return a non-null ReadResult on the happy path")
                    .isNotNull();
            assertThat(result.programId())
                    .as("ReadResult.programId() must echo 'CBACT03C' regardless of input cardinality")
                    .isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount() must equal the fixture size (3) — one per COBOL READ NEXT success")
                    .isEqualTo(3L);

            // AND: findAll(Sort) was invoked exactly once (no per-record
            // re-query — preserving COBOL single-pass scan semantic).
            verify(cardCrossReferenceRepository, times(1)).findAll(any(Sort.class));

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
         * Single-row path. Verifies the SUT correctly processes
         * exactly one fixture and reports {@code recordCount=1}.
         * This separates the boundary case from the multi-row
         * path; a regression that processed all rows from
         * {@code findAll(...)} except the first would be invisible
         * to the three-row test but caught here.
         */
        @Test
        @DisplayName("readAllXref_singleRecord_returnsRecordCountOne — one row → ReadResult(recordCount=1)")
        void readAllXref_singleRecord_returnsRecordCountOne() {
            // GIVEN: a single-row fixture using the canonical Visa test PAN.
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            XrefFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: recordCount is exactly 1.
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount() must equal 1 for a single-row fixture")
                    .isEqualTo(1L);
            assertThat(result.programId())
                    .as("ReadResult.programId() must echo the COBOL program ID 'CBACT03C'")
                    .isEqualTo(COBOL_PROGRAM_ID);
        }

        /**
         * Asserts the {@link Sort} argument passed to
         * {@link CardCrossReferenceRepository#findAll(Sort)} is
         * ascending on {@code xrefCardNum}. Preserves the COBOL
         * {@code ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-XREF-CARD-NUM}
         * semantic (CBACT03C L31&ndash;L32) so the Java scan
         * emits rows in the same byte-order as the COBOL VSAM
         * KSDS key-sequenced read &mdash; required for
         * golden-output diffing during the parallel-run
         * validation window per AAP &sect;0.6.2.
         */
        @Test
        @DisplayName("readAllXref_invokesFindAllWithSortByXrefCardNum — preserves COBOL ACCESS MODE SEQUENTIAL on FD-XREF-CARD-NUM")
        void readAllXref_invokesFindAllWithSortByXrefCardNum() {
            // GIVEN: an empty result set — the assertion target is the
            // Sort argument captured on the way INTO the repository, not
            // anything returned FROM it.
            when(cardCrossReferenceRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the Sort argument and inspect its order by
            // property name. The expected Sort.by("xrefCardNum") translates
            // to a single Order over the "xrefCardNum" property ascending —
            // the direct relational analogue of COBOL FD-XREF-CARD-NUM
            // sequencing.
            ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
            verify(cardCrossReferenceRepository).findAll(sortCaptor.capture());

            Sort capturedSort = sortCaptor.getValue();
            assertThat(capturedSort)
                    .as("findAll(Sort) must be invoked with a non-null Sort argument to preserve "
                            + "COBOL ACCESS MODE SEQUENTIAL on FD-XREF-CARD-NUM")
                    .isNotNull();
            assertThat(capturedSort.getOrderFor(SORT_FIELD_XREF_CARD_NUM))
                    .as("Sort must carry an Order over the 'xrefCardNum' property — the relational "
                            + "analogue of COBOL FD-XREF-CARD-NUM primary key sequencing")
                    .isNotNull();
            assertThat(capturedSort.getOrderFor(SORT_FIELD_XREF_CARD_NUM).isAscending())
                    .as("Sort over 'xrefCardNum' must be ASCENDING to match the COBOL VSAM KSDS "
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
        @DisplayName("readAllXref_twentyRecords_processesEachOne — 20 rows → ReadResult(recordCount=20)")
        void readAllXref_twentyRecords_processesEachOne() {
            // GIVEN: a 20-row fixture spanning xrefCardNum 1..20.
            List<CardCrossReference> fixtures = buildXrefFixtures(20);
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            XrefFileReaderService.ReadResult result = service.readAndDisplayAll();

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

        /**
         * Verifies the SUT's audit payload carries the
         * {@code record_count} matching the fixture size exactly.
         * This complements the {@link #readAllXref_multipleRecords_processesEachOne()}
         * assertion (which checks {@code ReadResult.recordCount()}
         * &mdash; an internal counter) by also asserting that the
         * count surfaced to OpenSearch (the externally-visible
         * record-count for parallel-run parity checks) is
         * consistent with the returned {@code ReadResult}.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllXref_auditPayloadCarriesRecordCount — payload.record_count matches fixture size")
        void readAllXref_auditPayloadCarriesRecordCount() {
            // GIVEN: a fixed-size three-row fixture.
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA),
                    buildXrefFixture(TEST_PAN_MASTERCARD),
                    buildXrefFixture("0000000000000003"));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the audit-log payload and assert record_count
            // equals the fixture size (3).
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
                    .as("Audit payload must be non-null for record-count parity validation")
                    .isNotNull();
            assertThat(payload)
                    .as("Audit payload record_count must equal the fixture size (3) for "
                            + "parallel-run parity diffing per AAP §0.6.2")
                    .containsEntry(PAYLOAD_RECORD_COUNT_KEY, 3L);
        }
    }

    // =========================================================================
    // PanMasking — *** CRITICAL *** — PCI-DSS v4.0 Requirement 3.4
    // (PAN masking). The COBOL source emits the full PAN to SYSOUT;
    // the Java target MUST mask it for CloudWatch Logs ingest.
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
     * </ol>
     *
     * <p>The captor approach exercises the same code path that
     * would be used by a CloudWatch log-filter to scan
     * production audit emissions for PAN leakage. If the SUT
     * ever surfaces card data in the audit payload without
     * masking, these tests fail loudly and block the build.</p>
     *
     * <p>This is the CRITICAL nested test class for the
     * XrefFileReaderService refactor &mdash; the COBOL source
     * CBACT03C emits the full 16-digit
     * {@code XREF-CARD-NUM PIC X(16)} via the
     * {@code DISPLAY CARD-XREF-RECORD} statement at L78 and L96.
     * The Java target deliberately deviates from this behaviour
     * to satisfy PCI-DSS v4.0 Requirement 3.4 &mdash; the
     * deviation is mandated by AAP &sect;0.6.6 ("Cross-Cutting:
     * Audit, Observability, and PCI-DSS"). The Minimal Change
     * Clause (AAP &sect;0.7.3) explicitly accommodates this
     * deviation because the COBOL source pre-dates PCI-DSS v4.0
     * enforcement and the platform now sits in scope for
     * PCI-DSS.</p>
     */
    @Nested
    @DisplayName("PanMasking — PCI-DSS v4.0 Req 3.4 (PAN masking)")
    class PanMasking {

        /**
         * Asserts the captured audit payload does NOT contain
         * any full 16-digit Visa test PAN
         * ({@value XrefFileReaderServiceTest#TEST_PAN_VISA}).
         * This is the canonical PCI-DSS v4.0 Requirement 3.4
         * test &mdash; the most obvious failure mode would be
         * a regression that called
         * {@code payload.put("xrefCardNum", xref.getXrefCardNum())}
         * without masking.
         *
         * <p>Captures all arguments to
         * {@link AuditLogService#logBatchJobLifecycle} via
         * {@link ArgumentCaptor} and asserts:
         * <ul>
         *   <li>The {@code Map<String, Object>} payload does
         *       not contain the literal {@code "4111111111111111"}
         *       string anywhere in its values' stringified form
         *       (covers direct {@code put("xrefCardNum", pan)},
         *       nested-map leaks, and {@code toString()}-leak
         *       paths).</li>
         *   <li>The payload's {@code toString()} does not
         *       contain the literal PAN (covers any future
         *       refactor that adds card data to the payload as a
         *       collection or custom-typed value).</li>
         *   <li>The payload key set does not include a
         *       {@code xrefCardNum} key (or analogous PAN-bearing
         *       key) &mdash; operational metadata only.</li>
         * </ul>
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllXref_emittedData_xrefCardNumMasked — full 16-digit PAN NEVER appears in audit payload")
        void readAllXref_emittedData_xrefCardNumMasked() {
            // GIVEN: a fixture carrying the canonical Visa test PAN
            // (4111111111111111). The SUT MUST NOT expose this full
            // PAN in any audit emission.
            // PCI-DSS v4.0 Requirement 3.4 (AAP §0.6.6).
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

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
            // must not include a 'xrefCardNum' (or analogous
            // PAN-bearing key) — defense-in-depth against a
            // key-only leak that String.contains() would miss if
            // the value were already masked.
            assertThat(payload.keySet())
                    .as("Audit payload must not surface any card-number key — operational "
                            + "metadata only per AAP §0.6.6 (program_id, entity_name, "
                            + "record_count, vsam_cluster)")
                    .doesNotContain("xrefCardNum", "xref_card_num", "cardNum", "card_num", "pan",
                            "primary_account_number");
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
        @DisplayName("readAllXref_emittedData_multipleBrandsAllMasked — Visa, Mastercard, Amex PANs all absent from payload")
        void readAllXref_emittedData_multipleBrandsAllMasked() {
            // GIVEN: three distinct brand PANs.
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA),
                    buildXrefFixture(TEST_PAN_MASTERCARD),
                    buildXrefFixture(TEST_PAN_AMEX));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

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
         * form ({@value XrefFileReaderServiceTest#PAN_MASK_PREFIX}
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
        @DisplayName("readAllXref_emittedData_anyCardNumberValueIsMasked — masked-form contract documented")
        void readAllXref_emittedData_anyCardNumberValueIsMasked() {
            // GIVEN: a fixture with the canonical Visa test PAN.
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

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
        @DisplayName("readAllXref_emittedData_payloadOnlyContainsOperationalMetadata — strict allowlist of payload keys")
        void readAllXref_emittedData_payloadOnlyContainsOperationalMetadata() {
            // GIVEN: a single-row fixture carrying a full PAN — the
            // strictest input to surface any leak path.
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

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
                    .as("program_id must equal the COBOL program ID 'CBACT03C'")
                    .containsEntry(PAYLOAD_PROGRAM_ID_KEY, COBOL_PROGRAM_ID);
            assertThat(payload)
                    .as("entity_name must equal 'CARD_XREF'")
                    .containsEntry(PAYLOAD_ENTITY_NAME_KEY, ENTITY_NAME);
            assertThat(payload)
                    .as("record_count must equal 1 (the fixture size)")
                    .containsEntry(PAYLOAD_RECORD_COUNT_KEY, 1L);
            assertThat(payload)
                    .as("vsam_cluster must equal the legacy CARDXREF cluster name verbatim")
                    .containsEntry(PAYLOAD_VSAM_CLUSTER_KEY, VSAM_CLUSTER);
        }

        /**
         * Defense-in-depth: asserts the captured payload does
         * NOT contain the fixture customer ID or account ID
         * under PAN-bearing keys (e.g., a regression that
         * inadvertently surfaced an account ID under a
         * {@code "cardNum"} key would slip past the PAN-string
         * search). The payload keys verified here are the same
         * "PAN-bearing" keys excluded by the
         * {@link #readAllXref_emittedData_xrefCardNumMasked()}
         * test, plus the foreign-key fields
         * {@code xrefCustId} / {@code xrefAcctId} that — while
         * not PCI-DSS-protected — also must not appear in the
         * operational-metadata-only payload.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllXref_emittedData_payloadOmitsAllRecordFields — no entity scalar fields in payload")
        void readAllXref_emittedData_payloadOmitsAllRecordFields() {
            // GIVEN: a fixture with distinct, recognisable
            // customer / account IDs so the assertion can search
            // for them by literal value.
            Long uniqueCustId = 987654321L;
            Long uniqueAcctId = 98765432109L;
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA, uniqueCustId, uniqueAcctId));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

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

            // AND: the payload must not contain entity-field keys.
            assertThat(payload.keySet())
                    .as("Audit payload must not surface any CardCrossReference entity field as a key "
                            + "(per AAP §0.6.6 only operational metadata is emitted)")
                    .doesNotContain("xrefCardNum", "xrefCustId", "xrefAcctId",
                            "xref_card_num", "xref_cust_id", "xref_acct_id");

            // AND: the payload's toString() must not surface any
            // of the fixture entity values — the customer ID and
            // account ID are not PCI-DSS-sensitive but their
            // absence is the canonical proof that the SUT emits
            // operational metadata only.
            String payloadString = payload.toString();
            assertThat(payloadString)
                    .as("Audit payload must not contain the fixture customer ID — only operational metadata")
                    .doesNotContain(uniqueCustId.toString());
            assertThat(payloadString)
                    .as("Audit payload must not contain the fixture account ID — only operational metadata")
                    .doesNotContain(uniqueAcctId.toString());
        }
    }

    // =========================================================================
    // ReadOnly — verifies the COBOL OPEN INPUT (read-only) semantic
    // (CBACT03C L120 — OPEN INPUT XREFFILE-FILE).
    // =========================================================================

    /**
     * Asserts the SUT never invokes any mutating method on the
     * repository. The COBOL source opens the VSAM cluster with
     * {@code OPEN INPUT} (read-only) at line 120 and never
     * issues a {@code REWRITE}, {@code WRITE}, or {@code DELETE}
     * verb across its 179 lines &mdash; a property the Java
     * target must preserve.
     *
     * <p>The {@link CardCrossReferenceRepository} extends
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
     *       {@code app/cbl/CBACT03C.cbl} L120 which constrains
     *       the program to read-only access &mdash; required for
     *       byte-identical parallel-run output diff per AAP
     *       &sect;0.6.2.</li>
     * </ul>
     *
     * <p>This nested class verifies the entire read-only contract
     * across the multi-row and empty-result paths so future
     * regressions are caught regardless of where in the SUT they
     * are introduced.</p>
     */
    @Nested
    @DisplayName("ReadOnly — preserves COBOL OPEN INPUT (read-only) semantic")
    class ReadOnly {

        /**
         * Verifies {@link CardCrossReferenceRepository#save(Object)}
         * is never invoked. The repository's {@code save} method
         * is the canonical persistence-mutating entry point
         * inherited from
         * {@link org.springframework.data.jpa.repository.JpaRepository};
         * any invocation would break the read-only invariant
         * declared by the SUT's
         * {@code @Transactional(readOnly = true)} boundary and
         * would diverge from the COBOL {@code OPEN INPUT}
         * semantic on {@code XREFFILE-FILE}.
         */
        @Test
        @DisplayName("readAllXref_doesNotInvokeSave — never calls cardCrossReferenceRepository.save(CardCrossReference)")
        void readAllXref_doesNotInvokeSave() {
            // GIVEN: a small three-row fixture exercising the SUT's
            // per-record processing loop. The mix of Visa /
            // Mastercard / synthetic PANs broadens coverage; the
            // assertion target is purely the SUT's call discipline,
            // not the fixture content.
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA),
                    buildXrefFixture(TEST_PAN_MASTERCARD),
                    buildXrefFixture("0000000000000003"));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            // COBOL: CBACT03C:PROCEDURE DIVISION L70–L87 — the
            // entire scan loop must never invoke any WRITE/REWRITE/
            // DELETE on the VSAM cluster.
            service.readAndDisplayAll();

            // THEN: cardCrossReferenceRepository.save was never invoked
            // — the read-only invariant from COBOL OPEN INPUT
            // (CBACT03C L120) is preserved.
            verify(cardCrossReferenceRepository, never()).save(any(CardCrossReference.class));
        }

        /**
         * Verifies the broader read-only contract: NO mutating
         * method on the {@link CardCrossReferenceRepository} is
         * invoked by the sequential scan. This single test acts
         * as a defense-in-depth net against future refactors
         * accidentally introducing a persistence side-effect via
         * {@code saveAll}, {@code saveAndFlush},
         * {@code delete}, {@code deleteById}, {@code deleteAll},
         * {@code deleteAllInBatch}, or {@code flush}.
         */
        @Test
        @DisplayName("readAllXref_doesNotInvokeAnyMutatingRepositoryMethod — save/delete/flush guard")
        void readAllXref_doesNotInvokeAnyMutatingRepositoryMethod() {
            // GIVEN: an empty result set is sufficient — the SUT's
            // for-each loop is never entered, but the SUT's
            // before/after-loop logic still runs and could
            // theoretically mutate the repository. We want to
            // verify it never does.
            when(cardCrossReferenceRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: every mutating method on the repository is
            // never invoked. Each verify(...) is asserted
            // individually so the failure message identifies the
            // specific violation.
            verify(cardCrossReferenceRepository, never()).save(any());
            verify(cardCrossReferenceRepository, never()).saveAll(any());
            verify(cardCrossReferenceRepository, never()).saveAndFlush(any());
            verify(cardCrossReferenceRepository, never()).delete(any());
            verify(cardCrossReferenceRepository, never()).deleteById(any());
            verify(cardCrossReferenceRepository, never()).deleteAll();
            verify(cardCrossReferenceRepository, never()).deleteAllInBatch();
            verify(cardCrossReferenceRepository, never()).flush();
        }

        /**
         * Verifies the read-only contract still holds when the
         * fixture is non-empty. The previous test exercises the
         * empty-loop path; this one exercises the multi-record
         * loop path. A regression that introduced a write-back
         * for a specific cross-reference state (e.g., "mark
         * record as scanned") would fire here even though it
         * would NOT fire in the empty-fixture test.
         */
        @Test
        @DisplayName("readAllXref_doesNotInvokeAnyMutatingRepositoryMethodOnMultiRecord — multi-record read-only guard")
        void readAllXref_doesNotInvokeAnyMutatingRepositoryMethodOnMultiRecord() {
            // GIVEN: a multi-row fixture exercising the SUT's
            // per-record processing loop.
            List<CardCrossReference> fixtures = buildXrefFixtures(5);
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: no mutating method on the repository is
            // invoked, even though the for-each loop processed 5
            // records.
            verify(cardCrossReferenceRepository, never()).save(any());
            verify(cardCrossReferenceRepository, never()).saveAll(any());
            verify(cardCrossReferenceRepository, never()).saveAndFlush(any());
            verify(cardCrossReferenceRepository, never()).delete(any());
            verify(cardCrossReferenceRepository, never()).deleteById(any());
            verify(cardCrossReferenceRepository, never()).deleteAll();
            verify(cardCrossReferenceRepository, never()).deleteAllInBatch();
            verify(cardCrossReferenceRepository, never()).flush();
        }

        /**
         * Verifies the SUT does not invoke any alternate-index
         * lookup method ({@code findByXrefAcctId},
         * {@code findByXrefAcctIdOrderByXrefCardNumAsc}). The
         * COBOL source {@code CBACT03C} performs only the
         * primary-key sequential scan (L29&ndash;L33,
         * L70&ndash;L87); AIX-driven lookups are exposed via
         * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
         * but consumed only by other services
         * ({@code AccountViewService},
         * {@code TransactionAddService},
         * {@code TransactionPostingService}). A regression that
         * accidentally invoked an AIX method from
         * {@code XrefFileReaderService} would break the COBOL
         * source's "sequential scan only" contract and would be
         * caught here.
         */
        @Test
        @DisplayName("readAllXref_doesNotInvokeAlternateIndexLookups — preserves COBOL primary-key-only access")
        void readAllXref_doesNotInvokeAlternateIndexLookups() {
            // GIVEN: a small fixture exercising the SUT's loop.
            List<CardCrossReference> fixtures = List.of(
                    buildXrefFixture(TEST_PAN_VISA));
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: no alternate-index method on the repository
            // was invoked. The COBOL source CBACT03C performs
            // primary-key sequential scan only — AIX-driven
            // lookups are exposed but consumed by other services.
            verify(cardCrossReferenceRepository, never()).findByXrefAcctId(any());
            verify(cardCrossReferenceRepository, never()).findByXrefAcctIdOrderByXrefCardNumAsc(any());
            // findById is also outside the COBOL CBACT03C contract
            // (the program performs sequential read only, never
            // primary-key lookup).
            verify(cardCrossReferenceRepository, never()).findById(any());
        }
    }
}
