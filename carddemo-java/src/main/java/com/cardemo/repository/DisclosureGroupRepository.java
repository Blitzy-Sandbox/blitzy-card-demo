package com.cardemo.repository;

import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link DisclosureGroup} entity
 * &mdash; the interest-rate disclosure-group reference record.
 *
 * <p>This repository is the Java&nbsp;25 / Spring Data JPA replacement for the
 * legacy AWS CardDemo <strong>{@code DISCGRP} VSAM KSDS dataset</strong>
 * ({@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}), provisioned on the mainframe by
 * the IDCAMS job {@code app/jcl/DISCGRP.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(16 0) RECORDSIZE(50 50) INDEXED}). Its fixed
 * 50-byte record layout is defined by the COBOL copybook
 * {@code app/cpy/CVTRA02Y.cpy} ({@code 01 DIS-GROUP-RECORD}: the 16-byte
 * {@code DIS-GROUP-KEY} group &mdash; {@code DIS-ACCT-GROUP-ID PIC X(10)} +
 * {@code DIS-TRAN-TYPE-CD PIC X(02)} + {@code DIS-TRAN-CAT-CD PIC 9(04)}
 * &mdash; followed by {@code DIS-INT-RATE PIC S9(04)V99} and
 * {@code FILLER PIC X(28)}). In the migrated stack the same reference data lives
 * in the PostgreSQL {@code disclosure_group} table mapped by the
 * {@link DisclosureGroup} entity (whose {@code @EmbeddedId} is
 * {@link DisclosureGroupId}), and every access path is served through this
 * interface (AAP &sect;0.4.1, &sect;0.6.2, &sect;0.7.6).</p>
 *
 * <h2>Role &mdash; interest-rate lookup for batch interest calculation</h2>
 * <p>{@code DISCGRP} holds the interest rate that applies to each distinct
 * {@code (account-group, transaction-type, transaction-category)} combination.
 * On the mainframe it was consulted exclusively by the batch interest-calculation
 * program {@code CBACT04C} (the {@code INTCALC} job). For each category balance,
 * {@code CBACT04C} assembles the three-part key from the account's group id plus
 * the balance's transaction type and category
 * ({@code MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID},
 * {@code MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD},
 * {@code MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD}) and performs a single keyed
 * {@code READ DISCGRP-FILE} (paragraph {@code 1200-GET-INTEREST-RATE}). The
 * retrieved {@code DIS-INT-RATE} then drives the monthly-interest formula
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (paragraph
 * {@code 1300-COMPUTE-INTEREST}). The dataset is read-only reference data:
 * no program writes to it at run time.</p>
 *
 * <h2>The {@code DEFAULT}-group fallback is service-layer business logic,
 * not a repository method</h2>
 * <p>When the keyed read in {@code 1200-GET-INTEREST-RATE} returns FILE STATUS
 * {@code '23'} (record not found / {@code INVALID KEY}), {@code CBACT04C} does
 * <strong>not</strong> issue a different kind of query. It instead overlays the
 * group-id component of the <em>same</em> key with the literal {@code 'DEFAULT'}
 * ({@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}) and performs a
 * <strong>second keyed read</strong> with the identical access path (paragraph
 * {@code 1200-A-GET-DEFAULT-INT-RATE}). Both reads are full-composite-key
 * lookups &mdash; the only difference is the value supplied for the group-id
 * component. That fallback is therefore reproduced in the interest-calculation
 * service/processor ({@code InterestCalculationJob} and its item processor) as a
 * <strong>second {@code findById}</strong> using a
 * {@link DisclosureGroupId} whose {@code groupId} is {@code "DEFAULT"} (the
 * {@code typeCode} and {@code catCode} unchanged), invoked only when the first
 * {@code findById} returns an empty {@link java.util.Optional}. It is a control-flow
 * decision (two sequential keyed reads), not a distinct data-access path, so per
 * the Minimal Change Clause (AAP &sect;0.7.1) it is deliberately kept out of this
 * interface: declaring a {@code findDefault...} finder here would invent a query
 * the legacy system never had. The inherited {@code findById} covers both reads.</p>
 *
 * <h2>Technology substitution &mdash; VSAM KSDS composite keyed access &rarr;
 * {@code JpaRepository} (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>A KSDS exposes exactly the access path {@code CBACT04C} uses against
 * {@code DISCGRP}: a single keyed read on the full 16-byte composite key. The
 * physically concatenated key is replaced by an explicit, typed composite key
 * &mdash; the {@code @EmbeddedId} {@link DisclosureGroupId} declared on the
 * entity &mdash; and the observed operation maps directly onto a method inherited
 * from {@link JpaRepository}. This interface therefore declares <strong>no</strong>
 * methods of its own:</p>
 * <table border="1">
 *   <caption>Legacy KSDS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM KSDS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>Keyed {@code READ} on the full three-part {@code DIS-GROUP-KEY}
 *       ({@code CBACT04C 1200-GET-INTEREST-RATE}), and the second keyed
 *       {@code READ} with the group-id overlaid by {@code 'DEFAULT'}
 *       ({@code 1200-A-GET-DEFAULT-INT-RATE})</td>
 *       <td>{@link JpaRepository#findById(Object) findById(DisclosureGroupId)}
 *           &mdash; returns an {@code Optional}; an absent value models the COBOL
 *           {@code INVALID KEY} / FILE STATUS {@code '23'} "not found" path that
 *           triggers the {@code DEFAULT}-group retry in the service layer</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed
 * {@code JpaRepository<DisclosureGroup, DisclosureGroupId>}. Unlike a
 * single-column reference table, {@link DisclosureGroup} carries a
 * <strong>composite</strong> primary key: the {@code @EmbeddedId}
 * {@link DisclosureGroupId}, whose components &mdash; in COBOL field order
 * &mdash; are {@code groupId} ({@link String} of length&nbsp;10, from
 * {@code DIS-ACCT-GROUP-ID PIC X(10)}), {@code typeCode} ({@link String} of
 * length&nbsp;2, from {@code DIS-TRAN-TYPE-CD PIC X(02)}) and {@code catCode}
 * ({@link Integer}, from {@code DIS-TRAN-CAT-CD PIC 9(04)}). The identifier type
 * parameter is therefore the composite-key class {@link DisclosureGroupId}, not a
 * scalar type, so {@code findById} operates on the full three-part key exactly as
 * the keyed COBOL access did.</p>
 *
 * <h2>Decimal-fidelity note</h2>
 * <p>The {@code DIS-INT-RATE PIC S9(04)V99} rate that this dataset supplies is
 * mapped on the entity to a {@link java.math.BigDecimal} of scale&nbsp;2 (no
 * {@code float}/{@code double}, AAP &sect;0.7.3). The interest arithmetic that
 * consumes it &mdash; {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with
 * {@link java.math.RoundingMode#HALF_EVEN} and the {@code / 1200} divisor
 * preserved without algebraic rearrangement (AAP &sect;0.7.6) &mdash; lives in the
 * batch interest processor, not in this data-access interface. This repository's
 * sole responsibility is to return the rate faithfully.</p>
 *
 * <h2>No optimistic locking / no transactional method</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the online
 * read-update programs {@code COACTUPC} and {@code COCRDUPC}). The
 * disclosure-group record is read-only reference data consumed by batch interest
 * calculation, so the {@link DisclosureGroup} entity carries no {@code @Version}
 * column and this interface declares no concurrency-specific method.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>No {@code @Repository} annotation.</strong> Spring Data
 *       automatically detects interfaces that extend {@link JpaRepository}, and
 *       Spring Boot auto-configures repository scanning for the application base
 *       package {@code com.cardemo}; an explicit {@code @Repository} or
 *       {@code @EnableJpaRepositories} is unnecessary. Persistence exceptions are
 *       still translated transparently for these proxies.</li>
 *   <li><strong>No custom query methods.</strong> The only operation
 *       {@code CBACT04C} performs against {@code DISCGRP} is a full-key read, and
 *       it is already provided by {@link JpaRepository}. Per the Minimal Change
 *       Clause (AAP &sect;0.7.1) no derived queries, {@code @Query} methods,
 *       Jakarta Bean Validation or business logic are added here &mdash; the
 *       {@code DEFAULT}-group fallback (a second {@code findById}) and the
 *       {@link java.math.BigDecimal} interest arithmetic belong to the batch
 *       interest-calculation layer, not to the data-access interface.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see JpaRepository
 */
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroupId> {
    // Intentionally empty: DISCGRP is reached by CBACT04C only through a keyed
    // read on the full composite key (1200-GET-INTEREST-RATE -> findById). The
    // DEFAULT-group fallback (1200-A-GET-DEFAULT-INT-RATE) is a second keyed read
    // with the group-id overlaid by 'DEFAULT' on FILE STATUS '23', reproduced in
    // the interest-calculation service/processor as a second findById -- it is
    // business control flow, not a data-access method. The composite ID type
    // parameter is DisclosureGroupId (groupId String(10) + typeCode String(2) +
    // catCode Integer). No custom query method is declared (Minimal Change
    // Clause, AAP §0.7.1); the inherited findById covers every observed access.
}
