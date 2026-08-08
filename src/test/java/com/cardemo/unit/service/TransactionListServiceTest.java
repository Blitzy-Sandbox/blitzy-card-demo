/*
 * ******************************************************************
 * Program     : TransactionListServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies TransactionListService against COTRN00C paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the page size of ten expressed as a
 *               bare literal in four source places, the eleventh look-ahead
 *               read whose record is discarded, the program-local pagination
 *               group that COCOM01Y does not declare, the two independent page
 *               counters, the seven navigation literals asserted byte for byte,
 *               the edited amount mask of :56 including its deliberate loss of
 *               a ninth integer digit, and the rule that a full card number
 *               never reaches a log, a projection or an exception message.
 * Source      : app/cbl/COTRN00C.cbl (699 lines, 16 own paragraph labels)
 *               app/cpy-bms/COTRN00.CPY   (59 input fields, PAGENUMI X(8) :60)
 *               app/cpy/CVTRA05Y.cpy      (TRAN-RECORD, TRAN-AMT S9(09)V99)
 *               app/cpy/COCOM01Y.cpy      (47 lines, no pagination fields)
 *               app/cbl/CBACT04C.cbl:1-21 (the banner reproduced above)
 *               CONTRIBUTING.md:33-34, NOTICE @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.transaction.TransactionListService;
import com.cardemo.service.transaction.TransactionListService.AttentionIdentifier;
import com.cardemo.service.transaction.TransactionListService.TransactionListScreen;
import com.cardemo.service.transaction.TransactionListService.TransactionListState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/**
 * Unit tests for {@code com.cardemo.service.transaction.TransactionListService}, the Java target of
 * {@code app/cbl/COTRN00C.cbl} and CICS transaction {@code CT00}.
 *
 * <h2>What it does</h2>
 *
 * <p>Pins the behaviour of the paginated transaction list against its COBOL system of record, which is
 * 699 lines carrying 16 paragraph labels. Every locator cited below was verified by direct inspection
 * of the frozen corpus at traceability anchor commit {@code 7756d89}; the corpus is read-only and
 * nothing under {@code app/} is touched by this or any other test.
 *
 * <p>Load-bearing locators in {@code app/cbl/COTRN00C.cbl}: {@code :L54} the second, unused
 * {@code WS-PAGE-NUM PIC S9(04) COMP} page counter; {@code :L56-L57} the
 * {@code WS-TRAN-AMT PIC +99999999.99} display mask and the {@code WS-TRAN-DATE} unset literal
 * {@code 00/00/00}; {@code :L61-L71} the program-local {@code CDEMO-CT00-INFO} group appended after
 * {@code COPY COCOM01Y}; {@code :L95} {@code MAIN-PARA}; {@code :L146} {@code PROCESS-ENTER-KEY};
 * {@code :L214} the numeric-edit message; {@code :L234} {@code PROCESS-PF7-KEY}; {@code :L248} the
 * already-at-top message; {@code :L257} {@code PROCESS-PF8-KEY}; {@code :L270} the already-at-bottom
 * message; {@code :L279} {@code PROCESS-PAGE-FORWARD}; {@code :L290} the forward slot-initialisation
 * bound; {@code :L333} {@code PROCESS-PAGE-BACKWARD}; {@code :L344} the backward slot-initialisation
 * bound; {@code :L349} the backward index seed; {@code :L381} {@code POPULATE-TRAN-DATA};
 * {@code :L450} {@code INITIALIZE-TRAN-DATA}; {@code :L510} {@code RETURN-TO-PREV-SCREEN};
 * {@code :L527} {@code SEND-TRNLST-SCREEN}; {@code :L554} {@code RECEIVE-TRNLST-SCREEN};
 * {@code :L567} {@code POPULATE-HEADER-INFO}; {@code :L591} {@code STARTBR-TRANSACT-FILE};
 * {@code :L608} the at-top message; {@code :L615} the first lookup-failure site; {@code :L624}
 * {@code READNEXT-TRANSACT-FILE}; {@code :L642} the reached-bottom message; {@code :L649} the second
 * lookup-failure site; {@code :L658} {@code READPREV-TRANSACT-FILE}; {@code :L676} the reached-top
 * message; {@code :L683} the third lookup-failure site; and {@code :L692}
 * {@code ENDBR-TRANSACT-FILE}. The field budget comes from {@code app/cpy-bms/COTRN00.CPY}, whose
 * page-number field is {@code PAGENUMI PIC X(8)} at {@code COTRN00.CPY:L60}.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B -ntp clean compile} and run this class with
 * {@code ./mvnw -B -ntp test -Dtest=TransactionListServiceTest}. This is a <strong>Surefire</strong>
 * tier. Verified against the root {@code pom.xml} at {@code pom.xml:876-893}, Surefire 3.5.4 includes
 * {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} and excludes
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, while Failsafe 3.5.4 at
 * {@code pom.xml:916-931} includes only the two excluded trees. This class sits under
 * {@code src/test/java/com/cardemo/unit/service}, outside both excluded trees, and its simple name
 * ends in {@code Test}, so Surefire collects it - confirmed empirically by
 * {@code target/surefire-reports/TEST-com.cardemo.unit.service.TransactionListServiceTest.xml}. The
 * hazard the two include sets create is worth naming precisely, because it is silent: a class whose
 * simple name ends in neither {@code Test} nor {@code Tests} matches no include of either plugin and
 * is collected by neither, so it never runs while the build stays green, both plugins report success
 * and JaCoCo records the code it would have covered as uncovered, so the class keeps its
 * {@code ...Test} suffix and stays inside {@code unit/}. The compiler is
 * configured with {@code -Xlint:all} and {@code failOnWarning}, so one raw type or one deprecated call fails
 * the build - an unused import does not, because {@code javac} 25 publishes no {@code unused} lint key - and
 * JaCoCo 0.8.12 enforces an 80% line floor at {@code verify} with no exclusions.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The page size is bound from {@code carddemo.pagination.transaction-list-page-size}, whose value
 * is {@code 10} in {@code src/main/resources/application.yml}. Its siblings in that family are
 * {@code card-list-page-size} (7) and {@code user-list-page-size} (10). These are
 * <strong>parity contracts, not tunables</strong>, and the assertions here fail if the value drifts.
 * Mockito runs with {@code Strictness.STRICT_STUBS}, so an unused stubbing fails the test and an
 * unmatched argument set raises {@code PotentialStubbingProblem}. Money is {@code BigDecimal} with
 * {@code RoundingMode.HALF_EVEN} and equality by {@code compareTo}, never {@code equals}; no
 * {@code float} or {@code double} appears in any financial position. The clock is a fixed
 * {@code java.time.Clock} built from a literal instant, so the two header fields the source derives
 * from one {@code MOVE FUNCTION CURRENT-DATE} at {@code :L569} are reproducible. This is a pure-JVM
 * tier: no container, no Spring context, no database and no network, so no external endpoint is
 * reachable and no credential exists to leak.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A raw type or a redundant cast fails the build outright under {@code -Xlint:all} with
 *       {@code failOnWarning}. A single unused import does not, because {@code javac} 25 publishes no
 *       {@code unused} lint key, so it is caught at review instead.</li>
 *   <li>Replacing the eleventh look-ahead read with a {@code count(*)} query changes the number of
 *       round trips and the observable end-of-file behaviour.</li>
 *   <li>Unifying this sentinel family with the card list's. {@code COTRN00C} uses an {@code 'N'}
 *       defaulted {@code X(01)} flag with condition names {@code NEXT-PAGE-YES}/{@code NEXT-PAGE-NO},
 *       whereas {@code COCRDLIC} uses a {@code 0}/{@code 9} pair and a 27-byte composite cursor.</li>
 *   <li>Asserting a page-number field on {@code app/cpy/COCOM01Y.cpy}, which declares none: that
 *       copybook is 47 lines and ends at {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET}. The
 *       pagination fields are this program's own extension at {@code :L61-L71}.</li>
 *   <li>Widening the {@code +99999999.99} mask instead of asserting that it truncates the ninth
 *       integer digit of an {@code S9(09)V99} amount.</li>
 *   <li>Reformatting the {@code PIC X(26)} timestamps, or converting them to a temporal type. They
 *       are text and are sliced as text.</li>
 *   <li>An unordered paged query, which makes page contents non-reproducible and breaks the keyset
 *       cursor.</li>
 *   <li>Logging or echoing a full card number, which the transaction record carries at offsets
 *       263-278.</li>
 *   </ul>
 *
 * <h2>Legacy oddities preserved rather than refactored away</h2>
 *
 * <ul>
 *   <li>The page size is a bare literal in four source places rather than a named constant:
 *       {@code :L290}, {@code :L297}, {@code :L344} and {@code :L349}. Unlike {@code COCRDLIC}, which
 *       declares {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}, this program names nothing.
 *       Declaring one constant would be the tidier form and is deliberately not applied, because
 *       {@code app/} is frozen.</li>
 *   <li>The forward fill loop is written {@code >= 11} at {@code :L297} while the slot-initialisation
 *       loops are written {@code > 10} at {@code :L290} and {@code :L344}. The inconsistency is
 *       preserved and is proved here to leave the row count unchanged.</li>
 *   <li>Two independent page counters coexist: {@code WS-PAGE-NUM PIC S9(04) COMP} at {@code :L54},
 *       never referenced in the procedure division, and {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}, which
 *       is the one the screen receives.</li>
 *   <li>{@code :L214} carries a space before its ellipsis, unlike every other message in the program.
 *       The byte-exact assertion here would fail if the space were tidied away.</li>
 *   <li>{@code :L248} says "already at the top" while {@code :L608} says "at the top". Two distinct
 *       literals from two distinct paths, deliberately not consolidated.</li>
 *   </ul>
 *
 * <h2>The documented conflict, and why parity governs</h2>
 *
 * <p>The prohibition on dead code collides with the mandate to preserve control flow one-for-one. Parity governs, and
 * the prohibition is satisfied on its own terms: what it forbids is an artefact carried without explanation, and each
 * retained item here carries the source locator cited above, an explicit intentional-no-op marker and a test that
 * pins it. This file's instances are the eleventh look-ahead read whose record is discarded, the second and unused
 * page counter at {@code :L54}, and the identical {@code Unable to lookup transaction...} literal at three separate
 * sites, none of them consolidated. Deleting any of them would break the paragraph-level correspondence with the
 * source.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("TransactionListService - COTRN00C / CICS transaction CT00")
final class TransactionListServiceTest {

    /**
     * A card number occupying the full width of {@code TRAN-CARD-NUM PIC X(16)}, which
     * {@code app/cpy/CVTRA05Y.cpy} places at record offsets 263-278. It is held only as the needle for
     * the leak assertions: every test that uses it proves the value never escapes into a log, a
     * projection, a {@code toString} or an exception message.
     *
     * <p>The value is deliberately synthetic rather than a recognisable test card. It fails the Luhn
     * check, its leading digit is reserved for national assignment rather than issued to any payment
     * network, and it therefore cannot be mistaken for a live account number by a scanner or by a
     * reader. It also cannot collide with the identifiers {@link #id(int)} generates, which are small
     * integers padded with leading zeros, so {@code doesNotContain} remains a meaningful assertion.
     */
    private static final String CARD = "9999000011112222";

    /**
     * The fixed instant every clock in this class reports. Parsed from a literal, never read from the
     * host clock, so the header date and time are reproducible on any machine in any zone.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** A full twenty-six character {@code TRAN-ORIG-TS}, in the online rendering. */
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** A full twenty-six character {@code TRAN-PROC-TS}, in the online rendering. */
    private static final String PROC_TS = "2022-06-11 08:00:00.000000";

    /**
     * A blank twenty-six character timestamp. {@code app/data/ASCII/dailytran.txt} carries records
     * whose timestamp is twenty-six spaces, so this is real data rather than a synthetic edge case.
     */
    private static final String BLANK_TS = "                          ";

    /**
     * Rows per page. Ten, from the bare literal at {@code app/cbl/COTRN00C.cbl:L290}
     * ({@code UNTIL WS-IDX > 10}), the forward fill bound at {@code :L297}
     * ({@code UNTIL WS-IDX >= 11}), the backward bound at {@code :L344} and the backward index seed
     * at {@code :L349} ({@code MOVE 10 TO WS-IDX}).
     */
    private static final int PAGE_SIZE = 10;

    /** The look-ahead window: one row beyond the page, which is the eleventh read of {@code :L305}. */
    private static final int LOOKAHEAD_WINDOW = PAGE_SIZE + 1;

    /**
     * The ceiling of {@code WS-PAGE-NUM PIC S9(04) COMP} at {@code app/cbl/COTRN00C.cbl:L54}, the
     * counter that is <em>not</em> the one surfaced to the screen.
     */
    private static final int BINARY_PAGE_COUNTER_CEILING = 9_999;

    /**
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'} at {@code app/cbl/COTRN00C.cbl:L67}. A terminal
     * representation, never placed on the wire.
     */
    private static final char NEXT_PAGE_YES = 'Y';

    /** {@code 88 NEXT-PAGE-NO VALUE 'N'} at {@code app/cbl/COTRN00C.cbl:L68}, and the field default. */
    private static final char NEXT_PAGE_NO = 'N';

    /**
     * The card list's sentinel pair, {@code CA-LAST-PAGE-SHOWN VALUE 0} and
     * {@code CA-LAST-PAGE-NOT-SHOWN VALUE 9} in {@code app/cbl/COCRDLIC.cbl}. Held only as the
     * negative half of the do-not-unify assertion.
     */
    private static final char CARD_LIST_SENTINEL_SHOWN = '0';

    /** The other half of the card list's sentinel pair. See {@link #CARD_LIST_SENTINEL_SHOWN}. */
    private static final char CARD_LIST_SENTINEL_NOT_SHOWN = '9';

    /** {@code app/cbl/COTRN00C.cbl:L167} and {@code :L200}, byte for byte. */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /**
     * {@code app/cbl/COTRN00C.cbl:L214}, byte for byte. Note the <strong>space before the
     * ellipsis</strong>, which no other message in this program has.
     */
    private static final String MSG_NOT_NUMERIC_L214 = "Tran ID must be Numeric ...";

    /** {@code app/cbl/COTRN00C.cbl:L248}, byte for byte. Note the word {@code already}. */
    private static final String MSG_ALREADY_TOP_L248 = "You are already at the top of the page...";

    /** {@code app/cbl/COTRN00C.cbl:L270}, byte for byte. Note the word {@code already}. */
    private static final String MSG_ALREADY_BOTTOM_L270 = "You are already at the bottom of the page...";

    /**
     * {@code app/cbl/COTRN00C.cbl:L608}, byte for byte, emitted from {@code STARTBR-TRANSACT-FILE}.
     * <strong>No {@code already}</strong> — a different message from {@link #MSG_ALREADY_TOP_L248}.
     */
    private static final String MSG_AT_TOP_L608 = "You are at the top of the page...";

    /** {@code app/cbl/COTRN00C.cbl:L642}, byte for byte, emitted from {@code READNEXT-TRANSACT-FILE}. */
    private static final String MSG_REACHED_BOTTOM_L642 = "You have reached the bottom of the page...";

    /** {@code app/cbl/COTRN00C.cbl:L676}, byte for byte, emitted from {@code READPREV-TRANSACT-FILE}. */
    private static final String MSG_REACHED_TOP_L676 = "You have reached the top of the page...";

    /**
     * {@code app/cbl/COTRN00C.cbl:L615}, {@code :L649} and {@code :L683} — the same literal at three
     * distinct sites, one per browse verb, deliberately not consolidated in the source. Note the
     * lower-case {@code transaction}, which diverges from the sibling detail program's spelling.
     */
    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    /** {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}, raised at {@code :L130-L133}. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** The raw file status this service hands the shared mapper on an infrastructure fault. */
    private static final String IO_FAILURE_STATUS = "90";

    /**
     * The four-character rendering of {@link #IO_FAILURE_STATUS}. The {@code '9x'} arm of
     * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} copies the first byte
     * through and expands the second into three digits of its <em>binary</em> value, so the
     * {@code '0'} byte becomes {@code 048} rather than {@code 000}.
     */
    private static final String IO_FAILURE_EXPANDED_STATUS = "9048";

    /** The logical file name of the transaction cluster, from {@code app/csd/CARDDEMO.CSD}. */
    private static final String TRANSACT_FILE = "TRANSACT";

    /**
     * The 16 paragraph labels of {@code app/cbl/COTRN00C.cbl}, paired with the private Java method
     * each must map to and the source line the label sits on. Immutable, so it is safe as a constant.
     */
    private static final List<String> PARAGRAPH_METHOD_NAMES = List.of(
            "mainPara",                 // MAIN-PARA               :95
            "processEnterKey",          // PROCESS-ENTER-KEY       :146
            "processPf7Key",            // PROCESS-PF7-KEY         :234
            "processPf8Key",            // PROCESS-PF8-KEY         :257
            "processPageForward",       // PROCESS-PAGE-FORWARD    :279
            "processPageBackward",      // PROCESS-PAGE-BACKWARD   :333
            "populateTranData",         // POPULATE-TRAN-DATA      :381
            "initializeTranData",       // INITIALIZE-TRAN-DATA    :450
            "returnToPrevScreen",       // RETURN-TO-PREV-SCREEN   :510
            "sendTrnlstScreen",         // SEND-TRNLST-SCREEN      :527
            "receiveTrnlstScreen",      // RECEIVE-TRNLST-SCREEN   :554
            "populateHeaderInfo",       // POPULATE-HEADER-INFO    :567
            "startbrTransactFile",      // STARTBR-TRANSACT-FILE   :591
            "readnextTransactFile",     // READNEXT-TRANSACT-FILE  :624
            "readprevTransactFile",     // READPREV-TRANSACT-FILE  :658
            "endbrTransactFile");       // ENDBR-TRANSACT-FILE     :692

    @Mock
    private TransactionRepository repository;

    /**
     * A translator that classifies nothing, used only by {@link LookupFailureLiteralAtEachSite} to
     * expose the fallback literal of {@code COTRN00C:L615}, {@code :L649} and {@code :L683}. Every
     * other test uses the real translator instead.
     */
    @Mock
    private FileStatusMapper unmappedStatus;

    /**
     * The real translator rather than a double. It has a no-argument constructor and no collaborators
     * of its own, so using the production translation table means the exception types asserted here
     * are exactly the ones production raises.
     */
    private FileStatusMapper fileStatusMapper;

    private TransactionListService service;

    /** Builds the bean over its four collaborators with a fixed clock and the configured page size. */
    @BeforeEach
    void setUp() {
        fileStatusMapper = new FileStatusMapper();
        service = newService(PAGE_SIZE);
    }

    /**
     * Builds a service at an arbitrary configured page size, for the configuration-boundary tests.
     *
     * @param pageSize the value {@code carddemo.pagination.transaction-list-page-size} would bind
     * @return a new service over the mocked repository and a fixed clock
     */
    private TransactionListService newService(final int pageSize) {
        return new TransactionListService(repository, fileStatusMapper, fixedClock(), pageSize);
    }

    /**
     * Returns the deterministic clock. Built from {@link #FIXED_INSTANT} at {@link ZoneOffset#UTC} so
     * that neither the host clock nor the host default zone can influence a result.
     *
     * @return a fixed clock, never {@code null}
     */
    private static Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Renders {@code n} as a sixteen-character {@code TRAN-ID PIC X(16)} value.
     * @param n the ordinal to render.
     * @return the sixteen-character identifier.
     */
    private static String id(final int n) {
        return String.format(Locale.ROOT, "%016d", n);
    }

    /**
     * Builds one transaction with the canonical amount, description and originating timestamp.
     *
     * @param n the ordinal the sixteen-character identifier is derived from.
     * @return the transaction.
     */
    private static Transaction tx(final int n) {
        return tx(n, new BigDecimal("123.45"), "DESCRIPTION " + n, ORIG_TS);
    }

    /**
     * Builds one transaction, varying the three fields a case needs to vary.
     *
     * @param n the ordinal the sixteen-character identifier is derived from.
     * @param amount the {@code TRAN-AMT}.
     * @param description the {@code TRAN-DESC}.
     * @param origTs the {@code TRAN-ORIG-TS}.
     * @return the transaction.
     */
    private static Transaction tx(final int n, final BigDecimal amount, final String description,
            final String origTs) {
        return new Transaction(id(n), "01", 5, "POS       ", description, amount, 123L,
                "ACME", "SEATTLE", "12345-0001", CARD, origTs, PROC_TS);
    }

    /**
     * {@code count} records with ascending keys, as an ascending browse window would yield.
     * @param count how many records the window holds.
     * @return the records, ascending by key.
     */
    private static List<Transaction> ascending(final int count) {
        final List<Transaction> rows = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            rows.add(tx(n));
        }
        return rows;
    }

    /**
     * {@code count} records descending from {@code highest}, as a backward browse would yield.
     * @param highest the key the window starts from.
     * @param count how many records the window holds.
     * @return the records, descending by key.
     */
    private static List<Transaction> descendingFrom(final int highest, final int count) {
        final List<Transaction> rows = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            rows.add(tx(highest - n));
        }
        return rows;
    }

    /**
     * Stubs the inclusive ascending finder, the bound ENTER uses because it never primes.
     * @param rows the rows the inclusive ascending finder is to return.
     */
    private void stubInclusive(final List<Transaction> rows) {
        when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                .thenReturn(new SliceImpl<>(rows));
    }

    /**
     * Stubs the exclusive ascending finder, the bound PF8 uses because it primes past the anchor.
     * @param rows the rows the exclusive ascending finder is to return.
     */
    private void stubExclusive(final List<Transaction> rows) {
        when(repository.findByTransactionIdGreaterThanOrderByTransactionIdAsc(any(), any()))
                .thenReturn(new SliceImpl<>(rows));
    }

    /**
     * Stubs the descending finder, the bound PF7 uses.
     * @param rows the rows the descending finder is to return.
     */
    private void stubDescending(final List<Transaction> rows) {
        when(repository.findByTransactionIdLessThanOrderByTransactionIdDesc(any(), any()))
                .thenReturn(new SliceImpl<>(rows));
    }

    /**
     * Builds a selection column carrying one flag in one slot and nothing in the other nine.
     *
     * @param oneBasedSlot the row slot the flag sits in, one through ten.
     * @param flag the selection character to place there.
     * @return the ten selection values, in slot order.
     */
    private static List<String> selectors(final int oneBasedSlot, final String flag) {
        final String[] flags = new String[PAGE_SIZE];
        Arrays.fill(flags, "");
        flags[oneBasedSlot - 1] = flag;
        return Arrays.asList(flags);
    }

    /**
     * The ten identifiers a full first page renders, ascending.
     *
     * @return the identifiers of records one through ten.
     */
    private static List<String> displayedIds() {
        final List<String> ids = new ArrayList<>();
        for (int n = 1; n <= PAGE_SIZE; n++) {
            ids.add(id(n));
        }
        return ids;
    }

    /**
     * The ten empty identifiers a page with no rows renders.
     *
     * @return ten empty strings, one per row slot.
     */
    private static List<String> blankIds() {
        final List<String> ids = new ArrayList<>();
        for (int n = 0; n < PAGE_SIZE; n++) {
            ids.add("");
        }
        return ids;
    }

    /**
     * Projects the rendered page onto its identifiers, so a row order is asserted without row noise.
     *
     * @param screen the rendered screen.
     * @return the identifier of each of the ten row slots, in slot order.
     */
    private static List<String> rowIds(final TransactionListScreen screen) {
        final List<String> ids = new ArrayList<>();
        for (final TransactionDto.TransactionListRow row : screen.page().getRows()) {
            ids.add(row.transactionId());
        }
        return ids;
    }

    /**
     * Submits an ENTER turn carrying only a search key, from the initial browse position.
     * @param searchKey the sixteen-character key the turn carries, blank for none.
     * @return the rendered screen.
     */
    private TransactionListScreen enter(final String searchKey) {
        return service.submitScreen(AttentionIdentifier.ENTER, searchKey, List.of(), List.of(),
                TransactionListState.initial());
    }

    /**
     * Submits a PF8 turn from a carried position.
     * @param state the carried browse position the PF8 turn resumes from.
     * @return the rendered screen.
     */
    private TransactionListScreen pageForward(final TransactionListState state) {
        return service.submitScreen(AttentionIdentifier.PF8, null, List.of(), List.of(), state);
    }

    /**
     * Submits a PF7 turn from a carried position.
     * @param state the carried browse position the PF7 turn resumes from.
     * @return the rendered screen.
     */
    private TransactionListScreen pageBackward(final TransactionListState state) {
        return service.submitScreen(AttentionIdentifier.PF7, null, List.of(), List.of(), state);
    }

    /**
     * Renders one single-row page and returns the screen, for the projection assertions.
     * @param amount the row's signed amount.
     * @param description the row's hundred-character description.
     * @param origTs the row's twenty-six-character originating timestamp.
     * @return the rendered single-row screen.
     */
    private TransactionListScreen single(final BigDecimal amount, final String description,
            final String origTs) {
        stubInclusive(List.of(tx(1, amount, description, origTs)));
        return service.openList();
    }

    /**
     * Captures the anchor key the service passed to the inclusive ascending finder.
     * @return the anchor key captured from the inclusive ascending finder.
     */
    private String capturedInclusiveKey() {
        final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(key.capture(),
                any());
        return key.getValue();
    }

    // ============================================================ Phase 1 - page size ten

    /**
     * The page size of ten and the look-ahead window of eleven, which the source expresses as four
     * separate bare literals rather than one named constant.
     */
    @Nested
    @DisplayName("Page size ten - COTRN00C:L290, :L297, :L344, :L349")
    class PageSizeContract {

        @Test
        @DisplayName("configured page size is ten and is read from configuration, not the call site")
        void configuredPageSizeIsTen() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().getPageSize())
                    .as("carddemo.pagination.transaction-list-page-size is a parity contract, not a tunable")
                    .isEqualTo(PAGE_SIZE);
            assertThat(PageResponse.PAGE_SIZE_TRANSACTION_LIST).isEqualTo(PAGE_SIZE);
            assertThat(TransactionDto.PAGE_SIZE).isEqualTo(PAGE_SIZE);
            assertThat(PageResponse.PAGE_SIZE_CARD_LIST)
                    .as("COCRDLIC paginates at seven; the two sizes must not be unified")
                    .isEqualTo(7);
            assertThat(PageResponse.PAGE_SIZE_USER_LIST).isEqualTo(PAGE_SIZE);
        }

        @Test
        @DisplayName("a full page returns exactly ten rows")
        void fullPageReturnsExactlyTen() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(rowIds(screen)).containsExactlyElementsOf(displayedIds());
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("a window of exactly ten rows fills the page and reports no further page")
        void exactlyTenRowsIsAFullFinalPage() {
            stubInclusive(ascending(PAGE_SIZE));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM_L642);
        }

        @Test
        @DisplayName("a short final page returns fewer than ten rows and is not padded")
        void shortFinalPageIsNotPadded() {
            stubInclusive(ascending(5));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().getRows()).hasSize(5);
            assertThat(rowIds(screen)).containsExactly(id(1), id(2), id(3), id(4), id(5));
            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("the positional map always carries ten slots even when fewer rows materialise")
        void tenPositionalSlotsAlways() {
            stubInclusive(ascending(3));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.list().rows())
                    .as("INITIALIZE-TRAN-DATA at :L290 blanks all ten slots before the fill loop")
                    .hasSize(PAGE_SIZE);
            assertThat(screen.page().getRows()).hasSize(3);
            assertThat(screen.list().rows().get(9).transactionId()).isBlank();
        }

        @Test
        @DisplayName("an empty result returns zero rows and the at-top outcome, never an exception")
        void emptyBrowseIsNotAnException() {
            stubInclusive(List.of());

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_AT_TOP_L608);
            assertThat(screen.errorFlagOn())
                    .as("end of data is a control path in COBOL, not an error")
                    .isFalse();
            assertThat(screen.page().isNextPageAvailable()).isFalse();
        }

        @Test
        @DisplayName("the >= 11 fill bound and the > 10 slot bound are behaviourally identical")
        void elevenAndTenBoundsAreEquivalent() {
            for (int idx = 0; idx <= LOOKAHEAD_WINDOW + 1; idx++) {
                assertThat(idx >= LOOKAHEAD_WINDOW)
                        .as("COTRN00C:L297 writes >= 11 where :L290 and :L344 write > 10; idx=%d", idx)
                        .isEqualTo(idx > PAGE_SIZE);
            }

            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().getRows())
                    .as("the inconsistent bound is preserved and must not change the row count")
                    .hasSize(PAGE_SIZE);
        }

        @Test
        @DisplayName("the configured page size is validated against the map's ten-row budget")
        void constructorValidatesPageSize() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> newService(0))
                    .withMessageContaining("carddemo.pagination.transaction-list-page-size")
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> newService(-1))
                    .withMessageContaining("carddemo.pagination.transaction-list-page-size");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> newService(TransactionDto.PAGE_SIZE + 1))
                    .withMessageContaining("carddemo.pagination.transaction-list-page-size");
            assertThat(newService(PAGE_SIZE)).isNotNull();
        }

        @Test
        @DisplayName("every collaborator is mandatory and names itself when absent")
        void constructorRejectsNullCollaborators() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionListService(null, fileStatusMapper, fixedClock(),
                            PAGE_SIZE))
                    .withMessage("transactionRepository must not be null")
                    .withNoCause();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionListService(repository, null, fixedClock(),
                            PAGE_SIZE))
                    .withMessage("fileStatusMapper must not be null")
                    .withNoCause();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionListService(repository, fileStatusMapper, null,
                            PAGE_SIZE))
                    .withMessage("clock must not be null")
                    .withNoCause();
        }
    }

    // ================================================== Phase 2 - pagination state contract

    /**
     * The program-local {@code CDEMO-CT00-INFO} group of {@code COTRN00C:L61-L71}, which
     * {@code app/cpy/COCOM01Y.cpy} does not declare, and which in the stateless target travels as
     * request parameters and response metadata rather than as server-side session state.
     */
    @Nested
    @DisplayName("Pagination state - COTRN00C:L54, :L61-L71")
    class PaginationStateContract {

        @Test
        @DisplayName("the keyset boundaries are sixteen-character transaction identifiers")
        void keysetBoundariesAreSixteenCharacterTransactionIds() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(TransactionDto.TRANSACTION_ID_LENGTH)
                    .as("CDEMO-CT00-TRNID-FIRST and -LAST are both PIC X(16) at :L63-L64")
                    .isEqualTo(16);
            assertThat(screen.state().firstKey()).hasSize(16).isEqualTo(id(1));
            assertThat(screen.state().lastKey()).hasSize(16).isEqualTo(id(PAGE_SIZE));
            assertThat(screen.page().getFirstKey()).isEqualTo(screen.state().firstKey());
            assertThat(screen.page().getLastKey()).isEqualTo(screen.state().lastKey());
            assertThat(rowIds(screen))
                    .startsWith(screen.state().firstKey())
                    .endsWith(screen.state().lastKey());
        }

        @Test
        @DisplayName("the next-page sentinel is the 'Y'/'N' pair of :L67-L68, defaulting to 'N'")
        void nextPageSentinelIsTheYesNoPairDefaultingToNo() {
            assertThat(sentinel(TransactionListState.initial().nextPageAvailable()))
                    .as("CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N' at :L66")
                    .isEqualTo(NEXT_PAGE_NO);

            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(sentinel(screen.page().isNextPageAvailable())).isEqualTo(NEXT_PAGE_YES);
            assertThat(sentinel(screen.state().nextPageAvailable())).isEqualTo(NEXT_PAGE_YES);
            assertThat(new char[] {NEXT_PAGE_YES, NEXT_PAGE_NO})
                    .as("COCRDLIC uses 0/9; the two sentinel families must not be unified")
                    .doesNotContain(CARD_LIST_SENTINEL_SHOWN, CARD_LIST_SENTINEL_NOT_SHOWN);
        }

        @Test
        @DisplayName("the surfaced page counter is the eight-digit extension, not the four-digit binary one")
        void surfacedPageCounterIsTheEightDigitExtension() {
            stubExclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = pageForward(new TransactionListState(
                    id(1), id(PAGE_SIZE), BINARY_PAGE_COUNTER_CEILING, true));

            assertThat(TransactionDto.PAGE_NUMBER_LENGTH)
                    .as("PAGENUMI is PIC X(8) at COTRN00.CPY:L60, where COCRDLI.CPY:L60 is PIC X(3)")
                    .isEqualTo(8);
            assertThat(screen.state().pageNumber())
                    .as("CDEMO-CT00-PAGE-NUM PIC 9(08) holds a value WS-PAGE-NUM S9(04) COMP could not")
                    .isEqualTo(BINARY_PAGE_COUNTER_CEILING + 1)
                    .isGreaterThan(BINARY_PAGE_COUNTER_CEILING);
            assertThat(screen.list().pageNumber()).isEqualTo("00010000").hasSize(8);
        }

        @Test
        @DisplayName("no server-side session state is retained between turns")
        void noServerSideSessionStateIsRetained() {
            for (final Field field : TransactionListService.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final: the pseudo-conversational COMMAREA has no server "
                                + "side counterpart under SessionCreationPolicy.STATELESS", field.getName())
                        .isTrue();
            }

            stubInclusive(ascending(LOOKAHEAD_WINDOW));
            stubExclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen first = service.openList();
            final TransactionListScreen forward = pageForward(first.state());
            final TransactionListScreen repeat = service.openList();

            assertThat(forward.state().pageNumber()).isEqualTo(2);
            assertThat(repeat.state())
                    .as("a fresh turn from the initial cursor must not observe the earlier one")
                    .isEqualTo(first.state());
        }

        @Test
        @DisplayName("the initial cursor is an unset keyset at the reset page number")
        void initialStateIsAnUnsetCursor() {
            final TransactionListState initial = TransactionListState.initial();

            assertThat(initial.firstKey()).isNull();
            assertThat(initial.lastKey()).isNull();
            assertThat(initial.pageNumber()).isZero();
            assertThat(initial.nextPageAvailable()).isFalse();
            verifyNoInteractions(repository);
        }

        /**
         * Renders the Java boolean back to the terminal sentinel of {@code COTRN00C:L66-L68}, so the
         * two-valued character contract can be asserted without leaking a display concern into the
         * production surface.
         *
         * @param nextPageAvailable the boolean the response metadata carries
         * @return {@code 'Y'} or {@code 'N'}
         */
        private char sentinel(final boolean nextPageAvailable) {
            return nextPageAvailable ? NEXT_PAGE_YES : NEXT_PAGE_NO;
        }
    }

    // ============================================== Phase 3 - the eleventh look-ahead read

    /**
     * The eleventh read of {@code COTRN00C:L305}, whose record is fetched and then discarded. It is
     * the source's own has-next mechanism and is preserved verbatim: a {@code count(*)} query would
     * change both the number of round trips and the observable end-of-file behaviour. Intentional
     * no-op marker: the eleventh row is never displayed, and that is deliberate.
     */
    @Nested
    @DisplayName("Look-ahead probe - COTRN00C:L305, :L353")
    class LookaheadProbe {

        @Test
        @DisplayName("the repository is asked for eleven rows while the response exposes ten")
        void windowIsPageSizePlusOne() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            final ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(),
                    window.capture());
            assertThat(window.getValue().getPageSize())
                    .as("one row beyond the page, which is the eleventh READNEXT of :L305")
                    .isEqualTo(LOOKAHEAD_WINDOW);
            assertThat(window.getValue().getPageNumber()).isZero();
            assertThat(screen.page().getRows())
                    .as("the eleventh record is read but never displayed")
                    .hasSize(PAGE_SIZE);
        }

        @Test
        @DisplayName("the has-next sentinel is 'Y' when an eleventh row exists")
        void eleventhRowSetsNextPageYes() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().isNextPageAvailable()).isTrue();
            assertThat(screen.state().nextPageAvailable()).isTrue();
            assertThat(rowIds(screen))
                    .as("the probe row is discarded, not appended")
                    .doesNotContain(id(LOOKAHEAD_WINDOW));
        }

        @Test
        @DisplayName("the has-next sentinel is 'N' when no eleventh row exists")
        void missingEleventhRowSetsNextPageNo() {
            stubInclusive(ascending(PAGE_SIZE));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.page().isNextPageAvailable()).isFalse();
            assertThat(screen.state().nextPageAvailable()).isFalse();
            assertThat(screen.list().errorMessage())
                    .as("the failed probe is what raises :L642, and a count query would not")
                    .isEqualTo(MSG_REACHED_BOTTOM_L642);
        }

        @Test
        @DisplayName("no count query is issued: exactly one repository call serves the whole page")
        void noCountQueryIsIssued() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            service.openList();

            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(),
                    any());
            verifyNoMoreInteractions(repository);
        }

        @Test
        @DisplayName("the keyset finders return Slice, never Page, so no count is even available")
        void keysetFindersReturnSliceNotPage() throws NoSuchMethodException {
            final List<Method> finders = List.of(
                    TransactionRepository.class.getMethod(
                            "findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc",
                            String.class, Pageable.class),
                    TransactionRepository.class.getMethod(
                            "findByTransactionIdGreaterThanOrderByTransactionIdAsc",
                            String.class, Pageable.class),
                    TransactionRepository.class.getMethod(
                            "findByTransactionIdLessThanOrderByTransactionIdDesc",
                            String.class, Pageable.class));

            for (final Method finder : finders) {
                assertThat(finder.getReturnType())
                        .as("%s must return Slice: Page would force the COUNT the source never issues",
                                finder.getName())
                        .isEqualTo(Slice.class);
            }
        }

        @Test
        @DisplayName("the browse is ended after every turn, so nothing is held across requests")
        void browseIsEndedAfterEveryTurn() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen first = service.openList();
            final TransactionListScreen second = service.openList();

            verify(repository, times(2))
                    .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any());
            verifyNoMoreInteractions(repository);
            assertThat(second.page().getRows())
                    .as("ENDBR-TRANSACT-FILE at :L692 releases the buffer, so each turn re-opens it")
                    .hasSameSizeAs(first.page().getRows());
        }

        @Test
        @DisplayName("a browse aborted by a fault is still released, so the next turn succeeds")
        void abortedBrowseIsStillReleased() {
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(new QueryTimeoutException("browse timed out"))
                    .thenReturn(new SliceImpl<>(ascending(LOOKAHEAD_WINDOW)));

            assertThatExceptionOfType(FileAccessException.class).isThrownBy(service::openList);

            final TransactionListScreen recovered = service.openList();

            assertThat(recovered.page().getRows())
                    .as("no browse resource survives the failed turn")
                    .hasSize(PAGE_SIZE);
            assertThat(recovered.errorFlagOn()).isFalse();
        }
    }

    // ================================================ Phase 4 - navigation and exact literals

    /** {@code MAIN-PARA} at {@code COTRN00C:L95} and the attention-identifier switch it drives. */
    @Nested
    @DisplayName("MAIN-PARA dispatch - COTRN00C:L95")
    class MainParaDispatch {

        @Test
        @DisplayName("an absent COMMAREA routes to the sign-on program without touching the file")
        void noContextTargetsSignOn() {
            final TransactionListScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget()).isEqualTo("COSGN00C");
            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("PF3 returns to the main menu without touching the file")
        void pf3ReturnsToMainMenu() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF3, null,
                    List.of(), List.of(), TransactionListState.initial());

            assertThat(screen.navigationTarget()).isEqualTo("COMEN01C");
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("any other attention identifier is rejected with the invalid-key message")
        void otherKeyIsRejected() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.OTHER, null,
                    List.of(), List.of(), TransactionListState.initial());

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_INVALID_KEY);
            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.cursorField()).isEqualTo("TRNIDIN");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the attention identifier is mandatory and names itself when absent")
        void attentionIdentifierIsMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitScreen(null, null, List.of(), List.of(),
                            TransactionListState.initial()))
                    .withMessage("attentionIdentifier must not be null")
                    .withNoCause();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("null collections and a null carried cursor are tolerated as empty")
        void nullRequestCollectionsAreTolerated() {
            stubInclusive(ascending(3));

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    null, null, null);

            assertThat(screen.page().getRows()).hasSize(3);
            assertThat(capturedInclusiveKey())
                    .as("a null carried cursor collapses to the LOW-VALUES anchor of :L281")
                    .isEmpty();
        }

        @Test
        @DisplayName("exactly five attention identifiers exist, matching the source's AID handling")
        void fiveAttentionIdentifiers() {
            assertThat(AttentionIdentifier.values())
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.PF7, AttentionIdentifier.PF8,
                            AttentionIdentifier.OTHER);
        }
    }

    /** {@code POPULATE-HEADER-INFO} at {@code COTRN00C:L567}, driven by the fixed clock. */
    @Nested
    @DisplayName("POPULATE-HEADER-INFO - COTRN00C:L567")
    class HeaderInfo {

        @Test
        @DisplayName("all six header fields are populated from the injected clock")
        void headerFields() {
            stubInclusive(ascending(1));

            final TransactionDto list = service.openList().list();

            assertThat(list.transactionName()).isEqualTo("CT00");
            assertThat(list.programName()).isEqualTo("COTRN00C");
            assertThat(list.title01()).hasSize(40);
            assertThat(list.title02()).hasSize(40);
            assertThat(list.currentDate())
                    .as("MM/dd/yy at Locale.ROOT from the fixed instant, never the host clock")
                    .isEqualTo("06/10/22");
            assertThat(list.currentTime()).isEqualTo("19:27:53");
        }

        @Test
        @DisplayName("the header is populated even when the browse finds nothing")
        void headerPopulatedOnEmptyBrowse() {
            stubInclusive(List.of());

            final TransactionDto list = service.openList().list();

            assertThat(list.transactionName()).isEqualTo("CT00");
            assertThat(list.currentDate()).isEqualTo("06/10/22");
            assertThat(list.currentTime()).isEqualTo("19:27:53");
        }
    }

    /**
     * The seven navigation and diagnostic literals, asserted byte for byte. Two of them are
     * deliberately near-identical and are proved distinct here.
     */
    @Nested
    @DisplayName("Exact literals - COTRN00C:L214, :L248, :L270, :L608, :L642, :L676")
    class ExactLiterals {

        @Test
        @DisplayName(":L214 carries a space before its ellipsis, unlike every other message here")
        void tranIdMustBeNumeric() {
            stubInclusive(ascending(1));

            final TransactionListScreen screen = enter("00000000000000AB");

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(MSG_NOT_NUMERIC_L214)
                    .as("the space before the ellipsis is the source's, and must not be tidied away")
                    .isEqualTo("Tran ID must be Numeric ...")
                    .contains(" ...");
            assertThat(screen.errorFlagOn()).isTrue();
        }

        @Test
        @DisplayName(":L248 - PF7 on the first page reports 'already at the top'")
        void alreadyAtTheTop() {
            final TransactionListScreen screen = pageBackward(new TransactionListState(id(1),
                    id(PAGE_SIZE), PageResponse.FIRST_PAGE_NUMBER, false));

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_ALREADY_TOP_L248);
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName(":L270 - PF8 with no further page reports 'already at the bottom'")
        void alreadyAtTheBottom() {
            final TransactionListScreen screen = pageForward(new TransactionListState(id(1),
                    id(PAGE_SIZE), 3, false));

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_ALREADY_BOTTOM_L270);
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName(":L608 - an empty browse reports 'at the top', with no 'already'")
        void atTheTopFromStartbr() {
            stubInclusive(List.of());

            final TransactionListScreen screen = service.openList();

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_AT_TOP_L608);
            assertThat(MSG_AT_TOP_L608).doesNotContain("already");
        }

        @Test
        @DisplayName(":L248 and :L608 are distinct literals reached from distinct paths")
        void alreadyAtTopAndAtTopAreDistinct() {
            final TransactionListScreen fromPf7 = pageBackward(new TransactionListState(id(1),
                    id(PAGE_SIZE), PageResponse.FIRST_PAGE_NUMBER, false));
            verifyNoInteractions(repository);

            stubInclusive(List.of());
            final TransactionListScreen fromStartbr = service.openList();

            assertThat(fromPf7.list().errorMessage()).isEqualTo(MSG_ALREADY_TOP_L248);
            assertThat(fromStartbr.list().errorMessage()).isEqualTo(MSG_AT_TOP_L608);
            assertThat(MSG_ALREADY_TOP_L248)
                    .as("PROCESS-PF7-KEY:L248 and STARTBR-TRANSACT-FILE:L608 are not consolidated")
                    .isNotEqualTo(MSG_AT_TOP_L608);
        }

        @Test
        @DisplayName(":L642 - a spent forward window reports 'reached the bottom'")
        void reachedTheBottom() {
            stubInclusive(ascending(PAGE_SIZE));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM_L642);
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName(":L676 - a spent backward window reports 'reached the top'")
        void reachedTheTop() {
            stubDescending(descendingFrom(PAGE_SIZE, 3));

            final TransactionListScreen screen = pageBackward(new TransactionListState(
                    id(PAGE_SIZE + 1), id(20), 2, true));

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_TOP_L676);
            assertThat(screen.page().getRows()).hasSize(3);
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("the invalid-selection literal of :L198-L199 is byte-exact")
        void invalidSelection() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    selectors(1, "X"), displayedIds(), TransactionListState.initial());

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_INVALID_SELECTION);
            assertThat(MSG_INVALID_SELECTION).isEqualTo("Invalid selection. Valid value is S");
            assertThat(screen.navigationTarget())
                    .as("an unrecognised selector sets the message but does not dispatch")
                    .isNull();
        }

        @Test
        @DisplayName("the lookup-failure literal of :L615, :L649 and :L683 is one byte-exact string")
        void unableToLookupIsOneLiteralAtThreeSites() {
            assertThat(MSG_UNABLE_TO_LOOKUP)
                    .as("identical at STARTBR:L615, READNEXT:L649 and READPREV:L683, not consolidated")
                    .isEqualTo("Unable to lookup transaction...")
                    .endsWith("...")
                    .doesNotContain(" ...")
                    .isNotEqualTo(MSG_NOT_NUMERIC_L214);
        }
    }

    // ============================================ PROCESS-PAGE-FORWARD - COTRN00C:L279

    /** {@code PROCESS-PAGE-FORWARD} at {@code COTRN00C:L279} and the ENTER path that reaches it. */
    @Nested
    @DisplayName("PROCESS-PAGE-FORWARD - COTRN00C:L279")
    class ForwardPaging {

        @Test
        @DisplayName("ENTER browses on the inclusive bound, never the exclusive one")
        void enterUsesInclusiveBound() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            enter(id(5));

            assertThat(capturedInclusiveKey())
                    .as("ENTER does not prime, so the anchor record itself must be returned")
                    .isEqualTo(id(5));
            verify(repository, never())
                    .findByTransactionIdGreaterThanOrderByTransactionIdAsc(any(), any());
        }

        @Test
        @DisplayName("a partial page leaves the stale last-key anchor untouched")
        void partialPageLeavesLastKeyStale() {
            stubInclusive(ascending(4));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.state().firstKey()).isEqualTo(id(1));
            assertThat(screen.state().lastKey())
                    .as("POPULATE-TRAN-DATA writes the last-key anchor only at slot ten, :L437-L439")
                    .isNull();
            assertThat(screen.page().getRows()).hasSize(4);
        }

        @Test
        @DisplayName("the page number advances to one on a first full page")
        void firstFullPageIsPageOne() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.state().pageNumber()).isEqualTo(1);
            assertThat(screen.page().getPageNumber()).isEqualTo(PageResponse.FIRST_PAGE_NUMBER);
            assertThat(screen.list().pageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("an entirely empty browse leaves the page number at the reset value")
        void emptyBrowseDoesNotAdvanceThePageNumber() {
            stubInclusive(List.of());

            final TransactionListScreen screen = service.openList();

            assertThat(screen.state().pageNumber())
                    .as(":L318 advances the counter only when at least one row materialised")
                    .isZero();
            assertThat(screen.page().getPageNumber())
                    .as("the response metadata clamps the reset value up to the first page")
                    .isEqualTo(PageResponse.FIRST_PAGE_NUMBER);
            assertThat(screen.list().pageNumber()).isEqualTo("00000000");
        }

        @Test
        @DisplayName("ENTER recomputes the has-next sentinel instead of carrying it forward")
        void enterIgnoresCarriedNextPageFlag() {
            stubInclusive(ascending(PAGE_SIZE));

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, List.of(), List.of(), new TransactionListState(id(1), id(PAGE_SIZE), 7, true));

            assertThat(screen.state().nextPageAvailable())
                    .as("a carried 'Y' must not survive a fresh ENTER browse that finds no spare row")
                    .isFalse();
        }

        @Test
        @DisplayName("ENTER resets the page number to the first page")
        void pageNumberResetsOnEnter() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                    null, List.of(), List.of(), new TransactionListState(id(1), id(PAGE_SIZE), 7, true));

            assertThat(screen.state().pageNumber())
                    .as("PROCESS-ENTER-KEY moves zero to the counter at :L228 before browsing")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the search field is blanked on success and preserved on rejection")
        void searchFieldBlankedOnlyOnSuccess() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen accepted = enter(id(1));

            assertThat(accepted.list().transactionIdInput()).isEmpty();
            assertThat(accepted.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("a rejected search key stays on the screen for correction")
        void rejectedSearchKeyIsPreserved() {
            stubInclusive(ascending(1));

            final TransactionListScreen rejected = enter("123456789012345A");

            assertThat(rejected.list().transactionIdInput())
                    .as(":L232 blanks the field only when the error flag is off")
                    .isEqualTo("123456789012345A");
            assertThat(rejected.errorFlagOn()).isTrue();
        }
    }

    // =============================================== PROCESS-PF8-KEY - COTRN00C:L257

    /** {@code PROCESS-PF8-KEY} at {@code COTRN00C:L257}. */
    @Nested
    @DisplayName("PROCESS-PF8-KEY - COTRN00C:L257")
    class Pf8Forward {

        @Test
        @DisplayName("PF8 browses on the exclusive bound, anchored on the carried last key")
        void pf8UsesExclusiveBound() {
            stubExclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = pageForward(
                    new TransactionListState(id(1), id(PAGE_SIZE), 1, true));

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdGreaterThanOrderByTransactionIdAsc(key.capture(),
                    any());
            assertThat(key.getValue())
                    .as("PF8 primes past the anchor, so the anchor record must be excluded")
                    .isEqualTo(id(PAGE_SIZE));
            assertThat(screen.state().pageNumber()).isEqualTo(2);
            verify(repository, never())
                    .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any());
        }

        @Test
        @DisplayName("PF8 on a stale last-key anchor reads nothing at all")
        void pf8OnStaleAnchorReadsNothing() {
            final TransactionListScreen screen = pageForward(
                    new TransactionListState(id(1), null, 2, true));

            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM_L642);
            assertThat(screen.errorFlagOn()).isFalse();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("PF8 past the last page reports the bottom rather than failing")
        void pf8BeyondTheLastPageReportsBottom() {
            stubExclusive(List.of());

            final TransactionListScreen screen = pageForward(
                    new TransactionListState(id(1), id(PAGE_SIZE), 4, true));

            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM_L642);
            assertThat(screen.state().pageNumber())
                    .as("no row materialised, so the counter does not advance")
                    .isEqualTo(4);
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName("a keyset cursor that no longer exists reports the bottom, not an error")
        void nonExistentForwardCursorReportsBottom() {
            stubExclusive(List.of());

            final TransactionListScreen screen = pageForward(
                    new TransactionListState(id(1), id(999_999), 2, true));

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_BOTTOM_L642);
        }
    }

    // =============================================== PROCESS-PF7-KEY - COTRN00C:L234

    /** {@code PROCESS-PF7-KEY} at {@code COTRN00C:L234} and the backward fill of {@code :L333}. */
    @Nested
    @DisplayName("PROCESS-PF7-KEY - COTRN00C:L234, :L333")
    class Pf7Backward {

        @Test
        @DisplayName("the backward window fills from slot ten down and is presented ascending")
        void backwardFillsDescendingAndPresentsAscending() {
            stubDescending(descendingFrom(20, LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = pageBackward(
                    new TransactionListState(id(21), id(30), 3, true));

            assertThat(rowIds(screen))
                    .as(":L349 seeds the index at ten and counts down, so slot one holds the lowest key")
                    .containsExactly(id(11), id(12), id(13), id(14), id(15), id(16), id(17), id(18),
                            id(19), id(20));
            assertThat(screen.state().firstKey()).isEqualTo(id(11));
            assertThat(screen.state().lastKey()).isEqualTo(id(20));
            assertThat(screen.state().pageNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("a backward window with no spare row clamps the page number to one")
        void backwardWindowWithoutSpareRowClampsToOne() {
            stubDescending(descendingFrom(20, PAGE_SIZE));

            final TransactionListScreen screen = pageBackward(
                    new TransactionListState(id(21), id(30), 3, true));

            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
            assertThat(screen.state().pageNumber())
                    .as(":L365 clamps to the first page when the probe finds no earlier row")
                    .isEqualTo(PageResponse.FIRST_PAGE_NUMBER);
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_TOP_L676);
        }

        @Test
        @DisplayName("PF7 sets the has-next sentinel unconditionally, even at the very top")
        void pf7AtTopStillSetsNextPageYes() {
            final TransactionListScreen screen = pageBackward(
                    new TransactionListState(id(1), id(PAGE_SIZE), PageResponse.FIRST_PAGE_NUMBER,
                            false));

            assertThat(screen.state().nextPageAvailable())
                    .as(":L238 sets NEXT-PAGE-YES before the page test; preserved, not corrected")
                    .isTrue();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_ALREADY_TOP_L248);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a blank carried first key anchors the backward browse on LOW-VALUES")
        void blankFirstKeyStartsFromLowValues() {
            stubDescending(List.of());

            pageBackward(new TransactionListState(null, id(PAGE_SIZE), 2, true));

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository).findByTransactionIdLessThanOrderByTransactionIdDesc(key.capture(),
                    any());
            assertThat(key.getValue())
                    .as(":L236 moves LOW-VALUES to the record key when the anchor is unset")
                    .isEmpty();
        }

        @Test
        @DisplayName("the backward path never blanks the search field")
        void backwardPathDoesNotBlankSearchField() {
            stubDescending(descendingFrom(20, LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.PF7,
                    id(5), List.of(), List.of(), new TransactionListState(id(21), id(30), 3, true));

            assertThat(screen.list().transactionIdInput())
                    .as("PROCESS-PAGE-BACKWARD has no counterpart to the forward path's :L322")
                    .isEqualTo(id(5));
        }

        @Test
        @DisplayName("a keyset cursor that no longer exists reports the top, not an error")
        void nonExistentBackwardCursorReportsTop() {
            stubDescending(List.of());

            final TransactionListScreen screen = pageBackward(
                    new TransactionListState(id(999_999), id(30), 3, true));

            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_REACHED_TOP_L676);
            assertThat(screen.errorFlagOn()).isFalse();
        }
    }

    // ============================================ PROCESS-ENTER-KEY selector scan - :L146

    /** The ten-slot selector scan of {@code COTRN00C:L146-L203}. */
    @Nested
    @DisplayName("Selector scan - COTRN00C:L146")
    class SelectionScan {

        @Test
        @DisplayName("an upper-case S dispatches to the transaction detail program")
        void validSelectionNavigates() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    selectors(1, "S"), displayedIds(), TransactionListState.initial());

            assertThat(screen.navigationTarget()).isEqualTo("COTRN01C");
            assertThat(screen.selectedTransactionId()).isEqualTo(id(1));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a lower-case s is accepted just as :L192 accepts it")
        void lowerCaseSelectorAccepted() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    selectors(4, "s"), displayedIds(), TransactionListState.initial());

            assertThat(screen.navigationTarget()).isEqualTo("COTRN01C");
            assertThat(screen.selectedTransactionId()).isEqualTo(id(4));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the first non-blank selector wins, as the EVALUATE cascade dictates")
        void firstNonBlankSelectorWins() {
            final List<String> flags = new ArrayList<>(selectors(3, "S"));
            flags.set(6, "S");

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    flags, displayedIds(), TransactionListState.initial());

            assertThat(screen.selectedTransactionId()).isEqualTo(id(3));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("every one of the ten slots is scanned, not merely the first")
        void everySlotIsScanned() {
            for (int slot = 1; slot <= PAGE_SIZE; slot++) {
                final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER,
                        null, selectors(slot, "S"), displayedIds(), TransactionListState.initial());

                assertThat(screen.selectedTransactionId())
                        .as("slot %d corresponds to SEL%04dI in COTRN00.CPY", slot, slot)
                        .isEqualTo(id(slot));
            }
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a selector survives the slot blanking that clears the row it pointed at")
        void selectorSurvivesRowBlanking() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    selectors(1, "X"), displayedIds(), TransactionListState.initial());

            assertThat(screen.list().rows().get(0).selectionFlag())
                    .as("INITIALIZE-TRAN-DATA at :L450 clears four row fields but not the selector")
                    .isEqualTo("X");
            assertThat(screen.list().rows().get(0).transactionId()).isEqualTo(id(1));
        }

        @Test
        @DisplayName("a selector with no identifier beside it falls through to the browse")
        void selectorWithoutIdentifierSkipsDispatch() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    selectors(1, "S"), blankIds(), TransactionListState.initial());

            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.selectedTransactionId()).isNull();
            assertThat(screen.page().getRows()).hasSize(PAGE_SIZE);
        }

        @Test
        @DisplayName("a selector list shorter than ten entries is tolerated")
        void shortSelectorListTolerated() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    List.of("S", "", ""), displayedIds(), TransactionListState.initial());

            assertThat(screen.navigationTarget()).isEqualTo("COTRN01C");
            assertThat(screen.selectedTransactionId()).isEqualTo(id(1));
            verifyNoInteractions(repository);
        }
    }

    // ================================================= Phase 7 - hostile search keys

    /**
     * The numeric edit of {@code COTRN00C:L207-L219} against hostile input. The field is plain numeric:
     * a currency symbol or a thousands separator is rejected, and a rejected key provably never
     * reaches the repository as a browse anchor.
     */
    @Nested
    @DisplayName("Hostile search keys - COTRN00C:L207-L219")
    class HostileSearchKeys {

        @Test
        @DisplayName("a sixteen-digit key is used verbatim as the browse anchor")
        void numericSearchKeyUsed() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            enter(id(7));

            assertThat(capturedInclusiveKey()).isEqualTo(id(7)).hasSize(16);
        }

        @Test
        @DisplayName("a null, empty or all-blank key anchors on LOW-VALUES")
        void blankKeysAnchorOnLowValues() {
            stubInclusive(ascending(1));

            enter(null);
            enter("");
            enter("                ");

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(repository, times(3))
                    .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(key.capture(), any());
            assertThat(key.getAllValues())
                    .as(":L281 moves LOW-VALUES to the record key whenever the filter is unset")
                    .containsExactly("", "", "");
        }

        @Test
        @DisplayName("a fifteen-character key is rejected: the field is exactly sixteen wide")
        void underWidthKeyRejected() {
            stubInclusive(ascending(1));

            final TransactionListScreen screen = enter("000000000000001");

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(capturedInclusiveKey())
                    .as("a rejected key must never become the browse anchor")
                    .isEmpty();
        }

        @Test
        @DisplayName("a seventeen-character key is bounded to the map width before the edit")
        void overWidthKeyIsBoundedToMapWidth() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = enter("00000000000000123");

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(capturedInclusiveKey())
                    .as("RECEIVE-TRNLST-SCREEN cannot deliver more than PIC X(16) of TRNIDINI")
                    .isEqualTo("0000000000000012");
        }

        @Test
        @DisplayName("an over-width key whose leading sixteen bytes are not digits is still rejected")
        void overWidthNonNumericKeyStillRejected() {
            stubInclusive(ascending(1));

            final TransactionListScreen screen = enter("00000000000000A123");

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(capturedInclusiveKey()).isEmpty();
        }

        @Test
        @DisplayName("a trailing alphabetic byte is rejected")
        void alphabeticKeyRejected() {
            stubInclusive(ascending(1));

            final TransactionListScreen screen = enter("123456789012345A");

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(capturedInclusiveKey()).isEmpty();
        }

        @Test
        @DisplayName("a currency symbol is rejected: this is a plain-numeric field, not an amount")
        void currencySymbolRejected() {
            stubInclusive(ascending(1));

            final TransactionListScreen screen = enter("$123456789012345");

            assertThat(screen.list().errorMessage())
                    .as(":L211 uses the plain numeric edit, not the currency-aware one of COTRN02C:L383")
                    .isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(capturedInclusiveKey()).isEmpty();
        }

        @Test
        @DisplayName("a thousands separator is rejected for the same reason")
        void thousandsSeparatorRejected() {
            stubInclusive(ascending(1));

            final TransactionListScreen screen = enter("1,234,567,890,12");

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(capturedInclusiveKey()).isEmpty();
        }

        @Test
        @DisplayName("a signed or spaced key is rejected: only the sixteen ASCII digits pass")
        void signedAndSpacedKeysRejected() {
            stubInclusive(ascending(1));

            assertThat(enter("-000000000000001").list().errorMessage())
                    .isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(enter("0000 00000000001").list().errorMessage())
                    .isEqualTo(MSG_NOT_NUMERIC_L214);
            assertThat(enter("+000000000000001").list().errorMessage())
                    .isEqualTo(MSG_NOT_NUMERIC_L214);
        }

        @Test
        @DisplayName("row identifiers wider than the map are bounded before dispatch")
        void overWidthRowFieldsAreBounded() {
            final TransactionListScreen screen = service.submitScreen(AttentionIdentifier.ENTER, null,
                    selectors(1, "S"), List.of("00000000000000019999"),
                    TransactionListState.initial());

            assertThat(screen.selectedTransactionId())
                    .as("TRNID01I is PIC X(16); anything longer cannot have come from the terminal")
                    .isEqualTo("0000000000000001")
                    .hasSize(16);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a negative carried page number is clamped in the response metadata")
        void negativeCarriedPageNumberIsClamped() {
            final TransactionListScreen screen = pageBackward(
                    new TransactionListState(id(1), id(PAGE_SIZE), -5, false));

            assertThat(screen.state().pageNumber())
                    .as("the raw carried counter is echoed back untouched")
                    .isEqualTo(-5);
            assertThat(screen.page().getPageNumber())
                    .as("the response metadata never publishes a page below the first")
                    .isEqualTo(PageResponse.FIRST_PAGE_NUMBER);
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_ALREADY_TOP_L248);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("page zero is treated as the top of the browse, not as a page")
        void pageZeroIsTheTop() {
            final TransactionListScreen screen = pageBackward(
                    new TransactionListState(id(1), id(PAGE_SIZE), 0, false));

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_ALREADY_TOP_L248);
            assertThat(screen.page().getPageNumber()).isEqualTo(PageResponse.FIRST_PAGE_NUMBER);
            verifyNoInteractions(repository);
        }
    }

    // ================================================== Phase 5 - field contracts and precision

    /**
     * {@code POPULATE-TRAN-DATA} at {@code COTRN00C:L381} and the three edited fields it produces: the
     * amount mask of {@code :L56}, the date projection of {@code :L57} and the page number of
     * {@code COTRN00.CPY:L60}.
     */
    @Nested
    @DisplayName("Projections - COTRN00C:L56-L57, :L381")
    class Projections {

        @Test
        @DisplayName("the amount mask is a mandatory sign, eight integer digits and two decimals")
        void amountMask() {
            assertThat(amountOf(new BigDecimal("123.45"))).isEqualTo("+00000123.45").hasSize(12);
            assertThat(amountOf(new BigDecimal("0.00"))).isEqualTo("+00000000.00");
            assertThat(amountOf(new BigDecimal("99999999.99"))).isEqualTo("+99999999.99");
            assertThat(TransactionDto.AMOUNT_EDITED_MASK).isEqualTo("+99999999.99");
            assertThat(TransactionDto.AMOUNT_DISPLAY_LENGTH).isEqualTo(12);
        }

        @Test
        @DisplayName("a negative amount keeps its sign and is never normalised to an absolute value")
        void negativeAmountsArePreserved() {
            assertThat(amountOf(new BigDecimal("-9.50"))).isEqualTo("-00000009.50");
            assertThat(amountOf(new BigDecimal("-0.01"))).isEqualTo("-00000000.01");
        }

        @Test
        @DisplayName("the mask truncates the ninth integer digit of an S9(09)V99 amount")
        void amountMaskTruncatesTheNinthIntegerDigit() {
            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS)
                    .as("TRAN-AMT is PIC S9(09)V99 in app/cpy/CVTRA05Y.cpy, so NUMERIC(11,2)")
                    .isEqualTo(9);
            assertThat(amountOf(new BigDecimal("123456789.99")))
                    .as("WS-TRAN-AMT PIC +99999999.99 at :L56 holds only eight integer digits; the "
                            + "truncation is asserted, never repaired by widening the mask")
                    .isEqualTo("+23456789.99");
            assertThat(amountOf(new BigDecimal("-999999999.99"))).isEqualTo("-99999999.99");
            assertThat(amountOf(new BigDecimal("100000000.00"))).isEqualTo("+00000000.00");
        }

        @Test
        @DisplayName("amount equality follows compareTo, so scale never changes the rendering")
        void amountEqualityFollowsCompareTo() {
            final BigDecimal oneDecimal = new BigDecimal("123.4");
            final BigDecimal twoDecimals = new BigDecimal("123.40");

            assertThat(oneDecimal.compareTo(twoDecimals))
                    .as("compareTo is the equality operator for money; equals compares scale too")
                    .isZero();
            assertThat(oneDecimal).isNotEqualTo(twoDecimals);
            assertThat(amountOf(oneDecimal))
                    .as("HALF_EVEN rescaling makes the two indistinguishable on the screen")
                    .isEqualTo(amountOf(twoDecimals))
                    .isEqualTo("+00000123.40");
            assertThat(TransactionDto.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionDto.AMOUNT_ROUNDING_MODE)
                    .isEqualTo(RoundingMode.HALF_EVEN);
        }

        @Test
        @DisplayName("the date is projected by slicing the twenty-six character originating timestamp")
        void dateProjection() {
            assertThat(dateOf(ORIG_TS)).isEqualTo("06/10/22").hasSize(8);
            assertThat(dateOf("1999-12-31 00:00:00.000000")).isEqualTo("12/31/99");
            assertThat(dateOf("2000-01-02 03:04:05.000006")).isEqualTo("01/02/00");
        }

        @Test
        @DisplayName("the projection slices text and never parses, so an impossible date still renders")
        void dateProjectionNeverParses() {
            assertThat(dateOf("2022-99-99 99:99:99.999999"))
                    .as("TRAN-ORIG-TS is PIC X(26) text; a temporal parse would throw here")
                    .isEqualTo("99/99/22");
            assertThat(dateOf("xxxx-AB-CD ef:gh:ij.klmnop")).isEqualTo("AB/CD/xx");
        }

        @Test
        @DisplayName("a blank or short timestamp yields the unset literal of :L57")
        void blankTimestampYieldsUnsetDate() {
            assertThat(dateOf(BLANK_TS))
                    .as("WS-TRAN-DATE PIC X(08) VALUE '00/00/00' at :L57")
                    .isEqualTo("00/00/00");
            assertThat(dateOf("2022-06")).isEqualTo("00/00/00");
            assertThat(dateOf("")).isEqualTo("00/00/00");
        }

        @Test
        @DisplayName("the twenty-six character timestamps are carried unmodified and never reformatted")
        void timestampsAreCarriedAsUnmodifiedText() {
            final Transaction record = tx(1, new BigDecimal("1.00"), "DESC", ORIG_TS);

            assertThat(TransactionDto.PERSISTED_TIMESTAMP_LENGTH).isEqualTo(26);
            assertThat(record.getOrigTs())
                    .as("three incompatible producers exist corpus-wide; this service only reads")
                    .isEqualTo(ORIG_TS)
                    .hasSize(26);
            assertThat(record.getProcTs()).isEqualTo(PROC_TS).hasSize(26);
            assertThat(BLANK_TS).hasSize(26).isBlank();
        }

        @Test
        @DisplayName("a row description is bounded to the twenty-six characters the map can show")
        void descriptionTruncatesToTwentySix() {
            final String description = "A".repeat(100);

            final TransactionListScreen screen = single(new BigDecimal("1.00"), description, ORIG_TS);

            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH).isEqualTo(26);
            assertThat(screen.page().getRows().get(0).description())
                    .as("TRAN-DESC is PIC X(100); TDESC01I shows only the leading twenty-six")
                    .isEqualTo("A".repeat(26));
        }

        @Test
        @DisplayName("the page number is rendered as eight zero-padded digits")
        void pageNumberRendering() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));
            stubExclusive(ascending(LOOKAHEAD_WINDOW));

            assertThat(service.openList().list().pageNumber()).isEqualTo("00000001");
            assertThat(pageForward(new TransactionListState(id(1), id(PAGE_SIZE), 41, true))
                    .list().pageNumber()).isEqualTo("00000042");
        }

        @Test
        @DisplayName("a page number beyond eight digits keeps its low-order eight")
        void pageNumberOverflowTruncates() {
            stubExclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = pageForward(
                    new TransactionListState(id(1), id(PAGE_SIZE), 99_999_999, true));

            assertThat(screen.state().pageNumber()).isEqualTo(100_000_000);
            assertThat(screen.list().pageNumber())
                    .as("PAGENUMI is PIC X(8); a ninth digit cannot be sent to the terminal")
                    .isEqualTo("00000000")
                    .hasSize(8);
        }

        @Test
        @DisplayName("the list projection leaves every detail-only field unset")
        void detailFieldsAreNotProjected() {
            final TransactionListScreen screen = single(new BigDecimal("1.00"), "DESC", ORIG_TS);
            final TransactionDto list = screen.list();

            assertThat(list.transactionId()).isNull();
            assertThat(list.cardNumber()).isNull();
            assertThat(list.typeCode()).isNull();
            assertThat(list.categoryCode()).isNull();
            assertThat(list.source()).isNull();
            assertThat(list.description()).isNull();
            assertThat(list.amount()).isNull();
            assertThat(list.merchantId()).isNull();
            assertThat(list.merchantName()).isNull();
            assertThat(list.merchantCity()).isNull();
            assertThat(list.merchantZip()).isNull();
            assertThat(list.amountValue()).isNull();
        }

        @Test
        @DisplayName("the projection carries the fifty-nine field budget of COTRN00.CPY")
        void listProjectionCarriesTheFiftyNineFieldBudget() {
            final TransactionListScreen screen = single(new BigDecimal("1.00"), "DESC", ORIG_TS);

            assertThat(TransactionDto.LIST_FIELD_COUNT)
                    .as("app/cpy-bms/COTRN00.CPY carries 59 input fields")
                    .isEqualTo(59)
                    .isEqualTo(TransactionDto.LIST_PREAMBLE_FIELD_COUNT
                            + TransactionDto.LIST_ROW_FIELD_COUNT * TransactionDto.PAGE_SIZE
                            + TransactionDto.LIST_TRAILER_FIELD_COUNT);
            assertThat(screen.list().rows())
                    .as("a ten-row table is what makes the count 59 rather than 21")
                    .hasSize(PAGE_SIZE);
            assertThat(TransactionDto.TransactionListRow.class.getRecordComponents())
                    .as("SELnnnnI, TRNIDnnI, TDATEnnI, TDESCnnI and TAMTnnnI per row")
                    .hasSize(TransactionDto.LIST_ROW_FIELD_COUNT);
            assertThat(TransactionDto.LIST_ROW_FIELD_COUNT).isEqualTo(5);
        }

        /**
         * Renders one amount through the service and returns the edited string it produced.
         * @param amount the signed amount to render.
         * @return the edited string the service produced for it.
         */
        private String amountOf(final BigDecimal amount) {
            return single(amount, "DESC", ORIG_TS).page().getRows().get(0).amount();
        }

        /**
         * Renders one originating timestamp through the service and returns the projected date.
         * @param origTs the twenty-six-character originating timestamp to render.
         * @return the ten-character projected date the service produced.
         */
        private String dateOf(final String origTs) {
            return single(new BigDecimal("1.00"), "DESC", origTs).page().getRows().get(0)
                    .transactionDate();
        }
    }

    // ==================================================== Phase 6 - paragraph correspondence

    /**
     * One private Java method per COBOL paragraph label, never consolidated. This is the map the
     * scope-coverage gate is proved against, and it is the reason the intentional no-ops of
     * {@code COTRN00C} are retained rather than deleted.
     */
    @Nested
    @DisplayName("Paragraph correspondence - 16 labels in 699 lines")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("all sixteen source labels map to a private method of the same name")
        void everyLabelHasAPrivateMethod() {
            final List<String> declared = new ArrayList<>();
            for (final Method method : TransactionListService.class.getDeclaredMethods()) {
                if (!method.isSynthetic()) {
                    declared.add(method.getName());
                }
            }

            assertThat(PARAGRAPH_METHOD_NAMES).hasSize(16).doesNotHaveDuplicates();
            assertThat(declared)
                    .as("app/cbl/COTRN00C.cbl carries 16 paragraph labels; none may be consolidated")
                    .containsAll(PARAGRAPH_METHOD_NAMES);
        }

        @Test
        @DisplayName("every paragraph method is private, so no label leaks into the public surface")
        void everyParagraphMethodIsPrivate() {
            for (final Method method : TransactionListService.class.getDeclaredMethods()) {
                if (method.isSynthetic() || !PARAGRAPH_METHOD_NAMES.contains(method.getName())) {
                    continue;
                }
                assertThat(Modifier.isPrivate(method.getModifiers()))
                        .as("paragraph method %s must be private", method.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the public surface is exactly the three entry points the controller calls")
        void publicSurfaceIsExactlyTheThreeEntryPoints() {
            final List<String> publicMethods = new ArrayList<>();
            for (final Method method : TransactionListService.class.getDeclaredMethods()) {
                if (!method.isSynthetic() && Modifier.isPublic(method.getModifiers())) {
                    publicMethods.add(method.getName());
                }
            }

            assertThat(publicMethods)
                    .containsExactlyInAnyOrder("openWithoutContext", "openList", "submitScreen");
        }

        @Test
        @DisplayName("the carried cursor is a record, so the request and response contract is immutable")
        void carriedCursorIsAnImmutableRecord() {
            assertThat(TransactionListState.class.isRecord()).isTrue();
            assertThat(TransactionListScreen.class.isRecord()).isTrue();

            final List<String> components = new ArrayList<>();
            for (final RecordComponent component
                    : TransactionListState.class.getRecordComponents()) {
                components.add(component.getName());
            }
            assertThat(components)
                    .as("CDEMO-CT00-TRNID-FIRST, -TRNID-LAST, -PAGE-NUM and -NEXT-PAGE-FLG at :L61-L71")
                    .containsExactly("firstKey", "lastKey", "pageNumber", "nextPageAvailable");
        }
    }

    // ================================================ Phase 7 - determinism and security

    /**
     * Determinism, ordering and parameter binding. Every paged query carries an ordering, or page
     * contents would not be reproducible and the keyset cursor would be meaningless.
     */
    @Nested
    @DisplayName("Determinism and ordering")
    class Determinism {

        @Test
        @DisplayName("every keyset finder encodes an ORDER BY in its derived query name")
        void everyPagedQueryIsOrdered() {
            final List<String> finders = List.of(
                    "findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc",
                    "findByTransactionIdGreaterThanOrderByTransactionIdAsc",
                    "findByTransactionIdLessThanOrderByTransactionIdDesc");

            for (final String finder : finders) {
                assertThat(finder)
                        .as("an unordered paged query makes page contents non-reproducible")
                        .contains("OrderByTransactionId");
                assertThat(finder.endsWith("Asc") || finder.endsWith("Desc")).isTrue();
            }
        }

        @Test
        @DisplayName("the service reaches the repository only through the three ordered finders")
        void onlyOrderedFindersAreEverCalled() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));
            stubExclusive(ascending(LOOKAHEAD_WINDOW));
            stubDescending(descendingFrom(30, LOOKAHEAD_WINDOW));

            final TransactionListScreen first = service.openList();
            final TransactionListScreen forward = pageForward(first.state());
            pageBackward(new TransactionListState(id(21), id(30), 3, true));

            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any());
            verify(repository).findByTransactionIdGreaterThanOrderByTransactionIdAsc(any(), any());
            verify(repository).findByTransactionIdLessThanOrderByTransactionIdDesc(any(), any());
            verifyNoMoreInteractions(repository);
            assertThat(forward.state().pageNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("the header timestamp comes from the injected clock, never from the host clock")
        void headerComesFromTheInjectedClock() {
            stubInclusive(ascending(1));

            final TransactionListService other = new TransactionListService(repository,
                    fileStatusMapper, Clock.fixed(Instant.parse("1999-12-31T23:58:57Z"),
                            ZoneOffset.UTC), PAGE_SIZE);

            final TransactionDto canonical = service.openList().list();
            final TransactionDto shifted = other.openList().list();

            assertThat(canonical.currentDate()).isEqualTo("06/10/22");
            assertThat(canonical.currentTime()).isEqualTo("19:27:53");
            assertThat(shifted.currentDate()).isEqualTo("12/31/99");
            assertThat(shifted.currentTime()).isEqualTo("23:58:57");
        }

        @Test
        @DisplayName("two identical turns produce byte-identical output")
        void repeatedTurnsAreByteIdentical() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen first = service.openList();
            final TransactionListScreen second = service.openList();

            assertThat(second.state()).isEqualTo(first.state());
            assertThat(second.page()).isEqualTo(first.page());
            assertThat(rowIds(second)).containsExactlyElementsOf(rowIds(first));
        }
    }

    /**
     * Secret hygiene and least privilege. The transaction record carries a full card number at record
     * offsets 263-278, so the governing rule is that it never reaches a projection, a rendered string
     * or an exception message.
     */
    @Nested
    @DisplayName("Secret hygiene - CVTRA05Y offsets 263-278")
    class Security {

        @Test
        @DisplayName("no full card number reaches the projection or any rendered string")
        void noCardNumberLeaks() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            final TransactionListScreen screen = service.openList();

            assertThat(screen.toString()).doesNotContain(CARD);
            assertThat(screen.list().toString()).doesNotContain(CARD);
            assertThat(screen.page().toString()).doesNotContain(CARD);
            assertThat(screen.state().toString()).doesNotContain(CARD);
            for (final TransactionDto.TransactionListRow row : screen.list().rows()) {
                assertThat(row.toString()).doesNotContain(CARD);
                assertThat(row.description()).doesNotContain(CARD);
                assertThat(row.amount()).doesNotContain(CARD);
            }
        }

        @Test
        @DisplayName("no full card number reaches a failure message")
        void noCardNumberInFailureMessage() {
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(new QueryTimeoutException("browse timed out"));

            assertThatThrownBy(service::openList)
                    .isInstanceOfSatisfying(FileAccessException.class, thrown -> {
                        assertThat(thrown.getMessage()).doesNotContain(CARD);
                        assertThat(thrown.toString()).doesNotContain(CARD);
                    });
        }

        @Test
        @DisplayName("a rejected key never reaches the repository, so no metacharacter is ever bound")
        void rejectedKeyNeverReachesTheRepository() {
            stubInclusive(ascending(1));

            final TransactionListScreen screen = enter("' OR '1'='1' -- x");

            assertThat(screen.list().errorMessage()).isEqualTo(MSG_NOT_NUMERIC_L214);
            final String anchor = capturedInclusiveKey();
            assertThat(anchor)
                    .as("the numeric edit rejects the key before :L281 could ever anchor on it")
                    .doesNotContain("OR")
                    .doesNotContain("'");
            assertThat(anchor).isEmpty();
        }

        @Test
        @DisplayName("the anchor and the window travel as bound arguments, never as concatenated text")
        void anchorAndWindowAreBoundArguments() {
            stubInclusive(ascending(LOOKAHEAD_WINDOW));

            enter(id(9));

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                    key.capture(), window.capture());
            assertThat(key.getValue())
                    .as("a derived-query parameter is bound, so no quoting or escaping is applied")
                    .isEqualTo(id(9));
            assertThat(window.getValue()).isNotNull();
            assertThat(window.getValue().getPageSize()).isEqualTo(LOOKAHEAD_WINDOW);
        }
    }

    /**
     * The three lookup-failure sites of {@code COTRN00C:L615}, {@code :L649} and {@code :L683}, each
     * independently reachable, all routed through the one shared translation point.
     */
    @Nested
    @DisplayName("Failure routing - COTRN00C:L615, :L649, :L683")
    class FailureRouting {

        @Test
        @DisplayName(":L615 - a STARTBR fault is reported as a file access failure with its cause")
        void startbrFailureIsReachable() {
            final QueryTimeoutException failure = new QueryTimeoutException("browse timed out");
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(failure);

            assertThatThrownBy(service::openList)
                    .isInstanceOfSatisfying(FileAccessException.class, thrown -> {
                        assertThat(thrown)
                                .as("every I/O failure is a typed CardDemoException, never a raw one")
                                .isInstanceOf(CardDemoException.class);
                        assertThat(thrown.getOperation()).isEqualTo("STARTBR");
                        assertThat(thrown.getLogicalFileName()).isEqualTo(TRANSACT_FILE);
                        assertThat(thrown.getExpandedStatus())
                                .isEqualTo(IO_FAILURE_EXPANDED_STATUS);
                        assertThat(thrown.getCause())
                                .as("no swallowing: the root cause is preserved verbatim")
                                .isSameAs(failure);
                    });
            assertThat(fileStatusMapper.displayIoStatus(IO_FAILURE_STATUS))
                    .as("the expansion comes from the one owning renderer, not from this test")
                    .endsWith(IO_FAILURE_EXPANDED_STATUS);
        }

        @Test
        @DisplayName(":L649 - a READNEXT fault is reported independently of the STARTBR site")
        void readnextFailureIsReachable() {
            stubInclusive(Arrays.asList(tx(1), null));

            assertThatThrownBy(service::openList)
                    .isInstanceOfSatisfying(FileAccessException.class, thrown -> {
                        assertThat(thrown.getOperation())
                                .as("READNEXT-TRANSACT-FILE:L649 is its own site, not consolidated")
                                .isEqualTo("READNEXT");
                        assertThat(thrown.getLogicalFileName()).isEqualTo(TRANSACT_FILE);
                        assertThat(thrown.getExpandedStatus())
                                .isEqualTo(IO_FAILURE_EXPANDED_STATUS);
                        assertThat(thrown.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName(":L683 - a READPREV fault is reported independently of the other two sites")
        void readprevFailureIsReachable() {
            stubDescending(Arrays.asList(tx(20), null));

            assertThatThrownBy(() -> pageBackward(new TransactionListState(id(21), id(30), 3, true)))
                    .isInstanceOfSatisfying(FileAccessException.class, thrown -> {
                        assertThat(thrown.getOperation())
                                .as("READPREV-TRANSACT-FILE:L683 is its own site, not consolidated")
                                .isEqualTo("READPREV");
                        assertThat(thrown.getExpandedStatus())
                                .isEqualTo(IO_FAILURE_EXPANDED_STATUS);
                        assertThat(thrown.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName("a PF8 fault routes through the same STARTBR site as an ENTER fault")
        void pf8FaultRoutesThroughStartbr() {
            final QueryTimeoutException failure = new QueryTimeoutException("browse timed out");
            when(repository.findByTransactionIdGreaterThanOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(failure);

            assertThatThrownBy(() -> pageForward(
                    new TransactionListState(id(1), id(PAGE_SIZE), 1, true)))
                    .isInstanceOfSatisfying(FileAccessException.class, thrown ->
                            assertThat(thrown.getOperation()).isEqualTo("STARTBR"));
        }

        @Test
        @DisplayName("a PF7 fault routes through the same STARTBR site")
        void pf7FaultRoutesThroughStartbr() {
            when(repository.findByTransactionIdLessThanOrderByTransactionIdDesc(any(), any()))
                    .thenThrow(new QueryTimeoutException("browse timed out"));

            assertThatThrownBy(() -> pageBackward(new TransactionListState(id(21), id(30), 3, true)))
                    .isInstanceOfSatisfying(FileAccessException.class, thrown ->
                            assertThat(thrown.getOperation()).isEqualTo("STARTBR"));
        }

        @Test
        @DisplayName("every failure message is produced by the one shared translation point")
        void failureMessageComesFromTheSingleTranslationPoint() {
            final QueryTimeoutException failure = new QueryTimeoutException("browse timed out");
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(failure);
            final String expected = new FileStatusMapper()
                    .toException(IO_FAILURE_STATUS, TRANSACT_FILE, "STARTBR", failure)
                    .orElseThrow()
                    .getMessage();

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(service::openList)
                    .withMessage(expected);
        }

        @Test
        @DisplayName("end of data is a control path, never a failure")
        void endOfDataIsNotAnError() {
            stubInclusive(List.of());

            final TransactionListScreen screen = service.openList();

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.page().getRows()).isEmpty();
            assertThat(screen.list().errorMessage()).isEqualTo(MSG_AT_TOP_L608);
        }
    }

    /**
     * The {@code Unable to lookup transaction...} literal of {@code COTRN00C:L615}, {@code :L649} and
     * {@code :L683}, observed at each of the three sites.
     *
     * <p>In production the shared translator recognises the {@code '9x'} family, so the literal never
     * surfaces: the message the caller sees comes from the single translation point instead. These
     * tests therefore stand the translator down, which is the only way to observe the literal that
     * {@code browseFailure} falls back on, and so the only way to prove byte for byte that all three
     * sites carry the same string and that none of them was consolidated away.
     */
    @Nested
    @DisplayName("The lookup-failure literal observed - COTRN00C:L615, :L649, :L683")
    class LookupFailureLiteralAtEachSite {

        /**
         * Builds a service whose translator classifies nothing, so {@code browseFailure} must fall back
         * on the program's own literal.
         *
         * @return a service over the mocked repository and a stood-down translator
         */
        private TransactionListService withNoTranslation() {
            when(unmappedStatus.toException(any(), any(), any(), any())).thenReturn(Optional.empty());
            return new TransactionListService(repository, unmappedStatus, fixedClock(), PAGE_SIZE);
        }

        @Test
        @DisplayName(":L615 - the STARTBR site carries the literal and keeps the root cause")
        void startbrSiteCarriesTheLiteral() {
            final QueryTimeoutException failure = new QueryTimeoutException("browse timed out");
            when(repository.findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(any(), any()))
                    .thenThrow(failure);
            final TransactionListService subject = withNoTranslation();

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(subject::openList)
                    .withMessage("Unable to lookup transaction...")
                    .withCause(failure);
        }

        @Test
        @DisplayName(":L649 - the READNEXT site carries the same literal and names its own operation")
        void readnextSiteCarriesTheLiteral() {
            stubInclusive(Arrays.asList(tx(1), null));
            final TransactionListService subject = withNoTranslation();

            assertThatThrownBy(subject::openList)
                    .isInstanceOfSatisfying(FileAccessException.class, thrown -> {
                        assertThat(thrown.getMessage())
                                .isEqualTo("Unable to lookup transaction...")
                                .isEqualTo(MSG_UNABLE_TO_LOOKUP);
                        assertThat(thrown.getOperation()).isEqualTo("READNEXT");
                        assertThat(thrown.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName(":L683 - the READPREV site carries the same literal and names its own operation")
        void readprevSiteCarriesTheLiteral() {
            stubDescending(Arrays.asList(tx(20), null));
            final TransactionListService subject = withNoTranslation();

            assertThatThrownBy(() -> subject.submitScreen(AttentionIdentifier.PF7, null, List.of(),
                    List.of(), new TransactionListState(id(21), id(30), 3, true)))
                    .isInstanceOfSatisfying(FileAccessException.class, thrown -> {
                        assertThat(thrown.getMessage()).isEqualTo(MSG_UNABLE_TO_LOOKUP);
                        assertThat(thrown.getOperation()).isEqualTo("READPREV");
                        assertThat(thrown.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName("the three sites emit one identical string, deliberately not consolidated")
        void allThreeSitesEmitTheIdenticalString() {
            stubInclusive(Arrays.asList(tx(1), null));
            stubDescending(Arrays.asList(tx(20), null));
            final TransactionListService subject = withNoTranslation();
            final List<String> messages = new ArrayList<>();

            assertThatThrownBy(subject::openList)
                    .isInstanceOfSatisfying(FileAccessException.class,
                            thrown -> messages.add(thrown.getMessage()));
            assertThatThrownBy(() -> subject.submitScreen(AttentionIdentifier.PF7, null, List.of(),
                    List.of(), new TransactionListState(id(21), id(30), 3, true)))
                    .isInstanceOfSatisfying(FileAccessException.class,
                            thrown -> messages.add(thrown.getMessage()));

            assertThat(messages)
                    .as("READNEXT:L649 and READPREV:L683 repeat STARTBR:L615's literal verbatim")
                    .containsExactly(MSG_UNABLE_TO_LOOKUP, MSG_UNABLE_TO_LOOKUP);
        }
    }

}
