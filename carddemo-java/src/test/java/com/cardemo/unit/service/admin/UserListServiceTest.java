package com.cardemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.dto.UserSecurityDto.UserListItem;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserListService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Fast, fully-mocked behavioral-parity unit test for
 * {@link com.cardemo.service.admin.UserListService}, the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * migration of the legacy AWS CardDemo admin user-list CICS program
 * {@code app/cbl/COUSR00C.cbl} (CICS transaction {@code CU00}, BMS map {@code COUSR0A},
 * &ldquo;List Users&rdquo;). The COBOL is read-only reference material at the frozen baseline
 * commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only its observable
 * behavior is asserted here (AAP &sect;0.7.1&ndash;&sect;0.7.2).
 *
 * <h2>Test strategy &mdash; pure Mockito, no Spring</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em>
 * {@code @SpringBootTest}, no Spring context, no {@code @DataJpaTest}, no database, no
 * Testcontainers and no file/network I/O. The {@link UserSecurityRepository} collaborator is a
 * Mockito {@code @Mock} and the system under test is wired by constructor injection via
 * {@code @InjectMocks}. Because the service is invoked directly (not through a Spring AOP proxy),
 * its {@code @PreAuthorize}/{@code @Transactional(readOnly = true)} advice is intentionally not
 * exercised &mdash; that wiring is verified by the integration tier, not by this fast unit tier.</p>
 *
 * <p>The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}), so every
 * {@code when(...)} stub is exercised by the test that declares it (each test stubs the single
 * browse method exactly once and the service consumes it exactly once) &mdash; no {@code lenient()}
 * is needed anywhere.</p>
 *
 * <h2>The single most important parity assertion &mdash; page size is EXACTLY ten</h2>
 * <p>The legacy {@code COUSR00C} screen painted a fixed {@code 02 USER-REC OCCURS 10 TIMES} row
 * array ({@code COUSR00C} L57); the forward-read loop filled at most ten rows per page. The
 * make-or-break parity check is therefore the {@link ArgumentCaptor}-based assertion that the
 * {@link Pageable} this service builds has {@code getPageSize() == 10}, with an ascending
 * {@code secUsrId} sort (the {@code STARTBR(GTEQ)} + {@code READNEXT} browse order).</p>
 *
 * <h2>Pinned behaviors (verified against the on-disk sources)</h2>
 * <ul>
 *   <li>Page size is ten and the browse is ascending on {@code secUsrId}.</li>
 *   <li>A null/blank/whitespace filter positions the browse at the first record (empty start key,
 *       the {@code LOW-VALUES} equivalent &mdash; {@code COUSR00C} L218-221); a non-blank filter is
 *       trimmed and used as the greater-than-or-equal start key.</li>
 *   <li>The page number is one-based and clamped to a floor of one; it is converted to Spring
 *       Data's zero-based index, and the one-based value is echoed on the response DTO. The DTO's
 *       {@code pageNumber} accessor is a {@link String} (the legacy {@code PAGENUM} is
 *       {@code PIC X(8)}), zero-padded to the full width (F-PAGE-001), so the echoed value is
 *       asserted as {@code "00000001"}/{@code "00000003"}.</li>
 *   <li>Each browsed row maps {@code secUsrId}/{@code secUsrFname}/{@code secUsrLname}/
 *       {@code secUsrType} field-for-field; the credential ({@code secUsrPwd}) is never exposed.</li>
 *   <li>The selection flag is left blank on display (row selection routing is a controller concern,
 *       out of scope here).</li>
 *   <li>The read-only browse returns an empty list (never an exception) past end-of-data.</li>
 * </ul>
 *
 * <p>There is <strong>no</strong> {@code USRSEC} golden fixture under {@code app/data/ASCII/}
 * (only nine fixtures exist, none for users), so the test data is built inline by the
 * {@link #user(String, String, String, UserType)} helper. User ids are {@code X(08)} (max eight
 * characters) and user names are {@code X(20)} (max twenty), per {@code app/cpy/CSUSR01Y.cpy}.</p>
 *
 * @see com.cardemo.service.admin.UserListService
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.dto.UserSecurityDto
 * @see com.cardemo.model.dto.UserSecurityDto.UserListItem
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserListService — COUSR00C user-list browse parity (CU00, 10 rows/page)")
class UserListServiceTest {

    /**
     * A deliberately fake, clearly non-secret BCrypt-shaped fixture for the entity's password
     * field. It exists only to prove the list output never echoes the credential; it is not a real
     * hash and matches no live provider value (secret-sanitization rule V.S1).
     */
    private static final String BCRYPT_HASH_FIXTURE = "$2a$10$doesNotMatterForList";

    /** Mocked data-access collaborator (the {@code USRSEC} VSAM KSDS replacement). */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** The system under test, constructor-injected with the single mock collaborator. */
    @InjectMocks
    private UserListService userListService;

    /** Captures the {@link Pageable} the service hands to the browse query. */
    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    /** Captures the greater-than-or-equal start key the service hands to the browse query. */
    @Captor
    private ArgumentCaptor<String> startKeyCaptor;

    // ------------------------------------------------------------------------------------------------
    // Inline test-data helpers. No USRSEC golden fixture exists, so rows are built by hand.
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds a {@link UserSecurity} row with the five mapped fields populated. The password is the
     * fake {@link #BCRYPT_HASH_FIXTURE} (never surfaced by the list).
     *
     * @param id    the eight-character user id ({@code SEC-USR-ID PIC X(08)})
     * @param fname the twenty-character first name ({@code SEC-USR-FNAME PIC X(20)})
     * @param lname the twenty-character last name ({@code SEC-USR-LNAME PIC X(20)})
     * @param type  the user role ({@code SEC-USR-TYPE PIC X(01)})
     * @return a fully-populated detached entity instance
     */
    private static UserSecurity user(String id, String fname, String lname, UserType type) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);
        u.setSecUsrFname(fname);
        u.setSecUsrLname(lname);
        u.setSecUsrPwd(BCRYPT_HASH_FIXTURE);
        u.setSecUsrType(type);
        return u;
    }

    /**
     * Wraps the supplied rows in a {@link Page} the same way Spring Data would return a browse page.
     *
     * @param users the page content (zero or more rows; an empty call models an exhausted browse)
     * @return a {@link Page} over the supplied rows
     */
    private static Page<UserSecurity> pageOf(UserSecurity... users) {
        // List.of(...) is @SafeVarargs and UserSecurity is reifiable, so this is warning-free.
        return new PageImpl<>(List.of(users));
    }

    // ------------------------------------------------------------------------------------------------
    // 1. Page size is exactly ten -> COBOL 02 USER-REC OCCURS 10 TIMES [COUSR00C L57].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Browse page size is exactly 10 — USER-REC OCCURS 10 TIMES [COUSR00C L57]")
    void shouldUsePageSizeOfTenMirroringUserRecOccursTen() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        userListService.listUsers("", 1);

        verify(userSecurityRepository)
                .findBySecUsrIdGreaterThanEqual(anyString(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    // ------------------------------------------------------------------------------------------------
    // 2. Ascending browse order on secUsrId -> STARTBR(GTEQ)+READNEXT key order [COUSR00C L586-629].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Browse is ordered ascending by secUsrId — STARTBR(GTEQ)+READNEXT order [COUSR00C L586-629]")
    void shouldBrowseAscendingBySecUsrId() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        userListService.listUsers("", 1);

        verify(userSecurityRepository)
                .findBySecUsrIdGreaterThanEqual(anyString(), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("secUsrId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    // ------------------------------------------------------------------------------------------------
    // 3. Blank/null/whitespace filter -> start key "" (LOW-VALUES, start at first record)
    //    [COUSR00C L218-221].
    // ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "filter=[{0}] -> start key \"\"")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Blank/null/whitespace filter starts browse at first record — LOW-VALUES [COUSR00C L218-221]")
    void shouldStartFromBeginningWhenFilterBlank(String blankFilter) {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        userListService.listUsers(blankFilter, 1);

        verify(userSecurityRepository)
                .findBySecUsrIdGreaterThanEqual(startKeyCaptor.capture(), any(Pageable.class));
        assertThat(startKeyCaptor.getValue()).isEqualTo("");
    }

    // ------------------------------------------------------------------------------------------------
    // 4. Non-blank filter is trimmed and forwarded as the GTEQ start key [COUSR00C L221].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Non-blank filter is trimmed and forwarded as the GTEQ start key [COUSR00C L221]")
    void shouldTrimAndForwardNonBlankFilter() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        userListService.listUsers("  USER0005  ", 1);

        verify(userSecurityRepository)
                .findBySecUsrIdGreaterThanEqual(startKeyCaptor.capture(), any(Pageable.class));
        assertThat(startKeyCaptor.getValue()).isEqualTo("USER0005");
    }

    // ------------------------------------------------------------------------------------------------
    // 5. Page number below one is clamped to one (floor) -> 0-based index 0; DTO echoes "1"
    //    [COUSR00C L366-369; service Math.max(pageNumber, 1)].
    // ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "pageNumber={0} -> clamped to 1 (0-based 0)")
    @ValueSource(ints = {0, -3})
    @DisplayName("Page number below one is clamped to one — CDEMO-CU00-PAGE-NUM floor [COUSR00C L366-369]")
    void shouldClampPageNumberFloorToOne(int belowOne) {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        UserSecurityDto dto = userListService.listUsers("", belowOne);

        verify(userSecurityRepository)
                .findBySecUsrIdGreaterThanEqual(anyString(), pageableCaptor.capture());
        // Clamped one-based page 1 -> Spring Data zero-based index 0.
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        // DTO echoes the one-based page number ZERO-PADDED to the full PIC X(8) width (F-PAGE-001),
        // so page 1 -> "00000001" (byte-identical to the sibling COTRN00C PAGENUM PIC 9(08)).
        assertThat(dto.getPageNumber()).isEqualTo("00000001");
    }

    // ------------------------------------------------------------------------------------------------
    // 6. One-based page number converts to Spring Data's zero-based index; DTO echoes the 1-based value
    //    [COUSR00C L327].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("One-based page 3 -> zero-based index 2; DTO echoes \"00000003\" [COUSR00C L327]")
    void shouldConvertOneBasedPageToZeroBased() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        UserSecurityDto dto = userListService.listUsers("", 3);

        verify(userSecurityRepository)
                .findBySecUsrIdGreaterThanEqual(anyString(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        // DTO echoes the one-based page number ZERO-PADDED to the full PIC X(8) width (F-PAGE-001).
        assertThat(dto.getPageNumber()).isEqualTo("00000003");
    }

    // ------------------------------------------------------------------------------------------------
    // 7. Each browsed row maps field-for-field (id/first/last/type) -> POPULATE-USER-DATA [COUSR00C L388].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Each row maps secUsrId/Fname/Lname/Type field-for-field — POPULATE-USER-DATA [COUSR00C L388]")
    void shouldMapEntityFieldsToDtoItems() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf(
                        user("USER0001", "Alice", "Anderson", UserType.ADMIN),
                        user("USER0002", "Bob", "Brown", UserType.USER)));

        UserSecurityDto dto = userListService.listUsers("", 1);

        List<UserListItem> rows = dto.getUsers();
        // The page content (at most ROWS_PER_PAGE = 10 rows) maps one-to-one onto the DTO list.
        assertThat(rows).hasSize(2);

        UserListItem first = rows.get(0);
        assertThat(first.getUserId()).isEqualTo("USER0001");
        assertThat(first.getFirstName()).isEqualTo("Alice");
        assertThat(first.getLastName()).isEqualTo("Anderson");
        assertThat(first.getUserType()).isEqualTo(UserType.ADMIN);

        UserListItem second = rows.get(1);
        assertThat(second.getUserId()).isEqualTo("USER0002");
        assertThat(second.getFirstName()).isEqualTo("Bob");
        assertThat(second.getLastName()).isEqualTo("Brown");
        assertThat(second.getUserType()).isEqualTo(UserType.USER);
    }

    // ------------------------------------------------------------------------------------------------
    // 8. The credential is never exposed in list output (no password accessor; no field equals the hash).
    //    SEC-USR-PWD is intentionally not mapped onto a browse row [COUSR00C POPULATE-USER-DATA].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Browse output never exposes the credential — no password accessor, no hash in any field")
    void shouldNeverExposePasswordInListOutput() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf(user("USER0001", "Alice", "Anderson", UserType.ADMIN)));

        UserSecurityDto dto = userListService.listUsers("", 1);

        UserListItem row = dto.getUsers().get(0);
        // None of the exposed string-valued fields carries the stubbed BCrypt hash.
        assertThat(row.getSelectionFlag()).isNotEqualTo(BCRYPT_HASH_FIXTURE);
        assertThat(row.getUserId()).isNotEqualTo(BCRYPT_HASH_FIXTURE);
        assertThat(row.getFirstName()).isNotEqualTo(BCRYPT_HASH_FIXTURE);
        assertThat(row.getLastName()).isNotEqualTo(BCRYPT_HASH_FIXTURE);

        // Structural guarantee: the browse-row type exposes no password-bearing accessor at all.
        // A plain loop over public methods avoids any deep/setAccessible reflection (warning-free).
        boolean exposesPassword = false;
        for (Method method : UserListItem.class.getMethods()) {
            if (isPasswordAccessor(method.getName())) {
                exposesPassword = true;
                break;
            }
        }
        assertThat(exposesPassword)
                .as("UserListItem must expose no password-bearing accessor")
                .isFalse();
    }

    // ------------------------------------------------------------------------------------------------
    // 9. The selection flag is left blank on display (row selection routing is a controller concern)
    //    [COUSR00C INITIALIZE-USER-DATA].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Selection flag is left blank on display — SEL000n is a blank input field [COUSR00C]")
    void shouldLeaveSelectionFlagBlankOnDisplay() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf(
                        user("USER0001", "Alice", "Anderson", UserType.ADMIN),
                        user("USER0002", "Bob", "Brown", UserType.USER)));

        UserSecurityDto dto = userListService.listUsers("", 1);

        assertThat(dto.getUsers()).hasSize(2);
        assertThat(dto.getUsers())
                .allSatisfy(row -> assertThat(row.getSelectionFlag()).isNullOrEmpty());
    }

    // ------------------------------------------------------------------------------------------------
    // 10. The read-only browse returns an empty list (never an exception) past end-of-data
    //     [COUSR00C STARTBR/READNEXT end-of-file is a navigation message, not a service error].
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Read-only browse past end-of-data returns an empty list and throws nothing [COUSR00C EOF]")
    void shouldReturnEmptyListWithoutThrowingAtEndOfData() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        AtomicReference<UserSecurityDto> result = new AtomicReference<>();
        assertThatCode(() -> result.set(userListService.listUsers("ZZZZZZZZ", 99)))
                .doesNotThrowAnyException();

        assertThat(result.get()).isNotNull();
        assertThat(result.get().getUsers()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------
    // 11. The repository browse is invoked exactly once per call — no extra reads.
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The repository browse is invoked exactly once per call — no extra reads")
    void shouldInvokeRepositoryExactlyOncePerCall() {
        when(userSecurityRepository.findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        userListService.listUsers("", 1);

        verify(userSecurityRepository, times(1))
                .findBySecUsrIdGreaterThanEqual(anyString(), any(Pageable.class));
        verifyNoMoreInteractions(userSecurityRepository);
    }

    // ------------------------------------------------------------------------------------------------
    // Shared helper.
    // ------------------------------------------------------------------------------------------------

    /**
     * Reports whether a method name looks like a password-bearing accessor. Case-insensitive match on
     * {@code "pwd"} or {@code "password"} &mdash; used by the no-leakage structural check.
     *
     * @param methodName the candidate accessor name
     * @return {@code true} if the name references a password/pwd, {@code false} otherwise
     */
    private static boolean isPasswordAccessor(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        return lower.contains("pwd") || lower.contains("password");
    }
}
