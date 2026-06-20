package com.carddemo.model.dto;

/**
 * Immutable response payload for the card-update screen.
 *
 * <p>Serialized to JSON and returned by {@code CardController} from
 * {@code PUT /api/cards/{cardNumber}} once {@code CardUpdateService} (migrated
 * from COBOL program {@code COCRDUPC}) completes. It carries the
 * redisplay/confirmation view of the card together with informational and error
 * messaging, mirroring the output ({@code CCRDUPAO}) projection of the
 * {@code COCRDUP} BMS symbolic map.</p>
 *
 * <p>Field lineage (BMS map field &rarr; record component): {@code ACCTSID} &rarr;
 * {@code accountId}, {@code CARDSID} &rarr; {@code cardNumber}, {@code CRDNAME}
 * &rarr; {@code nameOnCard}, {@code CRDSTCD} &rarr; {@code cardStatus},
 * {@code EXPMON} &rarr; {@code expirationMonth}, {@code EXPYEAR} &rarr;
 * {@code expirationYear}, {@code EXPDAY} &rarr; {@code expirationDay},
 * {@code INFOMSG} &rarr; {@code infoMessage}, {@code ERRMSG} &rarr;
 * {@code errorMessage}. Screen chrome and PF-key legend fields from the map are
 * intentionally excluded. Every display field is rendered as text to preserve
 * the fixed-width character semantics of the originating {@code PIC X} clauses.</p>
 *
 * <p>The {@code version} component echoes the persisted JPA {@code @Version} of
 * the {@code Card} entity so the client can supply it on the next
 * {@link CardUpdateRequest}, enabling stateless optimistic-locking
 * (AAP &sect;0.8.4); it is the only non-text component and has no BMS-map
 * equivalent.</p>
 *
 * <p>Source lineage (reference only, COBOL not copied): AWS CardDemo commit
 * {@code 27d6c6f}.</p>
 *
 * @param accountId       account identifier (BMS {@code ACCTSID}, {@code PIC X(11)})
 * @param cardNumber      card number (BMS {@code CARDSID}, {@code PIC X(16)})
 * @param nameOnCard      embossed cardholder name (BMS {@code CRDNAME}, {@code PIC X(50)})
 * @param cardStatus      card status code (BMS {@code CRDSTCD}, {@code PIC X(1)})
 * @param expirationMonth expiration month (BMS {@code EXPMON}, {@code PIC X(2)})
 * @param expirationYear  expiration year (BMS {@code EXPYEAR}, {@code PIC X(4)})
 * @param expirationDay   expiration day (BMS {@code EXPDAY}, {@code PIC X(2)})
 * @param version         optimistic-locking token (JPA {@code @Version} of the
 *                        {@code Card} entity) echoed for the next
 *                        {@link CardUpdateRequest}; no BMS equivalent
 * @param infoMessage     informational message (BMS {@code INFOMSG}, {@code PIC X(40)})
 * @param errorMessage    error message (BMS {@code ERRMSG}, {@code PIC X(80)})
 */
public record CardUpdateResponse(
        String accountId,
        String cardNumber,
        String nameOnCard,
        String cardStatus,
        String expirationMonth,
        String expirationYear,
        String expirationDay,
        Long version,
        String infoMessage,
        String errorMessage) {
}
