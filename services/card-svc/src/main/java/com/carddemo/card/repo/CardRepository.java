package com.carddemo.card.repo;

import com.carddemo.card.domain.Card;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Card} entity &mdash; the live-tracer read
 * seam of the CardDemo walking skeleton ({@code card-svc}, Spring Boot 3.5 / Java 21).
 *
 * <p>Parameterized on the natural key {@code CARD_NUM} (the entity's {@code @Id String
 * cardNumber}), so the surrogate identity primary key {@code CARD_SK} stays OFF the
 * read path. The tracer uses the inherited {@code findById(String cardNumber)} &mdash;
 * which returns {@code Optional<Card>} and maps to {@code SELECT ... FROM CARD WHERE
 * CARD_NUM = ?} &mdash; so no custom query methods or {@code @Query} annotations are
 * needed.</p>
 *
 * <p>Spring Data auto-detects this {@link JpaRepository} sub-interface through the
 * default component scan rooted at {@code com.carddemo.card} (see {@code CardApplication}),
 * so neither a {@code @Repository} stereotype nor an explicit
 * {@code @EnableJpaRepositories} declaration is required. Entity-to-DTO conversion is the
 * responsibility of the sibling {@code service.CardService}; this interface references
 * ONLY the hand-written persistence entity {@code com.carddemo.card.domain.Card} and
 * never the OpenAPI-generated API DTO of the same simple name (produced by
 * openapi-generator from the frozen contract), keeping it independent of the generated
 * contract sources.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT] &mdash; app/csd/CARDDEMO.CSD
 * ({@code DEFINE TRANSACTION(CCDL) PROGRAM(COCRDSLC)} over {@code DEFINE FILE(CARDDAT)});
 * the legacy {@code 9100-GETCARD-BYACCTCARD} VSAM read keyed on the 16-character card
 * number (app/cbl/COCRDSLC.cbl) becomes this {@code findById} natural-key read.</p>
 */
public interface CardRepository extends JpaRepository<Card, String> {
}
