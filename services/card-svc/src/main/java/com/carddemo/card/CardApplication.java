package com.carddemo.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * card-svc &mdash; CardDemo walking skeleton.
 *
 * <p>THE ONE LIVE TRACER service. The endpoint {@code GET /cards/{cardNumber}}
 * performs a REAL Oracle read of the single seeded row along the chain
 * UI -&gt; BFF -&gt; card-svc -&gt; Oracle FREEPDB1. Every other endpoint is a typed stub
 * ([DEFERRED]).</p>
 *
 * <p>This class lives at the package root {@code com.carddemo.card} so the default
 * Spring Boot component scan and JPA auto-configuration cover the web/service/repo/
 * domain/config subpackages without explicit {@code @EntityScan} /
 * {@code @EnableJpaRepositories} declarations.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT] &mdash; app/csd/CARDDEMO.CSD
 * (transaction CCDL -&gt; program COCRDSLC "VIEW CARD DETAIL" over file CARDDAT).</p>
 */
@SpringBootApplication
public class CardApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardApplication.class, args);
    }
}
