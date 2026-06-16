package com.carddemo.controller;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserAddRequest;
import com.carddemo.model.dto.UserListResponse;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.dto.UserUpdateRequest;
import com.carddemo.service.admin.UserAddService;
import com.carddemo.service.admin.UserDeleteService;
import com.carddemo.service.admin.UserListService;
import com.carddemo.service.admin.UserUpdateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-administration REST controller. Re-platforms the CICS user list/add/
 * update/delete programs COUSR00C-COUSR03C and their BMS screens COUSR00-COUSR03
 * (reference only, lineage commit 27d6c6f). Stateless JSON CRUD endpoints replace
 * the pseudo-conversational COMMAREA flow.
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserListService userListService;
    private final UserAddService userAddService;
    private final UserUpdateService userUpdateService;
    private final UserDeleteService userDeleteService;

    public UserAdminController(UserListService userListService,
                              UserAddService userAddService,
                              UserUpdateService userUpdateService,
                              UserDeleteService userDeleteService) {
        this.userListService = userListService;
        this.userAddService = userAddService;
        this.userUpdateService = userUpdateService;
        this.userDeleteService = userDeleteService;
    }

    @GetMapping
    public UserListResponse listUsers(@RequestParam(required = false) String userId,
                                      @RequestParam(defaultValue = "1") int page) {
        return userListService.listUsers(page, userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse addUser(@Valid @RequestBody UserAddRequest request) {
        return userAddService.addUser(request);
    }

    @PutMapping("/{userId}")
    public UserResponse updateUser(@PathVariable String userId,
                                   @Valid @RequestBody UserUpdateRequest request) {
        // Reject a body that targets a different user than the path resource, removing
        // the ambiguous write semantics flagged in review and honouring the documented
        // PUT /api/admin/users/{userId} contract.
        if (!userId.equals(request.userId())) {
            throw new ValidationException(
                    "User ID in path does not match request body", "userId");
        }
        return userUpdateService.updateUser(request);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable String userId) {
        userDeleteService.deleteUser(userId);
    }
}
