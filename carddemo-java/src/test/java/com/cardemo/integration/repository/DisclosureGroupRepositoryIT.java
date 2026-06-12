package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.repository.DisclosureGroupRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link DisclosureGroupRepository} executed against a real
 * PostgreSQL&nbsp;16 Testcontainer seeded by Flyway, validating the relational replacement of
 * the legacy AWS CardDemo VSAM KSDS dataset {@code DISCGRP}.
 *
 * <p>On the mainframe, {@code DISCGRP} was provisioned by {@code app/jcl/DISCGRP.jcl}
 * ({@code DEFINE CLUSTER ... AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS KEYS(16 0) RECORDSIZE(50 50)
 * INDEXED}); its fixed-length 50-byte record layout was defined by copybook
 * {@code app/cpy/CVTRA02Y.cpy} ({@code 01 DIS-GROUP-RECORD}: the 16-byte {@code DIS-GROUP-KEY}
 * group &mdash; {@code DIS-ACCT-GROUP-ID PIC X(10)} + {@code DIS-TRAN-TYPE-CD PIC X(02)} +
 * {@code DIS-TRAN-CAT-CD PIC 9(04)} &mdash; followed by {@code DIS-INT-RATE PIC S9(04)V99} and a
 * trailing {@code FILLER PIC X(28)} = {@code 10 + 2 + 4 + 6 + 28 = 50}). It is small, static
 * <em>reference</em> data &mdash; the interest rate that applies to each distinct
 * {@code (account-group, transaction-type, transaction-category)} combination &mdash; that was
 * consulted, never written, by the batch interest-calculation program:</p>
 * <ul>
 *   <li>{@code CBACT04C} (the {@code INTCALC} job): for each transaction-category balance it
 *       assembles the three-part key from the account's group id plus the balance's type and
 *       category and performs a single keyed {@code READ DISCGRP-FILE} (paragraph
 *       {@code 1200-GET-INTEREST-RATE}). On {@code INVALID KEY} / FILE STATUS {@code '23'} it
 *       overlays the group-id component with the literal {@code 'DEFAULT'} and re-reads
 *       (paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}). The retrieved {@code DIS-INT-RATE} then
 *       drives the monthly-interest formula {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 *       (paragraph {@code 1300-COMPUTE-INTEREST}), which {@code CBACT04C} performs only when the
 *       rate is non-zero ({@code IF DIS-INT-RATE NOT = 0}).</li>
 * </ul>
 * <p>In the migrated stack the same data lives in the PostgreSQL {@code disclosure_group} table
 * mapped by {@link DisclosureGroup} (whose {@code @EmbeddedId} is {@link DisclosureGroupId}), and
 * every access path is served through the Spring Data {@link DisclosureGroupRepository}. These
 * tests prove that migration preserves behavior: three-field composite-key access, full-table
 * count parity, {@code BigDecimal} interest-rate fidelity, and the "record not found" path.</p>
 *
 * <h2>The three-field composite {@code @EmbeddedId} key</h2>
 * <p>The legacy KSDS key was the 16-byte concatenation of the three {@code DIS-GROUP-KEY}
 * sub-fields ({@code DIS-ACCT-GROUP-ID PIC X(10)} + {@code DIS-TRAN-TYPE-CD PIC X(02)} +
 * {@code DIS-TRAN-CAT-CD PIC 9(04)}). That physical concatenated key is migrated to an explicit,
 * typed composite key &mdash; the {@code @Embeddable} {@link DisclosureGroupId} &mdash; so the
 * entity carries {@code @EmbeddedId DisclosureGroupId id} and the repository is typed
 * {@code JpaRepository<DisclosureGroup, DisclosureGroupId>}. Consequently
 * {@link org.springframework.data.repository.CrudRepository#findById(Object) findById(...)} takes
 * a <strong>fully-populated</strong> {@link DisclosureGroupId} (group id, type code and category
 * code all set), exactly as the legacy keyed read required the complete 16-byte key. The key
 * components are constructed via the all-args constructor in their original COBOL field order
 * &mdash; {@code new DisclosureGroupId(groupId, typeCode, catCode)} &mdash; where {@code groupId}
 * is the {@code DIS-ACCT-GROUP-ID PIC X(10)} {@link String}, {@code typeCode} is the
 * {@code DIS-TRAN-TYPE-CD PIC X(02)} {@link String} and {@code catCode} is the
 * {@code DIS-TRAN-CAT-CD PIC 9(04)} {@link Integer}. The order must not be transposed.</p>
 *
 * <h2>Why the assertions are padding-tolerant (the one subtlety unique to this dataset)</h2>
 * <p>{@code DIS-ACCT-GROUP-ID} is {@code PIC X(10)} &mdash; fixed-width, space-padded alphanumeric
 * on the mainframe. Two of the three seeded group ids are <em>shorter</em> than ten characters
 * ({@code "DEFAULT"} and {@code "ZEROAPR"} are seven), so whether the {@code V3} seed stores them
 * space-padded to ten or trimmed, and whether the column is {@code CHAR(10)} (blank-padded) or
 * {@code VARCHAR(10)} (exact), materially affects a naive keyed lookup: a
 * {@code findById(new DisclosureGroupId("DEFAULT", "01", 1))} could miss if the stored key were
 * {@code "DEFAULT   "}, and conversely a blindly right-padded {@code "DEFAULT   "} key misses
 * against an exact {@code VARCHAR} column. To stay robust to that decision, the
 * <strong>authoritative</strong> assertions perform a {@code findAll()} round-trip and match rows
 * by the <em>trimmed</em> group id ({@code getId().getGroupId().trim()}) rather than relying on an
 * exact, padded key. A secondary keyed-read check then re-resolves the row via
 * {@code findById(...)} using the row's <em>own actual stored key</em> ({@code matchedRow.getId()}),
 * which is guaranteed to round-trip regardless of the {@code CHAR}/{@code VARCHAR} representation.
 * This honors the external-interface-contract rule for fixed-width {@code X(n)} fields (AAP
 * &sect;0.7.2) without coupling the suite to a particular padding strategy.</p>
 *
 * <h2>Decimal fidelity ({@code DIS-INT-RATE}) &mdash; {@code compareTo}, never {@code equals}</h2>
 * <p>{@code DIS-INT-RATE PIC S9(04)V99} maps to a {@link BigDecimal} of scale&nbsp;2
 * ({@code NUMERIC(6,2)}); no {@code float}/{@code double} is used (AAP &sect;0.7.3). Rate
 * assertions use {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo
 * isEqualByComparingTo} (i.e. {@link BigDecimal#compareTo}, scale-insensitive) and never
 * {@link BigDecimal#equals} (scale-sensitive), and every expected literal is built with the
 * {@link BigDecimal#BigDecimal(String) String constructor} &mdash; never a {@code double}. The
 * three seeded groups are the interest-rate ground truth for {@code CBACT04C}'s
 * {@code (bal * rate) / 1200} formula: the {@code "ZEROAPR"} group is entirely zero-rate (so
 * {@code CBACT04C} skips the interest computation for it), while {@code "A000000000"} and the
 * {@code "DEFAULT"} fallback carry non-zero rates such as {@code 15.00} for {@code (..., "01", 1)}.</p>
 *
 * <h2>No optimistic locking ({@code @Version}); no custom query methods</h2>
 * <p>{@link DisclosureGroup} carries <strong>no</strong> JPA {@code @Version} column: per AAP
 * &sect;0.7.5 optimistic locking is applied only to {@code Account} (in {@code COACTUPC}) and
 * {@code Card} (in {@code COCRDUPC}); {@code DISCGRP} is read-only reference data. The
 * {@code DEFAULT}-group fallback that {@code CBACT04C} performs is service-layer business logic (a
 * second {@code findById} with the group-id component set to {@code "DEFAULT"}), <em>not</em> a
 * repository method &mdash; so {@link DisclosureGroupRepository} declares <strong>no</strong>
 * custom query methods (Minimal Change Clause, AAP &sect;0.7.1) and these tests exercise only the
 * inherited {@code JpaRepository} operations ({@code findById(DisclosureGroupId)} /
 * {@code findAll()} / {@code count()}). This test therefore deliberately reproduces neither the
 * fallback control flow nor the interest arithmetic.</p>
 *
 * <h2>Infrastructure &amp; isolation</h2>
 * <p>This class extends {@link AbstractRepositoryIT} and therefore inherits the singleton
 * PostgreSQL&nbsp;16 container, the {@code @SpringBootTest} + {@code @ActiveProfiles("test")}
 * context configuration, the {@code @DynamicPropertySource} datasource wiring, and the
 * {@code protected} {@code jdbcTemplate}/{@code entityManager} helpers. None of those are
 * re-declared here, so this class shares the one cached Spring context and one Flyway migration
 * with its sibling repository ITs. The class is annotated {@link Transactional} so each test
 * method runs in its own transaction that is rolled back on completion; although every test here
 * is read-only, this keeps isolation uniform with the mutating sibling ITs and leaves the seeded
 * data pristine.</p>
 *
 * <p><strong>Traceability.</strong> Net-new greenfield test with no COBOL source equivalent;
 * behavior under test is translated from the frozen AWS CardDemo COBOL baseline at commit SHA
 * {@code 27d6c6f}. The COBOL/JCL sources ({@code app/jcl/DISCGRP.jcl},
 * {@code app/cbl/CBACT04C.cbl}) and the ASCII fixture ({@code app/data/ASCII/discgrp.txt}) are
 * read-only reference material and are never copied into this repository.</p>
 *
 * @see DisclosureGroupRepository
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see AbstractRepositoryIT
 */
@Transactional
@DisplayName("DisclosureGroupRepository (DISCGRP / CBACT04C) — composite-key parity integration tests")
class DisclosureGroupRepositoryIT extends AbstractRepositoryIT {

    /**
     * The exact number of disclosure-group rows seeded by {@code V3__seed_data.sql} from
     * {@code app/data/ASCII/discgrp.txt}: three account-group blocks
     * ({@code A000000000}, {@code DEFAULT}, {@code ZEROAPR}) of 17 {@code (type, category)}
     * combinations each = {@code 3 * 17 = 51}.
     */
    private static final long SEEDED_ROWS = 51L;

    /** The number of {@code (type, category)} rows each account-group block contributes (17). */
    private static final long ROWS_PER_GROUP = 17L;

    /** Account-group id of the first seeded block ({@code DIS-ACCT-GROUP-ID}, already 10 chars). */
    private static final String GROUP_A = "A000000000";

    /** Account-group id of the {@code DEFAULT} fallback block (7 chars; padding-sensitive). */
    private static final String GROUP_DEFAULT = "DEFAULT";

    /** Account-group id of the all-zero-rate block (7 chars; padding-sensitive). */
    private static final String GROUP_ZEROAPR = "ZEROAPR";

    /** The complete set of trimmed account-group ids the full browse must yield. */
    private static final Set<String> EXPECTED_GROUPS = Set.of(GROUP_A, GROUP_DEFAULT, GROUP_ZEROAPR);

    /** {@code DIS-TRAN-TYPE-CD} of the first row of each block ({@code "01"}). */
    private static final String TYPE_01 = "01";

    /** {@code DIS-TRAN-CAT-CD} of the first row of each block ({@code 1}). */
    private static final int CAT_1 = 1;

    /**
     * Expected interest rate for {@code (A000000000, "01", 1)} and {@code (DEFAULT, "01", 1)} =
     * {@code 15.00} (from {@code discgrp.txt}; the {@code S9(04)V99} overpunch {@code 00150{}
     * decodes to {@code 0015.00}). Built with the {@link BigDecimal} String constructor (never a
     * {@code double}) so the scale is an exact 2.
     */
    private static final BigDecimal RATE_01_1 = new BigDecimal("15.00");

    /** The all-zero interest rate carried by every {@code ZEROAPR} row ({@code 0.00}). */
    private static final BigDecimal RATE_ZERO = new BigDecimal("0.00");

    /** Repository under test &mdash; the relational replacement for {@code DISCGRP}. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /**
     * Verifies full-table count parity: the repository reports exactly the 51 rows that
     * {@code discgrp.txt} / {@code V3__seed_data.sql} provide &mdash; the count equivalent of a
     * full browse of the small {@code DISCGRP} reference cluster (three 17-row account-group
     * blocks). A raw-JDBC {@code COUNT(*)} cross-check confirms the JPA count matches the physical
     * table {@code disclosure_group} (singular).
     */
    @Test
    @DisplayName("count() equals the 51 seeded rows (3 groups × 17), cross-checked via raw JDBC")
    void count_matchesSeededRowCount() {
        assertThat(disclosureGroupRepository.count())
                .as("DISCGRP replacement must contain exactly the 51 seeded fixture rows (3 × 17)")
                .isEqualTo(SEEDED_ROWS);

        // Cross-check against the physical table (named "disclosure_group", singular) using the
        // inherited JdbcTemplate, bypassing the JPA persistence context.
        Long jdbcCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM disclosure_group", Long.class);
        assertThat(jdbcCount)
                .as("raw JDBC COUNT(*) must agree with JpaRepository.count()")
                .isEqualTo(SEEDED_ROWS);
    }

    /**
     * Verifies that a full browse ({@code findAll()}) yields exactly the three seeded account
     * groups, each contributing 17 {@code (type, category)} rows. Group ids are compared
     * <em>trimmed</em> (the padding-tolerant rule from the class Javadoc) so the assertion holds
     * whether or not the {@code X(10)} group id is stored space-padded.
     */
    @Test
    @DisplayName("findAll() contains exactly the 3 distinct groups {A000000000, DEFAULT, ZEROAPR}, 17 rows each")
    void findAll_containsThreeDistinctGroups() {
        List<DisclosureGroup> all = disclosureGroupRepository.findAll();
        assertThat(all)
                .as("the full DISCGRP browse must return all 51 seeded rows")
                .hasSize((int) SEEDED_ROWS);

        // Distinct, trim-tolerant set of account-group ids must be exactly the three blocks.
        Set<String> distinctGroups = all.stream()
                .map(group -> group.getId().getGroupId().trim())
                .collect(Collectors.toSet());
        assertThat(distinctGroups)
                .as("the full browse must surface exactly the three seeded account-group blocks")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_GROUPS);

        // Each block must contribute exactly 17 (type, category) combinations.
        Map<String, Long> rowsByGroup = all.stream()
                .collect(Collectors.groupingBy(
                        group -> group.getId().getGroupId().trim(), Collectors.counting()));
        assertThat(rowsByGroup)
                .as("each account-group block must contribute exactly 17 (type, category) rows")
                .containsOnlyKeys(EXPECTED_GROUPS)
                .containsEntry(GROUP_A, ROWS_PER_GROUP)
                .containsEntry(GROUP_DEFAULT, ROWS_PER_GROUP)
                .containsEntry(GROUP_ZEROAPR, ROWS_PER_GROUP);
    }

    /**
     * Verifies {@code BigDecimal} interest-rate fidelity for the rate that {@code CBACT04C} feeds
     * into {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}:
     * <ul>
     *   <li>every {@code "ZEROAPR"} row carries a {@code 0.00} rate (so {@code CBACT04C}'s
     *       {@code IF DIS-INT-RATE NOT = 0} guard skips the computation), and</li>
     *   <li>the {@code (A000000000, "01", 1)} row carries a non-null {@code 15.00} rate at the
     *       exact scale&nbsp;2 of {@code NUMERIC(6,2)}.</li>
     * </ul>
     * All numeric comparisons use {@code compareTo} semantics
     * ({@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo
     * isEqualByComparingTo}), never {@link BigDecimal#equals} (AAP &sect;0.7.3).
     */
    @Test
    @DisplayName("interest rates: ZEROAPR rows are 0.00; A000000000/01/1 is 15.00 at scale 2 (compareTo, not equals)")
    void disclosureGroup_interestRate_hasCorrectScaleAndZeroApr() {
        List<DisclosureGroup> all = disclosureGroupRepository.findAll();

        // ZEROAPR block: every row's rate must compare equal to 0.00 (scale-insensitive).
        List<DisclosureGroup> zeroAprRows = all.stream()
                .filter(group -> GROUP_ZEROAPR.equals(group.getId().getGroupId().trim()))
                .collect(Collectors.toList());
        assertThat(zeroAprRows)
                .as("the ZEROAPR block must contribute its full 17 rows")
                .hasSize((int) ROWS_PER_GROUP);
        zeroAprRows.forEach(group ->
                assertThat(group.getDisIntRate())
                        .as("every ZEROAPR rate must be 0.00 (compared via BigDecimal.compareTo)")
                        .isEqualByComparingTo(RATE_ZERO));

        // A000000000 / type 01 / category 1: non-null, exact NUMERIC(6,2) scale of 2, value 15.00.
        Optional<DisclosureGroup> firstA = all.stream()
                .filter(group -> GROUP_A.equals(group.getId().getGroupId().trim()))
                .filter(group -> TYPE_01.equals(group.getId().getTypeCode().trim()))
                .filter(group -> Integer.valueOf(CAT_1).equals(group.getId().getCatCode()))
                .findFirst();
        assertThat(firstA)
                .as("the (A000000000, 01, 1) interest-rate ground-truth row must be present")
                .isPresent();
        BigDecimal rate = firstA.get().getDisIntRate();
        assertThat(rate)
                .as("DIS-INT-RATE must be stored (non-null reference data)")
                .isNotNull();
        assertThat(rate.scale())
                .as("DIS-INT-RATE PIC S9(04)V99 -> NUMERIC(6,2): scale must be exactly 2")
                .isEqualTo(2);
        assertThat(rate)
                .as("(A000000000, 01, 1) rate must be 15.00 (compareTo, not equals)")
                .isEqualByComparingTo(RATE_01_1);
    }

    /**
     * Demonstrates the padding-tolerant keyed lookup that distinguishes this dataset (class
     * Javadoc, §3): the {@code (DEFAULT, "01", 1)} row &mdash; the {@code DEFAULT} fallback that
     * {@code CBACT04C}'s {@code 1200-A-GET-DEFAULT-INT-RATE} would reach &mdash; is located via the
     * <strong>authoritative</strong> {@code findAll()} trim filter (robust to whether the seven-character
     * {@code "DEFAULT"} group id is stored space-padded), and its rate is asserted to be the
     * {@code 15.00} ground truth. A robust secondary keyed read then re-resolves the same row via
     * {@code findById(...)} using the row's <em>own actual stored key</em>, proving keyed access
     * round-trips irrespective of the {@code CHAR}/{@code VARCHAR} representation. A blindly
     * right-padded key is additionally exercised only to confirm it never binds a <em>different</em>
     * row (it legitimately returns empty against this exact-match {@code VARCHAR} schema).
     */
    @Test
    @DisplayName("findById is padding-tolerant: the DEFAULT/01/1 fallback row resolves via the trimmed key round-trip")
    void findById_paddingTolerant_resolvesDefaultGroup() {
        List<DisclosureGroup> all = disclosureGroupRepository.findAll();

        // Authoritative: locate the logical (DEFAULT, 01, 1) row by trimmed-key match.
        Optional<DisclosureGroup> defaultRow = all.stream()
                .filter(group -> GROUP_DEFAULT.equals(group.getId().getGroupId().trim()))
                .filter(group -> TYPE_01.equals(group.getId().getTypeCode().trim()))
                .filter(group -> Integer.valueOf(CAT_1).equals(group.getId().getCatCode()))
                .findFirst();
        assertThat(defaultRow)
                .as("the DEFAULT-group fallback row (DEFAULT, 01, 1) must be present, trim-tolerant")
                .isPresent();
        DisclosureGroup row = defaultRow.get();
        assertThat(row.getDisIntRate())
                .as("(DEFAULT, 01, 1) fallback rate must be 15.00 (compareTo, not equals)")
                .isEqualByComparingTo(RATE_01_1);

        // Robust secondary keyed read: re-resolve via findById using the row's ACTUAL stored key,
        // which round-trips regardless of whether group_id is CHAR-padded or VARCHAR-exact.
        Optional<DisclosureGroup> viaActualKey = disclosureGroupRepository.findById(row.getId());
        assertThat(viaActualKey)
                .as("findById with the row's own stored composite key must resolve the same row")
                .isPresent();

        // Optional right-padded variant (§3): robust to the CHAR/VARCHAR decision. Against this
        // VARCHAR(10) exact-match schema with a trimmed seed it returns empty; against a
        // blank-padded CHAR(10) column it would resolve. Either way it must never bind a DIFFERENT
        // logical row, so we only assert it does not resolve to something other than DEFAULT/01/1.
        DisclosureGroupId rightPaddedKey =
                new DisclosureGroupId(String.format("%-10s", GROUP_DEFAULT), TYPE_01, CAT_1);
        disclosureGroupRepository.findById(rightPaddedKey).ifPresent(group -> {
            assertThat(group.getId().getGroupId().trim()).isEqualTo(GROUP_DEFAULT);
            assertThat(group.getId().getTypeCode().trim()).isEqualTo(TYPE_01);
            assertThat(group.getId().getCatCode()).isEqualTo(CAT_1);
        });
    }

    /**
     * Verifies that a keyed read for a non-existent composite key yields an empty {@link Optional}
     * rather than throwing &mdash; the JPA equivalent of a VSAM "record not found"
     * ({@code FILE STATUS 23}) on a {@code READ} of an absent key. This is the COBOL
     * {@code INVALID KEY} path in {@code CBACT04C}'s {@code 1200-GET-INTEREST-RATE} that triggers
     * the {@code DEFAULT}-group retry in the service layer (reproduced there, not here).
     */
    @Test
    @DisplayName("findById(NOPE______/99/999) returns Optional.empty() (no such composite key)")
    void findById_missingCompositeKey_returnsEmpty() {
        Optional<DisclosureGroup> found =
                disclosureGroupRepository.findById(new DisclosureGroupId("NOPE______", "99", 999));

        assertThat(found)
                .as("a composite key that is not present must produce an empty Optional, not an error")
                .isEmpty();
    }
}
