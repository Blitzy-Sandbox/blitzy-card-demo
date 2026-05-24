/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.dto.CardListDto;
import com.awsm2.carddemo.dto.CardUpdateDto;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.service.CardDetailService;
import com.awsm2.carddemo.service.CardListService;
import com.awsm2.carddemo.service.CardUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Card REST controller &mdash; list, view, and update card records.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1):</b> this controller replaces
 * three CICS COBOL programs operating on the {@code CARDDATA} VSAM KSDS
 * cluster (record layout {@code app/cpy/CVACT02Y.cpy} CARD-RECORD,
 * 150-byte) with optimistic-lock semantics on the update path:</p>
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (CICS transaction id {@code CCLI})
 *       &mdash; Card list with pagination (7 rows per page),
 *       supporting optional account-filter narrowing; rendered via
 *       {@code app/bms/COCRDLI.bms}. The Java target uses
 *       {@link CardListService#listCards(Long, boolean, int)}.</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (CICS transaction id {@code CCDL})
 *       &mdash; Card detail by 16-digit card number; rendered via
 *       {@code app/bms/COCRDSL.bms}. The Java target uses
 *       {@link CardDetailService#getCardDetail(String)}.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (CICS transaction id {@code CCUP})
 *       &mdash; Card update with optimistic locking on cardholder
 *       name / expiration / active status; rendered via
 *       {@code app/bms/COCRDUP.bms}. The Java target uses
 *       {@link CardUpdateService#updateCard(String, CardUpdateDto)}
 *       with JPA {@code @Version} replacing the COBOL before/after
 *       snapshot pattern.</li>
 * </ul>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code GET /api/cards?account={id}&page={n}} &mdash; paginated
 *       card list (7 rows per page); supports an optional
 *       {@code account} filter. Available to any authenticated
 *       principal; admin callers receive the full data set, non-admin
 *       callers see only the cards for the queried account
 *       (enforced server-side per the COBOL
 *       {@code COCRDLIC.cbl:0000-MAIN} role-driven narrowing).</li>
 *   <li>{@code GET /api/cards/{cardNumber}} &mdash; card detail by
 *       16-digit card number. Available to any authenticated
 *       principal.</li>
 *   <li>{@code PUT /api/cards/{cardNumber}} &mdash; card update with
 *       optimistic locking. Available to any authenticated principal
 *       (the COBOL source did not gate the update program by role).</li>
 * </ul>
 *
 * <p><b>Card number representation (AAP &sect;0.6.6 PCI-DSS):</b> card
 * numbers are 16-digit strings ({@code CARD-NUM PIC X(16)} from
 * {@code app/cpy/CVACT02Y.cpy}). In response payloads, card numbers are
 * masked (PAN tokenization) by the DTO layer's {@code toString()} to
 * protect cardholder data per PCI-DSS Requirement 3. The controller
 * layer accepts the full 16-digit string as a path variable for lookups
 * but NEVER logs the full PAN: every log statement emits only the last
 * 4 digits prefixed with {@code "****"} (using
 * {@code cardNumber.substring(12)}, which is safe because the
 * {@link Pattern @Pattern(regexp = "^[0-9]{16}$")} validator
 * guarantees the input is exactly 16 characters before the handler
 * body executes). Structured logs flow through Logback +
 * logstash-logback-encoder to CloudWatch Logs (AAP &sect;0.6.6).</p>
 *
 * <p><b>Bean Validation (AAP &sect;0.3.4):</b> the class-level
 * {@link Validated @Validated} annotation activates Jakarta Bean
 * Validation on {@code @RequestParam} and {@code @PathVariable}
 * arguments &mdash; without it, the {@link Pattern @Pattern},
 * {@link NotBlank @NotBlank}, and {@link Min @Min} annotations on
 * individual parameters would be ignored. Cascading validation into
 * the {@code @RequestBody CardUpdateDto} is triggered separately by
 * the parameter-level {@link Valid @Valid} annotation. Constraint
 * violations bubble up as
 * {@code jakarta.validation.ConstraintViolationException} (for path /
 * query) or
 * {@code org.springframework.web.bind.MethodArgumentNotValidException}
 * (for the request body) and are translated by
 * {@code GlobalExceptionHandler} into the standardized
 * {@link ApiResponse} envelope with HTTP 400 status.</p>
 *
 * <p><b>Layered architecture compliance:</b> thin Spring MVC fa&ccedil;ade;
 * no business state, no I/O, no repository or AWS-adapter access.
 * Delegates to {@link CardListService}, {@link CardDetailService}, and
 * {@link CardUpdateService} via constructor injection.</p>
 *
 * <p><b>Standardized response envelope (AAP &sect;0.3.4):</b> all
 * responses use {@link ApiResponse#success(Object)}. Failures surface
 * as typed exceptions handled by {@code GlobalExceptionHandler}.</p>
 *
 * @see CardListService
 * @see CardDetailService
 * @see CardUpdateService
 * @see CardListDto
 * @see CardDetailDto
 * @see CardUpdateDto
 */
@RestController
@RequestMapping("/api/cards")
@Tag(name = "Card",
        description = "Card list, detail, and maintenance. Replaces CICS "
                + "COCRDLIC (Tran-ID CCLI), COCRDSLC (Tran-ID CCDL), and "
                + "COCRDUPC (Tran-ID CCUP).")
@Validated
public class CardController {

    /**
     * SLF4J facade for structured JSON logging. Per AAP &sect;0.7.2.
     * PCI-DSS discipline (AAP &sect;0.6.6): full card numbers are
     * NEVER logged at any level; only the last 4 digits prefixed with
     * {@code "****"} (via {@code cardNumber.substring(12)}) are
     * emitted, and only after the
     * {@link Pattern @Pattern(regexp = "^[0-9]{16}$")} validator on
     * the path variable has guaranteed length == 16. Account IDs and
     * page indices are non-sensitive and may be logged unmasked.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardController.class);

    /**
     * Card list service collaborator &mdash; encapsulates the paginated
     * scan of {@code CARDDATA} with optional account-filter narrowing
     * originally implemented by the
     * {@code 9000-LIST-CARDS-WITH-FILTERS} paragraph in
     * {@code app/cbl/COCRDLIC.cbl}.
     */
    private final CardListService cardListService;

    /**
     * Card detail service collaborator &mdash; encapsulates the single
     * keyed read of {@code CARDDATA} originally implemented by the
     * {@code 9000-READ-CARDDATA} paragraph in
     * {@code app/cbl/COCRDSLC.cbl}, with cache-aside caching via
     * ElastiCache Redis (AAP &sect;0.6.5).
     */
    private final CardDetailService cardDetailService;

    /**
     * Card update service collaborator &mdash; encapsulates the
     * optimistic-lock-guarded update of {@code CARDDATA} originally
     * implemented by the {@code 9500-WRITE-PROCESSING} paragraph in
     * {@code app/cbl/COCRDUPC.cbl} with the {@code REWRITE} CICS
     * verb on the {@code CARDDATA} VSAM cluster.
     */
    private final CardUpdateService cardUpdateService;

    /**
     * Constructor used by Spring's dependency injection container.
     *
     * @param cardListService   the {@link CardListService} collaborator
     * @param cardDetailService the {@link CardDetailService} collaborator
     * @param cardUpdateService the {@link CardUpdateService} collaborator
     */
    public CardController(CardListService cardListService,
                          CardDetailService cardDetailService,
                          CardUpdateService cardUpdateService) {
        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
    }

    /**
     * Returns a paginated card list with optional account-filter narrowing.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COCRDLIC.cbl:0000-MAIN} which paginated the
     * {@code CARDDATA} VSAM browse (7 records per BMS page from
     * {@code app/bms/COCRDLI.bms} {@code OCCURS 7 TIMES}). Non-admin
     * users see only the cards bound to the account they specified;
     * admin users see all cards.</p>
     *
     * <p><b>Pagination contract:</b> the service-level constant
     * {@link CardListService#PAGE_SIZE} (7, per the COBOL BMS
     * {@code OCCURS 7}) determines the page size; the client cannot
     * override it.</p>
     *
     * <p><b>Role-based narrowing:</b> the controller derives the
     * {@code isAdmin} flag from the authenticated principal's
     * authorities (NOT from any request-body or query-string field).
     * This prevents the body-trust defect identified in the CP5
     * review of the previously-removed {@code /api/menu/resolve}
     * endpoint &mdash; the source of truth for the caller's role is
     * the JWT-populated {@code SecurityContext}.</p>
     *
     * @param accountId optional 11-digit account ID to narrow the
     *                  result to cards bound to that account. May
     *                  be {@code null}; if {@code null} and caller
     *                  is non-admin, the service throws
     *                  {@code ValidationException}
     * @param page      0-based page index; defaults to 0. Rejected via
     *                  {@link Min @Min(0)} when negative
     *                  (HTTP 400 via {@code GlobalExceptionHandler}).
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link CardListDto} wrapped in {@link ApiResponse}
     */
    @GetMapping
    @Operation(
            summary = "List cards (paginated, 7 rows per page)",
            description = "Returns a paginated card list with optional "
                    + "account-filter narrowing. Default page size is 7 "
                    + "(matches CICS COCRDLI.bms layout). Admin callers see "
                    + "all cards (across all accounts); non-admin callers "
                    + "must supply an account filter and see only that "
                    + "account's cards (enforced server-side). "
                    + "Replaces CICS COCRDLIC / Tran-ID CCLI (card list)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Card list returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (e.g., non-admin call without "
                            + "account filter, or negative page index)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<CardListDto>> listCards(
            @RequestParam(name = "accountId", required = false)
            @Parameter(
                    description = "Optional 11-digit account filter (COBOL ACCT-ID); "
                            + "required for non-admin callers",
                    example = "10000000001")
            Long accountId,

            @RequestParam(name = "page", required = false, defaultValue = "0")
            @Min(value = 0, message = "page must be >= 0")
            @Parameter(
                    description = "0-based page index; defaults to 0 when omitted",
                    example = "0")
            int page,

            // Issue CP4-#15: page size is validated at the controller
            // boundary to prevent DoS via excessive size requests. The
            // COBOL COCRDLIC contract fixes the page at 7 rows (the
            // height of the BMS COCRDLI map's WS-MAX-SCREEN-LINES); the
            // service enforces this internally via PAGE_SIZE=7. The
            // size query parameter is accepted for OpenAPI forward
            // compatibility and to ensure invalid values are rejected
            // explicitly (400 Bad Request) rather than silently
            // ignored. Bounded to [1, 100] per QA recommendation.
            @RequestParam(name = "size", required = false, defaultValue = "7")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = 100, message = "size must be <= 100")
            @Parameter(
                    description = "Requested page size; bounded to [1, 100] to prevent "
                            + "DoS via excessive page requests. The service caps the "
                            + "effective page size at 7 to preserve the COBOL "
                            + "COCRDLIC BMS row-repeat contract (PAGE_SIZE=7). "
                            + "Values >7 are silently capped; values outside "
                            + "[1, 100] are rejected with HTTP 400.",
                    example = "7")
            int size) {
        // COBOL: COCRDLIC / Tran-ID CCLI -- 0000-MAIN paginated browse
        //   (delegates to CardListService which preserves the COBOL
        //   PAGE_SIZE=7 from WS-MAX-SCREEN-LINES and role-driven
        //   narrowing per AAP §0.4.1).
        // Derive the isAdmin flag from the authenticated principal's
        // authorities (NOT from any request body/query field). This is
        // the principal-of-truth approach mandated by the CP5 review
        // and replaces the COBOL CDEMO-USRTYPE-ADMIN COMMAREA check.
        final boolean isAdmin = currentCallerIsAdmin();
        // PCI-DSS-safe traceability log (AAP §0.6.6 / §0.7.2): only
        // non-sensitive account ID + page index emitted; no card number
        // leaks into operational logs.
        // Issue CP4-#15: size is validated at the controller boundary
        // (@Min(1) @Max(100)) but the service enforces the COBOL
        // PAGE_SIZE=7 contract internally. The size parameter
        // therefore acts only as a DoS guard; it is intentionally not
        // plumbed into the service method to preserve the COBOL
        // page-shape contract per the AAP Minimal Change Clause.
        LOG.debug("Card list requested: accountId={} isAdmin={} page={} requestedSize={} (effective=7)",
                accountId, isAdmin, page, size);
        CardListDto cardList = cardListService.listCards(accountId, isAdmin, page);
        return ResponseEntity.ok(ApiResponse.success(cardList));
    }

    /**
     * Returns the card detail for the supplied 16-digit card number.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COCRDSLC.cbl:PROCESS-ENTER-KEY} which read the
     * {@code CARD-RECORD} from {@code CARDDATA} keyed on
     * {@code CARD-NUM} and rendered the {@code COCRDSL.bms} screen.
     * The Java target uses cache-aside via ElastiCache Redis to
     * accelerate the repeated lookup pattern (the COBOL source had
     * no cache; AAP &sect;0.6.5 introduces caching as a performance
     * non-functional improvement permitted by the migration).</p>
     *
     * <p><b>Authorization (QA finding D1):</b> restricted to callers
     * holding the {@code ADMIN} role. PCI-DSS v4.0 Requirement 7.1
     * mandates access restriction by business need-to-know; in the
     * COBOL source code the USER role accessed card detail only via
     * implicit CICS COMMAREA navigation context which the REST
     * surface cannot reproduce. Because the {@link com.awsm2.carddemo.domain.UserSecurity}
     * entity has no user-to-customer/account binding (USR records
     * model bank employees, not cardholders), per-card ownership
     * cannot be enforced at the row level without a data-model
     * change that is out of scope for the migration. Restricting
     * the endpoint to administrators preserves operational parity
     * (employees in the COBOL system could reach any card through
     * navigation) while closing the IDOR vulnerability. Non-admin
     * callers receive HTTP 403 from Spring Security via
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler#handleAccessDenied}.</p>
     *
     * <p><b>Input validation:</b> the path variable is rejected with
     * HTTP 400 ({@code ConstraintViolationException} &rarr;
     * {@code GlobalExceptionHandler}) when blank
     * ({@link NotBlank @NotBlank}) or not exactly 16 digits
     * ({@link Pattern @Pattern(regexp = "^[0-9]{16}$")}). When the
     * path is well-formed but no card exists, the service layer
     * throws {@code RecordNotFoundException} &rarr; HTTP 404.</p>
     *
     * @param cardNumber the 16-digit card number
     *                   ({@code CARD-NUM PIC X(16)} from
     *                   {@code app/cpy/CVACT02Y.cpy})
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link CardDetailDto} wrapped in {@link ApiResponse}.
     *         HTTP 400 on malformed input; HTTP 403 when the caller
     *         does not hold the {@code ADMIN} role; HTTP 404
     *         ({@code RecordNotFoundException}) if no card
     *         exists for the supplied number
     */
    @GetMapping("/{cardNumber}")
    @Operation(
            summary = "Get card detail by 16-digit card number (ADMIN-only)",
            description = "Returns a single card record. Restricted to ADMIN role "
                    + "per PCI-DSS v4.0 Requirement 7.1 (QA finding D1 -- IDOR "
                    + "remediation). Replaces CICS COCRDSLC / Tran-ID CCDL "
                    + "(card detail)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Card detail returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (card number not 16 digits)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden -- caller does not hold ADMIN role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Card not found")
    })
    // QA finding D1 (Checkpoint 2): restricted to ADMIN role to remediate
    // the Insecure Direct Object Reference (IDOR) vulnerability that
    // allowed any authenticated USER to fetch any card by its number.
    // PCI-DSS v4.0 Requirement 7.1 mandates need-to-know access; USER
    // (bank-employee) records have no user-to-cardholder binding so
    // row-level ownership checks are not possible without a data-model
    // change that exceeds migration scope. See V017 / DB1 fix narrative.
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CardDetailDto>> getCard(
            @PathVariable("cardNumber")
            @NotBlank(message = "cardNumber is required")
            @Pattern(regexp = "^[0-9]{16}$",
                    message = "cardNumber must be exactly 16 digits")
            @Parameter(
                    description = "16-digit card number (COBOL CARD-NUM PIC X(16))",
                    example = "4111111111111111")
            String cardNumber) {
        // COBOL: COCRDSLC / Tran-ID CCDL -- 9000-READ-CARDDATA single
        //   keyed read of CARDDAT (delegates to CardDetailService which
        //   performs cache-aside via ElastiCache Redis per AAP §0.6.5).
        // PCI-DSS (AAP §0.6.6, §0.7.2): NEVER log the full PAN. Emit
        // only the last 4 digits prefixed with "****" so operational
        // logs are safe for CloudWatch + OpenSearch ingestion. The
        // @Pattern validator above guarantees length == 16, so the
        // substring(12) slice is always safe.
        LOG.debug("Card detail requested for cardNumber=****{}",
                cardNumber.substring(12));
        CardDetailDto detail = cardDetailService.getCardDetail(cardNumber);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Applies validated updates to the card with optimistic locking.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COCRDUPC.cbl:9500-WRITE-PROCESSING} which:</p>
     * <ol>
     *   <li>{@code READ UPDATE} the {@code CARD-RECORD} from
     *       {@code CARDDATA} keyed on {@code CARD-NUM}.</li>
     *   <li>Compared the read record against the snapshot taken at the
     *       prior screen render. On mismatch &rarr;
     *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} branch and unlock
     *       without rewrite.</li>
     *   <li>{@code REWRITE} the {@code CARD-RECORD} with new
     *       values (cardholder name, expiration date, active status).</li>
     * </ol>
     *
     * <p>The Java target replaces these with a {@code @Transactional}
     * method on {@link CardUpdateService} using JPA {@code @Version}
     * for optimistic locking; the {@code CardUpdateDto.version()}
     * field carries the version value from the prior {@code GET},
     * and JPA raises
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * (translated to HTTP 409) on mismatch.</p>
     *
     * <p><b>Path vs body card-number consistency check:</b> the
     * controller verifies the {@code cardNumber} path variable equals
     * the {@code cardNumber} field carried in the request body.
     * Mismatches throw {@link ValidationException} (HTTP 400) to
     * prevent IDOR-style confusion.</p>
     *
     * @param cardNumber the 16-digit card number from the URL path
     * @param request    the validated {@link CardUpdateDto} containing
     *                   the card number (must equal path), version,
     *                   and updatable fields
     * @return {@link ResponseEntity} with HTTP 200 and the updated
     *         {@link CardDetailDto} wrapped in {@link ApiResponse}.
     *         HTTP 409 on optimistic-lock conflict; HTTP 400 on
     *         validation failure; HTTP 404 if the card does not exist
     */
    @PutMapping("/{cardNumber}")
    @Operation(
            summary = "Update card details with optimistic locking (ADMIN-only)",
            description = "Applies validated updates (cardholder name, "
                    + "expiration, active status) with JPA @Version optimistic "
                    + "locking. Restricted to ADMIN role per PCI-DSS v4.0 "
                    + "Requirement 7.1 (QA finding U1 -- IDOR remediation). "
                    + "Replaces CICS COCRDUPC / Tran-ID CCUP (card update) "
                    + "including the DATA-WAS-CHANGED-BEFORE-UPDATE branch."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Card updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (path/body mismatch, invalid field)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden -- caller does not hold ADMIN role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Card not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Optimistic-lock conflict (card changed since GET)")
    })
    // QA finding U1 (Checkpoint 2): restricted to ADMIN role to remediate
    // the Insecure Direct Object Reference (IDOR) vulnerability that
    // allowed any authenticated USER to modify any card (including
    // deactivation of cards belonging to other customers). PCI-DSS v4.0
    // Requirement 7.1 mandates need-to-know access for state-modifying
    // operations on cardholder data. See D1 remediation rationale on
    // getCard above.
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CardDetailDto>> updateCard(
            @PathVariable("cardNumber")
            @NotBlank(message = "cardNumber is required")
            @Pattern(regexp = "^[0-9]{16}$",
                    message = "cardNumber must be exactly 16 digits")
            @Parameter(
                    description = "16-digit card number (COBOL CARD-NUM PIC X(16)); "
                            + "must equal the cardNumber field in the request body",
                    example = "4111111111111111")
            String cardNumber,
            @Valid @RequestBody CardUpdateDto request) {
        // COBOL: COCRDUPC / Tran-ID CCUP -- 9500-WRITE-PROCESSING +
        //   DATA-WAS-CHANGED-BEFORE-UPDATE branch (delegates to
        //   CardUpdateService which uses JPA @Version for optimistic
        //   locking per AAP §0.4.1, replacing COBOL before/after image
        //   comparison and the CICS REWRITE verb on the CARDDAT VSAM
        //   cluster).
        //
        // Path/body consistency check — prevents IDOR confusion (a
        // client must not be able to PUT against /api/cards/A with a
        // body carrying cardNumber=B). Card numbers are 16-digit
        // strings (may have leading zeros); compare via Objects.equals
        // to honour null-safety. CardUpdateService also performs this
        // defensive check, but rejecting at the controller boundary is
        // cheaper and surfaces a clearer error to the caller.
        if (!Objects.equals(cardNumber, request.cardNumber())) {
            LOG.warn("Card update rejected: path cardNumber differs from body");
            throw new ValidationException(
                    "CARD_NUMBER_MISMATCH",
                    "Path card number must match request body cardNumber");
        }
        // PCI-DSS (AAP §0.6.6, §0.7.2): NEVER log the full PAN. The
        // @Pattern validator guarantees length == 16, so substring(12)
        // is always safe.
        LOG.info("Card update requested for cardNumber=****{}",
                cardNumber.substring(12));
        CardDetailDto updated = cardUpdateService.updateCard(cardNumber, request);
        LOG.info("Card update successful for cardNumber=****{}",
                cardNumber.substring(12));
        return ResponseEntity.ok(ApiResponse.success(updated,
                "Card updated successfully"));
    }

    /**
     * Returns {@code true} if the current authenticated principal
     * carries the {@code ROLE_ADMIN} authority. Reads the
     * {@code SecurityContextHolder} directly (rather than a controller
     * method parameter) so this can be used from any endpoint without
     * threading {@code Authentication} through every signature.
     *
     * <p><b>Security rationale (CP5 review):</b> deriving the admin
     * flag from the authenticated principal's authorities &mdash; rather
     * than from any request-body or query-string field &mdash; closes
     * the body-trust defect identified by the CP5 review against the
     * previously-removed {@code /api/menu/resolve} endpoint. The JWT
     * authentication filter populates the {@code SecurityContext} with
     * authorities derived from the {@code UserSecurity.secUsrType}
     * column (mapped {@code 'A'} &rarr; {@code ROLE_ADMIN}, anything
     * else &rarr; {@code ROLE_USER}); this method reads from that
     * trusted source.</p>
     *
     * @return {@code true} if the caller is an admin; {@code false}
     *         otherwise (including when no authentication is present,
     *         which should not happen because the URL matchers gate
     *         this controller behind {@code authenticated()})
     */
    private boolean currentCallerIsAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
