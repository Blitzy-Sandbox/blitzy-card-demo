package com.carddemo.card;

import com.carddemo.card.domain.Card;
import com.carddemo.card.repo.CardRepository;
import com.carddemo.card.service.CardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * card-svc &mdash; context-load, Card mapping, deferred-path, and health test suite.
 *
 * <p>This is the single test class of the {@code card-svc} bounded context of the CardDemo
 * walking skeleton (Spring Boot 3.5.16 / Java 21) &mdash; the ONE live-tracer service whose
 * {@code GET /cards/{cardNumber}} performs a REAL Oracle read of a single Flyway-seeded row
 * along the chain UI &rarr; BFF &rarr; card-svc &rarr; Oracle {@code FREEPDB1}.</p>
 *
 * <p><strong>Runs fully OFFLINE &mdash; with NO live Oracle.</strong> The production
 * {@code src/main/resources/application.yml} wires a real Oracle datasource and
 * {@code spring-boot-starter-data-jpa} is on the classpath, so {@code DataSourceAutoConfiguration}
 * would otherwise create a HikariCP {@code DataSource} that eagerly connects at startup and
 * fails offline. This suite therefore keeps the offline-isolation seam intact: it excludes the
 * datasource / Hibernate-JPA / JPA-repositories / Flyway auto-configurations for the test only
 * and replaces the JPA {@link CardRepository} with a Mockito bean ({@link MockitoBean}). No
 * database connection is ever attempted; no embedded database, Testcontainers, {@code @Sql},
 * or live datasource is introduced. The production {@code CardApplication} keeps its real
 * datasource at runtime.</p>
 *
 * <p><strong>What this suite proves (beyond the build-and-start-green context gate):</strong></p>
 * <ul>
 *   <li>{@link #contextLoads()} &mdash; the full Spring context assembles and boots green
 *       offline (the seams wire together).</li>
 *   <li>{@link #getCard_mapsEntityToFiveFieldDto_whenRowExists()} &mdash; the live tracer
 *       mapping: the persistence entity {@link Card} is converted to the OpenAPI-generated
 *       five-field API DTO {@code com.carddemo.card.model.Card} with the load-bearing
 *       conversions correct (account id {@code Long}&rarr;numeric {@code String}, the
 *       legacy-misspelled {@code getCardExpiraionDate()} &rarr; the correctly spelled
 *       {@code expiryDate}, and the {@code Y}/{@code N} flag &rarr; the generated
 *       {@code ActiveStatusEnum}).</li>
 *   <li>{@link #cardDto_exposesExactlyTheFiveReadFields_andNeverCvv()} &mdash; the read DTO
 *       carries exactly the five contract fields and NEVER the CVV
 *       ({@code CARD-CVV-CD} is intentionally excluded from the read path).</li>
 *   <li>{@link #getCard_mapsNullAccountIdAndStatus_withoutThrowing()} &mdash; the null-safe
 *       branches of the mapping (a {@code null} account id maps to {@code null}, never the
 *       literal {@code "null"}; a {@code null} flag maps to {@code null}, never an
 *       {@link IllegalArgumentException} from {@code ActiveStatusEnum.fromValue(...)}).</li>
 *   <li>{@link #getCard_returnsEmpty_whenCardNotFound()} &mdash; the missing-row path: an
 *       absent card yields {@link Optional#empty()} (the {@code 404} / UI empty state),
 *       mirroring legacy {@code DFHRESP(NOTFND)}.</li>
 *   <li>{@link #getCard_propagatesDataAccessException_onReadError()} &mdash; the read-error
 *       path: a data-access failure propagates unswallowed (the {@code 500} / UI error
 *       state), mirroring legacy {@code DFHRESP(OTHER)}.</li>
 *   <li>{@link #actuatorHealth_reportsUp_offline()} &mdash; the Actuator health endpoint is
 *       wired and reports {@code UP} offline (the docker-compose / CI container-gating probe).
 *       With the datasource excluded no {@code db} health contributor is registered, so the
 *       aggregate status is {@code UP} without a database.</li>
 * </ul>
 *
 * <p>The persistence entity {@link com.carddemo.card.domain.Card} is imported and referenced
 * unqualified; the OpenAPI-generated API DTO {@code com.carddemo.card.model.Card} (emitted at
 * build time under {@code target/generated-sources/openapi} from the frozen contract
 * {@code contracts/card-svc.openapi.yaml}, never hand-edited) is referenced by its fully
 * qualified name to disambiguate the identical simple name &mdash; matching the convention in
 * {@code com.carddemo.card.service.CardService}.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT] &mdash; the legacy Credit Card View transaction
 * (CCDL &rarr; COCRDSLC) reading the CARDDAT VSAM KSDS keyed on the 16-character card number;
 * card field shapes derive from copybook CVACT02Y.cpy (CARD-RECORD). The legacy reference is
 * topology / provenance only and does not dictate these assertions.</p>
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@AutoConfigureMockMvc
class CardApplicationTests {

    /** The seeded tracer card number (V2__seed_tracer.sql) &mdash; the natural read key. */
    private static final String SEED_CARD_NUMBER = "0500024453765740";

    /** The seeded owning account id (Oracle {@code NUMBER}); a {@code Long} on the entity. */
    private static final long SEED_ACCOUNT_ID = 50L;

    /** The seeded embossed cardholder name. */
    private static final String SEED_EMBOSSED_NAME = "Aniya Von";

    /** The seeded expiry date, already formatted {@code YYYY-MM-DD}. */
    private static final String SEED_EXPIRY_DATE = "2023-03-09";

    /** The seeded active-status flag ({@code Y}/{@code N}). */
    private static final String SEED_ACTIVE_STATUS = "Y";

    /** A card number known NOT to exist, used to drive the missing-row path. */
    private static final String ABSENT_CARD_NUMBER = "9999999999999999";

    /**
     * The JPA repository is replaced by a Mockito bean so the context loads offline (no live
     * Oracle) and each test can drive the read outcome deterministically. Spring resets this
     * mock between test methods, so stubs never leak across tests.
     */
    @MockitoBean
    CardRepository cardRepository;

    /**
     * The real, Spring-wired {@link CardService} under test. Autowiring the production bean
     * (rather than constructing it by hand) also proves the service is correctly wired by the
     * application context over the mocked repository.
     */
    @Autowired
    CardService cardService;

    /** Drives the Actuator health endpoint without opening a real network port. */
    @Autowired
    MockMvc mockMvc;

    /**
     * Builds a fully-populated {@code CARD}-table entity matching the single seeded tracer row.
     *
     * @return a new {@link Card} entity with all five read fields set to the seed values
     */
    private static Card seededCardEntity() {
        Card entity = new Card();
        entity.setCardNumber(SEED_CARD_NUMBER);
        entity.setAccountId(SEED_ACCOUNT_ID);
        entity.setEmbossedName(SEED_EMBOSSED_NAME);
        entity.setCardExpiraionDate(SEED_EXPIRY_DATE);
        entity.setActiveStatus(SEED_ACTIVE_STATUS);
        return entity;
    }

    /**
     * Build-and-start-green gate: passing means the {@code card-svc} Spring context assembled
     * and booted offline (no datasource / JPA / Flyway present; the repository is mocked).
     */
    @Test
    void contextLoads() {
        // Intentionally empty: a failure to load the context fails this test.
    }

    /**
     * Live-tracer mapping (happy path): when the seeded row exists, {@link CardService#getCard(String)}
     * returns a present DTO whose five fields are mapped exactly, including the account-id
     * {@code Long}&rarr;{@code String} conversion, the legacy-misspelled expiry getter mapped to
     * the correctly spelled {@code expiryDate}, and the {@code Y} flag mapped to the generated
     * {@code ActiveStatusEnum.Y}.
     */
    @Test
    void getCard_mapsEntityToFiveFieldDto_whenRowExists() {
        when(cardRepository.findById(SEED_CARD_NUMBER))
                .thenReturn(Optional.of(seededCardEntity()));

        Optional<com.carddemo.card.model.Card> result = cardService.getCard(SEED_CARD_NUMBER);

        assertThat(result).isPresent();
        com.carddemo.card.model.Card dto = result.get();
        assertThat(dto.getCardNumber()).isEqualTo(SEED_CARD_NUMBER);
        // CARD_ACCT_ID is a Long on the entity and a numeric String on the DTO.
        assertThat(dto.getAccountId()).isEqualTo("50");
        assertThat(dto.getEmbossedName()).isEqualTo(SEED_EMBOSSED_NAME);
        // Sourced from the legacy-misspelled getter getCardExpiraionDate() -> correctly spelled expiryDate.
        assertThat(dto.getExpiryDate()).isEqualTo(SEED_EXPIRY_DATE);
        // The "Y"/"N" String flag is converted to the generated nested enum.
        assertThat(dto.getActiveStatus())
                .isEqualTo(com.carddemo.card.model.Card.ActiveStatusEnum.Y);
    }

    /**
     * Exact five-field read shape: the generated read DTO exposes precisely the five contract
     * fields and NEVER a CVV field &mdash; {@code CARD-CVV-CD} is intentionally excluded from
     * the read path (the entity does not map it and the DTO does not declare it).
     */
    @Test
    void cardDto_exposesExactlyTheFiveReadFields_andNeverCvv() {
        List<String> fieldNames = Arrays.stream(com.carddemo.card.model.Card.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fieldNames)
                .contains("cardNumber", "accountId", "embossedName", "expiryDate", "activeStatus");
        assertThat(fieldNames)
                .as("read DTO must never expose the CVV")
                .noneMatch(name -> name.toLowerCase().contains("cvv"));
    }

    /**
     * Null-safe mapping branches: a {@code null} account id maps to {@code null} (never the
     * literal string {@code "null"}), and a {@code null} status flag maps to {@code null}
     * (never throwing {@link IllegalArgumentException} from {@code ActiveStatusEnum.fromValue}).
     */
    @Test
    void getCard_mapsNullAccountIdAndStatus_withoutThrowing() {
        Card entity = seededCardEntity();
        entity.setAccountId(null);
        entity.setActiveStatus(null);
        when(cardRepository.findById(SEED_CARD_NUMBER)).thenReturn(Optional.of(entity));

        Optional<com.carddemo.card.model.Card> result = cardService.getCard(SEED_CARD_NUMBER);

        assertThat(result).isPresent();
        com.carddemo.card.model.Card dto = result.get();
        assertThat(dto.getAccountId()).isNull();
        assertThat(dto.getActiveStatus()).isNull();
    }

    /**
     * Missing-row path (mirrors legacy {@code DFHRESP(NOTFND)}): when the repository finds no
     * row, {@link CardService#getCard(String)} returns {@link Optional#empty()} unchanged,
     * driving the {@code 404} / UI empty state.
     */
    @Test
    void getCard_returnsEmpty_whenCardNotFound() {
        when(cardRepository.findById(ABSENT_CARD_NUMBER)).thenReturn(Optional.empty());

        Optional<com.carddemo.card.model.Card> result = cardService.getCard(ABSENT_CARD_NUMBER);

        assertThat(result).isEmpty();
    }

    /**
     * Read-error path (mirrors legacy {@code DFHRESP(OTHER)}): a data-access failure is NOT
     * swallowed by the service &mdash; it propagates to the caller, driving the {@code 500} /
     * UI error state.
     */
    @Test
    void getCard_propagatesDataAccessException_onReadError() {
        when(cardRepository.findById(SEED_CARD_NUMBER))
                .thenThrow(new DataAccessResourceFailureException("simulated Oracle read failure"));

        assertThatThrownBy(() -> cardService.getCard(SEED_CARD_NUMBER))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * Health / container-gating probe: the Actuator health endpoint is wired and reports
     * {@code UP} offline. With the datasource auto-configuration excluded, no {@code db} health
     * contributor is registered, so the aggregate status is {@code UP} without any database.
     *
     * @throws Exception if the MockMvc request processing fails
     */
    @Test
    void actuatorHealth_reportsUp_offline() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
