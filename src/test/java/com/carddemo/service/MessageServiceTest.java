package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure, dependency-free unit test for {@link MessageService}.
 *
 * <p>{@code MessageService} centralizes the shared, cross-cutting user-facing
 * message literals migrated from the COBOL common-message copybook
 * {@code CCDA-COMMON-MESSAGES} (copybook {@code CSMSG01Y}). Those literals are an
 * external-facing byte-parity contract: the exact visible characters travel out
 * over the REST/JSON boundary, so any accidental edit to a production literal
 * (an added trailing space, a dropped period, changed punctuation) is a
 * behavioral regression against the legacy system. This test asserts each
 * message <em>verbatim</em> so that such an edit breaks the build — that is the
 * parity guard (Gates&nbsp;1/4/5).</p>
 *
 * <p>The COBOL source stores each message as a fixed-width {@code PIC X(50)}
 * field right-padded with spaces to fill the 3270 display; {@code MessageService}
 * intentionally holds the <em>trimmed</em> visible text (no trailing display
 * padding) so the JSON payload carries only the visible characters. The
 * {@code doesNotEndWith(" ")} and exact-length assertions below lock that
 * trimming decision in place and prevent the legacy padding from silently
 * creeping back.</p>
 *
 * <p>The class under test has no collaborators, so this test loads no Spring
 * context and uses no Testcontainers, database, file, network, or mocks — every
 * assertion is an in-memory string check. The suite therefore runs in
 * milliseconds and contributes fast, deterministic line coverage toward the
 * Gate&nbsp;8 (&ge;80%) JaCoCo threshold. The design rationale for centralizing
 * these messages (versus keeping screen-specific literals private to their
 * owning domain service) is documented in {@code docs/decision-log.md}, not in
 * these comments; no COBOL source is reproduced here beyond the migrated visible
 * text that the production class already exposes.</p>
 */
@DisplayName("MessageService — verbatim shared message literals (COBOL CSMSG01Y parity)")
class MessageServiceTest {

    // ---------------------------------------------------------------------
    // Expected literals — copied character-for-character from the production
    // MessageService constants. These local copies are the byte-parity oracle:
    // if a production literal is edited, the isEqualTo assertions below fail and
    // pinpoint exactly which migrated message regressed. Trailing display
    // padding from the COBOL PIC X(50) fields is intentionally absent.
    // ---------------------------------------------------------------------

    /** Verbatim expected value of {@link MessageService#THANK_YOU}. */
    private static final String EXPECTED_THANK_YOU =
            "Thank you for using CardDemo application...";

    /** Verbatim expected value of {@link MessageService#INVALID_KEY}. */
    private static final String EXPECTED_INVALID_KEY =
            "Invalid key pressed. Please see below...";

    /** Fixed-width COBOL display length of the source {@code PIC X(50)} fields. */
    private static final int COBOL_DISPLAY_WIDTH = 50;

    /** Fresh, Spring-free instance exercising the plain {@code new MessageService()} path. */
    private final MessageService service = new MessageService();

    // ---------------------------------------------------------------------
    // Phase 1 — getters return their backing constant AND the exact literal.
    // One test per message so a single altered literal names the culprit.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getThankYouMessage() returns THANK_YOU verbatim")
    void getThankYouMessage_returnsExactConstant() {
        assertThat(service.getThankYouMessage())
                .isEqualTo(MessageService.THANK_YOU)
                .isEqualTo(EXPECTED_THANK_YOU);
    }

    @Test
    @DisplayName("getInvalidKeyMessage() returns INVALID_KEY verbatim")
    void getInvalidKeyMessage_returnsExactConstant() {
        assertThat(service.getInvalidKeyMessage())
                .isEqualTo(MessageService.INVALID_KEY)
                .isEqualTo(EXPECTED_INVALID_KEY);
    }

    // ---------------------------------------------------------------------
    // Phase 2 — constant byte-parity guards. Assert the exact literal, the
    // exact length, the absence of trailing COBOL display padding, and the
    // preserved sign-off ellipsis so the migrated contract cannot drift.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("THANK_YOU is the verbatim, trimmed (un-padded) migrated literal")
    void thankYouConstant_isVerbatimByteParityLiteral() {
        assertThat(MessageService.THANK_YOU)
                .isEqualTo(EXPECTED_THANK_YOU)
                .hasSize(43)
                .endsWith("...")
                .doesNotEndWith(" ")
                .doesNotEndWith("  ")
                .hasSizeLessThanOrEqualTo(COBOL_DISPLAY_WIDTH);
    }

    @Test
    @DisplayName("INVALID_KEY is the verbatim, trimmed (un-padded) migrated literal")
    void invalidKeyConstant_isVerbatimByteParityLiteral() {
        assertThat(MessageService.INVALID_KEY)
                .isEqualTo(EXPECTED_INVALID_KEY)
                .hasSize(40)
                .endsWith("...")
                .doesNotEndWith(" ")
                .doesNotEndWith("  ")
                .hasSizeLessThanOrEqualTo(COBOL_DISPLAY_WIDTH);
    }

    // ---------------------------------------------------------------------
    // Phase 3 — sanity guards shared by every public message surface. These
    // ensure no constant is ever null, empty, or blank (all-whitespace), which
    // would surface an empty message to the user at the REST boundary.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("All public message constants are non-null and non-blank")
    void allMessageConstants_areNonNullAndNonBlank() {
        assertThat(MessageService.THANK_YOU)
                .isNotNull()
                .isNotEmpty()
                .isNotBlank();
        assertThat(MessageService.INVALID_KEY)
                .isNotNull()
                .isNotEmpty()
                .isNotBlank();
    }
}
