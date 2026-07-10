package com.carddemo.repository;

import com.carddemo.entity.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Card} aggregate.
 *
 * <p>This interface is the migrated replacement for the legacy VSAM access to
 * the {@code CARDDATA} dataset defined by copybook {@code app/cpy/CVACT02Y.cpy}
 * ({@code CARD-RECORD}, 150-byte record) captured at source commit SHA
 * {@code 27d6c6f}. Two legacy access paths existed and are preserved here:</p>
 *
 * <ul>
 *   <li><b>Base KSDS key</b> &mdash; {@code CARD-NUM PIC X(16)} at offset 0
 *       (the VSAM cluster is defined with {@code KEYS(16 0)} in
 *       {@code app/jcl/CARDFILE.jcl}). This maps to the entity's natural
 *       {@code @Id} of type {@link String}, so single-record retrieval by card
 *       number is served by the inherited
 *       {@link JpaRepository#findById(Object) findById(String)} and updates by
 *       {@link JpaRepository#save(Object) save(Card)}.</li>
 *   <li><b>Alternate index on account id</b> &mdash; the alternate index
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} is defined with
 *       {@code KEYS(11 16)} (offset 16, length 11 = {@code CARD-ACCT-ID
 *       PIC 9(11)}) and, crucially, declared {@code NONUNIQUEKEY} &mdash; a
 *       single account owns many cards. This maps to the derived query
 *       {@link #findByCardAcctId(Long, Pageable)}, which therefore returns
 *       multiple rows.</li>
 * </ul>
 *
 * <p>The repository backs the three online card transactions migrated from
 * CICS/BMS:</p>
 *
 * <ul>
 *   <li><b>Card List</b> (CCLI / {@code COCRDLIC}) &mdash; the card-list screen
 *       pages cards for an account seven rows at a time
 *       ({@code WS-MAX-SCREEN-LINES VALUE 7}). It is served by
 *       {@link #findByCardAcctId(Long, Pageable)} with a
 *       {@code PageRequest.of(page, 7, Sort.by("cardNum"))}.</li>
 *   <li><b>Card View</b> (CCDL / {@code COCRDSLC}) &mdash; served by the
 *       inherited {@code findById(String)}.</li>
 *   <li><b>Card Update</b> (CCUP / {@code COCRDUPC}) &mdash; served by
 *       {@code findById(String)} followed by {@code save(Card)}; the
 *       read-then-rewrite optimistic-concurrency behavior is realized by the
 *       {@code @Version} column on {@link Card}, so no bespoke locking method is
 *       required here.</li>
 * </ul>
 *
 * <p>The generic parameters bind the managed aggregate to {@link Card} and its
 * identifier to {@link String} (the 16-character {@code cardNum}). All standard
 * CRUD, paging and sorting operations are inherited from {@link JpaRepository}.</p>
 *
 * <p>Persistence runs with {@code spring.jpa.hibernate.ddl-auto=validate} over
 * the Flyway-managed {@code card} table. For read parity with the VSAM
 * alternate index, the {@code card_acct_id} column is expected to be indexed by
 * the schema migrations (owned by the database-migration deliverable); this
 * repository does not create that index.</p>
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Retrieves a page of cards belonging to the given account.
     *
     * <p>Migrated from the {@code CARDDATA} VSAM alternate index on
     * {@code CARD-ACCT-ID} ({@code NONUNIQUEKEY}); Spring Data derives the query
     * {@code WHERE card_acct_id = ?} from the method name, matching the property
     * {@code cardAcctId} on {@link Card}. Because the alternate index is
     * non-unique (an account may own many cards), the result is a
     * {@link Page paged} collection rather than a single card.</p>
     *
     * <p>The Card List transaction (CCLI / {@code COCRDLIC}) invokes this with a
     * page size of seven, reproducing the legacy screen's seven-row window
     * (the caller supplies a {@code PageRequest.of(page, 7, Sort.by("cardNum"))}
     * so ordering follows the base card-number key).</p>
     *
     * @param cardAcctId the owning account identifier
     *                   (from {@code CARD-ACCT-ID PIC 9(11)}); must not be
     *                   {@code null}
     * @param pageable   the paging and sorting specification; must not be
     *                   {@code null}
     * @return a {@link Page} of matching {@link Card} rows for the requested
     *         window; empty when the account owns no cards or the requested page
     *         is beyond the available data
     */
    Page<Card> findByCardAcctId(Long cardAcctId, Pageable pageable);
}
