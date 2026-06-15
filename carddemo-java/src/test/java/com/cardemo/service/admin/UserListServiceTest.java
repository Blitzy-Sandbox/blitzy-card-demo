package com.cardemo.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pure-JVM behavioral-parity unit test for {@link UserListService}, the Java migration of the legacy
 * AWS CardDemo admin user-list CICS program {@code app/cbl/COUSR00C.cbl} (transaction {@code CU00},
 * BMS map {@code COUSR0A}; frozen baseline commit SHA {@code 27d6c6f}).
 *
 * <p>These tests pin the observable browse contract the migration must reproduce exactly (AAP
 * &sect;0.7.2): the page size is exactly ten ({@code USER-REC OCCURS 10 TIMES}); the browse is keyed
 * greater-than-or-equal and ordered ascending on {@code SEC-USR-ID}; a blank/whitespace start filter
 * starts the browse at the first record ({@code COUSR00C} L218-221, {@code LOW-VALUES}); and the page
 * number is one-based and clamped to a floor of one ({@code CDEMO-CU00-PAGE-NUM}). They also assert the
 * per-row field mapping and that the credential is <strong>never</strong> exposed on a browse row.</p>
 *
 * <p>Fast and isolated: the {@link UserSecurityRepository} collaborator is a Mockito mock, so there is
 * no Spring context, database or Testcontainers (Surefire-compatible). The COBOL source is read-only
 * reference and is never copied into this repository; only its behavior is asserted.</p>
 */
class UserListServiceTest {

    /** Mocked data-access collaborator (the {@code USRSEC} VSAM replacement). */
    private UserSecurityRepository repository;

    /** The system under test. */
    private UserListService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserSecurityRepository.class);
        service = new UserListService(repository);
    }

    // -------------------------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------------------------

    /**
     * Builds a {@link UserSecurity} fixture with every mapped field populated, including a credential,
     * so tests can assert the password is never copied onto the browse output.
     */
    private static UserSecurity user(String id, String firstName, String lastName, String passwordHash,
                                     UserType type) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);
        u.setSecUsrFname(firstName);
        u.setSecUsrLname(lastName);
        u.setSecUsrPwd(passwordHash);
        u.setSecUsrType(type);
        return u;
    }

    /** Stubs the GTEQ paginated browse to return the supplied content as a single page. */
    private void stubBrowse(List<UserSecurity> content) {
        when(repository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content));
    }

    /** Captures the (startKey, Pageable) the service passed to the repository on its single call. */
    private Pageable captureBrowse() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findBySecUsrIdGreaterThanEqual(anyString(), pageableCaptor.capture());
        return pageableCaptor.getValue();
    }

    /** Captures the start key the service passed to the repository on its single call. */
    private String captureStartKey() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).findBySecUsrIdGreaterThanEqual(keyCaptor.capture(), any(Pageable.class));
        return keyCaptor.getValue();
    }

    // -------------------------------------------------------------------------------------------
    // (a) Start-key derivation -- COUSR00C L218-221 (USRIDINI = SPACES OR LOW-VALUES -> LOW-VALUES)
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Start key (COUSR00C L218-221: blank USRIDINI -> start from beginning)")
    class StartKey {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "\t", " \n "})
        @DisplayName("a null/blank/whitespace filter starts the browse from the beginning (\"\")")
        void blankFilterStartsFromBeginning(String filter) {
            stubBrowse(Collections.emptyList());

            service.listUsers(filter, 1);

            // LOW-VALUES -> empty start key (sorts first under the ascending secUsrId order).
            assertThat(captureStartKey()).isEqualTo("");
        }

        @Test
        @DisplayName("a supplied filter is trimmed and used as the GTEQ start key")
        void suppliedFilterIsTrimmedStartKey() {
            stubBrowse(Collections.emptyList());

            service.listUsers("  ADMIN001  ", 1);

            assertThat(captureStartKey()).isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("a supplied filter with no surrounding whitespace is used verbatim")
        void suppliedFilterUsedVerbatim() {
            stubBrowse(Collections.emptyList());

            service.listUsers("USER0005", 1);

            assertThat(captureStartKey()).isEqualTo("USER0005");
        }
    }

    // -------------------------------------------------------------------------------------------
    // (b)+(c) Pagination contract -- page size 10, ascending secUsrId, 1-based clamped page number
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Pagination (USER-REC OCCURS 10 TIMES; ascending SEC-USR-ID; 1-based page)")
    class Pagination {

        @Test
        @DisplayName("page size is exactly 10 and bound to the DTO contract constant")
        void pageSizeIsExactlyTen() {
            stubBrowse(Collections.emptyList());

            service.listUsers(null, 1);

            assertThat(captureBrowse().getPageSize()).isEqualTo(10);
            assertThat(UserSecurityDto.ROWS_PER_PAGE).isEqualTo(10);
        }

        @Test
        @DisplayName("browse is ordered ascending by the secUsrId key property")
        void browseSortedBySecUsrIdAscending() {
            stubBrowse(Collections.emptyList());

            service.listUsers(null, 1);

            Sort.Order order = captureBrowse().getSort().getOrderFor("secUsrId");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("a 1-based page number maps to the 0-based Spring Data index and is echoed back")
        void oneBasedPageNumberConvertedAndEchoed() {
            stubBrowse(Collections.emptyList());

            UserSecurityDto dto = service.listUsers(null, 3);

            // page 3 (1-based) -> Spring Data page index 2 (0-based).
            assertThat(captureBrowse().getPageNumber()).isEqualTo(2);
            // pageNumber echoed 1-based, ZERO-PADDED to the full PIC X(8) width (F-PAGE-001).
            assertThat(dto.getPageNumber()).isEqualTo("00000003");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100, 1})
        @DisplayName("a page number below 1 is clamped to 1 (CDEMO-CU00-PAGE-NUM floor)")
        void pageNumberClampedToOne(int requested) {
            stubBrowse(Collections.emptyList());

            UserSecurityDto dto = service.listUsers(null, requested);

            // Clamped to 1 (1-based) -> Spring Data page index 0.
            assertThat(captureBrowse().getPageNumber()).isEqualTo(0);
            // pageNumber echoed 1-based, ZERO-PADDED to the full PIC X(8) width (F-PAGE-001).
            assertThat(dto.getPageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("the start filter is echoed back on the response")
        void filterEchoedOnResponse() {
            stubBrowse(Collections.emptyList());

            UserSecurityDto dto = service.listUsers("USER0007", 1);

            assertThat(dto.getUserIdFilter()).isEqualTo("USER0007");
        }
    }

    // -------------------------------------------------------------------------------------------
    // (b)+(d) Row mapping -- order preserved, <=10 rows, field mapping, NO password exposure
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Row mapping (POPULATE-USER-DATA: id/fname/lname/type; password never exposed)")
    class RowMapping {

        @Test
        @DisplayName("up to 10 rows are mapped, preserving the ascending browse order")
        void mapsUpToTenRowsInOrder() {
            List<UserSecurity> rows = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                String id = String.format("USER%04d", i);
                rows.add(user(id, "First" + i, "Last" + i, "$2a$10$hashvalue" + i,
                        (i % 2 == 0) ? UserType.ADMIN : UserType.USER));
            }
            stubBrowse(rows);

            UserSecurityDto dto = service.listUsers(null, 1);

            assertThat(dto.getUsers()).hasSize(10);
            List<String> ids = dto.getUsers().stream().map(UserSecurityDto.UserListItem::getUserId).toList();
            assertThat(ids).containsExactly(
                    "USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                    "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");
        }

        @Test
        @DisplayName("each row maps id/firstName/lastName/userType; selectionFlag is blank; password is NOT carried")
        void fieldMappingCorrectAndPasswordNeverPopulated() {
            String secretHash = "$2a$10$SHOULD-NEVER-LEAK-TO-LIST-OUTPUT";
            UserSecurity entity = user("ADMIN001", "Alice", "Anderson", secretHash, UserType.ADMIN);
            stubBrowse(List.of(entity));

            UserSecurityDto dto = service.listUsers(null, 1);

            assertThat(dto.getUsers()).hasSize(1);
            UserSecurityDto.UserListItem item = dto.getUsers().get(0);

            assertThat(item.getUserId()).isEqualTo("ADMIN001");
            assertThat(item.getFirstName()).isEqualTo("Alice");
            assertThat(item.getLastName()).isEqualTo("Anderson");
            assertThat(item.getUserType()).isEqualTo(UserType.ADMIN);
            // SEL000n is a blank input field on display.
            assertThat(item.getSelectionFlag()).isEqualTo("");

            // The credential must never appear on any string-valued field of the browse row.
            assertThat(item.getSelectionFlag()).doesNotContain(secretHash);
            assertThat(item.getUserId()).doesNotContain(secretHash);
            assertThat(item.getFirstName()).doesNotContain(secretHash);
            assertThat(item.getLastName()).doesNotContain(secretHash);
            // The row type structurally carries no password field, so toString cannot leak it.
            assertThat(item.toString()).doesNotContain(secretHash);
        }

        @Test
        @DisplayName("an empty page yields an empty users list and throws no exception (read-only browse)")
        void emptyPageReturnsEmptyUsers() {
            stubBrowse(Collections.emptyList());

            UserSecurityDto dto = service.listUsers(null, 1);

            assertThat(dto.getUsers()).isNotNull().isEmpty();
            // pageNumber echoed 1-based, ZERO-PADDED to the full PIC X(8) width (F-PAGE-001).
            assertThat(dto.getPageNumber()).isEqualTo("00000001");
        }
    }
}
