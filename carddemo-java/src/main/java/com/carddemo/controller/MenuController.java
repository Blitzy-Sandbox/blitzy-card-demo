package com.carddemo.controller;

import com.carddemo.model.dto.MenuResponse;
import com.carddemo.service.menu.AdminMenuService;
import com.carddemo.service.menu.MainMenuService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Menu REST controller. Re-platforms the CICS main-menu (COMEN01C) and
 * admin-menu (COADM01C) programs and their BMS screens COMEN01 / COADM01
 * (reference only, lineage commit 27d6c6f). Returns the routing option tables
 * as stateless JSON; PF-key/AID navigation maps to these REST routes.
 *
 * <p>The admin-menu route {@code GET /api/menu/admin} is documented as
 * {@code ADMIN}-only (api-contracts.md &sect;2.2). Because it does not sit under
 * the {@code /api/admin/**} URL prefix that {@code SecurityConfig} guards by role,
 * it carries an explicit method-level {@code @PreAuthorize("hasRole('ADMIN')")}
 * check so a {@code ROLE_USER} principal receives {@code 403 Forbidden} while a
 * {@code ROLE_ADMIN} principal receives {@code 200}.</p>
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MainMenuService mainMenuService;
    private final AdminMenuService adminMenuService;

    public MenuController(MainMenuService mainMenuService,
                         AdminMenuService adminMenuService) {
        this.mainMenuService = mainMenuService;
        this.adminMenuService = adminMenuService;
    }

    @GetMapping("/main")
    public MenuResponse mainMenu() {
        return new MenuResponse(mainMenuService.getMenuOptions(), null, null);
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public MenuResponse adminMenu() {
        return new MenuResponse(adminMenuService.getMenuOptions(), null, null);
    }
}
