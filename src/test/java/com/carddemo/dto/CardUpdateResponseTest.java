package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.assertPanMaskedLast4;
import static com.carddemo.dto.DtoTestSupport.componentNames;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardUpdateResponse}, the aggregate response for the Card
 * Update transaction (CCUP) implemented by COBOL {@code COCRDUPC} (source commit
 * {@code 27d6c6f}). The DTO wraps the refreshed {@link CardViewResponse}, a
 * human-readable confirmation message, and the JPA {@code @Version} token echoed
 * back for optimistic-locking parity with the COBOL read-then-rewrite pattern
 * (AAP &sect;0.8.4).
 *
 * <p>These tests pin the {@code SUCCESS_MESSAGE} constant, the
 * {@code withConfirmation(...)} factory (which derives the version from the
 * supplied card null-safely), the PAN-masking invariant carried by the nested
 * card, the structural contract, and JSON round-trip fidelity. They contribute
 * to the CP2 test-coverage gate (Gate 8, JaCoCo &ge; 80%).</p>
 */
@DisplayName("CardUpdateResponse — card-update aggregate response DTO")
class CardUpdateResponseTest {

    /** Account-id fixture ({@code ACCT-ID} PIC 9(11)). */
    private static final String ACCOUNT_ID = "00000000001";

    /** A full 16-digit PAN whose visible last four are {@code 1234}. */
    private static final String FULL_PAN = "4111111111111234";

    /** Optimistic-lock version fixture echoed back on the update round-trip. */
    private static final Long VERSION = 7L;

    private static CardViewResponse card(Long version) {
        return new CardViewResponse(
                ACCOUNT_ID, FULL_PAN, "JOHN Q PUBLIC", "Y", LocalDate.of(2027, 12, 31), version);
    }

    @Nested
    @DisplayName("SUCCESS_MESSAGE constant")
    class SuccessMessage {

        @Test
        @DisplayName("carries the standard card-update confirmation text")
        void value() {
            assertThat(CardUpdateResponse.SUCCESS_MESSAGE).isEqualTo("Card updated successfully");
        }
    }

    @Nested
    @DisplayName("withConfirmation(card) factory")
    class WithConfirmation {

        @Test
        @DisplayName("applies the standard message and echoes the card version")
        void defaultMessageAndVersion() {
            CardUpdateResponse response = CardUpdateResponse.withConfirmation(card(VERSION));

            assertThat(response.message()).isEqualTo(CardUpdateResponse.SUCCESS_MESSAGE);
            assertThat(response.version()).isEqualTo(VERSION);
            assertThat(response.card().accountId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("null card yields a null version without failing (null-safe version derivation)")
        void nullCardNullVersion() {
            CardUpdateResponse response = CardUpdateResponse.withConfirmation(null);

            assertThat(response.card()).isNull();
            assertThat(response.version()).isNull();
            assertThat(response.message()).isEqualTo(CardUpdateResponse.SUCCESS_MESSAGE);
        }
    }

    @Nested
    @DisplayName("Masking, construction, and serialization")
    class MaskingConstructionSerialization {

        @Test
        @DisplayName("the nested card number is masked to its last four digits")
        void cardNumberMasked() {
            CardUpdateResponse response = CardUpdateResponse.withConfirmation(card(VERSION));

            assertPanMaskedLast4(response.card().cardNumber(), "1234");
        }

        @Test
        @DisplayName("the canonical constructor preserves card, message, and version")
        void canonicalCtor() {
            CardUpdateResponse response = new CardUpdateResponse(card(VERSION), "custom", 5L);

            assertThat(response.card().accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.message()).isEqualTo("custom");
            assertThat(response.version()).isEqualTo(5L);
        }

        @Test
        @DisplayName("declares exactly card, message, and version")
        void declaresComponents() {
            assertThat(componentNames(CardUpdateResponse.class))
                    .containsExactlyInAnyOrder("card", "message", "version");
        }

        @Test
        @DisplayName("round-trips through JSON preserving the masked card and version")
        void jsonRoundTrip() {
            CardUpdateResponse original = CardUpdateResponse.withConfirmation(card(VERSION));

            CardUpdateResponse restored = roundTrip(original, CardUpdateResponse.class);

            assertThat(restored).isEqualTo(original);
            assertPanMaskedLast4(restored.card().cardNumber(), "1234");
            assertThat(restored.version()).isEqualTo(VERSION);
        }
    }
}
