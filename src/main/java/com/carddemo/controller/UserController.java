package com.carddemo.controller;

import com.carddemo.dto.UserDto;
import com.carddemo.service.UserAddService;
import com.carddemo.service.UserDeleteService;
import com.carddemo.service.UserListService;
import com.carddemo.service.UserUpdateService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless REST surface for the user-administration screens of the AWS CardDemo
 * application, rooted at {@code /api/admin/users}.
 *
 * <p>This controller is the HTTP entry point for the four CICS
 * pseudo-conversational user-administration programs translated at source commit
 * {@code 27d6c6f}, each delegating to its own dedicated service:</p>
 *
 * <ul>
 *   <li>{@code GET /api/admin/users} &larr; {@code COUSR00C} (transaction
 *       {@code CU00}, list all users), delegating to {@link UserListService}. The
 *       legacy PF7/PF8 browse navigation is exposed as a single zero-based
 *       {@code page} request parameter; the page size is fixed inside the
 *       service.</li>
 *   <li>{@code POST /api/admin/users} &larr; {@code COUSR01C} (transaction
 *       {@code CU01}, add a new user), delegating to {@link UserAddService}.</li>
 *   <li>{@code PUT /api/admin/users/{userId}} &larr; {@code COUSR02C}
 *       (transaction {@code CU02}, update a user), delegating to
 *       {@link UserUpdateService}.</li>
 *   <li>{@code DELETE /api/admin/users/{userId}} &larr; {@code COUSR03C}
 *       (transaction {@code CU03}, delete a user), delegating to
 *       {@link UserDeleteService}.</li>
 * </ul>
 *
 * <p>The controller is a thin HTTP adapter: it carries no business logic and no
 * data access. Request validation is declarative ({@link Min} on the page
 * parameter, enforced by the class-level {@link Validated}, and {@link Valid} on
 * the create and update bodies); domain errors raised by the services are
 * translated to HTTP status codes by the centralized
 * {@code GlobalExceptionHandler}.</p>
 *
 * <p>Every route is restricted to administrators. The class-level
 * {@link PreAuthorize} provides method-security enforcement of the
 * {@code ADMIN} role in addition to the {@code /api/admin/**} path rule declared
 * by the application security configuration.</p>
 */
@RestController
@RequestMapping("/api/admin/users")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserListService userListService;
    private final UserAddService userAddService;
    private final UserUpdateService userUpdateService;
    private final UserDeleteService userDeleteService;

    /**
     * Creates the controller with its four collaborating services.
     *
     * @param userListService   service backing the paginated user-list endpoint
     * @param userAddService    service backing the user-add endpoint
     * @param userUpdateService service backing the user-update endpoint
     * @param userDeleteService service backing the user-delete endpoint
     */
    public UserController(UserListService userListService,
                          UserAddService userAddService,
                          UserUpdateService userUpdateService,
                          UserDeleteService userDeleteService) {
        this.userListService = userListService;
        this.userAddService = userAddService;
        this.userUpdateService = userUpdateService;
        this.userDeleteService = userDeleteService;
    }

    /**
     * Returns one page of users, optionally constrained by a user-id prefix
     * ({@code COUSR00C} / {@code CU00}).
     *
     * @param userId optional user-id prefix filter; {@code null} means no filter
     * @param page   the zero-based page index; must not be negative
     * @return {@code 200 OK} with the requested page of user summaries
     */
    @GetMapping
    public ResponseEntity<UserDto.ListResponse> listUsers(
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page) {
        return ResponseEntity.ok(userListService.listUsers(userId, page));
    }

    /**
     * Validates and creates a new user ({@code COUSR01C} / {@code CU01}).
     *
     * @param request the validated create body carrying the new user values
     * @return {@code 201 Created} with the summary of the created user
     */
    @PostMapping
    public ResponseEntity<UserDto.UserSummary> addUser(
            @Valid @RequestBody UserDto.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAddService.addUser(request));
    }

    /**
     * Validates and applies an update to a single user ({@code COUSR02C} /
     * {@code CU02}).
     *
     * @param userId  the user identifier from the request path that identifies
     *                the record to update
     * @param request the validated update body carrying the new user values
     * @return {@code 200 OK} with the summary of the updated user
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserDto.UserSummary> updateUser(
            @PathVariable("userId") String userId,
            @Valid @RequestBody UserDto.UpdateRequest request) {
        return ResponseEntity.ok(userUpdateService.updateUser(userId, request));
    }

    /**
     * Deletes a single user ({@code COUSR03C} / {@code CU03}).
     *
     * @param userId the user identifier from the request path that identifies the
     *               record to delete
     * @return {@code 200 OK} with the confirmation of the deleted user
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<UserDto.DeleteResponse> deleteUser(
            @PathVariable("userId") String userId) {
        return ResponseEntity.ok(userDeleteService.deleteUser(userId));
    }
}
