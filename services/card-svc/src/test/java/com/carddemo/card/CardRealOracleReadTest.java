package com.carddemo.card;

import com.carddemo.card.domain.Card;
import com.carddemo.card.repo.CardRepository;
import com.carddemo.card.service.CardService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * card-svc &mdash; <strong>real-Oracle</strong> integration test for THE ONE LIVE TRACER
 * read path (CardDemo walking skeleton, Spring Boot 3.5.16 / Java 21, Oracle 23ai).
 *
 * <p><strong>Why this class exists (QA finding F-1).</strong> The sibling
 * {@link CardApplicationTests} deliberately runs OFFLINE &mdash; it mocks
 * {@link CardRepository} and excludes the datasource / Hibernate-JPA / JPA-repositories /
 * Flyway auto-configurations &mdash; so it proves the entity&rarr;DTO <em>mapping logic</em>
 * but never exercises a real database. That leaves the single most important path of the
 * whole skeleton &mdash; the live tracer's REAL JPA/Hibernate/Oracle read of the seeded
 * {@code CARD} row &mdash; without durable committed or CI-enforced automated coverage: a
 * regression to the entity's column mapping (for example a wrong {@code @Column(name=...)},
 * the deliberately-misspelled {@code CARD_EXPIRAION_DATE}, or the {@code accountId} type)
 * would pass the offline suite AND CI and escape to runtime. This test closes that gap.</p>
 *
 * <p><strong>What it does.</strong> It stands up an <em>ephemeral, real</em> Oracle Database
 * Free 23ai using Testcontainers on the SAME pinned image the deployment unit and CI use
 * ({@code gvenzl/oracle-free:23.26.2-slim}), points the Spring datasource at it via
 * {@link DynamicPropertySource}, and lets Flyway apply the platform-owned migrations
 * {@code db/migration/V1__baseline.sql} (schema) and {@code V2__seed_tracer.sql} (the single
 * seeded card + account). It then asserts the tracer read end-to-end over the real database
 * at three layers &mdash; repository, service DTO mapping, and the HTTP endpoint &mdash; plus
 * the empty/{@code 404} path for an unknown card.</p>
 *
 * <p><strong>Runtime chain proven.</strong> {@code GET /cards/{cardNumber}} &rarr;
 * {@code CardController} &rarr; {@link CardService#getCard(String)} &rarr;
 * {@link CardRepository#findById(Object)} &rarr;
 * {@code SELECT ... FROM CARD WHERE CARD_NUM = ?} against the seeded row in a real Oracle
 * PDB {@code FREEPDB1} &rarr; typed five-field {@code Card} payload. This is exactly the
 * hop the offline suite cannot reach.</p>
 *
 * <p><strong>Build behaviour.</strong> The class is a standard surefire test (name ends in
 * {@code Test}) so it runs in the {@code test} phase &mdash; hence it is exercised by the
 * existing CI backend build ({@code mvn -f pom.xml package}) with no workflow change needed.
 * It is annotated {@link Testcontainers @Testcontainers(disabledWithoutDocker = true)}: where
 * Docker is available (CI runners, local dev with Docker) it RUNS against real Oracle; where
 * Docker is absent it is gracefully SKIPPED, so no build turns red for lack of a Docker
 * daemon. The service Dockerfile builds with {@code -DskipTests}, so this test never runs
 * inside {@code docker compose build}.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT] &mdash; app/cbl/COCRDSLC.cbl (transaction CCDL)
 * reading the CARDDAT VSAM KSDS keyed on the 16-character card number; card field shapes
 * derive from copybook CVACT02Y.cpy (CARD-RECORD). The legacy reference is topology /
 * provenance only and does not dictate these assertions.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardRealOracleReadTest {

    /**
     * The pinned Oracle Database Free 23ai image &mdash; byte-for-byte the tag used by the
     * docker-compose deployment unit and CI ({@code gvenzl/oracle-free:23.26.2-slim}). Pinned
     * explicitly (never {@code latest} / a moving major tag) per the AAP.
     */
    private static final DockerImageName ORACLE_IMAGE =
            DockerImageName.parse("gvenzl/oracle-free:23.26.2-slim");

    /** The seeded tracer card number (V2__seed_tracer.sql) &mdash; the natural read key. */
    private static final String SEED_CARD_NUMBER = "0500024453765740";

    /** The seeded owning account id (Oracle {@code NUMBER}); a {@code Long} on the entity. */
    private static final long SEED_ACCOUNT_ID = 50L;

    /** The seeded owning account id rendered as the DTO's numeric {@code String}. */
    private static final String SEED_ACCOUNT_ID_STRING = "50";

    /** The seeded embossed cardholder name. */
    private static final String SEED_EMBOSSED_NAME = "Aniya Von";

    /** The seeded expiry date, already formatted {@code YYYY-MM-DD}. */
    private static final String SEED_EXPIRY_DATE = "2023-03-09";

    /** The seeded active-status flag ({@code Y}/{@code N}). */
    private static final String SEED_ACTIVE_STATUS = "Y";

    /** A card number known NOT to exist, used to drive the empty / {@code 404} path. */
    private static final String ABSENT_CARD_NUMBER = "9999999999999999";

    /**
     * The ephemeral real Oracle. {@code static} + {@link Container @Container} means the JUnit 5
     * Testcontainers extension starts it ONCE before the Spring context (so
     * {@link #datasourceProperties(DynamicPropertyRegistry)} can read its JDBC coordinates) and
     * stops it after the class. A generous startup timeout accommodates Oracle's first-boot.
     */
    @Container
    static final OracleContainer ORACLE =
            new OracleContainer(ORACLE_IMAGE).withStartupTimeout(Duration.ofMinutes(5));

    /**
     * Wires the Spring datasource to the ephemeral container and turns Flyway ON for this test
     * so the platform migrations create the schema and seed the single tracer row.
     *
     * <p>Dynamic properties take precedence over {@code application.yml} AND over the CI
     * {@code SPRING_DATASOURCE_*} / {@code SPRING_FLYWAY_ENABLED=false} environment, so this
     * test is self-contained regardless of the surrounding environment. {@code spring.flyway.
     * locations} points at the platform-owned {@code db/migration} directory on the filesystem
     * (the single source of truth &mdash; the SQL is never copied into this module), resolved
     * robustly relative to the module working directory.</p>
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", CardRealOracleReadTest::resolveMigrationLocations);
        // Flyway owns the schema; Hibernate must never create or validate it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /**
     * Robustly resolves the Flyway location for the platform-owned {@code db/migration} folder.
     *
     * <p>Walks up from the JVM working directory (the card-svc module basedir under surefire,
     * whether the reactor or a standalone module build launched it) until it finds a directory
     * containing {@code db/migration}, then returns it as a Flyway {@code filesystem:} location.
     * Keeping the SQL as the single source of truth in {@code /db/migration} &mdash; rather than
     * duplicating it into this module's test resources &mdash; guarantees this test exercises the
     * exact migrations the deployment unit and CI apply.</p>
     *
     * @return a Flyway {@code filesystem:} location pointing at the repository {@code db/migration}
     * @throws IllegalStateException if the {@code db/migration} directory cannot be located
     */
    private static String resolveMigrationLocations() {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path dir = start;
        for (int depth = 0; depth < 8 && dir != null; depth++) {
            Path candidate = dir.resolve("db").resolve("migration");
            if (Files.isDirectory(candidate)) {
                return "filesystem:" + candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate the platform db/migration directory starting from " + start);
    }

    /** The real, Spring Data JPA repository bound to the live Oracle datasource. */
    @Autowired
    CardRepository cardRepository;

    /** The real, Spring-wired service performing the entity&rarr;DTO mapping over the real read. */
    @Autowired
    CardService cardService;

    /** HTTP client bound to the running server's random port (auto-configured for RANDOM_PORT). */
    @Autowired
    TestRestTemplate restTemplate;

    /** The random port the embedded server bound to, used to build absolute request URLs. */
    @LocalServerPort
    int port;

    /**
     * Repository layer (the load-bearing regression guard): reading the seeded row through the
     * REAL JPA/Hibernate/Oracle path returns an entity whose five mapped columns match the seed
     * EXACTLY. This is precisely what the offline mocked suite cannot assert &mdash; a wrong
     * {@code @Column(name=...)} (including the deliberately-misspelled {@code CARD_EXPIRAION_DATE})
     * or a wrong {@code accountId} type would fail here against a real Oracle schema.
     */
    @Test
    @DisplayName("repository.findById reads the seeded CARD row from real Oracle with exact field mapping")
    void repositoryReadsSeededRowWithExactFieldMapping() {
        Optional<Card> found = cardRepository.findById(SEED_CARD_NUMBER);

        assertThat(found).as("seeded card must be found by its natural key in real Oracle").isPresent();
        Card card = found.get();
        assertThat(card.getCardNumber()).isEqualTo(SEED_CARD_NUMBER);
        assertThat(card.getAccountId()).isEqualTo(SEED_ACCOUNT_ID);
        assertThat(card.getEmbossedName()).isEqualTo(SEED_EMBOSSED_NAME);
        // getCardExpiraionDate() preserves the legacy EXPIRAION misspelling; mapped to CARD_EXPIRAION_DATE.
        assertThat(card.getCardExpiraionDate()).isEqualTo(SEED_EXPIRY_DATE);
        assertThat(card.getActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);
    }

    /**
     * Repository layer, empty path (mirrors legacy {@code DFHRESP(NOTFND)}): an unknown card
     * number yields {@link Optional#empty()} from the real database read.
     */
    @Test
    @DisplayName("repository.findById returns empty for an unknown card from real Oracle")
    void repositoryReturnsEmptyForUnknownCard() {
        assertThat(cardRepository.findById(ABSENT_CARD_NUMBER)).isEmpty();
    }

    /**
     * Service layer over the real read: {@link CardService#getCard(String)} maps the seeded
     * entity to the OpenAPI-generated five-field API DTO with the load-bearing conversions correct
     * &mdash; account id {@code Long}&rarr;numeric {@code String}, the legacy-misspelled expiry
     * getter mapped to the correctly-spelled {@code expiryDate}, and the {@code Y} flag mapped to
     * the generated {@code ActiveStatusEnum.Y} &mdash; all sourced from a real Oracle row.
     */
    @Test
    @DisplayName("service.getCard maps the real seeded row to the five-field DTO")
    void serviceMapsSeededRowToFiveFieldDto() {
        Optional<com.carddemo.card.model.Card> result = cardService.getCard(SEED_CARD_NUMBER);

        assertThat(result).isPresent();
        com.carddemo.card.model.Card dto = result.get();
        assertThat(dto.getCardNumber()).isEqualTo(SEED_CARD_NUMBER);
        assertThat(dto.getAccountId()).isEqualTo(SEED_ACCOUNT_ID_STRING);
        assertThat(dto.getEmbossedName()).isEqualTo(SEED_EMBOSSED_NAME);
        assertThat(dto.getExpiryDate()).isEqualTo(SEED_EXPIRY_DATE);
        assertThat(dto.getActiveStatus())
                .isEqualTo(com.carddemo.card.model.Card.ActiveStatusEnum.Y);
    }

    /**
     * HTTP endpoint, happy path (the full tracer surface): {@code GET /cards/{seeded}} returns
     * {@code 200} with a JSON body carrying exactly the five contract fields read from real
     * Oracle &mdash; controller + JSON serialization + service + repository + Hibernate + Oracle,
     * end to end.
     */
    @Test
    @DisplayName("GET /cards/{seeded} returns 200 with the exact five fields from real Oracle")
    void httpEndpointReturns200WithExactFieldsForSeededCard() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity(cardUrl(SEED_CARD_NUMBER), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).as("200 response must carry a JSON body").isNotNull();
        assertThat(body.path("cardNumber").asText()).isEqualTo(SEED_CARD_NUMBER);
        assertThat(body.path("accountId").asText()).isEqualTo(SEED_ACCOUNT_ID_STRING);
        assertThat(body.path("embossedName").asText()).isEqualTo(SEED_EMBOSSED_NAME);
        assertThat(body.path("expiryDate").asText()).isEqualTo(SEED_EXPIRY_DATE);
        assertThat(body.path("activeStatus").asText()).isEqualTo(SEED_ACTIVE_STATUS);
        // The read DTO must never expose the CVV (CARD-CVV-CD is off the read path).
        assertThat(body.has("cvv")).as("read payload must never expose a CVV").isFalse();
    }

    /**
     * HTTP endpoint, empty path (mirrors legacy {@code DFHRESP(NOTFND)}): {@code GET /cards/{unknown}}
     * returns {@code 404} (the UI empty state) via the {@code NotFoundException} &rarr; RFC&nbsp;7807
     * mapping in {@code GlobalExceptionHandler}, driven by a real (empty) Oracle read.
     */
    @Test
    @DisplayName("GET /cards/{unknown} returns 404 from a real (empty) Oracle read")
    void httpEndpointReturns404ForUnknownCard() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(cardUrl(ABSENT_CARD_NUMBER), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Builds an absolute URL for the card-detail endpoint on the running server's random port.
     *
     * @param cardNumber the path variable card number
     * @return the absolute {@code http://localhost:{port}/cards/{cardNumber}} URL
     */
    private String cardUrl(String cardNumber) {
        return "http://localhost:" + port + "/cards/" + cardNumber;
    }
}
