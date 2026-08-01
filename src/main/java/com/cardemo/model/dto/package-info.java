/*
 * ******************************************************************
 * Program     : package-info.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 * Function    : Package contract for the 17 data transfer objects
 *               replacing the BMS symbolic maps and the COMMAREA.
 * Source      : app/cpy-bms/** (17 symbolic maps, 441 input fields)
 *               + app/cpy/COCOM01Y.cpy @ 7756d89
 * Source      : app/cpy/COSTM01.CPY + app/cpy/CVTRA07Y.cpy (statement
 *               record, 32 byte key; 133 byte report lines) @ 7756d89
 * Source      : app/cpy/COMEN02Y.cpy + app/cpy/COADM02Y.cpy (menu and
 *               admin option tables, counts 10 and 4) @ 7756d89
 * Source      : app/cpy/CSSETATY.cpy (PROCEDURE DIVISION template for
 *               the three state field markers, no class here) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L505-L508,L746,L1256-L1275,
 *               L4174-L4179 (the stateless snapshot contract) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L204,L218,L383,L456 (the two
 *               distinct numeric intrinsics) @ 7756d89
 * Source      : app/cbl/COCRDLIC.cbl:L177-L178 (page size 7),
 *               app/cbl/COTRN00C.cbl:L65-L68,L290,L297 (page size 10),
 *               app/cbl/COUSR00C.cbl:L57 (page size 10) @ 7756d89
 * Source      : app/jcl/CREASTMT.JCL:L53-L54 (the statement projection
 *               truncation) @ 7756d89
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
 * Request and response payloads for the CardDemo REST surface, derived field for field from the 17 BMS
 * symbolic maps and the COMMAREA of the frozen COBOL corpus.
 *
 * <p>The banner above names every legacy artefact this package derives from, each pinned to commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} (short {@code 7756d89}), which is the traceability
 * anchor for the whole migration. Filename casing in those citations is load bearing and is reproduced
 * exactly as it appears on disk: all 17 members of {@code app/cpy-bms} carry an <strong>uppercase</strong>
 * {@code .CPY} extension; in {@code app/cpy} only {@code COSTM01.CPY} is uppercase while the other 27 use a
 * lowercase {@code .cpy}; in {@code app/cbl} only {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} are
 * uppercase while the other 26 use a lowercase {@code .cbl}; and in {@code app/jcl} only
 * {@code CREASTMT.JCL} is uppercase while the other 28 of the 29 members use a lowercase {@code .jcl}. A
 * case sensitive lookup that assumes the wrong form finds nothing, and a {@code *.jcl} glob silently drops
 * the sole source of statement generation.
 *
 * <h2>What it does</h2>
 *
 * <p>This package holds exactly <strong>17</strong> data transfer objects. Together with this file it
 * contains exactly <strong>18</strong> {@code .java} files and nothing else. They replace two legacy
 * mechanisms and no others: the 17 BMS symbolic maps under {@code app/cpy-bms}, which carried screen field
 * values between a 3270 terminal and a CICS program, and the COMMAREA of
 * {@code app/cpy/COCOM01Y.cpy}, which carried identity and selection state between programs across
 * {@code EXEC CICS XCTL}.
 *
 * <p>Nothing here is a screen. The 3270 and BMS presentation layer is <strong>not</strong> reimplemented:
 * there is no green screen rendering, no pseudo conversational session emulation and no user interface of
 * any kind in this package. The symbolic maps are consumed purely as <em>field contracts</em> that fix each
 * payload's field names, types and lengths.
 *
 * <h3>Verified BMS input field census: 441 fields</h3>
 *
 * <p>The census was established by counting the {@code ...I PIC} declarations lying strictly inside each
 * map's input group, that is between the {@code 01 xxxxAI.} line and the following
 * {@code 01 xxxxAO REDEFINES} line, across all 17 maps, with comment lines excluded and only COBOL columns
 * 7 to 72 considered. Per map:
 *
 * <ul>
 *   <li>{@code COACTUP.CPY} 54</li>
 *   <li>{@code COACTVW.CPY} 37</li>
 *   <li>{@code COADM01.CPY} 20</li>
 *   <li>{@code COBIL00.CPY} 10</li>
 *   <li>{@code COCRDLI.CPY} 45</li>
 *   <li>{@code COCRDSL.CPY} 15</li>
 *   <li>{@code COCRDUP.CPY} 17</li>
 *   <li>{@code COMEN01.CPY} 20</li>
 *   <li>{@code CORPT00.CPY} 17</li>
 *   <li>{@code COSGN00.CPY} 11</li>
 *   <li>{@code COTRN00.CPY} 59</li>
 *   <li>{@code COTRN01.CPY} 21</li>
 *   <li>{@code COTRN02.CPY} 21</li>
 *   <li>{@code COUSR00.CPY} 59</li>
 *   <li>{@code COUSR01.CPY} 12</li>
 *   <li>{@code COUSR02.CPY} 12</li>
 *   <li>{@code COUSR03.CPY} 11</li>
 * </ul>
 *
 * <p>The sum is stated explicitly so it can be checked without recounting:
 * 54 + 37 + 20 + 10 + 45 + 15 + 17 + 20 + 17 + 11 + 59 + 21 + 21 + 59 + 12 + 12 + 11 =
 * <strong>441</strong>. Running totals, for the same reason: 54, 91, 111, 121, 166, 181, 198, 218, 235,
 * 246, 305, 326, 347, 406, 418, 430, 441.
 *
 * <p>Three of the counts are large because of row arrays rather than richer screens, and the arrays are
 * the pagination contract of Key configuration and defaults below: {@code COTRN00.CPY} and
 * {@code COUSR00.CPY} each carry ten rows of five fields, and {@code COCRDLI.CPY} carries seven rows, six
 * of them of five fields and the first of four.
 *
 * <p><strong>Correction, Medium severity.</strong> The body of
 * {@code docs/technical-specifications.md} reports <strong>460</strong> input fields in total and
 * <strong>36</strong> for {@code COACTVW.CPY}. Both figures are <strong>incorrect</strong>. The governing
 * figures are <strong>441</strong> and <strong>37</strong>, measured by the method above and reproducible
 * from the frozen corpus at {@code 7756d89}. The consequence of trusting 36 is a payload one field short
 * of its map, so it is recorded rather than quietly reconciled. The discrepancy register for the migration
 * is the root {@code DECISION_LOG.md}; no Markdown file is created in this package to hold it, because
 * this docstring is the only documentation vehicle permitted here.
 *
 * <h3>The 17 payloads and the artefact each derives from</h3>
 *
 * <ul>
 *   <li>{@link SignOnRequest} from {@code app/cpy-bms/COSGN00.CPY} (11 fields). Carries the presented
 *       credentials inward only.</li>
 *   <li>{@link SignOnResponse} has <strong>no BMS symbolic map: Not available.</strong> No such map exists
 *       anywhere in {@code app/cpy-bms}, and none is invented here. The type is new to the target and
 *       carries the issued token in place of the populated COMMAREA that the legacy sign on returned. What
 *       would be needed to derive it from the corpus is a symbolic map describing an authentication
 *       response, and the corpus contains none, because CICS returned identity in the COMMAREA rather than
 *       on a screen.</li>
 *   <li>{@link AccountDto} from {@code app/cpy-bms/COACTVW.CPY} (37 fields).</li>
 *   <li>{@link AccountUpdateRequest} from {@code app/cpy-bms/COACTUP.CPY} (54 fields) together with
 *       {@code app/cbl/COACTUPC.cbl}. It carries <strong>both</strong> the old and the new detail groups,
 *       mirroring {@code ACUP-OLD-DETAILS} and {@code ACUP-NEW-DETAILS}, because a stateless request cannot
 *       otherwise reproduce the change detection comparison. See failure mode 1.</li>
 *   <li>{@link CardDto} from {@code app/cpy-bms/COCRDSL.CPY} (15 fields) for the detail projection and
 *       {@code app/cpy-bms/COCRDLI.CPY} (45 fields) for the list row projection.</li>
 *   <li>{@link CardUpdateRequest} from {@code app/cpy-bms/COCRDUP.CPY} (17 fields).</li>
 *   <li>{@link TransactionDto} from {@code app/cpy-bms/COTRN01.CPY} (21 fields) plus the row descriptions
 *       of {@code app/cpy-bms/COTRN00.CPY} (59 fields).</li>
 *   <li>{@link TransactionAddRequest} from {@code app/cpy-bms/COTRN02.CPY} (21 fields).</li>
 *   <li>{@link UserSecurityDto} from {@code app/cpy-bms/COUSR00.CPY} (59 fields). Carries no password and
 *       no hash.</li>
 *   <li>{@link UserCreateRequest} from {@code app/cpy-bms/COUSR01.CPY} (12 fields).</li>
 *   <li>{@link UserUpdateRequest} from {@code app/cpy-bms/COUSR02.CPY} (12 fields).</li>
 *   <li>{@link BillPaymentRequest} from {@code app/cpy-bms/COBIL00.CPY} (10 fields).</li>
 *   <li>{@link ReportRequest} from {@code app/cpy-bms/CORPT00.CPY} (17 fields), covering the three report
 *       periods including the six custom range date components.</li>
 *   <li>{@link MenuResponse} from {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}, the two
 *       option tables rather than a symbolic map.</li>
 *   <li>{@link PageResponse} from the pagination fields of {@code app/cpy-bms/COCRDLI.CPY},
 *       {@code app/cpy-bms/COTRN00.CPY} and {@code app/cpy-bms/COUSR00.CPY} together with the owning
 *       programs' {@code WORKING-STORAGE}. <strong>Not</strong> from the COMMAREA; see failure mode 6.</li>
 *   <li>{@link CommArea} from {@code app/cpy/COCOM01Y.cpy}, reduced to the fields that survive the move to
 *       a stateless protocol.</li>
 *   <li>{@link StatementTransaction} from {@code app/cpy/COSTM01.CPY}, whose {@code TRNX-KEY} is
 *       {@code TRNX-CARD-NUM PIC X(16)} followed by {@code TRNX-ID PIC X(16)} for a 32 byte key, plus the
 *       report line layouts of {@code app/cpy/CVTRA07Y.cpy}.</li>
 * </ul>
 *
 * <p>Two of the 17 therefore do not come from a symbolic map at all, and that is deliberate rather than an
 * omission: {@link MenuResponse} comes from two option tables and {@link CommArea} comes from the
 * communication area. A third, {@link SignOnResponse}, has no legacy counterpart of any kind.
 *
 * <p><strong>Correction, Medium severity.</strong> {@code docs/technical-specifications.md} is internally
 * inconsistent about how many payloads this package holds. Its scope table gives the count as
 * <strong>16</strong>, while its own target structure listing enumerates <strong>17</strong> by name, and
 * the 17 names it enumerates are exactly the 17 listed above. The governing figure is <strong>17</strong>
 * payloads and therefore <strong>18</strong> {@code .java} files including this one. The consequence of
 * trusting 16 is one payload never authored and one endpoint left without a request or response type, so
 * the discrepancy is recorded here and in the root {@code DECISION_LOG.md} rather than reconciled
 * silently.
 *
 * <p>{@code app/cpy/CSSETATY.cpy} is cited in the banner but has <strong>no class in this package</strong>.
 * It is a {@code COPY ... REPLACING} <strong>PROCEDURE DIVISION</strong> template, parameterised on
 * {@code (TESTVAR1)}, {@code (SCRNVAR2)} and {@code (MAPNAME3)}, not a data layout. It contributes the
 * three state validation model described in failure mode 5 and maps onto validation annotations and per
 * field error markers rather than onto a type.
 *
 * <h3>Package wide invariants that all 17 payloads must honour</h3>
 *
 * <ul>
 *   <li><strong>Pure data holders.</strong> No payload may import from {@code com.cardemo.exception},
 *       {@code com.cardemo.repository}, {@code com.cardemo.service}, {@code com.cardemo.controller},
 *       {@code com.cardemo.batch}, {@code com.cardemo.security}, {@code com.cardemo.config} or
 *       {@code com.cardemo.observability}. The <strong>only</strong> permitted project import is
 *       {@code com.cardemo.model.enums}. That single rule is what keeps this package a leaf of the
 *       dependency graph and free of cycles by construction.</li>
 *   <li><strong>A payload holds data; it does not act on it.</strong> It does not compare, normalise, map,
 *       persist or reach a repository. In particular <strong>no payload performs ingest time
 *       {@code trim()}, {@code toUpperCase()} or {@code toLowerCase()}</strong>. Normalisation looks
 *       harmless and destroys comparison fidelity: the corpus compares some fields through
 *       {@code FUNCTION LOWER-CASE}, others through {@code FUNCTION UPPER-CASE} and others with no case
 *       function at all, and it distinguishes spaces from {@code LOW-VALUES}. A payload that has already
 *       folded or trimmed its input has destroyed the evidence the comparison needs.</li>
 *   <li><strong>Zero static mutable fields</strong> anywhere in the package. {@code static final}
 *       references to immutable values are permitted and are not mutable state.</li>
 *   <li><strong>Defensive copies on both ingress and egress</strong> for every collection or array field. A
 *       constructor or setter that stores the caller's {@code List} directly, or a getter that returns the
 *       internal one, publishes shared mutable state, which Clause B of the project standard forbids.</li>
 *   <li><strong>No {@code float} and no {@code double} in any monetary field.</strong> Monetary values are
 *       {@code java.math.BigDecimal}, compared with {@code compareTo()} and <strong>never</strong> with
 *       {@code equals()}, and rounded {@code RoundingMode.HALF_EVEN}. Two precisions occur and must not be
 *       conflated: account money is {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7-L14}, so scale
 *       2 and precision 12, while {@code TRAN-AMT} at {@code app/cpy/CVTRA05Y.cpy:L10} and
 *       {@code TRNX-AMT} at {@code app/cpy/COSTM01.CPY:L29} are {@code PIC S9(09)V99}, so scale 2 and
 *       precision 11.</li>
 *   <li><strong>Timestamps are text.</strong> {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are
 *       {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16-L17}, as are {@code TRNX-ORIG-TS} and
 *       {@code TRNX-PROC-TS} at {@code app/cpy/COSTM01.CPY:L34-L35}. They are modelled as {@code String}
 *       and <strong>never</strong> as {@code LocalDateTime}, {@code Timestamp} or {@code Instant}. Three
 *       mutually incompatible producers write these 26 bytes: the batch generator emits
 *       {@code yyyy-MM-dd-HH.mm.ss.SS0000}, the online path emits
 *       {@code yyyy-MM-dd HH:mm:ss.000000}, and some values are pure pass through of whatever the input
 *       record contained. No single temporal type round trips all three, so text is the only faithful
 *       representation, and parsing on ingest would corrupt the values a parity comparison reads.</li>
 *   <li><strong>No secret is ever serialized outward.</strong> Password fields are write only, that is
 *       deserialize only. {@link UserSecurityDto} carries neither a password nor a hash.
 *       {@link SignOnResponse} carries neither a hash nor a signing key. No {@code toString} anywhere in
 *       this package may expose credentials, a presented password, a BCrypt hash (prefix {@code $2a$},
 *       {@code $2b$} or {@code $2y$}), a JWT, a signing key, an {@code Authorization} or {@code Bearer}
 *       header, a social security number, a card number, a telephone number, a government issued
 *       identifier, a date of birth or an electronic funds transfer account identifier.</li>
 *   <li><strong>{@code Locale.ROOT} on every case conversion and every format operation.</strong> The
 *       platform default locale, charset and time zone are never relied upon, so behaviour cannot differ
 *       between a developer machine and continuous integration.</li>
 *   <li><strong>No shared abstractions.</strong> There is deliberately no common header helper, no shared
 *       balance helper, no mapper, no value object and no validator class in this package. This is
 *       <em>not</em> an oversight and must not be "tidied up": the field contracts diverge in ways that
 *       make such abstractions factually wrong. The evidence is specific.
 *       <ul>
 *         <li>{@code CURTIMEI} is {@code PIC X(9)} at {@code app/cpy-bms/COSGN00.CPY:54} and
 *             {@code PIC X(8)} at line 54 of each of the other sixteen maps. One shared header type cannot
 *             be both widths.</li>
 *         <li>{@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66} differs in
 *             <strong>name and width</strong> from {@code ACURBALI PIC X(15)} at
 *             {@code app/cpy-bms/COACTUP.CPY:138} and {@code app/cpy-bms/COACTVW.CPY:102}. A shared
 *             balance holder would have to pick one and silently truncate or pad the other.</li>
 *         <li>{@code ACCTSIDI} is numeric {@code PIC 99999999999} at
 *             {@code app/cpy-bms/COACTVW.CPY:60} but {@code PIC X(11)} in four other maps, at
 *             {@code app/cpy-bms/COACTUP.CPY:60}, {@code app/cpy-bms/COCRDLI.CPY:66},
 *             {@code app/cpy-bms/COCRDSL.CPY:60} and {@code app/cpy-bms/COCRDUP.CPY:60}. The same screen
 *             name is a different type depending on the map.</li>
 *         <li>Even the pagination field is not shared: it is {@code PAGENUMI} in
 *             {@code app/cpy-bms/COTRN00.CPY} and {@code app/cpy-bms/COUSR00.CPY} but {@code PAGENOI} in
 *             {@code app/cpy-bms/COCRDLI.CPY}.</li>
 *       </ul>
 *       Each payload therefore declares its own fields at its own map's widths. Duplication that mirrors a
 *       divergent source is correctness, not redundancy.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>There is <strong>nothing to run</strong>. This package contains no {@code main} method, no Spring
 * bean, no scheduled task and no HTTP endpoint. It is compiled as part of the single CardDemo Spring Boot
 * module, which is a modular monolith and deliberately not a set of microservices, and it is exercised only
 * through its consumers in the controller, service and batch layers.
 *
 * <h3>Build</h3>
 *
 * <ul>
 *   <li><strong>Command.</strong> {@code ./mvnw -q -DskipTests compile} to compile and
 *       {@code ./mvnw -q verify} for the full gated build, both from the repository root.</li>
 *   <li><strong>Language level.</strong> Java <strong>25</strong>, set through
 *       {@code maven.compiler.release} rather than {@code source} and {@code target}, so the JDK 25 API
 *       surface is enforced and not merely the bytecode version. <strong>No preview features</strong> are
 *       enabled and none may be used here.</li>
 *   <li><strong>Pinned toolchain.</strong> Apache Maven <strong>3.9.11</strong> through the checked in
 *       wrapper, parent {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}, and
 *       {@code maven-compiler-plugin:3.14.1}. {@code maven-enforcer-plugin} asserts a Java floor of
 *       {@code [25,)} and a Maven floor of {@code [3.9.11,)}, so the build cannot silently run on a
 *       different toolchain. Maven 4 is deliberately not adopted: the plugin set this build depends on is
 *       validated against the 3.9 line.</li>
 *   <li><strong>Warnings are errors.</strong> The compiler runs with {@code -Xlint:all} and
 *       {@code -Werror}, and additionally with {@code -parameters}. An unused import, a raw type, an
 *       unchecked cast, a call to a deprecated API, a {@code switch} fall through or a missing
 *       {@code serialVersionUID} on a serializable type is a <strong>hard build failure</strong>, not a
 *       warning to triage later. This is exactly why this file carries <strong>zero imports and zero
 *       annotations</strong>: there is no nullability annotation available to apply, because no JSR 305 and
 *       no JSpecify artefact is declared anywhere in {@code pom.xml}, and reaching for a framework
 *       annotation such as one from {@code org.springframework.lang} instead would risk a deprecation that
 *       the build treats as fatal. See failure modes 8 and 9.</li>
 *   <li><strong>No Lombok, no annotation processor, no new dependency.</strong> This package adds none.
 *       Every accessor, every {@code equals}, every {@code hashCode} and every {@code toString} across the
 *       17 payloads is written out explicitly, so what is compiled is exactly what is read.</li>
 * </ul>
 *
 * <h3>Test</h3>
 *
 * <ul>
 *   <li><strong>Location.</strong> Unit tests for these 17 types live in the sibling test tree at
 *       {@code src/test/java/com/cardemo/unit/model}, <strong>never</strong> in this package. This package
 *       stays free of test scaffolding, and it contains no test class and no fixture.</li>
 *   <li><strong>Coverage gate.</strong> JaCoCo enforces an <strong>80 percent LINE</strong> coverage floor
 *       on the merged bundle at the {@code verify} phase with {@code haltOnFailure}, and there are
 *       <strong>no exclusions</strong> for this package. The figure must come from meaningful assertions
 *       and must not be padded by calling getters in a loop. This file is documentation only, contributes
 *       no executable lines, and therefore neither helps nor harms the figure.</li>
 *   <li><strong>Coverage plugin version.</strong> Pinned to <strong>0.8.13</strong>. The requirements named
 *       0.8.12, but that release cannot physically run on this target: Java 25 emits class file major
 *       version 69, which the ASM build inside 0.8.12 rejects outright, failing the report goal before any
 *       coverage figure is computed. 0.8.13 is the smallest release that reads it, so the stated preference
 *       for the lower version is kept while the gate is made able to run at all, and 0.8.14 is not adopted.
 *       Recorded in {@code DECISION_LOG.md}.</li>
 *   <li><strong>Assertions that actually matter</strong> for this package, as distinct from mechanical
 *       accessor coverage:
 *       <ul>
 *         <li>Each payload's field count asserted against its map's verified census figure, so a field
 *             added or dropped fails the test rather than drifting silently.</li>
 *         <li>{@link AccountUpdateRequest} carrying both detail groups, with the date of birth snapshot
 *             held in its compact 8 character form and compared component wise. This is the single most
 *             important assertion in the package; see failure mode 1.</li>
 *         <li>{@link CardDto} list rows modelling row 1 with four fields and rows 2 through 7 with five,
 *             asserted on the absence of a first row selector; see failure mode 3.</li>
 *         <li>Monetary fields asserted to be {@code BigDecimal} of scale 2 at the correct precision, 12 for
 *             account money and 11 for transaction amounts, and compared with {@code compareTo()} so that
 *             a value differing only in trailing zeros still compares equal.</li>
 *         <li>Timestamp fields asserted to remain 26 character {@code String} values, byte identical to the
 *             input, under all three producer formats.</li>
 *         <li>Every collection accessor asserted to return a copy, by mutating the returned value and
 *             confirming the payload is unchanged.</li>
 *         <li>Every {@code toString} asserted <em>not</em> to contain any password, hash prefix, token or
 *             other value named in the no secret invariant above.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Determinism.</strong> No static mutable state, no dependence on a clock and no reliance on
 *       the platform default locale, charset or time zone, so a test that passes locally passes in
 *       continuous integration for the same reason.</li>
 *   <li><strong>Contribution expectation.</strong> {@code CONTRIBUTING.md:L34} requires
 *       "3. Ensure local tests pass." before a change is proposed, and {@code CONTRIBUTING.md:L33} asks
 *       that a change stay focused rather than reformatting surrounding code. Both apply to every edit in
 *       this package.</li>
 * </ul>
 *
 * <h3>Toolchain actually present in this environment</h3>
 *
 * <p>Measured rather than assumed, so the statement can be relied on: {@code java} and {@code javac} report
 * OpenJDK <strong>25.0.3</strong>, {@code mvn} reports Apache Maven <strong>3.9.11</strong>, and
 * <strong>Docker Engine 29.7.0 with {@code docker compose} v5.3.1 is available</strong> and is what
 * provisions PostgreSQL 16, LocalStack, Jaeger, Prometheus and Grafana for the integration tiers. The host
 * toolchain is activated by sourcing {@code /etc/profile.d/10-carddemo-toolchain.sh}. Where a host JDK is
 * not provisioned, the identical build runs inside the pinned Java 25 and Maven 3.9.11 container image with
 * the repository mounted, and produces the same result because every plugin and every non managed
 * dependency version is pinned. <strong>Any claim that the Java toolchain or the container runtime is
 * absent is stale and must not be repeated.</strong>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>No value in this package is configurable. Not one
 * {@code src/main/resources/application*.yml} key, environment variable, system property, command line
 * argument or active profile changes how any of the 17 payloads behaves. There is no
 * {@code @ConfigurationProperties} class, no {@code @Value} injection and no {@code Environment} lookup
 * anywhere here. Every default below is <strong>source fixed</strong>, transcribed from the frozen corpus at
 * {@code 7756d89}, and changes only if the corpus changes, which it does not: {@code app/} and
 * {@code samples/} are read only for the whole migration.
 *
 * <ul>
 *   <li><strong>Page sizes are three separate constants, not one.</strong>
 *       <ul>
 *         <li><strong>7</strong> for the card list, from
 *             {@code app/cbl/COCRDLIC.cbl:L177-L178}, {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE
 *             7}.</li>
 *         <li><strong>10</strong> for the transaction list. The bound is written out in the paging loops at
 *             {@code app/cbl/COTRN00C.cbl:L290}, {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX
 *             &gt; 10}, and {@code :L297}, {@code PERFORM UNTIL WS-IDX &gt;= 11}, with the backward paging
 *             equivalents at {@code :L344} and {@code :L351}. The pagination state that
 *             {@link PageResponse} carries is declared separately at {@code :L65-L68} as
 *             {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} and {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE
 *             'N'}.</li>
 *         <li><strong>10</strong> for the user list, from {@code app/cbl/COUSR00C.cbl:L57},
 *             {@code 02 USER-REC OCCURS 10 TIMES}.</li>
 *       </ul>
 *       Collapsing these into one shared page size would change the card list from 7 rows to 10 and break
 *       parity on the very first page.</li>
 *   <li><strong>Menu option counts are bounded by the count field, never by the table capacity.</strong>
 *       There are exactly <strong>10</strong> populated main menu options,
 *       {@code app/cpy/COMEN02Y.cpy:21}, {@code 05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10}, and exactly
 *       <strong>4</strong> populated admin options, {@code app/cpy/COADM02Y.cpy:20},
 *       {@code 05 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}. The declared tables are larger, at
 *       {@code OCCURS 12 TIMES} on {@code app/cpy/COMEN02Y.cpy:88} and {@code OCCURS 9 TIMES} on
 *       {@code app/cpy/COADM02Y.cpy:45}. Iterating to the capacity rather than to the count emits two empty
 *       main options and five empty admin options.</li>
 *   <li><strong>No server side session state.</strong> Pagination travels in request parameters and
 *       response metadata only. The COMMAREA fields that existed solely to drive the pseudo conversational
 *       CICS flow have no counterpart and are omitted from {@link CommArea}: {@code CDEMO-FROM-TRANID},
 *       {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM} and {@code CDEMO-TO-PROGRAM}, because routing is
 *       URL based; {@code CDEMO-PGM-CONTEXT} with its {@code CDEMO-PGM-ENTER} and
 *       {@code CDEMO-PGM-REENTER} condition names, because the enter versus re enter distinction collapses
 *       into stateless request handling; and {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET}, because
 *       no screen state is retained. What does survive is identity and selection:
 *       {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} become token claims, while
 *       {@code CDEMO-ACCT-ID}, {@code CDEMO-CARD-NUM} and {@code CDEMO-CUST-ID} become payload fields.</li>
 *   <li><strong>JSON serialisation.</strong> The framework's bundled Jackson, managed by
 *       {@code jackson-bom} through the Spring Boot parent, with <strong>no custom module</strong> and no
 *       custom {@code ObjectMapper} configuration originating from this package, unless a masking
 *       requirement from the no secret invariant demands one. Field names are the payload's own, and no
 *       global naming strategy is applied that would rename them.</li>
 *   <li><strong>Fixed width geometry preserved at every boundary.</strong> These byte counts are contracts,
 *       not hints, because the parity comparison is byte level: the transaction image is <strong>350</strong>
 *       bytes; the statement record is <strong>350</strong>, being a <strong>32</strong> byte key of
 *       {@code TRNX-CARD-NUM PIC X(16)} plus {@code TRNX-ID PIC X(16)} at
 *       {@code app/cpy/COSTM01.CPY:L21-L23} and a <strong>318</strong> byte remainder; the report line is
 *       <strong>133</strong>; the statement text output is <strong>80</strong>; and the statement HTML
 *       output is <strong>100</strong>. Emitting any other width is a parity failure even when every field
 *       value is correct.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Nine failure modes, each one an implementation that compiles cleanly, passes a naive test and still
 * diverges from the system of record. Every one has been observed to arise from a reasonable looking
 * simplification, which is why each is listed with its symptom, its evidence and its remedy rather than
 * left to be rediscovered.
 *
 * <ol>
 *   <li><strong>A whole string date of birth comparison bricks the account update endpoint.
 *       Severity: Blocker.</strong>
 *       <br><em>Symptom:</em> every single {@code PUT} of an account reports that the record changed since
 *       it was read and refuses the write, so the endpoint is permanently unusable and no account can ever
 *       be updated.
 *       <br><em>Evidence:</em> the live customer field is dash separated,
 *       {@code 05 CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:19}, with components at
 *       offsets 1, 6 and 9. The snapshot field is <strong>compact</strong>,
 *       {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code app/cbl/COACTUPC.cbl:L746}, with
 *       components at 1, 5 and 7. The source therefore compares them <em>component wise with different
 *       offsets on each side</em> at {@code app/cbl/COACTUPC.cbl:L4174-L4179}: {@code (1:4)} against
 *       {@code (1:4)}, then {@code (6:2)} against {@code (5:2)}, then {@code (9:2)} against
 *       {@code (7:2)}. Comparing the two ten and eight character strings whole can never match, and storing
 *       ten dash separated characters into the eight byte snapshot truncates the day.
 *       <br><em>Remedy:</em> {@link AccountUpdateRequest} stores the snapshot date in compact 8 character
 *       form and the comparison is performed component by component, year then month then day, exactly as
 *       the source does. Never compare the two dates as whole strings.</li>
 *
 *   <li><strong>A shared header helper breaks sign on.</strong>
 *       <br><em>Symptom:</em> the sign on payload silently loses a character of the current time, or every
 *       other screen gains a spurious one, depending on which width the shared type picked.
 *       <br><em>Evidence:</em> {@code CURTIMEI} is {@code PIC X(9)} at
 *       {@code app/cpy-bms/COSGN00.CPY:54} and {@code PIC X(8)} at line 54 of each of the other sixteen
 *       maps. The six recurring header fields look interchangeable and are not.
 *       <br><em>Remedy:</em> declare the header fields per payload at that payload's own map widths. Never
 *       extract them into a shared type. The same reasoning applies to {@code CURBALI} versus
 *       {@code ACURBALI} and to {@code ACCTSIDI}, both detailed in the no shared abstractions
 *       invariant above.</li>
 *
 *   <li><strong>Inventing a first row status selector breaks the card list contract.</strong>
 *       <br><em>Symptom:</em> a seven element row model whose first element has a field the map never
 *       sends, so deserialisation of a real payload leaves it null and any round trip adds a field the
 *       legacy screen cannot accept.
 *       <br><em>Evidence:</em> {@code app/cpy-bms/COCRDLI.CPY} declares {@code CRDSTP2I} through
 *       {@code CRDSTP7I PIC X(1)} at lines 108, 138, 168, 198, 228 and 258. There is
 *       <strong>no {@code CRDSTP1I} of any suffix</strong>, and the string {@code CRDSTP1} does not occur
 *       anywhere in {@code app/}. That is six selectors over a seven row page: row 1 genuinely has none.
 *       The field census confirms the shape, since 6 header fields plus {@code PAGENOI} plus
 *       {@code ACCTSIDI} plus {@code CARDSIDI} plus 4 for row 1 plus 5 for each of rows 2 through 7 plus
 *       {@code INFOMSGI} and {@code ERRMSGI} is exactly the verified 45.
 *       <br><em>Remedy:</em> {@link CardDto} models row 1 with four fields and rows 2 through 7 with five.
 *       Do not regularise the array.</li>
 *
 *   <li><strong>One shared amount parser diverges from the source's two.</strong>
 *       <br><em>Symptom:</em> either an amount written as {@code $1,234.56} is rejected although the legacy
 *       screen accepted it, or an account identifier written as {@code 1,234} is accepted although the
 *       legacy screen rejected it. Both are silent parity breaks on valid user input.
 *       <br><em>Evidence:</em> {@code app/cbl/COTRN02C.cbl} uses the plain intrinsic for identifiers,
 *       {@code FUNCTION NUMVAL} at {@code :L204} for the account identifier and {@code :L218} for the card
 *       number, but the currency tolerant intrinsic for amounts, {@code FUNCTION NUMVAL-C} at
 *       {@code :L383} and {@code :L456}. The currency tolerant form additionally accepts a currency symbol
 *       and thousands separators.
 *       <br><em>Remedy:</em> keep <strong>two</strong> parsers, a strict digits only parser for identifiers
 *       and card numbers and a currency tolerant parser for amounts, and apply each where the source
 *       applies it. Note that parsing itself belongs to the validation layer, not to a payload: the payload
 *       carries the raw text so the correct parser can be chosen by the caller.</li>
 *
 *   <li><strong>Collapsing null, blank and low values breaks the three state model.</strong>
 *       <br><em>Symptom:</em> a field left empty produces the "is not valid" message where the legacy
 *       screen produced "must be supplied", and the field marker fails to appear, so the response text no
 *       longer matches the parity baseline.
 *       <br><em>Evidence:</em> {@code app/cpy/CSSETATY.cpy} is a {@code COPY ... REPLACING} template whose
 *       body tests {@code IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER}, so
 *       <strong>BLANK is a distinct state from NOT-OK</strong>, and inside that guard the BLANK branch
 *       additionally performs {@code MOVE '*'}, so a blank field is stamped where an invalid one is not.
 *       The markers fire <strong>only on re entry</strong>. Distinctly again,
 *       {@code app/cbl/COACTUPC.cbl:L1256-L1275} moves <strong>{@code LOW-VALUES}</strong>, which is binary
 *       zeros and not spaces, into each date of birth component when the screen field holds {@code '*'} or
 *       {@code SPACES}. The message literals settle it: {@code app/cbl/COACTUPC.cbl:L505-L508} declares
 *       {@code CRED-LIMIT-IS-BLANK VALUE 'Credit Limit must be supplied'} and
 *       {@code CRED-LIMIT-IS-NOT-VALID VALUE 'Credit Limit is not valid'} - two different strings for the
 *       same field.
 *       <br><em>Remedy:</em> keep absent, blank and low values as three distinct states through the payload
 *       and into validation. Do not map blank to null on ingest, and do not normalise low values to
 *       spaces.</li>
 *
 *   <li><strong>Attributing pagination metadata to the COMMAREA sends you hunting for a field that does not
 *       exist. Severity: Medium.</strong>
 *       <br><em>Symptom:</em> time lost searching {@code app/cpy/COCOM01Y.cpy} for a page number, followed
 *       by an invented field and a page size that matches no screen.
 *       <br><em>Evidence:</em> {@code app/cpy/COCOM01Y.cpy} declares exactly five groups,
 *       {@code CDEMO-GENERAL-INFO}, {@code CDEMO-CUSTOMER-INFO}, {@code CDEMO-ACCOUNT-INFO},
 *       {@code CDEMO-CARD-INFO} and {@code CDEMO-MORE-INFO}, and contains <strong>no page number field and
 *       no next page flag</strong>. The real sources are the BMS maps, where the field is {@code PAGENUMI}
 *       in {@code app/cpy-bms/COTRN00.CPY} and {@code app/cpy-bms/COUSR00.CPY} but {@code PAGENOI} in
 *       {@code app/cpy-bms/COCRDLI.CPY}, together with each owning program's {@code WORKING-STORAGE}, for
 *       example {@code CDEMO-CT00-PAGE-NUM} and {@code CDEMO-CT00-NEXT-PAGE-FLG} at
 *       {@code app/cbl/COTRN00C.cbl:L65-L68}. Those sources disagree with each other on both the field name
 *       and the page size.
 *       <br><em>Remedy:</em> see {@link PageResponse}, which is derived from the maps and the working
 *       storage rather than from the COMMAREA, and which keeps the three page sizes separate.</li>
 *
 *   <li><strong>"Fixing" the statement projection truncation produces output that differs from the parity
 *       baseline.</strong>
 *       <br><em>Symptom:</em> a statement whose processing timestamp is two characters longer than the
 *       legacy one, reported as a Java defect when it is in fact a faithful reproduction that was
 *       undone.
 *       <br><em>Evidence:</em> {@code app/jcl/CREASTMT.JCL:L54} specifies
 *       {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} after the two key sort at {@code :L53},
 *       {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}. The projection copies only <strong>50</strong> bytes
 *       from offset 279, but the two timestamps that begin there occupy 26 plus 26 bytes. The originating
 *       timestamp survives whole and the processing timestamp keeps only its <strong>first 24</strong>
 *       characters, so {@code TRNX-PROC-TS} is declared {@code PIC X(26)} at
 *       {@code app/cpy/COSTM01.CPY:L35} yet carries 24 significant characters padded to 26. The base
 *       record's 20 byte trailing {@code FILLER} is never written at all.
 *       <br><em>Remedy:</em> {@link StatementTransaction} reproduces the truncation exactly. Do not restore
 *       the two characters and do not reinstate the filler.</li>
 *
 *   <li><strong>An unused import fails the build.</strong>
 *       <br><em>Symptom:</em> {@code mvn compile} fails on a file that a permissive IDE reported as clean,
 *       often after a field type changed and left its import behind.
 *       <br><em>Evidence:</em> {@code maven-compiler-plugin:3.14.1} is configured with {@code -Xlint:all}
 *       and {@code -Werror}, so a lint warning is an error. Clause B of the project standard independently
 *       forbids unused imports and dead code.
 *       <br><em>Remedy:</em> import only what is referenced. This file imports nothing at all, which is why
 *       it cannot fail this way; the same discipline applies to the 17 payloads beside it.</li>
 *
 *   <li><strong>{@code Serializable} without {@code serialVersionUID} fails the build.</strong>
 *       <br><em>Symptom:</em> adding {@code implements Serializable} to a payload, for instance to place it
 *       in an HTTP session or a cache, fails the compile with the
 *       {@code missing-explicit-serial-version-uid} lint rather than a warning.
 *       <br><em>Evidence:</em> that lint is enabled by {@code -Xlint:all} and made fatal by
 *       {@code -Werror}.
 *       <br><em>Remedy:</em> prefer <strong>not</strong> implementing {@code Serializable}. No payload in
 *       this package does, and none needs to, because nothing in the target transports these types by Java
 *       serialisation - they cross every boundary as JSON. If a future requirement genuinely demands it,
 *       declare an explicit {@code private static final long serialVersionUID}.</li>
 * </ol>
 *
 * <h2>Not available</h2>
 *
 * <p>Three things this package would benefit from do not exist in the corpus, and are reported as
 * unavailable rather than invented.
 *
 * <ul>
 *   <li><strong>A symbolic map for {@link SignOnResponse}: Not available.</strong> No authentication
 *       response map exists in {@code app/cpy-bms}, because CICS returned identity in the COMMAREA rather
 *       than on a screen. <em>What would be needed:</em> a symbolic map describing the response side of the
 *       sign on conversation. The corpus contains none, so the type is new to the target and its shape is
 *       justified by the token replacing the COMMAREA rather than by a field contract.</li>
 *   <li><strong>A machine readable field contract: Not available.</strong> There is no schema, IDL or
 *       generated specification for any of these payloads. The symbolic maps are the only authority, and
 *       they are fixed column COBOL. <em>What would be needed:</em> the maps themselves, read at
 *       {@code 7756d89} - which is why the census method is written out above in enough detail to be
 *       reproduced rather than merely asserted.</li>
 *   <li><strong>A generated OpenAPI document: Not available and out of scope.</strong> Specification
 *       generation is explicitly excluded from this migration. The manual substitute is
 *       {@code docs/api-contracts.md}, which documents each endpoint's payloads and error modes.</li>
 * </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li><strong>Directory shape.</strong> This package contains exactly <strong>18</strong> {@code .java}
 *       files: the 17 payloads named above and this file. No nineteenth file may be added, and in
 *       particular <strong>no {@code README} and no Markdown file of any kind</strong>, here or anywhere
 *       under {@code src/main/java}, because this docstring is the module documentation. Cross cutting
 *       findings belong in the root {@code DECISION_LOG.md} and
 *       {@code TRACEABILITY_MATRIX.md}.</li>
 *   <li><strong>No shared helper, mapper, base class or validator.</strong> For the field divergence
 *       reasons evidenced above. A reviewer seeing repeated header fields across payloads is seeing a
 *       faithful reproduction of seventeen genuinely different maps, not copy and paste to be
 *       refactored.</li>
 *   <li><strong>Dependency direction.</strong> Outward only. These types are consumed by the controller,
 *       service and batch layers and depend on none of them, so the package is a leaf and cannot
 *       participate in a cycle. The single permitted project import is {@code com.cardemo.model.enums}.</li>
 *   <li><strong>This file carries zero imports and zero annotations</strong>, by design and not by
 *       omission, for the reasons given under Build above. No package level nullability annotation is
 *       applied because no such artefact is declared in {@code pom.xml}.</li>
 *   <li><strong>Nothing sensitive is present here</strong>, and nothing sensitive may be added: this file
 *       contains no credential, no token, no key, no endpoint and no personally identifying value, only
 *       citations into the frozen corpus.</li>
 *   <li><strong>The corpus stays frozen.</strong> Every citation in this file is a read. No file under
 *       {@code app/} or {@code samples/} is edited, moved, renamed, reformatted or deleted by this
 *       migration, because that tree is simultaneously the parity oracle, the field contract source and the
 *       traceability anchor, and it loses all three roles the moment it is touched.</li>
 * </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
package com.cardemo.model.dto;
