/*
 * UserListServiceTest.java — Unit Tests for Paginated User List Browse
 *
 * Tests the UserListService which migrates COBOL program COUSR00C.cbl
 * (695 lines, transaction CU00) — paginated browse of the USRSEC VSAM
 * KSDS dataset. These unit tests verify:
 *   - PAGE_SIZE constant is 10 (matches WS-USER-DATA OCCURS 10 TIMES)
 *   - Pagination with Spring Data Page/Pageable
 *   - Ascending sort by secUsrId (VSAM KSDS key order)
 *   - User ID filtering via listUsersFromId()
 *   - Password field NEVER appears in DTOs (security constraint)
 *   - Entity-to-DTO mapping completeness
 *
 * Testing approach: Pure unit tests with Mockito mocks — NO Spring context
 * loading. The UserSecurityRepository is mocked to return controlled Page
 * results, isolating the service layer logic.
 *
 * COBOL Traceability (COUSR00C.cbl, commit 27d6c6f):
 *   WS-USER-DATA OCCURS 10 TIMES (line 57)    → testListUsers_pageSize10
 *   PROCESS-PAGE-FORWARD (line 282)            → testListUsers_firstPage_returnsPage
 *   STARTBR USRSEC, DFHRESP(NOTFND) (line 600)→ testListUsers_emptyResult_returnsEmptyPage
 *   VSAM KSDS key order (SEC-USR-ID)           → testListUsers_sortBySecUsrIdAscending
 *   PROCESS-ENTER-KEY filter (line 218)        → testListUsersFromId_withStartId_filtersCorrectly
 *   USRIDINI = SPACES (line 218)               → testListUsersFromId_blankStartId_returnsAll
 *   SEC-USR-PWD never on list screen           → testListUsers_passwordNeverInDto
 *   POPULATE-USER-DATA (line 384)              → testListUsers_entityToDtoMapping
 *   10-record page display                     → testListUsers_multipleUsers
 */
package com.cardemo.unit.service;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserListService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserListService} — paginated user list browse.
 *
 * <p>Validates the service layer logic migrated from COBOL program COUSR00C.cbl
 * (695 lines, transaction CU00). The COBOL program provides paginated browse of
 * USRSEC VSAM KSDS records, displaying 10 users per BMS screen page with
 * forward/backward navigation via PF7/PF8 keys.</p>
 *
 * <p>Uses Mockito strict stubs via {@code @ExtendWith(MockitoExtension.class)}
 * with NO Spring context loading — tests exercise pure service logic only.</p>
 *
 * <p>Key security verification: password fields are NEVER included in
 * {@link UserSecurityDto} returned by the service's list/browse methods. The
 * COBOL program also does not display SEC-USR-PWD on the user list screen.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserListServiceTest {

    /**
     * Mocked repository for USRSEC VSAM data access.
     * Replaces CICS STARTBR/READNEXT/READPREV/ENDBR operations.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Service under test — UserListService with mocked repository injected.
     */
    @InjectMocks
    private UserListService userListService;

    /**
     * Argument captor for verifying Pageable parameters passed to repository.
     * Used to inspect page size, page number, and sort order.
     */
    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    /**
     * Admin user fixture — SEC-USR-TYPE = 'A' (ADMIN).
     * Represents a user record with full field population for mapping tests.
     */
    private UserSecurity adminUser;

    /**
     * Regular user fixture — SEC-USR-TYPE = 'U' (USER).
     * Second test fixture for multi-user page scenarios.
     */
    private UserSecurity regularUser;

    /**
     * Initializes test fixtures before each test method.
     *
     * <p>Creates two UserSecurity entities matching the COBOL SEC-USER-DATA
     * record layout (CSUSR01Y.cpy). Each entity has all fields populated
     * including a BCrypt password hash to verify that passwords are excluded
     * from DTOs during the list/browse operation.</p>
     */
    @BeforeEach
    void setUp() {
        // Admin user — SEC-USR-ID 'ADMIN001', SEC-USR-TYPE 'A'
        adminUser = new UserSecurity();
        adminUser.setSecUsrId("ADMIN001");
        adminUser.setSecUsrFname("Admin");
        adminUser.setSecUsrLname("UserOne");
        adminUser.setSecUsrPwd("$2a$10$xK7rQ5NlvWb.hashedAdminPassword");
        adminUser.setSecUsrType(UserType.ADMIN);

        // Regular user — SEC-USR-ID 'USER0001', SEC-USR-TYPE 'U'
        regularUser = new UserSecurity();
        regularUser.setSecUsrId("USER0001");
        regularUser.setSecUsrFname("Regular");
        regularUser.setSecUsrLname("UserTwo");
        regularUser.setSecUsrPwd("$2a$10$aB3cD4eF5gH.hashedRegularPassword");
        regularUser.setSecUsrType(UserType.USER);
    }

    // -----------------------------------------------------------------------
    // PAGE_SIZE Constant Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that the page size used by {@link UserListService#listUsers(int)}
     * is exactly 10, matching the COBOL {@code WS-USER-DATA OCCURS 10 TIMES}
     * (COUSR00C.cbl line 57).
     *
     * <p>The COBOL program reads exactly 10 records per READNEXT loop iteration
     * before checking for the existence of a next page. The Java migration must
     * preserve this exact page size to maintain behavioral parity.</p>
     */
    @Test
    void testListUsers_pageSize10() {
        // Arrange — stub repository with a single-user page
        Pageable expectedPageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(List.of(adminUser), expectedPageable, 1);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(entityPage);

        // Act — invoke the service method
        userListService.listUsers(0);

        // Assert — capture the Pageable argument and verify page size is exactly 10
        verify(userSecurityRepository).findAll(pageableCaptor.capture());
        Pageable capturedPageable = pageableCaptor.getValue();
        assertThat(capturedPageable.getPageSize()).isEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Pagination Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that requesting page 0 returns a valid {@link Page} of
     * {@link UserSecurityDto} with correct pagination metadata.
     *
     * <p>Maps COBOL PROCESS-PAGE-FORWARD (lines 282-331) — the initial page
     * display when the user first enters the COUSR00C screen.</p>
     */
    @Test
    void testListUsers_firstPage_returnsPage() {
        // Arrange — two users on the first page
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(
                List.of(adminUser, regularUser), pageable, 2);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(entityPage);

        // Act
        Page<UserSecurityDto> result = userListService.listUsers(0);

        // Assert — page metadata and content
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious()).isFalse();

        // Verify repository was called
        verify(userSecurityRepository).findAll(any(Pageable.class));
    }

    /**
     * Verifies that when no user records exist, the service returns an empty
     * page without throwing exceptions.
     *
     * <p>Maps COBOL {@code DFHRESP(NOTFND)} at STARTBR (lines 600-606) — when
     * the USRSEC file contains no records matching the browse start position,
     * the COBOL program sets the error flag and displays a "no records found"
     * message. The Java migration returns an empty Page instead.</p>
     */
    @Test
    void testListUsers_emptyResult_returnsEmptyPage() {
        // Arrange — empty result from repository
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> emptyPage = new PageImpl<>(
                Collections.emptyList(), pageable, 0);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        // Act
        Page<UserSecurityDto> result = userListService.listUsers(0);

        // Assert — empty page, no exception thrown
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
        assertThat(result.getTotalPages()).isEqualTo(0);

        // Verify repository was called
        verify(userSecurityRepository).findAll(any(Pageable.class));
    }

    /**
     * Verifies that the paginated query sorts results by {@code secUsrId} in
     * ascending order, matching the VSAM KSDS primary key order.
     *
     * <p>The USRSEC VSAM file has KEYS(8,0) — the 8-byte SEC-USR-ID at offset 0
     * is the primary key. STARTBR/READNEXT operations on a KSDS always return
     * records in ascending key order. The Spring Data Pageable must replicate
     * this by specifying {@code Sort.by("secUsrId").ascending()}.</p>
     */
    @Test
    void testListUsers_sortBySecUsrIdAscending() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(List.of(adminUser), pageable, 1);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(entityPage);

        // Act
        userListService.listUsers(0);

        // Assert — capture Pageable and verify ascending sort on secUsrId
        verify(userSecurityRepository).findAll(pageableCaptor.capture());
        Pageable capturedPageable = pageableCaptor.getValue();
        Sort sort = capturedPageable.getSort();
        assertThat(sort.isSorted()).isTrue();

        Sort.Order order = sort.getOrderFor("secUsrId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    // -----------------------------------------------------------------------
    // User ID Filtering Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link UserListService#listUsersFromId(String, int)} correctly
     * applies user ID filtering when a non-blank start ID is provided.
     *
     * <p>Maps COBOL PROCESS-ENTER-KEY (lines 218-222) — when the user enters
     * a value in USRIDINI, the COBOL program positions the STARTBR cursor at
     * the first record with SEC-USR-ID greater than or equal to the entered
     * value (GTEQ positioning). This is replicated by the repository's
     * {@code findBySecUsrIdGreaterThanEqual()} method.</p>
     */
    @Test
    void testListUsersFromId_withStartId_filtersCorrectly() {
        // Arrange — filter from "USER0001"
        String startId = "USER0001";
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(
                List.of(regularUser), pageable, 1);
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(
                eq(startId), any(Pageable.class))).thenReturn(entityPage);

        // Act
        Page<UserSecurityDto> result = userListService.listUsersFromId(startId, 0);

        // Assert — filtered result returned
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSecUsrId()).isEqualTo("USER0001");

        // Verify the filtered repository method was called with correct start ID
        verify(userSecurityRepository).findBySecUsrIdGreaterThanEqual(
                eq(startId), any(Pageable.class));
    }

    /**
     * Verifies that when a blank or null start user ID is provided to
     * {@link UserListService#listUsersFromId(String, int)}, the method
     * delegates to {@link UserListService#listUsers(int)} — returning all
     * users without filtering.
     *
     * <p>Maps COBOL logic at line 218:
     * {@code IF USRIDINI OF COUSR0AI = SPACES OR LOW-VALUES} — when the
     * user ID input field is blank, the COBOL program moves LOW-VALUES to
     * SEC-USR-ID, effectively starting the browse from the beginning of
     * the file.</p>
     */
    @Test
    void testListUsersFromId_blankStartId_returnsAll() {
        // Arrange — all users returned (no filter)
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(
                List.of(adminUser, regularUser), pageable, 2);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(entityPage);

        // Act — blank string should delegate to listUsers()
        Page<UserSecurityDto> blankResult = userListService.listUsersFromId("", 0);
        assertThat(blankResult).isNotNull();
        assertThat(blankResult.getContent()).hasSize(2);

        // Act — null should also delegate to listUsers()
        Page<UserSecurityDto> nullResult = userListService.listUsersFromId(null, 0);
        assertThat(nullResult).isNotNull();
        assertThat(nullResult.getContent()).hasSize(2);

        // Verify findAll was called twice (once per delegation), not findBySecUsrIdGreaterThanEqual
        verify(userSecurityRepository, times(2)).findAll(any(Pageable.class));
    }

    // -----------------------------------------------------------------------
    // Password Security Tests
    // -----------------------------------------------------------------------

    /**
     * CRITICAL security test: verifies that the password field is NEVER included
     * in {@link UserSecurityDto} objects returned by the list/browse service.
     *
     * <p>The COBOL program COUSR00C.cbl populates SEC-USR-ID, SEC-USR-FNAME,
     * SEC-USR-LNAME, and SEC-USR-TYPE into the BMS screen fields — but NEVER
     * displays SEC-USR-PWD on the user list screen. The Java migration must
     * maintain this security behavior by explicitly setting the DTO password
     * to {@code null}.</p>
     *
     * <p>This test verifies that even though the source entity has a non-null
     * BCrypt password hash, the DTO returned by the service always has a null
     * password field.</p>
     */
    @Test
    void testListUsers_passwordNeverInDto() {
        // Arrange — entity has a BCrypt password hash
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(List.of(adminUser), pageable, 1);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(entityPage);

        // Verify the source entity actually has a non-null password
        assertThat(adminUser.getSecUsrPwd()).isNotNull();

        // Act
        Page<UserSecurityDto> result = userListService.listUsers(0);

        // Assert — CRITICAL: password must NEVER appear in DTO
        assertThat(result.getContent()).hasSize(1);
        UserSecurityDto dto = result.getContent().get(0);
        assertThat(dto.getSecUsrPwd()).isNull();
    }

    // -----------------------------------------------------------------------
    // DTO Mapping Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that the {@link UserSecurity} entity is correctly mapped to a
     * {@link UserSecurityDto}, with all non-password fields preserved.
     *
     * <p>Maps COBOL paragraph POPULATE-USER-DATA (lines 384-441) which copies:
     * <pre>
     *   SEC-USR-ID    → USRID01I  (user ID in list row)
     *   SEC-USR-FNAME → FNAME01I  (first name in list row)
     *   SEC-USR-LNAME → LNAME01I  (last name in list row)
     *   SEC-USR-TYPE  → UTYPE01I  (user type in list row)
     * </pre>
     * Password (SEC-USR-PWD) is explicitly excluded.</p>
     */
    @Test
    void testListUsers_entityToDtoMapping() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(List.of(adminUser), pageable, 1);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(entityPage);

        // Act
        Page<UserSecurityDto> result = userListService.listUsers(0);

        // Assert — all entity fields correctly mapped to DTO (except password)
        assertThat(result.getContent()).hasSize(1);
        UserSecurityDto dto = result.getContent().get(0);

        // Verify each field maps correctly from entity to DTO
        assertThat(dto.getSecUsrId()).isEqualTo(adminUser.getSecUsrId());
        assertThat(dto.getSecUsrFname()).isEqualTo(adminUser.getSecUsrFname());
        assertThat(dto.getSecUsrLname()).isEqualTo(adminUser.getSecUsrLname());
        assertThat(dto.getSecUsrType()).isEqualTo(adminUser.getSecUsrType());

        // Password must be null — never mapped from entity to list DTO
        assertThat(dto.getSecUsrPwd()).isNull();
    }

    /**
     * Verifies that multiple user entities in a single page are all correctly
     * mapped to DTOs, with correct field values and passwords excluded.
     *
     * <p>Simulates the common COUSR00C scenario where the READNEXT loop populates
     * multiple rows of the WS-USER-DATA structure (up to 10 occurrences). Each
     * entity must be independently mapped to its own DTO with the correct
     * field values.</p>
     */
    @Test
    void testListUsers_multipleUsers() {
        // Arrange — page containing both admin and regular user
        List<UserSecurity> users = List.of(adminUser, regularUser);
        Pageable pageable = PageRequest.of(0, 10, Sort.by("secUsrId").ascending());
        Page<UserSecurity> entityPage = new PageImpl<>(users, pageable, 2);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(entityPage);

        // Act
        Page<UserSecurityDto> result = userListService.listUsers(0);

        // Assert — both users mapped correctly
        assertThat(result.getContent()).hasSize(2);

        // First user — Admin (ADMIN001)
        UserSecurityDto firstDto = result.getContent().get(0);
        assertThat(firstDto.getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(firstDto.getSecUsrFname()).isEqualTo("Admin");
        assertThat(firstDto.getSecUsrLname()).isEqualTo("UserOne");
        assertThat(firstDto.getSecUsrType()).isEqualTo(UserType.ADMIN);
        assertThat(firstDto.getSecUsrPwd()).isNull();

        // Second user — Regular (USER0001)
        UserSecurityDto secondDto = result.getContent().get(1);
        assertThat(secondDto.getSecUsrId()).isEqualTo("USER0001");
        assertThat(secondDto.getSecUsrFname()).isEqualTo("Regular");
        assertThat(secondDto.getSecUsrLname()).isEqualTo("UserTwo");
        assertThat(secondDto.getSecUsrType()).isEqualTo(UserType.USER);
        assertThat(secondDto.getSecUsrPwd()).isNull();
    }
}
