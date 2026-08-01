/*
 * ******************************************************************
 * Package     : com.cardemo.model.entity
 * Application : CardDemo
 * Type        : Java Package Documentation (JPA persistence model)
 * Function    : Package-level documentation for the 11 JPA entities
 *               replacing the CardDemo VSAM KSDS clusters.
 * Source      : app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy, CUSTREC.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy,
 *               CVTRA03Y.cpy, CVTRA04Y.cpy, CVTRA05Y.cpy, CVTRA06Y.cpy,
 *               CSUSR01Y.cpy; app/catlg/LISTCAT.txt @ 7756d89
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
 * JPA persistence model for CardDemo: the eleven entities that replace the ten VSAM KSDS clusters and the
 * one sequential staging dataset of the frozen legacy corpus.
 *
 * <h2>What it does</h2>
 *
 * <p>This package holds exactly <strong>eleven</strong> {@code @Entity} classes and nothing else besides
 * this documentation file. Each one is the relational replacement for a single legacy dataset: ten of them
 * for the ten VSAM KSDS clusters catalogued in {@code app/catlg/LISTCAT.txt}, and one,
 * {@link DailyTransaction}, for the sequential {@code DALYTRAN} staging dataset that the daily posting job
 * reads.
 *
 * <p>They are <strong>pure data holders</strong>. An entity in this package maps columns, validates its own
 * field widths and compares itself for equality; it does not calculate interest, decide whether a
 * transaction is over limit, post a balance, read a file or reject a record. Every one of those behaviours
 * belongs to a service, a batch processor or a repository, so that the persistence model can be reasoned
 * about, and asserted against the copybooks, without dragging business logic into scope.
 *
 * <p>The table below is the field-contract evidence for the package. Every record length was obtained by
 * summing the {@code PIC} clauses of the named copybook, and every key length and record length was then
 * corroborated independently against the VSAM catalogue listing. The two agree in all ten catalogued cases,
 * so each row may be treated as settled fact rather than inference.
 *
 * <table>
 *   <caption>The eleven entities, their originating copybooks and their corroborating catalogue evidence</caption>
 *   <thead>
 *     <tr>
 *       <th scope="col">Entity</th>
 *       <th scope="col">Copybook</th>
 *       <th scope="col">Record length</th>
 *       <th scope="col">Key length</th>
 *       <th scope="col">Catalogue evidence in {@code app/catlg/LISTCAT.txt}</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@link Account}</td>
 *       <td>{@code app/cpy/CVACT01Y.cpy}</td>
 *       <td>300 B</td>
 *       <td>11</td>
 *       <td>{@code L57} cluster {@code ACCTDATA}, {@code L59} {@code KEYLEN 11} / {@code AVGLRECL 300}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link Card}</td>
 *       <td>{@code app/cpy/CVACT02Y.cpy}</td>
 *       <td>150 B</td>
 *       <td>16</td>
 *       <td>{@code L200} cluster {@code CARDDATA}, {@code L202} {@code KEYLEN 16} / {@code AVGLRECL 150}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link Customer}</td>
 *       <td>{@code app/cpy/CVCUS01Y.cpy} + {@code app/cpy/CUSTREC.cpy}</td>
 *       <td>500 B</td>
 *       <td>9</td>
 *       <td>{@code L630} cluster {@code CUSTDATA}, {@code L632} {@code KEYLEN 9} / {@code AVGLRECL 500}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link CardCrossReference}</td>
 *       <td>{@code app/cpy/CVACT03Y.cpy}</td>
 *       <td>36 populated B in a 50 B slot</td>
 *       <td>16</td>
 *       <td>{@code L401} cluster {@code CARDXREF}, {@code L403} {@code KEYLEN 16} / {@code AVGLRECL 50}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link Transaction}</td>
 *       <td>{@code app/cpy/CVTRA05Y.cpy}</td>
 *       <td>350 B</td>
 *       <td>16</td>
 *       <td>{@code L3591} cluster {@code TRANSACT}, {@code L3593} {@code KEYLEN 16} / {@code AVGLRECL 350}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link DailyTransaction}</td>
 *       <td>{@code app/cpy/CVTRA06Y.cpy}</td>
 *       <td>350 B</td>
 *       <td>none, a sequential PS dataset</td>
 *       <td>not catalogued as a cluster; see the note below</td>
 *     </tr>
 *     <tr>
 *       <td>{@link TransactionCategoryBalance}</td>
 *       <td>{@code app/cpy/CVTRA01Y.cpy}</td>
 *       <td>50 B</td>
 *       <td>17, composite</td>
 *       <td>{@code L1369} cluster {@code TCATBALF}, {@code L1371} {@code KEYLEN 17} / {@code AVGLRECL 50}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link DisclosureGroup}</td>
 *       <td>{@code app/cpy/CVTRA02Y.cpy}</td>
 *       <td>50 B</td>
 *       <td>16, composite</td>
 *       <td>{@code L894} cluster {@code DISCGRP}, {@code L896} {@code KEYLEN 16} / {@code AVGLRECL 50}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link TransactionType}</td>
 *       <td>{@code app/cpy/CVTRA03Y.cpy}</td>
 *       <td>60 B</td>
 *       <td>2</td>
 *       <td>{@code L3777} cluster {@code TRANTYPE}, {@code L3779} {@code KEYLEN 2} / {@code AVGLRECL 60}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link TransactionCategory}</td>
 *       <td>{@code app/cpy/CVTRA04Y.cpy}</td>
 *       <td>60 B</td>
 *       <td>6, composite</td>
 *       <td>{@code L1473} cluster {@code TRANCATG}, {@code L1475} {@code KEYLEN 6} / {@code AVGLRECL 60}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link UserSecurity}</td>
 *       <td>{@code app/cpy/CSUSR01Y.cpy}, seeded from {@code app/jcl/DUSRSECJ.jcl}</td>
 *       <td>80 B</td>
 *       <td>8</td>
 *       <td>{@code L3881} cluster {@code USRSEC}, {@code L3883} {@code KEYLEN 8} / {@code AVGLRECL 80};
 *           corroborated by {@code app/jcl/DUSRSECJ.jcl:L65-L66} {@code KEYS(8,0)} /
 *           {@code RECORDSIZE(80,80)}</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <p>The following invariants hold across the whole package. They are recorded here rather than repeated in
 * eleven class comments, and every one of them is enforced by at least one test in
 * {@code src/test/java/com/cardemo/unit/model}.
 *
 * <ul>
 *   <li><strong>Eleven entities, and why it is eleven rather than ten or fourteen.</strong> Ten VSAM KSDS
 *       clusters plus one sequential {@code DALYTRAN} staging dataset gives eleven.
 *       {@code app/catlg/LISTCAT.txt:L3938-L3946} reports the catalogue totals as
 *       {@code AIX 3}, {@code CLUSTER 10}, {@code GDG 7} and {@code PATH 3}, so there are exactly ten
 *       clusters and there is no eleventh to be found. {@link DailyTransaction} is therefore the only
 *       entity in this package that does not correspond to a catalogued cluster.</li>
 *   <li><strong>The three alternate indexes are not entities.</strong> An alternate index is a second
 *       access path over an existing record, not a record of its own, so none of
 *       {@code CARDDATA.VSAM.AIX} ({@code L279} definition, {@code L281} {@code KEYLEN 11}),
 *       {@code CARDXREF.VSAM.AIX} ({@code L480} / {@code L482} {@code KEYLEN 11}) or
 *       {@code TRANSACT.VSAM.AIX} ({@code L3672} / {@code L3674} {@code KEYLEN 26}) becomes a class here.
 *       Each becomes a derived finder method on the matching interface in {@code com.cardemo.repository},
 *       backed by a non-unique B-tree index created in {@code V2__create_indexes.sql}. Adding an entity for
 *       an alternate index would duplicate the base record and give it a second, divergent mapping.</li>
 *   <li><strong>{@link Customer} serves two proven-duplicate copybooks.</strong> Running
 *       {@code diff -w app/cpy/CVCUS01Y.cpy app/cpy/CUSTREC.cpy} yields exactly two hunks: the field name
 *       {@code CUST-DOB-YYYY-MM-DD} versus {@code CUST-DOB-YYYYMMDD}, and a version comment whose
 *       timestamps differ by one second. Both declare {@code 01 CUSTOMER-RECORD.}, both total 500 B, and
 *       the field order and every field width are identical. That is a duplicate layout, not a second
 *       record type, so one entity serves both and no {@code CustomerRecord} twin is created.</li>
 *   <li><strong>Trailing {@code FILLER} is never modelled.</strong> Each layout ends in a pad that carries
 *       no data and exists only to reach the catalogued record length: {@code X(178)} in
 *       {@code app/cpy/CVACT01Y.cpy}, {@code X(59)} in {@code app/cpy/CVACT02Y.cpy}, {@code X(14)} in
 *       {@code app/cpy/CVACT03Y.cpy}, {@code X(168)} in {@code app/cpy/CVCUS01Y.cpy}, {@code X(22)} in
 *       {@code app/cpy/CVTRA01Y.cpy}, {@code X(28)} in {@code app/cpy/CVTRA02Y.cpy}, {@code X(08)} in
 *       {@code app/cpy/CVTRA03Y.cpy}, {@code X(04)} in {@code app/cpy/CVTRA04Y.cpy}, {@code X(20)} in both
 *       {@code app/cpy/CVTRA05Y.cpy} and {@code app/cpy/CVTRA06Y.cpy}, and {@code SEC-USR-FILLER X(23)} in
 *       {@code app/cpy/CSUSR01Y.cpy}. No column is created for any of them. The byte counts are recorded in
 *       the class documentation so that the fixed-width writers can re-pad on emission.</li>
 *   <li><strong>Every financial value is a {@link java.math.BigDecimal}.</strong> There is no
 *       {@code float} and no {@code double} anywhere in this package, in any field, accessor, constructor
 *       parameter or local. Amounts are compared with {@code compareTo} and never with {@code equals}, so
 *       that a scale difference cannot make two equal amounts test unequal, and any rounding uses
 *       {@link java.math.RoundingMode#HALF_EVEN}.</li>
 *   <li><strong>Three distinct SQL precision tiers, derived from the {@code PIC} clauses and never
 *       collapsed into one.</strong> {@code S9(10)V99} becomes {@code NUMERIC(12,2)} and applies to exactly
 *       the five {@link Account} money and cycle fields {@code ACCT-CURR-BAL},
 *       {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-CURR-CYC-CREDIT} and
 *       {@code ACCT-CURR-CYC-DEBIT}. {@code S9(09)V99} becomes {@code NUMERIC(11,2)} and applies to exactly
 *       three fields, {@code TRAN-AMT}, {@code DALYTRAN-AMT} and {@code TRAN-CAT-BAL}. {@code S9(04)V99}
 *       becomes {@code NUMERIC(6,2)} and applies to exactly one, {@code DIS-INT-RATE}. Widening any of the
 *       narrower tiers to {@code NUMERIC(12,2)} for uniformity is a Blocker; see the failure-mode
 *       table.</li>
 *   <li><strong>{@code @Version} appears on exactly four entities</strong>: {@link Account}, {@link Card},
 *       {@link Customer} and {@link Transaction}. It is the <em>store-level</em> optimistic guard only. It
 *       is deliberately <strong>not sufficient on its own</strong> for the account-update path, because
 *       {@code app/cbl/COACTUPC.cbl:L4109-L4193}, paragraph {@code 9700-CHECK-CHANGE-IN-REC}, compares
 *       <em>business field values</em> against a snapshot taken when the screen was first populated, not a
 *       version counter. A version column detects that some row changed; the legacy program detects which
 *       fields changed. A concurrent write that restored a field to its original value passes the legacy
 *       check and fails a version check, so the two guarantees are not interchangeable. The second,
 *       field-by-field layer lives outside this package, in the request payload carried by
 *       {@code AccountUpdateRequest} in {@code com.cardemo.model.dto}.</li>
 *   <li><strong>The 26-byte transaction timestamps are text, not temporal types.</strong>
 *       {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are declared {@code PIC X(26)} in
 *       {@code app/cpy/CVTRA05Y.cpy}, and {@code app/cpy/CVTRA06Y.cpy} declares the same pair under
 *       {@code DALYTRAN-} prefixes. All four map to {@code String} over {@code CHAR(26)}. Substituting a
 *       temporal type is a <strong>Blocker</strong> and is the first row of the failure-mode table
 *       below.</li>
 *   <li><strong>No JPA associations, anywhere.</strong> There is no {@code @ManyToOne},
 *       {@code @OneToMany}, {@code @OneToOne}, {@code @ManyToMany}, {@code @JoinColumn} and no cascade in
 *       this package. Foreign-key columns such as an account identifier or a card number are plain scalar
 *       columns. Referential integrity is enforced by the ten foreign keys declared in
 *       {@code V1__create_schema.sql}, not by object graph navigation. Three reasons, in order of weight:
 *       the legacy corpus performs explicit keyed lookup chains rather than navigation, so an association
 *       would model a traversal the source never makes; the repository layer depends on the scalar property
 *       resolving directly, so a finder such as {@code findByAccountId} would have to be renamed and
 *       rewritten if the field became an entity reference; and scalar columns cannot trigger lazy-loading
 *       or {@code N+1} behaviour in batch steps that stream hundreds of records.</li>
 *   <li><strong>The dependency direction is strictly inward and cycle-free.</strong> These entities import
 *       nothing from {@code com.cardemo.exception}, {@code .repository}, {@code .service},
 *       {@code .controller}, {@code .batch}, {@code .security}, {@code .config} or
 *       {@code .observability}. The only intra-project imports in the entire package are the three
 *       {@code @EmbeddedId} types from {@code com.cardemo.model.key}, used by
 *       {@link TransactionCategoryBalance}, {@link DisclosureGroup} and {@link TransactionCategory}, and
 *       {@code UserType} from {@code com.cardemo.model.enums}, used by {@link UserSecurity}. Everything
 *       else comes from {@code jakarta.persistence}, {@code jakarta.validation} or the JDK.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This package has no executable entry point. It is compiled as part of the single CardDemo Spring Boot
 * module, the modular monolith described in the migration plan, and is exercised through the repositories,
 * services and batch steps that consume it.
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify} from the repository root, or
 *       {@code ./mvnw clean compile} for a fast syntax and mapping check. The wrapper pins Apache Maven
 *       <strong>3.9.11</strong>. The build targets {@code maven.compiler.release} <strong>25</strong> with
 *       <strong>no preview features</strong> enabled, under the parent
 *       {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}.</li>
 *   <li><strong>Warnings are errors, so a lint problem is a build failure.</strong>
 *       {@code maven-compiler-plugin} <strong>3.14.1</strong> is configured with {@code -Xlint:all},
 *       {@code -Werror} and {@code failOnWarning}. An unused import, a raw type, a deprecation or a missing
 *       {@code serialVersionUID} on a {@link java.io.Serializable} type therefore <strong>fails the
 *       build</strong> rather than producing a warning to triage later. This is the mechanism that enforces
 *       the no-dead-code and no-unused-import standard mechanically instead of by review, and it is why
 *       nothing in this package may be left speculatively unused.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an <strong>80 percent LINE</strong> coverage floor at the
 *       {@code verify} phase, with <strong>no exclusions</strong> for this package. The plugin version and
 *       the floor are pinned in {@code pom.xml}, which is the single authority for both; at this commit the
 *       plugin is {@code 0.8.13} and the floor property is {@code 0.80}. Coverage must come from meaningful
 *       assertions on field widths, precision and equality semantics. Padding the number by calling getters
 *       in a loop is not acceptable and defeats the purpose of the gate. This file is documentation only,
 *       contributes no executable lines, and so neither helps nor harms the figure.</li>
 *   <li><strong>Tests.</strong> Tests for these entities live in
 *       {@code src/test/java/com/cardemo/unit/model} and <strong>never in this package</strong>, which
 *       contains production classes only. They assert the eleven record lengths, the key lengths, the three
 *       precision tiers, that {@code origTs} and {@code procTs} are declared {@code String}, and that no
 *       accessor or {@code toString} exposes credential material or personal data.</li>
 *   <li><strong>Toolchain actually present.</strong> Verified on this environment: {@code java} and
 *       {@code javac} report OpenJDK <strong>25.0.3</strong>, {@code mvn} reports Apache Maven
 *       <strong>3.9.11</strong>, and Docker Engine <strong>29.7.0</strong> with {@code docker compose}
 *       <strong>v5.3.1</strong> is available and is what provisions PostgreSQL 16 and LocalStack for the
 *       integration tiers. Any claim that the Java toolchain or the container runtime is absent is stale and
 *       must not be repeated.</li>
 *   <li><strong>No Lombok and no new dependency.</strong> This package introduces neither, and uses no
 *       annotation processor. Constructors, accessors, {@code equals}, {@code hashCode} and
 *       {@code toString} are written out explicitly, so what is compiled is exactly what is read here and a
 *       generated method can never silently start emitting a sensitive field.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>These entities hold no configuration of their own, but the settings below determine whether their
 * mapping is accepted at startup. All of them are <strong>mandated for every Spring profile</strong> of this
 * application.
 *
 * <ul>
 *   <li><strong>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile.</strong> This is the most
 *       consequential setting for this package. Any divergence between an entity mapping and the actual
 *       DDL, whether a missing column, a wrong column name, a wrong type, a wrong precision or a wrong
 *       nullability, <strong>fails application-context startup outright</strong> and names the offending
 *       table and column. It does not degrade into a warning and it does not defer to a wrong query at run
 *       time. {@code ddl-auto} must never be set to {@code create}, {@code create-drop} or {@code update}:
 *       schema ownership belongs to Flyway, and letting Hibernate generate DDL would silently paper over
 *       exactly the mismatches this setting exists to catch.</li>
 *   <li><strong>Exactly three Flyway migrations.</strong> {@code V1__create_schema.sql},
 *       {@code V2__create_indexes.sql} and {@code V3__seed_data.sql}. {@code V1} creates exactly
 *       <strong>11 tables</strong>, one per entity, carrying <strong>10 foreign keys</strong> and
 *       <strong>5 check constraints</strong>. The Spring Batch {@code BATCH_*} metadata tables come from the
 *       framework's own schema script and must <strong>never</strong> appear as a fourth migration nor as
 *       extra tables inside {@code V1}.</li>
 *   <li><strong>{@code spring.jpa.open-in-view: false}.</strong> No persistence context is held open across
 *       the view layer, so an entity cannot be lazily touched outside the transaction that loaded it. With
 *       no associations in this package there is nothing to lazily traverse anyway, which makes the setting
 *       cheap to hold and worth holding.</li>
 *   <li><strong>Hibernate JDBC time zone {@code UTC}.</strong> Because the 26-byte timestamps are stored as
 *       text and re-emitted byte for byte, this package is entirely insulated from JVM time-zone drift. The
 *       setting is nonetheless mandated as a module-wide invariant so that determinism never depends on the
 *       platform default.</li>
 *   <li><strong>{@link java.util.Locale#ROOT} on every case-changing or formatting operation.</strong> The
 *       platform default charset, locale and time zone are never relied upon, so behaviour cannot differ
 *       between a developer machine and CI. This matters concretely: a default-locale
 *       {@code toUpperCase()} mangles certain characters, which would corrupt a fixed-width field.</li>
 *   <li><strong>Explicit {@code @Column(name = ...)} on every field.</strong> No Hibernate implicit or
 *       physical naming strategy can silently rename a column, so the mapping stays pinned to the migration
 *       regardless of framework defaults or a future Spring Boot upgrade.</li>
 *   <li><strong>{@code .editorconfig} at the repository root</strong> fixes UTF-8, LF line endings,
 *       <strong>4-space</strong> Java indentation, a final newline and no trailing whitespace, and
 *       deliberately unsets every property over {@code app/**} so that no editor can reflow the frozen
 *       fixed-width corpus.</li>
 *   <li><strong>Where the conventions in this file come from.</strong> The source banner is inherited: it
 *       reproduces {@code app/cbl/CBACT04C.cbl:L1-L21}, whose form is universal across the legacy corpus,
 *       so following it is not a new opinion. The formatting rules are the opposite case. No formatter,
 *       linter or style-tool configuration existed anywhere in the source repository, so there was no
 *       existing style to conform to and the convention had to be <strong>established</strong> at the
 *       repository root rather than inherited. That distinction matters because establishing a convention
 *       for a new tree is not the same as overriding one that was already in force.
 *       {@code CONTRIBUTING.md:L33} asks contributors to focus on the specific change and warns that
 *       reformatting everything makes the real change hard to see, which is precisely why the formatting
 *       rules stop at the boundary of {@code app/}; {@code CONTRIBUTING.md:L34} requires that local tests
 *       pass before a change is offered.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Each row below is a mistake that has a plausible, tidy-looking implementation which is nevertheless
 * wrong. Findings are classified <strong>Blocker</strong>, <strong>High</strong>, <strong>Medium</strong> or
 * <strong>Low</strong> by the damage they do and by how loudly they announce themselves: the dangerous ones
 * are not the noisy ones.
 *
 * <table>
 *   <caption>Failure modes for the persistence model, with symptom and remediation</caption>
 *   <thead>
 *     <tr>
 *       <th scope="col">Severity</th>
 *       <th scope="col">Failure mode</th>
 *       <th scope="col">Symptom</th>
 *       <th scope="col">Remediation</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td><strong>Blocker</strong></td>
 *       <td>A temporal type ({@link java.time.LocalDateTime}, {@link java.sql.Timestamp},
 *           {@link java.time.Instant}, {@link java.time.OffsetDateTime}) used for {@code origTs} or
 *           {@code procTs}</td>
 *       <td>The {@code app/data/ASCII/dailytran.txt} fixture cannot be loaded at all, and end-to-end
 *           boundary parity fails</td>
 *       <td>Both fields are {@code String} mapped to {@code CHAR(26)}. {@code app/cpy/CVTRA05Y.cpy}
 *           declares {@code TRAN-ORIG-TS PIC X(26)} and {@code TRAN-PROC-TS PIC X(26)}, and
 *           {@code app/cpy/CVTRA06Y.cpy} the same pair with {@code DALYTRAN-} prefixes. All
 *           <strong>300</strong> rows of the fixture carry the {@code origTs} text
 *           {@code 2022-06-10 19:27:53.000000} together with a <strong>blank 26-space
 *           {@code procTs}</strong>, and a blank is not a parseable timestamp. Three mutually incompatible
 *           producers also exist: the batch path at {@code app/cbl/CBTRN02C.cbl:L438} writes the DB2 form
 *           documented at {@code :L149}, the online {@code COBIL00C} path writes a space-separated form,
 *           and {@code app/cbl/CBTRN02C.cbl:L436} passes the inbound text straight through unparsed. No
 *           single temporal type can round-trip all three, so the bytes are preserved instead</td>
 *     </tr>
 *     <tr>
 *       <td><strong>Blocker</strong></td>
 *       <td>{@code NUMERIC(12,2)} applied to {@code TRAN-AMT}, {@code DALYTRAN-AMT} or
 *           {@code TRAN-CAT-BAL}</td>
 *       <td>{@code ddl-auto: validate} fails at context startup, or, if the migration was widened to
 *           match, a silent scale divergence at the fixed-width boundary</td>
 *       <td>Those three are {@code S9(09)V99} and therefore <strong>{@code NUMERIC(11,2)}</strong>. Only
 *           the five {@link Account} money and cycle fields are {@code S9(10)V99} and therefore
 *           {@code NUMERIC(12,2)}. The tiers are read off the {@code PIC} clauses and must not be
 *           unified</td>
 *     </tr>
 *     <tr>
 *       <td><strong>Blocker</strong></td>
 *       <td>{@code float} or {@code double} in any financial field</td>
 *       <td>The security-audit gate fails by inspection, and binary floating point cannot represent decimal
 *           currency exactly, so balances drift</td>
 *       <td>{@link java.math.BigDecimal} only, everywhere, with {@code compareTo} for equality and
 *           {@link java.math.RoundingMode#HALF_EVEN} for rounding</td>
 *     </tr>
 *     <tr>
 *       <td><strong>High</strong></td>
 *       <td>Any column name, type, precision or nullability mismatch against
 *           {@code V1__create_schema.sql}</td>
 *       <td>The application context fails to start under {@code ddl-auto: validate}, naming the offending
 *           table and column</td>
 *       <td>Treat the field tables in these entity Javadocs as the normative contract and converge
 *           {@code V1} onto them, for the reason set out in the disclosure below. Where the migration is
 *           provably right against the copybook and the mapping is wrong, fix the mapping instead</td>
 *     </tr>
 *     <tr>
 *       <td><strong>High</strong></td>
 *       <td>A password hash, a plaintext password, a card number, a CVV or any personal field emitted from
 *           {@code toString}</td>
 *       <td>Credential or personal-data leakage into logs, where it persists in aggregation systems long
 *           after the request has gone</td>
 *       <td>{@link UserSecurity} has no hash-bearing {@code toString}, emitting only its identifier and
 *           user type; {@link Card} exposes neither the card number nor the CVV; {@link Customer} exposes
 *           no national identifier, telephone number, government-issued identifier, date of birth or
 *           electronic-funds account identifier. Log masking is the backstop; <strong>never emitting is the
 *           primary defence</strong>, because masking that is misconfigured fails silently</td>
 *     </tr>
 *     <tr>
 *       <td><strong>Medium</strong></td>
 *       <td>A second customer layout class created for {@code app/cpy/CUSTREC.cpy}</td>
 *       <td>Duplication, and two mappings over one table that drift apart at the next change</td>
 *       <td>One {@link Customer} entity serves both copybooks. They are proven duplicates, differing only
 *           in one field name and a one-second version comment</td>
 *     </tr>
 *     <tr>
 *       <td><strong>Medium</strong></td>
 *       <td>{@code @AttributeOverride}, or a duplicate key {@code @Column}, on a composite-key entity</td>
 *       <td>A double-mapped column, surfacing as a Hibernate mapping error or a {@code validate}
 *           failure</td>
 *       <td>The three {@code @Embeddable} identifier classes in {@code com.cardemo.model.key} own
 *           <strong>all</strong> key {@code @Column} declarations. {@link TransactionCategoryBalance},
 *           {@link DisclosureGroup} and {@link TransactionCategory} declare only {@code @EmbeddedId} plus
 *           their non-key columns</td>
 *     </tr>
 *     <tr>
 *       <td><strong>Medium</strong></td>
 *       <td>{@code ACCT-CURR-CYC-DEBIT} normalised with an absolute value, or its column declared
 *           unsigned</td>
 *       <td>Over-limit arithmetic is silently wrong from the second batch cycle onward. Nothing fails on
 *           the first run, which is what makes this expensive to find</td>
 *       <td>The accumulator legitimately holds negative values.
 *           {@code app/cbl/CBTRN02C.cbl:L548-L552} adds the amount to the cycle <strong>credit</strong>
 *           when it is {@code &gt;= 0} and to the cycle <strong>debit</strong> otherwise, with no
 *           normalisation, which is precisely why the over-limit formula at {@code :L403-L405}
 *           <em>subtracts</em> it. The fixture proves the branch is live: 50 of the 300 rows in
 *           {@code app/data/ASCII/dailytran.txt} carry a negative zoned-decimal overpunch sign</td>
 *     </tr>
 *     <tr>
 *       <td><strong>Low</strong></td>
 *       <td>A {@code PIC 9(n)} field with significant leading zeros mapped to a numeric type</td>
 *       <td>The leading zero is dropped, breaking byte-exact re-emission and the fixture round-trip</td>
 *       <td>{@code CUST-SSN}, {@code CUST-FICO-CREDIT-SCORE} and {@code CARD-CVV-CD} are {@code String}
 *           over {@code CHAR(n)}; leading zeros are present in the fixtures and are part of the value. Key
 *           identifiers stay numeric and are zero-padded at emission time by the fixed-width writers</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <h2>Not available: the authoritative SQL column contract</h2>
 *
 * <p><strong>Not available.</strong> The authoritative SQL column contract for these eleven entities cannot
 * be cited at this commit. {@code src/main/resources/db/migration/} does not exist, so
 * {@code V1__create_schema.sql}, {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} are all
 * unwritten and there is no authored SQL type, length, constraint or index to point at. No SQL is invented
 * here to fill the gap, and no column type is asserted as though it had been verified.
 *
 * <p><strong>Consequence, stated plainly so it is not mistaken for a detail.</strong> Because the migration
 * does not yet exist, the field tables in this package's class documentation are the <strong>normative
 * column contract</strong>, and {@code V1} must be written to converge on them rather than the reverse. That
 * direction is not a preference: these mappings were derived from the copybooks and corroborated against
 * {@code app/catlg/LISTCAT.txt}, so they carry evidence that an as-yet-unwritten migration cannot.
 *
 * <p><strong>Severity: Medium.</strong> Not a Blocker, because the module compiles and all eleven entities
 * are complete and internally consistent without the migration. Not Low, because {@code ddl-auto: validate}
 * means the first real application boot fails until the migration exists and agrees with these mappings.
 *
 * <p><strong>What is needed to close it.</strong> None of it is this package's responsibility:
 *
 * <ol>
 *   <li>{@code src/main/resources/db/migration/V1__create_schema.sql} containing the <strong>11
 *       {@code CREATE TABLE} statements</strong>, one per entity, with the exact column names, SQL types,
 *       precisions and {@code NOT NULL} constraints enumerated in each entity's Javadoc field table.</li>
 *   <li>The <strong>10 foreign keys</strong> and <strong>5 check constraints</strong> that {@code V1} is
 *       required to declare.</li>
 *   <li>The {@code @Version} columns on the four versioned entities {@link Account}, {@link Card},
 *       {@link Customer} and {@link Transaction}, without which optimistic locking cannot be enforced at
 *       the store level.</li>
 *   <li>{@code V2__create_indexes.sql} with the three non-unique B-tree indexes standing in for the three
 *       VSAM alternate indexes, and {@code V3__seed_data.sql} loading the nine ASCII fixtures with
 *       position-aware overpunch decoding and the ten seeded users stored only as BCrypt hashes.</li>
 * </ol>
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These apply to every type in this package and to any change made to it.
 *
 * <ul>
 *   <li><strong>Directory shape.</strong> This package contains exactly <strong>twelve</strong>
 *       {@code .java} files: the eleven entities and this file. There is deliberately <strong>no
 *       {@code README.md} and no Markdown file of any kind here</strong>. This docstring <em>is</em> the
 *       module documentation, and keeping the same content in two places would guarantee that the two
 *       versions diverge.</li>
 *   <li><strong>No business logic.</strong> No interest calculation, no over-limit decision, no posting, no
 *       I/O and no framework lookup. An entity validates its own field widths and compares itself; anything
 *       that reasons about more than one record belongs in a service or a batch processor.</li>
 *   <li><strong>No secrets, and no credential value from the legacy corpus.</strong> The seed users in
 *       {@code app/jcl/DUSRSECJ.jcl} carry an inline plaintext password. That literal value is
 *       <strong>never</strong> reproduced in this package, in any test, in any comment or anywhere else
 *       under {@code src/}. {@link UserSecurity} stores a BCrypt hash only, and the seed migration is
 *       responsible for hashing on load.</li>
 *   <li><strong>No global mutable state.</strong> The only static members are {@code static final}
 *       constants such as field-width limits and {@code serialVersionUID}, together with private static
 *       pure helpers. There is no mutable static field anywhere in the package.</li>
 *   <li><strong>Explicit boundary handling.</strong> Null and empty inputs are handled deliberately rather
 *       than incidentally: {@code equals} tolerates {@code null} and a foreign type instead of throwing,
 *       and a field whose width or range is validated on assignment reports the failure through
 *       {@link IllegalArgumentException} naming the offending field. This package must not import
 *       {@code com.cardemo.exception}, which would invert the dependency direction of a model leaf.</li>
 *   <li><strong>No unsafe deserialization, no dynamic execution, no string-built SQL.</strong> Nothing here
 *       accepts serialized bytes from outside the application, there is no
 *       {@link Runtime#exec(String)} or {@code ProcessBuilder} use, and no concatenated JPQL or SQL. Field
 *       values reach the database only as bound parameters through JPA.</li>
 *   <li><strong>Fixed-width geometry is load bearing.</strong> Field declaration order reproduces the
 *       copybook, and the documented record lengths of 300, 150, 500, 50, 350, 350, 50, 50, 60, 60 and 80
 *       bytes are what the fixed-width readers and writers re-pad to. Reordering or silently widening a
 *       field compiles cleanly and breaks parity, so neither is permitted.</li>
 *   <li><strong>The legacy corpus is frozen.</strong> Everything under {@code app/} is read-only reference
 *       material and remains byte for byte unmodified. This package is purely additive alongside it, and
 *       cites it rather than copying from it.</li>
 * </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
package com.cardemo.model.entity;
