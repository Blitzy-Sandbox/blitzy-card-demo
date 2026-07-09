package com.carddemo.repository;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Data JPA slice test for {@link DisclosureGroupRepository}, executed against a
 * <strong>real PostgreSQL&nbsp;16</strong> database provisioned by Testcontainers through
 * {@link AbstractRepositoryTest}. A real engine (never H2) is mandatory here because the
 * assertions below depend on PostgreSQL's exact {@code NUMERIC(6,2)} sign and scale
 * semantics, which an embedded database does not reproduce faithfully.
 *
 * <p>The entity under test, {@link DisclosureGroup}, is the migrated form of the COBOL
 * copybook {@code app/cpy/CVTRA02Y.cpy} ({@code DIS-GROUP-RECORD}, fixed record length
 * <strong>50</strong> bytes, source commit SHA {@code 27d6c6f}). Its composite key
 * {@link DisclosureGroupId} reproduces {@code DIS-GROUP-KEY}
 * ({@code DIS-ACCT-GROUP-ID PIC X(10)}, {@code DIS-TRAN-TYPE-CD PIC X(02)},
 * {@code DIS-TRAN-CAT-CD PIC 9(04)}), and the single non-key data field
 * {@code DIS-INT-RATE PIC S9(04)V99} maps to a signed {@code NUMERIC(6,2)} column.</p>
 *
 * <h2>Behaviour verified</h2>
 * <ol>
 *   <li><strong>Full-composite-key access</strong> — the inherited
 *       {@link org.springframework.data.jpa.repository.JpaRepository#findById(Object)
 *       findById(DisclosureGroupId)}, which replaces the {@code CBACT04C}
 *       {@code 1200-GET-INTEREST-RATE} keyed VSAM read.</li>
 *   <li><strong>Embedded-id property traversal</strong> — the derived query
 *       {@link DisclosureGroupRepository#findByIdDisAcctGroupId(String)}, whose method name
 *       walks the {@code @EmbeddedId} property path {@code id.disAcctGroupId} and generates
 *       {@code WHERE dis_acct_group_id = ?1}; this reproduces a VSAM leading-key browse of
 *       every disclosure row owned by one account group.</li>
 *   <li><strong>Decimal fidelity</strong> — {@code DIS-INT-RATE} round-trips through the
 *       {@code NUMERIC(6,2)} column preserving sign and scale, including a negative value
 *       and the maximum magnitude the precision permits.</li>
 * </ol>
 *
 * <h2>Isolation</h2>
 * <p>Because the base class is {@code @DataJpaTest}, every {@code @Test} runs inside its own
 * transaction that is rolled back on completion. Each test therefore provisions the rows it
 * needs with test-owned account-group identifiers ({@code "TESTGRP0xx"}, each exactly ten
 * characters and distinct from the committed Flyway seed groups {@code A000000000},
 * {@code DEFAULT} and {@code ZEROAPR}) so row counts stay deterministic and no test depends
 * on data written by another. The committed V3 seed is shared and read-only from a test's
 * point of view, so the optional seed-backed assertions below observe the real migrated
 * reference data.</p>
 *
 * @see DisclosureGroupRepository
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see AbstractRepositoryTest
 */
class DisclosureGroupRepositoryTest extends AbstractRepositoryTest {

    /** The repository under test, injected by the {@code @DataJpaTest} slice context. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /**
     * JPA test helper used to seed rows and manage the persistence context (flush the
     * pending inserts to the database, then {@code clear()} so subsequent reads are served
     * from PostgreSQL rather than the first-level cache).
     */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Builds a transient {@link DisclosureGroup} from its three composite-key parts and its
     * interest rate. The key is constructed with the all-arguments {@link DisclosureGroupId}
     * constructor so lookups performed later rely on the id class's
     * {@code equals}/{@code hashCode} over all three components.
     *
     * @param groupId the account-group identifier ({@code DIS-ACCT-GROUP-ID PIC X(10)})
     * @param typeCd  the transaction-type code ({@code DIS-TRAN-TYPE-CD PIC X(02)})
     * @param catCd   the transaction-category code ({@code DIS-TRAN-CAT-CD PIC 9(04)})
     * @param rate    the interest rate ({@code DIS-INT-RATE PIC S9(04)V99})
     * @return a new, unpersisted {@link DisclosureGroup} instance
     */
    private DisclosureGroup newGroup(String groupId, String typeCd, Integer catCd, BigDecimal rate) {
        DisclosureGroup group = new DisclosureGroup();
        group.setId(new DisclosureGroupId(groupId, typeCd, catCd));
        group.setDisIntRate(rate);
        return group;
    }

    /**
     * Verifies that a row is retrievable by its full three-part composite key and that every
     * field — the three key components and the {@code NUMERIC(6,2)} interest rate — round-trips
     * exactly. This exercises the inherited {@code findById(DisclosureGroupId)} that replaces
     * the {@code CBACT04C} full-key keyed read of the DISCGRP VSAM KSDS.
     */
    @Test
    void findById_byCompositeKey_returnsExactRow() {
        entityManager.persist(newGroup("TESTGRP001", "01", 1, new BigDecimal("12.34")));
        entityManager.flush();
        entityManager.clear();

        DisclosureGroup found = disclosureGroupRepository
                .findById(new DisclosureGroupId("TESTGRP001", "01", 1))
                .orElseThrow();

        // Interest rate preserves value and scale (NUMERIC(6,2)).
        assertThat(found.getDisIntRate()).isEqualByComparingTo("12.34");
        assertThat(found.getDisIntRate().scale()).isEqualTo(2);

        // All three composite-key components are reconstituted exactly.
        assertThat(found.getId().getDisAcctGroupId()).isEqualTo("TESTGRP001");
        assertThat(found.getId().getDisTranTypeCd()).isEqualTo("01");
        assertThat(found.getId().getDisTranCatCd()).isEqualTo(1);

        // Optional cross-check against the committed Flyway V3 seed: the first disclosure
        // row for account group A000000000 (transaction type 01, category 1) carries a
        // 15.00 interest rate, confirming findById also resolves the real migrated data.
        DisclosureGroup seeded = disclosureGroupRepository
                .findById(new DisclosureGroupId("A000000000", "01", 1))
                .orElseThrow();
        assertThat(seeded.getDisIntRate()).isEqualByComparingTo("15.00");
    }

    /**
     * Verifies the embedded-id property traversal derived query
     * {@link DisclosureGroupRepository#findByIdDisAcctGroupId(String)}. Three rows sharing the
     * account group {@code "TESTGRP002"} (each with a distinct transaction-type / category
     * combination, hence a distinct composite key) plus one row for a different group
     * {@code "TESTGRP003"} are persisted; the query must return exactly the three rows whose
     * {@code id.disAcctGroupId} matches, proving Spring Data walks the {@code @EmbeddedId}
     * property path ({@code WHERE dis_acct_group_id = ?1}).
     */
    @Test
    void findByIdDisAcctGroupId_traversesEmbeddedId_returnsAllRowsForGroup() {
        List<DisclosureGroup> rows = List.of(
                newGroup("TESTGRP002", "01", 1, new BigDecimal("10.00")),
                newGroup("TESTGRP002", "01", 2, new BigDecimal("11.00")),
                newGroup("TESTGRP002", "02", 1, new BigDecimal("12.00")),
                newGroup("TESTGRP003", "01", 1, new BigDecimal("13.00")));
        disclosureGroupRepository.saveAllAndFlush(rows);
        entityManager.clear();

        List<DisclosureGroup> found = disclosureGroupRepository.findByIdDisAcctGroupId("TESTGRP002");

        assertThat(found).hasSize(3);
        assertThat(found).allMatch(g -> g.getId().getDisAcctGroupId().equals("TESTGRP002"));

        // Optional cross-check against the committed Flyway V3 seed: account group
        // A000000000 is seeded with exactly 17 disclosure rows across its transaction-type
        // and category combinations, confirming the leading-key browse over real seed data.
        assertThat(disclosureGroupRepository.findByIdDisAcctGroupId("A000000000")).hasSize(17);
    }

    /**
     * Verifies decimal fidelity of the signed {@code DIS-INT-RATE PIC S9(04)V99} field mapped
     * to {@code NUMERIC(6,2)} (four integer digits plus two fractional digits). A negative rate
     * and the maximum in-range magnitude ({@code 9999.99}) must both round-trip through the
     * database with their sign preserved and at scale 2.
     */
    @Test
    void disIntRate_preservesSignAndScale() {
        // Negative rate — the picture is signed (S9(04)V99), so the sign must survive.
        entityManager.persist(newGroup("TESTGRP004", "01", 1, new BigDecimal("-1.50")));
        entityManager.flush();
        entityManager.clear();

        DisclosureGroup negative = disclosureGroupRepository
                .findById(new DisclosureGroupId("TESTGRP004", "01", 1))
                .orElseThrow();
        assertThat(negative.getDisIntRate()).isEqualByComparingTo("-1.50");
        assertThat(negative.getDisIntRate().scale()).isEqualTo(2);

        // Maximum magnitude for NUMERIC(6,2): four integer digits + two fractional digits.
        entityManager.persist(newGroup("TESTGRP005", "01", 1, new BigDecimal("9999.99")));
        entityManager.flush();
        entityManager.clear();

        DisclosureGroup max = disclosureGroupRepository
                .findById(new DisclosureGroupId("TESTGRP005", "01", 1))
                .orElseThrow();
        assertThat(max.getDisIntRate()).isEqualByComparingTo("9999.99");
        assertThat(max.getDisIntRate().scale()).isEqualTo(2);
    }
}
