package com.carddemo.useradmin.web;

import com.carddemo.useradmin.api.UsersApi;
import com.carddemo.useradmin.model.User;
import com.carddemo.useradmin.model.UserCreateRequest;
import com.carddemo.useradmin.model.UserListResponse;
import com.carddemo.useradmin.model.UserUpdateRequest;

import java.util.ArrayList;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * User Administration REST controller for {@code useradmin-svc} — a <strong>typed stub</strong>
 * ({@code [DEFERRED]}) in the CardDemo walking skeleton.
 *
 * <p>This class implements the OpenAPI-generated {@link UsersApi} interface (generated at build
 * time by {@code openapi-generator-maven-plugin} from the frozen single-source-of-truth contract
 * {@code contracts/useradmin-svc.openapi.yaml} into {@code com.carddemo.useradmin.api}). Because the
 * generator runs with {@code interfaceOnly=true}, every Spring MVC concern — the URL mappings
 * ({@code @RequestMapping}), the HTTP method, the media types, the parameter bindings
 * ({@code @PathVariable}, {@code @RequestBody}, {@code @RequestHeader}, {@code @RequestParam}) and
 * the bean-validation constraints ({@code @Valid}, {@code @Size}, {@code @Min}, {@code @Max}) — is
 * declared <em>on the interface</em>. This controller therefore stays deliberately annotation-light:
 * it carries only {@link RestController} on the type and {@code @Override} on each method, inheriting
 * all request mappings from {@code UsersApi}. Re-declaring any mapping/binding annotation here would
 * risk duplicate or ambiguous handler registration.</p>
 *
 * <p><strong>Zero persistence (Decision E).</strong> {@code useradmin-svc} owns no JPA entity,
 * repository, or service layer and holds no schema. Consequently this controller injects nothing
 * (no {@code @Autowired}, no constructor dependencies), reads/writes no database, and implements no
 * user-administration business rules. Each handler returns a fully-typed placeholder response. This
 * is the intended, correct outcome for the walking skeleton: the seam (contract + typed stub) is
 * what is being proven, not behavioral parity with the legacy application. The {@code 400}/{@code 404}/
 * {@code 409} error responses documented in the contract are not produced by this stub, though the
 * bean-validation constraints inherited from {@code UsersApi} may still yield an automatic
 * {@code 400} for malformed input — which is expected and acceptable.</p>
 *
 * <p><strong>Password is never exposed.</strong> The {@link User} response model intentionally has
 * no password field (the legacy {@code SEC-USR-PWD} is omitted from every response). This controller
 * never reads, maps, returns, or logs the incoming password, and it never logs whole request bodies.
 * No logger is declared here at all.</p>
 *
 * <p><strong>Health is out of scope for this controller.</strong> The generated {@code HealthApi} is
 * deliberately left unimplemented — Spring Boot Actuator serves {@code /actuator/health} (which backs
 * the docker-compose {@code depends_on: condition: service_healthy} gate). Implementing a second
 * handler for that path would fail startup with a duplicate-mapping error. This class implements
 * {@link UsersApi} only; the sibling admin-menu endpoint is served by a separate controller.</p>
 *
 * <p>This bean is auto-detected by the default component scan of the base-package entrypoint
 * {@code com.carddemo.useradmin.UserAdminApplication} (a plain {@code @SpringBootApplication}).</p>
 *
 * <p>Provenance: {@code [SRC: COUSR00C/01C/02C/03C | USRSEC]} — the legacy CICS User-Administration
 * transactions (User List / Add / Update / Delete) over the {@code USRSEC} VSAM KSDS, registered in
 * {@code app/csd/CARDDEMO.CSD}. User records mirror {@code SEC-USER-DATA} [app/cpy/CSUSR01Y.cpy].</p>
 *
 * @see UsersApi
 */
@RestController
public class UserController implements UsersApi {

    /**
     * {@code GET /users} — list users (typed stub).
     *
     * <p>Returns HTTP {@code 200} with an empty, fully-populated {@link UserListResponse}
     * (no items, zero totals). Performs no {@code USRSEC} read; the correlation-ID header and
     * pagination parameters are accepted (and bound/validated by the interface) but intentionally
     * unused by this stub.</p>
     *
     * @param xCorrelationID the optional {@code X-Correlation-ID} trace header (bound by the interface)
     * @param page           the optional zero-based page index (bound/validated by the interface)
     * @param size           the optional page size (bound/validated by the interface)
     * @return {@code 200 OK} with an empty page of users
     */
    @Override
    public ResponseEntity<UserListResponse> listUsers(UUID xCorrelationID, Integer page, Integer size) {
        // Empty page placeholder. totalItems is int64 in the contract -> must be a Long (0L).
        UserListResponse body = new UserListResponse()
                .items(new ArrayList<>())
                .page(0)
                .size(0)
                .totalItems(0L)
                .totalPages(0);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code POST /users} — create a user (typed stub).
     *
     * <p>Returns HTTP {@code 201} with a {@link User} echoing the request's
     * {@code userId}/{@code firstName}/{@code lastName}/{@code userType}. The password is
     * deliberately <strong>excluded</strong> — the {@link User} response model has no password field
     * and the raw password is never read here. No user is persisted.</p>
     *
     * <p>The {@code userType} is converted across the two distinct generated enum types
     * ({@link UserCreateRequest.UserTypeEnum} → {@link User.UserTypeEnum}) via its string value,
     * because a direct assignment or cast between the separate inner enums would not compile.</p>
     *
     * @param userCreateRequest the create payload (validated by the interface)
     * @param xCorrelationID    the optional {@code X-Correlation-ID} trace header (bound by the interface)
     * @return {@code 201 CREATED} with the echoed user (password omitted)
     */
    @Override
    public ResponseEntity<User> createUser(UserCreateRequest userCreateRequest, UUID xCorrelationID) {
        // Echo request fields EXCLUDING password (the User model has no password field, and the
        // password must never be returned or logged). Cross-convert the userType enum by value.
        User body = new User()
                .userId(userCreateRequest.getUserId())
                .firstName(userCreateRequest.getFirstName())
                .lastName(userCreateRequest.getLastName())
                .userType(User.UserTypeEnum.fromValue(userCreateRequest.getUserType().getValue()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * {@code GET /users/{userId}} — get a user by ID (typed stub).
     *
     * <p>Returns HTTP {@code 200} with a placeholder {@link User} that echoes the path
     * {@code userId}, carries empty names, and defaults {@code userType} to {@code U}. Performs no
     * {@code USRSEC} read.</p>
     *
     * @param userId         the user identifier from the path (validated by the interface)
     * @param xCorrelationID the optional {@code X-Correlation-ID} trace header (bound by the interface)
     * @return {@code 200 OK} with a placeholder user
     */
    @Override
    public ResponseEntity<User> getUser(String userId, UUID xCorrelationID) {
        User body = new User()
                .userId(userId)
                .firstName("")
                .lastName("")
                .userType(User.UserTypeEnum.U);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code PUT /users/{userId}} — update a user (typed stub).
     *
     * <p>Returns HTTP {@code 200} with a placeholder {@link User} echoing the path {@code userId}
     * together with the update payload's {@code firstName}/{@code lastName}/{@code userType}. The
     * optional password is never read or returned. Because {@code userType} is required by the
     * contract it is normally present, but a null-guard defaults it to {@code U} defensively before
     * the cross-enum conversion. No user is persisted.</p>
     *
     * @param userId            the user identifier from the path (validated by the interface)
     * @param userUpdateRequest the update payload (validated by the interface)
     * @param xCorrelationID    the optional {@code X-Correlation-ID} trace header (bound by the interface)
     * @return {@code 200 OK} with the echoed, updated user (password omitted)
     */
    @Override
    public ResponseEntity<User> updateUser(String userId, UserUpdateRequest userUpdateRequest, UUID xCorrelationID) {
        User body = new User()
                .userId(userId)
                .firstName(userUpdateRequest.getFirstName())
                .lastName(userUpdateRequest.getLastName())
                .userType(userUpdateRequest.getUserType() == null
                        ? User.UserTypeEnum.U
                        : User.UserTypeEnum.fromValue(userUpdateRequest.getUserType().getValue()));
        return ResponseEntity.ok(body);
    }

    /**
     * {@code DELETE /users/{userId}} — delete a user (typed stub).
     *
     * <p>Returns HTTP {@code 204 No Content}. Performs no deletion in the walking skeleton.</p>
     *
     * @param userId         the user identifier from the path (validated by the interface)
     * @param xCorrelationID the optional {@code X-Correlation-ID} trace header (bound by the interface)
     * @return {@code 204 NO CONTENT}
     */
    @Override
    public ResponseEntity<Void> deleteUser(String userId, UUID xCorrelationID) {
        return ResponseEntity.noContent().build();
    }
}
