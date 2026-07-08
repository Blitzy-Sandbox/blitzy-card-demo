package com.carddemo.dto;

import static com.carddemo.dto.DtoTestSupport.componentNames;
import static com.carddemo.dto.DtoTestSupport.roundTrip;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountUpdateResponse}, the aggregate response for the
 * Account Update transaction (CAUP) implemented by COBOL {@code COACTUPC}
 * (source commit {@code 27d6c6f}). The DTO wraps the refreshed
 * {@link AccountViewResponse}, a human-readable confirmation message, and the
 * JPA {@code @Version} token echoed back for optimistic-locking parity with the
 * COBOL read-then-rewrite pattern (AAP &sect;0.8.4).
 *
 * <p>These tests pin the {@code SUCCESS_MESSAGE} constant, the
 * {@code updated(...)} factory methods (which derive the version from the
 * supplied account null-safely), the structural contract, and JSON round-trip
 * fidelity. They contribute to the CP2 test-coverage gate (Gate 8, JaCoCo
 * &ge; 80%).</p>
 */
@DisplayName("AccountUpdateResponse — account-update aggregate response DTO")
class AccountUpdateResponseTest {

    /** Account-id fixture ({@code ACCT-ID} PIC 9(11)). */
    private static final String ACCOUNT_ID = "00000000001";

    /** Optimistic-lock version fixture echoed back on the update round-trip. */
    private static final Long VERSION = 7L;

    /**
     * Builds a minimal but valid {@link AccountViewResponse} carrying the given
     * optimistic-lock version (component index 11 of the 30-component record).
     * All optional money, customer, and address fields are left {@code null};
     * the canonical constructor tolerates {@code null} money (preserved as
     * {@code null}) and a {@code null} SSN.
     *
     * @param version the optimistic-lock token to embed
     * @return a populated {@link AccountViewResponse}
     */
    private static AccountViewResponse account(Long version) {
        return new AccountViewResponse(
                ACCOUNT_ID, "Y",
                null, null, null, null, null,
                null, null, null,
                "DEFAULT01",
                version,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null,
                null,
                null, null, null, null,
                null);
    }

    @Nested
    @DisplayName("SUCCESS_MESSAGE constant")
    class SuccessMessage {

        @Test
        @DisplayName("carries the standard account-update confirmation text")
        void value() {
            assertThat(AccountUpdateResponse.SUCCESS_MESSAGE).isEqualTo("Account updated successfully");
        }
    }

    @Nested
    @DisplayName("updated(...) factory methods")
    class UpdatedFactory {

        @Test
        @DisplayName("updated(account) applies the standard message and echoes the account version")
        void defaultMessageAndVersion() {
            AccountUpdateResponse response = AccountUpdateResponse.updated(account(VERSION));

            assertThat(response.message()).isEqualTo(AccountUpdateResponse.SUCCESS_MESSAGE);
            assertThat(response.version()).isEqualTo(VERSION);
            assertThat(response.account().accountId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("updated(account, message) uses the custom message but still echoes the version")
        void customMessage() {
            AccountUpdateResponse response =
                    AccountUpdateResponse.updated(account(VERSION), "Account reviewed and updated");

            assertThat(response.message()).isEqualTo("Account reviewed and updated");
            assertThat(response.version()).isEqualTo(VERSION);
        }

        @Test
        @DisplayName("updated(null) yields a null version without failing (null-safe version derivation)")
        void nullAccountNullVersion() {
            AccountUpdateResponse response = AccountUpdateResponse.updated(null);

            assertThat(response.account()).isNull();
            assertThat(response.version()).isNull();
            assertThat(response.message()).isEqualTo(AccountUpdateResponse.SUCCESS_MESSAGE);
        }
    }

    @Nested
    @DisplayName("Canonical construction and serialization")
    class ConstructionAndSerialization {

        @Test
        @DisplayName("the canonical constructor preserves account, message, and version")
        void canonicalCtor() {
            AccountUpdateResponse response = new AccountUpdateResponse(account(VERSION), "custom", 5L);

            assertThat(response.account().accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.message()).isEqualTo("custom");
            assertThat(response.version()).isEqualTo(5L);
        }

        @Test
        @DisplayName("declares exactly account, message, and version")
        void declaresComponents() {
            assertThat(componentNames(AccountUpdateResponse.class))
                    .containsExactlyInAnyOrder("account", "message", "version");
        }

        @Test
        @DisplayName("round-trips through JSON with the nested account, message, and version preserved")
        void jsonRoundTrip() {
            AccountUpdateResponse original = AccountUpdateResponse.updated(account(VERSION));

            AccountUpdateResponse restored = roundTrip(original, AccountUpdateResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.version()).isEqualTo(VERSION);
            assertThat(restored.account().accountId()).isEqualTo(ACCOUNT_ID);
        }
    }
}
