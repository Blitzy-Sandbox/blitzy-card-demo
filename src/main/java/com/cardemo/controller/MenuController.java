package com.cardemo.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.ValidationException;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;

/**
 * REST controller providing menu metadata retrieval for navigation routing.
 *
 * <p>Replaces CICS BMS screens COMEN01 (main menu — 10 options from COMEN02Y.cpy)
 * and COADM01 (admin menu — 4 options from COADM02Y.cpy). Delegates to
 * {@link MainMenuService} and {@link AdminMenuService} for static menu option
 * metadata. Returns JSON lists of menu option records for client-side rendering.</p>
 *
 * <h3>COBOL Program Mapping:</h3>
 * <ul>
 *   <li>COMEN01C.cbl (282 lines) — BUILD-MENU-OPTIONS paragraph → {@code GET /api/menu/main}</li>
 *   <li>COADM01C.cbl (268 lines) — BUILD-MENU-OPTIONS paragraph → {@code GET /api/menu/admin}</li>
 * </ul>
 *
 * <p>Admin menu access control is enforced by Spring Security (SecurityConfig),
 * NOT by this controller. This controller is purely a read-only metadata provider
 * with no business logic.</p>
 *
 * @see MainMenuService
 * @see AdminMenuService
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private static final Logger logger = LoggerFactory.getLogger(MenuController.class);

    private final MainMenuService mainMenuService;
    private final AdminMenuService adminMenuService;

    /**
     * Constructs a new {@code MenuController} with the required menu service dependencies.
     *
     * <p>Spring auto-wires via single-constructor injection — no {@code @Autowired} annotation needed.</p>
     *
     * @param mainMenuService  service providing main menu routing metadata (10 options from COMEN02Y.cpy)
     * @param adminMenuService service providing admin menu routing metadata (4 options from COADM02Y.cpy)
     */
    public MenuController(MainMenuService mainMenuService, AdminMenuService adminMenuService) {
        this.mainMenuService = mainMenuService;
        this.adminMenuService = adminMenuService;
    }

    /**
     * Retrieves menu options by menu type.
     *
     * <p>Maps the COBOL BUILD-MENU-OPTIONS paragraph from both COMEN01C.cbl (main menu)
     * and COADM01C.cbl (admin menu) to a single parameterized REST endpoint. The menu
     * type is extracted from the URI path variable and matched case-insensitively.</p>
     *
     * <h4>Supported menu types:</h4>
     * <ul>
     *   <li>{@code "main"} — Returns 10 main menu options (Account View, Account Update,
     *       Credit Card List, Credit Card View, Credit Card Update, Transaction List,
     *       Transaction View, Transaction Add, Transaction Reports, Bill Payment)</li>
     *   <li>{@code "admin"} — Returns 4 admin menu options (User List, User Add,
     *       User Update, User Delete)</li>
     * </ul>
     *
     * @param type the menu type path variable — must be "main" or "admin" (case-insensitive)
     * @return {@code ResponseEntity} containing:
     *         <ul>
     *           <li>HTTP 200 with {@code List<MenuOption>} for type "main"</li>
     *           <li>HTTP 200 with {@code List<AdminMenuOption>} for type "admin"</li>
     *           <li>HTTP 400 with error message for any other type</li>
     *         </ul>
     */
    @GetMapping("/{type}")
    public ResponseEntity<?> getMenu(@PathVariable String type) {
        logger.info("Retrieving {} menu", type);

        if ("main".equalsIgnoreCase(type)) {
            List<MainMenuService.MenuOption> menuOptions = mainMenuService.getMenuOptions();
            return ResponseEntity.ok(menuOptions);
        }

        if ("admin".equalsIgnoreCase(type)) {
            List<AdminMenuService.AdminMenuOption> adminMenuOptions = adminMenuService.getAdminMenuOptions();
            return ResponseEntity.ok(adminMenuOptions);
        }

        logger.info("Invalid menu type requested: {}", type);
        throw new ValidationException("Invalid menu type. Use 'main' or 'admin'.");
    }
}
