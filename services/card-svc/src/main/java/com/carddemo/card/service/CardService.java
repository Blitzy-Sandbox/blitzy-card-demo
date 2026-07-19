package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.repo.CardRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service layer for the ONE live tracer read of {@code card-svc} &mdash; the single
 * fully-wired vertical slice of the CardDemo walking skeleton (Spring Boot 3.5 / Java 21,
 * Oracle 23ai). This is the pivot of the whole tracer: the one place a REAL database read
 * is turned into the frozen-contract API DTO.
 *
 * <p><strong>Runtime chain.</strong> UI &rarr; BFF &rarr; {@code card-svc}
 * {@code GET /cards/{cardNumber}} &rarr; {@link #getCard(String)} &rarr;
 * {@link CardRepository#findById(Object)} &rarr;
 * {@code SELECT ... FROM CARD WHERE CARD_NUM = ?} against a single Flyway-seeded row in
 * PDB {@code FREEPDB1} &rarr; entity-to-DTO mapping &rarr; a rendered React Card Detail
 * screen (with real loading / empty / error states).</p>
 *
 * <p><strong>Legacy semantics (mirrors {@code 9100-GETCARD-BYACCTCARD} in
 * app/cbl/COCRDSLC.cbl).</strong> The legacy program issues
 * {@code EXEC CICS READ FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)} keyed on the
 * 16-character card number and evaluates the response:</p>
 * <ul>
 *   <li>{@code DFHRESP(NORMAL)} &rarr; row found &rarr; {@code findById} returns a present
 *       {@link Optional} &rarr; {@code .map(this::toDto)} yields a present DTO
 *       (controller {@code 200}).</li>
 *   <li>{@code DFHRESP(NOTFND)} &rarr; row absent ("Did not find this account in cards
 *       database") &rarr; {@code findById} returns {@link Optional#empty()}, propagated
 *       unchanged (controller {@code 404} &mdash; the UI empty state).</li>
 *   <li>{@code DFHRESP(OTHER)} &rarr; read error ("Error reading Card Data File") &rarr; a
 *       data-access exception is allowed to propagate untouched (controller/advice
 *       {@code 500} &mdash; the UI error state).</li>
 * </ul>
 *
 * <p><strong>Natural-key read.</strong> The read is keyed on the natural 16-character
 * card number only ({@code CardRepository.findById(cardNumber)}); the surrogate identity
 * primary key {@code CARD_SK} is deliberately kept OFF the read path, matching the legacy
 * VSAM read.</p>
 *
 * <p><strong>Type mapping.</strong> The persistence entity
 * {@link com.carddemo.card.domain.Card} (imported and referenced unqualified) is mapped to
 * the OpenAPI-generated API DTO {@code com.carddemo.card.model.Card} (referenced by fully
 * qualified name to disambiguate the identical simple name). The DTO is generated at build
 * time by {@code openapi-generator-maven-plugin} from the frozen contract
 * {@code contracts/card-svc.openapi.yaml} and is never hand-edited. The entity is never
 * exposed outside this service; conversion is always entity &rarr; DTO.</p>
 *
 * <p>Field shapes derive from copybook app/cpy/CVACT02Y.cpy ({@code CARD-RECORD}).
 * Provenance: [SRC: COCRDSLC | CARDDAT].</p>
 */
@Service
public class CardService {

    /**
     * Spring Data JPA repository providing the natural-key read of the {@code CARD} table.
     * Injected by constructor and held {@code final} for immutability and thread safety.
     */
    private final CardRepository cardRepository;

    /**
     * Constructor injection of the card repository. As the sole constructor, Spring
     * autowires it automatically without an explicit {@code @Autowired} annotation.
     *
     * @param cardRepository the Spring Data JPA repository for the {@code CARD} table
     */
    public CardService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Live tracer read: fetch a single card by its natural key ({@code CARD_NUM}) and map
     * it to the generated API DTO.
     *
     * <p>Performs a real Oracle read via {@link CardRepository#findById(Object)} keyed on
     * the 16-digit card number. When the row exists the mapped DTO is returned; when it is
     * absent {@link Optional#empty()} is returned unchanged (driving the {@code 404} / UI
     * empty state). Data-access exceptions are intentionally NOT caught here &mdash; they
     * propagate to the web layer (driving the {@code 500} / UI error state).</p>
     *
     * @param cardNumber the natural 16-digit card number to look up
     * @return the mapped DTO wrapped in an {@link Optional} if the row exists, otherwise
     *         {@link Optional#empty()}
     */
    public Optional<com.carddemo.card.model.Card> getCard(String cardNumber) {
        return cardRepository.findById(cardNumber).map(this::toDto);
    }

    /**
     * Maps the JPA persistence entity to the generated 5-field API DTO. The CVV
     * ({@code CARD-CVV-CD}) is intentionally excluded from the read DTO.
     *
     * <p>Load-bearing mapping details:</p>
     * <ul>
     *   <li>{@code accountId}: the entity's {@link Long} ({@code CARD_ACCT_ID}) becomes the
     *       DTO's numeric {@code String}, null-safely (a {@code null} account id maps to
     *       {@code null}, never the literal {@code "null"}).</li>
     *   <li>{@code expiryDate}: sourced from the entity getter
     *       {@link Card#getCardExpiraionDate()}, whose name preserves the legacy
     *       {@code EXPIRAION} misspelling, and mapped into the correctly-spelled DTO field
     *       {@code expiryDate} (already formatted {@code YYYY-MM-DD}).</li>
     *   <li>{@code activeStatus}: the {@code "Y"}/{@code "N"} {@link String} flag is
     *       converted to the generated nested enum via
     *       {@code ActiveStatusEnum.fromValue(...)}, null-safely (a {@code null} flag maps
     *       to {@code null} rather than throwing {@link IllegalArgumentException}).</li>
     * </ul>
     *
     * @param entity the {@code CARD}-table entity read from Oracle (never {@code null})
     * @return the populated generated API DTO
     */
    private com.carddemo.card.model.Card toDto(Card entity) {
        com.carddemo.card.model.Card dto = new com.carddemo.card.model.Card();
        dto.setCardNumber(entity.getCardNumber());
        dto.setAccountId(entity.getAccountId() == null
                ? null
                : String.valueOf(entity.getAccountId()));
        dto.setEmbossedName(entity.getEmbossedName());
        dto.setExpiryDate(entity.getCardExpiraionDate());
        dto.setActiveStatus(entity.getActiveStatus() == null
                ? null
                : com.carddemo.card.model.Card.ActiveStatusEnum.fromValue(entity.getActiveStatus()));
        return dto;
    }
}
