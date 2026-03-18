/*
 * AuthenticationServiceTest.java — Unit Tests for Sign-On Logic (COSGN00C.cbl)
 *
 * JUnit 5 + Mockito unit tests for AuthenticationService — tests the COSGN00C.cbl
 * sign-on logic migration: USRSEC read + BCrypt verify + UUID token generation +
 * routing by user type (ADMIN→COADM01C/CA00, USER→COMEN01C/CM01).
 *
 * COBOL Paragraph Coverage (tested through AuthenticationService.authenticate):
 *   PROCESS-ENTER-KEY    → tests 1-4 (input validation: null/blank userId/password)
 *   FUNCTION UPPER-CASE  → tests 6, 8 (uppercase conversion for userId and password)
 *   READ-USER-SEC-FILE   → test 5 (user-not-found → RecordNotFoundException)
 *   Password comparison   → tests 7-8 (wrong password, uppercase-before-BCrypt)
 *   User type routing     → tests 9-12 (ADMIN→COADM01C/CA00, USER→COMEN01C/CM01)
 *   Response population   → tests 13-14 (userType, all fields)
 *   Interaction verify    → tests 15-16 (repository and passwordEncoder called once)
 *
 * Testing Framework:
 *   - JUnit 5 with @ExtendWith(MockitoExtension.class) — NO Spring context loading
 *   - Mockito @Mock for UserSecurityRepository and PasswordEncoder
 *   - Mockito @InjectMocks for AuthenticationService (constructor injection)
 *   - AssertJ assertThat/assertThatThrownBy for fluent assertions
 *
 * COBOL Source References (original repository commit SHA 27d6c6f):
 *   - app/cbl/COSGN00C.cbl — Sign-on program (261 lines, CICS transaction CC00)
 *   - app/cpy/CSUSR01Y.cpy — SEC-USER-DATA record layout (80 bytes)
 *   - app/cpy/COCOM01Y.cpy — CARDDEMO-COMMAREA central session state
 *   - app/cpy-bms/COSGN00.CPY — BMS symbolic map for sign-on screen
 */
package com.cardemo.unit.service;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.auth.AuthenticationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthenticationService} — pure Mockito-based tests with
 * no Spring context loading.
 *
 * <p>Validates all COSGN00C.cbl sign-on logic paths migrated into
 * {@code AuthenticationService.authenticate(SignOnRequest)}, including:</p>
 * <ul>
 *   <li>Input guard checks (null/blank userId and password)</li>
 *   <li>COBOL FUNCTION UPPER-CASE conversion for userId and password</li>
 *   <li>USRSEC dataset keyed READ via UserSecurityRepository</li>
 *   <li>BCrypt password verification (security upgrade from plaintext C-003)</li>
 *   <li>User type routing: ADMIN→COADM01C/CA00, USER→COMEN01C/CM01</li>
 *   <li>Token generation and response population</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    // -----------------------------------------------------------------------
    // Mocks — replaces VSAM I/O and BCrypt infrastructure
    // -----------------------------------------------------------------------

    /**
     * Mocked UserSecurityRepository — provides findBySecUsrId(String) stubs
     * for user lookup tests. Maps COBOL READ USRSEC by SEC-USR-ID from
     * COSGN00C.cbl READ-USER-SEC-FILE paragraph.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mocked PasswordEncoder — provides matches(CharSequence, String) stubs
     * for BCrypt verification. Replaces COBOL plaintext comparison
     * SEC-USR-PWD = WS-USER-PWD (security upgrade per AAP §0.8.1).
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * Service under test — AuthenticationService with injected mocks.
     * Constructor injection of userSecurityRepository and passwordEncoder.
     */
    @InjectMocks
    private AuthenticationService authenticationService;

    // -----------------------------------------------------------------------
    // Test Fixtures — SEC-USER-DATA records from CSUSR01Y.cpy
    // -----------------------------------------------------------------------

    /** Admin user fixture: SEC-USR-TYPE = 'A' → routes to COADM01C/CA00. */
    private UserSecurity adminUser;

    /** Regular user fixture: SEC-USR-TYPE = 'U' → routes to COMEN01C/CM01. */
    private UserSecurity regularUser;

    /**
     * Initializes test fixtures before each test method.
     *
     * <p>Creates two UserSecurity entities representing the two user types
     * in the CardDemo application. The BCrypt hash strings are placeholder
     * values — the actual BCrypt verification is mocked via PasswordEncoder.</p>
     *
     * <p>Maps COBOL SEC-USER-DATA 80-byte record layout from CSUSR01Y.cpy:
     * <pre>
     * 01 SEC-USER-DATA.
     *    05 SEC-USR-ID     PIC X(08).  → secUsrId
     *    05 SEC-USR-FNAME  PIC X(20).  → secUsrFname
     *    05 SEC-USR-LNAME  PIC X(20).  → secUsrLname
     *    05 SEC-USR-PWD    PIC X(08).  → secUsrPwd (BCrypt hash in Java)
     *    05 SEC-USR-TYPE   PIC X(01).  → secUsrType (UserType enum)
     * </pre></p>
     */
    @BeforeEach
    void setUp() {
        // Admin user fixture — 88 CDEMO-USRTYP-ADMIN VALUE 'A'
        adminUser = new UserSecurity();
        adminUser.setSecUsrId("ADMIN001");
        adminUser.setSecUsrFname("Admin");
        adminUser.setSecUsrLname("User");
        adminUser.setSecUsrPwd("$2a$10$hashedAdminPasswordXXXXXXXXXXXXXXXXXXXXXXX");
        adminUser.setSecUsrType(UserType.ADMIN);

        // Regular user fixture — 88 CDEMO-USRTYP-USER VALUE 'U'
        regularUser = new UserSecurity();
        regularUser.setSecUsrId("USER0001");
        regularUser.setSecUsrFname("Regular");
        regularUser.setSecUsrLname("User");
        regularUser.setSecUsrPwd("$2a$10$hashedRegularPasswordXXXXXXXXXXXXXXXXXXXXX");
        regularUser.setSecUsrType(UserType.USER);
    }

    // =======================================================================
    // Input Validation Tests (COBOL PROCESS-ENTER-KEY guard checks)
    // Maps: COSGN00C.cbl lines 117-130 EVALUATE TRUE block
    // =======================================================================

    /**
     * Test 1: Null userId triggers IllegalArgumentException.
     *
     * <p>Maps COBOL: {@code WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES}
     * → "Please enter User ID ..." (COSGN00C.cbl line 118).</p>
     *
     * <p>Null in Java is the equivalent of LOW-VALUES in COBOL — an unset
     * field that fails the presence check.</p>
     */
    @Test
    void testAuthenticate_nullUserId_throwsIllegalArgument() {
        // Given — request with null userId (COBOL LOW-VALUES equivalent)
        SignOnRequest request = new SignOnRequest();
        request.setUserId(null);
        request.setPassword("TESTPWD1");

        // When/Then — authenticate throws IllegalArgumentException for missing userId
        assertThatThrownBy(() -> authenticationService.authenticate(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test 2: Blank userId triggers IllegalArgumentException.
     *
     * <p>Maps COBOL: {@code WHEN USERIDI OF COSGN0AI = SPACES}
     * → "Please enter User ID ..." (COSGN00C.cbl line 118).</p>
     *
     * <p>All-whitespace in Java is the equivalent of SPACES in COBOL —
     * an all-space field that fails the presence check.</p>
     */
    @Test
    void testAuthenticate_blankUserId_throwsIllegalArgument() {
        // Given — request with blank userId (COBOL SPACES equivalent)
        SignOnRequest request = new SignOnRequest();
        request.setUserId("   ");
        request.setPassword("TESTPWD1");

        // When/Then — authenticate throws IllegalArgumentException for blank userId
        assertThatThrownBy(() -> authenticationService.authenticate(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test 3: Null password triggers IllegalArgumentException.
     *
     * <p>Maps COBOL: {@code WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES}
     * → "Please enter Password ..." (COSGN00C.cbl line 123).</p>
     */
    @Test
    void testAuthenticate_nullPassword_throwsIllegalArgument() {
        // Given — valid userId but null password (COBOL LOW-VALUES equivalent)
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword(null);

        // When/Then — authenticate throws IllegalArgumentException for missing password
        assertThatThrownBy(() -> authenticationService.authenticate(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test 4: Blank password triggers IllegalArgumentException.
     *
     * <p>Maps COBOL: {@code WHEN PASSWDI OF COSGN0AI = SPACES}
     * → "Please enter Password ..." (COSGN00C.cbl line 123).</p>
     */
    @Test
    void testAuthenticate_blankPassword_throwsIllegalArgument() {
        // Given — valid userId but blank password (COBOL SPACES equivalent)
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("   ");

        // When/Then — authenticate throws IllegalArgumentException for blank password
        assertThatThrownBy(() -> authenticationService.authenticate(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =======================================================================
    // User Lookup Tests (COBOL READ-USER-SEC-FILE paragraph)
    // Maps: COSGN00C.cbl lines 211-219 EXEC CICS READ DATASET('USRSEC')
    // =======================================================================

    /**
     * Test 5: User not found triggers RecordNotFoundException.
     *
     * <p>Maps COBOL: {@code RESP(13) DFHRESP(NOTFND)} → "User not found.
     * Try again ..." (COSGN00C.cbl line 249). FILE STATUS '23' = INVALID KEY,
     * record not found on READ by SEC-USR-ID.</p>
     */
    @Test
    void testAuthenticate_userNotFound_throwsRecordNotFound() {
        // Given — findBySecUsrId returns empty Optional (COBOL DFHRESP(NOTFND))
        SignOnRequest request = new SignOnRequest();
        request.setUserId("UNKNOWN1");
        request.setPassword("TESTPWD1");

        when(userSecurityRepository.findBySecUsrId("UNKNOWN1"))
                .thenReturn(Optional.empty());

        // When/Then — authenticate throws RecordNotFoundException
        assertThatThrownBy(() -> authenticationService.authenticate(request))
                .isInstanceOf(RecordNotFoundException.class);
    }

    /**
     * Test 6: User ID is uppercased before repository lookup.
     *
     * <p>Maps COBOL: {@code MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)
     * TO WS-USER-ID} (COSGN00C.cbl line 132). The COBOL application
     * uppercases the terminal input before performing the VSAM keyed READ.</p>
     *
     * <p>Verifies that lowercase input "user01" results in repository being
     * called with "USER01" — preserving COBOL FUNCTION UPPER-CASE behavior.</p>
     */
    @Test
    void testAuthenticate_uppercasesUserId() {
        // Given — lowercase userId "user01" should be uppercased to "USER01"
        SignOnRequest request = new SignOnRequest();
        request.setUserId("user01");
        request.setPassword("TESTPWD1");

        when(userSecurityRepository.findBySecUsrId("USER01"))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("TESTPWD1", adminUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        authenticationService.authenticate(request);

        // Then — verify repository was called with the UPPERCASED userId
        verify(userSecurityRepository).findBySecUsrId(
                request.getUserId().trim().toUpperCase());
    }

    // =======================================================================
    // Password Verification Tests (BCrypt replaces plaintext comparison)
    // Maps: COSGN00C.cbl line 223 IF SEC-USR-PWD = WS-USER-PWD
    // Security upgrade: plaintext → BCrypt per AAP §0.8.1 / constraint C-003
    // =======================================================================

    /**
     * Test 7: Wrong password triggers IllegalArgumentException with message.
     *
     * <p>Maps COBOL: {@code IF SEC-USR-PWD NOT = WS-USER-PWD}
     * → "Wrong Password. Try again ..." (COSGN00C.cbl line 242).
     * BCrypt.matches() returns false → service throws IllegalArgumentException.</p>
     */
    @Test
    void testAuthenticate_wrongPassword_throwsIllegalArgument() {
        // Given — valid user but wrong password (BCrypt.matches returns false)
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("WRONGPWD");

        when(userSecurityRepository.findBySecUsrId(adminUser.getSecUsrId()))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("WRONGPWD", adminUser.getSecUsrPwd()))
                .thenReturn(false);

        // When/Then — authenticate throws IllegalArgumentException for wrong password
        assertThatThrownBy(() -> authenticationService.authenticate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid password");
    }

    /**
     * Test 8: Password is uppercased before passing to BCrypt.matches().
     *
     * <p>Maps COBOL: {@code MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI)
     * TO WS-USER-PWD} (COSGN00C.cbl line 135). The COBOL application
     * uppercased passwords before both storage and comparison; this behavior
     * is preserved so BCrypt hashes match the COBOL uppercase convention.</p>
     *
     * <p>Verifies that lowercase input "mypass" is uppercased to "MYPASS"
     * before being passed to passwordEncoder.matches().</p>
     */
    @Test
    void testAuthenticate_uppercasesPasswordBeforeBCrypt() {
        // Given — lowercase password "mypass" → uppercased to "MYPASS"
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("mypass");

        when(userSecurityRepository.findBySecUsrId(adminUser.getSecUsrId()))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("MYPASS", adminUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        authenticationService.authenticate(request);

        // Then — verify passwordEncoder received the UPPERCASED password
        verify(passwordEncoder).matches(
                request.getPassword().trim().toUpperCase(),
                adminUser.getSecUsrPwd());
    }

    // =======================================================================
    // Successful Authentication — Admin User Tests
    // Maps: COSGN00C.cbl lines 230-232
    //   IF CDEMO-USRTYP-ADMIN → EXEC CICS XCTL PROGRAM('COADM01C')
    // =======================================================================

    /**
     * Test 9: Admin user receives correct routing to COADM01C / CA00.
     *
     * <p>Maps COBOL: {@code IF CDEMO-USRTYP-ADMIN → EXEC CICS XCTL
     * PROGRAM('COADM01C')} (COSGN00C.cbl line 232). When the authenticated
     * user has SEC-USR-TYPE = 'A', COBOL transfers control to the admin
     * menu program COADM01C with transaction ID CA00.</p>
     */
    @Test
    void testAuthenticate_adminUser_returnsCorrectRouting() {
        // Given — admin user (UserType.ADMIN → SEC-USR-TYPE 'A')
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("ADMINPWD");

        when(userSecurityRepository.findBySecUsrId(adminUser.getSecUsrId()))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("ADMINPWD", adminUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        SignOnResponse response = authenticationService.authenticate(request);

        // Then — ADMIN routing: COADM01C / CA00
        assertThat(response.getToProgram()).isEqualTo("COADM01C");
        assertThat(response.getToTranId()).isEqualTo("CA00");
    }

    /**
     * Test 10: Admin authentication response contains a non-blank token.
     *
     * <p>The token replaces the CICS RETURN TRANSID COMMAREA mechanism.
     * In COBOL, session state was carried in the COMMAREA across
     * pseudo-conversational interactions. In Java, a UUID token provides
     * the equivalent session reference for stateless REST endpoints.</p>
     */
    @Test
    void testAuthenticate_adminUser_responseContainsToken() {
        // Given — admin user with valid credentials
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("ADMINPWD");

        when(userSecurityRepository.findBySecUsrId(adminUser.getSecUsrId()))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("ADMINPWD", adminUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        SignOnResponse response = authenticationService.authenticate(request);

        // Then — token must be present and non-blank
        assertThat(response.getToken()).isNotNull();
        assertThat(response.getToken()).isNotBlank();
    }

    // =======================================================================
    // Successful Authentication — Regular User Tests
    // Maps: COSGN00C.cbl lines 235-238
    //   ELSE → EXEC CICS XCTL PROGRAM('COMEN01C')
    // =======================================================================

    /**
     * Test 11: Regular user receives correct routing to COMEN01C / CM01.
     *
     * <p>Maps COBOL: {@code ELSE → EXEC CICS XCTL PROGRAM('COMEN01C')}
     * (COSGN00C.cbl line 237). When the authenticated user has
     * SEC-USR-TYPE != 'A' (i.e., 'U'), COBOL transfers control to the
     * main menu program COMEN01C with transaction ID CM01.</p>
     */
    @Test
    void testAuthenticate_regularUser_returnsCorrectRouting() {
        // Given — regular user (UserType.USER → SEC-USR-TYPE 'U')
        SignOnRequest request = new SignOnRequest();
        request.setUserId("USER0001");
        request.setPassword("USERPWD1");

        when(userSecurityRepository.findBySecUsrId(regularUser.getSecUsrId()))
                .thenReturn(Optional.of(regularUser));
        when(passwordEncoder.matches("USERPWD1", regularUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        SignOnResponse response = authenticationService.authenticate(request);

        // Then — USER routing: COMEN01C / CM01
        assertThat(response.getToProgram()).isEqualTo("COMEN01C");
        assertThat(response.getToTranId()).isEqualTo("CM01");
    }

    /**
     * Test 12: Response userId matches the uppercased input.
     *
     * <p>Maps COBOL: {@code MOVE WS-USER-ID TO CDEMO-USER-ID}
     * (COSGN00C.cbl line 225). WS-USER-ID is the result of
     * FUNCTION UPPER-CASE(USERIDI), so the COMMAREA always contains
     * the uppercased user ID regardless of terminal input case.</p>
     */
    @Test
    void testAuthenticate_regularUser_responseContainsUserId() {
        // Given — lowercase userId "user0001" → uppercased "USER0001"
        SignOnRequest request = new SignOnRequest();
        request.setUserId("user0001");
        request.setPassword("USERPWD1");

        when(userSecurityRepository.findBySecUsrId("USER0001"))
                .thenReturn(Optional.of(regularUser));
        when(passwordEncoder.matches("USERPWD1", regularUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        SignOnResponse response = authenticationService.authenticate(request);

        // Then — response userId is the uppercased version of the input
        assertThat(response.getUserId()).isEqualTo("USER0001");
    }

    // =======================================================================
    // Response Content Tests
    // Maps: COSGN00C.cbl lines 224-240 COMMAREA population
    // =======================================================================

    /**
     * Test 13: Response userType matches the entity's user type.
     *
     * <p>Maps COBOL: {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}
     * (COSGN00C.cbl line 226). The COMMAREA carries the user type from
     * the USRSEC record for downstream programs to determine access level.</p>
     */
    @Test
    void testAuthenticate_responseContainsUserType() {
        // Given — admin user with known UserType
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("ADMINPWD");

        when(userSecurityRepository.findBySecUsrId(adminUser.getSecUsrId()))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("ADMINPWD", adminUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        SignOnResponse response = authenticationService.authenticate(request);

        // Then — response userType matches entity's type (ADMIN)
        assertThat(response.getUserType()).isEqualTo(adminUser.getSecUsrType());
    }

    /**
     * Test 14: All SignOnResponse fields are populated on successful auth.
     *
     * <p>Maps the complete COMMAREA population from COSGN00C.cbl:
     * <ul>
     *   <li>CDEMO-USER-ID → userId (non-null, non-blank)</li>
     *   <li>CDEMO-USER-TYPE → userType (non-null)</li>
     *   <li>Token → (non-null, non-blank) replaces CICS RETURN TRANSID</li>
     *   <li>CDEMO-TO-PROGRAM → toProgram (non-null, non-blank)</li>
     *   <li>CDEMO-TO-TRANID → toTranId (non-null, non-blank)</li>
     * </ul></p>
     */
    @Test
    void testAuthenticate_responseContainsAllFields() {
        // Given — regular user for comprehensive field check
        SignOnRequest request = new SignOnRequest();
        request.setUserId("USER0001");
        request.setPassword("USERPWD1");

        when(userSecurityRepository.findBySecUsrId(regularUser.getSecUsrId()))
                .thenReturn(Optional.of(regularUser));
        when(passwordEncoder.matches("USERPWD1", regularUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        SignOnResponse response = authenticationService.authenticate(request);

        // Then — all 5 COMMAREA-equivalent fields are populated
        assertThat(response.getUserId()).isNotNull().isNotBlank();
        assertThat(response.getUserType()).isNotNull();
        assertThat(response.getToken()).isNotNull().isNotBlank();
        assertThat(response.getToProgram()).isNotNull().isNotBlank();
        assertThat(response.getToTranId()).isNotNull().isNotBlank();
    }

    // =======================================================================
    // Interaction Verification Tests
    // Verifies correct delegation to mocked dependencies
    // =======================================================================

    /**
     * Test 15: UserSecurityRepository.findBySecUsrId() is called exactly once.
     *
     * <p>Verifies the USRSEC VSAM READ is performed exactly once per
     * authentication attempt. In COBOL, the READ is a single I/O operation
     * within the READ-USER-SEC-FILE paragraph — it must not be duplicated
     * in the Java implementation.</p>
     */
    @Test
    void testAuthenticate_verifyRepositoryCalledOnce() {
        // Given — successful authentication flow
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("ADMINPWD");

        when(userSecurityRepository.findBySecUsrId(adminUser.getSecUsrId()))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("ADMINPWD", adminUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        authenticationService.authenticate(request);

        // Then — repository findBySecUsrId invoked exactly once
        verify(userSecurityRepository, times(1))
                .findBySecUsrId(adminUser.getSecUsrId());
    }

    /**
     * Test 16: PasswordEncoder.matches() is called exactly once.
     *
     * <p>Verifies that BCrypt password verification occurs exactly once
     * per authentication attempt. In COBOL, the password comparison is
     * a single IF statement — it must not be duplicated in the Java
     * implementation.</p>
     */
    @Test
    void testAuthenticate_verifyPasswordEncoderCalledOnce() {
        // Given — successful authentication flow
        SignOnRequest request = new SignOnRequest();
        request.setUserId("ADMIN001");
        request.setPassword("ADMINPWD");

        when(userSecurityRepository.findBySecUsrId(adminUser.getSecUsrId()))
                .thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("ADMINPWD", adminUser.getSecUsrPwd()))
                .thenReturn(true);

        // When
        authenticationService.authenticate(request);

        // Then — passwordEncoder.matches invoked exactly once
        verify(passwordEncoder, times(1))
                .matches("ADMINPWD", adminUser.getSecUsrPwd());
    }
}
