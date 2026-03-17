package com.cardemo.repository;

import com.cardemo.model.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Account} entity, replacing all COBOL
 * VSAM keyed access patterns for the {@code ACCTDATA.VSAM.KSDS} dataset.
 *
 * <p>This repository interface maps the original VSAM KSDS dataset defined in
 * {@code app/jcl/ACCTFILE.jcl} with the following specifications:</p>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS)
 *     KEYS(11 0)
 *     RECORDSIZE(300 300)
 *     SHAREOPTIONS(2 3)
 *     INDEXED)
 * </pre>
 *
 * <h3>COBOL Access Pattern Mapping</h3>
 * <table>
 *   <tr><th>COBOL Program</th><th>COBOL Operation</th><th>Java Replacement</th></tr>
 *   <tr>
 *     <td>COACTUPC.cbl</td>
 *     <td>READ ACCTDAT / REWRITE ACCTDAT (with SYNCPOINT)</td>
 *     <td>{@code findById()} + {@code save()} with {@code @Version} optimistic locking</td>
 *   </tr>
 *   <tr>
 *     <td>COACTVWC.cbl</td>
 *     <td>READ ACCTDAT (keyed read for account view)</td>
 *     <td>{@code findById()}</td>
 *   </tr>
 *   <tr>
 *     <td>COBIL00C.cbl</td>
 *     <td>READ ACCTDAT + REWRITE (bill payment balance update)</td>
 *     <td>{@code findById()} + {@code save()}</td>
 *   </tr>
 *   <tr>
 *     <td>CBTRN02C.cbl</td>
 *     <td>READ ACCTDAT (batch — credit limit check, balance update)</td>
 *     <td>{@code findById()} + {@code save()}</td>
 *   </tr>
 *   <tr>
 *     <td>CBACT04C.cbl</td>
 *     <td>READ ACCTDAT (batch — interest calculation, group ID lookup)</td>
 *     <td>{@code findById()}</td>
 *   </tr>
 *   <tr>
 *     <td>CBACT01C.cbl</td>
 *     <td>Sequential read (batch file reader utility)</td>
 *     <td>{@code findAll()}</td>
 *   </tr>
 *   <tr>
 *     <td>COCRDLIC.cbl</td>
 *     <td>Account status filter for card listing</td>
 *     <td>{@code findByAcctActiveStatus()}</td>
 *   </tr>
 * </table>
 *
 * <h3>Primary Key</h3>
 * <p>The VSAM primary key is the 11-byte account ID at offset 0 ({@code ACCT-ID PIC 9(11)}),
 * stored as a {@link String} to preserve leading zeros (e.g., "00000000001"). This is the
 * second generic type parameter ({@code String}) in {@code JpaRepository<Account, String>}.</p>
 *
 * <h3>Optimistic Locking</h3>
 * <p>The {@link Account} entity uses {@code @Version} for optimistic locking, replacing
 * the COBOL pattern in COACTUPC.cbl where before/after record images are compared during
 * updates. The {@code save()} method automatically checks the version field, throwing
 * {@code OptimisticLockException} on concurrent modification — equivalent to the COBOL
 * snapshot mismatch that triggers SYNCPOINT ROLLBACK.</p>
 *
 * <h3>Persistence Exception Translation</h3>
 * <p>The {@code @Repository} annotation enables automatic translation of JPA-specific
 * exceptions to Spring's {@code DataAccessException} hierarchy:</p>
 * <ul>
 *   <li>{@code EntityNotFoundException} → maps COBOL FILE STATUS '23' (record not found)</li>
 *   <li>{@code DataIntegrityViolationException} → maps COBOL FILE STATUS '22' (duplicate key)</li>
 *   <li>{@code OptimisticLockException} → maps COBOL before/after snapshot mismatch</li>
 * </ul>
 *
 * @see Account
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/jcl/ACCTFILE.jcl">
 *      ACCTFILE.jcl — VSAM cluster definition</a>
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cpy/CVACT01Y.cpy">
 *      CVACT01Y.cpy — Account record layout</a>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Finds all accounts matching the given active status.
     *
     * <p>Maps the COBOL browse pattern that filters accounts by their active/inactive
     * status. The original COBOL programs use {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * with values 'Y' (active) or 'N' (inactive) to filter account lists.</p>
     *
     * <p>Used by:</p>
     * <ul>
     *   <li>Account listing views — display only active accounts</li>
     *   <li>Admin views — display accounts of a specific status</li>
     *   <li>COCRDLIC.cbl card listing — filter cards by account active status</li>
     * </ul>
     *
     * <p>Spring Data JPA derives the query automatically from the method name:
     * {@code SELECT a FROM Account a WHERE a.acctActiveStatus = :status}</p>
     *
     * @param status the active status filter value — 'Y' for active, 'N' for inactive
     * @return list of accounts matching the given active status; empty list if none found
     */
    List<Account> findByAcctActiveStatus(String status);

    /**
     * Finds all accounts whose account ID starts with the given prefix.
     *
     * <p>Maps the COBOL {@code STARTBR} (START BROWSE) pattern with partial key match
     * on the VSAM KSDS dataset. In COBOL, {@code EXEC CICS STARTBR FILE('ACCTDAT')
     * RIDFLD(WS-ACCT-ID) GTEQ} allows browsing from a key prefix position, enabling
     * paginated account navigation by starting from a given account ID prefix.</p>
     *
     * <p>Used by:</p>
     * <ul>
     *   <li>Paginated account browsing — start from a given account ID position</li>
     *   <li>Account search by partial ID match</li>
     * </ul>
     *
     * <p>Spring Data JPA derives the query automatically from the method name:
     * {@code SELECT a FROM Account a WHERE a.acctId LIKE :prefix%}</p>
     *
     * @param prefix the account ID prefix to match (e.g., "0000000001" for 10-digit prefix)
     * @return list of accounts whose IDs start with the given prefix; empty list if none found
     */
    List<Account> findByAcctIdStartingWith(String prefix);
}
