package com.cardemo.repository;

import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link TransactionCategoryBalance} entity,
 * replacing all VSAM keyed access patterns to the {@code TCATBALF.VSAM.KSDS}
 * dataset (KEYS 17 0, RECORDSIZE 50 50) defined in {@code app/jcl/TCATBALF.jcl}.
 *
 * <p>This repository manages cumulative balances for each unique combination of
 * account ID, transaction type code, and transaction category code. It is a
 * critical component used by two batch programs in the CardDemo pipeline:</p>
 *
 * <ul>
 *   <li><strong>Interest Calculation ({@code CBACT04C.cbl})</strong> — Reads
 *       category balances by account ID to compute interest using the formula
 *       {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200} with
 *       {@code RoundingMode.HALF_EVEN} (banker's rounding).</li>
 *   <li><strong>Daily Transaction Posting ({@code CBTRN02C.cbl})</strong> —
 *       Reads and rewrites category balance records to update cumulative
 *       balances after posting each validated daily transaction.</li>
 * </ul>
 *
 * <h3>VSAM-to-JPA Access Pattern Mapping</h3>
 * <table>
 *   <caption>COBOL VSAM Operations → JPA Repository Methods</caption>
 *   <tr><th>COBOL Pattern</th><th>JPA Method</th></tr>
 *   <tr><td>{@code READ TCATBALF} (by composite key)</td>
 *       <td>{@code findById(TransactionCategoryBalanceId)}</td></tr>
 *   <tr><td>{@code REWRITE TCATBALF}</td>
 *       <td>{@code save(TransactionCategoryBalance)}</td></tr>
 *   <tr><td>{@code READ TCATBALF} (all for account)</td>
 *       <td>{@code findByIdAcctId(String)}</td></tr>
 *   <tr><td>{@code WRITE TCATBALF}</td>
 *       <td>{@code save(TransactionCategoryBalance)}</td></tr>
 *   <tr><td>{@code DELETE TCATBALF}</td>
 *       <td>{@code deleteById(TransactionCategoryBalanceId)}</td></tr>
 * </table>
 *
 * <h3>Composite Key Structure</h3>
 * <p>The entity uses {@link TransactionCategoryBalanceId} as an
 * {@code @EmbeddedId} composite key, mapping the 17-byte
 * {@code TRAN-CAT-KEY} from COBOL {@code CVTRA01Y.cpy}:</p>
 * <pre>{@code
 *   TRANCAT-ACCT-ID  PIC 9(11)  → acctId   (String, 11 chars)
 *   TRANCAT-TYPE-CD  PIC X(02)  → typeCode (String, 2 chars)
 *   TRANCAT-CD       PIC 9(04)  → catCode  (Short)
 * }</pre>
 *
 * <p>COBOL source reference: {@code app/jcl/TCATBALF.jcl} and
 * {@code app/cpy/CVTRA01Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {

    /**
     * Finds all transaction category balance records for a given account ID.
     *
     * <p>This derived query method navigates through the {@code @EmbeddedId}
     * property ({@code id}) to match the {@code acctId} field within the
     * {@link TransactionCategoryBalanceId} composite key. Spring Data JPA
     * interprets the method name as a property path: {@code id.acctId}.</p>
     *
     * <p><strong>Primary usage:</strong> The interest calculation batch
     * ({@code CBACT04C.cbl}) iterates over all category balance records for
     * an account when computing interest. In COBOL, this was accomplished by
     * sequential reads with a partial key match on {@code TRANCAT-ACCT-ID};
     * in JPA, the derived query generates a {@code WHERE} clause filtering
     * on the {@code acct_id} column of the composite key.</p>
     *
     * <p><strong>Generated SQL equivalent:</strong></p>
     * <pre>{@code
     * SELECT * FROM transaction_category_balances
     *  WHERE acct_id = :acctId
     * }</pre>
     *
     * @param acctId the 11-character account identifier (e.g., {@code "00000000001"});
     *               leading zeros are significant and must be preserved
     * @return a list of all category balance records for the specified account;
     *         an empty list if no records exist for the account
     */
    List<TransactionCategoryBalance> findByIdAcctId(String acctId);
}
