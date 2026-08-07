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
 * Source      : app/cbl/COSGN00C.cbl (260 lines, 6 mapped methods).
 *               Every second figure below is the mapped method count
 *               the table in this file publishes - CSSTRPFY
 *               expansions included where a program copies it - and
 *               is deliberately not a raw Area-A paragraph tally,
 *               because a COPY line is provenance rather than a
 *               mapped method:
 *               COACTVWC.cbl (941, 37), COACTUPC.cbl (4,236, 87),
 *               COCRDLIC.cbl (1,459, 41), COCRDSLC.cbl (887, 36),
 *               COCRDUPC.cbl (1,560, 47), COTRN00C.cbl (699, 16),
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
 * <p>This package holds no type. It is the <em>layer</em> document, because the four topics Rule 1 Clause E
 * names are properties of the layer as a whole: one toolchain builds all 21 beans, one configuration contract
 * binds them, one exception hierarchy is thrown across them, and one paragraph-correspondence mandate
 * constrains every method in them. Each subpackage carries its own documentation for the detail that belongs
 * there, so this file <strong>summarises and points rather than restating</strong>; where the two disagree the
 * subpackage governs for its own beans, because it sits next to the code it describes.
 *
 * <h2>What it does</h2>
 *
 * <p>The legacy online tier was 17 pseudo-conversational COBOL programs driving 3270 terminals through a CICS
 * region: screen state lived in the COMMAREA, navigation was {@code EXEC CICS XCTL}, and layout came from BMS
 * mapsets. Every decision those programs made - which field to validate first, which message to emit, when to
 * write and when to refuse - now lives in exactly one bean in this layer. The controllers above translate HTTP
 * to method calls and back; the repositories below store; <strong>a service decides, and does nothing
 * else</strong>.
 *
 * <p>All line counts and label counts below were measured directly against the frozen corpus at traceability
 * anchor {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} (short {@code 7756d89}), reading {@code app/cbl}
 * case insensitively - two members use an uppercase {@code .CBL} extension and a {@code *.cbl} glob drops them -
 * and stripping carriage returns from the five members that carry them, without which every line offset drifts.
 *
 * <p><strong>The last column is the mapped-method count, and it is a different measurement from the structural
 * Area-A census.</strong> The two are defined and reconciled under the paragraph-correspondence heading below;
 * the short form is that a mapped method is a label that carries executable statements - a label that appears
 * <em>after</em> {@code PROCEDURE DIVISION} - plus the labels a procedural copybook expands into that program,
 * whereas the structural census additionally counts the {@code IDENTIFICATION} and {@code ENVIRONMENT} division
 * entries such as {@code PROGRAM-ID.}, {@code DATE-WRITTEN.}, {@code DATE-COMPILED.} and {@code FILE-CONTROL.},
 * which are declarations and become no method anywhere. Neither figure is an estimate of the other.
 *
 * <table>
 *   <caption>The 21 service beans, their source programs, and the measured size of each source</caption>
 *   <thead>
 *     <tr>
 *       <th scope="col">Subpackage</th>
 *       <th scope="col">Bean</th>
 *       <th scope="col">Source</th>
 *       <th scope="col">Lines</th>
 *       <th scope="col">Mapped methods</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code auth}</td><td>{@code AuthenticationService}</td>
 *         <td>{@code app/cbl/COSGN00C.cbl}</td><td>260</td><td>6</td></tr>
 *     <tr><td>{@code account}</td><td>{@code AccountViewService}</td>
 *         <td>{@code app/cbl/COACTVWC.cbl}</td><td>941</td><td>37, being 35 of its own plus 2 expanded from
 *         {@code app/cpy/CSSTRPFY.cpy}</td></tr>
 *     <tr><td>{@code account}</td><td>{@code AccountUpdateService}</td>
 *         <td>{@code app/cbl/COACTUPC.cbl}</td><td>4,236</td><td>87, being 85 of its own plus 2 expanded from
 *         {@code app/cpy/CSSTRPFY.cpy}</td></tr>
 *     <tr><td>{@code card}</td><td>{@code CardListService}</td>
 *         <td>{@code app/cbl/COCRDLIC.cbl}</td><td>1,459</td><td>41, being 39 of its own plus 2 expanded from
 *         {@code app/cpy/CSSTRPFY.cpy}</td></tr>
 *     <tr><td>{@code card}</td><td>{@code CardDetailService}</td>
 *         <td>{@code app/cbl/COCRDSLC.cbl}</td><td>887</td><td>36, being 34 of its own plus 2 expanded from
 *         {@code app/cpy/CSSTRPFY.cpy}</td></tr>
 *     <tr><td>{@code card}</td><td>{@code CardUpdateService}</td>
 *         <td>{@code app/cbl/COCRDUPC.cbl}</td><td>1,560</td><td>47, being 45 of its own plus 2 expanded from
 *         {@code app/cpy/CSSTRPFY.cpy}</td></tr>
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
 *         <td>157</td><td>16, being 2 of its own plus 14 expanded from {@code app/cpy/CSUTLDPY.cpy}</td></tr>
 *     <tr><td>{@code shared}</td><td>{@code ValidationLookupService}</td>
 *         <td>{@code app/cpy/CSLKPCDY.cpy} (1,318 lines)</td><td>-</td><td>-</td></tr>
 *     <tr><td>{@code shared}</td><td>{@code FileStatusMapper}</td>
 *         <td>the universal COBOL {@code FILE STATUS} guard idiom</td><td>-</td><td>-</td></tr>
 *     <tr><td>{@code shared}</td><td>{@code FileService}</td>
 *         <td>{@code app/cbl/CBSTM03B.CBL}</td><td>230</td><td>14</td></tr>
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
 *   <li>{@code auth} - 1 bean. Sign-on. Upper-cases <strong>both</strong> the identifier and the password before
 *       comparison, exactly as the source does, then verifies against a BCrypt hash and issues a token in place
 *       of populating a COMMAREA.</li>
 *   <li>{@code account} - 2 beans. View, and the dual-dataset update that is the largest single translation in
 *       the migration at 4,236 source lines.</li>
 *   <li>{@code card} - 3 beans. List at seven rows a page, single-record detail, and a change-detection
 *       update.</li>
 *   <li>{@code transaction} - 3 beans. List at ten rows a page, detail, and add with identifier generation by
 *       descending browse.</li>
 *   <li>{@code billing} - 1 bean. Bill payment, which always pays the <strong>whole</strong> balance and drives
 *       it to exactly zero; there is no partial payment in the source and none is invented.</li>
 *   <li>{@code report} - 1 bean. Report submission, replacing an embedded job deck written to a transient data
 *       queue with one typed message on an SQS FIFO queue.</li>
 *   <li>{@code admin} - 4 beans. User list at ten rows a page, add, update and delete.</li>
 *   <li>{@code menu} - 2 beans. The main and administrator option tables, bounded by their own count fields and
 *       gated by user type.</li>
 *   <li>{@code shared} - 4 beans. Date validation, lookup tables, file-status translation and the file-access
 *       call contract - each replacing one identifiable legacy mechanism.</li>
 *   </ul>
 *
 * <p><strong>Why the count is 21 and not 20.</strong> Twenty are directly mandated: the 17 online program
 * services plus {@code DateValidationService}, {@code ValidationLookupService} and {@code FileStatusMapper}. The
 * twenty-first, {@code FileService}, is additive, because {@code app/cbl/CBSTM03A.CBL} reaches all of its
 * datasets through {@code CALL 'CBSTM03B' USING WS-M03B-AREA}, selecting the target by a DD name carried in the
 * call area, and that indirection has no home in any of the twenty.
 *
 * <p><strong>One CICS transaction has no bean here, deliberately.</strong>
 * {@code app/csd/CARDDEMO.CSD} defines 18 transactions and 18 programs but only 17 mapsets. The eighteenth is
 * {@code CDV1} at {@code :L388}, whose program operand at {@code :L390} names {@code COCRDSEC} - and
 * {@code COCRDSEC} has no source file anywhere in the repository, occurring only at {@code :L211} and
 * {@code :L390} inside the CSD itself. There is nothing to translate, so no bean and no endpoint is invented and
 * the online surface is <strong>17 operations, not 18</strong>. Three programs are absent from the CSD for the
 * opposite reason and are still represented: {@code CSUTLDTC} is statically called, and {@code CBSTM03A} and
 * {@code CBSTM03B} are batch.
 *
 * <h3>Three collapse rules, which are also why no helper file may be added</h3>
 *
 * <ul>
 *   <li>{@code app/cbl/CSUTLDTC.cbl} together with its two work area copybooks {@code app/cpy/CSUTLDPY.cpy} and
 *       {@code app/cpy/CSUTLDWY.cpy} collapse to <strong>one</strong> {@code shared.DateValidationService}. The
 *       static {@code CALL} and both copybooks become one injected bean, not three types.</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL} collapses to <strong>one</strong> {@code shared.FileService}. The DD names
 *       are {@code TRNXFILE}, {@code XREFFILE}, {@code CUSTFILE} and {@code ACCTFILE}, dispatched by
 *       {@code EVALUATE LK-M03B-DD} at {@code app/cbl/CBSTM03B.CBL:L118-L128}; six operations are
 *       <em>declared</em> as condition names on {@code WS-M03B-OPER} at
 *       {@code app/cbl/CBSTM03A.CBL:L71-L83} - {@code 'O'} open, {@code 'C'} close, {@code 'R'} read,
 *       {@code 'K'} keyed read, {@code 'W'} write and {@code 'Z'} rewrite.
 *       <p><strong>Four by six is the declared shape and not the implemented one: only 12 of the 24 cells
 *       exist.</strong> Each DD handler tests exactly three operations - open, close, and the single read form
 *       its access mode allows, sequential {@code 'R'} for {@code TRNXFILE} and {@code XREFFILE} and keyed
 *       {@code 'K'} for {@code CUSTFILE} and {@code ACCTFILE} - so {@code IF M03B-WRITE} and
 *       {@code IF M03B-REWRITE} appear nowhere in the subprogram. The root cause is that every dataset is
 *       opened {@code OPEN INPUT}, so a write and a rewrite are unreachable in the system of record.
 *       {@code FileService} therefore admits only
 *       {@code EnumSet.of(OPEN, CLOSE, dd.accessMode().readOperation())} per DD and <strong>rejects the other
 *       twelve combinations rather than inventing them</strong>: a write, a rewrite, or the wrong read form for
 *       a DD fails with an unimplemented-operation reason. Reading the matrix as fully populated is the single
 *       most likely way to add behaviour the frozen source does not have.</p></li>
 *   <li>The <strong>five</strong> 88-level tables of {@code app/cpy/CSLKPCDY.cpy} collapse to
 *       <strong>one</strong> {@code shared.ValidationLookupService} reading three classpath resources. The
 *       tables are {@code VALID-PHONE-AREA-CODE} at {@code :L30}, {@code VALID-GENERAL-PURP-CODE} at
 *       {@code :L521}, {@code VALID-EASY-RECOG-AREA-CODE} at {@code :L931}, {@code VALID-US-STATE-CODE} at
 *       {@code :L1013} and {@code VALID-US-STATE-ZIP-CD2-COMBO} at {@code :L1073}. They are held as
 *       <strong>data, never as a generated constants class</strong>: membership is preserved exactly while
 *       better than a thousand lines of Java literals are not.</li>
 *   </ul>
 *
 * <p>The consequence is a rule about this subtree. <strong>No helper, mapper, validator, facade, utility,
 * abstract base or constants class may be added here.</strong> Each would be a second place for logic that
 * already has exactly one owner, and each would put a type in a package whose purpose is to hold none.
 *
 * <h3>Paragraph correspondence</h3>
 *
 * <p><strong>Every applicable COBOL source label maps one to one to a private Java method</strong>, and each such
 * method carries a Javadoc citation naming its source path, its label and its line. That includes
 * <strong>duplicate, empty, unreachable and defect paths</strong>, and <strong>labels are never
 * consolidated</strong> - not even a label with its own {@code -EXIT} partner.
 *
 * <p><strong>Two corpus figures are published here, under distinct names, because they measure different
 * things and only one of them is a method count.</strong> Quoting either as the other overstates the mapping,
 * which is why both are stated with their composition rather than as a single headline number.
 *
 * <dl>
 *   <dt><strong>Structural source census: 639 Area-A entries across the 28 programs</strong></dt>
 *   <dd>Being 553 paragraph style entries and 86 {@code SECTION} entries. This is a <em>lexical</em> inventory
 *       of everything written in Area A, so it counts the {@code IDENTIFICATION} and {@code ENVIRONMENT}
 *       division entries alongside the procedure labels - {@code PROGRAM-ID.}, {@code DATE-WRITTEN.} and
 *       {@code DATE-COMPILED.} in the online programs, {@code FILE-CONTROL.} in the batch ones. Those are
 *       declarations: they carry no statement, they are performed by nothing, and <strong>none of them becomes
 *       a Java method in any package.</strong> The figure is retained because it is the census that proves no
 *       source line was overlooked, and for no other purpose.</dd>
 *   <dt><strong>Executable mapping inventory: 552 labels</strong></dt>
 *   <dd>Being 528 in-program procedure labels - every Area-A label that appears after
 *       {@code PROCEDURE DIVISION} - plus 10 expanded from {@code app/cpy/CSSTRPFY.cpy} and 14 from
 *       {@code app/cpy/CSUTLDPY.cpy}. <strong>This is the figure that corresponds one to one to private Java
 *       methods</strong>, and it is the figure the mapped-method column of the table above is drawn from and
 *       the one the scope-coverage gate reads. It is smaller than the structural census by exactly the 111
 *       division-header entries and {@code SECTION} entries that declare rather than execute, and larger by
 *       the 24 labels the two procedural copybooks expand into their host programs.</dd>
 * </dl>
 *
 * <p>Two contributions come from copybooks rather than from the programs themselves, and both are easy to
 * mistake for a miscount.
 *
 * <ul>
 *   <li>Five of this layer's programs copy the <strong>procedural</strong> copybook {@code app/cpy/CSSTRPFY.cpy}
 *       into their procedure division, which contributes exactly <strong>two</strong> labels each -
 *       {@code YYYY-STORE-PFKEY.} at {@code :L17} and {@code YYYY-STORE-PFKEY-EXIT.} at {@code :L80}, and
 *       nothing else. That is why the mapped-method count for {@code COACTUPC} ({@code COPY} at
 *       {@code :L4199}), {@code COACTVWC} ({@code :L913}), {@code COCRDLIC} ({@code :L1416}),
 *       {@code COCRDSLC} ({@code :L855}) and {@code COCRDUPC} ({@code :L1528}) exceeds that program's own
 *       procedure-label count by <strong>two, not by three</strong>. <strong>The {@code COPY 'CSSTRPFY'}
 *       statement line is provenance, not a method.</strong> It is a compiler directive that names where the
 *       two labels come from; counting the directive as a third mapped item would claim a Java method that
 *       does not and must not exist, and inflate this layer's share of the corpus by one per program. Five
 *       programs times two labels is the 10 the executable inventory above records.</li>
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
 * <p>One program in this layer shows the two measurements diverging inside a single row, which is why the row
 * states both. {@code app/cbl/CBSTM03B.CBL} carries <strong>15 Area-A entries of which 14 are procedure
 * paragraphs</strong>: the fifteenth is {@code FILE-CONTROL.} at {@code :L30}, an {@code ENVIRONMENT} division
 * entry that declares the four {@code SELECT} clauses and executes nothing. {@code FileService} therefore maps
 * 14 methods, and the 15 is retained only as the structural census for that member. Keeping the two apart in
 * the row is deliberate: collapsing them would either invent a method for a declaration or lose the census.
 *
 * <p>A consequence for the {@code TRACEABILITY_MATRIX.md}, recorded here because this package is where
 * the counts are published: <strong>the two figures must be derived separately and labelled separately in that
 * document too.</strong> A single hardcoded total cannot serve both, and the first time one is quoted as the
 * other the matrix stops being mechanically provable against the corpus. Both are reproducible by inspection -
 * the structural census by counting Area-A entries, the executable inventory by counting those that follow
 * {@code PROCEDURE DIVISION} and adding the two procedural copybooks' expansions.
 *
 * <p>Industry guidance on COBOL modernisation warns against exactly this discipline, on the grounds that
 * transliterating {@code PERFORM} and {@code GO TO} structure into Java produces an unreadable dialect. The
 * conflict is genuine and is <strong>resolved in favour of parity</strong>, because behavioural parity is the
 * acceptance contract and the paragraph map must stay mechanically provable. The readability concern is answered
 * by the source-citing Javadoc on every method rather than by restructuring, and where the guidance can be
 * honoured without touching control flow it is: naming is idiomatic, {@code java.math.BigDecimal} replaces
 * packed decimal, and framework mechanisms replace static linkage.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Nothing in this layer runs on its own: it is compiled and packaged with the single deployable artefact.
 * {@code ./mvnw -B -ntp clean verify} from the repository root compiles it under {@code -Xlint:all -Werror},
 * runs the unit tier in {@code src/test/java/com/cardemo/unit/service}, the integration tier against
 * Testcontainers PostgreSQL 16 and LocalStack, and enforces the coverage floor. {@code docker compose up -d}
 * supplies the runtime substrate. No test may live inside this package, which holds production classes only.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>No bean in this layer reads a property directly, constructs a client or names an endpoint. Everything
 * arrives by constructor injection, and the values behind it are owned by
 * {@code src/main/resources/application.yml} and its three profile siblings. The page sizes - seven for the card
 * list, ten for the transaction and user lists - are <strong>parity constants, not configuration</strong>, and
 * are declared as constants beside the beans that use them so that no property can change them. The signing key,
 * the object-store and queue endpoints, the BCrypt strength and the token lifetime are all resolved from the
 * environment in every profile with no committed default.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p><strong>The exception vocabulary.</strong> This layer throws only the nine types of
 * {@code com.cardemo.exception}: {@code CardDemoException} as the base, then {@code ValidationException},
 * {@code RecordNotFoundException}, {@code DuplicateRecordException}, {@code FileUnavailableException},
 * {@code ConcurrentUpdateException}, {@code DataIntegrityException}, {@code FileAccessException} and
 * {@code FatalProcessingException}. Nothing is swallowed and every one preserves its cause.
 *
 * <p><strong>The {@code FILE STATUS} translation is owned in exactly one place</strong> -
 * {@code shared.FileStatusMapper} - because every open, read, write, rewrite and close in the corpus follows a
 * single guard idiom: {@code 00} success; {@code 10} end of file, which is <strong>loop termination, not an
 * error</strong>; {@code 22} to {@code DuplicateRecordException}; {@code 23} to
 * {@code RecordNotFoundException}; {@code 35} to {@code FileUnavailableException}; {@code 9x} to
 * {@code FileAccessException} carrying the four-character expansion; anything else to
 * {@code FatalProcessingException} with <strong>abend code 999 and return code 12</strong>, per
 * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}. Two sites accept {@code 23} as a normal
 * control path and the file-service call sites accept {@code 04} as success, so classification is not
 * context-free.
 *
 * <p><strong>{@code FILE STATUS IS: NNNN} is emitted verbatim, and the four letters are part of the text.</strong>
 * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} displays
 * {@code 'FILE STATUS IS: NNNN'} followed by {@code IO-STATUS-04} on both branches, so a not-found status appears
 * as {@code FILE STATUS IS: NNNN0023}. {@code IO-STATUS-04} is {@code PIC 9} plus {@code PIC 999} at
 * {@code :L138-L140} - exactly four characters. The message passes through the logging configuration byte for
 * byte: no masking rule may match inside it and it must never be reformatted, because boundary comparison diffs
 * it against the legacy baseline.
 *
 * <p><strong>Three parity behaviours that read like defects and are not.</strong>
 *
 * <ul>
 *   <li><strong>An account update can report success while the customer row is unchanged.</strong>
 *       {@code app/cbl/COACTUPC.cbl} never tests {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}, so a failure to read
 *       the customer record for update falls through {@code WHEN OTHER} and sets
 *       {@code ACUP-CHANGES-OKAYED-AND-DONE}. The behaviour is reproduced rather than repaired, with the
 *       internal outcome kept distinguishable so a diagnostic can still tell the two apart. There is no remedy
 *       to apply: consult the internal outcome rather than the reported one.</li>
 *   <li><strong>The monthly report covers the full calendar month.</strong> At
 *       {@code app/cbl/CORPT00C.cbl:L213-L238} the start day is forced to {@code '01'} ({@code :L219}) and the
 *       end is computed by rolling to the first of the next month ({@code :L223-L227}) and subtracting a day
 *       with {@code COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)}
 *       ({@code :L229-L230}), which is the last day of the current month. Yearly is {@code yyyy-01-01} to
 *       {@code yyyy-12-31} ({@code :L239-L255}). A monthly report whose end date is today rather than the month
 *       end is the defect.</li>
 *   <li><strong>Two empty private methods in {@code card.CardDetailService} are intentional.</strong>
 *       {@code app/cbl/COCRDSLC.cbl} declares {@code 9150-GETCARD-BYACCT.} at {@code :L779} and
 *       {@code 9150-GETCARD-BYACCT-EXIT.} at {@code :L810}, and a repository-wide search returns exactly those
 *       two lines - both labels, with no {@code PERFORM}, {@code GO TO} or {@code THRU} reference anywhere in
 *       {@code app/cbl}. The only paragraph that could have reached the range, {@code 9000-READ-DATA.} at
 *       {@code :L726}, performs {@code 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT} and nothing
 *       else. Both labels map to empty private methods carrying an explicit intentional no-op marker and the
 *       proof of unreachability. <strong>No JaCoCo exclusion may be added to compensate</strong>, and putting a
 *       repository call in either method would manufacture a phantom caller - dead code of a worse kind than the
 *       no-op. Rule 1 Clause B forbids dead code <em>without an owner or a tracking reference</em>; these have
 *       both, at their own declarations, which is where the governing statement lives.</li>
 *   </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These bind every bean in all nine subpackages, and each exists because a plausible implementation would
 * violate it.
 *
 * <ul>
 *   <li><strong>Decimal arithmetic only.</strong> {@code java.math.BigDecimal} at the precision the picture
 *       clause dictates, with an explicit {@code RoundingMode.HALF_EVEN}. Equality is tested with
 *       {@code compareTo} and <strong>never</strong> {@code equals}, which distinguishes {@code 1.0} from
 *       {@code 1.00}. There is <strong>zero {@code float} and zero {@code double} in any financial field</strong>,
 *       and a source formula is <strong>never algebraically normalised</strong> - a rewrite that looks equivalent
 *       changes the rounding, and rounding is the output.</li>
 *   <li><strong>Determinism.</strong> {@code Locale.ROOT} on every case operation, so an upper-case in a Turkish
 *       locale cannot change which user signs on. A deterministic ordering on every paged query, or page two is a
 *       lottery. No reliance on hash iteration order, the default charset, the default locale or the default time
 *       zone.</li>
 *   <li><strong>Statelessness, and only identity crosses the boundary.</strong> Token claims replace COMMAREA
 *       identity and nothing more: {@code CDEMO-USER-ID PIC X(08)} becomes the subject and
 *       {@code CDEMO-USER-TYPE PIC X(01)} the role. There is no HTTP session, no retained screen or re-entry
 *       state and no CSRF state; {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID},
 *       {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT},
 *       {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} have no counterpart. Note precisely, because it is
 *       easy to attribute wrongly: <strong>{@code COCOM01Y.cpy} declares no page number and no next-page flag at
 *       all</strong> - pagination state lives in each program's own working storage, for instance
 *       {@code 05 WS-PAGE-NUM PIC S9(04) COMP VALUE ZEROS} at {@code app/cbl/COUSR00C.cbl:L54} - and it moves to
 *       request parameters and response metadata.</li>
 *   <li><strong>Constructor injection only.</strong> No field {@code @Autowired}, no setter injection, no service
 *       locator, and <strong>zero static mutable fields</strong>. A legacy working-storage flag becomes a
 *       <strong>method-local</strong> variable and never a bean field: the COBOL programs were single-threaded by
 *       construction and these beans are singletons serving concurrent requests, so a flag promoted to a field is
 *       a race, not a translation.</li>
 *   <li><strong>Error handling that preserves context.</strong> Every {@code catch} rethrows a typed
 *       {@code com.cardemo.exception} subtype carrying the original as its cause, and no credential, hash, token,
 *       social security number, government identifier or full card number reaches a message, a log field or a
 *       metric tag.</li>
 *   </ul>
 */
package com.cardemo.service;
