package com.carddemo.bff;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * bff — context-load / health smoke test.
 *
 * <p>Boots the full Spring context to verify the BFF wiring (the
 * "build-and-start green" gate). Runs OFFLINE with no external dependencies:
 * the BFF owns no persistence (no JPA/JDBC on the classpath, so no DataSource
 * autoconfiguration runs) and the downstream RestClient bean(s) open no
 * connections at context initialization — so the context loads green with no
 * Oracle, card-svc, or auth-svc running and no environment variables set.</p>
 *
 * <p>Provenance: [SRC: CARDDEMO.CSD (topology)].</p>
 */
@SpringBootTest
class BffApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: a failure to load the context fails this test.
    }
}
