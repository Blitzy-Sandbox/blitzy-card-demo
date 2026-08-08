/*
 * ******************************************************************
 * Program     : CardUpdateService.java
 * Application : CardDemo
 * Type        : Spring Service Bean (online)
 * Function    : Accept and process credit card update request.
 * Source      : app/cbl/COCRDUPC.cbl (1,560 lines, 45 own / 47 mapped paragraph labels) @ 7756d89
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

package com.cardemo.service.card;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Credit card update, migrated one-for-one from the frozen COBOL program
 * {@code app/cbl/COCRDUPC.cbl} (1,560 lines, 45 own / 47 mapped paragraph labels) at traceability anchor
 * {@code 7756d89}. The legacy program fronts CICS transaction {@code CCUP}
 * ({@code app/csd/CARDDEMO.CSD:367-369} defines {@code TRANSACTION(CCUP)} with
 * {@code PROGRAM(COCRDUPC)}; {@code :227} defines the program itself as "CREDIT CARD UPDATE
 * SCREEN"), and this bean is surfaced by {@code com.cardemo.controller.CardController} under
 * {@code /api/cards/*}.
 *
 * <h2>What it does</h2>
 * <p>Four responsibilities, in the order the source performs them.</p>
 * <ol>
 *   <li><strong>Two-phase edit.</strong> {@code 1200-EDIT-MAP-INPUTS} at {@code :641} branches on
 *       whether the card has already been fetched. The first request validates only the two search
 *       keys and then jumps to the paragraph exit at {@code :661}, so <em>none</em> of the four field
 *       edits runs on a fetch. Only a second request, arriving with a snapshot, runs
 *       {@code 1230-EDIT-NAME} through {@code 1260-EDIT-EXPIRY-YEAR}.</li>
 *   <li><strong>Snapshot change detection.</strong> Three independent concurrency regimes, none of
 *       which substitutes for the others. See <em>Concurrency</em> below.</li>
 *   <li><strong>Confirm-then-write.</strong> {@code 2000-DECIDE-ACTION} at {@code :948} only reaches
 *       {@code 9200-WRITE-PROCESSING} when the edits validated <em>and</em> the caller supplied the
 *       {@code PF05} confirmation intent ({@code :988-991}). An unconfirmed request falls through to
 *       the duplicated, unguarded branch at {@code :1006} and merely redisplays.</li>
 *   <li><strong>Single-dataset rewrite.</strong> {@code 9200} reads the base cluster for update at
 *       {@code :1427-1428} and rewrites it at {@code :1478}. One dataset, one row, no second write.</li>
 *   </ol>
 *
 * <h2>How to build, run and test</h2>
 * <p>Java 25 and Maven 3.9.11, driven through the repository wrapper. Source and target level come
 * from the {@code maven.compiler.release} property of {@code pom.xml}, set to 25; the compiler runs
 * with that file's {@code <arg>-Xlint:all</arg>}, {@code <arg>-Werror</arg>} and
 * {@code <failOnWarning>} settings, so any warning in this file fails the build.</p>
 * <pre>
 * ./mvnw -B -ntp clean compile
 * ( set -a; . ./.env; set +a; ./mvnw -B -ntp test )
 * </pre>
 * <p>Compilation reads no environment value; only a command that starts a context does. The subshell
 * parentheses confine the exported values to that one command rather than leaving every later child of the
 * shell inheriting them, and the file must already be at mode {@code 0600}.</p>
 * <p>Unit tests for this bean live in {@code src/test/java/com/cardemo/unit/service/} and are owned
 * by a different agent; this file creates none. The JaCoCo gate is a merged 80% LINE floor - the
 * {@code jacoco.line.coverage.minimum} property of {@code pom.xml} and the {@code <haltOnFailure>}
 * {@code <limit>} it feeds - and no exclusion is added for this class. Nothing here
 * performs hidden I/O, holds static mutable state, or captures a clock or identifier statically, so
 * every branch is reachable from the injected {@code CardRepository} and {@code java.time.Clock}.</p>
 *
 * <h2>Key configuration and defaults</h2>
 * <p><strong>This service binds no configuration property of its own.</strong> That is a deliberate
 * statement rather than an omission: {@code COCRDUPC} does not paginate, so it has no analogue of the
 * {@code carddemo.pagination.card-list-page-size} value (7) that {@code application.yml} supplies to
 * the card <em>list</em> path. There is no {@code @Value}, no {@code @ConfigurationProperties} and no
 * tunable here.</p>
 * <p>What this bean consumes from elsewhere:</p>
 * <ul>
 *   <li>Entity scanning and transaction management belong to {@code JpaConfig}. This class declares a
 *       method-level {@code @Transactional} boundary on the write path, which is a method concern, but
 *       never {@code @EnableTransactionManagement}.</li>
 *   <li>Message and numeric converters belong to {@code WebConfig}; the security filter chain and role
 *       mapping belong to {@code SecurityConfig}. Neither is redeclared here, and this file carries no
 *       {@code @ControllerAdvice}, {@code @ExceptionHandler} or {@code @ResponseStatus}.</li>
 *   <li>{@code spring.jpa.open-in-view} is {@code false} in {@code application.yml}. That is safe
 *       because {@code Card.accountId} is a plain scalar {@code Long} rather than a
 *       {@code @ManyToOne}, so no lazy association is ever touched outside the transaction.</li>
 *   <li>A {@code java.time.Clock} bean must be present in the context. This is a pre-existing
 *       requirement of the tree, not a new one: {@code AccountViewService} and
 *       {@code TransactionDetailService} both constructor-inject one for the same reason. It stands in
 *       for {@code MOVE FUNCTION CURRENT-DATE} at {@code :1055} and {@code :1062}. Production supplies the
 *       one bean {@code com.cardemo.config.ObservabilityConfig#clock(String)} publishes, a system clock in the
 *       deployment's own zone - region-local rather than UTC, because the intrinsic it replaces returned the
 *       region's local civil time - which {@code carddemo.time.zone} can pin when a run has to reproduce a
 *       baseline captured elsewhere; tests inject {@code Clock.fixed(...)}.</li>
 *   <li>Trace, span and correlation identifiers reach the log encoder through the MDC populated by
 *       {@code CorrelationIdFilter}. No metric is registered here; {@code MetricsConfig} owns the four
 *       named counters.</li>
 *   </ul>
 *
 * <h2>Concurrency: three regimes, all three required</h2>
 * <ul>
 *   <li><strong>Regime A, "did the caller change anything?"</strong> {@code :680-683} compares
 *       {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA)} against
 *       {@code FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)} as one 59-byte group: embossed name (50),
 *       expiry year (4), month (2), day (2) and status (1). The carried day therefore
 *       <em>does</em> participate, while the verification value, account id and card number sit
 *       outside the group and do not. Reproduced by
 *       {@link #renderCardDataGroup} plus
 *       {@code toUpperCase(Locale.ROOT)}; {@code Locale.ROOT} is mandatory because
 *       {@code LIT-ALL-ALPHA-FROM} at {@code :255-257} is the 52 ASCII letters and nothing else, so a
 *       Turkish default locale would mis-fold {@code i}.</li>
 *   <li><strong>Regime B, "did the stored row change under us?"</strong>
 *       {@code 9300-CHECK-CHANGE-IN-REC} at {@code :1498-1521} evaluates <em>six</em> ANDed predicates
 *       against the snapshot, and compares the expiry date as three separate substrings at offsets
 *       {@code (1:4)}, {@code (6:2)} and {@code (9:2)} rather than as one string. A whole-string
 *       comparison is wrong and is not used.</li>
 *   <li><strong>Regime C, the store-level guard.</strong> JPA {@code @Version} on
 *       {@code Card.version}.</li>
 *   </ul>
 * <p><strong>{@code @Version} alone is insufficient.</strong> It detects <em>that</em> a row changed;
 * the source detects <em>which field values</em> differ from what the caller was shown. A concurrent
 * write that restored a value to its original passes the legacy check and fails a version check, so
 * both layers are carried.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <table border="1">
 *   <caption>Outcomes, their source site, and the exception raised</caption>
 *   <tr><th>Condition</th><th>Source</th><th>Outcome</th></tr>
 *   <tr><td>Account filter null, {@code "*"}, spaces or zeros</td><td>{@code :722-732}</td>
 *       <td>{@code ValidationException}, field {@code accountId}, {@code BLANK}</td></tr>
 *   <tr><td>Account filter not 11 numeric digits</td><td>{@code :735-746}</td>
 *       <td>{@code ValidationException}, field {@code accountId}, {@code INVALID}</td></tr>
 *   <tr><td>Card filter null, {@code "*"}, spaces or zeros</td><td>{@code :768-780}</td>
 *       <td>{@code ValidationException}, field {@code cardNumber}, {@code BLANK}</td></tr>
 *   <tr><td>Card filter not 16 numeric digits</td><td>{@code :783-794}</td>
 *       <td>{@code ValidationException}, field {@code cardNumber}, {@code INVALID}</td></tr>
 *   <tr><td>Both filters blank</td><td>{@code :656-659}</td>
 *       <td>{@code ValidationException}, "No input received"</td></tr>
 *   <tr><td>Embossed name blank</td><td>{@code :811-820}</td>
 *       <td>{@code ValidationException}, {@code BLANK}</td></tr>
 *   <tr><td>Embossed name holds a non-letter, non-space character</td><td>{@code :822-837}</td>
 *       <td>{@code ValidationException}, {@code INVALID}</td></tr>
 *   <tr><td>Status blank <em>or</em> not {@code Y}/{@code N}</td><td>{@code :850-872}</td>
 *       <td>{@code ValidationException}; one shared literal for both</td></tr>
 *   <tr><td>Expiry month blank <em>or</em> outside 1-12</td><td>{@code :883-907}</td>
 *       <td>{@code ValidationException}; one shared literal for both</td></tr>
 *   <tr><td>Expiry year blank <em>or</em> outside 1950-2099</td><td>{@code :916-943}</td>
 *       <td>{@code ValidationException}; one shared literal for both</td></tr>
 *   <tr><td>Card row absent</td><td>{@code :1395} {@code DFHRESP(NOTFND)}</td>
 *       <td>{@code RecordNotFoundException}</td></tr>
 *   <tr><td>Could not lock for update</td><td>{@code :1441-1449}</td>
 *       <td>{@code ConcurrentUpdateException}, {@code COULD_NOT_LOCK_ACCOUNT}</td></tr>
 *   <tr><td>Stored row changed since display</td><td>{@code :1498-1521}</td>
 *       <td>{@code ConcurrentUpdateException}, {@code DATA_CHANGED_BEFORE_UPDATE}</td></tr>
 *   <tr><td>Rewrite failed after the lock succeeded</td><td>{@code :1488-1492}</td>
 *       <td>{@code ConcurrentUpdateException}, {@code LOCKED_BUT_UPDATE_FAILED}</td></tr>
 *   <tr><td>Edits valid but confirmation not yet given</td><td>{@code :710-714}, {@code :1006}</td>
 *       <td>{@code ConcurrentUpdateException}, {@code CHANGES_NOT_CONFIRMED}</td></tr>
 *   <tr><td>Unexpected state</td><td>{@code :1019-1026}</td>
 *       <td>{@code FatalProcessingException}, code {@code 0001}, culprit {@code COCRDUPC}</td></tr>
 *   <tr><td>Data-store failure on any file verb</td><td>{@code :1402-1412}</td>
 *       <td>{@code FileAccessException} carrying the composed {@code File Error: } text</td></tr>
 *   </table>
 * <p><strong>The three write outcomes must stay distinguishable.</strong> Collapsing
 * {@code COULD_NOT_LOCK_FOR_UPDATE}, {@code DATA_WAS_CHANGED_BEFORE_UPDATE} and
 * {@code LOCKED_BUT_UPDATE_FAILED} into one generic conflict response would be a
 * regression: {@code 2000-DECIDE-ACTION} routes each to a different next state at {@code :993-1000},
 * and only the middle one is recoverable by redisplaying refreshed data rather than terminal.</p>
 * <p>Troubleshooting notes. A startup failure naming {@code Clock} means the context has no
 * {@code Clock} bean; that is a configuration gap, not a defect here. A rejected update that reports
 * "Record changed by some one else. Please review" when nothing visibly changed usually means the
 * caller replayed a stale sealed snapshot, because Regime B compares the snapshot against
 * the live row rather than comparing versions. An update that reports "Card name can only contain
 * alphabets and spaces" for an accented character is correct behaviour, not a bug: the source alphabet
 * at {@code :255-257} is ASCII-only.</p>
 *
 * <h2>Deviations from the source, and preserved source behaviours</h2>
 * <ol>
 *   <li><strong>The legacy quirk that destroyed card verification data is not reproduced.</strong>
 *       {@code CCUP-NEW-CVV-CD} is declared at {@code :306} inside the inline {@code CCUP-NEW-DETAILS}
 *       group, is set to {@code SPACES} by {@code INITIALIZE CCUP-NEW-DETAILS} at {@code :586}, and is then
 *       <em>read</em> at {@code :1464} into the record that {@code :1478} rewrites. It is never assigned
 *       anywhere in the program, which a census of the two occurrences of the name - the declaration and
 *       that read - confirms, and {@code app/cpy-bms/COCRDUP.CPY} carries no verification-value field
 *       among its seventeen inputs, so the screen cannot supply one. The legacy rewrite therefore wrote
 *       three spaces over the stored card verification value on every successful update.
 *       <p><strong>The value is stored here.</strong> {@code V1__create_schema.sql} declares
 *       {@code card_cvv_cd CHAR(3) NOT NULL}, {@link com.cardemo.model.entity.Card} maps it, and
 *       {@code V3__seed_data.sql}'s {@code card} insert loads it. What is withheld is the
 *       <em>read path</em>, not the
 *       column: the entity field is write-once with no getter of any visibility, which
 *       {@code V1__create_schema.sql} records, in its {@code card_cvv_cd IS DECLARED} comment, as
 *       the resolution of a High-severity finding. Both
 *       {@code MOVE}s at {@code :1464-1465} are therefore deliberately absent, because reproducing them
 *       would overwrite live authentication data with spaces - indefensible under Rule 1 Clause D - and
 *       because there is no read path by which their operand could be obtained in the first place. This is
 *       a deliberate, labelled deviation from byte-level parity: a successful update here leaves the stored
 *       verification value intact, where the source destroyed it.</p>
 *       <p>{@link CardUpdateRequest.CardDetails} correspondingly declares no verification component, and
 *       that is a narrowing rather than a gap: no symbolic map declares such a field, so no client could
 *       supply one, and accepting one over the wire would be the same retention problem one hop earlier.
 *       The change-detection consequence is the five-predicate comparison described below.</p></li>
 *   <li><strong>Mechanism substitution, not a behaviour change.</strong> A single
 *       {@code @Transactional(rollbackFor = Exception.class)} method reproduces the source's rollback
 *       semantics by scoping. {@code 9200-WRITE-PROCESSING} touches one dataset and contains
 *       <em>no</em> rollback verb, so there is no asymmetric-rollback branch here, unlike
 *       {@code COACTUPC}'s dual-dataset write. A reviewer should not go looking for a missing
 *       {@code SYNCPOINT ROLLBACK}. The program's lone {@code EXEC CICS SYNCPOINT} at {@code :470}
 *       sits in the navigation branch immediately before the {@code XCTL} at {@code :473} and belongs
 *       to the transfer path, not to the write.</li>
 *   <li><strong>Two-layer concurrency is mandatory.</strong> Relying on
 *       {@code @Version} alone would silently accept a concurrent write that restored a value, which
 *       the source rejects, and omitting it would miss a concurrent write that Regime B cannot see.
 *       Both layers are therefore load-bearing; see <em>Concurrency</em> above for the full
 *       derivation. Getting this wrong is not cosmetic: an unset snapshot field makes Regime B's
 *       predicate fail unconditionally and no update can ever complete, which is exactly the failure
 *       recorded in the {@code CCUP-OLD-CVV-CD} entry below.</li>
 *   <li><strong>The snapshot travels in the request.</strong>
 *       {@code CCUP-OLD-DETAILS} at
 *       {@code :291-301} and {@code CCUP-NEW-DETAILS} at {@code :303-313} are declared inline in the
 *       program and appear in no copybook, so there is no copybook-derived carrier for them.
 *       {@code CardUpdateRequest} carries the old group as the sealed {@code snapshot} member and the
 *       edited group as {@code newDetails}, and its own documentation records why the {@code CardData}
 *       nesting is load-bearing. The old snapshot is therefore relayed by the caller and is
 *       <strong>never</strong> derived from the live entity - deriving it would make Regime B
 *       tautologically true and destroy the guard - and it is sealed, so relaying is the only thing a
 *       caller can do with it.</li>
 *   <li><strong>{@code EXPDAYI} is the only screen field not normalised.</strong>
 *       {@code 1100-RECEIVE-MAP} replaces {@code "*"} or spaces with low values for six fields, but
 *       {@code :621} is a bare {@code MOVE EXPDAYI OF CCRDUPAI TO CCUP-NEW-EXPDAY} with no such
 *       handling, so the expiry day is accepted raw. A uniform loop over all seven fields would be
 *       wrong.</li>
 *   <li><strong>There is no {@code 1270-EDIT-EXPIRY-DAY}.</strong> The expiry day is
 *       accepted at {@code :621} and written at {@code :1471}, but is never range-checked,
 *       numeric-checked or blank-checked, and is always echoed back as the <em>old</em> value
 *       ({@code :1110}, {@code :1123}, {@code :1127}). The {@code MOVE CCUP-NEW-EXPDAY} at
 *       {@code :1122} is commented out in the source and is not resurrected here. Corroborated by
 *       {@code 3300} making the field non-display via an unconditional
 *       {@code MOVE DFHBMDAR TO EXPDAYC} at {@code :1277}, and by {@code CardDto} having no expiry-day
 *       component at all. No validation the source lacks is added, and in particular no
 *       self-consistency check between month, year and the carried day.</li>
 *   <li><strong>{@code 1220} clears the new card id to two different values.</strong>
 *       The blank branch moves ZEROES at {@code :777-778}; the not-numeric branch moves LOW-VALUES at
 *       {@code :793}. Both are reproduced distinctly, and {@code null}, {@code ""} and {@code "0"} are
 *       never coerced into one another.</li>
 *   <li><strong>{@code 1260} orders its flags differently from its siblings, and its comment is
 *       wrong.</strong> {@code 1240} and {@code 1250} set the pessimistic not-ok state before the blank
 *       test ({@code :847}, {@code :880}); {@code 1260} sets it <em>after</em>, at {@code :930}, so the
 *       blank branch sets only the blank state. Because these are condition names over one flag byte
 *       the resulting state is identical, but the statement order is transcribed as written rather than
 *       harmonised. Separately, the comment at {@code :927-928} reads "Must be 1 to 12", copy-pasted
 *       from the month paragraph; the code validates 1950-2099.</li>
 *   <li><strong>One message literal serves both the blank and the invalid
 *       outcome</strong> in {@code 1240}, {@code 1250} and {@code 1260}. No second message is
 *       invented.</li>
 *   <li><strong>Medium, implementation hazard. {@code 2000-DECIDE-ACTION} is order-dependent.</strong>
 *       {@code WHEN CCUP-CHANGES-OK-NOT-CONFIRMED} appears twice: guarded by {@code PF05} at
 *       {@code :988-989}, then unguarded at {@code :1006}. The guarded form must be tested first or the
 *       save can never fire. This is not a defect; it is how COBOL {@code EVALUATE TRUE} works. The
 *       Java branch order matches, and reordering it would silently disable the write path.</li>
 *   <li><strong>The source upper-cases its own record buffer in place, twice.</strong>
 *       {@code 9000-READ-DATA} runs {@code INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO
 *       LIT-UPPER} at {@code :1356-1358} <em>before</em> snapshotting the name, and {@code 9300} runs
 *       the same conversion again at {@code :1499-1501} before comparing. Both sides of the Regime B
 *       name predicate are consequently already folded. The effect is reproduced without mutating any
 *       shared buffer. On mismatch {@code 9300} additionally refreshes all six snapshot fields from the
 *       live record at {@code :1512-1517} and then performs a cross-paragraph
 *       {@code GO TO 9200-WRITE-PROCESSING-EXIT} at {@code :1518}, jumping into its caller's exit
 *       label; that is modelled as a distinct returned outcome. The source's malformed
 *       {@code END-IF EXIT} at {@code :1519} has no Java counterpart.</li>
 *   <li><strong>Nine declared data items are never referenced and become no Java member.</strong>
 *       {@code WS-LONG-MSG PIC X(500)} at {@code :156} has no reference anywhere in the program, and
 *       {@code LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} at {@code :253} is never used by
 *       any file verb. A further seven are {@code WS-RETURN-MSG} condition names that no statement ever
 *       sets, verified by counting {@code SET} sites: {@code WS-EXIT-MESSAGE} ({@code :175}),
 *       {@code SEARCHED-ACCT-ZEROES} ({@code :189}), {@code SEARCHED-ACCT-NOT-NUMERIC} ({@code :191}),
 *       {@code SEARCHED-CARD-NOT-NUMERIC} ({@code :193}),
 *       {@code DID-NOT-FIND-ACCT-IN-CARDXREF} ({@code :201}), {@code XREF-READ-ERROR} ({@code :211})
 *       and {@code CODING-TO-BE-DONE} ({@code :213}); their literals can never reach a terminal. The
 *       one-for-one mandate covers paragraph labels, not data, so none of the nine is declared. Two of
 *       the seven are load-bearing rather than merely dead: because the two {@code SEARCHED-*}
 *       not-numeric names are never set, the account and card edits report <em>inline</em> literals
 *       instead ({@code :744-746}, {@code :788-790}), which is why those two messages are worded in
 *       upper case while every live condition name is in mixed case. Separately, every file verb in the
 *       program targets {@code LIT-CARDFILENAME} (the base cluster), which is why only
 *       {@code CardRepository} is injected and why no alternate-index finder is used.</li>
 *   <li><strong>This program has zero unreachable labels.</strong> A census
 *       of every Area-A label for a {@code PERFORM}, {@code GO TO}, {@code THRU} or {@code THROUGH}
 *       reference found none unreferenced: {@code 0000-MAIN} is the entry point by fall-through and
 *       {@code ABEND-ROUTINE} is performed at {@code :1025-1026}. Unlike its two sibling card programs,
 *       therefore, <strong>this file contains no retained no-op method and claims no tracked-dead-code
 *       exception</strong>. Rule 1 Clause B applies in full strictness and every method below is
 *       genuinely reachable and genuinely implemented.</li>
 *   <li><strong>Low, citation accuracy. {@code COPY CSSETATY REPLACING} is not used by this
 *       program.</strong> It is a {@code COACTUPC} construct. {@code COCRDUPC} hand-codes the
 *       equivalent field-error marking inline in {@code 3300} with a <em>different</em> guard, testing
 *       {@code CCUP-CHANGES-NOT-OK} for the four data fields ({@code :1258-1288}) where the template
 *       tests {@code CDEMO-PGM-REENTER}, which it retains only for the two filter fields
 *       ({@code :1247-1258}). No type is created for that copybook. The only copybook this program
 *       copies into its procedure division is {@code CSSTRPFY} at {@code :1528}, contributing the two
 *       labels mapped by {@link #storePfKey} and {@link #storePfKeyExit}.</li>
 *   <li><strong>Two legacy oddities in the guarded-message idiom.</strong> At
 *       {@code :1404-1406} the {@code IF WS-RETURN-MSG-OFF} guard wraps a <em>flag</em> assignment
 *       rather than only a message assignment, which no other site does. At {@code :1490-1491} the
 *       rewrite-failure branch sets neither {@code INPUT-ERROR} nor a guard, unlike the lock-failure
 *       branch above it. Both are transcribed as written.</li>
 *   <li><strong>Three source redundancies.</strong> {@code 3100-SCREEN-INIT} evaluates
 *       {@code FUNCTION CURRENT-DATE} twice, at {@code :1055} and {@code :1062}, discarding the first
 *       result; {@code 3200} names {@code CRDNAMEO} twice in one multi-target {@code MOVE} at
 *       {@code :1101-1102}; and the {@code ABEND-DATA} default-message guard can never fire, see
 *       {@link #abendRoutine}.</li>
 *   <li><strong>Three distinct abend values coexist and are cited per site.</strong> The online
 *       payload code is {@code '0001'} ({@code :1021}), the CICS abend code issued by
 *       {@code ABEND-ROUTINE} is {@code '9999'} ({@code :1550-1552}), and the batch corpus uses 999
 *       with return code 12. They are not conflated.</li>
 *   <li><strong>Every received map field is clamped to the width its symbolic map
 *       declares.</strong> Each field {@code 1100-RECEIVE-MAP} reads at {@code :589-635} is declared
 *       {@code PIC X(n)} in {@code app/cpy-bms/COCRDUP.CPY} - {@code ACCTSIDI} X(11) at line 60,
 *       {@code CARDSIDI} X(16) at 66, {@code CRDNAMEI} X(50) at 72, {@code CRDSTCDI} X(1) at 78,
 *       {@code EXPMONI} X(2) at 84, {@code EXPYEARI} X(4) at 90, {@code EXPDAYI} X(2) at 96 - and is
 *       moved into a {@code CCUP-NEW-*} item of the same width, inline at {@code :294-312}. A terminal
 *       field cannot hold more bytes than it declares, so an over-length value is unrepresentable on the
 *       legacy side, and a COBOL alphanumeric {@code MOVE} into a shorter item truncates rather than
 *       faulting. A Java {@code String} has no such bound, and {@code CardUpdateRequest}'s
 *       {@code @Size(max = n)} is Bean Validation enforced at the controller boundary and not inside the
 *       canonical record constructor, so a direct caller can present a value the terminal could never
 *       produce. Left unclamped, such a value travels as far as the screen render or an entity setter and
 *       surfaces as a <em>masked abend</em> rather than the field-level rejection the edit paragraphs
 *       exist to produce - an over-width status code was observed doing exactly that. The clamp is
 *       applied by {@link #truncateToWidth} at each receive point, before the asterisk and blank tests,
 *       because that is where the terminal's own width applies. It truncates and never pads, since a
 *       legacy {@code MOVE} between equal-width items is the identity. For {@code EXPDAYI} the clamp is
 *       <em>not</em> normalisation: what {@code :621} omits is the asterisk-and-blank test, and that
 *       omission stands untouched, so the expiry day stays un-normalised.</li>
 *   <li><strong>{@code CCUP-OLD-CVV-CD} has no counterpart, and the concurrency question it asked is
 *       answered by the version column instead.</strong> {@code :396-400} restores the whole of
 *       {@code WS-THIS-PROGCOMMAREA} in a single {@code MOVE}, re-establishing every leaf of
 *       {@code CCUP-OLD-DETAILS} ({@code :291-301}) including {@code CCUP-OLD-CVV-CD PIC X(3)} at
 *       {@code :294}. Every other leaf is restored here from the opened sealed snapshot; this
 *       one is not, and the reason is worth stating exactly. Both operands of {@code :1503} are
 *       <em>server-side</em>: {@code :1354} snapshots the value the display-time read returned and
 *       {@code :1503} compares it against the value the write-time locking re-read returned. No symbolic
 *       map declares a verification field, so the operator never typed it and no client can carry it. The
 *       predicate therefore asks one question only - did this row change between the two reads - and
 *       {@code @Version} on {@link com.cardemo.model.entity.Card} answers exactly that question for every
 *       column of the row, this one included. Dropping the predicate and the component <em>together</em> is
 *       what keeps the stateless path completing, and it is why neither may be reinstated alone:
 *       reinstating the predicate with no read path refuses every update, and reinstating the component
 *       would put authentication data on the wire.</li>
 *   <li><strong>Both {@code REDEFINES} views are established in
 *       {@code 1100}.</strong> {@code app/cpy/CVCRD01Y.cpy:36} declares
 *       {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID} and {@code :39} declares
 *       {@code CC-CARD-NUM-N REDEFINES CC-CARD-NUM}, so the single {@code MOVE} at {@code :594} and the
 *       one at {@code :603} make the alphanumeric and the numeric view of the same storage current
 *       together, on <em>every</em> task. Deriving the numeric view inside {@code 1210} and {@code 1220}
 *       instead is wrong, because those paragraphs run only on the fetch pass: on the confirmation pass
 *       {@code 1200} takes the {@code :685-693} early exit before them, yet {@code :1425} still needs
 *       {@code CC-CARD-NUM} as the record identifier {@code 9200} locks on and {@code :1463} still needs
 *       {@code CC-ACCT-ID-N}. Note also that {@code :796} reads {@code CC-CARD-NUM-N} as the
 *       <em>source</em> of a move, not its target, so it is not a derivation site at all.</li>
 *   <li><strong>High, resolved. The function-key fold precedes the state derivation.</strong>
 *       {@code :406-407} performs {@code YYYY-STORE-PFKEY} before {@code :414-422} consults the state,
 *       and {@code app/cpy/CSSTRPFY.cpy:62-63} folds {@code DFHPF13} through {@code DFHPF24} onto
 *       {@code PFK01} through {@code PFK12}. Both {@link #storePfKey} and
 *       {@link UpdateContext#resolveInitialAction} therefore share {@link #foldAttentionIdentifier}.
 *       Comparing the raw identifier in the state derivation would leave the upper twelve keys outside
 *       the fold, so {@code DFHPF17} would derive show-details, the gate at {@code :417} would reject
 *       {@code PFK05}, and {@code :423-425} would silently rewrite the key to {@code ENTER} - turning a
 *       confirmed save into a redisplay.</li>
 *   </ol>
 *
 * <h2>Security</h2>
 * <p>Card numbers and the embossed name are the sensitive surface of this file. Neither is ever logged,
 * echoed into an exception message, returned in a projection or written into a Javadoc example. There is
 * no card verification value anywhere on <em>this</em> path - not in the request, not in the context and
 * not in the comparison. The value is stored, on {@link com.cardemo.model.entity.Card}, and it is
 * unreadable there: the field is write-once with no getter of any visibility, so this file could not obtain
 * it even to log it by mistake. {@code Card.toString()} deliberately excludes
 * the card number and the embossed name and offers no masking helper, so the two diagnostic log
 * statements in this file mask at the call site through {@link #maskTail}. The
 * old and new snapshot payloads are never logged as a pair, because together they carry the card number
 * and the cardholder name.</p>
 *
 * <h2>Financial types</h2>
 * <p>{@code Card} carries no monetary or rate field, so this service performs no decimal arithmetic and
 * deliberately imports neither {@code java.math.BigDecimal} nor any rounding mode. {@code expiraionDate}
 * is dash-separated <em>text</em> throughout, reassembled by {@link #assembleExpiraionDate} exactly as
 * the {@code STRING} statement at {@code :1467-1474} builds it; there is no {@code LocalDate}, no
 * {@code @Temporal} and no formatter round-trip on that value. The misspelling of
 * {@code CARD-EXPIRAION-DATE} is part of the field contract and is preserved in the entity property
 * name rather than corrected.</p>
 *
 * <h2>Threading</h2>
 * <p>This bean is a stateless singleton. It holds exactly two immutable collaborators and no mutable
 * field, static or otherwise. Every legacy WORKING-STORAGE item, including the whole
 * {@code CCUP-CHANGE-ACTION} state machine and every {@code FLG-} condition-name triad, lives in a
 * method-local {@link UpdateContext} created per request, so concurrent callers cannot observe one
 * another. There is no HTTP session, no server-side cursor and no static cache.</p>
 *
 * @see CardUpdateRequest
 * @see CardDto
 */
@Service
public class CardUpdateService {

    /** Logger for this bean. Card numbers and embossed names are never passed to it unmasked. */
    private static final Logger LOG = LoggerFactory.getLogger(CardUpdateService.class);

    // WS-LITERALS, app/cbl/COCRDUPC.cbl:218-263. Transcribed character for character.
    // WS-LONG-MSG (:156) and LIT-CARDFILENAME-ACCT-PATH (:253) are deliberately absent.

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDUPC'} at {@code :219}. Also the abend culprit. */
    private static final String PROGRAM_NAME = "COCRDUPC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCUP'} at {@code :221}. */
    private static final String TRANSACTION_ID = "CCUP";

    /** {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDUP '} at {@code :223}, trailing space included. */
    private static final String THIS_MAPSET = "COCRDUP ";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDUPA'} at {@code :225}. */
    private static final String THIS_MAP = "CCRDUPA";

    /** {@code LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'} at {@code :227}. Compared, never invoked. */
    private static final String CARD_LIST_PROGRAM = "COCRDLIC";

    /** {@code LIT-CCLISTMAPSET PIC X(7) VALUE 'COCRDLI'} at {@code :231}. */
    private static final String CARD_LIST_MAPSET = "COCRDLI";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} at {@code :235}. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} at {@code :237}. */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /** {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '} at {@code :251}, trailing space included. */
    private static final String CARD_FILE_NAME = "CARDDAT ";

    /**
     * {@code LIT-ALL-ALPHA-FROM PIC X(52)} at {@code :255-257}. The 52 ASCII letters and nothing else,
     * which is the whole alphabet the embossed-name edit accepts. Read from the source rather than
     * assumed, so no accented or non-ASCII letter is admitted.
     */
    private static final String ALL_ALPHA_FROM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // Screen titles and the WS-INFO-MSG condition names, app/cbl/COCRDUPC.cbl:157-171.

    /** {@code CCDA-TITLE01} from {@code app/cpy/COTTL01Y.cpy}, moved at {@code :1056}. */
    private static final String SCREEN_TITLE_01 = "AWS Mainframe Modernization";

    /** {@code CCDA-TITLE02} from {@code app/cpy/COTTL01Y.cpy}, moved at {@code :1057}. */
    private static final String SCREEN_TITLE_02 = "CardDemo";

    /** {@code 88 FOUND-CARDS-FOR-ACCOUNT} at {@code :160}. */
    private static final String INFO_FOUND_CARDS_FOR_ACCOUNT = "Details of selected card shown above";

    /** {@code 88 PROMPT-FOR-SEARCH-KEYS} at {@code :162}. */
    private static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

    /** {@code 88 PROMPT-FOR-CHANGES} at {@code :164}. */
    private static final String INFO_PROMPT_FOR_CHANGES = "Update card details presented above.";

    /** {@code 88 PROMPT-FOR-CONFIRMATION} at {@code :166}. Note the absent space after the full stop. */
    private static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code 88 CONFIRM-UPDATE-SUCCESS} at {@code :168}. */
    private static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

    /** {@code 88 INFORM-FAILURE} at {@code :170}. */
    private static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    // WS-RETURN-MSG condition names, app/cbl/COCRDUPC.cbl:173-214, plus the two inline literals.
    //
    // WS-RETURN-MSG is PIC X(75) at :173, which is narrower than the 80-character
    // WS-FILE-ERROR-MESSAGE moved into it at :1411. That move therefore truncates five bytes - and
    // exactly five, namely the trailing FILLER PIC X(5) VALUE SPACES at :151-152, so the truncation is
    // harmless. Recorded because it looks like data loss and is not.
    //
    // SEVEN of this group's condition names are DECLARED BUT NEVER SET anywhere in the program, verified
    // by counting SET sites: WS-EXIT-MESSAGE (:175), SEARCHED-ACCT-ZEROES (:189),
    // SEARCHED-ACCT-NOT-NUMERIC (:191), SEARCHED-CARD-NOT-NUMERIC (:193),
    // DID-NOT-FIND-ACCT-IN-CARDXREF (:201), XREF-READ-ERROR (:211) and CODING-TO-BE-DONE (:213). Their
    // literals can never be emitted at run time, so none of them becomes a Java constant - the same
    // ruling that applies to WS-LONG-MSG, since the 1:1 mandate covers labels, not data. Two
    // of the seven are directly load-bearing: because SEARCHED-ACCT-NOT-NUMERIC and
    // SEARCHED-CARD-NOT-NUMERIC are dead, the two not-numeric edits report INLINE literals instead, and
    // those inline literals are worded in upper case where every live condition name is in mixed case.

    /** {@code 88 WS-PROMPT-FOR-ACCT} at {@code :177}. */
    private static final String MSG_PROMPT_FOR_ACCOUNT = "Account number not provided";

    /** {@code 88 WS-PROMPT-FOR-CARD} at {@code :179}. */
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

    /** {@code 88 WS-PROMPT-FOR-NAME} at {@code :181}. */
    private static final String MSG_PROMPT_FOR_NAME = "Card name not provided";

    /** {@code 88 WS-NAME-MUST-BE-ALPHA} at {@code :183}. */
    private static final String MSG_NAME_MUST_BE_ALPHA = "Card name can only contain alphabets and spaces";

    /** {@code 88 NO-SEARCH-CRITERIA-RECEIVED} at {@code :185}. */
    private static final String MSG_NO_SEARCH_CRITERIA_RECEIVED = "No input received";

    /** {@code 88 NO-CHANGES-DETECTED} at {@code :187}. */
    private static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /**
     * Inline literal at {@code :744-746}, moved to {@code WS-RETURN-MSG} under the
     * {@code IF WS-RETURN-MSG-OFF} guard. The declared condition name
     * {@code 88 SEARCHED-ACCT-NOT-NUMERIC} at {@code :191} is never set by any statement, so this
     * inline text, not that condition name, is what the account edit actually reports.
     */
    private static final String MSG_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Inline literal at {@code :788-790}. As above, the declared
     * {@code 88 SEARCHED-CARD-NOT-NUMERIC} at {@code :193} is never set.
     */
    private static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * {@code 88 CARD-STATUS-MUST-BE-YES-NO} at {@code :195}. One literal serves both the blank outcome
     * at {@code :856} and the invalid outcome at {@code :869}.
     */
    private static final String MSG_CARD_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";

    /**
     * {@code 88 CARD-EXPIRY-MONTH-NOT-VALID} at {@code :197}. One literal serves both the blank outcome
     * at {@code :889} and the invalid outcome at {@code :904}.
     */
    private static final String MSG_CARD_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /**
     * {@code 88 CARD-EXPIRY-YEAR-NOT-VALID} at {@code :199}. One literal serves both the blank outcome
     * at {@code :922} and the invalid outcome at {@code :940}. Note that this wording,
     * unlike the month's, states no range - which is consistent with the copy-pasted comment defect at
     * {@code :927-929}, though the code validates 1950 to 2099.
     */
    private static final String MSG_CARD_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /** {@code 88 DID-NOT-FIND-ACCTCARD-COMBO} at {@code :203}, set on {@code DFHRESP(NOTFND)}. */
    private static final String MSG_DID_NOT_FIND_ACCTCARD_COMBO =
            "Did not find cards for this search condition";

    /**
     * {@code 88 COULD-NOT-LOCK-FOR-UPDATE} at {@code :205}. This program's own wording carries no
     * "account": {@code ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT} declares the
     * {@code COACTUPC} variant, so this literal is passed explicitly as the exception message.
     */
    private static final String MSG_COULD_NOT_LOCK_FOR_UPDATE = "Could not lock record for update";

    /** {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code :207}. Two words in "some one", as written. */
    private static final String MSG_DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /** {@code 88 LOCKED-BUT-UPDATE-FAILED} at {@code :209}. */
    private static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    // WS-FILE-ERROR-MESSAGE, app/cbl/COCRDUPC.cbl:133-152. This program's own layout: the leading
    // literal HAS a trailing space and the final FILLER HAS VALUE SPACES, both of which differ from
    // COCRDLIC. Never share a formatter across the three card programs.

    /** {@code FILLER PIC X(12) VALUE 'File Error: '} at {@code :135}, trailing space included. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code FILLER PIC X(4) VALUE ' on '} at {@code :139}. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} at {@code :143}. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} at {@code :147}. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code FILLER PIC X(5) VALUE SPACES} at {@code :151}, the trailing filler of the 75-byte group. */
    private static final String FILE_ERROR_TRAILER = "     ";

    /** {@code MOVE 'READ' TO ERROR-OPNAME} at {@code :1407}. */
    private static final String OPERATION_READ = "READ";

    /** The operation label used when the update lock or the rewrite fails. */
    private static final String OPERATION_REWRITE = "REWRITE";

    // The abend contract. app/cpy/CSMSG02Y.cpy, internally titled CABENDD.CPY, declares ABEND-DATA as
    // ABEND-CODE X(4), ABEND-CULPRIT X(8), ABEND-REASON X(50), ABEND-MSG X(72): 134 bytes.

    /** {@code MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-MSG} at {@code :1023-1024}. */
    private static final String UNEXPECTED_DATA_SCENARIO_MESSAGE = "UNEXPECTED DATA SCENARIO";

    /** {@code MOVE '0001' TO ABEND-CODE} at {@code :1021}, the online payload code. */
    private static final String UNEXPECTED_SCENARIO_ABEND_CODE = "0001";

    /**
     * {@code MOVE SPACES TO ABEND-REASON} at {@code :1022}. The width comes from
     * {@code FileStatusMapper.ABEND_REASON_WIDTH} rather than a local literal, so the one declaration of
     * {@code ABEND-REASON PIC X(50)} governs both this file and the batch tier.
     */
    private static final String ABEND_REASON_SPACES = " ".repeat(FileStatusMapper.ABEND_REASON_WIDTH);

    // The CICS transaction abend code of EXEC CICS ABEND ABCODE('9999') at :1550-1552 intentionally has
    // NO constant here. FatalProcessingException carries a single abendCode, and the value that belongs in
    // it is the '0001' payload code that :1021 moves into ABEND-DATA - the '9999' is the separate
    // transaction-level code that the CICS ABEND command takes, and there is no field to put it in. The
    // three-way distinction between it, the '0001' payload and the batch corpus's 999 is documented on
    // abendRoutine rather than encoded as an unused constant.

    // Attention identifiers, app/cpy/CSSTRPFY.cpy via COPY 'CSSTRPFY' at app/cbl/COCRDUPC.cbl:1528.

    /** {@code DFHENTER} at {@code app/cpy/CSSTRPFY.cpy:19}. The default when no intent is supplied. */
    private static final String ATTENTION_IDENTIFIER_ENTER = "DFHENTER";

    // Field widths from app/cpy/CVACT02Y.cpy and app/cpy-bms/COCRDUP.CPY. Taken exactly; never widened
    // and never truncated.

    /** {@code CARD-NUM PIC X(16)} and {@code CARDSIDI PIC X(16)} at {@code app/cpy-bms/COCRDUP.CPY:66}. */
    private static final int WIDTH_CARD_NUMBER = 16;

    /** {@code CC-ACCT-ID PIC X(11)} and {@code ACCTSIDI PIC X(11)} at {@code COCRDUP.CPY:60}. */
    private static final int WIDTH_ACCOUNT_ID = 11;

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} and {@code CRDNAMEI PIC X(50)} at {@code COCRDUP.CPY:72}. */
    private static final int WIDTH_EMBOSSED_NAME = 50;

    /** {@code CARD-ACTIVE-STATUS PIC X(01)} and {@code CRDSTCDI PIC X(1)} at {@code COCRDUP.CPY:78}. */
    private static final int WIDTH_CARD_STATUS = 1;

    /** {@code CCUP-xxx-EXPYEAR PIC X(4)} and {@code EXPYEARI PIC X(4)} at {@code COCRDUP.CPY:90}. */
    private static final int WIDTH_EXPIRY_YEAR = 4;

    /** {@code CCUP-xxx-EXPMON PIC X(2)} and {@code EXPMONI PIC X(2)} at {@code COCRDUP.CPY:84}. */
    private static final int WIDTH_EXPIRY_MONTH = 2;

    /** {@code CCUP-xxx-EXPDAY PIC X(2)} and {@code EXPDAYI PIC X(2)} at {@code COCRDUP.CPY:96}. */
    private static final int WIDTH_EXPIRY_DAY = 2;

    /** {@code CARD-EXPIRAION-DATE PIC X(10)}, the dash-separated stored form. */
    private static final int WIDTH_EXPIRAION_DATE = 10;

    /** {@code INFOMSGI PIC X(40)} at {@code app/cpy-bms/COCRDUP.CPY:102}. */
    private static final int WIDTH_INFORMATION_MESSAGE = 40;

    /** {@code ERRMSGI PIC X(80)} at {@code app/cpy-bms/COCRDUP.CPY:108}. */
    private static final int WIDTH_ERROR_MESSAGE = 80;

    /** {@code ERROR-OPNAME PIC X(8)} of {@code WS-FILE-ERROR-MESSAGE} at {@code :136}. */
    private static final int WIDTH_ERROR_OPERATION_NAME = 8;

    /** {@code ERROR-FILE PIC X(9)} of {@code WS-FILE-ERROR-MESSAGE} at {@code :140}. */
    private static final int WIDTH_ERROR_FILE_NAME = 9;

    /** {@code ERROR-RESP} and {@code ERROR-RESP2}, both {@code PIC X(10)}, at {@code :145} and {@code :149}. */
    private static final int WIDTH_ERROR_RESPONSE_CODE = 10;

    /**
     * How many trailing characters {@link #maskTail} leaves visible. The source has no counterpart: the
     * legacy screen displayed the full card number, whereas Rule 1 Clause D forbids it in any diagnostic
     * this implementation emits.
     */
    private static final int MASK_VISIBLE_SUFFIX_LENGTH = 4;

    // The split function-key area of this map - FKEYSI PIC X(21) at app/cpy-bms/COCRDUP.CPY:114 and
    // FKEYSCI PIC X(18) at :120 - intentionally has NO width constant here. Both halves are static
    // literals declared in app/bms/COCRDUP.bms; COCRDUPC never moves a value into either, it only
    // brightens FKEYSCA at :1313. There is therefore no legend for this service to emit and no width for
    // it to enforce. See ScreenBuffer.toDto, which records the omission as "Not available" rather than
    // fabricating legend text.

    /**
     * {@code 88 VALID-MONTH VALUES 1 THRU 12} at {@code :95}, lower bound. Inclusive.
     */
    private static final int MIN_EXPIRY_MONTH = 1;

    /** {@code 88 VALID-MONTH VALUES 1 THRU 12} at {@code :95}, upper bound. Inclusive. */
    private static final int MAX_EXPIRY_MONTH = 12;

    /** {@code 88 VALID-YEAR VALUES 1950 THRU 2099} at {@code :99}, lower bound. Inclusive. */
    private static final int MIN_EXPIRY_YEAR = 1950;

    /** {@code 88 VALID-YEAR VALUES 1950 THRU 2099} at {@code :99}, upper bound. Inclusive. */
    private static final int MAX_EXPIRY_YEAR = 2099;

    /** {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} at {@code :91}. No case folding is performed. */
    private static final String CARD_STATUS_ACTIVE = "Y";

    /** {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} at {@code :91}. */
    private static final String CARD_STATUS_INACTIVE = "N";

    /** The single asterisk that {@code 1100-RECEIVE-MAP} treats as "field not supplied". */
    private static final String NOT_SUPPLIED_MARKER = "*";

    /** {@code MOVE ZEROES TO CCUP-NEW-CARDID} at {@code :777-778}, the blank branch of {@code 1220}. */
    private static final String CARD_NUMBER_ZEROES = "0".repeat(WIDTH_CARD_NUMBER);

    /** Field name reported by a {@code ValidationException} raised from {@code 1210-EDIT-ACCOUNT}. */
    private static final String FIELD_ACCOUNT_ID = "accountId";

    /** Field name reported by a {@code ValidationException} raised from {@code 1220-EDIT-CARD}. */
    private static final String FIELD_CARD_NUMBER = "cardNumber";

    /** Field name reported by a {@code ValidationException} raised from {@code 1230-EDIT-NAME}. */
    private static final String FIELD_CARDHOLDER_NAME = "cardholderName";

    /** Field name reported by a {@code ValidationException} raised from {@code 1240-EDIT-CARDSTATUS}. */
    private static final String FIELD_CARD_STATUS_CODE = "cardStatusCode";

    /** Field name reported by a {@code ValidationException} raised from {@code 1250-EDIT-EXPIRY-MON}. */
    private static final String FIELD_EXPIRY_MONTH = "expiryMonth";

    /** Field name reported by a {@code ValidationException} raised from {@code 1260-EDIT-EXPIRY-YEAR}. */
    private static final String FIELD_EXPIRY_YEAR = "expiryYear";

    /** The record type reported by a {@code RecordNotFoundException} from {@code 9100}. */
    private static final String RECORD_TYPE_CARD = "CARD";

    /**
     * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} at {@code :1065}. {@code WS-CURDATE-MM-DD-YY} is
     * assembled from month, day and the last two digits of the year at {@code :1063-1065}, giving the
     * legacy {@code MM/DD/YY} rendering.
     */
    private static final DateTimeFormatter CURRENT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO} at {@code :1069}, assembled from hours, minutes and
     * seconds at {@code :1066-1069}. {@code CURTIMEI} is {@code PIC X(8)} at
     * {@code app/cpy-bms/COCRDUP.CPY:54}, which is exactly what {@code HH:mm:ss} occupies.
     */
    private static final DateTimeFormatter CURRENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The one repository this service uses. Every file verb in {@code COCRDUPC} targets
     * {@code LIT-CARDFILENAME} (the base cluster {@code CARDDAT}): the plain read at
     * {@code :1382-1383}, the read for update at {@code :1427-1428} and the rewrite at {@code :1478}.
     * {@code LIT-CARDFILENAME-ACCT-PATH} at {@code :253} names the alternate index but is referenced by
     * no verb, so no alternate-index finder is used and no other repository is injected.
     */
    private final CardRepository cardRepository;

    /**
     * Stands in for {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} in
     * {@code 3100-SCREEN-INIT} at {@code :1055} and {@code :1062}. Injected rather than captured
     * statically so that the screen header is deterministic under test.
     */
    private final Clock clock;

    /**
     * The sealer of {@code CCUP-OLD-DETAILS}, standing in for the COMMAREA half the source restores at
     * {@code :396-400}.
     *
     * <p>It is what lets the as-displayed group survive between two stateless requests without becoming
     * caller-controlled. The read entry point seals the group this service itself snapshotted; the write entry
     * point opens it and compares that recovered group, never a group taken from the request body - which is
     * why {@code com.cardemo.model.dto.CardUpdateRequest} carries a sealed member rather than a readable
     * one.</p>
     */
    private final SnapshotTokenService snapshotTokenService;

    /**
     * Creates the service.
     *
     * @param cardRepository repository over the {@code CARDDAT} base cluster; must not be {@code null}
     * @param clock          time source for the screen header furniture; must not be {@code null}
     * @param snapshotTokenService the sealer of {@code CCUP-OLD-DETAILS}, which stands in for the COMMAREA
     *                             half restored at {@code :396-400}; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public CardUpdateService(final CardRepository cardRepository, final Clock clock,
                             final SnapshotTokenService snapshotTokenService) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.snapshotTokenService =
                Objects.requireNonNull(snapshotTokenService, "snapshotTokenService must not be null");
    }

    /**
     * The operation kind {@code CCUP-OLD-DETAILS} is sealed under.
     *
     * <p>Authenticated additional data, so a snapshot sealed for the account update - or the card row
     * reference sealed under its own kind - cannot be presented here, and one sealed here cannot be presented
     * anywhere else.</p>
     */
    private static final String SNAPSHOT_KIND = "card-update-snapshot";

    /**
     * The separator joining the two identifiers of the snapshot's record key.
     *
     * <p>A character neither identifier can contain: {@code 2210-EDIT-ACCOUNT} and {@code 2220-EDIT-CARD}
     * admit digits only, so no pair of distinct identifiers can render the same key.</p>
     */
    private static final char RECORD_KEY_SEPARATOR = '/';

    /**
     * The component a record key uses in place of an identifier the request did not supply.
     *
     * <p>Deliberately a value the two edits could never have accepted, so no read can have sealed a snapshot
     * under it and a snapshot presented without both identifiers is refused.</p>
     */
    private static final String NO_IDENTIFIER = "-";

    /**
     * Runs one complete pass of {@code COCRDUPC}, from {@code 0000-MAIN} at {@code :367} to either
     * {@code COMMON-RETURN} at {@code :546} or the {@code EXEC CICS XCTL} at {@code :473}. This is the
     * screen-faithful entry point: it reports every business outcome through the returned
     * {@link CardUpdateResult} exactly as the legacy program reports it through
     * {@code CCUP-CHANGE-ACTION} and the two message fields, and throws only for the conditions the
     * source itself treats as terminal, namely an abend and a data-store failure.
     *
     * <p>One HTTP request equals one CICS task equals one pass through {@code 0000-MAIN}. The pseudo
     * conversation's carried state is reconstructed from the request rather than from a session; see
     * {@code UpdateContext.resolveInitialAction} for the derivation and its evidence.</p>
     *
     * <p>Side effects: on the confirmed-save path this method writes one row through
     * {@link CardRepository#save}. Every other path is read-only.</p>
     *
     * @param request             the received map plus the new-as-edited group; must not be {@code null}
     * @param oldDetails          the as-displayed {@code CCUP-OLD-DETAILS} group this pass compares against,
     *                            supplied directly because this entry point's caller is this application
     *                            reproducing one screen turn in process rather than a remote client asserting
     *                            a precondition. {@code null} reproduces {@code CCUP-DETAILS-NOT-FETCHED} at
     *                            {@code :278-280}, the state in which the write is unreachable
     * @param attentionIdentifier the raw {@code EIBAID} symbol carrying the caller's intent, for example
     *                            {@code DFHENTER}, {@code DFHPF3}, {@code DFHPF5} or {@code DFHPF12}.
     *                            {@code null}, blank and unrecognised values resolve to
     *                            {@code DFHENTER}, matching {@code app/cpy/CSSTRPFY.cpy}
     * @param entryMode           whether this is a fresh entry or a continuation, standing in for
     *                            {@code CDEMO-PGM-CONTEXT} at {@code app/cpy/COCOM01Y.cpy}; must not be
     *                            {@code null}
     * @return the screen or transfer outcome, never {@code null}
     * @throws NullPointerException      if {@code request} or {@code entryMode} is {@code null}
     * @throws FileAccessException       if a file verb fails other than with "not found",
     *                                   reproducing {@code :1402-1412}
     * @throws FatalProcessingException  if {@code 2000-DECIDE-ACTION} reaches its
     *                                   {@code WHEN OTHER} at {@code :1019-1026}
     */
    @Transactional(rollbackFor = Exception.class)
    public CardUpdateResult processRequest(final CardUpdateRequest request,
                                           final CardUpdateRequest.CardDetails oldDetails,
                                           final String attentionIdentifier,
                                           final EntryMode entryMode) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entryMode, "entryMode must not be null");
        // The group is taken from the argument, not from the body: no wire member carries it, and the
        // screen-faithful path's caller is in process, so it holds the group already.
        return mainLine0000(new UpdateContext(request, attentionIdentifier, entryMode, oldDetails));
    }

    /**
     * Applies a card update and returns the refreshed detail screen, translating the legacy program's
     * flag-and-redisplay outcomes into the typed exception contract the REST surface expects.
     *
     * <p>This is the confirmation-carrying convenience form of
     * {@link #processRequest(CardUpdateRequest, CardUpdateRequest.CardDetails, String, EntryMode)}. It supplies the {@code PF05}
     * attention identifier, which {@code :417} shows is the only intent under which
     * {@code 2000-DECIDE-ACTION} reaches {@code 9200-WRITE-PROCESSING}, and it converts the three
     * distinguishable write failures plus the validation failures into exceptions rather than into a
     * redisplayed screen.</p>
     *
     * <p><strong>The old snapshot is taken from the request body and is never derived from the live
     * row.</strong> Transformation Rule 7 puts it there: the COMMAREA held {@code CCUP-OLD-DETAILS} between
     * the two turns of the pseudo-conversation and a stateless server has nowhere to put it, so the caller
     * returns the group {@link #sealSnapshotForUpdate(String, String, String)} projected. Deriving it from the live
     * row instead would make the Regime B comparison at {@code :1503-1508} tautologically true and destroy
     * the guard, so that is never done. What a body-carried group cannot include is the card verification
     * value of {@code :294}: it is stored but has no read path, and no symbolic map declares a field for it,
     * so no caller could supply one - see {@link #checkChangeInRec9300} for why the concurrency question that
     * predicate asked is answered by the {@code @Version} column instead.</p>
     *
     * <p>Side effects: writes one row on success.</p>
     *
     * @param request the received map plus the sealed {@code snapshot} member; must not be {@code null}. That
     *                member is the as-displayed group
     *                {@link #sealSnapshotForUpdate(String, String, String)} sealed, echoed back unaltered; an
     *                absent value, one that fails to open, or one that opens onto nothing is reported rather
     *                than compared against nothing
     * @param subject the authenticated principal the sealed snapshot must have been issued to. One issued to
     *                another operator will not open, so evidence of what was displayed cannot be borrowed;
     *                must not be {@code null} or blank
     * @return the refreshed card detail screen, never {@code null}
     * @throws NullPointerException       if {@code request} is {@code null}
     * @throws IllegalArgumentException   if {@code subject} is {@code null} or blank
     * @throws ValidationException        if any edit paragraph rejects a field; the field name and the
     *                                    two-state blank-versus-invalid kind are carried on the
     *                                    exception, and the field <em>value</em> never is
     * @throws RecordNotFoundException    if the card row is absent, reproducing {@code :1395}
     * @throws ConcurrentUpdateException  with {@code COULD_NOT_LOCK_ACCOUNT},
     *                                    {@code DATA_CHANGED_BEFORE_UPDATE},
     *                                    {@code LOCKED_BUT_UPDATE_FAILED} or
     *                                    {@code CHANGES_NOT_CONFIRMED}; these four stay distinct. An absent
     *                                    sealed snapshot, or one that opens onto nothing, reports
     *                                    {@code CHANGES_NOT_CONFIRMED}; one that fails to open, and one whose
     *                                    group no longer matches the stored row, both report
     *                                    {@code DATA_CHANGED_BEFORE_UPDATE}, because the remedies differ:
     *                                    read the card, versus read it again
     * @throws FileAccessException        if a file verb fails for any other reason
     * @throws FatalProcessingException   if the state machine reaches {@code WHEN OTHER}
     */
    @Transactional(rollbackFor = Exception.class)
    public CardDto updateCard(final CardUpdateRequest request, final String subject) {
        Objects.requireNonNull(request, "request must not be null");
        requireSubject(subject);
        raiseIfIdentifiersUnusable(request);
        // The snapshot is OPENED from the sealed value the request carries and comes from nowhere else - in
        // particular, never from a readable body group, which is why CardUpdateRequest declares none.
        // Transformation Rule 7 moves the storage lifetime the COMMAREA held between the two turns of the
        // pseudo-conversation onto the request; sealing is what lets that happen without handing the
        // comparison's own operand to the party the comparison exists to guard against. Deriving it from the
        // live row instead would make the Regime B comparison at :1503-1508 tautologically true and destroy
        // the guard, which is why it is never done either.
        //
        // The value is bound to this operation, to these two identifiers and to this principal, and it
        // expires, so a snapshot that opens here was issued by this server, for this card, to this caller,
        // recently.
        //
        // The group's card number is the ONE member restored rather than relayed, and :1347 is the authority:
        // MOVE CC-CARD-NUM TO CCUP-OLD-CARDID takes the RECEIVED card number, not the stored one. So the
        // group's copy is redundant with the request's own identity field, and the sealed payload omits it
        // because it is the full sixteen digits CardResponse otherwise masks. Restoring it here reproduces
        // :1347 exactly. None of the five values :1503-1508 compares is touched.
        final CardUpdateRequest.CardDetails openedOldDetails = this.snapshotTokenService.open(
                request.snapshot(), SNAPSHOT_KIND,
                snapshotRecordKey(request.accountId(), request.cardNumber()), subject,
                CardUpdateRequest.CardDetails.class);
        final CardUpdateRequest.CardDetails authenticOldDetails =
                restoreSnapshotCardNumber(openedOldDetails, request.cardNumber());
        if (isSnapshotEmpty(authenticOldDetails)) {
            // The token opened but carries nothing to compare, which is what a read that found no card
            // would have sealed. The Regime B guard at :1503-1508 has nothing to work with, the legacy
            // program is in CCUP-DETAILS-NOT-FETCHED here (:278) and cannot reach the write at all, so the
            // request is reported as unconfirmed rather than saved.
            throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED,
                    INFO_PROMPT_FOR_SEARCH_KEYS, null, null);
        }
        // Pass one, the validation pass. ENTER with a snapshot present resolves to CCUP-SHOW-DETAILS,
        // which is the only state in which 1200-EDIT-MAP-INPUTS runs the four field edits at :698-708:
        // a PF05 request takes the early exit at :685-693 instead, exactly as the source intends,
        // because in the pseudo conversation those edits already ran on the preceding ENTER task.
        // Collapsing the two tasks into one stateless request is what transformation Rule 7 requires,
        // and it is what makes the documented ValidationException contract reachable.
        final UpdateContext validationPass =
                new UpdateContext(request, ATTENTION_IDENTIFIER_ENTER, EntryMode.REENTER,
                        authenticOldDetails);
        final CardUpdateResult validationResult = mainLine0000(validationPass);
        if (validationPass.pendingFailure != null) {
            throw validationPass.pendingFailure;
        }
        raiseIfRecordAbsent(validationPass);
        if (validationPass.inputError) {
            throw fieldFailure(validationPass);
        }
        if (validationPass.changeAction != ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            // :680-683 found the two groups equal, so :681 set NO-CHANGES-DETECTED and :685-693 took the
            // early exit. The source redisplays with that message and writes nothing.
            //
            // The redisplay is the answer, not a refusal. :972-976 evaluates
            // IF INPUT-ERROR OR NO-CHANGES-DETECTED CONTINUE, which leaves the state machine in
            // CCUP-SHOW-DETAILS - the same state a successful read leaves it in - so the task ends by
            // painting the map again. The two conditions are OR'd precisely BECAUSE they are different:
            // an input error additionally sets a FLG-*-NOT-OK marker and 3300 highlights the offending
            // field, while a no-change outcome sets none and :1212-1214 places the cursor on the card
            // name alongside FOUND-CARDS-FOR-ACCOUNT, which is an informational state. Reporting it as a
            // rejection of the request would collapse that distinction and would tell a client to correct
            // an input that is not wrong.
            //
            // The literal travels in the error-message field because :547 and :569 move WS-RETURN-MSG to
            // CCARD-ERROR-MSG, and it is relayed byte for byte.
            return validationResult.screen();
        }
        // Pass two, the confirmation pass. :417 shows PF05 is the only intent accepted while the state
        // machine holds CCUP-CHANGES-OK-NOT-CONFIRMED, and :988-991 is the only route to the write.
        final UpdateContext confirmationPass =
                new UpdateContext(request, AidKey.PFK05.symbol(), EntryMode.REENTER, authenticOldDetails);
        final CardUpdateResult result = mainLine0000(confirmationPass);
        raiseTerminalOutcome(confirmationPass);
        return result.screen();
    }

    /**
     * Runs the source's own two identifier edits before the snapshot is consulted, so an omitted identifier
     * is reported as the missing field it is.
     *
     * <p><strong>Finding, severity Major - remediated here.</strong> {@code PUT /api/cards} omitting the
     * card number answered {@code 412 CARDDEMO-UPDATE-CONFLICT} - "the as-displayed snapshot could not be
     * verified for this record" - and omitting the snapshot too answered {@code 428}. Neither names a field,
     * and neither is true: nothing was stale and nothing had changed. The snapshot simply could not verify,
     * because it is bound to a card number and there was no card number to bind it to.
     *
     * <p>The source does not have this problem, because it edits first. {@code 1200-EDIT-MAP-INPUTS.} at
     * {@code app/cbl/COCRDUPC.cbl} opens with {@code IF CCUP-DETAILS-NOT-FETCHED} at {@code :645} and, on
     * that arm, performs {@code 1210-EDIT-ACCOUNT} at {@code :647-648} and then {@code 1220-EDIT-CARD} at
     * {@code :650-651}, producing {@code 'Account number not provided'}, {@code 'Card number not provided'}
     * or, when both are blank, {@code 'No input received'} at {@code :656-659}. A request that carries no
     * snapshot <em>is</em> that turn - the details have not been fetched - so those edits are exactly the
     * ones it is owed. Opening the snapshot first made the whole arm unreachable and substituted a
     * precondition failure for a field refusal.
     *
     * <p>What this does not change: a request whose identifiers are well formed and which carries no
     * snapshot still answers {@code 428}, because the comparison genuinely cannot run without one, and one
     * that carries both takes the ordinary path with no extra read. The order of the two checks is the only
     * thing that moves, and it moves to the order the source uses.
     *
     * <p>The guard runs on <strong>every</strong> request, not only on one that omits a snapshot. A
     * request carrying a snapshot but no card number would otherwise still be answered {@code 412}: the
     * snapshot is sealed against the card number, so with none to seal against it cannot verify - which is
     * a true statement about the token and a misleading one about the request.
     *
     * <p>It performs <strong>no input or output</strong>. {@code 1210-EDIT-ACCOUNT} and
     * {@code 1220-EDIT-CARD} are pure edits over the submitted fields, so they are invoked directly rather
     * than by driving a fetch turn through {@link #mainLine0000}. Two consequences are intended. A
     * well-formed request that omits its snapshot is answered {@code 428} whether or not the card exists,
     * because a caller that has not read is told to read rather than told what the read would have found.
     * And the ordinary path costs nothing: no request pays for a second read to satisfy this check.
     *
     * <p>Only the <strong>card</strong> verdict is acted on. {@code :669-670} SETs both filter states valid
     * on the second turn without re-editing either, and the card number is the one value that turn cannot
     * proceed without, because the snapshot is sealed against it; the account identifier is recoverable from
     * the snapshot and is therefore not re-demanded. The account verdict reaches the caller only through the
     * both-blank message of {@code :656-659}, which is the source's own precedence.
     *
     * <p>The guard runs the state machine rather than re-testing the fields, so there is exactly one
     * implementation of each edit and its message and cursor come from the paragraph that owns them. It
     * cannot write: {@code DETAILS_NOT_FETCHED} never reaches {@code :988-991}, the only route to the
     * update.
     *
     * @param request the received body; never null
     * @throws ValidationException naming the offending identifier when one is absent, blank or malformed
     */
    private void raiseIfIdentifiersUnusable(final CardUpdateRequest request) {

        // :647-651 - the two search-key edits of the CCUP-DETAILS-NOT-FETCHED arm, run on their own.
        // They are pure: 1210-EDIT-ACCOUNT and 1220-EDIT-CARD read no dataset, so this pre-check reaches
        // no repository and cannot answer a question about whether the record exists. That is deliberate.
        // A caller who has not read cannot be told what a read would have found; it is told to read.
        final UpdateContext editPass =
                new UpdateContext(request, ATTENTION_IDENTIFIER_ENTER, EntryMode.REENTER, null);
        editPass.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
        // :576 PERFORM 1100-RECEIVE-MAP first, exactly as every turn does: the edits read the received
        // work fields, not the request record, so without it both filters would look blank on every
        // request. It reaches no dataset either - it only moves the submitted map into the work area.
        receiveMap1100(editPass);
        editAccount1210(editPass);
        editCard1220(editPass);

        // Only the CARD verdict is acted on, and the account verdict only through the both-blank message.
        // That asymmetry is the source's: :669-670 SETs both filter states valid on the second turn without
        // re-editing either, because the screen had already validated them. Here the card number is the one
        // value the turn genuinely cannot proceed without - the snapshot is sealed against it - while the
        // account identifier is recoverable from the snapshot and is therefore not re-demanded.
        if (editPass.cardFilterState == FieldEditState.IS_VALID) {
            return;
        }

        // :656-659 IF FLG-ACCTFILTER-BLANK AND FLG-CARDFILTER-BLANK SET NO-SEARCH-CRITERIA-RECEIVED - a
        // message without an in-error flag of its own, so it is tested separately and takes precedence over
        // the single-field prompts, exactly as the source's message guard does. The account is named because
        // 1210 is the earlier of the two edits and the cursor rule reports the earlier field [:1212-1214].
        if (editPass.accountFilterState == FieldEditState.BLANK
                && editPass.cardFilterState == FieldEditState.BLANK) {
            throw ValidationException.missingField(FIELD_ACCOUNT_ID, MSG_NO_SEARCH_CRITERIA_RECEIVED);
        }

        throw fieldFailure(editPass);
    }

    /**
     * Reads one card and returns the as-displayed values the matching update requires, sealed into one opaque
     * value the caller echoes back.
     *
     * <p>This is the read half of the stateless substitution for the pseudo-conversation. It drives the same
     * conversation the legacy program's first pass drove - Enter, re-entered, with no snapshot carried, which
     * {@code resolveInitialAction} resolves to {@code CCUP-DETAILS-NOT-FETCHED} and which
     * {@code 2000-DECIDE-ACTION} at {@code :954-966} answers by performing {@code 9000-READ-DATA} - and then
     * returns exactly what {@code :1345-1367} snapshotted into {@code CCUP-OLD-DETAILS}: the upper-cased
     * embossed name, the active status and the three expiry components.</p>
     *
     * <p><strong>Why the group, and why sealed.</strong> Transformation Rule 7 carries the group in the
     * request body of the matching write, and the caller echoes this value back unaltered. It has to be the
     * group and not a rebuildable rendering, because {@code app/cpy-bms/COCRDSL.CPY} declares fifteen fields
     * and none of them is the expiry <em>day</em> that {@code :1507} compares: the day is a member of
     * {@code CCUP-OLD-DETAILS} that the legacy detail screen never displayed, and this is the only route by
     * which a client obtains it. And it has to be sealed, because a group the caller can rewrite is not
     * evidence of what was displayed - replacing it with the live row would make the Regime B comparison at
     * {@code :1503-1508} unconditionally true, so the guard would hold only for callers who chose not to
     * defeat it. The seal binds the operation, the two identifiers the read addressed, the requesting
     * principal and an expiry, and it costs the caller nothing: the value is echoed back verbatim exactly as
     * the group would have been.</p>

     * <p>The sealed group carries no card number. It is not one of the five values {@code :1503-1508}
     * compares, and {@code :1347} moves the <em>received</em> {@code CC-CARD-NUM} into
     * {@code CCUP-OLD-CARDID} rather than reading it from the stored record, so the write restores it from the
     * request's own identity field. Omitting it keeps the sixteen digits out of the sealed payload even though
     * that payload is encrypted.</p>
     *
     * <p>The card verification value of {@code :294} is not a member of the returned group. It is a stored
     * value with no read path, for the reason set out on {@link #checkChangeInRec9300}, and no symbolic map
     * declares a field for it, so no caller could echo one.</p>
     *
     * <p>Side effects: none. This is a read.</p>
     *
     * @param accountFilter the account identifier as typed into {@code ACCTSIDI}; relayed verbatim, so
     *                      absent, blank and populated stay distinct and the source's own edits decide
     * @param cardFilter    the card number as typed into {@code CARDSIDI}; relayed verbatim on the same terms
     * @param subject       the authenticated principal the snapshot is issued to; must not be {@code null} or
     *                      blank
     * @return the sealed as-displayed snapshot, never {@code null}
     * @throws IllegalArgumentException if {@code subject} is {@code null} or blank
     * @throws ValidationException      if either filter is refused by the edits at {@code :698-708}
     * @throws RecordNotFoundException  if no card carries that number, reproducing {@code :1395}
     * @throws FileAccessException      if the read fails, reproducing {@code :1402-1411}
     * @throws FatalProcessingException if the state machine reaches {@code WHEN OTHER}
     */
    @Transactional(readOnly = true)
    public String sealSnapshotForUpdate(final String accountFilter, final String cardFilter,
                                        final String subject) {
        requireSubject(subject);
        final CardUpdateRequest probe = new CardUpdateRequest(null, null, null, null, null, null,
                accountFilter, cardFilter, null, null, null, null, null, null, null, null, null,
                null, null);
        // The seeded state is the turn on which the legacy program reads: ENTER, re-entered, and no snapshot
        // carried. Seeding EntryMode.ENTER would instead reproduce the empty first paint at :393-394 and
        // never read the card.
        final UpdateContext context =
                new UpdateContext(probe, ATTENTION_IDENTIFIER_ENTER, EntryMode.REENTER, null);
        mainLine0000(context);
        if (context.pendingFailure != null) {
            throw context.pendingFailure;
        }
        raiseIfRecordAbsent(context);
        if (context.inputError) {
            throw fieldFailure(context);
        }
        final CardUpdateRequest.CardDetails snapshot = context.snapshotOfFetchedValues();
        // The record key is built from the identifiers the read actually resolved rather than from the
        // arguments, so the value the write must present matches what this read addressed. The card number is
        // dropped from the sealed payload for the reason given above; it is still bound into the key, so a
        // snapshot cannot be moved to another card.
        return this.snapshotTokenService.seal(SNAPSHOT_KIND,
                snapshotRecordKey(snapshot.accountId(), snapshot.cardNumber()), subject,
                withoutCardNumber(snapshot));
    }

    /**
     * Refuses a call that reached a snapshot-bearing entry point without an authenticated principal.
     *
     * <p>Both entry points this guards are only reachable through routes {@code SecurityConfig} declares
     * authenticated, so an absent principal is a wiring defect rather than a request a client made. It is
     * therefore an {@link IllegalArgumentException} and not a {@code ValidationException}, which would render
     * as a {@code 400} and tell a caller to correct something they never sent.</p>
     *
     * @param subject the principal the caller resolved
     * @throws IllegalArgumentException when {@code subject} is {@code null} or blank
     */
    private static void requireSubject(final String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be null or blank: the as-displayed snapshot"
                    + " is bound to the authenticated principal, and every route reaching this service is"
                    + " declared authenticated in SecurityConfig");
        }
    }

    /**
     * Renders the record key the as-displayed snapshot is sealed under: the two identifiers that address the
     * row, joined.
     *
     * <p>Both are taken exactly as they stand - not trimmed, not padded and not re-cased - so that a snapshot
     * issued for one card cannot be presented against another. The binding is authenticated additional data,
     * which makes it a cryptographic guarantee rather than a comparison that could be reasoned around.</p>
     *
     * @param accountId  the account identifier, possibly {@code null} or blank
     * @param cardNumber the card number, possibly {@code null} or blank
     * @return the record key, never {@code null} and never blank
     */
    private static String snapshotRecordKey(final String accountId, final String cardNumber) {
        return keyComponent(accountId) + RECORD_KEY_SEPARATOR + keyComponent(cardNumber);
    }

    /**
     * Renders one component of a record key, substituting a fixed placeholder for an absent value.
     *
     * @param value the identifier as received
     * @return the value itself, or {@value #NO_IDENTIFIER} when it is absent or blank
     */
    private static String keyComponent(final String value) {
        return value == null || value.isBlank() ? NO_IDENTIFIER : value;
    }

    /**
     * Rebuilds an as-displayed group without its card number, for sealing.
     *
     * <p>The card number is not one of the five values {@code app/cbl/COCRDUPC.cbl:L1503-L1508} compares, and
     * {@code :L1347} moves the <em>received</em> {@code CC-CARD-NUM} into {@code CCUP-OLD-CARDID} rather than
     * reading it from the stored record, so the write restores it from the request's own identity field. Every
     * other member is relayed by reference, unaltered.</p>
     *
     * @param snapshot the projected group; must not be {@code null}
     * @return the group with its card number {@code null}
     */
    private static CardUpdateRequest.CardDetails withoutCardNumber(
            final CardUpdateRequest.CardDetails snapshot) {
        return new CardUpdateRequest.CardDetails(snapshot.accountId(), null, snapshot.cardData());
    }

    /**
     * Translates the flags that {@code COCRDUPC} would have rendered onto the screen into the typed
     * exception contract of {@link #updateCard}. Ordered so that the most specific outcome wins: a
     * write failure is reported ahead of a field failure, because the source only ever reaches the
     * write once the field edits have passed.
     *
     * <p>The three write outcomes are kept distinguishable here rather than collapsed. That is
     * deliberate and load-bearing: {@code :993-1000} routes each to a different next state, and
     * {@code DATA_WAS_CHANGED_BEFORE_UPDATE} alone is recoverable, since {@code :998} sets
     * {@code CCUP-SHOW-DETAILS} so the caller may retry against refreshed data.</p>
     *
     * @param context the completed per-request state
     * @throws CardDemoException if the pass ended in any non-success outcome
     */
    private void raiseTerminalOutcome(final UpdateContext context) {
        if (context.pendingFailure != null) {
            throw context.pendingFailure;
        }
        raiseIfRecordAbsent(context);
        switch (context.writeOutcome) {
            case COULD_NOT_LOCK_FOR_UPDATE -> throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT,
                    MSG_COULD_NOT_LOCK_FOR_UPDATE, context.maskedCardNumber(), null);
            case DATA_WAS_CHANGED_BEFORE_UPDATE -> throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                    MSG_DATA_WAS_CHANGED_BEFORE_UPDATE, context.maskedCardNumber(), null);
            case LOCKED_BUT_UPDATE_FAILED -> throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED,
                    MSG_LOCKED_BUT_UPDATE_FAILED, context.maskedCardNumber(), null);
            case NOT_ATTEMPTED, COMPLETED -> { /* :999-1000 WHEN OTHER, and the paths that never write */ }
        }
        if (context.inputError) {
            throw fieldFailure(context);
        }
        if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                || context.changeAction == ChangeAction.SHOW_DETAILS) {
            // :1006-1007 the unguarded duplicate branch: valid, but confirmation was not given, so the
            // legacy program redisplays rather than saving. The REST surface reports it explicitly.
            throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED,
                    INFO_PROMPT_FOR_CONFIRMATION, context.maskedCardNumber(), null);
        }
    }

    /**
     * Reports an absent card row as a missing record rather than as a rejected field.
     *
     * <p>{@code 9100}'s {@code WHEN DFHRESP(NOTFND)} arm at {@code :1395-1401} sets {@code INPUT-ERROR}
     * and marks <em>both</em> filter flags {@code NOT-OK}, because a keyed read cannot tell which of the
     * two identifiers the caller got wrong. On a 3270 that is exactly right: the screen returns with both
     * filters highlighted and the message from {@code :203-204}. Reported through
     * {@link #fieldFailure} it would instead surface as a rejected account field, which would mis-describe
     * a row that is simply not there, so the more specific outcome is raised first.</p>
     *
     * <p>The key travels on the exception in masked form only, per Rule 1 Clause D.</p>
     *
     * @param context the completed per-request state
     * @throws RecordNotFoundException if {@code 9100} did not find the row
     */
    private void raiseIfRecordAbsent(final UpdateContext context) {
        if (!context.cardAbsent) {
            return;
        }
        throw new RecordNotFoundException(context.returnMessage.isEmpty()
                ? MSG_DID_NOT_FIND_ACCTCARD_COMBO
                : context.returnMessage, RECORD_TYPE_CARD, context.maskedCardNumber());
    }

    /**
     * Builds the {@code ValidationException} for whichever edit paragraph set {@code INPUT-ERROR},
     * consulting the {@code FLG-} states in the same priority order that
     * {@code 3300-SETUP-SCREEN-ATTRS} uses to position the cursor at {@code :1201-1226}. The blank
     * state maps to {@code BLANK} and every other rejection to {@code INVALID}, mirroring the fact that
     * {@code 3300} stamps an asterisk only on the blank branches ({@code :1247}, {@code :1255},
     * {@code :1265}, {@code :1275}, {@code :1284}, {@code :1292}).
     *
     * <p>The exception carries the field <em>name</em> and never the field <em>value</em>, so no card
     * number or cardholder name can leak through it.</p>
     *
     * @param context the completed per-request state
     * @return the exception to throw, never {@code null}
     */
    private ValidationException fieldFailure(final UpdateContext context) {
        if (context.accountFilterState != FieldEditState.IS_VALID) {
            return context.accountFilterState == FieldEditState.BLANK
                    ? ValidationException.missingField(FIELD_ACCOUNT_ID, context.returnMessage)
                    : ValidationException.invalidField(FIELD_ACCOUNT_ID, context.returnMessage);
        }
        if (context.cardFilterState != FieldEditState.IS_VALID) {
            return context.cardFilterState == FieldEditState.BLANK
                    ? ValidationException.missingField(FIELD_CARD_NUMBER, context.returnMessage)
                    : ValidationException.invalidField(FIELD_CARD_NUMBER, context.returnMessage);
        }
        if (context.cardNameState != FieldEditState.IS_VALID) {
            return context.cardNameState == FieldEditState.BLANK
                    ? ValidationException.missingField(FIELD_CARDHOLDER_NAME, context.returnMessage)
                    : ValidationException.invalidField(FIELD_CARDHOLDER_NAME, context.returnMessage);
        }
        if (context.cardStatusState != FieldEditState.IS_VALID) {
            return context.cardStatusState == FieldEditState.BLANK
                    ? ValidationException.missingField(FIELD_CARD_STATUS_CODE, context.returnMessage)
                    : ValidationException.invalidField(FIELD_CARD_STATUS_CODE, context.returnMessage);
        }
        if (context.expiryMonthState != FieldEditState.IS_VALID) {
            return context.expiryMonthState == FieldEditState.BLANK
                    ? ValidationException.missingField(FIELD_EXPIRY_MONTH, context.returnMessage)
                    : ValidationException.invalidField(FIELD_EXPIRY_MONTH, context.returnMessage);
        }
        if (context.expiryYearState != FieldEditState.IS_VALID) {
            return context.expiryYearState == FieldEditState.BLANK
                    ? ValidationException.missingField(FIELD_EXPIRY_YEAR, context.returnMessage)
                    : ValidationException.invalidField(FIELD_EXPIRY_YEAR, context.returnMessage);
        }
        // :656-659 SET NO-SEARCH-CRITERIA-RECEIVED, and :1395-1400 DFHRESP(NOTFND): both set
        // INPUT-ERROR without singling out one field.
        return new ValidationException(context.returnMessage);
    }
    // 0000-MAIN. app/cbl/COCRDUPC.cbl:367-560.

    /**
     * The program mainline.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 0000-MAIN.} at line 367.</p>
     *
     * <p>Structure, in source order: the abend handler at {@code :368-370}; the working-storage
     * initialisation at {@code :371-373}; the transaction identifier and message reset at {@code :380}
     * and {@code :384}; the first-entry-versus-continuation split at {@code :385-401}; the attention
     * identifier capture at {@code :406-407}; the function-key validity gate at {@code :413-425}; and
     * the five-branch dispatch at {@code :429-543}.</p>
     *
     * <p>{@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at {@code :368-370} is reproduced by the
     * enclosing {@code try}. A {@code CardDemoException} is already typed and contextual and is
     * rethrown unchanged; anything else is routed through {@link #abendRoutine} with the original
     * throwable preserved as the cause, so nothing is swallowed and no context is lost.</p>
     *
     * @param context the per-request state, standing in for the program's WORKING-STORAGE
     * @return the screen or transfer outcome, never {@code null}
     */
    private CardUpdateResult mainLine0000(final UpdateContext context) {
        // :368-370 EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)
        try {
            // :371-373 INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA. Performed by the
            // UpdateContext constructor, which is the only place this bean holds per-request state.
            // :380 MOVE LIT-THISTRANID TO WS-TRANID
            context.transactionId = TRANSACTION_ID;
            // :384 SET WS-RETURN-MSG-OFF TO TRUE
            context.returnMessage = "";
            // :385-401 IF EIBCALEN IS EQUAL TO 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT
            //          CDEMO-PGM-REENTER) ... ELSE restore both COMMAREA halves. A stateless request
            //          carries no COMMAREA, so the fresh-entry arm resets the state machine and the
            //          continuation arm leaves the state as derived from the request payload.
            if (context.entryMode == EntryMode.ENTER) {
                // :390-392 INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA
                // :393 SET CDEMO-PGM-ENTER TO TRUE
                // :394 SET CCUP-DETAILS-NOT-FETCHED TO TRUE
                context.programContextEnter = true;
                context.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            } else {
                // :396-401 MOVE DFHCOMMAREA(...) TO CARDDEMO-COMMAREA / WS-THIS-PROGCOMMAREA
                context.programContextEnter = false;
            }
            // :406-407 PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT
            context.attentionKey = storePfKey(context.rawAttentionIdentifier);
            // :413 SET PFK-INVALID TO TRUE
            context.pfKeyValid = false;
            // :414-422 the accepted keys: ENTER and PF03 unconditionally, PF05 only while the changes
            //          are validated but unconfirmed, PF12 only once the details have been fetched.
            if (context.attentionKey == AidKey.ENTER
                    || context.attentionKey == AidKey.PFK03
                    || (context.attentionKey == AidKey.PFK05
                        && context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED)
                    || (context.attentionKey == AidKey.PFK12
                        && context.changeAction != ChangeAction.DETAILS_NOT_FETCHED)) {
                // :421 SET PFK-VALID TO TRUE
                context.pfKeyValid = true;
            }
            // :423-425 IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE. An unaccepted key is silently
            //          rewritten to ENTER rather than rejected, so the screen simply redisplays.
            if (!context.pfKeyValid) {
                context.attentionKey = AidKey.ENTER;
            }
            return dispatch0000(context);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            throw abendRoutine(context, unexpected);
        }
    }

    /**
     * The {@code EVALUATE TRUE} that decides which of the five request shapes this task is.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 0000-MAIN.} at lines 429-543.</p>
     *
     * <p>Branch order is preserved exactly, because a COBOL {@code EVALUATE TRUE} runs only the first
     * matching {@code WHEN} and several of these conditions overlap. Each of the first four branches
     * ends by transferring or by jumping to {@code COMMON-RETURN}, so the Java equivalent returns
     * rather than falling through.</p>
     *
     * @param context the per-request state
     * @return the screen or transfer outcome, never {@code null}
     */
    private CardUpdateResult dispatch0000(final UpdateContext context) {
        // :430-434 WHEN CCARD-AID-PFK03 / WHEN (CCUP-CHANGES-OKAYED-AND-DONE AND CDEMO-LAST-MAPSET
        //          EQUAL LIT-CCLISTMAPSET) / WHEN (CCUP-CHANGES-FAILED AND ... ) - three conditions
        //          sharing one body. Note that returning to the card list after a completed or failed
        //          save is a transfer, not a redisplay.
        if (context.attentionKey == AidKey.PFK03
                || (context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE
                    && CARD_LIST_MAPSET.equals(context.lastMapset))
                || (context.changeAction.isChangesFailed()
                    && CARD_LIST_MAPSET.equals(context.lastMapset))) {
            return transfer0000(context);
        }
        // :476-479 WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM
        //          WHEN CCARD-AID-PFK12 AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM
        if ((context.programContextEnter && CARD_LIST_PROGRAM.equals(context.fromProgram))
                || (context.attentionKey == AidKey.PFK12
                    && CARD_LIST_PROGRAM.equals(context.fromProgram))) {
            // :480 SET CDEMO-PGM-REENTER TO TRUE
            context.programContextEnter = false;
            // :481 SET INPUT-OK TO TRUE
            context.inputError = false;
            // :482-483 SET FLG-ACCTFILTER-ISVALID / FLG-CARDFILTER-ISVALID TO TRUE - arriving from the
            //          card list, both keys are trusted and neither edit paragraph runs.
            context.accountFilterState = FieldEditState.IS_VALID;
            context.cardFilterState = FieldEditState.IS_VALID;
            // :484-485 MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N / MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N
            context.receivedAccountId = context.commareaAccountId;
            context.receivedCardNumber = context.commareaCardNumber;
            // :486-487 PERFORM 9000-READ-DATA THRU 9000-READ-DATA-EXIT
            readData9000(context);
            // :488 SET CCUP-SHOW-DETAILS TO TRUE
            context.changeAction = ChangeAction.SHOW_DETAILS;
            // :489-490 PERFORM 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT
            sendMap3000(context);
            // :491 GO TO COMMON-RETURN
            return commonReturn(context);
        }
        // :492-495 WHEN CCUP-DETAILS-NOT-FETCHED AND CDEMO-PGM-ENTER
        //           WHEN CDEMO-FROM-PROGRAM EQUAL LIT-MENUPGM AND NOT CDEMO-PGM-REENTER
        if ((context.changeAction == ChangeAction.DETAILS_NOT_FETCHED && context.programContextEnter)
                || (MENU_PROGRAM.equals(context.fromProgram) && context.programContextEnter)) {
            // :496 INITIALIZE WS-THIS-PROGCOMMAREA
            context.clearProgramCommarea();
            // :497-498 PERFORM 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT
            sendMap3000(context);
            // :499 SET CDEMO-PGM-REENTER TO TRUE
            context.programContextEnter = false;
            // :500 SET CCUP-DETAILS-NOT-FETCHED TO TRUE
            context.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            // :501 GO TO COMMON-RETURN
            return commonReturn(context);
        }
        // :502-503 WHEN CCUP-CHANGES-OKAYED-AND-DONE / WHEN CCUP-CHANGES-FAILED - the caller has been
        //          shown the outcome, so the search keys are cleared and the screen goes back to square
        //          one.
        if (context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE
                || context.changeAction.isChangesFailed()) {
            // :504-507 INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE CDEMO-ACCT-ID CDEMO-CARD-NUM
            context.clearProgramCommarea();
            context.clearMiscStorage();
            context.commareaAccountId = null;
            context.commareaCardNumber = null;
            // :508 SET CDEMO-PGM-ENTER TO TRUE
            context.programContextEnter = true;
            // :509-510 PERFORM 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT
            sendMap3000(context);
            // :511 SET CDEMO-PGM-REENTER TO TRUE
            context.programContextEnter = false;
            // :512 SET CCUP-DETAILS-NOT-FETCHED TO TRUE
            context.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            // :513 GO TO COMMON-RETURN
            return commonReturn(context);
        }
        // :514-522 WHEN OTHER - the ordinary receive, decide and redisplay cycle.
        // :515-516 PERFORM 1000-PROCESS-INPUTS THRU 1000-PROCESS-INPUTS-EXIT
        processInputs1000(context);
        // :517-518 PERFORM 2000-DECIDE-ACTION THRU 2000-DECIDE-ACTION-EXIT
        decideAction2000(context);
        // :519-520 PERFORM 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT
        sendMap3000(context);
        // :521 GO TO COMMON-RETURN
        return commonReturn(context);
    }

    /**
     * The navigation body shared by the three conditions at {@code :430-434}, ending in the program's
     * only {@code EXEC CICS SYNCPOINT} and its only {@code EXEC CICS XCTL}.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 0000-MAIN.} at lines 435-475.</p>
     *
     * <p>Under transformation Rule 7 the {@code XCTL} at {@code :473} becomes URL navigation: it
     * transfers control and <em>terminates</em> the caller, so it is not a subroutine invocation and
     * neither sibling card service is injected or invoked. The {@code SYNCPOINT} at {@code :470}
     * belongs to this transfer path and must not be attributed to
     * {@link #writeProcessing9200}, which contains no rollback verb at all.</p>
     *
     * @param context the per-request state
     * @return a {@link ResponseKind#TRANSFER} outcome carrying the resolved navigation target
     */
    private CardUpdateResult transfer0000(final UpdateContext context) {
        // :435 SET CCARD-AID-PFK03 TO TRUE - the branch normalises the key, because two of its three
        //      entry conditions are reached without PF03 having been pressed.
        context.attentionKey = AidKey.PFK03;
        // :436-441 resolve the target transaction, defaulting to the main menu when the caller context
        //          is absent.
        final String toTransactionId = isBlankOrLowValues(context.fromTransactionId)
                ? MENU_TRANSACTION_ID
                : context.fromTransactionId;
        // :442-447 resolve the target program, defaulting to the main menu program.
        final String toProgram = isBlankOrLowValues(context.fromProgram)
                ? MENU_PROGRAM
                : context.fromProgram;
        // :448-449 MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID / MOVE LIT-THISPGM TO CDEMO-FROM-PROGRAM
        context.fromTransactionId = TRANSACTION_ID;
        context.fromProgram = PROGRAM_NAME;
        // :450-453 IF CDEMO-LAST-MAPSET EQUAL LIT-CCLISTMAPSET MOVE ZEROS TO CDEMO-ACCT-ID
        //          CDEMO-CARD-NUM
        if (CARD_LIST_MAPSET.equals(context.lastMapset)) {
            context.commareaAccountId = 0L;
            context.commareaCardNumber = 0L;
        }
        // :454 SET CDEMO-USRTYP-USER TO TRUE
        // :455 SET CDEMO-PGM-ENTER TO TRUE
        context.programContextEnter = true;
        // :456-457 MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET / MOVE LIT-THISMAP TO CDEMO-LAST-MAP
        context.lastMapset = THIS_MAPSET;
        context.lastMap = THIS_MAP;
        // :459-471 EXEC CICS SYNCPOINT - the unit of work is committed before control leaves the
        //          program. The enclosing @Transactional boundary commits at the same point.
        // :472-475 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
        LOG.debug("CCUP transfer resolved to transaction {} program {} (lastMapset={} state={})",
                toTransactionId, toProgram, context.lastMapset, context.changeAction);
        // :546 COMMON-RETURN's message move still applies to the transfer payload.
        context.errorMessage = context.returnMessage;
        return new CardUpdateResult(ResponseKind.TRANSFER, null,
                new Navigation(toTransactionId, toProgram, context.fromTransactionId,
                        context.fromProgram, context.lastMapset, context.lastMap),
                context.changeAction, null, null, null);
    }

    /**
     * Reassembles the reply and returns to the caller for the next pseudo-conversational turn.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code COMMON-RETURN.} at line 546.</p>
     *
     * <p>{@code :547} moves {@code WS-RETURN-MSG} into {@code CCARD-ERROR-MSG}; {@code :548-552}
     * reassemble the two COMMAREA halves; {@code :553-558} issue
     * {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)}. Under transformation
     * Rule 7 the {@code RETURN TRANSID ... COMMAREA} becomes a stateless response: the identity and
     * navigation fields have no Java equivalent, and what the caller genuinely needs, namely the
     * rendered screen and the refreshed snapshot, travels in the returned record instead.</p>
     *
     * @param context the per-request state
     * @return a {@link ResponseKind#MAP} outcome carrying the rendered screen
     */
    private CardUpdateResult commonReturn(final UpdateContext context) {
        // :547 MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        context.errorMessage = context.returnMessage;
        // :548-552 reassemble CARDDEMO-COMMAREA and WS-THIS-PROGCOMMAREA into WS-COMMAREA
        // :553-558 EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)
        final CardUpdateResult result = new CardUpdateResult(ResponseKind.MAP,
                context.screen.toDto(context), null, context.changeAction,
                context.screen.attributes(context), context.refreshedSnapshot(),
                context.submittedDetails());
        mainExit0000();
        return result;
    }

    /**
     * The mainline exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 0000-MAIN-EXIT.} at line 560. The paragraph body is
     * the single COBOL {@code EXIT} statement at {@code :561}, which transfers control to the end of the paragraph
     * and has no Java counterpart beyond returning. It is retained as its own method because the one-for-one
     * paragraph mandate covers exit labels, so the correspondence stays mechanically checkable.</p>
     */
    private void mainExit0000() {
        // :561 EXIT
    }
    // 1000-PROCESS-INPUTS and 1100-RECEIVE-MAP. app/cbl/COCRDUPC.cbl:564-640.

    /**
     * Receives the map, edits it, and stamps the reply routing fields.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1000-PROCESS-INPUTS.} at line 564.</p>
     *
     * <p>{@code :565-566} performs {@code 1100-RECEIVE-MAP}; {@code :567-568} performs
     * {@code 1200-EDIT-MAP-INPUTS}; {@code :569-572} move the return message and this program's own
     * name, mapset and map into the reply. The last three are the pseudo-conversational "come back to
     * me" fields; under transformation Rule 7 they carry no Java meaning beyond identifying the screen
     * that was rendered, which the response already reports.</p>
     *
     * @param context the per-request state
     */
    private void processInputs1000(final UpdateContext context) {
        // :565-566 PERFORM 1100-RECEIVE-MAP THRU 1100-RECEIVE-MAP-EXIT
        receiveMap1100(context);
        // :567-568 PERFORM 1200-EDIT-MAP-INPUTS THRU 1200-EDIT-MAP-INPUTS-EXIT
        editMapInputs1200(context);
        // :569 MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        context.errorMessage = context.returnMessage;
        // :570-572 MOVE LIT-THISPGM / LIT-THISMAPSET / LIT-THISMAP TO the next-screen fields
        context.nextProgram = PROGRAM_NAME;
        context.nextMapset = THIS_MAPSET;
        context.nextMap = THIS_MAP;
        processInputsExit1000();
    }

    /**
     * The input-processing exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1000-PROCESS-INPUTS-EXIT.} at line 575,
     * whose body is the {@code EXIT} statement at {@code :576}.</p>
     */
    private void processInputsExit1000() {
        // :576 EXIT
    }

    /**
     * Receives the screen and normalises the supplied fields.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1100-RECEIVE-MAP.} at line 578.</p>
     *
     * <p>Two things happen here and both are load-bearing.</p>
     *
     * <p>First, {@code INITIALIZE CCUP-NEW-DETAILS} at {@code :586} clears the <em>entire</em> new-values
     * group before any field is populated. Because no statement anywhere in the program ever assigns
     * {@code CCUP-NEW-CVV-CD}, that clear is the only thing that ever touches it, which is why the source's
     * rewrite wrote three spaces over the stored value and why the two {@code MOVE}s at {@code :1464-1465}
     * are deliberately absent here - see the verification-value deviation in the class documentation.</p>
     *
     * <p>Second, the comment at {@code :588} reads "REPLACE * WITH LOW-VALUES" and the substitution is
     * applied to exactly <strong>six</strong> fields: the account filter at {@code :589-596}, the card
     * filter at {@code :598-605}, the embossed name at {@code :607-612}, the status at {@code :614-619},
     * the expiry month at {@code :623-628} and the expiry year at {@code :630-635}.
     * <strong>{@code EXPDAYI} is the one exception</strong>: {@code :621} is a bare, unconditional
     * {@code MOVE EXPDAYI OF CCRDUPAI TO CCUP-NEW-EXPDAY} with no asterisk handling and no blank
     * handling, so the expiry day is accepted raw. A uniform loop over all seven fields would be wrong,
     * and this asymmetry is preserved deliberately.</p>
     *
     * <p>The flat map components of the request are the received map and therefore govern, because they
     * are what this paragraph reads. Where a flat component is absent entirely the corresponding
     * {@code newDetails} leaf is used instead, so a caller that prefers the structured form is served
     * without the two ever disagreeing silently.</p>
     *
     * @param context the per-request state
     */
    private void receiveMap1100(final UpdateContext context) {
        final CardUpdateRequest received = context.request;
        final CardUpdateRequest.CardDetails submitted = received.newDetails();
        final CardUpdateRequest.CardData submittedData =
                submitted == null ? null : submitted.cardData();
        final CardUpdateRequest.ExpiraionDate submittedDate =
                submittedData == null ? null : submittedData.expiraionDate();
        // :579-584 EXEC CICS RECEIVE MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET) INTO(CCRDUPAI)
        //          RESP(WS-RESP-CD) RESP2(WS-REAS-CD). The DTO is the received map.
        // :586 INITIALIZE CCUP-NEW-DETAILS
        context.clearNewDetails();
        // :588 REPLACE * WITH LOW-VALUES
        // :589-596 ACCTSIDI, cleared or moved to BOTH CC-ACCT-ID and CCUP-NEW-ACCTID.
        //           ACCTSIDI is PIC X(11) at COCRDUP.CPY:60 and CC-ACCT-ID is PIC X(11) at
        //           app/cpy/CVCRD01Y.cpy:34, so the terminal width applies before the '*' test.
        final String accountFilter = truncateToWidth(firstSupplied(received.accountId(),
                submitted == null ? null : submitted.accountId()), WIDTH_ACCOUNT_ID);
        if (isNotSupplied(accountFilter)) {
            context.receivedAccountIdText = null;
            context.receivedAccountId = null;
            context.newAccountId = null;
        } else {
            context.receivedAccountIdText = accountFilter;
            // app/cpy/CVCRD01Y.cpy:36 declares CC-ACCT-ID-N REDEFINES CC-ACCT-ID, so the single MOVE
            // at :594 establishes the alphanumeric and the numeric view of the same storage at once.
            // Both views must therefore be current after 1100, on EVERY task and not only on the fetch
            // task, because :1463's MOVE CC-ACCT-ID-N TO CARD-UPDATE-ACCT-ID reads the numeric view
            // from inside 9200 - a paragraph that 1210-EDIT-ACCOUNT never precedes.
            context.receivedAccountId = parseDigits(accountFilter);
            context.newAccountId = accountFilter;
        }
        // :598-605 CARDSIDI, cleared or moved to BOTH CC-CARD-NUM and CCUP-NEW-CARDID
        //          CARDSIDI is PIC X(16) at COCRDUP.CPY:66 and CC-CARD-NUM is PIC X(16) at
        //          app/cpy/CVCRD01Y.cpy:37, so the terminal width applies before the '*' test.
        final String cardFilter = truncateToWidth(firstSupplied(received.cardNumber(),
                submitted == null ? null : submitted.cardNumber()), WIDTH_CARD_NUMBER);
        if (isNotSupplied(cardFilter)) {
            context.receivedCardNumberText = null;
            context.receivedCardNumber = null;
            context.newCardId = null;
        } else {
            context.receivedCardNumberText = cardFilter;
            // app/cpy/CVCRD01Y.cpy:39 declares CC-CARD-NUM-N REDEFINES CC-CARD-NUM, so :603 makes both
            // views current together. This is load bearing for the write: :1425's MOVE CC-CARD-NUM TO
            // WS-CARD-RID-CARDNUM is the record identifier 9200 locks on, and 9200 is reached only from
            // :988 - a path on which 1220-EDIT-CARD has not run.
            context.receivedCardNumber = parseDigits(cardFilter);
            context.newCardId = cardFilter;
        }
        // :607-612 CRDNAMEI -> CCUP-NEW-CRDNAME. CRDNAMEI is PIC X(50) at COCRDUP.CPY:72.
        final String cardholderName = truncateToWidth(firstSupplied(received.cardholderName(),
                submittedData == null ? null : submittedData.cardholderName()), WIDTH_EMBOSSED_NAME);
        context.newCardholderName = isNotSupplied(cardholderName) ? null : cardholderName;
        // :614-619 CRDSTCDI -> CCUP-NEW-CRDSTCD. CRDSTCDI is PIC X(1) at COCRDUP.CPY:78, so the field
        //          can hold exactly one byte and the clamp precedes the '*' and blank tests.
        final String cardStatusCode = truncateToWidth(firstSupplied(received.cardStatusCode(),
                submittedData == null ? null : submittedData.cardStatusCode()), WIDTH_CARD_STATUS);
        context.newCardStatusCode = isNotSupplied(cardStatusCode) ? null : cardStatusCode;
        // :621 MOVE EXPDAYI OF CCRDUPAI TO CCUP-NEW-EXPDAY - bare and unconditional. The expiry day is
        //      NOT normalised: an asterisk and a blank both pass straight through. The width
        //      clamp is not normalisation: EXPDAYI is PIC X(2) at COCRDUP.CPY:96 and the receiving item
        //      is PIC X(2) at :300, so the field geometry applies here exactly as it does to the six
        //      normalised fields. What :621 omits is the '*' and SPACES test, and that omission stands.
        //
        //      WHAT CARRIES THE VALUE INTO EXPDAYI, AND WHY IT CANNOT BE THE REQUEST. The expiry day is
        //      the one field of this map the source does not let a user change, and it says so in as many
        //      words. 3200-SETUP-SCREEN-VARS writes CCUP-OLD-EXPDAY into EXPDAYO on EVERY arm - SHOW
        //      DETAILS at :1110, CHANGES MADE at :1123 and WHEN OTHER at :1127 - and at :1120-1122 the
        //      NEW-value MOVE is present but COMMENTED OUT, under the banner 'MOVE OLD VALUES TO
        //      NON-DISPLAY FIELDS THAT WE ARE NOT ALLOWING USER TO CHANGE(FOR NOW)'. :1285 then sets
        //      DFHBMDAR on EXPDAYC, so the field is rendered dark. The terminal returns what was sent, so
        //      CCUP-NEW-EXPDAY at :621 can only ever hold the OLD day - which is why :1471 may write it
        //      into the rewrite image safely, and why COCRDSL.CPY declares no EXPDAYI at all.
        //
        //      The screen was the carrier. Statelessly there is no screen, so the snapshot is the carrier:
        //      the day is taken from the CCUP-OLD-EXPDAY leaf the constructor populated from the snapshot
        //      this service opened. Trusting request.expiryDay() instead reproduces the MOVE and loses the
        //      behaviour, and it is destructive rather than merely divergent - a caller echoing back a
        //      detail read, which cannot publish a day it does not carry, would submit none and the STRING
        //      at :1467-1474 would compose an expiry with the day blank, silently erasing it from the
        //      stored record.
        //
        //      Finding, severity Major - remediated here. The submitted component is retained on the
        //      request because COCRDUP.CPY declares the field and the DTO mirrors the map, and it is
        //      echoed back on the response as the source echoes EXPDAYO; it simply never reaches the
        //      rewrite image, exactly as :1120-1122 arranges.
        final String submittedExpiryDay = truncateToWidth(firstSupplied(received.expiryDay(),
                submittedDate == null ? null : submittedDate.expiryDay()), WIDTH_EXPIRY_DAY);
        context.newExpiryDay = context.oldExpiryDay != null
                ? context.oldExpiryDay
                : submittedExpiryDay;
        // :623-628 EXPMONI -> CCUP-NEW-EXPMON. EXPMONI is PIC X(2) at COCRDUP.CPY:84.
        final String expiryMonth = truncateToWidth(firstSupplied(received.expiryMonth(),
                submittedDate == null ? null : submittedDate.expiryMonth()), WIDTH_EXPIRY_MONTH);
        context.newExpiryMonth = isNotSupplied(expiryMonth) ? null : expiryMonth;
        // :630-635 EXPYEARI -> CCUP-NEW-EXPYEAR. EXPYEARI is PIC X(4) at COCRDUP.CPY:90.
        final String expiryYear = truncateToWidth(firstSupplied(received.expiryYear(),
                submittedDate == null ? null : submittedDate.expiryYear()), WIDTH_EXPIRY_YEAR);
        context.newExpiryYear = isNotSupplied(expiryYear) ? null : expiryYear;
        receiveMapExit1100();
    }

    /**
     * The receive exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1100-RECEIVE-MAP-EXIT.} at line 638,
     * whose body is the {@code EXIT} statement at {@code :639}.</p>
     */
    private void receiveMapExit1100() {
        // :639 EXIT
    }

    // 1200-EDIT-MAP-INPUTS. app/cbl/COCRDUPC.cbl:641-719.

    /**
     * Edits the received map in two distinct phases.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1200-EDIT-MAP-INPUTS.} at line 641.</p>
     *
     * <p><strong>Phase one, the fetch request</strong> ({@code IF CCUP-DETAILS-NOT-FETCHED} at
     * {@code :645}). Only the two search keys are edited, {@code :647} then {@code :650}. The new
     * card-data group is then cleared at {@code :653}, and if both filters came back blank
     * {@code :656-659} reports "No input received". The branch ends with
     * {@code GO TO 1200-EDIT-MAP-INPUTS-EXIT} at {@code :661}, so <strong>every field edit is skipped on
     * a fetch</strong>. A first request validates the keys and nothing else.</p>
     *
     * <p><strong>Phase two, the details already fetched</strong> ({@code :667-714}). Both filter states
     * are asserted valid at {@code :669-670} without re-editing, the seven snapshot values are restored
     * into the work fields at {@code :671-677}, and Regime A runs at {@code :680-683}. The early exit at
     * {@code :685-693} then fires when no change was detected, or when the changes were already
     * validated but not confirmed, or when the save already completed: in all three cases the four field
     * states are <em>forced</em> valid and the paragraph exits without editing. Only otherwise does the
     * chain {@code :698} to {@code :708} run, in exactly that order, followed by the
     * {@code IF INPUT-ERROR CONTINUE ELSE SET CCUP-CHANGES-OK-NOT-CONFIRMED} at {@code :710-714}.</p>
     *
     * @param context the per-request state
     */
    private void editMapInputs1200(final UpdateContext context) {
        // :643 SET INPUT-OK TO TRUE
        context.inputError = false;
        // :645 IF CCUP-DETAILS-NOT-FETCHED
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED) {
            // :647-648 PERFORM 1210-EDIT-ACCOUNT THRU 1210-EDIT-ACCOUNT-EXIT
            editAccount1210(context);
            // :650-651 PERFORM 1220-EDIT-CARD THRU 1220-EDIT-CARD-EXIT
            editCard1220(context);
            // :653 MOVE LOW-VALUES TO CCUP-NEW-CARDDATA - the four data fields plus the carried day are
            //      cleared, because a fetch has nothing to update with.
            context.newCardholderName = null;
            context.newExpiryYear = null;
            context.newExpiryMonth = null;
            context.newExpiryDay = null;
            context.newCardStatusCode = null;
            // :656-659 IF FLG-ACCTFILTER-BLANK AND FLG-CARDFILTER-BLANK SET NO-SEARCH-CRITERIA-RECEIVED
            if (context.accountFilterState == FieldEditState.BLANK
                    && context.cardFilterState == FieldEditState.BLANK) {
                context.returnMessage = MSG_NO_SEARCH_CRITERIA_RECEIVED;
            }
            // :661 GO TO 1200-EDIT-MAP-INPUTS-EXIT
            editMapInputsExit1200();
            return;
        }
        // :663-665 ELSE CONTINUE
        // :668 SET FOUND-CARDS-FOR-ACCOUNT TO TRUE
        context.informationMessage = INFO_FOUND_CARDS_FOR_ACCOUNT;
        // :669-670 SET FLG-ACCTFILTER-ISVALID / FLG-CARDFILTER-ISVALID TO TRUE
        context.accountFilterState = FieldEditState.IS_VALID;
        context.cardFilterState = FieldEditState.IS_VALID;
        // :671-677 restore the seven CCUP-OLD-* values into the work fields, so that the comparison and
        //          the screen render against what the caller was actually shown.
        //
        // The values come from the context's own CCUP-OLD-* leaves, which the constructor populated from the
        // snapshot this service OPENED from the request's sealed member - never from a readable body group,
        // because CardUpdateRequest declares none. A caller-supplied precondition is not a precondition, so
        // the only group entitled to stand in for CCUP-OLD-DETAILS here is the one the read sealed; it is the
        // Rule 7 substitution for the COMMAREA half the source restores at :396-400.
        //
        // Reading
        // context.request.oldDetails() here would read a group that is ALWAYS null on a request that
        // reaches here: the operation refuses a body-carried snapshot outright, because a caller-supplied
        // precondition is not a precondition. The seven work fields would be restored from nothing,
        // the Regime A comparison at :680-683 would compare the submitted group against an empty one, the
        // groups would never match, NO-CHANGES-DETECTED would be unreachable, and an identical resubmission
        // would run straight through to the write - answering 'Changes committed to database' and incrementing
        // the row's version although no value had changed. The snapshot the service opened is the Rule 7
        // substitution for the COMMAREA half the source restores at :396-400, so it is the only group
        // entitled to stand in for CCUP-OLD-DETAILS here.
        // :671 MOVE CCUP-OLD-ACCTID TO CDEMO-ACCT-ID
        context.commareaAccountId = context.oldAccountId;
        // :672 MOVE CCUP-OLD-CARDID TO CDEMO-CARD-NUM
        context.commareaCardNumber = context.oldCardNumber;
        // :673 MOVE CCUP-OLD-CRDNAME TO CARD-EMBOSSED-NAME
        context.editEmbossedName = context.oldCardholderName;
        // :674 MOVE CCUP-OLD-CRDSTCD TO CARD-ACTIVE-STATUS
        context.editCardStatus = context.oldCardStatusCode;
        // :675 MOVE CCUP-OLD-EXPDAY TO CARD-EXPIRY-DAY
        context.editExpiryDay = context.oldExpiryDay;
        // :676 MOVE CCUP-OLD-EXPMON TO CARD-EXPIRY-MONTH
        context.editExpiryMonth = context.oldExpiryMonth;
        // :677 MOVE CCUP-OLD-EXPYEAR TO CARD-EXPIRY-YEAR
        context.editExpiryYear = context.oldExpiryYear;
        // :680-683 Regime A. See checkCallerChangedAnything1200 for the group semantics.
        if (checkCallerChangedAnything1200(context)) {
            // :681 SET NO-CHANGES-DETECTED TO TRUE
            context.noChangesDetected = true;
            context.returnMessage = MSG_NO_CHANGES_DETECTED;
        }
        // :685-693 the early exit. Three states share it, and all three force the four field states
        //          valid so that 3300 marks nothing in error.
        if (context.noChangesDetected
                || context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                || context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            // :687-690 SET FLG-CARDNAME-ISVALID / FLG-CARDSTATUS-ISVALID / FLG-CARDEXPMON-ISVALID /
            //          FLG-CARDEXPYEAR-ISVALID TO TRUE
            context.cardNameState = FieldEditState.IS_VALID;
            context.cardStatusState = FieldEditState.IS_VALID;
            context.expiryMonthState = FieldEditState.IS_VALID;
            context.expiryYearState = FieldEditState.IS_VALID;
            // :692 GO TO 1200-EDIT-MAP-INPUTS-EXIT
            editMapInputsExit1200();
            return;
        }
        // :696 SET CCUP-CHANGES-NOT-OK TO TRUE - pessimistic, until the four edits below all pass.
        context.changeAction = ChangeAction.CHANGES_NOT_OK;
        // :698-699 PERFORM 1230-EDIT-NAME THRU 1230-EDIT-NAME-EXIT
        editName1230(context);
        // :701-702 PERFORM 1240-EDIT-CARDSTATUS THRU 1240-EDIT-CARDSTATUS-EXIT
        editCardStatus1240(context);
        // :704-705 PERFORM 1250-EDIT-EXPIRY-MON THRU 1250-EDIT-EXPIRY-MON-EXIT
        editExpiryMonth1250(context);
        // :707-708 PERFORM 1260-EDIT-EXPIRY-YEAR THRU 1260-EDIT-EXPIRY-YEAR-EXIT
        editExpiryYear1260(context);
        // :710-714 IF INPUT-ERROR CONTINUE ELSE SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
        if (context.inputError) {
            // :711 CONTINUE - the state stays CCUP-CHANGES-NOT-OK so 3300 marks the offending fields.
            context.changeAction = ChangeAction.CHANGES_NOT_OK;
        } else {
            // :713 SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
            context.changeAction = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
        }
        editMapInputsExit1200();
    }

    /**
     * Regime A: whole-group, case-insensitive comparison of the new card-data group against the old one.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1200-EDIT-MAP-INPUTS.} at lines
     * 680-683, {@code IF FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA) EQUAL
     * FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)}.</p>
     *
     * <p>The comparison unit is the {@code CARDDATA} group declared at {@code :296-301} and
     * {@code :308-313}: embossed name (50), expiry year (4), expiry month (2), expiry day (2) and
     * active status (1), 59 bytes in all. Two consequences follow directly from that layout and both
     * matter. The <strong>carried expiry day participates</strong>, even though no paragraph ever
     * validates it. The <strong>verification value, account id and card number do not</strong>, because
     * they sit outside {@code CARDDATA} in the enclosing {@code CCUP-*-DETAILS} group. In the source the
     * verification value was nevertheless compared by Regime B at {@code :1503}, which was one reason the
     * two regimes could not be merged; that predicate has no counterpart here because the stored value has
     * no read path and {@code @Version} answers the concurrency question it asked, and the regimes
     * still cannot be merged because Regime A folds a 59-byte group while Regime B compares five fields
     * against a snapshot taken at a different moment.</p>
     *
     * <p>Because COBOL compares fixed-width, space-padded storage, both sides are rendered to their
     * declared widths by {@link #renderCardDataGroup} before folding. {@code toUpperCase(Locale.ROOT)}
     * is mandatory rather than incidental: {@code LIT-ALL-ALPHA-FROM} at {@code :255-257} is the 52
     * ASCII letters, so a locale-sensitive fold would diverge from the source on {@code i} under a
     * Turkish default locale.</p>
     *
     * @param context the per-request state, holding both the new group as normalised by {@code 1100} and
     *     the {@code CCUP-OLD-*} leaves the constructor populated from the snapshot this service opened
     * @return {@code true} when the two groups are equal after folding, meaning nothing changed
     */
    private boolean checkCallerChangedAnything1200(final UpdateContext context) {
        final String newGroup = renderCardDataGroup(context.newCardholderName, context.newExpiryYear,
                context.newExpiryMonth, context.newExpiryDay, context.newCardStatusCode);
        // The old group is rendered from the context's own CCUP-OLD-* leaves, which came from the snapshot
        // this service opened from the request's sealed member. There is no readable body group to read them
        // from, and reading them from an empty group would make the comparison tautologically false - see
        // editMapInputs1200.
        final String oldGroup = renderCardDataGroup(context.oldCardholderName, context.oldExpiryYear,
                context.oldExpiryMonth, context.oldExpiryDay, context.oldCardStatusCode);
        return newGroup.toUpperCase(Locale.ROOT).equals(oldGroup.toUpperCase(Locale.ROOT));
    }

    /**
     * The map-edit exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1200-EDIT-MAP-INPUTS-EXIT.} at line 717,
     * whose body is the {@code EXIT} statement at {@code :718}. Three {@code GO TO} statements target
     * it, at {@code :661} and {@code :692} in the same paragraph.</p>
     */
    private void editMapInputsExit1200() {
        // :718 EXIT
    }
    // The six field edits. app/cbl/COCRDUPC.cbl:721-946.

    /**
     * Validates the account filter.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1210-EDIT-ACCOUNT.} at line 721.</p>
     *
     * <p>Pessimistic default at {@code :722}, then two gates. The blank gate at {@code :725-736} accepts
     * low values, spaces or all zeros; it reports "Account number not provided" under the
     * {@code IF WS-RETURN-MSG-OFF} guard at {@code :730-732}, clears the commarea field to ZEROES at
     * {@code :733} and the new-values field to LOW-VALUES at {@code :734}. The class test at
     * {@code :740-750} reports the inline 11-digit literal, again guarded, and clears the same two
     * fields the same two ways. Success at {@code :752-754} is a multi-target move into both.</p>
     *
     * <p>The two clearing values are deliberately different and are kept different: {@code null} stands
     * for LOW-VALUES and a string of ASCII zeros for ZEROES, and the three of {@code null}, the empty
     * string and {@code "0"} are never coerced into one another.</p>
     *
     * @param context the per-request state
     */
    private void editAccount1210(final UpdateContext context) {
        // :722 SET FLG-ACCTFILTER-NOT-OK TO TRUE
        context.accountFilterState = FieldEditState.NOT_OK;
        // :725-727 IF CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES OR CC-ACCT-ID-N EQUAL ZEROS
        if (isBlankOrLowValues(context.receivedAccountIdText)
                || isAllZeroes(context.receivedAccountIdText)) {
            // :728 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :729 SET FLG-ACCTFILTER-BLANK TO TRUE
            context.accountFilterState = FieldEditState.BLANK;
            // :730-732 IF WS-RETURN-MSG-OFF SET WS-PROMPT-FOR-ACCT TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_PROMPT_FOR_ACCOUNT;
            }
            // :733 MOVE ZEROES TO CDEMO-ACCT-ID
            context.commareaAccountId = 0L;
            // :734 MOVE LOW-VALUES TO CCUP-NEW-ACCTID
            context.newAccountId = null;
            // :735 GO TO 1210-EDIT-ACCOUNT-EXIT
            editAccountExit1210();
            return;
        }
        // :740 IF CC-ACCT-ID IS NOT NUMERIC - the COBOL numeric class test over PIC X(11), so every one
        //      of the eleven positions must hold a digit. A shorter value is space-padded and therefore
        //      fails, which is the behaviour the inline message describes.
        if (!isNumericClass(context.receivedAccountIdText, WIDTH_ACCOUNT_ID)) {
            // :741 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :742 SET FLG-ACCTFILTER-NOT-OK TO TRUE
            context.accountFilterState = FieldEditState.NOT_OK;
            // :743-747 IF WS-RETURN-MSG-OFF MOVE the inline literal at :745 TO WS-RETURN-MSG
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_ACCOUNT_FILTER_NOT_NUMERIC;
            }
            // :748 MOVE ZERO TO CDEMO-ACCT-ID
            context.commareaAccountId = 0L;
            // :749 MOVE LOW-VALUES TO CCUP-NEW-ACCTID
            context.newAccountId = null;
            // :750 GO TO 1210-EDIT-ACCOUNT-EXIT
            editAccountExit1210();
            return;
        }
        // :751-753 ELSE MOVE CC-ACCT-ID TO CDEMO-ACCT-ID CCUP-NEW-ACCTID. Only the COMMAREA field and
        //          the new-values field are written here; CC-ACCT-ID-N is already current from :594,
        //          per the REDEFINES note in receiveMap1100.
        context.commareaAccountId = parseDigits(context.receivedAccountIdText);
        context.newAccountId = context.receivedAccountIdText;
        // :754 SET FLG-ACCTFILTER-ISVALID TO TRUE
        context.accountFilterState = FieldEditState.IS_VALID;
        editAccountExit1210();
    }

    /**
     * The account-edit exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1210-EDIT-ACCOUNT-EXIT.} at line 758,
     * whose body is the {@code EXIT} statement at {@code :759}. Targeted by the {@code GO TO} statements
     * at {@code :735} and {@code :750}.</p>
     */
    private void editAccountExit1210() {
        // :759 EXIT
    }

    /**
     * Validates the card-number filter.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1220-EDIT-CARD.} at line 762.</p>
     *
     * <p>Same shape as {@code 1210} with one difference that must be preserved:
     * <strong>the two failure branches clear the new card id to two different values.</strong> The blank
     * branch moves ZEROES into both the commarea field and the new-values field in one multi-target
     * statement at {@code :777-778}; the class-test branch moves ZERO into the commarea field at
     * {@code :792} but LOW-VALUES into the new-values field at {@code :793}. That asymmetry is recorded
     * distinctly and is reproduced rather than harmonised.</p>
     *
     * <p>Success at {@code :796-797} is also asymmetric between the two targets: the commarea receives
     * the numeric redefinition {@code CC-CARD-NUM-N} while the new-values field receives the
     * alphanumeric {@code CC-CARD-NUM}.</p>
     *
     * @param context the per-request state
     */
    private void editCard1220(final UpdateContext context) {
        // :765 SET FLG-CARDFILTER-NOT-OK TO TRUE
        context.cardFilterState = FieldEditState.NOT_OK;
        // :768-770 IF CC-CARD-NUM EQUAL LOW-VALUES OR CC-CARD-NUM EQUAL SPACES OR CC-CARD-NUM-N EQUAL
        //          ZEROS
        if (isBlankOrLowValues(context.receivedCardNumberText)
                || isAllZeroes(context.receivedCardNumberText)) {
            // :771 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :772 SET FLG-CARDFILTER-BLANK TO TRUE
            context.cardFilterState = FieldEditState.BLANK;
            // :773-775 IF WS-RETURN-MSG-OFF SET WS-PROMPT-FOR-CARD TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_PROMPT_FOR_CARD;
            }
            // :777-778 MOVE ZEROES TO CDEMO-CARD-NUM CCUP-NEW-CARDID - one multi-target statement, so
            //          BOTH receive ZEROES here, unlike the branch below.
            context.commareaCardNumber = 0L;
            context.newCardId = CARD_NUMBER_ZEROES;
            // :779 GO TO 1220-EDIT-CARD-EXIT
            editCardExit1220();
            return;
        }
        // :784 IF CC-CARD-NUM IS NOT NUMERIC - all sixteen positions must hold a digit.
        if (!isNumericClass(context.receivedCardNumberText, WIDTH_CARD_NUMBER)) {
            // :785 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :786 SET FLG-CARDFILTER-NOT-OK TO TRUE
            context.cardFilterState = FieldEditState.NOT_OK;
            // :787-791 IF WS-RETURN-MSG-OFF MOVE the inline literal at :789 TO WS-RETURN-MSG
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_CARD_FILTER_NOT_NUMERIC;
            }
            // :792 MOVE ZERO TO CDEMO-CARD-NUM
            context.commareaCardNumber = 0L;
            // :793 MOVE LOW-VALUES TO CCUP-NEW-CARDID - LOW-VALUES here, ZEROES above.
            context.newCardId = null;
            // :794 GO TO 1220-EDIT-CARD-EXIT
            editCardExit1220();
            return;
        }
        // :795-796 ELSE MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM - the numeric view is the SOURCE of this
        //          move, not its target, and it is already current from :603.
        context.commareaCardNumber = parseDigits(context.receivedCardNumberText);
        // :797 MOVE CC-CARD-NUM TO CCUP-NEW-CARDID
        context.newCardId = context.receivedCardNumberText;
        // :798 SET FLG-CARDFILTER-ISVALID TO TRUE
        context.cardFilterState = FieldEditState.IS_VALID;
        editCardExit1220();
    }

    /**
     * The card-edit exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1220-EDIT-CARD-EXIT.} at line 802, whose
     * body is the {@code EXIT} statement at {@code :803}. Targeted by the {@code GO TO} statements at
     * {@code :779} and {@code :794}.</p>
     */
    private void editCardExit1220() {
        // :803 EXIT
    }

    /**
     * Validates the embossed cardholder name.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1230-EDIT-NAME.} at line 806.</p>
     *
     * <p>Pessimistic default at {@code :808}. The blank gate at {@code :811-820} is a three-way test for
     * low values, spaces or zeros and reports "Card name not provided" under the guard at
     * {@code :816-818}.</p>
     *
     * <p>The alphabetic check at {@code :823-837} carries the source comment "Only Alphabets and space
     * allowed" and is expressed as an idiom rather than a predicate: {@code :823} copies the value into
     * a work field, {@code :824-826} runs {@code INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM
     * TO LIT-ALL-SPACES-TO} so that every letter becomes a space, and {@code :828} then asserts that
     * the trimmed length of what remains is zero. Anything that is not a letter survives the conversion
     * and makes the length non-zero. <strong>The outcome is therefore: valid if and only if every
     * character is an ASCII letter or a space.</strong> Digits and punctuation are rejected.</p>
     *
     * <p>The outcome is implemented directly as a per-character test rather than by simulating the
     * {@code INSPECT}. No regular expression is introduced, because the source has none, and no accented
     * or non-ASCII letter is accepted, because {@code LIT-ALL-ALPHA-FROM} at {@code :255-257} contains
     * only the 52 ASCII letters.</p>
     *
     * @param context the per-request state
     */
    private void editName1230(final UpdateContext context) {
        // :808 SET FLG-CARDNAME-NOT-OK TO TRUE
        context.cardNameState = FieldEditState.NOT_OK;
        // :811-813 IF CCUP-NEW-CRDNAME EQUAL LOW-VALUES OR SPACES OR ZEROS
        if (isBlankOrLowValues(context.newCardholderName)
                || isAllZeroes(context.newCardholderName)) {
            // :814 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :815 SET FLG-CARDNAME-BLANK TO TRUE
            context.cardNameState = FieldEditState.BLANK;
            // :816-818 IF WS-RETURN-MSG-OFF SET WS-PROMPT-FOR-NAME TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_PROMPT_FOR_NAME;
            }
            // :819 GO TO 1230-EDIT-NAME-EXIT
            editNameExit1230();
            return;
        }
        // :823 MOVE CCUP-NEW-CRDNAME TO CARD-NAME-CHECK - a method-local copy, never a bean field.
        final String cardNameCheck = padRight(context.newCardholderName, WIDTH_EMBOSSED_NAME);
        // :824-826 INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALL-SPACES-TO
        // :828-829 IF FUNCTION LENGTH(FUNCTION TRIM(CARD-NAME-CHECK)) = 0 CONTINUE
        if (!isAllLettersOrSpaces(cardNameCheck)) {
            // :830-831 ELSE SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :832 SET FLG-CARDNAME-NOT-OK TO TRUE
            context.cardNameState = FieldEditState.NOT_OK;
            // :833-835 IF WS-RETURN-MSG-OFF SET WS-NAME-MUST-BE-ALPHA TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_NAME_MUST_BE_ALPHA;
            }
            // :836 GO TO 1230-EDIT-NAME-EXIT
            editNameExit1230();
            return;
        }
        // :839 SET FLG-CARDNAME-ISVALID TO TRUE
        context.cardNameState = FieldEditState.IS_VALID;
        editNameExit1230();
    }

    /**
     * The name-edit exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1230-EDIT-NAME-EXIT.} at line 841, whose
     * body is the {@code EXIT} statement at {@code :842}. Targeted by the {@code GO TO} statements at
     * {@code :819} and {@code :836}.</p>
     */
    private void editNameExit1230() {
        // :842 EXIT
    }

    /**
     * Validates the card active status.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1240-EDIT-CARDSTATUS.} at line 845.</p>
     *
     * <p>Pessimistic default at {@code :847}, blank gate at {@code :850-859}, then the value test at
     * {@code :861-872}: {@code :861} moves the value into {@code FLG-YES-NO-CHECK} and {@code :863}
     * tests {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} declared at {@code :91}.
     * <strong>Only upper-case {@code Y} and {@code N} are accepted</strong>; the source performs no case
     * folding at this site, so {@code y}, {@code n}, {@code 1}, {@code 0}, {@code true} and
     * {@code false} are all rejected.</p>
     *
     * <p>The <strong>same</strong> message literal serves both the blank outcome at {@code :856} and the
     * invalid outcome at {@code :869}. No second message is invented.</p>
     *
     * @param context the per-request state
     */
    private void editCardStatus1240(final UpdateContext context) {
        // :847 SET FLG-CARDSTATUS-NOT-OK TO TRUE
        context.cardStatusState = FieldEditState.NOT_OK;
        // :850-852 IF CCUP-NEW-CRDSTCD EQUAL LOW-VALUES OR SPACES OR ZEROS
        if (isBlankOrLowValues(context.newCardStatusCode)
                || isAllZeroes(context.newCardStatusCode)) {
            // :853 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :854 SET FLG-CARDSTATUS-BLANK TO TRUE
            context.cardStatusState = FieldEditState.BLANK;
            // :855-857 IF WS-RETURN-MSG-OFF SET CARD-STATUS-MUST-BE-YES-NO TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_CARD_STATUS_MUST_BE_YES_NO;
            }
            // :858 GO TO 1240-EDIT-CARDSTATUS-EXIT
            editCardStatusExit1240();
            return;
        }
        // :861 MOVE CCUP-NEW-CRDSTCD TO FLG-YES-NO-CHECK - method-local, never a bean field.
        final String yesNoCheck = context.newCardStatusCode;
        // :863 IF FLG-YES-NO-VALID
        if (CARD_STATUS_ACTIVE.equals(yesNoCheck) || CARD_STATUS_INACTIVE.equals(yesNoCheck)) {
            // :864 SET FLG-CARDSTATUS-ISVALID TO TRUE
            context.cardStatusState = FieldEditState.IS_VALID;
        } else {
            // :865-866 ELSE SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :867 SET FLG-CARDSTATUS-NOT-OK TO TRUE
            context.cardStatusState = FieldEditState.NOT_OK;
            // :868-870 IF WS-RETURN-MSG-OFF SET CARD-STATUS-MUST-BE-YES-NO TO TRUE - the same literal
            //          the blank branch uses.
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_CARD_STATUS_MUST_BE_YES_NO;
            }
            // :871 GO TO 1240-EDIT-CARDSTATUS-EXIT
            editCardStatusExit1240();
            return;
        }
        editCardStatusExit1240();
    }

    /**
     * The status-edit exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1240-EDIT-CARDSTATUS-EXIT.} at line 874,
     * whose body is the {@code EXIT} statement at {@code :875}. Targeted by the {@code GO TO} statements
     * at {@code :858} and {@code :871}.</p>
     */
    private void editCardStatusExit1240() {
        // :875 EXIT
    }

    /**
     * Validates the card expiry month.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1250-EDIT-EXPIRY-MON.} at line 877.</p>
     *
     * <p>Pessimistic default at {@code :880}, <em>before</em> the blank test, matching {@code 1240} and
     * differing from {@code 1260}. Blank gate at {@code :883-892}, then {@code :896} moves the value
     * into {@code CARD-MONTH-CHECK} and {@code :898} tests
     * {@code 88 VALID-MONTH VALUES 1 THRU 12} declared at {@code :95}, so the accepted range is
     * <strong>1 to 12 inclusive</strong>. The condition name is declared over the numeric redefinition
     * {@code CARD-MONTH-CHECK-N PIC 9(2)}, so a non-numeric value cannot satisfy it.</p>
     *
     * <p>One literal serves both the blank outcome at {@code :889} and the invalid outcome at
     * {@code :904}.</p>
     *
     * @param context the per-request state
     */
    private void editExpiryMonth1250(final UpdateContext context) {
        // :880 SET FLG-CARDEXPMON-NOT-OK TO TRUE
        context.expiryMonthState = FieldEditState.NOT_OK;
        // :883-885 IF CCUP-NEW-EXPMON EQUAL LOW-VALUES OR SPACES OR ZEROS
        if (isBlankOrLowValues(context.newExpiryMonth) || isAllZeroes(context.newExpiryMonth)) {
            // :886 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :887 SET FLG-CARDEXPMON-BLANK TO TRUE
            context.expiryMonthState = FieldEditState.BLANK;
            // :888-890 IF WS-RETURN-MSG-OFF SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_CARD_EXPIRY_MONTH_NOT_VALID;
            }
            // :891 GO TO 1250-EDIT-EXPIRY-MON-EXIT
            editExpiryMonthExit1250();
            return;
        }
        // :896 MOVE CCUP-NEW-EXPMON TO CARD-MONTH-CHECK - method-local, never a bean field.
        final Long monthCheck = parseNumericClass(context.newExpiryMonth, WIDTH_EXPIRY_MONTH);
        // :898 IF VALID-MONTH
        if (monthCheck != null && monthCheck >= MIN_EXPIRY_MONTH && monthCheck <= MAX_EXPIRY_MONTH) {
            // :899 SET FLG-CARDEXPMON-ISVALID TO TRUE
            context.expiryMonthState = FieldEditState.IS_VALID;
        } else {
            // :900-901 ELSE SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :902 SET FLG-CARDEXPMON-NOT-OK TO TRUE
            context.expiryMonthState = FieldEditState.NOT_OK;
            // :903-905 IF WS-RETURN-MSG-OFF SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_CARD_EXPIRY_MONTH_NOT_VALID;
            }
            // :906 GO TO 1250-EDIT-EXPIRY-MON-EXIT
            editExpiryMonthExit1250();
            return;
        }
        editExpiryMonthExit1250();
    }

    /**
     * The month-edit exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1250-EDIT-EXPIRY-MON-EXIT.} at line 910,
     * whose body is the {@code EXIT} statement at {@code :911}. Targeted by the {@code GO TO} statements
     * at {@code :891} and {@code :906}.</p>
     */
    private void editExpiryMonthExit1250() {
        // :911 EXIT
    }

    /**
     * Validates the card expiry year.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1260-EDIT-EXPIRY-YEAR.} at line 913.</p>
     *
     * <p><strong>Statement order differs from its two siblings and is transcribed as written.</strong>
     * {@code 1240} and {@code 1250} set their pessimistic not-ok state before the blank test, at
     * {@code :847} and {@code :880}; this paragraph runs the blank test first, at {@code :916-925}, and
     * only then sets it, at {@code :930}. The blank branch therefore sets the blank state alone. Because
     * these are condition names over a single flag byte the resulting state is identical either way, so
     * the difference is invisible in behaviour, but the order is not reordered to match the siblings.
     *</p>
     *
     * <p>{@code :932} moves the value into {@code CARD-YEAR-CHECK} and {@code :934} tests
     * {@code 88 VALID-YEAR VALUES 1950 THRU 2099} declared at {@code :99}, so the accepted range is
     * <strong>1950 to 2099 inclusive</strong>. The comment block at {@code :927-928} reads "Must be
     * numeric" and "Must be 1 to 12", the latter copy-pasted from the month paragraph; the code
     * validates the year range, and the copy-pasted comment is left as written rather than acted on.</p>
     *
     * <p>One literal serves both the blank outcome at {@code :922} and the invalid outcome at
     * {@code :940}.</p>
     *
     * @param context the per-request state
     */
    private void editExpiryYear1260(final UpdateContext context) {
        // :916-918 IF CCUP-NEW-EXPYEAR EQUAL LOW-VALUES OR SPACES OR ZEROS - note that no pessimistic
        //          state has been set yet at this point, unlike 1240 and 1250.
        if (isBlankOrLowValues(context.newExpiryYear) || isAllZeroes(context.newExpiryYear)) {
            // :919 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :920 SET FLG-CARDEXPYEAR-BLANK TO TRUE
            context.expiryYearState = FieldEditState.BLANK;
            // :921-923 IF WS-RETURN-MSG-OFF SET CARD-EXPIRY-YEAR-NOT-VALID TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_CARD_EXPIRY_YEAR_NOT_VALID;
            }
            // :924 GO TO 1260-EDIT-EXPIRY-YEAR-EXIT
            editExpiryYearExit1260();
            return;
        }
        // :927-928 the comment defect: "Must be numeric" / "Must be 1 to 12", the latter wrong.
        // :930 SET FLG-CARDEXPYEAR-NOT-OK TO TRUE - AFTER the blank test.
        context.expiryYearState = FieldEditState.NOT_OK;
        // :932 MOVE CCUP-NEW-EXPYEAR TO CARD-YEAR-CHECK - method-local, never a bean field.
        final Long yearCheck = parseNumericClass(context.newExpiryYear, WIDTH_EXPIRY_YEAR);
        // :934 IF VALID-YEAR
        if (yearCheck != null && yearCheck >= MIN_EXPIRY_YEAR && yearCheck <= MAX_EXPIRY_YEAR) {
            // :935 SET FLG-CARDEXPYEAR-ISVALID TO TRUE
            context.expiryYearState = FieldEditState.IS_VALID;
        } else {
            // :936-937 ELSE SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :938 SET FLG-CARDEXPYEAR-NOT-OK TO TRUE
            context.expiryYearState = FieldEditState.NOT_OK;
            // :939-941 IF WS-RETURN-MSG-OFF SET CARD-EXPIRY-YEAR-NOT-VALID TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_CARD_EXPIRY_YEAR_NOT_VALID;
            }
            // :942 GO TO 1260-EDIT-EXPIRY-YEAR-EXIT
            editExpiryYearExit1260();
            return;
        }
        editExpiryYearExit1260();
    }

    /**
     * The year-edit exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 1260-EDIT-EXPIRY-YEAR-EXIT.} at line 945,
     * whose body is the {@code EXIT} statement at {@code :946}. Targeted by the {@code GO TO} statements
     * at {@code :924} and {@code :942}.</p>
     *
     * <p>There is deliberately <strong>no</strong> {@code 1270-EDIT-EXPIRY-DAY} counterpart: the source
     * has no such paragraph, so the expiry day is never range-checked, numeric-checked or blank-checked,
     * and no validation the source lacks is added here.</p>
     */
    private void editExpiryYearExit1260() {
        // :946 EXIT
    }
    // 2000-DECIDE-ACTION. app/cbl/COCRDUPC.cbl:948-1031.

    /**
     * Decides what this task does, from the state the edits left behind.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 2000-DECIDE-ACTION.} at line 948.</p>
     *
     * <p><strong>Branch order is load-bearing and is preserved exactly.</strong>
     * {@code WHEN CCUP-CHANGES-OK-NOT-CONFIRMED} appears <em>twice</em> in this one
     * {@code EVALUATE TRUE}: guarded by {@code CCARD-AID-PFK05} at {@code :988-989}, and then again
     * unguarded at {@code :1006}. A COBOL {@code EVALUATE TRUE} runs only the first matching
     * {@code WHEN}, so the guarded form must be tested first or the save can never fire. This is not a
     * defect; reordering the Java chain to look tidier would silently disable the write path.</p>
     *
     * <p>Two of the seven branches are intentional no-ops that are nevertheless reachable, so they are
     * emitted explicitly rather than elided: {@code WHEN CCUP-CHANGES-NOT-OK} at {@code :982-983} and
     * the unguarded duplicate at {@code :1006-1007}. Neither is dead code.</p>
     *
     * @param context the per-request state
     */
    private void decideAction2000(final UpdateContext context) {
        // :954 WHEN CCUP-DETAILS-NOT-FETCHED
        // :958 WHEN CCARD-AID-PFK12 - two conditions sharing one body at :959-966. PF12 is CANCEL, and
        //      the source comment reads "CHANGES MADE. BUT USER CANCELS", so cancelling deliberately
        //      re-fetches from the store and discards whatever the caller had edited.
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED
                || context.attentionKey == AidKey.PFK12) {
            // :960-961 IF FLG-ACCTFILTER-ISVALID AND FLG-CARDFILTER-ISVALID
            if (context.accountFilterState == FieldEditState.IS_VALID
                    && context.cardFilterState == FieldEditState.IS_VALID) {
                // :962-963 PERFORM 9000-READ-DATA THRU 9000-READ-DATA-EXIT
                readData9000(context);
                // :964-965 IF FOUND-CARDS-FOR-ACCOUNT SET CCUP-SHOW-DETAILS TO TRUE
                if (INFO_FOUND_CARDS_FOR_ACCOUNT.equals(context.informationMessage)) {
                    context.changeAction = ChangeAction.SHOW_DETAILS;
                }
            }
            decideActionExit2000();
            return;
        }
        // :971 WHEN CCUP-SHOW-DETAILS
        if (context.changeAction == ChangeAction.SHOW_DETAILS) {
            // :972-976 IF INPUT-ERROR OR NO-CHANGES-DETECTED CONTINUE ELSE SET
            //          CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
            if (context.inputError || context.noChangesDetected) {
                // :974 CONTINUE
                context.changeAction = ChangeAction.SHOW_DETAILS;
            } else {
                // :976 SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
                context.changeAction = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
            }
            decideActionExit2000();
            return;
        }
        // :982 WHEN CCUP-CHANGES-NOT-OK -> :983 CONTINUE. An intentional no-op branch: the edits failed,
        //      so the screen simply redisplays with 3300 marking the offending fields. Reachable, and
        //      therefore emitted rather than elided.
        if (context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            // :983 CONTINUE
            decideActionExit2000();
            return;
        }
        // :988-989 WHEN CCUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05 - PF05 is CONFIRM SAVE, and
        //          this guarded branch MUST be tested before the unguarded one below.
        if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                && context.attentionKey == AidKey.PFK05) {
            // :990-991 PERFORM 9200-WRITE-PROCESSING THRU 9200-WRITE-PROCESSING-EXIT
            writeProcessing9200(context);
            // :992-1001 the inner EVALUATE TRUE, in exactly this order.
            switch (context.writeOutcome) {
                // :993-994 WHEN COULD-NOT-LOCK-FOR-UPDATE SET CCUP-CHANGES-OKAYED-LOCK-ERROR
                case COULD_NOT_LOCK_FOR_UPDATE ->
                        context.changeAction = ChangeAction.CHANGES_OKAYED_LOCK_ERROR;
                // :995-996 WHEN LOCKED-BUT-UPDATE-FAILED SET CCUP-CHANGES-OKAYED-BUT-FAILED
                case LOCKED_BUT_UPDATE_FAILED ->
                        context.changeAction = ChangeAction.CHANGES_OKAYED_BUT_FAILED;
                // :997-998 WHEN DATA-WAS-CHANGED-BEFORE-UPDATE SET CCUP-SHOW-DETAILS - note that this
                //          returns to the refreshed-detail state and is NOT a terminal error, which is
                //          precisely why the three write outcomes must stay distinguishable.
                case DATA_WAS_CHANGED_BEFORE_UPDATE ->
                        context.changeAction = ChangeAction.SHOW_DETAILS;
                // :999-1000 WHEN OTHER SET CCUP-CHANGES-OKAYED-AND-DONE - the success path.
                case COMPLETED, NOT_ATTEMPTED ->
                        context.changeAction = ChangeAction.CHANGES_OKAYED_AND_DONE;
            }
            decideActionExit2000();
            return;
        }
        // :1006 WHEN CCUP-CHANGES-OK-NOT-CONFIRMED -> :1007 CONTINUE. The same condition as :988 with
        //       the PF05 guard removed: the edits are valid but confirmation was not given, so the
        //       screen redisplays with "Changes validated.Press F5 to save". Reachable no-op.
        if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            // :1007 CONTINUE
            decideActionExit2000();
            return;
        }
        // :1011 WHEN CCUP-CHANGES-OKAYED-AND-DONE - the source comment reads "SHOW CONFIRMATION. GO
        //       BACK TO SQUARE 1".
        if (context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            // :1012 SET CCUP-SHOW-DETAILS TO TRUE
            context.changeAction = ChangeAction.SHOW_DETAILS;
            // :1013-1017 IF CDEMO-FROM-TRANID EQUAL LOW-VALUES OR SPACES MOVE ZEROES TO CDEMO-ACCT-ID
            //            CDEMO-CARD-NUM, MOVE LOW-VALUES TO CDEMO-ACCT-STATUS
            if (isBlankOrLowValues(context.fromTransactionId)) {
                context.commareaAccountId = 0L;
                context.commareaCardNumber = 0L;
                context.commareaAccountStatus = null;
            }
            decideActionExit2000();
            return;
        }
        // :1019 WHEN OTHER - an unexpected state is fatal.
        // :1020 MOVE LIT-THISPGM TO ABEND-CULPRIT
        // :1021 MOVE '0001' TO ABEND-CODE
        // :1022 MOVE SPACES TO ABEND-REASON
        // :1023-1024 MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-MSG - note that this program puts the
        //            literal in ABEND-MSG and then performs the abend, unlike COCRDSLC which puts it in
        //            WS-RETURN-MSG and does not abend.
        // :1025-1026 PERFORM ABEND-ROUTINE THRU ABEND-ROUTINE-EXIT
        context.abendCode = UNEXPECTED_SCENARIO_ABEND_CODE;
        context.abendCulprit = PROGRAM_NAME;
        context.abendReason = ABEND_REASON_SPACES;
        context.abendMessage = UNEXPECTED_DATA_SCENARIO_MESSAGE;
        throw abendRoutine(context, null);
    }

    /**
     * The decide-action exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 2000-DECIDE-ACTION-EXIT.} at line 1029,
     * whose body is the {@code EXIT} statement at {@code :1030}.</p>
     */
    private void decideActionExit2000() {
        // :1030 EXIT
    }
    // 3000-SEND-MAP and its four helpers. app/cbl/COCRDUPC.cbl:1035-1340.

    /**
     * Renders the reply screen.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3000-SEND-MAP.} at line 1035.</p>
     *
     * <p>Five {@code PERFORM ... THRU} statements in a fixed order at {@code :1036-1045}: initialise the
     * output area, populate the data fields, choose the information message, choose the attributes and
     * colours, then send. The order is load-bearing, because {@code 3300} reads back values that
     * {@code 3200} wrote and overwrites them with an asterisk on the blank branches.</p>
     *
     * @param context the per-request state
     */
    private void sendMap3000(final UpdateContext context) {
        // :1036-1037 PERFORM 3100-SCREEN-INIT THRU 3100-SCREEN-INIT-EXIT
        screenInit3100(context);
        screenInitExit3100();
        // :1038-1039 PERFORM 3200-SETUP-SCREEN-VARS THRU 3200-SETUP-SCREEN-VARS-EXIT
        setupScreenVars3200(context);
        setupScreenVarsExit3200();
        // :1040-1041 PERFORM 3250-SETUP-INFOMSG THRU 3250-SETUP-INFOMSG-EXIT
        setupInfoMsg3250(context);
        setupInfoMsgExit3250();
        // :1042-1043 PERFORM 3300-SETUP-SCREEN-ATTRS THRU 3300-SETUP-SCREEN-ATTRS-EXIT
        setupScreenAttrs3300(context);
        setupScreenAttrsExit3300();
        // :1044-1045 PERFORM 3400-SEND-SCREEN THRU 3400-SEND-SCREEN-EXIT
        sendScreen3400(context);
        sendScreenExit3400();
        // :1046 falls through to 3000-SEND-MAP-EXIT, which all four PERFORM sites name as their THRU
        //       target (:495-496, :507-508, :524-525, :540-541).
        sendMapExit3000();
    }

    /**
     * The send-map exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3000-SEND-MAP-EXIT.} at line 1048, whose
     * body is the {@code EXIT} statement at {@code :1049}.</p>
     */
    private void sendMapExit3000() {
        // :1049 EXIT
    }

    /**
     * Clears the output map area and fills the six-field header that every one of the seventeen legacy
     * screens carries.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3100-SCREEN-INIT.} at line 1052.</p>
     *
     * <p>{@code :1053} clears the whole symbolic output area; {@code :1057-1060} move the two title
     * lines, the transaction identifier and the program name; {@code :1064-1068} render the date as
     * {@code MM/DD/YY} through the {@code WS-CURDATE-MM-DD-YY} group of {@code app/cpy/CSDAT01Y.cpy:30},
     * whose two {@code FILLER} bytes at {@code app/cpy/CSDAT01Y.cpy:32} and {@code :34} carry
     * {@code '/'}; {@code :1070-1074} render the time as {@code HH:MM:SS} through
     * {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy:36}, whose {@code FILLER} bytes at
     * {@code :38} and {@code :40} carry {@code ':'}. The year is taken two digits at a time from
     * {@code WS-CURDATE-YEAR(3:2)} at {@code :1066}, so the century is deliberately dropped.</p>
     *
     * <p><strong>The clock is read twice.</strong> {@code FUNCTION CURRENT-DATE} is evaluated
     * <em>twice</em>, at {@code :1055} and again at {@code :1062}, and the first result is overwritten
     * before any field reads it. The redundancy is preserved as a single clock read followed by a second
     * read, so the paragraph's observable behaviour is identical while the source's redundancy stays
     * visible. Both reads go through the injected {@link java.time.Clock}, never through
     * {@code LocalDateTime.now()}, so the rendering is deterministic under test.</p>
     *
     * @param context the per-request state
     */
    private void screenInit3100(final UpdateContext context) {
        // :1053 MOVE LOW-VALUES TO CCRDUPAO
        context.screen = new ScreenBuffer();
        // :1055 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - the first read, discarded at :1062.
        LocalDateTime currentDate = LocalDateTime.now(clock);
        // :1057 MOVE CCDA-TITLE01 TO TITLE01O, app/cpy/COTTL01Y.cpy:18
        context.screen.title01 = SCREEN_TITLE_01;
        // :1058 MOVE CCDA-TITLE02 TO TITLE02O, app/cpy/COTTL01Y.cpy:20
        context.screen.title02 = SCREEN_TITLE_02;
        // :1059 MOVE LIT-THISTRANID TO TRNNAMEO
        context.screen.transactionName = TRANSACTION_ID;
        // :1060 MOVE LIT-THISPGM TO PGMNAMEO
        context.screen.programName = PROGRAM_NAME;
        // :1062 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - the second read, which is the one every
        //       subsequent statement in this paragraph actually uses.
        currentDate = LocalDateTime.now(clock);
        // :1064-1066 MOVE WS-CURDATE-MONTH / -DAY / -YEAR(3:2) TO WS-CURDATE-MM / -DD / -YY
        // :1068 MOVE WS-CURDATE-MM-DD-YY TO CURDATEO
        context.screen.currentDate = CURRENT_DATE_FORMAT.format(currentDate);
        // :1070-1072 MOVE WS-CURTIME-HOURS / -MINUTE / -SECOND TO WS-CURTIME-HH / -MM / -SS
        // :1074 MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO
        context.screen.currentTime = CURRENT_TIME_FORMAT.format(currentDate);
    }

    /**
     * The screen-initialisation exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3100-SCREEN-INIT-EXIT.} at line 1078,
     * whose body is the {@code EXIT} statement at {@code :1079}.</p>
     */
    private void screenInitExit3100() {
        // :1079 EXIT
    }

    /**
     * Populates the two filter fields and the five card data fields of the output map.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3200-SETUP-SCREEN-VARS.} at line 1082.</p>
     *
     * <p>The whole paragraph is wrapped in {@code IF CDEMO-PGM-ENTER CONTINUE ELSE ...} at
     * {@code :1084-1133}, so a first-entry task deliberately leaves every data field cleared as
     * {@code 3100} left it. Zero is rendered as an empty field rather than as {@code 00000000000} by
     * {@code :1087-1091} and {@code :1093-1097}.</p>
     *
     * <p><strong>The carried expiry day is echoed as the OLD value on every branch.</strong>
     * {@code :1110}, {@code :1123} and {@code :1127} all move {@code CCUP-OLD-EXPDAY} to
     * {@code EXPDAYO}, and the {@code MOVE CCUP-NEW-EXPDAY} that would have echoed the edited day sits
     * <em>commented out</em> at {@code :1122} beneath a comment block at {@code :1119-1121} reading
     * "MOVE OLD VALUES TO NON-DISPLAY FIELDS THAT WE ARE NOT ALLOWING USER TO CHANGE(FOR NOW)".
     * {@code :1122} is <strong>not</strong> resurrected here.</p>
     *
     * <p><strong>One assignment in the multi-target MOVE is a duplicate.</strong> The multi-target
     * {@code MOVE LOW-VALUES} of the
     * {@code CCUP-DETAILS-NOT-FETCHED} branch names {@code CRDNAMEO OF CCRDUPAO} twice, at
     * {@code :1101} and again at {@code :1102}, before naming the other four fields at
     * {@code :1103-1106}. The duplicate assignment is a no-op and is recorded rather than reproduced as
     * two Java statements.</p>
     *
     * @param context the per-request state
     */
    private void setupScreenVars3200(final UpdateContext context) {
        // :1084-1086 IF CDEMO-PGM-ENTER CONTINUE
        if (context.programContextEnter) {
            // :1085 CONTINUE - a first-entry task shows an entirely empty screen.
            return;
        }
        // :1087-1091 IF CC-ACCT-ID-N = 0 MOVE LOW-VALUES TO ACCTSIDO ELSE MOVE CC-ACCT-ID TO ACCTSIDO
        context.screen.accountId = context.receivedAccountId == null || context.receivedAccountId == 0L
                ? null
                : padLeftZeroes(Long.toString(context.receivedAccountId), WIDTH_ACCOUNT_ID);
        // :1093-1097 IF CC-CARD-NUM-N = 0 MOVE LOW-VALUES TO CARDSIDO ELSE MOVE CC-CARD-NUM TO CARDSIDO
        context.screen.cardNumber = context.receivedCardNumber == null || context.receivedCardNumber == 0L
                ? null
                : padLeftZeroes(Long.toString(context.receivedCardNumber), WIDTH_CARD_NUMBER);
        // :1099-1130 EVALUATE TRUE over the state machine, choosing which side of the snapshot to echo.
        //            Expressed as an ordered chain rather than a Java switch because the third branch
        //            tests a GROUP condition, CCUP-CHANGES-MADE, that spans five of the seven states.
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED) {
            // :1100-1106 WHEN CCUP-DETAILS-NOT-FETCHED - clear all five data fields. CRDNAMEO is named
            //            twice, at :1101 and :1102.
            context.screen.cardholderName = null;
            context.screen.cardStatusCode = null;
            context.screen.expiryDay = null;
            context.screen.expiryMonth = null;
            context.screen.expiryYear = null;
        } else if (context.changeAction == ChangeAction.SHOW_DETAILS) {
            // :1107-1112 WHEN CCUP-SHOW-DETAILS - echo the stored snapshot, including the OLD day.
            context.screen.cardholderName = context.oldCardholderName;
            context.screen.cardStatusCode = context.oldCardStatusCode;
            // :1110 MOVE CCUP-OLD-EXPDAY TO EXPDAYO
            context.screen.expiryDay = context.oldExpiryDay;
            context.screen.expiryMonth = context.oldExpiryMonth;
            context.screen.expiryYear = context.oldExpiryYear;
        } else if (context.changeAction.isChangesMade()) {
            // :1113-1123 WHEN CCUP-CHANGES-MADE - echo the edited name, status, month and year, but the
            //            OLD day, because :1122 is commented out.
            context.screen.cardholderName = context.newCardholderName;
            context.screen.cardStatusCode = context.newCardStatusCode;
            context.screen.expiryMonth = context.newExpiryMonth;
            context.screen.expiryYear = context.newExpiryYear;
            // :1122 * MOVE CCUP-NEW-EXPDAY TO EXPDAYO - commented out in the source, not resurrected.
            // :1123 MOVE CCUP-OLD-EXPDAY TO EXPDAYO
            context.screen.expiryDay = context.oldExpiryDay;
        } else {
            // :1124-1129 WHEN OTHER - echoes the stored snapshot unchanged. Reachable only if the state
            //            byte holds a value none of the eight 88-levels at :278-290 declares, which the
            //            enumeration makes impossible; retained because the source declares it and the
            //            Java language requires a total chain.
            context.screen.cardholderName = context.oldCardholderName;
            context.screen.cardStatusCode = context.oldCardStatusCode;
            // :1127 MOVE CCUP-OLD-EXPDAY TO EXPDAYO
            context.screen.expiryDay = context.oldExpiryDay;
            context.screen.expiryMonth = context.oldExpiryMonth;
            context.screen.expiryYear = context.oldExpiryYear;
        }
    }

    /**
     * The screen-variable exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3200-SETUP-SCREEN-VARS-EXIT.} at line
     * 1135, whose body is the {@code EXIT} statement at {@code :1136}.</p>
     */
    private void setupScreenVarsExit3200() {
        // :1136 EXIT
    }

    /**
     * Chooses the information message from the state machine, then moves both messages onto the map.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3250-SETUP-INFOMSG.} at line 1138.</p>
     *
     * <p>The {@code EVALUATE TRUE} at {@code :1140-1159} has nine branches. Two details matter and both
     * are preserved. First, {@code :1141-1142 WHEN CDEMO-PGM-ENTER} is tested <em>before</em> any state
     * branch, so a first-entry task always prompts for the search keys whatever the state field holds.
     * Second, {@code :1157-1158 WHEN WS-NO-INFO-MESSAGE} is a fall-through default expressed as an
     * explicit condition rather than as {@code WHEN OTHER}, which means a state that matches none of the
     * eight preceding branches <em>and</em> already carries a message keeps that message.</p>
     *
     * <p>{@code :1161} moves {@code WS-INFO-MSG} to {@code INFOMSGO} and {@code :1163} moves
     * {@code WS-RETURN-MSG} to {@code ERRMSGO}, which is why the error line is populated from the same
     * field that {@code COMMON-RETURN} copies to the caller at {@code :547}.</p>
     *
     * @param context the per-request state
     */
    private void setupInfoMsg3250(final UpdateContext context) {
        // :1140-1159 EVALUATE TRUE
        if (context.programContextEnter) {
            // :1141-1142 WHEN CDEMO-PGM-ENTER SET PROMPT-FOR-SEARCH-KEYS TO TRUE
            context.informationMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        } else if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED) {
            // :1143-1144 WHEN CCUP-DETAILS-NOT-FETCHED SET PROMPT-FOR-SEARCH-KEYS TO TRUE
            context.informationMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        } else if (context.changeAction == ChangeAction.SHOW_DETAILS) {
            // :1145-1146 WHEN CCUP-SHOW-DETAILS SET FOUND-CARDS-FOR-ACCOUNT TO TRUE
            context.informationMessage = INFO_FOUND_CARDS_FOR_ACCOUNT;
        } else if (context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            // :1147-1148 WHEN CCUP-CHANGES-NOT-OK SET PROMPT-FOR-CHANGES TO TRUE
            context.informationMessage = INFO_PROMPT_FOR_CHANGES;
        } else if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            // :1149-1150 WHEN CCUP-CHANGES-OK-NOT-CONFIRMED SET PROMPT-FOR-CONFIRMATION TO TRUE
            context.informationMessage = INFO_PROMPT_FOR_CONFIRMATION;
        } else if (context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            // :1151-1152 WHEN CCUP-CHANGES-OKAYED-AND-DONE SET CONFIRM-UPDATE-SUCCESS TO TRUE
            context.informationMessage = INFO_CONFIRM_UPDATE_SUCCESS;
        } else if (context.changeAction == ChangeAction.CHANGES_OKAYED_LOCK_ERROR) {
            // :1153-1154 WHEN CCUP-CHANGES-OKAYED-LOCK-ERROR SET INFORM-FAILURE TO TRUE
            context.informationMessage = INFO_INFORM_FAILURE;
        } else if (context.changeAction == ChangeAction.CHANGES_OKAYED_BUT_FAILED) {
            // :1155-1156 WHEN CCUP-CHANGES-OKAYED-BUT-FAILED SET INFORM-FAILURE TO TRUE
            context.informationMessage = INFO_INFORM_FAILURE;
        } else if (context.informationMessage == null || context.informationMessage.isEmpty()) {
            // :1157-1158 WHEN WS-NO-INFO-MESSAGE SET PROMPT-FOR-SEARCH-KEYS TO TRUE - an explicit
            //            condition rather than a WHEN OTHER, so an already-populated message survives.
            context.informationMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        }
        // :1161 MOVE WS-INFO-MSG TO INFOMSGO
        context.screen.informationMessage = context.informationMessage;
        // :1163 MOVE WS-RETURN-MSG TO ERRMSGO
        context.screen.errorMessage = context.returnMessage;
    }

    /**
     * The information-message exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3250-SETUP-INFOMSG-EXIT.} at line 1165,
     * whose body is the {@code EXIT} statement at {@code :1166}.</p>
     */
    private void setupInfoMsgExit3250() {
        // :1166 EXIT
    }

    /**
     * Chooses the protection attribute of every input field, positions the cursor, and marks the fields
     * that failed their edit.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3300-SETUP-SCREEN-ATTRS.} at line
     * 1168.</p>
     *
     * <p>Three sections, executed in this order. The {@code EVALUATE TRUE} at {@code :1171-1198} decides
     * which fields are enterable, protecting the two filters once the details are fetched and protecting
     * everything once the changes are confirmed. The {@code EVALUATE TRUE} at {@code :1201-1226}
     * positions the cursor on exactly one field by moving {@code -1} to its length subfield. The
     * statement run at {@code :1229-1317} then applies colour and, on the blank branches only, stamps an
     * asterisk into the field.</p>
     *
     * <p>Four details are load-bearing. The asterisk is stamped <strong>only</strong> on a
     * {@code FLG-...-BLANK} state, never on a {@code FLG-...-NOT-OK} state, which is exactly the
     * two-state contract {@code ValidationException} carries. The two filter fields are additionally
     * guarded by {@code AND CDEMO-PGM-REENTER} at {@code :1246} and {@code :1254}, while the four data
     * fields are guarded by {@code AND CCUP-CHANGES-NOT-OK} at {@code :1262}, {@code :1270},
     * {@code :1280} and {@code :1288}. The expiry day is made non-display by an
     * <strong>unconditional</strong> {@code MOVE DFHBMDAR TO EXPDAYC} at {@code :1277}, which is the
     * screen-level counterpart of the day never being validated. And arriving from the card list
     * neutralises both filter colours at {@code :1232-1235}.</p>
     *
     * <p>The {@code DFHBMFSE}, {@code DFHBMPRF}, {@code DFHBMDAR}, {@code DFHBMBRY}, {@code DFHRED} and
     * {@code DFHDFCOL} symbols come from the CICS-supplied {@code DFHBMSCA} and {@code DFHATTR}
     * copybooks, which are not present in this repository, so they are modelled as
     * {@link FieldAttribute} and {@link FieldColour} rather than imported.</p>
     *
     * @param context the per-request state
     */
    private void setupScreenAttrs3300(final UpdateContext context) {
        // :1171-1198 EVALUATE TRUE - which fields may the caller enter?
        switch (context.changeAction) {
            // :1172-1178 WHEN CCUP-DETAILS-NOT-FETCHED - the filters are open, the data fields are not.
            case DETAILS_NOT_FETCHED -> {
                context.screen.accountIdAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
                context.screen.cardNumberAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
                context.screen.cardholderNameAttribute = FieldAttribute.PROTECTED;
                context.screen.cardStatusAttribute = FieldAttribute.PROTECTED;
                context.screen.expiryMonthAttribute = FieldAttribute.PROTECTED;
                context.screen.expiryYearAttribute = FieldAttribute.PROTECTED;
            }
            // :1180-1181 WHEN CCUP-SHOW-DETAILS / WHEN CCUP-CHANGES-NOT-OK - two conditions sharing one
            //            body at :1182-1188. The filters lock and the data fields open.
            case SHOW_DETAILS, CHANGES_NOT_OK -> {
                context.screen.accountIdAttribute = FieldAttribute.PROTECTED;
                context.screen.cardNumberAttribute = FieldAttribute.PROTECTED;
                context.screen.cardholderNameAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
                context.screen.cardStatusAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
                context.screen.expiryMonthAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
                context.screen.expiryYearAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
            }
            // :1189-1190 WHEN CCUP-CHANGES-OK-NOT-CONFIRMED / WHEN CCUP-CHANGES-OKAYED-AND-DONE - two
            //            conditions sharing one body at :1191-1192: everything is protected, because the
            //            caller is being asked to confirm or is being shown the outcome.
            case CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_AND_DONE -> {
                context.screen.accountIdAttribute = FieldAttribute.PROTECTED;
                context.screen.cardNumberAttribute = FieldAttribute.PROTECTED;
                context.screen.cardholderNameAttribute = FieldAttribute.PROTECTED;
                context.screen.cardStatusAttribute = FieldAttribute.PROTECTED;
                context.screen.expiryMonthAttribute = FieldAttribute.PROTECTED;
                context.screen.expiryYearAttribute = FieldAttribute.PROTECTED;
            }
            // :1193-1197 WHEN OTHER - the two lock-error and update-failed states reopen the filters.
            default -> {
                context.screen.accountIdAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
                context.screen.cardNumberAttribute = FieldAttribute.UNPROTECTED_MODIFIED;
                context.screen.cardholderNameAttribute = FieldAttribute.PROTECTED;
                context.screen.cardStatusAttribute = FieldAttribute.PROTECTED;
                context.screen.expiryMonthAttribute = FieldAttribute.PROTECTED;
                context.screen.expiryYearAttribute = FieldAttribute.PROTECTED;
            }
        }
        // :1201-1226 EVALUATE TRUE - position the cursor on exactly one field, by moving -1 to its
        //            length subfield. Branch order is preserved: the two message states are tested
        //            before any FLG- state, so a successful fetch parks the cursor on the name.
        if (INFO_FOUND_CARDS_FOR_ACCOUNT.equals(context.informationMessage)
                || context.noChangesDetected) {
            // :1202-1204 WHEN FOUND-CARDS-FOR-ACCOUNT / WHEN NO-CHANGES-DETECTED -> MOVE -1 TO CRDNAMEL
            context.screen.cursorField = FIELD_CARDHOLDER_NAME;
        } else if (context.accountFilterState != FieldEditState.IS_VALID) {
            // :1205-1207 WHEN FLG-ACCTFILTER-NOT-OK / WHEN FLG-ACCTFILTER-BLANK -> MOVE -1 TO ACCTSIDL
            context.screen.cursorField = FIELD_ACCOUNT_ID;
        } else if (context.cardFilterState != FieldEditState.IS_VALID) {
            // :1208-1210 WHEN FLG-CARDFILTER-NOT-OK / WHEN FLG-CARDFILTER-BLANK -> MOVE -1 TO CARDSIDL
            context.screen.cursorField = FIELD_CARD_NUMBER;
        } else if (context.cardNameState != FieldEditState.IS_VALID) {
            // :1211-1213 WHEN FLG-CARDNAME-NOT-OK / WHEN FLG-CARDNAME-BLANK -> MOVE -1 TO CRDNAMEL
            context.screen.cursorField = FIELD_CARDHOLDER_NAME;
        } else if (context.cardStatusState != FieldEditState.IS_VALID) {
            // :1214-1216 WHEN FLG-CARDSTATUS-NOT-OK / WHEN FLG-CARDSTATUS-BLANK -> MOVE -1 TO CRDSTCDL
            context.screen.cursorField = FIELD_CARD_STATUS_CODE;
        } else if (context.expiryMonthState != FieldEditState.IS_VALID) {
            // :1217-1219 WHEN FLG-CARDEXPMON-NOT-OK / WHEN FLG-CARDEXPMON-BLANK -> MOVE -1 TO EXPMONL
            context.screen.cursorField = FIELD_EXPIRY_MONTH;
        } else if (context.expiryYearState != FieldEditState.IS_VALID) {
            // :1220-1222 WHEN FLG-CARDEXPYEAR-NOT-OK / WHEN FLG-CARDEXPYEAR-BLANK -> MOVE -1 TO EXPYEARL
            context.screen.cursorField = FIELD_EXPIRY_YEAR;
        } else {
            // :1223-1225 WHEN OTHER -> MOVE -1 TO ACCTSIDL
            context.screen.cursorField = FIELD_ACCOUNT_ID;
        }
        setupScreenColours3300(context);
    }

    /**
     * The colour-and-marker run of {@code 3300-SETUP-SCREEN-ATTRS}, split out purely so that the
     * paragraph's three sections remain individually readable while staying a single unit of control
     * flow; it is invoked once, unconditionally, from the tail of {@link #setupScreenAttrs3300}.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3300-SETUP-SCREEN-ATTRS.} at lines
     * 1229-1317.</p>
     *
     * <p>Statement order is preserved exactly as written, because the asterisk stamps at {@code :1247},
     * {@code :1255}, {@code :1265}, {@code :1275}, {@code :1284} and {@code :1292} overwrite values that
     * {@code 3200-SETUP-SCREEN-VARS} has already placed in the same fields, and the unconditional
     * non-display of the expiry day at {@code :1277} sits between the card-status and expiry-month
     * tests rather than at the end.</p>
     *
     * @param context the per-request state
     */
    private void setupScreenColours3300(final UpdateContext context) {
        // :1232-1235 IF CDEMO-LAST-MAPSET EQUAL LIT-CCLISTMAPSET MOVE DFHDFCOL TO ACCTSIDC CARDSIDC -
        //            arriving from the card list neutralises both filter colours.
        if (CARD_LIST_MAPSET.equals(context.lastMapset)) {
            context.screen.accountIdColour = FieldColour.DEFAULT;
            context.screen.cardNumberColour = FieldColour.DEFAULT;
        }
        // :1238-1240 IF FLG-ACCTFILTER-NOT-OK MOVE DFHRED TO ACCTSIDC
        if (context.accountFilterState == FieldEditState.NOT_OK) {
            context.screen.accountIdColour = FieldColour.RED;
        }
        // :1245-1250 IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER MOVE '*' TO ACCTSIDO, DFHRED TO
        //            ACCTSIDC. The asterisk is stamped on the blank state only.
        if (context.accountFilterState == FieldEditState.BLANK && !context.programContextEnter) {
            // :1247 MOVE '*' TO ACCTSIDO OF CCRDUPAO
            context.screen.accountId = NOT_SUPPLIED_MARKER;
            // :1248 MOVE DFHRED TO ACCTSIDC OF CCRDUPAO
            context.screen.accountIdColour = FieldColour.RED;
        }
        // :1252-1253 IF FLG-CARDFILTER-NOT-OK MOVE DFHRED TO CARDSIDC
        if (context.cardFilterState == FieldEditState.NOT_OK) {
            context.screen.cardNumberColour = FieldColour.RED;
        }
        // :1254-1258 IF FLG-CARDFILTER-BLANK AND CDEMO-PGM-REENTER MOVE '*' TO CARDSIDO, DFHRED TO
        //            CARDSIDC
        if (context.cardFilterState == FieldEditState.BLANK && !context.programContextEnter) {
            // :1255 MOVE '*' TO CARDSIDO OF CCRDUPAO
            context.screen.cardNumber = NOT_SUPPLIED_MARKER;
            // :1256 MOVE DFHRED TO CARDSIDC OF CCRDUPAO
            context.screen.cardNumberColour = FieldColour.RED;
        }
        // :1260-1261 IF FLG-CARDNAME-NOT-OK AND CCUP-CHANGES-NOT-OK MOVE DFHRED TO CRDNAMEC
        if (context.cardNameState == FieldEditState.NOT_OK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            context.screen.cardholderNameColour = FieldColour.RED;
        }
        // :1262-1268 IF FLG-CARDNAME-BLANK AND CCUP-CHANGES-NOT-OK MOVE '*' TO CRDNAMEO, DFHRED TO
        //            CRDNAMEC
        if (context.cardNameState == FieldEditState.BLANK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            // :1265 MOVE '*' TO CRDNAMEO OF CCRDUPAO
            context.screen.cardholderName = NOT_SUPPLIED_MARKER;
            // :1266 MOVE DFHRED TO CRDNAMEC OF CCRDUPAO
            context.screen.cardholderNameColour = FieldColour.RED;
        }
        // :1270-1271 IF FLG-CARDSTATUS-NOT-OK AND CCUP-CHANGES-NOT-OK MOVE DFHRED TO CRDSTCDC
        if (context.cardStatusState == FieldEditState.NOT_OK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            context.screen.cardStatusColour = FieldColour.RED;
        }
        // :1272-1276 IF FLG-CARDSTATUS-BLANK AND CCUP-CHANGES-NOT-OK MOVE '*' TO CRDSTCDO, DFHRED TO
        //            CRDSTCDC
        if (context.cardStatusState == FieldEditState.BLANK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            // :1275 MOVE '*' TO CRDSTCDO OF CCRDUPAO
            context.screen.cardStatusCode = NOT_SUPPLIED_MARKER;
            // :1276 MOVE DFHRED TO CRDSTCDC OF CCRDUPAO
            context.screen.cardStatusColour = FieldColour.RED;
        }
        // :1277 MOVE DFHBMDAR TO EXPDAYC OF CCRDUPAO - UNCONDITIONAL. The expiry day is always
        //       non-display, which is the screen-level counterpart of it never being validated and always
        //       being echoed as the OLD value.
        context.screen.expiryDayColour = FieldColour.NON_DISPLAY;
        // :1279-1280 IF FLG-CARDEXPMON-NOT-OK AND CCUP-CHANGES-NOT-OK MOVE DFHRED TO EXPMONC
        if (context.expiryMonthState == FieldEditState.NOT_OK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            context.screen.expiryMonthColour = FieldColour.RED;
        }
        // :1281-1287 IF FLG-CARDEXPMON-BLANK AND CCUP-CHANGES-NOT-OK MOVE '*' TO EXPMONO, DFHRED TO
        //            EXPMONC
        if (context.expiryMonthState == FieldEditState.BLANK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            // :1284 MOVE '*' TO EXPMONO OF CCRDUPAO
            context.screen.expiryMonth = NOT_SUPPLIED_MARKER;
            // :1285 MOVE DFHRED TO EXPMONC OF CCRDUPAO
            context.screen.expiryMonthColour = FieldColour.RED;
        }
        // :1288-1289 IF FLG-CARDEXPYEAR-NOT-OK AND CCUP-CHANGES-NOT-OK MOVE DFHRED TO EXPYEARC
        if (context.expiryYearState == FieldEditState.NOT_OK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            context.screen.expiryYearColour = FieldColour.RED;
        }
        // :1290-1295 IF FLG-CARDEXPYEAR-BLANK AND CCUP-CHANGES-NOT-OK MOVE '*' TO EXPYEARO, DFHRED TO
        //            EXPYEARC
        if (context.expiryYearState == FieldEditState.BLANK
                && context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            // :1292 MOVE '*' TO EXPYEARO OF CCRDUPAO
            context.screen.expiryYear = NOT_SUPPLIED_MARKER;
            // :1293 MOVE DFHRED TO EXPYEARC OF CCRDUPAO
            context.screen.expiryYearColour = FieldColour.RED;
        }
        // :1300-1305 IF WS-NO-INFO-MESSAGE MOVE DFHBMDAR TO INFOMSGA ELSE MOVE DFHBMBRY TO INFOMSGA -
        //            an empty information line is hidden rather than shown blank.
        context.screen.informationMessageAttribute =
                context.informationMessage == null || context.informationMessage.isEmpty()
                        ? FieldAttribute.NON_DISPLAY
                        : FieldAttribute.BRIGHT;
        // :1310-1316 IF PROMPT-FOR-CONFIRMATION MOVE DFHBMBRY TO FKEYSCA - the second function-key
        //            field is highlighted only while the caller is being asked to confirm, which is the
        //            source's only use of the split FKEYSI X(21) plus FKEYSCI X(18) area.
        if (INFO_PROMPT_FOR_CONFIRMATION.equals(context.informationMessage)) {
            context.screen.functionKeysAttribute = FieldAttribute.BRIGHT;
        }
    }

    /**
     * The screen-attribute exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3300-SETUP-SCREEN-ATTRS-EXIT.} at line
     * 1319, whose body is the {@code EXIT} statement at {@code :1320}.</p>
     */
    private void setupScreenAttrsExit3300() {
        // :1320 EXIT
    }

    /**
     * Sends the completed map to the terminal.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3400-SEND-SCREEN.} at line 1324.</p>
     *
     * <p>{@code :1325-1326} move the mapset and map names into the COMMAREA so the next task knows what
     * was displayed; {@code :1328-1336} issue
     * {@code EXEC CICS SEND MAP(...) MAPSET(...) FROM(CCRDUPAO) CURSOR ERASE FREEKB RESP(WS-RESP-CD)}.
     * Under transformation Rule 6 the {@code SEND MAP} becomes the returned payload rather than a
     * terminal write, so there is no I/O here and the {@code RESP} value has no counterpart; the
     * rendering itself happens in {@link ScreenBuffer#toDto}. The {@code CURSOR} option is honoured by
     * the cursor field that {@code 3300} selected, and {@code ERASE} by {@code 3100} having replaced the
     * buffer outright.</p>
     *
     * @param context the per-request state
     */
    private void sendScreen3400(final UpdateContext context) {
        // :1325 MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
        context.nextMapset = THIS_MAPSET;
        // :1326 MOVE LIT-THISMAP TO CCARD-NEXT-MAP
        context.nextMap = THIS_MAP;
        // :1328-1336 EXEC CICS SEND MAP ... FROM(CCRDUPAO) CURSOR ERASE FREEKB RESP(WS-RESP-CD)
        context.lastMapset = THIS_MAPSET;
        context.lastMap = THIS_MAP;
    }

    /**
     * The send-screen exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3400-SEND-SCREEN-EXIT.} at line 1338,
     * whose body is the {@code EXIT} statement at {@code :1339}.</p>
     */
    private void sendScreenExit3400() {
        // :1339 EXIT
    }
    // 9000-READ-DATA and 9100-GETCARD-BYACCTCARD. app/cbl/COCRDUPC.cbl:1343-1417.

    /**
     * Fetches the card row and takes the as-displayed snapshot from it.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9000-READ-DATA.} at line 1343.</p>
     *
     * <p>{@code :1345} clears the whole {@code CCUP-OLD-DETAILS} group and {@code :1346-1347} seed its
     * two key fields from the validated filters, so a fetch deliberately <em>discards</em> whatever
     * snapshot the previous turn carried. {@code :1349-1350} performs the read, and only
     * {@code :1352-1369}, guarded by {@code IF FOUND-CARDS-FOR-ACCOUNT}, loads the six comparison
     * fields.</p>
     *
     * <p><strong>{@code :1356-1358} upper-cases {@code CARD-EMBOSSED-NAME} in place</strong> with
     * {@code INSPECT ... CONVERTING LIT-LOWER TO LIT-UPPER} before the snapshot is taken, so the snapshot
     * always holds the folded form. {@code 9300-CHECK-CHANGE-IN-REC} folds the live record again at
     * {@code :1499-1501} before comparing, which means both sides of that comparison are already folded
     * and the fold is applied twice on the write path.</p>
     *
     * <p>This method is reached only from the two fetch paths, {@code :486-487} and {@code :962-963}. It
     * is deliberately <strong>not</strong> reached from the write path, which is why the caller-supplied
     * snapshot survives to be compared at {@code :1503-1508} rather than being overwritten by the very
     * row it is meant to guard.</p>
     *
     * @param context the per-request state
     */
    private void readData9000(final UpdateContext context) {
        // :1345 INITIALIZE CCUP-OLD-DETAILS - the fetch discards the carried snapshot outright.
        context.oldCardholderName = null;
        context.oldCardStatusCode = null;
        context.oldExpiryDay = null;
        context.oldExpiryMonth = null;
        context.oldExpiryYear = null;
        // :1346 MOVE CC-ACCT-ID TO CCUP-OLD-ACCTID
        context.oldAccountId = context.receivedAccountId;
        // :1347 MOVE CC-CARD-NUM TO CCUP-OLD-CARDID
        context.oldCardNumber = context.receivedCardNumber;
        // :1349-1350 PERFORM 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT
        final Card card = getCardByAcctCard9100(context);
        // :1352 IF FOUND-CARDS-FOR-ACCOUNT
        if (card != null) {
            // :1386 INTO(CARD-RECORD) - the read replaced the record work area, so the five CARD-*
            //       fields that :673-677 pre-loaded are overwritten here.
            context.loadRecordWorkArea(card);
            // :1354 MOVE CARD-CVV-CD TO CCUP-OLD-CVV-CD - NOT REPRODUCED. The value IS stored, on
            //       com.cardemo.model.entity.Card, but it is write-once with no getter of any
            //       visibility, so there is no read path to snapshot it through - see
            //       the card_cvv_cd IS DECLARED comment of V1__create_schema.sql. Both operands of
            //       :1503 were server-side, so the only
            //       question that predicate asked was whether the row changed between the display read
            //       and the write read, and @Version answers that for every column. The predicate is
            //       dropped together with this snapshot component, never one without the other.
            // :1356-1358 INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER - the record work
            //            area is folded IN PLACE before the snapshot is taken.
            //            The fold is applied to the work area ONLY, never to the loaded entity: an
            //            INSPECT mutates WORKING-STORAGE and never the dataset, whereas mutating a
            //            managed JPA instance inside this transaction would flush an UPDATE the source
            //            never performs and would silently rewrite the row on a read-only fetch.
            context.editEmbossedName = context.editEmbossedName == null
                    ? null
                    : context.editEmbossedName.toUpperCase(Locale.ROOT);
            // :1360 MOVE CARD-EMBOSSED-NAME TO CCUP-OLD-CRDNAME
            context.oldCardholderName = context.editEmbossedName;
            // :1361-1362 MOVE CARD-EXPIRAION-DATE(1:4) TO CCUP-OLD-EXPYEAR
            context.oldExpiryYear = context.editExpiryYear;
            // :1363-1364 MOVE CARD-EXPIRAION-DATE(6:2) TO CCUP-OLD-EXPMON
            context.oldExpiryMonth = context.editExpiryMonth;
            // :1365-1366 MOVE CARD-EXPIRAION-DATE(9:2) TO CCUP-OLD-EXPDAY - the day is snapshotted even
            //            though no paragraph ever validates it, because 3200 echoes it and 9200 writes it.
            context.oldExpiryDay = context.editExpiryDay;
            // :1367 MOVE CARD-ACTIVE-STATUS TO CCUP-OLD-CRDSTCD
            context.oldCardStatusCode = context.editCardStatus;
        }
        readDataExit9000();
    }

    /**
     * The read-data exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9000-READ-DATA-EXIT.} at line 1372, whose
     * body is the {@code EXIT} statement at {@code :1373}.</p>
     */
    private void readDataExit9000() {
        // :1373 EXIT
    }

    /**
     * Reads one card row for display.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9100-GETCARD-BYACCTCARD.} at line
     * 1376.</p>
     *
     * <p><strong>Despite its name the paragraph reads by card number alone.</strong> The
     * account-identifier half of the record identifier is <em>commented out</em> at {@code :1379}, and
     * {@code :1380} moves only {@code CC-CARD-NUM} into {@code WS-CARD-RID-CARDNUM}, which
     * {@code :1384-1385} then supply as {@code RIDFLD} and {@code KEYLENGTH}. The read therefore goes
     * against the base cluster {@code LIT-CARDFILENAME}, never against the alternate-index path
     * {@code LIT-CARDFILENAME-ACCT-PATH} declared at {@code :253} and referenced by no verb in the
     * program. That is why {@code CardRepository} is the only injected collaborator and why no derived
     * account finder is called.</p>
     *
     * <p>The {@code EVALUATE WS-RESP-CD} at {@code :1392-1412} has exactly three branches, matching the
     * program's whole RESP census of {@code NORMAL} three times and {@code NOTFND} once, with no
     * {@code ENDFILE}, {@code DUPREC} or {@code DUPKEY} anywhere. {@code NOTFND} at {@code :1395-1401}
     * is a validation outcome that marks <em>both</em> filters not-OK and, guarded by
     * {@code IF WS-RETURN-MSG-OFF}, sets the combination message. {@code WHEN OTHER} at
     * {@code :1402-1411} composes {@code WS-FILE-ERROR-MESSAGE} and, unusually, guards only the
     * <em>flag</em> rather than the message.</p>
     *
     * @param context the per-request state
     * @return the card row, or {@code null} when the read returned {@code NOTFND} at {@code :1395}
     * @throws FileAccessException if the read fails for any other reason, reproducing {@code :1402-1411}
     */
    private Card getCardByAcctCard9100(final UpdateContext context) {
        // :1379 MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID - commented out in the source, and RESTORED
        //       here as a two-key predicate. The paragraph is named 9100-GETCARD-BYACCTCARD and the
        //       message it latches on a miss is 'Did not find this card in cards database' keyed on the
        //       ACCOUNT-CARD COMBINATION (:1400), yet with :1379 commented out the read matched on the
        //       card number alone: any card number reached any account's screen, and 9200 then rewrote
        //       the row with the account identifier the request carried. Reading by both values is what
        //       the paragraph name, the message and the commented-out MOVE all describe.
        // :1380 MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM
        final String recordIdentifier = cardRecordKey(context.receivedCardNumber);
        final Optional<Card> found;
        try {
            // :1382-1390 EXEC CICS READ FILE(LIT-CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)
            //            KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM) INTO(CARD-RECORD)
            found = readCardForAccount(recordIdentifier, context.receivedAccountId);
        } catch (final DataAccessException accessFailure) {
            // :1402-1411 WHEN OTHER - a genuine I/O failure rather than a missing row. The composed
            //            message is reproduced byte for byte, and the root cause is preserved.
            context.inputError = true;
            // :1404-1406 IF WS-RETURN-MSG-OFF SET FLG-ACCTFILTER-NOT-OK TO TRUE - note that the guard
            //            wraps the FLAG here, not the message.
            if (context.returnMessage.isEmpty()) {
                context.accountFilterState = FieldEditState.NOT_OK;
            }
            // :1407-1411 MOVE 'READ' TO ERROR-OPNAME ... MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG
            context.returnMessage = fileErrorMessage(OPERATION_READ);
            throw new FileAccessException(context.returnMessage, null, CARD_FILE_NAME.strip(),
                    OPERATION_READ, accessFailure);
        }
        if (found.isPresent()) {
            // :1393-1394 WHEN DFHRESP(NORMAL) SET FOUND-CARDS-FOR-ACCOUNT TO TRUE
            context.informationMessage = INFO_FOUND_CARDS_FOR_ACCOUNT;
            getCardByAcctCardExit9100();
            return found.get();
        }
        // :1395 WHEN DFHRESP(NOTFND)
        context.cardAbsent = true;
        // :1396 SET INPUT-ERROR TO TRUE
        context.inputError = true;
        // :1397 SET FLG-ACCTFILTER-NOT-OK TO TRUE
        context.accountFilterState = FieldEditState.NOT_OK;
        // :1398 SET FLG-CARDFILTER-NOT-OK TO TRUE - both filters are marked, because the read cannot
        //       tell which of the two the caller got wrong.
        context.cardFilterState = FieldEditState.NOT_OK;
        // :1399-1401 IF WS-RETURN-MSG-OFF SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE
        if (context.returnMessage.isEmpty()) {
            context.returnMessage = MSG_DID_NOT_FIND_ACCTCARD_COMBO;
        }
        getCardByAcctCardExit9100();
        return null;
    }

    /**
     * The card-read exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9100-GETCARD-BYACCTCARD-EXIT.} at line
     * 1415, whose body is the {@code EXIT} statement at {@code :1416}.</p>
     */
    private void getCardByAcctCardExit9100() {
        // :1416 EXIT
    }
    // 9200-WRITE-PROCESSING and 9300-CHECK-CHANGE-IN-REC. app/cbl/COCRDUPC.cbl:1420-1523.

    /**
     * Locks the card row, verifies that nobody changed it, applies the edited fields and rewrites it.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9200-WRITE-PROCESSING.} at line 1420.</p>
     *
     * <p>Side effects: writes exactly one row, and only on the success path.</p>
     *
     * <p><strong>This paragraph touches a single dataset and contains no rollback verb of any kind.</strong>
     * The lone {@code EXEC CICS SYNCPOINT} in the whole program is at {@code :470}, inside the navigation
     * branch of {@code 0000-MAIN}, and must not be attributed here. There is therefore no
     * asymmetric-rollback branch to reproduce, unlike {@code app/cbl/COACTUPC.cbl:4079-4102} whose
     * dual-dataset write rolls back on the second failure only. The enclosing
     * {@code @Transactional(rollbackFor = Exception.class)} boundary declared on
     * {@link #processRequest(CardUpdateRequest, CardUpdateRequest.CardDetails, String, EntryMode)} and {@link #updateCard} reproduces the
     * source's semantics by <em>scoping</em> rather than by conditional logic, which is a mechanism
     * substitution and not a behaviour change.</p>
     *
     * <p>Three distinguishable failure outcomes plus success, exactly as the source produces them:
     * {@code COULD-NOT-LOCK-FOR-UPDATE} at {@code :1446}, {@code DATA-WAS-CHANGED-BEFORE-UPDATE} raised
     * by {@code 9300} at {@code :1511}, and {@code LOCKED-BUT-UPDATE-FAILED} at {@code :1491}. Collapsing
     * them into one status would be a regression: {@code :997-998} shows that only the
     * middle one is recoverable, because it returns to {@code CCUP-SHOW-DETAILS} so the caller may retry
     * against refreshed data.</p>
     *
     * <p><strong>The two card-verification {@code MOVE}s at {@code :1464-1465} have no counterpart
     * here, and the reason is a legacy defect rather than a missing column.</strong> The verification value
     * <em>is</em> retained by this system: {@code card_cvv_cd          CHAR(3)     NOT NULL} is declared in
     * {@code src/main/resources/db/migration/V1__create_schema.sql}, {@link com.cardemo.model.entity.Card}
     * maps it, and {@code V3__seed_data.sql} seeds a real value for every fixture row. What it has no
     * counterpart <em>for</em> is the source's write. {@code CCUP-NEW-CVV-CD} is declared at {@code :306},
     * left as {@code SPACES} by {@code INITIALIZE CCUP-NEW-DETAILS} at {@code :586}, and <strong>never
     * assigned anywhere in the program</strong>, yet {@code :1464-1465} moves it into the row rewritten at
     * {@code :1478} - so every successful legacy update overwrote the stored verification value with three
     * spaces. That is a defect, not a rule, and reproducing it would destroy live authentication data on
     * each write, which Rule 1 Clause D forbids. The pair of {@code MOVE}s is therefore deliberately
     * absent, the stored value is left untouched, and the divergence is recorded rather than hidden.</p>
     *
     * <p>No request component could supply a replacement in any case: {@code app/cpy-bms/COCRDUP.CPY}
     * declares no card-verification field, so the value is server-owned throughout. It is also write-once
     * and unreadable - {@code cvvCode} exposes no getter of any visibility - so it is never rendered, and
     * the concurrency question the source asked of it is answered instead by the {@code version} column,
     * which covers every column of the row.</p>
     *
     * <p><strong>Lock acquisition happens at the read, not at the rewrite.</strong> The
     * {@code EXEC CICS READ ... UPDATE} of {@code :1427}-{@code :1436} is a read-for-update, so the row
     * is held from before {@code 9300} compares until after {@code :1478} writes.
     * {@link CardRepository#findByIdForUpdate(String)} carries
     * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} to reproduce that timing; the {@code version} column
     * remains as the second layer but cannot substitute for the lock, because it detects a clash after
     * the fact whereas the source prevents one.</p>
     *
     * @param context the per-request state
     * @throws FileAccessException if the locking read fails with a genuine access error
     */
    private void writeProcessing9200(final UpdateContext context) {
        // :1424 MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID - commented out, exactly as in 9100, and
        //       RESTORED here for the same reason. This is the read whose row is rewritten, so a
        //       single-key predicate here is what would let one account's request modify another
        //       account's card.
        // :1425 MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM
        final String recordIdentifier = cardRecordKey(context.receivedCardNumber);
        final Optional<Card> locked;
        try {
            // :1427-1436 EXEC CICS READ FILE(LIT-CARDFILENAME) UPDATE RIDFLD(...) INTO(CARD-RECORD).
            //            The UPDATE option is the lock acquisition, and it happens HERE - before 9300
            //            compares - not after. findByIdAndAccountIdForUpdate carries
            //            @Lock(LockModeType.PESSIMISTIC_WRITE), which Hibernate renders as
            //            SELECT ... FOR UPDATE, so the row is held from this read through the rewrite at
            //            :1478. A plain findById would leave the window between :1453 and :1478 open and
            //            would reduce the source's lock-then-compare to a compare-then-hope. The account
            //            predicate of the restored :1424 is part of the SAME read rather than a second one:
            //            locking first and checking ownership afterwards would still have held a lock on
            //            another account's row, and checking ownership first would have read it unlocked.
            locked = readCardForAccountForUpdate(recordIdentifier, context.receivedAccountId);
        } catch (final DataAccessException accessFailure) {
            context.inputError = true;
            context.returnMessage = fileErrorMessage(OPERATION_READ);
            throw new FileAccessException(context.returnMessage, null, CARD_FILE_NAME.strip(),
                    OPERATION_READ, accessFailure);
        }
        // :1441-1449 IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL) CONTINUE ELSE ... - anything other than a
        //            normal response is treated as "could not lock", including a missing row.
        if (locked.isEmpty()) {
            // :1444 SET INPUT-ERROR TO TRUE
            context.inputError = true;
            // :1445-1447 IF WS-RETURN-MSG-OFF SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE
            if (context.returnMessage.isEmpty()) {
                context.returnMessage = MSG_COULD_NOT_LOCK_FOR_UPDATE;
            }
            context.writeOutcome = WriteOutcome.COULD_NOT_LOCK_FOR_UPDATE;
            LOG.warn("CCUP could not lock card {} for update; write abandoned at :1446",
                    context.maskedCardNumber());
            // :1448 GO TO 9200-WRITE-PROCESSING-EXIT
            writeProcessingExit9200();
            return;
        }
        final Card card = locked.get();
        // :1432 INTO(CARD-RECORD) - populate the record work area from the locked row, which is what
        //       9300 then folds and compares.
        context.loadRecordWorkArea(card);
        // :1453-1454 PERFORM 9300-CHECK-CHANGE-IN-REC THRU 9300-CHECK-CHANGE-IN-REC-EXIT
        checkChangeInRec9300(context);
        // :1455-1457 IF DATA-WAS-CHANGED-BEFORE-UPDATE GO TO 9200-WRITE-PROCESSING-EXIT
        if (context.writeOutcome == WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE) {
            writeProcessingExit9200();
            return;
        }
        // :1461 INITIALIZE CARD-UPDATE-RECORD - the update image is built field by field below. In JPA
        //       the row read for update and the row rewritten are the same managed instance, so the
        //       clear-then-populate idiom becomes a field-by-field assignment onto that instance.
        // :1462 MOVE CCUP-NEW-CARDID TO CARD-UPDATE-NUM - a no-op on the identifier: :598-605 moved the
        //       received field to BOTH CC-CARD-NUM and CCUP-NEW-CARDID, and :796-797 re-established that
        //       on the success path, so the value written is the key just read. Assigning a JPA
        //       identifier on a managed instance is invalid, so the equality is asserted instead.
        if (!recordIdentifier.equals(padLeftZeroes(context.newCardId, WIDTH_CARD_NUMBER))
                || !recordIdentifier.equals(card.getCardNumber())) {
            // Unreachable while the repository honours its contract; guarded so a silent key mismatch can
            // never be written, and reported through the same typed hierarchy as every other failure.
            throw new FileAccessException(fileErrorMessage(OPERATION_REWRITE), null,
                    CARD_FILE_NAME.strip(), OPERATION_REWRITE);
        }
        // :1463 MOVE CC-ACCT-ID-N TO CARD-UPDATE-ACCT-ID
        card.setAccountId(context.receivedAccountId);
        // :1464 MOVE CCUP-NEW-CVV-CD TO CARD-CVV-CD-X
        // :1465 MOVE CARD-CVV-CD-N   TO CARD-UPDATE-CVV-CD
        //
        // DELIBERATELY ABSENT, and the reason is the legacy defect rather than an absent column. Both
        // MOVEs write the stored card verification value - with the three spaces that
        // INITIALIZE CCUP-NEW-DETAILS at :586 left in CCUP-NEW-CVV-CD, since that field is DECLARED at
        // :306 and READ at :1464 but NEVER ASSIGNED anywhere in the program. Reproducing them would
        // overwrite live authentication data with spaces on every successful update, which Rule 1
        // Clause D forbids. The column exists (card_cvv_cd CHAR(3) NOT NULL in V1__create_schema.sql),
        // the entity maps it and V3__seed_data.sql's card insert seeds it; what is withheld is the read
        // path, so
        // these MOVEs have no operand to read and no sanctioned reason to write. Labelled deviation:
        // a successful update here leaves the stored value intact where the source destroyed it.
        // :1466 MOVE CCUP-NEW-CRDNAME TO CARD-UPDATE-EMBOSSED-NAME
        card.setEmbossedName(padRight(context.newCardholderName, WIDTH_EMBOSSED_NAME));
        // :1467-1474 STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY DELIMITED BY SIZE
        //            INTO CARD-UPDATE-EXPIRAION-DATE - the CARRIED day is written, and because 3200 echoes
        //            the OLD day on every arm and :1120-1122 leaves the new-value MOVE commented out, the
        //            carried day IS the old day. 1100 reproduces that carrier from the opened snapshot; see
        //            the note at :621 there for why the request cannot be it.
        card.setExpiraionDate(assembleExpiraionDate(context.newExpiryYear, context.newExpiryMonth,
                context.newExpiryDay));
        // :1475 MOVE CCUP-NEW-CRDSTCD TO CARD-UPDATE-ACTIVE-STATUS
        card.setActiveStatus(context.newCardStatusCode);
        try {
            // :1477-1483 EXEC CICS REWRITE FILE(LIT-CARDFILENAME) FROM(CARD-UPDATE-RECORD)
            cardRepository.save(card);
            // FLUSH INSIDE THE GUARD, AND BEFORE THE SUCCESS STATE IS SET. save() only enrols the row
            // with the persistence context; the UPDATE itself would otherwise be issued at commit, which
            // is after this try block and after the two statements below. A version conflict or a
            // constraint violation would then escape the catch clauses entirely, so :1491's
            // LOCKED-BUT-UPDATE-FAILED would never be reported and the caller would have been told at
            // :1488-1489 that the rewrite succeeded. The flush moves the failure back inside the
            // paragraph that owns it, exactly as EXEC CICS REWRITE reported its own RESP inline.
            cardRepository.flush();
            // :1488-1489 IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL) CONTINUE
            context.writeOutcome = WriteOutcome.COMPLETED;
            // The one state change this service makes, so it is the one event worth recording. The card
            // number is masked and the card verification value, the embossed name and both snapshot
            // groups are omitted entirely, per Rule 1 Clause D.
            LOG.info("CCUP rewrote card {} status={} expiry={}-{} (version={})",
                    context.maskedCardNumber(), context.newCardStatusCode, context.newExpiryYear,
                    context.newExpiryMonth, card.getVersion());
        } catch (final OptimisticLockingFailureException versionConflict) {
            // Regime C, the store-level guard. @Version caught a concurrent write that Regime B could not
            // see, which is precisely the case a value-restoring update produces. The source has no
            // counterpart because VSAM held the record locked, so it is reported as the closest legacy
            // outcome, :1491's LOCKED-BUT-UPDATE-FAILED, with the root cause preserved.
            context.returnMessage = MSG_LOCKED_BUT_UPDATE_FAILED;
            context.writeOutcome = WriteOutcome.LOCKED_BUT_UPDATE_FAILED;
            throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED,
                    MSG_LOCKED_BUT_UPDATE_FAILED, context.maskedCardNumber(), versionConflict);
        } catch (final DataAccessException rewriteFailure) {
            // :1490-1492 ELSE SET LOCKED-BUT-UPDATE-FAILED TO TRUE - note that this branch sets neither
            //            INPUT-ERROR nor a WS-RETURN-MSG-OFF guard, unlike every other failure path in
            //            the program.
            context.writeOutcome = WriteOutcome.LOCKED_BUT_UPDATE_FAILED;
            throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED,
                    MSG_LOCKED_BUT_UPDATE_FAILED, context.maskedCardNumber(), rewriteFailure);
        }
        writeProcessingExit9200();
    }

    /**
     * The write-processing exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9200-WRITE-PROCESSING-EXIT.} at line
     * 1494, whose body is the {@code EXIT} statement at {@code :1495}. Targeted by the {@code GO TO}
     * statements at {@code :1448} and {@code :1456} and, unusually, by the cross-paragraph {@code GO TO}
     * at {@code :1518} inside {@code 9300-CHECK-CHANGE-IN-REC}.</p>
     */
    private void writeProcessingExit9200() {
        // :1495 EXIT
    }

    /**
     * Regime B: verifies field by field that the stored row still holds what the caller was shown.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9300-CHECK-CHANGE-IN-REC.} at line
     * 1498.</p>
     *
     * <p>{@code :1499-1501} folds {@code CARD-EMBOSSED-NAME} to upper case <strong>in place</strong>,
     * mutating the program's own record buffer before any comparison happens; the same fold was already
     * applied at {@code :1356-1358} when the snapshot was taken, so on the write path it is applied
     * twice. The fold is reproduced here on a local value so the comparison behaves
     * identically, and {@code Locale.ROOT} is used so the result cannot vary with the platform locale.</p>
     *
     * <p>{@code :1503-1508} is a single {@code IF} with <strong>six {@code AND}ed predicates</strong>,
     * every one of which must hold for the row to count as unchanged: the card verification value - whose
     * counterpart is the {@code @Version} column, for the reason given below - the
     * embossed name, and then <strong>the expiry date compared as three separate substrings</strong> at
     * offsets {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, and finally the active status. The
     * substring comparison is mandatory rather than cosmetic: the live {@code CARD-EXPIRAION-DATE} is ten
     * bytes of dash-separated text while the snapshot holds three discrete fields with no separators, so
     * a whole-string comparison would report a change on every request and make the endpoint permanently
     * unusable.</p>
     *
     * <p>On mismatch {@code :1511} sets the changed flag, {@code :1512-1517} <strong>refresh all six
     * snapshot fields from the live row</strong> so the redisplayed screen shows current data, and
     * {@code :1518} jumps to {@code 9200-WRITE-PROCESSING-EXIT} &mdash; a cross-paragraph {@code GO TO}
     * into the caller's exit label, which Java expresses as a distinct outcome that the caller tests.
     * The malformed {@code END-IF EXIT} at {@code :1519} is a no-op with no Java counterpart. Finding
     * 16.</p>
     *
     * <p>Regime C, the JPA version column, is <em>not</em> a substitute for this check and this check is
     * not a substitute for it. A version column detects that some concurrent write happened; this
     * detects which field values differ from what the caller was shown. A concurrent write that restored
     * a value passes here and fails there, so both layers are required.</p>
     *
     * @param context the per-request state, carrying both the as-displayed snapshot and the record work
     *                area that the locking read at {@code :1432} populated from the stored row
     */
    private void checkChangeInRec9300(final UpdateContext context) {
        // :1499-1501 INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER - in place, on the
        //            record work area that the locking read populated, before comparing.
        //            The fold never touches the loaded entity, for the reason given in readData9000: an
        //            INSPECT mutates WORKING-STORAGE, not the dataset.
        context.editEmbossedName = context.editEmbossedName == null
                ? null
                : context.editEmbossedName.toUpperCase(Locale.ROOT);
        // :1503 IF CARD-CVV-CD EQUAL TO CCUP-OLD-CVV-CD
        // :1504 AND CARD-EMBOSSED-NAME EQUAL TO CCUP-OLD-CRDNAME
        // :1505 AND CARD-EXPIRAION-DATE(1:4) EQUAL TO CCUP-OLD-EXPYEAR
        // :1506 AND CARD-EXPIRAION-DATE(6:2) EQUAL TO CCUP-OLD-EXPMON
        // :1507 AND CARD-EXPIRAION-DATE(9:2) EQUAL TO CCUP-OLD-EXPDAY
        // :1508 AND CARD-ACTIVE-STATUS EQUAL TO CCUP-OLD-CRDSTCD
        // :1503 has no counterpart, and not because the value is absent: card_cvv_cd is declared at
        //       card_cvv_cd in V1__create_schema.sql, mapped on the entity and seeded by
        //       V3__seed_data.sql's card insert.
        //       It has no READ path - the entity field is write-once with no getter - so neither the
        //       record work area nor the snapshot can carry it. Both of the source's operands were
        //       server-side (a display-time read and a write-time re-read), so the predicate asked only
        //       whether the row changed between them, and @Version answers that for every column of the
        //       row. It is not replaced by a constant: a comparison of two absent values is not a
        //       weakened comparison, it is no comparison. The five predicates below guard every field the
        //       screen can change, which is every field the legacy rewrite could change.
        final boolean unchanged =
                fixedWidthEquals(context.editEmbossedName, context.oldCardholderName,
                        WIDTH_EMBOSSED_NAME)
                && fixedWidthEquals(context.editExpiryYear, context.oldExpiryYear, WIDTH_EXPIRY_YEAR)
                && fixedWidthEquals(context.editExpiryMonth, context.oldExpiryMonth,
                        WIDTH_EXPIRY_MONTH)
                && fixedWidthEquals(context.editExpiryDay, context.oldExpiryDay, WIDTH_EXPIRY_DAY)
                && fixedWidthEquals(context.editCardStatus, context.oldCardStatusCode,
                        WIDTH_CARD_STATUS);
        if (unchanged) {
            // :1509 CONTINUE
            checkChangeInRecExit9300();
            return;
        }
        // :1511 SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE
        context.writeOutcome = WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE;
        context.returnMessage = MSG_DATA_WAS_CHANGED_BEFORE_UPDATE;
        // :1512 MOVE CARD-CVV-CD TO CCUP-OLD-CVV-CD - NOT REPRODUCED, for the same reason as :1354
        //       and :1503: the stored value has no read path, and @Version carries the concurrency
        //       question those predicates asked.
        // :1513 MOVE CARD-EMBOSSED-NAME TO CCUP-OLD-CRDNAME
        context.oldCardholderName = context.editEmbossedName;
        // :1514 MOVE CARD-EXPIRAION-DATE(1:4) TO CCUP-OLD-EXPYEAR
        context.oldExpiryYear = context.editExpiryYear;
        // :1515 MOVE CARD-EXPIRAION-DATE(6:2) TO CCUP-OLD-EXPMON
        context.oldExpiryMonth = context.editExpiryMonth;
        // :1516 MOVE CARD-EXPIRAION-DATE(9:2) TO CCUP-OLD-EXPDAY
        context.oldExpiryDay = context.editExpiryDay;
        // :1517 MOVE CARD-ACTIVE-STATUS TO CCUP-OLD-CRDSTCD
        context.oldCardStatusCode = context.editCardStatus;
        // :1518 GO TO 9200-WRITE-PROCESSING-EXIT - a cross-paragraph jump into the caller's exit label,
        //       expressed as the DATA_WAS_CHANGED_BEFORE_UPDATE outcome that :1455 tests.
        // :1519 END-IF EXIT - malformed and inert; no Java counterpart.
        writeProcessingExit9200();
    }

    /**
     * The change-check exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code 9300-CHECK-CHANGE-IN-REC-EXIT.} at line
     * 1521, whose body is the {@code EXIT} statement at {@code :1522}.</p>
     */
    private void checkChangeInRecExit9300() {
        // :1522 EXIT
    }
    // YYYY-STORE-PFKEY, copied in at app/cbl/COCRDUPC.cbl:1528 from app/cpy/CSSTRPFY.cpy.

    /**
     * Maps the terminal attention identifier onto the function-key field of the shared communication
     * area.
     *
     * <p>Source: {@code app/cpy/CSSTRPFY.cpy} paragraph {@code YYYY-STORE-PFKEY.} at line 17, copied into
     * this program by {@code COPY 'CSSTRPFY'} at {@code app/cbl/COCRDUPC.cbl:1528} and performed at
     * {@code app/cbl/COCRDUPC.cbl:406-407}. Together with its exit label at
     * {@code app/cpy/CSSTRPFY.cpy:80} and the {@code COPY} statement line itself, this brings the
     * program's paragraph count to the documented forty-eight: forty-five in-file Area-A labels plus
     * two plus one.</p>
     *
     * <p>One {@code EVALUATE TRUE} with twenty-eight branches at {@code app/cpy/CSSTRPFY.cpy:21-78}.
     * The first sixteen map {@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1}, {@code DFHPA2} and
     * {@code DFHPF1} through {@code DFHPF12} one for one. <strong>The last twelve fold
     * {@code DFHPF13} through {@code DFHPF24} back onto {@code PFK01} through {@code PFK12}</strong>, at
     * {@code app/cpy/CSSTRPFY.cpy:54-77}, so a 24-key keyboard is indistinguishable from a 12-key one.
     * There is no {@code WHEN OTHER}, so an unrecognised identifier leaves the field as the caller's
     * {@code INITIALIZE} left it, which {@code app/cbl/COCRDUPC.cbl:371-373} makes the enter state.</p>
     *
     * <p>Java has no attention identifier. Under transformation Rule 7 the vocabulary becomes explicit
     * request intent, and only three of the twenty-eight values reach a distinct behaviour in this
     * program: {@code PFK03} is back, {@code PFK05} is confirm-and-save, and {@code PFK12} is cancel.
     * Every other value is accepted, mapped, and then silently rewritten to enter by the validity gate
     * at {@code app/cbl/COCRDUPC.cbl:423-425}.</p>
     *
     * @param attentionIdentifier the raw identifier; {@code null}, blank and unrecognised values resolve
     *                            to {@link AidKey#ENTER}, matching the absent {@code WHEN OTHER}
     * @return the mapped key, never {@code null}
     */
    private AidKey storePfKey(final String attentionIdentifier) {
        final AidKey mapped = foldAttentionIdentifier(attentionIdentifier);
        storePfKeyExit();
        return mapped;
    }

    /**
     * The key fold itself, as a pure function, so that it can be applied both by the paragraph above
     * and by the state derivation in {@code UpdateContext.resolveInitialAction}.
     *
     * <p>Sharing one fold is required for correctness, not merely for tidiness. In the legacy pseudo
     * conversation {@code CCUP-CHANGE-ACTION} arrives in the COMMAREA and the fold at
     * {@code app/cbl/COCRDUPC.cbl:406-407} runs before the validity gate at
     * {@code app/cbl/COCRDUPC.cbl:414-422} consults it. A stateless request has no COMMAREA, so the
     * state is derived from the request instead, and that derivation has to see the SAME folded key the
     * gate will see. Comparing the raw identifier there would leave {@code DFHPF13} through
     * {@code DFHPF24} outside the fold: {@code DFHPF17} would derive the show-details state, the gate at
     * {@code app/cbl/COCRDUPC.cbl:417} would then reject {@code PFK05}, and
     * {@code app/cbl/COCRDUPC.cbl:423-425} would silently rewrite it to enter - turning a confirmed save
     * into a redisplay. {@code app/cpy/CSSTRPFY.cpy:62-63} is explicit that the two are one key.</p>
     *
     * @param attentionIdentifier the raw identifier; {@code null}, blank and unrecognised values resolve
     *                            to {@link AidKey#ENTER}, matching the absent {@code WHEN OTHER}
     * @return the mapped key, never {@code null}
     */
    private static AidKey foldAttentionIdentifier(final String attentionIdentifier) {
        if (attentionIdentifier == null || attentionIdentifier.isBlank()) {
            // No WHEN OTHER at app/cpy/CSSTRPFY.cpy:78, so the field keeps the value that
            // app/cbl/COCRDUPC.cbl:371-373 initialised it to.
            return AidKey.ENTER;
        }
        final String identifier = attentionIdentifier.strip().toUpperCase(Locale.ROOT);
        return switch (identifier) {
            // app/cpy/CSSTRPFY.cpy:22-23 WHEN EIBAID IS EQUAL TO DFHENTER SET CCARD-AID-ENTER
            case "DFHENTER" -> AidKey.ENTER;
            // app/cpy/CSSTRPFY.cpy:24-25 DFHCLEAR
            case "DFHCLEAR" -> AidKey.CLEAR;
            // app/cpy/CSSTRPFY.cpy:26-27 DFHPA1
            case "DFHPA1" -> AidKey.PA1;
            // app/cpy/CSSTRPFY.cpy:28-29 DFHPA2
            case "DFHPA2" -> AidKey.PA2;
            // app/cpy/CSSTRPFY.cpy:30-31 DFHPF1, and :54-55 DFHPF13 folded onto the same key
            case "DFHPF1", "DFHPF13" -> AidKey.PFK01;
            // app/cpy/CSSTRPFY.cpy:32-33 DFHPF2, :56-57 DFHPF14
            case "DFHPF2", "DFHPF14" -> AidKey.PFK02;
            // app/cpy/CSSTRPFY.cpy:34-35 DFHPF3, :58-59 DFHPF15 - back
            case "DFHPF3", "DFHPF15" -> AidKey.PFK03;
            // app/cpy/CSSTRPFY.cpy:36-37 DFHPF4, :60-61 DFHPF16
            case "DFHPF4", "DFHPF16" -> AidKey.PFK04;
            // app/cpy/CSSTRPFY.cpy:38-39 DFHPF5, :62-63 DFHPF17 - confirm and save
            case "DFHPF5", "DFHPF17" -> AidKey.PFK05;
            // app/cpy/CSSTRPFY.cpy:40-41 DFHPF6, :64-65 DFHPF18
            case "DFHPF6", "DFHPF18" -> AidKey.PFK06;
            // app/cpy/CSSTRPFY.cpy:42-43 DFHPF7, :66-67 DFHPF19
            case "DFHPF7", "DFHPF19" -> AidKey.PFK07;
            // app/cpy/CSSTRPFY.cpy:44-45 DFHPF8, :68-69 DFHPF20
            case "DFHPF8", "DFHPF20" -> AidKey.PFK08;
            // app/cpy/CSSTRPFY.cpy:46-47 DFHPF9, :70-71 DFHPF21
            case "DFHPF9", "DFHPF21" -> AidKey.PFK09;
            // app/cpy/CSSTRPFY.cpy:48-49 DFHPF10, :72-73 DFHPF22
            case "DFHPF10", "DFHPF22" -> AidKey.PFK10;
            // app/cpy/CSSTRPFY.cpy:50-51 DFHPF11, :74-75 DFHPF23
            case "DFHPF11", "DFHPF23" -> AidKey.PFK11;
            // app/cpy/CSSTRPFY.cpy:52-53 DFHPF12, :76-77 DFHPF24 - cancel
            case "DFHPF12", "DFHPF24" -> AidKey.PFK12;
            // No WHEN OTHER exists at app/cpy/CSSTRPFY.cpy:78; the field is left as initialised.
            default -> AidKey.ENTER;
        };
    }

    /**
     * The store-function-key exit label.
     *
     * <p>Source: {@code app/cpy/CSSTRPFY.cpy} paragraph {@code YYYY-STORE-PFKEY-EXIT.} at line 80, whose
     * body is the {@code EXIT} statement at {@code app/cpy/CSSTRPFY.cpy:81}.</p>
     */
    private void storePfKeyExit() {
        // app/cpy/CSSTRPFY.cpy:81 EXIT
    }
    // ABEND-ROUTINE. app/cbl/COCRDUPC.cbl:1531-1556.

    /**
     * Terminates the task abnormally, carrying the four abend fields of
     * {@code app/cpy/CSMSG02Y.cpy}.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code ABEND-ROUTINE.} at line 1531. Reachable
     * from two places: as the {@code EXEC CICS HANDLE ABEND LABEL} target registered at
     * {@code :368-370}, and by the explicit
     * {@code PERFORM ABEND-ROUTINE THRU ABEND-ROUTINE-EXIT} at {@code :1025-1026} inside
     * {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER}. It is therefore genuinely reachable and genuinely
     * implemented; no tracked-dead-code exception applies anywhere in this file.</p>
     *
     * <p>The payload is the {@code ABEND-DATA} group of {@code app/cpy/CSMSG02Y.cpy}, internally titled
     * {@code CABENDD.CPY}: {@code ABEND-CODE PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)},
     * {@code ABEND-REASON PIC X(50)} and {@code ABEND-MSG PIC X(72)}, one hundred and thirty-four bytes
     * in all. {@code :1537} always overwrites the culprit with this program's own name, whatever the
     * caller set.</p>
     *
     * <p><strong>Parity trap, preserved.</strong> {@code :1533-1535} substitutes
     * {@code 'UNEXPECTED ABEND OCCURRED.'} only when {@code ABEND-MSG} equals {@code LOW-VALUES}, yet all
     * four fields are declared {@code VALUE SPACES} in {@code app/cpy/CSMSG02Y.cpy} and no
     * {@code INITIALIZE} in this program covers {@code ABEND-DATA}, so at runtime the substitution can
     * never fire &mdash; and at the {@code :1023-1024} call site the message is set explicitly anyway, so
     * the guard is doubly false. {@code FatalProcessingException} substitutes its default on a
     * {@code null} message only, never on a blank or empty one, which reproduces that exactly.</p>
     *
     * <p><strong>Three distinct abend values coexist in this corpus and must not be conflated.</strong>
     * {@code :1550-1552} issues {@code EXEC CICS ABEND ABCODE('9999')}, the CICS transaction abend code,
     * while the payload field carries {@code '0001'} from {@code :1021}; batch programs use a third value
     * altogether, {@code 999} with return code 12, at {@code app/cbl/CBTRN02C.cbl:707-711}. The payload
     * code is the one this exception carries, because it is the one the terminal was sent at
     * {@code :1539-1544}.</p>
     *
     * @param context the per-request state, carrying the four abend fields
     * @param cause   the throwable that triggered the handler, or {@code null} when the routine was
     *                performed explicitly from {@code :1025-1026}
     * @return the exception to throw; returned rather than thrown so every call site reads
     *         {@code throw abendRoutine(...)} and the compiler can see the method never falls through
     */
    private FatalProcessingException abendRoutine(final UpdateContext context, final Throwable cause) {
        // :1533-1535 IF ABEND-MSG EQUAL LOW-VALUES MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG - the
        //            guard tests LOW-VALUES while the field is declared VALUE SPACES, so it never fires
        //            at runtime. FatalProcessingException substitutes on null only, which matches.
        final String abendMessage = context.abendMessage;
        // :1537 MOVE LIT-THISPGM TO ABEND-CULPRIT - unconditional, so the culprit is always this program.
        context.abendCulprit = PROGRAM_NAME;
        // :1539-1544 EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA) NOHANDLE ERASE - the
        //            134-byte payload is sent to the terminal. Its Java counterpart is the exception's
        //            own field set, which the error handler renders. The send is additionally recorded
        //            here, because an abend is the single most operationally significant event this
        //            service can produce and the legacy program had no equivalent of a log at all. All
        //            four payload fields are program-generated literals, so no caller data can reach the
        //            log through them; the state byte is added for diagnosis and the card number is not.
        LOG.warn("CCUP abend: code={} culprit={} message=[{}] state={} writeOutcome={}",
                context.abendCode, context.abendCulprit, abendMessage, context.changeAction,
                context.writeOutcome);
        // :1546-1548 EXEC CICS HANDLE ABEND CANCEL
        // :1550-1552 EXEC CICS ABEND ABCODE('9999') - the CICS abend code, distinct from the '0001' the
        //            payload carries.
        final FatalProcessingException fatal = cause == null
                ? new FatalProcessingException(context.abendCode, context.abendCulprit,
                        context.abendReason, abendMessage)
                : new FatalProcessingException(context.abendCode, context.abendCulprit,
                        context.abendReason, abendMessage, cause);
        abendRoutineExit();
        return fatal;
    }

    /**
     * The abend-routine exit label.
     *
     * <p>Source: {@code app/cbl/COCRDUPC.cbl} paragraph {@code ABEND-ROUTINE-EXIT.} at line 1554, whose
     * body is the {@code EXIT} statement at {@code :1555}. Unreachable in the source, because
     * {@code :1550-1552} terminates the task before control can arrive here, but named as the
     * {@code THRU} target of {@code :1025-1026} and therefore mapped.</p>
     */
    private void abendRoutineExit() {
        // :1555 EXIT
    }
    // Fixed-width, class-test and rendering helpers. Each one reproduces a single COBOL idiom and is
    // pure and static, so no per-request state can leak between concurrent callers.

    /**
     * Reproduces {@code IF field EQUAL LOW-VALUES OR SPACES}, the first gate of every edit paragraph
     * ({@code :725}, {@code :768}, {@code :811}, {@code :850}, {@code :883}, {@code :919}) and the
     * message latch {@code IF WS-RETURN-MSG-OFF}.
     *
     * <p>An absent value stands in for {@code LOW-VALUES}, which is how {@code 1100-RECEIVE-MAP}
     * normalises a not-supplied field. A value made entirely of NUL characters is also accepted, so a
     * caller that transmits the byte form rather than omitting the field behaves identically.</p>
     *
     * @param value the field value, possibly {@code null}
     * @return {@code true} when the field is absent, all blanks or all NUL characters
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
     * Reproduces {@code IF field EQUAL ZEROS}, the third arm of the three-way blank gate at
     * {@code :768}, {@code :811}, {@code :850}, {@code :883} and {@code :919}, and of the numeric gate
     * at {@code :740} and {@code :784}.
     *
     * <p>An absent or empty value is <strong>not</strong> all zeroes, because {@code LOW-VALUES} and
     * {@code ZEROS} are distinct COBOL figurative constants and the source tests them as separate arms of
     * one {@code OR}.</p>
     *
     * @param value the field value, possibly {@code null}
     * @return {@code true} when every character is the digit zero
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
     * Reproduces the COBOL {@code IS NOT NUMERIC} class test on a {@code PIC 9(width)} DISPLAY field,
     * negated: {@code CC-ACCT-ID-N} at {@code :740} and {@code CC-CARD-NUM-N} at {@code :784}.
     *
     * <p>The class test is true only when <em>every one</em> of the declared positions holds a digit, so
     * a short entry, which the terminal blank-pads, is rejected. That is exactly what the two rejection
     * literals promise: "MUST BE A 11 DIGIT NUMBER" and "MUST BE A 16 DIGIT NUMBER". No leniency is added
     * and no separator, sign or space is tolerated.</p>
     *
     * @param value the field value, possibly {@code null}
     * @param width the declared field width, {@link #WIDTH_ACCOUNT_ID} or {@link #WIDTH_CARD_NUMBER}
     * @return {@code true} when the value is exactly {@code width} digits
     */
    private static boolean isNumericClass(final String value, final int width) {
        if (value == null || value.length() != width) {
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
     * Reproduces the accepting arm of a numeric edit, {@code MOVE CC-ACCT-ID-N TO CDEMO-ACCT-ID} at
     * {@code :752} and {@code MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM} at {@code :796}, which is reached
     * only once {@link #isNumericClass} has already accepted the value.
     *
     * @param value the field value
     * @param width the declared field width
     * @return the numeric value, or {@code null} when the class test would have rejected it
     */
    private static Long parseNumericClass(final String value, final int width) {
        return isNumericClass(value, width) ? Long.valueOf(value) : null;
    }

    /**
     * Reproduces {@code MOVE CCUP-OLD-ACCTID TO CDEMO-ACCT-ID} at {@code :671} and
     * {@code MOVE CCUP-OLD-CARDID TO CDEMO-CARD-NUM} at {@code :672}, where the receiving field is
     * {@code PIC 9(n)} and the sending field is the snapshot's text form.
     *
     * <p>Unlike {@link #parseNumericClass} this is a {@code MOVE} and not a class test, so it is
     * deliberately tolerant of a shorter value: the snapshot was produced by this very program and its
     * width is whatever {@code 9000-READ-DATA} stored. A value that is not a run of digits yields
     * {@code null} rather than an exception, because the source's {@code MOVE} cannot fail and a thrown
     * {@code NumberFormatException} would be an error path the program does not have.</p>
     *
     * @param value the snapshot field value, possibly {@code null}
     * @return the numeric value, or {@code null} when the field is absent, blank or not all digits
     */
    private static Long parseDigits(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            final char character = trimmed.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        return Long.valueOf(trimmed);
    }

    /**
     * Reproduces a COBOL {@code MOVE} into an alphanumeric {@code PIC X(width)} field, which left-aligns
     * the value and blank-pads it to the declared width, truncating on the right if it is longer.
     *
     * <p>This is what makes the group comparison of Regime A and the six predicates of Regime B behave
     * like the source: COBOL compares fixed-width storage, so {@code "AB"} and {@code "AB "} are the
     * same value in an {@code X(3)} field and must compare equal here too.</p>
     *
     * @param value the value to place in the field, possibly {@code null}
     * @param width the declared field width
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String padRight(final String value, final int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Clamps a received map field to the width its BMS symbolic map declares, reproducing the geometry
     * of a 3270 input field.
     *
     * <p>Every field {@code 1100-RECEIVE-MAP} reads at {@code :589-635} is declared in
     * {@code app/cpy-bms/COCRDUP.CPY} with a fixed {@code PIC X(n)} - {@code CRDSTCDI} is
     * {@code PIC X(1)} at line 78, {@code EXPMONI} is {@code PIC X(2)} at line 84, and so on - and each
     * is moved into a {@code CCUP-NEW-*} item of the same declared width, inline at {@code :294-312}.
     * A terminal field physically cannot hold more bytes than it declares, so an over-length value is
     * unrepresentable on the legacy side, and a COBOL alphanumeric {@code MOVE} into a shorter
     * receiving item truncates on the right rather than faulting.</p>
     *
     * <p>A Java {@code String} carries no such bound. {@code CardUpdateRequest} declares
     * {@code @Size(max = n)} on each component, but Bean Validation runs at the controller boundary and
     * not inside the canonical record constructor, so a direct caller can present a value the terminal
     * could never have produced. Clamping here - before the {@code '*'} and blank tests, exactly where
     * the terminal's own width applies - keeps every downstream consumer seeing the same byte width the
     * legacy would have seen. Without it an over-width value travels as far as the screen render at
     * {@code :2313} or the entity setter, and surfaces as a masked abend instead of the field-level
     * rejection the edit paragraphs are there to produce.</p>
     *
     * <p>This truncates but never pads, because a legacy {@code MOVE} between two items of equal width
     * is the identity: padding here would push trailing spaces into comparisons that the source
     * performs on the unpadded work fields.</p>
     *
     * @param value the value as presented by the caller, possibly {@code null}
     * @param width the width the symbolic map declares for the field
     * @return the value clamped to {@code width} characters, or {@code null} when {@code value} is
     *         {@code null}
     */
    private static String truncateToWidth(final String value, final int width) {
        if (value == null || value.length() <= width) {
            return value;
        }
        return value.substring(0, width);
    }

    /**
     * Reproduces a COBOL {@code MOVE} into a numeric {@code PIC 9(width)} DISPLAY field, which
     * right-aligns the value and zero-pads it, as {@code :1090} and {@code :1096} do when rendering the
     * two filters back onto the map.
     *
     * @param value the digits to place in the field, possibly {@code null}
     * @param width the declared field width
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String padLeftZeroes(final String value, final int width) {
        if (value == null) {
            return "0".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(value.length() - width);
        }
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * Reproduces the alphabetic-only edit of {@code 1230-EDIT-NAME} at {@code :823-837}.
     *
     * <p>The source idiom is {@code MOVE CCUP-NEW-CRDNAME TO CARD-NAME-CHECK}, then
     * {@code INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALL-SPACES-TO}, then
     * {@code IF FUNCTION LENGTH(FUNCTION TRIM(CARD-NAME-CHECK)) = 0}. Blanking every letter and then
     * checking that nothing survives is precisely a test that the field holds letters and spaces only, so
     * the outcome is implemented directly as a per-character test rather than as a copy-and-blank pass.</p>
     *
     * <p>{@code LIT-ALL-ALPHA-FROM} at {@code :255-257} is the fifty-two ASCII letters
     * {@code A} to {@code Z} and {@code a} to {@code z} and nothing else, so digits, punctuation,
     * accented letters and every other non-ASCII letter are rejected. The alphabet is taken from that
     * literal rather than from {@link Character#isLetter}, which would wrongly accept the whole Unicode
     * letter category, and <strong>no regular expression is introduced</strong> because the source has
     * none.</p>
     *
     * @param value the field value, already padded to its declared width by the caller
     * @return {@code true} when every character is an ASCII letter or a space
     */
    private static boolean isAllLettersOrSpaces(final String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            // The membership test is driven from the source literal itself rather than from a hand-written
            // character range, so the accepted alphabet is provably the same 52 characters that
            // LIT-ALL-ALPHA-FROM lists at :255-257. A range test would silently diverge if that literal
            // ever differed from A-Z plus a-z; this cannot.
            if (ALL_ALPHA_FROM.indexOf(character) < 0 && character != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders the {@code CARDDATA} group that Regime A compares at {@code :680-683}.
     *
     * <p>The group is declared twice, as {@code CCUP-OLD-CARDDATA} at {@code :296-301} and
     * {@code CCUP-NEW-CARDDATA} at {@code :308-313}, with identical subordinate fields in this order:
     * embossed name {@code X(50)}, expiry year {@code X(4)}, expiry month {@code X(2)}, expiry day
     * {@code X(2)}, active status {@code X(1)} &mdash; fifty-nine bytes. Concatenating the five at their
     * declared widths reproduces the contiguous storage that {@code FUNCTION UPPER-CASE} is applied to,
     * so the comparison is byte-for-byte equivalent to the source's.</p>
     *
     * <p>The expiry <strong>day participates</strong>, which is why an edit that changes only the day is
     * detected as a change even though no paragraph validates the day. The card verification value, the
     * account identifier and the card number do <strong>not</strong> participate, because they sit
     * outside the group; the verification value is compared separately by Regime B at {@code :1503}, whose
     * counterpart here is the {@code @Version} column.</p>
     *
     * @param cardholderName the embossed name, possibly {@code null}
     * @param expiryYear     the four-character expiry year, possibly {@code null}
     * @param expiryMonth    the two-character expiry month, possibly {@code null}
     * @param expiryDay      the two-character carried expiry day, possibly {@code null}
     * @param cardStatusCode the single-character active status, possibly {@code null}
     * @return the fifty-nine character group image, never {@code null}
     */
    private static String renderCardDataGroup(final String cardholderName, final String expiryYear,
                                              final String expiryMonth, final String expiryDay,
                                              final String cardStatusCode) {
        return padRight(cardholderName, WIDTH_EMBOSSED_NAME)
                + padRight(expiryYear, WIDTH_EXPIRY_YEAR)
                + padRight(expiryMonth, WIDTH_EXPIRY_MONTH)
                + padRight(expiryDay, WIDTH_EXPIRY_DAY)
                + padRight(cardStatusCode, WIDTH_CARD_STATUS);
    }

    /**
     * Reproduces the {@code STRING} statement at {@code :1467-1474}, which builds the stored expiry date
     * from three snapshot fields joined by literal dashes:
     * {@code STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY DELIMITED BY SIZE INTO
     * CARD-UPDATE-EXPIRAION-DATE}.
     *
     * <p>{@code DELIMITED BY SIZE} means each sending field contributes its full declared width, so the
     * result is exactly {@code 4 + 1 + 2 + 1 + 2 = }{@value #WIDTH_EXPIRAION_DATE} characters and the
     * three components land at the offsets {@code (1:4)}, {@code (6:2)} and {@code (9:2)} that Regime B
     * reads back at {@code :1505-1507}. The value stays {@code String} throughout: no
     * {@code LocalDate}, no {@code DateTimeFormatter} round trip, because a date type would normalise
     * away the very representation the comparison depends on, and the day is never validated so it need
     * not even be a real calendar day.</p>
     *
     * @param expiryYear  the four-character expiry year, possibly {@code null}
     * @param expiryMonth the two-character expiry month, possibly {@code null}
     * @param expiryDay   the two-character carried expiry day, possibly {@code null}
     * @return the ten-character dash-separated stored form, never {@code null}
     */
    private static String assembleExpiraionDate(final String expiryYear, final String expiryMonth,
                                                final String expiryDay) {
        final String assembled = padRight(expiryYear, WIDTH_EXPIRY_YEAR)
                + '-'
                + padRight(expiryMonth, WIDTH_EXPIRY_MONTH)
                + '-'
                + padRight(expiryDay, WIDTH_EXPIRY_DAY);
        // The STRING at :1467-1474 delivers into CARD-UPDATE-EXPIRAION-DATE PIC X(10) at :319, so the
        // assembled value is held to that declared width: 4 + 1 + 2 + 1 + 2. Padding rather than
        // truncating, because every component is already fixed-width by the time it arrives here.
        return padRight(assembled, WIDTH_EXPIRAION_DATE);
    }

    /**
     * Reproduces a COBOL reference modifier {@code field(start:length)} on the stored expiry date, as used
     * at {@code :1361-1366} and {@code :1505-1507} and {@code :1514-1516}.
     *
     * <p>COBOL offsets are one-based, so {@code (1:4)}, {@code (6:2)} and {@code (9:2)} become
     * {@code (1, 4)}, {@code (6, 2)} and {@code (9, 2)} here. A field too short to contain the requested
     * window yields {@code null} rather than an exception, because the source's reference modifier cannot
     * fail on a fixed-width record and a thrown
     * {@code StringIndexOutOfBoundsException} would be an error path the program does not have.</p>
     *
     * @param value         the field value, possibly {@code null}
     * @param oneBasedStart the one-based start position, exactly as written in the source
     * @param length        the window length
     * @return the extracted window, or {@code null} when the field cannot supply it
     */
    private static String substringOrNull(final String value, final int oneBasedStart,
                                          final int length) {
        if (value == null) {
            return null;
        }
        final int start = oneBasedStart - 1;
        if (start < 0 || start + length > value.length()) {
            return null;
        }
        return value.substring(start, start + length);
    }

    /**
     * Compares two values the way COBOL compares two alphanumeric fields of the same declared width:
     * both are padded to that width first, so trailing blanks and an absent value are indistinguishable
     * from a run of spaces. Used for all six predicates of Regime B at {@code :1503-1508}.
     *
     * @param left  one field value, possibly {@code null}
     * @param right the other field value, possibly {@code null}
     * @param width the declared width both fields share
     * @return {@code true} when the two fixed-width images are identical
     */
    private static boolean fixedWidthEquals(final String left, final String right, final int width) {
        return padRight(left, width).equals(padRight(right, width));
    }

    /**
     * Reproduces {@code MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM} at {@code :1380} and {@code :1425},
     * producing the sixteen-character record identifier that both file verbs supply as {@code RIDFLD}.
     *
     * <p>{@code CC-CARD-NUM} is {@code X(16)} redefined as {@code CC-CARD-NUM-N PIC 9(16)}, and
     * {@code 1220-EDIT-CARD} has already required the class test to pass, so a validated filter is
     * exactly sixteen digits and the text and numeric views coincide. Rendering from the numeric view
     * therefore loses nothing and cannot carry a stray blank into the key.</p>
     *
     * @param cardNumber the validated numeric card number, possibly {@code null}
     * @return the sixteen-character key, never {@code null}
     */
    private static String cardRecordKey(final Long cardNumber) {
        return cardNumber == null
                ? CARD_NUMBER_ZEROES
                : padLeftZeroes(Long.toString(cardNumber), WIDTH_CARD_NUMBER);
    }

    /**
     * Reads one card by card number <strong>and</strong> owning account, which is the predicate
     * {@code 9100-GETCARD-BYACCTCARD} and {@code 9200-WRITE-PROCESSING} are named for.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:1379} and {@code :1424} both carry
     * {@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID} as a comment, so the legacy reads matched on the card
     * number alone. On a 3270 that was survivable: the operator had reached the card through the account's
     * own list screen, and the account identifier on the screen was the one the region had put there. Over
     * HTTP both values arrive together in one request, so a single-key read hands the caller any card in the
     * file - and {@code :1463} then rewrites that card with the account identifier the caller supplied.
     * Restoring the commented-out half of the key is what closes both halves of that at once.
     *
     * <p>A card that exists but belongs to another account returns empty, exactly as a card that does not
     * exist returns empty, so the caller's outcome is the byte-exact
     * {@value #MSG_DID_NOT_FIND_ACCTCARD_COMBO} of {@code :1400} in both cases. That is not an accident of
     * implementation: distinguishing the two would let a caller enumerate the card file by account.
     *
     * <p>Side effects: none. One read-only query, its two values bound as parameters.
     *
     * @param recordIdentifier the sixteen-character card number, zero-padded, as
     *                         {@code WS-CARD-RID-CARDNUM} holds it
     * @param accountId        the account the card must belong to, as {@code CC-ACCT-ID-N} holds it; a
     *                         {@code null} account can own nothing and yields empty
     * @return the card when it exists and belongs to that account, otherwise empty
     */
    private Optional<Card> readCardForAccount(final String recordIdentifier, final Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return cardRepository.findByCardNumberAndAccountId(recordIdentifier, accountId);
    }

    /**
     * The same two-key read as {@link #readCardForAccount(String, Long)}, taken under the write lock that
     * {@code EXEC CICS READ ... UPDATE} at {@code :1427-1436} acquires.
     *
     * <p>Separate from the unlocked form because the two call sites have different needs and neither may
     * borrow the other's: {@code 9100-GETCARD-BYACCTCARD} reads to display and must not hold a row, while
     * {@code 9200-WRITE-PROCESSING} reads to rewrite and must. Both apply the account predicate, so an
     * unowned card is invisible to both.
     *
     * <p>Side effects: one read-only query that takes a row lock for the remainder of the transaction. A
     * {@code null} account can own nothing and yields empty without querying, which the caller reports as the
     * could-not-lock outcome of {@code :1441-1449}.
     *
     * @param recordIdentifier the sixteen-character card number, zero-padded, as
     *                         {@code WS-CARD-RID-CARDNUM} holds it
     * @param accountId        the account the card must belong to, as {@code CC-ACCT-ID-N} holds it
     * @return the locked card when it exists and belongs to that account, otherwise empty
     */
    private Optional<Card> readCardForAccountForUpdate(final String recordIdentifier, final Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return cardRepository.findByIdAndAccountIdForUpdate(recordIdentifier, accountId);
    }

    /**
     * Composes {@code WS-FILE-ERROR-MESSAGE}, declared at {@code :133-152}, which
     * {@code 9100-GETCARD-BYACCTCARD} moves into {@code WS-RETURN-MSG} at {@code :1411}.
     *
     * <p>The group is eight subordinate fields totalling exactly eighty characters, matching
     * {@code WS-RETURN-MSG PIC X(80)}: the {@value #FILE_ERROR_PREFIX} literal of {@code X(12)} at
     * {@code :134-135} &mdash; note the <strong>trailing space</strong>, which
     * {@code app/cbl/COCRDLIC.cbl} does not have; {@code ERROR-OPNAME X(8)} at {@code :136};
     * {@value #FILE_ERROR_ON} of {@code X(4)} at {@code :138-139}; {@code ERROR-FILE X(9)} at
     * {@code :140}; {@value #FILE_ERROR_RETURNED_RESP} of {@code X(15)} at {@code :142-144};
     * {@code ERROR-RESP X(10)} at {@code :145}; {@value #FILE_ERROR_RESP2} of {@code X(7)} at
     * {@code :147-148}; {@code ERROR-RESP2 X(10)} at {@code :149}; and a final {@code FILLER X(5)}
     * declared {@code VALUE SPACES} at {@code :151-152}, which this program has and
     * {@code app/cbl/COCRDLIC.cbl} does not. The literal is never shared with the sibling card services
     * for exactly that reason.</p>
     *
     * <p>The two response subfields are left at their declared {@code VALUE SPACES}. That is deliberate
     * and is the honest rendering: <strong>Not available</strong> &mdash; Java has no CICS response or
     * reason code to place there, and inventing one would put a fabricated diagnostic into an
     * eighty-character contract the parity comparison reads. The real diagnosis is not lost: it travels
     * as the {@code cause} of the {@code FileAccessException} this message accompanies.</p>
     *
     * @param operation the file verb name, {@link #OPERATION_READ} or {@link #OPERATION_REWRITE}
     * @return the eighty-character message image, never {@code null}
     */
    private static String fileErrorMessage(final String operation) {
        return FILE_ERROR_PREFIX
                + padRight(operation, WIDTH_ERROR_OPERATION_NAME)
                + FILE_ERROR_ON
                + padRight(CARD_FILE_NAME, WIDTH_ERROR_FILE_NAME)
                + FILE_ERROR_RETURNED_RESP
                + padRight(null, WIDTH_ERROR_RESPONSE_CODE)
                + FILE_ERROR_RESP2
                + padRight(null, WIDTH_ERROR_RESPONSE_CODE)
                + FILE_ERROR_TRAILER;
    }

    /**
     * Masks a card number for the one purpose a card number may legitimately appear in this file: the
     * {@code affectedRecord} argument of {@code ConcurrentUpdateException}, which the error handler may
     * render.
     *
     * <p>Rule 1 Clause D forbids secrets in code, logs, tests and configuration, and the {@code Card}
     * entity deliberately excludes the card number, the verification value and the embossed name from its
     * own {@code toString()} while providing no masking helper of its own, so masking is this file's
     * responsibility. Only the last four characters survive; everything before them becomes an asterisk,
     * so the length of the original is preserved for diagnosis while the value is not. The card
     * verification value is never passed here, and could not be: it is stored on the entity as a
     * write-once field with no getter of any visibility, so no code in this file can read it.</p>
     *
     * @param value the card number, possibly {@code null}
     * @return the masked form, or {@code null} when there was nothing to mask
     */
    private static String maskTail(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String trimmed = value.strip();
        if (trimmed.length() <= MASK_VISIBLE_SUFFIX_LENGTH) {
            return "*".repeat(trimmed.length());
        }
        return "*".repeat(trimmed.length() - MASK_VISIBLE_SUFFIX_LENGTH)
                + trimmed.substring(trimmed.length() - MASK_VISIBLE_SUFFIX_LENGTH);
    }

    /**
     * Restores the card number the read withheld from the as-displayed group.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:1347} - {@code MOVE CC-CARD-NUM TO CCUP-OLD-CARDID} - sources that
     * member from the <em>received</em> map field rather than from the stored record, so reinstating it from
     * the request's own card number is what the source does, not a substitution for it. It is done here
     * because {@code CardResponse} withholds the member on the read: it is the full sixteen digits that
     * response masks everywhere else, and no comparison reads it - {@code 9300-CHECK-CHANGE-IN-REC} compares
     * the embossed name, the three expiry components and the active status, and {@code isSnapshotEmpty} tests
     * those same five.</p>
     *
     * <p>A group that already carries a card number keeps it untouched - which is the in-process
     * screen-faithful path, where the caller holds the whole group. Every other member is relayed by reference
     * in both cases.</p>
     *
     * @param openedOldDetails the group recovered from the sealed snapshot, or the in-process caller's own
     *     group, or {@code null} when none was presented
     * @param receivedCardNumber the request's own {@code CARDSIDI} value, possibly {@code null}
     * @return the group to compare against, or {@code null} when {@code openedOldDetails} was {@code null}
     */
    private static CardUpdateRequest.CardDetails restoreSnapshotCardNumber(
            final CardUpdateRequest.CardDetails openedOldDetails, final String receivedCardNumber) {
        if (openedOldDetails == null || openedOldDetails.cardNumber() != null) {
            return openedOldDetails;
        }
        return new CardUpdateRequest.CardDetails(openedOldDetails.accountId(), receivedCardNumber,
                openedOldDetails.cardData());
    }

    /**
     * Decides whether the request carries a usable as-displayed snapshot, which is the stateless
     * equivalent of {@code IF CCUP-DETAILS-NOT-FETCHED} at {@code :645} and {@code :954}.
     *
     * <p>A snapshot whose five comparison fields are all absent cannot support Regime B, so the state
     * machine is in {@code CCUP-DETAILS-NOT-FETCHED} and the write is unreachable. Testing the fields
     * rather than merely the presence of the group matters: a caller that sends an empty object would
     * otherwise be treated as having been shown data it never saw, and Regime B would pass vacuously
     * against a stored row of blanks.</p>
     *
     * @param details the {@code CCUP-OLD-DETAILS} counterpart, possibly {@code null}
     * @return {@code true} when no comparison field is present
     */
    private static boolean isSnapshotEmpty(final CardUpdateRequest.CardDetails details) {
        if (details == null) {
            return true;
        }
        final CardUpdateRequest.CardData data = details.cardData();
        if (data == null) {
            return true;
        }
        final CardUpdateRequest.ExpiraionDate date = data.expiraionDate();
        return isBlankOrLowValues(data.cardholderName())
                && isBlankOrLowValues(data.cardStatusCode())
                && (date == null
                    || (isBlankOrLowValues(date.expiryYear())
                        && isBlankOrLowValues(date.expiryMonth())
                        && isBlankOrLowValues(date.expiryDay())));
    }

    /**
     * Resolves one screen field from the request, preferring the flat map component because that is what
     * {@code 1100-RECEIVE-MAP} reads at {@code :589-635}, and falling back to the corresponding
     * {@code newDetails} leaf when the flat component is absent entirely.
     *
     * <p>The fallback exists because {@code CardUpdateRequest} carries both projections and a caller may
     * legitimately populate only the structured one. It is a fallback and not a merge: a flat component
     * that is present but not supplied, meaning blank or the asterisk marker, still wins, so the
     * asterisk normalisation of {@code :589-635} cannot be bypassed by also sending a structured value.</p>
     *
     * @param flat   the flat map component, possibly {@code null}
     * @param nested the corresponding {@code newDetails} leaf, possibly {@code null}
     * @return the resolved raw field value, possibly {@code null}
     */
    private static String firstSupplied(final String flat, final String nested) {
        return flat != null ? flat : nested;
    }

    /**
     * Reproduces the asterisk-or-blank normalisation of {@code 1100-RECEIVE-MAP}, whose comment at
     * {@code :588} reads "REPLACE * WITH LOW-VALUES".
     *
     * <p>The test is {@code IF field = '*' OR field = SPACES} at {@code :589-590}, {@code :598-599},
     * {@code :607-608}, {@code :614-615}, {@code :623-624} and {@code :630-631}. COBOL extends the
     * one-character literal {@code '*'} to the field width with blanks, so equality holds if and only if
     * the field is an asterisk followed by nothing but spaces &mdash; which is why the remainder is tested
     * rather than the whole value simply being stripped: {@code " *"} is <em>not</em> the marker.</p>
     *
     * <p><strong>This applies to six fields only.</strong> {@code EXPDAYI} is deliberately excluded: its
     * move at {@code :621} is bare and unconditional, so the carried expiry day accepts an asterisk and a
     * blank as literal values.</p>
     *
     * @param value the raw received field value, possibly {@code null}
     * @return {@code true} when the field is absent, all blanks, or the asterisk marker
     */
    private static boolean isNotSupplied(final String value) {
        if (isBlankOrLowValues(value)) {
            return true;
        }
        return value.charAt(0) == '*' && value.substring(1).isBlank();
    }
    // Nested types. Every one is a member of this class rather than a separate compilation unit, so the
    // package keeps to its three-file budget and no helper, mapper or validator type is introduced.

    /**
     * How the reply is delivered, standing in for the two mutually exclusive terminal operations of
     * {@code 0000-MAIN}: {@code EXEC CICS SEND MAP} at {@code :1328-1336} followed by
     * {@code EXEC CICS RETURN TRANSID} at {@code :553-558}, or {@code EXEC CICS XCTL} at
     * {@code :472-475}.
     */
    public enum ResponseKind {

        /** {@code SEND MAP} then {@code RETURN TRANSID}: the screen is redisplayed to the same caller. */
        MAP,

        /**
         * {@code XCTL}: control passes elsewhere and this program ends. Under transformation Rule 7 the
         * caller navigates to the URL named by the accompanying {@link Navigation}; no card service is
         * invoked, because {@code XCTL} terminates the caller rather than returning to it.
         */
        TRANSFER
    }

    /**
     * Whether this is a first entry or a continuation, standing in for {@code CDEMO-PGM-CONTEXT} of
     * {@code app/cpy/COCOM01Y.cpy} as tested at {@code :387}, {@code :1084}, {@code :1141},
     * {@code :1246} and {@code :1254}.
     *
     * <p>The pseudo-conversational enter-versus-re-enter flag has no natural Java counterpart, because a
     * stateless request carries no COMMAREA. It is surfaced as an explicit parameter so the caller states
     * its intent rather than the server inferring it from retained state.</p>
     */
    public enum EntryMode {

        /** {@code SET CDEMO-PGM-ENTER TO TRUE} at {@code :393}: an empty screen, no filters honoured. */
        ENTER,

        /** {@code SET CDEMO-PGM-REENTER TO TRUE} at {@code :480}: the filters and snapshot are honoured. */
        REENTER
    }

    /**
     * The terminal attention identifier, as {@code YYYY-STORE-PFKEY} maps it from {@code EIBAID} at
     * {@code app/cpy/CSSTRPFY.cpy:21-78}.
     *
     * <p>All sixteen distinct values are modelled, because the copybook maps all sixteen and the
     * paragraph is transcribed one for one. Only three reach a distinct behaviour in this program:
     * {@link #PFK03} is back at {@code :430}, {@link #PFK05} is confirm-and-save at {@code :988}, and
     * {@link #PFK12} is cancel at {@code :958} and {@code :478}. The validity gate at {@code :413-425}
     * silently rewrites every other value to {@link #ENTER}.</p>
     */
    public enum AidKey {

        /** {@code DFHENTER}, {@code app/cpy/CSSTRPFY.cpy:22-23}. */
        ENTER("DFHENTER"),

        /** {@code DFHCLEAR}, {@code app/cpy/CSSTRPFY.cpy:24-25}. */
        CLEAR("DFHCLEAR"),

        /** {@code DFHPA1}, {@code app/cpy/CSSTRPFY.cpy:26-27}. */
        PA1("DFHPA1"),

        /** {@code DFHPA2}, {@code app/cpy/CSSTRPFY.cpy:28-29}. */
        PA2("DFHPA2"),

        /** {@code DFHPF1}, {@code app/cpy/CSSTRPFY.cpy:30-31}; {@code DFHPF13} folds here at {@code :54-55}. */
        PFK01("DFHPF1"),

        /** {@code DFHPF2}, {@code app/cpy/CSSTRPFY.cpy:32-33}; {@code DFHPF14} folds here at {@code :56-57}. */
        PFK02("DFHPF2"),

        /** {@code DFHPF3}, {@code app/cpy/CSSTRPFY.cpy:34-35}. Back, at {@code app/cbl/COCRDUPC.cbl:430}. */
        PFK03("DFHPF3"),

        /** {@code DFHPF4}, {@code app/cpy/CSSTRPFY.cpy:36-37}. */
        PFK04("DFHPF4"),

        /**
         * {@code DFHPF5}, {@code app/cpy/CSSTRPFY.cpy:38-39}. Confirm and save, at
         * {@code app/cbl/COCRDUPC.cbl:988}: the only intent that reaches {@code 9200-WRITE-PROCESSING}.
         */
        PFK05("DFHPF5"),

        /** {@code DFHPF6}, {@code app/cpy/CSSTRPFY.cpy:40-41}. */
        PFK06("DFHPF6"),

        /** {@code DFHPF7}, {@code app/cpy/CSSTRPFY.cpy:42-43}. */
        PFK07("DFHPF7"),

        /** {@code DFHPF8}, {@code app/cpy/CSSTRPFY.cpy:44-45}. */
        PFK08("DFHPF8"),

        /** {@code DFHPF9}, {@code app/cpy/CSSTRPFY.cpy:46-47}. */
        PFK09("DFHPF9"),

        /** {@code DFHPF10}, {@code app/cpy/CSSTRPFY.cpy:48-49}. */
        PFK10("DFHPF10"),

        /** {@code DFHPF11}, {@code app/cpy/CSSTRPFY.cpy:50-51}. */
        PFK11("DFHPF11"),

        /**
         * {@code DFHPF12}, {@code app/cpy/CSSTRPFY.cpy:52-53}. Cancel, at
         * {@code app/cbl/COCRDUPC.cbl:958}, whose source comment reads "CHANGES MADE. BUT USER CANCELS":
         * it re-fetches from the store and discards the caller's edits.
         */
        PFK12("DFHPF12");

        /** The {@code EIBAID} symbol {@code app/cpy/CSSTRPFY.cpy} tests for this key. */
        private final String symbol;

        /**
         * Binds one attention key to the {@code EIBAID} symbol that identifies it.
         *
         * @param symbol the {@code EIBAID} symbol this key corresponds to
         */
        AidKey(final String symbol) {
            this.symbol = symbol;
        }

        /**
         * Returns the {@code EIBAID} symbol this key corresponds to, which is the form the public API
         * accepts so that a caller may pass the legacy vocabulary verbatim.
         *
         * @return the symbol, never {@code null}
         */
        public String symbol() {
            return symbol;
        }
    }

    /**
     * The card-update state machine, {@code CCUP-CHANGE-ACTION PIC X(1)} declared at {@code :276-290}
     * with its eight {@code 88}-level condition names.
     *
     * <p>Seven states, because two of the eight condition names are <strong>group</strong> conditions
     * rather than states: {@code CCUP-CHANGES-MADE} at {@code :282-284} spans the five values
     * {@code 'E'}, {@code 'N'}, {@code 'C'}, {@code 'L'} and {@code 'F'}, and
     * {@code CCUP-CHANGES-FAILED} at {@code :288} spans {@code 'L'} and {@code 'F'}. They are exposed
     * as {@link #isChangesMade()} and {@link #isChangesFailed()} so that {@code :1113} and
     * {@code :502-503} read exactly as the source does.</p>
     *
     * <p>The state travels in the request and the response, never in a session, a bean field or a
     * server-side cursor. {@code CCUP-DETAILS-NOT-FETCHED} covers both {@code LOW-VALUES} and
     * {@code SPACES} at {@code :278-280}, which is why an absent or empty snapshot resolves to it.</p>
     */
    public enum ChangeAction {

        /** {@code 88 CCUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES} at {@code :278-280}. */
        DETAILS_NOT_FETCHED(' '),

        /** {@code 88 CCUP-SHOW-DETAILS VALUE 'S'} at {@code :281}. */
        SHOW_DETAILS('S'),

        /** {@code 88 CCUP-CHANGES-NOT-OK VALUE 'E'} at {@code :285}. At least one field edit failed. */
        CHANGES_NOT_OK('E'),

        /** {@code 88 CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} at {@code :286}. Awaiting {@code PF05}. */
        CHANGES_OK_NOT_CONFIRMED('N'),

        /** {@code 88 CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} at {@code :287}. The row was rewritten. */
        CHANGES_OKAYED_AND_DONE('C'),

        /** {@code 88 CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} at {@code :289}. Set by {@code :994}. */
        CHANGES_OKAYED_LOCK_ERROR('L'),

        /** {@code 88 CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} at {@code :290}. Set by {@code :996}. */
        CHANGES_OKAYED_BUT_FAILED('F');

        /** The single byte this state occupies in {@code CCUP-CHANGE-ACTION} at {@code :276}. */
        private final char code;

        /**
         * Binds one state to the {@code CCUP-CHANGE-ACTION} byte that represents it.
         *
         * @param code the {@code CCUP-CHANGE-ACTION} byte for this state, space standing for
         *             {@code LOW-VALUES}
         */
        ChangeAction(final char code) {
            this.code = code;
        }

        /**
         * Returns the single byte this state occupies in {@code CCUP-CHANGE-ACTION}. Space stands for the
         * {@code LOW-VALUES} initial value, which {@code :278-280} treats as equivalent to
         * {@code SPACES}.
         *
         * @return the state byte
         */
        public char code() {
            return code;
        }

        /**
         * The group condition {@code 88 CCUP-CHANGES-MADE VALUES 'E', 'N', 'C', 'L', 'F'} at
         * {@code :282-284}, tested at {@code :1113}.
         *
         * @return {@code true} for every state except {@link #DETAILS_NOT_FETCHED} and
         *         {@link #SHOW_DETAILS}
         */
        public boolean isChangesMade() {
            return this != DETAILS_NOT_FETCHED && this != SHOW_DETAILS;
        }

        /**
         * The group condition {@code 88 CCUP-CHANGES-FAILED VALUES 'L', 'F'} at {@code :288}, tested at
         * {@code :432} and {@code :503}.
         *
         * @return {@code true} for the lock-error and update-failed states only
         */
        public boolean isChangesFailed() {
            return this == CHANGES_OKAYED_LOCK_ERROR || this == CHANGES_OKAYED_BUT_FAILED;
        }
    }

    /**
     * The three-state outcome of one field edit, which every {@code FLG-} triad in the program declares:
     * {@code FLG-ACCTFILTER-*} at {@code :63-69}, {@code FLG-CARDFILTER-*}, {@code FLG-CARDNAME-*},
     * {@code FLG-CARDSTATUS-*}, {@code FLG-CARDEXPMON-*} and {@code FLG-CARDEXPYEAR-*}.
     *
     * <p>Each triad is three {@code 88}-levels over one flag byte, so the three states are mutually
     * exclusive and the last {@code SET} wins &mdash; which is exactly why {@code 1260}'s ordering
     * asymmetry at {@code :930} is harmless and is nevertheless transcribed as written.</p>
     *
     * <p>{@link #BLANK} versus {@link #NOT_OK} is the two-state distinction that
     * {@code ValidationException} carries and that {@code 3300} acts on: only a blank field is stamped
     * with an asterisk, at {@code :1247}, {@code :1255}, {@code :1265}, {@code :1275}, {@code :1284} and
     * {@code :1292}.</p>
     */
    public enum FieldEditState {

        /** {@code SET FLG-...-NOT-OK TO TRUE}: supplied but rejected. No asterisk is stamped. */
        NOT_OK,

        /** {@code SET FLG-...-ISVALID TO TRUE}: accepted. */
        IS_VALID,

        /** {@code SET FLG-...-BLANK TO TRUE}: not supplied. {@code 3300} stamps an asterisk. */
        BLANK
    }

    /**
     * The outcome of {@code 9200-WRITE-PROCESSING}, which the inner {@code EVALUATE TRUE} at
     * {@code :992-1001} switches on.
     *
     * <p><strong>The three failures must stay distinguishable.</strong> Collapsing them into one status
     * would be a regression, because {@code :993-1000} routes each to a different next
     * state and only {@link #DATA_WAS_CHANGED_BEFORE_UPDATE} is recoverable: {@code :998} returns to
     * {@code CCUP-SHOW-DETAILS} so the caller may retry against the refreshed snapshot, whereas
     * {@code :994} and {@code :996} are terminal.</p>
     */
    public enum WriteOutcome {

        /** No write was attempted on this pass, which is every path other than {@code :988-1001}. */
        NOT_ATTEMPTED,

        /** {@code :1488-1489}, the accepting arm of the rewrite: the row was written. */
        COMPLETED,

        /** {@code SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE} at {@code :1446}. Terminal. */
        COULD_NOT_LOCK_FOR_UPDATE,

        /** {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} at {@code :1511}. Recoverable. */
        DATA_WAS_CHANGED_BEFORE_UPDATE,

        /** {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code :1491}. Terminal. */
        LOCKED_BUT_UPDATE_FAILED
    }

    /**
     * The protection attribute of one screen field, replacing the {@code DFHBMSCA} symbols that
     * {@code 3300-SETUP-SCREEN-ATTRS} moves at {@code :1173-1197}, {@code :1277} and
     * {@code :1300-1316}.
     *
     * <p>{@code DFHBMSCA} is supplied by CICS and is copied in at {@code :327}; it is not present in this
     * repository, so its symbols are modelled here rather than imported.</p>
     */
    public enum FieldAttribute {

        /** {@code DFHBMFSE}: unprotected, modified-data-tag set. The caller may type into the field. */
        UNPROTECTED_MODIFIED,

        /** {@code DFHBMPRF}: protected. The field is displayed but cannot be typed into. */
        PROTECTED,

        /** {@code DFHBMDAR}: dark. The field is present but not displayed. */
        NON_DISPLAY,

        /** {@code DFHBMBRY}: bright. The field is displayed with emphasis. */
        BRIGHT
    }

    /**
     * The colour of one screen field, replacing the {@code DFHRED} and {@code DFHDFCOL} symbols that
     * {@code 3300-SETUP-SCREEN-ATTRS} moves at {@code :1232-1294}, plus the {@code DFHBMDAR} that
     * {@code :1277} moves into the expiry day's colour subfield rather than its attribute subfield.
     */
    public enum FieldColour {

        /** {@code DFHDFCOL}: the map's default colour, restored at {@code :1233-1234}. */
        DEFAULT,

        /** {@code DFHRED}: the field failed its edit. */
        RED,

        /**
         * {@code DFHBMDAR} moved into a colour subfield by {@code :1277}, unconditionally, so the carried
         * expiry day is never shown.
         */
        NON_DISPLAY
    }

    /**
     * Where {@code 0000-MAIN}'s transfer branch sends control, replacing
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code :472-475}.
     *
     * <p>Every component is a legacy identifier rather than a URL, because this service does not own
     * routing: {@code WebConfig} does. The record states <em>where the source would have transferred</em>
     * so the controller can map that onto its own URL space.</p>
     *
     * @param toTransactionId   {@code CDEMO-TO-TRANID}, resolved at {@code :436-441}, defaulting to the
     *                          main menu transaction when the caller context is absent
     * @param toProgram         {@code CDEMO-TO-PROGRAM}, resolved at {@code :442-447}, defaulting to the
     *                          main menu program
     * @param fromTransactionId {@code CDEMO-FROM-TRANID}, set to this transaction at {@code :448}
     * @param fromProgram       {@code CDEMO-FROM-PROGRAM}, set to this program at {@code :449}
     * @param lastMapset        {@code CDEMO-LAST-MAPSET}, set to this mapset at {@code :456}
     * @param lastMap           {@code CDEMO-LAST-MAP}, set to this map at {@code :457}
     */
    public record Navigation(String toTransactionId, String toProgram, String fromTransactionId,
                             String fromProgram, String lastMapset, String lastMap) {
    }

    /**
     * The per-field presentation decisions of {@code 3300-SETUP-SCREEN-ATTRS}, {@code :1168-1317}.
     *
     * <p>These are carried alongside the payload rather than inside {@code CardDto}, because
     * {@code CardDto} is the {@code app/cpy-bms/COCRDSL.CPY} and {@code COCRDLI.CPY} field contract and
     * has no attribute or colour members. A caller that only renders values may ignore this record
     * entirely; a caller that reproduces the legacy screen has everything the source computed.</p>
     *
     * @param accountIdAttribute          {@code ACCTSIDA}, set at {@code :1174} and {@code :1183}
     * @param cardNumberAttribute         {@code CARDSIDA}, set at {@code :1175} and {@code :1184}
     * @param cardholderNameAttribute     {@code CRDNAMEA}, set at {@code :1176} and {@code :1185}
     * @param cardStatusAttribute         {@code CRDSTCDA}, set at {@code :1177} and {@code :1186}
     * @param expiryMonthAttribute        {@code EXPMONA}, set at {@code :1178} and {@code :1187}
     * @param expiryYearAttribute         {@code EXPYEARA}, set at {@code :1179} and {@code :1188}
     * @param informationMessageAttribute {@code INFOMSGA}, set at {@code :1301} or {@code :1304}
     * @param functionKeysAttribute       {@code FKEYSCA}, set at {@code :1313} only while confirmation is
     *                                    pending; {@code null} otherwise, since the source leaves it
     *                                    untouched
     * @param accountIdColour             {@code ACCTSIDC}, {@code :1233}, {@code :1239}, {@code :1248}
     * @param cardNumberColour            {@code CARDSIDC}, {@code :1234}, {@code :1253}, {@code :1256}
     * @param cardholderNameColour        {@code CRDNAMEC}, {@code :1261} and {@code :1266}
     * @param cardStatusColour            {@code CRDSTCDC}, {@code :1271} and {@code :1276}
     * @param expiryDayColour             {@code EXPDAYC}, set unconditionally at {@code :1277}
     * @param expiryMonthColour           {@code EXPMONC}, {@code :1280} and {@code :1285}
     * @param expiryYearColour            {@code EXPYEARC}, {@code :1289} and {@code :1293}
     * @param cursorField                 the field the {@code MOVE -1} of {@code :1201-1226} selected,
     *                                    named as the request property rather than as the length subfield
     */
    public record ScreenAttributes(FieldAttribute accountIdAttribute,
                                   FieldAttribute cardNumberAttribute,
                                   FieldAttribute cardholderNameAttribute,
                                   FieldAttribute cardStatusAttribute,
                                   FieldAttribute expiryMonthAttribute,
                                   FieldAttribute expiryYearAttribute,
                                   FieldAttribute informationMessageAttribute,
                                   FieldAttribute functionKeysAttribute,
                                   FieldColour accountIdColour,
                                   FieldColour cardNumberColour,
                                   FieldColour cardholderNameColour,
                                   FieldColour cardStatusColour,
                                   FieldColour expiryDayColour,
                                   FieldColour expiryMonthColour,
                                   FieldColour expiryYearColour,
                                   String cursorField) {
    }

    /**
     * Everything one pass of {@code COCRDUPC} produces, replacing the COMMAREA that
     * {@code EXEC CICS RETURN TRANSID ... COMMAREA(WS-COMMAREA)} at {@code :553-558} would have handed
     * to the next task.
     *
     * <p>Under transformation Rule 7 the identity and routing halves of that COMMAREA have no Java
     * counterpart: {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM},
     * {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT}, {@code CDEMO-LAST-MAP} and
     * {@code CDEMO-LAST-MAPSET} are all replaced by URL routing and by the token claims that
     * {@code SecurityConfig} owns. What genuinely has to survive to the next turn does travel here: the
     * rendered screen, the state byte, the presentation decisions and <strong>both</strong> snapshot
     * groups, because {@code :548-552} reassembles {@code CCUP-OLD-DETAILS} and
     * {@code CCUP-NEW-DETAILS} into the returned area and the next turn's Regime A and Regime B both
     * need them.</p>
     *
     * <p>No card verification value appears anywhere in this record. {@link CardUpdateRequest.CardDetails}
     * declares no such component, so neither {@code refreshedSnapshot} nor {@code submittedDetails} can
     * carry one and the stored value cannot leave the service, even though {@code CCUP-OLD-CVV-CD} is part
     * of the legacy group at {@code :294}.</p>
     *
     * @param responseKind      whether the reply is a screen or a transfer
     * @param screen            the rendered map, or {@code null} on a transfer
     * @param navigation        the transfer target, or {@code null} on a screen
     * @param changeAction      the state byte the next turn must send back
     * @param screenAttributes  the presentation decisions of {@code 3300}, or {@code null} on a transfer
     * @param refreshedSnapshot {@code CCUP-OLD-DETAILS} as this pass leaves it, which is what the read entry
     *                          point seals for the next turn to send as {@code snapshot}; {@code null} on a
     *                          transfer
     * @param submittedDetails  {@code CCUP-NEW-DETAILS} as {@code 1100} and the edits left it, echoed so
     *                          the caller can redisplay its own input; {@code null} on a transfer
     */
    public record CardUpdateResult(ResponseKind responseKind, CardDto screen, Navigation navigation,
                                   ChangeAction changeAction, ScreenAttributes screenAttributes,
                                   CardUpdateRequest.CardDetails refreshedSnapshot,
                                   CardUpdateRequest.CardDetails submittedDetails) {
    }

    /**
     * The symbolic output map {@code CCRDUPAO}, which {@code 3100-SCREEN-INIT} clears at {@code :1053}
     * and which {@code 3200}, {@code 3250} and {@code 3300} then populate.
     *
     * <p>Mutable and package-private by field, exactly as a BMS symbolic map is: the three paragraphs
     * write into the same area in a fixed order, and {@code 3300} deliberately overwrites values that
     * {@code 3200} placed. A record would not model that. Because one instance is created per request in
     * {@code screenInit3100} and is reachable only through the enclosing {@link UpdateContext}, it holds
     * no state that outlives the request and none that is shared between concurrent requests.</p>
     */
    private static final class ScreenBuffer {

        /**
         * Creates an empty map area, which is the state {@code MOVE LOW-VALUES TO CCRDUPAO} at
         * {@code :1053} establishes: every field null until {@code 3100}, {@code 3200}, {@code 3250} and
         * {@code 3300} populate it.
         */
        private ScreenBuffer() {
            // All fields intentionally left at their null defaults; :1053 clears the whole area and the
            // three populating paragraphs then write into it in a fixed order.
        }

        /** {@code TRNNAMEO}, set at {@code :1059}. */
        private String transactionName;

        /** {@code TITLE01O}, set at {@code :1057}. */
        private String title01;

        /** {@code TITLE02O}, set at {@code :1058}. */
        private String title02;

        /** {@code PGMNAMEO}, set at {@code :1060}. */
        private String programName;

        /** {@code CURDATEO}, set at {@code :1068}. */
        private String currentDate;

        /** {@code CURTIMEO}, set at {@code :1074}. */
        private String currentTime;

        /** {@code ACCTSIDO}, set at {@code :1088}, {@code :1090} or {@code :1247}. */
        private String accountId;

        /** {@code CARDSIDO}, set at {@code :1094}, {@code :1096} or {@code :1255}. */
        private String cardNumber;

        /** {@code CRDNAMEO}, set at {@code :1101}, {@code :1108}, {@code :1114}, {@code :1125} or {@code :1265}. */
        private String cardholderName;

        /** {@code CRDSTCDO}, set at {@code :1103}, {@code :1109}, {@code :1115}, {@code :1126} or {@code :1275}. */
        private String cardStatusCode;

        /**
         * {@code EXPDAYO}, set at {@code :1104}, {@code :1110}, {@code :1123} or {@code :1127}. Always the
         * OLD day, never the edited one: {@code :1122} is commented out in the source.
         */
        private String expiryDay;

        /** {@code EXPMONO}, set at {@code :1105}, {@code :1111}, {@code :1116}, {@code :1128} or {@code :1284}. */
        private String expiryMonth;

        /** {@code EXPYEARO}, set at {@code :1106}, {@code :1112}, {@code :1117}, {@code :1129} or {@code :1292}. */
        private String expiryYear;

        /** {@code INFOMSGO}, set at {@code :1161}. */
        private String informationMessage;

        /** {@code ERRMSGO}, set at {@code :1163}. */
        private String errorMessage;

        /** {@code ACCTSIDA}, set at {@code :1174}, {@code :1183}, {@code :1191} or {@code :1194}. */
        private FieldAttribute accountIdAttribute;

        /** {@code CARDSIDA}, set at {@code :1175}, {@code :1184}, {@code :1191} or {@code :1195}. */
        private FieldAttribute cardNumberAttribute;

        /** {@code CRDNAMEA}, set at {@code :1176}, {@code :1185}, {@code :1192} or {@code :1196}. */
        private FieldAttribute cardholderNameAttribute;

        /** {@code CRDSTCDA}, set at {@code :1177}, {@code :1186}, {@code :1192} or {@code :1196}. */
        private FieldAttribute cardStatusAttribute;

        /** {@code EXPMONA}, set at {@code :1178}, {@code :1187}, {@code :1192} or {@code :1197}. */
        private FieldAttribute expiryMonthAttribute;

        /** {@code EXPYEARA}, set at {@code :1179}, {@code :1188}, {@code :1192} or {@code :1197}. */
        private FieldAttribute expiryYearAttribute;

        /** {@code INFOMSGA}, set at {@code :1301} or {@code :1304}. */
        private FieldAttribute informationMessageAttribute;

        /** {@code FKEYSCA}, set at {@code :1313} only; left untouched otherwise. */
        private FieldAttribute functionKeysAttribute;

        /** {@code ACCTSIDC}, set at {@code :1233}, {@code :1239} or {@code :1248}. */
        private FieldColour accountIdColour;

        /** {@code CARDSIDC}, set at {@code :1234}, {@code :1253} or {@code :1256}. */
        private FieldColour cardNumberColour;

        /** {@code CRDNAMEC}, set at {@code :1261} or {@code :1266}. */
        private FieldColour cardholderNameColour;

        /** {@code CRDSTCDC}, set at {@code :1271} or {@code :1276}. */
        private FieldColour cardStatusColour;

        /** {@code EXPDAYC}, set unconditionally at {@code :1277}. */
        private FieldColour expiryDayColour;

        /** {@code EXPMONC}, set at {@code :1280} or {@code :1285}. */
        private FieldColour expiryMonthColour;

        /** {@code EXPYEARC}, set at {@code :1289} or {@code :1293}. */
        private FieldColour expiryYearColour;

        /** The field selected by the {@code MOVE -1} of {@code :1201-1226}, honouring {@code CURSOR}. */
        private String cursorField;

        /**
         * Renders this buffer as the {@code app/cpy-bms/COCRDSL.CPY} detail payload that
         * {@code CardDto.detail} declares, which is the closest field contract in the shared DTO to this
         * program's own {@code app/cpy-bms/COCRDUP.CPY}.
         *
         * <p>Two components of this map have no place in that contract and are recorded rather than
         * dropped silently. The carried expiry day is not a {@code COCRDSL} field, so it travels in the
         * result's snapshot groups instead, where the next turn actually needs it. The function-key line
         * is <strong>Not available</strong>: {@code COCRDUP.CPY} splits it into {@code FKEYSI PIC X(21)}
         * at line 114 and {@code FKEYSCI PIC X(18)} at line 120, a split unique to this map, and both are
         * static literals defined in {@code app/bms/COCRDUP.bms} which this program never populates
         * &mdash; it only brightens the second one's attribute at {@code :1313}. Emitting invented legend
         * text would put a fabricated value into a fixed-width contract whose two halves do not even sum
         * to the single joined field the shared DTO declares, so {@code null} is passed and the attribute
         * is reported through {@link ScreenAttributes} instead.</p>
         *
         * <p>The two message fields are padded to their declared widths, because
         * {@code MOVE ... TO INFOMSGO} and {@code ERRMSGO} at {@code :1161} and {@code :1163} deliver into
         * {@code INFOMSGI PIC X(40)} at {@code COCRDUP.CPY:102} and {@code ERRMSGI PIC X(80)} at
         * {@code :108}, and a COBOL move to a fixed-width field space-pads.</p>
         *
         * @param context the per-request state, consulted only for the two filter fields, which
         *                {@code 3200} renders from the work area rather than from this buffer
         * @return the payload, never {@code null}
         */
        private CardDto toDto(final UpdateContext context) {
            final String renderedAccountId = accountId != null
                    ? accountId
                    : renderFilter(context.receivedAccountId, WIDTH_ACCOUNT_ID);
            final String renderedCardNumber = cardNumber != null
                    ? cardNumber
                    : renderFilter(context.receivedCardNumber, WIDTH_CARD_NUMBER);
            return CardDto.detail(transactionName, title01, currentDate, programName, title02,
                    currentTime, renderedAccountId, renderedCardNumber, cardholderName,
                    cardStatusCode, expiryMonth, expiryYear,
                    padRight(informationMessage, WIDTH_INFORMATION_MESSAGE),
                    padRight(errorMessage, WIDTH_ERROR_MESSAGE), null);
        }

        /**
         * Collects the presentation decisions this buffer holds.
         *
         * @param context the per-request state, consulted for nothing beyond confirming that the buffer
         *                belongs to it, which keeps the two rendering entry points symmetrical
         * @return the attributes, never {@code null}
         */
        private ScreenAttributes attributes(final UpdateContext context) {
            return new ScreenAttributes(accountIdAttribute, cardNumberAttribute,
                    cardholderNameAttribute, cardStatusAttribute, expiryMonthAttribute,
                    expiryYearAttribute, informationMessageAttribute, functionKeysAttribute,
                    accountIdColour, cardNumberColour, cardholderNameColour, cardStatusColour,
                    expiryDayColour, expiryMonthColour, expiryYearColour,
                    context.screen == this ? cursorField : null);
        }

        /**
         * Reproduces {@code :1087-1097}: a zero filter renders as an empty field rather than as a run of
         * zeroes, because {@code :1088} and {@code :1094} move {@code LOW-VALUES}.
         *
         * @param value the validated numeric filter, possibly {@code null}
         * @param width the declared field width
         * @return the rendered field, or {@code null} when the filter is absent or zero
         */
        private static String renderFilter(final Long value, final int width) {
            return value == null || value == 0L
                    ? null
                    : padLeftZeroes(Long.toString(value), width);
        }
    }

    /**
     * Every WORKING-STORAGE item this program declares, held for the lifetime of one request and no
     * longer.
     *
     * <p>This is the mechanism that satisfies Rule 1 Clause B's prohibition on global mutable state.
     * {@code COCRDUPC} keeps its flags, its state byte, its two snapshot groups and its record work area
     * in WORKING-STORAGE, which in CICS is per-task; on a Spring singleton the equivalent would have to
     * be a bean field, and a bean field would be an outright concurrency defect. Every one of those items
     * therefore lives here, one instance per request, and <strong>this class holds no static mutable
     * state whatsoever</strong>.</p>
     *
     * <p>The COMMAREA-carried state byte is reconstructed from the request in the constructor, because a
     * stateless request carries no COMMAREA. That reconstruction is the whole of the Rule 7 substitution
     * for {@code :385-401}: a first entry is {@code CCUP-DETAILS-NOT-FETCHED} as {@code :394} sets; a
     * continuation with no usable snapshot is the same state, since {@code :278-280} treats
     * {@code LOW-VALUES} and {@code SPACES} alike; a continuation carrying a snapshot and the
     * confirm-and-save intent is {@code CCUP-CHANGES-OK-NOT-CONFIRMED}, which is the only state under
     * which {@code :417} accepts {@code PF05} and {@code :988} reaches the write; and any other
     * continuation carrying a snapshot is {@code CCUP-SHOW-DETAILS}, the state in which {@code :698-708}
     * runs the four field edits.</p>
     */
    private static final class UpdateContext {

        /** The received map plus both snapshot groups. Never mutated. */
        private final CardUpdateRequest request;

        /** {@code EIBAID} as the caller supplied it, mapped by {@code YYYY-STORE-PFKEY} at {@code :406}. */
        private final String rawAttentionIdentifier;

        /** {@code CDEMO-PGM-CONTEXT}, as tested at {@code :387} and {@code :1084}. */
        private final EntryMode entryMode;

        /** {@code WS-TRANID}, set at {@code :380}. */
        private String transactionId;

        /**
         * {@code WS-RETURN-MSG PIC X(75)} at {@code :173}; empty stands for
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code :174}, which is the guard that every
         * "first message wins" test in the program reads.
         */
        private String returnMessage = "";

        /** {@code WS-INFO-MSG PIC X(40)}; empty stands for {@code WS-NO-INFO-MESSAGE}. */
        private String informationMessage = "";

        /** {@code CCARD-ERROR-MSG}, set from {@code WS-RETURN-MSG} at {@code :547} and {@code :568}. */
        private String errorMessage = "";

        /** {@code CDEMO-PGM-ENTER} true, {@code CDEMO-PGM-REENTER} false. */
        private boolean programContextEnter;

        /** {@code CCUP-CHANGE-ACTION}, the state byte declared at {@code :276}. */
        private ChangeAction changeAction;

        /** {@code CCARD-AID-*}, the mapped attention identifier. */
        private AidKey attentionKey = AidKey.ENTER;

        /** {@code PFK-VALID} / {@code PFK-INVALID}, set at {@code :413} and {@code :421}. */
        private boolean pfKeyValid;

        /** {@code INPUT-ERROR} / {@code INPUT-OK}, the program's global rejection latch. */
        private boolean inputError;

        /** {@code NO-CHANGES-DETECTED}, set by Regime A at {@code :681}. */
        private boolean noChangesDetected;

        /**
         * The absence of {@code FOUND-CARDS-FOR-ACCOUNT} after {@code 9100}, i.e. the
         * {@code WHEN DFHRESP(NOTFND)} arm at {@code :1395-1401} fired.
         *
         * <p>Held as its own flag rather than inferred from the message text, because {@code :1399}'s
         * guard means the message is only stamped when none was already set, so a missing row cannot be
         * recognised from {@code returnMessage} alone.</p>
         */
        private boolean cardAbsent;

        /** {@code FLG-ACCTFILTER-*}, set by {@code 1210} and by {@code 9100}'s {@code NOTFND} arm. */
        private FieldEditState accountFilterState = FieldEditState.NOT_OK;

        /** {@code FLG-CARDFILTER-*}, set by {@code 1220} and by {@code 9100}'s {@code NOTFND} arm. */
        private FieldEditState cardFilterState = FieldEditState.NOT_OK;

        /** {@code FLG-CARDNAME-*}, set by {@code 1230}. */
        private FieldEditState cardNameState = FieldEditState.NOT_OK;

        /** {@code FLG-CARDSTATUS-*}, set by {@code 1240}. */
        private FieldEditState cardStatusState = FieldEditState.NOT_OK;

        /** {@code FLG-CARDEXPMON-*}, set by {@code 1250}. */
        private FieldEditState expiryMonthState = FieldEditState.NOT_OK;

        /** {@code FLG-CARDEXPYEAR-*}, set by {@code 1260}. */
        private FieldEditState expiryYearState = FieldEditState.NOT_OK;

        /** The outcome of {@code 9200}, switched on at {@code :992-1001}. */
        private WriteOutcome writeOutcome = WriteOutcome.NOT_ATTEMPTED;

        /**
         * A typed failure raised deep in the paragraph chain and rethrown by the public entry points, so
         * that a {@code GO TO} out of an edit paragraph never has to be modelled as an exception in the
         * middle of the transcription. Always a {@code com.cardemo.exception} subtype, never swallowed.
         */
        private CardDemoException pendingFailure;

        /** {@code CDEMO-LAST-MAPSET}, tested at {@code :432}, {@code :450} and {@code :1232}. */
        private String lastMapset;

        /** {@code CDEMO-LAST-MAP}, set at {@code :457}. */
        private String lastMap;

        /** {@code CDEMO-FROM-PROGRAM}, tested at {@code :477}, {@code :494} and set at {@code :449}. */
        private String fromProgram;

        /** {@code CDEMO-FROM-TRANID}, tested at {@code :1013} and set at {@code :448}. */
        private String fromTransactionId;

        /** {@code CCARD-NEXT-PROG}, set at {@code :569}. */
        private String nextProgram;

        /** {@code CCARD-NEXT-MAPSET}, set at {@code :570} and {@code :1325}. */
        private String nextMapset;

        /** {@code CCARD-NEXT-MAP}, set at {@code :571} and {@code :1326}. */
        private String nextMap;

        /** {@code CDEMO-ACCT-ID PIC 9(11)} of {@code app/cpy/COCOM01Y.cpy}. */
        private Long commareaAccountId;

        /** {@code CDEMO-CARD-NUM PIC 9(16)} of {@code app/cpy/COCOM01Y.cpy}. */
        private Long commareaCardNumber;

        /** {@code CDEMO-ACCT-STATUS}, cleared at {@code :1016}. */
        private String commareaAccountStatus;

        /** {@code CC-ACCT-ID-N PIC 9(11)}, the numeric view of the account filter. */
        private Long receivedAccountId;

        /** {@code CC-CARD-NUM-N PIC 9(16)}, the numeric view of the card filter. */
        private Long receivedCardNumber;

        /** {@code CC-ACCT-ID PIC X(11)}, the raw text view that {@code :740}'s class test reads. */
        private String receivedAccountIdText;

        /** {@code CC-CARD-NUM PIC X(16)}, the raw text view that {@code :784}'s class test reads. */
        private String receivedCardNumberText;

        /**
         * {@code CCUP-NEW-ACCTID PIC X(11)} at {@code :304}, maintained as the second target of the
         * multi-target moves at {@code :592}, {@code :595} and {@code :753}.
         *
         * <p>Cleared to {@code LOW-VALUES} on <strong>both</strong> of {@code 1210}'s failure branches, at
         * {@code :734} and {@code :749}, which is precisely the contrast that makes {@code 1220}'s
         * asymmetry worth recording: the card field uses two different clearing values where this one uses
         * one.</p>
         */
        private String newAccountId;

        /**
         * {@code CCUP-NEW-CARDID PIC X(16)} at {@code :305}. Cleared to <strong>ZEROES</strong> on the
         * blank branch at {@code :777-778} and to <strong>LOW-VALUES</strong> on the not-numeric branch at
         * {@code :793}; the two are never coerced to one another. Read back at {@code :1462}.
         */
        private String newCardId;

        /** {@code CCUP-NEW-CRDNAME PIC X(50)} at {@code :308}. */
        private String newCardholderName;

        /** {@code CCUP-NEW-CRDSTCD PIC X(1)} at {@code :313}. */
        private String newCardStatusCode;

        /**
         * {@code CCUP-NEW-EXPDAY PIC X(2)} at {@code :312}. Accepted unnormalised at {@code :621},
         * validated by no paragraph, written at {@code :1471}, and never echoed.
         */
        private String newExpiryDay;

        /** {@code CCUP-NEW-EXPMON PIC X(2)} at {@code :311}. */
        private String newExpiryMonth;

        /** {@code CCUP-NEW-EXPYEAR PIC X(4)} at {@code :310}. */
        private String newExpiryYear;

        /** {@code CCUP-OLD-ACCTID PIC X(11)} at {@code :292}, seeded at {@code :1346}. */
        private Long oldAccountId;

        /** {@code CCUP-OLD-CARDID PIC X(16)} at {@code :293}, seeded at {@code :1347}. */
        private Long oldCardNumber;

        /** {@code CCUP-OLD-CRDNAME PIC X(50)} at {@code :296}, loaded already folded at {@code :1360}. */
        private String oldCardholderName;

        /** {@code CCUP-OLD-CRDSTCD PIC X(1)} at {@code :301}, loaded at {@code :1367}. */
        private String oldCardStatusCode;

        /** {@code CCUP-OLD-EXPDAY PIC X(2)} at {@code :300}, loaded at {@code :1366}, echoed by {@code 3200}. */
        private String oldExpiryDay;

        /** {@code CCUP-OLD-EXPMON PIC X(2)} at {@code :299}, loaded at {@code :1364}. */
        private String oldExpiryMonth;

        /** {@code CCUP-OLD-EXPYEAR PIC X(4)} at {@code :298}, loaded at {@code :1362}. */
        private String oldExpiryYear;

        /**
         * {@code CARD-EMBOSSED-NAME} of the record work area, pre-loaded at {@code :673} and then
         * replaced by {@code INTO(CARD-RECORD)}, and folded in place at {@code :1356-1358} and
         * {@code :1499-1501}.
         */
        private String editEmbossedName;

        /** {@code CARD-ACTIVE-STATUS} of the record work area, pre-loaded at {@code :674}. */
        private String editCardStatus;

        /** {@code CARD-EXPIRY-DAY}, the {@code (9:2)} view of the work area, pre-loaded at {@code :675}. */
        private String editExpiryDay;

        /** {@code CARD-EXPIRY-MONTH}, the {@code (6:2)} view of the work area, pre-loaded at {@code :676}. */
        private String editExpiryMonth;

        /** {@code CARD-EXPIRY-YEAR}, the {@code (1:4)} view of the work area, pre-loaded at {@code :677}. */
        private String editExpiryYear;

        /** {@code ABEND-CODE PIC X(4)} of {@code app/cpy/CSMSG02Y.cpy}, set at {@code :1021}. */
        private String abendCode;

        /** {@code ABEND-CULPRIT PIC X(8)} of {@code app/cpy/CSMSG02Y.cpy}, set at {@code :1020}, {@code :1537}. */
        private String abendCulprit;

        /** {@code ABEND-REASON PIC X(50)} of {@code app/cpy/CSMSG02Y.cpy}, set at {@code :1022}. */
        private String abendReason;

        /** {@code ABEND-MSG PIC X(72)} of {@code app/cpy/CSMSG02Y.cpy}, set at {@code :1023-1024}. */
        private String abendMessage;

        /** {@code CCRDUPAO}, the symbolic output map, replaced wholesale by {@code :1053}. */
        private ScreenBuffer screen = new ScreenBuffer();

        /**
         * Reproduces {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} at {@code :371-373} and
         * the COMMAREA restore of {@code :385-401}.
         *
         * @param request              the received map plus the new-as-edited snapshot group
         * @param attentionIdentifier  the raw {@code EIBAID}, possibly {@code null}
         * @param entryMode            first entry or continuation
         * @param authenticOldDetails  the as-displayed snapshot this service is entitled to trust: opened
         *                             from the sealed token on the REST write path, and the caller's own
         *                             group on the screen-faithful path where the caller is in-process
         *                             rather than remote. No request member carries the group, so a remote
         *                             caller has no way to influence what the change detection compares
         *                             against
         */
        private UpdateContext(final CardUpdateRequest request, final String attentionIdentifier,
                              final EntryMode entryMode,
                              final CardUpdateRequest.CardDetails authenticOldDetails) {
            this.request = request;
            this.rawAttentionIdentifier = attentionIdentifier;
            this.entryMode = entryMode;
            this.programContextEnter = entryMode == EntryMode.ENTER;
            this.changeAction =
                    resolveInitialAction(authenticOldDetails, attentionIdentifier, entryMode);
            final CardUpdateRequest.CardDetails snapshot = authenticOldDetails;
            final CardUpdateRequest.CardData snapshotData =
                    snapshot == null ? null : snapshot.cardData();
            final CardUpdateRequest.ExpiraionDate snapshotDate =
                    snapshotData == null ? null : snapshotData.expiraionDate();
            // :396-400 MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1 : LENGTH OF
            // WS-THIS-PROGCOMMAREA) TO WS-THIS-PROGCOMMAREA. The restore is WHOLESALE over the
            // program COMMAREA, so it re-establishes every leaf of CCUP-OLD-DETAILS (:291-301) on
            // every re-entered task. The snapshot is the Rule 7 substitution for that COMMAREA half.
            //
            // CCUP-OLD-CVV-CD at :294 is the one leaf with NO counterpart here, and deliberately so.
            // The value IS stored, but it has no read path and no symbolic map declares a field for it,
            // so there is nothing for the caller to send back; CardUpdateRequest.CardDetails declares no
            // cvvCode component. That is a narrowing, not a gap: a request DTO carrying a verification
            // value would mean ACCEPTING authentication data over the wire, which is a retention problem
            // one hop earlier. @Version carries the concurrency question :1503 asked.
            this.oldAccountId = parseDigits(snapshot == null ? null : snapshot.accountId());
            this.oldCardNumber = parseDigits(snapshot == null ? null : snapshot.cardNumber());
            this.oldCardholderName = snapshotData == null ? null : snapshotData.cardholderName();
            this.oldCardStatusCode = snapshotData == null ? null : snapshotData.cardStatusCode();
            this.oldExpiryYear = snapshotDate == null ? null : snapshotDate.expiryYear();
            this.oldExpiryMonth = snapshotDate == null ? null : snapshotDate.expiryMonth();
            this.oldExpiryDay = snapshotDate == null ? null : snapshotDate.expiryDay();
        }

        /**
         * Reconstructs {@code CCUP-CHANGE-ACTION} from the authentic snapshot, standing in for the COMMAREA
         * restore of {@code :385-401}. See the class documentation of {@link UpdateContext} for the four
         * cases and why each is the state the source would hold.
         *
         * @param authenticOldDetails the as-displayed snapshot this service is entitled to trust, never the
         *                            request body's own group
         * @param attentionIdentifier the raw {@code EIBAID}, possibly {@code null}
         * @param entryMode           first entry or continuation
         * @return the initial state, never {@code null}
         */
        private static ChangeAction resolveInitialAction(
                final CardUpdateRequest.CardDetails authenticOldDetails,
                final String attentionIdentifier,
                final EntryMode entryMode) {
            // :393-394 a first entry resets the state machine.
            if (entryMode == EntryMode.ENTER) {
                return ChangeAction.DETAILS_NOT_FETCHED;
            }
            // :278-280 LOW-VALUES and SPACES are the same state, so an absent or empty snapshot means the
            //          details have not been fetched and the write is unreachable. The snapshot
            //          consulted here is the authentic one and never the request body's, for the reason
            //          given on the enclosing constructor.
            if (isSnapshotEmpty(authenticOldDetails)) {
                return ChangeAction.DETAILS_NOT_FETCHED;
            }
            // :417 PF05 is accepted only while the changes are validated but unconfirmed, and :988 is the
            //      only route to the write.
            // The FOLDED key, never the raw identifier: app/cpy/CSSTRPFY.cpy:62-63 makes DFHPF17 the
            // same key as DFHPF5, and the validity gate at :414-422 will compare the folded value.
            // See foldAttentionIdentifier for why comparing the raw text here disables the save.
            if (foldAttentionIdentifier(attentionIdentifier) == AidKey.PFK05) {
                return ChangeAction.CHANGES_OK_NOT_CONFIRMED;
            }
            // :488 and :1012 the ordinary state once a row has been fetched and displayed.
            return ChangeAction.SHOW_DETAILS;
        }

        /**
         * Reproduces {@code INITIALIZE CCUP-NEW-DETAILS} at {@code :586}, which clears the whole
         * new-values group including the card verification value that no statement ever assigns. That
         * clear is why the source's rewrite wrote spaces over the stored value, and why the two
         * {@code MOVE}s at {@code :1464-1465} are deliberately absent - see the class documentation.
         */
        private void clearNewDetails() {
            newAccountId = null;
            newCardId = null;
            newCardholderName = null;
            newCardStatusCode = null;
            newExpiryDay = null;
            newExpiryMonth = null;
            newExpiryYear = null;
        }

        /**
         * Reproduces {@code INITIALIZE WS-THIS-PROGCOMMAREA} at {@code :496} and {@code :504}, which
         * clears the state byte and both snapshot groups.
         */
        private void clearProgramCommarea() {
            changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            clearNewDetails();
            oldAccountId = null;
            oldCardNumber = null;
            oldCardholderName = null;
            oldCardStatusCode = null;
            oldExpiryDay = null;
            oldExpiryMonth = null;
            oldExpiryYear = null;
        }

        /**
         * Reproduces {@code INITIALIZE WS-MISC-STORAGE} at {@code :505}, which clears the flags, the two
         * messages and the record work area but not the state byte.
         */
        private void clearMiscStorage() {
            inputError = false;
            noChangesDetected = false;
            cardAbsent = false;
            returnMessage = "";
            informationMessage = "";
            accountFilterState = FieldEditState.NOT_OK;
            cardFilterState = FieldEditState.NOT_OK;
            cardNameState = FieldEditState.NOT_OK;
            cardStatusState = FieldEditState.NOT_OK;
            expiryMonthState = FieldEditState.NOT_OK;
            expiryYearState = FieldEditState.NOT_OK;
            writeOutcome = WriteOutcome.NOT_ATTEMPTED;
            receivedAccountId = null;
            receivedCardNumber = null;
            receivedAccountIdText = null;
            receivedCardNumberText = null;
            editEmbossedName = null;
            editCardStatus = null;
            editExpiryDay = null;
            editExpiryMonth = null;
            editExpiryYear = null;
        }

        /**
         * Reproduces {@code INTO(CARD-RECORD)} at {@code :1386} and {@code :1432}, which replaces the
         * record work area with the row just read.
         *
         * <p>The three expiry components are extracted through the {@code CICS-OUTPUT-EDIT-VARS}
         * decomposition of {@code CARD-EXPIRAION-DATE-X}: year {@code (1:4)}, filler {@code (5:1)}, month
         * {@code (6:2)}, filler {@code (8:1)}, day {@code (9:2)}. The misspelling of the field name is
         * part of the contract and is not corrected.</p>
         *
         * @param card the row just read
         */
        private void loadRecordWorkArea(final Card card) {
            editEmbossedName = card.getEmbossedName();
            editCardStatus = card.getActiveStatus();
            editExpiryYear = substringOrNull(card.getExpiraionDate(), 1, WIDTH_EXPIRY_YEAR);
            editExpiryMonth = substringOrNull(card.getExpiraionDate(), 6, WIDTH_EXPIRY_MONTH);
            editExpiryDay = substringOrNull(card.getExpiraionDate(), 9, WIDTH_EXPIRY_DAY);
        }

        /**
         * Renders {@code CCUP-OLD-DETAILS} as this pass leaves it, which is what {@code :548-552} would
         * have placed in the returned COMMAREA and what the read entry point seals for the next turn to send
         * back as {@code snapshot}.
         *
         * <p>There is no card verification component, because {@link CardUpdateRequest.CardDetails}
         * declares none. The legacy group carries it at {@code :294} and {@code 9300} compares it at
         * {@code :1503}. The value is stored here - {@code card_cvv_cd CHAR(3) NOT NULL} at
         * the {@code card_cvv_cd} column of {@code V1__create_schema.sql}, mapped on the entity and
         * seeded by {@code V3__seed_data.sql}'s {@code card} insert - but the read path is withheld
         * rather than the column, so it
         * cannot be projected into a snapshot, and no symbolic map declares a field a caller could echo.
         * Exposing a caller-visible component would be a retention problem one hop earlier and a Rule 1
         * Clause D violation; the concurrency question {@code :1503} asked is answered by
         * {@code @Version}.</p>
         *
         * @return the refreshed snapshot, never {@code null}
         */
        private CardUpdateRequest.CardDetails refreshedSnapshot() {
            return new CardUpdateRequest.CardDetails(
                    oldAccountId == null
                            ? null
                            : padLeftZeroes(Long.toString(oldAccountId), WIDTH_ACCOUNT_ID),
                    oldCardNumber == null
                            ? null
                            : padLeftZeroes(Long.toString(oldCardNumber), WIDTH_CARD_NUMBER),
                    new CardUpdateRequest.CardData(oldCardholderName,
                            new CardUpdateRequest.ExpiraionDate(oldExpiryYear, oldExpiryMonth,
                                    oldExpiryDay),
                            oldCardStatusCode));
        }

        /**
         * Renders {@code CCUP-OLD-DETAILS} exactly as {@code 9000-READ-DATA} snapshotted it at
         * {@code :1345-1367} for sealing into the opaque snapshot token.
         *
         * <p>The sealed rendering carries no {@code CCUP-OLD-CVV-CD}, and the reason is a read path rather
         * than an absent column. The value <em>is</em> persisted - {@code V1__create_schema.sql} declares
         * {@code card_cvv_cd CHAR(3) NOT NULL}, {@code com.cardemo.model.entity.Card} maps it and
         * {@code V3__seed_data.sql} seeds it - but it is write-once with no getter of any visibility, so this
         * read has no way to obtain it and no snapshot can be projected from it. No symbolic map declares a
         * CVV field either, so the operator never typed one and no caller could echo one. The first predicate
         * of the change-detection guard at {@code :1503} therefore has no operand on either side and is inert
         * - it can neither fire nor be evaded - and the concurrency question it asked is answered by the
         * {@code @Version} column instead, while the five remaining predicates are reproduced in full from
         * this snapshot. The sealing is what makes those five trustworthy at all: every value here is
         * encrypted and authenticated before it leaves the service, so the caller receives one opaque string,
         * can recover nothing from it, and no snapshot value is ever accepted back as plaintext.</p>
         *
         * <p>The two key components are rendered from the snapshot fields rather than the received text, so
         * that what is sealed is what the read actually found.</p>
         *
         * @return the fetched snapshot including the card verification value, never {@code null}
         */
        private CardUpdateRequest.CardDetails snapshotOfFetchedValues() {
            return new CardUpdateRequest.CardDetails(
                    oldAccountId == null
                            ? null
                            : padLeftZeroes(Long.toString(oldAccountId), WIDTH_ACCOUNT_ID),
                    oldCardNumber == null
                            ? null
                            : padLeftZeroes(Long.toString(oldCardNumber), WIDTH_CARD_NUMBER),
                    new CardUpdateRequest.CardData(oldCardholderName,
                            new CardUpdateRequest.ExpiraionDate(oldExpiryYear, oldExpiryMonth,
                                    oldExpiryDay),
                            oldCardStatusCode));
        }

        /**
         * Renders {@code CCUP-NEW-DETAILS} as {@code 1100-RECEIVE-MAP} and the four field edits leave it,
         * which is the other half of what {@code :548-552} would have returned.
         *
         * <p>Both key components are rendered from the {@code CCUP-NEW-*} fields rather than from the
         * numeric work fields, because those are what {@code :548-552} carries and what the next task's
         * restore at {@code :396-401} reads. That distinction is observable: on {@code 1210}'s and
         * {@code 1220}'s failure branches the {@code CCUP-NEW-*} field is cleared while the received text
         * is not, and {@code 1220} clears to two different values depending on which branch fired.</p>
         *
         * <p>There is no card verification component here either, for the same reason as in
         * {@link #refreshedSnapshot()} - and note that the source never assigns its own copy at all.</p>
         *
         * @return the echoed new-values group, never {@code null}
         */
        private CardUpdateRequest.CardDetails submittedDetails() {
            return new CardUpdateRequest.CardDetails(
                    newAccountId,
                    newCardId,
                    new CardUpdateRequest.CardData(newCardholderName,
                            new CardUpdateRequest.ExpiraionDate(newExpiryYear, newExpiryMonth,
                                    newExpiryDay),
                            newCardStatusCode));
        }

        /**
         * The only form in which a card number may appear in a diagnostic, per Rule 1 Clause D.
         *
         * @return the masked validated card filter, or {@code null} when none was supplied
         */
        private String maskedCardNumber() {
            return receivedCardNumber == null
                    ? maskTail(receivedCardNumberText)
                    : maskTail(padLeftZeroes(Long.toString(receivedCardNumber), WIDTH_CARD_NUMBER));
        }
    }
}
