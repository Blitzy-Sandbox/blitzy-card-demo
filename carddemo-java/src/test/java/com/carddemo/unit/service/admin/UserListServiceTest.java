package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.carddemo.model.dto.UserListResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.admin.UserListService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link UserListService} (COBOL {@code COUSR00C} parity): ten-rows-per-page
 * browse in ascending user-id order, the row projection, and the boundary/navigation messages.
 */
@ExtendWith(MockitoExtension.class)
class UserListServiceTest {

    @Mock
    private UserSecurityRepository userSecurityRepository;

    private UserListService service() {
        return new UserListService(userSecurityRepository);
    }

    private static UserSecurity user(String id) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);
        u.setSecUsrFname("F" + id);
        u.setSecUsrLname("L" + id);
        u.setSecUsrType(UserType.USER);
        return u;
    }

    @Test
    void emptyDatasetReturnsTopOfPageMessage() {
        Pageable pageable = PageRequest.of(0, 10);
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        UserListResponse response = service().listUsers(1, "");

        assertThat(response.users()).isEmpty();
        assertThat(response.errorMessage()).isEqualTo("You are at the top of the page...");
        assertThat(response.pageNumber()).isEqualTo("1");
    }

    @Test
    void populatedPageProjectsRowsWithBlankSelectionFlag() {
        Pageable pageable = PageRequest.of(0, 10);
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user("ADMIN001"), user("USER0002")), pageable, 2));

        UserListResponse response = service().listUsers(1, "ABC");

        assertThat(response.users()).hasSize(2);
        UserListResponse.UserListItem first = response.users().get(0);
        assertThat(first.selectionFlag()).isNull();
        assertThat(first.userId()).isEqualTo("ADMIN001");
        assertThat(first.firstName()).isEqualTo("FADMIN001");
        assertThat(first.userType()).isEqualTo(UserType.USER);
        assertThat(response.userIdFilter()).isEqualTo("ABC");
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void forwardPagePastLastReturnsBottomOfPageMessage() {
        // Non-empty dataset (total>0) but an empty page beyond the last => bottom-of-page.
        Pageable pageable = PageRequest.of(2, 10);
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 15));

        UserListResponse response = service().listUsers(3, null);

        assertThat(response.users()).isEmpty();
        assertThat(response.errorMessage()).isEqualTo("You have reached the bottom of the page...");
        assertThat(response.pageNumber()).isEqualTo("3");
    }
}
