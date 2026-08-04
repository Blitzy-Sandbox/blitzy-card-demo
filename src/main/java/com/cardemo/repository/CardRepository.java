/*
 * ****************************************************************************
 * Program     : CardRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the card master file,
 *               including the account-keyed alternate-index browse used by the
 *               card list, card detail and card update programs.
 * Source      : CICS FILE CARDDAT (app/csd/CARDDEMO.CSD:L25-L26) + CICS FILE
 *               CARDAIX over AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH (:L13-L14);
 *               cluster key 16 / reclen 150 (app/catlg/LISTCAT.txt:L202
 *               DATA-component attribute line; app/jcl/CARDFILE.jcl:L50, L54-L55
 *               KEYS(16 0) RECORDSIZE(150 150)); alternate index
 *               CARDDATA.VSAM.AIX KEYLEN 11 RKP 5 AXRKP 16 NONUNIQKEY
 *               (app/catlg/LISTCAT.txt:L279, L281, L282, L283, L285;
 *               app/jcl/CARDFILE.jcl:L83-L85 KEYS(11 16), PATH :L100-L102);
 *               record layout app/cpy/CVACT02Y.cpy:L4-L11 @ 7756d89
 * ****************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import com.cardemo.model.entity.Card;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository over the card master file: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}, and for the two CICS files that fronted that cluster online -
 * {@code CARDDAT} over the base cluster and {@code CARDAIX} over the alternate-index path.
 *
 * <p>It replaces the VSAM access verbs the legacy card programs issued - {@code READ}, {@code READ ... UPDATE},
 * {@code REWRITE}, {@code STARTBR}, {@code READNEXT}, {@code READPREV} and {@code ENDBR} - with Spring Data
 * query methods. It holds no state, applies no business rule and formats nothing. Driving these calls in the
 * order the legacy screens drove them is the responsibility of the card services in
 * {@code com.cardemo.service.card}, and translating a failed call into a typed exception happens exactly once,
 * in {@code com.cardemo.service.shared.FileStatusMapper}. Being an interface it has no implementation class and
 * needs none: Spring Data supplies the proxy at runtime, which is why no {@code CardRepositoryImpl}, no
 * custom-fragment interface and no criteria or specification helper exists anywhere in this package.
 *
 * <p>The key is therefore the 16-character card number, which is why this interface is typed
 * {@code JpaRepository<Card, String>} and not over a numeric identifier: {@code CARD-NUM} is
 * {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}, a character field, and treating it as a
 * number would discard leading zeros that the 50-row fixture actually contains.</p>
 *
 * <p><b>The alternate index, and the offset that must never be quoted without its base.</b>
 * {@code app/catlg/LISTCAT.txt} records exactly three alternate indexes across the whole
 * catalogue - the summary block reports {@code AIX 3}, {@code CLUSTER 10} and {@code GDG 7} at
 * {@code :L3938}, {@code :L3940} and {@code :L3942}, and an {@code AXRKP} census returns exactly
 * three hits. This is the first of those three. Its physical detail is:</p>
 *
 * <ul>
 *   <li>{@code app/catlg/LISTCAT.txt:L279} names {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX}; the
 *       {@code PATH} association is at {@code :L270} and the {@code DATA}-component block of the
 *       alternate index begins at {@code :L271}.</li>
 *   <li>{@code :L281} reports {@code KEYLEN 11}, {@code :L282} reports {@code RKP 5},
 *       {@code :L283} reports {@code AXRKP 16}, and {@code :L285} reports
 *       {@code SPANNED NONUNIQKEY}.</li>
 *   <li>Independently corroborated by IDCAMS in {@code app/jcl/CARDFILE.jcl}:
 *       {@code DEFINE ALTERNATEINDEX} at {@code :L83}, {@code RELATE} to the base cluster at
 *       {@code :L84}, {@code KEYS(11 16)} at {@code :L85}, {@code NONUNIQUEKEY} at {@code :L86},
 *       {@code UPGRADE} at {@code :L87} and {@code RECORDSIZE(150,150)} at {@code :L88}; the
 *       {@code DEFINE PATH} with {@code PATHENTRY} over that alternate index is at
 *       {@code :L100-L102}, and {@code BLDINDEX} at {@code :L110-L112}.</li>
 *   </ul>
 *
 * <p><b>Both offset numbers, stated explicitly.</b> IDCAMS {@code KEYS(length offset)} and the
 * catalogue's {@code AXRKP} are both <b>zero-based</b>. The zero-based offset <b>16</b>
 * ({@code app/catlg/LISTCAT.txt:L283} and {@code app/jcl/CARDFILE.jcl:L85}) is the same position
 * as <b>1-based record byte 17</b>, which is exactly where {@code CARD-ACCT-ID PIC 9(11)} begins:
 * it occupies 1-based bytes 17 through 27, immediately after the 16-byte card number
 * ({@code app/cpy/CVACT02Y.cpy:L6}). The alternate key width of 11 equals that field's width,
 * which is the cross-check that the offset has been read against the right base. Where the
 * specification says the alternate key "sits at byte 16 of the base record" it is quoting the
 * zero-based figure; elsewhere the same specification quotes a 1-based figure for a different
 * index, so a bare byte number must never be written here without naming its base.</p>
 *
 * <p><b>The alternate index becomes an index plus a finder, never an entity.</b> There is no
 * class for {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX}. It resolves into exactly two artefacts: the
 * single account-keyed finder declared below, and a <b>non-unique</b> B-tree index on
 * {@code card.card_acct_id} created by {@code V2__create_indexes.sql}. Non-uniqueness is the
 * catalogued fact, asserted twice in the source under two spellings of one attribute -
 * {@code NONUNIQKEY} at {@code app/catlg/LISTCAT.txt:L285} and {@code NONUNIQUEKEY} at
 * {@code app/jcl/CARDFILE.jcl:L86}. That index must not be created {@code UNIQUE}, and no second
 * alternate-key finder may be added here: the package declares exactly three across its eleven
 * interfaces, matching the three catalogued alternate indexes and the three non-unique indexes of
 * {@code V2}, and this file owns exactly one of them.</p>
 *
 * <p><b>Why the account finder returns a page and never a single card.</b> Because
 * the alternate key is non-unique, one account may legitimately front several cards, so a scalar or
 * {@code Optional} return would silently discard rows. That failure mode is invisible in testing
 * and permanent in production, which is why the return type is
 * {@code org.springframework.data.domain.Page} and why no unique-result variant of this query may
 * be introduced. Note carefully what the evidence does and does not show: non-uniqueness is a
 * <b>schema guarantee</b>, taken from {@code NONUNIQKEY} and {@code NONUNIQUEKEY} above, and it is
 * <b>not</b> observable in the shipped fixture. A census of all 50 rows of
 * {@code app/data/ASCII/carddata.txt} at the eleven-character account identifier, 1-based bytes
 * 17-27, finds 50 distinct values - one per row - so the seed data holds exactly one card per
 * account. The collection return is therefore mandated by the declared key semantics
 * rather than by the current contents of the file, and the integration test that proves it must
 * construct a second card for one account rather than expect the seed to supply one. Stating this
 * plainly matters: an implementation that inferred a one-to-one relationship from the fixture and
 * narrowed the return type would pass every test written against that fixture and corrupt the card
 * list the first time a real account was issued a replacement card.</p>
 *
 * <p><b>Why the ordering is ascending by card number, and why it is not left to the caller.</b> The
 * card list browses the <b>base</b> cluster, not the alternate index:
 * {@code app/cbl/COCRDLIC.cbl:L1128-L1135} issues {@code EXEC CICS STARTBR DATASET(LIT-CARD-FILE)}
 * with {@code RIDFLD(WS-CARD-RID-CARDNUM)} and {@code GTEQ}, where {@code LIT-CARD-FILE} is
 * {@code 'CARDDAT '} at {@code :L213-L214}, and then reads forward with {@code READNEXT} at
 * {@code :L1146} or backward with {@code READPREV} at {@code :L1294} and {@code :L1322}. The
 * account restriction is applied afterwards, as an in-program predicate in
 * {@code 9500-FILTER-RECORDS}, which excludes a record unless {@code CARD-ACCT-ID = CC-ACCT-ID}.
 * The observable sequence is therefore base-key order - card number ascending - in the filtered and
 * unfiltered cases alike, and reproducing it is a parity requirement rather than a preference. The
 * ordering is expressed in the method names so that it is guaranteed by this interface instead of
 * depending on every caller remembering to pass a {@code Sort}; leaving it to caller convention
 * would be exactly the reliance on unspecified row order that Rule 1 clause A forbids. For the same
 * reason no method here returns an unordered multi-row result.</p>
 *
 * <p><b>Page size is a parity contract, and it is not declared in this file.</b> The card list
 * shows seven rows per page. The evidence is {@code app/cbl/COCRDLIC.cbl:L176-L178}, where
 * {@code 01 WS-CONSTANTS.} declares {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.},
 * corroborated by three {@code OCCURS 7 TIMES} row arrays at {@code :L76}, {@code :L86}
 * ({@code WS-EDIT-SELECT-ERRORS}) and {@code :L255} ({@code WS-SCREEN-ROWS}). The
 * {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN} at {@code :L295} is the COMMAREA and is not
 * a row array, so it does not bear on the page size. That value reaches the query through the
 * {@code Pageable} the service builds from the {@code carddemo.pagination.*} configuration; it is
 * deliberately absent from every signature, annotation and default in this interface, so that the
 * transaction, user and card list sizes stay configurable in one place instead of being scattered
 * as literals across the data-access layer.</p>
 *
 * <p><b>Field contract of the entity this repository manages.</b> Reproduced so the property names
 * used by the derived queries can be checked without opening another file. The authority is
 * {@code app/cpy/CVACT02Y.cpy}, whose header at {@code :L2} states {@code RECLN 150} and whose
 * {@code 01 CARD-RECORD.} begins at {@code :L4}:</p>
 *
 * <pre>
 *   COBOL field (CVACT02Y.cpy)     PIC     1-based bytes   Java property    Column
 *   ----------------------------   -----   -------------   --------------   -------------------
 *   CARD-NUM             (:L5)     X(16)    1 - 16         cardNumber       card_num
 *   CARD-ACCT-ID         (:L6)     9(11)   17 - 27         accountId        card_acct_id
 *   CARD-CVV-CD          (:L7)     9(03)   28 - 30         cvvCode *        card_cvv_cd
 *   CARD-EMBOSSED-NAME   (:L8)     X(50)   31 - 80         embossedName     card_embossed_name
 *   CARD-EXPIRAION-DATE  (:L9)     X(10)   81 - 90         expiraionDate    card_expiraion_date
 *   CARD-ACTIVE-STATUS  (:L10)     X(01)   91              activeStatus     card_active_status
 *   FILLER              (:L11)     X(59)   92 - 150        not modelled     none
 *
 *   * cvvCode is persisted but has no read path: no accessor returns it, no projection selects
 *     it and no derived query in this interface may reference it. See com.cardemo.model.entity.Card.
 *
 *   16 + 11 + 3 + 50 + 10 + 1 = 91 persisted bytes + 59 FILLER = 150, matching AVGLRECL 150
 *   at app/catlg/LISTCAT.txt:L202 and RECORDSIZE(150 150) at app/jcl/CARDFILE.jcl:L55. The
 *   fixture app/data/ASCII/carddata.txt is 7550 bytes over 50 rows of uniform 150-byte records.
 * </pre>
 *
 * <p><b>The misspelling {@code EXPIRAION} is part of the field contract and is retained
 * verbatim.</b> {@code app/cpy/CVACT02Y.cpy:L9} declares {@code CARD-EXPIRAION-DATE}, without the
 * {@code T} of EXPIRATION. The Java property is {@code expiraionDate} and the column is
 * {@code card_expiraion_date}. This is recorded explicitly because a reader will otherwise read
 * it as a transcription slip and "fix" it, which would break the column contract, fail schema
 * validation at startup and silently invalidate the traceability mapping. It is not an isolated
 * slip either: the same spelling appears in the account layout as
 * {@code ACCT-EXPIRAION-DATE}, so it is a corpus-wide convention. No derived query in this
 * interface references the field, but the name is stated here so that no future one corrects
 * it.</p>
 *
 * <p><b>{@code accountId} must remain a plain scalar {@code Long}.</b> The account finder
 * below is a derived query, and Spring Data derives it from the JavaBean property name. The
 * property on {@code com.cardemo.model.entity.Card} is therefore a plain scalar {@code Long} named
 * exactly {@code accountId}, with accessors {@code getAccountId()} and {@code setAccountId(Long)},
 * and it is deliberately <b>never</b> a {@code @ManyToOne} association. Remodelling it as an
 * association would rename the derived path to {@code findByAccountAccountId}, at which point this
 * interface stops resolving: Hibernate reports an unknown property path and the application context
 * fails to start. Renaming it to {@code acctId} or {@code cardAcctId} breaks it the same way.
 * Referential integrity to the account is enforced by one of the ten foreign keys created in
 * {@code V1__create_schema.sql}, which is where it belongs, so nothing is lost by keeping the
 * mapping scalar - and an association would additionally expose the card list to N+1 query
 * behaviour for no benefit, since nothing here navigates to the account.</p>
 *
 * <p><b>No card number and no card verification value may ever be exposed.</b> This
 * interface declares no projection, no interface-based or class-based DTO view and no
 * {@code @Query} of any kind, so there is no place where {@code card_cvv_cd} could be selected into
 * a narrower result; and there is deliberately no finder on the verification value, the embossed
 * name, the expiry date or the active status. No card number or verification value appears in any
 * example in this file, not even a fabricated one, because a realistic-looking value invites being
 * copied into a test fixture or a log line. The card number is nevertheless a primary key and is
 * therefore an unavoidable method argument; what is prohibited is rendering it. Accordingly
 * {@code spring.jpa.show-sql} is {@code false} in every profile and no Hibernate SQL or
 * bind-parameter logging is enabled anywhere, since bind-parameter logging would print the key of
 * every {@code findById} call into the application log.</p>
 *
 * <p><b>Key configuration and defaults.</b> This interface reads no property itself and holds no
 * static mutable state, but its behaviour depends on four settings, and it is worth knowing which:
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, so the schema is
 * owned by Flyway and any divergence fails startup rather than being silently patched;
 * {@code spring.jpa.open-in-view} is {@code false}, so every result returned from here must already
 * be fully initialised and no lazy access outside a transaction is possible - which is a further
 * reason the entity carries no association; {@code spring.jpa.show-sql} is {@code false};
 * and {@code carddemo.pagination.*} supplies the page sizes, seven for the card list. The Spring
 * Batch {@code BATCH_*} metadata tables are created by the framework's own schema script through
 * {@code spring.batch.jdbc.initialize-schema}, never by a fourth Flyway migration and never as
 * extra tables inside {@code V1}. Connection-pool tuning is explicitly out of scope: the HikariCP
 * defaults are used as shipped. The tradeoff is stated rather than guessed at, because there is no
 * measured workload to tune against and no service-level objective anywhere in the legacy source to
 * tune towards.</p>
 *
 * <p><b>Observability, and why this interface is deliberately silent.</b> It emits no log record of
 * its own and registers no metric. Instrumentation is delegated to
 * {@code com.cardemo.observability}, where {@code CorrelationIdFilter} places the correlation,
 * trace and span identifiers into the logging context and {@code MetricsConfig} registers the
 * counters, so a call made here is observable through the caller's span rather than through
 * chatter emitted from the persistence layer. That silence is also a safety property: with
 * {@code spring.jpa.show-sql} {@code false} and no Hibernate SQL or bind-parameter logging enabled
 * in any profile, a sixteen-character card number has no route to a log sink. It is the same
 * constraint as the projection ban above, applied to the logging path instead of the query path.
 * Errors stay meaningful because nothing is caught here: a
 * {@code org.springframework.dao.DataAccessException} reaches
 * {@code com.cardemo.service.shared.FileStatusMapper} with its root cause intact.</p>
 *
 * <p><b>Least privilege.</b> This interface holds no credential, reads no environment variable and
 * opens no connection of its own; the {@code DataSource} is injected. Its online reach is evidenced
 * rather than assumed: {@code CARDDAT} and {@code CARDAIX} are two of the eight
 * {@code DEFINE FILE} entries in {@code app/csd/CARDDEMO.CSD} - {@code :L25-L26} and
 * {@code :L13-L14} respectively - which is precisely what establishes the card master as an online
 * file rather than a batch-only one. The contrast is deliberate: {@code TCATBALF},
 * {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} occur nowhere among those eight, so
 * although each has a repository for batch access, none of them is granted any online endpoint
 * surface anywhere in the application.</p>
 *
 * <p><b>How to build and test this component.</b> Compile with {@code ./mvnw -B clean compile} and
 * run the unit suite with {@code ./mvnw -B clean test}. The build sets
 * {@code maven.compiler.release} to 25 and runs {@code -Xlint:all -Werror} with
 * {@code failOnWarning}, so a warning in any category {@code javac} 25 actually publishes - among them
 * {@code deprecation}, {@code removal}, {@code rawtypes} and {@code unchecked} - is a build failure, not
 * a note. {@code javac} at release 25 publishes no lint key for an unused import or for unreachable code,
 * as {@code javac --help-lint} shows, and no Checkstyle or Error Prone analyser is in the pinned dependency
 * set, so those two must be spotted by hand rather than mechanically. Behaviour is exercised by
 * {@code src/test/java/com/cardemo/integration/repository} against a Testcontainers PostgreSQL 16
 * instance, which is to assert that the account finder returns <b>more than one</b> card for a single
 * account after a second card is inserted for it, that a page holds seven rows when the configured
 * size is seven, that the rows arrive in ascending card-number order, and that the derived query
 * metadata resolves at all - the last of which is the check that catches an entity property being
 * renamed or converted to an association. Those tests need a reachable container runtime; without
 * one they cannot run and must be reported as blocked rather than assumed to pass.</p>
 *
 * <p><b>Common failure modes and troubleshooting.</b> A startup failure naming an unknown property
 * path such as {@code accountId} means the entity property was renamed or turned into an
 * association: fix the entity, not this interface. A startup failure reporting a wrong column type
 * or a missing column in table {@code card} means {@code V1__create_schema.sql} diverged from the
 * column contract above: fix the migration, not the mapping. An account query that returns rows in
 * an unexpected order means a caller supplied its own conflicting {@code Sort} inside the
 * {@code Pageable}; the {@code OrderBy} clause in the method name is the authority and a caller
 * should pass an unsorted {@code Pageable}. An account query that is unexpectedly slow means the
 * non-unique {@code idx_card_acct_id} index of {@code V2__create_indexes.sql} was not applied, since
 * without it the query degrades to a sequential scan; the query remains correct, so this shows up as
 * latency rather than as a wrong answer. An empty page for an account that visibly owns cards means the caller passed
 * a page index beyond the end, which is a legitimate empty result and not an error. Values read
 * back longer than they were written are not a defect: the columns are {@code CHAR(n)} and
 * PostgreSQL blank-pads them, which is precisely the fixed-width behaviour being reproduced.</p>
 *
 * <p><b>A {@code null} {@code Pageable} does not fail.</b> Spring Data normalises it to
 * {@code Pageable.unpaged()} and the query then returns every matching row in a single page. The
 * consequence is silent loss of pagination rather than an error, so callers should pass a
 * {@code Pageable} explicitly; a service-layer guard is the place to reject {@code null}, not this
 * interface.</p>
 *
 * <p><b>The normative schema contract, and one construct the corpus does not ground.</b></p>
 *
 * <ul>
 *   <li>Because {@code ddl-auto} is {@code validate} in
 *       every profile, the assertions made here form the <b>normative contract the migrations
 *       must satisfy</b>, not the reverse: the table name {@code card}; the seven columns of the
 *       field table above, <b>including the retained {@code card_expiraion_date} misspelling</b>;
 *       the {@code version} column backing {@code @Version}; and a <b>non-unique</b> B-tree index
 *       on {@code card_acct_id} in {@code V2}. A mismatch fails application-context startup
 *       outright rather than degrading gracefully. For context on the surrounding set, {@code V1}
 *       creates exactly 11 tables with 10 foreign keys and 5 check constraints, {@code V2} creates
 *       exactly three non-unique indexes - {@code card.card_acct_id},
 *       {@code card_cross_reference.xref_acct_id} and the processing-timestamp index on the
 *       transaction table - and {@code V3} seeds 50 card rows of 150 bytes each from
 *       {@code app/data/ASCII/carddata.txt}.</li>
 *   <li>FILE STATUS {@code '35'}, file unavailable, has no grounding as a source
 *       construct. No literal {@code '35'} occurs anywhere in {@code app/cbl}, and the
 *       {@code DFHRESP} census over the corpus is {@code NORMAL} 43, {@code NOTFND} 23,
 *       {@code ENDFILE} 8, {@code DUPREC} 7, {@code DUPKEY} 3 and <b>{@code NOTOPEN} 0</b>. The
 *       corresponding {@code com.cardemo.exception.FileUnavailableException} is therefore
 *       specification-derived only, and no code path in this repository can claim it as a
 *       reproduction of legacy behaviour. What would be needed to ground it is a legacy source site
 *       that tests for an unopened file; none exists.</li>
 *   </ul>
 *
 * <p><b>Deliberate omissions, so that each reads as a decision rather than an oversight.</b> No
 * unpaged {@code List} overload of the account finder: the paged form already covers the shape of
 * {@code app/cbl/COCRDSLC.cbl:L779-L809} when given a first-page {@code Pageable}, so a second
 * overload would be dead code under Rule 1 clause B on top of a method that already has no caller. No finder on the
 * verification value, the embossed name, the expiry date or
 * the active status, since no legacy access path keys on any of them. No {@code delete} usage: the
 * CSD grants {@code DELETE(YES)} on {@code CARDDAT} at {@code app/csd/CARDDEMO.CSD:L31} but no
 * program in the corpus issues a card delete, so the inherited method exists and stays unused by
 * design. No native query and no string concatenation anywhere: the two paged finders are derived from
 * their method names, and the one declared {@code @Query} - the read-for-update finder, which needs
 * {@code @Query} because a derived name cannot express a lock mode - binds its single argument through
 * {@code @Param}, so there is no query text into which a value could be interpolated. No
 * {@code @Modifying} bulk statement, which would bypass the {@code @Version} guard. No default or static
 * method, and no state of any kind.</p>
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Returns one page of the cards belonging to a single account, ordered by card number ascending: the
     * Java form of the {@code CARDDATA.VSAM.AIX} alternate index and its path {@code CARDAIX}.
     *
     * <p>{@code accountId} is the owning account identifier, matched for equality against the scalar
     * {@code Card.accountId} property mapped to {@code card.card_acct_id}; the source field is
     * {@code PIC 9(11)}, so the meaningful range is 0 through 99999999999 inclusive. It is bound as a query
     * parameter, never interpolated into query text. The alternate key sits at byte 16 of the base record -
     * {@code app/catlg/LISTCAT.txt} records {@code KEYLEN 11, RKP 5, AXRKP 16} for that index - and the
     * matching non-unique B-tree index is created by {@code V2__create_indexes.sql} on
     * {@code card (card_acct_id)}.
     *
     * <h4>Why this method exists with no caller, and why it must not be deleted</h4>
     *
     * <p><strong>It has no production caller, and cannot faithfully acquire one.</strong> That is a property
     * of the frozen corpus, not of this interface, and the evidence is exact. {@code CARDAIX} is declared as
     * a {@code PIC X(8)} literal in five online programs - {@code app/cbl/COCRDLIC.cbl:L217},
     * {@code app/cbl/COCRDUPC.cbl:L254}, {@code app/cbl/COACTVWC.cbl:L191},
     * {@code app/cbl/COACTUPC.cbl:L580} and {@code app/cbl/COCRDSLC.cbl:L190} - and is read by a
     * {@code PROCEDURE DIVISION} statement in exactly one of them: {@code app/cbl/COCRDSLC.cbl:L784}, inside
     * {@code 9150-GETCARD-BYACCT}. That paragraph is never performed; the member's only references to its
     * name are the two labels at {@code :L779} and {@code :L810}, with no {@code PERFORM}, {@code GO TO} or
     * {@code THRU} anywhere. Worse for any attempt to wire it up, the record-identification field the
     * paragraph reads by, {@code WS-CARD-RID-ACCT-ID} at {@code :L99}, is never populated: the only
     * {@code MOVE} that would populate it is commented out at {@code :L739}. So the paragraph is not just
     * unreached but unreachable as written, which is why its Java counterpart in
     * {@code com.cardemo.service.card.CardDetailService} is a documented empty method rather than a
     * transcription.
     *
     * <p><strong>The card list does not use it either, and must not be changed to.</strong>
     * {@code app/cbl/COCRDLIC.cbl} filters by account with {@code IF CARD-ACCT-ID = CC-ACCT-ID} at
     * {@code :L1386} inside {@code 9500-FILTER-RECORDS}, applied to records arriving from a
     * {@code STARTBR}/{@code READNEXT}/{@code READPREV} browse of the <em>base</em> cluster at
     * {@code :L1129-L1258}. The browse is key-addressed and supports three filter shapes - account, card and
     * unfiltered - so replacing one of the three with an indexed finder would fork the paging mechanism and
     * change how positions are resolved. {@code com.cardemo.service.card.CardListService} therefore uses
     * {@link #findAllByOrderByCardNumberAsc(Pageable)} for all three.
     *
     * <p><strong>It is required regardless.</strong> Transformation Rule 5 of the migration plan maps every
     * alternate index and path onto a derived finder plus a B-tree index, and the plan's own corrections
     * record a <em>missing</em> derived finder as a defect - the alternate-index count is three, not two, and
     * {@code CARDDATA.VSAM.AIX} is one of the three. Deleting this method would satisfy a dead-code reading
     * while reintroducing that defect and leaving the {@code card (card_acct_id)} index with nothing in the
     * Java model that corresponds to it. The three finders and the three indexes are asserted together by
     * {@code com.cardemo.unit.repository.AlternateIndexFinderMandateTest}, so neither half can be removed
     * silently.
     *
     * @param accountId the owning account identifier, from {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}.
     * @param pageable the page index and page size to apply.
     * @return a page of the account's cards in ascending card-number order, empty if the account owns none or
     * the page index lies past the end.
     */
    Page<Card> findByAccountIdOrderByCardNumberAsc(Long accountId, Pageable pageable);

    /**
     * Returns one page of all cards, ordered by card number ascending.
     *
     * <p>Reproduces the unfiltered base-key browse of the card list. When the user supplies neither an account
     * nor a card filter, {@code app/cbl/COCRDLIC.cbl} browses the base cluster from the low key and pages
     * forward: {@code EXEC CICS STARTBR DATASET(LIT-CARD-FILE)} with {@code RIDFLD(WS-CARD-RID-CARDNUM)} and
     * {@code GTEQ} at {@code :L1128-L1135}, then {@code READNEXT} at {@code :L1146}, with
     * {@code 9500-FILTER-RECORDS} excluding nothing because neither filter flag is set. {@code LIT-CARD-FILE}
     * is {@code 'CARDDAT '} at {@code :L213-L214}, the base cluster of {@code app/csd/CARDDEMO.CSD:L25-L26}.
     *
     * <p><strong>Finding, Medium severity - this returned {@code Page} and now returns {@code Slice}.</strong>
     * A {@code Page} makes Spring Data issue a {@code select count(*)} alongside <em>every</em> window query.
     * Its one caller, {@code com.cardemo.service.card.CardListService}, positions its browse by binary search
     * over the key sequence, so a single request fetched on the order of log2(n) windows and paid for a count
     * query on each of them - while needing the total exactly once, as the search's upper bound. That caller
     * now takes the total from one explicit {@link org.springframework.data.repository.CrudRepository#count()}
     * per request and reads only the window content from here, which is the shape the review prescribes:
     * slice the windows, count once where a count is genuinely required.
     *
     * <p>Narrowing is also the faithful direction. {@code app/cbl/COCRDLIC.cbl} pages by {@code STARTBR} plus
     * {@code READNEXT} at {@code :L1128-L1154}, and a VSAM browse publishes no cardinality: the screen's
     * more-records indicator comes from whether the next {@code READNEXT} succeeded. A {@code Slice} carries
     * the window and that same one-bit answer, and nothing else.
     *
     * @param pageable the page index and page size to apply.
     * @return a slice of cards in ascending card-number order, empty if the table holds no rows or the page
     * index lies past the end.
     */
    Slice<Card> findAllByOrderByCardNumberAsc(Pageable pageable);

    /**
     * Reads one card row under a pessimistic write lock, so that a caller can compare its business field values
     * against a client-supplied snapshot and rewrite it without an interleaved write.
     *
     * <p>This is the Java form of {@code EXEC CICS READ FILE(LIT-CARDFILENAME) UPDATE} issued at
     * {@code app/cbl/COCRDUPC.cbl:1427}-{@code :1436}, the first step of {@code 9200-WRITE-PROCESSING}. It
     * exists because no inherited signature expresses it. {@code findById} reads without a lock, which leaves a
     * window between {@code 9300-CHECK-CHANGE-IN-REC} at {@code :1453} and the {@code REWRITE} at {@code :1478}
     * in which another writer may change the row, defeating the very check the legacy program performs at that
     * point. The {@code version} column cannot substitute for the lock either: a version counter reports
     * <em>that</em> a row changed, whereas {@code 9300} reports <em>which</em> field values differ from what the
     * operator was shown, and the source acquires its lock <em>before</em> comparing rather than detecting the
     * clash afterwards. Both layers are required, exactly as for
     * {@link AccountRepository#findByIdForUpdate(Long)} and
     * {@link CustomerRepository#findByIdForUpdate(Long)}, whose paired reads at
     * {@code app/cbl/COACTUPC.cbl:L3894} and {@code :L3920} are the same construct.
     *
     * <p>Hibernate renders the lock as {@code SELECT ... FOR UPDATE}. It therefore has no meaning outside a
     * transaction and the provider rejects the call when one is absent, so it must be invoked from within the
     * service's {@code @Transactional} write method, which is where the card rewrite is already scoped.
     *
     * @param cardNumber the sixteen-digit card number, {@code CARD-NUM} at {@code app/cpy/CVACT02Y.cpy:L5},
     * already left-padded to its declared width by the caller.
     * @return an {@link Optional} holding the locked {@link Card}, or an empty {@link Optional} when no row
     * carries that card number, which the source treats as "could not lock" at {@code :1441}-{@code :1449}
     * rather than as a distinct not-found outcome.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.cardNumber = :cardNumber")
    Optional<Card> findByIdForUpdate(@Param("cardNumber") String cardNumber);

    /**
     * Reads the row for rewrite under a pessimistic write lock <strong>and</strong> proves it belongs to the
     * requesting account, which is the only read the card rewrite may use.
     *
     * <p>It is the composition of the two guards this interface would otherwise offer separately, and both are
     * load-bearing at the same call site. The lock is
     * {@code EXEC CICS READ FILE(LIT-CARDFILENAME) UPDATE} at
     * {@code app/cbl/COCRDUPC.cbl:1427}-{@code :1436}: it must be acquired at the read, before
     * {@code 9300-CHECK-CHANGE-IN-REC} compares at {@code :1453}, or the window through to the
     * {@code REWRITE} at {@code :1478} is open. The account predicate is the
     * {@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID} that sits commented out at {@code :1424}: without it a
     * request naming any card number rewrites - and locks - a card the requesting account does not own.
     * Choosing either guard alone leaves the other hole open, which is why neither
     * {@link #findByIdForUpdate(String)} nor {@link #findByCardNumberAndAccountId(String, Long)} is used on
     * the write path.
     *
     * <p>Both values are bound as query parameters. The account column is the one
     * {@code CARDDATA.VSAM.AIX} keys on at {@code AXRKP 16} per
     * {@code app/catlg/LISTCAT.txt:L281-L284}, so the index created by {@code V2__create_indexes.sql} serves
     * the added predicate and the lock still reduces to a single-row {@code SELECT ... FOR UPDATE}.
     *
     * <p>An empty result deliberately does not distinguish "no such card" from "that card belongs to another
     * account": {@code :1441}-{@code :1449} reports anything other than a normal response as "could not
     * lock", so both arrive as that one outcome and a caller cannot use the difference to discover which
     * cards exist outside its own account.
     *
     * @param cardNumber the sixteen-character card number, already left-padded by the caller.
     * @param accountId  the account the card must belong to, from {@code CARD-ACCT-ID PIC 9(11)}.
     * @return the locked card when it exists and belongs to that account, otherwise empty.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.cardNumber = :cardNumber and c.accountId = :accountId")
    Optional<Card> findByIdAndAccountIdForUpdate(@Param("cardNumber") String cardNumber,
            @Param("accountId") Long accountId);

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
     * <p><strong>The seed value.</strong> The first window is requested with the empty string, which
     * precedes every non-empty value under the character comparison this column uses. {@code CARD-NUM} is
     * {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} and the column is {@code CHAR(16) NOT NULL}, so
     * no stored key can be empty and the seed is provably below the whole key space. This mirrors
     * {@code CardCrossReferenceRepository.findByCardNumberGreaterThanOrderByCardNumberAsc}, which already
     * serves the statement program's XREFFILE scan the same way.
     *
     * <p>The {@code Pageable} supplies the window size only. Its page number must be zero, because the
     * predicate - not an offset - is what positions the window, and the ordering is fixed by the method
     * name so it cannot be varied by a caller-supplied {@code Sort}.
     *
     * @param cardNumber the card number of the last row already consumed, or the empty string to start at
     *     the beginning of the key sequence.
     * @param pageable the window size; page number zero.
     * @return the next window in ascending key order, never {@code null} and empty once the scan is
     *     exhausted, which is the readers' end-of-file condition.
     */
    List<Card> findByCardNumberGreaterThanOrderByCardNumberAsc(String cardNumber, Pageable pageable);

    /**
     * Returns the card bearing this number <strong>only if</strong> it belongs to this account.
     *
     * <p>This is the two-key read the card programs' own {@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID}
     * statements anticipate - present but commented out at {@code app/cbl/COCRDUPC.cbl:1379} and
     * {@code :1424}, and at {@code app/cbl/COCRDSLC.cbl:739} - so the card services can prove that the card
     * they are about to show, lock or rewrite is the card the requested account actually owns. A single-key
     * {@code findById} cannot prove that: it returns the row for any card number in the file, and the caller
     * then has to trust an account identifier that arrived with the request.
     *
     * <p>Both values are matched for equality. {@code cardNumber} is the primary key, {@code CARD-NUM
     * PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}; {@code accountId} is the scalar mapped to
     * {@code card.card_acct_id}, {@code CARD-ACCT-ID PIC 9(11)} at {@code :L6}, which is the same column the
     * {@code CARDDATA.VSAM.AIX} alternate index keys on at {@code AXRKP 16} per
     * {@code app/catlg/LISTCAT.txt:L281-L284}, so the index created by {@code V2__create_indexes.sql}
     * serves this predicate. Both are bound as query parameters; the method name is derived, so there is no
     * query text into which a value could be interpolated.
     *
     * <p>An empty result deliberately does not distinguish "no such card" from "that card belongs to
     * another account". The card services report both as not-found, so a caller cannot use the difference to
     * discover which cards exist outside its own account.
     *
     * @param cardNumber the sixteen-character card number, the primary key.
     * @param accountId  the account the card must belong to, from {@code CARD-ACCT-ID PIC 9(11)}.
     * @return the card when it exists and belongs to that account, otherwise empty.
     */
    Optional<Card> findByCardNumberAndAccountId(String cardNumber, Long accountId);
}
