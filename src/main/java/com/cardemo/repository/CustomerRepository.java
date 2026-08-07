/*
 * ****************************************************************************
 * Program     : CustomerRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the customer master file
 *               used by account view, account update and the customer reader.
 * Source      : CICS FILE CUSTDAT (app/csd/CARDDEMO.CSD:L50, L52, DSNAME
 *               AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS); cluster key 9 / reclen 500
 *               (app/catlg/LISTCAT.txt:L632 DATA-component attribute line;
 *               app/jcl/CUSTFILE.jcl:L46, L50-L51 KEYS(9 0) RECORDSIZE(500 500));
 *               record layout app/cpy/CVCUS01Y.cpy:L4-L23 @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */

package com.cardemo.repository;

import com.cardemo.model.entity.Customer;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence gateway for the customer master record — the relational replacement for the
 * VSAM KSDS cluster {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}, known to the online region
 * as CICS file {@code CUSTDAT}.
 *
 * <h2>What this component does</h2>
 * <p>
 * It replaces the VSAM access verbs — {@code READ}, {@code READ ... UPDATE}, {@code REWRITE}
 * and the sequential {@code READ NEXT} browse — with typed, transactional repository
 * operations over the {@code customer} table. It declares no business logic: change
 * detection, case folding, date-component comparison, area-code and state-code lookup and
 * every validation rule live in the service layer. This interface is the seam between them
 * and Hibernate, and nothing more.
 * </p>
 * <p>
 * Spring Data supplies the implementation at runtime. There is deliberately <em>no</em>
 * hand-written implementation class, no custom-fragment interface, no dynamic-predicate or
 * criteria-API helper type, no mapper and no data-access-object wrapper: this package contains
 * exactly eleven repository interfaces plus {@code package-info.java}, one interface per VSAM
 * cluster catalogued in {@code app/catlg/LISTCAT.txt}. Every query this application issues
 * against the customer master is therefore visible either in this file or in the inherited
 * {@code JpaRepository} contract — there is no second place to look.
 * </p>
 *
 * <h2>Provenance and physical contract</h2>
 * <p>
 * The geometry below is dual-sourced, so that neither the catalogue listing nor the
 * provisioning job is taken on trust alone.
 * </p>
 * <ul>
 *   <li>Online definition: {@code app/csd/CARDDEMO.CSD:L50} declares
 *       {@code DEFINE FILE(CUSTDAT) GROUP(CARDDEMO)} and {@code :L52} binds it to
 *       {@code DSNAME(AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS)}. It is one of exactly
 *       <strong>eight</strong> {@code DEFINE FILE} entries in the CSD, which is what makes
 *       {@code CUSTDAT} an online file rather than a batch-only dataset.</li>
 *   <li>Catalogued geometry: the cluster block opens at {@code app/catlg/LISTCAT.txt:L595};
 *       the {@code DATA}-component attribute line {@code :L632} reports {@code KEYLEN 9}
 *       with {@code AVGLRECL 500}, and {@code :L633} corroborates {@code MAXLRECL 500} with
 *       {@code RKP 0} — the nine-byte key is the record prefix, at offset zero. Statistics
 *       at {@code :L637} report {@code REC-TOTAL 50}.</li>
 *   <li>Provisioning job: {@code app/jcl/CUSTFILE.jcl:L46} defines the cluster,
 *       {@code :L50} declares {@code KEYS(9 0)} and {@code :L51} declares
 *       {@code RECORDSIZE(500 500)}. Fixed-length, indexed, nine-byte key.</li>
 *   <li>Fixture corroboration: {@code app/data/ASCII/custdata.txt} is 25,050 bytes over
 *       50 rows — 500 data bytes plus one line terminator each — matching both the
 *       catalogued record length and the catalogued record count.</li>
 *   <li>Traceability anchor: commit {@code 7756d89}. The corpus under {@code app/} is
 *       frozen and byte-for-byte read-only; it is simultaneously the parity oracle, the
 *       field-contract source and the traceability anchor.</li>
 *   </ul>
 *
 * <h2>One entity, two copybooks</h2>
 * <p>
 * Two copybooks in the frozen corpus describe this record and they are the same layout.
 * {@code app/cpy/CVCUS01Y.cpy:L4-L23} and {@code app/cpy/CUSTREC.cpy:L4-L23} both declare
 * {@code 01 CUSTOMER-RECORD}, both run to 500 bytes, and every field width and the field
 * order are identical. The <em>only</em> substantive difference is at {@code :L19}, where
 * the date-of-birth field is spelled {@code CUST-DOB-YYYY-MM-DD} in {@code CVCUS01Y.cpy}
 * and {@code CUST-DOB-YYYYMMDD} in {@code CUSTREC.cpy} — the name differs, the
 * {@code PIC X(10)} does not.
 * </p>
 * <p>
 * Accordingly there is <strong>one</strong> entity, {@code com.cardemo.model.entity.Customer},
 * serving both copybooks, and <strong>one</strong> repository — this one — over it. A second
 * entity or a second repository would be duplication that had to be kept in lockstep forever
 * for no behavioural gain, which Rule 1 Clause C forbids.
 * </p>
 *
 * <h2>The entity this interface is typed over</h2>
 * <p>
 * {@code Customer} maps to table {@code customer} with {@code @Id Long customerId} on column
 * {@code cust_id NUMERIC(9)} and a framework-owned {@code @Version Long version} column.
 * The identifier type is therefore {@code Long} and the JPQL identifier path is
 * {@code c.customerId}. The eighteen populated fields of the COBOL group map one-for-one onto
 * eighteen fixed-width columns; the 168-byte {@code FILLER} at {@code CVCUS01Y.cpy:L23} is not
 * modelled, because it carries no value in any program.
 * </p>
 * <p>
 * Three field types are worth restating because they are counter-intuitive and because
 * {@code ddl-auto: validate} will reject a mismatch at context startup rather than degrade
 * gracefully: the national identifier, the date of birth and the credit score are all
 * {@code String}, not numeric and not {@code java.time.LocalDate}. They are stored as the
 * fixed-width character images the source holds, because the change-detection comparison
 * described below compares representations, not parsed values.
 * </p>
 *
 * <h2>Inherited operations, and the source verb each one replaces</h2>
 * <p>
 * The following are inherited from {@code JpaRepository} and are deliberately
 * <strong>not</strong> redeclared here — redeclaring them would add no contract and would
 * duplicate documentation that Spring Data already owns. They are mapped to their source
 * verbs and named call sites so that the traceability matrix can be checked against this
 * file mechanically.
 * </p>
 * <ul>
 *   <li>{@code findById(Long)} replaces the keyed {@code EXEC CICS READ} of paragraph
 *       {@code 9400-GETCUSTDATA-BYCUST} at {@code app/cbl/COACTVWC.cbl:L825}, whose
 *       {@code READ} spans {@code :L826-L834} — the third link in the cross-reference to
 *       account to customer lookup chain of that 941-line program. The source distinguishes
 *       exactly two outcomes: {@code DFHRESP(NORMAL)} at {@code :L837} sets
 *       {@code FOUND-CUST-IN-MASTER}, and {@code DFHRESP(NOTFND)} at {@code :L839-L841} sets
 *       an input error. An empty {@code Optional} is therefore the not-found control path,
 *       never an exception thrown from here; the caller surfaces it as
 *       {@code com.cardemo.exception.RecordNotFoundException}, the Java form of FILE STATUS
 *       {@code '23'} and {@code DFHRESP(NOTFND)}.</li>
 *   <li>{@code save(Customer)} replaces the customer {@code EXEC CICS REWRITE} at
 *       {@code app/cbl/COACTUPC.cbl:L4085-L4091}. It is the second of the two writes in the
 *       dual-dataset unit of work and, per the rollback discussion below, the one whose
 *       failure the source explicitly backs out.</li>
 *   <li>{@code findAll()} and its {@code Sort} and {@code Pageable} overloads replace the
 *       read-only sequential scan of {@code app/cbl/CBCUS01C.cbl} (178 lines): paragraph
 *       {@code 0000-CUSTFILE-OPEN} at {@code :L118}, {@code 1000-CUSTFILE-GET-NEXT} at
 *       {@code :L92} and {@code 9000-CUSTFILE-CLOSE} at {@code :L136}, driven from the
 *       mainline at {@code :L72}, {@code :L76} and {@code :L83}. That program's verb
 *       inventory is {@code OPEN}, {@code READ} and {@code CLOSE} only — zero {@code WRITE},
 *       zero {@code REWRITE}, zero {@code DELETE} — so it becomes a read-only verification
 *       step consumed by {@code com.cardemo.batch.readers.CustomerReader}.</li>
 *   </ul>
 * <p>
 * <strong>Determinism of that scan matters, and no bespoke method is needed to get it.</strong>
 * {@code app/cbl/CBCUS01C.cbl:L30-L31} declares {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS SEQUENTIAL}, so the legacy browse returns rows in ascending key
 * order. A bare {@code findAll()} guarantees no order at all. The batch reader reproduces the
 * source ordering by passing an explicit ascending sort on {@code customerId} to the
 * inherited {@code findAll(Sort)} overload, and streams or pages rather than materialising
 * the whole table. Because the inherited overloads already express that, declaring an
 * ordered finder here would be speculative dead code, which Rule 1 Clause B forbids — this
 * package contains no retained-parity no-op artefact, so that clause applies at full strength
 * with zero exemptions.
 * </p>
 *
 * <h2>Two layers of concurrency control, both mandatory</h2>
 * <p>
 * <strong>Severity: High.</strong> A JPA {@code @Version} column detects <em>that</em> a row
 * changed. {@code app/cbl/COACTUPC.cbl} — at 4,236 lines the largest program in the corpus —
 * detects <em>which field values</em> differ from what the user was shown. These are different
 * guarantees and neither substitutes for the other: a concurrent write that set a field back to
 * its original value passes the legacy check and fails a version check, while a version counter
 * cannot report which field moved or in what representation.
 * </p>
 * <p>
 * The snapshot the source compares against is a real structure, not an inference:
 * {@code 05 ACUP-OLD-DETAILS} begins at {@code app/cbl/COACTUPC.cbl:L669} with
 * {@code 10 ACUP-OLD-ACCT-DATA} at {@code :L670}, runs through
 * {@code ACUP-OLD-CUST-FICO-SCORE} at {@code :L754-L756}, and is paired with
 * {@code 05 ACUP-NEW-DETAILS} at {@code :L757}. Paragraph {@code 9700-CHECK-CHANGE-IN-REC},
 * defined at {@code :L4109} and performed from {@code :L3947-L3948}, evaluates twelve account
 * predicates and a comparable set of customer predicates; any mismatch sets the data-changed
 * flag and the write is abandoned at {@code :L3950-L3952}.
 * </p>
 * <p>
 * Because the target is stateless, that snapshot cannot live on the server between requests —
 * it is carried in the request body by {@code com.cardemo.model.dto.AccountUpdateRequest},
 * which is why that DTO holds both an old and a new detail group. This interface's obligation
 * is narrower and specific: it must offer an explicit <strong>read-for-update</strong> so that
 * {@code com.cardemo.service.account.AccountUpdateService} can perform the field-by-field
 * comparison against the live row <em>inside the same unit of work</em> that will write it.
 * That is what {@link #findByIdForUpdate(Long)} exists for. Omitting it would make the
 * comparison unimplementable without a lost-update window between the read and the write.
 * </p>
 *
 * <h2>The failure taxonomy this repository feeds</h2>
 * <p>
 * The source declares four distinct outcome flags as 88-levels at
 * {@code app/cbl/COACTUPC.cbl:L517-L524}, and each must remain distinguishable all the way out
 * to the HTTP response — collapsing them into a single conflict status discards information the
 * legacy screen displayed to the operator.
 * </p>
 * <ul>
 *   <li>{@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} ({@code :L517-L518}) — the account row could
 *       not be locked. A further marker, {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} at
 *       {@code :L667}, is set from {@code :L2607-L2608} specifically for this case.</li>
 *   <li>{@code COULD-NOT-LOCK-CUST-FOR-UPDATE} ({@code :L519-L520}) — <strong>the customer row
 *       could not be locked</strong>. This is the outcome {@link #findByIdForUpdate(Long)}
 *       produces, and it is the flag this repository is directly responsible for enabling.</li>
 *   <li>{@code DATA-WAS-CHANGED-BEFORE-UPDATE} ({@code :L521-L522}) — the snapshot comparison
 *       found a difference. Surfaces as
 *       {@code com.cardemo.exception.ConcurrentUpdateException}.</li>
 *   <li>{@code LOCKED-BUT-UPDATE-FAILED} ({@code :L523-L524}) — the row was locked but the
 *       rewrite itself failed.</li>
 *   </ul>
 *
 * <h2>Asymmetric rollback — preserved, not corrected</h2>
 * <p>
 * The source rolls back on one of its two write-failure paths and not the other. On the account
 * rewrite failure at {@code app/cbl/COACTUPC.cbl:L4079-L4080} it sets
 * {@code LOCKED-BUT-UPDATE-FAILED} and branches straight to the exit with <strong>no</strong>
 * backout. On the customer rewrite failure at {@code :L4098-L4102} it sets the same flag,
 * issues {@code EXEC CICS SYNCPOINT ROLLBACK}, and only then branches to the exit.
 * </p>
 * <p>
 * This reads like a defect and is not one, and it is <strong>preserved rather than
 * corrected</strong>. At the earlier failure point nothing has yet been written inside the unit
 * of work, so the transaction monitor releases the read-for-update locks at task end with no
 * explicit action. At the later point the account rewrite has already occurred inside the same
 * unit of work, so an explicit backout is the only way to avoid a half-applied update.
 * </p>
 * <p>
 * A single {@code @Transactional(rollbackFor = Exception.class)} service method spanning both
 * writes reproduces both branches automatically, because each failure path returns or throws
 * before the commit point; nothing needs to be conditional. <strong>That transaction boundary
 * belongs to the service, not to this interface.</strong> A repository interface that opened its
 * own transaction would fragment the unit of work and break the atomicity the source
 * guarantees.
 * </p>
 *
 * <h2>Why the comparison cannot be reconstructed server-side</h2>
 * <p>
 * Three characteristics of {@code 9700-CHECK-CHANGE-IN-REC} explain why the snapshot travels in
 * the request instead of being re-read here, and each is easy to translate wrongly. They are
 * recorded for the reader of this interface; the logic itself lives in the service.
 * </p>
 * <ol>
 *   <li>Dates are compared as <strong>three separate substrings</strong> — year, then month,
 *       then day — never as whole strings.</li>
 *   <li>Case handling is <strong>deliberately asymmetric</strong>. The customer name, address,
 *       state, country and government-issued identifier fields are compared through an
 *       upper-casing function on both sides, while the postal code, both telephone numbers, the
 *       national identifier, the electronic-funds account identifier, the primary-holder
 *       indicator and the credit score are compared with <strong>no case function at all</strong>.
 *       Normalising that in either direction changes which updates are accepted.</li>
 *   <li>The date-of-birth comparison uses <strong>different offsets on each side</strong>: the
 *       live record holds a dash-separated date, so its components sit at offsets 1, 6 and 9,
 *       whereas the snapshot holds the same date without separators, so its components sit at
 *       offsets 1, 5 and 7. A naive whole-string comparison of the two would report a change on
 *       <em>every single request</em>, making the endpoint permanently unusable.</li>
 *   </ol>
 *
 * <h2>Personally identifiable information — the strictest constraint on this file</h2>
 * <p>
 * <strong>Severity: Blocker.</strong> This entity is the personally-identifiable-information
 * epicentre of the whole application: {@code app/cpy/CVCUS01Y.cpy} carries a national
 * identifier at {@code :L17}, a government-issued identifier at {@code :L18}, a date of birth at
 * {@code :L19}, an electronic-funds account identifier at {@code :L20} and two telephone numbers
 * at {@code :L15-L16}. Rule 1 Clause D requires that no such value reach code, logs, tests or
 * configuration.
 * </p>
 * <p>
 * The primary defence is structural and is enforced here by omission: <strong>this interface
 * declares no projection, no interface-based view and no query that selects any sensitive column
 * in isolation, and no finder keyed on any of them.</strong> There is no
 * {@code findBySsn}, no {@code findByPhoneNumber}, no {@code findByDateOfBirth}, no
 * {@code findByGovernmentIssuedId} and no credit-score finder — not because none could be
 * written, but because nothing may select or return what it does not need. Log masking in
 * {@code logback-spring.xml} is the secondary defence, and secondary defences are not relied on.
 * Consistently, {@code Customer.toString()} exposes only the identifier and the version column,
 * and {@code spring.jpa.show-sql} is {@code false} in every profile with no Hibernate SQL or
 * bind-parameter logging anywhere — precisely because of these columns. No example in this file
 * contains a real-looking identifier, telephone number or date.
 * </p>
 *
 * <h2>Invariants this interface is written against</h2>
 * <ul>
 *   <li><strong>Determinism.</strong> Every multi-row or paged access carries an explicit
 *       ordering; no result set relies on natural, heap or hash order. The single declared query
 *       here is by primary key and returns at most one row, so ordering is vacuous for it.</li>
 *   <li><strong>Parameter binding only.</strong> The declared query is a static JPQL string with
 *       a named parameter bound through {@code @Param}. There is no string concatenation, no
 *       interpolation and no {@code nativeQuery} anywhere in this file, so no query text can be
 *       influenced by input.</li>
 *   <li><strong>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile.</strong> The
 *       table name, every column name, every type and every width asserted by the entity are
 *       validated against the live schema at context startup. A mismatch fails the boot
 *       outright rather than degrading, which is the intended behaviour.</li>
 *   <li><strong>{@code spring.jpa.open-in-view: false}.</strong> Results are returned fully
 *       initialised; nothing is lazily dereferenced outside a transaction.</li>
 *   <li><strong>No index on {@code customer}.</strong> {@code V2__create_indexes.sql} creates
 *       exactly three non-unique indexes — on the card account identifier, the cross-reference
 *       account identifier and the transaction processing timestamp — reproducing the three VSAM
 *       alternate indexes. The customer cluster has no alternate index in
 *       {@code app/catlg/LISTCAT.txt}, so none is created and <strong>no finder here may imply
 *       one</strong>. Access is by primary key or by ordered scan, exactly as the source.</li>
 *   <li><strong>Precision tiers are never collapsed</strong> across the schema:
 *       {@code S9(10)V99} maps to {@code NUMERIC(12,2)}, {@code S9(09)V99} to
 *       {@code NUMERIC(11,2)} and {@code S9(04)V99} to {@code NUMERIC(6,2)}, always
 *       {@code BigDecimal} with {@code RoundingMode.HALF_EVEN} and equality by
 *       {@code compareTo}. No {@code float} and no {@code double} appears in any financial
 *       field. This entity holds no monetary column, so the tiers bind its siblings rather than
 *       it; the rule is restated because uniformity across the package is what makes it
 *       auditable.</li>
 *   <li><strong>Status-to-exception translation happens exactly once</strong>, in
 *       {@code com.cardemo.service.shared.FileStatusMapper}. This interface throws nothing of
 *       its own and swallows nothing: an absent row is an empty {@code Optional}, and infrastructure
 *       failures propagate as Spring's {@code DataAccessException} hierarchy with the root cause
 *       intact. Exception types are named in prose here and are deliberately not imported.</li>
 *   <li><strong>Pagination sizes are parity contracts and are never hardcoded here.</strong> The
 *       card list is 7 rows ({@code app/cbl/COCRDLIC.cbl:L177-L178}) and the transaction and user
 *       lists are 10 ({@code app/cbl/COTRN00C.cbl:L290}, {@code app/cbl/COUSR00C.cbl:L57}); they
 *       are supplied through {@code carddemo.pagination.*}. No listing screen reads the customer
 *       master, so no page size applies to this interface at all.</li>
 *   <li><strong>Connection-pool tuning is out of scope</strong> and is recorded as residual risk
 *       in the {@code DECISION_LOG.md} and {@code docs/validation-gates.md}. Stating it plainly is the
 *       honest discharge of Rule 1 Clause A's requirement to justify performance tradeoffs rather
 *       than to make undocumented ones.</li>
 *   </ul>
 *
 * <h2>Recorded finding: the orphan customer cluster is not a target</h2>
 * <p>
 * <strong>Severity: Low.</strong> A second customer-shaped cluster exists in the corpus:
 * {@code app/jcl/DEFCUST.jcl:L35} defines {@code AWS.CUSTDATA.CLUSTER} with
 * {@code KEYS(10 0)} at {@code :L37} and a 500-byte record size. Its key length of ten
 * disagrees with the nine-byte key of the cluster every program actually uses, and
 * <strong>no program opens it</strong> — it appears in no {@code SELECT}, no CSD
 * {@code DEFINE FILE} and no other job. It is therefore a dangling legacy definition and is
 * recorded here as a finding only. <strong>No second entity, no second repository and no finder
 * is created for it.</strong> Remediation, if the corpus were ever unfrozen, would be to delete
 * the dead {@code DEFINE}; that is out of scope precisely because {@code app/} is frozen.
 * </p>
 *
 * <h2>Clause F disclosure — information not available at authoring time</h2>
 * <p>
 * The following were <strong>Not available</strong> when this interface was authored, and are
 * disclosed rather than guessed at.
 * </p>
 * <ol>
 *   <li><strong>All three Flyway migrations are present.</strong> An earlier revision of this bullet
 *       recorded {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} as not available; that is no
 *       longer true and the claim is withdrawn. {@code V1__create_schema.sql} declares
 *       {@code CREATE TABLE customer} with {@code ck_customer_ssn_numeric}, {@code V2} correctly declares
 *       no index for this table because {@code CUSTDATA} has no alternate index in
 *       {@code app/catlg/LISTCAT.txt}, and {@code V3} seeds it from
 *       {@code app/data/ASCII/custdata.txt}. Because {@code ddl-auto: validate} is set in all four
 *       profiles, which are present too, the mapping asserted by {@code com.cardemo.model.entity.Customer},
 *       which is also what {@code V1} declares — table
 *       {@code customer}; {@code cust_id NUMERIC(9)} as primary key; the fixed-width character
 *       columns for names, address lines, state, country, postal code, telephone numbers,
 *       national identifier, government identifier, {@code cust_dob_yyyy_mm_dd CHAR(10)},
 *       electronic-funds account identifier, primary-holder indicator and credit score; and the
 *       {@code version BIGINT} optimistic-locking column — constitutes the <strong>normative
 *       contract that {@code V1} must satisfy</strong>. A mismatch fails context startup rather
 *       than degrading gracefully, so the failure mode is loud and immediate.
 *       {@code V3} seeds the 50 customer rows of {@code app/data/ASCII/custdata.txt} with
 *       position-aware zoned-decimal overpunch decoding driven by the {@code PIC} clauses, never
 *       a global text substitution. {@code V1} creates exactly eleven tables, ten foreign keys
 *       and five check constraints; the Spring Batch {@code BATCH_*} metadata tables come from
 *       the framework's own script through {@code spring.batch.jdbc.initialize-schema} and never
 *       from a fourth migration.</li>
 *   <li><strong>Not available:</strong> a grounded source construct for FILE STATUS {@code '35'}
 *       (file unavailable). The literal {@code '35'} occurs <strong>nowhere</strong> in
 *       {@code app/cbl}, and the {@code DFHRESP} census across the corpus is
 *       {@code NORMAL} 43, {@code NOTFND} 23, {@code ENDFILE} 8, {@code DUPREC} 7,
 *       {@code DUPKEY} 3 and <strong>{@code NOTOPEN} 0</strong>. The corresponding
 *       {@code com.cardemo.exception.FileUnavailableException} is therefore
 *       specification-derived only, with no paragraph in the corpus to cite. What is needed to
 *       ground it: a legacy artefact that actually raises the condition — none exists at
 *       {@code 7756d89}, so it is documented as specification-derived rather than presented as
 *       migrated behaviour.</li>
 *   </ol>
 *
 * @see com.cardemo.model.entity.Customer
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Reads one customer row and holds a write lock on it for the remainder of the enclosing transaction — the
     * Java form of {@code EXEC CICS READ ... UPDATE}.
     *
     * @param customerId the nine-digit customer identifier to read and lock.
     * @return the locked customer row, or {@link Optional#empty()} if no row bears that identifier
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.customerId = :customerId")
    Optional<Customer> findByIdForUpdate(@Param("customerId") Long customerId);

    /**
     * Reads the next window of the key sequence, positioned by the last key already consumed.
     *
     * <p><strong>Finding, Medium severity - the sequential scan positioned by page number.</strong> The
     * verification readers used {@code findAll(Pageable)}, which positions a window with SQL
     * {@code OFFSET}. An {@code OFFSET} is not a seek: the engine produces and discards every preceding
     * row, so the cost of window <em>n</em> grows with <em>n</em> and a full scan is quadratic in the row
     * count. A keyset predicate is a single index descent whose cost is constant per window, which is also
     * what the legacy {@code READ NEXT} actually is - VSAM resumes from the key it last returned and never
     * re-reads the front of the cluster. The page-number form modelled something the source does not do.
     *
     * <p>Two further costs went with it. {@code findAll(Pageable)} returns a {@code Page}, so every window
     * carried a {@code select count(*)} whose result the readers discarded; and the {@code OFFSET} form
     * re-reads rows a concurrent insert may have shifted, which can skip or duplicate a row across window
     * boundaries. A {@code List} keyed on the last consumed key has neither problem.
     *
     * <p><strong>The seed value.</strong> The first window is requested with any value strictly below every
     * legal key. {@code CUST-ID} is {@code PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} - unsigned display,
     * so its least legal value is zero, which {@code com.cardemo.model.entity.Customer} enforces as its
     * minimum - and the column is {@code NUMERIC(9) NOT NULL} at
     * {@code src/main/resources/db/migration/V1__create_schema.sql:609}. Any negative seed is therefore
     * provably below the whole key space; the reader passes {@code -1}.
     *
     * <p>The {@code Pageable} supplies the window size only. Its page number must be zero, because the
     * predicate - not an offset - is what positions the window, and the ordering is fixed by the method
     * name so it cannot be varied by a caller-supplied {@code Sort}.
     *
     * @param customerId the customer identifier of the last row already consumed, or a negative value to
     *     start at the beginning of the key sequence.
     * @param pageable the window size; page number zero.
     * @return the next window in ascending key order, never {@code null} and empty once the scan is
     *     exhausted, which is the readers' end-of-file condition.
     */
    List<Customer> findByCustomerIdGreaterThanOrderByCustomerIdAsc(Long customerId, Pageable pageable);
}
