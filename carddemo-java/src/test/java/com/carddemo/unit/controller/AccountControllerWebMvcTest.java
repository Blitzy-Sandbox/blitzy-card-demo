package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.AccountController;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.model.dto.AccountUpdateResponse;
import com.carddemo.service.account.AccountUpdateService;
import com.carddemo.service.account.AccountViewService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer (MockMvc) contract tests for {@link AccountController}.
 *
 * <p>Proves the CP4 account-update optimistic-concurrency contract at the HTTP layer: the
 * {@code version} field is required on the update payload (omission fails {@code @Valid} with
 * {@code 400}), and a stale-version conflict raised by the service surfaces as {@code 409 Conflict}
 * through the global exception handler (review finding for the missing account-update
 * {@code version} echo / stale-client conflict detection).</p>
 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "carddemo.security.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long-0123456789"
})
class AccountControllerWebMvcTest {

    /** Minimal valid body: only {@code version} and {@code accountId} are required by the DTO. */
    private static final String VALID_BODY = "{\"version\":0,\"accountId\":\"12345\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountViewService accountViewService;

    @MockitoBean
    private AccountUpdateService accountUpdateService;

    @Test
    void updateAccount_validVersionedBody_isOk() throws Exception {
        when(accountUpdateService.updateAccount(any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/accounts/12345")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("12345"));
    }

    @Test
    void updateAccount_staleVersion_isConflict() throws Exception {
        // Stale optimistic-concurrency token must surface as 409 via GlobalExceptionHandler.
        when(accountUpdateService.updateAccount(any()))
                .thenThrow(new ConcurrencyException("Account was updated by another transaction"));

        mockMvc.perform(put("/api/accounts/12345")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void updateAccount_missingVersion_isBadRequest() throws Exception {
        // version is a required contract field (@NotNull); omission fails @Valid with 400.
        mockMvc.perform(put("/api/accounts/12345")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"12345\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateAccount_unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(put("/api/accounts/12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Builds a fully-populated {@link AccountUpdateResponse}. Field grouping mirrors the record
     * declaration exactly so the 45-component constructor stays aligned with the DTO.
     */
    private static AccountUpdateResponse sampleResponse() {
        return new AccountUpdateResponse(
                "12345",                            // accountId
                "Y",                                // accountStatus
                "2020", "01", "15",                 // open year/month/day
                new BigDecimal("5000.00"),          // creditLimit
                "2030", "12", "31",                 // expiration year/month/day
                new BigDecimal("1000.00"),          // cashCreditLimit
                "2024", "06", "01",                 // reissue year/month/day
                new BigDecimal("250.00"),           // currentBalance
                new BigDecimal("0.00"),             // currentCycleCredit
                "G1",                               // accountGroupId
                new BigDecimal("0.00"),             // currentCycleDebit
                "67890",                            // customerId
                "123", "45", "6789",                // ssn part1/2/3
                "1980", "05", "20",                 // dob year/month/day
                "750",                              // ficoScore
                "John", "Q", "Public",              // first/middle/last name
                "1 Main St", "NY", "Apt 2",         // addressLine1/stateCode/addressLine2
                "10001", "New York", "USA",         // zipCode/city/countryCode
                "212", "555", "1234",               // phone1 area/prefix/line
                "GID-1",                            // governmentIssuedId
                "646", "555", "5678",               // phone2 area/prefix/line
                "EFT-1",                            // eftAccountId
                "Y",                                // primaryCardHolderIndicator
                "Account updated successfully.",    // infoMessage
                null);                              // errorMessage
    }
}
