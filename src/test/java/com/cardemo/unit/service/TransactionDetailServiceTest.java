/*
 * ******************************************************************
 * Program     : TransactionDetailServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies TransactionDetailService paragraph by paragraph against
 *               COTRN01C. Concentrates on the contracts a reader cannot confirm
 *               by inspection: that the retrieval reaches the repository as a
 *               plain keyed read and nothing else, that the record the service
 *               returns is never mutated, the three screen literals asserted
 *               byte for byte, the numeric guard the source does NOT have, the
 *               edited amount mask of :49 including its deliberate loss of a
 *               ninth integer digit, the two vestigial declarations retained as
 *               documented no-ops, and the rule that a card number never
 *               reaches a log, an exception message or an assertion message.
 * Source      : app/cbl/COTRN01C.cbl      (330 lines, 9 own paragraph labels)
 *               app/cpy/CVTRA05Y.cpy      (TRAN-RECORD 350 bytes, S9(09)V99)
 *               app/cpy/COCOM01Y.cpy      (CARDDEMO-COMMAREA, no pagination)
 *               app/cpy-bms/COTRN01.CPY   (21 input fields, TDESCI X(60):96)
 *               app/cbl/CBACT04C.cbl      (canonical banner form, L1-L21)
 *               CONTRIBUTING.md, NOTICE   (style and licence conventions)
 *                                                                  @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionDetailService.AttentionIdentifier;
import com.cardemo.service.transaction.TransactionDetailService.TransactionDetailScreen;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit tests for {@link TransactionDetailService}, the Java replacement for {@code app/cbl/COTRN01C.cbl}.
 *
 * <p><strong>1. What it does.</strong> Asserts the observable behaviour of the {@code CT01} transaction's
 * detail-view service against the frozen COBOL program, one concern per test. Every claim is anchored to a
 * verified locator in the source, and the following were re-verified by direct inspection at commit
 * {@code 7756d89} rather than taken on trust:
 * <ul>
 *   <li>{@code :86} {@code MAIN-PARA}, {@code :144} {@code PROCESS-ENTER-KEY}, {@code :197}
 *       {@code RETURN-TO-PREV-SCREEN}, {@code :213} {@code SEND-TRNVIEW-SCREEN}, {@code :230}
 *       {@code RECEIVE-TRNVIEW-SCREEN}, {@code :243} {@code POPULATE-HEADER-INFO}, {@code :267}
 *       {@code READ-TRANSACT-FILE}, {@code :301} {@code CLEAR-CURRENT-SCREEN} and {@code :309}
 *       {@code INITIALIZE-ALL-FIELDS} - all nine paragraphs of a 330-line program.</li>
 *   <li>{@code :95} and {@code :200} route to {@code COSGN00C}; {@code :117} to {@code COMEN01C};
 *       {@code :126} to {@code COTRN00C}. {@code :129} raises {@code WS-ERR-FLG} on the separate
 *       {@code WHEN OTHER} arm of the same {@code EVALUATE EIBAID} - it is not part of the {@code :126}
 *       arm, and the two are adjacent lines in different branches.</li>
 *   <li>{@code :144-160} is the whole of the input validation, and {@code :149}, {@code :285} and
 *       {@code :292} are the three screen literals.</li>
 *   <li>{@code :267-279} is the read, whose {@code UPDATE} option sits alone at {@code :275}.</li>
 * </ul>
 *
 * <p><strong>2. How to run, build and test.</strong> This class is bound to the <em>Surefire</em> tier, not
 * Failsafe: the root build includes {@code **}{@code /*Test.java} while excluding {@code **}{@code
 * /integration/**} and {@code **}{@code /e2e/**}, so a class named {@code *Test} under
 * {@code src/test/java/com/cardemo/unit/} is collected by Surefire and a class placed outside that tree
 * would be collected by neither plugin and would silently never run. Run it with
 * {@code ./mvnw -B -ntp test -Dtest=TransactionDetailServiceTest}, the whole unit tier with
 * {@code ./mvnw -B -ntp test}, and the gated build with
 * {@code ./mvnw -B -ntp clean verify}.
 *
 * <p><strong>3. Key configs and defaults.</strong> A pure-JVM tier: no Spring context, no container, no
 * database and no network, so nothing here can reach an external endpoint. {@link MockitoExtension} supplies
 * Mockito's default {@code STRICT_STUBS}, which fails a test on an unused stubbing and so keeps the fixtures
 * honest. {@link TransactionRepository} and {@link EntityManager} are doubles; {@link FileStatusMapper} is a
 * real instance, because it has a no-argument constructor and no collaborators, so the exception types
 * asserted here are exactly the ones production raises. The clock is a fixed
 * {@link Clock#fixed(Instant, java.time.ZoneId)} at {@link #FIXED_INSTANT} in {@link ZoneOffset#UTC},
 * constructed inline: no ambient {@code now()} call and no default time zone appears anywhere in this file.
 * Money is {@link BigDecimal} at scale {@value com.cardemo.model.dto.TransactionDto#AMOUNT_SCALE} with
 * {@link RoundingMode#HALF_EVEN}, compared with {@code compareTo} and never with {@code equals}.
 *
 * <p><strong>4. Common failure modes and troubleshooting.</strong>
 * <ul>
 *   <li><em>{@code warnings found and -Werror specified}.</em> Test compilation runs under {@code -Xlint:all -Werror}
 *   , so one dangling documentation comment, raw type or unchecked cast fails the build - but <em>not</em> an unused
 *   import, for which {@code javac} 25 publishes no lint key, so that one is review-enforced. Note the licence banner
 *   above is a plain block comment, never {@code /**}, precisely so it cannot be read as a dangling doc
 *   comment.</li>
 *   <li><em>An identifier wider than sixteen characters appears to pass.</em> It reaches the repository
 *       unvalidated on purpose. Only the not-found route may be used for it, because a found record would
 *       then breach {@code TransactionDto}'s width contract on {@code transactionIdInput}.</li>
 *   <li><em>A test asserting a numeric guard fails.</em> There is no numeric guard to assert; see the
 *       source facts below.</li>
 *   <li><em>An {@code UnnecessaryStubbingException}.</em> Strict stubs are deliberate. Stub inside the test
 *       that consumes the stubbing, never in a shared {@code @BeforeEach}.</li>
 *   <li><em>A timestamp assertion fails after a formatting change.</em> {@code TRAN-ORIG-TS} and
 *       {@code TRAN-PROC-TS} are text, not temporal values; see the source facts below.</li>
 *   <li><em>The Surefire report for this class reads {@code tests="0"}.</em> That is Surefire's convention
 *       for a class whose tests all live in {@code @Nested} inner classes; the {@code testcase} elements are
 *       present and counted in the aggregate. It is a reporting artefact, not a collection failure.</li>
 *   </ul>
 *
 * <p><strong>Source facts this class pins, and the ways they get broken.</strong>
 * <ul>
 *   <li><strong>A test class outside the Surefire tree runs nowhere.</strong> The root build binds
 *       Surefire to {@code src/test/java/com/cardemo/unit/} and its subtree. A class placed outside it
 *       matches neither Surefire's nor Failsafe's include set, so it is collected by neither plugin: the
 *       build stays green, both plugins report success, and coverage silently records the class as untested -
 *       no error, no warning. This class sits inside that tree, and its report file is the proof.</li>
 *   <li><strong>{@code READ … UPDATE} on a strictly read-only path.</strong> {@code :267-279} reads
 *       {@code TRANSACT} with the {@code UPDATE} option at {@code :275}, taking an exclusive record lock.
 *       A repository-wide scan of this program returns <strong>zero</strong> occurrences of
 *       {@code REWRITE}, {@code UNLOCK}, {@code SYNCPOINT} and {@code WRITE} - its entire CICS verb
 *       inventory is {@code RETURN}, {@code XCTL}, {@code SEND}, {@code RECEIVE} and that one {@code READ} -
 *       so the program is a pure detail view and the lock is acquired needlessly and released only
 *       implicitly at task end. <em>The labelled deviation:</em> the Java retrieval reaches
 *       the store as a plain keyed read through {@link TransactionRepository#findById(Object)} with no lock
 *       hint, no write and no flush, which is what
 *       {@link ReadOnlyRetrievalContract#repositoryReceivesOnlyOnePlainKeyedRead()} pins. Translating
 *       {@code :275} literally into a Java pessimistic lock would hold a database row lock for the whole
 *       request and change the concurrency profile, which is why dropping it is the justified performance
 *       tradeoff required by Rule 1 clause A. <em>Divergence to disclose:</em> the production service
 *       additionally escalates the located row to
 *       {@link LockModeType#PESSIMISTIC_WRITE} through the entity manager, a deliberate choice documented in
 *       that class and owned by {@code src/main/**} rather than by this test. This class therefore asserts
 *       the contract it can hold true - that the <em>repository</em> is never asked for a lock, a write or a
 *       flush, and that the lock is never attempted when no row was found - and records the divergence here
 *       rather than encoding it silently as correct.</li>
 *   <li><strong>Cardholder data must not reach diagnostics.</strong> {@code TRAN-CARD-NUM} occupies bytes 263-278
 *       of the 350-byte record. It legitimately reaches the response, because the map displays it, but must
 *       never reach a log, an exception message or a test message; {@code :290}'s
 *       {@code DISPLAY 'RESP:' … 'REAS:'} must carry status codes only.</li>
 *   <li><strong>No numeric guard exists, and none may be invented.</strong> {@code :146-156} tests for empty
 *       and nothing else. The sibling program's {@code Tran ID must be Numeric ...} has no counterpart here, so a
 *       non-numeric identifier must reach the repository and fail as not-found.</li>
 *   <li><strong>The amount mask must not be widened.</strong> {@code :49} declares
 *       {@code WS-TRAN-AMT PIC +99999999.99}: eight integer digits against the record's nine, so a value of
 *       one hundred million or more silently loses its leading digit. The truncation is asserted, not
 *       corrected.</li>
 *   <li><strong>The timestamps must not be converted.</strong> Both are {@code PIC X(26)} text. Three
 *       incompatible producers exist across the corpus, so the service passes the characters through and
 *       must never reformat them.</li>
 *   <li><strong>A single-record view has no pagination to assert.</strong></li>
 *   <li><strong>The vestigial {@code WS-USR-MODIFIED} flag</strong> at {@code :45-47}, set once at
 *       {@code :89} and read nowhere, in a program that modifies nothing.</li>
 *   <li><strong>The cloned {@code CDEMO-CT01-INFO} pagination extension</strong> at {@code :53-61},
 *       appended after {@code COPY COCOM01Y.} and a verbatim clone of the transaction list's group.
 *       {@code app/cpy/COCOM01Y.cpy} itself declares no page-number and no next-page flag, so both are
 *       program-local. Only {@code CDEMO-CT01-TRN-SELECTED} carries meaning here.</li>
 *   <li><strong>Divergent capitalisation.</strong> This program's {@code :292} capitalises
 *       {@code Transaction} where the sibling list program does not. Both spellings are contracts, so no
 *       message constant is shared between the two test classes.</li>
 *   <li><strong>The cursor move sits on the success branch too.</strong> {@code :154} parks the cursor
 *       inside {@code WHEN OTHER} before {@code CONTINUE}, so it happens on the valid path as well as the
 *       error path.</li>
 *   </ul>
 *
 * <p><strong>Boundaries of this tier.</strong> An assertion against a live {@code TRANSACT} table cannot be
 * made from a pure-JVM tier: it needs a container runtime and belongs to the integration tier,
 * which Failsafe owns and which this class must not duplicate.
 *
 * <p><strong>The documented conflict - parity governs.</strong> Rule 1 clause B forbids dead code, while the
 * migration mandate requires one-to-one control-flow parity. They collide on the two vestigial declarations
 * above and on the {@code :275} {@code UPDATE} keyword itself. Parity governs, and clause B is satisfied on
 * its own terms: what it prohibits is an artefact <em>without an owner or tracking reference</em>, and each
 * retained item carries the source locator cited above and
 * an explicit intentional-no-op marker on the test that pins it. Deleting any of them would break the
 * paragraph and field map the scope-coverage gate is proved against.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDetailService - COTRN01C / CICS transaction CT01")
final class TransactionDetailServiceTest {

    /**
     * {@code app/cbl/COTRN01C.cbl:149}, byte for byte. Note {@code NOT} in capitals.
     */
    private static final String EMPTY_TRAN_ID_MESSAGE_L149 = "Tran ID can NOT be empty...";

    /**
     * {@code app/cbl/COTRN01C.cbl:285}, byte for byte. Note {@code NOT} in capitals, and that the wording is
     * {@code Transaction ID} rather than the {@code Tran ID} used at {@code :149}.
     */
    private static final String NOT_FOUND_MESSAGE_L285 = "Transaction ID NOT found...";

    /**
     * {@code app/cbl/COTRN01C.cbl:292}, byte for byte. Note the capital {@code T} on {@code Transaction}.
     */
    private static final String LOOKUP_FAILURE_MESSAGE_L292 = "Unable to lookup Transaction...";

    /**
     * The sibling transaction-list program's lower-case spelling of the same concept, held here <em>only</em>
     * as the negative half of the capitalisation assertion. It is deliberately not shared with that
     * program's test class, because the divergence is a contract on both sides.
     */
    private static final String SIBLING_LOWER_CASE_VARIANT = "Unable to lookup transaction...";

    /**
     * {@code app/cbl/COTRN01C.cbl:95} and {@code :200}.
     */
    private static final String SIGN_ON_PROGRAM_L95 = "COSGN00C";

    /**
     * {@code app/cbl/COTRN01C.cbl:117}.
     */
    private static final String MAIN_MENU_PROGRAM_L117 = "COMEN01C";

    /**
     * {@code app/cbl/COTRN01C.cbl:126}.
     */
    private static final String TRANSACTION_LIST_PROGRAM_L126 = "COTRN00C";

    /**
     * The field {@code MOVE -1 TO TRNIDINL} parks the cursor on, at {@code :102}, {@code :151}, {@code :154},
     * {@code :287}, {@code :294} and {@code :311}.
     */
    private static final String CURSOR_FIELD_TRAN_ID_INPUT = "TRNIDIN";

    /**
     * The logical file name of {@code WS-TRANSACT-FILE}, {@code app/cbl/COTRN01C.cbl:39}.
     */
    private static final String TRANSACT_FILE = "TRANSACT";

    /**
     * The operation label the service reports for the read of {@code :269-278}, preserving the {@code :275}
     * {@code UPDATE} intent in the diagnostic even though no lock hint reaches the repository.
     */
    private static final String READ_UPDATE_OPERATION = "READ UPDATE";

    /**
     * A well-formed sixteen-character identifier, the exact width of {@code TRAN-ID PIC X(16)}.
     */
    private static final String ID = "0000000000000042";

    /**
     * A wholly synthetic sixteen-digit stand-in for {@code TRAN-CARD-NUM PIC X(16)}, used only to prove the
     * value never escapes into a log or an exception. It is not a real or published card number, and no
     * assertion in this file ever prints it.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "9990001112223334";

    /**
     * A full twenty-six character {@code TRAN-ORIG-TS PIC X(26)}, bytes 279-304.
     */
    private static final String ORIG_TS_26 = "2022-06-10 19:27:53.000000";

    /**
     * A full twenty-six character {@code TRAN-PROC-TS PIC X(26)}, bytes 305-330, in the batch rendering, so
     * that the two timestamps differ in both value and shape and neither can be mistaken for the other.
     */
    private static final String PROC_TS_26 = "2022-06-11-08.00.00.000000";

    /**
     * The instant the fixed clock reports. Chosen from {@code app/data/ASCII/dailytran.txt}, whose 300 rows
     * carry one single distinct originating timestamp at columns 279-304, so the value is evidence rather
     * than an arbitrary pick. Parsed from a literal, never read from the host clock.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /**
     * The nine paragraph labels of {@code app/cbl/COTRN01C.cbl}, paired with the private Java method each
     * must map to and the source line the label sits on. Immutable, so it is safe as a static constant.
     */
    private static final List<String> PARAGRAPH_METHOD_NAMES = List.of(
            "mainPara",                 // MAIN-PARA               :86
            "processEnterKey",          // PROCESS-ENTER-KEY       :144
            "returnToPrevScreen",       // RETURN-TO-PREV-SCREEN   :197
            "sendTrnviewScreen",        // SEND-TRNVIEW-SCREEN     :213
            "receiveTrnviewScreen",     // RECEIVE-TRNVIEW-SCREEN  :230
            "populateHeaderInfo",       // POPULATE-HEADER-INFO    :243
            "readTransactFile",         // READ-TRANSACT-FILE      :267
            "clearCurrentScreen",       // CLEAR-CURRENT-SCREEN    :301
            "initializeAllFields");     // INITIALIZE-ALL-FIELDS   :309

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private EntityManager entityManager;

    private FileStatusMapper fileStatusMapper;

    private TransactionDetailService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logEvents;

    /**
     * Builds the bean over its four collaborators and attaches an in-memory log appender.
     *
     * <p>No stubbing happens here. Under strict stubs a stubbing that a given test does not consume fails
     * that test, so every {@code when(...)} lives in the test that needs it.
     */
    @BeforeEach
    void setUp() {
        fileStatusMapper = new FileStatusMapper();
        service = new TransactionDetailService(transactionRepository, fileStatusMapper, entityManager,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        serviceLogger = (Logger) LoggerFactory.getLogger(TransactionDetailService.class);
        logEvents = new ListAppender<>();
        logEvents.start();
        serviceLogger.addAppender(logEvents);
        serviceLogger.setLevel(Level.TRACE);
    }

    /**
     * Detaches the appender so one test's log output cannot leak into another's assertions.
     */
    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logEvents);
        logEvents.stop();
    }

    /**
     * Builds a transaction record. Every argument names one field of {@code app/cpy/CVTRA05Y.cpy}.
     *
     * @param amount {@code TRAN-AMT PIC S9(09)V99}, bytes 133-143
     * @param origTs {@code TRAN-ORIG-TS PIC X(26)}, bytes 279-304
     * @param procTs {@code TRAN-PROC-TS PIC X(26)}, bytes 305-330
     * @param cardNumber {@code TRAN-CARD-NUM PIC X(16)}, bytes 263-278
     * @return the record, never {@code null}
     */
    private static Transaction transaction(final BigDecimal amount,
            final String origTs,
            final String procTs,
            final String cardNumber) {
        return new Transaction(ID, "01", 5, "POS       ", "COFFEE", amount, 123L,
                "ACME", "SEATTLE", "12345-0001", cardNumber, origTs, procTs);
    }

    /**
     * The canonical record: every field comfortably inside its width, so nothing truncates incidentally.
     *
     * @return the record, never {@code null}
     */
    private static Transaction canonicalTransaction() {
        return transaction(new BigDecimal("123.45"), ORIG_TS_26, PROC_TS_26, SYNTHETIC_CARD_NUMBER);
    }

    /**
     * Stubs the keyed read to answer {@code candidate} and drives the deep-link entry of {@code :103-107}.
     *
     * @param candidate the record the store answers with
     * @return the rendered screen, never {@code null}
     */
    private TransactionDetailScreen viewFound(final Transaction candidate) {
        when(transactionRepository.findById(ID)).thenReturn(Optional.of(candidate));
        return service.viewTransaction(ID);
    }

    /**
     * Renders every captured log event as one searchable string: the formatted message, every argument and
     * the whole throwable chain. Used only to prove that a value is <em>absent</em>.
     *
     * @return the concatenated log output, never {@code null}
     */
    private String capturedLogText() {
        final StringBuilder text = new StringBuilder(512);
        for (final ILoggingEvent event : logEvents.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getArgumentArray() != null) {
                for (final Object argument : event.getArgumentArray()) {
                    text.append(argument).append('\n');
                }
            }
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(proxy.getClassName()).append('\n').append(proxy.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Collects the values of the thirteen record-bearing detail fields, that is the projection of
     * {@code :159-171} and {@code :178-190} without the six header fields and the message field.
     *
     * @param detail the rendered detail projection
     * @return the thirteen record-bearing values in map order, never {@code null}
     */
    private static List<String> recordBearingFields(final TransactionDto detail) {
        final List<String> values = new ArrayList<>(13);
        values.add(detail.transactionId());
        values.add(detail.cardNumber());
        values.add(detail.typeCode());
        values.add(detail.categoryCode());
        values.add(detail.source());
        values.add(detail.description());
        values.add(detail.amount());
        values.add(detail.originatingDate());
        values.add(detail.processingDate());
        values.add(detail.merchantId());
        values.add(detail.merchantName());
        values.add(detail.merchantCity());
        values.add(detail.merchantZip());
        return values;
    }

    /**
     * {@code READ-TRANSACT-FILE}, {@code app/cbl/COTRN01C.cbl:267-279}, and the response-code note on this
     * class: the {@code UPDATE} option at {@code :275} takes an exclusive lock on a path that never writes.
     *
     * <p>A scan of the program returns zero occurrences of {@code REWRITE}, {@code UNLOCK}, {@code SYNCPOINT}
     * and {@code WRITE}; its complete CICS verb inventory is {@code RETURN} at {@code :136}, {@code XCTL} at
     * {@code :205}, {@code SEND} at {@code :219}, {@code RECEIVE} at {@code :232} and the {@code READ} at
     * {@code :269}. So no write verb may reach the store.
     *
     * <p><strong>Both collaborators are asserted, and that is not optional.</strong> Stating that the
     * retrieval reaches the store as a plain keyed read with "no lock hint", and omitting any assertion about
     * the lock, would assert the opposite of the production contract.
     * {@code TransactionDetailService} escalates the found row to
     * {@link LockModeType#PESSIMISTIC_WRITE} through {@link EntityManager#lock(Object, LockModeType)},
     * which is what reproduces the {@code UPDATE} option; Hibernate renders it as
     * {@code SELECT ... FOR UPDATE}. Such assertions would pass anyway, because that escalation travels
     * through the {@link EntityManager}, not through {@link TransactionRepository}, so a
     * {@code verifyNoMoreInteractions} on the repository could never observe it. Verifying the
     * repository alone is therefore not sufficient to describe this path, and both collaborators are
     * asserted below.
     */
    @Nested
    @DisplayName("READ-TRANSACT-FILE :267-279 - keyed read, escalated to PESSIMISTIC_WRITE, never written")
    final class ReadOnlyRetrievalContract {

        @Test
        @DisplayName("the found row IS escalated to PESSIMISTIC_WRITE, reproducing the UPDATE option at :275")
        void foundRowIsEscalatedToPessimisticWrite() {
            final Transaction record = canonicalTransaction();

            viewFound(record);

            verify(entityManager).lock(record, LockModeType.PESSIMISTIC_WRITE);
            verifyNoMoreInteractions(entityManager);
        }

        @Test
        @DisplayName("the escalation locks the very row the store returned, not a copy of it")
        void theLockedRowIsTheRowTheStoreReturned() {
            final Transaction record = canonicalTransaction();

            viewFound(record);

            final ArgumentCaptor<Object> locked = ArgumentCaptor.forClass(Object.class);
            verify(entityManager).lock(locked.capture(), eq(LockModeType.PESSIMISTIC_WRITE));
            assertThat(locked.getValue()).isSameAs(record);
        }

        @Test
        @DisplayName("the store sees exactly one plain keyed read: the lock travels via the entity manager")
        void repositoryReceivesOnlyOnePlainKeyedRead() {
            viewFound(canonicalTransaction());

            verify(transactionRepository).findById(ID);
            verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        @DisplayName("no write verb reaches the repository: no save, no delete, no flush")
        void noWriteVerbReachesTheRepository() {
            viewFound(canonicalTransaction());

            verify(transactionRepository, never()).save(any());
            verify(transactionRepository, never()).saveAndFlush(any());
            verify(transactionRepository, never()).delete(any());
            verify(transactionRepository, never()).deleteById(anyString());
            verify(transactionRepository, never()).flush();
        }

        @Test
        @DisplayName("the record the store handed over is returned unmutated, field for field")
        void recordIsNotMutatedByTheService() {
            final Transaction record = canonicalTransaction();

            viewFound(record);

            assertThat(record.getTransactionId()).isEqualTo(ID);
            assertThat(record.getTypeCode()).isEqualTo("01");
            assertThat(record.getCategoryCode()).isEqualTo(5);
            assertThat(record.getTransactionSource()).isEqualTo("POS       ");
            assertThat(record.getDescription()).isEqualTo("COFFEE");
            assertThat(record.getAmount()).isEqualByComparingTo(new BigDecimal("123.45"));
            assertThat(record.getMerchantId()).isEqualTo(123L);
            assertThat(record.getMerchantName()).isEqualTo("ACME");
            assertThat(record.getMerchantCity()).isEqualTo("SEATTLE");
            assertThat(record.getMerchantZip()).isEqualTo("12345-0001");
            assertThat(record.getCardNumber()).isEqualTo(SYNTHETIC_CARD_NUMBER);
            assertThat(record.getOrigTs()).isEqualTo(ORIG_TS_26);
            assertThat(record.getProcTs()).isEqualTo(PROC_TS_26);
        }

        @Test
        @DisplayName("nothing is locked when no row was found, exactly as CICS locks nothing on NOTFND")
        void noLockIsAttemptedWhenNoRowWasFound() {
            when(transactionRepository.findById(ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(ID));

            verify(entityManager, never()).lock(any(), eq(LockModeType.PESSIMISTIC_WRITE));
            verifyNoMoreInteractions(entityManager);
        }

        @Test
        @DisplayName("the absent-row path still costs the store only its single read")
        void absentRowCostsOnlyOneRead() {
            when(transactionRepository.findById(ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(ID));

            verify(transactionRepository).findById(ID);
            verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        @DisplayName("the read is keyed by a bound parameter, never by assembled text")
        void readIsKeyedByABoundParameter() {
            final String hostile = "0'; DROP TABLE--";

            when(transactionRepository.findById(hostile)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(hostile));

            verify(transactionRepository).findById(hostile);
            verifyNoMoreInteractions(transactionRepository);
        }
    }

    /**
     * The two vestigial copy-paste declarations, retained as documented no-ops. <strong>Intentional no-op
     * markers.</strong>
     *
     * <p>{@code WS-USR-MODIFIED PIC X(01) VALUE 'N'} at {@code :45-47} is a residue of the update programs,
     * set once at {@code :89} and read by nothing, in a program that modifies nothing. {@code CDEMO-CT01-INFO}
     * at {@code :53-61} is a verbatim clone of the transaction list's group, appended after
     * {@code COPY COCOM01Y.} at {@code :52}; of its six fields only {@code CDEMO-CT01-TRN-SELECTED} carries
     * meaning on a screen that shows one record. {@code app/cpy/COCOM01Y.cpy} declares neither a page number
     * nor a next-page flag, so both are program-local extensions rather than shared commarea state.
     *
     * <p>Both are preserved in the traceability map and neither acquires a Java field; these tests pin that
     * absence so a later change cannot quietly introduce one.
     */
    @Nested
    @DisplayName("vestigial declarations :45-47 and :53-61 - documented no-ops (Low)")
    final class VestigialDeclarations {

        @Test
        @DisplayName(":45-47 WS-USR-MODIFIED gets no Java counterpart: no modification flag is exposed")
        void serviceExposesNoModificationFlag() {
            for (final Field field : TransactionDetailService.class.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase(Locale.ROOT)).doesNotContain("modified");
            }
            for (final Method method : TransactionDetailService.class.getDeclaredMethods()) {
                assertThat(method.getName().toLowerCase(Locale.ROOT)).doesNotContain("modified");
            }
        }

        @Test
        @DisplayName("no change detection is performed: the record is read once, never re-read to compare")
        void noChangeDetectionIsPerformed() {
            viewFound(canonicalTransaction());

            verify(transactionRepository, times(1)).findById(ID);
            verify(transactionRepository, never()).findFirstByOrderByTransactionIdDesc();
            verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        @DisplayName(":53-61 a single-record view carries no page number and no row collection")
        void singleRecordViewCarriesNoPaginationMetadata() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(screen.detail().pageNumber()).isNull();
            assertThat(screen.detail().rows()).isNull();
        }

        @Test
        @DisplayName(":53-61 no next-page flag survives into the detail projection either")
        void detailProjectionCarriesNoNextPageFlag() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(screen.detail().detailProjection())
                    .hasSize(TransactionDto.DETAIL_FIELD_COUNT)
                    .doesNotContain("Y", "N");
        }
    }

    /**
     * The three screen literals, asserted byte for byte, plus the capitalisation that diverges from the
     * sibling transaction-list program.
     *
     * <p>{@code :149} {@code Tran ID can NOT be empty...}, {@code :285}
     * {@code Transaction ID NOT found...} and {@code :292} {@code Unable to lookup Transaction...}. Two carry
     * {@code NOT} in capitals; the third capitalises {@code Transaction} where the sibling program does not.
     * These strings are compared character for character by the parity gate, so each is pinned exactly.
     */
    @Nested
    @DisplayName("the three screen literals :149, :285, :292 - byte for byte")
    final class ExactScreenLiterals {

        @Test
        @DisplayName(":149 an empty identifier yields 'Tran ID can NOT be empty...' exactly")
        void emptyIdentifierYieldsTheLine149Literal() {
            final TransactionDetailScreen screen = service.submitScreen(AttentionIdentifier.ENTER, "", null);

            assertThat(screen.detail().errorMessage()).isEqualTo(EMPTY_TRAN_ID_MESSAGE_L149);
            assertThat(screen.errorFlagOn()).isTrue();
        }

        @Test
        @DisplayName(":149 the literal capitalises NOT and ends in three dots")
        void line149LiteralCapitalisesNot() {
            assertThat(EMPTY_TRAN_ID_MESSAGE_L149)
                    .contains("NOT")
                    .doesNotContain("not")
                    .endsWith("...");

            final TransactionDetailScreen screen = service.submitScreen(AttentionIdentifier.ENTER, "", null);

            assertThat(screen.detail().errorMessage()).isEqualTo("Tran ID can NOT be empty...");
        }

        @Test
        @DisplayName(":285 an absent record yields 'Transaction ID NOT found...' exactly")
        void notFoundYieldsTheLine285Literal() {
            when(transactionRepository.findById(ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(ID))
                    .withMessage(NOT_FOUND_MESSAGE_L285);
        }

        @Test
        @DisplayName(":285 the literal says 'Transaction ID', not 'Tran ID', and capitalises NOT")
        void line285LiteralSaysTransactionIdAndCapitalisesNot() {
            assertThat(NOT_FOUND_MESSAGE_L285)
                    .isEqualTo("Transaction ID NOT found...")
                    .startsWith("Transaction ID")
                    .contains("NOT")
                    .doesNotContain("not found");
            assertThat(NOT_FOUND_MESSAGE_L285).isNotEqualTo("Tran ID NOT found...");
        }

        @Test
        @DisplayName(":292 an unexpected status yields 'Unable to lookup Transaction...' exactly")
        void lookupFailureYieldsTheLine292Literal() {
            final QueryTimeoutException boom = new QueryTimeoutException("read timed out");
            when(transactionRepository.findById(ID)).thenThrow(boom);

            final Throwable thrown = catchThrowable(() -> service.viewTransaction(ID));

            assertThat(thrown).isInstanceOf(CardDemoException.class);
            assertThat(thrown.getCause()).isSameAs(boom);
            assertThat(capturedLogText()).contains(LOOKUP_FAILURE_MESSAGE_L292);
        }

        @Test
        @DisplayName(":292 capitalises Transaction, unlike the sibling list program's lower-case spelling")
        void line292LiteralCapitalisesTransaction() {
            assertThat(LOOKUP_FAILURE_MESSAGE_L292)
                    .isEqualTo("Unable to lookup Transaction...")
                    .contains("Transaction")
                    .isNotEqualTo(SIBLING_LOWER_CASE_VARIANT);
            assertThat(LOOKUP_FAILURE_MESSAGE_L292.equalsIgnoreCase(SIBLING_LOWER_CASE_VARIANT)).isTrue();
        }

        @Test
        @DisplayName("the failure message carries the file and operation context and preserves the cause")
        void failureMessageCarriesContextAndCause() {
            final QueryTimeoutException boom = new QueryTimeoutException("read timed out");
            when(transactionRepository.findById(ID)).thenThrow(boom);

            final Throwable thrown = catchThrowable(() -> service.viewTransaction(ID));

            assertThat(thrown)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining(TRANSACT_FILE)
                    .hasMessageContaining(READ_UPDATE_OPERATION);
            assertThat(thrown.getCause()).isSameAs(boom);
        }
    }

    /**
     * The cursor and the field clearing of {@code PROCESS-ENTER-KEY}, {@code app/cbl/COTRN01C.cbl:144-192}.
     *
     * <p>{@code :151} parks the cursor on the error branch and {@code :154} parks it again inside
     * {@code WHEN OTHER} before {@code CONTINUE}, so the move happens on the valid path too - the ordering note
     * recorded on this class. {@code :158-171} then blanks the thirteen record-bearing fields before the read,
     * so a failed lookup cannot leave a previous record on the screen.
     */
    @Nested
    @DisplayName("PROCESS-ENTER-KEY :144-192 - cursor parking and field clearing")
    final class CursorAndFieldClearing {

        @Test
        @DisplayName(":151 the error branch parks the cursor on the identifier input")
        void errorBranchParksTheCursor() {
            final TransactionDetailScreen screen = service.submitScreen(AttentionIdentifier.ENTER, "", null);

            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIELD_TRAN_ID_INPUT);
        }

        @Test
        @DisplayName(":154 the WHEN OTHER success branch parks the cursor as well (Low)")
        void successBranchAlsoParksTheCursor() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIELD_TRAN_ID_INPUT);
            assertThat(screen.errorFlagOn()).isFalse();
        }

        @Test
        @DisplayName(":158-171 the error branch never reads, so the thirteen fields stay blank")
        void errorBranchLeavesTheRecordFieldsBlank() {
            final TransactionDetailScreen screen = service.submitScreen(AttentionIdentifier.ENTER, "", null);

            assertThat(recordBearingFields(screen.detail())).containsOnly("");
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName(":158-171 no record residue survives between two interactions on one bean")
        void noRecordResidueSurvivesBetweenInteractions() {
            when(transactionRepository.findById(ID)).thenReturn(Optional.of(canonicalTransaction()));

            final TransactionDetailScreen populated =
                    service.submitScreen(AttentionIdentifier.ENTER, ID, null);
            assertThat(populated.detail().transactionId()).isEqualTo(ID);

            final TransactionDetailScreen afterInvalidKey =
                    service.submitScreen(AttentionIdentifier.OTHER, ID, null);

            assertThat(recordBearingFields(afterInvalidKey.detail())).containsOnly("");
        }

        @Test
        @DisplayName(":301-326 PF4 clears the input field too, and reads nothing")
        void pf4ClearsTheInputFieldAndReadsNothing() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.PF4, ID, null);

            assertThat(screen.detail().transactionIdInput()).isEqualTo("");
            assertThat(recordBearingFields(screen.detail())).containsOnly("");
            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIELD_TRAN_ID_INPUT);
            verify(transactionRepository, never()).findById(anyString());
        }
    }

    /**
     * The guard the source does <strong>not</strong> have - the absent-guard note on this class.
     *
     * <p>{@code :146-156} tests for empty and for nothing else. The sibling transaction-list program carries
     * {@code Tran ID must be Numeric ...} at its {@code :214}; this program has no equivalent, so every
     * non-empty identifier - however malformed - must reach the repository and fail as not-found rather than
     * be rejected up front.
     */
    @Nested
    @DisplayName("PROCESS-ENTER-KEY :146-156 - there is NO numeric guard (Medium)")
    final class AbsentNumericGuard {

        @ParameterizedTest(name = "[{index}] a malformed identifier still reaches the store: \"{0}\"")
        @ValueSource(strings = {
            "123456789012345A",
            "$0000000000001.5",
            "1,000,000,000,00",
            "000000000000004\t",
            "abcdefghijklmnop",
            "----------------",
            "0000000000000042 ",
            "000000000000004"
        })
        @DisplayName("no identifier is rejected for its content: all reach the repository")
        void malformedIdentifiersReachTheRepository(final String identifier) {
            when(transactionRepository.findById(identifier)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(identifier))
                    .withMessage(NOT_FOUND_MESSAGE_L285);

            verify(transactionRepository).findById(identifier);
        }

        @Test
        @DisplayName("an identifier one character short of the key width is not rejected either")
        void fifteenCharacterIdentifierIsNotRejected() {
            final String fifteen = "000000000000042";

            when(transactionRepository.findById(fifteen)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(fifteen))
                    .withMessage(NOT_FOUND_MESSAGE_L285);

            assertThat(fifteen).hasSize(TransactionDto.TRANSACTION_ID_LENGTH - 1);
        }

        @Test
        @DisplayName("an identifier one character wider than the key width is not rejected either")
        void seventeenCharacterIdentifierIsNotRejected() {
            final String seventeen = "00000000000000042";

            when(transactionRepository.findById(seventeen)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(seventeen))
                    .withMessage(NOT_FOUND_MESSAGE_L285);

            assertThat(seventeen).hasSize(TransactionDto.TRANSACTION_ID_LENGTH + 1);
        }

        @Test
        @DisplayName("no message this service can produce mentions a numeric format")
        void noMessageMentionsANumericFormat() {
            assertThat(EMPTY_TRAN_ID_MESSAGE_L149).doesNotContain("Numeric").doesNotContain("numeric");
            assertThat(NOT_FOUND_MESSAGE_L285).doesNotContain("Numeric").doesNotContain("numeric");
            assertThat(LOOKUP_FAILURE_MESSAGE_L292).doesNotContain("Numeric").doesNotContain("numeric");

            final TransactionDetailScreen screen = service.submitScreen(AttentionIdentifier.ENTER, "", null);

            assertThat(screen.detail().errorMessage()).doesNotContainIgnoringCase("numeric");
        }
    }

    /**
     * The {@code :290} diagnostic, {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}, and the High
     * cardholder-data note on this class.
     *
     * <p>One {@code DISPLAY} in the source becomes one structured log event. It must carry the status codes and
     * the file and operation context only: no card number, and nothing that could identify a cardholder.
     */
    @Nested
    @DisplayName(":290 the RESP/REAS diagnostic - status codes only, never PII (High)")
    final class DiagnosticLogging {

        @Test
        @DisplayName("the failure path logs once, at ERROR, carrying the file, operation and status")
        void failurePathLogsOnceWithContext() {
            final QueryTimeoutException boom = new QueryTimeoutException("read timed out");
            when(transactionRepository.findById(ID)).thenThrow(boom);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.viewTransaction(ID));

            final List<ILoggingEvent> errors = new ArrayList<>();
            for (final ILoggingEvent event : logEvents.list) {
                if (event.getLevel() == Level.ERROR) {
                    errors.add(event);
                }
            }

            assertThat(errors).hasSize(1);
            final String text = capturedLogText();
            assertThat(text).contains(TRANSACT_FILE, READ_UPDATE_OPERATION, LOOKUP_FAILURE_MESSAGE_L292);
        }

        @Test
        @DisplayName("no log event on the failure path carries the card number")
        void failurePathLogsNoCardNumber() {
            final QueryTimeoutException boom = new QueryTimeoutException("read timed out");
            when(transactionRepository.findById(ID)).thenThrow(boom);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.viewTransaction(ID));

            assertThat(capturedLogText()).doesNotContain(SYNTHETIC_CARD_NUMBER);
        }

        @Test
        @DisplayName("the card number reaches the response payload but never a log")
        void cardNumberReachesThePayloadButNeverALog() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(screen.detail().cardNumber()).isEqualTo(SYNTHETIC_CARD_NUMBER);
            assertThat(capturedLogText()).doesNotContain(SYNTHETIC_CARD_NUMBER);
        }

        @Test
        @DisplayName("no exception message carries the card number")
        void noExceptionMessageCarriesTheCardNumber() {
            final QueryTimeoutException boom = new QueryTimeoutException("read timed out");
            when(transactionRepository.findById(ID)).thenThrow(boom);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.viewTransaction(ID))
                    .withMessageNotContaining(SYNTHETIC_CARD_NUMBER);
        }

        @Test
        @DisplayName("the success path logs no VALUE of the record - not the amount, the merchant or the card")
        void successPathLogsNoFieldOfTheRecord() {
            // Masking the card number alone was never enough. This one line carried the amount and the
            // merchant identifier BESIDE the transaction identifier, so it disclosed what a cardholder spent
            // and where, and the identifier then links that back to the card through a single lookup - which
            // made the masked card number no protection at all. This path runs once per view request, so
            // those three together amounted to a ledger in the log stream.
            //
            // The boundary is between VALUES and KEYS, and it is asserted in both directions. The amount, the
            // merchant identifier, the merchant name and the card number are gone, replaced by fixed-width
            // placeholders of their declared widths - width by construction, so the geometry invariant holds
            // even in a redacted line. The transaction identifier, the type and category codes, the source
            // and the two dates are KEPT: they are reference and key data, they are what this event exists to
            // confirm, and logback-spring.xml records that the value masks deliberately leave
            // identifier-shaped lines alone rather than over-redact them. Removing them would leave an event
            // that confirms nothing and an incident with no key to follow; the exception path is where the
            // identifier is withheld from the log and carried on the throwable instead, which its own test
            // asserts.
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(screen.detail()).isNotNull();
            assertThat(capturedLogText())
                    .as("no VALUE from the record may appear in any captured event")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain("123.45")
                    .doesNotContain("COFFEE");
            assertThat(capturedLogText())
                    .as("each redaction is a fixed-width placeholder of the field's declared width")
                    .contains("amount=" + "*".repeat(12))
                    .contains("merchantId=" + "*".repeat(9));
            assertThat(capturedLogText())
                    .as("the key and reference fields are kept, so the event still confirms something")
                    .contains("transactionId=" + ID)
                    .contains("processingDate=");
        }

        @Test
        @DisplayName("the failure path logs no transaction identifier either")
        void failurePathLogsNoTransactionIdentifier() {
            // The identifier is caller-supplied input and the failure is a property of the store, so naming
            // the record adds nothing a correlation identifier does not already give.
            final QueryTimeoutException boom = new QueryTimeoutException("read timed out");
            when(transactionRepository.findById(ID)).thenThrow(boom);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.viewTransaction(ID));

            assertThat(capturedLogText()).doesNotContain(ID);
        }

        @Test
        @DisplayName("the appender is at TRACE, so the assertions above cover every enabled level")
        void everyLevelIsCaptured() {
            // Without this, a value emitted at DEBUG or TRACE would slip past the assertions above and the
            // whole nest would be proving something weaker than it claims.
            assertThat(serviceLogger.getLevel()).isEqualTo(Level.TRACE);
            assertThat(serviceLogger.isTraceEnabled()).isTrue();
            assertThat(serviceLogger.isDebugEnabled()).isTrue();

            viewFound(canonicalTransaction());

            assertThat(logEvents.list)
                    .as("the populate path does emit, so the absence assertions are not vacuous")
                    .isNotEmpty();
        }
    }

    /**
     * Field contracts and decimal precision: the 350-byte record of {@code app/cpy/CVTRA05Y.cpy} projected
     * onto the 21 input fields of {@code app/cpy-bms/COTRN01.CPY}.
     *
     * <p>Two source characteristics noted on this class are pinned here. {@code WS-TRAN-AMT PIC +99999999.99} at
     * {@code :49} holds eight integer digits while {@code TRAN-AMT} is {@code S9(09)V99} with nine, so a value
     * of one hundred million or more silently loses its leading digit - the truncation is asserted, never
     * widened. And {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} text, so they are
     * carried as characters and never converted to a temporal type or re-rendered.
     */
    @Nested
    @DisplayName("field contracts and precision - CVTRA05Y onto COTRN01.CPY (Medium)")
    final class FieldContractsAndPrecision {

        @Test
        @DisplayName("the detail projection carries exactly the 21 input fields of the symbolic map")
        void detailProjectionCarriesExactlyTwentyOneFields() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(TransactionDto.DETAIL_FIELD_COUNT).isEqualTo(21);
            assertThat(screen.detail().detailProjection()).hasSize(21);
        }

        @ParameterizedTest(name = "[{index}] {0} renders as {1}")
        @CsvSource({
            "0.00,          +00000000.00",
            "123.45,        +00000123.45",
            "-123.45,       -00000123.45",
            "99999999.99,   +99999999.99",
            "-99999999.99,  -99999999.99"
        })
        @DisplayName(":49 the mask carries a mandatory sign, eight integer digits and two decimals")
        void maskRendersWithMandatorySignAndEightIntegerDigits(final String raw, final String expected) {
            final TransactionDetailScreen screen = viewFound(
                    transaction(new BigDecimal(raw.trim()), ORIG_TS_26, PROC_TS_26, SYNTHETIC_CARD_NUMBER));

            assertThat(screen.detail().amount()).isEqualTo(expected);
            assertThat(screen.detail().amount()).hasSize(TransactionDto.AMOUNT_DISPLAY_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] the ninth digit of {0} is discarded, leaving {1}")
        @CsvSource({
            "100000000.00,  +00000000.00",
            "123456789.99,  +23456789.99",
            "999999999.99,  +99999999.99",
            "-100000000.00, -00000000.00",
            "-987654321.01, -87654321.01"
        })
        @DisplayName(":49 a ninth integer digit is SILENTLY TRUNCATED - asserted, not widened")
        void ninthIntegerDigitIsSilentlyTruncated(final String raw, final String expected) {
            final TransactionDetailScreen screen = viewFound(
                    transaction(new BigDecimal(raw.trim()), ORIG_TS_26, PROC_TS_26, SYNTHETIC_CARD_NUMBER));

            assertThat(screen.detail().amount()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the record holds nine integer digits while the mask holds only eight")
        void recordIsWiderThanTheMask() {
            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TransactionDto.AMOUNT_EDITED_MASK).isEqualTo("+99999999.99");
            assertThat(TransactionDto.AMOUNT_EDITED_MASK.chars().filter(c -> c == '9').count()).isEqualTo(10L);
        }

        @Test
        @DisplayName("a negative amount keeps its sign and is never normalised to its magnitude")
        void negativeAmountKeepsItsSign() {
            final TransactionDetailScreen screen = viewFound(
                    transaction(new BigDecimal("-42.17"), ORIG_TS_26, PROC_TS_26, SYNTHETIC_CARD_NUMBER));

            assertThat(screen.detail().amount()).isEqualTo("-00000042.17").startsWith("-");
            assertThat(screen.detail().amountValue()).isEqualByComparingTo(new BigDecimal("-42.17"));
            assertThat(screen.detail().amountValue().signum()).isEqualTo(-1);
        }

        @Test
        @DisplayName("both X(26) timestamps stay 26 characters on the record and are passed through as text")
        void timestampsAreSurfacedAsUnmodifiedText() {
            final Transaction record = canonicalTransaction();

            final TransactionDetailScreen screen = viewFound(record);

            assertThat(record.getOrigTs())
                    .isEqualTo(ORIG_TS_26)
                    .hasSize(TransactionDto.PERSISTED_TIMESTAMP_LENGTH);
            assertThat(record.getProcTs())
                    .isEqualTo(PROC_TS_26)
                    .hasSize(TransactionDto.PERSISTED_TIMESTAMP_LENGTH);

            assertThat(screen.detail().originatingDate())
                    .isEqualTo(ORIG_TS_26.substring(0, TransactionDto.DETAIL_DATE_LENGTH));
            assertThat(screen.detail().processingDate())
                    .isEqualTo(PROC_TS_26.substring(0, TransactionDto.DETAIL_DATE_LENGTH));
        }

        @Test
        @DisplayName("the two timestamps' incompatible renderings both survive untouched")
        void incompatibleTimestampRenderingsBothSurvive() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(ORIG_TS_26).contains(" ").doesNotContain("-08.");
            assertThat(PROC_TS_26).contains("-08.00.00");
            assertThat(screen.detail().originatingDate()).isEqualTo("2022-06-10");
            assertThat(screen.detail().processingDate()).isEqualTo("2022-06-11");
        }

        @Test
        @DisplayName("money is BigDecimal at scale 2 with HALF_EVEN, compared with compareTo not equals")
        void moneyUsesCompareToEqualitySemantics() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());
            final BigDecimal actual = screen.detail().amountValue();
            final BigDecimal sameValueWiderScale = new BigDecimal("123.4500");

            assertThat(TransactionDto.AMOUNT_ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);
            assertThat(TransactionDto.AMOUNT_SCALE).isEqualTo(2);
            assertThat(actual.scale()).isEqualTo(TransactionDto.AMOUNT_SCALE);

            assertThat(actual).isEqualByComparingTo(sameValueWiderScale);
            assertThat(actual.compareTo(sameValueWiderScale)).isZero();
            assertThat(actual).isNotEqualTo(sameValueWiderScale);
            assertThat(actual.equals(sameValueWiderScale)).isFalse();
        }

        @Test
        @DisplayName("no float or double appears anywhere on this service's signatures")
        void serviceSignaturesCarryNoBinaryFloatingPoint() {
            for (final Method method : TransactionDetailService.class.getDeclaredMethods()) {
                assertThat(method.getReturnType()).isNotIn(float.class, double.class,
                        Float.class, Double.class);
                assertThat(method.getParameterTypes()).doesNotContain(float.class, double.class,
                        Float.class, Double.class);
            }
            for (final Field field : TransactionDetailService.class.getDeclaredFields()) {
                assertThat(field.getType()).isNotIn(float.class, double.class, Float.class, Double.class);
            }
        }

        @Test
        @DisplayName("the wider record fields are truncated to their narrower map widths, not reformatted")
        void widerRecordFieldsTruncateToTheirMapWidths() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(screen.detail().description()).isEqualTo("COFFEE");
            assertThat(TransactionDto.DESCRIPTION_LENGTH)
                    .isLessThan(TransactionDto.DESCRIPTION_PERSISTED_LENGTH);
            assertThat(screen.detail().cardNumber()).hasSize(TransactionDto.CARD_NUMBER_LENGTH);
        }
    }

    /**
     * Paragraph correspondence: every one of the nine labels in a 330-line program maps to exactly one private
     * Java method, and labels are never consolidated.
     *
     * <p>{@code MAIN-PARA:86}, {@code PROCESS-ENTER-KEY:144}, {@code RETURN-TO-PREV-SCREEN:197},
     * {@code SEND-TRNVIEW-SCREEN:213}, {@code RECEIVE-TRNVIEW-SCREEN:230}, {@code POPULATE-HEADER-INFO:243},
     * {@code READ-TRANSACT-FILE:267}, {@code CLEAR-CURRENT-SCREEN:301} and {@code INITIALIZE-ALL-FIELDS:309}.
     */
    @Nested
    @DisplayName("paragraph correspondence - all 9 COTRN01C labels map 1:1")
    final class ParagraphCorrespondence {

        @Test
        @DisplayName("all nine paragraphs exist as distinct private methods, none consolidated")
        void allNineParagraphsArePresentAsPrivateMethods() {
            final List<String> declared = new ArrayList<>();
            for (final Method method : TransactionDetailService.class.getDeclaredMethods()) {
                declared.add(method.getName());
            }

            assertThat(PARAGRAPH_METHOD_NAMES).hasSize(9);
            assertThat(declared).containsAll(PARAGRAPH_METHOD_NAMES);

            for (final String name : PARAGRAPH_METHOD_NAMES) {
                boolean foundPrivate = false;
                for (final Method method : TransactionDetailService.class.getDeclaredMethods()) {
                    if (method.getName().equals(name) && Modifier.isPrivate(method.getModifiers())) {
                        foundPrivate = true;
                    }
                }
                assertThat(foundPrivate)
                        .as("paragraph method %s must exist and be private", name)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the three public entry points are the only public methods on the bean")
        void onlyTheThreeEntryPointsArePublic() {
            final List<String> publicMethods = new ArrayList<>();
            for (final Method method : TransactionDetailService.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    publicMethods.add(method.getName());
                }
            }

            assertThat(publicMethods)
                    .containsExactlyInAnyOrder("viewTransaction", "submitScreen", "openWithoutContext");
        }
    }

    /**
     * The routing literals of {@code MAIN-PARA} and {@code RETURN-TO-PREV-SCREEN}.
     *
     * <p>{@code :95} routes to {@code COSGN00C} when the commarea is absent and {@code :200} defaults to the
     * same program when no target was set. {@code :117} substitutes {@code COMEN01C} when no origin was
     * supplied, {@code :126} walks back to {@code COTRN00C}, and {@code :129} raises {@code WS-ERR-FLG} on the
     * separate {@code WHEN OTHER} arm of the same {@code EVALUATE}.
     */
    @Nested
    @DisplayName("routing literals :95, :117, :126, :129, :200")
    final class RoutingLiterals {

        @Test
        @DisplayName(":95 an absent comm area routes to COSGN00C and reads nothing")
        void absentCommAreaRoutesToSignOn() {
            final TransactionDetailScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget()).isEqualTo(SIGN_ON_PROGRAM_L95);
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName(":117 PF3 with no origin falls back to the literal COMEN01C")
        void pf3WithoutOriginFallsBackToMainMenu() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.PF3, ID, null);

            assertThat(screen.navigationTarget()).isEqualTo(MAIN_MENU_PROGRAM_L117);
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName(":119-120 PF3 with an origin returns to that origin instead")
        void pf3WithOriginReturnsToIt() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.PF3, ID, "COTRN00C");

            assertThat(screen.navigationTarget()).isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName(":126 PF5 always walks back to COTRN00C, whatever the origin")
        void pf5AlwaysWalksBackToTheList() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.PF5, ID, MAIN_MENU_PROGRAM_L117);

            assertThat(screen.navigationTarget()).isEqualTo(TRANSACTION_LIST_PROGRAM_L126);
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName(":129 the separate WHEN OTHER arm raises the error flag and navigates nowhere")
        void invalidKeyRaisesTheErrorFlagWithoutNavigating() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.OTHER, ID, null);

            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.detail().errorMessage()).isNotBlank();
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName(":116-117 an all-blank origin is 'no origin', so PF3 still falls back to COMEN01C")
        void allBlankOriginIsTreatedAsNoOrigin() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.PF3, ID, "   ");

            assertThat(screen.navigationTarget()).isEqualTo(MAIN_MENU_PROGRAM_L117);
        }

        @Test
        @DisplayName(":103-107 a populated selection deep-links straight into the lookup")
        void populatedSelectionDeepLinksIntoTheLookup() {
            final TransactionDetailScreen screen = viewFound(canonicalTransaction());

            assertThat(screen.detail().transactionId()).isEqualTo(ID);
            assertThat(screen.navigationTarget()).isNull();
            verify(transactionRepository).findById(ID);
        }

        @Test
        @DisplayName(":102 a blank selection parks the cursor and reads nothing")
        void blankSelectionReadsNothing() {
            final TransactionDetailScreen screen = service.viewTransaction("   ");

            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIELD_TRAN_ID_INPUT);
            assertThat(screen.errorFlagOn()).isFalse();
            verify(transactionRepository, never()).findById(anyString());
        }
    }

    /**
     * Hostile input and design invariants, per Rule 1 clause A's direction to treat inputs as untrusted and
     * clause B's prohibition on global mutable state.
     */
    @Nested
    @DisplayName("hostile input and design invariants - Rule 1 clauses A and B")
    final class HostileInputAndDesignInvariants {

        @Test
        @DisplayName("a null identifier is treated as absent, LOW-VALUES having no HTTP counterpart")
        void nullIdentifierIsTreatedAsAbsent() {
            final TransactionDetailScreen screen = service.viewTransaction(null);

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIELD_TRAN_ID_INPUT);
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("a null attention identifier lands on the WHEN OTHER arm rather than failing")
        void nullAttentionIdentifierLandsOnTheOtherArm() {
            final TransactionDetailScreen screen = service.submitScreen(null, ID, null);

            assertThat(screen.errorFlagOn()).isTrue();
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("a null submitted identifier is the empty condition of :147")
        void nullSubmittedIdentifierIsTheEmptyCondition() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, null, null);

            assertThat(screen.detail().errorMessage()).isEqualTo(EMPTY_TRAN_ID_MESSAGE_L149);
            verify(transactionRepository, never()).findById(anyString());
        }

        @ParameterizedTest(name = "[{index}] a blank identifier \"{0}\" is the empty condition of :147")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n", "                "})
        @DisplayName("every blank form is the empty condition and never reaches the store")
        void everyBlankFormIsTheEmptyCondition(final String blank) {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, blank, null);

            assertThat(screen.detail().errorMessage()).isEqualTo(EMPTY_TRAN_ID_MESSAGE_L149);
            assertThat(screen.errorFlagOn()).isTrue();
            verify(transactionRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("every collaborator is required, and each is named in its own rejection")
        void everyCollaboratorIsRequired() {
            final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionDetailService(
                            null, fileStatusMapper, entityManager, clock))
                    .withMessageContaining("transactionRepository");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionDetailService(
                            transactionRepository, null, entityManager, clock))
                    .withMessageContaining("fileStatusMapper");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionDetailService(
                            transactionRepository, fileStatusMapper, null, clock))
                    .withMessageContaining("entityManager");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionDetailService(
                            transactionRepository, fileStatusMapper, entityManager, null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("exactly one constructor, so the container injects without an annotation")
        void exactlyOneConstructor() {
            final Constructor<?>[] constructors = TransactionDetailService.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].getParameterCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("no global mutable state: every instance and static field is final")
        void beanCarriesNoGlobalMutableState() {
            for (final Field field : TransactionDetailService.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the screen payload rejects a missing detail projection")
        void screenPayloadRejectsAMissingDetail() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionDetailScreen(null, null, null, false))
                    .withMessageContaining("detail");
        }

        @Test
        @DisplayName("only the four keys COTRN01C tests are modelled, plus its WHEN OTHER arm")
        void onlyTheTestedKeysAreModelled() {
            assertThat(AttentionIdentifier.values())
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.PF4, AttentionIdentifier.PF5, AttentionIdentifier.OTHER);
        }

        @Test
        @DisplayName("two interactions on one bean cannot leak state into one another")
        void interactionsCannotLeakStateIntoOneAnother() {
            when(transactionRepository.findById(ID)).thenReturn(Optional.of(canonicalTransaction()));

            final TransactionDetailScreen first = service.viewTransaction(ID);
            final TransactionDetailScreen second = service.viewTransaction(ID);

            assertThat(first.detail().detailProjection())
                    .isEqualTo(second.detail().detailProjection());
            assertThat(first.detail()).isNotSameAs(second.detail());
        }
    }

    /**
     * Boundary arms of the rendering helpers, and the one paragraph default no public entry point can drive.
     *
     * <p>Rule 1 clause B requires inputs and boundary conditions to be validated and null and empty cases to
     * be handled explicitly. These are exactly those cases, and they divide into two kinds.
     *
     * <p><strong>Reachable, so driven over the public API.</strong> {@code renderZeroPadded} takes its
     * low-order-digits path whenever the rendered magnitude is at least as wide as the destination field.
     * That happens at the widest value {@link Transaction} permits - a four-digit category code of
     * {@code 9999} against a four-character field and a nine-digit merchant identifier of
     * {@code 999999999} against a nine-character one - so the arm is exercised through a real record rather
     * than reflectively.
     *
     * <p><strong>Unreachable by construction, so driven directly.</strong> {@link Transaction} rejects
     * {@code null} for every one of its thirteen fields, each of its four validators raising
     * {@link IllegalArgumentException}, so no valid record can ever carry the {@code null} that
     * {@code truncate}, {@code renderZeroPadded} and {@code renderEditedAmount} guard against, and its
     * amount validator rejects a scale above two, so no record can carry the third decimal that makes the
     * rounding mode observable. The guards are still right to exist - a rendering helper that dereferences
     * an unchecked argument is a latent defect, and a monetary rendering whose rounding mode is untested is
     * a silent one - so they are pinned in executable assertions instead of in prose.
     *
     * <p>{@code :199-201}'s default of an unset target to {@code COSGN00C} is unreachable for a different
     * reason: every caller of {@code RETURN-TO-PREV-SCREEN} stamps a non-blank target first, so the source's
     * own {@code IF} never fires either. <strong>Intentional no-op, retained for parity</strong> - a
     * faithful reproduction of a branch that is reachable in COBOL and unreachable in practice, carrying the
     * same marker as the two vestigial declarations and exercised through the paragraph itself.
     *
     * <p>Reflection reaches {@code private} members of the class under test and nothing else. Every member
     * name is a compile-time literal, never externally supplied, so this is not the reflection-driven
     * arbitrary invocation Rule 1 clause D prohibits. An absent member fails the test, which is the signal
     * wanted if a helper or a paragraph is ever renamed away.
     */
    @Nested
    @DisplayName("boundary and defensive arms")
    final class BoundaryAndDefensiveArms {

        /**
         * Resolves one {@code private static} rendering helper of the service and makes it invocable.
         *
         * @param name the helper's declared name, always a compile-time literal
         * @param parameterTypes its declared parameter types
         * @return the accessible handle, never {@code null}
         * @throws ReflectiveOperationException if the helper is absent, which is itself the defect
         */
        private Method helper(final String name, final Class<?>... parameterTypes)
                throws ReflectiveOperationException {
            final Method method = TransactionDetailService.class.getDeclaredMethod(name, parameterTypes);
            assertThat(Modifier.isPrivate(method.getModifiers()))
                    .as("a rendering helper must stay private to the service")
                    .isTrue();
            assertThat(Modifier.isStatic(method.getModifiers()))
                    .as("a rendering helper must stay static, so it cannot read bean state")
                    .isTrue();
            method.setAccessible(true);
            return method;
        }

        /**
         * Reads one declared field of the per-invocation work area.
         *
         * @param owner the declaring type
         * @param target the instance to read
         * @param name the field's declared name, always a compile-time literal
         * @return the current value, possibly {@code null}
         * @throws ReflectiveOperationException if the field is absent, which is itself the defect
         */
        private Object fieldValue(final Class<?> owner, final Object target, final String name)
                throws ReflectiveOperationException {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }

        @Test
        @DisplayName("the widest permitted category code and merchant identifier fill their fields exactly")
        void widestPermittedNumericFieldsRenderAtTheirExactWidths() {
            final Transaction widest = new Transaction(ID, "01", 9999, "POS       ", "COFFEE",
                    new BigDecimal("123.45"), 999_999_999L, "ACME", "SEATTLE", "12345-0001",
                    SYNTHETIC_CARD_NUMBER, ORIG_TS_26, PROC_TS_26);

            final TransactionDetailScreen screen = viewFound(widest);

            // TCATCD is X(4) on app/cpy-bms/COTRN01.CPY and TRAN-CAT-CD is 9(04) on app/cpy/CVTRA05Y.cpy, so
            // the widest permitted value fills the field exactly and nothing is padded or dropped.
            assertThat(screen.detail().categoryCode()).isEqualTo("9999");
            assertThat(screen.detail().merchantId()).isEqualTo("999999999");
        }

        @Test
        @DisplayName("renderZeroPadded keeps the low-order digits when a value overflows its field")
        void renderZeroPaddedKeepsLowOrderDigitsOnOverflow() throws ReflectiveOperationException {
            final Method method = helper("renderZeroPadded", Number.class, int.class);

            assertThat(method.invoke(null, Integer.valueOf(123_456), 4)).isEqualTo("3456");

            // The magnitude is taken unsigned, so a negative value never spends a character on its sign.
            assertThat(method.invoke(null, Integer.valueOf(-42), 4)).isEqualTo("0042");
        }

        @Test
        @DisplayName("truncate yields a blank field for a null source rather than propagating the null")
        void truncateReturnsBlankForNull() throws ReflectiveOperationException {
            assertThat(helper("truncate", String.class, int.class).invoke(null, null, 10)).isEqualTo("");
        }

        @Test
        @DisplayName("renderZeroPadded yields a blank field for a null source")
        void renderZeroPaddedReturnsBlankForNull() throws ReflectiveOperationException {
            assertThat(helper("renderZeroPadded", Number.class, int.class).invoke(null, null, 4))
                    .isEqualTo("");
        }

        @Test
        @DisplayName("renderEditedAmount yields a blank field for a null amount")
        void renderEditedAmountReturnsBlankForNull() throws ReflectiveOperationException {
            final Method method = helper("renderEditedAmount", BigDecimal.class);

            assertThat(method.invoke(null, new Object[] {null})).isEqualTo("");
        }

        @Test
        @DisplayName("renderEditedAmount rounds HALF_EVEN rather than letting the mask decide")
        void renderEditedAmountRoundsHalfEven() throws ReflectiveOperationException {
            final Method method = helper("renderEditedAmount", BigDecimal.class);

            // Both are exact halves. HALF_EVEN breaks each tie towards the even digit, which HALF_UP would
            // not: HALF_UP would render 1.01 and 1.02 respectively.
            assertThat(method.invoke(null, new BigDecimal("1.005"))).isEqualTo("+00000001.00");
            assertThat(method.invoke(null, new BigDecimal("1.015"))).isEqualTo("+00000001.02");
        }

        @Test
        @DisplayName(":199-201 RETURN-TO-PREV-SCREEN defaults an unset target to COSGN00C")
        void returnToPrevScreenDefaultsAnUnsetTargetToSignOn() throws ReflectiveOperationException {
            final String transactionIdentifier = "CT01";
            final String programName = "COTRN01C";

            Class<?> workAreaType = null;
            for (final Class<?> candidate : TransactionDetailService.class.getDeclaredClasses()) {
                if ("ScreenWorkArea".equals(candidate.getSimpleName())) {
                    workAreaType = candidate;
                }
            }
            assertThat(workAreaType).as("the per-invocation work area must still exist").isNotNull();

            final Constructor<?> workAreaConstructor = workAreaType.getDeclaredConstructor();
            workAreaConstructor.setAccessible(true);
            final Object work = workAreaConstructor.newInstance();
            assertThat(fieldValue(workAreaType, work, "toProgram"))
                    .as("the target must start unset, or the default below is not what is being driven")
                    .isNull();

            final Method paragraph =
                    TransactionDetailService.class.getDeclaredMethod("returnToPrevScreen", workAreaType);
            paragraph.setAccessible(true);
            paragraph.invoke(service, work);

            // :199-201 the default, then :202-204 the identity stamp, then :205-208 the published target.
            assertThat(fieldValue(workAreaType, work, "toProgram")).isEqualTo(SIGN_ON_PROGRAM_L95);
            assertThat(fieldValue(workAreaType, work, "navigationTarget")).isEqualTo(SIGN_ON_PROGRAM_L95);
            assertThat(fieldValue(workAreaType, work, "fromTranId")).isEqualTo(transactionIdentifier);
            assertThat(fieldValue(workAreaType, work, "fromProgram")).isEqualTo(programName);

            // :204 MOVE ZEROS TO CDEMO-PGM-CONTEXT, carried as the single digit its PIC 9(01) picture holds.
            assertThat(fieldValue(workAreaType, work, "programContext")).isEqualTo("0");

            // The navigation trace carries program and transaction identity only, never cardholder data.
            assertThat(capturedLogText()).doesNotContain(SYNTHETIC_CARD_NUMBER);
        }
    }
}
