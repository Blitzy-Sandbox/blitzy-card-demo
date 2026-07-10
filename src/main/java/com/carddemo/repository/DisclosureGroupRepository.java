package com.carddemo.repository;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link DisclosureGroup} reference entity &ndash; the
 * <strong>disclosure-group / interest-rate</strong> lookup table ({@code disclosure_group}).
 *
 * <p>This interface is the idiomatic Java/Spring replacement for the legacy AWS CardDemo VSAM
 * <strong>KSDS</strong> {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}, whose record layout is defined by
 * the COBOL copybook {@code app/cpy/CVTRA02Y.cpy} ({@code DIS-GROUP-RECORD}, fixed record length
 * <strong>50</strong> bytes) and whose cluster is provisioned by IDCAMS in {@code app/jcl/DISCGRP.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(16 0) RECORDSIZE(50 50) INDEXED}), referenced at source commit SHA
 * {@code 27d6c6f}. The base cluster is keyed on the leading 16 bytes &ndash; the three-part
 * {@code DIS-GROUP-KEY} (account-group id + transaction-type code + transaction-category code) &ndash;
 * which is modeled as the composite identifier {@link DisclosureGroupId}. That keyed VSAM access is
 * now expressed as identifier access through Spring Data JPA.</p>
 *
 * <h2>Role in the interest-calculation batch</h2>
 * <p>A disclosure group binds a single interest rate ({@code DIS-INT-RATE PIC S9(04)V99}) to an
 * (account-group, transaction-type, transaction-category) combination. The table is consumed by the
 * interest-calculation program {@code CBACT04C} (the INTCALC job, {@code app/jcl/INTCALC.jcl}), which
 * declares {@code DISCGRP-FILE} as {@code ORGANIZATION IS INDEXED} / {@code ACCESS MODE IS RANDOM}
 * with {@code RECORD KEY IS FD-DISCGRP-KEY} and issues a keyed read for every transaction-category
 * balance it processes (paragraph {@code 1200-GET-INTEREST-RATE}). When the exact group is not on
 * file (VSAM {@code FILE STATUS '23'}) the program substitutes the literal account group
 * {@code 'DEFAULT'} and re-reads (paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}); both are
 * full-composite-key accesses.</p>
 *
 * <h2>VSAM access-path &rarr; Spring Data mapping</h2>
 * <ul>
 *   <li><strong>Full-key keyed read</strong> &mdash; {@code READ DISCGRP-FILE} on the complete
 *       {@code FD-DISCGRP-KEY} (and its {@code 'DEFAULT'} fallback) maps to the inherited
 *       {@link JpaRepository#findById(Object) findById(DisclosureGroupId)}. No custom method is
 *       declared for it, because that would be redundant with the inherited primary-key lookup.</li>
 *   <li><strong>Leading-key (generic-key) scan</strong> by account group maps to the single derived
 *       query {@link #findByIdDisAcctGroupId(String)}, which returns every disclosure row whose
 *       {@code DIS-ACCT-GROUP-ID} matches &ndash; i.e. all interest rates configured for one account
 *       group across every transaction-type / transaction-category combination.</li>
 * </ul>
 * <p>Scope is deliberately bounded to these two access patterns (Gate 7): {@code CBACT04C} keys the
 * DISCGRP file only by the full composite key, so no additional derived finders are introduced.</p>
 *
 * <h2>Embedded-id property traversal</h2>
 * <p>{@link DisclosureGroup} declares its composite key as an
 * {@link jakarta.persistence.EmbeddedId @EmbeddedId} field named {@code id}
 * ({@code private DisclosureGroupId id}). Spring Data therefore addresses a key sub-field through the
 * property path {@code id.<subField>}; the derived method name {@code findById} + {@code DisAcctGroupId}
 * resolves to {@code WHERE id.disAcctGroupId = ?1} (SQL column {@code dis_acct_group_id}). The method
 * is thus intentionally named {@code findByIdDisAcctGroupId} (not {@code findByDisAcctGroupId}).</p>
 *
 * <h2>Type contract</h2>
 * <ul>
 *   <li>Aggregate type: {@link DisclosureGroup}; identifier type: {@link DisclosureGroupId} (the
 *       {@code @Embeddable} composite of {@code disAcctGroupId}, {@code disTranTypeCd} and
 *       {@code disTranCatCd}). The repository is accordingly parameterized as
 *       {@code JpaRepository<DisclosureGroup, DisclosureGroupId>}.</li>
 *   <li>{@link #findByIdDisAcctGroupId(String)} accepts the account-group identifier as a
 *       {@link String} (COBOL {@code DIS-ACCT-GROUP-ID PIC X(10)}).</li>
 * </ul>
 *
 * <h2>Schema contract</h2>
 * <p>The application runs with {@code spring.jpa.hibernate.ddl-auto: validate}; this repository
 * performs no DDL and operates over the {@code disclosure_group} table (composite primary key on
 * {@code dis_acct_group_id}, {@code dis_tran_type_cd}, {@code dis_tran_cat_cd}) created by the Flyway
 * migration {@code db/migration/V1__schema.sql}. The {@link Repository @Repository} stereotype is
 * applied for explicitness and layer consistency &mdash; Spring Data JPA would auto-detect and create
 * the proxy for this interface regardless.</p>
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see JpaRepository
 */
@Repository
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {

    /**
     * Finds every disclosure-group row configured for the supplied <em>account group</em> &ndash;
     * that is, all interest-rate rows sharing the leading key component {@code DIS-ACCT-GROUP-ID}
     * across every transaction-type / transaction-category combination.
     *
     * <p>This derived query reproduces a VSAM leading-key (generic-key) browse over the KSDS
     * {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}, whose 16-byte key begins with the 10-byte
     * {@code DIS-ACCT-GROUP-ID}. Because that key sub-field lives inside the
     * {@link jakarta.persistence.EmbeddedId @EmbeddedId} property {@code id}, Spring Data derives the
     * query from the embedded-id property path {@code id.disAcctGroupId} and generates
     * {@code WHERE dis_acct_group_id = ?1}. A single account group may own many disclosure rows, so
     * the result is returned as a {@link List} &ndash; empty when the group has no rows, never
     * {@code null}.</p>
     *
     * <p>Full-composite-key access (the {@code CBACT04C} paragraph {@code 1200-GET-INTEREST-RATE}
     * keyed read and its {@code 'DEFAULT'} fallback) is served instead by the inherited
     * {@link JpaRepository#findById(Object) findById(DisclosureGroupId)}.</p>
     *
     * @param disAcctGroupId the account-group identifier to match (COBOL
     *                       {@code DIS-ACCT-GROUP-ID PIC X(10)}); must not be {@code null}
     * @return all matching {@link DisclosureGroup} rows for the account group, in no guaranteed
     *         order; an empty list if none match
     */
    List<DisclosureGroup> findByIdDisAcctGroupId(String disAcctGroupId);
}
