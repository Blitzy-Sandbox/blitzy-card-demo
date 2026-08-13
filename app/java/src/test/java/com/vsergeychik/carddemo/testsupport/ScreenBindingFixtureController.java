package com.vsergeychik.carddemo.testsupport;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A one-route stand-in for a screen controller, used by {@code WebConfigErrorContractTest}.
 */
@RestController
public final class ScreenBindingFixtureController {
    public static final String PATH = "/screen";

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public String accept(@RequestBody final Screen screen) {
        return screen.trnName();
    }

    public record Screen(String trnName, int pageNum) {
    }
}
