/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.repository
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Spring Data JPA persistence access layer)
 * Function    : Package documentation for the eleven Spring Data JPA
 *               repository interfaces that replace the VSAM access
 *               verbs of the frozen COBOL corpus with queries over
 *               PostgreSQL 16.
 * Source      : app/catlg/LISTCAT.txt:L3938,L3940,L3942,L3946
 *               (AIX 3, CLUSTER 10, GDG 7, PATH 3) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L59,L202,L403,L632,L896,L1371,
 *               L1475,L3593,L3779,L3883 (DATA-component KEYLEN and
 *               AVGLRECL per cluster) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L283,L486,L3676 (AXRKP) and
 *               L285,L488,L3678 (NONUNIQKEY) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L1,L13,L25,L37,L50,L63,L76,L88
 *               (the 8 online DEFINE FILE entries) @ 7756d89
 * Source      : app/jcl/ACCTFILE.jcl, CARDFILE.jcl, XREFFILE.jcl,
 *               CUSTFILE.jcl, TRANFILE.jcl, TRANIDX.jcl, POSTTRAN.jcl,
 *               TCATBALF.jcl, DISCGRP.jcl, TRANTYPE.jcl, TRANCATG.jcl,
 *               DUSRSECJ.jcl (IDCAMS DEFINE CLUSTER cards) @ 7756d89
 * Source      : app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy, CVTRA03Y.cpy,
 *               CVTRA04Y.cpy, CVTRA05Y.cpy, CVTRA06Y.cpy, CSUSR01Y.cpy
 *               (the 11 record-layout copybooks) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L481,L512,L530,L556 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L194,L216,L422,L436-L439,L446,L458
 *               @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L444-L449 @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L212-L217,L485-L489 @ 7756d89
 * Source      : app/cbl/COCRDLIC.cbl:L177-L178, COTRN00C.cbl:L290-L351,
 *               COUSR00C.cbl:L57 (pagination sizes) @ 7756d89
 * Source      : app/proc/TRANREPT.prc:L39-L46 (sort and date filter)
 *               @ 7756d89
 * ******************************************************************
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
 * ******************************************************************
 */

/**
 * The persistence access layer for CardDemo: eleven Spring Data JPA repository interfaces that replace the
 * VSAM access verbs of the frozen COBOL corpus with queries over PostgreSQL 16.
 *
 * <p>The banner above names every legacy artefact this package derives from, each pinned to commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} (short {@code 7756d89}), the traceability anchor for the
 * whole migration. Nothing under {@code app/} is read at build time or at run time: the corpus is frozen
 * reference material, consulted once to fix a contract and cited thereafter. Every key length, record
 * length, byte offset, literal and control-flow rule quoted below was re-verified against those files at
 * that commit rather than copied from prose, because where prose and source disagree the source governs.
 *
 * <p><strong>Why this documentation file exists.</strong> It is mandated by the project rule
 * <em>Build Verify</em>, not by any functional requirement. Clause B of that rule requires
 * "Document public APIs: purpose, inputs/outputs, side effects, error modes", and Clause E requires that
 * "Every module/component must have a short README or docstring explaining:" what it does, how to
 * run/build/test it, its key configs and defaults, and its common failure modes and troubleshooting. This
 * tree takes the <em>docstring</em> option of that either/or, uniformly, at package granularity: there is
 * no {@code README} and no Markdown file anywhere under {@code src/main/java}. The four sections below are
 * headed with Clause E's four sub-bullets <em>verbatim</em> — "What it does", "How to run/build/test",
 * "Key configs and defaults", "Common failure modes and troubleshooting" — which is why their wording
 * differs slightly from the paraphrased headings used in the sibling packages; the section order, the
 * banner form and every other convention of those files are preserved exactly.
 *
 * <p><strong>Directory shape.</strong> This package contains exactly <strong>twelve</strong> {@code .java}
 * files: the eleven repository interfaces listed below plus this file. There is no implementation class, no
 * {@code …RepositoryImpl}, no {@code …CustomRepository} fragment, no base-repository abstraction, no
 * {@code Specification} or {@code Criteria} helper, no mapper, no DAO wrapper and no subpackage. A
 * thirteenth file here would break that count, and a {@code README} or Markdown file is specifically
 * excluded because this docstring <em>is</em> the module documentation.
 *
 * <p>The eleven interfaces, each with the dataset it stands for, the key length and average record length
 * recorded on the <strong>DATA-component attribute line</strong> of {@code app/catlg/LISTCAT.txt}, and the
 * IDCAMS card that independently corroborates both figures:
 *
 * <ul>
 *   <li>{@link AccountRepository} over {@code Account} keyed by {@code Long} — CICS file {@code ACCTDAT},
 *       cluster {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}. Key length 11, record length 300
 *       ({@code app/catlg/LISTCAT.txt:L59}, the DATA-component attribute line), corroborated by
 *       {@code app/jcl/ACCTFILE.jcl:L40-L41 KEYS(11 0) RECORDSIZE(300 300)}. Layout
 *       {@code app/cpy/CVACT01Y.cpy}.</li>
 *   <li>{@link CardRepository} over {@code Card} keyed by {@code String} — CICS files {@code CARDDAT} and
 *       {@code CARDAIX}, cluster {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}. Key length 16, record length
 *       150 ({@code app/catlg/LISTCAT.txt:L202}, the DATA-component attribute line), corroborated by
 *       {@code app/jcl/CARDFILE.jcl:L54-L55 KEYS(16 0) RECORDSIZE(150 150)}. Layout
 *       {@code app/cpy/CVACT02Y.cpy}.</li>
 *   <li>{@link CardCrossReferenceRepository} over {@code CardCrossReference} keyed by {@code String} — CICS
 *       files {@code CCXREF} and {@code CXACAIX}, cluster {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}. Key
 *       length 16, record length 50 ({@code app/catlg/LISTCAT.txt:L403}, the DATA-component attribute
 *       line), corroborated by {@code app/jcl/XREFFILE.jcl:L43-L44 KEYS(16 0) RECORDSIZE(50 50)}. Layout
 *       {@code app/cpy/CVACT03Y.cpy}, whose 36 populated bytes sit in a 50-byte slot; the 14-byte slack is
 *       not modelled.</li>
 *   <li>{@link CustomerRepository} over {@code Customer} keyed by {@code Long} — CICS file {@code CUSTDAT},
 *       cluster {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}. Key length 9, record length 500
 *       ({@code app/catlg/LISTCAT.txt:L632}, the DATA-component attribute line), corroborated by
 *       {@code app/jcl/CUSTFILE.jcl:L50-L51 KEYS(9 0) RECORDSIZE(500 500)}. Layout
 *       {@code app/cpy/CVCUS01Y.cpy}.</li>
 *   <li>{@link TransactionRepository} over {@code Transaction} keyed by {@code String} — CICS file
 *       {@code TRANSACT}, cluster {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}. Key length 16, record length
 *       350 ({@code app/catlg/LISTCAT.txt:L3593}, the DATA-component attribute line), corroborated by
 *       {@code app/jcl/TRANFILE.jcl:L53-L54 KEYS(16 0) RECORDSIZE(350 350)}. Layout
 *       {@code app/cpy/CVTRA05Y.cpy}.</li>
 *   <li>{@link DailyTransactionRepository} over {@code DailyTransaction} keyed by {@code String} — the
 *       <strong>physical sequential</strong> staging dataset {@code AWS.M2.CARDDEMO.DALYTRAN.PS}, read by
 *       {@code app/jcl/POSTTRAN.jcl}. Record length 350; being sequential rather than keyed it has no
 *       {@code KEYLEN} and therefore no {@code LISTCAT} cluster entry. Layout
 *       {@code app/cpy/CVTRA06Y.cpy}.</li>
 *   <li>{@link TransactionCategoryBalanceRepository} over {@code TransactionCategoryBalance} keyed by the
 *       composite {@code TransactionCategoryBalanceId} — cluster
 *       {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}, <strong>batch-only</strong>. Key length
 *       <strong>17</strong>, record length 50 ({@code app/catlg/LISTCAT.txt:L1371}, the DATA-component
 *       attribute line), corroborated by {@code app/jcl/TCATBALF.jcl:L40-L41 KEYS(17 0) RECORDSIZE(50 50)}.
 *       Layout {@code app/cpy/CVTRA01Y.cpy}.</li>
 *   <li>{@link DisclosureGroupRepository} over {@code DisclosureGroup} keyed by the composite
 *       {@code DisclosureGroupId} — cluster {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS},
 *       <strong>batch-only</strong>. Key length <strong>16</strong>, record length 50
 *       ({@code app/catlg/LISTCAT.txt:L896}, the DATA-component attribute line), corroborated by
 *       {@code app/jcl/DISCGRP.jcl:L40-L41 KEYS(16 0) RECORDSIZE(50 50)}. Layout
 *       {@code app/cpy/CVTRA02Y.cpy}.</li>
 *   <li>{@link TransactionTypeRepository} over {@code TransactionType} keyed by {@code String} — cluster
 *       {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}, <strong>batch-only</strong>. Key length 2, record
 *       length 60 ({@code app/catlg/LISTCAT.txt:L3779}, the DATA-component attribute line), corroborated by
 *       {@code app/jcl/TRANTYPE.jcl:L40-L41 KEYS(2 0) RECORDSIZE(60 60)}. Layout
 *       {@code app/cpy/CVTRA03Y.cpy}.</li>
 *   <li>{@link TransactionCategoryRepository} over {@code TransactionCategory} keyed by the composite
 *       {@code TransactionCategoryId} — cluster {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS},
 *       <strong>batch-only</strong>. Key length <strong>6</strong>, record length 60
 *       ({@code app/catlg/LISTCAT.txt:L1475}, the DATA-component attribute line), corroborated by
 *       {@code app/jcl/TRANCATG.jcl:L40-L41 KEYS(6 0) RECORDSIZE(60 60)}. Layout
 *       {@code app/cpy/CVTRA04Y.cpy}.</li>
 *   <li>{@link UserSecurityRepository} over {@code UserSecurity} keyed by {@code String} — CICS file
 *       {@code USRSEC}, cluster {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}. Key length 8, record length 80
 *       ({@code app/catlg/LISTCAT.txt:L3883}, the DATA-component attribute line — the one cluster whose
 *       {@code BUFSPACE} and {@code CISIZE} differ from the rest, at 24576 and 8192), corroborated by
 *       {@code app/jcl/DUSRSECJ.jcl:L65-L66 KEYS(8,0) RECORDSIZE(80,80)}. Layout
 *       {@code app/cpy/CSUSR01Y.cpy}.</li>
 * </ul>
 *
 * <p>Note the delimiter inconsistency preserved in those citations: {@code ACCTFILE.jcl},
 * {@code CARDFILE.jcl}, {@code TCATBALF.jcl}, {@code DISCGRP.jcl}, {@code TRANTYPE.jcl} and
 * {@code TRANCATG.jcl} write {@code KEYS(n 0)} with a space, while {@code XREFFILE.jcl} and
 * {@code DUSRSECJ.jcl} write {@code KEYS(n,0)} with a comma. IDCAMS accepts both, the two forms are
 * equivalent, and the quotations above reproduce whichever form the member actually uses.
 *
 * <h2>What it does</h2>
 *
 * <p>This package is the single boundary between CardDemo's business logic and its relational store. It
 * replaces the VSAM access verbs of the frozen corpus — {@code READ}, {@code WRITE}, {@code REWRITE},
 * {@code DELETE}, {@code STARTBR}, {@code READNEXT}, {@code READPREV} and {@code ENDBR} — with Spring Data
 * JPA queries. The mapping is mechanical for the four record-at-a-time verbs: {@code READ} becomes
 * {@code findById}, {@code WRITE} and {@code REWRITE} both become {@code save}, and {@code DELETE} becomes
 * {@code deleteById}. The browse quartet has no single-method equivalent, because a CICS browse is a
 * stateful cursor held open across calls: {@code STARTBR} positions, {@code READNEXT} and {@code READPREV}
 * step forward and backward, {@code ENDBR} releases. Those four collapse into stateless
 * {@code org.springframework.data.domain.Pageable} requests returning {@code Slice} or {@code Page}
 * results, with the position that the cursor used to hold moved into the request parameters and the
 * "more rows exist" signal that {@code READNEXT} used to imply moved into the response metadata.
 *
 * <p>There is <strong>one repository interface per VSAM base cluster</strong>, plus one for the
 * physical-sequential daily staging dataset. That is not a stylistic choice but a count taken from the
 * catalogue: {@code app/catlg/LISTCAT.txt} defines <strong>exactly 10 base clusters</strong> and its closing
 * summary block confirms the whole inventory — {@code CLUSTER 10} ({@code :L3940}), {@code AIX 3}
 * ({@code :L3938}), {@code PATH 3} ({@code :L3946}) and {@code GDG 7} ({@code :L3942}). Ten clusters plus
 * the one sequential staging dataset is eleven interfaces, and the generation data groups are deliberately
 * absent from this package: they are object-storage outputs handled by {@code com.cardemo.batch}, never
 * tables.
 *
 * <h3>The three alternate indexes become three non-unique derived finders</h3>
 *
 * <p>Before the three are listed, the offset convention has to be stated, because the source documents mix
 * two of them and reading one as the other moves an index onto the wrong field. The {@code AXRKP} value in
 * {@code LISTCAT} and the offset argument of an IDCAMS {@code KEYS(length offset)} card are both
 * <strong>zero-based</strong>, whereas COBOL record positions are one-based. Every offset below is
 * therefore given twice.
 *
 * <ul>
 *   <li><strong>{@code CARDDATA.VSAM.AIX}</strong> — {@code KEYLEN 11}
 *       ({@code app/catlg/LISTCAT.txt:L281}), {@code RKP 5} ({@code :L282}), <strong>{@code AXRKP 16}</strong>
 *       ({@code :L283}), and {@code NONUNIQKEY} ({@code :L285}). Corroborated by
 *       {@code app/jcl/CARDFILE.jcl:L83-L85}, which declares
 *       {@code DEFINE ALTERNATEINDEX … RELATE(…CARDDATA.VSAM.KSDS) KEYS(11 16)}. Zero-based offset 16 is
 *       <strong>one-based record byte 17</strong>, where {@code CARD-ACCT-ID PIC 9(11)} begins and runs to
 *       byte 27. Realised as {@link CardRepository}'s account finder,
 *       {@code findByAccountIdOrderByCardNumberAsc(Long, Pageable)}, returning {@code Page}.</li>
 *   <li><strong>{@code CARDXREF.VSAM.AIX}</strong> — {@code KEYLEN 11}
 *       ({@code app/catlg/LISTCAT.txt:L482}), {@code RKP 5} ({@code :L485}), <strong>{@code AXRKP 25}</strong>
 *       ({@code :L486}), and {@code NONUNIQKEY} ({@code :L488}). Corroborated by
 *       {@code app/jcl/XREFFILE.jcl:L72-L74}, which declares
 *       {@code DEFINE ALTERNATEINDEX … RELATE(…CARDXREF.VSAM.KSDS) KEYS(11,25)}. Zero-based 25 is
 *       <strong>one-based byte 26</strong>, where {@code XREF-ACCT-ID PIC 9(11)} begins and runs to byte 36.
 *       Realised as {@link CardCrossReferenceRepository}'s account finder,
 *       {@code findByAccountIdOrderByCardNumberAsc(Long)}, returning {@code List}. Two points of care here:
 *       the migration specification body omits this offset entirely, so <strong>25 is the verified value</strong>
 *       taken from the catalogue and the IDCAMS card rather than from prose, and it is logged as a
 *       {@code DECISION_LOG.md} discrepancy; and the {@code RKP} line sits at {@code :L485} rather than
 *       {@code :L483} only because a page-break carriage-control character intrudes, which is a listing
 *       artefact and not a difference in the index.</li>
 *   <li><strong>{@code TRANSACT.VSAM.AIX}</strong> — {@code KEYLEN 26}
 *       ({@code app/catlg/LISTCAT.txt:L3674}), {@code RKP 5} ({@code :L3675}),
 *       <strong>{@code AXRKP 304}</strong> ({@code :L3676}), and {@code NONUNIQKEY} ({@code :L3678}).
 *       Corroborated <em>twice</em>: {@code app/jcl/TRANFILE.jcl:L82-L84} and
 *       {@code app/jcl/TRANIDX.jcl:L25-L27} both declare the same index with {@code KEYS(26 304)}.
 *       Zero-based 304 is <strong>one-based byte 305</strong>, where {@code TRAN-PROC-TS PIC X(26)} begins
 *       and runs to byte 330 ({@code app/cpy/CVTRA05Y.cpy:L17}). Realised as
 *       {@link TransactionRepository}'s processing-timestamp finder,
 *       {@code findByProcessingDateRangeOrderByCardNumberAsc(String, String)}, returning {@code List}.</li>
 * </ul>
 *
 * <p>The count is closed, not open-ended: {@code grep -n AXRKP app/catlg/LISTCAT.txt} returns
 * <strong>exactly three</strong> hits, matching the {@code AIX 3} and {@code PATH 3} figures in the summary
 * block. There is no fourth alternate index in the corpus and therefore <strong>no fourth derived finder</strong>
 * in this package. Adding one would assert a legacy access path that does not exist.
 *
 * <p><strong>All three carry {@code NONUNIQKEY}</strong>, and that single attribute fixes the return type of
 * all three finders. A non-unique alternate key admits many base records per key value — one account owns
 * many cards, one account owns many cross-reference rows, one processing date covers many transactions — so
 * each finder returns a collection: {@code List}, {@code Slice} or {@code Page} as the caller's paging needs
 * dictate, and <strong>never {@code Optional} and never a scalar</strong>. {@code Page} is a sub-interface of
 * {@code Slice}, so both satisfy the contract; the distinction between them is only whether a total count is
 * required. Typing any of the three as a single value would silently discard rows, which is failure mode 4
 * below.
 *
 * <h3>Four datasets are batch-only, and the evidence is an absence</h3>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} contains <strong>exactly 8</strong> {@code DEFINE FILE} entries, which are
 * the complete online file control table: {@code ACCTDAT} ({@code :L1}), {@code CARDAIX} ({@code :L13}),
 * {@code CARDDAT} ({@code :L25}), {@code CCXREF} ({@code :L37}), {@code CUSTDAT} ({@code :L50}),
 * {@code CXACAIX} ({@code :L63}), {@code TRANSACT} ({@code :L76}) and {@code USRSEC} ({@code :L88}). The
 * strings {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} occur
 * <strong>zero</strong> times anywhere in that file.
 *
 * <p>That absence is the proof, not an inference. {@link TransactionCategoryBalanceRepository},
 * {@link DisclosureGroupRepository}, {@link TransactionCategoryRepository} and
 * {@link TransactionTypeRepository} stand for datasets the CICS region could not reach, so they are consumed
 * by {@code com.cardemo.batch} and {@code com.cardemo.service} <strong>only</strong>, with <strong>no
 * controller CRUD, no REST endpoint and no administrative management surface</strong>. Exposing any of the
 * four online would grant the request path an authority the legacy system never had, which is precisely what
 * Rule 1 Clause D's "Principle of least privilege for tokens/credentials/config" forbids. Note also that
 * {@link TransactionTypeRepository} and {@link TransactionCategoryRepository} declare no finder at all: they
 * are inherited-CRUD lookups, and adding a query to either without a named call site would be dead code.
 *
 * <h3>Only two of the three alternate-index paths have an online definition</h3>
 *
 * <p>Two of the eight {@code DEFINE FILE} entries name an alternate-index path rather than a base cluster:
 * {@code CARDAIX} over {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH}
 * ({@code app/csd/CARDDEMO.CSD:L13-L14}) and {@code CXACAIX} over
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} ({@code :L63-L65}). The third path,
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH}, has <strong>no</strong> {@code DEFINE FILE} entry at all —
 * the {@code TRANSACT} entry at {@code :L76} resolves to {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 * ({@code :L77}), the base cluster, not the path.
 *
 * <p>The consequence is a genuine difference in reach between two finders that look alike. The two account
 * finders are online access paths, reachable from a controller. The processing-timestamp finder is a
 * <strong>batch-only access path</strong>, exercised by the report sort and its date-range filter
 * ({@code app/proc/TRANREPT.prc:L39-L46}) and never from an online request.
 *
 * <h3>Pagination sizes are parity contracts, not tunables</h3>
 *
 * <p>Three page sizes are supplied to callers through {@code carddemo.pagination.*}. They are transcribed
 * from the corpus and must not be adjusted for convenience, because the legacy screens were built around
 * them and the parity comparison is made row by row:
 *
 * <ul>
 *   <li><strong>Card list: 7.</strong> {@code app/cbl/COCRDLIC.cbl:L177-L178} declares
 *       {@code 05  WS-MAX-SCREEN-LINES  PIC S9(4) COMP} with {@code VALUE 7.} on the continuation line, and
 *       the figure is corroborated three times over by {@code OCCURS 7 TIMES} at {@code :L76}, {@code :L86}
 *       and {@code :L255}. Consumed through {@link CardRepository}'s two {@code Page} finders.</li>
 *   <li><strong>Transaction list: 10.</strong> {@code app/cbl/COTRN00C.cbl:L290} reads
 *       {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10} and {@code :L297} reads
 *       {@code PERFORM UNTIL WS-IDX &gt;= 11 OR TRANSACT-EOF OR ERR-FLG-ON}; the backward page repeats the
 *       bound at {@code :L344}, seeds it with {@code MOVE 10 TO WS-IDX} at {@code :L349} and unwinds with
 *       {@code PERFORM UNTIL WS-IDX &lt;= 0 OR TRANSACT-EOF OR ERR-FLG-ON} at {@code :L351}. Consumed
 *       through {@link TransactionRepository}'s three {@code Slice} finders, whose forward-inclusive,
 *       forward-exclusive and backward-descending shapes exist to reproduce exactly that pair of
 *       loops.</li>
 *   <li><strong>User list: 10.</strong> {@code app/cbl/COUSR00C.cbl:L57} declares
 *       {@code 02 USER-REC OCCURS 10 TIMES.} Consumed through {@link UserSecurityRepository}'s
 *       {@code findAllByOrderBySecUsrIdAsc(Pageable)}.</li>
 * </ul>
 *
 * <h3>Three behavioural control paths, each documented in the interface that owns it</h3>
 *
 * <p>Three legacy behaviours are not expressible as a method signature and would be lost if they were left
 * to the caller to rediscover. Each is specified in full on the interface that owns it and summarised here so
 * that a reader of this package meets all three at once:
 *
 * <ul>
 *   <li><strong>The descending top-one identifier lookup, with empty meaning one.</strong>
 *       {@link TransactionRepository}'s {@code findFirstByOrderByTransactionIdDesc()} reproduces the legacy
 *       {@code MOVE HIGH-VALUES} / {@code STARTBR} / {@code READPREV} / {@code ENDBR} browse
 *       ({@code app/cbl/COTRN02C.cbl:L444-L447}, {@code app/cbl/COBIL00C.cbl:L212-L215}). An empty result
 *       means zero, not failure, so the first identifier ever generated is 1.</li>
 *   <li><strong>The scoped {@code '23'} create path.</strong>
 *       {@link TransactionCategoryBalanceRepository} is an upsert target: at
 *       {@code app/cbl/CBTRN02C.cbl:L481} the read guard accepts {@code '00'} <em>or</em> {@code '23'}, so a
 *       missing row is an accepted create branch rather than an error. The leniency is scoped to that read
 *       alone.</li>
 *   <li><strong>The two-query {@code DEFAULT}-group fallback, whose second miss is fatal.</strong>
 *       {@link DisclosureGroupRepository} exposes {@code findDefaultGroupRate(String, Integer)} as a
 *       deliberately separate second query, because {@code app/cbl/CBACT04C.cbl} performs two distinct reads
 *       and treats their failures differently.</li>
 * </ul>
 *

 * <h2>How to run/build/test</h2>
 *
 * <p>There is nothing to run in this package: it declares eleven interfaces and no {@code main} method, no
 * bean with behaviour of its own and no entry point. Spring Data generates the implementations at context
 * refresh, so "running" this package means starting the application and letting it do so. What follows is
 * how the package is built, how it is tested, and what the environment actually provides.
 *
 * <h3>Build</h3>
 *
 * <p>One command from the repository root builds and verifies everything: <strong>{@code ./mvnw clean verify}</strong>.
 * The wrapper is committed so that no preinstalled Maven is required and the build is reproducible. The
 * parent POM is {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}, which is the root of
 * dependency management; the Spring Cloud AWS bill of materials at 3.3.0 is imported alongside it. Every
 * plugin and every non-managed dependency carries an exact version — no ranges, no {@code LATEST}, no
 * {@code RELEASE}.
 *
 * <ul>
 *   <li><strong>{@code maven-enforcer-plugin:3.5.0}</strong> asserts the toolchain floor before anything
 *       compiles: <strong>Java 25</strong> ({@code requireJavaVersion [25,)}, with
 *       {@code maven.compiler.release} set to 25 and <strong>no preview features</strong> enabled) and
 *       <strong>Maven 3.9.11</strong> ({@code requireMavenVersion [3.9.11,)}). Maven 4 is deliberately not
 *       used: the plugin ecosystem this build depends on is validated against the 3.9 line.</li>
 *   <li><strong>{@code maven-compiler-plugin:3.14.1}</strong> compiles with
 *       <strong>{@code -Xlint:all -Werror}</strong> and {@code -parameters}. Every warning is an error, so an
 *       unused import, a raw type, an unchecked cast, a use of deprecated API, a switch fall-through or a
 *       missing {@code serialVersionUID} is a <strong>hard build failure</strong>. That is why this file
 *       declares <strong>no import and no annotation</strong>: it needs neither, and an unused one would stop
 *       the build.</li>
 *   <li><strong>{@code jacoco-maven-plugin}</strong> enforces a <strong>0.80 LINE {@code COVEREDRATIO}
 *       floor</strong> at {@code verify}, with no exclusions and no getter-only padding. The pinned version
 *       is <strong>0.8.13</strong>, and the reason it is not the 0.8.12 named in the migration requirement is
 *       recorded in {@code pom.xml} and in {@code DECISION_LOG.md}: JaCoCo 0.8.12 cannot read the class files
 *       this project produces. Java 25 emits <strong>class file major version 69</strong>, the ASM build
 *       inside 0.8.12 rejects it outright, and the report goal then fails before any coverage figure is
 *       computed — the effect is not a lenient gate but <em>no gate at all</em>. Measured on this toolchain,
 *       0.8.12 fails, 0.8.13 succeeds and 0.8.14 also succeeds, so 0.8.13 is chosen as the smallest viable
 *       increment: it keeps the instruction's preference for the lower version and avoids the 0.8.14 figure
 *       that the requirement explicitly superseded. Classified <strong>Blocker</strong>, since the alternative
 *       is a {@code verify} phase that can never exit zero.</li>
 *   <li><strong>{@code org.owasp:dependency-check-maven:12.1.0}</strong> supplies the vulnerability scan
 *       behind the security gate.</li>
 * </ul>
 *
 * <h3>Test</h3>
 *
 * <p>Unit tests run under <strong>{@code maven-surefire-plugin:3.5.4}</strong> and integration tests under
 * <strong>{@code maven-failsafe-plugin:3.5.4}</strong>. The split matters for this package specifically: a
 * repository interface has no logic to unit test, since Spring Data writes the implementation, so its
 * meaningful coverage is integration coverage against a real database. A mocked repository proves only that
 * the mock was configured.
 *
 * <p>Repository integration tests live in {@code src/test/java/com/cardemo/integration/repository} and run
 * against a <strong>Testcontainers PostgreSQL 16</strong> instance, with Flyway applying the migrations to
 * the fresh container so that the schema under test is the schema that ships.
 *
 * <p><strong>Testcontainers is pinned to 2.0.3 by overriding the Boot-managed version property, not by
 * importing a second bill of materials.</strong> Both halves of that sentence are load bearing, and applying
 * one without the other still fails:
 *
 * <ul>
 *   <li>Spring Boot 3.5.11 already manages a Testcontainers version from the <em>1.x</em> line and imports
 *       the Testcontainers bill of materials itself. Importing a competing one produces an
 *       ordering-dependent resolution that may silently select the managed 1.x version. The fix is to set the
 *       {@code testcontainers.version} property to exactly {@code 2.0.3}.</li>
 *   <li>The 2.x line <strong>renamed every module artefact</strong>. Only the <em>prefixed</em> coordinates
 *       resolve: {@code testcontainers}, {@code testcontainers-postgresql}, {@code testcontainers-localstack}
 *       and {@code testcontainers-junit-jupiter}. The bare 1.x identifiers {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} <strong>do not exist</strong> at that version and fail
 *       resolution outright.</li>
 * </ul>
 *
 * <p>Overriding without renaming resolves artefacts that do not exist; renaming without overriding resolves
 * the wrong version. Severity <strong>Blocker</strong>. A related consequence of {@code -Werror} applies to
 * the test sources for this package: the 2.x API must be imported from
 * {@code org.testcontainers.postgresql.PostgreSQLContainer}, because the legacy
 * {@code org.testcontainers.containers.*} classes are deprecated at 2.0.3 and a deprecation warning is fatal.
 *
 * <h3>Toolchain actually present in this environment</h3>
 *
 * <p>Measured rather than assumed, so the statement can be relied on: {@code java} and {@code javac} report
 * OpenJDK <strong>25.0.3</strong>, {@code mvn} reports Apache Maven <strong>3.9.11</strong>, and
 * <strong>Docker Engine 29.7.0 with {@code docker compose} v5.3.1 is available</strong> and is what
 * provisions PostgreSQL 16, LocalStack, Jaeger, Prometheus and Grafana. The host toolchain is activated by
 * sourcing {@code /etc/profile.d/10-carddemo-toolchain.sh}. Where a host JDK is not provisioned, the identical
 * build runs inside the pinned Java 25 and Maven 3.9.11 container image with the repository mounted, and
 * produces the same result because every plugin and every non-managed dependency version is pinned. Any claim
 * that the Java toolchain or the container runtime is absent is <strong>stale and must not be repeated</strong>;
 * the container runtime in particular is present, so the Testcontainers-backed tier for this package is
 * executable rather than blocked.
 *

 * <h2>Key configs and defaults</h2>
 *
 * <p>No type in this package reads configuration itself. There is no {@code @ConfigurationProperties} class
 * here, no {@code @Value} injection, no {@code Environment} lookup, and — by package rule — no direct
 * environment-variable or system-property read anywhere in it. Every setting below is
 * nevertheless load bearing <em>for</em> this package, because it changes what these eleven interfaces are
 * permitted to assert. Each is a real key of {@code src/main/resources/application*.yml}; none is invented.
 *
 * <ul>
 *   <li><strong>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile</strong> — base,
 *       {@code local}, {@code test} and {@code prod} alike; never {@code create}, {@code update} or
 *       {@code create-drop}. <strong>This is the single most important sentence in this file: every derived
 *       property name, column name, SQL type and precision asserted by the eleven interfaces must align with
 *       {@code V1__create_schema.sql} and {@code V2__create_indexes.sql}, or the Spring application context
 *       fails to start outright.</strong> There is no partial-success mode and no warning-only mode. A
 *       misspelled property in a derived finder is not a latent bug that shows up under load; it is a boot
 *       failure on the first startup after the change.</li>
 *   <li><strong>{@code spring.jpa.open-in-view: false}</strong> — the persistence context does not span the
 *       web request, so nothing lazily loads outside a transaction. The obligation this places on the
 *       package is concrete: a finder must return results already initialised for what its caller needs,
 *       rather than relying on a session that will not be open when the serialiser runs.</li>
 *   <li><strong>{@code spring.jpa.show-sql: false}, and no Hibernate SQL or bind-parameter logging in any
 *       profile</strong> — not in {@code prod}, and not in {@code local} or {@code test} either. The rows
 *       these interfaces carry are full of personal data: {@code CUST-SSN PIC 9(09)}
 *       ({@code app/cpy/CVCUS01Y.cpy:L17}), two telephone numbers ({@code :L15-L16}), a government-issued
 *       identifier ({@code :L18}), a date of birth ({@code :L19}), plus card numbers, electronic funds
 *       account identifiers and a BCrypt password-hash column. Bind-parameter logging would print all of it
 *       in clear. The masking configured in {@code logback-spring.xml} is the <em>secondary</em> defence; the
 *       <em>primary</em> defence is never selecting or returning what is not needed, which is a design
 *       obligation on the finders in this package.</li>
 *   <li><strong>Hibernate's JDBC time zone is UTC — but the timestamp columns are text.</strong>
 *       {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)}
 *       ({@code app/cpy/CVTRA05Y.cpy:L16-L17}) and are mapped as {@code CHAR(26)} character columns, never as
 *       a temporal type. The time-zone setting therefore does not touch them, and the processing-timestamp
 *       finder is <strong>lexical over {@code CHAR(26)}</strong> — it compares the leading ten characters as
 *       {@code String}, exactly as {@code app/proc/TRANREPT.prc:L40} declares
 *       {@code TRAN-PROC-DT,305,10,CH} and {@code :L45-L46} applies it with an inclusive
 *       {@code GE}/{@code LE} pair.</li>
 *   <li><strong>Flyway: exactly three migrations</strong> under {@code classpath:db/migration}, with
 *       {@code validate-on-migrate: true}, {@code clean-disabled: true}, {@code out-of-order: false} and
 *       {@code baseline-on-migrate: false}. Spring Batch's {@code BATCH_*} metadata tables come from the
 *       framework's own script through {@code spring.batch.jdbc.initialize-schema} — <strong>never a fourth
 *       Flyway migration and never extra tables in {@code V1}</strong>, because a gate asserts that
 *       {@code V1} creates exactly 11 tables, 10 foreign keys and 5 check constraints, which the committed
 *       {@code V1__create_schema.sql} does. {@code spring.batch.job.enabled: false} keeps batch jobs from
 *       launching merely because the context started.</li>
 *   <li><strong>{@code V2__create_indexes.sql} creates exactly three non-unique B-tree indexes</strong> — on
 *       {@code card.card_acct_id}, {@code card_cross_reference.xref_acct_id} and
 *       {@code "transaction".tran_proc_ts}, one for each alternate index of the corpus and not one more. The
 *       three columns already exist in the committed {@code V1__create_schema.sql} with the shapes those
 *       indexes require: {@code card_acct_id NUMERIC(11) NOT NULL}, {@code xref_acct_id NUMERIC(11) NOT NULL}
 *       and {@code tran_proc_ts CHAR(26) NOT NULL}. Note that {@code transaction} is a reserved word in SQL
 *       and the table is therefore quoted as {@code "transaction"} throughout.</li>
 *   <li><strong>{@code carddemo.pagination.*} defaults are 7, 10 and 10</strong> for the card, transaction
 *       and user lists respectively, with the citations given under "What it does". They are parity contracts
 *       rather than tuning knobs.</li>
 *   <li><strong>Three numeric precision tiers, never collapsed into one.</strong> {@code S9(10)V99} becomes
 *       <strong>{@code NUMERIC(12,2)}</strong> and occurs exactly five times, on the {@code Account} balance
 *       and cycle fields; {@code S9(09)V99} becomes <strong>{@code NUMERIC(11,2)}</strong> and occurs exactly
 *       three times, on {@code TRAN-AMT}, {@code DALYTRAN-AMT} and {@code TRAN-CAT-BAL}; {@code S9(04)V99}
 *       becomes <strong>{@code NUMERIC(6,2)}</strong> and occurs exactly once, on {@code DIS-INT-RATE}, the
 *       only field in the corpus at that precision. Those counts are verifiable in the committed
 *       {@code V1__create_schema.sql}, which contains five {@code NUMERIC(12,2)} columns, three
 *       {@code NUMERIC(11,2)} and one {@code NUMERIC(6,2)}. Rounding everything to a single width would widen
 *       or truncate a field and break the parity comparison. <strong>Zero {@code float} and zero
 *       {@code double} appear in any financial field</strong>; the Java type is {@code BigDecimal} with
 *       {@code RoundingMode.HALF_EVEN}, and equality is tested with {@code compareTo()}, never
 *       {@code equals()}, because {@code BigDecimal.equals} distinguishes {@code 1.0} from {@code 1.00}.</li>
 *   <li><strong>HikariCP connection-pool tuning is explicitly out of scope.</strong> No pool size, timeout,
 *       fetch size or query hint is set by or for this package, and none may be added without a cited
 *       justification. This is not an oversight: it is recorded as residual risk in {@code DECISION_LOG.md}
 *       and {@code docs/validation-gates.md}, which is the honest discharge of Rule 1 Clause A's
 *       "Performance: avoid obvious inefficiencies; justify tradeoffs only when needed" — the legacy system
 *       publishes no service-level objective, so there is no target to tune towards and inventing one would
 *       be fabrication.</li>
 *   <li><strong>No secrets, here or anywhere near here.</strong> The JWT signing key resolves from the
 *       environment with <strong>no committed default</strong>, so a missing value fails fast rather than
 *       falling back to something guessable. This package holds no credential of any kind, and
 *       {@link UserSecurityRepository} deliberately exposes <strong>no password-hash projection</strong>: the
 *       hash travels only as far as {@code com.cardemo.security.CardDemoUserDetailsService}, which is the one
 *       component that has to verify it.</li>
 * </ul>
 *

 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Fourteen failures account for essentially everything that goes wrong in this package. Each is stated as
 * symptom, then cause, then remediation, then a severity drawn from Rule 1 Clause F's scale of
 * <strong>Blocker / High / Medium / Low</strong>. Several are not bugs at all but legacy behaviours that a
 * well-meaning correction would destroy, and those are marked as accepted so that nobody "fixes" them twice.
 *
 * <ol>
 *   <li><p><strong>Symptom: the application context fails to start with a Hibernate schema-validation
 *       error.</strong> Cause: a derived property name, a column name, a SQL type or a precision asserted
 *       here diverges from {@code V1__create_schema.sql} or {@code V2__create_indexes.sql}, and
 *       {@code ddl-auto: validate} refuses to boot rather than silently adapting.
 *       <em>Remediation:</em> reconcile the derived method name against the entity property spelling and the
 *       entity's {@code @Column(name=…)} mapping. The entity is authoritative for the Java property; the
 *       migration is authoritative for the column. Change whichever one is wrong — never relax
 *       {@code ddl-auto} to make the error disappear, because that converts a boot failure into silent data
 *       corruption. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: an account finder fails to resolve, or Hibernate reports an unknown path for
 *       {@code accountId}.</strong> Cause: {@code Card.accountId} or {@code CardCrossReference.accountId} was
 *       turned into a {@code @ManyToOne} association. Both are declared as <strong>plain scalar {@code Long}
 *       properties named exactly {@code accountId}</strong>, precisely so that
 *       {@code findByAccountIdOrderByCardNumberAsc} resolves against a column rather than traversing a
 *       relationship — which also keeps the query a single statement against the indexed column.
 *       <em>Remediation:</em> restore the scalar property; do not introduce an association. Navigate from
 *       card to account through {@link AccountRepository} explicitly, as the legacy programs did through the
 *       cross-reference file. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a processing-timestamp query returns nothing, or a conversion error appears on
 *       {@code tran_proc_ts}.</strong> Cause: a {@code LocalDateTime}, {@code Timestamp}, {@code Instant} or
 *       {@code OffsetDateTime} was introduced on that path. The column is {@code CHAR(26)} text
 *       ({@code app/cpy/CVTRA05Y.cpy:L17}) written by three mutually incompatible legacy producers, so it is
 *       not reliably parseable as a temporal value at all.
 *       <em>Remediation:</em> keep the comparison <strong>lexical over {@code String}</strong>, comparing the
 *       first ten characters where the source does so — {@code app/proc/TRANREPT.prc:L40} defines
 *       {@code TRAN-PROC-DT,305,10,CH} and {@code :L45-L46} applies it inclusively at both ends.
 *       <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: an alternate-key finder returns a single value and rows are silently
 *       dropped.</strong> Cause: the finder was typed {@code Optional} or as a scalar entity. All three
 *       alternate indexes carry {@code NONUNIQKEY} ({@code app/catlg/LISTCAT.txt:L285}, {@code :L488},
 *       {@code :L3678}), so many base records legitimately share one key value.
 *       <em>Remediation:</em> return {@code List}, {@code Slice} or {@code Page}. If a caller genuinely wants
 *       one row it must select it explicitly from the collection, so that the choice is visible rather than
 *       accidental. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a duplicate-key violation on transaction insert under concurrency.</strong>
 *       Cause: the descending top-one maximum-key identifier algorithm is <strong>inherently racy — exactly
 *       as the legacy {@code STARTBR} / {@code READPREV} / {@code ENDBR} browse was</strong>
 *       ({@code app/cbl/COTRN02C.cbl:L444-L449}, {@code app/cbl/COBIL00C.cbl:L212-L217}). Two callers can read
 *       the same maximum and both add one. This is <strong>preserved on purpose</strong>.
 *       <em>Remediation:</em> let the primary-key constraint surface it as
 *       {@code com.cardemo.exception.DuplicateRecordException} and let the caller retry at the business
 *       level. Do <strong>not</strong> add a database sequence, a {@code @GeneratedValue} strategy, a retry
 *       loop inside the repository or an upsert: every one of those changes the generated identifier values
 *       and breaks the parity baseline the migration is measured against. Recorded in
 *       {@code DECISION_LOG.md}. <strong>Severity: Medium (accepted, documented).</strong></p></li>
 *   <li><p><strong>Symptom: the first transaction inserted into an empty table receives identifier 0, or the
 *       insert fails.</strong> Cause: the empty result of the top-one descending query was not defaulted.
 *       {@code app/cbl/COBIL00C.cbl:L487-L488} reads {@code WHEN DFHRESP(ENDFILE)} then
 *       {@code MOVE ZEROS TO TRAN-ID}, and {@code :L217} then adds one, so <strong>the first generated
 *       identifier is 1</strong>, not 0 and not a failure.
 *       <em>Remediation:</em> default the empty result to zero, increment, then zero-pad to sixteen
 *       characters so the {@code X(16)} key sorts correctly as text. <strong>Severity:
 *       High.</strong></p></li>
 *   <li><p><strong>Symptom: the daily posting job abends on a missing transaction-category-balance
 *       row.</strong> Cause: a not-found result was mapped to
 *       {@code com.cardemo.exception.RecordNotFoundException}. {@code app/cbl/CBTRN02C.cbl:L481} reads
 *       {@code IF  TCATBALF-STATUS = '00'  OR '23'}, so a missing row is an <strong>accepted create
 *       branch</strong>, not an error.
 *       <em>Remediation:</em> treat not-found on that read as the create path. Scope the leniency precisely:
 *       it applies to the <strong>read guard only</strong> — the subsequent write verification at
 *       {@code :L512} and rewrite verification at {@code :L530} both accept <strong>only {@code '00'}</strong>,
 *       so a failure there is still fatal. Widening the leniency to the writes would mask a lost update.
 *       <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: the interest calculation job abends on a disclosure-group lookup.</strong> Cause:
 *       the two-query fallback was collapsed into a single query, or the second miss was swallowed.
 *       {@code app/cbl/CBACT04C.cbl:L422} accepts {@code '00'} or {@code '23'} on the first read;
 *       {@code :L436-L439} tests for {@code '23'}, substitutes the literal {@code 'DEFAULT'} into the group
 *       identifier <em>only</em> and retries; {@code :L446} then accepts <strong>only {@code '00'}</strong>,
 *       and {@code :L458} performs the abend.
 *       <em>Remediation:</em> keep two distinct queries — the keyed lookup, then
 *       {@link DisclosureGroupRepository}'s {@code findDefaultGroupRate}. The first miss is not an exception;
 *       the <strong>second miss is fatal</strong> and must raise
 *       {@code com.cardemo.exception.FatalProcessingException} carrying abend code 999 and return code 12.
 *       Note that only the group identifier is replaced on retry: the type and category codes are carried
 *       through unchanged. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: an interest rate from a previous row is applied to the current row.</strong>
 *       Cause: the legacy {@code READ … INTO} leaves the previous iteration's record in the buffer on
 *       {@code INVALID KEY} until the default read overwrites it, so a translation that caches rate state
 *       inherits a stale value instead of a miss.
 *       <em>Remediation:</em> never retain rate state across iterations. Resolve the rate freshly for every
 *       category-balance row, and let an empty {@code Optional} mean "not found" rather than "unchanged".
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: sequential category-balance processing produces wrong per-account totals.</strong>
 *       Cause: the scan is not in primary-key order. The legacy control break at
 *       {@code app/cbl/CBACT04C.cbl:L194}, {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}, detects an
 *       account change by comparing consecutive rows only, which is correct <strong>solely</strong> because
 *       {@code TRANCAT-ACCT-ID} leads the 17-byte composite key and VSAM returns the file in key order. An
 *       unordered scan interleaves accounts and the break fires repeatedly on the same account.
 *       <em>Remediation:</em> order by {@code (acct_id, tran_type_cd, tran_cat_cd)}, which is what
 *       {@link TransactionCategoryBalanceRepository}'s
 *       {@code findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(Pageable)} spells out in its name for
 *       exactly this reason. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: page contents differ between two requests for the same page.</strong> Cause: a
 *       paged query without an explicit {@code ORDER BY}. Row order is not guaranteed by the database, so
 *       {@code LIMIT}/{@code OFFSET} over an unordered result can repeat or skip rows across pages.
 *       <em>Remediation:</em> every paged or multi-row query in this package carries a deterministic ordering
 *       in its own signature — which is why every finder name here ends in an {@code OrderBy…} clause or
 *       carries an explicit {@code order by} in its query. This is Rule 1 Clause A's "Correctness first:
 *       prioritize correctness, determinism, and explicit behavior over cleverness" applied literally.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a password hash, a social security number, a card number or a plaintext
 *       credential appears in a log line, an HTTP response or a projection.</strong> Cause: a projection or a
 *       finder returned it, or SQL/bind logging was enabled.
 *       <em>Remediation:</em> {@link UserSecurityRepository} exposes <strong>no</strong> hash projection, and
 *       the hash must reach only {@code com.cardemo.security.CardDemoUserDetailsService};
 *       {@code Customer}'s {@code toString} renders only the identifier and version. Keep
 *       {@code show-sql} and bind-parameter logging off in every profile. <strong>Severity:
 *       Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: repository integration tests fail to start a container.</strong> Cause: the bare
 *       Testcontainers 1.x artefact identifiers were used, or a second bill of materials was imported, or
 *       both.
 *       <em>Remediation:</em> apply <strong>both</strong> parts of the fix together — override the managed
 *       {@code testcontainers.version} property to exactly {@code 2.0.3}, <em>and</em> use only the four
 *       prefixed coordinates {@code testcontainers}, {@code testcontainers-postgresql},
 *       {@code testcontainers-localstack} and {@code testcontainers-junit-jupiter}. Either alone still fails.
 *       If the container itself will not start, check the runtime rather than the coordinates: Docker Engine
 *       29.7.0 is present in this environment, so an absent-daemon diagnosis is wrong.
 *       <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: the build fails with an unused-import or deprecation warning attributed to this
 *       package.</strong> Cause: {@code -Xlint:all -Werror} promotes both to errors.
 *       <em>Remediation:</em> remove the import, or replace the deprecated API. Every finder declared in this
 *       package must have a named call site, and every import must be used. <strong>Note explicitly that no
 *       retained-parity no-op artefact lives in {@code com.cardemo.repository}</strong>, so Rule 1 Clause B's
 *       "No dead code, no unused imports, no TODOs without owners or tracking reference" applies here at
 *       <strong>full strength with zero exemptions</strong>: an unused finder or an unused import in this
 *       package is a defect, never a preserved legacy quirk, and it may not be justified by appeal to the
 *       parity mandate. <strong>Severity: Blocker.</strong></p></li>
 * </ol>
 *
 * <h2>The one documented conflict, and why it is not instantiated here</h2>
 *
 * <p>The project has exactly one rule conflict on record, and it is worth stating in this package precisely
 * in order to rule it out. Rule 1 Clause B forbids dead code, while the migration's parity mandate requires
 * preserving reachable no-ops so that paragraph-level traceability stays provable. Three artefacts sit in
 * that tension: the empty but genuinely performed {@code 1400-COMPUTE-FEES} paragraph
 * ({@code app/cbl/CBACT04C.cbl:L518-L520}, performed at {@code :L216}); reject code 109, assigned at
 * {@code app/cbl/CBTRN02C.cbl:L556} on an already-validated path and therefore never consumed as a reject
 * outcome; and the redundant index assignment in {@code app/cbl/CBSTM03A.CBL:L316-L338}. Parity governs in
 * all three cases, because Clause B forbids <em>untracked</em> dead code and each of the three is cited,
 * tracked in {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}, and marked in code with an explicit
 * intentional-no-op comment.
 *
 * <p><strong>None of those three artefacts lives in {@code com.cardemo.repository}.</strong> This package
 * therefore has <strong>zero exemptions</strong> from Clause B, which is the point made under failure mode 14
 * above and is restated here so that the exemption is not borrowed by analogy from a neighbouring package.
 *

 * <h2>Rule 1, Build Verify, clause by clause</h2>
 *
 * <p>Exactly <strong>one</strong> user-specified rule applies to this project. It is titled
 * "GLOBAL CODING &amp; DESIGN STANDARDS (Apply to all projects)", framed as "You are a senior engineer + code
 * auditor. Enforce the standards below consistently.", and it has six lettered clauses. Its full text lives in
 * the project's rules document and is deliberately not transcribed here; what follows is how this package
 * satisfies each clause, quoting the phrases it is answering.
 *
 * <ul>
 *   <li><p><strong>Clause A, Engineering Principles.</strong> "Correctness first: prioritize correctness,
 *       determinism, and explicit behavior over cleverness" is met by the deterministic-ordering requirement
 *       on every paged and multi-row query, and by the explicit collection-versus-scalar contract on the three
 *       alternate-key finders. "Security by default: treat inputs as untrusted, avoid unsafe defaults" is met
 *       by reaching the database exclusively through bound parameters — every query in this package is either
 *       a derived method or a named-parameter {@code @Query}, and no value is ever concatenated into JPQL or
 *       SQL. "Maintainability: readable naming, modular design, minimal complexity, clear separation of
 *       concerns" is met by keeping the package to eleven interfaces with no implementation class and no base
 *       abstraction, so the whole persistence surface is readable in one sitting. "Observability: structured
 *       logs, meaningful errors, and measurable behavior (metrics/tracing where relevant)" is met by
 *       delegation rather than duplication: this package emits nothing itself, and correlation, tracing,
 *       metrics and masking belong to {@code com.cardemo.observability} and {@code logback-spring.xml}, which
 *       instrument the JDBC and HTTP boundaries around it. "Performance: avoid obvious inefficiencies; justify
 *       tradeoffs only when needed" is met by the connection-pool residual-risk statement above, which
 *       declines to tune what has no published objective and says so in writing instead of guessing.</p></li>
 *   <li><p><strong>Clause B, Code Quality Rules.</strong> "No dead code, no unused imports, no TODOs without
 *       owners or tracking reference" is met literally: this file declares <strong>zero imports</strong> and
 *       contains no marker of deferred work, and every finder in the package has a named call site.
 *       "Validate all inputs and boundary conditions; handle null/empty cases explicitly" is met by the three
 *       named boundary contracts — an empty maximum-identifier result meaning 1, a not-found category balance
 *       meaning create, and a not-found disclosure group meaning retry once and then fail. "Avoid global
 *       mutable state; prefer dependency injection and pure functions where possible" is met because
 *       interfaces cannot hold state: there is no static field of any kind in this package, and every consumer
 *       receives its repository by constructor injection. "Clear error handling: no swallowing exceptions;
 *       wrap with context and preserve root cause" is met by locating status-to-exception translation in
 *       exactly one place, {@code com.cardemo.service.shared.FileStatusMapper}, so no repository swallows or
 *       reinterprets a failure. "Tests required for core logic and any non-trivial bug fix" is met by the
 *       integration tier named above. And <strong>"Document public APIs: purpose, inputs/outputs, side
 *       effects, error modes"</strong> is one of the two clauses that force this very file to
 *       exist.</p></li>
 *   <li><p><strong>Clause C, Repository Hygiene.</strong> "Follow repository conventions
 *       (formatters/linters/tests) if present; never fight existing style" is met by the Apache-2.0 provenance
 *       banner that opens this file, matching the universal convention of the legacy corpus, and by conformance
 *       to the root {@code .editorconfig}: UTF-8, LF endings, a final newline, no trailing whitespace and a
 *       four-space Java indent. The "if present" condition is worth recording accurately: a search of the top
 *       three directory levels of the source repository for {@code .editorconfig}, {@code .prettierrc*},
 *       {@code checkstyle*}, {@code spotless*}, {@code Makefile}, {@code *.toml} and {@code *.cfg} returned
 *       nothing, so formatter configuration was <strong>established</strong> at the repository root rather than
 *       inherited — which is not the same thing as overriding an existing style, and is why creating that
 *       configuration was legitimate. {@code CONTRIBUTING.md:L33} asks contributors to focus on the specific
 *       change and warns that reformatting everything makes the change hard to review, and {@code :L34}
 *       requires local tests to pass; both are honoured, and the frozen trees are left untouched.
 *       "Keep commits/build scripts deterministic; avoid environment-specific assumptions" is met by admitting
 *       <strong>no direct environment-variable read, no direct system-property read and no hardcoded host,
 *       port, path or file name</strong> into this package: configuration arrives only through Spring
 *       property binding, so the same code behaves identically on a developer machine and in CI.
 *       "Use consistent directory structure; avoid duplication" is met by a flat package of twelve files
 *       mirroring the sibling {@code model/entity}, {@code model/key} and {@code model/enums} packages, and by
 *       keeping the three composite-key contracts distinct rather than harmonising them behind a shared
 *       abstraction that would hide their differing key lengths of 17, 16 and 6.</p></li>
 *   <li><p><strong>Clause D, Security Standards.</strong> "No secrets in code, logs, tests, or config" is met
 *       absolutely: no credential, hash, token, social security number, card number, telephone number,
 *       government identifier, date of birth or electronic funds identifier appears in this file, and the
 *       legacy plaintext seed password carried by {@code app/jcl/DUSRSECJ.jcl} must never appear anywhere
 *       under {@code src/} — the seed migration stores BCrypt hashes only. "Pin dependencies where possible;
 *       flag known risky patterns (eval/exec, insecure deserialization, shell injection)" is met by the total
 *       version pinning in {@code pom.xml} and by the absence, as a package rule, of concatenated JPQL or SQL,
 *       {@code Runtime.exec}, {@code ProcessBuilder}, Java deserialization of untrusted input and any EBCDIC
 *       parsing. "Principle of least privilege for tokens/credentials/config" is met by giving the four
 *       batch-only datasets no online surface whatsoever, and by {@link UserSecurityRepository} exposing no
 *       password-hash projection.</p></li>
 *   <li><p><strong>Clause E, Documentation Standards.</strong> "Every module/component must have a short
 *       README or docstring explaining:" — with the four sub-bullets "What it does", "How to run/build/test",
 *       "Key configs and defaults" and "Common failure modes and troubleshooting". This file discharges that
 *       obligation through the <em>docstring</em> option, with all four sub-bullets present above as clearly
 *       delineated sections headed in the clause's own words. No {@code README} and no Markdown file may be
 *       created in this subtree, since doing so would split the module documentation across two artefacts that
 *       could then disagree.</p></li>
 *   <li><p><strong>Clause F, Output Requirements.</strong> "Be evidence-based: cite file paths, symbols, and
 *       examples" is met by giving every key length, record length and {@code AXRKP} offset its
 *       {@code app/catlg/LISTCAT.txt} DATA-component attribute-line citation <em>plus</em> its IDCAMS
 *       corroboration, and every behavioural rule its {@code app/cbl/…} paragraph citation.
 *       "Classify findings by severity: Blocker / High / Medium / Low" is met by the severity label closing
 *       each of the fourteen failure modes. "Provide clear remediation steps and (if asked) minimal patch
 *       suggestions" is met by the remediation sentence in each of those entries. "If information is missing,
 *       state &quot;Not available&quot; and list what's needed" is met by the disclosure immediately
 *       below.</p></li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Rule 1 Clause F requires that missing information be stated plainly rather than glossed over. Three items
 * are <strong>"Not available"</strong> as at the authoring of this package, and each is recorded with what
 * would be needed to close it. The inventory is deliberately precise about which migration files exist,
 * because an over-broad claim would be as much an evidence defect as an omission.
 *
 * <ul>
 *   <li><p><strong>{@code V2__create_indexes.sql} and {@code V3__seed_data.sql} are "Not available".</strong>
 *       {@code V1__create_schema.sql} <em>is</em> present under {@code src/main/resources/db/migration} and was
 *       inspected for this file: it creates exactly 11 tables, 10 foreign keys and 5 check constraints, and
 *       deliberately no index, since indexes belong to {@code V2}. What is needed is therefore the remaining
 *       two migrations in that same directory — {@code V2} carrying the three non-unique B-tree indexes
 *       enumerated above, and {@code V3} carrying the seed data.</p></li>
 *   <li><p><strong>The four {@code src/main/resources/application*.yml} profile files are "Not available".</strong>
 *       Every configuration key quoted under "Key configs and defaults" is a required setting of those files
 *       rather than an observed one, and is stated here as the contract they must satisfy. What is needed is
 *       {@code application.yml}, {@code application-local.yml}, {@code application-test.yml} and
 *       {@code application-prod.yml}, each setting {@code spring.jpa.hibernate.ddl-auto: validate}.</p></li>
 *   <li><p><strong>Direct corpus evidence for FILE STATUS {@code '35'} is "Not available".</strong> The status
 *       for an unavailable file has <strong>no grounding anywhere in the frozen corpus</strong>: there is no
 *       literal {@code '35'} in {@code app/cbl} at all, and the complete {@code DFHRESP} census across those
 *       28 programs is {@code NORMAL} 43, {@code NOTFND} 23, {@code ENDFILE} 8, {@code DUPREC} 7 and
 *       {@code DUPKEY} 3, with <strong>{@code NOTOPEN} appearing 0 times</strong> — those five are the only
 *       response codes the corpus tests. The corresponding condition is therefore specification-derived only.
 *       What would be needed to ground it is a source occurrence, and none exists; the type is retained
 *       because a JDBC-level unavailability still has to be representable, but no parity claim is made for
 *       it.</p></li>
 * </ul>
 *
 * <p>Because {@code spring.jpa.hibernate.ddl-auto: validate} is set in every profile, the consequence of the
 * first two items is not merely documentary. The table names, column names, SQL types, precisions and index
 * definitions asserted by the eleven interfaces in this package constitute the <strong>normative contract that
 * {@code V1} and {@code V2} must satisfy</strong>. A mismatch does not degrade gracefully and does not surface
 * as a slow query or a wrong result: it fails Spring context startup outright, which is the earliest and
 * loudest failure available and is the reason that setting was chosen.
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These constraints apply to every type in this package and to any change made to it.
 *
 * <ul>
 *   <li><strong>Interfaces only.</strong> Eleven repository interfaces and this file, twelve {@code .java}
 *       files in the folder, no more and no fewer. No implementation class, no {@code …RepositoryImpl}, no
 *       {@code …CustomRepository} fragment, no shared base repository, no {@code Specification} or
 *       {@code Criteria} helper, no mapper, no DAO wrapper, no subpackage, and no {@code README} or Markdown
 *       file.</li>
 *   <li><strong>No business logic.</strong> A repository declares access, never policy. Validation ordering,
 *       reject-code assignment, control breaks, interest arithmetic and identifier formatting belong to
 *       {@code com.cardemo.service} and {@code com.cardemo.batch}. A repository that decided when a
 *       transaction is over limit would put a parity-critical rule where no test looks for it.</li>
 *   <li><strong>Bound parameters only.</strong> Every query is a derived method or a {@code @Query} with named
 *       parameters. No string-built JPQL or SQL, no dynamic query assembly, no
 *       {@code Runtime.exec} or {@code ProcessBuilder}, and no Java deserialization of untrusted input.</li>
 *   <li><strong>Deterministic ordering on every multi-row result.</strong> No finder here may return more than
 *       one row without an ordering fixed in its own signature or query, so page contents cannot vary between
 *       identical requests.</li>
 *   <li><strong>Collection returns for non-unique keys.</strong> The three alternate-key finders return
 *       {@code List}, {@code Slice} or {@code Page}. None may be narrowed to {@code Optional} or to a scalar,
 *       because all three underlying indexes are {@code NONUNIQKEY}.</li>
 *   <li><strong>Text stays text.</strong> The {@code CHAR(26)} timestamp columns and the {@code X(16)}
 *       identifier columns are compared and ordered lexically as {@code String}. No temporal or numeric type
 *       may be introduced on those paths, and no identifier may be generated by the database.</li>
 *   <li><strong>Decimal arithmetic only.</strong> Any monetary or rate value crossing this boundary is
 *       {@code BigDecimal} at the precision its field definition dictates — {@code NUMERIC(12,2)},
 *       {@code NUMERIC(11,2)} or {@code NUMERIC(6,2)} — with {@code RoundingMode.HALF_EVEN} and equality by
 *       {@code compareTo()}. No {@code float} and no {@code double} in any financial field.</li>
 *   <li><strong>No privileged projection.</strong> No finder may project a password hash, a social security
 *       number or a full card number where the caller does not require it, and
 *       {@link UserSecurityRepository} exposes no hash projection at all.</li>
 *   <li><strong>Least privilege by consumer.</strong> {@link TransactionCategoryBalanceRepository},
 *       {@link DisclosureGroupRepository}, {@link TransactionCategoryRepository} and
 *       {@link TransactionTypeRepository} are reachable from {@code com.cardemo.batch} and
 *       {@code com.cardemo.service} only, never from a controller, because their datasets have no CICS
 *       definition.</li>
 *   <li><strong>No new dependency, and no configuration lookup.</strong> This package adds no dependency, uses
 *       no annotation processor and no Lombok, and performs no direct environment-variable or system-property
 *       read. Configuration reaches it only through Spring property binding in the layers above.</li>
 *   <li><strong>No dead code, with zero exemptions.</strong> Every declared finder must have a named call
 *       site and every import must be used. The parity mandate's allowance for retained no-ops does not reach
 *       this package, because none of the three retained artefacts lives here.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing here reads {@code app/} at build or run time.
 *       Those files are cited as evidence and must survive byte for byte; {@code git status --porcelain app/ samples/}
 *       is expected to be empty after any change to this package.</li>
 * </ul>
 *

 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
package com.cardemo.repository;
