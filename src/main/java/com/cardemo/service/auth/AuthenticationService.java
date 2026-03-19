/*
 * AuthenticationService.java — Spring @Service for Sign-On/Authentication
 *
 * Migrated from COBOL source artifact:
 *   - app/cbl/COSGN00C.cbl (261 lines, CICS transaction CC00, commit 27d6c6f)
 *
 * This service implements the complete user authentication flow from the COBOL
 * sign-on controller COSGN00C.cbl, translating the CICS pseudo-conversational
 * 3270 terminal authentication model into a stateless Spring Security-integrated
 * service that returns a token-based authentication response.
 *
 * COBOL Paragraph-to-Java Method Traceability (100% coverage):
 *   MAIN-PARA             → Controller routing (AuthController handles HTTP dispatch)
 *   PROCESS-ENTER-KEY     → authenticate() — input validation + uppercase conversion
 *   READ-USER-SEC-FILE    → readUserSecurityFile() — USRSEC VSAM keyed READ
 *   Password comparison   → verifyPassword() — BCrypt replaces plaintext (C-003 upgrade)
 *   User type routing     → buildSignOnResponse() — ADMIN→COADM01C, USER→COMEN01C
 *   SEND-SIGNON-SCREEN    → Controller response (AuthController returns HTTP response)
 *   SEND-PLAIN-TEXT       → Controller response (handled by controller layer)
 *   POPULATE-HEADER-INFO  → Not applicable (3270 terminal header, no REST equivalent)
 *
 * COBOL Working Storage Constants Preserved:
 *   WS-PGMNAME  PIC X(08) VALUE 'COSGN00C' → PROGRAM_NAME
 *   WS-TRANID   PIC X(04) VALUE 'CC00'     → TRANSACTION_ID
 *   WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC' → repository access pattern
 *
 * Security Upgrade (Constraint C-003):
 *   COBOL stores and compares passwords in plaintext (SEC-USR-PWD = WS-USER-PWD).
 *   Java upgrades to BCrypt via Spring Security PasswordEncoder.matches().
 *   COBOL uppercased passwords before storage and comparison; this service
 *   preserves that behavior by uppercasing the input password before BCrypt
 *   verification to maintain behavioral parity with the COBOL comparison semantics.
 *
 * @see com.cardemo.controller.AuthController
 * @see com.cardemo.model.entity.UserSecurity
 * @see com.cardemo.repository.UserSecurityRepository
 */
package com.cardemo.service.auth;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Spring service implementing user authentication for the CardDemo application.
 *
 * <p>Migrated from COBOL program {@code COSGN00C.cbl} (CICS transaction CC00).
 * This service replaces the CICS pseudo-conversational sign-on flow with a
 * stateless REST-compatible authentication mechanism using BCrypt password
 * hashing and UUID-based token generation.</p>
 *
 * <h3>Authentication Flow (preserving COBOL behavioral parity)</h3>
 * <ol>
 *   <li>Validate that userId and password are non-blank
 *       (maps COBOL {@code EVALUATE TRUE} in PROCESS-ENTER-KEY)</li>
 *   <li>Uppercase userId and password
 *       (maps COBOL {@code FUNCTION UPPER-CASE} on both fields)</li>
 *   <li>Read user record from USRSEC dataset
 *       (maps COBOL {@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)})</li>
 *   <li>Verify password using BCrypt
 *       (upgrades COBOL plaintext comparison {@code SEC-USR-PWD = WS-USER-PWD})</li>
 *   <li>Build response with routing information based on user type
 *       (maps COBOL {@code EXEC CICS XCTL PROGRAM('COADM01C'/'COMEN01C')})</li>
 * </ol>
 *
 * <h3>Error Handling (preserving COBOL error messages)</h3>
 * <table>
 *   <caption>COBOL Error Message to Java Exception Mapping</caption>
 *   <tr><th>COBOL Condition</th><th>COBOL Message</th><th>Java Exception</th></tr>
 *   <tr><td>USRIDINI = SPACES/LOW-VALUES</td>
 *       <td>"Please enter User ID ..."</td>
 *       <td>{@code IllegalArgumentException}</td></tr>
 *   <tr><td>PASSWDI = SPACES/LOW-VALUES</td>
 *       <td>"Please enter Password ..."</td>
 *       <td>{@code IllegalArgumentException}</td></tr>
 *   <tr><td>RESP(13) DFHRESP(NOTFND)</td>
 *       <td>"User not found. Try again ..."</td>
 *       <td>{@link RecordNotFoundException}</td></tr>
 *   <tr><td>SEC-USR-PWD != WS-USER-PWD</td>
 *       <td>"Wrong Password. Try again ..."</td>
 *       <td>{@code IllegalArgumentException}</td></tr>
 *   <tr><td>RESP(OTHER)</td>
 *       <td>"Unable to verify the User ..."</td>
 *       <td>Propagated {@code RuntimeException}</td></tr>
 * </table>
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    // -----------------------------------------------------------------------
    // COBOL Constants — Preserved from COSGN00C.cbl Working Storage
    // -----------------------------------------------------------------------

    /**
     * COBOL program name constant.
     * Maps: {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'} (COSGN00C.cbl line 36).
     * Used in structured logging for traceability to the original COBOL program.
     */
    private static final String PROGRAM_NAME = "COSGN00C";

    /**
     * CICS transaction identifier constant.
     * Maps: {@code WS-TRANID PIC X(04) VALUE 'CC00'} (COSGN00C.cbl line 37).
     * Preserved for audit logging and COMMAREA compatibility in the response.
     */
    private static final String TRANSACTION_ID = "CC00";

    /**
     * Admin menu program name — CICS XCTL target for admin users.
     * Maps: {@code PROGRAM('COADM01C')} (COSGN00C.cbl line 232).
     * When SEC-USR-TYPE = 'A', COBOL transfers control to this program.
     */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /**
     * Admin menu transaction ID.
     * The CICS transaction associated with the admin menu program COADM01C.
     * Used in the SignOnResponse to provide routing metadata to the client.
     */
    private static final String ADMIN_TRAN_ID = "CA00";

    /**
     * Regular user main menu program name — CICS XCTL target for regular users.
     * Maps: {@code PROGRAM('COMEN01C')} (COSGN00C.cbl line 237).
     * When SEC-USR-TYPE != 'A', COBOL transfers control to this program.
     */
    private static final String USER_PROGRAM = "COMEN01C";

    /**
     * Regular user main menu transaction ID.
     * The CICS transaction associated with the main menu program COMEN01C.
     * Used in the SignOnResponse to provide routing metadata to the client.
     */
    private static final String USER_TRAN_ID = "CM01";

    // -----------------------------------------------------------------------
    // Injected Dependencies
    // -----------------------------------------------------------------------

    /**
     * Repository for accessing user security records in the USRSEC dataset.
     * Maps: COBOL {@code EXEC CICS READ DATASET(WS-USRSEC-FILE)}
     * from READ-USER-SEC-FILE paragraph (COSGN00C.cbl lines 211-219).
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Spring Security password encoder for BCrypt verification.
     * Replaces: COBOL plaintext comparison {@code IF SEC-USR-PWD = WS-USER-PWD}
     * (COSGN00C.cbl line 223). Security upgrade from constraint C-003.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Custom business metrics recorder for authentication attempt counters.
     * Records success/failure outcomes to the {@code carddemo.auth.attempts}
     * Micrometer counter, which is scraped by Prometheus and displayed in the
     * Grafana "Authentication Attempts" dashboard panel.
     * <p>Per AAP §0.7.1: Observability ships with initial implementation.</p>
     */
    private final MetricsConfig metricsConfig;

    // -----------------------------------------------------------------------
    // Constructor — Dependency Injection
    // -----------------------------------------------------------------------

    /**
     * Constructs the authentication service with required dependencies.
     *
     * <p>Uses constructor injection (not field injection) per Spring best practices.
     * All dependencies are required for the authentication flow.</p>
     *
     * @param userSecurityRepository repository for USRSEC VSAM dataset access;
     *                                must not be {@code null}
     * @param passwordEncoder        BCrypt password encoder configured in
     *                                SecurityConfig; must not be {@code null}
     * @param metricsConfig          custom business metrics recorder for
     *                                authentication attempt counters; must not
     *                                be {@code null}
     */
    public AuthenticationService(UserSecurityRepository userSecurityRepository,
                                 PasswordEncoder passwordEncoder,
                                 MetricsConfig metricsConfig) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.metricsConfig = metricsConfig;
    }

    // -----------------------------------------------------------------------
    // Public API — authenticate()
    // -----------------------------------------------------------------------

    /**
     * Authenticates a user against the USRSEC security dataset.
     *
     * <p>This method combines the logic from COBOL paragraphs
     * {@code PROCESS-ENTER-KEY} (input validation and normalization) and
     * {@code READ-USER-SEC-FILE} (VSAM lookup, password verification, and
     * user-type-based routing).</p>
     *
     * <h4>COBOL Behavioral Parity</h4>
     * <ul>
     *   <li>User ID is uppercased before lookup — preserves
     *       {@code MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID}
     *       (COSGN00C.cbl line 132)</li>
     *   <li>Password is uppercased before BCrypt comparison — preserves
     *       {@code MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD}
     *       (COSGN00C.cbl line 135). COBOL uppercased passwords before both
     *       storage and comparison, so BCrypt hashes were created from
     *       uppercased passwords.</li>
     *   <li>User-not-found and wrong-password produce distinct errors —
     *       preserves COBOL's separate error messages for each condition</li>
     *   <li>Admin users route to COADM01C/CA00, regular users to
     *       COMEN01C/CM01 — preserves COBOL XCTL routing logic</li>
     * </ul>
     *
     * @param request the sign-on request containing userId and password;
     *                must not be {@code null}
     * @return a {@link SignOnResponse} containing the authentication token,
     *         user type, user ID, and routing information
     * @throws IllegalArgumentException  if userId or password is blank/null
     *                                    (maps COBOL "Please enter User ID/Password")
     * @throws BadCredentialsException   if the password is incorrect
     *                                    (maps COBOL "Wrong Password. Try again ...")
     * @throws RecordNotFoundException   if the user ID does not exist in the
     *                                    USRSEC dataset (maps COBOL RESP(13)
     *                                    DFHRESP(NOTFND): "User not found. Try again ...")
     */
    public SignOnResponse authenticate(SignOnRequest request) {
        log.debug("Entering authenticate — source COBOL paragraph: PROCESS-ENTER-KEY "
                + "(program: {}, transaction: {})", PROGRAM_NAME, TRANSACTION_ID);

        // Step 1 — Input validation
        // Maps: COSGN00C.cbl PROCESS-ENTER-KEY lines 117-130
        //   EVALUATE TRUE
        //     WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES → "Please enter User ID ..."
        //     WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES → "Please enter Password ..."
        validateInput(request);

        // Step 2 — Uppercase conversion (COBOL FUNCTION UPPER-CASE)
        // Maps: COSGN00C.cbl lines 132-136
        //   MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID, CDEMO-USER-ID
        //   MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD
        String normalizedUserId = request.getUserId().trim().toUpperCase();
        String normalizedPassword = request.getPassword().trim().toUpperCase();

        log.info("Authentication attempt for user: {}", normalizedUserId);

        // Steps 3-5 wrapped in try-catch for metrics recording.
        // Success → recordAuthAttempt(true), Failure → recordAuthAttempt(false).
        // Per AAP §0.7.1: carddemo.auth.attempts counter with success/failure tag.
        try {
            // Step 3 — User lookup (CICS READ DATASET('USRSEC'))
            // Maps: COSGN00C.cbl READ-USER-SEC-FILE lines 211-219
            //   EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
            //     RIDFLD(WS-USER-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
            UserSecurity user = readUserSecurityFile(normalizedUserId);

            // Step 4 — Password verification (BCrypt replaces plaintext comparison)
            // Maps: COSGN00C.cbl line 223
            //   IF SEC-USR-PWD = WS-USER-PWD (plaintext → BCrypt upgrade per C-003)
            verifyPassword(normalizedPassword, user.getSecUsrPwd(), normalizedUserId);

            // Step 5 — Build successful response with routing information
            // Maps: COSGN00C.cbl lines 224-240
            //   MOVE WS-TRANID TO CDEMO-FROM-TRANID ... EXEC CICS XCTL PROGRAM(...)
            SignOnResponse response = buildSignOnResponse(user, normalizedUserId);

            // Record successful authentication metric
            metricsConfig.recordAuthAttempt(true);

            log.info("User {} authenticated successfully as {}", normalizedUserId, user.getSecUsrType());

            return response;
        } catch (RecordNotFoundException | BadCredentialsException e) {
            // Record failed authentication metric — user not found (RESP(13))
            // or wrong password (SEC-USR-PWD != WS-USER-PWD)
            metricsConfig.recordAuthAttempt(false);
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // Private Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Validates that the sign-on request contains non-blank credentials.
     *
     * <p>Maps COBOL paragraph {@code PROCESS-ENTER-KEY} lines 117-130,
     * specifically the {@code EVALUATE TRUE} block that checks for
     * SPACES or LOW-VALUES in the user ID and password input fields.</p>
     *
     * <p>COBOL checks {@code USRIDINI = SPACES OR LOW-VALUES} and
     * {@code PASSWDI = SPACES OR LOW-VALUES}. The Java equivalent checks
     * for {@code null}, empty string, or all-whitespace.</p>
     *
     * @param request the sign-on request to validate
     * @throws IllegalArgumentException if userId or password is null, empty,
     *                                   or contains only whitespace
     */
    private void validateInput(SignOnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Sign-on request must not be null");
        }

        // Maps: COSGN00C.cbl line 118
        //   WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
        //     MOVE 'Please enter User ID ...' TO WS-MESSAGE
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            log.warn("Authentication failed: blank user ID provided");
            throw new IllegalArgumentException("User ID is required");
        }

        // Maps: COSGN00C.cbl line 123
        //   WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
        //     MOVE 'Please enter Password ...' TO WS-MESSAGE
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            log.warn("Authentication failed: blank password provided for user input");
            throw new IllegalArgumentException("Password is required");
        }
    }

    /**
     * Reads the user security record from the USRSEC dataset by user ID.
     *
     * <p>Maps COBOL paragraph {@code READ-USER-SEC-FILE} (COSGN00C.cbl
     * lines 209-219):</p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-USRSEC-FILE)
     *      INTO      (SEC-USER-DATA)
     *      RIDFLD    (WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>COBOL RESP code handling:</p>
     * <ul>
     *   <li>RESP(0) — Record found, proceed to password verification</li>
     *   <li>RESP(13) DFHRESP(NOTFND) — Record not found, maps to
     *       {@link RecordNotFoundException} with message
     *       "User not found. Try again ..." (COSGN00C.cbl line 249)</li>
     *   <li>RESP(OTHER) — Unexpected error, maps to propagated
     *       {@code RuntimeException} with message
     *       "Unable to verify the User ..." (COSGN00C.cbl line 254)</li>
     * </ul>
     *
     * @param userId the uppercased user ID for the keyed lookup
     * @return the {@link UserSecurity} record for the authenticated user
     * @throws RecordNotFoundException if the user ID does not exist
     *                                  (COBOL RESP(13) / FILE STATUS 23)
     */
    private UserSecurity readUserSecurityFile(String userId) {
        log.debug("Reading USRSEC record for user: {}", userId);

        // Maps: EXEC CICS READ DATASET(WS-USRSEC-FILE) RIDFLD(WS-USER-ID)
        // RESP(0) → return record; RESP(13) → RecordNotFoundException
        // Any other exception propagates naturally (maps RESP(OTHER))
        return userSecurityRepository.findBySecUsrId(userId)
                .orElseThrow(() -> {
                    // Maps: COSGN00C.cbl lines 247-251
                    //   WHEN 13
                    //     MOVE 'User not found. Try again ...' TO WS-MESSAGE
                    log.warn("User not found: {}", userId);
                    return new RecordNotFoundException("UserSecurity", userId);
                });
    }

    /**
     * Verifies the provided password against the stored BCrypt hash.
     *
     * <p>Maps COBOL password comparison logic from {@code READ-USER-SEC-FILE}
     * (COSGN00C.cbl line 223):</p>
     * <pre>
     * IF SEC-USR-PWD = WS-USER-PWD
     * </pre>
     *
     * <p><strong>Security Upgrade (C-003):</strong> The original COBOL
     * application performs a plaintext password comparison. The Java
     * migration upgrades to BCrypt via {@link PasswordEncoder#matches(CharSequence, String)}.
     * This is an approved security improvement per AAP §0.8.1.</p>
     *
     * <p>The input password has already been uppercased by the caller to
     * preserve COBOL behavioral parity — COBOL uppercased both the stored
     * password and the input password before comparison.</p>
     *
     * @param rawPassword     the uppercased plaintext password from the request
     * @param encodedPassword the BCrypt hash stored in the user security record
     * @param userId          the user ID for logging (never log the password)
     * @throws BadCredentialsException if the password does not match
     *                                  (maps COBOL "Wrong Password. Try again ...")
     */
    private void verifyPassword(String rawPassword, String encodedPassword, String userId) {
        // Maps: COSGN00C.cbl line 223
        //   IF SEC-USR-PWD = WS-USER-PWD → success path
        //   ELSE → "Wrong Password. Try again ..." (line 242)
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            // Maps: COSGN00C.cbl lines 241-245
            //   MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
            //   MOVE -1 TO PASSWDL OF COSGN0AI
            //   PERFORM SEND-SIGNON-SCREEN
            log.warn("Failed password attempt for user: {}", userId);
            throw new BadCredentialsException("Invalid password");
        }
        log.debug("Password verified successfully for user: {}", userId);
    }

    /**
     * Builds the authentication response with routing information.
     *
     * <p>Maps COBOL COMMAREA population and XCTL routing from
     * {@code READ-USER-SEC-FILE} (COSGN00C.cbl lines 224-240):</p>
     * <pre>
     * MOVE WS-TRANID    TO CDEMO-FROM-TRANID    (→ preserved as metadata)
     * MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM   (→ preserved as metadata)
     * MOVE WS-USER-ID   TO CDEMO-USER-ID        (→ userId)
     * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE       (→ userType)
     * MOVE ZEROS        TO CDEMO-PGM-CONTEXT     (→ initial context)
     *
     * IF CDEMO-USRTYP-ADMIN
     *     EXEC CICS XCTL PROGRAM ('COADM01C') ...
     * ELSE
     *     EXEC CICS XCTL PROGRAM ('COMEN01C') ...
     * END-IF
     * </pre>
     *
     * <p>The CICS XCTL (transfer control) is replaced by routing metadata
     * in the response DTO, allowing the client to navigate to the
     * appropriate menu screen.</p>
     *
     * @param user             the authenticated user security record
     * @param normalizedUserId the uppercased user ID
     * @return a fully populated {@link SignOnResponse}
     */
    private SignOnResponse buildSignOnResponse(UserSecurity user, String normalizedUserId) {
        // Generate informational session identifier (NOT used for request authentication).
        // SecurityConfig enforces HTTP Basic auth via DaoAuthenticationProvider — each
        // REST request is independently authenticated, mirroring COBOL's stateless CICS
        // pseudo-conversational model (RETURN TRANSID COMMAREA). This UUID provides a
        // unique session correlation token for client-side routing and logging, replacing
        // the COMMAREA-based state passing without serving as a security credential.
        String token = UUID.randomUUID().toString();

        // Determine routing based on user type
        // Maps: COSGN00C.cbl lines 230-240
        //   IF CDEMO-USRTYP-ADMIN → XCTL PROGRAM('COADM01C')
        //   ELSE                  → XCTL PROGRAM('COMEN01C')
        UserType userType = user.getSecUsrType();
        String toProgram = determineRoutingProgram(userType);
        String toTranId = determineRoutingTransId(userType);

        return new SignOnResponse(token, userType, normalizedUserId, toTranId, toProgram);
    }

    /**
     * Determines the target program name based on user type.
     *
     * <p>Maps the COBOL {@code IF CDEMO-USRTYP-ADMIN} conditional
     * in READ-USER-SEC-FILE (COSGN00C.cbl lines 230-240):</p>
     * <ul>
     *   <li>{@link UserType#ADMIN} → {@code "COADM01C"} (admin menu program)</li>
     *   <li>{@link UserType#USER} → {@code "COMEN01C"} (main menu program)</li>
     * </ul>
     *
     * @param userType the authenticated user's type
     * @return the target CICS program name for post-login routing
     */
    private String determineRoutingProgram(UserType userType) {
        if (UserType.ADMIN == userType) {
            return ADMIN_PROGRAM;
        }
        return USER_PROGRAM;
    }

    /**
     * Determines the target transaction ID based on user type.
     *
     * <p>Maps the COBOL XCTL target transaction IDs:</p>
     * <ul>
     *   <li>{@link UserType#ADMIN} → {@code "CA00"} (admin menu transaction)</li>
     *   <li>{@link UserType#USER} → {@code "CM01"} (main menu transaction)</li>
     * </ul>
     *
     * @param userType the authenticated user's type
     * @return the target CICS transaction ID for post-login routing
     */
    private String determineRoutingTransId(UserType userType) {
        if (UserType.ADMIN == userType) {
            return ADMIN_TRAN_ID;
        }
        return USER_TRAN_ID;
    }
}
