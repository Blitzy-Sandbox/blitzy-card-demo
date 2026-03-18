/*
 * UserListService.java — Spring @Service for Paginated User Browse
 *
 * Migrated from COBOL source artifact:
 *   - app/cbl/COUSR00C.cbl (695 lines, transaction ID CU00, commit 27d6c6f)
 *   - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA, 80-byte record, commit 27d6c6f)
 *
 * This service class replaces the CICS online program COUSR00C, which provides
 * paginated browse functionality for the USRSEC VSAM KSDS dataset. The COBOL
 * program uses STARTBR/READNEXT/READPREV/ENDBR to sequentially read user
 * security records 10 at a time (WS-USER-DATA OCCURS 10 TIMES), tracking
 * pagination state via CDEMO-CU00-USRID-FIRST, CDEMO-CU00-USRID-LAST,
 * CDEMO-CU00-PAGE-NUM, and CDEMO-CU00-NEXT-PAGE-FLG.
 *
 * In the Java migration, Spring Data JPA's Pageable/Page infrastructure
 * replaces all VSAM browse operations. A single JPA paginated query replaces
 * the COBOL STARTBR + READNEXT loop, and the Page metadata replaces the
 * manually tracked pagination state variables.
 *
 * COBOL Paragraph → Java Method Traceability:
 *   MAIN-PARA (line 98)                  → Class-level orchestration
 *   PROCESS-ENTER-KEY (line 149)         → listUsersFromId() + controller routing
 *   PROCESS-PF7-KEY (line 237)           → listUsers(pageNumber - 1)
 *   PROCESS-PF8-KEY (line 260)           → listUsers(pageNumber + 1)
 *   PROCESS-PAGE-FORWARD (line 282)      → listUsers() core pagination
 *   PROCESS-PAGE-BACKWARD (line 336)     → listUsers(pageNumber - 1)
 *   POPULATE-USER-DATA (line 384)        → convertToDto()
 *   INITIALIZE-USER-DATA (line 446)      → N/A (implicit in Page creation)
 *   RETURN-TO-PREV-SCREEN (line 506)     → N/A (controller routing)
 *   SEND-USRLST-SCREEN (line 522)        → N/A (REST response)
 *   RECEIVE-USRLST-SCREEN (line 549)     → N/A (REST request)
 *   POPULATE-HEADER-INFO (line 562)      → N/A (controller/framework)
 *   STARTBR-USER-SEC-FILE (line 586)     → JPA findAll(Pageable)
 *   READNEXT-USER-SEC-FILE (line 619)    → JPA findAll(Pageable)
 *   READPREV-USER-SEC-FILE (line 653)    → JPA findAll(Pageable) with lower page
 *   ENDBR-USER-SEC-FILE (line 687)       → N/A (JPA manages cursor lifecycle)
 *
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.entity.UserSecurity
 * @see com.cardemo.model.dto.UserSecurityDto
 */
package com.cardemo.service.admin;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service providing paginated user list/browse functionality.
 *
 * <p>Migrates COBOL program {@code COUSR00C.cbl} (695 lines, transaction CU00)
 * to a Spring {@code @Service}. The COBOL program uses CICS STARTBR, READNEXT,
 * READPREV, and ENDBR operations on the USRSEC VSAM KSDS file to display
 * user security records in pages of 10.</p>
 *
 * <p>All operations in this service are read-only (no WRITE, REWRITE, or DELETE
 * on the USRSEC file), hence the class-level {@code @Transactional(readOnly = true)}
 * annotation. This enables JPA/Hibernate dirty-checking optimization for browse
 * queries.</p>
 *
 * <p>The COBOL program tracks pagination state via working storage variables
 * ({@code CDEMO-CU00-USRID-FIRST}, {@code CDEMO-CU00-USRID-LAST},
 * {@code CDEMO-CU00-PAGE-NUM}, {@code CDEMO-CU00-NEXT-PAGE-FLG}). In the Java
 * migration, Spring Data's {@link Page} metadata replaces all manual state
 * tracking, providing total elements, total pages, and next/previous page
 * indicators directly.</p>
 *
 * <p>Password fields are NEVER included in any DTO returned by this service.
 * The {@link #convertToDto(UserSecurity)} method explicitly sets the password
 * to {@code null} in the DTO to prevent credential leakage.</p>
 */
@Service
@Transactional(readOnly = true)
public class UserListService {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Replaces COBOL DISPLAY statements for diagnostic output in COUSR00C.cbl.
     */
    private static final Logger logger = LoggerFactory.getLogger(UserListService.class);

    /**
     * Number of user records displayed per page.
     *
     * <p>Matches the COBOL {@code WS-USER-DATA} structure which defines
     * {@code USER-REC OCCURS 10 TIMES} (COUSR00C.cbl line 57). The COBOL
     * program reads exactly 10 records per READNEXT loop iteration before
     * performing one additional READNEXT to check for the existence of a
     * next page.</p>
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Spring Data JPA repository for user security record access.
     *
     * <p>Replaces all CICS file control commands (STARTBR, READNEXT, READPREV,
     * ENDBR) on the USRSEC VSAM file from COUSR00C.cbl. Injected via
     * constructor injection (no {@code @Autowired} needed with single-constructor
     * auto-wiring).</p>
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Constructs a new {@code UserListService} with the required repository dependency.
     *
     * <p>Spring auto-wires via single-constructor injection — no {@code @Autowired}
     * annotation is needed. This replaces the COBOL program's implicit access to the
     * USRSEC file through CICS file control commands defined in the FCT (File
     * Control Table).</p>
     *
     * @param userSecurityRepository the repository for USRSEC data access;
     *                               must not be {@code null}
     */
    public UserListService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Lists user security records with pagination.
     *
     * <p>Maps COBOL paragraph {@code PROCESS-PAGE-FORWARD} (lines 282-331) which
     * performs:</p>
     * <ol>
     *   <li>{@code STARTBR-USER-SEC-FILE} — positions browse at current key</li>
     *   <li>{@code READNEXT-USER-SEC-FILE} loop — reads up to 10 records</li>
     *   <li>One additional READNEXT to set {@code NEXT-PAGE-FLG}</li>
     *   <li>{@code ENDBR-USER-SEC-FILE} — ends the browse session</li>
     * </ol>
     *
     * <p>In the Java migration, a single JPA paginated query replaces the entire
     * STARTBR + READNEXT loop. The ascending sort on {@code secUsrId} preserves
     * the VSAM KSDS key order (SEC-USR-ID is the primary key).</p>
     *
     * <p>Also maps {@code PROCESS-PF7-KEY} (line 237, page backward) when called
     * with {@code pageNumber - 1}, and {@code PROCESS-PF8-KEY} (line 260, page
     * forward) when called with {@code pageNumber + 1}.</p>
     *
     * @param pageNumber the 0-based page number to retrieve; maps to
     *                   {@code CDEMO-CU00-PAGE-NUM} in the COBOL COMMAREA.
     *                   Negative values are clamped to 0.
     * @return a {@link Page} of {@link UserSecurityDto} records for the requested
     *         page, with pagination metadata (total elements, total pages,
     *         hasNext, hasPrevious). Returns an empty page if no users exist
     *         (maps COBOL {@code DFHRESP(NOTFND)} at STARTBR, lines 600-606).
     */
    public Page<UserSecurityDto> listUsers(int pageNumber) {
        // Clamp negative page numbers to 0 — maps COBOL's implicit lower bound
        // of CDEMO-CU00-PAGE-NUM (PIC 9(08), unsigned)
        int safePage = Math.max(pageNumber, 0);

        // Create pageable request with ascending sort on secUsrId — matches
        // VSAM KSDS key order for STARTBR/READNEXT sequential access
        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, Sort.by("secUsrId").ascending());

        logger.info("Listing users: requesting page {}", safePage);

        // Single JPA query replaces COBOL STARTBR + READNEXT loop (lines 282-331)
        Page<UserSecurity> entityPage = userSecurityRepository.findAll(pageable);

        // Convert entity page to DTO page — maps POPULATE-USER-DATA (lines 384-441)
        Page<UserSecurityDto> dtoPage = convertPageToDto(entityPage);

        logger.info("Listing users: page {} of {} (totalElements={}, hasNext={}, hasPrevious={})",
                safePage, entityPage.getTotalPages(), entityPage.getTotalElements(),
                entityPage.hasNext(), entityPage.hasPrevious());

        return dtoPage;
    }

    /**
     * Lists user security records starting from a specific user ID, with pagination.
     *
     * <p>Maps COBOL paragraph {@code PROCESS-ENTER-KEY} (lines 218-232), specifically
     * the filtered browse logic:</p>
     * <pre>
     *   IF USRIDINI OF COUSR0AI = SPACES OR LOW-VALUES
     *       MOVE LOW-VALUES TO SEC-USR-ID
     *   ELSE
     *       MOVE USRIDINI OF COUSR0AI TO SEC-USR-ID
     *   END-IF
     *   PERFORM PROCESS-PAGE-FORWARD
     * </pre>
     *
     * <p>When the user enters a starting user ID in the USRIDINI field on the BMS
     * screen, the COBOL STARTBR positions the browse cursor at the first record
     * with a key greater than or equal to the entered value (GTEQ default). This
     * method replicates that behavior using the repository's
     * {@code findBySecUsrIdGreaterThanEqual()} query.</p>
     *
     * <p>If {@code startUserId} is null or blank, delegates to
     * {@link #listUsers(int)} — equivalent to COBOL's handling when USRIDINI
     * contains SPACES or LOW-VALUES.</p>
     *
     * @param startUserId the starting user ID for the filtered browse; records
     *                    with this ID or lexicographically greater IDs are returned.
     *                    If {@code null} or blank, returns all users from the beginning.
     * @param pageNumber  the 0-based page number to retrieve. Negative values
     *                    are clamped to 0.
     * @return a {@link Page} of {@link UserSecurityDto} records matching the filter,
     *         with pagination metadata. Returns an empty page if no users match
     *         (maps COBOL {@code DFHRESP(NOTFND)} at STARTBR, lines 600-606).
     */
    public Page<UserSecurityDto> listUsersFromId(String startUserId, int pageNumber) {
        // COBOL: IF USRIDINI = SPACES OR LOW-VALUES → browse from beginning
        if (startUserId == null || startUserId.isBlank()) {
            return listUsers(pageNumber);
        }

        // Clamp negative page numbers to 0
        int safePage = Math.max(pageNumber, 0);

        // Create pageable with ascending sort matching VSAM KSDS key order
        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, Sort.by("secUsrId").ascending());

        logger.info("Listing users from ID '{}': requesting page {}", startUserId, safePage);

        // Repository query maps COBOL STARTBR with GTEQ positioning on SEC-USR-ID
        // (PROCESS-ENTER-KEY lines 218-222)
        Page<UserSecurity> entityPage = userSecurityRepository.findBySecUsrIdGreaterThanEqual(
                startUserId, pageable);

        // Convert entity page to DTO page — maps POPULATE-USER-DATA (lines 384-441)
        Page<UserSecurityDto> dtoPage = convertPageToDto(entityPage);

        logger.info("Listing users from ID '{}': page {} of {} (totalElements={}, hasNext={}, hasPrevious={})",
                startUserId, safePage, entityPage.getTotalPages(), entityPage.getTotalElements(),
                entityPage.hasNext(), entityPage.hasPrevious());

        return dtoPage;
    }

    /**
     * Converts a {@link UserSecurity} entity to a {@link UserSecurityDto}.
     *
     * <p>Maps COBOL paragraph {@code POPULATE-USER-DATA} (lines 384-441) which
     * copies fields from the SEC-USER-DATA record to the BMS screen fields for
     * each of the 10 display rows:</p>
     * <pre>
     *   MOVE SEC-USR-ID    TO USRID01I OF COUSR0AI
     *   MOVE SEC-USR-FNAME TO FNAME01I OF COUSR0AI
     *   MOVE SEC-USR-LNAME TO LNAME01I OF COUSR0AI
     *   MOVE SEC-USR-TYPE  TO UTYPE01I OF COUSR0AI
     * </pre>
     *
     * <p>In the COBOL program, the first record also sets
     * {@code CDEMO-CU00-USRID-FIRST} (pagination anchor for backward navigation),
     * and the 10th record sets {@code CDEMO-CU00-USRID-LAST} (pagination anchor
     * for forward navigation). In the Java migration, Spring Data's Page metadata
     * handles this automatically.</p>
     *
     * <p><strong>Security:</strong> The password field ({@code SEC-USR-PWD}) is
     * NEVER mapped to the DTO. The DTO password is explicitly set to {@code null}
     * to prevent credential leakage in browse responses. The COBOL program also
     * does not display passwords on the user list screen.</p>
     *
     * @param entity the {@link UserSecurity} entity to convert; must not be {@code null}
     * @return a new {@link UserSecurityDto} containing user ID, first name, last name,
     *         and user type, with password set to {@code null}
     */
    public UserSecurityDto convertToDto(UserSecurity entity) {
        UserSecurityDto dto = new UserSecurityDto();

        // Map fields matching COBOL POPULATE-USER-DATA (lines 384-441):
        // SEC-USR-ID → USRID01I..USRID10I
        dto.setSecUsrId(entity.getSecUsrId());

        // SEC-USR-FNAME → FNAME01I..FNAME10I
        dto.setSecUsrFname(entity.getSecUsrFname());

        // SEC-USR-LNAME → LNAME01I..LNAME10I
        dto.setSecUsrLname(entity.getSecUsrLname());

        // SEC-USR-TYPE → UTYPE01I..UTYPE10I
        dto.setSecUsrType(entity.getSecUsrType());

        // SECURITY: Password NEVER included in list/browse responses.
        // The COBOL program does not display SEC-USR-PWD on the user list screen.
        // Explicitly set to null to prevent any accidental leakage.
        dto.setSecUsrPwd(null);

        return dto;
    }

    /**
     * Converts a page of {@link UserSecurity} entities to a page of
     * {@link UserSecurityDto} instances.
     *
     * <p>Uses Spring Data's {@link Page#map(java.util.function.Function)} to
     * efficiently convert all entities in the page while preserving pagination
     * metadata (total elements, total pages, current page number, sort order).</p>
     *
     * <p>This method effectively replaces the COBOL pattern of iterating through
     * {@code WS-USER-DATA} with {@code WS-IDX} from 1 to 10 (PROCESS-PAGE-FORWARD
     * lines 300-306), calling POPULATE-USER-DATA for each record index.</p>
     *
     * @param page the page of {@link UserSecurity} entities to convert;
     *             must not be {@code null}
     * @return a new {@link Page} of {@link UserSecurityDto} with identical pagination
     *         metadata and all entities converted via {@link #convertToDto(UserSecurity)}
     */
    public Page<UserSecurityDto> convertPageToDto(Page<UserSecurity> page) {
        return page.map(this::convertToDto);
    }

    /**
     * Checks whether there is a next page available after the current page.
     *
     * <p>Maps COBOL {@code NEXT-PAGE-FLG} logic (lines 308-323). In the COBOL
     * program, after reading 10 records, one additional READNEXT is performed
     * to determine if more records exist:</p>
     * <pre>
     *   IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
     *       PERFORM READNEXT-USER-SEC-FILE
     *       IF USER-SEC-NOT-EOF AND ERR-FLG-OFF
     *           SET NEXT-PAGE-YES TO TRUE
     *       ELSE
     *           SET NEXT-PAGE-NO TO TRUE
     *       END-IF
     *   END-IF
     * </pre>
     *
     * <p>In the Java migration, {@link Page#hasNext()} provides this information
     * directly from the JPA count query, without the need for an extra read.</p>
     *
     * <p>Used by the controller to determine whether to enable PF8 (page forward)
     * functionality in the response metadata.</p>
     *
     * @param page the current page of results; must not be {@code null}
     * @return {@code true} if there is a subsequent page of results;
     *         {@code false} if the current page is the last
     */
    public boolean hasNextPage(Page<?> page) {
        return page.hasNext();
    }

    /**
     * Checks whether there is a previous page available before the current page.
     *
     * <p>Maps the COBOL page backward availability check, which verifies
     * {@code CDEMO-CU00-PAGE-NUM > 1} before allowing PF7 (page backward)
     * navigation (PROCESS-PF7-KEY lines 248-255):</p>
     * <pre>
     *   IF CDEMO-CU00-PAGE-NUM > 1
     *       PERFORM PROCESS-PAGE-BACKWARD
     *   ELSE
     *       MOVE 'You are already at the top of the page...' TO WS-MESSAGE
     *   END-IF
     * </pre>
     *
     * <p>In the Java migration, {@link Page#hasPrevious()} provides this check
     * directly — returns {@code false} for page 0 (equivalent to COBOL page 1).</p>
     *
     * @param page the current page of results; must not be {@code null}
     * @return {@code true} if there is a preceding page of results;
     *         {@code false} if the current page is the first
     */
    public boolean hasPreviousPage(Page<?> page) {
        return page.hasPrevious();
    }
}
