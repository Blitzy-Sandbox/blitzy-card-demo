/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.card
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (card transactions))
 * Function    : The card boundary's decisions: the seven-row list of
 *               COCRDLIC, the single-record detail of COCRDSLC, and the
 *               change-detection update of COCRDUPC. Note that the card
 *               record carries both a card number and a verification
 *               value, so nothing here may render a record image.
 * Source      : app/cbl/COCRDLIC.cbl (1,459 lines, 39 own / 41 mapped paragraph labels; 7 rows per page at :L177) @ 7756d89
 * Source      : app/cbl/COCRDSLC.cbl (887 lines, 34 own / 36 mapped paragraph labels; single-record detail) @ 7756d89
 * Source      : app/cbl/COCRDUPC.cbl (1,560 lines, 45 own / 47 mapped paragraph labels; change-detection update) @ 7756d89
 * Source      : app/cpy/CVACT02Y.cpy (150-byte card layout; card number bytes 1-16, verification value 28-30) @ 7756d89
 * Source      : app/cpy-bms/COCRDLI.CPY (45 input fields), COCRDSL.CPY (15), COCRDUP.CPY (17) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L202 (key length 16, record length 150), :L281-L284 (the alternate index:
 *               KEYLEN 11, RKP 5, AXRKP 16) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE FILE(CARDDAT) and (CARDAIX); transactions CCLI, CCDL, CCUP) @ 7756d89
 * Source      : app/cbl/COCRDSLC.cbl:L779-L810 (9150-GETCARD-BYACCT - confirmed dead, no PERFORM anywhere in app/cbl)
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
 * The card services: list, detail and update over the card cluster.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.service.card.CardListService} - {@code app/cbl/COCRDLIC.cbl}, 1,459 lines.
 *       <strong>Seven rows a page</strong> ({@code app/cbl/COCRDLIC.cbl:L177}), with the filter-by-account and
 *       filter-by-card paths preserved as distinct routes rather than merged into one predicate. The seven-row
 *       table is why {@code app/cpy-bms/COCRDLI.CPY} declares 45 input fields.</li>
 *   <li>{@link com.cardemo.service.card.CardDetailService} - {@code app/cbl/COCRDSLC.cbl}, 887 lines.
 *       Single-record retrieval with the source's validation order intact.</li>
 *   <li>{@link com.cardemo.service.card.CardUpdateService} - {@code app/cbl/COCRDUPC.cbl}, 1,560 lines. A
 *       change-detection comparison mirroring the account update pattern: the decision to write is made by
 *       comparing business field values against a snapshot, not by comparing a version counter, so the request
 *       payload carries the snapshot and two layers of concurrency control result.</li>
 *   </ul>
 *
 * <h3>The alternate index, and why it is a derived finder rather than a second table</h3>
 *
 * <p>{@code app/catlg/LISTCAT.txt:L281-L284} records the card cluster's alternate index as
 * {@code KEYLEN 11, RKP 5, AXRKP 16} - the alternate key sits at <strong>byte 16 of the base record</strong> and
 * is the account identifier. In the legacy system that index was a separate physical object with its own path,
 * declared to CICS as {@code CARDAIX}; here it is a derived finder over the same table backed by a
 * <strong>non-unique</strong> B-tree index, because one account holds many cards. Making it unique would reject
 * legitimate data.
 *
 * <h3>What must never be rendered</h3>
 *
 * <p>{@code app/cpy/CVACT02Y.cpy} places the card number at bytes 1-16 and the card verification value at bytes
 * 28-30. Neither may appear in a log line, an exception message, a payload beyond what the screen showed, or a
 * {@code toString()}. Rule 1 Clause D forbids it outright, and masking in
 * {@code src/main/resources/logback-spring.xml} cannot save an unlabelled record image - which is precisely why
 * {@code com.cardemo.batch.readers.CardReader} emits an identifier-only projection rather than a record image
 * and reads the account identifier explicitly rather than through the entity's own {@code toString()}. The same
 * discipline binds this package: when a diagnostic must name a card, it names a masked form.
 *
 * <h3>One confirmed dead paragraph, which is not this package's exemption</h3>
 *
 * <p>{@code app/cbl/COCRDSLC.cbl:L779-L810} declares {@code 9150-GETCARD-BYACCT}, and
 * <strong>no {@code PERFORM 9150} exists anywhere in {@code app/cbl}</strong> - it is confirmed dead in the
 * source, not merely unreferenced by its own program. It is therefore <em>not</em> translated, and no method in
 * this package corresponds to it. That distinguishes it from a retained reachable no-op: unreachable source code
 * is dropped, reachable source code that does nothing is kept and marked. No declaration in this package carries
 * an intentional-no-op marker.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Page size 7 is a parity contract</strong>, from {@code app/cbl/COCRDLIC.cbl:L177}. It is not
 *       configurable, differs deliberately from the transaction and user lists' 10, and is unrelated to the batch
 *       reader fetch sizes under {@code carddemo.batch.*}. Browse position moves to a request parameter and the
 *       next-page flag to response metadata, because the exchange is stateless.</li>
 *   <li>Key length 16 and record length 150 ({@code app/catlg/LISTCAT.txt:L202}), realised in
 *       {@code src/main/resources/db/migration/V1__create_schema.sql}, with the account-based index in
 *       {@code V2__create_indexes.sql}.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile, so a column or nullability mismatch
 *       against the migration fails context startup rather than degrading into a wrong query.
 *       {@code spring.jpa.open-in-view: false}.</li>
 *   <li>Field widths in the request and response payloads come from {@code app/cpy-bms/COCRDLI.CPY},
 *       {@code COCRDSL.CPY} and {@code COCRDUP.CPY} exactly. Widening a field because a Java type makes it
 *       convenient is a parity break that no functional test catches.</li>
 *   <li>A {@code java.time.Clock} is injected where a rendered date or time is needed, published once as
 *       {@code Clock.systemDefaultZone()} - region-local, because the legacy region rendered local civil
 *       time.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: the card list returns ten rows a page.</strong> Cause: the page size was unified with
 *       the transaction and user lists. <em>Remediation:</em> restore 7; the difference is in the source.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: a full card number or a verification value appears in a log line, a message or a
 *       payload.</strong> Cause: a diagnostic quoted a value, or a record image was rendered.
 *       <em>Remediation:</em> mask it, and read individual fields rather than a whole record.
 *       <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a card update always reports a conflict.</strong> Cause: the snapshot comparison -
 *       most often a whole-string comparison where the source compares components, or a case function applied
 *       where the source applies none. <em>Remediation:</em> compare exactly as the source does, field by field.
 *       <strong>Severity: Blocker</strong> - the endpoint becomes unusable.</p></li>
 *   <li><p><strong>Symptom: an account with several cards returns one, or the finder fails on a duplicate
 *       key.</strong> Cause: the account-based index was made unique. <em>Remediation:</em> it must be
 *       non-unique; one account holds many cards. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: filtering by account and by card return inconsistent results.</strong> Cause: the two
 *       paths were merged into one predicate. <em>Remediation:</em> keep them distinct, as
 *       {@code app/cbl/COCRDLIC.cbl} does. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: a payload accepts more characters than the screen allowed, or truncates
 *       silently.</strong> Cause: the payload type drifted from its symbolic map. <em>Remediation:</em> restore
 *       the declared width and assert it. <strong>Severity: High</strong> - it corrupts the fixed-width boundary
 *       downstream.</p></li>
 *   <li><p><strong>Symptom: a mapping error at startup naming the card table.</strong> Cause:
 *       {@code ddl-auto: validate} found a disagreement with the migration. <em>Remediation:</em> treat the
 *       migration as authoritative and align the mapping; never switch {@code ddl-auto} to {@code update} to
 *       silence it. <strong>Severity: Blocker.</strong></p></li>
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
 *       {@code CardListServiceTest}, {@code CardDetailServiceTest} and {@code CardUpdateServiceTest} all exist.
 *       The assertions that matter, as distinct from mechanical coverage: page size exactly 7; the two filter
 *       paths behaving independently; the account-based finder returning every card of an account; the update's
 *       snapshot comparison per field; and that no card number or verification value reaches any message, payload
 *       or log event unmasked.</li>
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
package com.cardemo.service.card;
