/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.billing
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (bill payment))
 * Function    : Bill payment. The payment is ALWAYS the full balance -
 *               never partial - and it drives the balance to exactly
 *               zero. A balance at or below zero is rejected before
 *               anything is written.
 * Source      : app/cbl/COBIL00C.cbl (572 lines, 16 paragraphs) @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L193 (capture the current balance), :L198 (reject at or below zero) @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L224 (move the ENTIRE balance into the amount), :L234 (subtract it) @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L173 (the two-phase confirmation gate) @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L212-L219 (descending browse of the maximum key plus one) @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L472-L496 (the empty-file path: zeros, so the first identifier is 1) @ 7756d89
 * Source      : app/cpy-bms/COBIL00.CPY (10 input fields) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE TRANSACTION(CB00)) @ 7756d89
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
 * Bill payment: one service, and a set of rules that look like simplifications until they are read against the
 * source.
 *
 * <h2>What it does</h2>
 *
 * <p>{@link com.cardemo.service.billing.BillPaymentService} reproduces {@code app/cbl/COBIL00C.cbl} - 572 lines
 * across 16 paragraphs - behind CSD transaction {@code CB00}. Its sequence is fixed and each step is a distinct
 * source line: capture the current balance ({@code :L193}); reject when that balance is at or below zero
 * ({@code :L198}); generate a transaction identifier; move the <strong>entire</strong> current balance into the
 * transaction amount ({@code :L224}); and subtract it, driving the balance to exactly zero ({@code :L234}).
 *
 * <h3>The payment is always the full balance</h3>
 *
 * <p>There is <strong>no partial payment</strong> in this service, and no amount field for the caller to choose.
 * {@code app/cbl/COBIL00C.cbl:L224} moves the whole captured balance, so the resulting balance is exactly zero -
 * not "approximately zero" and not "reduced". An implementation that accepted an amount from the request would be
 * adding a capability the source does not have, which is as much a parity break as removing one. The ten input
 * fields of {@code app/cpy-bms/COBIL00.CPY} are consistent with that: none of them is a payment amount.
 *
 * <h3>The zero-or-below rejection happens first</h3>
 *
 * <p>{@code :L198} rejects before anything is written, so an account with a zero or credit balance produces the
 * source's own screen message and no transaction at all. Reordering this after the identifier generation would
 * consume an identifier for a payment that never happens - a visible divergence, because identifiers are
 * sequential.
 *
 * <h3>The confirmation gate, and the identifier race</h3>
 *
 * <p>A two-phase confirmation precedes the whole sequence ({@code :L173}), with cursor repositioning on
 * re-prompt. In a stateless exchange the confirmation becomes an explicit flag on the request rather than a
 * remembered screen state: an unconfirmed request is refused with the source's own prompt.
 *
 * <p>Identifier generation is the same descending-browse idiom the transaction-add service uses
 * ({@code :L212-L219}): move high values into the key, browse, read the previous record, add one. The empty-file
 * path is explicit at {@code :L472-L496} - an end-of-file response yields zeros, so
 * <strong>the first generated identifier is 1</strong>, and any other response produces a specific screen message
 * with the cursor repositioned. The algorithm is inherently racy, exactly as the browse was, and it is
 * <strong>preserved</strong> rather than replaced by a database sequence, because a sequence would change
 * generated values and break comparison against the baseline. A collision surfaces as a duplicate-record outcome.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Account money fields are {@code PIC S9(10)V99} and therefore {@code NUMERIC(12,2)}, while the transaction
 *       amount this service writes is {@code PIC S9(09)V99} and therefore {@code NUMERIC(11,2)} -
 *       <strong>eleven digits, not twelve</strong>. The two precisions are not interchangeable, and this service
 *       moves a value from the wider field into the narrower one.</li>
 *   <li>{@code carddemo.decimal.rounding-mode: HALF_EVEN} and {@code monetary-scale: 2}. Equality is by
 *       {@code compareTo}, never {@code equals}, because {@code equals} distinguishes {@code 1.0} from
 *       {@code 1.00} - which matters directly here, since "is the balance zero" is a decision this service
 *       makes.</li>
 *   <li>Timestamps are 26 characters whose generated form ends in four zeros, so a generator formats to
 *       <strong>hundredths-of-a-second</strong> precision followed by those four zeros. An earlier revision
 *       said millisecond precision and is withdrawn: three fraction digits plus four zeros is seven
 *       characters, and {@code app/cbl/CBTRN02C.cbl:L159-L174} declares the fraction as two digits plus a
 *       four-character literal.</li>
 *   <li>A {@code java.time.Clock} is injected, published once as {@code Clock.systemDefaultZone()} -
 *       region-local, because the legacy region rendered local civil time.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile, so a mapping that disagrees with the
 *       Flyway schema fails context startup rather than degrading into a wrong query.</li>
 *   <li>The account update and the transaction insert are one unit of work. Object storage is not involved on this
 *       path.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: a caller can pay part of the balance.</strong> Cause: an amount was taken from the
 *       request. <em>Remediation:</em> remove it; the payment is the whole captured balance.
 *       <strong>Severity: High</strong> - it adds behaviour the source does not have.</p></li>
 *   <li><p><strong>Symptom: the resulting balance is a residual fraction rather than zero.</strong> Cause: the
 *       amount was rounded or re-scaled between capture and subtraction. <em>Remediation:</em> subtract the same
 *       value that was captured and written. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: an account with a zero balance produces a transaction.</strong> Cause: the
 *       zero-or-below rejection was moved or made a strict inequality. <em>Remediation:</em> reject at
 *       <em>or below</em> zero, before anything is written. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: identifiers have gaps after rejected payments.</strong> Cause: the identifier was
 *       generated before the rejection. <em>Remediation:</em> restore the source order.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: a payment is applied without confirmation.</strong> Cause: the confirmation gate was
 *       treated as presentation rather than behaviour. <em>Remediation:</em> refuse an unconfirmed request with the
 *       source's own prompt. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a duplicate-identifier failure under load.</strong> Cause: <em>designed</em>, and
 *       preserved. <em>Remediation:</em> surface it as a duplicate-record outcome; do not substitute a sequence.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: the balance is reduced but no transaction row exists, or the reverse.</strong> Cause:
 *       the two writes are not in one transaction. <em>Remediation:</em> scope both into a single unit of work with
 *       rollback on any exception. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a balance or a card number appears in a log line.</strong> Cause: a diagnostic quoted
 *       a value. <em>Remediation:</em> name the field, never the content. <strong>Severity: Blocker.</strong></p></li>
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
 *       {@code BillPaymentServiceTest} exists. The assertions that matter, as distinct from mechanical coverage:
 *       the payment equalling the whole captured balance and the resulting balance being exactly zero by
 *       {@code compareTo}; a zero and a credit balance both rejected before any write and consuming no identifier;
 *       an unconfirmed request refused with the source's prompt; the first identifier on an empty table being 1;
 *       and the account update and transaction insert either both applying or neither.</li>
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
package com.cardemo.service.billing;
