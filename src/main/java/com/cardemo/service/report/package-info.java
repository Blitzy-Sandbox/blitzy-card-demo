/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.report
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (report submission))
 * Function    : Report submission. The source carried an entire JCL
 *               job deck as eighty-byte literal constants and wrote it
 *               card by card to a transient data queue; here that
 *               becomes one typed queue message. Note the monthly range
 *               is month-to-date, not a full calendar month.
 * Source      : app/cbl/CORPT00C.cbl (649 lines, 10 paragraphs) @ 7756d89
 * Source      : app/cbl/CORPT00C.cbl:L80-L127 (the job deck as 80-byte literals, redefined as an array of card
 *               images) @ 7756d89
 * Source      : app/cbl/CORPT00C.cbl:L498-L508 (the submission loop; the terminating card IS written before exit) @
 *               7756d89
 * Source      : app/cbl/CORPT00C.cbl:L515-L537 (the queue write, and its exact failure message) @ 7756d89
 * Source      : app/cbl/CORPT00C.cbl:L213-L238 (monthly), :L239-L255 (yearly), :L256, :L381-L410 (custom) @ 7756d89
 * Source      : app/cbl/CORPT00C.cbl:L129-L135 (the date utility parameter block: date, format, severity, message
 *               number) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE TDQUEUE(JOBS) TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT)
 *               RECORDSIZE(80) RECORDFORMAT(FIXED) DISPOSITION(MOD)) @ 7756d89
 * Source      : app/proc/TRANREPT.prc:STEP05R (the SYMNAMES the embedded deck defines: TRAN-CARD-NUM,263,16,ZD and
 *               TRAN-PROC-DT,305,10,CH) @ 7756d89
 * Source      : app/cpy-bms/CORPT00.CPY (17 input fields, including six custom-range date components) @ 7756d89
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
 * Report submission: the bridge from an online request to a batch run.
 *
 * <h2>What it does</h2>
 *
 * <p>{@link com.cardemo.service.report.ReportSubmissionService} reproduces {@code app/cbl/CORPT00C.cbl} - 649
 * lines across 10 paragraphs - behind CSD transaction {@code CR00}. It validates a reporting period, confirms the
 * request, and hands the run off to the batch tier.
 *
 * <h3>An entire job deck collapses into one message</h3>
 *
 * <p>{@code app/cbl/CORPT00C.cbl:L80-L127} carries the submission job as a run of <strong>eighty-byte literal
 * constants</strong>, redefined as an array of up to a thousand card images: the job card, a notify continuation, a
 * procedure-library statement, the execute statement, the sort symbol definitions, the date-parameter card and a
 * terminating marker. The two date values are injected through named subfields positioned inside filler groups
 * whose lengths are chosen so that each card totals exactly eighty bytes. The submission loop at
 * {@code :L498-L508} iterates the array, writes each card to the queue, and sets a termination flag when the card
 * is the terminating marker or is blank or low values - and note the ordering, because it is easy to get wrong:
 * <strong>the terminating card is written before the loop exits.</strong>
 *
 * <p>All of that becomes <strong>one typed queue message</strong> carrying the report name and the two dates. The
 * sort symbol offsets the deck defined - {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}
 * ({@code app/proc/TRANREPT.prc:STEP05R}) - become a typed predicate in the batch tier, and the date-parameter card
 * becomes job parameters. The queue itself is
 * {@code DEFINE TDQUEUE(JOBS) TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80) RECORDFORMAT(FIXED)
 * DISPOSITION(MOD)}, and its declared eighty-byte fixed record is what fixes the parameter record's shape on the
 * other side.
 *
 * <p>One thing does <strong>not</strong> collapse: <strong>the failure message.</strong> The write paragraph at
 * {@code :L515-L537} - whose own name is misspelled in the source - produces a specific screen message when the
 * queue write fails, and that string is part of the observable contract. It is reproduced verbatim.
 *
 * <h3>Three periods, one of them counter-intuitive</h3>
 *
 * <ul>
 *   <li><strong>Monthly</strong> ({@code :L213-L238}): the start is the current year and month with day
 *       {@code 01}; the end is the current year, month <strong>and day</strong>. That is
 *       <strong>month-to-date, not a full calendar month</strong> - the single most easily mis-implemented rule in
 *       this package, because "monthly report" suggests otherwise.</li>
 *   <li><strong>Yearly</strong> ({@code :L239-L255}): the first through the last day of the current year.</li>
 *   <li><strong>Custom</strong> ({@code :L256}, {@code :L381-L410}): six discrete input fields - year, month and
 *       day for each end - each composite validated through the date utility against an explicit format string,
 *       with the two dates then assembled with dash separators.</li>
 *   </ul>
 *
 * <p>Validation goes through {@code com.cardemo.service.shared.DateValidationService}, whose parameter block the
 * source declares at {@code :L129-L135} as a date, a format, and a result group of severity code, filler and
 * message number. That shape is the service's return contract, and it is an <em>outcome</em> rather than merely a
 * parse: a date that {@code java.time} can parse but the legacy utility rejected must still be rejected.
 *
 * <h3>The confirmation handshake distinguishes four inputs</h3>
 *
 * <p>Blank, affirmative, negative and invalid are four distinct outcomes, each with its own message and cursor
 * behaviour, and the invalid case <strong>quotes the offending value back</strong>. Collapsing blank into invalid,
 * or treating anything non-affirmative as negative, changes what the caller is told.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>The queue name and its FIFO group are configured under {@code carddemo.aws.sqs.*}. Publication replaces
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}, and the listener that consumes it replaces the JES2 internal
 *       reader.</li>
 *   <li>The endpoint override and its static placeholder credentials are declared with no defaults in the
 *       <strong>base</strong> profile, so every profile inherits the indirection.
 *       {@code com.cardemo.config.AwsConfig} parses the endpoint and refuses any non-allowlisted host
 *       <em>before</em> any client bean exists, so nothing here can reach a live endpoint.</li>
 *   <li>Both dates are ten characters and are carried as job parameters rather than properties, because a per-run
 *       value in a property file would be shared by two concurrent runs.</li>
 *   <li>A {@code java.time.Clock} is injected, published once as {@code Clock.systemDefaultZone()}. Region-local
 *       rather than UTC matters directly here: the monthly and yearly ranges are derived from "today", so a UTC
 *       clock shifts the window near midnight.</li>
 *   <li>Field widths come from {@code app/cpy-bms/CORPT00.CPY} - 17 input fields, including the six custom-range
 *       date components - exactly.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: a monthly report covers the whole calendar month.</strong> Cause: the end date was set
 *       to the month's last day. <em>Remediation:</em> the end is <em>today</em> - month-to-date.
 *       <strong>Severity: High</strong> - the report content differs from the baseline.</p></li>
 *   <li><p><strong>Symptom: a date the legacy utility rejected is accepted.</strong> Cause: validation was replaced
 *       by a lenient parse. <em>Remediation:</em> route it through the shared date validation service and honour its
 *       severity outcome, not merely whether parsing succeeded. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a job is submitted but never runs.</strong> Cause: the message was published to the
 *       wrong queue, or the listener is not running. <em>Remediation:</em> confirm the queue name and that the
 *       emulator has been provisioned by {@code localstack-init/init-aws.sh}.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a queue write failure produces a generic error.</strong> Cause: the source's specific
 *       screen message was replaced. <em>Remediation:</em> reproduce it verbatim; it is part of the observable
 *       contract. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: a request is submitted without confirmation, or a blank confirmation is treated as
 *       negative.</strong> Cause: the four-way handshake was collapsed. <em>Remediation:</em> restore all four
 *       outcomes, including quoting the offending value on the invalid one.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: the batch report includes or excludes boundary dates unexpectedly.</strong> Cause: the
 *       range predicate is not inclusive at both ends, or it compares more than the ten-character date prefix of
 *       the processing timestamp. <em>Remediation:</em> inclusive at both ends, on the ten-character prefix, per
 *       the sort symbols. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: startup or submission fails naming an AWS endpoint.</strong> Cause: the emulator
 *       binding guard rejected a non-local host, or the emulator is not up. <em>Remediation:</em> point the
 *       endpoint at the emulator; do not relax the allowlist.
 *       <strong>Severity: Blocker</strong> if bypassed.</p></li>
 *   <li><p><strong>Symptom: a report window near midnight differs by a day from the baseline.</strong> Cause: a
 *       UTC clock. <em>Remediation:</em> use the injected region-local {@code Clock}.
 *       <strong>Severity: High.</strong></p></li>
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
 *       environment loaded with {@code set -a; . ./.env; set +a}, because {@code JWT_SIGNING_KEY} has no default and
 *       startup fails without it by design.</li>
 *   <li><strong>Test.</strong> Tests belong in {@code src/test/java/com/cardemo/unit/service}, and the
 *       queue-publication path additionally in {@code src/test/java/com/cardemo/integration/aws} against the
 *       emulator. <strong>Not available, measured 3 August 2026:</strong> {@code ReportSubmissionService} has no
 *       test class and is not referenced anywhere under {@code src/test/java}, so this package contributes
 *       <strong>zero</strong> covered lines and no coverage figure quoted anywhere is evidence about it. The
 *       required assertions are: the monthly range ending today rather than at month end; the yearly range spanning
 *       the whole current year; each of the six custom components validated individually; all four confirmation
 *       outcomes distinguishable, with the invalid one quoting the offending value; the published message carrying
 *       the report name and both dates; and the queue-write failure message reproduced verbatim.</li>
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
package com.cardemo.service.report;
