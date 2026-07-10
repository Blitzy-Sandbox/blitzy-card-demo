package com.carddemo.repository;

import com.carddemo.entity.CardXref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link CardXref} entity &ndash; the central
 * <strong>card&nbsp;&harr;&nbsp;account&nbsp;&harr;&nbsp;customer</strong> cross-reference table.
 *
 * <p>This interface is the idiomatic Java/Spring replacement for the legacy AWS CardDemo VSAM
 * dataset {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}, whose record layout is defined by the COBOL
 * copybook {@code app/cpy/CVACT03Y.cpy} ({@code CARD-XREF-RECORD}, record length {@code 50}) and
 * whose access paths are provisioned by the JCL job {@code app/jcl/XREFFILE.jcl} (source commit
 * SHA {@code 27d6c6f}). Together they drove the cross-reference navigation used throughout the
 * online (CICS) and batch tiers &ndash; for example {@code CBTRN02C} resolves an inbound card
 * number to its owning account via this cross-reference before posting a transaction.</p>
 *
 * <h2>VSAM access-path &rarr; Spring Data mapping</h2>
 * <p>The legacy dataset exposes two access paths; each maps to a distinct repository operation:</p>
 * <ul>
 *   <li><strong>Base KSDS key</strong> &mdash; {@code KEYS(16 0)} over {@code XREF-CARD-NUM PIC X(16)}
 *       (offset {@code 0}, length {@code 16}). This is the entity's {@code @Id} ({@link CardXref#getXrefCardNum()},
 *       a {@code String}), so <em>card&nbsp;&rarr;&nbsp;account/customer</em> resolution is served by the
 *       inherited {@link JpaRepository#findById(Object) findById(String)} &mdash; no custom method is
 *       declared for it (that would be redundant with the inherited primary-key lookup). Card&nbsp;&rarr;&nbsp;customer
 *       resolution reads {@link CardXref#getXrefCustId()} from the record returned by {@code findById}.</li>
 *   <li><strong>Alternate index</strong> {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} &mdash; defined in
 *       {@code app/jcl/XREFFILE.jcl} as {@code KEYS(11,25) NONUNIQUEKEY} (offset {@code 25}, length {@code 11})
 *       over {@code XREF-ACCT-ID PIC 9(11)}. Because the alternate index is declared
 *       {@code NONUNIQUEKEY}, a single account may map to many cross-reference rows, so the
 *       <em>account&nbsp;&rarr;&nbsp;cross-reference</em> lookup ({@link #findByXrefAcctId(Long)}) returns a
 *       {@link List}. This reproduces the AIX-driven, account-based navigation and statement flows.</li>
 * </ul>
 *
 * <h2>Type contract</h2>
 * <ul>
 *   <li>Aggregate type: {@link CardXref}; identifier type: {@link String} (the 16-character card number).</li>
 *   <li>{@link #findByXrefAcctId(Long)} accepts a {@link Long}, matching the scalar
 *       {@link CardXref#getXrefAcctId()} field mapped to column {@code xref_acct_id}.</li>
 * </ul>
 *
 * <h2>Schema / performance note</h2>
 * <p>The repository runs against the Flyway-managed {@code card_xref} table under a
 * {@code spring.jpa.hibernate.ddl-auto: validate} configuration. To preserve the performance
 * characteristics of the original VSAM alternate index, the {@code xref_acct_id} column is expected
 * to be indexed by the schema migrations ({@code db/migration/V2__indexes.sql}); that index is owned
 * by the database-migration artifacts and is intentionally not declared here.</p>
 *
 * @see CardXref
 * @see JpaRepository
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Finds every card cross-reference row associated with the supplied account identifier &ndash;
     * i.e. resolves an <em>account&nbsp;&rarr;&nbsp;card(s)</em> relationship.
     *
     * <p>This derived query reproduces the legacy VSAM non-unique alternate index
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} ({@code KEYS(11,25) NONUNIQUEKEY} over
     * {@code XREF-ACCT-ID}). Spring Data derives the query from the method name, matching on the
     * {@code xrefAcctId} property of {@link CardXref} and generating {@code WHERE xref_acct_id = ?}.
     * Because the alternate index is non-unique, an account may be linked to multiple cards, so the
     * result is returned as a {@link List} (empty when the account has no cross-reference rows &ndash;
     * never {@code null}).</p>
     *
     * @param xrefAcctId the account identifier to search on (maps to {@code XREF-ACCT-ID}); must not be {@code null}
     * @return all matching {@link CardXref} rows for the account, in no guaranteed order; an empty
     *         list if none match
     */
    List<CardXref> findByXrefAcctId(Long xrefAcctId);
}
