package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.exception.FileProcessingException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.service.InterestCalculationService;

/**
 * Pure, fast unit test for {@link InterestCalculationProcessor}, the chunk-step
 * {@code ItemProcessor<Long, Long>} for the CardDemo interest-calculation stage (processor #2 of 5
 * in the batch pipeline, wired into {@code InterestCalculationJob} / JCL step {@code INTCALC},
 * {@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}).
 *
 * <p><strong>What this test guards &mdash; and, deliberately, what it does not.</strong> Following
 * the AAP&nbsp;&sect;0.4.3 {@code CALL}&nbsp;&rarr;&nbsp;bean mandate, the legacy interest program
 * {@code CBACT04C} (frozen COBOL source referenced read-only at commit SHA {@code 27d6c6f}; never
 * copied here) is decomposed so that the entire per-account interest computation lives in
 * {@link InterestCalculationService#applyInterestToAccount(Long)} &mdash; the disclosure-group rate
 * lookup with {@code DEFAULT} fallback ({@code 1200-GET-INTEREST-RATE}), the monthly-interest
 * arithmetic {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * ({@code 1300-COMPUTE-INTEREST}), the per-category interest-transaction writes, and the
 * account-balance rewrite ({@code 1050-UPDATE-ACCOUNT}). {@link InterestCalculationProcessor} is a
 * <em>thin orchestrator</em>: it reproduces exactly one iteration of the {@code CBACT04C}
 * per-account control break by delegating to that service and returning the account id unchanged so
 * the chunk keeps flowing. Consequently this test locks only the orchestration contract; the
 * balance/interest decimal-fidelity parity (AAP&nbsp;G2) is asserted in
 * {@code InterestCalculationServiceTest}, the reader ordering in {@code BatchReadersIT}, and the job
 * wiring in {@code BatchJobConfigurationIT}. No COBOL source is reproduced here; rationale lives in
 * {@code docs/decision-log.md}.</p>
 *
 * <p>The sole collaborator {@link InterestCalculationService} is a Mockito mock (the
 * {@code CALL}&nbsp;&rarr;&nbsp;bean seam), so the suite loads no Spring context and touches no
 * database, Spring Batch runtime, Testcontainers, Docker, or live AWS &mdash; it exercises the real
 * {@link InterestCalculationProcessor#process(Long)} path in milliseconds and feeds JaCoCo line
 * coverage toward the Gate&nbsp;8 (&ge;80%) threshold. It compiles warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2): the mock is fully generic (no raw types) and every stub is
 * consumed by the code under test (Mockito {@code STRICT_STUBS}). Coverage is grouped by the
 * behaviour under test:</p>
 * <ul>
 *   <li><strong>Delegation</strong> &mdash; each account id is forwarded to the service verbatim
 *       and exactly once (one interest posting per distinct account, matching the control break).</li>
 *   <li><strong>Identity return</strong> &mdash; {@code process(id)} returns the very same id
 *       instance; a {@code null} return would filter the item out of the Spring Batch chunk and skew
 *       the processed count, so identity must hold even when the service reports zero interest.</li>
 *   <li><strong>Fault handling</strong> &mdash; genuine service faults (a missing account, an
 *       optimistic-lock conflict) propagate unchanged (never caught or re-wrapped), while an
 *       out-of-contract {@code null} item fails fast as a {@link FileProcessingException} (the
 *       {@code CBACT04C 9999-ABEND-PROGRAM} class) without ever delegating.</li>
 *   <li><strong>Constructor contract</strong> &mdash; the sole collaborator is mandatory.</li>
 * </ul>
 *
 * @see InterestCalculationProcessor
 * @see InterestCalculationService#applyInterestToAccount(Long)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationProcessor - INTCALC delegation & identity contract (CBACT04C, SHA 27d6c6f)")
class InterestCalculationProcessorTest {

    /**
     * A representative 11-digit account id ({@code TRANCAT-ACCT-ID} / {@code ACCT-ID}, COBOL
     * {@code PIC 9(11)}). Held in a field so identity ({@code ==}) assertions on the processor's
     * return value are meaningful: the processor returns the same boxed {@link Long} reference it
     * was handed, and this value is far outside the JLS {@link Long} auto-box cache
     * ({@code -128..127}).
     */
    private static final Long ACCT_ID = 11111111111L;

    /**
     * The sole collaborator, mocked as the {@code CALL 'CBACT04C'} interest logic replacement. Fresh
     * per test (re-initialized by {@link MockitoExtension}), so stubs never leak between tests.
     */
    @Mock
    private InterestCalculationService interestCalculationService;

    /** System under test, reconstructed per test against the freshly injected mock. */
    private InterestCalculationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InterestCalculationProcessor(interestCalculationService);
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 1 - Delegation: the per-account interest work is forwarded verbatim to the service bean
    // (AAP §0.4.3 CALL -> bean); the processor re-implements none of it.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Delegation (CALL -> bean, AAP §0.4.3)")
    class Delegation {

        @Test
        @DisplayName("process(id) delegates to applyInterestToAccount(id) with the exact id, exactly once")
        void delegatesExactAccountIdExactlyOnce() {
            // No stubbing: the mock returns null by default, which the processor only logs; its
            // return value is the account id, independent of the service result. A pure interaction
            // test therefore needs no stub (and adding one would be flagged as unnecessary).
            processor.process(ACCT_ID);

            // The exact id is forwarded (Mockito matches by equals), and nothing else is invoked.
            verify(interestCalculationService).applyInterestToAccount(ACCT_ID);
            verifyNoMoreInteractions(interestCalculationService);
        }

        @Test
        @DisplayName("process(id) delegates once per distinct account id (mirrors the CBACT04C control break)")
        void delegatesOncePerDistinctAccountId() {
            // CBACT04C performs its per-account interest work exactly once for each distinct account
            // (the TRANCAT-ACCT-ID control break). The reader emits one id per distinct account, so
            // the processor must delegate once per id and no more.
            List<Long> accountIds = List.of(11111111111L, 22222222222L, 33333333333L);

            for (Long id : accountIds) {
                processor.process(id);
            }

            verify(interestCalculationService).applyInterestToAccount(11111111111L);
            verify(interestCalculationService).applyInterestToAccount(22222222222L);
            verify(interestCalculationService).applyInterestToAccount(33333333333L);
            verifyNoMoreInteractions(interestCalculationService);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 2 - Identity return: a non-null carrier keeps the item in the chunk (counted + written).
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Identity return (non-null carrier keeps the chunk flowing)")
    class IdentityReturn {

        @Test
        @DisplayName("process(id) returns the very same id instance it received")
        void returnsSameIdInstance() {
            // A realistic applied-interest total (scale-2 BigDecimal). The stub is consumed by
            // process(...) so it is never an unnecessary stub under STRICT_STUBS.
            when(interestCalculationService.applyInterestToAccount(ACCT_ID))
                    .thenReturn(new BigDecimal("42.17"));

            Long result = processor.process(ACCT_ID);

            // Same reference (==) is the strongest identity guarantee, and value-equality documents
            // the contract explicitly. The processor returns its input id unchanged.
            assertThat(result).isSameAs(ACCT_ID).isEqualTo(ACCT_ID);
        }

        @Test
        @DisplayName("process(id) still returns the id when the service reports zero interest (DIS-INT-RATE = 0)")
        void returnsIdWhenZeroInterest() {
            // CBACT04C skips 1300-COMPUTE-INTEREST when DIS-INT-RATE = 0, so the service reports a
            // total of 0.00. The item must still flow (non-null) so it is counted by the step.
            when(interestCalculationService.applyInterestToAccount(ACCT_ID))
                    .thenReturn(new BigDecimal("0.00"));

            Long result = processor.process(ACCT_ID);

            assertThat(result).isSameAs(ACCT_ID);
        }

        @Test
        @DisplayName("process(id) is non-null and same-ref for several distinct accounts")
        void returnsNonNullSameRefForSeveralAccounts() {
            // A representative spread across the 11-digit account-id domain: the minimum, a mid
            // value, and the maximum PIC 9(11). Every one must pass through non-null and same-ref so
            // no account is ever filtered out of the chunk (which would drop its interest posting).
            List<Long> accountIds = List.of(1L, 500L, 99999999999L);

            for (Long id : accountIds) {
                Long result = processor.process(id);

                assertThat(result).isNotNull().isSameAs(id);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 3 - Fault handling: real service faults propagate unchanged; a null item fails fast.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Fault handling (service faults propagate unchanged; null id fails fast)")
    class FaultHandling {

        @Test
        @DisplayName("process(id) propagates a service ResourceNotFoundException unchanged (no wrapping)")
        void propagatesResourceNotFoundUnchanged() {
            // The COBOL INVALID KEY path (account/xref not found) surfaces from the service as a
            // ResourceNotFoundException. The processor must not catch or re-wrap it.
            ResourceNotFoundException boom = new ResourceNotFoundException("Account", ACCT_ID);
            doThrow(boom).when(interestCalculationService).applyInterestToAccount(ACCT_ID);

            ResourceNotFoundException thrown =
                    assertThrows(ResourceNotFoundException.class, () -> processor.process(ACCT_ID));

            // Same instance proves the exception was propagated verbatim, not re-wrapped. That the
            // stubbed method threw at all also proves delegation occurred.
            assertThat(thrown).isSameAs(boom);
        }

        @Test
        @DisplayName("process(id) propagates a service OptimisticLockConflictException unchanged (no wrapping)")
        void propagatesOptimisticLockConflictUnchanged() {
            // The read-then-rewrite @Version conflict at the account boundary surfaces from the
            // service as an OptimisticLockConflictException; it, too, propagates verbatim.
            OptimisticLockConflictException boom =
                    new OptimisticLockConflictException("Account " + ACCT_ID + " was modified concurrently");
            doThrow(boom).when(interestCalculationService).applyInterestToAccount(ACCT_ID);

            OptimisticLockConflictException thrown = assertThrows(
                    OptimisticLockConflictException.class, () -> processor.process(ACCT_ID));

            assertThat(thrown).isSameAs(boom);
        }

        @Test
        @DisplayName("process(null) throws FileProcessingException and never delegates (CBACT04C 9999-ABEND-PROGRAM)")
        void nullIdThrowsFileProcessingExceptionWithoutDelegating() {
            // The reader guarantees a non-null id (trancat_acct_id is BIGINT NOT NULL); a null item
            // is an out-of-contract corrupt-input fault of the COBOL 9999-ABEND-PROGRAM class. The
            // processor must fail fast BEFORE delegating, never passing null to the service.
            FileProcessingException thrown =
                    assertThrows(FileProcessingException.class, () -> processor.process(null));

            assertThat(thrown.getMessage()).contains("null account id");
            verifyNoInteractions(interestCalculationService);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Constructor contract - the sole collaborator is mandatory (constructor-injection wiring).
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor contract")
    class ConstructorContract {

        @Test
        @DisplayName("constructor rejects a null service with NullPointerException")
        void constructorRejectsNullService() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new InterestCalculationProcessor(null))
                    .withMessage("interestCalculationService must not be null");
        }
    }
}
