package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.controller.UserAdminController;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Focused unit test for {@link UserAdminController#updateUser(String, UserSecurityDto)} CWE-20 null-body
 * handling. The controller deliberately omits {@code @Valid} so the {@link UserUpdateService} owns the
 * ordered, verbatim COBOL edit messages ({@code COUSR02C}); the controller's only responsibility is to
 * stamp the authoritative path {@code {id}} onto the request and delegate.
 *
 * <p>Before the fix a JSON {@code null} request body NPE'd on {@code request.setUserId(id)} and surfaced
 * as a generic HTTP 500. The fix synthesizes an empty {@link UserSecurityDto} when the body is absent, so
 * the path id is still stamped and the request flows into the service's ordered edits (which then reject
 * the absent first name with an HTTP 400 in production). This test proves the synthesize-and-delegate
 * behaviour in isolation with a mocked service.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAdminController — CWE-20 null-body guard (PUT /api/admin/users/{id})")
class UserAdminControllerTest {

    @Mock
    private UserListService userListService;

    @Mock
    private UserAddService userAddService;

    @Mock
    private UserUpdateService userUpdateService;

    @Mock
    private UserDeleteService userDeleteService;

    @InjectMocks
    private UserAdminController controller;

    @Test
    @DisplayName("PUT with JSON null body: synthesizes an empty DTO, stamps the path id, and delegates (no NPE/500)")
    void updateUser_nullBody_synthesizesDtoStampsPathIdAndDelegates() {
        final String pathId = "00000007";
        // The service is the authority for the ordered COBOL edits; here it simply echoes its argument so
        // the controller's synthesize-and-delegate behaviour can be asserted in isolation.
        when(userUpdateService.updateUser(any(UserSecurityDto.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // A JSON `null` body must NOT NPE on setUserId; the controller synthesizes an empty DTO instead.
        final ResponseEntity<UserSecurityDto> response = controller.updateUser(pathId, null);

        // The delegated DTO is a non-null synthesized instance carrying the authoritative path id, so the
        // service runs its user-id edit (passes) and would then reject the absent first name with a 400 in
        // production -- never a controller-level 500.
        final ArgumentCaptor<UserSecurityDto> captor = ArgumentCaptor.forClass(UserSecurityDto.class);
        verify(userUpdateService).updateUser(captor.capture());
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue().getUserId()).isEqualTo(pathId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
