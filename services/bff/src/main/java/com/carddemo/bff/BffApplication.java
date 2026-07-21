package com.carddemo.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * bff — CardDemo walking skeleton backend-for-frontend.
 *
 * <p>The single backend the React UI binds to (the UI binds ONLY to the BFF). It performs
 * aggregation only — NO domain logic and NO persistence. The Card Detail path is the one
 * LIVE tracer (UI -&gt; BFF -&gt; card-svc -&gt; Oracle FREEPDB1 seeded row); every other
 * aggregation returns a typed [DEFERRED] placeholder.</p>
 *
 * <p>This class lives at the package root {@code com.carddemo.bff} so the default Spring Boot
 * component scan covers the {@code web}, {@code aggregation}, and {@code config} subpackages
 * without an explicit {@code @ComponentScan}. No JPA/persistence annotations are present
 * because the BFF owns no database.</p>
 *
 * <p>Provenance: [SRC: COMEN02Y | COCRDSLC | CARDDAT | CARDDEMO.CSD].</p>
 */
@SpringBootApplication
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
