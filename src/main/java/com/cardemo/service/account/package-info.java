/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.account
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (account transactions))
 * Function    : The account boundary's decisions: the read chain of
 *               COACTVWC, and the seven-step dual-dataset write of
 *               COACTUPC with its snapshot comparison, its deliberate
 *               case asymmetry and its asymmetric rollback. This is the
 *               largest program in the corpus and the one whose obvious
 *               mechanical translation is wrong.
 * Source      : app/cbl/COACTVWC.cbl (941 lines, 35 own / 37 mapped paragraph labels) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl (4,236 lines, 85 own / 87 mapped paragraph labels) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L3888-L4105 (9600-WRITE-PROCESSING, the seven steps in fixed order) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L669-L756 (9700-CHECK-CHANGE-IN-REC, twelve account predicates plus the customer
 *               set) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L4079-L4102 (rollback on the customer rewrite only) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L517-L523, :L667 (the five distinguishable outcome flags) @ 7756d89
 * Source      : app/cpy/CVACT01Y.cpy (300-byte account layout), app/cpy/CVCUS01Y.cpy (500-byte customer layout) @
 *               7756d89
 * Source      : app/cpy/CSMSG02Y.cpy - internally CABENDD.CPY, the abend work areas @ 7756d89
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
 * The account services: one read chain and one write, of which the write is the hardest translation target in
 * the online corpus.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.service.account.AccountViewService} - {@code app/cbl/COACTVWC.cbl}, 941 lines. The
 *       cross-reference, account and customer lookup chain, in that order, with typed exceptions replacing the
 *       source's response-code branching.</li>
 *   <li>{@link com.cardemo.service.account.AccountUpdateService} - {@code app/cbl/COACTUPC.cbl}, 4,236 lines
 *       across 85 own paragraph labels, 87 mapped. A dual-dataset write with change detection.</li>
 *   </ul>
 *
 * <h3>The seven-step write sequence, whose order is the behaviour</h3>
 *
 * <p>{@code 9600-WRITE-PROCESSING} at {@code app/cbl/COACTUPC.cbl:L3888} proceeds in a fixed order that must be
 * preserved exactly: read the account for update, branching to the exit on a non-normal response; read the
 * customer for update, branching on its own distinct flag; perform the change-detection comparison and abandon
 * the write if the data changed; initialise the update images and move each new field into place; rewrite the
 * account, branching on failure <strong>with no rollback</strong>; rewrite the customer, branching on failure
 * <strong>with an explicit rollback</strong>; exit.
 *
 * <h3>Why the rollback asymmetry is correct, and why no Java statement corresponds to it</h3>
 *
 * <p>The rollback appears on only one of the two failure paths, which reads like a defect and is not one. At the
 * account-rewrite failure point nothing has yet been written inside the unit of work, so the transaction monitor
 * released the read-for-update locks at task end with no explicit action. At the customer-rewrite failure point
 * the account rewrite has <em>already occurred</em> inside the same unit of work, so an explicit backout is the
 * only way to avoid a half-applied update.
 *
 * <p><strong>A single transactional service method reproduces both branches automatically</strong>, because each
 * failure path returns or throws before the commit point. Nothing needs to be conditional. That is why a reviewer
 * comparing the two sources side by side finds a rollback verb with no Java counterpart and must not conclude
 * something was lost: it is a mechanism substitution, and it is stated here for exactly that reason.
 *
 * <h3>The change-detection comparison, and the trap that makes a naive version unusable</h3>
 *
 * <p>{@code 9700-CHECK-CHANGE-IN-REC} compares the live records against a snapshot captured when the screen was
 * first populated - twelve account predicates and a comparable customer set - and any mismatch abandons the
 * write. Three characteristics are easy to translate wrongly.
 *
 * <p><strong>Dates are compared as three separate substrings, never as whole strings.</strong> The open, expiry
 * and reissue dates are each compared by year, then month, then day, against discrete snapshot fields.
 *
 * <p><strong>Case handling is deliberately asymmetric.</strong> The account group identifier is compared through
 * a <em>lower</em>-casing function on both sides, while the customer name, address, state, country and government
 * identifier fields are compared through an <em>upper</em>-casing function on both sides, and the postal code,
 * both telephone numbers, the social security number, the electronic-funds account identifier, the primary-holder
 * indicator and the credit score are compared with <strong>no case function at all</strong>. This is not noise;
 * it is the behaviour, and normalising it in either direction changes which updates are accepted.
 *
 * <p><strong>The date-of-birth comparison uses different offsets on each side.</strong> The live customer record
 * holds a dash-separated date, so its components sit at offsets 1, 6 and 9; the snapshot holds the same date
 * <em>without</em> separators, at offsets 1, 5 and 7. The source compares 1 against 1, 6 against 5, and 9 against
 * 7. <strong>A whole-string comparison of the two reports a change on every single request</strong>, making the
 * endpoint permanently unusable. Compare components, and hold the snapshot date in its compact form.
 *
 * <h3>Why a version column alone is not enough</h3>
 *
 * <p>A JPA version column detects that <em>some</em> concurrent write occurred. The source detects that
 * <em>specific business field values</em> differ from what the user was shown - so a concurrent write that set a
 * field back to its original value passes the legacy check and fails a version check. These are different
 * guarantees and only the second reproduces the source. Because the target is stateless the snapshot cannot live
 * on the server between requests, so the request payload carries both the old and the new detail groups.
 * <strong>Two layers of concurrency control are therefore mandatory and neither substitutes for the
 * other.</strong>
 *
 * <h3>Five outcomes must stay five outcomes</h3>
 *
 * <p>{@code app/cbl/COACTUPC.cbl:L517-L523} declares four outcome flags - account lock failure, customer lock
 * failure, data changed before update, and locked-but-update-failed - and {@code :L667} sets a fifth marker
 * specifically when the account lock fails. The legacy screen showed which had occurred, so collapsing them into
 * one conflict loses information the legacy screen made available to the operator.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile, so a mapping that disagrees with the
 *       Flyway schema fails context startup rather than degrading into a wrong query.
 *       {@code spring.jpa.open-in-view: false}.</li>
 *   <li>Money precision is not uniform and the differences are not interchangeable: account money fields are
 *       {@code PIC S9(10)V99} and therefore {@code NUMERIC(12,2)}, while the transaction amount and the
 *       transaction-category balance are {@code PIC S9(09)V99} and therefore {@code NUMERIC(11,2)} - eleven
 *       digits, not twelve.</li>
 *   <li>{@code carddemo.decimal.rounding-mode: HALF_EVEN} and {@code monetary-scale: 2}.</li>
 *   <li>A {@code java.time.Clock} is injected, published once by
 *       {@code com.cardemo.config.ObservabilityConfig#clock(String)} as {@code Clock.systemDefaultZone()}.
 *       Region-local rather than UTC is a parity decision: the legacy region rendered local civil time, so a
 *       UTC clock would shift every rendered date and time. {@code carddemo.time.zone} pins it when a run has
 *       to reproduce a baseline captured under another zone.</li>
 *   <li>No pagination applies to this package; the account boundary is single-record.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: every update reports that the data changed.</strong> Cause: almost always the
 *       date-of-birth offsets, or a whole-string date comparison. <em>Remediation:</em> compare components at the
 *       cited offsets and store the snapshot date compactly. <strong>Severity: Blocker</strong> - the endpoint is
 *       unusable.</p></li>
 *   <li><p><strong>Symptom: an update is accepted that the legacy system would have rejected, or vice
 *       versa.</strong> Cause: the case asymmetry was normalised. <em>Remediation:</em> restore lower-casing on
 *       the group identifier, upper-casing on the customer text fields, and no case function on the remainder.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a customer row updated without its account row, or the reverse.</strong> Cause: the
 *       two writes are not in one transaction. <em>Remediation:</em> scope both into a single unit of work with
 *       rollback on any exception; that reproduces both source branches without conditional logic.
 *       <strong>Severity: Blocker</strong> - it leaves half-applied state.</p></li>
 *   <li><p><strong>Symptom: all five outcomes arrive as one status.</strong> Cause: they were collapsed.
 *       <em>Remediation:</em> restore the distinct mapping. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: an optimistic-lock failure where the legacy system would have written.</strong>
 *       Cause: the version check was relied on alone, without the field comparison, or the snapshot was
 *       regenerated server-side. <em>Remediation:</em> keep both layers and take the snapshot from the
 *       request. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a social security number or a date of birth appears in a message or a log
 *       line.</strong> Cause: a diagnostic quoted a snapshot field. <em>Remediation:</em> name the field, never
 *       the value. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a rendered date or time is an hour or a day out.</strong> Cause: a UTC clock.
 *       <em>Remediation:</em> use the injected region-local {@code Clock}.
 *       <strong>Severity: High</strong> - it is a byte-level parity break.</p></li>
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
 *   <li><strong>Test.</strong> Unit tests belong in {@code src/test/java/com/cardemo/unit/service}.
 *       {@code AccountViewServiceTest} and {@code AccountUpdateServiceTest} both exist. The assertions that
 *       matter, as distinct from mechanical coverage: each of the twelve account predicates and the customer set
 *       detecting a change independently; the lower-case versus upper-case asymmetry per field; the date-of-birth
 *       offset pairing; a failure on the customer rewrite leaving <em>no</em> account change visible; and each of
 *       the five outcomes being distinguishable.</li>
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
package com.cardemo.service.account;
