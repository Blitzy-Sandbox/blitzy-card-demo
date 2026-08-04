/*
 * ******************************************************************
 * Program     : package-info.java
 * Component   : com.cardemo.service (service layer root)
 * Package     : com.cardemo.service
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer root - the online business tier)
 * Function    : Layer level documentation for the 21 service beans
 *               that replace the 17 sourced CICS screen programs of
 *               the frozen corpus, together with the four shared
 *               services that absorb the statically called date
 *               utility, the file access subprogram, the lookup
 *               tables and the universal FILE STATUS guard idiom.
 *               This package declares no type of its own: it states
 *               what the layer is, how it is built and tested, the
 *               configuration it binds and how it fails, and points
 *               at the nine subpackages that own the detail.
 * Source      : app/cbl/COSGN00C.cbl (260 lines, 6 paragraphs),
 *               COACTVWC.cbl (941, 38), COACTUPC.cbl (4,236, 88),
 *               COCRDLIC.cbl (1,459, 42), COCRDSLC.cbl (887, 37),
 *               COCRDUPC.cbl (1,560, 48), COTRN00C.cbl (699, 16),
 *               COTRN01C.cbl (330, 9), COTRN02C.cbl (783, 18),
 *               COBIL00C.cbl (572, 16), CORPT00C.cbl (649, 10),
 *               COUSR00C.cbl (695, 16), COUSR01C.cbl (299, 9),
 *               COUSR02C.cbl (414, 11), COUSR03C.cbl (359, 11),
 *               COMEN01C.cbl (282, 7), COADM01C.cbl (268, 7)
 *               @ 7756d89
 * Source      : app/cbl/CSUTLDTC.cbl (157 lines, 2 paragraphs) +
 *               app/cpy/CSUTLDPY.cpy (14 Area-A labels) +
 *               app/cpy/CSUTLDWY.cpy @ 7756d89
 * Source      : app/cbl/CBSTM03B.CBL (230 lines; 4 DD names by 6
 *               operations) @ 7756d89
 * Source      : app/cpy/CSLKPCDY.cpy (1,318 lines, 5 88-level
 *               tables), COMEN02Y.cpy, COADM02Y.cpy, COCOM01Y.cpy,
 *               CSSTRPFY.cpy @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl (the universal FILE STATUS guard
 *               idiom and the four character status rendering)
 *               @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (the transaction to program map
 *               and the authorisation inventory) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L1-L21 (the banner convention
 *               this header reproduces) @ 7756d89
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
 * Root of the online business tier: the 21 service beans that carry every decision the CardDemo CICS screen
 * programs used to make, arranged into nine subpackages by the resource they act on.
 *
 * <p>This package holds no type. It is the <em>layer</em> document, and it exists because the four topics Rule 1
 * Clause E names are properties of the layer as a whole rather than of any one subpackage: one toolchain builds
 * all 21 beans, one configuration contract binds them, one exception hierarchy is thrown across them, and one
 * paragraph correspondence mandate constrains every method in them. Each of the nine subpackages carries its own
 * package documentation for the detail that belongs there, so this file <strong>summarises and points rather than
 * restating</strong> what a subpackage already says. Where the two ever disagree, the subpackage governs for its
 * own beans, because it sits next to the code it describes.
 *
 * <h2>What it does</h2>
 *
 * <p>The legacy online tier was 17 pseudo conversational COBOL programs driving 3270 terminals through a CICS
 * region: screen state lived in the COMMAREA, navigation was {@code EXEC CICS XCTL}, and layout came from BMS
 * mapsets. Every decision those programs made - which field to validate first, which message to emit, when to
 * write and when to refuse - now lives in exactly one bean in this layer. The controllers above translate HTTP
 * to method calls and back; the repositories below store; <strong>a service decides, and does nothing
 * else</strong>.
 *
 * <p>All line counts and paragraph counts below were measured directly against the frozen corpus at traceability
 * anchor {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} (short {@code 7756d89}), reading {@code app/cbl}
 * case insensitively - two members use an uppercase {@code .CBL} extension and a {@code *.cbl} glob drops them -
 * and stripping carriage returns from the five members that carry them, without which every line offset drifts.
 * A paragraph count is a count of Area-A labels.
 *
 * <table>
 *   <caption>The 21 service beans, their source programs, and the measured size of each source</caption>
 *   <thead>
 *     <tr>
 *       <th scope="col">Subpackage</th>
 *       <th scope="col">Bean</th>
 *       <th scope="col">Source</th>
 *       <th scope="col">Lines</th>
 *       <th scope="col">Paragraphs</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code auth}</td><td>{@code AuthenticationService}</td>
 *         <td>{@code app/cbl/COSGN00C.cbl}</td><td>260</td><td>6</td></tr>
 *     <tr><td>{@code account}</td><td>{@code AccountViewService}</td>
 *         <td>{@code app/cbl/COACTVWC.cbl}</td><td>941</td><td>38</td></tr>
 *     <tr><td>{@code account}</td><td>{@code AccountUpdateService}</td>
 *         <td>{@code app/cbl/COACTUPC.cbl}</td><td>4,236</td><td>88</td></tr>
 *     <tr><td>{@code card}</td><td>{@code CardListService}</td>
 *         <td>{@code app/cbl/COCRDLIC.cbl}</td><td>1,459</td><td>42</td></tr>
 *     <tr><td>{@code card}</td><td>{@code CardDetailService}</td>
 *         <td>{@code app/cbl/COCRDSLC.cbl}</td><td>887</td><td>37</td></tr>
 *     <tr><td>{@code card}</td><td>{@code CardUpdateService}</td>
 *         <td>{@code app/cbl/COCRDUPC.cbl}</td><td>1,560</td><td>48</td></tr>
 *     <tr><td>{@code transaction}</td><td>{@code TransactionListService}</td>
 *         <td>{@code app/cbl/COTRN00C.cbl}</td><td>699</td><td>16</td></tr>
 *     <tr><td>{@code transaction}</td><td>{@code TransactionDetailService}</td>
 *         <td>{@code app/cbl/COTRN01C.cbl}</td><td>330</td><td>9</td></tr>
 *     <tr><td>{@code transaction}</td><td>{@code TransactionAddService}</td>
 *         <td>{@code app/cbl/COTRN02C.cbl}</td><td>783</td><td>18</td></tr>
 *     <tr><td>{@code billing}</td><td>{@code BillPaymentService}</td>
 *         <td>{@code app/cbl/COBIL00C.cbl}</td><td>572</td><td>16</td></tr>
 *     <tr><td>{@code report}</td><td>{@code ReportSubmissionService}</td>
 *         <td>{@code app/cbl/CORPT00C.cbl}</td><td>649</td><td>10</td></tr>
 *     <tr><td>{@code admin}</td><td>{@code UserListService}</td>
 *         <td>{@code app/cbl/COUSR00C.cbl}</td><td>695</td><td>16</td></tr>
 *     <tr><td>{@code admin}</td><td>{@code UserAddService}</td>
 *         <td>{@code app/cbl/COUSR01C.cbl}</td><td>299</td><td>9</td></tr>
 *     <tr><td>{@code admin}</td><td>{@code UserUpdateService}</td>
 *         <td>{@code app/cbl/COUSR02C.cbl}</td><td>414</td><td>11</td></tr>
 *     <tr><td>{@code admin}</td><td>{@code UserDeleteService}</td>
 *         <td>{@code app/cbl/COUSR03C.cbl}</td><td>359</td><td>11</td></tr>
 *     <tr><td>{@code menu}</td><td>{@code MainMenuService}</td>
 *         <td>{@code app/cbl/COMEN01C.cbl} + {@code app/cpy/COMEN02Y.cpy}</td><td>282</td><td>7</td></tr>
 *     <tr><td>{@code menu}</td><td>{@code AdminMenuService}</td>
 *         <td>{@code app/cbl/COADM01C.cbl} + {@code app/cpy/COADM02Y.cpy}</td><td>268</td><td>7</td></tr>
 *     <tr><td>{@code shared}</td><td>{@code DateValidationService}</td>
 *         <td>{@code app/cbl/CSUTLDTC.cbl} + {@code app/cpy/CSUTLDPY.cpy} + {@code app/cpy/CSUTLDWY.cpy}</td>
 *         <td>157</td><td>2, plus 14 contributed by {@code CSUTLDPY.cpy}</td></tr>
 *     <tr><td>{@code shared}</td><td>{@code ValidationLookupService}</td>
 *         <td>{@code app/cpy/CSLKPCDY.cpy} (1,318 lines)</td><td>-</td><td>-</td></tr>
 *     <tr><td>{@code shared}</td><td>{@code FileStatusMapper}</td>
 *         <td>the universal COBOL {@code FILE STATUS} guard idiom</td><td>-</td><td>-</td></tr>
 *     <tr><td>{@code shared}</td><td>{@code FileService}</td>
 *         <td>{@code app/cbl/CBSTM03B.CBL}</td><td>230</td><td>14 procedure paragraphs of 15 Area-A
 *         labels</td></tr>
 *   </tbody>
 * </table>
 *
 * <h3>Why the bean count is 21 and not 20</h3>
 *
 * <p>Twenty beans are directly mandated: the 17 online program services, plus {@code DateValidationService},
 * {@code ValidationLookupService} and {@code FileStatusMapper}. The twenty-first, {@code FileService}, is
 * <strong>additive</strong>, and the reason is a piece of indirection that has no home in any of the twenty:
 * {@code app/cbl/CBSTM03A.CBL} reaches all of its datasets through {@code CALL 'CBSTM03B' USING WS-M03B-AREA},
 * selecting the target by a DD name carried in the call area rather than by opening a file directly. Something
 * has to own that call contract, and none of the other twenty is the right owner. Twenty mandated plus one
 * additive is 21.
 *
 * <h3>The nine subpackages, and there is no tenth</h3>
 *
 * <ul>
 *   <li>{@code auth} - 1 bean. Sign on. Upper cases <strong>both</strong> the identifier and the password before
 *       comparison, exactly as the source does, then verifies against a BCrypt hash and issues a token in place
 *       of populating a COMMAREA.</li>
 *   <li>{@code account} - 2 beans. View, and the dual dataset update that is the largest single translation in
 *       the migration at 4,236 source lines and 88 paragraphs.</li>
 *   <li>{@code card} - 3 beans. List at seven rows a page, single record detail, and a change detection
 *       update.</li>
 *   <li>{@code transaction} - 3 beans. List at ten rows a page, detail, and add with identifier generation by
 *       descending browse.</li>
 *   <li>{@code billing} - 1 bean. Bill payment, which always pays the <strong>whole</strong> balance and drives
 *       it to exactly zero; there is no partial payment in the source and none is invented.</li>
 *   <li>{@code report} - 1 bean. Report submission, which replaces an embedded job deck written to a transient
 *       data queue with a single typed message on an SQS FIFO queue.</li>
 *   <li>{@code admin} - 4 beans. User list at ten rows a page, add, update and delete.</li>
 *   <li>{@code menu} - 2 beans. The main and administrator option tables, bounded by their own count fields and
 *       gated by user type.</li>
 *   <li>{@code shared} - 4 beans. Date validation, lookup tables, file status translation and the file access
 *       call contract. Nothing here is a grab bag: each of the four replaces one identifiable legacy
 *       mechanism.</li>
 *   </ul>
 *
 * <h3>One CICS transaction has no bean here, and that is deliberate</h3>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines 18 transactions and 18 programs but only 17 mapsets. The eighteenth is
 * {@code CDV1}, declared by {@code DEFINE TRANSACTION(CDV1)} at {@code :L388}, whose program operand at
 * {@code :L390} names {@code COCRDSEC} - and <strong>{@code COCRDSEC} has no source file anywhere in the
 * repository</strong>. The name occurs at exactly two places, both inside the CSD itself: {@code :L211}, which is
 * {@code DEFINE PROGRAM(COCRDSEC)}, and {@code :L390}. There is nothing to translate, so <strong>no bean and no
 * endpoint is invented for it</strong>, and the online surface this layer backs is <strong>17 operations, not
 * 18</strong>. Three further programs are missing from the CSD for the opposite reason and are still represented
 * here: {@code CSUTLDTC} is statically called, and {@code CBSTM03A} and {@code CBSTM03B} are batch.
 *
 * <h3>Three collapse rules, which are also why no helper file may be added</h3>
 *
 * <p>Rule 1 Clause C asks for a consistent directory structure and no duplication. Three groups of legacy
 * artefacts each collapse to exactly one bean, and stating them here is what stops a fourth, fifth or sixth
 * file appearing to hold the same logic a second time.
 *
 * <ul>
 *   <li>{@code app/cbl/CSUTLDTC.cbl} together with its two work area copybooks {@code app/cpy/CSUTLDPY.cpy} and
 *       {@code app/cpy/CSUTLDWY.cpy} collapse to <strong>one</strong> {@code shared.DateValidationService}. The
 *       static {@code CALL} and both copybooks become one injected bean, not three types.</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL} collapses to <strong>one</strong> {@code shared.FileService} covering four
 *       DD names by six operations. The DD names are {@code TRNXFILE}, {@code XREFFILE}, {@code CUSTFILE} and
 *       {@code ACCTFILE}, dispatched by {@code EVALUATE LK-M03B-DD} at
 *       {@code app/cbl/CBSTM03B.CBL:L118-L128}; the operations are {@code 'O'} open, {@code 'C'} close,
 *       {@code 'R'} read, {@code 'K'} keyed read, {@code 'W'} write and {@code 'Z'} rewrite, declared as
 *       condition names on {@code WS-M03B-OPER} at {@code app/cbl/CBSTM03A.CBL:L71-L83}.</li>
 *   <li>The <strong>five</strong> 88-level tables of {@code app/cpy/CSLKPCDY.cpy} collapse to
 *       <strong>one</strong> {@code shared.ValidationLookupService} reading three classpath resources. The
 *       tables are {@code VALID-PHONE-AREA-CODE} at {@code :L30}, {@code VALID-GENERAL-PURP-CODE} at
 *       {@code :L521}, {@code VALID-EASY-RECOG-AREA-CODE} at {@code :L931}, {@code VALID-US-STATE-CODE} at
 *       {@code :L1013} and {@code VALID-US-STATE-ZIP-CD2-COMBO} at {@code :L1073}. They are held as
 *       <strong>data, never as a generated constants class</strong>: membership is preserved exactly while
 *       better than a thousand lines of Java literals are not.</li>
 *   </ul>
 *
 * <p>The consequence is a rule about this subtree rather than a preference. <strong>No helper, mapper, validator,
 * facade, utility, abstract base or constants class may be added here.</strong> Each would be a second place for
 * logic that already has exactly one owner, which is the duplication Clause C forbids, and each would put a
 * type in a package whose whole purpose is to hold none.
 *
 * <h3>Paragraph correspondence, and the industry guidance it deliberately overrides</h3>
 *
 * <p><strong>Every applicable COBOL source label maps one to one to a private Java method</strong>, and each such
 * method carries a Javadoc citation naming its source path, its label and its line. That includes
 * <strong>duplicate, empty, unreachable and defect paths</strong>, and <strong>labels are never
 * consolidated</strong> - not even a label with its own {@code -EXIT} partner. The corpus figure this layer draws
 * from is <strong>639 source labels across the 28 programs</strong>, being 553 paragraph style labels and 86
 * {@code SECTION} labels; the per program share owned by this layer is the paragraph column of the table above.
 *
 * <p>Two contributions come from copybooks rather than from the programs themselves, and both are easy to
 * mistake for a miscount.
 *
 * <ul>
 *   <li>Five of this layer's programs copy the <strong>procedural</strong> copybook {@code app/cpy/CSSTRPFY.cpy}
 *       into their procedure division, which contributes two real labels - {@code YYYY-STORE-PFKEY.} at
 *       {@code :L17} and {@code YYYY-STORE-PFKEY-EXIT.} at {@code :L80} - plus the {@code COPY 'CSSTRPFY'}
 *       statement line itself. That is why the mapped method count for {@code COACTUPC} ({@code COPY} at
 *       {@code :L4199}), {@code COACTVWC} ({@code :L913}), {@code COCRDLIC} ({@code :L1416}),
 *       {@code COCRDSLC} ({@code :L855}) and {@code COCRDUPC} ({@code :L1528}) each exceeds that program's own
 *       in-file label count by three.</li>
 *   <li>{@code app/cpy/CSUTLDPY.cpy} contributes <strong>fourteen</strong> Area-A paragraph labels:
 *       {@code EDIT-DATE-CCYYMMDD.} at {@code :L18}, {@code EDIT-YEAR-CCYY.} at {@code :L25},
 *       {@code EDIT-YEAR-CCYY-EXIT.} at {@code :L88}, {@code EDIT-MONTH.} at {@code :L91},
 *       {@code EDIT-MONTH-EXIT.} at {@code :L145}, {@code EDIT-DAY.} at {@code :L150},
 *       {@code EDIT-DAY-EXIT.} at {@code :L205}, {@code EDIT-DAY-MONTH-YEAR.} at {@code :L209},
 *       {@code EDIT-DAY-MONTH-YEAR-EXIT.} at {@code :L280}, {@code EDIT-DATE-LE.} at {@code :L284},
 *       {@code EDIT-DATE-LE-EXIT.} at {@code :L323}, {@code EDIT-DATE-CCYYMMDD-EXIT.} at {@code :L329},
 *       {@code EDIT-DATE-OF-BIRTH.} at {@code :L341} and {@code EDIT-DATE-OF-BIRTH-EXIT.} at {@code :L370}.
 *       {@code DateValidationService} therefore maps <strong>2 + 14 = 16</strong> labels, not 2.</li>
 *   </ul>
 *
 * <p>Industry guidance on COBOL modernisation warns against exactly this discipline, on the grounds that
 * transliterating {@code PERFORM} and {@code GO TO} structure into Java produces the unreadable dialect the
 * literature calls JOBOL. That guidance is real and the conflict is genuine, and it is
 * <strong>resolved in favour of parity</strong>, because behavioural parity is the acceptance contract and the
 * paragraph map has to stay mechanically provable. The readability concern is answered by two compensating
 * mechanisms rather than by restructuring: the <strong>source citing Javadoc on every method</strong>, which is
 * also the evidence mechanism Rule 1 Clause F requires and the artefact the scope coverage gate reads, and the
 * planned {@code TRACEABILITY_MATRIX.md}, which makes the correspondence navigable. Where the guidance can be
 * honoured without touching control flow it is honoured: naming is idiomatic, {@code java.math.BigDecimal}
 * replaces packed decimal, and framework mechanisms replace static linkage.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>There is one Maven module and one command. Everything below is a build <em>invariant</em>, durable because
 * it lives in the root {@code pom.xml}; measured results such as a coverage percentage or a test count are
 * deliberately not quoted here, because a number copied into a comment is wrong the moment the tree grows.
 *
 * <ul>
 *   <li><strong>Build and verify.</strong> {@code ./mvnw -B -ntp clean verify}. Unit tests alone are
 *       {@code ./mvnw -B -ntp test}. The vulnerability scan needs network access to the vulnerability feed and
 *       is slow on a cold cache, so it is suppressed for a fast loop with
 *       {@code -Ddependency-check.skip=true} - note the hyphen, and note that the switch suppresses
 *       <em>only</em> that scan.</li>
 *   <li><strong>Toolchain.</strong> Java <strong>25</strong> with {@code maven.compiler.release} set to 25 and
 *       <strong>no preview features</strong>, Maven <strong>3.9.11</strong> through the pinned wrapper, and
 *       parent {@code spring-boot-starter-parent:3.5.11}. {@code maven-enforcer-plugin:3.5.0} asserts the
 *       floors as {@code [25,)} and {@code [3.9.11,)}, so the build cannot silently run on a wrong
 *       toolchain.</li>
 *   <li><strong>Compilation is warning fatal.</strong> {@code maven-compiler-plugin:3.14.1} runs
 *       {@code -Xlint:all} and {@code -Werror} with {@code showWarnings}, {@code showDeprecation} and
 *       {@code failOnWarning}. A raw type, an unchecked cast, a deprecation notice, a switch fall through or a
 *       dangling documentation comment is a build failure, so every bean in this layer has to be warning clean
 *       rather than merely compiling. An unused import is <em>not</em> caught by the compiler, because
 *       {@code javac} 25 publishes no {@code unused} lint key; Clause B's prohibition on one is therefore
 *       enforced at review, and this file is the trivial case - it declares <strong>no import at all</strong>.</li>
 *   <li><strong>Documentation is gated too.</strong> Malformed Javadoc is not a compile failure, so it is caught
 *       by its own gate: {@code maven-javadoc-plugin:3.11.2} is bound at {@code verify} as the execution
 *       {@code doclint-gate}, running {@code javadoc-no-fork} with {@code doclint} set to {@code all},
 *       {@code failOnWarnings} true and {@code show} set to {@code private}. An unclosed {@code <p>}, an
 *       unescaped {@code &lt;}, a broken {@code @link} or a malformed table therefore fails the build, which is
 *       why the documentation in this layer is held to the same standard as its code.</li>
 *   <li><strong>Tests.</strong> {@code maven-surefire-plugin:3.5.4} runs the unit tier with the integration and
 *       end to end trees excluded by path and with alphabetical run order for determinism;
 *       {@code maven-failsafe-plugin:3.5.4} is bound to {@code verify} and runs the tiers that stand up
 *       PostgreSQL 16 and LocalStack through Testcontainers. Each bean in this layer has a unit test class of
 *       its own under {@code src/test/java/com/cardemo/unit/service}.</li>
 *   <li><strong>Coverage.</strong> {@code jacoco-maven-plugin:0.8.12} enforces an <strong>80 percent LINE</strong>
 *       covered ratio at {@code verify} with {@code haltOnFailure}, <strong>no core package exclusions and no
 *       getter only padding</strong>. That gate is the practical reason two of the constraints below are not
 *       stylistic: constructor injection and zero global mutable state are what make a 4,236 line translation
 *       testable at all. The threshold is a {@code pom.xml} property and must never be lowered there; a run that
 *       has to proceed below it does so on the command line only.</li>
 *   <li><strong>Security scan.</strong> {@code org.owasp:dependency-check-maven:12.1.0} fails the build on a
 *       finding at CVSS 7 or above, which is High and Critical.</li>
 *   <li><strong>Add no dependency.</strong> This layer introduces none, and specifically <strong>no
 *       Lombok</strong> and <strong>no standalone JWT library</strong> - not {@code jjwt}, not
 *       {@code java-jwt}, not an Auth0 artefact. Token handling goes through the OAuth2 resource server and the
 *       Nimbus decoder that the Spring Boot parent already manages, so nothing here needs a version pin of its
 *       own.</li>
 *   </ul>
 *
 * <p><strong>Host readings, measured rather than assumed.</strong> On 3 August 2026 this working tree measured
 * {@code java} at OpenJDK <strong>25.0.3</strong> (Temurin 25.0.3+9 LTS), {@code mvn} at Apache Maven
 * <strong>3.9.11</strong>, Docker Engine <strong>29.7.0</strong> and {@code docker compose}
 * <strong>v5.3.1</strong>, with {@code JAVA_HOME} unset in a non-login shell and therefore worth exporting
 * before a build. These are <em>readings, not requirements</em>: re-measure after a host change instead of
 * quoting them, and never assert a build or gate result that was not actually run. Where a step genuinely cannot
 * run, the correct report names the command attempted, the date, the tool versions and the exit status.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every property this layer consumes is bound through Spring configuration property binding. <strong>No bean
 * here reads {@code System.getenv}</strong>: Clause C asks that build and behaviour be deterministic and free of
 * environment specific assumptions, and a direct environment read is untestable, unbindable and invisible to
 * the fail fast machinery. The names below are spelled exactly as {@code src/main/resources/application.yml}
 * spells them; alternative spellings are not interchangeable.
 *
 * <p><strong>Pagination - three keys, and they are parity contracts rather than tunables.</strong> Each value is
 * a literal in a COBOL program, so changing one is a behaviour change and not a configuration change. What makes
 * this worth spelling out is that the three numbers arrive by three different mechanisms:
 *
 * <ul>
 *   <li>{@code carddemo.pagination.card-list-page-size} = <strong>7</strong>, bound with no default by
 *       {@code card.CardListService}. A declared <em>constant</em>:
 *       {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:L177-L178}.</li>
 *   <li>{@code carddemo.pagination.transaction-list-page-size} = <strong>10</strong>, bound by
 *       {@code transaction.TransactionListService}. A <em>loop bound</em>:
 *       {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10} at {@code app/cbl/COTRN00C.cbl:L290},
 *       with the backward path at {@code :L349}. The related fields at {@code :L65-L68} are the page number and
 *       next page flag, which are pagination <em>state</em> and not the size.</li>
 *   <li>{@code carddemo.pagination.user-list-page-size} = <strong>10</strong>, bound by
 *       {@code admin.UserListService}. A <em>table dimension</em>: {@code 02 USER-REC OCCURS 10 TIMES} at
 *       {@code app/cbl/COUSR00C.cbl:L57}.</li>
 *   </ul>
 *
 * <p>There is deliberately <strong>no fourth pagination key</strong>. The report's 20 lines per page is a batch
 * concern, held as a constant named {@code PAGE_SIZE} on
 * {@code com.cardemo.batch.processors.TransactionReportProcessor} and sourced from
 * {@code 05 WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at {@code app/cbl/CBTRN03C.cbl:L131-L132}, applied at
 * {@code :L282} through {@code FUNCTION MOD}. A {@code report-lines-per-page} key alongside the three above
 * would be bound by nothing while looking authoritative, which is worse than having none, because an operator
 * changing it would see neither an effect nor an error.
 *
 * <p><strong>Security.</strong> {@code carddemo.security.jwt.signing-key} resolves from
 * {@code ${JWT_SIGNING_KEY}} with <strong>no default, no example and no fallback</strong>, so an unresolvable
 * value fails fast at context refresh rather than starting with a weak key. Its two companions carry documented
 * defaults precisely because they are non-secret metadata: {@code carddemo.security.jwt.issuer} is
 * {@code ${JWT_ISSUER:carddemo}} and {@code carddemo.security.jwt.expiration-minutes} is
 * {@code ${JWT_EXPIRATION_MINUTES:30}}. {@code carddemo.security.bcrypt.strength} is <strong>10</strong>. Sign
 * on is the only unauthenticated operation, {@code /api/admin/*} is restricted to the administrator role, the
 * session policy is {@code STATELESS}, and there is no HTTP session and no CSRF state to hold.
 *
 * <p><strong>Messaging and object storage.</strong> {@code carddemo.aws.sqs.report-queue} resolves from
 * {@code ${CARDDEMO_SQS_REPORT_QUEUE}}, with {@code report-queue-logical-name} and
 * {@code report-message-group-id} both {@code carddemo-report-jobs}. The queue is FIFO and the message group
 * identifier is deterministic, which is what preserves the submission ordering the legacy transient data queue
 * gave for free. <strong>All traffic targets LocalStack, there are zero live credentials anywhere, and no code
 * path in this layer may reach a real AWS endpoint</strong> - the endpoint override exists only in the
 * {@code local} and {@code test} profiles, so a live fallback is structurally impossible rather than merely
 * discouraged.
 *
 * <p><strong>Persistence and batch.</strong> {@code spring.jpa.open-in-view} is {@code false}, so
 * <strong>there is no lazy loading outside a transaction</strong> and a service that needs an association must
 * fetch it deliberately. {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, so the
 * entity mapping and the Flyway schema cannot drift apart unnoticed. {@code spring.batch.job.enabled} is
 * {@code false}, so starting the application posts nothing; a job is launched explicitly.
 *
 * <p><strong>Lookup resources.</strong> {@code shared.ValidationLookupService} reads three classpath resources -
 * {@code validation/nanpa-area-codes.json}, {@code validation/us-state-codes.json} and
 * {@code validation/state-zip-prefixes.json}. They are <strong>owned by {@code src/main/resources}</strong>; this
 * layer only reads them, and must never generate a constants class from them.
 *
 * <p><strong>Financial precision, derived from the picture clauses rather than chosen.</strong> Three widths
 * differ from one another and the differences matter: account money fields are {@code S9(10)V99} over
 * {@code NUMERIC(12,2)}; <strong>{@code TRAN-AMT} and {@code TRAN-CAT-BAL} are {@code S9(09)V99} over
 * {@code NUMERIC(11,2)}</strong>; and <strong>{@code DIS-INT-RATE} is {@code S9(04)V99} over
 * {@code NUMERIC(6,2)}</strong>. Widening any of them silently changes what round trips.
 *
 * <p><strong>The two transaction timestamps are text, and that is not a missed modernisation.</strong>
 * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} and are modelled as {@code String} over
 * {@code CHAR(26)} - <strong>never {@link java.time.LocalDateTime}, {@link java.sql.Timestamp} or
 * {@link java.time.Instant}</strong>. Three mutually incompatible producers write these fields: a batch form, an
 * online form, and a pure pass through of whatever the input record already carried. Text is the only
 * representation that can hold all three without loss. A generated value is formatted to two fraction digits
 * followed by four literal zeros - not three digits and not nine - because any other precision yields a value of
 * the wrong length and every generated timestamp then differs from the legacy baseline.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Findings below carry a severity and a locator, per Rule 1 Clause F.
 *
 * <p><strong>The exception vocabulary.</strong> This layer throws only the nine types of
 * {@code com.cardemo.exception}: {@code CardDemoException} as the base, then {@code ValidationException},
 * {@code RecordNotFoundException}, {@code DuplicateRecordException}, {@code FileUnavailableException},
 * {@code ConcurrentUpdateException}, {@code DataIntegrityException}, {@code FileAccessException} and
 * {@code FatalProcessingException}. Nothing is swallowed and every one preserves its cause.
 *
 * <p><strong>The {@code FILE STATUS} translation, owned in exactly one place.</strong> Every open, read, write,
 * rewrite and close in the legacy corpus follows a single guard idiom, which is why one bean -
 * {@code shared.FileStatusMapper} - owns the whole translation and no other bean re-decides it:
 *
 * <ul>
 *   <li>{@code 00} success.</li>
 *   <li>{@code 10} end of file. This is <strong>loop termination, not an error</strong>; mapping it to an
 *       exception turns a normal completion into a failure.</li>
 *   <li>{@code 22} duplicate key, to {@code DuplicateRecordException}.</li>
 *   <li>{@code 23} record not found, to {@code RecordNotFoundException}.</li>
 *   <li>{@code 35} file unavailable, to {@code FileUnavailableException}.</li>
 *   <li>{@code 9x} a physical or logical I/O error, to {@code FileAccessException} carrying the four character
 *       expansion described below.</li>
 *   <li>anything else, to {@code FatalProcessingException} with <strong>abend code 999 and return code
 *       12</strong>. The contract is {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}, which
 *       displays {@code 'ABENDING PROGRAM'}, moves {@code 0 TO TIMING}, moves {@code 999 TO ABCODE} and calls
 *       {@code 'CEE3ABD'}.</li>
 *   </ul>
 *
 * <p><strong>{@code FILE STATUS IS: NNNN} is emitted verbatim, and the four letters are part of the text.</strong>
 * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} executes
 * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} on <strong>both</strong> branches of its numeric test. The
 * {@code NNNN} was a placeholder the author never substituted, so those four characters really are emitted and
 * the rendered status follows them - a not found status appears as {@code FILE STATUS IS: NNNN0023}. The first
 * branch, taken when {@code IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'}, copies byte one through and expands byte
 * two from a two byte binary field into three digits; the second moves {@code '0000'} and then places the two
 * status characters at offset three. {@code IO-STATUS-04} is {@code IO-STATUS-0401 PIC 9} plus
 * {@code IO-STATUS-0403 PIC 999} at {@code :L138-L140}, which is exactly four characters. The logging
 * configuration passes this message through byte for byte; <strong>no masking rule may match inside it and it
 * must never be reformatted</strong>, because boundary comparison diffs it against the legacy baseline. A
 * masking rule that reaches into this literal is a misconfiguration, not a safety improvement.
 *
 * <p><strong>Exactly three scoped exceptions exist to that mapping, and nothing else may treat a non-{@code 00}
 * status as success.</strong> They are the transaction category balance upsert, where {@code 23} is the accepted
 * create path; the first disclosure group read, where {@code 23} triggers the default group retry rather than an
 * error; and the file access call contract, where {@code CBSTM03B} returns {@code '04'} as a success alongside
 * {@code '00'}. That third one is not a generalisation: it is asserted at nine verified call sites in
 * {@code app/cbl/CBSTM03A.CBL}, each reading {@code IF WS-M03B-RC = '00' OR '04'}, at {@code :L736},
 * {@code :L748}, {@code :L771}, {@code :L789}, {@code :L807}, {@code :L862}, {@code :L879}, {@code :L895} and
 * {@code :L911}.
 *
 * <p><strong>An account update conflict is five distinguishable outcomes, not one generic conflict.</strong>
 * Collapsing them loses information the legacy screen displayed to the user. The condition names and their exact
 * literals are at {@code app/cbl/COACTUPC.cbl:L517-L524} - {@code 'Could not lock account record for update'},
 * {@code 'Could not lock customer record for update'},
 * {@code 'Record changed by some one else. Please review'} and {@code 'Update of record failed'} - and the state
 * markers at {@code :L659-L668} are {@code ACUP-SHOW-DETAILS 'S'}, {@code ACUP-CHANGES-NOT-OK 'E'},
 * {@code ACUP-CHANGES-OK-NOT-CONFIRMED 'N'}, {@code ACUP-CHANGES-OKAYED-AND-DONE 'C'},
 * {@code ACUP-CHANGES-OKAYED-LOCK-ERROR 'L'} and {@code ACUP-CHANGES-OKAYED-BUT-FAILED 'F'}. Two layers guard
 * the write, and the second is not optional: JPA {@code @Version} for the store level check,
 * <strong>plus</strong> an explicit field by field snapshot comparison, because the source compares business
 * field values rather than a version counter. A version counter detects <em>that</em> a row changed; the source
 * detects <em>which fields</em> changed, which is why a concurrent write that restored a field to its original
 * value passes the legacy check and fails a version check.
 *
 * <p><strong>Blocker, reproduced deliberately: a customer lock failure is reported as success.</strong> At
 * {@code app/cbl/COACTUPC.cbl:L2606-L2615}, the {@code EVALUATE TRUE} that follows
 * {@code PERFORM 9600-WRITE-PROCESSING THRU 9600-WRITE-PROCESSING-EXIT} tests only
 * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}, {@code LOCKED-BUT-UPDATE-FAILED},
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} and {@code WHEN OTHER}.
 * <strong>{@code COULD-NOT-LOCK-CUST-FOR-UPDATE} is never tested</strong>, so a failure to read the customer
 * record for update falls through {@code WHEN OTHER} and sets {@code ACUP-CHANGES-OKAYED-AND-DONE} - the top
 * level outcome the user sees is success even though nothing was written. Parity is the contract, so this is
 * reproduced rather than repaired, with the internal outcome kept distinguishable so a diagnostic can still tell
 * the two apart. It is owed an entry in the planned {@code DECISION_LOG.md}. <strong>Symptom:</strong> an update
 * reports success and the customer record is unchanged. <strong>Remedy:</strong> none - this is the source
 * behaviour; consult the internal outcome rather than the reported one.
 *
 * <p><strong>High, and the specification prose is wrong here: the monthly report covers the FULL calendar
 * month.</strong> At {@code app/cbl/CORPT00C.cbl:L213-L238} the start day is forced to the literal {@code '01'}
 * ({@code :L219}) and the end is computed by rolling to the first of the next month ({@code :L223-L227}) and then
 * subtracting a day with
 * {@code COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)}
 * ({@code :L229-L230}), which is the last day of the current month. Yearly is {@code yyyy-01-01} to
 * {@code yyyy-12-31} ({@code :L239-L255}). Section 0.7.5.2 of the technical specification calls the monthly range
 * month to date; <strong>the source governs and the prose is the defect</strong>. The discrepancy is owed an
 * entry in the planned {@code DECISION_LOG.md}. <strong>Symptom:</strong> a monthly report whose end date is
 * today rather than the month end. <strong>Remedy:</strong> compute the month end, not the current day.
 *
 * <p><strong>Low, retained for parity: an unreachable paragraph pair in the card package.</strong>
 * {@code app/cbl/COCRDSLC.cbl} declares {@code 9150-GETCARD-BYACCT.} at {@code :L779} and
 * {@code 9150-GETCARD-BYACCT-EXIT.} at {@code :L810}, and a repository wide search for the name returns exactly
 * those two lines - both labels, with no {@code PERFORM}, no {@code GO TO} and no {@code THRU} reference anywhere
 * in {@code app/cbl}. The only paragraph that could have reached the range, {@code 9000-READ-DATA.} at
 * {@code :L726}, performs {@code 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT} and nothing else.
 * Both labels are therefore mapped to empty private methods in {@code card.CardDetailService}, each carrying an
 * explicit intentional no-op marker and its proof of unreachability, and each owed an entry in the planned
 * {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}. <strong>No JaCoCo exclusion may be added to
 * compensate</strong>: the Javadoc carries the fidelity and the empty body carries the no-op, and putting a
 * repository call in either method would manufacture a phantom caller - dead code of a worse kind than the no-op
 * it replaced.
 *
 * <p><strong>Preserved quirks a maintainer will otherwise try to fix.</strong> Each is source behaviour at its own
 * locator, and correcting any of them breaks parity.
 *
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl} declares {@code 0000-MAIN-EXIT.} <strong>twice</strong>, at {@code :L408}
 *       and again at {@code :L411}. Both are mapped, because labels are never consolidated.</li>
 *   <li>{@code app/cbl/COTRN01C.cbl:L275} issues {@code UPDATE} on the read of a read <em>only</em> detail path,
 *       taking a lock the path never uses.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl:L111-L112} triggers the save from <strong>PF3</strong>
 *       ({@code WHEN DFHPF3 / PERFORM UPDATE-USER-INFO}), which is the key a user reaches for to leave without
 *       saving.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl:L332} emits the wrong verb on a delete failure:
 *       {@code 'Unable to Update User...'}.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} contains <strong>zero</strong> references to {@code CDEMO-USER-ID}, which is
 *       the proof that no self delete guard exists. <strong>None may be added.</strong> A signed on administrator
 *       can delete their own record, and that is the behaviour.</li>
 *   <li>{@code app/cbl/COADM01C.cbl:L149-L152} has the option name lines <strong>commented out</strong>, so the
 *       administrator menu's coming soon message omits the option name, while
 *       {@code app/cbl/COMEN01C.cbl:L159-L163} includes it. The two menus differ, and the difference is
 *       kept.</li>
 *   <li>{@code app/cbl/CORPT00C.cbl:L515} declares the misspelled paragraph {@code WIRTE-JOBSUB-TDQ.}, and
 *       {@code app/cbl/COACTUPC.cbl} uses the misspelled field name {@code ACCT-EXPIRAION-DATE}. Both
 *       misspellings are part of the field and label contract, so both are cited as written.</li>
 *   <li>The change detection comparison at {@code app/cbl/COACTUPC.cbl:L4109-L4193} is deliberately
 *       <strong>asymmetric</strong> about case, and normalising it in either direction changes which updates are
 *       accepted. The account group identifier is compared through {@code FUNCTION LOWER-CASE} on both sides
 *       ({@code :L4139}); the customer name, address, state, country and government identifier fields are
 *       compared through {@code FUNCTION UPPER-CASE} on both sides ({@code :L4152-L4173}); and the remaining
 *       fields are compared with no case function at all. Dates are compared as three separate substrings, never
 *       as whole strings, and the date of birth uses <em>different offsets on each side</em> - the live record at
 *       {@code (1:4)}, {@code (6:2)} and {@code (9:2)} against the snapshot at {@code (1:4)}, {@code (5:2)} and
 *       {@code (7:2)} ({@code :L4174-L4179}) - because the live value carries separators and the snapshot does
 *       not. A whole string comparison of the two would report a change on <strong>every</strong> request and the
 *       endpoint would never accept an update at all.</li>
 *   </ul>
 *
 * <p><strong>Medium, now reconciled: the {@code CBSTM03B} paragraph count of 14 versus 15.</strong> Both figures
 * are right, under different counting conventions, and the delta is a single label. An Area-A scan of
 * {@code app/cbl/CBSTM03B.CBL} finds <strong>15</strong> labels, of which <strong>14 are procedure division
 * paragraphs</strong> - {@code 0000-START.} {@code :L116}, {@code 9999-GOBACK.} {@code :L130},
 * {@code 1000-TRNXFILE-PROC.} {@code :L133}, {@code 1900-EXIT.} {@code :L151}, {@code 1999-EXIT.} {@code :L154},
 * {@code 2000-XREFFILE-PROC.} {@code :L157}, {@code 2900-EXIT.} {@code :L175}, {@code 2999-EXIT.} {@code :L178},
 * {@code 3000-CUSTFILE-PROC.} {@code :L181}, {@code 3900-EXIT.} {@code :L200}, {@code 3999-EXIT.} {@code :L203},
 * {@code 4000-ACCTFILE-PROC.} {@code :L206}, {@code 4900-EXIT.} {@code :L225} and {@code 4999-EXIT.}
 * {@code :L228}. The fifteenth is <strong>{@code FILE-CONTROL.} at {@code :L30}</strong>, an environment division
 * paragraph inside the input-output section - an Area-A label, but not a procedure paragraph, which is the entire
 * delta. <strong>Remediation:</strong> always state the convention alongside the count.
 * {@code FileService} maps the 14 procedure paragraphs; 15 is the Area-A total.
 *
 * <p><strong>Medium, and genuinely unresolved: the account predicate counting convention is
 * Not available.</strong> The account block of {@code 9700-CHECK-CHANGE-IN-REC} at
 * {@code app/cbl/COACTUPC.cbl:L4109-L4193} carries <strong>16 comparison clauses over 10 logical fields</strong>:
 * six scalar comparisons at {@code :L4115}, {@code :L4117}, {@code :L4119}, {@code :L4121}, {@code :L4123} and
 * {@code :L4125}; three dates each split into three substring comparisons at {@code :L4127-L4129},
 * {@code :L4131-L4133} and {@code :L4135-L4137}; and the lower cased group identifier at {@code :L4139}. The
 * technical specification says twelve account predicates, and twelve is not reconstructible from either measured
 * figure under any convention this analysis could identify. <strong>What is needed:</strong> the counting
 * convention behind the figure twelve, or a decision to restate it as 16 clauses over 10 fields.
 * <strong>What must not happen:</strong> resolving the gap by consolidating labels or by dropping predicates -
 * every one of the 16 clauses is compared, and the implementation compares all 16.
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These bind every bean in all nine subpackages. Each exists because a specific, plausible implementation
 * would violate it.
 *
 * <ul>
 *   <li><strong>Decimal arithmetic only.</strong> {@code java.math.BigDecimal} at the precision the picture
 *       clause dictates, with an explicit {@code RoundingMode.HALF_EVEN}. Equality is tested with
 *       {@code compareTo} and <strong>never</strong> {@code equals}, because {@code equals} on
 *       {@code BigDecimal} distinguishes {@code 1.0} from {@code 1.00}. There is <strong>zero {@code float} and
 *       zero {@code double} in any financial field</strong>, and <strong>a source formula is never algebraically
 *       normalised</strong> - a rewrite that looks equivalent changes the rounding, and rounding is the
 *       output.</li>
 *   <li><strong>Determinism, per Clause A.</strong> {@code Locale.ROOT} on <strong>every</strong> case operation,
 *       so an upper case in a Turkish locale cannot change which user signs on. A deterministic ordering on every
 *       paged query, or page two is a lottery. No reliance on hash iteration order, and none on the default
 *       charset, locale or time zone.</li>
 *   <li><strong>Statelessness, and only identity crosses the boundary.</strong> Token claims replace COMMAREA
 *       identity and nothing more: {@code CDEMO-USER-ID PIC X(08)} becomes the subject and
 *       {@code CDEMO-USER-TYPE PIC X(01)} becomes the role, the latter carrying
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'} in
 *       {@code app/cpy/COCOM01Y.cpy}. There is <strong>no HTTP session, no retained screen or re-entry state and
 *       no CSRF state</strong>. {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM},
 *       {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT}, {@code CDEMO-LAST-MAP} and
 *       {@code CDEMO-LAST-MAPSET} have <strong>no counterpart</strong>, because routing is URL based and no
 *       screen state is kept. Note precisely, because it is easy to attribute wrongly:
 *       <strong>{@code COCOM01Y.cpy} declares no page number and no next page flag at all</strong> - pagination
 *       state lives in each program's own working storage, for instance
 *       {@code 05 WS-PAGE-NUM PIC S9(04) COMP VALUE ZEROS} at {@code app/cbl/COUSR00C.cbl:L54} - and it moves to
 *       request parameters and response metadata.</li>
 *   <li><strong>Constructor injection only, per Clause B.</strong> No field {@code @Autowired}, no setter
 *       injection, no service locator. <strong>Zero static mutable fields.</strong> A legacy working storage flag
 *       becomes a <strong>method local</strong> variable and never a bean field: the COBOL programs were single
 *       threaded by construction and these beans are singletons serving concurrent requests, so a flag promoted
 *       to a field is a race, not a translation.</li>
 *   <li><strong>Error handling that preserves context, per Clause B.</strong> Every {@code catch} rethrows a
 *       typed {@code com.cardemo.exception} subtype <strong>carrying the original as its cause</strong>. There is
 *       no empty catch and no bare {@code catch (Exception e)} that discards what it caught.</li>
 *   <li><strong>Observability, per Clause A - and this layer consumes it rather than defining it.</strong> A bean
 *       logs through SLF4J, and 19 of the 21 hold a logger of their own; the records come out as structured JSON
 *       because {@code logback-spring.xml} renders the {@code traceId}, {@code spanId} and {@code correlationId}
 *       trio from MDC, where {@code traceId} and {@code spanId} are placed by Micrometer tracing bridged to
 *       OpenTelemetry and {@code correlationId} is placed by
 *       {@code com.cardemo.observability.CorrelationIdFilter}. That filter is what replaces the CICS transaction
 *       identifier as the thread of identity through a request, since the legacy corpus had no other per-request
 *       handle and no instrumentation beyond {@code DISPLAY} to SYSOUT. The four metric instruments are declared
 *       as constants on {@code com.cardemo.observability.MetricsConfig}, and <strong>no bean here increments
 *       one</strong>: metering the online path is that package's concern, and a counter created in a service
 *       would be a second owner of an instrument that already has one. Measurable behaviour at this layer is
 *       therefore the log record and the typed exception, both of which name the COBOL field and the widths
 *       involved rather than the value.</li>
 *   <li><strong>Performance, per Clause A - constraints, and deliberately no target.</strong> The legacy corpus
 *       publishes no service level objective of any kind, so <strong>none is invented here</strong>: the
 *       performance gate records a <em>measured baseline</em> and never an improvement target, and no throughput
 *       or latency figure appears anywhere in this layer's documentation. What this layer does owe is the absence
 *       of obvious inefficiency, and three constraints deliver it. Every list path is bounded by its parity page
 *       size, so no query is unbounded. {@code spring.jpa.open-in-view} being {@code false} forces a fetch to be
 *       deliberate rather than triggered lazily during serialisation, which is what stops an N+1 appearing in the
 *       response writer where no test would see it. And the sort work that the legacy job stream handed to DFSORT
 *       is done in process with a {@link java.util.Comparator}, never by spawning a utility. Where a tradeoff is
 *       made against these, Clause A requires it be justified in writing rather than assumed - which is why the
 *       one deliberate departure from the source's own algorithm, the removal of a fixed in-memory ceiling, is
 *       labelled a deviation in the package that makes it and not quietly absorbed here.</li>
 *   <li><strong>Nothing sensitive is logged or serialised, per Clause D.</strong> Credentials, presented
 *       passwords, stored BCrypt hashes, tokens, signing keys, authorisation headers, social security numbers,
 *       card numbers, phone numbers, government identifiers, dates of birth and electronic funds account
 *       identifiers never appear in a message, a log line or a response body. A diagnostic names the COBOL field
 *       and the widths involved, never the content. The masking rules are the second line of defence, not the
 *       first, and they cannot reach a value that was emitted without a label.</li>
 *   <li><strong>Risky patterns are excluded by construction, per Clause D.</strong> No {@code Runtime.exec} and
 *       no {@code ProcessBuilder} anywhere - which is also the rule that forbids shelling out to a sort or a
 *       dataset utility, since the sort replacement is a {@link java.util.Comparator} and never an external
 *       process. No Java deserialisation of untrusted input. No string concatenated JPQL or SQL. No EBCDIC
 *       parsing, because {@code app/data/EBCDIC/**} is codepage reference and is never read by the build or at
 *       run time. No path to a live cloud endpoint.</li>
 *   <li><strong>Least privilege, per Clause D.</strong> The {@code admin} beans are reachable only through
 *       administrator restricted routes. The four batch only datasets {@code TCATBALF}, {@code DISCGRP},
 *       {@code TRANCATG} and {@code TRANTYPE} have <strong>no {@code DEFINE FILE} entry in
 *       {@code app/csd/CARDDEMO.CSD}</strong>, which is the evidence that the legacy online region could not
 *       reach them; accordingly <strong>no bean in this layer gives them an online surface</strong>.</li>
 *   <li><strong>Configuration is consumed, never redeclared, per Clause C.</strong>
 *       {@code com.cardemo.config.WebConfig} owns the two numeric converters - a strict digits only parser for
 *       identifiers and card numbers, and a currency aware parser for amounts, which the source distinguishes
 *       deliberately - along with the message converters. {@code SecurityConfig} owns the filter chain, the
 *       BCrypt encoder at strength 10, the {@code 'A'} and {@code 'U'} to role mapping and the symmetric Nimbus
 *       decoder. {@code JpaConfig} owns entity scanning and transaction management. No bean here declares
 *       {@code @EnableWebSecurity} or {@code @EnableTransactionManagement}, because a second declaration of an
 *       owned concern is the duplication Clause C forbids.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing in this layer reads {@code app/} at build or run
 *       time. Those members are cited as evidence and must survive byte for byte; the same applies to
 *       {@code samples/}.</li>
 *   </ul>
 *
 * <h3>The one rule conflict in this layer, and how it is resolved</h3>
 *
 * <p>Rule 1 Clause B forbids dead code. The parity mandate requires that a declared or reachable no-op be
 * preserved, so that the paragraph map stays provable. These collide, and in this subtree they collide at one
 * identifiable place: the unreachable {@code 9150-GETCARD-BYACCT} and {@code 9150-GETCARD-BYACCT-EXIT} pair of
 * {@code app/cbl/COCRDSLC.cbl:L779} and {@code :L810}, mapped to two empty private methods in
 * {@code card.CardDetailService}.
 *
 * <p><strong>Parity governs, and Clause B is satisfied by a different mechanism.</strong> Read precisely, the
 * clause forbids dead code and deferred work <em>without an owner or a tracking reference</em>. Each retained
 * artefact here has both: it is cited at its own declaration with the COBOL locator and a proof of
 * unreachability, it carries an explicit intentional no-op marker in the body, and it is owed an entry in the
 * planned {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}. It is a documented faithful reproduction of
 * something that exists in the system of record, not abandoned residue. Deleting it would produce a layer that
 * is marginally tidier and demonstrably less traceable - failing a stated acceptance criterion to satisfy a
 * stylistic one. The authoritative statement of how this register is bounded across the whole tree lives in the
 * root {@code com.cardemo} package documentation, and the marker at each declaration governs over any summary,
 * including this one.
 */
package com.cardemo.service;
