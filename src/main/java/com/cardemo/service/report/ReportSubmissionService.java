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
 * Source      : app/cbl/CORPT00C.cbl (649 lines, 10 own paragraph labels) @ 7756d89
 * ******************************************************************
 */
package com.cardemo.service.report;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.shared.DateValidationService;

import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.operations.MessagingOperationFailedException;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

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
 * <p><strong>Severity: Blocker. Locator: {@code app/cbl/CORPT00C.cbl:L213-L238}. Status: implemented correctly here;
 * recorded so the root agent can enter it in the {@code DECISION_LOG.md}.</strong>
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
 *   </ol>
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
 * <p><strong>Why the reading is easy to get wrong.</strong> An author reading only {@code :L232-L234} sees
 * the current-date subfields named and concludes the end date is today, without noticing that
 * {@code :L223-L230} has already overwritten them in place. Implemented that way the emitted end date would
 * differ on every day of the month except the last, and every downstream {@code TRANREPT} report would
 * diverge from the parity baseline. {@code com.cardemo.model.dto.ReportRequest} and this bean therefore
 * carry the same reading, and the variance against the specification prose is disclosed exactly once, with
 * its severity and its remediation, in the register carried by the documentation of the {@code com.cardemo}
 * root package.
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
 * <p>The count is <strong>seventeen</strong>, on the 14 + 3 arithmetic of the table above, and every surface
 * that publishes it agrees: the YAML comment beside the queue properties in
 * {@code src/main/resources/application.yml}, {@code com.cardemo.model.dto.ReportRequest} and
 * {@code src/main/resources/application-prod.yml}. Section 0.7.5.1 of
 * {@code docs/technical-specifications.md} deliberately asserts no card count, so nothing there can drift
 * against this table. The figure carries no code impact either way, because the deck collapses to one typed
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
 *   </ul>
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
 *   </ul>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Java 25 with {@code maven.compiler.release} set to 25 and no preview feature enabled, and Maven
 * 3.9.11 through the pinned wrapper. The compiler runs {@code -Xlint:all} with {@code -Werror}, so any
 * warning {@code javac} 25 publishes fails the build. Compile with {@code ./mvnw -B -ntp clean compile} and
 * test with {@code ./mvnw -B -ntp test}; {@code ./mvnw -B -ntp verify} additionally enforces the JaCoCo
 * line coverage floor of 0.80. The environment file must be sourced for those commands, because the queue name
 * resolves from it, and it is sourced inside a subshell that also carries the command -
 * {@code ( set -a; . ./.env; set +a; ./mvnw -B -ntp verify )} - rather than exported into the shell, where every
 * later child would inherit it.
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
 *   </table>
 *
 * <p>All three property values are asserted rather than assumed. The two queue names are validated at
 * startup by {@code com.cardemo.config.AwsConfig}, which requires the physical name to equal the logical
 * name plus the {@code .fifo} suffix exactly and then verifies the provisioned queue's FIFO attributes; the
 * message group identifier is validated by this bean's own constructor against the SQS grammar of 1 to 128
 * alphanumeric and punctuation characters. A blank, whitespace-bearing or overlong group identifier is
 * therefore a refusal to start, not a failure once per submission.
 *
 * <p>The remaining collaborators are beans rather than properties: {@code SqsTemplate}, which
 * {@code com.cardemo.config.AwsConfig} owns together with the LocalStack endpoint override, so no client is
 * ever constructed here and no code path can reach a live endpoint;
 * {@link com.cardemo.service.shared.DateValidationService}, which is mandatory; {@link Clock}, which
 * supplies both the instant and the zone the monthly and yearly periods resolve against; and an
 * {@link org.springframework.beans.factory.ObjectProvider} of {@link io.micrometer.tracing.Tracer}, which is
 * optional by design so this bean is correct with or without a tracing stack. This bean needs
 * send capability on one queue and asks for nothing more, which is Rule 1 Clause D least privilege
 * satisfied structurally.
 *
 * <h2>What crosses the queue boundary</h2>
 *
 * <p>The payload is exactly the three fields {@code JobSubmissionMessage} declares - report name, start date
 * and end date - and that contract is fixed by the queue's own {@code RECORDSIZE(80) RECORDFORMAT(FIXED)}
 * declaration. Identity travels beside it, in at most four bounded message headers: the correlation
 * identifier under {@link CorrelationIdFilter#CORRELATION_ID_HEADER}, the trace and span identifiers, and
 * the originating transaction as a four-character literal. Headers rather than payload fields deliberately -
 * the batch tier consumes the typed body, so adding identity to it would change a contract that parity
 * depends on, while a header is metadata the consumer may use or ignore.
 *
 * <p>Every propagated value is bounded before it is attached: non-blank, no longer than the correlation
 * identifier's own 64-character limit, and restricted to alphanumerics, hyphen and underscore. That last
 * restriction is not decoration. The correlation identifier originates in an inbound HTTP header, so it is
 * attacker-influenced input on a path ending in a message an operator reads, and excluding carriage return,
 * line feed, colon and NUL makes header injection structurally impossible. A value failing the check is
 * dropped, never truncated: a partially rewritten identifier correlates to nothing while looking as though
 * it should.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>{@code FileAccessException} carrying {@code Unable to Write TDQ (JOBS)...}</strong> - the
 *       publish failed. A warning is logged first, carrying the {@code RESP:} and {@code REAS:} prefixes of
 *       the diagnostic at {@code app/cbl/CORPT00C.cbl:L529} so that the legacy operator's log line is still
 *       recognisable. The {@code RESP:} slot carries a symbolic reason - {@code unavailable} for a publisher
 *       or transport failure, {@code timeout} when the caller's deadline elapsed, {@code interrupted} when
 *       the submitting thread was interrupted, {@code error} for anything else - and the {@code REAS:} slot
 *       carries the failing exception's class name. Neither slot carries the resolved endpoint or the
 *       throwable, because both would publish the account-bearing queue URL on a channel an operator reads;
 *       the cause travels on the exception instead, where a handler still has all of it. {@code unavailable}
 *       normally means the queue does not exist; {@code timeout} means it exists but did not answer within
 *       {@value #SEND_DEADLINE_SECONDS} seconds, so check emulator load rather than provisioning.</li>
 *   <li><strong>Startup fails saying the message group identifier is invalid.</strong>
 *       {@code carddemo.aws.sqs.report-message-group-id} resolved to a blank, whitespace-bearing or overlong
 *       value. SQS permits 1 to 128 alphanumeric and punctuation characters and nothing else, and the value
 *       is asserted at construction so the failure is a refusal to start rather than one rejected submission
 *       per user. That key is a <em>literal</em> in {@code application.yml} rather than an environment
 *       indirection, and no profile overrides it, so reaching this failure means either the base profile was
 *       edited or an external property source overrode the key. Remedy: restore it to the logical queue
 *       name, {@code carddemo-report-jobs}, which is what the {@code DISPOSITION(MOD)} append semantics
 *       require.</li>
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
 *   </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable after construction and safe to share. All seven fields are {@code private final} and none is
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
     * The closed set of report names this application will submit or accept, in the order
     * {@code app/cbl/CORPT00C.cbl} assigns them.
     *
     * <p>Published so that {@code com.cardemo.config.BatchConfig} enforces the same set on the consuming side
     * without restating the three literals. Finding M-12: a set defined in one place cannot drift, and the
     * queue boundary needs the same rule at both ends because a message may arrive from a redelivery issued
     * before a deployment.
     */
    public static final Set<String> PERMITTED_REPORT_NAMES =
            Set.of(REPORT_NAME_MONTHLY, REPORT_NAME_YEARLY, REPORT_NAME_CUSTOM);

    /**
     * The only shape a parameter date may take: ten characters, {@code yyyy-MM-dd}, exactly as
     * {@code app/cbl/CORPT00C.cbl:L60-L71} assembles it from its {@code PIC 9} components.
     *
     * <p>Anchored at both ends, so a value carrying a trailing line feed or any other suffix is refused rather
     * than matched on its prefix. It deliberately checks shape and not validity: whether {@code 2022-02-31}
     * is a date is {@link DateValidationService}'s decision, which reports a severity code the caller acts on.
     */
    private static final Pattern PARAMETER_DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

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
     * The legacy operation this publish replaces, {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at
     * {@code app/cbl/CORPT00C.cbl:L515-L523}.
     *
     * <p>Carried on the failure so that the diagnostic can name <em>what</em> was attempted rather than
     * leaving the slot empty. The source's own verb is used, not the SDK operation name: an operator reading
     * the line is reconciling it against the COBOL program, and {@code WRITEQ TD} is the term that appears
     * there and in {@code app/csd/CARDDEMO.CSD}'s queue definition. It is a compile-time literal, so it
     * cannot vary by request and cannot carry anything derived from input.
     */
    private static final String OPERATION_WRITEQ_TD = "WRITEQ TD";

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
     * The job name on the first card, {@code //TRNRPT00 JOB 'TRAN REPORT'} at
     * {@code app/cbl/CORPT00C.cbl:L83-L84}.
     *
     * <p>Published as the notification subject, because the job name and its description are the only
     * identification the notified party had in the source: JES2 named this job when it reported completion.
     */
    private static final String JOB_NAME = "TRNRPT00";

    /**
     * The job description on the same card, the quoted {@code 'TRAN REPORT'} of
     * {@code app/cbl/CORPT00C.cbl:L84}, byte for byte including the single space.
     */
    private static final String JOB_DESCRIPTION = "TRAN REPORT";

    /**
     * The notification card, {@code // NOTIFY=&SYSUID} at {@code app/cbl/CORPT00C.cbl:L85-L86}, recorded
     * verbatim.
     *
     * <p>This literal is the entire provenance of the notification publish. It is card two of the seventeen
     * and asks JES2 to report the job's completion; the SQS message that replaces the deck carries the
     * job's <em>parameters</em> and has nowhere to put its <em>notification</em> instruction, which is the
     * gap the notification topic fills.
     *
     * <p><strong>No user identity is published, and that is the faithful outcome rather than a compromise.</strong>
     * The card is a {@code FILLER ... VALUE} literal: the program never substitutes a value into it and holds
     * no field for one, so the identity behind {@code &SYSUID} is resolved by whatever environment reads the
     * job in - for a deck handed to the internal reader through {@code DDNAME(INREADER)}
     * ({@code app/csd/CARDDEMO.CSD:L499-L505}) that is the submitting region, not the terminal operator this
     * program served. Publishing a signed-on user identifier here would therefore invent a binding the source
     * does not have, and would put an identity into a notification channel for no parity gain.
     */
    private static final String JOB_NOTIFY_CARD = "// NOTIFY=&SYSUID";

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
     * The closed reason vocabulary for a failed publish, occupying the {@code RESP} slot of the diagnostic
     * that reproduces {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at
     * {@code app/cbl/CORPT00C.cbl:L529}.
     *
     * <p>{@code unavailable} is the publisher or transport failing to deliver. The source always had a
     * {@code RESP2} value because CICS always set one; a publisher failure may carry no comparable code, and
     * Rule 1 Clause F asks that missing information be named rather than guessed at.
     */
    private static final String REASON_UNAVAILABLE = "unavailable";

    /** The caller's deadline elapsed before the publish completed. */
    private static final String REASON_TIMEOUT = "timeout";

    /** The submitting thread was interrupted while awaiting the publish. */
    private static final String REASON_INTERRUPTED = "interrupted";

    /** Any other runtime failure, so the vocabulary is total rather than only covering what was foreseen. */
    private static final String REASON_ERROR = "error";

    /**
     * Upper bound on how long a report submission waits for its publish to complete, in seconds.
     *
     * <p>This is a <em>caller</em> deadline layered on top of the SDK deadlines that
     * {@code com.cardemo.config.AwsConfig} sets on the shared client, and it is deliberately the tighter of
     * the two. Those client deadlines are sized for the batch writers, which upload multi-hundred-kilobyte
     * fixed-width objects on the same client; an online submission is a single small message with a user
     * waiting on a screen, and the legacy path it replaces could not have waited that long either - a CICS
     * task that stops responding is terminated by the region's own transaction timeout long before thirty
     * seconds. Ten seconds is therefore generous for the work and still bounded well inside any plausible
     * front-end timeout.
     *
     * <p>Its purpose is not to hide a slow queue but to make the failure <em>deterministic</em>: without a
     * caller deadline this method's worst case is whatever the client, the retry policy and the transport
     * agree on. That is not a rhetorical point: {@code SqsTemplate.send} is compiled to
     * {@code unwrapCompletionException(sendAsync(...))} over a bare {@code CompletableFuture.join()}, so the
     * synchronous form waits without any bound at all and offers no handle with which to abandon the
     * request. The deadline enforced here is asserted by the timeout and cancellation tests in
     * {@code ReportSubmissionServiceTest}.
     */
    private static final long SEND_DEADLINE_SECONDS = 10L;

    /**
     * Message header carrying the request's correlation identifier onto the queue, using the same name the
     * HTTP boundary uses so one identifier spans the whole path.
     *
     * <p>{@link CorrelationIdFilter#CORRELATION_ID_HEADER} is the single source of that name. This is what
     * makes an online submission and the publish hop it triggers reconcilable. The identifier is
     * <strong>new capability rather than a translation</strong>: the specification motivates it by analogy with
     * {@code EIBTRNID}, but that field is CICS-supplied and occurs <strong>zero times under {@code app/}</strong>,
     * so nothing in the frozen corpus is being replaced. See {@code CorrelationIdFilter} for the full census.
     *
     * <p><strong>Where this header reaches, corrected.</strong> An earlier revision of this comment said the
     * listener "neither reads this header nor restores it into the diagnostic context", and described the
     * correlation as a two-hop join through the SQS deduplication identifier. <strong>That is withdrawn: it
     * describes behaviour this application does not have.</strong> {@code BatchConfig.ReportJobQueueListener}
     * reads this header and installs it into the diagnostic context for the duration of the launch —
     * {@code restoreDiagnosticContext} puts it under {@code CorrelationIdFilter.MDC_KEY_CORRELATION_ID} before
     * the job is launched and {@code releaseDiagnosticContext} removes exactly what it installed afterwards, by
     * key rather than by clearing, because the listener container's threads are pooled. So <strong>one
     * identifier spans the HTTP request, the publish, the listener, the launch and every log record the batch
     * run emits</strong>, and no join is required.
     *
     * <p>Two honest qualifications remain, and the first is easy to state wrongly. The listener accepts the
     * header only when it is <strong>propagatable</strong> — non-blank, no longer than 128 characters and
     * entirely visible ASCII — falling back to the SQS deduplication identifier otherwise. That guard is
     * <strong>defence in depth rather than a path a caller can reach</strong>: a value arriving through this
     * application's HTTP boundary has already been screened by
     * {@link CorrelationIdFilter#MAX_CORRELATION_ID_LENGTH}, which is <strong>64</strong> characters and stricter
     * than the listener's 128, and a value failing that screen is replaced with a generated identifier before it
     * is ever published. So the fallback serves a message published by some other producer, or one whose header
     * was stripped in transit — not an over-long value from a caller here.
     *
     * <p>Second, the job parameters themselves are still built from the report name, the two dates and the
     * deduplication identifier: the correlation identifier travels in the diagnostic context, not in the
     * parameter set, so it does not participate in Spring Batch job-instance identity. That is deliberate — a
     * correlation identifier in the parameter set would make every resubmission a new job instance.
     */
    private static final String HEADER_CORRELATION_ID = CorrelationIdFilter.CORRELATION_ID_HEADER;

    /**
     * Message header carrying interoperable W3C trace context, so a consumer parents onto this publish hop.
     *
     * <p><strong>A bespoke {@code X-Trace-Id} and {@code X-Span-Id} pair is not a substitute.</strong> Such a
     * pair names the identifiers without establishing parentage: a consumer has to be told that those two
     * headers exist and how to assemble a parent context from them, and nothing outside this repository is. The
     * one hop distributed tracing is here to show - the online submission joined to the batch run it triggers -
     * would then be the one hop that could not be reconstructed from the message.
     * {@link CorrelationIdFilter#TRACE_PARENT_HEADER} is the standard form, extracted by every OpenTelemetry
     * and Micrometer Tracing consumer with no configuration, and the name and the composition rule are owned by
     * that class rather than re-declared here.
     */
    private static final String HEADER_TRACE_PARENT = CorrelationIdFilter.TRACE_PARENT_HEADER;

    /**
     * Message header naming the originating legacy transaction, always the compile-time literal
     * {@link #TRANSACTION_ID}.
     *
     * <p>Bounded metadata in the strictest sense: the value is a four-character constant, so this header
     * cannot grow, cannot vary by request and cannot carry anything derived from input. It exists so a
     * consumer, or an operator reading a queue, can attribute a message to {@code CR00} without parsing the
     * payload.
     */
    private static final String HEADER_SOURCE_TRANSACTION = "X-Carddemo-Transaction";

    /** Span name for the publish hop, fixed so it is a low-cardinality key in the tracing backend. */
    private static final String SPAN_NAME_PUBLISH = "carddemo.report.submit";

    /** Span tag naming the logical queue the publish targeted. Never the physical or account-qualified name. */
    private static final String SPAN_TAG_QUEUE = "messaging.destination";

    /** Span tag carrying the symbolic outcome reason when a publish fails. Never a throwable. */
    private static final String SPAN_TAG_ERROR = "error";

    /**
     * Longest header value this bean will propagate, in characters.
     *
     * <p>Matches {@link CorrelationIdFilter#MAX_CORRELATION_ID_LENGTH} so the correlation identifier is
     * bounded identically on both sides of the hop, and it comfortably admits a whole
     * {@value CorrelationIdFilter#TRACE_PARENT_HEADER} value - two version characters, a 32-character trace
     * identifier, a 16-character parent identifier, two flag characters and three hyphens, fifty-five in all. A
     * value longer than this is dropped rather than truncated: a truncated identifier correlates to nothing and
     * is worse than an absent one, because it looks like a real identifier that simply does not match.
     */
    private static final int MAX_HEADER_VALUE_LENGTH = CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH;

    /**
     * Lower bound on the length of a FIFO message group identifier, from the SQS contract.
     *
     * <p>SQS requires 1 to 128 characters. The lower bound is what rejects a blank value, which is the case
     * a null check cannot see: {@code ""} and {@code "   "} are both non-null and both fail at send time,
     * one request at a time, instead of at startup.
     */
    private static final int MIN_MESSAGE_GROUP_ID_LENGTH = 1;

    /** Upper bound on the length of a FIFO message group identifier, from the SQS contract. */
    private static final int MAX_MESSAGE_GROUP_ID_LENGTH = 128;

    /**
     * The characters a propagated header value may contain: unreserved URL characters only.
     *
     * <p>Deliberately narrower than the group-identifier class. A header value is derived from the diagnostic
     * context, which is populated from an inbound HTTP header, so it is attacker-influenced input on a path
     * that ends in a message an operator will read. Restricting it to alphanumerics, hyphen and underscore
     * makes header injection structurally impossible - a carriage return, a line feed, a colon and a NUL are
     * all outside the class - and every value this bean actually propagates is admitted by it: the bounded
     * correlation token {@link CorrelationIdFilter} already validated, and a
     * {@value CorrelationIdFilter#TRACE_PARENT_HEADER} value, whose fields are lowercase hexadecimal joined by
     * the hyphen this class already permits.
     */
    private static final Pattern HEADER_VALUE_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

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
         * Rejects an incompletely or implausibly populated message at construction.
         *
         * <p>The source could not express a partially built deck: the parameter fields are fixed-width
         * areas that always hold something, and the report name is assigned on every reachable arm before
         * the submission paragraph is performed. Requiring all three here reproduces that, and does so at
         * the one point where the omission would otherwise reach the queue.
         *
         * <p><strong>Null-checking alone is not validation here.</strong> With only a null check, an
         * arbitrary and unbounded report name travels to the queue and then into an <em>identifying</em> job
         * parameter, which the batch repository stores and keys a job instance on. Two things follow from the
         * source that make a closed set the correct rule rather than a length cap. {@code WS-REPORT-NAME} is
         * {@code PIC X(10)} at {@code app/cbl/CORPT00C.cbl:L58}, so no longer value could exist on the
         * mainframe at all; and the only three values ever moved into it are the literals at {@code :L214},
         * {@code :L240} and {@code :L433}. Anything else is not a report this application knows how to run, so
         * it is refused here rather than carried to a step that would refuse it later with less context.
         *
         * <p>The dates are checked for shape only - ten characters, {@code yyyy-MM-dd}, digits and dashes -
         * because whether a shaped value is a real calendar date is
         * {@link DateValidationService}'s decision and it reports a severity code the caller acts on. What
         * this check removes is the class of value that could never have been assembled by
         * {@code :L60-L71}: one carrying a control character, a separator, or a length the fixed-width
         * parameter card could not hold.
         *
         * @throws IllegalArgumentException if the report name is not one of the three source literals, or if
         *     either date is not ten characters of {@code yyyy-MM-dd} shape
         */
        public JobSubmissionMessage {
            Objects.requireNonNull(reportName, "reportName must not be null");
            Objects.requireNonNull(startDate, "startDate must not be null");
            Objects.requireNonNull(endDate, "endDate must not be null");
            if (!PERMITTED_REPORT_NAMES.contains(reportName)) {
                throw new IllegalArgumentException("reportName must be one of " + PERMITTED_REPORT_NAMES
                        + ", the three literals app/cbl/CORPT00C.cbl:L214, :L240 and :L433 move into"
                        + " WS-REPORT-NAME PIC X(10); the presented value is " + reportName.length()
                        + " characters and is not one of them");
            }
            requireParameterDateShape(startDate, "startDate");
            requireParameterDateShape(endDate, "endDate");
        }

        /**
         * Refuses a parameter date that the fixed-width card of {@code app/cbl/CORPT00C.cbl:L60-L71} could
         * not have carried.
         *
         * <p>The value itself is never echoed. A rejected date is untrusted input by definition, and a
         * message that quoted it would carry whatever it contained into a log line; the field name and the
         * observed length are what an operator needs and are all that is reported.
         *
         * @param value the presented date, never {@code null}
         * @param field the field name, for the diagnostic
         * @throws IllegalArgumentException if the value is not ten characters of {@code yyyy-MM-dd} shape
         */
        private static void requireParameterDateShape(final String value, final String field) {
            if (!PARAMETER_DATE_SHAPE.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " must be ten characters of yyyy-MM-dd shape, as"
                        + " app/cbl/CORPT00C.cbl:L60-L71 assembles it from PIC 9 components; the presented"
                        + " value is " + value.length() + " characters and does not match that shape");
            }
        }

        /**
         * Renders the three fields as the one canonical string the envelope signature covers.
         *
         * <p>Signing a canonical rendering of the fields rather than the serialised body is deliberate: a
         * signature over the body would break the moment the encoder changed a space, a field order or an
         * escape, and it would authenticate a representation rather than a meaning. Every field is validated
         * above to exclude the separator, so the rendering is unambiguous - no value can contain a line feed
         * and therefore no two distinct messages can render identically.
         *
         * @return the canonical rendering, never {@code null}
         */
        String canonicalForm() {
            return reportName + '\n' + startDate + '\n' + endDate;
        }
    }

    /**
     * The authenticity envelope that makes a queue message provably this application's own.
     *
     * <p><strong>Structural validity is not authenticity, and the queue supplies nothing else.</strong> A
     * listener that replaces the JES2 internal reader and launches a job from any structurally valid message on
     * the queue has no barrier at all in this topology: the queue is an emulator queue with static local
     * credentials and the emulator's community edition enforces no authorisation, so any process able to reach
     * the emulator port could submit a report job, choose its period and name the job instance. Network
     * placement narrows who can reach the port - that is what {@code docker-compose.yml} contributes - but it
     * cannot distinguish one reachable principal from another, so the distinction is made here and in
     * {@code com.cardemo.config.BatchConfig} instead.
     *
     * <p>The mechanism is a keyed message authentication code over the message's canonical form, carried as an
     * ordinary message header. A publisher without the key cannot produce a valid code, and the consumer
     * refuses a message whose code is absent, malformed or wrong before it launches anything. The comparison
     * is {@link MessageDigest#isEqual(byte[], byte[])}, which does not short-circuit, so a caller cannot
     * recover the expected value one byte at a time by measuring how long a rejection takes.
     *
     * <p><strong>The key is derived, never reused.</strong> The material is the application's existing signing
     * key - mandatory in all four profiles, environment-indirected with no committed default, and already
     * refused at startup when too short - and a single-purpose key is derived from it by taking the code of a
     * fixed label under it. Deriving rather than reusing means a message code can never be replayed as a token
     * and a token can never be replayed as a message code, which is the whole point of domain separation; and
     * it means this control introduces no new secret for an operator to distribute, so it cannot be
     * accidentally left unconfigured. There is deliberately <strong>no unsigned mode</strong>: a control that
     * can be switched off by omitting configuration is a control that will be off.
     */
    public static final class JobSubmissionEnvelope {

        /**
         * The header carrying the code. A message attribute, so it survives the queue unchanged and is
         * visible to the consumer before any payload is interpreted.
         */
        public static final String SIGNATURE_HEADER = "X-CardDemo-Message-Signature";

        /**
         * The version prefix of a rendered code. Present so that a future algorithm change is a new prefix
         * rather than an ambiguous byte string, and so that a consumer can refuse a version it does not
         * implement instead of comparing bytes produced by different rules.
         *
         * <p>{@code v2} rather than {@code v1}: {@code v1} authenticated the three payload fields alone, so a
         * captured pair could be replayed under a fresh submission identifier for as long as the key lived.
         * Every {@code v1} code is refused here, which is the point of versioning the prefix.
         */
        public static final String SIGNATURE_VERSION = "v2";

        /**
         * How long a signed submission stays acceptable, in seconds.
         *
         * <p>A compile-time constant rather than a property, deliberately. A lifetime an operator can set is a
         * lifetime an operator can set to something useless, and the interval a legitimate submission needs is
         * a property of this topology rather than of a deployment: the publish is bounded at
         * {@value #SEND_DEADLINE_SECONDS} seconds, the queue's deduplication window is five minutes, and the
         * listener drains continuously. Fifteen minutes therefore covers every ordinary delivery, including a
         * redelivery after a visibility timeout, with a wide margin - and it is the same figure the sealed
         * snapshot uses, so an operator has one number to remember rather than two.
         */
        public static final long LIFETIME_SECONDS = 900L;

        /**
         * Tolerance, in seconds, for the two clocks disagreeing.
         *
         * <p>Producer and consumer are the same process in this topology, so the tolerance is nominal. It
         * exists because they need not be: a submission published a moment before the consumer's clock catches
         * up must not be refused as issued in the future, and a one-minute allowance is small enough that it
         * widens the replay window by a negligible amount.
         */
        public static final long CLOCK_SKEW_TOLERANCE_SECONDS = 60L;

        /** The keyed hash. Available on every supported runtime, so no configuration selects it. */
        private static final String MAC_ALGORITHM = "HmacSHA256";

        /**
         * The domain-separation label. Any change to it invalidates every previously issued code, which is
         * why it carries the version that {@link #SIGNATURE_VERSION} renders.
         */
        private static final String KEY_DERIVATION_LABEL = "carddemo/sqs/jobs-envelope/v2";

        /** The separator between the rendered code's four parts. */
        private static final char PART_SEPARATOR = ':';

        /** How many parts a rendered code carries after its version prefix. */
        private static final int RENDERED_PART_COUNT = 4;

        /**
         * The shape a submission identifier must have to be bound into a code.
         *
         * <p>Neither the part separator nor the canonical form's line feed may appear in it, or two distinct
         * submissions could render identically. {@link #newDeduplicationId()} produces a
         * {@link java.util.UUID}, which satisfies this comfortably; the pattern is the check rather than the
         * assumption, because the identifier reaching {@code verify} came off the wire.
         */
        private static final Pattern SUBMISSION_ID_SHAPE = Pattern.compile("[0-9A-Za-z._-]{1,128}");

        /**
         * The single-purpose key, derived once at construction.
         *
         * <p><strong>Finding SEC-001, severity High, RESOLVED here.</strong> The application signing key is
         * <em>not</em> retained - not by this type and not by either of the two beans that build one. It is
         * read as a constructor argument, used to derive this key, and the byte arrays holding both it and the
         * derived material are overwritten with zeroes in a {@code finally} block before the constructor
         * returns. A {@code String} field holding the signing key could not be overwritten at all: strings are
         * immutable and live until collection, so the key would sit in the heap - and in any heap dump - for
         * the life of the process, which is {@code CWE-316}. What remains is this derived key, which is useless
         * for anything but authenticating a submission on this queue.
         *
         * <p>Deriving once rather than per call also removes the per-message derivation the previous
         * arrangement performed, whose intermediate arrays were left to the collector on every sign and every
         * verify.
         */
        private final SecretKeySpec purposeKey;

        /**
         * Derives the single-purpose key from the application signing key and retains nothing else.
         *
         * <p><b>Side effects.</b> None beyond zeroing its own temporaries. <b>Inputs.</b> The application
         * signing key, which is mandatory in all four profiles, environment-indirected with no committed
         * default, and already refused at startup when too short.
         *
         * @param signingKey the application signing key; must not be {@code null} or blank
         * @throws IllegalArgumentException if the signing key is absent or blank. The value is never echoed,
         *     not even by length
         */
        public JobSubmissionEnvelope(final String signingKey) {
            if (signingKey == null || signingKey.isBlank()) {
                throw new IllegalArgumentException("the message envelope key is derived from "
                        + "carddemo.security.jwt.signing-key, which must be configured; it has no default "
                        + "anywhere in this repository and an unsigned queue message is never accepted");
            }
            final byte[] configured = signingKey.getBytes(StandardCharsets.UTF_8);
            byte[] derived = null;
            try {
                derived = mac(configured, KEY_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
                // SecretKeySpec copies the array it is given, so the copy below is the retained material and
                // the array itself can be - and is - destroyed.
                this.purposeKey = new SecretKeySpec(derived, MAC_ALGORITHM);
            } finally {
                Arrays.fill(configured, (byte) 0);
                if (derived != null) {
                    Arrays.fill(derived, (byte) 0);
                }
            }
        }

        /**
         * Renders the code for one submission.
         *
         * <p><b>Side effects.</b> None. <b>Inputs.</b> All three are this application's own values, produced
         * immediately before the publish.
         *
         * @param message the message about to be published; must not be {@code null}
         * @param submissionId the transport deduplication identifier this message will carry, generated before
         *     signing so that the code and the identifier cannot disagree; must match
         *     {@link #SUBMISSION_ID_SHAPE}
         * @param issuedAt the instant the submission is published, from the injected clock; must not be
         *     {@code null}
         * @return the rendered code: {@value #SIGNATURE_VERSION}, {@code =}, the submission identifier, the
         *     issue and expiry instants as epoch seconds and the lowercase hexadecimal code, separated by
         *     {@code :}; never {@code null}
         * @throws NullPointerException if {@code message} or {@code issuedAt} is {@code null}
         * @throws IllegalArgumentException if {@code submissionId} is not of the required shape
         */
        public String sign(final JobSubmissionMessage message, final String submissionId,
                final Instant issuedAt) {

            Objects.requireNonNull(message, "message must not be null");
            Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            requireSubmissionIdShape(submissionId);
            final long issued = issuedAt.getEpochSecond();
            final long expires = issued + LIFETIME_SECONDS;
            return SIGNATURE_VERSION + '=' + submissionId + PART_SEPARATOR + issued + PART_SEPARATOR
                    + expires + PART_SEPARATOR
                    + hexadecimal(code(canonicalForm(message, submissionId, issued, expires)));
        }

        /**
         * Decides whether a presented code was produced by a holder of the key, for this message, for this
         * submission identifier, and recently.
         *
         * <p><strong>Finding SEC-002, severity High, RESOLVED here.</strong> The code now covers the
         * submission identifier and the validity window as well as the three payload fields, so a captured
         * pair can no longer be replayed. The two properties that closes are worth stating separately.
         * Binding the identifier means a replay under a <em>fresh</em> identifier - which is what would
         * otherwise produce a second Spring Batch job instance from one captured message, because the
         * identifier is an identifying job parameter - fails the code check. Binding the window means an
         * exact replay of the original identifier stops being accepted at all once the window closes, where
         * before it was refused only by the launcher's own once-only guarantee, and only for as long as the
         * job repository retained the instance.
         *
         * <p>An exact replay <em>inside</em> the window is deliberately still verified rather than refused
         * here: it carries the original identifier, so it resolves to the same job instance and the launcher
         * refuses it as the at-least-once delivery it is. That is the queue redelivering, not an attack, and
         * treating it as an attack would turn an ordinary visibility-timeout redelivery into an error.
         *
         * <p><b>Side effects.</b> None. <b>Inputs.</b> {@code message} and {@code presented} are untrusted;
         * {@code submissionId} is the transport's own deduplication identifier, and the code must have been
         * issued for exactly it.
         *
         * @param message the message as parsed from the body; must not be {@code null}
         * @param submissionId the submission identifier the transport delivered, or the caller's sentinel when
         *     the transport carried none; may be {@code null}
         * @param presented the header value exactly as delivered, possibly {@code null} or of another type
         * @param now the instant to measure the validity window against, from the injected clock; must not be
         *     {@code null}
         * @return the outcome, never {@code null}; {@link Verification#VERIFIED} only when every check passed
         * @throws NullPointerException if {@code message} or {@code now} is {@code null}
         */
        public Verification verify(final JobSubmissionMessage message, final String submissionId,
                final Object presented, final Instant now) {

            Objects.requireNonNull(message, "message must not be null");
            Objects.requireNonNull(now, "now must not be null");
            if (presented == null) {
                return Verification.ABSENT;
            }
            final String rendered = presented.toString();
            final String prefix = SIGNATURE_VERSION + '=';
            if (!rendered.startsWith(prefix)) {
                return Verification.UNSUPPORTED_VERSION;
            }
            final String[] parts = rendered.substring(prefix.length()).split(String.valueOf(PART_SEPARATOR));
            if (parts.length != RENDERED_PART_COUNT) {
                return Verification.MALFORMED;
            }
            if (!SUBMISSION_ID_SHAPE.matcher(parts[0]).matches()) {
                return Verification.MALFORMED;
            }
            final long issued;
            final long expires;
            try {
                issued = Long.parseLong(parts[1]);
                expires = Long.parseLong(parts[2]);
            } catch (final NumberFormatException notATimestamp) {
                return Verification.MALFORMED;
            }
            final byte[] offered = fromHexadecimal(parts[3]);
            if (offered == null) {
                return Verification.MALFORMED;
            }
            // The identifier the code was issued for must be the identifier the transport delivered. Checked
            // explicitly as well as cryptographically: the code check below would fail anyway, but a caller
            // that must tell an operator WHY a submission was refused cannot distinguish a mismatched
            // identifier from a forged code once both have collapsed into one boolean.
            if (!Objects.equals(parts[0], submissionId)) {
                return Verification.SUBMISSION_ID_MISMATCH;
            }
            // Compared before the code, because a window check is a comparison of two longs this application
            // produced and reveals nothing a forger could use.
            final long seconds = now.getEpochSecond();
            if (seconds + CLOCK_SKEW_TOLERANCE_SECONDS < issued) {
                return Verification.NOT_YET_VALID;
            }
            if (seconds - CLOCK_SKEW_TOLERANCE_SECONDS > expires) {
                return Verification.EXPIRED;
            }
            final byte[] expected = code(canonicalForm(message, parts[0], issued, expires));
            // MessageDigest.isEqual does not short-circuit, so a caller cannot recover the expected value one
            // byte at a time by measuring how long a rejection takes.
            return MessageDigest.isEqual(expected, offered)
                    ? Verification.VERIFIED
                    : Verification.CODE_MISMATCH;
        }

        /**
         * Why a presented code was accepted or refused.
         *
         * <p>The distinctions exist for the operator, never for the publisher: the consumer logs the constant
         * name and answers a refused submission by discarding it silently, so nothing here reaches whoever
         * published the message. Collapsing them into one boolean was the previous arrangement and it left an
         * operator unable to tell a stale submission from a forged one, which are different incidents.
         */
        public enum Verification {

            /** Every check passed: the code is this application's own, for this message and this identifier. */
            VERIFIED,

            /** No code was presented at all. There is no unsigned mode, so this is a refusal. */
            ABSENT,

            /** A code was presented, but not of a version this implementation produces. */
            UNSUPPORTED_VERSION,

            /** The code's shape is wrong: the wrong number of parts, or a part that will not parse. */
            MALFORMED,

            /** The code was issued for a different submission identifier than the transport delivered. */
            SUBMISSION_ID_MISMATCH,

            /** The code's validity window has closed. */
            EXPIRED,

            /** The code's validity window has not opened, beyond the tolerated clock skew. */
            NOT_YET_VALID,

            /** The code does not match the one this key produces for this message. */
            CODE_MISMATCH;

            /**
             * Reports whether the submission may be launched.
             *
             * @return {@code true} only for {@link #VERIFIED}
             */
            public boolean verified() {
                return this == VERIFIED;
            }
        }

        /**
         * Renders everything the code covers as one unambiguous string.
         *
         * <p>Signing a canonical rendering of the fields rather than the serialised body is deliberate: a
         * signature over the body would break the moment the encoder changed a space, a field order or an
         * escape, and it would authenticate a representation rather than a meaning. Every component is
         * validated to exclude the line feed - the three payload fields by
         * {@link JobSubmissionMessage}'s own constructor, the identifier by {@link #SUBMISSION_ID_SHAPE}, and
         * the two instants because they are decimal renderings of a {@code long} - so no two distinct
         * submissions can render identically.
         *
         * @param message the message
         * @param submissionId the submission identifier
         * @param issued the issue instant as epoch seconds
         * @param expires the expiry instant as epoch seconds
         * @return the canonical rendering, never {@code null}
         */
        private static String canonicalForm(final JobSubmissionMessage message, final String submissionId,
                final long issued, final long expires) {

            return message.canonicalForm() + '\n' + submissionId + '\n' + issued + '\n' + expires;
        }

        /**
         * Refuses a submission identifier that could not be bound unambiguously.
         *
         * @param submissionId the identifier about to be signed
         * @throws IllegalArgumentException if it is absent or of the wrong shape. The value is not echoed: on
         *     the signing path it is this application's own, but the same check guards the shape of a value
         *     that arrived off the wire
         */
        private static void requireSubmissionIdShape(final String submissionId) {
            if (submissionId == null || !SUBMISSION_ID_SHAPE.matcher(submissionId).matches()) {
                throw new IllegalArgumentException("the submission identifier is bound into the envelope code,"
                        + " so it must be a bounded run of unreserved characters carrying neither the part"
                        + " separator nor a line feed; the presented value is not");
            }
        }

        /**
         * Computes the code of a canonical form under the derived purpose key.
         *
         * @param canonicalForm the exact bytes to authenticate, interpreted as UTF-8
         * @return the raw code bytes, never {@code null}
         */
        private byte[] code(final String canonicalForm) {
            return mac(this.purposeKey, canonicalForm.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Applies the keyed hash to raw key material, for the one derivation the constructor performs.
         *
         * @param key the key material
         * @param data the bytes to authenticate
         * @return the code, never {@code null}
         */
        private static byte[] mac(final byte[] key, final byte[] data) {
            return mac(new SecretKeySpec(key, MAC_ALGORITHM), data);
        }

        /**
         * Applies the keyed hash.
         *
         * <p>The two checked exceptions the interface declares cannot occur here and are converted rather
         * than propagated: the algorithm is one every supported runtime implements, and the key is a non-empty
         * specification this class constructed. Converting them keeps the two public operations free of a
         * checked contract their callers could not act on, and nothing is swallowed - the original is the
         * cause.
         *
         * @param key the key specification
         * @param data the bytes to authenticate
         * @return the code, never {@code null}
         */
        private static byte[] mac(final SecretKeySpec key, final byte[] data) {
            try {
                final Mac mac = Mac.getInstance(MAC_ALGORITHM);
                mac.init(key);
                return mac.doFinal(data);
            } catch (final NoSuchAlgorithmException | InvalidKeyException impossible) {
                throw new IllegalStateException(MAC_ALGORITHM
                        + " is required by every supported runtime and the key is a non-empty specification"
                        + " built by this class, so neither failure is reachable", impossible);
            }
        }

        /**
         * Renders bytes as lowercase hexadecimal.
         *
         * @param bytes the bytes to render
         * @return the rendering, never {@code null}
         */
        private static String hexadecimal(final byte[] bytes) {
            final StringBuilder rendered = new StringBuilder(bytes.length * 2);
            for (final byte value : bytes) {
                rendered.append(Character.forDigit((value >> 4) & 0xF, 16));
                rendered.append(Character.forDigit(value & 0xF, 16));
            }
            return rendered.toString();
        }

        /**
         * Parses lowercase or uppercase hexadecimal, refusing anything else.
         *
         * @param rendered the presented hexadecimal, never {@code null}
         * @return the bytes, or {@code null} when the value is not an even-length run of hexadecimal digits
         */
        private static byte[] fromHexadecimal(final String rendered) {
            if (rendered.isEmpty() || rendered.length() % 2 != 0) {
                return null;
            }
            final byte[] parsed = new byte[rendered.length() / 2];
            for (int index = 0; index < parsed.length; index++) {
                final int high = Character.digit(rendered.charAt(index * 2), 16);
                final int low = Character.digit(rendered.charAt(index * 2 + 1), 16);
                if (high < 0 || low < 0) {
                    return null;
                }
                parsed[index] = (byte) ((high << 4) | low);
            }
            return parsed;
        }
    }

    /**
     * The operator notification standing in for {@code // NOTIFY=&SYSUID}, {@code app/cbl/CORPT00C.cbl:L85-L86}.
     *
     * <p>It carries what the notified party needs to recognise the job and nothing else: the job name JES2
     * would have reported, the job description from the same card, and the period the submission covers. It
     * deliberately carries <strong>no identity of any kind</strong> - no user identifier, no terminal, no
     * network address - for the reason set out on {@link #JOB_NOTIFY_CARD}.
     *
     * <p>A record rather than a formatted string, so the subscriber parses fields instead of scraping text,
     * and so this class cannot accidentally interpolate something it should not.
     *
     * @param jobName     the job name from {@code app/cbl/CORPT00C.cbl:L84}, never {@code null}
     * @param jobDescription the quoted description from the same card, never {@code null}
     * @param reportName  the resolved period name, one of the three the screen offers, never {@code null}
     * @param startDate   the inclusive period start in {@code yyyy-MM-dd}, never {@code null}
     * @param endDate     the inclusive period end in {@code yyyy-MM-dd}, never {@code null}
     */
    public record JobNotification(String jobName, String jobDescription, String reportName,
            String startDate, String endDate) {

        /**
         * Validates every component, because a notification with a null field would publish the word
         * {@code null} to a subscriber.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public JobNotification {
            Objects.requireNonNull(jobName, "jobName must not be null");
            Objects.requireNonNull(jobDescription, "jobDescription must not be null");
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
     *
     * <p><strong>Validated at construction against the service's own limits, not merely null-checked.</strong>
     * Amazon SQS accepts a message group identifier of 1 to {@value #MAX_MESSAGE_GROUP_ID_LENGTH} characters
     * drawn from the visible ASCII range - alphanumerics and punctuation, no space and nothing outside
     * {@code U+0021} to {@code U+007E}. A value that breaks any of those rules is rejected by the service, and
     * the rejection arrives at the <em>first report submission</em>: an operator sees a report that will not
     * submit, in a code path whose own validation has already passed, and the actual cause is a configuration
     * value set once at deployment. Worse, a blank value is not a null and would previously have passed
     * construction unchallenged. Validating here converts that latent per-request failure into a startup
     * failure with a remedy in the message, which is the same trade the queue-name check in
     * {@code com.cardemo.config.AwsConfig} makes for the same reason.
     */
    private final String reportMessageGroupId;

    /**
     * Lowest code point Amazon SQS accepts in a message group identifier, {@code '!'}. The space at
     * {@code U+0020} is deliberately below it: a leading, trailing or embedded space is rejected by the
     * service, which is precisely why a blank value cannot be allowed through as "present but empty".
     */
    private static final char MIN_MESSAGE_GROUP_ID_CHAR = '\u0021';

    /** Highest code point Amazon SQS accepts in a message group identifier, {@code '~'}. */
    private static final char MAX_MESSAGE_GROUP_ID_CHAR = '\u007E';

    /** Property key behind {@link #reportMessageGroupId}, named once so messages and binding cannot drift. */
    private static final String KEY_MESSAGE_GROUP_ID = "carddemo.aws.sqs.report-message-group-id";

    /**
     * Property key behind {@link #notificationTopic}, named once for the same reason: the binding and any
     * message that reports a problem with it read from one literal.
     */
    private static final String KEY_NOTIFICATION_TOPIC = "carddemo.aws.sns.notification-topic";

    /**
     * Property key the {@link #envelope} is derived from.
     *
     * <p>Deliberately the <em>application</em> signing key rather than a key of this service's own.
     * {@link JobSubmissionEnvelope} derives a single-purpose key from it, so the two uses are
     * cryptographically separated while the operator has exactly one secret to supply - one that is already
     * mandatory in every profile, environment-indirected, and refused at startup when it is too short. A
     * second secret would be a second thing to forget, and a control that can be left unconfigured is a
     * control that is off.
     */
    private static final String KEY_ENVELOPE_SIGNING_KEY = "carddemo.security.jwt.signing-key";

    /**
     * The optional tracer used to open a child span around the publish.
     *
     * <p>A provider rather than the tracer itself because tracing must be optional here. The tracing bridge
     * and its exporter are wired by {@code com.cardemo.config.ObservabilityConfig}, and this bean has to be
     * constructible and correct without them - in a unit test, and in any context assembled without a
     * tracing stack. When no tracer is present the publish proceeds untraced and the message still carries
     * the correlation and trace identifiers from the diagnostic context, so the hop remains correlatable
     * even when it is not spanned.
     */
    private final ObjectProvider<Tracer> tracerProvider;

    /**
     * The notification publisher owned by {@code com.cardemo.config.AwsConfig}.
     *
     * <p>Injected, never constructed, for the same reason the queue publisher is: the region, the credential
     * resolution and the emulator endpoint override are applied to the client beneath it by profile
     * configuration, and constructing one here would place that decision outside the profile layering.
     */
    private final SnsTemplate snsTemplate;

    /**
     * The notification topic, bound from {@code carddemo.aws.sns.notification-topic}.
     *
     * <p>Named explicitly on every publish rather than set as a template default, because
     * {@code com.cardemo.config.AwsConfig} deliberately leaves the template with no default destination - a
     * default would resolve the topic ARN eagerly during context refresh and so make startup depend on the
     * emulator being up.
     */
    private final String notificationTopic;

    /**
     * The authenticator that makes a submission provably this application's own.
     *
     * <p><strong>Finding SEC-001, severity High, RESOLVED here.</strong> This field replaces a
     * {@code String} that held the application signing key for the life of the bean. The key is now read as a
     * constructor argument, handed to {@link JobSubmissionEnvelope} which derives a single-purpose key from
     * it, and never retained here in any form - see that type for how it destroys its own temporaries. The
     * previous arrangement also re-derived the purpose key on every publish, leaving an intermediate array
     * per message for the collector; this one derives once, at startup.
     *
     * <p>Constructed rather than injected, deliberately. A bean would have to be declared somewhere, and the
     * two places that need one - this service and the queue listener in {@code com.cardemo.config.BatchConfig}
     * - are a publisher and a consumer that must not share mutable state; each holding its own instance over
     * the same derived key keeps the dependency graph honest about that, and the derivation is deterministic
     * so the two agree by construction.
     */
    private final JobSubmissionEnvelope envelope;

    /**
     * Assembles the bean.
     *
     * <p>Constructor injection throughout, with no field annotation, no setter and no lookup, so the type is
     * constructible in a unit test with four test doubles and three strings and needs no application
     * context. Every argument is required; a missing collaborator fails fast at construction with a message
     * naming it, rather than at the first request with a null dereference.
     *
     * <p><strong>The FIFO message group is validated here, not at send time.</strong> A null check alone let
     * a blank, whitespace-only or overlong value survive construction and fail once per submission, which is
     * the wrong failure at the wrong time: it turns a single misconfigured property into an intermittent,
     * per-user error whose cause is invisible from the message the user sees. SQS constrains the identifier
     * to {@value #MIN_MESSAGE_GROUP_ID_LENGTH} to {@value #MAX_MESSAGE_GROUP_ID_LENGTH} alphanumeric and
     * punctuation characters, and that grammar is asserted at startup so a violation is a refusal to start.
     * The rejected value is named in the failure message deliberately - a message group identifier is a
     * configured routing token, not a secret, and an operator cannot fix what they cannot see.
     *
     * <p>The two queue names are null-checked only, and that is not an oversight. Their grammar is already
     * enforced at startup by {@code com.cardemo.config.AwsConfig}, which requires the physical name to equal
     * the logical name plus the {@code .fifo} suffix exactly and then verifies the provisioned queue's
     * attributes. Restating that here would be the duplication Rule 1 Clause C forbids, and a blank value
     * cannot get past it: blank never equals a logical name plus a suffix.
     *
     * <p>The tracer is supplied as a provider rather than as a hard dependency because tracing is
     * infrastructure this bean must work without. Its presence adds a child span around the publish; its
     * absence changes nothing else, because the identifiers propagated onto the message come from the
     * diagnostic context {@link CorrelationIdFilter} maintains, which does not require a tracer either.
     *
     * @param sqsTemplate             the publisher owned by {@code com.cardemo.config.AwsConfig}
     * @param snsTemplate             the notification publisher, also owned by
     *                                {@code com.cardemo.config.AwsConfig}, standing in for the
     *                                {@code // NOTIFY=&SYSUID} half of card two of the job deck
     * @param dateValidationService   the validator standing in for {@code CALL 'CSUTLDTC'}
     * @param clock                   the time source standing in for {@code FUNCTION CURRENT-DATE}
     * @param tracerProvider          the optional tracer used to open a child span around the publish
     * @param reportQueueName         the physical queue name, from
     *                                {@code carddemo.aws.sqs.report-queue}
     * @param reportQueueLogicalName  the logical queue name, from
     *                                {@code carddemo.aws.sqs.report-queue-logical-name}
     * @param reportMessageGroupId    the deterministic FIFO group, from
     *                                {@value #KEY_MESSAGE_GROUP_ID}
     * @param notificationTopic       the operator notification topic, from
     *                                {@value #KEY_NOTIFICATION_TOPIC}
     * @param envelopeSigningKey      the application signing key, from
     *                                {@value #KEY_ENVELOPE_SIGNING_KEY}, from which
     *                                {@link JobSubmissionEnvelope} derives the single-purpose key that makes
     *                                a submission provably this application's own. Finding SEC-001: it is
     *                                consumed here to construct {@link #envelope} and is not retained by this
     *                                service in any field
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the message group identifier is blank, longer than
     *                               {@value #MAX_MESSAGE_GROUP_ID_LENGTH} characters, or contains a character
     *                               Amazon SQS does not accept - each of which the service would otherwise
     *                               reject at the first report submission rather than here
     */
    public ReportSubmissionService(
            final SqsTemplate sqsTemplate,
            final SnsTemplate snsTemplate,
            final DateValidationService dateValidationService,
            final Clock clock,
            final ObjectProvider<Tracer> tracerProvider,
            @Value("${carddemo.aws.sqs.report-queue}") final String reportQueueName,
            @Value("${carddemo.aws.sqs.report-queue-logical-name}") final String reportQueueLogicalName,
            @Value("${" + KEY_MESSAGE_GROUP_ID + "}") final String reportMessageGroupId,
            @Value("${" + KEY_NOTIFICATION_TOPIC + "}") final String notificationTopic,
            @Value("${" + KEY_ENVELOPE_SIGNING_KEY + "}") final String envelopeSigningKey) {
        this.sqsTemplate = Objects.requireNonNull(sqsTemplate, "sqsTemplate must not be null");
        this.snsTemplate = Objects.requireNonNull(snsTemplate, "snsTemplate must not be null");
        this.dateValidationService =
                Objects.requireNonNull(dateValidationService, "dateValidationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tracerProvider = Objects.requireNonNull(tracerProvider, "tracerProvider must not be null");
        this.reportQueueName = Objects.requireNonNull(reportQueueName, "reportQueueName must not be null");
        this.reportQueueLogicalName =
                Objects.requireNonNull(reportQueueLogicalName, "reportQueueLogicalName must not be null");
        this.reportMessageGroupId = validatedMessageGroupId(
                Objects.requireNonNull(reportMessageGroupId, "reportMessageGroupId must not be null"));
        this.notificationTopic =
                Objects.requireNonNull(notificationTopic, "notificationTopic must not be null");
        // The key is used here and not kept: the envelope derives its own single-purpose key from it and
        // zeroes every temporary, and this constructor's parameter goes out of scope with the frame.
        this.envelope = new JobSubmissionEnvelope(envelopeSigningKey);
    }


    /**
     * Proves the configured message group identifier is one Amazon SQS will accept.
     *
     * <p>Three conditions, each with its own message because each has its own remedy: the value must be
     * non-blank, because a set-but-empty property satisfies placeholder resolution and even beats a profile
     * default; it must be no longer than {@value #MAX_MESSAGE_GROUP_ID_LENGTH} characters; and every character
     * must lie in the visible ASCII range {@value #MIN_MESSAGE_GROUP_ID_CHAR} to
     * {@value #MAX_MESSAGE_GROUP_ID_CHAR}, which excludes the space, every control character and everything
     * outside ASCII.
     *
     * <p>An offending character is reported by <em>position and code point</em> rather than by echoing the
     * configured value, matching the convention this class and {@code com.cardemo.config.AwsConfig} follow for
     * every configuration failure: the message must be actionable without becoming a disclosure channel. The
     * length failure reports the length for the same reason.
     *
     * @param messageGroupId the non-{@code null} configured identifier
     * @return the same value, once proved acceptable
     * @throws IllegalStateException if the value is blank, over-long or contains an unaccepted character,
     *                               aborting the context refresh
     */
    private static String validatedMessageGroupId(final String messageGroupId) {
        if (messageGroupId.isBlank()) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Property '%s' is set but blank. Amazon SQS requires a message group identifier of at "
                            + "least one visible character on a FIFO queue, and every report submission "
                            + "carries this one value so that submissions are delivered in the order the "
                            + "extrapartition queue DEFINE TDQUEUE(JOBS) appended them. Set the property to a "
                            + "stable literal; the migration uses 'carddemo-report-jobs'.",
                    KEY_MESSAGE_GROUP_ID));
        }
        if (messageGroupId.length() < MIN_MESSAGE_GROUP_ID_LENGTH
                || messageGroupId.length() > MAX_MESSAGE_GROUP_ID_LENGTH) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Property '%s' is %d characters long, which is outside the %d to %d characters Amazon "
                            + "SQS accepts for a message group identifier. The value is not reported. Resize "
                            + "it; the migration uses 'carddemo-report-jobs'.",
                    KEY_MESSAGE_GROUP_ID, messageGroupId.length(),
                    MIN_MESSAGE_GROUP_ID_LENGTH, MAX_MESSAGE_GROUP_ID_LENGTH));
        }
        for (int index = 0; index < messageGroupId.length(); index++) {
            final char candidate = messageGroupId.charAt(index);
            if (candidate < MIN_MESSAGE_GROUP_ID_CHAR || candidate > MAX_MESSAGE_GROUP_ID_CHAR) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Property '%s' holds a character Amazon SQS does not accept in a message group "
                                + "identifier at position %d: code point U+%04X. Only alphanumerics and "
                                + "punctuation are accepted - every character must lie between U+%04X and "
                                + "U+%04X - so the space, tabs, newlines and all non-ASCII characters are "
                                + "rejected. The offending value is not reported, only where the character "
                                + "sits and what it is. Remove it; the migration uses 'carddemo-report-jobs'.",
                        KEY_MESSAGE_GROUP_ID, index, (int) candidate,
                        (int) MIN_MESSAGE_GROUP_ID_CHAR, (int) MAX_MESSAGE_GROUP_ID_CHAR));
            }
        }
        return messageGroupId;
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
     * to date.</strong> The class documentation carries the six-step proof and the mechanism that makes it
     * work - {@code WS-CURDATE-N REDEFINES WS-CURDATE} at {@code app/cpy/CSDAT01Y.cpy:L23}. This method
     * reproduces the source's four steps literally, in order:
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
        // a month-to-date reading overlooks.
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
     * @throws ValidationException when the confirmation is a supplied value the one-byte field does not
     *     accept; a blank confirmation is a prompt and returns a screen instead
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
            // A REDISPLAY, NOT A REJECTION - and this arm previously threw.
            //
            // The source gives all three non-publishing arms the same mechanism: STATE 1 here, STATE 3 for
            // 'N' at :L480-L483 and STATE 4 for anything else at :L484-L493 each MOVE 'Y' TO WS-ERR-FLG and
            // each PERFORM SEND-TRNRPT-SCREEN. Nothing in the program distinguishes them by severity. What
            // distinguishes them is what the caller DID: a blank gate is the operation asking a question,
            // 'N' is the caller answering it, and any other character is a value the one-byte field does not
            // accept. Only the third is a malformed request, so only the third stays a 400.
            //
            // Answering 400 to the prompt also disagreed with the two sibling confirmation gates -
            // com.cardemo.service.billing.BillPaymentService and
            // com.cardemo.service.transaction.TransactionAddService both answer 200 on their blank arm - so
            // one API had two contradictory conventions for one legacy idiom.
            //
            // Two details of STATE 1 differ from STATE 3 and are preserved. The form is NOT cleared: :L464
            // has no PERFORM INITIALIZE-ALL-FIELDS, so the caller's period selection survives and the
            // confirmation can simply be added. And the cursor IS placed, by MOVE -1 TO CONFIRML at :L470,
            // which STATE 3 does not do.
            return Optional.of(sendTrnrptScreen(mapArea,
                    MSG_CONFIRM_PREFIX + period.reportName() + MSG_CONFIRM_SUFFIX, true, false,
                    CURSOR_CONFIRM));
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

        // Card two of the seventeen, :L85-L86 // NOTIFY=&SYSUID. The queue message carries the job's
        // parameters; this carries its notification instruction, which the message has nowhere to put.
        notifyJobSubmitted(period);

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
     *       The warning in {@link #publishFailure(Span, String, Throwable)} carries both prefixes in that
     *       form so an operator who knew the legacy line still recognises this one, with a symbolic reason
     *       standing in for the response code and the failing exception's class name for the reason code.
     *       Neither slot may carry the resolved endpoint or the throwable - see that method for why.</li>
     * </ul>
     *
     * <p>The failure is rethrown as a typed exception carrying the original as its cause, so nothing is
     * swallowed and the root cause survives, which is what Rule 1 Clause B requires. It is not retried
     * <em>by this method</em>: the source does not retry, and adding an application-level retry would change
     * how many messages a failing queue eventually receives. The SDK's own bounded standard retry policy,
     * configured once on the shared client by {@code com.cardemo.config.AwsConfig}, still applies beneath -
     * that is transport-level redelivery of a request that never arrived, not a second submission, so it
     * changes no observable count.
     *
     * <p><strong>Boundedness.</strong> The publish is issued asynchronously and awaited for at most
     * {@value #SEND_DEADLINE_SECONDS} seconds, and an expired or interrupted publish is cancelled with
     * interruption rather than left running. Without a caller deadline this method's worst case is whatever
     * the client, the retry policy and the transport agree on, which is documented nowhere and cannot be
     * asserted in a test; with it, a slow queue produces the source's own literal at a known bound. The
     * deadline is deliberately tighter than the client's, which is sized for the batch writers that share it.
     *
     * <p><strong>Propagation.</strong> The message carries at most three bounded headers - the correlation
     * identifier, the W3C trace context, and the originating transaction literal - and a child span wraps the
     * publish when a tracer is configured. The payload itself remains exactly the three fields
     * {@link JobSubmissionMessage} declares: identity travels in headers, never inside the typed body, so the
     * payload contract the batch tier consumes is unchanged.
     *
     * <p><strong>Deduplication.</strong> An explicit
     * {@code MessageDeduplicationId} is generated once per call by {@link #newDeduplicationId()}. That alone is
     * what carries the parity, and it carries it unconditionally: an explicit identifier takes precedence over
     * the queue's body hash, so two identical submissions are both delivered even against a queue that still
     * reports {@code ContentBasedDeduplication=true}. This was confirmed against the emulator rather than
     * assumed - two identical bodies with distinct identifiers both arrived, where the same two bodies without
     * one collapsed. The queue is nevertheless provisioned with the attribute disabled by
     * {@code localstack-init/init-aws.sh}, as defence for any future publisher that omits an identifier;
     * {@code com.cardemo.config.AwsConfig} reports that attribute at startup but deliberately does not refuse
     * to start over it, because it is mutable by any holder of the queue and this send path does not depend on
     * it.
     *
     * <p><strong>Omitting the identifier loses submissions, and that is why it may not be omitted.</strong>
     * Setting no identifier leaves the queue's
     * body hash as the deduplication key, so two submissions of the same period - identical report name,
     * identical start and end dates, which is exactly what an operator re-submitting produces - collapse into
     * one inside the five-minute deduplication window. The second is accepted here, logged as published and
     * then discarded by the queue, with nothing on the caller's side to show it. That is not what the source
     * does: {@code DEFINE TDQUEUE(JOBS) ... DISPOSITION(MOD)} <em>appends</em>, and {@code :L515-L523} writes
     * unconditionally with no idempotency key at all, so two identical writes produced two reader entries.
     *
     * <p>A fresh identifier per call is what reproduces that append, and it is not the invention of a guard the
     * source lacks - it is the mechanism by which the queue is told <em>not</em> to guard. What it does still
     * prevent is the one duplicate that would be an artefact of this implementation rather than of the caller's
     * intent: a transport-level retry by the shared client's bounded retry strategy re-sends the same request,
     * carrying the same identifier, and the queue collapses it. One submission therefore becomes exactly one
     * message however many times the transport tries.
     *
     * @param card the typed message standing in for the eighty-byte card images
     * @throws FileAccessException when the publish fails, carrying the source's literal and the cause
     */
    private void wirteJobsubTdq(final JobSubmissionMessage card) {
        final Span span = openPublishSpan();
        // Held outside the try so an abandoned publish can be cancelled: without that, a deadline that
        // expires leaves the request running on an SDK thread holding a connection, so a slow queue would
        // accumulate one orphaned publish per submission instead of failing cleanly.
        CompletableFuture<SendResult<JobSubmissionMessage>> pending = null;
        try {
            final Map<String, Object> headers = propagationHeaders(span);

            // Finding M-11, extended by finding SEC-002. The envelope code is added last, after the
            // propagation headers, so that the header map this method sends is the one the consumer verifies
            // against. It covers the three payload fields, the submission identifier and the validity window,
            // and nothing else: the propagation headers are diagnostic context, which the consumer validates
            // for shape rather than trusting, so signing them would authenticate values no decision is taken
            // on.
            //
            // One identifier per call, generated HERE and before signing, held in a local so the value bound
            // into the code is the same value that reaches the queue and the same value that could be logged.
            // Reading it twice from a generator would produce two identities and the consumer would refuse the
            // message; generating it after signing would leave it unbound, which is the replay SEC-002 names.
            final String deduplicationId = newDeduplicationId();
            headers.put(JobSubmissionEnvelope.SIGNATURE_HEADER,
                    this.envelope.sign(card, deduplicationId, this.clock.instant()));

            // :L517-L523. The queue name and the message group are both configuration; the template and the
            // client beneath it belong to com.cardemo.config.AwsConfig, so no endpoint is named here.
            //
            // sendAsync plus an explicit bounded await, rather than send. This is NOT an asynchronous
            // handoff layered over a blocking call, because the synchronous form is itself exactly that:
            // AbstractMessagingTemplate.send(String, Message) is compiled to
            // unwrapCompletionException(sendAsync(queue, message)), and that helper is a bare
            // CompletableFuture.join() - so send() pays the identical handoff and then waits with NO
            // caller-visible bound and NO handle to abandon. SqsTemplateOptions exposes no send timeout
            // either (defaultPollTimeout governs receive), so this form is the only bounded synchronous
            // send available. It costs nothing extra and adds the deadline and the cancellation.
            pending = this.sqsTemplate.sendAsync(options -> options
                    .queue(this.reportQueueName)
                    .payload(card)
                    .messageGroupId(this.reportMessageGroupId)
                    .messageDeduplicationId(deduplicationId)
                    .headers(headers));
            final SendResult<JobSubmissionMessage> sendResult =
                    pending.get(SEND_DEADLINE_SECONDS, TimeUnit.SECONDS);

            // :L526-L527 WHEN DFHRESP(NORMAL) -> CONTINUE. The source is silent here; one informational line
            // is added because a submission that leaves the online tier with no trace at all cannot be
            // reconciled against the batch run it triggers. The message identifier is a service-generated
            // UUID: it names the message without naming the queue URL or the account that owns it.
            LOG.info("report job submission published to the {} queue as message {} in group {} carrying {} "
                            + "propagation headers, replacing {} fixed {}-byte job cards",
                    this.reportQueueLogicalName, sendResult.messageId(), this.reportMessageGroupId,
                    headers.size(), JOB_CARD_COUNT, JOB_CARD_LENGTH);
        } catch (final TimeoutException deadlineExceeded) {
            cancelQuietly(pending);
            throw publishFailure(span, REASON_TIMEOUT, deadlineExceeded);
        } catch (final InterruptedException interrupted) {
            cancelQuietly(pending);
            // Restore the flag before leaving so the interruption is reported rather than absorbed; the
            // caller decides what to do about it, and the user still sees the source's literal.
            Thread.currentThread().interrupt();
            throw publishFailure(span, REASON_INTERRUPTED, interrupted);
        } catch (final ExecutionException completedExceptionally) {
            // An asynchronous publish reports failure through the future, so the meaningful type is the
            // cause. It is unwrapped for classification and preserved as the exception's cause, which keeps
            // the failure indistinguishable from the synchronous form's for any caller.
            final Throwable cause = completedExceptionally.getCause() == null
                    ? completedExceptionally
                    : completedExceptionally.getCause();
            throw publishFailure(span, classify(cause), cause);
        } catch (final RuntimeException failure) {
            // :L528-L534 WHEN OTHER, for a failure raised before the future existed - a template that
            // rejects the request outright, for instance.
            cancelQuietly(pending);
            throw publishFailure(span, classify(failure), failure);
        } finally {
            endSpan(span);
        }
    }

    /**
     * Opens a child span around the publish, or returns {@code null} when no tracer is configured.
     *
     * <p>The span is what makes the outbound hop measurable: it is the only place the publish latency this
     * method now bounds can be observed, and without it the {@value #SEND_DEADLINE_SECONDS} second deadline
     * would be a number nobody could justify from evidence. The name is a fixed literal so it stays a
     * low-cardinality key, and the only tag added up front is the <em>logical</em> queue name, which is a
     * literal in {@code application.yml} and therefore provably free of an account identifier - the physical
     * name and the resolved URL are never tagged.
     *
     * @return the started span, or {@code null} when tracing is not configured
     */
    private Span openPublishSpan() {
        final Tracer tracer = this.tracerProvider.getIfAvailable();
        if (tracer == null) {
            return null;
        }
        return tracer.nextSpan()
                .name(SPAN_NAME_PUBLISH)
                .tag(SPAN_TAG_QUEUE, this.reportQueueLogicalName)
                .start();
    }

    /**
     * Ends a span if one was opened.
     *
     * <p>Called from a {@code finally} block so the span is closed on every path, including the deadline and
     * interrupt paths. An unended span is worse than an absent one: it is reported by the backend as an
     * incomplete trace and skews every latency percentile computed over that operation.
     *
     * @param span the span to end, or {@code null} when tracing is not configured
     */
    private static void endSpan(final Span span) {
        if (span != null) {
            span.end();
        }
    }

    /**
     * Publishes the operator notification that {@code // NOTIFY=&SYSUID} asked JES2 for,
     * {@code app/cbl/CORPT00C.cbl:L85-L86}.
     *
     * <p>Card two of the seventeen is a notification instruction rather than a job parameter. The typed queue
     * message that replaces the deck carries the three parameter values and has nowhere to put an instruction
     * to tell anyone the job exists, so that half of the card lands here. This is the only notification
     * publish in the application, and this is why {@code com.cardemo.config.AwsConfig} declares a notification
     * template at all.
     *
     * <p><strong>A failed notification does not fail the submission, and that is parity rather than
     * leniency.</strong> {@code NOTIFY=} is a courtesy JES2 performs after read-in: the job runs whether or
     * not the notification reaches anyone, and the source has no error path for it - unlike the queue write at
     * {@code :L515-L537}, which has an explicit {@code WHEN OTHER} arm, a message literal and a cursor
     * position. Raising here would invent a failure mode the source lacks and would reject a submission the
     * legacy system accepted.
     *
     * <p>Nothing is swallowed. The failure is logged at warning level <em>with the throwable</em>, so the
     * class name, the message and the whole cause chain survive in the log; what is deliberately not done is
     * converting it into a response. That is the distinction Rule 1 Clause B draws - an empty {@code catch} is
     * forbidden, a documented non-fatal outcome that preserves the cause is not.
     *
     * <p>It is not retried <em>by this method</em>, for the reason the queue publish is not: the source retries
     * nothing, and an application-level retry would change how many notifications a failing topic eventually
     * receives.
     *
     * <p><strong>Finding I-01, severity Low, DOCUMENTED.</strong> Beneath this method the shared client does
     * apply a bounded standard retry, and unlike the queue send there is no deduplication token to collapse a
     * repeat: a first publish that succeeded and then lost its response is sent again and <em>delivered
     * twice</em>. So a subscriber observes <strong>at least once</strong>, not exactly once. That is stated
     * rather than removed - suppressing transport retries here would trade a duplicated courtesy for a lost
     * one, and the mainframe's {@code NOTIFY} was itself best-effort and undeduplicated. A subscriber that must
     * not act twice on one notice is expected to be idempotent; the payload carries no identity to key on and
     * inventing one would change what the notified party receives.
     *
     * <p><strong>Acceptance is not delivery, and the guarantee is provisioned rather than asserted here.</strong>
     * This method reports a successful publish, and a successful publish is <em>acceptance by the service</em>,
     * which is not the same thing as delivery to anyone. A topic created with no subscriber makes that
     * distinction fatal to the capability, because every notification this method logged as published would be
     * accepted and immediately discarded. {@code localstack-init/init-aws.sh} therefore provisions a durable
     * inbox queue subscribed to the topic and <em>fails</em> when the topic reports no subscription, which is
     * the enforceable half of the guarantee; the log line below is honest about being the other half, since a
     * publisher cannot observe a subscriber's receipt. There is deliberately no delivery check here: it would
     * require this method to read the inbox, which is the operator's to read.
     *
     * <p><strong>Boundedness.</strong> This publish is synchronous on
     * the request thread, and the submission it follows has already succeeded - so whatever budget bounds it
     * bounds how long a successful request can be held open by a courtesy. Sharing the object and queue
     * clients' thirty-second budget, which is sized for a batch generation, would let an unreachable topic
     * delay a completed submission for thirty seconds before the failure was logged and discarded. The
     * notification client therefore carries its own much shorter budget, applied by
     * {@code com.cardemo.config.AwsConfig.applyNotificationPolicy}, so the worst case here is that budget.
     *
     * <p>The publish is deliberately <em>not</em> moved onto another thread. An executor would need lifecycle
     * management, would make the ordering of notifications relative to submissions nondeterministic, and would
     * let a shutdown drop a notification that had been reported as sent; correcting the budget where the budget
     * is declared achieves the same bound with none of that. The corollary is that the bound is a property of
     * the client rather than of this method, which is why this method imposes no deadline of its own -
     * two independent deadlines on one call would leave neither authoritative.
     *
     * <p>Side effects: one notification on {@link #notificationTopic}, or one warning line. Configuration:
     * the topic name only. Error modes: none propagate.
     *
     * @param period the resolved period, already validated and already published to the queue; never
     *        {@code null}
     */
    private void notifyJobSubmitted(final JobSubmissionMessage period) {
        final JobNotification notification = new JobNotification(JOB_NAME, JOB_DESCRIPTION,
                period.reportName(), period.startDate(), period.endDate());

        try {
            // The subject is what JES2 would have named: the job, then its quoted description from :L84. The
            // topic is named on the call because the template deliberately carries no default destination.
            this.snsTemplate.sendNotification(this.notificationTopic, notification,
                    JOB_NAME + " " + JOB_DESCRIPTION);

            // "accepted by" rather than "delivered to": the service acknowledges the publish, and delivery to
            // the subscribed inbox is the service's to perform. Wording that claimed more than the call proves
            // would be wrong here whatever the topic's subscriber count happened to be.
            LOG.info("operator notification for the {} period accepted by the {} topic for delivery to its "
                            + "subscribed inbox, replacing the {} card; the topic is provisioned with a "
                            + "subscriber by localstack-init/init-aws.sh, which fails when it has none",
                    period.reportName(), this.notificationTopic, JOB_NOTIFY_CARD);
        } catch (final RuntimeException failure) {
            // Non-fatal by design; see the method documentation. The period name is safe to log - it is one
            // of three fixed literals - and the two dates are not logged here because they add nothing to a
            // diagnostic about reachability.
            LOG.warn("operator notification for the {} period could not be published to the {} topic; "
                            + "the submission itself stands, because the {} card is a courtesy the source "
                            + "has no error path for",
                    period.reportName(), this.notificationTopic, JOB_NOTIFY_CARD, failure);
        }
    }

    /**
     * Cancels a publish that is still in flight, interrupting it if it has already started.
     *
     * <p>Cancelling an already-completed future is a documented no-op, so this needs no completion test.
     *
     * @param pending the future to abandon, or {@code null} if the publish was never issued
     */
    private static void cancelQuietly(final CompletableFuture<?> pending) {
        if (pending != null) {
            pending.cancel(true);
        }
    }

    /**
     * Mints the deduplication identity for one submission.
     *
     * <p><strong>Finding H-08, severity High.</strong> See {@link #wirteJobsubTdq(JobSubmissionMessage)} for why
     * the identity is explicit and why it is fresh on every call rather than derived from the message. Two
     * properties are required of it and a random identifier has both: it is distinct for every submission, so a
     * legitimate re-submission of the same period is delivered exactly as {@code DISPOSITION(MOD)} delivered it;
     * and it is fixed within one call, so a transport retry of that call re-sends the same value and the queue
     * collapses the duplicate.
     *
     * <p>Deliberately <em>not</em> derived from the report parameters, which would reinstate body-keyed
     * collapse under another name, and deliberately not the correlation identifier, which an upstream caller
     * controls and may repeat across genuinely separate submissions.
     *
     * <p>A random UUID renders as 36 characters from the alphabet SQS accepts for this attribute, well inside its
     * 128-character limit, so no validation or truncation is needed. It is an identity and not a secret: it
     * names a submission and carries nothing about it.
     *
     * @return a fresh deduplication identifier, never {@code null}
     */
    private static String newDeduplicationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Builds the bounded propagation headers the message carries.
     *
     * <p>Three headers at most, every one of them an identifier and none of them derived from the report
     * parameters, so the header set cannot grow with input. The correlation identifier comes from
     * {@link CorrelationIdFilter#currentCorrelationId()}, which returns {@code null} rather than a malformed
     * value; the trace context comes from the span when one was opened and otherwise from the diagnostic
     * context the tracing bridge maintains, so the hop is correlatable whether or not tracing is configured.
     * The source transaction header is a compile-time constant.
     *
     * <p>Every value passes {@link #propagatable(String)} before it is attached. That is not defensive
     * padding: the correlation identifier originates in an inbound HTTP header, so it is attacker-influenced
     * input on a path that ends in a message an operator will read, and a value carrying a carriage return
     * would be a header-injection vector. A value that fails the check is <em>dropped</em>, never truncated
     * and never sanitised, because a partially rewritten identifier correlates to nothing while looking as
     * though it should.
     *
     * @param span the span opened for this publish, or {@code null} when tracing is not configured
     * @return an insertion-ordered map of headers, possibly empty and never {@code null}
     */
    private Map<String, Object> propagationHeaders(final Span span) {
        final Map<String, Object> headers = new LinkedHashMap<>();

        putIfPropagatable(headers, HEADER_CORRELATION_ID, CorrelationIdFilter.currentCorrelationId());

        if (span == null) {
            putIfPropagatable(headers, HEADER_TRACE_PARENT, CorrelationIdFilter.currentTraceParent());
        } else {
            // The span's own identifiers rather than the diagnostic context's, so a consumer parents onto
            // this publish hop and not onto the request span that contains it.
            //
            // FINDING M-03: and the span's own sampling decision alongside them, rather than an asserted
            // "sampled". The trace-flags octet used to be hard-coded, so with the production sampling
            // probability of one in ten this header told nine consumers in ten to record a child of a trace
            // this process had already decided to drop - producing an orphaned half-trace at the collector.
            // sampled() may be null when the decision is deferred, which traceParent reports as sampled.
            final TraceContext context = span.context();
            putIfPropagatable(headers, HEADER_TRACE_PARENT, CorrelationIdFilter.traceParent(
                    context.traceId(), context.spanId(), context.sampled()));
        }

        headers.put(HEADER_SOURCE_TRANSACTION, TRANSACTION_ID);
        return headers;
    }

    /**
     * Adds a header when its value is present and safe to propagate.
     *
     * @param headers the map under construction
     * @param name    the header name, always a compile-time constant
     * @param value   the candidate value, possibly {@code null}
     */
    private static void putIfPropagatable(final Map<String, Object> headers, final String name,
            final String value) {
        if (propagatable(value)) {
            headers.put(name, value);
        }
    }

    /**
     * Reports whether a value may be attached to an outbound message.
     *
     * @param value the candidate, possibly {@code null}
     * @return {@code true} when the value is non-blank, no longer than
     *         {@value #MAX_HEADER_VALUE_LENGTH} characters, and contains only alphanumerics, hyphen and
     *         underscore
     */
    private static boolean propagatable(final String value) {
        return value != null
                && !value.isEmpty()
                && value.length() <= MAX_HEADER_VALUE_LENGTH
                && HEADER_VALUE_PATTERN.matcher(value).matches();
    }

    /**
     * Maps a publish failure onto the closed reason vocabulary.
     *
     * @param failure the cause, already unwrapped from any future wrapper
     * @return {@link #REASON_UNAVAILABLE} when the publisher reported a messaging failure, otherwise
     *         {@link #REASON_ERROR}
     */
    private static String classify(final Throwable failure) {
        return failure instanceof MessagingOperationFailedException ? REASON_UNAVAILABLE : REASON_ERROR;
    }

    /**
     * Reports a failed publish on every channel and builds the exception to throw.
     *
     * <p>:L528-L534 {@code WHEN OTHER}. Three things happen here and each is deliberate.
     *
     * <p><strong>The log line keeps the legacy shape and drops the legacy leak.</strong>
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at {@code :L529} concatenates its operands with
     * no separator, and that shape is retained so an operator who knew the legacy line recognises this one.
     * What occupies the two slots has changed: the {@code RESP} slot carries a symbolic reason from the
     * closed vocabulary, and the {@code REAS} slot carries the failing exception's class name. It previously
     * carried {@code MessagingOperationFailedException.getEndpoint()}, which is the resolved queue endpoint
     * and therefore an account-bearing diagnostic - a High-severity disclosure on a channel an operator
     * reads - and the throwable itself was passed as the trailing SLF4J argument, which renders its message
     * and full stack and leaks the same values positionally, where no field-path masking rule in
     * {@code logback-spring.xml} can reach them. Neither is emitted now. A class name is a compile-time
     * symbol and cannot carry an endpoint, an account identifier or a credential.
     *
     * <p><strong>The cause is preserved, not swallowed.</strong> Rule 1 Clause B is satisfied by
     * classification plus propagation rather than by rendering: the reason vocabulary separates every remedy
     * an operator can act on, the class name names the exact type, and the throwable itself travels on the
     * returned exception where a caller - including an exception handler that decides an HTTP status - still
     * has all of it.
     *
     * <p><strong>The user-facing outcome is byte-identical to the source.</strong> The message is
     * {@link #MSG_UNABLE_TO_WRITE_TDQ}, byte for byte, three trailing periods included, on every failure path
     * without exception; a deadline and a transport error are the same event to the user, exactly as every
     * non-{@code NORMAL} response was one event at {@code :L528}.
     *
     * <p><strong>The failure names its resource and its operation, and deliberately carries no file
     * status.</strong> {@link FileAccessException} is built with the logical queue name and
     * {@link #OPERATION_WRITEQ_TD}, because {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at {@code :L515-L523}
     * states both, and an {@code ERROR} diagnostic that omitted them would report a failure without saying
     * what failed - which Rule 1 Clause A calls a meaningless error and Clause B calls lost context. The
     * status argument is {@code null} on purpose: that type renders a COBOL {@code FILE STATUS} as the fixed
     * literal {@code FILE STATUS IS: NNNN} followed by the status digits, and this program has no status to
     * render - it declares no {@code FILE-CONTROL} paragraph, no {@code SELECT} and no {@code FD} anywhere -
     * so a supplied value would fabricate an I/O condition that never occurred. The resulting instance reports
     * {@code hasIoStatus() == false}, which is what tells the reader of the diagnostic to render no status at
     * all rather than the absent-status placeholder.
     *
     * <p>The <em>logical</em> queue name is used, never the physical name and never the resolved URL: it is a
     * literal in {@code application.yml}, so it is provably free of an account identifier, and it is the same
     * value the warning above and the publish span already carry.
     *
     * <p>The cursor field {@code MONTHLYL} at {@code :L533} is documented on the caller but is not carried
     * on the exception: a failed publish is a server-side failure rather than a field-level rejection, so
     * unlike the eighteen validation outcomes there is no field for a caller to place a cursor on and no
     * field-marked exception type to carry one.
     *
     * @param span    the span opened for this publish, tagged with the outcome; may be {@code null}
     * @param reason  the symbolic reason from the closed vocabulary
     * @param failure the cause, preserved on the returned exception
     * @return the exception the caller must throw
     */
    private FileAccessException publishFailure(final Span span, final String reason,
            final Throwable failure) {
        if (span != null) {
            // The symbolic reason, never span.error(failure): that records the throwable's message on the
            // trace, which is the same account-bearing text this method refuses to log.
            span.tag(SPAN_TAG_ERROR, reason);
        }

        // :L529 DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD, concatenated with no separator.
        LOG.warn("RESP:{}REAS:{} unable to write the report job submission to the {} queue",
                reason, failure.getClass().getSimpleName(), this.reportQueueLogicalName);

        // :L530-L534 MOVE 'Y' TO WS-ERR-FLG, MOVE the literal TO WS-MESSAGE, MOVE -1 TO MONTHLYL,
        // PERFORM SEND-TRNRPT-SCREEN.
        //
        // The resource and the operation are named; the file status is not. Both halves are deliberate and
        // both are read by com.cardemo.controller.ReportController's ERROR diagnostic: the source writes
        // WRITEQ TD QUEUE('JOBS'), so naming the logical queue and that verb reports what the source itself
        // says, while a null status leaves hasIoStatus() false so that no FILE STATUS IS: NNNN rendering is
        // produced for a program that declares no file at all.
        return new FileAccessException(MSG_UNABLE_TO_WRITE_TDQ, null, this.reportQueueLogicalName,
                OPERATION_WRITEQ_TD, failure);
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
