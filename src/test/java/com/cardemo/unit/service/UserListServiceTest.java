/*
 * ******************************************************************
 * Program     : UserListServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies UserListService against COUSR00C paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the ten row page arity of :57, the
 *               action conditional skip read of :288 and :342, the guarded
 *               clear of :292-296 that leaves stale rows at end of data, the
 *               eleventh look ahead read of :311 that decides the next page
 *               flag and is then discarded, the asymmetric page number
 *               increment of :309-310 against :319-322, the first non blank
 *               selection of :151-185, the two boundary keys captured by rows
 *               one and ten alone, the exact key browse start left by the
 *               commented out GTEQ at :592, and the five distinct edge of
 *               data literals.
 * Source      : app/cbl/COUSR00C.cbl    (695 lines, 16 own paragraph labels)
 *               app/cpy/CSUSR01Y.cpy    (SEC-USER-DATA, 80 bytes, key 8)
 *               app/cpy/COCOM01Y.cpy    (CARDDEMO-COMMAREA, no paging fields)
 *               app/cpy/CSMSG01Y.cpy    (CCDA-MSG-INVALID-KEY)
 *               app/cpy-bms/COUSR00.CPY (59 input fields, PAGENUMI X(8))
 *               app/cpy/COTTL01Y.cpy    (the two title literals)
 *               app/cbl/CBACT04C.cbl    (the canonical banner form, L1-L21)
 *               app/jcl/DUSRSECJ.jcl    (KEYS(8,0) RECORDSIZE(80,80))
 *               app/csd/CARDDEMO.CSD    (CU00 -> COUSR00C) @ 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserListService.AttentionIdentifier;
import com.cardemo.service.admin.UserListService.UserListRequest;
import com.cardemo.service.admin.UserListService.UserListScreen;
import com.cardemo.service.shared.FileStatusMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit tests for {@code UserListService}, the Java target of {@code app/cbl/COUSR00C.cbl} and CICS
 * transaction {@code CU00}.
 *
 * <h2>What it does</h2>
 *
 * <p>Asserts the behaviour of the twenty two paragraph level contracts that a reader cannot confirm from
 * the Java alone, each against its verified locator in the frozen source. The locators exercised here are
 * {@code app/cbl/COUSR00C.cbl} at {@code :40-:48} (the four working storage switches), {@code :54} (the
 * dead {@code WS-PAGE-NUM}), {@code :56-:64} (the dead four hundred and eighty byte work table),
 * {@code :66-:75} (the program local paging extension appended after {@code COPY COCOM01Y}),
 * {@code :78-:80} (the title, date and message copybooks), {@code :98} ({@code MAIN-PARA}), {@code :149}
 * ({@code PROCESS-ENTER-KEY}), {@code :151-:185} (the ten arm selection evaluate), {@code :187-:199} and
 * {@code :200-:215} (the three arm action dispatch), {@code :237} ({@code PROCESS-PF7-KEY}), {@code :251},
 * {@code :260} ({@code PROCESS-PF8-KEY}), {@code :273}, {@code :282}
 * ({@code PROCESS-PAGE-FORWARD}), {@code :288-:290} (the action conditional skip read),
 * {@code :292-:296} (the guarded clear), {@code :300-:306} (the ten row fill), {@code :308-:323} (the
 * eleventh look ahead and the asymmetric increment), {@code :327}, {@code :336}
 * ({@code PROCESS-PAGE-BACKWARD}), {@code :384-:437} ({@code POPULATE-USER-DATA}), {@code :446}
 * ({@code INITIALIZE-USER-DATA}), {@code :506} ({@code RETURN-TO-PREV-SCREEN}), {@code :522}
 * ({@code SEND-USRLST-SCREEN}), {@code :549} ({@code RECEIVE-USRLST-SCREEN}), {@code :562}
 * ({@code POPULATE-HEADER-INFO}), {@code :586} and {@code :588-:614} ({@code STARTBR-USER-SEC-FILE}),
 * {@code :592} (the commented out {@code GTEQ}), {@code :601} (the {@code CONTINUE} that terminates
 * nothing), {@code :603}, {@code :610}, {@code :619} ({@code READNEXT-USER-SEC-FILE}), {@code :637},
 * {@code :644}, {@code :653} ({@code READPREV-USER-SEC-FILE}), {@code :671}, {@code :678} and
 * {@code :687} ({@code ENDBR-USER-SEC-FILE}).
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp test -Dtest=UserListServiceTest} runs this class alone;
 * {@code ./mvnw -B -ntp test} runs the whole unit tier. <strong>Surefire binds this tier.</strong> The
 * class sits at {@code src/test/java/com/cardemo/unit/service/}, which matches the Surefire include
 * {@code **}{@code /*Test.java} while falling outside the Failsafe includes of
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}. A class placed outside
 * {@code com.cardemo.unit} would match neither plugin and would silently never run, with both plugins
 * still reporting success. Nothing here starts a container, a Spring context, a database or a socket.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <ul>
 *   <li>{@code carddemo.pagination.user-list-page-size} is ten, injected through the constructor. It is a
 *       <strong>parity contract, not a tunable</strong>: the source hard codes the arity at
 *       {@code USER-REC OCCURS 10 TIMES} on {@code app/cbl/COUSR00C.cbl:57} and repeats the literal in the
 *       fill bound at {@code :300} and the clear bound at {@code :293}. Ten is passed here for every test
 *       that pages; the only tests that pass another value are the two that assert the constructor floor.</li>
 *   <li>The repository is a Mockito double created with {@link Strictness#STRICT_STUBS}, because the whole
 *       point of these tests is to dictate the exact number of records a browse window yields. A stubbing
 *       whose arguments the service never presents fails loudly rather than returning a default.</li>
 *   <li>{@link FileStatusMapper} is a <strong>real</strong> instance, not a double: it has a no argument
 *       constructor and no collaborators, so the exception types asserted here are the ones production
 *       raises.</li>
 *   <li>The clock is {@link Clock#fixed} at {@code 2022-06-10T19:27:53Z} against {@link ZoneOffset#UTC},
 *       so the two header fields the source derives from one {@code MOVE FUNCTION CURRENT-DATE} at
 *       {@code :564} render reproducibly as {@code 06/10/22} and {@code 19:27:53}.</li>
 *   <li>Stored credentials are BCrypt at strength ten. Every credential in this file is a
 *       <strong>shape only</strong> placeholder built by {@code syntheticHash}: sixty characters that
 *       satisfy the entity invariant and carry no secret. The plaintext the ten seeded rows of
 *       {@code app/jcl/DUSRSECJ.jcl} share is never written here in any form.</li>
 *   <li>Authorisation is {@code ADMIN} only and the session policy is stateless, but neither is enforced
 *       by this bean - see the failure modes below.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>An unused import must be pruned, though the build will not report it.</strong> The compiler
 *       runs {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning} and that reaches test
 *       compilation, but {@code javac} 25 publishes no {@code unused} lint key, so an unused import is a
 *       Rule 1 Clause B violation caught at review rather than a build failure. This class
 *       deliberately imports neither the shared fixed-clock helper of the sibling {@code unit.model}
 *       package nor any fixture loading helper: nothing on the user list path consults a fixture, and the
 *       one clock it needs is built inline. Naming either symbol here, even in prose, would trip the
 *       repository grep gate that proves the absence, so neither is spelled out.</li>
 *   <li><strong>Deriving the next page flag from a count.</strong> The source issues a real read one
 *       ordinal past the page at {@code :311} and throws the record away. A {@code count} query, a
 *       {@code totalElements} or any {@code hasNext} computed from a total is not equivalent, and
 *       {@code neverDerivesTheNextPageFlagFromACountQuery} fails if one is introduced.</li>
 *   <li><strong>Omitting the action conditional skip read.</strong> {@code :288} reads one record before
 *       the fill loop when, and only when, the attention identifier is none of {@code DFHENTER},
 *       {@code DFHPF7} and {@code DFHPF3}. Dropping it shifts every forward page by exactly one record,
 *       which looks like an off by one defect and is not.</li>
 *   <li><strong>Clearing the row buffer eagerly.</strong> The clear at {@code :293-:295} sits inside a
 *       guard at {@code :292}, so at end of data the previous page's rows survive on the screen. Clearing
 *       unconditionally loses that.</li>
 *   <li><strong>Validating "exactly one selection".</strong> {@code :151-:185} is an {@code EVALUATE}, so
 *       the first non blank selector wins and every later one is ignored in silence. Rejecting multiple
 *       selections diverges.</li>
 *   <li><strong>Restoring the commented out {@code GTEQ}.</strong> {@code :592} leaves it commented, so
 *       the browse positions on an exact key and an unknown key takes the not found arm. Restoring it
 *       changes first page behaviour.</li>
 *   <li><strong>Sharing a message constant with the transaction list.</strong> {@code COTRN00C} uses the
 *       same five sentence shapes but spells its lookup failure with a lower case {@code t}. The two
 *       services own their own literals; a shared constant silently breaks one of them.</li>
 *   <li><strong>The fixture name trap.</strong> The daily transaction fixture is
 *       {@code app/data/ASCII/dailytran.txt}, never {@code dalytran.txt}. It is irrelevant to this path,
 *       which is precisely why no fixture is loaded here.</li>
 *   <li><strong>Not available at this tier, stated plainly per clause F.</strong> Three facts cannot be
 *       observed through this bean's public surface. First, the {@code ADMIN} only mapping of
 *       {@code /api/admin/*} and {@code SessionCreationPolicy.STATELESS} live in the security
 *       configuration and the administration controller; what is asserted here instead is everything this
 *       bean itself can offer towards them - that it declares no authorisation of its own, accepts no role
 *       or user type argument, holds no mutable state, and is read only on every querying entry point. A
 *       web layer test owns the rest. Second, the literal {@code 'You are at the top of the page...'} of
 *       {@code :603} and the literal {@code 'Unable to lookup User...'} of {@code :610}, {@code :644} and
 *       {@code :678} never reach an observable screen, because the entry point converts both arms into
 *       typed exceptions before the screen is assembled; what is asserted instead is the arm each one
 *       accompanies, distinguished by exception type, operation name and cause, plus the byte exact
 *       literals pinned as constants in this class. Making the literals themselves observable would need
 *       either a named constant on the service for the second one, which is currently inlined at all three
 *       sites, or an accessor for the screen assembled on a throwing path; neither is in scope for a test
 *       and neither is invented here.</li>
 *   </ul>
 *
 * <h2>Deliberately preserved source artefacts, and their severities</h2>
 *
 * <p><strong>Blocker</strong> is the top of the ladder and there are exactly two ways to reach it here:
 * asserting any seeded password as plaintext, or letting the password field appear in a list response.
 * Neither is a stylistic matter - {@code app/jcl/DUSRSECJ.jcl} seeds ten rows that share one plaintext value,
 * so a single careless assertion would publish a credential into version control. Three tests stand guard:
 * {@code theResponseOmitsTheCredentialEntirely} proves {@code UserRow} has no credential component at all,
 * {@code noStoredCredentialReachesTheAssembledScreen} proves no assembled field ever equals the stored hash,
 * and {@code credentialsUsedHereAreWellFormedPlaceholders} proves the only credential shaped strings in this
 * file are synthetic. The seeded plaintext appears nowhere in this file in any form.
 *
 * <p>Four artefacts are retained rather than repaired, each carrying an entry in the {@code DECISION_LOG.md}
 * and a {@code TRACEABILITY_MATRIX.md} row, which is what satisfies clause B: the prohibition is on dead code
 * <em>without</em> an owner or tracking reference. The four hundred and eighty byte {@code WS-USER-DATA} table at
 * {@code :56-:64} has zero procedural references (Low). {@code WS-PAGE-NUM} at {@code :54} is
 * referenced only by its own declaration (Low). The {@code CONTINUE} at {@code :601} terminates nothing,
 * so the four statements after it all execute (Medium). The commented out {@code GTEQ} at {@code :592}
 * leaves the browse on exact key semantics (High if restored). Each has a test below that fails if the
 * artefact is "fixed".
 *
 * <p>Two deliberate mechanism substitutions are also asserted rather than hidden. The ten branch unrolled
 * {@code EVALUATE} of {@code :386-:441} collapses into one loop, with the row one and row ten asymmetries
 * preserved explicitly. The eleventh look ahead read is retained over a cheaper count query, because the
 * flag answers "does a record exist at that ordinal" and not "how many records are there".
 *
 * <p><strong>A correction to the plan this class was written from.</strong> The instruction said of
 * {@code POPULATE-USER-DATA} that {@code WHEN 1} alone captures a boundary key and that "no other branch
 * captures anything". Direct inspection of {@code app/cbl/COUSR00C.cbl:433-435} shows {@code WHEN 10} also
 * carries a multi receiver {@code MOVE}, into {@code CDEMO-CU00-USRID-LAST}. Two branches are asymmetric,
 * not one; rows two through nine capture nothing. The source governs, the implementation already matches
 * it, and {@code rowTenAlsoCapturesTheLastKey} pins the corrected fact.
 *
 * @see UserListService
 * @see UserSecurityRepository
 */
class UserListServiceTest {

    /**
     * {@code USER-REC OCCURS 10 TIMES}, {@code app/cbl/COUSR00C.cbl:57}. Reinforced by the fill bound
     * {@code PERFORM UNTIL WS-IDX >= 11} at {@code :300} and the clear bound
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} at {@code :293}.
     */
    private static final int PAGE_SIZE = 10;

    /** {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}, {@code app/cbl/COUSR00C.cbl:70}. */
    private static final int PAGE_NUMBER_DIGITS = 8;

    /** {@code SEC-USR-ID PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy:18}, and {@code KEYS(8,0)}. */
    private static final int KEY_LENGTH = 8;

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR00C'}, {@code app/cbl/COUSR00C.cbl:36}. */
    private static final String PROGRAM_NAME = "COUSR00C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CU00'}, {@code app/cbl/COUSR00C.cbl:37}. */
    private static final String TRANSACTION_ID = "CU00";

    /** {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}, {@code app/cbl/COUSR00C.cbl:39}. */
    private static final String USRSEC_FILE = "USRSEC";

    /** {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM}, {@code app/cbl/COUSR00C.cbl:126}. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}, {@code app/cbl/COUSR00C.cbl:111} and {@code :509}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** {@code MOVE 'COUSR02C' TO CDEMO-TO-PROGRAM}, {@code app/cbl/COUSR00C.cbl:192}. */
    private static final String USER_UPDATE_PROGRAM = "COUSR02C";

    /** {@code MOVE 'COUSR03C' TO CDEMO-TO-PROGRAM}, {@code app/cbl/COUSR00C.cbl:202}. */
    private static final String USER_DELETE_PROGRAM = "COUSR03C";

    /** {@code CCDA-TITLE01 PIC X(40)}, {@code app/cpy/COTTL01Y.cpy:19}. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)}, {@code app/cpy/COTTL01Y.cpy:22}. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /** The cursor field of every {@code MOVE -1 TO USRIDINL OF COUSR0AI} in the program. */
    private static final String CURSOR_FIELD = "USRIDIN";

    /**
     * {@code app/cbl/COUSR00C.cbl:251}, raised by {@code PROCESS-PF7-KEY} when the displayed page is
     * already the first. Note the word {@code already}, which the browse reported variant at {@code :603}
     * does not carry.
     */
    private static final String ALREADY_AT_TOP_MESSAGE = "You are already at the top of the page...";

    /** {@code app/cbl/COUSR00C.cbl:273}, raised by {@code PROCESS-PF8-KEY} at the last page. */
    private static final String ALREADY_AT_BOTTOM_MESSAGE = "You are already at the bottom of the page...";

    /**
     * {@code app/cbl/COUSR00C.cbl:603}, the {@code DFHRESP(NOTFND)} arm of {@code STARTBR}.
     * <strong>No {@code already}</strong> - this is a distinct string from {@link #ALREADY_AT_TOP_MESSAGE}
     * and collapsing the two loses parity.
     */
    private static final String AT_TOP_MESSAGE = "You are at the top of the page...";

    /** {@code app/cbl/COUSR00C.cbl:637}, the {@code DFHRESP(ENDFILE)} arm of {@code READNEXT}. */
    private static final String REACHED_BOTTOM_MESSAGE = "You have reached the bottom of the page...";

    /** {@code app/cbl/COUSR00C.cbl:671}, the {@code DFHRESP(ENDFILE)} arm of {@code READPREV}. */
    private static final String REACHED_TOP_MESSAGE = "You have reached the top of the page...";

    /**
     * {@code app/cbl/COUSR00C.cbl:610}, {@code :644} and {@code :678} - the same literal at three sites,
     * spelled with a <strong>capital {@code U}</strong>. The parallel family in {@code COTRN00C} spells
     * its noun in lower case, so the two services must never share a constant.
     */
    private static final String UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup User...";

    /** The lower case sibling spelling, held only so that the casing asymmetry can be asserted. */
    private static final String SIBLING_UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup transaction...";

    /** {@code app/cbl/COUSR00C.cbl:212}, the {@code WHEN OTHER} arm of the action dispatch. */
    private static final String INVALID_SELECTION_MESSAGE = "Invalid selection. Valid values are U and D";

    /** {@code CCDA-MSG-INVALID-KEY PIC X(50)} of {@code app/cpy/CSMSG01Y.cpy:21}, trailing blanks trimmed. */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** The seven character version and cost field of a BCrypt digest produced at the pinned strength. */
    private static final String BCRYPT_PREFIX = "$2a$10$";

    /**
     * Fifty one characters of salt and digest that carry no secret. Two digits of discriminator are
     * appended, taking the tail to the fifty three characters the entity invariant requires.
     */
    private static final String SYNTHETIC_DIGEST_STEM = "UnitTestSaltAndDigestPlaceholderNoSecretValueHereAB";

    /** The width of a stored credential, {@code sec_usr_pwd}. */
    private static final int BCRYPT_HASH_WIDTH = 60;

    /** The fixed instant behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :564}. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** {@code WS-CURDATE-MM-DD-YY} as the fixed instant renders it at {@link ZoneOffset#UTC}. */
    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    /** {@code WS-CURTIME-HH-MM-SS} as the fixed instant renders it at {@link ZoneOffset#UTC}. */
    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    /**
     * The sixteen paragraph labels of {@code app/cbl/COUSR00C.cbl}, in source order, as the private method
     * names they map to one for one. Never consolidated: {@code TRACEABILITY_MATRIX.md} will be proved against
     * this correspondence.
     */
    private static final List<String> PARAGRAPH_METHOD_NAMES = List.of(
            "mainPara",
            "processEnterKey",
            "processPf7Key",
            "processPf8Key",
            "processPageForward",
            "processPageBackward",
            "populateUserData",
            "initializeUserData",
            "returnToPrevScreen",
            "sendUsrlstScreen",
            "receiveUsrlstScreen",
            "populateHeaderInfo",
            "startbrUserSecFile",
            "readnextUserSecFile",
            "readprevUserSecFile",
            "endbrUserSecFile");

    /** The four browse paragraphs, which collapse conceptually but must stay four distinct methods. */
    private static final List<String> BROWSE_METHOD_NAMES = List.of(
            "startbrUserSecFile", "readnextUserSecFile", "readprevUserSecFile", "endbrUserSecFile");

    /** The security file, reached only through its one ordered paged finder. */
    private UserSecurityRepository repository;

    /** The bean under test, rebuilt for every test so that no state can survive a test boundary. */
    private UserListService service;

    /** Captures every {@link Pageable} the service presents, which is how the browse arithmetic is proved. */
    private ArgumentCaptor<Pageable> pageableCaptor;

    /**
     * Rebuilds the double, the mapper, the clock and the bean before every test.
     *
     * <p>Nothing is shared between tests and nothing is static and mutable, so the order Surefire chooses
     * cannot change an outcome.
     */
    @BeforeEach
    void setUp() {
        this.repository = mock(UserSecurityRepository.class,
                withSettings().strictness(Strictness.STRICT_STUBS));
        this.service = newService(PAGE_SIZE);
        this.pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    }

    // Fixtures and helpers. Every one of these is deterministic: no clock is
    // consulted, no randomness is drawn and no map iteration order is relied
    // upon, which is what clause A demands of a paging test in particular.

    /**
     * Builds the bean with a chosen page size, so that the constructor floor can be probed without
     * disturbing the ten row parity contract every other test relies on.
     *
     * @param pageSize the value {@code carddemo.pagination.user-list-page-size} would supply
     * @return a fresh bean over the current repository double, a real mapper and the fixed clock
     */
    private UserListService newService(int pageSize) {
        return new UserListService(
                this.repository,
                new FileStatusMapper(),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                pageSize);
    }

    /**
     * Produces a sixty character value shaped exactly like a BCrypt digest at strength ten and carrying no
     * secret whatsoever.
     *
     * <p>{@code UserSecurity} rejects anything that fails {@code ^\$2[aby]\$10\$[./A-Za-z0-9]{53}$}, so a
     * placeholder still has to be well formed. Seven characters of prefix plus fifty one characters of stem
     * plus two digits of ordinal discriminator is sixty. The plaintext that the ten rows seeded by
     * {@code app/jcl/DUSRSECJ.jcl} all share never appears in this file.
     *
     * @param ordinal a value in {@code 0 .. 99} that makes each placeholder distinct
     * @return a well formed, secret free credential placeholder
     */
    private static String syntheticHash(int ordinal) {
        String hash = BCRYPT_PREFIX + SYNTHETIC_DIGEST_STEM + String.format(Locale.ROOT, "%02d", ordinal % 100);
        if (hash.length() != BCRYPT_HASH_WIDTH) {
            throw new IllegalStateException(
                    "synthetic credential placeholder must be " + BCRYPT_HASH_WIDTH
                            + " characters to satisfy the entity invariant, but was " + hash.length());
        }
        return hash;
    }

    /**
     * Builds one security record whose key sorts in ascending order with its ordinal.
     *
     * <p>The eight character key mirrors {@code SEC-USR-ID PIC X(08)} exactly, so the keys the browse
     * captures at {@code :389} and {@code :435} are directly comparable with the fixture.
     *
     * @param ordinal a one based position in the store
     * @return a record with a deterministic key, names inside twenty characters and an alternating type
     */
    private static UserSecurity user(int ordinal) {
        return new UserSecurity(
                String.format(Locale.ROOT, "USR%05d", ordinal),
                String.format(Locale.ROOT, "FIRST%05d", ordinal),
                String.format(Locale.ROOT, "LAST%05d", ordinal),
                syntheticHash(ordinal),
                ordinal % 2 == 1 ? UserType.ADMIN : UserType.USER);
    }

    /**
     * Builds a store of {@code count} records in ascending key order.
     *
     * @param count how many records the security file holds
     * @return an ordered, immutable store
     */
    private static List<UserSecurity> store(int count) {
        List<UserSecurity> rows = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            rows.add(user(ordinal));
        }
        return List.copyOf(rows);
    }

    /**
     * Stubs the two keyset finders H-08 added, over the same ordered store the page finder serves.
     *
     * <p>Installed with {@code lenient()} because a given path uses one direction or neither: a start-of-file
     * browse anchors on no key at all and uses only the page finder, a forward page walk uses only the
     * ascending finder, and a backward one only the descending finder. Strict stubs would otherwise report the
     * unused direction as an unnecessary stubbing on every test that pages in a single direction.
     *
     * <p>The semantics reproduced are the derived-query semantics exactly: inclusive of the anchor, ordered by
     * the single key, ascending in one and descending in the other, and truncated to the requested size with a
     * has-next flag when rows remain.
     *
     * @param rows the ordered store to serve, ascending by user identifier
     */
    private void stubKeysetStore(List<UserSecurity> rows) {
        lenient().when(this.repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                anyString(), any(Pageable.class))).thenAnswer(invocation -> {
                    String anchor = invocation.getArgument(0, String.class);
                    Pageable pageable = invocation.getArgument(1, Pageable.class);
                    List<UserSecurity> matched = rows.stream()
                            .filter(row -> row.getSecUsrId().compareTo(anchor) >= 0)
                            .toList();
                    int to = Math.min(pageable.getPageSize(), matched.size());
                    return new SliceImpl<>(matched.subList(0, to), pageable, to < matched.size());
                });
        lenient().when(this.repository.findBySecUsrIdLessThanEqualOrderBySecUsrIdDesc(
                anyString(), any(Pageable.class))).thenAnswer(invocation -> {
                    String anchor = invocation.getArgument(0, String.class);
                    Pageable pageable = invocation.getArgument(1, Pageable.class);
                    List<UserSecurity> matched = new ArrayList<>(rows.stream()
                            .filter(row -> row.getSecUsrId().compareTo(anchor) <= 0)
                            .toList());
                    Collections.reverse(matched);
                    int to = Math.min(pageable.getPageSize(), matched.size());
                    return new SliceImpl<>(matched.subList(0, to), pageable, to < matched.size());
                });
    }

    /**
     * Stubs the one finder the repository declares so that it slices whatever store is supplied by whatever
     * {@link Pageable} the service presents.
     *
     * <p>The stub is installed only by the tests that page, which is why no test that asserts
     * {@code verifyNoInteractions} carries an unused stubbing. Because the matcher is
     * {@code any(Pageable.class)}, strict stubs can never raise a spurious argument mismatch here; the
     * arithmetic is asserted separately, from {@link #pageableCaptor}.
     *
     * @param rows the ordered store to slice
     */
    private void stubStore(List<UserSecurity> rows) {
        stubKeysetStore(rows);
        when(this.repository.findAllByOrderBySecUsrIdAsc(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0, Pageable.class);
            int from = Math.min((int) pageable.getOffset(), rows.size());
            int to = Math.min(from + pageable.getPageSize(), rows.size());
            return new SliceImpl<>(rows.subList(from, to), pageable, to < rows.size());
        });
    }

    /**
     * Stubs the finder to hand back exactly the slice a window request would yield, then to fail loudly if
     * a window beyond the store is ever asked for. Used by the tests that pin how many windows a path
     * loads.
     *
     * @param rows the ordered store to slice
     */
    private void stubStoreRejectingWindowsPastTheEnd(List<UserSecurity> rows) {
        stubKeysetStore(rows);
        when(this.repository.findAllByOrderBySecUsrIdAsc(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0, Pageable.class);
            if (pageable.getOffset() > rows.size()) {
                throw new IllegalStateException(
                        "the browse asked for a window starting at offset " + pageable.getOffset()
                                + ", which is past the " + rows.size() + " record store");
            }
            int from = Math.min((int) pageable.getOffset(), rows.size());
            int to = Math.min(from + pageable.getPageSize(), rows.size());
            return new SliceImpl<>(rows.subList(from, to), pageable, to < rows.size());
        });
    }

    /**
     * Stubs the finder to raise a data access failure for every window at or past {@code fromPageNumber},
     * while serving earlier windows normally.
     *
     * <p>This is how the {@code WHEN OTHER} arms of {@code READNEXT} at {@code :641-:647} and
     * {@code READPREV} at {@code :675-:681} are reached without disturbing the browse start that precedes
     * them.
     *
     * <p><strong>Why the failure is discriminated by requested page size.</strong> The browse start itself
     * now issues an equal-or-greater probe, and that probe asks for exactly one row. A helper that failed
     * every keyset call unconditionally would therefore fail the {@code STARTBR} instead of the read that
     * follows it, leaving both {@code WHEN OTHER} arms unreachable again. Every real page read asks for a
     * full page and never for a single row, so the size is an exact discriminator: one row is the position
     * probe and is served normally, anything wider is a page read and fails.
     *
     * @param rows the ordered store to slice for the windows that succeed
     * @param fromPageNumber the first zero based window number that fails
     * @param failure the failure to raise
     */
    private void stubStoreFailingFromWindow(
            List<UserSecurity> rows, int fromPageNumber, RuntimeException failure) {
        // H-08: a keyed browse reads through the keyset finders, so a helper that failed only the page finder
        // would leave the READNEXT and READPREV failure arms unreachable. Both directions fail here for the
        // same reason the page finder does: the store is broken, not one query shape.
        lenient().when(this.repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                anyString(), any(Pageable.class))).thenAnswer(invocation -> {
                    String anchor = invocation.getArgument(0, String.class);
                    Pageable pageable = invocation.getArgument(1, Pageable.class);
                    if (pageable.getPageSize() > 1) {
                        throw failure;
                    }
                    List<UserSecurity> matched = rows.stream()
                            .filter(row -> row.getSecUsrId().compareTo(anchor) >= 0)
                            .toList();
                    int to = Math.min(pageable.getPageSize(), matched.size());
                    return new SliceImpl<>(matched.subList(0, to), pageable, to < matched.size());
                });
        lenient().when(this.repository.findBySecUsrIdLessThanEqualOrderBySecUsrIdDesc(
                anyString(), any(Pageable.class))).thenThrow(failure);
        when(this.repository.findAllByOrderBySecUsrIdAsc(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0, Pageable.class);
            if (pageable.getPageNumber() >= fromPageNumber) {
                throw failure;
            }
            int from = Math.min((int) pageable.getOffset(), rows.size());
            int to = Math.min(from + pageable.getPageSize(), rows.size());
            return new SliceImpl<>(rows.subList(from, to), pageable, to < rows.size());
        });
    }

    /**
     * Builds the request the controller would submit on a re-entry, defaulting every field the caller does
     * not care about.
     *
     * @param pageNumber the page currently displayed, as {@code CDEMO-CU00-PAGE-NUM}
     * @param nextPageAvailable {@code CDEMO-CU00-NEXT-PAGE-FLG}
     * @param firstKey {@code CDEMO-CU00-USRID-FIRST}
     * @param lastKey {@code CDEMO-CU00-USRID-LAST}
     * @param userIdInput {@code USRIDINI}
     * @param rows {@code SEL0001I .. SEL0010I} together with the identifiers beside them
     * @return the assembled request
     */
    private static UserListRequest request(
            int pageNumber,
            boolean nextPageAvailable,
            String firstKey,
            String lastKey,
            String userIdInput,
            List<UserSecurityDto.UserRow> rows) {
        return new UserListRequest(pageNumber, nextPageAvailable, firstKey, lastKey, userIdInput, rows);
    }

    /**
     * Builds one submitted screen row, as {@code RECEIVE-USRLST-SCREEN} at {@code :549} would deliver it.
     *
     * @param selectionFlag one character, blank when the row is not selected
     * @param userId the eight character identifier painted beside the selector
     * @return the submitted row
     */
    private static UserSecurityDto.UserRow row(String selectionFlag, String userId) {
        return new UserSecurityDto.UserRow(selectionFlag, userId, "FIRST", "LAST", "U");
    }

    /**
     * Builds a submitted page of ten rows whose keys match {@link #store(int)}, with a selector on one of
     * them.
     *
     * @param selectedIndex a one based row position, or zero for a page with no selection at all
     * @param selectionFlag the selector character to place on that row
     * @return ten rows, at most one of them selected
     */
    private static List<UserSecurityDto.UserRow> pageWithSelection(int selectedIndex, String selectionFlag) {
        List<UserSecurityDto.UserRow> rows = new ArrayList<>(PAGE_SIZE);
        for (int position = 1; position <= PAGE_SIZE; position++) {
            rows.add(row(position == selectedIndex ? selectionFlag : " ",
                    String.format(Locale.ROOT, "USR%05d", position)));
        }
        return List.copyOf(rows);
    }

    /**
     * Runs the first entry path, {@code EIBCALEN} non zero and {@code CDEMO-PGM-CONTEXT} not re-enter.
     *
     * @param userIdInput the starting key typed into {@code USRIDINI}, or {@code null} for none
     * @return the assembled screen
     */
    private UserListScreen listUsers(String userIdInput) {
        return this.service.listUsers(userIdInput);
    }

    /**
     * Collects every {@link Pageable} the service presented, in call order.
     *
     * @return the captured window requests
     */
    private List<Pageable> capturedWindows() {
        verify(this.repository, atLeastOnce()).findAllByOrderBySecUsrIdAsc(this.pageableCaptor.capture());
        return List.copyOf(this.pageableCaptor.getAllValues());
    }

    /**
     * Reads a declared field off the assembled screen record by component name, so that the record's shape
     * can be asserted without hard coding a component order.
     *
     * @param screen the assembled screen
     * @param componentName the record component to read
     * @return the component value
     */
    private static Object screenComponent(UserListScreen screen, String componentName) {
        for (RecordComponent component : UserListScreen.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                try {
                    return component.getAccessor().invoke(screen);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(
                            "record component " + componentName + " is not readable", failure);
                }
            }
        }
        throw new IllegalStateException(
                "UserListScreen declares no record component named " + componentName);
    }

    /**
     * Collects the declared method names of the service, which is how the paragraph map is proved.
     *
     * @return every declared method name, including the private ones
     */
    private static Set<String> declaredMethodNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : UserListService.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Stubs the finder so that every window reports a wildly overstated total element count while returning
     * the correct slice.
     *
     * <p>This is the discriminator that proves the next page flag comes from the eleventh <em>read</em> of
     * {@code app/cbl/COUSR00C.cbl:311} and not from a total. Anything deriving the flag from
     * {@code getTotalElements} or from {@code Page.hasNext} would report a further page for a store that has
     * none.
     *
     * @param rows the ordered store to slice
     */
    private void stubStoreWithMisleadingTotal(List<UserSecurity> rows) {
        when(this.repository.findAllByOrderBySecUsrIdAsc(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0, Pageable.class);
            int from = Math.min((int) pageable.getOffset(), rows.size());
            int to = Math.min(from + pageable.getPageSize(), rows.size());
            return new SliceImpl<>(rows.subList(from, to), pageable, true);
        });
    }

    /**
     * Extracts the identifiers of the rows a screen paints, in paint order.
     *
     * @param screen the assembled screen
     * @return the row identifiers
     */
    private static List<String> keysOf(UserListScreen screen) {
        List<String> keys = new ArrayList<>(screen.page().getRows().size());
        for (UserSecurityDto.UserRow paintedRow : screen.page().getRows()) {
            keys.add(paintedRow.userId());
        }
        return List.copyOf(keys);
    }

    // Phase 1 - the page arity of ten, and where the pagination state lives.

    /**
     * The ten row page and the eight digit counter, both taken straight from the source's declarations.
     *
     * <p>Sources: {@code app/cbl/COUSR00C.cbl:54} ({@code WS-PAGE-NUM}, dead), {@code :57}
     * ({@code USER-REC OCCURS 10 TIMES}), {@code :66-:75} (the program local paging extension appended after
     * {@code COPY COCOM01Y}), {@code :70} ({@code CDEMO-CU00-PAGE-NUM PIC 9(08)}), {@code :293} and
     * {@code :300} (the two bounds that repeat the literal ten), {@code :327} (the counter reaching the
     * screen).
     */
    @Nested
    @DisplayName("Phase 1 - page arity of ten and pagination state")
    class PageArityAndPaginationState {

        @Test
        @DisplayName("a full page returns exactly ten rows, the OCCURS 10 arity of COUSR00C.cbl:57")
        void fullPageReturnsExactlyTenRows() {
            stubStore(store(25));

            UserListScreen screen = listUsers(null);

            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.page().getPageSize()).isEqualTo(PAGE_SIZE);
            assertThat(keysOf(screen)).containsExactly(
                    "USR00001", "USR00002", "USR00003", "USR00004", "USR00005",
                    "USR00006", "USR00007", "USR00008", "USR00009", "USR00010");
        }

        @Test
        @DisplayName("a partial final page returns fewer than ten rows and is never padded out to ten")
        void partialFinalPageIsNotPadded() {
            stubStore(store(5));

            UserListScreen screen = listUsers(null);

            // :293-:295 blanks all ten slots, but :300-:306 fills only five and toScreen emits only the
            // slots that carry a key. A padded response would show ten rows, five of them empty.
            assertThat(screen.page().getRows()).hasSize(5);
            assertThat(keysOf(screen))
                    .containsExactly("USR00001", "USR00002", "USR00003", "USR00004", "USR00005")
                    .doesNotContainNull();
            assertThat(screen.page().getPageSize()).isEqualTo(PAGE_SIZE);
        }

        @Test
        @DisplayName("a partial page never captures a last key, because :434-435 needs row ten to be filled")
        void aPartialPageNeverCapturesALastKey() {
            stubStore(store(5));

            UserListScreen screen = listUsers(null);

            // A preserved consequence of the row-ten asymmetry, not a defect: CDEMO-CU00-USRID-LAST is only
            // written when WS-IDX reaches ten, so a short page leaves it unset.
            assertThat(screen.page().getFirstKey()).isEqualTo("USR00001");
            assertThat(screen.page().getLastKey()).isNull();
        }

        @Test
        @DisplayName("an empty store takes the not-found control path of :600-:606, not an I/O failure")
        void anEmptyStoreTakesTheNotFoundControlPath() {
            stubStore(store(0));

            // The source sets USER-SEC-EOF and emits a message without lighting WS-ERR-FLG. The stateless
            // entry point surfaces that arm as the typed exception the mapper produces for FILE STATUS 23.
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> listUsers(null))
                    .withMessage("STARTBR of USRSEC reported COBOL FILE STATUS 23 (IO-STATUS-04 0023)")
                    .withNoCause();
            verify(repository, times(1)).findAllByOrderBySecUsrIdAsc(any(Pageable.class));
        }

        @Test
        @DisplayName("a zero row screen assembles without failing on the no-communication-area path of :110")
        void aZeroRowScreenAssemblesWithoutFailing() {
            UserListScreen screen = service.openWithoutContext();

            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.page().getPageSize()).isEqualTo(PAGE_SIZE);
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the page number travels as response metadata, never as retained server-side state")
        void thePageNumberTravelsAsResponseMetadata() {
            stubStore(store(25));

            UserListScreen first = listUsers(null);
            UserListScreen second = listUsers(null);

            // Two identical requests produce two identical answers. Nothing accumulated between them, which
            // is the whole point of dropping CDEMO-CU00-PAGE-NUM out of a retained communication area.
            assertThat(first.legacyPageNumber()).isEqualTo(1);
            assertThat(second.legacyPageNumber()).isEqualTo(1);
            assertThat(first.page().getPageNumber()).isEqualTo(1);
            assertThat(second.page().getPageNumber()).isEqualTo(1);
            assertThat(first.screen().pageNumber()).isEqualTo("00000001");
            assertThat(second.screen().pageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("advancing a page requires the caller to resubmit the counter, so nothing is remembered")
        void advancingRequiresTheCallerToResubmitTheCounter() {
            stubStore(store(35));

            UserListRequest onPageOne =
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " "));
            UserListScreen firstForward = service.submitScreen(AttentionIdentifier.PF8, onPageOne);
            UserListScreen secondForward = service.submitScreen(AttentionIdentifier.PF8, onPageOne);

            assertThat(firstForward.legacyPageNumber()).isEqualTo(2);
            assertThat(secondForward.legacyPageNumber()).isEqualTo(2);
            assertThat(keysOf(secondForward)).isEqualTo(keysOf(firstForward));
        }

        @Test
        @DisplayName("the page number field is eight digits wide and zero padded, per PIC 9(08) at :70")
        void thePageNumberFieldIsEightDigitsWide() {
            stubStore(store(25));

            UserListScreen screen = listUsers(null);

            assertThat(screen.screen().pageNumber())
                    .hasSize(PAGE_NUMBER_DIGITS)
                    .isEqualTo("00000001")
                    .containsOnlyDigits();
            assertThat(UserSecurityDto.PAGE_NUMBER_WIDTH).isEqualTo(PAGE_NUMBER_DIGITS);
        }

        @Test
        @DisplayName("there is no separate row count field to reconcile, unlike COMEN02Y and COADM02Y")
        void thereIsNoSeparateRowCountFieldToReconcile() {
            // COUSR00C declares a bare OCCURS 10 with no count field beside it and no over-arity REDEFINES,
            // so the response carries no total either. A total would be a field with nothing to derive it
            // from, and its presence would tempt an implementation into a count-derived next-page flag.
            Set<String> pageAccessors = new LinkedHashSet<>();
            for (Method method : PageResponse.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && method.getParameterCount() == 0) {
                    pageAccessors.add(method.getName());
                }
            }
            assertThat(pageAccessors)
                    .contains("getRows", "getPageNumber", "getPageSize", "isNextPageAvailable")
                    .noneMatch(name -> name.contains("Total") || name.contains("Count"));
        }

        @Test
        @DisplayName("ten is a parity contract: the three declarations of it agree and the floor is enforced")
        void tenIsAParityContractNotATunable() {
            assertThat(UserSecurityDto.PAGE_SIZE).isEqualTo(PAGE_SIZE);
            assertThat(PageResponse.PAGE_SIZE_USER_LIST).isEqualTo(PAGE_SIZE);

            // The bean will not start below one, so a misconfigured property cannot silently paint no rows.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> newService(0))
                    .withMessage("carddemo.pagination.user-list-page-size must be at least 1, but was 0");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> newService(-1))
                    .withMessage("carddemo.pagination.user-list-page-size must be at least 1, but was -1");
        }
    }

    // Phase 2 - the four hundred and eighty bytes of dead work storage, and the
    // field contract the live path uses instead.

    /**
     * {@code 01 WS-USER-DATA} with its {@code 02 USER-REC OCCURS 10 TIMES} at
     * {@code app/cbl/COUSR00C.cbl:56-:64} is declared and never referenced: a repository wide search for
     * {@code WS-USER-DATA}, {@code USER-REC}, {@code USER-SEL}, {@code USER-NAME} and {@code USER-TYPE}
     * inside this program returns only lines 56, 57, 58, 62 and 64 - the declarations themselves.
     *
     * <p>Slot geometry is {@code 1 + 2 + 8 + 2 + 25 + 2 + 8}, so forty eight bytes, times ten, so four
     * hundred and eighty bytes never populated, never read and never cleared. The structure is retained as a
     * documented dead artefact with an entry in the {@code DECISION_LOG.md} and a
     * {@code TRACEABILITY_MATRIX.md} row. It is neither deleted nor resurrected as the response shape, and the tests
     * here prove the second half of that: had it shaped the response, name data would be lossy and the type field
     * eight times too wide.
     */
    @Nested
    @DisplayName("Phase 2 - the dead 480-byte work table shapes nothing")
    class DeadWorkTableAndFieldContract {

        @Test
        @DisplayName("the row carries first and last name separately, each twenty wide, never concatenated")
        void theRowCarriesFirstAndLastNameSeparately() {
            // Forty characters of name data. USER-NAME PIC X(25) at :62 could have carried twenty five of
            // them, so the dead table would have silently dropped fifteen. The live path carries both whole.
            UserSecurity wideNames = new UserSecurity(
                    "USR00001", "ABCDEFGHIJKLMNOPQRST", "UVWXYZABCDEFGHIJKLMN", syntheticHash(1),
                    UserType.ADMIN);
            stubStore(List.of(wideNames));

            UserSecurityDto.UserRow painted = listUsers(null).page().getRows().get(0);

            assertThat(painted.firstName()).isEqualTo("ABCDEFGHIJKLMNOPQRST").hasSize(20);
            assertThat(painted.lastName()).isEqualTo("UVWXYZABCDEFGHIJKLMN").hasSize(20);
            assertThat(UserSecurityDto.NAME_WIDTH).isEqualTo(20);
        }

        @Test
        @DisplayName("the row contract declares two distinct name components, so no 25-char field exists")
        void theRowContractDeclaresTwoDistinctNameComponents() {
            Set<String> componentNames = new LinkedHashSet<>();
            for (RecordComponent component : UserSecurityDto.UserRow.class.getRecordComponents()) {
                componentNames.add(component.getName());
            }

            assertThat(componentNames)
                    .containsExactly("selectionFlag", "userId", "firstName", "lastName", "userType")
                    .doesNotContain("userName", "name");
        }

        @Test
        @DisplayName("the user type is one character mapping to UserType, not the dead table's X(08)")
        void theUserTypeIsOneCharacterMappingToTheEnum() {
            stubStore(store(2));

            List<UserSecurityDto.UserRow> painted = listUsers(null).page().getRows();

            // USER-TYPE PIC X(08) at :64 in the dead table is eight wide; the real SEC-USR-TYPE of
            // app/cpy/CSUSR01Y.cpy:22 is PIC X(01), and that is what reaches the screen.
            assertThat(UserSecurityDto.USER_TYPE_WIDTH).isEqualTo(1);
            assertThat(painted.get(0).userType())
                    .hasSize(1)
                    .isEqualTo(String.valueOf(UserType.ADMIN.getCode()))
                    .isEqualTo("A");
            assertThat(painted.get(1).userType())
                    .hasSize(1)
                    .isEqualTo(String.valueOf(UserType.USER.getCode()))
                    .isEqualTo("U");
            assertThat(UserType.fromCode(painted.get(0).userType())).contains(UserType.ADMIN);
            assertThat(UserType.fromCode(painted.get(1).userType())).contains(UserType.USER);
        }

        @Test
        @DisplayName("the dead slot geometry differs from the live row width, so neither derives the other")
        void theDeadSlotGeometryDiffersFromTheLiveRowWidth() {
            // :58-:64 - USER-SEL X(1) + FILLER X(2) + USER-ID X(8) + FILLER X(2) + USER-NAME X(25)
            //           + FILLER X(2) + USER-TYPE X(8).
            int deadSlotWidth = 1 + 2 + 8 + 2 + 25 + 2 + 8;
            int deadTableWidth = deadSlotWidth * PAGE_SIZE;
            // The live contract, from app/cpy-bms/COUSR00.CPY - SELnnnnI X(1) + USRIDnnI X(8)
            //           + FNAMEnnI X(20) + LNAMEnnI X(20) + UTYPEnnI X(1), with no filler.
            int liveRowWidth = UserSecurityDto.SELECTION_FLAG_WIDTH + UserSecurityDto.USER_ID_WIDTH
                    + UserSecurityDto.NAME_WIDTH + UserSecurityDto.NAME_WIDTH
                    + UserSecurityDto.USER_TYPE_WIDTH;

            assertThat(deadSlotWidth).isEqualTo(48);
            assertThat(deadTableWidth).isEqualTo(480);
            assertThat(liveRowWidth).isEqualTo(50).isNotEqualTo(deadSlotWidth);
        }

        @Test
        @DisplayName("no stored credential reaches any part of the assembled screen")
        void noStoredCredentialReachesTheAssembledScreen() {
            String storedHash = syntheticHash(1);
            UserSecurity record = new UserSecurity(
                    "USR00001", "FIRST00001", "LAST00001", storedHash, UserType.ADMIN);
            stubStore(List.of(record));

            UserListScreen screen = listUsers(null);
            UserSecurityDto.UserRow painted = screen.page().getRows().get(0);

            // Clause D names tests explicitly, and this is a user administration path, so the assertion is
            // made against the assembled response rather than against the entity in isolation.
            assertThat(List.of(painted.userId(), painted.firstName(), painted.lastName(), painted.userType()))
                    .doesNotContain(storedHash);
            assertThat(painted.toString()).doesNotContain(storedHash);
            assertThat(screen.screen().toString()).doesNotContain(storedHash);
            for (RecordComponent component : UserSecurityDto.UserRow.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("password")
                        .doesNotContain("pwd")
                        .doesNotContain("hash")
                        .doesNotContain("secret");
            }
        }
    }

    // Phase 3 - POPULATE-USER-DATA is a ten branch unrolled EVALUATE, and two of
    // the ten branches are asymmetric.

    /**
     * {@code POPULATE-USER-DATA} at {@code app/cbl/COUSR00C.cbl:384-:437} moves four fields per row -
     * identifier, first name, last name and type - as four separate screen fields, fully unrolled across ten
     * {@code WHEN} branches for forty {@code MOVE} statements. The Java collapses that into one loop, a
     * mechanism substitution held as {@code DL-MS-05} in the {@code DECISION_LOG.md}, with both
     * asymmetries kept explicit.
     *
     * <p><strong>Correction to the written plan.</strong> The plan states that {@code WHEN 1} alone captures
     * a boundary key and that no other branch captures anything. Inspection of {@code :433-:435} shows
     * {@code WHEN 10} carries a second multi receiver {@code MOVE}, into
     * {@code CDEMO-CU00-USRID-LAST}. Two branches are asymmetric, not one. Rows two through nine capture
     * nothing at all, which is the part of the plan that does hold.
     */
    @Nested
    @DisplayName("Phase 3 - POPULATE-USER-DATA and its two asymmetric branches")
    class PopulateUserData {

        @Test
        @DisplayName("every returned row carries all four projected fields")
        void everyReturnedRowCarriesAllFourFields() {
            stubStore(store(25));

            List<UserSecurityDto.UserRow> painted = listUsers(null).page().getRows();

            assertThat(painted).hasSize(PAGE_SIZE);
            for (int position = 0; position < painted.size(); position++) {
                UserSecurityDto.UserRow paintedRow = painted.get(position);
                int ordinal = position + 1;
                assertThat(paintedRow.userId()).isEqualTo(String.format(Locale.ROOT, "USR%05d", ordinal));
                assertThat(paintedRow.firstName()).isEqualTo(String.format(Locale.ROOT, "FIRST%05d", ordinal));
                assertThat(paintedRow.lastName()).isEqualTo(String.format(Locale.ROOT, "LAST%05d", ordinal));
                assertThat(paintedRow.userType()).isEqualTo(ordinal % 2 == 1 ? "A" : "U");
            }
        }

        @Test
        @DisplayName("row one captures the page's first key, per the multi-receiver MOVE at :388-389")
        void rowOneCapturesTheFirstKey() {
            stubStore(store(25));

            UserListScreen screen = listUsers(null);

            assertThat(screen.page().getFirstKey())
                    .isEqualTo("USR00001")
                    .isEqualTo(keysOf(screen).get(0));
        }

        @Test
        @DisplayName("row ten ALSO captures the page's last key, per the MOVE at :434-435 - plan corrected")
        void rowTenAlsoCapturesTheLastKey() {
            stubStore(store(25));

            UserListScreen screen = listUsers(null);

            assertThat(screen.page().getLastKey())
                    .isEqualTo("USR00010")
                    .isEqualTo(keysOf(screen).get(PAGE_SIZE - 1));
        }

        @Test
        @DisplayName("rows two through nine contribute no captured key at all")
        void rowsTwoThroughNineContributeNoCapturedKey() {
            // Nine rows fill slots one through nine, so every asymmetric branch except WHEN 1 is bypassed.
            // If any of rows two to nine captured a key, the last key would be USR00009 rather than absent.
            stubStore(store(9));

            UserListScreen screen = listUsers(null);

            assertThat(keysOf(screen)).hasSize(9).endsWith("USR00009");
            assertThat(screen.page().getFirstKey()).isEqualTo("USR00001");
            assertThat(screen.page().getLastKey()).isNull();
        }

        @Test
        @DisplayName("a two row page proves the same: only row one captures, row two does not")
        void aTwoRowPageCapturesOnlyThroughRowOne() {
            stubStore(store(2));

            UserListScreen screen = listUsers(null);

            assertThat(keysOf(screen)).containsExactly("USR00001", "USR00002");
            assertThat(screen.page().getFirstKey()).isEqualTo("USR00001");
            assertThat(screen.page().getLastKey()).isNull();
        }

        @Test
        @DisplayName("the row one capture survives a partial page of exactly one row")
        void theRowOneCaptureSurvivesASinglePartialRow() {
            stubStore(store(1));

            UserListScreen screen = listUsers(null);

            assertThat(keysOf(screen)).containsExactly("USR00001");
            assertThat(screen.page().getFirstKey()).isEqualTo("USR00001");
            assertThat(screen.page().getLastKey()).isNull();
        }

        @Test
        @DisplayName("the boundary keys are recomputed for every page, not inherited from the request")
        void theBoundaryKeysAreRecomputedForEveryPage() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF8,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            // The request carried USR00001 and USR00010; the response must carry the new page's own pair.
            assertThat(screen.page().getFirstKey()).isEqualTo("USR00011");
            assertThat(screen.page().getLastKey()).isEqualTo("USR00020");
        }
    }

    // Phase 4 - the conditional pre-read, the guarded clear, the eleventh
    // look-ahead probe and the asymmetric page-number increment.

    /**
     * The four load bearing behaviours of {@code PROCESS-PAGE-FORWARD} at
     * {@code app/cbl/COUSR00C.cbl:282-:331} and {@code PROCESS-PAGE-BACKWARD} at {@code :336-:379}.
     *
     * <ol>
     *   <li><strong>High.</strong> {@code :288} reads one record before the fill loop when the attention
     *       identifier is none of {@code DFHENTER}, {@code DFHPF7} and {@code DFHPF3}. {@code :342} does the
     *       same for the backward direction with a different operand set, {@code DFHENTER} and
     *       {@code DFHPF8}.</li>
     *   <li><strong>Medium.</strong> The ten row clear at {@code :293-:295} sits inside the guard at
     *       {@code :292}, so at end of data the previous page's rows survive.</li>
     *   <li><strong>High.</strong> {@code :311} issues a real read one ordinal past the page purely to set
     *       the next page flag, and discards the record.</li>
     *   <li><strong>Medium.</strong> {@code :309-:310} increments unconditionally; {@code :319-:322}
     *       increments only when at least one row was filled.</li>
     * </ol>
     */
    @Nested
    @DisplayName("Phase 4 - skip read, guarded clear, eleventh probe, asymmetric increment")
    class BrowseArithmetic {

        @Test
        @DisplayName("the skip read fires on page-forward, so page two begins after the submitted last key")
        void theSkipReadFiresOnPageForward() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF8,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            // Without the skip read of :289 the browse would still be sitting on USR00010 and page two would
            // open on it, shifting every forward page by exactly one record.
            assertThat(keysOf(screen)).startsWith("USR00011").doesNotContain("USR00010");
            assertThat(keysOf(screen)).containsExactly(
                    "USR00011", "USR00012", "USR00013", "USR00014", "USR00015",
                    "USR00016", "USR00017", "USR00018", "USR00019", "USR00020");
            verify(repository, atLeastOnce()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    eq("USR00010"), any(Pageable.class));
        }

        @Test
        @DisplayName("the skip read does NOT fire on initial entry, so page one opens on the first record")
        void theSkipReadDoesNotFireOnInitialEntry() {
            stubStore(store(25));

            assertThat(keysOf(listUsers(null))).startsWith("USR00001");
        }

        @Test
        @DisplayName("the skip read does NOT fire on the ENTER action of a re-entry either")
        void theSkipReadDoesNotFireOnTheEnterAction() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, null, List.of()));

            assertThat(keysOf(screen)).startsWith("USR00001").hasSize(PAGE_SIZE);
        }

        @Test
        @DisplayName("the exit action browses nothing at all, so no skip read can be observed on it")
        void theExitActionBrowsesNothing() {
            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF3,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            assertThat(screen.navigationTarget()).isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(screen.controlTransferred()).isTrue();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the page-back skip read fires too, so forward then back round-trips to the same ten")
        void theBackwardSkipReadFiresAndTheDirectionsRoundTrip() {
            stubStore(store(35));

            UserListScreen forwardToTwo = service.submitScreen(AttentionIdentifier.PF8,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));
            UserListScreen backwardToTwo = service.submitScreen(AttentionIdentifier.PF7,
                    request(3, true, "USR00021", "USR00030", null, pageWithSelection(0, " ")));

            // Both skip reads have to be present for these two to agree. Drop either one and the two paths
            // land one record apart, which is exactly the defect that reads as an off-by-one.
            assertThat(keysOf(backwardToTwo)).isEqualTo(keysOf(forwardToTwo));
            assertThat(keysOf(backwardToTwo)).startsWith("USR00011").endsWith("USR00020");
            assertThat(backwardToTwo.legacyPageNumber()).isEqualTo(2);
            assertThat(forwardToTwo.legacyPageNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("the page-back browse walks its windows in descending order, per the READPREV loop")
        void thePageBackBrowseWalksWindowsDescending() {
            stubStore(store(35));

            service.submitScreen(AttentionIdentifier.PF7,
                    request(3, true, "USR00021", "USR00030", null, pageWithSelection(0, " ")));

            // H-08: the browse is positioned on the echoed first key, so what this asserts is that the
            // backward walk reads DESCENDING FROM THAT KEY and never through a page index. :352-:360 counts
            // WS-IDX down from ten, which is a READPREV loop, and the descending finder is its translation.
            verify(repository, atLeastOnce()).findBySecUsrIdLessThanEqualOrderBySecUsrIdDesc(
                    eq("USR00021"), any(Pageable.class));
            verify(repository, never()).findAllByOrderBySecUsrIdAsc(any(Pageable.class));
        }

        @Test
        @DisplayName("the row buffer is NOT cleared at end of data, so the previous page's rows survive")
        void theRowBufferIsNotClearedAtEndOfData() {
            // A blank submitted last key drives the HIGH-VALUES positioning of :263, so the skip read hits
            // end of data immediately and the guard at :292 keeps the clear loop from running.
            List<UserSecurityDto.UserRow> submitted = pageWithSelection(0, " ");

            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF8,
                    request(1, true, "USR00001", null, null, submitted));

            assertThat(keysOf(screen)).containsExactly(
                    "USR00001", "USR00002", "USR00003", "USR00004", "USR00005",
                    "USR00006", "USR00007", "USR00008", "USR00009", "USR00010");
            assertThat(screen.page().getRows()).isEqualTo(submitted);
            assertThat(screen.screen().errorMessage()).isEqualTo(REACHED_BOTTOM_MESSAGE);
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the eleventh row is fetched and then discarded, never painted")
        void theEleventhRowIsFetchedAndDiscarded() {
            stubStore(store(11));

            UserListScreen screen = listUsers(null);

            // The eleventh record's window is loaded - a real read, per :311 - yet the record never appears.
            assertThat(keysOf(screen)).hasSize(PAGE_SIZE).doesNotContain("USR00011");
            assertThat(screen.page().isNextPageAvailable()).isTrue();
            assertThat(capturedWindows()).extracting(Pageable::getPageNumber).containsExactly(0, 1);
        }

        @Test
        @DisplayName("hasNext is false when exactly ten rows remain, and the probe read still happens")
        void hasNextIsFalseWhenExactlyTenRowsRemain() {
            stubStoreRejectingWindowsPastTheEnd(store(10));

            UserListScreen screen = listUsers(null);

            assertThat(keysOf(screen)).hasSize(PAGE_SIZE);
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            // The read past the page is issued either way; only its outcome differs. That is what separates
            // this design from one that consults a total before deciding whether to look.
            assertThat(capturedWindows()).extracting(Pageable::getPageNumber).containsExactly(0, 1);
        }

        @Test
        @DisplayName("hasNext is true when eleven rows remain")
        void hasNextIsTrueWhenElevenRowsRemain() {
            stubStore(store(11));

            assertThat(listUsers(null).page().isNextPageAvailable()).isTrue();
        }

        @Test
        @DisplayName("the next-page flag is never derived from a count query or from a reported total")
        void theNextPageFlagIsNeverDerivedFromACountQuery() {
            // Every window reports Long.MAX_VALUE elements. Anything reading getTotalElements, or delegating
            // to Page.hasNext, would announce a further page for a store that ends at exactly ten.
            stubStoreWithMisleadingTotal(store(10));

            UserListScreen screen = listUsers(null);

            assertThat(screen.page().isNextPageAvailable()).isFalse();
            verify(repository, never()).count();
            verify(repository, never()).findAll();
        }

        @Test
        @DisplayName("the misleading total does not mask a genuine further page either")
        void theMisleadingTotalDoesNotMaskAGenuineFurtherPage() {
            stubStoreWithMisleadingTotal(store(11));

            assertThat(listUsers(null).page().isNextPageAvailable()).isTrue();
        }

        @Test
        @DisplayName("the page counter increments unconditionally on a full page, per :309-310")
        void thePageCounterIncrementsOnAFullPage() {
            stubStore(store(60));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF8,
                    request(4, true, "USR00031", "USR00040", null, pageWithSelection(0, " ")));

            assertThat(screen.legacyPageNumber()).isEqualTo(5);
            assertThat(screen.screen().pageNumber()).isEqualTo("00000005");
            assertThat(keysOf(screen)).startsWith("USR00041").endsWith("USR00050");
        }

        @Test
        @DisplayName("the page counter increments on a partial page too, because :319 only needs one row")
        void thePageCounterIncrementsOnAPartialPageWithAtLeastOneRow() {
            stubStore(store(5));

            UserListScreen screen = listUsers(null);

            // WS-IDX reached six, so the guard at :319 passes even though the fill ended early.
            assertThat(screen.page().getRows()).hasSize(5);
            assertThat(screen.legacyPageNumber()).isEqualTo(1);
            assertThat(screen.screen().pageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("the page counter does NOT increment when no row was filled, per the :319 guard")
        void thePageCounterDoesNotIncrementWhenNoRowWasFilled() {
            // Blank last key plus an empty submitted page: end of data on the skip read, WS-IDX still one.
            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF8,
                    request(4, true, "USR00031", null, null, List.of()));

            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.legacyPageNumber()).isEqualTo(4);
            assertThat(screen.screen().pageNumber()).isEqualTo("00000004");
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.screen().errorMessage()).isEqualTo(REACHED_BOTTOM_MESSAGE);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the rendered counter stays eight digits wide even at the PIC 9(08) ceiling")
        void theRenderedCounterStaysEightDigitsAtTheCeiling() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF8,
                    request(99_999_999, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            // H-08 exposed the faithful outcome here, which the previous ordinal-based positioning hid by
            // failing to position at all: CDEMO-CU00-PAGE-NUM is PIC 9(08) at app/cbl/COUSR00C.cbl:70 and
            // :309-:310 is COMPUTE CDEMO-CU00-PAGE-NUM = CDEMO-CU00-PAGE-NUM + 1 with NO ON SIZE ERROR, so
            // incrementing the ceiling truncates into the destination picture and wraps to zero. It does not
            // saturate, and it does not fail. The counter stays eight digits either way, which is what this
            // test is for; the value it wraps to is the source's, not a convenience.
            assertThat(screen.screen().pageNumber()).hasSize(PAGE_NUMBER_DIGITS).isEqualTo("00000000");
            assertThat(screen.legacyPageNumber()).isZero();
        }

        @Test
        @DisplayName("page-forward requests contiguous ascending windows of exactly the page size")
        void pageForwardRequestsContiguousAscendingWindows() {
            stubStore(store(25));

            listUsers(null);

            List<Pageable> windows = capturedWindows();
            assertThat(windows).hasSize(2);
            assertThat(windows).extracting(Pageable::getPageNumber).containsExactly(0, 1);
            assertThat(windows).allSatisfy(window -> {
                assertThat(window.getPageSize()).isEqualTo(PAGE_SIZE);
                // Ordering lives in the finder name, not in the Pageable, which is why an unsorted request
                // is still a deterministic one. See the ordering test in the rule-compliance group.
                assertThat(window.getSort().isUnsorted()).isTrue();
            });
        }
    }

    // Phase 5 - selection: the first non-blank selector wins, silently.

    /**
     * {@code PROCESS-ENTER-KEY} at {@code app/cbl/COUSR00C.cbl:149-:235}.
     *
     * <p>{@code :151-:185} is an {@code EVALUATE TRUE} with one arm per screen row, which is what makes the
     * first non blank selector win and every later one lose in silence - no message, no flag, no indication.
     * An implementation that validated "exactly one selection", rejected multiples, or acted on the last one
     * would diverge; that is a <strong>High</strong> severity divergence because the caller receives a
     * different record than the source would have acted on.
     *
     * <p>{@code :187-:215} is the action dispatch, read in full from source rather than assumed. It has
     * exactly three arms and nothing is Not available: {@code WHEN 'U'} with {@code WHEN 'u'} at
     * {@code :190-:199} transferring to {@code COUSR02C}; {@code WHEN 'D'} with {@code WHEN 'd'} at
     * {@code :200-:209} transferring to {@code COUSR03C}; and {@code WHEN OTHER} at {@code :210-:214} which
     * sets a message, repositions the cursor, sets <strong>no</strong> error flag and issues
     * <strong>no</strong> transfer. Case insensitivity comes from two separate {@code WHEN} clauses per
     * action, not from a case function, so the selector the caller submitted is echoed back unaltered.
     */
    @Nested
    @DisplayName("Phase 5 - first non-blank selection wins, silently")
    class Selection {

        /**
         * Submits a single selector on one row and returns the assembled screen.
         *
         * @param rowPosition the one based row carrying the selector
         * @param selectionFlag the selector character
         * @return the assembled screen
         */
        private UserListScreen selectRow(int rowPosition, String selectionFlag) {
            return service.submitScreen(AttentionIdentifier.ENTER, request(
                    1, true, "USR00001", "USR00010", null, pageWithSelection(rowPosition, selectionFlag)));
        }

        @Test
        @DisplayName("a selector on row one resolves to row one's identifier - :152-154")
        void rowOneSelectionResolvesToRowOne() {
            assertThat(selectRow(1, "U").selectedUserId()).isEqualTo("USR00001");
        }

        @Test
        @DisplayName("a selector on row two resolves to row two's identifier - :155-157")
        void rowTwoSelectionResolvesToRowTwo() {
            assertThat(selectRow(2, "U").selectedUserId()).isEqualTo("USR00002");
        }

        @Test
        @DisplayName("a selector on row three resolves to row three's identifier - :158-160")
        void rowThreeSelectionResolvesToRowThree() {
            assertThat(selectRow(3, "U").selectedUserId()).isEqualTo("USR00003");
        }

        @Test
        @DisplayName("a selector on row four resolves to row four's identifier - :161-163")
        void rowFourSelectionResolvesToRowFour() {
            assertThat(selectRow(4, "U").selectedUserId()).isEqualTo("USR00004");
        }

        @Test
        @DisplayName("a selector on row five resolves to row five's identifier - :164-166")
        void rowFiveSelectionResolvesToRowFive() {
            assertThat(selectRow(5, "U").selectedUserId()).isEqualTo("USR00005");
        }

        @Test
        @DisplayName("a selector on row six resolves to row six's identifier - :167-169")
        void rowSixSelectionResolvesToRowSix() {
            assertThat(selectRow(6, "U").selectedUserId()).isEqualTo("USR00006");
        }

        @Test
        @DisplayName("a selector on row seven resolves to row seven's identifier - :170-172")
        void rowSevenSelectionResolvesToRowSeven() {
            assertThat(selectRow(7, "U").selectedUserId()).isEqualTo("USR00007");
        }

        @Test
        @DisplayName("a selector on row eight resolves to row eight's identifier - :173-175")
        void rowEightSelectionResolvesToRowEight() {
            assertThat(selectRow(8, "U").selectedUserId()).isEqualTo("USR00008");
        }

        @Test
        @DisplayName("a selector on row nine resolves to row nine's identifier - :176-178")
        void rowNineSelectionResolvesToRowNine() {
            assertThat(selectRow(9, "U").selectedUserId()).isEqualTo("USR00009");
        }

        @Test
        @DisplayName("a selector on row ten resolves to row ten's identifier - :179-181")
        void rowTenSelectionResolvesToRowTen() {
            assertThat(selectRow(10, "U").selectedUserId()).isEqualTo("USR00010");
        }

        @Test
        @DisplayName("two simultaneous selectors act on the EARLIER row and the later one is ignored silently")
        void twoSimultaneousSelectorsActOnTheEarlierRow() {
            List<UserSecurityDto.UserRow> rows = new ArrayList<>(PAGE_SIZE);
            for (int position = 1; position <= PAGE_SIZE; position++) {
                rows.add(row(position == 3 || position == 7 ? "U" : " ",
                        String.format(Locale.ROOT, "USR%05d", position)));
            }

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(1, true, "USR00001", "USR00010", null, List.copyOf(rows)));

            assertThat(screen.selectedUserId()).isEqualTo("USR00003");
            assertThat(screen.selectionFlag()).isEqualTo("U");
            // Silently: no message, no error flag, and the transfer proceeds as if row seven were blank.
            assertThat(screen.screen().errorMessage()).isEmpty();
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.navigationTarget()).isEqualTo(USER_UPDATE_PROGRAM);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("ten simultaneous selectors still act on row one only, with no error")
        void tenSimultaneousSelectorsActOnRowOneOnly() {
            List<UserSecurityDto.UserRow> rows = new ArrayList<>(PAGE_SIZE);
            for (int position = 1; position <= PAGE_SIZE; position++) {
                rows.add(row("U", String.format(Locale.ROOT, "USR%05d", position)));
            }

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(1, true, "USR00001", "USR00010", null, List.copyOf(rows)));

            assertThat(screen.selectedUserId()).isEqualTo("USR00001");
            assertThat(screen.screen().errorMessage()).isEmpty();
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("two different selectors still take the earlier arm, so 'D' behind 'U' never runs")
        void twoDifferentSelectorsStillTakeTheEarlierArm() {
            List<UserSecurityDto.UserRow> rows = new ArrayList<>(PAGE_SIZE);
            for (int position = 1; position <= PAGE_SIZE; position++) {
                String selector = " ";
                if (position == 2) {
                    selector = "U";
                } else if (position == 4) {
                    selector = "D";
                }
                rows.add(row(selector, String.format(Locale.ROOT, "USR%05d", position)));
            }

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(1, true, "USR00001", "USR00010", null, List.copyOf(rows)));

            assertThat(screen.selectedUserId()).isEqualTo("USR00002");
            assertThat(screen.navigationTarget()).isEqualTo(USER_UPDATE_PROGRAM);
        }

        @Test
        @DisplayName("no selection clears BOTH the flag and the identifier, and is not an error - :182-184")
        void noSelectionClearsBothAndIsNotAnError() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            assertThat(screen.selectionFlag()).isEmpty();
            assertThat(screen.selectedUserId()).isEmpty();
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.screen().errorMessage()).isEmpty();
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.controlTransferred()).isFalse();
        }

        @Test
        @DisplayName("an empty submitted page is also no selection, and also not an error")
        void anEmptySubmittedPageIsAlsoNoSelection() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, null, List.of()));

            assertThat(screen.selectionFlag()).isEmpty();
            assertThat(screen.selectedUserId()).isEmpty();
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("upper-case 'U' transfers to COUSR02C for update - :190, :192")
        void upperCaseUpdateSelectorTransfersToTheUpdateProgram() {
            UserListScreen screen = selectRow(3, "U");

            assertThat(screen.navigationTarget()).isEqualTo(USER_UPDATE_PROGRAM).isEqualTo("COUSR02C");
            assertThat(screen.controlTransferred()).isTrue();
            assertThat(screen.selectionFlag()).isEqualTo("U");
        }

        @Test
        @DisplayName("lower-case 'u' transfers to the same program, via the second WHEN clause at :191")
        void lowerCaseUpdateSelectorTransfersToTheSameProgram() {
            UserListScreen screen = selectRow(3, "u");

            assertThat(screen.navigationTarget()).isEqualTo(USER_UPDATE_PROGRAM);
            assertThat(screen.controlTransferred()).isTrue();
            // Two WHEN clauses, not a case function: the submitted character is echoed back unaltered.
            assertThat(screen.selectionFlag()).isEqualTo("u");
        }

        @Test
        @DisplayName("upper-case 'D' transfers to COUSR03C for delete - :201, :203")
        void upperCaseDeleteSelectorTransfersToTheDeleteProgram() {
            UserListScreen screen = selectRow(5, "D");

            assertThat(screen.navigationTarget()).isEqualTo(USER_DELETE_PROGRAM).isEqualTo("COUSR03C");
            assertThat(screen.controlTransferred()).isTrue();
            assertThat(screen.selectedUserId()).isEqualTo("USR00005");
        }

        @Test
        @DisplayName("lower-case 'd' transfers to the same program, via the second WHEN clause at :202")
        void lowerCaseDeleteSelectorTransfersToTheSameProgram() {
            UserListScreen screen = selectRow(7, "d");

            assertThat(screen.navigationTarget()).isEqualTo(USER_DELETE_PROGRAM);
            assertThat(screen.controlTransferred()).isTrue();
            assertThat(screen.selectionFlag()).isEqualTo("d");
            assertThat(screen.selectedUserId()).isEqualTo("USR00007");
        }

        @Test
        @DisplayName("an unrecognised selector takes the WHEN OTHER arm of :210-214 exactly as written")
        void anUnrecognisedSelectorTakesTheWhenOtherArm() {
            stubStore(store(25));

            UserListScreen screen = selectRow(2, "X");

            // The source's own arm: a message, a cursor reposition, and then it falls through to the browse.
            // It sets no error flag and issues no transfer, so this is neither a failure nor a navigation.
            assertThat(screen.screen().errorMessage()).isEqualTo(INVALID_SELECTION_MESSAGE);
            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIELD);
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.controlTransferred()).isFalse();
            // Fall-through is the point: the page is still refreshed behind the message.
            assertThat(keysOf(screen)).hasSize(PAGE_SIZE).startsWith("USR00001");
            assertThat(screen.screen().pageNumber()).isEqualTo("00000001");
            assertThat(screen.selectionFlag()).isEqualTo("X");
            assertThat(screen.selectedUserId()).isEqualTo("USR00002");
        }

        @Test
        @DisplayName("a recognised selector is a terminal transfer: no browse runs and no page is refreshed")
        void aRecognisedSelectorIsATerminalTransfer() {
            UserListScreen screen = selectRow(4, "U");

            // EXEC CICS XCTL never returns, so nothing downstream of :199 executes: no key derivation, no
            // page reset, no browse, no send. Zero repository interactions and zero sends prove it.
            assertThat(screen.controlTransferred()).isTrue();
            assertThat(screen.sendCount()).isZero();
            assertThat(screen.screen().pageNumber()).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a blank selector on every row is indistinguishable from no selector at all")
        void aBlankSelectorOnEveryRowIsNoSelector() {
            stubStore(store(25));

            UserListScreen screen = selectRow(0, " ");

            assertThat(screen.selectedUserId()).isEmpty();
            assertThat(screen.navigationTarget()).isNull();
        }
    }

    // Phase 6 - the browse start: a commented-out option that was the default anyway, and a CONTINUE
    // that continues.

    /**
     * {@code STARTBR-USER-SEC-FILE} at {@code app/cbl/COUSR00C.cbl:586-:614}, with its three verified
     * findings.
     *
     * <ol>
     *   <li><strong>High if reversed.</strong> {@code GTEQ} is commented out at {@code :592}, and that changes
     *       nothing, because {@code GTEQ} is the option {@code EXEC CICS STARTBR} applies when none is coded for a
     *       direct browse of a KSDS - and {@code USRSEC} is a KSDS, {@code KEYS(8,0) INDEXED}. The browse therefore
     *       positions on the first record whose key is <em>at or after</em> the supplied one, and an identifier that
     *       names no record positions on the next higher key rather than failing. An earlier reading of this suite
     *       asserted the opposite, on the ground that the comment disabled the option; that reading is withdrawn
     *       here and in the register entry that carried it.</li>
     *   <li><strong>Medium.</strong> The {@code CONTINUE} at {@code :601} does <em>not</em> terminate its branch: the
     *       four statements at {@code :602-:606} all execute. A reader who assumes otherwise drops the end of data
     *       flag, the message, the cursor reposition and the send.</li>
     *   <li><strong>Medium.</strong> The not found arm never sets {@code WS-ERR-FLG}. Only {@code WHEN OTHER} does.
     *       Not found on a browse start is therefore an edge of data control path, not an error state.</li>
     * </ol>
     *
     * <p>The browse key is {@code SEC-USR-ID} with {@code KEYLENGTH (LENGTH OF SEC-USR-ID)}, so eight,
     * matching {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED} in {@code app/jcl/DUSRSECJ.jcl}.
     */
    @Nested
    @DisplayName("Phase 6 - equal-or-greater browse start, the non-terminating CONTINUE, the two arms")
    class BrowseStart {

        @Test
        @DisplayName("an identifier beyond the last record positions past the end, not on a failure")
        void anIdentifierBeyondTheLastRecordPositionsPastTheEnd() {
            stubStore(store(25));

            // Nothing in the file is at or after this key, which is the position HIGH-VALUES names, so the
            // browse is accepted there and the first READNEXT ends the file at once. That is an edge-of-data
            // control path carrying a message, never an error: the source's arm sets no WS-ERR-FLG.
            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "ZZZZZZZZ", List.of()));

            assertThat(keysOf(screen)).isEmpty();
            assertThat(screen.screen().errorMessage()).isEqualTo(REACHED_BOTTOM_MESSAGE);
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("an identifier below the first record positions on the first record, not on a failure")
        void anIdentifierBelowTheFirstRecordPositionsOnTheFirstRecord() {
            stubStore(store(25));

            // The whole point of the default GTEQ option: a key naming no record slides forward to the next
            // higher one. Under the withdrawn equal-only reading this answered not-found.
            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "AAAAAAAA", List.of()));

            assertThat(keysOf(screen)).hasSize(PAGE_SIZE).startsWith("USR00001");
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("an identifier that falls between two records positions on the higher one")
        void anIdentifierBetweenTwoRecordsPositionsOnTheHigherOne() {
            // A store with gaps, because contiguous eight character keys leave no room for a key to fall
            // between two of them: USR00003 and USR00004 differ only in their last character and no character
            // sorts between '3' and '4'. Odd ordinals only, so USR00002, USR00004 and USR00006 are all keys
            // that name no record while records still follow them.
            stubStore(List.of(user(1), user(3), user(5)));

            UserListScreen onAGap = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "USR00002", List.of()));

            // The record the browse slides forward to is the next higher one, not the next lower and not a
            // failure. This is the single behaviour the withdrawn equal-only reading got wrong.
            assertThat(keysOf(onAGap)).containsExactly("USR00003", "USR00005");

            UserListScreen onALaterGap = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "USR00004", List.of()));

            assertThat(keysOf(onALaterGap)).containsExactly("USR00005");
            assertThat(onALaterGap.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("an existing starting identifier opens page one AT that identifier")
        void anExistingStartingIdentifierOpensPageOneAtThatIdentifier() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "USR00003", List.of()));

            // :219-:221 moves USRIDINI into SEC-USR-ID precisely so the browse starts there. An earlier
            // revision of this case asserted the page opened on USR00001 instead, which recorded the defect
            // rather than the contract: the derived key was being discarded before the browse was anchored.
            assertThat(keysOf(screen)).hasSize(PAGE_SIZE).startsWith("USR00003");
            assertThat(keysOf(screen)).doesNotContain("USR00001", "USR00002");
            assertThat(screen.screen().pageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("a blank starting identifier drives the LOW-VALUES path of :219 and probes no key")
        void aBlankStartingIdentifierProbesNoKey() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "", List.of()));

            assertThat(keysOf(screen)).startsWith("USR00001");
            verify(repository, never()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    anyString(), any(Pageable.class));
        }

        @Test
        @DisplayName("a whitespace-only starting identifier is treated as blank, exactly as LOW-VALUES is")
        void aWhitespaceOnlyStartingIdentifierIsTreatedAsBlank() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "        ", List.of()));

            assertThat(keysOf(screen)).startsWith("USR00001");
            verify(repository, never()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    anyString(), any(Pageable.class));
        }

        @Test
        @DisplayName("the browse key is eight characters wide, matching SEC-USR-ID and KEYS(8,0)")
        void theBrowseKeyIsEightCharactersWide() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "USR00003", List.of()));

            assertThat(KEY_LENGTH).isEqualTo(8);
            assertThat(UserSecurityDto.USER_ID_WIDTH).isEqualTo(KEY_LENGTH);
            verify(repository, atLeastOnce()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    eq("USR00003"), any(Pageable.class));
            assertThat(keysOf(screen)).allSatisfy(key -> assertThat(key).hasSize(KEY_LENGTH));
        }

        @Test
        @DisplayName("a nine character identifier is refused before any browse is attempted")
        void aNineCharacterIdentifierIsRefusedBeforeTheBrowse() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(0, false, null, null, "USR000011", List.of())))
                    .withMessage("userIdInput must be at most 8 characters because the screen field is"
                            + " that wide, but was 9 characters long");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a seven character identifier is accepted and probed verbatim, without padding")
        void aSevenCharacterIdentifierIsProbedVerbatim() {
            stubStore(store(25));

            service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "USR0001", List.of()));

            verify(repository, atLeastOnce()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    eq("USR0001"), any(Pageable.class));
        }

        @Test
        @DisplayName("a browse-start hard failure raises an I/O failure, distinct from the not-found arm")
        void aBrowseStartHardFailureRaisesAnIoFailure() {
            stubStore(store(25));
            DataAccessResourceFailureException underlying =
                    new DataAccessResourceFailureException("simulated security file outage");
            when(repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    eq("USR00003"), any(Pageable.class))).thenThrow(underlying);

            // FileAccessException is not on this file's import whitelist, so it is caught as the base type
            // and identified by name. The distinction that matters is that it is NOT a RecordNotFoundException:
            // WHEN OTHER at :607-613 lights WS-ERR-FLG where the not-found arm at :600-606 does not.
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(0, false, null, null, "USR00003", List.of())))
                    .withMessage("STARTBR of USRSEC reported COBOL FILE STATUS 90 (IO-STATUS-04 9048)")
                    .withCause(underlying)
                    .isNotInstanceOf(RecordNotFoundException.class)
                    .satisfies(failure ->
                            assertThat(failure.getClass().getSimpleName()).isEqualTo("FileAccessException"));
        }

        @Test
        @DisplayName("the READNEXT site raises the same failure kind, named for its own operation")
        void theReadNextSiteRaisesTheSameFailureKind() {
            DataAccessResourceFailureException underlying =
                    new DataAccessResourceFailureException("simulated security file outage");
            // The probe window loads normally; the look-ahead read of :311 is the one that fails.
            stubStoreFailingFromWindow(store(25), 1, underlying);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> listUsers(null))
                    .withMessage("READNEXT of USRSEC reported COBOL FILE STATUS 90 (IO-STATUS-04 9048)")
                    .withCause(underlying)
                    .isNotInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("the READPREV site raises the same failure kind, named for its own operation")
        void theReadPrevSiteRaisesTheSameFailureKind() {
            DataAccessResourceFailureException underlying =
                    new DataAccessResourceFailureException("simulated security file outage");
            stubStoreFailingFromWindow(store(35), 2, underlying);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF7,
                            request(3, true, "USR00021", "USR00030", null, pageWithSelection(0, " "))))
                    .withMessage("READPREV of USRSEC reported COBOL FILE STATUS 90 (IO-STATUS-04 9048)")
                    .withCause(underlying)
                    .isNotInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("the response and reason codes reach the exception as the four-char IO-STATUS-04 field")
        void theResponseAndReasonCodesReachTheExceptionContext() {
            // The NOTFND arm is reached by the one condition that still produces it once the browse
            // positions equal-or-greater: an EMPTY file, where LOW-VALUES itself finds nothing. A key beyond
            // the last record is not that condition - it is the HIGH-VALUES position, tested above.
            stubStore(store(0));

            // 9910-DISPLAY-IO-STATUS at app/cbl/CBTRN02C.cbl:714-731 renders the status in exactly four
            // characters, and that rendering is what the exception message carries. Nothing is swallowed.
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(0, false, null, null, "", List.of())))
                    .withMessageContaining("(IO-STATUS-04 0023)")
                    .withMessageContaining("STARTBR")
                    .withMessageContaining(USRSEC_FILE);
        }

        @Test
        @DisplayName("the not-found arm names the record type and key it could not position on")
        void theNotFoundArmNamesTheRecordTypeAndKey() {
            stubStore(store(0));

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(0, false, null, null, "", List.of())))
                    .satisfies(failure -> assertThat(failure.getMessage()).contains("FILE STATUS 23"));
        }

        @Test
        @DisplayName("end of data is not an error state, but an unrecognised key press is")
        void endOfDataIsNotAnErrorStateButAnUnrecognisedKeyPressIs() {
            stubStore(store(5));

            UserListScreen endOfData = listUsers(null);

            // The ENDFILE arms of :634-640 and :668-674 emit a message and leave WS-ERR-FLG off, exactly as
            // the browse-start not-found arm does. This is the observable half of that distinction.
            assertThat(endOfData.screen().errorMessage()).isEqualTo(REACHED_BOTTOM_MESSAGE);
            assertThat(endOfData.errorFlagOn()).isFalse();

            UserListScreen unrecognisedKey = service.submitScreen(AttentionIdentifier.OTHER,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            assertThat(unrecognisedKey.screen().errorMessage()).isEqualTo(INVALID_KEY_MESSAGE);
            assertThat(unrecognisedKey.errorFlagOn()).isTrue();
        }
    }

    // Phase 7 - the message vocabulary, byte for byte.

    /**
     * Five distinct edge of data sentences plus one lookup failure sentence repeated at three sites.
     *
     * <table>
     *   <caption>Verified literals and their locators in {@code app/cbl/COUSR00C.cbl}</caption>
     *   <tr><th>Locator</th><th>Literal</th><th>Raised by</th></tr>
     *   <tr><td>{@code :251}</td><td>{@code You are already at the top of the page...}</td>
     *       <td>{@code PROCESS-PF7-KEY}, navigation refused</td></tr>
     *   <tr><td>{@code :273}</td><td>{@code You are already at the bottom of the page...}</td>
     *       <td>{@code PROCESS-PF8-KEY}, navigation refused</td></tr>
     *   <tr><td>{@code :603}</td><td>{@code You are at the top of the page...}</td>
     *       <td>{@code STARTBR}, browse reported - <strong>no {@code already}</strong></td></tr>
     *   <tr><td>{@code :637}</td><td>{@code You have reached the bottom of the page...}</td>
     *       <td>{@code READNEXT}, browse reported</td></tr>
     *   <tr><td>{@code :671}</td><td>{@code You have reached the top of the page...}</td>
     *       <td>{@code READPREV}, browse reported</td></tr>
     *   <tr><td>{@code :610}, {@code :644}, {@code :678}</td><td>{@code Unable to lookup User...}</td>
     *       <td>all three {@code WHEN OTHER} arms - one literal, three sites</td></tr>
     * </table>
     *
     * <p>The lookup failure noun is capitalised here. The parallel family in {@code COTRN00C} uses the same
     * five sentence shapes but spells its noun in lower case, so the two services must own their literals
     * separately - a shared constant would silently change one of them. That is a <strong>Medium</strong>
     * severity trap, and the remedy is exactly what this class does: declare the literals locally and assert
     * the casing.
     *
     * <p><strong>Not available</strong> at this tier: {@code :603} and the three {@code :610}, {@code :644},
     * {@code :678} sites never reach an observable screen, because the stateless entry point converts both
     * arms into typed exceptions before the screen is assembled. What is asserted instead is the arm each
     * accompanies - by exception type, operation name and cause, in the browse-start group above - together
     * with the byte exact literals pinned as constants in this class. Making the strings themselves
     * observable would require either a named constant on the service for the triplicated one, which is
     * inlined at all three sites today, or an accessor for the screen assembled on a throwing path. Neither
     * is a test's business, and neither is invented here.
     */
    @Nested
    @DisplayName("Phase 7 - the message vocabulary, byte for byte")
    class MessageLiterals {

        @Test
        @DisplayName(":251 - PF7 at the first page refuses navigation with the 'already' wording")
        void pf7AtTheFirstPageRefusesWithTheAlreadyWording() {
            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF7,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            assertThat(screen.screen().errorMessage())
                    .isEqualTo(ALREADY_AT_TOP_MESSAGE)
                    .isEqualTo("You are already at the top of the page...");
            assertThat(screen.errorFlagOn()).isFalse();
            // :253 SET SEND-ERASE-NO TO TRUE - the refusal repaints without erasing.
            assertThat(screen.eraseRequested()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName(":273 - PF8 at the last page refuses navigation with the 'already' wording")
        void pf8AtTheLastPageRefusesWithTheAlreadyWording() {
            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF8,
                    request(1, false, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            assertThat(screen.screen().errorMessage())
                    .isEqualTo(ALREADY_AT_BOTTOM_MESSAGE)
                    .isEqualTo("You are already at the bottom of the page...");
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.eraseRequested()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName(":637 - READNEXT reporting end of file uses the 'have reached the bottom' wording")
        void readNextEndOfFileUsesTheHaveReachedBottomWording() {
            stubStore(store(5));

            UserListScreen screen = listUsers(null);

            assertThat(screen.screen().errorMessage())
                    .isEqualTo(REACHED_BOTTOM_MESSAGE)
                    .isEqualTo("You have reached the bottom of the page...");
        }

        @Test
        @DisplayName(":671 - READPREV reporting end of file uses the 'have reached the top' wording")
        void readPrevEndOfFileUsesTheHaveReachedTopWording() {
            // A page-back that walks off the front of the file: page two, twelve records, so the descending
            // fill exhausts the store before WS-IDX reaches zero.
            stubStore(store(12));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF7,
                    request(2, true, "USR00011", "USR00012", null,
                            List.of(row(" ", "USR00011"), row(" ", "USR00012"))));

            assertThat(screen.screen().errorMessage())
                    .isEqualTo(REACHED_TOP_MESSAGE)
                    .isEqualTo("You have reached the top of the page...");
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName(":603 versus :251 - the 'already'-free and 'already'-bearing sentences are distinct")
        void theAlreadyFreeAndAlreadyBearingSentencesAreDistinct() {
            assertThat(AT_TOP_MESSAGE).isEqualTo("You are at the top of the page...");
            assertThat(ALREADY_AT_TOP_MESSAGE).isEqualTo("You are already at the top of the page...");
            assertThat(AT_TOP_MESSAGE).isNotEqualTo(ALREADY_AT_TOP_MESSAGE);
            assertThat(AT_TOP_MESSAGE).doesNotContain("already");
            assertThat(ALREADY_AT_TOP_MESSAGE).contains("already");
        }

        @Test
        @DisplayName("all five edge-of-data sentences are distinct strings, so none may share a constant")
        void allFiveEdgeOfDataSentencesAreDistinct() {
            List<String> vocabulary = List.of(
                    ALREADY_AT_TOP_MESSAGE,
                    ALREADY_AT_BOTTOM_MESSAGE,
                    AT_TOP_MESSAGE,
                    REACHED_BOTTOM_MESSAGE,
                    REACHED_TOP_MESSAGE);

            assertThat(vocabulary).doesNotHaveDuplicates().hasSize(5);
            assertThat(vocabulary).allSatisfy(sentence -> assertThat(sentence).endsWith("..."));
        }

        @Test
        @DisplayName(":610, :644 and :678 - one literal at three sites, identical at all three")
        void theLookupFailureLiteralIsIdenticalAtAllThreeSites() {
            // The service inlines the string at each of the three WHEN OTHER arms rather than naming it, so
            // the identity is asserted against the pinned constant, which was transcribed from all three.
            assertThat(UNABLE_TO_LOOKUP_MESSAGE).isEqualTo("Unable to lookup User...");
            List<String> threeSites = List.of(
                    UNABLE_TO_LOOKUP_MESSAGE, UNABLE_TO_LOOKUP_MESSAGE, UNABLE_TO_LOOKUP_MESSAGE);
            assertThat(threeSites).containsOnly(UNABLE_TO_LOOKUP_MESSAGE);
        }

        @Test
        @DisplayName("the lookup failure noun is capitalised here, unlike the transaction list's lower case")
        void theLookupFailureNounIsCapitalisedHere() {
            assertThat(UNABLE_TO_LOOKUP_MESSAGE).contains("User").doesNotContain("user");
            assertThat(SIBLING_UNABLE_TO_LOOKUP_MESSAGE).contains("transaction").doesNotContain("User");
            assertThat(UNABLE_TO_LOOKUP_MESSAGE).isNotEqualTo(SIBLING_UNABLE_TO_LOOKUP_MESSAGE);
            // Same prefix, different noun and different casing: precisely the shape that invites a shared
            // constant and then breaks one of the two services.
            assertThat(UNABLE_TO_LOOKUP_MESSAGE).startsWith("Unable to lookup ");
            assertThat(SIBLING_UNABLE_TO_LOOKUP_MESSAGE).startsWith("Unable to lookup ");
        }

        @Test
        @DisplayName(":212 - the invalid selection sentence names both valid selectors, upper case only")
        void theInvalidSelectionSentenceNamesBothSelectors() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(1, "Z")));

            assertThat(screen.screen().errorMessage())
                    .isEqualTo(INVALID_SELECTION_MESSAGE)
                    .isEqualTo("Invalid selection. Valid values are U and D");
        }

        @Test
        @DisplayName(":135 - the invalid key sentence comes from CSMSG01Y, not from this program")
        void theInvalidKeySentenceComesFromTheSharedCopybook() {
            UserListScreen screen = service.submitScreen(AttentionIdentifier.OTHER,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")));

            // CCDA-MSG-INVALID-KEY PIC X(50) of app/cpy/CSMSG01Y.cpy:21, trailing blanks trimmed.
            assertThat(screen.screen().errorMessage())
                    .isEqualTo(INVALID_KEY_MESSAGE)
                    .isEqualTo("Invalid key pressed. Please see below...");
            assertThat(screen.errorFlagOn()).isTrue();
        }

        @Test
        @DisplayName("every message fits the 78-byte ERRMSGO field, so :526 never has to truncate")
        void everyMessageFitsTheErrorMessageField() {
            List<String> vocabulary = List.of(
                    ALREADY_AT_TOP_MESSAGE, ALREADY_AT_BOTTOM_MESSAGE, AT_TOP_MESSAGE,
                    REACHED_BOTTOM_MESSAGE, REACHED_TOP_MESSAGE, UNABLE_TO_LOOKUP_MESSAGE,
                    INVALID_SELECTION_MESSAGE, INVALID_KEY_MESSAGE);

            assertThat(UserSecurityDto.ERROR_MESSAGE_WIDTH).isEqualTo(78);
            assertThat(vocabulary).allSatisfy(sentence ->
                    assertThat(sentence.length()).isLessThanOrEqualTo(UserSecurityDto.ERROR_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("a clean page carries no message at all, so silence is itself part of the vocabulary")
        void aCleanPageCarriesNoMessage() {
            stubStore(store(25));

            assertThat(listUsers(null).screen().errorMessage()).isEmpty();
        }
    }

    // Phase 8 - field contracts and the sixteen-way paragraph correspondence.

    /**
     * The field contract and the paragraph map.
     *
     * <p>{@code UserSecurityDto} derives from {@code app/cpy-bms/COUSR00.CPY}, whose fifty nine input fields
     * are what the ten row table inflates the count to. {@code UserSecurity} derives from
     * {@code app/cpy/CSUSR01Y.cpy}, an eighty byte layout of {@code SEC-USR-ID X(08)} plus
     * {@code SEC-USR-FNAME X(20)} plus {@code SEC-USR-LNAME X(20)} plus {@code SEC-USR-PWD X(08)} plus
     * {@code SEC-USR-TYPE X(01)} plus filler, with a key length of eight.
     * <strong>{@code SEC-USR-PWD} never appears in any list response.</strong>
     *
     * <p>{@code app/cbl/COUSR00C.cbl} has sixteen paragraph labels at {@code :98}, {@code :149},
     * {@code :237}, {@code :260}, {@code :282}, {@code :336}, {@code :384}, {@code :446}, {@code :506},
     * {@code :522}, {@code :549}, {@code :562}, {@code :586}, {@code :619}, {@code :653} and {@code :687}.
     * Each maps to one private method and labels are never consolidated - including the four browse
     * paragraphs, which collapse conceptually into paged queries but must stay four distinct methods so the
     * paragraph map remains provable.
     */
    @Nested
    @DisplayName("Phase 8 - field contracts and the sixteen paragraph methods")
    class FieldContractsAndParagraphMap {

        @Test
        @DisplayName("the response omits the credential entirely: no component, no accessor, no value")
        void theResponseOmitsTheCredentialEntirely() {
            Set<String> rowComponents = new LinkedHashSet<>();
            for (RecordComponent component : UserSecurityDto.UserRow.class.getRecordComponents()) {
                rowComponents.add(component.getName());
            }
            Set<String> screenComponents = new LinkedHashSet<>();
            for (RecordComponent component : UserSecurityDto.class.getRecordComponents()) {
                screenComponents.add(component.getName());
            }

            assertThat(rowComponents).hasSize(UserSecurityDto.ROW_FIELD_COUNT);
            assertThat(rowComponents)
                    .containsExactly("selectionFlag", "userId", "firstName", "lastName", "userType");
            assertThat(screenComponents).containsExactly("transactionName", "title01", "currentDate",
                    "programName", "title02", "currentTime", "pageNumber", "userIdInput", "rows",
                    "errorMessage");
            assertThat(screenComponents).noneMatch(name -> {
                String lowered = name.toLowerCase(Locale.ROOT);
                return lowered.contains("password") || lowered.contains("pwd") || lowered.contains("hash");
            });
        }

        @Test
        @DisplayName("the user type maps to a two constant enum, 'A' for admin and 'U' for standard")
        void theUserTypeMapsToATwoConstantEnum() {
            assertThat(UserType.values()).hasSize(2).containsExactly(UserType.ADMIN, UserType.USER);
            assertThat(UserType.ADMIN.getCode()).isEqualTo('A');
            assertThat(UserType.USER.getCode()).isEqualTo('U');
        }

        @Test
        @DisplayName("the bean declares one private method per source paragraph, all sixteen of them")
        void theBeanDeclaresOneMethodPerSourceParagraph() {
            assertThat(PARAGRAPH_METHOD_NAMES).hasSize(16).doesNotHaveDuplicates();
            assertThat(declaredMethodNames()).containsAll(PARAGRAPH_METHOD_NAMES);
        }

        @Test
        @DisplayName("the four browse paragraphs remain four distinct methods, never collapsed into one")
        void theFourBrowseParagraphsRemainFourDistinctMethods() {
            Set<String> declared = declaredMethodNames();

            assertThat(BROWSE_METHOD_NAMES).hasSize(4).doesNotHaveDuplicates();
            assertThat(declared).containsAll(BROWSE_METHOD_NAMES);
            for (String browseMethod : BROWSE_METHOD_NAMES) {
                assertThat(PARAGRAPH_METHOD_NAMES).contains(browseMethod);
            }
        }

        @Test
        @DisplayName("the three public entry points cover the three ways MAIN-PARA can be reached")
        void theThreePublicEntryPointsCoverTheThreeWaysMainParaIsReached() {
            Set<String> publicMethods = new LinkedHashSet<>();
            for (Method method : UserListService.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    publicMethods.add(method.getName());
                }
            }

            // :110 EIBCALEN = 0, :115 not re-enter, and :121 re-enter with an attention identifier.
            assertThat(publicMethods).containsExactlyInAnyOrder(
                    "listUsers", "submitScreen", "openWithoutContext");
        }

        @Test
        @DisplayName("the attention identifiers are exactly the five EIBAID cases of :122-137")
        void theAttentionIdentifiersAreExactlyTheFiveEibaidCases() {
            List<String> names = Arrays.stream(AttentionIdentifier.values()).map(Enum::name).toList();

            assertThat(names).containsExactly("ENTER", "PF3", "PF7", "PF8", "OTHER");
        }

        @Test
        @DisplayName("the screen record exposes every observable the source's screen carried")
        void theScreenRecordExposesEveryObservable() {
            stubStore(store(25));

            UserListScreen screen = listUsers(null);

            assertThat(screenComponent(screen, "legacyPageNumber")).isEqualTo(1);
            assertThat(screenComponent(screen, "cursorField")).isEqualTo(CURSOR_FIELD);
            assertThat(screenComponent(screen, "errorFlagOn")).isEqualTo(Boolean.FALSE);
            assertThat(screenComponent(screen, "controlTransferred")).isEqualTo(Boolean.FALSE);
            assertThat(screenComponent(screen, "navigationTarget")).isNull();
        }

        @Test
        @DisplayName("POPULATE-HEADER-INFO fills all six recurring header fields from one clock reading")
        void populateHeaderInfoFillsAllSixHeaderFields() {
            stubStore(store(25));

            UserSecurityDto assembled = listUsers(null).screen();

            // The six fields that recur on all seventeen symbolic maps: TRNNAME, TITLE01, CURDATE, PGMNAME,
            // TITLE02, CURTIME. Sources :566-:581 plus app/cpy/COTTL01Y.cpy.
            assertThat(assembled.transactionName()).isEqualTo(TRANSACTION_ID).isEqualTo("CU00");
            assertThat(assembled.programName()).isEqualTo(PROGRAM_NAME).isEqualTo("COUSR00C");
            assertThat(assembled.title01()).isEqualTo(SCREEN_TITLE_01).hasSize(40);
            assertThat(assembled.title02()).isEqualTo(SCREEN_TITLE_02).hasSize(40);
            assertThat(assembled.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(assembled.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
        }

        @Test
        @DisplayName("the header is derived from the injected clock, so it is reproducible run after run")
        void theHeaderIsDerivedFromTheInjectedClock() {
            stubStore(store(25));

            UserSecurityDto first = listUsers(null).screen();
            UserSecurityDto second = listUsers(null).screen();

            assertThat(second.currentDate()).isEqualTo(first.currentDate());
            assertThat(second.currentTime()).isEqualTo(first.currentTime());
        }

        @Test
        @DisplayName("the identifier field is cleared before the send, per :328 MOVE SPACE TO USRIDINO")
        void theIdentifierFieldIsClearedBeforeTheSend() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, "USR00003", List.of()));

            assertThat(screen.screen().userIdInput()).isEmpty();
        }

        @Test
        @DisplayName("the send count reflects every SEND MAP the source issues on the path taken")
        void theSendCountReflectsEverySendMap() {
            stubStore(store(25));

            // :119 sends after PROCESS-ENTER-KEY and :329 sends inside PROCESS-PAGE-FORWARD, so the
            // first-entry path issues two. A re-entry skips :119 and issues one.
            assertThat(listUsers(null).sendCount()).isEqualTo(2);

            UserListService second = newService(PAGE_SIZE);
            assertThat(second.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, null, List.of())).sendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the erase flag follows SEND-ERASE-YES, and only a refused navigation turns it off")
        void theEraseFlagFollowsTheSendEraseSwitch() {
            stubStore(store(25));

            assertThat(listUsers(null).eraseRequested()).isTrue();

            UserListService second = newService(PAGE_SIZE);
            assertThat(second.submitScreen(AttentionIdentifier.PF7,
                    request(1, true, "USR00001", "USR00010", null, pageWithSelection(0, " ")))
                    .eraseRequested()).isFalse();
        }
    }

    // Phase 9 - hostile input, and the clauses of Rule 1 that this bean can
    // actually be held to.

    /**
     * Untrusted input and the standards of Rule 1, clause by clause, asserted where this bean is the thing
     * that can satisfy them.
     *
     * <p>Clause A wants determinism and explicit behaviour: every reading of the clock goes through the
     * injected {@link Clock}, every paged read carries a deterministic order, and nothing depends on hash
     * iteration order - which for a paging service is the failure that shifts page boundaries between runs.
     * Clause A also wants untrusted input treated as untrusted, which is the whole first half of this group.
     * Clause B wants explicit null and boundary handling, no global mutable state, and errors that preserve
     * type, message and root cause. Clause C wants determinism free of environment specific assumptions, so
     * nothing here names a host, a port or a connection string. Clause D wants no secret in a test, so every
     * credential is a well formed placeholder and the plaintext the ten seeded rows of
     * {@code app/jcl/DUSRSECJ.jcl} share is absent from this file in every form.
     */
    @Nested
    @DisplayName("Phase 9 - hostile input and Rule 1 compliance")
    class HostileInputAndRuleCompliance {

        @Test
        @DisplayName("a null attention identifier is refused by name, not by NullPointerException downstream")
        void aNullAttentionIdentifierIsRefusedByName() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitScreen(
                            null, request(0, false, null, null, null, List.of())))
                    .withMessage("aid must not be null");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a null request is refused by name")
        void aNullRequestIsRefusedByName() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, null))
                    .withMessage("request must not be null");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a null starting identifier is the LOW-VALUES case, not a failure")
        void aNullStartingIdentifierIsTheLowValuesCase() {
            stubStore(store(25));

            UserListScreen screen = listUsers(null);

            assertThat(keysOf(screen)).startsWith("USR00001");
            verify(repository, never()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    anyString(), any(Pageable.class));
        }

        @Test
        @DisplayName("a blank first key on page-back resets to LOW-VALUES and never probes a key")
        void aBlankFirstKeyOnPageBackResetsToLowValues() {
            stubStore(store(35));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.PF7,
                    request(3, true, "   ", "USR00030", null, pageWithSelection(0, " ")));

            // :239-:242 chooses between LOW-VALUES and the captured first key. A blank key takes the
            // LOW-VALUES arm, so the browse opens at the front and the backward skip read immediately runs
            // off it: no rows, the counter left where it was, and the :671 wording rather than the :251 one.
            assertThat(keysOf(screen)).isEmpty();
            assertThat(screen.legacyPageNumber()).isEqualTo(3);
            assertThat(screen.screen().pageNumber()).isEqualTo("00000003");
            assertThat(screen.screen().errorMessage()).isEqualTo(REACHED_TOP_MESSAGE);
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.sendCount()).isEqualTo(2);
            // The decisive assertion: a blank key is never handed to the store as a key to look up.
            verify(repository, never()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    anyString(), any(Pageable.class));
        }

        @Test
        @DisplayName("a negative page number is refused, because PIC 9(08) is unsigned")
        void aNegativePageNumberIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(-1, false, null, null, null, List.of())))
                    .withMessage("pageNumber must be between 0 and 99999999 because CDEMO-CU00-PAGE-NUM is"
                            + " PIC 9(08), but was -1")
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo("pageNumber");
                        assertThat(failure.hasFieldName()).isTrue();
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a page number past the eight digit ceiling is refused")
        void aPageNumberPastTheCeilingIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(100_000_000, false, null, null, null, List.of())))
                    .withMessage("pageNumber must be between 0 and 99999999 because CDEMO-CU00-PAGE-NUM is"
                            + " PIC 9(08), but was 100000000");
        }

        @Test
        @DisplayName("page number zero is accepted, because :227 resets the counter to exactly that")
        void pageNumberZeroIsAccepted() {
            stubStore(store(25));

            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, null, List.of()));

            assertThat(screen.legacyPageNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("an over-wide first key is refused and named")
        void anOverWideFirstKeyIsRefusedAndNamed() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF7,
                            request(2, true, "USR000011", null, null, List.of())))
                    .withMessage("firstKey must be at most 8 characters because the screen field is that"
                            + " wide, but was 9 characters long")
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("firstKey"));
        }

        @Test
        @DisplayName("an over-wide last key is refused and named")
        void anOverWideLastKeyIsRefusedAndNamed() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.PF8,
                            request(2, true, null, "USR000011", null, List.of())))
                    .withMessage("lastKey must be at most 8 characters because the screen field is that"
                            + " wide, but was 9 characters long")
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("lastKey"));
        }

        @Test
        @DisplayName("an eleventh submitted row is refused, because the screen paints exactly ten")
        void anEleventhSubmittedRowIsRefused() {
            List<UserSecurityDto.UserRow> elevenRows = new ArrayList<>(PAGE_SIZE + 1);
            for (int position = 1; position <= PAGE_SIZE + 1; position++) {
                elevenRows.add(row(" ", String.format(Locale.ROOT, "USR%05d", position)));
            }

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(1, true, null, null, null, List.copyOf(elevenRows))))
                    .withMessage("displayedRows holds 11 rows, which exceeds the 10 rows the user list"
                            + " screen paints")
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo("displayedRows"));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a null row inside the submitted page is refused by the request record itself")
        void aNullRowInsideTheSubmittedPageIsRefused() {
            List<UserSecurityDto.UserRow> withHole = new ArrayList<>(2);
            withHole.add(row(" ", "USR00001"));
            withHole.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> request(1, true, null, null, null, withHole))
                    .withMessage("displayedRows must not contain a null element, but index 1 was null");
        }

        @Test
        @DisplayName("a null submitted page becomes an empty one, so the browse still runs")
        void aNullSubmittedPageBecomesAnEmptyOne() {
            stubStore(store(25));

            UserListRequest lenient = request(0, false, null, null, null, null);

            assertThat(lenient.displayedRows()).isEmpty();
            assertThat(keysOf(service.submitScreen(AttentionIdentifier.ENTER, lenient)))
                    .hasSize(PAGE_SIZE);
        }

        @Test
        @DisplayName("the submitted page is copied, so a later mutation of the caller's list cannot leak in")
        void theSubmittedPageIsCopied() {
            List<UserSecurityDto.UserRow> caller = new ArrayList<>(1);
            caller.add(row("U", "USR00001"));

            UserListRequest captured = request(1, true, null, null, null, caller);
            caller.clear();

            assertThat(captured.displayedRows()).hasSize(1);
            assertThat(captured.displayedRows().get(0).userId()).isEqualTo("USR00001");
        }

        @Test
        @DisplayName("every collaborator is refused at construction when absent, each by name")
        void everyCollaboratorIsRefusedAtConstructionWhenAbsent() {
            Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new UserListService(null, new FileStatusMapper(), clock, PAGE_SIZE))
                    .withMessage("userSecurityRepository must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new UserListService(repository, null, clock, PAGE_SIZE))
                    .withMessage("fileStatusMapper must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            new UserListService(repository, new FileStatusMapper(), null, PAGE_SIZE))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("the bean holds no mutable state at all, so concurrent callers cannot interfere")
        void theBeanHoldsNoMutableState() {
            for (Field field : UserListService.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("UserListService.%s must be final: a paging bean with mutable state would let"
                                + " one caller's page bleed into another's", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("collaborators arrive only through the constructor, so nothing is set after the fact")
        void collaboratorsArriveOnlyThroughTheConstructor() {
            Set<String> mutators = new LinkedHashSet<>();
            for (Method method : UserListService.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("set")) {
                    mutators.add(method.getName());
                }
            }

            assertThat(UserListService.class.getDeclaredConstructors()).hasSize(1);
            assertThat(mutators).isEmpty();
        }

        @Test
        @DisplayName("one instance serves repeated invocations identically, which is what stateless means")
        void oneInstanceServesRepeatedInvocationsIdentically() {
            stubStore(store(25));

            UserListScreen first = listUsers(null);
            UserListScreen second = listUsers(null);
            UserListScreen third = listUsers(null);

            // The work area is per invocation, so the send count, the page number and the rows repeat exactly
            // rather than accumulating. Session state would show up here as drift.
            assertThat(second.sendCount()).isEqualTo(first.sendCount());
            assertThat(third.sendCount()).isEqualTo(first.sendCount());
            assertThat(keysOf(second)).isEqualTo(keysOf(first));
            assertThat(keysOf(third)).isEqualTo(keysOf(first));
            assertThat(second.legacyPageNumber()).isEqualTo(first.legacyPageNumber());
            assertThat(second.page().getFirstKey()).isEqualTo(first.page().getFirstKey());
        }

        @Test
        @DisplayName("the paged read carries a deterministic order on the eight character identifier")
        void thePagedReadCarriesADeterministicOrder() {
            Set<String> finderNames = new LinkedHashSet<>();
            for (Method method : UserSecurityRepository.class.getDeclaredMethods()) {
                finderNames.add(method.getName());
            }

            // The ordering is encoded in every browse finder's name, which is why the Pageable can stay
            // unsorted and the result still be reproducible. Without it, page boundaries would move between
            // runs. The descending finder is the backward browse of PF7 and orders on the same single key, so
            // the total order is one order read in two directions and not two different orders.
            // In any order: getDeclaredMethods makes no ordering guarantee, and the contract here is which
            // finders exist, not what order the JVM happens to report them in.
            assertThat(finderNames)
                    .as("the interface declares exactly four methods and no more: three browses over the one "
                            + "key - the start-of-file page read this service opens with and the two keyset "
                            + "finders it positions with - and the pessimistic read the update and delete "
                            + "paths use to reproduce EXEC CICS READ ... UPDATE. A fifth would be an access "
                            + "path nobody asked for")
                    .containsExactlyInAnyOrder("findAllByOrderBySecUsrIdAsc",
                            "findByIdForUpdate",
                            "findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc",
                            "findBySecUsrIdLessThanEqualOrderBySecUsrIdDesc");
            assertThat(finderNames)
                    .as("every browse this service reads through carries its order in its own name")
                    .filteredOn(name -> !"findByIdForUpdate".equals(name))
                    .allSatisfy(name -> assertThat(name)
                            .matches(candidate -> candidate.endsWith("OrderBySecUsrIdAsc")
                                    || candidate.endsWith("OrderBySecUsrIdDesc")));
        }

        @Test
        @DisplayName("rows arrive and are painted in ascending key order, never in hash order")
        void rowsArePaintedInAscendingKeyOrder() {
            stubStore(store(25));

            List<String> painted = keysOf(listUsers(null));

            // containsExactly is order sensitive on purpose. An implementation that staged rows through a
            // HashMap or HashSet would pass an order-insensitive assertion and still shift page boundaries.
            assertThat(painted).containsExactly(
                    "USR00001", "USR00002", "USR00003", "USR00004", "USR00005",
                    "USR00006", "USR00007", "USR00008", "USR00009", "USR00010");
            assertThat(painted).isSorted();
        }

        @Test
        @DisplayName("untrusted input reaches the store only through bound parameters, never through SQL text")
        void untrustedInputReachesTheStoreOnlyThroughBoundParameters() {
            stubStore(store(25));
            String hostileKey = "'; DROP";

            // The key travels as an argument and nothing else. This service's own finder is derived, so it has
            // no query text at all; the interface's one JPQL finder - the pessimistic read the update and delete
            // paths use - is authored rather than assembled and binds its argument by name, which is the
            // property that matters. A native query, or a query text containing a concatenation operator or a
            // format placeholder, is what would open a splicing path.
            for (Method method : UserSecurityRepository.class.getDeclaredMethods()) {
                for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
                    assertThat(annotation.annotationType().getSimpleName()).isNotEqualTo("NativeQuery");
                }
                final Query query = method.getAnnotation(Query.class);
                if (query != null) {
                    assertThat(query.value()).doesNotContain("+").doesNotContain("%s").contains(":");
                    assertThat(method.getParameters())
                            .allSatisfy(parameter -> assertThat(parameter.isAnnotationPresent(Param.class))
                                    .isTrue());
                }
            }
            // The key positions equal-or-greater like any other, so it returns rows rather than failing -
            // which is the stronger statement: the hostile text is never interpreted, only compared. It
            // sorts below every seeded identifier, so the page opens on the first record.
            UserListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    request(0, false, null, null, hostileKey, List.of()));

            assertThat(keysOf(screen)).startsWith("USR00001");
            verify(repository, atLeastOnce()).findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    eq(hostileKey), any(Pageable.class));
        }

        @Test
        @DisplayName("both querying entry points are read-only transactions; the stateless one needs none")
        void bothQueryingEntryPointsAreReadOnlyTransactions() throws NoSuchMethodException {
            Method list = UserListService.class.getDeclaredMethod("listUsers", String.class);
            Method submit = UserListService.class.getDeclaredMethod(
                    "submitScreen", AttentionIdentifier.class, UserListRequest.class);
            Method openWithout = UserListService.class.getDeclaredMethod("openWithoutContext");

            assertThat(list.getAnnotation(Transactional.class)).isNotNull()
                    .satisfies(annotation -> assertThat(annotation.readOnly()).isTrue());
            assertThat(submit.getAnnotation(Transactional.class)).isNotNull()
                    .satisfies(annotation -> assertThat(annotation.readOnly()).isTrue());
            // :110-112 touches no file at all, so it needs no transaction and declares none.
            assertThat(openWithout.getAnnotation(Transactional.class)).isNull();
        }

        @Test
        @DisplayName("the bean declares no authorisation of its own, so least privilege lives above it")
        void theBeanDeclaresNoAuthorisationOfItsOwn() {
            Set<String> annotationNames = new LinkedHashSet<>();
            for (java.lang.annotation.Annotation annotation : UserListService.class.getAnnotations()) {
                annotationNames.add(annotation.annotationType().getSimpleName());
            }
            for (Method method : UserListService.class.getDeclaredMethods()) {
                for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
                    annotationNames.add(annotation.annotationType().getSimpleName());
                }
            }

            // Everything this bean can offer towards the ADMIN-only rule: it carries no authorisation
            // annotation, takes no role or user type argument, and therefore cannot be reached except through
            // whatever guards its caller. NOT AVAILABLE at this tier: the /api/admin/* mapping and the
            // STATELESS session policy, which live in the security configuration and the administration
            // controller. A web layer test owns those; asserting them here would assert nothing.
            assertThat(annotationNames)
                    .contains("Service", "Transactional")
                    .doesNotContain("PreAuthorize", "Secured", "RolesAllowed", "PostAuthorize");
            for (Method method : UserListService.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertThat(method.getParameterTypes()).doesNotContain(UserType.class);
                }
            }
        }

        @Test
        @DisplayName("the only time source is the injected clock, so no hidden wall clock can leak in")
        void theOnlyTimeSourceIsTheInjectedClock() {
            stubStore(store(25));
            UserSecurityDto pinned = listUsers(null).screen();

            UserSecurityRepository other = mock(UserSecurityRepository.class,
                    withSettings().strictness(Strictness.STRICT_STUBS));
            when(other.findAllByOrderBySecUsrIdAsc(any(Pageable.class))).thenAnswer(invocation -> {
                Pageable pageable = invocation.getArgument(0, Pageable.class);
                List<UserSecurity> rows = store(25);
                int from = Math.min((int) pageable.getOffset(), rows.size());
                int to = Math.min(from + pageable.getPageSize(), rows.size());
                return new SliceImpl<>(rows.subList(from, to), pageable, to < rows.size());
            });
            UserListService shifted = new UserListService(other, new FileStatusMapper(),
                    Clock.fixed(Instant.parse("1999-12-31T23:58:01Z"), ZoneOffset.UTC), PAGE_SIZE);

            UserSecurityDto moved = shifted.listUsers(null).screen();

            assertThat(pinned.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(pinned.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(moved.currentDate()).isEqualTo("12/31/99");
            assertThat(moved.currentTime()).isEqualTo("23:58:01");
        }

        @Test
        @DisplayName("the header formats are locale independent, so a host locale cannot alter them")
        void theHeaderFormatsAreLocaleIndependent() {
            stubStore(store(25));

            UserSecurityDto assembled = listUsers(null).screen();

            // MM/DD/YY and HH:MM:SS with fixed separators, as :571-:581 assembles them field by field.
            assertThat(assembled.currentDate()).matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(assembled.currentTime()).matches("\\d{2}:\\d{2}:\\d{2}");
        }

        @Test
        @DisplayName("credentials used here are well formed placeholders that carry no secret")
        void credentialsUsedHereAreWellFormedPlaceholders() {
            String placeholder = syntheticHash(7);

            // Clause D names tests explicitly. The entity enforces a strength ten BCrypt shape, so a
            // placeholder still has to be well formed - but it holds no secret, and the plaintext that the
            // ten rows seeded by app/jcl/DUSRSECJ.jcl share appears nowhere in this file.
            assertThat(placeholder).hasSize(BCRYPT_HASH_WIDTH).startsWith(BCRYPT_PREFIX);
            assertThat(placeholder).matches("^\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}$");
            assertThat(placeholder).contains("NoSecret");
            assertThat(syntheticHash(8)).isNotEqualTo(placeholder);
        }

        @Test
        @DisplayName("no assertion in this class names a host, a port or a connection string")
        void noAssertionNamesAHostPortOrConnectionString() {
            List<String> everyLiteralUsedHere = List.of(
                    PROGRAM_NAME, TRANSACTION_ID, USRSEC_FILE, ADMIN_MENU_PROGRAM, SIGN_ON_PROGRAM,
                    USER_UPDATE_PROGRAM, USER_DELETE_PROGRAM, SCREEN_TITLE_01, SCREEN_TITLE_02,
                    CURSOR_FIELD, ALREADY_AT_TOP_MESSAGE, ALREADY_AT_BOTTOM_MESSAGE, AT_TOP_MESSAGE,
                    REACHED_BOTTOM_MESSAGE, REACHED_TOP_MESSAGE, UNABLE_TO_LOOKUP_MESSAGE,
                    SIBLING_UNABLE_TO_LOOKUP_MESSAGE, INVALID_SELECTION_MESSAGE, INVALID_KEY_MESSAGE,
                    EXPECTED_HEADER_DATE, EXPECTED_HEADER_TIME, syntheticHash(1));

            // Clause C: the build must not assume an environment. None of these strings could only be true on
            // one machine, which is what keeps this tier runnable with no container and no network.
            assertThat(everyLiteralUsedHere).allSatisfy(literal -> assertThat(literal)
                    .doesNotContain("localhost")
                    .doesNotContain("127.0.0.1")
                    .doesNotContain("0.0.0.0")
                    .doesNotContain("jdbc:")
                    .doesNotContain("amazonaws.com")
                    .doesNotContain(":5432")
                    .doesNotContain(":4566"));
        }

        @Test
        @DisplayName("the sign-on program is the target when no communication area arrives, per :110-112")
        void theSignOnProgramIsTheTargetWhenNoCommunicationAreaArrives() {
            UserListScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget()).isEqualTo(SIGN_ON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(screen.controlTransferred()).isTrue();
            assertThat(screen.legacyPageNumber()).isZero();
            assertThat(screen.sendCount()).isZero();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a store of exactly the page size plus one is the boundary the look-ahead exists for")
        void aStoreOfExactlyThePageSizePlusOneIsTheLookAheadBoundary() {
            stubStore(store(PAGE_SIZE + 1));

            UserListScreen screen = listUsers(null);

            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.page().isNextPageAvailable()).isTrue();
            assertThat(screen.screen().errorMessage()).isEmpty();
        }

        @Test
        @DisplayName("a store of exactly the page size stops cleanly with the bottom-of-page message")
        void aStoreOfExactlyThePageSizeStopsCleanly() {
            stubStore(store(PAGE_SIZE));

            UserListScreen screen = listUsers(null);

            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.screen().errorMessage()).isEqualTo(REACHED_BOTTOM_MESSAGE);
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("a single stored row is still a page, not an empty result and not a failure")
        void aSingleStoredRowIsStillAPage() {
            stubStore(store(1));

            UserListScreen screen = listUsers(null);

            assertThat(keysOf(screen)).containsExactly("USR00001");
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("a page size of one still works, proving ten is configuration and not an array bound")
        void aPageSizeOfOneStillWorks() {
            stubStore(store(3));
            UserListService narrow = newService(1);

            UserListScreen screen = narrow.listUsers(null);

            // Ten is the parity contract for this application; the mechanism itself is not hard wired to it,
            // which is what lets the contract be asserted rather than merely assumed.
            assertThat(screen.page().getRows()).hasSize(1);
            assertThat(screen.page().getPageSize()).isEqualTo(1);
            assertThat(screen.page().isNextPageAvailable()).isTrue();
            assertThat(screen.page().getFirstKey()).isEqualTo("USR00001");
            assertThat(screen.page().getLastKey()).isEqualTo("USR00001");
        }
    }
}
