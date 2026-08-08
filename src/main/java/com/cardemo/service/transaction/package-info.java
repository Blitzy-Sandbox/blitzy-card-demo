/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.transaction
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (transaction transactions))
 * Function    : The transaction boundary's decisions: the ten-row list
 *               of COTRN00C, the detail view of COTRN01C, and the add
 *               path of COTRN02C with its descending-browse identifier
 *               generation and its TWO deliberately different numeric
 *               parsers.
 * Source      : app/cbl/COTRN00C.cbl (699 lines, 16 own paragraph labels; 10 rows per page at :L65-L68) @ 7756d89
 * Source      : app/cbl/COTRN01C.cbl (330 lines, 9 own paragraph labels; single-record detail) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl (783 lines, 18 own paragraph labels; transaction add) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L444-L451 (descending browse of the maximum key plus one) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L204, :L218 (plain numeric conversion for the identifiers) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L383, :L456 (CURRENCY-aware conversion for the amount) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L58-L59, :L385-L386 (S9(09)V99 field and the edited display mask) @ 7756d89
 * Source      : app/cpy/CVTRA05Y.cpy (350-byte transaction layout; the fourteen fields and their offsets) @ 7756d89
 * Source      : app/cpy-bms/COTRN00.CPY (59 input fields), COTRN01.CPY (21), COTRN02.CPY (21) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L351-L360 (the alternate index: KEYLEN 26, AXRKP 304, on the processing
 *               timestamp) @ 7756d89
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
 * The transaction services: list, detail and add over the transaction cluster.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.service.transaction.TransactionListService} - {@code app/cbl/COTRN00C.cbl}, 699 lines.
 *       <strong>Ten rows a page</strong> ({@code app/cbl/COTRN00C.cbl:L65-L68}), forward and backward. The ten-row
 *       table is why {@code app/cpy-bms/COTRN00.CPY} declares 59 input fields.</li>
 *   <li>{@link com.cardemo.service.transaction.TransactionDetailService} - {@code app/cbl/COTRN01C.cbl}, 330
 *       lines. Single-record retrieval, with the amount rendered on the legacy display mask rather than as a plain
 *       decimal.</li>
 *   <li>{@link com.cardemo.service.transaction.TransactionAddService} - {@code app/cbl/COTRN02C.cbl}, 783 lines.
 *       Identifier generation, two parsers and the edited echo, all described below.</li>
 *   </ul>
 *
 * <h3>Identifier generation: a race that is preserved, not repaired</h3>
 *
 * <p>{@code app/cbl/COTRN02C.cbl:L444-L451} moves high values into the key, starts a browse, reads the
 * <em>previous</em> record, ends the browse and adds one to the retrieved identifier. The empty-file path is
 * explicit: an end-of-file response yields zeros, so <strong>the first generated identifier is 1</strong>. In
 * Java this is a top-one descending-ordered query, parsed on a hit and defaulted to zero on an empty result, then
 * incremented and zero-padded to sixteen characters.
 *
 * <p><strong>The algorithm is inherently racy under concurrency, exactly as the browse was, and it stays
 * that way.</strong> The parity-preserving choice is to keep it and let the primary-key constraint surface a
 * collision as a duplicate-record outcome, rather than substituting a database sequence - which would change
 * generated values and break comparison against the baseline. Note too that once an interest run has occurred,
 * generated batch identifiers dominate the descending browse, because they lead with the ten-character date
 * parameter and are therefore numerically large.
 *
 * <h3>Two numeric parsers, used deliberately - not an inconsistency to unify</h3>
 *
 * <p>The source uses the <em>plain</em> numeric conversion for the account identifier
 * ({@code app/cbl/COTRN02C.cbl:L204}) and the card number ({@code :L218}), but the
 * <strong>currency-aware</strong> conversion for the amount ({@code :L383} and {@code :L456}). The currency-aware
 * form additionally tolerates a currency symbol and thousands separators. Java therefore needs
 * <strong>two distinct parsers</strong>: a strict digits-only parser for identifiers and card numbers, and a
 * currency-tolerant parser for amounts. Using one for both either accepts input the legacy system rejected or
 * rejects input it accepted.
 *
 * <p>The display round trip is equally specific. The parsed amount is moved into an edited field and back into
 * the screen field ({@code :L385-L386}), where the edited field carries a <strong>mandatory sign, exactly eight
 * integer digits and two decimals</strong> ({@code :L59}) while the underlying numeric field is a signed
 * nine-integer, two-decimal value ({@code :L58}). So the response echo is formatted on the edited mask - which is
 * why a report or a payload shows a comma-grouped, sign-bearing form and not the plain decimal string.
 *
 * <h3>The alternate index on the processing timestamp</h3>
 *
 * <p>{@code app/catlg/LISTCAT.txt:L351-L360} records the transaction cluster's alternate index as
 * {@code KEYLEN 26, AXRKP 304} - a 26-character key at byte 305 of the base record, which is the processing
 * timestamp per {@code app/cpy/CVTRA05Y.cpy}. It becomes a finder over the same table backed by a non-unique
 * B-tree index, because many transactions share a timestamp.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Page size 10 is a parity contract</strong>, from {@code app/cbl/COTRN00C.cbl:L65-L68}. Not
 *       configurable, and unrelated to the batch reader fetch sizes under {@code carddemo.batch.*}. Browse
 *       position moves to a request parameter and the next-page flag to response metadata.</li>
 *   <li>The transaction amount is {@code PIC S9(09)V99} and therefore {@code NUMERIC(11,2)} -
 *       <strong>eleven digits, not the twelve</strong> the account money fields use. The two precisions are not
 *       interchangeable.</li>
 *   <li>{@code carddemo.decimal.rounding-mode: HALF_EVEN} and {@code monetary-scale: 2}. Equality is by
 *       {@code compareTo}, never {@code equals}.</li>
 *   <li>Timestamps are 26 characters and the generated form's <strong>final four digits are always zeros</strong>,
 *       so any generator here formats to <strong>hundredths-of-a-second</strong> precision followed by four
 *       zeros, never millisecond precision, because three fraction
 *       digits plus four zeros is seven characters and the field holds six -
 *       {@code app/cbl/CBTRN02C.cbl:L159-L174} splits the fraction into {@code DB2-MIL PIC 9(002)} and
 *       {@code DB2-REST PIC X(04)}, and {@code :L700-L701} moves the two-digit hundredths in and the literal
 *       {@code '0000'} after it. Three mutually
 *       incompatible producers exist upstream and all three must survive untouched, including pure
 *       pass-through.</li>
 *   <li>A {@code java.time.Clock} is injected, published once as {@code Clock.systemDefaultZone()} - region-local
 *       rather than UTC, because the legacy region rendered local civil time and a UTC clock would shift every
 *       generated timestamp.</li>
 *   <li>Field widths come from {@code app/cpy-bms/COTRN00.CPY}, {@code COTRN01.CPY} and {@code COTRN02.CPY}
 *       exactly.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: an amount with a currency symbol or a thousands separator is rejected.</strong>
 *       Cause: the strict identifier parser was applied to the amount. <em>Remediation:</em> use the
 *       currency-tolerant parser there. <strong>Severity: High</strong> - it rejects input the legacy system
 *       accepted.</p></li>
 *   <li><p><strong>Symptom: an account identifier or card number containing a symbol is accepted.</strong>
 *       Cause: the currency-tolerant parser was applied to an identifier. <em>Remediation:</em> use the strict
 *       parser there. <strong>Severity: High</strong> - it accepts input the legacy system rejected, and the value
 *       then reaches a fixed-width boundary.</p></li>
 *   <li><p><strong>Symptom: a duplicate-identifier failure under load.</strong> Cause: <em>designed</em> - the
 *       generation algorithm is racy in the source and is preserved. <em>Remediation:</em> surface the collision
 *       as a duplicate-record outcome and let the caller retry; do not substitute a sequence, which would change
 *       generated values. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: the first transaction on an empty table gets identifier 0, or the insert
 *       fails.</strong> Cause: the empty-result path was not handled. <em>Remediation:</em> default to zero then
 *       increment, so the first identifier is 1. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: an echoed amount differs from the baseline in its formatting.</strong> Cause: the
 *       plain decimal string was returned instead of the edited mask. <em>Remediation:</em> format on the mask -
 *       mandatory sign, eight integer digits, two decimals. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: a generated timestamp differs from the baseline in its final digits.</strong> Cause:
 *       nanosecond or millisecond precision. <em>Remediation:</em> hundredths-of-a-second precision followed
 *       by four literal zeros, which is what fits the six-character fraction field.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a card number appears in a log line or an error payload.</strong> Cause: a diagnostic
 *       quoted the value. <em>Remediation:</em> mask it; the card number sits at bytes 263-278 of the record and
 *       must never be logged. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: the transaction list skips or repeats rows.</strong> Cause: page size changed, or the
 *       browse position came from somewhere other than the request. <em>Remediation:</em> restore 10 and drive the
 *       position from the request. <strong>Severity: Medium.</strong></p></li>
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
 *       {@code TransactionListServiceTest}, {@code TransactionDetailServiceTest} and
 *       {@code TransactionAddServiceTest} all exist. The assertions that matter, as distinct from mechanical
 *       coverage: page size exactly 10 with forward and backward paging; the strict parser rejecting a symbol and
 *       the currency-tolerant parser accepting one, on their respective fields; the first identifier on an empty
 *       table being 1; a collision surfacing as a duplicate-record outcome rather than an overwrite; the edited
 *       mask on the echoed amount; and no card number reaching any message, payload or log event unmasked.</li>
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
package com.cardemo.service.transaction;
