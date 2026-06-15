package com.cardemo.repository;

import com.cardemo.model.entity.UserSecurity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link UserSecurity} aggregate.
 *
 * <p>This repository is the Java&nbsp;25 / Spring Data JPA replacement for the
 * legacy AWS CardDemo VSAM KSDS dataset {@code USRSEC}. On the mainframe that
 * cluster was provisioned by {@code app/jcl/DUSRSECJ.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(8,0) RECORDSIZE(80,80) INDEXED}), its
 * fixed-length 80-byte record layout was defined by the copybook
 * {@code app/cpy/CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA}, primary key
 * {@code SEC-USR-ID PIC X(08)}), and it was reached exclusively through CICS file
 * control by the online sign-on and user-administration programs. In the migrated
 * stack the same data lives in the PostgreSQL {@code user_security} table mapped
 * by the {@link UserSecurity} entity, and every access path is served through this
 * interface.</p>
 *
 * <h2>Technology substitution &mdash; VSAM KSDS keyed + browse access &rarr;
 * {@code JpaRepository} (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>The {@code USRSEC} Key-Sequenced Data Set exposed two distinct access
 * patterns keyed on the 8-byte cluster key {@code SEC-USR-ID}: a direct keyed read
 * (online sign-on) and a forward/backward sequential browse positioned with
 * {@code STARTBR} (the admin user-list screen). Both map directly onto Spring Data
 * operations &mdash; the keyed read and CRUD onto operations inherited from
 * {@link JpaRepository}, and the browse onto the two derived query methods declared
 * below:</p>
 * <table border="1">
 *   <caption>Legacy VSAM/CICS operation &rarr; Spring Data operation</caption>
 *   <tr><th>Legacy VSAM/CICS operation</th><th>Repository operation</th></tr>
 *   <tr><td>{@code READ DATASET('USRSEC') RIDFLD(WS-USER-ID)} (keyed sign-on read,
 *       {@code COSGN00C})</td>
 *       <td>{@link #findBySecUsrId(String)} &mdash; returns an {@code Optional}; an
 *           absent value models the COBOL {@code DFHRESP(NOTFND)} invalid-key
 *           condition</td></tr>
 *   <tr><td>{@code STARTBR} (GTEQ positioning) + {@code READNEXT}/{@code READPREV}
 *       over {@code SEC-USR-ID} (admin user list, {@code COUSR00C}, 10 rows/page,
 *       PF8 forward / PF7 backward)</td>
 *       <td>{@link #findBySecUsrIdGreaterThanEqual(String, Pageable)}</td></tr>
 *   <tr><td>Existence probe before add ({@code COUSR01C})</td>
 *       <td>{@link JpaRepository#existsById(Object) existsById(String)}</td></tr>
 *   <tr><td>{@code WRITE DATASET('USRSEC')} (add user, {@code COUSR01C}) /
 *       {@code REWRITE} (update user, {@code COUSR02C})</td>
 *       <td>{@link JpaRepository#save(Object) save(UserSecurity)}</td></tr>
 *   <tr><td>{@code DELETE DATASET('USRSEC')} (delete user, {@code COUSR03C})</td>
 *       <td>{@link JpaRepository#deleteById(Object) deleteById(String)} /
 *           {@link JpaRepository#delete(Object) delete(UserSecurity)}</td></tr>
 *   <tr><td>Bulk load (IDCAMS {@code REPRO} of the seed into the cluster,
 *       {@code DUSRSECJ.jcl} STEP03)</td>
 *       <td>{@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;UserSecurity&gt;)}</td></tr>
 *   <tr><td>Cluster record count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<UserSecurity, String>}. The
 * identifier type is {@link String} &mdash; not a numeric type &mdash; because the
 * primary key {@link UserSecurity#getSecUsrId()} is migrated from
 * {@code SEC-USR-ID PIC X(08)}, the fixed 8-character (alphanumeric) user id that
 * is the cluster's natural VSAM key. It is kept as a {@link String} (PostgreSQL
 * {@code VARCHAR(8)}) so the exact 8-byte width and any leading characters are
 * preserved, matching the keyed VSAM access used by both sign-on and user admin.</p>
 *
 * <h2>The two declared query methods</h2>
 * <ul>
 *   <li><strong>Sign-on keyed read &rarr; {@link #findBySecUsrId(String)}.</strong>
 *       Although {@link JpaRepository#findById(Object) findById(String)} is
 *       inherited and functionally equivalent (the user id <em>is</em> the
 *       {@code @Id}), the explicit derived method is declared so the
 *       {@code AuthenticationService} reads intention-revealingly and so the
 *       property name binds to the entity field {@code secUsrId} exactly. It
 *       returns an {@link Optional} so the caller handles "user not found" without
 *       a thrown exception, mirroring the COBOL invalid-key
 *       ({@code DFHRESP(NOTFND)}) branch of {@code COSGN00C}.</li>
 *   <li><strong>Admin user-list browse &rarr;
 *       {@link #findBySecUsrIdGreaterThanEqual(String, Pageable)}.</strong> The
 *       {@code GreaterThanEqual} keyword reproduces the {@code STARTBR} GTEQ
 *       positioning of {@code COUSR00C}; the {@link Pageable} supplies the page
 *       window so the legacy 10-rows-per-page contract stays a service-layer
 *       concern.</li>
 * </ul>
 *
 * <h2>No password logic here &mdash; BCrypt is a service concern (C-003, AAP
 * &sect;0.7.2)</h2>
 * <p>The single permitted behavioral change of the whole migration &mdash;
 * upgrading the legacy {@code SEC-USR-PWD PIC X(08)} plaintext password to a salted
 * <strong>BCrypt</strong> hash &mdash; is realised entirely in the service/security
 * layer, <strong>never here</strong>. Where {@code COSGN00C} compared the entered
 * password to the stored plaintext, the migrated {@code AuthenticationService}
 * BCrypt-verifies it against the stored hash; the {@code UserAddService} /
 * {@code UserUpdateService} encode on create/update. This repository deliberately
 * declares <strong>no</strong> password lookup, comparison or encoding method: the
 * sign-on flow loads the record by id ({@link #findBySecUsrId(String)}) and the
 * service performs the BCrypt verification. Keeping the data-access boundary free of
 * credential logic preserves the strict layering the Minimal Change Clause requires.</p>
 *
 * <h2>Consumers (AAP &sect;0.6.2 access matrix)</h2>
 * <p>{@code USRSEC} is an <strong>online-only</strong> dataset; no batch program
 * reaches it. Its consumers are the migrated online programs:</p>
 * <ul>
 *   <li>Sign-on &mdash; {@code COSGN00C} reads the user record by id to
 *       authenticate &rarr; {@link #findBySecUsrId(String)}.</li>
 *   <li>User administration &mdash; {@code COUSR00C} lists users a page at a time
 *       &rarr; {@link #findBySecUsrIdGreaterThanEqual(String, Pageable)};
 *       {@code COUSR01C} adds, {@code COUSR02C} updates and {@code COUSR03C} deletes
 *       a single user &rarr; the inherited {@code save(...)} /
 *       {@code deleteById(String)} / {@code existsById(String)} operations.</li>
 * </ul>
 *
 * <h2>No optimistic locking</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the read-update
 * programs {@code COACTUPC} and {@code COCRDUPC}). The {@code USRSEC} admin programs
 * perform single-record reads/writes without a read-update snapshot comparison, so
 * the {@link UserSecurity} entity carries <strong>no</strong> {@code @Version}
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
 *   <li><strong>Only the two access-path methods are declared.</strong> Every other
 *       operation the COBOL programs perform against {@code USRSEC} is keyed on the
 *       primary key ({@code SEC-USR-ID}) and is already provided by
 *       {@link JpaRepository}. Per the Minimal Change Clause (AAP &sect;0.7.1) no
 *       further derived queries, {@code @Query} methods, Jakarta Bean Validation or
 *       business logic are added here &mdash; that behaviour belongs to the service
 *       and DTO layers.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see UserSecurity
 * @see JpaRepository
 */
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    /**
     * Returns the single user-security record for the supplied user id, if present.
     *
     * <p>This is the relational replacement for the keyed read performed by the
     * online sign-on program {@code COSGN00C}. On the mainframe that program issued
     * {@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)
     * KEYLENGTH(LENGTH OF WS-USER-ID)} &mdash; a direct keyed read on the 8-byte
     * {@code SEC-USR-ID} &mdash; and then compared the entered password to the
     * stored value. Spring Data derives the {@code WHERE user_id = ?} predicate from
     * the method name: the property path {@code secUsrId} on {@link UserSecurity}
     * resolves to the {@code user_id} primary-key column.</p>
     *
     * <p>Although {@link JpaRepository#findById(Object) findById(String)} is
     * inherited and equivalent, this explicit derived method is declared so the
     * {@code AuthenticationService} reads intention-revealingly. The
     * <strong>password is not handled here</strong>: this method only loads the
     * record by id, and the service then BCrypt-verifies the credential
     * (constraint C-003, AAP &sect;0.7.2). An absent {@link Optional} models the
     * COBOL invalid-key ({@code DFHRESP(NOTFND)}) "user not found" branch.</p>
     *
     * @param userId the 8-character user id ({@code SEC-USR-ID}, {@code PIC X(08)});
     *               maps to the {@code user_id} column
     * @return an {@link Optional} containing the matching user, or
     *         {@link Optional#empty()} if no user has that id
     */
    Optional<UserSecurity> findBySecUsrId(String userId);

    /**
     * Returns one page of users whose id is greater than or equal to the supplied
     * starting key, ordered and paged by the caller.
     *
     * <p>This is the relational replacement for the forward browse performed by the
     * online user-administration list program {@code COUSR00C}. On the mainframe
     * that program positioned the {@code USRSEC} browse with
     * {@code EXEC CICS STARTBR RIDFLD(SEC-USR-ID)} (greater-than-or-equal
     * positioning) and then issued {@code READNEXT} to fill a fixed 10-row screen
     * array ({@code USER-REC OCCURS 10 TIMES}), with PF8 paging forward and PF7
     * paging backward. Spring Data derives the {@code WHERE user_id &gt;= ?}
     * predicate from the method name (the property path {@code secUsrId} resolves to
     * the {@code user_id} primary-key column), exactly reproducing the
     * {@code STARTBR} GTEQ positioning; the page window (offset and size) and any
     * additional ordering are supplied by the caller's {@link Pageable}.</p>
     *
     * <p>The original screen displayed a fixed 10 rows per page with PF7/PF8
     * navigation; that page size is intentionally <strong>not</strong> fixed here so
     * the screen contract remains a service-layer concern (the calling service
     * supplies, for example, {@code PageRequest.of(page, 10)}).</p>
     *
     * @param userId   the inclusive lower-bound user id to browse from
     *                 ({@code SEC-USR-ID}); maps to the {@code user_id} column
     * @param pageable the paging (and optional sorting) specification supplied by
     *                 the caller
     * @return the requested page of users with id &ge; {@code userId} (possibly
     *         empty, never {@code null})
     */
    Page<UserSecurity> findBySecUsrIdGreaterThanEqual(String userId, Pageable pageable);
}
