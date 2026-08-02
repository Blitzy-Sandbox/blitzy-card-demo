/*
 * ******************************************************************
 * Program     : ReportSubmissionService.java
 * Application : CardDemo
 * Type        : Spring Boot Service (REST/SQS)
 * Function    : Print Transaction reports by submitting batch job
 *               from online using extra partition TDQ.
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
 * Source      : app/cbl/CORPT00C.cbl (649 lines, 10 paragraphs) @ 7756d89
 * ******************************************************************
 */
package com.cardemo.service.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.shared.DateValidationService;

import io.awspring.cloud.sqs.operations.MessagingOperationFailedException;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsTemplate;

/**
 * The CardDemo report submission bean: CICS transaction {@code CR00} and its program
 * {@code app/cbl/CORPT00C.cbl}, with the transient data queue job submission replaced by one SQS FIFO
 * publish.
 *
 * <h2>What it does</h2>
 *
 * <p>This bean is the online half of the transaction report feature. It resolves a reporting period,
 * validates it, runs a two phase confirmation handshake, and then publishes <strong>exactly one</strong>
 * typed message asking the batch tier to produce the report. It is the Java form of
 * {@code app/cbl/CORPT00C.cbl}, 649 lines and ten paragraph labels, fronted by
 * {@code DEFINE TRANSACTION(CR00) ... PROGRAM(CORPT00C)} at {@code app/csd/CARDDEMO.CSD:L409-L410} and
 * surfaced over HTTP by {@code com.cardemo.controller.ReportController}.
 *
 * <p><strong>It publishes, and that is all it does.</strong> It runs no batch job, schedules no job,
 * builds no job control text, writes no file, and spawns no process. The legacy program submitted work by
 * writing seventeen eighty byte job control card images one at a time to an extrapartition transient data
 * queue whose {@code DDNAME(INREADER)} handed them to the JES2 internal reader
 * ({@code app/csd/CARDDEMO.CSD:L499-L505}). In the target those cards collapse into a single typed message
 * on an SQS FIFO queue, and an SQS listener on the batch side replaces the internal reader and maps the
 * message onto {@code TransactionReportJob} parameters. Rule 1 Clause D names {@code eval/exec} and
 * {@code shell injection} explicitly, and this is the one bean in the migration whose ancestor submitted a
 * job, so the prohibition is absolute and structural: there is no {@code Runtime.exec}, no
 * {@code ProcessBuilder}, and no string built control text anywhere in this file.
 *
 * <p>Three periods are supported, and the selector precedence between them is behaviour rather than
 * convention. {@code app/cbl/CORPT00C.cbl:L212} opens a single {@code EVALUATE TRUE} whose three arms test
 * {@code MONTHLYI} at {@code :L213}, {@code YEARLYI} at {@code :L239} and {@code CUSTOMI} at {@code :L256},
 * each for being neither spaces nor low values. {@code EVALUATE TRUE} is first match wins, so
 * <strong>monthly beats yearly beats custom</strong> when more than one selector is supplied. That is also
 * why the request carries three independent one character fields rather than an enumeration: an enumeration
 * cannot express "two selectors supplied at once", and the source can.
 *
 * <h2>Finding, Blocker: the monthly period is the FULL current calendar month</h2>
 *
 * <p><strong>Severity: Blocker. Locator: {@code app/cbl/CORPT00C.cbl:L213-L238}. Status: implemented
 * correctly here; recorded so the root agent can enter it in {@code DECISION_LOG.md}.</strong>
 *
 * <p>The monthly period runs from the first day of the current month to the <strong>last</strong> day of
 * the current month. It is <strong>not</strong> month to date. The source proves it in six steps:
 *
 * <ol>
 *   <li>{@code :L217-L219} build the start date as the current year, the current month and the literal
 *       {@code '01'}.</li>
 *   <li>{@code :L223} executes {@code MOVE 1 TO WS-CURDATE-DAY}, which <strong>discards today's day of
 *       month</strong> and forces it to 1.</li>
 *   <li>{@code :L224} executes {@code ADD 1 TO WS-CURDATE-MONTH}, advancing the month by one.</li>
 *   <li>{@code :L225-L228} roll the year and reset the month to 1 when the month exceeds 12.</li>
 *   <li>{@code :L229-L230} compute
 *       {@code FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)}: the first of the
 *       <em>next</em> month, minus one day, which is the last day of the <em>current</em> month.</li>
 *   <li>{@code :L232-L234} read the end year, month and day back out of {@code WS-CURDATE-YEAR},
 *       {@code -MONTH} and {@code -DAY} <strong>after step 5 has already mutated all three</strong>.</li>
 * </ol>
 *
 * <p>Step 6 is the whole difficulty, and the mechanism behind it is
 * {@code app/cpy/CSDAT01Y.cpy:L19-L23}: {@code WS-CURDATE} is a group of {@code WS-CURDATE-YEAR PIC 9(04)},
 * {@code WS-CURDATE-MONTH PIC 9(02)} and {@code WS-CURDATE-DAY PIC 9(02)}, and {@code :L23} declares
 * {@code WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)} over exactly those eight digits. The
 * {@code COMPUTE} therefore writes its result <em>through the redefinition and back into the three
 * subfields</em>, so the moves at {@code :L232-L234} emit the computed month end and not today.
 *
 * <p>December confirms the reading rather than contradicting it: month 13 becomes January of the following
 * year, one day less is 31 December, and the year and month land back on the original year and December.
 *
 * <p><strong>Two secondary sources state this incorrectly and are superseded by the source.</strong>
 * The first is the plan prose at section 0.7.5.2, which describes the monthly period as month to date. The
 * second is the planning prompt written for {@code src/main/java/com/cardemo/model/dto/ReportRequest.java},
 * which repeated that error. The mistake is explainable: an author reading only {@code :L232-L234} sees the
 * current date subfields named and concludes the end date is today, without noticing that
 * {@code :L223-L230} has already overwritten them in place. Had it been implemented that way the emitted
 * end date would differ on every day of the month except the last, and every downstream {@code TRANREPT}
 * report would diverge from the Gate 1 parity baseline.
 *
 * <p>The obligation to fix it was formally assigned to this bean. The delivered
 * {@code com.cardemo.model.dto.ReportRequest} records, in its own findings register, that the monthly
 * period is a full calendar month and that "remediation still owed by the service layer:
 * ReportSubmissionService must implement the arithmetic at :223-234 rather than the prose description; that
 * bean is not available". <strong>This file discharges that obligation.</strong> The delivered DTO and this
 * bean therefore agree, and no conflicting assertion is propagated from it.
 *
 * <p>The arithmetic is performed with {@code java.time} month length adjusters against the injected
 * {@link Clock}. There is no hard coded table of month lengths, no leap year branch, and no bare
 * {@code LocalDate.now()} anywhere in this file. Rule 1 Clause A requires determinism, and the month end is
 * the single most time zone sensitive value the online tier computes: an unclocked {@code now()} would make
 * the period boundary depend on the ambient zone of whatever host happened to run the request.
 *
 * <h2>Finding, High: every screen send is a terminal exit, so validation is first failure wins</h2>
 *
 * <p><strong>Severity: High. Locator: {@code app/cbl/CORPT00C.cbl:L580}.</strong>
 *
 * <p>{@code SEND-TRNRPT-SCREEN} closes its conditional at {@code :L578} and then, as a separate
 * unconditional sentence at {@code :L580}, executes {@code GO TO RETURN-TO-CICS.}.
 * {@code RETURN-TO-CICS} at {@code :L585} issues {@code EXEC CICS RETURN} and ends the pseudo
 * conversational task. <strong>All twenty two {@code PERFORM SEND-TRNRPT-SCREEN} sites are therefore
 * terminal exits: the {@code PERFORM} never returns.</strong>
 *
 * <p>The consequence is decisive for the six sequential unguarded range tests at {@code :L329-L379}. They
 * do <strong>not</strong> overwrite one another, because the first one that fails ends the task. Validation
 * is short circuit, and <strong>exactly one message is emitted per request</strong>. This is the opposite
 * of the {@code CBTRN02C} reject code cascade documented elsewhere in the migration, where code 103
 * genuinely overwrites code 102 precisely because that program has no terminal exit between the two checks.
 * Accordingly every failing check here raises immediately, carrying its own literal and its own per field
 * marker, and no multi error list is ever accumulated.
 *
 * <p>Because the {@code GO TO} leaves a performed range and the following {@code EXEC CICS RETURN} ends the
 * task outright, the {@code PERFORM} stack is never unwound and no return address is ever resumed. That is
 * why the three {@code IF NOT ERR-FLG-ON} guards at {@code :L434}, {@code :L445} and {@code :L476} can
 * never observe a set error flag at runtime. They are nevertheless real code on a reachable path, so they
 * are reproduced one for one and marked as intentionally retained defensive guards. Rule 1 Clause B forbids
 * <em>untracked</em> dead code; these are cited, justified and tracked.
 *
 * <h2>Finding, Medium: the embedded job deck is seventeen cards, not eighteen</h2>
 *
 * <p><strong>Severity: Medium, documentation only. Locator: {@code app/cbl/CORPT00C.cbl:L81-L127}.</strong>
 *
 * <p>{@code 01 JOB-DATA.} declares <strong>seventeen</strong> eighty byte card images, counted directly as
 * the {@code 05} entries of {@code 02 JOB-DATA-1}: fourteen plain {@code 05 FILLER PIC X(80) VALUE}
 * literals plus the three named multi part groups {@code FILLER-1} at {@code :L103}, {@code FILLER-2} at
 * {@code :L108} and {@code FILLER-3} at {@code :L117}, each of which sums to eighty bytes. Seventeen cards
 * of eighty bytes is 1,360 bytes, which is what {@code 02 JOB-DATA-2 REDEFINES JOB-DATA-1} at
 * {@code :L126-L127} exposes as {@code 05 JOB-LINES OCCURS 1000 TIMES PIC X(80)}. The deck, in order, is:
 *
 * <table border="1">
 *   <caption>The seventeen card images, verified on the anchor commit</caption>
 *   <tr><th>#</th><th>Lines</th><th>Card</th></tr>
 *   <tr><td>1</td><td>83-84</td><td>the job card</td></tr>
 *   <tr><td>2</td><td>85-86</td><td>the notify continuation</td></tr>
 *   <tr><td>3</td><td>87-88</td><td>a comment card</td></tr>
 *   <tr><td>4</td><td>89-90</td><td>the procedure library card</td></tr>
 *   <tr><td>5</td><td>91-92</td><td>a comment card</td></tr>
 *   <tr><td>6</td><td>93-94</td><td>the execute card naming the report procedure</td></tr>
 *   <tr><td>7</td><td>95-96</td><td>a comment card</td></tr>
 *   <tr><td>8</td><td>97-98</td><td>the sort symbol input card</td></tr>
 *   <tr><td>9</td><td>99-100</td><td>the card number symbol, offset 263, 16 zoned decimal</td></tr>
 *   <tr><td>10</td><td>101-102</td><td>the processing date symbol, offset 305, 10 characters</td></tr>
 *   <tr><td>11</td><td>103-107</td><td>{@code FILLER-1}: 18 + {@code PARM-START-DATE-1} 10 + 52</td></tr>
 *   <tr><td>12</td><td>108-112</td><td>{@code FILLER-2}: 16 + {@code PARM-END-DATE-1} 10 + 54</td></tr>
 *   <tr><td>13</td><td>113-114</td><td>an end of data card</td></tr>
 *   <tr><td>14</td><td>115-116</td><td>the date parameter input card</td></tr>
 *   <tr><td>15</td><td>117-121</td><td>{@code FILLER-3}: {@code PARM-START-DATE-2} 10 + 1 +
 *       {@code PARM-END-DATE-2} 10 + 59</td></tr>
 *   <tr><td>16</td><td>122-123</td><td>an end of data card</td></tr>
 *   <tr><td>17</td><td>124-125</td><td>the terminator card</td></tr>
 * </table>
 *
 * <p>The plan prose at section 0.7.5.1 says eighteen and is superseded. The delivered
 * {@code com.cardemo.model.dto.ReportRequest} already records the same correction, so the two agree. One
 * residual instance of the stale figure survives as a YAML comment beside the queue properties in
 * {@code src/main/resources/application.yml}; it is documentation only, it is not this file, and it is not
 * edited here. The finding carries no code impact either way, because the deck collapses to one typed
 * message whatever its card count.
 *
 * <p>Cards 11, 12 and 15 are why the source moves each date to a <strong>pair</strong> of targets at
 * {@code :L220-L221}, {@code :L235-L236}, {@code :L247-L248}, {@code :L252-L253}, {@code :L429-L430} and
 * {@code :L431-L432}. Each date is injected twice, so there are <strong>four</strong> injection points and
 * not two: {@code PARM-START-DATE-1} on the sort symbol card and {@code PARM-START-DATE-2} on the date
 * parameter card, and likewise for the end date. The typed message carries each date <strong>once</strong>,
 * so the two injection points per date collapse to one field per date. Cards 9 and 10 independently
 * corroborate the sort specification of {@code app/proc/TRANREPT.prc}; they are provenance only, and no
 * sort is implemented here.
 *
 * <h2>Finding, High: message number 2513 is explicitly tolerated as success</h2>
 *
 * <p><strong>Severity: High. Locators: {@code app/cbl/CORPT00C.cbl:L396-L406} and
 * {@code :L416-L426}.</strong>
 *
 * <p>Both date validations accept a severity code of {@code '0000'} outright and then, on the else arm,
 * fail <strong>only when the message number is not {@code '2513'}</strong>. A non zero severity whose
 * message number is {@code '2513'} is therefore treated as success, on <strong>both</strong> calls.
 * {@code 2513} is the Language Environment {@code FC-UNSUPP-RANGE} condition, carried in the
 * {@code DateValidationService} contract as
 * {@code FeedbackCondition.FC_UNSUPP_RANGE(3, 2513, "Unsupp. Range  ")}. A naive implementation that
 * rejected any non zero severity would wrongly reject dates the source accepts, so the exemption is
 * reproduced on both calls.
 *
 * <h2>Finding, Low: two unreachable numeric guards</h2>
 *
 * <p><strong>Severity: Low. Locators: {@code app/cbl/CORPT00C.cbl:L347} and {@code :L373}.</strong>
 *
 * <p>{@code :L305-L327} round trips all six custom range components through the <em>unsigned</em> items
 * {@code WS-NUM-99 PIC 99} and {@code WS-NUM-9999 PIC 9999} unconditionally, so every component holds
 * nothing but digit characters afterwards. {@code IF SDTYYYYI IS NOT NUMERIC} at {@code :L347} and
 * {@code IF EDTYYYYI IS NOT NUMERIC} at {@code :L373} can consequently never be true, and the
 * {@code IS NOT NUMERIC} disjunct of the four month and day tests is likewise always false, leaving only
 * the string comparisons to fire. All six tests are reproduced one for one regardless, and the two wholly
 * unreachable ones are marked as intentionally retained. This finding is this file's own; it is not carried
 * by the plan.
 *
 * <h2>Finding, Low: a captured response code that the source never tests</h2>
 *
 * <p><strong>Severity: Low. Locator: {@code app/cbl/CORPT00C.cbl:L596-L604}.</strong>
 *
 * <p>{@code RECEIVE-TRNRPT-SCREEN} captures {@code RESP(WS-RESP-CD)} and {@code RESP2(WS-REAS-CD)} and then
 * tests neither: no conditional follows the command. Transformation Rule 12 requires that an I/O status
 * never be swallowed, but here the frozen source swallows one, and parity governs, so the absent check is
 * preserved rather than invented. In the target the concern is moot anyway, because binding an HTTP request
 * body is the framework's responsibility and its failures surface before this bean is entered.
 *
 * <h2>Mechanism substitutions recorded for the decision log</h2>
 *
 * <ul>
 *   <li><strong>Transient data queue write becomes an SQS FIFO publish.</strong>
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at {@code app/cbl/CORPT00C.cbl:L517-L523} becomes one
 *       {@code SqsTemplate} send. The queue's own declaration fixes the message contract:
 *       {@code RECORDSIZE(80) RECORDFORMAT(FIXED)} at {@code app/csd/CARDDEMO.CSD:L502-L503} is the
 *       provenance of an eighty byte fixed shape, and {@code DISPOSITION(MOD)} - strict sequential append -
 *       is the provenance of the single message group that makes FIFO ordering deterministic.</li>
 *   <li><strong>The seventeen card write loop becomes one publish.</strong> The loop at
 *       {@code :L498-L508} wrote the cards one at a time; a typed message needs one send. The loop's
 *       observable ordering property is documented on {@code submitJobToIntrdr} and preserved in
 *       behaviour.</li>
 *   <li><strong>The pseudo conversational re-enter flag has no counterpart.</strong>
 *       {@code CDEMO-PGM-REENTER} at {@code :L177} splits first entry from re-entry. In a stateless target
 *       that split is the difference between a GET that returns an empty form and a POST that submits, so
 *       it becomes two methods and no server side state. {@code CDEMO-TO-PROGRAM},
 *       {@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-PGM-CONTEXT},
 *       {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} likewise have no counterpart: routing is URL
 *       based under Transformation Rule 7, which forbids server side session state.</li>
 *   <li><strong>Text substitution into control cards becomes a typed message.</strong> The source injected
 *       dates into eighty byte card images by positional substitution. The target emits a typed message, so
 *       no injection surface exists, and none may be reintroduced downstream by assembling control text
 *       from strings.</li>
 * </ul>
 *
 * <h2>Deliberate omissions, so a reviewer diffing the source is not puzzled</h2>
 *
 * <ul>
 *   <li>Five working storage items are declared in the source and never referenced by any statement:
 *       {@code WS-TRANSACT-FILE} at {@code app/cbl/CORPT00C.cbl:L40}, {@code WS-TRANSACT-EOF} with its two
 *       condition names at {@code :L44-L46}, {@code WS-REC-COUNT} at {@code :L56}, {@code WS-TRAN-AMT} at
 *       {@code :L77} and {@code WS-TRAN-DATE} at {@code :L78}. They are data division items, not
 *       paragraphs, so they carry no control flow and the paragraph map is unaffected by their absence. No
 *       Java field is created for any of them, which is the Rule 1 Clause B answer: a field no statement
 *       reads is dead code.</li>
 *   <li>{@code COPY DFHAID} and {@code COPY DFHBMSCA} at {@code :L148-L149} are supplied by the transaction
 *       monitor and are absent from the repository, so they map onto framework mechanisms and produce no
 *       Java import. The attention identifier they define is modelled by {@link AttentionIdentifier}; the
 *       screen attribute {@code DFHGREEN} used at {@code :L448} becomes the success flag on
 *       {@link ReportSubmissionScreen}.</li>
 *   <li>{@code COPY CVTRA05Y} at {@code :L146} supplies the 350 byte transaction record layout. No
 *       statement in the program references any of its fields; the program neither reads nor writes a
 *       transaction. No entity is imported.</li>
 *   <li>{@code com.cardemo.service.shared.FileStatusMapper} was retrieved and evaluated and is
 *       deliberately <strong>not</strong> injected. {@code app/cbl/CORPT00C.cbl} declares no
 *       {@code FILE-CONTROL} paragraph, no {@code SELECT}, no {@code FD} and no {@code FILE STATUS} clause
 *       anywhere - it is a pure CICS program whose only I/O status vocabulary is {@code RESP} and
 *       {@code RESP2} - and that mapper states in its own documentation that it "deliberately does not
 *       conflate" the COBOL {@code FILE STATUS} vocabulary with the CICS response code vocabulary. There is
 *       therefore nothing here for it to translate, and injecting an unused collaborator would be precisely
 *       the dead code Clause B forbids. The fixed literal {@code FILE STATUS IS: NNNN} is consequently
 *       never emitted by this file, and cannot be reformatted by it.</li>
 *   <li>{@code com.cardemo.exception.FatalProcessingException} was likewise evaluated and is not used: the
 *       program contains no abend path at all, no {@code CALL 'CEE3ABD'} and no abend paragraph, so there is
 *       no site that could raise it. {@code com.cardemo.exception.CardDemoException} is not imported either;
 *       it is the supertype of the two exceptions that <em>are</em> raised, and naming it without a use site
 *       would leave an unused import.</li>
 *   <li>A width mismatch exists in the source and is harmless in practice.
 *       {@code WS-MESSAGE} is {@code PIC X(80)} at {@code :L39}, while {@code ERRMSGO} and {@code ERRMSGI}
 *       are {@code PIC X(78)} at {@code app/cpy-bms/CORPT00.CPY:L224} and {@code :L120}, so the move at
 *       {@code app/cbl/CORPT00C.cbl:L560} truncates two bytes. No literal in the program reaches 78
 *       characters, so nothing is ever actually truncated; the mismatch is recorded here so that a reviewer
 *       comparing the two widths is not left wondering.</li>
 * </ul>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Java 25 with {@code maven.compiler.release} set to 25 and no preview feature enabled, and Maven
 * 3.9.11 through the pinned wrapper. The compiler runs {@code -Xlint:all} with {@code -Werror}, so any
 * warning {@code javac} 25 publishes fails the build. Compile with {@code ./mvnw -B -ntp clean compile} and
 * test with {@code ./mvnw -B -ntp test}; {@code ./mvnw -B -ntp verify} additionally enforces the JaCoCo
 * line coverage floor of 0.80. The environment file must be sourced first, because the queue name resolves
 * from it: {@code set -a; . ./.env; set +a}.
 *
 * <p>Tests for this bean live in {@code src/test/java/com/cardemo/unit/service/} and nowhere else; no test
 * source, fixture or helper belongs in this package. The bean is built to be unit testable without any
 * container: every collaborator is constructor injected, there is no static mutable state, and the only
 * time source is the injected {@link Clock}. The cases a test tier needs are the full calendar month for
 * 28, 29, 30 and 31 day months <strong>and</strong> the December roll; the yearly period; each of the six
 * blank checks and each of the six range checks in source order; the {@code '2513'} exemption on both
 * validation calls; all four confirmation states including the deliberately blank message on the negative
 * one; the terminator written before the loop exits; and a publish failure reproducing the literal from
 * {@code app/cbl/CORPT00C.cbl:L531}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Three properties, all bound by Spring from {@code src/main/resources/application.yml} under
 * {@code carddemo.aws.sqs}. There is no {@code System.getenv} call in this file and no hard coded host,
 * port or endpoint literal.
 *
 * <table border="1">
 *   <caption>Configuration consumed by this bean</caption>
 *   <tr><th>Property</th><th>Value</th><th>Purpose</th></tr>
 *   <tr><td>{@code carddemo.aws.sqs.report-queue}</td><td>{@code ${CARDDEMO_SQS_REPORT_QUEUE}}</td>
 *       <td>The physical queue name the publish targets. A {@code .fifo} suffix is required by the service
 *       and is permitted here. No default: an unset variable fails property resolution and therefore
 *       startup, which is the wanted behaviour.</td></tr>
 *   <tr><td>{@code carddemo.aws.sqs.report-queue-logical-name}</td><td>{@code carddemo-report-jobs}</td>
 *       <td>The logical contract name, used in diagnostics so that a log line names the queue the migration
 *       documents rather than whatever physical name an environment happens to carry.</td></tr>
 *   <tr><td>{@code carddemo.aws.sqs.report-message-group-id}</td><td>{@code carddemo-report-jobs}</td>
 *       <td>The FIFO message group. <strong>Deterministic and configured, never generated.</strong> A random
 *       group identifier would scatter messages across groups and forfeit the ordering guarantee
 *       non-reproducibly, whereas one fixed group reproduces the strict sequential append semantics of
 *       {@code DISPOSITION(MOD)}.</td></tr>
 * </table>
 *
 * <p>The remaining collaborators are beans rather than properties: {@code SqsTemplate}, which
 * {@code com.cardemo.config.AwsConfig} owns together with the LocalStack endpoint override, so no client is
 * ever constructed here and no code path can reach a live endpoint;
 * {@link com.cardemo.service.shared.DateValidationService}, which is mandatory; and {@link Clock}, which
 * supplies both the instant and the zone the monthly and yearly periods resolve against. This bean needs
 * send capability on one queue and asks for nothing more, which is Rule 1 Clause D least privilege
 * satisfied structurally.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>{@code FileAccessException} carrying {@code Unable to Write TDQ (JOBS)...}</strong> - the
 *       publish failed. A warning is logged first, carrying the {@code RESP:} and {@code REAS:} prefixes of
 *       the diagnostic at {@code app/cbl/CORPT00C.cbl:L529} so that the legacy operator's log line is still
 *       recognisable, followed by the endpoint and the underlying cause. Check that the queue exists.</li>
 *   <li><strong>Queue does not exist.</strong> {@code localstack-init/init-aws.sh} provisions it and runs
 *       automatically from the LocalStack ready hook; if the stack was started without it, the queue is
 *       absent and every publish fails. Remedy: {@code docker compose up -d} with the initialisation script
 *       mounted, then confirm the queue is listed.</li>
 *   <li><strong>An end date that is wrong by a few days, or that changes between hosts.</strong> The month
 *       end was resolved against something other than the injected {@link Clock}. Remedy: no
 *       {@code LocalDate.now()} without the clock, and no reliance on the default zone; the clock carries
 *       the zone.</li>
 *   <li><strong>A custom date rejected with {@code Start Date - Not a valid date...} that the legacy
 *       system accepted.</strong> A severity was treated as failure without checking the message number.
 *       Remedy: {@code '2513'} is success on both calls.</li>
 *   <li><strong>Two or more validation messages for one request.</strong> Impossible in the source and a
 *       defect here: every send is terminal, so exactly one outcome may be produced. Remedy: raise on the
 *       first failure and never accumulate.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable after construction and safe to share. All six fields are {@code private final} and none is
 * reassigned. Every value the source held in working storage - the error flag, the report name, the current
 * date area, the assembled dates, the loop index, the card buffer, the erase flag and the loop control flag
 * - is <strong>method local</strong> here and never a field. The source itself corroborates that this is
 * required rather than merely tidy: {@code POPULATE-HEADER-INFO} re-reads {@code FUNCTION CURRENT-DATE}
 * into the very {@code WS-CURDATE-DATA} area the monthly computation has already mutated
 * ({@code app/cbl/CORPT00C.cbl:L611}), which is harmless only because the send is terminal and the period
 * has already been captured. Held in a field, that overwrite would corrupt concurrent requests.
 *
 * @see com.cardemo.model.dto.ReportRequest
 * @see com.cardemo.service.shared.DateValidationService
 */
@Service
public class ReportSubmissionService {

    /**
     * The structured sink that replaces the two {@code DISPLAY} statements this program contains.
     *
     * <p>The whole corpus has no instrumentation beyond {@code DISPLAY} to SYSOUT, and this program uses it
     * exactly twice: {@code DISPLAY 'PROCESS ENTER KEY'} at {@code app/cbl/CORPT00C.cbl:L210}, which is a
     * trace marker, and the response code diagnostic at {@code :L529}, which is the only instrumentation on
     * the publish failure path. Both survive as log events at levels matching their intent, which is the
     * Rule 1 Clause A observability requirement discharged against the two sites the source actually has.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReportSubmissionService.class);

    /**
     * The stand-in for a COBOL {@code MOVE SPACES}, matching the convention the sibling services already
     * established.
     *
     * <p>{@code null} is deliberately not used. {@code MOVE SPACES} produces a present-but-empty
     * fixed-width field, whereas {@code MOVE LOW-VALUES} produces an absent one, and this program
     * distinguishes the two throughout - every selector test at {@code app/cbl/CORPT00C.cbl:L213},
     * {@code :L239} and {@code :L256} and every blank test at {@code :L259-L300} reads
     * {@code NOT = SPACES AND LOW-VALUES}, so both states must remain expressible. The empty string carries
     * "present and blank"; {@code null} carries "absent".
     */
    private static final String SPACES = "";

    /**
     * The transaction identifier, {@code WS-TRANID PIC X(04) VALUE 'CR00'} at
     * {@code app/cbl/CORPT00C.cbl:L37}, rendered into the header at {@code :L619}.
     *
     * <p>It is also the endpoint identity: {@code DEFINE TRANSACTION(CR00) ... PROGRAM(CORPT00C)} at
     * {@code app/csd/CARDDEMO.CSD:L409-L410}.
     */
    private static final String TRANSACTION_ID = "CR00";

    /**
     * The program name, {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} at
     * {@code app/cbl/CORPT00C.cbl:L38}, rendered into the header at {@code :L620}.
     */
    private static final String PROGRAM_NAME = "CORPT00C";

    /**
     * The first header title, {@code CCDA-TITLE01} from {@code app/cpy/COTTL01Y.cpy}, moved to
     * {@code TITLE01O} at {@code app/cbl/CORPT00C.cbl:L616}.
     *
     * <p>The forty characters are reproduced exactly as the copybook declares them, leading and trailing
     * blanks included, because the field is {@code PIC X(40)} and the blanks are the centring.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * The second header title, {@code CCDA-TITLE02} from {@code app/cpy/COTTL01Y.cpy}, moved to
     * {@code TITLE02O} at {@code app/cbl/CORPT00C.cbl:L617}.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * The sign-on program, the default navigation target of {@code RETURN-TO-PREV-SCREEN}.
     *
     * <p>Named twice: at {@code app/cbl/CORPT00C.cbl:L173} when the program is entered with no COMMAREA,
     * and again at {@code :L543} as the fallback when {@code CDEMO-TO-PROGRAM} arrives blank.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * The main menu program, the navigation target of the PF3 arm at
     * {@code app/cbl/CORPT00C.cbl:L187-L189}.
     */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * The monthly report name, {@code MOVE 'Monthly' TO WS-REPORT-NAME} at
     * {@code app/cbl/CORPT00C.cbl:L214}.
     *
     * <p>{@code WS-REPORT-NAME} is {@code PIC X(10)} at {@code :L58}, so the stored value is blank-padded.
     * Every use of it composes through {@code DELIMITED BY SPACE}, which stops at the first blank, so the
     * padding never reaches a message. The unpadded form is therefore the correct constant, and mixed case
     * with a capital initial is what the source literal carries.
     */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    /**
     * The yearly report name, {@code MOVE 'Yearly' TO WS-REPORT-NAME} at
     * {@code app/cbl/CORPT00C.cbl:L240}.
     */
    private static final String REPORT_NAME_YEARLY = "Yearly";

    /**
     * The custom report name, {@code MOVE 'Custom' TO WS-REPORT-NAME} at
     * {@code app/cbl/CORPT00C.cbl:L433}.
     *
     * <p>Note the position of that move: it is assigned <em>after</em> both date validations have passed,
     * not when the custom selector is recognised, so a rejected custom range never carries a report name at
     * all.
     */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    /**
     * The cursor marker for the monthly selector, {@code MONTHLYL} of the symbolic map.
     *
     * <p>{@code MOVE -1 TO MONTHLYL} is the CICS idiom for "place the cursor here". This program uses it at
     * {@code app/cbl/CORPT00C.cbl:L180} on first entry, {@code :L192} on an unrecognised key, {@code :L441}
     * when no report type was selected, {@code :L453} on the success screen and - notably - {@code :L533}
     * on a publish failure, where the cursor lands on the monthly selector and <strong>not</strong> on the
     * confirmation field.
     */
    private static final String CURSOR_MONTHLY = "MONTHLYL";

    /**
     * The cursor marker for the start-date month, {@code SDTMML}, used at
     * {@code app/cbl/CORPT00C.cbl:L264}, {@code :L336}, and {@code :L403}.
     *
     * <p>{@code :L403} is the one worth noting: a whole-date validation failure on the start date parks the
     * cursor on the <em>month</em> component, because the month is where a corrected entry begins.
     */
    private static final String CURSOR_START_MONTH = "SDTMML";

    /**
     * The cursor marker for the start-date day, {@code SDTDDL}, used at
     * {@code app/cbl/CORPT00C.cbl:L271} and {@code :L345}.
     */
    private static final String CURSOR_START_DAY = "SDTDDL";

    /**
     * The cursor marker for the start-date year, {@code SDTYYYYL}, used at
     * {@code app/cbl/CORPT00C.cbl:L278} and {@code :L353}.
     */
    private static final String CURSOR_START_YEAR = "SDTYYYYL";

    /**
     * The cursor marker for the end-date month, {@code EDTMML}, used at
     * {@code app/cbl/CORPT00C.cbl:L285}, {@code :L362}, and {@code :L423}.
     *
     * <p>As with the start date, {@code :L423} parks a whole-date failure on the month component rather
     * than on the year.
     */
    private static final String CURSOR_END_MONTH = "EDTMML";

    /**
     * The cursor marker for the end-date day, {@code EDTDDL}, used at
     * {@code app/cbl/CORPT00C.cbl:L292} and {@code :L371}.
     */
    private static final String CURSOR_END_DAY = "EDTDDL";

    /**
     * The cursor marker for the end-date year, {@code EDTYYYYL}, used at
     * {@code app/cbl/CORPT00C.cbl:L299} and {@code :L379}.
     */
    private static final String CURSOR_END_YEAR = "EDTYYYYL";

    /**
     * The cursor marker for the confirmation gate, {@code CONFIRML}, used at
     * {@code app/cbl/CORPT00C.cbl:L472} on a blank confirmation and {@code :L492} on an unrecognised one.
     *
     * <p>The negative arm at {@code :L480-L483} sets no cursor at all, which is why this constant appears
     * on two of the four confirmation states and not on three.
     */
    private static final String CURSOR_CONFIRM = "CONFIRML";

    /**
     * {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy:L20-L21}, moved to {@code WS-MESSAGE}
     * at {@code app/cbl/CORPT00C.cbl:L193}.
     *
     * <p>This is a shared constant rather than an inline literal, which is why the source references it by
     * name. The copybook declares it {@code PIC X(50)} with trailing blanks that pad the literal to the
     * field width; the unpadded text is reproduced here because the trailing blanks are padding and not
     * content, and the target field {@code ERRMSGO} is {@code PIC X(78)}.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** {@code app/cbl/CORPT00C.cbl:L261}, the blank start-date month. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** {@code app/cbl/CORPT00C.cbl:L268}, the blank start-date day. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** {@code app/cbl/CORPT00C.cbl:L275}, the blank start-date year. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** {@code app/cbl/CORPT00C.cbl:L282}, the blank end-date month. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** {@code app/cbl/CORPT00C.cbl:L289}, the blank end-date day. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** {@code app/cbl/CORPT00C.cbl:L296}, the blank end-date year. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** {@code app/cbl/CORPT00C.cbl:L331}, the out-of-range start-date month. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** {@code app/cbl/CORPT00C.cbl:L340}, the out-of-range start-date day. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    /** {@code app/cbl/CORPT00C.cbl:L348}, the non-numeric start-date year. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** {@code app/cbl/CORPT00C.cbl:L357}, the out-of-range end-date month. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    /** {@code app/cbl/CORPT00C.cbl:L366}, the out-of-range end-date day. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    /** {@code app/cbl/CORPT00C.cbl:L374}, the non-numeric end-date year. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** {@code app/cbl/CORPT00C.cbl:L400}, the start date rejected as a whole by the date validator. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** {@code app/cbl/CORPT00C.cbl:L420}, the end date rejected as a whole by the date validator. */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** {@code app/cbl/CORPT00C.cbl:L438}, the {@code WHEN OTHER} arm with no selector supplied. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /**
     * The opening fragment of the blank-confirmation prompt, {@code app/cbl/CORPT00C.cbl:L466}, composed
     * {@code DELIMITED BY SIZE} so every character including the trailing blank is emitted.
     */
    private static final String MSG_CONFIRM_PREFIX = "Please confirm to print the ";

    /**
     * The closing fragment of the blank-confirmation prompt, {@code app/cbl/CORPT00C.cbl:L469}, also
     * {@code DELIMITED BY SIZE}, so the leading blank before {@code report} is part of the literal.
     */
    private static final String MSG_CONFIRM_SUFFIX = " report...";

    /**
     * The opening delimiter of the unrecognised-confirmation message, {@code app/cbl/CORPT00C.cbl:L486}: a
     * single double quote, {@code DELIMITED BY SIZE}.
     */
    private static final String MSG_INVALID_CONFIRM_PREFIX = "\"";

    /**
     * The closing fragment of the unrecognised-confirmation message,
     * {@code app/cbl/CORPT00C.cbl:L488-L489}, which carries the second double quote.
     */
    private static final String MSG_INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /**
     * The success fragment, {@code app/cbl/CORPT00C.cbl:L450-L451}.
     *
     * <p>Two spacing details are load-bearing and are reproduced exactly: the literal opens with a blank,
     * because the report name is concatenated immediately before it, and it carries a blank between
     * {@code printing} and the three dots, which the other nineteen messages do not.
     */
    private static final String MSG_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /**
     * The publish-failure message, {@code app/cbl/CORPT00C.cbl:L531-L532}.
     *
     * <p><strong>Byte exact, three trailing periods.</strong> The parity gates compare this string
     * literally, so altering it in any way - including normalising the three periods to an ellipsis, or
     * changing the capitalisation of {@code Write}, or expanding {@code TDQ} - is a Blocker.
     */
    private static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    /**
     * The severity code that the date validator returns for an accepted date,
     * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'} at {@code app/cbl/CORPT00C.cbl:L396} and {@code :L416}.
     *
     * <p>Four characters, because {@code CSUTLDTC-RESULT-SEV-CD} is {@code PIC X(04)} at {@code :L133}.
     */
    private static final String SEVERITY_ACCEPTED = "0000";

    /**
     * The message number that is tolerated despite a non-zero severity,
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} at {@code app/cbl/CORPT00C.cbl:L399} and
     * {@code :L419}.
     *
     * <p>This is the {@code FC-UNSUPP-RANGE} condition, carried by the date validator as
     * {@code FeedbackCondition.FC_UNSUPP_RANGE(3, 2513, "Unsupp. Range  ")}. The exemption applies to
     * <strong>both</strong> calls, and omitting it on either would reject dates the source accepts.
     */
    private static final String SEVERITY_TOLERATED_MESSAGE_NUMBER = "2513";

    /**
     * The month upper bound, the character literal {@code '12'} at {@code app/cbl/CORPT00C.cbl:L330} and
     * {@code :L356}.
     *
     * <p>The source compares {@code SDTMMI &gt; '12'}, an <strong>alphanumeric</strong> comparison of two
     * two-character fields, not a numeric one. There is no lower bound anywhere, so {@code '00'} passes.
     */
    private static final String MONTH_UPPER_BOUND = "12";

    /**
     * The day upper bound, the character literal {@code '31'} at {@code app/cbl/CORPT00C.cbl:L339} and
     * {@code :L365}, compared the same alphanumeric way and with the same absent lower bound.
     */
    private static final String DAY_UPPER_BOUND = "31";

    /**
     * The width of the month and day range components, {@code SDTMMI} and {@code SDTDDI} being
     * {@code PIC X(2)} at {@code app/cpy-bms/CORPT00.CPY:L78} and {@code :L84}.
     *
     * <p>It is also the digit count of {@code WS-NUM-99 PIC 99} at {@code app/cbl/CORPT00C.cbl:L74}, which
     * is what makes the normalisation round trip zero-pad to exactly two characters.
     */
    private static final int MONTH_DAY_WIDTH = 2;

    /**
     * The width of the year range components, {@code SDTYYYYI} and {@code EDTYYYYI} being
     * {@code PIC X(4)} at {@code app/cpy-bms/CORPT00.CPY:L90} and {@code :L108}, matching the digit count
     * of {@code WS-NUM-9999 PIC 9999} at {@code app/cbl/CORPT00C.cbl:L75}.
     */
    private static final int YEAR_WIDTH = 4;

    /**
     * The width of the confirmation gate, {@code CONFIRMI} being {@code PIC X(1)} at
     * {@code app/cpy-bms/CORPT00.CPY:L114}.
     *
     * <p>Reproducing the declared width is what bounds the value echoed back inside double quotes at
     * {@code app/cbl/CORPT00C.cbl:L486-L489}: a one-character field cannot hold more than one character, so
     * the message cannot be lengthened by a caller. This is a fixed-width field contract, not an invented
     * guard.
     */
    private static final int CONFIRMATION_WIDTH = 1;

    /**
     * January, from {@code MOVE '01' TO WS-START-DATE-MM} at {@code app/cbl/CORPT00C.cbl:L245}, which the
     * yearly arm applies to the start month.
     *
     * <p>This is also the month the monthly arm resets to when the added month overflows twelve at
     * {@code :L227}, though the Java form of that arm reaches the same result through month arithmetic and
     * never names the constant.
     */
    private static final int FIRST_MONTH_OF_YEAR = 1;

    /**
     * The first day of a month, from {@code MOVE '01' TO WS-START-DATE-DD} at
     * {@code app/cbl/CORPT00C.cbl:L219} for the monthly arm and {@code :L246} for the yearly arm, and from
     * {@code MOVE 1 TO WS-CURDATE-DAY} at {@code :L223}, the move that discards today's day of month.
     */
    private static final int FIRST_DAY_OF_MONTH = 1;

    /** December, from {@code MOVE '12' TO WS-END-DATE-MM} at {@code app/cbl/CORPT00C.cbl:L250}. */
    private static final int LAST_MONTH_OF_YEAR = 12;

    /**
     * The last day of December, from {@code MOVE '31' TO WS-END-DATE-DD} at
     * {@code app/cbl/CORPT00C.cbl:L251}.
     *
     * <p>A fixed literal in the source and a fixed literal here, because December's length never varies.
     * This is the <em>only</em> place a month length is written down, and it is written down because the
     * source writes it down. The monthly arm derives its month end arithmetically and hard-codes nothing.
     */
    private static final int LAST_DAY_OF_DECEMBER = 31;

    /**
     * The number of eighty-byte card images the source carries,
     * {@code app/cbl/CORPT00C.cbl:L81-L127}: fourteen plain filler literals plus the three named groups.
     *
     * <p>Recorded as a constant so the count is asserted somewhere executable rather than only in prose,
     * and referenced by the diagnostic on the publish path. See the class documentation for the enumeration
     * and for the Medium-severity correction of the figure eighteen.
     */
    private static final int JOB_CARD_COUNT = 17;

    /**
     * The iteration bound of the submission loop, {@code UNTIL WS-IDX &gt; 1000} at
     * {@code app/cbl/CORPT00C.cbl:L498}, which matches {@code OCCURS 1000 TIMES} at {@code :L127}.
     *
     * <p>Documented rather than executed. The Java form publishes once, so it never walks the 983 unused
     * slots the COBOL table declares; iterating them would be the obvious inefficiency Rule 1 Clause A
     * forbids, and the loop could not reach them anyway because the terminator card stops it at 17.
     */
    private static final int JOB_LINE_LIMIT = 1000;

    /**
     * The declared length of one card, {@code JCL-RECORD PIC X(80)} at {@code app/cbl/CORPT00C.cbl:L79}, and
     * independently {@code RECORDSIZE(80) RECORDFORMAT(FIXED)} on the queue definition at
     * {@code app/csd/CARDDEMO.CSD:L502-L503}.
     *
     * <p>This is the provenance of the message shape: the queue accepted fixed eighty-byte records, which
     * is what fixes the typed message to the three fields it carries and no more.
     */
    private static final int JOB_CARD_LENGTH = 80;

    /**
     * The terminator card, {@code IF JCL-RECORD = '/*EOF'} at {@code app/cbl/CORPT00C.cbl:L502}, whose
     * literal is the seventeenth card at {@code :L124-L125}.
     *
     * <p>Recorded because the loop's ordering property turns on it: the card that <em>sets</em> the
     * end-of-loop flag is still written, since {@code PERFORM VARYING ... UNTIL} re-tests only at the top of
     * the following iteration.
     */
    private static final String JOB_TERMINATOR_CARD = "/*EOF";

    /**
     * The affirmative confirmation in upper case, {@code WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'} at
     * {@code app/cbl/CORPT00C.cbl:L478}.
     */
    private static final String CONFIRM_YES_UPPER = "Y";

    /** The affirmative confirmation in lower case, the second alternative at {@code app/cbl/CORPT00C.cbl:L478}. */
    private static final String CONFIRM_YES_LOWER = "y";

    /**
     * The negative confirmation in upper case, {@code WHEN CONFIRMI OF CORPT0AI = 'N' OR 'n'} at
     * {@code app/cbl/CORPT00C.cbl:L480}.
     */
    private static final String CONFIRM_NO_UPPER = "N";

    /** The negative confirmation in lower case, the second alternative at {@code app/cbl/CORPT00C.cbl:L480}. */
    private static final String CONFIRM_NO_LOWER = "n";

    /**
     * The low-values byte, the second half of every {@code NOT = SPACES AND LOW-VALUES} test in this
     * program.
     *
     * <p>{@code MOVE LOW-VALUES TO CORPT0AO} at {@code app/cbl/CORPT00C.cbl:L179} fills the map with this
     * byte on first entry, so a field arriving as a single {@code 0x00} is the source's "never populated"
     * state and must test as absent rather than as data.
     */
    private static final char LOW_VALUES = '\u0000';

    /**
     * The date separator, the {@code FILLER X(01) VALUE '-'} items of {@code WS-START-DATE} at
     * {@code app/cbl/CORPT00C.cbl:L62} and {@code :L64} and of {@code WS-END-DATE} at {@code :L68} and
     * {@code :L70}.
     */
    private static final String DATE_SEPARATOR = "-";

    /**
     * The decimal point the currency-aware numeric intrinsic recognises under the default
     * {@code DECIMAL-POINT} convention.
     *
     * <p>It matters to the normalisation stage because the receiving items are {@code PIC 99} and
     * {@code PIC 9999}, which have no decimal places, so a fractional part is discarded rather than rounded
     * or carried.
     */
    private static final char DECIMAL_POINT = '.';

    /** The lowest ASCII digit, the lower bound of a COBOL {@code IS NUMERIC} test on an alphanumeric item. */
    private static final char DIGIT_ZERO = '0';

    /** The highest ASCII digit, the upper bound of a COBOL {@code IS NUMERIC} test. */
    private static final char DIGIT_NINE = '9';

    /**
     * The radix of the zoned-decimal receiving items, used by the normalisation stage to derive the modulus
     * that reproduces high-order truncation into a {@code PIC 9(n)} field.
     */
    private static final long DECIMAL_RADIX = 10L;

    /**
     * The stand-in for a reason code the publisher did not supply, used in the diagnostic that reproduces
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at {@code app/cbl/CORPT00C.cbl:L529}.
     *
     * <p>The source always had a {@code RESP2} value because CICS always set one. A publisher failure may
     * carry no endpoint, and Rule 1 Clause F asks that missing information be stated rather than guessed at,
     * so the absence is named rather than rendered as an empty field or a fabricated zero.
     */
    private static final String REASON_UNAVAILABLE = "unavailable";

    /**
     * The header date rendering, {@code MM/DD/YY}.
     *
     * <p>{@code app/cbl/CORPT00C.cbl:L622-L626} assembles it from
     * {@code WS-CURDATE-MONTH}, {@code WS-CURDATE-DAY} and {@code WS-CURDATE-YEAR(3:2)} into the
     * {@code WS-CURDATE-MM-DD-YY} group of {@code app/cpy/CSDAT01Y.cpy}, whose two {@code FILLER} items
     * carry the literal solidus. The reference modification {@code (3:2)} is what makes the year two digits.
     * {@code Locale.ROOT} keeps the rendering identical on every host.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * The header time rendering, {@code HH:MM:SS}.
     *
     * <p>{@code app/cbl/CORPT00C.cbl:L627-L628} assembles it into the {@code WS-CURTIME-HH-MM-SS} group of
     * {@code app/cpy/CSDAT01Y.cpy}, whose {@code FILLER} items carry the literal colon. The group stops at
     * seconds; the millisecond subfield the copybook also declares is not rendered.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The ten-character dashed date rendering the submitted parameters carry.
     *
     * <p>{@code WS-START-DATE} at {@code app/cbl/CORPT00C.cbl:L60-L65} and {@code WS-END-DATE} at
     * {@code :L66-L71} are each a group of {@code X(04)}, a {@code FILLER X(01) VALUE '-'}, an
     * {@code X(02)}, a second {@code FILLER X(01) VALUE '-'} and an {@code X(02)}: ten bytes with the
     * separators built into the layout. <strong>The dashed form is mandatory and a compact undashed form is
     * a High-severity divergence</strong>, because it is what the date validator is asked to parse against
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} at {@code :L72} and what the batch tier receives.
     *
     * <p>{@code uuuu} is used rather than {@code yyyy} so the pattern needs no era to resolve, which keeps
     * the formatter strict-resolvable as well as format-safe.
     */
    private static final DateTimeFormatter PARAMETER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);

    /**
     * How the program was entered, replacing the {@code EIBCALEN} test at
     * {@code app/cbl/CORPT00C.cbl:L172} and the {@code CDEMO-PGM-REENTER} test at {@code :L177}.
     *
     * <p>These two tests are the whole of the pseudo-conversational protocol, and per the plan's COMMAREA
     * mapping the re-enter flag has no Java counterpart: it exists only to tell a single CICS program which
     * half of a two-turn conversation it is executing. Stateless HTTP expresses the same split as two
     * requests, so the flag becomes this enumeration at the boundary and disappears entirely below it.
     */
    public enum EntryMode {

        /**
         * {@code EIBCALEN = 0} at {@code app/cbl/CORPT00C.cbl:L172}: the program was reached with no
         * communication area at all, which cannot happen through the normal transaction chain and is
         * treated as an unauthenticated arrival.
         */
        NO_COMMAREA,

        /**
         * {@code NOT CDEMO-PGM-REENTER} at {@code app/cbl/CORPT00C.cbl:L177}: the first turn, which paints
         * an empty form and waits.
         */
        FIRST_ENTRY,

        /**
         * The else arm at {@code app/cbl/CORPT00C.cbl:L182}: a subsequent turn, which reads the map and acts
         * on the attention identifier.
         */
        RE_ENTER
    }

    /**
     * The attention identifier, {@code EVALUATE EIBAID} at {@code app/cbl/CORPT00C.cbl:L184-L195}.
     *
     * <p><strong>Exactly three outcomes.</strong> The cascade is written inline in {@code MAIN-PARA} rather
     * than delegated, and {@code app/cpy/CSSTRPFY.cpy} - the procedural copybook that stores the key
     * elsewhere in the corpus - is <em>not</em> among the eight {@code COPY} members this program takes.
     * That is the independent confirmation that the paragraph count is ten and not eleven.
     */
    public enum AttentionIdentifier {

        /** {@code WHEN DFHENTER} at {@code app/cbl/CORPT00C.cbl:L185}: process the form. */
        ENTER,

        /** {@code WHEN DFHPF3} at {@code app/cbl/CORPT00C.cbl:L187}: leave for the main menu. */
        PF3,

        /**
         * {@code WHEN OTHER} at {@code app/cbl/CORPT00C.cbl:L190}: any other key, which produces the shared
         * invalid-key message and parks the cursor on the monthly selector.
         */
        OTHER
    }

    /**
     * The resolved reporting period and, unchanged, the message the batch tier receives.
     *
     * <p>One type serves both roles because in the source they are the same three values. {@code
     * WS-REPORT-NAME} at {@code app/cbl/CORPT00C.cbl:L58} and the two ten-character parameter dates are
     * exactly what the three variable job cards carry - {@code FILLER-1} at {@code :L103-L107},
     * {@code FILLER-2} at {@code :L108-L112} and {@code FILLER-3} at {@code :L117-L121} - so introducing a
     * second identically shaped record for the wire would be the duplication Rule 1 Clause C forbids.
     *
     * <p>The collapse this type embodies is worth being precise about. Each date was injected
     * <strong>twice</strong> in the source, once into the sort symbol card and once into the date parameter
     * card, giving four injection points across the deck. A typed message needs each value once, so the two
     * points per date become one field per date, and the consumer on the batch side derives both uses from
     * it.
     *
     * <p>The dates are carried as {@code String} deliberately. They are ten-character fixed-width text in
     * the source, they are validated as text by a service that reports a severity code rather than by a
     * parser, and re-deriving them as temporal types would discard the distinction between a value the
     * validator accepted under the tolerated message number and one it accepted outright.
     *
     * @param reportName  {@code Monthly}, {@code Yearly} or {@code Custom}, unpadded, exactly as the three
     *                    source literals spell them
     * @param startDate   the ten-character dashed start date, {@code PARM-START-DATE-1} and
     *                    {@code PARM-START-DATE-2} collapsed to one field
     * @param endDate     the ten-character dashed end date, {@code PARM-END-DATE-1} and
     *                    {@code PARM-END-DATE-2} collapsed to one field
     */
    public record JobSubmissionMessage(String reportName, String startDate, String endDate) {

        /**
         * Rejects an incompletely populated message at construction.
         *
         * <p>The source could not express a partially built deck: the parameter fields are fixed-width
         * areas that always hold something, and the report name is assigned on every reachable arm before
         * the submission paragraph is performed. Requiring all three here reproduces that, and does so at
         * the one point where the omission would otherwise reach the queue.
         */
        public JobSubmissionMessage {
            Objects.requireNonNull(reportName, "reportName must not be null");
            Objects.requireNonNull(startDate, "startDate must not be null");
            Objects.requireNonNull(endDate, "endDate must not be null");
        }
    }

    /**
     * One screen outcome: the map area as it would have been sent, plus the three pieces of presentation
     * state the map carried alongside it.
     *
     * <p>{@link ReportRequest} doubles as the map area, which is what the source does too - {@code CORPT0AI}
     * and {@code CORPT0AO} are the input and output views of one symbolic map, and
     * {@code app/cpy-bms/CORPT00.CPY} declares both from the same seventeen field definitions. Echoing the
     * request type back therefore reproduces the map without introducing a second field-for-field copy of
     * the same contract.
     *
     * <p>Only five of the twenty-two send sites produce one of these. The other seventeen raise a
     * {@link ValidationException} and the eighteenth raises a {@link FileAccessException}, because a
     * validation failure in a REST target is a failed request rather than a repainted screen. See the class
     * documentation for why that split is exhaustive.
     *
     * @param form              the map area, with the six header fields freshly populated and
     *                          {@code errorMessage} carrying what {@code WS-MESSAGE} held
     * @param errorFlagOn       {@code WS-ERR-FLG}, {@code true} when the screen is a re-prompt rather than a
     *                          result
     * @param successHighlight  {@code MOVE DFHGREEN TO ERRMSGC} at {@code app/cbl/CORPT00C.cbl:L448}: the
     *                          message is a success notice and not an error, which is the one place this
     *                          program distinguishes the two
     * @param cursorField       the symbolic-map length field that received {@code -1}, or {@code null} where
     *                          the source set no cursor - which the negative confirmation arm at
     *                          {@code :L480-L483} deliberately does not
     * @param navigationTarget  the program {@code RETURN-TO-PREV-SCREEN} would have transferred to, or
     *                          {@code null} when the screen is redisplayed rather than left
     */
    public record ReportSubmissionScreen(
            ReportRequest form,
            boolean errorFlagOn,
            boolean successHighlight,
            String cursorField,
            String navigationTarget) {

        /**
         * Requires the map area, the one component that is never absent.
         *
         * <p>{@code cursorField} and {@code navigationTarget} are both legitimately {@code null}, each
         * because a specific source arm sets neither, so neither may be required here.
         */
        public ReportSubmissionScreen {
            Objects.requireNonNull(form, "form must not be null");
        }
    }

    /**
     * The publisher that replaces {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at
     * {@code app/cbl/CORPT00C.cbl:L517-L523}.
     *
     * <p>Injected, never constructed. {@code com.cardemo.config.AwsConfig} owns the template, the client
     * beneath it and the LocalStack endpoint override, so this bean has no way to reach a live endpoint and
     * no credential of any kind passes through it. Building a client here would also duplicate that
     * configuration, and would put an environment-specific endpoint inside business code, which Rule 1
     * Clause C forbids.
     */
    private final SqsTemplate sqsTemplate;

    /**
     * The date validator that replaces {@code CALL 'CSUTLDTC'} at
     * {@code app/cbl/CORPT00C.cbl:L392-L394} and {@code :L412-L414}.
     *
     * <p>A mandatory collaborator: the custom period cannot be resolved without it, because the six
     * component checks establish only that the parts are in range and say nothing about whether the
     * assembled date exists. It reports outcomes as a return value rather than by throwing, which is what
     * lets the {@code '2513'} exemption be expressed at all - an exception carries no message number to
     * exempt.
     */
    private final DateValidationService dateValidationService;

    /**
     * The time source that replaces {@code FUNCTION CURRENT-DATE} at
     * {@code app/cbl/CORPT00C.cbl:L215}, {@code :L241} and {@code :L611}.
     *
     * <p>Injected rather than ambient, and this is the bean where that matters most. The monthly period's
     * end date is derived from the current month, so an unclocked reading would make the boundary depend on
     * the host's zone and would be unassertable under test on any day but one. The clock's zone carries the
     * local-time semantics {@code FUNCTION CURRENT-DATE} has on the mainframe.
     */
    private final Clock clock;

    /**
     * The physical queue the publish targets, bound from
     * {@code carddemo.aws.sqs.report-queue} in {@code src/main/resources/application.yml}, which in turn
     * indirects to the {@code CARDDEMO_SQS_REPORT_QUEUE} environment variable.
     *
     * <p>No default is supplied deliberately. An unset variable fails property resolution and therefore
     * fails startup, which is preferable to a bean that starts and then publishes every report request into
     * a queue nobody consumes.
     */
    private final String reportQueueName;

    /**
     * The logical queue name, bound from {@code carddemo.aws.sqs.report-queue-logical-name} and fixed by the
     * migration at {@code carddemo-report-jobs}.
     *
     * <p>Used in diagnostics only. It exists so a log line names the queue the documentation describes
     * rather than whatever physical name, suffix or account-qualified form a given environment happens to
     * carry, which is what makes a failure message useful across environments.
     */
    private final String reportQueueLogicalName;

    /**
     * The FIFO message group, bound from {@code carddemo.aws.sqs.report-message-group-id}.
     *
     * <p><strong>Deterministic and configured, never generated.</strong> The queue this replaces was
     * declared {@code DISPOSITION(MOD)} at {@code app/csd/CARDDEMO.CSD:L503} - strict sequential append to a
     * single stream - so one fixed group reproduces its ordering exactly. A generated identifier would place
     * each submission in its own group, which forfeits ordering between submissions and does so
     * unreproducibly, and is therefore a High-severity divergence rather than a stylistic choice.
     */
    private final String reportMessageGroupId;

    /**
     * Assembles the bean.
     *
     * <p>Constructor injection throughout, with no field annotation, no setter and no lookup, so the type is
     * constructible in a unit test with three test doubles and three strings and needs no application
     * context. Every argument is required; a missing collaborator fails fast at construction with a message
     * naming it, rather than at the first request with a null dereference.
     *
     * @param sqsTemplate             the publisher owned by {@code com.cardemo.config.AwsConfig}
     * @param dateValidationService   the validator standing in for {@code CALL 'CSUTLDTC'}
     * @param clock                   the time source standing in for {@code FUNCTION CURRENT-DATE}
     * @param reportQueueName         the physical queue name, from
     *                                {@code carddemo.aws.sqs.report-queue}
     * @param reportQueueLogicalName  the logical queue name, from
     *                                {@code carddemo.aws.sqs.report-queue-logical-name}
     * @param reportMessageGroupId    the deterministic FIFO group, from
     *                                {@code carddemo.aws.sqs.report-message-group-id}
     * @throws NullPointerException if any argument is {@code null}
     */
    public ReportSubmissionService(
            final SqsTemplate sqsTemplate,
            final DateValidationService dateValidationService,
            final Clock clock,
            @Value("${carddemo.aws.sqs.report-queue}") final String reportQueueName,
            @Value("${carddemo.aws.sqs.report-queue-logical-name}") final String reportQueueLogicalName,
            @Value("${carddemo.aws.sqs.report-message-group-id}") final String reportMessageGroupId) {
        this.sqsTemplate = Objects.requireNonNull(sqsTemplate, "sqsTemplate must not be null");
        this.dateValidationService =
                Objects.requireNonNull(dateValidationService, "dateValidationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.reportQueueName = Objects.requireNonNull(reportQueueName, "reportQueueName must not be null");
        this.reportQueueLogicalName =
                Objects.requireNonNull(reportQueueLogicalName, "reportQueueLogicalName must not be null");
        this.reportMessageGroupId =
                Objects.requireNonNull(reportMessageGroupId, "reportMessageGroupId must not be null");
    }

    /**
     * Opens the report form: the first turn of the conversation.
     *
     * <p>Reproduces the first-entry arm at {@code app/cbl/CORPT00C.cbl:L177-L181}, which sets the re-enter
     * flag, moves {@code LOW-VALUES} over the whole output map, parks the cursor on the monthly selector and
     * sends. Nothing is validated and nothing is published; the six header fields are populated from the
     * clock and every other field comes back absent.
     *
     * <p>The header fields are the only populated ones, and that is not an accident of the translation:
     * {@code MOVE LOW-VALUES TO CORPT0AO} at {@code :L179} clears the map, and
     * {@code PERFORM POPULATE-HEADER-INFO} at {@code :L558} then refills the header inside the send. So the
     * empty form carries a transaction name, a program name, two titles, a date and a time, and nothing
     * else.
     *
     * @return the empty form, with no message, no error flag and the cursor on the monthly selector
     */
    public ReportSubmissionScreen openReportScreen() {
        return mainPara(EntryMode.FIRST_ENTRY, AttentionIdentifier.ENTER, null);
    }

    /**
     * Handles an arrival with no established context.
     *
     * <p>Reproduces {@code IF EIBCALEN = 0} at {@code app/cbl/CORPT00C.cbl:L172-L174}, where the absence of
     * a communication area means the transaction was started directly rather than reached through the menu,
     * so the program sets the sign-on program as its target and transfers out without painting anything.
     *
     * <p>In the target the equivalent condition is an authenticated identity that cannot be established.
     * The outcome is a navigation instruction rather than an exception, because the source treats it as a
     * routing decision and not as an error: no message is set and no error flag is raised.
     *
     * @return a screen carrying {@code COSGN00C} as its navigation target and no message
     */
    public ReportSubmissionScreen openWithoutContext() {
        return mainPara(EntryMode.NO_COMMAREA, AttentionIdentifier.ENTER, null);
    }

    /**
     * Submits the report form: the second and subsequent turns of the conversation.
     *
     * <p>Reproduces the re-enter arm at {@code app/cbl/CORPT00C.cbl:L182-L195}, which receives the map and
     * dispatches on the attention identifier. Only {@link AttentionIdentifier#ENTER} reaches the period
     * resolution, the confirmation handshake and the publish; {@link AttentionIdentifier#PF3} leaves for the
     * main menu without touching the form, and anything else is rejected with the shared invalid-key
     * message.
     *
     * <p><strong>Exactly one outcome is produced per call.</strong> Every send site in the source is a
     * terminal exit, so the first failing check ends the turn and no second message can be reached. In Java
     * that means the first failure raises immediately and no error list is accumulated; see the class
     * documentation for the proof at {@code :L580}.
     *
     * <p>On success the form is cleared, a green success notice is composed, the cursor returns to the
     * monthly selector, and one message has been published to the report queue. The publish has already
     * happened by the time this method returns normally.
     *
     * @param attentionIdentifier the key the terminal sent; must not be {@code null}
     * @param request             the submitted map area; must not be {@code null}
     * @return the resulting screen: the cleared form with a success notice, or the cleared form with a blank
     *         message when the confirmation was declined, or a navigation instruction for
     *         {@link AttentionIdentifier#PF3}
     * @throws ValidationException  when a period selector is absent, a custom range component is blank or
     *                              out of range, an assembled date is rejected by the validator, the
     *                              confirmation is blank or unrecognised, or the key is not recognised. The
     *                              exception carries the source's own message literal and the symbolic-map
     *                              field the cursor would have been placed on
     * @throws FileAccessException  when the publish fails, carrying the message literal from {@code :L531}
     *                              and the underlying failure as its cause
     * @throws NullPointerException if either argument is {@code null}
     */
    public ReportSubmissionScreen submitScreen(
            final AttentionIdentifier attentionIdentifier, final ReportRequest request) {
        Objects.requireNonNull(attentionIdentifier, "attentionIdentifier must not be null");
        Objects.requireNonNull(request, "request must not be null");
        return mainPara(EntryMode.RE_ENTER, attentionIdentifier, request);
    }

    /**
     * {@code MAIN-PARA}, {@code app/cbl/CORPT00C.cbl:L163-L202}.
     *
     * <p>The entry paragraph. It initialises three flags, clears the message, decides which of the three
     * arrival shapes it is looking at, and - on a re-entry - dispatches on the attention identifier through
     * a cascade written inline rather than delegated.
     *
     * <p>Three source constructs have no Java counterpart and are documented here rather than translated:
     *
     * <ul>
     *   <li>{@code SET TRANSACT-NOT-EOF TO TRUE} at {@code :L166} initialises a condition name on
     *       {@code WS-TRANSACT-EOF}, one of the five working-storage items no statement in the program ever
     *       reads. No field is created for it.</li>
     *   <li>{@code SET SEND-ERASE-YES TO TRUE} at {@code :L167} selects which of the two {@code SEND}
     *       forms {@code SEND-TRNRPT-SCREEN} uses. The flag is set here and never set to its other value
     *       anywhere in the program, so the erase form is always the one taken; the alternative arm at
     *       {@code :L572-L577} is consequently unreachable. Terminal erase has no meaning in an HTTP
     *       response, so the distinction is recorded and dropped.</li>
     *   <li>{@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} at {@code :L176} copies the inbound
     *       communication area into working storage. Under Transformation Rule 7 there is no server-side
     *       session state, so the identity that area carried is a token claim and the navigation fields it
     *       carried are URL segments.</li>
     * </ul>
     *
     * @param entryMode           which arrival shape this is
     * @param attentionIdentifier the key, meaningful only on a re-entry
     * @param submitted           the submitted map area, {@code null} on the two arrivals that read no map
     * @return the resulting screen
     * @throws ValidationException when the key is unrecognised, or when the enter path rejects the form
     * @throws FileAccessException when the enter path reaches the publish and the publish fails
     */
    private ReportSubmissionScreen mainPara(final EntryMode entryMode,
            final AttentionIdentifier attentionIdentifier, final ReportRequest submitted) {

        // :L165-L167 SET ERR-FLG-OFF, TRANSACT-NOT-EOF and SEND-ERASE-YES TO TRUE. The error flag starts
        // off; in the target a failure raises rather than setting a flag, so "off" is the absence of a
        // raised failure and needs no variable. See the class documentation on :L580 for why.
        // :L169-L170 MOVE SPACES TO WS-MESSAGE and to ERRMSGO OF CORPT0AO: both the working-storage message
        // and the map's message field are blanked, so a message left by the previous turn cannot survive.
        final String message = SPACES;

        // :L172-L174 IF EIBCALEN = 0 -> MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, PERFORM RETURN-TO-PREV-SCREEN.
        // The target program is set explicitly here and would in any case be defaulted to the same value by
        // the blank test inside that paragraph at :L542-L544.
        if (entryMode == EntryMode.NO_COMMAREA) {
            return returnToPrevScreen(SIGN_ON_PROGRAM, lowValuesMapArea());
        }

        // :L177-L181 IF NOT CDEMO-PGM-REENTER -> SET CDEMO-PGM-REENTER TO TRUE, MOVE LOW-VALUES TO CORPT0AO,
        // MOVE -1 TO MONTHLYL, PERFORM SEND-TRNRPT-SCREEN. Setting the re-enter flag has no counterpart: the
        // second turn is a second HTTP request and identifies itself by being one.
        if (entryMode == EntryMode.FIRST_ENTRY) {
            return sendTrnrptScreen(lowValuesMapArea(), message, false, false, CURSOR_MONTHLY);
        }

        // :L183 PERFORM RECEIVE-TRNRPT-SCREEN.
        final ReportRequest mapArea = receiveTrnrptScreen(submitted);

        // :L184-L195 EVALUATE EIBAID, three arms and no more.
        return switch (attentionIdentifier) {
            // :L185-L186 WHEN DFHENTER -> PERFORM PROCESS-ENTER-KEY.
            case ENTER -> processEnterKey(mapArea);
            // :L187-L189 WHEN DFHPF3 -> MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM, PERFORM RETURN-TO-PREV-SCREEN.
            case PF3 -> returnToPrevScreen(MAIN_MENU_PROGRAM, mapArea);
            // :L190-L194 WHEN OTHER -> MOVE 'Y' TO WS-ERR-FLG, MOVE -1 TO MONTHLYL,
            // MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE, PERFORM SEND-TRNRPT-SCREEN. The message is the shared
            // constant from app/cpy/CSMSG01Y.cpy and not an inline literal.
            case OTHER -> throw ValidationException.invalidField(CURSOR_MONTHLY, MSG_INVALID_KEY);
        };

        // :L199-L202 EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA) is unreachable in
        // practice: every arm above either transfers control or performs a send, and every send is a terminal
        // exit through :L580. It is the paragraph's syntactic fall-through and carries no behaviour of its
        // own that RETURN-TO-CICS does not already carry.
    }

    /**
     * {@code PROCESS-ENTER-KEY}, {@code app/cbl/CORPT00C.cbl:L208-L460}.
     *
     * <p>The paragraph that resolves the reporting period, submits it, and composes the success notice. It
     * is one {@code EVALUATE TRUE} with four arms followed by a tail, and its 252 source lines are the bulk
     * of the program.
     *
     * <p><strong>The arm order is behaviour.</strong> {@code EVALUATE TRUE} evaluates its {@code WHEN}
     * conditions in written order and takes the first that holds, so monthly at {@code :L213} beats yearly at
     * {@code :L239} beats custom at {@code :L256}. A caller that supplies two selectors gets the earlier one,
     * and that must remain true here. It is also why the three selectors are three independent one-character
     * fields rather than an enumeration: an enumeration cannot represent two selectors at once, and this
     * program can.
     *
     * <p><strong>The submission asymmetry is behaviour too.</strong> Monthly at {@code :L238} and yearly at
     * {@code :L255} perform the submission unconditionally, because neither can fail - both derive their
     * dates from the clock and neither consults the submitted range at all. Custom at {@code :L434-L436}
     * performs it inside {@code IF NOT ERR-FLG-ON}. In the target that guard is reproduced structurally: the
     * custom resolution raises on any failure, so reaching the submission statement <em>is</em> the guard
     * holding. Writing an explicit always-false test instead would fabricate exactly the dead code Rule 1
     * Clause B forbids, whereas the control-flow decision itself - submit only when nothing failed - is
     * preserved intact and cited here.
     *
     * <p>The tail at {@code :L445-L456} runs only when the submission paragraph returned without ending the
     * turn, which is the success path. Its statement order matters: {@code PERFORM INITIALIZE-ALL-FIELDS} at
     * {@code :L447} comes <strong>first</strong> and blanks {@code WS-MESSAGE} at {@code :L646}, and only
     * then is the green notice composed at {@code :L448-L452}. Reversing the two would wipe the notice.
     *
     * @param mapArea the received map area
     * @return the cleared form carrying the green success notice, or the cleared form carrying a blank
     *         message when the confirmation was declined
     * @throws ValidationException when no selector was supplied, or the custom range is rejected, or the
     *                             confirmation is blank or unrecognised
     * @throws FileAccessException when the publish fails
     */
    private ReportSubmissionScreen processEnterKey(final ReportRequest mapArea) {

        // :L210 DISPLAY 'PROCESS ENTER KEY'. One of the program's two DISPLAY statements, and a trace marker
        // rather than a diagnostic, so it is logged at debug.
        LOG.debug("PROCESS ENTER KEY");

        // :L212 EVALUATE TRUE, first match wins.
        final JobSubmissionMessage period;
        if (selectorSupplied(mapArea.monthlySelected())) {
            // :L213-L236 the monthly arm.
            period = monthlyPeriod();
        } else if (selectorSupplied(mapArea.yearlySelected())) {
            // :L239-L253 the yearly arm.
            period = yearlyPeriod();
        } else if (selectorSupplied(mapArea.customSelected())) {
            // :L256-L433 the custom arm, which raises rather than returning on any of its fourteen failures.
            period = customPeriod(mapArea);
        } else {
            // :L437-L442 WHEN OTHER: no selector at all. MOVE 'Select a report type to print report...' TO
            // WS-MESSAGE, MOVE 'Y' TO WS-ERR-FLG, MOVE -1 TO MONTHLYL, PERFORM SEND-TRNRPT-SCREEN.
            throw ValidationException.invalidField(CURSOR_MONTHLY, MSG_SELECT_REPORT_TYPE);
        }

        // :L238 and :L255 unconditionally, :L434-L436 under IF NOT ERR-FLG-ON. Reaching this statement is
        // the guard holding, since every failure above raised.
        final Optional<ReportSubmissionScreen> declined = submitJobToIntrdr(mapArea, period);
        if (declined.isPresent()) {
            // :L481-L483 the confirmation was declined, and that arm ends the turn, so the tail below - the
            // success notice - is never reached. Returning here reproduces the terminal send.
            return declined.get();
        }

        // :L445-L446 IF NOT ERR-FLG-ON, again structural.
        // :L447 PERFORM INITIALIZE-ALL-FIELDS. First, and it blanks the message.
        final ReportRequest cleared = initializeAllFields(mapArea);

        // :L449-L452 STRING WS-REPORT-NAME DELIMITED BY SPACE, ' report submitted for printing ...'
        // DELIMITED BY SIZE, INTO WS-MESSAGE. DELIMITED BY SPACE stops at the first blank of the PIC X(10)
        // report name, which is why the unpadded constant is the correct operand.
        final String notice = period.reportName() + MSG_SUBMITTED_SUFFIX;

        // :L448 MOVE DFHGREEN TO ERRMSGC: a success attribute, not an error one. :L453 MOVE -1 TO MONTHLYL.
        // :L454 PERFORM SEND-TRNRPT-SCREEN.
        return sendTrnrptScreen(cleared, notice, false, true, CURSOR_MONTHLY);
    }

    /**
     * The monthly arm of {@code PROCESS-ENTER-KEY}, {@code app/cbl/CORPT00C.cbl:L213-L238}.
     *
     * <p>An inline stage of that paragraph rather than a paragraph of its own; the source has no label here.
     *
     * <p><strong>The period is the full current calendar month, first day through last day. It is not month
     * to date.</strong> The class documentation carries the six-step proof, the mechanism that makes it work
     * - {@code WS-CURDATE-N REDEFINES WS-CURDATE} at {@code app/cpy/CSDAT01Y.cpy:L23} - and the record of the
     * two secondary sources that state it incorrectly. This method reproduces the source's four steps
     * literally, in order:
     *
     * <ol>
     *   <li>{@code :L223} forces the day of month to 1, discarding today's day.</li>
     *   <li>{@code :L224} adds one month.</li>
     *   <li>{@code :L225-L228} roll the year when the month passes twelve. {@code java.time} month
     *       arithmetic performs this roll intrinsically, which is why no branch appears below: December plus
     *       one month is January of the following year without being told so.</li>
     *   <li>{@code :L229-L230} subtract one day.</li>
     * </ol>
     *
     * <p>Nothing here knows how long a month is. There is no table of month lengths and no leap-year test,
     * exactly as the source has neither - it delegates to the intrinsic date functions and this delegates to
     * {@code java.time}. February of a leap year and February of a common year are therefore both correct
     * without either being special-cased.
     *
     * @return the monthly period, named {@code Monthly}, with both dates rendered as ten dashed characters
     */
    private JobSubmissionMessage monthlyPeriod() {

        // :L215 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. Read once, through the injected clock, and
        // both dates derived from that single reading so the period cannot straddle a midnight.
        final LocalDate today = LocalDate.now(this.clock);

        // :L217-L219 MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY, WS-CURDATE-MONTH TO WS-START-DATE-MM,
        // '01' TO WS-START-DATE-DD. :L223 MOVE 1 TO WS-CURDATE-DAY does the same thing to the shared area.
        final LocalDate periodStart = today.withDayOfMonth(FIRST_DAY_OF_MONTH);

        // :L224-L228 ADD 1 TO WS-CURDATE-MONTH with the twelve-month roll, then :L229-L230
        // DATE-OF-INTEGER(INTEGER-OF-DATE(...) - 1). The first of the next month, less one day, is the last
        // day of this one. :L232-L234 then read the mutated year, month and day back out, which is the step
        // the two incorrect secondary sources overlook.
        final LocalDate periodEnd = periodStart.plusMonths(1L).minusDays(1L);

        // :L214 MOVE 'Monthly' TO WS-REPORT-NAME, and :L220-L221 and :L235-L236 move each date to its pair of
        // parameter targets - the pair that collapses to one field per date here.
        return new JobSubmissionMessage(REPORT_NAME_MONTHLY,
                PARAMETER_DATE_FORMAT.format(periodStart),
                PARAMETER_DATE_FORMAT.format(periodEnd));
    }

    /**
     * The yearly arm of {@code PROCESS-ENTER-KEY}, {@code app/cbl/CORPT00C.cbl:L239-L255}.
     *
     * <p>An inline stage of that paragraph, not a paragraph of its own.
     *
     * <p>The whole current calendar year, from its first day to its last. Unlike the monthly arm this needs
     * no arithmetic at all: {@code :L243-L244} move the current year into both the start and the end year in
     * a single statement, {@code :L245-L246} move the literal {@code '01'} into both the start month and the
     * start day, and {@code :L250-L251} move the literals {@code '12'} and {@code '31'} into the end month
     * and day. Four fixed literals, no month-length question to answer, and December's length written down
     * because the source writes it down.
     *
     * @return the yearly period, named {@code Yearly}, with both dates rendered as ten dashed characters
     */
    private JobSubmissionMessage yearlyPeriod() {

        // :L241 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, read once through the injected clock.
        final int currentYear = LocalDate.now(this.clock).getYear();

        // :L243-L246 the current year, January, the first.
        final LocalDate periodStart = LocalDate.of(currentYear, FIRST_MONTH_OF_YEAR, FIRST_DAY_OF_MONTH);

        // :L243-L244 and :L250-L251 the same year, December, the thirty-first.
        final LocalDate periodEnd = LocalDate.of(currentYear, LAST_MONTH_OF_YEAR, LAST_DAY_OF_DECEMBER);

        // :L240 MOVE 'Yearly' TO WS-REPORT-NAME, and :L247-L248 and :L252-L253 the parameter pairs.
        return new JobSubmissionMessage(REPORT_NAME_YEARLY,
                PARAMETER_DATE_FORMAT.format(periodStart),
                PARAMETER_DATE_FORMAT.format(periodEnd));
    }

    /**
     * The custom arm of {@code PROCESS-ENTER-KEY}, {@code app/cbl/CORPT00C.cbl:L256-L433}.
     *
     * <p>An inline stage of that paragraph, not a paragraph of its own. It runs six stages in a fixed order,
     * each of which is a separate stage helper below so that no single method carries 178 source lines:
     *
     * <ol>
     *   <li>{@code :L258-L303} the six blank tests.</li>
     *   <li>{@code :L305-L327} the six numeric round trips, which zero-pad in place.</li>
     *   <li>{@code :L329-L379} the six range tests.</li>
     *   <li>{@code :L381-L386} assembly into the two dashed ten-character dates.</li>
     *   <li>{@code :L388-L426} the two whole-date validations, start first and end second.</li>
     *   <li>{@code :L429-L433} population of the parameter fields and assignment of the report name.</li>
     * </ol>
     *
     * <p>The order is not interchangeable. Stage 2 rewrites the six components in place, so stage 3 tests the
     * rewritten values and not the submitted ones, and stage 4 assembles from the rewritten values too - a
     * submitted {@code 1} reaches the validator as {@code 01}. Stage 5 must follow stage 3 because a
     * component out of range would otherwise be reported as a whole-date failure with the wrong message and
     * the wrong cursor.
     *
     * <p>Every failure in stages 1, 3 and 5 raises immediately with its own literal and its own field marker,
     * because every one of those fourteen sites is a terminal send in the source. Exactly one of the fourteen
     * can be reported per call.
     *
     * <p>Note where the report name is assigned: {@code :L433}, after both validations have passed. A
     * rejected custom range never carries one.
     *
     * @param mapArea the received map area
     * @return the custom period, named {@code Custom}
     * @throws ValidationException on the first of the fourteen checks that fails
     */
    private JobSubmissionMessage customPeriod(final ReportRequest mapArea) {

        // STAGE 1 :L258-L303.
        checkCustomRangeSupplied(mapArea);

        // STAGE 2 :L305-L327. The rewritten map area, which every later stage reads.
        final ReportRequest normalised = normaliseCustomRange(mapArea);

        // STAGE 3 :L329-L379.
        checkCustomRangeValues(normalised);

        // STAGE 4 :L381-L383 for the start date and :L384-L386 for the end date.
        final String startDate = assembleDate(
                normalised.startDateYear(), normalised.startDateMonth(), normalised.startDateDay());
        final String endDate = assembleDate(
                normalised.endDateYear(), normalised.endDateMonth(), normalised.endDateDay());

        // STAGE 5 :L388-L406 then :L408-L426. Start first, end second; the order is the order in which the
        // two messages can be produced, and only one of them ever is.
        validateAssembledDate(startDate, MSG_START_DATE_INVALID, CURSOR_START_MONTH);
        validateAssembledDate(endDate, MSG_END_DATE_INVALID, CURSOR_END_MONTH);

        // STAGE 6 :L429-L432 move each date to its pair of parameter targets, and :L433 MOVE 'Custom' TO
        // WS-REPORT-NAME.
        return new JobSubmissionMessage(REPORT_NAME_CUSTOM, startDate, endDate);
    }

    /**
     * Stage 1 of the custom arm: the six blank tests, {@code app/cbl/CORPT00C.cbl:L258-L303}.
     *
     * <p>A nested {@code EVALUATE TRUE} whose six {@code WHEN} conditions each test one component for being
     * {@code SPACES OR LOW-VALUES}, followed by {@code WHEN OTHER -&gt; CONTINUE} at {@code :L301-L302}.
     * First match wins, and each match is a terminal send, so at most one of the six messages is ever
     * produced.
     *
     * <p>The order is month, day, year for the start date and then month, day, year for the end date, which
     * is exactly the declaration order of the six fields in {@code app/cpy-bms/CORPT00.CPY:L78-L108}. It is
     * <em>not</em> the order the assembled date puts them in, and it is preserved as written.
     *
     * <p>Each message ends in three periods. All three are content, not formatting, and are compared
     * literally by the parity gates.
     *
     * @param mapArea the received map area
     * @throws ValidationException on the first component found blank, carrying that component's literal and
     *                             its symbolic-map cursor field
     */
    private static void checkCustomRangeSupplied(final ReportRequest mapArea) {

        // :L259-L265 WHEN SDTMMI OF CORPT0AI = SPACES OR LOW-VALUES.
        if (blankOrLowValues(mapArea.startDateMonth())) {
            throw ValidationException.missingField(CURSOR_START_MONTH, MSG_START_MONTH_EMPTY);
        }
        // :L266-L272 WHEN SDTDDI OF CORPT0AI = SPACES OR LOW-VALUES.
        if (blankOrLowValues(mapArea.startDateDay())) {
            throw ValidationException.missingField(CURSOR_START_DAY, MSG_START_DAY_EMPTY);
        }
        // :L273-L279 WHEN SDTYYYYI OF CORPT0AI = SPACES OR LOW-VALUES.
        if (blankOrLowValues(mapArea.startDateYear())) {
            throw ValidationException.missingField(CURSOR_START_YEAR, MSG_START_YEAR_EMPTY);
        }
        // :L280-L286 WHEN EDTMMI OF CORPT0AI = SPACES OR LOW-VALUES.
        if (blankOrLowValues(mapArea.endDateMonth())) {
            throw ValidationException.missingField(CURSOR_END_MONTH, MSG_END_MONTH_EMPTY);
        }
        // :L287-L293 WHEN EDTDDI OF CORPT0AI = SPACES OR LOW-VALUES.
        if (blankOrLowValues(mapArea.endDateDay())) {
            throw ValidationException.missingField(CURSOR_END_DAY, MSG_END_DAY_EMPTY);
        }
        // :L294-L300 WHEN EDTYYYYI OF CORPT0AI = SPACES OR LOW-VALUES.
        if (blankOrLowValues(mapArea.endDateYear())) {
            throw ValidationException.missingField(CURSOR_END_YEAR, MSG_END_YEAR_EMPTY);
        }
        // :L301-L302 WHEN OTHER -> CONTINUE. All six are populated; nothing further happens here.
    }

    /**
     * Stage 2 of the custom arm: the six numeric round trips, {@code app/cbl/CORPT00C.cbl:L305-L327}.
     *
     * <p>Six pairs of statements, each {@code COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(field)} followed by
     * {@code MOVE WS-NUM-99 TO field}, with {@code WS-NUM-9999} standing in for the two year components. All
     * six run <strong>unconditionally</strong>: there is no test around them and no way to skip them.
     *
     * <p><strong>The round trip is not a no-op, and reproducing its side effect is mandatory.</strong>
     * {@code WS-NUM-99} is {@code PIC 99} at {@code :L74} and {@code WS-NUM-9999} is {@code PIC 9999} at
     * {@code :L75}; both are unsigned display items, so moving one back into an alphanumeric field of the same
     * width writes zero-padded digits. A submitted {@code 1} becomes {@code 01} and a submitted {@code 7}
     * becomes {@code 0007}, and it is the padded value that stages 3, 4 and 5 all see.
     *
     * <p>Two consequences are worth naming because they are easy to lose. First, the intrinsic used here is
     * the <em>currency-aware</em> one, applied to date components rather than to money - so the parse must
     * tolerate what that intrinsic tolerates, not merely what a strict digit parser would. Second, input the
     * intrinsic cannot make sense of yields zero, which becomes {@code 00} or {@code 0000}, and {@code 00}
     * passes the stage 3 range tests because those tests have no lower bound. The rejection of such input
     * therefore happens in stage 5 and nowhere earlier, which is precisely why both whole-date validations -
     * and the tolerated message number that qualifies them - are load-bearing rather than belt-and-braces.
     *
     * <p>The rewrite is expressed as a new map area rather than as mutation, because the source's target is
     * the map field itself and a record is the Java form of that map. The eleven components the source does
     * not touch are carried across unchanged.
     *
     * @param mapArea the received map area
     * @return the map area with its six range components replaced by their normalised forms
     */
    private static ReportRequest normaliseCustomRange(final ReportRequest mapArea) {
        return new ReportRequest(
                // The six header components: untouched by this stage.
                mapArea.transactionName(),
                mapArea.title01(),
                mapArea.currentDate(),
                mapArea.programName(),
                mapArea.title02(),
                mapArea.currentTime(),
                // The three selectors: untouched.
                mapArea.monthlySelected(),
                mapArea.yearlySelected(),
                mapArea.customSelected(),
                // :L305-L308 SDTMMI through WS-NUM-99.
                normaliseComponent(mapArea.startDateMonth(), MONTH_DAY_WIDTH),
                // :L309-L312 SDTDDI through WS-NUM-99.
                normaliseComponent(mapArea.startDateDay(), MONTH_DAY_WIDTH),
                // :L313-L316 SDTYYYYI through WS-NUM-9999.
                normaliseComponent(mapArea.startDateYear(), YEAR_WIDTH),
                // :L317-L319 EDTMMI through WS-NUM-99.
                normaliseComponent(mapArea.endDateMonth(), MONTH_DAY_WIDTH),
                // :L320-L323 EDTDDI through WS-NUM-99.
                normaliseComponent(mapArea.endDateDay(), MONTH_DAY_WIDTH),
                // :L324-L327 EDTYYYYI through WS-NUM-9999.
                normaliseComponent(mapArea.endDateYear(), YEAR_WIDTH),
                // The confirmation gate and the message field: untouched.
                mapArea.confirmation(),
                mapArea.errorMessage());
    }

    /**
     * Stage 3 of the custom arm: the six range tests, {@code app/cbl/CORPT00C.cbl:L329-L379}.
     *
     * <p>Six <strong>separate, sequential, unguarded</strong> {@code IF} statements - not an
     * {@code EVALUATE}, and with no {@code ELSE} and no early exit between them. They nevertheless behave as
     * a first-failure-wins cascade, because each one's body performs a send and every send is a terminal exit
     * through {@code :L580}. A later test cannot overwrite an earlier failure, and exactly one of the six
     * messages can be produced.
     *
     * <p><strong>Three absences are reproduced deliberately.</strong>
     *
     * <ul>
     *   <li>The comparisons are <em>alphanumeric</em>. {@code IF SDTMMI &gt; '12'} compares two
     *       two-character fields byte by byte against the character literal {@code '12'}; it is not a numeric
     *       comparison, and it is written here as a string comparison for that reason. On digit-only operands
     *       of equal width the two agree, but the semantics are the source's.</li>
     *   <li>There is <strong>no lower bound</strong> on either the month or the day, so {@code 00} passes
     *       both. No lower bound is added.</li>
     *   <li>There is <strong>no range test on the year at all</strong> - only a numeric test. A year of
     *       {@code 0000} or {@code 9999} passes this stage untouched. No range is added.</li>
     * </ul>
     *
     * <p>The two year tests at {@code :L347} and {@code :L373} are additionally <strong>unreachable</strong>,
     * and so is the {@code IS NOT NUMERIC} half of each of the four month and day tests: stage 2 has already
     * rewritten all six components as digits, unconditionally, so no non-numeric value can reach here. This
     * is a Low-severity finding of this file's own, recorded in the class documentation. All six tests are
     * reproduced one for one regardless. They are real predicates evaluated against real data on a reachable
     * path, so they are neither fabricated nor untracked, which is what Rule 1 Clause B actually asks; and
     * deleting them would break the correspondence the traceability matrix is checked against.
     *
     * @param normalised the map area as stage 2 rewrote it
     * @throws ValidationException on the first component found out of range or non-numeric
     */
    private static void checkCustomRangeValues(final ReportRequest normalised) {

        // :L329-L337 IF SDTMMI IS NOT NUMERIC OR SDTMMI > '12'. The numeric half is unreachable after
        // stage 2; retained because the source evaluates it.
        if (!allDigits(normalised.startDateMonth())
                || normalised.startDateMonth().compareTo(MONTH_UPPER_BOUND) > 0) {
            throw ValidationException.invalidField(CURSOR_START_MONTH, MSG_START_MONTH_INVALID);
        }
        // :L338-L346 IF SDTDDI IS NOT NUMERIC OR SDTDDI > '31'.
        if (!allDigits(normalised.startDateDay())
                || normalised.startDateDay().compareTo(DAY_UPPER_BOUND) > 0) {
            throw ValidationException.invalidField(CURSOR_START_DAY, MSG_START_DAY_INVALID);
        }
        // :L347-L354 IF SDTYYYYI IS NOT NUMERIC. No upper bound and no lower bound; wholly unreachable after
        // stage 2, and retained one for one for that reason rather than in spite of it.
        if (!allDigits(normalised.startDateYear())) {
            throw ValidationException.invalidField(CURSOR_START_YEAR, MSG_START_YEAR_INVALID);
        }
        // :L355-L363 IF EDTMMI IS NOT NUMERIC OR EDTMMI > '12'.
        if (!allDigits(normalised.endDateMonth())
                || normalised.endDateMonth().compareTo(MONTH_UPPER_BOUND) > 0) {
            throw ValidationException.invalidField(CURSOR_END_MONTH, MSG_END_MONTH_INVALID);
        }
        // :L364-L372 IF EDTDDI IS NOT NUMERIC OR EDTDDI > '31'.
        if (!allDigits(normalised.endDateDay())
                || normalised.endDateDay().compareTo(DAY_UPPER_BOUND) > 0) {
            throw ValidationException.invalidField(CURSOR_END_DAY, MSG_END_DAY_INVALID);
        }
        // :L373-L379 IF EDTYYYYI IS NOT NUMERIC. Unreachable, as above.
        if (!allDigits(normalised.endDateYear())) {
            throw ValidationException.invalidField(CURSOR_END_YEAR, MSG_END_YEAR_INVALID);
        }
    }

    /**
     * Stage 5 of the custom arm: one whole-date validation,
     * {@code app/cbl/CORPT00C.cbl:L388-L406} for the start date and {@code :L408-L426} for the end date.
     *
     * <p>Each call sets the date, sets the format mask, blanks the result area, calls the date utility and
     * then inspects the outcome in a two-level test whose shape is the whole point:
     *
     * <pre>
     * IF CSUTLDTC-RESULT-SEV-CD = '0000'      -&gt; CONTINUE
     * ELSE IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'  -&gt; message, error flag, cursor, terminal send
     * </pre>
     *
     * <p><strong>Message number 2513 is explicitly tolerated on both calls.</strong> A non-zero severity
     * whose message number is {@code 2513} falls through both tests and is accepted. That number is the
     * {@code FC-UNSUPP-RANGE} condition, and an implementation that rejected every non-zero severity would
     * reject dates the source accepts - a High-severity divergence, recorded in the class documentation.
     *
     * <p>The outcome is consumed as a return value, not as an exception. That is not incidental: an exception
     * carries no message number, so the exemption above could not be expressed against one. The validator is
     * built to report severity and message number precisely because these two call sites need them, and its
     * own documentation names {@code :L396} and {@code :L399} as the reason those two accessors exist.
     *
     * <p>The cursor field is worth noting. A whole-date failure parks the cursor on the <em>month</em>
     * component - {@code SDTMML} at {@code :L403} and {@code EDTMML} at {@code :L423} - and not on the year,
     * because the month is where a corrected entry starts.
     *
     * @param assembledDate the ten-character dashed date, as stage 4 assembled it
     * @param message       the literal to report on rejection
     * @param cursorField   the symbolic-map field to park the cursor on
     * @throws ValidationException when the validator rejects the date with a severity and a message number
     *                             other than the tolerated one
     */
    private void validateAssembledDate(
            final String assembledDate, final String message, final String cursorField) {

        // :L389-L391 MOVE the date and WS-DATE-FORMAT into the parameter, MOVE SPACES TO the result area.
        // :L392-L394 CALL 'CSUTLDTC' USING date, format, result. The mask is the validator's own constant,
        // which is character-for-character WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD' at :L72.
        final DateValidationService.DateValidationResult result =
                this.dateValidationService.validate(assembledDate, DateValidationService.MASK_YYYY_MM_DD);

        // :L396-L397 IF CSUTLDTC-RESULT-SEV-CD = '0000' -> CONTINUE.
        if (SEVERITY_ACCEPTED.equals(result.severityCode())) {
            return;
        }

        // :L399 ELSE IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'. The tolerated number falls through to
        // acceptance on both calls.
        if (!SEVERITY_TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber())) {
            // :L400-L405 MOVE the literal TO WS-MESSAGE, MOVE 'Y' TO WS-ERR-FLG, MOVE -1 TO the month
            // component, PERFORM SEND-TRNRPT-SCREEN.
            throw ValidationException.invalidField(cursorField, message);
        }

        // The tolerated outcome. Logged at debug because it is the one accepted path that carries a non-zero
        // severity and would otherwise be invisible. The result renders only its severity code and message
        // number, so no submitted character reaches the log through it.
        LOG.debug("date accepted under the tolerated message number {}: {}",
                SEVERITY_TOLERATED_MESSAGE_NUMBER, result);
    }

    /**
     * {@code SUBMIT-JOB-TO-INTRDR}, {@code app/cbl/CORPT00C.cbl:L462-L510}.
     *
     * <p>The confirmation handshake and the submission loop. Four confirmation states are distinguished, and
     * only one of them reaches the publish.
     *
     * <table border="1">
     *   <caption>The four confirmation states</caption>
     *   <tr><th>State</th><th>Lines</th><th>Condition</th><th>Behaviour</th></tr>
     *   <tr><td>Blank</td><td>{@code :L464-L474}</td><td>{@code SPACES OR LOW-VALUES}</td>
     *       <td>Composes {@code Please confirm to print the <em>Name</em> report...}, raises the error flag,
     *       parks the cursor on the confirmation field, sends. The first turn of the handshake.</td></tr>
     *   <tr><td>Affirmative</td><td>{@code :L478-L479}</td><td>{@code 'Y'} or {@code 'y'}</td>
     *       <td>{@code CONTINUE}: falls through to the loop and publishes. Exactly two values are accepted
     *       and nothing else is.</td></tr>
     *   <tr><td>Negative</td><td>{@code :L480-L483}</td><td>{@code 'N'} or {@code 'n'}</td>
     *       <td>Clears the form, raises the error flag, sends. <strong>Sets no message and no
     *       cursor.</strong></td></tr>
     *   <tr><td>Unrecognised</td><td>{@code :L484-L493}</td><td>{@code WHEN OTHER}</td>
     *       <td>Composes {@code "<em>value</em>" is not a valid value to confirm...} with the offending
     *       character quoted back inside double quotes, raises the error flag, parks the cursor on the
     *       confirmation field, sends.</td></tr>
     * </table>
     *
     * <p><strong>The negative arm's silence is behaviour, not an omission.</strong> It performs
     * {@code INITIALIZE-ALL-FIELDS}, which blanks {@code WS-MESSAGE} at {@code :L646}, and then sends without
     * composing anything, so the user is shown a cleared form with an empty message area. No "cancelled"
     * notice is invented here, and none may be: the asymmetry against the other three arms - which all set a
     * message, and two of which also set a cursor - is the source's own.
     *
     * <p><strong>On the value quoted back.</strong> Rule 1 Clause A treats every submitted field as untrusted
     * and Clause D forbids secrets in logs, and this is the one place the program echoes submitted input into
     * a message. It is safe and must not be suppressed: the value is a report-confirmation character, never
     * credential material, and it is bounded by its own field width - {@code CONFIRMI} is {@code PIC X(1)} at
     * {@code app/cpy-bms/CORPT00.CPY:L114}, so one character is all it can be. Truncating to that declared
     * width is reproducing a fixed-width field contract, not adding a guard the source lacks, and it is what
     * makes the composed message impossible to lengthen or to forge a record with.
     *
     * <p>{@code DELIMITED BY SPACE} at {@code :L487} takes the field up to its first blank. On a
     * one-character field that has already been established as non-blank, that is the identity, so no
     * separate truncation step appears below.
     *
     * <p>The guard at {@code :L476} is reproduced structurally: the blank arm above raises, so reaching the
     * state cascade is the guard holding. See {@code processEnterKey} for why an explicit always-false test
     * would be worse than useless.
     *
     * @param mapArea the received map area, whose confirmation field is the gate
     * @param period  the resolved period, whose report name the blank-confirmation prompt names
     * @return empty when the publish happened and the caller should compose the success notice; a screen when
     *         the confirmation was declined, which ends the turn
     * @throws ValidationException when the confirmation is blank or unrecognised
     * @throws FileAccessException when the publish fails
     */
    private Optional<ReportSubmissionScreen> submitJobToIntrdr(
            final ReportRequest mapArea, final JobSubmissionMessage period) {

        // The gate as the symbolic map would hold it: CONFIRMI is PIC X(1), so at most one character.
        final String gate = truncateToWidth(mapArea.confirmation(), CONFIRMATION_WIDTH);

        // :L464-L474 STATE 1. IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES -> STRING
        // 'Please confirm to print the ' DELIMITED BY SIZE, WS-REPORT-NAME DELIMITED BY SPACE,
        // ' report...' DELIMITED BY SIZE INTO WS-MESSAGE; MOVE 'Y' TO WS-ERR-FLG;
        // MOVE -1 TO CONFIRML; PERFORM SEND-TRNRPT-SCREEN.
        if (blankOrLowValues(gate)) {
            throw ValidationException.missingField(CURSOR_CONFIRM,
                    MSG_CONFIRM_PREFIX + period.reportName() + MSG_CONFIRM_SUFFIX);
        }

        // :L476 IF NOT ERR-FLG-ON, structural. :L477 EVALUATE TRUE.
        if (CONFIRM_YES_UPPER.equals(gate) || CONFIRM_YES_LOWER.equals(gate)) {
            // :L478-L479 STATE 2. WHEN 'Y' OR 'y' -> CONTINUE. Both cases and nothing else; the comparison
            // is against two distinct literals rather than a case-folded one, so no locale enters into it.
            LOG.debug("report submission confirmed for the {} period", period.reportName());
        } else if (CONFIRM_NO_UPPER.equals(gate) || CONFIRM_NO_LOWER.equals(gate)) {
            // :L480-L483 STATE 3. WHEN 'N' OR 'n' -> PERFORM INITIALIZE-ALL-FIELDS, MOVE 'Y' TO WS-ERR-FLG,
            // PERFORM SEND-TRNRPT-SCREEN. No message and no cursor: the cleared form carries a blank message
            // area because INITIALIZE-ALL-FIELDS blanked WS-MESSAGE at :L646.
            return Optional.of(
                    sendTrnrptScreen(initializeAllFields(mapArea), SPACES, true, false, null));
        } else {
            // :L484-L493 STATE 4. WHEN OTHER -> STRING '"' DELIMITED BY SIZE, CONFIRMI DELIMITED BY SPACE,
            // '" is not a valid value to confirm...' DELIMITED BY SIZE INTO WS-MESSAGE; MOVE 'Y' TO
            // WS-ERR-FLG; MOVE -1 TO CONFIRML; PERFORM SEND-TRNRPT-SCREEN.
            throw ValidationException.invalidField(CURSOR_CONFIRM,
                    MSG_INVALID_CONFIRM_PREFIX + gate + MSG_INVALID_CONFIRM_SUFFIX);
        }

        // :L496 SET END-LOOP-NO TO TRUE, then :L498-L508:
        //
        //   PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 1000 OR END-LOOP-YES OR ERR-FLG-ON
        //       MOVE JOB-LINES(WS-IDX) TO JCL-RECORD
        //       IF JCL-RECORD = '/*EOF' OR JCL-RECORD = SPACES OR LOW-VALUES
        //           SET END-LOOP-YES TO TRUE
        //       END-IF
        //       PERFORM WIRTE-JOBSUB-TDQ
        //   END-PERFORM
        //
        // Three properties of that loop are preserved as documented behaviour, because the seventeen cards
        // collapse into one typed message and there is no loop left to carry them:
        //
        //   1. THE TERMINATOR CARD IS WRITTEN BEFORE THE LOOP EXITS. The flag is set at :L504 and the write
        //      at :L507 still executes on that same iteration, because PERFORM VARYING ... UNTIL re-tests
        //      only at the top of the next one. Seventeen cards are written, not sixteen, and the
        //      seventeenth is the terminator itself.
        //   2. THE WHOLE BLOCK IS CONDITIONAL. :L476-L510 wraps it in IF NOT ERR-FLG-ON, so a declined or
        //      unrecognised confirmation never reaches it - the declined arm above returns and the other two
        //      raise, which is the same outcome by a different mechanism.
        //   3. THE BOUND IS 1000, matching OCCURS 1000 TIMES at :L127, and the loop never approaches it
        //      because the terminator stops it at seventeen. Nothing here walks the 983 unused slots; doing
        //      so would be the obvious inefficiency Rule 1 Clause A forbids, and would publish 983 blank
        //      messages besides.
        LOG.debug("submitting the {} period as one message in place of {} eighty-byte job cards, "
                        + "loop bound {}, terminator {}",
                period.reportName(), JOB_CARD_COUNT, JOB_LINE_LIMIT, JOB_TERMINATOR_CARD);

        // :L507 PERFORM WIRTE-JOBSUB-TDQ, once.
        wirteJobsubTdq(period);

        // Control returns to :L443 and then to the success tail at :L445.
        return Optional.empty();
    }

    /**
     * {@code WIRTE-JOBSUB-TDQ}, {@code app/cbl/CORPT00C.cbl:L515-L537}.
     *
     * <p><strong>The misspelling in the method name is deliberate and must not be corrected.</strong> The
     * paragraph is declared {@code WIRTE-JOBSUB-TDQ.} at {@code :L515} and performed as
     * {@code PERFORM WIRTE-JOBSUB-TDQ} at {@code :L507}; the transposition is in the frozen source, in both
     * places, and it is preserved here for the same reason the misspelled account expiry field name and the
     * wrong-verb user update message are preserved elsewhere in the migration. The traceability matrix is
     * checked mechanically against paragraph labels, and a silently corrected label breaks that check while
     * looking tidier.
     *
     * <p>The source writes one eighty-byte card to an extrapartition transient data queue:
     *
     * <pre>
     * EXEC CICS WRITEQ TD
     *   QUEUE ('JOBS')
     *   FROM (JCL-RECORD)
     *   LENGTH (LENGTH OF JCL-RECORD)
     *   RESP(WS-RESP-CD)
     *   RESP2(WS-REAS-CD)
     * END-EXEC.
     * </pre>
     *
     * <p>and then, at {@code :L525-L535}, evaluates the response: {@code DFHRESP(NORMAL)} continues, and
     * {@code WHEN OTHER} displays the diagnostic, raises the error flag, sets the message, parks the cursor
     * and sends. Both response items are named {@code PIC S9(09) COMP} fields declared at {@code :L54-L55}.
     *
     * <p>Three details of the failure arm are reproduced exactly:
     *
     * <ul>
     *   <li>The message is {@code Unable to Write TDQ (JOBS)...}, byte for byte, three trailing periods
     *       included. Altering it in any way is a Blocker.</li>
     *   <li>The cursor goes to {@code MONTHLYL} at {@code :L533} - the <em>monthly selector</em>, not the
     *       confirmation field the user was last on.</li>
     *   <li>{@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at {@code :L529} is the only
     *       instrumentation the source has on this path, and COBOL {@code DISPLAY} concatenates its operands
     *       with no separator, so the emitted text reads {@code RESP:}<em>resp</em>{@code REAS:}<em>reas</em>.
     *       The warning below carries both prefixes in that form so an operator who knew the legacy line
     *       still recognises this one, with the failure condition standing in for the response code and the
     *       endpoint for the reason code.</li>
     * </ul>
     *
     * <p>The failure is rethrown as a typed exception carrying the original as its cause, so nothing is
     * swallowed and the root cause survives, which is what Rule 1 Clause B requires. It is not retried: the
     * source does not retry, and adding a retry would change how many messages a failing queue eventually
     * receives.
     *
     * <p>No deduplication identifier is set, because the source has no idempotency key and adding one would
     * be inventing a guard it lacks. Whether two identical submissions collapse is therefore a queue
     * attribute rather than a decision of this bean, and it is configured where the queue is provisioned.
     *
     * @param card the typed message standing in for the eighty-byte card images
     * @throws FileAccessException when the publish fails, carrying the source's literal and the cause
     */
    private void wirteJobsubTdq(final JobSubmissionMessage card) {
        try {
            // :L517-L523. The queue name and the message group are both configuration; the template and the
            // client beneath it belong to com.cardemo.config.AwsConfig, so no endpoint is named here.
            final SendResult<JobSubmissionMessage> sendResult = this.sqsTemplate.send(options -> options
                    .queue(this.reportQueueName)
                    .payload(card)
                    .messageGroupId(this.reportMessageGroupId));

            // :L526-L527 WHEN DFHRESP(NORMAL) -> CONTINUE. The source is silent here; one informational line
            // is added because a submission that leaves the online tier with no trace at all cannot be
            // reconciled against the batch run it triggers.
            LOG.info("report job submission published to the {} queue as message {} in group {}, "
                            + "replacing {} fixed {}-byte job cards",
                    this.reportQueueLogicalName, sendResult.messageId(), this.reportMessageGroupId,
                    JOB_CARD_COUNT, JOB_CARD_LENGTH);
        } catch (final RuntimeException failure) {
            // :L528-L534 WHEN OTHER. The reason code is the endpoint the send was attempted against when the
            // failure reports one, which is the closest analogue of RESP2 the publisher offers.
            final String reasonCode =
                    failure instanceof final MessagingOperationFailedException messagingFailure
                            && messagingFailure.getEndpoint() != null
                            ? messagingFailure.getEndpoint()
                            : REASON_UNAVAILABLE;

            // :L529 DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD, concatenated with no separator.
            LOG.warn("RESP:{}REAS:{} unable to write the report job submission to the {} queue",
                    failure.getClass().getSimpleName(), reasonCode, this.reportQueueLogicalName, failure);

            // :L530-L534 MOVE 'Y' TO WS-ERR-FLG, MOVE the literal TO WS-MESSAGE, MOVE -1 TO MONTHLYL,
            // PERFORM SEND-TRNRPT-SCREEN.
            //
            // The two-argument form is used deliberately. The four-argument form of this exception renders a
            // COBOL FILE STATUS as the fixed literal 'FILE STATUS IS: NNNN' followed by the status digits,
            // and this program has no FILE STATUS to render - it declares no FILE-CONTROL paragraph, no
            // SELECT and no FD anywhere - so emitting that literal here would fabricate an I/O status that
            // does not exist. The message and the preserved cause are what this failure actually carries.
            //
            // The cursor field, MONTHLYL, is documented above but is not carried on the exception: a failed
            // publish is a server-side failure rather than a field-level rejection, so unlike the eighteen
            // validation outcomes there is no field for a caller to place a cursor on and no field-marked
            // exception type to carry one.
            throw new FileAccessException(MSG_UNABLE_TO_WRITE_TDQ, failure);
        }
    }

    /**
     * {@code RETURN-TO-PREV-SCREEN}, {@code app/cbl/CORPT00C.cbl:L540-L551}.
     *
     * <p>Leaves the transaction for another program. Reached twice: from {@code :L174} when there was no
     * communication area, and from {@code :L189} on PF3.
     *
     * <p>The paragraph defaults its target at {@code :L542-L544} -
     * {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES -&gt; MOVE 'COSGN00C'} - which is why a caller that
     * supplies nothing still lands on the sign-on program. Both call sites in this program set the target
     * explicitly first, so the default is a second line of defence rather than the normal path, and it is
     * reproduced here for the same reason.
     *
     * <p>Everything else the paragraph does has no Java counterpart, and all of it is COMMAREA bookkeeping:
     * {@code MOVE WS-TRANID TO CDEMO-FROM-TRANID} at {@code :L546},
     * {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} at {@code :L547} and
     * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} at {@code :L548} record where control came from and reset the
     * next program's re-enter state, and {@code EXEC CICS XCTL} at {@code :L550-L551} transfers with that area
     * attached. Under Transformation Rule 7 the target keeps no session state, so the transfer becomes a
     * navigation target on the response and the three moves become nothing at all.
     *
     * <p>No header is populated and no message is set, because {@code POPULATE-HEADER-INFO} is performed only
     * from the send paragraph and this paragraph does not send.
     *
     * @param toProgram the program to transfer to, defaulted to the sign-on program when blank
     * @param mapArea   the map area as it stands, returned unchanged
     * @return a screen carrying only the navigation target
     */
    private static ReportSubmissionScreen returnToPrevScreen(
            final String toProgram, final ReportRequest mapArea) {

        // :L542-L544 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES -> MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
        final String navigationTarget = blankOrLowValues(toProgram) ? SIGN_ON_PROGRAM : toProgram;

        // :L546-L548 the three COMMAREA moves, and :L550-L551 EXEC CICS XCTL: no counterpart, by design.
        return new ReportSubmissionScreen(mapArea, false, false, null, navigationTarget);
    }

    /**
     * {@code SEND-TRNRPT-SCREEN}, {@code app/cbl/CORPT00C.cbl:L556-L580}.
     *
     * <p>Paints the map and ends the turn. Performed from twenty-two sites, and it returns to none of them.
     *
     * <p><strong>This paragraph does not return, and that single fact shapes the whole translation.</strong>
     * It closes its conditional at {@code :L578} and then executes {@code GO TO RETURN-TO-CICS.} at
     * {@code :L580} as a separate unconditional sentence. That branch leaves the performed range, and
     * {@code RETURN-TO-CICS} immediately issues {@code EXEC CICS RETURN}, which ends the pseudo-conversational
     * task. So no {@code PERFORM} stack is ever unwound and no return address is ever resumed - the question
     * of what a {@code GO TO} out of a performed range does to the stack simply never arises here, because
     * the task is gone before it could matter. It is also why the six sequential range tests cannot overwrite
     * one another and why exactly one message is ever produced per turn.
     *
     * <p>Its three steps are:
     *
     * <ul>
     *   <li>{@code :L558} {@code PERFORM POPULATE-HEADER-INFO}, so every painted screen carries a fresh
     *       header regardless of which of the twenty-two sites reached it.</li>
     *   <li>{@code :L560} {@code MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO}. {@code WS-MESSAGE} is
     *       {@code PIC X(80)} at {@code :L39} and {@code ERRMSGO} is {@code PIC X(78)} at
     *       {@code app/cpy-bms/CORPT00.CPY:L224}, so this move truncates two bytes. No literal in the program
     *       reaches 78 characters, so nothing is ever actually lost; the mismatch is recorded because a
     *       reviewer comparing the two widths would otherwise wonder.</li>
     *   <li>{@code :L562-L578} one of two {@code SEND MAP('CORPT0A') MAPSET('CORPT00')} forms, chosen on
     *       {@code SEND-ERASE-YES}. The flag is set true at {@code :L167} and never set false anywhere in the
     *       program, so the erase form at {@code :L563-L570} is always the one taken and the alternative at
     *       {@code :L572-L577} - which additionally has its own {@code ERASE} commented out at {@code :L575} -
     *       is unreachable. Terminal erase has no meaning in an HTTP response, so the choice is recorded and
     *       dropped rather than modelled.</li>
     * </ul>
     *
     * @param mapArea          the map area to paint
     * @param message          what {@code WS-MESSAGE} held, moved into the map's message field
     * @param errorFlagOn      whether this is a re-prompt rather than a result
     * @param successHighlight whether the message carries the {@code DFHGREEN} success attribute
     * @param cursorField      the symbolic-map field that received {@code -1}, or {@code null} where the
     *                         source set none
     * @return the painted screen, by way of {@code RETURN-TO-CICS}
     */
    private ReportSubmissionScreen sendTrnrptScreen(final ReportRequest mapArea, final String message,
            final boolean errorFlagOn, final boolean successHighlight, final String cursorField) {

        // :L558 PERFORM POPULATE-HEADER-INFO.
        final ReportRequest painted = populateHeaderInfo(mapArea);

        // :L560 MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO.
        final ReportRequest withMessage = withErrorMessage(painted, message);

        // :L562-L578 the SEND, then :L580 GO TO RETURN-TO-CICS - unconditional, and the end of the turn.
        return returnToCics(
                new ReportSubmissionScreen(withMessage, errorFlagOn, successHighlight, cursorField, null));
    }

    /**
     * {@code RETURN-TO-CICS}, {@code app/cbl/CORPT00C.cbl:L585-L591}.
     *
     * <p>Ends the turn. {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code :L587-L591} hands control back to CICS, naming this transaction as the next one so the terminal
     * user's next key press re-enters this program, and attaching the communication area so the re-enter flag
     * survives. The {@code LENGTH(...)} option is present but commented out at {@code :L590}.
     *
     * <p>It is reached <strong>only</strong> by the {@code GO TO} at {@code :L580} and is never
     * {@code PERFORM}ed from anywhere. That is the whole of its call graph, and it is why the paragraph is
     * modelled as the completion of a send rather than as an independently reachable step.
     *
     * <p>Both of its options vanish under Transformation Rule 7. The next transaction is decided by the URL a
     * caller requests next rather than named in advance, and there is no communication area to attach because
     * no server-side session state exists to put in one. What remains is the fact that the turn is over,
     * which is exactly what returning the response expresses. The paragraph is retained as its own method
     * because the traceability matrix is checked against paragraph labels, and it is the reason
     * {@code MAIN-PARA}'s own trailing {@code EXEC CICS RETURN} at {@code :L199-L202} carries no additional
     * behaviour.
     *
     * @param screen the screen the send assembled
     * @return that screen, now the completed response
     */
    private static ReportSubmissionScreen returnToCics(final ReportSubmissionScreen screen) {
        // :L587-L591. Nothing is added: the response is the turn ending.
        return screen;
    }

    /**
     * {@code RECEIVE-TRNRPT-SCREEN}, {@code app/cbl/CORPT00C.cbl:L596-L604}.
     *
     * <p>Reads the map into the input area:
     * {@code EXEC CICS RECEIVE MAP('CORPT0A') MAPSET('CORPT00') INTO(CORPT0AI) RESP(WS-RESP-CD)
     * RESP2(WS-REAS-CD)}.
     *
     * <p><strong>Preserved quirk, severity Low: the two response items are captured and never tested.</strong>
     * {@code RESP} and {@code RESP2} are named at {@code :L602-L603} and no {@code IF} or {@code EVALUATE}
     * follows the command, so a failed receive is not detected and the program proceeds to interpret whatever
     * the input area happens to hold. Transformation Rule 12 requires that an I/O status never be swallowed,
     * and here the frozen source swallows one; parity governs, so the absent check is preserved rather than
     * supplied, and the divergence from the rule is recorded instead of quietly closed.
     *
     * <p>In the target the concern is largely moot in any case. Binding an HTTP request body is the
     * framework's responsibility, its failures surface as a rejected request before this bean is entered, and
     * the bean-validation constraints on {@link ReportRequest} enforce the field widths the symbolic map used
     * to enforce. So the check the source omits is one the target does not need at this level - which is why
     * the finding is Low rather than higher.
     *
     * @param submitted the bound request body, standing in for the receive's target area
     * @return that area, unchanged
     */
    private static ReportRequest receiveTrnrptScreen(final ReportRequest submitted) {
        // :L598-L603. The response items are captured by the source and deliberately not examined; there is
        // nothing to translate but the read itself.
        return Objects.requireNonNull(submitted, "submitted must not be null");
    }

    /**
     * {@code POPULATE-HEADER-INFO}, {@code app/cbl/CORPT00C.cbl:L609-L628}.
     *
     * <p>Fills the six header fields that every one of the seventeen symbolic maps carries. Performed only
     * from the send paragraph, so every painted screen gets a fresh header and no screen that transfers
     * control gets one.
     *
     * <p>The six moves are {@code CCDA-TITLE01} to {@code TITLE01O} at {@code :L616},
     * {@code CCDA-TITLE02} to {@code TITLE02O} at {@code :L617} - both from {@code app/cpy/COTTL01Y.cpy} -
     * {@code WS-TRANID} to {@code TRNNAMEO} at {@code :L619}, {@code WS-PGMNAME} to {@code PGMNAMEO} at
     * {@code :L620}, the assembled date to {@code CURDATEO} at {@code :L626} and the assembled time to
     * {@code CURTIMEO} at {@code :L628}.
     *
     * <p>The date is rendered {@code MM/DD/YY}: {@code :L622-L624} move the month, the day and
     * {@code WS-CURDATE-YEAR(3:2)} into the {@code WS-CURDATE-MM-DD-YY} group of
     * {@code app/cpy/CSDAT01Y.cpy}, whose two {@code FILLER} items supply the solidus. The reference
     * modification is what makes the year two digits. The time is rendered {@code HH:MM:SS} the same way
     * through {@code WS-CURTIME-HH-MM-SS}, stopping at seconds even though the copybook also declares a
     * millisecond subfield.
     *
     * <p><strong>{@code :L611} re-reads {@code FUNCTION CURRENT-DATE} into {@code WS-CURDATE-DATA} - the very
     * area the monthly computation mutated.</strong> That is harmless in the source only because the send is
     * terminal and the parameter dates were captured long before, but it is direct evidence that the date
     * working area cannot be a shared field in Java: two concurrent requests sharing it would see each
     * other's month-end arithmetic. Every value the source held there is method-local here, and this is the
     * paragraph that proves the requirement rather than merely asserting it.
     *
     * @param mapArea the map area whose header is to be filled
     * @return the map area with its six header fields populated and its eleven other fields untouched
     */
    private ReportRequest populateHeaderInfo(final ReportRequest mapArea) {

        // :L611 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. Read once, through the injected clock, so both
        // rendered fields come from the same instant and cannot straddle a second boundary.
        final LocalDateTime headerTimestamp = LocalDateTime.now(this.clock);

        return new ReportRequest(
                // :L619 MOVE WS-TRANID TO TRNNAMEO OF CORPT0AO.
                TRANSACTION_ID,
                // :L616 MOVE CCDA-TITLE01 TO TITLE01O OF CORPT0AO.
                SCREEN_TITLE_01,
                // :L622-L626 the MM/DD/YY assembly, then MOVE WS-CURDATE-MM-DD-YY TO CURDATEO.
                HEADER_DATE_FORMAT.format(headerTimestamp),
                // :L620 MOVE WS-PGMNAME TO PGMNAMEO OF CORPT0AO.
                PROGRAM_NAME,
                // :L617 MOVE CCDA-TITLE02 TO TITLE02O OF CORPT0AO.
                SCREEN_TITLE_02,
                // :L627-L628 the HH:MM:SS assembly, then MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO.
                HEADER_TIME_FORMAT.format(headerTimestamp),
                // The eleven remaining components are not touched by this paragraph.
                mapArea.monthlySelected(),
                mapArea.yearlySelected(),
                mapArea.customSelected(),
                mapArea.startDateMonth(),
                mapArea.startDateDay(),
                mapArea.startDateYear(),
                mapArea.endDateMonth(),
                mapArea.endDateDay(),
                mapArea.endDateYear(),
                mapArea.confirmation(),
                mapArea.errorMessage());
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS}, {@code app/cbl/CORPT00C.cbl:L633-L646}.
     *
     * <p>Clears the form. Performed from exactly two sites, and the pair is instructive: the success tail at
     * {@code :L447}, where a submitted form is cleared so the next one starts empty, and the negative
     * confirmation arm at {@code :L481}, where a declined form is cleared for the same reason.
     *
     * <p>It clears ten fields and one working-storage item, and the boundaries of that list matter:
     *
     * <ul>
     *   <li>{@code :L635} {@code MOVE -1 TO MONTHLYL}, parking the cursor back on the first selector.</li>
     *   <li>{@code :L637-L639} {@code INITIALIZE} the three period selectors.</li>
     *   <li>{@code :L640-L645} {@code INITIALIZE} the six custom range components.</li>
     *   <li>{@code :L645} {@code INITIALIZE CONFIRMI}, so the handshake starts over.</li>
     *   <li>{@code :L646} {@code INITIALIZE WS-MESSAGE}. <strong>This is why the negative confirmation arm
     *       shows a blank message.</strong></li>
     * </ul>
     *
     * <p>It does <strong>not</strong> clear {@code ERRMSGI} and it does <strong>not</strong> clear the six
     * header fields. The header would be repainted by the send that follows in any case; the untouched
     * {@code ERRMSGI} is simply not in the list.
     *
     * <p>{@code INITIALIZE} on an alphanumeric item sets it to spaces, not to low values, so a cleared field
     * is present-and-blank rather than absent. The empty-string convention this file uses for
     * {@code MOVE SPACES} carries that distinction, which matters because the very next thing a caller does
     * with a cleared form is submit it, and the selector tests read {@code NOT = SPACES AND LOW-VALUES}.
     *
     * <p>The cursor move at {@code :L635} is carried by the callers rather than returned from here, because
     * both of them set a cursor of their own afterwards - the success tail re-sets the same field at
     * {@code :L453}, and the negative arm sets none at all, which is the asymmetry that arm is preserved for.
     *
     * @param mapArea the map area to clear
     * @return the map area with its ten form fields blank and its six header fields untouched
     */
    private static ReportRequest initializeAllFields(final ReportRequest mapArea) {
        return new ReportRequest(
                // The six header components: not in the INITIALIZE list, so carried across unchanged.
                mapArea.transactionName(),
                mapArea.title01(),
                mapArea.currentDate(),
                mapArea.programName(),
                mapArea.title02(),
                mapArea.currentTime(),
                // :L637 INITIALIZE MONTHLYI OF CORPT0AI.
                SPACES,
                // :L638 INITIALIZE YEARLYI OF CORPT0AI.
                SPACES,
                // :L639 INITIALIZE CUSTOMI OF CORPT0AI.
                SPACES,
                // :L640-L642 INITIALIZE SDTMMI, SDTDDI and SDTYYYYI OF CORPT0AI.
                SPACES,
                SPACES,
                SPACES,
                // :L643-L645 INITIALIZE EDTMMI, EDTDDI and EDTYYYYI OF CORPT0AI.
                SPACES,
                SPACES,
                SPACES,
                // :L645 INITIALIZE CONFIRMI OF CORPT0AI.
                SPACES,
                // :L646 INITIALIZE WS-MESSAGE. ERRMSGI is deliberately absent from the list, so the map's own
                // message field is carried across; the caller supplies what the send moves into it.
                mapArea.errorMessage());
    }

    /**
     * {@code MOVE LOW-VALUES TO CORPT0AO}, {@code app/cbl/CORPT00C.cbl:L179}.
     *
     * <p>The empty output map of the first turn. Every one of the seventeen fields is absent rather than
     * blank, which is the distinction {@code LOW-VALUES} draws against {@code SPACES} and which every
     * {@code NOT = SPACES AND LOW-VALUES} test in the program depends on. The six header fields are filled in
     * afterwards by the send, so the form a caller receives is not entirely empty; the eleven others are.
     *
     * @return a map area with all seventeen fields absent
     */
    private static ReportRequest lowValuesMapArea() {
        // :L179. Seventeen absent fields, in the declaration order of app/cpy-bms/CORPT00.CPY:L24-L120.
        return new ReportRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO}, {@code app/cbl/CORPT00C.cbl:L560}.
     *
     * <p>Places the composed message into the map's message field, leaving the other sixteen fields alone.
     * See {@code sendTrnrptScreen} for the two-byte width mismatch this move carries and why it never bites.
     *
     * @param mapArea the map area to place the message into
     * @param message what {@code WS-MESSAGE} held
     * @return the map area with its message field replaced
     */
    private static ReportRequest withErrorMessage(final ReportRequest mapArea, final String message) {
        return new ReportRequest(
                mapArea.transactionName(),
                mapArea.title01(),
                mapArea.currentDate(),
                mapArea.programName(),
                mapArea.title02(),
                mapArea.currentTime(),
                mapArea.monthlySelected(),
                mapArea.yearlySelected(),
                mapArea.customSelected(),
                mapArea.startDateMonth(),
                mapArea.startDateDay(),
                mapArea.startDateYear(),
                mapArea.endDateMonth(),
                mapArea.endDateDay(),
                mapArea.endDateYear(),
                mapArea.confirmation(),
                // :L560, the only field this move touches.
                message);
    }

    /**
     * The selector test, {@code IF <em>field</em> NOT = SPACES AND LOW-VALUES}, at
     * {@code app/cbl/CORPT00C.cbl:L213}, {@code :L239} and {@code :L256}.
     *
     * <p>An abbreviated combined relation: the condition is {@code NOT = SPACES} <em>and</em>
     * {@code NOT = LOW-VALUES}, so a field counts as supplied only when it is neither. That is the whole of
     * the test - the source never examines <em>what</em> the selector holds, only that it holds something, so
     * any non-blank character selects the period.
     *
     * @param value the selector field
     * @return {@code true} when the selector was supplied
     */
    private static boolean selectorSupplied(final String value) {
        return !blankOrLowValues(value);
    }

    /**
     * The {@code = SPACES OR LOW-VALUES} test, used by the three selector tests, the six blank range tests at
     * {@code app/cbl/CORPT00C.cbl:L259-L300}, the confirmation gate at {@code :L464} and the navigation
     * default at {@code :L542}.
     *
     * <p>COBOL compares a field against a figurative constant across its whole width, so {@code = SPACES}
     * holds only when every byte is a blank and {@code = LOW-VALUES} only when every byte is the low-values
     * byte. A field of mixed blanks and low values therefore equals neither, and the two fills are tested
     * separately here rather than as one "is every byte blank-or-low" pass, which would wrongly accept that
     * mixture.
     *
     * <p>{@code null} means the field was never populated, which is what {@code MOVE LOW-VALUES} produced, so
     * it reports as blank. The empty string is this file's stand-in for {@code MOVE SPACES} and reports as
     * blank too.
     *
     * @param value the field, which may be {@code null}
     * @return {@code true} when the field is absent, all blanks, or all low values
     */
    private static boolean blankOrLowValues(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return isFilledWith(value, ' ') || isFilledWith(value, LOW_VALUES);
    }

    /**
     * Tests whether every character of a field is one given fill character.
     *
     * <p>The primitive behind the figurative-constant comparison above: a COBOL field equals a figurative
     * constant when, and only when, every one of its bytes is that constant's character.
     *
     * @param value the field, which must not be empty for the answer to be meaningful
     * @param fill  the fill character to test for
     * @return {@code true} when every character of {@code value} is {@code fill}
     */
    private static boolean isFilledWith(final String value, final char fill) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != fill) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code IS NUMERIC} test, used in its negated form by the six range tests at
     * {@code app/cbl/CORPT00C.cbl:L329-L379}.
     *
     * <p>On an alphanumeric item, {@code IS NUMERIC} holds when every character is a digit. Blanks are not
     * digits, so an unpopulated field is not numeric, and there is no sign to allow because the items being
     * tested are the symbolic map's own {@code PIC X} fields.
     *
     * <p>The ASCII range is tested explicitly rather than through {@code Character.isDigit}, and the choice is
     * deliberate: that method accepts every Unicode decimal digit, including forms a COBOL numeric test
     * rejects outright, so using it would make this predicate <em>more</em> permissive than the source's.
     *
     * @param value the field, which may be {@code null}
     * @return {@code true} when the field is non-empty and every character is an ASCII digit
     */
    private static boolean allDigits(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < DIGIT_ZERO || character > DIGIT_NINE) {
                return false;
            }
        }
        return true;
    }

    /**
     * One numeric round trip of stage 2, {@code COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(<em>field</em>)}
     * followed by {@code MOVE WS-NUM-99 TO <em>field</em>}, at {@code app/cbl/CORPT00C.cbl:L305-L327}.
     *
     * <p>Three behaviours of that pair are reproduced, and each of them is observable downstream:
     *
     * <ul>
     *   <li><strong>Zero padding.</strong> The receiving item is an unsigned display item of fixed width, so
     *       the value moved back is the width's worth of digits with leading zeros. A submitted {@code 1}
     *       becomes {@code 01} in a two-character component and {@code 0007} in a four-character one, and it
     *       is the padded form that stage 3 tests and stage 4 assembles.</li>
     *   <li><strong>Tolerance.</strong> The intrinsic is the currency-aware one, so a currency symbol, a
     *       thousands separator, a sign or surrounding blanks are all absorbed rather than rejected. Anything
     *       it cannot make sense of yields zero, which becomes {@code 00} or {@code 0000} - and passes stage 3,
     *       because stage 3 has no lower bound. Such input is caught by stage 5 and nowhere earlier.</li>
     *   <li><strong>Truncation, in both directions.</strong> A fractional part is dropped at the decimal point
     *       because the receiving item has no decimal places, and high-order digits beyond the item's width
     *       are lost because a {@code MOVE} into {@code PIC 9(n)} keeps the low-order {@code n} digits. The
     *       modulus below reproduces the second; the break at the decimal point reproduces the first.</li>
     * </ul>
     *
     * <p>The sign is immaterial: the receiving items are unsigned, so a negative result is stored as its
     * absolute value, and only the digits are accumulated here.
     *
     * @param value the submitted component; stage 1 has already established that it is neither absent nor
     *              blank, so the empty case below is unreachable rather than expected
     * @param width the component's declared width, 2 for a month or a day and 4 for a year
     * @return the component's digits, zero-padded to exactly {@code width} characters
     */
    private static String normaliseComponent(final String value, final int width) {

        // The modulus that reproduces a MOVE into PIC 9(width): 100 for two digits, 10000 for four. Derived
        // by multiplication rather than from a table or a floating-point power, so it is exact and needs no
        // maintenance if a width ever changes.
        long modulus = 1L;
        for (int power = 0; power < width; power++) {
            modulus *= DECIMAL_RADIX;
        }

        long accumulated = 0L;
        final String source = value == null ? SPACES : value;
        for (int index = 0; index < source.length(); index++) {
            final char character = source.charAt(index);
            if (character == DECIMAL_POINT) {
                // The receiving item has no decimal places, so everything from here on is discarded.
                break;
            }
            if (character >= DIGIT_ZERO && character <= DIGIT_NINE) {
                // Taking the modulus at each step is equivalent to taking it once at the end and cannot
                // overflow, however long the submitted value is.
                accumulated = (accumulated * DECIMAL_RADIX + (character - DIGIT_ZERO)) % modulus;
            }
        }

        final String digits = Long.toString(accumulated);
        if (digits.length() >= width) {
            return digits;
        }
        return String.valueOf(DIGIT_ZERO).repeat(width - digits.length()) + digits;
    }

    /**
     * Reproduces a fixed-width field's inability to hold more than its declared width.
     *
     * <p>Applied to the confirmation gate, which is {@code PIC X(1)} at
     * {@code app/cpy-bms/CORPT00.CPY:L114}. A symbolic map field physically cannot carry more characters than
     * it declares, so truncating to the declared width is reproducing the field contract rather than adding a
     * validation the source lacks - and it is what bounds the value the unrecognised-confirmation message
     * quotes back.
     *
     * @param value the submitted field, which may be {@code null}
     * @param width the field's declared width
     * @return the field truncated to {@code width}, or {@code null} when the field was absent
     */
    private static String truncateToWidth(final String value, final int width) {
        if (value == null || value.length() <= width) {
            return value;
        }
        return value.substring(0, width);
    }

    /**
     * Stage 4 of the custom arm, {@code app/cbl/CORPT00C.cbl:L381-L383} for the start date and
     * {@code :L384-L386} for the end date.
     *
     * <p>Moves the year, the month and the day into the three data items of {@code WS-START-DATE} or
     * {@code WS-END-DATE}, whose two {@code FILLER X(01) VALUE '-'} items already hold the separators. The
     * group is ten bytes, and the result is the ten-character dashed form the date validator is asked to parse
     * against {@code 'YYYY-MM-DD'} and the batch tier receives.
     *
     * <p><strong>A compact undashed form is a High-severity divergence.</strong> The separators are declared in
     * the layout rather than assembled, which is why they are impossible to lose by accident in COBOL and easy
     * to lose in Java.
     *
     * @param year  the four-character year, as stage 2 normalised it
     * @param month the two-character month, as stage 2 normalised it
     * @param day   the two-character day, as stage 2 normalised it
     * @return the ten-character dashed date
     */
    private static String assembleDate(final String year, final String month, final String day) {
        // :L381-L386. Year, separator, month, separator, day: 4 + 1 + 2 + 1 + 2 = 10.
        return year + DATE_SEPARATOR + month + DATE_SEPARATOR + day;
    }
}
