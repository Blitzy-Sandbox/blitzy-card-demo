package com.carddemo.bff.aggregation;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.MenuOption;
import com.carddemo.bff.model.MenuResponse;

/**
 * Main-menu navigation aggregator (static, typed).
 *
 * <p>Returns the 10-option CardDemo main menu that drives the React MUI
 * navigation {@code Drawer}. This is a <em>static, non-downstream</em>
 * aggregation: the BFF composes the navigation model from its own knowledge
 * because there is no menu domain service to call. No persistence, no
 * downstream service call, and no business logic are performed here &mdash; the
 * navigation data is nonetheless real and authoritative, so the menu works.</p>
 *
 * <p>The option set is derived one-to-one from the legacy CardDemo main-menu
 * copybook &mdash; 10 options, every entry {@code CDEMO-MENU-OPT-USRTYPE = 'U'}
 * (regular user), iterated by program {@code COMEN01C}. Provenance:
 * {@code [SRC: COMEN02Y]} (app/cpy/COMEN02Y.cpy L19-L84). Because every legacy
 * option is a regular-user option, no {@code A} (admin-only) or {@code ALL}
 * user types are ever emitted.</p>
 *
 * <p>Option 4, "Credit Card View", is the single live vertical tracer slice: its
 * {@code route} targets the one seeded card ({@code 0500024453765740}) so that
 * selecting it navigates to the fully-wired Card Detail screen
 * (UI &rarr; BFF &rarr; card-svc &rarr; Oracle {@code FREEPDB1} seeded row). The
 * {@code route} values are UI client-side paths and are kept coherent with the
 * frozen {@code contracts/bff.openapi.yaml} {@code MenuResponse} example and the
 * committed UI navigation ({@code ui/app/layout/NavMenu.tsx},
 * {@code ui/features/menu/MainMenu.tsx}), so the BFF menu and the client router
 * agree on every path.</p>
 *
 * <p>The {@link MenuResponse} produced here is returned directly to the
 * {@code web} controller that implements the generated {@code MenuApi}
 * ({@code GET /api/menu}); that controller wraps the response in
 * {@code ResponseEntity.ok(...)}.</p>
 */
@Service
public class MenuAggregator {

    /**
     * The seeded credit-card number backing the live Card Detail tracer slice
     * (see {@code db/migration/V2__seed_tracer.sql}). Options 4 ("Credit Card
     * View") and 5 ("Credit Card Update") reference this value so their routes
     * resolve to the single seeded row that exists in the walking skeleton.
     */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /**
     * Build the CardDemo main-menu navigation model.
     *
     * <p>Returns exactly the 10 options defined by the legacy main menu, in
     * their original order, each visible to regular users
     * ({@code userType = U}). The result is a fully-populated model assembled
     * in memory; no external call is made.</p>
     *
     * @return the 10-option {@link MenuResponse} that drives the UI navigation
     */
    public MenuResponse getMenu() {
        List<MenuOption> options = List.of(
                option(1, "Account View", "/accounts/50"),
                option(2, "Account Update", "/accounts/50/edit"),
                option(3, "Credit Card List", "/cards"),
                option(4, "Credit Card View", "/cards/" + SEEDED_CARD_NUMBER),
                option(5, "Credit Card Update", "/cards/" + SEEDED_CARD_NUMBER + "/edit"),
                option(6, "Transaction List", "/transactions"),
                option(7, "Transaction View", "/transactions/1"),
                option(8, "Transaction Add", "/transactions/new"),
                option(9, "Transaction Reports", "/reports"),
                option(10, "Bill Payment", "/bill-payment"));

        MenuResponse response = new MenuResponse();
        response.setOptions(options);
        return response;
    }

    /**
     * Construct a single regular-user {@link MenuOption}.
     *
     * <p>Every legacy option carries {@code CDEMO-MENU-OPT-USRTYPE = 'U'}, so
     * the user type is fixed to {@code MenuOption.UserTypeEnum.U} for all rows;
     * {@code A} (admin-only) and {@code ALL} assignments are intentionally never
     * produced, faithful to {@code COMEN02Y}.</p>
     *
     * @param number the 1-based option number (CDEMO-MENU-OPT-NUM)
     * @param label  the human-readable menu label (CDEMO-MENU-OPT-NAME, &le; 35 chars)
     * @param route  the UI client-side route path this option navigates to
     * @return a fully-populated {@link MenuOption}
     */
    private MenuOption option(int number, String label, String route) {
        MenuOption opt = new MenuOption();
        opt.setOptionNumber(number);
        opt.setLabel(label);
        opt.setRoute(route);
        opt.setUserType(MenuOption.UserTypeEnum.U);
        return opt;
    }
}
