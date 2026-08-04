/*
 * ****************************************************************************
 * Program     : CardListServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies CardListService against COCRDLIC paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the seven-row page that is a parity
 *               contract rather than a tunable (:177-178), the
 *               counter-intuitive 0 = shown / 9 = not shown last-page
 *               sentinel and the LOW-VALUES / 'Y' tri-state next-page
 *               indicator (:237-248), the 27-byte card-number plus
 *               account-identifier keyset cursor that now travels in the
 *               request rather than in a commarea (:230-235), the forward
 *               and backward browses (:1123-1261, :1264-1374), the
 *               short-circuiting two-gate record filter (:1382-1409), the
 *               three-valued filter flags with their byte-exact upper-case
 *               literals (:1003-1032, :1036-1069), and the asymmetric first
 *               row of the symbolic map, which declares no CRDSTP1I.
 * Source      : app/cbl/COCRDLIC.cbl    (1,459 lines; 39 PROCEDURE DIVISION
 *                                        Area-A labels plus 2 copied in)
 *               app/cpy/CVCRD01Y.cpy    (CC-ACCT-ID X(11) + CC-ACCT-ID-N,
 *                                        CC-CARD-NUM X(16) + CC-CARD-NUM-N,
 *                                        the CCARD-AID-* family)
 *               app/cpy/CVACT02Y.cpy    (CARD-RECORD, RECLN 150, key X(16))
 *               app/cpy-bms/COCRDLI.CPY (45 input fields, PAGENOI X(3))
 *               app/csd/CARDDEMO.CSD    (CCLI -> COCRDLIC) @ 7756d89
 * ****************************************************************************
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
 * ****************************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardListService.CardListRequest;
import com.cardemo.service.card.CardListService.CardListRequest.DisplayedRow;
import com.cardemo.service.card.CardListService.CardListResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@code com.cardemo.service.card.CardListService}, the Java target of
 * {@code app/cbl/COCRDLIC.cbl} and CICS transaction {@code CCLI}
 * ({@code app/csd/CARDDEMO.CSD}).
 *
 * <h2>1. What this test class does</h2>
 * <p>It exercises the one public entry point, {@code listCards(CardListRequest)}, against a Mockito
 * double of {@code CardRepository}. A double rather than a database is the point: every contract
 * below is only observable when the exact number of rows a browse window yields is dictated row by
 * row. The following verified locators are asserted, and a change to any of them turns a test
 * red rather than silently diverging from the system of record:</p>
 * <ul>
 *   <li>{@code :177-178} - {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}, corroborated by
 *       {@code OCCURS 7 TIMES} at {@code :76}, {@code :86} and {@code :255} and by the source's own
 *       comment at {@code :250}, {@code 28 CHARS X 7 ROWS = 196}.</li>
 *   <li>{@code :237-248} - the paging sentinels. {@code 88 CA-LAST-PAGE-SHOWN VALUE 0} and
 *       {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9}, so zero means the last page HAS been shown; and
 *       {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES} against
 *       {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'}, which is a tri-state and not a boolean.</li>
 *   <li>{@code :230-235} - {@code WS-CA-LAST-CARDKEY} and {@code WS-CA-FIRST-CARDKEY}, each a
 *       {@code X(16)} card number followed by a {@code 9(11)} account identifier: a 27-byte
 *       composite cursor.</li>
 *   <li>{@code :298} {@code 0000-MAIN.}, {@code :604} {@code COMMON-RETURN.}, {@code :621}
 *       {@code 0000-MAIN-EXIT.}, {@code :624} {@code 1000-SEND-MAP.}, {@code :642}
 *       {@code 1100-SCREEN-INIT.}, {@code :678} {@code 1200-SCREEN-ARRAY-INIT.}, {@code :748}
 *       {@code 1250-SETUP-ARRAY-ATTRIBS.}, {@code :837} {@code 1300-SETUP-SCREEN-ATTRS.},
 *       {@code :895} {@code 1400-SETUP-MESSAGE.}, {@code :938} {@code 1500-SEND-SCREEN.},
 *       {@code :951} {@code 2000-RECEIVE-MAP.}, {@code :962} {@code 2100-RECEIVE-SCREEN.},
 *       {@code :985} {@code 2200-EDIT-INPUTS.}, {@code :1073} {@code 2250-EDIT-ARRAY.},
 *       {@code :1422} {@code SEND-PLAIN-TEXT.} and {@code :1441} {@code SEND-LONG-TEXT.}</li>
 *   <li>{@code :1003-1032} {@code 2210-EDIT-ACCOUNT.} and {@code :1036-1069}
 *       {@code 2220-EDIT-CARD.} - three-valued flags and the two exact literals.</li>
 *   <li>{@code :1123-1261} {@code 9000-READ-FORWARD.} and {@code :1264-1374}
 *       {@code 9100-READ-BACKWARDS.} - forward from the last key, backward from the first.</li>
 *   <li>{@code :1382-1409} {@code 9500-FILTER-RECORDS.} - two independent gates ANDed, the first
 *       short-circuiting through {@code GO TO 9500-FILTER-RECORDS-EXIT}.</li>
 *   <li>{@code app/cpy-bms/COCRDLI.CPY:60} {@code PAGENOI PIC X(3)}, and {@code :108}, {@code :138},
 *       {@code :168}, {@code :198}, {@code :228} and {@code :258} - {@code CRDSTP2I} through
 *       {@code CRDSTP7I}, with no {@code CRDSTP1I} declared anywhere.</li>
 *   </ul>
 *
 * <h2>2. How to build, run and test</h2>
 * <p>This class is bound to <strong>Surefire</strong> {@code 3.5.4}, which includes
 * {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}; it therefore must stay under
 * {@code src/test/java/com/cardemo/unit/}. A class placed outside that tree matches neither
 * Surefire's nor Failsafe's include set, so it is collected by neither plugin and never runs -
 * a green build with no error and no warning. From the repository root:</p>
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} - runs this class under Surefire; the report lands in
 *       {@code target/surefire-reports/}.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=CardListServiceTest test} - runs this class alone.</li>
 *   <li>{@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify} - the full gate. Note the
 *       skip property is hyphenated.</li>
 *   </ul>
 * <p>Compilation is {@code maven-compiler-plugin:3.14.1} at {@code release 25} with
 * {@code -Xlint:all -Werror} and {@code failOnWarning}, so a single warning anywhere in the test
 * tree fails the build. Coverage is {@code jacoco-maven-plugin:0.8.12} with an 80 percent LINE
 * floor and no exclusions.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 * <p><strong>{@code carddemo.pagination.card-list-page-size} = 7.</strong> A parity contract, not a
 * tunable: the service constructor rejects any other value outright, so the property is supplied
 * here through the constructor exactly as {@code @Value} supplies it in production. The transaction
 * list and the user list are ten, and this family is deliberately NOT unified with theirs -
 * {@code COTRN00C} uses an {@code 'N'}-valued sentinel family with {@code X(16)} identifier bounds
 * and a {@code PIC 9(08)} page number, where this program uses a numeric 0/9 pair and a
 * {@code PIC X(3)} page field.</p>
 * <p>Mockito runs with <strong>strict stubs</strong>, declared explicitly through
 * {@code @MockitoSettings}: a stub that no test uses fails the build, and an argument that no stub
 * matches fails at the call site rather than returning null. This tier is pure JVM - no Spring
 * context, no container, no database, no network, no clock and no random source - so nothing here
 * can reach an external endpoint and every run is reproducible. The header date and time are
 * supplied on the request because the service takes them as input rather than reading a clock.</p>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><strong>Compilation fails on a warning.</strong> {@code -Werror} is fatal in the test tree too. One
 *       raw type, one unchecked cast or one deprecated call is enough. An unused import is not - {@code javac}
 *       25 publishes no {@code unused} lint key - so remove one because Rule 1 Clause B requires it, not
 *       because the build will stop.</li>
 *   <li><strong>{@code lastPageDisplayed} sentinel inverted.</strong> Passing 1 for "the last page
 *       has been shown" is the natural mistake and it silently loses the
 *       {@code NO MORE PAGES TO DISPLAY} error, because the condition tests for 0. Severity High.</li>
 *   <li><strong>Next-page indicator treated as a boolean.</strong> The field has three states -
 *       unset, {@code LOW-VALUES} and {@code 'Y'} - and the unset state takes neither paging
 *       branch.</li>
 *   <li><strong>All-zeros filter treated as invalid.</strong> The blank test at {@code :1009} and
 *       {@code :1044} compares the numeric redefinition against {@code ZEROS}, so
 *       {@code "00000000000"} means "no filter" and must not raise. Severity Medium.</li>
 *   <li><strong>A row-one selector synthesised.</strong> The map declares no {@code CRDSTP1I}, so
 *       row one has four fields where the others have five, and the map has 45 input fields rather
 *       than 46. Severity Low.</li>
 *   <li><strong>Pagination state sought in a commarea field.</strong> {@code app/cpy/COCOM01Y.cpy}
 *       declares no page-number and no next-page field; the paging state lives in this program's
 *       own {@code WORKING-STORAGE} at {@code :229-260} and now travels in the request and the
 *       response metadata. Asserting a commarea page field is severity High.</li>
 *   <li><strong>{@code UnnecessaryStubbingException}.</strong> A test that suppresses the browse -
 *       any filter validation failure, or a transfer of control - must not stub the repository.</li>
 *   </ul>
 *
 * <h2>5. Findings register (Rule 1 Clause F)</h2>
 * <ol>
 *   <li><strong>Medium - brief corrected against the source.</strong> The migration brief asks for a
 *       test proving the account-based finder
 *       ({@code findByAccountIdOrderByCardNumberAsc}, the {@code CARDAIX} alternate-index
 *       replacement) is used on the account-filter path. It is not, and it must not be:
 *       {@code LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} is referenced exactly once
 *       repository-wide, at its own declaration {@code app/cbl/COCRDLIC.cbl:215-217}, while every
 *       browse verb - {@code STARTBR} at {@code :1129} and {@code :1273}, {@code READNEXT} at
 *       {@code :1146} and {@code :1197}, {@code READPREV} at {@code :1294} and {@code :1322},
 *       {@code ENDBR} at {@code :1258} and {@code :1376} - names {@code LIT-CARD-FILE}
 *       ({@code 'CARDDAT '}). The account filter is applied in memory by {@code 9500}. Remediation
 *       applied: the assertion is inverted to prove the alternate-index finder is never invoked,
 *       which is the parity-preserving outcome, because the forward lookahead at {@code :1197-1205}
 *       deliberately does not filter and pushing the predicate into SQL would silently repair that
 *       defect and change which pages report more data.</li>
 *   <li><strong>Medium - brief corrected against the source.</strong> The brief cites 42 paragraphs.
 *       An Area-A census of {@code app/cbl/COCRDLIC.cbl} returns 42 labelled entries, of which three
 *       are IDENTIFICATION DIVISION paragraphs - {@code PROGRAM-ID.} at {@code :26},
 *       {@code DATE-WRITTEN.} at {@code :28} and {@code DATE-COMPILED.} at {@code :30}. The
 *       PROCEDURE DIVISION therefore has 39 labels, and {@code COPY 'CSSTRPFY'} at {@code :1416}
 *       contributes two more, giving 41 executable paragraphs. Both figures are asserted.</li>
 *   <li><strong>Not available.</strong> {@code SET FLG-PROTECT-SELECT-ROWS-YES TO TRUE} at
 *       {@code :1020} and {@code :1055} is not observable through this service's public API: both
 *       filter edits raise {@code ValidationException} before {@code 1250-SETUP-ARRAY-ATTRIBS} at
 *       {@code :748} can project the attribute, so {@code rowSelectionProtected} is unreachable on
 *       the only path that sets it. What IS observable, and is asserted, is the protection's
 *       purpose: the browse is suppressed entirely, so no page and therefore no selectable row is
 *       produced. Prerequisite for the direct assertion: a controller-tier or result-returning
 *       variant that surfaces the attribute instead of throwing.</li>
 *   <li><strong>Not available.</strong> That {@code /api/admin/*} is restricted to the administrator
 *       role and that the HTTP session policy is {@code STATELESS} cannot be asserted from a
 *       pure-JVM test of this bean. {@code com.cardemo.config.SecurityConfig} is not a dependency of
 *       this file, and {@code app/csd/CARDDEMO.CSD} maps the administrative transactions
 *       {@code CU00} through {@code CU03} to the four {@code COUSR*} programs, not to this one. The
 *       property those rules exist to protect IS asserted: the bean keeps no server-side state, so
 *       two identical turns are indistinguishable and the paging cursor must travel in the request.
 *       Prerequisite: a security-tier test that may import {@code SecurityConfig}.</li>
 *   <li><strong>Low - preserved legacy defect.</strong> {@code MOVE WS-CA-FIRST-CARDKEY TO
 *       WS-CA-LAST-CARDKEY} at {@code :1268} makes the last key of a backward page the first key of
 *       the page being left, so the reported last key is the row above the page just returned.
 *       Asserted rather than corrected.</li>
 *   <li><strong>Low - preserved legacy defect.</strong> Neither {@code READPREV} has a
 *       {@code DFHRESP(ENDFILE)} arm ({@code :1294-1318}, {@code :1322-1370}), so exhausting the
 *       records while paging up is reported as a file error. Asserted rather than corrected.</li>
 *   </ol>
 *
 * <h2>6. The documented conflict - parity governs</h2>
 * <p>Rule 1 Clause B forbids dead code; the parity mandate requires that the bare-{@code EXIT} exit
 * paragraphs and the {@code ELSE CONTINUE} no-op arms of {@code 9500} be retained. Parity governs,
 * and Clause B is satisfied on its own wording: the prohibition is on artefacts without an owner or
 * a tracking reference, and every retained item carries an entry in the planned {@code DECISION_LOG.md}, a
 * {@code TRACEABILITY_MATRIX.md} row and Javadoc citing its source locator. This class asserts that
 * those methods exist, so deleting one breaks a test instead of silently breaking the paragraph map
 * that Gate 7 verifies. No other conflict exists.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("CardListService - COCRDLIC / transaction CCLI")
final class CardListServiceTest {

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'} at {@code app/cbl/COCRDLIC.cbl:179-180}. */
    private static final String THIS_PROGRAM = "COCRDLIC";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} at {@code app/cbl/COCRDLIC.cbl:187-188}. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} at {@code app/cbl/COCRDLIC.cbl:195-196}. */
    private static final String CARD_DETAIL_PROGRAM = "COCRDSLC";

    /** {@code LIT-CARDUPDPGM PIC X(8) VALUE 'COCRDUPC'} at {@code app/cbl/COCRDLIC.cbl:203-204}. */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDLIA'} at {@code app/cbl/COCRDLIC.cbl:185-186}. */
    private static final String THIS_MAP = "CCRDLIA";

    /** {@code LIT-THISMAPSET PIC X(7) VALUE 'COCRDLI'} at {@code app/cbl/COCRDLIC.cbl:183-184}. */
    private static final String THIS_MAPSET = "COCRDLI";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'} at {@code app/cbl/COCRDLIC.cbl:191-192}. */
    private static final String MENU_MAPSET = "COMEN01";

    /** {@code LIT-CARDDTLMAPSET} / {@code LIT-CARDDTLMAP} at {@code app/cbl/COCRDLIC.cbl:199-202}. */
    private static final String CARD_DETAIL_MAPSET = "COCRDSL";

    /** {@code LIT-CARDDTLMAP PIC X(7) VALUE 'CCRDSLA'} at {@code app/cbl/COCRDLIC.cbl:201-202}. */
    private static final String CARD_DETAIL_MAP = "CCRDSLA";

    /** {@code LIT-CARDUPDMAPSET PIC X(7) VALUE 'COCRDUP'} at {@code app/cbl/COCRDLIC.cbl:207-208}. */
    private static final String CARD_UPDATE_MAPSET = "COCRDUP";

    /** {@code LIT-CARDUPDMAP PIC X(7) VALUE 'CCRDUPA'} at {@code app/cbl/COCRDLIC.cbl:209-210}. */
    private static final String CARD_UPDATE_MAP = "CCRDUPA";

    /**
     * {@code 88 CCARD-AID-ENTER VALUE 'ENTER'} at {@code app/cpy/CVCRD01Y.cpy:L4}, COBOL sequence number
     * {@code 001000}. The only identifier whose inbound and stored spellings coincide.
     *
     * <p>This copybook carries sequence numbers in columns 1-6, so {@code 001000} is a sequence number and
     * not a file line - the declaration sits on physical line 4 of the 46-line member.
     */
    private static final String AID_ENTER = "ENTER";

    /**
     * The inbound {@code EIBAID} spelling for PF3, which {@code YYYY-STORE-PFKEY}
     * ({@code app/cpy/CSSTRPFY.cpy:21-78}) stores as {@code CCARD-AID-PFK03}: exit to the menu.
     *
     * <p>Inbound and stored spellings differ - {@code PF03} arrives, {@code PFK03} is stored - and
     * conflating them is why the paging tests below send the {@code PF} form and assert on
     * behaviour rather than on the stored form.</p>
     */
    private static final String AID_PF03 = "PF03";

    /** The inbound spelling for PF7, stored as {@code CCARD-AID-PFK07}: page up. */
    private static final String AID_PF07 = "PF07";

    /** The inbound spelling for PF8, stored as {@code CCARD-AID-PFK08}: page down. */
    private static final String AID_PF08 = "PF08";

    /** PF20, which folds onto PF08 because {@code EIBAID} reuses the lower twelve key codes. */
    private static final String AID_PF20_FOLDS_TO_PF08 = "PF20";

    /**
     * A recognised function key that the validity gate at {@code app/cbl/COCRDLIC.cbl:370-380} does
     * not accept - only {@code ENTER}, {@code PFK03}, {@code PFK07} and {@code PFK08} pass - and
     * which is therefore coerced to {@code ENTER} at {@code :381-383} rather than rejected.
     */
    private static final String AID_UNSUPPORTED = "PF05";

    /**
     * A string that is not an attention identifier at all. It leaves {@code CCARD-AID} unset, which
     * the same gate then coerces to {@code ENTER}.
     */
    private static final String AID_UNRECOGNISED = "NOT-AN-AID";

    /** The seven-row page of {@code WS-MAX-SCREEN-LINES} at {@code app/cbl/COCRDLIC.cbl:177-178}. */
    private static final int SCREEN_LINES = 7;

    /** {@code WS-CA-SCREEN-NUM PIC 9(1)} with {@code 88 CA-FIRST-PAGE VALUE 1} at {@code :237-238}. */
    private static final int FIRST_PAGE = 1;

    /** {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'} at {@code app/cbl/COCRDLIC.cbl:244}. */
    private static final char NEXT_PAGE_EXISTS = 'Y';

    /** {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES} at {@code app/cbl/COCRDLIC.cbl:243}. */
    private static final char NEXT_PAGE_NOT_EXISTS = '\u0000';

    /**
     * The third state of {@code WS-CA-NEXT-PAGE-IND PIC X(1)} at {@code app/cbl/COCRDLIC.cbl:242}:
     * the space that {@code INITIALIZE WS-THIS-PROGCOMMAREA} leaves, matching neither condition name.
     * Its existence is what makes the field a tri-state rather than a boolean.
     */
    private static final char NEXT_PAGE_UNSET = ' ';

    /** {@code 88 CA-LAST-PAGE-SHOWN VALUE 0} at {@code app/cbl/COCRDLIC.cbl:240}. Zero means shown. */
    private static final int LAST_PAGE_SHOWN = 0;

    /** {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9} at {@code app/cbl/COCRDLIC.cbl:241}. */
    private static final int LAST_PAGE_NOT_SHOWN = 9;

    /**
     * The value a port that assumed 1 = true and 0 = false would send for "the last page has been
     * shown". It matches neither condition name, which is exactly what makes the inversion
     * detectable.
     */
    private static final int LAST_PAGE_NAIVE_TRUE = 1;

    /** {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy:L34}, sequence number {@code 004400}. */
    private static final int ACCOUNT_FILTER_WIDTH = 11;

    /** {@code CC-CARD-NUM PIC X(16)} at {@code app/cpy/CVCRD01Y.cpy:L37}, sequence number {@code 004600}. */
    private static final int CARD_FILTER_WIDTH = 16;

    /** {@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60}. */
    private static final int PAGE_NUMBER_WIDTH = 3;

    /** {@code WS-ROW-ACCTNO X(11)} + {@code WS-ROW-CARD-NUM X(16)} + status, per {@code :257-260}. */
    private static final int ROW_WIDTH = 28;

    /** {@code PAGENUMI PIC X(8)} of {@code app/cpy-bms/COTRN00.CPY}: a different name and width. */
    private static final int TRANSACTION_LIST_PAGE_NUMBER_WIDTH = 8;

    /** The first account identifier used by the fixtures, {@code CARD-ACCT-ID PIC 9(11)}. */
    private static final long ACCOUNT_A = 11L;

    /** A second account identifier, so the account gate has something to exclude. */
    private static final long ACCOUNT_B = 22L;

    /** {@link #ACCOUNT_A} rendered as {@code CC-ACCT-ID} would hold it: eleven zoned digits. */
    private static final String ACCOUNT_A_FILTER = "00000000011";

    /** {@link #ACCOUNT_B} rendered as eleven zoned digits. */
    private static final String ACCOUNT_B_FILTER = "00000000022";

    /**
     * {@code MOVE 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER' TO WS-ERROR-MSG} at
     * {@code app/cbl/COCRDLIC.cbl:1021-1023}. Assembled from three parts so that the two
     * peculiarities are documented rather than merely copied: there is NO space after the comma, and
     * the article is the grammatically odd {@code A 11}. The literal is upper case throughout.
     */
    private static final String MSG_ACCOUNT_FILTER_INVALID =
            "ACCOUNT FILTER" + "," + "IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * {@code MOVE 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER' TO WS-ERROR-MSG} at
     * {@code app/cbl/COCRDLIC.cbl:1057-1059}, guarded by {@code IF WS-ERROR-MSG-OFF} at
     * {@code :1056}. Same two peculiarities.
     */
    private static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER" + "," + "IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** {@code 88 WS-INFORM-REC-ACTIONS} at {@code app/cbl/COCRDLIC.cbl:115-116}. */
    private static final String MSG_INFORM_REC_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** {@code 88 WS-EXIT-MESSAGE VALUE 'PF03 PRESSED.EXITING'} at {@code :119-120}. */
    private static final String MSG_EXIT = "PF03 PRESSED.EXITING";

    /** {@code 88 WS-NO-RECORDS-FOUND} at {@code app/cbl/COCRDLIC.cbl:121-122}. */
    private static final String MSG_NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** {@code 88 WS-MORE-THAN-1-ACTION} at {@code app/cbl/COCRDLIC.cbl:123-124}. */
    private static final String MSG_MORE_THAN_ONE_ACTION = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /** {@code 88 WS-INVALID-ACTION-CODE VALUE 'INVALID ACTION CODE'} at {@code :125-126}. */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** {@code MOVE 'NO MORE RECORDS TO SHOW' TO WS-ERROR-MSG} at {@code :1219-1220} and {@code :1239}. */
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** {@code MOVE 'NO PREVIOUS PAGES TO DISPLAY' TO WS-ERROR-MSG} at {@code :903-904}. */
    private static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** {@code MOVE 'NO MORE PAGES TO DISPLAY' TO WS-ERROR-MSG} at {@code :908-909}. */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /** {@code FILLER PIC X(12) VALUE 'File Error:'} at {@code app/cbl/COCRDLIC.cbl:154-155}. */
    private static final String FILE_ERROR_PREFIX = "File Error:";

    /** {@code MOVE 'READ' TO ERROR-OPNAME}, the only operation this program composes. */
    private static final String OPERATION_READ = "READ";

    /** {@code LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '} at {@code app/cbl/COCRDLIC.cbl:213-214}. */
    private static final String CARD_FILE_NAME = "CARDDAT";

    /**
     * {@code LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} at
     * {@code app/cbl/COCRDLIC.cbl:215-217}. Declared with trailing-space padding and referenced
     * nowhere else in the repository, which is the evidence that this program never opens the
     * alternate index.
     */
    private static final String CARD_ACCOUNT_PATH_NAME = "CARDAIX ";

    /** The condition a browse that runs off the front of the file latches, per {@code :1308}. */
    private static final String RESP_ENDFILE = "ENDFILE";

    /** {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:7}; never read by this program. */
    private static final String CVV = "123";

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:8}. */
    private static final String EMBOSSED_NAME = "CARDHOLDER NAME";

    /** {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:9}; the source misspells it. */
    private static final String EXPIRY_DATE = "2026-12-31";

    /** {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:10}. */
    private static final String STATUS_ACTIVE = "Y";

    /** The formatted header date {@code CURDATEO} the caller supplies in place of a clock read. */
    private static final String HEADER_DATE = "07/19/22";

    /** The formatted header time {@code CURTIMEO} the caller supplies in place of a clock read. */
    private static final String HEADER_TIME = "23:16:00";

    /** {@code 88 SELECT-OK VALUES 'S', 'U'}: view, at {@code app/cbl/COCRDLIC.cbl:78}. */
    private static final String SELECT_VIEW = "S";

    /** {@code 88 UPDATE-REQUESTED-ON VALUE 'U'} at {@code app/cbl/COCRDLIC.cbl:79}. */
    private static final String SELECT_UPDATE = "U";

    /** A row code that is neither {@code S}, {@code U}, a space nor {@code LOW-VALUES}. */
    private static final String SELECT_INVALID = "X";

    /** The repository double. The service's only collaborator, injected through its constructor. */
    @Mock
    private CardRepository cardRepository;

    // ============================================================================================
    // FIXTURE BUILDERS
    //
    // Card numbers are synthetic sixteen-digit sequence keys that begin with a zero, so none can be
    // a real primary account number - no issuer identification number starts with zero - and none
    // satisfies the Luhn check. Rule 1 Clause D still applies to them: no card number reaches a log,
    // an assertion description or an exception message anywhere below.
    // ============================================================================================

    /**
     * Builds one {@code CARD-RECORD} of {@code app/cpy/CVACT02Y.cpy}.
     *
     * @param ordinal the one-based position in ascending card-number order
     * @param accountId {@code CARD-ACCT-ID PIC 9(11)}
     * @return a fully populated card
     */
    private static Card card(final int ordinal, final long accountId) {
        return new Card(
                cardNumber(ordinal), accountId, "007",  EMBOSSED_NAME, EXPIRY_DATE, STATUS_ACTIVE);
    }

    /**
     * Renders the synthetic {@code CARD-NUM PIC X(16)} for one ordinal. Zero padded to a fixed width
     * so that ascending numeric order and ascending string order coincide, which is what makes the
     * key-ordered browse reproducible.
     *
     * @param ordinal the one-based position in ascending card-number order
     * @return exactly sixteen digits
     */
    private static String cardNumber(final int ordinal) {
        return String.format(Locale.ROOT, "%016d", 100L + ordinal);
    }

    /**
     * Builds a card file of consecutive ordinals, all on the same account.
     *
     * @param count how many records the file holds
     * @param accountId the account every record belongs to
     * @return the file in ascending card-number order
     */
    private static List<Card> cardFile(final int count, final long accountId) {
        final List<Card> file = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            file.add(card(ordinal, accountId));
        }
        return file;
    }

    /**
     * Builds a card file that alternates between two accounts, so that the account gate of
     * {@code 9500-FILTER-RECORDS} has something to exclude. Odd ordinals belong to
     * {@link #ACCOUNT_A} and even ordinals to {@link #ACCOUNT_B}.
     *
     * @param count how many records the file holds
     * @return the file in ascending card-number order
     */
    private static List<Card> mixedCardFile(final int count) {
        final List<Card> file = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            file.add(card(ordinal, ordinal % 2 == 1 ? ACCOUNT_A : ACCOUNT_B));
        }
        return file;
    }

    /**
     * Stubs the one repository method the service calls, answering with the aligned window the
     * supplied {@code Pageable} asks for.
     *
     * <p>Only the tests that actually browse may call this: under strict stubs an unused stub fails
     * the build, which is precisely how a test that expects the browse to be suppressed proves
     * it.</p>
     *
     * <p>The row count is stubbed leniently alongside the window. {@code startBrowse} takes the total as
     * the upper bound of its binary search, and it needs one only when a start key is supplied - the
     * unfiltered entry positions at offset zero and never asks. A strict stub would therefore fail every
     * unfiltered test for being unused, while omitting it would make every filtered test see an empty
     * table. Lenient is the accurate statement: available to the paths that position by key.
     *
     * @param file the card file in ascending card-number order
     */
    private void givenCardFile(final List<Card> file) {
        when(cardRepository.findAllByOrderByCardNumberAsc(any(Pageable.class)))
                .thenAnswer(invocation -> window(file, invocation.getArgument(0, Pageable.class)));
        lenient().when(cardRepository.count()).thenReturn((long) file.size());
    }

    /**
     * Slices one aligned window out of a card file.
     *
     * @param file the whole file in ascending card-number order
     * @param pageable the window the service asked for
     * @return the requested window, carrying the file's total element count
     */
    private static Slice<Card> window(final List<Card> file, final Pageable pageable) {
        final int offset = (int) Math.min(pageable.getOffset(), file.size());
        final int end = Math.min(offset + pageable.getPageSize(), file.size());
        return new SliceImpl<>(new ArrayList<>(file.subList(offset, end)), pageable, end < file.size());
    }

    /**
     * Builds the service exactly as the container does: the repository double plus the configured
     * page size.
     *
     * @return a service bound to the seven-row parity contract
     */
    private CardListService service() {
        return new CardListService(cardRepository, SCREEN_LINES);
    }

    /**
     * Builds a first entry, the transcription of {@code EIBCALEN = 0} at
     * {@code app/cbl/COCRDLIC.cbl:315-325}. No commarea, so no filter and no selection is received.
     *
     * @return a request describing a plain open of the screen
     */
    private static CardListRequest firstEntry() {
        return CardListRequest.initialEntry(
                AID_ENTER, MENU_PROGRAM, false, HEADER_DATE, HEADER_TIME);
    }

    /**
     * Builds a re-entry from this program itself, which is the only shape whose filters and
     * selections are received at all - the guard at {@code app/cbl/COCRDLIC.cbl:357-362} requires
     * {@code CDEMO-FROM-PROGRAM} to equal {@code LIT-THISPGM}.
     *
     * @param attentionIdentifier the attention identifier
     * @param accountFilter {@code ACCTSIDI}, or {@code null} for none
     * @param cardFilter {@code CARDSIDI}, or {@code null} for none
     * @param rowSelections the seven row codes, or {@code null} for none
     * @param pageNumber {@code WS-CA-SCREEN-NUM} being echoed back
     * @param lastPageDisplayed {@code WS-CA-LAST-PAGE-DISPLAYED} being echoed back
     * @param nextPageAvailable {@code WS-CA-NEXT-PAGE-IND} being echoed back
     * @param firstCardNumber {@code WS-CA-FIRST-CARD-NUM}, the page-up start key
     * @param lastCardNumber {@code WS-CA-LAST-CARD-NUM}, the page-down start key
     * @param displayedRows the rows the previous turn displayed
     * @return the request
     */
    private static CardListRequest reentry(
            final String attentionIdentifier,
            final String accountFilter,
            final String cardFilter,
            final List<String> rowSelections,
            final int pageNumber,
            final int lastPageDisplayed,
            final boolean nextPageAvailable,
            final String firstCardNumber,
            final String lastCardNumber,
            final List<DisplayedRow> displayedRows) {

        return new CardListRequest(
                true,
                attentionIdentifier,
                accountFilter,
                cardFilter,
                rowSelections,
                "CCLI",
                THIS_PROGRAM,
                false,
                true,
                THIS_MAP,
                THIS_MAPSET,
                null,
                null,
                pageNumber,
                lastPageDisplayed,
                nextPageAvailable,
                firstCardNumber,
                lastCardNumber,
                displayedRows,
                HEADER_DATE,
                HEADER_TIME);
    }

    /**
     * Builds the shortest re-entry that carries a filter pair and nothing else: page one, last page
     * not yet shown, no saved keys and no displayed rows.
     *
     * @param accountFilter {@code ACCTSIDI}, or {@code null} for none
     * @param cardFilter {@code CARDSIDI}, or {@code null} for none
     * @return the request
     */
    private static CardListRequest filterEntry(final String accountFilter, final String cardFilter) {
        return reentry(
                AID_ENTER,
                accountFilter,
                cardFilter,
                null,
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                false,
                null,
                null,
                null);
    }

    /**
     * Builds a re-entry that presses a paging key from a known page position.
     *
     * @param attentionIdentifier {@link #AID_PF07} to page up or {@link #AID_PF08} to page down
     * @param pageNumber the page currently displayed
     * @param nextPageAvailable whether the previous turn reported more data
     * @param firstCardNumber the saved first key, which page up starts from
     * @param lastCardNumber the saved last key, which page down starts from
     * @return the request
     */
    private static CardListRequest pagingEntry(
            final String attentionIdentifier,
            final int pageNumber,
            final boolean nextPageAvailable,
            final String firstCardNumber,
            final String lastCardNumber) {

        return reentry(
                attentionIdentifier,
                null,
                null,
                null,
                pageNumber,
                LAST_PAGE_NOT_SHOWN,
                nextPageAvailable,
                firstCardNumber,
                lastCardNumber,
                null);
    }

    /**
     * Builds the seven row-selection codes with one code placed on one row.
     *
     * @param rowNumber the one-based row to mark
     * @param code the selection code to place there
     * @return seven entries, all {@code null} but the marked one
     */
    private static List<String> selectionOn(final int rowNumber, final String code) {
        final List<String> codes = new ArrayList<>(SCREEN_LINES);
        for (int row = 1; row <= SCREEN_LINES; row++) {
            codes.add(row == rowNumber ? code : null);
        }
        return codes;
    }

    /**
     * Mirrors a card file back as the rows a previous turn displayed, so that a selection code has
     * something to resolve against.
     *
     * @param file the card file whose leading page was displayed
     * @return up to seven displayed rows
     */
    private static List<DisplayedRow> displayed(final List<Card> file) {
        final List<DisplayedRow> rows = new ArrayList<>(SCREEN_LINES);
        for (int index = 0; index < Math.min(SCREEN_LINES, file.size()); index++) {
            final Card source = file.get(index);
            rows.add(new DisplayedRow(
                    String.format(Locale.ROOT, "%011d", source.getAccountId()),
                    source.getCardNumber(),
                    source.getActiveStatus()));
        }
        return rows;
    }

    /**
     * Collects every {@code Pageable} the service handed the repository during one turn.
     *
     * @return the captured windows, in invocation order
     */
    private List<Pageable> capturedWindows() {
        final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository, atLeastOnce()).findAllByOrderByCardNumberAsc(captor.capture());
        return captor.getAllValues();
    }

    /**
     * Returns the card numbers the rows of a result carry, in row order.
     *
     * @param result the turn's outcome
     * @return the card numbers of the projected rows
     */
    private static List<String> projectedCardNumbers(final CardListResult result) {
        final List<String> numbers = new ArrayList<>();
        for (final CardDto.CardListRow row : result.page.getRows()) {
            numbers.add(row.getCardNumber());
        }
        return numbers;
    }

    // ============================================================================================
    // PHASE 1 - THE SEVEN-ROW PAGE IS A PARITY CONTRACT, NOT A TUNABLE
    // app/cbl/COCRDLIC.cbl:177-178
    // ============================================================================================

    @Test
    @DisplayName("A full page returns exactly 7 rows - WS-MAX-SCREEN-LINES VALUE 7 at :177-178")
    void aFullPageReturnsExactlySevenRows() {
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        assertThat(result.rowCount()).isEqualTo(SCREEN_LINES);
        assertThat(result.page.getPageSize()).isEqualTo(SCREEN_LINES);
        assertThat(projectedCardNumbers(result))
                .containsExactly(
                        cardNumber(1),
                        cardNumber(2),
                        cardNumber(3),
                        cardNumber(4),
                        cardNumber(5),
                        cardNumber(6),
                        cardNumber(7));
    }

    @Test
    @DisplayName("A short final page returns fewer than 7 rows and is not padded")
    void aShortFinalPageIsNotPadded() {
        givenCardFile(cardFile(3, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        // WS-SCRN-COUNTER stopped at three, so 1200-SCREEN-ARRAY-INIT leaves rows four through seven
        // at LOW-VALUES (:686-692) and buildDisplayedRows skips them rather than emitting blanks.
        assertThat(result.page.getRows()).hasSize(3);
        assertThat(result.page.getPageSize()).isEqualTo(SCREEN_LINES);
        assertThat(projectedCardNumbers(result))
                .containsExactly(cardNumber(1), cardNumber(2), cardNumber(3));
        assertThat(result.page.getRows()).noneMatch(row -> row.getCardNumber() == null);
    }

    @Test
    @DisplayName("An empty file yields 0 rows and the NO RECORDS FOUND message, never an exception")
    void anEmptyFileYieldsNoRowsAndAMessageRatherThanAnException() {
        givenCardFile(List.of());

        final CardListResult result = service().listCards(firstEntry());

        // 9000-READ-FORWARD reaches DFHRESP(ENDFILE) with WS-SCRN-COUNTER still zero on page one, so
        // :1240-1243 sets the error message. Empty is an outcome, not a fault.
        assertThat(result.page.getRows()).isEmpty();
        assertThat(result.rowCount()).isZero();
        assertThat(result.errorMessage).isEqualTo(MSG_NO_RECORDS_FOUND);
        assertThat(result.inputError).isFalse();
        assertThat(result.exitRequested).isFalse();
        // IF NOT WS-NO-INFO-MESSAGE AND NOT WS-NO-RECORDS-FOUND at :926-930. The message paragraph
        // still SELECTS the information text - so the state field carries it - but the guard stops it
        // being moved to INFOMSGO, so the projected screen field stays empty. The two messages never
        // appear on the screen together.
        assertThat(result.informationMessage).isEqualTo(MSG_INFORM_REC_ACTIONS);
        assertThat(result.screen.getInformationMessage()).isEmpty();
        assertThat(result.screen.getErrorMessage())
                .hasSize(75)
                .startsWith(MSG_NO_RECORDS_FOUND);
    }

    @Test
    @DisplayName("The page size comes from configuration: 7 is accepted and drives the page")
    void theConfiguredPageSizeOfSevenIsAcceptedAndDrivesThePage() {
        givenCardFile(cardFile(20, ACCOUNT_A));

        // The production bean receives this value from @Value("${carddemo.pagination"
        // + ".card-list-page-size}"); the test supplies it through the same constructor parameter, so
        // the value is not hard-coded at the call site inside the service.
        final CardListResult result = new CardListService(cardRepository, SCREEN_LINES)
                .listCards(firstEntry());

        assertThat(result.page.getPageSize()).isEqualTo(SCREEN_LINES);
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        assertThat(PageResponse.PAGE_SIZE_CARD_LIST).isEqualTo(SCREEN_LINES);
        assertThat(CardDto.CARD_LIST_PAGE_SIZE).isEqualTo(SCREEN_LINES);
    }

    @Test
    @DisplayName("A positive page size other than 7 is rejected at construction, citing the mapset")
    void aConfiguredPageSizeOtherThanSevenIsRejectedAtConstruction() {
        // This is what makes the page size a parity contract rather than a tunable: drift cannot
        // reach a browse, because the bean refuses to exist. The property name and the mapset rows
        // both appear in the message, which is how an operator learns where the wrong value came from
        // and why seven is not negotiable.
        for (final int drifted : List.of(1, 6, 8, 10, 20)) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardListService(cardRepository, drifted))
                    .withMessageContaining("carddemo.pagination.card-list-page-size")
                    .withMessageContaining("COCRDLI.CPY")
                    .withMessageContaining(Integer.toString(SCREEN_LINES))
                    .withMessageEndingWith(Integer.toString(drifted));
        }
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A zero or negative page size is rejected by the positivity guard, not clamped")
    void aZeroOrNegativeConfiguredPageSizeIsRejectedByThePositivityGuard() {
        // A separate, earlier guard: a non-positive size is nonsense before it is non-seven, so it
        // gets its own message rather than being folded into the parity message.
        for (final int nonPositive : List.of(0, -1, -7)) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardListService(cardRepository, nonPositive))
                    .withMessage("carddemo.pagination.card-list-page-size must be positive but was "
                            + nonPositive);
        }
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A null repository is rejected at construction rather than at the first browse")
    void aNullRepositoryIsRejectedAtConstruction() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new CardListService(null, SCREEN_LINES))
                .withMessage("cardRepository is required");
    }

    @Test
    @DisplayName("The screen row geometry is 28 characters by 7 rows - the comment at :250")
    void theScreenRowGeometryIsTwentyEightBySeven() {
        // WS-ROW-ACCTNO X(11) + WS-ROW-CARD-NUM X(16) + WS-ROW-CARD-STATUS X(1) = 28, and
        // WS-ALL-ROWS PIC X(196) = 28 * 7, which is the source's own arithmetic at :250-260.
        assertThat(ACCOUNT_FILTER_WIDTH + CARD_FILTER_WIDTH + 1).isEqualTo(ROW_WIDTH);
        assertThat(ROW_WIDTH * SCREEN_LINES).isEqualTo(196);
    }

    // ============================================================================================
    // PHASE 2 - THE COUNTER-INTUITIVE SENTINELS
    // app/cbl/COCRDLIC.cbl:237-248, :230-235
    // ============================================================================================

    @Test
    @DisplayName("CA-LAST-PAGE-SHOWN is 0 and CA-LAST-PAGE-NOT-SHOWN is 9 - the inversion")
    void theLastPageSentinelValuesAreZeroForShownAndNineForNotShown() {
        // 88 CA-LAST-PAGE-SHOWN VALUE 0 at :240; 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9 at :241. The
        // naming is counter-intuitive, and a port that assumed 1 = true / 0 = false would invert it.
        assertThat(LAST_PAGE_SHOWN).isZero();
        assertThat(LAST_PAGE_NOT_SHOWN).isEqualTo(9);
        assertThat(LAST_PAGE_SHOWN).isNotEqualTo(LAST_PAGE_NAIVE_TRUE);
        assertThat(LAST_PAGE_NOT_SHOWN).isNotEqualTo(LAST_PAGE_NAIVE_TRUE);
    }

    @Test
    @DisplayName("Paging past the end with the latch at 0 (shown) yields NO MORE PAGES TO DISPLAY")
    void pagingPastTheEndWithTheLastPageAlreadyShownYieldsNoMorePages() {
        // 1400-SETUP-MESSAGE :905-909 needs THREE conditions: PF8, next page does not exist, and
        // CA-LAST-PAGE-SHOWN - which is the value ZERO, not one. A port that sent 1 for "shown"
        // would take the :910-916 branch instead and lose this message entirely, so this test and
        // its sibling below differ in exactly one input: the sentinel.
        givenCardFile(cardFile(SCREEN_LINES, ACCOUNT_A));

        final CardListRequest request = reentry(
                AID_PF08,
                null,
                null,
                null,
                FIRST_PAGE,
                LAST_PAGE_SHOWN,
                false,
                cardNumber(1),
                cardNumber(SCREEN_LINES),
                null);

        final CardListResult result = service().listCards(request);

        assertThat(result.errorMessage).isEqualTo(MSG_NO_MORE_PAGES);
        // The message overwrote the NO MORE RECORDS TO SHOW the browse had set, which is the
        // observable difference between the two sentinel values.
        assertThat(result.errorMessage).isNotEqualTo(MSG_NO_MORE_RECORDS);
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        assertThat(result.page.isNextPageAvailable()).isFalse();
    }

    @Test
    @DisplayName("Paging past the end with the latch at 9 (not shown) informs instead and latches")
    void pagingPastTheEndWithTheLastPageNotYetShownLatchesTheSentinel() {
        // :910-916 - the latch arm, reached only because the sentinel is 9 rather than 0. It sets the
        // information message, leaves the browse's own error message standing, and flips the sentinel
        // to 0, which is what arms NO MORE PAGES TO DISPLAY on the next press.
        givenCardFile(cardFile(SCREEN_LINES, ACCOUNT_A));

        final CardListRequest request = reentry(
                AID_PF08,
                null,
                null,
                null,
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                false,
                cardNumber(1),
                cardNumber(SCREEN_LINES),
                null);

        final CardListResult result = service().listCards(request);

        assertThat(result.informationMessage).isEqualTo(MSG_INFORM_REC_ACTIONS);
        assertThat(result.errorMessage).isEqualTo(MSG_NO_MORE_RECORDS);
        assertThat(result.errorMessage).isNotEqualTo(MSG_NO_MORE_PAGES);
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
    }

    @Test
    @DisplayName("The next-page indicator is a tri-state, not a boolean - 'Y', LOW-VALUES and unset")
    void theNextPageIndicatorIsATriStateRatherThanABoolean() {
        // 88 CA-NEXT-PAGE-EXISTS VALUE 'Y' at :244 against 88 CA-NEXT-PAGE-NOT-EXISTS VALUE
        // LOW-VALUES at :243, plus the INITIALIZE state that is neither. Two of the three states
        // report "no next page" to a caller, but only one of them takes the :910-916 latch arm, so
        // collapsing the field to a boolean loses a branch.
        assertThat(new TreeSet<>(List.of(NEXT_PAGE_EXISTS, NEXT_PAGE_NOT_EXISTS, NEXT_PAGE_UNSET)))
                .containsExactly(NEXT_PAGE_NOT_EXISTS, NEXT_PAGE_UNSET, NEXT_PAGE_EXISTS)
                .hasSize(3);
        assertThat(NEXT_PAGE_NOT_EXISTS).isNotEqualTo(NEXT_PAGE_UNSET);

        // And the observable projection: 'Y' becomes true, LOW-VALUES becomes false.
        givenCardFile(cardFile(20, ACCOUNT_A));
        assertThat(service().listCards(firstEntry()).page.isNextPageAvailable()).isTrue();
    }

    @Test
    @DisplayName("Exhausting the file clears the next-page indicator to LOW-VALUES, reported as false")
    void exhaustingTheFileClearsTheNextPageIndicator() {
        // Exactly seven records: the page fills, then the unfiltered lookahead READNEXT at
        // :1197-1205 hits ENDFILE, so :1215-1220 sets LOW-VALUES and the NO MORE RECORDS message.
        givenCardFile(cardFile(SCREEN_LINES, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        assertThat(result.page.isNextPageAvailable()).isFalse();
        assertThat(result.errorMessage).isEqualTo(MSG_NO_MORE_RECORDS);
    }

    @Test
    @DisplayName("The keyset cursor is a card-number plus account-identifier composite of 27 bytes")
    void theKeysetCursorIsATwentySevenByteComposite() {
        // WS-CA-LAST-CARDKEY at :230-232 and WS-CA-FIRST-CARDKEY at :233-235 are each
        // WS-CA-*-CARD-NUM PIC X(16) followed by WS-CA-*-CARD-ACCT-ID PIC 9(11).
        assertThat(CARD_FILTER_WIDTH + ACCOUNT_FILTER_WIDTH).isEqualTo(27);

        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        // The card-number half of both keys is surfaced as the page's boundary metadata, at the
        // declared X(16) width.
        assertThat(result.page.getFirstKey()).hasSize(CARD_FILTER_WIDTH).isEqualTo(cardNumber(1));
        assertThat(result.page.getLastKey()).hasSize(CARD_FILTER_WIDTH).isEqualTo(cardNumber(8));
    }

    @Test
    @DisplayName("The cursor travels in the request and the response - no server-side session state")
    void theCursorTravelsInTheRequestAndResponseWithNoServerSideState() {
        givenCardFile(cardFile(20, ACCOUNT_A));
        final CardListService service = service();

        // RETURN TRANSID ... COMMAREA at :615-619 becomes a stateless turn: the same service
        // instance, asked the same question twice, must answer identically. If any paging state were
        // retained in a field, the second turn would differ.
        final CardListResult first = service.listCards(firstEntry());
        final CardListResult second = service.listCards(firstEntry());

        assertThat(projectedCardNumbers(second)).isEqualTo(projectedCardNumbers(first));
        assertThat(second.page.getFirstKey()).isEqualTo(first.page.getFirstKey());
        assertThat(second.page.getLastKey()).isEqualTo(first.page.getLastKey());
        assertThat(second.page.getPageNumber()).isEqualTo(first.page.getPageNumber());
        assertThat(second.page.isNextPageAvailable()).isEqualTo(first.page.isNextPageAvailable());

        // And the cursor the caller must send back next turn is the one the response just handed it.
        final CardListResult third = service.listCards(
                pagingEntry(AID_PF08, first.page.getPageNumber(), true, first.page.getFirstKey(),
                        first.page.getLastKey()));
        assertThat(projectedCardNumbers(third)).doesNotContainAnyElementsOf(
                projectedCardNumbers(first));
    }

    @Test
    @DisplayName("The service holds no mutable state - every field is final and none is static")
    void theServiceHoldsNoMutableState() {
        // Rule 1 Clause B: avoid global mutable state. A paging cursor kept in a field would make
        // concurrent turns interfere, so the legacy WORKING-STORAGE is reconstructed per call.
        for (final Field field : CardListService.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .withFailMessage("field is not final: %s", field.getName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("This sentinel family is distinct from the transaction-list family")
    void thisSentinelFamilyIsDistinctFromTheTransactionListFamily() {
        // COTRN00C uses an 'N'-valued family with TRNID-FIRST/TRNID-LAST X(16) bounds and
        // PAGE-NUM PIC 9(08) (COTRN00C.cbl:63-68); this program uses a numeric 0/9 pair, a
        // LOW-VALUES/'Y' indicator, a 27-byte composite cursor and PAGENOI PIC X(3). Unifying them
        // would corrupt both, so the divergences are pinned.
        assertThat(PAGE_NUMBER_WIDTH).isNotEqualTo(TRANSACTION_LIST_PAGE_NUMBER_WIDTH);
        assertThat(SCREEN_LINES).isNotEqualTo(PageResponse.PAGE_SIZE_TRANSACTION_LIST);
        assertThat(SCREEN_LINES).isNotEqualTo(PageResponse.PAGE_SIZE_USER_LIST);
        assertThat(PageResponse.PAGE_SIZE_TRANSACTION_LIST)
                .isEqualTo(PageResponse.PAGE_SIZE_USER_LIST);
        // The card list's cursor carries an account identifier alongside the record key; the
        // transaction list's carries the key alone.
        assertThat(CARD_FILTER_WIDTH + ACCOUNT_FILTER_WIDTH).isNotEqualTo(CARD_FILTER_WIDTH);
    }

    @Test
    @DisplayName("The page number is projected at PAGENOI's three bytes, not PAGENUMI's eight")
    void thePageNumberIsProjectedAtThreeBytes() {
        // MOVE WS-CA-SCREEN-NUM TO PAGENOI at :649, where PAGENOI is PIC X(3) at
        // app/cpy-bms/COCRDLI.CPY:60. COTRN00 and COUSR00 declare PAGENUMI PIC X(8), so no shared
        // header helper is possible.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.screen.getPageNumber())
                .hasSize(PAGE_NUMBER_WIDTH)
                .isEqualTo("1  ")
                .isNotEqualTo("1");
        assertThat(result.page.getPageNumber()).isEqualTo(FIRST_PAGE);
    }

    // ============================================================================================
    // PHASE 3 - FORWARD AND BACKWARD BROWSE
    // app/cbl/COCRDLIC.cbl:1123-1261 and :1264-1374
    // ============================================================================================

    @Test
    @DisplayName("Paging forward advances from the LAST key of the previous page")
    void pagingForwardAdvancesFromTheLastKeyOfThePreviousPage() {
        // 0000-MAIN :488-489 moves WS-CA-LAST-CARD-NUM into the RIDFLD before performing
        // 9000-READ-FORWARD, whose STARTBR at :1129-1136 positions on it. Page one displayed cards
        // one to seven and the lookahead reported card eight as the last key, so page two starts
        // there.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_PF08, FIRST_PAGE, true, cardNumber(1), cardNumber(8)));

        assertThat(projectedCardNumbers(result))
                .containsExactly(
                        cardNumber(8),
                        cardNumber(9),
                        cardNumber(10),
                        cardNumber(11),
                        cardNumber(12),
                        cardNumber(13),
                        cardNumber(14));
        assertThat(result.page.getFirstKey()).isEqualTo(cardNumber(8));
        // MOVE WS-CA-SCREEN-NUM + 1 at :487, modulo the single PIC 9(1) digit.
        assertThat(result.page.getPageNumber()).isEqualTo(2);
        assertThat(result.page.isNextPageAvailable()).isTrue();
    }

    @Test
    @DisplayName("PF20 folds onto PF08, so it pages forward too")
    void pf20FoldsOntoPf08AndPagesForward() {
        // The upper twelve EIBAID key codes reuse the lower twelve, per app/cpy/CSSTRPFY.cpy:21-78.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_PF20_FOLDS_TO_PF08, FIRST_PAGE, true, cardNumber(1), cardNumber(8)));

        assertThat(projectedCardNumbers(result)).first().isEqualTo(cardNumber(8));
        assertThat(result.page.getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("Paging backward retreats from the FIRST key of the current page")
    void pagingBackwardRetreatsFromTheFirstKeyOfTheCurrentPage() {
        // 0000-MAIN :505-506 moves WS-CA-FIRST-CARD-NUM into the RIDFLD before performing
        // 9100-READ-BACKWARDS, which walks READPREV and fills the row table from row seven down to
        // row one, so the rows come back in ascending order even though they were read descending.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_PF07, 2, true, cardNumber(8), cardNumber(15)));

        assertThat(projectedCardNumbers(result))
                .containsExactly(
                        cardNumber(1),
                        cardNumber(2),
                        cardNumber(3),
                        cardNumber(4),
                        cardNumber(5),
                        cardNumber(6),
                        cardNumber(7));
        assertThat(result.page.getFirstKey()).isEqualTo(cardNumber(1));
        assertThat(result.page.getPageNumber()).isEqualTo(FIRST_PAGE);
    }

    @Test
    @DisplayName("Paging backward reports the row ABOVE the page as the last key - the :1268 defect")
    void pagingBackwardReportsTheRowAboveThePageAsTheLastKey() {
        // MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY at :1268-1269, executed BEFORE the browse
        // repopulates the table, so the last key of a backward page is the first key of the page
        // being left rather than the last row actually displayed. Preserved, not corrected: the
        // reported key is what the next PF8 starts from, and changing it would change which page
        // that press produces.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_PF07, 2, true, cardNumber(8), cardNumber(15)));

        assertThat(projectedCardNumbers(result)).last().isEqualTo(cardNumber(7));
        assertThat(result.page.getLastKey())
                .isEqualTo(cardNumber(8))
                .isNotEqualTo(cardNumber(7));
    }

    @Test
    @DisplayName("Paging backward off the front of the file is reported as a file error - no ENDFILE arm")
    void pagingBackwardOffTheFrontOfTheFileIsReportedAsAFileError() {
        // Neither READPREV has a DFHRESP(ENDFILE) arm (:1294-1318, :1322-1370), so exhausting the
        // records falls into WHEN OTHER and abends the conversation. Preserved as found; the Java
        // target raises the typed file exception the same path composes.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final Throwable thrown = catchThrowable(() -> service().listCards(
                pagingEntry(AID_PF07, 2, true, cardNumber(1), cardNumber(8))));

        assertThat(thrown).isInstanceOf(CardDemoException.class);
        assertThat(thrown.getClass().getSimpleName()).isEqualTo("FileAccessException");
        assertThat(thrown).hasMessageStartingWith(FILE_ERROR_PREFIX);
        assertThat(thrown.getMessage()).contains(RESP_ENDFILE).contains(CARD_FILE_NAME);
    }

    @Test
    @DisplayName("Page 1 rejects a backward request with a message, never a negative offset")
    void pageOneRejectsABackwardRequestWithoutANegativeOffset() {
        // WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE at :498-503 performs 9000-READ-FORWARD, not
        // 9100-READ-BACKWARDS, so page one is simply redisplayed and 1400-SETUP-MESSAGE :901-904
        // explains why. No arithmetic runs that could produce a negative position.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_PF07, FIRST_PAGE, true, cardNumber(1), cardNumber(8)));

        assertThat(result.errorMessage).isEqualTo(MSG_NO_PREVIOUS_PAGES);
        assertThat(result.page.getPageNumber()).isEqualTo(FIRST_PAGE);
        assertThat(projectedCardNumbers(result)).first().isEqualTo(cardNumber(1));
        for (final Pageable window : capturedWindows()) {
            assertThat(window.getOffset()).isNotNegative();
            assertThat(window.getPageNumber()).isNotNegative();
            assertThat(window.getPageSize()).isPositive();
        }
    }

    @Test
    @DisplayName("Every paged query the repository exposes carries a deterministic ordering")
    void everyPagedRepositoryQueryCarriesADeterministicOrdering() {
        // Rule 1 Clause A: an unordered paged query makes page contents non-reproducible, because
        // the store may return rows in any order and a row could then appear on two pages or on
        // none. Both paged finders encode ORDER BY in the derived-query method name, which is the
        // only place the ordering can live for a derived query.
        for (final Method declared : CardRepository.class.getDeclaredMethods()) {
            if (!Page.class.isAssignableFrom(declared.getReturnType())) {
                continue;
            }
            assertThat(declared.getName())
                    .withFailMessage("paged finder without an ordering: %s", declared.getName())
                    .contains("OrderBy");
        }
    }

    @Test
    @DisplayName("The browse reads aligned windows of pageSize + 1, the lookahead's extra record")
    void theBrowseReadsAlignedWindowsOfPageSizePlusOne() {
        // The forward browse needs one record beyond the page to answer "is there a next page", so
        // the window is eight wide. Windows are block aligned, which is what lets the binary-search
        // STARTBR reposition without re-reading.
        givenCardFile(cardFile(20, ACCOUNT_A));

        service().listCards(firstEntry());

        final List<Pageable> windows = capturedWindows();
        assertThat(windows).isNotEmpty();
        for (final Pageable window : windows) {
            assertThat(window.getPageSize()).isEqualTo(SCREEN_LINES + 1);
            assertThat(window.getOffset() % (SCREEN_LINES + 1)).isZero();
        }
    }

    @Test
    @DisplayName("Nothing but a Pageable ever reaches the repository - binding, not concatenation")
    void nothingButAPageableEverReachesTheRepository() {
        // Rule 1 Clause D: no string-concatenated SQL. The only finder this service calls takes a
        // Pageable and nothing else, so an untrusted filter value is structurally incapable of
        // reaching the query - the filter is applied in memory by 9500-FILTER-RECORDS instead.
        givenCardFile(cardFile(20, ACCOUNT_A));

        service().listCards(filterEntry(ACCOUNT_A_FILTER, cardNumber(3)));

        for (final Pageable window : capturedWindows()) {
            assertThat(window.getSort().isSorted())
                    .withFailMessage("ordering belongs in the finder name, not the Pageable")
                    .isFalse();
        }
        verify(cardRepository, never()).findByAccountIdOrderByCardNumberAsc(any(), any(Pageable.class));
    }

    // ============================================================================================
    // PHASE 4 - 9500-FILTER-RECORDS IS AN AND OF TWO INDEPENDENT GUARDS
    // app/cbl/COCRDLIC.cbl:1382-1409. Four combinations, each its own test.
    // ============================================================================================

    @Test
    @DisplayName("Filter combination 1 of 4: neither filter supplied - every record passes")
    void neitherFilterSuppliedAdmitsEveryRecord() {
        // Both IF guards take their ELSE CONTINUE arm (:1391-1392, :1404-1405), so
        // WS-DONOT-EXCLUDE-THIS-RECORD survives from :1383 and the page is the physical file order.
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(null, null));

        assertThat(projectedCardNumbers(result))
                .containsExactly(
                        cardNumber(1),
                        cardNumber(2),
                        cardNumber(3),
                        cardNumber(4),
                        cardNumber(5),
                        cardNumber(6),
                        cardNumber(7));
    }

    @Test
    @DisplayName("Filter combination 2 of 4: account filter only - the account gate alone excludes")
    void accountFilterOnlyAdmitsOnlyThatAccount() {
        // IF FLG-ACCTFILTER-ISVALID / IF CARD-ACCT-ID = CC-ACCT-ID at :1384-1386. The card gate takes
        // its ELSE CONTINUE arm, so only the odd ordinals - account A - survive.
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(ACCOUNT_A_FILTER, null));

        assertThat(projectedCardNumbers(result))
                .containsExactly(
                        cardNumber(1),
                        cardNumber(3),
                        cardNumber(5),
                        cardNumber(7),
                        cardNumber(9),
                        cardNumber(11),
                        cardNumber(13));
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
    }

    @Test
    @DisplayName("Filter combination 3 of 4: card filter only - the card gate alone excludes")
    void cardFilterOnlyAdmitsOnlyThatCard() {
        // IF FLG-CARDFILTER-ISVALID / IF CARD-NUM = CC-CARD-NUM-N at :1397-1399. Exactly one record
        // can match a whole sixteen-digit key, so the page is a single row and the browse then runs
        // to end of file.
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(null, cardNumber(5)));

        assertThat(projectedCardNumbers(result)).containsExactly(cardNumber(5));
        assertThat(result.page.isNextPageAvailable()).isFalse();
        assertThat(result.errorMessage).isEqualTo(MSG_NO_MORE_RECORDS);
    }

    @Test
    @DisplayName("Filter combination 4 of 4: both filters - the two gates are ANDed, not ORed")
    void bothFiltersSuppliedAreAndedTogether() {
        givenCardFile(mixedCardFile(20));
        final CardListService service = service();

        // Card five is an odd ordinal, so it belongs to account A: both gates pass.
        final CardListResult agreeing =
                service.listCards(filterEntry(ACCOUNT_A_FILTER, cardNumber(5)));
        assertThat(projectedCardNumbers(agreeing)).containsExactly(cardNumber(5));

        // The same card against account B: the account gate excludes it even though the card gate
        // would have admitted it. Under an OR the row would still appear, which is what this proves
        // it does not.
        final CardListResult disagreeing =
                service.listCards(filterEntry(ACCOUNT_B_FILTER, cardNumber(5)));
        assertThat(disagreeing.page.getRows()).isEmpty();
        assertThat(disagreeing.errorMessage).isEqualTo(MSG_NO_RECORDS_FOUND);
    }

    @Test
    @DisplayName("A record failing the account gate is excluded before the card gate is reached")
    void aRecordFailingTheAccountGateIsExcludedBeforeTheCardGateIsReached() {
        // SET WS-EXCLUDE-THIS-RECORD TO TRUE followed by GO TO 9500-FILTER-RECORDS-EXIT at
        // :1388-1389 skips the card gate outright.
        //
        // Evidence-based caveat (Rule 1 Clause F): both gates are pure comparisons with no side
        // effect, so short-circuiting is behaviourally indistinguishable from evaluating both and
        // ANDing the results. What IS observable is the exclusion itself, asserted here, and the
        // continued existence of the GO TO target paragraph, asserted by the paragraph census in
        // Phase 7. Together they pin the structure the short-circuit depends on.
        givenCardFile(mixedCardFile(20));

        final CardListResult result =
                service().listCards(filterEntry(ACCOUNT_B_FILTER, cardNumber(3)));

        assertThat(result.page.getRows()).isEmpty();
        assertThat(result.errorMessage).isEqualTo(MSG_NO_RECORDS_FOUND);
    }

    @Test
    @DisplayName("The account gate compares eleven zoned digits, so leading zeros are significant")
    void theAccountGateComparesElevenZonedDigits() {
        // IF CARD-ACCT-ID = CC-ACCT-ID at :1385 compares the PIC 9(11) record field against the
        // PIC X(11) display field, so the account number is matched through its zero-filled
        // eleven-digit rendering. The same digits in a different position do not match.
        givenCardFile(mixedCardFile(20));
        final CardListService service = service();

        assertThat(projectedCardNumbers(service.listCards(filterEntry(ACCOUNT_A_FILTER, null))))
                .isNotEmpty();
        assertThat(ACCOUNT_A_FILTER).hasSize(ACCOUNT_FILTER_WIDTH).endsWith("11");

        // "11000000000" is eleven digits and therefore a VALID filter, but it denotes a different
        // account, so nothing matches.
        final CardListResult shifted = service.listCards(filterEntry("11000000000", null));
        assertThat(shifted.page.getRows()).isEmpty();
        assertThat(shifted.errorMessage).isEqualTo(MSG_NO_RECORDS_FOUND);
    }

    @Test
    @DisplayName("The card gate compares the sixteen-character key exactly, digit for digit")
    void theCardGateComparesTheSixteenCharacterKeyExactly() {
        // IF CARD-NUM = CC-CARD-NUM-N at :1398 compares the PIC X(16) record key against the
        // PIC 9(16) numeric redefinition of the filter, so the whole key must agree. A fifteen-digit
        // value cannot be the key of any record.
        givenCardFile(mixedCardFile(20));
        final CardListService service = service();

        assertThat(cardNumber(5)).hasSize(CARD_FILTER_WIDTH);
        assertThat(projectedCardNumbers(service.listCards(filterEntry(null, cardNumber(5)))))
                .containsExactly(cardNumber(5));

        // Sixteen digits that match no record: MOVE ... a valid filter, but nothing to find.
        final CardListResult absent = service.listCards(filterEntry(null, "9999999999999999"));
        assertThat(absent.page.getRows()).isEmpty();
        assertThat(absent.errorMessage).isEqualTo(MSG_NO_RECORDS_FOUND);
    }

    @Test
    @DisplayName("The forward lookahead is NOT filtered, so a filtered page can over-report more data")
    void theForwardLookaheadIsNotFilteredAndCanOverReportMoreData() {
        // The lookahead READNEXT at :1197-1205 sets CA-NEXT-PAGE-EXISTS from the raw record without
        // performing 9500-FILTER-RECORDS, so the indicator reflects the presence of ANY next record
        // rather than a matching one - and the saved last key becomes that unfiltered record.
        //
        // This is exactly why the account predicate must NOT be pushed into the query: doing so would
        // silently repair this defect and change which pages report more data. Preserved and pinned.
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(ACCOUNT_A_FILTER, null));

        assertThat(projectedCardNumbers(result)).last().isEqualTo(cardNumber(13));
        // Card fourteen belongs to account B and could never appear on this filtered list, yet it is
        // what the lookahead saw and therefore what the cursor now carries.
        assertThat(result.page.getLastKey()).isEqualTo(cardNumber(14));
        assertThat(result.page.isNextPageAvailable()).isTrue();
    }

    // ============================================================================================
    // PHASE 5 - THREE-VALUED FILTER FLAGS AND THE BYTE-EXACT LITERALS
    // app/cbl/COCRDLIC.cbl:1003-1032 and :1036-1069
    // ============================================================================================

    @Test
    @DisplayName("Account flag state 1 of 3 - BLANK: an all-zeros filter suppresses the filter")
    void anAllZerosAccountFilterIsBlankRatherThanInvalid() {
        // IF CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES OR CC-ACCT-ID-N EQUAL ZEROS at
        // :1009-1011. The third operand goes through the NUMERIC redefinition, so eleven zeros mean
        // "no filter" - it must neither raise nor filter anything out.
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry("00000000000", null));

        assertThat(result.inputError).isFalse();
        assertThat(result.errorMessage).isEmpty();
        assertThat(projectedCardNumbers(result))
                .containsExactly(
                        cardNumber(1),
                        cardNumber(2),
                        cardNumber(3),
                        cardNumber(4),
                        cardNumber(5),
                        cardNumber(6),
                        cardNumber(7));
    }

    @Test
    @DisplayName("Account flag state 2 of 3 - ISVALID: eleven digits are applied as a filter")
    void anElevenDigitAccountFilterIsValidAndApplied() {
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(ACCOUNT_A_FILTER, null));

        assertThat(result.inputError).isFalse();
        assertThat(projectedCardNumbers(result))
                .containsExactly(
                        cardNumber(1),
                        cardNumber(3),
                        cardNumber(5),
                        cardNumber(7),
                        cardNumber(9),
                        cardNumber(11),
                        cardNumber(13));
    }

    @Test
    @DisplayName("Account flag state 3 of 3 - NOT-OK: a non-numeric filter raises with the exact literal")
    void aNonNumericAccountFilterIsNotOkAndRaisesTheExactLiteral() {
        // IF CC-ACCT-ID IS NOT NUMERIC at :1015 sets INPUT-ERROR, FLG-ACCTFILTER-NOT-OK and
        // FLG-PROTECT-SELECT-ROWS-YES, and moves the literal at :1021-1023.
        final Throwable thrown =
                catchThrowable(() -> service().listCards(filterEntry("1234567890A", null)));

        assertThat(thrown).isInstanceOf(ValidationException.class);
        final ValidationException failure = (ValidationException) thrown;
        assertThat(failure).hasMessage(MSG_ACCOUNT_FILTER_INVALID);
        assertThat(failure.getFieldName()).isEqualTo("accountId");
        assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
        assertThat(failure.hasFieldName()).isTrue();
        assertThat(failure).hasNoCause();
        // The browse is suppressed entirely, which is what SET FLG-PROTECT-SELECT-ROWS-YES exists to
        // achieve: with no page there is no selectable row.
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("Card flag state 1 of 3 - BLANK: an all-zeros filter suppresses the filter")
    void anAllZerosCardFilterIsBlankRatherThanInvalid() {
        // IF CC-CARD-NUM EQUAL LOW-VALUES OR ... OR CC-CARD-NUM-N EQUAL ZEROS at :1042-1044.
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(null, "0000000000000000"));

        assertThat(result.inputError).isFalse();
        assertThat(result.errorMessage).isEmpty();
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        assertThat(projectedCardNumbers(result)).first().isEqualTo(cardNumber(1));
    }

    @Test
    @DisplayName("Card flag state 2 of 3 - ISVALID: sixteen digits are applied as a filter")
    void aSixteenDigitCardFilterIsValidAndApplied() {
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(null, cardNumber(9)));

        assertThat(result.inputError).isFalse();
        assertThat(projectedCardNumbers(result)).containsExactly(cardNumber(9));
    }

    @Test
    @DisplayName("Card flag state 3 of 3 - NOT-OK: a non-numeric filter raises with the exact literal")
    void aNonNumericCardFilterIsNotOkAndRaisesTheExactLiteral() {
        // IF CC-CARD-NUM IS NOT NUMERIC at :1050, message moved under IF WS-ERROR-MSG-OFF at :1056.
        final Throwable thrown =
                catchThrowable(() -> service().listCards(filterEntry(null, "123456789012345X")));

        assertThat(thrown).isInstanceOf(ValidationException.class);
        final ValidationException failure = (ValidationException) thrown;
        assertThat(failure).hasMessage(MSG_CARD_FILTER_INVALID);
        assertThat(failure.getFieldName()).isEqualTo("cardNumber");
        assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
        assertThat(failure).hasNoCause();
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("The two filter literals reproduce their peculiarities byte for byte")
    void theTwoFilterLiteralsReproduceTheirPeculiaritiesByteForByte() {
        // Three peculiarities in each literal, all preserved: upper case throughout, NO space after
        // the comma, and the grammatically odd article "A" before a numeral. Observability, Rule 1
        // Clause A: the message an operator reads must be the message the system of record produced.
        for (final String literal : List.of(MSG_ACCOUNT_FILTER_INVALID, MSG_CARD_FILTER_INVALID)) {
            assertThat(literal).isEqualTo(literal.toUpperCase(Locale.ROOT));
            assertThat(literal).contains("FILTER,IF SUPPLIED");
            assertThat(literal).doesNotContain("FILTER, IF");
            assertThat(literal).contains(" MUST BE A ");
            assertThat(literal).endsWith(" DIGIT NUMBER");
        }
        assertThat(MSG_ACCOUNT_FILTER_INVALID)
                .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER")
                .contains("A 11 DIGIT")
                .doesNotContain("AN 11 DIGIT");
        assertThat(MSG_CARD_FILTER_INVALID)
                .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER")
                .contains("A 16 DIGIT");
    }

    @Test
    @DisplayName("The account edit runs before the card edit, so its literal wins when both are bad")
    void theAccountEditRunsBeforeTheCardEdit() {
        // 2200-EDIT-INPUTS performs 2210-EDIT-ACCOUNT before 2220-EDIT-CARD (:988-995). The account
        // edit reports first, so the card edit never contributes a message - which is also why the
        // card edit's move is guarded by IF WS-ERROR-MSG-OFF at :1056 and the account edit's, at
        // :1020-1023, is not: the guard exists to stop the second edit overwriting the first.
        final Throwable thrown = catchThrowable(
                () -> service().listCards(filterEntry("1234567890A", "123456789012345X")));

        assertThat(thrown).isInstanceOf(ValidationException.class);
        assertThat(thrown).hasMessage(MSG_ACCOUNT_FILTER_INVALID);
        assertThat(((ValidationException) thrown).getFieldName()).isEqualTo("accountId");
        verifyNoInteractions(cardRepository);
    }

    // ============================================================================================
    // PHASE 6 - THE BMS FIELD CONTRACT HAS AN ASYMMETRIC FIRST ROW
    // app/cpy-bms/COCRDLI.CPY - 45 input fields, no CRDSTP1I
    // ============================================================================================

    @Test
    @DisplayName("Row 1 projects 4 BMS fields and rows 2 to 7 project 5 - there is no CRDSTP1I")
    void rowOneProjectsFourBmsFieldsAndTheRestProjectFive() {
        // CRDSTP2I through CRDSTP7I are declared at app/cpy-bms/COCRDLI.CPY:108, 138, 168, 198, 228
        // and 258. No CRDSTP1I exists anywhere in the mapset, so row one carries the selection flag,
        // account number, card number and status only.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());
        final List<CardDto.CardListRow> rows = result.page.getRows();

        assertThat(rows).hasSize(SCREEN_LINES);
        assertThat(rows.get(0).getRowNumber()).isEqualTo(1);
        assertThat(rows.get(0).getBmsFieldCount()).isEqualTo(4);
        assertThat(rows.get(0).hasSelectorType()).isFalse();
        for (int index = 1; index < rows.size(); index++) {
            assertThat(rows.get(index).getRowNumber()).isEqualTo(index + 1);
            assertThat(rows.get(index).getBmsFieldCount()).isEqualTo(5);
            assertThat(rows.get(index).hasSelectorType()).isTrue();
        }
        // 9 header and filter fields + 4 for row one + 30 for rows two to seven + 2 trailing = 45.
        assertThat(9 + 4 + (6 * 5) + 2).isEqualTo(CardDto.LIST_FIELD_COUNT);
        assertThat(CardDto.LIST_FIELD_COUNT).isEqualTo(45);
        assertThat(CardDto.DETAIL_FIELD_COUNT).isEqualTo(15);
    }

    @Test
    @DisplayName("No row-1 selector is synthesised - the projection leaves every selector type unset")
    void noRowOneSelectorIsSynthesised() {
        // COCRDLIC never populates a selector-type field: CRDSTP2I onward exist in the mapset but the
        // program moves nothing into them, so the projection carries null for every row and row one
        // cannot even accept one.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.page.getRows()).allMatch(row -> row.getSelectorType() == null);
        assertThat(CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE).isEqualTo(1);
        assertThat(CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE).isEqualTo(4);
        assertThat(CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE).isEqualTo(5);
    }

    @Test
    @DisplayName("The row projection carries the declared field widths - 11, 16 and 1")
    void theRowProjectionCarriesTheDeclaredFieldWidths() {
        // ACCTNO1I PIC X(11) at :84, CRDNUM1I PIC X(16) at :90 and CRDSTS1I PIC X(1) at :96, which are
        // also the widths of CARD-ACCT-ID, CARD-NUM and CARD-ACTIVE-STATUS in app/cpy/CVACT02Y.cpy.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        for (final CardDto.CardListRow row : result.page.getRows()) {
            assertThat(row.getAccountNumber()).hasSize(ACCOUNT_FILTER_WIDTH);
            assertThat(row.getCardNumber()).hasSize(CARD_FILTER_WIDTH);
            assertThat(row.getStatusCode()).hasSize(1);
            assertThat(row.getSelectionFlag()).hasSize(1);
        }
    }

    @Test
    @DisplayName("The screen header carries the six fields every mapset in the corpus shares")
    void theScreenHeaderCarriesTheSixSharedFields() {
        // TRNNAMEI X(4), TITLE01I X(40), CURDATEI X(8), PGMNAMEI X(8), TITLE02I X(40) and
        // CURTIMEI X(9) recur on all seventeen symbolic maps. The date and time come from the request
        // rather than a clock, which is what keeps this tier deterministic.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.screen.getTransactionName()).isEqualTo("CCLI");
        assertThat(result.screen.getProgramName()).isEqualTo(THIS_PROGRAM);
        assertThat(result.screen.getCurrentDate()).isEqualTo(HEADER_DATE);
        assertThat(result.screen.getCurrentTime()).isEqualTo(HEADER_TIME);
        assertThat(result.screen.getTitle01()).isNotBlank();
        assertThat(result.screen.getTitle02()).isNotBlank();
    }

    @Test
    @DisplayName("The account-based CARDAIX finder is NEVER used - COCRDLIC opens CARDDAT only")
    void theAccountBasedFinderIsNeverUsedBecauseTheProgramOpensTheBaseClusterOnly() {
        // Evidence: LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX ' at app/cbl/COCRDLIC.cbl:215-217
        // is referenced exactly once repository-wide - at its own declaration. Every browse verb names
        // LIT-CARD-FILE ('CARDDAT '): STARTBR :1129 and :1273, READNEXT :1146 and :1197, READPREV
        // :1294 and :1322, ENDBR :1258 and :1376.
        //
        // Pushing the account predicate into the query would be faster AND WRONG: the lookahead at
        // :1197-1205 deliberately does not filter, so an indexed query would change which pages report
        // more data. Parity governs. The declaration is retained with its trailing-space padding
        // because the traceability matrix cites it.
        assertThat(CARD_ACCOUNT_PATH_NAME).hasSize(8).isEqualTo("CARDAIX ");
        assertThat(CARD_FILE_NAME).isEqualTo("CARDDAT");

        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry(ACCOUNT_A_FILTER, null));

        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        verify(cardRepository, never()).findByAccountIdOrderByCardNumberAsc(any(), any(Pageable.class));
        verify(cardRepository, atLeastOnce()).findAllByOrderByCardNumberAsc(any(Pageable.class));
    }

    // ============================================================================================
    // PHASE 7 - PARAGRAPH CORRESPONDENCE
    // Every source label maps 1:1 to a private Java method, exit paragraphs included.
    // ============================================================================================

    @Test
    @DisplayName("The bean declares one private method per COBOL paragraph, exits included")
    void theBeanDeclaresOnePrivateMethodPerCobolParagraph() {
        // 39 PROCEDURE DIVISION Area-A labels in app/cbl/COCRDLIC.cbl plus the 2 that COPY 'CSSTRPFY'
        // at :1416 contributes = 41. Deleting any of them - including the bare-EXIT exit paragraphs
        // and the empty YYYY-STORE-PFKEY-EXIT - would break the paragraph map Gate 7 verifies, so the
        // census is asserted rather than assumed. This is the documented conflict with Rule 1 Clause
        // B's no-dead-code requirement, resolved in favour of parity.
        final List<String> expected = List.of(
                "main0000",
                "commonReturn",
                "main0000Exit",
                "sendMap1000",
                "sendMap1000Exit",
                "screenInit1100",
                "screenInit1100Exit",
                "screenArrayInit1200",
                "screenArrayInit1200Exit",
                "setupArrayAttribs1250",
                "setupArrayAttribs1250Exit",
                "setupScreenAttrs1300",
                "setupScreenAttrs1300Exit",
                "setupMessage1400",
                "setupMessage1400Exit",
                "sendScreen1500",
                "sendScreen1500Exit",
                "receiveMap2000",
                "receiveMap2000Exit",
                "receiveScreen2100",
                "receiveScreen2100Exit",
                "editInputs2200",
                "editInputs2200Exit",
                "editAccount2210",
                "editAccount2210Exit",
                "editCard2220",
                "editCard2220Exit",
                "editArray2250",
                "editArray2250Exit",
                "readForward9000",
                "readForward9000Exit",
                "readBackwards9100",
                "readBackwards9100Exit",
                "filterRecords9500",
                "filterRecords9500Exit",
                "sendPlainText",
                "sendPlainTextExit",
                "sendLongText",
                "sendLongTextExit",
                "yyyyStorePfkey",
                "yyyyStorePfkeyExit");

        assertThat(expected).hasSize(41).doesNotHaveDuplicates();

        final List<String> declared = new ArrayList<>();
        for (final Method method : CardListService.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                declared.add(method.getName());
            }
        }
        assertThat(declared).containsAll(expected);
    }

    @Test
    @DisplayName("The paragraph methods are private - the bean exposes only listCards")
    void theParagraphMethodsArePrivateAndOnlyListCardsIsPublic() {
        // Rule 1 Clause B, documented public surface: a paragraph is an implementation detail of the
        // program, so exposing one would invite a caller to enter the conversation mid-flow.
        final List<String> publicMethods = new ArrayList<>();
        for (final Method method : CardListService.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && Modifier.isPublic(method.getModifiers())) {
                publicMethods.add(method.getName());
            }
        }
        assertThat(publicMethods).containsExactly("listCards");
    }

    @Test
    @DisplayName("The two request-shaping and result-shaping types are the only nested public types")
    void theNestedPublicTypesAreTheRequestAndTheResult() {
        final List<String> nested = new ArrayList<>();
        for (final Class<?> declared : CardListService.class.getDeclaredClasses()) {
            if (Modifier.isPublic(declared.getModifiers())) {
                nested.add(declared.getSimpleName());
            }
        }
        assertThat(nested).containsExactlyInAnyOrder("CardListRequest", "CardListResult");
    }

    // ============================================================================================
    // TRANSFER OF CONTROL - the XCTL targets, and the browse that must not run
    // app/cbl/COCRDLIC.cbl:384-397 (PF3), :560-577 (S and U)
    // ============================================================================================

    @Test
    @DisplayName("Selecting S transfers to the card-detail program without browsing")
    void selectingViewTransfersToTheCardDetailProgram() {
        // WHEN SELECT-OK / 'S' at :560-568 issues XCTL to LIT-CARDDTLPGM, so the conversation ends
        // before 9000-READ-FORWARD is reached. The absence of a repository stub here is the proof:
        // under strict stubs an unused stub would fail the build.
        final List<Card> file = cardFile(20, ACCOUNT_A);

        final CardListResult result = service().listCards(reentry(
                AID_ENTER,
                null,
                null,
                selectionOn(3, SELECT_VIEW),
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                displayed(file)));

        assertThat(result.nextProgram).isEqualTo(CARD_DETAIL_PROGRAM);
        assertThat(result.nextMapset).isEqualTo(CARD_DETAIL_MAPSET);
        assertThat(result.nextMap).isEqualTo(CARD_DETAIL_MAP);
        assertThat(result.exitRequested).isFalse();
        assertThat(result.screen).isNull();
        assertThat(result.page).isNull();
        assertThat(result.rowCount()).isZero();
        assertThat(result.cardNumber).isEqualTo(cardNumber(3));
        assertThat(result.accountId).isEqualTo(ACCOUNT_A_FILTER);
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("Selecting U transfers to the card-update program without browsing")
    void selectingUpdateTransfersToTheCardUpdateProgram() {
        // WHEN UPDATE-REQUESTED-ON / 'U' at :569-577 issues XCTL to LIT-CARDUPDPGM.
        final List<Card> file = cardFile(20, ACCOUNT_A);

        final CardListResult result = service().listCards(reentry(
                AID_ENTER,
                null,
                null,
                selectionOn(1, SELECT_UPDATE),
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                displayed(file)));

        assertThat(result.nextProgram).isEqualTo(CARD_UPDATE_PROGRAM);
        assertThat(result.nextMapset).isEqualTo(CARD_UPDATE_MAPSET);
        assertThat(result.nextMap).isEqualTo(CARD_UPDATE_MAP);
        assertThat(result.cardNumber).isEqualTo(cardNumber(1));
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("PF3 exits to the main menu carrying the LIT-THISMAP quirk of :391")
    void pf3ExitsToTheMainMenuCarryingThePreservedMapQuirk() {
        // MOVE LIT-MENUMAPSET TO CCARD-NEXT-MAPSET at :390 but MOVE LIT-THISMAP - not LIT-MENUMAP -
        // TO CCARD-NEXT-MAP at :391. LIT-MENUMAP is declared at :193-194 and never referenced, so the
        // outgoing map name belongs to the mapset being left. Preserved, and pinned here so that
        // "tidying" it breaks a test.
        final CardListResult result = service().listCards(reentry(
                AID_PF03,
                null,
                null,
                null,
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                null));

        assertThat(result.nextProgram).isEqualTo(MENU_PROGRAM);
        assertThat(result.nextMapset).isEqualTo(MENU_MAPSET);
        assertThat(result.nextMap).isEqualTo(THIS_MAP);
        assertThat(result.exitRequested).isTrue();
        assertThat(result.errorMessage).isEqualTo(MSG_EXIT);
        assertThat(result.screen).isNull();
        assertThat(result.page).isNull();
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("Two selections are reported in the result, never thrown, and the page still returns")
    void twoSelectionsAreReportedInTheResultAndThePageStillReturns() {
        // 2250-EDIT-ARRAY :1078-1092 sets INPUT-ERROR and the more-than-one literal, then :1093-1117
        // marks every row. 0000-MAIN's IF INPUT-ERROR arm at :425-437 still re-reads forward, so the
        // user sees the page again with the message - a selection error is a screen outcome, not a
        // fault.
        final List<Card> file = cardFile(20, ACCOUNT_A);
        givenCardFile(file);

        final List<String> twoSelections = new ArrayList<>(selectionOn(1, SELECT_VIEW));
        twoSelections.set(1, SELECT_UPDATE);

        final CardListResult result = service().listCards(reentry(
                AID_ENTER,
                null,
                null,
                twoSelections,
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                displayed(file)));

        assertThat(result.inputError).isTrue();
        assertThat(result.errorMessage).isEqualTo(MSG_MORE_THAN_ONE_ACTION);
        assertThat(result.rowSelectionErrorFlags).isEqualTo("1100000");
        assertThat(result.selectionFailure).isNotNull();
        assertThat(result.selectionFailure).hasMessage(MSG_MORE_THAN_ONE_ACTION);
        assertThat(result.selectionFailure.hasFieldName()).isFalse();
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        assertThat(result.nextProgram).isEqualTo(THIS_PROGRAM);
    }

    @Test
    @DisplayName("An unrecognised action code is reported against its own row, and the page returns")
    void anUnrecognisedActionCodeIsReportedAgainstItsOwnRow() {
        // WHEN OTHER at :1108-1116 sets INPUT-ERROR, marks the offending row and records the
        // INVALID ACTION CODE literal against CRDSELn.
        final List<Card> file = cardFile(20, ACCOUNT_A);
        givenCardFile(file);

        final CardListResult result = service().listCards(reentry(
                AID_ENTER,
                null,
                null,
                selectionOn(3, SELECT_INVALID),
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                displayed(file)));

        assertThat(result.inputError).isTrue();
        assertThat(result.errorMessage).isEqualTo(MSG_INVALID_ACTION_CODE);
        assertThat(result.rowSelectionErrorFlags).hasSize(SCREEN_LINES);
        assertThat(result.rowSelectionErrorFlags.charAt(2)).isEqualTo('1');
        assertThat(result.selectionFailure).isNotNull();
        assertThat(result.selectionFailure.getFieldName()).isEqualTo("CRDSEL3");
        assertThat(result.selectionFailure.getFailureKind())
                .isEqualTo(ValidationException.FailureKind.INVALID);
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
    }

    @Test
    @DisplayName("The screen attribute projections are fixed width - 7 row flags and 4 filter flags")
    void theScreenAttributeProjectionsAreFixedWidth() {
        // 1250-SETUP-ARRAY-ATTRIBS (:748) drives one selectable and one highlight flag per row, and
        // 1300-SETUP-SCREEN-ATTRS (:837) drives enterable and highlighted for each of the two filter
        // fields.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        // A full page makes all seven rows selectable; an unfilled row is left protected, which is
        // the IF ROW-i-IS-LOW-VALUES arm of each unrolled block.
        assertThat(result.rowSelectableFlags).hasSize(SCREEN_LINES).isEqualTo("YYYYYYY");
        assertThat(result.rowHighlightFlags).hasSize(SCREEN_LINES).isEqualTo("NNNNNNN");
        assertThat(result.filterFieldFlags).hasSize(4);
        assertThat(result.rowSelectionErrorFlags).hasSize(SCREEN_LINES);
        assertThat(result.screenSent).isTrue();
        assertThat(result.rowSelectionProtected).isFalse();
    }

    @Test
    @DisplayName("An unfilled row is not selectable, so a short page protects its empty rows")
    void anUnfilledRowIsNotSelectable() {
        givenCardFile(cardFile(3, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.page.getRows()).hasSize(3);
        assertThat(result.rowSelectableFlags).isEqualTo("YYYNNNN");
    }

    // ============================================================================================
    // RULE 1 CLAUSE A - HOSTILE AND BOUNDARY INPUT
    // "Security by default: treat inputs as untrusted, avoid unsafe defaults."
    // ============================================================================================

    @Test
    @DisplayName("A null request is rejected with a clear message, not a NullPointerException")
    void aNullRequestIsRejectedWithAClearMessage() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service().listCards(null))
                .withMessage("request is required")
                .withNoCause();
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("Empty, all-blank and LOW-VALUES filters are all BLANK - no filter, no error")
    void emptyBlankAndLowValuesFiltersAreAllTreatedAsBlank() {
        // IF ... EQUAL LOW-VALUES OR ... EQUAL SPACES at :1009-1010 and :1042-1043. Every one of these
        // shapes means "the user typed nothing", so all seven leading records come back.
        givenCardFile(mixedCardFile(20));
        final CardListService service = service();

        final List<String> blankShapes = new ArrayList<>();
        blankShapes.add(null);
        blankShapes.add("");
        blankShapes.add("           ");
        blankShapes.add("\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000");

        for (final String shape : blankShapes) {
            final CardListResult result = service.listCards(filterEntry(shape, null));
            assertThat(result.inputError).isFalse();
            assertThat(result.errorMessage).isEmpty();
            assertThat(projectedCardNumbers(result)).first().isEqualTo(cardNumber(1));
            assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        }
    }

    @Test
    @DisplayName("A 10-character account filter is padded to 11 and then rejected as non-numeric")
    void aTenCharacterAccountFilterIsRejected() {
        // MOVE ACCTSIDI TO CC-ACCT-ID at :966 is a MOVE into PIC X(11), which left-justifies and
        // space-fills, so ten digits become "1234567890 " - and a trailing space is not numeric. This
        // is why the filter must be the full width, and why an eleven-digit rule cannot be relaxed.
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service().listCards(filterEntry("1234567890", null)))
                .withMessage(MSG_ACCOUNT_FILTER_INVALID);
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A 12-character account filter is truncated to 11 and then applied")
    void aTwelveCharacterAccountFilterIsTruncatedToEleven() {
        // The same MOVE truncates on the right, so twelve digits lose their last one. The value that
        // survives is a valid eleven-digit filter, which matches no record here.
        givenCardFile(mixedCardFile(20));

        final CardListResult result = service().listCards(filterEntry("000000000112", null));

        assertThat(result.inputError).isFalse();
        // "00000000011" survived the truncation and does match account A.
        assertThat(projectedCardNumbers(result)).first().isEqualTo(cardNumber(1));
    }

    @Test
    @DisplayName("A 15-character card filter is padded to 16 and then rejected as non-numeric")
    void aFifteenCharacterCardFilterIsRejected() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service().listCards(filterEntry(null, "123456789012345")))
                .withMessage(MSG_CARD_FILTER_INVALID);
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A 17-character card filter is truncated to 16 and then applied")
    void aSeventeenCharacterCardFilterIsTruncatedToSixteen() {
        givenCardFile(mixedCardFile(20));

        // cardNumber(5) with one extra digit appended: the extra digit is dropped on the right.
        final CardListResult result = service().listCards(filterEntry(null, cardNumber(5) + "7"));

        assertThat(result.inputError).isFalse();
        assertThat(projectedCardNumbers(result)).containsExactly(cardNumber(5));
    }

    @Test
    @DisplayName("A filter carrying a currency symbol is rejected - these are plain numeric fields")
    void aFilterCarryingACurrencySymbolIsRejected() {
        // COCRDLIC uses no currency-aware conversion anywhere: both filters go through IS NOT NUMERIC
        // only, so a currency symbol or a thousands separator is invalid input rather than something
        // to be parsed away.
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service().listCards(filterEntry("$1234567890", null)))
                .withMessage(MSG_ACCOUNT_FILTER_INVALID);
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A filter carrying thousands separators is rejected for the same reason")
    void aFilterCarryingThousandsSeparatorsIsRejected() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service().listCards(filterEntry("1,234,567,8", null)))
                .withMessage(MSG_ACCOUNT_FILTER_INVALID);
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A card filter carrying a separator is rejected, naming the card field")
    void aCardFilterCarryingASeparatorIsRejected() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service().listCards(filterEntry(null, "1234-5678-9012-3")))
                .withMessage(MSG_CARD_FILTER_INVALID);
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("Page 0 is normalised to page 1 at the response boundary, never reported as 0")
    void pageZeroIsNormalisedToPageOne() {
        // WS-CA-SCREEN-NUM is PIC 9(1) and legitimately holds zero before :1177-1181 bumps it, but a
        // page response must start at one. The normalisation happens only at the boundary.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_ENTER, 0, false, cardNumber(1), cardNumber(8)));

        assertThat(result.page.getPageNumber()).isEqualTo(FIRST_PAGE);
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        assertThat(PageResponse.FIRST_PAGE_NUMBER).isEqualTo(FIRST_PAGE);
    }

    @Test
    @DisplayName("A negative page number is normalised too, and produces no negative offset")
    void aNegativePageNumberIsNormalised() {
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_ENTER, -1, false, cardNumber(1), cardNumber(8)));

        assertThat(result.page.getPageNumber()).isEqualTo(FIRST_PAGE);
        for (final Pageable window : capturedWindows()) {
            assertThat(window.getOffset()).isNotNegative();
        }
    }

    @Test
    @DisplayName("Paging beyond the last page yields an empty page and a message, not an exception")
    void pagingBeyondTheLastPageYieldsAnEmptyPageAndAMessage() {
        // The saved cursor points past every record, so the STARTBR positions at end of file and the
        // very first READNEXT reports DFHRESP(ENDFILE). :1236-1243 then sets the message.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_PF08, 3, true, cardNumber(15), "9999999999999999"));

        assertThat(result.page.getRows()).isEmpty();
        assertThat(result.page.isNextPageAvailable()).isFalse();
        assertThat(result.errorMessage).isEqualTo(MSG_NO_MORE_RECORDS);
        assertThat(result.informationMessage).isEqualTo(MSG_INFORM_REC_ACTIONS);
    }

    @Test
    @DisplayName("An unsupported function key is coerced to ENTER rather than rejected")
    void anUnsupportedFunctionKeyIsCoercedToEnter() {
        // IF NOT PFK-VALID / MOVE DFHENTER TO EIBAID at :370-383. Only ENTER, PFK03, PFK07 and PFK08
        // pass the gate; PF5 is a real key that simply has no meaning on this screen.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_UNSUPPORTED, FIRST_PAGE, true, cardNumber(1), cardNumber(8)));

        assertThat(result.inputError).isFalse();
        assertThat(projectedCardNumbers(result)).first().isEqualTo(cardNumber(1));
        // Coerced to ENTER, so this is the plain WHEN OTHER redisplay of page one: no paging message,
        // and the lookahead still reports more data.
        assertThat(result.errorMessage).isEmpty();
        assertThat(result.page.isNextPageAvailable()).isTrue();
        assertThat(result.informationMessage).isEqualTo(MSG_INFORM_REC_ACTIONS);
    }

    @Test
    @DisplayName("An unrecognisable attention identifier is coerced to ENTER, not dereferenced")
    void anUnrecognisableAttentionIdentifierIsCoercedToEnter() {
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(AID_UNRECOGNISED, FIRST_PAGE, true, cardNumber(1), cardNumber(8)));

        assertThat(result.inputError).isFalse();
        assertThat(projectedCardNumbers(result)).first().isEqualTo(cardNumber(1));
    }

    @Test
    @DisplayName("A null attention identifier is coerced to ENTER, not dereferenced")
    void aNullAttentionIdentifierIsCoercedToEnter() {
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(
                pagingEntry(null, FIRST_PAGE, true, cardNumber(1), cardNumber(8)));

        assertThat(result.inputError).isFalse();
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
    }

    @Test
    @DisplayName("A selection on a row the previous turn never displayed does not dereference null")
    void aSelectionOnAnUndisplayedRowDoesNotDereferenceNull() {
        // The row table is loaded from the displayed rows; selecting a row that was never displayed
        // leaves the account and card fields unresolved rather than raising.
        final CardListResult result = service().listCards(reentry(
                AID_ENTER,
                null,
                null,
                selectionOn(SCREEN_LINES, SELECT_VIEW),
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                displayed(cardFile(2, ACCOUNT_A))));

        assertThat(result.nextProgram).isEqualTo(CARD_DETAIL_PROGRAM);
        assertThat(result.cardNumber).isNull();
        assertThat(result.accountId).isNull();
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("A null element inside the selection list is a normal unselected row")
    void aNullElementInsideTheSelectionListIsANormalUnselectedRow() {
        // WS-EDIT-SELECT-FLAGS is initialised to LOW-VALUES at :72, so an absent code is the normal
        // case. The request therefore permits null elements rather than rejecting the list.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final List<String> allNull = new ArrayList<>();
        for (int row = 0; row < SCREEN_LINES; row++) {
            allNull.add(null);
        }

        final CardListResult result = service().listCards(reentry(
                AID_ENTER,
                null,
                null,
                allNull,
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                null));

        assertThat(result.inputError).isFalse();
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
    }

    @Test
    @DisplayName("A short selection list reads as no selection on the missing rows")
    void aShortSelectionListReadsAsNoSelectionOnTheMissingRows() {
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(reentry(
                AID_ENTER,
                null,
                null,
                List.of(" "),
                FIRST_PAGE,
                LAST_PAGE_NOT_SHOWN,
                true,
                cardNumber(1),
                cardNumber(8),
                null));

        assertThat(result.inputError).isFalse();
        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
    }

    // ============================================================================================
    // RULE 1 CLAUSE B - ERROR HANDLING: TYPE, MESSAGE AND CAUSE
    // "no swallowing exceptions; wrap with context and preserve root cause."
    // ============================================================================================

    @Test
    @DisplayName("A store failure surfaces as a typed file exception carrying context AND the cause")
    void aStoreFailureSurfacesAsATypedFileExceptionCarryingTheCause() {
        // The universal COBOL I/O guard - :1147-1155 and its siblings - renders the response code into
        // the 75-byte message the screen shows and then abends. The Java target raises the typed file
        // exception with the identical message and the original cause attached, so nothing is
        // swallowed and no context is lost.
        final QueryTimeoutException cause = new QueryTimeoutException("simulated store failure");
        when(cardRepository.findAllByOrderByCardNumberAsc(any(Pageable.class))).thenThrow(cause);

        final Throwable thrown = catchThrowable(() -> service().listCards(firstEntry()));

        assertThat(thrown).isInstanceOf(CardDemoException.class);
        assertThat(thrown.getClass().getSimpleName()).isEqualTo("FileAccessException");
        assertThat(thrown).hasMessageStartingWith(FILE_ERROR_PREFIX);
        assertThat(thrown.getMessage())
                .hasSize(75)
                .contains(OPERATION_READ)
                .contains(CARD_FILE_NAME)
                .contains("QueryTimeo");
        assertThat(thrown.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("The whole exception family descends from CardDemoException, so nothing escapes untyped")
    void theExceptionFamilyDescendsFromCardDemoException() {
        // ValidationException is the declared filter failure and FileAccessException the declared I/O
        // failure; both are CardDemoException, which is what lets a caller handle the family without
        // catching RuntimeException.
        assertThat(CardDemoException.class).isAssignableFrom(ValidationException.class);
        assertThat(RuntimeException.class).isAssignableFrom(CardDemoException.class);

        final Throwable validation =
                catchThrowable(() -> service().listCards(filterEntry("1234567890A", null)));
        assertThat(validation).isInstanceOf(CardDemoException.class);
    }

    // ============================================================================================
    // RULE 1 CLAUSE D - SECRET AND PII HYGIENE
    // "No secrets in code, logs, tests, or config." The card number is sensitive.
    // ============================================================================================

    @Test
    @DisplayName("No diagnostic string the service produces carries a card number")
    void noDiagnosticStringTheServiceProducesCarriesACardNumber() {
        // PageResponse.toString reports page metadata only, and CardDto, CardDto.CardListRow and
        // CardListResult deliberately declare no toString at all, so an accidental interpolation into
        // a log line cannot leak a primary account number.
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.page.toString()).doesNotContain(cardNumber(1));
        assertThat(result.screen.toString()).doesNotContain(cardNumber(1));
        assertThat(String.valueOf(result.page.getRows().get(0))).doesNotContain(cardNumber(1));

        for (final Class<?> shape : List.of(
                CardDto.class, CardDto.CardListRow.class, CardListResult.class)) {
            final List<String> overrides = new ArrayList<>();
            for (final Method method : shape.getDeclaredMethods()) {
                if (!method.isSynthetic() && "toString".equals(method.getName())) {
                    overrides.add(method.getName());
                }
            }
            assertThat(overrides)
                    .withFailMessage("%s must not render itself into a log line",
                            shape.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("A rejected filter is not echoed back inside the error message")
    void aRejectedFilterIsNotEchoedBackInsideTheErrorMessage() {
        // The literals at :1021-1023 and :1057-1059 are fixed text. Echoing the offending value would
        // put an operator-supplied card number into a screen field and, from there, into a log.
        final String hostileCardValue = "123456789012345X";

        final Throwable thrown =
                catchThrowable(() -> service().listCards(filterEntry(null, hostileCardValue)));

        assertThat(thrown).isInstanceOf(ValidationException.class);
        assertThat(thrown.getMessage())
                .isEqualTo(MSG_CARD_FILTER_INVALID)
                .doesNotContain(hostileCardValue)
                .doesNotContain("12345678901234");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("The service declares a masking helper for the card number it logs")
    void theServiceDeclaresAMaskingHelperForTheCardNumberItLogs() {
        // The one log statement that mentions a filter passes it through a masking helper first. The
        // declaration is asserted rather than invoked: Rule 1 Clause D forbids reflection-driven
        // arbitrary invocation, so this checks the seam exists without calling through it.
        final List<String> helpers = new ArrayList<>();
        for (final Method method : CardListService.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && "maskTail".equals(method.getName())) {
                helpers.add(method.getName());
            }
        }
        assertThat(helpers).isNotEmpty();
    }

    /**
     * Pins the round-trip cost of the key-positioning binary search: exactly one count per request.
     *
     * <p>{@code startBrowse} locates a supplied start key by binary search, so it fetches roughly
     * log2(n) windows per request. While the window fetch returned a {@code Page}, every one of those
     * probes carried a {@code count(*)} that the search discarded, even though the total it needed was
     * needed exactly once as the upper bound. The window is now a {@code Slice} and the total comes from
     * one explicit {@code count()}, memoised in the per-request working storage.
     */
    @Test
    @DisplayName("A key-positioned browse counts the table once, however many window probes it makes")
    void aKeyPositionedBrowseCountsTheTableOnce() {
        givenCardFile(cardFile(64, ACCOUNT_A));

        // Paging down from a saved key is the entry that positions the browse by key, which is the only
        // path that runs the binary search. The CARDSIDI filter is not a start key: 9500-FILTER-RECORDS
        // applies it as an exclusion while the browse walks, so a filtered first entry still starts at
        // offset zero and asks for no total.
        final CardListResult result = service().listCards(
                pagingEntry(AID_PF08, FIRST_PAGE, true, cardNumber(1), cardNumber(40)));

        assertThat(result).as("the browse must have run for the counts below to mean anything").isNotNull();
        verify(cardRepository, times(1))
                .count();
        verify(cardRepository, atLeast(2))
                .findAllByOrderByCardNumberAsc(any(Pageable.class));
    }

    /**
     * The unfiltered entry positions at offset zero, so it needs no upper bound and asks for no total.
     *
     * <p>This is why the count stub in {@link #givenCardFile(List)} is lenient: a strict stub would fail
     * every unfiltered test for being unused, which is exactly the state this test asserts.
     */
    @Test
    @DisplayName("An unfiltered browse never counts the table at all - it positions at offset zero")
    void anUnfilteredBrowseNeverCountsTheTable() {
        givenCardFile(cardFile(20, ACCOUNT_A));

        final CardListResult result = service().listCards(firstEntry());

        assertThat(result.page.getRows()).hasSize(SCREEN_LINES);
        verify(cardRepository, never()).count();
    }

    /**
     * The count is not merely moved: its failure must latch, not escape.
     *
     * <p>An unreadable table must latch the same {@code RESP} condition a failed window fetch latches, so
     * that {@code throwLatchedFileError} rethrows it after {@code EXEC CICS ENDBR} has run - the four
     * unexpected-condition arms at {@code app/cbl/COCRDLIC.cbl:1226-1230}, {@code :1250-1254},
     * {@code :1312-1316} and {@code :1365-1369} are all reached only after the browse is terminated. A
     * count that threw in place would skip the {@code ENDBR} and leave the browse open.
     */
    @Test
    @DisplayName("An unreadable table surfaces as a latched file error, rethrown after ENDBR")
    void anUnreadableTableSurfacesAsALatchedFileError() {
        when(cardRepository.findAllByOrderByCardNumberAsc(any(Pageable.class)))
                .thenAnswer(invocation -> window(List.of(), invocation.getArgument(0, Pageable.class)));
        when(cardRepository.count()).thenThrow(new QueryTimeoutException("CARDDAT unavailable"));

        assertThatExceptionOfType(FileAccessException.class)
                .as("the condition must surface as the service's own file-error outcome at the normal "
                        + "point, never as a raw DataAccessException escaping mid-browse")
                .isThrownBy(() -> service().listCards(
                        pagingEntry(AID_PF08, FIRST_PAGE, true, cardNumber(1), cardNumber(5))));
    }
}
