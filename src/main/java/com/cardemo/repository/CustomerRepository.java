package com.cardemo.repository;

import com.cardemo.model.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Customer} entity.
 *
 * <p>Replaces all COBOL VSAM keyed access patterns for the
 * {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS} dataset as defined in
 * {@code CUSTFILE.jcl} (KEYS 9 0, RECORDSIZE 500 500, INDEXED).
 *
 * <h3>COBOL Access Patterns Replaced</h3>
 * <table>
 *   <caption>VSAM-to-JPA operation mapping</caption>
 *   <tr><th>COBOL Program</th><th>VSAM Operation</th><th>JPA Equivalent</th></tr>
 *   <tr>
 *     <td>{@code COACTVWC.cbl}</td>
 *     <td>READ CUSTDAT (keyed read for account view)</td>
 *     <td>{@link #findById(Object) findById(String)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code COACTUPC.cbl}</td>
 *     <td>READ CUSTDAT (customer read within SYNCPOINT scope)</td>
 *     <td>{@link #findById(Object) findById(String)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CBCUS01C.cbl}</td>
 *     <td>Sequential READ (batch customer file reader utility)</td>
 *     <td>{@link #findAll()}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CBSTM03A.CBL}</td>
 *     <td>READ CUSTDAT (customer data for statement generation)</td>
 *     <td>{@link #findById(Object) findById(String)}</td>
 *   </tr>
 * </table>
 *
 * <h3>Key Design Notes</h3>
 * <ul>
 *   <li>The generic ID type is {@code String} (not {@code Long}) because the COBOL primary key
 *       {@code CUST-ID PIC 9(09)} is a 9-digit numeric identifier where leading zeros are
 *       semantically significant (e.g., "000000001").</li>
 *   <li>No custom query methods are required — all COBOL VSAM access patterns (keyed read,
 *       sequential read, write, rewrite, delete) are covered by inherited
 *       {@link JpaRepository} methods.</li>
 *   <li>No alternate indexes exist for the CUSTDATA VSAM dataset, so no additional
 *       {@code findBy*} methods are needed.</li>
 *   <li>The {@code @Repository} annotation enables Spring component scanning and persistence
 *       exception translation from JPA-specific exceptions (e.g., {@code EntityNotFoundException}
 *       for COBOL FILE STATUS '23' record-not-found) to Spring's {@code DataAccessException}
 *       hierarchy.</li>
 * </ul>
 *
 * @see Customer
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/jcl/CUSTFILE.jcl">CUSTFILE.jcl</a>
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cpy/CVCUS01Y.cpy">CVCUS01Y.cpy</a>
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cpy/CUSTREC.cpy">CUSTREC.cpy</a>
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {

    // All required operations are inherited from JpaRepository<Customer, String>:
    //
    // Keyed read (READ CUSTDAT by CUST-ID):
    //   Optional<Customer> findById(String custId)
    //   boolean existsById(String custId)
    //
    // Sequential read (batch file reader):
    //   List<Customer> findAll()
    //   Page<Customer> findAll(Pageable pageable)
    //   List<Customer> findAll(Sort sort)
    //
    // Write / Rewrite (WRITE / REWRITE CUSTDAT):
    //   Customer save(Customer entity)
    //   List<Customer> saveAll(Iterable<Customer> entities)
    //
    // Delete (DELETE CUSTDAT):
    //   void deleteById(String custId)
    //   void delete(Customer entity)
    //   void deleteAll()
    //
    // Count:
    //   long count()
}
