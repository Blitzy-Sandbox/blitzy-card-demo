package com.vsergeychik.carddemo.testsupport;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A one-route stand-in for a screen controller, used by {@code WebConfigErrorContractTest}.
 *
 * <p>It exists only so a request body can be bound by the real message converter and the resulting
 * failure can be seen to reach the real {@code CobolErrorHandler} advice - a malformed document, an
 * unknown property, a type mismatch, and one well-formed body that must still bind.
 *
 * <p>It sits in this package, and not nested in the suite that drives it, for the reason this
 * package's documentation states in full: {@code @RestController} is what
 * {@code MockMvcBuilders.standaloneSetup} needs to register a handler method and to write a
 * {@code String} return as a body, and it is also what makes a class a component-scan candidate. In
 * {@code com.vsergeychik.carddemo.config} - a scanned package - that made this fixture one scan
 * configuration away from joining the seventeen controllers the deployed artifact publishes, which is
 * exactly what happened to a sibling fixture. Here it cannot.
 */
@RestController
public final class ScreenBindingFixtureController {

    /** The single path this fixture serves. */
    public static final String PATH = "/screen";

    /**
     * Accepts a screen payload as JSON, echoing the transaction name when binding succeeds.
     *
     * @param screen the bound payload
     * @return the payload's transaction name
     */
    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public String accept(@RequestBody final Screen screen) {
        return screen.trnName();
    }

    /**
     * The payload of {@link #accept(Screen)}, deliberately holding a numeric member so a type
     * mismatch can be provoked.
     *
     * @param trnName a {@code PIC X(4)} character field
     * @param pageNum a {@code PIC 9(8)} numeric field
     */
    public record Screen(String trnName, int pageNum) {
    }
}
