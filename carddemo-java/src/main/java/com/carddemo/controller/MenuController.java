package com.carddemo.controller;

import com.carddemo.model.dto.MenuResponse;
import com.carddemo.service.menu.AdminMenuService;
import com.carddemo.service.menu.MainMenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Menu REST controller. Re-platforms the CICS main-menu (COMEN01C) and
 * admin-menu (COADM01C) programs and their BMS screens COMEN01 / COADM01
 * (reference only, lineage commit 27d6c6f). Returns the routing option tables
 * as stateless JSON; PF-key/AID navigation maps to these REST routes.
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
    public MenuResponse adminMenu() {
        return new MenuResponse(adminMenuService.getMenuOptions(), null, null);
    }
}
