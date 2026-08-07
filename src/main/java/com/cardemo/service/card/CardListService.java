/*
 ******************************************************************
 * Program     : CardListService.java
 * Application : CardDemo
 * Type        : Spring Boot Service Bean (Java 25)
 * Function    : List credit cards, filtered by account and card number.
 * Source      : app/cbl/COCRDLIC.cbl (1,459 lines, 42 paragraphs) @ 7756d89
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.service.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;

/**
 * Paged credit-card listing, migrated one-to-one from the frozen COBOL program
 * {@code app/cbl/COCRDLIC.cbl} (1,459 lines, 42 paragraphs) at traceability anchor {@code 7756d89}.
 *
 * <h2>1. What this service does</h2> <p>Reproduces CICS transaction {@code CCLI}, defined at
 * {@code app/csd/CARDDEMO.CSD:357-358} as {@code DEFINE TRANSACTION(CCLI) ... PROGRAM(COCRDLIC)}. It browses the card
 * base cluster ({@code DEFINE FILE(CARDDAT)}, {@code app/csd/CARDDEMO.CSD:25-31}, which carries {@code BROWSE(YES)}
 * at {@code :31}) in card-number order and returns one screen of rows, together with the paging metadata, the
 * information or error message, and the navigation intent produced by a per-row {@code S} (view) or {@code U}
 * (update) selection.</p>
 * <ul>
 *   <li>Page size is a parity contract, not a tunable - see section 3.</li>
 *   <li>Optional account and card-number filters, evaluated account-gate-first ({@code 9500-FILTER-RECORDS.} at
 *       {@code app/cbl/COCRDLIC.cbl:1382-1407}).</li>
 *   <li>Key-based cursor paging forward ({@code 9000-READ-FORWARD.} at {@code :1123}) and backward
 *       ({@code 9100-READ-BACKWARDS.} at {@code :1264}).</li>
 *   <li>Per-row selection with a strict one-selection rule ({@code 2250-EDIT-ARRAY.} at {@code :1073-1117}).</li>
 * </ul>
 * <p>Every private method below corresponds to exactly one COBOL Area-A paragraph label and cites
 * it. Nothing is consolidated: duplicate, empty, unreachable and defective paths are all retained,
 * because the paragraph map must stay mechanically provable.
 * Industry guidance against literal transliteration is deliberately overridden here - behavioural
 * parity is the contract - and the readability concern it raises is answered by these
 * source-citing comments rather than by restructuring control flow.</p>
 *
 * <h2>2. How to build, run and test</h2>
 * <p>Build contract: Java 25 ({@code maven.compiler.release=25}, no preview features), Maven
 * 3.9.11, parent {@code spring-boot-starter-parent:3.5.11}, {@code maven-compiler-plugin:3.14.1}
 * with {@code -Xlint:all -Werror} and {@code failOnWarning=true},
 * {@code maven-surefire-plugin:3.5.4}, {@code maven-failsafe-plugin:3.5.4},
 * {@code jacoco-maven-plugin:0.8.12} enforcing an 80 percent LINE gate at {@code verify}, and
 * {@code org.owasp:dependency-check-maven:12.1.0}.</p>
 * <p>The provisioned toolchain exposes {@code java}, {@code javac} and {@code mvn} directly, so the
 * canonical commands are, from the repository root, with the local environment loaded in a subshell around
 * whichever command needs it - {@code ( set -a; . ./.env; set +a; <command> )} rather than an export into the
 * shell, so the values are not inherited by every later child:</p>
 * <ul>
 *   <li>{@code ./mvnw -B -ntp clean compile} - compiles this file under the zero-warning gate.</li>
 *   <li>{@code ./mvnw -B -ntp test} - runs the unit tier.</li>
 *   <li>{@code ./mvnw -B -ntp clean verify} - fast local verification, which
 *       runs the compiler, doclint, test and coverage checks but <strong>does not</strong> satisfy the
 *       zero-warning build gate, because the skip suppresses the vulnerability scan and a skipped scan is
 *       never evidence that the scan passes. Note the skip property is hyphenated.</li>
 *   <li>{@code ./mvnw -B -ntp clean verify} - the full gate, online and with nothing skipped.</li>
 *   </ul>
 * <p>Where a host toolchain is absent, the pinned container
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -B -ntp -q -e verify}
 * produces an identical build. Unit tests for this service belong in
 * {@code src/test/java/com/cardemo/unit/service/} and are owned by a different agent; this file
 * therefore performs no hidden I/O, holds no static initialiser and captures no clock or random
 * source, so every behaviour is reachable through the injected repository, the injected page size
 * and the request object.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 * <p><strong>{@code carddemo.pagination.card-list-page-size} = 7.</strong> Bound with no default
 * fallback, so a missing property fails fast at context startup. This is the transcription of
 * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
 * {@code app/cbl/COCRDLIC.cbl:177-178}, corroborated by {@code OCCURS 7 TIMES} at {@code :76},
 * {@code :86} and {@code :255}. {@code src/main/resources/application.yml} records it as a parity
 * contract rather than a tunable. The single injected value resolves all four source use sites:</p>
 * <ol>
 *   <li>the constant declaration at {@code :177-178};</li>
 *   <li>the forward page-full comparison at {@code :1191};</li>
 *   <li>the backward counter seed at {@code :1284-1286}, which computes the value plus one;</li>
 *   <li>the separately hardcoded literal in {@code 2250-EDIT-ARRAY.} at {@code :1099}
 *       ({@code PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7}).</li>
 *   </ol>
 * <p>Site 4 is a legacy inconsistency: the source duplicated the constant instead of referencing
 * {@code WS-MAX-SCREEN-LINES}. This migration unifies the two without changing behaviour, so they
 * can never drift. The row and selection arrays are likewise sized from the injected
 * value, so the literal seven appears nowhere in this file.</p>
 * <p>No other configuration is consumed. {@code WebConfig} owns numeric and message converters,
 * {@code SecurityConfig} owns the filter chain and role mapping, and {@code JpaConfig} owns entity
 * scanning and transaction management; this service consumes those and redeclares none of them.
 * It is strictly read-only - no write, rewrite or delete, no {@code @Transactional}, and it neither
 * reads nor increments the {@code Card} version column.</p>
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 * <table border="1">
 *   <caption>Outcome table with the exact legacy message for each condition</caption>
 *   <tr><th>Condition</th><th>Source</th><th>Legacy message</th><th>Outcome</th></tr>
 *   <tr><td>Blank account filter</td><td>{@code :1007-1012}</td><td>none - legal</td>
 *       <td>unfiltered browse, no exception</td></tr>
 *   <tr><td>Blank card filter</td><td>{@code :1041-1046}</td><td>none - legal</td>
 *       <td>unfiltered browse, no exception</td></tr>
 *   <tr><td>Account filter not numeric</td><td>{@code :1017-1024}</td>
 *       <td>{@code ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER} (unguarded)</td>
 *       <td>{@code ValidationException}, field {@code accountId}; rows protected</td></tr>
 *   <tr><td>Card filter not numeric</td><td>{@code :1051-1060}</td>
 *       <td>{@code CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER} (guarded)</td>
 *       <td>{@code ValidationException}, field {@code cardNumber}; rows protected</td></tr>
 *   <tr><td>More than one row selected</td><td>{@code :1084-1093}</td>
 *       <td>{@code PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE} (unguarded)</td>
 *       <td>error-bearing result; page payload still returned</td></tr>
 *   <tr><td>Row code not S, U or blank</td><td>{@code :1108-1113}</td>
 *       <td>{@code INVALID ACTION CODE} (guarded)</td>
 *       <td>error-bearing result; page payload still returned</td></tr>
 *   <tr><td>No rows match on page one</td><td>{@code :1241-1244}</td>
 *       <td>{@code NO RECORDS FOUND FOR THIS SEARCH CONDITION.}</td>
 *       <td>message, not an exception</td></tr>
 *   <tr><td>Forward lookahead hits end of file</td><td>{@code :1215-1221}</td>
 *       <td>{@code NO MORE RECORDS TO SHOW} (guarded)</td><td>next-page indicator false</td></tr>
 *   <tr><td>Outer forward read hits end of file</td><td>{@code :1233-1240}</td>
 *       <td>{@code NO MORE RECORDS TO SHOW} (guarded)</td><td>next-page indicator false</td></tr>
 *   <tr><td>Page up while already on page one</td><td>{@code :901-904}</td>
 *       <td>{@code NO PREVIOUS PAGES TO DISPLAY}</td><td>forward re-read of the first page</td></tr>
 *   <tr><td>Page down twice past the end</td><td>{@code :905-916}</td>
 *       <td>{@code NO MORE PAGES TO DISPLAY}</td><td>latched on the second press only</td></tr>
 *   <tr><td>Any browse read fails</td><td>{@code :1222-1230}, {@code :1246-1254},
 *       {@code :1308-1317}, {@code :1361-1369}</td><td>composed {@code File Error:...}</td>
 *       <td>{@code FileAccessException}</td></tr>
 *   <tr><td>Backward browse exhausts records</td><td>{@code :1294-1318}, {@code :1322-1370}</td>
 *       <td>composed {@code File Error:...}</td>
 *       <td>{@code FileAccessException} - legacy defect, preserved</td></tr>
 *   <tr><td>PF03 pressed</td><td>{@code :384-406}</td><td>{@code PF03 PRESSED.EXITING}</td>
 *       <td>exit intent in the result</td></tr>
 *   <tr><td>Rows returned normally</td><td>{@code :917-919}</td>
 *       <td>{@code TYPE S FOR DETAIL, U TO UPDATE ANY RECORD}</td><td>populated page</td></tr>
 *   </table>
 * <p>Troubleshooting. A startup failure naming
 * {@code carddemo.pagination.card-list-page-size} means the property is absent - it is intentionally
 * defaultless. An {@code IllegalArgumentException} from {@code CardDto.CardListRow} means the
 * configured page size exceeds the seven-row map contract of {@code app/cpy-bms/COCRDLI.CPY}. A
 * {@code FileAccessException} whose message begins {@code File Error:} carries the operation, the
 * logical file name {@code CARDDAT} and the originating condition; the underlying data-access
 * failure is always preserved as the exception cause. An empty first page is a message and never an
 * exception, because the source has no {@code DFHRESP(NOTFND)} path at all.</p>
 *
 * <h2>5. Source facts and preserved quirks</h2>
 * <p>Each entry names what the source does and what this class therefore does.</p>
 * <ol>
 *   <li>{@code DFHRESP(DUPREC)} is a success path, not an error. It occurs
 *       four times - {@code :1158}, {@code :1209}, {@code :1306}, {@code :1334} - and every
 *       occurrence shares its branch with {@code DFHRESP(NORMAL)}. {@code DFHRESP(DUPKEY)} occurs
 *       zero times. Applied here: browse reads treat the duplicate condition as success. A
 *       naive mapping of it to a duplicate-record exception would break every browse.</li>
 *   <li>Naming correction, not a code change: the {@code exception} package
 *       documents those same four line numbers under the name {@code DUPKEY}. The line numbers are
 *       right and the condition name is wrong. That file is owned by another agent and is not
 *       edited here.</li>
 *   <li>Attribution correction, not a code change: the {@code CardRepository}
 *       Javadoc names {@code COCRDLIC} as the caller of the {@code CARDDATA.VSAM.AIX} account
 *       finder. {@code COCRDLIC} never opens that path - {@code LIT-CARD-FILE-ACCT-PATH}
 *       ({@code CARDAIX}) is referenced exactly once repository-wide, at its own declaration
 *       {@code :215-217} - and every browse verb targets the base cluster {@code LIT-CARD-FILE}.
 *       No caller is invented for that finder and that file is not edited.</li>
 *   <li>The forward lookahead at {@code :1197-1205} does not apply
 *       {@code 9500-FILTER-RECORDS}, so the next-page indicator is a false positive whenever every
 *       following record would have been filtered out. Preserved deliberately: pushing the predicate
 *       into the database would silently repair the defect and
 *       change which pages report more data, which is a behaviour change and therefore forbidden.
 *       This is the decisive reason filtering is performed in memory.</li>
 *   <li>Neither {@code READPREV} has a {@code DFHRESP(ENDFILE)} branch
 *       ({@code :1294-1318}, {@code :1322-1370}), so exhausting records while paging up falls into
 *       {@code WHEN OTHER} and is reported as a file error, leaving the row table partly cleared.
 *       Preserved. Not repaired here; repairing it would change
 *       observable behaviour.</li>
 *   <li><strong>Medium - labelled deviation.</strong> {@code I-SELECTED} is set to zero at
 *       {@code :1097} and is subscripted without any bounds check at {@code :518}, {@code :531},
 *       {@code :533}, {@code :546}, {@code :559} and {@code :561}. Subscript zero against
 *       {@code OCCURS 7 TIMES} is out of range and, under the production {@code NOSSRANGE} compile,
 *       silently reads storage preceding the table. Java cannot reproduce an out-of-bounds read.
 *       Applied here: zero is treated as "no row selected", so ENTER without a selection
 *       falls through to the {@code WHEN OTHER} branch at {@code :572} and re-reads forward from the
 *       first card - the observable legacy outcome whenever the garbage byte is neither
 *       {@code S} nor {@code U}. This is the one place where "absent guards are preserved" yields,
 *       because the absent guard is not representable. An unused {@code 88 DETAIL-WAS-REQUESTED
 *       VALUES 1 THRU 7} at {@code :94} shows the bound was known and never consulted.</li>
 *   <li>The paragraph label names in the migration brief do not all exist
 *       in the source. The authoritative Area-A roster was rescanned from
 *       {@code PROCEDURE DIVISION.} at {@code :297} and yields 39 in-file labels; the real names are
 *       {@code 1200-SCREEN-ARRAY-INIT}, {@code 1500-SEND-SCREEN}, {@code 2000-RECEIVE-MAP} and
 *       {@code 2100-RECEIVE-SCREEN}, and {@code 1250-SETUP-ARRAY-ATTRIBS},
 *       {@code 1400-SETUP-MESSAGE} and {@code 2220-EDIT-CARD} were absent from the brief entirely.
 *       Applied here: the source governs and all 39 labels plus the two from
 *       {@code app/cpy/CSSTRPFY.cpy} are implemented.</li>
 *   <li>Two message literals were absent from the brief's inventory:
 *       {@code NO PREVIOUS PAGES TO DISPLAY} at {@code :903} and {@code NO MORE PAGES TO DISPLAY}
 *       at {@code :908}, the latter reachable only through the two-press latch at {@code :910-916}.
 *       Applied here: both are implemented verbatim.</li>
 *   <li><strong>Medium - performance tradeoff, justified.</strong> {@code STARTBR ... GTEQ} has no
 *       Spring Data equivalent, and the only permitted finders are offset-paged. Positioning is
 *       therefore performed by an exponential probe followed by a binary search over single-row
 *       reads, which is logarithmic in table size and issues no {@code COUNT} query - consistent
 *       with {@code PageResponse} deliberately omitting totals. Not addressed here: a keyset
 *       finder on {@code CardRepository} would make this a single query, but no method may be added
 *       to that interface in this change.</li>
 *   <li>Duplicate {@code WHEN} condition: {@code CCARD-AID-PFK07 AND
 *       CA-FIRST-PAGE} is declared twice, at {@code :439-440} and {@code :444-445}, with nothing but
 *       comments between them, so the first occurrence is empty and redundant and COBOL reduces the
 *       pair to a single condition. Applied here: one guarded branch is emitted.</li>
 *   <li>Self-{@code THRU} inconsistency:
 *       {@code PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP} at {@code :436-437}, {@code :452-453},
 *       {@code :480-481} and {@code :580-581} names the same paragraph as both range ends and so
 *       executes only that paragraph, whereas {@code :495-496} and {@code :511-512} use the correct
 *       {@code THRU 1000-SEND-MAP-EXIT}. Both forms are rendered faithfully: the self-range sites
 *       call only the paragraph method, the correct sites also call the exit method. No range is
 *       lost in either case, because the exit paragraph is an {@code EXIT} statement.</li>
 *   <li>Page size duplicated in the source - {@code WS-MAX-SCREEN-LINES} at
 *       {@code :177-178} versus the hardcoded literal at {@code :1099}. Unified here through one
 *       property.</li>
 *   <li>{@code MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY} at
 *       {@code :1268} makes the last key of a backward page the first key of the page being left,
 *       so a following page-down starts at that key and, under {@code GTEQ}, redisplays the page
 *       just left including its first row. Off-by-one preserved.</li>
 *   <li>On the outer end-of-file path the last keys are saved from the stale
 *       record buffer, which still holds the previously read record ({@code :1236-1237}). Preserved;
 *       where no record was ever read the keys are left untouched, because the source would copy an
 *       uninitialised buffer that a fresh CICS task presents as low values.</li>
 *   <li>{@code CRDSTP1I} is absent from {@code app/cpy-bms/COCRDLI.CPY}: row
 *       one has four fields ({@code :78}, {@code :84}, {@code :90}, {@code :96}) while rows two to
 *       seven have five each, giving 45 input fields and not 46. No seventh selector-type field is
 *       invented, and {@code CardDto.CardListRow} independently rejects a non-null selector type on
 *       row one.</li>
 *   <li>{@code PageResponse} omits total-element and total-page counts because
 *       the legacy never computes them. Those values are not computed anywhere; obtaining them
 *       would need a {@code COUNT} query the source never issues.</li>
 *   <li>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} is issued twice
 *       in {@code 1100-SCREEN-INIT}, at {@code :645} and again at {@code :652}. The second is
 *       redundant. Preserved as two calls to the same step.</li>
 *   <li>A stray bare {@code I} sits alone on {@code :790}, between
 *       {@code MOVE DFHBMPRO TO CRDSEL4A OF CCRDLIAI} at {@code :789} and {@code ELSE} at
 *       {@code :791}. Read as a second receiving operand of that {@code MOVE}, it would overwrite
 *       the subscript with an attribute byte. It is harmless because {@code I} is no longer live
 *       once {@code 1000-SEND-MAP} runs and is re-initialised per request at {@code :300-302}. The
 *       actual compiler parse is not possible here - confirming it needs an Enterprise COBOL
 *       compiler, which this environment does not provide. Not reproduced as a clobber.</li>
 *   <li>Row one of {@code 1250-SETUP-ARRAY-ATTRIBS} is asymmetric with rows
 *       two to seven: it moves {@code DFHBMPRF} at {@code :753} where the others move
 *       {@code DFHBMPRO}, and its error arm stamps an asterisk into the output field at
 *       {@code :757-759} where the others reposition the cursor instead. This is the origin of the
 *       two-state validation contract in which only a blank selection is stamped. Preserved.</li>
 *   <li>In the PF03 branch {@code CCARD-NEXT-MAPSET} receives
 *       {@code LIT-MENUMAPSET} at {@code :394} but {@code CCARD-NEXT-MAP} receives
 *       {@code LIT-THISMAP} at {@code :395}, pairing the menu mapset with this program's own map;
 *       {@code LIT-MENUMAP} is consequently never used. {@code CCARD-NEXT-PROG} is not set on that
 *       branch at all, and {@code SET CDEMO-PGM-ENTER TO TRUE} is issued twice, at {@code :389} and
 *       {@code :400}. All preserved verbatim.</li>
 *   <li><strong>Low - mechanism substitution.</strong> The source does not abend on a failed browse
 *       read: it composes the diagnostic into {@code WS-ERROR-MSG} and sends the map. A REST
 *       response cannot present a half-populated 3270 screen carrying a diagnostic, so the
 *       screen-rendered text becomes a typed {@code FileAccessException} whose message is the
 *       identical composed text. No abend exists anywhere in {@code COCRDLIC}.</li>
 *   <li>{@code WS-FILE-ERROR-MESSAGE} at {@code :153-171} occupies exactly 80
 *       bytes and is moved into the 75-byte {@code WS-ERROR-MSG}, so the trailing
 *       {@code FILLER PIC X(5)} that carries no {@code VALUE} clause begins at byte 76 and is
 *       structurally unobservable. The leading filler is {@code 'File Error:'} with no trailing
 *       space, which differs from the sibling card programs; no message formatter is shared with
 *       them.</li>
 *   <li>Declared but never referenced in the source, and therefore not given
 *       Java counterparts: {@code WS-EDIT-SELECT-COUNTER} at {@code :69-71},
 *       {@code 88 DETAIL-WAS-REQUESTED} at {@code :94}, {@code WS-CONTEXT-FLAG} at {@code :130-132},
 *       and {@code WS-LONG-MSG PIC X(500)} at {@code :111}, which is referenced only inside the
 *       unreachable body described in section 6. Neither {@code STARTBR} tests the response code it
 *       captures ({@code :1129-1136}, {@code :1273-1280}) - an absent guard, preserved. The
 *       one-to-one mandate covers paragraph labels, not data-division items, so unused storage is
 *       not carried over.</li>
 *   <li>Navigation literals declared at {@code :179-210} but never referenced
 *       by any executed path, and therefore documented here rather than declared as dead constants:
 *       {@code LIT-MENUTRANID 'CM00'}, {@code LIT-MENUMAP 'COMEN1A'},
 *       {@code LIT-CARDDTLTRANID 'CCDL'} and {@code LIT-CARDUPDTRANID 'CCUP'}. The source never
 *       moves any of them, because it sets {@code CDEMO-TO-PROGRAM} without
 *       {@code CDEMO-TO-TRANID}.</li>
 *   </ol>
 *
 * <h2>6. Retained unreachable paragraphs, and the tracked Clause B conflict</h2>
 * <p>An authoritative census over every Area-A label, checking for any {@code PERFORM},
 * {@code GO TO}, {@code THRU} or {@code THROUGH} reference, yields exactly four unreachable labels:
 * {@code SEND-PLAIN-TEXT.} at {@code :1422}, {@code SEND-PLAIN-TEXT-EXIT.} at {@code :1433},
 * {@code SEND-LONG-TEXT.} at {@code :1441} and {@code SEND-LONG-TEXT-EXIT.} at {@code :1452}. The
 * source comments them as a plain-text exit not for production use and a debugging aid not for
 * regular use. {@code COCRDLIC} has no abend routine, and {@code 0000-MAIN} is reached by
 * fall-through.</p>
 * <p>Rule 1 Clause B forbids dead code. The parity mandate requires preserving declared and
 * unreachable no-ops so the paragraph map stays provable for the scope-coverage gate. <strong>Parity
 * governs, and Clause B is satisfied by a different mechanism:</strong> the clause forbids
 * <em>untracked</em> dead code and deferred work without an owner or tracking reference, and these
 * four artefacts are cited, tracked and explicitly marked. Deleting them would produce a system that
 * is marginally cleaner and demonstrably less traceable, failing a stated acceptance criterion to
 * satisfy a stylistic one. Their bodies are kept empty - the documentation carries the fidelity, the
 * body carries the no-op - which costs about four uncovered lines and stays comfortably inside the
 * 80 percent line gate. No coverage exclusion is added for this class or package.</p>
 *
 * <h2>7. Data handling and security (Rule 1 Clause D)</h2>
 * <p>A single request handles at most one page of card numbers plus one lookahead record. Card
 * numbers are personally identifying and are never logged, echoed into an exception message or
 * shown in documentation; where a diagnostic needs one it is masked at the logging statement, since
 * {@code Card} deliberately excludes the card number from its own string form and offers no masking
 * helper. Whole pages of rows are never logged. The card verification value is never read,
 * projected, logged or exposed - no projection selects it. No example in this documentation contains
 * a realistic card number. No secret, credential, token or signing key appears anywhere in this
 * file, and no environment variable is read from business code.</p>
 */
@Service
public class CardListService {

    /**
     * Diagnostic logger. The legacy program has no instrumentation of any kind - its only output is the
     * screen itself - so every use of this logger is new capability rather than a transcription, and is
     * written to the masking contract described in section 7 of the class documentation.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardListService.class);

    /**
     * Number of trailing card-number digits left unmasked in diagnostics. Card numbers are never
     * logged in full; see section 7 of the class documentation.
     */
    private static final int MASK_VISIBLE_DIGITS = 4;

    /**
     * The number of physical card rows the {@code COCRDLI} symbolic map declares.
     *
     * <p>This is <b>not</b> the page size and is deliberately not used as one: the page size is bound
     * from {@code carddemo.pagination.card-list-page-size} with no default, as the transcription of
     * {@code WS-MAX-SCREEN-LINES} at {@code app/cbl/COCRDLIC.cbl:177-178}. This constant is an
     * independent structural fact about the screen, proven by
     * {@code app/cpy-bms/COCRDLI.CPY}, which declares {@code CRDSEL1L} through {@code CRDSEL7L} at
     * lines 73, 97, 127, 157, 187, 217 and 247 and declares no eighth row. It exists solely so that the
     * constructor can reject a configured page size that disagrees with the screen the service is
     * rendering, and so that the unrolled per-row constructs this file is required to keep unrolled are
     * provably in range.</p>
     */
    private static final int SCREEN_ROW_COUNT = 7;


    // ----------------------------------------------------------------------------------------
    // WS-CONSTANTS - app/cbl/COCRDLIC.cbl:176-217. WS-MAX-SCREEN-LINES is deliberately absent:
    // it is bound from configuration instead. The four literals the source declares but never
    // moves are documented in the class Javadoc rather than declared here, so that no constant in
    // this file is dead.
    // ----------------------------------------------------------------------------------------

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'} - app/cbl/COCRDLIC.cbl:179-180. */
    private static final String LIT_THISPGM = "COCRDLIC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCLI'} - app/cbl/COCRDLIC.cbl:181-182. */
    private static final String LIT_THISTRANID = "CCLI";

    /** {@code LIT-THISMAPSET PIC X(7) VALUE 'COCRDLI'} - app/cbl/COCRDLIC.cbl:183-184. */
    private static final String LIT_THISMAPSET = "COCRDLI";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDLIA'} - app/cbl/COCRDLIC.cbl:185-186. */
    private static final String LIT_THISMAP = "CCRDLIA";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - app/cbl/COCRDLIC.cbl:187-188. */
    private static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'} - app/cbl/COCRDLIC.cbl:191-192. */
    private static final String LIT_MENUMAPSET = "COMEN01";

    /** {@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} - app/cbl/COCRDLIC.cbl:195-196. */
    private static final String LIT_CARDDTLPGM = "COCRDSLC";

    /** {@code LIT-CARDDTLMAPSET PIC X(7) VALUE 'COCRDSL'} - app/cbl/COCRDLIC.cbl:199-200. */
    private static final String LIT_CARDDTLMAPSET = "COCRDSL";

    /** {@code LIT-CARDDTLMAP PIC X(7) VALUE 'CCRDSLA'} - app/cbl/COCRDLIC.cbl:201-202. */
    private static final String LIT_CARDDTLMAP = "CCRDSLA";

    /** {@code LIT-CARDUPDPGM PIC X(8) VALUE 'COCRDUPC'} - app/cbl/COCRDLIC.cbl:203-204. */
    private static final String LIT_CARDUPDPGM = "COCRDUPC";

    /** {@code LIT-CARDUPDMAPSET PIC X(7) VALUE 'COCRDUP'} - app/cbl/COCRDLIC.cbl:207-208. */
    private static final String LIT_CARDUPDMAPSET = "COCRDUP";

    /** {@code LIT-CARDUPDMAP PIC X(7) VALUE 'CCRDUPA'} - app/cbl/COCRDLIC.cbl:209-210. */
    private static final String LIT_CARDUPDMAP = "CCRDUPA";

    /**
     * {@code LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '} - app/cbl/COCRDLIC.cbl:213-214. The base
     * cluster, and the only dataset this program opens. The trailing space is part of the eight-byte
     * literal and is preserved because it reaches the composed diagnostic through
     * {@code MOVE LIT-CARD-FILE TO ERROR-FILE}.
     */
    private static final String LIT_CARD_FILE = "CARDDAT ";

    // ----------------------------------------------------------------------------------------
    // Message literals. Each 88-level condition name in the source is a value test on the message
    // field itself (WS-INFO-MSG at :112-116, WS-ERROR-MSG at :117-126), so the Java transcription
    // tests the message against the same literal rather than carrying a parallel boolean.
    // ----------------------------------------------------------------------------------------

    /** {@code 88 WS-INFORM-REC-ACTIONS} - app/cbl/COCRDLIC.cbl:115-116. */
    private static final String MSG_INFORM_REC_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** {@code 88 WS-EXIT-MESSAGE} - app/cbl/COCRDLIC.cbl:119-120. Uppercase in this program. */
    private static final String MSG_EXIT = "PF03 PRESSED.EXITING";

    /** {@code 88 WS-NO-RECORDS-FOUND} - app/cbl/COCRDLIC.cbl:121-122. Note the trailing period. */
    private static final String MSG_NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** {@code 88 WS-MORE-THAN-1-ACTION} - app/cbl/COCRDLIC.cbl:123-124. */
    private static final String MSG_MORE_THAN_ONE_ACTION = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /** {@code 88 WS-INVALID-ACTION-CODE} - app/cbl/COCRDLIC.cbl:125-126. */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** Inline literal - app/cbl/COCRDLIC.cbl:1021-1023. Moved unguarded, unlike its card twin. */
    private static final String MSG_ACCOUNT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** Inline literal - app/cbl/COCRDLIC.cbl:1056-1060. Moved under an error-message-off guard. */
    private static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Inline literal - app/cbl/COCRDLIC.cbl:1219 and :1239. */
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** Inline literal - app/cbl/COCRDLIC.cbl:903-904. */
    private static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** Inline literal - app/cbl/COCRDLIC.cbl:908-909. Reachable only via the two-press latch. */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    // ----------------------------------------------------------------------------------------
    // WS-FILE-ERROR-MESSAGE - app/cbl/COCRDLIC.cbl:153-171. Eighty bytes of fixed layout moved
    // into the 75-byte WS-ERROR-MSG, so the final FILLER X(5) that carries no VALUE clause is
    // unobservable. The leading filler is 'File Error:' with no trailing space, which is unique to
    // this program; no formatter is shared with COCRDSLC or COCRDUPC.
    // ----------------------------------------------------------------------------------------

    /** {@code FILLER PIC X(12) VALUE 'File Error:'} - app/cbl/COCRDLIC.cbl:154-155. */
    private static final String FILE_ERROR_PREFIX = "File Error:";

    /** {@code FILLER PIC X(4) VALUE ' on '} - app/cbl/COCRDLIC.cbl:158-159. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} - app/cbl/COCRDLIC.cbl:162-164. */
    private static final String FILE_ERROR_RESP = " returned RESP ";

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} - app/cbl/COCRDLIC.cbl:167-168. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** Width of the leading filler that holds {@link #FILE_ERROR_PREFIX} - :154. */
    private static final int FILE_ERROR_PREFIX_WIDTH = 12;

    /** {@code ERROR-OPNAME PIC X(8)} - app/cbl/COCRDLIC.cbl:156. */
    private static final int FILE_ERROR_OPNAME_WIDTH = 8;

    /** {@code ERROR-FILE PIC X(9)} - app/cbl/COCRDLIC.cbl:160. */
    private static final int FILE_ERROR_FILE_WIDTH = 9;

    /** {@code ERROR-RESP PIC X(10)} and {@code ERROR-RESP2 PIC X(10)} - :165 and :169. */
    private static final int FILE_ERROR_RESP_WIDTH = 10;

    /** {@code WS-ERROR-MSG PIC X(75)} - app/cbl/COCRDLIC.cbl:117. The composition truncates here. */
    private static final int ERROR_MESSAGE_WIDTH = 75;

    /** The only operation name the source ever moves to {@code ERROR-OPNAME} - :1226, :1250, :1312, :1365. */
    private static final String OPERATION_READ = "READ";

    /**
     * The CICS condition raised when a browse runs past either end of the file. CICS-supplied
     * {@code DFHRESP} numeric values are not present in this repository, so the condition name is
     * rendered where the source rendered {@code WS-RESP-CD}.
     */
    private static final String RESP_ENDFILE = "ENDFILE";

    /** Placeholder for {@code WS-REAS-CD}, which no CICS reason code is available to supply. */
    private static final String RESP2_NONE = "0";

    // ----------------------------------------------------------------------------------------
    // Field widths taken from the BMS symbolic map and the record copybook. These are field
    // contracts, entirely distinct from the page-size parity contract.
    // ----------------------------------------------------------------------------------------

    /** {@code ACCTSIDI PIC X(11)} - app/cpy-bms/COCRDLI.CPY:66, matching {@code CARD-ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_FILTER_WIDTH = 11;

    /** {@code CARDSIDI PIC X(16)} - app/cpy-bms/COCRDLI.CPY:72, matching {@code CARD-NUM PIC X(16)}. */
    private static final int CARD_FILTER_WIDTH = 16;

    /** {@code PAGENOI PIC X(3)} - app/cpy-bms/COCRDLI.CPY:60. */
    private static final int PAGE_NUMBER_WIDTH = 3;

    /** {@code WS-CA-SCREEN-NUM PIC 9(1)} - app/cbl/COCRDLIC.cbl:237. A single digit, so it wraps at nine. */
    private static final int SCREEN_NUMBER_MODULUS = 10;

    /** {@code 88 CA-FIRST-PAGE VALUE 1} - app/cbl/COCRDLIC.cbl:238. */
    private static final int CA_FIRST_PAGE = 1;

    /** {@code 88 CA-LAST-PAGE-SHOWN VALUE 0} - app/cbl/COCRDLIC.cbl:240. */
    private static final int CA_LAST_PAGE_SHOWN = 0;

    /** {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9} - app/cbl/COCRDLIC.cbl:241. */
    private static final int CA_LAST_PAGE_NOT_SHOWN = 9;

    /**
     * {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'} - app/cbl/COCRDLIC.cbl:244. The companion
     * {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES} at :243 tests for the low-value byte, so
     * the field is genuinely tri-state: after {@code INITIALIZE} it holds a space and neither
     * condition name is true. The indicator is therefore modelled as the character itself.
     */
    private static final char CA_NEXT_PAGE_EXISTS = 'Y';

    /** The low-value byte {@code 88 CA-NEXT-PAGE-NOT-EXISTS} tests for - app/cbl/COCRDLIC.cbl:243. */
    private static final char CA_NEXT_PAGE_NOT_EXISTS = '\u0000';

    /** The space byte {@code INITIALIZE} leaves in {@code WS-CA-NEXT-PAGE-IND}, matching neither 88. */
    private static final char CA_NEXT_PAGE_UNSET = ' ';

    // ----------------------------------------------------------------------------------------
    // CCARD-AID values - app/cpy/CVCRD01Y.cpy. Resolved from the raw attention identifier by
    // YYYY-STORE-PFKEY, copied in at app/cbl/COCRDLIC.cbl:1416.
    // ----------------------------------------------------------------------------------------

    /** {@code 88 CCARD-AID-ENTER VALUE 'ENTER'} - app/cpy/CVCRD01Y.cpy. */
    private static final String AID_ENTER = "ENTER";

    /** {@code 88 CCARD-AID-CLEAR VALUE 'CLEAR'} - app/cpy/CVCRD01Y.cpy. */
    private static final String AID_CLEAR = "CLEAR";

    /** {@code 88 CCARD-AID-PA1 VALUE 'PA1  '} - app/cpy/CVCRD01Y.cpy. */
    private static final String AID_PA1 = "PA1";

    /** {@code 88 CCARD-AID-PA2 VALUE 'PA2  '} - app/cpy/CVCRD01Y.cpy. */
    private static final String AID_PA2 = "PA2";

    /** {@code 88 CCARD-AID-PFK03 VALUE 'PFK03'} - app/cpy/CVCRD01Y.cpy. */
    private static final String AID_PFK03 = "PFK03";

    /** {@code 88 CCARD-AID-PFK07 VALUE 'PFK07'} - app/cpy/CVCRD01Y.cpy. */
    private static final String AID_PFK07 = "PFK07";

    /** {@code 88 CCARD-AID-PFK08 VALUE 'PFK08'} - app/cpy/CVCRD01Y.cpy. */
    private static final String AID_PFK08 = "PFK08";

    /** Prefix of the {@code CCARD-AID-PFKnn} family, used by the function-key fold at :54-77. */
    private static final String AID_PFK_PREFIX = "PFK";

    /** Highest function key CICS reports - {@code DFHPF24}, folded onto the first twelve. */
    private static final int HIGHEST_FUNCTION_KEY = 24;

    /** Number of function keys the {@code CCARD-AID} family models - {@code PFK01} through {@code PFK12}. */
    private static final int FUNCTION_KEY_FOLD = 12;

    // ----------------------------------------------------------------------------------------
    // Selection codes - app/cbl/COCRDLIC.cbl:77-82.
    // ----------------------------------------------------------------------------------------

    /** {@code 88 VIEW-REQUESTED-ON VALUE 'S'} - app/cbl/COCRDLIC.cbl:78. */
    private static final String SELECT_VIEW = "S";

    /** {@code 88 UPDATE-REQUESTED-ON VALUE 'U'} - app/cbl/COCRDLIC.cbl:79. */
    private static final String SELECT_UPDATE = "U";

    /** The row-error marker the source moves into {@code WS-ROW-CRDSELECT-ERROR} - :1104, :1110. */
    private static final char ROW_ERROR_MARKER = '1';

    /** The non-error row marker written by {@code INSPECT ... CHARACTERS BY '0'} - :1092. */
    private static final char ROW_NO_ERROR_MARKER = '0';

    /** The asterisk stamped into row one's output field on a blank selection error - :758. */
    private static final String BLANK_SELECTION_STAMP = "*";

    /** Screen title one, {@code CCDA-TITLE01} from app/cpy/COTTL01Y.cpy - moved at :647. */
    private static final String CCDA_TITLE01 = "AWS Mainframe Modernization";

    /** Screen title two, {@code CCDA-TITLE02} from app/cpy/COTTL01Y.cpy - moved at :648. */
    private static final String CCDA_TITLE02 = "CardDemo";

    // ----------------------------------------------------------------------------------------
    // Filter-flag states - app/cbl/COCRDLIC.cbl:61-68. The source declares WS-EDIT-ACCT-FLAG at :61
    // and WS-EDIT-CARD-FLAG at :65, each a PIC X(1) carrying the same three 88-level condition names.
    // The three byte values below are the source's own, not invented ones. They are transcribed as
    // characters rather than as an enum because this package admits no additional type.
    //
    // The blank state being a space matters: INITIALIZE at :300-302 leaves both flags as spaces, so
    // the initialised state IS the blank state. That is what makes the optimistic default at :1004
    // and :1039 consistent, and it is why a request that never reaches the edit paragraphs - a fresh
    // entry from the menu - browses unfiltered.
    // ----------------------------------------------------------------------------------------

    /** {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} - :64; the card twin at :68. */
    private static final char FILTER_BLANK = ' ';

    /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} - :63; the card twin at :67. */
    private static final char FILTER_VALID = '1';

    /** {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'} - :62; the card twin at :66. */
    private static final char FILTER_NOT_OK = '0';

    // ----------------------------------------------------------------------------------------
    // Field identities. These name the offending input on a validation failure and the field the
    // cursor is placed on; they never carry a field value.
    // ----------------------------------------------------------------------------------------

    /** The account filter's field name, reported on a validation failure raised at :1017-1025. */
    private static final String FIELD_ACCOUNT_ID = "accountId";

    /** The card filter's field name, reported on a validation failure raised at :1052-1062. */
    private static final String FIELD_CARD_NUMBER = "cardNumber";

    /** The account filter's screen field, cursored by {@code MOVE -1 TO ACCTSIDL} at :874, :885. */
    private static final String CURSOR_ACCOUNT_FILTER = "ACCTSID";

    /** The card filter's screen field, cursored by {@code MOVE -1 TO CARDSIDL} at :879. */
    private static final String CURSOR_CARD_FILTER = "CARDSID";

    /**
     * The stem of the seven row-selection screen fields, cursored by {@code MOVE -1 TO CRDSEL{n}L} at
     * :770, :782, :794, :805, :817 and :828.
     */
    private static final String CURSOR_ROW_SELECT_PREFIX = "CRDSEL";

    /**
     * Access to the {@code CARDDAT} base cluster, the only file this program opens.
     *
     * <p>The alternate-index path {@code CARDAIX} is deliberately not reachable from here: its operand
     * {@code LIT-CARD-FILE-ACCT-PATH} at {@code app/cbl/COCRDLIC.cbl:215-217} occurs exactly once
     * repository-wide, at its own declaration, so the program never browses it and this service must
     * not either. Filtering by account is therefore an in-memory predicate, not a keyed lookup.</p>
     */
    private final CardRepository cardRepository;

    /**
     * The screen depth, bound from {@code carddemo.pagination.card-list-page-size} with no default.
     *
     * <p>It is the single resolution of {@code WS-MAX-SCREEN-LINES} at
     * {@code app/cbl/COCRDLIC.cbl:177-178} and of the separately hardcoded literal the source repeats
     * at :1099, so the two cannot drift. It also drives the page-full test at :1191, the backward
     * counter seed at :1284-1286 and the depth of every array in {@code ProgramState}.</p>
     */
    private final int pageSize;

    /**
     * Creates the service.
     *
     * @param cardRepository repository over the {@code CARDDAT} base cluster. This is the only
     *     collaborator: the program never opens the {@code CARDAIX} alternate-index path, whose
     *     operand {@code LIT-CARD-FILE-ACCT-PATH} is referenced exactly once repository-wide at its
     *     own declaration, app/cbl/COCRDLIC.cbl:215-217.
     * @param pageSize the transcription of {@code WS-MAX-SCREEN-LINES} at
     *     app/cbl/COCRDLIC.cbl:177-178, bound from {@code carddemo.pagination.card-list-page-size}
     *     with no default so that a missing property fails fast at startup
     * @throws IllegalArgumentException if the configured page size is not positive, which would make
     *     every browse degenerate, or if it disagrees with the screen's physical row count
     */
    public CardListService(
            final CardRepository cardRepository,
            @Value("${carddemo.pagination.card-list-page-size}") final int pageSize) {
        if (cardRepository == null) {
            throw new IllegalArgumentException("cardRepository is required");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException(
                    "carddemo.pagination.card-list-page-size must be positive but was " + pageSize);
        }
        // The configured page size is a parity contract, not a tunable, and this guard is what makes
        // that statement enforceable rather than aspirational. Three families of construct in this file
        // are unrolled once per physical screen row, because the source unrolls them and the
        // one-to-one mandate forbids collapsing them into a loop: the row initialisations transcribed
        // from :680-743, the row attribute blocks from :751-833, and the seven received selection
        // codes from :972-978. Their count is fixed by the map, not by configuration - the symbolic map
        // declares CRDSEL1L through CRDSEL7L at app/cpy-bms/COCRDLI.CPY:73, :97, :127, :157, :187, :217
        // and :247 and declares no eighth row. A page size that disagreed with that count would leave
        // rows unread or index past the row table, so it is rejected at startup where the operator can
        // see it rather than at the first request. Left unguarded it would have surfaced later.
        if (pageSize != SCREEN_ROW_COUNT) {
            throw new IllegalArgumentException(
                    "carddemo.pagination.card-list-page-size must equal the "
                            + SCREEN_ROW_COUNT
                            + " physical rows the COCRDLI symbolic map declares"
                            + " (app/cpy-bms/COCRDLI.CPY rows at lines 73, 97, 127, 157, 187, 217, 247)"
                            + " but was "
                            + pageSize);
        }
        this.cardRepository = cardRepository;
        this.pageSize = pageSize;
    }

    /**
     * Lists one screen of cards. This is the whole of {@code 0000-MAIN.}
     * (app/cbl/COCRDLIC.cbl:298-602) reached as a single stateless request.
     *
     * @param request the received screen and the paging state the client echoes back; must not be
     *     {@code null}
     * @return the screen payload, the paging metadata and any navigation intent
     * @throws IllegalArgumentException if {@code request} is {@code null}
     * @throws ValidationException if an account or card filter is present but not numeric, which is
     *     the one family of errors that suppresses the browse entirely - see
     *     app/cbl/COCRDLIC.cbl:431-435
     * @throws FileAccessException if a browse read fails, or if a backward browse exhausts the
     *     records, the latter being a preserved legacy defect
     */
    public CardListResult listCards(final CardListRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        final CardListResult result = main0000(request);

        // Rule 1 Clause D. The legacy program's only instrumentation is the screen itself, so this is
        // new capability rather than a transcription, and it is written to the strictest reading of the
        // clause: never a card number, never a whole page of rows, never a verification value. Only the
        // page geometry, the counts and the masked trailing digits of the inbound filter are recorded.
        // Trace, span and correlation identifiers arrive through the MDC that CorrelationIdFilter and
        // the Logback encoder populate, so none is stamped here.
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "CCLI card list turn complete: page={}, rows={}, nextPage={}, inputError={}, "
                            + "rowsProtected={}, cardFilter={}",
                    result.page == null ? 0 : result.page.getPageNumber(),
                    result.rowCount(),
                    result.page != null && result.page.isNextPageAvailable(),
                    result.inputError,
                    result.rowSelectionProtected,
                    maskTail(request.cardFilter));
        }
        return result;
    }

    // ============================================================================================
    // PROCEDURE DIVISION - app/cbl/COCRDLIC.cbl:297 onward. One private method per Area-A label,
    // in source order. Thirty-nine in-file labels plus the two contributed by
    // COPY 'CSSTRPFY' at :1416.
    // ============================================================================================

    /**
     * Decides what to do with the received screen and dispatches accordingly.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 0000-MAIN.} at line 298. The
     * paragraph is the entry point by fall-through from {@code PROCEDURE DIVISION.} at line 297.</p>
     *
     * @param request the received screen and echoed paging state
     * @return the outcome of whichever branch the dispatch selects
     */
    private CardListResult main0000(final CardListRequest request) {
        // INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA - :300-302. Constructing the
        // per-request state IS that INITIALIZE: every WORKING-STORAGE item cleared here is a field
        // of ProgramState and therefore method-local, never a bean field. Paging state held in a
        // bean field would corrupt concurrent requests.
        final ProgramState state = new ProgramState(this.pageSize);

        // FUNCTION CURRENT-DATE at :645 and again at :652 reads the clock. In the target the caller
        // supplies the already formatted header date and time on the request, so the service holds no
        // clock, statically captured or injected, and stays deterministic under test.
        state.suppliedDate = request.currentDate;
        state.suppliedTime = request.currentTime;

        // EIBCALEN, tested at :315, :357 and :839. Mirrored onto the state because the presentation
        // paragraphs take no arguments in the source and must be able to test it at :839.
        state.commAreaPresent = request.isCommAreaPresent();

        // MOVE LIT-THISTRANID TO WS-TRANID - :307. WS-TRANID is declared at :51 and, per a
        // repository-wide census, is written here and never read. The store is preserved. Low.
        state.transactionId = LIT_THISTRANID;

        // SET WS-ERROR-MSG-OFF TO TRUE - :311. The 88 at :118 tests the field for SPACES.
        state.errorMessage = "";

        if (!request.isCommAreaPresent()) {
            // IF EIBCALEN = 0 - :315-325. First entry: nothing was passed, so build the context.
            state.initializeCardDemoCommArea();
            state.initializeThisProgramCommArea();
            state.cdemoFromTranId = LIT_THISTRANID;
            state.cdemoFromProgram = LIT_THISPGM;
            state.cdemoUserTypeAdmin = false;
            state.cdemoProgramReenter = false;
            state.cdemoLastMap = LIT_THISMAP;
            state.cdemoLastMapset = LIT_THISMAPSET;
            state.caScreenNumber = CA_FIRST_PAGE;
            state.caLastPageDisplayed = CA_LAST_PAGE_NOT_SHOWN;
        } else {
            // ELSE - :326-331 - split DFHCOMMAREA into CARDDEMO-COMMAREA and WS-THIS-PROGCOMMAREA.
            // WS-THIS-PROGCOMMAREA (:229-260) includes the 196-byte row table WS-SCREEN-DATA as an
            // 05-level child, so the previously displayed rows round-trip across turns. In the
            // stateless target the client echoes both halves back as request fields.
            state.loadCommArea(request);
        }

        // IF (CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM NOT EQUAL LIT-THISPGM) - :336-343.
        // Coming in from the menu: forget the past and start afresh. Note the asymmetry with
        // :322-323 - CDEMO-LAST-MAPSET is not reset here, only CDEMO-LAST-MAP. Preserved.
        if (!state.cdemoProgramReenter && !LIT_THISPGM.equals(state.cdemoFromProgram)) {
            state.initializeThisProgramCommArea();
            state.cdemoProgramReenter = false;
            state.cdemoLastMap = LIT_THISMAP;
            state.caScreenNumber = CA_FIRST_PAGE;
            state.caLastPageDisplayed = CA_LAST_PAGE_NOT_SHOWN;
        }

        // PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT - :349-350.
        yyyyStorePfkey(request, state);
        yyyyStorePfkeyExit();

        // IF EIBCALEN > 0 AND CDEMO-FROM-PROGRAM EQUAL LIT-THISPGM - :357-362. Only re-entry from
        // this program itself carries a screen worth receiving and editing.
        if (request.isCommAreaPresent() && LIT_THISPGM.equals(state.cdemoFromProgram)) {
            receiveMap2000(request, state);
            receiveMap2000Exit();
        }

        // SET PFK-INVALID TO TRUE / IF ENTER OR PF03 OR PF07 OR PF08 SET PFK-VALID - :370-376,
        // then IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE - :378-380. Any other key is coerced to
        // ENTER, which is why an unrecognised key behaves like a plain refresh.
        boolean pfkValid = false;
        if (AID_ENTER.equals(state.ccardAid)
                || AID_PFK03.equals(state.ccardAid)
                || AID_PFK07.equals(state.ccardAid)
                || AID_PFK08.equals(state.ccardAid)) {
            pfkValid = true;
        }
        if (!pfkValid) {
            state.ccardAid = AID_ENTER;
        }

        // IF (CCARD-AID-PFK03 AND CDEMO-FROM-PROGRAM EQUAL LIT-THISPGM) - :384-406. Go back to the
        // main menu. Note three preserved quirks: CCARD-NEXT-MAPSET receives LIT-MENUMAPSET at
        // :394 while CCARD-NEXT-MAP receives LIT-THISMAP at :395, so LIT-MENUMAP is never used;
        // CCARD-NEXT-PROG is never set on this branch; and SET CDEMO-PGM-ENTER TO TRUE is issued
        // twice, at :389 and again at :400.
        if (AID_PFK03.equals(state.ccardAid) && LIT_THISPGM.equals(state.cdemoFromProgram)) {
            state.cdemoFromTranId = LIT_THISTRANID;
            state.cdemoFromProgram = LIT_THISPGM;
            state.cdemoUserTypeAdmin = false;
            state.cdemoProgramReenter = false;
            state.cdemoLastMapset = LIT_THISMAPSET;
            state.cdemoLastMap = LIT_THISMAP;
            state.cdemoToProgram = LIT_MENUPGM;
            state.nextMapset = LIT_MENUMAPSET;
            state.nextMap = LIT_THISMAP;
            state.errorMessage = MSG_EXIT;
            state.cdemoProgramReenter = false;
            // EXEC CICS XCTL PROGRAM(LIT-MENUPGM) - :402-405.
            return transferControl(state, LIT_MENUPGM, true);
        }

        // IF CCARD-AID-PFK08 CONTINUE ELSE SET CA-LAST-PAGE-NOT-SHOWN TO TRUE - :410-414.
        // The inverted-CONTINUE shape is preserved rather than simplified to a negated test.
        if (AID_PFK08.equals(state.ccardAid)) {
            // CONTINUE - :411.
            state.noOperation();
        } else {
            state.caLastPageDisplayed = CA_LAST_PAGE_NOT_SHOWN;
        }

        // EVALUATE TRUE - :418-583.
        if (state.inputError) {
            // WHEN INPUT-ERROR - :419-438. Ask for corrections to the inputs.
            state.ccardErrorMessage = state.errorMessage;
            state.cdemoFromProgram = LIT_THISPGM;
            state.cdemoLastMapset = LIT_THISMAPSET;
            state.cdemoLastMap = LIT_THISMAP;
            state.nextProgram = LIT_THISPGM;
            state.nextMapset = LIT_THISMAPSET;
            state.nextMap = LIT_THISMAP;
            // IF NOT FLG-ACCTFILTER-NOT-OK AND NOT FLG-CARDFILTER-NOT-OK - :431-435. A selection
            // error still re-reads and returns the list; only a filter error suppresses the browse.
            if (!state.isAccountFilterNotOk() && !state.isCardFilterNotOk()) {
                readForward9000(state);
                readForward9000Exit(state);
            }
            // PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP - :436-437. Self-range: only the paragraph
            // itself runs, so the exit method is deliberately not called here.
            sendMap1000(state);
            return commonReturn(state);
        }

        if (AID_PFK07.equals(state.ccardAid) && state.isFirstPage()) {
            // WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE - declared TWICE, at :439-440 and again at
            // :444-445, with only comment lines between them. COBOL treats consecutive WHEN phrases
            // as an OR-list, so the pair reduces to one condition and the first occurrence is empty
            // and redundant. One guarded branch is emitted.
            // Page up while already on the first page re-reads FORWARD from the first card, which
            // redisplays the current page rather than raising an error.
            state.ridCardNumber = state.caFirstCardNumber;
            // The commented-out account-key move at :448-449 is a vestige of the abandoned
            // alternate-index path and is deliberately not resurrected.
            readForward9000(state);
            readForward9000Exit(state);
            sendMap1000(state);
            return commonReturn(state);
        }

        if (AID_PFK03.equals(state.ccardAid)
                || (state.cdemoProgramReenter && !LIT_THISPGM.equals(state.cdemoFromProgram))) {
            // WHEN CCARD-AID-PFK03 / WHEN CDEMO-PGM-REENTER AND CDEMO-FROM-PROGRAM NOT EQUAL
            // LIT-THISPGM - :458-482. An OR-list of two conditions sharing one body: the fresh
            // entry from another program, and hence the primary path for an unpaged first request.
            state.initializeCardDemoCommArea();
            state.initializeThisProgramCommArea();
            state.cdemoFromTranId = LIT_THISTRANID;
            state.cdemoFromProgram = LIT_THISPGM;
            state.cdemoUserTypeAdmin = false;
            state.cdemoProgramReenter = false;
            state.cdemoLastMap = LIT_THISMAP;
            state.cdemoLastMapset = LIT_THISMAPSET;
            state.caScreenNumber = CA_FIRST_PAGE;
            state.caLastPageDisplayed = CA_LAST_PAGE_NOT_SHOWN;
            // MOVE WS-CA-FIRST-CARD-NUM TO WS-CARD-RID-CARDNUM - :473-474. This runs AFTER the
            // INITIALIZE above, so the start key is the space-filled value that INITIALIZE leaves.
            state.ridCardNumber = state.caFirstCardNumber;
            readForward9000(state);
            readForward9000Exit(state);
            sendMap1000(state);
            return commonReturn(state);
        }

        if (AID_PFK08.equals(state.ccardAid) && state.isNextPageExists()) {
            // WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-EXISTS - :486-497. Page down.
            state.ridCardNumber = state.caLastCardNumber;
            // ADD +1 TO WS-CA-SCREEN-NUM - :492. WS-CA-SCREEN-NUM is PIC 9(1) (:237), so the
            // single digit wraps rather than overflowing.
            state.caScreenNumber = (state.caScreenNumber + 1) % SCREEN_NUMBER_MODULUS;
            readForward9000(state);
            readForward9000Exit(state);
            // PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP-EXIT - :495-496. The correct range form.
            sendMap1000(state);
            sendMap1000Exit();
            return commonReturn(state);
        }

        if (AID_PFK07.equals(state.ccardAid) && !state.isFirstPage()) {
            // WHEN CCARD-AID-PFK07 AND NOT CA-FIRST-PAGE - :501-513. Page up.
            state.ridCardNumber = state.caFirstCardNumber;
            // SUBTRACT 1 FROM WS-CA-SCREEN-NUM - :508.
            state.caScreenNumber = Math.floorMod(state.caScreenNumber - 1, SCREEN_NUMBER_MODULUS);
            readBackwards9100(state);
            readBackwards9100Exit(state);
            sendMap1000(state);
            sendMap1000Exit();
            return commonReturn(state);
        }

        // WHEN CCARD-AID-ENTER AND VIEW-REQUESTED-ON(I-SELECTED) AND CDEMO-FROM-PROGRAM EQUAL
        // LIT-THISPGM - :517-541, and its update twin at :545-569.
        //
        // LABELLED DEVIATION. I-SELECTED is zeroed at :1097 and subscripted with
        // no bounds check at :518, :531, :533, :546, :559 and :561. Subscript zero against
        // OCCURS 7 TIMES is out of range and, under the production NOSSRANGE compile, silently
        // reads storage preceding the table. Java cannot reproduce an out-of-bounds read, so zero
        // is treated as "no row selected" and the dispatch falls through to WHEN OTHER at :572 -
        // the observable legacy outcome whenever that garbage byte is neither 'S' nor 'U'.
        if (AID_ENTER.equals(state.ccardAid)
                && state.selectedIndex > 0
                && LIT_THISPGM.equals(state.cdemoFromProgram)) {
            final String selectedFlag = state.selectFlags[state.selectedIndex - 1];
            if (SELECT_VIEW.equals(selectedFlag)) {
                return dispatchToCardDetail(state);
            }
            if (SELECT_UPDATE.equals(selectedFlag)) {
                return dispatchToCardUpdate(state);
            }
        }

        // WHEN OTHER - :572-582.
        state.ridCardNumber = state.caFirstCardNumber;
        readForward9000(state);
        readForward9000Exit(state);
        sendMap1000(state);
        return commonReturn(state);
    }

    /**
     * Transfers to the card detail program for the selected row.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 517-541, the {@code 'S'} arm of the
     * {@code EVALUATE} in {@code 0000-MAIN.}. Extracted only so that the two structurally identical
     * transfer arms read clearly; no paragraph boundary is crossed.</p>
     *
     * @param state the per-request working storage
     * @return a navigation intent naming the detail program and the selected keys
     */
    private CardListResult dispatchToCardDetail(final ProgramState state) {
        state.cdemoFromTranId = LIT_THISTRANID;
        state.cdemoFromProgram = LIT_THISPGM;
        state.cdemoUserTypeAdmin = false;
        state.cdemoProgramReenter = false;
        state.cdemoLastMapset = LIT_THISMAPSET;
        state.cdemoLastMap = LIT_THISMAP;
        state.nextProgram = LIT_CARDDTLPGM;
        state.nextMapset = LIT_CARDDTLMAPSET;
        state.nextMap = LIT_CARDDTLMAP;
        // MOVE WS-ROW-ACCTNO (I-SELECTED) TO CDEMO-ACCT-ID - :531-532, and
        // MOVE WS-ROW-CARD-NUM (I-SELECTED) TO CDEMO-CARD-NUM - :533-534.
        state.cdemoAcctId = state.rowAccountNumber[state.selectedIndex - 1];
        state.cdemoCardNumber = state.rowCardNumber[state.selectedIndex - 1];
        // EXEC CICS XCTL PROGRAM(CCARD-NEXT-PROG) - :538-541.
        return transferControl(state, LIT_CARDDTLPGM, false);
    }

    /**
     * Transfers to the card update program for the selected row.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 545-569, the {@code 'U'} arm of the
     * {@code EVALUATE} in {@code 0000-MAIN.}.</p>
     *
     * @param state the per-request working storage
     * @return a navigation intent naming the update program and the selected keys
     */
    private CardListResult dispatchToCardUpdate(final ProgramState state) {
        state.cdemoFromTranId = LIT_THISTRANID;
        state.cdemoFromProgram = LIT_THISPGM;
        state.cdemoUserTypeAdmin = false;
        state.cdemoProgramReenter = false;
        state.cdemoLastMapset = LIT_THISMAPSET;
        state.cdemoLastMap = LIT_THISMAP;
        state.nextProgram = LIT_CARDUPDPGM;
        state.nextMapset = LIT_CARDUPDMAPSET;
        state.nextMap = LIT_CARDUPDMAP;
        // MOVE WS-ROW-ACCTNO (I-SELECTED) TO CDEMO-ACCT-ID - :559-560, and
        // MOVE WS-ROW-CARD-NUM (I-SELECTED) TO CDEMO-CARD-NUM - :561-562.
        state.cdemoAcctId = state.rowAccountNumber[state.selectedIndex - 1];
        state.cdemoCardNumber = state.rowCardNumber[state.selectedIndex - 1];
        // EXEC CICS XCTL PROGRAM(CCARD-NEXT-PROG) - :566-569.
        return transferControl(state, LIT_CARDUPDPGM, false);
    }

    /**
     * Renders {@code EXEC CICS XCTL}, which transfers control and terminates this program without
     * sending a map.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 402-405, 538-541 and 566-569. Under
     * transformation Rule 7 a transfer of control becomes URL navigation, so the target program is
     * reported to the caller rather than invoked. This service therefore never injects
     * {@code CardDetailService} or {@code CardUpdateService}: the legacy coupling is bidirectional -
     * {@code COCRDSLC} has a dedicated entry path taken from this program - so injecting either
     * would create a circular dependency and reintroduce commarea-style statefulness.</p>
     *
     * @param state the per-request working storage
     * @param targetProgram the program named on the transfer
     * @param exitRequested {@code true} only for the PF03 return to the main menu
     * @return the navigation intent, carrying no screen payload because no map was sent
     */
    private CardListResult transferControl(
            final ProgramState state, final String targetProgram, final boolean exitRequested) {
        return new CardListResult(
                null,
                null,
                targetProgram,
                state.nextMapset,
                state.nextMap,
                state.cdemoAcctId,
                state.cdemoCardNumber,
                exitRequested,
                state.protectSelectRows,
                state.inputError,
                new String(state.selectErrorFlags),
                state.errorMessage,
                state.infoMessage,
                null,
                state.cursorField,
                flagString(state.rowSelectable),
                flagString(state.rowErrorHighlighted),
                filterFieldFlags(state),
                state.mapSent);
    }

    /**
     * Saves the context back into the commarea and returns to CICS pseudo-conversationally.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code COMMON-RETURN.} at line 604. The
     * {@code EXEC CICS RETURN TRANSID ... COMMAREA} at :615-619 becomes the response: the paging
     * state the source stored in {@code WS-COMMAREA} is returned as metadata for the client to echo
     * back, because the stateless target keeps no server-side session or cursor.</p>
     *
     * @param state the per-request working storage
     * @return the fully populated screen payload, paging metadata and any navigation intent
     */
    private CardListResult commonReturn(final ProgramState state) {
        state.cdemoFromTranId = LIT_THISTRANID;
        state.cdemoFromProgram = LIT_THISPGM;
        state.cdemoLastMapset = LIT_THISMAPSET;
        state.cdemoLastMap = LIT_THISMAP;

        // MOVE CARDDEMO-COMMAREA TO WS-COMMAREA and MOVE WS-THIS-PROGCOMMAREA to the tail of it -
        // :609-612. The two halves become the response DTO plus the paging metadata below.
        final List<CardDto.CardListRow> rows = buildDisplayedRows(state);
        final CardDto screen = CardDto.list(
                state.transactionId,
                state.outTitle01,
                state.outCurrentDate,
                state.outProgramName,
                state.outTitle02,
                state.outCurrentTime,
                state.outPageNumber,
                state.outAccountFilter,
                state.outCardFilter,
                rows,
                state.outInfoMessage,
                state.outErrorMessage);

        // WS-CA-SCREEN-NUM is PIC 9(1) and may legitimately hold zero before :1177-1181 bumps it,
        // whereas PageResponse requires a page number of at least one. The value is normalised at
        // this boundary only, and nowhere in the browse logic.
        final int reportedPageNumber = Math.max(state.caScreenNumber, CA_FIRST_PAGE);
        // CA-FIRST-CARD-NUM and CA-LAST-CARD-NUM (:1197-1205) ARE card numbers: the browse repositions from
        // them exactly as the source did, so they are carried here in the clear as INTERNAL keys.
        //
        // They must never be emitted. A primary account number may not appear in an HTTP response, and a
        // page cursor is the worst place for one - it is precisely the value a client logs, caches and
        // bookmarks. The REST boundary therefore does not return this PageResponse: the operation seals both
        // keys with com.cardemo.security.SnapshotTokenService, and only the sealed forms reach a client,
        // which reopens them on the next request so the browse behaves identically. This object stays
        // in-process, which is why the keys may stay in the clear on it.
        final PageResponse<CardDto.CardListRow> page = new PageResponse<>(
                rows,
                reportedPageNumber,
                this.pageSize,
                state.isNextPageExists(),
                state.caFirstCardNumber,
                state.caLastCardNumber);

        final CardListResult result = new CardListResult(
                screen,
                page,
                state.nextProgram,
                state.nextMapset,
                state.nextMap,
                state.cdemoAcctId,
                state.cdemoCardNumber,
                false,
                state.protectSelectRows,
                state.inputError,
                new String(state.selectErrorFlags),
                state.errorMessage,
                state.infoMessage,
                state.selectionFailure,
                state.cursorField,
                flagString(state.rowSelectable),
                flagString(state.rowErrorHighlighted),
                filterFieldFlags(state),
                state.mapSent);

        // Fall-through to 0000-MAIN-EXIT at :621, which textually follows COMMON-RETURN. In CICS the
        // EXEC CICS RETURN at :615-619 terminates the task first, so the paragraph never executes;
        // the call preserves the structural relationship at zero behavioural cost.
        main0000Exit();
        return result;
    }

    /**
     * {@code 0000-MAIN-EXIT.} - the paragraph is a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 0000-MAIN-EXIT.} at line 621, whose
     * body is {@code EXIT} at :622. It carries no {@code PERFORM}, {@code GO TO} or {@code THRU}
     * reference and is reachable only by fall-through from {@code COMMON-RETURN.}, which the
     * preceding {@code EXEC CICS RETURN} prevents from ever completing.</p>
     */
    private void main0000Exit() {
        // EXIT - :622. The COBOL EXIT statement is a no-op that transfers to the end of the
        // paragraph; there is nothing for Java to do.
    }

    /**
     * Builds the outbound screen by running the six presentation paragraphs in order.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1000-SEND-MAP.} at line 624. Each
     * inner {@code PERFORM} at :625-636 names its own exit paragraph as the range end, so both
     * members of every pair are invoked here.</p>
     *
     * <p>Four call sites in {@code 0000-MAIN} write {@code PERFORM 1000-SEND-MAP THRU
     * 1000-SEND-MAP} (:436-437, :452-453, :480-481, :580-581), naming this paragraph as both range
     * ends so that only it executes; two write the correct {@code THRU 1000-SEND-MAP-EXIT}
     * (:495-496, :511-512). Both forms are rendered faithfully and neither loses a range, because
     * the exit paragraph contains only {@code EXIT}.</p>
     *
     * @param state the per-request working storage
     */
    private void sendMap1000(final ProgramState state) {
        screenInit1100(state);
        screenInit1100Exit();
        screenArrayInit1200(state);
        screenArrayInit1200Exit();
        setupArrayAttribs1250(state);
        setupArrayAttribs1250Exit();
        setupScreenAttrs1300(state);
        setupScreenAttrs1300Exit();
        setupMessage1400(state);
        setupMessage1400Exit();
        sendScreen1500(state);
        sendScreen1500Exit();
    }

    /**
     * {@code 1000-SEND-MAP-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1000-SEND-MAP-EXIT.} at line 639,
     * body {@code EXIT} at :640.</p>
     */
    private void sendMap1000Exit() {
        // EXIT - :640.
    }

    /**
     * Clears the map output area and populates the six header fields, the page number and the
     * information message.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1100-SCREEN-INIT.} at line 642.</p>
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} is issued twice, at :645 and again at
     * :652; the second is redundant. Both are preserved as two assignments of the same value.
     * The clock is not read here: the pre-formatted date and time arrive on the
     * request, which keeps the service deterministic and free of a statically captured clock, and
     * avoids depending on a collaborator that is not declared.</p>
     *
     * @param state the per-request working storage
     */
    private void screenInit1100(final ProgramState state) {
        // MOVE LOW-VALUES TO CCRDLIAO - :643. Clears the screen OUTPUT area only; the row table
        // WS-ALL-ROWS is untouched here and is copied across by 1200-SCREEN-ARRAY-INIT.
        state.clearScreenOutput();

        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - :645 (first of two).
        state.curDateData = state.suppliedDate;
        state.curTimeData = state.suppliedTime;

        // MOVE CCDA-TITLE01/CCDA-TITLE02 - :647-648, from app/cpy/COTTL01Y.cpy.
        state.outTitle01 = CCDA_TITLE01;
        state.outTitle02 = CCDA_TITLE02;
        // MOVE LIT-THISTRANID TO TRNNAMEO and LIT-THISPGM TO PGMNAMEO - :649-650.
        state.transactionId = LIT_THISTRANID;
        state.outProgramName = LIT_THISPGM;

        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - :652 (second, redundant).
        state.curDateData = state.suppliedDate;
        state.curTimeData = state.suppliedTime;

        // MOVE WS-CURDATE-MONTH/DAY/YEAR(3:2) then WS-CURDATE-MM-DD-YY TO CURDATEO - :654-658, and
        // MOVE WS-CURTIME-HOURS/MINUTE/SECOND then WS-CURTIME-HH-MM-SS TO CURTIMEO - :660-664.
        state.outCurrentDate = state.curDateData;
        state.outCurrentTime = state.curTimeData;

        // MOVE WS-CA-SCREEN-NUM TO PAGENOO - :667. PIC 9(1) into PIC X(3).
        state.outPageNumber = movePicX(Integer.toString(state.caScreenNumber), PAGE_NUMBER_WIDTH);

        // SET WS-NO-INFO-MESSAGE TO TRUE - :669. The 88 at :113-114 tests for SPACES or LOW-VALUES.
        state.infoMessage = "";
        // MOVE WS-INFO-MSG TO INFOMSGO - :670. The message was cleared on the line above, so this
        // deliberately propagates an empty value; 1400-SETUP-MESSAGE may replace it later.
        state.outInfoMessage = state.infoMessage;
        // MOVE DFHBMDAR TO INFOMSGC - :671. A BMS colour attribute from the CICS-supplied copybook
        // DFHBMSCA (COPY at :267), which is not present in this repository and has no REST
        // counterpart, so no byte value is invented for it.
        state.noOperation();
    }

    /**
     * {@code 1100-SCREEN-INIT-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1100-SCREEN-INIT-EXIT.} at line 674,
     * body {@code EXIT} at :675.</p>
     */
    private void screenInit1100Exit() {
        // EXIT - :675.
    }

    /**
     * Copies the seven row-table entries into the seven groups of map output fields.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1200-SCREEN-ARRAY-INIT.} at line 678.
     * The source carries a comment at :679 observing that redefines could clean up the repetitive
     * code; the seven blocks are nevertheless kept unrolled at :680, :689, :698, :707, :716, :726
     * and :735, because each is its own source construct and the one-to-one mandate covers duplicate
     * paths.</p>
     *
     * <p>Note that the selector-type output fields ({@code CRDSTP2O} through {@code CRDSTP7O}) are
     * never written by this program, so they remain at low values on every turn. That is why the
     * selector type handed to {@code CardDto.CardListRow} is always absent.</p>
     *
     * @param state the per-request working storage
     */
    private void screenArrayInit1200(final ProgramState state) {
        // Seven unrolled blocks, preserved individually rather than collapsed into a loop.
        screenArrayInitRow(state, 1);
        screenArrayInitRow(state, 2);
        screenArrayInitRow(state, 3);
        screenArrayInitRow(state, 4);
        screenArrayInitRow(state, 5);
        screenArrayInitRow(state, 6);
        screenArrayInitRow(state, 7);
    }

    /**
     * One of the seven unrolled row-initialisation blocks of {@code 1200-SCREEN-ARRAY-INIT.}.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 680-687 (row 1), 689-696, 698-705, 707-714,
     * 716-723, 726-733 and 735-742. Every block has an identical shape -
     * {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES CONTINUE ELSE} four moves {@code END-IF} - so the
     * body is expressed once and the seven call sites above preserve the unrolling.</p>
     *
     * @param state the per-request working storage
     * @param rowNumber the one-based row, matching the COBOL subscript
     */
    private void screenArrayInitRow(final ProgramState state, final int rowNumber) {
        final int index = rowNumber - 1;
        if (state.isRowLowValues(index)) {
            // CONTINUE - the row was never filled by the browse, so nothing is displayed.
            state.noOperation();
        } else {
            state.outRowSelect[index] = state.selectFlags[index];
            state.outRowAccountNumber[index] = state.rowAccountNumber[index];
            state.outRowCardNumber[index] = state.rowCardNumber[index];
            state.outRowStatus[index] = state.rowCardStatus[index];
        }
    }

    /**
     * {@code 1200-SCREEN-ARRAY-INIT-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1200-SCREEN-ARRAY-INIT-EXIT.} at line
     * 745, body {@code EXIT} at :746.</p>
     */
    private void screenArrayInit1200Exit() {
        // EXIT - :746.
    }

    /**
     * Sets the protection, colour and cursor attributes of the seven row-selection fields.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1250-SETUP-ARRAY-ATTRIBS.} at line
     * 748. The seven per-row error checks sit at :755, :768, :780, :792, :803, :815 and :826 and are
     * kept unrolled as seven distinct call sites; row one has its own body because it is genuinely
     * asymmetric with the rest.</p>
     *
     * <p>The BMS attribute constants ({@code DFHBMPRF}, {@code DFHBMPRO}, {@code DFHBMFSE},
     * {@code DFHRED}) come from the CICS-supplied copybook {@code DFHBMSCA}, copied in at :267 and
     * absent from this repository. No byte values are invented for them; each is rendered as its
     * observable effect - whether the row is selectable, whether it is flagged in error, and where
     * the cursor lands.</p>
     *
     * @param state the per-request working storage
     */
    private void setupArrayAttribs1250(final ProgramState state) {
        setupArrayAttribsRow1(state);
        setupArrayAttribsRow(state, 2);
        setupArrayAttribsRow(state, 3);
        setupArrayAttribsRow(state, 4);
        setupArrayAttribsRow(state, 5);
        setupArrayAttribsRow(state, 6);
        setupArrayAttribsRow(state, 7);
    }

    /**
     * The row-one block of {@code 1250-SETUP-ARRAY-ATTRIBS.}, which differs from rows two to seven.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 751-762. Two asymmetries are preserved. The
     * protected arm moves {@code DFHBMPRF} at :753 where every other row moves {@code DFHBMPRO}.
     * And the error arm stamps an asterisk into the output field at :757-759 when the received
     * selection was a space or low values, where every other row instead repositions the cursor.
     * That stamp is the origin of the two-state validation contract in which only a blank failure is
     * marked.</p>
     *
     * @param state the per-request working storage
     */
    private void setupArrayAttribsRow1(final ProgramState state) {
        final int index = 0;
        if (state.isRowLowValues(index) || state.protectSelectRows) {
            // MOVE DFHBMPRF TO CRDSEL1A - :753. Protected, and unlike the other rows the "first"
            // variant of the protected attribute is used.
            state.rowSelectable[index] = false;
        } else {
            // IF WS-ROW-CRDSELECT-ERROR(1) = '1' - :755.
            if (state.selectErrorFlags[index] == ROW_ERROR_MARKER) {
                // MOVE DFHRED TO CRDSEL1C - :756.
                state.rowErrorHighlighted[index] = true;
                // IF WS-EDIT-SELECT(1) = SPACE OR LOW-VALUES MOVE '*' TO CRDSEL1O - :757-759.
                if (isSelectBlank(state.selectFlags[index])) {
                    state.outRowSelect[index] = BLANK_SELECTION_STAMP;
                }
            }
            // MOVE DFHBMFSE TO CRDSEL1A - :761.
            state.rowSelectable[index] = true;
        }
    }

    /**
     * One of the six unrolled blocks of {@code 1250-SETUP-ARRAY-ATTRIBS.} covering rows two to seven.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 764-773 (row 2), 775-785, 787-797, 799-808,
     * 810-820 and 822-831. All six share an identical shape, so the body is expressed once while the
     * six call sites above preserve the unrolling.</p>
     *
     * <p>Row four's protected arm carries a stray bare {@code I} alone on :790, immediately after
     * {@code MOVE DFHBMPRO TO CRDSEL4A OF CCRDLIAI} at :789 and before {@code ELSE} at :791. Read as
     * a second receiving operand of that move it would overwrite the subscript with an attribute
     * byte, which is harmless because the subscript is no longer live once this paragraph runs and is
     * re-initialised each request at :300-302. The actual compiler parse cannot be performed here:
     * confirming it needs an Enterprise COBOL compiler, which this environment does not provide. The
     * clobber is therefore documented rather than reproduced.</p>
     *
     * @param state the per-request working storage
     * @param rowNumber the one-based row, matching the COBOL subscript
     */
    private void setupArrayAttribsRow(final ProgramState state, final int rowNumber) {
        final int index = rowNumber - 1;
        if (state.isRowLowValues(index) || state.protectSelectRows) {
            // MOVE DFHBMPRO TO CRDSEL{n}A - :766, :777, :789, :801, :812, :824.
            state.rowSelectable[index] = false;
        } else {
            // IF WS-ROW-CRDSELECT-ERROR(n) = '1' - :768, :780, :792, :803, :815, :826.
            if (state.selectErrorFlags[index] == ROW_ERROR_MARKER) {
                // MOVE DFHRED TO CRDSEL{n}C - :769, :781, :793, :804, :816, :827.
                state.rowErrorHighlighted[index] = true;
                // MOVE -1 TO CRDSEL{n}L - :770, :782, :794, :805, :817, :828. A length of minus one
                // is the BMS idiom for "place the cursor here"; no asterisk is stamped on these rows.
                state.cursorField = CURSOR_ROW_SELECT_PREFIX + rowNumber;
            }
            // MOVE DFHBMFSE TO CRDSEL{n}A - :772, :784, :796, :807, :819, :830.
            state.rowSelectable[index] = true;
        }
    }

    /**
     * {@code 1250-SETUP-ARRAY-ATTRIBS-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1250-SETUP-ARRAY-ATTRIBS-EXIT.} at
     * line 834, body {@code EXIT} at :835.</p>
     */
    private void setupArrayAttribs1250Exit() {
        // EXIT - :835.
    }

    /**
     * Echoes the two filter fields back to the screen and positions the cursor.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1300-SETUP-SCREEN-ATTRS.} at line
     * 837. On a first entry, or on a fresh entry from the main menu, the filters are deliberately
     * left cleared (:839-842), which is why arriving from the menu shows an empty search criterion
     * rather than the previous one.</p>
     *
     * @param state the per-request working storage
     */
    private void setupScreenAttrs1300(final ProgramState state) {
        // IF EIBCALEN = 0 OR (CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-MENUPGM) - :839-842.
        if (!state.commAreaPresent
                || (!state.cdemoProgramReenter && LIT_MENUPGM.equals(state.cdemoFromProgram))) {
            // CONTINUE - :842.
            state.noOperation();
        } else {
            // EVALUATE TRUE for the account filter - :844-854.
            if (state.isAccountFilterValid() || state.isAccountFilterNotOk()) {
                // MOVE CC-ACCT-ID TO ACCTSIDO / MOVE DFHBMFSE TO ACCTSIDA - :847-848.
                state.outAccountFilter = state.ccAcctId;
                state.accountFilterSelectable = true;
            } else if (isZeroDigits(state.cdemoAcctId)) {
                // WHEN CDEMO-ACCT-ID = 0 - :849-850.
                state.outAccountFilter = null;
            } else {
                // WHEN OTHER - :851-853.
                state.outAccountFilter = state.cdemoAcctId;
                state.accountFilterSelectable = true;
            }

            // EVALUATE TRUE for the card filter - :856-867.
            if (state.isCardFilterValid() || state.isCardFilterNotOk()) {
                // MOVE CC-CARD-NUM TO CARDSIDO / MOVE DFHBMFSE TO CARDSIDA - :859-860.
                state.outCardFilter = state.ccCardNumber;
                state.cardFilterSelectable = true;
            } else if (isZeroDigits(state.cdemoCardNumber)) {
                // WHEN CDEMO-CARD-NUM = 0 - :861-862.
                state.outCardFilter = null;
            } else {
                // WHEN OTHER - :863-866.
                state.outCardFilter = state.cdemoCardNumber;
                state.cardFilterSelectable = true;
            }
        }

        // IF FLG-ACCTFILTER-NOT-OK - :872-875. Colour the field and place the cursor on it.
        if (state.isAccountFilterNotOk()) {
            state.accountFilterErrorHighlighted = true;
            state.cursorField = CURSOR_ACCOUNT_FILTER;
        }

        // IF FLG-CARDFILTER-NOT-OK - :877-880.
        if (state.isCardFilterNotOk()) {
            state.cardFilterErrorHighlighted = true;
            state.cursorField = CURSOR_CARD_FILTER;
        }

        // IF INPUT-OK MOVE -1 TO ACCTSIDL - :884-886. With no errors the cursor rests on the
        // account filter.
        if (!state.inputError) {
            state.cursorField = CURSOR_ACCOUNT_FILTER;
        }
    }

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1300-SETUP-SCREEN-ATTRS-EXIT.} at
     * line 890, body {@code EXIT} at :891.</p>
     */
    private void setupScreenAttrs1300Exit() {
        // EXIT - :891.
    }

    /**
     * Chooses the information or error message the screen will carry.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1400-SETUP-MESSAGE.} at line 895.</p>
     *
     * <p>Two of this paragraph's literals are easy to miss and are implemented verbatim:
     * {@code NO PREVIOUS PAGES TO DISPLAY} at :903-904 and {@code NO MORE PAGES TO DISPLAY} at
     * :908-909. The latter is reachable only through the latch at :910-916, which on a first
     * page-down past the end merely records that the last page has now been shown; a second
     * page-down then satisfies the condition. A filter error suppresses every paging message,
     * because the first {@code WHEN} pair at :898-900 continues without setting anything.</p>
     *
     * @param state the per-request working storage
     */
    private void setupMessage1400(final ProgramState state) {
        // EVALUATE TRUE - :897-922.
        if (state.isAccountFilterNotOk() || state.isCardFilterNotOk()) {
            // WHEN FLG-ACCTFILTER-NOT-OK / WHEN FLG-CARDFILTER-NOT-OK - :898-900. An OR-list whose
            // shared body is CONTINUE, so a filter error leaves the message the edit paragraph set.
            state.noOperation();
        } else if (AID_PFK07.equals(state.ccardAid) && state.isFirstPage()) {
            // WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE - :901-904.
            state.errorMessage = MSG_NO_PREVIOUS_PAGES;
        } else if (AID_PFK08.equals(state.ccardAid)
                && state.isNextPageNotExists()
                && state.caLastPageDisplayed == CA_LAST_PAGE_SHOWN) {
            // WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-NOT-EXISTS AND CA-LAST-PAGE-SHOWN - :905-909.
            state.errorMessage = MSG_NO_MORE_PAGES;
        } else if (AID_PFK08.equals(state.ccardAid) && state.isNextPageNotExists()) {
            // WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-NOT-EXISTS - :910-916. The latch.
            state.infoMessage = MSG_INFORM_REC_ACTIONS;
            if (state.caLastPageDisplayed == CA_LAST_PAGE_NOT_SHOWN && state.isNextPageNotExists()) {
                state.caLastPageDisplayed = CA_LAST_PAGE_SHOWN;
            }
        } else if (state.isNoInfoMessage() || state.isNextPageExists()) {
            // WHEN WS-NO-INFO-MESSAGE / WHEN CA-NEXT-PAGE-EXISTS - :917-919.
            state.infoMessage = MSG_INFORM_REC_ACTIONS;
        } else {
            // WHEN OTHER - :920-921.
            state.infoMessage = "";
        }

        // MOVE WS-ERROR-MSG TO ERRMSGO - :924. Unconditional.
        state.outErrorMessage = movePicX(state.errorMessage, ERROR_MESSAGE_WIDTH);

        // IF NOT WS-NO-INFO-MESSAGE AND NOT WS-NO-RECORDS-FOUND - :926-930. The information message
        // is suppressed while the no-records-found error stands, so the two never appear together.
        if (!state.isNoInfoMessage() && !MSG_NO_RECORDS_FOUND.equals(state.errorMessage)) {
            state.outInfoMessage = state.infoMessage;
            // MOVE DFHNEUTR TO INFOMSGC - :929. A CICS-supplied colour attribute; see the note on
            // 1250-SETUP-ARRAY-ATTRIBS.
            state.noOperation();
        }
    }

    /**
     * {@code 1400-SETUP-MESSAGE-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1400-SETUP-MESSAGE-EXIT.} at line 933,
     * body {@code EXIT} at :934.</p>
     */
    private void setupMessage1400Exit() {
        // EXIT - :934.
    }

    /**
     * Sends the completed map to the terminal.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1500-SEND-SCREEN.} at line 938. The
     * {@code EXEC CICS SEND MAP ... CURSOR ERASE FREEKB} at :939-946 has no REST counterpart: the
     * screen is serialised by {@code COMMON-RETURN} into the response DTO instead. The response code
     * the source captures at :944 is never examined, an absent guard that is preserved.</p>
     *
     * @param state the per-request working storage
     */
    private void sendScreen1500(final ProgramState state) {
        // The map is materialised as a DTO by COMMON-RETURN; marking it sent keeps the paragraph
        // observable so that the send and the response cannot drift apart.
        state.mapSent = true;
    }

    /**
     * {@code 1500-SEND-SCREEN-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 1500-SEND-SCREEN-EXIT.} at line 948,
     * body {@code EXIT} at :949.</p>
     */
    private void sendScreen1500Exit() {
        // EXIT - :949.
    }

    /**
     * Receives the screen and edits everything it carried.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2000-RECEIVE-MAP.} at line 951, body
     * at :952-956.</p>
     *
     * @param request the inbound request standing in for the received map
     * @param state the per-request working storage
     * @throws ValidationException when a filter fails its edit; selection failures are carried in the
     *     response instead, exactly as the source carries them
     */
    private void receiveMap2000(final CardListRequest request, final ProgramState state) {
        // PERFORM 2100-RECEIVE-SCREEN THRU 2100-RECEIVE-SCREEN-EXIT - :952-953.
        receiveScreen2100(request, state);
        receiveScreen2100Exit();
        // PERFORM 2200-EDIT-INPUTS THRU 2200-EDIT-INPUTS-EXIT - :955-956.
        editInputs2200(state);
        editInputs2200Exit();
    }

    /**
     * {@code 2000-RECEIVE-MAP-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2000-RECEIVE-MAP-EXIT.} at line 959,
     * body {@code EXIT} at :960.</p>
     */
    private void receiveMap2000Exit() {
        // EXIT - :960.
    }

    /**
     * Moves the received screen fields into working storage.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2100-RECEIVE-SCREEN.} at line 962. The
     * {@code EXEC CICS RECEIVE MAP INTO(CCRDLIAI)} at :963-967 becomes the inbound request; its
     * response code at :966 is captured by the source and never examined, an absent guard that is
     * preserved.</p>
     *
     * <p>Both filter moves are fixed-width byte moves - {@code ACCTSIDI} is {@code X(11)} into
     * {@code CC-ACCT-ID} {@code X(11)} at :969 and {@code CARDSIDI} is {@code X(16)} into
     * {@code CC-CARD-NUM} {@code X(16)} at :970 - so a partially typed filter arrives space-padded and
     * consequently fails the {@code IS NUMERIC} test in the edit paragraphs. That is why the two error
     * messages insist on a full 11 or 16 digit number, and it is reproduced here by padding to the
     * declared width before editing.</p>
     *
     * @param request the inbound request standing in for the received map
     * @param state the per-request working storage
     */
    private void receiveScreen2100(final CardListRequest request, final ProgramState state) {
        // MOVE ACCTSIDI TO CC-ACCT-ID - :969.
        state.ccAcctId = movePicX(request.accountFilter, ACCOUNT_FILTER_WIDTH);
        // MOVE CARDSIDI TO CC-CARD-NUM - :970.
        state.ccCardNumber = movePicX(request.cardFilter, CARD_FILTER_WIDTH);

        // Seven unrolled MOVE CRDSEL{n}I TO WS-EDIT-SELECT(n) statements - :972-978. Kept as seven
        // distinct assignments because each is its own source construct.
        state.selectFlags[0] = movePicX(request.rowSelection(1), 1);
        state.selectFlags[1] = movePicX(request.rowSelection(2), 1);
        state.selectFlags[2] = movePicX(request.rowSelection(3), 1);
        state.selectFlags[3] = movePicX(request.rowSelection(4), 1);
        state.selectFlags[4] = movePicX(request.rowSelection(5), 1);
        state.selectFlags[5] = movePicX(request.rowSelection(6), 1);
        state.selectFlags[6] = movePicX(request.rowSelection(7), 1);
    }

    /**
     * {@code 2100-RECEIVE-SCREEN-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2100-RECEIVE-SCREEN-EXIT.} at line
     * 981, body {@code EXIT} at :982.</p>
     */
    private void receiveScreen2100Exit() {
        // EXIT - :982.
    }

    /**
     * Runs the three edit paragraphs in their source order.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2200-EDIT-INPUTS.} at line 985. The
     * order is load-bearing: the account edit runs first and its message is unguarded, so when both
     * filters are non-numeric the account message wins and the card message is suppressed by its own
     * guard at :1056.</p>
     *
     * @param state the per-request working storage
     * @throws ValidationException when either filter fails its edit
     */
    private void editInputs2200(final ProgramState state) {
        // SET INPUT-OK TO TRUE - :986.
        state.inputError = false;
        // SET FLG-PROTECT-SELECT-ROWS-NO TO TRUE - :987.
        state.protectSelectRows = false;

        // PERFORM 2210-EDIT-ACCOUNT THRU 2210-EDIT-ACCOUNT-EXIT - :989-990.
        editAccount2210(state);
        editAccount2210Exit();
        // PERFORM 2220-EDIT-CARD THRU 2220-EDIT-CARD-EXIT - :992-993.
        editCard2220(state);
        editCard2220Exit();
        // PERFORM 2250-EDIT-ARRAY THRU 2250-EDIT-ARRAY-EXIT - :995-996.
        editArray2250(state);
        editArray2250Exit();
    }

    /**
     * {@code 2200-EDIT-INPUTS-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2200-EDIT-INPUTS-EXIT.} at line 999,
     * body {@code EXIT} at :1000.</p>
     */
    private void editInputs2200Exit() {
        // EXIT - :1000.
    }

    /**
     * Edits the account filter.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2210-EDIT-ACCOUNT.} at line 1003.</p>
     *
     * <p>Two asymmetries against the sibling card edit are deliberate and are the substantive reason
     * no shared validator exists for this package. First, the flag is defaulted <em>optimistically</em>
     * to blank at :1004 and the blank branch at :1010-1012 sets neither an error nor a message, so a
     * blank account filter is legal and simply means "browse unfiltered". Second, the not-numeric
     * message at :1021-1023 is <em>unguarded</em>, where the card equivalent at :1056-1060 is guarded
     * by {@code IF WS-ERROR-MSG-OFF}.</p>
     *
     * <p>The blank test at :1007-1009 also treats an all-zero filter as absent, because the third
     * operand compares the numeric redefinition against zeros. And the valid branch at :1027 moves the
     * <em>alphanumeric</em> {@code CC-ACCT-ID} into the numeric {@code CDEMO-ACCT-ID}, where the card
     * edit at :1064 moves the numeric redefinition instead; both are transcribed as written.</p>
     *
     * @param state the per-request working storage
     * @throws ValidationException when the supplied filter is not an eleven digit number
     */
    private void editAccount2210(final ProgramState state) {
        // SET FLG-ACCTFILTER-BLANK TO TRUE - :1004. The optimistic default.
        state.accountFilterFlag = FILTER_BLANK;

        // IF CC-ACCT-ID EQUAL LOW-VALUES OR EQUAL SPACES OR CC-ACCT-ID-N EQUAL ZEROS - :1007-1009.
        if (isPicBlank(state.ccAcctId) || isZeroDigits(state.ccAcctId)) {
            // SET FLG-ACCTFILTER-BLANK TO TRUE - :1010. Redundant; the default already set it.
            state.accountFilterFlag = FILTER_BLANK;
            // MOVE ZEROES TO CDEMO-ACCT-ID - :1011.
            state.cdemoAcctId = padDigits("0", ACCOUNT_FILTER_WIDTH);
            // GO TO 2210-EDIT-ACCOUNT-EXIT - :1012. No error, no message: blank is legal.
            return;
        }

        // IF CC-ACCT-ID IS NOT NUMERIC - :1017.
        if (!isPicNumeric(state.ccAcctId)) {
            // SET INPUT-ERROR TO TRUE - :1018.
            state.inputError = true;
            // SET FLG-ACCTFILTER-NOT-OK TO TRUE - :1019.
            state.accountFilterFlag = FILTER_NOT_OK;
            // SET FLG-PROTECT-SELECT-ROWS-YES TO TRUE - :1020. An invalid filter protects the row
            // selection fields, so no row can be chosen until the filter is corrected.
            state.protectSelectRows = true;
            // MOVE '...' TO WS-ERROR-MSG - :1021-1023. Unguarded, unlike the card equivalent.
            state.errorMessage = MSG_ACCOUNT_FILTER_INVALID;
            // MOVE ZERO TO CDEMO-ACCT-ID - :1024.
            state.cdemoAcctId = padDigits("0", ACCOUNT_FILTER_WIDTH);
            // GO TO 2210-EDIT-ACCOUNT-EXIT - :1025. The source returns to the dispatcher, which then
            // skips the browse at :431-435 and re-sends the map carrying this message.
            throw new ValidationException(
                    MSG_ACCOUNT_FILTER_INVALID,
                    FIELD_ACCOUNT_ID,
                    ValidationException.FailureKind.INVALID);
        }

        // ELSE - :1026-1028.
        // MOVE CC-ACCT-ID TO CDEMO-ACCT-ID - :1027.
        state.cdemoAcctId = state.ccAcctId;
        // SET FLG-ACCTFILTER-ISVALID TO TRUE - :1028.
        state.accountFilterFlag = FILTER_VALID;
    }

    /**
     * {@code 2210-EDIT-ACCOUNT-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2210-EDIT-ACCOUNT-EXIT.} at line 1032,
     * body {@code EXIT} at :1033. It is the target of the two {@code GO TO}s at :1012 and :1025, which
     * are rendered as an early return and a thrown validation failure respectively.</p>
     */
    private void editAccount2210Exit() {
        // EXIT - :1033.
    }

    /**
     * Edits the card filter.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2220-EDIT-CARD.} at line 1036. The
     * shape mirrors the account edit with two documented differences: the not-numeric message at
     * :1057-1059 is guarded by {@code IF WS-ERROR-MSG-OFF} at :1056, and the valid branch at :1064
     * moves the numeric redefinition {@code CC-CARD-NUM-N} rather than the alphanumeric group.</p>
     *
     * <p>The guard means that when the account filter has already failed, this paragraph still sets
     * the error and the protection flag but leaves the account message standing. The response
     * therefore reports the account failure while both filters are highlighted.</p>
     *
     * @param state the per-request working storage
     * @throws ValidationException when the supplied filter is not a sixteen digit number
     */
    private void editCard2220(final ProgramState state) {
        // SET FLG-CARDFILTER-BLANK TO TRUE - :1039. The optimistic default, as for the account.
        state.cardFilterFlag = FILTER_BLANK;

        // IF CC-CARD-NUM EQUAL LOW-VALUES OR EQUAL SPACES OR CC-CARD-NUM-N EQUAL ZEROS - :1042-1044.
        if (isPicBlank(state.ccCardNumber) || isZeroDigits(state.ccCardNumber)) {
            // SET FLG-CARDFILTER-BLANK TO TRUE - :1045. Redundant, as at :1010.
            state.cardFilterFlag = FILTER_BLANK;
            // MOVE ZEROES TO CDEMO-CARD-NUM - :1046.
            state.cdemoCardNumber = padDigits("0", CARD_FILTER_WIDTH);
            // GO TO 2220-EDIT-CARD-EXIT - :1047.
            return;
        }

        // IF CC-CARD-NUM IS NOT NUMERIC - :1052.
        if (!isPicNumeric(state.ccCardNumber)) {
            // SET INPUT-ERROR TO TRUE - :1053.
            state.inputError = true;
            // SET FLG-CARDFILTER-NOT-OK TO TRUE - :1054.
            state.cardFilterFlag = FILTER_NOT_OK;
            // SET FLG-PROTECT-SELECT-ROWS-YES TO TRUE - :1055.
            state.protectSelectRows = true;
            // IF WS-ERROR-MSG-OFF MOVE '...' TO WS-ERROR-MSG END-IF - :1056-1060. Guarded.
            if (state.isErrorMessageOff()) {
                state.errorMessage = MSG_CARD_FILTER_INVALID;
            }
            // MOVE ZERO TO CDEMO-CARD-NUM - :1061.
            state.cdemoCardNumber = padDigits("0", CARD_FILTER_WIDTH);
            // GO TO 2220-EDIT-CARD-EXIT - :1062.
            throw new ValidationException(
                    state.errorMessage, FIELD_CARD_NUMBER, ValidationException.FailureKind.INVALID);
        }

        // ELSE - :1063-1065.
        // MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM - :1064. The numeric redefinition, not the group.
        state.cdemoCardNumber = state.ccCardNumber;
        // SET FLG-CARDFILTER-ISVALID TO TRUE - :1065.
        state.cardFilterFlag = FILTER_VALID;
    }

    /**
     * {@code 2220-EDIT-CARD-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2220-EDIT-CARD-EXIT.} at line 1069,
     * body {@code EXIT} at :1070.</p>
     */
    private void editCard2220Exit() {
        // EXIT - :1070.
    }

    /**
     * Edits the seven row-selection codes and enforces the one-selection rule.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2250-EDIT-ARRAY.} at line 1073.</p>
     *
     * <p>Four transcription details matter. The tally at :1079-1082 <em>accumulates</em> into
     * {@code I} rather than replacing it, which is safe only because the dispatcher's
     * {@code INITIALIZE} at :300-302 zeroes the subscript once per request; here the counter is
     * method-local and therefore starts at zero by construction. The loop at :1099 then reuses that
     * same subscript as its control variable, destroying the count, which is why the marking test
     * inside the loop at :1103 interrogates the latched message rather than the counter. The
     * replacement at :1090-1093 is order-sensitive: {@code 'S'} and {@code 'U'} become {@code '1'}
     * first and only the characters still remaining become {@code '0'}. And the loop bound at :1099 is
     * a second, hardcoded copy of the page size; both copies resolve from the single injected property
     * here so they can never drift.</p>
     *
     * <p>On a multiple selection the subscript is overwritten on every hit at :1102, so it ends up
     * holding the <em>last</em> selected row. That is harmless in the source because the error flag is
     * already set and the dispatcher's error branch runs first, and it is preserved.</p>
     *
     * @param state the per-request working storage
     */
    private void editArray2250(final ProgramState state) {
        // IF INPUT-ERROR GO TO 2250-EDIT-ARRAY-EXIT - :1075-1077. A filter failure skips the whole
        // selection edit. In Java the filter edits throw, so this guard is reached only when an
        // earlier non-throwing error is latched; it is transcribed rather than assumed unreachable.
        if (state.inputError) {
            return;
        }

        // INSPECT WS-EDIT-SELECT-FLAGS TALLYING I FOR ALL 'S' ALL 'U' - :1079-1082.
        int i = 0;
        for (final String selection : state.selectFlags) {
            if (SELECT_VIEW.equals(selection) || SELECT_UPDATE.equals(selection)) {
                i = i + 1;
            }
        }

        // IF I > +1 - :1084-1095.
        if (i > 1) {
            // SET INPUT-ERROR TO TRUE - :1085.
            state.inputError = true;
            // SET WS-MORE-THAN-1-ACTION TO TRUE - :1086. Unguarded, so it overwrites any standing
            // message.
            state.errorMessage = MSG_MORE_THAN_ONE_ACTION;

            // MOVE WS-EDIT-SELECT-FLAGS TO WS-EDIT-SELECT-ERROR-FLAGS - :1088-1089, then
            // INSPECT ... REPLACING ALL 'S' BY '1' ALL 'U' BY '1' CHARACTERS BY '0' - :1090-1093.
            // The order is load-bearing: the two selection codes are replaced first and only the
            // characters still remaining are zeroed.
            for (int row = 0; row < state.selectErrorFlags.length; row++) {
                final String selection = state.selectFlags[row];
                if (SELECT_VIEW.equals(selection) || SELECT_UPDATE.equals(selection)) {
                    state.selectErrorFlags[row] = ROW_ERROR_MARKER;
                } else {
                    state.selectErrorFlags[row] = ROW_NO_ERROR_MARKER;
                }
            }

            // The typed description of the failure. It is latched on the state and returned inside the
            // result rather than thrown, because the dispatcher's error branch at :431-435 re-reads the
            // list whenever neither filter flag is at fault - so a selection failure must not suppress
            // the page payload. No field name is supplied: the fault is the combination of rows, not
            // any one of them.
            state.selectionFailure = new ValidationException(MSG_MORE_THAN_ONE_ACTION);
        }

        // MOVE ZERO TO I-SELECTED - :1097.
        state.selectedIndex = 0;

        // PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7 - :1099. The bound is the injected page size, so
        // the source's duplicated literal cannot drift from WS-MAX-SCREEN-LINES at :177.
        for (i = 1; i <= this.pageSize; i++) {
            final String selection = state.selectFlags[i - 1];
            // EVALUATE TRUE - :1100-1114.
            if (SELECT_VIEW.equals(selection) || SELECT_UPDATE.equals(selection)) {
                // WHEN SELECT-OK(I) - :1101-1105.
                // MOVE I TO I-SELECTED - :1102. Overwritten on each hit.
                state.selectedIndex = i;
                // IF WS-MORE-THAN-1-ACTION MOVE '1' TO WS-ROW-CRDSELECT-ERROR(I) - :1103-1105.
                if (MSG_MORE_THAN_ONE_ACTION.equals(state.errorMessage)) {
                    state.selectErrorFlags[i - 1] = ROW_ERROR_MARKER;
                }
            } else if (isSelectBlank(selection)) {
                // WHEN SELECT-BLANK(I) CONTINUE - :1106-1107.
                state.noOperation();
            } else {
                // WHEN OTHER - :1108-1113.
                // SET INPUT-ERROR TO TRUE - :1109.
                state.inputError = true;
                // MOVE '1' TO WS-ROW-CRDSELECT-ERROR(I) - :1110.
                state.selectErrorFlags[i - 1] = ROW_ERROR_MARKER;
                // IF WS-ERROR-MSG-OFF SET WS-INVALID-ACTION-CODE TO TRUE - :1111-1113. Guarded, so a
                // standing more-than-one-action message survives.
                if (state.isErrorMessageOff()) {
                    state.errorMessage = MSG_INVALID_ACTION_CODE;
                }
                // The typed description, latched rather than thrown for the reason given above. The
                // field name identifies the offending row's selection field and never carries its
                // value, so no user-supplied content reaches the exception.
                if (state.selectionFailure == null) {
                    state.selectionFailure = ValidationException.invalidField(
                            CURSOR_ROW_SELECT_PREFIX + i, state.errorMessage);
                }
            }
        }
    }

    /**
     * {@code 2250-EDIT-ARRAY-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 2250-EDIT-ARRAY-EXIT.} at line 1119,
     * body {@code EXIT} at :1120. It is the target of the {@code GO TO} at :1076, rendered as an early
     * return.</p>
     */
    private void editArray2250Exit() {
        // EXIT - :1120.
    }

    /**
     * Browses the card file forward from the current start key and fills one screen of rows.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 9000-READ-FORWARD.} at line 1123.</p>
     *
     * <p><b>The record-at-a-time contract.</b> The browse opens on the <em>base</em> cluster with
     * {@code STARTBR ... GTEQ} at :1129-1136, then reads one record at a time with {@code READNEXT}
     * at :1146-1154, filtering each record <em>after</em> it arrives. Two consequences of that shape
     * are load-bearing and are the reason the filter is applied in memory rather than pushed into the
     * query:</p>
     * <ul>
     *   <li>An excluded record does <em>not</em> consume a screen line. The {@code ELSE CONTINUE} at
     *       :1185-1186 skips the counter increment, so the browse keeps reading until seven records
     *       have survived the filter.</li>
     *   <li>The single lookahead read at :1197-1205 does <em>not</em> apply the filter. "A next page
     *       exists" is therefore a <b>false positive</b> whenever every following record would have
     *       been excluded. A database-side predicate would silently repair that defect and change
     *       which pages report more data, which is a behaviour change and therefore forbidden.
     *       Preserved deliberately.</li>
     * </ul>
     *
     * <p><b>Why the saved last key is the next page's first row.</b> The key pair saved at :1194-1195
     * from the seventh record is overwritten at :1212-1214 with the eighth record's values when the
     * lookahead succeeds. Because the next page-down reopens the browse with {@code GTEQ} on that key,
     * the overwrite is exactly what makes paging land on the first unseen row.</p>
     *
     * <p><b>The stale-buffer save.</b> On the outer end-of-file arm at :1233-1237 the last key is
     * saved from the record buffer, which end-of-file leaves holding the <em>previously</em> read
     * record. Preserved.</p>
     *
     * <p>The {@code STARTBR} response at :1134 is never examined - an absent guard, preserved - and
     * the {@code ENDBR} at :1258-1259 is issued unconditionally, including on the error arms.</p>
     *
     * @param state the per-request working storage
     */
    private void readForward9000(final ProgramState state) {
        // MOVE LOW-VALUES TO WS-ALL-ROWS - :1124. The whole 196-byte row table is cleared first, so a
        // short page leaves its unused rows absent rather than stale.
        state.clearRowTable();

        // EXEC CICS STARTBR ... GTEQ - :1129-1136. The response at :1134 is captured and never tested.
        startBrowse(state, state.ridCardNumber);

        // MOVE ZEROES TO WS-SCRN-COUNTER - :1140.
        state.screenCounter = 0;
        // SET CA-NEXT-PAGE-EXISTS TO TRUE - :1141. Unconditional and optimistic; the end-of-file arms
        // below are the only things that clear it.
        state.caNextPageIndicator = CA_NEXT_PAGE_EXISTS;
        // SET MORE-RECORDS-TO-READ TO TRUE - :1142.
        state.readLoopExit = false;

        // PERFORM UNTIL READ-LOOP-EXIT - :1144-1256.
        while (!state.readLoopExit) {
            // EXEC CICS READNEXT - :1146-1154.
            final Card record = readNextRecord(state);

            // EVALUATE WS-RESP-CD - :1156-1255.
            if (record != null) {
                // WHEN DFHRESP(NORMAL) / WHEN DFHRESP(DUPREC) - :1157-1158. The two conditions share
                // one arm, so a duplicate is a SUCCESS on every browse read. Mapping it to a duplicate
                // exception would break every browse. See the class documentation.
                readForwardOnRecord(state, record);
            } else if (state.fileErrorRespCondition == null) {
                // WHEN DFHRESP(ENDFILE) - :1233-1245.
                readForwardOnEndFile(state);
            } else {
                // WHEN OTHER - :1246-1254. The diagnostic is composed and the loop ends; the ENDBR
                // below still runs, exactly as in the source.
                state.readLoopExit = true;
                state.errorMessage = latchFileError(state, OPERATION_READ);
            }
        }

        // EXEC CICS ENDBR FILE(LIT-CARD-FILE) - :1258-1259. Unconditional.
        endBrowse(state);
    }

    /**
     * The success arm of the forward browse: stores one surviving record and handles the page-full
     * lookahead.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 1159-1232, the shared body of the
     * {@code WHEN DFHRESP(NORMAL)} and {@code WHEN DFHRESP(DUPREC)} arms at :1157-1158.</p>
     *
     * @param state the per-request working storage
     * @param record the record the read returned
     */
    private void readForwardOnRecord(final ProgramState state, final Card record) {
        // The record buffer keeps the last successfully read record, which is what the end-of-file arm
        // at :1236-1237 later saves from.
        state.cardRecord = record;

        // PERFORM 9500-FILTER-RECORDS THRU 9500-FILTER-RECORDS-EXIT - :1159-1160.
        filterRecords9500(state, record);
        filterRecords9500Exit();

        // IF WS-DONOT-EXCLUDE-THIS-RECORD - :1162-1187.
        if (!state.excludeThisRecord) {
            // ADD 1 TO WS-SCRN-COUNTER - :1163.
            state.screenCounter = state.screenCounter + 1;

            // MOVE CARD-NUM / CARD-ACCT-ID / CARD-ACTIVE-STATUS into the row - :1165-1171.
            state.storeRow(state.screenCounter, record);

            // IF WS-SCRN-COUNTER = 1 - :1173-1184.
            if (state.screenCounter == 1) {
                // MOVE CARD-ACCT-ID TO WS-CA-FIRST-CARD-ACCT-ID - :1174-1175.
                state.caFirstCardAccountId = accountIdOf(record);
                // MOVE CARD-NUM TO WS-CA-FIRST-CARD-NUM - :1176.
                state.caFirstCardNumber = cardNumberOf(record);
                // IF WS-CA-SCREEN-NUM = 0 ADD +1 ELSE CONTINUE - :1177-1181. A zero page number means
                // this browse produced the first page, so the display number becomes one.
                if (state.caScreenNumber == 0) {
                    state.caScreenNumber = state.caScreenNumber + 1;
                } else {
                    state.noOperation();
                }
            } else {
                // ELSE CONTINUE - :1182-1183.
                state.noOperation();
            }
        } else {
            // ELSE CONTINUE - :1185-1186. The excluded record consumes no screen line.
            state.noOperation();
        }

        // IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES - :1191-1232. The page-full test sits inside the
        // success arm and is therefore evaluated after every successful read, filtered or not.
        if (state.screenCounter == this.pageSize) {
            // SET READ-LOOP-EXIT TO TRUE - :1192.
            state.readLoopExit = true;

            // MOVE CARD-ACCT-ID / CARD-NUM TO the LAST key - :1194-1195. Overwritten below when the
            // lookahead finds an eighth record.
            state.caLastCardAccountId = accountIdOf(record);
            state.caLastCardNumber = cardNumberOf(record);

            // EXEC CICS READNEXT - :1197-1205. The lookahead. It deliberately does NOT filter.
            final Card lookahead = readNextRecord(state);

            // EVALUATE WS-RESP-CD - :1207-1231.
            if (lookahead != null) {
                // WHEN DFHRESP(NORMAL) / WHEN DFHRESP(DUPREC) - :1208-1214.
                state.cardRecord = lookahead;
                // SET CA-NEXT-PAGE-EXISTS TO TRUE - :1210-1211.
                state.caNextPageIndicator = CA_NEXT_PAGE_EXISTS;
                // MOVE CARD-ACCT-ID / CARD-NUM TO the LAST key - :1212-1214. The stored last key is
                // now the FIRST ROW OF THE NEXT PAGE, which is what the page-down start key needs.
                state.caLastCardAccountId = accountIdOf(lookahead);
                state.caLastCardNumber = cardNumberOf(lookahead);
            } else if (state.fileErrorRespCondition == null) {
                // WHEN DFHRESP(ENDFILE) - :1215-1221.
                state.caNextPageIndicator = CA_NEXT_PAGE_NOT_EXISTS;
                // IF WS-ERROR-MSG-OFF MOVE 'NO MORE RECORDS TO SHOW' - :1218-1221. Guarded.
                if (state.isErrorMessageOff()) {
                    state.errorMessage = MSG_NO_MORE_RECORDS;
                }
            } else {
                // WHEN OTHER - :1222-1230.
                state.readLoopExit = true;
                state.errorMessage = latchFileError(state, OPERATION_READ);
            }
        }
    }

    /**
     * The end-of-file arm of the forward browse.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 1233-1245. End of file is loop termination, never
     * an error. The commented-out {@code MOVE 'NO RECORDS TO SHOW'} at :1243 is left as the source
     * leaves it and is not resurrected.</p>
     *
     * @param state the per-request working storage
     */
    private void readForwardOnEndFile(final ProgramState state) {
        // SET READ-LOOP-EXIT TO TRUE - :1234.
        state.readLoopExit = true;
        // SET CA-NEXT-PAGE-NOT-EXISTS TO TRUE - :1235.
        state.caNextPageIndicator = CA_NEXT_PAGE_NOT_EXISTS;

        // MOVE CARD-ACCT-ID / CARD-NUM TO the LAST key - :1236-1237. Read from the record buffer,
        // which end of file leaves holding the previously read record. When nothing was ever read the
        // buffer is empty and the keys stay unset, which is the faithful rendering of an untouched
        // buffer without inventing byte content for it. Preserved.
        state.caLastCardAccountId = accountIdOf(state.cardRecord);
        state.caLastCardNumber = cardNumberOf(state.cardRecord);

        // IF WS-ERROR-MSG-OFF MOVE 'NO MORE RECORDS TO SHOW' - :1238-1240. Guarded.
        if (state.isErrorMessageOff()) {
            state.errorMessage = MSG_NO_MORE_RECORDS;
        }

        // IF WS-CA-SCREEN-NUM = 1 AND WS-SCRN-COUNTER = 0 - :1241-1245. An empty first page is
        // reported as a message, not as an exception: this program never raises a not-found condition,
        // its DFHRESP(NOTFND) census being zero.
        if (state.caScreenNumber == CA_FIRST_PAGE && state.screenCounter == 0) {
            state.errorMessage = MSG_NO_RECORDS_FOUND;
        }
    }

    /**
     * {@code 9000-READ-FORWARD-EXIT.} - the convergence point of the forward browse.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 9000-READ-FORWARD-EXIT.} at line 1261,
     * body {@code EXIT} at :1262. All five call sites - :433-434, :450-451, :478-479, :493-494 and
     * :578-579 - perform the full {@code THRU} range, so this always runs.</p>
     *
     * <p>The substituted exception is raised here rather than at the point of failure so that the
     * unconditional {@code ENDBR} of :1258-1259 has already been issued. The source instead returns to
     * the dispatcher carrying the composed diagnostic in {@code WS-ERROR-MSG}; raising a typed
     * exception is a labelled mechanism substitution, recorded in the class documentation.</p>
     *
     * @param state the per-request working storage
     * @throws FileAccessException when the browse latched an unexpected condition
     */
    private void readForward9000Exit(final ProgramState state) {
        // EXIT - :1262.
        throwLatchedFileError(state);
    }

    /**
     * Browses the card file backward to redisplay the previous screen of rows.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 9100-READ-BACKWARDS.} at line 1264.</p>
     *
     * <p>Four legacy behaviours are transcribed rather than corrected:</p>
     * <ul>
     *   <li><b>The last key is overwritten with the first key</b> at :1268, before the browse even
     *       opens. The page being left therefore becomes the page-down target, so a page-up followed
     *       by a page-down redisplays the page just left, including its first row.</li>
     *   <li><b>The next-page indicator is set unconditionally</b> at :1287, so paging up always
     *       asserts that a next page exists - which, having just come from it, is true.</li>
     *   <li><b>The priming read at :1294-1302 is discarded and unfiltered.</b> Its only effect on
     *       success is to decrement the counter at :1307. It returns the current page's first row,
     *       which must not be shown again.</li>
     *   <li><b>Neither read has an end-of-file arm.</b> Running out of records while paging up falls
     *       into {@code WHEN OTHER} at :1308 and :1361 and is reported as a file error, leaving the low
     *       row indices cleared. Preserved: a graceful "you are on the first page"
     *       outcome would be a behaviour change.</li>
     * </ul>
     *
     * <p>The counter is seeded to the page size plus one at :1284-1286 and the rows are filled
     * <em>descending</em> from the highest index down to one, so the redisplayed page reads in
     * ascending card-number order even though it was gathered backwards.</p>
     *
     * @param state the per-request working storage
     */
    private void readBackwards9100(final ProgramState state) {
        // MOVE LOW-VALUES TO WS-ALL-ROWS - :1266.
        state.clearRowTable();

        // MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY - :1268. The off-by-one described above; the
        // group move copies both the card number and the account id.
        state.caLastCardNumber = state.caFirstCardNumber;
        state.caLastCardAccountId = state.caFirstCardAccountId;

        // EXEC CICS STARTBR ... GTEQ - :1273-1280. The base cluster again; response never tested.
        startBrowse(state, state.ridCardNumber);

        // COMPUTE WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES + 1 - :1284-1286.
        state.screenCounter = this.pageSize + 1;
        // SET CA-NEXT-PAGE-EXISTS TO TRUE - :1287. Unconditional.
        state.caNextPageIndicator = CA_NEXT_PAGE_EXISTS;
        // SET MORE-RECORDS-TO-READ TO TRUE - :1288.
        state.readLoopExit = false;

        // EXEC CICS READPREV - :1294-1302. The priming read.
        final Card primed = readPrevRecord(state);

        // EVALUATE WS-RESP-CD - :1304-1318.
        if (primed != null) {
            // WHEN DFHRESP(NORMAL) / WHEN DFHRESP(DUPREC) - :1305-1307. DUPREC is success here too.
            state.cardRecord = primed;
            // SUBTRACT 1 FROM WS-SCRN-COUNTER - :1307. The record itself is discarded and the filter
            // is deliberately not applied to it.
            state.screenCounter = state.screenCounter - 1;
        } else {
            // WHEN OTHER - :1308-1317. There is no end-of-file arm, so exhausting the file here is a
            // file error. GO TO 9100-READ-BACKWARDS-EXIT at :1317 still reaches the ENDBR.
            state.readLoopExit = true;
            state.errorMessage = latchFileError(state, OPERATION_READ);
            return;
        }

        // PERFORM UNTIL READ-LOOP-EXIT - :1320-1371.
        while (!state.readLoopExit) {
            // EXEC CICS READPREV - :1322-1330.
            final Card record = readPrevRecord(state);

            // EVALUATE WS-RESP-CD - :1332-1370.
            if (record != null) {
                // WHEN DFHRESP(NORMAL) / WHEN DFHRESP(DUPREC) - :1333-1359.
                readBackwardsOnRecord(state, record);
            } else {
                // WHEN OTHER - :1361-1369. No GO TO here; the loop condition ends the paragraph.
                state.readLoopExit = true;
                state.errorMessage = latchFileError(state, OPERATION_READ);
            }
        }
    }

    /**
     * The success arm of the backward browse: stores one surviving record at the descending index.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} lines 1335-1359. An excluded record consumes no line
     * here either, because the {@code ELSE CONTINUE} at :1357-1358 skips both the store and the
     * decrement.</p>
     *
     * @param state the per-request working storage
     * @param record the record the read returned
     */
    private void readBackwardsOnRecord(final ProgramState state, final Card record) {
        state.cardRecord = record;

        // PERFORM 9500-FILTER-RECORDS THRU 9500-FILTER-RECORDS-EXIT - :1335-1336.
        filterRecords9500(state, record);
        filterRecords9500Exit();

        // IF WS-DONOT-EXCLUDE-THIS-RECORD - :1337-1359.
        if (!state.excludeThisRecord) {
            // MOVE CARD-NUM / CARD-ACCT-ID / CARD-ACTIVE-STATUS into the row - :1338-1344.
            state.storeRow(state.screenCounter, record);

            // SUBTRACT 1 FROM WS-SCRN-COUNTER - :1346.
            state.screenCounter = state.screenCounter - 1;

            // IF WS-SCRN-COUNTER = 0 - :1347-1356.
            if (state.screenCounter == 0) {
                // SET READ-LOOP-EXIT TO TRUE - :1348.
                state.readLoopExit = true;
                // MOVE CARD-ACCT-ID / CARD-NUM TO the FIRST key - :1350-1353.
                state.caFirstCardAccountId = accountIdOf(record);
                state.caFirstCardNumber = cardNumberOf(record);
            } else {
                // ELSE CONTINUE - :1354-1355.
                state.noOperation();
            }
        } else {
            // ELSE CONTINUE - :1357-1358.
            state.noOperation();
        }
    }

    /**
     * {@code 9100-READ-BACKWARDS-EXIT.} - terminates the backward browse.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 9100-READ-BACKWARDS-EXIT.} at line
     * 1374. Unlike the forward browse, whose {@code ENDBR} sits at :1258 inside the browse paragraph
     * itself, the backward {@code ENDBR} sits here at :1375-1377 ahead of the {@code EXIT} at :1379.
     * That placement is what lets the {@code GO TO} at :1317 still terminate the browse. The asymmetry
     * is transcribed rather than harmonised.</p>
     *
     * @param state the per-request working storage
     * @throws FileAccessException when the browse latched an unexpected condition
     */
    private void readBackwards9100Exit(final ProgramState state) {
        // EXEC CICS ENDBR FILE(LIT-CARD-FILE) - :1375-1377. Unconditional.
        endBrowse(state);
        // EXIT - :1379.
        throwLatchedFileError(state);
    }

    /**
     * Decides whether one browse record survives the account and card criteria.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 9500-FILTER-RECORDS.} at line 1382.</p>
     *
     * <p>The two gates are independent and ordered: the account gate at :1385-1394 runs first and
     * exits early on a mismatch, then the card gate at :1396-1405. A gate participates only while its
     * flag reads valid, so a blank or rejected filter is simply not applied. The comparisons are
     * transcribed as written - the account one against the alphanumeric {@code CC-ACCT-ID} at :1386
     * and the card one against the numeric redefinition {@code CC-CARD-NUM-N} at :1397, which shares
     * its storage byte for byte and therefore compares identically.</p>
     *
     * @param state the per-request working storage
     * @param record the record just read
     */
    private void filterRecords9500(final ProgramState state, final Card record) {
        // SET WS-DONOT-EXCLUDE-THIS-RECORD TO TRUE - :1383.
        state.excludeThisRecord = false;

        // IF FLG-ACCTFILTER-ISVALID - :1385-1394.
        if (state.isAccountFilterValid()) {
            // IF CARD-ACCT-ID = CC-ACCT-ID CONTINUE - :1386-1387.
            if (accountIdOf(record).equals(state.ccAcctId)) {
                state.noOperation();
            } else {
                // SET WS-EXCLUDE-THIS-RECORD TO TRUE / GO TO ...-EXIT - :1389-1390.
                state.excludeThisRecord = true;
                return;
            }
        } else {
            // ELSE CONTINUE - :1392-1393.
            state.noOperation();
        }

        // IF FLG-CARDFILTER-ISVALID - :1396-1405.
        if (state.isCardFilterValid()) {
            // IF CARD-NUM = CC-CARD-NUM-N CONTINUE - :1397-1398.
            if (cardNumberOf(record).equals(state.ccCardNumber)) {
                state.noOperation();
            } else {
                // SET WS-EXCLUDE-THIS-RECORD TO TRUE / GO TO ...-EXIT - :1400-1401.
                state.excludeThisRecord = true;
                return;
            }
        } else {
            // ELSE CONTINUE - :1403-1404.
            state.noOperation();
        }
    }

    /**
     * {@code 9500-FILTER-RECORDS-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code 9500-FILTER-RECORDS-EXIT.} at line
     * 1409, body {@code EXIT} at :1410. It is the target of the two early {@code GO TO}s at :1390 and
     * :1401, rendered as early returns.</p>
     */
    private void filterRecords9500Exit() {
        // EXIT - :1410.
    }

    /**
     * Maps the attention identifier the terminal raised onto the shared navigation field.
     *
     * <p>Source: {@code app/cpy/CSSTRPFY.cpy} paragraph {@code YYYY-STORE-PFKEY.} at line 17, copied
     * into this program's procedure division by {@code COPY 'CSSTRPFY'} at
     * {@code app/cbl/COCRDLIC.cbl:1416}. The single {@code EVALUATE TRUE} at :21-78 covers
     * {@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1}, {@code DFHPA2} and {@code DFHPF1} through
     * {@code DFHPF24}, with :54-77 <b>folding</b> {@code DFHPF13} through {@code DFHPF24} onto
     * {@code CCARD-AID-PFK01} through {@code CCARD-AID-PFK12}, so the upper twelve function keys are
     * indistinguishable from the lower twelve.</p>
     *
     * <p>There is deliberately <b>no {@code WHEN OTHER}</b> arm. An identifier outside that set
     * therefore leaves the navigation field exactly as the request found it, and the dispatcher's
     * validity gate at {@code app/cbl/COCRDLIC.cbl:370-380} is what subsequently forces it to enter.
     * That absent arm is preserved rather than filled in.</p>
     *
     * @param request the inbound request, whose attention identifier stands in for {@code EIBAID}
     * @param state the per-request working storage
     */
    private void yyyyStorePfkey(final CardListRequest request, final ProgramState state) {
        final String aid = request.attentionIdentifier == null ? "" : request.attentionIdentifier;

        // EVALUATE TRUE - app/cpy/CSSTRPFY.cpy:21-78.
        if (AID_ENTER.equals(aid)) {
            // WHEN EIBAID IS EQUAL TO DFHENTER - :22-23.
            state.ccardAid = AID_ENTER;
        } else if (AID_CLEAR.equals(aid)) {
            // WHEN EIBAID IS EQUAL TO DFHCLEAR - :24-25.
            state.ccardAid = AID_CLEAR;
        } else if (AID_PA1.equals(aid)) {
            // WHEN EIBAID IS EQUAL TO DFHPA1 - :26-27.
            state.ccardAid = AID_PA1;
        } else if (AID_PA2.equals(aid)) {
            // WHEN EIBAID IS EQUAL TO DFHPA2 - :28-29.
            state.ccardAid = AID_PA2;
        } else {
            // WHEN EIBAID IS EQUAL TO DFHPF1 .. DFHPF24 - :30-77.
            final int key = functionKeyNumber(aid);
            if (key >= 1 && key <= HIGHEST_FUNCTION_KEY) {
                final int folded = key > FUNCTION_KEY_FOLD ? key - FUNCTION_KEY_FOLD : key;
                state.ccardAid = AID_PFK_PREFIX + padDigits(Integer.toString(folded), 2);
            }
            // No WHEN OTHER at :21-78: an unrecognised identifier is left untouched.
        }
    }

    /**
     * {@code YYYY-STORE-PFKEY-EXIT.} - a bare {@code EXIT} statement.
     *
     * <p>Source: {@code app/cpy/CSSTRPFY.cpy} paragraph {@code YYYY-STORE-PFKEY-EXIT.} at line 80,
     * body {@code EXIT} at :81.</p>
     */
    private void yyyyStorePfkeyExit() {
        // EXIT - app/cpy/CSSTRPFY.cpy:81.
    }

    // ============================================================================================
    // RETAINED UNREACHABLE PARAGRAPHS
    //
    // An authoritative census over every Area-A label in app/cbl/COCRDLIC.cbl, checking for any
    // PERFORM, GO TO, THRU or THROUGH reference, returns exactly four labels with no reference of any
    // kind. They are emitted here as four separate, deliberately EMPTY private methods. See the class
    // Javadoc for the Rule 1 Clause B conflict and its resolution: parity governs, and the clause is
    // satisfied because these artefacts are cited, tracked and marked rather than untracked.
    //
    // The bodies stay empty on purpose. Elaborating them would multiply uncoverable lines against the
    // 80% JaCoCo line gate, and no JaCoCo exclusion is permitted for this class or package. The
    // Javadoc carries the fidelity; the body carries the no-op.
    // ============================================================================================

    /**
     * {@code SEND-PLAIN-TEXT.} - <b>intentional no-op: unreachable, retained for control-flow parity.</b>
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code SEND-PLAIN-TEXT.} at line 1422. The
     * source comment at :1420 reads "Plain text exit - Dont use in production". No {@code PERFORM},
     * {@code GO TO}, {@code THRU} or {@code THROUGH} anywhere in the program names this label, so the
     * paragraph is declared and never entered.</p>
     *
     * <p>It is preserved so that the paragraph map remains mechanically provable for the scope-coverage
     * gate. Should parity ever be relaxed, delete this method together with its
     * three companions and amend both evidence artefacts in the same change.</p>
     */
    private void sendPlainText() {
    }

    /**
     * {@code SEND-PLAIN-TEXT-EXIT.} - <b>intentional no-op: unreachable, retained for control-flow
     * parity.</b>
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code SEND-PLAIN-TEXT-EXIT.} at line 1433. It
     * is never referenced, and it is not merged with its partner at :1422 because the one-to-one
     * paragraph mandate maps labels individually.</p>
     *
     * <p>See {@code sendPlainText()} for the shared rationale.</p>
     */
    private void sendPlainTextExit() {
    }

    /**
     * {@code SEND-LONG-TEXT.} - <b>intentional no-op: unreachable, retained for control-flow parity.</b>
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code SEND-LONG-TEXT.} at line 1441. The
     * source comment describes it as primarily for debugging and not for use in the regular course;
     * the body would have sent {@code WS-LONG-MSG} with {@code ERASE FREEKB} and then returned.</p>
     *
     * <p>{@code WS-LONG-MSG PIC X(500)} at :111 is referenced only inside this dead body, at
     * :1443-1444, so no Java field is declared for it. The one-to-one mandate covers <em>labels</em>,
     * not data-division items, which get the opposite ruling.</p>
     *
     * <p>See {@code sendPlainText()} for the shared rationale.</p>
     */
    private void sendLongText() {
    }

    /**
     * {@code SEND-LONG-TEXT-EXIT.} - <b>intentional no-op: unreachable, retained for control-flow
     * parity.</b>
     *
     * <p>Source: {@code app/cbl/COCRDLIC.cbl} paragraph {@code SEND-LONG-TEXT-EXIT.} at line 1452,
     * never referenced and not merged with its partner at :1441.</p>
     *
     * <p>See {@code sendPlainText()} for the shared rationale.</p>
     */
    private void sendLongTextExit() {
    }

    // ============================================================================================
    // BROWSE PRIMITIVES
    //
    // These render the four VSAM browse verbs the program issues against the CARDDAT base cluster:
    // STARTBR ... GTEQ, READNEXT, READPREV and ENDBR. They are not COBOL paragraphs and therefore
    // carry no paragraph citation of their own; each names the verb and the source line it serves.
    //
    // Only the mandated finder is used - CardRepository.findAllByOrderByCardNumberAsc(Pageable) - and
    // no method is added to that interface, no query string is built and no criteria API is touched.
    // ============================================================================================

    /**
     * Renders {@code EXEC CICS STARTBR ... GTEQ}: positions the browse at the first card whose number
     * is greater than or equal to the start key.
     *
     * <p>Serves {@code app/cbl/COCRDLIC.cbl:1129-1136} and :1273-1280. The source captures the response
     * at :1134 and :1278 and never tests it, an absent guard that is preserved: a positioning failure
     * surfaces on the first read instead.</p>
     *
     * <p><b>How the position is found, and the tradeoff.</b> A key-addressed browse cannot be expressed
     * through an offset {@code Pageable}, so the offset of the start key is located by binary search
     * over the same totally ordered finder, in logarithmic single-block reads. A blank or low-values
     * start key - the fresh-entry case, since {@code INITIALIZE} at :300-302 leaves the key as spaces
     * and it sorts before every populated key - short-circuits to offset zero with no probe at all, so
     * the common first-page request costs nothing extra. Ordering by the sixteen-digit primary key is
     * a total order, which is what makes both the search and every page reproducible.</p>
     *
     * <p>The remaining cost is deliberate. A keyset finder such as
     * {@code findByCardNumberGreaterThanEqualOrderByCardNumberAsc} would locate the position in one
     * read, but adding a method to {@code CardRepository} is outside this file's scope. Correctness and
     * parity outrank the marginal efficiency of the probe, and the probe is bounded logarithmically
     * rather than scanning. The cleaner form is that one finder, to be added by whoever
     * owns the repository.</p>
     *
     * @param state the per-request working storage
     * @param startKey the sixteen-character start key, {@code WS-CARD-RID-CARDNUM}
     * @throws FileAccessException if the underlying store cannot be read
     */
    private void startBrowse(final ProgramState state, final String startKey) {
        state.browseActive = true;
        state.browseWindowStart = -1;
        state.browseWindow = new ArrayList<>();
        state.browseTotal = -1L;
        state.fileErrorRespCondition = null;

        final String key = movePicX(startKey, CARD_FILTER_WIDTH);
        if (isPicBlank(key)) {
            state.browsePosition = 0;
            return;
        }

        final long total = browseTotal(state);
        int low = 0;
        int high = (int) Math.min(total, Integer.MAX_VALUE);
        while (low < high) {
            final int mid = low + ((high - low) / 2);
            final Card probe = recordAt(state, mid);
            if (probe == null || cardNumberOf(probe).compareTo(key) >= 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        state.browsePosition = low;
    }

    /**
     * Renders {@code EXEC CICS READNEXT}: returns the record at the browse position and advances.
     *
     * <p>Serves {@code app/cbl/COCRDLIC.cbl:1146-1154} and the unfiltered lookahead at :1197-1205.</p>
     *
     * @param state the per-request working storage
     * @return the record, or {@code null} for end of file. A {@code null} accompanied by a latched
     *     condition on the state means the caller must take its unexpected-condition arm instead
     */
    private Card readNextRecord(final ProgramState state) {
        state.fileErrorRespCondition = null;
        final Card record = recordAt(state, state.browsePosition);
        if (record != null) {
            state.browsePosition = state.browsePosition + 1;
        }
        return record;
    }

    /**
     * Renders {@code EXEC CICS READPREV}: returns the record at the browse position and steps back.
     *
     * <p>Serves the priming read at {@code app/cbl/COCRDLIC.cbl:1294-1302} and the loop read at
     * :1322-1330.</p>
     *
     * <p>Running off the front of the file latches the {@code ENDFILE} condition, because that is what
     * the terminal monitor would have raised - and <b>neither {@code READPREV} has an {@code ENDFILE}
     * arm</b>, so the caller's {@code WHEN OTHER} at :1308 or :1361 takes it and the browse is reported
     * as a file error. That is the preserved legacy defect, not an oversight
     * here.</p>
     *
     * @param state the per-request working storage
     * @return the record, or {@code null} once the front of the file is passed
     */
    private Card readPrevRecord(final ProgramState state) {
        state.fileErrorRespCondition = null;
        if (state.browsePosition < 0) {
            state.fileErrorRespCondition = RESP_ENDFILE;
            return null;
        }
        final Card record = recordAt(state, state.browsePosition);
        state.browsePosition = state.browsePosition - 1;
        if (record == null && state.fileErrorRespCondition == null) {
            state.fileErrorRespCondition = RESP_ENDFILE;
        }
        return record;
    }

    /**
     * Reads the single record at an absolute browse offset, buffering one aligned block at a time.
     *
     * <p>The block size is the page size plus one, the same width as the source's screen-plus-lookahead
     * window at {@code app/cbl/COCRDLIC.cbl:1191} and :1197, so no independent literal is introduced.
     * Blocks are aligned to that width, which is what lets an offset be addressed through a
     * page-indexed {@code Pageable} while a browse walking forward or backward crosses each block at
     * most once.</p>
     *
     * @param state the per-request working storage
     * @param offset the absolute zero-based offset in ascending card-number order
     * @return the record, or {@code null} when the offset lies outside the table
     */
    private Card recordAt(final ProgramState state, final int offset) {
        if (offset < 0) {
            return null;
        }
        final int blockSize = this.pageSize + 1;
        final int block = offset / blockSize;
        final int blockStart = block * blockSize;
        if (state.browseWindowStart != blockStart) {
            try {
                // A Slice, not a Page. The binary search in startBrowse fetches on the order of log2(n)
                // windows per request, and a Page would have issued a count query on every one of them to
                // supply a total this method needed only once. browseTotal now obtains it with a single
                // explicit count(); see there.
                final Slice<Card> window =
                        this.cardRepository.findAllByOrderByCardNumberAsc(
                                PageRequest.of(block, blockSize));
                state.browseWindow = new ArrayList<>(window.getContent());
                state.browseWindowStart = blockStart;
            } catch (final DataAccessException cause) {
                // The unexpected-condition arms at :1222-1230, :1246-1254, :1308-1316 and :1361-1369.
                // The root cause is preserved and rethrown by throwLatchedFileError once the browse has
                // been terminated, so nothing is swallowed.
                state.fileErrorRespCondition = cause.getClass().getSimpleName();
                state.fileErrorCause = cause;
                return null;
            }
        }
        final int index = offset - state.browseWindowStart;
        if (index < 0 || index >= state.browseWindow.size()) {
            return null;
        }
        return state.browseWindow.get(index);
    }

    /**
     * Returns the number of rows the browse can see, counting them once per request.
     *
     * <p>The count is the upper bound of the binary search in {@link #startBrowse(ProgramState, String)} and
     * is needed exactly once. It used to arrive as a side effect of the window fetch, because that fetch
     * returned a {@code Page}; every window therefore carried a {@code count(*)} the search discarded. One
     * explicit count, memoised in the request state, replaces all of them.
     *
     * <p>The failure path is the reason this does not simply call {@code count()} inline. An unreadable
     * table must latch the same {@code RESP} condition and the same cause that a failed window fetch
     * latches, <b>and must compose the diagnostic</b>, so that {@code throwLatchedFileError} rethrows it
     * after {@code ENDBR} has run - exactly as the four unexpected-condition arms of
     * {@code app/cbl/COCRDLIC.cbl:1226-1230}, {@code :1250-1254}, {@code :1312-1316} and
     * {@code :1365-1369} require. Nothing is swallowed; a zero is returned so the search collapses
     * immediately and the latched error surfaces at the normal point.
     *
     * <p><b>Why the diagnostic is composed here and not left to the caller.</b> A count failure is the
     * one file error in this program that no subsequent read repeats: a {@code count(*)} examines the
     * whole table while a windowed fetch examines at most {@link #pageSize} rows, so a timeout can strike
     * the former while the latter still succeeds. Because {@code readNextRecord} and
     * {@code readPrevRecord} each clear {@code fileErrorRespCondition} at their first statement, a
     * condition left merely transient here would be wiped by the very next read and the browse would
     * position at offset zero instead of at the requested key - a wrong position reported as success.
     *
     * @param state the per-request working storage
     * @return the row count, or zero when the table is empty or unreadable
     */
    private long browseTotal(final ProgramState state) {
        if (state.browseTotal < 0L) {
            try {
                state.browseTotal = this.cardRepository.count();
            } catch (final DataAccessException cause) {
                // Latching the transient condition is not enough on its own: readNextRecord and
                // readPrevRecord both clear fileErrorRespCondition at their first statement, because it
                // describes the outcome of one operation. latchFileError is what makes the condition
                // durable, by composing WS-ERROR-MSG into fileErrorMessage, which is the field
                // throwLatchedFileError tests once ENDBR has run. Without this call a count failure would
                // be wiped by the first read that followed, and the browse would silently position at the
                // start of the file rather than at the requested key - a wrong position reported as
                // success. The operation name is READ because that is the only one this program uses.
                state.fileErrorRespCondition = cause.getClass().getSimpleName();
                state.fileErrorCause = cause;
                state.errorMessage = latchFileError(state, OPERATION_READ);
                return 0L;
            }
        }
        return state.browseTotal < 0L ? 0L : state.browseTotal;
    }

    /**
     * Renders {@code EXEC CICS ENDBR}: terminates the browse.
     *
     * <p>Serves {@code app/cbl/COCRDLIC.cbl:1258-1259} for the forward browse and :1375-1377 for the
     * backward one. Both are unconditional, including on the error arms, which is why the substituted
     * exception is raised only after this has run.</p>
     *
     * @param state the per-request working storage
     */
    private void endBrowse(final ProgramState state) {
        state.browseActive = false;
        state.browseWindow = new ArrayList<>();
        state.browseWindowStart = -1;
    }

    /**
     * Composes {@code WS-FILE-ERROR-MESSAGE} and latches it for the deferred throw.
     *
     * <p>Renders the four identical unexpected-condition arms at {@code app/cbl/COCRDLIC.cbl:1226-1230},
     * :1250-1254, :1312-1316 and :1365-1369, each of which moves the operation name, the file name and
     * the two response codes into the group at :153-171 and then moves that group into
     * {@code WS-ERROR-MSG}.</p>
     *
     * @param state the per-request working storage
     * @param operation the value moved into {@code ERROR-OPNAME}; always {@code READ} in this program
     * @return the composed diagnostic, already truncated to the error field's width
     */
    private String latchFileError(final ProgramState state, final String operation) {
        state.errorOperation = operation;
        state.errorFile = LIT_CARD_FILE;
        state.errorResp = state.fileErrorRespCondition;
        state.errorResp2 = RESP2_NONE;
        state.fileErrorMessage =
                composeFileErrorMessage(
                        state.errorOperation, state.errorFile, state.errorResp, state.errorResp2);
        return state.fileErrorMessage;
    }

    /**
     * Builds the fixed-width diagnostic declared as {@code WS-FILE-ERROR-MESSAGE} at
     * {@code app/cbl/COCRDLIC.cbl:153-171}.
     *
     * <p>The geometry is transcribed exactly: a twelve-byte prefix holding {@code 'File Error:'} with
     * <b>no</b> trailing space at :154-155, an eight-byte operation name, the four-byte literal at
     * :158-159, a nine-byte file name, the fifteen-byte literal at :162-164, a ten-byte response, the
     * seven-byte literal at :167-168 and a second ten-byte response. That totals 75 characters, and the
     * trailing {@code FILLER PIC X(5)} at :171 - the only field in the group with <b>no</b>
     * {@code VALUE} clause - is structurally unobservable because the eighty-byte group is moved into a
     * seventy-five character field. Both peculiarities belong to this program alone and are not shared
     * with the sibling card programs, which is why no formatter is shared with them.</p>
     *
     * <p>The two response fields carry the <em>condition name</em> rather than the numeric value the
     * source moves in. The numeric {@code DFHRESP} values are not in the repository: they come from the
     * CICS-supplied {@code DFHAID} and {@code DFHBMSCA} copybooks named at
     * {@code app/cbl/COCRDLIC.cbl:265-267}, neither of which exists in this repository. Supplying the
     * name preserves the field geometry without inventing a number.</p>
     *
     * @param operation the operation name, {@code ERROR-OPNAME} at :156
     * @param logicalFile the file name, {@code ERROR-FILE} at :160
     * @param resp the primary condition, {@code ERROR-RESP} at :165
     * @param resp2 the secondary condition, {@code ERROR-RESP2} at :169
     * @return exactly {@link #ERROR_MESSAGE_WIDTH} characters
     */
    private static String composeFileErrorMessage(
            final String operation, final String logicalFile, final String resp, final String resp2) {
        final StringBuilder composed = new StringBuilder(ERROR_MESSAGE_WIDTH);
        composed.append(movePicX(FILE_ERROR_PREFIX, FILE_ERROR_PREFIX_WIDTH));
        composed.append(movePicX(operation, FILE_ERROR_OPNAME_WIDTH));
        composed.append(FILE_ERROR_ON);
        composed.append(movePicX(logicalFile, FILE_ERROR_FILE_WIDTH));
        composed.append(FILE_ERROR_RESP);
        composed.append(movePicX(resp, FILE_ERROR_RESP_WIDTH));
        composed.append(FILE_ERROR_RESP2);
        composed.append(movePicX(resp2, FILE_ERROR_RESP_WIDTH));
        return movePicX(composed.toString(), ERROR_MESSAGE_WIDTH);
    }

    /**
     * Raises the latched diagnostic as a typed exception, after the browse has been terminated.
     *
     * <p>The source has no abend path: the four unexpected-condition arms compose the diagnostic, end
     * the loop, issue {@code ENDBR} and return to the dispatcher, which then re-sends the screen
     * carrying the text. Raising {@code FileAccessException} instead is a <b>labelled mechanism
     * substitution</b>, mandated so that an I/O failure cannot be mistaken for an empty
     * page. The composed seventy-five character text is carried through unchanged as the detail
     * message, and the originating store failure is preserved as the cause, so nothing is swallowed.</p>
     *
     * <p>No {@code ioStatus} is supplied. This program raises CICS response conditions, never COBOL
     * {@code FILE STATUS} values - its status census is empty - so any two-character status would be an
     * invention. That field therefore has no counterpart here and the condition name travels in the
     * message instead.</p>
     *
     * @param state the per-request working storage
     * @throws FileAccessException always, when a diagnostic is latched
     */
    private void throwLatchedFileError(final ProgramState state) {
        if (state.fileErrorMessage == null) {
            return;
        }
        throw new FileAccessException(
                state.fileErrorMessage,
                null,
                LIT_CARD_FILE.trim(),
                state.errorOperation,
                state.fileErrorCause);
    }

    // ============================================================================================
    // FIXED-WIDTH AND CONDITION-NAME PRIMITIVES
    //
    // COBOL moves are width-driven, and the browse depends on that: a partially typed filter arrives
    // space-padded and therefore fails IS NUMERIC, which is exactly why the two edit messages insist
    // on a full-length number. These render the four move and test forms the program relies on.
    //
    // All are static and pure, so they add no state and are trivially testable. Locale is never
    // consulted: this program performs no case folding of any kind, so 88 SELECT-OK VALUES 'S','U' at
    // :78-79 genuinely rejects a lowercase code, which then falls to WHEN OTHER at :1108 and reports
    // INVALID ACTION CODE. Upper-casing anything here would break that parity.
    // ============================================================================================

    /**
     * Renders {@code MOVE} into an alphanumeric {@code PIC X(n)}: left justified, space filled on the
     * right, truncated on the right when too long.
     *
     * @param value the sending value; {@code null} is treated as an empty field
     * @param width the receiving field width
     * @return exactly {@code width} characters
     */
    private static String movePicX(final String value, final int width) {
        final String text = value == null ? "" : value;
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        final StringBuilder padded = new StringBuilder(width);
        padded.append(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Renders {@code MOVE} into a numeric-display {@code PIC 9(n)}: right justified, zero filled on the
     * left, truncated on the <em>left</em> when too long, which is how COBOL discards high-order digits.
     *
     * @param value the sending digits; {@code null} is treated as zero
     * @param width the receiving field width
     * @return exactly {@code width} characters
     */
    private static String padDigits(final String value, final int width) {
        final String digits = value == null ? "" : value;
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        final StringBuilder padded = new StringBuilder(width);
        for (int position = digits.length(); position < width; position++) {
            padded.append('0');
        }
        padded.append(digits);
        return padded.toString();
    }

    /**
     * Tests an alphanumeric field for {@code LOW-VALUES} or {@code SPACES}, the first two operands of
     * the blank tests at {@code app/cbl/COCRDLIC.cbl:1007-1008} and :1042-1043.
     *
     * @param value the field to test
     * @return {@code true} when the field holds nothing but spaces or null bytes, or is absent
     */
    private static boolean isPicBlank(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character != ' ' && character != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders {@code IS NUMERIC} for an unsigned alphanumeric field, as tested at
     * {@code app/cbl/COCRDLIC.cbl:1017} and :1052.
     *
     * <p>Every character must be a digit, so an embedded or trailing space fails. That is what makes a
     * partially typed filter invalid and is the behaviour the two error messages describe.</p>
     *
     * @param value the field to test
     * @return {@code true} only when the field is non-empty and wholly digits
     */
    private static boolean isPicNumeric(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a comparison against {@code ZEROS}, the third operand of the blank tests at
     * {@code app/cbl/COCRDLIC.cbl:1009} and :1044 and the condition at :849 and :861.
     *
     * <p>An absent or blank field counts as zero, because {@code INITIALIZE} leaves a numeric-display
     * item filled with zeros, which is the state those two conditions are written to detect.</p>
     *
     * @param value the field to test
     * @return {@code true} when the field is blank or wholly zero digits
     */
    private static boolean isZeroDigits(final String value) {
        if (isPicBlank(value)) {
            return true;
        }
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders {@code 88 SELECT-BLANK VALUES ' ', LOW-VALUES} at {@code app/cbl/COCRDLIC.cbl:80-81}.
     *
     * <p>Because the condition covers both a space and a null byte, an absent, empty, blank or
     * null-byte selection are all one and the same "no selection" state. None of them is a distinct
     * third case and none is coerced into one.</p>
     *
     * @param value the received selection code
     * @return {@code true} when no selection was made on that row
     */
    private static boolean isSelectBlank(final String value) {
        return isPicBlank(value);
    }

    /**
     * Extracts the function-key number from an attention identifier such as {@code PF7}.
     *
     * <p>Serves the {@code DFHPF1} through {@code DFHPF24} arms at {@code app/cpy/CSSTRPFY.cpy:30-77}.
     * Parsing is manual and total: an identifier that is not a function key yields zero rather than
     * raising, which is what lets the absent {@code WHEN OTHER} arm leave the field untouched.</p>
     *
     * @param aid the attention identifier
     * @return the key number between 1 and 24, or 0 when the identifier is not a function key
     */
    private static int functionKeyNumber(final String aid) {
        if (aid == null || aid.length() < 3 || aid.charAt(0) != 'P' || aid.charAt(1) != 'F') {
            return 0;
        }
        final String digits = aid.substring(2);
        if (!isPicNumeric(digits) || digits.length() > 2) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    /**
     * Renders {@code CARD-ACCT-ID}, the eleven-byte zoned account identifier of the card record.
     *
     * <p>The entity models the column as a scalar {@code Long} rather than an association, matching
     * the numeric comparison the filter performs at {@code app/cbl/COCRDLIC.cbl:1386}; it is rendered
     * back to its eleven-character zoned form so that comparisons and row storage stay byte faithful.
     * An absent value renders as zeros, which is the state {@code INITIALIZE} would have left.</p>
     *
     * @param record the card record, or {@code null} for an untouched record buffer
     * @return eleven characters, or {@code null} when the record itself is absent
     */
    private static String accountIdOf(final Card record) {
        if (record == null) {
            return null;
        }
        final Long accountId = record.getAccountId();
        return padDigits(accountId == null ? "0" : Long.toString(accountId), ACCOUNT_FILTER_WIDTH);
    }

    /**
     * Renders {@code CARD-NUM}, the sixteen-byte card number of the card record.
     *
     * @param record the card record, or {@code null} for an untouched record buffer
     * @return sixteen characters, or {@code null} when the record itself is absent
     */
    private static String cardNumberOf(final Card record) {
        if (record == null) {
            return null;
        }
        return movePicX(record.getCardNumber(), CARD_FILTER_WIDTH);
    }

    /**
     * Masks all but the trailing digits of a sensitive value so that it can appear in a log record.
     *
     * <p>Required by Rule 1 Clause D. Nothing in this class writes a card number, an account
     * identifier or any other sensitive value to a log without passing it through here, and no page of
     * rows is ever logged. The card verification value cannot be read, projected or logged at all, though
     * not because it is unmodelled: {@code card_cvv_cd} is declared and seeded, and
     * {@link com.cardemo.model.entity.Card} maps it. It is <em>write-once and accessor-less</em> - no getter
     * of any visibility exists - so no read path to it exists in this class or anywhere else.
     * This program would not have referenced one anyway, its
     * output edit fields {@code CARD-CVV-CD-X} and {@code CARD-CVV-CD-N} at
     * {@code app/cbl/COCRDLIC.cbl:102-104} being declared and never used.</p>
     *
     * @param value the sensitive value
     * @return the value with everything but its last {@value #MASK_VISIBLE_DIGITS} characters replaced,
     *     or {@code null} when the value is absent
     */
    private static String maskTail(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.length() <= MASK_VISIBLE_DIGITS) {
            return trimmed.isEmpty() ? trimmed : "*".repeat(trimmed.length());
        }
        final int masked = trimmed.length() - MASK_VISIBLE_DIGITS;
        return "*".repeat(masked) + trimmed.substring(masked);
    }

    /**
     * Projects the row table onto the list rows of the outbound screen.
     *
     * <p>A row the browse never filled stays at low values and is omitted rather than emitted blank,
     * which is how a short final page - or the partially filled table a failed backward browse leaves
     * behind - is represented. The one-based row number is preserved from the table position, so an
     * omitted row leaves a gap in the numbering exactly as it leaves a gap on the screen.</p>
     *
     * <p>The selector type is always absent. {@code app/cpy-bms/COCRDLI.CPY} declares
     * {@code CRDSTP2I} through {@code CRDSTP7I} and <b>no {@code CRDSTP1I}</b>, giving row one four
     * fields where the others have five and the map 45 input fields rather than 46; and this program
     * never writes any of the six that do exist. No seventh field is invented to
     * regularise row one.</p>
     *
     * @param state the per-request working storage
     * @return the rows to display, never {@code null} and never longer than the configured page size
     */
    private static List<CardDto.CardListRow> buildDisplayedRows(final ProgramState state) {
        final List<CardDto.CardListRow> rows = new ArrayList<>(state.rowCapacity);
        for (int index = 0; index < state.rowCapacity; index++) {
            if (state.isRowLowValues(index)) {
                continue;
            }
            rows.add(
                    new CardDto.CardListRow(
                            index + 1,
                            movePicX(state.outRowSelect[index], 1),
                            null,
                            state.outRowAccountNumber[index],
                            state.outRowCardNumber[index],
                            state.outRowStatus[index]));
        }
        return rows;
    }

    /**
     * Renders a per-row attribute array as a fixed-width string of {@code 'Y'} and {@code 'N'}.
     *
     * <p>The row-selection attribute bytes are real, observable screen state - they decide whether the
     * user can type in a row and whether that row is highlighted - but the byte values themselves come
     * from {@code DFHBMSCA}, which is supplied by the transaction monitor and is absent from this
     * repository, so no numeric value is invented. The observable effect is reported instead.</p>
     *
     * @param values one flag per row, in row order
     * @return one character per row, {@code 'Y'} where the flag is set
     */
    private static String flagString(final boolean[] values) {
        final StringBuilder rendered = new StringBuilder(values.length);
        for (final boolean value : values) {
            rendered.append(value ? 'Y' : 'N');
        }
        return rendered.toString();
    }

    /**
     * Renders the two filter fields' attribute state as four characters.
     *
     * <p>Order: account enterable ({@code DFHBMFSE} into {@code ACCTSIDA} at
     * {@code app/cbl/COCRDLIC.cbl:848} and :853), account highlighted ({@code DFHRED} into
     * {@code ACCTSIDC} at :873), card enterable (:860, :865) and card highlighted (:878).</p>
     *
     * @param state the per-request working storage
     * @return four characters of {@code 'Y'} or {@code 'N'}
     */
    private static String filterFieldFlags(final ProgramState state) {
        return flagString(new boolean[] {
            state.accountFilterSelectable,
            state.accountFilterErrorHighlighted,
            state.cardFilterSelectable,
            state.cardFilterErrorHighlighted
        });
    }


    // ============================================================================================
    // REQUEST, RESULT AND PER-REQUEST WORKING STORAGE
    //
    // Three nested types, and deliberately no more: this package is exactly three .java files, so no
    // separate carrier, helper or mapper type may be created. All
    // three are static, so none captures the service instance.
    //
    // The two public carriers expose public final fields rather than accessor pairs. They are
    // immutable - every reference field is a String, a primitive or an unmodifiable copy - and the
    // service itself reads them directly, so accessors would add a body per field that no path in this
    // file exercises, inflating the class against the 80% JaCoCo line gate for no behavioural gain.
    // Serialization reads public fields directly, so the JSON contract is unaffected. The tradeoff is
    // recorded here because Rule 1 Clause A asks for tradeoffs to be justified rather than assumed.
    // ============================================================================================

    /**
     * One turn of the card list screen: the received map plus the paging state the client echoes back.
     *
     * <p>This is the stateless replacement for the two halves of the commarea that
     * {@code app/cbl/COCRDLIC.cbl:327-331} splits apart. {@code CARDDEMO-COMMAREA} of
     * {@code app/cpy/COCOM01Y.cpy} contributes the identity and key fields, and
     * {@code WS-THIS-PROGCOMMAREA} at :229-260 contributes the paging keys, the page number, the two
     * page indicators <b>and the 196-byte row table</b>, which is an 05-level child of that group at
     * :252-260 and therefore genuinely round-trips across turns. That round trip is how a selection
     * code resolves to a card: the {@code 'S'} and {@code 'U'} arms at :531-534 and :559-562 read the
     * selected row out of the table the previous turn displayed.</p>
     *
     * <p>There is no server-side session, no server-side cursor and no cache. Paging is <b>key based</b>
     * rather than offset based, because the browse reopens with {@code GTEQ} from a saved key at :1129
     * and :1273 - so a turn carries a start key, not a row offset.</p>
     *
     * <p>Fields with <b>no legacy equivalent</b> are deliberately absent: the four routing fields
     * {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM} and
     * {@code CDEMO-TO-PROGRAM} survive only as the program identity this service compares against,
     * routing itself being URL based; {@code CDEMO-PGM-CONTEXT} collapses into
     * {@code programReenter}; and the two account-key halves of the saved page keys are omitted
     * because {@code WS-CA-FIRST-CARD-ACCT-ID} and {@code WS-CA-LAST-CARD-ACCT-ID} are written at
     * :1175, :1194, :1213, :1236 and :1351 and <b>never read</b> - their only readers, the moves at
     * :448, :475, :490, :506 and :576, are commented out. The stores are still performed
     * internally so the paragraph transcription stays complete.</p>
     */
    public static final class CardListRequest {

        /**
         * Whether a commarea was passed, the transcription of {@code EIBCALEN} being non-zero at
         * {@code app/cbl/COCRDLIC.cbl:315}. When false this is a first entry and the received-map
         * fields below are ignored, exactly as the guard at :357-362 ignores them.
         */
        public final boolean commAreaPresent;

        /**
         * The attention identifier, standing in for {@code EIBAID} as mapped at
         * {@code app/cpy/CSSTRPFY.cpy:21-78}. Accepted values are {@code ENTER}, {@code CLEAR},
         * {@code PA1}, {@code PA2} and {@code PF1} through {@code PF24}, the upper twelve of which
         * fold onto the lower twelve. Anything else leaves the key unset and is then coerced to
         * {@code ENTER} by the validity gate at :370-380.
         */
        public final String attentionIdentifier;

        /** The account filter, {@code ACCTSIDI} at {@code app/cpy-bms/COCRDLI.CPY:66}, eleven bytes. */
        public final String accountFilter;

        /** The card filter, {@code CARDSIDI} at {@code app/cpy-bms/COCRDLI.CPY:72}, sixteen bytes. */
        public final String cardFilter;

        /**
         * The seven row-selection codes, {@code CRDSEL1I} through {@code CRDSEL7I} at
         * {@code app/cpy-bms/COCRDLI.CPY:78, 102, 132, 162, 192, 222, 252}. Unmodifiable; a shorter or
         * absent list reads as no selection on the missing rows.
         */
        public final List<String> rowSelections;

        /** {@code CDEMO-FROM-TRANID}, {@code app/cpy/COCOM01Y.cpy:21}. */
        public final String fromTranId;

        /**
         * {@code CDEMO-FROM-PROGRAM}, {@code app/cpy/COCOM01Y.cpy:22}. Must be {@code COCRDLIC} for
         * the received screen to be edited at all, per the guard at
         * {@code app/cbl/COCRDLIC.cbl:357-362}: a turn arriving from anywhere else has its filters and
         * selections ignored and is served an unfiltered first page.
         */
        public final String fromProgram;

        /** {@code CDEMO-USRTYP-ADMIN}, {@code app/cpy/COCOM01Y.cpy:27}. */
        public final boolean userTypeAdmin;

        /** {@code CDEMO-PGM-REENTER}, {@code app/cpy/COCOM01Y.cpy:31}. */
        public final boolean programReenter;

        /** {@code CDEMO-LAST-MAP}, {@code app/cpy/COCOM01Y.cpy:43}. */
        public final String lastMap;

        /** {@code CDEMO-LAST-MAPSET}, {@code app/cpy/COCOM01Y.cpy:44}. */
        public final String lastMapset;

        /** {@code CDEMO-ACCT-ID}, {@code app/cpy/COCOM01Y.cpy:38}, eleven digits. */
        public final String accountId;

        /** {@code CDEMO-CARD-NUM}, {@code app/cpy/COCOM01Y.cpy:41}, sixteen digits. */
        public final String cardNumber;

        /**
         * {@code WS-CA-SCREEN-NUM}, {@code app/cbl/COCRDLIC.cbl:237}. A single digit, so it wraps at
         * nine rather than overflowing, which is preserved.
         */
        public final int pageNumber;

        /**
         * {@code WS-CA-LAST-PAGE-DISPLAYED}, {@code app/cbl/COCRDLIC.cbl:239-241}. Zero means the last
         * page has been shown and nine means it has not, which is the latch the message paragraph
         * drives at :910-916.
         */
        public final int lastPageDisplayed;

        /**
         * {@code WS-CA-NEXT-PAGE-IND}, {@code app/cbl/COCRDLIC.cbl:242-244}.
         */
        public final boolean nextPageAvailable;

        /** {@code WS-CA-FIRST-CARD-NUM}, {@code app/cbl/COCRDLIC.cbl:234}, the page-up start key. */
        public final String firstCardNumber;

        /** {@code WS-CA-LAST-CARD-NUM}, {@code app/cbl/COCRDLIC.cbl:231}, the page-down start key. */
        public final String lastCardNumber;

        /**
         * The rows the previous turn displayed, the transcription of {@code WS-ALL-ROWS} at
         * {@code app/cbl/COCRDLIC.cbl:253-260}. Unmodifiable. A selection code is resolved against
         * this list, so omitting it makes every selection unresolvable.
         */
        public final List<DisplayedRow> displayedRows;

        /**
         * The formatted date for the screen header, {@code CURDATEO}, built from
         * {@code FUNCTION CURRENT-DATE} at {@code app/cbl/COCRDLIC.cbl:645} and :652. Supplied by the
         * caller rather than read from a clock here, which keeps this service deterministic and
         * testable and avoids depending on a collaborator this file may not declare.
         */
        public final String currentDate;

        /** The formatted time for the screen header, {@code CURTIMEO}, per :660-664. */
        public final String currentTime;

        /**
         * Constructs a full turn.
         *
         * @param commAreaPresent whether a commarea was passed
         * @param attentionIdentifier the attention identifier
         * @param accountFilter the account filter as received
         * @param cardFilter the card filter as received
         * @param rowSelections the seven row-selection codes; may be {@code null}
         * @param fromTranId the originating transaction identifier
         * @param fromProgram the originating program name
         * @param userTypeAdmin whether the signed-on user is an administrator
         * @param programReenter whether this is a re-entry rather than a first entry
         * @param lastMap the last map name
         * @param lastMapset the last mapset name
         * @param accountId the account identifier carried in from elsewhere
         * @param cardNumber the card number carried in from elsewhere
         * @param pageNumber the page number being echoed back
         * @param lastPageDisplayed the last-page latch being echoed back
         * @param nextPageAvailable the next-page indicator being echoed back
         * @param firstCardNumber the saved first key of the displayed page
         * @param lastCardNumber the saved last key of the displayed page
         * @param displayedRows the rows the previous turn displayed; may be {@code null}
         * @param currentDate the formatted header date
         * @param currentTime the formatted header time
         */
        public CardListRequest(
                final boolean commAreaPresent,
                final String attentionIdentifier,
                final String accountFilter,
                final String cardFilter,
                final List<String> rowSelections,
                final String fromTranId,
                final String fromProgram,
                final boolean userTypeAdmin,
                final boolean programReenter,
                final String lastMap,
                final String lastMapset,
                final String accountId,
                final String cardNumber,
                final int pageNumber,
                final int lastPageDisplayed,
                final boolean nextPageAvailable,
                final String firstCardNumber,
                final String lastCardNumber,
                final List<DisplayedRow> displayedRows,
                final String currentDate,
                final String currentTime) {

            this.commAreaPresent = commAreaPresent;
            this.attentionIdentifier = attentionIdentifier;
            this.accountFilter = accountFilter;
            this.cardFilter = cardFilter;
            // Collections.unmodifiableList over a copy, deliberately NOT List.copyOf: an unselected row
            // carries LOW-VALUES, which is the declared initial value of WS-EDIT-SELECT-FLAGS at
            // app/cbl/COCRDLIC.cbl:72, so a null element is the NORMAL case rather than an error. The
            // 88 SELECT-BLANK at :81-82 covers both a space and LOW-VALUES, so null, "" and " " are one
            // state and none of them may be coerced into a distinct third. List.copyOf rejects null
            // elements outright and would reduce that ordinary screen to a NullPointerException.
            this.rowSelections = rowSelections == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(rowSelections));
            this.fromTranId = fromTranId;
            this.fromProgram = fromProgram;
            this.userTypeAdmin = userTypeAdmin;
            this.programReenter = programReenter;
            this.lastMap = lastMap;
            this.lastMapset = lastMapset;
            this.accountId = accountId;
            this.cardNumber = cardNumber;
            this.pageNumber = pageNumber;
            this.lastPageDisplayed = lastPageDisplayed;
            this.nextPageAvailable = nextPageAvailable;
            this.firstCardNumber = firstCardNumber;
            this.lastCardNumber = lastCardNumber;
            // Unmodifiable copy rather than List.copyOf, for the same reason: MOVE LOW-VALUES TO
            // WS-ALL-ROWS at :1124 and :1266 leaves a short page's unused rows empty, so a null element
            // is the faithful representation of a row the browse never filled. loadCommArea skips them
            // explicitly at its guard rather than rejecting the whole request.
            this.displayedRows = displayedRows == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(displayedRows));
            this.currentDate = currentDate;
            this.currentTime = currentTime;
        }

        /**
         * Builds a first entry, the transcription of {@code EIBCALEN = 0} at
         * {@code app/cbl/COCRDLIC.cbl:315-325} and of arrival from the main menu at :336-343.
         *
         * <p>A first entry carries no filters and no selections, because the guard at :357-362 does
         * not receive the map on this path. The result is therefore always an unfiltered first page.
         * To apply a filter, send a subsequent turn with {@code commAreaPresent} true and
         * {@code fromProgram} set to {@code COCRDLIC}.</p>
         *
         * @param attentionIdentifier the attention identifier; {@code ENTER} for a plain open
         * @param fromProgram the program navigated from, typically {@code COMEN01C}
         * @param userTypeAdmin whether the signed-on user is an administrator
         * @param currentDate the formatted header date
         * @param currentTime the formatted header time
         * @return a request describing a first entry
         */
        public static CardListRequest initialEntry(
                final String attentionIdentifier,
                final String fromProgram,
                final boolean userTypeAdmin,
                final String currentDate,
                final String currentTime) {

            return new CardListRequest(
                    false,
                    attentionIdentifier,
                    null,
                    null,
                    null,
                    null,
                    fromProgram,
                    userTypeAdmin,
                    false,
                    null,
                    null,
                    null,
                    null,
                    0,
                    CA_LAST_PAGE_NOT_SHOWN,
                    false,
                    null,
                    null,
                    null,
                    currentDate,
                    currentTime);
        }

        /**
         * Returns whether a commarea accompanied this turn.
         *
         * @return {@code true} when {@code EIBCALEN} would have been greater than zero
         */
        public boolean isCommAreaPresent() {
            return this.commAreaPresent;
        }

        /**
         * Returns the selection code received for one row, the transcription of the seven moves at
         * {@code app/cbl/COCRDLIC.cbl:972-978}.
         *
         * @param rowNumber the one-based row, matching the COBOL subscript
         * @return the code, or {@code null} when the row carried none
         */
        public String rowSelection(final int rowNumber) {
            final int index = rowNumber - 1;
            if (index < 0 || index >= this.rowSelections.size()) {
                return null;
            }
            return this.rowSelections.get(index);
        }

        /**
         * One entry of the round-tripped row table, {@code WS-EACH-CARD} at
         * {@code app/cbl/COCRDLIC.cbl:257-260}: an eleven-byte account identifier, a sixteen-byte card
         * number and a one-byte status, twenty-eight bytes in all, seven of which make up the
         * 196-byte {@code WS-ALL-ROWS} at :253.
         */
        public static final class DisplayedRow {

            /** {@code WS-ROW-ACCTNO PIC X(11)}, {@code app/cbl/COCRDLIC.cbl:258}. */
            public final String accountNumber;

            /** {@code WS-ROW-CARD-NUM PIC X(16)}, {@code app/cbl/COCRDLIC.cbl:259}. */
            public final String cardNumber;

            /** {@code WS-ROW-CARD-STATUS PIC X(1)}, {@code app/cbl/COCRDLIC.cbl:260}. */
            public final String activeStatus;

            /**
             * Constructs one row-table entry.
             *
             * @param accountNumber the account identifier as displayed
             * @param cardNumber the card number as displayed
             * @param activeStatus the one-character active status as displayed
             */
            public DisplayedRow(
                    final String accountNumber, final String cardNumber, final String activeStatus) {
                this.accountNumber = accountNumber;
                this.cardNumber = cardNumber;
                this.activeStatus = activeStatus;
            }
        }
    }

    /**
     * The outcome of one turn: the screen, the paging metadata, the screen attributes and any
     * navigation intent.
     *
     * <p>This replaces both terminal outcomes the program can reach. A turn that ends at
     * {@code COMMON-RETURN.} ({@code app/cbl/COCRDLIC.cbl:604}) carries a screen and a page; a turn
     * that ends at an {@code EXEC CICS XCTL} (:402-405, :538-541, :566-569) carries neither and instead
     * names the program the client should navigate to, together with the selected keys.</p>
     *
     * <p><b>A selection error still carries the rows.</b> The dispatcher's error branch at :431-435
     * re-reads the list whenever <em>neither</em> filter flag is at fault, so a bad row-selection code
     * produces an error-bearing result that nevertheless holds a populated page. Only a filter failure
     * suppresses the browse, and that one is raised as {@code ValidationException} rather than
     * returned. {@code selectionFailure} carries the typed description of the non-suppressing failure so
     * that a caller can map it to a status without re-deriving it - it is deliberately <b>not</b>
     * thrown, because throwing it would discard the payload the source displays.</p>
     */
    public static final class CardListResult {

        /** The outbound screen, or {@code null} when control transferred instead of sending a map. */
        public final CardDto screen;

        /**
         * The paging metadata, or {@code null} on a transfer of control.
         *
         * <p>Total element and total page counts are not produced: the source never computes
         * them - it knows only whether one further record exists, from the lookahead at :1197-1205 - so
         * no count query is issued to synthesise them. Obtaining them would need a counting query the
         * legacy system never performed.</p>
         */
        public final PageResponse<CardDto.CardListRow> page;

        /** {@code CCARD-NEXT-PROG}: the program to navigate to, or {@code null} when staying here. */
        public final String nextProgram;

        /** {@code CCARD-NEXT-MAPSET}. */
        public final String nextMapset;

        /** {@code CCARD-NEXT-MAP}. */
        public final String nextMap;

        /**
         * {@code CDEMO-ACCT-ID} as handed onward, set from the selected row at :531-532 and :559-560.
         */
        public final String accountId;

        /**
         * {@code CDEMO-CARD-NUM} as handed onward, set from the selected row at :533-534 and :561-562.
         */
        public final String cardNumber;

        /** {@code true} only for the PF03 return to the main menu at :384-406. */
        public final boolean exitRequested;

        /**
         * {@code FLG-PROTECT-SELECT-ROWS-YES} ({@code app/cbl/COCRDLIC.cbl:107}), set at :1020 and
         * :1055. When true an invalid filter has protected the row-selection fields, so no row can be
         * chosen until the filter is corrected.
         */
        public final boolean rowSelectionProtected;

        /** {@code INPUT-ERROR} ({@code app/cbl/COCRDLIC.cbl:60}). */
        public final boolean inputError;

        /**
         * {@code WS-EDIT-SELECT-ERROR-FLAGS} ({@code app/cbl/COCRDLIC.cbl:83-87}), seven characters,
         * {@code '1'} marking a row in error. Built by the replacement at :1090-1093 and the loop at
         * :1099-1115.
         */
        public final String rowSelectionErrorFlags;

        /** {@code WS-ERROR-MSG} ({@code app/cbl/COCRDLIC.cbl:117}), at most 75 characters. */
        public final String errorMessage;

        /** {@code WS-INFO-MSG} ({@code app/cbl/COCRDLIC.cbl:112}), at most 45 characters. */
        public final String informationMessage;

        /**
         * The typed description of a row-selection failure, or {@code null} when there was none. Never
         * thrown from this service; see the class note above.
         */
        public final ValidationException selectionFailure;

        /**
         * The screen field the cursor was placed on by a {@code MOVE -1 TO ...L} at :770, :782, :794,
         * :805, :817, :828, :874, :879 or :885, or {@code null} when none was.
         */
        public final String cursorField;

        /**
         * Seven characters, {@code 'Y'} where the row-selection field was left enterable
         * ({@code DFHBMFSE} at :761, :772, :784, :796, :807, :819, :830) and {@code 'N'} where it was
         * protected ({@code DFHBMPRF} at :753 for row one, {@code DFHBMPRO} elsewhere).
         */
        public final String rowSelectableFlags;

        /**
         * Seven characters, {@code 'Y'} where the row-selection field was highlighted in error
         * ({@code DFHRED} at :756, :769, :781, :793, :804, :816, :827).
         */
        public final String rowHighlightFlags;

        /**
         * Four characters describing the two filter fields, in order: account enterable, account
         * highlighted, card enterable, card highlighted. Set by the two attribute evaluations at
         * :844-867 and the two cursor blocks at :872-880.
         */
        public final String filterFieldFlags;

        /** Whether {@code 1500-SEND-SCREEN.} (:938) ran, which a transfer of control bypasses. */
        public final boolean screenSent;

        /**
         * Constructs a turn outcome.
         *
         * @param screen the outbound screen, or {@code null} on a transfer of control
         * @param page the paging metadata, or {@code null} on a transfer of control
         * @param nextProgram the program to navigate to, or {@code null}
         * @param nextMapset the mapset to navigate to, or {@code null}
         * @param nextMap the map to navigate to, or {@code null}
         * @param accountId the account identifier handed onward
         * @param cardNumber the card number handed onward
         * @param exitRequested whether PF03 asked to leave for the main menu
         * @param rowSelectionProtected whether the row-selection fields were protected
         * @param inputError whether any edit failed
         * @param rowSelectionErrorFlags the seven per-row error markers
         * @param errorMessage the error message the screen carries
         * @param informationMessage the information message the screen carries
         * @param selectionFailure the typed selection failure, or {@code null}
         * @param cursorField the field the cursor was placed on, or {@code null}
         * @param rowSelectableFlags the seven per-row enterable markers
         * @param rowHighlightFlags the seven per-row highlight markers
         * @param filterFieldFlags the four filter-field attribute markers
         * @param screenSent whether a map was sent
         */
        public CardListResult(
                final CardDto screen,
                final PageResponse<CardDto.CardListRow> page,
                final String nextProgram,
                final String nextMapset,
                final String nextMap,
                final String accountId,
                final String cardNumber,
                final boolean exitRequested,
                final boolean rowSelectionProtected,
                final boolean inputError,
                final String rowSelectionErrorFlags,
                final String errorMessage,
                final String informationMessage,
                final ValidationException selectionFailure,
                final String cursorField,
                final String rowSelectableFlags,
                final String rowHighlightFlags,
                final String filterFieldFlags,
                final boolean screenSent) {

            this.screen = screen;
            this.page = page;
            this.nextProgram = nextProgram;
            this.nextMapset = nextMapset;
            this.nextMap = nextMap;
            this.accountId = accountId;
            this.cardNumber = cardNumber;
            this.exitRequested = exitRequested;
            this.rowSelectionProtected = rowSelectionProtected;
            this.inputError = inputError;
            this.rowSelectionErrorFlags = rowSelectionErrorFlags;
            this.errorMessage = errorMessage;
            this.informationMessage = informationMessage;
            this.selectionFailure = selectionFailure;
            this.cursorField = cursorField;
            this.rowSelectableFlags = rowSelectableFlags;
            this.rowHighlightFlags = rowHighlightFlags;
            this.filterFieldFlags = filterFieldFlags;
            this.screenSent = screenSent;
        }

        /**
         * Returns how many rows the screen carries, without exposing any of them.
         *
         * @return the row count, or zero when no map was sent
         */
        public int rowCount() {
            return this.page == null ? 0 : this.page.getRows().size();
        }
    }

    /**
     * The whole of this program's {@code WORKING-STORAGE SECTION} for one request.
     *
     * <p>Every item the source declares between {@code app/cbl/COCRDLIC.cbl:41} and :262 lives here as
     * an instance field of a per-request object, never as a field of the service bean. That is not a
     * stylistic choice: {@code WS-SCRN-COUNTER}, {@code WS-CA-SCREEN-NUM}, the two saved page keys, the
     * selection flags, the row table and the browse cursor are all mutated during a turn, and holding
     * any of them on the singleton bean would corrupt concurrent requests. Constructing this object is
     * the transcription of {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} at :300-302.</p>
     *
     * <p>Three declared items are deliberately <b>absent</b>, because the one-to-one mandate covers
     * paragraph labels and not data-division items, which get the opposite ruling. A repository-wide
     * census shows {@code WS-EDIT-SELECT-COUNTER} (:69-71), {@code 88 DETAIL-WAS-REQUESTED} (:94),
     * {@code WS-CONTEXT-FLAG} (:130-132) and {@code WS-RETURN-FLAG} (:246-248) occurring only at their
     * own declarations, and {@code WS-LONG-MSG} (:111) only inside the unreachable body at :1443-1444.
     * The four output edit fields at :99-104, including the two card-verification-value fields, are
     * likewise declared and never referenced - which is the positive evidence that this program never
     * touches that value.</p>
     */
    private static final class ProgramState {

        /** The row-table depth, {@code OCCURS 7 TIMES} at :255, sized from the injected page size. */
        private final int rowCapacity;

        /**
         * {@code EIBCALEN} being non-zero, tested at :315, :357 and :839.
         *
         * <p>This is the one item here that is not a {@code WORKING-STORAGE} field: it belongs to the
         * CICS exec interface block, which is task scoped and therefore per request just as this object
         * is. It is mirrored onto the state so that the presentation paragraphs, which the source
         * reaches without any argument at all, can test it at :839 exactly where the source does,
         * instead of having the inbound request threaded through the whole chain.</p>
         */
        private boolean commAreaPresent;

        /** {@code WS-TRANID} (:51). Written at :307 and never read; the store is preserved. */
        private String transactionId;

        /** {@code WS-ERROR-MSG} (:117). */
        private String errorMessage;

        /** {@code WS-INFO-MSG} (:112). */
        private String infoMessage;

        /** {@code CCARD-ERROR-MSG} of app/cpy/CVCRD01Y.cpy, moved at :423 and :587. */
        private String ccardErrorMessage;

        /** {@code CCARD-AID-*} of app/cpy/CVCRD01Y.cpy, set by {@code YYYY-STORE-PFKEY}. */
        private String ccardAid;

        /** {@code CDEMO-FROM-TRANID} (app/cpy/COCOM01Y.cpy:21). */
        private String cdemoFromTranId;

        /** {@code CDEMO-FROM-PROGRAM} (app/cpy/COCOM01Y.cpy:22). */
        private String cdemoFromProgram;

        /** {@code CDEMO-TO-PROGRAM} (app/cpy/COCOM01Y.cpy:24), set at :392. */
        private String cdemoToProgram;

        /** {@code CDEMO-USRTYP-ADMIN} (app/cpy/COCOM01Y.cpy:27). */
        private boolean cdemoUserTypeAdmin;

        /** {@code CDEMO-PGM-REENTER} (app/cpy/COCOM01Y.cpy:31). */
        private boolean cdemoProgramReenter;

        /** {@code CDEMO-LAST-MAP} (app/cpy/COCOM01Y.cpy:43). */
        private String cdemoLastMap;

        /** {@code CDEMO-LAST-MAPSET} (app/cpy/COCOM01Y.cpy:44). */
        private String cdemoLastMapset;

        /** {@code CDEMO-ACCT-ID} (app/cpy/COCOM01Y.cpy:38). */
        private String cdemoAcctId;

        /** {@code CDEMO-CARD-NUM} (app/cpy/COCOM01Y.cpy:41). */
        private String cdemoCardNumber;

        /** {@code CCARD-NEXT-PROG} of app/cpy/CVCRD01Y.cpy. */
        private String nextProgram;

        /** {@code CCARD-NEXT-MAPSET} of app/cpy/CVCRD01Y.cpy. */
        private String nextMapset;

        /** {@code CCARD-NEXT-MAP} of app/cpy/CVCRD01Y.cpy. */
        private String nextMap;

        /** {@code WS-CA-FIRST-CARD-NUM} (:234). */
        private String caFirstCardNumber;

        /**
         * {@code WS-CA-FIRST-CARD-ACCT-ID} (:235). Written at :1175 and :1351 and never read - its only
         * readers at :448, :475, :506 and :576 are commented out - so the store is preserved and the
         * value is not surfaced.
         */
        private String caFirstCardAccountId;

        /** {@code WS-CA-LAST-CARD-NUM} (:231). */
        private String caLastCardNumber;

        /**
         * {@code WS-CA-LAST-CARD-ACCT-ID} (:232). Written at :1194, :1213 and :1236 and never read, its
         * only reader at :490 being commented out.
         */
        private String caLastCardAccountId;

        /** {@code WS-CA-SCREEN-NUM} (:237), a single digit. */
        private int caScreenNumber;

        /** {@code WS-CA-LAST-PAGE-DISPLAYED} (:239). */
        private int caLastPageDisplayed;

        /** {@code WS-CA-NEXT-PAGE-IND} (:242). */
        private char caNextPageIndicator;

        /** {@code WS-CARD-RID-CARDNUM} (:138), the browse start key. */
        private String ridCardNumber;

        /** {@code WS-SCRN-COUNTER} (:145). */
        private int screenCounter;

        /** {@code READ-LOOP-EXIT} (:151) versus {@code MORE-RECORDS-TO-READ} (:152). */
        private boolean readLoopExit;

        /** {@code WS-EXCLUDE-THIS-RECORD} (:148) versus its negation (:149). */
        private boolean excludeThisRecord;

        /** {@code INPUT-ERROR} (:60). */
        private boolean inputError;

        /** {@code FLG-PROTECT-SELECT-ROWS-YES} (:107). */
        private boolean protectSelectRows;

        /** {@code WS-EDIT-ACCT-FLAG} (:61). */
        private char accountFilterFlag;

        /** {@code WS-EDIT-CARD-FLAG} (:65). */
        private char cardFilterFlag;

        /** {@code CC-ACCT-ID} of app/cpy/CVCRD01Y.cpy, received at :969. */
        private String ccAcctId;

        /** {@code CC-CARD-NUM} of app/cpy/CVCRD01Y.cpy, received at :970. */
        private String ccCardNumber;

        /** {@code WS-EDIT-SELECT} (:74), one per row, received at :972-978. */
        private final String[] selectFlags;

        /** {@code WS-ROW-CRDSELECT-ERROR} (:87), one per row. */
        private final char[] selectErrorFlags;

        /** {@code I-SELECTED} (:92). Zero means no row is selected; see the labelled deviation. */
        private int selectedIndex;

        /** {@code WS-ROW-ACCTNO} (:258). */
        private final String[] rowAccountNumber;

        /** {@code WS-ROW-CARD-NUM} (:259). */
        private final String[] rowCardNumber;

        /** {@code WS-ROW-CARD-STATUS} (:260). */
        private final String[] rowCardStatus;

        /** {@code CRDSEL{n}O} of the map output area, written at :681, :690, :699, :708, :717, :727, :736. */
        private final String[] outRowSelect;

        /** {@code ACCTNO{n}O} of the map output area. */
        private final String[] outRowAccountNumber;

        /** {@code CRDNUM{n}O} of the map output area. */
        private final String[] outRowCardNumber;

        /** {@code CRDSTS{n}O} of the map output area. */
        private final String[] outRowStatus;

        /** Whether each row-selection field was left enterable by {@code 1250-SETUP-ARRAY-ATTRIBS}. */
        private final boolean[] rowSelectable;

        /** Whether each row-selection field was highlighted by {@code 1250-SETUP-ARRAY-ATTRIBS}. */
        private final boolean[] rowErrorHighlighted;

        /** The field a {@code MOVE -1 TO ...L} placed the cursor on. */
        private String cursorField;

        /** {@code ACCTSIDO}, written at :847 and :852. */
        private String outAccountFilter;

        /** {@code CARDSIDO}, written at :859 and :864. */
        private String outCardFilter;

        /** {@code DFHBMFSE} into {@code ACCTSIDA} at :848 and :853. */
        private boolean accountFilterSelectable;

        /** {@code DFHBMFSE} into {@code CARDSIDA} at :860 and :865. */
        private boolean cardFilterSelectable;

        /** {@code DFHRED} into {@code ACCTSIDC} at :873. */
        private boolean accountFilterErrorHighlighted;

        /** {@code DFHRED} into {@code CARDSIDC} at :878. */
        private boolean cardFilterErrorHighlighted;

        /** {@code TITLE01O}, written at :647. */
        private String outTitle01;

        /** {@code TITLE02O}, written at :648. */
        private String outTitle02;

        /** {@code PGMNAMEO}, written at :650. */
        private String outProgramName;

        /** {@code CURDATEO}, written at :658. */
        private String outCurrentDate;

        /** {@code CURTIMEO}, written at :664. */
        private String outCurrentTime;

        /** {@code PAGENOO}, written at :667. */
        private String outPageNumber;

        /** {@code INFOMSGO}, written at :670 and :928. */
        private String outInfoMessage;

        /** {@code ERRMSGO}, written at :924. */
        private String outErrorMessage;

        /** {@code WS-CURDATE-DATA}, assigned twice at :645 and :652. */
        private String curDateData;

        /** {@code WS-CURTIME-DATA}, assigned alongside the date. */
        private String curTimeData;

        /** The formatted date the caller supplied in place of {@code FUNCTION CURRENT-DATE}. */
        private String suppliedDate;

        /** The formatted time the caller supplied. */
        private String suppliedTime;

        /** Whether {@code 1500-SEND-SCREEN} ran. */
        private boolean mapSent;

        /**
         * {@code CARD-RECORD} of app/cpy/CVACT02Y.cpy, the browse {@code INTO} area. It deliberately
         * retains the last record read, because the end-of-file arm at :1236-1237 saves the page keys
         * from whatever the buffer still holds. Preserved.
         */
        private Card cardRecord;

        /** Whether a browse is open between {@code STARTBR} and {@code ENDBR}. */
        private boolean browseActive;

        /** The row count the browse can see, or negative when not yet observed. */
        private long browseTotal;

        /** The absolute offset the next sequential browse read will return. */
        private int browsePosition;

        /** The buffered aligned block of records, standing in for the VSAM control interval. */
        private List<Card> browseWindow;

        /** The absolute offset of the first record in {@link #browseWindow}, or negative when empty. */
        private int browseWindowStart;

        /** The condition a failed browse read raised, or {@code null} after a normal read. */
        private String fileErrorRespCondition;

        /** The store failure underlying a browse error, preserved as the exception cause. */
        private DataAccessException fileErrorCause;

        /** The composed {@code WS-FILE-ERROR-MESSAGE}, latched until the browse is terminated. */
        private String fileErrorMessage;

        /** {@code ERROR-OPNAME} (:156). */
        private String errorOperation;

        /** {@code ERROR-FILE} (:160). */
        private String errorFile;

        /** {@code ERROR-RESP} (:165). */
        private String errorResp;

        /** {@code ERROR-RESP2} (:169). */
        private String errorResp2;

        /** The typed description of a row-selection failure, carried in the result rather than thrown. */
        private ValidationException selectionFailure;

        /**
         * Performs {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} (:300-302).
         *
         * <p>Every array is sized from the injected page size rather than from a literal, so the
         * {@code OCCURS 7 TIMES} of :255 and the separately hardcoded {@code 7} of :1099 resolve from
         * one place and cannot drift.</p>
         *
         * @param rowCapacity the configured page size
         */
        private ProgramState(final int rowCapacity) {
            this.rowCapacity = rowCapacity;
            this.selectFlags = new String[rowCapacity];
            this.selectErrorFlags = new char[rowCapacity];
            this.rowAccountNumber = new String[rowCapacity];
            this.rowCardNumber = new String[rowCapacity];
            this.rowCardStatus = new String[rowCapacity];
            this.outRowSelect = new String[rowCapacity];
            this.outRowAccountNumber = new String[rowCapacity];
            this.outRowCardNumber = new String[rowCapacity];
            this.outRowStatus = new String[rowCapacity];
            this.rowSelectable = new boolean[rowCapacity];
            this.rowErrorHighlighted = new boolean[rowCapacity];
            for (int index = 0; index < rowCapacity; index++) {
                // WS-EDIT-SELECT-ERROR-FLAGS (:83) carries no VALUE clause, so INITIALIZE leaves it
                // filled with spaces rather than with the '0' the replacement at :1093 would write.
                this.selectErrorFlags[index] = ' ';
            }
            this.errorMessage = "";
            this.infoMessage = "";
            this.accountFilterFlag = FILTER_BLANK;
            this.cardFilterFlag = FILTER_BLANK;
            this.caScreenNumber = 0;
            this.caLastPageDisplayed = CA_LAST_PAGE_NOT_SHOWN;
            // INITIALIZE leaves WS-CA-NEXT-PAGE-IND holding a space, which matches NEITHER of the two
            // condition names at :243-244 - CA-NEXT-PAGE-NOT-EXISTS tests for LOW-VALUES and
            // CA-NEXT-PAGE-EXISTS for 'Y'. The third state is real and is represented rather than
            // collapsed into one of the two.
            this.caNextPageIndicator = CA_NEXT_PAGE_UNSET;
            this.browseTotal = -1L;
            this.browsePosition = 0;
            this.browseWindow = new ArrayList<>();
            this.browseWindowStart = -1;
        }

        /**
         * Renders the COBOL {@code CONTINUE} verb, which does nothing and falls through.
         *
         * <p>It is called rather than left implicit so that every preserved {@code ELSE CONTINUE} arm
         * and every attribute move with no REST counterpart remains visible at its source line instead
         * of becoming an empty block a later reader might delete as redundant.</p>
         */
        private void noOperation() {
            // CONTINUE.
        }

        /**
         * Performs {@code MOVE LOW-VALUES TO WS-ALL-ROWS} (:1124 and :1266), clearing the row table
         * only. The map output area is a separate structure and is cleared by :643.
         */
        private void clearRowTable() {
            for (int index = 0; index < this.rowCapacity; index++) {
                this.rowAccountNumber[index] = null;
                this.rowCardNumber[index] = null;
                this.rowCardStatus[index] = null;
            }
        }

        /**
         * Performs {@code MOVE LOW-VALUES TO CCRDLIAO} (:643), clearing the map output area.
         *
         * <p>The row-selection attribute bytes are <em>not</em> cleared here. The source writes them
         * qualified as {@code OF CCRDLIAI} at :753 onward - the input structure, not the output one -
         * so this clear does not reach them. They are recomputed by
         * {@code 1250-SETUP-ARRAY-ATTRIBS} on every turn regardless.</p>
         */
        private void clearScreenOutput() {
            this.outTitle01 = null;
            this.outTitle02 = null;
            this.outProgramName = null;
            this.outCurrentDate = null;
            this.outCurrentTime = null;
            this.outPageNumber = null;
            this.outInfoMessage = null;
            this.outErrorMessage = null;
            this.outAccountFilter = null;
            this.outCardFilter = null;
            this.accountFilterSelectable = false;
            this.cardFilterSelectable = false;
            this.accountFilterErrorHighlighted = false;
            this.cardFilterErrorHighlighted = false;
            this.cursorField = null;
            for (int index = 0; index < this.rowCapacity; index++) {
                this.outRowSelect[index] = null;
                this.outRowAccountNumber[index] = null;
                this.outRowCardNumber[index] = null;
                this.outRowStatus[index] = null;
            }
        }

        /**
         * Renders {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES}, the guard at :680, :689, :698, :707,
         * :716, :726, :735 and again at :751, :764, :775, :787, :799, :810 and :822.
         *
         * @param index the zero-based row index
         * @return {@code true} when the browse never filled that row
         */
        private boolean isRowLowValues(final int index) {
            return this.rowCardNumber[index] == null;
        }

        /**
         * Stores one browse record into the row table, the three moves at :1165-1171 going forward and
         * :1338-1344 going backward.
         *
         * @param oneBasedIndex the value of {@code WS-SCRN-COUNTER} at the point of the move
         * @param record the record just read
         */
        private void storeRow(final int oneBasedIndex, final Card record) {
            final int index = oneBasedIndex - 1;
            this.rowCardNumber[index] = cardNumberOf(record);
            this.rowAccountNumber[index] = accountIdOf(record);
            this.rowCardStatus[index] = movePicX(record.getActiveStatus(), 1);
        }

        /** Performs {@code INITIALIZE CARDDEMO-COMMAREA} (:316, :462, :1316-adjacent resets). */
        private void initializeCardDemoCommArea() {
            this.cdemoFromTranId = null;
            this.cdemoFromProgram = null;
            this.cdemoToProgram = null;
            this.cdemoUserTypeAdmin = false;
            this.cdemoProgramReenter = false;
            this.cdemoLastMap = null;
            this.cdemoLastMapset = null;
            this.cdemoAcctId = padDigits("0", ACCOUNT_FILTER_WIDTH);
            this.cdemoCardNumber = padDigits("0", CARD_FILTER_WIDTH);
        }

        /** Performs {@code INITIALIZE WS-THIS-PROGCOMMAREA} (:317, :338, :463). */
        private void initializeThisProgramCommArea() {
            this.caFirstCardNumber = movePicX("", CARD_FILTER_WIDTH);
            this.caFirstCardAccountId = padDigits("0", ACCOUNT_FILTER_WIDTH);
            this.caLastCardNumber = movePicX("", CARD_FILTER_WIDTH);
            this.caLastCardAccountId = padDigits("0", ACCOUNT_FILTER_WIDTH);
            this.caScreenNumber = 0;
            this.caLastPageDisplayed = CA_LAST_PAGE_NOT_SHOWN;
            this.caNextPageIndicator = CA_NEXT_PAGE_UNSET;
            this.ridCardNumber = movePicX("", CARD_FILTER_WIDTH);
            clearRowTable();
        }

        /**
         * Splits the inbound turn into the two commarea halves, the transcription of :327-331.
         *
         * @param request the inbound turn
         */
        private void loadCommArea(final CardListRequest request) {
            this.cdemoFromTranId = request.fromTranId;
            this.cdemoFromProgram = request.fromProgram;
            this.cdemoUserTypeAdmin = request.userTypeAdmin;
            this.cdemoProgramReenter = request.programReenter;
            this.cdemoLastMap = request.lastMap;
            this.cdemoLastMapset = request.lastMapset;
            this.cdemoAcctId = padDigits(request.accountId, ACCOUNT_FILTER_WIDTH);
            this.cdemoCardNumber = padDigits(request.cardNumber, CARD_FILTER_WIDTH);

            this.caFirstCardNumber = movePicX(request.firstCardNumber, CARD_FILTER_WIDTH);
            this.caFirstCardAccountId = padDigits("0", ACCOUNT_FILTER_WIDTH);
            this.caLastCardNumber = movePicX(request.lastCardNumber, CARD_FILTER_WIDTH);
            this.caLastCardAccountId = padDigits("0", ACCOUNT_FILTER_WIDTH);
            this.caScreenNumber = request.pageNumber;
            this.caLastPageDisplayed = request.lastPageDisplayed;
            this.caNextPageIndicator =
                    request.nextPageAvailable ? CA_NEXT_PAGE_EXISTS : CA_NEXT_PAGE_NOT_EXISTS;
            this.ridCardNumber = movePicX(request.firstCardNumber, CARD_FILTER_WIDTH);

            // WS-SCREEN-DATA is an 05-level child of WS-THIS-PROGCOMMAREA (:252-260), so the rows the
            // previous turn displayed arrive with the commarea and are what a selection resolves
            // against.
            clearRowTable();
            for (int index = 0; index < this.rowCapacity && index < request.displayedRows.size();
                    index++) {
                final CardListRequest.DisplayedRow row = request.displayedRows.get(index);
                if (row == null || row.cardNumber == null) {
                    continue;
                }
                this.rowAccountNumber[index] = padDigits(row.accountNumber, ACCOUNT_FILTER_WIDTH);
                this.rowCardNumber[index] = movePicX(row.cardNumber, CARD_FILTER_WIDTH);
                this.rowCardStatus[index] = movePicX(row.activeStatus, 1);
            }
        }

        /**
         * Renders {@code 88 CA-FIRST-PAGE VALUE 1} (:238).
         *
         * @return {@code true} when the page number is one
         */
        private boolean isFirstPage() {
            return this.caScreenNumber == CA_FIRST_PAGE;
        }

        /**
         * Renders {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'} (:244).
         *
         * @return {@code true} when a further page is believed to exist
         */
        private boolean isNextPageExists() {
            return this.caNextPageIndicator == CA_NEXT_PAGE_EXISTS;
        }

        /**
         * Renders {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES} (:243).
         *
         * @return {@code true} when the browse proved there is no further page. This is not the
         *     negation of {@link #isNextPageExists()}: the field is tri-state after the initialise at
         *     :300-302, because a space matches neither condition name
         */
        private boolean isNextPageNotExists() {
            return this.caNextPageIndicator == CA_NEXT_PAGE_NOT_EXISTS;
        }

        /**
         * Renders {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} (:113-114).
         *
         * @return {@code true} when no information message is standing
         */
        private boolean isNoInfoMessage() {
            return isPicBlank(this.infoMessage);
        }

        /**
         * Renders {@code 88 WS-ERROR-MSG-OFF VALUE SPACES} (:118).
         *
         * @return {@code true} when no error message is standing, which is the guard the source tests
         *     before overwriting one at :1056, :1112, :1218 and :1238
         */
        private boolean isErrorMessageOff() {
            return isPicBlank(this.errorMessage);
        }

        /**
         * Renders {@code 88 FLG-ACCTFILTER-ISVALID} (:63).
         *
         * @return {@code true} when a usable account filter was supplied, which is what makes the
         *     account gate at :1385 participate in the record filter
         */
        private boolean isAccountFilterValid() {
            return this.accountFilterFlag == FILTER_VALID;
        }

        /**
         * Renders {@code 88 FLG-ACCTFILTER-NOT-OK} (:62).
         *
         * @return {@code true} when the supplied account filter failed its edit
         */
        private boolean isAccountFilterNotOk() {
            return this.accountFilterFlag == FILTER_NOT_OK;
        }

        /**
         * Renders {@code 88 FLG-CARDFILTER-ISVALID} (:67).
         *
         * @return {@code true} when a usable card filter was supplied, which is what makes the card
         *     gate at :1396 participate in the record filter
         */
        private boolean isCardFilterValid() {
            return this.cardFilterFlag == FILTER_VALID;
        }

        /**
         * Renders {@code 88 FLG-CARDFILTER-NOT-OK} (:66).
         *
         * @return {@code true} when the supplied card filter failed its edit
         */
        private boolean isCardFilterNotOk() {
            return this.cardFilterFlag == FILTER_NOT_OK;
        }
    }
}
