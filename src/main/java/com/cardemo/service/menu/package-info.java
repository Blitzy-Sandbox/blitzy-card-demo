/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.menu
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (menu dispatch))
 * Function    : Menu option resolution and dispatch. Two services that
 *               bound an option index, gate it by user type and route
 *               it - replacing EXEC CICS XCTL with URL navigation. The
 *               admin menu additionally guards a placeholder program
 *               entry, which is preserved.
 * Source      : app/cbl/COMEN01C.cbl (282 lines, 7 paragraphs; main menu dispatch) @ 7756d89
 * Source      : app/cbl/COADM01C.cbl (268 lines, 7 paragraphs; admin menu dispatch) @ 7756d89
 * Source      : app/cbl/COMEN01C.cbl:L149-L150 (identity MOVEs commented out), :L152-L155 (XCTL with COMMAREA) @
 *               7756d89
 * Source      : app/cpy/COMEN02Y.cpy (10 populated menu slots, bounded by its own count field) @ 7756d89
 * Source      : app/cpy/COADM02Y.cpy (4 populated admin slots) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L26-L28 (CDEMO-USER-TYPE 'A'/'U' - the user-type gate) @ 7756d89
 * Source      : app/cpy-bms/COMEN01.CPY (20 input fields), COADM01.CPY (20) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE TRANSACTION(CM00) and (CA00)) @ 7756d89
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
 * The two menu services: option resolution, the user-type gate, and what happens to navigation when there is no
 * transfer of control left to perform.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.service.menu.MainMenuService} - {@code app/cbl/COMEN01C.cbl}, 282 lines, over the ten
 *       populated slots of {@code app/cpy/COMEN02Y.cpy}. An option bounds check, the user-type gate, then dispatch
 *       by option index.</li>
 *   <li>{@link com.cardemo.service.menu.AdminMenuService} - {@code app/cbl/COADM01C.cbl}, 268 lines, over the four
 *       populated slots of {@code app/cpy/COADM02Y.cpy}. The same bounds check, plus the placeholder-program
 *       guard.</li>
 *   </ul>
 *
 * <h3>The option table is bounded by its own count field, not by its array size</h3>
 *
 * <p>Both copybooks declare an array larger than the number of slots actually populated, with a count field naming
 * how many are live - ten in {@code COMEN02Y}, four in {@code COADM02Y}. The bounds check reads the
 * <strong>count</strong>, not the array length, so an option beyond the populated set is refused with the source's
 * own message rather than resolving to an empty slot. Deriving the bound from the array size instead would accept
 * options the legacy screen rejected.
 *
 * <h3>The user-type gate is part of the menu, not only of the endpoint</h3>
 *
 * <p>Each slot carries the user type permitted to select it ({@code app/cpy/COCOM01Y.cpy:L26-L28}, values
 * {@code 'A'} and {@code 'U'}). The source therefore refuses an option twice over: once when the menu is built and
 * again when the option is selected. Both refusals are reproduced, and neither substitutes for the endpoint
 * authorisation that {@code com.cardemo.config.SecurityConfig} owns - that is a third, independent layer. Removing
 * the in-menu gate would present a standard user with options they cannot use, which is a visible behaviour
 * change even though the endpoint would still refuse them.
 *
 * <h3>What dispatch becomes, and what it loses</h3>
 *
 * <p>{@code app/cbl/COMEN01C.cbl:L152-L155} is the canonical {@code EXEC CICS XCTL} naming
 * {@code COMMAREA(CARDDEMO-COMMAREA)} - the legacy system's whole navigation mechanism. Here an option resolves to
 * a URL, and the identity that travelled in the control block travels in a bearer token instead. Two of the
 * COMMAREA's field groups therefore have <strong>no counterpart at all</strong>: the from- and to-transaction and
 * program names, and the enter-versus-re-enter context flag. A menu service that retained either would be
 * reintroducing the COMMAREA under another name.
 *
 * <p>One source detail is worth recording because it looks like an omission: the identity {@code MOVE} statements
 * at {@code app/cbl/COMEN01C.cbl:L149-L150} are <strong>commented out in the source</strong>. They are therefore
 * not translated, and no method here corresponds to them.
 *
 * <h3>The placeholder-program guard is preserved</h3>
 *
 * <p>The admin menu carries a slot whose target program is a placeholder rather than a real program, and the source
 * guards against selecting it with its own message. That guard is <strong>reproduced</strong>, not removed: a menu
 * that silently accepted the option and then failed elsewhere would move the failure away from where the operator
 * could understand it.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Option counts, labels and permitted user types come from {@code app/cpy/COMEN02Y.cpy} and
 *       {@code app/cpy/COADM02Y.cpy} exactly - ten and four live slots respectively - and are
 *       <strong>not configurable</strong>. They are data in the source, so they are data here, and a slot's label
 *       is compared byte for byte by the parity gate.</li>
 *   <li>Field widths in the response come from {@code app/cpy-bms/COMEN01.CPY} and {@code COADM01.CPY}, 20 input
 *       fields each.</li>
 *   <li>No pagination applies: both menus fit one screen in the source and one response here.</li>
 *   <li>Endpoint authorisation is declared in {@code com.cardemo.config.SecurityConfig} from the CSD and the two
 *       user-type values. The in-menu gate described above is additional to it, never a replacement.</li>
 *   <li>This package reads no repository and holds no state. Both services are stateless and neither needs a
 *       {@code java.time.Clock}.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: an option beyond the populated set resolves to an empty entry instead of being
 *       refused.</strong> Cause: the bound came from the array size rather than the count field.
 *       <em>Remediation:</em> read the count field. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a standard user is offered administrative options.</strong> Cause: the in-menu
 *       user-type gate was dropped on the assumption that the endpoint would refuse them.
 *       <em>Remediation:</em> restore it; the source refuses twice. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: selecting the placeholder option produces an obscure downstream failure.</strong>
 *       Cause: the placeholder guard was removed. <em>Remediation:</em> restore it with the source's own message.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: a menu label differs from the baseline.</strong> Cause: a label was reworded or
 *       re-cased. <em>Remediation:</em> restore it verbatim from the copybook; labels are compared byte for byte.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: navigation state appears in a response or is remembered between calls.</strong>
 *       Cause: a COMMAREA routing field was translated rather than dropped. <em>Remediation:</em> remove it;
 *       navigation is URL-based. <strong>Severity: High</strong> - it reintroduces server-side conversation
 *       state.</p></li>
 *   <li><p><strong>Symptom: an option index is off by one against the baseline.</strong> Cause: zero-based
 *       indexing. <em>Remediation:</em> the source's option numbers are as displayed; keep the mapping explicit
 *       rather than arithmetic. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: an option count changed after a copybook was re-read.</strong> Cause: counting
 *       declared slots rather than populated ones. <em>Remediation:</em> ten and four are the populated figures.
 *       <strong>Severity: Low</strong>, but it invalidates the bounds check.</p></li>
 *   </ol>
 *
 * <h2>How to run, build and test</h2>
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify} from the repository root. The wrapper pins Maven
 *       3.9.11, {@code maven-enforcer-plugin:3.5.0} floors the toolchain at Java {@code [25,)}, compilation is
 *       at {@code release} 25 with no preview features, and {@code -Xlint:all}, {@code -Werror} and
 *       {@code failOnWarning} together make any warning attributed to this package a
 *       <strong>build failure</strong>.</li>
 *   <li><strong>Run.</strong> These are Spring beans and are never invoked directly; a controller or a batch
 *       step calls them. A manual exercise needs the compose stack up - {@code docker compose up -d} - and the
 *       environment scoped to that one command rather than exported into the shell:
 *       {@code ( set -a; . ./.env; set +a; <command> )}. The parentheses confine the values to the subshell
 *       instead of leaving every later child inheriting them. {@code JWT_SIGNING_KEY} has no default and
 *       startup fails without it by design.</li>
 *   <li><strong>Test.</strong> Tests belong in {@code src/test/java/com/cardemo/unit/service}.
 *       {@code MainMenuServiceTest}, {@code MainMenuServiceCoverageTest}, {@code AdminMenuServiceTest},
 *       {@code AdminMenuServiceCoverageTest} and the shared {@code MenuServiceTestSupport} all exist, and
 *       {@code MenuOptionCopybookTest} asserts the option tables against the frozen copybooks. The assertions that
 *       matter: exactly ten and exactly four live slots; an option one beyond the count refused; the user-type gate
 *       refusing at both build and select time; the placeholder guard firing; and every label byte-identical to the
 *       copybook.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an <strong>80 percent LINE</strong> floor on the merged
 *       bundle at {@code verify} with {@code haltOnFailure} and no exclusions for this package. Coverage must
 *       come from assertions on behaviour; exercising a method to move the number is not acceptable. This file
 *       is documentation only and contributes no executable lines.</li>
 *   <li><strong>Toolchain actually present, measured 3 August 2026</strong> at commit {@code 2e087c4}: OpenJDK
 *       and {@code javac} 25.0.3, Apache Maven 3.9.11 from the pinned wrapper, Docker Engine 29.7.0 with
 *       {@code docker compose} v5.3.1. These are readings, not requirements - re-measure after a host change
 *       rather than quoting them.</li>
 *   </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li><strong>One private method per COBOL paragraph, never consolidated</strong>, each carrying a Javadoc
 *       citation to its source label. That correspondence is what makes the scope-coverage gate provable by
 *       inspection rather than by assertion.</li>
 *   <li><strong>No HTTP concern and no persistence concern.</strong> A service decides; the controller
 *       translates and the repository stores. A service that built a response status or wrote SQL would be in
 *       the wrong layer.</li>
 *   <li><strong>No {@code float} or {@code double} on any financial path</strong>, and equality by
 *       {@code compareTo} rather than {@code equals}, because {@code equals} distinguishes {@code 1.0} from
 *       {@code 1.00}.</li>
 *   <li><strong>Every file status is translated by its single owner</strong>,
 *       {@code com.cardemo.service.shared.FileStatusMapper}, and never re-decided here. Nothing is swallowed
 *       and every exception preserves its cause.</li>
 *   <li><strong>No value in a message or a log line.</strong> A diagnostic names the COBOL field and the
 *       widths involved, never the content - Rule 1 Clause D, and the masking rules in
 *       {@code logback-spring.xml} cannot reach an unlabelled value.</li>
 *   <li><strong>No declaration in this package carries an intentional-no-op marker</strong>, the per-artefact
 *       form in which a retained-for-parity artefact is justified, so Rule 1 Clause B binds this package at
 *       full strength with no exemption.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing here reads {@code app/} at build or run time;
 *       those files are cited as evidence and must survive byte for byte.</li>
 *   </ul>
 */
package com.cardemo.service.menu;
