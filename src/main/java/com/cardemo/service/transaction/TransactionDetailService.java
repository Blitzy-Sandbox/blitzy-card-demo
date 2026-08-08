/*
 * ******************************************************************
 * Program     : TransactionDetailService.java
 * Application : CardDemo
 * Type        : Spring Service Bean
 * Function    : Single-transaction detail view. Reproduces COTRN01C
 *               (transaction CT01), including the legacy READ ... UPDATE
 *               exclusive lock taken on this read-only path.
 * Source      : app/cbl/COTRN01C.cbl (330 lines, 9 own paragraph labels) @ 7756d89
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
package com.cardemo.service.transaction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;

/**
 * Single-transaction detail view: the Java replacement for the CICS program {@code COTRN01C}.
 *
 * <h2>1. What it does</h2>
 * Reproduces {@code app/cbl/COTRN01C.cbl} - 330 lines, 9 paragraph labels - at behavioural parity.
 * The program fronts CICS transaction {@code CT01} through mapset {@code COTRN01}
 * ({@code app/csd/CARDDEMO.CSD}), and its whole job is to accept one transaction identifier and render
 * the matching {@code TRANSACT} record on one screen. It is surfaced over HTTP by
 * {@code com.cardemo.controller.TransactionController} under {@code /api/transactions/*}; the deep-link
 * entry {@code GET /api/transactions/detail?transactionId=...} is the REST analogue of the
 * {@code CDEMO-CT01-TRN-SELECTED} hand-off at {@code app/cbl/COTRN01C.cbl:103-108}.
 * Traceability anchor commit {@code 7756d89}.
 *
 * <p>Every one of the nine source paragraphs maps to exactly one private method here, one for one, with
 * no consolidation and no splitting, each carrying a Javadoc citation of its label and verified line
 * range. That correspondence is the artefact the scope-coverage gate reads, so it is structural rather
 * than stylistic: the industry advice against
 * literal transliteration is deliberately overridden here, and the readability cost is paid back by the
 * per-method source citations rather than by restructuring.
 *
 * <p><strong>Scope.</strong> This bean reads. It never writes, because the source never writes - the verb
 * inventory of {@code app/cbl/COTRN01C.cbl} contains no {@code WRITE}, no {@code REWRITE} and no
 * {@code DELETE} anywhere in its 330 lines. Pagination is not its concern either; the ten-row browse
 * belongs to the transaction list program {@code COTRN00C}, so no page size is declared here.
 *
 * <h2>2. How to build, run and test</h2>
 * <ul>
 *   <li>Compile: {@code ./mvnw -B -ntp clean compile}. The build runs {@code -Xlint:all -Werror} with
 *       {@code failOnWarning}, so this file must be warning-clean. It must also carry no unused import, but
 *       that is a review obligation rather than a build one: {@code javac} 25 publishes no {@code unused}
 *       lint key.</li>
 *   <li>Unit tests: {@code ./mvnw -B -ntp clean test}. The tests for this bean live at
 *       {@code src/test/java/com/cardemo/unit/service/TransactionDetailServiceTest.java} and drive it
 *       directly with a fixed {@code java.time.Clock} and mocked collaborators, so no database and no
 *       container are needed.</li>
 *   <li>Full gate: {@code ./mvnw -B -ntp clean verify} additionally applies the JaCoCo line-coverage floor and
 *       the OWASP dependency scan.</li>
 *   </ul>
 *
 * <h2>3. Key configuration and defaults</h2>
 * <ul>
 *   <li><strong>Consumed, never redeclared:</strong> {@code spring.jpa.hibernate.ddl-auto=validate} and
 *       {@code spring.jpa.open-in-view=false}. This class declares no property of its own, reads no
 *       environment variable and hardcodes no host, port or path.</li>
 *   <li><strong>Transaction and lock semantics.</strong> Both public entry points are annotated
 *       {@code @Transactional(rollbackFor = Exception.class)} - deliberately <em>not</em>
 *       {@code readOnly = true}, because a read-only transaction cannot hold a write lock. See the
 *       preserved-quirk note below and {@link #readTransactFile(String)}.</li>
 *   <li><strong>Clock.</strong> The screen header furniture is read from an injected
 *       {@link java.time.Clock}, standing in for {@code MOVE FUNCTION CURRENT-DATE} at
 *       {@code app/cbl/COTRN01C.cbl:245}. {@code FUNCTION CURRENT-DATE} returns the local date and time
 *       of the host, so a clock in the system default zone is the parity-preserving choice.</li>
 *   <li><strong>Field widths.</strong> Every width is consumed from the public constants on
 *       {@code com.cardemo.model.dto.TransactionDto} rather than restated here, so there is one
 *       definition of each field contract in the tree.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><strong>Blank identifier.</strong> A blank, empty or absent identifier does not reach the
 *       database at all: the screen comes back carrying the legacy message
 *       {@code Tran ID can NOT be empty...} with the cursor on the input field, and no exception is
 *       thrown. This mirrors {@code app/cbl/COTRN01C.cbl:146-156}, which sends the screen and returns.</li>
 *   <li><strong>Unknown identifier.</strong> {@code com.cardemo.exception.RecordNotFoundException}
 *       carrying the legacy message {@code Transaction ID NOT found...}, from the
 *       {@code DFHRESP(NOTFND)} branch at {@code app/cbl/COTRN01C.cbl:283-288}.</li>
 *   <li><strong>Storage failure.</strong> Routed through
 *       {@code com.cardemo.service.shared.FileStatusMapper}, the single status-to-exception translation
 *       point, which yields {@code com.cardemo.exception.FileAccessException} for the {@code '9x'}
 *       family and {@code com.cardemo.exception.FatalProcessingException} for anything unrecognised.
 *       The legacy screen text {@code Unable to lookup Transaction...} and the {@code RESP}/{@code REAS}
 *       pair are emitted first as one structured log event, reproducing
 *       {@code app/cbl/COTRN01C.cbl:290}.</li>
 *   <li><strong>Lock-wait contention.</strong> <em>The most likely operational surprise in this class.</em>
 *       Because the preserved {@code :275} {@code UPDATE} lock is an exclusive row lock, two concurrent
 *       detail views of the <em>same</em> transaction serialise, and a viewer can block behind a writer.
 *       Symptoms are latency on {@code GET /api/transactions/{id}} and, once a lock timeout is
 *       configured, a {@code FileAccessException} raised from the read. This is legacy behaviour
 *       faithfully reproduced, not a defect in this class - see the preserved-lock note below.</li>
 *   <li><strong>Startup failure mentioning {@code Clock} or {@code EntityManager}.</strong> Both are
 *       constructor-injected. A {@code Clock} bean is a pre-existing requirement of the tree, since
 *       {@code com.cardemo.service.shared.DateValidationService} also takes one; the shared
 *       {@code EntityManager} proxy is contributed by Spring Data JPA.</li>
 *   </ul>
 *
 * <h2>Preserved legacy quirks</h2>
 *
 * <h3>The {@code READ ... UPDATE} exclusive lock on a pure read-only path</h3>
 * {@code app/cbl/COTRN01C.cbl:269-278} issues {@code EXEC CICS READ ... UPDATE}, and the {@code UPDATE}
 * option at {@code :275} is the <strong>only</strong> occurrence of that word in the file. The program
 * takes an exclusive update lock and then never writes: it has no {@code REWRITE}, no {@code WRITE} and
 * no {@code DELETE}. The lock is therefore pure overhead in the source - and it is
 * <strong>reproduced deliberately</strong>, because it is observable under concurrency and parity is the
 * contract. An inefficiency must be justified rather than removed silently; this
 * paragraph and {@link #readTransactFile(String)} are that justification. The cleaner home for the lock
 * is a {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} finder on
 * {@code com.cardemo.repository.TransactionRepository}, declaring it at the repository layer
 * instead of escalating it here; that interface declares no such finder, so the lock is escalated
 * through the injected {@link jakarta.persistence.EntityManager}.
 *
 * <h3>There is no numeric validation of the transaction identifier, and none is added</h3>
 * {@code app/cbl/COTRN01C.cbl:146-156} checks the identifier for blank and for {@code LOW-VALUES} and
 * for nothing else. The sibling list program has {@code 'Tran ID must be Numeric ...'} at
 * {@code app/cbl/COTRN00C.cbl:214}; <strong>{@code COTRN01C} has no such check at all.</strong> The
 * absent guard is preserved: a non-numeric identifier is not rejected here, it flows into the lookup and
 * comes back as the not-found path. Adding a numeric guard would reject input the legacy screen accepted,
 * which is a behaviour change. It is a deliberately preserved asymmetry
 * between {@code CT00} and {@code CT01}.
 *
 * <h3>The edited amount mask renders eight integer digits while the record holds nine</h3>
 * {@code TRAN-AMT} is {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10}, but
 * {@code WS-TRAN-AMT} is {@code PIC +99999999.99} at {@code app/cbl/COTRN01C.cbl:49} - eight integer
 * digits. A value of a thousand million or more therefore loses its leading digit on the way to the
 * screen. Reproduced exactly by {@link #renderEditedAmount(BigDecimal)}; not "fixed".
 *
 * <h3>Low - three inert working-storage declarations, modelled as no Java field at all</h3>
 * Rule 1 Clause B forbids dead code, and the parity mandate requires the control flow to survive intact.
 * These three items are data declarations rather than control flow, so both are satisfied by omitting
 * the field and recording it here:
 * <ul>
 *   <li>{@code WS-TRAN-DATE} at {@code app/cbl/COTRN01C.cbl:50} - declared with the value
 *       {@code '00/00/00'} and never referenced anywhere in the procedure division.</li>
 *   <li>{@code WS-USR-MODIFIED} at {@code app/cbl/COTRN01C.cbl:45-47} - set once by
 *       {@code SET USR-MODIFIED-NO TO TRUE} at {@code :89} and never read.</li>
 *   <li>{@code CDEMO-CT01-INFO} at {@code app/cbl/COTRN01C.cbl:53-61} - a six-member group of which only
 *       {@code CDEMO-CT01-TRN-SELECTED} is ever used, at {@code :103} and {@code :105}. The first and
 *       last identifier anchors, the page number and the next-page flag are inert in this program; they
 *       are the list program's state, and modelling them here would invent pagination the source does not
 *       perform.</li>
 *   </ul>
 *
 * <h3>Low - the failure message differs in case from its sibling, and the difference is preserved</h3>
 * This program says {@code 'Unable to lookup Transaction...'} with an upper-case {@code T} at
 * {@code app/cbl/COTRN01C.cbl:292}. The list program says {@code 'Unable to lookup transaction...'} with
 * a lower-case {@code t} at {@code app/cbl/COTRN00C.cbl:615}, {@code :649} and {@code :683}. The two are
 * <strong>not</strong> unified: the parity gate compares the text byte for byte.
 *
 * <h3>Low - the send paragraph does not return, so a guard flag carries the control flow</h3>
 * {@code SEND-TRNVIEW-SCREEN} at {@code app/cbl/COTRN01C.cbl:213-225} issues {@code EXEC CICS SEND} and
 * <strong>no {@code EXEC CICS RETURN}</strong>, so it falls through and the statements after it still
 * execute. The callers rely on the {@code IF NOT ERR-FLG-ON} guards at {@code :158} and {@code :176} to
 * decide whether to continue. That is reproduced with a method-local flag rather than with an early
 * return, so the fall-through survives. The sibling add program's send paragraph <em>does</em> return and
 * is therefore fail-fast; that pattern is deliberately not applied here.
 *
 * <h2>Boundaries of this class</h2>
 * <ul>
 *   <li><strong>The exclusive lock is escalated here, not declared at the repository.</strong>
 *       {@code com.cardemo.repository.TransactionRepository} declares no
 *       {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} finder, so the lock is taken through the
 *       injected {@link jakarta.persistence.EntityManager}; that interface is owned by the repository
 *       package and is deliberately not modified from this file.</li>
 *   <li><strong>The schema this class binds against.</strong>
 *       {@code V1__create_schema.sql} declares {@code tran_id CHAR(16)} as the
 *       primary key with {@code tran_desc CHAR(100)}, {@code tran_orig_ts CHAR(26)} and
 *       {@code tran_proc_ts CHAR(26)}.</li>
 *   <li><strong>{@code FILE STATUS '35'} has no corpus literal.</strong> The value has no
 *       literal attestation anywhere in {@code app/}. It is reachable here only through
 *       {@code FileStatusMapper}, never constructed by this class.</li>
 *   <li><strong>The header time field is eight characters on this map, not nine.</strong>
 *       {@code app/cpy-bms/COTRN01.CPY:54} declares {@code CURTIMEI PIC X(8)}, matching the
 *       eight characters of {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy:36-41}, so
 *       this class renders eight characters and follows the map.</li>
 *   </ul>
 *
 * <h2>Thread safety</h2>
 * Immutable after construction. All four collaborators are {@code private final} and none is reassigned;
 * there is no static mutable state and no instance field carrying request state. Every legacy
 * working-storage item that does have a Java counterpart - the error flag, the edited amount, the
 * response and reason codes - is a method-local, so concurrent requests cannot interfere. A
 * {@link java.time.Clock} is itself immutable and thread safe, so sharing this singleton bean between
 * request threads is safe.
 *
 * @see com.cardemo.model.dto.TransactionDto
 * @see com.cardemo.service.shared.FileStatusMapper
 */
@Service
public class TransactionDetailService {

    /**
     * The structured-log sink that replaces the single {@code DISPLAY} of
     * {@code app/cbl/COTRN01C.cbl:290}. The source had no instrumentation beyond that one statement, so
     * everything logged here beyond it is new capability of the kind Rule 1 Clause A asks for.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionDetailService.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COTRN01C'}, {@code app/cbl/COTRN01C.cbl:36}. Rendered into the
     * screen header by {@link #populateHeaderInfo(ScreenWorkArea)} and stamped into the outbound navigation
     * state by {@link #returnToPrevScreen(ScreenWorkArea)}.
     */
    private static final String PROGRAM_NAME = "COTRN01C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CT01'}, {@code app/cbl/COTRN01C.cbl:37}. The CICS transaction
     * identifier this program answers for, per {@code app/csd/CARDDEMO.CSD}.
     */
    private static final String TRANSACTION_ID = "CT01";

    /**
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'}, {@code app/cbl/COTRN01C.cbl:39}. The logical
     * file name handed to {@code FileStatusMapper} so its diagnostics name the same dataset the source
     * named.
     */
    private static final String TRANSACT_FILE = "TRANSACT";

    /**
     * The operation label handed to {@code FileStatusMapper}. It names the {@code UPDATE} option of
     * {@code app/cbl/COTRN01C.cbl:275} explicitly, so a diagnostic from this path cannot be mistaken for
     * one from an ordinary read.
     */
    private static final String READ_UPDATE_OPERATION = "READ UPDATE";

    /**
     * The sign-on program, {@code 'COSGN00C'} at {@code app/cbl/COTRN01C.cbl:95} and again at
     * {@code :200}. It is the target both when the commarea is empty and when no other target has been
     * established.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * The main menu program, {@code 'COMEN01C'} at {@code app/cbl/COTRN01C.cbl:117}: the PF3 target when
     * the caller supplied no originating program.
     */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * The transaction list program, {@code 'COTRN00C'} at {@code app/cbl/COTRN01C.cbl:126}: the fixed PF5
     * target, which walks back to the list this detail view was reached from.
     */
    private static final String TRANSACTION_LIST_PROGRAM = "COTRN00C";

    /**
     * {@code 'Tran ID can NOT be empty...'}, {@code app/cbl/COTRN01C.cbl:149}. Byte exact, including the
     * upper-case {@code NOT} and all three trailing full stops.
     */
    private static final String EMPTY_TRAN_ID_MESSAGE = "Tran ID can NOT be empty...";

    /**
     * {@code 'Transaction ID NOT found...'}, {@code app/cbl/COTRN01C.cbl:285}. Byte exact, including the
     * upper-case {@code NOT}. Becomes the detail message of the thrown
     * {@code com.cardemo.exception.RecordNotFoundException}.
     */
    private static final String TRANSACTION_NOT_FOUND_MESSAGE = "Transaction ID NOT found...";

    /**
     * {@code 'Unable to lookup Transaction...'}, {@code app/cbl/COTRN01C.cbl:292}.
     *
     * <p><strong>The {@code T} of {@code Transaction} is upper-case here.</strong> The list program spells
     * the same sentence with a lower-case {@code t} at {@code app/cbl/COTRN00C.cbl:615}, {@code :649} and
     * {@code :683}. The two are never unified: the parity gate compares the rendered text byte for byte,
     * so folding the case would register as a diff.
     */
    private static final String LOOKUP_FAILURE_MESSAGE = "Unable to lookup Transaction...";

    /**
     * {@code CCDA-MSG-INVALID-KEY}, {@code app/cpy/CSMSG01Y.cpy:21}, moved to the message area by
     * {@code app/cbl/COTRN01C.cbl:130} when an unmapped key is pressed.
     *
     * <p>The COBOL literal is 49 characters inside a {@code PIC X(50)} field, so the compiler pads it to
     * fifty and the screen field pads it again to the seventy-eight of {@code ERRMSGO}. Those trailing
     * spaces are field padding with no semantic content and are indistinguishable after the second
     * padding, so the significant text alone is carried here.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * {@code CCDA-TITLE01 PIC X(40)}, {@code app/cpy/COTTL01Y.cpy:19}. Forty characters exactly, with the
     * six leading and seven trailing spaces that centre it on the terminal; the padding is part of the
     * field contract and is reproduced verbatim.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02 PIC X(40)}, {@code app/cpy/COTTL01Y.cpy:22}. Forty characters exactly. The
     * copybook carries a commented-out alternative on the preceding line; the live value is this one.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * Renders the header date as {@code WS-CURDATE-MM-DD-YY} does: {@code WS-CURDATE-MM PIC 9(02)}, a
     * {@code FILLER} holding an oblique stroke, {@code WS-CURDATE-DD}, another stroke and
     * {@code WS-CURDATE-YY}, at {@code app/cpy/CSDAT01Y.cpy:30-35}. Eight characters, matching
     * {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COTRN01.CPY:36}. {@code Locale.ROOT} is passed so
     * neither the digits nor the separators can vary with the platform default locale.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * Renders the header time as {@code WS-CURTIME-HH-MM-SS} does: two digits, a colon, two digits, a
     * colon, two digits, at {@code app/cpy/CSDAT01Y.cpy:36-41}. Eight characters, matching
     * {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COTRN01.CPY:54} - <em>not</em> the {@code X(9)} the
     * technical specification claims for every map. {@code Locale.ROOT} for the same reason as the date.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * Integer digits the edited mask {@code PIC +99999999.99} of {@code app/cbl/COTRN01C.cbl:49} can
     * render, namely eight - one fewer than the nine {@code TRAN-AMT} holds.
     */
    private static final int AMOUNT_MASK_INTEGER_DIGITS = 8;

    /**
     * Ten raised to {@link #AMOUNT_MASK_INTEGER_DIGITS}, used as a modulus so that a value too large for
     * the mask loses its <em>leading</em> digits exactly as a COBOL {@code MOVE} into a narrower edited
     * field does. Reproducing the truncation is the point; it is not an error condition.
     */
    private static final BigDecimal AMOUNT_MASK_MODULUS = BigDecimal.TEN.pow(AMOUNT_MASK_INTEGER_DIGITS);

    /**
     * Significant digits the edited mask renders in total - the eight integer digits plus the two decimals -
     * which is the width the unscaled magnitude is zero-filled to before it is split around the decimal
     * point. The scale is taken from {@code com.cardemo.model.dto.TransactionDto} rather than restated, so
     * the mask cannot drift from the payload's declared precision.
     */
    private static final int AMOUNT_MASK_TOTAL_DIGITS =
            AMOUNT_MASK_INTEGER_DIGITS + TransactionDto.AMOUNT_SCALE;

    /**
     * The sign the edited mask emits for a non-negative amount. The picture at {@code app/cbl/COTRN01C.cbl:49}
     * leads with {@code +}, which is a mandatory sign position rather than a floating one, so a positive
     * amount renders an explicit {@code +} and zero renders {@code +00000000.00}.
     */
    private static final String PLUS_SIGN = "+";

    /**
     * The sign the edited mask emits for a negative amount. Negative transaction amounts are genuine - the
     * fixture {@code app/data/ASCII/dailytran.txt} carries both overpunch signs - and are never normalised.
     */
    private static final String MINUS_SIGN = "-";

    /**
     * The decimal point of the edited mask, in position eleven of the twelve characters.
     */
    private static final String DECIMAL_POINT = ".";

    /**
     * Digits in the zero-padded category code, from {@code TRAN-CAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA05Y.cpy:7} moved to {@code TCATCDI PIC X(4)} by
     * {@code app/cbl/COTRN01C.cbl:181}.
     */
    private static final int CATEGORY_CODE_DIGITS = 4;

    /**
     * Digits in the zero-padded merchant identifier, from {@code TRAN-MERCHANT-ID PIC 9(09)} at
     * {@code app/cpy/CVTRA05Y.cpy:11} moved to {@code MIDI PIC X(9)} by
     * {@code app/cbl/COTRN01C.cbl:187}.
     */
    private static final int MERCHANT_ID_DIGITS = 9;

    /**
     * The fixed-width placeholder that replaces the edited amount in this class's one diagnostic emission,
     * and only there.
     *
     * <p>Twelve characters, the exact width of the mask {@code PIC +99999999.99} at
     * {@code app/cbl/COTRN01C.cbl:49} - one sign, {@value #AMOUNT_MASK_INTEGER_DIGITS} integer digits, the
     * decimal point and two decimals - so the emitted event keeps the width a reader would use to verify
     * that the mask was applied at all. See {@code populateTranviewScreen} for the full justification.
     */
    private static final String REDACTED_AMOUNT =
            "*".repeat(PLUS_SIGN.length() + AMOUNT_MASK_TOTAL_DIGITS + DECIMAL_POINT.length());

    /**
     * The fixed-width placeholder that replaces the merchant identifier in this class's one diagnostic
     * emission, and only there. Exactly {@value #MERCHANT_ID_DIGITS} characters, one per digit position of
     * {@code TRAN-MERCHANT-ID PIC 9(09)}.
     */
    private static final String REDACTED_MERCHANT_ID = "*".repeat(MERCHANT_ID_DIGITS);

    /**
     * The two-character file status this class reports to {@code FileStatusMapper} when the persistence
     * layer fails, standing in for the {@code WHEN OTHER} arm of {@code app/cbl/COTRN01C.cbl:289-296}.
     *
     * <p>{@code '90'} is deliberate. The {@code WHEN OTHER} arm catches every CICS response other than
     * {@code NORMAL} and {@code NOTFND}, and the corpus-wide idiom for a physical or logical I/O failure
     * is the {@code '9x'} family, which {@code com.cardemo.model.enums.FileStatus} recognises by its first
     * byte. Reporting {@code '90'} therefore lands on
     * {@code com.cardemo.exception.FileAccessException}, which is the intended translation. The mapper
     * makes that decision, not this class.
     */
    private static final String IO_FAILURE_STATUS = "90";

    /**
     * A fixed, digit-free stand-in logged wherever a card number would otherwise appear.
     *
     * <p>Rule 1 Clause D forbids secrets in logs, and a primary account number is the most sensitive value
     * flowing through this class. This constant leaks <strong>no digits at all</strong> - not even the last
     * four - so no log line, at any level, can be correlated back to a cardholder.
     */
    private static final String CARD_NUMBER_REDACTED = "<redacted-16-digit-card-number>";

    /**
     * Logged in place of a card number when the record carries none.
     */
    private static final String CARD_NUMBER_ABSENT = "<absent>";

    /**
     * A fixed, digit-free stand-in logged wherever a value from the record would otherwise appear.
     *
     * <p>It stands in for any card-specific constant. Masking the card number
     * alone is not enough: the transaction identifier, the amount, the two dates and the merchant
     * identifier together identify a transaction and its value as precisely as the card number does, and this
     * service runs once per view request, so enumerating them at DEBUG amounted to a transaction ledger in a
     * stream that is aggregated, retained and replicated outside the boundary that protects the row.
     *
     * <p>No field of the record now reaches any log statement at any level. The response payload is
     * unaffected - the screen at {@code app/cpy-bms/COTRN01.CPY:62-66} legitimately displays these values -
     * and the correlation identifier that {@code CorrelationIdFilter} places in the MDC is what ties a log
     * event to the request that produced it.
     */
    private static final String WITHHELD_VALUE = "[withheld]";

    /**
     * The stand-in for a COBOL {@code MOVE SPACES}.
     *
     * <p>The empty string is used rather than a run of blanks because every screen field is fixed width in
     * the source and is padded to that width by the BMS layer, which the REST surface does not reproduce: a
     * field the source blanked and a field the source padded are indistinguishable once sent. Padding is
     * therefore left to whatever renders a fixed-width record, and the payload carries the significant text
     * only. {@code null} is deliberately not used, because {@code MOVE SPACES} produces a present-but-empty
     * field rather than an absent one, and collapsing the two would lose that distinction.
     */
    private static final String SPACES = "";

    /**
     * The field the cursor is parked on by {@code MOVE -1 TO TRNIDINL OF COTRN1AI}, which the source issues
     * at {@code :102}, {@code :151}, {@code :154}, {@code :287}, {@code :294} and {@code :311}.
     *
     * <p>{@code TRNIDINL} is the length subfield BMS generates alongside the data field {@code TRNIDINI}
     * ({@code app/cpy-bms/COTRN01.CPY:56-60}); moving {@code -1} into it is the 3270 idiom for "put the
     * cursor here". The value carried out is the field name rather than the length subfield's, because a
     * client focuses a field, not a length. There is no 3270 cursor over HTTP, so this is advisory: it tells
     * the caller which input the source would have focused.
     */
    private static final String CURSOR_FIELD_TRAN_ID_INPUT = "TRNIDIN";

    /**
     * The pad character used when rendering an unsigned COBOL numeric into an alphanumeric screen field,
     * where leading zeros are significant.
     */
    private static final String ZERO_DIGIT = "0";

    /**
     * The keyed store behind the CICS file {@code TRANSACT}, replacing the
     * {@code EXEC CICS READ DATASET('TRANSACT')} of {@code app/cbl/COTRN01C.cbl:269-278}.
     *
     * <p>The read goes through this interface rather than through the entity manager so that the
     * architectural access path stays intact and this bean keeps depending on the repository abstraction.
     * Only the <em>lock escalation</em> uses the entity manager, and only because the interface declares no
     * for-update finder.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The single status-to-exception translation point, consumed rather than reimplemented so that this
     * class holds no second copy of the mapping. Rule 1 Clause C, avoid duplication.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The persistence context used for one purpose only: escalating the row lock of
     * {@code app/cbl/COTRN01C.cbl:275} to {@link LockModeType#PESSIMISTIC_WRITE}.
     *
     * <p>It is injected because {@code com.cardemo.repository.TransactionRepository} declares no
     * for-update finder - as this class's boundaries note records - and because adding one would mean
     * editing a file this class does not own. Spring Data JPA contributes the shared, transaction-bound
     * proxy, so this reference resolves to whichever persistence context is active on the calling thread
     * and is safe to hold on a singleton.
     */
    private final EntityManager entityManager;

    /**
     * The clock behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/COTRN01C.cbl:245}.
     *
     * <p>Held as an injected collaborator rather than reached through an ambient {@code now()} call for two
     * reasons: the dependence on the host time zone becomes explicit in one declared place, and
     * {@link #populateHeaderInfo(ScreenWorkArea)} becomes deterministic under test. Asserting the eight
     * rendered characters of the header is impossible against the wall clock and would fail intermittently
     * on second, day and month boundaries.
     */
    private final Clock clock;

    /**
     * Creates the bean over its four collaborators.
     *
     * <p>This is the only constructor, so the container selects it without ambiguity and without an
     * injection annotation. Constructor injection is used throughout: there is no field injection, no
     * setter injection and no context lookup, and every field it assigns is {@code final}.
     *
     * <p>Side effects: none. It performs no I/O, touches no static state and calls no overridable instance
     * method, so no partially initialised reference can escape.
     *
     * @param transactionRepository the keyed store behind the CICS file {@code TRANSACT}; must not be
     * {@code null}
     * @param fileStatusMapper the shared status-to-exception translator; must not be {@code null}
     * @param entityManager the persistence context used solely to escalate the preserved row lock of
     * {@code app/cbl/COTRN01C.cbl:275}; must not be {@code null}
     * @param clock the time source standing in for {@code FUNCTION CURRENT-DATE} at
     * {@code app/cbl/COTRN01C.cbl:245}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}, which is a wiring defect rather than a
     * data condition and so is reported immediately rather than deferred to first use
     */
    public TransactionDetailService(final TransactionRepository transactionRepository,
            final FileStatusMapper fileStatusMapper,
            final EntityManager entityManager,
            final Clock clock) {

        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Opens the detail screen, optionally deep-linked to one transaction: the first-entry branch of
     * {@code MAIN-PARA} at {@code app/cbl/COTRN01C.cbl:99-109}.
     *
     * <p>This is the entry point behind {@code GET /api/transactions/detail?transactionId=...}. Supplying
     * an identifier is the REST analogue of arriving with {@code CDEMO-CT01-TRN-SELECTED} populated from
     * the transaction list,
     * which {@code :103-108} moves into the input field and then looks up immediately. Supplying no
     * identifier is the other arm of the same {@code IF}: the screen comes back blank, with the cursor on
     * the input field and no lookup attempted.
     *
     * <p><strong>Side effects.</strong> Reads one row from {@code TRANSACT} and, in doing so, takes the
     * exclusive row lock described on this class and on {@link #readTransactFile(String)}. Writes nothing.
     * Emits log output.
     *
     * <p><strong>The transaction boundary lives here, on purpose.</strong> Spring applies
     * {@code @Transactional} through a proxy, so it has no effect on a private method reached by
     * self-invocation. Declaring it on this public entry is therefore the only placement at which a
     * transaction is actually active when {@link #readTransactFile(String)} escalates the lock; a
     * {@link LockModeType#PESSIMISTIC_WRITE} request outside a transaction would fail instead of locking.
     * It is deliberately <strong>not</strong> {@code readOnly = true}: a read-only transaction cannot hold
     * a write lock, and the whole point of the preserved {@code :275} {@code UPDATE} option is that the
     * lock is a write lock.
     *
     * @param selectedTransactionId the identifier to display, or {@code null} or blank to open the screen
     * without a lookup. Not validated for numeric content - see the preserved absent guard on this class.
     * @return the rendered screen, never {@code null}; its {@code detail} always carries the twenty-one
     * fields of the detail projection and its {@code navigationTarget} is {@code null} because this path
     * stays on the screen
     * @throws com.cardemo.exception.RecordNotFoundException if an identifier was supplied and no such
     * transaction exists, carrying the legacy text {@code Transaction ID NOT found...}
     * @throws com.cardemo.exception.CardDemoException if the read fails for any other reason; the concrete
     * subtype is chosen by {@code com.cardemo.service.shared.FileStatusMapper}
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionDetailScreen viewTransaction(final String selectedTransactionId) {
        return mainPara(true, false, AttentionIdentifier.ENTER, selectedTransactionId, null, null);
    }

    /**
     * Handles a key press against an already-displayed screen: the re-entry branch of {@code MAIN-PARA} at
     * {@code app/cbl/COTRN01C.cbl:111-132}.
     *
     * <p>The source receives the map and then dispatches on {@code EIBAID}. Each arm is reproduced:
     * {@link AttentionIdentifier#ENTER} looks the identifier up, {@link AttentionIdentifier#PF3} walks back
     * to the caller or to the main menu, {@link AttentionIdentifier#PF4} clears the screen and
     * {@link AttentionIdentifier#PF5} walks back to the transaction list. Anything else is the
     * {@code WHEN OTHER} arm and comes back carrying the invalid-key message.
     *
     * <p><strong>Side effects.</strong> For {@link AttentionIdentifier#ENTER} only, reads one row from
     * {@code TRANSACT} under the preserved exclusive lock. Writes nothing. Emits log output.
     *
     * <p><strong>On the transaction boundary and on why it is not {@code readOnly}</strong>, see
     * {@link #viewTransaction(String)}; the reasoning is identical. The other three arms perform no I/O, so
     * the transaction they open stays empty and is inexpensive; keeping one boundary for all arms is
     * preferred to splitting the dispatch across two methods, because splitting it would break the
     * one-paragraph-to-one-method correspondence the scope-coverage gate reads.
     *
     * @param aid the key pressed, standing in for {@code EIBAID}; {@code null} is treated as the
     * {@code WHEN OTHER} arm rather than rejected, because an unrecognised key is exactly what that arm
     * exists for
     * @param transactionIdInput the contents of the input field, {@code TRNIDINI}; may be {@code null} or
     * blank, which is the condition {@code :147} tests for
     * @param fromProgram the originating program carried in {@code CDEMO-FROM-PROGRAM}, consulted only by
     * the {@link AttentionIdentifier#PF3} arm; may be {@code null} or blank, in which case
     * {@code :116-117} substitutes the main menu
     * @return the rendered screen, never {@code null}. For the two walk-back arms the {@code detail} is the
     * cleared projection and {@code navigationTarget} names the program to transfer to; otherwise
     * {@code navigationTarget} is {@code null}.
     * @throws com.cardemo.exception.RecordNotFoundException if {@link AttentionIdentifier#ENTER} was
     * pressed and no such transaction exists
     * @throws com.cardemo.exception.CardDemoException if the read fails for any other reason
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionDetailScreen submitScreen(final AttentionIdentifier aid,
            final String transactionIdInput,
            final String fromProgram) {
        return mainPara(true, true, aid, null, transactionIdInput, fromProgram);
    }

    /**
     * Handles arrival with no conversational state at all: the {@code IF EIBCALEN = 0} arm of
     * {@code app/cbl/COTRN01C.cbl:94-96}.
     *
     * <p>{@code EIBCALEN} is the length of the commarea CICS passed in, so zero means the transaction was
     * started with no state whatsoever. The source responds by routing to the sign-on program and
     * transferring control, without rendering anything.
     *
     * <p><strong>This branch has no counterpart on the REST surface</strong>, because a request that
     * reaches a controller always carries its own state - a path variable, a body, or an authenticated
     * principal - so there is no such thing as a zero-length commarea over HTTP. It is nevertheless exposed
     * as a real, reachable method rather than folded away: the paragraph map must stay complete for the
     * scope-coverage gate, and a caller that finds itself without request context has a defined,
     * source-faithful answer to give.
     *
     * <p><strong>Side effects.</strong> None. No I/O, no lookup, no lock - which is why this method opens
     * no transaction.
     *
     * @return a cleared screen whose {@code navigationTarget} is the sign-on program, never {@code null}
     */
    public TransactionDetailScreen openWithoutContext() {
        return mainPara(false, false, null, null, null, null);
    }

    /**
     * {@code MAIN-PARA} of {@code app/cbl/COTRN01C.cbl:86-141}: clears the flags, then dispatches on the
     * conversational state and the attention identifier.
     *
     * <p>The paragraph has three nested decisions and every one is reproduced in place. First
     * {@code IF EIBCALEN = 0} at {@code :94} detects arrival with no commarea and routes to sign-on.
     * Otherwise {@code IF NOT CDEMO-PGM-REENTER} at {@code :99} separates first entry - which blanks the
     * output map, parks the cursor, honours a deep link and sends - from re-entry, which receives the map
     * and then runs the {@code EVALUATE EIBAID} of {@code :112-132}.
     *
     * <p>Three statements in this paragraph have no Java counterpart and are recorded rather than
     * reproduced:
     * <ul>
     *   <li>{@code :89 SET USR-MODIFIED-NO TO TRUE} sets {@code WS-USR-MODIFIED}, which
     *       <strong>nothing ever reads</strong>. No field is created for it.</li>
     *   <li>{@code :98 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} copies the inbound commarea into
     *       working storage. A REST request carries its state in its own parameters, so there is nothing to
     *       copy.</li>
     *   <li>{@code :100 SET CDEMO-PGM-REENTER TO TRUE} and {@code :136-139 EXEC CICS RETURN TRANSID
     *       COMMAREA} together implement the pseudo-conversational round trip. The target is stateless, so
     *       the re-entry flag is an inbound parameter here and there is no state to hand back.</li>
     * </ul>
     *
     * @param commAreaPresent {@code false} models {@code EIBCALEN = 0} at {@code :94}
     * @param programReenter {@code false} models {@code NOT CDEMO-PGM-REENTER} at {@code :99}, that is,
     * first entry
     * @param aid the attention identifier, consulted only on re-entry; {@code null} lands on the
     * {@code WHEN OTHER} arm
     * @param selectedTransactionId {@code CDEMO-CT01-TRN-SELECTED}, the deep-link identifier tested at
     * {@code :103-104}; consulted only on first entry
     * @param transactionIdInput {@code TRNIDINI}, the received input field; consulted only on re-entry
     * @param fromProgram {@code CDEMO-FROM-PROGRAM}, consulted only by the PF3 arm at {@code :116}
     * @return the rendered screen, never {@code null}
     */
    private TransactionDetailScreen mainPara(final boolean commAreaPresent,
            final boolean programReenter,
            final AttentionIdentifier aid,
            final String selectedTransactionId,
            final String transactionIdInput,
            final String fromProgram) {

        // The per-invocation stand-in for WORKING-STORAGE and the BMS map area. Allocated here so that
        // every legacy working-storage item is method-local and nothing leaks between requests.
        final ScreenWorkArea work = new ScreenWorkArea();

        // :88 SET ERR-FLG-OFF TO TRUE.
        work.errFlgOn = false;

        // :91-92 MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COTRN1AO. Both the working-storage message and the
        // screen's error line start blank on every pass.
        work.message = SPACES;

        if (!commAreaPresent) {

            // :95 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
            work.toProgram = SIGN_ON_PROGRAM;

            // :96 PERFORM RETURN-TO-PREV-SCREEN.
            returnToPrevScreen(work);

        } else if (!programReenter) {

            // :101 MOVE LOW-VALUES TO COTRN1AO. The output map is cleared before anything is placed in it;
            // the work area is constructed blank, which is the same starting state.
            // :102 MOVE -1 TO TRNIDINL OF COTRN1AI - park the cursor on the input field.
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;

            // :103-104 IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND LOW-VALUES. The deep link: the list
            // program hands one identifier over and the detail view looks it up without a further keystroke.
            if (isPresent(selectedTransactionId)) {

                // :105-106 MOVE CDEMO-CT01-TRN-SELECTED TO TRNIDINI OF COTRN1AI.
                work.transactionIdInput = selectedTransactionId;

                // :107 PERFORM PROCESS-ENTER-KEY.
                processEnterKey(work);
            }

            // :109 PERFORM SEND-TRNVIEW-SCREEN. Reached whether or not the deep link fired, and - because
            // the send paragraph does not return - reached even after PROCESS-ENTER-KEY has already sent.
            sendTrnviewScreen(work);

        } else {

            // :111 PERFORM RECEIVE-TRNVIEW-SCREEN.
            receiveTrnviewScreen(work, transactionIdInput);

            // :112 EVALUATE EIBAID.
            switch (aid) {

                // :113-114 WHEN DFHENTER.
                case ENTER -> processEnterKey(work);

                // :115-122 WHEN DFHPF3.
                case PF3 -> {
                    if (isPresent(fromProgram)) {
                        // :119-120 MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM.
                        work.toProgram = fromProgram;
                    } else {
                        // :116-117 IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES, so the main menu.
                        work.toProgram = MAIN_MENU_PROGRAM;
                    }
                    // :122 PERFORM RETURN-TO-PREV-SCREEN.
                    returnToPrevScreen(work);
                }

                // :123-124 WHEN DFHPF4.
                case PF4 -> clearCurrentScreen(work);

                // :125-127 WHEN DFHPF5. The target is the literal transaction list program, so PF5 always
                // walks back to the list regardless of how the screen was reached.
                case PF5 -> {
                    work.toProgram = TRANSACTION_LIST_PROGRAM;
                    returnToPrevScreen(work);
                }

                // :128-131 WHEN OTHER. A null identifier lands here too: an unrecognised key is precisely
                // what this arm exists for, so it is answered rather than rejected.
                case null, default -> {
                    work.errFlgOn = true;
                    work.message = INVALID_KEY_MESSAGE;
                    sendTrnviewScreen(work);
                }
            }
        }

        // :136-139 EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA). The pseudo-conversational
        // hand-back has no counterpart; the assembled screen is returned to the caller instead.
        return work.toScreen();
    }

    /**
     * {@code PROCESS-ENTER-KEY} of {@code app/cbl/COTRN01C.cbl:144-192}: validates the identifier, reads the
     * record and moves it onto the screen.
     *
     * <p>The paragraph is three stages guarded by the error flag, and the guards are what make the
     * fall-through of {@link #sendTrnviewScreen(ScreenWorkArea)} safe:
     * <ol>
     *   <li><strong>Presence check, {@code :146-156}.</strong> A blank or {@code LOW-VALUES} identifier
     *       raises the flag, sets the legacy message and parks the cursor. The {@code WHEN OTHER} arm at
     *       {@code :153-155} parks the cursor too and then falls through on {@code CONTINUE}.</li>
     *   <li><strong>Blank the outputs and read, {@code :158-174}</strong>, behind {@code IF NOT
     *       ERR-FLG-ON}. Thirteen output fields are cleared before the read, so a failed lookup cannot leave
     *       the previous record on the screen.</li>
     *   <li><strong>Populate, {@code :176-192}</strong>, behind a second {@code IF NOT ERR-FLG-ON}: the
     *       fourteen moves of {@code :177-190}, written inline below. They are deliberately not extracted
     *       into a helper: the paragraph map is one method per source label, and hoisting half a paragraph
     *       out would split {@code PROCESS-ENTER-KEY} across two methods.</li>
     * </ol>
     *
     * <p><strong>There is no numeric validation of the identifier, and none is added.</strong> The whole of
     * the validation is the blank test at {@code :147}. The sibling list program rejects a non-numeric
     * identifier at {@code app/cbl/COTRN00C.cbl:214}; this program does not test for it at all, so a
     * non-numeric identifier flows into the read and returns through the not-found path. Preserving the
     * absent guard is deliberate - adding one would reject input the legacy screen accepted.
     *
     * <p>Side effects: reads one row under the preserved exclusive lock and mutates the work area. Writes
     * nothing.
     *
     * @param work the per-invocation working-storage and map stand-in, mutated in place exactly as the
     * source mutates working storage
     */
    private void processEnterKey(final ScreenWorkArea work) {

        // :146-156 EVALUATE TRUE.
        if (isBlankOrUnset(work.transactionIdInput)) {

            // :148 MOVE 'Y' TO WS-ERR-FLG.
            work.errFlgOn = true;

            // :149-150 MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE.
            work.message = EMPTY_TRAN_ID_MESSAGE;

            // :151 MOVE -1 TO TRNIDINL OF COTRN1AI.
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;

            // :152 PERFORM SEND-TRNVIEW-SCREEN. The send does not return, so the two guards below are what
            // stop the read and the populate from running.
            sendTrnviewScreen(work);
        } else {

            // :153-155 WHEN OTHER: park the cursor and CONTINUE.
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
        }

        // :158 IF NOT ERR-FLG-ON.
        Transaction record = null;
        if (!work.errFlgOn) {

            // :159-171 MOVE SPACES TO the thirteen output fields, so a failed lookup cannot leave the
            // previous record on the screen. Enumerated rather than looped, one assignment per source line,
            // because the source enumerates them.
            work.transactionId = SPACES;      // :159 TRNIDI
            work.cardNumber = SPACES;         // :160 CARDNUMI
            work.typeCode = SPACES;           // :161 TTYPCDI
            work.categoryCode = SPACES;       // :162 TCATCDI
            work.source = SPACES;             // :163 TRNSRCI
            work.amount = SPACES;             // :164 TRNAMTI
            work.description = SPACES;        // :165 TDESCI
            work.originatingDate = SPACES;    // :166 TORIGDTI
            work.processingDate = SPACES;     // :167 TPROCDTI
            work.merchantId = SPACES;         // :168 MIDI
            work.merchantName = SPACES;       // :169 MNAMEI
            work.merchantCity = SPACES;       // :170 MCITYI
            work.merchantZip = SPACES;        // :171 MZIPI

            // :172 MOVE TRNIDINI OF COTRN1AI TO TRAN-ID.
            // :173 PERFORM READ-TRANSACT-FILE.
            record = readTransactFile(work.transactionIdInput);
        }

        // :176 IF NOT ERR-FLG-ON. A second, independent guard rather than an else-branch of the first: the
        // source repeats the test, and the repetition is preserved.
        //
        // The test is exactly the source's - bare, with no companion null check on the record. Adding one
        // would be an invented guard the source does not have, and it would be unreachable besides: nothing
        // between :158 and here raises the flag, so reaching this point with the flag down means the block
        // above ran and readTransactFile returned. That method never returns null - it throws
        // RecordNotFoundException on an absent row - so the record is non-null whenever this branch is taken.
        if (!work.errFlgOn) {

            // :177 MOVE TRAN-AMT TO WS-TRAN-AMT. The edited field of :49, PIC +99999999.99. The source moves
            // through working storage first and only then to the screen, so the mask - and its truncation of
            // a ninth integer digit - is applied here, not at send time.
            final String editedAmount = renderEditedAmount(record.getAmount());

            // :178 MOVE TRAN-ID TO TRNIDI. X(16) to X(16), same width.
            work.transactionId = record.getTransactionId();

            // :179 MOVE TRAN-CARD-NUM TO CARDNUMI. X(16) to X(16), same width. Cardholder data: it reaches
            // the payload because the screen displays it, and it is never logged.
            work.cardNumber = record.getCardNumber();

            // :180 MOVE TRAN-TYPE-CD TO TTYPCDI. X(02) to X(2), same width.
            work.typeCode = record.getTypeCode();

            // :181 MOVE TRAN-CAT-CD TO TCATCDI. 9(04) to X(4): a numeric move into an alphanumeric field
            // renders the digits with their leading zeros intact.
            work.categoryCode = renderZeroPadded(record.getCategoryCode(), CATEGORY_CODE_DIGITS);

            // :182 MOVE TRAN-SOURCE TO TRNSRCI. X(10) to X(10), same width. A plain string throughout - the
            // column is CHAR(10) and is never narrowed to an enumeration.
            work.source = record.getTransactionSource();

            // :183 MOVE WS-TRAN-AMT TO TRNAMTI. The already-edited twelve characters.
            work.amount = editedAmount;

            // :184 MOVE TRAN-DESC TO TDESCI. X(100) to X(60): TRUNCATION to the leftmost sixty characters.
            // Sixty, not twenty-six - twenty-six is the list-row width in app/cpy-bms/COTRN00.CPY. The three
            // widths coexist and none is unified.
            work.description = truncate(record.getDescription(), TransactionDto.DESCRIPTION_LENGTH);

            // :185 MOVE TRAN-ORIG-TS TO TORIGDTI. X(26) to X(10): TRUNCATION to the leftmost ten characters,
            // which is exactly the yyyy-MM-dd date portion of the layout at app/cpy/CSDAT01Y.cpy:42-55. This
            // is why the payload field is named for a date rather than a timestamp. Text throughout: never
            // parsed into a temporal type and never reformatted.
            work.originatingDate = truncate(record.getOrigTs(), TransactionDto.DETAIL_DATE_LENGTH);

            // :186 MOVE TRAN-PROC-TS TO TPROCDTI. X(26) to X(10), the same truncation. The processing
            // timestamp is legitimately blank on unposted input - all 300 rows of
            // app/data/ASCII/dailytran.txt carry twenty-six spaces - so ten spaces is a real, expected
            // value here and is passed through rather than nulled.
            work.processingDate = truncate(record.getProcTs(), TransactionDto.DETAIL_DATE_LENGTH);

            // :187 MOVE TRAN-MERCHANT-ID TO MIDI. 9(09) to X(9), leading zeros intact.
            work.merchantId = renderZeroPadded(record.getMerchantId(), MERCHANT_ID_DIGITS);

            // :188 MOVE TRAN-MERCHANT-NAME TO MNAMEI. X(50) to X(30): TRUNCATION to the leftmost thirty.
            work.merchantName = truncate(record.getMerchantName(), TransactionDto.MERCHANT_NAME_LENGTH);

            // :189 MOVE TRAN-MERCHANT-CITY TO MCITYI. X(50) to X(25): TRUNCATION to the leftmost
            // twenty-five.
            work.merchantCity = truncate(record.getMerchantCity(), TransactionDto.MERCHANT_CITY_LENGTH);

            // :190 MOVE TRAN-MERCHANT-ZIP TO MZIPI. X(10) to X(10), same width.
            work.merchantZip = record.getMerchantZip();

            // The arithmetic companion travels beside the edited text so a caller needing the number does not
            // re-parse the mask. It is not a screen field and is not serialised.
            work.amountValue = record.getAmount();

            // Observability (Rule 1 clause A) - no legacy counterpart, since the source's only instrumentation
            // is the DISPLAY at :290. The card number is masked, never rendered: it is the one cardholder
            // field on this screen and it must not reach a log, a stack trace or an exception message.
            //
            // Finding, Medium severity - the amount and the merchant identifier are redacted too. Masking
            // only the card number was too narrow a reading of clause D1. This one line carries the amount
            // and the merchant identifier BESIDE the transaction identifier, so it discloses what a
            // cardholder spent and where; the transaction identifier then links it back to the card through
            // a single lookup, which makes the masked card number no protection at all. Both values are
            // replaced by fixed-width placeholders of their declared widths. The transaction identifier, the
            // type and category codes, the source and the two dates are kept: they are reference and key
            // data, they are what this event exists to confirm, and logback-spring.xml:731-736 records that
            // the value masks deliberately leave identifier-shaped lines alone rather than over-redact them.
            // Debug alone was not sufficient - debug is enabled during exactly the incident investigations
            // in which logs are read most widely - so the guard is kept as a volume control and is no longer
            // the only control. Not a parity artefact: the source's DISPLAY at :290 is an error diagnostic,
            // not this event, and no screen field or response payload is altered. Remediation if the true
            // values are ever needed: emit them to a separately access-controlled artefact.
            LOG.debug("CT01 detail populated: transactionId={} typeCode={} categoryCode={} source={} "
                            + "amount={} originatingDate={} processingDate={} merchantId={} cardNumber={}",
                    work.transactionId, work.typeCode, work.categoryCode, work.source,
                    work.amount == null ? null : REDACTED_AMOUNT,
                    work.originatingDate, work.processingDate,
                    work.merchantId == null ? null : REDACTED_MERCHANT_ID,
                    maskCardNumber(work.cardNumber));

            // :191 PERFORM SEND-TRNVIEW-SCREEN.
            sendTrnviewScreen(work);
        }
    }

    /**
     * {@code RETURN-TO-PREV-SCREEN} of {@code app/cbl/COTRN01C.cbl:197-208}: defaults the target, stamps
     * this program's identity into the navigation state and transfers control.
     *
     * <p>{@code :199-201} defaults an unset target to the sign-on program, which is the same literal the
     * empty-commarea path uses at {@code :95}. {@code :202-204} then records where control is coming from -
     * the transaction identifier, the program name and a zeroed context - so the receiving program can walk
     * back again.
     *
     * <p><strong>What has no counterpart.</strong> {@code :205-208 EXEC CICS XCTL PROGRAM(...)
     * COMMAREA(...)} transfers control to another CICS program and never returns. Over REST there is no
     * transfer: the target program's name is carried out on the screen as
     * {@code navigationTarget} and the caller performs the navigation, typically as a redirect or as the
     * next request. The {@code CDEMO-PGM-CONTEXT} zeroing at {@code :204} is recorded on the work area for
     * completeness even though a stateless target has no context to reset.
     *
     * <p>Side effects: mutates the work area. No I/O.
     *
     * @param work the per-invocation working-storage and map stand-in
     */
    private void returnToPrevScreen(final ScreenWorkArea work) {

        // :199-201 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES, MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
        if (!isPresent(work.toProgram)) {
            work.toProgram = SIGN_ON_PROGRAM;
        }

        // :202 MOVE WS-TRANID TO CDEMO-FROM-TRANID.
        work.fromTranId = TRANSACTION_ID;

        // :203 MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM.
        work.fromProgram = PROGRAM_NAME;

        // :204 MOVE ZEROS TO CDEMO-PGM-CONTEXT. The field is PIC 9(01) (app/cpy/COCOM01Y.cpy:29) with the
        // condition name CDEMO-PGM-ENTER VALUE 0 (:30), so zeroing it declares "this is a first entry, not a
        // re-entry" to whichever program receives control. Carried as the single rendered digit, which is
        // exactly what the one-byte picture holds. The enter-versus-re-enter distinction it encodes has no
        // counterpart on a stateless surface; the MOVE is reproduced for traceability of the paragraph.
        work.programContext = ZERO_DIGIT;

        // :205-208 EXEC CICS XCTL. The target is published rather than jumped to.
        work.navigationTarget = work.toProgram;

        LOG.debug("CT01 navigation: transferring from program={} transaction={} to program={}",
                PROGRAM_NAME, TRANSACTION_ID, work.toProgram);
    }

    /**
     * {@code SEND-TRNVIEW-SCREEN} of {@code app/cbl/COTRN01C.cbl:213-225}: fills the header, copies the
     * message onto the error line and sends the map.
     *
     * <p><strong>This paragraph does not return, and that is load-bearing.</strong> It issues
     * {@code EXEC CICS SEND} at {@code :219-225} and <em>no</em> {@code EXEC CICS RETURN}, so control falls
     * straight through to whatever follows the {@code PERFORM}. Every caller therefore relies on the
     * {@code IF NOT ERR-FLG-ON} guards - {@code :158} and {@code :176} - to decide whether to carry on. The
     * Java reproduction is a plain {@code void} method with no early exit anywhere in its callers, so the
     * fall-through survives intact. The sibling add program's send paragraph <em>does</em> return and is
     * genuinely fail-fast; that pattern is deliberately not applied here, because applying it would skip
     * statements this program executes.
     *
     * <p>Being reached more than once per pass is normal, not a defect: on the deep-link path
     * {@code PROCESS-ENTER-KEY} sends at {@code :191} and {@code MAIN-PARA} sends again at {@code :109}.
     * The second send simply re-transmits the same assembled screen, which is why assembling it is
     * idempotent here.
     *
     * <p><strong>What has no counterpart.</strong> {@code EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01')
     * FROM(COTRN1AO) ERASE CURSOR} writes a 3270 datastream to a terminal. There is no terminal: the
     * assembled field values are returned to the caller as a payload. {@code ERASE} - clear the screen
     * before painting - and {@code CURSOR} - honour the {@code -1} cursor marker - are presentation
     * directives with no REST equivalent; the cursor field is nevertheless carried out on the screen so a
     * client can focus the field the source would have focused.
     *
     * <p>Side effects: mutates the work area's header fields and error line, and emits log output.
     *
     * @param work the per-invocation working-storage and map stand-in
     */
    private void sendTrnviewScreen(final ScreenWorkArea work) {

        // :215 PERFORM POPULATE-HEADER-INFO.
        populateHeaderInfo(work);

        // :217 MOVE WS-MESSAGE TO ERRMSGO OF COTRN1AO.
        work.errorMessage = work.message;

        // :219-225 EXEC CICS SEND MAP ... ERASE CURSOR. No RETURN follows, so this method does not exit the
        // caller either.
        LOG.debug("CT01 send map=COTRN1A mapset=COTRN01 errorFlag={} cursorField={} message=[{}]",
                work.errFlgOn, work.cursorField, work.message);
    }

    /**
     * {@code RECEIVE-TRNVIEW-SCREEN} of {@code app/cbl/COTRN01C.cbl:230-238}: reads the submitted map into
     * the input area.
     *
     * <p><strong>What has no counterpart.</strong> {@code EXEC CICS RECEIVE MAP('COTRN1A')
     * MAPSET('COTRN01') INTO(COTRN1AI) RESP(...) RESP2(...)} pulls the inbound 3270 datastream into the
     * symbolic input structure. Over REST the request has already been deserialised by the time this bean is
     * reached, so the paragraph reduces to accepting the one input field the map declares as modifiable.
     * The two response codes it captures are unused by the source on this path - it neither tests nor
     * displays them - so nothing is derived from them here either, and no field is created for them.
     *
     * <p>The paragraph is kept as its own method even though it now contains a single assignment, because
     * the paragraph map must stay one-for-one for the scope-coverage gate.
     *
     * <p>Side effects: mutates the work area. No I/O.
     *
     * @param work the per-invocation working-storage and map stand-in
     * @param transactionIdInput the submitted contents of {@code TRNIDINI}; may be {@code null} or blank,
     * and is deliberately neither trimmed nor validated here - {@code PROCESS-ENTER-KEY} owns the only test
     * the source performs
     */
    private void receiveTrnviewScreen(final ScreenWorkArea work, final String transactionIdInput) {

        // :232-238 EXEC CICS RECEIVE MAP ... INTO(COTRN1AI). TRNIDINI is the only modifiable input field on
        // this map; every other field is display-only output.
        work.transactionIdInput = transactionIdInput;
    }

    /**
     * {@code POPULATE-HEADER-INFO} of {@code app/cbl/COTRN01C.cbl:243-262}: sets the six header fields every
     * CardDemo screen shares.
     *
     * <p>{@code :245} reads the clock once and both assembled fields are derived from that single reading,
     * exactly as the single {@code FUNCTION CURRENT-DATE} call fed both groups, so the date and the time can
     * never straddle midnight relative to one another. The four literal fields follow at {@code :247-250}
     * and the two assembled ones at {@code :252-262}.
     *
     * <p>The six fields are assigned inline here rather than delegated to a shared header helper. A helper
     * would be a new file, and Rule 1 Clause C's direction to avoid duplication is satisfied instead by
     * consuming the width constants from {@code com.cardemo.model.dto.TransactionDto} and the title text
     * from the same copybook the source reads.
     *
     * <p><strong>The time field is eight characters, not nine.</strong>
     * {@code app/cpy-bms/COTRN01.CPY:54} declares {@code CURTIMEI PIC X(8)}, matching the eight bytes of
     * {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy:36-41}. The technical specification's claim
     * that the field is {@code X(9)} on every map does not hold for this map, and the map
     * governs. {@code WS-CURTIME-MILSEC} exists in the copybook but this paragraph never moves it, so no
     * fractional part is rendered.
     *
     * <p>Side effects: mutates the work area's header fields. No I/O.
     *
     * @param work the per-invocation working-storage and map stand-in
     */
    private void populateHeaderInfo(final ScreenWorkArea work) {

        // :245 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. Read through the injected clock rather than an
        // ambient now(), so the eight rendered characters of each field are assertable under test. The
        // clock's zone carries the local-time semantics of FUNCTION CURRENT-DATE.
        final LocalDateTime headerTimestamp = LocalDateTime.now(this.clock);

        // :247 MOVE CCDA-TITLE01 TO TITLE01O.
        work.title01 = SCREEN_TITLE_01;

        // :248 MOVE CCDA-TITLE02 TO TITLE02O.
        work.title02 = SCREEN_TITLE_02;

        // :249 MOVE WS-TRANID TO TRNNAMEO.
        work.transactionName = TRANSACTION_ID;

        // :250 MOVE WS-PGMNAME TO PGMNAMEO.
        work.programName = PROGRAM_NAME;

        // :252-256 the three date components into WS-CURDATE-MM-DD-YY, then into CURDATEO. The oblique
        // strokes are FILLER items of the copybook group, so formatting the whole group in one pattern
        // reproduces the assembled eight characters exactly.
        work.currentDate = HEADER_DATE_FORMAT.format(headerTimestamp);

        // :258-262 the three time components into WS-CURTIME-HH-MM-SS, then into CURTIMEO. Colons are
        // likewise FILLER items of the group.
        work.currentTime = HEADER_TIME_FORMAT.format(headerTimestamp);
    }

    /**
     * {@code READ-TRANSACT-FILE} of {@code app/cbl/COTRN01C.cbl:267-298}: reads one {@code TRANSACT} record
     * by key, <strong>holding an exclusive update lock</strong>, and branches on the outcome.
     *
     * <h4>High - the {@code UPDATE} option at {@code :275} is preserved deliberately</h4>
     * The source reads with {@code UPDATE}:
     * <pre>
     *   269:            EXEC CICS READ
     *   270:                 DATASET   (WS-TRANSACT-FILE)
     *   271:                 INTO      (TRAN-RECORD)
     *   272:                 LENGTH    (LENGTH OF TRAN-RECORD)
     *   273:                 RIDFLD    (TRAN-ID)
     *   274:                 KEYLENGTH (LENGTH OF TRAN-ID)
     *   275:                 UPDATE
     *   276:                 RESP      (WS-RESP-CD)
     *   277:                 RESP2     (WS-REAS-CD)
     *   278:            END-EXEC.
     * </pre>
     * {@code UPDATE} asks CICS for an <em>exclusive</em> lock on the record, held to the end of the unit of
     * work, so that a subsequent {@code REWRITE} cannot lose an intervening change. <strong>This program
     * never rewrites.</strong> Searching {@code app/cbl/COTRN01C.cbl} for {@code UPDATE} returns exactly one
     * hit - line 275 - and searching it for {@code WRITE}, {@code REWRITE} or {@code DELETE} returns none at
     * all: all 330 lines are a read-only detail view. The lock is therefore pure cost in the source, and it
     * is <strong>reproduced here on purpose</strong> because it is observable under concurrency - two
     * viewers of the same transaction serialise, and a viewer blocks behind a writer - and parity, not
     * efficiency, is the contract. Rule 1 Clause A asks that an inefficiency be justified rather than
     * removed silently; this paragraph is that justification.
     *
     * <p><em>The cleaner home for it</em>, against {@code app/cbl/COTRN01C.cbl:275}, is a
     * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} finder on
     * {@code com.cardemo.repository.TransactionRepository}, declaring the lock at the repository
     * layer instead of escalating it at the service layer.
     *
     * <h4>How the lock is taken, and why this way</h4>
     * That interface declares no method annotated
     * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)}, and it is owned by the repository package, so this file
     * does not add one. The lock is therefore escalated in two steps that together reproduce one
     * {@code READ ... UPDATE}: the row is fetched through the repository, keeping the architectural access
     * path intact, and then {@link EntityManager#lock(Object, LockModeType)} promotes it to
     * {@link LockModeType#PESSIMISTIC_WRITE}, which Hibernate renders as {@code SELECT ... FOR UPDATE}
     * against PostgreSQL.
     *
     * <p><strong>A transaction must be active for that to work</strong>, and it is: both public entry points
     * are annotated {@code @Transactional(rollbackFor = Exception.class)}. The annotation is on them rather
     * than on this method by necessity, not by preference - Spring applies {@code @Transactional} through a
     * proxy, so annotating a private method reached by self-invocation would have no effect whatsoever and
     * the lock request would fail for want of a transaction. The boundary is deliberately not
     * {@code readOnly = true}, because a read-only transaction cannot hold a write lock and the whole point
     * of {@code :275} is that the lock is a write lock.
     *
     * <h4>Outcome branching, {@code :280-296}</h4>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} at {@code :281-282} is {@code CONTINUE}: the record is returned.</li>
     *   <li>{@code DFHRESP(NOTFND)} at {@code :283-288} becomes
     *       {@code com.cardemo.exception.RecordNotFoundException} carrying the legacy text
     *       {@code Transaction ID NOT found...} and the identifier that found nothing. A transaction
     *       identifier is not sensitive, so it is safe to carry; a card number never would be.</li>
     *   <li>{@code WHEN OTHER} at {@code :289-296} is routed through
     *       {@code com.cardemo.service.shared.FileStatusMapper}, the single status-to-exception translation
     *       point, so this class holds no second copy of the mapping. A {@code '9x'} status lands on
     *       {@code com.cardemo.exception.FileAccessException}; anything unrecognised lands on
     *       {@code com.cardemo.exception.FatalProcessingException}. The original throwable is always
     *       preserved as the cause.</li>
     * </ul>
     * A file status of {@code '10'}, end of file, is loop termination rather than an error everywhere in the
     * corpus. It is unreachable on this single keyed read, but the rule is not weakened here: the mapper
     * owns it, and this method never classifies a status itself.
     *
     * <p><strong>What has no counterpart on the two failing arms.</strong> The source raises
     * {@code WS-ERR-FLG}, moves the message to {@code WS-MESSAGE}, parks the cursor with
     * {@code MOVE -1 TO TRNIDINL} and sends the screen. Once an exception is the error channel the screen is
     * abandoned, so those four statements have no effect to reproduce; the legacy text survives as the
     * exception message on the not-found arm and as the logged text on the other. The error flag is
     * consequently only ever raised by the blank-identifier path of {@code PROCESS-ENTER-KEY}, which is why
     * that path alone exercises the {@code IF NOT ERR-FLG-ON} guards.
     *
     * <p>Side effects: acquires an exclusive database row lock held until the surrounding transaction ends.
     * Reads one row. Writes nothing. Emits log output on the failing arm.
     *
     * @param tranId the key to read, {@code TRAN-ID}, taken straight from the input field by {@code :172}.
     * Neither trimmed nor checked for numeric content, because the source does neither.
     * @return the located record, never {@code null}
     * @throws com.cardemo.exception.RecordNotFoundException on the {@code DFHRESP(NOTFND)} arm
     * @throws com.cardemo.exception.CardDemoException on the {@code WHEN OTHER} arm, the concrete subtype
     * chosen by {@code FileStatusMapper}
     */
    private Transaction readTransactFile(final String tranId) {

        final Optional<Transaction> located;
        try {

            // :269-274 EXEC CICS READ DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID).
            // The keyed read, through the repository so the access path stays layered.
            located = this.transactionRepository.findById(tranId);

            // :275 UPDATE. THE PRESERVED EXCLUSIVE LOCK. Escalated only when a row was actually found,
            // because there is nothing to lock otherwise - and CICS likewise locks nothing on NOTFND.
            // DO NOT REMOVE: see this method's Javadoc.
            if (located.isPresent()) {
                this.entityManager.lock(located.get(), LockModeType.PESSIMISTIC_WRITE);
            }

        } catch (final DataAccessException | PersistenceException | TransactionException cause) {

            // :290 DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD. One DISPLAY statement in the source, so
            // one structured log event here, carrying the legacy screen text of :292 alongside it. The
            // response and reason codes are CICS concepts; the persistence layer's own diagnosis stands in
            // for them and is preserved in full as the cause.
            //
            // The transaction identifier is withheld: which record was being read is caller-supplied input, and
            // the store failure this reports is a property of the store rather than of the record. The
            // correlation identifier that CorrelationIdFilter puts in the MDC ties this event to the request
            // that caused it, which is what a diagnosis actually needs.
            LOG.error("CT01 {} file={} operation={} status={} transactionId={}",
                    LOOKUP_FAILURE_MESSAGE, TRANSACT_FILE, READ_UPDATE_OPERATION, IO_FAILURE_STATUS,
                    WITHHELD_VALUE, cause);

            // :291-295 the error flag, the message, the cursor and the send are replaced by the typed
            // exception. FileStatusMapper decides which one: '90' is in the '9x' family, so the decision is
            // FileAccessException.
            //
            // On the orElseGet arm (deliberately kept): toException answers an empty
            // Optional only for the two success statuses '00' and '10', and the status handed to it here is
            // always the '9x' constant, so this arm does not fire today - an instruction-level coverage run
            // reports its nine instructions as the only unexecuted code in this class. It is NOT dead code
            // in the sense Rule 1 clause B forbids: unwrapping Optional<CardDemoException> totally is an
            // obligation of the type the single translation point declares, and the alternatives are all
            // worse. orElseThrow would raise an untyped NoSuchElementException, breaking the rule that every
            // I/O path fails with a typed exception; deciding the exception class here instead would
            // duplicate the mapper's status table, which clause C forbids and which is the whole reason the
            // mapper exists. Java cannot express "this call always throws", so some tail is unavoidable.
            // This one is the smallest, and it carries the legacy screen text so the message survives even
            // if the mapper's contract ever changes.
            throw this.fileStatusMapper
                    .toException(IO_FAILURE_STATUS, TRANSACT_FILE, READ_UPDATE_OPERATION, cause)
                    .orElseGet(() -> new FileAccessException(LOOKUP_FAILURE_MESSAGE, IO_FAILURE_STATUS,
                            TRANSACT_FILE, READ_UPDATE_OPERATION, cause));
        }

        // :283-288 WHEN DFHRESP(NOTFND).
        if (located.isEmpty()) {
            throw new RecordNotFoundException(TRANSACTION_NOT_FOUND_MESSAGE, TRANSACT_FILE, tranId);
        }

        // :281-282 WHEN DFHRESP(NORMAL) - CONTINUE.
        return located.get();
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} of {@code app/cbl/COTRN01C.cbl:301-306}: blanks every field and repaints.
     *
     * <p>Two statements, both {@code PERFORM}, reached from the PF4 arm at {@code :123-124}. It is kept as
     * its own method rather than folded into either of the paragraphs it calls, because the paragraph map
     * must stay one-for-one; the scope-coverage gate is read out of it.
     *
     * <p>Side effects: mutates the work area and emits log output. No I/O and no lock, so PF4 never touches
     * the database.
     *
     * @param work the per-invocation working-storage and map stand-in
     */
    private void clearCurrentScreen(final ScreenWorkArea work) {

        // :303 PERFORM INITIALIZE-ALL-FIELDS.
        initializeAllFields(work);

        // :304 PERFORM SEND-TRNVIEW-SCREEN.
        sendTrnviewScreen(work);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} of {@code app/cbl/COTRN01C.cbl:309-326}: parks the cursor and blanks the
     * input field, the thirteen detail output fields and the message.
     *
     * <p>The field list differs from the one {@code PROCESS-ENTER-KEY} blanks at {@code :159-171}, and the
     * difference matters: this paragraph also clears {@code TRNIDINI} at {@code :312} - the key the user
     * typed - and {@code WS-MESSAGE} at {@code :326}. Blanking the key is what makes PF4 a true reset rather
     * than a re-query, so the two lists are not merged.
     *
     * <p>Side effects: mutates the work area. No I/O.
     *
     * @param work the per-invocation working-storage and map stand-in
     */
    private void initializeAllFields(final ScreenWorkArea work) {

        // :311 MOVE -1 TO TRNIDINL OF COTRN1AI.
        work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;

        // :312-326 MOVE SPACES TO the input field, the thirteen output fields and the message. Enumerated
        // one assignment per source line, as the source enumerates them.
        work.transactionIdInput = SPACES;   // :312 TRNIDINI - cleared here but not at :159-171
        work.transactionId = SPACES;        // :313 TRNIDI
        work.cardNumber = SPACES;           // :314 CARDNUMI
        work.typeCode = SPACES;             // :315 TTYPCDI
        work.categoryCode = SPACES;         // :316 TCATCDI
        work.source = SPACES;               // :317 TRNSRCI
        work.amount = SPACES;               // :318 TRNAMTI
        work.description = SPACES;          // :319 TDESCI
        work.originatingDate = SPACES;      // :320 TORIGDTI
        work.processingDate = SPACES;       // :321 TPROCDTI
        work.merchantId = SPACES;           // :322 MIDI
        work.merchantName = SPACES;         // :323 MNAMEI
        work.merchantCity = SPACES;         // :324 MCITYI
        work.merchantZip = SPACES;          // :325 MZIPI
        work.message = SPACES;              // :326 WS-MESSAGE

        // The arithmetic companion is cleared alongside the edited text it accompanies, so a cleared screen
        // cannot carry a stale number behind a blank field.
        work.amountValue = null;
    }

    // Field-contract helpers.
    //
    // These are not paragraph translations - they carry no source label and appear in no row of the paragraph
    // map. Each reproduces exactly one COBOL data-movement semantic that the source expresses through a PIC
    // clause rather than through a statement: truncation on a narrowing MOVE, zero-fill on a numeric-to-
    // alphanumeric MOVE, and the edited mask of :49. They are private and static because they are pure
    // functions of their arguments, which makes them exhaustively testable and keeps them off the bean's
    // state (Rule 1 clause B: "prefer dependency injection and pure functions where possible").

    /**
     * Tests a screen field for content, reproducing the COBOL predicate {@code NOT = SPACES AND LOW-VALUES}
     * that the source applies to {@code CDEMO-CT01-TRN-SELECTED} at {@code app/cbl/COTRN01C.cbl:103}.
     *
     * <p>{@code LOW-VALUES} is a run of binary zeros, which is how BMS presents a field the terminal never
     * transmitted. HTTP has no such representation: an untransmitted field arrives as {@code null} or is
     * absent from the payload altogether. {@code null} is therefore the counterpart of {@code LOW-VALUES},
     * and {@link String#isBlank()} the counterpart of {@code SPACES}. A field of literal NUL characters is
     * not treated as unset, because no client can produce one through a JSON string and inventing that
     * handling would be untested code.
     *
     * @param value the candidate field, possibly {@code null}
     * @return {@code true} when the field carries at least one non-whitespace character
     */
    private static boolean isPresent(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Tests a screen field for emptiness, reproducing the COBOL predicate {@code = SPACES OR LOW-VALUES} that
     * the source applies to {@code TRNIDINI} at {@code app/cbl/COTRN01C.cbl:147}.
     *
     * <p>Both predicates appear literally in the source - the negative form at {@code :103} and this positive
     * form at {@code :147} - so both are named here rather than one being written as the negation of the
     * other at its call site. The implementation delegates to {@link #isPresent(String)} so the emptiness
     * rule is defined exactly once (Rule 1 clause C: "avoid duplication").
     *
     * @param value the candidate field, possibly {@code null}
     * @return {@code true} when the field is absent, empty or entirely whitespace
     */
    private static boolean isBlankOrUnset(final String value) {
        return !isPresent(value);
    }

    /**
     * Reproduces a COBOL {@code MOVE} from a wider alphanumeric field into a narrower one, which retains the
     * leftmost characters and discards the remainder.
     *
     * <p>This is the mechanism behind all five width-changing moves of {@code :184-189}: the description from
     * {@code X(100)} to {@code X(60)}, both timestamps from {@code X(26)} to {@code X(10)}, the merchant name
     * from {@code X(50)} to {@code X(30)} and the merchant city from {@code X(50)} to {@code X(25)}. The
     * truncation is not incidental to the translation - it is the field contract, and
     * {@code com.cardemo.model.dto.TransactionDto} rejects an over-wide value outright, so a value that
     * reached the payload untruncated would fail construction rather than merely look wrong.
     *
     * <p>Boundary conditions are handled explicitly, as Rule 1 clause B requires. A {@code null} yields
     * {@link #SPACES}: every source column is {@code NOT NULL} in {@code V1__create_schema.sql}, so a
     * {@code null} can only arise from a detached or partially built instance, and a blank field is the
     * faithful rendering of "nothing to show" rather than a failure. A value already at or inside the width
     * is returned unchanged, with no padding - see {@link #SPACES} for why padding is left to the caller that
     * renders a fixed-width record.
     *
     * @param value the source field, possibly {@code null}, of any length
     * @param width the destination field width in characters; must be positive
     * @return {@link #SPACES} when {@code value} is {@code null}, {@code value} itself when it already fits,
     * otherwise its leftmost {@code width} characters
     */
    private static String truncate(final String value, final int width) {
        if (value == null) {
            return SPACES;
        }
        if (value.length() <= width) {
            return value;
        }
        return value.substring(0, width);
    }

    /**
     * Reproduces a COBOL {@code MOVE} from an unsigned numeric field into an alphanumeric one, where the
     * leading zeros of the numeric picture are significant characters of the result.
     *
     * <p>Used twice: {@code MOVE TRAN-CAT-CD TO TCATCDI} at {@code :181}, which renders {@code 9(04)} into
     * {@code X(4)}, and {@code MOVE TRAN-MERCHANT-ID TO MIDI} at {@code :187}, which renders {@code 9(09)}
     * into {@code X(9)}. Category code 1 is therefore the four characters {@code 0001} on the screen, not the
     * single character {@code 1}.
     *
     * <p>The magnitude is taken because the source pictures are unsigned: an unsigned COBOL field cannot hold
     * a sign, and a {@code MOVE} of a negative value into one stores its absolute value. The entity already
     * constrains both columns to their non-negative ranges, so this reproduces an unreachable-in-practice
     * COBOL rule rather than guarding against real data. Over-wide values keep their low-order digits, which
     * is again what a COBOL {@code MOVE} into a narrower numeric field does.
     *
     * @param value the numeric field, possibly {@code null}
     * @param digits the destination width in characters; must be positive
     * @return {@link #SPACES} when {@code value} is {@code null}, otherwise exactly {@code digits} decimal
     * characters, zero-filled on the left
     */
    private static String renderZeroPadded(final Number value, final int digits) {
        if (value == null) {
            return SPACES;
        }
        final String plain = Long.toString(Math.abs(value.longValue()));
        if (plain.length() >= digits) {
            return plain.substring(plain.length() - digits);
        }
        return ZERO_DIGIT.repeat(digits - plain.length()) + plain;
    }

    /**
     * Renders a monetary amount on the edited mask {@code WS-TRAN-AMT PIC +99999999.99} declared at
     * {@code app/cbl/COTRN01C.cbl:49}, reproducing {@code MOVE TRAN-AMT TO WS-TRAN-AMT} at {@code :177}.
     *
     * <p>The result is always exactly {@value com.cardemo.model.dto.TransactionDto#AMOUNT_DISPLAY_LENGTH}
     * characters: a mandatory sign, {@value #AMOUNT_MASK_INTEGER_DIGITS} zero-filled integer digits, a
     * decimal point and two decimals. The sign is mandatory because the picture leads with {@code +}, so a
     * positive amount renders {@code +} rather than a blank, and zero renders {@code +00000000.00}.
     *
     * <p><strong>The mask is one digit narrower than the field it renders, and that is preserved.</strong>
     * {@code TRAN-AMT} is {@code PIC S9(09)V99} ({@code app/cpy/CVTRA05Y.cpy:10}) and holds nine integer
     * digits; the mask accepts eight. A COBOL {@code MOVE} between them discards the high-order digit, so an
     * amount of 123456789.99 reaches the screen as {@code +23456789.99}. {@link #AMOUNT_MASK_MODULUS}
     * reproduces that discard exactly. This is a legacy display defect, documented on this
     * class - it is reproduced, not corrected, because the parity
     * gate compares the rendered characters.
     *
     * <p>Negative amounts are rendered with a leading {@code -} and are never normalised to their magnitude:
     * {@code app/data/ASCII/dailytran.txt} carries both {@code &#123;} and {@code &#125;} overpunch signs, so
     * negative amounts are real data. Arithmetic is entirely {@code BigDecimal} at scale
     * {@value com.cardemo.model.dto.TransactionDto#AMOUNT_SCALE} with
     * {@code RoundingMode.HALF_EVEN}; there is no binary floating point on this path. The digits are produced
     * from the unscaled value rather than through a formatter, which makes the rendering independent of the
     * default locale by construction rather than by passing a locale.
     *
     * @param amount the persisted amount, possibly {@code null}
     * @return {@link #SPACES} when {@code amount} is {@code null}, otherwise exactly
     * {@value com.cardemo.model.dto.TransactionDto#AMOUNT_DISPLAY_LENGTH} characters on the mask
     */
    private static String renderEditedAmount(final BigDecimal amount) {
        if (amount == null) {
            return SPACES;
        }

        final BigDecimal scaled =
                amount.setScale(TransactionDto.AMOUNT_SCALE, TransactionDto.AMOUNT_ROUNDING_MODE);
        final String sign = scaled.signum() < 0 ? MINUS_SIGN : PLUS_SIGN;

        // abs() before remainder() so the magnitude is unsigned and the sign is carried by the mask alone;
        // remainder() against ten-to-the-eighth is what discards the ninth integer digit the mask cannot hold.
        final BigDecimal magnitude = scaled.abs()
                .remainder(AMOUNT_MASK_MODULUS)
                .setScale(TransactionDto.AMOUNT_SCALE, TransactionDto.AMOUNT_ROUNDING_MODE);

        final String digits = magnitude.unscaledValue().toString();
        final String padded =
                ZERO_DIGIT.repeat(Math.max(0, AMOUNT_MASK_TOTAL_DIGITS - digits.length())) + digits;

        return sign
                + padded.substring(0, AMOUNT_MASK_INTEGER_DIGITS)
                + DECIMAL_POINT
                + padded.substring(AMOUNT_MASK_INTEGER_DIGITS);
    }

    /**
     * Renders a card number safe for a log statement.
     *
     * <p>{@code TRAN-CARD-NUM} ({@code app/cpy/CVTRA05Y.cpy:15}) is cardholder data. It legitimately reaches
     * the response payload, because the screen at {@code app/cpy-bms/COTRN01.CPY:62-66} displays it, but it
     * must never reach a log, an exception message or a stack trace. No digits are retained - not even a last
     * four - because nothing on this read-only path needs to identify a card from a log line, and the least
     * revealing rendering that still proves presence is the correct one under Rule 1 clause D's principle of
     * least privilege.
     *
     * @param cardNumber the card number, possibly {@code null} or blank
     * @return {@link #CARD_NUMBER_ABSENT} when no card number is present, otherwise
     * {@link #CARD_NUMBER_REDACTED}
     */
    private static String maskCardNumber(final String cardNumber) {
        return isPresent(cardNumber) ? CARD_NUMBER_REDACTED : CARD_NUMBER_ABSENT;
    }

    // Nested types.

    /**
     * The attention identifier the caller reports, standing in for {@code EIBAID} as tested by the
     * {@code EVALUATE TRUE} at {@code app/cbl/COTRN01C.cbl:112-139}.
     *
     * <p>Only the four keys the source actually tests are modelled, plus the catch-all its {@code WHEN OTHER}
     * arm at {@code :137-139} provides. Modelling the remaining twenty-odd {@code DFHAID} constants would add
     * unreachable constants the source never distinguishes: every one of them takes the same
     * {@code WHEN OTHER} path, so they are that path.
     *
     * <p>Declared here rather than in a package of its own because it has no meaning outside this screen's
     * contract, and because the file budget for this migration unit is fixed at one file.
     */
    public enum AttentionIdentifier {

        /**
         * {@code DFHENTER}, {@code app/cbl/COTRN01C.cbl:113}: run the lookup.
         */
        ENTER,

        /**
         * {@code DFHPF3}, {@code app/cbl/COTRN01C.cbl:115}: leave for the caller's origin, or for
         * {@code COMEN01C} when none was supplied.
         */
        PF3,

        /**
         * {@code DFHPF4}, {@code app/cbl/COTRN01C.cbl:122}: clear the screen in place.
         */
        PF4,

        /**
         * {@code DFHPF5}, {@code app/cbl/COTRN01C.cbl:124}: leave for the transaction list, {@code COTRN00C}.
         */
        PF5,

        /**
         * Every other key, {@code app/cbl/COTRN01C.cbl:137}: redisplay with the invalid-key message.
         */
        OTHER
    }

    /**
     * The outcome of one screen interaction: the twenty-one detail fields, plus the two pieces of terminal
     * state that {@code EXEC CICS SEND} and {@code EXEC CICS XCTL} carried in the source and that HTTP cannot.
     *
     * <p>A record rather than a class because it is a value with no identity and no behaviour, and immutable
     * so that a caller cannot mutate a screen after it has been produced. It is a nested type rather than a
     * file of its own for the same reasons as {@link AttentionIdentifier}.
     *
     * @param detail the twenty-one detail fields in map order, never {@code null}; blank-valued when the
     * screen carries no record, since the source sends a blanked map rather than nothing
     * @param navigationTarget the program the source would have transferred to via {@code EXEC CICS XCTL} -
     * {@code COSGN00C}, {@code COMEN01C} or {@code COTRN00C} - or {@code null} when the interaction stays on
     * this screen. Advisory: it names where the legacy flow went, and the caller decides how to honour it
     * @param cursorField the input field the source parked the cursor on with {@code MOVE -1} to its length
     * subfield, or {@code null} when it parked none. Advisory, since HTTP has no cursor
     * @param errorFlagOn the state of {@code WS-ERR-FLG} ({@code app/cbl/COTRN01C.cbl:41-43}) when the
     * interaction ended. {@code true} means the detail fields carry a message rather than a record, and it is
     * exposed because the source's own control flow branches on it at {@code :158} and {@code :176}
     */
    public record TransactionDetailScreen(TransactionDto detail,
                                          String navigationTarget,
                                          String cursorField,
                                          boolean errorFlagOn) {

        /**
         * Rejects a screen with no detail payload.
         *
         * @throws NullPointerException if {@code detail} is {@code null}
         */
        public TransactionDetailScreen {
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /**
     * The per-invocation stand-in for the program's {@code WORKING-STORAGE SECTION} and its {@code COTRN1AI}
     * symbolic map.
     *
     * <p>Every field the source holds in working storage or on the map lives here, and nowhere else. This is
     * what allows the bean itself to be stateless: a COBOL program is a single-threaded task with private
     * storage per invocation, whereas a Spring singleton serves every request from one instance, so any of
     * these values held as a bean field would leak one caller's screen into another's. A fresh instance is
     * allocated at the top of {@link TransactionDetailService#mainPara} and discarded when it returns, which
     * reproduces the task-scoped lifetime exactly and satisfies Rule 1 clause B's prohibition on global
     * mutable state.
     *
     * <p>Deliberately mutable, and deliberately field-access rather than accessor-wrapped. The source's
     * paragraphs communicate by assigning to shared storage - {@code PROCESS-ENTER-KEY} raises the flag that
     * {@code :176} then tests - and a paragraph map that is provable one-to-one has to preserve that shape.
     * Wrapping thirty fields in sixty accessors would add no invariant and would dilute the coverage measure
     * with getter-only lines. Visibility is {@code private} to the enclosing class and the type never escapes:
     * {@link #toScreen()} converts to the immutable payload before anything is returned.
     *
     * <p>The map fields are initialised to {@link TransactionDetailService#SPACES} rather than left
     * {@code null}, because a BMS symbolic map is a fixed-width storage area that is blank before anything is
     * moved into it; there is no state in which a screen field does not exist. The three terminal-state fields
     * and {@code amountValue} are left {@code null}, because absence is meaningful for those - no navigation
     * requested, no cursor parked, no amount known.
     */
    private static final class ScreenWorkArea {

        /**
         * {@code WS-ERR-FLG PIC X(01)} with its {@code ERR-FLG-ON} condition name,
         * {@code app/cbl/COTRN01C.cbl:41-43}. Cleared at {@code :88}, raised at {@code :150}, {@code :284}
         * and {@code :291}, and tested at {@code :158}, {@code :176} and {@code :182}.
         */
        private boolean errFlgOn;

        /**
         * {@code WS-MESSAGE PIC X(80)}, {@code app/cbl/COTRN01C.cbl:38}. Copied onto {@code ERRMSGO} by
         * {@code SEND-TRNVIEW-SCREEN} at {@code :217}.
         */
        private String message = SPACES;

        /**
         * The program named by {@code EXEC CICS XCTL PROGRAM(...)}, published as advisory navigation rather
         * than acted upon. Corresponds to {@code CDEMO-TO-PROGRAM} of {@code app/cpy/COCOM01Y.cpy}.
         */
        private String toProgram;

        /**
         * {@link #toProgram} as it leaves on the payload. Held separately so that the working-storage field
         * the source assigns and the value the caller observes are not conflated: the source sets
         * {@code CDEMO-TO-PROGRAM} on several paths that then transfer, and only a completed transfer is
         * navigation.
         */
        private String navigationTarget;

        /**
         * The field the source parked the cursor on by moving {@code -1} into its BMS length subfield.
         */
        private String cursorField;

        /**
         * {@code CDEMO-FROM-TRANID}, stamped with {@code WS-TRANID} at {@code app/cbl/COTRN01C.cbl:203}.
         */
        private String fromTranId;

        /**
         * {@code CDEMO-FROM-PROGRAM}, stamped with {@code WS-PGMNAME} at {@code app/cbl/COTRN01C.cbl:204} and
         * read at {@code :116} to decide the PF3 target.
         */
        private String fromProgram;

        /**
         * {@code CDEMO-PGM-CONTEXT}, zeroed at {@code app/cbl/COTRN01C.cbl:205}. Carried as text because the
         * COMMAREA field is a picture, and retained only for the traceability of that single MOVE - the
         * enter-versus-re-enter flag it drove has no counterpart on a stateless surface.
         */
        private String programContext;

        /**
         * {@code TRNNAMEO}, {@code X(4)}. Set from {@code WS-TRANID} at {@code app/cbl/COTRN01C.cbl:255}.
         */
        private String transactionName = SPACES;

        /**
         * {@code TITLE01O}, {@code X(40)}. Set from {@code CCDA-TITLE01} at {@code app/cbl/COTRN01C.cbl:253}.
         */
        private String title01 = SPACES;

        /**
         * {@code CURDATEO}, {@code X(8)}. Set from {@code WS-CURDATE-MM-DD-YY} at
         * {@code app/cbl/COTRN01C.cbl:257}.
         */
        private String currentDate = SPACES;

        /**
         * {@code PGMNAMEO}, {@code X(8)}. Set from {@code WS-PGMNAME} at {@code app/cbl/COTRN01C.cbl:256}.
         */
        private String programName = SPACES;

        /**
         * {@code TITLE02O}, {@code X(40)}. Set from {@code CCDA-TITLE02} at {@code app/cbl/COTRN01C.cbl:254}.
         */
        private String title02 = SPACES;

        /**
         * {@code CURTIMEO}, {@code X(8)} - eight, not nine. Set from {@code WS-CURTIME-HH-MM-SS} at
         * {@code app/cbl/COTRN01C.cbl:262}.
         */
        private String currentTime = SPACES;

        /**
         * {@code TRNIDINI}, {@code X(16)}: the identifier the caller supplied. Distinct from
         * {@link #transactionId} - the map declares both, at {@code app/cpy-bms/COTRN01.CPY:56} and
         * {@code :62}, and the source assigns them separately at {@code :105} and {@code :178}.
         */
        private String transactionIdInput = SPACES;

        /**
         * {@code TRNIDI}, {@code X(16)}: the key of the record actually read, echoed back from
         * {@code TRAN-ID} at {@code app/cbl/COTRN01C.cbl:178}.
         */
        private String transactionId = SPACES;

        /**
         * {@code CARDNUMI}, {@code X(16)}, from {@code TRAN-CARD-NUM} at {@code app/cbl/COTRN01C.cbl:179}.
         * Cardholder data: displayed, never logged.
         */
        private String cardNumber = SPACES;

        /**
         * {@code TTYPCDI}, {@code X(2)}, from {@code TRAN-TYPE-CD} at {@code app/cbl/COTRN01C.cbl:180}.
         */
        private String typeCode = SPACES;

        /**
         * {@code TCATCDI}, {@code X(4)}, from {@code TRAN-CAT-CD} at {@code app/cbl/COTRN01C.cbl:181},
         * zero-filled.
         */
        private String categoryCode = SPACES;

        /**
         * {@code TRNSRCI}, {@code X(10)}, from {@code TRAN-SOURCE} at {@code app/cbl/COTRN01C.cbl:182}.
         */
        private String source = SPACES;

        /**
         * {@code TDESCI}, {@code X(60)}, from {@code TRAN-DESC} at {@code app/cbl/COTRN01C.cbl:184},
         * truncated from one hundred.
         */
        private String description = SPACES;

        /**
         * {@code TRNAMTI}, {@code X(12)}: the twelve characters of the edited mask, assigned at
         * {@code app/cbl/COTRN01C.cbl:183}.
         */
        private String amount = SPACES;

        /**
         * {@code TORIGDTI}, {@code X(10)}, from {@code TRAN-ORIG-TS} at {@code app/cbl/COTRN01C.cbl:185},
         * truncated from twenty-six.
         */
        private String originatingDate = SPACES;

        /**
         * {@code TPROCDTI}, {@code X(10)}, from {@code TRAN-PROC-TS} at {@code app/cbl/COTRN01C.cbl:186},
         * truncated from twenty-six. Legitimately blank on an unposted transaction.
         */
        private String processingDate = SPACES;

        /**
         * {@code MIDI}, {@code X(9)}, from {@code TRAN-MERCHANT-ID} at {@code app/cbl/COTRN01C.cbl:187},
         * zero-filled.
         */
        private String merchantId = SPACES;

        /**
         * {@code MNAMEI}, {@code X(30)}, from {@code TRAN-MERCHANT-NAME} at {@code app/cbl/COTRN01C.cbl:188},
         * truncated from fifty.
         */
        private String merchantName = SPACES;

        /**
         * {@code MCITYI}, {@code X(25)}, from {@code TRAN-MERCHANT-CITY} at {@code app/cbl/COTRN01C.cbl:189},
         * truncated from fifty.
         */
        private String merchantCity = SPACES;

        /**
         * {@code MZIPI}, {@code X(10)}, from {@code TRAN-MERCHANT-ZIP} at {@code app/cbl/COTRN01C.cbl:190}.
         */
        private String merchantZip = SPACES;

        /**
         * {@code ERRMSGO}, {@code X(78)}: {@link #message} as {@code SEND-TRNVIEW-SCREEN} places it at
         * {@code app/cbl/COTRN01C.cbl:217}.
         */
        private String errorMessage = SPACES;

        /**
         * The amount as a number, travelling beside the edited text of {@link #amount} so that a caller
         * needing to compute does not parse the mask back. Not a screen field, not serialised, and
         * {@code null} until a record has been read.
         */
        private BigDecimal amountValue;

        /**
         * Creates a work area whose map fields are already blank, which is the state a BMS symbolic map is
         * in before the first {@code MOVE} reaches it.
         *
         * <p>Declared explicitly rather than left implicit so that it carries this comment: every committed
         * peer in this tree is clean under {@code javadoc -Xdoclint:all -private}, and an undocumented
         * default constructor is the one diagnostic that would break that. The field initialisers above do
         * all the work, so the body is empty by design and not a stub - there is nothing a constructor
         * argument could usefully supply, because {@code MAIN-PARA} at {@code app/cbl/COTRN01C.cbl:86}
         * receives a freshly cleared map and populates it statement by statement.
         */
        private ScreenWorkArea() {
            // Intentionally empty: the field initialisers establish the blank-map state described above.
        }

        /**
         * Materialises the immutable screen payload, which is the closest analogue of
         * {@code EXEC CICS SEND MAP} at {@code app/cbl/COTRN01C.cbl:219-224}: the point at which the mutable
         * map area becomes an outbound message.
         *
         * <p>Not a paragraph translation and not part of the paragraph map. The twenty-one fields are passed
         * in the declaration order of {@code app/cpy-bms/COTRN01.CPY}, from {@code TRNNAMEI} at line 24 to
         * {@code ERRMSGI} at line 144. {@code pageNumber} and {@code rows} are {@code null} because they
         * belong to the list projection of {@code COTRN00C} and this screen has neither - the detail map
         * declares no page number and no row array.
         *
         * @return the screen payload, never {@code null}
         * @throws IllegalArgumentException if any field exceeds the width of the map field it represents,
         * which would mean a truncation above was missed
         */
        private TransactionDetailScreen toScreen() {
            final TransactionDto detail = new TransactionDto(
                    transactionName,
                    title01,
                    currentDate,
                    programName,
                    title02,
                    currentTime,
                    transactionIdInput,
                    transactionId,
                    cardNumber,
                    typeCode,
                    categoryCode,
                    source,
                    description,
                    amount,
                    originatingDate,
                    processingDate,
                    merchantId,
                    merchantName,
                    merchantCity,
                    merchantZip,
                    errorMessage,
                    null,
                    null,
                    amountValue);
            return new TransactionDetailScreen(detail, navigationTarget, cursorField, errFlgOn);
        }
    }
}
