package com.carddemo.card;

import com.carddemo.card.repo.CardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * card-svc — context-load / health smoke test.
 *
 * <p>Boots the full Spring context to verify the seams wire together (the
 * "build-and-start green" gate). Runs OFFLINE with no live Oracle: the JPA
 * {@code CardRepository} is mocked and the DataSource/JPA/Flyway auto-config is
 * excluded, so no database connection is attempted.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT].</p>
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class CardApplicationTests {

    @MockitoBean
    CardRepository cardRepository;

    @Test
    void contextLoads() {
        // Intentionally empty: a failure to load the context fails this test.
    }
}
