/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.SecUserData;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port (hexagonal-architecture interface) for the {@code USRSEC}
 * user security dataset.
 *
 * <p>On the mainframe {@code USRSEC} is a VSAM KSDS with an 8-character
 * primary key ({@code SEC-USR-ID PIC X(08)}) and a fixed 80-byte record
 * length (copybook {@code app/cpy/CSUSR01Y.cpy}). The corresponding Java
 * domain record is {@link SecUserData}, which preserves the COBOL layout
 * byte-for-byte (including the plaintext {@code SEC-USR-PWD} field and
 * the trailing 23-byte {@code SEC-USR-FILLER}).
 *
 * <h2>COBOL consumers</h2>
 * Five COBOL programs exercise this dataset, and each operation in this
 * interface maps directly to one of their CICS file-control verbs:
 *
 * <ul>
 *   <li><b>COSGN00C</b> &mdash; online signon transaction {@code CC00}.
 *       Performs a single random-keyed
 *       {@code EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
 *       RIDFLD(WS-USER-ID)} ({@code app/cbl/COSGN00C.cbl:L211-L219}) and
 *       compares the returned {@code SEC-USR-PWD} against the user-typed
 *       password. Translates to {@link #findById(String)}.</li>
 *   <li><b>COUSR00C</b> &mdash; online user-list transaction with paginated
 *       browse. Uses the
 *       {@code EXEC CICS STARTBR ... READNEXT/READPREV ... ENDBR} loop
 *       ({@code app/cbl/COUSR00C.cbl:L588-L691}) to drive forward and
 *       backward pagination from a user-supplied key. Forward pagination
 *       (and full-table dumps by the batch utilities) translates to
 *       {@link #streamSequential()} and {@link #streamFrom(String)};
 *       backward pagination is handled by adapter-specific reverse
 *       iteration that is NOT exposed as a separate port method (the
 *       application layer composes it by sorting / windowing).</li>
 *   <li><b>COUSR01C</b> &mdash; online user-add transaction. Issues
 *       {@code EXEC CICS WRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)
 *       RIDFLD(SEC-USR-ID)} ({@code app/cbl/COUSR01C.cbl:L240-L248}) and
 *       distinguishes {@code DFHRESP(NORMAL)} from
 *       {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)}. Translates to
 *       {@link #insert(SecUserData)}.</li>
 *   <li><b>COUSR02C</b> &mdash; online user-update transaction. Performs
 *       {@code EXEC CICS READ ... UPDATE} ({@code
 *       app/cbl/COUSR02C.cbl:L322-L331}) followed by
 *       {@code EXEC CICS REWRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)}
 *       ({@code app/cbl/COUSR02C.cbl:L360-L366}) and distinguishes
 *       {@code DFHRESP(NORMAL)} from {@code DFHRESP(NOTFND)}. Translates
 *       to {@link #update(SecUserData)}.</li>
 *   <li><b>COUSR03C</b> &mdash; online user-delete transaction. Performs
 *       {@code EXEC CICS READ ... UPDATE} ({@code
 *       app/cbl/COUSR03C.cbl:L269-L278}) followed by
 *       {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE)} ({@code
 *       app/cbl/COUSR03C.cbl:L307-L311}) and distinguishes
 *       {@code DFHRESP(NORMAL)} from {@code DFHRESP(NOTFND)}. Translates
 *       to {@link #delete(String)}.</li>
 * </ul>
 *
 * <h2>Insert / update / delete vs. upsert</h2>
 * This port is the only repository in the CardDemo domain that exposes
 * the explicit {@code insert} / {@code update} / {@code delete} triplet
 * rather than a single {@code save(...)} upsert. The reason is that
 * {@code COUSR01C} / {@code COUSR02C} / {@code COUSR03C} each branch on a
 * distinct DFHRESP code &mdash; {@code DUPKEY} for COUSR01C,
 * {@code NOTFND} for COUSR02C and COUSR03C &mdash; and produce different
 * user-visible error messages on the BMS map. Collapsing them into a
 * single upsert would discard error-path observability that is part of
 * the COBOL behavior contract. A convenience
 * {@link #save(SecUserData)} upsert is provided as a {@code default}
 * method for seed-load utilities such as the {@code DUSRSECJ.jcl} job
 * that initially populates the {@code USRSEC} file; production callers
 * should prefer the explicit triplet.
 *
 * <h2>Plaintext password preservation (security note)</h2>
 * Per AAP &sect;0.1.3, the {@code SEC-USR-PWD} field is preserved as
 * plaintext {@code PIC X(08)} to maintain byte-for-byte parity with the
 * COBOL baseline. Any move to BCrypt / Argon2 / KDF-based password
 * hashing is explicitly OUT OF SCOPE for this migration and is tracked
 * in {@code java/MIGRATION_NOTES.md} as a follow-up effort. The
 * {@link SecUserData#toString()} accessor masks the password with
 * {@code "********"} as a defense-in-depth measure; implementations and
 * callers of this port MUST NOT emit {@link SecUserData#secUsrPwd()} to
 * any log sink. The same no-credential-in-logs principle that AAP
 * &sect;0.7.2 mandates for card PANs applies to passwords on this port.
 *
 * <h2>Stream ordering invariant</h2>
 * Both {@link #streamSequential()} and {@link #streamFrom(String)} MUST
 * return records in ascending {@code SEC-USR-ID} order (lexicographic on
 * the raw 8-byte key, matching VSAM KSDS key ordering). Any reordering
 * &mdash; including parallel-stream tricks, hash-based bucketing, or
 * concurrent fan-out on virtual threads &mdash; would change the
 * observable sequence of pagination pages in {@code COUSR00C} and is
 * therefore FORBIDDEN per AAP &sect;0.1.3.
 *
 * <h2>Stream lifecycle</h2>
 * Streams returned by {@link #streamSequential()} and {@link
 * #streamFrom(String)} typically back an underlying file channel, VSAM
 * cursor, or JDBC {@code ResultSet}. Callers MUST close them deterministically
 * (using {@code try-with-resources}, terminal {@code forEach}, or
 * explicit {@link Stream#close()}) to avoid resource leaks. The
 * {@link Stream} interface implements {@link AutoCloseable}, so {@code
 * try-with-resources} is the idiomatic pattern.
 *
 * <h2>Hexagonal placement</h2>
 * This interface is a <i>port</i> in the hexagonal-architecture sense
 * (AAP &sect;0.3.2). It belongs to the pure-domain module
 * {@code carddemo-domain} and has zero dependencies on infrastructure
 * concerns. The two known production adapters are:
 *
 * <ul>
 *   <li>{@code FileUserSecurityRepository} in
 *       {@code carddemo-adapter-file} &mdash; reads and writes
 *       fixed-width 80-byte records via {@link java.nio.file java.nio.file}
 *       (the default per AAP &sect;0.5.1).</li>
 *   <li>{@code JdbcUserSecurityRepository} in {@code carddemo-adapter-db}
 *       &mdash; optional JDBC-backed implementation, empty by default
 *       per AAP &sect;0.5.1; created only when a relational schema
 *       replaces the VSAM dataset.</li>
 * </ul>
 *
 * The composition root in {@code carddemo-app} selects which adapter to
 * wire into each use case at startup; no Spring container is used (the
 * COBOL system has no container, so per AAP &sect;0.5.1 none is
 * introduced).
 *
 * <h2>Thread-safety</h2>
 * Implementations are not required to be thread-safe; callers that need
 * concurrent access should hold a single instance per virtual thread or
 * synchronize externally. The {@link #save(SecUserData)} default method
 * is explicitly NOT atomic &mdash; see its javadoc for race semantics.
 *
 * @see SecUserData the 80-byte record carried across this port
 */
public interface UserSecurityRepository {

    /**
     * Looks up a single user by primary key (the 8-character
     * {@code SEC-USR-ID}). Mirrors the COBOL random-keyed read pattern
     * {@code EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
     * RIDFLD(WS-USER-ID)} used by COSGN00C
     * ({@code app/cbl/COSGN00C.cbl:L211-L219}), and the same read
     * without the {@code UPDATE} keyword is used implicitly by COUSR02C
     * ({@code app/cbl/COUSR02C.cbl:L322-L331}) and COUSR03C
     * ({@code app/cbl/COUSR03C.cbl:L269-L278}) as the prelude to their
     * update / delete operations.
     *
     * <h3>Return semantics</h3>
     * <ul>
     *   <li>{@code Optional.of(user)} corresponds to COBOL
     *       {@code WS-RESP-CD = 0} (DFHRESP NORMAL): the record was
     *       found and is populated.</li>
     *   <li>{@code Optional.empty()} corresponds to COBOL
     *       {@code WS-RESP-CD = 13} (DFHRESP NOTFND): no record with the
     *       given key exists. In COSGN00C this drives the
     *       "User not found. Try again ..." error path
     *       ({@code app/cbl/COSGN00C.cbl:L247-L250}); in COUSR02C and
     *       COUSR03C it drives "User ID NOT found..."
     *       ({@code app/cbl/COUSR02C.cbl:L340-L345}, {@code
     *       app/cbl/COUSR03C.cbl:L287-L292}).</li>
     * </ul>
     *
     * <h3>Key normalization</h3>
     * COBOL space-pads {@code SEC-USR-ID} to 8 characters on the right;
     * implementations SHOULD apply the same right-space-padding to the
     * input key before comparison so a caller passing {@code "ADMIN"}
     * matches the stored value {@code "ADMIN   "}. The same applies for
     * inputs strictly longer than 8 characters: implementations SHOULD
     * either truncate to 8 characters or throw
     * {@link IllegalArgumentException}; this contract does not mandate
     * one over the other.
     *
     * <h3>Error handling</h3>
     * Any COBOL {@code WS-RESP-CD} value other than {@code 0} or
     * {@code 13} (e.g., I/O failure, dataset unavailable, KSDS index
     * corruption) corresponds to an unchecked exception thrown by the
     * implementation. The exception type is adapter-specific (e.g.,
     * {@link java.io.UncheckedIOException} for the file adapter); this
     * interface does not declare a checked exception in order to match
     * the COBOL FILE STATUS handling style where I/O errors trigger
     * {@code 9999-ABEND-PROGRAM} rather than a recoverable branch.
     *
     * @param userId the 1- to 8-character user id; must not be
     *               {@code null}
     * @return an {@link Optional} containing the populated
     *         {@link SecUserData} when found, or {@link Optional#empty()}
     *         when no record exists for the given key (COBOL
     *         {@code DFHRESP(NOTFND)})
     * @throws NullPointerException if {@code userId} is {@code null}
     */
    Optional<SecUserData> findById(String userId);

    /**
     * Streams every user record in the {@code USRSEC} dataset in
     * ascending {@code SEC-USR-ID} order. Mirrors the COBOL
     * full-dataset browse pattern {@code EXEC CICS STARTBR ...
     * READNEXT ... ENDBR} used by COUSR00C
     * ({@code app/cbl/COUSR00C.cbl:L588-L689}) and the sequential file
     * read used by batch seed utilities (e.g., the IEBGENER copy of
     * {@code DUSRSECJ.jcl}).
     *
     * <h3>Ordering invariant</h3>
     * The returned stream MUST emit records in ascending key order
     * &mdash; the same order in which VSAM KSDS sequential
     * {@code READNEXT} returns them. Implementations MUST NOT reorder,
     * deduplicate, or filter the records. Any reordering changes the
     * observable pagination sequence in COUSR00C and is forbidden per
     * AAP &sect;0.1.3 (virtual-thread fan-out is not a license to
     * reorder records).
     *
     * <h3>Resource lifecycle</h3>
     * The returned {@link Stream} typically backs an open file channel,
     * VSAM cursor, or JDBC {@code ResultSet}. Callers MUST close it
     * deterministically, preferably via {@code try-with-resources}:
     *
     * <pre>{@code
     * try (Stream<SecUserData> users = repo.streamSequential()) {
     *     users.forEach(u -> ...);
     * }
     * }</pre>
     *
     * Terminal short-circuiting operations such as {@link Stream#findFirst()}
     * close the underlying source automatically.
     *
     * <h3>Empty dataset</h3>
     * If the dataset is empty (analogous to COBOL {@code DFHRESP(NOTFND)}
     * on the initial {@code STARTBR}, see
     * {@code app/cbl/COUSR00C.cbl:L600-L613}), the returned stream is
     * empty &mdash; it does NOT throw.
     *
     * <h3>Error handling</h3>
     * Implementations throw an unchecked exception (typically
     * {@link java.io.UncheckedIOException} for the file adapter) on
     * underlying I/O failures during iteration. The COBOL counterpart
     * displays {@code "Unable to lookup User..."}
     * ({@code app/cbl/COUSR00C.cbl:L608-L613}) and aborts; the Java
     * equivalent is the unchecked exception that propagates out of
     * {@link Stream#forEach}.
     *
     * @return a closeable {@link Stream} of {@link SecUserData} records
     *         in ascending key order; never {@code null}, possibly empty
     */
    Stream<SecUserData> streamSequential();

    /**
     * Streams user records in ascending {@code SEC-USR-ID} order
     * starting at or after the given key (inclusive). Mirrors the
     * COBOL {@code EXEC CICS STARTBR DATASET(WS-USRSEC-FILE)
     * RIDFLD(SEC-USR-ID)} followed by {@code READNEXT} pattern used by
     * COUSR00C ({@code app/cbl/COUSR00C.cbl:L588}) for forward
     * pagination from a user-supplied starting key.
     *
     * <h3>Equality vs. greater-than-or-equal positioning</h3>
     * VSAM {@code STARTBR} with no {@code EQUAL} qualifier (which is the
     * COUSR00C pattern, where the {@code GTEQ} is commented out at
     * {@code app/cbl/COUSR00C.cbl:L592}) positions the browse at the
     * first record whose key is greater than or equal to the supplied
     * value, with the subsequent {@code READNEXT} returning that
     * positioned record. Implementations MUST replicate this
     * inclusive-lower-bound semantics: the stream's first element, if
     * any, is the record whose key is the smallest value
     * {@code >= startUserId}.
     *
     * <h3>Null and blank handling</h3>
     * If {@code startUserId} is {@code null}, empty, or contains only
     * whitespace, this method behaves identically to
     * {@link #streamSequential()} &mdash; the stream begins at the
     * lowest key in the dataset. This matches the COBOL convention
     * where a blank {@code SEC-USR-ID} (space-filled to 8 bytes) sorts
     * to the lowest position in KSDS key order and causes
     * {@code STARTBR} to position at the first physical record.
     *
     * <h3>Past-end-of-dataset positioning</h3>
     * If {@code startUserId} is lexicographically greater than every
     * existing key, the returned stream is empty &mdash; analogous to
     * COBOL {@code DFHRESP(NOTFND)} on the initial {@code STARTBR}
     * ({@code app/cbl/COUSR00C.cbl:L600-L606}).
     *
     * <h3>Ordering invariant and resource lifecycle</h3>
     * Same as {@link #streamSequential()}: ascending key order, MUST be
     * closed by the caller (typically via {@code try-with-resources}).
     *
     * @param startUserId the inclusive starting key for the browse, or
     *                    {@code null}/blank to start at the lowest key
     * @return a closeable {@link Stream} of {@link SecUserData} records
     *         in ascending key order starting at or after
     *         {@code startUserId}; never {@code null}, possibly empty
     */
    Stream<SecUserData> streamFrom(String startUserId);

    /**
     * Inserts a new user record. Mirrors the COBOL {@code EXEC CICS
     * WRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)
     * RIDFLD(SEC-USR-ID)} pattern in COUSR01C ({@code
     * app/cbl/COUSR01C.cbl:L240-L248}).
     *
     * <h3>Duplicate-key contract</h3>
     * If a record with the same {@code SEC-USR-ID} already exists,
     * implementations MUST throw an unchecked exception (typically
     * {@link IllegalStateException}, or an adapter-defined sealed
     * subtype). Both COBOL {@code DFHRESP(DUPKEY)} and
     * {@code DFHRESP(DUPREC)} translate to this exception &mdash; the
     * COUSR01C error path
     * ({@code app/cbl/COUSR01C.cbl:L260+}) displays the user-visible
     * message {@code "User ID already exists..."} regardless of which
     * of the two duplicate-related response codes the file system
     * returns. The exception MUST carry the offending {@code
     * SEC-USR-ID} in its message to aid diagnostics, with the
     * password masked to prevent credential leakage.
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>{@code user} must not be {@code null}.</li>
     *   <li>{@code user.secUsrId()} must not be {@code null} or blank
     *       (the COBOL {@code SEC-USR-ID PIC X(08)} field would be
     *       space-filled, and the COUSR01C add screen rejects blank IDs
     *       before reaching the WRITE).</li>
     * </ul>
     *
     * <h3>Atomicity</h3>
     * The duplicate-key check and the actual write SHOULD be atomic
     * from the perspective of a single caller. Implementations backed
     * by VSAM or a relational database satisfy this naturally; in-memory
     * adapters MUST synchronize the check-then-write internally if they
     * are exposed to multiple threads.
     *
     * <h3>Error handling for non-duplicate failures</h3>
     * Any COBOL {@code WS-RESP-CD} value indicating an I/O failure
     * (dataset unavailable, KSDS index corruption, disk full, etc.)
     * corresponds to an adapter-specific unchecked exception
     * (typically {@link java.io.UncheckedIOException}).
     *
     * @param user the user record to insert; must not be {@code null}
     *             and must have a non-blank {@code secUsrId()}
     * @throws NullPointerException     if {@code user} is {@code null}
     * @throws IllegalArgumentException if {@code user.secUsrId()} is
     *                                  blank
     * @throws IllegalStateException    if a record with the same
     *                                  {@code SEC-USR-ID} already exists
     *                                  (COBOL {@code DFHRESP(DUPKEY)} or
     *                                  {@code DFHRESP(DUPREC)})
     */
    void insert(SecUserData user);

    /**
     * Updates an existing user record (matched by {@code SEC-USR-ID}).
     * Mirrors the COBOL two-step pattern in COUSR02C:
     * {@code EXEC CICS READ ... UPDATE}
     * ({@code app/cbl/COUSR02C.cbl:L322-L331}) followed by
     * {@code EXEC CICS REWRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)}
     * ({@code app/cbl/COUSR02C.cbl:L360-L366}).
     *
     * <h3>Not-found contract</h3>
     * If no record with the given {@code SEC-USR-ID} exists, the
     * implementation MUST throw an unchecked exception &mdash; the
     * conventional choice is {@link java.util.NoSuchElementException},
     * mirroring the COUSR02C {@code DFHRESP(NOTFND)} branch
     * ({@code app/cbl/COUSR02C.cbl:L340-L345}) which displays the
     * user-visible message {@code "User ID NOT found..."}.
     *
     * <h3>Read-then-rewrite locking semantics</h3>
     * On the mainframe, {@code EXEC CICS READ ... UPDATE} acquires an
     * exclusive lock on the record that is released by the subsequent
     * {@code REWRITE} (or {@code UNLOCK} on rollback). File-based
     * Java adapters MAY implement update as an unsynchronized
     * read-then-rewrite over the same byte channel; JDBC adapters
     * relying on transactional isolation MAY use {@code SELECT ... FOR
     * UPDATE}. <b>Callers MUST NOT rely on cross-method record locking
     * being provided by this port</b> &mdash; if strict
     * atomicity-against-concurrent-writers is required, callers must
     * synchronize externally or use an adapter that explicitly
     * documents stronger guarantees.
     *
     * <h3>Identity preservation</h3>
     * The {@code SEC-USR-ID} field of the supplied {@code user} record
     * IS the lookup key; implementations MUST NOT permit changing the
     * primary key via {@code update}. To change a user's ID the caller
     * must {@link #delete(String) delete} the old record and
     * {@link #insert(SecUserData) insert} a new one. This matches the
     * COBOL convention where {@code SEC-USR-ID} is bound as the
     * {@code RIDFLD} of {@code REWRITE} and cannot diverge from the
     * key under which the prior {@code READ ... UPDATE} positioned the
     * record.
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>{@code user} must not be {@code null}.</li>
     *   <li>{@code user.secUsrId()} must not be {@code null} or blank.</li>
     * </ul>
     *
     * @param user the user record carrying the updated field values;
     *             must not be {@code null} and must have a non-blank
     *             {@code secUsrId()}
     * @throws NullPointerException                if {@code user} is
     *                                             {@code null}
     * @throws IllegalArgumentException            if
     *                                             {@code user.secUsrId()}
     *                                             is blank
     * @throws java.util.NoSuchElementException    if no record with the
     *                                             given
     *                                             {@code SEC-USR-ID}
     *                                             exists (COBOL
     *                                             {@code DFHRESP(NOTFND)})
     */
    void update(SecUserData user);

    /**
     * Convenience upsert that inserts the record if no row with the
     * given {@code SEC-USR-ID} exists, or updates the existing row if
     * it does. Composed from {@link #findById(String)},
     * {@link #insert(SecUserData)}, and {@link #update(SecUserData)};
     * this is NOT a direct translation of any single COBOL CICS verb.
     *
     * <p>This is provided as a {@code default} method (one of the very
     * few allowed default methods on a domain port, per the rule that
     * defaults are permitted only for simple, obvious composition of
     * other methods) so adapters do not need to re-implement the
     * decision logic. It is the right entry point for seed-load
     * utilities such as the {@code DUSRSECJ.jcl} job that initially
     * populates {@code USRSEC} from the
     * {@code app/data/ASCII/} fixture &mdash; in that context, the
     * caller does not know whether the file is being rebuilt from
     * scratch (insert path) or topped-up incrementally (update path).
     *
     * <h3>Atomicity caveat (race conditions)</h3>
     * Because {@code save} is implemented as
     * <i>findById-then-insert-or-update</i>, there is a TOCTOU
     * (time-of-check-to-time-of-use) window between the lookup and the
     * subsequent write. Concurrent callers may both observe
     * {@code findById(...).isEmpty() == true} and then race on
     * {@link #insert(SecUserData)}, in which case the second caller
     * will receive an {@link IllegalStateException}. Likewise, a
     * concurrent {@link #delete(String)} between the lookup and the
     * write can cause {@link #update(SecUserData)} to throw
     * {@link java.util.NoSuchElementException}. Production callers that
     * require strict atomicity should invoke
     * {@link #insert(SecUserData)} or {@link #update(SecUserData)}
     * directly and handle the duplicate-key / not-found exception
     * paths explicitly.
     *
     * <h3>Behavior summary</h3>
     * <table border="1">
     *   <caption>save(user) behavior</caption>
     *   <tr><th>findById(user.secUsrId())</th><th>delegated call</th></tr>
     *   <tr><td>present</td><td>{@link #update(SecUserData)}</td></tr>
     *   <tr><td>empty</td><td>{@link #insert(SecUserData)}</td></tr>
     * </table>
     *
     * @param user the user record to save; must not be {@code null}
     *             and must have a non-blank {@code secUsrId()}
     * @throws NullPointerException     if {@code user} is {@code null}
     * @throws IllegalArgumentException if {@code user.secUsrId()} is
     *                                  blank, or per the delegated
     *                                  {@code insert} / {@code update}
     *                                  contract
     * @throws IllegalStateException    if a concurrent insert races and
     *                                  wins between the lookup and the
     *                                  delegated {@code insert} call
     * @throws java.util.NoSuchElementException
     *                                  if a concurrent delete races and
     *                                  wins between the lookup and the
     *                                  delegated {@code update} call
     */
    default void save(SecUserData user) {
        java.util.Objects.requireNonNull(user, "user");
        if (findById(user.secUsrId()).isPresent()) {
            update(user);
        } else {
            insert(user);
        }
    }

    /**
     * Deletes the user record with the given {@code SEC-USR-ID}.
     * Mirrors the COBOL two-step pattern in COUSR03C:
     * {@code EXEC CICS READ ... UPDATE}
     * ({@code app/cbl/COUSR03C.cbl:L269-L278}) followed by
     * {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE)}
     * ({@code app/cbl/COUSR03C.cbl:L307-L311}).
     *
     * <h3>Not-found contract</h3>
     * If no record with the given {@code userId} exists, the
     * implementation MUST throw an unchecked exception &mdash; the
     * conventional choice is {@link java.util.NoSuchElementException},
     * mirroring the COUSR03C {@code DFHRESP(NOTFND)} branch
     * ({@code app/cbl/COUSR03C.cbl:L287-L292}) which displays the
     * user-visible message {@code "User ID NOT found..."}.
     *
     * <h3>Key normalization</h3>
     * As with {@link #findById(String)}, implementations SHOULD apply
     * COBOL-style right-space-padding to the input key before lookup
     * so a caller passing {@code "ADMIN"} matches the stored value
     * {@code "ADMIN   "}.
     *
     * <h3>Atomicity</h3>
     * The lookup and the actual delete SHOULD be atomic from the
     * perspective of a single caller. Implementations backed by VSAM
     * or a relational database satisfy this through their native
     * {@code READ ... UPDATE} / {@code DELETE} locking; in-memory
     * adapters MUST synchronize the operation internally if they are
     * exposed to multiple threads.
     *
     * <h3>Error handling for non-not-found failures</h3>
     * Any COBOL {@code WS-RESP-CD} value indicating an I/O failure
     * (dataset unavailable, KSDS index corruption, etc.) corresponds
     * to an adapter-specific unchecked exception (typically
     * {@link java.io.UncheckedIOException}).
     *
     * @param userId the 1- to 8-character user id of the record to
     *               delete; must not be {@code null} or blank
     * @throws NullPointerException     if {@code userId} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code userId} is blank
     * @throws java.util.NoSuchElementException
     *                                  if no record with the given
     *                                  {@code SEC-USR-ID} exists (COBOL
     *                                  {@code DFHRESP(NOTFND)})
     */
    void delete(String userId);
}
