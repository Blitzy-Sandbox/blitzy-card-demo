package com.carddemo.useradmin.web;

import com.carddemo.useradmin.api.AdminApi;
import com.carddemo.useradmin.model.AdminMenuResponse;
import com.carddemo.useradmin.model.MenuOption;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-menu REST controller for {@code useradmin-svc} — static menu metadata, NOT business logic.
 *
 * <p>This controller implements the OpenAPI-generated {@link AdminApi} interface and serves the
 * CardDemo Admin Menu: the four administration/security options a signed-on admin user may launch.
 * The payload is <strong>real, static, verbatim</strong> menu content copied from the legacy
 * copybook — for the walking skeleton this is the correct outcome (a faithful stub), not an
 * invented implementation. There is deliberately <em>no</em> persistence and <em>no</em>
 * service/repository layer: the admin menu is a fixed compile-time constant on the mainframe and
 * likewise here.</p>
 *
 * <h2>Contract-first</h2>
 * <p>All Spring MVC routing ({@code GET /admin/menu}, the {@code X-Correlation-ID} header binding,
 * the produced media types, {@code @Validated}) is declared on the generated {@link AdminApi}
 * interface, which is emitted at build time by {@code openapi-generator-maven-plugin} from the
 * frozen SSoT {@code contracts/useradmin-svc.openapi.yaml} (options {@code generatorName=spring},
 * {@code interfaceOnly=true}, {@code useTags=true}). This class therefore carries only
 * {@link RestController @RestController} plus a single {@code @Override}; it adds no mapping,
 * header, or validation annotations of its own, and it injects no beans.</p>
 *
 * <h2>Menu content</h2>
 * <p>The four options are copied verbatim from {@code app/cpy/COADM02Y.cpy}
 * ({@code CARDDEMO-ADMIN-MENU-OPTIONS}, {@code CDEMO-ADMIN-OPT-COUNT = 4}); the legacy table is
 * declared {@code OCCURS 9 TIMES} but only four entries are populated, so exactly four are emitted
 * here. The human-readable {@code label} values are trimmed of the copybook's trailing
 * space-padding (each stays within the contract's {@code maxLength: 35}), and the eight-character
 * {@code targetProgram} identifiers ({@code COUSR00C}..{@code COUSR03C}) are preserved exactly
 * (within the contract's {@code maxLength: 8}).</p>
 *
 * <p>Health is served by Spring Boot Actuator at {@code /actuator/health}; the generated
 * {@code HealthApi} interface is intentionally left unimplemented, so this controller implements
 * {@link AdminApi} only.</p>
 *
 * <p>Provenance: {@code [SRC: COADM01C | COADM02Y]} — the legacy CICS Admin-Menu transaction
 * {@code CA00 -> COADM01C} (registered in {@code app/csd/CARDDEMO.CSD}), which
 * {@code COPY COADM02Y} and drives {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)} up to
 * {@code CDEMO-ADMIN-OPT-COUNT}.</p>
 */
@RestController
public class AdminController implements AdminApi {

    /**
     * Return the four static CardDemo admin-menu options.
     *
     * <p>Implements {@link AdminApi#getAdminMenu(UUID)}. Always responds {@code 200 OK} with the
     * fixed four-option {@link AdminMenuResponse}; the response is deterministic and independent of
     * any request state. The options are ordered by their 1-based {@code optionNumber}, mirroring
     * the legacy {@code CDEMO-ADMIN-OPT} table order.</p>
     *
     * <p>The {@code xCorrelationID} parameter is present solely to satisfy the generated interface
     * signature. Correlation-ID propagation into the SLF4J MDC is handled centrally by the sibling
     * {@code config.CorrelationIdFilter} ({@code OncePerRequestFilter}), so this method neither
     * reads nor mutates it.</p>
     *
     * @param xCorrelationID the optional {@code X-Correlation-ID} request header (may be
     *                       {@code null}); handled by the correlation filter, unused here.
     * @return {@code 200 OK} with the four admin-menu options in order.
     */
    @Override
    public ResponseEntity<AdminMenuResponse> getAdminMenu(UUID xCorrelationID) {
        // Verbatim from app/cpy/COADM02Y.cpy (CARDDEMO-ADMIN-MENU-OPTIONS, count = 4).
        // Labels trimmed of PIC X(35) padding; program names are exact PIC X(08) identifiers.
        AdminMenuResponse body = new AdminMenuResponse().options(List.of(
                new MenuOption().optionNumber(1).label("User List (Security)").targetProgram("COUSR00C"),
                new MenuOption().optionNumber(2).label("User Add (Security)").targetProgram("COUSR01C"),
                new MenuOption().optionNumber(3).label("User Update (Security)").targetProgram("COUSR02C"),
                new MenuOption().optionNumber(4).label("User Delete (Security)").targetProgram("COUSR03C")
        ));
        return ResponseEntity.ok(body);
    }
}
