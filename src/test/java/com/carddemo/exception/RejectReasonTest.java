package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RejectReason}, the enum that carries the COBOL
 * transaction-posting reject codes and their fixed-width trailer descriptions
 * migrated from the batch posting engine {@code CBTRN02C}.
 *
 * <p>The numeric {@link RejectReason#getCode() code} and
 * {@link RejectReason#getDescription() description} of every constant form a
 * byte-equivalent reject-trailer contract (Gates&nbsp;1/4/5). The description
 * literals are asserted <em>verbatim</em> so that any accidental edit to a
 * production literal breaks the build — that is the parity guard. The tests are
 * deliberately pure and dependency-free (no Spring context, no Testcontainers,
 * and no mocks), so they run in milliseconds and contribute fast line coverage
 * toward the Gate&nbsp;8 (&ge;80%) JaCoCo threshold. Rationale is documented in
 * {@code docs/decision-log.md}; no COBOL source is reproduced here.</p>
 */
@DisplayName("RejectReason — reject codes, verbatim trailer text, and code lookup")
class RejectReasonTest {

    // ------------------------------------------------------------------
    // Phase 1 — code + verbatim description parity. One test per constant
    // so that a single altered literal pinpoints exactly which trailer
    // contract regressed.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("VALID exposes code 0 and a blank description (COBOL SPACES)")
    void validExposesCodeZeroAndEmptyDescription() {
        assertThat(RejectReason.VALID.getCode()).isEqualTo(0);
        assertThat(RejectReason.VALID.getDescription()).isEqualTo("");
    }

    @Test
    @DisplayName("INVALID_CARD_NUMBER exposes code 100 and its verbatim description")
    void invalidCardNumberExposesCode100AndVerbatimDescription() {
        assertThat(RejectReason.INVALID_CARD_NUMBER.getCode()).isEqualTo(100);
        assertThat(RejectReason.INVALID_CARD_NUMBER.getDescription())
                .isEqualTo("INVALID CARD NUMBER FOUND");
    }

    @Test
    @DisplayName("ACCOUNT_NOT_FOUND exposes code 101 and its verbatim description")
    void accountNotFoundExposesCode101AndVerbatimDescription() {
        assertThat(RejectReason.ACCOUNT_NOT_FOUND.getCode()).isEqualTo(101);
        assertThat(RejectReason.ACCOUNT_NOT_FOUND.getDescription())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
    }

    @Test
    @DisplayName("OVERLIMIT exposes code 102 and its verbatim description")
    void overlimitExposesCode102AndVerbatimDescription() {
        assertThat(RejectReason.OVERLIMIT.getCode()).isEqualTo(102);
        assertThat(RejectReason.OVERLIMIT.getDescription())
                .isEqualTo("OVERLIMIT TRANSACTION");
    }

    @Test
    @DisplayName("ACCOUNT_EXPIRED exposes code 103 and its verbatim description")
    void accountExpiredExposesCode103AndVerbatimDescription() {
        assertThat(RejectReason.ACCOUNT_EXPIRED.getCode()).isEqualTo(103);
        assertThat(RejectReason.ACCOUNT_EXPIRED.getDescription())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    @DisplayName("ACCOUNT_NOT_FOUND_ON_UPDATE exposes code 109 and its verbatim description")
    void accountNotFoundOnUpdateExposesCode109AndVerbatimDescription() {
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE.getCode()).isEqualTo(109);
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
    }

    @Test
    @DisplayName("Codes 101 and 109 are distinct but share the same trailer text")
    void code101AndCode109AreDistinctYetShareTrailerText() {
        // The COBOL posting engine sets reason 101 on the account-lookup failure
        // and reason 109 on the REWRITE-time account failure, yet emits identical
        // trailer text for both. The migration must preserve BOTH the shared text
        // AND the numeric distinction between the two codes.
        assertThat(RejectReason.ACCOUNT_NOT_FOUND.getDescription())
                .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription());
        assertThat(RejectReason.ACCOUNT_NOT_FOUND.getCode())
                .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE.getCode());
    }

    // ------------------------------------------------------------------
    // Phase 2 — isValid() semantics: true only for VALID.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isValid() is true only for VALID and false for every reject reason")
    void isValidIsTrueOnlyForValid() {
        for (RejectReason reason : RejectReason.values()) {
            assertThat(reason.isValid())
                    .as("isValid() for %s", reason)
                    .isEqualTo(reason == RejectReason.VALID);
        }
    }

    // ------------------------------------------------------------------
    // Phase 3 — fromCode(int) -> Optional lookup.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fromCode resolves every mapped reject code to its constant")
    void fromCodeResolvesEveryMappedCode() {
        assertThat(RejectReason.fromCode(0)).contains(RejectReason.VALID);
        assertThat(RejectReason.fromCode(100)).contains(RejectReason.INVALID_CARD_NUMBER);
        assertThat(RejectReason.fromCode(101)).contains(RejectReason.ACCOUNT_NOT_FOUND);
        assertThat(RejectReason.fromCode(102)).contains(RejectReason.OVERLIMIT);
        assertThat(RejectReason.fromCode(103)).contains(RejectReason.ACCOUNT_EXPIRED);
        assertThat(RejectReason.fromCode(109)).contains(RejectReason.ACCOUNT_NOT_FOUND_ON_UPDATE);
    }

    @Test
    @DisplayName("fromCode returns a non-null, empty Optional for an unmapped code")
    void fromCodeReturnsEmptyForUnmappedCode() {
        // 999 is deliberately outside the mapped set {0,100,101,102,103,109}.
        Optional<RejectReason> result = RejectReason.fromCode(999);
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 4 — completeness guard: catch accidental additions/removals.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Enum declares exactly the six known reject reasons")
    void enumDeclaresExactlySixConstants() {
        assertThat(RejectReason.values()).hasSize(6);
    }

    // ------------------------------------------------------------------
    // Trailer rendering — formattedDescription() fixed 76-wide field
    // (PIC X(76)). Exercising it strengthens Gate 8 coverage and proves the
    // left-justified, space-padded reject-trailer padding contract.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formattedDescription() left-justifies and space-pads to 76 characters")
    void formattedDescriptionPadsToSeventySixCharacters() {
        String field = RejectReason.INVALID_CARD_NUMBER.formattedDescription();
        assertThat(field).hasSize(76);
        assertThat(field).startsWith("INVALID CARD NUMBER FOUND");
        assertThat(field.strip()).isEqualTo("INVALID CARD NUMBER FOUND");
    }

    @Test
    @DisplayName("formattedDescription() for VALID renders 76 spaces (COBOL SPACES)")
    void formattedDescriptionForValidIsSeventySixSpaces() {
        String field = RejectReason.VALID.formattedDescription();
        assertThat(field).hasSize(76);
        assertThat(field).isBlank();
    }
}
