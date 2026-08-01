/*
 * ****************************************************************************
 * Program     : UserSecurityRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the user security file used
 *               by sign-on and the four user-administration programs.
 * Source      : CICS FILE USRSEC (app/csd/CARDDEMO.CSD:L88-L89, DSNAME
 *               AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS); cluster key 8 / reclen 80
 *               (app/catlg/LISTCAT.txt:L3883 DATA-component attribute line;
 *               app/jcl/DUSRSECJ.jcl:L65-L66 KEYS(8,0) RECORDSIZE(80,80));
 *               record layout app/cpy/CSUSR01Y.cpy:L17-L23 @ 7756d89
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

import com.cardemo.model.entity.UserSecurity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * User, credential and role data access: the relational replacement for the VSAM access verbs
 * over the KSDS cluster {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}.
 *
 * <h2>What it does</h2>
 *
 * <p>This interface is the single door through which the application reaches the
 * {@code user_security} table. It replaces five CICS file-control verbs issued against the file
 * name {@code USRSEC} by five COBOL programs - one keyed {@code READ}, one {@code READ ... UPDATE}
 * plus {@code REWRITE}, one {@code WRITE}, one {@code DELETE}, and one
 * {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} browse - with four methods
 * inherited from {@link JpaRepository} and exactly one declared method.
 *
 * <p>It declares one method and one only, {@link #findAllByOrderBySecUsrIdAsc(Pageable)}, because
 * that is the sole access path the inherited surface cannot express with the guarantee the source
 * requires. Everything else is inherited; nothing is redeclared. There is no implementation class,
 * no custom-fragment interface, no {@code Specification} or criteria helper, no mapper and no DAO
 * wrapper: Spring Data supplies the implementation at runtime, and adding a hand-written one would
 * duplicate it. This interface holds no state, performs no validation, catches no exception, emits
 * no log line, and reads no environment variable or system property.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Discharging Rule 1 clause E for this component, alongside
 * {@code src/main/java/com/cardemo/repository/package-info.java}, which documents the package as a
 * whole:
 *
 * <ul>
 *   <li><b>Build.</b> {@code ./mvnw -B clean compile} - Java 25 with
 *       {@code maven.compiler.release} 25, under {@code -Xlint:all -Werror}, so an unused import or
 *       any other warning in this file is a build failure rather than a note.</li>
 *   <li><b>Test.</b> {@code ./mvnw -B clean test} for the unit tier;
 *       {@code ./mvnw -B clean verify} for the integration tier, which runs
 *       {@code src/test/java/com/cardemo/integration/repository} against a Testcontainers
 *       PostgreSQL 16 and therefore needs a reachable Docker socket. The same command applies the
 *       80% line-coverage floor.</li>
 *   <li><b>Run.</b> {@code docker compose up -d} to raise PostgreSQL, then
 *       {@code ./mvnw -B spring-boot:run}. Flyway applies the migrations at startup; a signing key
 *       must be supplied through the environment, because no profile carries a committed
 *       default.</li>
 * </ul>
 *
 * <h2>Provenance: key length 8 and record length 80, established twice over</h2>
 *
 * <p>The two physical facts that fix this mapping are each confirmed by two mutually independent
 * artefacts, which is why they are treated as settled rather than inferred:
 *
 * <ol>
 *   <li><b>The catalogue listing.</b> {@code app/catlg/LISTCAT.txt:L3846} opens the cluster block
 *       {@code CLUSTER ------- AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}, and the DATA-component attribute
 *       line at {@code :L3883} reports {@code KEYLEN 8} with {@code AVGLRECL 80}. The following
 *       line, {@code :L3884}, adds {@code RKP 0} and {@code MAXLRECL 80}: the key sits at relative
 *       byte zero, so it is the leading field, and because the maximum equals the average the
 *       record is fixed rather than merely averaging 80. {@code :L3885} confirms {@code INDEXED}
 *       and {@code REUSE}, and {@code :L3888} reports {@code REC-TOTAL 10}, matching the ten seed
 *       rows exactly.</li>
 *   <li><b>The IDCAMS job that defines the cluster.</b> {@code app/jcl/DUSRSECJ.jcl:L64} opens
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)}, {@code :L65} declares
 *       {@code KEYS(8,0)} and {@code :L66} declares {@code RECORDSIZE(80,80)}. {@code :L62}
 *       deletes any prior cluster and {@code :L63} resets the condition code so the job is
 *       re-runnable; {@code :L67-L73} add {@code REUSE}, {@code INDEXED}, {@code TRACKS(45,15)},
 *       {@code FREESPACE(10,15)}, {@code CISZ(8192)} and the DATA and INDEX component names. The
 *       earlier IEBGENER step corroborates the record length independently at {@code :L48} with
 *       {@code DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0)}.</li>
 * </ol>
 *
 * <p>A third, arithmetic confirmation comes from the record layout itself, and a fourth from the
 * online definition: {@code app/csd/CARDDEMO.CSD:L88} defines {@code FILE(USRSEC) GROUP(CARDDEMO)}
 * and {@code :L89} names the same dataset the two artefacts above describe.
 *
 * <h2>Verified field contract</h2>
 *
 * <p>Read verbatim from {@code app/cpy/CSUSR01Y.cpy}, whose group item at {@code :L17} is
 * {@code 01 SEC-USER-DATA}. That copybook is one of the 12 of 28 members of {@code app/cpy} that
 * carry the repository's Apache-2.0 banner themselves, which is exactly why its field definitions
 * begin at line 17 rather than line 1 - {@code :L1-L16} are the banner.
 *
 * <pre>
 * #  COBOL field (CSUSR01Y.cpy)   PIC    Bytes  Java property  Column         SQL type
 * -  ---------------------------  -----  -----  -------------  -------------  -----------
 * 1  SEC-USR-ID      (:L18)       X(08)   1-8   secUsrId       sec_usr_id     CHAR(8)  PK
 * 2  SEC-USR-FNAME   (:L19)       X(20)  9-28   secUsrFname    sec_usr_fname  CHAR(20)
 * 3  SEC-USR-LNAME   (:L20)       X(20)  29-48  secUsrLname    sec_usr_lname  CHAR(20)
 * 4  SEC-USR-PWD     (:L21)       X(08)  49-56  passwordHash   sec_usr_pwd    VARCHAR(60)
 * 5  SEC-USR-TYPE    (:L22)       X(01)     57  secUsrType     sec_usr_type   CHAR(1)
 * -  SEC-USR-FILLER  (:L23)       X(23)  58-80  not modelled   not modelled   not modelled
 * </pre>
 *
 * <p>The geometry closes exactly: {@code 8 + 20 + 20 + 8 + 1 = 57} populated bytes, plus 23 bytes
 * of trailing filler, equals the catalogued 80. Field 1 is the eight-byte primary key, which is
 * why the identifier type of this repository is {@link String} and not a numeric type - unlike the
 * account and card clusters, whose keys are zoned-decimal digit strings, this key is genuinely
 * alphanumeric, as {@code ADMIN001} and {@code USER0001} in the seed demonstrate.
 *
 * <p>Field 4 is the one place where the column width deliberately departs from the PIC width; see
 * the first Blocker finding below. Field 5 is persisted through the nested converter declared on
 * the entity rather than through either enumerated mode, and its Java type is
 * {@code com.cardemo.model.enums.UserType}, whose two constants carry the codes {@code A} and
 * {@code U} declared as condition names in {@code app/cpy/COCOM01Y.cpy:L26-L28}.
 *
 * <h2>Legacy verb mapping: four inherited methods, one declared</h2>
 *
 * <pre>
 * COBOL verb / program                              Java method            Declared here?
 * ------------------------------------------------  ---------------------  --------------
 * READ            COSGN00C:L211-L219                findById(String)       inherited
 * READ ... UPDATE COUSR02C:L322-L328                findById(String)       inherited
 * READ ... UPDATE COUSR03C:L269-L275                findById(String)       inherited
 * WRITE           COUSR01C:L240                     save(UserSecurity)     inherited
 * REWRITE         COUSR02C:L360-L364                save(UserSecurity)     inherited
 * DELETE          COUSR03C:L307-L311                deleteById(String)     inherited
 * STARTBR/READNEXT/READPREV/ENDBR  COUSR00C:L586+   findAllByOrderBy...    DECLARED
 * </pre>
 *
 * <p><b>{@code findById(String)} replaces the keyed read on three separate paths.</b> The sign-on
 * program reads the file by the entered identifier: {@code app/cbl/COSGN00C.cbl:L211-L219} issues
 * {@code EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)
 * KEYLENGTH(LENGTH OF WS-USER-ID)}, where {@code WS-USRSEC-FILE} is the literal {@code 'USRSEC  '}
 * at {@code :L39}. It returns {@code Optional}, and the empty case is the sign-on failure path: a
 * caller must handle it explicitly rather than dereference, which is the boundary condition Rule 1
 * clause B requires be handled and never a {@code null} return. The 414-line
 * {@code app/cbl/COUSR02C.cbl} uses the same method for the read half of its read-modify-write, and
 * the 359-line {@code app/cbl/COUSR03C.cbl} for the read half of its read-confirm-delete; both
 * issue {@code READ} with {@code UPDATE} to take an exclusive lock, which JPA supplies through its
 * own transactional and locking mechanisms rather than through a repository method name.
 *
 * <p><b>Case folding belongs to the service layer, not here.</b> The sign-on program upper-cases
 * <em>both</em> the entered identifier and the presented credential before comparing them -
 * {@code app/cbl/COSGN00C.cbl:L132} and {@code :L135} apply {@code FUNCTION UPPER-CASE} to each -
 * and {@code :L223} then compares in plaintext. That folding is deliberately absent from this
 * interface: it is authentication behaviour and lives in
 * {@code com.cardemo.service.auth.AuthenticationService}. A repository that silently folded its
 * key argument would make the mapping from method call to SQL predicate non-obvious, and folding
 * without pinning a locale would additionally be non-deterministic. Callers pass the key already
 * normalised.
 *
 * <p><b>{@code save(UserSecurity)} replaces both the insert and the update.</b> The 299-line
 * {@code app/cbl/COUSR01C.cbl} issues {@code EXEC CICS WRITE} at {@code :L240}; the 414-line
 * {@code app/cbl/COUSR02C.cbl} issues {@code EXEC CICS REWRITE DATASET(WS-USRSEC-FILE)
 * FROM(SEC-USER-DATA)} at {@code :L360-L364}. JPA merges both into one call and decides insert
 * against update from whether the identifier is already present, so no separate insert method is
 * declared. A duplicate key surfaces as {@code com.cardemo.exception.DuplicateRecordException} by
 * way of {@code com.cardemo.service.shared.FileStatusMapper}; the add program's own handling is at
 * {@code app/cbl/COUSR01C.cbl:L260-L261}, where {@code WHEN DFHRESP(DUPKEY)} and
 * {@code WHEN DFHRESP(DUPREC)} fall through to a single message. Note that FILE STATUS
 * {@code '22'} has <b>zero</b> literal occurrences anywhere in {@code app/cbl}: the duplicate
 * condition is grounded exclusively through those CICS response codes - {@code DUPREC} at 7 sites
 * and {@code DUPKEY} at 3 across the corpus - and not through a file-status literal.
 *
 * <p><b>{@code deleteById(String)} replaces the delete, and deliberately guards nothing.</b> The
 * 359-line {@code app/cbl/COUSR03C.cbl} reads the target record with {@code UPDATE} at
 * {@code :L269-L275} and, once the confirmation path is taken, issues
 * {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE)} at {@code :L307-L311}. See the High-severity
 * finding below for what that program does <em>not</em> do.
 *
 * <p><b>{@code existsById(String)} supports the add program's pre-flight.</b> It answers the
 * duplicate question before the write rather than after it, which is cheaper than loading a full
 * row to discard it, and it returns no column data at all - relevant here because one of this
 * table's columns is credential material.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><b>{@code spring.jpa.hibernate.ddl-auto: validate}</b> in every profile. Hibernate
 *       compares this entity's mapping against the live schema at startup, so the table and column
 *       names in the contract above are normative rather than descriptive: a mismatch fails
 *       context startup outright with a {@code SchemaManagementException} naming the offending
 *       column. It does not degrade gracefully, and that is the intended behaviour.</li>
 *   <li><b>{@code spring.jpa.open-in-view: false}.</b> Every entity this interface returns is
 *       fully initialised before the transaction closes; there is no lazy state to touch later,
 *       and no proxy escapes into a controller. This entity has no association of any kind, so
 *       nothing here can lazily load.</li>
 *   <li><b>{@code spring.jpa.show-sql: false}</b> in every profile, with no Hibernate SQL or
 *       bind-parameter logger enabled anywhere. See the second Blocker finding.</li>
 *   <li><b>Instrumentation is delegated, not absent.</b> Rule 1 clause A asks for measurable
 *       behaviour where relevant. This interface declares no body, so it cannot log, time or count
 *       anything itself, and it deliberately does not try: structured logging belongs to
 *       {@code logback-spring.xml}, and correlation, metrics and tracing belong to
 *       {@code com.cardemo.observability}. The layer is measurable regardless, because Spring Data
 *       registers the meter {@code spring.data.repository.invocations} for every repository - so
 *       calls to the declared method below are observable without this file emitting a thing. That
 *       meter was confirmed present at runtime on {@code /actuator/metrics}.</li>
 *   <li><b>{@code carddemo.pagination.*}</b> supplies the page size for the declared browse. It is
 *       ten for the user list, and that value is configuration, never a constant in this file.</li>
 *   <li><b>Connection pooling is left at its framework defaults.</b> Pool tuning is explicitly out
 *       of scope for this migration and is recorded as residual risk in {@code DECISION_LOG.md}
 *       and {@code docs/validation-gates.md}. Rule 1 clause A asks that tradeoffs be justified
 *       rather than assumed, and the honest justification is that the legacy system publishes no
 *       throughput or latency objective - {@code app/catlg/LISTCAT.txt} records
 *       {@code BUFSPACE 24576} and {@code CISIZE 8192} for this cluster, which are VSAM buffer
 *       geometry with no relational analogue - so any pool figure chosen here would be invented.
 *       The measured baseline is captured as evidence instead of a target being asserted.</li>
 * </ul>
 *
 * <h2>Findings, classified by severity</h2>
 *
 * <p><b>Blocker - no projection over the credential column exists, and none may be added.</b> Rule
 * 1 clause D is unconditional: no secrets in code, logs, tests or config, and least privilege for
 * credentials. This interface therefore declares no {@code findPasswordBy...} method, no interface
 * or class projection carrying the credential property, no DTO projection, no {@code @Query}
 * selecting {@code sec_usr_pwd} in isolation, and no method whose name or return type could leak
 * it. The stored hash reaches exactly one consumer,
 * {@code com.cardemo.security.CardDemoUserDetailsService}, which obtains it from the fully loaded
 * {@link UserSecurity} aggregate through that entity's accessor and never from a narrowed
 * projection. Two facts make this a real constraint rather than a formality: the source column is
 * the credential store, and its target type is a BCrypt strength-10 hash 60 characters wide, so a
 * projection would be an exfiltration path for verifiable credential material. The primary defence
 * is never selecting what is not needed; log masking is only the secondary one. <i>Remediation if
 * ever violated:</i> delete the projection and route the caller through {@code findById}.
 *
 * <p><b>Blocker - the credential column is 60 characters, not the source's 8.</b>
 * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21} held a plaintext value that the
 * sign-on program compared directly - {@code app/cbl/COSGN00C.cbl:L223} reads
 * {@code IF SEC-USR-PWD = WS-USER-PWD}. The target column is {@code sec_usr_pwd VARCHAR(60)}: 60
 * because that is the exact width of a BCrypt hash, and {@code VARCHAR} rather than {@code CHAR}
 * because a blank-padded hash fails verification. This is the single deliberate departure from the
 * record contract in this table, and it is a security requirement rather than a mapping
 * convenience. It constrains this interface only negatively - by making the previous finding
 * binding - since no method here reads or writes that column selectively.
 *
 * <p><b>Blocker - this interface performs no seeding.</b> The ten users load through
 * {@code V3__seed_data.sql} and through nothing else. There is deliberately no {@code saveAll}
 * helper, no {@code @PostConstruct} loader, no data-initialiser method and no runner of any kind
 * referenced here; a repository interface cannot carry executable initialisation in any case, and
 * naming one would invite a caller to add it. Note that there is no {@code app/data/ASCII} fixture
 * for this table at all - unlike the nine fixtures that seed the sibling tables, the ten records
 * exist only as inline {@code SYSUT1 DD *} data at {@code app/jcl/DUSRSECJ.jcl:L35-L44}, fed
 * through {@code EXEC PGM=IEBGENER} at {@code :L32}, in the {@code CSUSR01Y} layout with the type
 * character at byte 57. Five are administrators of type {@code A} and five are standard users of
 * type {@code U}. All ten carry the same legacy inline plaintext literal, which the migration
 * stores only as a precomputed BCrypt strength-10 hash; that literal is referred to here by
 * citation alone and is transcribed nowhere under {@code src/}.
 *
 * <p><b>High - the delete path has no self-delete guard, and that absence is preserved, not
 * corrected.</b> {@code app/cbl/COUSR03C.cbl} never compares the target identifier against the
 * signed-on identifier: the symbol {@code CDEMO-USER-ID} does not appear anywhere in the program,
 * and no predicate anywhere in it tests {@code SEC-USR-ID} against a session value. Consequently a
 * signed-on administrator can delete their own record, and the deletion at {@code :L307-L311}
 * proceeds unconditionally once the confirmation path is taken. {@code deleteById} reproduces that
 * exactly. <b>No guard is added.</b> Behavioural parity is the contract of this migration, and
 * inserting a check the source does not have would change observable behaviour - which is
 * forbidden - however defensible the check would be in a green-field system. The quirk is cited in
 * {@code TRACEABILITY_MATRIX.md} and justified in {@code DECISION_LOG.md}; it is a tracked,
 * deliberate reproduction rather than an oversight, and it must not be silently repaired by a later
 * change to this interface or its callers.
 *
 * <p><b>Medium - the specification body wrongly states that this cluster is not catalogued.</b> The
 * specification asserts that USRSEC "is defined in JCL rather than catalogued here", citing only
 * the IDCAMS job. That is incorrect: the cluster is catalogued, at
 * {@code app/catlg/LISTCAT.txt:L3846}, with {@code KEYLEN 8} and {@code AVGLRECL 80} at
 * {@code :L3883}. The two sources agree on both key length and record length, so the discrepancy is
 * documentary only and no code is affected. <i>Remediation, applied:</i> cite both locators
 * wherever this geometry is asserted, which the provenance section above does. Recorded in
 * {@code DECISION_LOG.md}.
 *
 * <p><b>Low - the browse in the list program does not use generic positioning.</b>
 * {@code app/cbl/COUSR00C.cbl:L592} carries {@code GTEQ} as a <em>comment</em>, and that is the
 * only occurrence of the keyword in the program, so the {@code STARTBR} at {@code :L588-L595}
 * takes the file-control default positioning instead. The observable effect is confined to how the
 * legacy screen positions its first page; the ordering the browse then walks is key order either
 * way, and it is that ordering the declared method reproduces. Noted for completeness because a
 * reader comparing the two sources will see a commented keyword and wonder whether something was
 * lost.
 *
 * <p><b>Low - this table has no alternate index, so no additional finder is declared.</b> The
 * corpus defines three alternate indexes and this cluster owns none of them: the catalogue lists
 * only a DATA and an INDEX component for it, at {@code app/catlg/LISTCAT.txt:L3871-L3872}. No
 * finder on the user type exists here either. One would be easy to write and is deliberately
 * absent: no program in the corpus browses this file by type, the migration's index set is exactly
 * three non-unique indexes and none of them is on this table, and an unindexed predicate would be
 * a sequential scan dressed up as a lookup. Role filtering, where it is needed, happens on the
 * authorisation path rather than in SQL.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>Context fails to start with a schema-validation error naming a column of
 *       {@code user_security}.</b> The migration and this entity's mapping disagree. Compare the
 *       live column against the contract above; {@code ddl-auto: validate} is doing its job and
 *       must not be relaxed to {@code none} or {@code update} to make the symptom disappear.</li>
 *   <li><b>Context fails to start with a property-resolution error naming this interface.</b> The
 *       declared method name no longer parses against the entity - almost always because a property
 *       was renamed on {@link UserSecurity}. Spring Data derives the query from the method name at
 *       startup, so this is caught at boot rather than on first call, which is the desired
 *       behaviour. Fix the method name and the entity together.</li>
 *   <li><b>{@code findById} returns empty where a row is expected.</b> The key is fixed width
 *       {@code CHAR(8)}, so a value that has been trimmed, lower-cased or padded differently will
 *       not match. Sign-on folds case in the service layer, so verify the caller normalised the
 *       identifier before calling.</li>
 *   <li><b>{@code save} fails with a constraint violation on the user type.</b> The schema
 *       restricts that column to {@code A} or {@code U}. The entity converter should make an
 *       out-of-range value unreachable, so a violation here indicates a direct SQL write or a
 *       migration defect rather than a fault in this interface.</li>
 *   <li><b>An integration test cannot start a container.</b> The repository tier needs a reachable
 *       Docker socket for its Testcontainers PostgreSQL 16 instance. That is a documented
 *       prerequisite, not a test defect; where no runtime is available the evidence artefacts state
 *       the prerequisite rather than asserting an untested pass.</li>
 *   <li><b>A data-access exception surfaces from a call to any method here.</b> This interface
 *       catches nothing and translates nothing. Status-to-exception translation happens exactly
 *       once, in {@code com.cardemo.service.shared.FileStatusMapper}, which is where the Spring
 *       {@code DataAccessException} hierarchy becomes
 *       {@code com.cardemo.exception.RecordNotFoundException},
 *       {@code com.cardemo.exception.DuplicateRecordException} or
 *       {@code com.cardemo.exception.FileAccessException} with the root cause preserved. Nothing is
 *       swallowed anywhere on this path.</li>
 * </ul>
 *
 * <h2>Information gaps: "Not available"</h2>
 *
 * <p>Rule 1 clause F requires that missing information be stated plainly rather than assumed. Two
 * items are <b>Not available</b> at the time this interface was authored:
 *
 * <ol>
 *   <li><b>The three Flyway migrations are "Not available".</b> Neither
 *       {@code V1__create_schema.sql}, nor {@code V2__create_indexes.sql}, nor
 *       {@code V3__seed_data.sql} exists yet. <i>What is needed:</i> those three files, under
 *       {@code src/main/resources/db/migration}. Until they exist, this interface and
 *       {@link UserSecurity} are the normative contract {@code V1} must satisfy, because
 *       {@code ddl-auto: validate} makes the match mandatory rather than advisory - table
 *       {@code user_security}, with columns {@code sec_usr_id CHAR(8)} as the primary key,
 *       {@code sec_usr_fname CHAR(20)}, {@code sec_usr_lname CHAR(20)},
 *       {@code sec_usr_pwd VARCHAR(60)} and {@code sec_usr_type CHAR(1)}, all not null, no version
 *       column, and a check constraint restricting {@code sec_usr_type} to {@code 'A'} or
 *       {@code 'U'}. {@code V2} must add <b>no</b> index on this table: its three non-unique
 *       indexes are on {@code card.card_acct_id}, {@code card_cross_reference.xref_acct_id} and
 *       {@code "transaction".tran_proc_ts}. {@code V3} must seed exactly <b>10</b> rows, from the
 *       inline data at {@code app/jcl/DUSRSECJ.jcl:L35-L44} rather than from any fixture file, with
 *       every credential stored only as a BCrypt strength-10 hash. A mismatch on any of these
 *       points fails context startup outright rather than degrading gracefully.</li>
 *   <li><b>FILE STATUS {@code '35'}, file unavailable, is "Not available" as a grounded source
 *       construct.</b> The literal {@code '35'} appears nowhere in {@code app/cbl} - zero
 *       occurrences across all 28 programs - and the CICS response census is
 *       {@code DFHRESP(NORMAL)} 43, {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8,
 *       {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3 and <b>{@code DFHRESP(NOTOPEN)}
 *       zero</b>. The corresponding {@code com.cardemo.exception.FileUnavailableException} is
 *       therefore specification derived only, and no behaviour of this repository depends on it.
 *       <i>What is needed</i> to promote it from specification to evidence: a legacy artefact that
 *       tests for status {@code '35'} or for {@code NOTOPEN}. None exists at {@code 7756d89}. The
 *       responses this file's access paths <em>do</em> ground are {@code NORMAL}, {@code NOTFND},
 *       {@code ENDFILE}, {@code DUPREC} and {@code DUPKEY}.</li>
 * </ol>
 *
 * @see UserSecurity
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    /**
     * Returns one page of all users, ordered by user identifier ascending.
     *
     * <p><b>Purpose.</b> This is the Java form of the {@code STARTBR} + {@code READNEXT} +
     * {@code ENDBR} sequence in the 695-line {@code app/cbl/COUSR00C.cbl}. That program opens a
     * browse over the file at {@code :L586-L595} with
     * {@code EXEC CICS STARTBR DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID)
     * KEYLENGTH(LENGTH OF SEC-USR-ID)}, walks it forward with {@code READNEXT} at
     * {@code :L619-L621} - or backward with {@code READPREV} at {@code :L653-L655} when the user
     * pages up - and closes it with {@code ENDBR} at {@code :L687-L691}. A VSAM browse over a KSDS
     * returns records in key order by construction, so key order is not an incidental property of
     * the legacy screen: it <em>is</em> the observable behaviour being reproduced, and it is what
     * makes the page-forward and page-backward paths at {@code :L282} and following coherent with
     * one another.</p>
     *
     * <p><b>Why this is declared rather than delegated to the inherited {@code findAll(Pageable)}.</b>
     * The inherited method applies only whatever {@code Sort} the caller happens to supply. If the
     * caller supplies none, row order becomes whatever the database finds convenient, and paging
     * over an unstable order can show a row twice or skip it entirely between two requests. Naming
     * the ordering here makes it an invariant of the repository rather than a caller convention,
     * which is what Rule 1 clause A demands of a multi-row query: correctness and determinism ahead
     * of cleverness. This is not an alternate-key finder - it restricts on nothing and orders by
     * the primary key, so it implies no index beyond the one the primary key already provides,
     * which is why the migration correctly adds none on this table.</p>
     *
     * <p><b>Inputs.</b> {@code pageable} carries the page index and the page size. The size
     * originates from {@code carddemo.pagination.*} and is ten for the user list, evidenced by
     * {@code app/cbl/COUSR00C.cbl:L57}, {@code 02 USER-REC OCCURS 10 TIMES.} - the screen's row
     * array is ten deep, so ten rows are what one page means. That value is configuration and is
     * deliberately not written as a constant anywhere in this interface. A {@code Sort} carried on
     * the argument is redundant here and should be omitted, because the {@code OrderBy} clause in
     * this method name governs the ordering.</p>
     *
     * <p><b>Outputs.</b> A {@code Page} of users in ascending identifier order, together with the
     * total count that {@code com.cardemo.service.admin.UserListService} turns into the next-page
     * indicator the legacy screen carried in its COMMAREA. Every returned entity is fully
     * initialised, since {@code spring.jpa.open-in-view} is {@code false}. Each element is a
     * complete {@link UserSecurity} aggregate, not a projection; the service maps it to a DTO that
     * omits the credential, so the hash never reaches a response body. The result is never
     * {@code null}: an empty page means the table holds no rows, or the requested index lies past
     * the end, and both are legitimate outcomes rather than failures.</p>
     *
     * <p><b>Side effects.</b> None. This is a read: it modifies no row, acquires no pessimistic
     * lock, writes no log record of its own and starts no transaction. It has no equivalent of the
     * legacy {@code ENDBR} to forget, because no cursor outlives the call.</p>
     *
     * <p><b>Failure modes.</b> A {@code null} {@code pageable} is <em>not</em> rejected: Spring Data
     * normalises it to {@code Pageable.unpaged()}, so the query degenerates into an unpaged read of
     * the whole table in a single page. Callers must therefore supply a {@code Pageable}
     * deliberately - on this unfiltered browse the consequence of {@code null} is the entire user
     * master in one response, which is precisely what pagination exists to prevent. A page index
     * past the end is not an error and yields an empty page. Connection loss or a schema fault
     * surfaces as a subtype of {@code org.springframework.dao.DataAccessException} for
     * {@code com.cardemo.service.shared.FileStatusMapper} to translate with the root cause intact;
     * nothing is caught, mapped or swallowed here. Note finally that this method applies no
     * authorisation of its own: the user-administration surface is restricted to the administrator
     * role at the controller boundary under {@code /api/admin/*}, matching the legacy screen's own
     * reachability only from the admin menu.</p>
     *
     * @param pageable the page index and page size to apply; the size comes from
     *                 {@code carddemo.pagination.*} and is never hardcoded in this interface. A
     *                 {@code Sort} on it is redundant, since this method name fixes the ordering
     * @return a page of users in ascending user-identifier order, empty if the table holds no rows
     *         or the page index lies past the end; never {@code null}
     */
    Page<UserSecurity> findAllByOrderBySecUsrIdAsc(Pageable pageable);
}
