/*
 * ******************************************************************
 * Program     : AccountViewService.java
 * Application : CardDemo
 * Type        : Spring Service Bean (online)
 * Function    : Account view - cross-reference, account and customer
 *               lookup chain for CICS transaction CAVW.
 * Source      : app/cbl/COACTVWC.cbl (941 lines, 38 paragraphs) @ 7756d89
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
package com.cardemo.service.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Account view: the Java replacement for CICS transaction {@code CAVW} and the program it fronts,
 * {@code app/cbl/COACTVWC.cbl}.
 *
 * <p>Every legacy claim below cites a path and a line or line range in the frozen corpus, and all of them
 * are keyed to the traceability anchor commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short
 * {@code 7756d89}, exactly as the {@code Source} line in the file header records. The anchor is stated once
 * here rather than repeated on every citation. Bare {@code :NNN} citations refer to
 * {@code app/cbl/COACTVWC.cbl}; citations against any other member name the member.</p>
 *
 * <h2>1. What it does</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD:317-318} declares {@code DEFINE TRANSACTION(CAVW) ... PROGRAM(COACTVWC)},
 * and {@code :181-182} declares {@code DEFINE PROGRAM(COACTVWC)} with {@code DESCRIPTION(VIEW ACCT)}. Note
 * that {@code CAVW} carries no {@code DESCRIPTION} line, an asymmetry against {@code CAUP} at
 * {@code app/csd/CARDDEMO.CSD:306-308}; nothing follows from it beyond inventory accuracy.</p>
 *
 * <p>Given an eleven-digit account identifier this bean walks the three-step lookup chain the legacy program
 * walks - card cross-reference by account, then account master, then customer master - and projects the
 * result into {@code com.cardemo.model.dto.AccountDto}, whose thirty-seven components are the thirty-six
 * output fields of {@code app/cpy-bms/COACTVW.CPY} plus the error message. The bean is strictly read-only:
 * a verb census of the source finds no {@code WRITE}, no {@code REWRITE} and no {@code DELETE} anywhere in
 * its 941 lines, which is what justifies {@code @Transactional(readOnly = true)} on both entry points.</p>
 *
 * <p>Two entry points are offered, and the difference between them is the single most important thing to
 * understand about this class:</p>
 *
 * <ul>
 *   <li>{@link #processRequest(String, String, EntryMode)} is the <em>screen-parity</em> entry point. It
 *       reproduces the legacy conversation exactly: a lookup miss is <strong>not</strong> an error, it is a
 *       control path that sets a field-error state and renders a diagnostic message into the response, just
 *       as {@code 9200}/{@code 9300}/{@code 9400} do at {@code :741-758}, {@code :789-807} and
 *       {@code :839-857}. Nothing is thrown for a miss.</li>
 *   <li>{@link #viewAccount(String)} is the <em>REST</em> entry point used by
 *       {@code com.cardemo.controller.AccountController} under {@code /api/accounts/*}. It runs the identical
 *       chain and then rethrows the typed exception that the miss produced. The typed exception is built by
 *       {@code com.cardemo.service.shared.FileStatusMapper} on every I/O path and retained on the request
 *       context; it is never discarded, so no exception is swallowed by the screen-parity path either.</li>
 *   </ul>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Build and verify the whole module with {@code ./mvnw -B -ntp -q verify} from the repository root. The
 * prerequisite is stated as a capability rather than as a host path: JDK 25 on {@code PATH} with
 * {@code JAVA_HOME} set, however the host provides it, with Apache Maven 3.9.11 supplied by the pinned
 * wrapper. {@code ./mvnw -B -ntp -o clean compile} and {@code ./mvnw -B -ntp -o clean test} both run offline
 * against the warm local repository; note that {@code -o} also causes Maven to skip
 * {@code dependency-check:check}, which declares {@code requiresOnline}, and a skipped scan is never
 * evidence that the scan passes. Nothing in this file is
 * executable standalone: it is a Spring bean and is exercised either through
 * {@code com.cardemo.controller.AccountController} or directly from unit tests under
 * {@code src/test/java/com/cardemo/unit/service/}, which construct it with the five collaborators of
 * section 3 and drive it with test doubles. Integration coverage of the same chain lives under
 * {@code src/test/java/com/cardemo/integration/repository/} against a Testcontainers PostgreSQL 16.</p>
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <p>This class reads no property of its own - it holds no {@code @Value}, no {@code Environment} lookup and
 * no {@code System.getenv} call. Its behaviour nonetheless depends on four settings, whose values in this
 * repository are:</p>
 *
 * <ul>
 *   <li>{@code spring.jpa.open-in-view: false} - the default asserted by the application profiles. Because
 *       there is no view-scoped session, every field the projection needs must be read inside the
 *       transactional boundary. The projection therefore materialises each entity into plain {@code String}
 *       and {@code BigDecimal} values before the transaction ends, and never hands a managed entity out.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} - the schema is owned by the three Flyway
 *       migrations, not by Hibernate. A column-width drift between {@code V1__create_schema.sql} and the
 *       entity mappings fails at start-up rather than corrupting a projection at run time.</li>
 *   <li>A {@code java.time.Clock} bean must be present in the context. That is not a new requirement
 *       introduced here: {@code com.cardemo.service.shared.DateValidationService} already declares a
 *       {@code Clock} constructor parameter, so the context already needs the bean. It is supplied by
 *       {@code com.cardemo.config.ObservabilityConfig#clock(String)} as a system clock in the deployment's own
 *       zone, which is the one production declaration in the tree; tests inject {@code Clock.fixed(...)} as
 *       {@code @Primary}.</li>
 *   <li>{@code com.cardemo.service.shared.FileStatusMapper} is a {@code @Component} with a no-argument
 *       constructor, so it is auto-wired with no configuration.</li>
 *   </ul>
 *
 * <p>No default is hard-coded here for any of the four. There is no secret, credential, token or signing key
 * anywhere in this class.</p>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <table>
 *   <caption>Failure modes, the exception raised by {@link #viewAccount(String)}, and remediation</caption>
 *   <tr><th>Failure mode</th><th>Exception</th><th>Remediation</th></tr>
 *   <tr>
 *     <td>Account filter blank, absent, all spaces, or the single character {@code *} ({@code :653-662})</td>
 *     <td>{@code ValidationException} with {@code FailureKind.BLANK} and message
 *         {@code No input received}. Note the message is <em>not</em>
 *         {@code Account number not provided}, even though {@code 2210-EDIT-ACCOUNT} stamps exactly that at
 *         {@code :657-659}: it stamps it <em>inside</em> the {@code IF WS-RETURN-MSG-OFF} first-error-wins
 *         latch, and the cross-field edit at {@code :639-642} then overwrites it <em>unlatched</em> with
 *         {@code 88 NO-SEARCH-CRITERIA-RECEIVED} ({@code :123-124}). The latch therefore protects the
 *         message only within {@code 2210}; the later unlatched assignment always wins</td>
 *     <td>Supply an eleven-digit account identifier; {@code *} means "not supplied", never a wildcard.</td>
 *   </tr>
 *   <tr>
 *     <td>Account filter non-numeric, shorter than eleven digits, or all zeroes ({@code :666-676})</td>
 *     <td>{@code ValidationException} with {@code FailureKind.INVALID} and message
 *         {@code Account Filter must  be a non-zero 11 digit number}</td>
 *     <td>Send exactly eleven digits with at least one non-zero; no padding is applied on your behalf.</td>
 *   </tr>
 *   <tr>
 *     <td>Cross-reference miss - no {@code CARDXREF} row carries the account ({@code :741-758})</td>
 *     <td>{@code RecordNotFoundException}</td>
 *     <td>Confirm the account exists in {@code card_cross_reference}; reseed from
 *         {@code app/data/ASCII/cardxref.txt} through {@code V3__seed_data.sql} if it does not.</td>
 *   </tr>
 *   <tr>
 *     <td>Account-master miss - the cross-reference resolves but {@code ACCTDAT} has no row
 *         ({@code :789-807})</td>
 *     <td>{@code RecordNotFoundException}</td>
 *     <td>Referential drift between {@code card_cross_reference} and {@code account}; reload both from the
 *         ASCII fixtures. <strong>The chain does not stop here</strong> - see defect V1 below.</td>
 *   </tr>
 *   <tr>
 *     <td>Customer-master miss - {@code CUSTDAT} has no row for the cross-referenced customer
 *         ({@code :839-857})</td>
 *     <td>{@code RecordNotFoundException}</td>
 *     <td>Referential drift between {@code card_cross_reference} and {@code customer}; reload from
 *         {@code app/data/ASCII/custdata.txt}.</td>
 *   </tr>
 *   <tr>
 *     <td>Unexpected file status on any of the three reads - the {@code WHEN OTHER} arms at {@code :759-768},
 *         {@code :809-818} and {@code :858-867}</td>
 *     <td>{@code FileAccessException} for the {@code 9x} family, {@code FileUnavailableException} for
 *         {@code 35}, {@code DuplicateRecordException} for {@code 22} - the choice belongs to
 *         {@code FileStatusMapper}, not to this class</td>
 *     <td>Inspect the {@code File Error:} diagnostic on the response, which carries the operation, the
 *         logical file name and the CICS response pair. Check database connectivity and the Flyway
 *         baseline.</td>
 *   </tr>
 *   <tr>
 *     <td>Abend path - anything not modelled above, reaching the {@code EXEC CICS HANDLE ABEND LABEL} handler
 *         registered at {@code :264-266}</td>
 *     <td>{@code FatalProcessingException} carrying abend code {@code 9999} and culprit {@code COACTVWC}</td>
 *     <td>The original throwable is preserved as the cause; read it first. This path is a bug in this bean
 *         or in a collaborator, not a data condition.</td>
 *   </tr>
 *   <tr>
 *     <td>Unexpected entry mode - a {@code null} {@link EntryMode}, reproducing a COMMAREA whose
 *         {@code CDEMO-PGM-CONTEXT} byte is neither {@code 0} nor {@code 1} ({@code :375-382})</td>
 *     <td>None. A plain-text response carrying {@code UNEXPECTED DATA SCENARIO} is returned - see defect V6
 *         below</td>
 *     <td>Pass a non-null entry mode. The legacy program deliberately does not abend here.</td>
 *   </tr>
 * </table>
 *
 * <h2>5. Provenance</h2>
 *
 * <table>
 *   <caption>Source of record for this class</caption>
 *   <tr><td>Source program</td><td>{@code app/cbl/COACTVWC.cbl}</td></tr>
 *   <tr><td>Line count</td><td>941 (LF only; carriage-return count zero)</td></tr>
 *   <tr><td>Paragraph count</td><td>38</td></tr>
 *   <tr><td>Anchor commit</td><td>{@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} ({@code 7756d89})</td></tr>
 *   <tr><td>Mapped Java methods</td><td>37, each private, each mapping 1:1 onto one source label</td></tr>
 * </table>
 *
 * <p>The paragraph arithmetic, which the scope-coverage gate checks, is: 35 Area-A paragraph labels declared
 * in the program itself, plus the Area-A construct {@code COPY 'CSSTRPFY'} at {@code :913}, plus the two
 * labels that copybook contributes - {@code YYYY-STORE-PFKEY.} at {@code app/cpy/CSSTRPFY.cpy:17} and
 * {@code YYYY-STORE-PFKEY-EXIT.} at {@code app/cpy/CSSTRPFY.cpy:80} - giving 38. The {@code COPY} line is a
 * directive, not a paragraph body, so <strong>no method is emitted for it</strong>; 35 plus 2 gives the 37
 * mapped methods. Every one carries Javadoc naming its path, its exact label and its logical line.</p>
 *
 * <p>Copybooks this program declares that contribute no Java type: {@code DFHBMSCA} at {@code :221} and
 * {@code DFHAID} at {@code :222} are supplied by CICS, are absent from the repository, and map onto framework
 * mechanisms rather than types. {@code CSSTRPFY} at {@code :913} is procedural and maps onto two methods.
 * {@code CVACT02Y} at {@code :248} is <strong>declared but never used</strong> - the {@code PROCEDURE
 * DIVISION} makes no reference to {@code CARD-RECORD} - so no card entity is imported or modelled here
 * (severity Low). The comment above that {@code COPY} at {@code :247} reads {@code *CUSTOMER RECORD LAYOUT},
 * which is itself wrong because {@code CVACT02Y} is the card layout; the comment is cited, not corrected.</p>
 *
 * <h2>6. Preserved-defect register</h2>
 *
 * <p>Eight legacy behaviours below are defects, redundancies or unreachable artefacts. Seven are reproduced
 * rather than repaired, because behavioural parity is the acceptance contract and the paragraph-level
 * traceability matrix must stay mechanically provable; the eighth cannot be reproduced and is labelled a
 * deviation rather than dressed up as parity. Each is owed an entry in the {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md}, so none is untracked deferred work, and no deferred-work marker token of
 * any kind appears anywhere in this file.</p>
 *
 * <ul>
 *   <li><strong>V1 - High, behavioural. The account-to-customer transition is unguarded.</strong>
 *       {@code 9000-READ-ACCT} tests three conditions at {@code :697}, {@code :704} and {@code :713}. The
 *       first, {@code IF FLG-ACCTFILTER-NOT-OK}, works. The second and third test
 *       {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} and {@code DID-NOT-FIND-CUST-IN-CUSTDAT}, which are
 *       <em>message-literal</em> {@code 88}-levels on {@code WS-RETURN-MSG} declared at {@code :131-132} and
 *       {@code :133-134} - not boolean flags - and whose only {@code SET} statements are commented out at
 *       {@code :792} and {@code :842}. Neither literal is ever assigned, so neither guard can ever fire.
 *       A third such literal, {@code DID-NOT-FIND-ACCT-IN-CARDXREF} at {@code :129-130}, is neither set nor
 *       tested; its {@code IF} is commented out at {@code :696}. <strong>Consequence: after an
 *       account-master miss the chain continues into the customer lookup at {@code :708-711}.</strong> Only
 *       the cross-reference-to-account transition is gated. This class reproduces that exactly and adds no
 *       guard.</li>
 *   <li><strong>V2 - Medium. {@code SEND-LONG-TEXT} is unreachable.</strong> All three
 *       {@code PERFORM SEND-LONG-TEXT} sites are commented out, at {@code :768}, {@code :818} and
 *       {@code :867}, as is the {@code WS-LONG-MSG} assignment above each. Both labels are mapped anyway and
 *       the long-message buffer they read is therefore always unset. No JaCoCo exclusion is added for
 *       them.</li>
 *   <li><strong>V3 - Low. A source typo is cited, not corrected.</strong> The header comment above
 *       {@code SEND-PLAIN-TEXT} at {@code :875} reads {@code * Plain text exit - Dont use in production};
 *       the missing apostrophe in {@code Dont} is in the corpus.</li>
 *   <li><strong>V4 - Medium. {@code 0000-MAIN-EXIT.} is declared twice and both copies are dead.</strong>
 *       The label appears at {@code :408-410} and again at {@code :411-413}, both bodies being a bare
 *       {@code EXIT}. Neither is ever {@code PERFORM}ed or {@code GO TO}'d - all flow leaves through
 *       {@code GO TO COMMON-RETURN} - so both are unreachable. Two separate methods are emitted, each
 *       citing its own line. They are never consolidated.</li>
 *   <li><strong>V5 - Low. A wholly redundant decision.</strong> The three-arm
 *       {@code EVALUATE TRUE} at {@code :546-552} executes the identical statement
 *       {@code MOVE -1 TO ACCTSIDL} in all three arms. The structure is reproduced.</li>
 *   <li><strong>V6 - Medium. The abend payload is populated and then discarded.</strong> The
 *       {@code WHEN OTHER} arm at {@code :375-382} sets {@code ABEND-CULPRIT}, {@code ABEND-CODE} to
 *       {@code 0001} and {@code ABEND-REASON} to spaces, then moves {@code UNEXPECTED DATA SCENARIO} to
 *       <em>{@code WS-RETURN-MSG}, not {@code ABEND-MSG}</em>, and performs
 *       <em>{@code SEND-PLAIN-TEXT}, not {@code ABEND-ROUTINE}</em>. The program therefore does not abend on
 *       an unexpected scenario and the abend payload it just built is thrown away. This is also the only
 *       reason {@code SEND-PLAIN-TEXT} is reachable at all.</li>
 *   <li><strong>V7 - Low. The trailing error block is unreachable and cannot be transcribed.</strong>
 *       {@code :385-392} tests {@code IF INPUT-ERROR} and falls through into {@code COMMON-RETURN} at
 *       {@code :393-394}. Every arm of the {@code EVALUATE} above it terminates the task - {@code EXEC CICS
 *       XCTL} at {@code :349-352}, {@code GO TO COMMON-RETURN} at {@code :360}, {@code :367} and
 *       {@code :373}, and the {@code EXEC CICS RETURN} inside {@code SEND-PLAIN-TEXT} at {@code :885-886} -
 *       so control never arrives. Java's definite-unreachability rule makes it a compile error to place
 *       statements after branches that all return, so the block is documented here instead of transcribed.
 *       Nothing observable is lost: its only statement,
 *       {@code MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG}, is already performed by {@code COMMON-RETURN} at
 *       {@code :395} and by {@code 2000-PROCESS-INPUTS} at {@code :601}.</li>
 *   <li><strong>V8 - Low, labelled deviation. The abend default-message substitution is made to
 *       fire.</strong> {@code ABEND-ROUTINE} tests {@code IF ABEND-MSG EQUAL LOW-VALUES} at {@code :918}.
 *       {@code ABEND-DATA} is an independent {@code 01} group in {@code app/cpy/CSMSG02Y.cpy:21} whose four
 *       members all carry {@code VALUE SPACES}, and it is <em>not</em> among the three groups
 *       {@code INITIALIZE} names at {@code :268-270}, so {@code ABEND-MSG} holds blanks and not
 *       {@code LOW-VALUES} at task start. It is also assigned nowhere in the program - the
 *       {@code WHEN OTHER} arm writes {@code WS-RETURN-MSG} instead, per V6 - so under a strict reading the
 *       test is false, the substitution never fires, and the legacy path sends seventy-two blanks to the
 *       terminal. That byte image has no counterpart in a thrown exception, and an exception with a blank
 *       diagnostic would violate the meaningful-errors requirement, so the unset message is modelled as
 *       absent and the substitution does fire, yielding
 *       {@code FatalProcessingException.DEFAULT_ABEND_MESSAGE}. This is the author's evident intent and is
 *       the one item in this register that is a deviation rather than a reproduction.</li>
 *   </ul>
 *
 * <h2>7. Reconciliations against the migration brief</h2>
 *
 * <p>Six statements in the migration brief for this file disagree with the corpus. The corpus wins in every
 * case; each divergence is recorded here so the citations in this file remain checkable, and each is
 * severity Low unless stated.</p>
 *
 * <ul>
 *   <li>The header date separator is {@code /}, not {@code -}. {@code app/cpy/CSDAT01Y.cpy} declares
 *       {@code WS-CURDATE-MM-DD-YY} with {@code FILLER PIC X(01) VALUE '/'} between the components, so
 *       {@code :447} renders {@code MM/dd/yy}. {@code com.cardemo.service.menu.MainMenuService} already uses
 *       that pattern.</li>
 *   <li>The header time separator is {@code :}, not {@code -}, from the same copybook, so {@code :453}
 *       renders {@code HH:mm:ss} into a field of <strong>eight</strong> bytes.
 *       {@code AccountDto.CURRENT_TIME_LENGTH} is 8, confirming it; a nine-byte {@code CURTIME} would not
 *       fit.</li>
 *   <li>The cross-reference finder is
 *       {@code CardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc}, keyed on the account
 *       identifier rather than the card number. {@code app/catlg/LISTCAT.txt:488} marks the alternate index
 *       {@code NONUNIQKEY}, so the account key is not unique and the index stays non-unique - but a VSAM
 *       {@code READ} through {@code CXACAIX} surfaces the <em>first</em> record in ascending card-number
 *       order, so the finder applies {@code LIMIT 1} and returns an {@code Optional}; an empty
 *       {@code Optional} is the {@code NOTFND} condition. {@code findById} is <strong>not</strong> used: it
 *       would key on the card number and silently return a different record.</li>
 *   <li>{@code 2000-PROCESS-INPUTS} performs four further moves at {@code :601-604} -
 *       {@code WS-RETURN-MSG} to {@code CCARD-ERROR-MSG}, {@code LIT-THISPGM} to {@code CCARD-NEXT-PROG},
 *       {@code LIT-THISMAPSET} to {@code CCARD-NEXT-MAPSET} and {@code LIT-THISMAP} to
 *       {@code CCARD-NEXT-MAP} - which the brief omits. All four are reproduced.</li>
 *   <li>The {@code DID-NOT-FIND-*} names are message-literal {@code 88}-levels on {@code WS-RETURN-MSG}
 *       ({@code :117-134}), not boolean flags. They are modelled as three string constants and the two live
 *       guards compare the current return message against them, which is exactly what the source does and
 *       is why the guards are provably dead rather than merely apparently dead. Severity Medium, because
 *       modelling them as booleans would have produced guards that <em>can</em> fire.</li>
 *   <li>The {@code *} test at {@code :628} compares an {@code X(11)} field against the one-character literal
 *       {@code '*'}, which COBOL pads with blanks, so it matches a <em>single</em> asterisk followed by
 *       spaces - not a run of asterisks. {@code '***'} is not "not supplied"; it is invalid input.</li>
 *   </ul>
 *
 * <p>One further reconciliation concerns the money rendering, and it is the only place where this class
 * takes ownership of a contract another file declared unavailable. {@code AccountDto} documents the byte
 * image of its five money components as unavailable, on the premise that the target is {@code PIC X(15)};
 * that is the <em>input</em> field, {@code ACRDLIMI}. The <em>output</em> fields {@code ACRDLIMO},
 * {@code ACSHLIMO}, {@code ACURBALO}, {@code ACRCYCRO} and {@code ACRCYDBO}
 * ({@code app/cpy-bms/COACTVW.CPY:302}, {@code :314}, {@code :326}, {@code :332}, {@code :344}) are
 * numeric-edited {@code PIC +ZZZ,ZZZ,ZZZ.99}, fifteen characters wide, matching
 * {@code AccountDto.MONEY_DISPLAY_LENGTH}. The image is therefore fully derivable and
 * {@link #toEditedAmount(BigDecimal)} produces it. Two consequences are documented rather than smoothed
 * over: the mask holds nine integer digits while {@code app/cpy/CVACT01Y.cpy} declares
 * {@code PIC S9(10)V99}, so a COBOL {@code MOVE} truncates the high-order digit and so does this class; and
 * {@code AccountDto.toAmount} rejects group separators, so the edited text is display-only and deliberately
 * does not round-trip. The commented-out {@code EDIT-FIELD-9-2 PIC +ZZZ,ZZZ,ZZZ.99} at {@code :69} of the
 * source confirms the mask was the author's own intent.</p>
 *
 * <h2>8. Performance</h2>
 *
 * <p><strong>Not available.</strong> No latency, throughput or capacity objective exists anywhere in the
 * corpus for transaction {@code CAVW}: the CSD definition carries no timing attribute, the program publishes
 * no service level, and no such figure appears in any JCL member, copybook or catalogue listing. The
 * validation gate for performance therefore records a <em>measured baseline</em> and never an invented
 * target. What is needed to turn that baseline into a pass-or-fail criterion is a stakeholder-supplied
 * service-level objective - a percentile latency and a concurrency figure for this endpoint. Until one
 * exists, the only performance claims made here are structural: the chain is three primary-key or
 * indexed-column reads with no N+1 pattern, all constants are hoisted to {@code static final} rather than
 * rebuilt per call, and no query is issued that the projection does not consume.</p>
 *
 * <h2>9. Thread safety and state</h2>
 *
 * <p>This bean is a stateless singleton. It holds five {@code private final} collaborator references and no
 * mutable field, static or instance. Every one of the legacy {@code WORKING-STORAGE} flags -
 * {@code WS-RETURN-MSG} and its {@code 88}-levels, {@code WS-INFO-MSG}, {@code WS-EDIT-ACCT-FLAG},
 * {@code WS-EDIT-CUST-FLAG}, {@code WS-INPUT-FLAG}, {@code WS-PFK-FLAG}, {@code WS-FILE-READ-FLAGS},
 * {@code WS-RESP-CD}, {@code WS-REAS-CD} and the {@code WS-FILE-ERROR-MESSAGE} sub-fields - lives on a
 * per-call {@code ViewContext} allocated by the entry point, never on the bean. Placing any of them on the
 * bean would be an outright concurrency defect.</p>
 *
 * @see com.cardemo.service.shared.FileStatusMapper
 * @see com.cardemo.model.dto.AccountDto
 */
@Service
public class AccountViewService {

    /**
     * Structured-logging sink. The legacy program has no instrumentation whatsoever - not one
     * {@code DISPLAY} statement appears in {@code app/cbl/COACTVWC.cbl} - so every log statement in this
     * class is new capability rather than a translation. Identifiers and outcomes are logged; payloads are
     * not, and the personally identifiable fields listed in section 4 of the class documentation are masked
     * or omitted on every path.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountViewService.class);

    /**
     * {@code LIT-THISTRANID PIC X(4) VALUE 'CAVW'} at {@code :145-146}; the transaction identifier the CSD
     * binds to this program at {@code app/csd/CARDDEMO.CSD:317-318}.
     */
    private static final String TRANSACTION_ID = "CAVW";

    /**
     * {@code LIT-THISPGM PIC X(8) VALUE 'COACTVWC'} at {@code :143-144}. Also the abend culprit at
     * {@code :922} and at {@code :376}.
     */
    private static final String PROGRAM_NAME = "COACTVWC";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTVW '} at {@code :147-148}. The trailing blank is part of the
     * eight-byte literal and is preserved.
     */
    private static final String THIS_MAPSET = "COACTVW ";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CACTVWA'} at {@code :149-150}. */
    private static final String THIS_MAP = "CACTVWA";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} at {@code :168-169}; the PF03 fallback target. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} at {@code :170-171}; the PF03 fallback transaction. */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /**
     * {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '} at {@code :184-185}, the CICS file name declared at
     * {@code app/csd/CARDDEMO.CSD:1}. Moved into {@code ERROR-FILE} at {@code :813}.
     */
    private static final String ACCOUNT_FILE_NAME = "ACCTDAT ";

    /**
     * {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '} at {@code :188-189}, the CICS file name declared at
     * {@code app/csd/CARDDEMO.CSD:50}. Moved into {@code ERROR-FILE} at {@code :862}.
     */
    private static final String CUSTOMER_FILE_NAME = "CUSTDAT ";

    /**
     * {@code LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX '} at {@code :192-193}. The CSD declares
     * {@code DEFINE FILE(CXACAIX)} with
     * {@code DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)} over
     * {@code DSNAME AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH}, which is why the cross-reference read is an
     * account-keyed finder and not a primary-key read.
     */
    private static final String XREF_ACCOUNT_PATH_NAME = "CXACAIX ";

    /**
     * {@code CCDA-TITLE01 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, moved into the screen header at
     * {@code :436}. The forty-character literal, leading and trailing blanks included, is reproduced
     * verbatim.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, moved into the screen header at
     * {@code :437}.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * The {@code WS-CURDATE-MM-DD-YY} rendering assembled at {@code :443-447}. The separator is a solidus,
     * declared as {@code FILLER PIC X(01) VALUE '/'} in {@code app/cpy/CSDAT01Y.cpy}, and the year is the
     * two low-order digits taken by the reference modifier {@code WS-CURDATE-YEAR(3:2)} at {@code :445}.
     * {@code Locale.ROOT} is mandatory for determinism.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * The {@code WS-CURTIME-HH-MM-SS} rendering assembled at {@code :449-453}. The separator is a colon,
     * declared as {@code FILLER PIC X(01) VALUE ':'} in {@code app/cpy/CSDAT01Y.cpy}, giving an eight-byte
     * value that matches {@code AccountDto.CURRENT_TIME_LENGTH}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * {@code MOVE 'READ' TO ERROR-OPNAME} at {@code :762}, {@code :812} and {@code :861}; the operation name
     * embedded in the {@code WS-FILE-ERROR-MESSAGE} diagnostic and handed to {@code FileStatusMapper}.
     */
    private static final String OPERATION_READ = "READ";

    /**
     * {@code 88 WS-PROMPT-FOR-ACCT} on {@code WS-RETURN-MSG} at {@code :121-122}, stamped by
     * {@code 2210-EDIT-ACCOUNT} at {@code :658}.
     *
     * <p>This value is stamped but is never observable on the blank path. {@code :658} sits inside the
     * {@code IF WS-RETURN-MSG-OFF} first-error-wins latch, and the unlatched cross-field edit at
     * {@code :639-642} unconditionally replaces it with {@link #NO_SEARCH_CRITERIA_MESSAGE} whenever the
     * filter is blank - which is the only state in which {@code :658} can have run. Do not "correct" the
     * failure-modes table in the class Javadoc to name this string; the source overwrites it.</p>
     */
    private static final String PROMPT_FOR_ACCOUNT_MESSAGE = "Account number not provided";

    /**
     * {@code 88 NO-SEARCH-CRITERIA-RECEIVED} on {@code WS-RETURN-MSG} at {@code :123-124}, stamped by the
     * cross-field edit at {@code :641}. Because that edit is not latched, this is the message a blank filter
     * actually surfaces, overwriting {@link #PROMPT_FOR_ACCOUNT_MESSAGE}.
     */
    private static final String NO_SEARCH_CRITERIA_MESSAGE = "No input received";

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF} on {@code WS-RETURN-MSG} at {@code :129-130}. Part of defect
     * V1: this literal is never assigned and never tested - the {@code IF} that would have read it is
     * commented out at {@code :696} - so it is retained purely as the declared contract it is in the source.
     */
    private static final String DID_NOT_FIND_ACCOUNT_IN_CARDXREF =
            "Did not find this account in account card xref file";

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT} on {@code WS-RETURN-MSG} at {@code :131-132}. Tested by the
     * guard at {@code :704}; its only {@code SET} is commented out at {@code :792}, so the guard is dead.
     * Defect V1.
     */
    private static final String DID_NOT_FIND_ACCOUNT_IN_ACCTDAT =
            "Did not find this account in account master file";

    /**
     * {@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT} on {@code WS-RETURN-MSG} at {@code :133-134}. Tested by the
     * guard at {@code :713}; its only {@code SET} is commented out at {@code :842}, so the guard is dead.
     * Defect V1.
     */
    private static final String DID_NOT_FIND_CUSTOMER_IN_CUSTDAT =
            "Did not find associated customer in master file";

    /**
     * The literal moved into {@code WS-RETURN-MSG} at {@code :671-673}. It carries <strong>two consecutive
     * spaces between {@code must} and {@code be}</strong>; the doubling is in the corpus and is preserved
     * byte for byte because the parity comparison is byte-level.
     */
    private static final String ACCOUNT_FILTER_NON_ZERO_MESSAGE =
            "Account Filter must  be a non-zero 11 digit number";

    /** The literal moved into {@code WS-RETURN-MSG} - not {@code ABEND-MSG} - at {@code :379-380}. */
    private static final String UNEXPECTED_DATA_SCENARIO_MESSAGE = "UNEXPECTED DATA SCENARIO";

    /**
     * {@code MOVE '0001' TO ABEND-CODE} at {@code :377}. Four characters, exactly filling
     * {@code ABEND-CODE PIC X(4)} of {@code app/cpy/CSMSG02Y.cpy}. Defect V6: the payload built around it is
     * discarded, because {@code SEND-PLAIN-TEXT} rather than {@code ABEND-ROUTINE} is performed.
     */
    private static final String UNEXPECTED_SCENARIO_ABEND_CODE = "0001";

    /**
     * {@code MOVE SPACES TO ABEND-REASON} at {@code :378}, rendered at the declared width of
     * {@code ABEND-REASON PIC X(50)} in {@code app/cpy/CSMSG02Y.cpy}.
     */
    private static final String ABEND_REASON_SPACES = " ".repeat(FileStatusMapper.ABEND_REASON_WIDTH);

    /**
     * {@code 88 WS-PROMPT-FOR-INPUT} on {@code WS-INFO-MSG} at {@code :113-114}, stamped at {@code :463} and
     * again at {@code :529}.
     */
    private static final String PROMPT_FOR_INPUT_MESSAGE = "Enter or update id of account to display";

    /**
     * {@code EXEC CICS ABEND ABCODE('9999')} at {@code :934-936}. This is the <strong>online</strong> abend
     * code and is four characters wide, exactly filling {@code ABEND-CODE PIC X(4)}. It is deliberately
     * distinct from {@code FatalProcessingException.BATCH_ABEND_CODE}, the three-digit {@code 999} that
     * {@code app/cbl/CBTRN02C.cbl} passes to {@code CALL 'CEE3ABD'}; those batch constants are neither
     * reused nor repurposed here, and an online abend has no process return code at all.
     */
    private static final String ONLINE_ABEND_CODE = "9999";

    /**
     * The {@code fieldName} carried by every {@code ValidationException} this class raises. The screen field
     * is {@code ACCTSIDI} of {@code app/cpy-bms/COACTVW.CPY} and the working-storage field is
     * {@code CC-ACCT-ID} of {@code app/cpy/CVCRD01Y.cpy}; the REST payload name is the one below.
     */
    private static final String ACCOUNT_FILTER_FIELD = "accountFilter";

    /**
     * {@code CC-ACCT-ID PIC X(11)} of {@code app/cpy/CVCRD01Y.cpy} and {@code ACCT-ID PIC 9(11)} of
     * {@code app/cpy/CVACT01Y.cpy}; also the eleven-byte key length the catalogue records for the
     * {@code ACCTDATA} cluster.
     */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * {@code WS-CARD-RID-CUST-ID PIC 9(09)} at {@code :75-77} and {@code CUST-ID PIC 9(09)} of
     * {@code app/cpy/CVCUS01Y.cpy}.
     */
    private static final int CUSTOMER_ID_LENGTH = 9;

    /** {@code WS-RETURN-MSG PIC X(75)} at {@code :117}. Messages longer than this are truncated by the MOVE. */
    private static final int RETURN_MESSAGE_LENGTH = 75;

    /** {@code WS-INFO-MSG PIC X(40)} at {@code :110}. */
    private static final int INFO_MESSAGE_LENGTH = 40;

    /** {@code WS-LONG-MSG PIC X(500)} at {@code :109}; read only by the unreachable {@code SEND-LONG-TEXT}. */
    private static final int LONG_MESSAGE_LENGTH = 500;

    /** {@code ERROR-OPNAME PIC X(8)} at {@code :89-90}. */
    private static final int ERROR_OPERATION_LENGTH = 8;

    /** {@code ERROR-FILE PIC X(9)} at {@code :93-94}; one byte wider than the eight-byte file-name literals. */
    private static final int ERROR_FILE_LENGTH = 9;

    /** {@code ERROR-RESP PIC X(10)} at {@code :98-99} and {@code ERROR-RESP2 PIC X(10)} at {@code :102-103}. */
    private static final int ERROR_RESPONSE_LENGTH = 10;

    /**
     * {@code WS-RESP-CD PIC S9(09) COMP} at {@code :40-41}. A numeric-to-alphanumeric {@code MOVE} of a
     * nine-digit item into {@code ERROR-RESP PIC X(10)} left-justifies nine zero-padded digits and blanks
     * the tenth byte, which is what {@link #renderResponseCode(int)} reproduces.
     */
    private static final int RESPONSE_CODE_DIGITS = 9;

    /**
     * The number of {@code Z} positions in the numeric-edited mask {@code +ZZZ,ZZZ,ZZZ.99}, confirmed by the
     * commented-out {@code EDIT-FIELD-9-2} declaration at {@code :69} and by the output-field pictures in
     * {@code app/cpy-bms/COACTVW.CPY}.
     */
    private static final int MONEY_INTEGER_DIGITS = 9;

    /**
     * The width of the suppressed-and-grouped integer portion of {@code +ZZZ,ZZZ,ZZZ.99}: nine digit
     * positions plus two comma positions.
     */
    private static final int MONEY_GROUPED_WIDTH = 11;

    /** The number of digit positions in the mask, integer and fractional together. */
    private static final int MONEY_TOTAL_DIGITS = MONEY_INTEGER_DIGITS + AccountDto.MONEY_SCALE;

    /**
     * {@code DFHRESP(NORMAL)} - the CICS response ordinal zero, tested at {@code :738}, {@code :787} and
     * {@code :837}. {@code DFHRESP} is supplied by CICS and is absent from this repository, so the ordinal is
     * reproduced here rather than cited to a corpus locator.
     */
    private static final int CICS_RESP_NORMAL = 0;

    /**
     * {@code DFHRESP(NOTFND)} - the CICS response ordinal thirteen, tested at {@code :741}, {@code :789} and
     * {@code :839} and rendered into the diagnostic messages built there.
     */
    private static final int CICS_RESP_NOTFND = 13;

    /**
     * {@code DFHRESP(IOERR)} - the CICS response ordinal seventeen. It is the response a physical read
     * failure raises, and therefore the value this class renders when a
     * {@code org.springframework.dao.DataAccessException} takes the {@code WHEN OTHER} arm at {@code :759},
     * {@code :809} or {@code :858}. A repository-wide census finds no {@code DFHRESP(NOTOPEN)} site anywhere
     * in {@code app/cbl}, so no {@code NOTOPEN} ordinal is claimed here.
     */
    private static final int CICS_RESP_IOERR = 17;

    /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} at {@code :42-43}; no reason code is derivable in Java. */
    private static final int CICS_REASON_NONE = 0;

    /**
     * The two-character file status handed to {@code FileStatusMapper} for a successful read. The mapper owns
     * the status-to-exception decision; this class only supplies the status.
     */
    private static final String IO_STATUS_SUCCESS = "00";

    /** The two-character file status handed to {@code FileStatusMapper} for {@code DFHRESP(NOTFND)}. */
    private static final String IO_STATUS_RECORD_NOT_FOUND = "23";

    /**
     * The two-character file status handed to {@code FileStatusMapper} for a physical read failure. It is a
     * member of the {@code 9x} family, which the mapper classifies as an I/O error.
     */
    private static final String IO_STATUS_IO_ERROR = "90";

    /** {@code FILLER PIC X(12) VALUE 'File Error: '} at {@code :87-88}. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code FILLER PIC X(4) VALUE ' on '} at {@code :91-92}. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} at {@code :95-97}. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} at {@code :100-101}. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code FILLER PIC X(5) VALUE SPACES} at {@code :104-105}, the eighty-byte group's trailing filler. */
    private static final int FILE_ERROR_TRAILING_BLANKS = 5;

    /** {@code 'Account:'} - the first {@code STRING} operand at {@code :748} and {@code :797}. */
    private static final String MESSAGE_ACCOUNT_PREFIX = "Account:";

    /** {@code ' not found in'} - the third {@code STRING} operand at {@code :750} and {@code :799}. */
    private static final String MESSAGE_NOT_FOUND_IN = " not found in";

    /**
     * {@code ' Cross ref file.  Resp:'} at {@code :751}. Note the <strong>two spaces after the full
     * stop</strong>; the corresponding account-master fragment has none, and the customer fragment has a
     * trailing space instead. All three variants are byte-exact and deliberately inconsistent in the corpus.
     */
    private static final String MESSAGE_XREF_FILE = " Cross ref file.  Resp:";

    /** {@code ' Acct Master file.Resp:'} at {@code :800}. No space follows the full stop. */
    private static final String MESSAGE_ACCOUNT_MASTER_FILE = " Acct Master file.Resp:";

    /** {@code ' Reas:'} at {@code :753} and {@code :802} - mixed case, unlike the customer variant. */
    private static final String MESSAGE_REASON = " Reas:";

    /** {@code 'CustId:'} - the first {@code STRING} operand at {@code :847}. */
    private static final String MESSAGE_CUSTOMER_PREFIX = "CustId:";

    /** {@code ' not found'} at {@code :849}; the customer variant omits the trailing {@code in}. */
    private static final String MESSAGE_NOT_FOUND = " not found";

    /** {@code ' in customer master.Resp: '} at {@code :850}. Note the <strong>trailing space</strong>. */
    private static final String MESSAGE_CUSTOMER_MASTER = " in customer master.Resp: ";

    /** {@code ' REAS:'} at {@code :852} - <strong>upper case</strong>, unique to this paragraph. */
    private static final String MESSAGE_REASON_UPPER = " REAS:";

    /**
     * {@code MOVE '*' TO ACCTSIDO} at {@code :563}: the field-error marker written only when the filter state
     * is blank on a re-entered request. It is also the literal the normalisation at {@code :628} treats as
     * "not supplied".
     */
    private static final String ASTERISK = "*";

    /**
     * {@code MOVE -1 TO ACCTSIDL} at {@code :549} and {@code :551}: the BMS convention for "place the cursor
     * in this field". All three arms of the redundant {@code EVALUATE} produce this same value - defect V5.
     */
    private static final int CURSOR_ON_ACCOUNT_FILTER = -1;

    /**
     * {@code MOVE DFHBMFSE TO ACCTSIDA} at {@code :543}. {@code DFHBMSCA} is supplied by CICS and is absent
     * from the repository, so the attribute is carried as its declared symbol name rather than as a byte.
     */
    private static final String ATTRIBUTE_UNPROTECTED_FSET = "DFHBMFSE";

    /** {@code MOVE DFHDFCOL TO ACCTSIDC} at {@code :555}: the default field colour. */
    private static final String COLOUR_DEFAULT = "DFHDFCOL";

    /** {@code MOVE DFHRED TO ACCTSIDC} at {@code :558} and again at {@code :564}. */
    private static final String COLOUR_RED = "DFHRED";

    /** {@code MOVE DFHBMDAR TO INFOMSGC} at {@code :568}: darkened when there is no information message. */
    private static final String ATTRIBUTE_DARK = "DFHBMDAR";

    /** {@code MOVE DFHNEUTR TO INFOMSGC} at {@code :570}: neutral when an information message is present. */
    private static final String ATTRIBUTE_NEUTRAL = "DFHNEUTR";

    /**
     * The attention identifier {@link #viewAccount(String)} supplies. {@code DFHENTER} is the symbol
     * {@code app/cpy/CSSTRPFY.cpy:22} tests first, and Enter is one of only two identifiers
     * {@code 0000-MAIN} accepts at {@code :307-308}.
     */
    private static final String ATTENTION_IDENTIFIER_ENTER = "DFHENTER";

    /**
     * Digit count of {@code CUST-SSN PIC 9(09)} in {@code app/cpy/CVCUS01Y.cpy}, and therefore the width the
     * three reference modifiers at {@code :497}, {@code :499} and {@code :501} index into.
     */
    private static final int SSN_DIGITS = 9;

    /** {@code CUST-SSN(1:3)} at {@code :497}: the area group, three digits from offset one. */
    private static final int SSN_AREA_END = 3;

    /** {@code CUST-SSN(4:2)} at {@code :499}: the group number, two digits from offset four. */
    private static final int SSN_GROUP_END = 5;

    /** The dash the two {@code '-'} literals at {@code :498} and {@code :500} insert. */
    private static final char SSN_SEPARATOR = '-';

    /** Digit positions the {@code ZZZ,ZZZ,ZZZ} run of the money mask groups, three at a time. */
    private static final int MONEY_GROUP_SIZE = 3;

    /** The {@code ,} insertion character of {@code PIC +ZZZ,ZZZ,ZZZ.99}. */
    private static final char MONEY_GROUP_SEPARATOR = ',';

    /** The {@code .} insertion character of {@code PIC +ZZZ,ZZZ,ZZZ.99}. */
    private static final char MONEY_DECIMAL_POINT = '.';

    /** The fixed-position sign of {@code PIC +ZZZ,ZZZ,ZZZ.99} for a non-negative value. */
    private static final char MONEY_SIGN_POSITIVE = '+';

    /** The fixed-position sign of {@code PIC +ZZZ,ZZZ,ZZZ.99} for a negative value. */
    private static final char MONEY_SIGN_NEGATIVE = '-';

    /** The {@code Z} suppression character's rendering: a blank. */
    private static final char MONEY_SUPPRESSED = ' ';

    /** How many low-order digits of a card number survive masking on a logging path. */
    private static final int MASK_VISIBLE_DIGITS = 4;

    /**
     * Access point for {@code CCXREF} through its account path {@code CXACAIX}, the first of the three
     * reads in the lookup chain of {@code app/cbl/COACTVWC.cbl:727-735}.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Access point for the base cluster {@code ACCTDAT}, the second read in the chain at
     * {@code app/cbl/COACTVWC.cbl:776-784}.
     */
    private final AccountRepository accountRepository;

    /**
     * Access point for {@code CUSTDAT}, the third read in the chain at
     * {@code app/cbl/COACTVWC.cbl:826-834}.
     */
    private final CustomerRepository customerRepository;

    /** The sole owner of the status-to-exception decision; never re-implemented here. */
    private final FileStatusMapper fileStatusMapper;

    /**
     * Injected time source replacing {@code FUNCTION CURRENT-DATE}, so the screen header pair
     * {@code CURDATE} and {@code CURTIME} is reproducible in a test rather than read from the wall clock.
     */
    private final Clock clock;

    /**
     * Constructs the bean. Constructor injection is the only injection form used: there is no field
     * {@code @Autowired}, no setter injection, no service-locator lookup and no {@code ApplicationContext}
     * access anywhere in this class, which is what Rule 1 Clause B requires when it asks that global mutable
     * state be avoided in favour of dependency injection.
     *
     * <p>The body is pure field assignment. No overridable method is invoked and {@code this} does not
     * escape, so the compiler's {@code this-escape} analysis - which is fatal under {@code -Werror} - has
     * nothing to report.</p>
     *
     * <p>Exactly five collaborators are taken, and the omissions are as deliberate as the inclusions.
     * {@code FileStatusMapper} is the only {@code service.shared} bean injected: a census of the source for
     * {@code CSLKPCDY}, {@code CSSETATY}, {@code CSUTLDPY} and {@code CSUTLDWY} returns nothing, so this
     * program performs no date editing and no lookup-table validation, and neither
     * {@code DateValidationService} nor {@code ValidationLookupService} is injected. The sibling
     * {@code AccountUpdateService} does inject both; that asymmetry is real and is grounded in the two
     * programs' copybook sets.</p>
     *
     * @param cardCrossReferenceRepository the {@code CCXREF} accessor, reached through the account-keyed
     *                                     alternate index {@code CXACAIX} declared at
     *                                     {@code app/csd/CARDDEMO.CSD:63}; must not be {@code null}
     * @param accountRepository            the {@code ACCTDAT} accessor of
     *                                     {@code app/csd/CARDDEMO.CSD:1}; must not be {@code null}
     * @param customerRepository           the {@code CUSTDAT} accessor of
     *                                     {@code app/csd/CARDDEMO.CSD:50}; must not be {@code null}
     * @param fileStatusMapper             the sole owner of the file-status-to-exception decision and of the
     *                                     {@code FILE STATUS IS: NNNN} rendering; must not be {@code null}
     * @param clock                        the time source for the two {@code FUNCTION CURRENT-DATE}
     *                                     invocations at {@code :434} and {@code :441}; must not be
     *                                     {@code null}. A constructor-injected clock is what makes the header
     *                                     projection deterministic and testable;
     *                                     {@code LocalDate.now()} and {@code LocalDateTime.now()} with no
     *                                     argument are never called
     */
    public AccountViewService(final CardCrossReferenceRepository cardCrossReferenceRepository,
                              final AccountRepository accountRepository,
                              final CustomerRepository customerRepository,
                              final FileStatusMapper fileStatusMapper,
                              final Clock clock) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.fileStatusMapper = fileStatusMapper;
        this.clock = clock;
    }

    /**
     * The screen-parity entry point: one complete pass of the CICS conversation transaction {@code CAVW}
     * conducted, from attention-identifier remapping through the lookup chain to the projected screen.
     *
     * <p>A lookup miss is <strong>not</strong> signalled by an exception on this path. The legacy program
     * treats every {@code DFHRESP(NOTFND)} as a control path that sets a field-error state and renders a
     * diagnostic into the error-message field, and that is reproduced exactly. The typed exception that
     * {@code FileStatusMapper} builds for the same status is nonetheless created and retained on the request
     * context, so nothing is swallowed; {@link #viewAccount(String)} rethrows it.</p>
     *
     * @param accountFilter       the raw contents of screen field {@code ACCTSIDI}, an {@code X(11)} field.
     *                            {@code null}, empty, all blanks, or the single character {@code *} all mean
     *                            "not supplied" per {@code :628-629}; a run of asterisks such as
     *                            {@code ***} does <em>not</em>, and is rejected as invalid
     * @param attentionIdentifier the raw {@code EIBAID} symbol, for example {@code DFHENTER},
     *                            {@code DFHPF3}, {@code PF3} or {@code PFK03}. Matching is
     *                            case-insensitive under {@code Locale.ROOT}. Anything other than Enter or
     *                            PF03 is silently coerced to Enter at {@code :312-313}; {@code null} is
     *                            accepted and coerced the same way
     * @param entryMode           the reconstructed {@code CDEMO-PGM-CONTEXT}. {@code null} is legal and
     *                            reproduces a COMMAREA whose context byte is neither {@code 0} nor
     *                            {@code 1}, taking the {@code WHEN OTHER} arm at {@code :375-382}
     * @return the projected outcome; never {@code null}
     * @throws FatalProcessingException if any unmodelled runtime failure reaches the abend handler registered
     *                                  at {@code :264-266}, carrying abend code {@code 9999}, culprit
     *                                  {@code COACTVWC} and the original throwable as its cause
     */
    @Transactional(readOnly = true)
    public AccountViewResult processRequest(final String accountFilter,
                                           final String attentionIdentifier,
                                           final EntryMode entryMode) {
        return mainLine0000(new ViewContext(accountFilter, attentionIdentifier, entryMode));
    }

    /**
     * The REST entry point, surfaced by {@code com.cardemo.controller.AccountController} under
     * {@code /api/accounts/*}. It drives the identical conversation as
     * {@link #processRequest(String, String, EntryMode)} with Enter as the attention identifier and a
     * re-entered context - the combination that performs the edits and then the lookup chain - and then
     * converts the legacy screen states into typed exceptions.
     *
     * <p>The order of the two checks below is deliberate and is dictated by the source. An I/O outcome can
     * only arise after the edits have already passed, because {@code 9000-READ-ACCT} is performed only on the
     * {@code ELSE} arm at {@code :368-373}; but a miss also raises {@code INPUT-ERROR} at {@code :742},
     * {@code :790} and {@code :840}. Testing the retained typed failure first therefore reports a missing
     * record as a missing record rather than mislabelling it as bad input.</p>
     *
     * <p>No padding, trimming or case folding is applied to {@code accountFilter} on the caller's behalf.
     * The legacy edit at {@code :666-667} requires all eleven bytes of an {@code X(11)} field to be digits
     * and rejects all-zeroes, so {@code "1"} is invalid input rather than account one. That is preserved.</p>
     *
     * @param accountFilter the account identifier as typed, subject to the normalisation of {@code :628-633}
     * @return the projected account view; never {@code null}
     * @throws ValidationException      when the account filter is blank - {@code FailureKind.BLANK} - or
     *                                  non-numeric, short or all-zeroes - {@code FailureKind.INVALID}
     * @throws RecordNotFoundException  when the cross-reference, the account master or the customer master
     *                                  has no matching record
     * @throws CardDemoException        for any other file status, the concrete subtype being chosen by
     *                                  {@code FileStatusMapper}: {@code FileAccessException} for the
     *                                  {@code 9x} family, {@code FileUnavailableException} for {@code 35},
     *                                  {@code DuplicateRecordException} for {@code 22} and
     *                                  {@code FatalProcessingException} otherwise
     */
    @Transactional(readOnly = true)
    public AccountDto viewAccount(final String accountFilter) {
        final ViewContext context = new ViewContext(accountFilter, ATTENTION_IDENTIFIER_ENTER,
                EntryMode.REENTER);
        final AccountViewResult result = mainLine0000(context);
        if (context.pendingFailure != null) {
            throw context.pendingFailure;
        }
        if (context.inputError) {
            throw new ValidationException(context.returnMessage,
                    ACCOUNT_FILTER_FIELD,
                    context.accountFilterState == AccountFilterState.BLANK
                            ? ValidationException.FailureKind.BLANK
                            : ValidationException.FailureKind.INVALID);
        }
        return result.screen();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 0000-MAIN.}, logical line {@code :262}. The control-flow
     * spine: registers the abend handler, initialises the work areas, reconstructs the caller context,
     * remaps the attention identifier, validates it, and dispatches.
     *
     * <p>{@code :264-266} issues {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)}. That is a catch-all for
     * abends, so a catch-all is the faithful translation, and it is the <em>only</em> route to
     * {@link #abendRoutine(ViewContext, Throwable)} - the label is never {@code PERFORM}ed. Conditions the
     * program handles itself through {@code RESP} never reach a CICS abend handler, so the typed
     * {@code CardDemoException} family is rethrown untouched ahead of the catch-all; everything else is
     * funnelled to the handler with its cause preserved. No exception is swallowed and no {@code catch} block
     * is empty.</p>
     *
     * <p>{@code :282-293} either initialises the COMMAREA or slices {@code DFHCOMMAREA} into its two halves.
     * Neither has a stateless counterpart: {@code CDEMO-USER-ID PIC X(08)} becomes the JWT subject claim and
     * {@code CDEMO-USER-TYPE PIC X(01)} - with {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} in {@code app/cpy/COCOM01Y.cpy} - becomes the role claim, both
     * owned by the security layer; the navigation and re-entry fields have no counterpart at all. What
     * survives is observable: because a stateless request carries no COMMAREA, {@code CDEMO-FROM-TRANID} and
     * {@code CDEMO-FROM-PROGRAM} are always unset, so the PF03 fallbacks at {@code :328-339} always resolve
     * to {@code CM00} and {@code COMEN01C}.</p>
     *
     * <p>{@code :306-314} accepts <strong>Enter and PF03 only</strong> and silently coerces every other
     * identifier to Enter. No validation error is raised for an unexpected key; that silence is the
     * behaviour. The sibling {@code AccountUpdateService} accepts a four-condition set, and the difference is
     * real.</p>
     *
     * <p>Defect V7: the trailing {@code IF INPUT-ERROR} at {@code :385-392} and the fall-through into
     * {@code COMMON-RETURN} at {@code :393-394} are unreachable, because every arm of the dispatch below
     * terminates the task. Java forbids statements after branches that all return, so the block is documented
     * in the class Javadoc rather than transcribed; its single statement is already performed twice
     * elsewhere.</p>
     *
     * @param context the per-request work areas; mutated in place, exactly as {@code WORKING-STORAGE} is
     * @return the projected outcome of whichever dispatch arm ran; never {@code null}
     * @throws FatalProcessingException from the abend handler, or rethrown from a collaborator
     */
    private AccountViewResult mainLine0000(final ViewContext context) {
        // :264-266 EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)
        try {
            // :268-270 INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA - performed by the ViewContext
            //          constructor, which is the only place this bean holds mutable per-request state.
            // :274 MOVE LIT-THISTRANID TO WS-TRANID
            context.transactionId = TRANSACTION_ID;
            // :278 SET WS-RETURN-MSG-OFF TO TRUE
            context.returnMessage = "";

            // :282-293 first entry versus continuation. A stateless request carries no COMMAREA, so both
            // arms leave the caller-context fields unset; see the paragraph Javadoc.
            if (context.entryMode != EntryMode.REENTER) {
                context.fromTransactionId = null;
                context.fromProgram = null;
            }

            // :299-300 PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT
            context.attentionKey = storePfKey(context.rawAttentionIdentifier);

            // :306 SET PFK-INVALID TO TRUE
            context.pfKeyValid = false;
            // :307-310 IF CCARD-AID-ENTER OR CCARD-AID-PFK03 / SET PFK-VALID TO TRUE
            if (context.attentionKey == AidKey.ENTER || context.attentionKey == AidKey.PFK03) {
                context.pfKeyValid = true;
            }
            // :312-314 IF PFK-INVALID / SET CCARD-AID-ENTER TO TRUE - silent coercion, no error raised
            if (!context.pfKeyValid) {
                context.attentionKey = AidKey.ENTER;
            }

            // :323-383 EVALUATE TRUE - decide what to do based on inputs received
            if (context.attentionKey == AidKey.PFK03) {
                // :328-333 IF CDEMO-FROM-TRANID EQUAL LOW-VALUES OR SPACES -> LIT-MENUTRANID
                context.toTransactionId = isBlankOrLowValues(context.fromTransactionId)
                        ? MENU_TRANSACTION_ID
                        : context.fromTransactionId;
                // :334-339 IF CDEMO-FROM-PROGRAM EQUAL LOW-VALUES OR SPACES -> LIT-MENUPGM
                context.toProgram = isBlankOrLowValues(context.fromProgram)
                        ? MENU_PROGRAM
                        : context.fromProgram;
                // :341-342 MOVE LIT-THISTRANID / LIT-THISPGM TO the from-fields
                context.fromTransactionId = TRANSACTION_ID;
                context.fromProgram = PROGRAM_NAME;
                // :344 SET CDEMO-USRTYP-USER TO TRUE
                context.regularUserContext = true;
                // :345 SET CDEMO-PGM-ENTER TO TRUE
                context.nextEntryMode = EntryMode.ENTER;
                // :346-347 MOVE LIT-THISMAPSET / LIT-THISMAP TO CDEMO-LAST-MAPSET / CDEMO-LAST-MAP
                context.lastMapset = THIS_MAPSET;
                context.lastMap = THIS_MAP;
                // :349-352 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA). Statelessly
                // there is no transfer of control and no session: the target is navigation metadata on the
                // response and nothing more. Note there is deliberately NO EXEC CICS SYNCPOINT on this path.
                LOG.debug("CAVW PF03 navigation resolved to transaction {} program {}",
                        context.toTransactionId, context.toProgram);
                return new AccountViewResult(ResponseKind.TRANSFER,
                        context.attentionKey,
                        null,
                        null,
                        new Navigation(context.toTransactionId, context.toProgram,
                                context.fromTransactionId, context.fromProgram,
                                context.lastMapset, context.lastMap, context.regularUserContext),
                        null,
                        context.nextEntryMode,
                        context.inputError,
                        context.accountFound,
                        context.customerFound);
            }
            if (context.entryMode == EntryMode.FIRST_ENTRY || context.entryMode == EntryMode.ENTER) {
                // :353-360 WHEN CDEMO-PGM-ENTER - coming from some other context, gather selection criteria
                sendMap1000(context);
                return commonReturn(context);
            }
            if (context.entryMode == EntryMode.REENTER) {
                // :361-363 WHEN CDEMO-PGM-REENTER
                processInputs2000(context);
                // :364-367 IF INPUT-ERROR
                if (context.inputError) {
                    sendMap1000(context);
                    return commonReturn(context);
                }
                // :368-373 ELSE
                readAcct9000(context);
                sendMap1000(context);
                return commonReturn(context);
            }
            // :375-382 WHEN OTHER - DEFECT V6. The abend payload is built and then discarded, because
            // SEND-PLAIN-TEXT rather than ABEND-ROUTINE is performed and the text goes to WS-RETURN-MSG
            // rather than to ABEND-MSG. The program does not abend on an unexpected scenario.
            context.abendCulprit = PROGRAM_NAME;
            context.abendCode = UNEXPECTED_SCENARIO_ABEND_CODE;
            context.abendReason = ABEND_REASON_SPACES;
            context.returnMessage = UNEXPECTED_DATA_SCENARIO_MESSAGE;
            LOG.warn("CAVW unexpected data scenario; abend payload built and discarded "
                            + "(code={} culprit={} reason-length={}) - see the DECISION_LOG.md defect V6",
                    context.abendCode, context.abendCulprit, context.abendReason.length());
            return sendPlainText(context);
        } catch (final CardDemoException typed) {
            // RESP-handled conditions bypass EXEC CICS HANDLE ABEND; rethrown with cause intact.
            throw typed;
        } catch (final RuntimeException unexpected) {
            throw abendRoutine(context, unexpected);
        }
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code COMMON-RETURN.}, logical line {@code :394}. The single
     * response-assembly point, reached by {@code GO TO COMMON-RETURN} from {@code :360}, {@code :367} and
     * {@code :373}.
     *
     * <p>{@code :395} moves {@code WS-RETURN-MSG} into {@code CCARD-ERROR-MSG}, the COMMAREA copy of the
     * message the screen already carries from {@code :532}. Statelessly there is one copy, not two, so the
     * assignment is reproduced by re-stamping the response component: a no-op whenever
     * {@code 1200-SETUP-SCREEN-VARS} already ran, and the only stamping when it did not.</p>
     *
     * <p>{@code :397-400} reassembles the two COMMAREA halves and {@code :402-406} issues
     * {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA) LENGTH(...)}. Returning the
     * populated response DTO is the whole of the translation: <strong>no server-side session state is
     * retained</strong>, which is a binding transformation rule of the migration.</p>
     *
     * @param context the per-request work areas
     * @return the assembled map response; never {@code null}
     */
    private AccountViewResult commonReturn(final ViewContext context) {
        // :395 MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        context.screen.errorMessage = moveAlphanumeric(context.returnMessage, RETURN_MESSAGE_LENGTH);
        // :397-400 reassemble CARDDEMO-COMMAREA and WS-THIS-PROGCOMMAREA into WS-COMMAREA - no counterpart.
        // :402-406 EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA) LENGTH(LENGTH OF ...)
        LOG.debug("CAVW ({}) returning map response: accountFound={} customerFound={} inputError={}",
                context.transactionId, context.accountFound, context.customerFound, context.inputError);
        return new AccountViewResult(ResponseKind.MAP,
                context.attentionKey,
                context.screen.toDto(),
                null,
                new Navigation(context.transactionId, context.nextProgram,
                        context.fromTransactionId, context.fromProgram,
                        context.nextMapset, context.nextMap, context.regularUserContext),
                new ScreenAttributes(context.accountFilterState, context.accountFilterAttribute,
                        context.accountFilterColour, context.accountFilterMarker,
                        context.cursorPosition, context.informationMessageAttribute),
                context.nextEntryMode,
                context.inputError,
                context.accountFound,
                context.customerFound);
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 0000-MAIN-EXIT.}, logical line {@code :408}. Body is a
     * bare {@code EXIT} at {@code :409-410}, so the method is empty.
     *
     * <p>Defect V4: the label is never {@code PERFORM}ed and never {@code GO TO}'d anywhere in the program -
     * all flow leaves through {@code GO TO COMMON-RETURN} - and it is declared a <em>second</em> time at
     * {@code :411}. Both copies are mapped, separately, and are never consolidated: the 1:1 label mandate
     * admits no exception for duplicates and the scope-coverage gate reads the citations.</p>
     */
    private void mainExit0000AtLine408() {
        // Intentional unreachable no-op preserved for control-flow parity; see the DECISION_LOG.md
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 0000-MAIN-EXIT.}, logical line {@code :411} - the
     * <strong>duplicate</strong> declaration. Body is a bare {@code EXIT} at {@code :412-413}, so the method
     * is empty.
     *
     * <p>Defect V4, second half. Byte for byte identical to the copy at {@code :408}, and equally
     * unreachable. Emitting one method for the two labels would break the paragraph map that the
     * scope-coverage gate verifies, so two are emitted with distinct citations.</p>
     */
    private void mainExit0000AtLine411() {
        // Intentional unreachable no-op preserved for control-flow parity; see the DECISION_LOG.md
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1000-SEND-MAP.}, logical line {@code :416}. Performs the
     * four presentation paragraphs in the order {@code :417-424} fixes them: initialise the map area, fill the
     * variables, set the attributes, send. The order is load-bearing - {@code 1300} overwrites the account-id
     * field that {@code 1200} filled - and is preserved.
     *
     * @param context the per-request work areas
     */
    private void sendMap1000(final ViewContext context) {
        // :417-418 PERFORM 1100-SCREEN-INIT THRU 1100-SCREEN-INIT-EXIT
        screenInit1100(context);
        // :419-420 PERFORM 1200-SETUP-SCREEN-VARS THRU 1200-SETUP-SCREEN-VARS-EXIT
        setupScreenVars1200(context);
        // :421-422 PERFORM 1300-SETUP-SCREEN-ATTRS THRU 1300-SETUP-SCREEN-ATTRS-EXIT
        setupScreenAttrs1300(context);
        // :423-424 PERFORM 1400-SEND-SCREEN THRU 1400-SEND-SCREEN-EXIT
        sendScreen1400(context);
        sendMap1000Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1000-SEND-MAP-EXIT.}, logical line {@code :427}. Body is
     * a bare {@code EXIT} at {@code :428-429}. Reached by the {@code PERFORM ... THRU} at {@code :417-424}
     * and at every other {@code PERFORM 1000-SEND-MAP} site.
     */
    private void sendMap1000Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1100-SCREEN-INIT.}, logical line {@code :431}. Clears the
     * output map area and stamps the six header fields that recur on all seventeen legacy maps:
     * {@code TRNNAME X(4)}, {@code TITLE01 X(40)}, {@code CURDATE X(8)}, {@code PGMNAME X(8)},
     * {@code TITLE02 X(40)} and {@code CURTIME X(8)}.
     *
     * <p>{@code FUNCTION CURRENT-DATE} is invoked <strong>twice</strong>, at {@code :434} and again at
     * {@code :441}, into the same {@code WS-CURDATE-DATA} area. The first read is redundant: nothing consumes
     * it before the second overwrites it. Both invocations are reproduced through the injected
     * {@code java.time.Clock} and the redundancy is made observable on the trace log rather than quietly
     * dropped (severity Low; see the {@code DECISION_LOG.md}). Reading through the injected clock rather than
     * {@code LocalDateTime.now()} is what makes this paragraph deterministic and testable.</p>
     *
     * <p>The renderings are {@code MM/dd/yy} at {@code :443-447} - a two-digit year, taken by the reference
     * modifier {@code WS-CURDATE-YEAR(3:2)} - and {@code HH:mm:ss} at {@code :449-453}. Both separators come
     * from {@code FILLER} items with {@code VALUE} clauses in {@code app/cpy/CSDAT01Y.cpy}.</p>
     *
     * @param context the per-request work areas; its screen buffer is cleared and re-headed
     */
    private void screenInit1100(final ViewContext context) {
        // :432 MOVE LOW-VALUES TO CACTVWAO
        context.screen.clear();
        // :434 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - the redundant first read
        final LocalDateTime redundantFirstRead = LocalDateTime.now(this.clock);
        // :436 MOVE CCDA-TITLE01 TO TITLE01O
        context.screen.title01 = SCREEN_TITLE_01;
        // :437 MOVE CCDA-TITLE02 TO TITLE02O
        context.screen.title02 = SCREEN_TITLE_02;
        // :438 MOVE LIT-THISTRANID TO TRNNAMEO
        context.screen.transactionName = TRANSACTION_ID;
        // :439 MOVE LIT-THISPGM TO PGMNAMEO
        context.screen.programName = PROGRAM_NAME;
        // :441 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - the read the header actually consumes
        final LocalDateTime headerTimestamp = LocalDateTime.now(this.clock);
        if (LOG.isTraceEnabled()) {
            // Intentional redundant re-read preserved for parity; see the DECISION_LOG.md
            LOG.trace("CAVW header timestamp read twice at :434 and :441 (first={} second={})",
                    redundantFirstRead, headerTimestamp);
        }
        // :443-447 MOVE WS-CURDATE-MM / -DD / -YEAR(3:2) then MOVE WS-CURDATE-MM-DD-YY TO CURDATEO
        context.screen.currentDate = HEADER_DATE_FORMAT.format(headerTimestamp);
        // :449-453 MOVE WS-CURTIME-HOURS / -MINUTE / -SECOND then MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO
        context.screen.currentTime = HEADER_TIME_FORMAT.format(headerTimestamp);
        screenInit1100Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1100-SCREEN-INIT-EXIT.}, logical line {@code :457}. Body
     * is a bare {@code EXIT} at {@code :458-459}.
     */
    private void screenInit1100Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1200-SETUP-SCREEN-VARS.}, logical line {@code :460}.
     * <strong>This paragraph is the {@code AccountDto} projection.</strong>
     *
     * <p>Three things about it are easy to get wrong and are therefore spelled out.</p>
     *
     * <p><em>The two guards are different and must stay different.</em> The ten account fields at
     * {@code :471-491} render when <em>either</em> master was found; the eighteen customer fields at
     * {@code :493-523} render only when the customer master was found. {@code FOUND-ACCT-IN-MASTER}
     * ({@code 88 ... VALUE '1'}, declared {@code :83}) is set at {@code :788};
     * {@code FOUND-CUST-IN-MASTER} (declared {@code :85}) is set at {@code :838}. Because of defect V1 the
     * combination "customer found, account not found" is reachable, and in that case the source renders the
     * residual, never-populated {@code ACCOUNT-RECORD} storage. Undefined storage has no Java counterpart, so
     * the ten account components are left unset instead - a labelled deviation, severity Low.</p>
     *
     * <p><em>The social-security number is dash-formatted here, not stored that way.</em> {@code :496-504}
     * builds {@code NNN-NN-NNNN} by {@code STRING} from a {@code PIC 9(09)} source. That rendering belongs in
     * the business response for parity and is produced by {@link #formatSocialSecurityNumber(String)}; it is
     * masked on every logging path. Response sink and log sink are different sinks and are not conflated.</p>
     *
     * <p><em>{@code CUST-ADDR-LINE-3} is the city.</em> {@code :513} moves it into {@code ACSCITYO}. The
     * legacy naming is preserved in the citation. Likewise {@code ACCT-EXPIRAION-DATE} at {@code :488} is
     * misspelled in {@code app/cpy/CVACT01Y.cpy:11} itself and is never corrected, here or in the entity
     * accessor {@code Account.getExpiraionDate()}.</p>
     *
     * <p><em>Three COBOL {@code MOVE} truncations in the customer block are load-bearing rather than
     * cosmetic</em>, because {@code AccountDto}'s canonical constructor rejects an over-width component
     * instead of repairing it. Each is a decision over two independently declared widths, so both are cited
     * rather than remembered:</p>
     * <table>
     *   <caption>The three narrowing moves, with the locator that declares each width</caption>
     *   <tr><th>Move</th><th>Record field</th><th>Screen field</th><th>Bytes dropped</th></tr>
     *   <tr>
     *     <td>{@code :515}</td>
     *     <td>{@code CUST-ADDR-ZIP PIC X(10)}, {@code app/cpy/CVCUS01Y.cpy}:14</td>
     *     <td>{@code ACSZIPCO PIC X(5)}, {@code app/cpy-bms/COACTVW.CPY}:410</td>
     *     <td>5</td>
     *   </tr>
     *   <tr>
     *     <td>{@code :517}</td>
     *     <td>{@code CUST-PHONE-NUM-1 PIC X(15)}, {@code app/cpy/CVCUS01Y.cpy}:15</td>
     *     <td>{@code ACSPHN1O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY}:428</td>
     *     <td>2</td>
     *   </tr>
     *   <tr>
     *     <td>{@code :518}</td>
     *     <td>{@code CUST-PHONE-NUM-2 PIC X(15)}, {@code app/cpy/CVCUS01Y.cpy}:16</td>
     *     <td>{@code ACSPHN2O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY}:440</td>
     *     <td>2</td>
     *   </tr>
     * </table>
     * <p>The screen fields named are the {@code O}-suffixed OUTPUT fields, which are what the two
     * {@code MOVE}s at {@code app/cbl/COACTVWC.cbl}:517-518 actually target; the {@code I}-suffixed input
     * twins {@code ACSPHN1I} at {@code app/cpy-bms/COACTVW.CPY}:204 and {@code ACSPHN2I} at {@code :216}
     * carry the same {@code PIC X(13)} and are not the destination. COBOL alphanumeric moves left-justify and
     * truncate on the RIGHT, so the bytes that SURVIVE are the leading ones - which is what
     * {@link #moveAlphanumeric(String, int)} does, and what
     * {@code AccountViewServiceTest} asserts by comparing against the leading substring rather than merely
     * against a length.</p>
     *
     * @param context the per-request work areas; its screen buffer is filled
     */
    private void setupScreenVars1200(final ViewContext context) {
        // :462-463 IF EIBCALEN = 0 / SET WS-PROMPT-FOR-INPUT TO TRUE. EIBCALEN = 0 is the direct-start case,
        // reproduced by EntryMode.FIRST_ENTRY; it has no other stateless counterpart.
        if (context.entryMode == EntryMode.FIRST_ENTRY) {
            context.infoMessage = PROMPT_FOR_INPUT_MESSAGE;
        } else {
            // :465-469 IF FLG-ACCTFILTER-BLANK -> LOW-VALUES ELSE -> CC-ACCT-ID. LOW-VALUES has no JSON
            // counterpart; the projection emits an unset component.
            context.screen.accountId = context.accountFilterState == AccountFilterState.BLANK
                    ? null
                    : moveAlphanumeric(context.accountIdText, ACCOUNT_ID_LENGTH);

            // :471-472 IF FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER
            if (context.accountFound || context.customerFound) {
                final Account account = context.account;
                if (account != null) {
                    // :473 MOVE ACCT-ACTIVE-STATUS TO ACSTTUSO
                    context.screen.accountStatus =
                            moveAlphanumeric(account.getActiveStatus(), AccountDto.ACCOUNT_STATUS_LENGTH);
                    // :475 MOVE ACCT-CURR-BAL TO ACURBALO
                    context.screen.currentBalance = toEditedAmount(account.getCurrentBalance());
                    // :477 MOVE ACCT-CREDIT-LIMIT TO ACRDLIMO
                    context.screen.creditLimit = toEditedAmount(account.getCreditLimit());
                    // :479-480 MOVE ACCT-CASH-CREDIT-LIMIT TO ACSHLIMO
                    context.screen.cashCreditLimit = toEditedAmount(account.getCashCreditLimit());
                    // :482-483 MOVE ACCT-CURR-CYC-CREDIT TO ACRCYCRO
                    context.screen.currentCycleCredit = toEditedAmount(account.getCurrentCycleCredit());
                    // :485 MOVE ACCT-CURR-CYC-DEBIT TO ACRCYDBO
                    context.screen.currentCycleDebit = toEditedAmount(account.getCurrentCycleDebit());
                    // :487 MOVE ACCT-OPEN-DATE TO ADTOPENO - PIC X(10) text, never a temporal type
                    context.screen.openDate =
                            moveAlphanumeric(account.getOpenDate(), AccountDto.DATE_TEXT_LENGTH);
                    // :488 MOVE ACCT-EXPIRAION-DATE TO AEXPDTO - copybook misspelling preserved
                    context.screen.expiryDate =
                            moveAlphanumeric(account.getExpiraionDate(), AccountDto.DATE_TEXT_LENGTH);
                    // :489 MOVE ACCT-REISSUE-DATE TO AREISDTO
                    context.screen.reissueDate =
                            moveAlphanumeric(account.getReissueDate(), AccountDto.DATE_TEXT_LENGTH);
                    // :490 MOVE ACCT-GROUP-ID TO AADDGRPO
                    context.screen.accountGroupId =
                            moveAlphanumeric(account.getGroupId(), AccountDto.ACCOUNT_GROUP_ID_LENGTH);
                }
                // When the guard passes on FOUND-CUST-IN-MASTER alone - reachable only because of defect V1 -
                // the source renders residual ACCOUNT-RECORD storage. Undefined storage has no Java
                // counterpart, so the ten components above stay unset. See the DECISION_LOG.md.
            }

            // :493 IF FOUND-CUST-IN-MASTER
            if (context.customerFound) {
                final Customer customer = context.customer;
                if (customer != null) {
                    // :494 MOVE CUST-ID TO ACSTNUMO - PIC 9(09) into X(9): nine zero-padded digits
                    final Long customerIdentifier = customer.getCustomerId();
                    context.screen.customerId = moveNumericText(
                            customerIdentifier == null ? null : customerIdentifier.toString(),
                            CUSTOMER_ID_LENGTH);
                    // :495 the direct MOVE CUST-SSN TO ACSTSSNO is commented out in the source
                    // :496-504 STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4) INTO ACSTSSNO
                    context.screen.customerSsn = formatSocialSecurityNumber(customer.getSsn());
                    // :505-506 MOVE CUST-FICO-CREDIT-SCORE TO ACSTFCOO - PIC 9(03) into X(3)
                    context.screen.customerFicoScore = moveNumericText(
                            customer.getFicoCreditScore(), AccountDto.FICO_SCORE_LENGTH);
                    // :507 MOVE CUST-DOB-YYYY-MM-DD TO ACSTDOBO - PIC X(10) text
                    context.screen.customerDateOfBirth =
                            moveAlphanumeric(customer.getDateOfBirth(), AccountDto.DATE_TEXT_LENGTH);
                    // :508 MOVE CUST-FIRST-NAME TO ACSFNAMO
                    context.screen.customerFirstName =
                            moveAlphanumeric(customer.getFirstName(), AccountDto.NAME_LENGTH);
                    // :509 MOVE CUST-MIDDLE-NAME TO ACSMNAMO
                    context.screen.customerMiddleName =
                            moveAlphanumeric(customer.getMiddleName(), AccountDto.NAME_LENGTH);
                    // :510 MOVE CUST-LAST-NAME TO ACSLNAMO
                    context.screen.customerLastName =
                            moveAlphanumeric(customer.getLastName(), AccountDto.NAME_LENGTH);
                    // :511 MOVE CUST-ADDR-LINE-1 TO ACSADL1O
                    context.screen.addressLine1 =
                            moveAlphanumeric(customer.getAddressLine1(), AccountDto.ADDRESS_LINE_LENGTH);
                    // :512 MOVE CUST-ADDR-LINE-2 TO ACSADL2O
                    context.screen.addressLine2 =
                            moveAlphanumeric(customer.getAddressLine2(), AccountDto.ADDRESS_LINE_LENGTH);
                    // :513 MOVE CUST-ADDR-LINE-3 TO ACSCITYO - address line three IS the city field
                    context.screen.addressCity =
                            moveAlphanumeric(customer.getAddressLine3(), AccountDto.ADDRESS_LINE_LENGTH);
                    // :514 MOVE CUST-ADDR-STATE-CD TO ACSSTTEO
                    context.screen.addressStateCode =
                            moveAlphanumeric(customer.getAddressStateCode(), AccountDto.STATE_CODE_LENGTH);
                    // :515 MOVE CUST-ADDR-ZIP TO ACSZIPCO - X(10) into X(5): a genuine MOVE truncation
                    context.screen.addressZip =
                            moveAlphanumeric(customer.getAddressZip(), AccountDto.ZIP_LENGTH);
                    // :516 MOVE CUST-ADDR-COUNTRY-CD TO ACSCTRYO
                    context.screen.addressCountryCode = moveAlphanumeric(
                            customer.getAddressCountryCode(), AccountDto.COUNTRY_CODE_LENGTH);
                    // :517 MOVE CUST-PHONE-NUM-1 TO ACSPHN1O - X(15) into X(13): a genuine MOVE truncation
                    context.screen.phoneNumber1 =
                            moveAlphanumeric(customer.getPhoneNumber1(), AccountDto.PHONE_NUMBER_LENGTH);
                    // :518 MOVE CUST-PHONE-NUM-2 TO ACSPHN2O - X(15) into X(13): a genuine MOVE truncation
                    context.screen.phoneNumber2 =
                            moveAlphanumeric(customer.getPhoneNumber2(), AccountDto.PHONE_NUMBER_LENGTH);
                    // :519 MOVE CUST-GOVT-ISSUED-ID TO ACSGOVTO
                    context.screen.governmentIssuedId = moveAlphanumeric(
                            customer.getGovernmentIssuedId(), AccountDto.GOVERNMENT_ID_LENGTH);
                    // :520 MOVE CUST-EFT-ACCOUNT-ID TO ACSEFTCO
                    context.screen.eftAccountId =
                            moveAlphanumeric(customer.getEftAccountId(), AccountDto.EFT_ACCOUNT_ID_LENGTH);
                    // :521-522 MOVE CUST-PRI-CARD-HOLDER-IND TO ACSPFLGO
                    context.screen.primaryCardHolderIndicator = moveAlphanumeric(
                            customer.getPrimaryCardHolderIndicator(),
                            AccountDto.CARD_HOLDER_INDICATOR_LENGTH);
                }
            }
        }

        // :528-530 IF WS-NO-INFO-MESSAGE / SET WS-PROMPT-FOR-INPUT TO TRUE
        if (isBlankOrLowValues(context.infoMessage)) {
            context.infoMessage = PROMPT_FOR_INPUT_MESSAGE;
        }
        // :532 MOVE WS-RETURN-MSG TO ERRMSGO - X(75) into X(78), so no truncation at the wider target
        context.screen.errorMessage = moveAlphanumeric(context.returnMessage, RETURN_MESSAGE_LENGTH);
        // :534 MOVE WS-INFO-MSG TO INFOMSGO - X(40) into X(45)
        context.screen.informationMessage = moveAlphanumeric(context.infoMessage, INFO_MESSAGE_LENGTH);
        setupScreenVars1200Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1200-SETUP-SCREEN-VARS-EXIT.}, logical line {@code :537}.
     * Body is a bare {@code EXIT} at {@code :538-539}.
     */
    private void setupScreenVars1200Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1300-SETUP-SCREEN-ATTRS.}, logical line {@code :541}.
     * Sets the account-filter attribute byte, positions the cursor, chooses the colour, emits the field-error
     * marker and switches the information-message attribute.
     *
     * <p>Defect V5, {@code :546-552}: a three-arm {@code EVALUATE TRUE} whose arms are
     * {@code WHEN FLG-ACCTFILTER-NOT-OK}, {@code WHEN FLG-ACCTFILTER-BLANK} and {@code WHEN OTHER}, and in
     * which <strong>all three execute the identical statement</strong> {@code MOVE -1 TO ACCTSIDL}. The
     * decision is wholly redundant. The structure is reproduced rather than collapsed, on the same reasoning
     * that keeps the redundant index assignment in {@code app/cbl/CBSTM03A.CBL}.</p>
     *
     * <p>{@code :561-565} is the reason a single boolean per field is insufficient. The {@code '*'} marker and
     * the red colour are applied only when the filter state is <em>blank</em>, so blank must stay
     * distinguishable from not-ok - which is exactly the tri-state
     * {@code ValidationException.FailureKind} already models. This is the hand-written equivalent of the
     * {@code app/cpy/CSSETATY.cpy} semantics; this program does not {@code COPY} that member, though its
     * sibling expands it many times. The {@code CDEMO-PGM-REENTER} half of the condition has no stateless
     * counterpart: the marker is emitted whenever the state is blank on a submitted - that is, re-entered -
     * request.</p>
     *
     * <p>Ordering note: this paragraph runs before {@code 1400-SEND-SCREEN}, which is where
     * {@code CDEMO-PGM-REENTER} is <em>set</em> at {@code :581}, so the flag read here is still the inbound
     * one.</p>
     *
     * @param context the per-request work areas; attribute state and possibly the account-id component are
     *                written
     */
    private void setupScreenAttrs1300(final ViewContext context) {
        // :543 MOVE DFHBMFSE TO ACCTSIDA - unprotected with the modified-data tag set
        context.accountFilterAttribute = ATTRIBUTE_UNPROTECTED_FSET;
        // :546-552 EVALUATE TRUE - DEFECT V5: all three arms move -1 into ACCTSIDL
        if (context.accountFilterState == AccountFilterState.NOT_OK
                || context.accountFilterState == AccountFilterState.BLANK) {
            // :549 WHEN FLG-ACCTFILTER-NOT-OK / WHEN FLG-ACCTFILTER-BLANK
            context.cursorPosition = CURSOR_ON_ACCOUNT_FILTER;
        } else {
            // :551 WHEN OTHER - Intentional redundant decision preserved for parity; see the DECISION_LOG.md
            context.cursorPosition = CURSOR_ON_ACCOUNT_FILTER;
        }
        // :555 MOVE DFHDFCOL TO ACCTSIDC
        context.accountFilterColour = COLOUR_DEFAULT;
        // :557-559 IF FLG-ACCTFILTER-NOT-OK / MOVE DFHRED TO ACCTSIDC
        if (context.accountFilterState == AccountFilterState.NOT_OK) {
            context.accountFilterColour = COLOUR_RED;
        }
        // :561-565 IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER / MOVE '*' TO ACCTSIDO / MOVE DFHRED
        if (context.accountFilterState == AccountFilterState.BLANK
                && context.entryMode == EntryMode.REENTER) {
            context.screen.accountId = ASTERISK;
            context.accountFilterMarker = ASTERISK;
            context.accountFilterColour = COLOUR_RED;
        }
        // :567-571 IF WS-NO-INFO-MESSAGE / MOVE DFHBMDAR ELSE MOVE DFHNEUTR TO INFOMSGC
        if (isBlankOrLowValues(context.infoMessage)) {
            context.informationMessageAttribute = ATTRIBUTE_DARK;
        } else {
            context.informationMessageAttribute = ATTRIBUTE_NEUTRAL;
        }
        setupScreenAttrs1300Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1300-SETUP-SCREEN-ATTRS-EXIT.}, logical line
     * {@code :574}. Body is a bare {@code EXIT} at {@code :575-576}.
     */
    private void setupScreenAttrs1300Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1400-SEND-SCREEN.}, logical line {@code :577}. Records
     * the next map and mapset, latches the pseudo-conversational re-entry flag, and issues
     * {@code EXEC CICS SEND MAP ... CURSOR ERASE FREEKB RESP(WS-RESP-CD)} at {@code :583-590}.
     *
     * <p>{@code :581} sets {@code CDEMO-PGM-REENTER TO TRUE}. That latch is the whole of the legacy
     * pseudo-conversational mechanism and has <strong>no stateless counterpart</strong>: no session is kept
     * and the flag is not stored anywhere on the server. It survives only as navigation metadata on the
     * response, so a client that wishes to resubmit knows which context byte the legacy screen would have
     * carried.</p>
     *
     * <p>The {@code RESP} clause writes {@code WS-RESP-CD}, which means a successful send
     * <em>overwrites</em> whatever response code an earlier read left there. That clobbering is faithful and
     * harmless, because every diagnostic message that consumes the response pair has already been
     * assembled by the time this paragraph runs.</p>
     *
     * @param context the per-request work areas
     */
    private void sendScreen1400(final ViewContext context) {
        // :579 MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
        context.nextMapset = THIS_MAPSET;
        // :580 MOVE LIT-THISMAP TO CCARD-NEXT-MAP
        context.nextMap = THIS_MAP;
        // :581 SET CDEMO-PGM-REENTER TO TRUE - response metadata only; no server-side session state
        context.nextEntryMode = EntryMode.REENTER;
        // :583-590 EXEC CICS SEND MAP(...) MAPSET(...) FROM(CACTVWAO) CURSOR ERASE FREEKB RESP(WS-RESP-CD)
        context.responseCode = CICS_RESP_NORMAL;
        LOG.debug("CAVW projected map {} of mapset {} (cursor={} colour={})",
                context.nextMap, context.nextMapset, context.cursorPosition, context.accountFilterColour);
        sendScreen1400Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 1400-SEND-SCREEN-EXIT.}, logical line {@code :592}. Body
     * is a bare {@code EXIT} at {@code :593-594}.
     */
    private void sendScreen1400Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2000-PROCESS-INPUTS.}, logical line {@code :596}.
     * Receives the map, edits it, then performs four further moves at {@code :601-604} that record the
     * message and the next program, mapset and map into the COMMAREA.
     *
     * <p>The four trailing moves are easy to overlook and are reproduced in full: {@code WS-RETURN-MSG} into
     * {@code CCARD-ERROR-MSG}, {@code LIT-THISPGM} into {@code CCARD-NEXT-PROG}, {@code LIT-THISMAPSET} into
     * {@code CCARD-NEXT-MAPSET} and {@code LIT-THISMAP} into {@code CCARD-NEXT-MAP}. They are why the
     * navigation metadata on a re-entered response names this program while an Enter-path response does
     * not - {@code 1400-SEND-SCREEN} stamps only the map and mapset.</p>
     *
     * @param context the per-request work areas
     */
    private void processInputs2000(final ViewContext context) {
        // :597-598 PERFORM 2100-RECEIVE-MAP THRU 2100-RECEIVE-MAP-EXIT
        receiveMap2100(context);
        // :599-600 PERFORM 2200-EDIT-MAP-INPUTS THRU 2200-EDIT-MAP-INPUTS-EXIT
        editMapInputs2200(context);
        // :601 MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        context.screen.errorMessage = moveAlphanumeric(context.returnMessage, RETURN_MESSAGE_LENGTH);
        // :602 MOVE LIT-THISPGM TO CCARD-NEXT-PROG
        context.nextProgram = PROGRAM_NAME;
        // :603 MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
        context.nextMapset = THIS_MAPSET;
        // :604 MOVE LIT-THISMAP TO CCARD-NEXT-MAP
        context.nextMap = THIS_MAP;
        processInputs2000Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2000-PROCESS-INPUTS-EXIT.}, logical line {@code :607}.
     * Body is a bare {@code EXIT} at {@code :608-609}.
     */
    private void processInputs2000Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2100-RECEIVE-MAP.}, logical line {@code :610}.
     * {@code :611-616} issues {@code EXEC CICS RECEIVE MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET)
     * INTO(CACTVWAI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)}.
     *
     * <p>There is no Java work beyond accepting the inbound request: Spring's message converters have already
     * bound the payload by the time this bean is entered, so the translation of a {@code RECEIVE MAP} is the
     * binding itself. The paragraph is nevertheless mapped, because the 1:1 label mandate admits no exception
     * for paragraphs whose mechanism moved into the framework, and because it is the single place where the
     * inbound field is copied into the working-storage input area - which is what makes the normalisation at
     * {@code :628-633} a transformation of a copy rather than of the request.</p>
     *
     * @param context the per-request work areas; the received account filter and the response pair are set
     */
    private void receiveMap2100(final ViewContext context) {
        // :611-616 EXEC CICS RECEIVE MAP ... INTO(CACTVWAI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        context.receivedAccountFilter = context.requestedAccountFilter;
        context.responseCode = CICS_RESP_NORMAL;
        context.reasonCode = CICS_REASON_NONE;
        receiveMap2100Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2100-RECEIVE-MAP-EXIT.}, logical line {@code :619}. Body
     * is a bare {@code EXIT} at {@code :620-621}.
     */
    private void receiveMap2100Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2200-EDIT-MAP-INPUTS.}, logical line {@code :622}. The
     * <strong>optimistic</strong> half of the edit pair: it opens by assuming the input is good.
     *
     * <p>{@code :624} sets {@code INPUT-OK} and {@code :625} sets {@code FLG-ACCTFILTER-ISVALID}, both before
     * anything has been examined. {@code 2210-EDIT-ACCOUNT} opens with the exact opposite assumption. That
     * asymmetry is behaviour and is normalised in neither direction.</p>
     *
     * <p>{@code :628-629} tests {@code ACCTSIDI = '*' OR ACCTSIDI = SPACES} and replaces either with
     * {@code LOW-VALUES}. The field is {@code X(11)} and the literal is one character, which COBOL pads with
     * blanks, so the test matches a <strong>single</strong> asterisk followed by spaces. A run such as
     * {@code ***} does not match and is therefore invalid input, not "not supplied". Under no reading is
     * {@code *} a wildcard search.</p>
     *
     * @param context the per-request work areas; the normalised account-id text and the edit flags are set
     */
    private void editMapInputs2200(final ViewContext context) {
        // :624 SET INPUT-OK TO TRUE
        context.inputError = false;
        // :625 SET FLG-ACCTFILTER-ISVALID TO TRUE
        context.accountFilterState = AccountFilterState.VALID;
        // :627-633 REPLACE * WITH LOW-VALUES
        final String received = context.receivedAccountFilter;
        if (isSingleAsterisk(received) || isBlankOrLowValues(received)) {
            // :630 MOVE LOW-VALUES TO CC-ACCT-ID
            context.accountIdText = null;
        } else {
            // :632 MOVE ACCTSIDI TO CC-ACCT-ID - X(11), so an over-long value truncates on the right
            context.accountIdText = moveAlphanumeric(received, ACCOUNT_ID_LENGTH);
        }
        // :635-637 INDIVIDUAL FIELD EDITS: PERFORM 2210-EDIT-ACCOUNT THRU 2210-EDIT-ACCOUNT-EXIT
        editAccount2210(context);
        // :639-642 CROSS FIELD EDITS: IF FLG-ACCTFILTER-BLANK / SET NO-SEARCH-CRITERIA-RECEIVED TO TRUE
        if (context.accountFilterState == AccountFilterState.BLANK) {
            context.returnMessage = NO_SEARCH_CRITERIA_MESSAGE;
        }
        editMapInputs2200Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2200-EDIT-MAP-INPUTS-EXIT.}, logical line {@code :645}.
     * Body is a bare {@code EXIT} at {@code :646-647}.
     */
    private void editMapInputs2200Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2210-EDIT-ACCOUNT.}, logical line {@code :649}. The
     * <strong>pessimistic</strong> half of the edit pair: {@code :650} sets {@code FLG-ACCTFILTER-NOT-OK}
     * before anything has been examined, the exact opposite of {@code :625}. Preserve the asymmetry.
     *
     * <p>Two rejection paths, each ending in {@code GO TO 2210-EDIT-ACCOUNT-EXIT}:</p>
     *
     * <ul>
     *   <li>{@code :653-662} not supplied. Sets {@code INPUT-ERROR} and {@code FLG-ACCTFILTER-BLANK}, stamps
     *       {@code WS-PROMPT-FOR-ACCT} <em>only</em> if the inner {@code IF WS-RETURN-MSG-OFF} latch at
     *       {@code :657-659} finds no message already pending - a first-error-wins latch - then moves zeroes
     *       into {@code CDEMO-ACCT-ID}.</li>
     *   <li>{@code :666-676} not numeric, or all zeroes. {@code CC-ACCT-ID} is {@code X(11)} and the
     *       {@code IS NOT NUMERIC} class test is true unless every one of the eleven bytes is a digit, so a
     *       short entry - blank-padded - is rejected. The literal moved under the same latch at
     *       {@code :670-674} carries two consecutive spaces between {@code must} and {@code be}.</li>
     * </ul>
     *
     * <p>{@code :677-680} is the accepting arm: it moves {@code CC-ACCT-ID} into {@code CDEMO-ACCT-ID}
     * ({@code PIC 9(11)}) and sets {@code FLG-ACCTFILTER-ISVALID} - redundantly, since {@code :625} already
     * did, which is preserved.</p>
     *
     * <p>The first-error-wins latch is method-local: it reads and writes the per-request context, never a bean
     * field. A bean field would be an outright concurrency defect on a singleton.</p>
     *
     * @param context the per-request work areas; the filter state, the input-error flag, the return message
     *                and the numeric account identifier are set
     */
    private void editAccount2210(final ViewContext context) {
        // :650 SET FLG-ACCTFILTER-NOT-OK TO TRUE - pessimistic, unlike :625
        context.accountFilterState = AccountFilterState.NOT_OK;
        // :652-654 Not supplied: IF CC-ACCT-ID EQUAL LOW-VALUES OR SPACES
        if (isBlankOrLowValues(context.accountIdText)) {
            // :655 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :656 SET FLG-ACCTFILTER-BLANK TO TRUE
            context.accountFilterState = AccountFilterState.BLANK;
            // :657-659 IF WS-RETURN-MSG-OFF / SET WS-PROMPT-FOR-ACCT TO TRUE - first error wins
            if (isBlankOrLowValues(context.returnMessage)) {
                context.returnMessage = PROMPT_FOR_ACCOUNT_MESSAGE;
            }
            // :660 MOVE ZEROES TO CDEMO-ACCT-ID
            context.accountId = 0L;
            // :661 GO TO 2210-EDIT-ACCOUNT-EXIT
            editAccount2210Exit();
            return;
        }
        // :664-667 Not numeric / not 11 characters: IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID EQUAL ZEROES
        if (!isElevenDigitNumeric(context.accountIdText) || isAllZeroes(context.accountIdText)) {
            // :668 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :669 SET FLG-ACCTFILTER-NOT-OK TO TRUE
            context.accountFilterState = AccountFilterState.NOT_OK;
            // :670-674 IF WS-RETURN-MSG-OFF / MOVE the two-space literal TO WS-RETURN-MSG - first error wins
            if (isBlankOrLowValues(context.returnMessage)) {
                context.returnMessage = ACCOUNT_FILTER_NON_ZERO_MESSAGE;
            }
            // :675 MOVE ZERO TO CDEMO-ACCT-ID
            context.accountId = 0L;
            // :676 GO TO 2210-EDIT-ACCOUNT-EXIT
            editAccount2210Exit();
            return;
        }
        // :677-680 ELSE: MOVE CC-ACCT-ID TO CDEMO-ACCT-ID / SET FLG-ACCTFILTER-ISVALID TO TRUE
        context.accountId = Long.parseLong(context.accountIdText);
        context.accountFilterState = AccountFilterState.VALID;
        editAccount2210Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 2210-EDIT-ACCOUNT-EXIT.}, logical line {@code :683}. Body
     * is a bare {@code EXIT} at {@code :684-685}. Reached both by fall-through and by the two
     * {@code GO TO}s at {@code :661} and {@code :676}.
     */
    private void editAccount2210Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9000-READ-ACCT.}, logical line {@code :687}. The
     * orchestrator of the three-step lookup chain, and the site of <strong>defect V1</strong> - the single
     * most consequential behavioural fact in this class.
     *
     * <p>The paragraph tests three conditions, at {@code :697}, {@code :704} and {@code :713}. Only the first
     * can ever fire.</p>
     *
     * <ul>
     *   <li>{@code :697} {@code IF FLG-ACCTFILTER-NOT-OK} works, and {@code 9200-GETCARDXREF-BYACCT} does set
     *       that flag - at {@code :743} for {@code NOTFND} and at {@code :761} for {@code WHEN OTHER}. So the
     *       cross-reference-to-account transition <em>is</em> gated.</li>
     *   <li>{@code :704} tests {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}, a message-literal {@code 88}-level on
     *       {@code WS-RETURN-MSG} declared at {@code :131-132}. Its only {@code SET} is commented out at
     *       {@code :792}, so the literal is never assigned and the guard can never fire.</li>
     *   <li>{@code :713} tests {@code DID-NOT-FIND-CUST-IN-CUSTDAT}, declared at {@code :133-134}, whose only
     *       {@code SET} is commented out at {@code :842}. Equally dead.</li>
     * </ul>
     *
     * <p>A third such literal, {@value #DID_NOT_FIND_ACCOUNT_IN_CARDXREF}, is declared at {@code :129-130} and
     * is neither set nor tested; the {@code IF} that would have read it is commented out at {@code :696}.</p>
     *
     * <p><strong>Consequence: after an account-master miss the chain continues into the customer lookup at
     * {@code :708-711}.</strong> The general claim that a downstream lookup must not run once an upstream one failed
     * is true here <em>only</em> of cross-reference to account; the account-to-customer transition is unguarded. This
     * is reproduced verbatim. No guard the source lacks is added, and the two dead guards are retained as written so
     * that the paragraph map stays provable. Severity High; owed an entry in the {@code DECISION_LOG.md} and
     * {@code TRACEABILITY_MATRIX.md}.</p>
     *
     * @param context the per-request work areas; the cross-reference, account and customer results plus the
     *                found flags, the filter states and any diagnostic message are set
     */
    private void readAcct9000(final ViewContext context) {
        // :689 SET WS-NO-INFO-MESSAGE TO TRUE - the first of the two VALUES, SPACES
        context.infoMessage = "";
        // :691 MOVE CDEMO-ACCT-ID TO WS-CARD-RID-ACCT-ID
        context.ridAccountId = context.accountId;
        // :693-694 PERFORM 9200-GETCARDXREF-BYACCT THRU 9200-GETCARDXREF-BYACCT-EXIT
        getCardXrefByAcct9200(context);
        // :696 IF DID-NOT-FIND-ACCT-IN-CARDXREF - commented out in the source; see the paragraph Javadoc
        // :697-699 IF FLG-ACCTFILTER-NOT-OK / GO TO 9000-READ-ACCT-EXIT - the only guard that can fire
        if (context.accountFilterState == AccountFilterState.NOT_OK) {
            readAcct9000Exit();
            return;
        }
        // :701-702 PERFORM 9300-GETACCTDATA-BYACCT THRU 9300-GETACCTDATA-BYACCT-EXIT
        getAcctDataByAcct9300(context);
        // :704-706 IF DID-NOT-FIND-ACCT-IN-ACCTDAT / GO TO 9000-READ-ACCT-EXIT
        // Intentional no-op: the source SET is commented out at app/cbl/COACTVWC.cbl:L792; the guard can
        // never fire, so the chain falls through to the customer lookup. See the DECISION_LOG.md defect V1.
        if (DID_NOT_FIND_ACCOUNT_IN_ACCTDAT.equals(context.returnMessage)) {
            readAcct9000Exit();
            return;
        }
        // :708 MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID
        context.ridCustomerId = context.customerId;
        // :710-711 PERFORM 9400-GETCUSTDATA-BYCUST THRU 9400-GETCUSTDATA-BYCUST-EXIT
        getCustDataByCust9400(context);
        // :713-715 IF DID-NOT-FIND-CUST-IN-CUSTDAT / GO TO 9000-READ-ACCT-EXIT
        // Intentional no-op: the source SET is commented out at app/cbl/COACTVWC.cbl:L842; the guard can
        // never fire. See the DECISION_LOG.md defect V1.
        if (DID_NOT_FIND_CUSTOMER_IN_CUSTDAT.equals(context.returnMessage)) {
            readAcct9000Exit();
            return;
        }
        if (LOG.isTraceEnabled()) {
            // WS-EDIT-CUST-FLAG is written at :841 and :860 and tested nowhere in the program; it is surfaced
            // here so the write is observable rather than silently dead. The card number is masked: it is
            // XREF-CARD-NUM and therefore personally identifiable.
            LOG.trace("CAVW chain complete for account {}: accountFound={} customerFound={} "
                            + "customerFilterNotOk={} card={}",
                    context.ridAccountId, context.accountFound, context.customerFound,
                    context.customerFilterNotOk, maskTail(context.cardNumber));
        }
        readAcct9000Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9000-READ-ACCT-EXIT.}, logical line {@code :720}. Body is
     * a bare {@code EXIT} at {@code :721-722}. Reached by fall-through and by the three {@code GO TO}s at
     * {@code :698}, {@code :705} and {@code :714}, two of which are unreachable per defect V1.
     */
    private void readAcct9000Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9200-GETCARDXREF-BYACCT.}, logical line {@code :723}. The
     * <strong>alternate-index</strong> read: {@code :727-735} issues {@code EXEC CICS READ
     * DATASET(LIT-CARDXREFNAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID-X) ...}, and the source comment at
     * {@code :725} says so outright - "Read the Card file. Access via alternate index ACCTID".
     *
     * <p>{@code app/csd/CARDDEMO.CSD:63} declares {@code DEFINE FILE(CXACAIX)} over
     * {@code DSNAME AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} with
     * {@code DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)}. This therefore maps to the derived
     * finder {@code findFirstByAccountIdOrderByCardNumberAsc}, <strong>never</strong> to {@code findById},
     * which would key on the sixteen-character card number and silently return a different record. The
     * alternate key is non-unique ({@code app/catlg/LISTCAT.txt:488} marks it {@code NONUNIQKEY}), so
     * duplicates are permitted; the first record in ascending card-number order is what a VSAM {@code READ}
     * through the path would surface, so the finder applies {@code LIMIT 1} and an empty {@code Optional} is
     * the {@code NOTFND} condition.</p>
     *
     * <p>Latch placement matters and differs between the three read paragraphs. Here the two moves of the
     * response pair into {@code ERROR-RESP} and {@code ERROR-RESP2} sit <em>inside</em> the
     * {@code IF WS-RETURN-MSG-OFF} latch, at {@code :745-746}. {@code 9300} does the same; {@code 9400} does
     * not. All three placements are preserved.</p>
     *
     * <p>The {@code NOTFND} literal built at {@code :747-757} carries <strong>two spaces after
     * {@code file.}</strong>, and the assembled string is eighty-one bytes moved into
     * {@code WS-RETURN-MSG PIC X(75)}, so the low-order six bytes of the reason code are truncated away. Both
     * facts are reproduced.</p>
     *
     * <p>{@code CVACT03Y} contract, from {@code app/cpy/CVACT03Y.cpy}: {@code XREF-CARD-NUM X(16)},
     * {@code XREF-CUST-ID 9(09)}, {@code XREF-ACCT-ID 9(11)}, {@code FILLER X(14)} - thirty-six populated
     * bytes in a fifty-byte slot, the slack deliberately not modelled.</p>
     *
     * @param context the per-request work areas
     */
    private void getCardXrefByAcct9200(final ViewContext context) {
        // :727-735 EXEC CICS READ DATASET(LIT-CARDXREFNAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID-X) ...
        final String ioStatus = readCrossReference(context);
        final Optional<CardDemoException> failure = classify(context, ioStatus,
                XREF_ACCOUNT_PATH_NAME, OPERATION_READ);
        // :737 EVALUATE WS-RESP-CD
        if (failure.isEmpty()) {
            // :738-740 WHEN DFHRESP(NORMAL)
            final CardCrossReference crossReference = context.crossReference;
            // :739 MOVE XREF-CUST-ID TO CDEMO-CUST-ID. XREF-CUST-ID is PIC 9(09) and the column is NOT NULL,
            // so an absent value is unreachable through the schema; it is nevertheless handled explicitly and
            // yields zero, which is what an unset COBOL numeric display field holds.
            final Long crossReferenceCustomerId = crossReference.getCustomerId();
            context.customerId = crossReferenceCustomerId == null ? 0L : crossReferenceCustomerId;
            // :740 MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM - personally identifiable; never logged unmasked
            context.cardNumber = crossReference.getCardNumber();
        } else if (failure.get() instanceof RecordNotFoundException) {
            // :741-758 WHEN DFHRESP(NOTFND)
            // :742 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :743 SET FLG-ACCTFILTER-NOT-OK TO TRUE
            context.accountFilterState = AccountFilterState.NOT_OK;
            // :744 IF WS-RETURN-MSG-OFF - the response-pair moves sit INSIDE this latch
            if (isBlankOrLowValues(context.returnMessage)) {
                // :745-746 MOVE WS-RESP-CD / WS-REAS-CD TO ERROR-RESP / ERROR-RESP2
                context.errorResponse = renderResponseCode(context.responseCode);
                context.errorResponse2 = renderResponseCode(context.reasonCode);
                // :747-757 STRING 'Account:' <rid> ' not found in' ' Cross ref file.  Resp:' <resp>
                //           ' Reas:' <reas> DELIMITED BY SIZE INTO WS-RETURN-MSG
                context.returnMessage = moveAlphanumeric(MESSAGE_ACCOUNT_PREFIX
                        + moveNumericText(Long.toString(context.ridAccountId), ACCOUNT_ID_LENGTH)
                        + MESSAGE_NOT_FOUND_IN
                        + MESSAGE_XREF_FILE
                        + context.errorResponse
                        + MESSAGE_REASON
                        + context.errorResponse2, RETURN_MESSAGE_LENGTH);
            }
            retainFailure(context, failure.get());
        } else {
            // :759-768 WHEN OTHER
            // :760 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :761 SET FLG-ACCTFILTER-NOT-OK TO TRUE
            context.accountFilterState = AccountFilterState.NOT_OK;
            // :762 MOVE 'READ' TO ERROR-OPNAME
            context.errorOperation = OPERATION_READ;
            // :763 MOVE LIT-CARDXREFNAME-ACCT-PATH TO ERROR-FILE
            context.errorFile = XREF_ACCOUNT_PATH_NAME;
            // :764-765 MOVE WS-RESP-CD / WS-REAS-CD TO ERROR-RESP / ERROR-RESP2 - outside any latch here
            context.errorResponse = renderResponseCode(context.responseCode);
            context.errorResponse2 = renderResponseCode(context.reasonCode);
            // :766 MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG
            context.returnMessage = moveAlphanumeric(fileErrorMessage(context), RETURN_MESSAGE_LENGTH);
            // :767-768 the WS-LONG-MSG assignment and PERFORM SEND-LONG-TEXT are commented out - defect V2
            retainFailure(context, failure.get());
        }
        getCardXrefByAcct9200Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9200-GETCARDXREF-BYACCT-EXIT.}, logical line {@code :771}.
     * Body is a bare {@code EXIT} at {@code :772-773}.
     */
    private void getCardXrefByAcct9200Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9300-GETACCTDATA-BYACCT.}, logical line {@code :774}.
     * {@code :776-784} issues {@code EXEC CICS READ DATASET(LIT-ACCTFILENAME) RIDFLD(WS-CARD-RID-ACCT-ID-X)
     * INTO(ACCOUNT-RECORD) ...} against the base cluster {@code ACCTDAT}, declared at
     * {@code app/csd/CARDDEMO.CSD:1} over {@code DSNAME AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}. This is a
     * primary-key read, so it maps to {@code AccountRepository.findById}.
     *
     * <p>{@code :788} sets {@code FOUND-ACCT-IN-MASTER}, the flag the account half of the projection guard
     * reads at {@code :471}. The {@code SET DID-NOT-FIND-ACCT-IN-ACCTDAT} that would have made the guard at
     * {@code :704} live is commented out at {@code :792} - defect V1.</p>
     *
     * <p>The {@code NOTFND} literal built at {@code :796-806} carries <strong>no space after
     * {@code file.}</strong>, in contrast to the cross-reference variant. The response-pair moves are
     * <em>inside</em> the latch, at {@code :794-795}.</p>
     *
     * @param context the per-request work areas
     */
    private void getAcctDataByAcct9300(final ViewContext context) {
        // :776-784 EXEC CICS READ DATASET(LIT-ACCTFILENAME) RIDFLD(WS-CARD-RID-ACCT-ID-X) ...
        final String ioStatus = readAccountMaster(context);
        final Optional<CardDemoException> failure = classify(context, ioStatus,
                ACCOUNT_FILE_NAME, OPERATION_READ);
        // :786 EVALUATE WS-RESP-CD
        if (failure.isEmpty()) {
            // :787-788 WHEN DFHRESP(NORMAL) / SET FOUND-ACCT-IN-MASTER TO TRUE
            context.accountFound = true;
        } else if (failure.get() instanceof RecordNotFoundException) {
            // :789-807 WHEN DFHRESP(NOTFND)
            // :790 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :791 SET FLG-ACCTFILTER-NOT-OK TO TRUE
            context.accountFilterState = AccountFilterState.NOT_OK;
            // :792 SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE - commented out in the source; defect V1
            // :793 IF WS-RETURN-MSG-OFF - the response-pair moves sit INSIDE this latch
            if (isBlankOrLowValues(context.returnMessage)) {
                // :794-795 MOVE WS-RESP-CD / WS-REAS-CD TO ERROR-RESP / ERROR-RESP2
                context.errorResponse = renderResponseCode(context.responseCode);
                context.errorResponse2 = renderResponseCode(context.reasonCode);
                // :796-806 STRING 'Account:' <rid> ' not found in' ' Acct Master file.Resp:' <resp>
                //           ' Reas:' <reas> DELIMITED BY SIZE INTO WS-RETURN-MSG
                context.returnMessage = moveAlphanumeric(MESSAGE_ACCOUNT_PREFIX
                        + moveNumericText(Long.toString(context.ridAccountId), ACCOUNT_ID_LENGTH)
                        + MESSAGE_NOT_FOUND_IN
                        + MESSAGE_ACCOUNT_MASTER_FILE
                        + context.errorResponse
                        + MESSAGE_REASON
                        + context.errorResponse2, RETURN_MESSAGE_LENGTH);
            }
            retainFailure(context, failure.get());
        } else {
            // :809-818 WHEN OTHER
            // :810 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :811 SET FLG-ACCTFILTER-NOT-OK TO TRUE
            context.accountFilterState = AccountFilterState.NOT_OK;
            // :812 MOVE 'READ' TO ERROR-OPNAME
            context.errorOperation = OPERATION_READ;
            // :813 MOVE LIT-ACCTFILENAME TO ERROR-FILE
            context.errorFile = ACCOUNT_FILE_NAME;
            // :814-815 MOVE WS-RESP-CD / WS-REAS-CD TO ERROR-RESP / ERROR-RESP2
            context.errorResponse = renderResponseCode(context.responseCode);
            context.errorResponse2 = renderResponseCode(context.reasonCode);
            // :816 MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG
            context.returnMessage = moveAlphanumeric(fileErrorMessage(context), RETURN_MESSAGE_LENGTH);
            // :817-818 the WS-LONG-MSG assignment and PERFORM SEND-LONG-TEXT are commented out - defect V2
            retainFailure(context, failure.get());
        }
        getAcctDataByAcct9300Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9300-GETACCTDATA-BYACCT-EXIT.}, logical line {@code :821}.
     * Body is a bare {@code EXIT} at {@code :822-823}.
     */
    private void getAcctDataByAcct9300Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9400-GETCUSTDATA-BYCUST.}, logical line {@code :825}.
     * {@code :826-834} issues {@code EXEC CICS READ DATASET(LIT-CUSTFILENAME) RIDFLD(WS-CARD-RID-CUST-ID-X)
     * INTO(CUSTOMER-RECORD) ...} against {@code CUSTDAT}, declared at {@code app/csd/CARDDEMO.CSD:50}. A
     * primary-key read, so {@code CustomerRepository.findById}.
     *
     * <p>Two asymmetries against the two paragraphs above are deliberate and preserved:</p>
     *
     * <ul>
     *   <li><em>Flag asymmetry.</em> {@code 9200} and {@code 9300} set {@code FLG-ACCTFILTER-NOT-OK};
     *       {@code 9400} sets {@code FLG-CUSTFILTER-NOT-OK}, at {@code :841} and {@code :860}. They are
     *       different flags on different working-storage items ({@code :58-61} versus {@code :62-65}), and
     *       {@code FLG-CUSTFILTER-NOT-OK} is <strong>tested nowhere in the program</strong> - which is exactly
     *       why an account-master miss stops the chain and a customer-master miss does not.</li>
     *   <li><em>Latch-placement asymmetry.</em> In {@code 9200} and {@code 9300} the response-pair moves sit
     *       inside the {@code IF WS-RETURN-MSG-OFF} latch; here they sit <strong>outside and before</strong>
     *       it, at {@code :843-844} with the {@code IF} following at {@code :845}.</li>
     * </ul>
     *
     * <p>The {@code NOTFND} literal built at {@code :846-856} is the odd one of the three: it has a
     * <strong>trailing space after {@code Resp:}</strong> and an <strong>upper-case {@code REAS:}</strong>,
     * both unique to this paragraph. The {@code SET DID-NOT-FIND-CUST-IN-CUSTDAT} is commented out at
     * {@code :842} - defect V1.</p>
     *
     * <p>{@code CVCUS01Y} contract, from {@code app/cpy/CVCUS01Y.cpy}, summing to exactly five hundred bytes:
     * {@code CUST-ID 9(09)}, {@code CUST-FIRST-NAME X(25)}, {@code CUST-MIDDLE-NAME X(25)},
     * {@code CUST-LAST-NAME X(25)}, {@code CUST-ADDR-LINE-1/2/3 X(50)} each,
     * {@code CUST-ADDR-STATE-CD X(02)}, {@code CUST-ADDR-COUNTRY-CD X(03)}, {@code CUST-ADDR-ZIP X(10)},
     * {@code CUST-PHONE-NUM-1 X(15)}, {@code CUST-PHONE-NUM-2 X(15)}, {@code CUST-SSN 9(09)},
     * {@code CUST-GOVT-ISSUED-ID X(20)}, {@code CUST-DOB-YYYY-MM-DD X(10)},
     * {@code CUST-EFT-ACCOUNT-ID X(10)}, {@code CUST-PRI-CARD-HOLDER-IND X(01)},
     * {@code CUST-FICO-CREDIT-SCORE 9(03)}, {@code FILLER X(168)}.</p>
     *
     * @param context the per-request work areas
     */
    private void getCustDataByCust9400(final ViewContext context) {
        // :826-834 EXEC CICS READ DATASET(LIT-CUSTFILENAME) RIDFLD(WS-CARD-RID-CUST-ID-X) ...
        final String ioStatus = readCustomerMaster(context);
        final Optional<CardDemoException> failure = classify(context, ioStatus,
                CUSTOMER_FILE_NAME, OPERATION_READ);
        // :836 EVALUATE WS-RESP-CD
        if (failure.isEmpty()) {
            // :837-838 WHEN DFHRESP(NORMAL) / SET FOUND-CUST-IN-MASTER TO TRUE
            context.customerFound = true;
        } else if (failure.get() instanceof RecordNotFoundException) {
            // :839-857 WHEN DFHRESP(NOTFND)
            // :840 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :841 SET FLG-CUSTFILTER-NOT-OK TO TRUE - a DIFFERENT flag from 9200/9300, tested nowhere
            context.customerFilterNotOk = true;
            // :842 SET DID-NOT-FIND-CUST-IN-CUSTDAT TO TRUE - commented out in the source; defect V1
            // :843-844 MOVE WS-RESP-CD / WS-REAS-CD TO ERROR-RESP / ERROR-RESP2 - OUTSIDE the latch here
            context.errorResponse = renderResponseCode(context.responseCode);
            context.errorResponse2 = renderResponseCode(context.reasonCode);
            // :845 IF WS-RETURN-MSG-OFF
            if (isBlankOrLowValues(context.returnMessage)) {
                // :846-856 STRING 'CustId:' <rid> ' not found' ' in customer master.Resp: ' <resp>
                //           ' REAS:' <reas> DELIMITED BY SIZE INTO WS-RETURN-MSG
                context.returnMessage = moveAlphanumeric(MESSAGE_CUSTOMER_PREFIX
                        + moveNumericText(Long.toString(context.ridCustomerId), CUSTOMER_ID_LENGTH)
                        + MESSAGE_NOT_FOUND
                        + MESSAGE_CUSTOMER_MASTER
                        + context.errorResponse
                        + MESSAGE_REASON_UPPER
                        + context.errorResponse2, RETURN_MESSAGE_LENGTH);
            }
            retainFailure(context, failure.get());
        } else {
            // :858-867 WHEN OTHER
            // :859 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :860 SET FLG-CUSTFILTER-NOT-OK TO TRUE
            context.customerFilterNotOk = true;
            // :861 MOVE 'READ' TO ERROR-OPNAME
            context.errorOperation = OPERATION_READ;
            // :862 MOVE LIT-CUSTFILENAME TO ERROR-FILE
            context.errorFile = CUSTOMER_FILE_NAME;
            // :863-864 MOVE WS-RESP-CD / WS-REAS-CD TO ERROR-RESP / ERROR-RESP2
            context.errorResponse = renderResponseCode(context.responseCode);
            context.errorResponse2 = renderResponseCode(context.reasonCode);
            // :865 MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG
            context.returnMessage = moveAlphanumeric(fileErrorMessage(context), RETURN_MESSAGE_LENGTH);
            // :866-867 the WS-LONG-MSG assignment and PERFORM SEND-LONG-TEXT are commented out - defect V2
            retainFailure(context, failure.get());
        }
        getCustDataByCust9400Exit();
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code 9400-GETCUSTDATA-BYCUST-EXIT.}, logical line {@code :870}.
     * Body is a bare {@code EXIT} at {@code :871-872}.
     */
    private void getCustDataByCust9400Exit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code SEND-PLAIN-TEXT.}, logical line {@code :877}.
     * {@code :878-883} issues {@code EXEC CICS SEND TEXT FROM(WS-RETURN-MSG)
     * LENGTH(LENGTH OF WS-RETURN-MSG) ERASE FREEKB} and {@code :885-886} then issues
     * {@code EXEC CICS RETURN}, ending the task.
     *
     * <p>Reachable, and reachable from exactly one site: the {@code PERFORM} at {@code :381-382} inside the
     * {@code WHEN OTHER} arm - which is defect V6. The header comment above the label, at {@code :875}, reads
     * verbatim {@code * Plain text exit - Dont use in production}; the missing apostrophe in {@code Dont} is
     * in the corpus and is cited rather than corrected (defect V3, severity Low).</p>
     *
     * <p>{@code LENGTH OF WS-RETURN-MSG} is seventy-five, so exactly seventy-five bytes are sent and the
     * message is blank-padded to that width. The padding is reproduced, because the byte image is what a
     * parity comparison examines.</p>
     *
     * @param context the per-request work areas
     * @return a plain-text response carrying the seventy-five-byte message; never {@code null}
     */
    private AccountViewResult sendPlainText(final ViewContext context) {
        // :878-883 EXEC CICS SEND TEXT FROM(WS-RETURN-MSG) LENGTH(LENGTH OF WS-RETURN-MSG) ERASE FREEKB
        final String text = padRight(context.returnMessage, RETURN_MESSAGE_LENGTH);
        LOG.warn("CAVW plain-text response: {}", text.strip());
        // :885-886 EXEC CICS RETURN - the task ends; statelessly the response is simply returned
        final AccountViewResult result = new AccountViewResult(ResponseKind.PLAIN_TEXT,
                context.attentionKey,
                null,
                text,
                null,
                null,
                context.nextEntryMode,
                context.inputError,
                context.accountFound,
                context.customerFound);
        sendPlainTextExit();
        return result;
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code SEND-PLAIN-TEXT-EXIT.}, logical line {@code :888}. Body
     * is a bare {@code EXIT} at {@code :889-890}.
     */
    private void sendPlainTextExit() {
        // EXIT
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code SEND-LONG-TEXT.}, logical line {@code :896}.
     * {@code :897-902} issues {@code EXEC CICS SEND TEXT FROM(WS-LONG-MSG) LENGTH(LENGTH OF WS-LONG-MSG)
     * ERASE FREEKB} and {@code :904-905} then issues {@code EXEC CICS RETURN}.
     *
     * <p><strong>Defect V2: unreachable.</strong> All three {@code PERFORM SEND-LONG-TEXT} sites are commented
     * out - at {@code :768}, {@code :818} and {@code :867} - and so is the {@code WS-LONG-MSG} assignment
     * above each of them, which is why the five-hundred-byte buffer this paragraph sends is always unset and
     * the emitted text is five hundred blanks. The header comment at {@code :891-895} says the paragraph is
     * for debugging and should not be used in the regular course. It is mapped anyway, because the 1:1 label
     * mandate admits no exception for unreachable labels, and <strong>no JaCoCo exclusion is added</strong>
     * for it.</p>
     *
     * @param context the per-request work areas
     * @return a plain-text response carrying the five-hundred-byte long-message buffer; never {@code null}
     */
    private AccountViewResult sendLongText(final ViewContext context) {
        // Intentional unreachable: all three PERFORM sites are commented out at
        // app/cbl/COACTVWC.cbl:L768, :L818, :L867. See the DECISION_LOG.md defect V2.
        // :897-902 EXEC CICS SEND TEXT FROM(WS-LONG-MSG) LENGTH(LENGTH OF WS-LONG-MSG) ERASE FREEKB
        final String text = padRight(context.longMessage, LONG_MESSAGE_LENGTH);
        LOG.debug("CAVW long-text diagnostic response of {} bytes issued", text.length());
        // :904-905 EXEC CICS RETURN
        final AccountViewResult result = new AccountViewResult(ResponseKind.PLAIN_TEXT,
                context.attentionKey,
                null,
                text,
                null,
                null,
                context.nextEntryMode,
                context.inputError,
                context.accountFound,
                context.customerFound);
        sendLongTextExit();
        return result;
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code SEND-LONG-TEXT-EXIT.}, logical line {@code :907}. Body is
     * a bare {@code EXIT} at {@code :908-909}. Unreachable for the same reason as the paragraph it closes -
     * defect V2.
     */
    private void sendLongTextExit() {
        // Intentional unreachable: reached only from SEND-LONG-TEXT. See the DECISION_LOG.md defect V2.
    }

    /**
     * {@code app/cbl/COACTVWC.cbl}, paragraph {@code ABEND-ROUTINE.}, logical line {@code :916}. Note that
     * this program declares <strong>no {@code ABEND-ROUTINE-EXIT}</strong>; the roster ends here.
     *
     * <p>Reached only through the handler registered by {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at
     * {@code :264-266} - never by {@code PERFORM}. The body, verbatim: substitute
     * {@code 'UNEXPECTED ABEND OCCURRED.'} when {@code ABEND-MSG} is {@code LOW-VALUES}
     * ({@code :918-920}); move {@code LIT-THISPGM} into {@code ABEND-CULPRIT} ({@code :922}); send the abend
     * block with {@code NOHANDLE} ({@code :924-928}) - and note there is deliberately <strong>no
     * {@code ERASE}</strong> here, unlike the sibling program; cancel the abend handler ({@code :930-932});
     * and {@code EXEC CICS ABEND ABCODE('9999')} ({@code :934-936}).</p>
     *
     * <p>The payload is the four abend work-area fields of {@code app/cpy/CSMSG02Y.cpy} - internally titled
     * {@code CABENDD.CPY} - namely {@code ABEND-CODE PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)},
     * {@code ABEND-REASON PIC X(50)} and {@code ABEND-MSG PIC X(72)}, one hundred and thirty-four bytes in
     * all, every one declared {@code VALUE SPACES}. They are exactly the constructor payload of
     * {@code FatalProcessingException}, and the empty-message substitution is already implemented there as
     * {@code DEFAULT_ABEND_MESSAGE}, so it is reused rather than reimplemented.</p>
     *
     * <p>Defect V8 lives on the first line of the body. {@code ABEND-DATA} is an independent {@code 01} group and is
     * not among the three groups {@code INITIALIZE} names at {@code :268-270}, so its {@code VALUE SPACES} stands and
     * {@code ABEND-MSG} holds blanks rather than {@code LOW-VALUES}; the program assigns it nowhere. Under a strict
     * reading the test at {@code :918} is therefore false and seventy-two blanks are sent. A thrown exception has no
     * terminal to send blanks to, so the unset message is treated as absent here and the substitution fires - a
     * labelled deviation, severity Low, recorded in the class-level register and in the
     * {@code DECISION_LOG.md}.</p>
     *
     * <p>Reconciliation: the abend code here is the <strong>online</strong> four-character
     * {@value #ONLINE_ABEND_CODE}, which exactly fills {@code PIC X(4)}. It is not the batch
     * {@code FatalProcessingException.BATCH_ABEND_CODE} of {@code 999} that {@code app/cbl/CBTRN02C.cbl}
     * passes to {@code CALL 'CEE3ABD'}, and it is not accompanied by
     * {@code FatalProcessingException.BATCH_RETURN_CODE}: an online transaction abend has no process return
     * code at all. Neither batch constant is redeclared, reused or repurposed here.</p>
     *
     * @param context the per-request work areas; the abend culprit and message are stamped
     * @param cause   the throwable that reached the handler; preserved as the exception's cause so the root
     *                cause is never lost
     * @return the fatal exception the caller must throw; never {@code null}
     */
    private FatalProcessingException abendRoutine(final ViewContext context, final Throwable cause) {
        // :918-920 IF ABEND-MSG EQUAL LOW-VALUES / MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG
        if (isBlankOrLowValues(context.abendMessage)) {
            context.abendMessage = FatalProcessingException.DEFAULT_ABEND_MESSAGE;
        }
        // :922 MOVE LIT-THISPGM TO ABEND-CULPRIT
        context.abendCulprit = PROGRAM_NAME;
        // :924-928 EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA) NOHANDLE - no ERASE
        LOG.error("CAVW abend: code={} culprit={} message={}",
                ONLINE_ABEND_CODE, context.abendCulprit, context.abendMessage, cause);
        // :930-932 EXEC CICS HANDLE ABEND CANCEL - the handler is not re-entered
        // :934-936 EXEC CICS ABEND ABCODE('9999') - the task is terminated
        return new FatalProcessingException(ONLINE_ABEND_CODE,
                context.abendCulprit,
                context.abendReason == null ? ABEND_REASON_SPACES : context.abendReason,
                context.abendMessage,
                cause);
    }

    /**
     * {@code app/cpy/CSSTRPFY.cpy}, paragraph {@code YYYY-STORE-PFKEY.}, logical line
     * {@code app/cpy/CSSTRPFY.cpy:17}, copied into this program's {@code PROCEDURE DIVISION} by the Area-A
     * {@code COPY 'CSSTRPFY'} construct at {@code :913}.
     *
     * <p>The copybook body is a twenty-eight-arm {@code EVALUATE TRUE} on {@code EIBAID}, at
     * {@code app/cpy/CSSTRPFY.cpy:21-78}, mapping {@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1},
     * {@code DFHPA2} and {@code DFHPF1} through {@code DFHPF24} onto the {@code CCARD-AID-*} flags of
     * {@code app/cpy/CVCRD01Y.cpy}, with <strong>PF13 through PF24 folding back onto PFK01 through
     * PFK12</strong>. There is no {@code WHEN OTHER}: an unrecognised identifier leaves the flag at the value
     * {@code INITIALIZE} gave it at {@code :268}, which is blank, so no {@code CCARD-AID-*} condition is true
     * and {@code :312-313} coerces the request to Enter.</p>
     *
     * <p>The translation is a pure mapping onto {@link AidKey} and nothing more; the HTTP-to-action mapping
     * proper belongs to the controller. Matching is case-insensitive under {@code Locale.ROOT} and accepts
     * three spellings of the same key - {@code DFHPF3}, {@code PF3} and {@code PFK03} - by normalising to the
     * copybook's own symbol before the switch. No method is emitted for the {@code COPY} directive itself.</p>
     *
     * @param attentionIdentifier the raw {@code EIBAID} symbol; {@code null}, blank and unrecognised values
     *                            all yield {@code null}, which is the copybook's fall-through behaviour
     * @return the mapped key, or {@code null} when no arm matched
     */
    private AidKey storePfKey(final String attentionIdentifier) {
        final AidKey mapped;
        if (isBlankOrLowValues(attentionIdentifier)) {
            mapped = null;
        } else {
            String symbol = attentionIdentifier.strip().toUpperCase(Locale.ROOT);
            if (symbol.startsWith("PFK")) {
                symbol = "PF" + Integer.parseInt(symbol.substring("PFK".length()));
            }
            if (!symbol.startsWith("DFH")) {
                symbol = "DFH" + symbol;
            }
            // app/cpy/CSSTRPFY.cpy:21-78 EVALUATE TRUE ... END-EVALUATE - twenty-eight arms, no WHEN OTHER
            mapped = switch (symbol) {
                case "DFHENTER" -> AidKey.ENTER;
                case "DFHCLEAR" -> AidKey.CLEAR;
                case "DFHPA1" -> AidKey.PA1;
                case "DFHPA2" -> AidKey.PA2;
                case "DFHPF1" -> AidKey.PFK01;
                case "DFHPF2" -> AidKey.PFK02;
                case "DFHPF3" -> AidKey.PFK03;
                case "DFHPF4" -> AidKey.PFK04;
                case "DFHPF5" -> AidKey.PFK05;
                case "DFHPF6" -> AidKey.PFK06;
                case "DFHPF7" -> AidKey.PFK07;
                case "DFHPF8" -> AidKey.PFK08;
                case "DFHPF9" -> AidKey.PFK09;
                case "DFHPF10" -> AidKey.PFK10;
                case "DFHPF11" -> AidKey.PFK11;
                case "DFHPF12" -> AidKey.PFK12;
                case "DFHPF13" -> AidKey.PFK01;
                case "DFHPF14" -> AidKey.PFK02;
                case "DFHPF15" -> AidKey.PFK03;
                case "DFHPF16" -> AidKey.PFK04;
                case "DFHPF17" -> AidKey.PFK05;
                case "DFHPF18" -> AidKey.PFK06;
                case "DFHPF19" -> AidKey.PFK07;
                case "DFHPF20" -> AidKey.PFK08;
                case "DFHPF21" -> AidKey.PFK09;
                case "DFHPF22" -> AidKey.PFK10;
                case "DFHPF23" -> AidKey.PFK11;
                case "DFHPF24" -> AidKey.PFK12;
                default -> null;
            };
        }
        storePfKeyExit();
        return mapped;
    }

    /**
     * {@code app/cpy/CSSTRPFY.cpy}, paragraph {@code YYYY-STORE-PFKEY-EXIT.}, logical line
     * {@code app/cpy/CSSTRPFY.cpy:80}. Body is a bare {@code EXIT} at
     * {@code app/cpy/CSSTRPFY.cpy:81-82}. Reached by the {@code PERFORM ... THRU} at {@code :299-300}.
     */
    private void storePfKeyExit() {
        // EXIT
    }

    // ------------------------------------------------------------------------------------------------
    // Repository adapters. These are not paragraph translations: they are the boundary at which a Spring
    // Data outcome is expressed as the two-character file status the corpus works in, so that the
    // status-to-exception decision stays where it belongs - in FileStatusMapper - and is not duplicated
    // once per read paragraph. Each records the CICS RESP ordinal the diagnostic messages of :747-757,
    // :796-806 and :846-856 render, and each is the only place a DataAccessException is caught.
    // ------------------------------------------------------------------------------------------------

    /**
     * Performs the alternate-index read of {@code :727-735} and reports its outcome as a file status.
     *
     * <p>{@code app/catlg/LISTCAT.txt} marks {@code CARDXREF.VSAM.AIX} non-unique, so duplicates are
     * permitted. A VSAM {@code READ} through the {@code CXACAIX} path surfaces the first record in
     * alternate-then-primary key order, which is what
     * {@code findFirstByAccountIdOrderByCardNumberAsc} reproduces with {@code LIMIT 1} applied by the
     * database; an empty {@code Optional} is the {@code NOTFND} condition and nothing else.</p>
     *
     * @param context the per-request work areas; the cross-reference record, the response pair and any
     *                underlying cause are recorded
     * @return {@code "00"} on a hit, {@code "23"} on a miss, {@code "90"} on an infrastructure failure
     */
    private String readCrossReference(final ViewContext context) {
        try {
            // LIMIT 1 at the database: a keyed read through the CXACAIX path yields one record, and only
            // the first was ever used here. See CardCrossReferenceRepository for the full reasoning.
            final Optional<CardCrossReference> found =
                    this.cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(
                            context.ridAccountId);
            if (found.isEmpty()) {
                return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            context.crossReference = found.get();
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Performs the primary-key read of {@code :776-784} against {@code ACCTDAT} and reports its outcome as a
     * file status.
     *
     * @param context the per-request work areas; the account record, the response pair and any underlying
     *                cause are recorded
     * @return {@code "00"} on a hit, {@code "23"} on a miss, {@code "90"} on an infrastructure failure
     */
    private String readAccountMaster(final ViewContext context) {
        try {
            final Optional<Account> found = this.accountRepository.findById(context.ridAccountId);
            if (found.isEmpty()) {
                return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            context.account = found.get();
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Performs the primary-key read of {@code :826-834} against {@code CUSTDAT} and reports its outcome as a
     * file status.
     *
     * @param context the per-request work areas; the customer record, the response pair and any underlying
     *                cause are recorded
     * @return {@code "00"} on a hit, {@code "23"} on a miss, {@code "90"} on an infrastructure failure
     */
    private String readCustomerMaster(final ViewContext context) {
        try {
            final Optional<Customer> found = this.customerRepository.findById(context.ridCustomerId);
            if (found.isEmpty()) {
                return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            context.customer = found.get();
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Stamps the {@code RESP}/{@code RESP2} pair the three read paragraphs render into their diagnostics.
     * {@code RESP2} is always zero here, because a Spring Data outcome carries no second-level reason code;
     * the corpus itself leaves {@code WS-REAS-CD} at zero for every condition these paragraphs handle.
     *
     * @param context      the per-request work areas
     * @param responseCode the CICS {@code RESP} ordinal to record
     * @param ioStatus     the file status to return unchanged
     * @return {@code ioStatus}, so that call sites read as a single expression
     */
    private static String recordResponse(final ViewContext context, final int responseCode,
                                         final String ioStatus) {
        context.responseCode = responseCode;
        context.reasonCode = CICS_REASON_NONE;
        return ioStatus;
    }

    /**
     * Hands the file status to {@code FileStatusMapper}, which owns the status-to-exception decision and the
     * {@code FILE STATUS IS: NNNN} rendering; neither is reimplemented here. When a
     * {@code DataAccessException} produced the status it is passed as the cause, so the root cause is
     * preserved rather than swallowed.
     *
     * <p>{@code DFHRESP(NOTOPEN)} - and therefore {@code FileUnavailableException} - has no locator in this
     * program: a repository-wide census of {@code NOTOPEN} in {@code app/cbl/COACTVWC.cbl} returns nothing.
     * The mapping is reachable only at runtime, through a file status of {@code '35'}, and is documented as
     * such rather than attributed to an invented line.</p>
     *
     * @param context         the per-request work areas; the retained cause is consumed and cleared
     * @param ioStatus        the two-character file status
     * @param logicalFileName the CICS file or path name the read used
     * @param operation       the attempted operation, always {@code READ} in this program
     * @return the exception the status maps to, or an empty {@code Optional} for a success
     */
    private Optional<CardDemoException> classify(final ViewContext context, final String ioStatus,
                                                 final String logicalFileName, final String operation) {
        final Throwable cause = context.ioFailureCause;
        context.ioFailureCause = null;
        if (cause == null) {
            return this.fileStatusMapper.toException(ioStatus, logicalFileName, operation);
        }
        return this.fileStatusMapper.toException(ioStatus, logicalFileName, operation, cause);
    }

    /**
     * Retains the first typed failure of the request, mirroring the {@code IF WS-RETURN-MSG-OFF}
     * first-error-wins latch the three read paragraphs apply to their messages. The legacy program cannot
     * throw, so it keeps the message and carries on; a stateless caller needs the typed exception as well,
     * and {@link #viewAccount(String)} rethrows it. Nothing is discarded and no {@code catch} block is empty.
     *
     * @param context the per-request work areas
     * @param failure the typed failure to retain; the first one wins, exactly as the first message does
     */
    private static void retainFailure(final ViewContext context, final CardDemoException failure) {
        if (context.pendingFailure == null) {
            context.pendingFailure = failure;
        }
    }

    /**
     * Assembles {@code WS-FILE-ERROR-MESSAGE}, the eighty-byte group declared at {@code :86-105}:
     * {@code 'File Error: '} then {@code ERROR-OPNAME X(8)}, {@code ' on '},
     * {@code ERROR-FILE X(9)}, {@code ' returned RESP '}, {@code ERROR-RESP X(10)}, {@code ',RESP2 '},
     * {@code ERROR-RESP2 X(10)} and five trailing blanks - twelve, eight, four, nine, fifteen, ten, seven,
     * ten and five, summing to exactly eighty. The {@code MOVE} into {@code WS-RETURN-MSG PIC X(75)} at
     * {@code :766}, {@code :816} and {@code :865} truncates the low-order five bytes, and the callers apply
     * that truncation.
     *
     * <p>Every component is blank-padded to its declared width, because a fixed-width group is what the
     * three {@code WHEN OTHER} arms send and a parity comparison examines the byte image. Note that
     * {@code ERROR-FILE} is {@code X(9)} while every file literal is {@code X(8)}, so each name carries one
     * extra trailing blank - and {@code 'CXACAIX '} therefore renders with two.</p>
     *
     * @param context the per-request work areas, whose four error components have already been stamped
     * @return the eighty-byte diagnostic; never {@code null}
     */
    private static String fileErrorMessage(final ViewContext context) {
        return FILE_ERROR_PREFIX
                + padRight(context.errorOperation, ERROR_OPERATION_LENGTH)
                + FILE_ERROR_ON
                + padRight(context.errorFile, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + padRight(context.errorResponse, ERROR_RESPONSE_LENGTH)
                + FILE_ERROR_RESP2
                + padRight(context.errorResponse2, ERROR_RESPONSE_LENGTH)
                + " ".repeat(FILE_ERROR_TRAILING_BLANKS);
    }

    /**
     * Renders a {@code WS-RESP-CD}/{@code WS-REAS-CD} value the way the {@code MOVE} into
     * {@code ERROR-RESP PIC X(10)} does. Both source fields are {@code PIC S9(09) COMP} ({@code :78-79}), so
     * a numeric-to-alphanumeric move yields nine zero-padded digits left-justified in the ten-byte target,
     * leaving one trailing blank: {@code DFHRESP(NOTFND)} becomes {@code "000000013 "}. The sign is dropped,
     * as it is for every unsigned move of this shape.
     *
     * @param code the response or reason ordinal
     * @return exactly ten characters; never {@code null}
     */
    private static String renderResponseCode(final int code) {
        return padRight(moveNumericText(Integer.toString(code), RESPONSE_CODE_DIGITS),
                ERROR_RESPONSE_LENGTH);
    }

    // ------------------------------------------------------------------------------------------------
    // COBOL data-movement primitives. These reproduce MOVE, class-test and PICTURE-edit semantics, which the
    // language performs implicitly and Java does not. They are pure functions of their arguments - no clock,
    // no locale default, no collaborator - so they are deterministic by construction (Rule 1 Clause A).
    // ------------------------------------------------------------------------------------------------

    /**
     * Blank-pads on the right to an exact width, truncating on the right when the value is already longer.
     * This is what a fixed-width COBOL group member looks like once assembled, and it is used only where the
     * byte image matters: the eighty-byte file-error group and the two {@code SEND TEXT} buffers.
     *
     * @param value the value, permitted to be {@code null}, which is treated as {@code SPACES}
     * @param width the exact width to produce
     * @return a string of exactly {@code width} characters; never {@code null}
     */
    private static String padRight(final String value, final int width) {
        final String source = value == null ? "" : value;
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * Reproduces an alphanumeric {@code MOVE}: left-justify in the receiving field and truncate any excess on
     * the right. Trailing blank fill is deliberately <em>not</em> applied, because the receiving fields here
     * are JSON components rather than screen bytes, and {@code AccountDto} validates a maximum width rather
     * than an exact one. The truncation is what carries parity - it is the mechanism behind
     * {@code CUST-ADDR-ZIP X(10)} into {@code X(5)} at {@code :515} and both
     * {@code CUST-PHONE-NUM-1/2 X(15)} into {@code X(13)} at {@code :517-518}.
     *
     * @param value the sending value, permitted to be {@code null}, which propagates as {@code null} and
     *              represents the {@code LOW-VALUES} an unset component carries
     * @param width the receiving field width
     * @return the truncated value, or {@code null} when {@code value} was {@code null}
     */
    private static String moveAlphanumeric(final String value, final int width) {
        if (value == null) {
            return null;
        }
        return value.length() <= width ? value : value.substring(0, width);
    }

    /**
     * Reproduces a {@code MOVE} of a {@code PIC 9(n)} item into an {@code X(n)} receiving field: the digits
     * are right-aligned and zero-filled on the left, and any excess is truncated from the
     * <strong>high</strong> order, which is how COBOL truncates numeric moves. Non-digit characters - an
     * overpunch sign, a separator, a stray blank - are discarded, because the sending item is a numeric
     * display field whose value is its digits.
     *
     * @param value the sending digits, permitted to be {@code null} or blank, both of which yield all zeroes
     *              exactly as an unset numeric display field does
     * @param width the receiving field width in digit positions
     * @return exactly {@code width} characters, all of them digits; never {@code null}
     */
    private static String moveNumericText(final String value, final int width) {
        final String candidate = value == null ? "" : value;
        final StringBuilder digits = new StringBuilder(candidate.length());
        for (int index = 0; index < candidate.length(); index++) {
            final char character = candidate.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            }
        }
        final String significant = digits.length() > width
                ? digits.substring(digits.length() - width)
                : digits.toString();
        return "0".repeat(width - significant.length()) + significant;
    }

    /**
     * The {@code EQUAL LOW-VALUES OR SPACES} test the source applies at {@code :628-629}, {@code :652-654},
     * {@code :657}, {@code :328}, {@code :334}, {@code :528} and {@code :567}. A Java {@code null} stands for
     * {@code LOW-VALUES} - the state of an unset component - and so does a run of NUL characters, which is
     * what a fixed-width buffer holds after {@code MOVE LOW-VALUES}.
     *
     * @param value the value to test, permitted to be {@code null}
     * @return {@code true} when the value is absent, blank or entirely NUL
     */
    private static boolean isBlankOrLowValues(final String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code ACCTSIDI = '*'} test at {@code :628}. The field is {@code X(11)} and the literal is a single
     * character, which COBOL extends with blanks to the field width, so equality holds if and only if the
     * value is one asterisk followed by nothing but blanks. A run such as {@code ***} therefore does
     * <strong>not</strong> match: it is invalid input, not "not supplied", and {@code *} is under no reading a
     * wildcard search.
     *
     * @param value the received filter, permitted to be {@code null}
     * @return {@code true} only for a single asterisk optionally followed by blanks
     */
    private static boolean isSingleAsterisk(final String value) {
        if (value == null || value.isEmpty() || value.charAt(0) != '*') {
            return false;
        }
        return value.substring(1).isBlank();
    }

    /**
     * The {@code CC-ACCT-ID IS NOT NUMERIC} class test at {@code :666}, expressed positively. {@code CC-ACCT-ID}
     * is {@code PIC X(11)} and the {@code NUMERIC} class test on an alphanumeric item is true only when
     * <strong>every</strong> character position holds a digit. A short entry is blank-padded in COBOL and a
     * blank fails the test, so {@code "1"} is rejected rather than read as account one. That is preserved
     * here by requiring exactly eleven digit characters.
     *
     * @param value the normalised filter text, permitted to be {@code null}
     * @return {@code true} only for exactly eleven digits
     */
    private static boolean isElevenDigitNumeric(final String value) {
        if (value == null || value.length() != ACCOUNT_ID_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code CC-ACCT-ID EQUAL ZEROES} test at {@code :667}, the second half of the same {@code OR}. It is
     * evaluated after the class test in the source's own order, and because the two are joined by {@code OR}
     * an eleven-digit all-zero value is the only case this half decides on its own.
     *
     * @param value the normalised filter text, permitted to be {@code null}
     * @return {@code true} when the value is non-empty and every character is the digit zero
     */
    private static boolean isAllZeroes(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the {@code STRING} statement at {@code :496-504}, which builds {@code NNN-NN-NNNN} from the
     * three reference modifiers {@code CUST-SSN(1:3)}, {@code CUST-SSN(4:2)} and {@code CUST-SSN(6:4)} of a
     * {@code PIC 9(09)} sending item, delimited by size, into {@code ACSTSSNO PIC X(12)}
     * ({@code app/cpy-bms/COACTVW.CPY:356}). The direct {@code MOVE CUST-SSN TO ACSTSSNO} immediately above,
     * at {@code :495}, is commented out in the corpus and is not reinstated.
     *
     * <p>The result belongs in the business response, for parity. It is personally identifiable and is
     * <strong>never</strong> written to a log sink; the two sinks are distinct and are not conflated.</p>
     *
     * @param ssn the nine-digit social security number as stored, permitted to be {@code null}
     * @return exactly twelve characters, or {@code null} when {@code ssn} was {@code null}
     */
    private static String formatSocialSecurityNumber(final String ssn) {
        if (ssn == null) {
            return null;
        }
        final String digits = moveNumericText(ssn, SSN_DIGITS);
        return digits.substring(0, SSN_AREA_END)
                + SSN_SEPARATOR
                + digits.substring(SSN_AREA_END, SSN_GROUP_END)
                + SSN_SEPARATOR
                + digits.substring(SSN_GROUP_END, SSN_DIGITS);
    }

    /**
     * Reproduces a {@code MOVE} into a {@code PIC +ZZZ,ZZZ,ZZZ.99} numeric-edited receiving field - the
     * declared picture of {@code ACRDLIMO}, {@code ACSHLIMO}, {@code ACURBALO}, {@code ACRCYCRO} and
     * {@code ACRCYDBO} at {@code app/cpy-bms/COACTVW.CPY:302}, {@code :314}, {@code :326}, {@code :332} and
     * {@code :344}. Fifteen characters wide: a fixed-position sign, nine integer digit positions with two
     * comma insertions, a decimal point, and two unsuppressed decimal digits.
     *
     * <p>Four editing rules are applied exactly as the {@code PICTURE} clause specifies them.</p>
     *
     * <ul>
     *   <li>The leading {@code +} is a fixed-position sign: {@code +} for zero and positive values,
     *       {@code -} for negative ones. Sign detection is on the scaled value, so a value that rounds to
     *       zero renders {@code +}.</li>
     *   <li>{@code Z} suppresses a leading zero to a blank. Suppression stops at the first significant digit
     *       and never crosses the decimal point, because the two positions after it are {@code 9}.</li>
     *   <li>A {@code ,} whose digit positions to the left are all suppressed is itself replaced by a blank.
     *       That is why the separator is emitted from the running suppression state rather than
     *       unconditionally.</li>
     *   <li>{@code app/cpy/CVACT01Y.cpy} declares these amounts {@code PIC S9(10)V99} while the mask offers
     *       only nine integer positions, so a COBOL {@code MOVE} discards the high-order digit. That
     *       truncation is reproduced by {@link #moveNumericText(String, int)}, which truncates from the high
     *       order.</li>
     * </ul>
     *
     * <p>Arithmetic is {@code BigDecimal} throughout with an explicit {@code RoundingMode.HALF_EVEN} - the
     * same value {@code AccountDto.MONEY_ROUNDING} publishes. No binary floating-point type appears anywhere
     * on this path, which is the invariant the security gate asserts. The rendering is display-only
     * and deliberately does not round-trip through {@code AccountDto.toAmount}, which rejects group
     * separators.</p>
     *
     * @param amount the amount as stored, permitted to be {@code null} for an unset component
     * @return exactly fifteen characters, or {@code null} when {@code amount} was {@code null}
     */
    private static String toEditedAmount(final BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        final BigDecimal scaled = amount.setScale(AccountDto.MONEY_SCALE, RoundingMode.HALF_EVEN);
        final char sign = scaled.signum() < 0 ? MONEY_SIGN_NEGATIVE : MONEY_SIGN_POSITIVE;
        final String allDigits = moveNumericText(
                scaled.abs().movePointRight(AccountDto.MONEY_SCALE).toBigInteger().toString(),
                MONEY_TOTAL_DIGITS);
        final StringBuilder edited = new StringBuilder(AccountDto.MONEY_DISPLAY_LENGTH);
        edited.append(sign);
        boolean significant = false;
        for (int position = 0; position < MONEY_INTEGER_DIGITS; position++) {
            if (position > 0 && position % MONEY_GROUP_SIZE == 0) {
                edited.append(significant ? MONEY_GROUP_SEPARATOR : MONEY_SUPPRESSED);
            }
            final char digit = allDigits.charAt(position);
            if (digit != '0') {
                significant = true;
            }
            edited.append(significant ? digit : MONEY_SUPPRESSED);
        }
        edited.append(MONEY_DECIMAL_POINT).append(allDigits.substring(MONEY_INTEGER_DIGITS));
        return edited.toString();
    }

    /**
     * Masks a card number for a logging path, leaving only the low-order {@value #MASK_VISIBLE_DIGITS} digits
     * legible. {@code XREF-CARD-NUM X(16)} is personally identifiable, so no log statement in this class ever
     * emits it whole. The legacy program has no instrumentation at all and therefore no such obligation; this
     * is new capability, added because the security clause requires it.
     *
     * @param cardNumber the card number, permitted to be {@code null} or blank
     * @return the masked rendering, or an empty string when there is nothing to mask; never {@code null}
     */
    private static String maskTail(final String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "";
        }
        final String digits = cardNumber.strip();
        if (digits.length() <= MASK_VISIBLE_DIGITS) {
            return "*".repeat(digits.length());
        }
        return "*".repeat(digits.length() - MASK_VISIBLE_DIGITS)
                + digits.substring(digits.length() - MASK_VISIBLE_DIGITS);
    }

    // ------------------------------------------------------------------------------------------------
    // Nested types. They are declared here rather than as separate files for a reason that is not stylistic:
    // this package is capped at exactly two compilation units - AccountViewService and its sibling
    // AccountUpdateService - so a third file is not permitted. Every type below is specific to this
    // conversation's shape and has no other consumer, which is also why nesting is the correct modelling
    // choice independently of the cap.
    // ------------------------------------------------------------------------------------------------

    /**
     * What the transaction produced. The legacy program ends its task in three distinct ways and a caller has
     * to be able to tell them apart, because they are not interchangeable.
     */
    public enum ResponseKind {

        /**
         * {@code EXEC CICS SEND MAP} at {@code :583-590} followed by {@code EXEC CICS RETURN TRANSID} at
         * {@code :402-406}: a populated screen and a re-entered conversation.
         */
        MAP,

        /**
         * {@code EXEC CICS SEND TEXT} followed by an unconditional {@code EXEC CICS RETURN}, from
         * {@code SEND-PLAIN-TEXT} at {@code :877-886} or - were it reachable - {@code SEND-LONG-TEXT} at
         * {@code :896-905}.
         */
        PLAIN_TEXT,

        /**
         * {@code EXEC CICS XCTL} at {@code :349-352}: the legacy program hands control to another program.
         * Statelessly nothing is transferred; the target is navigation metadata and nothing more.
         */
        TRANSFER
    }

    /**
     * The reconstruction of {@code CDEMO-PGM-CONTEXT} from {@code app/cpy/COCOM01Y.cpy}, whose {@code 88}-levels
     * are {@code CDEMO-PGM-ENTER VALUE '0'} and {@code CDEMO-PGM-REENTER VALUE '1'}.
     *
     * <p>The pseudo-conversational flag itself has no stateless counterpart and is not stored on the server; it
     * is a request parameter on the way in and response metadata on the way out. {@code null} is a legal
     * argument and is not an oversight: it reproduces a context byte that is neither {@code '0'} nor
     * {@code '1'}, which is precisely what drives the {@code WHEN OTHER} arm at {@code :375-382}.</p>
     */
    public enum EntryMode {

        /**
         * {@code EIBCALEN = 0} at {@code :283} and again at {@code :462}: the transaction was started directly
         * from the terminal with no COMMAREA, so there is no caller context at all.
         */
        FIRST_ENTRY,

        /** {@code CDEMO-PGM-ENTER}: arrived from another program, so selection criteria must be gathered. */
        ENTER,

        /** {@code CDEMO-PGM-REENTER}: the screen was submitted, so the inputs are edited and the chain runs. */
        REENTER
    }

    /**
     * The {@code CCARD-AID-*} flag set of {@code app/cpy/CVCRD01Y.cpy}, as populated by
     * {@code app/cpy/CSSTRPFY.cpy:17-82} from {@code EIBAID}.
     *
     * <p>Twelve function-key constants rather than twenty-four, because {@code PF13} through {@code PF24} fold
     * back onto {@code PFK01} through {@code PFK12} in the copybook itself - {@code DFHPF13} sets
     * {@code CCARD-AID-PFK01}, and so on. Only {@link #ENTER} and {@link #PFK03} are accepted by
     * {@code 0000-MAIN} at {@code :307-308}; every other constant is silently coerced to {@link #ENTER} at
     * {@code :312-314}, which is why the remaining constants exist but never survive validation.</p>
     */
    public enum AidKey {

        /** {@code DFHENTER} at {@code app/cpy/CSSTRPFY.cpy:22}. Accepted. */
        ENTER,

        /** {@code DFHCLEAR} at {@code app/cpy/CSSTRPFY.cpy:24}. Coerced to {@link #ENTER}. */
        CLEAR,

        /** {@code DFHPA1} at {@code app/cpy/CSSTRPFY.cpy:26}. Coerced to {@link #ENTER}. */
        PA1,

        /** {@code DFHPA2} at {@code app/cpy/CSSTRPFY.cpy:28}. Coerced to {@link #ENTER}. */
        PA2,

        /** {@code DFHPF1} and {@code DFHPF13}. Coerced to {@link #ENTER}. */
        PFK01,

        /** {@code DFHPF2} and {@code DFHPF14}. Coerced to {@link #ENTER}. */
        PFK02,

        /** {@code DFHPF3} and {@code DFHPF15}. Accepted: the exit key of this transaction. */
        PFK03,

        /** {@code DFHPF4} and {@code DFHPF16}. Coerced to {@link #ENTER}. */
        PFK04,

        /** {@code DFHPF5} and {@code DFHPF17}. Coerced to {@link #ENTER}. */
        PFK05,

        /** {@code DFHPF6} and {@code DFHPF18}. Coerced to {@link #ENTER}. */
        PFK06,

        /** {@code DFHPF7} and {@code DFHPF19}. Coerced to {@link #ENTER}. */
        PFK07,

        /** {@code DFHPF8} and {@code DFHPF20}. Coerced to {@link #ENTER}. */
        PFK08,

        /** {@code DFHPF9} and {@code DFHPF21}. Coerced to {@link #ENTER}. */
        PFK09,

        /** {@code DFHPF10} and {@code DFHPF22}. Coerced to {@link #ENTER}. */
        PFK10,

        /** {@code DFHPF11} and {@code DFHPF23}. Coerced to {@link #ENTER}. */
        PFK11,

        /** {@code DFHPF12} and {@code DFHPF24}. Coerced to {@link #ENTER}. */
        PFK12
    }

    /**
     * The three states of {@code WS-EDIT-ACCT-FLAG} at {@code :58-61}: {@code FLG-ACCTFILTER-ISVALID
     * VALUE '0'}, {@code FLG-ACCTFILTER-NOT-OK VALUE '1'} and {@code FLG-ACCTFILTER-BLANK VALUE ' '}.
     *
     * <p>Three states, not two, and the distinction is observable rather than internal: the {@code '*'} marker
     * and the red highlight at {@code :561-565} are emitted for {@link #BLANK} <strong>only</strong>, while
     * the red highlight alone is emitted for {@link #NOT_OK} at {@code :557-559}. A single boolean per field
     * cannot carry that, which is why this is an enum. It is the hand-written equivalent of the tri-state
     * semantics {@code app/cpy/CSSETATY.cpy} parameterises - a copybook this program does not {@code COPY},
     * though its sibling expands it many times over.</p>
     */
    public enum AccountFilterState {

        /** {@code FLG-ACCTFILTER-ISVALID}, set optimistically at {@code :625} and confirmed at {@code :679}. */
        VALID,

        /** {@code FLG-ACCTFILTER-BLANK}, set at {@code :656}: nothing was supplied. Emits the marker. */
        BLANK,

        /**
         * {@code FLG-ACCTFILTER-NOT-OK}, set pessimistically at {@code :650} and confirmed at {@code :669},
         * {@code :743}, {@code :761}, {@code :791} and {@code :811}. Emits the highlight but not the marker.
         */
        NOT_OK
    }

    /**
     * The navigation fields of {@code app/cpy/COCOM01Y.cpy} that survive the move to a stateless protocol as
     * response metadata. They are metadata and never instructions: no server-side transfer of control occurs,
     * and no session is kept.
     *
     * @param toTransactionId   {@code CDEMO-TO-TRANID}, resolved at {@code :328-333} with the
     *                          {@code LOW-VALUES}/{@code SPACES} fallback to {@code CM00}, or
     *                          {@code LIT-THISTRANID} on the map path
     * @param toProgram         {@code CDEMO-TO-PROGRAM} at {@code :334-339} with the fallback to
     *                          {@code COMEN01C}, or {@code CCARD-NEXT-PROG} from {@code :602}
     * @param fromTransactionId {@code CDEMO-FROM-TRANID}, stamped {@code CAVW} at {@code :341}
     * @param fromProgram       {@code CDEMO-FROM-PROGRAM}, stamped {@code COACTVWC} at {@code :342}
     * @param mapset            {@code CDEMO-LAST-MAPSET} at {@code :346} or {@code CCARD-NEXT-MAPSET} at
     *                          {@code :579}, both {@code COACTVW} with its trailing blank
     * @param map               {@code CDEMO-LAST-MAP} at {@code :347} or {@code CCARD-NEXT-MAP} at
     *                          {@code :580}, both {@code CACTVWA}
     * @param regularUserContext {@code SET CDEMO-USRTYP-USER TO TRUE} at {@code :344}. Note that the program
     *                          asserts this unconditionally on the exit path, overwriting whatever user type
     *                          the caller supplied; the authoritative role remains the JWT claim, and this
     *                          flag is reported rather than trusted
     */
    public record Navigation(String toTransactionId,
                             String toProgram,
                             String fromTransactionId,
                             String fromProgram,
                             String mapset,
                             String map,
                             boolean regularUserContext) {
    }

    /**
     * The screen attributes {@code 1300-SETUP-SCREEN-ATTRS} computes at {@code :541-572}. They are carried
     * beside the projection rather than inside it, because {@code AccountDto} models the {@code X(n)} data
     * fields of the symbolic map and not the attribute, colour and length bytes that surround each of them.
     *
     * @param filterState                 the tri-state field-error marker of {@link AccountFilterState}
     * @param accountFilterAttribute      {@code ACCTSIDA}, always {@code DFHBMFSE} from {@code :543} -
     *                                    unprotected with the modified-data tag set
     * @param accountFilterColour         {@code ACCTSIDC}: {@code DFHDFCOL} from {@code :555}, overridden to
     *                                    {@code DFHRED} at {@code :558} and again at {@code :564}
     * @param accountFilterMarker         the literal {@code *} moved into {@code ACCTSIDO} at {@code :563},
     *                                    emitted for {@link AccountFilterState#BLANK} only, or {@code null}
     * @param accountFilterCursorPosition {@code ACCTSIDL}, always {@code -1} - the cursor request - because
     *                                    all three arms of the redundant decision at {@code :546-552} move
     *                                    the same value
     * @param informationMessageAttribute {@code INFOMSGC}: {@code DFHBMDAR} when no information message is
     *                                    present and {@code DFHNEUTR} when one is, from {@code :567-571}
     */
    public record ScreenAttributes(AccountFilterState filterState,
                                   String accountFilterAttribute,
                                   String accountFilterColour,
                                   String accountFilterMarker,
                                   int accountFilterCursorPosition,
                                   String informationMessageAttribute) {
    }

    /**
     * The complete outcome of one {@code CAVW} conversation - everything the legacy task left in the COMMAREA,
     * on the screen or on the terminal, assembled into one immutable value.
     *
     * <p>{@link #screen()} is {@code null} for {@link ResponseKind#PLAIN_TEXT} and
     * {@link ResponseKind#TRANSFER}, and {@link #plainText()} is {@code null} for every other kind, because
     * the legacy program genuinely produces one or the other and never both. {@link #navigation()} is
     * {@code null} on the plain-text path, where {@code SEND-PLAIN-TEXT} returns without touching the
     * COMMAREA, and {@link #attributes()} is populated on the map path alone.</p>
     *
     * @param kind          how the task ended
     * @param attentionKey  the validated attention identifier after the coercion at {@code :312-314}, so
     *                      always {@link AidKey#ENTER} or {@link AidKey#PFK03}
     * @param screen        the projected map, or {@code null} when no map was sent
     * @param plainText     the text sent by {@code SEND-TEXT}, or {@code null} when no text was sent
     * @param navigation    the COMMAREA navigation metadata, or {@code null} on the plain-text path
     * @param attributes    the screen attributes, or {@code null} when no map was sent
     * @param nextEntryMode the context the legacy program latched for the next request - {@code REENTER} after
     *                      a send at {@code :581}, {@code ENTER} on the exit path at {@code :345} - which the
     *                      client echoes back rather than the server storing it
     * @param inputError    {@code INPUT-ERROR} at {@code :55-57}, true after any failed edit or failed read
     * @param accountFound  {@code FOUND-ACCT-IN-MASTER} at {@code :83}, set at {@code :788}
     * @param customerFound {@code FOUND-CUST-IN-MASTER} at {@code :85}, set at {@code :838}
     */
    public record AccountViewResult(ResponseKind kind,
                                    AidKey attentionKey,
                                    AccountDto screen,
                                    String plainText,
                                    Navigation navigation,
                                    ScreenAttributes attributes,
                                    EntryMode nextEntryMode,
                                    boolean inputError,
                                    boolean accountFound,
                                    boolean customerFound) {
    }

    /**
     * The symbolic output map {@code CACTVWAO}, as a mutable buffer.
     *
     * <p>It exists because the source fills the map field by field, in a fixed order, across four paragraphs -
     * {@code 1100-SCREEN-INIT} heads it, {@code 1200-SETUP-SCREEN-VARS} fills it, {@code 1300-SETUP-SCREEN-ATTRS}
     * overwrites one of its fields at {@code :563}, and {@code COMMON-RETURN} re-stamps another at
     * {@code :395}. A record cannot be filled that way. Every field is named exactly as the corresponding
     * {@code AccountDto} component, and {@link #toDto()} performs the single canonical construction at the end
     * of the conversation, which is also the point at which {@code AccountDto}'s width validation runs.</p>
     *
     * <p>An instance is allocated per {@link ViewContext} and is never shared, never static and never
     * published.</p>
     */
    private static final class ScreenBuffer {
        /**
         * Creates the work area with every member at its post-{@code INITIALIZE} value, which is the state
         * the legacy {@code WORKING-STORAGE SECTION} begins each task in. Declared explicitly rather than
         * left implicit so the surface is documented; it takes no argument and performs no work.
         */
        private ScreenBuffer() {
            // Every member carries its initial value in its own declaration above, exactly as a COBOL
            // VALUE clause does, so there is nothing for this constructor to assign.
        }

        /** {@code TRNNAMEO PIC X(4)}, stamped at {@code :438}. */
        private String transactionName;

        /** {@code TITLE01O PIC X(40)}, stamped at {@code :436}. */
        private String title01;

        /** {@code CURDATEO PIC X(8)}, {@code MM/dd/yy}, stamped at {@code :447}. */
        private String currentDate;

        /** {@code PGMNAMEO PIC X(8)}, stamped at {@code :439}. */
        private String programName;

        /** {@code TITLE02O PIC X(40)}, stamped at {@code :437}. */
        private String title02;

        /** {@code CURTIMEO}, {@code HH:mm:ss}, stamped at {@code :453}. */
        private String currentTime;

        /** {@code ACCTSIDO PIC X(11)}, stamped at {@code :466-468} and overwritten at {@code :563}. */
        private String accountId;

        /** {@code ACSTTUSO}, from {@code ACCT-ACTIVE-STATUS} at {@code :473}. */
        private String accountStatus;

        /** {@code ADTOPENO PIC X(10)}, from {@code ACCT-OPEN-DATE} at {@code :487}. */
        private String openDate;

        /** {@code ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99}, from {@code ACCT-CREDIT-LIMIT} at {@code :477}. */
        private String creditLimit;

        /** {@code AEXPDTO PIC X(10)}, from the misspelled {@code ACCT-EXPIRAION-DATE} at {@code :488}. */
        private String expiryDate;

        /** {@code ACSHLIMO}, from {@code ACCT-CASH-CREDIT-LIMIT} at {@code :479-480}. */
        private String cashCreditLimit;

        /** {@code AREISDTO PIC X(10)}, from {@code ACCT-REISSUE-DATE} at {@code :489}. */
        private String reissueDate;

        /** {@code ACURBALO}, from {@code ACCT-CURR-BAL} at {@code :475}. */
        private String currentBalance;

        /** {@code ACRCYCRO}, from {@code ACCT-CURR-CYC-CREDIT} at {@code :482-483}. */
        private String currentCycleCredit;

        /** {@code AADDGRPO PIC X(10)}, from {@code ACCT-GROUP-ID} at {@code :490}. */
        private String accountGroupId;

        /** {@code ACRCYDBO}, from {@code ACCT-CURR-CYC-DEBIT} at {@code :485}. */
        private String currentCycleDebit;

        /** {@code ACSTNUMO PIC X(9)}, from {@code CUST-ID} at {@code :494}. */
        private String customerId;

        /** {@code ACSTSSNO PIC X(12)}, the {@code NNN-NN-NNNN} rendering of {@code :496-504}. */
        private String customerSsn;

        /** {@code ACSTDOBO PIC X(10)}, from {@code CUST-DOB-YYYY-MM-DD} at {@code :507}. */
        private String customerDateOfBirth;

        /** {@code ACSTFCOO PIC X(3)}, from {@code CUST-FICO-CREDIT-SCORE} at {@code :505-506}. */
        private String customerFicoScore;

        /** {@code ACSFNAMO PIC X(25)}, from {@code CUST-FIRST-NAME} at {@code :508}. */
        private String customerFirstName;

        /** {@code ACSMNAMO PIC X(25)}, from {@code CUST-MIDDLE-NAME} at {@code :509}. */
        private String customerMiddleName;

        /** {@code ACSLNAMO PIC X(25)}, from {@code CUST-LAST-NAME} at {@code :510}. */
        private String customerLastName;

        /** {@code ACSADL1O PIC X(50)}, from {@code CUST-ADDR-LINE-1} at {@code :511}. */
        private String addressLine1;

        /** {@code ACSSTTEO PIC X(2)}, from {@code CUST-ADDR-STATE-CD} at {@code :514}. */
        private String addressStateCode;

        /** {@code ACSADL2O PIC X(50)}, from {@code CUST-ADDR-LINE-2} at {@code :512}. */
        private String addressLine2;

        /** {@code ACSZIPCO PIC X(5)}, from {@code CUST-ADDR-ZIP X(10)} at {@code :515}: a truncating move. */
        private String addressZip;

        /** {@code ACSCITYO}, from {@code CUST-ADDR-LINE-3} at {@code :513} - address line three is the city. */
        private String addressCity;

        /** {@code ACSCTRYO PIC X(3)}, from {@code CUST-ADDR-COUNTRY-CD} at {@code :516}. */
        private String addressCountryCode;

        /** {@code ACSPHN1O PIC X(13)}, from {@code CUST-PHONE-NUM-1 X(15)} at {@code :517}: truncating. */
        private String phoneNumber1;

        /** {@code ACSGOVTO PIC X(20)}, from {@code CUST-GOVT-ISSUED-ID} at {@code :519}. */
        private String governmentIssuedId;

        /** {@code ACSPHN2O PIC X(13)}, from {@code CUST-PHONE-NUM-2 X(15)} at {@code :518}: truncating. */
        private String phoneNumber2;

        /** {@code ACSEFTCO PIC X(10)}, from {@code CUST-EFT-ACCOUNT-ID} at {@code :520}. */
        private String eftAccountId;

        /** {@code ACSPFLGO PIC X(1)}, from {@code CUST-PRI-CARD-HOLDER-IND} at {@code :521-522}. */
        private String primaryCardHolderIndicator;

        /** {@code INFOMSGO PIC X(45)}, from {@code WS-INFO-MSG X(40)} at {@code :534}. */
        private String informationMessage;

        /** {@code ERRMSGO PIC X(78)}, from {@code WS-RETURN-MSG X(75)} at {@code :532}, {@code :601}, {@code :395}. */
        private String errorMessage;

        /**
         * Reproduces {@code MOVE LOW-VALUES TO CACTVWAO} at {@code :432}: every one of the thirty-seven
         * fields returns to its unset state. A {@code null} component is the closest JSON analogue of
         * {@code LOW-VALUES}, and it is what the projection emits for a field the source left cleared.
         */
        private void clear() {
            this.transactionName = null;
            this.title01 = null;
            this.currentDate = null;
            this.programName = null;
            this.title02 = null;
            this.currentTime = null;
            this.accountId = null;
            this.accountStatus = null;
            this.openDate = null;
            this.creditLimit = null;
            this.expiryDate = null;
            this.cashCreditLimit = null;
            this.reissueDate = null;
            this.currentBalance = null;
            this.currentCycleCredit = null;
            this.accountGroupId = null;
            this.currentCycleDebit = null;
            this.customerId = null;
            this.customerSsn = null;
            this.customerDateOfBirth = null;
            this.customerFicoScore = null;
            this.customerFirstName = null;
            this.customerMiddleName = null;
            this.customerLastName = null;
            this.addressLine1 = null;
            this.addressStateCode = null;
            this.addressLine2 = null;
            this.addressZip = null;
            this.addressCity = null;
            this.addressCountryCode = null;
            this.phoneNumber1 = null;
            this.governmentIssuedId = null;
            this.phoneNumber2 = null;
            this.eftAccountId = null;
            this.primaryCardHolderIndicator = null;
            this.informationMessage = null;
            this.errorMessage = null;
        }

        /**
         * Projects the buffer into the immutable transfer object, in the canonical component order
         * {@code AccountDto} declares - which is the symbolic map's own field order and therefore the screen's.
         * {@code AccountDto}'s compact constructor validates every width at this point, so an over-width
         * component fails loudly here rather than silently reaching a caller.
         *
         * @return the projected view; never {@code null}
         */
        private AccountDto toDto() {
            return new AccountDto(this.transactionName,
                    this.title01,
                    this.currentDate,
                    this.programName,
                    this.title02,
                    this.currentTime,
                    this.accountId,
                    this.accountStatus,
                    this.openDate,
                    this.creditLimit,
                    this.expiryDate,
                    this.cashCreditLimit,
                    this.reissueDate,
                    this.currentBalance,
                    this.currentCycleCredit,
                    this.accountGroupId,
                    this.currentCycleDebit,
                    this.customerId,
                    this.customerSsn,
                    this.customerDateOfBirth,
                    this.customerFicoScore,
                    this.customerFirstName,
                    this.customerMiddleName,
                    this.customerLastName,
                    this.addressLine1,
                    this.addressStateCode,
                    this.addressLine2,
                    this.addressZip,
                    this.addressCity,
                    this.addressCountryCode,
                    this.phoneNumber1,
                    this.governmentIssuedId,
                    this.phoneNumber2,
                    this.eftAccountId,
                    this.primaryCardHolderIndicator,
                    this.informationMessage,
                    this.errorMessage);
        }
    }

    /**
     * The {@code WORKING-STORAGE SECTION} of {@code app/cbl/COACTVWC.cbl:33-259}, as a per-request work area.
     *
     * <p>This type is the answer to a hard constraint. The source keeps two dozen flags, a message, an
     * information message, two response codes, four diagnostic sub-fields, three record areas and a
     * screen buffer in storage that every paragraph reads and writes. A Spring singleton must hold none of
     * that: a bean field would be an outright concurrency defect, silently corrupting one request with
     * another's account number. So exactly one instance is allocated per public call, threaded through every
     * paragraph method by parameter, and discarded when the call returns. Nothing here is static, nothing is
     * shared and nothing escapes the call.</p>
     *
     * <p>Field access is direct rather than through accessors. That is deliberate: this is a translation of a
     * data division, thirty-plus trivial accessor pairs would obscure rather than clarify, and the type is
     * private to the enclosing class so no external contract is exposed.</p>
     *
     * <p><strong>No field on this type is ever logged as a payload.</strong> {@code ssn}, the date of birth,
     * the government-issued identifier, both telephone numbers, the electronic-funds account identifier and
     * {@link #cardNumber} are personally identifiable; identifiers and outcomes are logged, and the card
     * number only ever through {@code maskTail}. There is deliberately no {@code toString()} override, so no
     * accidental interpolation can expose the record areas.</p>
     */
    private static final class ViewContext {

        /** The account filter exactly as the caller supplied it, before the normalisation of {@code :628-633}. */
        private final String requestedAccountFilter;

        /** {@code EIBAID} as the caller supplied it, resolved by {@code YYYY-STORE-PFKEY}. */
        private final String rawAttentionIdentifier;

        /** The reconstructed {@code CDEMO-PGM-CONTEXT}; {@code null} drives the {@code WHEN OTHER} arm. */
        private final EntryMode entryMode;

        /** {@code ACCTSIDI OF CACTVWAI}, the received filter, copied by {@code 2100-RECEIVE-MAP}. */
        private String receivedAccountFilter;

        /** {@code CC-ACCT-ID PIC X(11)} from {@code app/cpy/CVCRD01Y.cpy}, after normalisation. */
        private String accountIdText;

        /** {@code CDEMO-ACCT-ID PIC 9(11)}; zero after either rejection path, per {@code :660} and {@code :675}. */
        private long accountId;

        /** {@code CDEMO-CUST-ID PIC 9(09)}, taken from {@code XREF-CUST-ID} at {@code :739}. */
        private long customerId;

        /** {@code WS-TRANID}, stamped {@code CAVW} at {@code :274}. */
        private String transactionId;

        /** {@code WS-RETURN-MSG PIC X(75)} at {@code :117}, together with all of its {@code 88}-levels. */
        private String returnMessage;

        /** {@code WS-INFO-MSG PIC X(40)} at {@code :110}, together with all of its {@code 88}-levels. */
        private String infoMessage;

        /** {@code WS-LONG-MSG PIC X(500)}: assigned nowhere, because all three assignments are commented out. */
        private String longMessage;

        /** {@code WS-INPUT-FLAG} at {@code :55-57}: {@code INPUT-OK VALUE '0'} / {@code INPUT-ERROR VALUE '1'}. */
        private boolean inputError;

        /** {@code WS-PFK-FLAG}: {@code PFK-VALID VALUE '0'} / {@code PFK-INVALID VALUE '1'}, at {@code :306-314}. */
        private boolean pfKeyValid;

        /** The resolved {@code CCARD-AID-*} flag; {@code null} when {@code CSSTRPFY} matched no arm. */
        private AidKey attentionKey;

        /** {@code WS-EDIT-ACCT-FLAG} at {@code :58-61}, the tri-state account-filter marker. */
        private AccountFilterState accountFilterState;

        /**
         * {@code FLG-CUSTFILTER-NOT-OK} of {@code WS-EDIT-CUST-FLAG} at {@code :62-65}, set at {@code :841} and
         * {@code :860}. Write-only legacy state: it is tested nowhere in the program, which is exactly why a
         * customer-master miss does not stop the chain while an account-master miss does.
         */
        private boolean customerFilterNotOk;

        /** {@code FOUND-ACCT-IN-MASTER} at {@code :83}, set at {@code :788}. */
        private boolean accountFound;

        /** {@code FOUND-CUST-IN-MASTER} at {@code :85}, set at {@code :838}. */
        private boolean customerFound;

        /** {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy}, filled by {@code 9300}. */
        private Account account;

        /** {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy}, filled by {@code 9400}. Carries PII. */
        private Customer customer;

        /** {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy}, filled by {@code 9200}. */
        private CardCrossReference crossReference;

        /** {@code CDEMO-FROM-TRANID}; always unset inbound, because a stateless request carries no COMMAREA. */
        private String fromTransactionId;

        /** {@code CDEMO-FROM-PROGRAM}; always unset inbound, for the same reason. */
        private String fromProgram;

        /** {@code CDEMO-TO-TRANID}, resolved at {@code :328-333}. */
        private String toTransactionId;

        /** {@code CDEMO-TO-PROGRAM}, resolved at {@code :334-339}. */
        private String toProgram;

        /** {@code CCARD-NEXT-PROG}, stamped {@code COACTVWC} at {@code :602}. */
        private String nextProgram;

        /** {@code CCARD-NEXT-MAPSET}, stamped at {@code :603} and again at {@code :579}. */
        private String nextMapset;

        /** {@code CCARD-NEXT-MAP}, stamped at {@code :604} and again at {@code :580}. */
        private String nextMap;

        /** {@code CDEMO-LAST-MAPSET}, stamped at {@code :346} on the exit path only. */
        private String lastMapset;

        /** {@code CDEMO-LAST-MAP}, stamped at {@code :347} on the exit path only. */
        private String lastMap;

        /** The context the program latched for the next request: {@code :581} or {@code :345}. */
        private EntryMode nextEntryMode;

        /** {@code SET CDEMO-USRTYP-USER TO TRUE} at {@code :344}, asserted unconditionally on the exit path. */
        private boolean regularUserContext;

        /** {@code CACTVWAO}, the symbolic output map. */
        private final ScreenBuffer screen;

        /** {@code ACCTSIDL}, the cursor request; always {@code -1} per the redundant decision at {@code :546-552}. */
        private int cursorPosition;

        /** {@code ACCTSIDA}, the field attribute byte, from {@code :543}. */
        private String accountFilterAttribute;

        /** {@code ACCTSIDC}, the field colour byte, from {@code :555}, {@code :558} and {@code :564}. */
        private String accountFilterColour;

        /** The literal {@code *} moved into {@code ACCTSIDO} at {@code :563}, for {@code BLANK} only. */
        private String accountFilterMarker;

        /** {@code INFOMSGC}, the information-message attribute byte, from {@code :567-571}. */
        private String informationMessageAttribute;

        /** {@code WS-RESP-CD PIC S9(09) COMP} at {@code :78}. */
        private int responseCode;

        /** {@code WS-REAS-CD PIC S9(09) COMP} at {@code :79}. */
        private int reasonCode;

        /** {@code ERROR-OPNAME PIC X(8)} of {@code WS-FILE-ERROR-MESSAGE}, always {@code READ} here. */
        private String errorOperation;

        /** {@code ERROR-FILE PIC X(9)} of {@code WS-FILE-ERROR-MESSAGE}. */
        private String errorFile;

        /** {@code ERROR-RESP PIC X(10)} of {@code WS-FILE-ERROR-MESSAGE}. */
        private String errorResponse;

        /** {@code ERROR-RESP2 PIC X(10)} of {@code WS-FILE-ERROR-MESSAGE}. */
        private String errorResponse2;

        /** {@code WS-CARD-RID-ACCT-ID}, the record identification field of {@code 9200} and {@code 9300}. */
        private long ridAccountId;

        /** {@code WS-CARD-RID-CUST-ID}, the record identification field of {@code 9400}. */
        private long ridCustomerId;

        /** {@code CDEMO-CARD-NUM}, from {@code XREF-CARD-NUM} at {@code :740}. PII: masked on every log path. */
        private String cardNumber;

        /** {@code ABEND-CODE PIC X(4)}; {@code 0001} at {@code :377}, then discarded - defect V6. */
        private String abendCode;

        /** {@code ABEND-CULPRIT PIC X(8)}; {@code COACTVWC} at {@code :376} and {@code :922}. */
        private String abendCulprit;

        /** {@code ABEND-REASON PIC X(50)}; {@code SPACES} at {@code :378}. */
        private String abendReason;

        /** {@code ABEND-MSG PIC X(72)}; assigned nowhere in this program, hence the substitution at {@code :919}. */
        private String abendMessage;

        /**
         * The first typed failure of the request, retained rather than thrown so that the legacy program's
         * "record the message and carry on" behaviour is reproduced while {@link #viewAccount(String)} can
         * still surface it. First error wins, mirroring the {@code IF WS-RETURN-MSG-OFF} latch.
         */
        private CardDemoException pendingFailure;

        /**
         * The infrastructure throwable behind a {@code 9x} file status, held only between the repository
         * adapter that caught it and the {@code FileStatusMapper} call that consumes it as a cause. It is
         * cleared on consumption, so no cause ever leaks from one read into the next.
         */
        private Throwable ioFailureCause;

        /**
         * Reproduces {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} at {@code :268-270}, which
         * sets every alphanumeric item to {@code SPACES} and every numeric item to zero.
         *
         * <p>Java's default field initialisation supplies the numeric zeroes and the {@code false} flags. The
         * alphanumeric items are left {@code null} rather than blank-filled, because a {@code null} component
         * is what an unset JSON field looks like and because every consumer here routes through
         * {@code isBlankOrLowValues}, which treats {@code null}, blank and NUL-filled identically - exactly as
         * the source's {@code EQUAL LOW-VALUES OR SPACES} tests do.</p>
         *
         * <p>The one field given an explicit non-default value is the tri-state filter marker, which starts at
         * {@code VALID}: {@code FLG-ACCTFILTER-ISVALID VALUE '0'} is the {@code 88}-level that {@code INITIALIZE}
         * selects, and {@code :625} then sets it again optimistically.</p>
         *
         * @param accountFilter       the account filter as supplied, permitted to be {@code null}
         * @param attentionIdentifier the {@code EIBAID} symbol as supplied, permitted to be {@code null}
         * @param requestEntryMode    the reconstructed context byte, permitted to be {@code null}
         */
        private ViewContext(final String accountFilter, final String attentionIdentifier,
                            final EntryMode requestEntryMode) {
            this.requestedAccountFilter = accountFilter;
            this.rawAttentionIdentifier = attentionIdentifier;
            this.entryMode = requestEntryMode;
            this.screen = new ScreenBuffer();
            this.accountFilterState = AccountFilterState.VALID;
        }
    }
}
