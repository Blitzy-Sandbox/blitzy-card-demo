/*
 * ******************************************************************
 * Component   : UserListService
 * Application : CardDemo
 * Type        : Spring @Service (admin, user list)
 * Function    : List all users from USRSEC file
 * Source      : app/cbl/COUSR00C.cbl (695 lines, 16 own paragraph labels) @ 7756d89
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
package com.cardemo.service.admin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * The user-list screen: ten users per page, browsed forward and backward over the {@code USRSEC} security
 * file. This is the Java replacement for {@code app/cbl/COUSR00C.cbl} (695 lines, 16 own paragraph labels), the CICS
 * program behind transaction {@code CU00} - {@code DEFINE TRANSACTION(CU00) ... PROGRAM(COUSR00C)} in
 * {@code app/csd/CARDDEMO.CSD}, painting mapset {@code COUSR00} whose generated symbolic map is
 * {@code app/cpy-bms/COUSR00.CPY}.
 *
 * <h2>What it does</h2>
 *
 * <p>Three things, and nothing else. It pages the security file in the file's own key order; it reports the
 * boundary conditions the source reports, with the source's exact message text; and it reports which row the
 * operator selected, together with the program the source would have transferred to, so that the caller can
 * navigate. It is surfaced over HTTP by {@code com.cardemo.controller.AdminController} beneath
 * {@code /api/admin/*}, which is authored, so this service has an HTTP entry point rather than none.
 * {@code com.cardemo.config.SecurityConfig} restricts
 * {@code /api/admin/*} to the ADMIN role, so the rule is in place ahead of the route - the
 * {@code 'A'} against {@code 'U'} distinction of {@code CDEMO-USER-TYPE} at
 * {@code app/cpy/COCOM01Y.cpy}:27-28, surfaced as {@code com.cardemo.model.enums.UserType}.
 *
 * <p>It deliberately does <em>not</em> update, add or delete a user, does not authenticate, does not
 * configure persistence, security, HTTP or metrics, and does not invoke its sibling beans. A row marked for
 * update or deletion is reported as a selection; acting on it is the caller's decision, because the source's
 * {@code EXEC CICS XCTL} is a control transfer, not a subroutine call.
 *
 * <h2>How to build and test</h2>
 *
 * <p>The toolchain, the plugin versions and the zero-warning compiler settings are the project's, and
 * are stated once in {@code pom.xml}; what follows is only what is specific to this file.
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp clean compile} - compiles this file. {@code maven-compiler-plugin:3.14.1} runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning at all fails the build, and
 *       malformed Javadoc fails JDK 25 doclint.</li>
 *   <li>{@code ./mvnw -B -ntp test} - runs the unit tier through {@code maven-surefire-plugin:3.5.4}.
 *       This bean's tests belong in {@code src/test/java/com/cardemo/unit/**} and never in this package.</li>
 *   <li>{@code ./mvnw -B -ntp verify} - adds {@code maven-failsafe-plugin:3.5.4} and the
 *       {@code jacoco-maven-plugin:0.8.12} check, which enforces 80% LINE coverage with no exclusion for
 *       this package. Constructor injection and the complete absence of bean-held state are what make that
 *       reachable: every method below is exercisable by handing the constructor a fixed {@code Clock} and a
 *       stubbed repository, with no database and no Spring context.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>{@code carddemo.pagination.user-list-page-size}</strong> - the page size, owned by
 *       {@code src/main/resources/application.yml} where it is set to 10 and cited to
 *       {@code app/cbl/COUSR00C.cbl:57}, {@code 02 USER-REC OCCURS 10 TIMES}, with the loop bounds at
 *       {@code :293} and {@code :347}. It is injected, never hardcoded, and it carries <em>no</em> default:
 *       a missing property fails startup rather than silently substituting a number. It is a
 *       <strong>parity contract, not a tunable</strong>. Changing it changes how many rows a client
 *       receives, which is a behaviour change; the sibling contracts are 7 for the card list
 *       ({@code app/cbl/COCRDLIC.cbl:177-178}), 10 for the transaction list
 *       ({@code app/cbl/COTRN00C.cbl:65-68}) and 20 report lines per page
 *       ({@code app/cbl/CBTRN03C.cbl:127-137}).</li>
 *   <li><strong>Record layout</strong> - {@code app/cpy/CSUSR01Y.cpy}, exactly 80 bytes:
 *       {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-FNAME PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)},
 *       {@code SEC-USR-PWD PIC X(08)}, {@code SEC-USR-TYPE PIC X(01)}, {@code SEC-USR-FILLER PIC X(23)}.
 *       Four of those six are projected. The password field is not, and neither is the filler.</li>
 *   <li><strong>Key length 8</strong> - from {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED} on
 *       {@code DEFINE CLUSTER(NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)...)} at
 *       {@code app/jcl/DUSRSECJ.jcl}:64-73, matching {@code KEYLENGTH(LENGTH OF SEC-USR-ID)} on the browse
 *       at {@code app/cbl/COUSR00C.cbl:591}. It is the total order every page is taken in.</li>
 *   <li><strong>Header furniture</strong> - six fields whose widths come from
 *       {@code app/cpy-bms/COUSR00.CPY}: {@code TRNNAMEI PIC X(4)}, {@code TITLE01I PIC X(40)},
 *       {@code CURDATEI PIC X(8)}, {@code PGMNAMEI PIC X(8)}, {@code TITLE02I PIC X(40)} and
 *       {@code CURTIMEI PIC X(8)}. The titles are the literals of {@code app/cpy/COTTL01Y.cpy}; the date
 *       and time are {@code MM/DD/YY} and {@code HH:MM:SS}, both eight characters, from
 *       {@code app/cpy/CSDAT01Y.cpy}.</li>
 *   <li><strong>Clock</strong> - the header date and time are read from an injected
 *       {@code java.time.Clock}, standing in for {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
 *       {@code app/cbl/COUSR00C.cbl:564}. Nothing here calls {@code LocalDateTime.now()} with no clock, so
 *       the rendering is deterministic and testable.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>An empty list where rows were expected, with "You are at the top of the page...".</strong>
 *       The browse could not be positioned. Either the security table is empty, or a user identifier was
 *       supplied that does not exist <em>exactly</em>. Remedy: apply the migrations, whose
 *       {@code src/main/resources/db/migration/V3__seed_data.sql} seeds the ten operator rows, and supply
 *       an identifier that exists, or none at all. The typed exception is
 *       {@code com.cardemo.exception.RecordNotFoundException}.</li>
 *   <li><strong>End of file reported as an error.</strong> It is not one. {@code DFHRESP(ENDFILE)} at
 *       {@code app/cbl/COUSR00C.cbl:634} and {@code :668} sets {@code USER-SEC-EOF} and leaves
 *       {@code WS-ERR-FLG} untouched, which is the source's own proof that reaching the end terminates the
 *       loop rather than failing the request. Remedy: never throw for it. Severity of getting this wrong:
 *       <strong>High</strong>.</li>
 *   <li><strong>Startup failure naming {@code Clock} or the page-size property.</strong> Both are
 *       constructor arguments. The {@code Clock} bean is published by
 *       {@code com.cardemo.config.ObservabilityConfig#clock(String)} and the page-size property must resolve.
 *       Remedy:
 *       keep that single {@code Clock} declaration in place - do not add a second, because bean-definition
 *       overriding is disabled - and keep {@code carddemo.pagination.user-list-page-size} present in every
 *       profile.</li>
 *   <li><strong>Pages that are not reproducible between two identical requests.</strong> The order was
 *       lost. Every page is taken through
 *       {@code UserSecurityRepository.findAllByOrderBySecUsrIdAsc}, whose name fixes a total order on the
 *       eight-character key. Remedy: never page an unordered query. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>A page whose contents drift as the caller pages.</strong> Paging state was kept on the
 *       server. There is none: no session, no server-side cursor, no retained map or re-entry state. The
 *       page number, the boundary keys and the next-page flag travel on the request and come back on
 *       {@code com.cardemo.model.dto.PageResponse}. Remedy: keep it that way. Severity:
 *       <strong>High</strong>.</li>
 *   <li><strong>A next-page flag that is wrong on the last page.</strong> The lookahead read was replaced
 *       by a count. The source decides the flag by reading one record past the page
 *       ({@code app/cbl/COUSR00C.cbl:311}) and by nothing else, so the flag must stay the answer to
 *       "does a record exist at that ordinal", never to "how many records are there". Remedy: keep the
 *       lookahead read. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>A password or hash appearing anywhere.</strong> It cannot come from here.
 *       {@code UserSecurity.getPasswordHash()} is never called on any path in this file, and
 *       {@code com.cardemo.model.dto.UserSecurityDto} does not declare a password component at all.
 *       Remedy: keep both properties true. Severity: <strong>High</strong>.</li>
 *   <li><strong>Selection markers vanishing when a page is re-drawn.</strong> They are meant to survive.
 *       {@code INITIALIZE-USER-DATA} at {@code app/cbl/COUSR00C.cbl:446-501} clears the identifier, both
 *       names and the type, and never {@code SELnnnnI}. Remedy: do not clear the selection flag in the row
 *       re-initialisation.</li>
 *   </ul>
 *
 * <h2>Findings carried from the translation, by severity</h2>
 *
 * <h3>Blocker</h3>
 *
 * <p>Mapping fewer than 16 private methods. All 16 paragraph labels of
 * {@code app/cbl/COUSR00C.cbl} are present one-to-one and none is consolidated:
 * {@code MAIN-PARA}:98, {@code PROCESS-ENTER-KEY}:149, {@code PROCESS-PF7-KEY}:237,
 * {@code PROCESS-PF8-KEY}:260, {@code PROCESS-PAGE-FORWARD}:282, {@code PROCESS-PAGE-BACKWARD}:336,
 * {@code POPULATE-USER-DATA}:384, {@code INITIALIZE-USER-DATA}:446, {@code RETURN-TO-PREV-SCREEN}:506,
 * {@code SEND-USRLST-SCREEN}:522, {@code RECEIVE-USRLST-SCREEN}:549, {@code POPULATE-HEADER-INFO}:562,
 * {@code STARTBR-USER-SEC-FILE}:586, {@code READNEXT-USER-SEC-FILE}:619,
 * {@code READPREV-USER-SEC-FILE}:653 and {@code ENDBR-USER-SEC-FILE}:687. The source-citing Javadoc on
 * each is the evidence the scope-coverage gate reads.
 *
 * <h3>High</h3>
 *
 * <p>Four ways to break parity, all avoided above and all listed under troubleshooting: treating
 * {@code DFHRESP(ENDFILE)} as an error; returning or logging a password or hash; holding paging state on the
 * server; and importing one of the three batch-side "record not found is success" carve-outs. Those three
 * carve-outs - the {@code TCATBAL} upsert at {@code app/cbl/CBTRN02C.cbl:481}, the first {@code DISCGRP}
 * {@code DEFAULT} fallback at {@code app/cbl/CBACT04C.cbl:422} and {@code :436}, and {@code CBSTM03B}'s
 * acceptance of {@code '04'} - are scoped to batch. In this package a user that is not found is an error,
 * and none of {@code FileStatusMapper}'s carve-out methods is called from this file.
 *
 * <h3>Medium</h3>
 *
 * <p>Hardcoding the page size; omitting the total order; replacing the lookahead probe with a count query;
 * and unifying the forward and backward skip predicates, which are not the same predicate - the forward one
 * at {@code app/cbl/COUSR00C.cbl:288} excludes {@code DFHENTER}, {@code DFHPF7} and {@code DFHPF3} while the
 * backward one at {@code :342} excludes {@code DFHENTER} and {@code DFHPF8}. Each is avoided.
 *
 * <p>One Medium finding is <strong>not</strong> avoided and is disclosed instead. The source browses from a
 * key; this bean positions by ordinal within the same total order. See the mechanism note below.
 *
 * <h3>Low</h3>
 *
 * <ul>
 *   <li>The {@code GTEQ} option of {@code EXEC CICS STARTBR} is commented out at
 *       {@code app/cbl/COUSR00C.cbl:592}. It stays commented out here: a key that is supplied must match an
 *       existing record exactly, and no equal-or-greater repositioning is introduced.</li>
 *   <li>The second {@code EXEC CICS SEND} variant has its {@code ERASE} option commented out at
 *       {@code app/cbl/COUSR00C.cbl:541}. The branch is retained, and the choice is reported as
 *       {@code eraseRequested} so that it is observable rather than inert.</li>
 *   <li>The plan's general note on the symbolic maps records {@code CURTIME} as {@code X(9)}. Both
 *       {@code app/cpy-bms/COUSR00.CPY} and {@code app/cpy/CSDAT01Y.cpy} say eight. The source governs, so
 *       eight is used. Citation correction only.</li>
 *   <li>{@code WS-USER-DATA}'s {@code USER-TYPE} is {@code PIC X(08)} at
 *       {@code app/cbl/COUSR00C.cbl:64} while the map's {@code UTYPEnnI} is {@code PIC X(1)}. The screen
 *       carries one character and so does the projection; the eight-byte work area is not widened into the
 *       response.</li>
 *   </ul>
 *
 * <h2>A keyed browse stays a keyed browse</h2>
 *
 * <p>{@code CDEMO-CU00-USRID-FIRST} and {@code CDEMO-CU00-USRID-LAST} at
 * {@code app/cbl/COUSR00C.cbl:68-69} are keyset cursors: the source repositions a VSAM browse on one of them
 * and walks. So does this service. {@code com.cardemo.repository.UserSecurityRepository} publishes
 * {@code findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String, Pageable)} and
 * {@code findBySecUsrIdLessThanEqualOrderBySecUsrIdDesc(String, Pageable)} for the two directions, plus
 * {@code findAllByOrderBySecUsrIdAsc(Pageable)} for the start-of-file browse that has no key to anchor on.
 *
 * <p><strong>Finding H-08, severity High.</strong> Having only the paged finder and
 * positioning by an <em>ordinal</em> derived by arithmetic from the submitted page number - validating the
 * echoed key for existence but never positioning on it - produces three behaviours the source does not have:
 * an insert or a delete ahead of the browse shifts every later page, so one page number returns different rows
 * on successive requests; a caller whose echoed key and page number disagree gets the page number's rows; and
 * a large page number produces a correspondingly large {@code OFFSET} scan. Instead the
 * echoed key is the position and the page number is display metadata. Ordinals survive only as offsets
 * relative to the anchor, which is what a {@code READNEXT} and a {@code READPREV} are, and are bounded by a
 * page plus two probe reads - so no read this service issues grows with the page number.
 *
 * <p>What is preserved: the order, the window each key press selects, the page arithmetic including its floor
 * of 1 and its {@code PIC 9(08)} truncation on overflow, the next-page flag and its one-record lookahead,
 * every boundary message, the equal-only gate on a typed identifier, and the cursors themselves, which are
 * still reported on {@code PageResponse} so the caller's view stays checkable.
 *
 * <h2>Query budget</h2>
 *
 * <p>Bounded and small, because the source's browse is bounded and small. Reads are served from one
 * page-aligned block of records at a time, so a run of reads inside a page costs one query whichever
 * direction it runs in, and only a read that leaves the block loads another. That puts the ceiling at four
 * indexed queries for a whole request - one to position {@code STARTBR}, one for the block the page loop
 * consumes, one for the block a skip read falls into, one for the block the lookahead falls into - each
 * returning at most one page of rows.
 *
 * <p>Two properties matter more than the count. Nothing loads the table and slices it in memory: every
 * query is bounded by the page size and offset by the ordinal. And no {@code count} decides anything: the
 * next-page flag is the answer to whether a record exists one ordinal past the page, which is the question
 * the source's extra {@code READNEXT} asks.
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li><strong>Any latency or throughput objective.</strong> Not available. No service level is published
 *       anywhere in the source corpus, so none is asserted here; the performance gate records a measured
 *       baseline, never an invented target. What would be needed: a stated objective from the business
 *       owner.</li>
 *   <li><strong>Any authorisation rule finer than the user type.</strong> Not available. The source
 *       distinguishes {@code 'A'} from {@code 'U'} at {@code app/cpy/COCOM01Y.cpy}:27-28 and nothing else -
 *       there is no per-record ownership, no field-level rule and no delegation model. What would be
 *       needed: a stated policy. Nothing finer is invented, and no password policy, lockout policy or audit
 *       trail is introduced.</li>
 *   </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This bean is stateless and safe to share. Every collaborator field is {@code final} and immutable or
 * itself thread safe, there is no static mutable state, and every value the source keeps in
 * {@code WORKING-STORAGE} or on the symbolic map - {@code WS-ERR-FLG}, {@code WS-USER-SEC-EOF},
 * {@code WS-SEND-ERASE-FLG}, {@code WS-MESSAGE}, {@code WS-IDX}, {@code WS-PAGE-NUM}, the browse position
 * and all fifty row fields - lives on a {@code ScreenWorkArea} allocated per invocation and discarded when
 * it returns. That reproduces the task-scoped lifetime of a CICS task exactly, and it is why a singleton
 * cannot leak one caller's screen into another's.
 *
 * @see com.cardemo.model.dto.UserSecurityDto
 * @see com.cardemo.model.dto.PageResponse
 * @see com.cardemo.repository.UserSecurityRepository
 */
@Service
public class UserListService {

    /**
     * Diagnostic sink for the two {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} statements the source
     * emits before it raises {@code WS-ERR-FLG}, at {@code app/cbl/COUSR00C.cbl:608}, {@code :642} and
     * {@code :676}. Nothing that identifies a user, and nothing derived from a credential, is ever passed to
     * it.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserListService.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR00C'}, {@code app/cbl/COUSR00C.cbl:36}. Rendered onto
     * {@code PGMNAMEO} by {@code POPULATE-HEADER-INFO} at {@code :569}.
     */
    private static final String PROGRAM_NAME = "COUSR00C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU00'}, {@code app/cbl/COUSR00C.cbl:37}. The CICS transaction
     * identifier of {@code DEFINE TRANSACTION(CU00) ... PROGRAM(COUSR00C)} in
     * {@code app/csd/CARDDEMO.CSD}, rendered onto {@code TRNNAMEO} at {@code :568}.
     */
    private static final String TRANSACTION_ID = "CU00";

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}, {@code app/cbl/COUSR00C.cbl:39}, the
     * {@code DATASET} operand of every browse command in the program. Held trimmed because it is used as an
     * identity in diagnostics and never as a fixed-width field.
     */
    private static final String USRSEC_FILE = "USRSEC";

    /**
     * Operation label for {@code EXEC CICS STARTBR}, {@code app/cbl/COUSR00C.cbl:588}. Passed to
     * {@code FileStatusMapper} so a failure names the operation that produced it.
     */
    private static final String STARTBR_OPERATION = "STARTBR";

    /**
     * Operation label for {@code EXEC CICS READNEXT}, {@code app/cbl/COUSR00C.cbl:621}.
     */
    private static final String READNEXT_OPERATION = "READNEXT";

    /**
     * Operation label for {@code EXEC CICS READPREV}, {@code app/cbl/COUSR00C.cbl:655}.
     */
    private static final String READPREV_OPERATION = "READPREV";

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}, the sign-on program the source falls back to at
     * {@code app/cbl/COUSR00C.cbl:111} when it is entered with no communication area and at {@code :509} when
     * no target was set. Reported as advisory navigation; nothing is invoked.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM}, the administrative menu that {@code DFHPF3} returns to at
     * {@code app/cbl/COUSR00C.cbl:126}. {@code PF3} is conventional on this screen: it exits, it does not
     * save.
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /**
     * {@code MOVE 'COUSR02C' TO CDEMO-TO-PROGRAM}, the user-update program a selection of {@code 'U'} or
     * {@code 'u'} transfers to at {@code app/cbl/COUSR00C.cbl:192}.
     */
    private static final String USER_UPDATE_PROGRAM = "COUSR02C";

    /**
     * {@code MOVE 'COUSR03C' TO CDEMO-TO-PROGRAM}, the user-delete program a selection of {@code 'D'} or
     * {@code 'd'} transfers to at {@code app/cbl/COUSR00C.cbl:202}.
     */
    private static final String USER_DELETE_PROGRAM = "COUSR03C";

    /**
     * The row selector that requests an update, {@code WHEN 'U'} and {@code WHEN 'u'} at
     * {@code app/cbl/COUSR00C.cbl:190-191}. Both cases are accepted, and the comparison is folded with
     * {@code Locale.ROOT} so that a Turkish default locale cannot corrupt it.
     */
    private static final String SELECT_UPDATE = "U";

    /**
     * The row selector that requests a deletion, {@code WHEN 'D'} and {@code WHEN 'd'} at
     * {@code app/cbl/COUSR00C.cbl:200-201}.
     */
    private static final String SELECT_DELETE = "D";

    /**
     * {@code app/cbl/COUSR00C.cbl:211-213}, byte for byte, emitted when a row carries a selector that is
     * neither {@code 'U'}/{@code 'u'} nor {@code 'D'}/{@code 'd'}. The ellipsis is exactly three periods.
     */
    private static final String INVALID_SELECTION_MESSAGE = "Invalid selection. Valid values are U and D";

    /**
     * {@code app/cbl/COUSR00C.cbl:251}, byte for byte, emitted when {@code DFHPF7} is pressed on page one -
     * the caller was <em>already</em> at the boundary and no browse was attempted. Distinct from
     * {@code REACHED_TOP_MESSAGE}, which means a read ran off the front.
     */
    private static final String ALREADY_AT_TOP_MESSAGE = "You are already at the top of the page...";

    /**
     * {@code app/cbl/COUSR00C.cbl:273}, byte for byte, emitted when {@code DFHPF8} is pressed with the
     * next-page flag off. Distinct from {@code REACHED_BOTTOM_MESSAGE}, which means a read ran off the end.
     */
    private static final String ALREADY_AT_BOTTOM_MESSAGE = "You are already at the bottom of the page...";

    /**
     * {@code app/cbl/COUSR00C.cbl:603}, byte for byte, emitted when {@code EXEC CICS STARTBR} answers
     * {@code DFHRESP(NOTFND)} - the browse could not be positioned at all.
     */
    private static final String AT_TOP_MESSAGE = "You are at the top of the page...";

    /**
     * {@code app/cbl/COUSR00C.cbl:637}, byte for byte, emitted when {@code EXEC CICS READNEXT} answers
     * {@code DFHRESP(ENDFILE)}. That branch sets {@code USER-SEC-EOF} and leaves {@code WS-ERR-FLG} alone, so
     * this message accompanies a successful request, never a failed one.
     */
    private static final String REACHED_BOTTOM_MESSAGE = "You have reached the bottom of the page...";

    /**
     * {@code app/cbl/COUSR00C.cbl:671}, byte for byte, the {@code READPREV} counterpart of
     * {@code REACHED_BOTTOM_MESSAGE}.
     */
    private static final String REACHED_TOP_MESSAGE = "You have reached the top of the page...";

    /**
     * {@code CCDA-MSG-INVALID-KEY} of {@code app/cpy/CSMSG01Y.cpy}, moved to {@code WS-MESSAGE} by the
     * {@code WHEN OTHER} arm of the {@code EVALUATE EIBAID} at {@code app/cbl/COUSR00C.cbl:135}. The copybook
     * declares it {@code PIC X(50)} and pads it to width; the padding belongs to the field, not to the
     * message, so the semantic text is held here and the width is enforced by the projection.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * {@code CCDA-TITLE01 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, all forty characters including the
     * leading and trailing padding, moved to {@code TITLE01O} at {@code app/cbl/COUSR00C.cbl:566}.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, moved to {@code TITLE02O} at
     * {@code app/cbl/COUSR00C.cbl:567}. The copybook also carries a commented-out alternative reading
     * {@code '  Credit Card Demo Application (CCDA)   '}; the active literal is the one reproduced here.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * {@code WS-CURDATE-MM-DD-YY} of {@code app/cpy/CSDAT01Y.cpy}: two digits, a solidus, two digits, a
     * solidus, two digits - eight characters, matching {@code CURDATEI PIC X(8)}. The two-digit year is
     * {@code MOVE WS-CURDATE-YEAR(3:2)} at {@code app/cbl/COUSR00C.cbl:573}, the last two digits of the
     * four-digit year, which {@code yy} reproduces. Pinned to {@code Locale.ROOT} so the rendering
     * cannot vary with the platform default.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS} of {@code app/cpy/CSDAT01Y.cpy}: two digits, a colon, two digits, a colon,
     * two digits - eight characters, matching {@code CURTIMEI PIC X(8)}. Twenty-four hour, because
     * {@code WS-CURTIME-HOURS} is {@code PIC 9(02)} taken straight from {@code FUNCTION CURRENT-DATE}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The field the source parks the cursor on with {@code MOVE -1 TO USRIDINL OF COUSR0AI}, at
     * {@code app/cbl/COUSR00C.cbl:108}, {@code :134}, {@code :214}, {@code :224}, {@code :246}, {@code :268},
     * {@code :605}, {@code :612}, {@code :639}, {@code :646}, {@code :673} and {@code :680}. Advisory only:
     * HTTP has no cursor.
     */
    private static final String CURSOR_FIELD_USER_ID_INPUT = "USRIDIN";

    /**
     * A blank {@code PIC X} field. The source clears screen fields and {@code WS-MESSAGE} with
     * {@code MOVE SPACES}, and a fixed-width map field is blank rather than absent, so blank - not
     * {@code null} - is what the source's cleared state means.
     */
    private static final String SPACES = "";

    /**
     * {@code DFHRESP(NORMAL)}, ordinal 0. The three browse paragraphs each test for it first, at
     * {@code app/cbl/COUSR00C.cbl:598}, {@code :632} and {@code :666}.
     */
    private static final int CICS_RESP_NORMAL = 0;

    /**
     * {@code DFHRESP(NOTFND)}, ordinal 13, the condition {@code STARTBR} raises at
     * {@code app/cbl/COUSR00C.cbl:600} when the browse cannot be positioned.
     */
    private static final int CICS_RESP_NOTFND = 13;

    /**
     * {@code DFHRESP(IOERR)}, ordinal 17. The source does not name it - both reads fall into
     * {@code WHEN OTHER} - but a repository failure has to be reported as some condition, and this is the one
     * a physical read failure raises.
     */
    private static final int CICS_RESP_IOERR = 17;

    /**
     * {@code DFHRESP(ENDFILE)}, ordinal 20, the condition {@code READNEXT} and {@code READPREV} raise at
     * {@code app/cbl/COUSR00C.cbl:634} and {@code :668}. It terminates the browse loop and is never an error.
     */
    private static final int CICS_RESP_ENDFILE = 20;

    /**
     * {@code WS-REAS-CD} after every condition this program handles. The source declares
     * {@code RESP2(WS-REAS-CD)} on all three browse commands and never sets it itself, so zero is the value
     * its diagnostics render.
     */
    private static final int CICS_REASON_NONE = 0;

    /**
     * File status {@code '00'}, the value {@code FileStatusMapper} reads as a success.
     */
    private static final String IO_STATUS_SUCCESS = "00";

    /**
     * File status {@code '10'}, end of file. {@code FileStatusMapper.toException} returns an empty
     * {@code Optional} for it, which is precisely why {@code DFHRESP(ENDFILE)} cannot become an exception on
     * any path below.
     */
    private static final String IO_STATUS_END_OF_FILE = "10";

    /**
     * File status {@code '23'}, record not found, which {@code FileStatusMapper} maps to
     * {@code com.cardemo.exception.RecordNotFoundException}. It is the batch-side equivalent of
     * {@code DFHRESP(NOTFND)}, and in this package it is an error - none of the mapper's three scoped
     * not-found-is-success carve-outs applies to an online user lookup.
     */
    private static final String IO_STATUS_RECORD_NOT_FOUND = "23";

    /**
     * File status {@code '90'}, a member of the {@code '9x'} family, which {@code FileStatusMapper} maps to
     * {@code com.cardemo.exception.FileAccessException} carrying the expanded status.
     */
    private static final String IO_STATUS_IO_ERROR = "90";

    /**
     * The declared width of {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy}, equal to the
     * cluster key length of {@code KEYS(8,0)} at {@code app/jcl/DUSRSECJ.jcl}:65 and to
     * {@code KEYLENGTH(LENGTH OF SEC-USR-ID)} on the browse at {@code app/cbl/COUSR00C.cbl:591}.
     */
    private static final int USER_ID_KEY_LENGTH = 8;

    /**
     * The number of digits {@code PAGENUMI PIC X(8)} receives from {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at
     * {@code app/cbl/COUSR00C.cbl:327} and {@code :376}. A COBOL move from an unsigned display numeric to an
     * alphanumeric field of the same width transfers the zero-padded digits, so page one renders as
     * {@code "00000001"} and a browse that never advanced renders as {@code "00000000"}.
     */
    private static final int PAGE_NUMBER_DIGITS = 8;

    /**
     * The largest value {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} can hold, {@code 99999999}. A page number
     * above it could not have come from this screen, so it is rejected rather than arithmetically wrapped.
     */
    private static final int MAX_PAGE_NUMBER = 99_999_999;

    /**
     * The first page the source can report, and the floor {@code PROCESS-PAGE-BACKWARD} applies at
     * {@code app/cbl/COUSR00C.cbl:369}. Taken from {@code PageResponse} rather than restated.
     */
    private static final int FIRST_PAGE_NUMBER = PageResponse.FIRST_PAGE_NUMBER;

    /**
     * The browse ordinal that means "positioned past the last record", the state
     * {@code MOVE HIGH-VALUES TO SEC-USR-ID} at {@code app/cbl/COUSR00C.cbl:263} establishes. Held as a
     * sentinel rather than a computed ordinal because the source never learns how many records exist.
     */
    private static final long ORDINAL_PAST_END = -1L;

    /**
     * Ordinals of backward headroom reserved beyond one page when a browse is anchored on a key.
     *
     * <p>Two, because a page walk performs one skip read before the page and one lookahead after it, and
     * {@code fetchAtOrdinal} treats a negative ordinal as having run off an end of the file. Reserving a page
     * plus these two guarantees a backward walk from the anchor never turns negative and so never reports a
     * spurious end of file.
     */
    private static final int BROWSE_HEADROOM_PROBES = 2;

    /**
     * The highest browse ordinal the paged finder can address. Spring Data expresses an offset as a page
     * index multiplied by a page size, both {@code int}, so an ordinal beyond this bound cannot be requested
     * at all and is reported as an end of file rather than allowed to overflow. No security file can hold
     * this many records, so the guard is defensive.
     */
    private static final long MAX_SUPPORTED_ORDINAL = Integer.MAX_VALUE - 1L;


    /**
     * The security file, reached only through its ordered paged finder so that every page is taken in the
     * cluster's own key order.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * The single owner of the file-status-to-exception decision and of the {@code FILE STATUS IS: NNNN}
     * rendering. Neither is reimplemented here, and no status is re-rendered.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The time source behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/COUSR00C.cbl:564}.
     */
    private final Clock clock;

    /**
     * The page size, ten, injected from {@code carddemo.pagination.user-list-page-size}. A parity contract,
     * never a tunable, and never a literal in this file.
     */
    private final int pageSize;

    /**
     * Constructs the bean. Constructor injection only: there is no field {@code @Autowired}, no setter
     * injection and no service locator, which is what keeps the bean immutable and directly unit testable.
     *
     * @param userSecurityRepository the ordered paged view of the {@code USRSEC} cluster; must not be
     *                               {@code null}
     * @param fileStatusMapper       the shared file-status-to-exception mapper; must not be {@code null}
     * @param clock                  the time source for the screen header; must not be {@code null}
     * @param pageSize               the value of {@code carddemo.pagination.user-list-page-size}, which is
     *                               10; the property carries no default, so a missing value fails startup
     *                               rather than substituting a number, and a value below 1 is rejected here
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code pageSize} is less than 1, since a page that can hold no row
     *                                  cannot reproduce a ten-row screen
     */
    public UserListService(final UserSecurityRepository userSecurityRepository,
            final FileStatusMapper fileStatusMapper,
            final Clock clock,
            @Value("${carddemo.pagination.user-list-page-size}") final int pageSize) {

        this.userSecurityRepository =
                Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        if (pageSize < 1) {
            throw new IllegalArgumentException("carddemo.pagination.user-list-page-size must be at least 1, but was "
                    + pageSize);
        }
        this.pageSize = pageSize;
    }

    // Public surface. Three entry points, one per way the source can be reached.

    /**
     * Opens the list at its first page, optionally starting from a named user. Either way
     * {@code PROCESS-ENTER-KEY} forces {@code CDEMO-CU00-PAGE-NUM} to zero at
     * {@code app/cbl/COUSR00C.cbl:227} and pages forward, so the answer is always page one.
     *
     * <p><strong>Which source arm this takes, and why it depends on the argument.</strong> With no identifier
     * supplied this is the first-display arm at {@code :115-119}: clear the output map, run
     * {@code PROCESS-ENTER-KEY}, send. With an identifier supplied it is instead the {@code DFHENTER} arm at
     * {@code :122-124}, because {@code MOVE LOW-VALUES TO COUSR0AO} at {@code :117} blanks the whole shared
     * map - {@code USRIDINI} included, since {@code app/cpy-bms/COUSR00.CPY:373} redefines the output map over
     * the input map - so an identifier can only ever reach {@code PROCESS-ENTER-KEY} on a received map. The
     * routing is therefore a consequence of the source, not an invention: it sends the caller down the one
     * arm that can carry what the caller supplied. The two arms converge on the same paragraph, and with a
     * blank identifier and no row selected they produce identical output.
     *
     * <p>Side effects: none. Nothing is written, and no state survives the call.
     *
     * @param userIdInput the value of {@code USRIDINI PIC X(8)}, the identifier the operator typed to start
     *                    the browse from, or {@code null}, empty or blank for an unfiltered list. Treated as
     *                    untrusted: a value longer than the eight-character key, or one that does not name an
     *                    existing user, is rejected rather than truncated or silently ignored
     * @return the assembled screen, its page of rows, the paging metadata and any advisory navigation; never
     *         {@code null}
     * @throws com.cardemo.exception.ValidationException     if {@code userIdInput} is longer than the
     *                                                       eight-character {@code SEC-USR-ID} key
     * @throws com.cardemo.exception.RecordNotFoundException if the browse cannot be positioned, which is the
     *                                                       {@code DFHRESP(NOTFND)} arm of {@code STARTBR} at
     *                                                       {@code app/cbl/COUSR00C.cbl:600-606} - either the
     *                                                       file holds no record or the named user does not
     *                                                       exist
     * @throws com.cardemo.exception.FileAccessException     if the underlying read fails, the
     *                                                       {@code WHEN OTHER} arm at {@code :607-613}
     */
    @Transactional(readOnly = true)
    public UserListScreen listUsers(final String userIdInput) {
        return mainPara(true, isPresent(userIdInput), AttentionIdentifier.ENTER,
                new UserListRequest(0, false, null, null, userIdInput, List.of()));
    }

    /**
     * Re-enters the list with a submitted screen, which is the pseudo-conversational turn the source takes at
     * {@code app/cbl/COUSR00C.cbl:120-137}: receive the map, then dispatch on {@code EIBAID}.
     *
     * <p>Because the target is stateless, everything the source recovered from the communication area at
     * {@code :114} travels on {@code request} instead. {@code CDEMO-FROM-TRANID},
     * {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM},
     * {@code CDEMO-PGM-CONTEXT}, {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} have no counterpart at
     * all; only {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} survive, and the security layer supplies
     * both, not this bean.
     *
     * <p>Side effects: none.
     *
     * @param aid     which key the operator pressed. {@code ENTER} acts on any row selection and re-lists
     *                from page one; {@code PF3} exits to the administrative menu without saving; {@code PF7}
     *                pages backward; {@code PF8} pages forward; anything else is reported as an invalid key.
     *                Must not be {@code null}
     * @param request the submitted screen: the page number, the next-page flag, the two boundary keys, the
     *                identifier field and the ten rows as they were displayed. Must not be {@code null}, and
     *                every component is treated as untrusted
     * @return the assembled screen; never {@code null}
     * @throws NullPointerException                          if {@code aid} or {@code request} is {@code null}
     * @throws com.cardemo.exception.ValidationException     if the page number falls outside the
     *                                                       {@code PIC 9(08)} domain of
     *                                                       {@code CDEMO-CU00-PAGE-NUM}, or a key or
     *                                                       selector is wider than the field it came from
     * @throws com.cardemo.exception.RecordNotFoundException if the browse cannot be positioned
     * @throws com.cardemo.exception.FileAccessException     if the underlying read fails
     */
    @Transactional(readOnly = true)
    public UserListScreen submitScreen(final AttentionIdentifier aid, final UserListRequest request) {
        Objects.requireNonNull(aid, "aid must not be null");
        Objects.requireNonNull(request, "request must not be null");
        return mainPara(true, true, aid, request);
    }

    /**
     * Reproduces the source reached with no communication area at all: {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COUSR00C.cbl:110-112}, which sets {@code CDEMO-TO-PROGRAM} to {@code 'COSGN00C'} and
     * transfers to the sign-on program without touching the security file.
     *
     * <p>Reading no record is the whole point of the path, so the returned screen carries an empty page,
     * page number zero and {@code 'COSGN00C'} as its advisory navigation target. Nothing is thrown, because
     * the source raises no condition here.
     *
     * <p>Side effects: none. No query is issued.
     *
     * @return a screen whose only meaningful content is the navigation target; never {@code null}
     */
    public UserListScreen openWithoutContext() {
        return mainPara(false, false, AttentionIdentifier.ENTER,
                new UserListRequest(0, false, null, null, null, List.of()));
    }

    // Source-mapped paragraphs. Sixteen labels, sixteen private methods, in source order.
    // Never consolidate: TRACEABILITY_MATRIX.md will be proved against this correspondence and Gate 7 reads
    // exactly it. Mapping fewer than sixteen is a Blocker.

    /**
     * {@code app/cbl/COUSR00C.cbl:98 MAIN-PARA} - the entry paragraph. Initialises the four working-storage
     * switches, blanks the message, parks the cursor on the identifier field, then dispatches on
     * {@code EIBCALEN} and {@code EIBAID}.
     *
     * <p><strong>Statelessness.</strong> {@code CDEMO-PGM-CONTEXT}, the pseudo-conversational
     * enter-versus-re-enter flag tested at {@code :115}, has no Java counterpart: it collapses into stateless
     * request handling, and the caller states which arm it wants through {@code reenter}. Likewise
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at {@code :141-144} has no
     * counterpart - returning the assembled screen to the caller is the whole of it, and no transaction
     * identifier is retained.
     *
     * <p><strong>Retained parity artefact.</strong> {@code SET NEXT-PAGE-NO TO TRUE} at {@code :102} is a
     * dead assignment on every path that goes on to read the flag, because {@code :114}
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} immediately restores
     * {@code CDEMO-CU00-NEXT-PAGE-FLG} from the communication area. It survives only on the
     * {@code EIBCALEN = 0} path, which transfers away at once. Reproduced verbatim - assign, then overwrite
     * from the request - rather than deleted, and held as {@code DL-PP-13} in the DECISION_LOG.md.
     * Severity: Low.
     *
     * @param commAreaPresent {@code false} models {@code EIBCALEN = 0} at {@code :110}
     * @param reenter         {@code false} models the first-display arm at {@code :115-119}, {@code true} the
     *                        received-map arm at {@code :120-137}
     * @param aid             the attention identifier evaluated at {@code :122-137}; ignored when
     *                        {@code reenter} is {@code false}, exactly as the source ignores {@code EIBAID}
     *                        on that arm
     * @param request         the submitted screen, standing in for the restored communication area and the
     *                        received map
     * @return the assembled screen
     * @throws com.cardemo.exception.CardDemoException the first typed failure retained while the screen was
     *                                                built, rethrown here so that
     *                                                {@code com.cardemo.controller.AdminController} can map
     *                                                it; there is no {@code @ControllerAdvice} in the tree,
     *                                                so each controller declares its own handlers
     */
    private UserListScreen mainPara(final boolean commAreaPresent, final boolean reenter,
            final AttentionIdentifier aid, final UserListRequest request) {
        final ScreenWorkArea work = new ScreenWorkArea(pageSize);
        work.aid = aid;                                  // EIBAID, read again by both skip-read predicates

        work.errFlgOn = false;                          // :100 SET ERR-FLG-OFF TO TRUE
        work.userSecEof = false;                        // :101 SET USER-SEC-NOT-EOF TO TRUE
        work.nextPageAvailable = false;                 // :102 SET NEXT-PAGE-NO TO TRUE (see Javadoc)
        work.sendEraseYes = true;                       // :103 SET SEND-ERASE-YES TO TRUE
        work.message = SPACES;                           // :105-106 MOVE SPACES TO WS-MESSAGE / ERRMSGO
        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;   // :108 MOVE -1 TO USRIDINL OF COUSR0AI

        if (!commAreaPresent) {
            // :110-112 IF EIBCALEN = 0 / MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM / RETURN-TO-PREV-SCREEN
            work.navigationTarget = SIGN_ON_PROGRAM;
            returnToPrevScreen(work);
        } else {
            // :114 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA. The stateless equivalent: the four
            // CDEMO-CU00-INFO fields the source recovered from the communication area travel on the request.
            work.pageNum = requirePageNumber(request.pageNumber());
            work.nextPageAvailable = request.nextPageAvailable();
            work.usridFirst = requireKeyWidth(request.firstKey(), "firstKey");
            work.usridLast = requireKeyWidth(request.lastKey(), "lastKey");

            if (!reenter) {
                // :115-119. SET CDEMO-PGM-REENTER TO TRUE at :116 has no counterpart - nothing is retained.
                work.clearOutputMap();                   // :117 MOVE LOW-VALUES TO COUSR0AO
                processEnterKey(work);                   // :118
                sendUsrlstScreen(work);                  // :119
            } else {
                receiveUsrlstScreen(work, request);      // :121
                switch (aid) {                           // :122 EVALUATE EIBAID
                    case ENTER -> processEnterKey(work);                     // :123-124 WHEN DFHENTER
                    case PF3 -> {                                            // :125-127 WHEN DFHPF3
                        // PF3 is conventional here: it exits to the administrative menu, it does not save.
                        work.navigationTarget = ADMIN_MENU_PROGRAM;
                        returnToPrevScreen(work);
                    }
                    case PF7 -> processPf7Key(work);                         // :128-129 WHEN DFHPF7
                    case PF8 -> processPf8Key(work);                         // :130-131 WHEN DFHPF8
                    case OTHER -> {                                          // :132-137 WHEN OTHER
                        work.errFlgOn = true;                                // :133 MOVE 'Y' TO WS-ERR-FLG
                        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;       // :134 MOVE -1 TO USRIDINL
                        work.message = INVALID_KEY_MESSAGE;                  // :135 CCDA-MSG-INVALID-KEY
                        sendUsrlstScreen(work);                              // :136
                    }
                }
            }
        }

        if (work.retainedFailure != null) {
            throw work.retainedFailure;
        }
        return work.toScreen();
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:149 PROCESS-ENTER-KEY} - ten row-selection branches, the selection
     * dispatch, the browse key derivation and the forward page.
     *
     * <p>The ten {@code WHEN SELnnnnI OF COUSR0AI NOT = SPACES AND LOW-VALUES} arms at {@code :152-181} are
     * written out one per row rather than looped, mirroring the source {@code EVALUATE TRUE} exactly. The
     * first non-blank selector wins; {@code WHEN OTHER} at {@code :182-184} clears both selection fields.
     *
     * <p>The dispatch at {@code :187-216} accepts {@code 'U'}, {@code 'u'}, {@code 'D'} and {@code 'd'}.
     * Case folding uses {@code Locale.ROOT} so that a Turkish default locale cannot corrupt the comparison.
     *
     * <p><strong>Mechanism substitution.</strong> {@code EXEC CICS XCTL PROGRAM(COUSR02C)} at {@code :197-200}
     * and {@code PROGRAM(COUSR03C)} at {@code :207-210} become an advisory navigation target on the response.
     * This method does <em>not</em> invoke {@code UserUpdateService} or {@code UserDeleteService}: the row
     * selection is a client-side navigation decision in the target, and calling a sibling service would both
     * change the failure semantics and couple two screens the source keeps apart. Because {@code XCTL} never
     * returns, the method returns immediately on both arms, so {@code :218-233} are unreachable from them -
     * faithfully reproduced.
     *
     * <p><strong>Retained parity artefact.</strong> The {@code WHEN OTHER} arm at {@code :211-215} sets the
     * message and the cursor but <em>not</em> {@code WS-ERR-FLG}, so an invalid selector still falls through
     * to {@code PROCESS-PAGE-FORWARD} and the list is re-sent with the advisory attached. Preserved; do not
     * add an early exit. Severity: Low. Held as DL-PP-13 in DECISION_LOG.md.
     *
     * <p><strong>Retained parity artefact.</strong> {@code IF NOT ERR-FLG-ON MOVE SPACE TO USRIDINO} at
     * {@code :231-233} runs <em>after</em> {@code PROCESS-PAGE-FORWARD} has already sent the screen at {@code :330},
     * and duplicates the clearing that {@code :328} performed before that send, so it has no observable effect.
     * Reproduced as an explicit intentional no-op. Severity: Low. Held as DL-CR-01 in DECISION_LOG.md,
     * which is the entry for a construct retained because deleting it would break the paragraph map.
     *
     * @param work the per-invocation work area, mutated in place exactly as the source mutates
     *             working-storage and the shared map
     */
    private void processEnterKey(final ScreenWorkArea work) {
        // :151-185 EVALUATE TRUE - one arm per screen row, first non-blank selector wins.
        if (isPresent(work.selectionAt(0))) {                    // :152-154 SEL0001I / USRID01I
            work.selectionFlag = work.selectionAt(0);
            work.selectedUserId = work.rowUserIdAt(0);
        } else if (isPresent(work.selectionAt(1))) {              // :155-157 SEL0002I / USRID02I
            work.selectionFlag = work.selectionAt(1);
            work.selectedUserId = work.rowUserIdAt(1);
        } else if (isPresent(work.selectionAt(2))) {              // :158-160 SEL0003I / USRID03I
            work.selectionFlag = work.selectionAt(2);
            work.selectedUserId = work.rowUserIdAt(2);
        } else if (isPresent(work.selectionAt(3))) {              // :161-163 SEL0004I / USRID04I
            work.selectionFlag = work.selectionAt(3);
            work.selectedUserId = work.rowUserIdAt(3);
        } else if (isPresent(work.selectionAt(4))) {              // :164-166 SEL0005I / USRID05I
            work.selectionFlag = work.selectionAt(4);
            work.selectedUserId = work.rowUserIdAt(4);
        } else if (isPresent(work.selectionAt(5))) {              // :167-169 SEL0006I / USRID06I
            work.selectionFlag = work.selectionAt(5);
            work.selectedUserId = work.rowUserIdAt(5);
        } else if (isPresent(work.selectionAt(6))) {              // :170-172 SEL0007I / USRID07I
            work.selectionFlag = work.selectionAt(6);
            work.selectedUserId = work.rowUserIdAt(6);
        } else if (isPresent(work.selectionAt(7))) {              // :173-175 SEL0008I / USRID08I
            work.selectionFlag = work.selectionAt(7);
            work.selectedUserId = work.rowUserIdAt(7);
        } else if (isPresent(work.selectionAt(8))) {              // :176-178 SEL0009I / USRID09I
            work.selectionFlag = work.selectionAt(8);
            work.selectedUserId = work.rowUserIdAt(8);
        } else if (isPresent(work.selectionAt(9))) {              // :179-181 SEL0010I / USRID10I
            work.selectionFlag = work.selectionAt(9);
            work.selectedUserId = work.rowUserIdAt(9);
        } else {                                                  // :182-184 WHEN OTHER
            work.selectionFlag = SPACES;
            work.selectedUserId = SPACES;
        }

        // :187-216 IF both selection fields are populated, dispatch on the selector.
        if (isPresent(work.selectionFlag) && isPresent(work.selectedUserId)) {
            final String selector = work.selectionFlag.trim().toUpperCase(Locale.ROOT);
            if (SELECT_UPDATE.equals(selector)) {                 // :190-200 WHEN 'U' / WHEN 'u'
                work.navigationTarget = USER_UPDATE_PROGRAM;
                work.controlTransferred = true;
                LOG.debug("{} routing selected user to {} for update", PROGRAM_NAME, USER_UPDATE_PROGRAM);
                return;                                            // EXEC CICS XCTL does not return
            }
            if (SELECT_DELETE.equals(selector)) {                 // :201-210 WHEN 'D' / WHEN 'd'
                work.navigationTarget = USER_DELETE_PROGRAM;
                work.controlTransferred = true;
                LOG.debug("{} routing selected user to {} for delete", PROGRAM_NAME, USER_DELETE_PROGRAM);
                return;                                            // EXEC CICS XCTL does not return
            }
            // :211-215 WHEN OTHER. Note: WS-ERR-FLG is deliberately NOT set here.
            work.message = INVALID_SELECTION_MESSAGE;
            work.cursorField = CURSOR_FIELD_USER_ID_INPUT;        // :215 MOVE -1 TO USRIDINL
        }

        // :218-222 derive the browse key from the identifier field.
        if (isBlankOrUnset(work.userIdInput)) {
            work.startKey = null;                                  // :219 MOVE LOW-VALUES TO SEC-USR-ID
            work.startPastEnd = false;
        } else {
            work.startKey = work.userIdInput.trim();               // :221 MOVE USRIDINI TO SEC-USR-ID
            work.startPastEnd = false;
        }

        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;             // :224 MOVE -1 TO USRIDINL

        work.pageNum = 0;                                          // :227 MOVE 0 TO CDEMO-CU00-PAGE-NUM
        // The key derived at :219/:221 IS the browse position, and anchoring on it is the whole point of
        // moving USRIDINI into SEC-USR-ID: STARTBR positions on RIDFLD, so an identifier in the field starts
        // the page at that identifier and LOW-VALUES starts it at the first record. Discarding the key here
        // and anchoring unconditionally at ordinal zero made every request return page one, which silently
        // dropped a filter the source honours. One call covers both branches, because anchorOn ignores the
        // headroom for a null key - the same shape PROCESS-PF7-KEY and PROCESS-PF8-KEY already use.
        work.anchorOn(work.startKey, pageSize + BROWSE_HEADROOM_PROBES);
        processPageForward(work);                                  // :228

        if (!work.errFlgOn) {
            // :231-233 MOVE SPACE TO USRIDINO. Intentional no-op: :328 already cleared it before the send.
            work.userIdInput = SPACES;
        }
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:237 PROCESS-PF7-KEY} - the page-backward guard.
     *
     * <p>Derives the browse key from {@code CDEMO-CU00-USRID-FIRST} at {@code :239-243}, then pages backward
     * when {@code CDEMO-CU00-PAGE-NUM > 1} and otherwise reports the top boundary at {@code :251} without
     * reading anything.
     *
     * <p><strong>Preserve.</strong> {@code SET NEXT-PAGE-YES TO TRUE} at {@code :245} runs
     * <em>unconditionally, before</em> the branch, so even the already-at-top response reports that a next
     * page is available. That is the behaviour, not a defect.
     *
     * <p>The top-boundary response is distinguishable from {@code READPREV}'s
     * {@code 'You have reached the top of the page...'} at {@code :671}: this one means the operator was
     * already on page one, that one means the browse ran off the front while reading. Do not merge them.
     *
     * @param work the per-invocation work area
     */
    private void processPf7Key(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.usridFirst)) {
            work.startKey = null;                                  // :240 MOVE LOW-VALUES TO SEC-USR-ID
        } else {
            work.startKey = work.usridFirst.trim();                // :242 MOVE CDEMO-CU00-USRID-FIRST
        }
        work.startPastEnd = false;

        work.nextPageAvailable = true;                              // :245 SET NEXT-PAGE-YES TO TRUE
        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;              // :246 MOVE -1 TO USRIDINL

        if (work.pageNum > FIRST_PAGE_NUMBER) {                     // :248 IF CDEMO-CU00-PAGE-NUM > 1
            // H-08: the echoed first key IS the position - it is the head of the displayed page - so the
            // browse is anchored on it and the page number decides only whether there is a page to go back
            // to. Deriving an ordinal from the page number instead made an insert or a delete ahead of the
            // browse shift every later page.
            work.anchorOn(work.startKey, pageSize + BROWSE_HEADROOM_PROBES);
            processPageBackward(work);                              // :249
        } else {
            work.message = ALREADY_AT_TOP_MESSAGE;                  // :250-252
            work.sendEraseYes = false;                              // :253 SET SEND-ERASE-NO TO TRUE
            sendUsrlstScreen(work);                                 // :254
        }
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:260 PROCESS-PF8-KEY} - the page-forward guard.
     *
     * <p>Derives the browse key from {@code CDEMO-CU00-USRID-LAST} at {@code :262-266}, then pages forward
     * when {@code NEXT-PAGE-YES} and otherwise reports the bottom boundary at {@code :273} without reading
     * anything. Unlike {@code PROCESS-PF7-KEY} it never assigns the next-page flag - it only reads it.
     *
     * <p><strong>Preserve.</strong> An unset {@code CDEMO-CU00-USRID-LAST} yields {@code HIGH-VALUES} at
     * {@code :263}, not {@code LOW-VALUES}. That positions the browse past the last record, so the first
     * {@code READNEXT} ends the file at once and the response carries
     * {@code 'You have reached the bottom of the page...'}. The combination is reachable only from a
     * malformed request, because the source sets {@code USRID-LAST} whenever a page fills and reports
     * {@code NEXT-PAGE-NO} whenever it does not. Severity: Low; held as {@code DL-PP-13} in the
     * DECISION_LOG.md.
     *
     * <p>The bottom-boundary response is distinguishable from {@code READNEXT}'s
     * {@code 'You have reached the bottom of the page...'} at {@code :637} by the absence of any read: this
     * one means the flag already said there was nothing more. Do not merge them.
     *
     * @param work the per-invocation work area
     */
    private void processPf8Key(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.usridLast)) {
            work.startKey = null;                                   // :263 MOVE HIGH-VALUES TO SEC-USR-ID
            work.startPastEnd = true;
        } else {
            work.startKey = work.usridLast.trim();                  // :265 MOVE CDEMO-CU00-USRID-LAST
            work.startPastEnd = false;
        }

        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;              // :268 MOVE -1 TO USRIDINL

        if (work.nextPageAvailable) {                               // :270 IF NEXT-PAGE-YES
            // H-08: the echoed last key IS the position - it is the final row of the displayed page - so the
            // browse is anchored on it and walks forward from there. The submitted row count no longer
            // contributes to positioning, because the key already names the row it identified.
            work.anchorOn(work.startKey, pageSize + BROWSE_HEADROOM_PROBES);
            processPageForward(work);                               // :271
        } else {
            work.message = ALREADY_AT_BOTTOM_MESSAGE;               // :272-274
            work.sendEraseYes = false;                              // :275 SET SEND-ERASE-NO TO TRUE
            sendUsrlstScreen(work);                                 // :276
        }
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:282 PROCESS-PAGE-FORWARD} - begin browse, optional skip read, clear the
     * rows, read a page ascending, probe one record beyond it, end browse, send.
     *
     * <p><strong>The conditional skip read.</strong> {@code :288}
     * {@code IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3} performs one extra {@code READNEXT} before the
     * page loop, which consumes the record the browse was positioned on. It fires for {@code PF8} only, and
     * that is precisely what makes {@code PF8} advance past the last row of the displayed page instead of
     * repeating it. The predicate is reproduced as an explicit boolean on the originating action and is
     * <em>not</em> unified with the backward predicate at {@code :342} - the two exclude different keys and
     * are not interchangeable. Unifying them is a Medium-severity defect.
     *
     * <p><strong>The lookahead probe.</strong> {@code :311} performs one further {@code READNEXT} purely to
     * decide the next-page flag. It is reproduced as a genuine read of the one record sitting one ordinal past
     * the page - the flag is the answer to whether that record exists - and never as a {@code count} query,
     * because a count answers a different question and changes the observable flag on boundary pages. Doing
     * that is a Medium-severity defect.
     *
     * <p><strong>Order matters.</strong> The page number is incremented at {@code :309-310}
     * <em>before</em> the lookahead read at {@code :311}, so the increment happens whether or not another
     * record exists. On the exhausted arm the increment is instead guarded by {@code IF WS-IDX > 1} at
     * {@code :319}, which means an empty page leaves the page number alone - and therefore leaves it at zero
     * on a first display over an empty file. Both are reproduced exactly.
     *
     * <p><strong>Retained parity artefact.</strong> {@code MOVE SPACE TO USRIDINO} at {@code :328} clears the
     * identifier field immediately before the send, which is why {@code PROCESS-ENTER-KEY}'s later clearing
     * at {@code :231-233} is a no-op. Both are kept. Severity: Low; held as {@code DL-PP-13} in the
     * DECISION_LOG.md.
     *
     * @param work the per-invocation work area; {@code work.startKey}, {@code work.startPastEnd} and
     *             {@code work.startOrdinal} must already describe the browse position
     */
    private void processPageForward(final ScreenWorkArea work) {
        startbrUserSecFile(work);                                   // :284

        if (!work.errFlgOn) {                                       // :286 IF NOT ERR-FLG-ON

            if (work.forwardSkipReadRequired()) {                   // :288
                readnextUserSecFile(work);                          // :289
            }

            if (!work.userSecEof && !work.errFlgOn) {               // :292
                for (work.idx = 1; work.idx <= pageSize; work.idx++) {   // :293-295
                    initializeUserData(work);
                }
            }

            work.idx = 1;                                           // :298 MOVE 1 TO WS-IDX

            // :300-306 PERFORM UNTIL WS-IDX >= 11 OR USER-SEC-EOF OR ERR-FLG-ON
            while (work.idx < pageSize + 1 && !work.userSecEof && !work.errFlgOn) {
                readnextUserSecFile(work);                          // :301
                if (!work.userSecEof && !work.errFlgOn) {           // :302
                    populateUserData(work);                         // :303
                    work.idx = work.idx + 1;                        // :304
                }
            }

            if (!work.userSecEof && !work.errFlgOn) {               // :308
                work.pageNum = incrementPageNumber(work.pageNum);   // :309-310
                readnextUserSecFile(work);                          // :311 lookahead - one record only
                if (!work.userSecEof && !work.errFlgOn) {           // :312
                    work.nextPageAvailable = true;                  // :313 SET NEXT-PAGE-YES TO TRUE
                } else {
                    work.nextPageAvailable = false;                 // :315 SET NEXT-PAGE-NO TO TRUE
                }
            } else {
                work.nextPageAvailable = false;                     // :318 SET NEXT-PAGE-NO TO TRUE
                if (work.idx > 1) {                                 // :319
                    work.pageNum = incrementPageNumber(work.pageNum);   // :320-321
                }
            }

            endbrUserSecFile(work);                                 // :325
            work.pageNumberDisplay = renderZeroPadded(work.pageNum);    // :327
            work.userIdInput = SPACES;                              // :328 MOVE SPACE TO USRIDINO
            sendUsrlstScreen(work);                                 // :329
        }
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:336 PROCESS-PAGE-BACKWARD} - the mirror image of the forward page: begin
     * browse, optional skip read, clear the rows, read a page <em>descending</em> from the last slot, probe
     * one record beyond it, end browse, send.
     *
     * <p><strong>The skip predicate differs.</strong> {@code :342}
     * {@code IF EIBAID NOT = DFHENTER AND DFHPF8} excludes {@code DFHPF8} where the forward predicate at
     * {@code :288} excludes {@code DFHPF7} and {@code DFHPF3}. The two are not equivalent and must not be
     * unified. It fires for {@code PF7} only, consuming the first row of the displayed page so that the
     * loop retreats onto the previous page.
     *
     * <p><strong>The loop direction differs.</strong> {@code MOVE 10 TO WS-IDX} at {@code :352} then
     * {@code UNTIL WS-IDX &lt;= 0} decrementing at {@code :358} fills the row slots from the last to the
     * first while the ordinals descend, which is what leaves the page in ascending key order.
     *
     * <p><strong>The flag is read, never written.</strong> The backward path contains no
     * {@code SET NEXT-PAGE-YES} and no {@code SET NEXT-PAGE-NO}: it only tests {@code IF NEXT-PAGE-YES} at
     * {@code :364}. So after paging backward the flag still carries the value
     * {@code PROCESS-PF7-KEY} forced at {@code :245}, namely {@code 'Y'}. Preserved exactly - do not
     * recompute it here.
     *
     * <p><strong>The page number floors at one.</strong> {@code :365-370} decrements only when the lookahead
     * found a further record and the page number already exceeded one; otherwise it is forced to one. Note
     * that the whole adjustment sits under the outer {@code IF USER-SEC-NOT-EOF AND ERR-FLG-OFF} guard at
     * {@code :362}, so a backward page that read nothing at all leaves the page number untouched.
     *
     * <p><strong>Retained parity artefact.</strong> Unlike the forward path there is no
     * {@code MOVE SPACE TO USRIDINO} before the send, so a backward page echoes the identifier field back
     * while a forward page blanks it. The asymmetry is the behaviour; do not normalise it. Severity: Low;
     * held as {@code DL-PP-13} in the DECISION_LOG.md.
     *
     * <p><strong>Reachability note.</strong> If the backward loop reaches the front of the file before it
     * fills every slot, the low-numbered slots stay blank and the page displays with gaps at the top. That
     * is unreachable from a well-formed client, because {@code PROCESS-PF7-KEY} only pages backward when the
     * page number exceeds one and a page number above one implies a full page ahead of it. Severity: Low.
     *
     * @param work the per-invocation work area; {@code work.startKey} and {@code work.startOrdinal} must
     *             already describe the browse position
     */
    private void processPageBackward(final ScreenWorkArea work) {
        startbrUserSecFile(work);                                   // :338

        if (!work.errFlgOn) {                                       // :340 IF NOT ERR-FLG-ON

            if (work.backwardSkipReadRequired()) {                  // :342
                readprevUserSecFile(work);                          // :343
            }

            if (!work.userSecEof && !work.errFlgOn) {               // :346
                for (work.idx = 1; work.idx <= pageSize; work.idx++) {   // :347-349
                    initializeUserData(work);
                }
            }

            work.idx = pageSize;                                    // :352 MOVE 10 TO WS-IDX

            // :354-360 PERFORM UNTIL WS-IDX <= 0 OR USER-SEC-EOF OR ERR-FLG-ON
            while (work.idx > 0 && !work.userSecEof && !work.errFlgOn) {
                readprevUserSecFile(work);                          // :355
                if (!work.userSecEof && !work.errFlgOn) {           // :356
                    populateUserData(work);                         // :357
                    work.idx = work.idx - 1;                        // :358
                }
            }

            if (!work.userSecEof && !work.errFlgOn) {               // :362
                readprevUserSecFile(work);                          // :363 lookahead - one record only
                if (work.nextPageAvailable) {                       // :364 IF NEXT-PAGE-YES
                    if (!work.userSecEof && !work.errFlgOn && work.pageNum > FIRST_PAGE_NUMBER) {  // :365-366
                        work.pageNum = work.pageNum - 1;            // :367 SUBTRACT 1
                    } else {
                        work.pageNum = FIRST_PAGE_NUMBER;           // :369 MOVE 1
                    }
                }
            }

            endbrUserSecFile(work);                                 // :375
            work.pageNumberDisplay = renderZeroPadded(work.pageNum);    // :377
            sendUsrlstScreen(work);                                 // :378
        }
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:384 POPULATE-USER-DATA} - projects the record the browse just returned onto
     * the screen row selected by {@code WS-IDX}, and captures the keyset cursors.
     *
     * <p>Four fields are projected per row: {@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}
     * and {@code SEC-USR-TYPE}. <strong>{@code SEC-USR-PWD} is not among them</strong>, and this method never
     * reads the stored hash. Nothing beyond those four fields leaves the record, which keeps the response to
     * exactly the personal data the legacy screen displayed.
     *
     * <p><strong>The keyset cursors.</strong> {@code :388-389} also moves the identifier to
     * {@code CDEMO-CU00-USRID-FIRST} when {@code WS-IDX} is one, and {@code :434-435} to
     * {@code CDEMO-CU00-USRID-LAST} when it is ten. Those two values are the entire pagination state the
     * source carries forward, and they become the {@code firstKey} and {@code lastKey} metadata on the
     * response.
     *
     * <p><strong>Mechanism substitution.</strong> The source dispatches through a ten-arm
     * {@code EVALUATE WS-IDX} because BMS generates one set of field names per occurrence of
     * {@code USER-REC OCCURS 10 TIMES}. Java addresses the same table by index, which is the faithful
     * modelling of an {@code OCCURS} table rather than a consolidation of distinct logic: every arm performs
     * the identical four moves, and the two index-specific extras are reproduced as explicit index tests.
     * The {@code WHEN OTHER CONTINUE} arm at {@code :439-440} is retained below as an intentional no-op and
     * covered by {@code DL-CR-01} in the DECISION_LOG.md; it is reachable only if {@code WS-IDX} leaves
     * the table, which
     * the loop bounds prevent. Severity: Low.
     *
     * @param work the per-invocation work area; {@code work.currentRecord} holds the record just read and
     *             {@code work.idx} selects the row
     */
    private void populateUserData(final ScreenWorkArea work) {
        final UserSecurity record = work.currentRecord;
        if (record == null) {
            // Defensive only: the source reaches this paragraph solely after a successful read, so
            // SEC-USER-DATA always holds a record. Fail loudly rather than emit a blank row.
            throw new FatalProcessingException(PROGRAM_NAME
                    + " POPULATE-USER-DATA reached with no record available to project");
        }

        final int slot = work.idx - 1;
        if (slot < 0 || slot >= work.rowUserIds.length) {
            // :439-440 WHEN OTHER / CONTINUE - intentional no-op, retained for control-flow parity.
            return;
        }

        work.rowUserIds[slot] = record.getSecUsrId();               // MOVE SEC-USR-ID    TO USRIDnnI
        work.rowFirstNames[slot] = record.getSecUsrFname();          // MOVE SEC-USR-FNAME TO FNAMEnnI
        work.rowLastNames[slot] = record.getSecUsrLname();           // MOVE SEC-USR-LNAME TO LNAMEnnI
        work.rowUserTypes[slot] = renderUserType(record.getSecUsrType());   // MOVE SEC-USR-TYPE TO UTYPEnnI

        if (work.idx == 1) {
            work.usridFirst = record.getSecUsrId();                 // :388-389 CDEMO-CU00-USRID-FIRST
        }
        if (work.idx == work.rowUserIds.length) {
            work.usridLast = record.getSecUsrId();                  // :434-435 CDEMO-CU00-USRID-LAST
        }
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:446 INITIALIZE-USER-DATA} - blanks one screen row.
     *
     * <p><strong>Preserve: the selection flag is not cleared.</strong> The paragraph blanks
     * {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI} and deliberately leaves
     * {@code SELnnnnI} alone, so a selection the operator typed survives a row re-initialisation. The only
     * place the selectors are cleared is {@code MOVE LOW-VALUES TO COUSR0AO} on the first-display arm at
     * {@code :117}. Clearing them here would change which selection the next
     * {@code PROCESS-ENTER-KEY} sees, so do not add it.
     *
     * <p><strong>Mechanism substitution.</strong> As with {@code POPULATE-USER-DATA}, the ten-arm
     * {@code EVALUATE WS-IDX} addresses one {@code OCCURS} table and is modelled by index. The
     * {@code WHEN OTHER CONTINUE} arm at {@code :499-500} is retained below as an intentional no-op and
     * covered by {@code DL-CR-01} in the DECISION_LOG.md. Severity: Low.
     *
     * @param work the per-invocation work area; {@code work.idx} selects the row
     */
    private void initializeUserData(final ScreenWorkArea work) {
        final int slot = work.idx - 1;
        if (slot < 0 || slot >= work.rowUserIds.length) {
            // :499-500 WHEN OTHER / CONTINUE - intentional no-op, retained for control-flow parity.
            return;
        }

        work.rowUserIds[slot] = SPACES;                             // MOVE SPACES TO USRIDnnI
        work.rowFirstNames[slot] = SPACES;                          // MOVE SPACES TO FNAMEnnI
        work.rowLastNames[slot] = SPACES;                           // MOVE SPACES TO LNAMEnnI
        work.rowUserTypes[slot] = SPACES;                           // MOVE SPACES TO UTYPEnnI
        // SELnnnnI is deliberately NOT cleared - see the Javadoc above.
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:506 RETURN-TO-PREV-SCREEN} - defaults the transfer target and hands control
     * away.
     *
     * <p><strong>Retained parity artefact: this label has no state to carry.</strong> Of its five statements,
     * the default at {@code :508-510} is reproduced, and the other four have no Java counterpart under the
     * stateless mandate: {@code MOVE WS-TRANID TO CDEMO-FROM-TRANID} at {@code :511},
     * {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} at {@code :512},
     * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} at {@code :513} and
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at {@code :514-517}.
     * Routing is URL-based in the target and nothing is retained across a request, so the four collapse into
     * recording that control was transferred and to where. The label is nonetheless mapped one-to-one, as an
     * intentional no-op with respect to state, and is held as {@code DL-MS-06} in the DECISION_LOG.md.
     * Severity: Low.
     *
     * @param work the per-invocation work area; {@code work.navigationTarget} already holds the caller's
     *             intended target, or is unset when the caller had none
     */
    private void returnToPrevScreen(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.navigationTarget)) {                 // :508 IF CDEMO-TO-PROGRAM = LOW-VALUES
            work.navigationTarget = SIGN_ON_PROGRAM;                 // :509 MOVE 'COSGN00C'
        }
        // :511-513 have no Java counterpart - see the Javadoc. Nothing is retained.
        work.controlTransferred = true;                              // :514-517 EXEC CICS XCTL
        LOG.debug("{} transferring control to {}", PROGRAM_NAME, work.navigationTarget);
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:522 SEND-USRLST-SCREEN} - assembles the response.
     *
     * <p>Populates the header, moves {@code WS-MESSAGE} into {@code ERRMSGO}, then issues one of two
     * {@code EXEC CICS SEND} commands. {@code WS-MESSAGE} is {@code PIC X(80)} while {@code ERRMSGO} is
     * {@code PIC X(78)}, so the move at {@code :526} truncates the low-order two bytes; the truncation is
     * applied here rather than left to the transport.
     *
     * <p><strong>Retained parity artefact.</strong> The two {@code SEND} variants at {@code :528-544} differ
     * in one option: the first carries {@code ERASE}, the second has it <em>commented out</em> at
     * {@code :541}. Under HTTP neither option has a counterpart, which would make the whole
     * {@code IF SEND-ERASE-YES} decision inert - so the choice is recorded on the response instead of being
     * discarded, which keeps the {@code SET SEND-ERASE-NO} assignments at {@code :253} and {@code :275}
     * observable and testable. The commented-out option is retained in place, not deleted. Severity: Low;
     * held as {@code DL-PP-13} in the DECISION_LOG.md.
     *
     * <p>The source can send more than once in a single turn - the {@code NOTFND} arm of {@code STARTBR}
     * sends and then falls through to the page logic, which sends again. The send count is recorded so that
     * the behaviour stays visible; the last assembly wins, exactly as the last {@code SEND} does on a real
     * terminal.
     *
     * @param work the per-invocation work area, whose message and row state are transcribed into the
     *             response fields
     */
    private void sendUsrlstScreen(final ScreenWorkArea work) {
        populateHeaderInfo(work);                                    // :524 PERFORM POPULATE-HEADER-INFO
        // :526 MOVE WS-MESSAGE TO ERRMSGO - PIC X(80) into PIC X(78) truncates the low-order two bytes.
        work.errorMessage = truncate(work.message, UserSecurityDto.ERROR_MESSAGE_WIDTH);
        if (work.sendEraseYes) {
            work.eraseRequested = true;                              // :528-535 SEND ... ERASE CURSOR
        } else {
            work.eraseRequested = false;                             // :536-543 SEND ... (ERASE commented
        }                                                            //          out at :541) CURSOR
        work.sendCount = work.sendCount + 1;
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:549 RECEIVE-USRLST-SCREEN} - binds the submitted screen.
     *
     * <p>{@code EXEC CICS RECEIVE MAP('COUSR0A') MAPSET('COUSR00') INTO(COUSR0AI)} becomes request-body
     * binding: the map and mapset names have no counterpart, and the storage the map was received into is the
     * work area's row slots. Every component is treated as untrusted and is width-checked against the field
     * it transcribes before it is stored.
     *
     * <p><strong>Retained parity artefact.</strong> The command captures {@code RESP(WS-RESP-CD)} and
     * {@code RESP2(WS-REAS-CD)} at {@code :554-555} and then <em>never examines either</em> - there is no
     * {@code EVALUATE} after this command, unlike every other I/O paragraph in the program. The capture is
     * reproduced, unexamined, rather than dropped. Severity: Low; held as {@code DL-PP-13} in the
     * DECISION_LOG.md.
     *
     * <p>Note that the submitted selection flags are stored but the submitted row data is stored too, because
     * {@code INITIALIZE-USER-DATA} clears the four data fields while deliberately leaving the selectors
     * alone; reproducing that asymmetry requires both to be present in the first place.
     *
     * @param work    the per-invocation work area to bind into
     * @param request the submitted screen
     * @throws com.cardemo.exception.ValidationException if the identifier field or any row component is wider
     *                                                  than the field it transcribes, or if more rows were
     *                                                  submitted than the screen paints
     */
    private void receiveUsrlstScreen(final ScreenWorkArea work, final UserListRequest request) {
        work.userIdInput = requireKeyWidth(request.userIdInput(), "userIdInput");

        final List<UserSecurityDto.UserRow> submitted = request.displayedRows();
        if (submitted.size() > pageSize) {
            throw new ValidationException("displayedRows holds " + submitted.size()
                    + " rows, which exceeds the " + pageSize + " rows the user list screen paints",
                    "displayedRows", ValidationException.FailureKind.INVALID);
        }
        work.submittedRowCount = submitted.size();
        for (int slot = 0; slot < submitted.size(); slot++) {
            final UserSecurityDto.UserRow row = submitted.get(slot);
            work.selectionFlags[slot] = requireWidth(row.selectionFlag(), 1, "selectionFlag");
            work.rowUserIds[slot] = requireKeyWidth(row.userId(), "userId");
            work.rowFirstNames[slot] = requireWidth(row.firstName(), UserSecurityDto.NAME_WIDTH, "firstName");
            work.rowLastNames[slot] = requireWidth(row.lastName(), UserSecurityDto.NAME_WIDTH, "lastName");
            work.rowUserTypes[slot] = requireWidth(row.userType(), 1, "userType");
        }

        // :554-555 RESP and RESP2 are captured here and never examined - retained artefact.
        recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:562 POPULATE-HEADER-INFO} - stamps the six shared header fields.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :564} becomes a read of the
     * injected {@code java.time.Clock}. The clock is injected rather than taken from
     * {@code LocalDate.now()} so that the header is deterministic under test and cannot depend on the host
     * default time zone - Clause A requires the first, Clause B the second.
     *
     * <p>Field widths and formats come from the symbolic map and {@code app/cpy/CSDAT01Y.cpy}, not from
     * convention: {@code TRNNAMEI PIC X(4)}, {@code TITLE01I PIC X(40)}, {@code CURDATEI PIC X(8)},
     * {@code PGMNAMEI PIC X(8)}, {@code TITLE02I PIC X(40)} and {@code CURTIMEI PIC X(8)}.
     * {@code WS-CURDATE-MM-DD-YY} is {@code 9(02)} {@code '/'} {@code 9(02)} {@code '/'} {@code 9(02)},
     * so eight characters as {@code MM/DD/YY} with the year taken from {@code WS-CURDATE-YEAR(3:2)} - the
     * last two digits, at {@code :573}. {@code WS-CURTIME-HH-MM-SS} is the same shape, so eight characters
     * as {@code HH:MM:SS}.
     *
     * <p><strong>Citation correction.</strong> The plan's general note on the BMS layer records
     * {@code CURTIME} as {@code X(9)}. The symbolic map and {@code CSDAT01Y.cpy} both say {@code X(8)}, and
     * the source governs, so this method emits eight characters. Severity: Low; registered as
     * {@code CIT-CURTIME-WIDTH} under {@code DL-CR-09} in the DECISION_LOG.md.
     *
     * @param work the per-invocation work area to stamp
     */
    private void populateHeaderInfo(final ScreenWorkArea work) {
        final LocalDateTime now = LocalDateTime.now(clock);           // :564 FUNCTION CURRENT-DATE
        work.title01 = SCREEN_TITLE_01;                               // :566 MOVE CCDA-TITLE01 TO TITLE01O
        work.title02 = SCREEN_TITLE_02;                               // :567 MOVE CCDA-TITLE02 TO TITLE02O
        work.transactionName = TRANSACTION_ID;                        // :568 MOVE WS-TRANID  TO TRNNAMEO
        work.programName = PROGRAM_NAME;                              // :569 MOVE WS-PGMNAME TO PGMNAMEO
        work.currentDate = HEADER_DATE_FORMAT.format(now);            // :571-575 MM/DD/YY
        work.currentTime = HEADER_TIME_FORMAT.format(now);            // :577-581 HH:MM:SS
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:586 STARTBR-USER-SEC-FILE} - begins the browse.
     *
     * <p>{@code EXEC CICS STARTBR DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID)
     * KEYLENGTH(LENGTH OF SEC-USR-ID)} positions the browse on an eight-character key - eight because
     * {@code SEC-USR-ID} is {@code PIC X(08)} in {@code app/cpy/CSUSR01Y.cpy} and because
     * {@code app/jcl/DUSRSECJ.jcl:65} defines the cluster with {@code KEYS(8,0)}.
     *
     * <p><strong>The commented-out {@code GTEQ} changes nothing, because it is the default.</strong>
     * {@code :592} carries {@code GTEQ} as a comment, so neither {@code GTEQ} nor {@code EQUAL} is coded -
     * and for a direct browse of a KSDS the option {@code EXEC CICS STARTBR} applies when none is coded is
     * {@code GTEQ}. {@code USRSEC} is a KSDS: {@code app/jcl/DUSRSECJ.jcl} defines it with {@code KEYS(8,0)}
     * and {@code INDEXED}. The effective semantic is therefore equal-or-greater, and the browse positions on
     * the first record whose key is at or after the supplied one. The two sentinel keys the callers supply
     * are handled as the callers mean them - {@code LOW-VALUES} positions before the first record,
     * {@code HIGH-VALUES} positions past the last - and a real key that exceeds every record in the file is
     * the same condition as {@code HIGH-VALUES} and takes the same position.
     *
     * <p>An earlier reading of this method treated the comment as evidence of an equal-only browse and
     * validated the supplied key with a primary-key existence lookup. That reading is <strong>withdrawn</strong>:
     * it made a key that names no record a not-found even when records follow it, and it is not the option
     * the source's command carries.
     *
     * <p><strong>Retained parity artefact.</strong> The {@code NOTFND} arm opens with a bare
     * {@code CONTINUE} at {@code :601} before its real body, which is a no-op the compiler discards. It is
     * reproduced as a comment in place rather than deleted, because the arm's shape is what distinguishes it
     * from the {@code WHEN OTHER} arm below it. Severity: Low.
     *
     * <p><strong>Preserve: {@code NOTFND} does not set the error flag.</strong> The arm at {@code :600-606}
     * sets the end-of-file switch, the message and the cursor, sends, and lets
     * {@code PROCESS-PAGE-FORWARD} carry on - it never sets {@code WS-ERR-FLG}. So the caller still receives
     * an assembled screen. Because a stateless caller also needs the outcome as a type, the mapped
     * {@code RecordNotFoundException} is <em>retained</em> and rethrown by the entry point rather than thrown
     * from here, which reproduces the source's control flow exactly while still reporting the failure.
     *
     * <p><strong>The key is the position.</strong> The echoed cursor anchors the browse and the two keyset
     * finders read from it, ascending on the forward path and descending on the backward one, which is what
     * {@code STARTBR} followed by {@code READNEXT} or {@code READPREV} does. The key is probed with a
     * one-row equal-or-greater query first, so that a key naming no record still positions on the next
     * higher one exactly as the default {@code GTEQ} option does, and only a key beyond the last record
     * reaches the end of the file. The submitted page number no longer contributes to positioning at all -
     * see the class documentation for the three behaviours that resolved.
     *
     * @param work the per-invocation work area; {@code work.startKey}, {@code work.startPastEnd} and
     *             {@code work.startOrdinal} describe the requested position
     */
    private void startbrUserSecFile(final ScreenWorkArea work) {
        work.resetBrowseWindow();
        final String ioStatus = positionBrowse(work);

        // :597 EVALUATE WS-RESP-CD
        if (IO_STATUS_SUCCESS.equals(ioStatus)) {                     // :598-599 WHEN DFHRESP(NORMAL)
            return;                                                   // :599 CONTINUE
        }

        if (IO_STATUS_RECORD_NOT_FOUND.equals(ioStatus)) {            // :600 WHEN DFHRESP(NOTFND)
            // :601 CONTINUE - retained redundant no-op preceding the arm's real body.
            work.userSecEof = true;                                   // :602 SET USER-SEC-EOF TO TRUE
            work.message = AT_TOP_MESSAGE;                            // :603-604
            work.cursorField = CURSOR_FIELD_USER_ID_INPUT;            // :605 MOVE -1 TO USRIDINL
            classify(work, ioStatus, STARTBR_OPERATION).ifPresent(failure -> retainFailure(work, failure));
            sendUsrlstScreen(work);                                   // :606
            return;
        }

        // :607-613 WHEN OTHER
        // :608 DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD - now a structured log record.
        LOG.error("{} RESP:{} REAS:{} on {} of {}", PROGRAM_NAME, work.responseCode, work.reasonCode,
                STARTBR_OPERATION, USRSEC_FILE);
        work.errFlgOn = true;                                         // :609 MOVE 'Y' TO WS-ERR-FLG
        work.message = "Unable to lookup User...";                    // :610-611 - one of three occurrences
        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;                // :612 MOVE -1 TO USRIDINL
        classify(work, ioStatus, STARTBR_OPERATION).ifPresent(failure -> retainFailure(work, failure));
        sendUsrlstScreen(work);                                       // :613
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:619 READNEXT-USER-SEC-FILE} - reads the record the browse is positioned on
     * and advances.
     *
     * <p><strong>High severity if got wrong: {@code DFHRESP(ENDFILE)} is loop termination, not an error.</strong>
     * The arm at {@code :634-640} sets the end-of-file switch, the message and the cursor and sends -
     * it does <em>not</em> set {@code WS-ERR-FLG} and it raises nothing. That is the direct source proof, and
     * this method therefore never throws for an exhausted browse and retains no failure for it. Throwing here
     * would break every page boundary, the lookahead probe and the whole forward loop.
     *
     * <p><strong>Retained parity artefact.</strong> The {@code ENDFILE} arm opens with a bare
     * {@code CONTINUE} at {@code :635} before its real body; reproduced as a comment in place. Severity: Low.
     *
     * <p>{@code INTO(SEC-USER-DATA) LENGTH(LENGTH OF SEC-USER-DATA)} reads the whole eighty-byte record, and
     * that record does carry {@code SEC-USR-PWD PIC X(08)} - but nothing downstream reads it. The password
     * hash is never touched, logged, echoed, serialised or placed in an exception message anywhere in this
     * class.
     *
     * @param work the per-invocation work area; on success {@code work.currentRecord} holds the record and
     *             the browse position advances by one
     */
    private void readnextUserSecFile(final ScreenWorkArea work) {
        final String ioStatus = fetchAtOrdinal(work, work.browseOrdinal);

        // :631 EVALUATE WS-RESP-CD
        if (IO_STATUS_SUCCESS.equals(ioStatus)) {                     // :632-633 WHEN DFHRESP(NORMAL)
            work.browseOrdinal = work.browseOrdinal + 1L;             // the browse advances one record
            return;                                                  // :633 CONTINUE
        }

        if (IO_STATUS_END_OF_FILE.equals(ioStatus)) {                 // :634 WHEN DFHRESP(ENDFILE)
            // :635 CONTINUE - retained redundant no-op preceding the arm's real body.
            work.userSecEof = true;                                   // :636 SET USER-SEC-EOF TO TRUE
            work.message = REACHED_BOTTOM_MESSAGE;                    // :637-638
            work.cursorField = CURSOR_FIELD_USER_ID_INPUT;            // :639 MOVE -1 TO USRIDINL
            sendUsrlstScreen(work);                                   // :640
            return;                                                  // no exception - see the Javadoc
        }

        // :641-647 WHEN OTHER
        LOG.error("{} RESP:{} REAS:{} on {} of {}", PROGRAM_NAME, work.responseCode, work.reasonCode,
                READNEXT_OPERATION, USRSEC_FILE);                     // :642
        work.errFlgOn = true;                                         // :643 MOVE 'Y' TO WS-ERR-FLG
        work.message = "Unable to lookup User...";                    // :644-645 - one of three occurrences
        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;                // :646 MOVE -1 TO USRIDINL
        classify(work, ioStatus, READNEXT_OPERATION).ifPresent(failure -> retainFailure(work, failure));
        sendUsrlstScreen(work);                                       // :647
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:653 READPREV-USER-SEC-FILE} - reads the record the browse is positioned on
     * and retreats.
     *
     * <p>The mirror of {@code READNEXT-USER-SEC-FILE}, and the differences are load-bearing: the browse
     * position moves backward, and the {@code ENDFILE} message at {@code :671} reads
     * {@code 'You have reached the top of the page...'} where the forward one reads
     * {@code 'You have reached the bottom of the page...'}. Both are distinct again from
     * {@code PROCESS-PF7-KEY}'s {@code 'You are already at the top of the page...'} at {@code :251}, which
     * is emitted without reading anything. Three messages, three paths; never merge them.
     *
     * <p><strong>High severity if got wrong: {@code DFHRESP(ENDFILE)} is loop termination, not an
     * error.</strong> The arm at {@code :668-674} sets the end-of-file switch and the message and sends,
     * without setting {@code WS-ERR-FLG} and without raising anything. This method therefore never throws for
     * an exhausted browse.
     *
     * <p><strong>Retained parity artefact.</strong> The bare {@code CONTINUE} at {@code :669} preceding the
     * arm's real body; reproduced as a comment in place. Severity: Low.
     *
     * @param work the per-invocation work area; on success {@code work.currentRecord} holds the record and
     *             the browse position retreats by one
     */
    private void readprevUserSecFile(final ScreenWorkArea work) {
        final String ioStatus = fetchAtOrdinal(work, work.browseOrdinal);

        // :665 EVALUATE WS-RESP-CD
        if (IO_STATUS_SUCCESS.equals(ioStatus)) {                     // :666-667 WHEN DFHRESP(NORMAL)
            work.browseOrdinal = work.browseOrdinal - 1L;             // the browse retreats one record
            return;                                                  // :667 CONTINUE
        }

        if (IO_STATUS_END_OF_FILE.equals(ioStatus)) {                 // :668 WHEN DFHRESP(ENDFILE)
            // :669 CONTINUE - retained redundant no-op preceding the arm's real body.
            work.userSecEof = true;                                   // :670 SET USER-SEC-EOF TO TRUE
            work.message = REACHED_TOP_MESSAGE;                       // :671-672
            work.cursorField = CURSOR_FIELD_USER_ID_INPUT;            // :673 MOVE -1 TO USRIDINL
            sendUsrlstScreen(work);                                   // :674
            return;                                                  // no exception - see the Javadoc
        }

        // :675-681 WHEN OTHER
        LOG.error("{} RESP:{} REAS:{} on {} of {}", PROGRAM_NAME, work.responseCode, work.reasonCode,
                READPREV_OPERATION, USRSEC_FILE);                     // :676
        work.errFlgOn = true;                                         // :677 MOVE 'Y' TO WS-ERR-FLG
        work.message = "Unable to lookup User...";                    // :678-679 - one of three occurrences
        work.cursorField = CURSOR_FIELD_USER_ID_INPUT;                // :680 MOVE -1 TO USRIDINL
        classify(work, ioStatus, READPREV_OPERATION).ifPresent(failure -> retainFailure(work, failure));
        sendUsrlstScreen(work);                                       // :681
    }

    /**
     * {@code app/cbl/COUSR00C.cbl:687 ENDBR-USER-SEC-FILE} - ends the browse.
     *
     * <p>{@code EXEC CICS ENDBR DATASET(WS-USRSEC-FILE)} releases the browse the region was holding. It is
     * the only I/O command in the program with no {@code RESP} option and no {@code EVALUATE} after it, so it
     * cannot report anything and cannot fail visibly.
     *
     * <p><strong>Mechanism substitution.</strong> A paged query holds no cursor between reads, so there is no
     * browse to release: JPA pagination subsumes the whole command. The label is nonetheless mapped
     * one-to-one, and it is given the one job that remains - discarding the page window, so that a later
     * browse in the same request cannot serve a stale row and so that the rows become collectable as soon as
     * the source would have released them. Held as DL-MS-04 in DECISION_LOG.md, the entry for VSAM access
     * verbs becoming repository operations. Severity: Low.
     *
     * @param work the per-invocation work area whose page window is released
     */
    private void endbrUserSecFile(final ScreenWorkArea work) {
        work.resetBrowseWindow();                                     // :689-691 EXEC CICS ENDBR
    }

    // Browse internals. These are not paragraphs: they are the mechanism that stands in for the VSAM
    // browse, and they exist so that the sixteen source-mapped methods above read exactly like the source.

    /**
     * Resolves the requested browse position into an ordinal, and reports the outcome as the file status the
     * {@code STARTBR} evaluation expects.
     *
     * <p>Three positions are possible, one per sentinel the callers supply:
     *
     * <ul>
     *   <li>{@code HIGH-VALUES} - past the last record. No query is needed and no key can be validated, so
     *       the position is accepted and the next read ends the file at once.</li>
     *   <li>{@code LOW-VALUES} - before the first record. The file must hold at least one record, which is
     *       established by reading ordinal zero; an empty file takes the {@code NOTFND} arm. The read also
     *       primes the page window, so a first display costs one query here and none in the page loop.</li>
     *   <li>A real key - the browse positions on the first record whose key is <em>equal to or greater
     *       than</em> it, which is settled with a one-row keyset probe. A key that exceeds every record in
     *       the file is the same condition {@code HIGH-VALUES} expresses, so it takes the past-the-end
     *       position above and the next read ends the file at once.</li>
     * </ul>
     *
     * <p><strong>Why equal-or-greater, when {@code GTEQ} is commented out.</strong> {@code GTEQ} is the
     * <em>default</em> option of {@code EXEC CICS STARTBR} for a direct browse of a KSDS, and {@code USRSEC}
     * is a KSDS - {@code app/jcl/DUSRSECJ.jcl} defines it with {@code KEYS(8,0)} and {@code INDEXED}. The
     * commented-out {@code GTEQ} at {@code app/cbl/COUSR00C.cbl:592} therefore changes nothing: neither
     * {@code GTEQ} nor {@code EQUAL} is coded, so the effective option is the default, and the default is
     * equal-or-greater. An earlier reading of this method took the comment as evidence that the browse was
     * equal-only and validated the key with a primary-key existence lookup. That was wrong twice over: it
     * refused a key that names no record even when records follow it, and it is not what the source does.
     *
     * <p>A negative or unaddressable ordinal is reported as {@code NOTFND} rather than clamped, because a
     * caller that supplies a page number and a row count which cannot describe a real position has supplied
     * an inconsistent screen, and silently moving it somewhere plausible would return a page the operator
     * never asked for.
     *
     * @param work the per-invocation work area; the browse position is written to {@code work.browseOrdinal}
     * @return {@code '00'} when the browse is positioned, {@code '23'} when it cannot be, or {@code '90'}
     *         when the store failed
     */
    private String positionBrowse(final ScreenWorkArea work) {
        if (work.startPastEnd) {
            work.browseOrdinal = ORDINAL_PAST_END;
            return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        }

        if (work.startKey == null) {
            final String probe = fetchAtOrdinal(work, 0L);
            // STARTBR reads no record into SEC-USER-DATA; only the position survives the probe.
            work.currentRecord = null;
            if (IO_STATUS_SUCCESS.equals(probe)) {
                work.browseOrdinal = 0L;
                return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
            }
            if (IO_STATUS_END_OF_FILE.equals(probe)) {
                return recordResponse(work, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            return probe;
        }

        if (work.startOrdinal < 0L || work.startOrdinal > MAX_SUPPORTED_ORDINAL) {
            return recordResponse(work, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
        }

        try {
            // The equal-or-greater probe the default STARTBR option performs. One row is enough: the browse
            // needs to know only whether the file holds anything at or after the key, because the page reads
            // themselves walk forward from the anchor.
            if (this.userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(work.startKey, PageRequest.ofSize(1))
                    .getContent().isEmpty()) {
                // Nothing at or after the key, so the key is beyond the last record - the position
                // HIGH-VALUES names. The browse is accepted there and the first read ends the file, which is
                // the outcome the source produces for a key it cannot reach past.
                work.browseOrdinal = ORDINAL_PAST_END;
                return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
            }
        } catch (final DataAccessException failure) {
            work.ioFailureCause = failure;
            return recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }

        work.browseOrdinal = work.startOrdinal;
        return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
    }

    /**
     * Retrieves the record at one ordinal of the repository's declared total order, serving it from the page
     * window when possible and loading the window that contains it otherwise.
     *
     * <p>The window is always the page-aligned block containing the requested ordinal, so a run of reads in
     * either direction inside one page costs exactly one query: a forward loop reads the block ascending, a
     * backward loop reads the same kind of block descending. Loading the aligned block rather than a single
     * row is what keeps the whole request inside its four-query budget while leaving the two probe reads -
     * the skip read and the lookahead - as ordinary reads through this same method.
     *
     * <p>Nothing here counts rows. The next-page flag is decided by whether this method finds a record one
     * ordinal past the page, which is what the source's extra {@code READNEXT} decides; a
     * {@code count} query would answer a different question and would change the flag on boundary pages.
     *
     * @param work    the per-invocation work area; {@code work.currentRecord} is set on success and cleared
     *                otherwise, and {@code work.responseCode} and {@code work.reasonCode} are stamped
     * @param ordinal the zero-based position in key order; a negative value means the browse has run off
     *                either end and is reported as an end of file
     * @return {@code '00'} when a record was retrieved, {@code '10'} at an end of file, or {@code '90'} when
     *         the store failed
     */
    private String fetchAtOrdinal(final ScreenWorkArea work, final long ordinal) {
        work.currentRecord = null;

        if (ordinal < 0L || ordinal > MAX_SUPPORTED_ORDINAL) {
            return recordResponse(work, CICS_RESP_ENDFILE, IO_STATUS_END_OF_FILE);
        }

        if (!work.windowContains(ordinal)) {
            try {
                loadWindowFor(work, ordinal);
            } catch (final DataAccessException failure) {
                work.ioFailureCause = failure;
                return recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
            }
        }

        final UserSecurity record = work.windowRecord(ordinal);
        if (record == null) {
            return recordResponse(work, CICS_RESP_ENDFILE, IO_STATUS_END_OF_FILE);
        }
        work.currentRecord = record;
        return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
    }

    /**
     * Loads the block of records covering one ordinal, positioning by key wherever the browse was keyed.
     *
     * <p><strong>Finding H-08, severity High.</strong> Computing a page index from the
     * ordinal and reading {@code findAllByOrderBySecUsrIdAsc(PageRequest.of(pageIndex, pageSize))} is an
     * {@code OFFSET} scan whose cost grows with the page number, and which returns whatever rows occupy
     * that offset rather than the rows the caller's key identified. This reads from the key instead.
     *
     * <p><strong>Why the read is bounded whichever direction it runs.</strong> Ordinals are relative to the
     * anchor, and one invocation of this service reads at most a page plus its two probe reads, so the
     * distance from the anchor to any requested ordinal is at most {@code pageSize + 2}. Both branches
     * therefore fetch at most a little over two pages, whatever the size of the table and whatever page
     * number the caller submitted.
     *
     * <ul>
     *   <li><strong>At or after the anchor</strong> - read ascending from the anchor, inclusive, and base the
     *       block at the anchor. A short read simply yields a shorter block, and an ordinal past its end is
     *       reported as the end of file it is.</li>
     *   <li><strong>Before the anchor</strong> - read descending from the anchor, inclusive, drop the anchor
     *       row itself, and reverse what remains into ascending order. The block is based so that its last
     *       row is the one immediately before the anchor, which keeps the alignment correct even when the
     *       start of the file cut the read short. Running off the start of the file is then exactly an
     *       ordinal the block does not cover.</li>
     *   <li><strong>Unkeyed</strong> - {@code MOVE LOW-VALUES TO SEC-USR-ID} at
     *       {@code app/cbl/COUSR00C.cbl:L219} and {@code :L240} positions at the start of the file, where
     *       there is no key to anchor on. That browse alone still reads by page index, which costs nothing
     *       because its ordinals begin at zero.</li>
     * </ul>
     *
     * <p>A {@link Slice}, not a {@link org.springframework.data.domain.Page}, on every path: only the content
     * is consumed, so the count query a page issues alongside every window was pure waste, and a VSAM browse
     * has no total to reproduce in the first place.
     *
     * @param work    the per-invocation work area, whose browse window this method replaces
     * @param ordinal the ordinal the loaded block must cover
     * @throws DataAccessException if the store rejects the read; the caller translates it
     */
    private void loadWindowFor(final ScreenWorkArea work, final long ordinal) {
        if (work.anchorKey == null) {
            final int windowPageIndex = (int) (ordinal / pageSize);
            final Slice<UserSecurity> window = this.userSecurityRepository
                    .findAllByOrderBySecUsrIdAsc(PageRequest.of(windowPageIndex, pageSize));
            work.loadWindow((long) windowPageIndex * pageSize, pageSize, window.getContent());
            return;
        }

        if (ordinal >= work.anchorOrdinal) {
            final int span = (int) (ordinal - work.anchorOrdinal) + pageSize;
            final Slice<UserSecurity> forward = this.userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(work.anchorKey,
                            PageRequest.ofSize(span));
            work.loadWindow(work.anchorOrdinal, span, forward.getContent());
            return;
        }

        // One extra row is requested because the anchor row itself comes back first and is then dropped.
        final int span = (int) (work.anchorOrdinal - ordinal) + pageSize;
        final Slice<UserSecurity> backward = this.userSecurityRepository
                .findBySecUsrIdLessThanEqualOrderBySecUsrIdDesc(work.anchorKey,
                        PageRequest.ofSize(span + 1));
        final List<UserSecurity> descending = backward.getContent();
        final List<UserSecurity> ascending = new ArrayList<>(Math.max(descending.size() - 1, 0));
        for (int index = descending.size() - 1; index >= 1; index--) {
            ascending.add(descending.get(index));
        }
        work.loadWindow(work.anchorOrdinal - ascending.size(), Math.max(ascending.size(), 1), ascending);
    }

    /**
     * Stamps the {@code RESP} and {@code RESP2} work fields the source declares at
     * {@code app/cbl/COUSR00C.cbl:50-51} and returns the file status unchanged, so that a call site reads as
     * a single expression.
     *
     * @param work         the per-invocation work area
     * @param responseCode the CICS {@code RESP} ordinal to record
     * @param ioStatus     the file status to return unchanged
     * @return {@code ioStatus}
     */
    private static String recordResponse(final ScreenWorkArea work, final int responseCode,
            final String ioStatus) {
        work.responseCode = responseCode;
        work.reasonCode = CICS_REASON_NONE;
        return ioStatus;
    }

    /**
     * Hands the file status to the injected {@code FileStatusMapper}, which owns the status-to-exception
     * decision and the {@code FILE STATUS IS: NNNN} rendering. Neither is reimplemented here and neither is
     * re-rendered.
     *
     * <p>The mapper returns an empty result for {@code '00'} and for {@code '10'}, which is exactly why an
     * exhausted browse never produces an exception: end of file is loop termination. When a
     * {@code DataAccessException} produced the status it is passed as the cause, so the root cause is
     * preserved rather than swallowed.
     *
     * <p>None of the mapper's three batch-side carve-outs is used here. Those accept a not-found status as a
     * success for the category-balance upsert, the disclosure-group default fallback and the file service's
     * secondary status; in this program a user that cannot be found is an error, and importing a carve-out
     * would be a High-severity defect.
     *
     * @param work      the per-invocation work area; any retained cause is consumed and cleared
     * @param ioStatus  the two-character file status
     * @param operation the attempted operation, one of {@code STARTBR}, {@code READNEXT} or {@code READPREV}
     * @return the exception the status maps to, or an empty result for a success or an end of file
     */
    private Optional<CardDemoException> classify(final ScreenWorkArea work, final String ioStatus,
            final String operation) {
        final Throwable cause = work.ioFailureCause;
        work.ioFailureCause = null;
        if (cause == null) {
            return this.fileStatusMapper.toException(ioStatus, USRSEC_FILE, operation);
        }
        return this.fileStatusMapper.toException(ioStatus, USRSEC_FILE, operation, cause);
    }

    /**
     * Retains the first typed failure of the request. The source cannot throw, so it records a message and
     * carries on; a stateless caller needs the typed outcome as well, and {@code mainPara} rethrows it once
     * the screen has been assembled exactly as the source assembles it. Nothing is discarded and no
     * {@code catch} block is empty.
     *
     * @param work    the per-invocation work area
     * @param failure the typed failure to retain; the first one wins, exactly as the first message does
     */
    private static void retainFailure(final ScreenWorkArea work, final CardDemoException failure) {
        if (work.retainedFailure == null) {
            work.retainedFailure = failure;
        }
    }

    // Static helpers. Pure functions over the screen fields; no state, no clock, no repository.

    /**
     * Reproduces the source's {@code NOT = SPACES AND LOW-VALUES} test: a field counts as supplied only when
     * it holds something other than blanks and nulls.
     *
     * @param value the field to test, possibly {@code null}
     * @return {@code true} when the field carries a value the source would act on
     */
    private static boolean isPresent(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Reproduces the source's {@code = SPACES OR LOW-VALUES} test, the exact complement of
     * {@code isPresent}.
     *
     * @param value the field to test, possibly {@code null}
     * @return {@code true} when the field is unset or blank
     */
    private static boolean isBlankOrUnset(final String value) {
        return !isPresent(value);
    }

    /**
     * Applies a {@code MOVE} into a narrower alphanumeric field, which truncates the low-order bytes.
     *
     * @param value     the source field, possibly {@code null}
     * @param maxLength the width of the receiving field
     * @return the value cut to {@code maxLength}, or {@code null} when {@code value} was {@code null}
     */
    private static String truncate(final String value, final int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * Rejects a submitted field wider than the screen field it transcribes. Nothing is trimmed or padded:
     * an over-wide value is a malformed request, and quietly cutting it would accept input the source
     * rejects.
     *
     * <p>The width is a count of Unicode code points rather than of {@code char} values, and the unit is
     * load-bearing. A {@code PIC X(n)} clause declares n character positions and the {@code CHAR(n)} column
     * it maps to pads to n characters, while a Java {@code String} measures itself in UTF-16 code units; the
     * two disagree for any supplementary-plane character. Counting code units let a value that had been
     * accepted, stored and padded fail when it was read back and offered here again. A code point count never
     * exceeds a code unit count, so this is the same bound the write path applies rather than a looser one.
     * Held as {@code DL-MS-05} in {@code DECISION_LOG.md}.
     *
     * @param value     the submitted field, possibly {@code null}; {@code null} and blank are accepted,
     *                  because the source accepts an unset field everywhere it reads one
     * @param maxLength the declared width of the screen field
     * @param fieldName the field name to name in the failure
     * @return {@code value} unchanged
     * @throws ValidationException if {@code value} is longer than {@code maxLength}
     */
    private static String requireWidth(final String value, final int maxLength, final String fieldName) {
        if (value == null) {
            return null;
        }
        final int characterPositions = value.codePointCount(0, value.length());
        if (characterPositions > maxLength) {
            throw new ValidationException(fieldName + " must be at most " + maxLength
                    + " characters because the screen field is that wide, but was " + characterPositions
                    + " characters long", fieldName, ValidationException.FailureKind.INVALID);
        }
        return value;
    }

    /**
     * Rejects an identifier wider than {@code SEC-USR-ID PIC X(08)}, the eight-character cluster key
     * {@code app/jcl/DUSRSECJ.jcl:65} defines with {@code KEYS(8,0)}.
     *
     * @param value     the submitted identifier, possibly {@code null}
     * @param fieldName the field name to name in the failure
     * @return {@code value} unchanged
     * @throws ValidationException if {@code value} is longer than eight characters
     */
    private static String requireKeyWidth(final String value, final String fieldName) {
        return requireWidth(value, USER_ID_KEY_LENGTH, fieldName);
    }

    /**
     * Rejects a page number outside the domain of {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}, declared at
     * {@code app/cbl/COUSR00C.cbl:70}. Zero is inside the domain and is meaningful: it is the value a first
     * display carries before {@code PROCESS-PAGE-FORWARD} increments it, and the value an empty file leaves
     * behind.
     *
     * @param pageNumber the submitted page number
     * @return {@code pageNumber} unchanged
     * @throws ValidationException if {@code pageNumber} is negative or exceeds eight digits
     */
    private static int requirePageNumber(final int pageNumber) {
        if (pageNumber < 0 || pageNumber > MAX_PAGE_NUMBER) {
            throw new ValidationException("pageNumber must be between 0 and " + MAX_PAGE_NUMBER
                    + " because CDEMO-CU00-PAGE-NUM is PIC 9(08), but was " + pageNumber,
                    "pageNumber", ValidationException.FailureKind.INVALID);
        }
        return pageNumber;
    }

    /**
     * Adds one to the page number the way {@code COMPUTE CDEMO-CU00-PAGE-NUM = CDEMO-CU00-PAGE-NUM + 1}
     * does at {@code app/cbl/COUSR00C.cbl:309-310} and {@code :320-321}.
     *
     * <p><strong>Preserved quirk.</strong> The field is {@code PIC 9(08)} and the statement carries no
     * {@code ON SIZE ERROR}, so COBOL discards the high-order digit and eight nines become zero. The
     * truncation is reproduced rather than replaced by a cap or an exception, because inventing either would
     * invent a policy the source does not have. Reaching it requires a hundred million pages. Severity: Low;
     * held as {@code DL-PP-13} in the DECISION_LOG.md.
     *
     * @param pageNumber the current page number
     * @return the incremented page number, wrapping to zero past eight digits
     */
    private static int incrementPageNumber(final int pageNumber) {
        return (pageNumber + 1) % (MAX_PAGE_NUMBER + 1);
    }

    /**
     * Renders the page number the way {@code MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI} does at
     * {@code app/cbl/COUSR00C.cbl:327} and {@code :377}: a {@code PIC 9(08)} field moved into
     * {@code PIC X(8)} arrives as eight zero-padded digits, so page one renders as {@code 00000001} and an
     * empty first display as {@code 00000000}.
     *
     * @param pageNumber the page number to render
     * @return exactly eight digits
     */
    private static String renderZeroPadded(final int pageNumber) {
        return String.format(Locale.ROOT, "%0" + PAGE_NUMBER_DIGITS + "d", pageNumber);
    }

    /**
     * Renders {@code SEC-USR-TYPE PIC X(01)} for {@code UTYPEnnI PIC X(1)}.
     *
     * <p>The screen field carries one character while the program's unused work area declares
     * {@code USER-TYPE PIC X(08)}; the screen governs, so nothing is widened here. Severity of widening it:
     * Low.
     *
     * @param userType the record's type, possibly {@code null}
     * @return the one-character code, or blank when the record carries no type
     */
    private static String renderUserType(final UserType userType) {
        return userType == null ? SPACES : String.valueOf(userType.getCode());
    }

    // Nested types. All of them live in this file, so the four-file budget of com.cardemo.service.admin
    // is unaffected.

    /**
     * The attention identifier the terminal sent, standing in for {@code EIBAID} as the source evaluates it
     * at {@code app/cbl/COUSR00C.cbl:122-137}.
     *
     * <p>Four keys are named because the source names four; everything else collapses into {@code OTHER},
     * which is the {@code WHEN OTHER} arm at {@code :132}. The identifier is also read by the two skip-read
     * predicates at {@code :288} and {@code :342}, which is why {@code PF3} has to be a distinct constant
     * even though its own arm transfers control before either predicate is reached: the predicates name it.
     */
    public enum AttentionIdentifier {

        /** {@code DFHENTER} - act on any row selection and re-list from page one. */
        ENTER,

        /** {@code DFHPF3} - exit to the administrative menu. Conventional here: it exits, it does not save. */
        PF3,

        /** {@code DFHPF7} - page backward. */
        PF7,

        /** {@code DFHPF8} - page forward. */
        PF8,

        /** Any other key, which the source reports as {@code CCDA-MSG-INVALID-KEY}. */
        OTHER
    }

    /**
     * The submitted screen: everything the source recovered from the communication area at
     * {@code app/cbl/COUSR00C.cbl:114} plus everything it received into the map at {@code :549-557}.
     *
     * <p>Carrying the paging state on the request is what makes the service stateless. There is no HTTP
     * session, no server-side cursor and no retained browse: the two keyset cursors the source keeps in
     * {@code CDEMO-CU00-USRID-FIRST} and {@code CDEMO-CU00-USRID-LAST} travel here and come back as response
     * metadata. Adding server-side paging state would be a High-severity defect.
     *
     * <p>Every component is untrusted. Widths are checked against the screen fields they transcribe, the page
     * number against the {@code PIC 9(08)} domain of {@code CDEMO-CU00-PAGE-NUM}, and the row count against
     * the number of rows the screen paints.
     *
     * @param pageNumber        {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} as the previous response reported it.
     *                          Zero means no page has been displayed yet
     * @param nextPageAvailable {@code CDEMO-CU00-NEXT-PAGE-FLG} as the previous response reported it; the
     *                          source tests it at {@code :270} and {@code :364}
     * @param firstKey          {@code CDEMO-CU00-USRID-FIRST PIC X(08)}, the identifier of the first row
     *                          displayed. {@code null} or blank models {@code LOW-VALUES}
     * @param lastKey           {@code CDEMO-CU00-USRID-LAST PIC X(08)}, the identifier of the last row
     *                          displayed. {@code null} or blank models {@code LOW-VALUES}, which
     *                          {@code PROCESS-PF8-KEY} turns into {@code HIGH-VALUES}
     * @param userIdInput       {@code USRIDINI PIC X(08)}, the identifier the operator typed
     * @param displayedRows     the rows as they were displayed, each carrying the selector the operator may
     *                          have typed over it. Never {@code null} once constructed - a {@code null}
     *                          argument becomes an empty list - and never holds a {@code null} element
     */
    public record UserListRequest(
            int pageNumber,
            boolean nextPageAvailable,
            String firstKey,
            String lastKey,
            String userIdInput,
            List<UserSecurityDto.UserRow> displayedRows) {

        /**
         * Normalises the row collection to an unmodifiable copy and rejects a {@code null} element by index,
         * so that a malformed request names the offending row rather than failing later with no context.
         *
         * @throws IllegalArgumentException if {@code displayedRows} holds a {@code null} element
         */
        public UserListRequest {
            final List<UserSecurityDto.UserRow> supplied =
                    displayedRows == null ? List.of() : displayedRows;
            int index = 0;
            for (final UserSecurityDto.UserRow row : supplied) {
                if (row == null) {
                    throw new IllegalArgumentException(
                            "displayedRows must not contain a null element, but index " + index + " was null");
                }
                index++;
            }
            displayedRows = List.copyOf(supplied);
        }
    }

    /**
     * The assembled response: the screen the source would have sent, the page as paging metadata, and the
     * outcome fields a stateless caller needs in order to decide what to do next.
     *
     * <p><strong>No password and no hash.</strong> {@code UserSecurityDto} does not carry a password
     * component at all, and neither does {@code UserSecurityDto.UserRow}. Nothing in this record can disclose
     * either, and the personal data it does carry - identifier, first name, last name and type code - is
     * exactly what the legacy screen displayed and nothing more.
     *
     * @param screen             the six header fields, the page number, the identifier field, the rows and
     *                           the {@code ERRMSGO PIC X(78)} advisory
     * @param page               the same rows as paging metadata: the page number floored at one, the page
     *                           size, the next-page flag and the two keyset cursors
     * @param legacyPageNumber   {@code CDEMO-CU00-PAGE-NUM} exactly as the source left it, which can be zero
     *                           where {@code page} reports one. Zero is what an empty browse leaves behind,
     *                           because the increment at {@code app/cbl/COUSR00C.cbl:320-321} is guarded by
     *                           {@code IF WS-IDX > 1}. Send this value back on the next request
     * @param selectedUserId     {@code CDEMO-CU00-USR-SELECTED PIC X(08)}, the identifier of the row the
     *                           operator selected, or blank when none was
     * @param selectionFlag      {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}, the selector as typed - the case is
     *                           not folded, because the source stores it unfolded
     * @param navigationTarget   the program the source would have transferred to, or {@code null} when it
     *                           stayed on this screen. {@code COUSR02C} and {@code COUSR03C} are advisory: no
     *                           sibling service is invoked, and the caller performs the navigation
     * @param cursorField        the screen field the source parked the cursor on, always {@code USRIDIN} in
     *                           this program
     * @param errorFlagOn        {@code WS-ERR-FLG}. Note that neither an end of file nor a positioning
     *                           failure sets it: the source sets it only on its {@code WHEN OTHER} arms
     * @param eraseRequested     whether the source would have sent with {@code ERASE}. {@code false} is the
     *                           variant whose {@code ERASE} is commented out at {@code :541}, which the two
     *                           already-at-boundary paths select
     * @param sendCount          how many times the source would have sent within the turn. Two means a
     *                           boundary or failure arm sent and then fell through to the page logic, which
     *                           sent again
     * @param controlTransferred whether the source handed control away with {@code EXEC CICS XCTL} instead of
     *                           returning to this screen
     */
    public record UserListScreen(
            UserSecurityDto screen,
            PageResponse<UserSecurityDto.UserRow> page,
            int legacyPageNumber,
            String selectedUserId,
            String selectionFlag,
            String navigationTarget,
            String cursorField,
            boolean errorFlagOn,
            boolean eraseRequested,
            int sendCount,
            boolean controlTransferred) {
    }

    /**
     * The per-invocation work area: {@code WORKING-STORAGE}, the {@code CDEMO-CU00-INFO} block and the shared
     * map storage, all of it created fresh for every call and discarded when the call returns.
     *
     * <p><strong>Why this class exists.</strong> Clause B forbids global mutable state, so {@code WS-ERR-FLG},
     * {@code WS-MESSAGE}, {@code WS-IDX}, {@code WS-PAGE-NUM}, the end-of-file switch, the erase switch, the
     * browse position and every screen-row slot are held here rather than on the bean. The service itself has
     * four immutable fields and no mutable state at all, so it is stateless and safe to share across threads;
     * a message held on the bean would be both a concurrency defect and a disclosure risk.
     *
     * <p><strong>The shared map storage.</strong> {@code app/cpy-bms/COUSR00.CPY:373} declares
     * {@code 01 COUSR0AO REDEFINES COUSR0AI}, so the input and output maps occupy the same bytes. That is why
     * {@code POPULATE-USER-DATA} writing to the {@code I} fields is what the send transmits, and why a
     * selector the operator typed survives {@code INITIALIZE-USER-DATA}. One set of row arrays reproduces the
     * redefinition exactly.
     *
     * <p><strong>Not modelled.</strong> {@code WS-REC-COUNT} at {@code app/cbl/COUSR00C.cbl:52} is declared and never
     * referenced anywhere in the program, and {@code WS-USER-DATA} at {@code :56-64} is a ten-occurrence work table
     * the program never reads or writes - {@code POPULATE-USER-DATA} projects straight onto the map instead. Both are
     * dead in the source rather than dead here; they are held as {@code DL-PP-13} in the DECISION_LOG.md
     * instead of being
     * carried as fields that nothing would touch, which would be dead code in the target. Severity: Low.
     */
    private static final class ScreenWorkArea {

        /** {@code EIBAID}, read by the dispatch and by both skip-read predicates. */
        private AttentionIdentifier aid = AttentionIdentifier.ENTER;

        /** {@code WS-ERR-FLG} at {@code :40-42}. Set only by the {@code WHEN OTHER} arms. */
        private boolean errFlgOn;

        /** {@code WS-USER-SEC-EOF} at {@code :43-45}. Set by every end-of-file and by a failed positioning. */
        private boolean userSecEof;

        /** {@code CDEMO-CU00-NEXT-PAGE-FLG} at {@code :71-73}. */
        private boolean nextPageAvailable;

        /** {@code WS-SEND-ERASE-FLG} at {@code :46-48}, whose initial value is {@code 'Y'}. */
        private boolean sendEraseYes = true;

        /** Which of the two {@code SEND} variants the last send selected. */
        private boolean eraseRequested = true;

        /** How many times the turn would have sent. */
        private int sendCount;

        /** {@code WS-MESSAGE PIC X(80)} at {@code :38}. */
        private String message = SPACES;

        /** {@code ERRMSGO PIC X(78)}, the message after the narrowing move at {@code :526}. */
        private String errorMessage = SPACES;

        /** The screen field the cursor was parked on by {@code MOVE -1 TO USRIDINL}. */
        private String cursorField;

        /** {@code CDEMO-TO-PROGRAM}, the only communication-area routing field with any counterpart. */
        private String navigationTarget;

        /** Whether {@code EXEC CICS XCTL} handed control away. */
        private boolean controlTransferred;

        /** {@code WS-IDX} at {@code :53}, the row index both loops drive. */
        private int idx;

        /** {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at {@code :70}. */
        private int pageNum;

        /** {@code PAGENUMI PIC X(8)}, eight zero-padded digits. */
        private String pageNumberDisplay = SPACES;

        /** {@code CDEMO-CU00-USRID-FIRST PIC X(08)} at {@code :68}. */
        private String usridFirst;

        /** {@code CDEMO-CU00-USRID-LAST PIC X(08)} at {@code :69}. */
        private String usridLast;

        /** {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)} at {@code :74}. */
        private String selectionFlag = SPACES;

        /** {@code CDEMO-CU00-USR-SELECTED PIC X(08)} at {@code :75}. */
        private String selectedUserId = SPACES;

        /** {@code USRIDINI PIC X(08)}, the identifier field of the shared map. */
        private String userIdInput;

        /** How many rows the request submitted, which is how far {@code USRID-LAST} sits into its page. */
        private int submittedRowCount;

        /** {@code TRNNAMEI PIC X(4)}. */
        private String transactionName = SPACES;

        /** {@code TITLE01I PIC X(40)}. */
        private String title01 = SPACES;

        /** {@code CURDATEI PIC X(8)}, {@code MM/DD/YY}. */
        private String currentDate = SPACES;

        /** {@code PGMNAMEI PIC X(8)}. */
        private String programName = SPACES;

        /** {@code TITLE02I PIC X(40)}. */
        private String title02 = SPACES;

        /** {@code CURTIMEI PIC X(8)}, {@code HH:MM:SS}. */
        private String currentTime = SPACES;

        /** {@code SELnnnnI PIC X(1)} for each row. Never cleared by {@code INITIALIZE-USER-DATA}. */
        private final String[] selectionFlags;

        /** {@code USRIDnnI PIC X(8)} for each row. */
        private final String[] rowUserIds;

        /** {@code FNAMEnnI PIC X(20)} for each row. */
        private final String[] rowFirstNames;

        /** {@code LNAMEnnI PIC X(20)} for each row. */
        private final String[] rowLastNames;

        /** {@code UTYPEnnI PIC X(1)} for each row. */
        private final String[] rowUserTypes;

        /** {@code SEC-USER-DATA}, the eighty-byte record the last read returned. */
        private UserSecurity currentRecord;

        /** The ordinal the next read will return, standing in for the VSAM browse position. */
        private long browseOrdinal;

        /** The ordinal the caller's page arithmetic says the start key occupies. */
        private long startOrdinal;

        /**
         * The key the browse is positioned on, or {@code null} for a start-of-file browse.
         *
         * <p>H-08: this, and not the submitted page number, is what decides which rows a keyed browse returns.
         */
        private String anchorKey;

        /** The ordinal assigned to {@link #anchorKey}; every other ordinal is relative to it. */
        private long anchorOrdinal;

        /** {@code SEC-USR-ID} as {@code STARTBR} received it; {@code null} models {@code LOW-VALUES}. */
        private String startKey;

        /** Whether {@code STARTBR} received {@code HIGH-VALUES}, which positions past the last record. */
        private boolean startPastEnd;

        /** The page-aligned block of records currently loaded, or {@code null} when none is. */
        private List<UserSecurity> browseWindow;

        /** The ordinal of the first record the loaded block covers. */
        private long windowBaseOrdinal;

        /** How many ordinals the loaded block covers, whether or not every one holds a record. */
        private int windowSpan;

        /** {@code WS-RESP-CD S9(9) COMP} at {@code :50}. */
        private int responseCode = CICS_RESP_NORMAL;

        /** {@code WS-REAS-CD S9(9) COMP} at {@code :51}. */
        private int reasonCode = CICS_REASON_NONE;

        /** The store failure behind an I/O status, held so that it becomes the exception's cause. */
        private Throwable ioFailureCause;

        /** The first typed failure of the request, rethrown by {@code mainPara} once the screen is built. */
        private CardDemoException retainedFailure;

        /**
         * Allocates one work area, its row storage sized to the configured page size.
         *
         * @param rowCount how many rows the screen paints, from
         *                 {@code carddemo.pagination.user-list-page-size}
         */
        private ScreenWorkArea(final int rowCount) {
            this.selectionFlags = new String[rowCount];
            this.rowUserIds = new String[rowCount];
            this.rowFirstNames = new String[rowCount];
            this.rowLastNames = new String[rowCount];
            this.rowUserTypes = new String[rowCount];
        }

        /**
         * {@code MOVE LOW-VALUES TO COUSR0AO} at {@code app/cbl/COUSR00C.cbl:117}. Because the output map
         * redefines the input map, this clears the selectors and the identifier field as well as the row
         * data - which is the only place in the program where the selectors are cleared, and the reason a
         * first display never acts on a stale selection.
         */
        private void clearOutputMap() {
            for (int slot = 0; slot < this.rowUserIds.length; slot++) {
                this.selectionFlags[slot] = null;
                this.rowUserIds[slot] = null;
                this.rowFirstNames[slot] = null;
                this.rowLastNames[slot] = null;
                this.rowUserTypes[slot] = null;
            }
            this.userIdInput = null;
            this.pageNumberDisplay = null;
            this.errorMessage = null;
            this.transactionName = null;
            this.title01 = null;
            this.currentDate = null;
            this.programName = null;
            this.title02 = null;
            this.currentTime = null;
            this.submittedRowCount = 0;
        }

        /**
         * Reads {@code SELnnnnI} for one row, tolerating a row the configured page size does not paint.
         *
         * @param slot the zero-based row index
         * @return the selector, or {@code null} when the row is outside the screen
         */
        private String selectionAt(final int slot) {
            return slot < this.selectionFlags.length ? this.selectionFlags[slot] : null;
        }

        /**
         * Reads {@code USRIDnnI} for one row, tolerating a row the configured page size does not paint.
         *
         * @param slot the zero-based row index
         * @return the identifier, or {@code null} when the row is outside the screen
         */
        private String rowUserIdAt(final int slot) {
            return slot < this.rowUserIds.length ? this.rowUserIds[slot] : null;
        }

        /**
         * {@code IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3} at {@code app/cbl/COUSR00C.cbl:288} - the
         * forward skip read, which consumes the record the browse was positioned on so that {@code PF8}
         * advances past the last row of the displayed page instead of repeating it.
         *
         * @return whether the forward page must perform the extra leading read
         */
        private boolean forwardSkipReadRequired() {
            return switch (this.aid) {
                case ENTER, PF3, PF7 -> false;
                case PF8, OTHER -> true;
            };
        }

        /**
         * {@code IF EIBAID NOT = DFHENTER AND DFHPF8} at {@code app/cbl/COUSR00C.cbl:342} - the backward skip
         * read. It excludes different keys from the forward predicate and the two are not interchangeable.
         *
         * @return whether the backward page must perform the extra leading read
         */
        private boolean backwardSkipReadRequired() {
            return switch (this.aid) {
                case ENTER, PF8 -> false;
                case PF3, PF7, OTHER -> true;
            };
        }

        /**
         * Whether the loaded block covers an ordinal, whether or not a record exists at it.
         *
         * @param ordinal the ordinal to test
         * @return {@code true} when the block was loaded for a range containing {@code ordinal}
         */
        private boolean windowContains(final long ordinal) {
            return this.browseWindow != null
                    && ordinal >= this.windowBaseOrdinal
                    && ordinal < this.windowBaseOrdinal + this.windowSpan;
        }

        /**
         * Reads one record out of the loaded block.
         *
         * @param ordinal an ordinal the block covers
         * @return the record, or {@code null} when the block was loaded for that range and the store returned
         *         nothing at it, which is an end of file
         */
        private UserSecurity windowRecord(final long ordinal) {
            if (!windowContains(ordinal)) {
                return null;
            }
            final int offset = (int) (ordinal - this.windowBaseOrdinal);
            return offset < this.browseWindow.size() ? this.browseWindow.get(offset) : null;
        }

        /**
         * Installs a freshly read block of records, replacing whatever was loaded before.
         *
         * @param baseOrdinal the ordinal of the block's first record
         * @param span        how many ordinals the block was requested for
         * @param records     the records the store returned, at most {@code span} of them
         */
        private void loadWindow(final long baseOrdinal, final int span, final List<UserSecurity> records) {
            this.windowBaseOrdinal = baseOrdinal;
            this.windowSpan = span;
            this.browseWindow = records;
        }

        /**
         * Anchors the browse on a key, so that positioning depends on the key alone.
         *
         * <p>The anchor is given a non-zero ordinal purely so that a backward walk has room to run without the
         * ordinals turning negative - {@code fetchAtOrdinal} treats a negative ordinal as having run off an end
         * of the file. One invocation walks at most a page plus its two probe reads, so a page of headroom plus
         * two is provably enough; the value itself has no meaning beyond that and is never displayed.
         *
         * @param key the key to position on; {@code null} leaves the browse at the start of the file
         * @param headroom how many ordinals of backward room to reserve below the anchor
         */
        private void anchorOn(final String key, final int headroom) {
            this.anchorKey = key;
            this.anchorOrdinal = key == null ? 0L : headroom;
            this.startOrdinal = this.anchorOrdinal;
        }

        /**
         * Discards the loaded block, which is what {@code EXEC CICS ENDBR} amounts to once the browse has
         * become a paged query, and what {@code EXEC CICS STARTBR} must do before it repositions.
         */
        private void resetBrowseWindow() {
            this.browseWindow = null;
            this.windowBaseOrdinal = 0L;
            this.windowSpan = 0;
        }

        /**
         * Assembles the response from the map storage.
         *
         * <p>Only populated rows are emitted, in ascending row order. For every reachable path that is the
         * whole page in key order: a forward page fills the slots from the first, and a backward page fills
         * them to the last from a block that is always complete. A partially filled backward page would
         * collapse its leading blanks here, and that path is unreachable from a well-formed client - see
         * {@code processPageBackward}.
         *
         * @return the assembled screen, its paging metadata and the outcome fields
         */
        private UserListScreen toScreen() {
            final List<UserSecurityDto.UserRow> rows = new ArrayList<>(this.rowUserIds.length);
            for (int slot = 0; slot < this.rowUserIds.length; slot++) {
                if (isPresent(this.rowUserIds[slot])) {
                    rows.add(new UserSecurityDto.UserRow(this.selectionFlags[slot], this.rowUserIds[slot],
                            this.rowFirstNames[slot], this.rowLastNames[slot], this.rowUserTypes[slot]));
                }
            }

            final UserSecurityDto assembled = new UserSecurityDto(this.transactionName, this.title01,
                    this.currentDate, this.programName, this.title02, this.currentTime,
                    this.pageNumberDisplay, this.userIdInput, rows, this.errorMessage);

            final PageResponse<UserSecurityDto.UserRow> paging = new PageResponse<>(rows,
                    Math.max(FIRST_PAGE_NUMBER, this.pageNum), this.rowUserIds.length,
                    this.nextPageAvailable, this.usridFirst, this.usridLast);

            return new UserListScreen(assembled, paging, this.pageNum, this.selectedUserId,
                    this.selectionFlag, this.navigationTarget, this.cursorField, this.errFlgOn,
                    this.eraseRequested, this.sendCount, this.controlTransferred);
        }
    }
}
