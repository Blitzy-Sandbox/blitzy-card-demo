/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Unit test for strict LocalDate request parsing in WebConfig's Jackson customizer
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test with NO COBOL source equivalent. It guards the
 *  resolution of QA FINAL ACCEPTANCE (ALT) Issue #3: an invalid account date
 *  (e.g. 2024-02-30) was silently normalized to 2024-02-29 and persisted instead
 *  of being rejected, violating the COBOL CEEDAYS/CSUTLDTC date-edit parity
 *  (100% behavioral parity, §0.7.2; CEEDAYS->java.time, §0.1.2). Base package is
 *  com.cardemo (decision D-006).
 *
 *  This test builds the application ObjectMapper exactly the way Spring Boot does
 *  — by applying the REAL production WebConfig#jacksonCustomizer() (which registers
 *  StrictLocalDateDeserializer) to a Jackson2ObjectMapperBuilder — so it exercises
 *  the shipped wiring rather than a reimplementation. It needs neither a Spring
 *  context nor a database.
 * ============================================================================
 */
package com.cardemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.TransactionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Verifies that {@link WebConfig#jacksonCustomizer()} parses {@link LocalDate} request fields with
 * <strong>strict</strong> resolution: calendar-impossible dates are rejected (surfacing as
 * {@link InvalidFormatException}, which the controller advice maps to HTTP&nbsp;400), valid dates are
 * accepted with their exact value (no normalization/clamping), and the {@code yyyy-MM-dd}
 * serialization contract is preserved. This is the regression guard for QA Issue&nbsp;#3.
 */
@DisplayName("WebConfig Jackson customizer — strict LocalDate request parsing (QA Issue #3)")
class WebConfigStrictDateDeserializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void buildApplicationObjectMapper() {
        // Build the ObjectMapper the same way Spring Boot does: apply the REAL production
        // WebConfig customizer (which registers StrictLocalDateDeserializer) to the builder.
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new WebConfig(new String[] {"*"}).jacksonCustomizer().customize(builder);
        this.mapper = builder.build();
    }

    // ------------------------------------------------------------------------
    // Impossible dates must be REJECTED (the defect: they were silently normalized)
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "AccountDto.dateOfBirth = {0} is rejected")
    @ValueSource(strings = {
            "2024-02-30", // February never has 30 days (the exact QA reproduction value)
            "2023-02-29", // 2023 is NOT a leap year — Feb 29 is impossible
            "2024-04-31", // April has only 30 days
            "2025-13-01", // month 13 does not exist
            "2024-00-10", // month 00 does not exist
            "2024-01-32", // day 32 does not exist
            "2024-01-00"  // day 00 does not exist
    })
    @DisplayName("impossible account date-of-birth values are rejected, not normalized")
    void impossibleDateOfBirthIsRejected(String impossibleDate) {
        String json = "{\"dateOfBirth\":\"" + impossibleDate + "\"}";
        assertThatThrownBy(() -> mapper.readValue(json, AccountDto.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("impossible dates are rejected on every AccountDto LocalDate field")
    void impossibleDatesRejectedOnAllAccountFields() {
        assertThatThrownBy(() -> mapper.readValue("{\"openDate\":\"2024-02-30\"}", AccountDto.class))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"expirationDate\":\"2024-04-31\"}", AccountDto.class))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"reissueDate\":\"2023-02-29\"}", AccountDto.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("strictness applies globally to CardDto and TransactionDto LocalDate fields too")
    void impossibleDatesRejectedOnCardAndTransactionDtos() {
        assertThatThrownBy(() -> mapper.readValue("{\"expirationDate\":\"2024-02-30\"}", CardDto.class))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"originationDate\":\"2024-02-30\"}", TransactionDto.class))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"processingDate\":\"2023-02-29\"}", TransactionDto.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    // ------------------------------------------------------------------------
    // Valid dates must still be ACCEPTED with their EXACT value (no clamping)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("valid dates are accepted unchanged (incl. real leap-day 2024-02-29)")
    void validDatesAreAcceptedUnchanged() throws Exception {
        AccountDto dob = mapper.readValue("{\"dateOfBirth\":\"1961-06-08\"}", AccountDto.class);
        assertThat(dob.getDateOfBirth()).isEqualTo(LocalDate.of(1961, 6, 8));

        // 2024 IS a leap year, so Feb 29 is a real date and must be accepted as-is.
        AccountDto leap = mapper.readValue("{\"dateOfBirth\":\"2024-02-29\"}", AccountDto.class);
        assertThat(leap.getDateOfBirth()).isEqualTo(LocalDate.of(2024, 2, 29));

        AccountDto open = mapper.readValue("{\"openDate\":\"2020-12-31\"}", AccountDto.class);
        assertThat(open.getOpenDate()).isEqualTo(LocalDate.of(2020, 12, 31));
    }

    // ------------------------------------------------------------------------
    // The yyyy-MM-dd serialization contract must be preserved (output unchanged)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("serialization still emits the yyyy-MM-dd wire format")
    void serializationFormatIsPreserved() throws Exception {
        AccountDto dto = new AccountDto();
        dto.setDateOfBirth(LocalDate.of(1961, 6, 8));
        assertThat(mapper.writeValueAsString(dto)).contains("\"dateOfBirth\":\"1961-06-08\"");
    }

    @Test
    @DisplayName("a valid date round-trips through serialize -> deserialize unchanged")
    void validDateRoundTrips() {
        AccountDto dto = new AccountDto();
        dto.setOpenDate(LocalDate.of(1999, 1, 15));
        assertThatCode(() -> {
            String json = mapper.writeValueAsString(dto);
            AccountDto back = mapper.readValue(json, AccountDto.class);
            assertThat(back.getOpenDate()).isEqualTo(LocalDate.of(1999, 1, 15));
        }).doesNotThrowAnyException();
    }
}
