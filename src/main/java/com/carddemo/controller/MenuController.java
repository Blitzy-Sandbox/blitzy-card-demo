/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.controller;

import com.carddemo.dto.MenuDto;
import com.carddemo.service.MenuService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the CardDemo menu flow.
 *
 * <p>This controller is the stateless HTTP front for the two CICS
 * pseudo-conversational menu screens: {@code COMEN01C} (regular-user main menu,
 * transaction {@code CM00}, mapset {@code app/bms/COMEN01.bms}) and
 * {@code COADM01C} (administrator menu, transaction {@code CA00}, mapset
 * {@code app/bms/COADM01.bms}) @ {@code 27d6c6f}. Each screen becomes a single
 * {@code GET} endpoint that returns its menu definition; the legacy COMMAREA
 * navigation and PF-key handling are replaced by stateless request/response
 * flows secured by JWT.</p>
 *
 * <p>The controller is a thin presentation layer: it holds no business logic and
 * performs no data access, delegating entirely to {@link MenuService}. Failures
 * are not handled inline; they propagate to the application's central
 * {@code GlobalExceptionHandler}.</p>
 *
 * <p>Authorization mirrors the legacy {@code CDEMO-USER-TYPE} routing where the
 * administrator type ({@code 'A'} → {@code ROLE_ADMIN}) reaches the admin menu
 * and the regular-user type ({@code 'U'} → {@code ROLE_USER}) reaches the main
 * menu. {@code GET /api/menu/main} requires an authenticated caller, enforced by
 * the stateless filter chain in {@code SecurityConfig}; {@code GET
 * /api/menu/admin} additionally requires {@code ROLE_ADMIN}, enforced here with
 * {@link PreAuthorize}.</p>
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuService menuService;

    /**
     * Creates the controller with its single collaborator.
     *
     * @param menuService the service supplying the static menu definitions;
     *                    never {@code null}
     */
    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * Returns the regular-user main menu.
     *
     * <p>Realizes the screen rendered by {@code COMEN01C} for transaction
     * {@code CM00}: the {@code "Main Menu"} heading and its ten options. The
     * route requires an authenticated caller.</p>
     *
     * @return {@code 200 OK} wrapping the main-menu definition
     */
    @GetMapping("/main")
    public ResponseEntity<MenuDto.MenuResponse> getMainMenu() {
        return ResponseEntity.ok(menuService.getMainMenu());
    }

    /**
     * Returns the administrator menu.
     *
     * <p>Realizes the screen rendered by {@code COADM01C} for transaction
     * {@code CA00}: the {@code "Admin Menu"} heading and its four options. The
     * route requires {@code ROLE_ADMIN}.</p>
     *
     * @return {@code 200 OK} wrapping the admin-menu definition
     */
    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuDto.MenuResponse> getAdminMenu() {
        return ResponseEntity.ok(menuService.getAdminMenu());
    }
}
