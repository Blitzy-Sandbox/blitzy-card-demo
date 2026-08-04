/*
 * ******************************************************************
 * Program     : AccountController.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 REST Controller
 * Function    : Account view and account update endpoints.
 * Source      : app/csd/CARDDEMO.CSD transactions CAVW, CAUP
 *               -> app/cbl/COACTVWC.cbl (941 lines), mapset COACTVW
 *               -> app/cbl/COACTUPC.cbl (4,236 lines), mapset COACTUP @ 7756d89
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
package com.cardemo.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.dto.AccountUpdateResponse;
import com.cardemo.model.dto.AccountViewResponse;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountUpdateService.AccountUpdateResult;
import com.cardemo.service.account.AccountViewService;

/**
 * The two account operations of the CardDemo REST surface: the Java replacement for CICS transactions
 * {@code CAVW} and {@code CAUP} and the two programs they front.
 *
 * <p>Every legacy claim below cites a path and a line or line range in the frozen corpus under
 * {@code app/}, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the
 * {@code Source} lines in the file header record. Line numbers for {@code app/cbl/COACTUPC.cbl} were
 * taken after stripping the carriage returns that file carries - it is one of only five members of the
 * corpus terminated with CRLF - so they are logical line numbers and match a {@code tr -d '\r'}
 * reading of the member.</p>
 *
 * <h2>1. What it does</h2>
 *
 * <p>Two operations, and exactly two, numbered 4 and 5 of the seventeen that make up the whole REST
 * surface:</p>
 *
 * <ul>
 *   <li><strong>Account view</strong>, {@code GET} {@value #BASE_PATH}{@value #ACCOUNT_PATH}. Replaces
 *       transaction {@code CAVW}, defined at {@code app/csd/CARDDEMO.CSD:L317} against
 *       {@code PROGRAM(COACTVWC)} at {@code :L318}, which painted mapset {@code COACTVW}.</li>
 *   <li><strong>Account update</strong>, {@code PUT} {@value #BASE_PATH}. Replaces transaction
 *       {@code CAUP}, defined at {@code app/csd/CARDDEMO.CSD:L306} against {@code PROGRAM(COACTUPC)}
 *       at {@code :L308}, which painted mapset {@code COACTUP}.</li>
 *   </ul>
 *
 * <p>Both are boundary adapters and nothing more. Each performs one service call, translates the
 * outcome into a status and a body, and translates a typed failure into a problem detail. No business
 * rule, no arithmetic, no validation logic, no repository access, no {@code EntityManager} and no
 * {@code JdbcTemplate} appears here: all of it belongs to
 * {@code com.cardemo.service.account.AccountViewService} and
 * {@code com.cardemo.service.account.AccountUpdateService}, which carry the paragraph-level
 * correspondence to the two COBOL programs.</p>
 *
 * <p>{@code app/cbl/COACTUPC.cbl} is the largest program in the corpus at 4,236 lines and 88 paragraph
 * labels; {@code app/cbl/COACTVWC.cbl} is 941 lines and 38 paragraph labels. Neither is paginated, so
 * no page number and no next-page flag appears on either operation - and none could, because
 * {@code app/cpy/COCOM01Y.cpy} declares no such field.</p>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Build with the repository's pinned Maven wrapper, which resolves Maven 3.9.11 and compiles at
 * release 25 under {@code -Xlint:all -Werror} with {@code failOnWarning} enabled:</p>
 *
 * <pre>
 * ./mvnw -B -ntp clean compile
 * ./mvnw -B -ntp test        # Surefire 3.5.4, unit tree only
 * ./mvnw -B -ntp verify      # adds Failsafe 3.5.4 and the JaCoCo 80% line gate
 * </pre>
 *
 * <p>Where no JDK is installed on the host, the identical build runs in the pinned container image:</p>
 *
 * <pre>
 * docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -B -DskipTests compile
 * </pre>
 *
 * <p>Running it means running the application. The view operation reaches three tables through their
 * repositories and the update operation writes two of them, so exercising either end to end needs the
 * PostgreSQL 16 service from {@code docker compose up -d} and the three Flyway migrations applied. The
 * verified invocation recorded at {@code docs/project-guide.md:424-425} is
 * {@code curl -s http://localhost:8080/api/accounts/$ACCT_ID -H "Authorization: Bearer $TOKEN"}, where
 * {@code ACCT_ID} is any eleven-digit account identifier present in the seeded schema. The
 * header is not optional decoration: {@code com.cardemo.config.SecurityConfig} makes
 * {@code GET /api/accounts/{accountId}} require either authority, and only {@code POST /api/auth/**} and
 * five Actuator paths are {@code permitAll}, so the same command without the header returns 401 rather than
 * the documented body. Unit
 * coverage is cheapest as a Spring MVC standalone test over a constructor-injected instance with both
 * services stubbed - no application context, no database and no cloud emulator. Tests belong in
 * {@code src/test/java/com/cardemo/unit} and {@code src/test/java/com/cardemo/e2e}, never in this
 * package.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p><strong>This class reads no property, no profile and no classpath resource.</strong> It declares
 * no {@code @Value}, no {@code Environment} lookup and no {@code System.getenv} call, so there is no
 * key here that can be misconfigured or mistyped. The two paths, the request-parameter name and the
 * problem-detail property names below are compile-time constants.</p>
 *
 * <p>Four settings owned elsewhere nonetheless govern the observable behaviour of both operations, and
 * are named here so that a diagnosis starts in the right file:</p>
 *
 * <ul>
 *   <li>{@code SecurityConfig} declares the authorisation rules for {@value #BASE_PATH} centrally,
 *       together with {@code SessionCreationPolicy.STATELESS}. Nothing in this class contradicts those
 *       rules, no method-level security annotation is declared here, and no session is created or
 *       touched.</li>
 *   <li>{@code server.error.include-binding-errors}, set to {@code never} in
 *       {@code src/main/resources/application.yml}, suppresses the per-field detail of a bean-validation
 *       rejection. That is a deliberate secret-hygiene decision - a Hibernate binding message can echo
 *       a submitted value, and this operation's payload carries a social security number - so this
 *       class deliberately declares no handler for the framework's bean-validation exception and does
 *       not reinstate that detail.</li>
 *   <li>{@code server.error.include-message} and {@code include-stacktrace}, both {@code never}, are
 *       the reason the fixed problem details below reveal nothing about a cause. The cause stays
 *       attached to the exception and is logged, so diagnosis proceeds from the correlation
 *       identifier.</li>
 *   <li>{@code server.port}, defaulting to 8080, fixes the port both operations answer on.</li>
 * </ul>
 *
 * <p>{@code WebConfig} owns the two numeric converters this surface relies on - a strict digits-only
 * parser for identifiers and card numbers, mirroring {@code NUMVAL}, and a currency-aware parser used
 * for amounts only, mirroring {@code NUMVAL-C}. No parser, no formatter and no {@code @InitBinder} is
 * declared here, and none may be: the asymmetry between the two is behaviour, and re-implementing
 * either would accept input the legacy programs reject or reject input they accept.</p>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <table>
 *   <caption>Every status either operation can produce, and what to do about it</caption>
 *   <tr><th>Status</th><th>Cause and remedy</th></tr>
 *   <tr>
 *     <td>{@code 200 OK}</td>
 *     <td>For the view, the thirty-seven-field account projection. For the update, the applied write
 *         together with the {@code ACUP-CHANGE-ACTION} marker the next turn must echo. <strong>Note
 *         that a 200 on the update does not by itself prove both records were written</strong>: see the
 *         unreported customer lock in section 5, and read {@code errorMessage} on the body.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 400 Bad Request} with a problem detail</td>
 *     <td>A {@code ValidationException}. Its message is relayed byte for byte, so a legacy literal such
 *         as {@code Credit Limit must be supplied} or {@code Credit Limit is not valid} arrives exactly
 *         as the 3270 screen displayed it. The {@code failureKind} property distinguishes the two:
 *         {@code BLANK} means nothing was supplied, {@code INVALID} means something wrong was. They are
 *         not interchangeable - see section 5.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 400 Bad Request} with an empty body</td>
 *     <td>Bean validation rejected a width contract on the payload before the handler ran. The
 *         per-field detail is suppressed by {@code server.error.include-binding-errors: never} rather
 *         than lost; compare the submitted field widths against
 *         {@code com.cardemo.model.dto.AccountUpdateRequest}, whose {@code @Size} bounds are taken from
 *         {@code app/cpy-bms/COACTUP.CPY}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 401 Unauthorized}, empty body</td>
 *     <td>No bearer token, an expired token or an anonymous authentication reached the operation. Only
 *         sign-on, transaction {@code CC00}, is unauthenticated. Obtain a token from the sign-on
 *         endpoint and present it as a bearer credential.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 404 Not Found} with a problem detail</td>
 *     <td>A {@code RecordNotFoundException} from one of the three links of the lookup chain. The body is
 *         the SAME for all three - a fixed detail and the code {@code ACCOUNT_RECORD_NOT_FOUND} - because
 *         varying it would tell a caller which relation is missing the row. It names no link, no dataset
 *         and no key either: the record type this exception carries is a logical file name and its message
 *         is the status mapper's internal diagnostic, so both go to the {@code WARN} log instead, reached
 *         through the {@code correlationId} the body carries. The legacy screen said the same thing in
 *         three different sentences and those sentences survive on the success path; see section 5.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 409 Conflict} with a problem detail</td>
 *     <td>Either a {@code DataIntegrityException} - a referential constraint refused the write, reported as
 *         the code {@code ACCOUNT_WRITE_REFUSED} with the constraint name and the relation logged rather
 *         than returned, because together they map the schema - or a {@code ConcurrentUpdateException}
 *         whose outcome is {@code COULD_NOT_LOCK_CUSTOMER} or is unnamed. The {@code outcome} property
 *         tells the two apart, and the {@code errorCode} property separates
 *         {@value #ERROR_CODE_CONSTRAINT} from {@value #ERROR_CODE_UPDATE_CONFLICT} without parsing any
 *         text.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 412 Precondition Failed} with a problem detail</td>
 *     <td>The snapshot precondition failed: outcome {@code DATA_CHANGED_BEFORE_UPDATE}, detail
 *         {@code Record changed by some one else. Please review}. Re-read the account, resubmit with the
 *         fresh snapshot as {@code oldDetails}, and re-apply the edits. Do not retry blindly - the
 *         snapshot is the guarantee.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 423 Locked} with a problem detail</td>
 *     <td>Outcome {@code COULD_NOT_LOCK_ACCOUNT}, detail
 *         {@code Could not lock account record for update}. The affected dataset is logged rather than
 *         returned. Nothing was written. Safe to retry.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 428 Precondition Required} with a problem detail</td>
 *     <td>Outcome {@code CHANGES_NOT_CONFIRMED}: the update was submitted without asserting
 *         {@code ?}{@value #CONFIRM_PARAMETER}{@code =true}. No write was attempted. This is the
 *         explicit action mapping of the PF5 confirmation - see section 5 - and the detail carries the
 *         legacy prompt {@code Changes validated.Press F5 to save} verbatim.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 500 Internal Server Error} with a problem detail</td>
 *     <td>Outcome {@code LOCKED_BUT_UPDATE_FAILED}, detail {@code Update of record failed} - a rewrite
 *         failed after both records were locked and the snapshot matched; or a
 *         {@code FatalProcessingException}, whose abend code {@code 999} and batch return code
 *         {@code 12} are logged rather than returned; or any other typed failure. The detail is fixed and
 *         reveals nothing. Diagnose from the log line matching the request's {@code correlationId}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 502 Bad Gateway} with a problem detail</td>
 *     <td>A {@code FileAccessException}, the {@code FILE STATUS '9x'} family. The four-character
 *         rendering the legacy status renderer produced, the dataset and the verb are all logged rather
 *         than returned - returned together they said which internal dataset failed which verb with which
 *         status. This is a storage-layer fault, not a client fault.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 503 Service Unavailable} with a problem detail</td>
 *     <td>A {@code FileUnavailableException}, {@code FILE STATUS '35'}: a dataset the operation needs is
 *         not open, and which one is logged rather than returned. In the target that means the database is
 *         unreachable or the migrations have not run. Bring the compose stack up and let Flyway apply.</td>
 *   </tr>
 * </table>
 *
 * <h2>5. Legacy provenance and the three traps</h2>
 *
 * <p>Three places in {@code app/cbl/COACTUPC.cbl} produce working code that behaves differently from
 * the source when translated the obvious way. Each is reproduced rather than repaired, and each is
 * called out below so that a later edit does not mistake it for a defect.</p>
 *
 * <p><strong>Trap 1 - a customer lock failure is reported as success.</strong> The
 * post-write dispatch at {@code app/cbl/COACTUPC.cbl:L2606-L2615} is an {@code EVALUATE TRUE} that
 * tests exactly three conditions - {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} at {@code :L2607-L2608},
 * {@code LOCKED-BUT-UPDATE-FAILED} at {@code :L2609-L2610} and
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code :L2611-L2612} - before falling through
 * {@code WHEN OTHER} at {@code :L2613-L2614} to {@code SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE}. The
 * customer read-for-update guard at {@code :L3934-L3942} sets
 * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} and returns with nothing written, and that condition is
 * <strong>never tested</strong>. A customer lock failure therefore reports top-level success. This
 * class reproduces that: the operation answers {@code 200 OK}. The outcome stays distinguishable in two
 * places that lose nothing - the {@code errorMessage} member of the returned result still carries the
 * verbatim literal {@code Could not lock customer record for update} from {@code :L519-L520}, and the
 * handler emits a {@code WARN} log line naming the unreported lock. It is <strong>not</strong> converted into a
 * {@code 409} or a {@code 500}: parity is the contract, and this is reproduced live behaviour rather
 * than dead code, so Rule 1 Clause B does not reach it.</p>
 *
 * <p><strong>Trap 2 - five distinguishable outcomes, and collapsing them loses information.</strong>
 * {@code com.cardemo.exception.ConcurrentUpdateException} exposes exactly five, as a nested
 * {@code public enum Outcome}. Four are the message condition names declared at
 * {@code app/cbl/COACTUPC.cbl:L517-L524} and the fifth is a value of the change-action marker at
 * {@code :L654-L668}. Each reaches its own status here, and every one carries its own byte-exact legacy
 * literal, its own {@code outcome} property and its own {@code changeAction} marker, so no information
 * the 3270 screen displayed is lost. Note that the source has five outcomes and not six because
 * <strong>both rewrite failures set the same flag</strong>: the account rewrite failure at
 * {@code :L4076-L4081} sets {@code LOCKED-BUT-UPDATE-FAILED} with no rollback, and the customer rewrite
 * failure at {@code :L4095-L4103} sets that same flag and additionally issues
 * {@code EXEC CICS SYNCPOINT ROLLBACK} at {@code :L4099-L4101}.</p>
 *
 * <p><strong>Trap 3 - the snapshot travels verbatim or the endpoint breaks on every request.</strong>
 * {@code com.cardemo.model.dto.AccountUpdateRequest} carries both an {@code oldDetails} and a
 * {@code newDetails} group, mirroring {@code ACUP-OLD-DETAILS} at {@code app/cbl/COACTUPC.cbl:L669} and
 * {@code ACUP-NEW-DETAILS} at {@code :L757}. That is structural rather than optional: the target is
 * stateless, so the snapshot the change detection compares against cannot live on the server between
 * requests. The date-of-birth comparison at {@code :L4174-L4179} is the proof that no normalisation is
 * permissible - the live customer record holds a dash-separated date and is read at offsets
 * {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, whereas the snapshot field
 * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD} is declared {@code PIC X(08)} at {@code :L746} with a
 * {@code REDEFINES} at {@code :L747-L751} and is read at offsets {@code (1:4)}, {@code (5:2)} and
 * {@code (7:2)}. The snapshot date must therefore be carried in its <strong>compact, separator-free</strong>
 * form, and a whole-string comparison of the two would report a change on every single request. This
 * class passes both groups through untouched: no trimming, no stripping, no padding, no case folding, no
 * reformatting and no date parsing is applied to either.</p>
 *
 * <p>The case regime of that comparison is deliberately asymmetric, which is the second reason nothing
 * may be normalised at the boundary. At {@code app/cbl/COACTUPC.cbl:L4115-L4191} the account group
 * identifier is compared through {@code FUNCTION LOWER-CASE} on both sides at {@code :L4139-L4140};
 * the customer names, the three address lines, the state code and the country code are compared through
 * {@code FUNCTION UPPER-CASE} on both sides at {@code :L4152-L4167}, as is
 * {@code CUST-GOVT-ISSUED-ID} at {@code :L4172-L4173}; while the postal code at {@code :L4168}, both
 * telephone numbers at {@code :L4169-L4170}, the social security number at {@code :L4171}, the
 * electronic funds account identifier at {@code :L4181-L4182}, the primary-holder indicator at
 * {@code :L4183-L4185} and the credit score at {@code :L4186} are compared with no case function at
 * all. Folding case in either direction would change which updates are accepted.</p>
 *
 * <h2>6. The write sequence, and why the rollback asymmetry needs no counterpart</h2>
 *
 * <p>{@code 9600-WRITE-PROCESSING} at {@code app/cbl/COACTUPC.cbl:L3888-L4107} runs seven steps in a
 * fixed order, all of them owned by {@code com.cardemo.service.account.AccountUpdateService} and none
 * of them performed here. They are recorded so that a reader of this class knows what a {@code 200}
 * and each failure status stand for:</p>
 *
 * <ol>
 *   <li>Read the account record for update, {@code :L3894-L3903}. The guard at {@code :L3907-L3915}
 *       always sets {@code INPUT-ERROR}, sets {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} only while no
 *       message has already been staged, and branches to the exit.</li>
 *   <li>Read the customer record for update, {@code :L3921-L3930}, with the same-shaped guard at
 *       {@code :L3934-L3942} setting the flag Trap 1 shows is never tested.</li>
 *   <li>Compare the live records against the snapshot, {@code :L3947-L3948}, abandoning the write at
 *       {@code :L3950-L3952} when anything differs.</li>
 *   <li>Initialise the update images and move each edited field into place.</li>
 *   <li>Rewrite the account record, {@code :L4065-L4071}. On failure, {@code :L4076-L4081} sets
 *       {@code LOCKED-BUT-UPDATE-FAILED} and exits <strong>with no rollback</strong>.</li>
 *   <li>Rewrite the customer record, {@code :L4085-L4091}. On failure, {@code :L4095-L4103} sets the
 *       same flag and additionally issues {@code EXEC CICS SYNCPOINT ROLLBACK}.</li>
 *   <li>Exit, {@code :L4105-L4107}.</li>
 * </ol>
 *
 * <p>The rollback appearing on only one of the two failure paths reads like a defect and is not one. At
 * the account-rewrite failure point nothing has yet been written inside the unit of work, so the
 * transaction monitor releases the read-for-update locks at task end with no explicit action; at the
 * customer-rewrite failure point the account rewrite has already happened inside the same unit of work,
 * so an explicit backout is the only way to avoid a half-applied update. A single
 * {@code @Transactional(rollbackFor = Exception.class)} on the service method reproduces both branches
 * automatically, because each failure path throws before the commit point. That is a
 * <strong>mechanism substitution, not a behaviour change</strong>: a reviewer comparing the two sources
 * side by side will find a {@code SYNCPOINT ROLLBACK} with no line-for-line Java counterpart, and
 * nothing has been lost. <strong>This controller declares no transaction and manages none.</strong></p>
 *
 * <p>Concurrency control is deliberately two-layered, and neither layer substitutes for the other. The
 * JPA {@code @Version} column is the store-level guard and detects <em>that</em> a row changed; the
 * explicit field-by-field snapshot comparison of {@code 9700-CHECK-CHANGE-IN-REC} is the business-level
 * guard and detects <em>which</em> fields changed and in what representation. A concurrent write that
 * set a field back to its original value passes the legacy check and fails a version check, so a version
 * counter alone would not reproduce the source.</p>
 *
 * <h2>7. The view lookup chain</h2>
 *
 * <p>{@code app/cbl/COACTVWC.cbl} walks three datasets in a fixed order, again entirely inside the
 * service: {@code 9000-READ-ACCT} at {@code :L687} drives {@code 9200-GETCARDXREF-BYACCT} at
 * {@code :L723}, then {@code 9300-GETACCTDATA-BYACCT} at {@code :L774}, then
 * {@code 9400-GETCUSTDATA-BYCUST} at {@code :L825}. Each link builds its own diagnostic with
 * {@code STRING}, and the three sentences differ: the cross-reference and account-master links read
 * {@code ' not found in'} at {@code :L750} and {@code :L799} while the customer-master link reads
 * {@code ' not found'} at {@code :L849}. All three are surfaced as ONE indistinguishable {@code 404}, because
 * telling a caller which link found nothing tells it which relations exist and which identifiers are present in
 * them. The distinction is kept in the log entry, which carries the same correlation identifier as the
 * response.</p>
 *
 * <p>That program also declares the paragraph {@code 0000-MAIN-EXIT.} <strong>twice</strong>, at
 * {@code app/cbl/COACTVWC.cbl:L408} and again at {@code :L411}. The duplicate is a legacy artefact
 * retained rather than repaired, in the service where the paragraph map lives; it is named here only so
 * that a reader comparing the paragraph census does not treat it as a transcription error.</p>
 *
 * <h2>8. Statelessness, and what has no counterpart at all</h2>
 *
 * <p>{@code RETURN TRANSID ... COMMAREA} becomes stateless REST plus token claims, so
 * <strong>no server-side session state exists</strong>. Identity is taken only from the authenticated
 * principal - the subject is the upper-cased {@code CDEMO-USER-ID PIC X(08)} of
 * {@code app/cpy/COCOM01Y.cpy:L25} and the role authority is {@code CDEMO-USER-TYPE PIC X(01)} at
 * {@code :L26} with {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :L27} and
 * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :L28}. It is never taken from a request body and
 * never from a session. Neither operation gates on user class: {@code app/cbl/COACTVWC.cbl:L344}
 * asserts {@code SET CDEMO-USRTYP-USER TO TRUE} unconditionally on its exit path, so no role check is
 * invented here and the authorisation rules stay central.</p>
 *
 * <p>These COMMAREA members have no counterpart and appear in no request and no response:
 * {@code CDEMO-FROM-TRANID PIC X(04)} at {@code app/cpy/COCOM01Y.cpy:L21},
 * {@code CDEMO-FROM-PROGRAM PIC X(08)} at {@code :L22}, {@code CDEMO-TO-TRANID PIC X(04)} at
 * {@code :L23}, {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code :L24},
 * {@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code :L29} with its {@code 88 CDEMO-PGM-ENTER VALUE 0} at
 * {@code :L30} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at {@code :L31},
 * {@code CDEMO-LAST-MAP PIC X(7)} at {@code :L43} and {@code CDEMO-LAST-MAPSET PIC X(7)} at
 * {@code :L44} - both seven bytes wide, not eight. Conversely
 * {@code CDEMO-ACCT-ID PIC 9(11)} at {@code :L38}, {@code CDEMO-ACCT-STATUS PIC X(01)} at
 * {@code :L39}, {@code CDEMO-CUST-ID PIC 9(09)} at {@code :L33},
 * {@code CDEMO-CUST-FNAME}, {@code -MNAME} and {@code -LNAME PIC X(25)} at {@code :L34-L36} and
 * {@code CDEMO-CARD-NUM PIC 9(16)} at {@code :L41} are payload members and never token claims: the
 * claim set is exactly the subject, the role, the issuer, the issued-at and the expiry, because a
 * signed token is not an encrypted one.</p>
 *
 * <p>Four members of {@code app/cpy/CVCRD01Y.cpy} - {@code CCARD-LAST-PROG},
 * {@code CCARD-RETURN-TO-PROG}, {@code CCARD-RETURN-FLAG} and {@code CCARD-FUNCTION} - are commented
 * out in the source itself and have no Java counterpart of any kind. {@code DFHAID},
 * {@code DFHBMSCA} and {@code DFHATTR} are supplied by the transaction monitor, are absent from this
 * repository, and are imported nowhere.</p>
 *
 * <h2>9. Field contracts, precision and observability</h2>
 *
 * <p>The two symbolic maps genuinely diverge and are never unified. {@code app/cpy-bms/COACTVW.CPY:60}
 * declares {@code ACCTSIDI PIC 99999999999} - numeric, eleven digits, and the only map in the corpus
 * that does so - whereas {@code app/cpy-bms/COACTUP.CPY:60} declares {@code ACCTSIDI PIC X(11)}. Both
 * declare {@code ACURBALI PIC X(15)}, at {@code COACTVW.CPY:102} and {@code COACTUP.CPY:138}, where
 * {@code app/cpy-bms/COBIL00.CPY:66} uses {@code CURBALI PIC X(14)}; and {@code COACTVW.CPY:54}
 * declares {@code CURTIMEI PIC X(8)} where {@code app/cpy-bms/COSGN00.CPY} uses {@code X(9)}. No field
 * is widened, narrowed, renamed, re-ordered or unified here, and no shared header helper and no unified
 * balance or time abstraction is introduced. {@code com.cardemo.model.dto.AccountDto} declares
 * exactly 37 components, one per input field of the {@code 01 CACTVWAI} group, so the projection width
 * is machine-checkable rather than asserted in prose.</p>
 *
 * <p>Every monetary value crossing this boundary is a fixed-scale decimal, never a binary floating-point
 * type, and equality on one is decided by {@code compareTo} and never by {@code equals}. The account
 * money fields are {@code PIC S9(10)V99}, so {@code NUMERIC(12,2)}, with {@code HALF_EVEN} rounding.
 * Timestamp members are carried as text and never as a temporal type, because {@code TRAN-ORIG-TS} and
 * {@code TRAN-PROC-TS} are {@code PIC X(26)} with three mutually incompatible producers; parsing or
 * reformatting one would destroy that parity and does not happen here. Serialisation policy - no numeric
 * timestamps, fail on unknown properties, no decimal-as-float - is configured centrally under
 * {@code src/main/resources} and is not overridden per controller.</p>
 *
 * <p>This is the richest personally-identifiable surface in the application, so nothing sensitive is
 * logged. No credential, no presented password, no password hash, no bearer token, no signing key, no
 * social security number, no card number, no telephone number, no government-issued identifier, no date
 * of birth and no electronic funds account identifier is written to a log line by this class, and the
 * authenticated subject itself is not logged either. Masking rules for anything that reaches a logger by
 * another route live in {@code logback-spring.xml}. Correlation is supplied by
 * {@code CorrelationIdFilter} through the mapped diagnostic context keys {@code correlationId},
 * {@code traceId} and {@code spanId}; this class adds none, renames none, overwrites none, removes none
 * and clears none, and registers no additional metric.</p>
 *
 * <p>Two artefacts of the frozen corpus are named for completeness and are deliberately given no
 * endpoint: {@code CDV1}, defined at {@code app/csd/CARDDEMO.CSD:L388-L391} as a developer transaction
 * against {@code PROGRAM(COCRDSEC)} at {@code :L390} and declared at {@code :L211}, has no source file
 * anywhere in the repository. Its implementation is {@code Not available}, and inventing one would add
 * an eighteenth operation, so none exists here or anywhere else.</p>
 */
@RestController
@RequestMapping(AccountController.BASE_PATH)
public class AccountController {

    /**
     * The base path both operations are mounted under, and the whole path of the update operation. Fixed
     * by {@code docs/project-guide.md:161} and {@code :424-425}, which record the verified invocation
     * {@code curl -s http://localhost:8080/api/accounts/$ACCT_ID -H "Authorization: Bearer $TOKEN"}, with
     * {@code ACCT_ID} an eleven-digit account identifier from the seeded schema.
     * Both operations require a bearer token; neither is {@code permitAll}.
     */
    static final String BASE_PATH = "/api/accounts";

    /**
     * The view path, relative to {@value #BASE_PATH}. The single template variable is the contents of
     * screen field {@code ACCTSIDI}, declared {@code PIC 99999999999} at
     * {@code app/cpy-bms/COACTVW.CPY:60}.
     */
    static final String ACCOUNT_PATH = "/{accountId}";

    /**
     * The name of the template variable inside {@value #ACCOUNT_PATH}.
     */
    static final String ACCOUNT_ID_VARIABLE = "accountId";

    /**
     * The name of the request parameter that carries the confirmation, and therefore the whole of the
     * function-key vocabulary this operation needs.
     *
     * <p>{@code app/cpy/CSSTRPFY.cpy} is a procedural copybook whose paragraph
     * {@code YYYY-STORE-PFKEY.} at {@code :L17} maps {@code EIBAID} onto the {@code CCARD-AID-*} flag
     * set of {@code app/cpy/CVCRD01Y.cpy}, where {@code 88 CCARD-AID-PFK05 VALUE 'PFK05'} sits at
     * {@code :L12}. The decider gate at {@code app/cbl/COACTUPC.cbl:L2602-L2603} requires
     * <em>both</em> {@code ACUP-CHANGES-OK-NOT-CONFIRMED} and {@code CCARD-AID-PFK05}, so PF5 is the
     * confirm-and-save action and nothing else is. That single decision becomes this single named
     * parameter - an explicit action, not a generic attention-identifier string and not a
     * re-implemented function-key dispatcher.</p>
     */
    static final String CONFIRM_PARAMETER = "confirm";

    /** The only accepted affirmative spelling of {@link #CONFIRM_PARAMETER}. */
    private static final String TRUE_TOKEN = "true";

    /** The only accepted negative spelling of {@link #CONFIRM_PARAMETER}. */
    private static final String FALSE_TOKEN = "false";

    /**
     * The CICS transaction the view operation replaces, defined at {@code app/csd/CARDDEMO.CSD:L317}.
     * Logged rather than surfaced, so that a log line can be tied back to the legacy transaction.
     */
    private static final String VIEW_TRANSACTION_ID = "CAVW";

    /**
     * The CICS transaction the update operation replaces, defined at
     * {@code app/csd/CARDDEMO.CSD:L306}. Matches {@code LIT-THISTRANID} declared at
     * {@code app/cbl/COACTUPC.cbl:L535-L536}.
     */
    private static final String UPDATE_TRANSACTION_ID = "CAUP";

    /**
     * The COBOL program the view operation replaces, named by {@code PROGRAM(COACTVWC)} at
     * {@code app/csd/CARDDEMO.CSD:L318}. Eight characters, which is exactly the width of
     * {@code ABEND-CULPRIT PIC X(8)} declared at {@code app/cpy/CSMSG02Y.cpy:L24-L25}.
     */
    private static final String VIEW_PROGRAM = "COACTVWC";

    /**
     * The COBOL program the update operation replaces, named by {@code PROGRAM(COACTUPC)} at
     * {@code app/csd/CARDDEMO.CSD:L308} and by {@code LIT-THISPGM} at
     * {@code app/cbl/COACTUPC.cbl:L533-L534}. Also eight characters.
     */
    private static final String UPDATE_PROGRAM = "COACTUPC";

    /**
     * The abend code both operations report when an untyped failure escapes a service, rendered as text
     * for {@code ABEND-CODE PIC X(4)} of {@code app/cpy/CSMSG02Y.cpy:L22-L23}. Derived from the single
     * definition on {@code com.cardemo.exception.FatalProcessingException} rather than restated, so the
     * literal 999 of {@code CALL 'CEE3ABD'} appears once in the codebase.
     */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /**
     * The abend reason for the view operation, sized within {@code ABEND-REASON PIC X(50)}.
     */
    private static final String VIEW_ABEND_REASON = "ACCOUNT VIEW FAILED UNEXPECTEDLY";

    /**
     * The abend message for the view operation, sized within {@code ABEND-MSG PIC X(72)}.
     */
    private static final String VIEW_ABEND_MESSAGE = "UNEXPECTED ERROR IN CAVW ACCOUNT VIEW.";

    /**
     * The abend reason for the update operation, sized within {@code ABEND-REASON PIC X(50)}.
     */
    private static final String UPDATE_ABEND_REASON = "ACCOUNT UPDATE FAILED UNEXPECTEDLY";

    /**
     * The abend message for the update operation, sized within {@code ABEND-MSG PIC X(72)}.
     */
    private static final String UPDATE_ABEND_MESSAGE = "UNEXPECTED ERROR IN CAUP ACCOUNT UPDATE.";

    /**
     * The byte-exact prompt an unconfirmed update is answered with.
     *
     * <p>{@code com.cardemo.exception.ConcurrentUpdateException.Outcome#CHANGES_NOT_CONFIRMED} carries
     * an <strong>empty</strong> legacy message, and correctly so: the four other outcomes are condition
     * names on the error field {@code WS-RETURN-MSG PIC X(75)} declared at
     * {@code app/cbl/COACTUPC.cbl:L479}, whereas the not-confirmed state is not an error at all and its
     * text lives in the separate information field {@code WS-INFO-MSG PIC X(40)} declared at
     * {@code :L463}. The literal below is {@code 88 PROMPT-FOR-CONFIRMATION} at {@code :L472-L473},
     * reproduced exactly - <strong>including the absent space after the full stop</strong> - and
     * {@code 3250-SETUP-INFOMSG} at {@code :L2966-L2967} is what selects it whenever the change action
     * is {@code ACUP-CHANGES-OK-NOT-CONFIRMED}.</p>
     */
    private static final String NOT_CONFIRMED_PROMPT = "Changes validated.Press F5 to save";

    /**
     * The customer-lock literal Trap 1 hides behind a reported success, taken from the exception's own
     * outcome rather than restated here so that the byte-exact text of
     * {@code app/cbl/COACTUPC.cbl:L519-L520} has exactly one definition in the codebase.
     */
    private static final String CUSTOMER_LOCK_LITERAL =
            ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER.getLegacyMessage();

    /**
     * Problem title for a field-level rejection.
     */
    private static final String VALIDATION_PROBLEM_TITLE = "Account request rejected";

    /**
     * Problem title for any link of the lookup chain that found nothing.
     */
    private static final String NOT_FOUND_PROBLEM_TITLE = "Account record not found";

    /**
     * The single detail every {@code 404} from this controller carries.
     *
     * <p>Fixed on purpose. {@code app/cbl/COACTVWC.cbl} says it three ways depending on which link of the
     * chain found nothing, and {@code app/cbl/COACTUPC.cbl:L513-L514} adds a fourth; relaying whichever one
     * was thrown would tell a caller which relation is missing the row, and the identifier that was looked up
     * is the caller's own input echoed as confirmation that it does or does not exist. The distinction is kept
     * where it belongs: the projected screen field on the normal path still carries the source's own wording,
     * and the log records which link failed.
     */
    private static final String NOT_FOUND_PROBLEM_DETAIL =
            "The requested account record could not be found";

    /**
     * Problem title shared by all five concurrency outcomes, which the {@code outcome} property and the
     * status code then distinguish.
     */
    private static final String CONCURRENCY_PROBLEM_TITLE = "Account update not applied";

    /**
     * Fallback detail for a concurrency failure that names no outcome. The four-argument constructors of
     * {@code com.cardemo.exception.ConcurrentUpdateException} leave the outcome null, so this path is
     * reachable and is answered explicitly rather than with a null detail.
     */
    private static final String CONCURRENCY_PROBLEM_DETAIL =
            "The account update was not applied. Re-read the account and resubmit with a fresh snapshot.";

    /**
     * Problem title for a referential-constraint refusal.
     */
    private static final String INTEGRITY_PROBLEM_TITLE = "Account write refused by a constraint";

    /**
     * Fixed detail for a referential-constraint refusal. Names no value, because the payload of this
     * operation carries personally-identifiable data.
     */
    private static final String INTEGRITY_PROBLEM_DETAIL =
            "The write violates a referential constraint of the account schema and was not applied.";

    /**
     * Problem title for {@code FILE STATUS '35'}, a dataset that is not open.
     */
    private static final String UNAVAILABLE_PROBLEM_TITLE = "Account data store unavailable";

    /**
     * Fixed detail for {@code FILE STATUS '35'}.
     */
    private static final String UNAVAILABLE_PROBLEM_DETAIL =
            "The account data store is not available. Retry once the store and its migrations are ready.";

    /**
     * Problem title for the {@code FILE STATUS '9x'} family.
     */
    private static final String IO_PROBLEM_TITLE = "Account data store input-output failure";

    /**
     * Fixed detail for the {@code FILE STATUS '9x'} family.
     */
    private static final String IO_PROBLEM_DETAIL =
            "The account data store reported an input-output failure. The request was not completed.";

    /**
     * Problem title for an abend and for any other typed failure.
     */
    private static final String FAILURE_PROBLEM_TITLE = "Account operation failed";

    /**
     * Fixed detail for an abend and for any other typed failure. Deliberately reveals nothing: the cause
     * stays attached to the exception and reaches the log, and diagnosis proceeds from the request's
     * correlation identifier. This mirrors {@code server.error.include-message: never} and
     * {@code include-stacktrace: never} in {@code src/main/resources/application.yml}.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "The account operation could not be completed. Diagnose from the correlation identifier.";

    /**
     * Problem-detail property naming the concurrency outcome, so that the five are never confused.
     */
    private static final String OUTCOME_PROPERTY = "outcome";

    /**
     * Problem-detail property carrying the {@code ACUP-CHANGE-ACTION PIC X(1)} marker of
     * {@code app/cbl/COACTUPC.cbl:L654-L668} that the failed turn would have left on the screen.
     */
    private static final String CHANGE_ACTION_PROPERTY = "changeAction";

    /**
     * Problem-detail property naming the rejected field.
     */
    private static final String FIELD_PROPERTY = "field";

    /**
     * Problem-detail property distinguishing a blank field from an invalid one. The distinction is
     * behaviour, not decoration: {@code app/cbl/COACTUPC.cbl:L505-L506} says
     * {@code Credit Limit must be supplied} for blank and {@code :L507-L508} says
     * {@code Credit Limit is not valid} for invalid, so the two states are never collapsed.
     */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /**
     * The problem-detail property carrying the stable, machine-readable code for the failure class.
     * <p>
     * Every error body this controller returns carries exactly one of the {@code ERROR_CODE_*} constants
     * below. A client branches on that code, never on the wording of {@code detail} and never on a property
     * naming an internal resource: the code is the supported contract, so the internal detail that used to
     * travel beside it could be withdrawn without breaking any caller.
     */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /**
     * The problem-detail property carrying the correlation identifier of the failing request.
     * <p>
     * This is the hinge of the {@code CWE-209} fix. The relation, constraint, logical file, operation and
     * file-status values that used to be returned to the client are now written only to the log, and this
     * identifier is what lets a caller reporting a failure be joined to those log records: it is the same
     * value {@code CorrelationIdFilter} placed in the diagnostic context and echoed on the
     * {@code X-Correlation-Id} response header, so support can retrieve the internal detail while an
     * attacker holding the response body cannot.
     */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /**
     * The value substituted when no correlation identifier is in the diagnostic context.
     * <p>
     * {@code CorrelationIdFilter} runs at {@code HIGHEST_PRECEDENCE} and every request that reaches a
     * handler here has passed through it, so this is unreachable in the server. It exists because a
     * standalone unit test may invoke a handler directly, and because a null property would serialise as a
     * {@code null} member and make the body's shape depend on how it was produced.
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /**
     * Problem-detail property carrying the resource-specific public code.
     * <p>
     * <b>Two code properties travel on an error body from this controller, and they are not duplicates.</b>
     * {@value #ERROR_CODE_PROPERTY} is the envelope code every handler in every controller sets, drawn from
     * the {@code ERROR_CODE_*} constants below, and it classifies the failure coarsely enough to be uniform
     * across the API. This one is the account resource's own refinement, drawn from {@link PublicErrorCode},
     * and it is set only on the two paths that have a resource-specific meaning worth branching on: a link of
     * the lookup chain found nothing, and a referential constraint refused a write. A caller that wants one
     * rule for every endpoint reads the envelope code; a caller written against this resource reads this one.
     * <p>
     * Both replace the {@code recordType}, {@code recordKey}, {@code constraintName} and {@code relation}
     * properties this class used to publish. Each of those described the server's internals to the caller: two
     * named a relation of the schema, one named a database constraint, and one echoed the key that was not
     * found. A caller that must branch on the outcome needs a value it can switch on and that the server
     * promises not to change - which a constraint name is emphatically not - and the operator's need for the
     * specific relation, constraint and key is met by the log, where the correlation identifier ties the entry
     * to this exact response. The fixed detail those relays were replaced by is
     * {@value #NOT_FOUND_PROBLEM_DETAIL}: {@code AccountViewService} maps an empty repository result to the
     * file status {@code '23'} and hands it to {@code FileStatusMapper}, which composes a message naming the
     * operation, the alternate-index path and the legacy status, and relaying that returned all three to any
     * caller who asked for an account that does not exist - on the primary {@code GET} path of this
     * controller.
     */
    private static final String PUBLIC_CODE_PROPERTY = "code";

    /**
     * Stable error code meaning that the request was refused by a field-level validation rule.
     */
    private static final String ERROR_CODE_VALIDATION = "CARDDEMO-VALIDATION-REJECTED";

    /**
     * Stable error code meaning that a record the operation needed does not exist.
     */
    private static final String ERROR_CODE_NOT_FOUND = "CARDDEMO-RECORD-NOT-FOUND";

    /**
     * Stable error code meaning that the update was not applied because the stored state moved or was not confirmed.
     */
    private static final String ERROR_CODE_UPDATE_CONFLICT = "CARDDEMO-UPDATE-CONFLICT";

    /**
     * Stable error code meaning that a referential constraint refused the write.
     */
    private static final String ERROR_CODE_CONSTRAINT = "CARDDEMO-CONSTRAINT-REFUSED";

    /**
     * Stable error code meaning that a required data store or queue could not be reached; the request is retryable.
     */
    private static final String ERROR_CODE_UNAVAILABLE = "CARDDEMO-RESOURCE-UNAVAILABLE";

    /**
     * Stable error code meaning that the store behind this service reported an input-output failure.
     */
    private static final String ERROR_CODE_IO_FAILURE = "CARDDEMO-IO-FAILURE";

    /**
     * Stable error code meaning that processing terminated abnormally.
     */
    private static final String ERROR_CODE_ABEND = "CARDDEMO-PROCESSING-ABEND";

    /**
     * Stable error code meaning that an unexpected typed failure occurred.
     */
    private static final String ERROR_CODE_INTERNAL = "CARDDEMO-INTERNAL-FAILURE";

    /**
     * Diagnostic log destination. Static and final: a logger is neither mutable state nor per-request
     * state, and holding it once avoids a lookup on every request.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);

    /**
     * The account-view service, replacing {@code app/cbl/COACTVWC.cbl} over mapset {@code COACTVW}.
     * Final and never reassigned.
     */
    private final AccountViewService accountViewService;

    /**
     * The account-update service, replacing {@code app/cbl/COACTUPC.cbl} over mapset {@code COACTUP}.
     * Final and never reassigned.
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Creates the controller over the two account services.
     *
     * <p>Constructor injection is the only injection form used: there is no field injection, no setter
     * injection and no {@code @Autowired}, so both collaborators are non-null and final for the lifetime
     * of the bean. The class therefore holds <strong>no static mutable field and no per-request
     * state</strong>, which is what lets the contract suite drive both operations in any order against a
     * shared application context. Both arguments are validated rather than trusted, because a null
     * collaborator would otherwise surface as a failure on the first request instead of at context
     * refresh.</p>
     *
     * @param accountViewService the account-view service replacing {@code app/cbl/COACTVWC.cbl}; must
     * not be null.
     * @param accountUpdateService the account-update service replacing {@code app/cbl/COACTUPC.cbl};
     * must not be null.
     * @throws IllegalArgumentException if either service is null, which is a bean-wiring defect rather
     * than a request-time condition
     */
    public AccountController(final AccountViewService accountViewService,
            final AccountUpdateService accountUpdateService) {

        if (accountViewService == null) {
            throw new IllegalArgumentException(
                    "accountViewService must not be null; it is the replacement for app/cbl/COACTVWC.cbl");
        }
        if (accountUpdateService == null) {
            throw new IllegalArgumentException(
                    "accountUpdateService must not be null; it is the replacement for app/cbl/COACTUPC.cbl");
        }

        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    /**
     * Account view - operation 4 of the target 17, replacing CICS transaction {@code CAVW}. Both of this
     * controller's operations are authored; five of the target 17 are not.
     *
     * <p><strong>Purpose.</strong> Returns the account projection that {@code app/cbl/COACTVWC.cbl}
     * painted onto mapset {@code COACTVW}. Transaction {@code CAVW} is defined at
     * {@code app/csd/CARDDEMO.CSD:L317} against {@code PROGRAM(COACTVWC)} at {@code :L318}. The whole of
     * the behaviour lives in {@code com.cardemo.service.account.AccountViewService}, which holds the
     * paragraph-level correspondence to the program's 38 labels across its 941 lines; this method makes
     * exactly one call into it and translates the outcome.</p>
     *
     * <p><strong>Inputs.</strong> One path variable, {@code accountId}, which is the contents of screen
     * field {@code ACCTSIDI} - declared {@code PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60},
     * numeric and eleven digits wide, and the only map in the corpus that declares that field numerically
     * rather than as {@code PIC X(11)}. The value is handed to the service <strong>exactly as
     * received</strong>: it is not trimmed, stripped, padded, re-cased or re-formatted here. That is
     * deliberate and is the service's documented contract - {@code "1"} is invalid input under
     * {@code 1210-EDIT-ACCOUNT}, not a shorthand for account one, and normalising it at the boundary
     * would silently accept input the legacy screen rejected. Absent, blank and low-values remain three
     * distinct states all the way through, per the {@code OK}/{@code NOT-OK}/{@code BLANK} model that
     * {@code app/cpy/CSSETATY.cpy} establishes. Identity arrives only as the authenticated principal.</p>
     *
     * <p><strong>Outputs.</strong> {@code 200 OK} carrying {@code com.cardemo.model.dto.AccountDto}, the
     * thirty-seven-component projection of mapset {@code COACTVW}, with every monetary member rendered
     * on the legacy display mask of {@code ACURBALI PIC X(15)}. Money is decimal throughout, never a
     * binary floating-point type. On failure, a problem detail as tabulated in section 4 of the class
     * documentation; on an unusable identity, {@code 401} with an empty body.</p>
     *
     * <p><strong>Side effects.</strong> None that a client can observe beyond the read itself. The
     * service opens a read-only transaction and walks three datasets in a fixed order -
     * {@code 9000-READ-ACCT} at {@code app/cbl/COACTVWC.cbl:L687} driving
     * {@code 9200-GETCARDXREF-BYACCT} at {@code :L723}, then {@code 9300-GETACCTDATA-BYACCT} at
     * {@code :L774}, then {@code 9400-GETCUSTDATA-BYCUST} at {@code :L825}. Nothing is written, no queue
     * message is published and no object is stored. This method itself declares no transaction, holds no
     * state between requests and mutates no field.</p>
     *
     * <p><strong>Configuration and defaults.</strong> This method reads no property. The authorisation
     * rule for the path is declared centrally in {@code SecurityConfig}, as is
     * {@code SessionCreationPolicy.STATELESS}; no session is created or touched here and no method-level
     * security annotation is declared. No role check is applied, because
     * {@code app/cbl/COACTVWC.cbl:L344} sets {@code CDEMO-USRTYP-USER} unconditionally on its exit path -
     * the program gates on no user class, so neither does this operation.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} for a rejected account filter,
     * with the legacy literal relayed byte for byte and {@code failureKind} separating blank from
     * invalid. {@code 404} for any of the three links of the chain, with one fixed body for all three: the
     * source said it in three different sentences - the cross-reference and account-master diagnostics use
     * {@code ' not found in'} at {@code :L750} and {@code :L799} while the customer-master diagnostic uses
     * {@code ' not found'} at {@code :L849} - and which one fired is recorded in the {@code WARN} log, not in
     * the response. {@code 503} when the store
     * is closed, {@code 502} for the {@code FILE STATUS '9x'} family with the four-character expanded
     * status attached, and {@code 500} for an abend carrying code {@code 999} and return code
     * {@code 12}. Every one of them is logged against the request's correlation identifier, which is how
     * a diagnosis starts, since the response bodies deliberately reveal nothing.</p>
     *
     * <p><strong>What the response carries, and what it withholds.</strong> The body is
     * {@code AccountViewResponse}, not the thirty-seven-component projection. Nine of those components are
     * withheld - the social security number, the date of birth, the three customer names, both telephone
     * numbers, the government-issued identifier and the electronic funds account identifier - because none
     * of them is needed to display or to update an account and each is a protected value. The list is
     * published as {@code AccountViewResponse.WITHHELD_COMPONENTS} so the contract is machine-checkable
     * rather than merely documented.</p>
     *
     * <p><strong>The response is also the precondition for the update.</strong> It carries a sealed
     * as-displayed snapshot, published both as a body member and as the {@code ETag} header, and the
     * matching {@code PUT} requires that value in {@code If-Match}. This operation is the only way a
     * client obtains a snapshot, and that is deliberate: one the client composed for itself would not be a
     * precondition at all - the comparison at
     * {@code app/cbl/COACTUPC.cbl:L4109-L4193} would be answerable to the caller rather than to the record.
     * The snapshot is produced by the <em>update</em> service, because {@code ACUP-OLD-DETAILS} belongs to
     * {@code COACTUPC}, and it is sealed with authenticated encryption bound to this account and to a
     * lifetime, so all twenty-nine of its values take part in the comparison while none of them is
     * disclosed.</p>
     *
     * @param accountId the contents of screen field {@code ACCTSIDI}, passed to the service verbatim;
     * may be blank, which the service rejects rather than this method.
     * @param authentication the principal Spring Security resolved, which may be null when the request
     * bypassed the filter chain.
     * @return {@code 200} with the minimized account response and the sealed snapshot, the latter also
     * published as the {@code ETag}, or {@code 401} with an empty body when the request carries no usable
     * identity
     */
    @GetMapping(ACCOUNT_PATH)
    public ResponseEntity<AccountViewResponse> viewAccount(
            @PathVariable(ACCOUNT_ID_VARIABLE) final String accountId,
            final Authentication authentication) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation."
                    + " Only sign-on CC00 is unauthenticated.", VIEW_TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final AccountDto account = this.retrieveAccount(accountId);

        // The precondition the matching update requires. It is produced by the UPDATE service, because
        // ACUP-OLD-DETAILS belongs to app/cbl/COACTUPC.cbl and because the group carries values - the date
        // of birth, the social security number, the government-issued identifier, both telephone numbers
        // and the electronic funds account identifier - that no response may contain. What comes back is one
        // opaque string, and it is the only route by which a client can obtain a valid snapshot at all.
        final String snapshotToken = this.acquireUpdateSnapshot(accountId);
        final AccountViewResponse response = AccountViewResponse.of(account, snapshotToken);

        LOG.debug("Served transaction {} program {} from mapset COACTVW with a sealed snapshot",
                VIEW_TRANSACTION_ID, VIEW_PROGRAM);
        // Also published as an entity tag, so a client may use the standard conditional-request idiom
        // rather than reading the token out of the body.
        return ResponseEntity.ok().eTag(quotedETag(snapshotToken)).body(response);
    }

    /**
     * Account update - operation 5 of the target 17, replacing CICS transaction {@code CAUP}.
     *
     * <p><strong>Purpose.</strong> Applies an edited account together with its customer record, exactly
     * as {@code app/cbl/COACTUPC.cbl} did over mapset {@code COACTUP}. Transaction {@code CAUP} is
     * defined at {@code app/csd/CARDDEMO.CSD:L306} against {@code PROGRAM(COACTUPC)} at {@code :L308}.
     * At 4,236 lines and 88 paragraph labels that program is the largest in the corpus, and all of it
     * lives in {@code com.cardemo.service.account.AccountUpdateService}; this method makes exactly one
     * call into it. The seven-step write sequence, the change-detection comparison, the case regime and
     * the transaction boundary are described in sections 5 and 6 of the class documentation and are
     * <strong>not</strong> restated as logic here.</p>
     *
     * <p><strong>Inputs.</strong> A JSON body binding to
     * {@code com.cardemo.model.dto.AccountUpdateRequest}: the fifty-four input fields of
     * {@code app/cpy-bms/COACTUP.CPY} plus the {@code newDetails} group that mirrors
     * {@code ACUP-NEW-DETAILS} at {@code app/cbl/COACTUPC.cbl:L757}. The other group,
     * {@code ACUP-OLD-DETAILS} at {@code :L669}, <strong>arrives in {@code If-Match}</strong> as the sealed
     * value the preceding read returned, and a body that carries an {@code oldDetails} group is
     * <em>refused</em> rather than ignored - a precondition a caller composes is not a precondition, and the
     * group carries six protected customer values that no client should hold or replay.</p>
     *
     * <p><strong>Everything that does travel, travels verbatim.</strong> Nothing
     * in this method trims, strips, pads, folds case on, re-formats, re-orders or date-parses either
     * group, or any member of either group: Trap 3 in the class documentation proves why, and the
     * decisive evidence is the date-of-birth comparison at {@code :L4174-L4179}, where the live record is
     * read at offsets {@code (1:4)}, {@code (6:2)}, {@code (9:2)} and the snapshot - declared
     * {@code PIC X(08)} compact at {@code :L746} with its {@code REDEFINES} at {@code :L747-L751} - is
     * read at {@code (1:4)}, {@code (5:2)}, {@code (7:2)}. Normalising either side would report a change
     * on every single request and make the operation permanently unusable. The bean-validation
     * constraints are the DTO's own: the FICO band of {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH
     * 850} at {@code :L848-L849} binds {@code newDetails} alone, and {@code oldDetails} carries no
     * constraint at all because a snapshot records what was displayed rather than what is acceptable. No
     * validation is added here that would contradict that.</p>
     *
     * <p>One request parameter, {@value #CONFIRM_PARAMETER}, carries the confirmation. The gate at
     * {@code :L2602-L2603} fires only when the change action is
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED} <em>and</em> the attention identifier is
     * {@code CCARD-AID-PFK05} - {@code 88 CCARD-AID-PFK05 VALUE 'PFK05'} at
     * {@code app/cpy/CVCRD01Y.cpy:L12}, stored by the procedural paragraph
     * {@code YYYY-STORE-PFKEY.} at {@code app/cpy/CSSTRPFY.cpy:L17}. That is modelled as this one named
     * action rather than as a generic attention-identifier string and rather than as a second endpoint,
     * which would create an eighteenth operation. Absent and blank both mean not confirmed, reproducing
     * the {@code CONTINUE} arm at {@code :L2620-L2621}, while an unrecognised value is rejected rather
     * than charitably read as a confirmation. The consequence of collapsing the legacy pseudo-conversation
     * into one operation is that the unconfirmed validation repaint is not a separate turn: field-level
     * rejections surface on the confirmed request. Identity arrives only as the authenticated principal,
     * never from the body.</p>
     *
     * <p><strong>Outputs.</strong> {@code 200 OK} carrying {@code AccountUpdateResponse}, an API-native
     * body of four members: the account identifier echoed back, {@code changeAction} - the
     * {@code ACUP-CHANGE-ACTION PIC X(1)} outcome of {@code :L654-L668} reported by name so the source's
     * outcome vocabulary survives over HTTP - a boolean {@code applied}, and the two message fields
     * {@code WS-INFO-MSG PIC X(40)} at {@code :L463} and {@code WS-RETURN-MSG PIC X(75)} at {@code :L479}
     * relayed byte for byte as the program left them.</p>
     *
     * <p><strong>Three members of the service result are deliberately withheld</strong>, and each for its
     * own reason. {@code screen} is the submitted map plus the snapshot group - fifty-four fields including
     * the social security number, the date of birth, the government-issued identifier, both telephone
     * numbers and the electronic funds account identifier - so returning it would publish protected values
     * on every successful write. {@code navigation} carries {@code CDEMO-TO-PROGRAM} and its five siblings,
     * which describe a CICS screen flow that does not exist in a URL-routed target. {@code fieldAttributes}
     * carries {@code DFHBMPRF} attribute bytes, colour bytes, the {@code '*'} marker and a cursor position -
     * 3270 presentation instructions with no meaning to an HTTP client. The service record itself is
     * unchanged and remains the in-process contract; what changed is that it is no longer a response
     * body.</p>
     *
     * <p><strong>No refreshed snapshot is returned.</strong> A client that wishes to edit again reads the
     * account again, which is one request and is what the source required too - the screen was repainted
     * before the next turn. Returning one here would additionally mean issuing a precondition for a state
     * this response cannot vouch for, since the write has already completed.</p>
     *
     * <p><strong>Side effects.</strong> Two rows are rewritten, in the account relation and the customer
     * relation, inside one unit of work owned by the service's
     * {@code @Transactional(rollbackFor = Exception.class)}. <strong>This method declares no transaction
     * and manages none</strong>, which is exactly what reproduces the source's asymmetric rollback: the
     * account-rewrite failure at {@code :L4076-L4081} issues no backout because nothing has been written
     * yet, and the customer-rewrite failure at {@code :L4095-L4103} issues one at {@code :L4099-L4101}
     * because the account rewrite has already happened. Scoping both writes into one transaction gives
     * both branches for free - a mechanism substitution, not a behaviour change. When the confirmation
     * is absent no service call is made at all and nothing is written.</p>
     *
     * <p><strong>Configuration and defaults.</strong> This method reads no property. The authorisation
     * rule and the stateless session policy are declared centrally in {@code SecurityConfig}. The
     * per-field detail of a bean-validation rejection is suppressed by
     * {@code server.error.include-binding-errors: never}; that is a secret-hygiene decision, because a
     * binding message can echo a submitted value and this payload carries a social security number, so
     * no handler is declared here that would reinstate it. Serialisation policy is central and is not
     * overridden.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 428} when the confirmation was not
     * asserted, carrying {@value #NOT_CONFIRMED_PROMPT}, and equally when {@code If-Match} was absent so
     * that the guard had nothing to compare against; nothing was written in either case. {@code 423} for
     * {@code COULD_NOT_LOCK_ACCOUNT}, nothing written, safe to retry. {@code 412} for
     * {@code DATA_CHANGED_BEFORE_UPDATE}, which covers both a record that changed under the caller and an
     * {@code If-Match} value that did not verify - a token this server did not seal, one sealed for another
     * account, or one whose lifetime has passed, all answered with one message because distinguishing them
     * would let a caller probe the sealing key. Re-read, resubmit with the fresh {@code ETag}, re-apply the
     * edits, and do not retry blindly, because the snapshot is the guarantee. {@code 409} for a referential
     * refusal or for an outcome that names itself {@code COULD_NOT_LOCK_CUSTOMER} explicitly.
     * {@code 500} for {@code LOCKED_BUT_UPDATE_FAILED} and for an abend. {@code 400} for a field
     * rejection with the legacy literal relayed byte for byte. And - the trap that matters most - a
     * {@code 200} does <strong>not</strong> by itself prove both records were written: per Trap 1 of the
     * class documentation, a
     * customer lock failure is reported as success, so a client that needs certainty must read
     * {@code errorMessage} on the body, and this method emits a {@code WARN} line naming the unreported lock
     * whenever that happens.</p>
     *
     * @param request the symbolic map of {@code app/cpy-bms/COACTUP.CPY} carrying the {@code newDetails}
     * edits; validated against the DTO's own width and range contracts and then passed to the service
     * unaltered. It must <em>not</em> carry an {@code oldDetails} group.
     * @param confirmToken the explicit replacement for attention identifier {@code CCARD-AID-PFK05},
     * matched exactly against {@code true} and {@code false}; null means absent and therefore not
     * confirmed, and no alias is accepted.
     * @param ifMatch the sealed as-displayed snapshot the preceding read returned, quoted as an entity tag
     * or bare; null when the header was absent, which the service reports as unconfirmed.
     * @param authentication the principal Spring Security resolved, which may be null when the request
     * bypassed the filter chain.
     * @return {@code 200} with the API-native update response, or {@code 401} with an empty body when the
     * request carries no usable identity
     * @throws ConcurrentUpdateException with outcome {@code CHANGES_NOT_CONFIRMED} when the confirmation
     * was not asserted or no snapshot was presented, which this class maps to {@code 428} without
     * attempting a write, and with {@code DATA_CHANGED_BEFORE_UPDATE} mapped to {@code 412} when a
     * presented snapshot does not verify
     * @throws ValidationException when the body carries an {@code oldDetails} group, or when the
     * confirmation parameter is present and is neither exact token
     */
    @PutMapping
    public ResponseEntity<AccountUpdateResponse> updateAccount(
            @Valid @RequestBody final AccountUpdateRequest request,
            @RequestParam(name = CONFIRM_PARAMETER, required = false) final String confirmToken,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) final String ifMatch,
            final Authentication authentication) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation."
                    + " Only sign-on CC00 is unauthenticated.", UPDATE_TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        rejectBodyCarriedSnapshot(request);

        if (!requireConfirmToken(confirmToken)) {
            LOG.debug("Refused transaction {} with 428: request parameter {} was not asserted, so the"
                    + " app/cbl/COACTUPC.cbl:L2602-L2603 gate did not fire and no write was attempted",
                    UPDATE_TRANSACTION_ID, CONFIRM_PARAMETER);
            throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED, NOT_CONFIRMED_PROMPT);
        }

        final AccountUpdateResult result = this.applyAccountUpdate(request, unquotedETag(ifMatch));

        warnOnUnreportedCustomerLock(result);

        LOG.debug("Served transaction {} program {} with change action {} and response kind {}",
                UPDATE_TRANSACTION_ID, UPDATE_PROGRAM, result.changeAction(), result.responseKind());
        return ResponseEntity.ok(projectUpdateResponse(request, result));
    }

    /**
     * Delegates to the account-view service exactly once and returns its projection.
     *
     * <p>The filter is handed over untouched, for the reason given on
     * {@link #viewAccount}. The two catch clauses are asymmetric on purpose: a
     * typed {@code com.cardemo.exception.CardDemoException} already carries the file status, the field or
     * the record key the boundary needs, so re-wrapping it would bury information that the problem-detail
     * properties are built from - it is therefore rethrown unchanged. Anything else is a genuine
     * surprise, and it becomes an abend carrying the {@code app/cpy/CSMSG02Y.cpy} payload with
     * {@code app/cbl/COACTVWC.cbl}'s program name as the culprit, <strong>preserving the original as the
     * cause</strong> so that no throwable is swallowed and no stack is lost.</p>
     *
     * @param accountFilter the contents of screen field {@code ACCTSIDI}, passed on verbatim.
     * @return the thirty-seven-component account projection, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure
     */
    private AccountDto retrieveAccount(final String accountFilter) {

        try {
            return this.accountViewService.viewAccount(accountFilter);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, VIEW_PROGRAM, VIEW_ABEND_REASON,
                    VIEW_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Delegates to the account-update service exactly once and returns its result.
     *
     * <p>The request instance is passed by reference and is neither copied nor rewritten, which is what
     * keeps {@code oldDetails} and {@code newDetails} byte-identical to what the caller submitted. The
     * catch clauses behave exactly as described on {@link #retrieveAccount}, with
     * {@code app/cbl/COACTUPC.cbl}'s program name as the abend culprit.</p>
     *
     * @param request the symbolic map carrying both detail groups, passed on unaltered.
     * @param snapshotToken the sealed as-displayed snapshot the caller returned, passed on unaltered so the
     *     service can verify it against the token it minted; never rewritten here
     * @return the update result, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure
     */
    private AccountUpdateResult applyAccountUpdate(final AccountUpdateRequest request,
                                                   final String snapshotToken) {

        try {
            return this.accountUpdateService.updateAccount(request, snapshotToken);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, UPDATE_PROGRAM, UPDATE_ABEND_REASON,
                    UPDATE_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Obtains the sealed {@code ACUP-OLD-DETAILS} snapshot for one account.
     *
     * <p>Delegates once to the update service, which owns the group. The catch clauses behave exactly as on
     * {@link #retrieveAccount}: a typed failure is rethrown so the declared status mapping applies, and
     * anything else becomes an abend with its cause preserved.</p>
     *
     * <p>The failure is <em>not</em> suppressed. Returning a null token on a failed acquisition would answer
     * {@code 200} with a response a client cannot update from, which is the very gap this method closes;
     * the two services read the same three-dataset chain, so a filter this one refuses is a filter the view
     * would have refused as well.</p>
     *
     * @param accountFilter the contents of screen field {@code ACCTSIDI}, passed on verbatim.
     * @return the sealed snapshot, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure
     */
    private String acquireUpdateSnapshot(final String accountFilter) {

        try {
            return this.accountUpdateService.issueUpdateSnapshot(accountFilter);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, UPDATE_PROGRAM, UPDATE_ABEND_REASON,
                    UPDATE_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Projects the service result onto the API-native update response.
     *
     * <p>Four members reach a client and three do not. {@code changeAction}, the two message fields and the
     * account identifier are reported, because they are the source's own outcome vocabulary and its own
     * screen text. {@code screen} is withheld because it is the submitted map plus the snapshot group -
     * fifty-four fields including the social security number, the date of birth, the government-issued
     * identifier, both telephone numbers and the electronic funds account identifier. {@code navigation} is
     * withheld because routing is URL based in the target, so {@code CDEMO-TO-PROGRAM} and its siblings
     * describe a screen flow that does not exist here. {@code fieldAttributes} is withheld because a
     * {@code DFHBMPRF} attribute byte and a cursor position are 3270 presentation instructions with no
     * meaning to an HTTP client.</p>
     *
     * <p>{@code applied} is true for exactly one change action, {@code CHANGES_OKAYED_AND_DONE}. It is not
     * derived from the absence of an error message, because of the legacy trap that {@code :L2606-L2615}
     * never tests the customer-lock condition - which is why {@link #warnOnUnreportedCustomerLock} logs it
     * and why the error message is reported alongside.</p>
     *
     * @param request the submitted map, read only for the account identifier it echoes.
     * @param result the service outcome; never null here.
     * @return the response body, never null
     */
    private static AccountUpdateResponse projectUpdateResponse(final AccountUpdateRequest request,
                                                               final AccountUpdateResult result) {
        return new AccountUpdateResponse(
                request.getAccountId(),
                result.changeAction().name(),
                result.changeAction()
                        == AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE,
                result.informationMessage(),
                result.errorMessage());
    }

    /**
     * Refuses a request body that carries an as-displayed snapshot.
     *
     * <p>The group remains on the request type because it is the transcription of {@code ACUP-OLD-DETAILS}
     * at {@code app/cbl/COACTUPC.cbl:L669} and because it is the shape the sealed token carries internally.
     * What it is not is a wire input: the authentic snapshot arrives in {@code If-Match}, sealed, and the
     * service reads it from there and from nowhere else.</p>
     *
     * <p>A body that carries one is refused rather than ignored. Ignoring it would leave a caller believing
     * it controlled the write precondition when it did not, and that failure is silent and appears only
     * under concurrency. Refusing states the contract at the one moment the caller can act on it - and the
     * group is twenty-nine values of which six are protected, so accepting it would also invite a client to
     * hold and replay them.</p>
     *
     * @param request the bound request body; never null once the framework has bound one.
     * @throws ValidationException with failure kind {@code INVALID} when {@code oldDetails} is present
     */
    private static void rejectBodyCarriedSnapshot(final AccountUpdateRequest request) {

        if (request != null && request.getOldDetails() != null) {
            throw ValidationException.invalidField("oldDetails",
                    "oldDetails must not be sent: the as-displayed snapshot is server-issued and travels"
                            + " in the If-Match header, because a caller-supplied precondition is not a"
                            + " precondition and because the group carries protected customer values");
        }
    }

    /**
     * Resolves the confirmation parameter, accepting only {@code true} and {@code false}.
     *
     * <p>The framework's default {@code Boolean} binding additionally accepts {@code on}, {@code off},
     * {@code yes}, {@code no}, {@code 1} and {@code 0}. This parameter is the PF5 gate of
     * {@code app/cbl/COACTUPC.cbl:L2602-L2603} - the single decision that separates validating a payload
     * from writing two datasets - so admitting six aliases for two values would admit five spellings of an
     * instruction the operation never declared, on the one parameter where that matters most.</p>
     *
     * @param token the raw parameter value, or null when the parameter was absent, which means "not
     * confirmed" and is answered with {@code 428} exactly as before.
     * @return whether the confirmation was asserted
     * @throws ValidationException with failure kind {@code BLANK} when the parameter was present and empty,
     * and {@code INVALID} when it is neither exact token
     */
    private static boolean requireConfirmToken(final String token) {

        if (token == null) {
            return false;
        }
        if (token.isEmpty()) {
            throw ValidationException.missingField(CONFIRM_PARAMETER,
                    "confirm must be supplied as " + TRUE_TOKEN + " or " + FALSE_TOKEN
                            + " when the parameter is present at all");
        }
        if (TRUE_TOKEN.equals(token)) {
            return true;
        }
        if (FALSE_TOKEN.equals(token)) {
            return false;
        }
        throw ValidationException.invalidField(CONFIRM_PARAMETER,
                "confirm accepts exactly " + TRUE_TOKEN + " and " + FALSE_TOKEN
                        + "; no alias and no other spelling is accepted");
    }

    /**
     * Wraps a sealed token in the double quotes an entity tag requires.
     *
     * @param token the sealed token, which is base64url and therefore contains no character needing escape.
     * @return the quoted entity-tag value, or null when {@code token} is null
     */
    private static String quotedETag(final String token) {
        return token == null ? null : "\"" + token + "\"";
    }

    /**
     * Strips the entity-tag quoting from an {@code If-Match} value, so a client may return either the header
     * value verbatim or the bare token. A weak-validator prefix is stripped for the same reason.
     *
     * @param headerValue the raw header value, or null when the header was absent.
     * @return the bare token, or null when the header was absent
     */
    private static String unquotedETag(final String headerValue) {

        if (headerValue == null) {
            return null;
        }
        String value = headerValue.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Records the unreported customer lock whenever the update reports success while the lock failed.
     *
     * <p>This is the observability half of Trap 1, and the only half there is: the status stays
     * {@code 200}, because {@code app/cbl/COACTUPC.cbl:L2606-L2615} never tests
     * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} and therefore falls through {@code WHEN OTHER} at
     * {@code :L2613-L2614} to {@code SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE}. Converting it to a
     * {@code 409} or a {@code 500} would be repairing the source, which parity forbids; leaving it
     * completely invisible would be worse. The compromise the specification requires is met exactly: the
     * reported outcome is success, and the internal outcome stays distinguishable both on the returned
     * result, whose {@code errorMessage} still carries the literal, and in a structured log line that
     * names the defect.</p>
     *
     * <p>The comparison is {@code contains} rather than equality because {@code WS-RETURN-MSG} is
     * {@code PIC X(75)} at {@code :L479} and is space-padded to that width, so the literal is a substring
     * of the field rather than the whole of it. The literal itself is read from the exception's outcome
     * enum, so it has one definition in the codebase and cannot drift.</p>
     *
     * <p>Nothing identifying is logged: no account identifier, no customer identifier, no social security
     * number and no authenticated subject. The correlation identifier that
     * {@code CorrelationIdFilter} already placed in the mapped diagnostic context is what ties this line
     * to the request.</p>
     *
     * @param result the result the service returned, never null on this path.
     */
    private static void warnOnUnreportedCustomerLock(final AccountUpdateResult result) {

        final String errorMessage = result.errorMessage();
        if (errorMessage != null && errorMessage.contains(CUSTOMER_LOCK_LITERAL)) {
            LOG.warn("Transaction {} program {} reports success with change action {} while the internal"
                    + " outcome is {}. This is the source's unreported customer lock, reproduced"
                    + " deliberately: app/cbl/COACTUPC.cbl:L2606-L2615 never tests the flag that"
                    + " app/cbl/COACTUPC.cbl:L3934-L3942 sets, so a customer read-for-update failure"
                    + " falls through WHEN OTHER to ACUP-CHANGES-OKAYED-AND-DONE. Neither record was"
                    + " written.",
                    UPDATE_TRANSACTION_ID, UPDATE_PROGRAM, result.changeAction(),
                    ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER);
        }
    }

    /**
     * Decides whether a request arrived without a usable identity.
     *
     * <p>Three states are rejected explicitly rather than collapsed: a null principal, which is what a
     * request that bypassed the security filter chain presents; a principal that reports itself
     * unauthenticated; and an anonymous token, which reports itself <em>authenticated</em> and would
     * therefore slip past a bare {@code isAuthenticated()} test. Only sign-on, transaction {@code CC00},
     * is unauthenticated, so both operations of this class require a bearer credential.</p>
     *
     * <p>This method is duplicated across the controllers of this package rather than shared, because a
     * base class, an abstract controller and a shared helper type are all expressly excluded from this
     * package: the folder holds nine controllers and nothing else. The duplication is four lines of pure
     * predicate with no state, which is the cheaper of the two costs.</p>
     *
     * @param authentication the principal Spring Security resolved, which may be null.
     * @return true when the request carries no usable identity
     */
    private static boolean isUnauthenticated(final Authentication authentication) {
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    /**
     * Maps a concurrency outcome onto its own status, so that the five stay distinguishable.
     *
     * <p>Collapsing them into a single conflict status is classified <strong>High</strong>: the
     * specification is explicit that every one must map to a distinguishable HTTP response, because the
     * legacy screen showed the operator five different things. The choice of status belongs here rather
     * than on the exception - {@code com.cardemo.exception.ConcurrentUpdateException} carries no
     * annotation of any kind, and its own documentation assigns status selection to the controller.</p>
     *
     * <ul>
     *   <li>{@code COULD_NOT_LOCK_ACCOUNT} maps to {@code 423 Locked}: the read-for-update guard at
     *       {@code app/cbl/COACTUPC.cbl:L3907-L3915} returned with nothing written, which is precisely
     *       what a lock status describes, and the request is safe to retry unchanged.</li>
     *   <li>{@code COULD_NOT_LOCK_CUSTOMER} maps to {@code 409 Conflict}. It is kept mapped and its
     *       literal kept reachable even though Trap 1 shows it cannot arrive as a top-level failure
     *       through the update operation, because {@code :L2606-L2615} never tests it. Removing the arm
     *       would make the enum's five constants unrepresentable and would hide the defect rather than
     *       document it.</li>
     *   <li>{@code DATA_CHANGED_BEFORE_UPDATE} maps to {@code 412 Precondition Failed}. The snapshot is
     *       a precondition in the exact sense the status means: {@code 9700-CHECK-CHANGE-IN-REC} at
     *       {@code :L4109-L4195} compared the live records against it and refused.</li>
     *   <li>{@code LOCKED_BUT_UPDATE_FAILED} maps to {@code 500}. Both records were locked and the
     *       snapshot matched, so a rewrite failing is a server fault and not a client one. The single
     *       flag covers both rewrite sites, {@code :L4076-L4081} and {@code :L4095-L4103}, which is why
     *       there are five outcomes rather than six.</li>
     *   <li>{@code CHANGES_NOT_CONFIRMED} maps to {@code 428 Precondition Required}: the confirmation
     *       the {@code :L2602-L2603} gate demands was absent, so the client must supply something before
     *       the write can proceed.</li>
     * </ul>
     *
     * <p>A null outcome maps to {@code 409}. That is reachable, because the two message-only constructors
     * of the exception leave the outcome unset, and answering it explicitly is cheaper than assuming it
     * cannot happen.</p>
     *
     * @param outcome the outcome the exception named, which may be null.
     * @return the status that outcome is reported as, never null
     */
    private static HttpStatus statusFor(final ConcurrentUpdateException.Outcome outcome) {

        if (outcome == null) {
            return HttpStatus.CONFLICT;
        }

        return switch (outcome) {
            case COULD_NOT_LOCK_ACCOUNT -> HttpStatus.LOCKED;
            case COULD_NOT_LOCK_CUSTOMER -> HttpStatus.CONFLICT;
            case DATA_CHANGED_BEFORE_UPDATE -> HttpStatus.PRECONDITION_FAILED;
            case LOCKED_BUT_UPDATE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case CHANGES_NOT_CONFIRMED -> HttpStatus.PRECONDITION_REQUIRED;
        };
    }

    /**
     * Selects the problem detail for a concurrency failure, byte-exactly where the source has a literal.
     *
     * <p>Four of the five outcomes carry their own literal from the error field
     * {@code WS-RETURN-MSG PIC X(75)} declared at {@code app/cbl/COACTUPC.cbl:L479}, and those literals
     * are read from the exception's outcome enum so that they have exactly one definition in the
     * codebase. The fifth, {@code CHANGES_NOT_CONFIRMED}, has an empty legacy message because the
     * not-confirmed state is not an error: its text belongs to the separate information field
     * {@code WS-INFO-MSG PIC X(40)} at {@code :L463}, and {@value #NOT_CONFIRMED_PROMPT} is
     * {@code 88 PROMPT-FOR-CONFIRMATION} at {@code :L472-L473} reproduced exactly, including the missing
     * space after its full stop.</p>
     *
     * <p>An unnamed outcome, and the theoretical case of a named outcome whose literal is empty, both
     * fall back to a fixed sentence rather than to a null detail.</p>
     *
     * @param conflict the failure to describe.
     * @return the detail text, never null and never empty
     */
    private static String detailFor(final ConcurrentUpdateException conflict) {

        final ConcurrentUpdateException.Outcome outcome = conflict.getOutcome();
        if (outcome == null) {
            return CONCURRENCY_PROBLEM_DETAIL;
        }
        if (outcome == ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED) {
            return NOT_CONFIRMED_PROMPT;
        }

        final String legacyMessage = outcome.getLegacyMessage();
        return legacyMessage.isEmpty() ? CONCURRENCY_PROBLEM_DETAIL : legacyMessage;
    }

    /**
     * Maps a field-level rejection onto {@code 400 Bad Request}.
     *
     * <p>The exception's message is relayed <strong>byte for byte</strong>, because the legacy literals
     * are part of the observable contract rather than internal diagnostics: a client sees
     * {@code Credit Limit must be supplied} from {@code app/cbl/COACTUPC.cbl:L505-L506} or
     * {@code Credit Limit is not valid} from {@code :L507-L508} exactly as the 3270 screen displayed it,
     * along with {@code Card expiry month must be between 1 and 12} at {@code :L509-L510} and
     * {@code Invalid card expiry year} at {@code :L511-L512}. The {@code failureKind} property is what
     * makes the first pair meaningful: those two literals prove that blank and invalid are separate
     * states in the source, so they are never collapsed here. None of these literals is restated in this
     * class - each arrives on the exception the service raised, so there is one definition of each.</p>
     *
     * <p>Nothing identifying is logged. The field name is a schema label such as the credit-limit field
     * and carries no value, and the submitted value itself is never written to a log line, because this
     * payload carries a social security number, a date of birth, a government-issued identifier and an
     * electronic funds account identifier.</p>
     *
     * @param rejection the field-level rejection, whose message is a legacy literal.
     * @return {@code 400} carrying a problem detail, plus the field name when one was named and always
     * the failure kind
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailure(final ValidationException rejection) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        final String rejectionMessage = rejection.getMessage();
        if (rejectionMessage != null) {
            problem.setDetail(rejectionMessage);
        }
        if (rejection.hasFieldName()) {
            problem.setProperty(FIELD_PROPERTY, rejection.getFieldName());
        }
        problem.setProperty(FAILURE_KIND_PROPERTY, rejection.getFailureKind());

        LOG.warn("Refused an account request with 400 for field {} and failure kind {}",
                rejection.getFieldName(), rejection.getFailureKind());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps a missing record onto {@code 404 Not Found}, naming which link of the chain found nothing.
     *
     * <p>{@code app/cbl/COACTVWC.cbl} walks three datasets and builds a different diagnostic for each
     * with {@code STRING}: the cross-reference link at {@code :L747-L757} and the account-master link at
     * {@code :L796-L805} both read {@code ' not found in'}, at {@code :L750} and {@code :L799}
     * respectively, while the customer-master link at {@code :L846-L856} reads {@code ' not found'} at
     * {@code :L849}. The update path adds a fourth sentence,
     * {@code Did not find this account in cards database} at
     * {@code app/cbl/COACTUPC.cbl:L513-L514}. On a 3270 those four sentences went to the operator who had
     * just typed the key; over HTTP they go to whoever asked, so the distinction between them is what makes a
     * 404 usable for enumeration. It is preserved in the log rather than in the response, and the wording
     * itself still reaches a caller where the source put it - on the result of a <em>successful</em> exchange,
     * never on an error body.</p>
     *
     * <p><strong>Nothing is relayed from the exception, and that is a correction rather than a
     * tightening.</strong> On this resource group the service does not put a screen sentence on the
     * exception at all: {@code AccountViewService} records the file status {@code '23'} for an empty
     * repository result and hands it to {@code FileStatusMapper}, whose composed message names the
     * operation, the logical file and the legacy status. Relaying it disclosed all three, and
     * {@code recordType} and {@code recordKey} disclosed the dataset and the key beside it - and on the
     * customer link that key is an identifier the caller never supplied, since it is resolved server side
     * from the cross-reference. All four now go to the {@code WARN} log only, where they are subject to the
     * masking configuration and reachable from the correlation identifier, and the body carries
     * {@value #NOT_FOUND_PROBLEM_DETAIL}, {@value #ERROR_CODE_NOT_FOUND}, the resource-specific
     * {@link PublicErrorCode#ACCOUNT_RECORD_NOT_FOUND} and that correlation identifier.</p>
     *
     * @param notFound the missing-record failure raised by one of the three links.
     * @return {@code 404} carrying the fixed detail, the stable error codes and the correlation identifier,
     * and never the record type, the key or the composed diagnostic
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(final RecordNotFoundException notFound) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, NOT_FOUND_PROBLEM_DETAIL);
        problem.setTitle(NOT_FOUND_PROBLEM_TITLE);
        problem.setProperty(PUBLIC_CODE_PROPERTY, PublicErrorCode.ACCOUNT_RECORD_NOT_FOUND.code());

        // The composed message, the record type and the record key all go to the log and none to the body.
        // The message is the status mapper's internal diagnostic on this resource group; the record type is a
        // logical file or alternate-index path name; the key, echoed on a 404, is what an enumeration attempt
        // needs. The correlation identifier in the body is how a caller reporting this reaches these lines.
        // The public code is logged as well as returned, and that is the whole point of logging it: a caller
        // reports the code and the correlation identifier from the body, and this line is what turns that pair
        // into the record type, the key and the status mapper's diagnostic. Emitting the code costs nothing in
        // disclosure - it is already in the response - and without it the two channels share only the
        // correlation identifier, which an aggregator may sample away.
        LOG.warn("Answered an account request with 404, code {}, for record type {} key {}: {}",
                PublicErrorCode.ACCOUNT_RECORD_NOT_FOUND.code(),
                notFound.recordType().orElse("unspecified"), notFound.recordKey().orElse("unspecified"),
                notFound.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(withPublicEnvelope(problem, ERROR_CODE_NOT_FOUND));
    }

    /**
     * Maps each of the five concurrency outcomes onto its own status - Trap 2 of the class documentation.
     *
     * <p>The status comes from {@link #statusFor} and the detail from
     * {@link #detailFor}, both of which document their reasoning in full.
     * Every response additionally carries the {@code outcome} property, so a client never has to infer
     * the outcome from the status alone, and the {@code changeAction} property, which is the
     * {@code ACUP-CHANGE-ACTION PIC X(1)} marker of {@code app/cbl/COACTUPC.cbl:L654-L668} that the
     * failed turn would have left on the screen - {@code 'L'} for an account lock error, {@code 'F'} for
     * a failed rewrite, {@code 'S'} for a snapshot mismatch that repaints the details, {@code 'N'} for
     * changes awaiting confirmation. Collapsing the five into a single conflict status is classified
     * <strong>High</strong> and is not done.</p>
     *
     * <p>The not-confirmed outcome is logged at debug rather than warn, because it is an ordinary step of
     * the conversation and not a fault: it is the reproduction of the {@code CONTINUE} arm at
     * {@code :L2620-L2621}, where the source simply repainted the screen and waited for PF5.</p>
     *
     * @param conflict the concurrency failure, whose outcome may be null when a message-only constructor
     * was used.
     * @return one of {@code 423}, {@code 412}, {@code 428}, {@code 409} or {@code 500}, carrying a
     * problem detail with the outcome and the change-action marker
     */
    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentUpdate(final ConcurrentUpdateException conflict) {

        final ConcurrentUpdateException.Outcome outcome = conflict.getOutcome();
        final HttpStatus status = statusFor(outcome);

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detailFor(conflict));
        problem.setTitle(CONCURRENCY_PROBLEM_TITLE);

        if (outcome != null) {
            problem.setProperty(OUTCOME_PROPERTY, outcome);
            problem.setProperty(CHANGE_ACTION_PROPERTY, String.valueOf(outcome.getChangeActionCode()));
        }
        // The affected record names the dataset the lock or the rewrite failed against, so it is logged
        // below and not returned. The outcome and the change action above are screen state, not internals:
        // they are the five-way outcome and the ACUP-CHANGE-ACTION marker the failed turn would have left on
        // the map, and a client needs both to reproduce the source's behaviour.
        final String affectedRecord = conflict.getAffectedRecord();

        if (outcome == ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED) {
            LOG.debug("Answered transaction {} with {}: the {} action was not asserted",
                    UPDATE_TRANSACTION_ID, status.value(), CONFIRM_PARAMETER);
        } else {
            LOG.warn("Answered transaction {} program {} with {} for concurrency outcome {} on record {}",
                    UPDATE_TRANSACTION_ID, UPDATE_PROGRAM, status.value(), outcome, affectedRecord);
        }

        return ResponseEntity.status(status).body(withPublicEnvelope(problem, ERROR_CODE_UPDATE_CONFLICT));
    }

    /**
     * Maps a referential-constraint refusal onto {@code 409 Conflict}.
     *
     * <p>The schema declares ten foreign keys, and the account and customer relations this operation
     * writes sit at the head of most of them. A refusal is a genuine conflict between the submitted state
     * and the state of the database rather than a malformed request, which is what distinguishes this
     * from the {@code 400} path. The detail is fixed and names no value, because the payload carries
     * personally-identifiable data.</p>
     *
     * <p><strong>The constraint name and the relation are logged rather than returned.</strong> They are the
     * schema's own identifiers, and a client can act on neither: returning them published a table name and a
     * named constraint on it, which is a map of the store, and both are free to change with any migration, so
     * neither could be part of an API contract. The operator still has both, at {@code WARN}, joined to the
     * caller's report by the correlation identifier the body carries, and the response carries the stable
     * pair - {@link PublicErrorCode#ACCOUNT_WRITE_REFUSED} in {@code code} and
     * {@value #ERROR_CODE_CONSTRAINT} in {@code errorCode}.</p>
     *
     * @param violation the constraint refusal, whose constraint name and relation may each be null.
     * @return {@code 409} carrying the fixed detail, the stable error codes and the correlation identifier
     */
    @ExceptionHandler(DataIntegrityException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(final DataIntegrityException violation) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, INTEGRITY_PROBLEM_DETAIL);
        problem.setTitle(INTEGRITY_PROBLEM_TITLE);
        problem.setProperty(PUBLIC_CODE_PROPERTY, PublicErrorCode.ACCOUNT_WRITE_REFUSED.code());

        // The constraint name and the relation are logged, not returned. They are the schema's own
        // identifiers: together they disclose a table name and a named constraint on it, which is a map of
        // the store rather than anything the caller can act on. The public code is logged beside them so the
        // entry can be found from the code a caller quotes.
        LOG.warn("Answered an account request with 409 and public code {}: constraint {} on relation {} "
                        + "refused the write",
                PublicErrorCode.ACCOUNT_WRITE_REFUSED.code(), violation.getConstraintName(),
                violation.getRelation(), violation);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withPublicEnvelope(problem, ERROR_CODE_CONSTRAINT));
    }

    /**
     * Maps {@code FILE STATUS '35'} onto {@code 503 Service Unavailable}.
     *
     * <p>Status {@code '35'} meant a dataset was not open - which in the legacy region was what
     * {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl} controlled, and in the target means
     * the database is unreachable or the migrations have not been applied. It is a transient condition
     * that a client may sensibly retry, so it is reported as unavailability rather than as a fault. The
     * detail is fixed and the dataset is named on the log rather than in the body.</p>
     *
     * @param unavailable the file-unavailable failure.
     * @return {@code 503} carrying a problem detail, plus the resource name when the throwing site named
     * one
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFileUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_PROBLEM_DETAIL);
        problem.setTitle(UNAVAILABLE_PROBLEM_TITLE);

        // The dataset name is logged, not returned.
        LOG.error("Answered an account request with 503: resource {} is not available",
                unavailable.resourceName().orElse("unspecified"), unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNAVAILABLE));
    }

    /**
     * Maps the {@code FILE STATUS '9x'} family onto {@code 502 Bad Gateway}.
     *
     * <p>A physical or logical input-output failure is a fault in the store behind this service rather
     * than in the request or in this service, which is exactly what a gateway status describes.</p>
     *
     * <p><strong>The expanded status, the logical file and the operation are logged rather than
     * returned.</strong> The four-character rendering that {@code 9910-DISPLAY-IO-STATUS} produced - the
     * first byte copied through and the second expanded to three digits whenever the status was
     * non-numeric or began with {@code '9'} - remains available to an operator, on the {@code ERROR} log,
     * beside the dataset and the verb. What no longer happens is returning the three of them together to
     * the caller, which said which internal dataset failed which verb with which legacy status.</p>
     *
     * @param failure the input-output failure, whose expanded status is never null while the file name
     * and operation may each be null.
     * @return {@code 502} carrying the fixed detail, the stable error code and the correlation identifier
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccessFailure(final FileAccessException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, IO_PROBLEM_DETAIL);
        problem.setTitle(IO_PROBLEM_TITLE);

        // The expanded status, the logical file and the operation are logged, not returned.
        LOG.error("Answered an account request with 502: status {} on file {} during {}",
                failure.getExpandedStatus(), failure.getLogicalFileName(), failure.getOperation(), failure);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(withPublicEnvelope(problem, ERROR_CODE_IO_FAILURE));
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}, preserving the legacy abend contract.
     *
     * <p>The batch corpus terminated an unexpected status by moving {@code 999} into the abend code and
     * calling the language-environment abend service, leaving a job return code of {@code 12}; the online
     * corpus populated the four work areas of {@code app/cpy/CSMSG02Y.cpy} - which is internally titled
     * {@code CABENDD.CPY} and declares {@code ABEND-CODE PIC X(4)} at {@code :L22-L23},
     * {@code ABEND-CULPRIT PIC X(8)} at {@code :L24-L25}, {@code ABEND-REASON PIC X(50)} at
     * {@code :L26-L27} and {@code ABEND-MSG PIC X(72)} at {@code :L28-L29} - and sent them to the
     * terminal. Both halves of that contract survive: the code and the return code are surfaced as
     * properties, and the culprit, the reason and the message reach the log together with the cause.</p>
     *
     * <p>The detail is fixed and reveals nothing, matching {@code server.error.include-message: never}
     * and {@code include-stacktrace: never} in {@code src/main/resources/application.yml}. The abend code
     * falls back to the single definition on {@code com.cardemo.exception.FatalProcessingException} when
     * the throwing site carried none, so the response always states one.</p>
     *
     * @param abend the abend, whose payload members may each be null when a message-only constructor was
     * used.
     * @return {@code 500} carrying a problem detail with the abend code and the batch return code
     */
    @ExceptionHandler(FatalProcessingException.class)
    public ResponseEntity<ProblemDetail> handleAbend(final FatalProcessingException abend) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        // Logged, not returned: 999 and 12 are internals of the terminating path.
        LOG.error("An account operation abended: code {} returnCode {} culprit {} reason {}",
                abend.getAbendCode() == null ? ABEND_CODE : abend.getAbendCode(),
                FatalProcessingException.BATCH_RETURN_CODE, abend.getAbendCulprit(), abend.getAbendReason(),
                abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_ABEND));
    }

    /**
     * Maps any remaining typed CardDemo failure onto {@code 500 Internal Server Error}.
     *
     * <p>This is the base of the hierarchy and therefore the last resort: Spring selects the most
     * specific handler, so every subtype declared above reaches its own arm and only a subtype with no
     * arm of its own arrives here. One such subtype exists in the exception package -
     * {@code DuplicateRecordException}, the translation of {@code FILE STATUS '22'} - and it is
     * deliberately left to this arm rather than named. Neither of these two operations inserts a row:
     * {@code 9600-WRITE-PROCESSING} rewrites two records that it has already read for update, so a
     * duplicate key on this path is a server-side fault and {@code 500} is the correct report. Naming the
     * type here would add an import this file has no other use for.</p>
     *
     * <p>Nothing is swallowed: the throwable reaches the log with its cause chain intact, and the fixed
     * detail keeps the response free of anything the request submitted.</p>
     *
     * @param failure the typed failure that no more specific handler claimed.
     * @return {@code 500} carrying a fixed problem detail
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("An account operation failed with a typed CardDemo exception", failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_INTERNAL));
    }

    /**
     * Stamps the two properties every error body carries, and returns the same instance for chaining.
     *
     * <p>Called by each {@code @ExceptionHandler} above as the last thing it does to the body, so a future
     * handler cannot omit the envelope by accident: the {@code return} statement reads
     * {@code body(withPublicEnvelope(problem, ...))}, and a handler written without it does not compile
     * into that shape.
     *
     * <p><strong>This is the whole of the {@code CWE-209} posture.</strong> What the body carries is the
     * status, the title, a detail that is either a legacy screen literal or a fixed sentence, the error code
     * and the correlation identifier. What it no longer carries is the relation, the constraint name, the
     * logical file or dataset name, the input-output operation, the expanded file status, the record type,
     * the abend code and the batch return code. Every one of those is still emitted - at {@code WARN} or
     * {@code ERROR}, on a log stream the caller cannot read - so no diagnostic capability is lost and
     * nothing is swallowed.
     *
     * @param problem the body under construction; must not be null.
     * @param errorCode one of the {@code ERROR_CODE_*} constants.
     * @return {@code problem}, so the call can be inlined into the {@code body(...)} argument
     */
    private static ProblemDetail withPublicEnvelope(final ProblemDetail problem, final String errorCode) {

        problem.setProperty(ERROR_CODE_PROPERTY, errorCode);

        final String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
        problem.setProperty(CORRELATION_ID_PROPERTY,
                correlationId == null || correlationId.isEmpty() ? CORRELATION_ID_UNAVAILABLE : correlationId);

        return problem;
    }

    /**
     * The stable, public error codes this controller publishes in the {@code code} property.
     *
     * <p>These exist because the alternative is a caller branching on prose or on the name of a database
     * object. A code is a promise: the set may grow, but a member's spelling and meaning do not change, and
     * nothing about the server's schema, constraints or record keys is encoded in it. That is precisely what
     * {@code recordType}, {@code recordKey}, {@code constraintName} and {@code relation} could not offer -
     * they described the internals, they moved with every migration, and they let a 404 or a 409 be used to
     * enumerate what exists on the other side of the boundary.
     *
     * <p>The codes are deliberately coarse. A caller needs to know whether to fix its request, re-read and
     * retry, or escalate; it does not need to know which of ten foreign keys refused a write. An operator who
     * does need that finds it in the log entry carrying the same correlation identifier as the response.
     */
    private enum PublicErrorCode {

        /**
         * Some link of the account lookup chain found no row. Which link is deliberately not distinguished.
         */
        ACCOUNT_RECORD_NOT_FOUND("ACCOUNT_RECORD_NOT_FOUND"),

        /**
         * A referential constraint refused the write. Which constraint, and on which relation, is deliberately
         * not distinguished.
         */
        ACCOUNT_WRITE_REFUSED("ACCOUNT_WRITE_REFUSED");

        /** The wire form, which is part of the published contract. */
        private final String code;

        /**
         * Binds a constant to the exact text that reaches the wire.
         *
         * @param code the published wire form; never {@code null} and never derived from the constant name,
         *     because a rename must not be able to change a value a client matches on
         */
        PublicErrorCode(final String code) {
            this.code = code;
        }

        /**
         * The wire form of this code.
         *
         * @return the code as it appears in the {@code code} property; never {@code null}
         */
        String code() {
            return this.code;
        }
    }
}
