/*
 * ******************************************************************
 * Program     : TransactionListService.java
 * Application : CardDemo
 * Type        : Spring Service Bean
 * Function    : Paginated transaction list. Reproduces the CICS pseudo-
 *               conversational browse of COTRN00C (transaction CT00) as a
 *               stateless keyset-paged REST service, page size 10.
 * Source      : app/cbl/COTRN00C.cbl (699 lines, 16 own paragraph labels) @ 7756d89
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

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paginated transaction list service — the Java target of CICS transaction {@code CT00}.
 *
 * <h2>What it does</h2>
 *
 * <p>This bean reproduces {@code app/cbl/COTRN00C.cbl} — 699 lines, 16 paragraph labels — at
 * behavioural parity, as verified against traceability anchor commit {@code 7756d89}. The CSD
 * binds the transaction to the program at {@code app/csd/CARDDEMO.CSD:419-420}
 * ({@code DEFINE TRANSACTION(CT00)} / {@code PROGRAM(COTRN00C)}); the mapset {@code COTRN00} is
 * defined at {@code app/csd/CARDDEMO.CSD:145} and the program entry at {@code :257}.
 *
 * <p>Every applicable source paragraph maps to <strong>exactly one</strong> private method here,
 * one-for-one, with no consolidation and no splitting, so that the paragraph map is
 * mechanically provable against paragraph correspondence. Industry guidance against literal
 * transliteration is deliberately overridden: behavioural parity is the contract. The readability
 * cost is answered by the source-citing Javadoc on every method and by the traceability matrix,
 * not by restructuring. Where idiom can be honoured without touching control flow it is —
 * {@code BigDecimal} replaces packed decimal, dependency injection replaces static linkage, and
 * naming is idiomatic Java.
 *
 * <p>The legacy screen is a pseudo-conversational 3270 browse whose position lives in the
 * COMMAREA between turns. The Java rendering is <strong>stateless keyset pagination</strong>: the
 * caller round-trips the browse anchors as {@code TransactionListState} and receives them back as
 * response metadata. There is no HTTP session, no server-side cursor and no retained browse
 * position.
 *
 * <h2>How to build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B -ntp clean compile} and verify with
 * {@code ./mvnw -B -ntp clean verify}. The compiler runs {@code -Xlint:all -Werror} with
 * {@code failOnWarning}, so this file must be warning-clean; JaCoCo enforces an 80% line floor.
 * Unit tests live under {@code src/test/java/com/cardemo/unit/**} and inject a fixed
 * {@code java.time.Clock} so that the header date and time are deterministic. No test source
 * belongs in this package.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The page size is bound from <strong>{@code carddemo.pagination.transaction-list-page-size}</strong>,
 * whose value is {@code 10} in {@code src/main/resources/application.yml}. It is never a literal
 * in this class. The sibling keys in that family are {@code card-list-page-size} (7),
 * {@code user-list-page-size} (10) and the report page size (20); this service must never fall
 * back to any of them. Also consumed but never redeclared here:
 * {@code spring.jpa.hibernate.ddl-auto=validate} and {@code spring.jpa.open-in-view=false}.
 *
 * <p>The configured page size is a policy value; {@code TransactionDto.PAGE_SIZE} is the physical
 * slot count of the {@code COTRN00} symbolic map. The two are distinct concepts that happen to
 * coincide at ten, and the constructor rejects a configured size that will not fit the map.
 *
 * <p>Page-size evidence is the loop bounds and the ten-way dispatches, not a declared constant:
 * {@code app/cbl/COTRN00C.cbl:L290} ({@code UNTIL WS-IDX > 10}), {@code :L297}
 * ({@code UNTIL WS-IDX >= 11 ...}), {@code :L344}, {@code :L349} ({@code MOVE 10 TO WS-IDX}),
 * {@code :L351} ({@code UNTIL WS-IDX <= 0}), the ten-way {@code EVALUATE WS-IDX} at {@code :L390}
 * and {@code :L452}, and the ten row-field quintuples in {@code app/cpy-bms/COTRN00.CPY}.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>End of data is never an error. A CICS {@code DFHRESP(NOTFND)} on {@code STARTBR} and a
 * {@code DFHRESP(ENDFILE)} on {@code READNEXT}/{@code READPREV} terminate the fill loop and
 * produce an informational message without raising the error flag — see {@code :L605-L611},
 * {@code :L639-L645} and {@code :L673-L679}. Only a genuine I/O fault becomes an exception, and
 * it is routed through {@code com.cardemo.service.shared.FileStatusMapper}, the single
 * status-to-exception translation point, yielding an unchecked
 * {@code com.cardemo.exception.CardDemoException} subtype that always preserves the originating
 * failure as its cause. Nothing is swallowed and no exception type is declared, because the whole
 * hierarchy is unchecked.
 *
 * <p>Troubleshooting: an empty list together with the top-of-page message means the search key
 * sorted above every stored transaction id, or the table is empty; a non-numeric search key is
 * rejected before any query is issued; a stale forward anchor is expected behaviour, not a bug —
 * see the preserved defect below.
 *
 * <h2>Preserved legacy quirks — reproduced deliberately, never repaired</h2>
 *
 * <ul>
 *   <li><strong>The last-key anchor is written only at slot ten</strong>
 *       ({@code app/cbl/COTRN00C.cbl:L437-L439}). On a partial final page {@code WS-IDX} never
 *       reaches ten, so {@code CDEMO-CT00-TRNID-LAST} keeps the previous page's value, or spaces
 *       on the very first page, and a later forward page would anchor on a stale key. This class
 *       reproduces that exactly and does <em>not</em> derive the anchor from the actual final row.
 *       The tempting change - updating the anchor only when the page is full - is deliberately not
 *       applied, because the source does not do it.</li>
 *   <li><strong>The invalid-selection path falls through</strong>
 *       ({@code app/cbl/COTRN00C.cbl:L196-L203}). Both {@code SET TRANSACT-EOF TO TRUE} at
 *       {@code :L197} and {@code PERFORM SEND-TRNLST-SCREEN} at {@code :L202} are commented out in
 *       the source, so the path raises neither the error flag nor a send. It falls through into
 *       search-key handling and paging, and the message survives to the eventual send at the end
 *       of {@code PROCESS-PAGE-FORWARD}. No throw, no early return, no error state.</li>
 *   <li><strong>Medium — the {@code GTEQ} operand is commented out</strong> at
 *       {@code app/cbl/COTRN00C.cbl:L597}. This does <em>not</em> make the browse an equal-key
 *       browse. The program moves {@code LOW-VALUES} into the record-id field at {@code :L207}
 *       and {@code HIGH-VALUES} at {@code :L260}; neither is a real sixteen-character key, so an
 *       equal-key browse would return not-found on every first display and the list could never
 *       render. Greater-or-equal positioning is what the program's own idiom requires, and
 *       equal-key is documented as the default only for a direct ESDS browse, not a KSDS browse.
 *       The commented operand is therefore behaviourally inert, and the greater-or-equal reading
 *       agrees with the committed {@code TransactionRepository} contract.</li>
 *   <li><strong>Low — the backward path does not blank the search field.</strong> The forward path
 *       clears it at {@code :L325}; {@code PROCESS-PAGE-BACKWARD} has no counterpart statement.
 *       The asymmetry is preserved.</li>
 *   <li><strong>Low — slot initialisation never clears the row selector.</strong>
 *       {@code INITIALIZE-TRAN-DATA} ({@code :L450-L505}) blanks only the id, date, description and
 *       amount of each row. Because {@code app/cpy-bms/COTRN00.CPY:373} declares
 *       {@code 01 COTRN0AO REDEFINES COTRN0AI}, input and output share storage, so a received
 *       selector survives and is echoed back. This class echoes the selectors on every path.</li>
 *   <li><strong>Low — the amount mask is narrower than the stored field.</strong>
 *       {@code WS-TRAN-AMT} is {@code PIC +99999999.99} ({@code :L56}) — eight integer digits —
 *       while {@code TRAN-AMT} is {@code S9(09)V99} ({@code app/cpy/CVTRA05Y.cpy:10}), nine. The
 *       high-order digit is truncated by the legacy move and is truncated here too.</li>
 *   <li><strong>Low — three redundant no-ops.</strong> {@code CONTINUE} at {@code :L606},
 *       {@code :L640} and {@code :L674} is subsumed by Java control flow; no statement is emitted,
 *       and each is recorded here so the paragraph map stays provable.</li>
 *   <li><strong>Low — the page number is written to the input map.</strong> {@code :L324} and
 *       {@code :L373} move {@code CDEMO-CT00-PAGE-NUM} into {@code PAGENUMI}, narrowing
 *       {@code 9(08)} to {@code X(8)}. The eight-character rendering is preserved verbatim,
 *       including the all-zero value described below.</li>
 *   </ul>
 *
 * <h2>Constructs with no Java counterpart</h2>
 *
 * <p>{@code EIBAID} collapses into the explicit {@code AttentionIdentifier} argument, since a
 * stateless request carries no attention identifier. {@code EXEC CICS XCTL} has no server-side
 * counterpart: a navigation target is returned for the caller to act on. {@code SEND MAP} and
 * {@code RECEIVE MAP} have no counterpart — the projection is returned as a value and the request
 * is already deserialised. The cursor moves ({@code MOVE -1 TO TRNIDINL}) are reported as a cursor
 * field name rather than performed. {@code WS-SEND-ERASE-FLG} ({@code :L46-L48}) is a genuine
 * state transition in the source but drives only terminal repainting, so it is recorded and not
 * modelled. {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM},
 * {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT}, {@code CDEMO-LAST-MAP} and
 * {@code CDEMO-LAST-MAPSET} have no equivalent: routing is URL-based and every request is
 * evaluated fresh.
 *
 * <p>Two working-storage items are declared in the source but never referenced in its procedure
 * division — {@code WS-REC-COUNT} at {@code :L52} and {@code WS-PAGE-NUM} at {@code :L54}, the
 * live page counter being {@code CDEMO-CT00-PAGE-NUM} at {@code :L65}. No Java field is created
 * for either; they are recorded here instead.
 *
 * <h2>Deliberately unreachable arms</h2>
 *
 * <p>Nine statements in this class cannot execute under any request the public API accepts. Each is
 * retained on purpose and none is abandoned residue, so each is listed here with its justification.
 * Line coverage for this class is consequently bounded a little below one
 * hundred percent by design rather than by missing tests.
 *
 * <ul>
 *   <li>The out-of-range guards in {@code populateTranData} and {@code initializeTranData} reproduce
 *       the source's own {@code WHEN OTHER CONTINUE} arms at {@code :L443-L444} and
 *       {@code :L503-L504}. Those arms are equally unreachable in the source, because both loops
 *       bound the index to one through ten. Removing them would break the ten-way
 *       {@code EVALUATE WS-IDX} correspondence the paragraph map is proved against.</li>
 *   <li>The blank-target default in {@code returnToPrevScreen} reproduces {@code :L512-L514}. Every
 *       call site here sets the target first, so the condition is never true — the same category of
 *       preserved-but-inert guard as the {@code IF NEXT-PAGE-YES} at {@code :L361}, which PF7 also
 *       makes unconditionally true by setting the flag at {@code :L242}.</li>
 *   <li>The {@code default} arm of the attention-identifier dispatch guards against a constant being
 *       added to the enum without a matching branch. All five present constants have explicit
 *       branches.</li>
 *   <li>The fallback inside the browse-failure translation runs only if the shared mapper declines
 *       to classify the status. It never declines for the status this class passes, but the mapper
 *       returns an {@code Optional} and leaving that unhandled would breach the prohibition on
 *       swallowing or blindly unwrapping an error result.</li>
 *   <li>The null-amount and null-value returns in the amount renderer and the truncation helper are
 *       unreachable because both columns they read are {@code NOT NULL} and the entity enforces it
 *       in its constructor. They are retained because the requirement to handle empty and absent
 *       values explicitly applies to every helper, not only to those with a reachable null.</li>
 *   </ul>
 *
 * <h2>Boundaries and configuration this class relies on</h2>
 *
 * <ul>
 *   <li>Because {@code spring.jpa.hibernate.ddl-auto} is {@code validate}, a column mismatch fails
 *       context startup rather than silently
 *       migrating, so this class emits no DDL and assumes no schema beyond the entity mapping it
 *       consumes.</li>
 *   <li>The page size is bound by key,
 *       {@code carddemo.pagination.transaction-list-page-size}, so any profile overlay that
 *       redefines it is picked up without change. Every overlay must leave {@code ddl-auto} at
 *       {@code validate} and keep that key at ten.</li>
 *   <li>File status {@code '35'} has no literal attestation anywhere in the COBOL corpus; the status
 *       is handled by the shared mapper on the strength
 *       of the documented status taxonomy alone.</li>
 *   <li>The page size comes from the loop bounds, not from {@code :L65-L68}: {@code :L65} is
 *       {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}, the
 *       page <em>number</em>, and {@code :L66-L68} are the next-page flag and its condition names.
 *       The load-bearing locators are listed above.</li>
 *   <li>The header time field is eight characters on this mapset, not nine:
 *       {@code app/cpy-bms/COTRN00.CPY:54} declares {@code CURTIMEI PIC X(8)}, matching the
 *       eight-character {@code HH:MM:SS} group at {@code app/cpy/CSDAT01Y.cpy:36-41}.</li>
 *   </ul>
 *
 * @see TransactionListService#submitScreen
 */
@Service
public class TransactionListService {

    /** Structured log sink. Carries no card number, credential or other sensitive value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionListService.class);

    /** {@code WS-PGMNAME} — {@code app/cbl/COTRN00C.cbl:L36}. */
    private static final String PROGRAM_NAME = "COTRN00C";

    /** {@code WS-TRANID} — {@code app/cbl/COTRN00C.cbl:L37}. */
    private static final String TRANSACTION_ID = "CT00";

    /** {@code WS-TRANSACT-FILE} — {@code app/cbl/COTRN00C.cbl:L39}. */
    private static final String TRANSACT_FILE = "TRANSACT";

    /** Sign-on program, the {@code EIBCALEN = 0} target at {@code app/cbl/COTRN00C.cbl:L108}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** Main menu program, the PF3 target at {@code app/cbl/COTRN00C.cbl:L123}. */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /** Transaction detail program, the selection target at {@code app/cbl/COTRN00C.cbl:L188}. */
    private static final String TRANSACTION_DETAIL_PROGRAM = "COTRN01C";

    /** Invalid row selection — {@code app/cbl/COTRN00C.cbl:L199}. Byte-compared. */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /**
     * Non-numeric search key — {@code app/cbl/COTRN00C.cbl:L214}. Byte-compared, and note the
     * single space before the ellipsis, which is part of the literal.
     */
    private static final String MSG_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /** Backward paging refused — {@code app/cbl/COTRN00C.cbl:L248}. Byte-compared. */
    private static final String MSG_ALREADY_AT_TOP = "You are already at the top of the page...";

    /** Forward paging refused — {@code app/cbl/COTRN00C.cbl:L270}. Byte-compared. */
    private static final String MSG_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    /** Browse positioning found nothing — {@code app/cbl/COTRN00C.cbl:L608}. Byte-compared. */
    private static final String MSG_AT_TOP = "You are at the top of the page...";

    /** Forward end of data — {@code app/cbl/COTRN00C.cbl:L642}. Byte-compared. */
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /** Backward end of data — {@code app/cbl/COTRN00C.cbl:L676}. Byte-compared. */
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";

    /**
     * Browse failure — {@code app/cbl/COTRN00C.cbl:L615}, {@code :L649} and {@code :L683}.
     * Byte-compared, and note the lower-case {@code t} in "transaction". This literal must never
     * be unified with the upper-case {@code Transaction} spelling used by {@code COTRN01C:292};
     * the casing difference is real source behaviour.
     */
    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    /**
     * Invalid attention identifier — {@code CCDA-MSG-INVALID-KEY} declared as {@code PIC X(50)} at
     * {@code app/cpy/CSMSG01Y.cpy:20-21} and moved to the message area at
     * {@code app/cbl/COTRN00C.cbl:L131}. Byte-compared. The copybook pads the value to fifty
     * characters; the trailing padding is field width rather than content and is dropped, exactly
     * as the sibling {@code COTRN01C} service does. Unlike
     * {@link #MSG_TRAN_ID_NOT_NUMERIC} this literal has <em>no</em> space before its ellipsis.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** {@code CCDA-TITLE01} from {@code app/cpy/COTTL01Y.cpy}, forty characters. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} from {@code app/cpy/COTTL01Y.cpy}, forty characters. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * {@code WS-CURDATE-MM-DD-YY} — {@code app/cpy/CSDAT01Y.cpy:30-35}, eight characters, feeding
     * {@code CURDATEO} at {@code app/cbl/COTRN00C.cbl:L580}.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS} — {@code app/cpy/CSDAT01Y.cpy:36-41}, eight characters, feeding
     * {@code CURTIMEO} at {@code app/cbl/COTRN00C.cbl:L586}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** Integer-digit count of the {@code PIC +99999999.99} mask at {@code :L56}. */
    private static final int AMOUNT_MASK_INTEGER_DIGITS = 8;

    /** Modulus that truncates the high-order digit the narrower mask cannot hold. */
    private static final BigDecimal AMOUNT_MASK_MODULUS =
            BigDecimal.TEN.pow(AMOUNT_MASK_INTEGER_DIGITS);

    /** Total digit count rendered by the mask: eight integer digits plus two decimals. */
    private static final int AMOUNT_MASK_TOTAL_DIGITS =
            AMOUNT_MASK_INTEGER_DIGITS + TransactionDto.AMOUNT_SCALE;

    /** Offsets of the year within {@code WS-TIMESTAMP} — {@code MOVE ... DT-YYYY(3:2)}, {@code :L385}. */
    private static final int TIMESTAMP_YEAR_SUFFIX_BEGIN = 2;

    /** End offset of the two-digit year taken from the timestamp. */
    private static final int TIMESTAMP_YEAR_SUFFIX_END = 4;

    /** Offsets of {@code WS-TIMESTAMP-DT-MM} within the twenty-six-character timestamp. */
    private static final int TIMESTAMP_MONTH_BEGIN = 5;

    /** End offset of the timestamp month. */
    private static final int TIMESTAMP_MONTH_END = 7;

    /** Offsets of {@code WS-TIMESTAMP-DT-DD} within the twenty-six-character timestamp. */
    private static final int TIMESTAMP_DAY_BEGIN = 8;

    /** End offset of the timestamp day. */
    private static final int TIMESTAMP_DAY_END = 10;

    /** Minimum timestamp length the {@code MM/DD/YY} projection can read safely. */
    private static final int TIMESTAMP_MINIMUM_LENGTH = TIMESTAMP_DAY_END;

    /**
     * {@code WS-TRAN-DATE} initial value — {@code app/cbl/COTRN00C.cbl:L57}. Used when the stored
     * timestamp is blank or too short to project, which real fixture data contains.
     */
    private static final String UNSET_TRAN_DATE = "00/00/00";

    /** Value of {@code CDEMO-CT00-PAGE-NUM} immediately after the reset at {@code :L224}. */
    private static final int PAGE_NUMBER_RESET = 0;

    /**
     * The record-identification value standing in for {@code MOVE LOW-VALUES TO TRAN-ID} at
     * {@code app/cbl/COTRN00C.cbl:L207} and {@code :L239} — positioning at the start of the key
     * sequence. The empty string is blank-padded by {@code CHAR} semantics and so orders below every
     * stored sixteen-digit id. High values is not given a counterpart constant: a sentinel that
     * orders <em>above</em> every key would be collation-dependent, so positioning past the end is
     * modelled as an explicit flag instead.
     */
    private static final String LOW_VALUES_KEY = "";

    /** Representative {@code '9x'} family status for a genuine browse I/O fault. */
    private static final String IO_FAILURE_STATUS = "90";

    /** Cursor target for the search field, {@code MOVE -1 TO TRNIDINL}. */
    private static final String CURSOR_FIELD_TRAN_ID_INPUT = "TRNIDIN";

    /** Row selector accepted by the source, upper case — {@code app/cbl/COTRN00C.cbl:L186}. */
    private static final String SELECTOR_UPPER = "S";

    /** Row selector accepted by the source, lower case — {@code app/cbl/COTRN00C.cbl:L187}. */
    private static final String SELECTOR_LOWER = "s";

    /** Blank field value; the Java stand-in for COBOL {@code SPACES} in a variable-width string. */
    private static final String SPACES = "";

    /** Single zero digit, used for zero-padding rendered numerics. */
    private static final String ZERO_DIGIT = "0";

    /** Sign emitted by the edited amount mask for a non-negative value. */
    private static final String PLUS_SIGN = "+";

    /** Sign emitted by the edited amount mask for a negative value. */
    private static final String MINUS_SIGN = "-";

    /** Decimal point emitted by the edited amount mask. */
    private static final String DECIMAL_POINT = ".";

    /** Read-only browse over the transaction cluster, replacing the VSAM access verbs. */
    private final TransactionRepository transactionRepository;

    /** The single status-to-exception translation point for every I/O path. */
    private final FileStatusMapper fileStatusMapper;

    /** Injected time source so the screen header is deterministic and testable. */
    private final Clock clock;

    /** Rows per page, bound from configuration and validated against the map's slot count. */
    private final int pageSize;

    /**
     * Creates the service. Constructor injection only — there is no field or setter injection, no
     * service locator and no application-context lookup, and every collaborator is final.
     *
     * @param transactionRepository ordered keyset browse over the transaction cluster; must not be
     *     {@code null}
     * @param fileStatusMapper shared status-to-exception translator; must not be {@code null}
     * @param clock time source for the screen header; must not be {@code null}
     * @param pageSize rows per page, bound from
     *     {@code carddemo.pagination.transaction-list-page-size}; must be at least one and must
     *     not exceed the physical slot count of the {@code COTRN00} map
     * @throws NullPointerException if any collaborator is {@code null}
     * @throws IllegalArgumentException if the configured page size is below one or will not fit the
     *     symbolic map
     */
    public TransactionListService(
            final TransactionRepository transactionRepository,
            final FileStatusMapper fileStatusMapper,
            final Clock clock,
            @Value("${carddemo.pagination.transaction-list-page-size}") final int pageSize) {
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.fileStatusMapper =
                Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (pageSize < 1) {
            throw new IllegalArgumentException(
                    "carddemo.pagination.transaction-list-page-size must be at least 1 but was "
                            + pageSize);
        }
        if (pageSize > TransactionDto.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "carddemo.pagination.transaction-list-page-size must not exceed the "
                            + TransactionDto.PAGE_SIZE
                            + " row slots of the COTRN00 map but was "
                            + pageSize);
        }
        this.pageSize = pageSize;
    }

    /**
     * The attention identifiers this screen dispatches on, replacing {@code EIBAID}.
     *
     * <p>{@code EIBAID} itself has no counterpart in a stateless request: the terminal's attention
     * identifier is not transmitted, so the caller states its intent explicitly. The constants
     * mirror the {@code EVALUATE EIBAID} arms at {@code app/cbl/COTRN00C.cbl:L119-L134} exactly,
     * and {@code OTHER} preserves the {@code WHEN OTHER} arm at {@code :L127-L133}. Declared nested
     * because the file budget for this migration unit is fixed at one file.
     */
    public enum AttentionIdentifier {

        /** {@code DFHENTER} — {@code app/cbl/COTRN00C.cbl:L120}. Re-evaluates the search key. */
        ENTER,

        /** {@code DFHPF3} — {@code app/cbl/COTRN00C.cbl:L122}. Returns to the main menu. */
        PF3,

        /** {@code DFHPF7} — {@code app/cbl/COTRN00C.cbl:L125}. Pages backward. */
        PF7,

        /** {@code DFHPF8} — {@code app/cbl/COTRN00C.cbl:L128}. Pages forward. */
        PF8,

        /** Any other key — {@code app/cbl/COTRN00C.cbl:L130-L133}. Redisplays with a message. */
        OTHER
    }

    /**
     * The browse position carried between turns, replacing the pseudo-conversational COMMAREA.
     *
     * <p>This is the Java rendering of {@code 05 CDEMO-CT00-INFO} at
     * {@code app/cbl/COTRN00C.cbl:L62-L70} — program-local working storage appended after
     * {@code COPY COCOM01Y.} at {@code :L61}, <strong>not</strong> part of the COMMAREA.
     * {@code app/cpy/COCOM01Y.cpy} contains no page-number field and no next-page flag; any
     * attribution of pagination metadata to the COMMAREA is incorrect, and this class does not
     * repeat it.
     *
     * <p>Because the anchors are transaction ids rather than row offsets, paging is keyset-based:
     * the caller returns this value on the next request and the service positions from the anchor.
     * The page number it carries is the <strong>raw</strong> counter, never clamped, so that it can
     * re-enter the source's arithmetic unaltered.
     *
     * @param firstKey {@code CDEMO-CT00-TRNID-FIRST} ({@code :L63}) — the id in slot one of the
     *     page just displayed, or {@code null} when no page has been displayed
     * @param lastKey {@code CDEMO-CT00-TRNID-LAST} ({@code :L64}) — the id in slot ten, or
     *     {@code null}. Deliberately stale on a partial final page; see the class documentation
     * @param pageNumber {@code CDEMO-CT00-PAGE-NUM} ({@code :L65}), raw and unclamped
     * @param nextPageAvailable {@code CDEMO-CT00-NEXT-PAGE-FLG} ({@code :L66-L68}). Exposed as a
     *     boolean: the source's {@code 'Y'}/{@code 'N'} sentinel is a terminal representation and
     *     is not placed on the wire, and it deliberately differs from the {@code LOW-VALUES}
     *     sentinel a sibling program uses for the same concept
     */
    public record TransactionListState(
            String firstKey, String lastKey, int pageNumber, boolean nextPageAvailable) {

        /**
         * Returns the state of a screen that has not yet displayed a page, matching the initial
         * values at {@code app/cbl/COTRN00C.cbl:L63-L68}: blank anchors, page zero, and the
         * next-page flag off by virtue of its {@code VALUE 'N'} at {@code :L66}.
         *
         * @return a never-{@code null} initial browse position
         */
        public static TransactionListState initial() {
            return new TransactionListState(null, null, PAGE_NUMBER_RESET, false);
        }
    }

    /**
     * Everything one turn of the list screen produces.
     *
     * @param list the {@code COTRN00} screen projection — the six header fields, the page number
     *     rendered as eight characters, the search field, all ten positional row slots and the
     *     message line. This is the only place the raw page number escapes verbatim, including the
     *     all-zero value the source can genuinely display
     * @param page REST-facing page metadata: the materialised rows, the applied page size, the
     *     next-page indicator and the keyset anchors. Its page number is clamped to
     *     {@code PageResponse.FIRST_PAGE_NUMBER} because that type forbids a page number below
     *     one; the unclamped value remains available on {@code list} and on {@code state}
     * @param state the raw browse position to return on the next request
     * @param navigationTarget the legacy program a transfer of control would have branched to, or
     *     {@code null} when the screen is redisplayed. {@code EXEC CICS XCTL} has no server-side
     *     counterpart, so the target is reported rather than performed
     * @param selectedTransactionId the id chosen by a row selector, or {@code null}. The caller
     *     fetches the detail itself; this service performs no transfer
     * @param cursorField the field the legacy program placed the cursor on, or {@code null}.
     *     Cursor positioning has no counterpart and is reported only
     * @param errorFlagOn {@code WS-ERR-FLG} ({@code app/cbl/COTRN00C.cbl:L40}) as it stood when the
     *     turn ended
     */
    public record TransactionListScreen(
            TransactionDto list,
            PageResponse<TransactionDto.TransactionListRow> page,
            TransactionListState state,
            String navigationTarget,
            String selectedTransactionId,
            String cursorField,
            boolean errorFlagOn) {

        /**
         * Validates that the projection and metadata are present.
         *
         * @throws NullPointerException if {@code list}, {@code page} or {@code state} is
         *     {@code null}
         */
        public TransactionListScreen {
            Objects.requireNonNull(list, "list must not be null");
            Objects.requireNonNull(page, "page must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    /**
     * Handles arrival with no communication area — {@code app/cbl/COTRN00C.cbl:L107-L109}.
     *
     * <p>When {@code EIBCALEN = 0} the legacy program cannot know who is signed on, so it targets
     * the sign-on program and transfers control. No browse is performed and no row is read.
     *
     * @return a screen carrying the sign-on navigation target and an empty first page; never
     *     {@code null}
     */
    @Transactional(readOnly = true)
    public TransactionListScreen openWithoutContext() {
        return mainPara(
                false, false, AttentionIdentifier.ENTER, null, List.of(), List.of(),
                TransactionListState.initial());
    }

    /**
     * Renders the first page — the {@code NOT CDEMO-PGM-REENTER} arm at
     * {@code app/cbl/COTRN00C.cbl:L112-L116}.
     *
     * <p>The legacy program clears the map, runs the enter-key path and sends the screen. With no
     * received input the search key is blank, so positioning starts at the beginning of the key
     * sequence per {@code :L206-L207}, and the page number is reset then incremented to one.
     *
     * @return the first page of transactions; never {@code null}
     * @throws CardDemoException if the browse fails for any reason other than end of data
     */
    @Transactional(readOnly = true)
    public TransactionListScreen openList() {
        return mainPara(
                true, false, AttentionIdentifier.ENTER, null, List.of(), List.of(),
                TransactionListState.initial());
    }

    /**
     * Processes one submitted turn — the {@code CDEMO-PGM-REENTER} arm and the
     * {@code EVALUATE EIBAID} dispatch at {@code app/cbl/COTRN00C.cbl:L118-L134}.
     *
     * <p>Side effects: none. This is a read-only path; nothing is written, enqueued or published.
     *
     * <p>Failure modes: a non-numeric, non-blank search key is rejected before any query is issued
     * and comes back as a redisplayed screen carrying the numeric-key message with the error flag
     * raised — it is not an exception, because the source treats it as a screen outcome. End of
     * data is likewise not a failure. A genuine I/O fault is translated by the shared status mapper
     * into an unchecked {@code com.cardemo.exception.CardDemoException} subtype that preserves the
     * originating failure as its cause.
     *
     * @param attentionIdentifier which key was pressed; must not be {@code null}
     * @param searchTransactionId {@code TRNIDINI} ({@code app/cpy-bms/COTRN00.CPY:66}), the
     *     sixteen-character search key. Blank or {@code null} starts from the beginning of the key
     *     sequence
     * @param selectionFlags the ten row selectors {@code SEL0001I}..{@code SEL0010I} in positional
     *     order; may be shorter than ten or {@code null}, in which case the missing entries are
     *     treated as blank. Order is significant and is scanned one through ten
     * @param displayedTransactionIds the ten row ids {@code TRNID01I}..{@code TRNID10I} currently
     *     on the screen, in positional order, used to resolve a selector to an id exactly as
     *     {@code :L148-L182} does; may be shorter than ten or {@code null}
     * @param state the browse position returned by the previous turn; {@code null} is treated as
     *     the initial position
     * @return the resulting screen; never {@code null}
     * @throws NullPointerException if {@code attentionIdentifier} is {@code null}
     * @throws CardDemoException if the browse fails for any reason other than end of data
     */
    @Transactional(readOnly = true)
    public TransactionListScreen submitScreen(
            final AttentionIdentifier attentionIdentifier,
            final String searchTransactionId,
            final List<String> selectionFlags,
            final List<String> displayedTransactionIds,
            final TransactionListState state) {
        Objects.requireNonNull(attentionIdentifier, "attentionIdentifier must not be null");
        return mainPara(
                true,
                true,
                attentionIdentifier,
                searchTransactionId,
                selectionFlags == null ? List.of() : selectionFlags,
                displayedTransactionIds == null ? List.of() : displayedTransactionIds,
                state == null ? TransactionListState.initial() : state);
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code MAIN-PARA} ({@code :L95-L141}).
     *
     * <p>Initialises the flags at {@code :L97-L100}, blanks the message areas at {@code :L102-L103}
     * and positions the cursor at {@code :L105}. When no communication area is present
     * ({@code :L107}) it targets the sign-on program and transfers control. Otherwise it restores
     * the carried state at {@code :L111} and either renders a first display ({@code :L112-L116}) or
     * receives the map and dispatches on the attention identifier ({@code :L118-L134}).
     *
     * <p>The restore at {@code :L111} deliberately runs <em>after</em> the {@code SET NEXT-PAGE-NO}
     * at {@code :L99}, so the carried next-page flag overwrites that initialisation. This ordering
     * is load-bearing: {@code CDEMO-CT00-INFO} is an appended {@code 05} group under
     * {@code 01 CARDDEMO-COMMAREA} ({@code app/cpy/COCOM01Y.cpy:19}), so it round-trips through the
     * communication area, and were the reset to win, forward paging could never fire.
     *
     * <p>{@code EXEC CICS RETURN ... COMMAREA} at {@code :L138-L141} has no counterpart; the state
     * it would have saved is returned to the caller instead.
     *
     * @param contextPresent whether a communication area was supplied, i.e. {@code EIBCALEN != 0}
     * @param reenter {@code CDEMO-PGM-REENTER} as tested at {@code :L112}
     * @param attentionIdentifier the key pressed, replacing {@code EIBAID}
     * @param searchTransactionId the received search key, or {@code null}
     * @param selectionFlags the received row selectors in positional order
     * @param displayedTransactionIds the row ids currently displayed, in positional order
     * @param state the carried browse position
     * @return the completed screen
     */
    private TransactionListScreen mainPara(
            final boolean contextPresent,
            final boolean reenter,
            final AttentionIdentifier attentionIdentifier,
            final String searchTransactionId,
            final List<String> selectionFlags,
            final List<String> displayedTransactionIds,
            final TransactionListState state) {

        final ScreenWorkArea work = new ScreenWorkArea();

        work.errFlagOn = false;
        work.transactEof = false;
        work.nextPageYes = false;

        work.message = SPACES;
        work.errMsg = SPACES;

        work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;

        if (!contextPresent) {
            work.navigationTarget = SIGN_ON_PROGRAM;
            returnToPrevScreen(work);
            return toScreen(work);
        }

        work.trnIdFirst = state.firstKey();
        work.trnIdLast = state.lastKey();
        work.pageNum = state.pageNumber();
        work.nextPageYes = state.nextPageAvailable();
        work.attentionIdentifier = attentionIdentifier;

        if (!reenter) {
            work.clearMap();
            processEnterKey(work);
            sendTrnlstScreen(work);
            return toScreen(work);
        }

        receiveTrnlstScreen(work, searchTransactionId, selectionFlags, displayedTransactionIds);

        switch (attentionIdentifier) {
            case ENTER -> processEnterKey(work);
            case PF3 -> {
                work.navigationTarget = MAIN_MENU_PROGRAM;
                returnToPrevScreen(work);
            }
            case PF7 -> processPf7Key(work);
            case PF8 -> processPf8Key(work);
            case OTHER -> {
                work.errFlagOn = true;
                work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
                work.message = MSG_INVALID_KEY;
                sendTrnlstScreen(work);
            }
            default -> throw new IllegalStateException(
                    "Unhandled attention identifier: " + attentionIdentifier);
        }

        return toScreen(work);
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code PROCESS-ENTER-KEY} ({@code :L146-L229}).
     *
     * <p>Three stages, in order. First the ten-way selector scan at {@code :L148-L182}: slots are
     * tested in strict positional order one through ten and the first non-blank, non-low-values
     * selector wins, carrying its row's id with it; the {@code WHEN OTHER} arm at {@code :L179-L181}
     * blanks both. Second the selection dispatch at {@code :L183-L204}. Third the search-key
     * handling, page reset and forward page at {@code :L206-L229}.
     *
     * <p><strong>Preserved quirk, Medium.</strong> On the {@code WHEN OTHER} arm at
     * {@code :L196-L203} both {@code SET TRANSACT-EOF TO TRUE} ({@code :L197}) and
     * {@code PERFORM SEND-TRNLST-SCREEN} ({@code :L202}) are commented out in the source. The
     * invalid-selection path therefore raises no error flag and issues no send: it falls through
     * into the search-key handling below, and its message survives to be shown by the eventual send
     * at the end of {@code PROCESS-PAGE-FORWARD}. Reproduced exactly — no throw, no early return,
     * no error state.
     *
     * <p>{@code :L224} resets the page number to zero on every enter, and the forward path then
     * increments it to one. The reset is reproduced verbatim because the zero is observable when the
     * browse finds nothing.
     *
     * @param work the per-invocation work area
     */
    private void processEnterKey(final ScreenWorkArea work) {

        work.trnSelFlg = SPACES;
        work.trnSelected = SPACES;
        for (int slot = 0; slot < TransactionDto.PAGE_SIZE; slot++) {
            if (isPresent(work.sel[slot])) {
                work.trnSelFlg = work.sel[slot];
                work.trnSelected = work.rowTranId[slot];
                break;
            }
        }

        if (isPresent(work.trnSelFlg) && isPresent(work.trnSelected)) {
            if (SELECTOR_UPPER.equals(work.trnSelFlg) || SELECTOR_LOWER.equals(work.trnSelFlg)) {
                work.navigationTarget = TRANSACTION_DETAIL_PROGRAM;
                work.selectedTransactionId = work.trnSelected;
                return;
            }
            work.message = MSG_INVALID_SELECTION;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
        }

        if (isBlankOrUnset(work.trnIdIn)) {
            work.ridfld = LOW_VALUES_KEY;
            work.ridfldPastEnd = false;
        } else if (isFieldNumeric(work.trnIdIn)) {
            work.ridfld = work.trnIdIn;
            work.ridfldPastEnd = false;
        } else {
            work.errFlagOn = true;
            work.message = MSG_TRAN_ID_NOT_NUMERIC;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
            sendTrnlstScreen(work);
        }

        work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;

        work.pageNum = PAGE_NUMBER_RESET;
        processPageForward(work);

        if (!work.errFlagOn) {
            work.trnIdIn = SPACES;
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code PROCESS-PF7-KEY} ({@code :L234-L252}).
     *
     * <p>Anchors on {@code CDEMO-CT00-TRNID-FIRST}, or on low values when that is blank
     * ({@code :L236-L240}), then pages backward when the page number exceeds one, and otherwise
     * refuses with the top-of-page message ({@code :L245-L252}).
     *
     * <p><strong>Preserved quirk.</strong> {@code SET NEXT-PAGE-YES TO TRUE} at {@code :L242} is
     * <em>unconditional</em>. It is reproduced unconditionally and must never be made conditional:
     * the backward path reads that flag at {@code :L361} to decide whether to adjust the page
     * number, so making it conditional would change the page arithmetic.
     *
     * <p>{@code SET SEND-ERASE-NO} at {@code :L250} <strong>is</strong> modelled, and an earlier
     * revision of this comment was wrong to say it was not. It asserted that the flag "governs
     * terminal repainting only" and so needed no REST counterpart; that reading looks at the flag
     * and not at what the flag causes. {@code SEND-TRNLST-SCREEN} re-sends {@code COTRN0A} with the
     * row fields still at low values, and BMS does not transmit a low-values field, so with no
     * {@code ERASE} the 3270 leaves the rows it is already displaying untouched. The observable
     * outcome of a refused page is therefore the current page <em>plus</em> the message — never a
     * cleared list. Returning no rows here reported an empty result set to the caller, which is a
     * different answer to a different question.
     *
     * <p>The retained rows are reproduced by {@link #redisplayCurrentPage(ScreenWorkArea)}, which
     * re-reads the page this screen is already showing. See that method for why re-reading is the
     * faithful substitution for a terminal buffer.
     *
     * @param work the per-invocation work area
     */
    private void processPf7Key(final ScreenWorkArea work) {

        if (isBlankOrUnset(work.trnIdFirst)) {
            work.ridfld = LOW_VALUES_KEY;
            work.ridfldPastEnd = false;
        } else {
            work.ridfld = work.trnIdFirst;
            work.ridfldPastEnd = false;
        }

        work.nextPageYes = true;

        work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;

        if (work.pageNum > PageResponse.FIRST_PAGE_NUMBER) {
            processPageBackward(work);
        } else {
            work.message = MSG_ALREADY_AT_TOP;
            redisplayCurrentPage(work);
            sendTrnlstScreen(work);
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code PROCESS-PF8-KEY} ({@code :L257-L274}).
     *
     * <p>Anchors on {@code CDEMO-CT00-TRNID-LAST}, or on high values when that is blank
     * ({@code :L259-L263}), then pages forward when the next-page flag is on, and otherwise refuses
     * with the bottom-of-page message ({@code :L267-L274}).
     *
     * <p>High values positions past the end of the key sequence. That is modelled explicitly rather
     * than as a comparable sentinel, because a sentinel string's ordering against stored keys is
     * collation-dependent whereas the outcome here is not: nothing follows the end of the sequence.
     * The blank-anchor branch is unreachable in practice — the next-page flag is only set when a
     * page filled completely, which is exactly when the last-key anchor was written — but the branch
     * is preserved because the source has it.
     *
     * <p>The refusal arm at {@code :L272-L273} sets {@code SEND-ERASE-NO} exactly as the PF7 refusal
     * does, so it retains the displayed rows for the same reason and through the same
     * {@link #redisplayCurrentPage(ScreenWorkArea)} call.
     *
     * @param work the per-invocation work area
     */
    private void processPf8Key(final ScreenWorkArea work) {

        if (isBlankOrUnset(work.trnIdLast)) {
            work.ridfld = null;
            work.ridfldPastEnd = true;
        } else {
            work.ridfld = work.trnIdLast;
            work.ridfldPastEnd = false;
        }

        work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;

        if (work.nextPageYes) {
            processPageForward(work);
        } else {
            work.message = MSG_ALREADY_AT_BOTTOM;
            redisplayCurrentPage(work);
            sendTrnlstScreen(work);
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD} ({@code :L279-L328}).
     *
     * <p>Note that {@code PERFORM STARTBR-TRANSACT-FILE} at {@code :L281} sits <strong>outside</strong>
     * the {@code IF NOT ERR-FLG-ON} guard opened at {@code :L283} and closed at {@code :L328}.
     * Positioning therefore happens even when an earlier step raised the error flag, while the fill,
     * the lookahead, the end-browse and the send are all skipped. That placement is reproduced,
     * including the consequence that the browse is left open on the error path.
     *
     * <p>The priming read at {@code :L285-L287} fires when the attention identifier is none of
     * enter, PF7 or PF3 — reachable only for PF8. Its record is discarded, being overwritten by the
     * first fill read before anything projects it, so its sole effects are to advance the position
     * and to be capable of signalling end of data. It is rendered as an exclusive rather than
     * inclusive key bound, with the discarded record consumed as a pending anchor so that the read
     * still occurs and can still signal. This is exactly why the repository exposes both an
     * inclusive and an exclusive ascending finder.
     *
     * <p>The lookahead at {@code :L305-L320} is what sets the next-page flag: one extra read beyond
     * the page. In Java the window is fetched one row larger than the page and the indicator is
     * derived from whether that extra row materialised, so no counting query is issued. Both page
     * number guards are reproduced — the increment inside the non-end-of-data arm at
     * {@code :L306-L307}, and the {@code IF WS-IDX > 1} guarded increment in the other arm at
     * {@code :L316-L319}.
     *
     * @param work the per-invocation work area
     */
    private void processPageForward(final ScreenWorkArea work) {

        final boolean primingRead =
                work.attentionIdentifier != AttentionIdentifier.ENTER
                        && work.attentionIdentifier != AttentionIdentifier.PF7
                        && work.attentionIdentifier != AttentionIdentifier.PF3;

        startbrTransactFile(work, false, primingRead);

        if (!work.errFlagOn) {

            if (primingRead) {
                readnextTransactFile(work);
            }

            if (!work.transactEof && !work.errFlagOn) {
                for (work.idx = 1; work.idx <= TransactionDto.PAGE_SIZE; work.idx++) {
                    initializeTranData(work);
                }
            }

            work.idx = 1;

            while (work.idx <= this.pageSize && !work.transactEof && !work.errFlagOn) {
                readnextTransactFile(work);
                if (!work.transactEof && !work.errFlagOn) {
                    populateTranData(work);
                    work.idx = work.idx + 1;
                }
            }

            if (!work.transactEof && !work.errFlagOn) {
                work.pageNum = work.pageNum + 1;
                readnextTransactFile(work);
                if (!work.transactEof && !work.errFlagOn) {
                    work.nextPageYes = true;
                } else {
                    work.nextPageYes = false;
                }
            } else {
                work.nextPageYes = false;
                if (work.idx > 1) {
                    work.pageNum = work.pageNum + 1;
                }
            }

            endbrTransactFile(work);

            work.pageNumDisplay = renderPageNumber(work.pageNum);
            work.trnIdIn = SPACES;
            sendTrnlstScreen(work);
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code PROCESS-PAGE-BACKWARD} ({@code :L333-L376}).
     *
     * <p>The priming read at {@code :L339-L341} uses a <em>different</em> exclusion set from the
     * forward path — neither enter nor PF8 — so it always fires on PF7, the only key that reaches
     * here. Because it always fires, and because its record is always the existing anchor row, the
     * effective window is unconditionally "strictly before the anchor", which is precisely the
     * exclusive descending finder the repository exposes.
     *
     * <p>The fill at {@code :L349-L357} starts at the highest slot and decrements, so rows land in
     * <strong>descending slot order</strong>: the greatest key of the retrieved window occupies the
     * last slot and the least occupies the first. Rows are nonetheless presented in positional order
     * one through ten, which is what the descending fill produces once complete.
     *
     * <p>The tail at {@code :L359-L369} is three nested conditions, all reproduced: the outer
     * not-end-of-data guard, the next-page-flag test, and the page-number test that decrements or
     * else forces the page number to one.
     *
     * <p><strong>Preserved asymmetry, Low.</strong> This path does not blank the search field,
     * whereas the forward path does at {@code :L325}. It also never assigns the next-page flag; it
     * only reads it, so the carried value survives a backward page.
     *
     * @param work the per-invocation work area
     */
    private void processPageBackward(final ScreenWorkArea work) {

        final boolean primingRead =
                work.attentionIdentifier != AttentionIdentifier.ENTER
                        && work.attentionIdentifier != AttentionIdentifier.PF8;

        startbrTransactFile(work, true, primingRead);

        if (!work.errFlagOn) {

            if (primingRead) {
                readprevTransactFile(work);
            }

            if (!work.transactEof && !work.errFlagOn) {
                for (work.idx = 1; work.idx <= TransactionDto.PAGE_SIZE; work.idx++) {
                    initializeTranData(work);
                }
            }

            work.idx = this.pageSize;

            while (work.idx > 0 && !work.transactEof && !work.errFlagOn) {
                readprevTransactFile(work);
                if (!work.transactEof && !work.errFlagOn) {
                    populateTranData(work);
                    work.idx = work.idx - 1;
                }
            }

            if (!work.transactEof && !work.errFlagOn) {
                readprevTransactFile(work);
                if (work.nextPageYes) {
                    if (!work.transactEof
                            && !work.errFlagOn
                            && work.pageNum > PageResponse.FIRST_PAGE_NUMBER) {
                        work.pageNum = work.pageNum - 1;
                    } else {
                        work.pageNum = PageResponse.FIRST_PAGE_NUMBER;
                    }
                }
            }

            endbrTransactFile(work);

            work.pageNumDisplay = renderPageNumber(work.pageNum);
            sendTrnlstScreen(work);
        }
    }

    /**
     * Refills the row slots with the page this screen is already displaying, for the two refusals
     * that decline to page — {@code PROCESS-PF7-KEY} at {@code app/cbl/COTRN00C.cbl:L245-L252} and
     * {@code PROCESS-PF8-KEY} at {@code :L267-L274}.
     *
     * <p><strong>Why this exists, and why it is a re-read rather than an echo.</strong> Both refusals
     * set {@code SEND-ERASE-NO} and re-send the map with the row fields at low values. BMS does not
     * transmit a low-values field and there is no {@code ERASE}, so the 3270 keeps displaying the
     * rows already in its buffer and the reader sees the unchanged page beneath the new message. The
     * rows in that outcome come from state the <em>terminal</em> holds, and a stateless HTTP response
     * has no such buffer to inherit from, so the outcome has to be produced rather than retained.
     *
     * <p>Two substitutions were available and the other was rejected on a contract ground rather
     * than on effort. Echoing rows submitted by the caller is the closer analogue of a terminal
     * buffer, but the request carries only a row <em>count</em> — {@code rowCount} — and never the
     * row contents, so there is nothing to echo without widening the request contract to accept
     * fifty-nine row fields the caller would then be trusted to have told the truth about. A caller
     * could name rows that were never displayed and the server would repeat them back as though it
     * had read them. Re-reading the page from its own anchor produces the identical observable
     * outcome and asserts only what the database actually holds.
     *
     * <p><strong>The anchor is the first key of the displayed page</strong>, {@code CDEMO-CT00-TRNID-FIRST},
     * read inclusively — which is by construction the window the caller is looking at. Low values
     * stands in when that anchor is blank, matching the same substitution {@code :L236-L240} makes.
     *
     * <p><strong>This method changes no pagination state, and that is enforced rather than
     * intended.</strong> A refused page must leave the page number, the next-page flag, both key
     * anchors and the message exactly as the refusal set them, or the caller's next request would
     * navigate from a position the refusal never granted. {@link #populateTranData(ScreenWorkArea)}
     * is reused because it is the single authority for the amount mask and the date projection, and
     * duplicating either here would create two projections free to drift apart — but it also writes
     * {@code trnIdFirst} at slot one and {@code trnIdLast} at slot ten, so both anchors, the slot
     * index and the current record are snapshotted and restored around the fill. Re-reading the same
     * page would in fact restore the same two anchor values, but that holds only while the page is
     * full: on a partial final page slot ten is never reached, which is the preserved stale-anchor
     * defect documented on {@code populateTranData}. Restoring explicitly means this method cannot
     * perturb the anchors under any page shape rather than merely happening not to under most.
     *
     * <p>A failed read is <em>not</em> swallowed to keep the refusal looking clean. It surfaces as
     * the same typed exception every other browse failure raises, because a refusal that silently
     * reported zero rows after an I/O error is the defect this method exists to remove, only quieter.
     * An empty result is different and is not a failure: it means the page genuinely holds no rows.
     *
     * @param work the per-invocation work area, whose row slots are refilled in place
     */
    private void redisplayCurrentPage(final ScreenWorkArea work) {

        final String anchor = isBlankOrUnset(work.trnIdFirst) ? LOW_VALUES_KEY : work.trnIdFirst;

        final List<Transaction> displayed;
        try {
            displayed =
                    this.transactionRepository
                            .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                                    anchor, PageRequest.of(0, this.pageSize))
                            .getContent();
        } catch (final DataAccessException failure) {
            throw browseFailure("STARTBR", failure);
        }

        final String retainedFirst = work.trnIdFirst;
        final String retainedLast = work.trnIdLast;
        final int retainedIdx = work.idx;
        final Transaction retainedRecord = work.tranRecord;

        for (work.idx = 1; work.idx <= TransactionDto.PAGE_SIZE; work.idx++) {
            initializeTranData(work);
        }

        int slot = 1;
        for (final Transaction record : displayed) {
            if (slot > TransactionDto.PAGE_SIZE) {
                break;
            }
            work.tranRecord = record;
            work.idx = slot;
            populateTranData(work);
            slot = slot + 1;
        }

        work.trnIdFirst = retainedFirst;
        work.trnIdLast = retainedLast;
        work.idx = retainedIdx;
        work.tranRecord = retainedRecord;
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code POPULATE-TRAN-DATA} ({@code :L381-L445}).
     *
     * <p>Projects the current record into the slot named by {@code WS-IDX}. The two projections at
     * {@code :L383-L388} run before the ten-way dispatch and are therefore shared by every slot: the
     * amount is moved to the edited mask, and the date is derived from the originating timestamp.
     *
     * <p>Slot one additionally stores {@code CDEMO-CT00-TRNID-FIRST} ({@code :L392-L393}) and slot
     * ten additionally stores {@code CDEMO-CT00-TRNID-LAST} ({@code :L437-L439}).
     *
     * <p><strong>Preserved defect, High.</strong> The last-key anchor is written <em>only</em> at
     * slot ten. On a partial final page the index never reaches ten, so the anchor keeps its
     * previous value and a later forward page would position from a stale key. The anchor is
     * deliberately not derived from the actual final row. The anchor slot is the physical last slot
     * of the map, matching the source's {@code WHEN 10}, which is why a page size smaller than the
     * map width would never refresh it at all — the faithful extension of the same defect.
     *
     * @param work the per-invocation work area, whose index selects the slot
     */
    private void populateTranData(final ScreenWorkArea work) {

        final String editedAmount = renderEditedAmount(work.tranRecord.getAmount());
        final String editedDate = projectTranDate(work.tranRecord.getOrigTs());

        final int slot = work.idx;
        if (slot < 1 || slot > TransactionDto.PAGE_SIZE) {
            return;
        }

        final int index = slot - 1;
        final String transactionId = work.tranRecord.getTransactionId();

        work.rowTranId[index] = transactionId;
        work.rowDate[index] = editedDate;
        work.rowDesc[index] =
                truncate(work.tranRecord.getDescription(), TransactionDto.ROW_DESCRIPTION_LENGTH);
        work.rowAmt[index] = editedAmount;

        if (slot == 1) {
            work.trnIdFirst = transactionId;
        }
        if (slot == TransactionDto.PAGE_SIZE) {
            work.trnIdLast = transactionId;
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code INITIALIZE-TRAN-DATA} ({@code :L450-L505}).
     *
     * <p>Blanks the slot named by {@code WS-IDX}.
     *
     * <p><strong>Preserved omission, Low.</strong> Only the id, date, description and amount are
     * blanked. The row selector {@code SEL000nI} is <em>not</em> cleared anywhere in this paragraph,
     * and because {@code app/cpy-bms/COTRN00.CPY:373} declares
     * {@code 01 COTRN0AO REDEFINES COTRN0AI} the input and output maps share storage, so a received
     * selector survives the blanking and is echoed back to the terminal. The omission is reproduced
     * exactly: the selector array is untouched here.
     *
     * @param work the per-invocation work area, whose index selects the slot
     */
    private void initializeTranData(final ScreenWorkArea work) {

        final int slot = work.idx;
        if (slot < 1 || slot > TransactionDto.PAGE_SIZE) {
            return;
        }

        final int index = slot - 1;
        work.rowTranId[index] = SPACES;
        work.rowDate[index] = SPACES;
        work.rowDesc[index] = SPACES;
        work.rowAmt[index] = SPACES;
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code RETURN-TO-PREV-SCREEN} ({@code :L510-L521}).
     *
     * <p>Defaults the target to the sign-on program when none was set ({@code :L512-L514}), then
     * transfers control.
     *
     * <p>No counterpart for the terminal behaviour: {@code MOVE WS-TRANID TO CDEMO-FROM-TRANID}
     * ({@code :L515}), {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} ({@code :L516}) and
     * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} ({@code :L517}) maintain communication-area routing
     * fields that do not exist here, because routing is URL-based and every request is evaluated
     * fresh. {@code EXEC CICS XCTL} ({@code :L518-L521}) cannot be performed server-side; the target
     * is reported on the result so the caller can navigate.
     *
     * @param work the per-invocation work area
     */
    private void returnToPrevScreen(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.navigationTarget)) {
            work.navigationTarget = SIGN_ON_PROGRAM;
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code SEND-TRNLST-SCREEN} ({@code :L527-L549}).
     *
     * <p>Populates the header ({@code :L529}) and copies the pending message into the error line
     * ({@code :L531}), then sends the map with or without erase according to the erase flag
     * ({@code :L533}).
     *
     * <p><strong>This paragraph contains no {@code EXEC CICS RETURN}.</strong> It therefore falls
     * through, and callers rely on their own {@code IF NOT ERR-FLG-ON} guards to decide whether to
     * continue — statements after a send genuinely do still execute. That is why this method returns
     * normally and never throws: making it fail fast would diverge, and the contrasting
     * fail-fast shape of the sibling {@code COTRN02C} send paragraph must not be applied here.
     *
     * <p>{@code EXEC CICS SEND MAP} has no counterpart; the projection is returned as a value
     * instead. The erase flag governs terminal repainting only and is recorded rather than modelled.
     *
     * @param work the per-invocation work area
     */
    private void sendTrnlstScreen(final ScreenWorkArea work) {
        populateHeaderInfo(work);
        work.errMsg = work.message;
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code RECEIVE-TRNLST-SCREEN} ({@code :L554-L562}).
     *
     * <p>{@code EXEC CICS RECEIVE MAP} has no counterpart: the request arrives already deserialised.
     * This method performs the equivalent transfer, loading the submitted search key, the ten row
     * selectors and the ten displayed row ids into the work area in positional order, so that the
     * selector scan in {@code PROCESS-ENTER-KEY} resolves a selector to an id exactly as
     * {@code :L148-L182} does.
     *
     * <p>Entries beyond what the caller supplied are left blank, which is the equivalent of a
     * terminal transmitting nothing for an unmodified field. The displayed ids are deliberately
     * retained rather than cleared, so that the path where slot blanking is skipped keeps showing
     * them exactly as the shared input and output map storage does. The cosmetic row fields — date,
     * description and amount — are not carried on the request and so are blank on that path; a
     * bounded divergence, reachable only when the priming read hits end of data, which
     * the next-page guard makes unreachable in normal use.
     *
     * <p><strong>Every received field is bounded to its declared map width.</strong>
     * {@code :L559} receives {@code INTO(COTRN0AI)}, a fixed-width symbolic map in which
     * {@code TRNIDINI} is {@code PIC X(16)} ({@code app/cpy-bms/COTRN00.CPY:66}), {@code SEL000nI}
     * is {@code PIC X(1)} ({@code app/cpy-bms/COTRN00.CPY:72}) and {@code TRNIDnnI} is
     * {@code PIC X(16)} ({@code app/cpy-bms/COTRN00.CPY:78}). A 3270 field physically cannot deliver
     * more bytes than it declares, and a COBOL {@code MOVE} of a longer sending field into
     * {@code PIC X(n)} keeps the leftmost {@code n} bytes, so bounding here <em>is</em> the transfer
     * semantics rather than a guard layered on top of them. An HTTP caller, unlike a terminal, can
     * submit an over-width value; bounding it on receive is what keeps such a request on the same
     * code path the terminal would have taken — an over-width search key still fails the
     * {@code IS NUMERIC} test at {@code :L209} whenever its leading sixteen bytes are not all
     * digits, and the response still carries the {@code :L214} literal. Without this bound an
     * over-width field would instead escape as an {@code IllegalArgumentException} raised by the
     * map-width validation inside {@code TransactionDto}, which has no legacy counterpart and would
     * breach the standing requirement that inputs be treated as untrusted.
     *
     * @param work the per-invocation work area
     * @param searchTransactionId the submitted search key, or {@code null}
     * @param selectionFlags the submitted row selectors in positional order
     * @param displayedTransactionIds the row ids currently displayed, in positional order
     */
    private void receiveTrnlstScreen(
            final ScreenWorkArea work,
            final String searchTransactionId,
            final List<String> selectionFlags,
            final List<String> displayedTransactionIds) {

        work.trnIdIn = searchTransactionId == null
                ? SPACES
                : truncate(searchTransactionId, TransactionDto.TRANSACTION_ID_LENGTH);

        for (int slot = 0; slot < TransactionDto.PAGE_SIZE; slot++) {
            if (slot < selectionFlags.size() && selectionFlags.get(slot) != null) {
                work.sel[slot] =
                        truncate(selectionFlags.get(slot), TransactionDto.SELECTION_FLAG_LENGTH);
            }
            if (slot < displayedTransactionIds.size() && displayedTransactionIds.get(slot) != null) {
                work.rowTranId[slot] = truncate(
                        displayedTransactionIds.get(slot), TransactionDto.TRANSACTION_ID_LENGTH);
            }
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code POPULATE-HEADER-INFO} ({@code :L567-L586}).
     *
     * <p>Populates the six header fields that recur on all seventeen mapsets. {@code :L569} takes
     * the current date and time, which is why a {@code java.time.Clock} is injected rather than
     * read from the system: the header must be deterministic under test.
     *
     * <p>The titles come from {@code app/cpy/COTTL01Y.cpy} ({@code :L571-L572}); the transaction and
     * program names from working storage ({@code :L573-L574}); the date is the eight-character
     * {@code MM/DD/YY} group assembled at {@code :L576-L580} and the time the eight-character
     * {@code HH:MM:SS} group at {@code :L582-L586}, both defined at
     * {@code app/cpy/CSDAT01Y.cpy:30-41}. The header time field on this mapset is {@code X(8)}
     * ({@code app/cpy-bms/COTRN00.CPY:54}), not {@code X(9)}.
     *
     * <p>These six fields are deliberately <em>not</em> extracted into a shared helper: the file
     * budget for this migration unit is fixed and duplication across services is the accepted
     * trade-off recorded against repository hygiene.
     *
     * @param work the per-invocation work area
     */
    private void populateHeaderInfo(final ScreenWorkArea work) {

        final LocalDateTime headerTimestamp = LocalDateTime.now(this.clock);

        work.title01 = SCREEN_TITLE_01;
        work.title02 = SCREEN_TITLE_02;
        work.trnName = TRANSACTION_ID;
        work.pgmName = PROGRAM_NAME;
        work.curDate = HEADER_DATE_FORMAT.format(headerTimestamp);
        work.curTime = HEADER_TIME_FORMAT.format(headerTimestamp);
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code STARTBR-TRANSACT-FILE} ({@code :L591-L619}).
     *
     * <p>Positions the browse. In CICS this establishes a position without retrieving a record; here
     * it issues the single ordered, bounded query that the subsequent reads consume, which keeps one
     * page to one round trip rather than one row to one round trip.
     *
     * <p><strong>Preserved quirk, Medium.</strong> The {@code GTEQ} operand is commented out at
     * {@code :L597}. This does not make the browse an equal-key browse: the program positions on
     * low values ({@code :L207}) and high values ({@code :L260}), neither of which is a real key, so
     * an equal-key browse would find nothing on every first display. Greater-or-equal positioning is
     * what the idiom requires and what the repository contract provides; the commented operand is
     * behaviourally inert, and the commented operand is deliberately left commented rather than
     * restored.
     *
     * <p>Outcomes. {@code DFHRESP(NORMAL)} continues ({@code :L603-L604}). {@code DFHRESP(NOTFND)}
     * at {@code :L605-L611} sets end of data, reports the top-of-page message and sends — and
     * crucially does <strong>not</strong> raise the error flag, so processing continues; the
     * redundant {@code CONTINUE} at {@code :L606} is subsumed by Java control flow. Not-found is
     * signalled by an empty window with no pending anchor: when an anchor is pending the anchor row
     * itself satisfies the positioning, exactly as CICS positions successfully on a key that exists.
     * {@code WHEN OTHER} at {@code :L612-L618} logs the response codes, raises the error flag and
     * reports the lookup failure.
     *
     * <p><strong>Labelled deviation.</strong> The source's {@code WHEN OTHER} arm redisplays the
     * screen and returns, because a CICS response code is a displayable outcome. A Spring
     * data-access failure is an infrastructure fault, not a displayable response code, so the flag,
     * message and cursor transitions are reproduced and the failure is then rethrown as a typed
     * unchecked exception preserving the root cause. Swallowing it to return a screen would breach
     * the no-swallowing standard and hide the fault.
     *
     * @param work the per-invocation work area
     * @param descending whether the subsequent reads run backward. CICS derives direction from the
     *     following read verb, whereas an ordered query must know it when it is issued
     * @param skipAnchor whether the caller's priming read will consume the anchor row, in which case
     *     the bound is exclusive and the anchor is recorded as pending
     * @throws CardDemoException if positioning fails for a reason other than not-found
     */
    private void startbrTransactFile(
            final ScreenWorkArea work, final boolean descending, final boolean skipAnchor) {

        work.phantomAnchorPending = skipAnchor;
        work.browsePosition = 0;
        work.browseBuffer = List.of();

        final Pageable window = PageRequest.of(0, this.pageSize + 1);

        try {
            if (work.ridfldPastEnd) {
                work.browseBuffer = List.of();
            } else if (descending) {
                final Slice<Transaction> slice =
                        this.transactionRepository
                                .findByTransactionIdLessThanOrderByTransactionIdDesc(
                                        work.ridfld, window);
                work.browseBuffer = slice.getContent();
            } else if (skipAnchor) {
                final Slice<Transaction> slice =
                        this.transactionRepository
                                .findByTransactionIdGreaterThanOrderByTransactionIdAsc(
                                        work.ridfld, window);
                work.browseBuffer = slice.getContent();
            } else {
                final Slice<Transaction> slice =
                        this.transactionRepository
                                .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
                                        work.ridfld, window);
                work.browseBuffer = slice.getContent();
            }
        } catch (final DataAccessException failure) {
            work.errFlagOn = true;
            work.message = MSG_UNABLE_TO_LOOKUP;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
            sendTrnlstScreen(work);
            throw browseFailure("STARTBR", failure);
        }

        if (work.browseBuffer.isEmpty() && !work.phantomAnchorPending) {
            work.transactEof = true;
            work.message = MSG_AT_TOP;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
            sendTrnlstScreen(work);
        }
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code READNEXT-TRANSACT-FILE} ({@code :L624-L653}).
     *
     * <p>Advances the browse forward by one record into the record area.
     *
     * <p>Outcomes. {@code DFHRESP(NORMAL)} continues. {@code DFHRESP(ENDFILE)} at
     * {@code :L639-L645} sets end of data, reports the bottom-of-page message and sends, and does
     * <strong>not</strong> raise the error flag: end of data is loop termination, never an error, and
     * is never thrown. The redundant {@code CONTINUE} at {@code :L640} is subsumed by Java control
     * flow. {@code WHEN OTHER} at {@code :L646-L652} raises the error flag and reports the lookup
     * failure; in this rendering the response codes other than normal and end-of-data surface at the
     * query in {@code STARTBR}, and the arm is retained here for a persistence-provider fault
     * during consumption, which the explicit null check makes reachable.
     *
     * <p>When an anchor row is pending, this read consumes it: the position advances and the record
     * is discarded, which is exactly what the source's priming read does, since that record is
     * overwritten by the first fill read before anything projects it.
     *
     * @param work the per-invocation work area
     * @throws CardDemoException if a buffered record cannot be consumed
     */
    private void readnextTransactFile(final ScreenWorkArea work) {

        if (work.phantomAnchorPending) {
            work.phantomAnchorPending = false;
            return;
        }

        if (work.browsePosition >= work.browseBuffer.size()) {
            work.transactEof = true;
            work.message = MSG_REACHED_BOTTOM;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
            sendTrnlstScreen(work);
            return;
        }

        final Transaction record = work.browseBuffer.get(work.browsePosition);
        work.browsePosition = work.browsePosition + 1;

        if (record == null) {
            work.errFlagOn = true;
            work.message = MSG_UNABLE_TO_LOOKUP;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
            sendTrnlstScreen(work);
            throw browseFailure("READNEXT", null);
        }

        work.tranRecord = record;
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code READPREV-TRANSACT-FILE} ({@code :L658-L687}).
     *
     * <p>Advances the browse backward by one record into the record area. The window was retrieved
     * in descending key order, so consuming it in order walks backward exactly as the source does.
     *
     * <p>Outcomes. {@code DFHRESP(NORMAL)} continues. {@code DFHRESP(ENDFILE)} at
     * {@code :L673-L679} sets end of data and reports the top-of-page message without raising the
     * error flag — note that this message differs from the forward path's, so the two must not be
     * unified; the redundant {@code CONTINUE} at {@code :L674} is subsumed by Java control flow.
     * {@code WHEN OTHER} at {@code :L680-L686} raises the error flag and reports the lookup failure.
     *
     * @param work the per-invocation work area
     * @throws CardDemoException if a buffered record cannot be consumed
     */
    private void readprevTransactFile(final ScreenWorkArea work) {

        if (work.phantomAnchorPending) {
            work.phantomAnchorPending = false;
            return;
        }

        if (work.browsePosition >= work.browseBuffer.size()) {
            work.transactEof = true;
            work.message = MSG_REACHED_TOP;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
            sendTrnlstScreen(work);
            return;
        }

        final Transaction record = work.browseBuffer.get(work.browsePosition);
        work.browsePosition = work.browsePosition + 1;

        if (record == null) {
            work.errFlagOn = true;
            work.message = MSG_UNABLE_TO_LOOKUP;
            work.cursorField = CURSOR_FIELD_TRAN_ID_INPUT;
            sendTrnlstScreen(work);
            throw browseFailure("READPREV", null);
        }

        work.tranRecord = record;
    }

    /**
     * Source: {@code app/cbl/COTRN00C.cbl} {@code ENDBR-TRANSACT-FILE} ({@code :L692-L696}).
     *
     * <p>A bare {@code EXEC CICS ENDBR} with no outcome branching. The CICS command releases the
     * VSAM string held by the browse; there is no query to terminate here because it has already
     * completed, so the counterpart action is to release the retrieved window and reset the
     * position. Note that the source reaches this paragraph only inside the error-flag guard, so on
     * the error path the legacy browse is left open and is released only at task end — a source
     * behaviour that has no consequence in this rendering.
     *
     * @param work the per-invocation work area
     */
    private void endbrTransactFile(final ScreenWorkArea work) {
        work.browseBuffer = List.of();
        work.browsePosition = 0;
        work.phantomAnchorPending = false;
    }

    /**
     * Builds the typed browse failure, routing the status through the shared mapper so that this
     * class contains no second translation point and does not reformat the mapper's rendering.
     *
     * @param operation the browse operation that failed, for diagnostic context
     * @param cause the originating failure, or {@code null} when none is available
     * @return the exception to throw; never {@code null}
     */
    private CardDemoException browseFailure(final String operation, final Throwable cause) {
        LOG.error(
                "Transaction browse failed. operation={} file={} status={} program={} transaction={}",
                operation,
                TRANSACT_FILE,
                this.fileStatusMapper.displayIoStatus(IO_FAILURE_STATUS),
                PROGRAM_NAME,
                TRANSACTION_ID,
                cause);
        final Optional<CardDemoException> mapped =
                this.fileStatusMapper.toException(
                        IO_FAILURE_STATUS, TRANSACT_FILE, operation, cause);
        return mapped.orElseGet(
                () ->
                        cause == null
                                ? new FileAccessException(
                                        MSG_UNABLE_TO_LOOKUP,
                                        IO_FAILURE_STATUS,
                                        TRANSACT_FILE,
                                        operation)
                                : new FileAccessException(MSG_UNABLE_TO_LOOKUP, cause));
    }

    /**
     * Assembles the result of one turn from the work area, standing in for
     * {@code EXEC CICS RETURN ... COMMAREA} at {@code app/cbl/COTRN00C.cbl:L138-L141}.
     *
     * <p>All ten physical row slots are emitted so the projection always matches the map, while the
     * page metadata carries only the rows that actually materialised. The raw page number is
     * rendered verbatim on the projection, including the all-zero value the source produces when the
     * browse finds nothing, and is carried raw on the returned state so it can re-enter the source's
     * arithmetic unaltered.
     *
     * <p><strong>Labelled deviation.</strong> {@code PageResponse} forbids a page number below
     * one, so its copy is clamped. This is provably inert: a zero page number only arises together
     * with the next-page indicator off, in which case forward paging takes the refusal arm and the
     * backward guard tests identically for zero and one.
     *
     * @param work the per-invocation work area
     * @return the completed screen; never {@code null}
     */
    private TransactionListScreen toScreen(final ScreenWorkArea work) {

        final List<TransactionDto.TransactionListRow> slots =
                new ArrayList<>(TransactionDto.PAGE_SIZE);
        final List<TransactionDto.TransactionListRow> materialised =
                new ArrayList<>(TransactionDto.PAGE_SIZE);

        for (int slot = 0; slot < TransactionDto.PAGE_SIZE; slot++) {
            final TransactionDto.TransactionListRow row =
                    new TransactionDto.TransactionListRow(
                            work.sel[slot],
                            work.rowTranId[slot],
                            work.rowDate[slot],
                            work.rowDesc[slot],
                            work.rowAmt[slot]);
            slots.add(row);
            if (isPresent(work.rowTranId[slot])) {
                materialised.add(row);
            }
        }

        final TransactionDto projection =
                new TransactionDto(
                        work.trnName,
                        work.title01,
                        work.curDate,
                        work.pgmName,
                        work.title02,
                        work.curTime,
                        work.trnIdIn,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        work.errMsg,
                        work.pageNumDisplay,
                        slots,
                        null);

        final PageResponse<TransactionDto.TransactionListRow> page =
                new PageResponse<>(
                        materialised,
                        Math.max(PageResponse.FIRST_PAGE_NUMBER, work.pageNum),
                        this.pageSize,
                        work.nextPageYes,
                        work.trnIdFirst,
                        work.trnIdLast);

        final TransactionListState state =
                new TransactionListState(
                        work.trnIdFirst, work.trnIdLast, work.pageNum, work.nextPageYes);

        return new TransactionListScreen(
                projection,
                page,
                state,
                work.navigationTarget,
                work.selectedTransactionId,
                work.cursorField,
                work.errFlagOn);
    }

    /**
     * Renders the eight-character page number written to {@code PAGENUMI} at
     * {@code app/cbl/COTRN00C.cbl:L324} and {@code :L373}, narrowing {@code PIC 9(08)} to
     * {@code PIC X(8)}. A negative value cannot arise, since the only decrement is guarded by a
     * greater-than-one test.
     *
     * @param pageNumber the raw page counter
     * @return the zero-padded rendering; never {@code null}
     */
    private static String renderPageNumber(final int pageNumber) {
        final String digits = Integer.toString(Math.max(0, pageNumber));
        final int padding = TransactionDto.PAGE_NUMBER_LENGTH - digits.length();
        if (padding <= 0) {
            return digits.substring(digits.length() - TransactionDto.PAGE_NUMBER_LENGTH);
        }
        return ZERO_DIGIT.repeat(padding) + digits;
    }

    /**
     * Renders the {@code PIC +99999999.99} edited mask of {@code WS-TRAN-AMT}
     * ({@code app/cbl/COTRN00C.cbl:L56}) as twelve characters: a mandatory sign, eight zero-padded
     * integer digits, a decimal point and two decimals.
     *
     * <p>The mask holds eight integer digits while the stored amount is {@code S9(09)V99}
     * ({@code app/cpy/CVTRA05Y.cpy:10}), nine. The legacy move truncates the high-order digit and so
     * does this, via the modulus — the discrepancy is preserved, not repaired. The digits are taken
     * from the unscaled value rather than a locale-sensitive formatter, so the rendering is
     * locale-independent by construction, and negative amounts keep their sign because no
     * absolute-value normalisation is applied anywhere on this path.
     *
     * @param amount the stored amount, or {@code null}
     * @return the twelve-character rendering, or a blank field when the amount is absent
     */
    private static String renderEditedAmount(final BigDecimal amount) {
        if (amount == null) {
            return SPACES;
        }
        final BigDecimal scaled =
                amount.setScale(TransactionDto.AMOUNT_SCALE, TransactionDto.AMOUNT_ROUNDING_MODE);
        final String sign = scaled.signum() < 0 ? MINUS_SIGN : PLUS_SIGN;
        final BigDecimal magnitude =
                scaled.abs()
                        .remainder(AMOUNT_MASK_MODULUS)
                        .setScale(
                                TransactionDto.AMOUNT_SCALE, TransactionDto.AMOUNT_ROUNDING_MODE);
        final String digits = magnitude.unscaledValue().toString();
        final String padded =
                ZERO_DIGIT.repeat(Math.max(0, AMOUNT_MASK_TOTAL_DIGITS - digits.length())) + digits;
        return sign
                + padded.substring(0, AMOUNT_MASK_INTEGER_DIGITS)
                + DECIMAL_POINT
                + padded.substring(AMOUNT_MASK_INTEGER_DIGITS);
    }

    /**
     * Projects the eight-character {@code MM/DD/YY} row date from the originating timestamp, exactly
     * as {@code app/cbl/COTRN00C.cbl:L384-L388} does.
     *
     * <p>{@code app/cpy/CSDAT01Y.cpy:42-55} fixes the timestamp at twenty-six characters in the
     * layout {@code yyyy-MM-dd HH:mm:ss} followed by a point and six fractional digits, so the year
     * suffix, month and day sit at fixed offsets. The stored value is character data, so it is
     * sliced as text and deliberately never parsed into a temporal type: parsing would impose a
     * strictness the source does not have and would fail on values the source renders happily.
     *
     * <p>A blank or short value yields the initial value of {@code WS-TRAN-DATE}
     * ({@code app/cbl/COTRN00C.cbl:L57}). This case is real rather than defensive: fixture data
     * carries all-blank twenty-six-character timestamps.
     *
     * @param originatingTimestamp the stored twenty-six-character timestamp, possibly {@code null},
     *     blank or short
     * @return the eight-character {@code MM/DD/YY} rendering; never {@code null}
     */
    private static String projectTranDate(final String originatingTimestamp) {
        if (originatingTimestamp == null
                || originatingTimestamp.length() < TIMESTAMP_MINIMUM_LENGTH
                || originatingTimestamp.isBlank()) {
            return UNSET_TRAN_DATE;
        }
        return originatingTimestamp.substring(TIMESTAMP_MONTH_BEGIN, TIMESTAMP_MONTH_END)
                + "/"
                + originatingTimestamp.substring(TIMESTAMP_DAY_BEGIN, TIMESTAMP_DAY_END)
                + "/"
                + originatingTimestamp.substring(
                        TIMESTAMP_YEAR_SUFFIX_BEGIN, TIMESTAMP_YEAR_SUFFIX_END);
    }

    /**
     * Truncates to a map field width, reproducing the high-order-preserving truncation of a COBOL
     * move into a shorter alphanumeric field.
     *
     * @param value the value to truncate, possibly {@code null}
     * @param width the target field width
     * @return the value limited to {@code width} characters, or a blank field when absent
     */
    private static String truncate(final String value, final int width) {
        if (value == null) {
            return SPACES;
        }
        return value.length() <= width ? value : value.substring(0, width);
    }

    /**
     * Tests the COBOL condition {@code NOT = SPACES AND LOW-VALUES}: a field counts as supplied only
     * when it holds at least one non-blank character.
     *
     * @param value the field to test, possibly {@code null}
     * @return {@code true} when the field carries content
     */
    private static boolean isPresent(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Tests the COBOL condition {@code = SPACES OR LOW-VALUES}, the complement of
     * {@link #isPresent}.
     *
     * @param value the field to test, possibly {@code null}
     * @return {@code true} when the field is unset or blank
     */
    private static boolean isBlankOrUnset(final String value) {
        return !isPresent(value);
    }

    /**
     * Tests the COBOL {@code IS NUMERIC} class condition applied to {@code TRNIDINI} at
     * {@code app/cbl/COTRN00C.cbl:L209}.
     *
     * <p>The field is {@code PIC X(16)} ({@code app/cpy-bms/COTRN00.CPY:66}), and the class
     * condition on an alphanumeric display item requires <em>every</em> position to hold a digit. A
     * partially filled field is blank-padded and therefore fails, which is the legacy contract: the
     * search key must be a complete sixteen-digit transaction id. That strictness is preserved
     * rather than relaxed, and the digit test is restricted to ASCII digits so that no other Unicode
     * decimal digit is accepted.
     *
     * @param value the submitted field, possibly {@code null}
     * @return {@code true} when the field is exactly sixteen ASCII digits
     */
    private static boolean isFieldNumeric(final String value) {
        if (value == null || value.length() != TransactionDto.TRANSACTION_ID_LENGTH) {
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
     * The per-invocation stand-in for the legacy {@code WORKING-STORAGE SECTION}, the
     * {@code COTRN00} symbolic map and the appended {@code CDEMO-CT00-INFO} state group.
     *
     * <p>Every legacy working-storage item is a field here rather than on the service, so the bean
     * holds no mutable state and concurrent requests cannot interfere. Fields are accessed directly
     * rather than through accessors because this is a private data carrier standing in for a COBOL
     * record, and wrapping it would add ceremony without adding safety.
     *
     * <p>Two declared-but-unreferenced legacy items are deliberately absent: {@code WS-REC-COUNT}
     * ({@code app/cbl/COTRN00C.cbl:L52}) and {@code WS-PAGE-NUM} ({@code :L54}). Creating fields for
     * them would be dead code; they are recorded in the class documentation instead.
     */
    private static final class ScreenWorkArea {
        /**
         * Creates the work area with every member at its post-{@code INITIALIZE} value, which is the state
         * the legacy {@code WORKING-STORAGE SECTION} begins each task in. Declared explicitly rather than
         * left implicit so the surface is documented; it takes no argument and performs no work.
         */
        private ScreenWorkArea() {
            // Every member carries its initial value in its own declaration above, exactly as a COBOL
            // VALUE clause does, so there is nothing for this constructor to assign.
        }

        /** {@code WS-MESSAGE} — {@code app/cbl/COTRN00C.cbl:L38}. */
        private String message = SPACES;

        /** {@code WS-ERR-FLG} — {@code app/cbl/COTRN00C.cbl:L40}. */
        private boolean errFlagOn;

        /** {@code WS-TRANSACT-EOF} — {@code app/cbl/COTRN00C.cbl:L43}. */
        private boolean transactEof;

        /** {@code WS-IDX} — {@code app/cbl/COTRN00C.cbl:L53}. */
        private int idx;

        /** {@code CDEMO-CT00-TRNID-FIRST} — {@code app/cbl/COTRN00C.cbl:L63}. */
        private String trnIdFirst;

        /** {@code CDEMO-CT00-TRNID-LAST} — {@code app/cbl/COTRN00C.cbl:L64}. */
        private String trnIdLast;

        /** {@code CDEMO-CT00-PAGE-NUM} — {@code app/cbl/COTRN00C.cbl:L65}. */
        private int pageNum;

        /** {@code CDEMO-CT00-NEXT-PAGE-FLG} — {@code app/cbl/COTRN00C.cbl:L66-L68}. */
        private boolean nextPageYes;

        /** {@code CDEMO-CT00-TRN-SEL-FLG} — {@code app/cbl/COTRN00C.cbl:L69}. */
        private String trnSelFlg = SPACES;

        /** {@code CDEMO-CT00-TRN-SELECTED} — {@code app/cbl/COTRN00C.cbl:L70}. */
        private String trnSelected = SPACES;

        /** {@code TRNNAMEI} — {@code app/cpy-bms/COTRN00.CPY:24}. */
        private String trnName = SPACES;

        /** {@code TITLE01I} — {@code app/cpy-bms/COTRN00.CPY:30}. */
        private String title01 = SPACES;

        /** {@code CURDATEI} — {@code app/cpy-bms/COTRN00.CPY:36}. */
        private String curDate = SPACES;

        /** {@code PGMNAMEI} — {@code app/cpy-bms/COTRN00.CPY:42}. */
        private String pgmName = SPACES;

        /** {@code TITLE02I} — {@code app/cpy-bms/COTRN00.CPY:48}. */
        private String title02 = SPACES;

        /** {@code CURTIMEI} — {@code app/cpy-bms/COTRN00.CPY:54}, eight characters. */
        private String curTime = SPACES;

        /** {@code PAGENUMI} — {@code app/cpy-bms/COTRN00.CPY:60}. */
        private String pageNumDisplay = SPACES;

        /** {@code TRNIDINI} and {@code TRNIDINO}, which share storage. */
        private String trnIdIn = SPACES;

        /** {@code ERRMSGO} — {@code app/cpy-bms/COTRN00.CPY:728}. */
        private String errMsg = SPACES;

        /** {@code SEL0001I}..{@code SEL0010I}, positional. Never blanked by slot initialisation. */
        private final String[] sel = newBlankRow();

        /** {@code TRNID01I}..{@code TRNID10I}, positional. */
        private final String[] rowTranId = newBlankRow();

        /** {@code TDATE01I}..{@code TDATE10I}, positional. */
        private final String[] rowDate = newBlankRow();

        /** {@code TDESC01I}..{@code TDESC10I}, positional, twenty-six characters each. */
        private final String[] rowDesc = newBlankRow();

        /** {@code TAMT001I}..{@code TAMT010I}, positional, twelve characters each. */
        private final String[] rowAmt = newBlankRow();

        /** {@code TRAN-RECORD} — {@code app/cpy/CVTRA05Y.cpy:4}, the shared record area. */
        private Transaction tranRecord;

        /** {@code TRAN-ID} in its role as the browse record-identification field. */
        private String ridfld = LOW_VALUES_KEY;

        /** Whether the record-identification field holds high values, positioning past the end. */
        private boolean ridfldPastEnd;

        /** The retrieved browse window, consumed one record per read. */
        private List<Transaction> browseBuffer = List.of();

        /** The next window index a read will consume. */
        private int browsePosition;

        /** Whether the anchor row the priming read discards is still to be consumed. */
        private boolean phantomAnchorPending;

        /** The attention identifier of this turn, replacing {@code EIBAID}. */
        private AttentionIdentifier attentionIdentifier = AttentionIdentifier.ENTER;

        /** The program a transfer of control would have branched to. */
        private String navigationTarget;

        /** The transaction id a row selector chose. */
        private String selectedTransactionId;

        /** The field the legacy program placed the cursor on. */
        private String cursorField;

        /**
         * Returns a fully blank positional row array sized to the map's slot count.
         *
         * @return a new array of blank fields; never {@code null}
         */
        private static String[] newBlankRow() {
            final String[] row = new String[TransactionDto.PAGE_SIZE];
            for (int slot = 0; slot < row.length; slot++) {
                row[slot] = SPACES;
            }
            return row;
        }

        /**
         * Clears the symbolic map, reproducing {@code MOVE LOW-VALUES TO COTRN0AO} at
         * {@code app/cbl/COTRN00C.cbl:L114}. Unlike slot initialisation this does clear the row
         * selectors, because it blanks the whole map rather than individual row fields.
         */
        private void clearMap() {
            this.trnIdIn = SPACES;
            this.pageNumDisplay = SPACES;
            this.errMsg = SPACES;
            for (int slot = 0; slot < TransactionDto.PAGE_SIZE; slot++) {
                this.sel[slot] = SPACES;
                this.rowTranId[slot] = SPACES;
                this.rowDate[slot] = SPACES;
                this.rowDesc[slot] = SPACES;
                this.rowAmt[slot] = SPACES;
            }
        }
    }
}
