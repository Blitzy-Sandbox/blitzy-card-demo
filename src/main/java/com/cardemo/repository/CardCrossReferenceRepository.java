package com.cardemo.repository;

import com.cardemo.model.entity.CardCrossReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link CardCrossReference} entity, replacing
 * all VSAM keyed access patterns for the {@code CARDXREF.VSAM.KSDS} dataset
 * (KEYS 16 0, RECORDSIZE 50 50) and the CXACAIX alternate index (KEYS 11,25
 * NONUNIQUEKEY) defined in {@code XREFFILE.jcl}.
 *
 * <h3>VSAM-to-JPA Mapping</h3>
 * <ul>
 *   <li><b>Primary Key Access</b> — VSAM READ by XREF-CARD-NUM (16 bytes at offset 0)
 *       maps to inherited {@link #findById(Object)} with {@code String} key type.</li>
 *   <li><b>Alternate Index (CXACAIX)</b> — VSAM AIX on XREF-ACCT-ID (11 bytes at offset 25,
 *       NONUNIQUEKEY) maps to {@link #findByXrefAcctId(String)}, which returns all cards
 *       associated with a given account ID. This is the primary lookup path for
 *       card-to-account resolution throughout the application.</li>
 *   <li><b>Sequential Access</b> — VSAM sequential browse maps to inherited
 *       {@link #findAll()} for diagnostic reader utilities.</li>
 *   <li><b>CRUD Operations</b> — Inherited from {@link JpaRepository}: save, delete,
 *       count, existsById, etc.</li>
 * </ul>
 *
 * <h3>COBOL Program Access Patterns Replaced</h3>
 * <table>
 *   <tr><th>COBOL Program</th><th>Access Pattern</th><th>Java Equivalent</th></tr>
 *   <tr><td>COACTVWC.cbl</td><td>READ CXACAIX (by account ID)</td>
 *       <td>{@link #findByXrefAcctId(String)}</td></tr>
 *   <tr><td>COCRDLIC.cbl</td><td>READ CXACAIX (card list by account)</td>
 *       <td>{@link #findByXrefAcctId(String)}</td></tr>
 *   <tr><td>COCRDUPC.cbl</td><td>READ CARDXREF (by card number)</td>
 *       <td>{@link #findById(Object)}</td></tr>
 *   <tr><td>CBTRN02C.cbl</td><td>READ XREFFILE (batch card validation)</td>
 *       <td>{@link #findById(Object)}</td></tr>
 *   <tr><td>CBACT03C.cbl</td><td>Sequential read (file reader utility)</td>
 *       <td>{@link #findAll()}</td></tr>
 *   <tr><td>CBSTM03A.CBL</td><td>READ XREF (statement card-to-account)</td>
 *       <td>{@link #findByXrefAcctId(String)}</td></tr>
 * </table>
 *
 * <h3>Source COBOL Record Layout (CVACT03Y.cpy)</h3>
 * <pre>
 * 01 CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM     PIC X(16).   — Primary key
 *     05  XREF-CUST-ID      PIC 9(09).   — Customer ID (logical FK)
 *     05  XREF-ACCT-ID      PIC 9(11).   — Account ID (CXACAIX indexed)
 *     05  FILLER            PIC X(14).   — Padding (not mapped)
 * </pre>
 *
 * @see CardCrossReference
 */
@Repository
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {

    /**
     * Finds all card cross-reference records for the specified account identifier.
     *
     * <p>This method replaces the COBOL CXACAIX alternate index access pattern
     * (DEFINE ALTERNATEINDEX KEYS(11,25) NONUNIQUEKEY). Because the alternate index
     * is defined as NONUNIQUEKEY, multiple cards may be associated with a single
     * account, so this method returns a {@link List}.</p>
     *
     * <p>Spring Data JPA derives the query implementation automatically from the
     * method name, generating {@code SELECT * FROM card_cross_references WHERE
     * account_id = ?1}. The underlying {@code idx_xref_acct_id} database index
     * (declared on the {@link CardCrossReference} entity) ensures efficient
     * query execution.</p>
     *
     * <h4>COBOL Programs Using This Access Pattern</h4>
     * <ul>
     *   <li>COACTVWC.cbl — Account view: retrieves all cards for account display</li>
     *   <li>COCRDLIC.cbl — Card list: lists cards belonging to an account</li>
     *   <li>CBTRN02C.cbl — Batch posting: validates card-to-account mapping</li>
     *   <li>CBSTM03A.CBL — Statement generation: resolves cards for statement</li>
     * </ul>
     *
     * @param acctId the 11-character account identifier (XREF-ACCT-ID PIC 9(11));
     *               must not be {@code null}
     * @return a list of all cross-reference records for the given account;
     *         returns an empty list if no cards are associated with the account
     */
    List<CardCrossReference> findByXrefAcctId(String acctId);
}
