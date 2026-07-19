package com.carddemo.bff.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.MenuAggregator;
import com.carddemo.bff.api.MenuApi;
import com.carddemo.bff.model.MenuResponse;

/**
 * Main-menu navigation controller &mdash; the BFF HTTP edge for the CardDemo
 * main menu consumed by the React MUI navigation {@code Drawer}.
 *
 * <p>This class implements the contract-first, generated {@link MenuApi}
 * interface ({@code GET /api/menu}); the request mapping and the
 * {@code X-Correlation-ID} header binding are inherited verbatim from that
 * generated interface, so this controller declares <em>no</em> Spring MVC
 * mapping annotations of its own and serves the {@code /api} path exactly as the
 * frozen OpenAPI 3.1 contract specifies.</p>
 *
 * <p>It is a deliberately <strong>thin HTTP delegate</strong>: the 10-option
 * navigation model is assembled entirely by {@link MenuAggregator} (option 4,
 * "Credit Card View", routes to the single live Card Detail tracer slice). This
 * controller performs no domain logic, no persistence, and no downstream service
 * call &mdash; it simply forwards to the aggregator and wraps the result in a
 * {@code 200 OK} {@link ResponseEntity}. The UI binds only to the BFF, never to a
 * domain service directly.</p>
 *
 * <p>Provenance: {@code [SRC: COMEN02Y | navigation]} &mdash; the legacy CardDemo
 * main-menu copybook {@code app/cpy/COMEN02Y.cpy} (10 options) iterated by
 * program {@code app/cbl/COMEN01C.cbl}.</p>
 */
@RestController
public class MenuController implements MenuApi {

    /**
     * Static, typed aggregator that builds the authoritative 10-option main-menu
     * navigation model. Injected via the constructor and never reassigned.
     */
    private final MenuAggregator menuAggregator;

    /**
     * Create the controller with its single collaborator.
     *
     * <p>Uses constructor injection (the Spring-recommended style for mandatory
     * dependencies); no {@code @Autowired} annotation is required because the
     * class has exactly one constructor.</p>
     *
     * @param menuAggregator the aggregator that produces the main-menu model;
     *                       must not be {@code null}
     */
    public MenuController(MenuAggregator menuAggregator) {
        this.menuAggregator = menuAggregator;
    }

    /**
     * Handle {@code GET /api/menu} by returning the CardDemo main-menu navigation
     * options that drive the MUI navigation {@code Drawer}.
     *
     * <p>The correlation-ID header is accepted to honor the generated contract
     * signature but is intentionally not read here: correlation propagation is a
     * cross-cutting concern handled by the servlet {@code CorrelationIdFilter}
     * (SLF4J MDC), so this handler declares the parameter and ignores it.</p>
     *
     * @param xCorrelationID optional {@code X-Correlation-ID} request header
     *                       (declared to match the generated contract; ignored
     *                       by this handler)
     * @return {@code 200 OK} wrapping the 10-option {@link MenuResponse} built by
     *         the {@link MenuAggregator}
     */
    @Override
    public ResponseEntity<MenuResponse> getMenu(UUID xCorrelationID) {
        return ResponseEntity.ok(menuAggregator.getMenu());
    }
}
