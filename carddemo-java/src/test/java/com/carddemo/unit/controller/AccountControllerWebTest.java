package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.AccountController;
import com.carddemo.model.dto.AccountUpdateRequest;
import com.carddemo.service.account.AccountUpdateService;
import com.carddemo.service.account.AccountViewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link AccountController} asserting that {@code PUT /api/accounts/{accountId}}
 * binds and enforces the path identifier against the request-body account id.
 *
 * <p>Before the fix the handler ignored the {@code {accountId}} path variable and updated whatever
 * account id the body carried, so {@code PUT /api/accounts/111} with a body addressing {@code 222}
 * silently mutated account {@code 222}. The handler now compares the two (numerically, tolerating
 * zero padding) and rejects a mismatch with {@code 400} before the service is invoked
 * (api-contracts.md account-update contract; COACTUPC parity, source commit 27d6c6f — reference
 * only, no COBOL copied).</p>
 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // >= 32 bytes so SecurityConfig.hmacKey() accepts it; test-only, never a real secret.
        "carddemo.security.jwt.secret=carddemo-web-test-signing-secret-0123456789"
})
@DisplayName("AccountController web slice - PUT path/body identifier binding")
class AccountControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountViewService accountViewService;

    @MockitoBean
    private AccountUpdateService accountUpdateService;

    @Test
    @DisplayName("matching path and body account id updates the targeted account (service invoked)")
    void matchingPathAndBodyInvokesService() throws Exception {
        mockMvc.perform(put("/api/accounts/111")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"111\",\"version\":0}"))
                .andExpect(status().isOk());

        verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
    }

    @Test
    @DisplayName("mismatched path and body account id is rejected with 400 and the service is never called")
    void mismatchedPathAndBodyIsRejected() throws Exception {
        mockMvc.perform(put("/api/accounts/111")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"222\",\"version\":0}"))
                .andExpect(status().isBadRequest());

        verify(accountUpdateService, never()).updateAccount(any());
    }
}
