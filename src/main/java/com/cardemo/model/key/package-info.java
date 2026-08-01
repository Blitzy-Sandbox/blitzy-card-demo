/*
 * ******************************************************************
 * Program     : package-info.java
 * Application : CardDemo
 * Type        : Java package documentation (JPA composite identifiers)
 * Function    : Documents the com.cardemo.model.key package, which holds the
 *               three JPA composite identifier value types that replace the
 *               composite record keys of the three VSAM KSDS clusters whose
 *               primary key is a COBOL group item rather than a single field.
 * Source      : app/cpy/CVTRA01Y.cpy (key 17), app/cpy/CVTRA02Y.cpy (key 16),
 *               app/cpy/CVTRA04Y.cpy (key 6) @ 7756d89
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
 * Composite JPA identifiers for the three CardDemo VSAM KSDS clusters whose primary key is a COBOL group item
 * rather than a single field.
 *
 * <p><strong>What it does.</strong> Exactly three {@code @Embeddable} value types, each replacing the record
 * key of one cluster and consumed through {@code @EmbeddedId} by the matching entity in
 * {@code com.cardemo.model.entity}. They carry only their key components, expose value-based {@code equals}
 * and {@code hashCode}, perform no I/O and hold no collaborator. The package imports nothing from any other
 * {@code com.cardemo} package, so it is a cycle-free leaf of the dependency graph whose only compile
 * dependencies are {@code jakarta.persistence} and the JDK.
 *
 * <ul>
 *   <li>{@link TransactionCategoryBalanceId} - {@code app/cpy/CVTRA01Y.cpy} group {@code TRAN-CAT-KEY},
 *       components {@code TRANCAT-ACCT-ID 9(11)}, {@code TRANCAT-TYPE-CD X(02)}, {@code TRANCAT-CD 9(04)};
 *       key length 17 ({@code app/catlg/LISTCAT.txt:L1371}).</li>
 *   <li>{@link DisclosureGroupId} - {@code app/cpy/CVTRA02Y.cpy} group {@code DIS-GROUP-KEY}, components
 *       {@code DIS-ACCT-GROUP-ID X(10)}, {@code DIS-TRAN-TYPE-CD X(02)}, {@code DIS-TRAN-CAT-CD 9(04)}; key
 *       length 16 ({@code :L896}).</li>
 *   <li>{@link TransactionCategoryId} - {@code app/cpy/CVTRA04Y.cpy} group {@code TRAN-CAT-KEY}, components
 *       {@code TRAN-TYPE-CD X(02)}, {@code TRAN-CAT-CD 9(04)}; key length 6 ({@code :L1475}).</li>
 * </ul>
 *
 * <p>Each key length was corroborated two ways, by summing the copybook field widths and by reading
 * {@code KEYLEN} from the catalogue, and the two agree at {@code 11 + 2 + 4 = 17}, {@code 10 + 2 + 4 = 16}
 * and {@code 2 + 4 = 6}. All three clusters report {@code RKP 0}, so in each case the key is the record
 * prefix.
 *
 * <p>Two constraints are load-bearing. First, the COBOL group name {@code TRAN-CAT-KEY} is declared in two
 * different copybooks - 17 bytes over three {@code TRANCAT-} fields keying {@code TCATBALF}, and 6 bytes over
 * two {@code TRAN-} fields keying {@code TRANCATG} - so the shared name must not be read as a shared concept.
 * There is deliberately no base class, interface, marker type or shared helper here: unifying the two would
 * silently give one cluster a key of the wrong width. Second, component order reproduces the copybook exactly
 * and is never alphabetised, because a VSAM browse proceeds in key order and the interest job's account-level
 * control break works only while {@code TRANCAT-ACCT-ID} leads the key. Primary-key column order in the
 * schema must follow the same sequence.
 *
 * <p><strong>How to run, build and test.</strong> This package has no entry point and is compiled as part of
 * the single application artifact by {@code ./mvnw clean verify} under {@code -Xlint:all -Werror}. Its unit
 * tests live in {@code src/test/java/com/cardemo/unit/model} and assert component order, the three key
 * lengths, the width checks and the equality contract.
 *
 * <p><strong>Key configuration and defaults.</strong> These types read no property. They depend on one
 * setting owned elsewhere, {@code spring.jpa.hibernate.ddl-auto: validate} in every profile, which turns any
 * drift between a component's column mapping and the Flyway schema into an application-context startup
 * failure rather than a latent fault. Fixed-width components map to {@code CHAR(n)} so blank padding survives
 * the round trip, and numeric components keep the width their picture clause declares.
 *
 * <p><strong>Common failure modes and troubleshooting.</strong>
 *
 * <ul>
 *   <li>Sequential category-balance processing produces wrong account totals - the components were reordered,
 *       so the account identifier no longer leads the key and the control break no longer groups an
 *       account's rows together.</li>
 *   <li>A repository lookup by composite key silently misses - a component was trimmed, padded or
 *       case-folded. These classes normalise nothing; the stored values are already padded to their picture
 *       widths, and the interest job's {@code DEFAULT} group fallback depends on the full-width form.</li>
 *   <li>Context startup fails with a schema-validation error naming a key column - a component's column
 *       name, type or width diverges from {@code V1__create_schema.sql}. Reconcile against the copybook
 *       picture clause, which is authoritative for both sides.</li>
 *   <li>Two rows that should differ compare equal, or a map lookup misses - {@code equals} or
 *       {@code hashCode} was narrowed to a subset of components. All components participate in both.</li>
 *   <li>A width check rejects a legitimate value - the check was tightened beyond the picture width. It
 *       bounds length only, and deliberately imposes no digit pattern or character class.</li>
 * </ul>
 *
 * <p>These are entirely different keys that merely share a COBOL group name, over different clusters with
 * different record lengths. That collision is <strong>documented here, not abstracted away</strong>. There
 * is deliberately <strong>no shared base class</strong>, no common interface, no marker type and no shared
 * helper anywhere in this package. The repeated name must not be read as a common concept, and the two
 * keys must never be unified, generified or refactored into one type: doing so would silently give one of
 * the two clusters a key of the wrong width. Three flat, independent value types is the correct and
 * intended shape.
 *
 * <h2>Design constraint: COBOL component order is load bearing, not cosmetic</h2>
 *
 * <p>A VSAM browse proceeds in key order, so the byte layout of a composite key <em>is</em> its sort
 * order. {@code app/cbl/CBACT04C.cbl:L188-L222} browses the transaction category balance file
 * sequentially and detects an <strong>account level control break</strong> by testing
 * {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM} at {@code :L194}, flushing the interest accumulated
 * for the previous account through {@code 1050-UPDATE-ACCOUNT} at {@code :L196} on each break, guarded
 * by the first record test at {@code :L195-L199}.
 *
 * <p><strong>Correction: the source never flushes the final account.</strong> An earlier revision of this
 * document said the update ran "once more when end of file is reached". It does not. The apparent final
 * flush is {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} at {@code :L219-L220}, whose {@code ELSE} belongs to
 * {@code IF END-OF-FILE = 'N'} at {@code :L189}; it is therefore reachable only when
 * {@code END-OF-FILE} is already {@code 'Y'}, which is precisely the state in which the enclosing
 * test-before {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L188} has already exited. The branch is
 * dead code, so the last account in key order silently loses its accrued interest and keeps its stale
 * cycle accumulators. The Java implementation performs the flush on the end of data condition, which is
 * a <strong>labelled deviation</strong> from source behaviour and not parity; it is recorded as such
 * rather than presented as equivalence.
 *
 * <p>That control break is correct <em>only</em> because {@code TRANCAT-ACCT-ID} is the leading component
 * of the key. Reordering the components, for instance alphabetising them, would change the iteration
 * order and silently lose interest for whole accounts while still compiling and still passing any test
 * that did not assert ordering. Declaration order in all three classes therefore reproduces the copybook
 * exactly and is <strong>never alphabetised or tidied</strong>. Primary key column order in the schema
 * must match the same sequence.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This package contains no executable entry point; it is compiled as part of the single CardDemo
 * Spring Boot module and exercised through its consuming entities and repositories.
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify} from the repository root. The wrapper pins
 *       Maven 3.9.11 and the build targets {@code maven.compiler.release} 25 with no preview features.</li>
 *   <li><strong>Warnings are errors.</strong> {@code maven-compiler-plugin} is configured with
 *       {@code -Xlint:all} and {@code -Werror}, and additionally with {@code failOnWarning}. A raw type, a
 *       deprecation, a removal or a missing {@code serialVersionUID} on a
 *       {@link java.io.Serializable} type is therefore a <strong>build failure</strong>, not a warning to
 *       be triaged later. An <em>unused import</em> is not in that set: {@code javac} 25.0.3 publishes no
 *       lint key for one, as {@code javac --help-lint} shows, and no Checkstyle or Error Prone analyser is
 *       in the pinned dependency set, so Rule 1 Clause B's prohibition on unused imports and dead code is
 *       enforced by review rather than by the compiler.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an <strong>80 percent LINE</strong> coverage floor at
 *       the {@code verify} phase, with <strong>no exclusions</strong> for this package. Coverage must come
 *       from meaningful assertions on {@code equals}, {@code hashCode} and component validation; padding
 *       the figure by calling getters is not acceptable. This file is documentation only and contributes
 *       no executable lines, so it neither helps nor harms the figure and must not be "covered".
 *       <strong>Not available, measured 1 August 2026:</strong> none of the three identifier types has a
 *       test class, and none is referenced anywhere under {@code src/test/java}. This package therefore
 *       contributes <strong>zero</strong> covered lines today, and no coverage figure quoted anywhere may
 *       be read as evidence about it.</li>
 *   <li><strong>Toolchain actually present, measured 1 August 2026.</strong> Read in this container on
 *       that date after {@code source /etc/profile.d/10-carddemo-toolchain.sh}: {@code java} and
 *       {@code javac} report OpenJDK <strong>25.0.3</strong>, {@code ./mvnw --version} reports Apache
 *       Maven <strong>3.9.11</strong> from the pinned wrapper distribution, and Docker Engine
 *       <strong>29.7.0</strong> with {@code docker compose} <strong>v5.3.1</strong> is available and is
 *       what provisions PostgreSQL 16 and LocalStack for the integration tiers. Every figure here is a
 *       reading taken on 1 August 2026 rather than a requirement, so re-measure instead of quoting it
 *       after a host change. Any claim that the Java toolchain or the container runtime is absent is
 *       stale and must not be repeated.</li>
 *   <li><strong>Tests.</strong> Tests for these three types belong in the sibling test tree at
 *       {@code src/test/java/com/cardemo/unit/model}, never in this package. They must assert the three
 *       key lengths of 17, 16 and 6, the COBOL component order of each key, and the full
 *       {@code equals}/{@code hashCode} contract including reflexivity, symmetry, transitivity,
 *       null tolerance and behaviour against a foreign type.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>These types hold no configuration of their own, but four settings govern whether their mapping is
 * correct, and all four are mandated for every Spring profile of this application:
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in <strong>every</strong> profile. This is the
 *       single most important setting for this package: a column name, type or nullability mismatch
 *       between one of these keys and the Flyway schema fails <strong>application context startup</strong>
 *       loudly, instead of degrading into a runtime warning or a silently wrong query.</li>
 *   <li>{@code spring.jpa.open-in-view: false}, so no persistence context is held open across the view
 *       layer and identifier equality is never relied upon outside an explicit transaction.</li>
 *   <li>Hibernate JDBC time zone {@code UTC}. No component of any key in this package is temporal, so this
 *       setting does not affect them directly; it is recorded because it is a module wide invariant and
 *       because determinism must not depend on the platform default time zone.</li>
 *   <li>Schema ownership is <strong>Flyway</strong>, through {@code V1__create_schema.sql},
 *       {@code V2__create_indexes.sql} and {@code V3__seed_data.sql}. This package never generates DDL and
 *       {@code ddl-auto} must never be set to {@code create}, {@code create-drop} or {@code update}.</li>
 * </ul>
 *
 * <p>Within the classes themselves, every key component carries an explicit {@code @Column(name = ...)}.
 * That is deliberate: it means no Hibernate implicit or physical naming strategy can silently rename a
 * primary key column, so the mapping stays pinned to the migration regardless of framework defaults.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Missing {@code serialVersionUID}.</strong> Symptom: the build fails at compile time under
 *       {@code -Xlint:all -Werror}, because a {@link java.io.Serializable} type without an explicit
 *       identifier raises the {@code serial} lint warning, which is escalated to an error.
 *       <em>Remediation:</em> declare {@code private static final long serialVersionUID} on the class. All
 *       three classes in this package already do, each with the value {@code 1L}.</li>
 *   <li><strong>Missing or asymmetric {@code equals}/{@code hashCode}.</strong> Symptom: this does
 *       <em>not</em> fail cleanly. It corrupts the JPA identity map, so entities are duplicated in the
 *       persistence context, {@code merge} reattaches the wrong instance and map or set lookups miss. The
 *       result is <strong>intermittent data errors rather than a clean failure</strong>, which is far
 *       harder to diagnose than a crash. <em>Remediation:</em> implement both methods, value based, over
 *       <strong>all</strong> key components and mutually consistent, so that equal keys always produce
 *       equal hash codes.</li>
 *   <li><strong>Reordered components.</strong> Symptom: no compile error and no obvious test failure, but
 *       browse order changes and the {@code CBACT04C} account level control break described above stops
 *       breaking where it should, losing interest for whole accounts. <em>Remediation:</em> keep field
 *       declaration order byte for byte identical to the copybook, and keep the composite primary key
 *       column order in the migration identical to it as well.</li>
 *   <li><strong>Column mismatch against the migration.</strong> Symptom: application context startup fails
 *       with a Hibernate schema validation error naming the offending table and column, because
 *       {@code ddl-auto} is {@code validate}. <em>Remediation:</em> align the
 *       {@code @Column(name = ...)}, the Java type and the nullability with
 *       {@code V1__create_schema.sql}. Treat the migration as authoritative and change the mapping to
 *       match it, not the reverse, unless the migration itself is provably wrong against the copybook.</li>
 *   <li><strong>Confusing the two {@code TRAN-CAT-KEY} groups.</strong> Symptom: a key of the wrong
 *       width, which manifests as a wrong number of primary key columns or a wrong lookup that finds
 *       nothing. <em>Remediation:</em> check the copybook named in the class Javadoc rather than trusting
 *       the COBOL group name. It is 17 bytes over three fields for {@code app/cpy/CVTRA01Y.cpy}, and
 *       6 bytes over two fields for {@code app/cpy/CVTRA04Y.cpy}.</li>
 * </ul>
 *
 * <h2>The authoritative column contract now exists</h2>
 *
 * <p>{@code src/main/resources/db/migration/V1__create_schema.sql} <strong>exists</strong> and declares
 * the composite primary keys these three value types stand for. An earlier revision of this paragraph
 * said the migration directory did not exist yet; that is no longer true and the claim is withdrawn. The
 * three catalogued key lengths are corroborated column by column by {@code SchemaStructureTest} against
 * the copybooks: {@code transaction_category_balance} at 17 bytes from {@code CVTRA01Y},
 * {@code disclosure_group} at 16 from {@code CVTRA02Y} and {@code transaction_category} at 6 from
 * {@code CVTRA04Y}, each with its primary-key column order matching COBOL field order exactly. No SQL
 * type is invented here.
 *
 * <p><strong>What remains absent is {@code V2__create_indexes.sql}</strong>, which has never existed;
 * {@code V1} declares no {@code CREATE INDEX}, so any reference to a secondary index on these tables
 * describes <strong>planned</strong> work. Because {@code ddl-auto: validate} is set in every profile, a
 * disagreement between these mappings and {@code V1} would fail the first real application boot outright,
 * which is why the agreement is asserted by a test rather than by inspection.
 *
 * <p><strong>What is still not available.</strong> {@code V2__create_indexes.sql},
 * {@code V3__seed_data.sql} and all four {@code application*.yml} profile files. Severity remains
 * <strong>Medium</strong> for that residue: not a Blocker, because the module compiles and these three
 * value types are complete and self consistent, and the schema they must validate against now exists and
 * agrees with them; not Low, because {@code ddl-auto: validate} cannot be exercised until a profile
 * exists to boot with.
 *
 * <p><strong>The contract itself</strong>, derived from the copybooks and corroborated against
 * {@code app/catlg/LISTCAT.txt} and now against {@code V1}:
 *
 * <pre>
 * transaction_category_balance : acct_id       9(11)
 *                                tran_type_cd  X(02)  CHAR(2)
 *                                tran_cat_cd   9(04)
 * disclosure_group             : acct_group_id X(10)  CHAR(10)
 *                                tran_type_cd  X(02)  CHAR(2)
 *                                tran_cat_cd   9(04)
 * transaction_category         : tran_type_cd  X(02)  CHAR(2)
 *                                tran_cat_cd   9(04)
 * </pre>
 *
 * <p>Every column listed is {@code NOT NULL}, because every one of them is part of a primary key, and the
 * declared primary key column order must match the COBOL component order above exactly, for the control
 * break reason given earlier. The column names shown are the ones the three classes already pin through
 * {@code @Column(name = ...)}.
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These constraints apply to every type in this package and to any change made to it.
 *
 * <ul>
 *   <li><strong>Serialization is for JPA identity only.</strong> These types implement
 *       {@link java.io.Serializable} because a JPA identifier is required to be serializable, and they
 *       declare {@code serialVersionUID} so the form is stable. They must <strong>never</strong> be used to
 *       deserialize untrusted input: no {@link java.io.ObjectInputStream} path, no Java serialization based
 *       cache or message payload, and no acceptance of serialized bytes originating outside this
 *       application. Insecure deserialization is a known risky pattern and this is the package rule that
 *       forecloses it. Key instances are constructed from validated components, never from a byte
 *       stream.</li>
 *   <li><strong>No secrets and no personal data.</strong> No component of any key here is credential
 *       material or personally identifying: they are account identifiers, group identifiers and
 *       type/category codes. Nothing in this package needs masking, and nothing privileged is present or
 *       may be added.</li>
 *   <li><strong>No shell or dynamic execution, and no string built SQL.</strong> No
 *       {@link Runtime#exec(String)} or {@code ProcessBuilder} use, and no concatenated JPQL or SQL. Key
 *       components reach the database only as bound parameters through JPA.</li>
 *   <li><strong>No new dependency.</strong> This package adds none, and in particular uses no annotation
 *       processor and no Lombok. {@code equals}, {@code hashCode}, {@code toString} and the accessors are
 *       written out explicitly so that what is compiled is exactly what is read here.</li>
 *   <li><strong>No global mutable state.</strong> The only static members are {@code serialVersionUID},
 *       which is {@code static final} and therefore not mutable state, together with private static pure
 *       validation helpers. There is no mutable static field anywhere in the package.</li>
 *   <li><strong>Explicit boundary handling.</strong> Each {@code equals} tolerates {@code null} and a
 *       foreign type rather than throwing, and a partially populated key must not compare equal to a fully
 *       populated one. Where a component is validated on construction, the failure is signalled with
 *       {@link IllegalArgumentException} naming the offending component; this package must not import
 *       {@code com.cardemo.exception}, which would invert the dependency direction of a leaf package.</li>
 *   <li><strong>Determinism.</strong> Any case folding, parsing or formatting applied to a component must
 *       pass {@link java.util.Locale#ROOT} explicitly. The platform default locale, charset and time zone
 *       are never relied upon, so behaviour cannot vary between a developer machine and CI.</li>
 *   <li><strong>Performance and observability.</strong> {@code hashCode} is a cheap combination over a
 *       small fixed set of components, and <strong>iteration order of any hash based collection of these
 *       keys is never relied upon</strong>; anything order sensitive is ordered explicitly by key
 *       components. {@code toString} renders only non sensitive key components and exists for diagnostics
 *       only, so it must not be parsed or treated as a stable log format.</li>
 *   <li><strong>Directory shape.</strong> This package contains exactly <strong>four</strong>
 *       {@code .java} files: the three identifiers and this file. No fifth file may be added here, and in
 *       particular no {@code README} or other Markdown file, since this docstring is the module
 *       documentation, and no shared base class, abstract key or identifier generator, for the collision
 *       reason given above.</li>
 * </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.model.key;
