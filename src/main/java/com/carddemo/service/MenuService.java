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
package com.carddemo.service;

import java.util.List;

import com.carddemo.dto.MenuDto;
import org.springframework.stereotype.Service;

/**
 * Supplies the static menu definitions that back {@code GET /api/menu/main} and
 * {@code GET /api/menu/admin}.
 *
 * <p>This service replaces the CICS pseudo-conversational menu programs
 * {@code COMEN01C} (main menu, transaction {@code CM00}) and {@code COADM01C}
 * (admin menu, transaction {@code CA00}) @ {@code 27d6c6f}. The option tables
 * are translated from the copybooks {@code app/cpy/COMEN02Y.cpy} (ten main
 * options) and {@code app/cpy/COADM02Y.cpy} (four admin options); the menu
 * headings are taken from the BMS mapsets {@code app/bms/COMEN01.bms} and
 * {@code app/bms/COADM01.bms}.</p>
 *
 * <p>The legacy {@code BUILD-MENU-OPTIONS} index machinery over an
 * {@code OCCURS 12} fixed array is modeled here as immutable {@link List}
 * instances of {@link MenuDto.MenuOption}; the twelve physical screen slots are
 * a presentation concern owned by {@link MenuDto}. Each option exposes its
 * selection code and display name separately, leaving any display formatting to
 * the controller layer.</p>
 *
 * <p>The component is stateless and therefore thread-safe: the option tables are
 * {@code private static final} immutable lists and the service holds no mutable
 * fields.</p>
 */
@Service
public class MenuService {

    /**
     * Heading shown for the regular-user main menu, from the {@code COMEN01.bms}
     * mapset @ {@code 27d6c6f}.
     */
    private static final String MAIN_MENU_TITLE = "Main Menu";

    /**
     * Heading shown for the administrator menu, from the {@code COADM01.bms}
     * mapset @ {@code 27d6c6f}.
     */
    private static final String ADMIN_MENU_TITLE = "Admin Menu";

    /**
     * The {@code SEC-USR-TYPE} value ({@code app/cpy/CSUSR01Y.cpy}) that selects
     * the administrator menu; every other value selects the main menu.
     */
    private static final String ADMIN_USER_TYPE = "A";

    /**
     * The ten main-menu options, in the exact order and with the exact names
     * defined by {@code app/cpy/COMEN02Y.cpy} @ {@code 27d6c6f}.
     */
    private static final List<MenuDto.MenuOption> MAIN_MENU_OPTIONS = List.of(
            new MenuDto.MenuOption("1", "Account View"),
            new MenuDto.MenuOption("2", "Account Update"),
            new MenuDto.MenuOption("3", "Credit Card List"),
            new MenuDto.MenuOption("4", "Credit Card View"),
            new MenuDto.MenuOption("5", "Credit Card Update"),
            new MenuDto.MenuOption("6", "Transaction List"),
            new MenuDto.MenuOption("7", "Transaction View"),
            new MenuDto.MenuOption("8", "Transaction Add"),
            new MenuDto.MenuOption("9", "Transaction Reports"),
            new MenuDto.MenuOption("10", "Bill Payment"));

    /**
     * The four admin-menu options, in the exact order and with the exact names
     * defined by {@code app/cpy/COADM02Y.cpy} @ {@code 27d6c6f}.
     */
    private static final List<MenuDto.MenuOption> ADMIN_MENU_OPTIONS = List.of(
            new MenuDto.MenuOption("1", "User List (Security)"),
            new MenuDto.MenuOption("2", "User Add (Security)"),
            new MenuDto.MenuOption("3", "User Update (Security)"),
            new MenuDto.MenuOption("4", "User Delete (Security)"));

    /**
     * Returns the regular-user main menu.
     *
     * <p>Equivalent to the screen rendered by {@code COMEN01C} for transaction
     * {@code CM00}: the {@code "Main Menu"} heading and the ten options from
     * {@code COMEN02Y}.</p>
     *
     * @return the main-menu response carrying the title and the ten main options
     */
    public MenuDto.MenuResponse getMainMenu() {
        return new MenuDto.MenuResponse(MAIN_MENU_TITLE, MAIN_MENU_OPTIONS);
    }

    /**
     * Returns the administrator menu.
     *
     * <p>Equivalent to the screen rendered by {@code COADM01C} for transaction
     * {@code CA00}: the {@code "Admin Menu"} heading and the four options from
     * {@code COADM02Y}.</p>
     *
     * @return the admin-menu response carrying the title and the four admin
     *         options
     */
    public MenuDto.MenuResponse getAdminMenu() {
        return new MenuDto.MenuResponse(ADMIN_MENU_TITLE, ADMIN_MENU_OPTIONS);
    }

    /**
     * Selects the menu appropriate to the supplied user type, mirroring the
     * legacy sign-on routing where administrators land on the admin menu and all
     * other users land on the main menu.
     *
     * @param userType the {@code SEC-USR-TYPE} value; {@code "A"} selects the
     *                 admin menu, and any other value (including {@code null})
     *                 selects the main menu
     * @return the admin menu when {@code userType} is {@code "A"}, otherwise the
     *         main menu
     */
    public MenuDto.MenuResponse getMenuForUserType(String userType) {
        return ADMIN_USER_TYPE.equals(userType) ? getAdminMenu() : getMainMenu();
    }
}
