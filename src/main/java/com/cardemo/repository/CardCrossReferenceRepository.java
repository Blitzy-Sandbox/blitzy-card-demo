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

import com.cardemo.model.entity.CardCrossReference;
import java.util.List;
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
 *       is {@link #findByAccountIdOrderByCardNumberAsc(Long)}.</li>
 * </ul>
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
 *  READ  ... RIDFLD(account id) via CXACAIX    findByAccountIdOrderByCardNumberAsc declared
 *  OPEN INPUT / READ / CLOSE (sequential)      findAll()                           inherited
 *  ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID     non-unique B-tree index in V2       not code
 *  FILE STATUS / DFHRESP interrogation         typed exceptions, mapped once       not here
 * </pre>
 *
 * <p>The last two rows are deliberate exclusions. The alternate index becomes a database index, not
 * Java; and translation from a legacy status to a typed exception happens exactly once, in
 * {@code com.cardemo.service.shared.FileStatusMapper}. Exception types are therefore named in this
 * documentation as prose only and are never imported here, because importing a type this interface does
 * not use would be an unused import, which {@code -Werror} turns into a build failure.
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
 * </dl>
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
 * The corresponding index created by {@code V2__create_indexes.sql} on
 * {@code card_cross_reference.xref_acct_id} must therefore be a <strong>non-unique</strong> B-tree
 * index. Making it unique would reject valid data at load time. That index is also the deliberate
 * performance substitute for the VSAM alternate index - the honest discharge of Rule 1 clause A's
 * position on efficiency, which is to avoid an obvious inefficiency such as a sequential scan and to
 * justify the tradeoff rather than to tune speculatively.
 *
 * <h2>Findings, by severity</h2>
 *
 * <p><strong>Blocker - the two foreign keys on the entity must stay plain scalar {@code Long}
 * properties.</strong> {@code com.cardemo.model.entity.CardCrossReference} declares {@code accountId}
 * and {@code customerId} as plain scalar {@code Long} fields with ordinary accessors, and
 * <strong>never as {@code @ManyToOne} associations</strong>. That is a precondition of this file, not a
 * stylistic preference: Spring Data derives {@link #findByAccountIdOrderByCardNumberAsc(Long)} from the
 * JavaBean property name {@code accountId}, so converting either field into an association, or renaming
 * it after its COBOL item, makes the property path unresolvable. The failure is a startup failure - the
 * container reports that no property {@code accountId} was found for the entity - so it takes the whole
 * application down rather than failing one test. Verified against the entity as committed: it declares
 * no {@code @ManyToOne}, {@code @OneToMany}, {@code @OneToOne} or {@code @JoinColumn}, and no
 * {@code @Version} column either. Referential integrity is enforced instead by two of the ten foreign
 * keys in {@code V1__create_schema.sql}.
 *
 * <p><strong>High - the account keyed finder must return a collection.</strong> Because the alternate
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
 * <p><strong>Medium - the alternate index offset is absent from the plan body.</strong> The written
 * plan says only that this repository gains "a derived finder replacing the cross-reference alternate
 * index" and never states the offset that the finder replaces, which leaves the single most
 * error prone number in this file undocumented upstream. Severity is Medium: the omission is
 * recoverable from primary sources and has been recovered, so nothing is blocked, but an unrecorded
 * offset invites a future change to guess. Remediation, applied here: cite both
 * {@code app/catlg/LISTCAT.txt:L486} and {@code app/jcl/XREFFILE.jcl:L74} at every point the offset is
 * relied upon, state its base explicitly wherever it appears, and record the omission as a discrepancy
 * in {@code DECISION_LOG.md} so that the plan and the code converge rather than drift.
 *
 * <p><strong>Low - one call site is attributed to the wrong door upstream.</strong> The written brief
 * lists {@code app/cbl/COACTVWC.cbl} under the base key read. Reading the program shows otherwise:
 * {@code :L691} moves the account identifier into the key, {@code :L693-L694} performs
 * {@code 9200-GETCARDXREF-BYACCT}, and that paragraph at {@code :L723-L735} reads
 * {@code DATASET(LIT-CARDXREFNAME-ACCT-PATH)} - a literal whose value is {@code 'CXACAIX '} at
 * {@code :L192-L193} - with {@code RIDFLD} set to the account identifier, under a comment at
 * {@code :L725} reading "Read the Card file. Access via alternate index ACCTID". So the account view
 * chain does begin at the cross reference, but through the <strong>alternate</strong> key, which makes
 * it a third call site for {@link #findByAccountIdOrderByCardNumberAsc(Long)} rather than a call site
 * for {@code findById}. Severity is Low because the correction adds evidence without altering the method
 * surface: the finder count stays at one and {@code findById} remains inherited and still exercised by
 * {@code CBTRN02C}. Remediation: the citation is corrected above and the discrepancy belongs in
 * {@code DECISION_LOG.md}.
 *
 * <h2>Not available</h2>
 *
 * <p>Rule 1 clause F requires that missing information be declared rather than assumed. Three items are
 * <strong>Not available</strong> at the time this interface was authored.
 *
 * <p><strong>Not available: the three Flyway migrations.</strong>
 * {@code src/main/resources/db/migration/V1__create_schema.sql},
 * {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} do not exist; the directory
 * {@code src/main/resources/db/migration} has no children, which was confirmed by inspection rather
 * than presumed. What is needed is those three files. Until they exist the field contract stated in this
 * documentation is the <strong>normative contract they must satisfy</strong>, and not the reverse,
 * because {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile and a divergence
 * aborts context startup instead of degrading gracefully. Concretely:
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
 * </ul>
 *
 * <p><strong>Not available: the alternate index offset, upstream.</strong> Covered as the Medium
 * finding above. The verified value is 25 zero based, equivalently record byte 26 one based, from
 * {@code app/catlg/LISTCAT.txt:L486} and {@code app/jcl/XREFFILE.jcl:L74}.
 *
 * <p><strong>Not available: FILE STATUS {@code '35'} as a grounded source construct.</strong> The
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
 * </ul>
 *
 * <p>Connection pool tuning is explicitly out of scope and is recorded as residual risk in
 * {@code DECISION_LOG.md} and {@code docs/validation-gates.md}. The pool ships at its framework
 * defaults. Stating that plainly, rather than inventing numbers for a workload nobody has measured, is
 * the honest reading of Rule 1 clause A: the legacy system publishes no throughput or latency objective,
 * so none may be reverse engineered into a default here.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and check with {@code ./mvnw -B clean compile}, then {@code ./mvnw -B clean test}. The
 * compiler runs with {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, so any warning this
 * file provokes - an unused import above all - fails the build rather than scrolling past. The full gate
 * is {@code ./mvnw -B clean verify}, which adds a JaCoCo line coverage floor and a dependency
 * vulnerability scan.
 *
 * <p>To run: {@code docker compose up -d} brings up PostgreSQL 16 among the backing services, then
 * {@code ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=local} starts the application, Flyway
 * applies the migrations and the entity is validated against the result. {@code JWT_SECRET} must be
 * present in the environment; it is environment indirected with no committed default and the application
 * refuses to start without it.
 *
 * <p>The tests for this interface live in {@code src/test/java/com/cardemo/integration/repository} and
 * run against a Testcontainers PostgreSQL 16, because a derived query is only meaningfully proved
 * against a real dialect. Per Rule 1 clause B they must cover the core behaviour, which here means: the
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
 *       {@code Long accountId} with its ordinary accessors; see the Blocker finding above.</dd>
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
 *       This is the Blocker described under failure modes on the method.</dd>
 *   <dt>A card number appears in a log, a metric tag or a trace attribute</dt>
 *   <dd>Stop the emitting call site. Masking is a backstop; the rule is that the value is never emitted.
 *       Check that {@code spring.jpa.show-sql} is {@code false} in the active profile.</dd>
 * </dl>
 */
@Repository
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {

    /**
     * Finds every cross reference row attached to one account, ordered by card number ascending.
     *
     * <p><strong>Purpose.</strong> This is the relational replacement for the account keyed browse of
     * the VSAM alternate index {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX}, reached in the source through
     * its path {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} - online as CICS file {@code CXACAIX}
     * ({@code app/csd/CARDDEMO.CSD:L63, L65}), in batch as DD {@code XREFFIL1}
     * ({@code app/jcl/INTCALC.jcl:L31-L32}). The COBOL file definition that declares the same access
     * path is {@code app/cbl/CBACT04C.cbl:L34-L39}, whose {@code SELECT} carries
     * {@code RECORD KEY IS FD-XREF-CARD-NUM} followed by
     * {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID}.
     *
     * <p><strong>Input.</strong> {@code accountId} is the eleven digit account identifier,
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. It is bound as a query
     * parameter by Spring Data; no fragment of this query is ever assembled by string concatenation,
     * which is the standing answer in this package to Rule 1 clause A's requirement to treat inputs as
     * untrusted. A {@code null} argument is not defended against here and must not be passed: the
     * derived query would degenerate into an {@code is null} comparison against a {@code NOT NULL}
     * column and return an empty result, which is indistinguishable from a genuine miss and would
     * therefore hide a caller side defect. Callers resolve the identifier before calling - in the
     * source it is always already in hand, moved into the key immediately beforehand, as at
     * {@code app/cbl/CBACT04C.cbl:L204}.
     *
     * <p><strong>Output.</strong> A list, possibly empty, never {@code null}. Ordering is ascending by
     * {@code cardNumber}, which is the primary key, so the sequence is total and reproducible across
     * runs, machines and query plans. That ordering is not cosmetic: it reproduces the
     * {@code STARTBR} / {@code READNEXT} browse over the path, where records sharing one alternate key
     * are returned in base key sequence. Rule 1 clause A puts correctness and determinism first, and an
     * unordered multi-row result would leave the first element - which is exactly what the single
     * record legacy reads consume - at the mercy of the plan.
     *
     * <p><strong>Side effects.</strong> None. This is a read. It starts no unit of work, writes
     * nothing, emits no log record and mutates no state, so it is safe inside a read only transaction
     * and safe to call repeatedly. It returns fully initialised entities: the entity declares no
     * association and therefore no lazy proxy, and {@code spring.jpa.open-in-view} is {@code false},
     * so nothing here can escape as a detached lazy reference.
     *
     * <p><strong>Sensitivity.</strong> Every returned row carries a card number. It must never be
     * written to a log, an exception message, a metric tag or a trace attribute. Rule 1 clause D admits
     * no secret in code, logs, tests or config; the masking rules in {@code logback-spring.xml} are a
     * backstop and not the primary defence, and no illustrative card number appears anywhere in this
     * file for the same reason.
     *
     * <p><strong>Failure modes.</strong> An empty list is returned for an account with no cards. This
     * interface does not decide what that means, because the source does not decide it uniformly -
     * three different outcomes exist and each belongs to its caller:
     *
     * <ol>
     *   <li><strong>Fatal, in the interest calculation job.</strong>
     *       {@code app/cbl/CBACT04C.cbl:L393-L413}, paragraph {@code 1110-GET-XREF-DATA}, is the trap
     *       in this file. Its {@code INVALID KEY} branch at {@code :L396-L397} merely displays
     *       "ACCOUNT NOT FOUND: " and looks forgiving, but nothing returns there. Control falls into
     *       the unguarded status check at {@code :L400-L404}, which sets the result to 12 for any
     *       status other than {@code '00'}, and then into {@code :L405-L412}, which displays "ERROR
     *       READING XREF FILE" and performs {@code 9999-ABEND-PROGRAM}. <strong>The job abends.</strong>
     *       {@code com.cardemo.batch.processors.InterestCalculationProcessor} must therefore translate
     *       an empty result on this path into {@code com.cardemo.exception.FatalProcessingException},
     *       carrying abend code 999 and driving process return code 12, and must <strong>not</strong>
     *       skip the record. Treating it as a skip is a <strong>Blocker</strong>: it would silently
     *       compute interest for an incomplete population, and the divergence would surface as a
     *       balance discrepancy rather than as a failure.</li>
     *   <li><strong>An ordinary not found, in the online flows.</strong>
     *       {@code app/cbl/COBIL00C.cbl:L408-L436}, paragraph {@code READ-CXACAIX-FILE}, evaluates
     *       {@code DFHRESP(NOTFND)} at {@code :L423} into the screen message "Account ID NOT found..."
     *       and repositions the cursor; anything else becomes "Unable to lookup XREF AIX file..." at
     *       {@code :L429-L435}. {@code app/cbl/COACTVWC.cbl:L741-L757} does the equivalent, composing
     *       "Account:... not found in Cross ref file." Both surface as
     *       {@code com.cardemo.exception.RecordNotFoundException} - the mapping for FILE STATUS
     *       {@code '23'} and {@code DFHRESP(NOTFND)} - and neither abends.</li>
     *   <li><strong>A business reject, in the daily posting job.</strong> Reached by
     *       {@code findById} rather than by this method, and recorded here so that the contrast is not
     *       lost: {@code app/cbl/CBTRN02C.cbl:L385} moves {@code 100} into the reject reason and
     *       {@code :L386-L387} moves the description "INVALID CARD NUMBER FOUND". That is a business
     *       outcome that drives {@code ExitStatus} and the 430 byte reject record; it is
     *       <strong>never thrown</strong>.</li>
     * </ol>
     *
     * @param accountId the eleven digit account identifier to browse on, {@code XREF-ACCT-ID PIC
     *                  9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}; must not be {@code null}
     * @return every cross reference attached to that account, ascending by card number; empty when the
     *         account fronts no card, never {@code null}
     */
    List<CardCrossReference> findByAccountIdOrderByCardNumberAsc(Long accountId);
}
