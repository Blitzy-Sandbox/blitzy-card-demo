package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.model.dto.UserListResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.admin.UserListService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Unit tests for {@link com.carddemo.service.admin.UserListService} (&larr; COBOL COUSR00C, SHA 27d6c6f). */
@ExtendWith(MockitoExtension.class)
class UserListServiceTest {

    private static final int PAGE_SIZE = 10;

    private static final String DUMMY_PASSWORD = "$2a$10$DUMMYHASHdoNotEcho";

    private static final String MSG_TOP_OF_PAGE = "You are at the top of the page...";
    private static final String MSG_BOTTOM_OF_PAGE = "You have reached the bottom of the page...";
    private static final String MSG_ALREADY_AT_TOP = "You are already at the top of the page...";

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @InjectMocks
    private UserListService userListService;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private static UserSecurity user(String id, String fn, String ln, UserType type) {
        UserSecurity entity = new UserSecurity();
        entity.setSecUsrId(id);
        entity.setSecUsrFname(fn);
        entity.setSecUsrLname(ln);
        entity.setSecUsrType(type);
        entity.setSecUsrPwd(DUMMY_PASSWORD);
        return entity;
    }

    @Test
    void listUsers_happyPath_mapsRowsAndBuildsPageable() {
        List<UserSecurity> rows = List.of(
                user("USER0001", "Ann", "Adams", UserType.ADMIN),
                user("USER0002", "Bob", "Brown", UserType.USER),
                user("USER0003", "Cara", "Cole", UserType.USER));
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, PAGE_SIZE, Sort.by("secUsrId").ascending()), 3L));

        UserListResponse response = userListService.listUsers(1, null);

        assertThat(response.users()).hasSize(3);
        assertThat(response.users().get(0))
                .isEqualTo(new UserListResponse.UserListItem(null, "USER0001", "Ann", "Adams", UserType.ADMIN));
        assertThat(response.users().get(0).selectionFlag()).isNull();
        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.errorMessage()).isNull();

        verify(userSecurityRepository).findAll(pageableCaptor.capture());
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(0);
        assertThat(captured.getPageSize()).isEqualTo(PAGE_SIZE);
        Sort.Order order = captured.getSort().getOrderFor("secUsrId");
        assertThat(order).isNotNull();
        assertThat(order.isAscending()).isTrue();
        verifyNoMoreInteractions(userSecurityRepository);
    }

    @Test
    void listUsers_secondPage_usesPageIndexOne() {
        List<UserSecurity> rows = List.of(user("USER0011", "Dee", "Davis", UserType.USER));
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(1, PAGE_SIZE), 30L));

        UserListResponse response = userListService.listUsers(2, null);

        assertThat(response.errorMessage()).isNull();

        verify(userSecurityRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    void listUsers_emptyDataset_setsTopMessage() {
        List<UserSecurity> rows = Collections.emptyList();
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, PAGE_SIZE), 0L));

        UserListResponse response = userListService.listUsers(1, null);

        assertThat(response.users()).isEmpty();
        assertThat(response.errorMessage()).isEqualTo(MSG_TOP_OF_PAGE);
    }

    @Test
    void listUsers_forwardBeyondLastPage_setsBottomMessage() {
        List<UserSecurity> rows = Collections.emptyList();
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(5, PAGE_SIZE), 30L));

        UserListResponse response = userListService.listUsers(6, null);

        assertThat(response.users()).isEmpty();
        assertThat(response.errorMessage()).isEqualTo(MSG_BOTTOM_OF_PAGE);
    }

    @Test
    void listUsers_backwardBelowFirstPage_setsTopMessage() {
        List<UserSecurity> rows = List.of(user("USER0001", "Ann", "Adams", UserType.ADMIN));
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, PAGE_SIZE), 1L));

        UserListResponse response = userListService.listUsers(0, null);

        assertThat(response.errorMessage()).isEqualTo(MSG_ALREADY_AT_TOP);

        verify(userSecurityRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    void listUsers_echoesUserIdFilterWithoutFiltering() {
        List<UserSecurity> rows = List.of(user("USER0001", "Ann", "Adams", UserType.ADMIN));
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, PAGE_SIZE), 1L));

        UserListResponse response = userListService.listUsers(1, "USER0001");

        assertThat(response.userIdFilter()).isEqualTo("USER0001");

        verify(userSecurityRepository).findAll(any(Pageable.class));
        verifyNoMoreInteractions(userSecurityRepository);
    }

    @Test
    void listUsers_neverExposesPassword() {
        List<UserSecurity> rows = List.of(
                user("USER0001", "Ann", "Adams", UserType.ADMIN),
                user("USER0002", "Bob", "Brown", UserType.USER));
        when(userSecurityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, PAGE_SIZE), 2L));

        UserListResponse response = userListService.listUsers(1, null);

        assertThat(response.users()).hasSize(2);
        for (UserListResponse.UserListItem item : response.users()) {
            assertThat(item.selectionFlag()).isNotEqualTo(DUMMY_PASSWORD);
            assertThat(item.userId()).isNotEqualTo(DUMMY_PASSWORD);
            assertThat(item.firstName()).isNotEqualTo(DUMMY_PASSWORD);
            assertThat(item.lastName()).isNotEqualTo(DUMMY_PASSWORD);
        }
    }
}
