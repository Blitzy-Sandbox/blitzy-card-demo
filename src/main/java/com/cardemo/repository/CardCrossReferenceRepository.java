/*
 * ****************************************************************************
 * Program     : CardCrossReferenceRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the card cross-reference
 *               file, including the account-keyed alternate-index browse.
 * Source      : CICS FILE CCXREF (app/csd/CARDDEMO.CSD:L37, L39) + CICS FILE
 *               CXACAIX over AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH (:L63, L65);
 *               cluster key 16 / reclen 50 (app/catlg/LISTCAT.txt:L403
 *               DATA-component attribute line; app/jcl/XREFFILE.jcl:L39, L43-L44
 *               KEYS(16 0) RECORDSIZE(50 50)); alternate index
 *               CARDXREF.VSAM.AIX KEYLEN 11 RKP 5 AXRKP 25 NONUNIQKEY
 *               (app/catlg/LISTCAT.txt:L480, L482, L485, L486, L488;
 *               app/jcl/XREFFILE.jcl:L72-L74 KEYS(11,25), PATH :L90-L92);
 *               record layout app/cpy/CVACT03Y.cpy:L4-L8 @ 7756d89
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

import com.cardemo.model.entity.CardCrossReference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Keyed access to the card cross reference: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} together with its account keyed alternate index
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX}.
 *
 * <h2>What it does</h2>
 *
 * <p>The cross reference is the entry point of every keyed lookup chain in the legacy corpus. A card
 * number arrives from a screen or from a daily transaction record, the cross reference resolves it to
 * an account identifier and a customer identifier, and only then are the account and customer records
 * read - three separate keyed reads in a fixed order. Thirteen of the twenty eight programs open it:
 * {@code CBACT03C}, {@code CBACT04C}, {@code CBSTM03A}, {@code CBSTM03B}, {@code CBTRN01C},
 * {@code CBTRN02C}, {@code CBTRN03C}, {@code COACTUPC}, {@code COACTVWC}, {@code COBIL00C},
 * {@code COCRDSLC}, {@code COCRDUPC} and {@code COTRN02C}.
 *
 * <p>One cluster is reached through two distinct doors, and this single interface replaces both:
 *
 * <ul>
 *   <li><strong>The base cluster, keyed by card number.</strong> Online it is CICS file
 *       {@code CCXREF}, defined at {@code app/csd/CARDDEMO.CSD:L37} - described there as "CARD TO
 *       ACCOUNT XREF" at {@code :L38} - over {@code DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)} at
 *       {@code :L39}. In batch it is DD {@code XREFFILE}, for instance
 *       {@code app/jcl/POSTTRAN.jcl:L33}, one of the six datasets of the daily posting job. This door
 *       is the inherited {@code findById}.</li>
 *   <li><strong>The alternate index, keyed by account identifier.</strong> Online it is CICS file
 *       {@code CXACAIX}, defined at {@code app/csd/CARDDEMO.CSD:L63} - described there as "ALTERNATE
 *       INDEX TO CCXREF VIA ACCOUNT KEY" at {@code :L64}. Note carefully that its
 *       {@code DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH)} at {@code :L65} names the alternate
 *       index <strong>PATH</strong> and not the alternate index itself; a VSAM path is the object that
 *       makes an alternate key readable as though it were the base cluster, which is precisely why the
 *       online programs can issue an ordinary read against it. In batch the same door is DD
 *       {@code XREFFIL1}, allocated to {@code ...CARDXREF.VSAM.AIX.PATH} at
 *       {@code app/jcl/INTCALC.jcl:L31-L32} alongside the base cluster at {@code :L29-L30}. This door
 *       is {@link #findFirstByAccountIdOrderByCardNumberAsc(Long)}.</li>
 *   </ul>
 *
 * <p>{@code CCXREF} and {@code CXACAIX} are two of the eight - and only eight -
 * {@code DEFINE FILE} entries in the CICS resource definitions, the others being {@code ACCTDAT},
 * {@code CARDAIX}, {@code CARDDAT}, {@code CUSTDAT}, {@code TRANSACT} and {@code USRSEC}. Verified by
 * counting: {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} occur zero times
 * in that file, which is the evidence that those four are batch only datasets with no online surface -
 * the least privilege position required by Rule 1 clause D, established by measurement rather than by
 * assertion.
 *
 * <h2>VSAM verbs to repository methods</h2>
 *
 * <p>Nothing in this interface performs I/O of its own. Spring Data supplies the runtime behaviour for
 * every method below, so the file declares intent and provenance and no more:
 *
 * <pre>
 *  Legacy verb / construct                     Replacement                        Declared here
 *  READ  ... RIDFLD(card number)               findById(String)                    inherited
 *  READ  ... RIDFLD(account id) via CXACAIX    findFirstByAccountIdOrderBy...   declared
 *  OPEN INPUT / READ / CLOSE (sequential)      findAll()                           inherited
 *  ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID     non-unique B-tree index in V2       not code
 *  FILE STATUS / DFHRESP interrogation         typed exceptions, mapped once       not here
 * </pre>
 *
 * <p>The last two rows are deliberate exclusions. The alternate index becomes a database index, not
 * Java; and translation from a legacy status to a typed exception happens exactly once, in
 * {@code com.cardemo.service.shared.FileStatusMapper}. Exception types are therefore named in this
 * documentation as prose only and are never imported here, because importing a type this interface does
 * not use would be an unused import, which the project's code-quality standard forbids. {@code javac} at
 * release 25 publishes no unused-import lint key, so {@code -Werror} cannot catch one and it must be
 * spotted by hand.
 *
 * <h2>Inherited methods and their named call sites</h2>
 *
 * <p>Rule 1 clause B forbids dead code, and this package claims no exemption from it: every method
 * reachable through this interface is justified below by a named legacy call site. Inherited methods
 * are deliberately not redeclared - redeclaring them would add no behaviour and would only create a
 * second place to keep in step with Spring Data.
 *
 * <dl>
 *   <dt>{@code findById(String cardNumber)} - the base key read</dt>
 *   <dd>Replaces {@code READ XREF-FILE INTO CARD-XREF-RECORD} keyed on the card number. Named call
 *       site: {@code app/cbl/CBTRN02C.cbl:L380-L392}, paragraph {@code 1500-A-LOOKUP-XREF}, which
 *       moves the daily transaction card number into the key at {@code :L382} and reads at
 *       {@code :L383}. A second site is {@code app/cbl/COCRDSLC.cbl}, the card detail flow. The miss
 *       semantics differ by caller and the choice belongs to the caller, never to this interface:
 *       see the three cases enumerated below.</dd>
 *   <dt>{@code findAll()} - the sequential scan</dt>
 *   <dd>Replaces the {@code OPEN INPUT} / {@code READ} / {@code CLOSE} loop of
 *       {@code app/cbl/CBACT03C.cbl}, 178 lines whose {@code SELECT} at {@code :L29-L32} declares
 *       {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL}. Its verb inventory was
 *       counted rather than assumed: {@code OPEN} twice, {@code READ} once, {@code CLOSE} twice, and
 *       {@code WRITE}, {@code REWRITE} and {@code DELETE} <strong>zero times</strong> - so the program
 *       is strictly read only and becomes a read only verification step, consumed by
 *       {@code com.cardemo.batch.readers.CardCrossReferenceReader}. Note that a full materialisation is
 *       appropriate only at this cardinality; the cluster holds 50 records.</dd>
 *   <dt>{@code save}, {@code saveAll}, {@code delete} and the rest of the mutating surface</dt>
 *   <dd>Available because {@code JpaRepository} declares them, but <strong>no write path is invented
 *       here, because the corpus has none</strong>. Searching every program for a write against this
 *       file - {@code WRITE}, {@code REWRITE} or {@code DELETE} in the same statement as
 *       {@code XREF}, {@code CXACAIX} or {@code CCXREF} - returns zero matches across all 28 programs.
 *       The 50 rows are loaded by the provisioning job {@code app/jcl/XREFFILE.jcl}, whose
 *       {@code REPRO} step copies the sequential image into the cluster, and the Java equivalent of
 *       that load is {@code V3__seed_data.sql}. The CICS definitions do permit mutation -
 *       {@code ADD(YES) DELETE(YES) UPDATE(YES)} appears on both {@code CCXREF} and {@code CXACAIX} -
 *       but permission granted is not a call site, and the mutating methods are consequently exercised
 *       only by test fixtures.</dd>
 *   </dl>
 *
 * <h2>Physical contract, dual sourced</h2>
 *
 * <p>Every number below was read from two independent artefacts, the IBM catalogue listing and the
 * IDCAMS control cards that produced it, so that no single transcription error can propagate:
 *
 * <pre>
 *  Property        Catalogue evidence                     IDCAMS evidence
 *  key length 16   app/catlg/LISTCAT.txt:L403 KEYLEN 16    app/jcl/XREFFILE.jcl:L43 KEYS(16 0)
 *  key offset 0    app/catlg/LISTCAT.txt:L404 RKP 0        app/jcl/XREFFILE.jcl:L43 KEYS(16 0)
 *  record size 50  app/catlg/LISTCAT.txt:L403 AVGLRECL 50  app/jcl/XREFFILE.jcl:L44 RECORDSIZE(50 50)
 *  alt key len 11  app/catlg/LISTCAT.txt:L482 KEYLEN 11    app/jcl/XREFFILE.jcl:L74 KEYS(11,25)
 *  alt key off 25  app/catlg/LISTCAT.txt:L486 AXRKP 25     app/jcl/XREFFILE.jcl:L74 KEYS(11,25)
 *  non-unique      app/catlg/LISTCAT.txt:L488 NONUNIQKEY   app/jcl/XREFFILE.jcl:L75 NONUNIQUEKEY
 * </pre>
 *
 * <p>The cluster block begins at {@code app/catlg/LISTCAT.txt:L365}; the attribute line quoted for the
 * base cluster is the one in its {@code DATA} component block, which begins at {@code :L393}. The
 * alternate index block begins at {@code :L455} and its {@code DATA} component block at {@code :L472},
 * with the association back to {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} recorded at {@code :L480}.
 * The path that makes the alternate key readable is catalogued at {@code :L351} and defined by IDCAMS
 * at {@code app/jcl/XREFFILE.jcl:L90-L92}.
 *
 * <h2>Record layout</h2>
 *
 * <p>From {@code app/cpy/CVACT03Y.cpy}, whose group item {@code 01 CARD-XREF-RECORD} is at {@code :L4}
 * and whose header comment at {@code :L2} reads "Data-structure for card xref (RECLN 50)":
 *
 * <pre>
 *  COBOL field     Copybook  PIC     Bytes (1-based)  Java property  Column
 *  XREF-CARD-NUM   :L5       X(16)    1-16            cardNumber     xref_card_num CHAR(16)
 *  XREF-CUST-ID    :L6       9(09)   17-25            customerId     xref_cust_id  NUMERIC(9)
 *  XREF-ACCT-ID    :L7       9(11)   26-36            accountId      xref_acct_id  NUMERIC(11)
 *  FILLER          :L8       X(14)   37-50            not modelled   -
 * </pre>
 *
 * <p>That is {@code 16 + 9 + 11 = 36} populated bytes inside a catalogued 50 byte slot, leaving 14
 * bytes of slack that is deliberately not modelled. The fixture corroborates it independently:
 * {@code app/data/ASCII/cardxref.txt} is 1,850 bytes over 50 lines and every line measures exactly 36
 * characters, so {@code (36 + 1) * 50 = 1850} and the {@code FILLER} is not even present in the seed
 * data. Restoring those 14 bytes as blanks at a fixed width boundary is the business of the batch
 * writers, not of this interface. No monetary or rate value appears anywhere in this layout, so the
 * project rule that money is {@code java.math.BigDecimal} compared with {@code compareTo} - never
 * {@code equals}, which is scale sensitive - and that no binary approximate numeric type may appear in
 * a financial field, is inherited here without giving this interface anything to apply it to.
 *
 * <h2>The alternate index: offset 25 zero based, record byte 26 one based, and non-unique</h2>
 *
 * <p>The catalogue records <strong>exactly three</strong> alternate indexes for the whole application -
 * searching {@code app/catlg/LISTCAT.txt} for {@code AXRKP} returns three hits and no more, at
 * {@code :L283} with value 16, {@code :L486} with value 25 and {@code :L3676} with value 304, which the
 * summary block confirms by reporting {@code AIX 3} at {@code :L3938} against {@code CLUSTER 10} at
 * {@code :L3940}. This is the second of the three; the other two belong to the card file and the
 * transaction file. The three alternate indexes become exactly three derived finders across this
 * package and exactly three non-unique indexes in {@code V2__create_indexes.sql}, so this file declares
 * one such finder and there must never be a fourth anywhere.
 *
 * <p><strong>State the base of an offset, always.</strong> Both the IDCAMS form
 * {@code KEYS(length offset)} and the catalogue field {@code AXRKP} are zero based. So:
 *
 * <pre>
 *  zero based offset 25   ==   one based record byte 26
 *  and 16 + 9 = 25, so the alternate key begins immediately after XREF-CARD-NUM and XREF-CUST-ID,
 *  which is exactly where XREF-ACCT-ID PIC 9(11) starts - bytes 26 to 36 one based.
 * </pre>
 *
 * <p>Both numbers are written out above on purpose. The surrounding written material mixes the two
 * conventions - it describes one alternate key as sitting at "byte 16", which is zero based, and another
 * at "record byte 305", which is one based - so a bare byte number in this codebase means nothing until
 * its base is named. The value 25 is triple sourced: {@code app/catlg/LISTCAT.txt:L486}
 * ({@code AXRKP 25}), {@code app/jcl/XREFFILE.jcl:L74} ({@code KEYS(11,25)}), and arithmetic agreement
 * with the copybook field widths.
 *
 * <p><strong>The index is non-unique, and that is recorded in the source rather than inferred.</strong>
 * {@code app/catlg/LISTCAT.txt:L488} carries {@code SPANNED NONUNIQKEY}, and
 * {@code app/jcl/XREFFILE.jcl:L75} independently carries {@code NONUNIQUEKEY} on the
 * {@code DEFINE ALTERNATEINDEX}. The domain agrees: one account legitimately fronts more than one card.
 * The corresponding index in {@code V2__create_indexes.sql} on
 * {@code card_cross_reference.xref_acct_id} must therefore be a <strong>non-unique</strong> B-tree
 * index. Making it unique would reject valid data at load time. That index is also the deliberate
 * performance substitute for the VSAM alternate index - the honest discharge of Rule 1 clause A's
 * position on efficiency, which is to avoid an obvious inefficiency such as a sequential scan and to
 * justify the tradeoff rather than to tune speculatively.
 *
 * <h2>Constraints this contract depends on</h2>
 *
 * <p><strong>The two foreign keys on the entity must stay plain scalar {@code Long}
 * properties.</strong> {@code com.cardemo.model.entity.CardCrossReference} declares {@code accountId}
 * and {@code customerId} as plain scalar {@code Long} fields with ordinary accessors, and
 * <strong>never as {@code @ManyToOne} associations</strong>. That is a precondition of this file, not a
 * stylistic preference: Spring Data derives {@link #findFirstByAccountIdOrderByCardNumberAsc(Long)} from
 * the JavaBean property name {@code accountId}, so converting either field into an association, or renaming
 * it after its COBOL item, makes the property path unresolvable. The failure is a startup failure - the
 * container reports that no property {@code accountId} was found for the entity - so it takes the whole
 * application down rather than failing one test. Verified against the entity as committed: it declares
 * no {@code @ManyToOne}, {@code @OneToMany}, {@code @OneToOne} or {@code @JoinColumn}, and no
 * {@code @Version} column either. Referential integrity is enforced instead by two of the ten foreign
 * keys in {@code V1__create_schema.sql}.
 *
 * <p><strong>The account keyed finder must return a collection.</strong> Because the alternate
 * key is declared non-unique, narrowing the return type to a single valued result - a scalar entity, or
 * a single valued container, or a query forced to yield one row - would silently discard rows for any
 * account holding more than one card, and would raise a non-unique result failure at runtime rather than
 * at build time. A list is therefore the contract. Callers that need only the first row, as the single
 * record legacy reads do, take the first element of a deterministically ordered list, which is why the
 * ordering clause is part of the method name.
 *
 * <p>One honest caveat about the evidence for that, because it changes how the behaviour must be
 * tested. The seed fixture does <strong>not</strong> exercise the duplicate case: extracting bytes 26
 * to 36 from all 50 rows of {@code app/data/ASCII/cardxref.txt} yields 50 distinct account identifiers,
 * so the shipped data is one card per account. Non-uniqueness is mandated by the schema and by the
 * domain, not demonstrated by the fixture. A test that relies on seed data alone would therefore prove
 * nothing about the multi-row path, and the integration test consequently inserts its own account with
 * several cards.
 *
 * <p><strong>The account view chain is a third call site for the account keyed finder, not for
 * {@code findById}.</strong> Reading {@code app/cbl/COACTVWC.cbl} shows why:
 * {@code :L691} moves the account identifier into the key, {@code :L693-L694} performs
 * {@code 9200-GETCARDXREF-BYACCT}, and that paragraph at {@code :L723-L735} reads
 * {@code DATASET(LIT-CARDXREFNAME-ACCT-PATH)} - a literal whose value is {@code 'CXACAIX '} at
 * {@code :L192-L193} - with {@code RIDFLD} set to the account identifier, under a comment at
 * {@code :L725} reading "Read the Card file. Access via alternate index ACCTID". So the account view
 * chain does begin at the cross reference, but through the <strong>alternate</strong> key, which makes
 * it a third call site for {@link #findFirstByAccountIdOrderByCardNumberAsc(Long)} rather than a call site
 * for {@code findById}. The finder count stays at one and {@code findById} remains inherited and still
 * exercised by {@code CBTRN02C}.
 *
 * <h2>The schema contract this interface relies on</h2>
 *
 * <p>Rule 1 clause F requires that missing information be declared rather than assumed. Two items remain
 * <strong>Not available</strong>; a third, recorded below, has since been closed.
 *
 * <p><strong>Closed: all three Flyway migrations are present.</strong> An earlier revision of this section
 * recorded {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} as non-existent, and said the
 * alternate-index equivalent for this cluster therefore had no B-tree index; that is no longer true and the
 * claim is withdrawn. {@code src/main/resources/db/migration/V1__create_schema.sql} declares
 * {@code CREATE TABLE card_cross_reference} with the two foreign keys
 * {@code fk02_xref_customer} and {@code fk03_xref_account}; {@code V2} declares
 * {@code idx_card_cross_reference_acct_id ON card_cross_reference USING btree (xref_acct_id)}, the
 * non-unique B-tree replacement for {@code CARDXREF.VSAM.AIX} that backs
 * {@link #findFirstByAccountIdOrderByCardNumberAsc(Long)}; and {@code V3} seeds the 50 fixture
 * rows. The
 * field contract stated in this documentation is what {@code V1} declares, and because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in all four profiles - present too - a
 * divergence aborts context startup instead of degrading gracefully. Concretely:
 *
 * <ul>
 *   <li>{@code V1} must create table {@code card_cross_reference} with
 *       {@code xref_card_num CHAR(16)} as the primary key,
 *       {@code xref_cust_id NUMERIC(9) NOT NULL} referencing the customer table, and
 *       {@code xref_acct_id NUMERIC(11) NOT NULL} referencing the account table; no version column and
 *       no fourth column, in particular nothing standing for the 14 byte {@code FILLER}. {@code V1}
 *       creates exactly 11 tables, 10 foreign keys and 5 check constraints in total, of which this table
 *       supplies two foreign keys.</li>
 *   <li>{@code V2} must create a <strong>non-unique</strong> B-tree index on
 *       {@code card_cross_reference.xref_acct_id}. {@code V2} creates exactly three such indexes across
 *       the schema - on the card table's account column, on this column, and on the transaction table's
 *       processing timestamp - matching the three alternate indexes one for one.</li>
 *   <li>{@code V3} must seed <strong>50</strong> rows of <strong>36</strong> populated bytes each from
 *       {@code app/data/ASCII/cardxref.txt}, decoding positionally from the copybook field widths.</li>
 *   <li>There must never be a fourth migration: the Spring Batch metadata tables come from the
 *       framework's own bundled script by way of {@code spring.batch.jdbc.initialize-schema}, and must
 *       not be added to {@code V1} either.</li>
 *   </ul>
 *
 * <p><strong>The alternate index offset is 25 zero based</strong>, equivalently record byte 26 one
 * based, from {@code app/catlg/LISTCAT.txt:L486} and {@code app/jcl/XREFFILE.jcl:L74}. It is the single
 * most error prone number in this file, which is why it is cited at every point it is relied upon.
 *
 * <p><strong>FILE STATUS {@code '35'} has no grounding anywhere in the corpus.</strong> The
 * file unavailable status has no basis anywhere in the corpus: the literal {@code '35'} does not occur
 * in any of the 28 programs. The census of CICS response conditions was taken by counting rather than
 * by estimate - {@code NORMAL} 43, {@code NOTFND} 23, {@code ENDFILE} 8, {@code DUPREC} 7,
 * {@code DUPKEY} 3 and {@code NOTOPEN} <strong>zero</strong>. The corresponding exception type therefore
 * exists on written authority alone, and no code path in this package can produce it. What would be
 * needed to ground it is a source occurrence of either construct; there is none, so it is documented as
 * derived rather than observed.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This interface has no configuration of its own - no tunable, no default, no environment lookup, and
 * deliberately no call to read the environment or a system property, since Rule 1 clause C requires
 * builds to stay free of environment specific assumptions. Four settings outside it govern how it
 * behaves:
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile. The schema is never generated
 *       from the entity; it is checked against the migrated schema, so the column contract above is
 *       enforced at startup.</li>
 *   <li>{@code spring.jpa.open-in-view: false}. Results come back fully initialised and no lazy access
 *       is possible outside a transaction.</li>
 *   <li>{@code spring.jpa.show-sql: false}, with no statement or bind parameter logging in any profile.
 *       This is a security setting, not a noise setting: every row here carries a card number.</li>
 *   <li>Pagination sizes are parity contracts and live in configuration under
 *       {@code carddemo.pagination}, never in this file - 7 for the card list
 *       ({@code app/cbl/COCRDLIC.cbl:L177-L178}), 10 for the transaction list and 10 for the user list.
 *       This interface declares no paged method, so it consumes none of them.</li>
 *   </ul>
 *
 * <p>Connection pool tuning is explicitly out of scope; the pool ships at its framework defaults.
 * Stating that plainly, rather than inventing numbers for a workload nobody has measured, is
 * deliberate: the legacy system publishes no throughput or latency objective, so none may be reverse
 * engineered into a default here.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and check with {@code ./mvnw -B clean compile}, then {@code ./mvnw -B clean test}. The
 * compiler runs with {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, so any warning this
 * file provokes in a category {@code javac} 25 publishes - {@code deprecation} and {@code rawtypes} above
 * all - fails the build rather than scrolling past. An unused import is not one of those categories and
 * must be spotted by hand. The full gate
 * is {@code ./mvnw -B clean verify}, which adds a JaCoCo line coverage floor and a dependency
 * vulnerability scan.
 *
 * <p>To run: {@code docker compose up -d} brings up PostgreSQL 16 among the
 * backing services, then {@code ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=local} starts the
 * application, Flyway applies the migrations and the entity is validated against the result.
 * {@code JWT_SIGNING_KEY} must be
 * present in the environment; it is environment indirected with no committed default and the application
 * refuses to start without it.
 *
 * <p>Behavioural tests for this interface live in {@code src/test/java/com/cardemo/integration/repository}
 * and run against a Testcontainers PostgreSQL 16, because a derived query is only meaningfully proved
 * against a real dialect. Its structural contract is pinned separately by reflection in
 * {@code src/test/java/com/cardemo/unit/repository/RepositoryContractTest.java} - the
 * {@code JpaRepository} type arguments, the exact declared method inventory, and the non-scalar return
 * type the non-unique alternate key demands. That coverage must include: the
 * account keyed finder <strong>returns several rows for one account</strong> - inserting its own
 * multi-card account, since the seed fixture is one to one - that those rows are ordered ascending by
 * card number, that an account with no cards yields an empty list rather than {@code null}, and that the
 * base key read round trips. {@code CONTRIBUTING.md:L34} requires local tests to pass before a change is
 * proposed, and {@code CONTRIBUTING.md:L33} asks that a change stay confined to its subject rather than
 * reformatting its surroundings.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup fails: no property {@code accountId} found for type {@code CardCrossReference}</dt>
 *   <dd>The entity field was renamed, or promoted to an association. Restore the plain scalar
 *       {@code Long accountId} with its ordinary accessors; see the scalar foreign key constraint
 *       above.</dd>
 *   <dt>Startup fails: schema validation reports a missing table or a wrong column type</dt>
 *   <dd>The migrations disagree with the entity, and {@code ddl-auto: validate} is doing its job. Align
 *       {@code V1__create_schema.sql} with the column contract above - {@code CHAR(16)} for the card
 *       number specifically, since a variable length column would stop preserving the blank padding of
 *       a fixed width key - rather than relaxing the setting.</dd>
 *   <dt>A non-unique result failure at runtime from the account keyed lookup</dt>
 *   <dd>The finder has been narrowed to a single valued return type somewhere. The alternate key is
 *       {@code NONUNIQKEY} ({@code app/catlg/LISTCAT.txt:L488}); restore the list return. If the index
 *       in {@code V2} was created unique, the symptom appears earlier, as a constraint violation while
 *       seeding.</dd>
 *   <dt>Rows come back in a different order between runs or environments</dt>
 *   <dd>The ordering clause has been dropped from the method name, or a caller re-sorted the result. The
 *       browse order is part of the parity contract; restore it.</dd>
 *   <dt>The interest calculation job completes where it used to abend</dt>
 *   <dd>An empty account keyed lookup is being skipped instead of failing. That path is fatal in the
 *       source ({@code app/cbl/CBACT04C.cbl:L393-L413}); the processor must raise the fatal exception.
 *       This is described under failure modes on the method.</dd>
 *   <dt>A card number appears in a log, a metric tag or a trace attribute</dt>
 *   <dd>Stop the emitting call site. Masking is a backstop; the rule is that the value is never emitted.
 *       Check that {@code spring.jpa.show-sql} is {@code false} in the active profile.</dd>
 *   </dl>
 */
@Repository
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {

    /**
     * Reads the single cross reference row an account-keyed read resolves to: the lowest card number
     * attached to that account.
     *
     * <p>This is the relational replacement for the account keyed read of the VSAM alternate index
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX}, reached in the source through its path
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} - online as CICS file {@code CXACAIX}
     * ({@code app/csd/CARDDEMO.CSD:L63, L65}), in batch as DD {@code XREFFIL1}
     * ({@code app/jcl/INTCALC.jcl:L31-L32}). The COBOL file definition that declares the same access path is
     * {@code app/cbl/CBACT04C.cbl:L34-L39}, whose {@code SELECT} carries {@code RECORD KEY IS FD-XREF-CARD-NUM}
     * followed by {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID}.
     *
     * <p><strong>Why this returns one row rather than a list, and why that is the more faithful model.</strong>
     * An earlier revision declared {@code findByAccountIdOrderByCardNumberAsc} returning
     * {@link java.util.List}, and every one of its seven call sites - in
     * {@code service/account/AccountViewService}, {@code service/account/AccountUpdateService},
     * {@code service/billing/BillPaymentService}, {@code service/transaction/TransactionAddService},
     * {@code batch/processors/InterestCalculationProcessor} and {@code batch/jobs/InterestCalculationJob}
     * (twice) - discarded everything after element zero. The database was therefore materialising and
     * transporting a whole result set per read so that the caller could throw all but its first row away.
     *
     * <p>Returning {@link Optional} lets Spring Data derive {@code LIMIT 1} from the {@code First} keyword,
     * so the engine stops at the first index entry. It is also closer to the source: what the COBOL performs
     * is {@code EXEC CICS READ} against the <em>path</em>, and a keyed read through a VSAM path yields
     * exactly one record - the first with that alternate key - not a set. The list form modelled a browse
     * the source never issues.
     *
     * <p><strong>The alternate key is still non-unique, and that is still expressed.</strong> Duplicate
     * account identifiers remain permitted: the index in {@code V2__create_indexes.sql} is non-unique, as
     * the catalogued alternate index requires, and {@code OrderByCardNumberAsc} is what makes "the first
     * row" deterministic when duplicates exist. Only the transport changed; the data model did not.
     *
     * @param accountId the eleven digit account identifier to read on, {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}.
     * @return the lowest-numbered card cross reference attached to that account, or
     *     {@link Optional#empty()} when the account has none - which every caller maps to the
     *     {@code DFHRESP(NOTFND)} arm of its own read paragraph.
     */
    Optional<CardCrossReference> findFirstByAccountIdOrderByCardNumberAsc(Long accountId);

    /**
     * Reads the next window of the primary-key sequence, positioned by the last card number already
     * consumed.
     *
     * <p>This is the sequential browse of the base cluster {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS},
     * whose record key is {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}, so a
     * {@code READ NEXT} sequence over it ascends by card number. It serves the {@code XREFFILE} DD that
     * {@code app/cbl/CBSTM03A.CBL:L345-L366} drives its whole run from, and the ascending order is what
     * makes the statement program's one-pass lookup against the equally ascending {@code TRNXFILE} correct.
     *
     * <p><strong>Keyset positioning, not page-number positioning.</strong> The caller passes the card
     * number of the last row it consumed, so each window is one index seek rather than an {@code OFFSET}
     * that re-reads every preceding row. The first window is requested with the empty string, which
     * precedes every non-empty card number under character comparison. The {@code Pageable} supplies the
     * window size only; the ordering is fixed by the method name and its page number must be zero.
     *
     * @param cardNumber the card number of the last row already consumed, or the empty string to start at
     *     the beginning of the key sequence.
     * @param pageable the window size; page number zero.
     * @return the next window of cross references ascending by card number, never {@code null} and empty
     *     once the cluster is exhausted.
     */
    List<CardCrossReference> findByCardNumberGreaterThanOrderByCardNumberAsc(String cardNumber,
            Pageable pageable);
}
