package com.cardemo.repository;

import com.cardemo.model.entity.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Card} entity, replacing all VSAM
 * keyed access patterns for the {@code CARDDATA.VSAM.KSDS} dataset
 * (KEYS 16 0, RECORDSIZE 150 150).
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS)
 *     KEYS(16 0)              — 16-byte card number at offset 0 (primary key)
 *     RECORDSIZE(150 150)     — fixed 150-byte records
 *     SHAREOPTIONS(2 3)
 *     INDEXED)
 *
 * DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX)
 *     RELATE(AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS)
 *     KEYS(11 16)             — 11-byte account ID at offset 16
 *     NONUNIQUEKEY            — multiple cards per account
 *     UPGRADE)
 * </pre>
 *
 * <h3>COBOL Access Patterns Mapped</h3>
 * <ul>
 *   <li>{@code COCRDLIC.cbl} — Card list: paginated browse (7 rows/page), account/card
 *       range filtering via STARTBR/READNEXT. Mapped to inherited
 *       {@code findAll(Pageable)} and custom {@link #findByCardAcctId(String)}.</li>
 *   <li>{@code COCRDSLC.cbl} — Card detail: single card keyed read by CARD-NUM.
 *       Mapped to inherited {@code findById(String)}.</li>
 *   <li>{@code COCRDUPC.cbl} — Card update: READ for UPDATE + REWRITE with
 *       optimistic concurrency via before/after image comparison. Mapped to inherited
 *       {@code save(Card)} with JPA {@code @Version} on the Card entity.</li>
 *   <li>{@code CBACT02C.cbl} — Card file reader batch utility: sequential read.
 *       Mapped to inherited {@code findAll()}.</li>
 *   <li>Card AIX (alternate index): account-based card lookup used across card
 *       listing flows. Mapped to {@link #findByCardAcctId(String)}.</li>
 * </ul>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>The generic ID type is {@code String} matching the 16-character
 *       {@code CARD-NUM PIC X(16)} primary key preserved with exact width.</li>
 *   <li>The {@code findByCardAcctId()} derived query replaces the VSAM alternate
 *       index (CXACAIX KEYS 11 16 NONUNIQUEKEY) — Spring Data automatically
 *       generates the query from the method name against the Card entity's
 *       {@code cardAcctId} field.</li>
 *   <li>No implementation class is needed — Spring Data JPA generates a proxy
 *       implementation at runtime via component scanning.</li>
 *   <li>The {@code @Repository} annotation enables Spring's persistence exception
 *       translation, mapping JPA exceptions (e.g., {@code EntityNotFoundException})
 *       to Spring's {@code DataAccessException} hierarchy — equivalent to COBOL
 *       FILE STATUS code error handling.</li>
 * </ul>
 *
 * @see Card
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Finds all card records belonging to the specified account.
     *
     * <p>This derived query method maps the CARDDATA VSAM alternate index
     * (AIX KEYS 11 16, NONUNIQUEKEY). The COBOL system defines this
     * alternate index to allow multiple cards to be associated with a
     * single account — the {@code NONUNIQUEKEY} option permits duplicate
     * account IDs across card records.</p>
     *
     * <p><strong>COBOL usage context:</strong></p>
     * <ul>
     *   <li>{@code COCRDLIC.cbl} — Card listing filtered by account: uses the AIX
     *       path to browse all cards for a given account ID.</li>
     *   <li>Account view and statement generation flows that need to resolve
     *       which cards belong to an account.</li>
     * </ul>
     *
     * <p>Spring Data JPA automatically derives the query from the method name,
     * generating SQL equivalent to:
     * {@code SELECT * FROM cards WHERE card_acct_id = ?1}</p>
     *
     * @param acctId the 11-character account identifier to look up cards for;
     *               corresponds to COBOL {@code CARD-ACCT-ID PIC 9(11)}
     * @return a list of all {@link Card} entities with the matching account ID;
     *         may be empty if no cards exist for the account, never {@code null}
     */
    List<Card> findByCardAcctId(String acctId);
}
